package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;
@Component
public class RedisWorker {
    /**
     * 开始时间戳
     */
    private static final long BEGIN_TIME_STAMP =1672531200L;

    private static final int COUNT_BITS =32;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //要有构造函数，因为后面测试我们要调用Spring的依赖注入，而没有构造函数就无法进行使用
    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public long nextId(String keyPrefix){
        //生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowTime = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowTime - BEGIN_TIME_STAMP;

        //生成序列号
        //这里的设计思路是通过redis键的层级结构实现一个分类，有利于未来我们直接选择对应的日期查看缓存ID
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        /*
        让序列号实现自增长，redis的自增长是满足原子性的。因此可以直接使用
        这里的count选了long类型，并不会出现空指针而拆箱失败，
        因为redis在给不存在的键进行自增时，会自动添加到redis的数据库中
         */
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        /*
        拼接返回全局ID
        将生成时间戳和序列号进行二进制位的拼接，根据图片上的要求
        第一步，要让时间戳左移位32
        （这里的32是按照图片的要求来的，我们这里设置成一个变量，如果未来需求发生了改变，直接改变量即可）
        然后再与序列号进行按位或操作
         */
        return timestamp << COUNT_BITS | count;
    }
}
