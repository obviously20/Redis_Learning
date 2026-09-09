package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /**
     * 发送短信验证码并保存验证码到session中
     * @param phone
     * @param session
     * @return
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.先判断手机号的格式是否正确
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.手机号格式错误
            return Result.fail("手机号格式错误");
        }
        // 3.生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 4.将验证码保存到session中
        session.setAttribute(phone, code);
        // 5.发送短信验证码(这里简单打印)
        // 以后可以用阿里云的短信服务/公司自己的短信服务发送调用即可
        log.info("发送短信验证码:{}", code);
        // 6.返回成功
        return Result.ok();
    }
}
