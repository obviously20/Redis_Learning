package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private static final String LOCK_KEY_PREFIX = "lock:";

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSeconds) {
        // 获取当前线程的ID
        long threadId = Thread.currentThread().getId();

        // 尝试获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + name, threadId+"", timeoutSeconds, TimeUnit.SECONDS);

        // 解决空指针异常，如果返回true，说明获取锁成功;返回false/null，说明获取锁失败
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        // 释放锁
        stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
    }
}
