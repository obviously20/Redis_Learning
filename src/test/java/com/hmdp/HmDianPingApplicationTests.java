package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    @Resource
    private CacheClient cacheClient;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    // 测试保存商铺信息到redis，在实际业务中，这些热点数据需要我们在后台提前去导入到redis中（预热），但现在没有后台，所以这里直接测试保存一个商铺信息
    @Test
    void testSaveShop() {
//        shopService.saveShop2Redis(1L, 10L);
        // 先查询数据库
        Shop shop = shopService.getById(1L);

        // 再插入缓存
        cacheClient.setLogicalExpire(RedisConstants.CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS);
    }

    @Test
    void loadShopData() {
        // 1.查询店铺信息（如果店铺有很多，可以分批查询）
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeId一致的放到一个集合（1.stream流中collect的Collectors.groupingBy(Shop::getTypeId):根据typeId分组，妙用）
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis
        // map.entrySet():获取map中所有的key-value对：类型type(key),同类型的店铺的列表(value)
        // eg: typeId=1, value=[shop1, shop2, shop3]
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1.获取类型id
            Long typeId = entry.getKey();
            // 根据类型id拼接redis的key,后续根据类型id查询店铺时，直接根据key查询即可，不用再额外进行分组... where typeId=1
            String key = SHOP_GEO_KEY + typeId;
            // 3.2.获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            // 一：直接批量写入，效率高，故定义一个集合，用来存储店铺信息（集合的类型为RedisGeoCommands.GeoLocation<String>）
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3.写入redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                // 这个要一条一条写入，不能批量写入，效率低
                // stringRedisTemplate.opsForGeo().add(key, new Point(shop.getX(), shop.getY()), shop.getId().toString());

                // 二：先把店铺信息添加到集合中（集合的类型为RedisGeoCommands.GeoLocation<String>）
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),// member
                        new Point(shop.getX(), shop.getY())// location
                ));
            }
            // 三：再批量写入redis
            stringRedisTemplate.opsForGeo().add(key, locations);//批量插入 key 和 locations 列表中的所有元素（GEO提供的另一种写入方式）
        }
    }

}