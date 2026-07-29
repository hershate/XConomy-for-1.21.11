package net.md_5.bungee.api.config;

/*
 * 编译期桩（stub）。对应 BungeeCord 的 net.md_5.bungee.api.config.ServerInfo。
 */
import net.md_5.bungee.api.connection.ProxiedPlayer;

import java.util.Collection;

public interface ServerInfo {

    String getName();

    Collection<ProxiedPlayer> getPlayers();

    void sendData(String channel, byte[] data);
}
