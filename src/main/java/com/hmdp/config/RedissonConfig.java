package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient() {
        // 配置Redisson
        Config config = new Config();
        // 配置单节点Redis;若是集群Redis，需要配置集群节点.useClusterServers()
        config.useSingleServer().setAddress("redis://localhost:6379").setPassword("123456");
        // 创建Redisson客户端对象并返回
        return Redisson.create(config);
    }

}
