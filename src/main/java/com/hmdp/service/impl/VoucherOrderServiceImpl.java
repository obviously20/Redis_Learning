package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    // 先加载lua脚本
    // 定义lua脚本对象常量（DefaultRedisScript<Long>:lua脚本对象，Long.class:lua脚本返回值的类型）
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    // 静态代码块，加载lua脚本
    static {
        // new DefaultRedisScript<>()：创建lua脚本对象
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // setLocation：设置lua脚本的路径；ClassPathResource：表示类路径下的资源文件，即src/main/resources目录下的文件
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        // setResultType：设置lua脚本的返回值类型；Long.class：lua脚本返回值的类型为Long
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // 二.1:创建基于jdk的阻塞队列，存储在内存中
    private BlockingQueue<VoucherOrder> orderTasks =new ArrayBlockingQueue<>(1024 * 1024);
    // 二.2:异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // 二.4: 在类初始化之后执行，因为当这个类初始化好了之后，随时都是有可能要执行的,所以需要在类初始化之后执行(先准备好)
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 二.3: 用于线程池处理的任务
    // 当初始化完毕后，就会去从对列中去拿信息
    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    // 从阻塞队列中获取订单
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 处理订单
                    handelVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                }
            }
        }

        // 二.5: 完成处理订单方法
        private void handelVoucherOrder(VoucherOrder voucherOrder) {
            // 获取用户id
            Long userId = voucherOrder.getUserId();
            // 获取自定义的锁（创建锁对象）
//            SimpleRedisLock lock = new SimpleRedisLock("order"+userId, stringRedisTemplate);
            // 获取Redisson的锁对象：这里获取锁是二次确认，以防前面redis判断失败（当然理论上是不可能判断失败，但是保险起见）
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 尝试获取锁
            boolean isLock = redisLock.tryLock();
            // 判断是否成功获取锁
            if(!isLock){//redisson的这个，无参表名非阻塞式获取锁
                // 获取锁失败，说明有其他线程正在购买/已购买过，不能重复购买
                log.error("用户{}购买优惠券{}失败，因为有其他线程正在购买",userId,voucherOrder.getVoucherId());
            }
            try {
                // 调用创建订单方法
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 手动释放锁
                redisLock.unlock();
            }
        }
    }

    // 二.6: 定义代理对象
    // 代理对象需要调用所以暴露的接口
    private IVoucherOrderService proxy;
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 1.先执行lua脚本
        Long result = stringRedisTemplate.execute(SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());

        // 2.查看脚本返回结果是否为0
        // 2.1 如果不为0，说明秒杀失败
        int r = result.intValue();
        if(r != 0){
            return Result.fail(r==1?"库存不足":"不能重复购买优惠券");
        }

        // 2.2 如果为0，说明秒杀成功
        // 2.2.1生成订单id
        Long orderId = redisIdWorker.nextId("order");

        // 2.2.2将优惠券id、用户id、订单id保存到阻塞队列中
        // 一：创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 订单id
        voucherOrder.setId(orderId);
        // 用户id
//        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 代金券id
        voucherOrder.setVoucherId(voucherId);
        // 二：将订单存入阻塞队列中
        orderTasks.add(voucherOrder);
        // 二.7 初始化代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        // 3.返回结果
        return Result.ok(orderId);

    }


//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        // 1. 从数据库中查询代金券是否存在
//        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//        if (voucher == null) {
//            return Result.fail("优惠券不存在");
//        }
//        // 2. 判断代金券是否过期（前后）
//        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀未开始");
//        }
//        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
//            return Result.fail("优惠券秒杀已结束");
//        }
//
//
//        // 3，判断代金券库存是否充足
//        if (voucher.getStock() < 1) {
//            return Result.fail("优惠券库存不足");
//        }
//
//
//        Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {//二：这里将悲观锁的范围覆盖到整个createVoucherOrder方法中,先完成事务（确保操作完成数据库）后再释放锁，保证了不会出现并发安全问题；如果悲观锁写在createVoucherOrder方法中，在执行完方法后悲观锁释放，其他线程进入，此时事务还未提交数据库这样就会出现线程安全问题
////            //一：这里的悲观锁synchronized是解决并发安全问题的，这里是为了防止用户重复购买,用userId作为锁对象，表示每个用户只能购买一次
////            // intern() 这个方法是从常量池中拿到数据（在池中比对，值相等的就返回相等的那个值的对象存储地址），如果我们直接使用userId.toString() 他拿到的对象实际上是不同的对象，new出来的对象，我们使用锁必须保证锁必须是同一把锁
////            //三：调用的方法，其实是this.的方式调用的，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象， 来操作事务（@Transactional是基于代理的，所以这里需要获得原始的事务对象）
////            //return this.createVoucherOrder(voucherId);
////            // 获取原始的事务对象，来操作事务（启动类中加@EnableAspectJAutoProxy(exposeProxy = true)// 暴露代理对象，才能拿到代理对象，还要一个依赖）
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//
//        // 获取自定义的锁（创建锁对象）
////        SimpleRedisLock lock = new SimpleRedisLock("order"+userId, stringRedisTemplate);
//        // 获取Redisson的锁对象
//        RLock lock = redissonClient.getLock("lock:order:" + userId);
//        // 判断是否成功获取锁
//        if(!lock.tryLock()){//redisson的这个，无参表名非阻塞式获取锁
//            // 获取锁失败，说明有其他线程正在购买/已购买过，不能重复购买
//            return Result.fail("不能重复购买！");
//        }
//        try {
//            // 调用创建订单方法
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } finally {
//            // 手动释放锁
//            lock.unlock();
//        }
//
//
//    }

//    /**
//     * 创建订单（同步秒杀方案）
//     */
//    @Transactional
//    public Result createVoucherOrder(Long voucherId) {
//        // 一人一单逻辑(这部分也和秒杀代金券逻辑相同，也存在并发安全问题--> 所以要加锁：乐观锁不行（因为乐观锁解决是修改数据的问题，这里是判断是否存在，即查询订/插入），故用悲观锁)
//        // 1.用户id
//        Long userId = UserHolder.getUser().getId();
//        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
//        // 2.判断是否存在
//        if (count > 0) {
//            // 用户已经购买过了
//            return Result.fail("用户已经购买过一次！");
//        }
//
//        // 4. 减少库存金券库存
//        boolean success = seckillVoucherService.update()
//                .setSql("stock= stock -1")
//                .eq("voucher_id", voucherId)
//                .gt("stock", 0)//乐观锁：解决库存商品超卖问题
//                .update();
//        if (!success) {
//            //扣减库存
//            return Result.fail("库存不足！");
//        }
//        //6.创建订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 6.1.订单id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        // 6.2.用户id
////        Long userId = UserHolder.getUser().getId();
//        voucherOrder.setUserId(userId);
//        // 6.3.代金券id
//        voucherOrder.setVoucherId(voucherId);
//        save(voucherOrder);
//
//        return Result.ok(orderId);
//    }

    /**
     * 创建订单（异步秒杀方案）
     * @param voucherOrder
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单逻辑(这部分也和秒杀代金券逻辑相同，也存在并发安全问题--> 所以要加锁：乐观锁不行（因为乐观锁解决是修改数据的问题，这里是判断是否存在，即查询订/插入），故用悲观锁)
        // 1.用户id
        Long userId = voucherOrder.getUserId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        // 2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }

        // 4. 减少库存金券库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)//乐观锁：解决库存商品超卖问题
                .update();
        if (!success) {
            //扣减库存失败
            log.error("库存不足！");
            return;
        }

        // 将订单保存到数据库中
        save(voucherOrder);

    }
}