package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 从数据库中查询代金券是否存在
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return Result.fail("优惠券不存在");
        }
        // 2. 判断代金券是否过期（前后）
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("优惠券秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("优惠券秒杀已结束");
        }


        // 3，判断代金券库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("优惠券库存不足");
        }


        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {//二：这里将悲观锁的范围覆盖到整个createVoucherOrder方法中,先完成事务（确保操作完成数据库）后再释放锁，保证了不会出现并发安全问题；如果悲观锁写在createVoucherOrder方法中，在执行完方法后悲观锁释放，其他线程进入，此时事务还未提交数据库这样就会出现线程安全问题
            //一：这里的悲观锁synchronized是解决并发安全问题的，这里是为了防止用户重复购买,用userId作为锁对象，表示每个用户只能购买一次
            // intern() 这个方法是从常量池中拿到数据（在池中比对，值相等的就返回相等的那个值的对象存储地址），如果我们直接使用userId.toString() 他拿到的对象实际上是不同的对象，new出来的对象，我们使用锁必须保证锁必须是同一把锁
            //三：调用的方法，其实是this.的方式调用的，事务想要生效，还得利用代理来生效，所以这个地方，我们需要获得原始的事务对象， 来操作事务（@Transactional是基于代理的，所以这里需要获得原始的事务对象）
            //return this.createVoucherOrder(voucherId);
            // 获取原始的事务对象，来操作事务（启动类中加@EnableAspectJAutoProxy(exposeProxy = true)// 暴露代理对象，才能拿到代理对象，还要一个依赖）
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }


    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 一人一单逻辑(这部分也和秒杀代金券逻辑相同，也存在并发安全问题--> 所以要加锁：乐观锁不行（因为乐观锁解决是修改数据的问题，这里是判断是否存在，即查询订/插入），故用悲观锁)
        // 1.用户id
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 2.判断是否存在
        if (count > 0) {
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次！");
        }

        // 4. 减少库存金券库存
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)//乐观锁：解决库存商品超卖问题
                .update();
        if (!success) {
            //扣减库存
            return Result.fail("库存不足！");
        }
        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        // 6.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 6.2.用户id
//        Long userId = UserHolder.getUser().getId();
        voucherOrder.setUserId(userId);
        // 6.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }
}
