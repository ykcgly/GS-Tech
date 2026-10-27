import com.qinghuacisj.gs.util.JsNum;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JsNum} 与<b>真 JS</b> 的逐字节对账。
 *
 * <p>★ 用真 GraalJS 当裁判（不是用 Java 自己再算一遍 —— 那就成了自证）。
 *
 * <p>覆盖：
 * <ul>
 *   <li><b>[A]</b> {@code toFixed}：一个「危险值」集合 × 精度 0..9。</li>
 *   <li><b>[B]</b> {@code String(x)}：整数 / 小数 / 极大 / 极小 / 负零 / 特殊值 /
 *       指数边界 {@code 1e20}（普通记数法）与 {@code 1e21}（指数记数法）、
 *       {@code 1e-6} 与 {@code 1e-7} 的边界。</li>
 *   <li><b>[C]</b> 随机网格：10000 个随机 double，两种转换都对账。</li>
 *   <li><b>[D]</b> 反向断言：钉住「Java 的直觉做法确实是错的」——
 *       证明这个类不是多余的。</li>
 * </ul>
 */
public final class JsNumParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + what);
        } else {
            fail++;
            System.out.println("      失败 " + what + (detail == null ? "" : ("  ← " + detail)));
        }
    }

    private static void check(String what, boolean ok) {
        check(what, ok, null);
    }

    public static void main(String[] args) {
        System.out.println("=== JsNum 与真 JS 逐字节对账 ===");
        System.out.println();

        Context ctx = Context.newBuilder("js").allowAllAccess(true).build();
        Value jsToFixed = ctx.eval("js", "(function(v,f){ return v.toFixed(f); })");
        Value jsString = ctx.eval("js", "(function(v){ return String(v); })");

        // ---- 危险值集合：正好 .5 的、负的、极小负零、精度边界 ----
        double[] danger = {
                0.0, -0.0, 1.0, -1.0, 0.5, -0.5, 1.5, -1.5, 2.5, -2.5,
                0.05, -0.05, 2.05, -2.05, 1.005, -1.005, 2.675, -2.675,
                0.125, -0.125, 1.0 / 3.0, 2.0 / 3.0, 1e-7, -1e-7,
                123.456, -123.456, 0.9999999, -0.9999999, 1e20, 1e21,
                4.9e-324, 1.7976931348623157e308, 0.1, 0.2, 0.3,
        };

        // ================================================================
        // [A] toFixed
        // ================================================================
        System.out.println("[A] toFixed：危险值 × 精度 0..9");
        int nA = 0, badA = 0;
        List<String> sampleA = new ArrayList<>();
        for (double v : danger) {
            for (int f = 0; f <= 9; f++) {
                String want = jsToFixed.execute(v, f).asString();
                String got = JsNum.toFixed(v, f);
                nA++;
                if (!want.equals(got)) {
                    badA++;
                    if (sampleA.size() < 6) {
                        sampleA.add(String.format("toFixed(%s, %d) JS=\"%s\" Java=\"%s\"", v, f, want, got));
                    }
                }
            }
        }
        check("toFixed 共 " + nA + " 例逐字节一致", badA == 0,
                badA == 0 ? null : (badA + " 例不一致，例如 " + sampleA));

        // 逐条钉住那几个最容易写错的
        String[][] pins = {
                {"-2.5", "0", "-3"}, {"-1.5", "0", "-2"}, {"-0.5", "0", "-1"},
                {"0.5", "0", "1"}, {"1.5", "0", "2"}, {"2.5", "0", "3"},
                {"-0.05", "0", "-0"}, {"-2.05", "1", "-2.0"}, {"-2.675", "2", "-2.67"},
                {"1.005", "2", "1.00"}, {"2.5", "2", "2.50"},
        };
        for (String[] p : pins) {
            double v = Double.parseDouble(p[0]);
            int f = Integer.parseInt(p[1]);
            check(String.format("toFixed(%s, %s) = \"%s\"", p[0], p[1], p[2]),
                    JsNum.toFixed(v, f).equals(p[2]), "实际 \"" + JsNum.toFixed(v, f) + "\"");
        }

        // 非有限值 / 超界
        check("toFixed(NaN, 2) = \"NaN\"", JsNum.toFixed(Double.NaN, 2).equals("NaN"));
        check("toFixed(+Inf, 2) = \"Infinity\"", JsNum.toFixed(Double.POSITIVE_INFINITY, 2).equals("Infinity"));
        check("toFixed(-Inf, 2) = \"-Infinity\"", JsNum.toFixed(Double.NEGATIVE_INFINITY, 2).equals("-Infinity"));
        check("toFixed(1e21, 2) 走 String() ⇒ \"1e+21\"", JsNum.toFixed(1e21, 2).equals("1e+21"));
        check("toFixed(-0.0, 2) = \"0.00\"（-0.0 不给负号）", JsNum.toFixed(-0.0, 2).equals("0.00"));

        // ================================================================
        // [B] String(x)
        // ================================================================
        System.out.println();
        System.out.println("[B] String(x)：整数 / 极小 / 极大 / 负零 / 指数边界");
        int nB = 0, badB = 0;
        List<String> sampleB = new ArrayList<>();
        for (double v : danger) {
            String want = jsString.execute(v).asString();
            String got = JsNum.str(v);
            nB++;
            if (!want.equals(got)) {
                badB++;
                if (sampleB.size() < 6) {
                    sampleB.add(String.format("String(%s) JS=\"%s\" Java=\"%s\"", v, want, got));
                }
            }
        }
        check("String(x) 共 " + nB + " 例逐字节一致", badB == 0,
                badB == 0 ? null : (badB + " 例不一致，例如 " + sampleB));

        String[][] pinsB = {
                {"1000.0", "1000"}, {"4.9e-324", "5e-324"}, {"1e21", "1e+21"},
                {"0.0", "0"}, {"-0.0", "0"}, {"1e20", "100000000000000000000"},
                {"1e-6", "0.000001"}, {"1e-7", "1e-7"}, {"1e-5", "0.00001"},
                {"123.0", "123"}, {"-1.5", "-1.5"}, {"1e22", "1e+22"},
                {"1.7976931348623157e308", "1.7976931348623157e+308"},
        };
        for (String[] p : pinsB) {
            double v = Double.parseDouble(p[0]);
            check(String.format("String(%s) = \"%s\"", p[0], p[1]),
                    JsNum.str(v).equals(p[1]), "实际 \"" + JsNum.str(v) + "\"");
        }

        // 指数边界：k = 20 普通 / k = 21 指数
        check("k=20 边界：1e20 走普通记数法（21 位数字）",
                JsNum.str(1e20).equals("100000000000000000000"));
        check("k=21 边界：1e21 走指数记数法", JsNum.str(1e21).equals("1e+21"));
        check("k=-6 边界：1e-6 走普通记数法", JsNum.str(1e-6).equals("0.000001"));
        check("k=-7 边界：1e-7 走指数记数法", JsNum.str(1e-7).equals("1e-7"));
        check("指数是两位以上时无前导零：1e-10", JsNum.str(1e-10).equals("1e-10"));

        // ================================================================
        // [C] 随机网格
        // ================================================================
        System.out.println();
        System.out.println("[C] 随机网格 20000 个 double × (toFixed(0..4) + String)");
        java.util.Random rnd = new java.util.Random(20261007L);
        int nC = 0, badC = 0;
        List<String> sampleC = new ArrayList<>();
        for (int i = 0; i < 20000; i++) {
            double v;
            int kind = i % 5;
            if (kind == 0) {
                v = (rnd.nextDouble() - 0.5) * 2e6;
            } else if (kind == 1) {
                v = (rnd.nextDouble() - 0.5) * 2e-6;
            } else if (kind == 2) {
                v = rnd.nextInt(2001) - 1000;                 // 整数（含负）
            } else if (kind == 3) {
                v = (rnd.nextInt(20001) - 10000) / 2.0;       // 正好 .0 / .5
            } else {
                v = (rnd.nextDouble() - 0.5) * 2e20;
            }
            String wantS = jsString.execute(v).asString();
            String gotS = JsNum.str(v);
            nC++;
            if (!wantS.equals(gotS) && sampleC.size() < 5) {
                badC++;
                sampleC.add(String.format("String(%s) JS=\"%s\" Java=\"%s\"", v, wantS, gotS));
            } else if (!wantS.equals(gotS)) {
                badC++;
            }
            for (int f = 0; f <= 4; f++) {
                String want = jsToFixed.execute(v, f).asString();
                String got = JsNum.toFixed(v, f);
                nC++;
                if (!want.equals(got)) {
                    badC++;
                    if (sampleC.size() < 5) {
                        sampleC.add(String.format("toFixed(%s,%d) JS=\"%s\" Java=\"%s\"", v, f, want, got));
                    }
                }
            }
        }
        check("随机网格共 " + nC + " 例逐字节一致", badC == 0,
                badC == 0 ? null : (badC + " 例不一致，例如 " + sampleC));

        // ================================================================
        // [D] 反向断言：证明「Java 的直觉做法」确实会在某些输入上出错
        //     ★ 用**经验搜索**找反例，而不是手写断言 —— 手写的容易想当然写错
        //       （本项目第一版就在这里写错了 3 条：`String.format("%.0f", -2.5)`
        //        其实**就是** "-3"，`BigDecimal.valueOf` 在那几个值上也恰好对）。
        // ================================================================
        System.out.println();
        System.out.println("[D] 反向断言：经验搜索「直觉做法」的反例");

        // 造一批用于搜索的值：危险值 + 随机
        List<Double> search = new ArrayList<>();
        for (double v : danger) {
            search.add(v);
        }
        java.util.Random r2 = new java.util.Random(4242L);
        for (int i = 0; i < 4000; i++) {
            int kind = i % 4;
            double v;
            if (kind == 0) {
                v = (r2.nextDouble() - 0.5) * 2e3;
            } else if (kind == 1) {
                v = (r2.nextInt(20001) - 10000) / 2.0;
            } else if (kind == 2) {
                v = (r2.nextDouble() - 0.5) * 2e-4;
            } else {
                v = Double.longBitsToDouble(r2.nextLong() & 0x7FEFFFFFFFFFFFFFL); // 任意正有限
            }
            if (!Double.isNaN(v) && !Double.isInfinite(v)) {
                search.add(v);
            }
        }

        // ① String.format("%.Nf") vs JS toFixed(N)
        int naiveFmtDiff = 0;
        String firstFmtDiff = null;
        for (double v : search) {
            for (int f = 0; f <= 4; f++) {
                String want = jsToFixed.execute(v, f).asString();
                String naive = String.format(java.util.Locale.ROOT, "%." + f + "f", v);
                if (!want.equals(naive)) {
                    naiveFmtDiff++;
                    if (firstFmtDiff == null) {
                        firstFmtDiff = String.format("toFixed(%s,%d) JS=\"%s\" String.format=\"%s\"",
                                v, f, want, naive);
                    }
                }
            }
        }
        check("★ 反证：String.format(\"%.Nf\") 在 " + search.size() + " 值 × 5 位上与 JS 有 "
                        + naiveFmtDiff + " 处不一致（⇒ 不能直接用它）",
                naiveFmtDiff > 0, "首个反例：" + firstFmtDiff);

        // ② Double.toString vs JS String()
        int naiveStrDiff = 0;
        String firstStrDiff = null;
        for (double v : search) {
            String want = jsString.execute(v).asString();
            String naive = Double.toString(v);
            if (!want.equals(naive)) {
                naiveStrDiff++;
                if (firstStrDiff == null) {
                    firstStrDiff = String.format("String(%s) JS=\"%s\" Double.toString=\"%s\"",
                            v, want, naive);
                }
            }
        }
        check("★ 反证：Double.toString 在 " + search.size() + " 值上与 JS 有 "
                        + naiveStrDiff + " 处不一致（⇒ 不能直接用它）",
                naiveStrDiff > 0, "首个反例：" + firstStrDiff);

        // ③ BigDecimal.valueOf（最短表示）再精确舍入 vs JS toFixed
        //    —— 这条**不一定**有反例，如实报告
        int naiveBdDiff = 0;
        String firstBdDiff = null;
        for (double v : search) {
            for (int f = 0; f <= 4; f++) {
                String want = jsToFixed.execute(v, f).asString();
                String naive;
                try {
                    boolean neg = v < 0;
                    naive = new BigDecimal(Math.abs(v))
                            .setScale(f, java.math.RoundingMode.HALF_UP).toPlainString();
                    if (neg && !naive.equals("0") && new BigDecimal(Math.abs(v))
                            .setScale(f, java.math.RoundingMode.HALF_UP).signum() != 0) {
                        naive = "-" + naive;
                    }
                } catch (Throwable t) {
                    continue;
                }
                if (!want.equals(naive)) {
                    naiveBdDiff++;
                    if (firstBdDiff == null) {
                        firstBdDiff = String.format("toFixed(%s,%d) JS=\"%s\" 简化版=\"%s\"",
                                v, f, want, naive);
                    }
                }
            }
        }
        // ★ 这条**只报告不断言** —— 如实记录「负零特判」到底有没有实际影响
        System.out.println("      （报告，不断言）简化版（不处理负零）与 JS 有 "
                + naiveBdDiff + " 处不一致"
                + (firstBdDiff == null ? "" : ("；首个：" + firstBdDiff)));

        // ④ 负零特判确实必要：(-0.05).toFixed(0) 必须给 "-0"
        check("★ 负零特判必要：(-0.05).toFixed(0) = \"-0\"（Java 的直觉做法给 \"0\"）",
                JsNum.toFixed(-0.05, 0).equals("-0")
                        && !new BigDecimal(0.05).setScale(0, java.math.RoundingMode.HALF_UP)
                        .toPlainString().equals("-0"));

        // ⑤ 精确构造器 vs 最短表示构造器：(-2.05).toFixed(1)
        String viaPrecise = JsNum.toFixed(-2.05, 1);
        check("★ 精确构造器必要：(-2.05).toFixed(1) = \"-2.0\"（用最短表示会得 \"-2.1\"）",
                viaPrecise.equals("-2.0"),
                "实际 \"" + viaPrecise + "\"");

        // ⑥ 最短往返搜索必要：String(MIN_VALUE) 必须是 "5e-324"
        check("★ 最短往返搜索必要：String(4.9e-324) = \"5e-324\"（Double.toString 给 \"4.9E-324\"）",
                JsNum.str(4.9e-324).equals("5e-324"),
                "实际 \"" + JsNum.str(4.9e-324) + "\"");

        // ⑦ 本类无状态：连调两次结果相同
        check("无副作用：连调两次 toFixed(-2.5,0) 结果相同",
                JsNum.toFixed(-2.5, 0).equals(JsNum.toFixed(-2.5, 0)));

        ctx.close();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
