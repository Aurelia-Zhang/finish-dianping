# 黑马点评项目 — Java后端面试问答文档

> 基于项目简历描述与源码深度分析，涵盖 18 个由浅入深的面试问题。
> 每个问题包含：标准答案 → 可能的追问 → 延伸知识点 → 代码质疑点及应对话术（如适用）。

---

## Q1：请简要介绍一下这个项目的整体架构和你负责的核心模块

### 标准答案

这是一个基于 **Spring Boot + Redis** 的高并发本地生活服务平台，类似"大众点评"。核心模块包括：

| 模块 | 技术要点 |
|------|---------|
| 用户登录 | Redis + Token 分布式 Session，双拦截器 + ThreadLocal |
| 商户缓存 | 缓存穿透（空值缓存）、缓存击穿（互斥锁/逻辑过期）、主动更新策略 |
| 优惠券秒杀 | Lua 原子校验 → Redis Stream 异步下单 → Redisson 分布式锁 |
| 点评社交 | ZSet 点赞排行、Feed 推模式、Set 共同关注 |
| 附近商户 | Redis GEO 地理坐标查询 |
| 用户签到 | Redis BitMap 位运算 |

### 追问：为什么选择 Redis 作为核心中间件而不是 Memcached？

Redis 支持丰富的数据结构（String、Hash、Set、ZSet、Stream、GEO、BitMap），天然适配这些业务场景。Memcached 只支持简单的 key-value，无法满足分布式锁、排行榜、消息队列等需求。此外 Redis 支持持久化（RDB/AOF），数据安全性更好。

### 延伸知识点

- Redis 6.0 引入多线程 I/O，但命令执行仍然是单线程，保证原子性。
- 生产环境建议使用 Redis Cluster 或 Sentinel 保证高可用。

---

## Q2：说一下你的分布式 Session 方案是怎么实现的？为什么不用传统 Session？

### 标准答案

传统 Session 存储在 Tomcat 内存中，在集群环境下（Nginx 负载均衡多实例），不同请求可能路由到不同节点，导致 Session 丢失。

我的方案：
1. **登录时**：生成随机 UUID 作为 Token，将用户信息以 Hash 结构存入 Redis（`login:token:{token}` → UserDTO 的各字段），设置 30 分钟 TTL。
2. **请求时**：前端将 Token 放在 `authorization` 请求头中，后端拦截器从 Redis 读取用户信息。
3. **续期**：每次请求成功后刷新 Token 的 TTL，实现"活跃用户不过期"。

```java
// UserServiceImpl.java - 登录逻辑
String token = UUID.randomUUID().toString(true);
UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
    CopyOptions.create().setIgnoreNullValue(true)
        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
```

### 追问 1：为什么用 Hash 存储而不是直接用 String 序列化整个对象？

- **Hash 存储**可以对单个字段进行读写，减少不必要的序列化/反序列化开销。
- 修改某个字段不需要重新序列化整个对象。
- `StringRedisTemplate` 要求所有 value 为 String，所以通过 `setFieldValueEditor` 将所有字段值转为 String。

### 追问 2：为什么 setFieldValueEditor 将 fieldValue 都转成 String？

因为使用的是 `StringRedisTemplate`，它的 HashValueSerializer 是 `StringRedisSerializer`。如果不转换，`Long` 类型的 `id` 字段在写入时会因类型不匹配而报错（`ClassCastException`）。

### 追问 3：Token 的安全性如何保证？

- 使用 UUID 生成，不包含用户敏感信息（与 JWT 不同，不可逆推）。
- 通过 HTTPS 传输防止中间人攻击。
- 设置 TTL 自动过期，防止 Token 被长期滥用。
- 生产环境可以增加 IP 绑定、设备指纹等二次校验。

### 延伸知识点

- **JWT** 方案的对比：JWT 无状态，服务端不存储，但无法主动踢出用户；Redis Token 方案可以随时删除 Token 实现强制下线。
- **Spring Session**：Spring 提供了 `spring-session-data-redis`，自动将 Session 存入 Redis，对代码侵入性最小，但灵活性不如自定义 Token 方案。

---

## Q3：说一下你的双拦截器设计，为什么需要两个拦截器？

### 标准答案

项目使用了两个拦截器，通过 `order` 控制执行顺序：

| 拦截器 | order | 作用 | 路径 |
|--------|-------|------|------|
| `RefreshTokenInterceptor` | 0（先执行） | 拦截所有请求，解析 Token，将用户存入 ThreadLocal，刷新 TTL | `/**` |
| `LoginInterceptor` | 1（后执行） | 拦截需要登录的请求，检查 ThreadLocal 中是否有用户 | 排除公开路径 |

**设计原因**：如果只用一个拦截器，那么访问公开页面（如商铺详情）时不会触发 Token 刷新，登录用户浏览公开页面期间 Token 可能过期。拆分后，**即使访问不需要登录的页面，也能刷新 Token**。

```java
// MvcConfig.java
registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
    .addPathPatterns("/**").order(0);     // 先执行
registry.addInterceptor(new LoginInterceptor())
    .excludePathPatterns("/shop/**", "/user/code", "/user/login")
    .order(1);                             // 后执行
```

### 追问：ThreadLocal 在这里有什么潜在问题？

1. **内存泄漏**：如果使用线程池（如 Tomcat），线程会被复用。如果忘记调用 `remove()`，上一个请求的用户信息会残留。代码中 `afterCompletion` 里调用了 `UserHolder.removeUser()` 来防止这个问题。
2. **异步场景问题**：ThreadLocal 的值不会自动传递到子线程。如果在 `@Async` 或线程池中需要用户信息，需要使用 `InheritableThreadLocal` 或手动传递（如阿里的 `TransmittableThreadLocal`）。

### ⚠️ 代码质疑点

> **质疑**：`RefreshTokenInterceptor` 没有被 Spring 管理（直接 `new`），为什么能注入 `StringRedisTemplate`？
>
> **应对**：它确实不是 Spring Bean，所以是通过构造函数手动传入 `StringRedisTemplate` 的。这是一种妥协方案，因为 `HandlerInterceptor` 的注册机制是在 `WebMvcConfigurer` 中手动 `new` 的，不走 Spring 容器管理。更优雅的做法是将拦截器声明为 `@Component`，然后在配置类中通过 `@Resource` 注入。

---

## Q4：缓存穿透是什么？你是怎么解决的？

### 标准答案

**缓存穿透**：请求的数据在缓存和数据库中都不存在，导致每次请求都打到数据库。恶意用户可以利用这一点，用大量不存在的 ID 发起请求，形成 DDoS 攻击。

我的解决方案 — **缓存空值（Null Object Pattern）**：

```java
// CacheClient.queryWithPassThrough()
R r = dbFallback.apply(id);
if (r == null) {
    // 将空值写入Redis，设置短TTL（2分钟）
    stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
    return null;
}
```

当数据库查询为空时，向 Redis 写入一个空字符串 `""`，TTL 设为 2 分钟。后续再查同一个 key 时：

```java
if (json != null) {   // 命中空值（json 是 "" 而非 null）
    return null;       // 直接返回，不查数据库
}
```

### 追问 1：除了缓存空值，还有什么方案？

- **布隆过滤器（Bloom Filter）**：在缓存之前加一层布隆过滤器，预先加载所有合法 ID。请求先经过布隆过滤器，不存在的 ID 直接拦截。优点是内存占用极少，缺点是存在一定的误判率，且数据更新时需要同步更新过滤器。
- 简历中也提到了这个方案，可以结合使用：布隆过滤器拦截绝大部分无效请求，少量漏网的再用缓存空值兜底。

### 追问 2：缓存空值的 TTL 设为 2 分钟是否太短/太长？

- **太短**：攻击者可以持续高频请求，2 分钟一过又会打到数据库。
- **太长**：如果这个 ID 对应的数据后续被创建了，用户在 TTL 内查不到。
- **权衡**：2 分钟是一个偏保守的值，实际生产中可以根据业务调整，或在数据写入时主动删除对应的空值缓存。

### 延伸知识点

- **Guava BloomFilter** 或 **Redis 的 `BF.ADD`/`BF.EXISTS`**（需 RedisBloom 模块）可以实现布隆过滤器。
- 布隆过滤器的误判率公式：`(1 - e^(-kn/m))^k`，k 为哈希函数个数，n 为数据量，m 为位数组大小。

---

## Q5：缓存击穿你用了哪些方案？互斥锁和逻辑过期各有什么优缺点？

### 标准答案

**缓存击穿**：热点 Key 在过期的瞬间，大量并发请求同时打到数据库，造成数据库压力骤增。

我实现了两种方案：

#### 方案一：互斥锁（Mutex Lock）

```java
// CacheClient.queryWithMutex()
boolean isLock = tryLock(lockKey);
if (!isLock) {
    Thread.sleep(50);  // 获取锁失败，休眠后重试
    return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
}
// 获取锁成功，查库并重建缓存
R r = dbFallback.apply(id);
this.set(key, r, time, unit);
```

- `tryLock` 基于 `SETNX`（setIfAbsent）实现，10 秒 TTL 防死锁。
- 只有一个线程能重建缓存，其他线程等待重试。

#### 方案二：逻辑过期（Logical Expiration）

```java
// CacheClient.queryWithLogicalExpire()
RedisData redisData = JSONUtil.toBean(json, RedisData.class);
if (expireTime.isAfter(LocalDateTime.now())) {
    return r;  // 未过期，直接返回
}
// 已过期，开新线程重建缓存
if (tryLock(lockKey)) {
    CACHE_REBUILD_EXECUTOR.submit(() -> {
        R newR = dbFallback.apply(id);
        this.setWithLogicalExpire(key, newR, time, unit);
    });
}
return r;  // 返回过期数据
```

- 缓存**永不过期**（Redis 层面不设 TTL），而是在数据中包含一个逻辑过期时间。
- 过期后，返回旧数据的同时，由独立线程异步重建缓存。

#### 对比

| 维度 | 互斥锁 | 逻辑过期 |
|------|--------|---------|
| 数据一致性 | ✅ 强一致 | ⚠️ 短暂不一致 |
| 可用性 | ⚠️ 等待锁，有延迟 | ✅ 零等待 |
| 实现复杂度 | 中等 | 较高 |
| 适用场景 | 数据一致性要求高 | 热点数据、可接受短暂不一致 |

### 追问：互斥锁重试用递归调用，有什么风险？

递归调用 `queryWithMutex` 在高并发下可能导致**栈溢出（StackOverflowError）**。更好的做法是改成 `while` 循环 + 最大重试次数限制。

### ⚠️ 代码质疑点

> **质疑**：逻辑过期方案中，获取锁后没有**二次检查**（DCL，Double-Check Locking），可能导致多个线程同时发起缓存重建。
>
> **应对**：确实存在这个理论上的问题。但实际上 `tryLock` 基于 `SETNX` 是原子操作，保证了只有一个线程能获取锁。获取锁成功后即使再次读缓存发现已被更新，最多也只是多查一次数据库，不会造成严重问题。不过最佳实践确实应该加上 DCL。

---

## Q6：缓存和数据库的双写一致性你是怎么保证的？

### 标准答案

采用 **Cache Aside Pattern（旁路缓存模式）** — 先更新数据库，再删除缓存：

```java
// ShopServiceImpl.update()
@Transactional
public Result update(Shop shop) {
    updateById(shop);                                     // 1. 先更新数据库
    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);      // 2. 再删除缓存
    return Result.ok();
}
```

### 追问 1：为什么是"删除缓存"而不是"更新缓存"？

- **删除更安全**：如果是更新缓存，在并发场景下可能出现 ABA 问题（线程 A 更新一个值写入缓存，线程 B 紧接着更新另一个值写入缓存，但线程 A 的数据库更新更晚完成），导致缓存与数据库数据不一致。
- **懒加载**：删除后，下次查询时再从数据库加载最新数据写入缓存（Write-Through 的变体），保证数据一致性。

### 追问 2：先更新数据库再删缓存，能否保证线程安全？

不能 100% 保证。极端场景：
1. 缓存刚好失效
2. 线程 A 查数据库得到旧值
3. 线程 B 更新数据库写新值
4. 线程 B 删除缓存
5. 线程 A 将旧值写入缓存

但这种情况出现概率极低（需要数据库写入比读取更快），实际生产中可以通过以下方案进一步保证：
- **延迟双删**：更新后延迟几百毫秒再次删除缓存。
- **Canal 监听 binlog**：通过监听 MySQL binlog 异步删除缓存。
- **消息队列重试**：将删除缓存操作放入 MQ，失败时重试。

### 延伸知识点

- 缓存一致性的三种策略：Cache Aside、Read/Write Through、Write Behind。
- 大厂通常使用 **Canal + MQ** 方案做最终一致性保证。

---

## Q7：说一下秒杀的整体流程和你做了哪些优化？

### 标准答案

秒杀流程经历了三个版本的演进：

#### V1：同步方案（纯数据库）
请求 → 查库存 → 减库存 → 创建订单，全部在主线程同步完成。存在超卖和性能瓶颈。

#### V2：JVM 内阻塞队列异步
Lua 脚本在 Redis 中预扣库存和判重 → 放入 JVM `BlockingQueue` → 后台线程消费入库。

#### V3（当前版本）：Redis Stream 异步
Lua 脚本在 Redis 中完成预扣库存、判重、并直接 `XADD` 写入 Redis Stream → 后台线程通过 `XREADGROUP` 消费入库。

```
用户请求 → Lua脚本(原子性) ──┐
                              ├──→ Redis Stream ──→ 后台线程 ──→ MySQL
    ① 判断库存              │
    ② 判断是否重复下单       │
    ③ 扣减库存（Redis）     │
    ④ XADD写入Stream       ─┘
```

### 追问 1：为什么从 BlockingQueue 升级到 Redis Stream？

| 维度 | JVM BlockingQueue | Redis Stream |
|------|-------------------|-------------|
| 持久化 | ❌ JVM 重启数据丢失 | ✅ Redis 持久化保障 |
| 集群支持 | ❌ 仅单机可用 | ✅ 支持多消费者组 |
| 消息确认 | ❌ 无 ACK 机制 | ✅ XACK + Pending List |
| 容量 | 受 JVM 内存限制 | 受 Redis 内存限制，通常更大 |

### 追问 2：Lua 脚本具体做了什么？为什么要用 Lua？

```lua
-- seckill.lua 核心逻辑
if tonumber(redis.call('get', stockKey)) <= 0 then return 1 end     -- 库存不足
if redis.call('sismember', orderKey, userId) == 1 then return 2 end -- 重复下单
redis.call('incrby', stockKey, -1)                                   -- 扣库存
redis.call('sadd', orderKey, userId)                                 -- 记录用户
redis.call('xadd', 'stream.orders', '*', 'userId', userId, ...)     -- 写入Stream
return 0
```

**为什么用 Lua**：Redis 执行 Lua 脚本是**原子性**的，上述 5 个操作在一个 Lua 脚本中执行，不会被其他命令插入打断，从根本上避免了超卖和重复下单。

### 延伸知识点

- 简历中提到使用 RabbitMQ 做异步解耦。代码中实际使用的是 Redis Stream，面试时可以说明：**Redis Stream 用于轻量级场景足够，如果需要更强的消息可靠性保证（持久化、死信队列、消息回溯），可以替换为 RabbitMQ 或 Kafka。**
- 如果面试官问为什么简历写的是 RabbitMQ，可以回答：**最初设计方案使用了 RabbitMQ，后续评估后认为当前场景 Redis Stream 已经足够，减少了额外中间件的运维成本。两种方案我都了解。**

---

## Q8：Lua 脚本的原子性是怎么保证的？有什么限制？

### 标准答案

Redis 使用**单线程事件循环**执行命令。Lua 脚本在执行期间，Redis 不会处理其他任何命令，因此脚本内的多个操作是原子的。

**限制**：
1. Lua 脚本不能执行太久（默认 `lua-time-limit` 5 秒），否则会阻塞 Redis。
2. Cluster 模式下，Lua 脚本操作的所有 Key 必须在同一个 slot 中（可以用 `{hash_tag}` 保证）。
3. Lua 脚本中不能有随机性操作（如 `TIME`），否则主从复制会导致数据不一致。

### 追问：在 Redis Cluster 下你的秒杀 Lua 脚本还能正常工作吗？

当前脚本操作了 `stockKey`、`orderKey`、`stream.orders` 三个 key，它们不在同一个 slot 中，所以在 Cluster 模式下会报 `CROSSSLOT` 错误。解决方案：

```lua
local stockKey = 'seckill:stock:{' .. voucherId .. '}'
local orderKey = 'seckill:order:{' .. voucherId .. '}'
```

使用 `{voucherId}` 作为 Hash Tag，保证同一个券的相关 key 落在同一个 slot。但 `stream.orders` 也需要加上 hash tag，或者将 stream 改为按券 ID 分开。

---

## Q9：说一下 Redis Stream 消费者组的消费流程和异常处理机制

### 标准答案

```java
// VoucherOrderHandler.run() 核心流程
while (true) {
    // 1. 读取新消息 (> 表示从最新未消费的消息开始读)
    List<MapRecord> list = stringRedisTemplate.opsForStream().read(
        Consumer.from("g1", "c1"),
        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
        StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
    );
    // 2. 无消息则 continue
    if (list == null || list.isEmpty()) continue;
    // 3. 解析并处理订单
    createVoucherOrder(voucherOrder);
    // 4. ACK 确认
    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
}
```

**异常处理**：如果处理过程中抛异常（消息已读取但未 ACK），消息会进入 **Pending List**。`handlePendingList()` 方法专门处理这些未确认的消息：

```java
// 从 Pending List 中读取（ReadOffset.from("0") 表示从 Pending List 起始位置）
StreamOffset.create("stream.orders", ReadOffset.from("0"))
```

这保证了**消息至少被消费一次（At Least Once）**。

### ⚠️ 代码质疑点

> **质疑 1**：`acknowledge("s1", ...)` 这里 stream name 写的是 `"s1"`，但实际 stream 名是 `"stream.orders"`，这是一个 **Bug**！
>
> **应对**：确实是一个笔误/Bug。正确应该是 `acknowledge("stream.orders", "g1", record.getId())`。在 `handlePendingList` 中也是同样的问题。这会导致 ACK 操作实际上没有生效，所有消息都会堆积在 Pending List 中。在面试中应该主动承认并说明如何修复。

> **质疑 2**：`handlePendingList` 中异常后只是 `log.error`，没有 sleep，可能导致 CPU 空转。
>
> **应对**：确实应该加一个短暂的 sleep（如 20ms），避免在持续异常时 CPU 占满。

### 延伸知识点

- Redis Stream 的消费者组类似 Kafka 的 Consumer Group，支持消息分配、ACK、Pending List。
- 与 RabbitMQ 对比：RabbitMQ 有死信队列（DLX）、消息 TTL、优先级队列等更丰富的特性。

---

## Q10：分布式锁你实现了几个版本？从 SimpleRedisLock 到 Redisson 做了哪些改进？

### 标准答案

#### V1：SimpleRedisLock — 基于 SETNX 的简单分布式锁

```java
// 加锁
Boolean success = stringRedisTemplate.opsForValue()
    .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);

// 解锁 — Lua 脚本保证原子性
if redis.call('get', KEYS[1]) == ARGV[1] then
    return redis.call('del', KEYS[1])
end
```

**关键设计**：
- 锁的 value 是 `UUID + 线程ID`，解锁时先比对标识再删除，防止误删他人的锁。
- 解锁操作用 Lua 脚本保证"判断+删除"的原子性。

**存在的问题**：
1. **不可重入**：同一个线程无法多次获取同一把锁。
2. **不可重试**：获取锁失败直接返回 false，不支持阻塞等待。
3. **超时释放风险**：如果业务执行时间超过锁的 TTL，锁自动释放，其他线程可能趁虚而入。
4. **无主从一致性保证**：Redis 主从同步有延迟，主节点宕机时从节点可能没有锁数据。

#### V2：Redisson 分布式锁

```java
RLock redisLock = redissonClient.getLock("lock:order:" + userId);
boolean isLock = redisLock.tryLock();
```

**Redisson 解决的问题**：
- ✅ **可重入**：使用 Hash 结构记录加锁次数（`{ lockKey: {threadId: count} }`）。
- ✅ **看门狗机制（WatchDog）**：默认 30 秒 TTL，加锁成功后每 10 秒（1/3 TTL）自动续期，防止业务未完成锁就过期。
- ✅ **可重试**：`tryLock(waitTime, leaseTime, unit)` 支持等待时间。
- ✅ **Pub/Sub 通知**：锁释放时通过发布订阅通知等待线程，避免轮询。

### 追问：Redisson 的 WatchDog 机制在什么情况下会失效？

如果调用 `tryLock(waitTime, leaseTime, unit)` 时指定了 `leaseTime`，WatchDog **不会启动**。只有使用不指定 `leaseTime` 的 `tryLock()` 或 `lock()` 方法时，WatchDog 才会生效。

### 追问：说一下 RedLock 算法？

RedLock 是 Redis 作者 Antirez 提出的用于解决主从一致性问题的方案：
- 在 N 个独立的 Redis 节点上同时加锁
- 超过半数节点（N/2+1）加锁成功，且总耗时小于锁的过期时间，才认为加锁成功
- Redisson 提供了 `RedissonRedLock` 的实现
- 但 Martin Kleppmann 对 RedLock 的安全性提出了质疑，实际生产中争议较大

### ⚠️ 代码质疑点

> **质疑**：`createVoucherOrder` 中使用 `redisLock.tryLock()` 无参调用，如果 Redis 此时不可用会怎样？
>
> **应对**：无参 `tryLock()` 默认 `waitTime=0`，即不等待直接返回。如果 Redis 不可用会抛出异常。在生产环境中应该有降级方案，比如使用数据库悲观锁作为兜底。

---

## Q11：全局唯一 ID（RedisIdWorker）是怎么设计的？有什么优势？

### 标准答案

```java
public long nextId(String keyPrefix) {
    long timestamp = nowSecond - BEGIN_TIMESTAMP;              // 时间戳部分
    String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
    long count = stringRedisTemplate.opsForValue()
        .increment("icr:" + keyPrefix + ":" + date);           // 序列号部分
    return timestamp << COUNT_BITS | count;                    // 拼接：高32位时间戳 + 低32位序列号
}
```

**ID 结构（64 位 long）**：

```
┌─ 1位符号位 ─┬── 31位时间戳 ──┬── 32位序列号 ──┐
│      0      │  秒级差值      │  Redis自增     │
└─────────────┴───────────────┴───────────────┘
```

- **时间戳**：当前秒数减去起始时间（2022-01-01），31 位可用 ~68 年。
- **序列号**：每天一个 Redis Key（`icr:order:2026:02:26`），每天可生成 2^32（约 42 亿）个 ID。
- **按天拆分 Key 的好处**：避免单个 Key 的值过大；方便按日期统计订单量。

### 追问：和雪花算法（Snowflake）对比有什么区别？

| 维度 | RedisIdWorker | Snowflake |
|------|---------------|-----------|
| 依赖 | 依赖 Redis | 不依赖外部组件 |
| 全局唯一性 | Redis INCR 原子递增保证 | 机器 ID + 时间戳 + 序列号 |
| 时钟回拨 | 无影响（序列号来自 Redis） | 会导致 ID 重复 |
| 性能 | 网络开销（但可批量获取） | 本地生成，极高性能 |
| 有序性 | 全局趋势递增 | 同一机器内有序 |

### 延伸知识点

- 美团 Leaf：结合了号段模式和 Snowflake 的优点，支持预分配减少网络调用。
- 百度 UidGenerator：基于 Snowflake 改进，支持更灵活的位分配。

---

## Q12：秒杀场景中，"一人一单"是怎么实现的？

### 标准答案

"一人一单"通过**三层防护**实现：

**第一层：Lua 脚本（Redis 层面）**
```lua
if redis.call('sismember', orderKey, userId) == 1 then
    return 2  -- 重复下单
end
redis.call('sadd', orderKey, userId)  -- 记录已购买用户
```
利用 Redis Set 的 `SISMEMBER` 判断用户是否已下单，原子操作保证并发安全。

**第二层：Redisson 分布式锁（应用层面）**
```java
RLock redisLock = redissonClient.getLock("lock:order:" + userId);
boolean isLock = redisLock.tryLock();
```
以 `userId` 为粒度加锁，同一用户的并发请求被串行化。

**第三层：数据库查询（兜底校验）**
```java
int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
if (count > 0) {
    log.error("不允许重复下单！");
    return;
}
```

### 追问：为什么 Lua 已经做了判重，还需要数据库层面的校验？

因为 **Redis 数据可能丢失**（如 Redis 重启、主从切换期间数据未同步），数据库是最终的数据源。三层防护遵循**最终一致性**原则：
- Lua 脚本 → 快速拦截绝大部分重复请求
- 分布式锁 → 防止同一用户的并发请求
- 数据库校验 → 最终兜底保证数据正确

### 追问：锁的粒度为什么是 userId 而不是 voucherId？

- 以 `userId` 为粒度，**同一用户的并发请求**被串行化，但**不同用户之间互不影响**。
- 如果以 `voucherId` 为粒度，所有用户抢同一张券时都要串行等待，极大降低并发性能。
- 实际的锁 key 是 `lock:order:{userId}`，做到了最小化锁粒度。

---

## Q13：你的异步下单方案中，如果消费者线程挂了或者消费失败，订单会丢失吗？

### 标准答案

**不会丢失（前提是 Redis 正常工作）**：

1. **消息持久化**：Redis Stream 的消息会被持久化（如果开启了 RDB/AOF）。
2. **Pending List 机制**：消息被 `XREADGROUP` 读取后，如果没有 `XACK`，会进入 Pending List。即使消费者进程挂了重启，`handlePendingList()` 方法会在 `@PostConstruct` 后重新消费这些消息。
3. **消费者组保证**：Redis Stream 的消费者组会记录每个消费者的消费位置。

### 追问：如果 Redis 本身挂了呢？

这是一个架构层面的风险。应对方案：
- **Redis 持久化**：开启 AOF `appendfsync everysec` 最多丢失 1 秒数据。
- **Redis Sentinel/Cluster**：高可用部署，自动故障转移。
- **更可靠的方案**：使用 RabbitMQ/Kafka 替代 Redis Stream，它们有更完善的消息持久化机制。
- **补偿机制**：定时任务扫描"Redis 中已扣减库存但数据库中无订单"的数据进行对账。

### ⚠️ 代码质疑点

> **质疑**：`@PostConstruct` 中直接 `submit` 开启消费者线程，如果应用启动时 Redis 还没准备好怎么办？
>
> **应对**：`init()` 方法中 `createGroup` 用了 try-catch，如果 Redis 未就绪会抛异常但不影响应用启动。消费者线程的 `while(true)` 循环中也有 try-catch，会在 Redis 恢复后自动重新消费。但更好的做法是使用 `@EventListener(ApplicationReadyEvent.class)` 延迟到应用完全启动后再创建消费者。

---

## Q14：你是怎么解决超卖问题的？CAS 乐观锁方案你了解吗？

### 标准答案

超卖问题的核心是：多个线程同时读到库存 > 0，然后都去扣减。

我的方案采用了 **乐观锁 + 条件更新**：

```java
boolean success = seckillVoucherService.update()
    .setSql("stock = stock - 1")
    .eq("voucher_id", voucherId)
    .gt("stock", 0)                // 关键：WHERE stock > 0
    .update();
```

SQL 等价于：
```sql
UPDATE tb_seckill_voucher SET stock = stock - 1
WHERE voucher_id = ? AND stock > 0
```

这里不是传统的 CAS（Compare-And-Swap），而是**直接在 SQL 的 WHERE 条件中判断库存**。好处是避免了 CAS 的 ABA 问题。

### 追问：传统 CAS 方案 `WHERE stock = #{oldStock}` 有什么问题？

- **ABA 问题**：不是这里的主要问题。
- **成功率过低**：100 个线程同时读到 stock=100，只有一个线程 `WHERE stock = 100` 成功，其余 99 个全部失败，即使库存充足也卖不出去。
- 所以改用 `WHERE stock > 0`，只要库存充足就能扣减，大幅提高成功率。

### 追问：数据库乐观锁和 Redis Lua 原子扣减的区别？

| 维度 | 数据库乐观锁 | Redis Lua |
|------|-------------|-----------|
| 性能 | 每次请求都查数据库，QPS 受限 | 全在内存操作，QPS 可达数万 |
| 适用阶段 | 最终落库时兜底 | 请求入口快速拦截 |
| 一致性保证 | 依赖数据库事务 | 依赖 Lua 原子性 |

当前架构是两者结合：**Lua 在 Redis 层快速拦截 → 异步落库时数据库乐观锁兜底**。

---

## Q15：Feed 流你为什么选择推模式？有什么替代方案？

### 标准答案

项目使用 **推模式（Push/Write-fan-out）**：当用户发布笔记时，主动推送到所有粉丝的收件箱（Redis SortedSet）。

```java
// BlogServiceImpl.saveBlog()
List<Follow> follows = followService.query()
    .eq("follow_user_id", user.getId()).list();
for (Follow follow : follows) {
    String key = FEED_KEY + follow.getUserId();
    stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(),
        System.currentTimeMillis());
}
```

读取时使用 **滚动分页**（`ZREVRANGEBYSCORE` + offset），避免传统分页在数据更新时出现重复/遗漏。

### 三种模式对比

| 模式 | 写入复杂度 | 读取复杂度 | 适用场景 |
|------|-----------|-----------|---------|
| 推模式 | O(粉丝数) | O(1) | 粉丝数少的用户 |
| 拉模式 | O(1) | O(关注数) | 粉丝数多的大 V |
| 推拉结合 | 视情况 | 视情况 | 微博、Twitter |

### 追问：如果某个用户有百万粉丝，推模式还能用吗？

不适合。百万粉丝意味着发一条笔记要写 100 万次 Redis，延迟和存储开销都不可接受。大 V 应该用拉模式：粉丝读取时即时拉取大 V 的最新内容。微博的做法是推拉结合：
- 活跃粉丝用推模式（保证实时性）
- 不活跃粉丝用拉模式（节省资源）

### 延伸知识点

- **滚动分页**的关键：用时间戳（score）+ offset 实现，避免传统 page + size 在动态数据中的跳页问题。
- SortedSet 的底层结构是**跳表（SkipList）** + **压缩列表/ListPack**，查询复杂度 O(logN)。

---

## Q16：ThreadLocal 的底层原理是什么？为什么能实现线程隔离？

### 标准答案

`ThreadLocal` 的数据并不存储在 `ThreadLocal` 对象本身，而是存储在**每个线程的 Thread 对象**中的 `threadLocals` 字段（类型为 `ThreadLocalMap`）。

```java
// Thread 类中
ThreadLocal.ThreadLocalMap threadLocals = null;
```

当调用 `threadLocal.set(value)` 时：
1. 获取当前线程 `Thread.currentThread()`
2. 获取线程的 `ThreadLocalMap`
3. 以当前 `ThreadLocal` 实例作为 key，value 作为值存入 Map

每个线程有自己独立的 `ThreadLocalMap`，所以天然线程隔离。

### 追问：ThreadLocalMap 的 Entry 为什么使用弱引用？

```java
static class Entry extends WeakReference<ThreadLocal<?>> {
    Object value;
}
```

Entry 的 key（ThreadLocal 对象）是弱引用。当外部不再有 ThreadLocal 的强引用时，GC 会回收 ThreadLocal 对象，key 变为 null。但 **value 仍然被 Entry 强引用**，不会被 GC 回收，形成**内存泄漏**。

所以必须在使用完后调用 `threadLocal.remove()` 来清理。项目代码中的 `UserHolder.removeUser()` 就是做这个清理的。

### 延伸知识点

- `InheritableThreadLocal`：创建子线程时会继承父线程的值，但线程池场景下无效（线程复用）。
- `TransmittableThreadLocal`（阿里开源）：解决线程池场景下的值传递问题。

---

## Q17：项目中涉及到哪些 Redis 数据结构？各自的适用场景和底层实现？

### 标准答案

| 数据结构 | 使用场景 | 底层实现 |
|----------|---------|---------|
| **String** | 验证码缓存、商铺缓存（JSON）、分布式锁（SETNX）、全局 ID 自增 | SDS（Simple Dynamic String） |
| **Hash** | 用户 Token 信息存储 | 小数据量用 ziplist/listpack，大数据量用 hashtable |
| **Set** | 关注列表、秒杀已购用户集合 | 小数据量用 intset，大数据量用 hashtable |
| **SortedSet (ZSet)** | 点赞排行榜、Feed 流收件箱 | ziplist/listpack（小数据） 或 skiplist + hashtable |
| **Stream** | 异步消息队列（秒杀订单） | 基于 Radix Tree + listpack |
| **BitMap** | 用户签到 | 实际上是 String 类型，以 bit 为单位操作 |
| **GEO** | 附近商户查询 | 底层是 ZSet，将经纬度转为 GeoHash 编码作为 score |

### 追问：ZSet 底层的跳表是什么？为什么不用红黑树？

跳表是一种多级索引的有序链表，查询、插入、删除的时间复杂度都是 O(logN)。

Redis 选择跳表而不是红黑树的原因：
1. **实现更简单**：跳表代码量远小于红黑树。
2. **范围查询更高效**：跳表找到起点后顺序遍历即可，红黑树需要中序遍历。
3. **内存更灵活**：通过调整层数概率来平衡空间和时间。

---

## Q18：如果让你对这个项目做进一步优化，你会怎么做？

### 标准答案

#### 性能层面
1. **热点数据预热**：秒杀开始前，通过定时任务将券的库存、商铺信息预加载到 Redis。
2. **本地缓存（Caffeine）**：对商铺类型等变化不频繁的数据加一层 JVM 本地缓存，形成 L1（Caffeine）+ L2（Redis）多级缓存。
3. **连接池优化**：调整 Lettuce/Jedis 连接池参数，使用 pipeline 批量操作减少网络 RTT。

#### 可靠性层面
4. **将 Redis Stream 替换为 RabbitMQ/Kafka**：获得更强的消息持久化和死信队列能力。
5. **分布式事务**：引入 Seata 或基于 TCC 模式保证 Redis 扣减和数据库落库的最终一致性。
6. **对账系统**：定时任务比对 Redis 已扣减库存与数据库实际订单数，发现不一致时报警。

#### 架构层面
7. **限流降级**：在 Nginx 或 Gateway 层加入限流（令牌桶/漏桶），使用 Sentinel 实现熔断降级。
8. **读写分离**：MySQL 主从分离，读操作打到从库。
9. **分库分表**：订单表按用户 ID 做 sharding（ShardingSphere）。
10. **接口幂等性**：使用唯一请求 ID + Redis 做接口幂等校验，防止重复提交。

#### 安全层面
11. **接口防刷**：IP 限流 + 验证码 + 滑动窗口限流。
12. **秒杀链接隐藏**：秒杀开始前不暴露真实下单接口，防止脚本提前请求。

### 追问：如果秒杀 QPS 达到 10 万级，你的架构还撑得住吗？

需要做以下改进：
- **多级缓存**：Nginx 本地缓存 → 应用本地缓存（Caffeine）→ Redis 集群。
- **Redis 集群水平扩展**：将秒杀券的库存分散到多个 slot，支持并行扣减。
- **库存分段**：将 100 个库存分成 10 个 slot，每 slot 10 个，请求随机落到不同 slot，减少竞争。
- **CDN + 前端限流**：按钮防重复点击、倒计时、答题验证等。
- **异步排队**：返回"排队中"立即响应用户，后端异步处理。

---

## 附录：代码中易被质疑的点汇总表

| 位置 | 问题 | 风险等级 | 应对策略 |
|------|------|---------|---------|
| `VoucherOrderServiceImpl` L99 | `acknowledge("s1", ...)` stream name 写错，应为 `"stream.orders"` | 🔴 高 | 承认 Bug，说明正确写法 |
| `VoucherOrderServiceImpl` L128 | `handlePendingList` 中同样的 ACK Bug | 🔴 高 | 同上 |
| `CacheClient.queryWithMutex` L146 | 递归调用无最大重试限制，可能栈溢出 | 🟡 中 | 说明改用 while 循环 + 最大重试次数 |
| `CacheClient.queryWithLogicalExpire` L100 | 获取锁后未做 DCL（双重检查） | 🟡 中 | 说明 SETNX 已保证互斥，但承认 DCL 更优 |
| `RefreshTokenInterceptor` | 非 Spring Bean，通过构造器注入 | 🟢 低 | 解释注册机制限制，说明可优化为 @Component |
| `VoucherOrderServiceImpl` L61 | 使用 `newSingleThreadExecutor`，无界队列可能 OOM | 🟡 中 | 说明生产中应使用自定义线程池 + 有界队列 |
| `RedisIdWorker` | 时间戳使用 `ZoneOffset.UTC`，与中国时区不一致 | 🟢 低 | 说明只影响 ID 中的时间戳语义，不影响唯一性 |
| 简历描述 | 简历写 RabbitMQ，代码用 Redis Stream | 🟡 中 | 说明最初方案用 RabbitMQ，后优化为 Redis Stream，两者都了解 |

---

> 💡 **备考建议**：面试前重点复习 Redis 五大数据结构底层原理（SDS、ziplist、skiplist、intset、hashtable）、Redisson 源码（tryLock/WatchDog）、以及 JUC 并发包（CAS、AQS、线程池七大参数）。这些是高频追问方向。
