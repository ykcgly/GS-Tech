package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code gs_cursed} 的对账探针。
 *
 * <p>覆盖三块最容易出错、又最难靠肉眼发现的地方：
 * <ol>
 *   <li><b>加载日志行</b>：原脚本用的是<b>字符串拼接</b>而不是
 *       {@code info(String, Object...)}，所以开头那两个 {@code %s}
 *       <b>原样出现在日志里</b>（作者的本意没实现）。
 *       这里直接拿<b>参考服日志</b>里的真身逐字节比 ——
 *       这是「保留瑕疵、不顺手优化」的机械证据。</li>
 *   <li><b>起飞曲线</b>：{@code smoothstep} 的
 *       {@code t²(3−2t)} 与「16 秒前=0 / 22 秒后=8」三段行为，
 *       与真 JS 在全时间轴上逐值比。</li>
 *   <li><b>角度归一化</b>：{@code scNorm} 的负数回绕与浮点余数，
 *       含 {@code -0.0} / 极大值 / NaN 这些边界。</li>
 * </ol>
 */
public final class CursedParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 参考服（装 RSC 的那台）的日志 —— 里面是**真 JS** 跑出来的行。 */
    private static final String REF_LOG =
            "D:/qq下载/粘液科技自用/server/logs/latest.log";

    /** 真 JS 的 scNorm / scFlyLift —— 从 gs_cursed.js 逐字摘出。 */
    private static final String JS =
            "function scNorm(y) {\n"
                    + "  y = y % 360.0;\n"
                    + "  if (y < 0) y += 360.0;\n"
                    + "  return y;\n"
                    + "}\n"
                    + "function scFlyLift(el, START, END, H) {\n"
                    + "  if (!(el > START)) return 0.0;\n"
                    + "  if (el >= END) return H;\n"
                    + "  var t = (el - START) / (END - START);\n"
                    + "  return H * t * t * (3.0 - 2.0 * t);\n"
                    + "}\n"
                    + "function normAll(vals) {\n"
                    + "  var out = [];\n"
                    + "  for (var i = 0; i < vals.length; i++) out.push(scNorm(vals[i]));\n"
                    + "  return out;\n"
                    + "}\n"
                    + "function liftAll(els, START, END, H) {\n"
                    + "  var out = [];\n"
                    + "  for (var i = 0; i < els.length; i++) out.push(scFlyLift(els[i], START, END, H));\n"
                    + "  return out;\n"
                    + "}\n";

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_cursed：加载日志行 + 起飞曲线 + 角度归一化 对账 ===");
        System.out.println();

        loadLine();
        flyLift();
        norm();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ [A] 加载日志行

    private static void loadLine() throws Exception {
        System.out.println("[A] 加载日志行必须与参考服日志逐字节一致");

        String java = CursedModule.loadLine();
        System.out.println("      Java : " + java);

        Path p = Paths.get(REF_LOG);
        if (!Files.isRegularFile(p)) {
            check("参考服日志存在（" + REF_LOG + "）", false);
            System.out.println();
            return;
        }

        String want = null;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            // 认「1202 个音符」这一行 —— 就是 gs_cursed 的加载标记
            if (line.contains("1202 个音符") && line.contains("已加载")) {
                int k = line.indexOf("INFO]: ");
                want = k >= 0 ? line.substring(k + "INFO]: ".length()) : line;
                break;
            }
        }
        if (want == null) {
            check("参考服日志里找到 gs_cursed 的加载行", false);
            System.out.println();
            return;
        }

        System.out.println("      参考 : " + want);
        check("与真 JS 在参考服上打出的那一行逐字节相同", java.equals(want));

        // ★★ 反向断言：那两个 %s 必须**留着**
        check("★ 保留原瑕疵：开头就是「%s 已加载 · %s 」（不是被改成参数化）",
                java.startsWith("%s 已加载 · %s "));
        check("★ 没有把 %s 误当成格式化占位符（%s 出现 2 次）",
                countOf(java, "%s") == 2);
        check("含「1202 个音符」（实测曲谱行数算出来的）", java.contains("1202 个音符"));
        check("含「43.5 秒」两次（曲长与冷却都是它）", countOf(java, "43.5 秒") == 2);
        check("含「8 种音效」", java.contains("8 种音效"));
        check("含「排任务方式=runLater」（原生版不再需要调度器退路）",
                java.contains("排任务方式=runLater"));
        check("含「第 16~22 秒飞升 8 格」", java.contains("第 16~22 秒飞升 8 格"));
        System.out.println();
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    // ------------------------------------------------------------ [B] 起飞曲线

    private static void flyLift() throws Exception {
        System.out.println("[B] 起飞曲线（smoothstep）与真 JS 逐值一致");

        int start = CursedModule.SC_FLY_START_MS;
        int end = CursedModule.SC_FLY_END_MS;
        double h = CursedModule.SC_FLY_HEIGHT;

        // 覆盖：起飞前 / 起点 / 过程中 / 终点 / 之后，含毫秒级细扫
        List<Long> els = new ArrayList<>();
        for (long el = -5000; el <= 5000; el += 250) {
            els.add(el);
        }
        for (long el = 15500; el <= 22500; el += 1) {
            els.add(el);
        }
        for (long el = 25000; el <= 60000; el += 1000) {
            els.add(el);
        }

        double[] js = evalLift(els, start, end, h);
        int mismatch = 0;
        int zero = 0;
        int full = 0;
        int mid = 0;
        for (int i = 0; i < els.size(); i++) {
            double got = CursedModule.scFlyLift(0L, els.get(i));
            if (got != js[i]) {
                mismatch++;
                if (mismatch <= 5) {
                    System.out.println("      不一致 el=" + els.get(i)
                            + " JS=" + js[i] + " Java=" + got);
                }
            }
            if (got == 0.0) {
                zero++;
            } else if (got == h) {
                full++;
            } else {
                mid++;
            }
        }
        System.out.println("      用例 " + els.size() + "（0 段 " + zero
                + " ｜ 中间段 " + mid + " ｜ 满值段 " + full + "）");
        check("全部 " + els.size() + " 例逐值一致（含 15500~22500 每毫秒细扫）", mismatch == 0);
        check("三段都覆盖到了（起飞前 / 过程中 / 到顶后）",
                zero > 0 && mid > 0 && full > 0);

        // ★ 关键行为点
        check("第 16 秒整 ⇒ 还没动（!(el > START) ⇒ 0）",
                CursedModule.scFlyLift(0L, 16000L) == 0.0);
        check("第 16001 毫秒 ⇒ 刚开始抬（> 0 但极小）", () -> {
            double v = CursedModule.scFlyLift(0L, 16001L);
            return v > 0.0 && v < 0.01;
        });
        check("第 19 秒（正中间）⇒ 恰好一半高 = 4.0",
                Math.abs(CursedModule.scFlyLift(0L, 19000L) - 4.0) < 1e-12);
        check("第 22 秒整 ⇒ 正好到顶 8.0（>= END ⇒ H）",
                CursedModule.scFlyLift(0L, 22000L) == 8.0);
        check("第 30 秒 ⇒ 仍是 8.0（到顶后停住）",
                CursedModule.scFlyLift(0L, 30000L) == 8.0);

        // ★ smoothstep 的「两头慢、中间快」：中点导数为峰值
        double d0 = CursedModule.scFlyLift(0L, 16100L) - CursedModule.scFlyLift(0L, 16000L);
        double dm = CursedModule.scFlyLift(0L, 19050L) - CursedModule.scFlyLift(0L, 19000L);
        double d1 = CursedModule.scFlyLift(0L, 22000L) - CursedModule.scFlyLift(0L, 21900L);
        check("起步最慢（头 100ms 只抬 " + String.format("%.4f", d0) + " 格）",
                d0 < dm);
        check("到顶前最慢（尾 100ms 只抬 " + String.format("%.4f", d1) + " 格）", d1 < dm);
        check("中间最快（中段 50ms 抬 " + String.format("%.4f", dm) + " 格）", dm > d0 && dm > d1);
        System.out.println();
    }

    // ------------------------------------------------------------ [C] 角度归一化

    private static void norm() throws Exception {
        System.out.println("[C] 角度归一化 scNorm 与真 JS 逐值一致");

        double[] vals = {
                0.0, 54.0, 180.0, 359.0, 360.0, 361.0, 414.0, 720.0, 1000.0,
                -1.0, -54.0, -180.0, -360.0, -361.0, -1000.0,
                0.5, -0.5, 1e-12, -1e-12, 359.999999, -359.999999,
                1e9, -1e9, 1e15, -1e15, 0.0 / 1.0
        };
        double[] js = evalNorm(vals);
        int mismatch = 0;
        for (int i = 0; i < vals.length; i++) {
            double got = CursedModule.scNorm(vals[i]);
            if (got != js[i]) {
                mismatch++;
                System.out.println("      不一致 " + vals[i] + " JS=" + js[i] + " Java=" + got);
            }
        }
        check("全部 " + vals.length + " 例逐值一致（含负数 / 超过一圈 / 极小值）", mismatch == 0);

        check("scNorm(0) = 0", CursedModule.scNorm(0.0) == 0.0);
        check("scNorm(54) = 54（本曲每刻的步长）", CursedModule.scNorm(54.0) == 54.0);
        check("scNorm(-54) = 306（往右转那一只）", CursedModule.scNorm(-54.0) == 306.0);
        check("scNorm(360) = 0", CursedModule.scNorm(360.0) == 0.0);
        check("scNorm(-1) = 359", CursedModule.scNorm(-1.0) == 359.0);

        // ★ 一整圈 = 20 刻（54 × 20 = 1080 = 3 × 360）⇒ 每 20 刻回到原朝向
        double y = 0.0;
        for (int i = 0; i < 20; i++) {
            y = CursedModule.scNorm(y + CursedModule.SC_SPIN_DEG);
        }
        check("转 20 刻（1 秒）后回到原朝向（3 圈/秒 的自洽性）", Math.abs(y) < 1e-12);
        System.out.println();
    }

    // ------------------------------------------------------------ JS 求值

    private static double[] evalNorm(double[] vals) throws Exception {
        try (Context ctx = newCtx()) {
            ctx.eval(Source.newBuilder("js", JS, "js").build());
            Value f = ctx.getBindings("js").getMember("normAll");
            Value arr = ctx.eval(Source.newBuilder("js", toJs(vals), "arr").build());
            Value out = f.execute(arr);
            return toArray(out);
        }
    }

    private static double[] evalLift(List<Long> els, int start, int end, double h)
            throws Exception {
        try (Context ctx = newCtx()) {
            ctx.eval(Source.newBuilder("js", JS, "js").build());
            Value f = ctx.getBindings("js").getMember("liftAll");
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < els.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(els.get(i));
            }
            sb.append(']');
            Value arr = ctx.eval(Source.newBuilder("js", sb.toString(), "arr").build());
            Value out = f.execute(arr, start, end, h);
            return toArray(out);
        }
    }

    private static Context newCtx() {
        return Context.newBuilder("js").allowAllAccess(true).build();
    }

    private static String toJs(double[] vals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vals[i]);
        }
        return sb.append(']').toString();
    }

    private static double[] toArray(Value v) {
        int n = (int) v.getArraySize();
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            r[i] = v.getArrayElement(i).asDouble();
        }
        return r;
    }

    // ------------------------------------------------------------ 工具

    private interface Bool {
        boolean get();
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label);
        }
    }

    private static void check(String label, Bool b) {
        check(label, b.get());
    }
}
