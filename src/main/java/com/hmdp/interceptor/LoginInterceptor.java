package com.hmdp.interceptor;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.从session中获取用户信息
        Object user = request.getSession().getAttribute("user");
        // 2.判断用户信息是否存在
        if (user == null) {
            // 3.不存在则拦截返回401
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }
        // 4.存在则保存用户信息到threadlocal中
        UserHolder.saveUser((UserDTO) user);
        // 5.放行
        return true;

    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserHolder.removeUser();
    }
}