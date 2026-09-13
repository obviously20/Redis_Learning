-- 1.参数列表
-- 1.1 优惠券ID
local voucherId = ARGV[1]
-- 1.2 用户ID
local userId =ARGV[2]
-- 1.2 用户ID
local orderId =ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <= 0) then
    -- 库存不足，返回1
    return 1
end
-- 4.判断用户是否下单
if(redis.call('sismember',orderKey,userId) == 1) then
    -- 表明订单集合中已经存在改用户ID，已下过单
    return 2
end
-- 5.前两个都不符合，则既有库存，又没下过单
-- 5.1 扣减库存
redis.call('incrby',stockKey,-1)
-- 5.2 将用户ID添加到订单集合
redis.call('sadd',orderKey,userId)
-- 前面都符合，发送消息到队列
redis.call('xadd','stream.orders','*','voucherId',voucherId,'userId',userId,'id',orderId)
return 0