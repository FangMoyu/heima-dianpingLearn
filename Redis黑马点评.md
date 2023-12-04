# Redis黑马点评

按照教程下载对应的前后端文件。



## 配置后端文件

先打开MySQL，登录后创建一个新的数据库hmdp，然后将hmdp.sql的内容写入数据库

```sql
source hmdp.sql的路径
```

解压并用idea打开hm-dianping项目，然后右击pom.xml将项目添加为maven项目。然后在项目设置中选择自己对应的jdk。



接下来，将application.yaml的配置修改成实际的情况，主要是对datasource的修改：

```yml
server:
  port: 8081
spring:
  application:
    name: hmdp
  datasource:
    driver-class-name: com.mysql.jdbc.Driver
    url: jdbc:mysql://127.0.0.1:3306/hmdp?useSSL=false&serverTimezone=UTC
    username: root
    password: 123456
  redis:
    host: 127.0.0.1
    port: 6379
    password: 123321
    lettuce:
      pool:
        max-active: 10
        max-idle: 10
        min-idle: 1
        time-between-eviction-runs: 10s
  jackson:
    default-property-inclusion: non_null # JSON处理时忽略非空字段
mybatis-plus:
  type-aliases-package: com.hmdp.entity # 别名扫描包
logging:
  level:
    com.hmdp: debug
```

配置完成后，注意看一下maven关于MySQL的连接器是否和你的MySQL版本匹配。若不是，注意修改。

此时，我们可以尝试启动项目，并访问：

[localhost:8081/shop-type/list](http://localhost:8081/shop-type/list)

> 不一定是localhost，你可以将其部署在远程服务器上。

![image-20230724181443184](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230724181443184.png)

## 配置前端文件

从教程中下载解压 nginx-1.18.0（路径最好要是全英文的），然后打开nginx.exe所在的目录下，然后在该目录打开命令窗口输入：

```
start nginx.exe
```

前端就部署在nginx了。接下来访问页面：

(http://localhost:8080/)，若显示成功即可。

![image-20230724181449287](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230724181449287.png)



## 登录功能

## 基于Session实现登录

**思路**

![image-20230724182016165](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230724182016165.png)



我们先实现第一步，发送短信验证码的部分：

Controller：

```java
/**
 * 发送手机验证码
 */
@PostMapping("code")
public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
    // TODO 发送短信验证码并保存验证码
    return userService.sendCode(phone,session);
}
```

对应的实现：

```java
@Override
public Result sendCode(String phone, HttpSession session) {
    //校验手机号
    if(RegexUtils.isPhoneInvalid(phone)){
        return Result.fail("手机号格式错误！");
    }
    //生成验证码,使用hutool工具箱来随机生成一个6位数的手机验证码
    String code = RandomUtil.randomNumbers(6);

    //保存验证码到session
    session.setAttribute("code",code);
    //todo 发送验证码
    log.debug("发送验证码成功，验证码：{}", code);
    //返回ok
    return Result.ok();
}
```



## 保存登录态，设置登录拦截器判断登录状态

拦截器编写，主要是为了判断一下是否登录

```java
//登录拦截器，用于检测用户登录状态
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //从session中获取用户
        Object user = request.getSession().getAttribute("user");
        if(user == null){
            //校验用户是否为空，若为空则拦截请求，返回false就是拦截。
            response.setStatus(401);
           return false;
        }
        //将用户保存到ThreadLocal中来进行保存用户登录状态,UserHolder类可以实现这个功能
        UserHolder.saveUser((User) user);
        //放行拦截器
        return true;
    }
```

这里要配置一下拦截器使其工作

```java
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
```



## 隐藏用户敏感信息

```java
//我们需要将用户的敏感信息抹去，因此可以重新创建一个对象一个个赋值，也可以用下面的方法：
//这里使用了hutool插件的BeanUtil方法，可以实现快速地复制某个对象的属性到指定的类去创建一个对象
session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
```



## session共享问题和Redis集群引出

![image-20230725151031694](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230725151031694.png)

为了提高性能，服务器一般都会通过多台tomcat服务器来实现负载均衡，而在不同的tomcat服务器之间session是无法共享的，这就导致了用户在某一台tomcat上保存了session，但下次访问时是由另一个tomcat服务器来接收请求，就导致无法识别上一次的session。

为了解决上述问题，tomcat提出过共享session的方式，但是该方式会导致增大耗时和内存消耗，因此，我们需要一个能够实现数据共享，并且是内存存储，且session对应的id是属于key ,value 存储，能实现k,v存储的容器，我们考虑到了**redis集群**。



![image-20230725153039584](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230725153039584.png)





## 实现Redis存储登录用户的登录和验证逻辑



在LoginInterceptor这个拦截器中，并未被Spring创建对象，而是我们主动new的拦截器，因此，在其内部的属性对象无法直接被注入，因此我们就无法直接使用SpringBoot的注解来注入对象。因此，我们需要找到调用该拦截器的拦截器配置类来为其传入的属性参数使用注解实现依赖注入。



登录逻辑实现redis存储

```java
@Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号，虽然前面获取验证码已经校验过一次，但是如果用户可能会修改填写的手机号，因此还需再校验一次
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        //校验验证码
        String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if(CacheCode==null || !CacheCode.equals(code) ){
            return Result.fail("校验码错误或不一致");
        }
        //根据手机号查询用户是否存在,若不存在，则为其在数据库中创建用户，存在则将用户信息保存在session。
        User user = query().eq("phone", phone).one();
        if(user==null){
          user = createUserWithPhone(phone);
        }
        String token = UUID.randomUUID().toString();
        //我们需要将用户的敏感信息抹去，因此可以重新创建一个对象一个个赋值，也可以用下面的方法：
        //这里使用了hutool插件的BeanUtil方法，可以实现快速地复制某个对象的属性到指定的类去创建一个对象
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将信息存储到redis的hash存储结构中，需要将其转换为Map类型来使用，Map中
        String sID = userDTO.getId().toString();
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        userMap.put("id",sID);
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        String tokenKey = LOGIN_USER_KEY+token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);
        //前端要能拿到token后才能在后面我们再次访问时返回给我们token来进行查询用户
        return Result.ok(token);
    }
```

目的：将原先由session存储的userDTO对象并发送给前端改为由redis进行hash存储的userDTO对象集合，并使用token来作为键值存储在redis数据库中。最后将token发送给前端便于后续获取用户的逻辑



发送校验码逻辑的Redis实现

```java
@Override
public Result sendCode(String phone, HttpSession session) {
    //校验手机号
    if(RegexUtils.isPhoneInvalid(phone)){
        return Result.fail("手机号格式错误！");
    }
    //生成验证码,使用hutool工具箱来随机生成一个6位数的手机验证码
    String code = RandomUtil.randomNumbers(6);

    //保存验证码到Redis,还要设置一个有效期，因为验证码一般都需要时效，同时也是为了防止我们的Redis存储过多的数据
    stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
    //发送验证码
    log.debug("发送验证码成功，验证码：{}", code);
    //返回ok
    return Result.ok();
}
```

将验证码由session存储改为由redis存储，通过手机号来获取code，手机号作为键可以保证唯一性。



![image-20230725214255136](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230725214255136.png)

存储粒度指的是存储用户信息的内容范围，即确定合适的内容显示。



## 登录拦截器优化

之前做的一个拦截器并没有将所有的页面做登录校验拦截，因此若用户在登录的状态但是一直访问的页面的首页时候（这个页面不受拦截器影响），设置的token计时器就无法刷新，那么若用户在首页访问超过token的有效期，就会自动退出登录。

因此，要解决这个问题，我们需要再设置一个拦截器，并将所有的页面设置token刷新，这里注意，第一个拦截器不进行拦截，只是进行用户登录判断，如果没有token，就直接放行进入第二个拦截器。如果有token，就进行刷新token操作然后放行到第二个拦截器执行。

![image-20230726191743957](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230726191743957.png)

这个新的拦截器其实并没有什么需要改的，只要把原先return false的部分变成return true 放行即可。

```java
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
```

校验到为空后放行到第二个拦截器，第二个拦截器会查不到用户信息，就进行拦截：

```java
//登录拦截器，用于检测用户登录状态
public class LoginInterceptor implements HandlerInterceptor {
    private StringRedisTemplate stringRedisTemplate;

    public LoginInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        UserDTO user = UserHolder.getUser();
        if(user==null){
            response.setStatus(401);
            return false;
        }
        return true;
    }
```





### 设置拦截器执行优先级

在addInterceptor方法后还可以执行一个order方法，这个是设置在拦截器配置类中添加的多个拦截器的执行先后顺序的方法。order可以传入一个int值，该int值越大，执行越靠后，值越小，越早执行。

```java
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
```



> 在默认情况下，所有的拦截器执行优先级order都是0，它的顺序是按照添加的顺序来执行的，因此也可以将我们新创建的这个拦截器放在原来拦截器的上面。



# 缓存

缓存是数据交换的缓冲区，是存储数据的临时地方，一般读写性能较高。

![image-20230726194527865](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230726194527865.png)





**缓存实现流程**

![image-20230726195116084](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230726195116084.png)



这里我们可以使用redis来实现缓存的功能，根据上图，我们可以编写如下代码：

```java
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY+id;
        //从redis中查询id对应数据是否存在
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //若存在则将json转为shop对象并返回给前端
        if(StrUtil.isNotBlank(shopJson)){
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return Result.ok(shop);
        }
        //若不存在，则查询数据库
        Shop shop = getById(id);
        //若数据库中无对应数据，则直接返回错误信息
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        //若数据库中有对应数据，则将其放入redis数据库中作为缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        return Result.ok(shop);
    }
}
```



## 将shopType版面添加到缓存

和上面操作类似，这里是将一个List添加到缓存中，我们可以将List里面每一个元素转为String后，将其放入redis的list数据库中，然后再取出，将其转回shopType类型之后将其放入一个新的集合返回。

```java
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
        if(length!=null){
            //查询Redis数据，若存在则进行Json转ShopType
            shopTypeListStr = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY,0,length);
                if(shopTypeListStr!=null && !shopTypeListStr.isEmpty()){
                    for(String shopTypeJson:shopTypeListStr){
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
        if(typeList.isEmpty()){
            return Result.fail("该选项暂未开放");
        }
        //将查询到的数据转换成Json，然后放到Redis数据库中，用List集合的形式，从右往左放可以让后面取出的
        for(ShopType shopType : typeList){
            String shopTypeJson = JSONUtil.toJsonStr(shopType);
            stringRedisTemplate.opsForList().rightPush(CACHE_SHOP_TYPE_KEY,shopTypeJson);
        }
        return Result.ok(typeList);
    }
}

```



## 缓存更新策略

![image-20230728080022342](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728080022342.png)



有下列三种更新方式：

![image-20230728080735244](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728080735244.png)







## 操作缓存

有两种： 重建缓存、删除缓存

操作缓存推荐使用**删除缓存**的操作。

解释：重建缓存要求数据库每次操作都要进行缓存更新操作。这对于高访问量的数据来说是合理的，但是如果一个数据访问的频率很低，那么这些更新操作就有大量的无效操作。

删除缓存可以在数据库更新时直接让缓存失效，在查询时再重建缓存。这有利于减少重建缓存操作带来的无效更新，只需要在用户访问时查询数据库并同时重建缓存就行。



![image-20230728082558089](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728082558089.png)





### 缓存和数据库操作顺序

![image-20230728081842525](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728081842525.png)

上述两种方案都会带来线程安全问题，但是可以看到第二种方案发生的可能性更低，因为查询缓存未命中，说明缓存刚好自动删除了，查询数据库的时候查到了旧数据，同时在很短的时间里写入缓存（读写缓存的操作耗时较少），但是此时又刚好有别的用户更新数据库，并删除缓存。这个发生的可能性比较低。因此第二种方案更加安全。

对于第二种方案可能发生的线程安全问题，可以采用**设置缓存延迟删除**来解决。及时缓存被写入了错误的数据，不久之后就会自动读取数据库进行更新。





### 缓存更新实践

![image-20230728082758125](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728082758125.png)

**设置缓存延迟删除时间**

```java
//若数据库中有对应数据，则将其放入redis数据库中作为缓存
stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.HOURS);
```



**编写重建缓存操作**

```java
public Result update(Shop shop) {
    //获取前端发送来的店铺id，并判断是否存在
    Long id = shop.getId();
    if(id==null){
        return Result.fail("店铺id不能为空");
    }
    String key = CACHE_SHOP_KEY+id;
    //根据查询的id对数据库进行更新操作
    updateById(shop);
    //删除缓存
    stringRedisTemplate.delete(key);
    return Result.ok();
}
```



## 缓存穿透

缓存穿透指的是客户端不断发送不存在的数据导致在缓存和数据库中都无法找到，若用户使用并发线程来不断进行此类操作，会导致数据库被搞垮。

可以使用两种方案来解决：

1. 缓存空对象，未查询到数据库内容就缓存一个**空值（这不是直接存null，而是对应类型中表述为空的值，例如字符串里的 ("")) ）**

   优点：

   1. 实现简单，维护方便

   缺点：

   1. 额外的内存消耗，可以设置一个延迟时间来解决
   2. 可能造成短期的不一致，可能出现一种情况，就是用户先查询不存在，但同时又保存了对应数据到数据库中，就会出现不一致。

2. 布隆过滤

   优点：

   1. 内存占用较少，没有多余 key

   缺点：

   1. 实现复杂
   2. 存在误判可能

![image-20230728091738652](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728091738652.png)



![image-20230728093248625](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728093248625.png)



### 缓存穿透实践

这里我们使用上图的方案，返回空字符串：

在查询店铺的逻辑中，我们可以编写如下内容来解决缓存穿透：



```java
public Result queryById(Long id) {
    String key = CACHE_SHOP_KEY+id;
    //从redis中查询id对应数据是否存在
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    //若存在则将json转为shop对象并返回给前端
    if(StrUtil.isNotBlank(shopJson)){
        Shop shop = JSONUtil.toBean(shopJson, Shop.class);
        return Result.ok(shop);
    }
    //防止缓存穿透，判断返回的 json 数据是否为空字符串，若是，则说明数据库中不存在这条数据
    if(shopJson!=null){
        return Result.fail("店铺不存在");
    }
    //若不存在，则查询数据库
    Shop shop = getById(id);
    //若数据库中无对应数据，则先将空字符串存入缓存，避免缓存穿透，再返回错误信息
    if(shop == null){
        //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        return Result.fail("店铺不存在");
    }
    //若数据库中有对应数据，则将其放入redis数据库中作为缓存
    stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
    stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.HOURS);
    return Result.ok(shop);
}
```



**关键代码**

```java
 //防止缓存穿透，判断返回的 json 数据是否为空字符串，若是，则说明数据库中不存在这条数据
    if(shopJson!=null){
        return Result.fail("店铺不存在");
    }
    if(shop == null){
        //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
        stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
        return Result.fail("店铺不存在");
    }
    
```





![image-20230728094630819](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728094630819.png)



## 缓存雪崩

大量的缓存key同时失效从而导致请求都进入了数据库，或者是Redis直接宕机了，就会导致缓存雪崩

![image-20230728101007403](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728101007403.png)



**解决方案**

针对大量的key同时失效的问题，这是因为维护服务器时，同时将大量数据库的数据存入Redis中，然而它们的有效期（TTL）都是一致的，就会导致大量的key在同时失效，进而导致缓存雪崩。解决该问题我们可以**设置一个随机数来变化各个缓存的有效期**。



## 缓存击穿

缓存击穿问题也叫热点Key问题，指的是一个被高并发访问并且缓存重建业务较复杂的 key 突然失效了，无数的请求访问会在瞬间给数据库带来巨大的冲击。

解决方案：

1. 互斥锁：在判断到缓存未命中的情况，就给当前线程加锁，仅让当前线程操作数据库，使得其他查询到缓存未命中的线程无法操作数据库
2. 逻辑过期：实际不要设置缓存key的过期时间，在key中加入一个字段描述缓存是否过期，这个被称为逻辑过期。当有线程查询到缓存逻辑过期，那么就获取互斥锁并开辟一个新线程来进行数据库操作和缓存更新，当前线程直接返回旧数据，其他也查询到逻辑过期缓存的线程也直接返回旧数据，直到被开辟线程完成了数据库操作和缓存更新后，后面的线程可以查询到更新数据的缓存时，返回的是新数据。

互斥锁和逻辑过期描述图：

![image-20230728102652290](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728102652290.png)



**两种方案的优缺点**

![image-20230728103112199](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728103112199.png)

互斥锁的优缺点很好理解。

逻辑过期缺点中的额外内存消耗是因为我们加入了逻辑过期字段，这会导致内存的消耗。



### 互斥锁的实现

由于这并没有使用Java自带的多线程，因此不能直接使用synchronized，这里我们要使用另一种方案来解决：

我们来看一下Redis的 setnx 方法：

![image-20230728200707525](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728200707525.png)

在Redis的String类型数据中，有一个 setnx 命令，**它只能从无到有的创建，而不能进行修改的操作**，因此，将这个原理套到我们的互斥锁的概念上可以得到：

1. 我们将 setnx key value 作为出现缓存未命中时做的工作。这样，如果返回的值为1，就相当于为当前线程加锁，后面的操作只能由当前线程进行，而其他的线程调用 setnx key value 会返回 0，就让它们等待，并在休眠结束后重新尝试获取缓存里的数据，等待完成了对缓存的更新操作后，再通过 del key 来删除这个键，实现释放锁的功能。



**流程图**

![image-20230728202057535](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728202057535.png)





我们可以通过 **setnx key value**以及 **del key** 来实现加锁和释放锁的功能：

加锁：

```java
public boolean tryLock(String key){
    //使用 setnx 对应的 setIfAbsent 方法来设置键值，并设置延迟时间。避免死锁
    //这里的value目前随意，目前还用不上。就设为1吧
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    //这里返回的是一个包装类，为了防止出现空指针，这里我们使用hutool工具返回。
    return BooleanUtil.isTrue(flag);
}

```

释放锁

```java
public void unlock(String key){
    stringRedisTemplate.delete(key);
}
```



**缓存击穿互斥锁解决方案实现**

```java
public Shop queryWithMutex(Long id){
    //查询缓存，获取shop的json数据
    String key =  CACHE_SHOP_KEY+id;
    String ShopJson = stringRedisTemplate.opsForValue().get(key);
    if(StrUtil.isNotBlank(ShopJson)){
        //存在就直接返回Shop对象
        return JSONUtil.toBean(ShopJson,Shop.class);
    }
    //不存在，判断是否为null，防止缓存穿透
    if(ShopJson!=null){
        //为null，说明数据库中并没有该数据，返回null，表示店铺不存在
        return null;
    }
    Shop shop = null;
    //因为Thread.sleep(50)会抛出一个检查型异常，这里我们将整个缓存击穿的流程放入 tyr-catch-finally操作
    //因为释放锁的操作无论是否抛出异常都需要执行，因此这里会使用这个结构
    try {
        //缓存未命中，加给每家店铺各自加互斥锁，毕竟访问不同的店家都是一个线程
        boolean isLock = tryLock(LOCK_SHOP_KEY+id);
        //若未得到锁，就让它进入休眠，休眠结束就重新执行查询缓存
        if(!isLock){
            Thread.sleep(50);
            //这里要加return，否则递归结束后会继续往下执行数据库操作
            return queryWithMutex(id);
        }
        //获得了互斥锁,查询数据库之前，要再次查询缓存，
        // 可能由于当前线程前面并没有查到缓存，但是刚好此时别的线程更新了缓存并释放了锁
        // 且释放的锁被当前线程获得，因此就会出现线程得到了锁，然而缓存已经更新的情况。
        // 因此要再查询一次缓存，可以节省数据库查询
        ShopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //从缓存中获取的数据再做一下判断，然后返回
        if(StrUtil.isNotBlank(ShopJson)){
            return JSONUtil.toBean(ShopJson,Shop.class);
        }
        //不存在，判断是否为null，防止缓存穿透
        if(ShopJson!=null){
            //为null，说明数据库中并没有该数据，返回null，表示店铺不存在
            return null;
        }
        //若是缓存中依然没有数据，就去查询数据库
        shop = getById(id);
        if(shop == null){
            //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //若数据库中存在，就重建缓存，并返回shop对象
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop));
        stringRedisTemplate.expire(key,CACHE_SHOP_TTL, TimeUnit.HOURS);
        //返回shop对象
    } catch (InterruptedException e) {
        e.printStackTrace();
    }finally {
        //释放锁
        unlock(LOCK_SHOP_KEY+id);
    }
    return shop;

}
```



我们可以用JMeter来进行并发请求的发送来测试代码：





### 逻辑过期方案实现

**流程图**

![image-20230728224359351](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230728224359351.png)



**代码示例**

封装了逻辑过期、任意对象的类，用于存储shop和逻辑过期时间

```java
@Data
public class RedisData {
    private LocalDateTime expireTime;
    private Object data;
}
```



```java
//创建一个线程池，里面包含10个线程
private static final ExecutorService CACHE_REBUILD_EXECUTOR =
        Executors.newFixedThreadPool(10);

public Shop queryWithLogicalExpire(Long id) {
    String key = CACHE_SHOP_KEY+id;
    //从redis中查询id对应数据是否存在
    String RedisDataJson = stringRedisTemplate.opsForValue().get(key);
    //未命中，说明数据库中没有该数据，直接返回空
    if(StrUtil.isBlank(RedisDataJson)){
        return null;
    }
    //缓存命中，获取缓存数据并转为RedisData对象
    RedisData redisData = JSONUtil.toBean(RedisDataJson, RedisData.class);
    //判断缓存是否逻辑过期
    LocalDateTime expireTime = redisData.getExpireTime();
    Shop shop = JSONUtil.toBean((JSONObject)  redisData.getData(), Shop.class);
    if(expireTime.isAfter(LocalDateTime.now())){
        //若未过期，直接返回
        return shop;
    }
    //若逻辑过期，尝试获取互斥锁
    String LockKey = LOCK_SHOP_KEY + id;
        //若未获得锁，直接返回店铺信息
        if(!tryLock(LockKey)){
            return shop;
        }
        //若线程获得锁，则创建新线程，通过lambda表达式执行重建缓存逻辑
        CACHE_REBUILD_EXECUTOR.submit(()-> {
            try {
                saveShop2Redis(id,20L);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            finally {
                //无论是否出异常，都要释放锁
                unlock(LockKey);
            }
        });
    //当前线程依然返回旧的shop对象，但是此时缓存已经重建
    return shop;
}


```



```java
/**
 * 保存封装了逻辑过期字段的对象
 * @param id
 * @param expireTime
 */
public void saveShop2Redis(Long id, Long expireTime) throws InterruptedException {
    Shop shop = getById(id);
    Thread.sleep(200);
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireTime));
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
}
```



我们通过JMeter来进行测试：

![image-20230729103726481](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729103726481.png)

![image-20230729103751912](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729103751912.png)

可以看到，前面的请求是102，后面的HTTP请求的数据变为了108茶餐厅，说明前面大量的请求由于逻辑过期，但没有得到锁而被拦截，等待缓存重建之后，锁释放了，后面的请求得到的数据是更新的数据。





## 封装方法

我们可以将上述的解决方案进行封装一个缓存类及其方法。这样，未来我们需要使用缓存，我们直接调用这个工具类就可以实现了：

```java
/**
 * 缓存工具类
 * 封装了所有缓存操作
 * 可以实现直接调用
 * 实现缓存穿透、缓存击穿
 */
@Component
public class CacheClient {
    @Resource
    private final StringRedisTemplate stringRedisTemplate;
    //构造器实现调用时外部注入stringRedisTemplate
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //保存数据到缓存
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        //发送过来的时间可能不是以秒为单位，因此这里要转换成秒unit.toSeconds(time)
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    /**
     * 实现缓存穿透通用接口
     * @param keyPrefix 指定key前缀
     * @param id 传入的数据库数据对应id
     * @param type 传输给前端的值
     * @param dbFallback 用于数据库操作的函数
     * @param time 设置缓存过期时间
     * @param unit 缓存过期时间单位
     * @param <R> 返回前端类型，采用泛型，可以对数据库设置的任意pojo进行使用
     * @param <ID> id不一定就是某一个数值类型，因此也使用泛型来表示
     * @return
     */
    public <R,ID> R queryByPassThrough(String keyPrefix, ID id, Class<R> type,
                                       Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //从redis中查询id对应数据是否存在
        String json = stringRedisTemplate.opsForValue().get(key);
        //若存在则将json转为shop对象并返回给前端
        if(StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        }
        //防止缓存穿透，判断返回的 json 数据是否为空字符串，若是，则说明数据库中不存在这条数据
        if(json!=null){
            return null;
        }
        //若不存在，则根据id查询数据库
        //这里使用了函数式编程，通过Function.apply()，外部可以指定任意的函数来调用这个逻辑.
        R r = dbFallback.apply(id);
        //若数据库中无对应数据，则先将空字符串存入缓存，避免缓存穿透，再返回错误信息
        if(r == null){
            //不存在，就给对应key的缓存设置一个空字符串，避免恶意用户反复并发查询导致数据库崩溃
            stringRedisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        //若数据库中有对应数据，则将其放入redis数据库中作为缓存
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(r));
        stringRedisTemplate.expire(key,time, unit);
        return r;
    }

    //创建一个线程池，里面包含10个线程
    private static final ExecutorService CACHE_REBUILD_EXECUTOR =
            Executors.newFixedThreadPool(10);


    public <R,ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type,
                                           Function<ID,R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix+id;
        //从redis中查询id对应数据是否存在
        String RedisDataJson = stringRedisTemplate.opsForValue().get(key);
        //未命中，说明数据库中没有该数据，直接返回空
        if(StrUtil.isBlank(RedisDataJson)){
            return null;
        }
        //缓存命中，获取缓存数据并转为RedisData对象
        RedisData redisData = JSONUtil.toBean(RedisDataJson, RedisData.class);
        //判断缓存是否逻辑过期
        LocalDateTime expireTime = redisData.getExpireTime();
        R r = JSONUtil.toBean((JSONObject)  redisData.getData(), type);
        if(expireTime.isAfter(LocalDateTime.now())){
            //若未过期，直接返回
            return r;
        }
        //若逻辑过期，尝试获取互斥锁
        String LockKey = LOCK_SHOP_KEY + id;
        //若未获得锁，直接返回店铺信息
        if(!tryLock(LockKey)){
            return r;
        }
        //若线程获得锁，则创建新线程，通过lambda表达式执行重建缓存逻辑
        CACHE_REBUILD_EXECUTOR.submit(()-> {
            try {
                R r1 = dbFallback.apply(id);
                Thread.sleep(200);
                setWithLogicalExpire(key, r1, time, unit);

            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                //无论是否出异常，都要释放锁
                unlock(LockKey);
            }
        });
        //当前线程依然返回旧的shop对象，但是此时缓存已经重建
        return r;
    }

    public boolean tryLock(String key){
        //使用 setnx 对应的 setIfAbsent 方法来设置键值，并设置延迟时间。避免死锁
        //这里的value目前随意，目前还用不上。就设为1吧
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        //这里返回的是一个包装类，为了防止出现空指针，这里我们使用hutool工具返回。
        return BooleanUtil.isTrue(flag);
    }
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
```

这块的代码中重点掌握**泛型的使用以及函数式编程**。



# 优惠券秒杀

## 全局ID生成器

![image-20230729201511697](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729201511697.png)



![image-20230729201528631](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729201528631.png)





![image-20230729201551454](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729201551454.png)



实现唯一ID，我们可以使用现成的雪花算法orUUID直接实现，也可以自定义一个类似的类来实现。我们根据上图的符号位表示来自定义类似于雪花算法实现一个自增的全局ID。

**开始时间戳的编写**

​	这里的main解释了这个开始时间戳是怎么得来的：

```java
/**
 * 开始时间戳
 */
private static final long BEGIN_TIME_STAMP =1672531200L;


public static void main(String[] args) {
    //获取开始时间戳，我们用2023年1月1日0时0分的秒数作为开始时间戳
    LocalDateTime time = LocalDateTime.of(2023, 1, 1, 0, 0);
    long second = time.toEpochSecond(ZoneOffset.UTC);
    System.out.println(second);
}
```



**生成时间戳**

​	我们有了开始时间戳，接下来要存储在缓存中的时间戳我们可以选择将当前时间减去开始时间戳来得到：

```java
//生成时间戳
LocalDateTime now = LocalDateTime.now();
long nowTime = now.toEpochSecond(ZoneOffset.UTC);
long timestamp = nowTime - BEGIN_TIME_STAMP;

```



**生成序列号**

```java
//生成序列号
//这里的设计思路是通过redis键的层级结构实现一个分类，有利于未来我们直接选择对应的日期查看缓存ID
String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
/*
让序列号实现自增长，redis的自增长是满足原子性的。因此可以直接使用
这里的count选了long类型，并不会出现空指针而拆箱失败，
因为redis在给不存在的键进行自增时，会自动添加到redis的数据库中
 */
long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
```

**拼接时间戳和序列号**

```java
/*
拼接返回全局ID
将生成时间戳和序列号进行二进制位的拼接，根据图片上的要求
第一步，要让时间戳左移位32
（这里的32是按照图片的要求来的，我们这里设置成一个变量，如果未来需求发生了改变，直接改变量即可）
然后再与序列号进行按位或操作
 */
return timestamp << COUNT_BITS | count;
```



测试类

测试能不能生成符合要求的id

这里使用了线程池实现多线程操作快速进行缓存自增操作（因为redis的自增符合原子性，不会造成线程安全问题）

```java
@SpringBootTest
class HmDianPingApplicationTests{
    @Resource
    RedisWorker redisWorker;

    ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
   void testWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task =() ->{
            for(int i = 0;i<100;i++){
                long id = redisWorker.nextId("order");
                System.out.println("id= " + id);
            }
            latch.countDown();
        };
        long begin = System.nanoTime();
        for(int i = 0 ;i < 300; i++){
            es.submit(task);
        }
        latch.await();
        long end = System.nanoTime();

        System.out.println("执行时间= "+ (end-begin));
    }


}
```



![image-20230729220943672](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729220943672.png)



## 添加秒杀券

阅读Controller源码，添加秒杀券的功能是通过发送请求实现的，因此下面演示该操作

打开idea内置的请求发送工具：

![image-20230731112652413](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731112652413.png)



选择Post参数文本：

![image-20230731112724836](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731112724836.png)



输入如下内容：

```java
POST http://localhost:8081/voucher/seckill
Content-Type: application/json

{
  "shopId": 1,
  "title": "100元代金券",
  "subTitle": "全场通用\\n无需预约\\n可无限叠加\\不兑现、不找零\\n仅限堂食",
  "payValue": 8000,
  "actualValue": 10000,
  "type": 1,
  "stock": 100,
  "beginTime": "2023-07-31T10:58:20",
  "endTime": "2023-08-05T10:58:20"
}
```

这里我们是通过直接发送请求来实现的，未来我们可以让前端写一个表单来实现发送这个请求。

请求发送之后，可以打开数据库查看是否更新，若成功，则打开页面，进入1号店铺，可以看到秒杀券显示在页面上了：

![image-20230731113009787](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731113009787.png)











## 优惠券秒杀下单功能实现

**流程图**

![image-20230729224111797](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230729224111797.png)





**代码**

```java
@Resource
private ISeckillVoucherService seckillVoucherService;
@Resource
private RedisWorker redisWorker;

@Override
@Transactional
public Result seckillVoucher(Long voucherId) {
    //查询优惠券
    SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
    //判断秒杀是否开始
    if(voucher.getBeginTime().isAfter(LocalDateTime.now())){
        return Result.fail("秒杀尚未开始！");
    }
    //判断秒杀是否结束
    if(voucher.getEndTime().isBefore(LocalDateTime.now())){
        return Result.fail("秒杀已经结束！");
    }
    //库存不足
    if(voucher.getStock() < 1){
        return Result.fail("秒杀券库存不足");
    }
    //更新库存
    boolean success = seckillVoucherService.update()
            .setSql("stock=stock-1")
            .eq("voucher_id", voucherId).update();

    //创建订单
    VoucherOrder voucherOrder = new VoucherOrder();
    long orderId = redisWorker.nextId("order");
    //订单id
    voucherOrder.setId(orderId);
    //用户id
    Long userId = UserHolder.getUser().getId();
    voucherOrder.setUserId(userId);
    //代金券id
    voucherOrder.setVoucherId(voucherId);
    save(voucherOrder);
    //返回订单id
    return Result.ok(orderId);
}
```



## 超卖问题

这里我们用JMeter模拟实现抢购的情况，我们短时间内发送200个请求来购买秒杀券：

![image-20230731142408393](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142408393.png)

![image-20230731142415773](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142415773.png)



这里注意需要加上用户登录请求头：

![image-20230731142437808](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142437808.png)



从返回的情况可以看到，在前面的请求已经出现秒杀券库存不足了，但是后面的请求还是抢到了秒杀券的情况：

![image-20230731142646294](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142646294.png)

![image-20230731142707386](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142707386.png)

我们回到数据库，查看一下数据：
![image-20230731142730082](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142730082.png)



**库存出现了负数！**

这说明出现了线程安全问题，我们这里需要设置锁来确保线程安全。



![image-20230731142916086](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731142916086.png)



**实现方案及原理**

方案1：

![image-20230731143740384](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731143740384.png)



方案2：

![image-20230731143813282](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731143813282.png)



按照上面的方案2思路（因为我们的数据库中没有设置version字段）：

我们可以修改更新数据库操作的代码：

```java
//更新库存
boolean success = seckillVoucherService.update()
        .setSql("stock=stock-1")
        .eq("voucher_id", voucherId).eq("stock",voucher.getStock())
        .update();
```



**明明有票，却库存不足**？

我们使用Jmeter测试一下代码

发现很快就出现了库存不足情况：

![image-20230731145850963](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731145850963.png)

但是我们查询数据库，里面仍然有库存：
![image-20230731145918372](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731145918372.png)



由此会发现还会有大量的票不能出售，但是库存仍然足够，这是因为多个线程同时进入，它们都查到了原来的Stock，而第一个进入修改库存stock的线程判断SQL符合条件之后修改了 stock 字段，进而导致其他已经查到旧 stock 数据的线程无法满足上面的 eq("stock",voucher.getStock()) 语句，从而导致SQL更新失败，进而导致出现明明有票，却库存不足的情况。

这说明我们的乐观锁的判定范围过大了，出现了不准确的判断。









为了解决这个问题，我们只需要再次查询数据库判断是否还有库存就可以了：

这里我们将语句改为 stock>0，就可以解决这个问题了：

```java
boolean success = seckillVoucherService.update()
        .setSql("stock=stock-1")
        .eq("voucher_id", voucherId).gt("stock",0)
        .update();
```

这回我们给数据库100个库存，并发送100个请求：

![image-20230731150111029](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731150111029.png)

查看数据库中stock的数值：
![image-20230731150129212](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731150129212.png)

显示为0，成功实现乐观锁



![image-20230731150238693](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731150238693.png)



## 一人一单

我们的秒杀券订单一个用户只能使用一次，因此要实现一人一单

**流程图**

![image-20230731151729855](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731151729855.png)

一人一单的关键实现在于根据优惠券id和用户id查询订单中是否已经存在，若存在就直接返回错误信息，若不存在，就进行下单操作。

我们可以在下单操作进行数据更新前写入如下逻辑的代码：

```java
//判断一下数据库中是否存在相同userId,相同voucherId的订单，若有，则直接返回
Integer count = query()
        .eq("user_id", userId)
        .eq("voucher_id", voucherId).count();
if(count>0){
    return Result.fail("一个用户只能购买一张秒杀券");
}
```

我们将上述代码及后续的订单操作代码共同封装在下面的函数中：

要注意实现事务处理，因为涉及到数据库的操作

```java
@Transactional
@Override
public Result createVoucherOrder(Long voucherId,Long userId){
    //判断一下数据库中是否存在相同userId,相同voucherId的订单，若有，则直接返回
    Integer count = query()
            .eq("user_id", userId)
            .eq("voucher_id", voucherId).count();
    if(count>0){
        return Result.fail("一个用户只能购买一张秒杀券");
    }

    //更新库存
    //使用乐观锁，乐观锁适合更新操作
    boolean success = seckillVoucherService.update()
            .setSql("stock=stock-1")
            .eq("voucher_id", voucherId).gt("stock",0)
            .update();

    if(!success){
        return Result.fail("库存不足");
    }

    //创建订单
    VoucherOrder voucherOrder = new VoucherOrder();
    long orderId = redisWorker.nextId("order");
    //订单id
    voucherOrder.setId(orderId);
    //用户id
    Long Id = UserHolder.getUser().getId();
    voucherOrder.setUserId(Id);
    //代金券id
    voucherOrder.setVoucherId(voucherId);
    save(voucherOrder);
    //返回订单id
    return Result.ok(orderId);
}
```







但是这里有一个问题：

```
出现多个线程同时访问，
 而第一个线程还未更新订单，同时其他线程查询到数据库中的count仍然是0，
 因此也往下更新了数据库操作，造成了线程安全问题
```

这里的实现方案是使用悲观锁，通过用户id作为对象来使用锁：

这里调用了 intern方法，它可以确保字符串对象是唯一的

```java
 /*
        这里对整个方法加上了悲观锁，因为若没有加，会出现多个线程同时访问，
         而第一个线程还未更新订单，同时其他线程查询到数据库中的count仍然是0，
         因此也往下更新了数据库操作，造成了线程安全问题
        */

 /*
        因为String是不可变的，那么通过toString方法每次调用都会产生一个新的对象
        这里需要再调用一下 intern()方法，它会先去内存中查找是否存在着相同内容的对象，
         若有则直接返回找到的String对象，没有再创建新的String对象
        */
synchronized (userId.toString().intern()){
    /*createVoucherOrder设置了事务操作
    然而同类中调用本类方法会出现事务失效,
    因此我们要获取代理对象来调用createVoucherOrder方法获取返回值
    要实现事务，必须使用Spring代理的对象
     */
    IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
    return proxy.createVoucherOrder(voucherId,userId);
}
```

这里为了实现事务操作，需要使用代理对象来调用本方法。

具体的原因看下面的截图：

![image-20230731154653185](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731154653185.png)



> 这里附上一个Spring事务失效的情况、原因及解决方案的博客：

[spring事务失效的几种场景以及原因 - 个人文章 - SegmentFault 思否](https://segmentfault.com/a/1190000041475241)



这里还要注意，要加上AspectJ的依赖：

```xml
<dependency>
    <groupId>org.aspectj</groupId>
    <artifactId>aspectjweaver</artifactId>
</dependency>
```

然后要设置暴露代理的配置：

![image-20230731161408975](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731161408975.png)







# 分布式锁

## 集群模式下一人一单的并发安全问题

![image-20230731164641780](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731164641780.png)

为了实现负载均衡，我们会设置多个端口来作为服务器，那么会出现相同用户发送多个下单请求，但是这些请求分别发送给了不同的端口，那么我们之前设置的锁就失效了。这是因为两个服务器都有各自的JVM和锁监视器，导致它们是相互独立的。



为了解决上面的问题，我们需要一个能够在多个服务器都能识别的锁，这个就是分布式锁

![image-20230731165640855](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731165640855.png)

在同一个锁监视器下执行，实现多服务器下的线程安全



![image-20230731165619059](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731165619059.png)



![image-20230731170252691](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731170252691.png)





## 基于Redis的分布式锁实现原理

**获取锁**：

使用 setnx，设置过期时间（避免如果Redis宕机了，出现死锁的情况）

这里我们实现添加锁和过期时间两个动作是必须要么都成功，要么都失败，必须保证原子性，因此：

这里为了确保在 setnx 和 expire 创建key和设置过期时间是两个步骤，然而有可能出现Redis宕机在两个步骤之间，也会导致死锁，那么就需要保证原子性，可以使用：

```
SET lock thread1 EX 10 NX
```

同时确保了两个步骤。

对于两个线程来说，一个线程调用上述命令获取了锁，那么另一个线程执行了同样的命令会有两种情况：

1. 处于阻塞的状态
2. 执行失败，直接退出

这里采用非阻塞的方式，因为阻塞会大量消耗资源。只需要做到尝试一次，成功返回true，失败返回false即可。



**释放锁**：

1. 手动释放

```
DEL key
```

2. 超时释放



![image-20230731171252178](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230731171252178.png)



## 分布式锁实现

![image-20230802082002373](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230802082002373.png)

这里写一下该分布式锁的接口

```java
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功，false代表获取锁失败
     */

    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();

}
```

下面是该接口的实现类：

```java
public class SimpleRedisLock implements ILock{
    //作为锁的名称
    private String name;

    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX+name;
        //获取线程标识
        String value = String.valueOf(Thread.currentThread().getId());
        //进行设置字段操作
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);

        //这里将其转换为boolean类型返回
        return BooleanUtil.isTrue(success);

    }

    @Override
    public void unlock() {
        //释放锁
        stringRedisTemplate.delete(KEY_PREFIX+name);
    }
}
```



修改订票的逻辑：

```java
SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
boolean isLock = simpleRedisLock.tryLock(1200);
if(!isLock){
    return Result.fail("不允许重复下单");
}
```

```java
try{
    IVoucherOrderService proxy =(IVoucherOrderService) AopContext.currentProxy();
    return proxy.createVoucherOrder(voucherId,userId);
}finally {
    //如果出现异常，也要释放锁
    simpleRedisLock.unlock();
}
//获取锁成功，就执行业务逻辑
```

启动两个服务器端口，然后使用Postman发送两个请求测试即可。



## 分布式锁误删风险

我们上面设计的分布式锁有个风险：

当我们第一个线程获取到锁之后，它因为某些原因被阻塞了，从而导致锁的超时释放，进而被线程2获取到锁，执行其业务，而线程2还未完成前，线程1阻塞结束，继续执行业务，当线程1执行完自己的业务后会释放锁，进而释放了线程2的锁，此刻线程3进入获取了锁，执行其业务，此时此刻，就会出现线程2和线程3同时执行业务，这就有可能出现线程安全问题。

此情况出现在使用相同的key的分布式锁下。

![image-20230802084514597](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230802084514597.png)



要解决上述问题，我们需要在释放锁之前，判断一下当前的锁是否是自己获取到的。我们之前给锁设置了 value 属性的值是线程的id，因此我们可以使用这个来作为判断，如果当前线程判断是自己的线程id，就释放锁，如果不是，就不释放。

**流程图**

![image-20230802084922736](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230802084922736.png)



![image-20230802085600103](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230802085600103.png)



>  这里为啥要用uuid呢？

在一个jvm中，我们知道，线程标识是一个递增的序列，它们并不会重复，但是我们还需考虑到多个jvm（负载均衡）的情况，在不同的jvm下，可能会出现相同的线程标识，因此直接使用线程标识作为值去判断是远远不够的，而我们通过加入uuid拼接上线程标识，可以更好地确保唯一性。





## 分布式锁的原子性问题

在我们上面看似解决了误删问题，但是还会有下面的情况：

![image-20230802090012061](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230802090012061.png)

这里线程1获取锁执行业务，业务执行完毕后，判断锁标识是自己的，将要开始释放锁的时候，由于JVM的垃圾回收机制，出现了full GC，导致线程阻塞，进而锁被超时释放，此时线程2进入并获取锁执行业务，业务执行一半的时候，线程1阻塞状态结束，执行释放锁（因为前面已经判断过了），就导致线程2的锁被释放，此时线程3获取锁，执行业务，就又出现了线程安全问题。



## Lua脚本

> 一个可以操作redis并实现原子性的脚本

为了解决上面的问题，我们需要确保判断锁一致以及释放锁这两个功能是保持原子性的，需要它们要么同时实现，要么都失败。

使用Redis的incrby可以实现原子性，但是在事务逻辑上的实现比较困难

为了实现Redis在事务逻辑层面上的原子性，可以使用 Lua 脚本

![image-20230803094443024](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803094443024.png)



![image-20230803094725271](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803094725271.png)

**示例**

```lua
 EVAL "return redis.call('set', 'name', 'jack')" 0
```

![image-20230803095604347](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803095604347.png)

最后的 ‘0’，是指定的参数个数



**设置自定义参数的示例**

```lua
"return redis.call("set", KEYS[1], ARGV[1])" 1 name Rose
```



**分布式锁原子性实现**

按照我们上面的需要，将判断逻辑和删除锁逻辑写在一个脚本中，因此有下面的流程：

![image-20230803100334307](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803100334307.png)

可以新建一个.lua 文件，然后将上面的内容写入,我们可以直接在IDEA的类路径下创建一个unlock.lua，并写入上面的内容：

```lua
-- KEYS[1]，指的是锁的key，ARGV[1],指的是当前线程标识

-- 从redis中获取的线程标识是之前加锁线程的线程标识
-- 传入的线程标识是当前执行代码线程的线程标识
-- 获取线程标识，判断是否和传入的线程标识一致
if(redis.call('GET', KEYS[1] == ARGV[1])) then
    -- 若线程标识一致，说明是同一个线程执行代码，那么就删除键来释放锁
    return redis.call('DEL',KEYS[1])
end
--若不是同一个线程，那么就直接返回
return 0
```

![image-20230803104935471](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803104935471.png)

要有如下标识才能正常使用：

![image-20230803104953231](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803104953231.png)



然后回到我们实现锁的逻辑SimpleRedisLock类，编写一个DefaultRedisScript，这个是用来配置脚本的基本信息的，并让其在类加载时执行初始化，并设置如下内容：

1. 设置脚本路径
2. 设置脚本返回值类型

```java
//设置lua脚本对象,泛型指定可以返回值
private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
static {
    /*
     这个构造方法中可以传入字符串，
     字符串表示可以直接写入lua脚本内容，
     这种对于只需要编写一条很短的lua脚本来说是方便的
     但是我们的lua脚本长度较长，因此不直接使用这种方式
    */
    UNLOCK_SCRIPT = new DefaultRedisScript<>();
    /*
    设置lua脚本所在的路径，
     new ClassPathResource("unlock.lua")，可以从Resource目录下去查找脚本
    */
    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
    //设置脚本的返回值类型
    UNLOCK_SCRIPT.setResultType(Long.class);

}
```



在unlock方法中执行这个脚本，我们需要使用RedisTemplate的excute方法：

```java
@Override
public void unlock() {
    //判断一下当前的标识是否一致
    long ThreadId = Thread.currentThread().getId();
    String value = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
    //执行lua脚本
    stringRedisTemplate.execute(UNLOCK_SCRIPT,
            Collections.singletonList(KEY_PREFIX+name),
            uuid+ThreadId);
}
```

1. 需要向其指定脚本对应的对象UNLOCK_SCRIPT
2. 设置一个集合，集合是用来指定脚本执行的KEY的，由于key可以有多个，因此需要传入集合，可以使用Collections.singletonList() 来传入
3. 最后是一个可变参数，要传入lua脚本中需要的参数







## Redisson

我们上面的分布式锁实现是基于setnx的，它已经**能解决大多数**的情况，但是仍然有优化的空间，比如下图的问题：

![image-20230803110821602](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803110821602.png)







Redssion是一个实现各类锁的框架，可以帮助我们快速搭建一个分布式锁

![image-20230803110556775](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803110556775.png)

[官网地址](https://redisson.org)

[GitHub网址](https://github.com/redisson/redisson)



###Redisson快速入门

1. 引入依赖

```java
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.13.6</version>
</dependency>
```



2. 配置Redisson客户端

```java
@Configuration
public class RedissonConfig {
    @Bean
    public RedissonClient redissonClient(){
        //新建配置类，这个配置是Redisson的Config类，不是jdk自带的
        Config config = new Config();
        //设置redis服务器地址和密码
        config.useSingleServer().setAddress("redis://你的IP地址:6379").setPassword("123321");
        //返回该配置下生成的RedissonClient对象
        return Redisson.create(config);
    }
}
```



3. 使用Redisson锁功能

​	我们使用它来实现之前的订单服务功能：

​	我们只需要从SpringIOC容器中获取到配置的对象，然后调用tryLock和unlock方法就可以实现上锁和释放锁

你可以给 tryLock 设置等待时间，让线程在等待时间内若未获得锁就会不断重试获取锁，实现可重试的功能

```java
//使用Redisson框架的分布式锁实现一人一单功能
RLock lock = redissonClient.getLock("lock:order:" + userId);
//设置无参，若锁未获取成功，就直接返回false，符合当前业务需求。未来若需要能够重试获取锁，可以设置锁的延迟时间
boolean isLock = lock.tryLock();
if(!isLock){
    return Result.fail("不允许重复下单");
}
```

使用JMeter进行并发测试，发现数据库中订单只多了一个，秒杀券仓库也只少了一个库存，符合一人一单：

![image-20230803142524841](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803142524841.png)

![image-20230803142531993](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803142531993.png)





### Redisson可重入锁原理

**可重入：**

同一个线程可以多次获取同一把锁

举例：

调用一个方法时，它首先给自己上了锁，然后执行业务逻辑过程中，它会调用另一个方法，而另一个方法也会先去获取锁，而第二个方法获取的锁要是和第一次使用的是同一把锁，这就是可重入锁。

![image-20230803150306698](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803150306698.png)

解释：

> 在使用trylock的时候，Redisson会在Redis中创建一个Hash结构的键值对，它的键是我们自定义的键，而值是随着我们调用tryLock的次数递增的，一个方法获取了锁，先创建key，然后让value+1,如果方法内又调用了其他方法，同时该方法也去获取锁，在判断完锁是当前线程的之后，它也会让value+1，如果锁不是当前线程的锁，那么就会直接返回。如果要释放锁，它也会先判断锁是否是当前线程的，若是，则让value-1（注意，这里没有使用del key来删除锁），若不是，则直接返回，一旦value=0之后，就说明全部的锁被释放，那么就可以正式地释放锁，也就是调用del key 来释放锁。

这就实现了可重入锁。





### Redisson 可重试原理

![image-20230803221523975](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803221523975.png)





## Redission的MultiLock机制



### 问题提出

**主从一致性问题**

在Redis 主从模式中，服务是由一个 Redis 主节点 (**Master**)以及若干个从节点（**Slave**）组成。

当 Redis 提供了主从集群，一个 Java 应用获取锁，当主节点写入锁之后，进行主从同步的过程中，由于主从同步出现延迟，一旦出现主节点宕机，主从同步仍然未完成，并且 Redis 哨兵监控到主节点宕机，其会在 Slave 中选出一个作为新的主节点，由于此前的主从同步并未完成， 原来的 Java 应用访问新的主节点，而原来的锁已经失效了（因为原来的锁保存在宕机的主节点，而新的主节点并不会保存原来的锁），故而会导致其他的进程进入，就会出现并发安全问题。

![image-20231129215103574](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231129215103574.png)



### 解决方案

若是我们将所有的节点都改为主节点，当线程获取锁时，会对所有节点进行获取锁的操作并生成各个节点对应的键值对。这样，当一个主节点宕机时，其他的节点仍然存在锁，就可以解决主从一致性问题了。![image-20231129215315101](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231129215315101.png)

### 原理解析

原理可以通过追溯 Redission 的 MultiLock 的 tryLock 和 unLock 方法来得到：

```java
public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException {
    long newLeaseTime = -1L;
    if (leaseTime != -1L) {
        if (waitTime == -1L) {
            newLeaseTime = unit.toMillis(leaseTime);
        } else {
            newLeaseTime = unit.toMillis(waitTime) * 2L;
        }
    }

    long time = System.currentTimeMillis();
    long remainTime = -1L;
    if (waitTime != -1L) {
        remainTime = unit.toMillis(waitTime);
    }

    long lockWaitTime = this.calcLockWaitTime(remainTime);
    int failedLocksLimit = this.failedLocksLimit();
    List<RLock> acquiredLocks = new ArrayList(this.locks.size());
    ListIterator iterator = this.locks.listIterator();

    while(iterator.hasNext()) {
        RLock lock = (RLock)iterator.next();

        boolean lockAcquired;
        try {
            if (waitTime == -1L && leaseTime == -1L) {
                lockAcquired = lock.tryLock();
            } else {
                long awaitTime = Math.min(lockWaitTime, remainTime);
                lockAcquired = lock.tryLock(awaitTime, newLeaseTime, TimeUnit.MILLISECONDS);
            }
        } catch (RedisResponseTimeoutException var21) {
            this.unlockInner(Arrays.asList(lock));
            lockAcquired = false;
        } catch (Exception var22) {
            lockAcquired = false;
        }

        if (lockAcquired) {
            acquiredLocks.add(lock);
        } else {
            if (this.locks.size() - acquiredLocks.size() == this.failedLocksLimit()) {
                break;
            }

            if (failedLocksLimit == 0) {
                this.unlockInner(acquiredLocks);
                if (waitTime == -1L) {
                    return false;
                }

                failedLocksLimit = this.failedLocksLimit();
                acquiredLocks.clear();

                while(iterator.hasPrevious()) {
                    iterator.previous();
                }
            } else {
                --failedLocksLimit;
            }
        }

        if (remainTime != -1L) {
            remainTime -= System.currentTimeMillis() - time;
            time = System.currentTimeMillis();
            if (remainTime <= 0L) {
                this.unlockInner(acquiredLocks);
                return false;
            }
        }
    }

    if (leaseTime != -1L) {
        List<RFuture<Boolean>> futures = new ArrayList(acquiredLocks.size());
        Iterator var24 = acquiredLocks.iterator();

        while(var24.hasNext()) {
            RLock rLock = (RLock)var24.next();
            RFuture<Boolean> future = ((RedissonLock)rLock).expireAsync(unit.toMillis(leaseTime), TimeUnit.MILLISECONDS);
            futures.add(future);
        }

        var24 = futures.iterator();

        while(var24.hasNext()) {
            RFuture<Boolean> rFuture = (RFuture)var24.next();
            rFuture.syncUninterruptibly();
        }
    }

    return true;
}
```

1. 方法签名：`public boolean tryLock(long waitTime, long leaseTime, TimeUnit unit) throws InterruptedException`
   - 这是一个公共方法，返回一个`boolean`值，表示是否成功获取所有锁。
   - 方法接受三个参数：`waitTime`表示尝试获取锁的等待时间，`leaseTime`表示锁的持有时间，`unit`表示时间单位。
2. 参数处理：
   - 根据`leaseTime`的值来计算新的锁的持有时间`newLeaseTime`，如果`leaseTime`为-1，则表示不设置持有时间；如果`leaseTime`不为-1，则根据`waitTime`的值来决定新的持有时间。
   - 将`waitTime`转换为毫秒并赋值给`remainTime`，用于计算剩余等待时间。
3. 锁的获取过程：
   - 根据锁的数量创建一个空的`acquiredLocks`列表，用于存储获取成功的锁。
   - 使用`listIterator`迭代锁列表。
   - 对于每个锁，根据`waitTime`和`newLeaseTime`尝试获取锁。
   - 如果获取成功，则将锁添加到`acquiredLocks`列表中。
   - 如果获取失败，则根据`failedLocksLimit`判断是否继续尝试获取锁。
   - 如果达到了失败锁的限制次数，则结束循环。
   - 如果仍有剩余的等待时间，则更新剩余时间并继续循环。
4. 锁的续约：
   - 如果`leaseTime`不为-1，则对每个成功获取的锁执行续约操作。
   - 创建一个`futures`列表，用于存储续约的异步结果。
   - 遍历`acquiredLocks`列表，将每个锁的持有时间转换为毫秒，并执行异步的续约操作，将异步结果添加到`futures`列表中。
   - 遍历`futures`列表，等待所有续约操作完成。
5. 返回结果：
   - 返回`true`表示成功获取所有锁。
   - 如果获取锁的过程中出现异常或超时，则返回`false`。

总体而言，这段代码用于尝试获取多个锁，根据指定的等待时间和持有时间进行控制。它会逐个尝试获取锁，并根据获取结果进行处理，最终返回是否成功获取所有锁。如果成功获取锁，则可以选择对锁进行续约操作。



### 总结

![image-20230803221501031](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803221501031.png)



![image-20230803223331394](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230803223331394.png)





# 异步秒杀优化

## 提高并发效率

我们上面的下单流程，查询优惠券、查询订单、减库存和创建库存的操作都是直接跟数据库交互的。按照我们之前的设想，下单的操作是必须对数据库进行更新的。因此若是有大量的用户（和之前解决超卖问题不一样，这里是大量不同的用户）进行下单操作，那么每个用户都符合前面的一人一单的逻辑，就都会执行数据库的操作，那么对数据库的压力也是很大的。

我们需要找到一种方法，能够让这个过程的时间减少，就例如现实中餐厅的业务操作，如果只有一个厨师经营一整个餐厅，那么这位厨师就需要招待顾客、填写顾客订单、制作菜品、供给顾客、收取费用······，操作全部由一人完成，这非常耗时，而如果能再来一位服务员，帮助厨师完成了招待顾客，填写顾客订单，供给顾客和收取费用的工作，那么厨师的工作就轻松了。同理，在进行下面的秒杀业务逻辑时，我们可以**将部分流程放到一个线程中执行，将一部分流程放到新的线程中执行**。



**将整个业务转化为两个执行部分分别执行，进而提高效率**



因此我们可以将判断秒杀库存和校验一人一单提前到**Redis中去检测，在检测完成校验成功之后，将校验的数据信息放到阻塞队列中，随后开启一个新线程携带阻塞队列中的信息去执行数据库的业务逻辑**：

![image-20230805160550303](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230805160550303.png)







**实现流程**

​	**redis存储方式**

​	对于在redis中判断秒杀库存和校验一人一单的功能，我们需要将数据库中的秒杀券信息存储在redis中，由秒杀券库存信息的性质可以判断直接使用**String类型**去存储即可，而对于校验一人一单，用户id是唯一的，而一个类型的秒杀券有很多张，可以被多个用户购买，但是每个用户只能买一次，因此，**需要一个集合类型来存储多个用户，而用户又必须唯一，所以可以使用Set集合**。

对于左图的实现逻辑，这个判断要**满足原子性**，为了实现原子性，可以使用Lua脚本，设置两个key，第一个用于存储秒杀券库存，另一个用于存储订单对应的用户id，而这个用户id要是唯一的（为了满足一人一单，而且唯一也可以用来判断用户是否下单），判断完用户下单后，根据是否下单返回对应的值来给后续逻辑判断：

![image-20230805160711349](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230805160711349.png)





**存储库存信息到redis**

```java
 @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 保存优惠券
        save(voucher);
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);
		// 保存秒杀券库存到redis
     stringRedisTemplate.opsForValue().set(SECKILL_STOCK_KEY+voucher.getId(),voucher.getStock().toString());
   }

```



**实现判断库存和用户下单的lua脚本**

为了实现判断过程的原子性，因此要采用lua脚本。

```lua
-- 订单id
local voucherId = ARGV[1]

-- 用户id
local userId = ARGV[2]

-- 数据key
    -- 库存key
--在lua脚本中，拼接字符串使用的是..
local stockKey = 'seckill:stock:' .. voucherId
    -- 订单key
local orderKey = 'seckill:order:' .. userId

--脚本业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <=0 ) then
    return 1
end

-- 判断用户是否下单，若已经下单，则返回2，若未下单，则进行下单操作
--因为是 set 集合，则直接使用 sismember 函数来判断是否存在用户id即可
if(redis.call('sismember',orderKey, userId) == 1) then
    return 2
end

-- 如果上述判断中，库存充足，用户未下单，则执行下单业务
-- 库存减少
redis.call('incrby',stockKey,-1)
-- 添加用户id到订单中
redis.call('sadd',orderKey,userId)
return 0
```







## 阻塞队列

![image-20230911092014133](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230911092014133.png)

**BlockingQueue：**

当阻塞队列中没有元素，若线程尝试对队列获取元素，就会被阻塞。

我们可以使用阻塞队列，来存储订单的信息，实现抢单的操作，然后开辟新的线程来执行下单的操作

**阻塞队列声明**

```java
//阻塞队列
private BlockingQueue<VoucherOrder> orderTasks =
        new ArrayBlockingQueue<>(1024*1024);
```



**新线程对应线程池**

```java
private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
```



我们让订单创建处理操作这个需要新线程执行的流程在项目初始化完成时就不断执行，这样就会不断地从阻塞队列中去取出

```java
//项目初始化的时候就不断执行这个线程操作
@PostConstruct
private void init(){
    SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
}
```



具体线程执行的操作：

这里执行子线程操作的方法是通过编写一个内部类，实现Runnable接口，实现对应的run方法

run方法中，要取出阻塞队列中的数据，然后执行对应的下单逻辑handleVoucherOrder，这个线程方法是在类初始化后，不断地执行，因此需要使用一个死循环，让它不断地从阻塞队列中取出数据，如果没有数据，阻塞队列会抛出异常，程序自动拦截异常。如果在handleVoucherOrder中执行出现了异常，也会被拦截

```java
private class VoucherOrderHandler implements Runnable{

    @Override
    public void run() {
        while(true){
            try {
                //从阻塞队列中取出订单
                VoucherOrder voucherOrder = orderTasks.take();
                handleVoucherOrder(voucherOrder);
            } catch (InterruptedException e) {
                //若并没有订单，则直接被拦截，
                log.error("处理订单异常",e);
            }
        }
    }

}
```



**下单业务逻辑**

和之前写的类似，也是线程安全判断一下，加一下锁，然后执行创建订单逻辑，最后释放锁。因为前面Redis脚本中已经完成了对线程的判断，实际可以不用加锁，当为了防止lua脚本判断出现问题，这里还是加一下。

```java
   //处理下单业务逻辑
    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
//        使用Redisson框架的分布式锁实现一人一单功能
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //设置无参，若锁未获取成功，就直接返回false，符合当前业务需求。未来若需要能够重试获取锁，可以设置锁的延迟时间
        boolean isLock = lock.tryLock();
        if(!isLock){
            log.error("不允许重复下单");
        }
//        /*
//        因为String是不可变的，那么通过toString方法每次调用都会产生一个新的对象
//        这里需要再调用一下 intern()方法，它会先去内存中查找是否存在着相同内容的对象，
//         若有则直接返回找到的String对象，没有再创建新的String对象
//        */
//        synchronized (userId.toString().intern()){
            /*createVoucherOrder设置了事务操作
            然而同类中调用本类方法会出现事务失效,
            因此我们要获取代理对象来调用createVoucherOrder方法获取返回值
            要实现事务，必须使用Spring代理的对象
             */
        try{
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            //如果出现异常，也要释放锁
            lock.unlock();
        }
    }
```



### 阻塞队列的问题

由于阻塞队列本身还是使用的JVM的内存，我们需要在创建阻塞队列时指定它的大小，对于一个大型的下单业务情况来说，可能会因为大量的订单导致阻塞队列存储空间不足，内存泄露，还有一个问题，JVM如果一旦重启，宕机等需要重启时，就会导致阻塞队列中存储的新信息消失，因此，还需要进一步改进。



# Redis消息队列



为了解决阻塞队列需要使用 JVM 内存的问题，阻塞队列主要是为了连通 Redis 中判断满足一人一单以及订单库存要求的订单和数据库的交互，因此我们需要一个能够在 JVM 外部实现阻塞队列功能的一个工具来代替阻塞队列，而这里我们可以使用 Redis 消息队列。 



![image-20230908091631291](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230908091631291.png)

我们对消息队列的要求是：

1. 要能够接受从 Redis 中发送过来的订单数据，然后将该数据发送给数据库处理
2. 要实现持久化，不能出现传输过程的数据丢失







### List结构的消息队列

![image-20230911092417725](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230911092417725.png)



**操作方式**

下图的两个命令：

**删除元素**

![image-20231130214225800](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130214225800.png)

B：Block，阻塞

L和R分别表示从双端队列的哪一侧进行删除元素

它们可以实现类似于阻塞队列的效果，可以给这两个命令设置超时等待时间，如果消息队列中没有元素，它们就会等待其他进程向里面添加元素直到超时时间过期。如果有元素添加，它们会将其取出并从队列中删除。

**添加元素**

![image-20231130214352546](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130214352546.png)

从左边添加元素

其对应的还有右侧添加元素：

![image-20231130214548779](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130214548779.png)



1. 添加

```bash
LPUSH KEY VALUE1 VALUE2 ...
```

2. 指定时间内，移除元素，若不存在元素，则阻塞等待

```bash
BRPOP KEY TIMES
```



**优缺点**

![image-20230911092641418](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230911092641418.png)

这里解释一下缺点：

1. List结构的消息队列一旦其中的数据被取出后就会自动从消息队列中删除，而如果在后续处理数据的过程中出现了异常或者宕机的情况，就会导致这个消息直接丢失。
2. 为什么只支持单消费者呢？因为List结构的消息队列取出的数据只能给一个消费者获取，获取后它就删除了，其他消费者无法再获取这个数据。





### PubSub消息队列结构

支持进行消息订阅和消息发布操作。

**订阅消息：**

1. 直接指定订阅名称

```
SUBSCRIBE Message
```

2. 使用通配符进行模糊查询

``` bash
PSUBSCRIBE [Message]
```

这里的Message可以变化如下：

![image-20230912160137811](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912160137811.png)

**发布消息：**

``` bash
PUBLISH MESSAGE [你希望发布的内容]
```

优点：

+ 采用发布订阅模型，支持多生产，多消费

缺点：

+ 不支持数据持久化

  解释：Pub/Sub设计是用来做消息发布订阅的，而如果没有任何一个消费者订阅某个频道，那么这个频道发布的数据就丢失了
+ 无法避免消息丢失

  + 这很显然，和上一条一样的原因

+ 消息堆积有上限，超出时数据丢失

  + 发送消息时，若有消费者，那么数据会在消费者处进行缓存并处理，若消息发布速度非常快，而消费者处理数据的速度较慢，一旦订阅的消息超出了消费者缓存的上限，就会造成数据的缺失








### Stream消息队列

Stream 是 Redis 5.0 版本中新增的一种数据结构，它是一个高性能、持久化的消息队列，可以用于实现消息的发布和订阅。Stream 可以看作是一个有序的消息队列，每个消息都有一个唯一的 ID，可以根据 ID 进行消息的查找、删除和确认。在 Stream 中，消息以键值对的形式存储，可以存储任意类型的数据。Stream 还支持多个消费者组，每个消费者组可以独立消费消息，避免消息重复消费。Stream 的引入使得 Redis 在消息队列领域更具竞争力，同时也为开发者提供了一种高效、可靠的消息处理方式。

**添加数据：**

![image-20231130223320148](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130223320148.png)

具体的可变参数解释如下：

1. key ：键

2. NOMKSTREAM：是否默认创建队列
3. MAXLEN：设置消息队列的最大消息阈值，一旦超出就会删除前面的消息
4. ''*''  表示根据 Redis 默认的生成方式生成消息的唯一ID
5. field value 可以写入多个，表示发送到队列中的消息，称为 Entry

![image-20231130223616096](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130223616096.png)

``` bash
XADD Key ID field1 value1 [field2 value2 ...]
```

![image-20230912162147141](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912162147141.png)



**读取数据：**

![image-20231130223631210](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130223631210.png)



![image-20231130223901926](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130223901926.png)

**XREAD的问题**

用 `XREAD` 命令来读取消息队列有一个问题，上面的命令可以看到：

我们使用了 $ 来获取最新的消息，然而如果在我们后续处理消息： `handleMessage(msg)`  过程的时间较长，而此时有多条消息进入到消息队列当中，此时我们还未去执行 `XREAD` 命令，最终就会导致出现漏读的情况。



**XREAD的特点**

![image-20231130224337840](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231130224337840.png)



``` 
XRANGE key start end [COUNT count]
```

返回指定 Stream 中指定范围内的消息，范围由 start 和 end 指定，可以使用 “-” 表示最大或最小 ID，COUNT 参数表示返回消息的数量。



![image-20230912162458531](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912162458531.png)



![image-20230912162535473](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912162535473.png)



``` 
XRANGE key end start  [COUNT count]
```

同 XRANGE 命令，但是返回的消息是逆序的。

**阻塞读取：**

``` 
XREAD [COUNT count] [BLOCK milliseconds] STREAMS key [ID]
```

从指定 Stream 中读取消息，可以指定读取的消息数量和阻塞时间，如果没有新的消息，则等待指定时间后返回空结果。

![image-20230912162922708](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912162922708.png)

基于stream这个操作，可以进行如下业务开发操作：

![image-20230912163002206](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912163002206.png)



**特点：**

![image-20230912163214082](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20230912163214082.png)



### 读取Stream消息

**消费者组**

将多个消费者放到同一个组中去监听同一个队列。



![image-20231201185349186](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201185349186.png)



![image-20231201184721706](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201184721706.png)

这里每次读一条消息，下次读的都是新的消息。

对于ID，指定>，就是从消息队列中读取消息，而指定0，就是从pending-list中读取消息

XACK 命令，可以用来对消息进行确认。而 PendingList 是一个被消费者读过，但是未被ACK确认的消息的集合（若已经确定了，会将该消息从PendingList中删除。）

![image-20231201191859128](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201191859128.png)

```shell
XACK key group 消息ID
```

实现确认对应ID的消息。如果你没有自定义消息ID，那么消息的ID是下图中所示的：
![image-20231201192229701](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201192229701.png)



**但是XACK并不是万能的**。如果将来某个时刻，突然加入了一条新的消息，但是此时，它并未被 XACK 命令执行确认，导致它一直处于PendingList中无法被确认。就导致了数据漏读的情况。因此需要一个命令能够显示出所有未被确认的消息：

```
XPENDING key group start end  num
```

key：指定的消息对应的key

group：指定组

start：消息读取的起始位置

end：消息读取的终止位置

+ 如果希望读取所有位置的消息，可以指定strat end :  `- +` 

num：读取的消息个数

```
XPENDING s1 g1 - + 10
```

通过这个命令就可以读取当前未被确认的消息



这是通过 Java 实现的一个消费者组读取消息的操作：

![image-20231201193929376](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201193929376.png)



**消费者组读取的特点如下：**

![image-20231201194125932](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201194125932.png)

对于一组的消费者把数据读取了，但是另一个组的消费者仍然可以再次读取消息，因此消息可以支持回溯。

对于同消费者组的成员，它们之间是争抢消息的，这就可以加快消费速度

可以设置Block时间，实现阻塞读取



## 几个消息队列的区别

![image-20231201194348622](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201194348622.png)



## 使用 Stream 消息队列实现异步秒杀

> 来回忆一下我们的项目，我们知道，我们通过 Lua 脚本完成了一系列的库存判断，一人一单，之后通过返回一个数值给 Java 代码来判断是否完成下单，然后将订单放到阻塞队列中去进行真正的下单操作。但是由于阻塞队列的大小依赖于 JVM 的内存上限，如果 JVM 内存不足，就会导致阻塞队列无法使用。那么，我们可以通过创建订单的操作放在 Redis 的 Stream 消息队列中去实现，来解决这个问题。

我们来思考一下如何使用 Stream 消息队列来解决：

先来看一下阻塞队列具体的业务逻辑：

<img src="Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201195445976.png" alt="image-20231201195445976" style="zoom:67%;" />

显然，它是在经过判断了下单操作合法之后，新建了一个订单，然后将订单信息保存在阻塞队列中。



我们后续对阻塞队列的操作，也仅仅是在一个无限循环的线程中取出存储在阻塞队列中的订单：

<img src="Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201195556966.png" alt="image-20231201195556966" style="zoom:67%;" />



**那么，我们只需要把订单信息存到 Redis 的 Stream 消息队列中，不就相当于实现阻塞队列的操作了！**

因此，我们可以得出下面的逻辑：

1. 创建一个 Stream 消息队列（stream.orders），用来保存订单信息
2. 修改之前的 Lua 脚本，在认定当前用户符合抢单条件之后，直接向 stream.orders 中添加消息，消息包含 voucherId、userId、orderId
3. 项目启动时，开启一个线程任务，不断尝试获取 stream.order 中的消息，完成下单



让我们来看一下具体代码实现逻辑：

先去Redis中添加消息队列 

```
XGROUP CREATE stream.orders g1 0
```

修改lua脚本：

​	添加一个新的ARGV,用于存储orderId，然后在判断完一系列一人一单和库存情况之后，将用户id，订单id，voucherId 全部添加给消息队列 stream.orders

```lua
-- 订单id
local voucherId = ARGV[1]

-- 用户id
local userId = ARGV[2]

local orderId = ARGV[3]
-- 数据key
    -- 库存key
--在lua脚本中，拼接字符串使用的是..
local stockKey = 'seckill:stock:' .. voucherId
    -- 订单key
local orderKey = 'seckill:order:' .. userId

--脚本业务
--判断库存是否充足
if(tonumber(redis.call('get',stockKey)) <=0 ) then
    return 1
end

-- 判断用户是否下单，若已经下单，则返回2，若未下单，则进行下单操作
--因为是 set 集合，则直接使用 sismember 函数来判断是否存在用户id即可
if(redis.call('sismember',orderKey, userId) == 1) then
    return 2
end

-- 如果上述判断中，库存充足，用户未下单，则执行下单业务
-- 库存减少
redis.call('incrby',stockKey,-1)
-- 添加用户id到订单中
redis.call('sadd',orderKey,userId)
-- 将订单信息添加到消息队列steam.orders
-- XADD stream.orders * k1 v1 k2 v2 k3 v3
redis.call('xadd','stream.orders','*','userId',userId,'voucherId',voucherId,'id',orderId)
return 0
```





然后看 Java 逻辑：

<img src="Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231201213548082.png" alt="image-20231201213548082" style="zoom: 80%;" />



具体来看开辟线程任务，然后不断从Stream中取出订单信息，然后进行下单操作：

```java
public void run() {
            while(true){
                try {
                    //1.获取消息队列中的订单信息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders
                        //这里返回list，是因为count可以指定读取消息的个数
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获得成功
                    if(list == null || list.isEmpty()){
                        //若获取消息失败，则说明此时没有订单下单，则继续循环获取订单
                        continue;
                    }
                    //3.消息获取成功，成功获取订单，将list转换为 voucherOrder发送
                        //因为每次只返回一条消息（上面设置的count为0），因此只要get(0)即可
                    MapRecord<String, Object, Object> record = list.get(0);
                    //这里的recode，里面的String是消息的ID，即订单信息在Stream消息队列中创建时自动生成的id
                    //后面的Object，分别对应了k和v
                    Map<Object, Object> values = record.getValue();//通过getValue可以获得保存的订单信息的键值对
//                    通过hutool工具，将values转换为VoucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4.一旦订单信息创建成功，就执行下单逻辑
                    handleVoucherOrder(voucherOrder);

                    //5.将完成下单的订单消息进行ACK确认
                    stringRedisTemplate.opsForStream().
                            acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //若上述下单流程中出现异常，
                    // 可能会导致订单消息未被ACK，因此订单消息可能存在了Pending-List中
                    //因此，要获取Pending-List中的订单消息再次执行业务逻辑
                    handlePendingList();
                }
            }
}
```



下面是一旦订单操作遇上异常，通过下面的代码来执行逻辑：

```java
private void handlePendingList() {
    while(true){
        //不断循环，直到异常全部处理完成
        try {
            //从PendingList中读取消息：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
            List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                    Consumer.from("g1", "c1"),
                    StreamReadOptions.empty().count(1),
                    StreamOffset.create("stream.orders", ReadOffset.from("0"))
            );
            //如果pendinglist中没有消息，说明没有订单异常，直接结束循环
            if(list==null||list.isEmpty()){
                break;
            }
            //如果有订单异常，那么取出该订单，再次进行下单操作
            MapRecord<String, Object, Object> record = list.get(0);
            Map<Object, Object> values = record.getValue();
            VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
            handleVoucherOrder(voucherOrder);
        }catch (Exception e){
            log.error("处理Pending-List订单出现异常",e);
            //这里出现异常了的话，就重新再去Pending-List中去取消息，没必要做其他处理，也就是直接继续循环即可
                //当然，如果出现Pending-List异常了，不希望一直反复报这个log日志，就可以让它休眠一会。
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interruptedException) {
                    interruptedException.printStackTrace();
                }
        }
    }
}
```



完整的内部类代码如下：

```java
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    //项目初始化的时候就不断执行这个线程操作
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        /**
         * 通过Stream消息队列实现取出订单操作
         */
        String queueName = "stream.orders";
        @Override
        public void run() {
            while(true){
                try {
                    //1.获取消息队列中的订单信息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders
                        //这里返回list，是因为count可以指定读取消息的个数
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获得成功
                    if(list == null || list.isEmpty()){
                        //若获取消息失败，则说明此时没有订单下单，则继续循环获取订单
                        continue;
                    }
                    //3.消息获取成功，成功获取订单，将list转换为 voucherOrder发送
                        //因为每次只返回一条消息（上面设置的count为0），因此只要get(0)即可
                    MapRecord<String, Object, Object> record = list.get(0);
                    //这里的recode，里面的String是消息的ID，即订单信息在Stream消息队列中创建时自动生成的id
                    //后面的Object，分别对应了k和v
                    Map<Object, Object> values = record.getValue();//通过getValue可以获得保存的订单信息的键值对
//                    通过hutool工具，将values转换为VoucherOrder对象
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);

                    //4.一旦订单信息创建成功，就执行下单逻辑
                    handleVoucherOrder(voucherOrder);

                    //5.将完成下单的订单消息进行ACK确认
                    stringRedisTemplate.opsForStream().
                            acknowledge(queueName,"g1",record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常",e);
                    //若上述下单流程中出现异常，
                    // 可能会导致订单消息未被ACK，因此订单消息可能存在了Pending-List中
                    //因此，要获取Pending-List中的订单消息再次执行业务逻辑
                    handlePendingList();
                }
            }



        private void handlePendingList() {
            while(true){
                //不断循环，直到异常全部处理完成
                try {
                    //从PendingList中读取消息：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create("stream.orders", ReadOffset.from("0"))
                    );
                    //如果pendinglist中没有消息，说明没有订单异常，直接结束循环
                    if(list==null||list.isEmpty()){
                        break;
                    }
                    //如果有订单异常，那么取出该订单，再次进行下单操作
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handleVoucherOrder(voucherOrder);
                }catch (Exception e){
                    log.error("处理Pending-List订单出现异常",e);
                    //这里出现异常了的话，就重新再去Pending-List中去取消息，没必要做其他处理，也就是直接继续循环即可
                        //当然，如果出现Pending-List异常了，不希望一直反复报这个log日志，就可以让它休眠一会。
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException interruptedException) {
                            interruptedException.printStackTrace();
                        }
                }
            }
        }

    }
```





# 达人探店

## 查询显示博客



通过修改保存路径之后，就可以实现发布个人博客：

<img src="Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202093243827.png" alt="image-20231202093243827" style="zoom:67%;" />





目前需要实现一下点击图下的blog，会正常显示内容的操作：

![image-20231202093344784](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202093344784.png)

![image-20231202093419548](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202093419548.png)





可以从下图中看出对应的接口：

![image-20231202093455009](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202093455009.png)



查询博客的具体实现：

1. 根据前端返回的博客id，查询数据库中的博客信息
2. 再将博客信息对应的用户id，查询用户信息，然后将用户信息里的名称和图标设置到博客字段中
3. 返回前端博客信息

```java
public Result queryBlogById(Long id) {
        //根据博客id查询博客
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
        }
        //查询博客对应的用户
        queryBlogUser(blog);
        return Result.ok(blog);
    }
    //封装的查询用户方法
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
```

成功正常显示内容：

![image-20231202095723001](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202095723001.png)

## 点赞

![image-20231202100554701](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202100554701.png)

第一步简单，直接添加字段即可。

第二步：

Redis的 Set 集合是通过一个 key，多个 value 存储，这些 value 被称为 member，我们可以通过将笔记的 id 作为 key， 将用户 id 作为 member，存储在 Redis 的 key 结构中，这样如果用户点赞了，就将用户 id 作为 member 添加到当前笔记对应的 key 的 set 集合中。如果用户取消点赞，那么就将该用户id对应的 set 集合中的 member 删除即可。

这里主要应用了三个Redis的 set 集合操作：

+ 添加 MEMBER 到 key 中

```
XADD KEY MEMBER [MEMBER...]
```



+ 判断当前用户是否点赞

```
SISMEMBER KEY MEMBER
```



+ 移除取消点赞的用户

```
SMOVE KEY MEMBER
```



## 实现点赞用户显示

我们希望在blog的详细界面里显示有哪些用户对博客进行了点赞，仅显示最早点赞的 5 个用户的信息。

由于上面我们将点赞的用户存储在了 Redis 的 set 结构中，因此同样可以考虑一个能够得到有序且唯一的结构，来实现显示前五个用户显示的功能：
先来看一下几个可能可以使用的Redis数据类型：

![image-20231202123217155](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202123217155.png)

很显然，我们可以使用 SortedSet 结构来实现这个功能。因此我们只需要去修改一下原来的保存方式，把 Set 改成 SortedSet 。

我们先来看一下 SortedSet 需要的几个方法：
添加元素：

![image-20231202123354380](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202123354380.png)

 key 添加的键名

score 用来SortedSet进行排序的元素

member 标识当前 score 的元素



判断元素是否属于当前键：
![image-20231202123429380](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202123429380.png)



获取当前键的一个有序集合：

可以用 ZRANGE,由于SortedSet本身就会对score排序，因此直接指定范围返回就是一个有序的集合：

![image-20231202123736689](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202123736689.png)



咋们先来修改一下之前点赞的功能，然后再去做显示点赞用户的功能：

```java
public Result likeBlog(Long id) {
    //1.获取当前登录用户id
    Long userId = UserHolder.getUser().getId();
    //2.从Redis中判断登录用户是否点赞
    String key = "blog:liked"+id;

    Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
    //3.如果未点赞，可以点赞
    if(score==null){
        //3.1.修改数据库点赞数+1
        boolean isSuccess = update().setSql("liked = liked+1").eq("id", id).update();
        //3.2.保存用户数据到Redis的Zset集合中
        if(isSuccess){
            //将当前的时间戳存进SortedSet中的score字段，用来实现排序
            stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
        }
    }else{
        //4.如果已经点赞，取消点赞
        //4.1数据库点赞数-1
        boolean isSuccess = update().setSql("liked = liked-1").eq("id", id).update();
        //4.2将用户从Redis中移除
        if(isSuccess){
            stringRedisTemplate.opsForZSet().remove(key,userId.toString());
        }
    }
    return Result.ok();
}
```

关键代码：

三个操作ZSet的具体实现

```java
Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

//将当前的时间戳存进SortedSet中的score字段，用来实现排序
stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());

stringRedisTemplate.opsForZSet().remove(key,userId.toString());
```

### 有很值得学习的地方

**显示点赞**

**这里的流操作非常值得学习**

```java
@Override
public Result queryBlogLikes(Long id) {
    //实现显示前5个用户点赞显示
    //1. 去SortedSet中查找前五个用户 ZRANGE key 0 4， ZRANGE是可以直接返回排序好的member的，不会返回score
    Set<String> top5 = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + id, 0, 4);
    if(top5==null||top5.isEmpty()){
        return Result.ok();
    }
    //2. 解析出其中的userId
    List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
    String idStr = StrUtil.join(",", ids);
    //3.根据用户id查询用户
    //这里有点特殊情况，虽然我们上面根据排序结果返回了需要的userId,但是Mysql返回的结果并不是按照这个顺序来的，它是按照数据库中键的排序返回，因此我们需要修改SQL语句来实现我们需要的排序结果，因此使用了 order by FIELD,指定 id 的顺序
    List<UserDTO> userDTOS = userService.query().in("id",ids)
            .last("order by FIELD(id,"+idStr+")").list()
            .stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class))
            .collect(Collectors.toList());
    //返回
    return Result.ok(userDTOS);
}
```

这里需要注意一下这个查询语句：

```java
List<UserDTO> userDTOS = userService.query().in("id",ids)
            .last("order by FIELD(id,"+idStr+")").list()
```

由于Mybatis-Plus调用 `listByIds()` 是通过 in 来包括 id 的，因此查询时遵循的是 id 在数据库中的排序，而此时我们需要的是自定义的一个排序结果，所以必须要改 SQL 语句。具体是通过 order by FIELD("id", 5,1,2,3) ，这样就可以按照 id 从5 ，1， 2，3来返回结果了。





# 关注和取关

现在我们来实现关注和取关！

先来看前端请求的接口：

![image-20231202155228847](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202155228847.png)



逻辑如下：非常好理解

```java
@Override
public Result follow(Long followUserId, Boolean isFollow) {
    //1.获取用户登录id
    Long userId = UserHolder.getUser().getId();
    //判断到底是关注还是取关
    if(isFollow){
        //2. 关注，新增数据
        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setFollowUserId(followUserId);
        save(follow);
    }else{
        //3.取关，删除
        remove(new QueryWrapper<Follow>()
                .eq("user_id",userId).eq("follow_user_id",followUserId));
    }
        return Result.ok();
}

@Override
public Result isFollow(Long followUserId) {
    //获取用户
    Long userId = UserHolder.getUser().getId();
    //去数据库中判断是否已经关注
    Integer count = query().eq("follow_user_id", followUserId).eq("user_id", userId).count();
    //返回前端查询结果
    return Result.ok(count>0);
}
```



## 共同关注

接下来我们来实现共同关注功能。

共同关注需要一个博主其所有的关注，以及本用户的所有关注对象，求出它们的交集，就是共同关注。

为了实现共同关注，我们可以使用 Redis 中的 set 结构，它有一个 SINTER 命令，可以得到两个键的共同成员。

我们先把原先关注操作添加上 Redis 的set操作：

```java
String key ="follow:"+userId;
//判断到底是关注还是取关
if(isFollow){
    //2. 关注，新增数据
    Follow follow = new Follow();
    follow.setUserId(userId);
    follow.setFollowUserId(followUserId);
    boolean isSuccess = save(follow);
    if(isSuccess){
        stringRedisTemplate.opsForSet().add(key,followUserId.toString());
    }
}else{
    //3.取关，删除
    boolean isSuccess = remove(new QueryWrapper<Follow>()
            .eq("user_id", userId).eq("follow_user_id", followUserId));
    if(isSuccess){
        stringRedisTemplate.opsForSet().remove(key,followUserId.toString());
    }
}
```

其实很简单，就是直接在 set 集合中添加关注信息

接下来我们进行共同关注的编写：

先来看接口：



![image-20231202163538811](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202163538811.png)



要实现这个接口，其实很简单，只要拿出当前用户在 Redis 的 set 结构中存储的关注用户，然后跟前端发送过来的目标用户 id 进行求交集操作就可以得出共同关注的用户了，将共同关注用户的集合结果返回给前端即可。

```java
public Result followCommons(Long followUserId) {
    //1. 获取用户id
    Long userId = UserHolder.getUser().getId();
    //2.求交集，分别得出当前用户的关注列表和目标用户的关注列表
    //2.1当前用户和目标用户对应关注列表的键
    String key = "follows:"+ userId;
    String key2 = "follows:" + followUserId;
    //2.2求交集
    Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
    if(intersect == null || intersect.isEmpty()){
        return Result.ok(Collections.emptyList());
    }
    //3.解析id集合
    List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
    //4.查询用户
    List<UserDTO> users = listByIds(ids).stream()
            .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
            .collect(Collectors.toList());
    return Result.ok(users);
}
```





## 关注推流

### 简介

我们可能希望给用户推送一些他可能感兴趣的内容，因此接下来我们来看看如何实现

![image-20231202170328302](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202170328302.png)



![image-20231202170400277](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202170400277.png)

我们的项目采用 Timeline 模式，基于关注列表的推送来实现

接下来来看一下推模式和拉模式

### 拉模式

![image-20231202170811424](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202170811424.png)



博主通过自己发送自己的博客，用户来拉取关注的博主发布的内容称为拉模式。

博主会将内容发送到发件箱中，而用户的收件箱不停拉取关注的博主的发件箱中的内容。这种方式对于部分关注数庞大的用户来说，其读取效率很低，耗时很长。

### 推模式

![image-20231202171119284](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202171119284.png)

现在博主没有了发件箱，他会直接把内容发送给所有关注的人。这样就解决了延迟的问题。但是由于发送者需要把内容发送给所有关注的用户，其内容就需要写多份来发送。**对于内存的占用较高**

因此我们可以采用推拉相结合的方式：

![image-20231202171709924](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202171709924.png)

针对不同的用户，使用不同的模式

对于普通粉丝，使用拉模式，因为他们对内容的实时性要求不太高，不必太在乎延迟问题

对于活跃粉丝，使用推模式，可以让他们更快地接收到消息。同时活跃粉丝数目较少，同时发送多份推送少，因此内存占用较低。所以使用推模式。

对于僵尸粉，直接不发送推送。



![image-20231202172024709](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202172024709.png)

## 基于推模式实现推流



![image-20231202172906249](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202172906249.png)

这里有一个分页查询的问题：

![image-20231202173030508](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202173030508.png)

推模式中，用户会不断接收新的数据，那么如果采用传统的角标的方式来分页，会出现上图的情况。重复读取了 6 这条数据。

因此我们考虑使用滚动分页的方法：
![image-20231202173239437](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202173239437.png)

第一次读取时，设置读取最大值为无穷，这样就可以从最大时间戳开始读取。然后读取完5条记录之后，给 lastId 赋值当前读取的位置，然后下一次分页就从这个位置开始读取。就算后续有新数据接收，也不会影响当前的分页结果。



能够实现该功能可以使用 Redis 的 SortedSet，我们通过记录 score 值，按其降序排列来实现

> 我们先来看一下正常按照角标查询的情况：

我们直接按照排名前三位的来查询，不断往下查：

![image-20231202203957389](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202203957389.png)

可以看出来，角标查询会出现重复的情况。一旦新增一条数据，就可能导致查询出现问题。



我们可以通过记录每次查询到的最小的分数，然后从这个分数开始往下查询，就可以解决这个问题了。

无论是新增数据的位置在当前查询页面的上面，还是中间，还是下面，都可以实现正常查询。

于是，我们可以采用分数的最大最小值来实现：

![image-20231202204344252](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202204344252.png)



这里解释一下这个函数：

按照降序排序 score 值返回一个自定义个数的集合

```
ZREVRANGEBYSCORE key max min WITHSCORES LIMIT offset count
```

key：指定查询的键名

max：查询的 score 最大值，包含 max

min ：查询的 score 最小值，包含 min

WITHSCORES 返回的查询结果是否包含 SCORES 字段

LIMIT 做查询约束

offset 查询的起始位置，从0开始计数，0是查询到的第一个元素，1是第二个

count 返回的数据个数



![image-20231202205318012](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202205318012.png)

我们第一次查**设置一个非常大的值作为 max 来确保其大于我们的所有 scores 值。**

> 后续我们需要根据每次查询得到的最小值（即每次查询得到的最下方的值）作为 max ，min仍然是0（因为时间戳永远大于0），但是这里要注意，因为我们的查询是包含 max 值和 min 值的，而 max 值是我们之前查询的最小值，因此已经被查询过了，所以这里需要将后面的 offset 改为从第二个元素开始查询，因此设置为1。

但是这里还需要注意一下如果 score 相同的情况，我们需要把 offset 设置成上一次中最小值相同的次数。



### 具体实现

来看一下接口：

![image-20231202211718390](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202211718390.png)



代码如下：

```java
@Override
public Result queryBlogOfFollow(Long max, Integer offset) {
    //1.取出当前用户
    Long userId = UserHolder.getUser().getId();
    //2.查询收件箱 ZREVRANGEBYSCORE key MAX MIN LIMIT offset count
    String key = FEED_KEY+userId;
    //这里使用reverseRangeByScoreWithScores()，因为我们还需要根据 score 去进行分页操作
    //每次查两条数据，最大值由前端发送，最小值为0，offset也由前端发送
    Set<ZSetOperations.TypedTuple<String>> typedTuples =
            stringRedisTemplate.opsForZSet().
                    reverseRangeByScoreWithScores(key, 0, max, offset, 2);
    if(typedTuples==null || typedTuples.isEmpty()){
        return Result.ok();
    }
    //3.解析数据：blogId, minTime（时间戳），分页的主要部分
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0;//记录最小值，即通过不断循环之后的最后一个值
    int os = 1;//记录本次查询数据中最后一个元素的重复次数
    for(ZSetOperations.TypedTuple<String> tuple : typedTuples) {
        //3.1获取 id
        ids.add(Long.valueOf(tuple.getValue()));
        //3.2获取分数（时间戳）
        long time = tuple.getScore().longValue();
        if(time == minTime){
            os++;
        }else{
            os = 1;
            minTime = time;
        }
    }
    //查询邮箱接收到的博客数据
    String idStr = StrUtil.join(",", ids);
    List<Blog> blogs = query().in("id",ids)
            .last("ORDER BY FIELD(id,"+idStr+")").list();

    //由于博客中含有点赞信息，因此需要再次检测判断
    for(Blog blog : blogs){
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
```



这里我们解释一下上述的代码中最主要的部分：

我们之前通过 FEED_KEY+userId的Zset数据结构的方式发送给关注用户当前博主新发布的笔记。

这里我们通过上面讲过的 ZREVERSERANGE 命令来实现查询，这里使用 REVERSE 和 ByScore的原因是因为默认的排序是升序的 score，我们需要降序的 score，因为时间戳是越新的数据，其时间戳越大。又因为我们需要它们的最小 score 来实现分页，因此我们还需要带上 ByScoreWithScores，这样可以返回每条记录的 score 值，用来作为来我们后面分页功能的数据来源。

```java
 Long userId = UserHolder.getUser().getId();
    //2.查询收件箱 ZREVRANGEBYSCORE key MAX MIN LIMIT offset count
    String key = FEED_KEY+userId;
    //这里使用reverseRangeByScoreWithScores()，因为我们还需要根据 score 去进行分页操作
    //每次查两条数据，最大值由前端发送，最小值为0，offset也由前端发送
    Set<ZSetOperations.TypedTuple<String>> typedTuples =
            stringRedisTemplate.opsForZSet().
                    reverseRangeByScoreWithScores(key, 0, max, offset, 2);//我们每次只返回两条数据
    if(typedTuples==null || typedTuples.isEmpty()){
        return Result.ok();
    }
```

我们先解释一下这个返回的 `TypedTuple ` 是什么东西：

看一下它的源码可以知道，它存储了我们需要的从 Zset 结构中查询得到的 value 和 score，分别对应的是 userId 集合和 时间戳集合。

![image-20231202224404219](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202224404219.png)





+ 这里我们需要保存查询到的笔记对应的id，因此设定了一个 List，叫做ids，

+ 然后设置 minTime 用于保存最小的时间戳，也就是返回的最后一条数据，这是下一次查询分页的起点的前一个元素。

+ os用作计数器，用来计数最终需要设置的 offset 值，offset 值的多少取决于本次查询中出现最小值的次数。 

+ 通过for循环，取出其中的 tuple，用 ids 集合添加所有的 id（blogId），这是当前用户关注的博主发布的新博客对应的id集合。

+ 与此同时，获取时间戳，判断是否和假定设置的时间戳最小值 （minTime） 相等，若是则让计数器+1，但是可能当前值不是最小值，因此，一旦时间戳不再和假定设置的最小时间戳(minTime)不同，就把计数器重新赋值为1，为啥为1呢，因为用ZReverseRange读取数据时，是包含最小值的，而我们的分页是不希望有最小值的，因此我们要从读取到的第二个元素开始读，所以默认最小的os一定是1，而如果有多个相同的最小值，就将其**设置为本次查询中出现最小值的次数**。因为这就相当于本次查询有 os 个最小值，要将它们全部都排除在外才行。（比如有两个最小值，那么offset就该从2开始（因为0,1查询到的都是上次查询的最小值））

```java
   //3.解析数据：blogId, minTime（时间戳），分页的主要部分
    List<Long> ids = new ArrayList<>(typedTuples.size());
    long minTime = 0;//记录最小值，即通过不断循环之后的最后一个值
    int os = 1;//记录本次查询数据中最后一个元素的重复次数
    for(ZSetOperations.TypedTuple<String> tuple : typedTuples) {
        //3.1获取 id
        ids.add(Long.valueOf(tuple.getValue()));
        //3.2获取分数（时间戳）
        long time = tuple.getScore().longValue();
        if(time == minTime){
            os++;
        }else{
            os = 1;
            minTime = time;
        }
    }
```







# 附近商户

## GEO 数据结构

![image-20231202223114503](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202223114503.png)



## 添加一个地理空间信息GEOADD



![image-20231202225356139](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202225356139.png)

我们可以使用这条命令给一个键中添加多个地点的经纬度。

```
GEOADD KEY longitude latitude member
```

先添加经纬度，再设置地区名字

![image-20231202225430560](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202225430560.png)





![image-20231202225418492](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202225418492.png)

这里最终经纬度会被解析成一个 score 值保存。



## 计算两个地区的距离GEODIST



```
GEODIST key member1 member2 [m|km|ft|mi]
```



可选项：[m|km|ft|mi] 表达的是单位（米、千米、英尺、英里）



![image-20231202225704793](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202225704793.png)





##在指定范围内查询 GEOSEARCH

![image-20231202230126468](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202230126468.png)



```
GEOSEARCH key [FROMMEMBER member | FROMLONLAT longitude latitude m|km|ft|mi] [BYRADIUS | BYBOX width height m|km|ft|mi] ... 
```

[FROMMEMBER member | FROMLONLAT longitude latitude m|km|ft|mi] 指定为原点，可以是指定经纬度，也可以是指定一个member成员。

[BYRADIUS | BYBOX width height m|km|ft|mi] 指定是通过圆形半径大小来查询还是以矩形来查询。

查询 g1 中离自定义经纬度位置半径10km的点。其会自动按照由近到远的排列方式呈现。

![image-20231202230436493](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231202230436493.png)



## 具体实现

先来看接口：

![image-20231203084024017](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203084024017.png)

前端会先根据商户所属的一个类型，发送对应的 typeId，同时附上页码用于滚动查询，附上经纬度，用于作为查找附近商户的依据原点。



> 我们自然会想到使用 GEO 的数据结构来实现，那么我们需要存储啥数据呢？

显然，商户的经纬度信息是要存的，那么如果用商户对应的 id 来作为 key ，然后每次获取到附近的商户后再去数据库中查询商户的信息是一种合理的办法，但是这样我们还需要自己去判断查询到的商户是哪种类型的，所以这里可以优化一下，因为我们的前端发送了 typeId ，因此我们可以将存储在 GEO 中的 key 改为使用类型 id，这样就能拿到一个类型所有商户的集合。

![image-20231203084551561](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203084551561.png)



这里我们先来测试一下能不能将商户经纬度和商户 id 根据类型添加到 Redis 的 GEO 中，因此在测试类中编写了如下代码：

```java
  @Test
    void loadShopData(){
        // 1. 查询店铺信息
        List<Shop> list = shopService.list();
//        2.把店铺分组，按照 typeId分组，typeId一致的放到一个集合中
//        这里通过一个stream流，将相同typeId的数据分到一组，用一个 List<Shop>来存储
//        当然，也可以一个个遍历实现。只是下面这种写法比较优雅(
        Map<Long, List<Shop>> map = list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
//        3.分批次写入 Redis
        for(Map.Entry<Long,List<Shop>> entry : map.entrySet()){
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            //获取同类型店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            //将其保存在 GeoLocation 中，这个类可以存储商户的经纬度和其对应的member，这里我们member设置为商户id
            for (Shop shop : value){
                //将商户的id作为member，将经纬度设定的Point作为score存储进GeoLocation，
                // 然后保存在一个GeoLocation形成的list集合里
//                这样就可以生成一个list，里面存储的是同一类型的所有商户的集合
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(), new Point(shop.getX(),shop.getY())
                ));
                //依次通过循环，将同类型的商户的集合添加到GEO中
                stringRedisTemplate.opsForGeo().add(key,locations);
            }
        }
    }
```

运行测试该代码，可以在Redis中看到保存了同类型的商户的情况：

![image-20231203092045907](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203092045907.png)



下面是实现该接口的代码：

```java
public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
    //1.判断是否需要根据坐标查询，只要有一个参数不存在，就按照原来的查询方式，直接返回商户
    if (x == null || y == null) {
        // 根据类型分页查询
        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
    //2.计算分页参数，用来实现分页功能，
    // 由于这次并不像之前的分页操作，需要更新数据的频率很低，因此我们直接根据角标来做
    //current是页码，那么第一页的起始是0，终点是 current*每页显示的数目
    //后续每页的起始是 (current-1) * 每页显示的数目，终点仍然是 current * 每页显示的数目
    int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
    int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

    //3.查询Redis，按照距离远近查询
    String key = SHOP_GEO_KEY + typeId;
    GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
            .search(key, GeoReference.fromCoordinate(x, y)
                    , new Distance(5000), RedisGeoCommands.GeoSearchCommandArgs
                            .newGeoSearchArgs().includeDistance().limit(end));
    //这里只能 limit(end) ，意味着我们使用分页时，不能设置起始位置，只能设置终止位置，
    // 那么随着前端的current发送的值变大，那么这个查询的数据就越多，
    // 因此为了解决分页问题，我们需要将返回的数据手动进行分割。
    if(results == null){
        //如果查询没有结果，就直接返回
        return Result.ok(Collections.emptyList());
    }
    //results.getContent();可以得到查询到的GEO结构存储的shopid和经纬度的集合
    List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
    //我们查询的结果list也就是商户的集合，
    // 如果商户总数小于分页的起始页，那么就相当于这页没有商户，因此有下面的情况：
    // 如果list的长度小于起始页位置，相当于跳过了所有的数据
    // 那么后续查询会出现空指针。因此这里加上一个判断。
    if(list.size()<from){
        return Result.ok(Collections.emptyList());
    }
    List<Long> ids = new ArrayList<>(list.size());//用来存储shopId
    Map<String,Distance> distanceMap = new HashMap<>(list.size());
    //通过skip函数截断掉from前面的元素，实现分页功能
    list.stream().skip(from).forEach(result -> {
        //获取店铺Id,result.getContent()可以得到一个GeoLocation
        // 这里的GeoLocation存储的是每个 member 和其 score 值，也就是shopId和经纬度转化的score
        String shopIdStr = result.getContent().getName();
        ids.add(Long.valueOf(shopIdStr));
        //获取距离
        Distance distance = result.getDistance();
        distanceMap.put(shopIdStr,distance);
    });
    //根据id查询Shop
    String idStr = StrUtil.join(",",ids);
    List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
    for(Shop shop : shops){
        //给按照指定顺序查询到的 shop 赋 Distance 值
        shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
    }
    //返回结果
    return Result.ok(shops);
}
```





# 用户签到

## BitMap介绍



![image-20231203104839682](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203104839682.png)



**命令**

![image-20231203105036408](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203105036408.png)



一般设置BitMap,指定存入0或1使用 SETBIT ，修改也是使用这条命令

查询单个位置用 GETBIT

查询多个位置使用 BITFIELD，但是修改不常用 BITFIELD。

GETBIT 和 SETBIT 命令很简单，这里解释一下 BITFIELD

![image-20231203105554448](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203105554448.png)

```
BIEFIELD key GET [GET type offset]
```

type是用来指定返回的进制类型和个数，以及是否含有符号位，有符号的使用 i，无符号的使用 u

offset是返回的起始位置



+ 示例：（备注：此时二进制位情况是： 11100111）

![image-20231203105741982](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203105741982.png)



​	bm1 ：是key

​	GET ：表示获取数据

​	u2 ：指定查询的是无符号的两个位

​	0：起始位置从第一个位置开始

​	因此从第一位开始，读取两个数，而且是无符号的，所以结果是 11，化为十进制为 3 ，所以返回了3.



+ **查找第一个0或者1出现的位置**

```
BITPOS key 0|1
```



## 接口实现

![image-20231203145636098](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203145636098.png)

实现签到操作非常简单，只需要读取当前用户，然后创建key，创建好key之后，调用BitMap的接口方法就可以了。最后一个参数true or false的选择是当天是否签到了，true为1，false为0，就会把BitMap中这个位置设置为1.

而BitMap本身是0-30，而我们的日期是1-31，所以我们要在设置中让它 -1.因此有 dayOfMonth-1。

```java
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
```



利用PostMan发送请求，然后去Redis查看结果：

![image-20231203153703074](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203153703074.png)



今天是3号，所以在第三个位置显示1.



## 连续签到统计

我们希望记录用户的连续签到情况，这里给出它的具体定义和实现方式：

![image-20231203155148735](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203155148735.png)

![image-20231203155223903](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203155223903.png)

```java
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
```



# UV 统计

![image-20231203161449304](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203161449304.png)



![image-20231203162013436](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203162013436.png)

**HyPeLogLog的数据是不重复的**



测试：

```java
@Test
void TestHyperLogLog(){
    String[] values = new String[1000];
    int j = 0;
    for(int i = 0 ;i < 1000000 ; i++){
        j = i % 1000;
        values[j] = "user_" + i;
        if(j == 999){
            stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
        }
    }
    Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
    System.out.println("个数为："+count);
}
```

![image-20231203163503304](Redis%E9%BB%91%E9%A9%AC%E7%82%B9%E8%AF%84.assets/image-20231203163503304.png)

> 学完了学完了！！！！😁



# 问题

出现：

Failed to obtain JDBC Connection; nested exception is java.sql.SQLNonTransientConnectionException: **Public Key Retrieval is not allowed**

mysql 8.0 默认使用 caching_sha2_password 身份验证机制 （即从原来mysql_native_password 更改为 caching_sha2_password。）

从 5.7 升级 8.0 版本的不会改变现有用户的身份验证方法，但新用户会默认使用新的 caching_sha2_password 。 客户端不支持新的加密方式。 修改用户的密码和加密方式。



方案一：
在命令行模式下进入mysql，输入以下命令:

ALTER USER 'root'@'localhost' IDENTIFIED WITH mysql_native_password BY 'root';
或者 

ALTER USER 'root'@'%' IDENTIFIED WITH mysql_native_password BY 'root';
然后就可以正常连接了。



方案二：

在datasource配置的url中加入：
**allowPublicKeyRetrieval=true**







