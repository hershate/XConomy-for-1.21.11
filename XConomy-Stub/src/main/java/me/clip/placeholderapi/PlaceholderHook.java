package me.clip.placeholderapi;

/*
 * 编译期桩（stub）。严格参考 PlaceholderAPI 2.11.1 官方源码：
 * https://github.com/PlaceholderAPI/PlaceholderAPI/blob/2.11.1/src/main/java/me/clip/placeholderapi/PlaceholderHook.java
 *
 * PlaceholderExpansion extends PlaceholderHook；XConomy 的 Placeholder.onRequest 覆盖本类的 onRequest。
 * 本桩不打包进 Bukkit/Paper jar（provided 依赖）；运行时由服务器上的 PlaceholderAPI 插件提供真实实现。
 * 方法签名与官方保持一致，确保运行时链接真实类时无 NoSuchMethodError。
 */
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

public abstract class PlaceholderHook {

    public String onRequest(final OfflinePlayer player, final String params) {
        return null;
    }

    public String onPlaceholderRequest(final Player player, final String params) {
        return null;
    }
}
