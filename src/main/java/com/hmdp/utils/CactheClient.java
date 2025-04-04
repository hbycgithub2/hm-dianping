package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CactheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CactheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
   //实现缓存重建
    public void setWithLogicalExpire(String key, Object value,Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        redisData.setData(value);

        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPerfix, ID id, Class<R> type, Function<ID,R> dbFallback
    ,Long time, TimeUnit unit) {
        String key = keyPerfix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);

        }
        // 判断命中的是否是空值
        if ("".equals(json)) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
       // Shop shop = getById(id);
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (null == r) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // 6.存在，写入redis
      //  stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(r), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        this.set(key, r, time, unit);
        // 7.返回

        return r;
    }
    private static final ExecutorService CACH_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback
            ,Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询商铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;

        }
        //4.命中，需要先把ison反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r= JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5．判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期，直接返回店铺信息
            return r;
        }
        //5.2。已过期，需要缓存重建
        //6.缓存重建
        //6.1.获取互厅锁
        String lockKey = LOCK_SHOP_KEY + id;

        boolean isLock = tryLock(lockKey);
        //6.2.判断是否获取锁成功
        if (isLock) {
            // 6.3 成功，开启独立线程，实现缓存重建
            CACH_REBUILD_EXECUTOR.submit(() -> {
                try {
                    R r1 = dbFallback.apply(id);
                    this.setWithLogicalExpire(key, r1, time, unit);
                    //this.saveShop2Redis(id, 20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }


            });
        }
        // 返回过期的商铺信息
        return r;
    }

    //互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
