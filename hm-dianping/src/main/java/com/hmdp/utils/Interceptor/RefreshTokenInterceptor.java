package com.hmdp.utils.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;

//登录拦截器，用于检测用户登录状态
public class RefreshTokenInterceptor implements HandlerInterceptor {
    
    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从Redis中获取用户
//        Object user = request.getSession().getAttribute("user");
        //前端会将之前发送给它的token保存在一个请求头的v中，名字叫做authorization
        String token = request.getHeader("authorization");
        //校验是否为空
        if(StrUtil.isBlank(token)){
            return true;
        }

        Map<Object, Object> userMap =
                stringRedisTemplate.opsForHash().entries(LOGIN_USER_KEY + token);
        if(userMap.isEmpty()){
            return true;
        }
        //转换为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(),false);
        //将用户保存到ThreadLocal中来进行保存用户登录状态,UserHolder类可以实现这个功能
        UserHolder.saveUser(userDTO);
        //判断登录状态可以刷新有效期，每次进入这个页面，我们没必要都登录，如果在有效期内可以直接登录。
        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL, TimeUnit.SECONDS);
        //放行拦截器
        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) throws Exception {

    }
}
