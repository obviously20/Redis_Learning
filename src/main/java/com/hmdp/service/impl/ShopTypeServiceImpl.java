package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    /**
     * 查询所有商铺类型添加缓存支持
     * @return
     */
    @Override
    public Result queryList() {
        // 1. 从缓存中查询是否有数据
        String key = RedisConstants.CACHE_SHOP_KEY + "type";
        String cacheJson = redisTemplate.opsForValue().get(key);

        //2 有直接返回缓存数据
        if (cacheJson != null && !cacheJson.isEmpty()) {
            // 将缓存中的字符串转换为List<ShopType>
            List<ShopType> typeList = JSONUtil.toList(cacheJson, ShopType.class);
            return Result.ok(typeList);
        }
        //3 无从数据库中查询数据
        List<ShopType> typeList = this.query().orderByAsc("sort").list();

        //4 若数据库中也没有数据，返回空列表
        // TODO 后期优化缓存穿透问题
        if (typeList.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //5 若数据库中也有数据，写入缓存
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(typeList), RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);

        //6 返回缓存数据
        return Result.ok(typeList);
    }
}
