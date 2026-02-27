# 商户缓存模块深度剖析

---

## 一、缓存的数据结构：为什么用 String 不用 Hash？

看代码，缓存存储方式是把整个 Shop 对象序列化成 JSON 字符串，用 **String** 类型存储：

```java
// CacheClient.set()
stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
// key 示例: "cache:shop:1"
// value 示例: "{\"id\":1,\"name\":\"茶百道\",\"typeId\":1,\"address\":\"xxx\",...}"
```

### 面试回答："为什么不用 Hash？"

> "虽然 Hash 结构更适合存储对象（一个 field 对应一个属性），但在这里我选择 String + JSON 有几个原因：
>
> 1. **操作更简单**：String 的 GET/SET 是单条命令，Hash 的 HGETALL 也是一条命令，但如果需要局部更新用 HSET，局部读取用 HGET，Cache Aside 模式下我们做的是整体读写 + 整体删除，不需要局部操作，所以 String 够用。
> 2. **支持设置 TTL**：String 的 SET 命令可以直接带 EX 参数设置过期时间，Hash 也可以对整个 key 设置 TTL，这点两者都行。
> 3. **序列化灵活**：JSON 字符串方便跨语言（前端直接能用），而且我的逻辑过期方案需要给数据包一层 RedisData（含 expireTime），用 String 嵌套 JSON 更直观。"

### 什么时候该用 Hash？

| 场景 | 推荐结构 |
|------|---------|
| 读整个对象、写整个对象（本项目） | **String + JSON** |
| 需要频繁读写单个字段（如购物车数量） | **Hash** |
| 对象很大且只需要部分字段 | **Hash**（省带宽） |
| 需要对字段做计数操作 | **Hash + HINCRBY** |

**补充**：本项目里用户 Token 用的就是 Hash（`UserServiceImpl.login()`），因为需要存多个字段并且用 `StringRedisTemplate` 要求值为 String，Hash 的每个 field 存一个字段更自然。

---

## 二、CacheClient 工具类代码详解

`CacheClient` 是一个**通用的缓存工具类**，用泛型 + 函数式接口封装了缓存操作，核心设计思路是：

```
调用者只需要告诉我：key 前缀、ID、类型、查库方法、TTL
我帮你处理：查缓存 → 缓存未命中 → 查数据库 → 写缓存 → 处理穿透/击穿
```

### 方法一览

```java
public class CacheClient {
    // 基础写入
    void set(key, value, time, unit)              // 普通写入+TTL
    void setWithLogicalExpire(key, value, time, unit) // 写入+逻辑过期时间

    // 查询（核心）
    R queryWithPassThrough(...)     // 缓存穿透方案：缓存空值
    R queryWithBloomFilter(...)     // 缓存穿透方案：布隆过滤器
    R queryWithMutex(...)           // 缓存击穿方案：互斥锁
    R queryWithLogicalExpire(...)   // 缓存击穿方案：逻辑过期
}
```

### queryWithPassThrough 逐行解读

```java
public <R, ID> R queryWithPassThrough(
        String keyPrefix, ID id, Class<R> type,
        Function<ID, R> dbFallback,   // 函数式接口：传入查数据库的方法
        Long time, TimeUnit unit) {

    String key = keyPrefix + id;      // 拼 key，比如 "cache:shop:" + 1

    // ① 查 Redis
    String json = stringRedisTemplate.opsForValue().get(key);

    // ② 缓存命中且不为空字符串 → 直接返回
    if (StrUtil.isNotBlank(json)) {
        return JSONUtil.toBean(json, type);  // JSON → Java 对象
    }

    // ③ 命中了空字符串 "" → 说明这是我们之前写入的"空值缓存"
    //    json 不是 null（Redis 里有这个 key），但是 isBlank（值是 ""）
    if (json != null) {
        return null;   // 直接返回 null，不查数据库！拦住了穿透
    }

    // ④ 真正的缓存未命中（key 在 Redis 中不存在，json == null）
    R r = dbFallback.apply(id);       // 查数据库

    // ⑤ 数据库也没有 → 写入空值缓存，2 分钟过期
    if (r == null) {
        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
        return null;
    }

    // ⑥ 数据库有 → 写入正常缓存
    this.set(key, r, time, unit);
    return r;
}
```

**面试加分点**：这里用了 `Function<ID, R> dbFallback` 函数式接口，调用方只需要传 `this::getById`，工具类就能查任何表，不绑定具体的 Service。这就是**策略模式 + 模板方法**的思想。

---

## 三、为什么 MySQL 扛不住？Redis vs MySQL 的本质区别

### 不只是"内存 vs 磁盘"

| 维度 | Redis | MySQL |
|------|-------|-------|
| **存储介质** | 内存（主）+ 磁盘（持久化备份） | 磁盘（主）+ Buffer Pool（内存缓存） |
| **数据结构** | 哈希表直接寻址，O(1) | B+ 树索引，O(logN) + 回表 |
| **并发模型** | 单线程事件循环，无锁竞争 | 多线程 + 锁机制（行锁/表锁/MVCC） |
| **网络模型** | IO 多路复用（epoll），高效处理并发连接 | 每个连接一个线程，连接数有限 |
| **QPS 上限** | 单机可达 10 万+ | 单机通常 3000-5000（复杂查询更低） |

### 面试话术

> "MySQL 扛不住高并发的原因不仅仅是磁盘 IO 慢。更关键的是：
>
> 1. **锁竞争**：MySQL 的 InnoDB 在并发写入时需要行锁、间隙锁等，高并发下锁等待严重。
> 2. **连接数限制**：MySQL 默认最大连接数 151，每个连接都要分配线程和内存，1000 并发就可能连接池耗尽。
> 3. **查询解析开销**：每次查询都要经过 SQL 解析 → 查询优化 → 执行计划 → 回表查数据，Redis 直接 key-value 查找没有这些开销。
>
> 而 Redis 的单线程模型反而避免了锁竞争和上下文切换，加上纯内存操作和 IO 多路复用，单机就能轻松达到 10 万 QPS。"

---

## 四、布隆过滤器原理详解

### 核心数据结构：位数组 + 多个哈希函数

```
添加元素 "shop:1"：
  hash1("shop:1") = 3    → 位数组第 3 位 → 1
  hash2("shop:1") = 7    → 位数组第 7 位 → 1
  hash3("shop:1") = 11   → 位数组第 11 位 → 1

位数组状态（15位示例）：
  [0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0]
              ↑              ↑              ↑
            hash1          hash2          hash3

查询 "shop:999"（不存在）：
  hash1("shop:999") = 3  → 第 3 位 = 1  ✅
  hash2("shop:999") = 5  → 第 5 位 = 0  ❌  → 一定不存在！直接拦截

查询 "shop:888"（不存在但误判）：
  hash1("shop:888") = 3  → 第 3 位 = 1  ✅
  hash2("shop:888") = 7  → 第 7 位 = 1  ✅
  hash3("shop:888") = 11 → 第 11 位 = 1 ✅  → 三位都是 1，误判为"存在"！
```

### 关键特性

```
  布隆过滤器说"不存在" → 100% 一定不存在 ✅
  布隆过滤器说"存在"   → 可能存在，也可能误判 ⚠️
```

### 误判率怎么控制？

误判率取决于三个参数：
- **m**：位数组大小
- **n**：预期插入的元素数量
- **k**：哈希函数个数

公式：`误判率 ≈ (1 - e^(-kn/m))^k`

我们代码里的设置：

```java
// BloomFilterConfig.java
bloomFilter.tryInit(
    10000L,   // expectedInsertions：预期插入 1 万个商铺 ID
    0.01      // falseProbability：误判率 1%
);
```

Redisson 内部会根据这两个参数**自动计算**最优的位数组大小（m）和哈希函数个数（k）：
- 1 万数据 + 1% 误判率 → 大约需要 **96KB** 位数组 + **7 个哈希函数**
- 如果改成 0.001（0.1%）→ 约 144KB + 10 个哈希函数

### 布隆过滤器的缺点

1. **不能删除元素**：位数组的某一位可能被多个元素共用，删一个会影响其他元素
   - 解决方案：定期重建整个过滤器，或使用 Counting Bloom Filter（每位用计数器代替 0/1）
2. **有误判**：需要兜底方案（比如我们在 `queryWithBloomFilter` 里对误判情况仍然缓存了空值）
3. **新增数据需要同步**：新增商铺时必须 `bloomFilter.add(id)`，否则新商铺会被拦截

---

## 五、互斥锁解决缓存击穿——代码详解

### 这是分布式锁吗？

**是**，但是一个**极简版**的分布式锁——基于 Redis 的 `SETNX`（SET if Not eXists）。

```java
// CacheClient 中的互斥锁实现
private boolean tryLock(String key) {
    // SETNX：如果 key 不存在则设置成功返回 true，否则返回 false
    // 同时设置 10 秒过期，防止死锁
    Boolean flag = stringRedisTemplate.opsForValue()
        .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

private void unlock(String key) {
    stringRedisTemplate.delete(key);
}
```

### queryWithMutex 核心逻辑

```java
public <R, ID> R queryWithMutex(...) {
    // 1. 查缓存
    String shopJson = stringRedisTemplate.opsForValue().get(key);
    if (StrUtil.isNotBlank(shopJson)) return JSONUtil.toBean(shopJson, type);  // 命中
    if (shopJson != null) return null;                                         // 空值

    // 2. 缓存未命中 → 尝试获取互斥锁
    String lockKey = LOCK_SHOP_KEY + id;       //  "lock:shop:1"
    boolean isLock = tryLock(lockKey);

    if (!isLock) {
        // 获取锁失败 → 说明有其他线程正在重建缓存
        // 休眠 50ms 后重试（等别人重建好了我再查缓存）
        Thread.sleep(50);
        return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
    }

    // 获取锁成功 → 我来重建缓存
    try {
        R r = dbFallback.apply(id);            // 查数据库
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);          // 写入缓存
    } finally {
        unlock(lockKey);                        // 释放锁
    }
    return r;
}
```

### 与 Redisson 分布式锁的区别

| 维度 | CacheClient 的 tryLock | Redisson 分布式锁 |
|------|----------------------|------------------|
| 用途 | 缓存击穿保护（短暂，10 秒） | 业务互斥（如一人一单） |
| 可重入 | ❌ 不可重入 | ✅ 可重入（Hash 计数） |
| 自动续期 | ❌ 没有 | ✅ WatchDog 每 10s 续期 |
| 解锁安全 | ⚠️ 直接 delete，可能误删 | ✅ Lua 脚本判断后再删 |
| 失败处理 | sleep + 重试 | Pub/Sub 通知 |

**面试话术**：
> "缓存击穿场景下的互斥锁是一个轻量级的分布式锁，用 SETNX 实现，只需要保证短暂的互斥就行，不需要 Redisson 那么重量级的实现。但在秒杀一人一单场景下，我用的是 Redisson，因为那个场景业务时间更长，需要可重入和自动续期。"

---

## 六、三种缓存一致性策略对比

### Cache Aside（旁路缓存）—— 本项目使用的方案

```
读：先读缓存，未命中则读DB，然后写缓存
写：先写DB，再删缓存
```

```java
// ShopServiceImpl.update() — 就是典型的 Cache Aside
updateById(shop);                                  // 先更新 DB
stringRedisTemplate.delete(CACHE_SHOP_KEY + id);   // 再删缓存
```

- **优点**：实现最简单，最常用
- **缺点**：极端并发下可能短暂不一致（概率极低）
- **适用**：绝大多数业务场景

### Read/Write Through（读写穿透）

```
读：读缓存，未命中则由 "缓存层" 自动加载DB数据，应用只跟缓存交互
写：写缓存，由 "缓存层" 同步写DB
```

- 应用不直接操作数据库，而是通过一个"缓存服务层"代理
- 缓存层负责保证缓存和 DB 的同步
- **优点**：对应用层透明，代码简洁
- **缺点**：需要一个独立的缓存代理服务，增加架构复杂度
- **代表**：Guava Cache 的 `CacheLoader`，或一些 ORM 框架的二级缓存

### Write Behind（异步写回）

```
读：读缓存，未命中则加载DB
写：只写缓存，缓存异步批量写回DB
```

- **优点**：写入性能极高（只写内存），适合写多读少场景
- **缺点**：缓存宕机可能丢数据！一致性最差
- **代表**：操作系统的 Page Cache、CPU 的 Write Back 策略

### 面试总结表

| 策略 | 读 | 写 | 一致性 | 复杂度 | 适用场景 |
|------|----|----|--------|--------|---------|
| **Cache Aside** | 查缓存 → 查DB → 写缓存 | 更新DB → 删缓存 | 较好 | 低 | **大部分业务（推荐）** |
| **Read/Write Through** | 查缓存（未命中自动加载） | 写缓存（自动同步DB） | 好 | 中 | 有缓存代理层的架构 |
| **Write Behind** | 查缓存 | 只写缓存，异步回写DB | 弱 | 高 | 写密集型、允许丢数据 |

---

## 七、Canal + MQ 方案是什么？怎么实现？

### 问题背景

Cache Aside 模式有一个极端并发问题（虽然概率很低）：

```
1. 缓存刚好过期
2. 线程A 查DB得到旧值
3. 线程B 更新DB写新值
4. 线程B 删缓存
5. 线程A 把旧值写入缓存    ← 缓存中是脏数据！
```

### Canal + MQ 方案架构

```
                   MySQL
                     │
                     │ binlog（二进制日志）
                     ▼
                  Canal Server
              （伪装成 MySQL 从节点，
               实时监听 binlog 变更）
                     │
                     │ 解析变更事件
                     ▼
                 RabbitMQ / Kafka
                     │
                     │ 消费消息
                     ▼
              缓存删除/更新服务
                     │
                     ▼
                   Redis
            （删除对应的缓存 key）
```

### 工作流程

1. **Canal** 是阿里开源的中间件，它伪装成 MySQL 的从节点（Slave）
2. MySQL 主节点的每一次数据变更（INSERT/UPDATE/DELETE）都会写入 **binlog**
3. Canal 实时监听 binlog，解析出"哪张表的哪条数据变了"
4. Canal 把变更事件推送到 **MQ**（RabbitMQ/Kafka）
5. 一个消费者服务监听 MQ，收到消息后删除 Redis 中对应的缓存 key

### 为什么比"先更新DB再删缓存"更可靠？

- **解耦**：业务代码完全不需要关心缓存删除，只管更新数据库
- **可靠性**：即使删缓存失败，MQ 会重试
- **兜底**：如果代码里漏写了删缓存（比如直接 SQL 改了数据库），Canal 也能感知到
- **异步**：不影响主流程性能

### 面试话术

> "生产环境如果对一致性要求更高，可以引入 Canal 监听 MySQL binlog，通过 MQ 异步删除缓存。这样做到了**最终一致性**，而且对业务代码零侵入——不需要在每个 update 方法里手动删缓存。"

### 延迟双删方案（更轻量的替代）

如果不想引入 Canal，可以用**延迟双删**：

```java
// 伪代码
updateDB(shop);                    // 1. 更新数据库
deleteCache(key);                  // 2. 立即删缓存
Thread.sleep(500);                 // 3. 等待 500ms（等读请求的旧缓存写入完成）
deleteCache(key);                  // 4. 再次删缓存
```

延迟双删能覆盖极端并发场景，但 sleep 会影响性能，实际中可以把第二次删除放到 MQ 异步执行。
