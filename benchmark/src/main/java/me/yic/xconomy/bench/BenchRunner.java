package me.yic.xconomy.bench;

import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.command.core.BenchCommandCoreAccess;
import me.yic.xconomy.data.DataFormat;
import me.yic.xconomy.data.caches.Cache;
import me.yic.xconomy.data.syncdata.PlayerData;
import me.yic.xconomy.utils.UUIDMode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 全面热路径基准 + 行为等价正确性套件。
 *
 * 覆盖维度：
 *  - DataFormat：shown / PEshownf（含缓存优化对照），formatdouble/formatString/formatBigDecimal/isMAX（未优化对照）
 *  - 配置变体：小数模式 / 整数模式(integer-bal)；默认显示格式 / 富格式(含 %format_balance%)
 *  - 访问分布：均匀 / 偏斜(命中热集) / 高基数(几乎全 miss)
 *  - CommandCore.isDouble：合法集合 / 非法集合 / 整数模式 / 边界
 *  - 缓存读路径：DEFAULT UUID命中/未命中、SEMIONLINE 命中、String键(按名)、USERNAME_IGNORE_CASE
 *  - 多线程并发 shown：暴露展示缓存的并发竞争
 *
 * 运行：mvn -pl benchmark -am compile ；见 note/report/perf 报告 §6 的运行方式。
 */
public final class BenchRunner {

    private static final long WARMUP = 300_000L;
    private static final int ROUNDS = 12;
    private static final long ITERS = 1_000_000L;

    private static final int CONC_THREADS = 8;
    private static final long CONC_ITERS = 300_000L;

    private static long rng = 0x2545F4914F6CDD1DL;
    private static long nextRand() { rng = rng * 6364136223846793005L + 1442695040888963407L; return rng; }
    private static double nextDouble() { return (nextRand() >>> 11) * (1.0 / (1L << 53)); }

    // 输入池
    private static final int N = 4096;
    private static final int NBIG = 100000;
    private static final int HOT = 64;
    private static final int L = 131072;
    private static BigDecimal[] balances;
    private static BigDecimal[] balancesBig;
    private static int[] skewedIdx;

    public static void main(String[] args) throws Exception {
        BenchEnv.setup();
        prepareInputs();

        System.out.println("=== XConomy 热路径全面基准 ===");
        System.out.println("JVM       : " + System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
        System.out.println("processors: " + Runtime.getRuntime().availableProcessors());
        System.out.println("规模      : warmup=" + WARMUP + ", rounds=" + ROUNDS + ", iters/round=" + ITERS
                + " | 并发=" + CONC_THREADS + "x" + CONC_ITERS);
        System.out.println();

        // ---------- 0. 正确性套件（全维度） ----------
        boolean ok = runAllCorrectness();
        System.out.println();
        System.out.println("正确性总检: " + (ok ? "ALL PASS" : "FAIL"));
        if (!ok) {
            System.out.println("!! 存在行为不等价或边界错误，中止基准");
            return;
        }
        System.out.println();

        // ---------- 1. DataFormat.shown ----------
        System.out.println("########## DataFormat.shown（余额展示） ##########");
        modeDecimalDefault();

        benchPair("shown 小数-默认格式-均匀(N=" + N + ",全命中)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.shown(balances[i % N])),
                (i, bh) -> bh.consume(DataFormat.shown(balances[i % N])));
        benchPair("shown 小数-默认格式-偏斜(80%命中热集,真实)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N])),
                (i, bh) -> bh.consume(DataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N])));
        benchPair("shown 小数-默认格式-高基数(N=" + NBIG + ",几乎全miss)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.shown(balancesBig[i % NBIG])),
                (i, bh) -> bh.consume(DataFormat.shown(balancesBig[i % NBIG])));

        // 注：不通过反射切换 display-format（final static 会被 JIT 常量折叠，切换不可靠）。
        // getformatbalance 在 shown 中无条件调用，其成本已被默认格式覆盖。
        modeInteger();
        benchPair("shown 整数模式(integer-bal)-偏斜", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N])),
                (i, bh) -> bh.consume(DataFormat.shown(balances[skewedIdx[i & 0x1ffff] % N])));

        // ---------- 2. DataFormat.PEshownf ----------
        modeDecimalDefault();
        System.out.println();
        System.out.println("########## DataFormat.PEshownf（PlaceholderAPI 格式化余额） ##########");
        benchPair("PEshownf 小数-偏斜", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.PEshownf(balances[skewedIdx[i & 0x1ffff] % N])),
                (i, bh) -> bh.consume(DataFormat.PEshownf(balances[skewedIdx[i & 0x1ffff] % N])));

        // ---------- 3. 未优化方法对照（应≈1.0x，验证基准可信且无回退） ----------
        System.out.println();
        System.out.println("########## 未优化方法对照（control，应≈1.0x） ##########");
        benchPair("formatdouble(double)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.formatdouble((i % 100000) / 100.0)),
                (i, bh) -> bh.consume(DataFormat.formatdouble((i % 100000) / 100.0)));
        benchPair("formatString(String)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.formatString(Integer.toString(i % 100000))),
                (i, bh) -> bh.consume(DataFormat.formatString(Integer.toString(i % 100000))));
        benchPair("formatBigDecimal(BigDecimal)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.formatBigDecimal(balances[i % N])),
                (i, bh) -> bh.consume(DataFormat.formatBigDecimal(balances[i % N])));
        benchPair("isMAX(BigDecimal)", ITERS,
                (i, bh) -> bh.consume(BaselineDataFormat.isMAX(balances[i % N])),
                (i, bh) -> bh.consume(DataFormat.isMAX(balances[i % N])));

        // ---------- 4. CommandCore.isDouble ----------
        System.out.println();
        System.out.println("########## CommandCore.isDouble（金额校验） ##########");
        benchIsDoubleByCategory();

        // ---------- 5. 缓存读路径 ----------
        System.out.println();
        System.out.println("########## 缓存读路径（getPlayerDatai/getAccountBalance） ##########");
        benchCacheRead();

        // ---------- 6. 多线程并发 shown ----------
        modeDecimalDefault();
        System.out.println();
        System.out.println("########## 多线程并发 shown（" + CONC_THREADS + " 线程，缓存竞争） ##########");
        benchConcurrentShown();

        System.out.println();
        System.out.println("说明: baseline=原始算法拷贝; optimized=优化后 Core; speedup=optimized/baseline（多线程为聚合吞吐比）。");
    }

    // ================= 基准原语 =================

    private static void benchPair(String title, long iters, Stats.Op baseline, Stats.Op optimized) {
        double[] b = Stats.measure(WARMUP, ROUNDS, iters, baseline);
        double[] o = Stats.measure(WARMUP, ROUNDS, iters, optimized);
        printPair(title, b, o, iters);
    }

    private static void printPair(String title, double[] b, double[] o, long iters) {
        Stats.Result br = Stats.result("baseline", b, iters);
        Stats.Result or = Stats.result("optimized", o, iters);
        System.out.println("[" + title + "]");
        System.out.printf("  baseline  : %,14.0f ops/s (%6.1f ns/op)%n", br.throughputMedian, br.nsPerOpMedian);
        System.out.printf("  optimized : %,14.0f ops/s (%6.1f ns/op)%n", or.throughputMedian, or.nsPerOpMedian);
        System.out.printf("  speedup   : %s%n", or.speedupOver(br));
    }

    // ================= isDouble 分类基准 =================

    private static void benchIsDoubleByCategory() throws Exception {
        String[] valid = {"100", "0", "1", "1234.5", "999999.99", "50", "3.14", "1000000", "0.5", "88", "0.25", "9999"};
        String[] invalid = {"abc", "NaN", "Infinity", "1e3", "12.34.56", "0x1", "1.234", "-5", "-0.5",
                "123456789012345678901", "99999999999999999999", ""};
        modeDecimalDefault();
        benchPair("isDouble 小数模式-合法输入", ITERS,
                (i, bh) -> bh.consume(BaselineIsDouble.isDouble(valid[i % valid.length])),
                (i, bh) -> bh.consume(BenchCommandCoreAccess.isDouble(valid[i % valid.length])));
        benchPair("isDouble 小数模式-非法输入", ITERS,
                (i, bh) -> bh.consume(BaselineIsDouble.isDouble(invalid[i % invalid.length])),
                (i, bh) -> bh.consume(BenchCommandCoreAccess.isDouble(invalid[i % invalid.length])));

        modeInteger();
        // 整数模式下 isDouble 走 Integer.parseInt 分支
        String[] intValid = {"100", "0", "1", "999999", "50", "1000000", "88", "12345"};
        benchPair("isDouble 整数模式-合法整数", ITERS,
                (i, bh) -> bh.consume(BaselineIsDouble.isDouble(intValid[i % intValid.length])),
                (i, bh) -> bh.consume(BenchCommandCoreAccess.isDouble(intValid[i % intValid.length])));
        modeDecimalDefault();
    }

    // ================= 缓存读路径 =================

    private static void benchCacheRead() {
        // DEFAULT UUID 命中
        UUID[] keys = prepareCacheDefault(N);
        final int Nc = keys.length;
        benchPair("缓存读 DEFAULT UUID命中(N=" + Nc + ")", ITERS,
                (i, bh) -> { UUID u = keys[i % Nc]; if (Cache.CacheContainsKey(u)) bh.consume(Cache.getDataFromCache(u)); else bh.consume(false); },
                (i, bh) -> { Object pd = Cache.getDataFromCache(keys[i % Nc]); bh.consume(pd != null); });
        // DEFAULT UUID 未命中
        UUID[] missKeys = new UUID[Nc];
        for (int i = 0; i < Nc; i++) missKeys[i] = new UUID(nextRand() | 1L, nextRand()); // 确保基本不在缓存
        benchPair("缓存读 DEFAULT UUID未命中", ITERS,
                (i, bh) -> { UUID u = missKeys[i % Nc]; if (Cache.CacheContainsKey(u)) bh.consume(Cache.getDataFromCache(u)); else bh.consume(false); },
                (i, bh) -> { Object pd = Cache.getDataFromCache(missKeys[i % Nc]); bh.consume(pd != null); });

        // SEMIONLINE 命中（额外 m_uuids 跳转）
        UUID[][] semi = prepareCacheSemiOnline(N);
        final UUID[] localKeys = semi[0];
        final int Ns = localKeys.length;
        benchPair("缓存读 SEMIONLINE 命中(经 m_uuids)", ITERS,
                (i, bh) -> { UUID u = localKeys[i % Ns]; if (Cache.CacheContainsKey(u)) bh.consume(Cache.getDataFromCache(u)); else bh.consume(false); },
                (i, bh) -> { Object pd = Cache.getDataFromCache(localKeys[i % Ns]); bh.consume(pd != null); });

        // String 键（按名）命中
        String[][] named = prepareCacheByName(N);
        final String[] names = named[0];
        final int Nn = names.length;
        benchPair("缓存读 String键(按玩家名)", ITERS,
                (i, bh) -> { String k = names[i % Nn]; if (Cache.CacheContainsKey(k)) bh.consume(Cache.getDataFromCache(k)); else bh.consume(false); },
                (i, bh) -> { Object pd = Cache.getDataFromCache(names[i % Nn]); bh.consume(pd != null); });

        // USERNAME_IGNORE_CASE
        XConomyLoad.Config.USERNAME_IGNORE_CASE = true;
        final String[] namesCi = named[1];
        benchPair("缓存读 String键(忽略大小写,大写查询)", ITERS,
                (i, bh) -> { String k = namesCi[i % Nn]; if (Cache.CacheContainsKey(k)) bh.consume(Cache.getDataFromCache(k)); else bh.consume(false); },
                (i, bh) -> { Object pd = Cache.getDataFromCache(namesCi[i % Nn]); bh.consume(pd != null); });
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;

        // 复位 DEFAULT
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        Cache.clearCache();
    }

    // ================= 多线程并发 shown =================

    private static void benchConcurrentShown() {
        final int mask = 0x1ffff;
        ConcurrentStats.Op baseOp = (idx, bh) -> bh.consume(BaselineDataFormat.shown(balances[skewedIdx[idx & mask] % N]));
        ConcurrentStats.Op optOp = (idx, bh) -> bh.consume(DataFormat.shown(balances[skewedIdx[idx & mask] % N]));

        double[] b = ConcurrentStats.measure(CONC_THREADS, WARMUP, ROUNDS, CONC_ITERS, baseOp);
        double[] o = ConcurrentStats.measure(CONC_THREADS, WARMUP, ROUNDS, CONC_ITERS, optOp);
        ConcurrentStats.Result br = ConcurrentStats.result("baseline", b, CONC_THREADS, CONC_ITERS);
        ConcurrentStats.Result or = ConcurrentStats.result("optimized", o, CONC_THREADS, CONC_ITERS);
        System.out.println("[多线程并发 shown, T=" + CONC_THREADS + ", 偏斜(高命中)]");
        System.out.printf("  baseline  : %,14.0f ops/s 聚合%n", br.aggregateOpsPerSec);
        System.out.printf("  optimized : %,14.0f ops/s 聚合%n", or.aggregateOpsPerSec);
        System.out.printf("  speedup   : %s%n", or.speedupOver(br));
    }

    // ================= 正确性套件 =================

    private static boolean runAllCorrectness() throws Exception {
        boolean ok = true;
        ok &= checkShownEquivalenceDecimal();
        modeInteger();
        ok &= checkShownEquivalenceInteger();
        modeDecimalDefault();
        ok &= checkFormatEquivalence();
        ok &= checkIsDoubleEquivalence();
        ok &= checkCacheReadEquivalence();
        modeDecimalDefault();
        return ok;
    }

    private static boolean checkShownEquivalenceDecimal() {
        BigDecimal[] probes = balancesProbeSet();
        for (BigDecimal bd : probes) {
            String base = BaselineDataFormat.shown(bd);
            String opt1 = DataFormat.shown(bd);
            String opt2 = DataFormat.shown(bd); // 缓存命中路径
            if (!eq(base, opt1) || !eq(base, opt2)) {
                System.out.println("  shown(小数) 不等价: " + bd + " base=<" + base + "> opt=<" + opt1 + ">");
                return false;
            }
        }
        System.out.println("  shown 小数模式等价: PASS (" + probes.length + " 探针)");
        return true;
    }

    private static boolean checkShownEquivalenceInteger() {
        BigDecimal[] probes = balancesProbeSet();
        for (BigDecimal bd : probes) {
            String base = BaselineDataFormat.shown(bd);
            String opt = DataFormat.shown(bd);
            if (!eq(base, opt)) {
                System.out.println("  shown(整数) 不等价: " + bd + " base=<" + base + "> opt=<" + opt + ">");
                return false;
            }
        }
        System.out.println("  shown 整数模式等价: PASS (" + probes.length + " 探针)");
        return true;
    }

    private static boolean checkFormatEquivalence() {
        double[] dprobes = {0.0, 0.005, 0.015, 1.0, 1.005, 2.5, 1234.567, -0.0, 9999999.999};
        for (double d : dprobes) {
            BigDecimal b = BaselineDataFormat.formatdouble(d);
            BigDecimal o = DataFormat.formatdouble(d);
            if (b.compareTo(o) != 0) {
                System.out.println("  formatdouble 不等价: " + d + " base=" + b + " opt=" + o);
                return false;
            }
        }
        String[] sprobes = {"0", "0.005", "1.005", "1234.5", "9999999.999", "10000000000000000"};
        for (String s : sprobes) {
            BigDecimal b = BaselineDataFormat.formatString(s);
            BigDecimal o = DataFormat.formatString(s);
            if (b.compareTo(o) != 0) {
                System.out.println("  formatString 不等价: " + s + " base=" + b + " opt=" + o);
                return false;
            }
        }
        System.out.println("  formatdouble/formatString 等价: PASS");
        return true;
    }

    private static boolean checkIsDoubleEquivalence() {
        String[] probes = {
                "0", "1", "100", "1234.5", "999999.99", "1.234", "-5", "-0.5", "-0",
                "abc", "NaN", "Infinity", "1e3", "12.34.56", "", "0x1", "+5",
                "123456789012345678901", "99999999999999999999", "1.", ".5", "00100", " 10", "1E3",
                "2147483647", "2147483648", "0.00", "100.", "1.2.3", "¹", "1,000"
        };
        int mismatch = 0;
        for (String s : probes) {
            boolean base = BaselineIsDouble.isDouble(s);
            boolean opt = BenchCommandCoreAccess.isDouble(s);
            if (base != opt) {
                System.out.println("  isDouble 不等价: \"" + s + "\" base=" + base + " opt=" + opt);
                mismatch++;
            }
        }
        if (mismatch > 0) return false;
        System.out.println("  isDouble 等价: PASS (" + probes.length + " 探针)");
        return true;
    }

    private static boolean checkCacheReadEquivalence() {
        // DEFAULT UUID 命中 + 未命中(null，不抛 NPE)
        Cache.clearCache();
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;
        UUID u = new UUID(123L, 456L);
        Cache.insertIntoCache(u, new PlayerData(u, "Tester", new BigDecimal("1234.50")));
        if (!eqPD(Cache.getDataFromCache(u), "Tester", "1234.50")) {
            System.out.println("  cache DEFAULT 命中读不等价"); return false;
        }
        if (Cache.getDataFromCache(new UUID(999L, 999L)) != null) {
            System.out.println("  cache DEFAULT 未命中应返回 null"); return false;
        }
        // SEMIONLINE 命中（经 m_uuids）
        XConomyLoad.Config.UUIDMODE = UUIDMode.SEMIONLINE;
        Cache.clearCache();
        UUID local = new UUID(1L, 2L);
        UUID data = new UUID(3L, 4L);
        Cache.pds.put(data, new PlayerData(data, "Semi", new BigDecimal("9.00")));
        Cache.insertIntoMultiUUIDCache(data, local);
        if (Cache.getDataFromCache(local) == null) {
            System.out.println("  cache SEMIONLINE 读不等价"); return false;
        }
        // String 键命中 + 未命中(边界修复：不再 NPE，返回 null)
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;
        Cache.clearCache();
        UUID u2 = new UUID(5L, 6L);
        Cache.insertIntoCache(u2, new PlayerData(u2, "ByName", new BigDecimal("7.00")));
        if (Cache.getDataFromCache("ByName") == null) {
            System.out.println("  cache String键命中读不等价"); return false;
        }
        if (Cache.getDataFromCache("NoSuchPlayer") != null) {
            System.out.println("  cache String键未命中应返回 null(边界修复)"); return false;
        }
        // USERNAME_IGNORE_CASE：插入后小写存储，用大写查询应命中；未命中返回 null
        XConomyLoad.Config.USERNAME_IGNORE_CASE = true;
        Cache.clearCache();
        UUID u3 = new UUID(7L, 8L);
        Cache.insertIntoCache(u3, new PlayerData(u3, "MixedCase", new BigDecimal("7.00")));
        if (Cache.getDataFromCache("MIXEDCASE") == null) {
            System.out.println("  cache IGNORE_CASE 读不等价"); return false;
        }
        if (Cache.getDataFromCache("NOPE") != null) {
            System.out.println("  cache IGNORE_CASE 未命中应返回 null"); return false;
        }
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;
        Cache.clearCache();
        System.out.println("  缓存读等价(DEFAULT/SEMIONLINE/String/IGNORE_CASE + 未命中): PASS");
        return true;
    }

    // ================= 模式切换 =================

    private static void modeDecimalDefault() throws Exception {
        XConomyLoad.Config.INTEGER_BAL = false;
        reloadFormats();
    }

    private static void modeInteger() throws Exception {
        XConomyLoad.Config.INTEGER_BAL = true;
        reloadFormats();
    }

    private static void reloadFormats() throws Exception {
        DataFormat.load();
        BaselineDataFormat.load();
    }

    // ================= 输入准备 =================

    private static void prepareInputs() {
        balances = new BigDecimal[N];
        for (int i = 0; i < N; i++) {
            double mag = Math.pow(10, nextDouble() * 9);
            double v = Math.round(nextDouble() * mag * 100.0) / 100.0;
            balances[i] = BigDecimal.valueOf(v).setScale(2, RoundingMode.DOWN);
        }
        balances[0] = BigDecimal.ONE;
        balancesBig = new BigDecimal[NBIG];
        for (int i = 0; i < NBIG; i++) {
            double v = Math.round(nextDouble() * 1e9 * 100.0) / 100.0;
            balancesBig[i] = BigDecimal.valueOf(v).setScale(2, RoundingMode.DOWN);
        }
        skewedIdx = new int[L];
        for (int i = 0; i < L; i++) {
            double r = nextDouble();
            skewedIdx[i] = (r < 0.8) ? (int) ((r / 0.8) * HOT) : (int) (nextDouble() * N);
            if (skewedIdx[i] >= N) skewedIdx[i] = N - 1;
        }
    }

    private static BigDecimal[] balancesProbeSet() {
        return new BigDecimal[]{
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("2"), new BigDecimal("0.50"),
                new BigDecimal("999"), new BigDecimal("1000"), new BigDecimal("999.99"),
                new BigDecimal("1000.00"), new BigDecimal("999999"), new BigDecimal("1000000"),
                new BigDecimal("1234.50"), new BigDecimal("9999999.99"), new BigDecimal("1000000000"),
                DataFormat.maxNumber, DataFormat.maxNumber.subtract(BigDecimal.ONE)
        };
    }

    private static UUID[] prepareCacheDefault(int n) {
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;
        Cache.clearCache();
        UUID[] keys = new UUID[n];
        for (int i = 0; i < n; i++) {
            keys[i] = new UUID(nextRand(), nextRand());
            Cache.pds.put(keys[i], new PlayerData(keys[i], "p" + i, BigDecimal.valueOf(i)));
        }
        return keys;
    }

    private static UUID[][] prepareCacheSemiOnline(int n) {
        XConomyLoad.Config.UUIDMODE = UUIDMode.SEMIONLINE;
        Cache.clearCache();
        UUID[] local = new UUID[n];
        for (int i = 0; i < n; i++) {
            UUID l = new UUID(nextRand(), nextRand());
            UUID d = new UUID(nextRand(), nextRand());
            Cache.pds.put(d, new PlayerData(d, "s" + i, BigDecimal.valueOf(i)));
            Cache.insertIntoMultiUUIDCache(d, l);
            local[i] = l;
        }
        return new UUID[][]{local};
    }

    private static String[][] prepareCacheByName(int n) {
        XConomyLoad.Config.UUIDMODE = UUIDMode.DEFAULT;
        XConomyLoad.Config.USERNAME_IGNORE_CASE = false;
        Cache.clearCache();
        String[] names = new String[n];
        String[] namesUpper = new String[n];
        for (int i = 0; i < n; i++) {
            UUID u = new UUID(nextRand(), nextRand());
            String name = "player" + i;
            names[i] = name;
            namesUpper[i] = name.toUpperCase();
            Cache.insertIntoCache(u, new PlayerData(u, name, BigDecimal.valueOf(i)));
        }
        return new String[][]{names, namesUpper};
    }

    // ================= 小工具 =================

    private static boolean eq(String a, String b) { return a == null ? b == null : a.equals(b); }

    private static boolean eqPD(PlayerData pd, String name, String bal) {
        return pd != null && name.equals(pd.getName()) && new BigDecimal(bal).compareTo(pd.getBalance()) == 0;
    }
}
