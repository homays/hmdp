package com.hmdp.interceptor;

import cn.hutool.core.util.ObjectUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

@Configuration
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 获取用户信息
        HttpSession session = request.getSession();
        Object user = session.getAttribute("user");

        // 判断用户是否存在
        if (ObjectUtil.isNotNull(user)) {
            // 不存在，拦截，返回401状态码
            response.setStatus(401);
            return false;
        }
        // 存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser((UserDTO) user);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}
