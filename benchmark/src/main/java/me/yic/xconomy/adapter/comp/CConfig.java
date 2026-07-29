package me.yic.xconomy.adapter.comp;

import me.yic.xconomy.adapter.iConfig;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * benchmark 专用的 CConfig 覆盖实现（与 Core 桩同包同名，利用 classpath 优先级覆盖桩）。
 *
 * 以内存 Map 承载一份“代表真实 config.yml 默认值”的配置，供 DefaultConfig 在
 * 构造期读取，从而在不启动 Bukkit 服务端的前提下得到一份真实可用的 DataFormat 配置。
 */
@SuppressWarnings("unused")
public class CConfig implements iConfig {

    private final Map<String, Object> values = new TreeMap<>();

    public CConfig(File f) {
        loadDefaults();
    }

    public CConfig(URL url) {
        loadDefaults();
    }

    public CConfig(String path, String subpath) {
        loadDefaults();
    }

    /** 预填与 XConomy-Core/src/main/resources/config.yml 默认值一致的代表性配置。 */
    private void loadDefaults() {
        put("UUID-mode", "Default");
        put("Importdata-mode", false);

        put("Settings.language", "English");
        put("Settings.check-update", true);
        put("Settings.refresh-time", 30);
        put("Settings.eco-command", false);
        put("Settings.disable-essentials", false);
        put("Settings.initial-bal", 0.0d);
        put("Settings.payment-tax", 0.0d);
        put("Settings.ranking-size", 10);
        put("Settings.lines-per-page", 10);
        put("Settings.disable-cache", false);
        put("Settings.transaction-record", false);
        put("Settings.offline-pay-transfer-tips", false);
        put("Settings.username-ignore-case", false);

        put("non-player-account.enable", false);
        put("non-player-account.whitelist.enable", false);

        put("Currency.singular-name", "dollar");
        put("Currency.plural-name", "dollars");
        put("Currency.integer-bal", false);
        put("Currency.rounding-mode", 0);
        put("Currency.thousands-separator", ",");
        put("Currency.display-format", "%balance% %currencyname%");
        put("Currency.max-number", "10000000000000000");

        put("SyncData.enable", false);
        put("SyncData.sign", "aa");
        put("SyncData.channel-type", "Off");

        put("Thread.future-timeout", 3);

        put("Region-Thread.world", "world");
        put("Region-Thread.range-x", 512);
        put("Region-Thread.range-y", 512);
    }

    private void put(String k, Object v) {
        values.put(k, v);
    }

    @Override
    public Object getConfig() {
        return values;
    }

    @Override
    public boolean contains(String path) {
        return values.containsKey(path);
    }

    @Override
    public void createSection(String path) {
    }

    @Override
    public void set(String path, Object value) {
        values.put(path, value);
    }

    @Override
    public void save() throws IOException {
    }

    @Override
    public String getString(String path) {
        Object v = values.get(path);
        return v == null ? null : v.toString();
    }

    @Override
    public Integer getInt(String path) {
        Object v = values.get(path);
        if (v == null) return 0;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public boolean getBoolean(String path) {
        Object v = values.get(path);
        return Boolean.TRUE.equals(v);
    }

    @Override
    public double getDouble(String path) {
        Object v = values.get(path);
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return v == null ? 0.0d : Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return 0.0d;
        }
    }

    @Override
    public long getLong(String path) {
        Object v = values.get(path);
        if (v instanceof Number) return ((Number) v).longValue();
        return 0L;
    }

    @Override
    public List<String> getStringList(String path) {
        Object v = values.get(path);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<String> list = (List<String>) v;
            return list;
        }
        return java.util.Collections.emptyList();
    }

    @Override
    public LinkedHashMap<BigDecimal, String> getConfigurationSectionSort(String path) {
        // 代表性 format-balance：1000->"k", 1000000->"m"
        if ("Currency.format-balance".equals(path)) {
            LinkedHashMap<BigDecimal, String> m = new LinkedHashMap<>();
            m.put(new BigDecimal("1000"), "k");
            m.put(new BigDecimal("1000000"), "m");
            return m;
        }
        return null;
    }
}
