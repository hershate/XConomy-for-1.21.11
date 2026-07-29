package me.yic.xconomy.bench;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 多线程聚合吞吐测量：T 个线程并发执行同一被测操作，测量墙上时间，
 * 聚合吞吐 = (T * itersPerThread) / 墙上时间。
 *
 * 用于暴露“读多写少的展示缓存”在真实 PlaceholderAPI 多线程并发下的表现——
 * 例如 synchronizedMap 缓存的全局锁是否会成为吞吐瓶颈。
 */
public final class ConcurrentStats {

    @FunctionalInterface
    public interface Op {
        void apply(int index, Blackhole bh);
    }

    public static final class Result {
        public final String name;
        public final double aggregateOpsPerSec;
        public final int threads;
        public final long itersPerThread;

        public Result(String name, double[] tput, int threads, long itersPerThread) {
            this.name = name;
            this.threads = threads;
            this.itersPerThread = itersPerThread;
            this.aggregateOpsPerSec = median(tput);
        }

        public String speedupOver(Result baseline) {
            return String.format("%.2fx", this.aggregateOpsPerSec / baseline.aggregateOpsPerSec);
        }
    }

    /**
     * @param threads         并发线程数
     * @param warmupPerThread 单线程预热操作数（触发 JIT）
     * @param rounds          测量轮数
     * @param itersPerThread  每轮每线程操作数
     */
    public static double[] measure(int threads, long warmupPerThread, int rounds, long itersPerThread, Op op) {
        // 单线程预热
        Blackhole wbh = new Blackhole();
        for (long i = 0; i < warmupPerThread; i++) {
            op.apply((int) (i & 0x7fffffff), wbh);
        }
        wbh.touch();

        ExecutorService ex = Executors.newFixedThreadPool(threads);
        double[] tput = new double[rounds];
        try {
            for (int r = 0; r < rounds; r++) {
                CountDownLatch ready = new CountDownLatch(threads);
                CountDownLatch startGate = new CountDownLatch(1);
                CountDownLatch done = new CountDownLatch(threads);
                AtomicLong sharedSink = new AtomicLong(0);

                for (int t = 0; t < threads; t++) {
                    final int tid = t;
                    ex.submit(() -> {
                        Blackhole bh = new Blackhole();
                        ready.countDown();
                        try {
                            startGate.await();
                            long base = tid * 7919L; // 让不同线程访问不同索引偏移，减少伪共享
                            for (long i = 0; i < itersPerThread; i++) {
                                op.apply((int) ((base + i) & 0x7fffffff), bh);
                            }
                            sharedSink.addAndGet(bh.sink);
                            bh.touch();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                ready.await();
                long start = System.nanoTime();
                startGate.countDown();
                done.await();
                long dur = System.nanoTime() - start;
                tput[r] = (threads * (double) itersPerThread) * 1e9 / dur;
                if (sharedSink.get() == Long.MIN_VALUE) System.out.println("(c-sink)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            ex.shutdownNow();
            try { ex.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        }
        return tput;
    }

    public static Result result(String name, double[] tput, int threads, long itersPerThread) {
        return new Result(name, tput, threads, itersPerThread);
    }

    private static double median(double[] a) {
        double[] s = java.util.Arrays.copyOf(a, a.length);
        java.util.Arrays.sort(s);
        int n = s.length;
        return (n % 2 == 0) ? (s[n / 2 - 1] + s[n / 2]) / 2.0 : s[n / 2];
    }
}
