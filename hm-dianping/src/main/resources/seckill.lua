-- 订单id
local voucherId = ARGV[1]

-- 用户id
local userId = ARGV[2]

local orderId = ARGV[3]
-- 数据key
    -- 库存key
--在lua脚本中，拼接字符串使用的是..
local stockKey = 'seckill:stock:' .. voucherId
    -- 订单key
local orderKey = 'seckill:order:' .. userId

--脚本业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <=0 ) then
    return 1
end

-- 判断用户是否下单，若已经下单，则返回2，若未下单，则进行下单操作
--因为是 set 集合，则直接使用 sismember 函数来判断是否存在用户id即可
if(redis.call('sismember',orderKey, userId) == 1) then
    return 2
end

-- 如果上述判断中，库存充足，用户未下单，则执行下单业务
-- 库存减少
redis.call('incrby',stockKey,-1)
-- 添加用户id到订单中
redis.call('sadd',orderKey,userId)
-- 将订单信息添加到消息队列steam.orders
-- XADD stream.orders * k1 v1 k2 v2 k3 v3
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0