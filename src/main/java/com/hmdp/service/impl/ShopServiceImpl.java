package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        // Shop shop = queryByCachePenetrate(id);

        // 互斥锁  缓存击穿
        // Shop shop = queryByMutex(id);

        // 逻辑过期（需要提交缓存预热） 缓存击穿
        Shop shop = queryByLogical(id);

        return Result.ok(shop);
    }

    private Shop queryByLogical(Long id) {
        // 通过redis查询数据
        String cacheShop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        // 判断是否存在数据
        if (StrUtil.isBlank(cacheShop)) {
            // 不存在，直接返回
            return null;
        }
        // 3.未过期，直接返回数据
        RedisData data = JSONUtil.toBean(cacheShop, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
        if (data.getExpireTime().isAfter(LocalDateTime.now())) {
            return shop;
        }
        // 过期，尝试获取锁
        boolean flag = tryLock(id);
        // 获取成功，通过线程池异步进行数据的存储
        if (flag) {
            // 双重判定锁
            cacheShop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            // 未过期，直接返回数据
            data = JSONUtil.toBean(cacheShop, RedisData.class);
            shop = JSONUtil.toBean((JSONObject) data.getData(), Shop.class);
            if (data.getExpireTime().isAfter(LocalDateTime.now())) {
                return shop;
            }
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    saveDataToRedis(id, 10L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(id);
                }
            });
        }
        return shop;
    }

    private Shop queryByMutex(Long id) {
        // 通过redis查询数据
        String cacheShop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(cacheShop)) {
            return JSONUtil.toBean(cacheShop, Shop.class);
        }
        Shop shop = null;
        // 尝试获取锁
        boolean flag = tryLock(id);
        try {
            // 没有获取锁
            if (!flag) {
                Thread.sleep(50);
                return queryByMutex(id);
            }
            // 有获取锁
            // 通过redis查询数据
            cacheShop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
            if (StrUtil.isNotBlank(cacheShop)) {
                return JSONUtil.toBean(cacheShop, Shop.class);
            }
            shop = getById(id);
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(id);
        }
        return shop;
    }

    private Shop queryByCachePenetrate(Long id) {
        // 通过redis查询数据
        String cacheShop = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        if (StrUtil.isNotBlank(cacheShop)) {
            Shop shop = JSONUtil.toBean(cacheShop, Shop.class);
            return shop;
        }
        if (cacheShop != null) {
            //return Result.fail("店铺信息不存在");
            return null;
        }
        Shop shop = getById(id);
        if (ObjectUtil.isNull(shop)) {
            // 如果redis中没有数据，但是数据库也没有数据，为了防止缓存穿透，将空值写入redis
            stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            //return Result.fail("该店铺不存在");
            return null;
        }

        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // 更新店铺数据
        updateById(shop);
        // 删除redis中的数据
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return Result.ok();
    }

    // 加锁
    private boolean tryLock(Long id) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(LOCK_SHOP_KEY + id, String.valueOf(id), LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 解锁
    private void unlock(Long id) {
        stringRedisTemplate.delete(LOCK_SHOP_KEY + id);
    }

    // 保存数据
    public void saveDataToRedis(Long id, Long expireSeconds) {
        Shop shop = getById(id);
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }
}
