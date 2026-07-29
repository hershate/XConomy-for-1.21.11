package me.yic.xconomy.bench;

import me.yic.xconomy.data.DataFormat;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * “原始算法”基线 —— 逐行忠实拷贝优化前的 CommandCore.isDouble。
 *
 * 与优化后版本的唯一差异：字母检测用 s.matches(".*[a-zA-Z].*")（每次调用都编译正则），
 * 小数位检测每次都 Pattern.compile("\\.\\d+")。复用 DataFormat 的 isint/isMAX/formatString
 * （这些方法本次优化不改变行为），从而仅隔离 isDouble 自身的解析差异。
 */
public final class BaselineIsDouble {

    public static boolean isDouble(String s) {
        if (s.length() > 20) {
            return false;
        }
        if (s.matches(".*[a-zA-Z].*")) {
            return false;
        }

        BigDecimal value;
        if (DataFormat.isint) {
            try {
                Integer.parseInt(s);
                value = new BigDecimal(s);
            } catch (NumberFormatException ignored) {
                return false;
            }
        } else {
            try {
                Double.parseDouble(s);
                Pattern pattern = Pattern.compile("\\.\\d+");
                Matcher matcher = pattern.matcher(s);

                if (matcher.find()) {
                    String decimalPart = matcher.group();
                    int decimalPlaces = decimalPart.length() - 1;
                    if (decimalPlaces > 2) {
                        return false;
                    }
                }
                value = new BigDecimal(s);
            } catch (NumberFormatException ignored) {
                return false;
            }
        }

        if (value.compareTo(BigDecimal.ZERO) > 0) {
            return !DataFormat.isMAX(DataFormat.formatString(s));
        }
        return value.compareTo(BigDecimal.ZERO) == 0;
    }
}
