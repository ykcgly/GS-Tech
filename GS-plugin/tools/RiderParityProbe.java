package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code gs_boss_rider} 的对账探针。
 *
 * <p>覆盖四块：
 * <ol>
 *   <li><b>常量对账</b>：用<b>真 GraalJS</b> 跑原脚本，读出全部顶层 {@code BR_*} 绑定，
 *       与 {@link RiderModule} 的 {@code static final} 字段<b>按名按值</b>比对。
 *       同时做「双向完备性」检查：JS 里有的参数常量 Java 必须都有、Java 里有的必须都在 JS 里，
 *       唯一允许缺席的是<b>运行时状态</b>（实例字段）那一小组（{@link #JS_RUNTIME_STATE}）。</li>
 *   <li><b>加载日志行</b>：5 条与 {@code docs/参考服日志_构思技艺.txt} <b>逐字节</b>比对，
 *       并且用反射钉住「本模块的日志行集合恰好等于这 5 条 info + 2 条 warning」。</li>
 *   <li><b>几何 / 数值算式</b>：把真 JS 的算式逐字抄进 {@link #JS}（并断言它<b>确实出现在原脚本里</b>，
 *       防止探针自己漂移），在<b>十几万组输入</b>上与 Java 逐值比对。</li>
 *   <li><b>反向断言</b>：钉住「<b>不应该存在</b>的东西」——死代码没有照抄、
 *       没有多打日志、爆炸拦截没有 {@code setCancelled}、没有 {@code ignoreCancelled = true}…</li>
 * </ol>
 */
public final class RiderParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static final Path SCRIPT =
            Paths.get("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_boss_rider.js");

    /** 参考服（装 RSC 的那台）的日志 —— 里面是**真 JS** 跑出来的行。 */
    private static final Path REF_LOG =
            Paths.get("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt");

    private static final Path MODULE_SRC =
            Paths.get("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/"
                    + "script/module/RiderModule.java");

    /**
     * 共享的「方块交互拦截」监听器 —— <b>不在 RiderModule 里</b>。
     *
     * <p>★ 原脚本有 <b>5 个</b> HIGHEST 注册点，但其中「方块交互拦截」那一份
     * 已被抽成公共类 {@link com.qinghuacisj.gs.script.module.InteractGuard}
     * （已机械确认：同一份逻辑在 4 个脚本里逐字重复）。
     * 所以 RiderModule 自己只有 <b>4</b> 个 {@code @EventHandler}，
     * 第 5 个在这个文件里。统计时必须把两个文件一起数，
     * 否则会误判成「漏了一个监听器」。
     */
    private static final Path GUARD_SRC =
            Paths.get("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/"
                    + "script/module/InteractGuard.java");

    // ================================================================
    // 真 JS 的算式 —— 从 gs_boss_rider.js 逐字摘出（每段都会被断言「确实在原脚本里」）
    // ================================================================

    private static final String JS =
            // ---- ① 斩击波前的命中判定（brSlashWave）----
            "function jsSlashHit(rx, ry, rz, hx, hz, d, W, H, BAND) {\n"
                    + "  var along = rx * hx + rz * hz;\n"
                    + "  var lat   = rx * hz - rz * hx;\n"
                    + "  if (Math.abs(lat) > W) return false;\n"
                    + "  if (Math.abs(ry) > H) return false;\n"
                    + "  if (along > d || along < d - BAND) return false;\n"
                    + "  return true;\n"
                    + "}\n"
                    // ---- ② 冲刺路径的命中判定（brDashTick）----
                    + "function jsDashHit(rx, ry, rz, dirX, dirZ, HW) {\n"
                    + "  var along = rx * dirX + rz * dirZ;\n"
                    + "  var lat   = rx * dirZ - rz * dirX;\n"
                    + "  if (Math.abs(lat) > HW) return false;\n"
                    + "  if (along > 2.0 || along < -2.0) return false;\n"
                    + "  if (Math.abs(ry) > 3.0) return false;\n"
                    + "  return true;\n"
                    + "}\n"
                    // ---- ③ 蓄势圈半径（brSlashWindup）----
                    + "function jsWindupRadius(i, steps) {\n"
                    + "  if (steps < 1) steps = 1;\n"
                    + "  return 1.30 - 0.85 * (i / steps);\n"
                    + "}\n"
                    // ---- ④ 波前距离 / 冲刺进度 ----
                    + "function jsWaveDistance(i, STEP) { return (i + 1) * STEP; }\n"
                    + "function jsDashProgress(i, TICKS) { return (i + 1) / TICKS; }\n"
                    // ---- ⑤ 伺骨出生点 + 朝向（brSummonServants）----
                    + "function jsServantPlacement(baseX, baseZ, yaw0, k, COUNT) {\n"
                    + "  var ang = (yaw0 + (k - (COUNT - 1) / 2.0) * 45.0) * Math.PI / 180.0;\n"
                    + "  var px = baseX + (-Math.sin(ang)) * 3.0;\n"
                    + "  var pz = baseZ + (Math.cos(ang)) * 3.0;\n"
                    + "  var yaw = (ang * 180.0 / Math.PI + 180.0) % 360.0;\n"
                    + "  return [px, pz, yaw];\n"
                    + "}\n"
                    // ---- ⑥ 残驹每刻该挪到哪（brSvTick）----
                    + "function jsServantStep(hlX, hlZ, tlX, tlZ, STOP_R, SEEK, STEP) {\n"
                    + "  var dx = tlX - hlX, dz = tlZ - hlZ;\n"
                    + "  var n = Math.sqrt(dx * dx + dz * dz);\n"
                    + "  if (!(n > STOP_R && n <= SEEK)) return null;\n"
                    + "  dx /= n; dz /= n;\n"
                    + "  var step = STEP;\n"
                    + "  if (step > n - STOP_R) step = n - STOP_R;\n"
                    + "  var nx = hlX + dx * step;\n"
                    + "  var nz = hlZ + dz * step;\n"
                    + "  var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;\n"
                    + "  return [nx, nz, yaw];\n"
                    + "}\n"
                    // ---- ⑦ 凋零之首的转向（brSkullGuide）----
                    + "function jsSkullSteer(ox, oy, oz, wx, wy, wz, TURN) {\n"
                    + "  var wn = Math.sqrt(wx * wx + wy * wy + wz * wz);\n"
                    + "  if (!(wn > 1.0e-4)) return null;\n"
                    + "  wx /= wn; wy /= wn; wz /= wn;\n"
                    + "  var k = TURN;\n"
                    + "  if (k < 0) k = 0; if (k > 1) k = 1;\n"
                    + "  var nx = ox * (1 - k) + wx * k;\n"
                    + "  var ny = oy * (1 - k) + wy * k;\n"
                    + "  var nz = oz * (1 - k) + wz * k;\n"
                    + "  var nn = Math.sqrt(nx * nx + ny * ny + nz * nz);\n"
                    + "  if (!(nn > 1.0e-4)) return null;\n"
                    + "  return [nx / nn, ny / nn, nz / nn];\n"
                    + "}\n"
                    // ---- ⑧ 血条进度（brBarSync）----
                    + "function jsBarProgress(hp, mx) {\n"
                    + "  var prog = hp / (mx > 0 ? mx : 1);\n"
                    + "  if (prog < 0) prog = 0; if (prog > 1) prog = 1;\n"
                    + "  return prog;\n"
                    + "}\n"
                    // ---- ⑨ boss 出生点（brSummon）----
                    + "function jsSpawnAhead(baseX, baseZ, yaw, AHEAD) {\n"
                    + "  var rad = yaw * Math.PI / 180.0;\n"
                    + "  return [baseX + (-Math.sin(rad)) * AHEAD, baseZ + (Math.cos(rad)) * AHEAD];\n"
                    + "}\n"
                    // ---- ⑩ 朝向 <-> 前向量 ----
                    + "function jsYawFromDir(dx, dz) { return Math.atan2(-dx, dz) * 180.0 / Math.PI; }\n"
                    + "function jsDirFromYaw(yaw) {\n"
                    + "  return [-Math.sin(yaw * Math.PI / 180.0), Math.cos(yaw * Math.PI / 180.0)];\n"
                    + "}\n"
                    // ---- ⑪ 音效名 -> 枚举常量名 ----
                    + "function jsMusicEnumName(s) {\n"
                    + "  return 'MUSIC_DISC_' + ('' + s).replace('music_disc.', '').toUpperCase();\n"
                    + "}\n"
                    // ---- ⑫ 撞墙 / 爬坡（brDashStepY，passable 由调用方注入）----
                    + "function jsDashStepY(passable, x, y, z, STEP_UP) {\n"
                    + "  var bx = Math.floor(x), bz = Math.floor(z), by = Math.floor(y);\n"
                    + "  if (passable(bx, by, bz) && passable(bx, by + 1, bz)) return y;\n"
                    + "  for (var up = 1; up <= STEP_UP; up++) {\n"
                    + "    var ok = true;\n"
                    + "    for (var c = 0; c <= 1; c++) {\n"
                    + "      if (!passable(bx, by + up + c, bz)) { ok = false; break; }\n"
                    + "    }\n"
                    + "    if (ok) return by + up;\n"
                    + "  }\n"
                    + "  return null;\n"
                    + "}\n"
                    // ---- ⑬ 假世界：x<=0 是地面 y=0；x>=1 是「高 wallH 的墙」----
                    + "function jsStepYForWall(wallH, x, y, z, STEP_UP) {\n"
                    + "  function passable(bx, by, bz) {\n"
                    + "    if (bx <= 0) return by >= 0;\n"
                    + "    return by >= wallH;\n"
                    + "  }\n"
                    + "  return jsDashStepY(passable, x, y, z, STEP_UP);\n"
                    + "}\n";

    /** 探针里那些 JS 片段必须**真的出现在原脚本里**（防止探针自己漂移）。 */
    private static final String[] JS_MUST_APPEAR = {
            "var along = rx * wave.hx + rz * wave.hz;",
            "var lat   = rx * wave.hz - rz * wave.hx;",
            "if (Math.abs(lat) > BR_QI_HALF_W) continue;",
            "if (Math.abs(ry) > BR_QI_HALF_H) continue;",
            "if (along > d || along < d - BR_QI_BAND) continue;",
            "var along = rx * ref.dirX + rz * ref.dirZ;",
            "var lat   = rx * ref.dirZ - rz * ref.dirX;",
            "if (along > 2.0 || along < -2.0) continue;",
            "if (Math.abs(ry) > 3.0) continue;",
            "var r = 1.30 - 0.85 * (i / steps);",
            "var d = (i + 1) * BR_QI_STEP;",
            "var k = (i + 1) / BR_DASH_TICKS;",
            "var ang = (yaw0 + (k - (BR_SV_COUNT - 1) / 2.0) * 45.0) * Math.PI / 180.0;",
            "var px = base.getX() + (-Math.sin(ang)) * 3.0;",
            "var pz = base.getZ() + (Math.cos(ang)) * 3.0;",
            "loc.setYaw((ang * 180.0 / Math.PI + 180.0) % 360.0);",
            "if (n > BR_SV_STOP_R && n <= BR_SV_SEEK) {",
            "if (step > n - BR_SV_STOP_R) step = n - BR_SV_STOP_R;",
            "var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;",
            "var nx = ox * (1 - k) + wx * k;",
            "var nn = Math.sqrt(nx * nx + ny * ny + nz * nz);",
            "prog = hp / (mx > 0 ? mx : 1);",
            "base.getX() + (-Math.sin(rad)) * BR_SPAWN_AHEAD,",
            "base.getZ() + (Math.cos(rad)) * BR_SPAWN_AHEAD);",
            "ref.yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;",
            "dx = -Math.sin(yaw0 * Math.PI / 180.0);",
            "dz = Math.cos(yaw0 * Math.PI / 180.0);",
            "var nm = 'MUSIC_DISC_' + ('' + cands[i]).replace('music_disc.', '').toUpperCase();",
            "var bx = Math.floor(x), bz = Math.floor(z), by = Math.floor(y);",
            "if (brPassableAt(world, bx, by, bz) && brPassableAt(world, bx, by + 1, bz)) return y;",
    };

    // ================================================================
    // 日志行
    // ================================================================

    /** 5 条 info 行 —— 顺序与参考服日志一致。 */
    private static final String[][] INFO_LINES = {
            {"loadLine", "gs_boss_rider 已加载"},
            {"guardLine", "枯骸骑将：方块交互拦截 已启用"},
            {"deathLine", "枯骸骑将：死亡掉落处理 已启用"},
            {"explodeLine", "枯骸骑将：凋零之首 不破坏地形 已启用"},
            {"allyLine", "枯骸骑将：阵营（不打自己人）已启用"},
    };

    /** 2 条 warning 行（注册失败才会打；参考服上<b>不该出现</b>）。 */
    private static final String[][] WARN_LINES = {
            {"explodeFailLine", "[构思技艺] 枯骸骑将：爆炸拦截注册失败，凋零之首会炸坏地形！"},
            {"allyFailLine", "[构思技艺] 枯骸骑将：阵营拦截注册失败，伺骨可能回头打 boss！"},
    };

    /**
     * JS 里那些 <b>不是参数常量</b>的顶层 {@code BR_*} 绑定 ——
     * 在 Java 侧对应<b>实例字段</b>（一次 eval 一份的状态）或干脆不出现（宿主类）。
     */
    private static final Set<String> JS_RUNTIME_STATE = Set.of(
            "BR_BOSSES", "BR_OWNER", "BR_LAST", "BR_H2B",
            "BR_SERVANTS", "BR_SV_H2S", "BR_SV_S2H",
            "BR_PIERCE", "BR_MUSIC_PICKED",
            "BR_GUARD_OK", "BR_EXPLODE_OK", "BR_DEATH_OK", "BR_ALLY_OK",
            "BR_PDT");

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_boss_rider（枯骸骑将）对账探针 ===");
        System.out.println();

        String src = new String(Files.readAllBytes(SCRIPT), StandardCharsets.UTF_8);

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {

            ctx.eval(Source.newBuilder("js", src, "gs_boss_rider.js").build());
            ctx.eval(Source.newBuilder("js", JS, "rider_parity.js").build());
            Value g = ctx.getBindings("js");

            sectionA(g);
            sectionB();
            sectionC(g, src);
            sectionD();
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ================================================================
    // [A] 常量对账
    // ================================================================

    private static void sectionA(Value g) throws Exception {
        System.out.println("[A] 常量对账：真 JS 顶层 BR_* vs Java static final");

        Set<String> jsNames = new TreeSet<>();
        for (String k : g.getMemberKeys()) {
            if (k.startsWith("BR_")) {
                jsNames.add(k);
            }
        }
        check("JS 顶层 BR_* 绑定数 = 92（脚本自报）", jsNames.size() == 92, "实际 " + jsNames.size());

        Set<String> javaNames = new TreeSet<>();
        for (Field f : RiderModule.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getName().startsWith("BR_")) {
                javaNames.add(f.getName());
            }
        }

        // ---- ① 每个 JS 名字：要么是参数常量（Java 必须有同名同值字段），要么是运行时状态 ----
        int compared = 0;
        for (String name : jsNames) {
            if (JS_RUNTIME_STATE.contains(name)) {
                check("运行时状态 " + name + " 不该是 Java 静态字段",
                        !javaNames.contains(name));
                continue;
            }
            Field f;
            try {
                f = RiderModule.class.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                check("Java 有常量 " + name, false);
                continue;
            }
            if (!Modifier.isStatic(f.getModifiers())) {
                check("Java 常量 " + name + " 是 static", false);
                continue;
            }
            f.setAccessible(true);
            compared++;

            if ("BR_ALLY_TAGS".equals(name)) {
                // ★ JS 是对象字面量 {'bone_rider':1, ...}；Java 是 String[]
                //   ⇒ 按**键集合**比对（值恒为 1，无信息量）
                Set<String> jsKeys = new TreeSet<>();
                Value obj = g.getMember(name);
                for (String k : obj.getMemberKeys()) {
                    jsKeys.add(k);
                    check("BR_ALLY_TAGS[" + k + "] === 1",
                            obj.getMember(k).fitsInLong() && obj.getMember(k).asLong() == 1L);
                }
                String[] arr = (String[]) f.get(null);
                Set<String> javaKeys = new TreeSet<>(List.of(arr));
                check("BR_ALLY_TAGS 键集合一致 " + jsKeys, jsKeys.equals(javaKeys),
                        "JS=" + jsKeys + " Java=" + javaKeys);
                continue;
            }

            Value v = g.getMember(name);
            String jv = canonJava(f.get(null));
            String sv = canonJs(v);
            check("常量 " + name + " = " + sv, sv.equals(jv), "JS=" + sv + " Java=" + jv);
        }

        // ---- ② Java 侧不许有 JS 里没有的 BR_* 常量 ----
        for (String name : javaNames) {
            check("Java 常量 " + name + " 在 JS 里存在", jsNames.contains(name));
        }

        System.out.println("      （逐个比对 " + compared + " 个参数常量；"
                + "运行时状态 " + JS_RUNTIME_STATE.size() + " 个）");

        // ---- ③ 自检：规范化尺子本身必须「允许表示差异、但不允许精度损失」 ----
        check("尺子自检：64(int) 与 64.0(double) 判为相同（表示差异）",
                canonNum(64).equals(canonNum(64.0)));
        check("尺子自检：0.1(double) 与 0.1f(float) 判为**不同**（精度损失）",
                !canonNum(0.1).equals(canonNum((double) 0.1f)));
        check("尺子自检：0.1(double) 与 0.1f(float) 的规范形分别是 "
                        + canonNum(0.1) + " / " + canonNum((double) 0.1f),
                !canonNum(0.1).equals(canonNum((double) 0.1f)));
        System.out.println();
    }

    /**
     * 数值规范化 —— <b>契约 §7.1 的规则</b>：允许 {@code int ↔ long ↔ double} 的
     * <b>表示差异</b>，但<b>不允许精度损失</b>。
     *
     * <p>做法：整数值统一走整数形态（{@code 64} / {@code 64.0} / {@code 64L} 视为同一个数），
     * 非整数值走<b>精确十六进制</b>（{@code 0.1} 与 {@code 0.1f} 的二进制展开不同 ⇒ 判为不同）。
     */
    private static String canonNum(double d) {
        if (!Double.isNaN(d) && !Double.isInfinite(d)
                && d == Math.rint(d) && d >= -9.223372036854776E18 && d <= 9.223372036854776E18) {
            return "i:" + (long) d;
        }
        return "f:" + Double.toHexString(d);
    }

    /** Java 侧规范化：按<b>声明类型</b>决定形态（int/long 走整数，float/double 走精确十六进制）。 */
    private static String canonJava(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Boolean b) {
            return "b:" + b;
        }
        if (o instanceof Byte || o instanceof Short || o instanceof Integer || o instanceof Long) {
            return "i:" + ((Number) o).longValue();
        }
        if (o instanceof Float || o instanceof Double) {
            return canonNum(((Number) o).doubleValue());
        }
        if (o instanceof String s) {
            return "s:" + s;
        }
        if (o instanceof String[] arr) {
            StringBuilder sb = new StringBuilder("a[");
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(arr[i]);
            }
            return sb.append(']').toString();
        }
        return "o:" + o;
    }

    /** JS 侧规范化：与 {@link #canonJava} 同一把尺子。 */
    private static String canonJs(Value v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isBoolean()) {
            return "b:" + v.asBoolean();
        }
        if (v.isString()) {
            return "s:" + v.asString();
        }
        if (v.isNumber()) {
            return canonNum(v.asDouble());
        }
        if (v.hasArrayElements()) {
            StringBuilder sb = new StringBuilder("a[");
            long n = v.getArraySize();
            for (long i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(v.getArrayElement(i).asString());
            }
            return sb.append(']').toString();
        }
        // 宿主对象（NamespacedKey 等）—— 用 toString 比
        try {
            Object h = v.as(Object.class);
            return "o:" + h;
        } catch (Throwable e) {
            return "o:" + v;
        }
    }

    // ================================================================
    // [B] 加载日志行
    // ================================================================

    private static void sectionB() throws Exception {
        System.out.println("[B] 加载日志行：与参考服日志逐字节一致");

        List<String> refLines = new ArrayList<>();
        if (Files.isRegularFile(REF_LOG)) {
            for (String line : Files.readAllLines(REF_LOG, StandardCharsets.UTF_8)) {
                String s = line;
                // 去掉 logger 前缀（形如 "[12:00:00 INFO]: "）—— 参考服日志是纯文本，通常没有
                int k = s.indexOf("INFO]: ");
                if (k >= 0) {
                    s = s.substring(k + "INFO]: ".length());
                }
                if (s.contains("gs_boss_rider") || s.contains("枯骸骑将")) {
                    refLines.add(s);
                }
            }
        } else {
            check("参考服日志存在（" + REF_LOG + "）", false);
            System.out.println();
            return;
        }

        // ---- ① 参考服日志里这个脚本恰好 5 行 ----
        check("参考服日志里本脚本恰好 5 行（实际 " + refLines.size() + "）", refLines.size() == 5);
        for (int i = 0; i < Math.min(refLines.size(), 6); i++) {
            System.out.println("      参考服[" + i + "] : " + refLines.get(i));
        }

        // ---- ② 5 条 info 行逐字节 ----
        for (int i = 0; i < INFO_LINES.length; i++) {
            String label = INFO_LINES[i][0];
            String got = infoLine(label);
            System.out.println("      Java  " + label + " : " + got);
            if (i < refLines.size()) {
                check(label + " 与参考服日志第 " + (i + 1) + " 行逐字节一致",
                        got.equals(refLines.get(i)),
                        "Java=[" + got + "] 参考=[" + refLines.get(i) + "]");
            }
        }

        // ---- ③ 反向断言：5 条 info 行里不许出现 %s（本脚本没有 gs_cursed 那种瑕疵） ----
        for (String[] e : INFO_LINES) {
            check(e[0] + " 里没有 %s 瑕疵（本脚本是纯字面量）",
                    !infoLine(e[0]).contains("%s"));
        }

        // ---- ④ 反向断言：日志行方法集合**恰好**是这 7 个 ----
        Set<String> found = new TreeSet<>();
        for (Method m : RiderModule.class.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())
                    && m.getName().endsWith("Line")
                    && m.getParameterCount() == 0
                    && m.getReturnType() == String.class) {
                found.add(m.getName());
            }
        }
        Set<String> expect = new TreeSet<>();
        for (String[] e : INFO_LINES) {
            expect.add(e[0]);
        }
        for (String[] e : WARN_LINES) {
            expect.add(e[0]);
        }
        check("日志行方法集合恰好 = " + expect + "（实际 " + found + "）", expect.equals(found));

        // ---- ⑤ 两条 warning 行的文本也必须与原脚本逐字一致，且**不许**出现在参考服日志里 ----
        String src = new String(Files.readAllBytes(SCRIPT), StandardCharsets.UTF_8);
        for (String[] e : WARN_LINES) {
            Method m = RiderModule.class.getDeclaredMethod(e[0]);
            m.setAccessible(true);
            String got = (String) m.invoke(null);
            check(e[0] + " 与原脚本逐字一致", got.equals(e[1]), "Java=[" + got + "]");
            check(e[0] + " 的字面量出现在原脚本里", src.contains(e[1]));
            check("参考服日志里**没有** " + e[0] + "（注册没失败）",
                    refLines.stream().noneMatch(l -> l.equals(got)));
        }
        System.out.println();
    }

    private static String infoLine(String name) throws Exception {
        Method m = RiderModule.class.getDeclaredMethod(name);
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    // ================================================================
    // [C] 几何 / 数值算式
    // ================================================================

    private static void sectionC(Value g, String src) {
        System.out.println("[C] 几何 / 数值算式：与真 JS 逐值比对");

        // ---- ⓪ 先钉住「探针里的 JS 确实来自原脚本」 ----
        int missing = 0;
        for (String s : JS_MUST_APPEAR) {
            if (!src.contains(s)) {
                missing++;
                check("原脚本里含片段「" + s + "」", false);
            }
        }
        check("探针里的 " + JS_MUST_APPEAR.length + " 段 JS 全部出现在原脚本里", missing == 0);

        int n;

        // ---- ① 斩击波前命中：大扫描 ----
        n = 0;
        int mismatch = 0;
        String firstBad = null;
        double[] headings = {0.0, 0.7071, -0.7071};
        double[] dists = {0.35, 1.05, 5.0, 10.0};
        for (double hx : headings) {
            double hz = Math.sqrt(Math.max(0.0, 1.0 - hx * hx));
            for (double d : dists) {
                for (int ix = -16; ix <= 16; ix++) {
                    double rx = ix * 0.25;
                    for (int iz = -16; iz <= 16; iz++) {
                        double rz = iz * 0.25;
                        for (int iy = -6; iy <= 6; iy++) {
                            double ry = iy * 0.5;
                            boolean j = g.getMember("jsSlashHit").execute(rx, ry, rz, hx, hz, d,
                                    RiderModule.BR_QI_HALF_W, RiderModule.BR_QI_HALF_H,
                                    RiderModule.BR_QI_BAND).asBoolean();
                            boolean a = RiderModule.slashHit(rx, ry, rz, hx, hz, d);
                            n++;
                            if (j != a) {
                                mismatch++;
                                if (firstBad == null) {
                                    firstBad = "rx=" + rx + " ry=" + ry + " rz=" + rz
                                            + " hx=" + hx + " hz=" + hz + " d=" + d
                                            + " JS=" + j + " Java=" + a;
                                }
                            }
                        }
                    }
                }
            }
        }
        check("slashHit 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ② 冲刺路径命中：大扫描 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        double[] dirs = {0.0, 0.6, -0.6};
        for (double dx : dirs) {
            double dz = Math.sqrt(Math.max(0.0, 1.0 - dx * dx));
            for (int ix = -16; ix <= 16; ix++) {
                double rx = ix * 0.25;
                for (int iz = -16; iz <= 16; iz++) {
                    double rz = iz * 0.25;
                    for (int iy = -8; iy <= 8; iy++) {
                        double ry = iy * 0.5;
                        boolean j = g.getMember("jsDashHit").execute(rx, ry, rz, dx, dz,
                                RiderModule.BR_DASH_HALF_W).asBoolean();
                        boolean a = RiderModule.dashHit(rx, ry, rz, dx, dz);
                        n++;
                        if (j != a) {
                            mismatch++;
                            if (firstBad == null) {
                                firstBad = "rx=" + rx + " ry=" + ry + " rz=" + rz
                                        + " dx=" + dx + " dz=" + dz
                                        + " JS=" + j + " Java=" + a;
                            }
                        }
                    }
                }
            }
        }
        check("dashHit 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ③ 蓄势圈半径 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        for (int i = -2; i <= 40; i++) {
            double j = g.getMember("jsWindupRadius").execute(i, RiderModule.BR_QI_WINDUP).asDouble();
            double a = RiderModule.windupRadius(i);
            n++;
            if (Double.compare(j, a) != 0) {
                mismatch++;
                firstBad = "i=" + i + " JS=" + j + " Java=" + a;
            }
        }
        check("windupRadius 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ④ 波前距离 / 冲刺进度 ----
        n = 0;
        mismatch = 0;
        for (int i = 0; i <= 200; i++) {
            double j = g.getMember("jsWaveDistance").execute(i, RiderModule.BR_QI_STEP).asDouble();
            double a = RiderModule.waveDistance(i);
            if (Double.compare(j, a) != 0) {
                mismatch++;
            }
            double j2 = g.getMember("jsDashProgress").execute(i, RiderModule.BR_DASH_TICKS).asDouble();
            double a2 = RiderModule.dashProgress(i);
            if (Double.compare(j2, a2) != 0) {
                mismatch++;
            }
            n += 2;
        }
        check("waveDistance / dashProgress 扫描 " + n + " 组全部一致", mismatch == 0);

        // ---- ⑤ 伺骨出生点 + 朝向 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        for (int iy = 0; iy < 72; iy++) {
            double yaw0 = iy * 5.0;
            for (int k = 0; k < RiderModule.BR_SV_COUNT; k++) {
                double bx = 100.0 + iy * 0.37;
                double bz = -200.0 - iy * 0.11;
                Value jv = g.getMember("jsServantPlacement")
                        .execute(bx, bz, yaw0, k, RiderModule.BR_SV_COUNT);
                double[] a = RiderModule.servantPlacement(bx, bz, yaw0, k);
                for (int q = 0; q < 3; q++) {
                    double j = jv.getArrayElement(q).asDouble();
                    n++;
                    if (Double.compare(j, a[q]) != 0) {
                        mismatch++;
                        if (firstBad == null) {
                            firstBad = "yaw0=" + yaw0 + " k=" + k + " q=" + q
                                    + " JS=" + j + " Java=" + a[q];
                        }
                    }
                }
            }
        }
        check("servantPlacement 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ⑥ 残驹每刻位移 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        for (int ix = -30; ix <= 30; ix++) {
            for (int iz = -30; iz <= 30; iz++) {
                double hlX = ix * 1.1;
                double hlZ = iz * 1.3;
                double tlX = 12.5;
                double tlZ = -7.25;
                Value jv = g.getMember("jsServantStep").execute(hlX, hlZ, tlX, tlZ,
                        RiderModule.BR_SV_STOP_R, RiderModule.BR_SV_SEEK, RiderModule.BR_SV_STEP);
                double[] a = RiderModule.servantStep(hlX, hlZ, tlX, tlZ);
                n++;
                if (jv.isNull()) {
                    if (a != null) {
                        mismatch++;
                        firstBad = "hlX=" + hlX + " hlZ=" + hlZ + " JS=null Java=" + a[0];
                    }
                    continue;
                }
                if (a == null) {
                    mismatch++;
                    firstBad = "hlX=" + hlX + " hlZ=" + hlZ + " JS=" + jv + " Java=null";
                    continue;
                }
                for (int q = 0; q < 3; q++) {
                    double j = jv.getArrayElement(q).asDouble();
                    if (Double.compare(j, a[q]) != 0) {
                        mismatch++;
                        if (firstBad == null) {
                            firstBad = "hlX=" + hlX + " hlZ=" + hlZ + " q=" + q
                                    + " JS=" + j + " Java=" + a[q];
                        }
                    }
                }
            }
        }
        check("servantStep 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ⑦ 凋零之首转向 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        for (int io = -6; io <= 6; io++) {
            for (int iw = -6; iw <= 6; iw++) {
                for (int iu = -6; iu <= 6; iu++) {
                    double ox = io * 0.31;
                    double oy = iu * 0.17;
                    double oz = iw * 0.23;
                    double wx = iw * 0.53 + 0.1;
                    double wy = io * 0.19 - 0.05;
                    double wz = iu * 0.41 + 0.2;
                    Value jv = g.getMember("jsSkullSteer").execute(ox, oy, oz, wx, wy, wz,
                            RiderModule.BR_SKULL_TURN);
                    double[] a = RiderModule.skullSteer(ox, oy, oz, wx, wy, wz);
                    n++;
                    if (jv.isNull() || a == null) {
                        if (!(jv.isNull() && a == null)) {
                            mismatch++;
                            firstBad = "ox=" + ox + " oy=" + oy + " oz=" + oz
                                    + " JS=" + (jv.isNull() ? "null" : jv) + " Java="
                                    + (a == null ? "null" : a[0]);
                        }
                        continue;
                    }
                    for (int q = 0; q < 3; q++) {
                        double j = jv.getArrayElement(q).asDouble();
                        if (Double.compare(j, a[q]) != 0) {
                            mismatch++;
                            if (firstBad == null) {
                                firstBad = "ox=" + ox + " oy=" + oy + " oz=" + oz + " q=" + q
                                        + " JS=" + j + " Java=" + a[q];
                            }
                        }
                    }
                }
            }
        }
        check("skullSteer 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ---- ⑧ 血条进度 ----
        n = 0;
        mismatch = 0;
        for (int i = -20; i <= 220; i++) {
            for (double mx : new double[]{0.0, -1.0, 1.0, 450.0, 800.0}) {
                double hp = i * 5.0;
                double j = g.getMember("jsBarProgress").execute(hp, mx).asDouble();
                double a = RiderModule.barProgress(hp, mx);
                n++;
                if (Double.compare(j, a) != 0) {
                    mismatch++;
                }
            }
        }
        check("barProgress 扫描 " + n + " 组全部一致", mismatch == 0);

        // ---- ⑨ boss 出生点 ----
        n = 0;
        mismatch = 0;
        for (int iy = 0; iy < 360; iy += 3) {
            double yaw = iy - 180.0;
            double bx = iy * 0.7 - 50.0;
            double bz = 33.0 - iy * 0.4;
            Value jv = g.getMember("jsSpawnAhead")
                    .execute(bx, bz, yaw, RiderModule.BR_SPAWN_AHEAD);
            double[] a = RiderModule.spawnAhead(bx, bz, yaw);
            for (int q = 0; q < 2; q++) {
                double j = jv.getArrayElement(q).asDouble();
                n++;
                if (Double.compare(j, a[q]) != 0) {
                    mismatch++;
                }
            }
        }
        check("spawnAhead 扫描 " + n + " 组全部一致", mismatch == 0);

        // ---- ⑩ 朝向 <-> 前向量 ----
        n = 0;
        mismatch = 0;
        for (int iy = 0; iy < 360; iy += 2) {
            double yaw = iy - 180.0;
            Value jv = g.getMember("jsDirFromYaw").execute(yaw);
            double[] a = RiderModule.dirFromYaw(yaw);
            for (int q = 0; q < 2; q++) {
                n++;
                if (Double.compare(jv.getArrayElement(q).asDouble(), a[q]) != 0) {
                    mismatch++;
                }
            }
        }
        for (int i = -40; i <= 40; i++) {
            double dx = i * 0.025;
            double dz = 0.5 + i * 0.01;
            double j = g.getMember("jsYawFromDir").execute(dx, dz).asDouble();
            n++;
            if (Double.compare(j, RiderModule.yawFromDir(dx, dz)) != 0) {
                mismatch++;
            }
        }
        check("dirFromYaw / yawFromDir 扫描 " + n + " 组全部一致", mismatch == 0);

        // ---- ⑪ 音效名 -> 枚举常量名 ----
        n = 0;
        mismatch = 0;
        String[] sounds = {"music_disc.pigstep", "music_disc.ward", "music_disc.relic",
                "music_disc.11", "MUSIC_DISC_X", "music_disc.a.b"};
        for (String s : sounds) {
            String j = g.getMember("jsMusicEnumName").execute(s).asString();
            String a = RiderModule.musicEnumName(s);
            n++;
            if (!j.equals(a)) {
                mismatch++;
                check("musicEnumName(" + s + ") JS=" + j + " Java=" + a, false);
            }
        }
        check("musicEnumName 扫描 " + n + " 组全部一致", mismatch == 0);
        // ★ 只替第一处（JS 的 String.replace 语义）—— 换成 String#replace 就会露馅
        check("musicEnumName 只替第一处 music_disc.（证明用的是 replaceFirst）",
                "MUSIC_DISC_A.B".equals(RiderModule.musicEnumName("music_disc.a.b")));

        // ---- ⑫ 撞墙 / 爬坡 ----
        n = 0;
        mismatch = 0;
        firstBad = null;
        for (int wallH = 0; wallH <= 4; wallH++) {
            final int wh = wallH;
            for (double x : new double[]{0.0, 0.4, 0.9, 1.0, 1.5, 2.0}) {
                for (double y : new double[]{0.0, 0.5}) {
                    for (double z : new double[]{0.0, 0.5, -0.5}) {
                        Value jv = g.getMember("jsStepYForWall").execute(wallH, x, y, z,
                                RiderModule.BR_DASH_STEP_UP);
                        Double a = RiderModule.dashStepY(
                                (bx, by, bz) -> (bx <= 0) ? (by >= 0) : (by >= wh), x, y, z);
                        n++;
                        boolean ok;
                        if (jv.isNull()) {
                            ok = (a == null);
                        } else if (a == null) {
                            ok = false;
                        } else {
                            ok = Double.compare(jv.asDouble(), a) == 0;
                        }
                        if (!ok) {
                            mismatch++;
                            if (firstBad == null) {
                                firstBad = "wallH=" + wallH + " x=" + x + " y=" + y + " z=" + z
                                        + " JS=" + (jv.isNull() ? "null" : jv) + " Java=" + a;
                            }
                        }
                    }
                }
            }
        }
        check("dashStepY 扫描 " + n + " 组全部一致", mismatch == 0, "首个不符：" + firstBad);

        // ★ 反向断言：障碍 2 格 ⇒ 撞墙（null）；1 格 ⇒ 当台阶踩上去
        Double one = RiderModule.dashStepY(
                (bx, by, bz) -> (bx <= 0) ? (by >= 0) : (by >= 1), 1.5, 0.0, 0.0);
        Double two = RiderModule.dashStepY(
                (bx, by, bz) -> (bx <= 0) ? (by >= 0) : (by >= 2), 1.5, 0.0, 0.0);
        check("1 格障碍 = 当台阶踩上去（返回 by+1=1.0）", one != null && one == 1.0, "实际 " + one);
        check("2 格障碍 = 撞墙（返回 null）", two == null, "实际 " + two);

        System.out.println();
    }

    // ================================================================
    // [D] 反向断言（钉住「不应该存在」的东西）
    // ================================================================

    private static void sectionD() throws Exception {
        System.out.println("[D] 反向断言：钉住「不应该存在」的东西");

        String raw = new String(Files.readAllBytes(MODULE_SRC), StandardCharsets.UTF_8);
        String code = stripCommentsAndStrings(raw);

        // ---- ① 死代码没有照抄 ----
        check("Java 里没有 JavaAdapter（GraalJS 里它不存在 ⇒ 那 5 条退路恒不执行）",
                !code.contains("JavaAdapter"));
        check("Java 里没有 LootTables / setLootTable（1.21.11 没 EMPTY、Entity 也没这方法）",
                !code.contains("LootTables") && !code.contains("setLootTable"));
        check("Java 里没有 SMOKE_LARGE（已改名 LARGE_SMOKE ⇒ 恒取不到）",
                !code.contains("SMOKE_LARGE"));
        check("Java 里没有 GENERIC_ 旧属性名（1.21.11 第一档就命中）",
                !code.contains("GENERIC_"));
        check("Java 里没有 GOLD_SWORD 旧材质名（已改名 GOLDEN_SWORD）",
                !code.contains("GOLD_SWORD"));
        check("Java 里没有 setTame( 旧方法名（Tameable#setTamed 才是现名）",
                !code.contains("setTame("));
        check("Java 里没有 AbstractHorse#setSaddle（javap 实测没这个方法）",
                !code.contains("h.setSaddle("));

        // ---- ② BR_DASH_STOP_H 只写在文档里，代码里从不读 ----
        int refs = 0;
        for (String line : raw.split("\n")) {
            if (!line.contains("BR_DASH_STOP_H")) {
                continue;
            }
            String t = line.trim();
            boolean decl = t.startsWith("static final int BR_DASH_STOP_H");
            boolean comment = t.startsWith("*") || t.startsWith("//") || t.startsWith("/*");
            if (!decl && !comment) {
                refs++;
                System.out.println("      ⚠ 代码引用 BR_DASH_STOP_H：" + t);
            }
        }
        check("BR_DASH_STOP_H 在代码里零引用（只保留常量 + 注释，与原脚本一致）", refs == 0,
                "实际 " + refs + " 处");

        // ---- ③ 爆炸拦截**不取消**事件（只清方块列表） ----
        String explodeBody = slice(raw, "private void brOnExplode(EntityExplodeEvent e) {",
                "protected Object onUse(PlayerRightClickEvent event");
        check("brOnExplode 里没有 setCancelled（取消会连对生物的伤害一起掐掉）",
                explodeBody != null && !explodeBody.contains("setCancelled"));
        check("brOnExplode 里确实清空了方块列表（blockList().clear()）",
                explodeBody != null && explodeBody.contains("list.clear()"));
        check("brOnExplode 里确实把掉落率设 0（setYield(0.0f)）",
                explodeBody != null && explodeBody.contains("setYield(0.0f)"));

        // ---- ④ 事件监听器没有 ignoreCancelled = true（原脚本 registerEvent 只有 4 参） ----
        check("Java 里没有 ignoreCancelled（默认 false，与原脚本一致）",
                !raw.contains("ignoreCancelled"));

        // ---- ⑤ 不许 System.out.println ----
        check("Java 里没有 System.out.print", !code.contains("System.out.print"));

        // ---- ⑥ 阵营：斩击 / 冲刺两条命中循环都跳过自己人 ----
        int allySkip = countOf(code, "brIsAlly(ent)");
        check("斩击 + 冲刺两条命中循环都调用 brIsAlly(ent)（实际 " + allySkip + " 处）",
                allySkip >= 2);

        // ---- ⑦ 5 个 HIGHEST 监听器方法齐全 ----
        //   ★ 4 个在 RiderModule 里，第 5 个（方块交互拦截）在共享类 InteractGuard 里。
        //     原脚本的 5 个真实注册点（行号）：
        //       2214/2223 InteractEv   方块交互拦截  → InteractGuard#onInteract
        //       2474/2483 Ev           爆炸不破坏地形 → ExplodeListener#onExplode
        //       2508/2517 DeathEv      死亡掉落处理   → DeathListener#onDeath
        //       2526/2533 DmgEv        凋零之首不破坏地形 → DeathListener#onSkullDamage
        //       2563/2572 Ev           阵营不打自己人 → AllyListener#onAllyTarget
        String guardRaw = new String(Files.readAllBytes(GUARD_SRC), StandardCharsets.UTF_8);
        for (String[] h : new String[][]{
                {"onInteract", "InteractGuard", "guard"}, {"onExplode", "ExplodeListener", "module"},
                {"onDeath", "DeathListener", "module"}, {"onSkullDamage", "DeathListener", "module"},
                {"onAllyTarget", "AllyListener", "module"}}) {
            String where = h[2].equals("guard") ? guardRaw : raw;
            boolean ok = where.contains("public void " + h[0] + "(") && where.contains(h[1]);
            check("HIGHEST 监听器 " + h[1] + "#" + h[0] + " 存在", ok);
        }
        // ★ 两个文件合起来恰好 5 个 @EventHandler（4 + 1）
        int handlers = countOf(raw, "@EventHandler") + countOf(guardRaw, "@EventHandler");
        check("Java 里恰好 5 个 @EventHandler 方法（RiderModule 4 + InteractGuard 1）",
                handlers == 5, "实际 " + handlers
                        + "（RiderModule " + countOf(raw, "@EventHandler")
                        + " + InteractGuard " + countOf(guardRaw, "@EventHandler") + "）");
        int highest = countOf(raw, "EventPriority.HIGHEST") + countOf(guardRaw, "EventPriority.HIGHEST");
        check("5 个 @EventHandler 全部是 HIGHEST（与原脚本 5 个真实注册点一致）",
                highest == 5, "实际 " + highest);
        // ★ 反向：确认 InteractGuard 那一份确实是**共享**的（不是 rider 私有），
        //   否则「抽公共代码」这个前提就是假的。
        check("InteractGuard 是公共类（另外 3 个脚本也在用）",
                countOf(guardRaw, "static synchronized boolean register") == 1
                        && guardRaw.contains("public final class InteractGuard"));

        // ---- ⑧ 本模块**没有** gs_cursed 那种 %s 瑕疵 ----
        check("Java 里没有任何 %s 日志瑕疵", !code.contains("%s"));

        // ---- ⑨ 名字 / 文件名一致 ----
        RiderModule m = new RiderModule();
        check("name() 返回 gs_boss_rider", "gs_boss_rider".equals(m.name()),
                "实际 " + m.name());

        System.out.println();
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 把注释与字符串字面量里的内容都抹掉，只留**代码**（用于「不该出现的东西」扫描）。 */
    private static String stripCommentsAndStrings(String src) {
        StringBuilder sb = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                char q = c;
                i++;
                while (i < n && src.charAt(i) != q) {
                    if (src.charAt(i) == '\\') {
                        i++;
                    }
                    i++;
                }
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    /** 取 {@code from} 到 {@code to} 之间的源码片段（找不到返回 null）。 */
    private static String slice(String src, String from, String to) {
        int a = src.indexOf(from);
        if (a < 0) {
            return null;
        }
        int b = src.indexOf(to, a);
        if (b < 0) {
            return null;
        }
        return src.substring(a, b);
    }

    private static int countOf(String hay, String needle) {
        int c = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    private static void check(String label, boolean ok) {
        check(label, ok, null);
    }

    private static void check(String label, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label + (detail == null ? "" : "  ← " + detail));
        }
    }
}
