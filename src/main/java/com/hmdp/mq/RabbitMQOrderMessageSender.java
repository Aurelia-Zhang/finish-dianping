package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * RabbitMQ 模式的消息发送者
 * 将订单信息以 JSON 格式发送到 RabbitMQ 交换机
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.mq.type", havingValue = "rabbitmq")
public class RabbitMQOrderMessageSender implements OrderMessageSender {

    @Resource
    private RabbitTemplate rabbitTemplate;

    public static final String EXCHANGE_NAME = "seckill.order.exchange";
    public static final String ROUTING_KEY = "seckill.order";

    @Override
    public void sendOrder(VoucherOrder order) {
        String json = JSONUtil.toJsonStr(order);
        rabbitTemplate.convertAndSend(EXCHANGE_NAME, ROUTING_KEY, json);
        log.debug("RabbitMQ 模式: 订单消息已发送, orderId={}", order.getId());
    }
}
