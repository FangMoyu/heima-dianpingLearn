package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
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
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            isBlogLiked(blog);
        });

        return Result.ok(records);
    }

    @Override
    public Result queryBlogById(Long id) {
        //根据博客id查询博客
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        //查询博客对应的用户
        queryBlogUser(blog);
        //查询博客是否被当前用户点赞，为了显示点赞的图标
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    //判断当前用户是否点赞了这条博客
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            //用户未登录，无需查询用户点赞情况
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 用户点赞逻辑
     * 判断用户是否点赞
     * 点赞和取消点赞
     *
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        //1.获取当前登录用户id
        Long userId = UserHolder.getUser().getId();
        //2.从Redis中判断登录用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        RLock lock = redissonClient.getLock(key + userId);
        try {
            if (lock.tryLock()) {
                Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
                //3.如果未点赞，可以点赞
                if (score == null) {
                    //3.1.修改数据库点赞数+1
                    boolean isSuccess = update().setSql("liked = liked+1").eq("id", id).update();
                    //3.2.保存用户数据到Redis的Zset集合中
                    if (isSuccess) {
                        //将当前的时间戳存进SortedSet中的score字段，用来实现排序
                        stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                    }
                } else {
                    //4.如果已经点赞，取消点赞
                    //4.1数据库点赞数-1
                    boolean isSuccess = update().setSql("liked = liked-1").eq("id", id).update();
                    //4.2将用户从Redis中移除
                    if (isSuccess) {
                        stringRedisTemplate.opsForZSet().remove(key, userId.toString());
                    }
                }
            }
        } finally {
            lock.unlock();
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        //实现显示前5个用户点赞显示
        //1. 去SortedSet中查找前五个用户 ZRANGE key 0 4， ZRANGE是可以直接返回排序好的member的，不会返回score
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok();
        }
        //2. 解析出其中的userId
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        //3.根据用户id查询用户
        List<UserDTO> userDTOS = userService.query().in("id", ids)
                .last("order by FIELD(id," + idStr + ")").list()
                .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //返回
        return Result.ok(userDTOS);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if (!isSuccess) {
            return Result.fail("新增探店笔记失败");
        }
        // 查询笔记作者的所有粉丝
        // （对于这张表来说，被关注者是follow_user_id，因此查询关注者就是userId）
        // 故有：select * from tb_follow where follow_user_id = userId
        List<Follow> follows =
                followService.query().eq("follow_user_id", user.getId()).list();
        if (follows == null || follows.isEmpty()) {
            return Result.ok(blog.getId());
        }
        for (Follow follow : follows) {
            Long userId = follow.getUserId();
            String key = FEED_KEY + userId;
            //推送
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 返回id
        return Result.ok(blog.getId());


    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1.取出当前用户
        Long userId = UserHolder.getUser().getId();
        //2.查询收件箱 ZREVRANGEBYSCORE key MAX MIN LIMIT offset count
        String key = FEED_KEY + userId;
        //这里使用reverseRangeByScoreWithScores()，因为我们还需要根据 score 去进行分页操作
        //每次查两条数据，最大值由前端发送，最小值为0，offset也由前端发送
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().
                        reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        //3.解析数据：blogId, minTime（时间戳），分页的主要部分
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;//记录最小值，即通过不断循环之后的最后一个值
        int os = 1;//记录本次查询数据中最后一个元素的重复次数
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            //3.1获取 id
            ids.add(Long.valueOf(tuple.getValue()));
            //3.2获取分数（时间戳）
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                os++;
            } else {
                os = 1;
                minTime = time;
            }
        }
        //查询邮箱接收到的博客数据
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids)
                .last("ORDER BY FIELD(id," + idStr + ")").list();

        //由于博客中含有点赞信息，因此需要再次检测判断
        for (Blog blog : blogs) {
            //查询博客对应的用户
            queryBlogUser(blog);
            //查询博客是否被当前用户点赞，为了显示点赞的图标
            isBlogLiked(blog);
        }
        //创建对象ScrollResult作为分页返回结果
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    //封装的查寻用户方法
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
