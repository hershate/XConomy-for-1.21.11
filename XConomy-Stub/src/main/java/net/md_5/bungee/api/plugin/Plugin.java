package net.md_5.bungee.api.plugin;

/*
 * 编译期桩（stub）。对应 BungeeCord 的 net.md_5.bungee.api.plugin.Plugin。
 * XConomyBungee extends Plugin；该代理端类不打包进 Paper jar 运行。
 */
import net.md_5.bungee.api.ProxyServer;

import java.util.logging.Logger;

public abstract class Plugin {

    public ProxyServer getProxy() {
        return null;
    }

    public Logger getLogger() {
        return null;
    }

    public void onEnable() {
    }

    public void onDisable() {
    }
}
