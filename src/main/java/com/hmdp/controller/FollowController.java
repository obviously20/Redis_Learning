package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        // 关注或取消关注
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result orOrNotFollow(@PathVariable("id") Long followUserId) {
        // 查询是否关注
        return followService.orOrNotFollow(followUserId);
    }

    @GetMapping("/common/{id}")
    public Result commonFollow(@PathVariable("id") Long followUserId) {
        // 查询共同关注
        return followService.commonFollow(followUserId);
    }

}
