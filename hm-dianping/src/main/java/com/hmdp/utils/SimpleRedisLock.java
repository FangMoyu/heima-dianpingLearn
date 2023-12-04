package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    //作为锁的名称
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    //使用UUID+线程id来作为value的标识
    private static final String uuid = UUID.randomUUID().toString();
    //设置lua脚本对象,泛型指定可以返回值
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        /*
         这个构造方法中可以传入字符串，
         字符串表示可以直接写入lua脚本内容，
         这种对于只需要编写一条很短的lua脚本来说是方便的
         但是我们的lua脚本长度较长，因此不直接使用这种方式
        */
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        /*
        设置lua脚本所在的路径，
         new ClassPathResource("unlock.lua")，可以从Resource目录下去查找脚本
        */
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        //设置脚本的返回值类型
        UNLOCK_SCRIPT.setResultType(Long.class);

    }

    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX+name;
        //获取线程标识
        String value = uuid + Thread.currentThread().getId();

        //进行设置字段操作
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);

        //这里将其转换为boolean类型返回
        return BooleanUtil.isTrue(success);

    }

    @Override
    public void unlock() {
        //判断一下当前的标识是否一致
        long ThreadId = Thread.currentThread().getId();
        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //执行lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                uuid+ThreadId);
    }

    /**
     * 未使用lua脚本实现释放锁逻辑
     */
//    @Override
//    public void unlock() {
//        //判断一下当前的标识是否一致
//        long ThreadId = Thread.currentThread().getId();
//        String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//        //满足标识相同，就释放锁
//        if((uuid+ThreadId).equals(value)){
//            //释放锁
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
//    }
}
