package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Redis Stream 模式的消息发送者
 * 由于 Lua 脚本 (seckill.lua) 中已经通过 XADD 将消息写入 Stream，
 * Java 侧无需再次发送，因此这是一个空操作（no-op）实现。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.mq.type", havingValue = "redis-stream", matchIfMissing = true)
public class RedisStreamOrderMessageSender implements OrderMessageSender {

    @Override
    public void sendOrder(VoucherOrder order) {
        // Lua 脚本中已通过 XADD 写入 Redis Stream，此处无需额外操作
        log.debug("Redis Stream 模式: 消息已由 Lua 脚本写入 stream.orders, orderId={}", order.getId());
    }
}
