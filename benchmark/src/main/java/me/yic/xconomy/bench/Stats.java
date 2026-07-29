package me.yic.xconomy.bench;

import java.util.Arrays;

/**
 * 轻量但严谨的微基准统计工具。
 *
 * 方法论：
 *  - 先执行足够长的 warmup 轮，触发 JIT 热点编译到 C2，达到稳态；
 *  - 再执行 rounds 个测量轮，每轮 itersPerRound 次操作，记录单轮耗时；
 *  - 吞吐 = itersPerRound * 1e9 / 单轮纳秒，取中位数代表稳态吞吐，另报 min/max 观察波动。
 *
 * 同一 JVM、相同输入、相同预热下对比“原始算法(Baseline*)”与“优化后 Core 实现”，
 * 从而把算法差异与环境噪声隔离开。
 */
public final class Stats {

    @FunctionalInterface
    public interface Op {
        void apply(int index, Blackhole bh);
    }

    /** 单次测量结果。 */
    public static final class Result {
        public final String name;
        public final double throughputMedian;   // ops/s
        public final double throughputMin;      // ops/s
        public final double throughputMax;      // ops/s
        public final double nsPerOpMedian;      // ns/op
        public final long itersPerRound;
        public final int rounds;

        public Result(String name, double[] tput, long itersPerRound) {
            this.name = name;
            this.itersPerRound = itersPerRound;
            this.rounds = tput.length;
            double[] sorted = Arrays.copyOf(tput, tput.length);
            Arrays.sort(sorted);
            this.throughputMedian = median(sorted);
            this.throughputMin = sorted[0];
            this.throughputMax = sorted[sorted.length - 1];
            this.nsPerOpMedian = 1e9 / throughputMedian;
        }

        public String speedupOver(Result baseline) {
            double ratio = this.throughputMedian / baseline.throughputMedian;
            return String.format("%.2fx", ratio);
        }
    }

    /**
     * 运行基准并返回每轮吞吐数组。
     *
     * @param warmupIters   预热总操作数（不计入统计）
     * @param rounds        测量轮数
     * @param itersPerRound 每轮操作数
     * @param op            被测操作；index 为本轮内的递增序号，便于按预生成输入取值
     */
    public static double[] measure(long warmupIters, int rounds, long itersPerRound, Op op) {
        Blackhole bh = new Blackhole();
        // 预热
        for (long i = 0; i < warmupIters; i++) {
            op.apply((int) (i & 0x7fffffff), bh);
            bh.sink ^= i;
        }
        bh.touch();

        double[] tput = new double[rounds];
        for (int r = 0; r < rounds; r++) {
            long start = System.nanoTime();
            for (long i = 0; i < itersPerRound; i++) {
                op.apply((int) (i & 0x7fffffff), bh);
                bh.sink ^= i;
            }
            long dur = System.nanoTime() - start;
            tput[r] = itersPerRound * 1e9 / dur;
        }
        bh.touch();
        return tput;
    }

    public static Result result(String name, double[] tput, long itersPerRound) {
        return new Result(name, tput, itersPerRound);
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return (n % 2 == 0) ? (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0 : sorted[n / 2];
    }
}
