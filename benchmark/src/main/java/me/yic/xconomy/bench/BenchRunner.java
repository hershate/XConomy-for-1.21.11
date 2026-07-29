package me.yic.xconomy.bench;

import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.adapter.comp.CChat;
import me.yic.xconomy.command.core.BenchCommandCoreAccess;
import me.yic.xconomy.data.DataFormat;
import me.yic.xconomy.data.caches.Cache;
import me.yic.xconomy.data.syncdata.PlayerData;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 热路径微基准主入口。
 *
 * 在同一 JVM、相同输入、相同预热下，对每个热路径同时测量：
 *   - baseline  ：原始算法（BaselineDataFormat / BaselineIsDouble / containsKey+get 读模式）
 *   - optimized ：优化后 Core 实现（DataFormat / CommandCore / 单次 get 读模式）
 * 并先做行为等价自检，确保优化不改变结果。
 *
 * 运行：mvn -pl benchmark exec:java
 */
public final class BenchRunner {

    // 基准规模（足够大以压过计时噪声与 JIT 抖动）
    private static final long WARMUP = 300_000L;
    private static final int ROUNDS = 15;
    private static final long ITERS = 1_000_000L;

    // ---- 简单确定性 LCG，避免 Math.random 开销与不可复现 ----
    private static long rng = 0x2545F4914F6CDD1DL;

    private static long nextRand() {
        rng = rng * 6364136223846793005L + 1442695040888963407L;
        return rng;
    }

    private static double nextDouble() {
        return (nextRand() >>> 11) * (1.0 / (1L << 53));
    }

    public static void main(String[] args) throws Exception {
        BenchEnv.setup();

        System.out.println("=== XConomy 热路径微基准 ===");
        System.out.println("JVM       : " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("integer-bal=" + DataFormat.isint
                + ", format-balance=" + (XConomyLoad.Config.FORMAT_BALANCE != null)
                + ", display-format=\"" + XConomyLoad.Config.DISPLAY_FORMAT + "\"");
        System.out.println("规模      : warmup=" + WARMUP + ", rounds=" + ROUNDS + ", iters/round=" + ITERS);
        System.out.println("sanity    : shown(1)=<" + DataFormat.shown(BigDecimal.ONE)
                + "> | shown(1500000)=<" + DataFormat.shown(new BigDecimal("1500000")) + ">");
        System.out.println();

        // ---------- 1. 行为等价自检 ----------
        boolean okShown = checkShownEquivalence();
        boolean okIsDouble = checkIsDoubleEquivalence();
        System.out.println("正确性自检: shown=" + (okShown ? "PASS" : "FAIL")
                + ", isDouble=" + (okIsDouble ? "PASS" : "FAIL"));
        if (!(okShown && okIsDouble)) {
            System.out.println("!! 行为不等价，中止基准（优化有误）");
            return;
        }
        System.out.println();

        // ---------- 2. 准备输入 ----------
        final int N = 4096;
        BigDecimal[] balances = new BigDecimal[N];
        for (int i = 0; i < N; i++) {
            double mag = Math.pow(10, nextDouble() * 9); // 1 ~ 1e9
            double v = Math.round(nextDouble() * mag * 100.0) / 100.0;
            balances[i] = BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.DOWN);
        }
        // 确保 singular 分支（==1）也被覆盖
        balances[0] = BigDecimal.ONE;

        // 偏斜访问序列：80% 命中前 64 个余额（模拟记分板/Tab 对相同玩家余额的重复刷新）
        final int HOT = 64;
        final int L = 131072;
        int[] skewedIdx = new int[L];
        for (int i = 0; i < L; i++) {
            double r = nextDouble();
            if (r < 0.8) {
                skewedIdx[i] = (int) ((r / 0.8) * HOT);
            } else {
                skewedIdx[i] = (int) (nextDouble() * N);
            }
            if (skewedIdx[i] >= N) skewedIdx[i] = N - 1;
        }

        // isDouble 输入：合法/非法混合
        String[] idInputs = {
                "100", "0", "1234.5", "999999.99", "50", "3.14", "1000000", "0.5", "88",
                "1.234", "-5", "abc", "NaN", "Infinity", "1e3", "12.34.56", "0x1", "",
                "12345678901234567890", "99999999999999999999", "1.", ".5", "00100", " 10"
        };

        // ---------- 3. 基准 ----------
        List<Stats.Result[]> rows = new ArrayList<>();

        // (A) shown —— 均匀（缓存基本不命中，诚实地展示格式化本身的成本）
        rows.add(new Stats.Result[]{
                Stats.result("baseline", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(BaselineDataFormat.shown(balances[i % N]))), ITERS),
                Stats.result("optimized", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(DataFormat.shown(balances[i % N]))), ITERS)
        });
        printRow("shown 均匀(N=" + N + " 去重, 轮询, 缓存基本不命中)", rows.get(rows.size() - 1));

        // (B) shown —— 偏斜（缓存命中占主导，反映记分板/Tab 的真实重复访问）
        rows.add(new Stats.Result[]{
                Stats.result("baseline", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(BaselineDataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N]))), ITERS),
                Stats.result("optimized", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(DataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N]))), ITERS)
        });
        printRow("shown 偏斜(80%命中前64项, 记分板/Tab 真实模式)", rows.get(rows.size() - 1));

        // (B2) shown —— 高基数：N 远大于缓存容量，缓存基本不命中（不现实的极端情形，
        //      仅用于诚实展示优化在“几乎全 miss”时的表现，验证缓存不会带来显著回退）
        final int NBIG = 100000;
        BigDecimal[] balancesBig = new BigDecimal[NBIG];
        for (int i = 0; i < NBIG; i++) {
            double v = Math.round(nextDouble() * 1e9 * 100.0) / 100.0;
            balancesBig[i] = BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.DOWN);
        }
        rows.add(new Stats.Result[]{
                Stats.result("baseline", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(BaselineDataFormat.shown(balancesBig[i % NBIG]))), ITERS),
                Stats.result("optimized", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(DataFormat.shown(balancesBig[i % NBIG]))), ITERS)
        });
        printRow("shown 高基数(N=" + NBIG + ">缓存容量, 几乎全miss, 极端最差情形)", rows.get(rows.size() - 1));

        // (C) isDouble —— 合法+非法混合输入
        final int idn = idInputs.length;
        rows.add(new Stats.Result[]{
                Stats.result("baseline", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(BaselineIsDouble.isDouble(idInputs[i % idn]))), ITERS),
                Stats.result("optimized", Stats.measure(WARMUP, ROUNDS, ITERS,
                        (i, bh) -> bh.consume(BenchCommandCoreAccess.isDouble(idInputs[i % idn]))), ITERS)
        });
        printRow("isDouble 混合输入(合法+非法, " + idn + " 条)", rows.get(rows.size() - 1));

        // (D) 缓存读路径 —— containsKey+get(原始) vs 单次 get(优化)
        prepareCache(N);
        UUID[] cacheKeys = new UUID[N];
        int idx = 0;
        for (UUID k : Cache.pds.keySet()) {
            cacheKeys[idx++] = k;
        }
        final int Nc = cacheKeys.length;
        rows.add(new Stats.Result[]{
                Stats.result("baseline", Stats.measure(WARMUP, ROUNDS, ITERS, (i, bh) -> {
                    UUID u = cacheKeys[i % Nc];
                    boolean hit = Cache.CacheContainsKey(u);
                    if (hit) bh.consume(Cache.getDataFromCache(u));
                    else bh.consume(false);
                }), ITERS),
                Stats.result("optimized", Stats.measure(WARMUP, ROUNDS, ITERS, (i, bh) -> {
                    UUID u = cacheKeys[i % Nc];
                    Object pd = Cache.getDataFromCache(u);
                    bh.consume(pd != null);
                }), ITERS)
        });
        printRow("缓存读路径 (containsKey+get -> 单次get, N=" + Nc + ")", rows.get(rows.size() - 1));

        System.out.println();
        System.out.println("说明: baseline=原始算法拷贝; optimized=优化后 Core 实现。"
                + "speedup=optimized 中位吞吐 / baseline 中位吞吐。");
    }

    private static void prepareCache(int n) {
        Cache.pds.clear();
        for (int i = 0; i < n; i++) {
            UUID u = new UUID(nextRand(), nextRand());
            BigDecimal bal = BigDecimal.valueOf(Math.round(nextDouble() * 1e6 * 100.0) / 100.0)
                    .setScale(2, java.math.RoundingMode.DOWN);
            Cache.pds.put(u, new PlayerData(u, "player" + i, bal));
        }
    }

    private static void printRow(String title, Stats.Result[] pair) {
        Stats.Result b = pair[0];
        Stats.Result o = pair[1];
        System.out.println();
        System.out.println("[" + title + "]");
        System.out.printf("  baseline  : %,12.0f ops/s  (%6.1f ns/op) [min=%,.0f max=%,.0f]%n",
                b.throughputMedian, b.nsPerOpMedian, b.throughputMin, b.throughputMax);
        System.out.printf("  optimized : %,12.0f ops/s  (%6.1f ns/op) [min=%,.0f max=%,.0f]%n",
                o.throughputMedian, o.nsPerOpMedian, o.throughputMin, o.throughputMax);
        System.out.printf("  speedup   : %s%n", o.speedupOver(b));
    }

    // ---------- 行为等价自检 ----------
    private static boolean checkShownEquivalence() {
        BigDecimal[] probes = {
                BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.TEN,
                new BigDecimal("1234.56"), new BigDecimal("9999999.99"),
                new BigDecimal("1500000"), new BigDecimal("0.5"),
                new BigDecimal("1000000000"), new BigDecimal("123456789.12")
        };
        for (BigDecimal bd : probes) {
            String base = BaselineDataFormat.shown(bd);
            // 多次调用 optimized 以触发缓存命中路径，结果必须一致
            String opt1 = DataFormat.shown(bd);
            String opt2 = DataFormat.shown(bd);
            if (!eq(base, opt1) || !eq(base, opt2)) {
                System.out.println("  shown 不等价: bd=" + bd + " base=<" + base + "> opt=<" + opt1 + ">");
                return false;
            }
        }
        return true;
    }

    private static boolean checkIsDoubleEquivalence() {
        String[] probes = {
                "0", "1", "100", "1234.5", "999999.99", "1.234", "-5", "-0.5",
                "abc", "NaN", "Infinity", "1e3", "12.34.56", "", "0x1",
                "123456789012345678901", "99999999999999999999", "1.", ".5", "00100"
        };
        for (String s : probes) {
            boolean base = BaselineIsDouble.isDouble(s);
            boolean opt = BenchCommandCoreAccess.isDouble(s);
            if (base != opt) {
                System.out.println("  isDouble 不等价: s=\"" + s + "\" base=" + base + " opt=" + opt);
                return false;
            }
        }
        return true;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
