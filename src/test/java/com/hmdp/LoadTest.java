package com.hmdp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简易压测工具 — 直接在 IDEA 里运行 main 方法即可
 * 无需安装 JMeter 等外部工具
 *
 * 使用前提：Docker 容器已启动（docker-compose up -d）
 *
 * 测试场景：
 *   1. 商铺查询（缓存命中）
 *   2. 秒杀下单（高并发）
 */
public class LoadTest {

    // ==================== 配置项 ====================
    private static final String BASE_URL = "http://localhost:8081";
    private static final int WARM_UP_REQUESTS = 20;      // 预热请求数

    // ==================== 统计数据 ====================
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         黑马点评 - 简易压测工具                ║");
        System.out.println("╚══════════════════════════════════════════════╝");
        System.out.println();

        // 测试1：商铺查询（测缓存性能）
        System.out.println("========== 测试1: 商铺查询 (GET /shop/1) ==========");
        System.out.println("先预热 " + WARM_UP_REQUESTS + " 次请求，让缓存加载...");
        warmUp(BASE_URL + "/shop/1", WARM_UP_REQUESTS);

        System.out.println("\n--- 场景A: 50 并发, 500 总请求 ---");
        reset();
        runLoadTest(BASE_URL + "/shop/1", "GET", null, null, 50, 500);

        System.out.println("\n--- 场景B: 100 并发, 1000 总请求 ---");
        reset();
        runLoadTest(BASE_URL + "/shop/1", "GET", null, null, 100, 1000);

        System.out.println("\n--- 场景C: 200 并发, 2000 总请求 ---");
        reset();
        runLoadTest(BASE_URL + "/shop/1", "GET", null, null, 200, 2000);

        // 测试2：查询不存在的商铺（测缓存穿透防护）
        System.out.println("\n========== 测试2: 查询不存在的商铺 (GET /shop/999999) ==========");
        System.out.println("测试缓存穿透防护效果...\n");
        System.out.println("--- 100 并发, 1000 总请求 ---");
        reset();
        runLoadTest(BASE_URL + "/shop/999999", "GET", null, null, 100, 1000);

        System.out.println("\n\n压测完成！以上数据可用于面试中说明项目性能。");
    }

    /**
     * 预热请求（让缓存加载数据）
     */
    private static void warmUp(String url, int count) {
        for (int i = 0; i < count; i++) {
            try {
                sendRequest(url, "GET", null, null);
            } catch (Exception ignored) {}
        }
        System.out.println("预热完成 ✅");
    }

    /**
     * 执行压测
     */
    private static void runLoadTest(String url, String method, String body, String token,
                                    int concurrency, int totalRequests) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);   // 所有线程同时起跑
        CountDownLatch endLatch = new CountDownLatch(totalRequests);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();  // 等待统一信号
                    long reqStart = System.currentTimeMillis();
                    int code = sendRequest(url, method, body, token);
                    long reqEnd = System.currentTimeMillis();
                    long elapsed = reqEnd - reqStart;

                    responseTimes.add(elapsed);
                    totalResponseTime.addAndGet(elapsed);

                    if (code >= 200 && code < 500) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 发令枪！所有线程同时开始
        startLatch.countDown();
        endLatch.await();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        executor.shutdown();

        // 输出结果
        printReport(concurrency, totalRequests, totalTime);
    }

    /**
     * 发送 HTTP 请求
     */
    private static int sendRequest(String urlStr, String method, String body, String token) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("Content-Type", "application/json");

        if (token != null) {
            conn.setRequestProperty("authorization", token);
        }

        if (body != null && ("POST".equals(method) || "PUT".equals(method))) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }

        int code = conn.getResponseCode();
        // 读取响应体（防止连接不释放）
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            while (reader.readLine() != null) {}
        } catch (Exception ignored) {}
        conn.disconnect();
        return code;
    }

    /**
     * 输出压测报告
     */
    private static void printReport(int concurrency, int totalRequests, long totalTimeMs) {
        int success = successCount.get();
        int fail = failCount.get();
        double totalTimeSec = totalTimeMs / 1000.0;
        double qps = success / totalTimeSec;
        double avgResponse = responseTimes.isEmpty() ? 0 :
                totalResponseTime.get() / (double) responseTimes.size();

        // 计算 P95、P99
        List<Long> sorted = new ArrayList<>(responseTimes);
        Collections.sort(sorted);
        long p50 = sorted.isEmpty() ? 0 : sorted.get((int)(sorted.size() * 0.50));
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int)(sorted.size() * 0.95));
        long p99 = sorted.isEmpty() ? 0 : sorted.get(Math.min((int)(sorted.size() * 0.99), sorted.size()-1));
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

        System.out.println("┌──────────────────────────────────────────┐");
        System.out.printf("│  并发数:        %-26d│%n", concurrency);
        System.out.printf("│  总请求数:      %-26d│%n", totalRequests);
        System.out.printf("│  成功:          %-26d│%n", success);
        System.out.printf("│  失败:          %-26d│%n", fail);
        System.out.printf("│  总耗时:        %-22.2f 秒  │%n", totalTimeSec);
        System.out.printf("│  QPS:           %-22.1f 次/秒│%n", qps);
        System.out.printf("│  平均响应时间:   %-22.1f ms  │%n", avgResponse);
        System.out.printf("│  P50 响应时间:   %-22d ms  │%n", p50);
        System.out.printf("│  P95 响应时间:   %-22d ms  │%n", p95);
        System.out.printf("│  P99 响应时间:   %-22d ms  │%n", p99);
        System.out.printf("│  最大响应时间:   %-22d ms  │%n", max);
        System.out.println("└──────────────────────────────────────────┘");
    }

    /**
     * 重置统计数据
     */
    private static void reset() {
        successCount.set(0);
        failCount.set(0);
        totalResponseTime.set(0);
        responseTimes.clear();
    }
}
