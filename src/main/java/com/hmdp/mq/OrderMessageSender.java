package com.hmdp.mq;

import com.hmdp.entity.VoucherOrder;

/**
 * 订单消息发送者接口 — 策略模式抽象
 * 不同实现对应不同的消息队列（Redis Stream / RabbitMQ）
 */
public interface OrderMessageSender {

    /**
     * 发送秒杀订单消息到消息队列
     * @param order 订单对象
     */
    void sendOrder(VoucherOrder order);
}
