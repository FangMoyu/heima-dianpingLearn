package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.lang.func.Func;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONNull;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 缓存工具类
 * 封装了所有缓存操作
 * 可以实现直接调用
 * 实现缓存穿透、缓存击穿
 */
@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;
    //构造器实现调用时外部注入stringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //保存数据到缓存
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //发送过来的时间可能不是以秒为单位，因此这里要转换成秒unit.toSeconds(time)
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 实现缓存穿透通用接口
     * @param keyPrefix 指定key前缀
     * @param id 传入的数据库数据对应id
     * @param type 传输给前端的值
     * @param dbFallback 用于数据库操作的函数
     * @param time 设置缓存过期时间
     * @param unit 缓存过期时间单位
     * @param <R> 返回前端类型，采用泛型，可以对数据库设置的任意pojo进行使用
     * @param <ID> id不一定就是某一个数值类型，因此也使用泛型来表示
     * @return
     */
    public <R,ID> R queryByPassThrough(String keyPrefix, ID id, Class<R> type,
                                       Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //从redis中查询id对应数据是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //若存在则将json转为shop对象并返回给前端
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //防止缓存穿透，判断返回的 json 数据是否为空字符串，若是，则说明数据库中不存在这条数据
        if(json!=null){
            return null;
        }
        //若不存在，则根据id查询数据库
        //这里使用了函数式编程，通过Function.apply()，外部可以指定任意的函数来调用这个逻辑.
        R r = dbFallback.apply(id);
        //若数据库中无对应数据，则先将空字符串存入缓存，避免缓存穿透，再返回错误信息
        if(r == null){
            //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        //若数据库中有对应数据，则将其放入redis数据库中作为缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r));
        stringRedisTemplate.expire(key,time, unit);
        return r;
    }

    //创建一个线程池，里面包含10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);


    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                           Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //从redis中查询id对应数据是否存在
        String RedisDataJson = stringRedisTemplate.opsForValue().get(key);
        //未命中，说明数据库中没有该数据，直接返回空
        if(StrUtil.isBlank(RedisDataJson)){
            return null;
        }
        //缓存命中，获取缓存数据并转为RedisData对象
        RedisData redisData = JSONUtil.toBean(RedisDataJson, RedisData.class);
        //判断缓存是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject)  redisData.getData(), type);
        if(expireTime.isAfter(LocalDateTime.now())){
            //若未过期，直接返回
            return r;
        }
        //若逻辑过期，尝试获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        //若未获得锁，直接返回店铺信息
        if(!tryLock(LockKey)){
            return r;
        }
        //若线程获得锁，则创建新线程，通过lambda表达式执行重建缓存逻辑
        CACHE_REBUILD_EXECUTOR.submit(()-> {
            try {
                R r1 = dbFallback.apply(id);
                Thread.sleep(200);
                setWithLogicalExpire(key, r1, time, unit);

            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //无论是否出异常，都要释放锁
                unlock(LockKey);
            }
        });
        //当前线程依然返回旧的shop对象，但是此时缓存已经重建
        return r;
    }

    public boolean tryLock(String key){
        //使用 setnx 对应的 setIfAbsent 方法来设置键值，并设置延迟时间。避免死锁
        //这里的value目前随意，目前还用不上。就设为1吧
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里返回的是一个包装类，为了防止出现空指针，这里我们使用hutool工具返回。
        return BooleanUtil.isTrue(flag);
    }
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
