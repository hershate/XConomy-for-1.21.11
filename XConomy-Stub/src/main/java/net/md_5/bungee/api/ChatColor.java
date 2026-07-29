package net.md_5.bungee.api;

/*
 * 编译期桩（stub）。
 * 仅用于满足 XConomy-Core 中 DataFormat.PEshownf 对 ChatColor.translateAlternateColorCodes 的编译期引用。
 * 本桩不打包进 Bukkit/Paper jar（provided 依赖）；运行时由 Paper/Spigot 服务端内嵌的
 * 真实 net.md_5.bungee.api.ChatColor 提供实现，签名与此处保持一致。
 */
public class ChatColor {

    public static String translateAlternateColorCodes(char altColorChar, String textToTranslate) {
        char[] b = textToTranslate.toCharArray();
        for (int i = 0; i < b.length - 1; i++) {
            if (b[i] == altColorChar && "0123456789AaBbCcDdEeFfKkLlMmNnOoRr".indexOf(b[i + 1]) > -1) {
                b[i] = '§';
                b[i + 1] = Character.toLowerCase(b[i + 1]);
            }
        }
        return new String(b);
    }
}
