package com.hmdp.utils;

import lombok.Data;

import java.time.LocalDateTime;

// 定义一个RedisData类，用于封装缓存数据（工具类）
@Data
public class RedisData {
    // 设置一个逻辑过期时间
    private LocalDateTime expireTime;
    // 缓存的数据
    private Object data;
}