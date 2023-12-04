package com.hmdp.config;

import com.hmdp.utils.Interceptor.LoginInterceptor;
import com.hmdp.utils.Interceptor.RefreshTokenInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

//配置拦截器，使拦截器功能启动
@Configuration
public class InterceptorConfig implements WebMvcConfigurer {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    //将不需要判断登录状态的请求从拦截器中去除。
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new LoginInterceptor(stringRedisTemplate)).excludePathPatterns(
                "/user/code",
                "/user/login",
                "/blog/hot",
                "/shop-type/**",
                "/shop/**",
                "/voucher/**",
                "/upload/**"
        ).order(1);//设置优先级在下面这个拦截器之后
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(0);
    }
}
