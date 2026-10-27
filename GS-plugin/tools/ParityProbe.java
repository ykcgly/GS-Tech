package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 行为对账探针 —— 把「已 Java 化的几何计算」与<b>原脚本真身</b>跑同一组输入，
 * 逐值比对。
 *
 * <p><b>为什么值得单独写</b>：{@code gs_rolling_log_ingot} 是阶段②里
 * <b>唯一已经跑在生产路径上</b>的模块（{@code items.yml:676} 直接引用它），
 * 而它最容易写错的地方就是几何：JS 是浮点除法，Java 默认是整数除法。
 * 肉眼审查已经抓到一次这类 bug，靠对账才能确认没有第二次。
 *
 * <p>对账内容：
 * <ol>
 *   <li>{@code gsRllFacing(yaw)} —— 20 个边界 yaw（含 44/45、134/135、224/225、314/315
 *       这些分界点，以及 360/450/-45/-90 这类需要取模的值）。</li>
 *   <li>区域格子集合 + 水平包围盒 —— 多种坐标 / 朝向 / 宽长组合。</li>
 *   <li>配置钳制 —— 确认 {@code width} 恒为奇数（这是几何等价的前提）。</li>
 * </ol>
 *
 * <p>JS 侧直接内嵌<b>原脚本里那两段代码的逐字副本</b>，不做任何改写。
 */
public final class ParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 原脚本里 gsRllFacing + 区域循环的逐字副本，外加一个把结果编码成字符串的壳。 */
    private static final String JS_HARNESS = String.join("\n",
            "function gsRllFacing(yaw) {",
            "    var y = ((yaw % 360) + 360) % 360;",
            "    if (y >= 315 || y < 45) return [0, 1];",
            "    if (y < 135) return [-1, 0];",
            "    if (y < 225) return [0, -1];",
            "    return [1, 0];",
            "}",
            "",
            "function __facing(yaw) {",
            "    var f = gsRllFacing(yaw);",
            "    return f[0] + ',' + f[1];",
            "}",
            "",
            "function __area(bx, by, bz, yaw, width, length) {",
            "    var f = gsRllFacing(yaw);",
            "    var fx = f[0], fz = f[1];",
            "    var lx = -fz, lz = fx;",
            "    var half = (width - 1) / 2;",
            "    var cells = {};",
            "    var minX = bx, maxX = bx, minZ = bz, maxZ = bz;",
            "    for (var d = 1; d <= length; d++) {",
            "        for (var w = -half; w <= half; w++) {",
            "            var cx = bx + fx * d + lx * w;",
            "            var cz = bz + fz * d + lz * w;",
            "            for (var dy = -1; dy <= 1; dy++) {",
            "                cells[cx + \",\" + (by + dy) + \",\" + cz] = true;",
            "            }",
            "            if (cx < minX) minX = cx;",
            "            if (cx > maxX) maxX = cx;",
            "            if (cz < minZ) minZ = cz;",
            "            if (cz > maxZ) maxZ = cz;",
            "        }",
            "    }",
            "    var keys = Object.keys(cells).sort();",
            "    return minX + '|' + maxX + '|' + minZ + '|' + maxZ + '|' + keys.join(';');",
            "}");

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_rolling_log_ingot：Java ↔ 原 JS 行为对账 ===");
        System.out.println();

        try (Context ctx = Context.newBuilder("js").allowAllAccess(true).build()) {
            ctx.eval(Source.newBuilder("js", JS_HARNESS, "JavaScript").build());
            Value bindings = ctx.getBindings("js");

            facingParity(bindings);
            areaParity(bindings);
        }

        clampInvariant();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 朝向

    private static void facingParity(Value b) {
        float[] yaws = {
                0f, 1f, 44f, 45f, 46f, 89f, 90f, 91f, 134f, 135f,
                136f, 179f, 180f, 181f, 224f, 225f, 226f, 269f, 270f, 271f,
                314f, 315f, 316f, 359f, 360f, 450f, 720f, -1f, -45f, -90f, -180f, -360f
        };
        int bad = 0;
        for (float yaw : yaws) {
            String js = b.getMember("__facing").execute(yaw).asString();
            int[] f = RollingLogIngotModule.facing(yaw);
            String java = f[0] + "," + f[1];
            if (!js.equals(java)) {
                bad++;
                System.out.println("      不一致 yaw=" + yaw + "  JS=" + js + "  Java=" + java);
            }
        }
        System.out.println("[1] gsRllFacing —— " + yaws.length + " 个 yaw");
        check("全部一致", bad == 0);
        System.out.println();
    }

    // ------------------------------------------------------------ 区域

    private static void areaParity(Value b) {
        int[][] pos = {
                {0, 64, 0}, {100, 70, -200}, {-37, 5, 999}, {1234567, 319, -7654321}
        };
        float[] yaws = {0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 359f, -90f, 450f};
        // ★ 只测奇数宽度：readCfg 的钳制保证 width 恒为奇数（见 clampInvariant）
        int[] widths = {1, 3, 5, 7, 9};
        int[] lengths = {1, 2, 5, 7};

        int n = 0;
        int bad = 0;
        for (int[] p : pos) {
            for (float yaw : yaws) {
                for (int w : widths) {
                    for (int len : lengths) {
                        String js = b.getMember("__area")
                                .execute(p[0], p[1], p[2], yaw, w, len).asString();

                        RollingLogIngotModule.Area a =
                                RollingLogIngotModule.computeArea(p[0], p[1], p[2], yaw, w, len);
                        Set<String> sorted = new TreeSet<>(a.cells);
                        String java = a.minX + "|" + a.maxX + "|" + a.minZ + "|" + a.maxZ
                                + "|" + String.join(";", sorted);

                        n++;
                        if (!js.equals(java)) {
                            bad++;
                            if (bad <= 5) {
                                System.out.println("      不一致 pos=" + p[0] + "," + p[1] + "," + p[2]
                                        + " yaw=" + yaw + " w=" + w + " len=" + len);
                                System.out.println("        JS  =" + js);
                                System.out.println("        Java=" + java);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("[2] 区域格子 + 包围盒 —— " + n + " 组输入");
        check("全部一致", bad == 0);
        System.out.println();
    }

    // ------------------------------------------------------------ 钳制

    private static void clampInvariant() {
        System.out.println("[3] 配置钳制（width 必须恒为奇数 —— 几何等价的前提）");
        int[] widths = {-5, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8, 100, 101};
        List<String> bad = new ArrayList<>();
        for (int w : widths) {
            RollingLogIngotModule.Cfg c = new RollingLogIngotModule.Cfg();
            c.width = w;
            c.length = -3;
            c.invisSeconds = 0;
            c.cooldownSeconds = -9;
            RollingLogIngotModule.clamp(c);
            boolean ok = c.width >= 1 && c.width % 2 == 1
                    && c.length >= 1 && c.invisSeconds >= 1 && c.cooldownSeconds >= 0;
            if (!ok) {
                bad.add("width=" + w + " -> " + c.width);
            }
        }
        System.out.println("      测了 " + widths.length + " 个宽度");
        check("钳制后 width 恒为奇数且 >= 1，其余字段也在合法域内", bad.isEmpty());

        // 默认值必须与原脚本的字面量一致
        RollingLogIngotModule.Cfg d = new RollingLogIngotModule.Cfg();
        check("默认值 3 / 5 / 10 / 9（与原脚本对象字面量一致）",
                d.width == 3 && d.length == 5 && d.invisSeconds == 10 && d.cooldownSeconds == 9);
        System.out.println();
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
}
