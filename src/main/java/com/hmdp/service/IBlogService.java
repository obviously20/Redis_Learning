package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询热门探店博文
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询探店博文
     * @param id
     * @return
     */
    Result queryBlogById(Long id);

    /**
     * 点赞探店博文
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询探店博文是否点赞排行榜
     * @param id
     * @return
     */
    Result queryBlogLike(Long id);

    /**
     * 保存探店博文
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);
}