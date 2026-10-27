package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code gs_boss_shadow} 的对账探针 —— 把 {@link ShadowModule} 与<b>真 JS</b>逐字节 / 逐值比对。
 *
 * <p>四块断言（对应契约 §7）：
 * <ol>
 *   <li><b>[A] 常量对账</b>：用真 GraalJS 跑<b>原脚本文件本身</b>，读出顶层 {@code var}，
 *       与 Java 的 {@code static final} <b>按名按值</b>比对。★ 还做<b>完备性</b>检查：
 *       JS 里所有 {@code SB_*} 的数值 / 布尔 / 字符串常量都必须有对应的 Java 字段
 *       （漏抄一个常量会当场被抓）。</li>
 *   <li><b>[B] 加载日志行</b>：6 行与 {@code docs/参考服日志_构思技艺.txt} <b>逐字节</b>比对，
 *       并反向钉住「本脚本<b>没有</b> {@code gs_boss_shadow 已加载} 这一行」。</li>
 *   <li><b>[C] 关键数值 / 几何</b>：{@code sbSafeY} / {@code sbHeadroom} / {@code sbPassableAt}
 *       直接调<b>真脚本里的那几个函数</b>（喂一个 {@code java.lang.reflect.Proxy} 造的假世界），
 *       与 Java 在<b>上万个输入</b>上逐值比对；三段纯算式（前方点 / 蛆虫环 / 蠕虫速度）
 *       用<b>逐字摘出的 JS 副本</b>在大量输入上比对。</li>
 *   <li><b>[D] 反向断言</b>：把「不应该存在的东西」钉住（多出来的加载行、被混用的两个半径、
 *       一阶段的掉落、§5 前缀出现在血条标题上…）。</li>
 * </ol>
 *
 * <p>★ 假世界为什么要用 Proxy：{@code org.bukkit.Material} 在<b>无服务器</b>环境里
 * 一读就 {@code ExceptionInInitializerError}（{@code Registry} 没实现），
 * 所以假 {@code Block#getType()} 一律返回 {@code null} —— 这正好跳过
 * {@code mt.isAir()} 那一支，走到脚本真正生效的那条路
 * （{@code blk.isPassable()}）。这条<b>局限</b>已在交付说明里写明。
 */
public final class ShadowParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static final String SCRIPT_DIR = "D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts";
    private static final String REF_LOG =
            "D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt";

    /** 宿主对象（不是值），按名比不了 ⇒ 单独按 {@code toString()} 断言。 */
    private static final Set<String> HOST_FIELDS = Set.of(
            "SB_PDT", "SB_KEY", "SB_PROJ_KEY", "SB_OWN_KEY", "SB_CLOUD_KEY");

    /**
     * 原脚本里这些 {@code SB_*} 是<b>可变状态</b>（{@code var x = false} 的闩锁 / 缓存标志），
     * 不是常量 ⇒ Java 侧只要求「有同名 static 字段」，<b>不比初值</b>
     * （JS 初值 false 与 Java 的空集合不是同一种东西，硬比会造假失败）。
     */
    private static final Set<String> MUTABLE_JS_STATE = Set.of(
            "SB_REG_DEATH", "SB_REG_SPELL", "SB_REG_GUARD",
            "SB_P_BREATH_TRIED", "SB_MAXATTR_TRIED");

    /** 逐字摘出的 JS 副本：脚本里那三段<b>内联</b>算式（行号见注释）。 */
    private static final String JS_SNIPPETS =
            // JS 的数字转字符串：'' + 3.0 === "3"（本脚本 6 行日志全靠它）
            "function jsNum(v) { return '' + v; }\n"
                    // gs_boss_shadow.js:1571-1573（sbSummon）与 810-812（sbPullToFront）同一个算式
                    + "function frontXZ(bx, bz, yaw, ahead) {\n"
                    + "  var rad = yaw * Math.PI / 180.0;\n"
                    + "  var fx = bx + (-Math.sin(rad)) * ahead;\n"
                    + "  var fz = bz + (Math.cos(rad)) * ahead;\n"
                    + "  return [fx, fz];\n"
                    + "}\n"
                    // gs_boss_shadow.js:1043-1045（sbSpawnMaggotWave）
                    + "function maggotRingXZ(x, z, i, n) {\n"
                    + "  var ang = (i * 2.0 * Math.PI) / n;\n"
                    + "  var mx = x + Math.cos(ang) * 1.2;\n"
                    + "  var mz = z + Math.sin(ang) * 1.2;\n"
                    + "  return [mx, mz];\n"
                    + "}\n"
                    // gs_boss_shadow.js:1318-1325（sbTick 里蠕虫那一段）
                    + "function wormVel(dx, dy, dz, flat) {\n"
                    + "  var inv = 1.0 / flat;\n"
                    + "  var vy = 0.0;\n"
                    + "  if (dy > 0.6) vy = 0.22;\n"
                    + "  else if (dy < -0.6) vy = -0.22;\n"
                    + "  return [dx * inv * 0.30, vy, dz * inv * 0.30];\n"
                    + "}\n"
                    // gs_boss_shadow.js:1306（接触判定）
                    + "function wormTouch(flat, dy) {\n"
                    + "  return (flat <= 1.6 && Math.abs(dy) <= 2.0);\n"
                    + "}\n"
                    // gs_boss_shadow.js:1031-1035（sbFireArrow 的瞄准方向）
                    + "function arrowDir(fx, fy, fz, tx, ty, tz) {\n"
                    + "  var dx = tx - fx;\n"
                    + "  var dy = (ty + 1.0) - fy + 0.35;\n"
                    + "  var dz = tz - fz;\n"
                    + "  var l2 = dx * dx + dy * dy + dz * dz;\n"
                    + "  return [dx, dy, dz, l2];\n"
                    + "}\n"
                    // gs_boss_shadow.js:855-857（sbFireBreath 的瞄准方向，没有 +0.35）
                    + "function breathDir(fx, fy, fz, tx, ty, tz) {\n"
                    + "  var dx = tx - fx;\n"
                    + "  var dy = (ty + 1.0) - fy;\n"
                    + "  var dz = tz - fz;\n"
                    + "  var l2 = dx * dx + dy * dy + dz * dz;\n"
                    + "  return [dx, dy, dz, l2];\n"
                    + "}\n";

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_boss_shadow（暗影虫卵）对账 ===");
        System.out.println();

        Path js = Paths.get(SCRIPT_DIR, "gs_boss_shadow.js");
        if (!Files.isRegularFile(js)) {
            System.out.println("!! 找不到脚本: " + js.toAbsolutePath());
            System.exit(1);
        }

        try (Context ctx = newContext()) {
            ctx.eval(Source.newBuilder("js",
                    new String(Files.readAllBytes(js), StandardCharsets.UTF_8), "gs_boss_shadow.js").build());
            Value b = ctx.getBindings("js");

            constants(b);
            loadLines();
            geometry(ctx, b);
            reverse(ctx, b);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // [A] 常量对账
    // ==================================================================

    private static void constants(Value b) throws Exception {
        System.out.println("[A] 常量对账：真 JS 顶层 var vs ShadowModule 的 static final");

        // ---- ① JS 里所有 SB_* 的「值型」常量，必须在 Java 侧同名存在且等值 ----
        Set<String> jsNames = new TreeSet<>();
        for (String k : b.getMemberKeys()) {
            if (!k.startsWith("SB_") || HOST_FIELDS.contains(k)) {
                continue;
            }
            Value v = b.getMember(k);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isNumber() || v.isBoolean() || v.isString()) {
                jsNames.add(k);
            }
        }
        check("真 JS 里找到 " + jsNames.size() + " 个 SB_* 值型常量", jsNames.size() >= 55);

        int compared = 0;
        int mutable = 0;
        for (String name : jsNames) {
            Field f;
            try {
                f = ShadowModule.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                check("Java 侧存在字段 " + name, false);
                continue;
            }
            if (MUTABLE_JS_STATE.contains(name)) {
                // 可变状态：只要求同名 + static（Java 侧类型可以是集合 / 闩锁，值比不了）
                mutable++;
                check("可变状态 " + name + " 有同名 static 字段（"
                                + f.getType().getSimpleName() + "）",
                        Modifier.isStatic(f.getModifiers()));
                continue;
            }
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) {
                check("Java 常量 " + name + " 是 static final", false);
                continue;
            }
            f.setAccessible(true);
            Object javaVal = f.get(null);
            Object jsVal = toJava(b.getMember(name));
            compared++;
            boolean ok = sameValue(jsVal, javaVal);
            if (!ok) {
                System.out.println("      不一致 " + name
                        + "  JS=" + show(jsVal) + " (" + typeOf(jsVal) + ")"
                        + "  Java=" + show(javaVal) + " (" + typeOf(javaVal) + ")");
            }
            check(name + " = " + show(javaVal), ok);
        }
        check("★ 完备性：JS 里 " + jsNames.size() + " 个 SB_* 值型成员全部处理过"
                        + "（常量 " + compared + " 个逐值比 + 可变状态 " + mutable + " 个只查同名）",
                compared + mutable == jsNames.size());

        // ---- ② 宿主对象：按 key 字符串断言 ----
        check("SB_KEY 的 key = gs:shadowboss",
                "gs:shadowboss".equals(String.valueOf(ShadowModule.SB_KEY))
                        && "gs:shadowboss".equals(b.getMember("SB_KEY").toString()));
        check("SB_PROJ_KEY 的 key = gs:shadowboss_proj",
                "gs:shadowboss_proj".equals(String.valueOf(ShadowModule.SB_PROJ_KEY))
                        && "gs:shadowboss_proj".equals(b.getMember("SB_PROJ_KEY").toString()));
        check("SB_OWN_KEY 的 key = gs:shadowboss_owner",
                "gs:shadowboss_owner".equals(String.valueOf(ShadowModule.SB_OWN_KEY))
                        && "gs:shadowboss_owner".equals(b.getMember("SB_OWN_KEY").toString()));
        check("★ SB_CLOUD_KEY 是**第三个** key（gs:shadowboss_cloud）—— 云不会被 sbOurs 认成自己人",
                "gs:shadowboss_cloud".equals(String.valueOf(ShadowModule.SB_CLOUD_KEY))
                        && "gs:shadowboss_cloud".equals(b.getMember("SB_CLOUD_KEY").toString()));
        check("SB_PDT 就是 PersistentDataType（JS 侧是宿主 Class 引用）",
                ShadowModule.SB_PDT == org.bukkit.persistence.PersistentDataType.class
                        && b.getMember("SB_PDT").toString().contains("PersistentDataType"));
        System.out.println();
    }

    // ==================================================================
    // [B] 加载日志行
    // ==================================================================

    private static void loadLines() throws Exception {
        System.out.println("[B] 加载日志行必须与参考服日志逐字节一致");

        String[] java = ShadowModule.loadLines();
        for (String s : java) {
            System.out.println("      Java : " + s);
        }

        // ★★ 反向断言：本脚本没有「已加载」这一行
        check("★ 反向：本模块 6 行加载日志里**没有**任何一行含「已加载」",
                !anyContains(java, "已加载"));
        check("★ 反向：没有任何一行含「gs_boss_shadow」",
                !anyContains(java, "gs_boss_shadow"));
        check("本模块加载日志**恰好 6 行**（第 7 行不存在）", java.length == 6);

        Path p = Paths.get(REF_LOG);
        if (!Files.isRegularFile(p)) {
            check("参考服日志存在（" + REF_LOG + "）", false);
            System.out.println();
            return;
        }

        List<String> refAll = new ArrayList<>();
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            int k = line.indexOf("[构思技艺] 暗影");
            if (k >= 0) {
                refAll.add(line.substring(k));
            }
        }
        System.out.println("      参考 : 找到 " + refAll.size() + " 行「[构思技艺] 暗影…」");
        check("★ 参考服日志里恰好 6 行暗影虫卵日志（与模块行数相等）", refAll.size() == 6);

        // ★★ 反向断言：参考服日志里**不存在** gs_boss_shadow 已加载
        boolean hasLoadedLine = false;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            if (line.contains("gs_boss_shadow") && line.contains("已加载")) {
                hasLoadedLine = true;
            }
        }
        check("★ 反向：参考服日志里**不存在**「gs_boss_shadow 已加载」这一行", !hasLoadedLine);

        int n = Math.min(java.length, refAll.size());
        int mismatch = 0;
        for (int i = 0; i < n; i++) {
            boolean ok = java[i].equals(refAll.get(i));
            if (!ok) {
                mismatch++;
                System.out.println("      第 " + (i + 1) + " 行不一致");
                System.out.println("        Java : " + java[i]);
                System.out.println("        参考 : " + refAll.get(i));
                System.out.println("        Java 长度=" + java[i].length()
                        + " 参考长度=" + refAll.get(i).length());
            }
        }
        check("6 行全部逐字节相同（含 ｜ · ⇒ 全角括号 **加粗**）",
                mismatch == 0 && java.length == refAll.size());

        // ★ 逐行单点钉住（失败时能一眼看出是哪一行）
        String[] tags = {"三阶段目标血量", "追踪龙息弹", "暗影女皇：瞬移", "阶段推进", "反制", "掉落"};
        for (int i = 0; i < java.length && i < tags.length; i++) {
            check("第 " + (i + 1) + " 行 = " + tags[i], java[i].contains(tags[i]));
        }
        System.out.println();
    }

    private static boolean anyContains(String[] arr, String sub) {
        for (String s : arr) {
            if (s.contains(sub)) {
                return true;
            }
        }
        return false;
    }

    // ==================================================================
    // [C] 关键数值 / 几何
    // ==================================================================

    private static void geometry(Context ctx, Value b) throws Exception {
        System.out.println("[C] 关键数值 / 几何：与真 JS 在大量输入上逐值比对");

        // ---- ① sbSafeY / sbHeadroom / sbPassableAt：调**真脚本里的函数** ----
        Value jsSafeY = b.getMember("sbSafeY");
        Value jsHeadroom = b.getMember("sbHeadroom");
        Value jsPassable = b.getMember("sbPassableAt");
        check("真脚本里取到了 sbSafeY / sbHeadroom / sbPassableAt",
                jsSafeY != null && jsSafeY.canExecute()
                        && jsHeadroom != null && jsHeadroom.canExecute()
                        && jsPassable != null && jsPassable.canExecute());

        Object world = fakeWorld();
        int cases = 0;
        int bad = 0;

        // 落地高度扫描：x/z 各 -8..8，y0 58..72，height 1..3
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (double y0 = 58.0; y0 <= 72.0; y0 += 1.0) {
                    for (int h = 1; h <= 3; h++) {
                        double want = jsSafeY.execute(world, x, z, y0, h).asDouble();
                        double got = ShadowModule.sbSafeY((org.bukkit.World) world, x, z, y0, h);
                        cases++;
                        if (want != got) {
                            bad++;
                            if (bad <= 5) {
                                System.out.println("      sbSafeY 不一致 x=" + x + " z=" + z
                                        + " y0=" + y0 + " h=" + h
                                        + " JS=" + want + " Java=" + got);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("      sbSafeY 用例 " + cases + "（含 0.0 与负坐标）");
        check("sbSafeY 全部 " + cases + " 例逐值一致", bad == 0);

        // headroom
        int hc = 0;
        int hb = 0;
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int by = 60; by <= 68; by++) {
                    for (int h = 1; h <= 3; h++) {
                        boolean want = jsHeadroom.execute(world, x, z, by, h).asBoolean();
                        boolean got = ShadowModule.sbHeadroom((org.bukkit.World) world, x, z, by, h);
                        hc++;
                        if (want != got) {
                            hb++;
                            if (hb <= 5) {
                                System.out.println("      sbHeadroom 不一致 x=" + x + " z=" + z
                                        + " by=" + by + " h=" + h + " JS=" + want + " Java=" + got);
                            }
                        }
                    }
                }
            }
        }
        System.out.println("      sbHeadroom 用例 " + hc);
        check("sbHeadroom 全部 " + hc + " 例逐值一致", hb == 0);

        // passable
        int pc = 0;
        int pb = 0;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = 56; y <= 72; y++) {
                    boolean want = jsPassable.execute(world, x, y, z).asBoolean();
                    boolean got = ShadowModule.sbPassableAt((org.bukkit.World) world, x, y, z);
                    pc++;
                    if (want != got) {
                        pb++;
                        if (pb <= 5) {
                            System.out.println("      sbPassableAt 不一致 x=" + x + " y=" + y
                                    + " z=" + z + " JS=" + want + " Java=" + got);
                        }
                    }
                }
            }
        }
        System.out.println("      sbPassableAt 用例 " + pc + "（含「玻璃挡住」的列）");
        check("sbPassableAt 全部 " + pc + " 例逐值一致", pb == 0);

        // ---- ② 三段内联算式：逐字摘出的 JS 副本 ----
        ctx.eval(Source.newBuilder("js", JS_SNIPPETS, "snip").build());
        Value jsFront = b.getMember("frontXZ");
        Value jsRing = b.getMember("maggotRingXZ");
        Value jsVel = b.getMember("wormVel");
        Value jsTouch = b.getMember("wormTouch");
        Value jsArrow = b.getMember("arrowDir");
        Value jsBreath = b.getMember("breathDir");
        check("真 JS 副本函数就绪（frontXZ/maggotRingXZ/wormVel/wormTouch/arrowDir/breathDir）",
                jsFront != null && jsRing != null && jsVel != null
                        && jsTouch != null && jsArrow != null && jsBreath != null);

        // 前方点：全角度 × 多基准点
        int fc = 0;
        int fb = 0;
        for (double yaw = -720.0; yaw <= 720.0; yaw += 0.5) {
            for (double bx = -30.0; bx <= 30.0; bx += 7.5) {
                for (double ahead : new double[]{3.0, 2.0}) {
                    Value r = jsFront.execute(bx, 100.25, yaw, ahead);
                    double wx = r.getArrayElement(0).asDouble();
                    double wz = r.getArrayElement(1).asDouble();
                    double[] g = ShadowModule.frontXZ(bx, 100.25, yaw, ahead);
                    fc++;
                    if (wx != g[0] || wz != g[1]) {
                        fb++;
                        if (fb <= 3) {
                            System.out.println("      frontXZ 不一致 yaw=" + yaw + " bx=" + bx
                                    + " ahead=" + ahead + " JS=(" + wx + "," + wz + ")"
                                    + " Java=(" + g[0] + "," + g[1] + ")");
                        }
                    }
                }
            }
        }
        System.out.println("      frontXZ 用例 " + fc);
        check("frontXZ 全部 " + fc + " 例逐值一致（yaw −720…720 步长 0.5）", fb == 0);

        // 蛆虫环
        int rc = 0;
        int rb = 0;
        for (int i = 0; i < 4; i++) {
            for (double bx = -12.0; bx <= 12.0; bx += 1.5) {
                Value r = jsRing.execute(bx, -7.25, i, 4);
                double wx = r.getArrayElement(0).asDouble();
                double wz = r.getArrayElement(1).asDouble();
                double[] g = ShadowModule.maggotRingXZ(bx, -7.25, i, 4);
                rc++;
                if (wx != g[0] || wz != g[1]) {
                    rb++;
                    if (rb <= 3) {
                        System.out.println("      maggotRingXZ 不一致 i=" + i + " bx=" + bx
                                + " JS=(" + wx + "," + wz + ") Java=(" + g[0] + "," + g[1] + ")");
                    }
                }
            }
        }
        System.out.println("      maggotRingXZ 用例 " + rc);
        check("maggotRingXZ 全部 " + rc + " 例逐值一致", rb == 0);

        // 蠕虫速度（含 ±0.6 竖直阈值两侧 + 边界）
        int vc = 0;
        int vb = 0;
        double[] dys = {-2.0, -0.600001, -0.6, -0.599999, -0.5, 0.0, 0.5, 0.599999, 0.6, 0.600001, 2.0};
        for (double dx = -4.0; dx <= 4.0; dx += 0.7) {
            for (double dz = -4.0; dz <= 4.0; dz += 0.7) {
                double flat = Math.sqrt(dx * dx + dz * dz);
                if (flat <= 0.01) {
                    continue;
                }
                for (double dy : dys) {
                    Value r = jsVel.execute(dx, dy, dz, flat);
                    double[] g = ShadowModule.wormVel(dx, dy, dz, flat);
                    vc++;
                    for (int k = 0; k < 3; k++) {
                        if (r.getArrayElement(k).asDouble() != g[k]) {
                            vb++;
                            if (vb <= 3) {
                                System.out.println("      wormVel 不一致 dx=" + dx + " dy=" + dy
                                        + " dz=" + dz + " flat=" + flat + " k=" + k
                                        + " JS=" + r.getArrayElement(k).asDouble() + " Java=" + g[k]);
                            }
                            break;
                        }
                    }
                }
            }
        }
        System.out.println("      wormVel 用例 " + vc + "（含 dy = ±0.6 阈值两侧）");
        check("wormVel 全部 " + vc + " 例逐值一致", vb == 0);

        // 接触判定
        int tc = 0;
        int tb = 0;
        for (double flat = 0.0; flat <= 3.0; flat += 0.05) {
            for (double dy = -3.0; dy <= 3.0; dy += 0.1) {
                boolean want = jsTouch.execute(flat, dy).asBoolean();
                boolean got = ShadowModule.wormTouch(flat, dy);
                tc++;
                if (want != got) {
                    tb++;
                    if (tb <= 3) {
                        System.out.println("      wormTouch 不一致 flat=" + flat + " dy=" + dy
                                + " JS=" + want + " Java=" + got);
                    }
                }
            }
        }
        System.out.println("      wormTouch 用例 " + tc + "（含 flat=1.6 / |dy|=2.0 边界）");
        check("wormTouch 全部 " + tc + " 例逐值一致", tb == 0);

        // 瞄准方向（箭多 0.35、龙息不多）；顺带用真 Vector 验证 lengthSquared 的闸门口径
        int ac = 0;
        int ab = 0;
        for (double d = 0.0; d <= 40.0; d += 1.3) {
            double[] from = {1.5, 70.0, -3.25};
            double[] to = {1.5 + d, 70.0 - d * 0.37, -3.25 + d * 0.11};
            Value ra = jsArrow.execute(from[0], from[1], from[2], to[0], to[1], to[2]);
            double[] ga = ShadowModule.arrowAim(from[0], from[1], from[2], to[0], to[1], to[2]);
            Value rb2 = jsBreath.execute(from[0], from[1], from[2], to[0], to[1], to[2]);
            double[] gb = ShadowModule.breathAim(from[0], from[1], from[2], to[0], to[1], to[2]);
            ac += 2;
            for (int k = 0; k < 3; k++) {
                if (ra.getArrayElement(k).asDouble() != ga[k]) {
                    ab++;
                    System.out.println("      arrowAim 不一致 k=" + k
                            + " JS=" + ra.getArrayElement(k).asDouble() + " Java=" + ga[k]);
                    break;
                }
            }
            for (int k = 0; k < 3; k++) {
                if (rb2.getArrayElement(k).asDouble() != gb[k]) {
                    ab++;
                    System.out.println("      breathAim 不一致 k=" + k
                            + " JS=" + rb2.getArrayElement(k).asDouble() + " Java=" + gb[k]);
                    break;
                }
            }
            // ★ 闸门用的就是 Vector#lengthSquared ⇒ 它必须与 JS 里那句 lengthSquared() 逐位一致
            double jsL2a = ra.getArrayElement(3).asDouble();
            double javaL2a = new org.bukkit.util.Vector(ga[0], ga[1], ga[2]).lengthSquared();
            if (jsL2a != javaL2a) {
                ab++;
                System.out.println("      arrowDir lengthSquared 不一致 JS=" + jsL2a + " Java=" + javaL2a);
            }
            double jsL2b = rb2.getArrayElement(3).asDouble();
            double javaL2b = new org.bukkit.util.Vector(gb[0], gb[1], gb[2]).lengthSquared();
            if (jsL2b != javaL2b) {
                ab++;
                System.out.println("      breathDir lengthSquared 不一致 JS=" + jsL2b + " Java=" + javaL2b);
            }
            ac += 2;
        }
        System.out.println("      arrowAim/breathAim 用例 " + ac);
        check("arrowAim / breathAim / lengthSquared 闸门 全部 " + ac + " 例逐值一致", ab == 0);

        // 血条标题（真脚本里也有 sbBarTitle，直接对账）
        Value jsBarTitle = b.getMember("sbBarTitle");
        int bc = 0;
        for (int stage = 1; stage <= 3; stage++) {
            String want = jsBarTitle.execute(stage).asString();
            String got = ShadowModule.sbBarTitle(stage);
            bc++;
            check("sbBarTitle(" + stage + ") = " + got + " 与真 JS 一致", want.equals(got));
        }
        check("血条标题三档都取到了", bc == 3);

        // ---- ③ 数字转字符串：6 行日志里的**每一个**数字都要与 JS 的 '' + n 逐字节相同 ----
        //    ★ 这条是回归保护：曾经把 3.0 直接拼成 "3.0"，日志就与参考服差一个 ".0"。
        Value jsNum = b.getMember("jsNum");
        check("真 JS 的 jsNum 就绪", jsNum != null && jsNum.canExecute());
        double[] logNums = {
                ShadowModule.SB_S1_HP, ShadowModule.SB_S1_DMG,
                ShadowModule.SB_S2_HP, ShadowModule.SB_S2_DMG,
                ShadowModule.SB_S3_HP, ShadowModule.SB_S3_DMG,
                ShadowModule.SB_S2_BREATH_CD / 1000.0, ShadowModule.SB_S2_BREATH_DMG,
                ShadowModule.SB_S2_BREATH_R, ShadowModule.SB_S2_TELE_CD / 1000.0,
                ShadowModule.SB_S3_TELE_CD / 1000.0, ShadowModule.SB_S3_ARROW_CD / 1000.0,
                ShadowModule.SB_S3_ARROW_DMG, ShadowModule.SB_S3_WORM_HP, ShadowModule.SB_S3_WORM_HEAL,
                ShadowModule.SB_S3_MAGGOT_CD / 1000.0, ShadowModule.SB_S3_MAGGOT_N,
                ShadowModule.SB_S3_MAGGOT_HP, ShadowModule.SB_S3_MAGGOT_DMG,
                ShadowModule.SB_S3_MAGGOT_LIFE_MS / 1000.0, ShadowModule.SB_S3_CLOUD_R,
                ShadowModule.SB_S3_CLOUD_TICKS / 20.0, ShadowModule.SB_S3_CLOUD_AMP,
                6 << ShadowModule.SB_S3_CLOUD_AMP,
                ShadowModule.SB_DROP_ORB_N, ShadowModule.SB_DROP_VOICE_N,
                0.0, 1.0, 12.0, 160.0, 8.0, 4.0, 3.0, 1000.0,
        };
        int nc = 0;
        int nb = 0;
        for (double v : logNums) {
            String want = jsNum.execute(v).asString();
            String got = ShadowModule.jsNum(v);
            nc++;
            if (!want.equals(got)) {
                nb++;
                System.out.println("      jsNum 不一致 v=" + v
                        + " JS=\"" + want + "\" Java=\"" + got + "\"");
            }
        }
        System.out.println("      jsNum 用例 " + nc + "（= 6 行日志里出现的每一个数字）");
        check("jsNum 全部 " + nc + " 例与 JS 的 '' + n 逐字节相同", nb == 0);
        System.out.println();
    }

    // ==================================================================
    // [D] 反向断言
    // ==================================================================

    private static void reverse(Context ctx, Value b) throws Exception {
        System.out.println("[D] 反向断言（钉住「不应该存在的东西」）");

        // ① 两个半径不能混用：龙息弹命中半径 3 ≠ 龙息团半径 4
        check("★ 龙息弹命中半径 SB_S2_BREATH_R = 3.0（与 JS 一致）", ShadowModule.SB_S2_BREATH_R == 3.0);
        check("★ 龙息团半径 SB_S3_CLOUD_R = 4.0（固定值）", ShadowModule.SB_S3_CLOUD_R == 4.0);
        check("★ 反向：两个半径**不相等**（3.0 ≠ 4.0，没有被顺手统一）",
                ShadowModule.SB_S2_BREATH_R != ShadowModule.SB_S3_CLOUD_R);
        check("★ 反向：龙息团不是原版的 600 刻（30 秒）", ShadowModule.SB_S3_CLOUD_TICKS != 600);
        check("龙息团存在 160 刻 = 8 秒", ShadowModule.SB_S3_CLOUD_TICKS == 160);
        check("★ 反向：龙息团半径不是原版的 3（原版 3→7 一路扩大）", ShadowModule.SB_S3_CLOUD_R != 3.0);
        check("INSTANT_DAMAGE 等级 1 ⇒ 6 << 1 = 12 点（与日志里那个 12 一致）",
                (6 << ShadowModule.SB_S3_CLOUD_AMP) == 12);

        // ② 一阶段不掉落：不存在 SB_DROP_S1* 常量，且两个掉落 id 分属二 / 三阶段
        Set<String> dropFields = new TreeSet<>();
        for (Field f : ShadowModule.class.getDeclaredFields()) {
            if (f.getName().startsWith("SB_DROP_")) {
                dropFields.add(f.getName());
            }
        }
        check("★ 反向：**没有**任何一阶段的掉落常量（只有 " + dropFields + "）",
                dropFields.equals(new TreeSet<>(Set.of(
                        "SB_DROP_ORB_ID", "SB_DROP_ORB_N", "SB_DROP_VOICE_ID", "SB_DROP_VOICE_N"))));
        check("二阶段掉 1 把 GS_VOICELESS", ShadowModule.SB_DROP_VOICE_N == 1
                && "GS_VOICELESS".equals(ShadowModule.SB_DROP_VOICE_ID));
        check("三阶段掉 6 个 GS_SHADOW_ORB", ShadowModule.SB_DROP_ORB_N == 6
                && "GS_SHADOW_ORB".equals(ShadowModule.SB_DROP_ORB_ID));

        // ③ 名字带 §5、血条标题不带
        check("三个 boss 名都带 §5 前缀",
                ShadowModule.SB_S1_NAME.startsWith("\u00a75")
                        && ShadowModule.SB_S2_NAME.startsWith("\u00a75")
                        && ShadowModule.SB_S3_NAME.startsWith("\u00a75")
                        && ShadowModule.SB_S3_WORM_NAME.startsWith("\u00a75")
                        && ShadowModule.SB_S3_MAGGOT_NAME.startsWith("\u00a75"));
        check("★ 反向：血条标题**不带** §5（原脚本 sbBarTitle 就是纯文字）",
                !ShadowModule.sbBarTitle(1).contains("\u00a75")
                        && !ShadowModule.sbBarTitle(2).contains("\u00a75")
                        && !ShadowModule.sbBarTitle(3).contains("\u00a75"));

        // ④ 瞬移独立冷却：与其它技能的冷却不是同一个数
        check("★ 瞬移冷却与龙息弹冷却不同（11 秒 ≠ 8 秒）",
                ShadowModule.SB_S2_TELE_CD != ShadowModule.SB_S2_BREATH_CD);
        check("★ 瞬移冷却与弹道箭冷却不同（7 秒 ≠ 10 秒）",
                ShadowModule.SB_S3_TELE_CD != ShadowModule.SB_S3_ARROW_CD);
        check("出场保护期仍是 4 秒（瞬移不吃它，但别的技能照旧）",
                ShadowModule.SB_FIRST_MS == 4000);
        check("主循环间隔 5 刻", ShadowModule.SB_TICK == 5);
        check("阶段切换演出 40 刻 = 2 秒", ShadowModule.SB_TRANS_TICKS == 40);
        check("凋零是 II 级（amplifier 1）5 秒（100 刻）",
                ShadowModule.SB_S1_WITHER_AMP == 1 && ShadowModule.SB_S1_WITHER_TICKS == 100);

        // ⑤ 模块里不该有的东西
        check("★ 反向：模块**没有** InventoryCloseEvent 之类的会话清理监听器（本脚本不需要）",
                !containsString(ShadowModule.class, "InventoryCloseEvent"));
        check("★ 反向：模块**没有** NMS / CraftBukkit 反射（纯 Bukkit API）",
                !containsString(ShadowModule.class, "net.minecraft")
                        && !containsString(ShadowModule.class, "craftbukkit"));
        check("★ 反向：加载日志集合里没有 %s（本脚本是拼接，不是参数化 info）",
                !anyContains(ShadowModule.loadLines(), "%s"));
        System.out.println();
    }

    /** 类文件里是否出现过某个字符串（用于「不该有的东西」这类反向断言）。 */
    private static boolean containsString(Class<?> cls, String needle) {
        try {
            java.io.InputStream in = cls.getResourceAsStream(cls.getSimpleName() + ".class");
            if (in == null) {
                return false;
            }
            byte[] buf = in.readAllBytes();
            in.close();
            return new String(buf, StandardCharsets.ISO_8859_1).contains(needle);
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // 假世界（Proxy）—— 让真 JS 的 sbSafeY / sbHeadroom / sbPassableAt 能跑起来
    // ==================================================================

    /**
     * 地形：{@code y <= ground(x,z)} 是实心，上方是空气；
     * 另外 {@code (x+z) % 7 == 0} 的列在 {@code ground+3..ground+5} 处放<b>玻璃</b>
     * （{@code isPassable() == false} ⇒ 挡得住，正是脚本注释里强调的那条）。
     */
    private static int ground(int x, int z) {
        return 64 + Math.floorMod(x * 31 + z * 17, 5) - 2;
    }

    private static boolean passableAt(int x, int y, int z) {
        int h = ground(x, z);
        if (y <= h) {
            return false;
        }
        if (Math.floorMod(x + z, 7) == 0 && y >= h + 3 && y <= h + 5) {
            return false;
        }
        return true;
    }

    private static Object fakeWorld() {
        InvocationHandler block = (proxy, method, args) -> {
            switch (method.getName()) {
                case "isPassable":
                    return passableAt(intOf(args, 0), intOf(args, 1), intOf(args, 2));
                case "getType":
                    // ★ 无服环境下 Material 一读就炸 ⇒ 返回 null（跳过 isAir 那一支）
                    return null;
                case "getLocation":
                    return null;
                default:
                    return defaultValue(method);
            }
        };
        InvocationHandler world = (proxy, method, args) -> {
            if ("getBlockAt".equals(method.getName()) && args != null && args.length > 0) {
                int bx;
                int by;
                int bz;
                if (args[0] instanceof org.bukkit.Location loc) {
                    bx = loc.getBlockX();
                    by = loc.getBlockY();
                    bz = loc.getBlockZ();
                } else if (args.length == 3) {
                    bx = intOf(args, 0);
                    by = intOf(args, 1);
                    bz = intOf(args, 2);
                } else {
                    return null;
                }
                return Proxy.newProxyInstance(ShadowParityProbe.class.getClassLoader(),
                        new Class<?>[]{org.bukkit.block.Block.class}, block);
            }
            return defaultValue(method);
        };
        return Proxy.newProxyInstance(ShadowParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, world);
    }

    private static int intOf(Object[] args, int i) {
        return ((Number) args[i]).intValue();
    }

    private static Object defaultValue(Method m) {
        Class<?> r = m.getReturnType();
        if (!r.isPrimitive() || r == void.class) {
            return null;
        }
        if (r == boolean.class) {
            return false;
        }
        if (r == int.class) {
            return 0;
        }
        if (r == long.class) {
            return 0L;
        }
        if (r == double.class) {
            return 0.0d;
        }
        if (r == float.class) {
            return 0.0f;
        }
        if (r == short.class) {
            return (short) 0;
        }
        if (r == byte.class) {
            return (byte) 0;
        }
        if (r == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static Context newContext() {
        return Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
    }

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
        if (v.fitsInLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        return v.toString();
    }

    /** 允许 int↔long↔double 的表示差异，但<b>不允许精度损失</b>。 */
    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
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
            if (a instanceof Long && b instanceof Long) {
                return ((Long) a).longValue() == ((Long) b).longValue();
            }
            return true;
        }
        return a.equals(b);
    }

    private static String show(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return "\"" + s + "\"";
        }
        if (o instanceof Double d) {
            return String.format(Locale.ROOT, "%.12g", d);
        }
        return String.valueOf(o);
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
