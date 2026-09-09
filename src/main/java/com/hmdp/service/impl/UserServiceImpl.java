package com.hmdp.service.impl;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexPatterns;
import com.hmdp.utils.RegexUtils;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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

    /**
     * 登录功能
     * @param loginForm
     * @param session
     * @return
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.先判断手机号的格式是否正确
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2.手机号格式错误
            return Result.fail("手机号格式错误");
        }
        // 2.判断验证码是否正确
        String code = loginForm.getCode();
        Object cacheCode = session.getAttribute(phone);
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            // 3.验证码错误，返回错误信息
            return Result.fail("验证码错误");
        }

        // 4.验证码正确，判断用户是否存在
        // select * from user where phone = ? :判断用户是否存在
        User user = query().eq("phone", phone).one();
        // 5.用户不存在，创建用户
        if(user == null){
            // 5.用户不存在，创建用户
            user = createUser(phone);
        }

        // 6. 保存用户到session中(session 存 UserDTO → 拦截器取 UserDTO → UserHolder 存 UserDTO)
        UserDTO userDTO = new UserDTO();
        userDTO.setId(user.getId());
        userDTO.setNickName(user.getNickName());
        userDTO.setIcon(user.getIcon());
        session.setAttribute("user", userDTO);

        // 7.返回成功
        return Result.ok();
    }

    /**
     * 创建用户with手机号
     * @param phone
     * @return
     */
    private User createUser(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);
        return user;
    }
}