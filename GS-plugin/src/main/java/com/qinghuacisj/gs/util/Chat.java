package com.qinghuacisj.gs.util;

import org.bukkit.ChatColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 颜色码翻译 —— 等价于 RSC 的 {@code CMIChatColor.translate}。
 *
 * <p><b>等价性已逐项确证</b>（不是"大概一样"）：
 * RSC 的 translate 依次做 5 件事，其中 4 件对本附属是空操作：
 * <ol>
 *   <li>{@code processGradient} —— 要求文本含 <code>{#</code> 前缀。全 content 扫描：<b>0 次</b>。</li>
 *   <li>{@code {#RRGGBB}} / <code>{#RGB}</code> 十六进制 —— 要求含 <code>{#</code>。同上，0 次。</li>
 *   <li>{@code {#色名}} 自定义色名 —— 要求含 <code>{#</code>。同上，0 次。</li>
 *   <li><code>&#RRGGBB</code> 怪癖十六进制 —— 要求含 <code>&#</code>。全 content：<b>0 次</b>。</li>
 *   <li>官方 <code>#RRGGBB</code> 正则 —— 要求含 <code>#</code>。<b>有 1 处真值含 <code>#</code></b>
 *       （{@code items.yml} 里 GS_PHANTOM 的名字 <code>"&8…&k…#…"</code>，是故意做成乱码风的），
 *       但 <code>#</code> 后跟的不是 3/6 位十六进制 ⇒ 正则<b>零命中</b>（已按 Java 的变长
 *       lookbehind 语义逐字符复核）。</li>
 * </ol>
 * ⇒ 剩下唯一实际生效的一步就是 {@link ChatColor#translateAlternateColorCodes(char, String)}。
 *
 * <p>⚠ 若将来有人往 yml 里写了 {@code {#ff0000}} 这类 CMI 语法，本方法<b>不会</b>翻译它。
 * 届时需要补上渐变/十六进制分支 —— 现在不加，因为"顺手多实现"本身就是行为偏差风险。
 */
public final class Chat {

    private Chat() {
    }

    /** 翻译一行文本；null 进 null 出（与 RSC 一致）。 */
    public static String t(String text) {
        if (text == null) {
            return null;
        }
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    /** 翻译一组文本。 */
    public static List<String> t(List<String> lines) {
        if (lines == null) {
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (String s : lines) {
            out.add(t(s));
        }
        return out;
    }

    /** 去掉颜色码（用于比较/日志）。 */
    public static String strip(String text) {
        return text == null ? null : ChatColor.stripColor(text);
    }
}
