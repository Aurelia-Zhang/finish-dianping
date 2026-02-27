package com.hmdp.mq;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.service.IVoucherOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis Stream 模式的消息消费者
 * 从 stream.orders 中消费秒杀订单消息，异步创建订单
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "hmdp.mq.type", havingValue = "redis-stream", matchIfMissing = true)
public class RedisStreamOrderMessageConsumer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private static final String STREAM_KEY = "stream.orders";
    private static final String GROUP_NAME = "g1";
    private static final String CONSUMER_NAME = "c1";

    @PostConstruct
    private void init() {
        // 创建 Stream 消费者组（如果不存在）
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), GROUP_NAME);
        } catch (Exception e) {
            // BUSYGROUP: 消费者组已存在，忽略
            log.debug("消费者组已存在或创建异常: {}", e.getMessage());
        }
        SECKILL_ORDER_EXECUTOR.submit(this::consumeLoop);
    }

    /**
     * 主消费循环：从 Stream 中读取新消息
     */
    private void consumeLoop() {
        while (true) {
            try {
                // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    continue;
                }
                // 3.解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 4.创建订单
                voucherOrderService.createVoucherOrder(voucherOrder);
                // 5.确认消息 XACK（修复原代码中 stream name 写错的 Bug）
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理订单异常", e);
                handlePendingList();
            }
        }
    }

    /**
     * 处理 Pending List 中未确认的消息
     */
    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from(GROUP_NAME, CONSUMER_NAME),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                );
                if (list == null || list.isEmpty()) {
                    break;
                }
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                voucherOrderService.createVoucherOrder(voucherOrder);
                stringRedisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP_NAME, record.getId());
            } catch (Exception e) {
                log.error("处理Pending List订单异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
