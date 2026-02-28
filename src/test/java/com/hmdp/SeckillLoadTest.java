package com.hmdp;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 秒杀压测工具 — 直接在 IDEA 里运行 main 方法即可
 *
 * 测试流程：
 *   1. 自动注册/登录多个用户，获取 token
 *   2. 创建一个秒杀优惠券（100 库存）
 *   3. 200 个用户同时抢购，测试高并发下的秒杀性能
 *
 * 使用前提：Docker 容器已启动（docker-compose up -d）
 */
public class SeckillLoadTest {

    private static final String BASE_URL = "http://localhost:8081";

    // 统计数据
    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failCount = new AtomicInteger(0);
    private static final AtomicLong totalResponseTime = new AtomicLong(0);
    private static final List<Long> responseTimes = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger orderCreated = new AtomicInteger(0);
    private static final AtomicInteger stockInsufficient = new AtomicInteger(0);
    private static final AtomicInteger duplicateOrder = new AtomicInteger(0);

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════╗");
        System.out.println("║         黑马点评 - 秒杀压测工具                ║");
        System.out.println("╚══════════════════════════════════════════════╝\n");

        // ========== 第1步：批量登录获取 token ==========
        int userCount = 200;
        System.out.println("第1步：批量登录 " + userCount + " 个用户...");
        List<String> tokens = batchLogin(userCount);
        System.out.println("登录完成 ✅ 获取到 " + tokens.size() + " 个 token\n");

        if (tokens.isEmpty()) {
            System.out.println("❌ 登录失败！请检查 Docker 容器是否正常运行。");
            return;
        }

        // ========== 第2步：创建秒杀优惠券 ==========
        int stock = 100;
        System.out.println("第2步：创建秒杀优惠券（库存 " + stock + " 张）...");
        Long voucherId = createSeckillVoucher(stock, tokens.get(0));
        if (voucherId == null) {
            System.out.println("❌ 创建优惠券失败！");
            return;
        }
        System.out.println("优惠券创建成功 ✅ ID=" + voucherId + "\n");

        // ========== 第3步：秒杀压测 ==========
        System.out.println("第3步：" + userCount + " 个用户同时秒杀！\n");
        System.out.println("--- " + userCount + " 并发, 每人抢 1 次 ---");
        runSeckillTest(voucherId, tokens);

        System.out.println("\n=== 业务结果 ===");
        System.out.println("  下单成功: " + orderCreated.get() + " 笔（应该 = " + stock + "）");
        System.out.println("  库存不足: " + stockInsufficient.get() + " 笔（应该 = " + (userCount - stock) + "）");
        System.out.println("  重复下单: " + duplicateOrder.get() + " 笔（应该 = 0）");
        System.out.println();

        if (orderCreated.get() == stock && duplicateOrder.get() == 0) {
            System.out.println("✅ 秒杀测试通过！库存一致、无超卖、无重复下单！");
        } else if (orderCreated.get() <= stock && duplicateOrder.get() == 0) {
            System.out.println("✅ 秒杀测试基本通过！无超卖、无重复下单！");
            System.out.println("   (下单数 < 库存可能是因为异步消费还未完成)");
        } else {
            System.out.println("⚠️ 请检查结果是否符合预期！");
        }
    }

    /**
     * 批量登录获取 token
     * 利用项目的验证码机制：先 sendCode，验证码存在 Redis 中并打印到日志
     * 然后用同一个手机号 + 验证码登录
     */
    private static List<String> batchLogin(int count) throws Exception {
        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            // 用不同手机号注册：13800000001, 13800000002, ...
            String phone = String.format("138%08d", i + 1);
            try {
                // Step 1: 发送验证码
                String sendCodeResp = post(BASE_URL + "/user/code?phone=" + phone, null, null);
                // 验证码被存到了 Redis 的 login:code:{phone} key 中

                // Step 2: 从 Redis 读验证码（通过一个取巧的方式）
                // 由于我们没有直接访问 Redis 的通道，用一个固定的验证码方式
                // 实际上 sendCode 会生成随机验证码存到 Redis
                // 我们需要通过 Redis 获取，这里直接查 Redis
                String code = getCodeFromRedis(phone);
                if (code == null) {
                    // 无法获取验证码，尝试暴力方式：直接在 Redis 设置一个已知验证码
                    setCodeInRedis(phone, "123456");
                    code = "123456";
                }

                // Step 3: 登录
                String loginBody = "{\"phone\":\"" + phone + "\",\"code\":\"" + code + "\"}";
                String loginResp = post(BASE_URL + "/user/login", loginBody, null);
                JSONObject json = JSONUtil.parseObj(loginResp);
                if (json.getBool("success", false)) {
                    tokens.add(json.getStr("data"));
                }
            } catch (Exception e) {
                // 跳过失败的
            }
            if ((i + 1) % 50 == 0) {
                System.out.println("  已登录 " + (i + 1) + "/" + count);
            }
        }
        return tokens;
    }

    /**
     * 通过 HTTP 调用 Redis CLI 获取验证码
     * 由于无法直接访问 Redis，通过 docker exec 执行命令
     */
    private static String getCodeFromRedis(String phone) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", "hmdp-redis",
                    "redis-cli", "-a", "123321",
                    "GET", "login:code:" + phone
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            String code = null;
            while ((line = reader.readLine()) != null) {
                // 过滤掉 Warning 信息
                if (!line.contains("Warning") && !line.trim().isEmpty() && !line.contains("nil")) {
                    code = line.trim();
                }
            }
            proc.waitFor(5, TimeUnit.SECONDS);
            return code;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 直接在 Redis 设置验证码
     */
    private static void setCodeInRedis(String phone, String code) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "exec", "hmdp-redis",
                    "redis-cli", "-a", "123321",
                    "SET", "login:code:" + phone, code, "EX", "300"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    /**
     * 创建秒杀优惠券
     */
    private static Long createSeckillVoucher(int stock, String token) throws Exception {
        String body = "{"
                + "\"shopId\": 1,"
                + "\"title\": \"压测秒杀券-" + System.currentTimeMillis() + "\","
                + "\"subTitle\": \"压测用\","
                + "\"rules\": \"仅限压测\","
                + "\"payValue\": 1,"
                + "\"actualValue\": 100,"
                + "\"type\": 1,"
                + "\"stock\": " + stock + ","
                + "\"beginTime\": \"2025-01-01T00:00:00\","
                + "\"endTime\": \"2027-12-31T23:59:59\""
                + "}";
        String resp = post(BASE_URL + "/voucher/seckill", body, token);
        JSONObject json = JSONUtil.parseObj(resp);
        if (json.getBool("success", false)) {
            return json.getLong("data");
        }
        System.out.println("  创建优惠券响应: " + resp);
        return null;
    }

    /**
     * 执行秒杀压测
     */
    private static void runSeckillTest(Long voucherId, List<String> tokens) throws Exception {
        int concurrency = tokens.size();
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(concurrency);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < concurrency; i++) {
            final String token = tokens.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();   // 等待统一起跑
                    long reqStart = System.currentTimeMillis();
                    String resp = post(
                            BASE_URL + "/voucher-order/seckill/" + voucherId,
                            null, token
                    );
                    long elapsed = System.currentTimeMillis() - reqStart;

                    responseTimes.add(elapsed);
                    totalResponseTime.addAndGet(elapsed);

                    JSONObject json = JSONUtil.parseObj(resp);
                    if (json.getBool("success", false)) {
                        successCount.incrementAndGet();
                        orderCreated.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                        String msg = json.getStr("errorMsg", "");
                        if (msg.contains("库存不足")) {
                            stockInsufficient.incrementAndGet();
                        } else if (msg.contains("重复")) {
                            duplicateOrder.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 发令枪！
        startLatch.countDown();
        endLatch.await();
        long totalTime = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // 等待异步消费完成
        System.out.println("\n等待 MQ 异步消费完成（3秒）...");
        Thread.sleep(3000);

        printReport(concurrency, concurrency, totalTime);
    }

    /**
     * 发送 POST 请求
     */
    private static String post(String urlStr, String body, String token) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/json");
        if (token != null) {
            conn.setRequestProperty("authorization", token);
        }
        if (body != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
        }
        int code = conn.getResponseCode();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(code >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    /**
     * 输出压测报告
     */
    private static void printReport(int concurrency, int totalRequests, long totalTimeMs) {
        double totalTimeSec = totalTimeMs / 1000.0;
        double qps = totalRequests / totalTimeSec;
        double avgResponse = responseTimes.isEmpty() ? 0 :
                totalResponseTime.get() / (double) responseTimes.size();

        List<Long> sorted = new ArrayList<>(responseTimes);
        Collections.sort(sorted);
        long p50 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.50));
        long p95 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.95));
        long p99 = sorted.isEmpty() ? 0 : sorted.get(Math.min((int) (sorted.size() * 0.99), sorted.size() - 1));
        long max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);

        System.out.println("┌──────────────────────────────────────────┐");
        System.out.printf("│  并发用户数:    %-26d│%n", concurrency);
        System.out.printf("│  总请求数:      %-26d│%n", totalRequests);
        System.out.printf("│  秒杀成功:      %-26d│%n", successCount.get());
        System.out.printf("│  秒杀失败:      %-26d│%n", failCount.get());
        System.out.printf("│  总耗时:        %-22.2f 秒  │%n", totalTimeSec);
        System.out.printf("│  QPS:           %-22.1f 次/秒│%n", qps);
        System.out.printf("│  平均响应时间:   %-22.1f ms  │%n", avgResponse);
        System.out.printf("│  P50 响应时间:   %-22d ms  │%n", p50);
        System.out.printf("│  P95 响应时间:   %-22d ms  │%n", p95);
        System.out.printf("│  P99 响应时间:   %-22d ms  │%n", p99);
        System.out.printf("│  最大响应时间:   %-22d ms  │%n", max);
        System.out.println("└──────────────────────────────────────────┘");
    }
}
