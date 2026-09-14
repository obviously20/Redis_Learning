package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            queryBlogUser(blog);
            // 查询是否点赞
            queryIsLike(blog);
        });
//        records.forEach(this::queryBlogUser);
        return Result.ok(records);
    }

    /**
     * 根据id查询探店博文
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        // 获取探店博文
        Blog blog = getById(id);
        // 查询用户
        queryBlogUser(blog);
        // 查询是否点赞
        queryIsLike(blog);
        // 返回结果
        return Result.ok(blog);
    }

    private void queryIsLike(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY+blog.getId();
        // 去redis中查询isMember对应笔记id的set中是否存在用户id
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞探店博文
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        // 获取用户id
        Long userId = UserHolder.getUser().getId();

        String key = BLOG_LIKED_KEY+id;

        // 去redis中查询isMember对应笔记id的set中是否存在用户id
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        if(score == null) {
            // 不存在
            // 数据库对应笔记点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // redis中添加用户id
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
            }
        }else {
            // 存在
            // 数据库对应笔记点赞数-1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // redis中删除用户id
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    /**
     * 查询探店博文是否点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result queryBlogLike(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 查询redis内的点赞排行榜top5: zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 将top5中的id分离
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1) --用FIELD函数保证返回顺序和点赞顺序一致
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}