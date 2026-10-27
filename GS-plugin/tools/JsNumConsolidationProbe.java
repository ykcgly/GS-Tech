package com.qinghuacisj.gs.tools;

import com.qinghuacisj.gs.util.JsNum;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 私有 {@code jsNum} / {@code toFixed} 副本与 {@link JsNum} 的**分歧实测**。
 *
 * <p>背景：18 个脚本分别移植时，多个模块各自带了一份
 * {@code jsNum} / {@code toFixed} 的私有拷贝（见 {@code 阶段2迁移台账.md}）。
 * 迁移完成后应当统一到 {@link JsNum} —— 但**先得知道换过去会不会改变行为**。
 * 这个探针就是回答这个问题的：把每个私有实现和 {@code JsNum} 放在同一张
 * 输入网格上逐值比对，**打印每一处分歧**。
 *
 * <p>★ 为什么用反射：这些方法是 {@code package-private} 的静态方法，
 * 分散在 {@code script.module} 与 {@code script.module.listener} 两个包里，
 * 一个探针类只能属于一个包。反射 {@code setAccessible(true)} 是唯一能一次覆盖全部的办法。
 *
 * <p>★ 判定方向：{@link JsNum} 是**按真 GraalJS 实测行为**实现的
 * （{@code JsNumParityProbe} 43/43 + 120,000 组随机对拍逐字节一致）。
 * 所以分歧 = **私有副本错了**，不是 JsNum 错了。
 *
 * <p>用法：
 * <pre>
 *   javac -cp libs/*;build/classes -d build/jnchk tools/JsNumConsolidationProbe.java
 *   java  -cp libs/*;build/classes;build/jnchk com.qinghuacisj.gs.tools.JsNumConsolidationProbe
 * </pre>
 */
public final class JsNumConsolidationProbe {

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> FAILED = new ArrayList<>();

    /** 一份待测副本：类名 + 方法名 + 方法形态。 */
    private static final class Copy {
        final String cls;
        final String method;
        /** 1 = jsNum(double)；2 = toFixed(double, int)；3 = toFixedN(double)（固定小数位）。 */
        final int kind;
        final int fixedDigits;

        Copy(String cls, String method, int kind) {
            this(cls, method, kind, 0);
        }

        Copy(String cls, String method, int kind, int fixedDigits) {
            this.cls = cls;
            this.method = method;
            this.kind = kind;
            this.fixedDigits = fixedDigits;
        }
    }

    private static final String MOD = "com.qinghuacisj.gs.script.module.";
    private static final String SUB = "com.qinghuacisj.gs.script.module.listener.";

    private static final Copy[] COPIES = {
            // --- jsNum 形态：对应 JS 的 String(x) ---
            new Copy(MOD + "PhantomModule", "jsNum", 1),
            new Copy(MOD + "ShadowModule", "jsNum", 1),
            new Copy(SUB + "EmSub", "jsNum", 1),
            new Copy(SUB + "MsSub", "jsNum", 1),
            new Copy(SUB + "PsychicSub", "jsNum", 1),
            new Copy(SUB + "SiSub", "jsNum", 1),
            new Copy(SUB + "VoSub", "jsNum", 1),
            new Copy(SUB + "WlSub", "jsNum", 1),
            new Copy(SUB + "WuliSub", "jsNum", 1),
            // --- toFixed(v, f) 形态 ---
            new Copy(MOD + "PhantomModule", "toFixed", 2),
            // --- toFixedN(v) 形态（固定小数位，位数写在方法名里）---
            new Copy(SUB + "CherrySub", "toFixed2", 3, 2),
            new Copy(SUB + "PsychicSub", "toFixed1", 3, 1),
            new Copy(SUB + "WuliSub", "toFixed1", 3, 1),
    };

    /**
     * 输入网格。
     *
     * <p>★ 故意覆盖四类「朴素实现会翻车」的值：
     * <ol>
     *   <li><b>小量级</b>（{@code < 1e-6}）：JS 切指数写法，Java 的 {@code Double.toString} 不同；</li>
     *   <li><b>1e-6 ~ 1e-5</b>：JS 用普通写法，Java 已切指数；</li>
     *   <li><b>大量级</b>（{@code >= 1e19}）：{@code (long) v} 会饱和到 {@code Long.MAX_VALUE}；</li>
     *   <li><b>toFixed 的 .5 边界</b>：Java 的 {@code %.Nf} 按十进制 HALF_UP，
     *       JS 按**二进制精确值**取整 ⇒ 2.675 这类会差一位。</li>
     * </ol>
     */
    private static double[] grid() {
        List<Double> v = new ArrayList<>();
        // 常规整数 / 小数
        for (double d : new double[]{0, 1, -1, 2, 3, 7, 11, 17, 35, 37, 45, 77, 99, 100, 233,
                450, 900, 996, 1000, 1500, 3000, 7000, 13000}) {
            v.add(d);
        }
        // 时长秒数（模块里真正会打印的那些：毫秒 / 1000.0）
        for (long ms : new long[]{500, 800, 1200, 1500, 1800, 2000, 3000, 4500, 5000, 7000,
                8000, 9000, 11000, 12000, 13000, 15000, 25000, 35000, 90000}) {
            v.add(ms / 1000.0);
        }
        // 小量级
        for (double d : new double[]{1e-7, 1.5e-7, 9.9e-7, 1e-6, 1.5e-6, 1e-5, 1.2e-5, 1e-4}) {
            v.add(d);
        }
        // 大量级
        for (double d : new double[]{1e15, 1e16, 1e17, 9.22e18, 1e19, 1e20, 1e21, 1.5e21, 1e22}) {
            v.add(d);
        }
        // 二进制不精确的小数 + toFixed 边界
        for (double d : new double[]{0.1, 0.2, 0.1 + 0.2, 1.1, 1.15, 1.005, 2.5, 2.675, 3.0,
                0.05, -0.05, -2.5, 8.575, 1.0000000000000002}) {
            v.add(d);
        }
        // 特殊值
        v.add(-0.0);
        v.add(Double.NaN);
        v.add(Double.POSITIVE_INFINITY);
        v.add(Double.NEGATIVE_INFINITY);
        v.add((double) Long.MAX_VALUE);
        v.add(Double.MIN_VALUE);
        v.add(Double.MAX_VALUE);

        double[] out = new double[v.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.get(i);
        }
        return out;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== 私有 jsNum / toFixed 副本 vs util/JsNum（分歧实测）===");
        System.out.println("★ 判定方向：JsNum 是按真 GraalJS 实测行为实现的（43/43 + 120,000 组随机对拍）");
        System.out.println("   ⇒ 分歧 = 私有副本错了，不是 JsNum 错了。");
        System.out.println();

        double[] grid = grid();
        System.out.println("输入网格: " + grid.length + " 个值 ｜ 待测副本: " + COPIES.length + " 份");
        System.out.println();

        for (Copy c : COPIES) {
            check(c, grid);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (!FAILED.isEmpty()) {
            System.out.println();
            System.out.println("★ 有分歧的副本（" + FAILED.size() + " 处）—— 换成 JsNum 会**改变行为**，"
                    + "且新行为更贴近 JS：");
            for (String s : FAILED) {
                System.out.println("    " + s);
            }
        }
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void check(Copy c, double[] grid) throws Exception {
        Class<?> k = Class.forName(c.cls);
        Method m;
        if (c.kind == 2) {
            m = k.getDeclaredMethod(c.method, double.class, int.class);
        } else {
            m = k.getDeclaredMethod(c.method, double.class);
        }
        m.setAccessible(true);
        if (!Modifier.isStatic(m.getModifiers())) {
            throw new IllegalStateException("不是静态方法: " + c.cls + "." + c.method);
        }

        int localFail = 0;
        List<String> samples = new ArrayList<>();

        for (double v : grid) {
            String got;
            String want;
            if (c.kind == 1) {
                got = (String) m.invoke(null, v);
                want = JsNum.str(v);
            } else if (c.kind == 2) {
                for (int f : new int[]{0, 1, 2}) {
                    got = (String) m.invoke(null, v, f);
                    want = JsNum.toFixed(v, f);
                    if (!got.equals(want)) {
                        localFail++;
                        if (samples.size() < 4) {
                            samples.add(String.format("%s.%s(%s, %d): 副本=%s  JsNum=%s",
                                    shortName(c.cls), c.method, lit(v), f, q(got), q(want)));
                        }
                    } else {
                        pass++;
                    }
                }
                continue;
            } else {
                got = (String) m.invoke(null, v);
                want = JsNum.toFixed(v, c.fixedDigits);
            }
            if (!got.equals(want)) {
                localFail++;
                if (samples.size() < 4) {
                    samples.add(String.format("%s.%s(%s): 副本=%s  JsNum=%s",
                            shortName(c.cls), c.method, lit(v), q(got), q(want)));
                }
            } else {
                pass++;
            }
        }

        String tag = (localFail == 0) ? "✅ 一致" : "❌ 分歧 " + localFail + " 处";
        System.out.printf("  %-34s %s%n", shortName(c.cls) + "." + c.method, tag);
        for (String s : samples) {
            System.out.println("        " + s);
            FAILED.add(s);
        }
        if (localFail > 0) {
            fail++;
        }
    }

    private static String shortName(String fqn) {
        return fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    private static String q(String s) {
        return "\"" + s + "\"";
    }

    /** 打印 double 的可读字面量（保留 -0.0 与特殊值）。 */
    private static String lit(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "+Inf" : "-Inf";
        }
        if (v == 0.0 && Double.doubleToRawLongBits(v) != 0L) {
            return "-0.0";
        }
        if (v == Double.MIN_VALUE) {
            return "MIN_VALUE";
        }
        if (v == Double.MAX_VALUE) {
            return "MAX_VALUE";
        }
        return Double.toString(v);
    }

    private JsNumConsolidationProbe() {
    }
}
