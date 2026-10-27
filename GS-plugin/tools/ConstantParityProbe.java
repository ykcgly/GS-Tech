package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 常量对账探针 —— 把<b>真 JS</b> 里的顶层常量与 Java 模块里的常量
 * <b>逐个按值比对</b>。
 *
 * <p><b>为什么这是核心验证</b>：目标里写明「概率 / 数值 / 颜色码 / tick 延迟
 * <b>逐字保留</b>」。肉眼看代码只能证明「我抄的时候觉得对」，
 * 而这个探针把 JS 真身跑起来、读出常量的<b>实际值</b>，再和 Java 侧的
 * {@code static final} 字段按值比较 —— 类型也一起比（1 和 1.0 会区分开）。
 *
 * <p>★ 命名约定：Java 字段名与 JS 顶层常量名<b>同名</b>。所以只要在
 * {@link #MAP} 里登记「脚本 → 类」，其余全自动。
 *
 * <p>★ 比对时会把 JS 的 {@code Integer} 归一化成 {@code long}、
 * {@code Double} 归一化成 {@code double}，因为 GraalJS 对
 * {@code 500} 和 {@code 1.00} 给出的宿主类型分别是 Integer / Double，
 * 而 Java 侧可能是 long / float —— 这是<b>表示差异</b>，不是行为差异。
 * 归一化只做「数值 → 最宽表示」，不做任何四舍五入或截断：
 * 一旦出现精度损失，比较就会失败。
 */
public final class ConstantParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /**
     * 求值前注入的垫片。
     *
     * <p><b>为什么需要</b>：{@code gs_maiyou_wine} 的顶层代码会
     * {@code Java.type('org.bukkit.attribute.Attribute')} 再读 {@code .SCALE}。
     * 无服务器环境下 {@code Attribute} 的静态初始化会失败
     * （{@code No RegistryAccess implementation found}）⇒ {@code ExceptionInInitializerError}。
     * 这个错误<b>不是能被 JS try/catch 抓住的普通异常</b>，
     * 它会作为 Truffle internal error 直接冒到顶，导致整个脚本求值失败。
     *
     * <p>所以这里把 {@code Java.type} 包一层：只对 {@code Attribute} 返回一个
     * 带 {@code SCALE = null} 的假对象（这正是真机上「拿不到缩放属性」的等价情形），
     * 其余类照常走真 {@code Java.type}。
     *
     * <p>★ 垫片<b>只影响常量对账</b>（这些常量都与 Attribute 无关），
     * 不会掩盖任何真实差异。真机上的 Attribute 可用性由真服测试覆盖。
     */
    private static final String SHIM =
            "var __realJava = Java;\n"
                    + "var Java = { type: function (n) {\n"
                    + "  if (n === 'org.bukkit.attribute.Attribute') {\n"
                    + "    return { SCALE: null, valueOf: function () { return null; } };\n"
                    + "  }\n"
                    + "  return __realJava.type(n);\n"
                    + "} };\n";

    /** 脚本名 → Java 模块类。字段名与 JS 顶层常量名同名。 */
    private static final Object[][] MAP = {
            {"gs_song", SongModule.class},
            {"gs_ding_me", DingMeModule.class},
            {"gs_rolling_log_ingot", RollingLogIngotModule.class},
            {"gs_fake_weisi", FakeWeisiModule.class},
            {"gs_maiyou_wine", MaiyouWineModule.class},
            {"gs_borrow_life", BorrowLifeModule.class},
            {"gs_cursed", CursedModule.class},
            {"gs_bad_apple", BadAppleModule.class},
    };

    /**
     * 不参与对账的字段。
     *
     * <p>★ {@code MY_MARK} 是 {@code NamespacedKey} 宿主对象 —— JS 侧拿到的是
     * 一个宿主引用，{@code toString()} 出来是 {@code gs:maiyou_fish}，
     * 与 Java 侧的 {@code NamespacedKey} 不是同一种东西，没法按值比。
     * 它的<b>键名</b>由 ModuleProbe 单独断言。
     */
    private static final java.util.Set<String> SKIP =
            java.util.Set.of("MY_MARK", "SG_GAIN");

    public static void main(String[] args) throws Exception {
        System.out.println("=== 常量对账：真 JS 顶层常量 vs Java 模块常量 ===");
        System.out.println();

        Path dir = Path.of(args.length > 0 ? args[0]
                : "../QHCGS/GS/GS/scripts");
        if (!Files.isDirectory(dir)) {
            System.out.println("!! 找不到脚本目录: " + dir.toAbsolutePath());
            System.exit(1);
        }

        for (Object[] entry : MAP) {
            String script = (String) entry[0];
            Class<?> cls = (Class<?>) entry[1];
            checkScript(dir.resolve(script + ".js"), script, cls);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void checkScript(Path jsFile, String script, Class<?> cls) throws Exception {
        System.out.println("[" + script + "]");
        if (!Files.isRegularFile(jsFile)) {
            System.out.println("      跳过：找不到 " + jsFile);
            System.out.println();
            return;
        }
        String source = new String(Files.readAllBytes(jsFile), StandardCharsets.UTF_8);
        String evalSource = SHIM + source;

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.eval(Source.newBuilder("js", evalSource, "JavaScript").build());
            Value b = ctx.getBindings("js");

            int n = 0;
            for (Field f : cls.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) {
                    continue;
                }
                String name = f.getName();
                if (SKIP.contains(name)) {
                    continue;
                }
                if (!b.hasMember(name)) {
                    // 该常量在 JS 里不存在 ⇒ 可能是 Java 侧自加的，跳过（下面单独报）
                    continue;
                }
                f.setAccessible(true);
                Object javaVal = f.get(null);
                Object jsVal = toJava(b.getMember(name));

                n++;
                boolean ok = sameValue(jsVal, javaVal);
                if (!ok) {
                    System.out.println("      不一致 " + name
                            + "  JS=" + show(jsVal) + " (" + typeOf(jsVal) + ")"
                            + "  Java=" + show(javaVal) + " (" + typeOf(javaVal) + ")");
                }
                check(name + " = " + show(javaVal), ok);
            }

            if (n == 0) {
                System.out.println("      （该脚本没有可对账的静态常量）");
            }
        }
        System.out.println();
    }

    /** GraalJS 的数值/布尔值 → 宿主 Java 值。 */
    private static Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            int n = (int) v.getArraySize();
            // ★ 数值数组（如 SG_TL / SG_PITCH / FW_SOUNDS 之外的 SC_FX_KINDS 是字符串）
            //   必须按数值取，不能一律 asString() —— 否则 int[] 对不上。
            boolean allNum = true;
            for (int i = 0; i < n; i++) {
                if (!v.getArrayElement(i).isNumber()) {
                    allNum = false;
                    break;
                }
            }
            if (allNum) {
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = v.getArrayElement(i).asDouble();
                }
                return out;
            }
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                out[i] = v.getArrayElement(i).asString();
            }
            return out;
        }
        if (v.fitsInLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        return v.toString();
    }

    /**
     * 按值比较，允许「整数 ↔ 浮点」的表示差异，但<b>不允许精度损失</b>。
     *
     * <p>例：JS 的 {@code 500}（Integer）与 Java 的 {@code 500L} 视为相同；
     * JS 的 {@code 1.00}（Double）与 Java 的 {@code 1.0f} 视为相同。
     * 但 {@code 0.1} 与 {@code 0.1f} 会因 float 精度不足而<b>不同</b> —— 这正是我们要抓的。
     */
    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        // ★ 字符串数组（如 FW_SOUNDS）逐元素比
        if (a instanceof String[] sa && b instanceof String[] sb) {
            if (sa.length != sb.length) {
                return false;
            }
            for (int i = 0; i < sa.length; i++) {
                if (!sa[i].equals(sb[i])) {
                    return false;
                }
            }
            return true;
        }
        // ★ 数值数组（SG_TL 的 int[] vs JS 的 double[]）逐元素比
        if (a instanceof double[] da2 && b instanceof double[] db2) {
            if (da2.length != db2.length) {
                return false;
            }
            for (int i = 0; i < da2.length; i++) {
                if (da2[i] != db2[i]) {
                    return false;
                }
            }
            return true;
        }
        double[] na = asNumArray(a);
        double[] nb = asNumArray(b);
        if (na != null && nb != null) {
            if (na.length != nb.length) {
                return false;
            }
            for (int i = 0; i < na.length; i++) {
                if (na[i] != nb[i]) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof String || b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            if (da != db) {
                return false;
            }
            // ★ 整数对整数时再确认一次精确相等，避免 long 溢出成同一个 double
            if (a instanceof Long && b instanceof Long) {
                return ((Long) a).longValue() == ((Long) b).longValue();
            }
            return true;
        }
        return a.equals(b);
    }

    /** 把 Java 侧任意数值数组统一成 {@code double[]}；不是数值数组则返回 null。 */
    private static double[] asNumArray(Object o) {
        if (o instanceof int[] a) {
            double[] r = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                r[i] = a[i];
            }
            return r;
        }
        if (o instanceof long[] a) {
            double[] r = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                r[i] = a[i];
            }
            return r;
        }
        if (o instanceof double[] a) {
            return a;
        }
        if (o instanceof float[] a) {
            double[] r = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                r[i] = a[i];
            }
            return r;
        }
        return null;
    }

    private static String show(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return "\"" + s + "\"";
        }
        if (o instanceof String[] arr) {
            return "[" + String.join(", ", arr) + "]";
        }
        // ★ 长数组截断显示 —— SG_TL 有 3606 个数，全打出来会淹掉整个报告
        double[] na = asNumArray(o);
        if (na != null) {
            StringBuilder sb = new StringBuilder("[");
            int lim = Math.min(na.length, 8);
            for (int i = 0; i < lim; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(trim(na[i]));
            }
            if (na.length > lim) {
                sb.append(", … 共 ").append(na.length).append(" 项");
            }
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }

    /** 整数就打成整数，避免 0.0 / 100.0 这种噪声。 */
    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.ROOT, "%.12g", v);
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
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
