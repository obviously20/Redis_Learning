package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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

    @Autowired
    private IFollowService followService;

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

    /**
     * 保存探店博文
     * @param blog
     * @return
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户（现在发博文的用户）
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("新增笔记失败!");
        }
        // 将保存的博文id封装进redis中（将其下发至关注当前用户的粉丝的信箱redis模拟）
        // 先查tb_follow表，获取关注当前用户的粉丝id
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 将查出的粉丝，根据id在redis中封装成一个收件箱
        follows.forEach(follow -> {
            // 获取粉丝id
            Long fanId = follow.getUserId();
            // 封装并发送保存至redis中(推模式)
            String key = FEED_KEY + fanId;
            // 普通分页查询依赖角标，会出现重复读取的情况
            // 其中为滚动分页查询，所以时间戳为score;在滚动分页查询时，会根据score从大到小排序，所以最新博文在最前面，且还会记录每页最后一个博文的时间戳，下一页查询时，会根据这个时间戳（比这个时间戳小的博文）来查询下一页的博文
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询关注的探店博主的博文，滚动分页查询
     * @param maxTime
     * @param offset
     * @return
     */
    @Override
    public Result queryFollowBlog(Long maxTime, Integer offset) {
        // 1.获取登录用户ID
        Long userId = UserHolder.getUser().getId();
        
        // 2.从redis中查询当前用户的收件箱（滚动分页查询）ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, maxTime, offset, 2);//滚动分页查询（根据时间戳从大到小排序，所以最新博文在最前面），offset为偏移量，2为每页数量

        // 3.判空（收件箱没有的话）
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok();
        }
        // 4.有则将查询到的信息提取出来：博文id列表，时间戳，偏移量（重点实现）

        // 博文id列表
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        // 最小时间戳（要返回给前端的minTime）
        Long minTime = 0L;
        // 下次查询要跳过的offset
        Integer count = 1;
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            // 获取blogid,并封装进blogIds中
            blogIds.add(Long.valueOf(typedTuple.getValue()));
            // 获取时间戳(查询到当前元素的时间戳)
            long time = typedTuple.getScore().longValue();
            if(time == minTime){// 说明当前元素的时间戳和上一个元素的时间戳相同，所以要跳过值加1
                count++;
            }else {// 说明当前元素的时间戳和上一个元素的时间戳不同，且已知上面的滚动分页查询是从大到小排序，所以后面查到的时间戳会更小
                // 不相等，就说明有跟小的时间戳，所以要重置count为1，minTime为当前元素的时间戳
                minTime = time;
                count = 1;
            }
        }
        // 情况若为 90 90 90 90 90 85;
        // 那第1页 90 90 、前端传的offset为0，maxTime=MAX,minTime=90,不相等所以offset为count(2) 所以要跳过2个元素(正常)；
        // 第2页 90 90 、前端传的offset为2，maxTime=90,后端计算的minTime=90,maxTime==minTime，但接下来要返回给前端的offset却为count(2)，跳过2个元素，(异常，实际要跳过4个元素:count(2)+offset(2)=4)
        // 那第3页就会陷入死循环查看一直是90 90
        // 所以这是为了处理第2页导致的死循环的情况，所以要判断minTime是否等于maxTime
        count = minTime == maxTime ?  count + offset : count;

        // 5.根据博文id列表查询博文
        String idStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds).last("ORDER BY FIELD(id," + idStr + ")").list();

        // 查询博文还需要查询用户信息和是否点赞过了
        for (Blog blog : blogs) {
            // 查询用户
            queryBlogUser(blog);
            // 查询是否点赞
            queryIsLike(blog);
        }


        // 封装vo返回值给前端
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(count);

        return Result.ok(scrollResult);
    }

    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }


}