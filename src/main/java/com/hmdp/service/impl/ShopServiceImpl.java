package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 根据id查询商铺信息
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //解决缓存穿透问题
//        Shop shop = queryWithPassThrough(id);
        //解决缓存击穿问题
        Shop shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("商铺不存在");
        }
        return Result.ok(shop);
    }

    /**
     * 根据id查询商铺信息，用互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id)  {
        String key = CACHE_SHOP_KEY + id;
        // 1、从redis中查询商铺缓存
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        // 2、判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            // 存在,直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        //判断命中的值是否是空值
        if (shopJson != null) {
            //返回一个错误信息
            return null;
        }
        // 4.实现缓存重构
        //4.1 获取互斥锁
        String lockKey = "lock:shop:" + id;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2 判断否获取成功
            if(!isLock){
                //4.3 失败，则休眠重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            //4.4 成功，Double Check 缓存是否存在
            String cacheShop = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(cacheShop)) {
                return JSONUtil.toBean(cacheShop, Shop.class);
            }
            if (cacheShop != null) {
                return null;
            }
            // 缓存仍然不存在，根据id查询数据库
            shop = getById(id);
            // 5.不存在，返回错误
            if(shop == null){
                //将空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.写入redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);

        }catch (Exception e){
            throw new RuntimeException(e);
        }
        finally {
            //7.释放互斥锁
            unlock(lockKey);
        }
        return shop;
    }

    /**
     * 根据id查询商铺信息，解决缓存穿透问题
     * @param id
     * @return
     */
    private Shop queryWithPassThrough(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //1 从redis中查询缓存
        String cacheShop = stringRedisTemplate.opsForValue().get(key);
        //2 判断缓存是否存在，存在直接返回缓存数据
        if (StrUtil.isNotBlank(cacheShop)) {
            Shop shop = JSONUtil.toBean(cacheShop, Shop.class);
            return shop;
        }
        // 判断缓存命中的是否为空，为空直接返回空(前面已经判断了，这里不为null就是空)
        if (cacheShop != null) {
            return null;
        }
        //3 从数据库中查询商铺信息
        Shop shop = getById(id);

        //4 查询失败，返回提示
        if (shop == null) {
            // 避免缓存穿透，缓存空，过期时间为2分钟
            stringRedisTemplate.opsForValue().set(key, "", RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }

        //5 查询成功，写入缓存
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //6 返回查询结果
        return shop;
    }

    /**
     * 更新商铺信息
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 先检查商铺的id是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("商铺id不能为空");
        }
        // 修改数据库
        updateById(shop);

        // 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);

        return Result.ok();
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