package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        //生成验证码,使用hutool工具箱来随机生成一个6位数的手机验证码
        String code = RandomUtil.randomNumbers(6);

        //保存验证码到Redis,还要设置一个有效期，因为验证码一般都需要时效，同时也是为了防止我们的Redis存储过多的数据
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码
        log.debug("发送验证码成功，验证码：{}", code);
        //返回ok
        return Result.ok();
    }

    /**
     * @param loginForm 前端登录表单信息
     * @param session   保存校验码以及保存用户信息
     * @return 若执行正常返回ok
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号，虽然前面获取验证码已经校验过一次，但是如果用户可能会修改填写的手机号，因此还需再校验一次
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (CacheCode == null || !CacheCode.equals(code)) {
            return Result.fail("校验码错误或不一致");
        }
        //根据手机号查询用户是否存在,若不存在，则为其在数据库中创建用户，存在则将用户信息保存在session。
        User user = query().eq("phone", phone).one();
        if (user == null) {
            user = createUserWithPhone(phone);
        }
        String token = UUID.randomUUID().toString();
        //我们需要将用户的敏感信息抹去，因此可以重新创建一个对象一个个赋值，也可以用下面的方法：
        //这里使用了hutool插件的BeanUtil方法，可以实现快速地复制某个对象的属性到指定的类去创建一个对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将信息存储到redis的hash存储结构中，需要将其转换为Map类型来使用，Map中
        String sID = userDTO.getId().toString();
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        userMap.put("id", sID);
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //前端要能拿到token后才能在后面我们再次访问时返回给我们token来进行查询用户
        return Result.ok(token);
    }

    @Override
    public Result sign() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //写入Redis BitMap 中,这里因为bitMap是从0开始的，而dayOfMonth是从1开始的，所以需要减1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2.获取日期
        LocalDateTime now = LocalDateTime.now();
        //3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //5.获取本月截至今天为止的所有的签到记录，返回的是一个十进制的数字 BITFIELD sign:userId:202312 GET u3 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key, BitFieldSubCommands.create().
                        get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0));
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        //因为BITFILED可以查找多个结果，然而明显我们这里只查了一个（用GET），即从0到今天的位返回的十进制结果
        //那么只需要取第一个元素就行
        Long num = result.get(0);
        //如果没查到，或者签到天数为0（所有位都是0，转成二进制就是0），那么就直接返回
        if(num == null || num == 0 ){
            return Result.ok(0);
        }
        //让 num 和 1 做按位与运算，可以得到最后一个位的情况。然后如果是1，那么让num做按位右移1次，来获取前一天的位值
        int count = 0;
        while(true){
            if((num & 1) == 0){
                //当前位为0，说明这天没签到，就直接退出循环
                break;
            }else{
                //当前位为1，说明这天签到了，让计数器+1，记录连续签到天数
                count++;
            }
            //这里用无符号右移
            num = num >>> 1;
        }
        return Result.ok(count);

    }


    /**
     * 创建新用户并保存到数据库中
     *
     * @param phone 用于设置新用户的手机号
     * @return 新用户对象
     */
    private User createUserWithPhone(String phone) {
        //创建新用户，并设置手机号和昵称后保存到数据库
        User NewUser = new User();
        NewUser.setPhone(phone);
        NewUser.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(5));
        save(NewUser);
        return NewUser;
    }
}
