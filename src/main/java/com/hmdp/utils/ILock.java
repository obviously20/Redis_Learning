package com.hmdp.utils;

public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSeconds 超时时间，单位秒,超时后自动释放锁
     * @return true 成功获取锁，false 失败获取锁
     */
    boolean tryLock(long timeoutSeconds);

    /**
     * 释放锁
     */
    void unlock();
}
