/*
 *  This file (PermissionINFO.java) is a part of project XConomy
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
package me.yic.xconomy.info;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public class PermissionINFO {
    // 跨线程(异步同步线程写、主线程读)读写，使用 volatile 保证可见性
    public static volatile boolean globalpayment = true;

    // 被异步同步线程(SyncPermission)与主线程命令并发访问，使用并发容器避免数据丢失与 CME
    private static final Map<UUID, Boolean> payment = new ConcurrentHashMap<>();
    private static final List<UUID> rpayment = new CopyOnWriteArrayList<>();

    public static boolean getGlobalPayment() {
        return globalpayment;
    }

    public static Boolean getPaymentPermission(UUID u) {
        return payment.getOrDefault(u, null);
    }

    public static void setPaymentPermission(UUID u, Boolean b) {
        if (b == null){
            payment.remove(u);
        } else {
            payment.put(u, b);
        }
    }
    public static boolean getRPaymentPermission(UUID u) {
        return !rpayment.contains(u);
    }

    public static void setRPaymentPermission(UUID u) {
        if (rpayment.contains(u)){
            rpayment.remove(u);
        }else {
            rpayment.add(u);
        }
    }

    public static void setRPaymentPermission(UUID u, boolean value) {
        if (value){
            rpayment.remove(u);
        }else {
            rpayment.add(u);
        }
    }
}