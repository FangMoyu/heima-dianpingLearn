package com.hmdp.service.impl;

import cn.hutool.core.collection.ListUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryShopTypeList() {
        //从Redis中获取对应List的String类型的元素长度
        Long length = stringRedisTemplate.opsForList().size(CACHE_SHOP_TYPE_KEY);
        //用于存放从Redis查询到的元素的Json字符串对应的List集合
        List<String> shopTypeListStr;
        //用于存放将上述集合中的Json字符串元素转换回ShopType类型的元素
        List<ShopType> shopTypes = new ArrayList<>();
        //判断一下长度不为空
        if (length != null) {
            //查询Redis数据，若存在则进行Json转ShopType
            shopTypeListStr = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, length);
            if (shopTypeListStr != null && !shopTypeListStr.isEmpty()) {
                for (String shopTypeJson : shopTypeListStr) {
                    ShopType shopType = JSONUtil.toBean(shopTypeJson, ShopType.class);
                    shopTypes.add(shopType);
                }
                //返回新集合，里面存放了在缓存查找的数据
                return Result.ok(shopTypes);
            }
        }
        //从数据库中查询到List
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //若未查到，则返回错误信息
        if (typeList.isEmpty()) {
            return Result.fail("该选项暂未开放");
        }
        //将查询到的数据转换成Json，然后放到Redis数据库中，用List集合的形式，从右往左放可以让后面取出的
        for (ShopType shopType : typeList) {
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForList().rightPush(CACHE_SHOP_TYPE_KEY, shopTypeJson);
            stringRedisTemplate.expire(CACHE_SHOP_TYPE_KEY, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        }
        return Result.ok(typeList);
    }
}
