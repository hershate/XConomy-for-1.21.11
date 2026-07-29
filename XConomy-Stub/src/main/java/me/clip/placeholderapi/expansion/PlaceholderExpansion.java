package me.clip.placeholderapi.expansion;

/*
 * 编译期桩（stub）。对应 PlaceholderAPI 的 me.clip.placeholderapi.expansion.PlaceholderExpansion。
 * XConomy 的 Placeholder extends PlaceholderExpansion（重写 getAuthor/getIdentifier/getVersion/
 * onRequest/persist/canRegister），XConomy 还调用 register()/unregister()。
 * 本桩不打包进 Bukkit/Paper jar（provided 依赖）；运行时由服务器上的 PlaceholderAPI 插件提供真实实现。
 */
import org.bukkit.OfflinePlayer;

public abstract class PlaceholderExpansion {

    public String getAuthor() {
        return "";
    }

    public String getIdentifier() {
        return null;
    }

    public String getVersion() {
        return null;
    }

    public String onRequest(OfflinePlayer player, String params) {
        return null;
    }

    public boolean persist() {
        return false;
    }

    public boolean canRegister() {
        return false;
    }

    public boolean register() {
        return false;
    }

    public void unregister() {
    }
}
