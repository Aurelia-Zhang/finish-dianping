package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.List;

/**
 * 布隆过滤器配置类
 * 仅在 hmdp.cache.anti-penetration=bloom-filter 时生效
 *
 * 布隆过滤器原理：
 * - 使用多个哈希函数将元素映射到位数组中
 * - 查询时，如果所有哈希位都为1，则"可能存在"；有任何一位为0，则"一定不存在"
 * - 存在误判率（false positive），但不会漏判（no false negative）
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "hmdp.cache.anti-penetration", havingValue = "bloom-filter")
public class BloomFilterConfig {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private IShopService shopService;

    /**
     * 创建商铺布隆过滤器 Bean
     * - 预期插入量：10000
     * - 误判率：0.01（1%）
     */
    @Bean
    public RBloomFilter<Long> shopBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("bloom:shop");
        // tryInit：如果已经初始化过则不会重复初始化
        bloomFilter.tryInit(10000L, 0.01);
        return bloomFilter;
    }

    /**
     * 应用启动时，将所有商铺 ID 预加载到布隆过滤器中
     */
    @PostConstruct
    public void initBloomFilter() {
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("bloom:shop");
        bloomFilter.tryInit(10000L, 0.01);

        List<Shop> shops = shopService.list();
        for (Shop shop : shops) {
            bloomFilter.add(shop.getId());
        }
        log.info("布隆过滤器初始化完成，已加载 {} 个商铺ID", shops.size());
    }
}
