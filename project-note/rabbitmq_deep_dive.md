# RabbitMQ 面试常考知识点

---

## 一、先搞懂 RabbitMQ 的整体架构

```
生产者 (Producer)                                    消费者 (Consumer)
     │                                                    ▲
     │ 发消息                                         接消息 │
     ▼                                                    │
┌─────────────────── RabbitMQ Broker ───────────────────────┐
│                                                           │
│   ┌──────────┐    路由规则    ┌──────────┐                │
│   │ Exchange  │ ──────────→  │  Queue   │                │
│   │ (交换机)   │              │  (队列)   │                │
│   └──────────┘              └──────────┘                │
│                                                           │
│   交换机决定消息                队列存储消息                  │
│   发到哪个队列                 消费者从队列取                 │
└───────────────────────────────────────────────────────────┘
```

**用快递来类比**：
- **生产者** = 寄件人（你的 Java 代码发消息）
- **交换机** = 快递分拣中心（决定包裹发到哪个目的地）
- **队列** = 快递柜（暂存包裹，等收件人来取）
- **消费者** = 收件人（你的 Java 代码收消息）

**关键理解：生产者不直接发消息到队列，而是发到交换机，交换机根据规则路由到队列。**

---

## 二、四种交换机类型（必背）

### 1. Direct Exchange（直连交换机）—— 本项目用的

```
生产者发消息时指定 routingKey = "seckill.order"

                    ┌─ routingKey="seckill.order" ─→ [seckill.order.queue] ✅ 匹配
Exchange (Direct) ──┤
                    └─ routingKey="user.register" ─→ [user.queue]         ❌ 不匹配
```

**精确匹配**：routingKey 必须完全相同才能路由到对应的队列。

对应你的代码：
```java
// RabbitMQConfig.java
BindingBuilder.bind(seckillOrderQueue)
    .to(seckillOrderExchange)
    .with("seckill.order");   // ← routingKey

// RabbitMQOrderMessageSender.java
rabbitTemplate.convertAndSend("seckill.order.exchange", "seckill.order", json);
//                            交换机名                    routingKey      消息体
```

### 2. Fanout Exchange（扇出/广播交换机）

```
                    ┌─→ [queue1] ✅ 收到
Exchange (Fanout) ──┼─→ [queue2] ✅ 收到     所有绑定的队列都收到！
                    └─→ [queue3] ✅ 收到
```

**无视 routingKey**，所有绑定到这个交换机的队列都会收到消息。
**场景**：通知所有服务（比如用户注册后，积分服务、邮件服务、短信服务都要知道）。

### 3. Topic Exchange（主题交换机）

```
routingKey = "order.seckill.success"

                    ┌─ "order.#"           ─→ [order.queue]    ✅  # 匹配多个词
Exchange (Topic) ───┼─ "order.seckill.*"   ─→ [seckill.queue]  ✅  * 匹配一个词
                    └─ "user.#"            ─→ [user.queue]     ❌  不匹配
```

**模糊匹配**：`*` 匹配一个单词，`#` 匹配零个或多个单词。
**场景**：按业务分类路由（比如 `log.error.payment` 发到错误日志队列）。

### 4. Headers Exchange（头交换机）

根据消息头（headers）的键值对匹配，**很少用**，面试一般不问，知道有这个就行。

### 面试回答模板

| 类型 | 路由规则 | 典型场景 |
|------|---------|---------|
| **Direct** | routingKey 精确匹配 | 点对点，一个消息给一个消费者 |
| **Fanout** | 广播所有队列 | 通知所有服务 |
| **Topic** | routingKey 模糊匹配 | 按主题分类路由 |
| Headers | 消息头匹配 | 很少用 |

---

## 三、消息可靠性（面试必问："消息会丢吗？怎么保证不丢？"）

消息从生产到消费经历三个阶段，每个阶段都可能丢：

```
生产者 ──①──→ 交换机 ──②──→ 队列 ──③──→ 消费者

① 生产者 → 交换机：网络断了，消息没到交换机
② 交换机 → 队列：routingKey 写错了，或队列不存在
③ 队列 → 消费者：消费者拿到消息还没处理就挂了
额外：RabbitMQ 本身挂了，内存里的消息没了
```

### 三端保证方案

| 阶段 | 问题 | 解决方案 | 你的代码 |
|------|------|---------|---------|
| ①生产端 | 消息没到交换机 | **Publisher Confirm**（生产者确认） | 可在 yaml 加 `publisher-confirm-type: correlated` |
| ②路由 | 消息没到队列 | **Return 回调** | 可在 yaml 加 `publisher-returns: true` |
| ③存储 | RabbitMQ 宕机 | **持久化**（交换机+队列+消息都设 durable） | 代码里 `DirectExchange(name, true, false)` 和 `QueueBuilder.durable()` ✅ |
| ④消费端 | 消费者处理失败 | **手动 ACK** | `acknowledge-mode: manual` + `channel.basicAck()` ✅ |

### 面试话术

> "我从三端保证消息不丢失：
> 1. **生产端**用 Publisher Confirm，消息到达交换机后 Broker 会回调确认
> 2. **Broker 端**交换机和队列都设置为持久化（durable），消息也持久化到磁盘
> 3. **消费端**用手动 ACK，消费者处理完业务逻辑后才确认消息。如果消费者挂了，消息会重新入队等待其他消费者处理"

---

## 四、死信队列（Dead Letter Queue）

### 什么是死信？

一条消息变成"死信"有三种情况：

```
正常队列里的消息
     │
     ├─ ① 消费者 basicNack/basicReject 拒绝了，且 requeue=false
     ├─ ② 消息 TTL 过期（在队列里等太久没人消费）
     └─ ③ 队列满了（到达 max-length），新消息进不来，老消息被挤出去
     │
     ▼
  死信交换机 (DLX) ──→ 死信队列 (DLQ)
                         │
                         ▼
                    死信消费者（记录日志/告警/人工处理）
```

### 在你的项目里可以怎么用？

```
正常流程：
  seckill.order.exchange → seckill.order.queue → 消费者处理订单

如果处理失败（比如数据库连不上）：
  消费者 basicNack(requeue=false) → 消息变死信
  → 自动转发到 seckill.order.dlx (死信交换机)
  → 进入 seckill.order.dlq (死信队列)
  → 死信消费者记录日志或告警，运维人工处理
```

### 当前代码的处理方式

```java
// RabbitMQOrderMessageConsumer.java
} catch (Exception e) {
    // 当前实现：拒绝并重新入队（会无限重试）
    channel.basicNack(deliveryTag, false, true);
    //                                    ↑ requeue=true
}
```

**面试可以主动说改进方向**：
> "当前是 nack + requeue，消息会无限重试。更好的做法是设置重试次数（比如用 `spring.rabbitmq.listener.simple.retry` 配置最多重试 3 次），超过次数后 requeue=false 进入死信队列，由运维人工处理。"

---

## 五、消息重复消费（幂等性）

### 问题

网络抖动或消费者超时，同一条消息可能被投递两次：

```
消费者处理完了 → 发 ACK → 网络断了 → RabbitMQ 没收到 ACK
→ RabbitMQ 以为没处理 → 重新投递 → 消费者又收到了同一条消息
```

### 解决方案：幂等性保证

你的代码里**已经有幂等保护**：

```java
// VoucherOrderServiceImpl.createVoucherOrder()
int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
if (count > 0) {
    return;   // 已经有订单了，重复消息直接跳过
}
```

**面试话术**：
> "消费端做了幂等处理。创建订单前先查数据库判断是否已存在，配合 Redisson 分布式锁保证并发安全。即使同一条消息被重复投递，也不会创建重复订单。"

---

## 六、RabbitMQ vs Kafka（高频对比题）

| 维度 | RabbitMQ | Kafka |
|------|----------|-------|
| **定位** | 通用消息队列 | 分布式流处理平台 |
| **吞吐量** | 万级（单机） | 百万级（集群） |
| **延迟** | 微秒级（更低） | 毫秒级 |
| **消息模型** | 推模式（Broker 推给消费者） | 拉模式（消费者从 Broker 拉取） |
| **路由** | 灵活（4种交换机） | 简单（Topic + Partition） |
| **消息保留** | 消费后删除 | 按时间保留（可重复消费） |
| **适用场景** | 业务消息、订单、通知 | 日志收集、大数据、流处理 |

### 面试话术："为什么选 RabbitMQ 不选 Kafka？"

> "在秒杀场景下，消息量级是万级不是百万级，RabbitMQ 完全够用。而且 RabbitMQ 的优势在于：
> 1. 路由机制灵活——Exchange + RoutingKey 可以实现精确路由
> 2. 消息确认机制完善——Publisher Confirm + 手动 ACK
> 3. 有死信队列——处理失败的消息不会丢，可以兜底
> 4. 延迟更低——适合对实时性要求高的业务场景
>
> Kafka 更适合日志收集、用户行为追踪这种高吞吐、允许少量延迟的场景。"

---

## 七、快速自测

能答出来这 5 个你就过关了：

- [ ] RabbitMQ 的消息从生产到消费经过哪些组件？
- [ ] Direct、Fanout、Topic 交换机分别什么场景用？
- [ ] 消息丢失有哪三个阶段？分别怎么保证？
- [ ] 什么是死信？消息什么条件下变成死信？
- [ ] RabbitMQ 和 Kafka 怎么选？
