package me.clip.placeholderapi.expansion;

/*
 * 编译期桩（stub）。严格参考 PlaceholderAPI 最新版（master）官方源码：
 * https://github.com/PlaceholderAPI/PlaceholderAPI/blob/master/src/main/java/me/clip/placeholderapi/expansion/PlaceholderExpansion.java
 *
 * XConomy 的 Placeholder extends PlaceholderExpansion（重写 getIdentifier/getAuthor/getVersion/
 * onRequest/persist/canRegister），XConomy 还调用 register()/unregister()。
 * 本桩不打包进 Bukkit/Paper jar（provided 依赖）；运行时由服务器上的 PlaceholderAPI 插件提供真实实现。
 *
 * 方法签名（方法名、参数类型、返回类型、final）与官方最新版严格一致，
 * 以保证编译产物的字节码在运行时链接到真实 PlaceholderExpansion 时不会出现
 * NoSuchMethodError / IncompatibleClassChangeError。桩方法体本身在运行时不会执行（provided 不打包），
 * 因此方法体仅作占位（返回默认值）。jetbrains 注解（@NotNull/@Nullable/@ApiStatus/@Contract）已省略——
 * 它们不影响方法签名与运行时链接，且部分注解可能不在编译期依赖中。
 */
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import me.clip.placeholderapi.PlaceholderAPIPlugin;
import me.clip.placeholderapi.PlaceholderHook;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;

public abstract class PlaceholderExpansion extends PlaceholderHook {

    protected Type expansionType = Type.INTERNAL;

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public String getName() {
        return getIdentifier();
    }

    public String getRequiredPlugin() {
        return getPlugin();
    }

    @SuppressWarnings("rawtypes")
    public List getPlaceholders() {
        return Collections.emptyList();
    }

    public boolean persist() {
        return false;
    }

    public final boolean isRegistered() {
        return false;
    }

    public boolean canRegister() {
        return getRequiredPlugin() == null
                || Bukkit.getPluginManager().getPlugin(getRequiredPlugin()) != null;
    }

    public boolean register() {
        return false;
    }

    public final boolean unregister() {
        return false;
    }

    public final PlaceholderAPIPlugin getPlaceholderAPI() {
        return PlaceholderAPIPlugin.getInstance();
    }

    public Type getExpansionType() {
        return expansionType;
    }

    public void setExpansionType(Type expansionType) {
        this.expansionType = expansionType;
    }

    // === Configuration ===

    public final ConfigurationSection getConfigSection() {
        return null;
    }

    public final ConfigurationSection getConfigSection(String path) {
        return null;
    }

    public final Object get(String path, Object def) {
        return def;
    }

    public final int getInt(String path, int def) {
        return def;
    }

    public final long getLong(String path, long def) {
        return def;
    }

    public final double getDouble(String path, double def) {
        return def;
    }

    public final String getString(String path, String def) {
        return def;
    }

    @SuppressWarnings("rawtypes")
    public final List getStringList(String path) {
        return Collections.emptyList();
    }

    public final boolean getBoolean(String path, boolean def) {
        return def;
    }

    public final boolean configurationContains(String path) {
        return false;
    }

    // === Logging ===

    public void log(Level level, String msg) {
    }

    public void log(Level level, String msg, Throwable throwable) {
    }

    public void info(String msg) {
    }

    public void warning(String msg) {
    }

    public void severe(String msg) {
    }

    public void severe(String msg, Throwable throwable) {
    }

    @Override
    public final boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlaceholderExpansion)) {
            return false;
        }

        final PlaceholderExpansion expansion = (PlaceholderExpansion) o;

        return getIdentifier().equals(expansion.getIdentifier())
                && getAuthor().equals(expansion.getAuthor())
                && getVersion().equals(expansion.getVersion());
    }

    @Override
    public final String toString() {
        return String.format("PlaceholderExpansion[name: '%s', author: '%s', version: '%s', type: '%s']",
                getName(), getAuthor(), getVersion(), getExpansionType());
    }

    // === Deprecated API ===

    @Deprecated
    public String getPlugin() {
        return null;
    }

    @Deprecated
    public String getDescription() {
        return null;
    }

    @Deprecated
    public String getLink() {
        return null;
    }

    public enum Type {
        INTERNAL,
        EXTERNAL
    }
}
