package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;

/**
 * RabbitMQ 模式的消息消费者
 * 监听 seckill.order.queue 队列，接收 JSON 格式的订单消息
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.mq.type", havingValue = "rabbitmq")
public class RabbitMQOrderMessageConsumer {

    @Resource
    private IVoucherOrderService voucherOrderService;

    @RabbitListener(queues = "seckill.order.queue")
    public void handleOrderMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        try {
            // 1.解析消息
            String json = new String(message.getBody());
            VoucherOrder voucherOrder = JSONUtil.toBean(json, VoucherOrder.class);
            log.debug("RabbitMQ 消费者: 收到订单消息, orderId={}", voucherOrder.getId());

            // 2.创建订单
            voucherOrderService.createVoucherOrder(voucherOrder);

            // 3.手动确认消息
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("RabbitMQ 处理订单异常", e);
            // 拒绝消息并重新入队，后续可改为死信队列处理
            channel.basicNack(deliveryTag, false, true);
        }
    }
}
