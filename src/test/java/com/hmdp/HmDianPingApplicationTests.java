package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.TimeUnit;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;


    // 测试保存商铺信息到redis，在实际业务中，这些热点数据需要我们在后台提前去导入到redis中（预热），但现在没有后台，所以这里直接测试保存一个商铺信息
    @Test
    void testSaveShop() {
//        shopService.saveShop2Redis(1L, 10L);
        // 先查询数据库
        Shop shop = shopService.getById(1L);

        // 再插入缓存
        cacheClient.setLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

}