package com.hmdp.service.impl;


import cn.hutool.core.util.BooleanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
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

import java.util.List;

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
        Long userId = UserHolder.getUser().getId();
        String key = BLOG_LIKED_KEY+blog.getId();
        // 去redis中查询isMember对应笔记id的set中是否存在用户id
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());
        blog.setIsLike(BooleanUtil.isTrue(isMember));
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
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(key, userId.toString());

        if(BooleanUtil.isFalse(isMember)) {
            // 不存在
            // 数据库对应笔记点赞数+1
            boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
            // redis中添加用户id
            if (success) {
                stringRedisTemplate.opsForSet().add(key, userId.toString());
            }
        }else {
            // 存在
            // 数据库对应笔记点赞数-1
            boolean success = update().setSql("liked = liked - 1").eq("id", id).update();
            // redis中删除用户id
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, userId.toString());
            }
        }

        return Result.ok();
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}