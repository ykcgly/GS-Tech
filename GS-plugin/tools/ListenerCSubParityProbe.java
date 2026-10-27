package com.qinghuacisj.gs.script.module.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

/**
 * 「五骊矟 / 威龙 / 基础构思」三个子系统的<b>对账探针</b>。
 *
 * <p>它做的事：把<b>真 JS</b>（{@code gs_listener.js}）用无服务器桩跑起来，
 * 然后<b>逐项</b>与三个 Java 类对账。
 *
 * <ol>
 *   <li><b>常量对账</b> —— 把原脚本第 2811~4833 / 4835~5833 / 5835~5944 行里
 *       <b>全部顶层 {@code var}</b> 的<b>真实值</b>与同名 Java 字段按值比对。</li>
 *   <li><b>日志逐字节对账</b> —— 让真 JS 的注册<b>成功</b>（桩里
 *       {@code getPlugin()} 返回一个假插件），于是 18 行加载日志会真的被
 *       {@code gsDaLog} 打出来；把这 18 行与 Java 拼出来的字符串、
 *       以及 {@code docs/参考服日志_构思技艺.txt} 第 10~27 行做<b>三方逐字节</b>比对。</li>
 *   <li><b>数值 / 几何对账</b> —— 用一块内存体素网格 + 动态代理 {@code World}/{@code Block}
 *       把真 JS 的 {@code gsWlBlockedAt} / {@code gsWlRayHit} / {@code gsWlDashRay} /
 *       {@code gsWlStuckAt} / {@code gsWlSafeY} / {@code wlDashStepY} / {@code wlAxes} /
 *       {@code wlAngDiff} / {@code gsWlCdMsg} 全部跑起来，与 Java 版逐点比对。</li>
 *   <li><b>反向断言</b> —— 见 {@link #sectionReverse}。</li>
 * </ol>
 *
 * <p>最后一行固定打印 {@code 通过 N ｜ 失败 M}，有失败就 {@code System.exit(1)}。
 */
public final class ListenerCSubParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static final String SCRIPT_PATH =
            "D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js";
    private static final String REFLOG_PATH =
            "D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt";

    private static final ClassLoader CL = ListenerCSubParityProbe.class.getClassLoader();

    private static final Object MISSING = new Object();

    private static WuliSub wuli;
    private static WlSub wl;
    private static PlainSub plain;

    // ======================================================================
    // 计数
    // ======================================================================

    private static void check(String label, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("  [X] " + label);
        }
    }

    private static void eq(String label, String js, String java) {
        boolean same = js.equals(java);
        if (!same) {
            fail++;
            System.out.println("  [X] " + label);
            System.out.println("        js   = " + js);
            System.out.println("        java = " + java);
        } else {
            pass++;
        }
    }

    // ======================================================================
    // 数值归一化（JS / Java 两边产出同一个字符串）
    // ======================================================================

    /** 数值 → 规范串。整数不带 {@code .0}，其余用 {@link Double#toString}。 */
    private static String num(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 9.007199254740992E15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** Java 值 → 规范串。 */
    private static String normJava(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof Boolean || o instanceof CharSequence) {
            return o.toString();
        }
        if (o instanceof Number) {
            return num(((Number) o).doubleValue());
        }
        if (o instanceof NamespacedKey) {
            return "key:" + o;
        }
        if (o instanceof WuliSub.Form) {
            WuliSub.Form f = (WuliSub.Form) o;
            return "{back:" + num(f.back) + ",side:" + num(f.side) + "}";
        }
        if (o.getClass().isArray()) {
            int n = Array.getLength(o);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(normJava(Array.get(o, i)));
            }
            return sb.append(']').toString();
        }
        if (o instanceof Map) {
            Map<?, ?> m = (Map<?, ?>) o;
            TreeMap<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> en : m.entrySet()) {
                sorted.put(String.valueOf(en.getKey()), en.getValue());
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> en : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(en.getKey()).append(':').append(normJava(en.getValue()));
            }
            return sb.append('}').toString();
        }
        if (o instanceof Collection) {
            Collection<?> c = (Collection<?>) o;
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object e : c) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(normJava(e));
            }
            return sb.append(']').toString();
        }
        return String.valueOf(o);
    }

    /** JS 值 → 规范串（与 {@link #normJava} 同一套写法）。 */
    private static String normJs(Value v) {
        if (v == null || v.isNull()) {
            return "null";
        }
        if (v.isBoolean()) {
            return String.valueOf(v.asBoolean());
        }
        if (v.isNumber()) {
            return num(v.asDouble());
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.canExecute()) {
            return "<fn>";
        }
        if (v.isHostObject()) {
            return normJava(v.asHostObject());
        }
        if (v.hasArrayElements()) {
            long n = v.getArraySize();
            StringBuilder sb = new StringBuilder("[");
            for (long i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(normJs(v.getArrayElement(i)));
            }
            return sb.append(']').toString();
        }
        if (v.hasMembers()) {
            // JS 普通对象：按 key 排序，和 Java Map 的写法对齐
            TreeMap<String, Value> sorted = new TreeMap<>();
            for (String k : v.getMemberKeys()) {
                sorted.put(k, v.getMember(k));
            }
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Value> en : sorted.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(en.getKey()).append(':').append(normJs(en.getValue()));
            }
            return sb.append('}').toString();
        }
        return "<" + v + ">";
    }

    // ======================================================================
    // 内存体素网格 + 假 World / Block
    // ======================================================================

    /** 一块只有"实心 / 空气"两种状态的体素网格。 */
    private static final class Vox {
        final Set<Long> solid = new HashSet<>();

        void set(int x, int y, int z) {
            solid.add(k(x, y, z));
        }

        void fill(int x0, int y0, int z0, int x1, int y1, int z1) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z);
                    }
                }
            }
        }

        boolean isSolid(int x, int y, int z) {
            return solid.contains(k(x, y, z));
        }

        static long k(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
        }
    }

    private static Object defaultValue(Class<?> ret) {
        if (ret == boolean.class) {
            return Boolean.FALSE;
        }
        if (ret == int.class) {
            return 0;
        }
        if (ret == long.class) {
            return 0L;
        }
        if (ret == double.class) {
            return 0.0;
        }
        if (ret == float.class) {
            return 0.0f;
        }
        if (ret == short.class) {
            return (short) 0;
        }
        if (ret == byte.class) {
            return (byte) 0;
        }
        if (ret == char.class) {
            return (char) 0;
        }
        return null;
    }

    private static Object objectMethod(Object proxy, Method m, Object[] a) {
        switch (m.getName()) {
            case "toString":
                return "fake:" + m.getDeclaringClass().getSimpleName();
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == (a == null || a.length == 0 ? null : a[0]);
            default:
                return MISSING;
        }
    }

    /** 假 Block。 */
    private static Block fakeBlock(Vox v, int x, int y, int z) {
        InvocationHandler h = (proxy, m, a) -> {
            Object om = objectMethod(proxy, m, a);
            if (om != MISSING) {
                return om;
            }
            switch (m.getName()) {
                case "getType":
                    return v.isSolid(x, y, z) ? Material.STONE : Material.AIR;
                case "isPassable":
                    return !v.isSolid(x, y, z);
                case "isAir":
                    return !v.isSolid(x, y, z);
                case "isLiquid":
                    return false;
                case "isEmpty":
                    return !v.isSolid(x, y, z);
                case "getX":
                    return x;
                case "getY":
                    return y;
                case "getZ":
                    return z;
                default:
                    return defaultValue(m.getReturnType());
            }
        };
        return (Block) Proxy.newProxyInstance(CL, new Class<?>[]{Block.class}, h);
    }

    /**
     * 假 World：只实现碰撞判定真正会用到的那几个方法。
     *
     * @param particles 若非 null，{@code spawnParticle} 的坐标会被记进来
     */
    private static World fakeWorld(Vox v, List<double[]> particles) {
        InvocationHandler h = (proxy, m, a) -> {
            Object om = objectMethod(proxy, m, a);
            if (om != MISSING) {
                return om;
            }
            switch (m.getName()) {
                case "getBlockAt": {
                    Location l = (Location) a[0];
                    return fakeBlock(v, (int) Math.floor(l.getX()),
                            (int) Math.floor(l.getY()), (int) Math.floor(l.getZ()));
                }
                case "spawnParticle": {
                    if (particles != null && a.length >= 4
                            && a[1] instanceof Number && a[2] instanceof Number
                            && a[3] instanceof Number) {
                        particles.add(new double[]{
                                ((Number) a[1]).doubleValue(),
                                ((Number) a[2]).doubleValue(),
                                ((Number) a[3]).doubleValue()});
                    }
                    return null;
                }
                case "playSound":
                case "spawnEntity":
                    return null;
                case "getName":
                    return "probe_world";
                case "getNearbyEntities":
                    return new ArrayList<>();
                default:
                    return defaultValue(m.getReturnType());
            }
        };
        return (World) Proxy.newProxyInstance(CL, new Class<?>[]{World.class}, h);
    }

    // ======================================================================
    // JS 运行环境
    // ======================================================================

    /** 一次脚本求值的产物。 */
    private static final class Js implements AutoCloseable {
        Context ctx;
        Value bind;
        final List<String> logs = new ArrayList<>();
        final List<Value> later = new ArrayList<>();
        final List<Value> repeating = new ArrayList<>();

        Value fn(String name) {
            Value v = bind.getMember(name);
            return (v != null && v.canExecute()) ? v : null;
        }

        @Override
        public void close() {
            ctx.close();
        }
    }

    /** 构造无服务器桩。{@code register=true} 时让插件解析成功，于是加载日志会真的被打出来。 */
    private static String stub(boolean register) {
        return "var __realJava = Java;\n"
                + "var Java = { type: function (n) {\n"
                + "  if (n === 'org.bukkit.attribute.Attribute') {\n"
                + "    return { SCALE: null, MOVEMENT_SPEED: null, GENERIC_MOVEMENT_SPEED: null,"
                + "             valueOf: function () { return null; } };\n"
                + "  }\n"
                + "  return __realJava.type(n);\n"
                + "} };\n"
                + "var __LOGS = [];\n"
                + "var __LATER = [];\n"
                + "var __REPEAT = [];\n"
                + "var __FAKE_PLUGIN = " + (register ? "{}" : "null") + ";\n"
                + "var __srv = {\n"
                + "  getPluginManager: function () {\n"
                + "    return { getPlugin: function () { return __FAKE_PLUGIN; },\n"
                + "             registerEvent: function () { return null; } };\n"
                + "  },\n"
                + "  getLogger: function () {\n"
                + "    return { info: function (s) { __LOGS.push('' + s); },\n"
                + "             warning: function (s) { __LOGS.push('WARN ' + s); },\n"
                + "             severe: function () {} };\n"
                + "  },\n"
                + "  getOnlinePlayers: function () { return []; },\n"
                + "  getWorlds: function () { return []; }\n"
                + "};\n"
                + "function getServer() { return __srv; }\n"
                + "function runLater(fn, d) { __LATER.push(fn); return null; }\n"
                + "function runRepeating(fn, d, p) { __REPEAT.push(fn); return null; }\n"
                + "function runAsync(fn) { return null; }\n"
                + "function runLaterAsync(fn, d) { return null; }\n"
                + "function runRepeatingAsync(fn, d, p) { return null; }\n"
                + "function getAddonConfig() { return null; }\n"
                + "function getSfItemById(id) { return null; }\n"
                + "function getSfItemByItem(it) { return null; }\n"
                + "function sendMessage(p, s) { return null; }\n"
                + "function runOpCommand(p, s) { return null; }\n"
                + "function runConsoleCommand(s) { return null; }\n"
                + "function getData(l, k) { return null; }\n"
                + "function setData(l, k, v) { return null; }\n"
                + "function isSlimefunBlock(l) { return false; }\n"
                + "function isBlock(l, id) { return false; }\n"
                + "function getBlockMenu(l) { return null; }\n"
                + "function getBlockData(l) { return null; }\n"
                + "function getSfItemByBlock(l) { return null; }\n"
                + "function isItemSimilar(a, b, c) { return false; }\n"
                + "function isRadioactiveItem(i) { return false; }\n"
                + "function isSoulbound(i) { return false; }\n"
                + "function canPlayerUseItem(p, i, b) { return false; }\n"
                + "function randintA(i) { return 0; }\n"
                + "function randintB(i, b) { return 0; }\n"
                + "function randintC(a, b) { return 0; }\n"
                + "function randintD(a, b, c) { return 0; }\n"
                + "function isPluginLoaded(n) { return false; }\n"
                + "function getScript(n) { return null; }\n"
                + "function getChatInput(p, c) { return null; }\n"
                + "function getChatInputWithCheck(p, q, c) { return null; }\n"
                + "function getSuperMultiBlockManager() { return null; }\n";
    }

    /** 跑一遍脚本。 */
    private static Js boot(boolean register) throws Exception {
        String source = new String(Files.readAllBytes(Path.of(SCRIPT_PATH)), StandardCharsets.UTF_8);
        Js js = new Js();
        js.ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        js.ctx.eval(Source.newBuilder("js", stub(register), "stub").build());
        js.ctx.eval(Source.newBuilder("js", source, "gs_listener").build());
        js.bind = js.ctx.getBindings("js");
        Value logs = js.bind.getMember("__LOGS");
        for (long i = 0; i < logs.getArraySize(); i++) {
            js.logs.add(logs.getArrayElement(i).asString());
        }
        Value later = js.bind.getMember("__LATER");
        for (long i = 0; i < later.getArraySize(); i++) {
            js.later.add(later.getArrayElement(i));
        }
        Value rep = js.bind.getMember("__REPEAT");
        for (long i = 0; i < rep.getArraySize(); i++) {
            js.repeating.add(rep.getArrayElement(i));
        }
        return js;
    }

    // ======================================================================
    // 一、常量对账
    // ======================================================================

    private static final String[] WULI_VARS = {
            "GS_WULI_ID", "GS_WULI_REIN_KEY", "GS_WULI_SUMMON_KEY", "GS_WULI_QI_KEY",
            "GS_WULI_DASH_KEY",
            "WL_REIN_NAME", "WL_REIN_LORE", "WL_PULSE", "WL_HORSE_SPEED", "WL_REAR_PITCH",
            "WL_FORM",
            "WL_QI_CD_MS", "WL_QI_SND1", "WL_QI_SND2", "WL_QI_SND1_VOL", "WL_QI_SND1_PIT",
            "WL_QI_SND2_VOL", "WL_QI_SND2_PIT",
            "WL_QI_DMG", "WL_QI_RANGE", "WL_QI_HALF_W", "WL_QI_HALF_T", "WL_QI_HALF_H",
            "WL_QI_STEP", "WL_QI_KB", "WL_QI_KB_UP", "WL_QI_NO_BLOCK",
            "WL_DASH_CD_MS", "WL_CHARGE_MS", "WL_DASH_DIST", "WL_DASH_TICKS",
            "WL_DASH_LOCK_LOOK", "WL_LOOK_EPS",
            "WL_CHARGE_INVULN", "WL_DASH_INVULN", "WL_DASH_DMG", "WL_DASH_HALF_W",
            "WL_DASH_HALF_H", "WL_DASH_STEP_UP",
            "WL_FXHORSE_ON", "WL_FXHORSE_Y", "WL_FXHORSE_FRONT", "WL_FXHORSE_STEP",
            "WL_FXHORSE_EVERY", "WL_FXHORSE_PARTICLES", "WL_FXHORSE_EYE",
            "WL_DISSMOKE_TICKS", "WL_DISSMOKE_UP", "WL_FLEE_DIST", "WL_FLEE_TICKS",
            "WL_FLEE_SMOKE", "WL_FLEE_REAR_TICKS", "WL_FLEE_REAR_KEEP",
            "WL_SMOKE_PARTICLES", "WL_SMOKE_SNDS", "WL_SMOKE_SND_VOL", "WL_SMOKE_SND_PIT",
            "WL_RIDE_TAG", "WL_RIDE_DIST", "WL_RIDE_TICKS", "WL_RIDE_HIT_R", "WL_RIDE_REAR_R",
            "WL_RIDE_EASE", "WL_RIDE_SND", "WL_RIDE_SWAP_TICKS", "WL_RIDE_SPAWN_FX",
            "WL_RIDE_SND_EVERY", "WL_RIDE_SND_VOL", "WL_RIDE_SND_PIT", "WL_RIDE_PARTICLE",
            "WL_DASH_QI_ON", "WL_DASH_QI_AT", "WL_DASH_QI_SOUND", "WL_DASH_QI_KB_MUL",
            "WL_MSG_USE", "WL_MSG_CD",
            "GS_WULI_STATE", "GS_WULI_TOGGLE_AT", "GS_WULI_SUMMONING", "GS_WULI_RIDE",
            "GS_WULI_REGISTERED", "GS_WULI_DAMAGING"
    };

    private static final String[] WL_VARS = {
            "GS_WL_ID",
            "WL_BOMB_DIST", "WL_BOMB_FLY_TICKS", "WL_BOMB_FUSE_TICKS", "WL_BOMB_R",
            "WL_BOMB_DMG", "WL_BOMB_CD_MS", "WL_BOMB_MAGNET_R", "WL_BOMB_MSG",
            "WL_BOMB_MSG_CD", "WL_BOMB_SNDS_THROW", "WL_BOOM_SNDS", "WL_BOOM_PARTICLES",
            "WL_CANNON_DIST", "WL_CANNON_FLY_TICKS", "WL_CANNON_FUSE_TICKS", "WL_CANNON_R",
            "WL_CANNON_DMG", "WL_CANNON_KB", "WL_CANNON_KB_UP", "WL_CANNON_STUN_MS",
            "WL_CANNON_STUN_LOCK", "WL_CANNON_CD_MS", "WL_CANNON_MSG_HIT", "WL_CANNON_MSG_CD",
            "WL_CANNON_SNDS_THROW", "WL_WIND_SNDS", "WL_WIND_PARTICLES",
            "WL_DASH2_DIST", "WL_DASH2_TICKS", "WL_DASH2_STEP", "WL_DASH2_FLAT",
            "WL_DASH2_AXIS", "WL_DASH2_HOP", "WL_DASH2_CD_MS", "WL_DASH2_MSG",
            "WL_DASH2_MSG_CD", "WL_DASH2_SNDS", "WL_SPIT_PARTICLES",
            "WL_SPEED_TICKS", "WL_SPEED_AMP", "WL_SPEED_CD_MS", "WL_SPEED_SNDS",
            "WL_HIT_PLAYERS", "WL_NO_BREAK", "WL_BLOCK_STEP",
            "GS_WL_CD_KEYS", "GS_WL_BOMB_KEY", "GS_WL_REGISTERED", "GS_WL_DAMAGING",
            "GS_WL_BOMBS", "GS_WL_BOMB_SEQ", "GS_WL_STUN", "GS_WL_CLICK_AT"
    };

    private static final String[] PLAIN_VARS = {
            "GS_BASIC_PLAIN_IDS", "GS_PLAIN_REGISTERED"
    };

    /** 按名字取 Java 侧的值（三个类依次找）。 */
    private static Object javaField(String name) throws Exception {
        Object[][] owners = {
                {WuliSub.class, wuli}, {WlSub.class, wl}, {PlainSub.class, plain}
        };
        for (Object[] o : owners) {
            Class<?> c = (Class<?>) o[0];
            Object inst = o[1];
            Field f;
            try {
                f = c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                continue;
            }
            f.setAccessible(true);
            return Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(inst);
        }
        return MISSING;
    }

    private static void sectionConstants(Js js) throws Exception {
        System.out.println("[1] 常量对账");
        int n = 0;
        for (String[] group : new String[][]{WULI_VARS, WL_VARS, PLAIN_VARS}) {
            for (String name : group) {
                Value jv = js.bind.getMember(name);
                if (jv == null) {
                    check("常量 " + name + " 在 JS 里不存在", false);
                    continue;
                }
                Object jf = javaField(name);
                if (jf == MISSING) {
                    check("常量 " + name + " 在 Java 里找不到同名字段", false);
                    continue;
                }
                n++;
                eq("常量 " + name, normJs(jv), normJava(jf));
            }
        }
        System.out.println("    比对了 " + n + " 个顶层常量");
    }

    // ======================================================================
    // 〇、覆盖度自检（防止"清单漏抄一个常量"这种最隐蔽的错）
    // ======================================================================

    /** 三个子系统的行号区间（闭区间，与原脚本一致）。 */
    private static final int[][] RANGES = {
            {2811, 4833},   // 五骊矟
            {4835, 5833},   // 威龙
            {5835, 5944}    // 基础构思
    };

    /**
     * 直接从<b>脚本源码</b>里把这三个区间内所有<b>顶层</b> {@code var}
     * （行首无缩进；同一行的 {@code var A = 1, B = 2} 两个都算）抽出来，
     * 断言探针的清单与它<b>完全相等</b>。
     *
     * <p><b>为什么必须这么做</b>：{@link #WULI_VARS} 这类清单是我手抄的。
     * 手抄就可能漏 —— 而漏掉的那个常量<b>永远不会被比对</b>，探针照样报「失败 0」。
     * 这个自检把"漏抄"变成一条会红的断言。
     */
    private static void sectionCoverage() throws Exception {
        System.out.println("[0] 顶层 var 覆盖度自检");
        List<String> src = Files.readAllLines(Path.of(SCRIPT_PATH), StandardCharsets.UTF_8);
        java.util.regex.Pattern pFirst =
                java.util.regex.Pattern.compile("^var\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
        java.util.regex.Pattern pMore =
                java.util.regex.Pattern.compile(",\\s*([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");

        Map<String, Integer> declared = new LinkedHashMap<>();
        for (int[] r : RANGES) {
            for (int i = r[0] - 1; i < r[1] && i < src.size(); i++) {
                String line = src.get(i);
                if (!line.startsWith("var ")) {
                    continue;
                }
                java.util.regex.Matcher m = pFirst.matcher(line);
                if (m.find()) {
                    declared.putIfAbsent(m.group(1), i + 1);
                }
                java.util.regex.Matcher m2 = pMore.matcher(line);
                while (m2.find()) {
                    declared.putIfAbsent(m2.group(1), i + 1);
                }
            }
        }

        Set<String> inProbe = new HashSet<>();
        for (String[] g : new String[][]{WULI_VARS, WL_VARS, PLAIN_VARS}) {
            inProbe.addAll(java.util.Arrays.asList(g));
        }

        List<String> missing = new ArrayList<>();
        for (String k : declared.keySet()) {
            if (!inProbe.contains(k)) {
                missing.add(k + "（脚本第 " + declared.get(k) + " 行）");
            }
        }
        List<String> extra = new ArrayList<>();
        for (String k : inProbe) {
            if (!declared.containsKey(k)) {
                extra.add(k);
            }
        }
        System.out.println("    脚本里顶层 var = " + declared.size() + " 个；探针清单 = "
                + inProbe.size() + " 个");
        check("★ 覆盖度：探针清单<b>没有漏</b>任何顶层 var" + (missing.isEmpty() ? "" : " —— 漏了 "
                + missing), missing.isEmpty());
        check("★ 覆盖度：探针清单里没有脚本里不存在的名字" + (extra.isEmpty() ? "" : " —— 多了 "
                + extra), extra.isEmpty());
        check("★ 覆盖度：脚本顶层 var 总数 = 探针清单总数",
                declared.size() == inProbe.size());
    }

    // ======================================================================
    // 二、日志逐字节对账（Java ↔ 真 JS ↔ 参考服日志）
    // ======================================================================

    /** Java 侧拼出来的 18 行（顺序 = 原脚本 4810~4831 / 5810~5831 / 5934~5942 行）。 */
    private static List<String> javaLogLines() {
        String p = ListenerCore.PREFIX;
        List<String> l = new ArrayList<>();
        l.add(WuliSub.registerLine());
        l.add(p + WuliSub.msgEnabled());
        l.add(p + WuliSub.msgSpeedFloor());
        l.add(p + WuliSub.msgInvuln());
        l.add(p + WuliSub.msgRideAnim());
        l.add(p + WuliSub.msgDismissAnim());
        l.add(WlSub.registerLine());
        l.add(p + WlSub.msgBomb());
        l.add(p + WlSub.msgCannon());
        l.add(p + WlSub.msgDash());
        l.add(p + WlSub.msgSpeed());
        l.add(p + WlSub.msgNoBoom());
        l.add(p + WlSub.msgStep());
        l.add(p + WlSub.msgNoBreak());
        l.add(p + WlSub.msgFlat());
        l.add(p + WlSub.msgKillClear());
        l.add(PlainSub.registerLine());
        l.add(p + PlainSub.msgPlain());
        return l;
    }

    private static void sectionLogs(Js js) throws Exception {
        System.out.println("[2] 加载日志逐字节对账");
        List<String> javaLines = javaLogLines();
        check("Java 侧日志行数 = 18", javaLines.size() == 18);

        // ---- 参考服日志第 10~27 行 ----
        List<String> ref = Files.readAllLines(Path.of(REFLOG_PATH), StandardCharsets.UTF_8);
        check("参考服日志至少 27 行", ref.size() >= 27);
        List<String> refBlock = new ArrayList<>(ref.subList(9, 27));
        check("参考服日志第 10~27 行 = 18 行", refBlock.size() == 18);

        // ---- Java ↔ 参考服日志 ----
        for (int i = 0; i < 18; i++) {
            eq("日志行[" + (i + 10) + "] Java vs 参考服", javaLines.get(i), refBlock.get(i));
        }

        // ---- 真 JS 打出来的行 ↔ Java ----
        System.out.println("    真 JS 在注册成功那一遍共打出 " + js.logs.size() + " 行日志");
        int at = -1;
        for (int s = 0; s + 18 <= js.logs.size(); s++) {
            boolean all = true;
            for (int i = 0; i < 18; i++) {
                if (!js.logs.get(s + i).equals(javaLines.get(i))) {
                    all = false;
                    break;
                }
            }
            if (all) {
                at = s;
                break;
            }
        }
        check("真 JS 打出了与 Java 完全一致的 18 行连续加载日志（在 " + js.logs.size()
                + " 行里找到）", at >= 0);
        if (at >= 0) {
            for (int i = 0; i < 18; i++) {
                eq("日志行[" + (i + 10) + "] 真 JS vs Java",
                        js.logs.get(at + i), javaLines.get(i));
            }
        } else {
            for (int i = 0; i < 18; i++) {
                check("日志行[" + (i + 10) + "] 真 JS vs Java", false);
            }
        }
    }

    // ======================================================================
    // 三、数值 / 几何对账
    // ======================================================================

    /** 射线命中点的规范串（与 JS 返回的 {@code {x,y,z}} 对象写法对齐）。 */
    private static String hitNorm(WlSub.Hit h) {
        return h == null ? "null"
                : "{x:" + num(h.x) + ",y:" + num(h.y) + ",z:" + num(h.z) + "}";
    }

    /** 造一块带墙 / 台阶 / 坑的测试地形。 */
    private static Vox terrain() {
        Vox v = new Vox();
        // 地板
        v.fill(-40, -1, -40, 40, -1, 40);
        // 一堵 3 格高的墙（x = 10，z 从 -5 到 5）
        v.fill(10, 0, -5, 10, 2, 5);
        // 一堵 1 格高的台阶（x = -10）
        v.fill(-10, 0, -5, -10, 0, 5);
        // 一根 5 格高的柱子
        v.fill(20, 0, 20, 20, 4, 20);
        // 一面斜着的玻璃幕（z = 15，x 从 0 到 12，高 2）
        v.fill(0, 0, 15, 12, 1, 15);
        // 一些散落的单格
        v.set(3, 0, 3);
        v.set(4, 0, 3);
        v.set(-3, 1, -3);
        v.set(7, 2, -7);
        return v;
    }

    private static void sectionGeometry(Js js) throws Exception {
        System.out.println("[3] 数值 / 几何对账");
        Vox v = terrain();
        World w = fakeWorld(v, null);
        Value fBlocked = js.fn("gsWlBlockedAt");
        Value fRay = js.fn("gsWlRayHit");
        Value fDashRay = js.fn("gsWlDashRay");
        Value fStuck = js.fn("gsWlStuckAt");
        Value fSafeY = js.fn("gsWlSafeY");
        Value fStepY = js.fn("wlDashStepY");
        Value fAxes = js.fn("wlAxes");
        Value fAng = js.fn("wlAngDiff");
        Value fCd = js.fn("gsWlCdMsg");
        Value fSegs = js.fn("wlFxHorseSegs");
        for (Value f : new Value[]{fBlocked, fRay, fDashRay, fStuck, fSafeY, fStepY,
                fAxes, fAng, fCd, fSegs}) {
            if (f == null) {
                check("JS 函数缺失", false);
                return;
            }
        }

        Random rnd = new Random(20240611L);
        int n;

        // ---- 3a. gsWlBlockedAt ----
        n = 0;
        boolean allOk = true;
        int blockedTrue = 0;
        for (int i = 0; i < 4000; i++) {
            double x, y, z;
            if (i % 4 == 0) {
                x = rnd.nextInt(41) - 20;
                y = rnd.nextInt(7) - 1;
                z = rnd.nextInt(41) - 20;
            } else {
                x = rnd.nextDouble() * 44.0 - 22.0;
                y = rnd.nextDouble() * 7.0 - 1.5;
                z = rnd.nextDouble() * 44.0 - 22.0;
            }
            boolean a = fBlocked.execute(w, x, y, z).asBoolean();
            boolean b = ListenerCore.gsWlBlockedAt(w, x, y, z);
            if (a) {
                blockedTrue++;
            }
            if (a != b) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        blockedAt(" + x + "," + y + "," + z
                            + ") js=" + a + " java=" + b);
                }
            }
        }
        check("gsWlBlockedAt 逐点一致（4000 点，含整格与分数坐标）", allOk);
        check("gsWlBlockedAt 测试覆盖了「挡住 " + blockedTrue + " 次 / 不挡 "
                + (4000 - blockedTrue) + " 次」两种结果", blockedTrue > 0 && blockedTrue < 4000);

        // ---- 3b. gsWlRayHit（逐段碰撞检测，0.25 格步长）----
        n = 0;
        allOk = true;
        int rayHit = 0;
        for (int i = 0; i < 1200; i++) {
            double[] s = {rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 5 - 1,
                    rnd.nextDouble() * 40 - 20};
            double[] e = {rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 5 - 1,
                    rnd.nextDouble() * 40 - 20};
            String a = normJs(fRay.execute(w, s[0], s[1], s[2], e[0], e[1], e[2]));
            WlSub.Hit h = WlSub.gsWlRayHit(w, s[0], s[1], s[2], e[0], e[1], e[2]);
            String b = hitNorm(h);
            if (h != null) {
                rayHit++;
            }
            if (!a.equals(b)) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        rayHit " + java.util.Arrays.toString(s) + " -> "
                            + java.util.Arrays.toString(e) + " js=" + a + " java=" + b);
                }
            }
        }
        check("gsWlRayHit 逐段一致（1200 段随机射线）", allOk);
        check("gsWlRayHit 测试覆盖了「撞到 " + rayHit + " 段 / 畅通 "
                + (1200 - rayHit) + " 段」两种结果", rayHit > 0 && rayHit < 1200);

        // ---- 3c. gsWlDashRay（按玩家体积，0.4 格步长）----
        n = 0;
        allOk = true;
        int dashHit = 0;
        for (int i = 0; i < 1200; i++) {
            double[] s = {rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 5 - 1,
                    rnd.nextDouble() * 40 - 20};
            double[] e = {rnd.nextDouble() * 40 - 20, rnd.nextDouble() * 5 - 1,
                    rnd.nextDouble() * 40 - 20};
            String a = normJs(fDashRay.execute(w, s[0], s[1], s[2], e[0], e[1], e[2]));
            WlSub.Hit h = WlSub.gsWlDashRay(w, s[0], s[1], s[2], e[0], e[1], e[2]);
            String b = hitNorm(h);
            if (h != null) {
                dashHit++;
            }
            if (!a.equals(b)) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        dashRay " + java.util.Arrays.toString(s) + " -> "
                            + java.util.Arrays.toString(e) + " js=" + a + " java=" + b);
                }
            }
        }
        check("gsWlDashRay 逐段一致（1200 段随机射线）", allOk);
        check("gsWlDashRay 测试覆盖了「撞到 " + dashHit + " 段 / 畅通 "
                + (1200 - dashHit) + " 段」两种结果", dashHit > 0 && dashHit < 1200);

        // ---- 3d. gsWlStuckAt / gsWlSafeY ----
        n = 0;
        allOk = true;
        int stuckHits = 0;
        int safeLifts = 0;
        for (int i = 0; i < 2000; i++) {
            double x = rnd.nextDouble() * 44 - 22;
            double y = rnd.nextDouble() * 7 - 1.5;
            double z = rnd.nextDouble() * 44 - 22;
            boolean jsStuck = fStuck.execute(w, x, y, z).asBoolean();
            boolean jvStuck = WlSub.gsWlStuckAt(w, x, y, z);
            if (jsStuck) {
                stuckHits++;
            }
            if (jsStuck != jvStuck) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        stuckAt(" + x + "," + y + "," + z + ") js="
                            + jsStuck + " java=" + jvStuck);
                }
            }
            double jsSy = fSafeY.execute(w, x, y, z).asDouble();
            double jvSy = WlSub.gsWlSafeY(w, x, y, z);
            if (jsSy != y) {
                safeLifts++;
            }
            if (!num(jsSy).equals(num(jvSy))) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        safeY(" + x + "," + y + "," + z + ") js=" + jsSy
                            + " java=" + jvSy);
                }
            }
        }
        check("gsWlStuckAt / gsWlSafeY 逐点一致（2000 点；卡住 " + stuckHits + " 次 / 抬起 "
                + safeLifts + " 次）", allOk);
        check("gsWlStuckAt 测试确实覆盖了「卡住」与「没卡住」两种结果",
                stuckHits > 0 && stuckHits < 2000);
        check("gsWlSafeY 测试确实覆盖了「需要抬起」的情形", safeLifts > 0);

        // ---- 3e. wlDashStepY（冲锋路径检查：1 格台阶 vs ≥2 格撞墙）----
        n = 0;
        allOk = true;
        int hitsWall = 0;
        int stepsUp = 0;
        for (int i = 0; i < 3000; i++) {
            double x, y, z;
            if (i % 3 == 0) {
                x = rnd.nextInt(41) - 20;
                y = rnd.nextInt(6) - 1;
                z = rnd.nextInt(41) - 20;
            } else {
                x = rnd.nextDouble() * 44 - 22;
                y = rnd.nextDouble() * 6 - 1.5;
                z = rnd.nextDouble() * 44 - 22;
            }
            Value jr = fStepY.execute(w, x, y, z);
            Double kr = WuliSub.wlDashStepY(w, x, y, z);
            String a = normJs(jr);
            String b = normJava(kr);
            if (!a.equals(b)) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        stepY(" + x + "," + y + "," + z + ") js=" + a
                            + " java=" + b);
                }
            }
            if (kr == null) {
                hitsWall++;
            } else if (Math.abs(kr - y) > 1.0e-9) {
                stepsUp++;
            }
        }
        check("wlDashStepY 逐点一致（3000 点；撞墙 " + hitsWall + " 次 / 上台阶 " + stepsUp + " 次）",
                allOk);
        check("wlDashStepY 测试确实覆盖了「撞墙」与「上台阶」两种结果",
                hitsWall > 0 && stepsUp > 0);

        // ---- 3f. wlAxes（剑气 hitbox 的正交基）----
        n = 0;
        allOk = true;
        int axesNonZero = 0;
        for (int i = 0; i < 3000; i++) {
            Vector d = new Vector(rnd.nextDouble() * 2 - 1, rnd.nextDouble() * 2 - 1,
                    rnd.nextDouble() * 2 - 1);
            if (d.length() < 1.0e-6) {
                continue;
            }
            d.normalize();
            Value jr = fAxes.execute(d);
            WuliSub.Axes a = WuliSub.wlAxes(d);
            String jsv = "[" + num(jr.getMember("lx").asDouble()) + ","
                    + num(jr.getMember("ly").asDouble()) + ","
                    + num(jr.getMember("lz").asDouble()) + ","
                    + num(jr.getMember("ux").asDouble()) + ","
                    + num(jr.getMember("uy").asDouble()) + ","
                    + num(jr.getMember("uz").asDouble()) + "]";
            String jv = "[" + num(a.lx) + "," + num(a.ly) + "," + num(a.lz) + ","
                    + num(a.ux) + "," + num(a.uy) + "," + num(a.uz) + "]";
            if (a.lx != 0.0 || a.ly != 0.0 || a.lz != 0.0) {
                axesNonZero++;
            }
            if (!jsv.equals(jv)) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        wlAxes(" + d + ") js=" + jsv + " java=" + jv);
                }
            }
        }
        check("wlAxes 逐分量一致（3000 个随机方向）", allOk);
        check("wlAxes 测试里左右轴确实非零（" + axesNonZero + " / 3000）", axesNonZero == 3000);

        // ---- 3g. wlAngDiff ----
        n = 0;
        allOk = true;
        int angNonZero = 0;
        double angMax = 0.0;
        for (int i = 0; i < 3000; i++) {
            double a1 = rnd.nextDouble() * 1440 - 720;
            double b1 = rnd.nextDouble() * 1440 - 720;
            String jsv = num(fAng.execute(a1, b1).asDouble());
            String jv = num(WuliSub.wlAngDiff(a1, b1));
            double dv = fAng.execute(a1, b1).asDouble();
            if (dv != 0.0) {
                angNonZero++;
            }
            angMax = Math.max(angMax, Math.abs(dv));
            if (!jsv.equals(jv)) {
                allOk = false;
                if (n++ < 3) {
                    System.out.println("        wlAngDiff(" + a1 + "," + b1 + ") js=" + jsv
                            + " java=" + jv);
                }
            }
        }
        check("wlAngDiff 逐点一致（3000 对角度，含 ±360° 环绕）", allOk);
        check("wlAngDiff 测试覆盖了非零差值（" + angNonZero + " / 3000，最大 |差| = " + angMax
                + "°）", angNonZero > 0 && angMax > 170.0 && angMax <= 180.0);

        // ---- 3h. gsWlCdMsg（{s} → 剩余秒数，1 位小数）----
        n = 0;
        allOk = true;
        long[] cdCases = {0, 1, 49, 50, 51, 99, 100, 149, 150, 151, 949, 950, 951, 9949, 9950,
                9951, 9999, 10000, 14949, 14950, 14951, 15000, 24499, 24500, 24501, 24999,
                25000, 7999, 8000, 4500, 4499};
        List<long[]> cdTests = new ArrayList<>();
        for (long c : cdCases) {
            cdTests.add(new long[]{c});
        }
        for (int i = 0; i < 3000; i++) {
            cdTests.add(new long[]{rnd.nextInt(30001)});
        }
        int cdDot = 0;
        int cdSubst = 0;
        for (long[] c : cdTests) {
            for (String base : new String[]{WlSub.WL_BOMB_MSG_CD, WlSub.WL_CANNON_MSG_CD,
                    WlSub.WL_DASH2_MSG_CD, "no-placeholder", "{s}{s}"}) {
                String jsv = fCd.execute(base, c[0]).asString();
                String jv = WlSub.gsWlCdMsg(base, c[0]);
                if (jsv.contains(".")) {
                    cdDot++;
                }
                if (!jsv.equals(base)) {
                    cdSubst++;
                }
                if (!jsv.equals(jv)) {
                    allOk = false;
                    if (n++ < 3) {
                        System.out.println("        gsWlCdMsg(" + base + "," + c[0] + ") js=" + jsv
                                + " java=" + jv);
                    }
                }
            }
        }
        check("gsWlCdMsg 逐字节一致（" + cdTests.size() + " 个毫秒值 × 5 个模板）", allOk);
        check("gsWlCdMsg 测试覆盖了「带 . 的 1 位小数」形态（" + cdDot + " 次）", cdDot > 0);
        check("gsWlCdMsg 测试覆盖了「{s} 真的被替换掉」的情形（" + cdSubst + " 次）", cdSubst > 0);

        // ---- 3i. wlFxHorseSegs（粒子马骨架表）----
        Value segs = fSegs.execute();
        String jsv = normJs(segs);
        String jv = normJava(WuliSub.WL_FXHORSE_SEGS);
        eq("wlFxHorseSegs（头顶粒子马的骨架线段表）", jsv, jv);
        check("wlFxHorseSegs 至少 20 条线段", segs.getArraySize() >= 20);
    }

    // ======================================================================
    // 四、反向断言
    // ======================================================================

    private static void sectionReverse(Js js, List<String> refLines) throws Exception {
        System.out.println("[4] 反向断言");

        // ---- 4a. 滚木必须不在纯材料清单里 ----
        boolean rollingIn = false;
        for (String id : PlainSub.GS_BASIC_PLAIN_IDS) {
            if (id.equals("GS_ROLLING_LOG_INGOT")) {
                rollingIn = true;
            }
        }
        check("★ 反向：滚木 GS_ROLLING_LOG_INGOT 不在 GS_BASIC_PLAIN_IDS 里（保留右键隐身）",
                !rollingIn);
        check("★ 反向：JS 侧的 GS_BASIC_PLAIN_IDS 里也没有滚木",
                !jsContainsString(js.bind.getMember("GS_BASIC_PLAIN_IDS"), "GS_ROLLING_LOG_INGOT"));
        eq("★ 反向：纯材料件数 = 18（日志里的「18 件」是 length，不是硬编码）",
                String.valueOf(js.bind.getMember("GS_BASIC_PLAIN_IDS").getArraySize()),
                String.valueOf(PlainSub.GS_BASIC_PLAIN_IDS.length));

        // ---- 4b. 蓄力无敌 / 冲刺无敌的不对称 ----
        check("★ 反向：WL_CHARGE_INVULN = true（蓄力无敌）", WuliSub.WL_CHARGE_INVULN);
        check("★ 反向：WL_DASH_INVULN = false（冲刺不无敌）", !WuliSub.WL_DASH_INVULN);
        eq("★ 反向：JS 侧蓄力/冲刺无敌同样是 true/false",
                js.bind.getMember("WL_CHARGE_INVULN").asBoolean() + "/"
                        + js.bind.getMember("WL_DASH_INVULN").asBoolean(),
                WuliSub.WL_CHARGE_INVULN + "/" + WuliSub.WL_DASH_INVULN);

        // ---- 4c. 两个子系统的 WL_ 前缀不串味 ----
        boolean clean = true;
        for (String n : WULI_VARS) {
            if (n.startsWith("WL_BOMB_") || n.startsWith("WL_CANNON_") || n.startsWith("WL_DASH2_")
                    || n.startsWith("WL_WIND_") || n.startsWith("WL_BOOM_")
                    || n.startsWith("WL_SPEED_") || n.startsWith("WL_SPIT_")) {
                clean = false;
            }
        }
        for (String n : WL_VARS) {
            if (n.startsWith("WL_QI_") || n.startsWith("WL_RIDE_") || n.startsWith("WL_FLEE_")
                    || n.startsWith("WL_FXHORSE_") || n.startsWith("WL_DISSMOKE_")
                    || n.startsWith("WL_SMOKE_") || n.startsWith("WL_FORM")) {
                clean = false;
            }
        }
        check("★ 反向：五骊矟与威龙的 WL_ 常量集合互不相交（同前缀不同系统）", clean);
        check("★ 反向：WuliSub 里有 WL_HORSE_SPEED 但没有 WL_BOMB_DIST",
                hasField(WuliSub.class, "WL_HORSE_SPEED") && !hasField(WuliSub.class, "WL_BOMB_DIST"));
        check("★ 反向：WlSub 里没有 WL_QI_*", !hasField(WlSub.class, "WL_QI_DMG"));

        // ---- 4d. 参考服日志里这三个子系统的行数必须正好 6 / 10 / 2 ----
        int c1 = countRef(refLines, "五骊矟");
        int c2 = countRef(refLines, "威龙");
        int c3 = countRef(refLines, "基础构思");
        check("★ 反向：参考服日志里 五骊矟 相关行数 = 6（实际 " + c1 + "）", c1 == 6);
        check("★ 反向：参考服日志里 威龙 相关行数 = 10（实际 " + c2 + "）", c2 == 10);
        check("★ 反向：参考服日志里 基础构思 相关行数 = 2（实际 " + c3 + "）", c3 == 2);

        // 真 JS 打出来的行数也必须一样
        int j1 = countRef(js.logs, "五骊矟");
        int j2 = countRef(js.logs, "威龙");
        int j3 = countRef(js.logs, "基础构思");
        check("★ 反向：真 JS 打出的 五骊矟 行数 = 6（实际 " + j1 + "）", j1 == 6);
        check("★ 反向：真 JS 打出的 威龙 行数 = 10（实际 " + j2 + "）", j2 == 10);
        check("★ 反向：真 JS 打出的 基础构思 行数 = 2（实际 " + j3 + "）", j3 == 2);

        // ---- 4e. 关键"不能改"的布尔开关 ----
        check("★ 反向：WL_DASH2_FLAT = true（冲刺只走水平）", WlSub.WL_DASH2_FLAT);
        check("★ 反向：WL_NO_BREAK = true（手持时无法破坏方块）", WlSub.WL_NO_BREAK);
        check("★ 反向：WL_HIT_PLAYERS = false（炸弹不打玩家）", !WlSub.WL_HIT_PLAYERS);
        check("★ 反向：WL_DASH2_AXIS = false（日志因此不带「并吸附到东/南/西/北」后缀）",
                !WlSub.WL_DASH2_AXIS);
        check("★ 反向：WL_DASH_LOCK_LOOK = false（默认不锁视角）",
                !WuliSub.WL_DASH_LOCK_LOOK);
        check("★ 反向：WL_QI_NO_BLOCK = true（手持五骊矟时不与方块交互）",
                WuliSub.WL_QI_NO_BLOCK);
        check("★ 反向：WL_QI_KB_MUL = 0（冲刺自动斩的 4 道剑气完全无击退）",
                WuliSub.WL_DASH_QI_KB_MUL == 0.0);
        check("★ 反向：WL_QI_CD_MS = 500（剑气 0.5 秒冷却，日志里的 0.5 由它算出）",
                WuliSub.WL_QI_CD_MS == 500);
        check("★ 反向：WL_RIDE_TAG = 'gs_ridein'（伤害拦截器靠它保护引路马）",
                "gs_ridein".equals(WuliSub.WL_RIDE_TAG));
        check("★ 反向：WL_HORSE_SPEED = 0.28（只升不降的移速下限）",
                WuliSub.WL_HORSE_SPEED == 0.28);
        check("★ 反向：WL_BLOCK_STEP = 0.25（TNT 逐段碰撞步长，日志里的 0.25 由它算出）",
                WlSub.WL_BLOCK_STEP == 0.25);

        // ---- 4f. 三个类的源码里不许有 System.out.println ----
        String[] files = {
                "src/main/java/com/qinghuacisj/gs/script/module/listener/WuliSub.java",
                "src/main/java/com/qinghuacisj/gs/script/module/listener/WlSub.java",
                "src/main/java/com/qinghuacisj/gs/script/module/listener/PlainSub.java"
        };
        Map<String, String> srcs = new LinkedHashMap<>();
        for (String f : files) {
            Path p = Path.of("D:/qq下载/粘液科技自用/GSPlugin", f);
            String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            srcs.put(p.getFileName().toString(), src);
            check("★ 反向：" + p.getFileName() + " 里没有 System.out.println",
                    !src.contains("System.out.println"));
            check("★ 反向：" + p.getFileName() + " 里没有 System.err.println",
                    !src.contains("System.err.println"));
        }

        // ---- 4f-2. 闩锁「懒重试」的分布必须与原脚本一致 ----
        //   原脚本 4537 行（五骊矟 pulse 里）：if (!GS_WULI_REGISTERED) gsWuliRegisterSync();
        //   原脚本 5637 / 5868 行（威龙 / 基础构思）：只有幂等守卫，**没有**懒重试。
        check("★ 反向：WuliSub 的 pulse 里有懒重试 if (!GS_WULI_REGISTERED)（原脚本 4537 行）",
                srcs.get("WuliSub.java").contains("if (!GS_WULI_REGISTERED)"));
        check("★ 反向：WlSub 里没有 !GS_WL_REGISTERED 懒重试（原脚本 5637 行只有幂等守卫）",
                !srcs.get("WlSub.java").contains("!GS_WL_REGISTERED"));
        check("★ 反向：PlainSub 里没有 !GS_PLAIN_REGISTERED 懒重试（原脚本 5868 行只有幂等守卫）",
                !srcs.get("PlainSub.java").contains("!GS_PLAIN_REGISTERED"));

        // ---- 4f-3. 跨子系统成员不许在 Sub 里"私自定义后就算了" ----
        //   这三个名字的**定义**在原脚本区间外（24 / 1122 / 1630 行），
        //   本子系统只是**使用者**。Java 里为了能编译暂时自带了一份，
        //   必须在交付说明里报给集成者收进 ListenerCore。
        check("★ 反向：WuliSub 确实自带了一份 GS_MAIYOU_KEY（待收进 ListenerCore）",
                srcs.get("WuliSub.java").contains("GS_MAIYOU_KEY = key(\"gs:maiyou_fish\")"));
        check("★ 反向：WlSub 是引用 WuliSub.GS_MAIYOU_KEY 而不是自己再定义一份",
                srcs.get("WlSub.java").contains("WuliSub.GS_MAIYOU_KEY")
                        && !srcs.get("WlSub.java").contains("GS_MAIYOU_KEY ="));

        // ---- 4g. 监听器的 priority 与 ignoreCancelled 必须与原脚本一致 ----
        Class<?>[] listeners = {
                Class.forName("com.qinghuacisj.gs.script.module.listener.WuliSub$WuliListener"),
                Class.forName("com.qinghuacisj.gs.script.module.listener.WlSub$WlListener"),
                Class.forName("com.qinghuacisj.gs.script.module.listener.PlainSub$PlainListener")
        };
        int handlers = 0;
        boolean prioOk = true;
        boolean icOk = true;
        for (Class<?> c : listeners) {
            for (Method m : c.getDeclaredMethods()) {
                org.bukkit.event.EventHandler eh =
                        m.getAnnotation(org.bukkit.event.EventHandler.class);
                if (eh == null) {
                    continue;
                }
                handlers++;
                if (eh.priority() != org.bukkit.event.EventPriority.HIGHEST) {
                    prioOk = false;
                }
                // ★ 原脚本 registerEvent 只传 4 个参数 ⇒ ignoreCancelled 默认 false
                if (eh.ignoreCancelled()) {
                    icOk = false;
                }
            }
        }
        check("★ 反向：三个监听器类的 @EventHandler 全部 priority = HIGHEST（共 " + handlers + " 条）",
                prioOk);
        check("★ 反向：没有一条 @EventHandler 写了 ignoreCancelled = true（原脚本默认 false）", icOk);
        check("★ 反向：监听器条数 = 11（五骊矟）+ 8（威龙）+ 1（基础构思）= 20（实际 " + handlers
                + "）", handlers == 20);

        // ---- 4h. 注册闩锁初值 ----
        check("★ 反向：GS_WULI_REGISTERED 初值 = false", !wuli.isRegistered());
        check("★ 反向：GS_WL_REGISTERED 初值 = false", !wl.isRegistered());
        check("★ 反向：GS_PLAIN_REGISTERED 初值 = false", !plain.isRegistered());
    }

    private static boolean jsContainsString(Value arr, String s) {
        for (long i = 0; i < arr.getArraySize(); i++) {
            if (s.equals(arr.getArrayElement(i).asString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static int countRef(List<String> lines, String needle) {
        int n = 0;
        for (String l : lines) {
            if (l.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    // ======================================================================
    // main
    // ======================================================================

    public static void main(String[] args) throws Exception {
        wuli = new WuliSub(null);
        wl = new WlSub(null);
        plain = new PlainSub(null);

        List<String> refLines = Files.readAllLines(Path.of(REFLOG_PATH), StandardCharsets.UTF_8);

        System.out.println("=== 五骊矟 / 威龙 / 基础构思 · 对账探针 ===");
        sectionCoverage();

        // ---- 第 1 遍：注册失败（getPlugin → null）⇒ 闩锁保持初值，用于常量对账 ----
        try (Js jsOff = boot(false)) {
            sectionConstants(jsOff);
            check("★ 反向：真 JS 里 GS_WULI_REGISTERED 初值 = false（注册失败那一遍）",
                    !jsOff.bind.getMember("GS_WULI_REGISTERED").asBoolean());
            check("★ 反向：真 JS 里 GS_WL_REGISTERED 初值 = false",
                    !jsOff.bind.getMember("GS_WL_REGISTERED").asBoolean());
            check("★ 反向：真 JS 里 GS_PLAIN_REGISTERED 初值 = false",
                    !jsOff.bind.getMember("GS_PLAIN_REGISTERED").asBoolean());

            // ★★ 反向：注册失败时「注册成功」那 3 行**一行都不该打**，
            //    但 15 行参数日志照打（它们在原脚本里是 gsWuliRegisterSync() 之后
            //    **无条件**执行的 gsDaLog）。这正好证明「注册成功」这一行是脚本自己在
            //    注册成功后才打的 —— 也是拆分子系统时最容易漏掉的那一行。
            List<String> all = javaLogLines();
            List<String> registerOnly = new ArrayList<>();
            List<String> configOnly = new ArrayList<>();
            for (int i = 0; i < all.size(); i++) {
                if (i == 0 || i == 6 || i == 16) {
                    registerOnly.add(all.get(i));
                } else {
                    configOnly.add(all.get(i));
                }
            }
            boolean noReg = true;
            for (String r : registerOnly) {
                if (jsOff.logs.contains(r)) {
                    noReg = false;
                }
            }
            check("★ 反向：注册失败时 3 行「注册成功」一行都没打（真 JS 实际 "
                    + jsOff.logs.size() + " 行日志）", noReg);

            boolean allCfg = true;
            for (String c : configOnly) {
                if (!jsOff.logs.contains(c)) {
                    allCfg = false;
                    System.out.println("        缺少参数日志: " + c);
                }
            }
            check("★ 反向：注册失败时 15 行参数日志照样全打（gsDaLog 是无条件执行的）", allCfg);
        }

        // ---- 第 2 遍：注册成功（getPlugin → 假插件）⇒ 加载日志真的被打出来 ----
        try (Js jsOn = boot(true)) {
            sectionLogs(jsOn);
            sectionGeometry(jsOn);
            sectionReverse(jsOn, refLines);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
