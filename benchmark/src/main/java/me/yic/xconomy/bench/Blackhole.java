package me.yic.xconomy.bench;

/**
 * 消除 JIT 死代码消除（DCE）的“黑洞”：强制消费每次基准调用的返回值，
 * 防止编译器把无副作用的被测代码优化掉。所有基准的最终结果都汇入此对象。
 */
public final class Blackhole {
    public long sink = 0;

    public void consume(Object o) {
        if (o != null) {
            sink ^= o.hashCode();
        }
    }

    public void consume(long v) {
        sink ^= v;
    }

    public void consume(boolean v) {
        sink ^= v ? 1L : 0L;
    }

    public void touch() {
        if (sink == Long.MIN_VALUE) {
            System.out.println("(blackhole) " + sink);
        }
    }
}
