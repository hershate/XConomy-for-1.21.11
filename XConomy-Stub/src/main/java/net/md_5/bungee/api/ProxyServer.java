package net.md_5.bungee.api;

/*
 * 编译期桩（stub）。对应 BungeeCord 的 net.md_5.bungee.api.ProxyServer。
 * 仅供 XConomyBungee / BCsync 编译；这些代理端类不打包进 Paper jar 运行，桩方法实现无意义。
 */
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.PluginManager;
import net.md_5.bungee.api.scheduler.TaskScheduler;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public abstract class ProxyServer {

    public static ProxyServer getInstance() {
        return null;
    }

    public abstract PluginManager getPluginManager();

    public abstract void registerChannel(String channel);

    public abstract ProxiedPlayer getPlayer(UUID uuid);

    public abstract ProxiedPlayer getPlayer(String name);

    public abstract Map<String, ServerInfo> getServers();

    public abstract TaskScheduler getScheduler();

    public abstract Collection<ProxiedPlayer> getPlayers();

    public abstract Logger getLogger();
}
