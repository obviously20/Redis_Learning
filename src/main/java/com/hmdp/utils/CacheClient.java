package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    // 定义一个线程池，用于缓存重建
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 设置缓存，有默认过期时间TTL
     * @param key
     * @param value
     * @param expireTime
     * @param unit
     */
    public void set(String key, Object value, long expireTime, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), expireTime, unit);
    }

    /**
     * 设置缓存,有逻辑过期时间
     * @param key
     * @param value
     * @param expireTime
     * @param unit
     */
    public void setLogicalExpire(String key, Object value, long expireTime, TimeUnit unit){
        // 设置缓存,有逻辑过期时间
        // 先封装value为RedisData对象
        RedisData redisData = new RedisData();
        // unit.toSeconds(expireTime),unit转换为秒级,再添加到当前时间;eg:expireTime=10,unit=TimeUnit.MINUTES,则expireTime=10*60秒
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(expireTime)));
        redisData.setData(value);
        // 缓存RedisData对象
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 缓存穿透问题解决
     * @param keyPrefix
     * @param id
     * @param clazz
     * @param dbFallback
     * @param expireTime
     * @param unit
     * @return
     * @param <T>
     * @param <ID>
     */
    public <T,ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> clazz, Function<ID,T> dbFallback ,long expireTime, TimeUnit unit) {
        String key = keyPrefix + id;
        //1 从redis中查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2 判断缓存是否存在，存在直接返回缓存数据
        if (StrUtil.isNotBlank(json)) {
            T t = JSONUtil.toBean(json, clazz);
            return t;
        }
        // 判断缓存命中的是否为空，为空直接返回空(前面已经判断了，这里不为null就是空)
        if (json != null) {
            return null;
        }
        //3 从数据库中查询商铺信息
        T t = dbFallback.apply(id);
        //4 查询失败，返回提示
        if (t == null) {
            // 避免缓存穿透，缓存空，过期时间为2分钟
            stringRedisTemplate.opsForValue().set(key, "", expireTime, unit);
            return null;
        }

        //5 查询成功，写入缓存
        this.set(key, t, expireTime, unit);

        //6 返回查询结果
        return t;
    }




    public <T,ID> T queryWithLogicalExpire(String keyPrefix, ID id, Class<T> clazz, Function<ID,T> dbFallback ,long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回数据
            return t;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // Double Check：再次检查缓存是否已被其他线程重建
            String cacheJson = stringRedisTemplate.opsForValue().get(key);
            RedisData cacheRedisData = JSONUtil.toBean(cacheJson, RedisData.class);
            LocalDateTime cacheExpireTime = cacheRedisData.getExpireTime();

            // 如果缓存未过期，说明其他线程已经重建了，无需重复重建
            if(cacheExpireTime.isAfter(LocalDateTime.now())) {
                unlock(lockKey);
            } else {
                // 缓存仍然过期，提交异步任务重建
                CACHE_REBUILD_EXECUTOR.submit( ()->{
                    try{
                        //重建缓存
                        // 从数据库中查询数据
                        T t1 = dbFallback.apply(id);
                        // 再将数据写入缓存，逻辑过期
                        this.setLogicalExpire(key, t1, time, unit);
                    }catch (Exception e){
                        throw new RuntimeException(e);
                    }finally {
                        unlock(lockKey);
                    }
                });
            }
        }
        // 6.4.返回过期的数据
        return t;
    }

    /**
     * 尝试获取互斥锁
     * @param key
     * @return
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁
     * @param key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


}
