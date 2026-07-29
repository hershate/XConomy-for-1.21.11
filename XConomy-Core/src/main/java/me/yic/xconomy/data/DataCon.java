/*
 *  This file (DataCon.java) is a part of project XConomy
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
package me.yic.xconomy.data;

import me.yic.xconomy.AdapterManager;
import me.yic.xconomy.XConomy;
import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.adapter.comp.CPlayer;
import me.yic.xconomy.adapter.comp.CallAPI;
import me.yic.xconomy.data.caches.Cache;
import me.yic.xconomy.data.caches.CacheNonPlayer;
import me.yic.xconomy.data.redis.RedisPublisher;
import me.yic.xconomy.data.syncdata.PlayerData;
import me.yic.xconomy.data.syncdata.SyncBalanceAll;
import me.yic.xconomy.data.syncdata.SyncData;
import me.yic.xconomy.data.syncdata.SyncDelData;
import me.yic.xconomy.info.MessageConfig;
import me.yic.xconomy.info.RecordInfo;
import me.yic.xconomy.info.SyncChannalType;
import me.yic.xconomy.utils.SendPluginMessage;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DataCon {

    // 按账户维度的锁，串行化同一玩家/非玩家账户的"读取-计算-写缓存"流程，
    // 消除高并发下的缓存丢失更新（lost update）。
    private static final ConcurrentHashMap<UUID, Object> playerLocks = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Object> accountLocks = new ConcurrentHashMap<>();

    // public 供跨服同步的 SyncStart 与本地变更串行化，避免异步同步线程与主线程并发写同一玩家缓存
    public static Object getPlayerLock(UUID u) {
        return playerLocks.computeIfAbsent(u, k -> new Object());
    }

    private static Object getAccountLock(String a) {
        return accountLocks.computeIfAbsent(a, k -> new Object());
    }

    public static PlayerData getPlayerData(UUID uuid) {
        return getPlayerDatai(uuid);
    }

    public static PlayerData getPlayerData(String username) {
        return getPlayerDatai(username);
    }

    public static BigDecimal getAccountBalance(String account) {
        if (XConomyLoad.Config.DISABLE_CACHE){
            return DataLink.getBalNonPlayer(account);
        }
        // 单次缓存查询；未命中（含并发移除）返回 null，再回源数据库。
        // 等价于原先“CacheContainsKey 命中后再 getBalanceFromCacheOrDB”的两步查询，省一次哈希查找。
        BigDecimal bal = CacheNonPlayer.getBalanceFromCacheOrDB(account);
        if (bal == null){
            bal =  DataLink.getBalNonPlayer(account);
        }
        return bal;
    }

    private static <T> PlayerData getPlayerDatai(T u) {
        if (XConomyLoad.Config.DISABLE_CACHE) {
            return DataLink.getPlayerData(u);
        }
        // 单次缓存查询；未命中（含并发移除）返回 null，再回源数据库。
        // 等价于原先“CacheContainsKey 命中后再 getDataFromCache”的两步查询，省一次哈希查找。
        PlayerData pd = Cache.getDataFromCache(u);
        if (pd == null){
            pd = DataLink.getPlayerData(u);
        }
        // 不再在"无在线玩家"时清空全部缓存：异步落库可能尚未完成，
        // 此时清缓存会导致下次读取从数据库拿到旧值，造成余额丢失。
        // 缓存清理改由玩家退出（onQuit）按个体进行。
        return pd;
    }

    public static int getPlayerHiddenState(UUID uid) {
        if (Cache.phids.containsKey(uid)){
            return Cache.phids.get(uid);
        }
        if (DataLink.getPlayerData(uid) != null) {
            return Cache.phids.get(uid);
        }
        return 1;
    }
    public static void removePlayerHiddenState(UUID uid) {
        Cache.phids.remove(uid);
    }

    public static void deletePlayerData(PlayerData pd) {
        DataLink.deletePlayerData(pd.getUniqueId());
        Cache.removefromCache(pd.getUniqueId());

        if (!(pd instanceof SyncDelData) && XConomyLoad.getSyncData_Enable()) {
            SendMessTask(new SyncDelData(pd));
        }

        CPlayer cp = AdapterManager.PLUGIN.getplayer(pd);
        if(cp.isOnline()){
            cp.kickPlayer("[XConomy] " + AdapterManager.translateColorCodes(MessageConfig.DELETE_DATA));
        }
    }

    public static boolean hasaccountdatacache(String name) {
        return CacheNonPlayer.CacheContainsKey(name);
    }

    public static void deletedatafromcache(UUID u) {
        Cache.deleteDataFromCache(u);
    }

    public static boolean containinfieldslist(String name) {
        if (XConomyLoad.Config.NON_PLAYER_ACCOUNT_SUBSTRING != null) {
            for (String field : XConomyLoad.Config.NON_PLAYER_ACCOUNT_SUBSTRING) {
                if (name.contains(field)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static BigDecimal changeplayerdata(final String type, final UUID uid, final BigDecimal amount, final Boolean isAdd, final String command, final Object comment) {
        PlayerData pd = getPlayerData(uid);
        if (pd == null) {
            XConomy.getInstance().logger("余额变更失败：未找到玩家数据 - " + uid, 1, null);
            return BigDecimal.ZERO;
        }
        final UUID u = pd.getUniqueId();
        final BigDecimal bal;
        final BigDecimal newvalue;

        // 串行化同一玩家的"读取-计算-写缓存"，避免并发丢失更新（lost update）。
        synchronized (getPlayerLock(u)) {
            pd = getPlayerData(uid);
            if (pd == null) {
                XConomy.getInstance().logger("余额变更失败：未找到玩家数据 - " + uid, 1, null);
                return BigDecimal.ZERO;
            }
            bal = pd.getBalance();
            if (isAdd != null) {
                if (isAdd) {
                    newvalue = bal.add(amount);
                } else {
                    newvalue = bal.subtract(amount);
                }
            } else {
                newvalue = amount;
            }
            Cache.updateIntoCache(u, pd, newvalue, bal);
        }

        final PlayerData fpd = pd;
        final RecordInfo ri = new RecordInfo(type, command, comment);

        CallAPI.CallPlayerAccountEvent(u, pd.getName(), bal, amount, isAdd, ri);

        Runnable saveTask = () -> {
            if (DataLink.save(fpd, isAdd, amount, ri)) {
                if (XConomyLoad.getSyncData_Enable()) {
                    SendMessTask(fpd);
                }
            } else {
                // 落库失败：失效该玩家缓存，下次读取将从数据库获取真实余额，
                // 避免缓存长期保留未被持久化的错误数值（防丢币/刷币）。
                Cache.deleteDataFromCache(u);
                XConomy.getInstance().logger("严重：玩家余额落库失败，已失效缓存以便从数据库恢复 - " + u, 1, null);
            }
        };

        if (XConomyLoad.DConfig.canasync && AdapterManager.checkisMainThread()) {
            AdapterManager.runTaskAsynchronously(saveTask);
        } else {
            saveTask.run();
        }

        return newvalue;
    }

    /**
     * 带余额充足性原子校验的玩家余额变更，专用于玩家转账(/pay)等"扣款前必须确认余额"的场景。
     * 在 per-UUID 锁内读取最新余额并校验，余额不足时不做任何变更并返回 false，
     * 消除"调用方检查余额"与"实际扣款"之间的 TOCTOU 竞态（高并发下被异步来源扣款导致透支）。
     * 仅扣款（isAdd == false）校验下限；存款/set 不校验。
     *
     * @return true 表示变更已提交（缓存已更新，落库已派发）；false 表示账户不存在或余额不足。
     */
    public static boolean changeplayerdataWithCheck(final String type, final UUID uid, final BigDecimal amount, final Boolean isAdd, final String command, final Object comment) {
        PlayerData pd = getPlayerData(uid);
        if (pd == null) {
            XConomy.getInstance().logger("余额变更失败：未找到玩家数据 - " + uid, 1, null);
            return false;
        }
        final UUID u = pd.getUniqueId();

        synchronized (getPlayerLock(u)) {
            pd = getPlayerData(uid);
            if (pd == null) {
                XConomy.getInstance().logger("余额变更失败：未找到玩家数据 - " + uid, 1, null);
                return false;
            }
            BigDecimal bal = pd.getBalance();
            if (isAdd != null && !isAdd && bal.compareTo(amount) < 0) {
                return false;
            }
            BigDecimal newvalue;
            if (isAdd != null) {
                if (isAdd) {
                    newvalue = bal.add(amount);
                } else {
                    newvalue = bal.subtract(amount);
                }
            } else {
                newvalue = amount;
            }
            Cache.updateIntoCache(u, pd, newvalue, bal);
        }

        final PlayerData fpd = pd;
        final RecordInfo ri = new RecordInfo(type, command, comment);

        Runnable saveTask = () -> {
            if (DataLink.save(fpd, isAdd, amount, ri)) {
                if (XConomyLoad.getSyncData_Enable()) {
                    SendMessTask(fpd);
                }
            } else {
                Cache.deleteDataFromCache(u);
                XConomy.getInstance().logger("严重：玩家余额落库失败，已失效缓存以便从数据库恢复 - " + u, 1, null);
            }
        };

        if (XConomyLoad.DConfig.canasync && AdapterManager.checkisMainThread()) {
            AdapterManager.runTaskAsynchronously(saveTask);
        } else {
            saveTask.run();
        }
        return true;
    }


    public static void changeaccountdata(final String type, final String u, final BigDecimal amount, final Boolean isAdd, final String command) {
        synchronized (getAccountLock(u)) {
            BigDecimal balance = getAccountBalance(u);
            if (balance == null) {
                XConomy.getInstance().logger("非玩家账户余额变更：账户不存在，按 0 处理 - " + u, 1, null);
                balance = BigDecimal.ZERO;
            }
            BigDecimal newvalue;

            RecordInfo ri = new RecordInfo(type, command, null);

            CallAPI.CallNonPlayerAccountEvent(u, balance, amount, isAdd, type);
            if (isAdd != null) {
                if (isAdd) {
                    newvalue = balance.add(amount);
                } else {
                    newvalue = balance.subtract(amount);
                }
            } else {
                newvalue = amount;
            }
            CacheNonPlayer.insertIntoCache(u, newvalue);

            final BigDecimal fnewvalue = newvalue;
            Runnable saveTask = () -> {
                if (!DataLink.saveNonPlayer(u, amount, fnewvalue, isAdd, ri)) {
                    CacheNonPlayer.bal.remove(u);
                    XConomy.getInstance().logger("严重：非玩家账户落库失败，已失效缓存以便从数据库恢复 - " + u, 1, null);
                }
            };

            if (XConomyLoad.DConfig.canasync && AdapterManager.checkisMainThread()) {
                AdapterManager.runTaskAsynchronously(saveTask);
            } else {
                saveTask.run();
            }
        }
    }

    public static void changeallplayerdata(String targettype, String type, BigDecimal amount, Boolean isAdd, String command, StringBuilder comment) {
        RecordInfo ri = new RecordInfo(type, command, comment);

        Runnable saveTask = () -> {
            DataLink.saveall(targettype, amount, isAdd, ri);
            // 批量更新写入数据库后再清空缓存，确保下次读取拿到的是更新后的真实余额，
            // 避免在"缓存已清但数据库未改"的窗口内读到旧值。
            Cache.clearCache();
        };

        if (XConomyLoad.DConfig.canasync && AdapterManager.checkisMainThread()) {
            AdapterManager.runTaskAsynchronously(saveTask);
        } else {
            saveTask.run();
        }

        boolean isallbool = targettype.equals("all");

        if (XConomyLoad.getSyncData_Enable()) {
            SendMessTask(new SyncBalanceAll(isallbool, isAdd, amount));
        }
    }

    public static void baltop() {
        Cache.baltop.clear();
        Cache.baltop_papi.clear();
        sumbal();
        DataLink.getTopBal();
    }


    public static void sumbal() {
        Cache.sumbalance = DataFormat.formatString(DataLink.getBalSum());
    }


    public static void SendMessTask(SyncData pd) {
        if (XConomyLoad.Config.SYNCDATA_TYPE.equals(SyncChannalType.REDIS)) {
            RedisPublisher.publishmessage(pd.toByteArray(XConomy.syncversion).toByteArray());
        }else if (XConomyLoad.Config.SYNCDATA_TYPE.equals(SyncChannalType.BUNGEECORD)) {
            SendPluginMessage.SendMessTask("xconomy:acb", pd);
        }
    }

}
