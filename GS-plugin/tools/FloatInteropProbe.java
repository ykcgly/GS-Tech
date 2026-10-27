import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

/**
 * 独立复核「GraalJS 把 JS double 传给 Java float 形参时，非 float 精确值会抛异常」这条结论。
 *
 * <p>★ 为什么要独立复核：本项目已经抓到过一次「子代理说『我 javap 验证过了』但结论是错的」
 * （`setDirection` 那件事）。这条结论如果成立，影响面很大（凡是把 JS 算出来的 double
 * 传进 Java float 形参的地方都会静默失效），所以必须自己跑一遍。
 *
 * <p>用**自定义的** Java 类（而不是 Bukkit 的 Location）来测，排除「Bukkit 类本身有什么特别之处」
 * 的可能；再用真 Bukkit 的 `Location#setRotation` 测一遍做交叉验证。
 */
public final class FloatInteropProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + what);
        } else {
            fail++;
            System.out.println("  失败 " + what + (detail == null ? "" : ("  ← " + detail)));
        }
    }

    /** 自定义目标类：只有 float 形参。 */
    public static final class FTarget {
        public float gotYaw;
        public float gotPitch;
        public int calls;

        public void setRotation(float yaw, float pitch) {
            gotYaw = yaw;
            gotPitch = pitch;
            calls++;
        }

        public void sound(String name, float vol, float pitch) {
            calls++;
        }
    }

    /** 对照类：double 形参。 */
    public static final class DTarget {
        public double gotYaw;
        public double gotPitch;
        public int calls;

        public void setRotation(double yaw, double pitch) {
            gotYaw = yaw;
            gotPitch = pitch;
            calls++;
        }
    }

    public static void main(String[] args) {
        System.out.println("=== 独立复核：GraalJS double → Java float 互操作 ===");
        System.out.println();

        // 用与 RSC 同款的 Context 配置
        Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowNativeAccess(true)
                .allowHostClassLoading(true)
                .build();

        Value jsTry = ctx.eval("js",
                "(function(target, method, a, b) {"
                        + "  try { target[method](a, b); return 'ok'; }"
                        + "  catch (e) { return 'THREW: ' + e; }"
                        + "})");

        FTarget ft = new FTarget();

        System.out.println("[A] 自定义类（float 形参）：哪些值能过？");
        System.out.println();

        // ---- 已知的 float 精确值 ----
        double[] floatExact = {0.0, 1.0, 1.5, 2.0, 0.5, 0.25, 0.75, 0.125, 270.5, 0.7};
        // ---- 已知的「看着整齐但其实不精确」的值 ----
        double[] notExact = {1.1, 1.4, 1.2, 1.6, 0.9, 0.8, 0.6, 1.3};

        System.out.println("  --- 声称「float 精确」的值 ---");
        for (double v : floatExact) {
            ft.calls = 0;
            Value r = jsTry.execute(ft, "setRotation", v, 0.0);
            String res = r.asString();
            boolean isExact = (float) v == v;
            String note = isExact ? "" : "  ⚠ 其实不精确！";
            System.out.println(String.format("    setRotation(%-8s, 0.0) → %-58s %s",
                    v, res.length() > 56 ? res.substring(0, 56) + "…" : res, note));
        }

        System.out.println();
        System.out.println("  --- 声称「非 float 精确」的值 ---");
        for (double v : notExact) {
            ft.calls = 0;
            Value r = jsTry.execute(ft, "setRotation", v, 0.0);
            String res = r.asString();
            boolean isExact = (float) v == v;
            System.out.println(String.format("    setRotation(%-8s, 0.0) → %-58s %s",
                    v, res.length() > 56 ? res.substring(0, 56) + "…" : res,
                    isExact ? "⚠ 其实是精确的！" : ""));
        }

        // ---- 结论性断言 ----
        System.out.println();
        ft.calls = 0;
        String exactRes = jsTry.execute(ft, "setRotation", 1.5, 0.0).asString();
        check("float 精确值 1.5 能过（1.5f == 1.5）",
                "ok".equals(exactRes) && ft.calls == 1, "实际 " + exactRes);

        ft.calls = 0;
        String inexactRes = jsTry.execute(ft, "setRotation", 1.1, 0.0).asString();
        check("★ 非 float 精确值 1.1 会抛（1.1f != 1.1）",
                inexactRes.startsWith("THREW") && ft.calls == 0, "实际 " + inexactRes);
        check("★ 异常信息里含 'lossy' 或 'Invalid'（与子代理报告一致）",
                inexactRes.contains("lossy") || inexactRes.contains("Invalid"),
                "实际 " + inexactRes);

        // ---- 对照：double 形参不受影响 ----
        System.out.println();
        System.out.println("[B] 对照：同样传 1.1 给 double 形参");
        DTarget dt = new DTarget();
        String dRes = jsTry.execute(dt, "setRotation", 1.1, 0.0).asString();
        check("★ double 形参能收 1.1（证明问题出在 float 上，不是 JS 数值本身）",
                "ok".equals(dRes) && dt.calls == 1 && dt.gotYaw == 1.1, "实际 " + dRes);

        // ---- 交叉验证：真 Bukkit Location#setRotation(float, float) ----
        System.out.println();
        System.out.println("[C] 交叉验证：真 org.bukkit.Location#setRotation(float, float)");
        try {
            Object loc = ctx.eval("js",
                    "Java.type('org.bukkit.Location') != null").asBoolean() ? null : null;
        } catch (Throwable ignored) {
        }
        try {
            Value mkLoc = ctx.eval("js", "(function(){ "
                    + "  var L = Java.type('org.bukkit.Location');"
                    + "  return new L(null, 0.0, 64.0, 0.0); })");
            Object locObj = mkLoc.execute();
            Value tryLoc = ctx.eval("js",
                    "(function(loc, yaw, pitch) {"
                            + "  try { loc.setRotation(yaw, pitch); return 'ok'; }"
                            + "  catch (e) { return 'THREW: ' + e; }"
                            + "})");
            String locExact = tryLoc.execute(locObj, 1.5, 0.0).asString();
            String locInexact = tryLoc.execute(locObj, 1.1, 0.0).asString();
            check("★ 真 Location#setRotation(1.5, 0.0) 能过",
                    "ok".equals(locExact), "实际 " + locExact);
            check("★★ 真 Location#setRotation(1.1, 0.0) 也会抛（确认真实 API 同样中招）",
                    locInexact.startsWith("THREW"), "实际 " + locInexact);
        } catch (Throwable t) {
            System.out.println("      （无法测真 Location： " + t + "）");
        }

        // ---- 反向：证明「如果 Java 侧形参是 double 就没这问题」 ----
        System.out.println();
        System.out.println("[D] 反向：把同样的调用改成 float 精确化之后就过了");
        ft.calls = 0;
        String fixed = jsTry.execute(ft, "setRotation", (double) (float) 1.1, 0.0).asString();
        check("★ (float)1.1 再传就过了（说明原脚本只要先转 float 就不会静默失效）",
                "ok".equals(fixed) && ft.calls == 1, "实际 " + fixed);

        ctx.close();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
