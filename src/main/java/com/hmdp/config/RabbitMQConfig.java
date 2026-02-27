package com.hmdp.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置类
 * 仅在 hmdp.mq.type=rabbitmq 时生效
 * 声明秒杀订单交换机、队列和绑定关系
 */
@Configuration
@ConditionalOnProperty(name = "hmdp.mq.type", havingValue = "rabbitmq")
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "seckill.order.exchange";
    public static final String QUEUE_NAME = "seckill.order.queue";
    public static final String ROUTING_KEY = "seckill.order";

    /**
     * 声明 Direct 类型交换机
     */
    @Bean
    public DirectExchange seckillOrderExchange() {
        return new DirectExchange(EXCHANGE_NAME, true, false);
    }

    /**
     * 声明持久化队列
     */
    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(QUEUE_NAME).build();
    }

    /**
     * 将队列绑定到交换机
     */
    @Bean
    public Binding seckillOrderBinding(Queue seckillOrderQueue, DirectExchange seckillOrderExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillOrderExchange).with(ROUTING_KEY);
    }

    /**
     * 消息转换器：使用 Jackson JSON 序列化
     */
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
