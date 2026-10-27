package com.qinghuacisj.gs.script.module;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两首红石音乐（{@code gs_cursed} 咒术回战 / {@code gs_bad_apple}）的
 * <b>曲谱数据溯源</b>与<b>加载日志</b>对账。
 *
 * <p>★★★ 为什么单独做这个探针：这两首曲子的曲谱一共 <b>112,992 个数</b>
 * （3606 + 109386），全是<b>从 JS 机械提取</b>的。
 * 手抄绝不可能抄对，所以这里做的是<b>逐元素比对</b>：
 * 直接读原 {@code .js} 文件、把 {@code SG_TL} 数组抠出来，
 * 和 Java 里的 {@code SG_TL} <b>一个一个数比</b>。
 * 只要有一个数错位 / 抄错，这里立刻红。
 *
 * <p>★ 另外两件事也在这里钉住：
 * <ol>
 *   <li><b>加载日志行</b>与<b>参考服（装 RSC 的那台）日志</b>逐字节相同 ——
 *       包括那两个照抄下来的 {@code %s} 瑕疵。</li>
 *   <li><b>曲谱自洽性</b>：时间戳单调不减、每个 {@code code} 都能解出合法的
 *       音效下标与音高序号（否则播放时会数组越界）。</li>
 * </ol>
 */
public final class SongDataParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static final String SCRIPTS = "D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/";
    private static final String REF_LOG = "D:/qq下载/粘液科技自用/server/logs/latest.log";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 红石音乐曲谱：数据溯源 + 加载日志 对账 ===");
        System.out.println();

        provenance("gs_cursed", "咒术回战", CursedModule.SG_TL, CursedModule.SG_SND,
                CursedModule.SG_N, CursedModule.SG_LEN_MS, CursedModule.loadLine(), "1202 个音符");
        System.out.println();
        provenance("gs_bad_apple", "Bad Apple", BadAppleModule.SG_TL, BadAppleModule.SG_SND,
                BadAppleModule.SG_N, BadAppleModule.SG_LEN_MS, BadAppleModule.loadLine(),
                "36462 个音符");

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 逐元素溯源

    private static void provenance(String script, String label, int[] java, String[] sounds,
            int n, int lenMs, String loadLine, String logKey) throws Exception {
        System.out.println("[" + script + "] " + label);

        Path js = Paths.get(SCRIPTS + script + ".js");
        if (!Files.isRegularFile(js)) {
            check("原脚本存在（" + js + "）", false);
            return;
        }
        int[] ref = extractTl(js);
        System.out.println("      JS 侧 " + ref.length + " 个数 ｜ Java 侧 " + java.length + " 个数");

        // ★★★ 逐元素比对 —— 本探针的核心
        check("元素个数相同（" + ref.length + "）", ref.length == java.length);
        int mismatch = -1;
        int bad = 0;
        for (int i = 0; i < Math.min(ref.length, java.length); i++) {
            if (ref[i] != java[i]) {
                if (mismatch < 0) {
                    mismatch = i;
                }
                bad++;
            }
        }
        if (bad > 0) {
            System.out.println("      首个不一致下标 " + mismatch
                    + "：JS=" + ref[mismatch] + " Java=" + java[mismatch]);
        }
        check("★ 与 JS 的 SG_TL 逐元素完全相同（" + ref.length + " 项，不一致 " + bad + "）", bad == 0);

        // 分组语义：每组三个数
        check("长度是 3 的整数倍（每组 [毫秒, code, 音量]）", java.length % 3 == 0);
        check("SG_N = " + n + " 与长度自洽", n == java.length / 3);
        check("SG_LEN_MS = " + lenMs + " 就是最后一个音符的时间戳",
                java[java.length - 3] == lenMs);

        // 时间戳单调不减（否则 while 的「一次性补放」会错序）
        boolean mono = true;
        for (int i = 3; i < java.length; i += 3) {
            if (java[i] < java[i - 3]) {
                mono = false;
                break;
            }
        }
        check("时间戳单调不减（保证「到点了就补放」的顺序正确）", mono);

        // 每个 code 都要能解出合法下标，否则播放时数组越界
        int badCode = 0;
        int maxSnd = -1;
        int maxPit = -1;
        for (int i = 1; i < java.length; i += 3) {
            int code = java[i];
            if (code < 0) {
                badCode++;
                continue;
            }
            maxSnd = Math.max(maxSnd, code / 32);
            maxPit = Math.max(maxPit, code % 32);
            if (code / 32 >= sounds.length || code % 32 >= 25) {
                badCode++;
            }
        }
        check("全部 code 都能解出合法音效下标 + 音高序号（异常 " + badCode + " 个）", badCode == 0);
        check("音效下标最大 " + maxSnd + " < " + sounds.length + " 种音效",
                maxSnd < sounds.length);
        check("音高序号最大 " + maxPit + " ≤ 24（音符盒只有 25 档）", maxPit <= 24);

        // 加载日志行 vs 参考服
        String want = refLine(logKey);
        if (want == null) {
            check("参考服日志里找到「" + logKey + "」那一行", false);
        } else {
            System.out.println("      Java : " + loadLine);
            System.out.println("      参考 : " + want);
            check("★ 加载日志与真 JS 在参考服上打出的那一行逐字节相同",
                    loadLine.equals(want));
            check("★ 保留原瑕疵：开头就是「%s 已加载 · %s 」（不是被改成参数化）",
                    loadLine.startsWith("%s 已加载 · %s "));
            check("★ 没有把 %s 误当成格式化占位符（%s 恰好 2 次）",
                    countOf(loadLine, "%s") == 2);
        }
        System.out.println();
    }

    /**
     * 从原 JS 里抠出 {@code SG_TL} 数组。
     *
     * <p>★ 用「找 {@code var SG_TL = [} 然后配对 {@code ]}」的方式，
     * 比正则更稳（数组里没有嵌套方括号，但配对写法不依赖这个假设）。
     */
    private static int[] extractTl(Path js) throws IOException {
        String t = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);
        int i = t.indexOf("var SG_TL = [");
        if (i < 0) {
            throw new IOException("找不到 var SG_TL = [");
        }
        int j = t.indexOf("];", i);
        if (j < 0) {
            throw new IOException("SG_TL 数组没有收尾");
        }
        Matcher m = Pattern.compile("-?\\d+").matcher(t.substring(i, j));
        List<Integer> out = new ArrayList<>();
        while (m.find()) {
            out.add(Integer.parseInt(m.group()));
        }
        int[] r = new int[out.size()];
        for (int k = 0; k < r.length; k++) {
            r[k] = out.get(k);
        }
        return r;
    }

    /** 从参考服日志里取「含 key 的那一行」去掉时间戳前缀后的正文。 */
    private static String refLine(String key) throws IOException {
        Path p = Paths.get(REF_LOG);
        if (!Files.isRegularFile(p)) {
            return null;
        }
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.contains(key) && line.contains("已加载")) {
                int k = line.indexOf("INFO]: ");
                return k >= 0 ? line.substring(k + "INFO]: ".length()) : line;
            }
        }
        return null;
    }

    private static int countOf(String s, String sub) {
        int c = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            c++;
            i += sub.length();
        }
        return c;
    }

    // ------------------------------------------------------------ 工具

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label);
        }
    }
}
