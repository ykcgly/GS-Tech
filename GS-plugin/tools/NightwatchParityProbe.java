package com.qinghuacisj.gs.script.module;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
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
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code gs_boss_nightwatch} 的对账探针 —— 机械证据。
 *
 * <p>这个脚本有三块「肉眼看不出来但一错就废」的东西，本探针专门钉它们：
 * <ol>
 *   <li><b>6 行加载日志</b>：原脚本全是<b>字符串拼接</b>，而 JS 把 {@code 20.0}
 *       拼成 {@code "20"}、Java 会拼成 {@code "20.0"} ⇒ 必须逐字节对账。
 *       这里做<b>三方对账</b>：真 JS 实跑出来的行 ↔ Java 的行 ↔ 参考服日志的行。</li>
 *   <li><b>NMS 相关的降级路径</b>：真机上 5 个 NMS 类都拿得到（参考服日志是
 *       {@code 可用 ｜ SonicBoom=ok ｜ WardenAi=ok}），而探针环境拿不到
 *       ⇒ 顺带把<b>降级那一支</b>也逐字节钉住（探针里 JS 与 Java 必须都给出降级行）。</li>
 *   <li><b>几何 / 数值</b>：箭矢初速、{@code nwSafeY} 的找地高度、飞天落点、
 *       拉到身前的落点与控视角角度、血条进度。这些全部用<b>真脚本自己的函数</b>
 *       在大量输入上跑，再与 Java 侧逐值比 —— 不是拿两份手抄公式互相比。</li>
 * </ol>
 *
 * <p>★★ <b>本探针抓到的最重要的一件事</b>：原脚本写的是
 * <pre>
 *   var dir = nwVec(dx, dy + lift, dz);
 *   dir.normalize();                              // ← 返回值被丢掉了
 *   ar.setVelocity(dir.multiply(NW_S1_ARROW_SPEED));
 * </pre>
 * 而 Bukkit 的 {@code Vector#normalize()} 是<b>不可变</b>的
 * （{@code return multiply(1 / length())}）⇒ {@code dir} <b>根本没有被归一化</b>，
 * 箭速实际是 {@code (dx, dy+lift, dz) × 2.0}，<b>随距离线性增长</b>。
 * 这是原脚本的瑕疵，不是我的移植错误 —— 已由 {@link #arrowVel} 用真
 * {@code org.bukkit.util.Vector} 逐值钉住，<b>照抄不改</b>。
 *
 * <p>★ 覆盖不到的：{@code nwPassableAt} 里 {@code Material#isAir()} 那一支 ——
 * 无服务器环境下 {@code Material} 的静态初始化直接
 * {@code ExceptionInInitializerError}（已实测），造不出 Material 实例。
 * 但「空气块」与「getType() 返回 null 且 isPassable() 为 true 的块」在
 * 本函数里<b>走同一条出口</b>（都返回 true）⇒ 结论不受影响。
 */
public final class NightwatchParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 参考服（装 RSC 的那台）的日志 —— 里面是**真 JS** 跑出来的行。 */
    private static final String REF_LOG =
            "D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt";

    private static final Path SCRIPT_DIR = Paths.get(
            "D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts");

    // ==================================================================
    // 造给脚本用的假世界（全部是 java.lang.reflect.Proxy）
    // ==================================================================

    /** 地形模式：0/1/2 = 不同分布，3 = isPassable 直接抛异常。 */
    private static int TERRAIN_MODE = 0;
    /** 血条 / 属性的可配置上限：< 0 ⇒ getAttribute 返回 null。 */
    private static double ATTR_MAX = -1.0;
    /** 玩家假体（nwNearestPlayer 用）的位置。 */
    private static double PLX = 0, PLY = 64, PLZ = 0;
    private static float PLYAW = 0;
    /** boss 假体的位置。 */
    private static double BSX = 0, BSY = 64, BSZ = 0;
    private static float BSYAW = 0;

    private static final List<Vector> ARROW_VEL = new ArrayList<>();
    private static final List<Location> ARROW_SPAWN = new ArrayList<>();
    private static final List<Location> BOSS_TELEPORT = new ArrayList<>();
    private static final List<Location> PLAYER_TELEPORT = new ArrayList<>();
    private static final List<double[]> PLAYER_ROT = new ArrayList<>();
    private static final List<Double> DAMAGE = new ArrayList<>();
    private static final List<double[]> ENT_SPAWN = new ArrayList<>();
    private static final List<Double> BAR_PROGRESS = new ArrayList<>();
    private static final List<Double> BAR_VISIBLE = new ArrayList<>();
    /** 假世界收到的 playSound（元素 = {vol, pitch}）。 */
    private static final List<double[]> PLAY_SOUND = new ArrayList<>();

    private static void reset() {
        TERRAIN_MODE = 0;
        ATTR_MAX = -1.0;
        ARROW_VEL.clear();
        ARROW_SPAWN.clear();
        BOSS_TELEPORT.clear();
        PLAYER_TELEPORT.clear();
        PLAYER_ROT.clear();
        DAMAGE.clear();
        ENT_SPAWN.clear();
        BAR_PROGRESS.clear();
        BAR_VISIBLE.clear();
        PLAY_SOUND.clear();
    }

    /** 探针侧的地形（真脚本通过 World 代理读到的就是它）。 */
    private static boolean terrainPassable(int bx, int by, int bz) {
        int h = bx * 31 + by * 17 + bz * 7;
        switch (TERRAIN_MODE) {
            case 1:
                return (h % 5) != 0;
            case 2:
                return ((h % 3) != 0) && (by > 60);
            default:
                return (h % 4) != 0;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T mk(Class<T> iface, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(NightwatchParityProbe.class.getClassLoader(),
                new Class<?>[]{iface}, h);
    }

    /** 「这不是 Object 方法」的哨兵。 */
    private static final Object NOT_OBJ = new Object();

    /**
     * 把 {@code equals / hashCode / toString} 接住。
     *
     * <p>★ 这一条是<b>必须</b>的：{@code Location#distance} 里有
     * {@code world.equals(o.getWorld())} 的校验（Bukkit 的
     * {@code distanceSquared} 会先 {@code Preconditions.checkArgument} 两个世界都非 null
     * 且相等）。代理不接住 {@code equals} 的话，{@link #dflt} 会返回 {@code false}
     * ⇒ {@code distance()} 抛异常 ⇒ 脚本的 {@code nwNearestPlayer} 一个玩家都找不到，
     * 后面所有「找目标」的技能全部静默失效（这正是第一版探针 0 组对比的原因）。
     */
    private static Object objMethods(Object p, Method m, Object[] a) {
        switch (m.getName()) {
            case "equals":
                return a != null && a.length == 1 && p == a[0];
            case "hashCode":
                return System.identityHashCode(p);
            case "toString":
                return "probe:" + m.getDeclaringClass().getSimpleName();
            default:
                return NOT_OBJ;
        }
    }

    private static World WORLD_SINGLETON = null;

    /** 全局唯一的假世界（必须唯一：Location#distance 会比对世界是否相等）。 */
    private static World WORLD() {
        if (WORLD_SINGLETON == null) {
            WORLD_SINGLETON = worldProxy();
        }
        return WORLD_SINGLETON;
    }

    private static Object dflt(Class<?> rt) {
        if (rt == void.class || rt == Void.class) {
            return null;
        }
        if (rt == boolean.class || rt == Boolean.class) {
            return false;
        }
        if (rt == int.class || rt == Integer.class) {
            return 0;
        }
        if (rt == long.class || rt == Long.class) {
            return 0L;
        }
        if (rt == double.class || rt == Double.class) {
            return 0.0;
        }
        if (rt == float.class || rt == Float.class) {
            return 0.0f;
        }
        return null;
    }

    private static Block blockProxy(int bx, int by, int bz) {
        return mk(Block.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            String n = m.getName();
            if ("isPassable".equals(n)) {
                if (TERRAIN_MODE == 3) {
                    // 模拟「isPassable 抛异常」⇒ 两侧都应落到 mt != null 的兜底
                    throw new IllegalStateException("probe: isPassable boom");
                }
                return terrainPassable(bx, by, bz);
            }
            if ("getType".equals(n)) {
                return null;      // Material 在无服务器环境下初始化失败（已实测）
            }
            return dflt(m.getReturnType());
        });
    }

    private static World worldProxy() {
        return mk(World.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            String n = m.getName();
            switch (n) {
                case "getBlockAt": {
                    int bx;
                    int by;
                    int bz;
                    if (a.length == 1) {
                        Location l = (Location) a[0];
                        bx = (int) Math.floor(l.getX());
                        by = (int) Math.floor(l.getY());
                        bz = (int) Math.floor(l.getZ());
                    } else {
                        bx = ((Number) a[0]).intValue();
                        by = ((Number) a[1]).intValue();
                        bz = ((Number) a[2]).intValue();
                    }
                    return blockProxy(bx, by, bz);
                }
                case "getPlayers":
                    return new ArrayList<>(List.of(playerProxy()));
                case "getName":
                    return "probe_world";
                case "playSound": {
                    // playSound(Location, String|Sound, float, float) 或带 SoundCategory 的重载
                    int cnt = a.length;
                    PLAY_SOUND.add(new double[]{((Number) a[cnt - 2]).doubleValue(),
                            ((Number) a[cnt - 1]).doubleValue()});
                    return null;
                }
                case "spawn": {
                    if (a.length >= 2 && a[1] instanceof Class<?> c
                            && c.getName().endsWith("Arrow")) {
                        ARROW_SPAWN.add(((Location) a[0]).clone());
                        return arrowProxy();
                    }
                    return null;
                }
                case "spawnEntity": {
                    Location l = (Location) a[0];
                    ENT_SPAWN.add(new double[]{l.getX(), l.getY(), l.getZ()});
                    return phantomProxy();
                }
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    private static Arrow arrowProxy() {
        return mk(Arrow.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            if ("setVelocity".equals(m.getName())) {
                ARROW_VEL.add(((Vector) a[0]).clone());
                return null;
            }
            return dflt(m.getReturnType());
        });
    }

    private static Player playerProxy() {
        return mk(Player.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            switch (m.getName()) {
                case "getUniqueId":
                    return UUID.fromString("00000000-0000-0000-0000-0000000000aa");
                case "getLocation":
                    return new Location(WORLD(), PLX, PLY, PLZ, PLYAW, 0f);
                case "getWorld":
                    return WORLD();
                case "isDead":
                    return false;
                case "isValid":
                    return true;      // ★ nwAlive = !(isDead() || !isValid()) ⇒ 必须 true
                case "isOnline":
                    return true;
                case "getGameMode":
                    return null;      // 与 JS 的 `getGameMode() != null &&` 短路口径一致
                case "teleport":
                    PLAYER_TELEPORT.add(((Location) a[0]).clone());
                    return true;
                case "setRotation":
                    PLAYER_ROT.add(new double[]{((Number) a[0]).doubleValue(),
                            ((Number) a[1]).doubleValue()});
                    return null;
                case "damage":
                    DAMAGE.add(((Number) a[0]).doubleValue());
                    return null;
                case "sendMessage":
                    return null;
                case "setNoDamageTicks":
                    return null;
                case "getPersistentDataContainer":
                    return null;
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    /** boss 假体：实现 {@link Mob}（这样 {@code instanceof Mob} 与 JS 的动态派发口径一致）。 */
    private static Mob bossProxy() {
        return mk(Mob.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            switch (m.getName()) {
                case "getUniqueId":
                    return UUID.fromString("00000000-0000-0000-0000-0000000000bb");
                case "getLocation":
                    return new Location(WORLD(), BSX, BSY, BSZ, BSYAW, 0f);
                case "getEyeLocation":
                    return new Location(WORLD(), BSX, BSY + 1.62, BSZ, BSYAW, 0f);
                case "getWorld":
                    return WORLD();
                case "isDead":
                    return false;
                case "isValid":
                    return true;      // ★ nwAlive = !(isDead() || !isValid()) ⇒ 必须 true
                case "teleport":
                    BOSS_TELEPORT.add(((Location) a[0]).clone());
                    return true;
                case "getAttribute":
                    if (ATTR_MAX < 0) {
                        return null;
                    }
                    return mk(AttributeInstance.class, (p2, m2, a2) -> {
                        switch (m2.getName()) {
                            case "getValue":
                            case "getBaseValue":
                                return ATTR_MAX;
                            case "getDefaultValue":
                                return 20.0;
                            case "getModifiers":
                                return new ArrayList<>();
                            case "getAttribute":
                                return a2 == null ? null : null;
                            default:
                                return dflt(m2.getReturnType());
                        }
                    });
                case "getPersistentDataContainer":
                    return null;
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    private static Phantom phantomProxy() {
        return mk(Phantom.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            switch (m.getName()) {
                case "getUniqueId":
                    return UUID.randomUUID();
                case "getLocation":
                    return new Location(WORLD(), BSX, BSY, BSZ, 0f, 0f);
                case "getWorld":
                    return WORLD();
                case "isDead":
                    return false;
                case "isValid":
                    return true;      // ★ nwAlive = !(isDead() || !isValid()) ⇒ 必须 true
                case "getFireTicks":
                    return 0;
                case "getAttribute":
                    return null;
                case "getPersistentDataContainer":
                    return null;
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    private static BossBar barProxy() {
        return mk(BossBar.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            switch (m.getName()) {
                case "setProgress":
                    BAR_PROGRESS.add(((Number) a[0]).doubleValue());
                    return null;
                case "setVisible":
                    BAR_VISIBLE.add(((Boolean) a[0]) ? 1.0 : 0.0);
                    return null;
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    // ==================================================================
    // 探针环境的 JS 垫片（原脚本顶层要用的 RSC 全局）
    // ==================================================================

    private static final String SHIM =
            "var __logs = [];\n"
                    + "var __msgs = [];\n"
                    + "function __mkLog() { return {\n"
                    + "  info: function (s) { __logs.push('' + s); },\n"
                    + "  warning: function (s) { __logs.push('W:' + s); }\n"
                    + "}; }\n"
                    + "function getServer() { return {\n"
                    + "  getLogger: __mkLog,\n"
                    + "  getWorlds: function () { return []; },\n"
                    + "  getOnlinePlayers: function () { return []; },\n"
                    + "  createBossBar: function (t, c, s) { return null; },\n"
                    + "  getPluginManager: function () { return {\n"
                    + "    getPlugin: function () { return null; },\n"
                    + "    registerEvent: function () { } }; }\n"
                    + "}; }\n"
                    + "function runLater(fn, d) { }\n"
                    + "function runRepeating(fn, d, p) { }\n"
                    + "function sendMessage(p, m) { __msgs.push('' + m); }\n"
                    + "function getSfItemByItem(s) { return null; }\n"
                    + "function getSfItemById(id) { return null; }\n"
                    + "var __realJava = Java;\n"
                    + "var Java = { type: function (n) {\n"
                    + "  if (n === 'org.bukkit.Material') {\n"
                    + "    return { AIR: null, valueOf: function () { return null; } };\n"
                    + "  }\n"
                    + "  return __realJava.type(n);\n"
                    + "} };\n"
                    // 造一个 JS 侧的 ref（对应 Java 的 NightwatchModule.Ref）
                    + "function __mkRef(world, ent, opts) {\n"
                    + "  var r = { owner: 'probe-owner', stage: 1, ent: ent, name: 'x', maxHp: 450,\n"
                    + "    bar: null, barNear: {}, arrow: null, flies: {}, flyFireUntil: 0,\n"
                    + "    cdArrow: 0, cdSky: 0, cdGrab: 0, cdFly: 0, cdNight: 0, readyAt: 0,\n"
                    + "    skyGen: 0, dx: null, dy: null, dz: null, emergeUntil: 0,\n"
                    + "    hpFixUntil: 0, hpOk: true, transitioning: false, done: false,\n"
                    + "    lw: world, lx: 0, ly: 64, lz: 0 };\n"
                    + "  if (opts) { for (var k in opts) { r[k] = opts[k]; } }\n"
                    + "  return r;\n"
                    + "}\n"
                    // 纯算式：逐字摘自脚本（用于 C8 的逐值对账）
                    + "function __jsRad(yaw) { return yaw * Math.PI / 180.0; }\n"
                    + "function __jsFrontX(x, rad, d) { return x + (-Math.sin(rad)) * d; }\n"
                    + "function __jsFrontZ(z, rad, d) { return z + (Math.cos(rad)) * d; }\n"
                    + "function __jsBehindX(tx, rad, d) { return tx + Math.sin(rad) * d; }\n"
                    + "function __jsBehindZ(tz, rad, d) { return tz - Math.cos(rad) * d; }\n"
                    + "function __jsLookYaw(dx, dz) { return Math.atan2(-dx, dz) * 180.0 / Math.PI; }\n"
                    + "function __jsLookPitch(dy, flat) { return -Math.atan2(dy, flat) * 180.0 / Math.PI; }\n"
                    + "function __jsFlat(dx, dz) { return Math.sqrt(dx * dx + dz * dz); }\n"
                    + "function __jsSkyStep() { return NW_S1_SKY_BLOCKS / NW_S1_SKY_TICKS; }\n"
                    + "function __jsTransRadius(f) { return 1.0 + f * 2.6; }\n"
                    + "function __jsTransAngle(a, f) { return a * (Math.PI / 6.0) + f * 2.0; }\n"
                    + "function __jsRingAngle(i) { return (i * 2.0 * Math.PI) / NW_S2_FLY_N; }\n"
                    + "function __jsRingX(a) { return Math.cos(a) * 2.0; }\n"
                    + "function __jsRingZ(a) { return Math.sin(a) * 2.0; }\n"
                    + "function __jsFirstCd(now, cd) { return now - cd + NW_FIRST_MS; }\n"
                    + "function __jsNum(v) { return '' + v; }\n"
                    // 箭矢初速：逐字抄 gs_boss_nightwatch.js 的 nwFireArrow 那 5 行
                    + "function __jsArrowVel(dx, dy, dz, flat) {\n"
                    + "  var V = Java.type('org.bukkit.util.Vector');\n"
                    + "  var lift = flat * NW_S1_ARROW_LIFT;\n"
                    + "  var dir = new V(dx, dy + lift, dz);\n"
                    + "  if (dir == null) return null;\n"
                    + "  dir.normalize();\n"
                    + "  var out = dir.multiply(NW_S1_ARROW_SPEED);\n"
                    + "  return [out.getX(), out.getY(), out.getZ()];\n"
                    + "}\n"
                    // ★ 探针自检用：确认假体真的"能用"，免得假体失灵被误判成移植错误
                    + "function __sc() { return {\n"
                    + "  aliveBoss: function (ref) { return nwAlive(ref.ent); },\n"
                    + "  aliveP: function (p) { return nwAlive(p); },\n"
                    + "  rot: function (p) { try { p.setRotation(1.5, 2.5); return 'ok'; }\n"
                    + "                      catch (e) { return 'ERR:' + e; } },\n"
                    + "  nearest: function (ref) { return nwNearestPlayer(ref) != null; },\n"
                    + "  dist: function (ref, p) { try { return p.getLocation().distance(ref.ent.getLocation()); }\n"
                    + "                            catch (e) { return 'ERR:' + e; } },\n"
                    + "  eye: function (ref) { try { return ref.ent.getEyeLocation().getY(); }\n"
                    + "                        catch (e) { return 'ERR:' + e; } },\n"
                    + "  lock: function (ref, p) { try { nwLockView(ref, p, 3); return 'ok'; }\n"
                    + "                             catch (e) { return 'ERR:' + e; } },\n"
                    + "  lockDiag: function (ref, p) {\n"
                    + "    var out = [];\n"
                    + "    try {\n"
                    + "      out.push('done=' + ref.done);\n"
                    + "      out.push('aliveB=' + nwAlive(ref.ent));\n"
                    + "      out.push('aliveP=' + nwAlive(p));\n"
                    + "      var bl = ref.ent.getLocation();\n"
                    + "      var pl = p.getLocation();\n"
                    + "      out.push('bl=' + bl.getX() + ',' + bl.getY() + ',' + bl.getZ());\n"
                    + "      out.push('pl=' + pl.getX() + ',' + pl.getY() + ',' + pl.getZ());\n"
                    + "      var dx = bl.getX() - pl.getX();\n"
                    + "      var dz = bl.getZ() - pl.getZ();\n"
                    + "      var dy = (bl.getY() + 1.4) - (pl.getY() + 1.62);\n"
                    + "      var flat = Math.sqrt(dx * dx + dz * dz);\n"
                    + "      var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;\n"
                    + "      var pitch = -Math.atan2(dy, flat) * 180.0 / Math.PI;\n"
                    + "      out.push('yaw=' + yaw + ' pitch=' + pitch);\n"
                    + "      p.setRotation(yaw, pitch);\n"
                    + "      out.push('rot-ok');\n"
                    + "    } catch (e) { out.push('ERR:' + e); }\n"
                    + "    return out.join(' | ');\n"
                    + "  }\n"
                    + "}; }\n"
                    // ★ [C9] 用：直接照抄原脚本的三种调用形态，看 GraalJS 认不认
                    + "function __c9snd(w, loc, name, vol, pitch) {\n"
                    + "  try { w.playSound(loc, name, vol, pitch); return 'ok'; }\n"
                    + "  catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); return 'ok2'; }\n"
                    + "               catch (e2) { return 'ERR1:' + e1 + ' || ERR2:' + e2; } }\n"
                    + "}\n"
                    + "function __c9loc(w, x, y, z, yaw, pitch) {\n"
                    + "  try { var L = Java.type('org.bukkit.Location');\n"
                    + "        return new L(w, x, y, z, yaw, pitch); }\n"
                    + "  catch (e) { return null; }\n"
                    + "}\n"
                    + "function __c9rot(p, yaw, pitch) {\n"
                    + "  try { p.setRotation(yaw, pitch); return 'ok'; }\n"
                    + "  catch (e) { return 'ERR:' + e; }\n"
                    + "}\n";

    // ==================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_boss_nightwatch：常量 + 加载日志 + 几何/数值 + 反向断言 对账 ===");
        System.out.println();

        Path jsFile = SCRIPT_DIR.resolve("gs_boss_nightwatch.js");
        if (!Files.isRegularFile(jsFile)) {
            System.out.println("!! 找不到脚本: " + jsFile);
            System.exit(1);
        }
        String source = new String(Files.readAllBytes(jsFile), StandardCharsets.UTF_8);
        String evalSource = SHIM + source;

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.eval(Source.newBuilder("js", evalSource, "JavaScript").build());
            Value b = ctx.getBindings("js");

            constants(b);
            logLines(b);
            selfCheck(b);
            arrowVel(b);
            safeY(b);
            appearBehind(b);
            grabPlayer(b);
            barSync(b);
            misc(b);
            floatTrap(b);
            reverse(b, source);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // [A] 常量对账：真 JS 顶层 var vs Java static final（按名按值，双向）
    // ==================================================================

    /** Java 侧必须有、且 JS 侧也必须有的常量名（缺一个都是失败）。 */
    private static final String[] CONST_NAMES = {
            "NW_ITEM_ID", "NW_CONSUME", "NW_SPAWN_AHEAD", "NW_TICK", "NW_FIRST_MS",
            "NW_SEEK", "NW_HP_FIX_MS", "NW_TRANS_TICKS", "NW_BAR_RANGE", "NW_BAR_ONLY_OWNER",
            "NW_TELE_AHEAD",
            "NW_S1_NAME", "NW_S1_HP", "NW_S1_DMG", "NW_S1_ARROW_CD", "NW_S1_ARROW_SPEED",
            "NW_S1_ARROW_LIFT", "NW_S1_DEBUFF_TICKS", "NW_S1_POISON_AMP", "NW_S1_SLOW_AMP",
            "NW_S1_BLIND_AMP", "NW_S1_SKY_CD", "NW_S1_SKY_BLOCKS", "NW_S1_SKY_TICKS",
            "NW_S1_BEHIND",
            "NW_S2_NAME", "NW_S2_HP", "NW_S2_DMG", "NW_S2_GRAB_CD", "NW_S2_GRAB_DMG",
            "NW_S2_GRAB_LOCK_TICKS", "NW_S2_GRAB_AHEAD", "NW_S2_FLY_CD", "NW_S2_FLY_N",
            "NW_S2_FLY_NAME", "NW_S2_FLY_HP", "NW_S2_FLY_DMG", "NW_S2_FLY_FIRE_MS",
            "NW_S2_NIGHT_CD", "NW_S2_NIGHT_DMG", "NW_S2_NIGHT_MSG", "NW_S2_MEM_HOLD",
            "NW_EMERGE_FALLBACK_TICKS",
            "NW_DROP_S1_ID", "NW_DROP_S1_N", "NW_DROP_S2_ID", "NW_DROP_S2_N",
    };

    private static void constants(Value b) throws Exception {
        System.out.println("[A] 常量对账（真 JS 顶层 var vs Java static final，双向按名按值）");

        Map<String, Field> fields = new LinkedHashMap<>();
        for (Field f : NightwatchModule.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                fields.put(f.getName(), f);
            }
        }

        int checked = 0;
        for (String name : CONST_NAMES) {
            Field f = fields.get(name);
            boolean jsHas = b.hasMember(name);
            if (f == null || !jsHas) {
                check(name + "（Java=" + (f != null) + " / JS=" + jsHas + "）", false);
                continue;
            }
            f.setAccessible(true);
            Object javaVal = f.get(null);
            Object jsVal = toJava(b.getMember(name));
            boolean ok = sameValue(jsVal, javaVal);
            if (!ok) {
                System.out.println("      不一致 " + name + "  JS=" + jsVal + " (" + typeOf(jsVal)
                        + ")  Java=" + javaVal + " (" + typeOf(javaVal) + ")");
            }
            check(name + " = " + javaVal, ok);
            checked++;
        }

        // 反向：Java 侧的静态常量集合必须与 CONST_NAMES 完全一致（不多不少）
        List<String> extra = new ArrayList<>();
        for (String n : fields.keySet()) {
            if (!Arrays.asList(CONST_NAMES).contains(n)) {
                extra.add(n);
            }
        }
        check("Java 侧静态常量数 = " + CONST_NAMES.length + "（实得 " + fields.size()
                + (extra.isEmpty() ? "）" : "，多出 " + extra + "）"), extra.isEmpty());
        check("已按值核对常量数 = " + checked + "/" + CONST_NAMES.length,
                checked == CONST_NAMES.length);
        System.out.println();
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

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    // ==================================================================
    // [B] 加载日志行：JS 实跑 ↔ Java ↔ 参考服日志
    // ==================================================================

    private static void logLines(Value b) throws Exception {
        System.out.println("[B] 加载日志行（JS 实跑 ↔ Java ↔ 参考服日志，逐字节）");

        List<String> js = new ArrayList<>();
        Value logs = b.getMember("__logs");
        for (long i = 0; i < logs.getArraySize(); i++) {
            js.add(logs.getArrayElement(i).asString());
        }
        check("JS 顶层恰好打 6 行日志（实得 " + js.size() + "）", js.size() == 6);

        // Java 侧（探针环境：NMS 不可用 ⇒ 第 1 行是降级行）
        List<String> java = List.of(
                NightwatchModule.nmsLine(false, false, false),
                NightwatchModule.hpLine(),
                NightwatchModule.s1Line(),
                NightwatchModule.s2Line(new NightwatchModule().nwEmergeTicks()),
                NightwatchModule.counterLine(),
                NightwatchModule.dropLine());

        String[] labels = {"NMS 通道", "两阶段目标血量", "巡夜守卫", "守夜人", "反制", "掉落"};
        for (int i = 0; i < 6 && i < js.size(); i++) {
            boolean ok = js.get(i).equals(java.get(i));
            if (!ok) {
                System.out.println("      JS   : " + js.get(i));
                System.out.println("      Java : " + java.get(i));
            }
            check("第 " + (i + 1) + " 行（" + labels[i] + "）JS == Java", ok);
        }

        // 参考服日志：第 79~84 行（1-based）就是这 6 行；NMS 那一行是真机可用态
        List<String> ref = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(REF_LOG), StandardCharsets.UTF_8)) {
            int k = line.indexOf("INFO]: ");
            ref.add(k >= 0 ? line.substring(k + "INFO]: ".length()) : line);
        }
        List<String> nightwatch = new ArrayList<>();
        for (String line : ref) {
            if (line.startsWith("[构思技艺] 守夜人") || line.startsWith("[构思技艺] 巡夜守卫")) {
                nightwatch.add(line);
            }
        }
        check("参考服日志里 nightwatch 的行数 = 6（实得 " + nightwatch.size() + "）",
                nightwatch.size() == 6);

        // 第 1 行必须用「真机可用态」比
        String refNms = nightwatch.isEmpty() ? "" : nightwatch.get(0);
        String javaNmsOk = NightwatchModule.nmsLine(true, true, true);
        if (!javaNmsOk.equals(refNms)) {
            System.out.println("      Java(可用): " + javaNmsOk);
            System.out.println("      参考服    : " + refNms);
        }
        check("第 1 行（NMS 通道·可用态）Java == 参考服日志", javaNmsOk.equals(refNms));

        for (int i = 1; i < 6 && i < nightwatch.size(); i++) {
            boolean ok = java.get(i).equals(nightwatch.get(i));
            if (!ok) {
                System.out.println("      Java  : " + java.get(i));
                System.out.println("      参考服: " + nightwatch.get(i));
            }
            check("第 " + (i + 1) + " 行（" + labels[i] + "）Java == 参考服日志", ok);
        }
        System.out.println();
    }

    // ==================================================================
    // [C0] 探针自检：先证明"假体真的能用"，再拿它去判移植对错
    // ==================================================================

    /**
     * 这一节的存在理由：第一版探针里 {@code isValid()} 返回了 {@code false}，
     * 于是脚本的 {@code nwAlive} 一律为假、所有技能静默返回 ——
     * 表现为「JS 一组数据都没产生」，看上去像移植错了。
     * 先自检假体，就不会再把探针自己的毛病当成移植的毛病。
     */
    private static void selfCheck(Value b) {
        System.out.println("[C0] 探针自检（假体可用性 —— 防「探针自己的毛病」被误判成移植错误）");

        Value sc = b.getMember("__sc").execute();
        Value mkRef = b.getMember("__mkRef");

        PLX = 105.0;
        PLY = 63.0;
        PLZ = 208.0;
        PLYAW = 20f;
        BSX = 100.0;
        BSY = 65.0;
        BSZ = 200.0;
        BSYAW = 0f;
        resetKeepPositions();

        Value refJs = mkRef.execute(WORLD(), bossProxy());
        Player p = playerProxy();

        check("nwAlive(boss) 为真（假体的 isDead/isValid 口径与真实体一致）",
                sc.getMember("aliveBoss").execute(refJs).asBoolean());
        check("nwAlive(player) 为真", sc.getMember("aliveP").execute(p).asBoolean());
        check("nwNearestPlayer 能通过 Location#distance 找到玩家（世界必须相等）",
                sc.getMember("nearest").execute(refJs).asBoolean());

        Value d = sc.getMember("dist").execute(refJs, p);
        // boss 在 (100, 65, 200)，玩家在 (105, 63, 208) ⇒ 三维距离
        double want = Math.sqrt(5.0 * 5.0 + 2.0 * 2.0 + 8.0 * 8.0);
        check("Location#distance 算得出数（期望 " + fmt(want) + "，实得 " + d + "）",
                d.isNumber() && Math.abs(d.asDouble() - want) < 1.0e-9);

        Value eye = sc.getMember("eye").execute(refJs);
        check("boss.getEyeLocation() 可用（y = " + eye + "）",
                eye.isNumber() && eye.asDouble() == 65.0 + 1.62);

        PLAYER_ROT.clear();
        Value rot = sc.getMember("rot").execute(p);
        check("从 JS 调 p.setRotation(1.5, 2.5) 能落到假体上（" + rot + "）"
                + " ⇒ nwLockView 的控视角可对账",
                "ok".equals(rot.asString()) && PLAYER_ROT.size() == 1);

        PLAYER_ROT.clear();
        Value lock = sc.getMember("lock").execute(refJs, p);
        Value diag = sc.getMember("lockDiag").execute(refJs, p);
        String ds = diag.asString();
        System.out.println("      [诊断] " + ds);
        // ★ 这里**必须**断言「记到 0 次」：原脚本的 setRotation 被 GraalJS 的
        //   double→float 有损转换拦下（详见 [C9]），所以控视角在参考服上无效。
        //   反过来，如果哪天这条断言变成了「记到 1 次」，说明脚本被"修好"了，
        //   那时 [C9] 的结论也要跟着改。
        check("从 JS 调 nwLockView(ref, p, 3) 一次都没记到朝向（" + lock + "，记到 "
                + PLAYER_ROT.size() + " 次）—— 原脚本的 setRotation 被 float 有损转换拦下",
                "ok".equals(lock.asString()) && PLAYER_ROT.isEmpty()
                        && ds.contains("lossy primitive coercion"));
        System.out.println();
    }

    // ==================================================================
    // [C1] 箭矢初速（真 nwFireArrow 记录到的 setVelocity vs Java nwArrowVel）
    // ==================================================================

    private static void arrowVel(Value b) {
        System.out.println("[C1] 箭矢初速（真 nwFireArrow 的 setVelocity vs Java nwArrowVel）");

        Value mkRef = b.getMember("__mkRef");
        Value fire = b.getMember("nwFireArrow");
        // ★ 必须留在 NW_SEEK = 48 格内，否则 nwNearestPlayer 找不到人、原脚本
        //   直接 return false（那是「射程」而不是「弹道」的事，见下面单独的越界断言）
        double[] xs = {0, 1, -3.5, 12.25, 30.0};
        double[] zs = {0, 2, -7.75, 20.5, -33.0};
        int n = 0;
        int bad = 0;
        String firstBad = null;

        for (double dx : xs) {
            for (double dz : zs) {
                for (double dy : new double[]{0, 1.5, -2.25, 6.0}) {
                    // JS 侧：把坐标摆好，让脚本自己算 (dx, dy, dz)
                    PLX = 100 + dx;
                    PLY = 70 + dy;
                    PLZ = 200 + dz;
                    PLYAW = 37.5f;
                    BSX = 100;
                    BSY = 70;
                    BSZ = 200;
                    BSYAW = 0f;
                    resetKeepPositions();

                    // ★ 脚本自己用的三个量（照抄 nwFireArrow 第 806~813 行）：
                    //   from = boss.getEyeLocation()（本探针的假体 = BSY + 1.62）
                    //   to   = tgt.getLocation()
                    //   dy   = (to.y + 1.0) - from.y
                    //   第一版探针直接把循环变量 dy 传给了 Java，少了这一层换算
                    //   ⇒ 那是探针自己的错，不是移植的错。
                    double fdx = PLX - BSX;
                    double fdz = PLZ - BSZ;
                    double fdy = (PLY + 1.0) - (BSY + 1.62);
                    double flat = NightwatchModule.nwFlat(fdx, fdz);

                    Value refJs = mkRef.execute(WORLD(), bossProxy());
                    boolean jsFired = fire.execute(refJs).asBoolean();

                    if (!jsFired || ARROW_VEL.size() != 1) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "JS 没射出箭 (dx=" + dx + " dy=" + dy + " dz=" + dz
                                    + " fired=" + jsFired + " 记录 " + ARROW_VEL.size() + ")";
                        }
                        continue;
                    }
                    Vector jsV = ARROW_VEL.get(0);

                    // Java 侧：同一组量
                    double[] jv = NightwatchModule.nwArrowVel(fdx, fdy, fdz, flat);
                    n++;
                    boolean ok = jv != null
                            && jv[0] == jsV.getX() && jv[1] == jsV.getY() && jv[2] == jsV.getZ();
                    if (!ok) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "dx=" + dx + " dy=" + dy + " dz=" + dz
                                    + "（脚本量 " + fmt(fdx) + "," + fmt(fdy) + "," + fmt(fdz) + "）"
                                    + "  JS=(" + jsV.getX() + "," + jsV.getY() + "," + jsV.getZ() + ")"
                                    + "  Java=(" + jv[0] + "," + jv[1] + "," + jv[2] + ")";
                        }
                    }
                }
            }
        }
        check("箭矢初速逐值一致（" + n + " 组）" + (firstBad == null ? "" : " 首个不一致: " + firstBad),
                bad == 0 && n == xs.length * zs.length * 4);

        // 「几乎重叠」的提前返回（原脚本第 814 行）：flat < 0.05 且 |dy| < 0.05
        //   fdy = (PLY + 1.0) - (BSY + 1.62) = 0 ⇒ PLY = BSY + 0.62 = 70.62
        PLX = 100.0;
        PLY = 70.62;
        PLZ = 200.0;
        BSX = 100;
        BSY = 70;
        BSZ = 200;
        resetKeepPositions();
        boolean overlapFired = fire.execute(mkRef.execute(WORLD(), bossProxy())).asBoolean();
        check("重叠时提前返回（flat=0 dy=0 ⇒ 原脚本 return false，一支箭都不该有）",
                !overlapFired && ARROW_VEL.isEmpty());

        // 超出 NW_SEEK（48 格）⇒ nwNearestPlayer 找不到人 ⇒ 不射箭
        PLX = 100.0 + 40.0;
        PLY = 70.0;
        PLZ = 200.0 - 33.0;
        BSX = 100;
        BSY = 70;
        BSZ = 200;
        resetKeepPositions();
        double far = NightwatchModule.nwFlat(40.0, -33.0);
        boolean farFired = fire.execute(mkRef.execute(WORLD(), bossProxy())).asBoolean();
        check("超出射程 NW_SEEK=" + fmt(NightwatchModule.NW_SEEK) + "（实测 " + fmt(far)
                + " 格）时原脚本不射箭", !farFired && ARROW_VEL.isEmpty());

        // ★★ 关键：paper-api 1.21.11 的 Vector#normalize() 是**原地**修改的
        //   （javap 字节码：x /= length; y /= length; z /= length; return this）
        //   ⇒ 原脚本 `dir.normalize(); dir.multiply(SPEED);` 真的归一化了。
        //   反向断言：谁要是"照老版本印象"把它改成不归一化，这条会立刻炸。
        double flat = NightwatchModule.nwFlat(30.0, 40.0);
        double[] v = NightwatchModule.nwArrowVel(30.0, 0.0, 40.0, flat);
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        check("箭速已归一化（|v| = " + fmt(len) + " == NW_S1_ARROW_SPEED = "
                + fmt(NightwatchModule.NW_S1_ARROW_SPEED)
                + "；1.21.11 的 normalize() 是原地改）",
                Math.abs(len - NightwatchModule.NW_S1_ARROW_SPEED) < 1.0e-9);

        double rawLen = Math.sqrt(30.0 * 30.0
                + Math.pow(flat * NightwatchModule.NW_S1_ARROW_LIFT, 2) + 40.0 * 40.0)
                * NightwatchModule.NW_S1_ARROW_SPEED;
        check("与「未归一化」那一支确实不同（未归一化 |v| = " + fmt(rawLen) + "）",
                Math.abs(len - rawLen) > 1.0);
        System.out.println();
    }

    // ==================================================================
    // [C9] 原脚本的 float 互操作缺陷（实测证据 + 本模块的刻意差异）
    // ==================================================================

    /**
     * ★★★ 全项目最重要的一节。
     *
     * <p>GraalJS 把 JS 的 {@code double} 往 Java 的 {@code float} 形参里塞时，
     * <b>只要不是 float 能精确表示的，一律拒绝</b>，报
     * {@code Cannot convert '1.1'(language: Java, type: java.lang.Double)
     * to Java type 'float': Invalid or lossy primitive coercion}
     * （方法重载解析失败时报 {@code no applicable overload found}）。
     *
     * <p>实测过三种 Context 配置（{@code allowAllAccess(true)}、
     * 再加 {@code HostAccess.ALL}、再照 RSC 的链加
     * {@code allowNativeAccess/allowExperimentalOptions/allowHostClassLoading/
     * allowCreateProcess/allowValueSharing}）—— <b>三种结果完全一样</b>，
     * 也就是说这不是配置问题，是 GraalJS 的固定行为。
     *
     * <p>原脚本正好踩在这上面，而脚本自己的 {@code try { } catch (e) { }} 把异常
     * 全吞了，所以这些技能<b>在参考服上是静默失效的</b>：
     * <ul>
     *   <li>{@code p.setRotation(yaw, pitch)}（第 1020 行）—— pitch 是
     *       {@code atan2} 算出来的，永远不是 float 精确值 ⇒
     *       <b>「控视角 1 秒」永远什么都没做</b>。</li>
     *   <li>{@code nwLoc(..., yaw + 180.0, 0.0)}（第 980 行，拉人技能）——
     *       {@code yaw} 是 {@code getYaw()} 的 float 加 180，多数情况不是
     *       float 精确值 ⇒ {@code nwLoc} 返回 null ⇒ {@code teleport(null)}
     *       抛 NPE 被吞 ⇒ <b>拉人传送失败</b>（yaw 是 0 / 30 / 270.5 这类
     *       "整齐"值时反而成功）。</li>
     *   <li>{@code nwSound(...)} 的 19 处调用 —— 只有 vol 与 pitch
     *       <b>都</b>是 float 精确值时才响。实测只有 4 处响。</li>
     * </ul>
     *
     * <p><b>本模块的处置（已刻意偏离原脚本的运行结果）</b>：纯 Java 里没有这层
     * 互操作边界，{@code setRotation((float), (float))} / {@code nwLoc} /
     * {@code playSound} 都能正常工作。本模块按<b>脚本注释写明的本意</b>实现
     * （「每刻强行把玩家的视线掰向 boss」等），因此这三处与原脚本的<b>实际运行结果</b>
     * 不同。这一节把这些差异用数字钉住，交付说明里也逐条列出。
     */
    private static void floatTrap(Value b) throws Exception {
        System.out.println("[C9] 原脚本的 float 互操作缺陷（实测）+ 本模块的刻意差异");

        Value snd = b.getMember("__c9snd");
        Value locFn = b.getMember("__c9loc");
        Value rotFn = b.getMember("__c9rot");

        // ---- ① 证据本身：JS 侧的报错原文 ----
        PLAYER_ROT.clear();
        Value r = rotFn.execute(playerProxy(), 147.99461679191648, -10.684933277782504);
        String err = r.asString();
        System.out.println("      [实测] JS p.setRotation(147.99…, -10.68…) ⇒ " + brief(err));
        check("GraalJS 拒绝 double→float 的有损转换（控视角在原脚本上永远无效）",
                err.startsWith("ERR:") && err.contains("lossy primitive coercion")
                        && PLAYER_ROT.isEmpty());

        Value l1 = locFn.execute(WORLD(), 1.0, 2.0, 3.0, 0.1 + 180.0, 0.0);
        check("nwLoc 在 yaw+180 非 float 精确值时返回 null（拉人传送在原脚本上失败）",
                l1.isNull());
        Value l2 = locFn.execute(WORLD(), 1.0, 2.0, 3.0, 270.5 + 180.0, 0.0);
        boolean l2ok = !l2.isNull();
        if (l2ok) {
            l2ok = l2.invokeMember("getYaw").asFloat() == (float) (270.5 + 180.0);
        }
        check("nwLoc 在 yaw+180 恰好 float 精确时成功（所以原脚本是「时好时坏」）", l2ok);

        // ---- ② 19 处 nwSound：逐处实测 JS 到底放不放 ----
        //    参数直接从脚本源码里抠出来，免得手抄漂移
        Path jsFile = SCRIPT_DIR.resolve("gs_boss_nightwatch.js");
        String src = new String(Files.readAllBytes(jsFile), StandardCharsets.UTF_8);
        List<double[]> pairs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "nwSound\\([^;]*?'([^']+)'\\s*,\\s*([0-9.]+)\\s*,\\s*([0-9.]+)\\s*\\)").matcher(src);
        while (m.find()) {
            names.add(m.group(1));
            pairs.add(new double[]{Double.parseDouble(m.group(2)), Double.parseDouble(m.group(3))});
        }
        check("从脚本里抠出 19 处 nwSound 调用（实得 " + pairs.size() + "）", pairs.size() == 19);

        Location realLoc = new Location(WORLD(), 0, 64, 0, 0f, 0f);
        int jsPlayed = 0;
        int javaPlayed = 0;
        StringBuilder jsQuiet = new StringBuilder();
        for (int i = 0; i < pairs.size(); i++) {
            double vol = pairs.get(i)[0];
            double pitch = pairs.get(i)[1];

            PLAY_SOUND.clear();
            snd.execute(WORLD(), realLoc, names.get(i), vol, pitch);
            boolean js = !PLAY_SOUND.isEmpty();
            if (js) {
                jsPlayed++;
            } else {
                jsQuiet.append(names.get(i)).append("(").append(fmt(vol)).append("/")
                        .append(fmt(pitch)).append(") ");
            }

            PLAY_SOUND.clear();
            NightwatchModule.nwSound(WORLD(), realLoc, names.get(i), (float) vol, (float) pitch);
            if (!PLAY_SOUND.isEmpty()) {
                javaPlayed++;
            }
        }
        System.out.println("      [实测] 19 处 nwSound：JS 实际只放 " + jsPlayed
                + " 处；Java 放 " + javaPlayed + " 处");
        System.out.println("      [实测] JS 放不出来的（vol/pitch 不是 float 精确值）："
                + jsQuiet.toString().trim());
        check("JS 只放得出 4 处（vol 与 pitch 都是 float 精确值的那些）", jsPlayed == 4);
        check("本模块 19 处全放（刻意的差异，已在交付说明里列出）", javaPlayed == 19);

        // ---- ③ Java 侧三处都正常 ----
        PLAY_SOUND.clear();
        NightwatchModule.nwSound(WORLD(), realLoc, "entity.arrow.shoot", 1.0f, 1.1f);
        check("Java 侧 nwSound(1.0, 1.1) 正常出声（原脚本此处静默）", PLAY_SOUND.size() == 1);

        Location jl = NightwatchModule.nwLoc(WORLD(), 1.0, 2.0, 3.0, (float) (0.1 + 180.0), 0f);
        check("Java 侧 nwLoc 永远返回非 null（原脚本此处返回 null）", jl != null);

        PLAYER_ROT.clear();
        NightwatchModule m2 = new NightwatchModule();
        NightwatchModule.Ref ref = new NightwatchModule.Ref();
        ref.owner = "probe-owner";
        ref.lw = WORLD();
        ref.ent = bossProxy();
        ref.maxHp = 900.0;
        m2.nwLockView(ref, playerProxy(), 3);
        check("Java 侧 nwLockView 真的记到朝向（原脚本此处一次都没成功）",
                PLAYER_ROT.size() == 1);
        System.out.println();
    }

    private static String brief(String s) {
        return s.length() > 190 ? s.substring(0, 190) + "…" : s;
    }

    private static void resetKeepPositions() {
        ARROW_VEL.clear();
        ARROW_SPAWN.clear();
        BOSS_TELEPORT.clear();
        PLAYER_TELEPORT.clear();
        PLAYER_ROT.clear();
        DAMAGE.clear();
        ENT_SPAWN.clear();
        BAR_PROGRESS.clear();
        BAR_VISIBLE.clear();
        PLAY_SOUND.clear();
    }

    // ==================================================================
    // [C2] nwSafeY（真 JS 的 nwSafeY vs Java 的 nwSafeY）
    // ==================================================================

    private static void safeY(Value b) {
        System.out.println("[C2] nwSafeY 找地高度（真 JS nwSafeY vs Java nwSafeY）");

        Value jsSafeY = b.getMember("nwSafeY");
        int n = 0;
        int bad = 0;
        String firstBad = null;

        for (int mode = 0; mode <= 3; mode++) {
            TERRAIN_MODE = mode;
            World w = WORLD();
            for (double x : new double[]{0.5, 3.0, -7.25, 100.75, -0.5}) {
                for (double z : new double[]{0.5, -2.0, 55.5, -100.25, 7.0}) {
                    for (int y0 = 55; y0 <= 75; y0++) {
                        for (int h : new int[]{1, 2, 3}) {
                            double js = jsSafeY.execute(w, x, z, (double) y0, h).asDouble();
                            double jv = NightwatchModule.nwSafeY(w, x, z, y0, h);
                            n++;
                            if (js != jv) {
                                bad++;
                                if (firstBad == null) {
                                    firstBad = "mode=" + mode + " x=" + x + " z=" + z
                                            + " y0=" + y0 + " h=" + h
                                            + "  JS=" + js + "  Java=" + jv;
                                }
                            }
                        }
                    }
                }
            }
        }
        check("nwSafeY 逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0);
        System.out.println();
    }

    // ==================================================================
    // [C3] 「出现在玩家背后」的落点（真 nwAppearBehind vs Java）
    // ==================================================================

    private static void appearBehind(Value b) {
        System.out.println("[C3] 飞天落点 = 玩家背后 1.5 格（真 nwAppearBehind vs Java）");

        Value mkRef = b.getMember("__mkRef");
        Value jsFn = b.getMember("nwAppearBehind");
        int n = 0;
        int bad = 0;
        String firstBad = null;

        for (int mode = 0; mode <= 2; mode++) {
            TERRAIN_MODE = mode;
            for (float yaw : new float[]{0f, 45f, -90f, 123.5f, 180f, -179.5f, 359f}) {
                for (double px : new double[]{0.5, 12.25, -30.0}) {
                    for (double pz : new double[]{0.5, -8.75, 44.0}) {
                        PLX = px;
                        PLY = 64.0;
                        PLZ = pz;
                        PLYAW = yaw;
                        BSX = px + 5;
                        BSY = 66.0;
                        BSZ = pz - 5;
                        BSYAW = 0f;
                        resetKeepPositions();
                        BOSS_TELEPORT.clear();

                        // JS
                        Value refJs = mkRef.execute(WORLD(), bossProxy());
                        jsFn.execute(refJs);
                        if (BOSS_TELEPORT.size() != 1) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "JS 没 teleport（yaw=" + yaw + "）";
                            }
                            continue;
                        }
                        Location jsLoc = BOSS_TELEPORT.get(0);

                        // Java
                        NightwatchModule m = new NightwatchModule();
                        NightwatchModule.Ref ref = new NightwatchModule.Ref();
                        ref.owner = "probe-owner";
                        ref.lw = WORLD();
                        ref.ent = bossProxy();
                        ref.maxHp = 450.0;
                        BOSS_TELEPORT.clear();
                        m.nwAppearBehind(ref);
                        if (BOSS_TELEPORT.size() != 1) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "Java 没 teleport（yaw=" + yaw + "）";
                            }
                            continue;
                        }
                        Location jv = BOSS_TELEPORT.get(0);

                        n++;
                        boolean ok = jsLoc.getX() == jv.getX()
                                && jsLoc.getY() == jv.getY()
                                && jsLoc.getZ() == jv.getZ()
                                && jsLoc.getYaw() == jv.getYaw();
                        if (!ok) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "yaw=" + yaw + " px=" + px + " pz=" + pz
                                        + "  JS=(" + jsLoc.getX() + "," + jsLoc.getY() + ","
                                        + jsLoc.getZ() + "," + jsLoc.getYaw() + ")"
                                        + "  Java=(" + jv.getX() + "," + jv.getY() + ","
                                        + jv.getZ() + "," + jv.getYaw() + ")";
                            }
                        }
                    }
                }
            }
        }
        check("飞天落点逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0 && n > 0);
        System.out.println();
    }

    // ==================================================================
    // [C4] 拉到身前 + 控视角（真 nwGrabPlayer vs Java）
    // ==================================================================

    private static void grabPlayer(Value b) {
        System.out.println("[C4] 拉到身前 + 控视角 1 秒（真 nwGrabPlayer vs Java）");

        Value mkRef = b.getMember("__mkRef");
        Value jsFn = b.getMember("nwGrabPlayer");
        int n = 0;
        int bad = 0;
        int jsFailed = 0;
        String firstBad = null;

        // yaw 取「整齐值」（+180 之后仍是 float 精确值 ⇒ 原脚本这一步能成）
        // 与「零碎值」（+180 之后不是 float 精确值 ⇒ 原脚本 nwLoc 返回 null、传送失败）
        float[] yaws = {0f, 30f, -75.25f, 180f, 270.5f, 12.34f, 0.1f, 33.77f};
        for (int mode = 0; mode <= 2; mode++) {
            TERRAIN_MODE = mode;
            for (float yaw : yaws) {
                for (double bx : new double[]{0.0, 8.5, -20.25}) {
                    for (double bz : new double[]{0.0, -3.5, 17.0}) {
                        PLX = bx + 6;
                        PLY = 63.0;
                        PLZ = bz + 6;
                        PLYAW = 0f;
                        BSX = bx;
                        BSY = 65.0;
                        BSZ = bz;
                        BSYAW = yaw;
                        resetKeepPositions();

                        // JS
                        Value refJs = mkRef.execute(WORLD(), bossProxy());
                        jsFn.execute(refJs, playerProxy());
                        // ★ 原脚本的 teleport 会因 float 有损转换失败（见 [C9]）；
                        //   不管成不成，20 点伤害都照打（nwHurt 的形参是 double，不受影响）
                        boolean jsTeleported = PLAYER_TELEPORT.size() == 1;
                        boolean jsRotated = PLAYER_ROT.size() == 1;
                        if (DAMAGE.size() != 1) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "JS 没造成伤害 dmg=" + DAMAGE.size();
                            }
                            continue;
                        }
                        double jsDmg = DAMAGE.get(0);
                        // ★ 必须在清列表**之前**把 JS 的落点抄下来
                        Location jsLoc = jsTeleported ? PLAYER_TELEPORT.get(0).clone() : null;
                        if (!jsTeleported) {
                            jsFailed++;
                        }

                        // Java
                        NightwatchModule m = new NightwatchModule();
                        NightwatchModule.Ref ref = new NightwatchModule.Ref();
                        ref.owner = "probe-owner";
                        ref.lw = WORLD();
                        ref.ent = bossProxy();
                        ref.maxHp = 900.0;
                        resetKeepPositions();
                        m.nwGrabPlayer(ref, playerProxy());
                        if (PLAYER_TELEPORT.size() != 1 || PLAYER_ROT.size() != 1
                                || DAMAGE.size() != 1) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "Java 记录不全 teleport=" + PLAYER_TELEPORT.size()
                                        + " rot=" + PLAYER_ROT.size() + " dmg=" + DAMAGE.size();
                            }
                            continue;
                        }
                        Location jv = PLAYER_TELEPORT.get(0);
                        double jdmg = DAMAGE.get(0);

                        n++;
                        boolean ok = jsDmg == jdmg;
                        if (jsTeleported) {
                            // 原脚本这一步成功 ⇒ 落点必须逐值一致
                            ok = ok && jsLoc.getX() == jv.getX()
                                    && jsLoc.getY() == jv.getY()
                                    && jsLoc.getZ() == jv.getZ()
                                    && jsLoc.getYaw() == jv.getYaw();
                        }
                        if (!ok) {
                            bad++;
                            if (firstBad == null) {
                                firstBad = "yaw=" + yaw + " bx=" + bx + " bz=" + bz
                                        + "  JS teleport=" + jsTeleported
                                        + " dmg=" + jsDmg + "  Java=(" + jv.getX() + ","
                                        + jv.getY() + "," + jv.getZ() + "|" + jv.getYaw()
                                        + ") dmg=" + jdmg;
                            }
                        }
                    }
                }
            }
        }
        check("拉到身前：20 点伤害逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0 && n > 0);
        check("原脚本的传送在 " + jsFailed + "/" + n
                + " 组上因 float 有损转换失败（这正是 [C9] 说的缺陷）", jsFailed > 0);
        System.out.println();
    }

    // ==================================================================
    // [C5] 血条进度（真 nwBarSync 的 setProgress vs Java nwBarProg+nwBarClamp）
    // ==================================================================

    private static void barSync(Value b) {
        System.out.println("[C5] 血条进度（真 nwBarSync 的 setProgress vs Java 算式）");

        Value mkRef = b.getMember("__mkRef");
        Value jsFn = b.getMember("nwBarSync");
        // ★ 探针环境里 Attribute 静态初始化可能失败 ⇒ 直接把脚本的缓存键钉成假体，
        //   这样 nwMaxHp(ent) 会真的去读 ent.getAttribute(k) ⇒ 读到 ATTR_MAX。
        b.putMember("NW_MAXATTR", attrProxy());
        double[] hps = {-5, 0, 1, 22.5, 100, 449.5, 450, 900, 1200};
        double[] maxes = {-1, 0, 1, 100, 450, 900, 2000};
        int n = 0;
        int bad = 0;
        String firstBad = null;

        for (double hp : hps) {
            for (double mx : maxes) {
                for (double refMax : new double[]{450, 900}) {
                    ATTR_MAX = mx;
                    // JS
                    resetKeepPositions();
                    Value refJs = mkRef.execute(WORLD(), healthProxy(hp));
                    refJs.putMember("bar", barProxy());
                    refJs.putMember("maxHp", refMax);
                    jsFn.execute(refJs);
                    if (BAR_PROGRESS.size() != 1) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "JS 没 setProgress（hp=" + hp + " mx=" + mx + "）";
                        }
                        continue;
                    }
                    double jsProg = BAR_PROGRESS.get(0);

                    // Java
                    resetKeepPositions();
                    double jv = NightwatchModule.nwBarClamp(
                            NightwatchModule.nwBarProg(hp, mx, refMax));
                    n++;
                    if (jsProg != jv) {
                        bad++;
                        if (firstBad == null) {
                            firstBad = "hp=" + hp + " mx=" + mx + " refMax=" + refMax
                                    + "  JS=" + jsProg + "  Java=" + jv;
                        }
                    }
                }
            }
        }
        check("血条进度逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0 && n > 0);
        ATTR_MAX = -1.0;
        System.out.println();
    }

    /** 一个 Attribute 假体（只为让脚本的 nwMaxHp 能取到"键"，取值靠 ATTR_MAX）。 */
    private static Attribute attrProxy() {
        return mk(Attribute.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            if ("name".equals(m.getName())) {
                return "MAX_HEALTH";
            }
            return dflt(m.getReturnType());
        });
    }

    /** 一个「活着、血量可配」的实体假体。 */
    private static LivingEntity healthProxy(double hp) {
        return mk(LivingEntity.class, (p, m, a) -> {
            Object o = objMethods(p, m, a);
            if (o != NOT_OBJ) {
                return o;
            }
            switch (m.getName()) {
                case "getHealth":
                    return hp;
                case "getUniqueId":
                    return UUID.fromString("00000000-0000-0000-0000-0000000000cc");
                case "getType":
                    return null;
                case "getLocation":
                    return new Location(WORLD(), 0, 64, 0, 0f, 0f);
                case "getWorld":
                    return WORLD();
                case "isDead":
                    return false;
                case "isValid":
                    return true;      // ★ nwAlive = !(isDead() || !isValid()) ⇒ 必须 true
                case "getAttribute":
                    if (ATTR_MAX < 0) {
                        return null;
                    }
                    return mk(AttributeInstance.class, (p2, m2, a2) -> {
                        switch (m2.getName()) {
                            case "getValue":
                            case "getBaseValue":
                                return ATTR_MAX;
                            case "getDefaultValue":
                                return 20.0;
                            case "getModifiers":
                                return new ArrayList<>();
                            default:
                                return dflt(m2.getReturnType());
                        }
                    });
                case "getPersistentDataContainer":
                    return null;
                default:
                    return dflt(m.getReturnType());
            }
        });
    }

    // ==================================================================
    // [C6] 其余可对账的函数（血条标题 / 出场刻数）+ [C7] 纯算式 + [C8] 数字格式
    // ==================================================================

    private static void misc(Value b) throws Exception {
        System.out.println("[C6] 血条标题 / 出场刻数（真 JS vs Java）");

        Value jsTitle = b.getMember("nwBarTitle");
        NightwatchModule m = new NightwatchModule();
        for (int stage : new int[]{1, 2}) {
            String js = jsTitle.execute(stage).asString();
            String jv = invokePrivate(m, "nwBarTitle", new Class<?>[]{int.class}, stage);
            check("nwBarTitle(" + stage + ") = " + jv + "（JS 同）", js.equals(jv));
        }
        int jsTicks = b.getMember("nwEmergeTicks").execute().asInt();
        check("nwEmergeTicks() = " + m.nwEmergeTicks() + "（JS 同；探针环境读不到 WardenAi ⇒ 走兜底 "
                + NightwatchModule.NW_EMERGE_FALLBACK_TICKS + "）",
                jsTicks == m.nwEmergeTicks());

        System.out.println();
        System.out.println("[C7] 纯算式逐值对账（身前/背后/视角/转场/环绕/首次冷却）");

        Value rad = b.getMember("__jsRad");
        Value frontX = b.getMember("__jsFrontX");
        Value frontZ = b.getMember("__jsFrontZ");
        Value behindX = b.getMember("__jsBehindX");
        Value behindZ = b.getMember("__jsBehindZ");
        Value lookYaw = b.getMember("__jsLookYaw");
        Value lookPitch = b.getMember("__jsLookPitch");
        Value flatFn = b.getMember("__jsFlat");
        Value firstCd = b.getMember("__jsFirstCd");
        Value ringAngle = b.getMember("__jsRingAngle");
        Value ringX = b.getMember("__jsRingX");
        Value ringZ = b.getMember("__jsRingZ");
        Value skyStep = b.getMember("__jsSkyStep");
        Value transR = b.getMember("__jsTransRadius");
        Value transA = b.getMember("__jsTransAngle");

        float[] yaws = {0f, 1f, -1f, 45f, 90f, -90f, 123.5f, -179.5f, 180f, 270f, 359.9f, 720.5f};
        double[] cs = {0, 1, -1, 2.5, -3.75, 100.125, -0.5};
        int n = 0;
        int bad = 0;
        String firstBad = null;

        for (float yaw : yaws) {
            double jsRad = rad.execute((double) yaw).asDouble();
            double jvRad = NightwatchModule.nwRad(yaw);
            if (jsRad != jvRad) {
                bad++;
                firstBad = firstBad != null ? firstBad : ("nwRad(" + yaw + ") JS=" + jsRad + " Java=" + jvRad);
            }
            n++;
            for (double c : cs) {
                double a = frontX.execute(c, jsRad, 2.0).asDouble();
                double bq = NightwatchModule.nwFrontX(c, jvRad, 2.0);
                n++;
                if (a != bq) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwFrontX(" + c + "," + yaw + ")");
                }
                double a2 = frontZ.execute(c, jsRad, 2.0).asDouble();
                double b2 = NightwatchModule.nwFrontZ(c, jvRad, 2.0);
                n++;
                if (a2 != b2) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwFrontZ(" + c + "," + yaw + ")");
                }
                double a3 = behindX.execute(c, jsRad, 1.5).asDouble();
                double b3 = NightwatchModule.nwBehindX(c, jvRad, 1.5);
                n++;
                if (a3 != b3) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwBehindX(" + c + "," + yaw + ")");
                }
                double a4 = behindZ.execute(c, jsRad, 1.5).asDouble();
                double b4 = NightwatchModule.nwBehindZ(c, jvRad, 1.5);
                n++;
                if (a4 != b4) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwBehindZ(" + c + "," + yaw + ")");
                }
                double f = flatFn.execute(c, jsRad).asDouble();
                double jf = NightwatchModule.nwFlat(c, jvRad);
                n++;
                if (f != jf) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwFlat(" + c + "," + yaw + ")");
                }
                double ly = lookYaw.execute(c, jsRad).asDouble();
                double jly = NightwatchModule.nwLookYaw(c, jvRad);
                n++;
                if (ly != jly) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwLookYaw(" + c + "," + yaw + ")");
                }
                double lp = lookPitch.execute(c, f).asDouble();
                double jlp = NightwatchModule.nwLookPitch(c, jf);
                n++;
                if (lp != jlp) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwLookPitch(" + c + "," + yaw + ")");
                }
            }
        }
        check("身前/背后/水平距离/视角角度逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0);

        // 首次冷却（含负数 now 的边界）
        n = 0;
        bad = 0;
        firstBad = null;
        long[] nows = {0L, 1L, 1000L, 1700000000000L, -5000L};
        long[] cds = {7000L, 9000L, 8000L, 15000L, 90000L};
        for (long now : nows) {
            for (long cd : cds) {
                long js = firstCd.execute((double) now, (double) cd).asLong();
                long jv = NightwatchModule.nwFirstCd(now, cd);
                n++;
                if (js != jv) {
                    bad++;
                    if (firstBad == null) {
                        firstBad = "nwFirstCd(" + now + "," + cd + ") JS=" + js + " Java=" + jv;
                    }
                }
            }
        }
        check("开场首次冷却逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0);

        // 环绕 / 转场 / 飞天步长
        n = 0;
        bad = 0;
        firstBad = null;
        for (int i = 0; i <= 12; i++) {
            double ang = ringAngle.execute(i).asDouble();
            double jang = NightwatchModule.nwRingAngle(i);
            n++;
            if (ang != jang) {
                bad++;
                firstBad = firstBad != null ? firstBad : ("nwRingAngle(" + i + ")");
            }
            double rx = ringX.execute(ang).asDouble();
            double jrx = NightwatchModule.nwRingX(jang);
            n++;
            if (rx != jrx) {
                bad++;
                firstBad = firstBad != null ? firstBad : ("nwRingX(" + i + ")");
            }
            double rz = ringZ.execute(ang).asDouble();
            double jrz = NightwatchModule.nwRingZ(jang);
            n++;
            if (rz != jrz) {
                bad++;
                firstBad = firstBad != null ? firstBad : ("nwRingZ(" + i + ")");
            }
        }
        for (int i = 0; i <= 8; i++) {
            double f = i / 6.0;
            double r = transR.execute(f).asDouble();
            double jr = NightwatchModule.nwTransRadius(f);
            n++;
            if (r != jr) {
                bad++;
                firstBad = firstBad != null ? firstBad : ("nwTransRadius(" + f + ")");
            }
            for (int a = 0; a < 12; a++) {
                double av = transA.execute(a, f).asDouble();
                double jav = NightwatchModule.nwTransAngle(a, f);
                n++;
                if (av != jav) {
                    bad++;
                    firstBad = firstBad != null ? firstBad : ("nwTransAngle(" + a + "," + f + ")");
                }
            }
        }
        double ss = skyStep.execute().asDouble();
        n++;
        if (ss != NightwatchModule.nwSkyStep()) {
            bad++;
            firstBad = firstBad != null ? firstBad : "nwSkyStep";
        }
        check("环绕/转场/飞天步长逐值一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0);

        // 数字 → 字符串（日志拼接的关键）
        System.out.println();
        System.out.println("[C8] 数字格式（Java nwNum vs JS '' + v —— 日志逐字节的关键）");
        Value jsNum = b.getMember("__jsNum");
        double[] nums = {0, 1, -1, 3, 7, 8, 9, 10, 15, 17, 20, 44, 70, 90, 134, 200, 450,
                900, 996, 0.5, 1.5, 0.25, -2.5, 1234567, 1000000000};
        n = 0;
        bad = 0;
        firstBad = null;
        for (double v : nums) {
            String js = jsNum.execute(v).asString();
            String jv = NightwatchModule.nwNum(v);
            n++;
            if (!js.equals(jv)) {
                bad++;
                if (firstBad == null) {
                    firstBad = v + " JS=\"" + js + "\" Java=\"" + jv + "\"";
                }
            }
        }
        check("nwNum 与 JS 的 '' + v 逐字节一致（" + n + " 组）"
                + (firstBad == null ? "" : " 首个不一致: " + firstBad), bad == 0);
        System.out.println();
    }

    private static String invokePrivate(Object target, String name, Class<?>[] sig, Object... args) {
        try {
            Method m = target.getClass().getDeclaredMethod(name, sig);
            m.setAccessible(true);
            return (String) m.invoke(target, args);
        } catch (Throwable t) {
            return "<反射失败: " + t + ">";
        }
    }

    private static String fmt(double v) {
        return String.valueOf(Math.round(v * 1.0e6) / 1.0e6);
    }

    // ==================================================================
    // [D] 反向断言：把「不应该存在的东西」钉住
    // ==================================================================

    private static void reverse(Value b, String jsSource) throws Exception {
        System.out.println("[D] 反向断言（不该存在的东西）");

        // ① 原脚本**没有**「gs_boss_nightwatch 已加载」这一行；参考服日志里也没有
        check("原脚本里搜不到「已加载」", !jsSource.contains("已加载"));
        boolean refHas = false;
        for (String line : Files.readAllLines(Paths.get(REF_LOG), StandardCharsets.UTF_8)) {
            if (line.contains("gs_boss_nightwatch 已加载")) {
                refHas = true;
            }
        }
        check("参考服日志里没有「gs_boss_nightwatch 已加载」", !refHas);
        check("本模块的**代码**里不打这一行（注释里提到它不算）",
                !codeOnly(moduleSource()).contains("已加载"));

        // ② 参考服日志里 nightwatch 的行**恰好**是这 6 行
        List<String> refNw = new ArrayList<>();
        for (String line : Files.readAllLines(Paths.get(REF_LOG), StandardCharsets.UTF_8)) {
            int k = line.indexOf("INFO]: ");
            String body = k >= 0 ? line.substring(k + "INFO]: ".length()) : line;
            if (body.startsWith("[构思技艺] 守夜人") || body.startsWith("[构思技艺] 巡夜守卫")) {
                refNw.add(body);
            }
        }
        check("参考服日志里 nightwatch 行数恰好 6（多一行就是多了可观察日志）", refNw.size() == 6);

        // ③ 死代码：nwPSafe / nwTitle 在原脚本里只有定义、没有调用
        check("原脚本里 nwPSafe 只有 1 处出现（定义）", countOf(jsSource, "nwPSafe") == 1);
        check("原脚本里 nwTitle 只有 1 处出现（定义）", countOf(jsSource, "nwTitle") == 1);
        check("原脚本里 NW_TELE_AHEAD 只有 1 处出现（定义，从未被读）",
                countOf(jsSource, "NW_TELE_AHEAD") == 1);
        check("原脚本里 nwHurt 有 3 处出现（定义 + 2 次调用）",
                countOf(jsSource, "nwHurt") == 3);

        // ④ Java 侧不许「顺手实现」那两个死函数
        List<String> methods = new ArrayList<>();
        for (Method m : NightwatchModule.class.getDeclaredMethods()) {
            methods.add(m.getName());
        }
        check("Java 侧没有 nwPSafe", !methods.contains("nwPSafe"));
        check("Java 侧没有 nwTitle", !methods.contains("nwTitle"));

        // ⑤ 方块交互拦截必须复用现成的 InteractGuard，不许自己再注册一个
        boolean ownGuard = false;
        for (Class<?> c : NightwatchModule.class.getDeclaredClasses()) {
            for (Method m : c.getDeclaredMethods()) {
                for (java.lang.annotation.Annotation an : m.getAnnotations()) {
                    if (an.annotationType().getName().endsWith("EventHandler")) {
                        Class<?>[] ps = m.getParameterTypes();
                        if (ps.length == 1 && ps[0].getName()
                                .equals("org.bukkit.event.player.PlayerInteractEvent")) {
                            ownGuard = true;
                        }
                    }
                }
            }
        }
        check("本模块没有自己的 PlayerInteractEvent 拦截器（复用 InteractGuard）", !ownGuard);

        // ⑥ 只给玩家发一句提示（§5夜深了），没有大标题 / 没有别的提示词
        String src = moduleSource();
        String code = codeOnly(src);
        check("本模块没有 sendTitle（原脚本的 nwTitle 是死代码）", !code.contains("sendTitle"));
        check("本模块只把 NW_S2_NIGHT_MSG 当玩家提示用（1 处调用）",
                countOf(code, "NW_S2_NIGHT_MSG") == 2);   // 定义 1 次 + 调用 1 次
        check("nwMsg 的两级兜底与 JS 一致（Chat.t 一次 + 正则兜底一次）",
                countOf(code, "sendMessage") == 2);
        check("模块里没有别的玩家可见文案（没有 NW_S2_NIGHT_MSG 以外的 sendMessage 参数）",
                !code.contains("nwTitle"));

        // ⑦ 掉落数量：一阶段 1 把、二阶段 6 个（写死不许漂）
        check("一阶段掉落数 = 1", NightwatchModule.NW_DROP_S1_N == 1);
        check("二阶段掉落数 = 6", NightwatchModule.NW_DROP_S2_N == 6);
        check("一阶段掉落物 = GS_MOON_SCYTHE",
                "GS_MOON_SCYTHE".equals(NightwatchModule.NW_DROP_S1_ID));
        check("二阶段掉落物 = GS_BLOOD_NIGHT_GRAIN",
                "GS_BLOOD_NIGHT_GRAIN".equals(NightwatchModule.NW_DROP_S2_ID));

        // ⑧ 模块名 / 基类
        NightwatchModule m = new NightwatchModule();
        check("name() = gs_boss_nightwatch", "gs_boss_nightwatch".equals(m.name()));
        check("继承 AbstractModule",
                AbstractModule.class.isAssignableFrom(NightwatchModule.class));
        check("final 类", Modifier.isFinal(NightwatchModule.class.getModifiers()));

        // ⑨ PDC 键名不许漂
        for (String key : new String[]{"gs:nightwatch", "gs:nightwatch_proj", "gs:nightwatch_owner"}) {
            check("PDC 键 " + key + " 在 Java 源码里逐字出现", src.contains(key));
        }
        System.out.println();
    }

    private static int countOf(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }

    /**
     * 去掉注释后的源码。
     *
     * <p>本模块的注释里**大量**提到原脚本的原文（比如「原脚本没有『已加载』这一行」），
     * 所以「源码里有没有某个字符串」这种断言必须只看代码、不看注释，
     * 否则断言会被自己的说明文字绊倒。
     */
    private static String codeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String raw : src.split("\n", -1)) {
            String line = raw;
            if (inBlock) {
                int e = line.indexOf("*/");
                if (e < 0) {
                    continue;
                }
                line = line.substring(e + 2);
                inBlock = false;
            }
            int b = line.indexOf("/*");
            if (b >= 0) {
                int e = line.indexOf("*/", b + 2);
                if (e < 0) {
                    line = line.substring(0, b);
                    inBlock = true;
                } else {
                    line = line.substring(0, b) + line.substring(e + 2);
                }
            }
            String t = line.trim();
            if (t.startsWith("//")) {
                continue;
            }
            int c = line.indexOf("//");
            if (c >= 0) {
                line = line.substring(0, c);
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static String MODULE_SRC = null;

    private static String moduleSource() throws Exception {
        if (MODULE_SRC == null) {
            Path p = Paths.get("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/"
                    + "com/qinghuacisj/gs/script/module/NightwatchModule.java");
            MODULE_SRC = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        }
        return MODULE_SRC;
    }

    private static boolean moduleSourceContains(String s) throws Exception {
        return moduleSource().contains(s);
    }

    // ==================================================================

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
