package com.hmdp;

import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private IShopService shopService;

    // 测试保存商铺信息到redis，在实际业务中，这些热点数据需要我们在后台提前去导入到redis中（预热），但现在没有后台，所以这里直接测试保存一个商铺信息
    @Test
    void testSaveShop() {
        shopService.saveShop2Redis(1L, 10L);
    }

}