package com.qinghuacisj.gs.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * JavaScript 的<b>数字 → 字符串</b>语义（{@code Number.prototype.toFixed} 与
 * {@code String(x)}）的等价实现。
 *
 * <p>★★ <b>为什么需要这个类</b>：原脚本用 JS 写日志行 / lore，里面有
 * {@code x.toFixed(1)} 和 {@code '' + x}。Java 的「看起来一样」的 API
 * <b>语义不同</b>，直接换会写出<b>不一样的日志行</b>。这个类把两条语义
 * 精确实现出来，并用 {@code tools/JsNumParityProbe.java} 在真 JS 上逐字节对账。
 *
 * <h2>① {@code toFixed(f)} —— 不是 {@code String.format}</h2>
 * <table border="1">
 *   <tr><th>输入</th><th>JS {@code toFixed}</th><th>Java 的直觉做法</th></tr>
 *   <tr><td>{@code (-2.5).toFixed(0)}</td><td>{@code "-3"}</td>
 *       <td>{@code "-2"}（{@code HALF_EVEN}）</td></tr>
 *   <tr><td>{@code (-0.05).toFixed(0)}</td><td>{@code "-0"}</td>
 *       <td>{@code "0"}（<b>丢了负号</b>）</td></tr>
 *   <tr><td>{@code (-2.05).toFixed(1)}</td><td>{@code "-2.0"}</td>
 *       <td>{@code "-2.1"}（若按最短表示 {@code -2.05} 舍入）</td></tr>
 *   <tr><td>{@code (1.005).toFixed(2)}</td><td>{@code "1.00"}</td>
 *       <td>{@code "1.01"}（同上）</td></tr>
 * </table>
 * <p><b>正确语义</b>：对<b>精确二进制值</b>取绝对值后 <b>HALF_UP</b>，再补回符号
 * （含负零特判）。
 * <p>★ 关键：必须用 {@code new BigDecimal(double)}（<b>精确</b>构造器），
 * <b>不能</b>用 {@link BigDecimal#valueOf(double)} —— 后者走 {@code Double.toString}
 * （最短往返表示），会把 {@code -2.05} 当成精确的 {@code -2.05}，
 * 而它的真实值是 {@code -2.0499999999999998223…}。
 *
 * <h2>② {@code String(x)} —— 不是 {@code Double.toString}</h2>
 * <table border="1">
 *   <tr><th>输入</th><th>JS {@code String(x)}</th><th>{@code Double.toString(x)}</th></tr>
 *   <tr><td>{@code 1000.0}</td><td>{@code "1000"}</td><td>{@code "1000.0"} ❌</td></tr>
 *   <tr><td>{@code 4.9e-324}</td><td>{@code "5e-324"}</td><td>{@code "4.9E-324"} ❌</td></tr>
 *   <tr><td>{@code 1e21}</td><td>{@code "1e+21"}</td><td>{@code "1.0E21"} ❌</td></tr>
 *   <tr><td>{@code -0.0}</td><td>{@code "0"}</td><td>{@code "-0.0"} ❌</td></tr>
 * </table>
 * <p><b>正确语义</b>（ECMA-262 {@code Number::toString}）：最短可往返十进制表示；
 * 指数 {@code k} 满足 {@code -6 ≤ k ≤ 21} 走普通记数法，否则走指数记数法
 * （<b>小写 {@code e}</b>、指数<b>带符号</b>、<b>无多余前导零</b>）；
 * {@code ±0 → "0"}、{@code NaN → "NaN"}、{@code ±Infinity → "±Infinity"}；
 * 整数值<b>不带 {@code .0}</b>。
 *
 * <p>★ 本类<b>无状态、无副作用</b>，可以直接给任何模块用。
 */
public final class JsNum {

    private JsNum() {
    }

    /**
     * JS {@code Number.prototype.toFixed(f)} 的等价实现。
     *
     * @param v 数值
     * @param f 小数位数（JS 会先 {@code ToIntegerOrInfinity}；这里按 {@code 0..100} 处理）
     * @return 与 JS 逐字节相同的字符串
     */
    public static String toFixed(double v, int f) {
        // JS 规范：非有限值直接走 ToString(x)，不补小数位
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        // JS 规范：|x| >= 10^21 时 toFixed 直接返回 ToString(x)
        if (Math.abs(v) >= 1.0e21) {
            return str(v);
        }
        int scale = f < 0 ? 0 : Math.min(f, 100);

        // ★ -0.0 不 < 0 ⇒ 不给负号（与 JS 一致：String(-0) === "0"）
        boolean neg = v < 0;
        // ★ 必须用精确构造器 new BigDecimal(double)
        BigDecimal bd = new BigDecimal(Math.abs(v))
                .setScale(scale, RoundingMode.HALF_UP);
        String s = bd.toPlainString();
        // ★ 负零特判：JS 里 (-0.05).toFixed(0) === "-0"
        if (neg && bd.signum() == 0) {
            return "-" + s;
        }
        return neg ? ("-" + s) : s;
    }

    /** JS {@code Number.prototype.toFixed()} 不带参数 ⇒ 等价于 {@code toFixed(0)}。 */
    public static String toFixed(double v) {
        return toFixed(v, 0);
    }

    /**
     * JS {@code String(x)} / {@code '' + x} 的等价实现（{@code Number::toString}）。
     *
     * @param v 数值
     * @return 与 JS 逐字节相同的字符串
     */
    public static String str(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0.0) {
            return "0";                     // ★ -0.0 也返回 "0"
        }

        boolean neg = v < 0;
        double a = Math.abs(v);

        // ★ 先找**最短可往返**十进制表示。
        //   ⚠️ 不能用 Double.toString —— 它在次正规数上不是最短的：
        //      Double.toString(Double.MIN_VALUE) = "4.9E-324"，而 JS 是 "5e-324"
        //      （"5e-324" 只有 1 位有效数字，也能量往返回 MIN_VALUE ⇒ 更短 ⇒ JS 用它）。
        BigDecimal bd = shortestRoundTrip(a);
        String digits = bd.unscaledValue().abs().toString();
        int scale = bd.scale();
        // 去掉尾随零（并同步修正 scale），否则算出的指数会偏
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        int stripped = digits.length() - end;
        digits = digits.substring(0, end);
        scale -= stripped;

        int n = digits.length();
        // 值 = 0.digits × 10^(k+1)  ⇒  k = n - scale - 1
        int k = n - scale - 1;

        String out;
        if (k >= -6 && k <= 20) {
            // 普通记数法
            if (k >= n - 1) {
                // 整数，后面补 (k - (n-1)) 个零
                StringBuilder sb = new StringBuilder(digits);
                for (int i = 0; i < k - (n - 1); i++) {
                    sb.append('0');
                }
                out = sb.toString();
            } else if (k >= 0) {
                out = digits.substring(0, k + 1) + "." + digits.substring(k + 1);
            } else {
                StringBuilder sb = new StringBuilder("0.");
                for (int i = 0; i < -k - 1; i++) {
                    sb.append('0');
                }
                sb.append(digits);
                out = sb.toString();
            }
        } else {
            // 指数记数法：d.ddd e±k（小写 e、指数带符号、无前导零）
            String mant = (n == 1) ? digits : (digits.charAt(0) + "." + digits.substring(1));
            String sign = k >= 0 ? "+" : "-";
            out = mant + "e" + sign + Math.abs(k);
        }
        return neg ? ("-" + out) : out;
    }

    /**
     * 求 {@code a} 的<b>最短可往返</b>十进制表示（{@code a} 必须 > 0 且有限）。
     *
     * <p>做法：有效位数 {@code p} 从 1 试到 17，用 {@code HALF_EVEN} 舍入到 {@code p} 位，
     * 第一个能 {@code doubleValue()} 还原回原值的即为最短。
     * ★ 17 位十进制一定可以唯一确定一个 double ⇒ 循环必在 17 步内结束。
     */
    private static BigDecimal shortestRoundTrip(double a) {
        BigDecimal exact = new BigDecimal(a);
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (r.doubleValue() == a) {
                return r;
            }
        }
        return exact;
    }

    /**
     * JS 里把数字拼进字符串的常见写法 {@code '' + (a/b)} 的便捷封装。
     * ★ 就是 {@link #str(double)}，加这个别名只是为了让调用点读起来更像原脚本。
     */
    public static String js(double v) {
        return str(v);
    }
}
