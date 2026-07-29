/*
 *  This file (Cache.java) is a part of project XConomy
 *  Copyright (C) YiC and contributors
 *
 *  This program is free software: you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package me.yic.xconomy.data.caches;

import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.data.GetUUID;
import me.yic.xconomy.data.syncdata.PlayerData;
import me.yic.xconomy.utils.UUIDMode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class Cache {
    public static final ConcurrentHashMap<UUID, PlayerData> pds = new ConcurrentHashMap<>();
    public static final ConcurrentHashMap<UUID, Integer> phids = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, UUID> uuids = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, UUID> m_uuids = new ConcurrentHashMap<>();

    // 排行榜数据在异步 Baltop 任务中写入、在主线程与 PlaceholderAPI 中读取，
    // 使用并发安全容器避免 ConcurrentModificationException；sumbalance 用 volatile 保证可见性。
    public static ConcurrentHashMap<String, BigDecimal> baltop = new ConcurrentHashMap<>();
    public static CopyOnWriteArrayList<String> baltop_papi = new CopyOnWriteArrayList<>();
    public static volatile BigDecimal sumbalance = BigDecimal.ZERO;

    public static void insertIntoCache(final UUID uuid, final PlayerData pd) {
        if (pd != null) {
            if (pd.getName() != null && pd.getBalance() != null) {
                pds.put(uuid, pd);
                if (XConomyLoad.Config.USERNAME_IGNORE_CASE) {
                    uuids.put(pd.getName().toLowerCase(), uuid);
                } else {
                    uuids.put(pd.getName(), uuid);
                }
            }
        }
    }

    public static void insertIntoMultiUUIDCache(final UUID uuid, final UUID luuid) {
        if (uuid != null && luuid != null && !uuid.toString().equals(luuid.toString())) {
            m_uuids.put(luuid, uuid);
        }
    }

    public static void insertIntoHiddenMap(final UUID uuid, final int hidden) {
        if (uuid != null) {
            phids.put(uuid, hidden);
        }
    }

    public static <T> boolean CacheContainsKey(final T key) {
        if (key instanceof UUID) {
            if (pds.containsKey((UUID) key)){
                return true;
            }
            if (XConomyLoad.Config.UUIDMODE.equals(UUIDMode.SEMIONLINE)) {
                UUID luuid = getMultiUUIDCache((UUID) key);
                if (luuid == null) {
                    return false;
                }else {
                    return pds.containsKey(luuid);
                }
            }
            return false;
        }
        if (XConomyLoad.Config.USERNAME_IGNORE_CASE) {
            return uuids.containsKey(((String) key).toLowerCase());
        }
        return uuids.containsKey((String) key);
    }


    public static <T> PlayerData getDataFromCache(final T key) {
        UUID u;
        if (key instanceof UUID) {
            u = (UUID) key;
            if (XConomyLoad.Config.UUIDMODE.equals(UUIDMode.SEMIONLINE)) {
                if (!pds.containsKey((UUID) key) && getMultiUUIDCache((UUID) key) != null){
                    u = getMultiUUIDCache((UUID) key);
                }
            }
        } else {
            if (XConomyLoad.Config.USERNAME_IGNORE_CASE) {
                u = uuids.get(((String) key).toLowerCase());
            } else {
                u = uuids.get(((String) key));
            }
        }
        // 边界修复：按玩家名(或 SEMIONLINE 之外的 String 键)未命中时 u 为 null，
        // 而 pds 是 ConcurrentHashMap（不允许 null 键），pds.get(null) 会抛 NPE。
        // 此处返回 null 表示“不在缓存”，使读路径可安全地“单次 get + 回源”，
        // 也修复了原先“仅在 containsKey 守卫下才不触发”的潜伏 NPE。
        return u == null ? null : pds.get(u);
    }

    public static void deleteDataFromCache(final UUID key) {
        if (CacheContainsKey(key)) {
            pds.remove(key);
        }
    }

    public static UUID getMultiUUIDCache(final UUID luuid) {
        if (m_uuids.containsKey(luuid)){
            return m_uuids.get(luuid);
        }
        return null;
    }

    public static void updateIntoCache(final UUID uuid, final PlayerData pd, final BigDecimal newbalance, final BigDecimal vbalance) {
        pd.setBalance(newbalance);
        pd.setVerifyBalance(vbalance);
        pds.put(uuid, pd);
    }

    @SuppressWarnings("all")
    public static void removefromCache(final UUID uuid) {
        if (pds.containsKey(uuid)) {
            String name = pds.get(uuid).getName();
            pds.remove(uuid);
            uuids.remove(name);
        }
    }


    @SuppressWarnings("all")
    public static void syncOnlineUUIDCache(final String oldname, final String newname, final UUID uuid) {
        if (uuids.containsKey(newname)) {
            UUID u = uuids.get(newname);
            pds.remove(u);
            uuids.remove(newname);
        }
        GetUUID.removeUUIDFromCache(oldname);
        GetUUID.removeUUIDFromCache(newname);
        removefromCache(uuid);
    }


    public static void clearCache() {
        pds.clear();
        uuids.clear();
        m_uuids.clear();
    }


}