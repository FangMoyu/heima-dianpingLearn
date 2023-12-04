package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.SHOP_GEO_KEY;

@SpringBootTest
class HmDianPingApplicationTests{
    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private CacheClient cacheClient;
    @Test
    void testSaveShop() throws InterruptedException {
        shopService.saveShop2Redis(1L,10L);
    }
    @Resource
    RedisWorker redisWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void TestHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for(int i = 0 ;i < 1000000 ; i++){
            j = i % 1000;
            values[j] = "user_" + i;
            if(j == 999){
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("个数为："+count);
    }

    @Test
    void loadShopData(){
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
//        2.把店铺分组，按照 typeId分组，typeId一致的放到一个集合中
//        这里通过一个stream流，将相同typeId的数据分到一组，用一个 List<Shop>来存储
//        当然，也可以一个个遍历实现。只是下面这种写法比较优雅(
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
//        3.分批次写入 Redis
        for(Map.Entry<Long,List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            String key = SHOP_GEO_KEY + typeId;
            //获取同类型店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //将其保存在 GeoLocation 中，这个类可以存储商户的经纬度和其对应的member，这里我们member设置为商户id
            for (Shop shop : value){
                //将商户的id作为member，将经纬度设定的Point作为score存储进GeoLocation，
                // 然后保存在一个GeoLocation形成的list集合里
//                这样就可以生成一个list，里面存储的是同一类型的所有商户的集合
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(),shop.getY())
                ));
                //依次通过循环，将同类型的商户的集合添加到GEO中
                stringRedisTemplate.opsForGeo().add(key,locations);
            }
        }
    }

    ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
   void testWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task =() ->{
            for(int i = 0;i<100;i++){
                long id = redisWorker.nextId("order");
                System.out.println("id= " + id);
            }
            latch.countDown();
        };
        long begin = System.nanoTime();
        for(int i = 0 ;i < 300; i++){
            es.submit(task);
        }
        latch.await();
        long end = System.nanoTime();

        System.out.println("执行时间= "+ (end-begin));
    }


}
