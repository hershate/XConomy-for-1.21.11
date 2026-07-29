package me.yic.xconomy.adapter.comp;

import net.md_5.bungee.api.ChatColor;

/**
 * benchmark 专用 CChat 覆盖实现（与 Core 桩同包同名，classpath 优先级覆盖桩）。
 *
 * Core 桩的 translateAlternateColorCodes 返回空串（仅占位），无法代表真实生产成本。
 * 这里委托给 XConomy-Stub 中已实现的、与 Bukkit/Paper 内嵌一致的 ChatColor 标准算法，
 * 使 DataFormat.shown() 在 benchmark 中执行与生产一致的完整颜色翻译工作。
 */
@SuppressWarnings("unused")
public class CChat {
    public static String translateAlternateColorCodes(Character cha, String str) {
        return ChatColor.translateAlternateColorCodes(cha, str);
    }
}
