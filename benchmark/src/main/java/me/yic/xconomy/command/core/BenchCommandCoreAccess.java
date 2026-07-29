package me.yic.xconomy.command.core;

/**
 * 同包访问器：CommandCore.isDouble 为 protected，仅同包可访问。
 * 本类置于 me.yic.xconomy.command.core 包下，提供公开委托，
 * 使 benchmark 无需反射即可调用优化后的 CommandCore.isDouble（与基线公平对比，无反射开销）。
 */
public final class BenchCommandCoreAccess {
    public static boolean isDouble(String s) {
        return CommandCore.isDouble(s);
    }
}
