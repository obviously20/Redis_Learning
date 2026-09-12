package com.hmdp.utils;


import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock {

    private static final String LOCK_KEY_PREFIX = "lock:";
    // 锁的ID标识的前缀，区分不同的JVM中相同的进程ID
    private static final String ID_PREFIX = UUID.randomUUID().toString();

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    // 先加载lua脚本
    // 定义lua脚本对象常量（DefaultRedisScript<Long>:lua脚本对象，Long.class:lua脚本返回值的类型）
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    // 静态代码块，加载lua脚本
    static {
        // new DefaultRedisScript<>()：创建lua脚本对象
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        // setLocation：设置lua脚本的路径；ClassPathResource：表示类路径下的资源文件，即src/main/resources目录下的文件
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        // setResultType：设置lua脚本的返回值类型；Long.class：lua脚本返回值的类型为Long
        UNLOCK_SCRIPT.setResultType(Long.class);
    }


    @Override
    public boolean tryLock(long timeoutSeconds) {
//        // 获取当前线程的ID
//        long threadId = Thread.currentThread().getId();
        // 获取当前线程的ID+锁的ID标识的前缀(作为进程释放锁前的判断条件)
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 尝试获取锁
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_KEY_PREFIX + name, threadId, timeoutSeconds, TimeUnit.SECONDS);

        // 解决空指针异常，如果返回true，说明获取锁成功;返回false/null，说明获取锁失败
        return Boolean.TRUE.equals(success);
    }

//    @Override
//    public void unlock() {
//        // 先获取当前线程的ID+锁的ID标识的前缀(作为进程释放锁前的判断条件)
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 再去获取redis分布式锁中的标识值
//        String lockId = stringRedisTemplate.opsForValue().get(LOCK_KEY_PREFIX + name);
//        // 判断是否是当前线程的锁
//        if (threadId.equals(lockId)) {
//            // 是当前线程的锁
//            // 释放锁
//            stringRedisTemplate.delete(LOCK_KEY_PREFIX + name);
//        }
//    }

    @Override
    public void unlock() {
       // 使用lua脚本释放锁
       stringRedisTemplate.execute(UNLOCK_SCRIPT,
               Collections.singletonList(LOCK_KEY_PREFIX+name),
               ID_PREFIX+Thread.currentThread().getId());
    }
}