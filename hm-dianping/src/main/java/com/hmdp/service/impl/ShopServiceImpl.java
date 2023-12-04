package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private CacheClient cacheClient;

    /**
     * 根据店铺id查询缓存及数据库中的内容
     *
     * @param id 店铺id
     * @return 店铺的信息
     */
    @Override
    public Result queryById(Long id) {
        //先注销掉缓存穿透的操作
//        return queryByPassThrough(id);

        //执行互斥锁缓存击穿逻辑
//        Shop shop = queryWithMutex(id);
        //执行逻辑过期的缓存击穿解决方案
        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
        //若返回的是null，说明店铺不存在
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回shop对象
        return Result.ok(shop);
    }

    /**
     * 缓存击穿实现，内部包含缓存穿透
     *
     * @param id 查询的店铺id
     * @return 店铺的信息
     */
    public Shop queryWithMutex(Long id) {
        //查询缓存，获取shop的json数据
        String key = CACHE_SHOP_KEY + id;
        String ShopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(ShopJson)) {
            //存在就直接返回Shop对象
            return JSONUtil.toBean(ShopJson, Shop.class);
        }
        //不存在，判断是否为null，防止缓存穿透
        if (ShopJson != null) {
            //为null，说明数据库中并没有该数据，返回null，表示店铺不存在
            return null;
        }
        Shop shop = null;
        //因为Thread.sleep(50)会抛出一个检查型异常，这里我们将整个缓存击穿的流程放入 tyr-catch-finally操作
        //因为释放锁的操作无论是否抛出异常都需要执行，因此这里会使用这个结构
        try {
            //缓存未命中，加给每家店铺各自加互斥锁，毕竟访问不同的店家都是一个线程
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            //若未得到锁，就让它进入休眠，休眠结束就重新执行查询缓存
            if (!isLock) {
                Thread.sleep(50);
                //这里要加return，否则递归结束后会继续往下执行数据库操作
                return queryWithMutex(id);
            }
            //获得了互斥锁,查询数据库之前，要再次查询缓存，
            // 可能由于当前线程前面并没有查到缓存，但是刚好此时别的线程更新了缓存并释放了锁
            // 且释放的锁被当前线程获得，因此就会出现线程得到了锁，然而缓存已经更新的情况。
            // 因此要再查询一次缓存，可以节省数据库查询
            ShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            //从缓存中获取的数据再做一下判断，然后返回
            if (StrUtil.isNotBlank(ShopJson)) {
                return JSONUtil.toBean(ShopJson, Shop.class);
            }
            //不存在，判断是否为null，防止缓存穿透
            if (ShopJson != null) {
                //为null，说明数据库中并没有该数据，返回null，表示店铺不存在
                return null;
            }
            //若是缓存中依然没有数据，就去查询数据库
            shop = getById(id);
            if (shop == null) {
                //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //若数据库中存在，就更新缓存，并返回shop对象
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop));
            stringRedisTemplate.expire(key, CACHE_SHOP_TTL, TimeUnit.HOURS);
            //返回shop对象
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            //释放锁
            unlock(LOCK_SHOP_KEY + id);
        }
        return shop;
    }

    //创建一个线程池，里面包含10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);

    public Shop queryWithLogicalExpire(Long id) {
        String key = CACHE_SHOP_KEY + id;
        //从redis中查询id对应数据是否存在
        String RedisDataJson = stringRedisTemplate.opsForValue().get(key);
        //未命中，说明数据库中没有该数据，直接返回空
        if (StrUtil.isBlank(RedisDataJson)) {
            return null;
        }
        //缓存命中，获取缓存数据并转为RedisData对象
        RedisData redisData = JSONUtil.toBean(RedisDataJson, RedisData.class);
        //判断缓存是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())) {
            //若未过期，直接返回
            return shop;
        }
        //若逻辑过期，尝试获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        //若未获得锁，直接返回店铺信息
        if (!tryLock(LockKey)) {
            return shop;
        }
        //若线程获得锁，则创建新线程，通过lambda表达式执行重建缓存逻辑
        CACHE_REBUILD_EXECUTOR.submit(() -> {
            try {
                saveShop2Redis(id, 20L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //无论是否出异常，都要释放锁
                unlock(LockKey);
            }
        });
        //当前线程依然返回旧的shop对象，但是此时缓存已经重建
        return shop;
    }

    /**
     * 保存封装了逻辑过期字段的对象
     *
     * @param id
     * @param expireTime
     */
    public void saveShop2Redis(Long id, Long expireTime) throws InterruptedException {
        Shop shop = getById(id);
        Thread.sleep(200);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 缓存穿透解决
     * 对数据库中不存在的数据给缓存存储一个空字符串
     *
     * @param id 店铺id
     * @return 店铺信息
     */
//    public Result queryByPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY+id;
//        //从redis中查询id对应数据是否存在
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        //若存在则将json转为shop对象并返回给前端
//        if(StrUtil.isNotBlank(shopJson)){
//            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
//            return Result.ok(shop);
//        }
//        //防止缓存穿透，判断返回的 json 数据是否为空字符串，若是，则说明数据库中不存在这条数据
//        if(shopJson!=null){
//            return Result.fail("店铺不存在");
//        }
//        //若不存在，则查询数据库
//        Shop shop = getById(id);
//        //若数据库中无对应数据，则先将空字符串存入缓存，避免缓存穿透，再返回错误信息
//        if(shop == null){
//            //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return Result.fail("店铺不存在");
//        }
//        //若数据库中有对应数据，则将其放入redis数据库中作为缓存
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
//        stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.HOURS);
//        return Result.ok(shop);
//    }
    public boolean tryLock(String key) {
        //使用 setnx 对应的 setIfAbsent 方法来设置键值，并设置延迟时间。避免死锁
        //这里的value目前随意，目前还用不上。就设为1吧
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里返回的是一个包装类，为了防止出现空指针，这里我们使用hutool工具返回。
        return BooleanUtil.isTrue(flag);
    }

    public void unlock(String key) {
        stringRedisTemplate.delete(key);
    }


    /**
     * 缓存更新操作
     *
     * @param shop
     * @return
     */
    @Override
    public Result update(Shop shop) {
        //获取前端发送来的店铺id，并判断是否存在
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + id;
        //根据查询的id对数据库进行更新操作
        updateById(shop);
        //删除缓存
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据坐标查询，只要有一个参数不存在，就按照原来的查询方式，直接返回商户
        if (x == null || y == null) {
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        //2.计算分页参数，用来实现分页功能，
        // 由于这次并不像之前的分页操作，需要更新数据的频率很低，因此我们直接根据角标来做
        //current是页码，那么第一页的起始是0，终点是 current*每页显示的数目
        //后续每页的起始是 (current-1) * 每页显示的数目，终点仍然是 current * 每页显示的数目
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        //3.查询Redis，按照距离远近查询
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y)
                        , new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs
                                .newGeoSearchArgs().includeDistance().limit(end));
        //这里只能 limit(end)，意味着我们使用分页时，不能设置起始位置，只能设置终止位置，
        // 那么随着前端的current发送的值变大，那么这个查询的数据就越多，
        // 因此为了解决分页问题，我们需要将返回的数据手动进行分割。
        if(results == null){
            //如果查询没有结果，就直接返回
            return Result.ok(Collections.emptyList());
        }
        //results.getContent();可以得到查询到的GEO结构存储的shopid和经纬度的集合
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //我们查询的结果list也就是商户的集合，
        // 如果商户总数小于分页的起始页，那么就相当于这页没有商户，因此有下面的情况：
        // 如果list的长度小于起始页位置，相当于跳过了所有的数据
        // 那么后续查询会出现空指针。因此这里加上一个判断。
        if(list.size()<from){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = new ArrayList<>(list.size());//用来存储shopId
        Map<String,Distance> distanceMap = new HashMap<>(list.size());
        //通过skip函数截断掉from前面的元素，实现分页功能
        list.stream().skip(from).forEach(result -> {
            //获取店铺Id,result.getContent()可以得到一个GeoLocation
            // 这里的GeoLocation存储的是每个 member 和其 score 值，也就是shopId和经纬度转化的score
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //根据id查询Shop
        String idStr = StrUtil.join(",",ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for(Shop shop : shops){
            //给按照指定顺序查询到的 shop 赋 Distance 值
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //返回结果
        return Result.ok(shops);
    }
}
