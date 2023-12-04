package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //新建配置类，这个配置是Redisson的Config类，不是jdk自带的
        Config config = new Config();
        //设置redis服务器地址和密码
        config.useSingleServer().setAddress("redis://211.159.163.187:6379").setPassword("123321");
        //返回该配置下生成的RedissonClient对象
        return Redisson.create(config);
    }
}
