--1. 参数解析
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 2. 数据 Key 定义
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId  -- 修正变量名大小写一致

-- 3. 业务逻辑
-- 3.1 判断库存是否充足
if (tonumber(redis.call('get', stockKey)) <= 0) then  -- 修正括号和空格
    return 1
end

-- 3.2 判断用户是否重复下单
if (redis.call('sismember', orderKey, userId) == 1) then  -- 修正括号
    return 2
end

-- 3.3 扣减库存
redis.call('incrby', stockKey, -1)  -- 修正为 -1

-- 3.4 记录用户订单
redis.call('sadd', orderKey, userId)  -- 修正逗号

return 0