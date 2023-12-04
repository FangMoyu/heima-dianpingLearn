package com.hmdp;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@Slf4j
@SpringBootTest
public class RedissionTest {
    @Resource
    private RedissonClient redissonClient;
    private RLock lock;

    @BeforeEach
    void setUp(){
        lock = redissonClient.getLock("order");
    }

    @Test
    void method1(){

        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("获取锁失败...1");
            return;
        }
        try{
            log.info("获取锁成功...1");
            method2();
            log.info("开始执行业务逻辑...1");
        }finally {
            log.warn("准备释放锁...1");
            lock.unlock();
        }
    }
    void method2(){
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("获取锁失败...2");
            return;
        }
        try{
            log.info("获取锁成功...2");
            log.info("执行业务逻辑....2");
        }finally {
            log.info("准备释放锁...2");
            lock.unlock();
        }
    }
}
