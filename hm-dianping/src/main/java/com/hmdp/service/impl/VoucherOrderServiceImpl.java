package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    //设置lua脚本对象,泛型指定可以返回值
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        /*
         这个构造方法中可以传入字符串，
         字符串表示可以直接写入lua脚本内容，
         这种对于只需要编写一条很短的lua脚本来说是方便的
         但是我们的lua脚本长度较长，因此不直接使用这种方式
        */
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        /*
        设置lua脚本所在的路径，
         new ClassPathResource("unlock.lua")，可以从Resource目录下去查找脚本
        */
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        //设置脚本的返回值类型
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //阻塞队列
    private BlockingQueue<VoucherOrder> orderTasks =
            new ArrayBlockingQueue<>(1024 * 1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //项目初始化的时候就不断执行这个线程操作
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable {
        /**
         * 通过Stream消息队列实现取出订单操作
         */
        String queueName = "stream.orders";

        @Override
        public void run() {
            while (true) {
                try {
                    //1.获取消息队列中的订单信息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders
                    //这里返回list，是因为count可以指定读取消息的个数
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获得成功
                    if (list == null || list.isEmpty()) {
                        //若获取消息失败，则说明此时没有订单下单，则继续循环获取订单
                        continue;
                    }
                    //3.消息获取成功，成功获取订单，将list转换为 voucherOrder发送
                    //因为每次只返回一条消息（上面设置的count为0），因此只要get(0)即可
                    MapRecord<String, Object, Object> record = list.get(0);
                    //这里的recode，里面的String是消息的ID，即订单信息在Stream消息队列中创建时自动生成的id
                    //后面的Object，分别对应了k和v
                    Map<Object, Object> values = record.getValue();//通过getValue可以获得保存的订单信息的键值对
//                    通过hutool工具，将values转换为VoucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4.一旦订单信息创建成功，就执行下单逻辑
                    handleVoucherOrder(voucherOrder);

                    //5.将完成下单的订单消息进行ACK确认
                    stringRedisTemplate.opsForStream().
                            acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //若上述下单流程中出现异常，
                    // 可能会导致订单消息未被ACK，因此订单消息可能存在了Pending-List中
                    //因此，要获取Pending-List中的订单消息再次执行业务逻辑
                    handlePendingList();
                }
            }


            //下面的代码是通过阻塞队列取出订单信息实现下单操作流程
//            while(true){
//                try {
//                    //从阻塞队列中取出订单
//                    VoucherOrder voucherOrder = orderTasks.take();
//                    handleVoucherOrder(voucherOrder);
//                } catch (InterruptedException e) {
//                    //若并没有订单，则直接被拦截，
//                    // 或者并未下单的时候，proxy为空，会抛出一些异常也会被拦截
//                    log.error("处理订单异常",e);
//                }
//            }
        }

        private void handlePendingList() {
            while (true) {
                //不断循环，直到异常全部处理完成
                try {
                    //从PendingList中读取消息：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //如果pendinglist中没有消息，说明没有订单异常，直接结束循环
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    //如果有订单异常，那么取出该订单，再次进行下单操作
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理Pending-List订单出现异常", e);
                    //这里出现异常了的话，就重新再去Pending-List中去取消息，没必要做其他处理，也就是直接继续循环即可
                    //当然，如果出现Pending-List异常了，不希望一直反复报这个log日志，就可以让它休眠一会。
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interruptedException) {
                        interruptedException.printStackTrace();
                    }
                }
            }
        }

    }


    //处理下单业务逻辑
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
//        使用Redisson框架的分布式锁实现一人一单功能
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //设置无参，若锁未获取成功，就直接返回false，符合当前业务需求。未来若需要能够重试获取锁，可以设置锁的延迟时间
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
        }
//        /*
//        因为String是不可变的，那么通过toString方法每次调用都会产生一个新的对象
//        这里需要再调用一下 intern()方法，它会先去内存中查找是否存在着相同内容的对象，
//         若有则直接返回找到的String对象，没有再创建新的String对象
//        */
//        synchronized (userId.toString().intern()){
            /*createVoucherOrder设置了事务操作
            然而同类中调用本类方法会出现事务失效,
            因此我们要获取代理对象来调用createVoucherOrder方法获取返回值
            要实现事务，必须使用Spring代理的对象
             */
        try {
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            //如果出现异常，也要释放锁
            lock.unlock();
        }
    }

    //由于代理也是使用了ThreadLocal，无法在子线程中保留，因此这里将其设为成员变量，直接让子线程携带proxy
    private IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {

        /**
         * 使用Stream消息队列实现保存订单
         */

        //获取当前用户的id
        Long userId = UserHolder.getUser().getId();
        //获取订单id，便于传入给lua脚本
        long orderId = redisWorker.nextId("order");
        //将秒杀券库存和订单校验放到lua脚本中实现原子性，同时将其放到redis中去执行
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(), String.valueOf(orderId));
        //这里上面三个参数要转换成 String ,因为Redis处理是通过字符串进行

        int r = result.intValue();
        if (r != 0) {
            //若结果不为0，说明库存不足或者用户已经购买
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }
        //由于订单信息已经保存在Stream消息队列中，因此这里根本不需要编写任何业务逻辑
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);


        /**
         * 使用阻塞队列实现订单信息保存的代码
         */
//        //获取当前用户的id
//        Long userId = UserHolder.getUser().getId();
//        //将秒杀券库存和订单校验放到lua脚本中实现原子性，同时将其放到redis中去执行
//        Long result = stringRedisTemplate.execute(
//                SECKILL_SCRIPT,
//                Collections.emptyList(),
//                voucherId.toString(),
//                userId.toString());
//
//        int r = result.intValue();
//        if(r!=0){
//            //若结果不为0，说明库存不足或者用户已经购买
//            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
//        }
//        //有购买资格，把下单信息放到阻塞队列
//        VoucherOrder voucher = new VoucherOrder();
//        long orderId = redisWorker.nextId("order");
//        voucher.setId(orderId);
//        voucher.setUserId(userId);
//        voucher.setVoucherId(voucherId);
//        //保存阻塞队列
//        orderTasks.add(voucher);
//
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
//        return Result.ok(orderId);
//        //查询优惠券
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        //判断秒杀是否开始
//        if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
//            return Result.fail("秒杀尚未开始！");
//        }
//        //判断秒杀是否结束
//        if(voucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("秒杀已经结束！");
//        }
//        //库存不足
//        if(voucher.getStock() < 1){
//            return Result.fail("秒杀券库存不足");
//        }
//        UserDTO user = UserHolder.getUser();
//        Long userId = user.getId();
//        /*
//        这里对整个方法加上了悲观锁，因为若没有加，会出现多个线程同时访问，
//         而第一个线程还未更新订单，同时其他线程查询到数据库中的count仍然是0，
//         因此也往下更新了数据库操作，造成了线程安全问题
//        */
//
////        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//
//        //使用Redisson框架的分布式锁实现一人一单功能
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        //设置无参，若锁未获取成功，就直接返回false，符合当前业务需求。未来若需要能够重试获取锁，可以设置锁的延迟时间
//        boolean isLock = lock.tryLock();
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
////        /*
////        因为String是不可变的，那么通过toString方法每次调用都会产生一个新的对象
////        这里需要再调用一下 intern()方法，它会先去内存中查找是否存在着相同内容的对象，
////         若有则直接返回找到的String对象，没有再创建新的String对象
////        */
////        synchronized (userId.toString().intern()){
//            /*createVoucherOrder设置了事务操作
//            然而同类中调用本类方法会出现事务失效,
//            因此我们要获取代理对象来调用createVoucherOrder方法获取返回值
//            要实现事务，必须使用Spring代理的对象
//             */
//        try{
//            IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId,userId);
//        }finally {
//            //如果出现异常，也要释放锁
//            lock.unlock();
//        }
//        //获取锁成功，就执行业务逻辑
//
////        }

    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long id = voucherOrder.getUserId();
        //判断一下数据库中是否存在相同userId,相同voucherId的订单，若有，则直接返回
        int count = query()
                .eq("user_id", id)
                .eq("voucher_id", voucherId).count();

        if (count > 0) {
            log.error("一个用户只能购买一张秒杀券");
        }

        //更新库存
        //使用乐观锁，乐观锁适合更新操作
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId).gt("stock", 0)
                .update();

        if (!success) {
            log.error("库存不足");
        }

//        //创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        long orderId = redisWorker.nextId("order");
//        //订单id
//        voucherOrder.setId(orderId);
//        //用户id
//        Long Id = UserHolder.getUser().getId();
//        voucherOrder.setUserId(Id);
//        //代金券id
//        voucherOrder.setVoucherId(voucherId);
        //这里直接保存即可，因为我们已经在前面创建好了voucherOrder
        save(voucherOrder);
    }
}
