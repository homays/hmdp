package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1. 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        // 2. 生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 3. 保存验证码到redis中
        //stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, 3, TimeUnit.MINUTES);
        if (!allowRequestLua(LOGIN_CODE_KEY + phone, code)) {
            return Result.fail("请勿重复发送验证码");
        }

        // 4. 发送验证码
        //log.info("发送验证码成功，验证码: {}", code);

        return Result.ok();
    }

    public boolean allowRequestLua(String key, String code) {
        // 当前时间戳
        long currentTime = System.currentTimeMillis();

        // 使用 Lua 脚本来确保原子性操作
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("rate_limit.lua"));
        script.setResultType(Long.class);

        // 将 Lua 脚本和参数传递给 execute 方法，ZADD key score member [score member ...]
        Long result = stringRedisTemplate.execute(
                script,
                Collections.singletonList(key), // Redis 键
                String.valueOf(currentTime), // 当前时间戳作为参数
                code, // 验证码作为值
                "10" // 最大请求次数
        );
        return result == 1;
    }

    public boolean allowRequest(String key, String code) {
        // 当前时间戳
        long currentTime = System.currentTimeMillis();
        // 窗口开始时间是当前时间减 60s
        long windowStart = currentTime - 60 * 1000;

        // 删除窗口开始时间之前的所有数据
        stringRedisTemplate.opsForZSet().removeRangeByScore(key, 0L, windowStart);

        // 计算总请求数
        long currentRequests = stringRedisTemplate.opsForZSet().zCard(key);

        // 窗口足够则把当前请求加入
        if (currentRequests < 10) {
            stringRedisTemplate.opsForZSet().add(key, code, currentTime);
            return true;
        }

        return false;
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        // 校验验证码
        String code = loginForm.getCode();
        //String redisCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        ZSetOperations<String, String> zSet = stringRedisTemplate.opsForZSet();
        Set<String> range = zSet.range(LOGIN_CODE_KEY + phone, -1, -1);
        String redisCode = null;
        if (range != null) {
            redisCode = range.iterator().next();
        }
        Double score = zSet.score(LOGIN_CODE_KEY + phone, redisCode);
        // 当前时间戳
        long currentTime = System.currentTimeMillis();
        // 过期时间为5分钟
        if (currentTime - score > 60 * 1000 * 5) {
            return Result.fail("验证码已过期，请重新获取");
        }

        if (!StrUtil.equals(code, redisCode)) {
            return Result.fail("验证码有误");
        }

        // 通过手机号查询用户
        User user = this.getOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));

        // 如果为空，则为新用户，保存
        if (ObjectUtil.isNull(user)) {
            user = SaveUser(loginForm);
        }

        // 保存用户信息到 redis中
        // 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        // 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 删除redis 中的验证码
        //stringRedisTemplate.delete(LOGIN_CODE_KEY + phone);

        // 返回token
        return Result.ok(token);
    }

    private User SaveUser(LoginFormDTO loginForm) {
        User user = new User();
        user.setPhone(loginForm.getPhone());
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomNumbers(6));
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        this.save(user);
        return user;
    }
}