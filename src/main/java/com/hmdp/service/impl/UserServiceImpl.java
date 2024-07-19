package com.hmdp.service.impl;

import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

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
        stringRedisTemplate.opsForValue().set(RedisConstants.LOGIN_CODE_KEY + phone, code, 3, TimeUnit.MINUTES);

        // 4. 发送验证码
        log.info("发送验证码成功，验证码: {}", code);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式不正确");
        }

        // 校验验证码
        String code = loginForm.getCode();
        String redisCode = stringRedisTemplate.opsForValue().get(RedisConstants.LOGIN_CODE_KEY + phone);
        if (StrUtil.isBlank(code) && !StrUtil.equals(code, redisCode)) {
            return Result.fail("验证码有误");
        }

        // 通过手机号查询用户
        User user = this.getOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));

        // 如果为空，则为新用户，保存
        if (ObjectUtil.isNull(user)) {
            user = SaveUser(loginForm);
        }

        // 将用户信息保存到session中
        session.setAttribute(RedisConstants.LOGIN_USER_KEY + phone, user);

        // 删除验证码
        stringRedisTemplate.delete(RedisConstants.LOGIN_CODE_KEY + phone);

        return Result.ok();
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