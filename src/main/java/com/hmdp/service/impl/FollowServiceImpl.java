package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private IUserService userService;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 关注或取消关注
     * @param followUserId
     * @param isFollow
     * @return
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 拿到当前登录用户id
        Long loadingId = UserHolder.getUser().getId();
        String key = "follows:" + loadingId;
        // 先看返回的isFollow是否为true
        // 如果为true，说明要关注
        if (isFollow) {
            // 关注
            Follow follow = new Follow();
            follow.setUserId(loadingId);
            follow.setFollowUserId(followUserId);
            // 保存关注记录
            boolean isSuccess = save(follow);
            // 并且存入redis中
            if (isSuccess) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }

        }else {// 如果为false，说明要取消关注
            // 取消关注
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", loadingId)
                    .eq("follow_user_id", followUserId));
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询是否关注
     * @param followUserId
     * @return
     */
    @Override
    public Result orOrNotFollow(Long followUserId) {
        // 获取当前用户id
        Long loadingId = UserHolder.getUser().getId();
        // 查询是否关注
        Integer count = Math.toIntExact(query().eq("user_id", loadingId).eq("follow_user_id", followUserId).count());
        return Result.ok(count > 0);
    }

    /**
     * 查询共同关注
     * @param followUserId
     * @return
     */
    @Override
    public Result commonFollow(Long followUserId) {
        // 获取当前用户id
        Long loadingId = UserHolder.getUser().getId();

        // 构建key
        String key = "follows:" + loadingId;
        String key2 = "follows:" + followUserId;

        // 去redis中查询用户id和followUserId的关注的交集
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key, key2);
        // 判空(无交集)
        if (set.isEmpty() || set == null) {
            return Result.ok(Collections.emptyList());
        }
        // 转换为Long类型
        List<Long> commonFollowIds = set.stream().map(Long::valueOf).collect(Collectors.toList());
        // 有交集
        // 将交集的ids都查出对应用户信息
        List<User> users = userService.listByIds(commonFollowIds);
        // 将user类型转换为UserDTO类型
        List<UserDTO> userDtos = users.stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).collect(Collectors.toList());
        return Result.ok(userDtos);
    }
}
