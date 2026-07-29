/*
 *  This file (DataFormat.java) is a part of project XConomy
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

import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.adapter.comp.CChat;
import me.yic.xconomy.info.DefaultConfig;
import net.md_5.bungee.api.ChatColor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class DataFormat {

    public static boolean isint = false;
    public static DecimalFormat decimalFormat;
    public static DecimalFormat decimalFormatX;
    public static BigDecimal maxNumber;
    public static RoundingMode roundingmode = RoundingMode.DOWN;
    final static String displayformat = XConomyLoad.Config.DISPLAY_FORMAT;
    final static String pluralname = XConomyLoad.Config.PLURAL_NAME;
    final static String singularname = XConomyLoad.Config.SINGULAR_NAME;

    // ---- 余额展示结果缓存（性能优化，行为等价）----
    // shown/PEshownf 的输出仅取决于“余额值”与“load() 之后固定的格式化配置”
    // （displayformat / singular|pluralname / decimalFormat / format-balance / roundingmode）。
    // 在高重复访问场景（PlaceholderAPI、记分板、Tab 列表对同一玩家余额反复刷新）下，
    // 用按余额值的有界 LRU 缓存，把一次完整的格式化（BigDecimal 比较 + 多次 String.replace
    // + DecimalFormat + 颜色翻译）折叠为一次哈希查询。配置重载（load()）时清空。
    // BigDecimal 不可变且 equals/hashCode 按值，作为缓存键安全。
    private static final int SHOWN_CACHE_CAPACITY = 4096;
    private static final Map<BigDecimal, String> shownCache = boundedLruCache();
    private static final Map<BigDecimal, String> peShownCache = boundedLruCache();

    private static Map<BigDecimal, String> boundedLruCache() {
        return Collections.synchronizedMap(new LinkedHashMap<BigDecimal, String>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<BigDecimal, String> eldest) {
                return size() > SHOWN_CACHE_CAPACITY;
            }
        });
    }



    public static BigDecimal formatString(String am) {
        BigDecimal bigDecimal = new BigDecimal(am);
        if (isint) {
            return bigDecimal.setScale(0, roundingmode);
        } else {
            return bigDecimal.setScale(2, roundingmode);
        }
    }

    public static BigDecimal formatdouble(double am) {
        BigDecimal bigDecimal = BigDecimal.valueOf(am);
        if (isint) {
            return bigDecimal.setScale(0, roundingmode);
        } else {
            return bigDecimal.setScale(2, roundingmode);
        }
    }

    public static BigDecimal formatBigDecimal(BigDecimal am) {
        if (isint) {
            return am.setScale(0, roundingmode);
        } else {
            return am.setScale(2, roundingmode);
        }
    }

    public static String shown(BigDecimal am) {
        // 缓存命中：直接返回；未命中：计算后写入（多线程并发未命中会重复计算，结果幂等，安全）。
        String cached = shownCache.get(am);
        if (cached != null) {
            return cached;
        }
        String result = computeShown(am);
        shownCache.put(am, result);
        return result;
    }

    private static String computeShown(BigDecimal am) {
        if (am.compareTo(BigDecimal.ONE) == 0) {
            return CChat.translateAlternateColorCodes('&', displayformat
                    .replace("%balance%", decimalFormat.format(am))
                    .replace("%format_balance%", getformatbalance(am))
                    .replace("%currencyname%", singularname));
        }
        return CChat.translateAlternateColorCodes('&', displayformat
                .replace("%balance%", decimalFormat.format(am))
                .replace("%format_balance%", getformatbalance(am))
                .replace("%currencyname%", pluralname));
    }


    public static String shown(double am) {
        return shown(BigDecimal.valueOf(am));
    }

    public static String PEshownf(BigDecimal am) {
        String cached = peShownCache.get(am);
        if (cached != null) {
            return cached;
        }
        String result = computePEshownf(am);
        peShownCache.put(am, result);
        return result;
    }

    private static String computePEshownf(BigDecimal am) {
        if (am.compareTo(BigDecimal.ONE) == 0) {
            return ChatColor.translateAlternateColorCodes('&', displayformat
                    .replace("%balance%", getformatbalance(am))
                    .replace("%format_balance%", getformatbalance(am))
                    .replace("%currencyname%", singularname));
        }
        return ChatColor.translateAlternateColorCodes('&', displayformat
                .replace("%balance%", getformatbalance(am))
                .replace("%format_balance%", getformatbalance(am))
                .replace("%currencyname%", pluralname));
    }

    public static boolean isMAX(BigDecimal am) {
        return am.compareTo(maxNumber) > 0;
    }

    public static void load() {
        // 配置重载：展示结果可能改变，清空展示缓存。
        shownCache.clear();
        peShownCache.clear();
        maxNumber = setmaxnumber();
        isint = XConomyLoad.Config.INTEGER_BAL;
        String gpoint = XConomyLoad.Config.THOUSANDS_SEPARATOR;
        decimalFormat = new DecimalFormat();
        decimalFormatX = new DecimalFormat();

        decimalFormatX.setMinimumFractionDigits(2);
        decimalFormatX.setMaximumFractionDigits(2);

        if (isint) {
            decimalFormat.setMinimumFractionDigits(0);
            decimalFormat.setMaximumFractionDigits(0);
        }else{
            decimalFormat.setMinimumFractionDigits(2);
            decimalFormat.setMaximumFractionDigits(2);
        }

        if (XConomyLoad.Config.ROUNDING_MODE == 1){
            roundingmode = RoundingMode.UP;
        }

        if (gpoint != null && gpoint.length() == 1) {
            DecimalFormatSymbols spoint = new DecimalFormatSymbols();
            spoint.setGroupingSeparator(gpoint.charAt(0));
            decimalFormat.setDecimalFormatSymbols(spoint);
            decimalFormatX.setDecimalFormatSymbols(spoint);
        }

        XConomyLoad.Config.PAYMENT_TAX = setpaymenttax();
    }


    private static BigDecimal setmaxnumber() {
        String maxn = XConomyLoad.Config.MAX_NUMBER;
        BigDecimal defaultmaxnumber = new BigDecimal("10000000000000000");
        if (maxn == null) {
            return defaultmaxnumber;
        }
        if (maxn.length() > 17) {
            return defaultmaxnumber;
        }
        BigDecimal mnumber = new BigDecimal(maxn);
        if (mnumber.compareTo(defaultmaxnumber) >= 0) {
            return defaultmaxnumber;
        } else {
            return mnumber;
        }
    }


    private static BigDecimal setpaymenttax() {
        double pt = DefaultConfig.config.getDouble("Settings.payment-tax");
        if (pt < 0.0) {
            pt = 0.0;
        }
        return BigDecimal.valueOf(pt).add(BigDecimal.ONE);
    }

    private static String getformatbalance(BigDecimal bal) {
        if (XConomyLoad.Config.FORMAT_BALANCE != null) {
            if (bal.compareTo(XConomyLoad.Config.FORMAT_BALANCE.get(0)) < 0) {
                return decimalFormat.format(bal);
            }
            BigDecimal x = BigDecimal.ZERO;
            for (BigDecimal b : XConomyLoad.Config.FORMAT_BALANCE) {
                if (bal.compareTo(b) >= 0) {
                    x = b;
                } else {
                    break;
                }
            }
            BigDecimal aa = bal.divide(x, 3, roundingmode);
            return decimalFormatX.format(aa) + XConomyLoad.Config.FORMAT_BALANCE_C.get(x);
        } else {
            return decimalFormat.format(bal);
        }
    }
}
