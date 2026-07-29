package me.yic.xconomy.bench;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 通过反射装配一个最小可运行的 XConomy 运行时环境，避免启动 Bukkit 服务端。
 *
 * 关键点（顺序敏感）：
 *  1. 先令 XConomy.instance 非空（DefaultConfig 构造期会调用 XConomy.getInstance().logger，桩实现为空体）；
 *  2. 再注入一份真实的、Map 版 CConfig 到 DefaultConfig.config；
 *  3. 构造 DefaultConfig 并赋给 XConomyLoad.Config —— 必须在 DataFormat 类被加载之前完成，
 *     因为 DataFormat 的 final static 模板字段在类初始化时读取 XConomyLoad.Config；
 *  4. 最后调用 DataFormat.load() 初始化 DecimalFormat / maxNumber / isint / format-balance。
 *
 * 本类全程使用反射，避免在编译期触发 DataFormat / CommandCore 的类加载。
 */
public final class BenchEnv {

    private static volatile boolean done = false;

    public static synchronized void setup() throws Exception {
        if (done) {
            return;
        }

        // 1. XConomy.instance = new XConomy()
        Class<?> xc = Class.forName("me.yic.xconomy.XConomy");
        Object inst = xc.getDeclaredConstructor().newInstance();
        Field instanceField = xc.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, inst);

        // 2. DefaultConfig.config = new CConfig(null)
        Class<?> cconfig = Class.forName("me.yic.xconomy.adapter.comp.CConfig");
        Object cconfigInst = cconfig.getDeclaredConstructor(java.io.File.class).newInstance(new Object[]{null});
        Class<?> defaultConfig = Class.forName("me.yic.xconomy.info.DefaultConfig");
        Field configField = defaultConfig.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(null, cconfigInst);

        // 3. XConomyLoad.Config = new DefaultConfig()
        Class<?> xconomyLoad = Class.forName("me.yic.xconomy.XConomyLoad");
        Object dc = defaultConfig.getDeclaredConstructor().newInstance();
        Field loadConfigField = xconomyLoad.getDeclaredField("Config");
        loadConfigField.setAccessible(true);
        loadConfigField.set(null, dc);

        // 4. DataFormat.load()
        Class<?> dataFormat = Class.forName("me.yic.xconomy.data.DataFormat");
        Method load = dataFormat.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(null);

        // 5. MessagesManager.messageFile —— CommandCore 静态初始化执行
        //    PREFIX = translateColorCodes("prefix")，依赖 messageFile.getString("prefix") 非空，
        //    否则 CChat.translateAlternateColorCodes 收到 null 抛 NPE。
        Class<?> mm = Class.forName("me.yic.xconomy.lang.MessagesManager");
        Object msgCfg = cconfig.getDeclaredConstructor(java.io.File.class).newInstance(new Object[]{null});
        Method setCconfig = cconfig.getMethod("set", String.class, Object.class);
        setCconfig.invoke(msgCfg, "prefix", "&f[XConomy]&r ");
        Field messageFileField = mm.getDeclaredField("messageFile");
        messageFileField.setAccessible(true);
        messageFileField.set(null, msgCfg);

        // 同步装配 BaselineDataFormat（其自带 DecimalFormat，模板取自同一 Config）
        Class.forName("me.yic.xconomy.bench.BaselineDataFormat")
                .getDeclaredMethod("load").invoke(null);

        done = true;
    }
}
