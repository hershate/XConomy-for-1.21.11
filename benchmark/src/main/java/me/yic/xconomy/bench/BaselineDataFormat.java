package me.yic.xconomy.bench;

import me.yic.xconomy.XConomyLoad;
import me.yic.xconomy.adapter.comp.CChat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;

/**
 * “原始算法”基线 —— 逐行忠实拷贝优化前的 DataFormat（无结果缓存、每次都做完整格式化）。
 *
 * 拥有独立的 DecimalFormat / 模板字段，从同一份 XConomyLoad.Config 取值，
 * 与优化后的 DataFormat 在相同配置下对比，仅隔离“是否缓存”这一变量。
 */
public final class BaselineDataFormat {

    public static boolean isint = false;
    public static DecimalFormat decimalFormat;
    public static DecimalFormat decimalFormatX;
    public static BigDecimal maxNumber;
    public static RoundingMode roundingmode = RoundingMode.DOWN;
    static final String displayformat = XConomyLoad.Config.DISPLAY_FORMAT;
    static final String pluralname = XConomyLoad.Config.PLURAL_NAME;
    static final String singularname = XConomyLoad.Config.SINGULAR_NAME;

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

    public static boolean isMAX(BigDecimal am) {
        return am.compareTo(maxNumber) > 0;
    }

    // ---- 以下为 shown 及其依赖，逐行拷贝自优化前 DataFormat ----

    public static String shown(BigDecimal am) {
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

    public static void load() {
        maxNumber = new BigDecimal("10000000000000000");
        isint = XConomyLoad.Config.INTEGER_BAL;
        String gpoint = XConomyLoad.Config.THOUSANDS_SEPARATOR;
        decimalFormat = new DecimalFormat();
        decimalFormatX = new DecimalFormat();

        decimalFormatX.setMinimumFractionDigits(2);
        decimalFormatX.setMaximumFractionDigits(2);

        if (isint) {
            decimalFormat.setMinimumFractionDigits(0);
            decimalFormat.setMaximumFractionDigits(0);
        } else {
            decimalFormat.setMinimumFractionDigits(2);
            decimalFormat.setMaximumFractionDigits(2);
        }

        if (XConomyLoad.Config.ROUNDING_MODE == 1) {
            roundingmode = RoundingMode.UP;
        }

        if (gpoint != null && gpoint.length() == 1) {
            DecimalFormatSymbols spoint = new DecimalFormatSymbols();
            spoint.setGroupingSeparator(gpoint.charAt(0));
            decimalFormat.setDecimalFormatSymbols(spoint);
            decimalFormatX.setDecimalFormatSymbols(spoint);
        }
    }
}
