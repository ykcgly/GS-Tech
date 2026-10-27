import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import com.qinghuacisj.gs.script.module.listener.EcSub;
import com.qinghuacisj.gs.script.module.listener.ListenerCore;
import com.qinghuacisj.gs.script.module.listener.MsSub;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 「月镰 + 蚀月·死神梦镰」子系统的**对账探针**（D 组）。
 *
 * <p>契约：{@code docs\gs_listener_拆分契约.md} §6。四类断言：
 * <ol>
 *   <li><b>[A] 常量对账</b> —— 原脚本顶层 {@code var GS_MS_* / GS_EC_*} 与 Java 字段
 *       <b>按名按值</b>双向核对（正方向：脚本里有的 Java 必须有；
 *       反方向：Java 里有的脚本必须有 —— 专抓「凭空多出来的常量」）。</li>
 *   <li><b>[B] 日志行对账</b> —— 6 行加载日志与「真 JS 跑出来的那一行」逐字节比对，
 *       再与 {@code docs\参考服日志_构思技艺.txt} 的 28~33 行逐字节比对。</li>
 *   <li><b>[C] 数值/几何算式对账</b> —— 在大量输入上与<b>真 JS 函数</b>逐值比对
 *       （挥砍矩形、蚀月轮飞行/停下、脉冲节拍、圆环点、噩梦射线、法阵、天降圆柱）。</li>
 *   <li><b>[D] 反向断言</b> —— 钉住「不该存在的东西」与两处<b>已修缺陷</b>的行为。</li>
 * </ol>
 *
 * <h2>★ 本探针跑起来的三个前提（都是实测结论，不是猜测）</h2>
 * <ol>
 *   <li>原脚本<b>前 7789 行</b>（含 {@code gsMsStart();} 与 {@code gsEcStart();}）
 *       能在桥接桩下完整求值 ⇒ 可以拿「真 JS 跑出来的日志行 / 真 JS 函数的返回值」
 *       当金标准。</li>
 *   <li>无服务器环境下 {@code org.bukkit.Material} 会 {@code ExceptionInInitializerError}
 *       （{@code Registry} 起不来）⇒ 探针里<b>不能碰 Material</b>。
 *       {@code Particle} / {@code EntityType} / {@code BarColor} / {@code NamespacedKey}
 *       实测可用。</li>
 *   <li>{@code GS.runLater} 需要真调度器 ⇒ 蚀月轮 / 天降光柱 / 法阵主循环
 *       这些**带 {@code runLater} 的链条在 Java 侧跑不起来**。
 *       对策：JS 侧把 {@code runLater} 做成**同步 + 预算**，跑完整个链条；
 *       Java 侧则把链条里的**节拍算式**抽成静态方法（{@code gsEcHoldPulseTicks} /
 *       {@code gsEcWheelStopTick} / {@code gsEcHoldTicks} / {@code gsEcSkyHeights}）
 *       与「真 JS 跑完整条链」的结果对账 —— 这是**非循环**的交叉验证。</li>
 * </ol>
 *
 * <h2>★ 精度处理</h2>
 * JS 与 JVM 的 {@code Math.cos/sin/sqrt} 可能差 1 ULP ⇒ 粒子坐标用相对容差
 * {@code 1e-9} 比较，不做字符串逐字节比对。伤害 / 层数 / 命中数这些<b>离散量</b>
 * 仍然严格相等。
 */
public final class ListenerDSubParityProbe {

    // ======================================================================
    // 行区间（契约 §2 的表）
    // ======================================================================

    static final int MS_FROM = 5946;
    static final int MS_TO = 6846;
    static final int EC_FROM = 6848;
    static final int EC_TO = 7789;

    static int pass = 0;
    static int fail = 0;

    /** 共用的 {@link ListenerCore}（定身表 {@code gsMsLocks} 是它的**实例**字段）。 */
    static final ListenerCore CORE = new ListenerCore();

    // ======================================================================
    // 桥接桩（在求值原脚本**之前**注入）
    // ======================================================================

    private static final String JS_STUBS = """
            var __log = [];          // getServer().getLogger() 收到的每一行
            var __pts = [];          // shim Location 构造记录
            var __online = [];

            function __mkPdc(init) {
              var m = {};
              if (init) { for (var k in init) { if (init[k] !== null && init[k] !== undefined) { m[k] = init[k]; } } }
              return {
                __m: m,
                get: function (key, type) { var v = m['' + key]; return (v === undefined) ? null : v; },
                set: function (key, type, v) { m['' + key] = v; },
                remove: function (key) { delete m['' + key]; },
                has: function (key, type) { return m['' + key] !== undefined; }
              };
            }

            // 世界世代号挂在 world[0] 的 PDC 上（原脚本 6624 行）
            var __genWorldPdc = __mkPdc({});
            var __genWorld = {
              getName: function () { return 'world'; },
              getPersistentDataContainer: function () { return __genWorldPdc; },
              playSound: function () { },
              spawnParticle: function () { },
              getNearbyEntities: function () {
                return { iterator: function () { return { hasNext: function () { return false; },
                                                          next: function () { return null; } }; } };
              },
              getBlockAt: function () { return { getType: function () { return { isSolid: function () { return false; } }; } }; },
              dropItemNaturally: function () { return null; }
            };

            var __srv = {
              getLogger: function () {
                return {
                  info: function (m) { __log.push('INFO ' + String(m)); },
                  warning: function (m) { __log.push('WARN ' + String(m)); },
                  severe: function (m) { __log.push('SEV ' + String(m)); }
                };
              },
              getPluginManager: function () {
                return {
                  registerEvent: function () { return null; },
                  getPlugin: function (n) { return { __fake: String(n) }; }
                };
              },
              getOnlinePlayers: function () { return __online; },
              // ★ 必须是 List 语义（脚本写的是 getWorlds().get(0)），不能返回 JS 数组
              getWorlds: function () {
                return { get: function (i) { return __genWorld; },
                         size: function () { return 1; },
                         isEmpty: function () { return false; },
                         iterator: function () { var d = false;
                           return { hasNext: function () { return !d; },
                                    next: function () { d = true; return __genWorld; } }; } };
              },
              createBossBar: function (t, c, s) { return __mkBar(t, c, s); }
            };
            function getServer() { return __srv; }
            function getAddonConfig() { return null; }

            // ★ 关键：runLater 在桩里是**空实现**（原脚本顶层会跑 runRepeating/注册监听器，
            //   不换掉会炸或卡死）。真正的「同步 + 预算」版在 HELPERS 里换上去。
            function runLater(fn, d) { return null; }
            function runRepeating(fn, d, p) { return null; }

            function __mkBar(title, color, style) {
              var o = {
                __title: String(title), __color: color, __style: style, __prog: 1.0, __vis: false,
                setTitle: function (t) { o.__title = String(t); },
                getTitle: function () { return o.__title; },
                setColor: function (c) { o.__color = c; },
                setProgress: function (v) { o.__prog = v; },
                setVisible: function (v) { o.__vis = v; },
                addPlayer: function () { }, removePlayer: function () { },
                removeAll: function () { }, isVisible: function () { return o.__vis; },
                getProgress: function () { return o.__prog; }
              };
              return o;
            }

            // ★ 纯 JS 的 org.bukkit.util.Vector 替身。
            //   不能用真 Vector：paper-api 不带 org.joml，GraalJS 暴露宿主对象时会扫
            //   Vector 的全部公开方法（含 toVector3f():org.joml.Vector3f）
            //   ⇒ NoClassDefFoundError（已实测）。
            function __JSVec(x, y, z) {
              return {
                __v: true,
                getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                setX: function (v) { x = v; return this; }, setY: function (v) { y = v; return this; },
                setZ: function (v) { z = v; return this; },
                clone: function () { return __JSVec(x, y, z); },
                lengthSquared: function () { return x * x + y * y + z * z; },
                length: function () { return Math.sqrt(x * x + y * y + z * z); },
                normalize: function () {
                  var l = Math.sqrt(x * x + y * y + z * z);
                  if (l === 0) return this;
                  x /= l; y /= l; z /= l;
                  return this;
                },
                crossProduct: function (o) {
                  var ox = o.getX(), oy = o.getY(), oz = o.getZ();
                  var nx = y * oz - z * oy, ny = z * ox - x * oz, nz = x * oy - y * ox;
                  x = nx; y = ny; z = nz;
                  return this;
                },
                multiply: function (k) { x *= k; y *= k; z *= k; return this; },
                add: function (o) { x += o.getX(); y += o.getY(); z += o.getZ(); return this; },
                subtract: function (o) { x -= o.getX(); y -= o.getY(); z -= o.getZ(); return this; },
                distance: function (o) {
                  return Math.sqrt(Math.pow(x - o.getX(), 2) + Math.pow(y - o.getY(), 2) + Math.pow(z - o.getZ(), 2));
                },
                toString: function () { return 'Vector[' + x + ',' + y + ',' + z + ']'; }
              };
            }
            """;

    /**
     * {@code Java.type} 垫片。
     *
     * <p>★ 无服务器环境下 {@code Attribute} 会 {@code ExceptionInInitializerError}
     * （不是能被 JS {@code try/catch} 抓住的普通异常）⇒ 必须换掉。
     * {@code Particle} / {@code PotionEffectType} / {@code Location} 同理
     * （注册表枚举 / 构造要真 World）。
     */
    private static final String JS_SHIM = """
            var __realJava = Java;
            var Java = { type: function (n) {
              if (n === 'org.bukkit.attribute.Attribute') {
                return { SCALE: null, valueOf: function () { return null; } };
              }
              if (n === 'org.bukkit.Particle') {
                return { FIREWORK: 'P:FIREWORK', FIREWORK_SPARK: 'P:FIREWORK_SPARK',
                         CRIT: 'P:CRIT', END_ROD: 'P:END_ROD',
                         DUST: 'P:DUST', REDSTONE: 'P:REDSTONE',
                         valueOf: function (x) { return 'P:' + x; } };
              }
              if (n === 'org.bukkit.potion.PotionEffectType') {
                return { SLOWNESS: 'E:SLOWNESS', SLOW: 'E:SLOW', NIGHT_VISION: 'E:NIGHT_VISION',
                         valueOf: function (x) { return 'E:' + x; } };
              }
              if (n === 'org.bukkit.potion.PotionEffect') {
                return function (t, d, a, amb, part) { return { __pe: true, type: t, duration: d, amplifier: a }; };
              }
              if (n === 'org.bukkit.Location') {
                return function (w, x, y, z) {
                  var o = { __loc: true, __id: __pts.length, x: x, y: y, z: z,
                    getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                    getWorld: function () { return w; },
                    getBlock: function () { return { getType: function () { return { isSolid: function () { return __blkSolid; } }; } }; }
                  };
                  __pts.push(o);
                  return o;
                };
              }
              if (n === 'org.bukkit.util.Vector') { return __JSVec; }
              if (n.indexOf('io.github.thebusybiscuit.slimefun4.implementation.tasks.armor') === 0) { return null; }
              return __realJava.type(n);
            } };
            """;

    // ======================================================================
    // 探针自己的 JS 工具（在求值原脚本**之后**注入 —— 要引用真函数）
    // ======================================================================

    private static final String JS_HELPERS = """
            // ---------- 捕获区 ----------
            var __SELF = '00000000-0000-0000-0000-0000000000aa';
            var __now = 1000000;
            var __budget = 0;
            var __tickNo = 0;
            var __snd = [], __ptc = [], __dmg = [], __locks = [], __cands = [];
            var __lastLockMs = 0;
            var __blkSolid = false, __world = null, __pdc = null, __hand = null, __worldPdc = null;
            var __pulses = [];

            // ★ 探针里的 ent 是**纯 JS 对象** ⇒ 真的 LivingEntity.class.isInstance(ent) 恒 false，
            //   而 gsIsCombatTarget（原脚本 8032 行）最后一步正是它 ⇒ 会把所有 mock 都判成非活物。
            //   这里把 GS_LIVING_CLS 换成一个「认 mock 自己声明的 __living 标记」的替身，
            //   **真函数 gsIsCombatTarget 一行不改**（黑名单那一步仍然是脚本原文）。
            GS_LIVING_CLS = { class: { isInstance: function (e) { return !!(e && e.__living === true); } } };

            // ★ 同步 + 预算版 runLater：让「带 runLater 的链条」一次跑完
            runLater = function (fn, d) {
              __tickNo++;
              if (__budget > 0) { __budget--; fn(); }
              return null;
            };

            function __mkEnt(c) {
              var o = {
                __living: (c.type !== 'ITEM'),
                getUniqueId: function () { return c.id; },
                getType: function () { return { name: function () { return c.type; } }; },
                getLocation: function () {
                  return { getX: function () { return c.x; }, getY: function () { return c.y; },
                           getZ: function () { return c.z; } };
                },
                isDead: function () { return c.dead; },
                getHealth: function () { return 20; },
                getWorld: function () { return __world; }
              };
              // ★ 真实 org.bukkit.entity.Item 没有这些方法（只有 getHealth）
              //   ⇒ ITEM 型 mock 也不给，才能忠实复现「掉落物骗过 getHealth 探针」那条路
              if (c.type !== 'ITEM') {
                o.setNoDamageTicks = function () { };
                o.getVelocity = function () { return __JSVec(0, 0, 0); };
                o.setVelocity = function () { };
                o.damage = function (d, p) { __dmg.push([c.id, d]); };
                o.hasAI = function () { return true; };
                o.setAI = function () { };
              }
              return o;
            }

            function __mkWorld() {
              return {
                getName: function () { return 'world'; },
                getPersistentDataContainer: function () { return __worldPdc; },
                playSound: function (loc, name, vol, pitch) { __snd.push([name, vol, pitch]); },
                spawnParticle: function (t, x, y, z) { __ptc.push([x, y, z]); },
                getNearbyEntities: function (pt, rx, ry, rz) {
                  var out = [];
                  for (var i = 0; i < __cands.length; i++) {
                    var c = __cands[i];
                    if (Math.abs(c.x - pt.getX()) <= rx && Math.abs(c.y - pt.getY()) <= ry
                        && Math.abs(c.z - pt.getZ()) <= rz) { out.push(__mkEnt(c)); }
                  }
                  var k = 0;
                  return { iterator: function () {
                    return { hasNext: function () { return k < out.length; },
                             next: function () { return out[k++]; } };
                  } };
                },
                getBlockAt: function () {
                  return { getType: function () { return { isSolid: function () { return __blkSolid; } }; } };
                },
                dropItemNaturally: function () { return null; }
              };
            }

            function __mkPlayer(x, y, z, dx, dy, dz) {
              var dir = __JSVec(dx, dy, dz);
              function L(yy) {
                return { getX: function () { return x; }, getY: function () { return yy; },
                         getZ: function () { return z; }, getDirection: function () { return dir; } };
              }
              return {
                getUniqueId: function () { return __SELF; },
                getName: function () { return 'Probe'; },
                getLocation: function () { return L(y); },
                getEyeLocation: function () { return L(y + 1.62); },
                getWorld: function () { return __world; },
                getPersistentDataContainer: function () { return __pdc; },
                getInventory: function () { return { getItemInMainHand: function () { return __hand; } }; },
                isOnline: function () { return true; },
                setInvulnerable: function () { }, setFallDistance: function () { },
                setNoDamageTicks: function () { }, getVelocity: function () { return __JSVec(0, 0, 0); },
                setVelocity: function () { }
              };
            }

            // ★ 定身侦察：真 gsMsLockFor 外面套一层
            var __realLockFor = gsMsLockFor;
            gsMsLockFor = function (ent, ms, col) {
              var id = '';
              try { id = '' + ent.getUniqueId(); } catch (e) { }
              __locks.push(id + '|' + (col === null ? 'null' : 'col'));
              __lastLockMs = ms;
              return __realLockFor(ent, ms, col);
            };

            function __reset(spec) {
              __snd = []; __ptc = []; __dmg = []; __locks = []; __cands = [];
              __pulses = []; __tickNo = 0; __budget = 0; __blkSolid = false;
              __lastLockMs = 0;
              __pdc = __mkPdc({});
              __worldPdc = __mkPdc({});
              __hand = null;
              __world = __mkWorld();
              gsMsNow = function () { return __now; };
              GS_MS_SWING_AT = {}; GS_MS_CLICK_AT = {}; GS_MS_SIDE = {};
              GS_MS_ST = {}; GS_MS_BARS = {}; GS_MS_LOCKS = [];
              GS_EC_FIRE_AT = {}; GS_EC_CLICK_AT = {}; GS_EC_LOCK_AT = {};
              GS_EC_ST = {}; GS_EC_BARS = {}; GS_EC_JUDGING = {};
              var parts = String(spec).split(';');
              var h = parts[0].split('|');
              for (var i = 1; i < parts.length; i++) {
                if (!parts[i]) { continue; }
                var f = parts[i].split('|');
                __cands.push({ id: f[0], x: (+f[1]), y: (+f[2]), z: (+f[3]),
                               type: f[4], dead: f[5] === '1' });
              }
              __blkSolid = (h[6] === '1');
              return { px: (+h[0]), py: (+h[1]), pz: (+h[2]), dx: (+h[3]), dy: (+h[4]), dz: (+h[5]) };
            }

            function __layers(which) {
              var t = (which === 'GS_MS_ST') ? GS_MS_ST : GS_EC_ST;
              var s = t[__SELF];
              return s ? (s.n | 0) : 0;
            }

            // ---------- 入口 ----------
            function __swing(spec) {
              var c = __reset(spec);
              gsMsSwing(__mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz));
              return { dmg: __dmg, snd: __snd, ptc: __ptc, locks: __locks, layers: __layers('GS_MS_ST') };
            }

            function __nightmare(spec) {
              var c = __reset(spec);
              GS_MS_ST[__SELF] = { n: 12, until: __now + 30000, full: 1 };
              var r = gsMsNightmare(__mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz));
              return { dmg: __dmg, snd: __snd, ptc: __ptc, locks: __locks,
                       layers: __layers('GS_MS_ST'), ret: r ? 1 : 0 };
            }

            function __melee(spec) {
              var c = __reset(spec);
              var n = gsEcMelee(__mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz));
              return { n: n, layers: __layers('GS_EC_ST'), dmg: __dmg, snd: __snd,
                       ptc: __ptc, locks: __locks };
            }

            function __pulse(spec) {
              var c = __reset(spec);
              var p = __mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz);
              var n = gsEcPulse(__world, p, __SELF, c.px, c.py, c.pz);
              return { n: n, layers: __layers('GS_EC_ST'), dmg: __dmg, snd: __snd,
                       ptc: __ptc, locks: __locks };
            }

            function __fieldLock(spec) {
              var c = __reset(spec);
              gsEcFieldLock(__mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz), __world, c.px, c.py, c.pz);
              return { locks: __locks, dmg: __dmg, ptc: __ptc, snd: __snd, ms: __lastLockMs };
            }

            function __ringAt(spec) {
              var c = __reset('0|0|0|0|0|1');
              var h = String(spec).split('|');
              var pt = gsMsDustType();
              var data = gsMsDustOpts(235, 30, 40, 1.0);
              gsEcRingAt(__world, pt, data, (+h[0]), (+h[1]), (+h[2]), (+h[3]), (+h[4]));
              return { ptc: __ptc, snd: __snd, dmg: __dmg };
            }

            function __drawField(spec) {
              var c = __reset('0|0|0|0|0|1');
              var h = String(spec).split('|');
              gsEcDrawField(__world, (+h[0]), (+h[1]), (+h[2]));
              return { ptc: __ptc, snd: __snd, dmg: __dmg };
            }

            function __slash(spec) {
              var c = __reset('0|0|0|0|0|1');
              var h = String(spec).split('|');
              var loc = __JSVec(0, 0, 0);
              var locObj = { getX: function () { return (+h[0]); }, getY: function () { return (+h[1]); },
                             getZ: function () { return (+h[2]); } };
              gsMsSlashFx(__world, locObj, { x: (+h[3]), z: (+h[4]) },
                          { x: (+h[5]), z: (+h[6]) }, (+h[7]) | 0);
              return { ptc: __ptc, snd: __snd, dmg: __dmg };
            }

            // ★ 蚀月轮整条链：同步 runLater 跑完飞行 + 停下后的脉冲
            function __wheel(spec) {
              var c = __reset(spec);
              __budget = 600;
              gsEcWheel(__mkPlayer(c.px, c.py, c.pz, c.dx, c.dy, c.dz));
              return { ptc: __ptc, snd: __snd, dmg: __dmg, locks: __locks,
                       layers: __layers('GS_EC_ST') };
            }

            // ★ 用真 gsEcHold 跑一遍，记下 gsEcPulse 落在第几拍
            function __holdTicks(ms) {
              __reset('0|0|0|0|0|1');
              var p = __mkPlayer(0, 0, 0, 0, 0, 1);
              var real = gsEcPulse;
              gsEcPulse = function () { __pulses.push(__tickNo); return real.apply(null, arguments); };
              __budget = 600;
              gsEcHold(__world, 'P:DUST', null, p, __SELF, 0, 0, 0, ms);
              gsEcPulse = real;
              return __pulses.join(',');
            }

            // ★ 用真 gsEcHold 数「一共画了几拍环」
            function __holdRingCount(ms) {
              __reset('0|0|0|0|0|1');
              var p = __mkPlayer(0, 0, 0, 0, 0, 1);
              var real = gsEcPulse;
              gsEcPulse = function () { return 0; };
              __budget = 600;
              gsEcHold(__world, 'P:DUST', null, p, __SELF, 0, 0, 0, ms);
              gsEcPulse = real;
              return __ptc.length;
            }

            // ★ 定身间隔：同一目标在给定时刻序列上各能否定身
            function __lockOnce(id, nows) {
              __reset('0|0|0|0|0|1');
              var ent = __mkEnt({ id: id, x: 0, y: 0, z: 0, type: 'ZOMBIE', dead: false });
              var out = [];
              for (var i = 0; i < nows.length; i++) {
                out.push(gsEcLockOnce(ent, nows[i]) ? 'T' : 'F');
              }
              return out.join(',');
            }

            // ★ 天降圆柱的层高序列（真函数）
            function __skyHeights() {
              var out = [];
              for (var h = 16.0; h >= -0.2; h -= 0.7) { out.push(h); }
              return out;
            }

            // ★ 蚀月轮停下前的飞行拍数（真函数）
            function __wheelStopTick() {
              var spec = '0|0|0|0|0|1';
              __reset(spec);
              __budget = 600;
              var hits = [];
              var realHold = gsEcHold;
              gsEcHold = function (w, pt, d, p, sid, cx, cy, cz, ms) { hits.push(__tickNo); };
              gsEcWheel(__mkPlayer(0, 0, 0, 0, 0, 1));
              gsEcHold = realHold;
              return hits.length ? hits[0] : -1;
            }
            """;

    // ======================================================================
    // main
    // ======================================================================

    public static void main(String[] args) throws Exception {
        Path script = pick(
                Path.of("..", "QHCGS", "GS", "GS", "scripts", "gs_listener.js"),
                Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"),
                Path.of("GS", "GS", "scripts", "gs_listener.js"));
        if (script == null) {
            System.out.println("!! 找不到 gs_listener.js");
            System.exit(1);
        }
        System.out.println("原脚本: " + script.toAbsolutePath());

        String all = Files.readString(script, StandardCharsets.UTF_8);
        String[] raw = all.split("\n", -1);

        String msRegion = join(raw, MS_FROM - 1, MS_TO);
        String ecRegion = join(raw, EC_FROM - 1, EC_TO);

        Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        ctx.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());
        ctx.eval(Source.newBuilder("js", JS_SHIM, "shim").build());

        // ★ 求值**整个**原脚本（不是只到 7789 行）：
        //   蚀月/月镰依赖的公共判定 gsIsCombatTarget（8032 行）、GS_NOT_ENEMY_TYPES（8014 行）、
        //   GS_LIVING_CLS（8010 行）都**在 7789 行之后**才定义。
        //   只求值前 7789 行的话，gsEcCanHit → gsIsCombatTarget 会 ReferenceError
        //   （被脚本自己的 try/catch 吞掉）⇒ 蚀月侧**一个目标都命中不了**，
        //   拿这种「永远 0 命中」的 JS 当金标准会把真差异全掩盖掉。
        boolean evalOk = true;
        try {
            ctx.eval(Source.newBuilder("js", all, "gs_listener").build());
        } catch (Throwable t) {
            evalOk = false;
            System.out.println("!! 原脚本求值抛错: " + t);
        }
        check("整个原脚本（" + raw.length + " 行）能在桥接桩下求值"
                + "（真 JS，含 gsMsStart / gsEcStart / gsIsCombatTarget）", evalOk);
        ctx.eval(Source.newBuilder("js", JS_HELPERS, "helpers").build());

        constants(ctx);
        logLines(ctx);
        geometry(ctx);
        reverse(ctx, msRegion, ecRegion);

        ctx.close();
        summary();
    }

    private static void summary() {
        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ======================================================================
    // [A] 常量对账
    // ======================================================================

    private static void constants(Context ctx) throws Exception {
        System.out.println();
        System.out.println("[A] 常量对账：原脚本顶层 var vs Java 字段（按名按值，双向）");

        Value b = ctx.getBindings("js");

        // ---- 月镰 ----
        String[] msScalar = {
                "GS_MS_ID", "GS_MS_REACH", "GS_MS_WIDE", "GS_MS_TALL", "GS_MS_DMG",
                "GS_MS_SWING_CD", "GS_MS_MAX", "GS_MS_WINDOW", "GS_MS_FULL_WIN",
                "GS_MS_NO_BREAK", "GS_MS_HIT_PLAYERS",
                "GS_MS_NM_RAYS", "GS_MS_NM_R", "GS_MS_NM_Y", "GS_MS_NM_DMG", "GS_MS_NM_LOCK",
                "GS_MS_LOCK_STICK",
        };
        for (String n : msScalar) {
            constCheck(ctx, b, MsSub.class, n);
        }
        arrCheck(ctx, MsSub.class, "GS_MS_NM_COL");
        keyCheck(ctx, b, MsSub.class, "GS_MS_K_DREAM");
        keyCheck(ctx, b, MsSub.class, "GS_MS_K_UNTIL");
        keyCheck(ctx, b, MsSub.class, "GS_MS_K_FULL");
        keyCheck(ctx, b, MsSub.class, "GS_MS_K_GEN");
        colorMapCheck(ctx, b);

        // ---- 蚀月 ----
        String[] ecScalar = {
                "GS_EC_ID", "GS_EC_NO_BREAK", "GS_EC_HIT_PLAYERS",
                "GS_EC_W_RANGE", "GS_EC_W_R", "GS_EC_W_STEP", "GS_EC_W_DMG", "GS_EC_W_PULSE",
                "GS_EC_W_LOCK", "GS_EC_W_LOCK_CD", "GS_EC_W_H", "GS_EC_W_STACK_PER_HIT",
                "GS_EC_W_LIFE", "GS_EC_W_CD", "GS_EC_W_GAP",
                "GS_EC_SW_HIT", "GS_EC_SW_R", "GS_EC_SW_W", "GS_EC_SW_H",
                "GS_EC_MAX", "GS_EC_WINDOW", "GS_EC_FULL_WIN", "GS_EC_BLOOD_COL",
                "GS_EC_J_R", "GS_EC_J_TICKS", "GS_EC_J_DMG", "GS_EC_J_INV",
                "GS_EC_J_MAX_TARGETS",
        };
        for (String n : ecScalar) {
            constCheck(ctx, b, EcSub.class, n);
        }
        arrCheck(ctx, EcSub.class, "GS_EC_W_COL");
        arrCheck(ctx, EcSub.class, "GS_EC_J_COL_R");
        arrCheck(ctx, EcSub.class, "GS_EC_J_COL_P");
        arrCheck(ctx, EcSub.class, "GS_EC_J_COL_W");
        arrCheck(ctx, EcSub.class, "GS_EC_J_COL_B");
        keyCheck(ctx, b, EcSub.class, "GS_EC_K_BLOOD");
        keyCheck(ctx, b, EcSub.class, "GS_EC_K_UNTIL");
        keyCheck(ctx, b, EcSub.class, "GS_EC_K_FULL");

        // ---- 反方向：Java 里**多出来**的常量（专抓凭空捏造的常量）----
        phantomCheck(ctx, b, MsSub.class, msScalar);
        phantomCheck(ctx, b, EcSub.class, ecScalar);

        // ---- 具体数值的「字面钉」：防止两边一起被改 ----
        check("GS_MS_DMG = 11.0", num(b, "GS_MS_DMG") == 11.0);
        check("GS_MS_REACH = 3.0 / GS_MS_WIDE = 3.0 / GS_MS_TALL = 1.6",
                num(b, "GS_MS_REACH") == 3.0 && num(b, "GS_MS_WIDE") == 3.0 && num(b, "GS_MS_TALL") == 1.6);
        check("GS_MS_MAX = 12 / GS_MS_NM_DMG = 35.0 / GS_MS_NM_R = 5.0 / GS_MS_NM_LOCK = 3000",
                num(b, "GS_MS_MAX") == 12.0 && num(b, "GS_MS_NM_DMG") == 35.0
                        && num(b, "GS_MS_NM_R") == 5.0 && num(b, "GS_MS_NM_LOCK") == 3000.0);
        check("GS_EC_W_DMG = 14.0 / GS_EC_W_PULSE = 500 / GS_EC_W_STEP = 0.5",
                num(b, "GS_EC_W_DMG") == 14.0 && num(b, "GS_EC_W_PULSE") == 500.0
                        && num(b, "GS_EC_W_STEP") == 0.5);
        check("GS_EC_W_LOCK = 600 / GS_EC_W_LOCK_CD = 3000 / GS_EC_W_LIFE = 1500 / GS_EC_W_CD = 1200",
                num(b, "GS_EC_W_LOCK") == 600.0 && num(b, "GS_EC_W_LOCK_CD") == 3000.0
                        && num(b, "GS_EC_W_LIFE") == 1500.0 && num(b, "GS_EC_W_CD") == 1200.0);
        check("GS_EC_MAX = 23 / GS_EC_J_DMG = 233.0 / GS_EC_J_R = 8.0",
                num(b, "GS_EC_MAX") == 23.0 && num(b, "GS_EC_J_DMG") == 233.0 && num(b, "GS_EC_J_R") == 8.0);
        check("GS_EC_J_TICKS = 36 / GS_EC_J_INV = 36（1.8 秒）",
                num(b, "GS_EC_J_TICKS") == 36.0 && num(b, "GS_EC_J_INV") == 36.0);
        check("GS_EC_J_MAX_TARGETS = 60", num(b, "GS_EC_J_MAX_TARGETS") == 60.0);
        check("GS_MS_NO_BREAK / GS_EC_NO_BREAK = true",
                bool(b, "GS_MS_NO_BREAK") && bool(b, "GS_EC_NO_BREAK"));
        check("GS_MS_HIT_PLAYERS / GS_EC_HIT_PLAYERS = false",
                !bool(b, "GS_MS_HIT_PLAYERS") && !bool(b, "GS_EC_HIT_PLAYERS"));
        check("GS_EC_W_STACK_PER_HIT = false（死代码分支）", !bool(b, "GS_EC_W_STACK_PER_HIT"));
        check("GS_EC_SW_HIT = true（普通左键叠层开着）", bool(b, "GS_EC_SW_HIT"));
    }

    private static void constCheck(Context ctx, Value b, Class<?> cls, String name) {
        try {
            Value jsv = b.getMember(name);
            if (jsv == null || jsv.isNull()) {
                check(name + " 在原脚本里存在", false);
                return;
            }
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object jv = f.get(null);
            boolean ok;
            if (jv instanceof Boolean) {
                ok = jsv.asBoolean() == (Boolean) jv;
            } else if (jv instanceof Double) {
                ok = jsv.asDouble() == (Double) jv;
            } else if (jv instanceof Integer) {
                ok = jsv.asInt() == (Integer) jv;
            } else if (jv instanceof Long) {
                ok = jsv.asLong() == (Long) jv;
            } else if (jv instanceof String) {
                ok = jsv.asString().equals(jv);
            } else {
                ok = false;
                System.out.println("   ?? " + name + " Java 类型未覆盖: " + jv.getClass());
            }
            check(name + " = " + jv, ok);
        } catch (NoSuchFieldException e) {
            check(name + " 在 Java 里存在", false);
        } catch (Throwable t) {
            check(name + " 反射失败: " + t, false);
        }
    }

    private static void arrCheck(Context ctx, Class<?> cls, String name) {
        try {
            String js = ctx.eval("js", "String(" + name + ".join(','))").asString();
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            int[] jv = (int[]) f.get(null);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < jv.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(jv[i]);
            }
            check(name + " = [" + sb + "]", js.equals(sb.toString()));
        } catch (Throwable t) {
            check(name + " 数组对账失败: " + t, false);
        }
    }

    private static void keyCheck(Context ctx, Value b, Class<?> cls, String name) {
        try {
            String js = ctx.eval("js", "String('' + " + name + ")").asString();
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object jv = f.get(null);
            check(name + " = " + jv, jv != null && js.equals(jv.toString()));
        } catch (Throwable t) {
            check(name + " 键名对账失败: " + t, false);
        }
    }

    /** {@code GS_MS_C} 颜色表 vs {@code MsSub.msColorTable()}。 */
    private static void colorMapCheck(Context ctx, Value b) throws Exception {
        try {
            Method m = MsSub.class.getDeclaredMethod("msColorTable");
            m.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> jv = (Map<String, String>) m.invoke(null);
            Value js = b.getMember("GS_MS_C");
            if (js == null || js.isNull()) {
                check("GS_MS_C 在原脚本里存在", false);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, String> e : jv.entrySet()) {
                String got = ctx.eval("js", "String('' + GS_MS_C['" + e.getKey() + "'])").asString();
                sb.append(e.getKey()).append('=').append(got.equals(e.getValue()) ? "ok" : "MISMATCH");
                sb.append(' ');
                if (!got.equals(e.getValue())) {
                    check("GS_MS_C." + e.getKey() + " 期望 " + e.getValue() + " 实得 " + got, false);
                }
            }
            check("GS_MS_C 颜色表逐项一致（" + jv.size() + " 项）: " + sb.toString().trim(), true);
        } catch (Throwable t) {
            check("GS_MS_C 对账失败: " + t, false);
        }
    }

    /**
     * 反方向：Java 里所有 {@code static final} 常量都必须在原脚本里有同名顶层 var。
     *
     * <p>★ 这条专门抓「移植时顺手多写了一个常量」——例如曾经多出来的
     * {@code GS_MS_LOCK_STICK_NOTE}。
     */
    private static void phantomCheck(Context ctx, Value b, Class<?> cls, String[] known) {
        List<String> extra = new ArrayList<>();
        List<String> knownList = Arrays.asList(known);
        for (Field f : cls.getDeclaredFields()) {
            int mod = f.getModifiers();
            if (!java.lang.reflect.Modifier.isStatic(mod) || !java.lang.reflect.Modifier.isFinal(mod)) {
                continue;
            }
            String n = f.getName();
            if (!n.startsWith("GS_")) {
                continue;               // 只管「像脚本常量」的那些（GS_* 前缀）
            }
            if (knownList.contains(n)) {
                continue;
            }
            Value v = b.getMember(n);
            if (v == null || v.isNull()) {
                extra.add(n);
            }
        }
        check(cls.getSimpleName() + " 没有凭空多出来的常量" + (extra.isEmpty() ? "" : " -> " + extra), extra.isEmpty());
    }

    private static double num(Value b, String n) {
        return b.getMember(n).asDouble();
    }

    private static boolean bool(Value b, String n) {
        return b.getMember(n).asBoolean();
    }

    // ======================================================================
    // [B] 日志行对账
    // ======================================================================

    private static void logLines(Context ctx) throws Exception {
        System.out.println();
        System.out.println("[B] 日志行对账：Java 静态方法 vs 真 JS 跑出来的行 vs 参考服日志");

        Value log = ctx.getBindings("js").getMember("__log");
        long n = log.getArraySize();
        List<String> lines = new ArrayList<>();
        for (long i = 0; i < n; i++) {
            String s = log.getArrayElement(i).asString();
            // 桩给每行加了 'INFO ' / 'WARN ' / 'SEV ' 级别前缀，比对前去掉
            int sp = s.indexOf(' ');
            lines.add(sp > 0 ? s.substring(sp + 1) : s);
        }

        String jsMsReg = findLog(lines, "月镰监听器注册成功");
        String jsMsSta = findLog(lines, "月镰状态：");
        String jsMsLoad = findLog(lines, "月镰（GS_MOON_SCYTHE）");
        String jsEcReg = findLog(lines, "蚀月·死神梦镰监听器注册成功");
        String jsEcSta = findLog(lines, "蚀月状态：");
        String jsEcLoad = findLog(lines, "蚀月·死神梦镰（GS_ECLIPSE_SCYTHE）");

        check("真 JS 跑出了 6 行月镰/蚀月日志", jsMsReg != null && jsMsSta != null && jsMsLoad != null
                && jsEcReg != null && jsEcSta != null && jsEcLoad != null);

        // Java 侧的「带前缀完整行」（与脚本 getLogger().info('[构思技艺] ' + msg) 对齐）
        String jMsReg = (String) callStatic(MsSub.class, "gsMsRegisterLine");
        String jMsLoad = (String) callStatic(MsSub.class, "gsMsLoadLine");
        String jEcReg = (String) callStatic(EcSub.class, "gsEcRegisterLine");
        String jEcLoad = (String) callStatic(EcSub.class, "gsEcLoadLine");

        // ---- ① 与真 JS 逐字节比对 ----
        // 月镰状态行带**运行期动态**的世界世代号 ⇒ 拆成「前缀 + 正整数」两段比
        String jMsSta = (String) callStatic(MsSub.class, "gsMsStatusLine",
                new Class<?>[]{int.class, boolean.class}, 1, true);
        String jEcSta = (String) callStatic(EcSub.class, "gsEcStatusLine",
                new Class<?>[]{boolean.class}, true);
        eq("月镰注册行 vs 真 JS", jsMsReg, jMsReg);
        eq("月镰状态行 vs 真 JS（世代号=1）", jsMsSta, jMsSta);
        eq("月镰加载行 vs 真 JS", jsMsLoad, jMsLoad);
        eq("蚀月注册行 vs 真 JS", jsEcReg, jEcReg);
        eq("蚀月状态行 vs 真 JS", jsEcSta, jEcSta);
        eq("蚀月加载行 vs 真 JS", jsEcLoad, jEcLoad);

        // ---- ② 与参考服日志逐字节比对 ----
        Path ref = pick(
                Path.of("docs", "参考服日志_构思技艺.txt"),
                Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt"));
        if (ref == null) {
            check("找到 docs\\参考服日志_构思技艺.txt", false);
            return;
        }
        List<String> rl = Files.readAllLines(ref, StandardCharsets.UTF_8);
        String rMsReg = findLog(rl, "月镰监听器注册成功");
        String rMsSta = findLog(rl, "月镰状态：");
        String rMsLoad = findLog(rl, "月镰（GS_MOON_SCYTHE）");
        String rEcReg = findLog(rl, "蚀月·死神梦镰监听器注册成功");
        String rEcSta = findLog(rl, "蚀月状态：");
        String rEcLoad = findLog(rl, "蚀月·死神梦镰（GS_ECLIPSE_SCYTHE）");
        check("参考服日志里 6 行都在（第 28~33 行）",
                rMsReg != null && rMsSta != null && rMsLoad != null
                        && rEcReg != null && rEcSta != null && rEcLoad != null);

        eq("月镰注册行 vs 参考服日志", rMsReg, jMsReg);
        eq("月镰加载行 vs 参考服日志", rMsLoad, jMsLoad);
        eq("蚀月注册行 vs 参考服日志", rEcReg, jEcReg);
        eq("蚀月状态行 vs 参考服日志", rEcSta, jEcSta);

        // ★ 参考服日志里的「蚀月加载行」是 215 字符 —— **没有被截断**。
        //   契约文档 §3 D 组写「最后两行被截断」，这里用逐字节相等直接证伪。
        check("★ 参考服日志的蚀月加载行 = 215 字符（**未被截断**，可逐字节比）",
                rEcLoad != null && rEcLoad.length() == 215);
        eq("蚀月加载行 vs 参考服日志（逐字节，无截断）", rEcLoad, jEcLoad);
        check("★ 参考服日志的月镰加载行 = 92 字符（**未被截断**）",
                rMsLoad != null && rMsLoad.length() == 92);

        // ---- ③ 月镰状态行的世代号按「动态值」处理 ----
        String head = ListenerCore.PREFIX + "月镰状态：层数存内存（权威）· PDC 备份 可用 · 世界世代号 ";
        check("月镰状态行前缀逐字节正确", rMsSta != null && rMsSta.startsWith(head));
        if (rMsSta != null && rMsSta.startsWith(head)) {
            String tail = rMsSta.substring(head.length());
            boolean pos = tail.matches("[1-9][0-9]*");
            check("月镰状态行的世代号是正整数（参考服为 96，**不写死**）: \"" + tail + "\"", pos);
        } else {
            check("月镰状态行的世代号是正整数", false);
        }

        // ---- ④ 状态行里「PDC 备份 可用 / 不可用」这个脚本自探测分支必须保留 ----
        String jEcStaNo = (String) callStatic(EcSub.class, "gsEcStatusMsg", new Class<?>[]{boolean.class}, false);
        eq("蚀月状态行（pdcOk=false 分支）", "蚀月状态：层数存内存（权威）· PDC 备份 不可用", jEcStaNo);
        String jMsStaNo = (String) callStatic(MsSub.class, "gsMsStatusMsg",
                new Class<?>[]{int.class, boolean.class}, 96, false);
        eq("月镰状态行（pdcOk=false，世代号 96）",
                "月镰状态：层数存内存（权威）· PDC 备份 不可用 · 世界世代号 96", jMsStaNo);
    }

    private static String findLog(List<String> lines, String marker) {
        for (String s : lines) {
            if (s.contains(marker)) {
                return s;
            }
        }
        return null;
    }

    // ======================================================================
    // [C] 数值 / 几何算式对账
    // ======================================================================

    private static void geometry(Context ctx) throws Exception {
        System.out.println();
        System.out.println("[C] 数值/几何对账：真 JS 函数 vs Java（挥砍 / 蚀月轮 / 脉冲 / 圆环 / 噩梦 / 法阵）");

        MsSub ms = new MsSub(CORE);
        EcSub ec = new EcSub(CORE);
        ms.bindEc(ec);

        swingGrid(ctx, ms);
        meleeGrid(ctx, ec);
        pulseGrid(ctx, ec);
        ringGrid(ctx, ec);
        holdGrid(ctx, ec);
        wheelGrid(ctx, ec);
        nightmareGrid(ctx, ms);
        fieldGrid(ctx, ec);
        slashGrid(ctx, ms);
        lockGrid(ctx, ec);
        skyGrid(ctx, ec);
    }

    // ---- 挥砍：真 gsMsSwing vs MsSub.gsMsSwing ----

    private static void swingGrid(Context ctx, MsSub ms) throws Exception {
        // 布局：玩家在原点朝 +Z，候选怪放在前后左右与高度边界上
        String[][] cases = {
                {"0|0|0|0|0|1", "e1|0|0|3|ZOMBIE|0"},                       // 正前方 3 格
                {"0|0|0|0|0|1", "e1|0|0|3.4|ZOMBIE|0"},                     // 超出长度
                {"0|0|0|0|0|1", "e1|1.4|0|1|ZOMBIE|0"},                     // 右 1.4 格（在宽内）
                {"0|0|0|0|0|1", "e1|1.6|0|1|ZOMBIE|0"},                     // 右 1.6 格（超宽）
                {"0|0|0|0|0|1", "e1|-1.4|0|1|ZOMBIE|0"},                    // 左 1.4 格
                {"0|0|0|0|0|1", "e1|0|1.4|1|ZOMBIE|0"},                     // 抬高 1.4（在 GS_MS_TALL=1.6 内）
                {"0|0|0|0|0|1", "e1|0|1.8|1|ZOMBIE|0"},                     // 抬高 1.8（超出）
                {"0|0|0|0|0|1", "e1|0|-1.0|1|ZOMBIE|0"},                    // 脚下（fwd 1.0，高差 -1.9 → 超）
                {"0|0|0|0|0|1", "e1|0|0|-0.3|ZOMBIE|0"},                    // 身后 0.3（-0.4 容差内）
                {"0|0|0|0|0|1", "e1|0|0|-0.5|ZOMBIE|0"},                    // 身后 0.5（超）
                {"0|0|0|0|0|1", "e1|0|0|1|PLAYER|0"},                       // 玩家（HIT_PLAYERS=false）
                {"0|0|0|0|0|1", "e1|0|0|1|ARMOR_STAND|0"},                  // 盔甲架
                {"0|0|0|0|0|1", "e1|0|0|1|ITEM|0"},                         // ★ 掉落物（有 getHealth）
                {"0|0|0|0|0|1", "e1|0|0|1|ZOMBIE|1"},                       // 已死
                {"0|0|0|0|0|1", "e1|0|0|1|ZOMBIE|0;e2|0.5|0|2|ZOMBIE|0;e3|-0.5|0|2.5|ZOMBIE|0"},
                {"0|0|0|0|0|1", "00000000-0000-0000-0000-0000000000aa|0|0|1|ZOMBIE|0"},  // 自己
                {"5|64|-3|0|0|1", "e1|5|64|-1.5|ZOMBIE|0"},                 // 非原点
                {"5|64|-3|0.6|0|0.8", "e1|7|64|-2.4|ZOMBIE|0"},             // 斜向 0.6/0.8
                {"5|64|-3|0|0|1", "e1|5|64|-1.5|ZOMBIE|0;e2|6.4|64|-2|ZOMBIE|0"},   // 一个在宽外
                {"-7|20|9|0|0|-1", "e1|-7|20|6.5|ZOMBIE|0"},                // 朝 -Z
        };
        int bad = 0;
        for (String[] c : cases) {
            String spec = c[0] + ";" + c[1];
            Trace js = fromJs(ctx, "swing", spec);
            Trace jv = javaSwing(ms, spec);
            if (!sameTrace("swing[" + spec + "]", js, jv)) {
                bad++;
            }
        }
        check("挥砍几何 + 命中集 + 粒子 + 音效（" + cases.length + " 组）", bad == 0);
    }

    private static Trace javaSwing(MsSub ms, String spec) throws Exception {
        Trace t = new Trace();
        Spec s = Spec.parse(spec, t);
        Player p = mockPlayer(t, s.px, s.py, s.pz, s.dx, s.dy, s.dz);
        resetMsState(ms);
        call(ms, "gsMsSwing", new Class<?>[]{Player.class}, p);
        t.layers = stN(call(ms, "gsMsGet", new Class<?>[]{Player.class}, p));
        return t;
    }

    // ---- 普通左键（蚀月）：真 gsEcMelee vs EcSub.gsEcMelee ----

    private static void meleeGrid(Context ctx, EcSub ec) throws Exception {
        String[][] cases = {
                {"0|0|0|0|0|1", "e1|0|0|3|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|3.4|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|1.4|0|1|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|1.6|0|1|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|1.4|1|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|1.8|1|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|1|PLAYER|0"},
                {"0|0|0|0|0|1", "e1|0|0|1|ARMOR_STAND|0"},
                {"0|0|0|0|0|1", "e1|0|0|1|ITEM|0"},          // ★ EC 必须挡住掉落物
                {"0|0|0|0|0|1", "e1|0|0|1|ZOMBIE|1"},
                {"0|0|0|0|0|1", "e1|0|0|1|ZOMBIE|0;e2|0.5|0|2|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|0|ZOMBIE|0"},        // 贴脸（fwd=0）
                {"0|0|0|0|0|1", "e1|0|0|-0.3|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|-0.5|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|1|ZOMBIE|0;e2|9|0|9|ZOMBIE|0"},
                {"3|-2|7|0|1|0", "e1|3|-2|7.5|ZOMBIE|0"},   // ★ 垂直看天 ⇒ 直接返回 0
        };
        int bad = 0;
        for (String[] c : cases) {
            String spec = c[0] + ";" + c[1];
            Value js = callJs(ctx, "melee", spec);
            Trace jt = new Trace();
            Spec s = Spec.parse(spec, jt);
            Player p = mockPlayer(jt, s.px, s.py, s.pz, s.dx, s.dy, s.dz);
            resetEcState(ec);
            Object r = call(ec, "gsEcMelee", new Class<?>[]{Player.class}, p);
            int jn = ((Number) r).intValue();
            int jsn = js.getMember("n").asInt();
            int jsl = js.getMember("layers").asInt();
            int jl = stN(call(ec, "gsEcGet", new Class<?>[]{Player.class}, p));
            if (jn != jsn || jl != jsl) {
                System.out.println("   melee[" + spec + "] JS n=" + jsn + " layers=" + jsl
                        + " / Java n=" + jn + " layers=" + jl);
                bad++;
            }
        }
        check("普通左键命中数 + 叠层（" + cases.length + " 组）", bad == 0);
    }

    // ---- 伤害脉冲：真 gsEcPulse vs EcSub.gsEcPulse ----

    private static void pulseGrid(Context ctx, EcSub ec) throws Exception {
        String[][] cases = {
                {"0|0|0|0|0|1", "e1|0|0|0.9|ZOMBIE|0"},                 // 圆环正中
                {"0|0|0|0|0|1", "e1|0.9|0|0|ZOMBIE|0"},                 // 半径 0.9（内）
                {"0|0|0|0|0|1", "e1|1.1|0|0|ZOMBIE|0"},                 // 半径 1.1（外 ⇒ 圆形判定挡掉）
                {"0|0|0|0|0|1", "e1|0.8|0|0.8|ZOMBIE|0"},               // √1.28≈1.13 > 1 ⇒ 挡掉
                {"0|0|0|0|0|1", "e1|0|2.6|0|ZOMBIE|0"},                 // 高度差 (0.9+2.6)-0 = 3.5 > 1.8
                {"0|0|0|0|0|1", "e1|0|1.0|0|ZOMBIE|0"},                 // 高度差 1.9 > 1.8 ⇒ 挡掉
                {"0|0|0|0|0|1", "e1|0|0.8|0|ZOMBIE|0"},                 // 高度差 1.7 ⇒ 通过
                {"0|0|0|0|0|1", "e1|0|0|0|PLAYER|0"},
                {"0|0|0|0|0|1", "e1|0|0|0|ITEM|0"},                     // ★ 掉落物必须挡掉
                {"0|0|0|0|0|1", "e1|0|0|0|ARMOR_STAND|0"},
                {"0|0|0|0|0|1", "e1|0|0|0|ZOMBIE|1"},
                {"0|0|0|0|0|1", "e1|0|0|0|ZOMBIE|0;e2|0.5|0|0.5|ZOMBIE|0;e3|0.7|0|-0.7|ZOMBIE|0"},
                {"0|0|0|0|0|1", "e1|0|0|0|ZOMBIE|0;e2|1.2|0|0|ZOMBIE|0"},
                {"0|0|0|0|0|1", ""},
        };
        int bad = 0;
        for (String[] c : cases) {
            String spec = c[0] + ";" + c[1];
            Value js = callJs(ctx, "pulse", spec);
            Trace jt = new Trace();
            Spec s = Spec.parse(spec, jt);
            Player p = mockPlayer(jt, s.px, s.py, s.pz, s.dx, s.dy, s.dz);
            resetEcState(ec);
            clearLocks(CORE);
            Object r = call(ec, "gsEcPulse",
                    new Class<?>[]{World.class, Player.class, String.class, double.class, double.class, double.class},
                    jt.world, p, SELF, s.px, s.py, s.pz);
            int jn = ((Number) r).intValue();
            int jsn = js.getMember("n").asInt();
            // ★ Java 侧的定身调用记在 ListenerCore 实例的 gsMsLocks 里（Trace.locks 只有 JS 侧有）
            int jlocks = locks(CORE).size();
            boolean ok = jn == jsn
                    && jt.dmg.size() == js.getMember("dmg").getArraySize()
                    && jlocks == js.getMember("locks").getArraySize();
            if (ok) {
                Value jd = js.getMember("dmg");
                for (int i = 0; i < jt.dmg.size(); i++) {
                    Value e = jd.getArrayElement(i);
                    if (!String.valueOf(jt.dmg.get(i)[0]).equals(e.getArrayElement(0).asString())
                            || !near((Double) jt.dmg.get(i)[1], e.getArrayElement(1).asDouble())) {
                        ok = false;
                        break;
                    }
                }
            }
            if (!ok) {
                System.out.println("   pulse[" + spec + "] JS n=" + jsn + " dmg=" + js.getMember("dmg").getArraySize()
                        + " locks=" + js.getMember("locks").getArraySize()
                        + " / Java n=" + jn + " dmg=" + jt.dmg.size() + " locks=" + jlocks);
                bad++;
            }
        }
        check("伤害脉冲命中集 + 14 点伤害 + 定身调用（" + cases.length + " 组）", bad == 0);
    }

    // ---- 圆环：真 gsEcRingAt vs EcSub.gsEcRingAt ----

    private static void ringGrid(Context ctx, EcSub ec) throws Exception {
        double[][] cases = {
                {0, 0, 0, 1.0, 0.35},
                {0, 0, 0, 1.0, 1.0},
                {0, 0, 0, 8.0, 0.8},
                {0, 0, 0, 8.0 * 0.68, 0.7},
                {0, 0, 0, 8.0 * 0.36, 0.6},
                {3.5, 64.25, -2.75, 5.0, 0.3},
                {0, 0, 0, 0.01, 0.35},          // 极小半径 ⇒ 钳到 8 点
                {0, 0, 0, 100.0, 0.1},          // 极大半径 ⇒ 钳到 200 点
                {0, 0, 0, 1.0, 0.0},            // gap<=0 ⇒ 用 0.4
                {0, 0, 0, 1.0, -1.0},
        };
        int bad = 0;
        for (double[] c : cases) {
            String spec = c[0] + "|" + c[1] + "|" + c[2] + "|" + c[3] + "|" + c[4];
            Trace js = fromJs(ctx, "ringAt", spec);
            Trace jv = new Trace();
            jv.world = mockWorld(jv, false, null, false);
            Particle dust = new ListenerCore().gsMsDustType();
            Particle.DustOptions data = ListenerCore.gsMsDustOpts(235, 30, 40, 1.0);
            call(ec, "gsEcRingAt",
                    new Class<?>[]{World.class, Particle.class, Particle.DustOptions.class,
                            double.class, double.class, double.class, double.class, double.class},
                    jv.world, dust, data, c[0], c[1], c[2], c[3], c[4]);
            if (!sameTrace("ringAt[" + spec + "]", js, jv)) {
                bad++;
            }
        }
        check("圆环点数 + 逐点坐标（" + cases.length + " 组，含钳位 8/200 与 gap<=0）", bad == 0);

        // 点数公式本身
        int bad2 = 0;
        for (double r = 0.05; r <= 20.0; r += 0.05) {
            for (double g : new double[]{0.1, 0.3, 0.35, 0.6, 0.8, 1.0, 0.0, -2.0}) {
                int jv = (Integer) callStatic(EcSub.class, "gsEcRingCount",
                        new Class<?>[]{double.class, double.class}, r, g);
                int js = ctx.eval("js", "(function(){var g=(" + g + "<=0)?0.4:(" + g
                        + ");var n=Math.round((2.0*Math.PI*(" + r + "))/g);if(n<8)n=8;if(n>200)n=200;return n;})()").asInt();
                if (jv != js) {
                    System.out.println("   ringCount r=" + r + " gap=" + g + " JS=" + js + " Java=" + jv);
                    bad2++;
                }
            }
        }
        check("gsEcRingCount 扫描 8×8×... 组（400 半径 × 8 点距）", bad2 == 0);
    }

    // ---- 停下后的节拍：真 gsEcHold vs EcSub.gsEcHoldPulseTicks / gsEcHoldTicks ----

    private static void holdGrid(Context ctx, EcSub ec) throws Exception {
        long[] cases = {100, 500, 1000, 1500, 2000, 3000, 7000, 50, 250, 1250, 35000};
        int badP = 0;
        int badR = 0;
        for (long ms : cases) {
            String jsTicks = ctx.eval("js", "__holdTicks(" + ms + ")").asString();
            int[] jv = (int[]) callStatic(EcSub.class, "gsEcHoldPulseTicks",
                    new Class<?>[]{double.class, double.class}, (double) ms, fnum(EcSub.class, "GS_EC_W_PULSE"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < jv.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(jv[i]);
            }
            if (!jsTicks.equals(sb.toString())) {
                System.out.println("   holdTicks ms=" + ms + " JS=[" + jsTicks + "] Java=[" + sb + "]");
                badP++;
            }
            int jsRings = ctx.eval("js", "__holdRingCount(" + ms + ")").asInt();
            // ★ gsEcHoldTicks 返回的是「画了几**拍**环」（left 每拍 -2），
            //   真 gsEcHold 每拍画一整圈 ⇒ 点数 = 拍数 × 圆环点数。
            int jvR = (Integer) callStatic(EcSub.class, "gsEcHoldTicks",
                    new Class<?>[]{double.class}, (double) ms);
            int jvPts = jvR * ringN(fnum(EcSub.class, "GS_EC_W_R"), fnum(EcSub.class, "GS_EC_W_GAP"));
            if (jsRings != jvPts) {
                System.out.println("   holdRings ms=" + ms + " JS点数=" + jsRings
                        + " Java拍数=" + jvR + " × 每圈点数=" + ringN(1.0, 0.35) + " = " + jvPts);
                badR++;
            }
        }
        check("停下后的伤害脉冲拍号（真 gsEcHold 跑整条链 vs gsEcHoldPulseTicks，" + cases.length + " 组）", badP == 0);
        check("停下后画环拍数（真 gsEcHold vs gsEcHoldTicks，" + cases.length + " 组）", badR == 0);
        String h1500 = ctx.eval("js", "__holdTicks(1500)").asString();
        check("★ 1.5 秒生命里脉冲落在第 " + h1500 + " 拍"
                + "（每 5 拍一发 ⇒ 1.5 秒里 3 发；原脚本注释写的「每 0.5 秒 / 第 1、11、21 刻」是错的）",
                "1,6,11".equals(h1500));
    }
    // ---- 蚀月轮整条链：真 gsEcWheel（同步 runLater）vs Java 的节拍算式 ----

    private static void wheelGrid(Context ctx, EcSub ec) throws Exception {
        String[] dirs = {"0|0|1", "0|1|0", "1|0|0", "0.6|0|0.8", "0|-1|0", "-1|0|0"};
        int jsRingCount = ctx.eval("js", "(function(){var g=GS_EC_W_GAP;var n=Math.round((2.0*Math.PI*GS_EC_W_R)/g);if(n<8)n=8;if(n>200)n=200;return n;})()").asInt();
        int jRingCount = (Integer) callStatic(EcSub.class, "gsEcRingCount",
                new Class<?>[]{double.class, double.class},
                fnum(EcSub.class, "GS_EC_W_R"), fnum(EcSub.class, "GS_EC_W_GAP"));
        check("蚀月轮圆环点数 真 JS=" + jsRingCount + " / Java=" + jRingCount, jsRingCount == jRingCount);

        int jStop = (Integer) callStatic(EcSub.class, "gsEcWheelStopTick");
        int jsStop = ctx.eval("js", "__wheelStopTick()").asInt();
        check("蚀月轮无碰撞时第 " + jStop + " 拍停下（真 gsEcWheel vs gsEcWheelStopTick）", jStop == jsStop);

        int jHold = (Integer) callStatic(EcSub.class, "gsEcHoldTicks",
                new Class<?>[]{double.class}, fnum(EcSub.class, "GS_EC_W_LIFE"));

        int bad = 0;
        for (String d : dirs) {
            String spec = "0|0|0|" + d;
            Value js = callJs(ctx, "wheel", spec);
            int jsPtc = (int) js.getMember("ptc").getArraySize();
            int jsSnd = (int) js.getMember("snd").getArraySize();
            int jsDmg = (int) js.getMember("dmg").getArraySize();
            int expectPtc = (jStop + jHold) * jRingCount;
            if (jsPtc != expectPtc || jsSnd != 1 || jsDmg != 0) {
                System.out.println("   wheel[" + d + "] JS ptc=" + jsPtc + " snd=" + jsSnd + " dmg=" + jsDmg
                        + " 期望 ptc=" + expectPtc + " snd=1 dmg=0");
                bad++;
            }
        }
        check("蚀月轮飞行 + 停下整条链的粒子/音效/伤害计数（6 个方向）", bad == 0);

        // 撞墙：第 1 拍就停 ⇒ 1 + 15 拍
        int badWall = 0;
        for (String d : dirs) {
            String spec = "0|0|0|" + d + "|1";
            Value js = callJs(ctx, "wheel", spec);
            int jsPtc = (int) js.getMember("ptc").getArraySize();
            int expect = (1 + jHold) * jRingCount;
            if (jsPtc != expect) {
                System.out.println("   wheel-wall[" + d + "] JS ptc=" + jsPtc + " 期望 " + expect);
                badWall++;
            }
        }
        check("蚀月轮撞墙 ⇒ 第 1 拍就停（6 个方向，粒子数 = (1+" + jHold + ")×" + jRingCount + "）", badWall == 0);
    }

    // ---- 噩梦：真 gsMsNightmare vs MsSub.gsMsNightmare ----

    private static void nightmareGrid(Context ctx, MsSub ms) throws Exception {
        String[][] cases = {
                {"0|0|0|0|0|1", "e1|0|0.9|0|ZOMBIE|0"},                // 圆心
                {"0|0|0|0|0|1", "e1|4.9|0.9|0|ZOMBIE|0"},              // 半径内
                {"0|0|0|0|0|1", "e1|5.1|0.9|0|ZOMBIE|0"},              // 半径外（圆判定）
                {"0|0|0|0|0|1", "e1|4.0|0.9|4.0|ZOMBIE|0"},            // 斜向 5.66 ⇒ 出圈
                {"0|0|0|0|0|1", "e1|3.0|0.9|3.0|ZOMBIE|0"},            // 斜向 4.24 ⇒ 圈内
                {"0|0|0|0|0|1", "e1|0|3.5|0|ZOMBIE|0"},                // |Δy|=2.6 > 2.5
                {"0|0|0|0|0|1", "e1|0|3.3|0|ZOMBIE|0"},                // |Δy|=2.4 ⇒ 通过
                {"0|0|0|0|0|1", "e1|0|0.9|0|PLAYER|0"},
                {"0|0|0|0|0|1", "e1|0|0.9|0|ITEM|0"},                  // ★ 掉落物
                {"0|0|0|0|0|1", "e1|0|0.9|0|ARMOR_STAND|0"},
                {"0|0|0|0|0|1", "e1|0|0.9|0|ZOMBIE|1"},
                {"0|0|0|0|0|1", "e1|1|0.9|1|ZOMBIE|0;e2|2|0.9|2|ZOMBIE|0;e3|4.9|0.9|0|ZOMBIE|0"},
                {"12|30|-8|0|0|1", "e1|12|30.9|-4.5|ZOMBIE|0"},        // 非原点
        };
        int bad = 0;
        for (String[] c : cases) {
            String spec = c[0] + ";" + c[1];
            Value js = callJs(ctx, "nightmare", spec);
            Trace jt = new Trace();
            Spec s = Spec.parse(spec, jt);
            Player p = mockPlayer(jt, s.px, s.py, s.pz, s.dx, s.dy, s.dz);
            resetMsState(ms);
            clearLocks(CORE);
            setSt(ms, "GS_MS_ST", SELF, 12, 0L, 1);
            Object r = call(ms, "gsMsNightmare", new Class<?>[]{Player.class}, p);
            boolean ret = Boolean.TRUE.equals(r);
            boolean jsRet = js.getMember("ret").asInt() == 1;
            int jlocks = locks(CORE).size();
            boolean ok = ret == jsRet
                    && jt.dmg.size() == js.getMember("dmg").getArraySize()
                    && jt.ptc.size() == js.getMember("ptc").getArraySize()
                    && jlocks == js.getMember("locks").getArraySize()
                    && jt.snd.size() == js.getMember("snd").getArraySize();
            if (ok) {
                Value jd = js.getMember("dmg");
                for (int i = 0; i < jt.dmg.size(); i++) {
                    Value e = jd.getArrayElement(i);
                    if (!String.valueOf(jt.dmg.get(i)[0]).equals(e.getArrayElement(0).asString())
                            || !near((Double) jt.dmg.get(i)[1], e.getArrayElement(1).asDouble())) {
                        ok = false;
                        break;
                    }
                }
            }
            if (ok) {
                Value jp = js.getMember("ptc");
                for (int i = 0; i < jt.ptc.size(); i++) {
                    Value e = jp.getArrayElement(i);
                    double[] q = jt.ptc.get(i);
                    if (!near(q[0], e.getArrayElement(0).asDouble())
                            || !near(q[1], e.getArrayElement(1).asDouble())
                            || !near(q[2], e.getArrayElement(2).asDouble())) {
                        ok = false;
                        System.out.println("   nightmare 粒子第 " + i + " 个不一致");
                        break;
                    }
                }
            }
            if (!ok) {
                System.out.println("   nightmare[" + spec + "] JS ret=" + jsRet
                        + " dmg=" + js.getMember("dmg").getArraySize()
                        + " ptc=" + js.getMember("ptc").getArraySize()
                        + " locks=" + js.getMember("locks").getArraySize()
                        + " snd=" + js.getMember("snd").getArraySize()
                        + " / Java ret=" + ret + " dmg=" + jt.dmg.size() + " ptc=" + jt.ptc.size()
                        + " locks=" + jlocks + " snd=" + jt.snd.size());
                bad++;
            }
        }
        check("噩梦：24 根射线粒子 + 35 点伤害 + 定身 3000ms（" + cases.length + " 组）", bad == 0);

        // 未满 12 层 ⇒ 直接返回 false、什么都不做
        Trace t0 = new Trace();
        Player p0 = mockPlayer(t0, 0, 0, 0, 0, 0, 1);
        resetMsState(ms);
        setSt(ms, "GS_MS_ST", SELF, 11, 0L, 0);
        Object r0 = call(ms, "gsMsNightmare", new Class<?>[]{Player.class}, p0);
        check("噩梦：未满 12 层 ⇒ 返回 false 且不产生任何粒子/伤害",
                Boolean.FALSE.equals(r0) && t0.ptc.isEmpty() && t0.dmg.isEmpty());
        check("噩梦：真 JS 未满层也返回 false",
                ctx.eval("js", "(function(){__reset('0|0|0|0|0|1');"
                        + "var p=__mkPlayer(0,0,0,0,0,1);GS_MS_ST[__SELF]={n:11,until:__now+1000,full:0};"
                        + "return gsMsNightmare(p)?'T':'F';})()").asString().equals("F"));
    }

    // ---- 法阵：真 gsEcDrawField / gsEcFieldLock vs Java ----

    private static void fieldGrid(Context ctx, EcSub ec) throws Exception {
        // 法阵里辐条的角度依赖 gsMsNow()%4000 ⇒ 只比「三圈同心圆 + 中心两点」，
        // 辐条部分单独做结构断言（数量 + 半径）。
        int[][] cases = {{0, 0, 0}, {3, 64, -7}, {-100, 5, 100}};
        int bad = 0;
        int expect0 = 0;
        for (int[] c : cases) {
            String spec = c[0] + "|" + c[1] + "|" + c[2];
            Trace js = fromJs(ctx, "drawField", spec);
            Trace jv = new Trace();
            jv.world = mockWorld(jv, false, null, false);
            call(ec, "gsEcDrawField", new Class<?>[]{World.class, double.class, double.class, double.class},
                    jv.world, (double) c[0], (double) c[1], (double) c[2]);

            // ★ 辐条是 rr = 1.2 → 8.01 步长 1.0 ⇒ **7** 个点（1.2/2.2/…/7.2；8.2 超出 8.01）
            int ringPts = ringN(8.0, 0.8) + ringN(8.0 * 0.68, 0.7) + ringN(8.0 * 0.36, 0.6);
            int spokes = 8 * 7;
            int expect = ringPts + spokes + 2;
            expect0 = expect;
            boolean ok = js.ptc.size() == expect && jv.ptc.size() == expect;
            if (!ok) {
                System.out.println("   drawField[" + spec + "] 点数 JS=" + js.ptc.size()
                        + " Java=" + jv.ptc.size() + " 期望=" + expect
                        + "（圆环 " + ringPts + " + 辐条 " + spokes + " + 中心 2）");
            }
            if (ok) {
                for (int i = 0; i < ringPts; i++) {
                    if (!nearPt(js.ptc.get(i), jv.ptc.get(i))) {
                        ok = false;
                        System.out.println("   drawField 圆环第 " + i + " 点不一致");
                        break;
                    }
                }
            }
            if (ok) {
                for (int i = 0; i < 2; i++) {
                    int k = expect - 2 + i;
                    if (!nearPt(js.ptc.get(k), jv.ptc.get(k))) {
                        ok = false;
                        System.out.println("   drawField 中心第 " + i + " 点不一致");
                        break;
                    }
                }
            }
            // 辐条：8 条 × 8 点，半径 1.2 → 8.2，步长 1.0
            if (ok) {
                for (int k = 0; k < 8; k++) {
                    for (int s = 0; s < 7; s++) {
                        double[] q = jv.ptc.get(ringPts + k * 7 + s);
                        double rr = 1.2 + s * 1.0;
                        double dx = q[0] - c[0];
                        double dz = q[2] - c[2];
                        if (!near(Math.sqrt(dx * dx + dz * dz), rr) || !near(q[1], c[1] + 0.15)) {
                            ok = false;
                            System.out.println("   drawField 辐条半径不对 k=" + k + " s=" + s);
                            break;
                        }
                    }
                }
            }
            if (!ok) {
                bad++;
            }
        }
        check("法阵：3 圈同心圆 + 8 辐条×8 点 + 中心 2 点（" + cases.length + " 组）", bad == 0);

        // 阵内定身：真 gsEcFieldLock vs Java（800ms，不画柱 ⇒ col = null）
        String[] fl = {"0|0|0|0|0|1", "0|0|0|0|0|1"};
        String[] flc = {"e1|0|0|0|ZOMBIE|0", "e1|7.9|0|0|ZOMBIE|0;e2|8.1|0|0|ZOMBIE|0"};
        int bad2 = 0;
        for (int i = 0; i < fl.length; i++) {
            String spec = fl[i] + ";" + flc[i];
            Value js = callJs(ctx, "fieldLock", spec);
            Trace jt = new Trace();
            Spec s = Spec.parse(spec, jt);
            Player p = mockPlayer(jt, s.px, s.py, s.pz, s.dx, s.dy, s.dz);
            resetEcState(ec);
            clearLocks(CORE);
            call(ec, "gsEcFieldLock",
                    new Class<?>[]{Player.class, World.class, double.class, double.class, double.class},
                    p, jt.world, s.px, s.py, s.pz);
            List<String> jl = lockSig(CORE);
            List<String> jsl = new ArrayList<>();
            Value jslv = js.getMember("locks");
            for (long k = 0; k < jslv.getArraySize(); k++) {
                jsl.add(jslv.getArrayElement(k).asString());
            }
            if (!jl.equals(jsl)) {
                System.out.println("   fieldLock[" + spec + "] JS=" + jsl + " Java=" + jl);
                bad2++;
            }
            if (!locksAll(CORE, 800L)) {
                System.out.println("   fieldLock[" + spec + "] Java 定身时长不是 800ms");
                bad2++;
            }
            int jsMs = js.getMember("ms").asInt();
            if (jsMs != 800) {
                System.out.println("   fieldLock[" + spec + "] JS 定身时长 = " + jsMs + "（期望 800）");
                bad2++;
            }
        }
        check("法阵定身：半径 8 圆形判定 + 800ms（两边同值）+ 不画柱（2 组）", bad2 == 0);
    }

    // ---- 斜劈粒子：真 gsMsSlashFx vs MsSub.gsMsSlashFx ----

    private static void slashGrid(Context ctx, MsSub ms) throws Exception {
        // 参数：locX|locY|locZ|fwx|fwz|rtx|rtz|side
        String[] cases = {
                "0|0|0|0|1|1|0|0",
                "0|0|0|0|1|1|0|1",
                "3|64|-2|0.6|0.8|-0.8|0.6|0",
                "3|64|-2|0.6|0.8|-0.8|0.6|1",
                "3|64|-2|1|0|0|1|1",
        };
        int bad = 0;
        for (String spec : cases) {
            Trace js = fromJs(ctx, "slash", spec);
            Trace jv = new Trace();
            jv.world = mockWorld(jv, false, null, false);
            String[] h = spec.split("\\|");
            double[] d = new double[h.length];
            for (int i = 0; i < h.length; i++) {
                d[i] = Double.parseDouble(h[i]);
            }
            call(ms, "gsMsSlashFx",
                    new Class<?>[]{World.class, Location.class, double.class, double.class,
                            double.class, double.class, int.class},
                    jv.world, new Location(jv.world, d[0], d[1], d[2]),
                    d[3], d[4], d[5], d[6], (int) d[7]);
            if (!sameTrace("slash[" + spec + "]", js, jv)) {
                bad++;
            }
        }
        check("斜劈粒子：14 点 + f/s/h 三条插值 + 左右交替（" + cases.length + " 组）", bad == 0);

        // 粒子条数 = n+1 = 15
        int jsN = fromJs(ctx, "slash", cases[0]).ptc.size();
        check("斜劈粒子共 15 个点（i=0..14）", jsN == 15);
    }

    // ---- 定身间隔：真 gsEcLockOnce vs EcSub.gsEcLockOnce ----

    private static void lockGrid(Context ctx, EcSub ec) throws Exception {
        String id = "11111111-2222-3333-4444-555555555555";
        long[][] nows = {
                {1000},
                {1000, 2000, 3999, 4000, 4001},
                {0, 3000, 6000, 9000},
                {500000, 500100, 503000},
        };
        int bad = 0;
        for (long[] ns : nows) {
            StringBuilder arr = new StringBuilder("[");
            for (int i = 0; i < ns.length; i++) {
                if (i > 0) {
                    arr.append(',');
                }
                arr.append(ns[i]);
            }
            arr.append(']');
            String js = ctx.eval("js", "__lockOnce('" + id + "'," + arr + ")").asString();

            resetEcState(ec);
            clearLocks(CORE);
            StringBuilder jv = new StringBuilder();
            for (int i = 0; i < ns.length; i++) {
                if (i > 0) {
                    jv.append(',');
                }
                Entity ent = mockEnt(new Trace(), id, 0, 0, 0, "ZOMBIE", false);
                Object r = call(ec, "gsEcLockOnce", new Class<?>[]{Entity.class, long.class}, ent, ns[i]);
                jv.append(Boolean.TRUE.equals(r) ? 'T' : 'F');
            }
            if (!js.equals(jv.toString())) {
                System.out.println("   lockOnce nows=" + arr + " JS=" + js + " Java=" + jv);
                bad++;
            }
        }
        check("蚀月轮定身间隔 3000ms（4 组时刻序列，含边界 3999/4000）", bad == 0);

        // 定身时长 = GS_EC_W_LOCK = 600
        resetEcState(ec);
        clearLocks(CORE);
        Entity ent = mockEnt(new Trace(), id, 0, 0, 0, "ZOMBIE", false);
        call(ec, "gsEcLockOnce", new Class<?>[]{Entity.class, long.class}, ent, 12345L);
        check("蚀月轮定身时长 = 600ms 且 col = null（只定身、不画柱）",
                locksAll(CORE, 600L) && lockColsNull(CORE));
    }

    // ---- 天降圆柱层高 + 安全阀 ----

    private static void skyGrid(Context ctx, EcSub ec) throws Exception {
        double[] jv = (double[]) callStatic(EcSub.class, "gsEcSkyHeights");
        // ★ 用**数值**比，不能用字符串比：JS 的 Number→String 把 16.0 写成 "16"，
        //   Java 的 Double.toString 写成 "16.0"（移植契约里那条 JsNum 的坑）。
        Value jsv = ctx.eval("js", "__skyHeights()");
        int jsn = (int) jsv.getArraySize();
        boolean ok = jsn == jv.length;
        if (ok) {
            for (int i = 0; i < jv.length; i++) {
                if (!near(jsv.getArrayElement(i).asDouble(), jv[i])) {
                    System.out.println("   skyHeights[" + i + "] JS=" + jsv.getArrayElement(i).asDouble()
                            + " Java=" + jv[i]);
                    ok = false;
                    break;
                }
            }
        } else {
            System.out.println("   skyHeights 层数 JS=" + jsn + " Java=" + jv.length);
        }
        check("天降圆柱层高序列（16.0 → -0.2，步长 -0.7，共 " + jv.length + " 层，逐值比对）", ok);
        check("天降圆柱层数 = 24 层", jv.length == 24);

        // 安全阀：n++ 之后才 break ⇒ 实际最多处理 GS_EC_J_MAX_TARGETS+1 = 61 个
        check("★ 安全阀是「n++ 之后才 break」⇒ 实际最多 61 个目标（照抄，别修）",
                ecRegionText().contains("if (n > GS_EC_J_MAX_TARGETS) break;"));
    }

    // ======================================================================
    // [D] 反向断言
    // ======================================================================

    private static void reverse(Context ctx, String msRegion, String ecRegion) throws Exception {
        System.out.println();
        System.out.println("[D] 反向断言：钉住「不该存在的东西」与两处已修缺陷");

        String msSrc = readDeliverable("MsSub.java");
        String ecSrc = readDeliverable("EcSub.java");

        // ---- ① ★★ 层数权威在内存（PDC 全炸也照样记账）----
        memoryAuthoritative();

        // ---- ② ★ 世代号读不到时返回 null（心跳不许把自己当旧实例停掉）----
        genNullSafe(ctx);

        // ---- ③ 蚀月子系统不注册 runRepeating（心跳由月镰驱动）----
        check("★ 原脚本 gsEcStart 里没有 runRepeating",
                !sub(ecRegion, "function gsEcStart()", "gsEcStart();").contains("runRepeating"));
        check("★ Java EcSub 里没有注册心跳（只在类注释里说明）",
                !ecSrc.contains("GS.runRepeating(") && !ecSrc.contains("runRepeating("));
        check("★ Java MsSub 用 GS.runRepeating(this::gsMsPulse, 20, 2)"
                        + "（delay=20 / period=2，两个参数不同 —— 照抄，别当成 20/20）",
                msSrc.contains("GS.runRepeating(this::gsMsPulse, 20, 2)"));

        // ---- ④ 死代码 GS_EC_PET / GS_EC_PE 只写不读 ----
        // ★ 注意 GS_EC_PE 是 GS_EC_PET 的前缀，统计必须用词边界
        int petAll = countRe(ecRegion, "GS_EC_PET\\b");
        int peAll = countRe(ecRegion, "GS_EC_PE\\b");
        check("★ 原脚本 GS_EC_PET 只出现 3 次（声明 + 赋值 + 兜底），没有读取 ⇒ 纯死代码", petAll == 3);
        check("★ 原脚本 GS_EC_PE 只出现 3 次（声明 + 赋值 + 兜底），没有读取 ⇒ 纯死代码", peAll == 3);
        check("★ 原脚本里没有任何 `GS_EC_PET.` / `GS_EC_PE(` 的**使用**（只写不读）",
                !ecRegion.contains("GS_EC_PET.") && !ecRegion.contains("GS_EC_PE("));
        check("★ Java 版没有移植这两个死变量（没有字段声明、没有赋值）",
                !ecSrc.contains("GS_EC_PET =") && !ecSrc.contains("GS_EC_PE ="));
        check("★ 审判不用漂浮药效：Java EcSub 里没有任何药效**调用**",
                !ecSrc.contains("new PotionEffect") && !ecSrc.contains("addPotionEffect")
                        && !ecSrc.contains("setPotionEffect") && !ecSrc.contains("PotionEffectType."));
        check("★ 原脚本 EC 段里也没有真的 new 过 PotionEffect",
                !ecRegion.contains("new GS_EC_PE") && !ecRegion.contains("GS_EC_PE("));

        // ---- ⑤ 已删函数不许复活（注释里提到名字可以，**定义**不行）----
        check("★ gsEcLockView 已删除且没有复活（JS 里没有函数定义）",
                !ecRegion.contains("function gsEcLockView"));
        check("★ gsEcInvulnUntilLand 已删除且没有复活（JS 里没有函数定义）",
                !ecRegion.contains("function gsEcInvulnUntilLand"));
        check("★ Java EcSub 里同样没有这两个函数（连名字都不带括号出现）",
                !ecSrc.contains("gsEcLockView(") && !ecSrc.contains("gsEcInvulnUntilLand("));
        check("★ Java EcSub 里没有 setPotionEffect / addPotionEffect",
                !ecSrc.contains("addPotionEffect") && !ecSrc.contains("setPotionEffect"));

        // ---- ⑥ 交互处理体**不检查** isCancelled（脚本故意如此）----
        String msInter = bodyOf(msRegion, "reg(InterEv, function (e) {");
        String ecInter = bodyOf(ecRegion, "reg(InterEv, function (e) {");
        check("★ 原脚本 MS 交互处理体不检查 e.isCancelled()"
                        + "（脚本 6730 行有注释解释：检查了就变成「必须点方块才放得出来」）",
                !msInter.isEmpty() && !msInter.contains("if (e.isCancelled())"));
        check("★ 原脚本 EC 交互处理体不检查 e.isCancelled()",
                !ecInter.isEmpty() && !ecInter.contains("if (e.isCancelled())"));
        check("★ Java MsSub 交互处理体不检查 isCancelled()", !msSrc.contains("if (e.isCancelled())"));
        check("★ Java EcSub 交互处理体不检查 isCancelled()", !ecSrc.contains("if (e.isCancelled())"));
        check("★ Java 两处 @EventHandler 都没写 ignoreCancelled = true（JS 无第 5 参 ⇒ 默认 false）",
                !msSrc.contains("ignoreCancelled = true") && !ecSrc.contains("ignoreCancelled = true"));

        // ---- ⑦ 注册优先级：原脚本用**一个** reg 辅助函数注册 4 个事件 ----
        check("★ 原脚本 MS 段里 Priority.HIGHEST 出现 2 次（reg 的正常路 + JavaAdapter 兜底路）",
                countOf(msRegion, "Priority.HIGHEST") == 2);
        check("★ 原脚本 EC 段里 Priority.HIGHEST 出现 2 次", countOf(ecRegion, "Priority.HIGHEST") == 2);
        check("★ 原脚本 MS 的 reg() 被调用 4 次（定义 1 次 + 调用 4 次 = 5）",
                countOf(msRegion, "reg(") == 5);
        check("★ 原脚本 EC 的 reg() 被调用 4 次", countOf(ecRegion, "reg(") == 5);
        check("★ Java MsSub 的 4 个事件处理体全是 HIGHEST",
                countOf(msSrc, "EventPriority.HIGHEST") == 4);
        check("★ Java EcSub 的 4 个事件处理体全是 HIGHEST",
                countOf(ecSrc, "EventPriority.HIGHEST") == 4);
        check("★ Java 两处都没有 NORMAL/LOW/LOWEST/MONITOR 之类其它优先级",
                !msSrc.contains("EventPriority.NORMAL") && !msSrc.contains("EventPriority.MONITOR")
                        && !msSrc.contains("EventPriority.LOW") && !msSrc.contains("EventPriority.LOWEST")
                        && !ecSrc.contains("EventPriority.NORMAL") && !ecSrc.contains("EventPriority.MONITOR")
                        && !ecSrc.contains("EventPriority.LOW") && !ecSrc.contains("EventPriority.LOWEST"));

        // ---- ⑧ 4 个事件类都真的被注册（Java 侧用注解存在性证明）----
        Class<?> msSync = Class.forName("com.qinghuacisj.gs.script.module.listener.MsSub$Sync");
        Class<?> ecSync = Class.forName("com.qinghuacisj.gs.script.module.listener.EcSub$Sync");
        String ann = "@EventHandler(priority = EventPriority.HIGHEST)";
        check("★ Java MsSub$Sync 有 4 个 @EventHandler(priority = EventPriority.HIGHEST)"
                        + "（Sync.onInteract 方法 " + countOf(msSync, "onInteract") + " 个）",
                countOf(msSync, "onInteract") == 1 && countOf(msSrc, ann) == 4);
        check("★ Java EcSub$Sync 有 4 个 @EventHandler(priority = EventPriority.HIGHEST)",
                countOf(ecSrc, ann) == 4);
        check("★ Java 两个 Sync 都 implements Listener",
                org.bukkit.event.Listener.class.isAssignableFrom(msSync)
                        && org.bukkit.event.Listener.class.isAssignableFrom(ecSync));

        // ---- ⑨ 注册幂等（原脚本 reload 会累积，Java 版只注册一次）----
        check("★ Java 用 GS_MS_REGISTERED 闩锁做幂等注册",
                msSrc.contains("GS_MS_REGISTERED") && msSrc.contains("registerEvents"));
        check("★ Java 用 GS_EC_REGISTERED 闩锁做幂等注册",
                ecSrc.contains("GS_EC_REGISTERED") && ecSrc.contains("registerEvents"));

        // ---- ⑩ 契约红线：不许 System.out.println ----
        check("★ Java MsSub 里没有 System.out.println", !msSrc.contains("System.out.println"));
        check("★ Java EcSub 里没有 System.out.println", !ecSrc.contains("System.out.println"));

        // ---- ⑪ 蚀月的 4 个事件类与 MS 一致（同一套注册集合）----
        for (String ev : new String[]{"PlayerInteractEvent", "PlayerAnimationEvent",
                "BlockBreakEvent", "BlockDamageEvent"}) {
            check("★ EC 注册了 " + ev, ecSrc.contains(ev));
            check("★ MS 注册了 " + ev, msSrc.contains(ev));
        }

        // ---- ⑪b ★ 唯一的结构性差异：NO_BREAK 的开关从「注册时」挪进「处理体第一行」----
        check("★ 原脚本 MS 在注册时判 GS_MS_NO_BREAK（6790 行）",
                msRegion.contains("if (GS_MS_NO_BREAK) {")
                        && msRegion.contains("reg(BreakEv,") && msRegion.contains("reg(DmgBlkEv,"));
        check("★ 原脚本 EC 在注册时判 GS_EC_NO_BREAK（7729 行）",
                ecRegion.contains("if (GS_EC_NO_BREAK) {")
                        && ecRegion.contains("reg(BreakEv,") && ecRegion.contains("reg(DmgBlkEv,"));
        check("★ Java MsSub 把开关挪进了两个处理体的第一行（2 处「原脚本的注册期开关」）",
                countOf(msSrc, "← 原脚本的注册期开关") == 2);
        check("★ Java EcSub 把开关挪进了两个处理体的第一行（2 处「原脚本的注册期开关」）",
                countOf(ecSrc, "← 原脚本的注册期开关") == 2);
        check("★ 原脚本的 NO_BREAK 常量恒为 true（⇒ 这个差异不改变任何可观察行为）",
                bool(ctx.getBindings("js"), "GS_MS_NO_BREAK")
                        && bool(ctx.getBindings("js"), "GS_EC_NO_BREAK"));

        // ---- ⑫ ★ Paper 的 Item 自己声明了 getHealth()（MS 那条 getHealth 探针挡不住掉落物）----
        boolean itemHasHealth = false;
        try {
            org.bukkit.entity.Item.class.getMethod("getHealth");
            itemHasHealth = true;
        } catch (NoSuchMethodException ignored) {
            // 没有
        }
        check("★ org.bukkit.entity.Item 确实声明了 getHealth()（⇒ MS 的探针挡不住掉落物，"
                + "必须保留这条已知缺陷；EC 用 gsIsCombatTarget 挡住了）", itemHasHealth);
        check("★ 而且 Item **不是** Damageable（所以不能用 instanceof 判）",
                !Damageable.class.isAssignableFrom(org.bukkit.entity.Item.class));
        check("★ MsSub 因此必须用反射探 getHealth（不能 instanceof Damageable）",
                msSrc.contains("getMethod(\"getHealth\")"));
        check("★ EcSub 的 gsEcCanHit 里有 gsIsCombatTarget 这道闸",
                ecSrc.contains("gsIsCombatTarget"));

        // ---- ⑬ MS/EC 的伤害器逐字同一套（都走 ListenerCore.gsMsHurt）----
        check("★ 原脚本 gsMsHurt 与 gsEcHurtNoKb 的函数体逐字相同",
                bodyOf(msRegion, "function gsMsHurt(ent, dmg, p) {").equals(
                        bodyOf(ecRegion, "function gsEcHurtNoKb(ent, dmg, p) {")));
        check("★ Java EcSub 的 gsEcHurtNoKb 直接委托 ListenerCore.gsMsHurt",
                ecSrc.contains("core.gsMsHurt(ent, dmg, p)"));

        // ---- ⑭ 标题只喂纯字符串（不许把 Component 塞进 setTitle）----
        check("★ Java 两处血条标题都经过 gsMsTitle2 变成纯字符串",
                msSrc.contains("gsMsTitle2(") && ecSrc.contains("gsMsTitle2("));
        check("★ Java EcSub 里没有把 Component / Adventure 塞给 setTitle（无 import、无 Component.xxx）",
                !ecSrc.contains("net.kyori") && !ecSrc.contains("Component.") && !ecSrc.contains("import net."));
        check("★ Java MsSub 里没有把 Component / Adventure 塞给 setTitle（无 import、无 Component.xxx）",
                !msSrc.contains("net.kyori") && !msSrc.contains("Component.") && !msSrc.contains("import net."));

        // ---- ⑮ 蚀月轮「停下后才伤害」：飞行路上只画环、不打人 ----
        check("★ 原脚本 gsEcWheel 的 step 里只有 touch 判定，没有 gsEcHurtNoKb",
                !sub(ecRegion, "function step() {", "runLater(step, 1);").contains("gsEcHurtNoKb"));
        check("★ Java EcSub 的飞行 Runnable 里同样没有 gsEcHurtNoKb",
                !sub(ecSrc, "Runnable step = new Runnable() {", "GS.runLater(step, 1);")
                        .contains("gsEcHurtNoKb"));

        // ---- ⑯ 蚀月轮飞行方向是完整三维（抬头能打上天）----
        check("★ 原脚本 gsEcWheel 用 dir.getY() 参与方向（完整三维）",
                sub(ecRegion, "function gsEcWheel(p) {", "function gsEcHold(").contains("dir.getY()"));
        check("★ Java EcSub 同样用了 dir.getY()",
                sub(ecSrc, "void gsEcWheel(Player p) {", "Runnable step").contains("dir.getY()"));

        // ---- ⑰ 普通左键只叠层、不额外造成伤害 ----
        check("★ 原脚本 gsEcMelee 里没有任何 gsEcHurtNoKb / damage 调用",
                !sub(ecRegion, "function gsEcMelee(p) {", "// ---------- 蚀月轮")
                        .contains("gsEcHurtNoKb"));
        check("★ Java EcSub 的 gsEcMelee 里同样没有 gsEcHurtNoKb",
                !sub(ecSrc, "int gsEcMelee(Player p) {", "void gsEcWheel(Player p) {")
                        .contains("gsEcHurtNoKb"));

        // ---- ⑱ 审判：先蓄力 1.8 秒才出伤 + 无敌 1.8 秒 ----
        String judge = sub(ecRegion, "function gsEcJudge(p) {", "// ---------- 去抖");
        check("★ 原脚本审判：gsEcField 在 gsEcSmite 之前（蓄力）",
                judge.indexOf("gsEcField(") < judge.indexOf("gsEcSmite("));
        check("★ 原脚本审判：出伤延迟 GS_EC_J_TICKS、无敌解除延迟 GS_EC_J_INV",
                judge.contains("}, GS_EC_J_TICKS);") && judge.contains("}, GS_EC_J_INV);"));
        String jJudge = sub(ecSrc, "boolean gsEcJudge(Player p) {", "// ======");
        check("★ Java 审判：gsEcField 在 gsEcSmite 之前",
                jJudge.indexOf("gsEcField(") < jJudge.indexOf("gsEcSmite("));
        check("★ Java 审判：两个 runLater 延迟分别是 GS_EC_J_TICKS / GS_EC_J_INV",
                jJudge.contains("}, GS_EC_J_TICKS);") && jJudge.contains("}, GS_EC_J_INV);"));

        // ---- ⑲ 契约要求：原脚本里没有 gsEc* 的 runRepeating 心跳 ----
        check("★ 整个 EC 段里没有 runRepeating", !ecRegion.contains("runRepeating"));
        check("★ 整个 MS 段里恰好有 1 处 runRepeating", countOf(msRegion, "runRepeating") == 1);

        // ---- ⑳ 结构化差异已写在类注释里 ----
        check("★ Java MsSub 类注释记录了「层数权威在内存」这条修复",
                msSrc.contains("权威"));
        check("★ Java EcSub 类注释记录了「心跳由月镰驱动」这条结构差异",
                ecSrc.contains("心跳由月镰驱动"));
        check("★ Java EcSub 类注释记录了「监听器改为幂等注册」",
                ecSrc.contains("幂等注册"));
        check("★ Java EcSub 类注释列出了死代码（GS_EC_PET / GS_EC_PE）",
                ecSrc.contains("死代码") && ecSrc.contains("GS_EC_PET"));
    }

    /** ★★ 层数权威在内存：把 PDC 换成「读写都抛异常」的桩，内存记账必须照常工作。 */
    private static void memoryAuthoritative() throws Exception {
        MsSub ms = new MsSub(CORE);
        EcSub ec = new EcSub(CORE);

        Trace t = new Trace();
        Player p = mockPlayerBoomPdc(t, 0, 0, 0, 0, 0, 1);

        // ---- 月镰 ----
        resetMsState(ms);
        call(ms, "gsMsSet", new Class<?>[]{Player.class, double.class, double.class, double.class},
                p, 5.0, 0.0, 0.0);
        Object g = call(ms, "gsMsGet", new Class<?>[]{Player.class}, p);
        int n = stN(g);
        check("★★ 月镰：PDC 读写全炸时写 5 层仍读回 5 层（内存权威）", n == 5);

        // 写 0 必须真的清掉内存
        call(ms, "gsMsSet", new Class<?>[]{Player.class, double.class, double.class, double.class},
                p, 0.0, 0.0, 0.0);
        check("★★ 月镰：写 0 层 ⇒ 内存条目被删（读回 0）",
                stN(call(ms, "gsMsGet", new Class<?>[]{Player.class}, p)) == 0);

        // 内存里没有、PDC 又炸 ⇒ 读回全 0（不许抛）
        resetMsState(ms);
        Object g2 = call(ms, "gsMsGet", new Class<?>[]{Player.class}, p);
        check("★★ 月镰：内存空 + PDC 炸 ⇒ 安全返回 0 层", stN(g2) == 0);

        // ---- 蚀月 ----
        resetEcState(ec);
        call(ec, "gsEcSet", new Class<?>[]{Player.class, double.class, double.class, double.class},
                p, 7.0, 0.0, 0.0);
        check("★★ 蚀月：PDC 读写全炸时写 7 层仍读回 7 层（内存权威）",
                stN(call(ec, "gsEcGet", new Class<?>[]{Player.class}, p)) == 7);
        call(ec, "gsEcSet", new Class<?>[]{Player.class, double.class, double.class, double.class},
                p, 0.0, 0.0, 0.0);
        check("★★ 蚀月：写 0 层 ⇒ 内存条目被删",
                stN(call(ec, "gsEcGet", new Class<?>[]{Player.class}, p)) == 0);

        // ---- 加层这条路在 PDC 炸掉时也必须照常叠 ----
        resetEcState(ec);
        for (int i = 0; i < 23; i++) {
            call(ec, "gsEcAdd", new Class<?>[]{Player.class}, p);
        }
        Object st = call(ec, "gsEcGet", new Class<?>[]{Player.class}, p);
        check("★★ 蚀月：PDC 全炸时连打 23 次 ⇒ 层数到 23 且 full=1（第 24 次不再涨）",
                stN(st) == 23 && stFull(st) == 1);
        call(ec, "gsEcAdd", new Class<?>[]{Player.class}, p);
        check("★★ 蚀月：满层后再打 ⇒ 层数仍是 23（只刷倒计时）",
                stN(call(ec, "gsEcGet", new Class<?>[]{Player.class}, p)) == 23);
    }

    /**
     * ★ 世代号读不到 ⇒ 返回 null（调用方据此<b>不许</b>把心跳停掉）。
     *
     * <h2>★ 这里为什么用「脚本侧对账 + Java 侧只验鲁棒性」的写法</h2>
     * Java 的 {@code gsMsGenNow()} 走 {@code Bukkit.getServer().getWorlds().get(0)}，
     * 而<b>没有服务器时 {@code Bukkit.getServer()} 就是 null</b>（实测：不抛、返回 null）
     * ⇒ Java 侧**根本拿不到世界**，{@code mockWorld} 挂不上去。
     * 所以：
     * <ul>
     *   <li>「PDC 读不到 ⇒ 0」这条分支在<b>真 JS 侧</b>对账（脚本桩里的 {@code getWorlds()} 可以换）。</li>
     *   <li>Java 侧只验「拿不到世界时返回 null 而不是抛异常 / 不是 0」这条鲁棒性契约。</li>
     * </ul>
     */
    private static void genNullSafe(Context ctx) throws Exception {
        MsSub ms = new MsSub(CORE);
        resetMsState(ms);

        // ---- Java：没有服务器 ⇒ 读不到世界 ⇒ null（不是 0、不抛）----
        Object g = call(ms, "gsMsGenNow");
        check("★ Java gsMsGenNow：拿不到世界（无服务器）⇒ 返回 null（不是 0、不抛）", g == null);

        // ---- Java：读不到也要能 +1（不许因为 null 就把自己当旧实例）----
        setField(ms, "GS_MS_GEN", -1);
        call(ms, "gsMsGenBump");
        Object gen = get(ms, "GS_MS_GEN");
        check("★ Java gsMsGenBump：读不到世代号 ⇒ 从 0 起算、变成 1（不抛）",
                gen != null && ((Number) gen).intValue() == 1);

        // ---- 真 JS：脚本自己的同一条分支 ----
        // ★ 顺带钉住「桥接桩必须给 List 语义的 getWorlds()」——脚本写的是
        //   getServer().getWorlds().get(0)，桩给 JS 数组的话这里会 TypeError。
        check("★ 桩里的 getWorlds() 是 List 语义（.get(0) 可用），且 GS_MS_PDT / GS_MS_K_GEN 都拿到了",
                ctx.eval("js", "getServer().getWorlds().get(0) === __genWorld").asBoolean()
                        && ctx.eval("js", "GS_MS_PDT !== null").asBoolean()
                        && ctx.eval("js", "GS_MS_K_GEN !== null").asBoolean());
        String jsEmpty = ctx.eval("js", "(function(){"
                + "__genWorldPdc = __mkPdc({});"
                + "var v = gsMsGenNow();"
                + "return (v === null) ? 'null' : ('' + v);})()").asString();
        check("★ 真 JS gsMsGenNow：世界 PDC 里没写过 ⇒ 返回 0（不是 null），实得 [" + jsEmpty + "]",
                "0".equals(jsEmpty));

        String jsBoom = ctx.eval("js", "(function(){"
                + "__genWorldPdc = { get: function(){ throw new Error('boom'); }, set: function(){} };"
                + "var v = gsMsGenNow();"
                + "return (v === null) ? 'null' : ('' + v);})()").asString();
        check("★ 真 JS gsMsGenNow：世界 PDC 炸掉 ⇒ 返回 null（读不到 ≠ 0），实得 [" + jsBoom + "]",
                "null".equals(jsBoom));

        String jsBump = ctx.eval("js", "(function(){"
                + "GS_MS_GEN = -1;"
                + "__genWorldPdc = { get: function(){ throw new Error('boom'); }, set: function(){} };"
                + "gsMsGenBump();"
                + "return '' + GS_MS_GEN;})()").asString();
        check("★ 真 JS gsMsGenBump：读不到世代号 ⇒ 从 0 起算、变成 1", "1".equals(jsBump));

        check("★ Java 与脚本同构：`(v == null) ? 0 : v` 这段三元表达式两边都在",
                readDeliverable("MsSub.java").contains("(v == null) ? 0 : v"));
    }

    // ======================================================================
    // Java 侧 mock（Proxy / 子类）
    // ======================================================================

    static final String SELF = "00000000-0000-0000-0000-0000000000aa";

    static final class Trace {
        World world;
        final List<double[]> ptc = new ArrayList<>();
        final List<Object[]> snd = new ArrayList<>();
        final List<Object[]> dmg = new ArrayList<>();
        final List<String> locks = new ArrayList<>();
        int layers;
    }

    /** 用例头 + 候选实体表。 */
    static final class Spec {
        double px, py, pz, dx, dy, dz;
        boolean solid;
        final List<Object[]> cands = new ArrayList<>();

        static Spec parse(String rawSpec, Trace t) {
            String spec = normSpec(rawSpec);
            Spec s = new Spec();
            String[] parts = spec.split(";");
            String[] h = parts[0].split("\\|");
            s.px = d(h[0]);
            s.py = d(h[1]);
            s.pz = d(h[2]);
            s.dx = d(h[3]);
            s.dy = d(h[4]);
            s.dz = d(h[5]);
            s.solid = h.length > 6 && "1".equals(h[6]);
            for (int i = 1; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    continue;
                }
                String[] f = parts[i].split("\\|");
                s.cands.add(new Object[]{f[0], d(f[1]), d(f[2]), d(f[3]), f[4], "1".equals(f[5])});
            }
            t.world = mockWorld(t, false, s.cands, s.solid);
            return s;
        }

        private static double d(String s) {
            return Double.parseDouble(s);
        }
    }

    /** 精确方向 / 精确坐标的 Location 子类（绕开 yaw/pitch 的 float 精度损失）。 */
    static final class FixLoc extends Location {
        private final Vector dir;

        FixLoc(World w, double x, double y, double z, Vector dir) {
            super(w, x, y, z);
            this.dir = dir;
        }

        @Override
        public Vector getDirection() {
            return dir;
        }
    }

    static PersistentDataContainer mockPdc(Map<String, Object> m, boolean boom) {
        return (PersistentDataContainer) Proxy.newProxyInstance(
                ListenerDSubParityProbe.class.getClassLoader(),
                new Class<?>[]{PersistentDataContainer.class},
                (pr, me, a) -> {
                    switch (me.getName()) {
                        case "get":
                            if (boom) {
                                throw new RuntimeException("probe: pdc.get 炸了");
                            }
                            return m.get(String.valueOf(a[0]));
                        case "set":
                            if (boom) {
                                throw new RuntimeException("probe: pdc.set 炸了");
                            }
                            m.put(String.valueOf(a[0]), a[2]);
                            return null;
                        case "remove":
                            m.remove(String.valueOf(a[0]));
                            return null;
                        case "has":
                            return m.containsKey(String.valueOf(a[0]));
                        case "isEmpty":
                            return m.isEmpty();
                        case "getKeys":
                            return m.keySet();
                        case "getAdapterContext":
                            return null;
                        case "copyTo":
                            return null;
                        case "equals":
                            return pr == a[0];
                        case "hashCode":
                            return System.identityHashCode(pr);
                        case "toString":
                            return "ProbePdc";
                        default:
                            return null;
                    }
                });
    }

    static World mockWorld(Trace t, boolean pdcBoom, List<Object[]> cands, boolean solid) {
        Map<String, Object> wpdc = new HashMap<>();
        return (World) Proxy.newProxyInstance(
                ListenerDSubParityProbe.class.getClassLoader(),
                new Class<?>[]{World.class},
                (pr, me, a) -> {
                    switch (me.getName()) {
                        case "getName":
                            return "world";
                        case "getPersistentDataContainer":
                            return mockPdc(wpdc, pdcBoom);
                        case "playSound":
                            if (a.length >= 4 && a[1] instanceof String) {
                                t.snd.add(new Object[]{a[1],
                                        ((Number) a[2]).doubleValue(), ((Number) a[3]).doubleValue()});
                            }
                            return null;
                        case "spawnParticle":
                            t.ptc.add(new double[]{((Number) a[1]).doubleValue(),
                                    ((Number) a[2]).doubleValue(), ((Number) a[3]).doubleValue()});
                            return null;
                        case "getNearbyEntities": {
                            Location l = (Location) a[0];
                            double rx = ((Number) a[1]).doubleValue();
                            double ry = ((Number) a[2]).doubleValue();
                            double rz = ((Number) a[3]).doubleValue();
                            List<Entity> out = new ArrayList<>();
                            if (cands != null) {
                                for (Object[] c : cands) {
                                    double cx = (Double) c[1];
                                    double cy = (Double) c[2];
                                    double cz = (Double) c[3];
                                    if (Math.abs(cx - l.getX()) <= rx
                                            && Math.abs(cy - l.getY()) <= ry
                                            && Math.abs(cz - l.getZ()) <= rz) {
                                        out.add(mockEnt(t, (String) c[0], cx, cy, cz,
                                                (String) c[4], (Boolean) c[5]));
                                    }
                                }
                            }
                            return out;
                        }
                        case "getBlockAt":
                            return mockBlock(solid);
                        case "dropItemNaturally":
                            return null;
                        case "getTime":
                        case "getFullTime":
                            return 0L;
                        case "equals":
                            return pr == a[0];
                        case "hashCode":
                            return System.identityHashCode(pr);
                        case "toString":
                            return "ProbeWorld";
                        default:
                            return null;
                    }
                });
    }

    /**
     * 方块 mock。
     *
     * <p>★ {@code Material} 是<b>枚举</b>，Proxy 造不出来；无服务器环境下
     * {@code Material.STONE} 也会 {@code ExceptionInInitializerError}。
     * 所以这里 {@code getType()} 返回 {@code null} ⇒ {@code isSolid()} 那条路
     * 永远走 false。**蚀月轮撞墙**那条分支因此只在 JS 侧验证
     * （见 {@code wheelGrid} 的「撞墙」一组），Java 侧的
     * {@code gsEcWheel} 本来也跑不起来（{@code GS.runLater} 要真调度器）。
     */
    static Object mockBlock(boolean solid) {
        return Proxy.newProxyInstance(
                ListenerDSubParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.Block.class},
                (pr, me, a) -> {
                    switch (me.getName()) {
                        case "getType":
                            return null;
                        case "getLocation":
                            return null;
                        case "equals":
                            return pr == a[0];
                        case "hashCode":
                            return System.identityHashCode(pr);
                        case "toString":
                            return "ProbeBlock";
                        default:
                            return null;
                    }
                });
    }

    /**
     * 实体 mock。
     *
     * <p>★ 类型 → 接口的映射是关键：{@code ITEM} 只给 {@code Damageable}
     * （有 {@code getHealth()}、但 {@code instanceof LivingEntity} 为 false），
     * 这才忠实复现「Paper 的 {@code Item} 自己声明了 getHealth()」那条已知缺陷。
     */
    static Entity mockEnt(Trace t, String id, double x, double y, double z, String type, boolean dead) {
        Class<?>[] ifaces;
        switch (type) {
            case "PLAYER":
                ifaces = new Class<?>[]{Player.class};
                break;
            case "ITEM":
                ifaces = new Class<?>[]{Damageable.class};
                break;
            default:
                ifaces = new Class<?>[]{LivingEntity.class};
                break;
        }
        boolean full = !"ITEM".equals(type);
        UUID uuid = UUID.fromString(id);
        return (Entity) Proxy.newProxyInstance(
                ListenerDSubParityProbe.class.getClassLoader(), ifaces,
                (pr, me, a) -> {
                    switch (me.getName()) {
                        case "getUniqueId":
                            return uuid;
                        case "getType":
                            return EntityType.valueOf(type);
                        case "getLocation":
                            return new Location(t.world, x, y, z);
                        case "getWorld":
                            return t.world;
                        case "isDead":
                            return dead;
                        case "isValid":
                            return !dead;
                        case "getHealth":
                            return 20.0;
                        case "getMaxHealth":
                            return 20.0;
                        case "setNoDamageTicks":
                        case "setAI":
                        case "setVelocity":
                        case "setInvulnerable":
                            return null;
                        case "getNoDamageTicks":
                            return 0;
                        case "getVelocity":
                            return new Vector(0, 0, 0);
                        case "hasAI":
                            return true;
                        case "damage":
                            if (!full) {
                                throw new UnsupportedOperationException("Item 没有 damage");
                            }
                            if (a.length == 2) {
                                t.dmg.add(new Object[]{id, ((Number) a[0]).doubleValue()});
                            } else {
                                t.dmg.add(new Object[]{id, ((Number) a[0]).doubleValue()});
                            }
                            return null;
                        case "equals":
                            return pr == a[0];
                        case "hashCode":
                            return System.identityHashCode(pr);
                        case "toString":
                            return "ProbeEnt[" + type + ":" + id + "]";
                        default:
                            return null;
                    }
                });
    }

    static Player mockPlayer(Trace t, double x, double y, double z, double dx, double dy, double dz) {
        return mockPlayer0(t, x, y, z, dx, dy, dz, false);
    }

    static Player mockPlayerBoomPdc(Trace t, double x, double y, double z, double dx, double dy, double dz) {
        return mockPlayer0(t, x, y, z, dx, dy, dz, true);
    }

    static Player mockPlayer0(Trace t, double x, double y, double z,
                              double dx, double dy, double dz, boolean pdcBoom) {
        Map<String, Object> pdc = new HashMap<>();
        UUID uuid = UUID.fromString(SELF);
        return (Player) Proxy.newProxyInstance(
                ListenerDSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (pr, me, a) -> {
                    switch (me.getName()) {
                        case "getUniqueId":
                            return uuid;
                        case "getName":
                            return "Probe";
                        case "getLocation":
                            return new FixLoc(t.world, x, y, z, new Vector(dx, dy, dz));
                        case "getEyeLocation":
                            return new FixLoc(t.world, x, y + 1.62, z, new Vector(dx, dy, dz));
                        case "getWorld":
                            return t.world;
                        case "getPersistentDataContainer":
                            return mockPdc(pdc, pdcBoom);
                        case "isOnline":
                            return true;
                        case "getHealth":
                            return 20.0;
                        case "getMaxHealth":
                            return 20.0;
                        case "getType":
                            return EntityType.PLAYER;
                        case "isDead":
                            return false;
                        case "isValid":
                            return true;
                        case "getVelocity":
                            return new Vector(0, 0, 0);
                        case "hasAI":
                            return true;
                        case "getNoDamageTicks":
                            return 0;
                        case "setInvulnerable":
                        case "setFallDistance":
                        case "setNoDamageTicks":
                        case "setVelocity":
                        case "setAI":
                        case "damage":
                            return null;
                        case "equals":
                            return pr == a[0];
                        case "hashCode":
                            return System.identityHashCode(pr);
                        case "toString":
                            return "ProbePlayer";
                        default:
                            return null;
                    }
                });
    }

    // ======================================================================
    // 反射 / 状态工具
    // ======================================================================

    static void resetMsState(MsSub ms) throws Exception {
        setField(ms, "GS_MS_SWING_AT", new HashMap<String, Long>());
        setField(ms, "GS_MS_CLICK_AT", new HashMap<String, Long>());
        setField(ms, "GS_MS_SIDE", new HashMap<String, Integer>());
        setField(ms, "GS_MS_ST", new HashMap<String, Object>());
        setField(ms, "GS_MS_BARS", ListenerCore.newBarTable());
        setField(ms, "GS_MS_GEN", -1);
    }

    static void resetEcState(EcSub ec) throws Exception {
        setField(ec, "GS_EC_FIRE_AT", new HashMap<String, Long>());
        setField(ec, "GS_EC_CLICK_AT", new HashMap<String, Long>());
        setField(ec, "GS_EC_LOCK_AT", new HashMap<String, Long>());
        setField(ec, "GS_EC_ST", new HashMap<String, Object>());
        setField(ec, "GS_EC_BARS", ListenerCore.newBarTable());
        setField(ec, "GS_EC_JUDGING", new HashMap<String, Boolean>());
    }

    @SuppressWarnings("unchecked")
    static void setSt(Object sub, String field, String uuid, int n, long until, int full) throws Exception {
        Map<String, Object> m = (Map<String, Object>) get(sub, field);
        Class<?> stCls = null;
        for (Class<?> c : (field.startsWith("GS_MS") ? MsSub.class : EcSub.class).getDeclaredClasses()) {
            if (c.getSimpleName().equals("St")) {
                stCls = c;
            }
        }
        if (stCls == null) {
            throw new IllegalStateException("找不到 St 内部类");
        }
        java.lang.reflect.Constructor<?> ctor = stCls.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object st = ctor.newInstance();
        setField(st, "n", n);
        setField(st, "until", until);
        setField(st, "full", full);
        m.put(uuid, st);
    }

    /** 读一个 package-private 的 static 常量（探针在默认包，不能直接引用）。 */
    static double fnum(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return ((Number) f.get(null)).doubleValue();
    }

    static int stN(Object st) throws Exception {
        return st == null ? 0 : ((Number) get(st, "n")).intValue();
    }

    static int stFull(Object st) throws Exception {
        return st == null ? 0 : ((Number) get(st, "full")).intValue();
    }

    /**
     * 定身表。
     *
     * <p>★ {@code ListenerCore.gsMsLocks} 是**实例字段**（{@code public final List<Lock>}），
     * 所以这里必须传 {@code ListenerCore} 实例，不能 {@code f.get(null)}。
     */
    @SuppressWarnings("unchecked")
    static List<Object> locks(ListenerCore core) throws Exception {
        Field f = ListenerCore.class.getDeclaredField("gsMsLocks");
        f.setAccessible(true);
        return (List<Object>) f.get(core);
    }

    static void clearLocks(ListenerCore core) throws Exception {
        locks(core).clear();
    }

    static List<String> lockSig(ListenerCore core) throws Exception {
        List<String> out = new ArrayList<>();
        for (Object o : locks(core)) {
            out.add(String.valueOf(get(o, "id")) + "|"
                    + (get(o, "col") == null ? "null" : "col"));
        }
        return out;
    }

    /** 所有定身记录的时长是否都是 ms（用当前时间反推）。 */
    static boolean locksAll(ListenerCore core, long ms) throws Exception {
        List<Object> l = locks(core);
        if (l.isEmpty()) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Object o : l) {
            long until = ((Number) get(o, "until")).longValue();
            long d = until - now;
            if (d < ms - 2000 || d > ms + 2000) {
                return false;
            }
        }
        return true;
    }

    static boolean lockColsNull(ListenerCore core) throws Exception {
        for (Object o : locks(core)) {
            if (get(o, "col") != null) {
                return false;
            }
        }
        return true;
    }

    static Object get(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static void setField(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }

    static Object call(Object o, String name, Class<?>[] sig, Object... a) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, sig);
        m.setAccessible(true);
        try {
            return m.invoke(o, a);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new RuntimeException("调用 " + name + " 抛错: " + e.getCause(), e.getCause());
        }
    }

    static Object call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(o);
    }

    static Object callStatic(Class<?> c, String name, Class<?>[] sig, Object... a) throws Exception {
        Method m = c.getDeclaredMethod(name, sig);
        m.setAccessible(true);
        return m.invoke(null, a);
    }

    static Object callStatic(Class<?> c, String name) throws Exception {
        Method m = c.getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(null);
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    static Value callJs(Context ctx, String fn, String spec) {
        Value v = ctx.getBindings("js").getMember("__" + fn);
        if (v == null || v.isNull()) {
            throw new IllegalStateException("探针 JS 里没有 __" + fn);
        }
        return v.execute(normSpec(spec));
    }

    static Trace fromJs(Context ctx, String fn, String spec) {
        Value v = callJs(ctx, fn, spec);
        Trace t = new Trace();
        Value ptc = v.getMember("ptc");
        if (ptc != null && !ptc.isNull()) {
            for (long i = 0; i < ptc.getArraySize(); i++) {
                Value e = ptc.getArrayElement(i);
                t.ptc.add(new double[]{e.getArrayElement(0).asDouble(),
                        e.getArrayElement(1).asDouble(), e.getArrayElement(2).asDouble()});
            }
        }
        Value snd = v.getMember("snd");
        if (snd != null && !snd.isNull()) {
            for (long i = 0; i < snd.getArraySize(); i++) {
                Value e = snd.getArrayElement(i);
                t.snd.add(new Object[]{e.getArrayElement(0).asString(),
                        e.getArrayElement(1).asDouble(), e.getArrayElement(2).asDouble()});
            }
        }
        Value dmg = v.getMember("dmg");
        if (dmg != null && !dmg.isNull()) {
            for (long i = 0; i < dmg.getArraySize(); i++) {
                Value e = dmg.getArrayElement(i);
                t.dmg.add(new Object[]{e.getArrayElement(0).asString(), e.getArrayElement(1).asDouble()});
            }
        }
        Value locks = v.getMember("locks");
        if (locks != null && !locks.isNull()) {
            for (long i = 0; i < locks.getArraySize(); i++) {
                t.locks.add(locks.getArrayElement(i).asString());
            }
        }
        Value lay = v.getMember("layers");
        if (lay != null && !lay.isNull()) {
            t.layers = lay.asInt();
        }
        return t;
    }

    static boolean sameTrace(String label, Trace js, Trace jv) {
        boolean ok = true;
        if (js.ptc.size() != jv.ptc.size()) {
            System.out.println("   " + label + " 粒子数 JS=" + js.ptc.size() + " Java=" + jv.ptc.size());
            ok = false;
        } else {
            for (int i = 0; i < js.ptc.size(); i++) {
                if (!nearPt(js.ptc.get(i), jv.ptc.get(i))) {
                    System.out.println("   " + label + " 粒子[" + i + "] JS=" + Arrays.toString(js.ptc.get(i))
                            + " Java=" + Arrays.toString(jv.ptc.get(i)));
                    ok = false;
                    break;
                }
            }
        }
        if (js.snd.size() != jv.snd.size()) {
            System.out.println("   " + label + " 音效数 JS=" + js.snd.size() + " Java=" + jv.snd.size());
            ok = false;
        } else {
            for (int i = 0; i < js.snd.size(); i++) {
                Object[] x = js.snd.get(i);
                Object[] y = jv.snd.get(i);
                if (!String.valueOf(x[0]).equals(String.valueOf(y[0]))
                        || !near((Double) x[1], (Double) y[1])
                        || !near((Double) x[2], (Double) y[2])) {
                    System.out.println("   " + label + " 音效[" + i + "] JS=" + Arrays.toString(x)
                            + " Java=" + Arrays.toString(y));
                    ok = false;
                    break;
                }
            }
        }
        if (js.dmg.size() != jv.dmg.size()) {
            System.out.println("   " + label + " 伤害数 JS=" + js.dmg.size() + " Java=" + jv.dmg.size());
            ok = false;
        } else {
            for (int i = 0; i < js.dmg.size(); i++) {
                Object[] x = js.dmg.get(i);
                Object[] y = jv.dmg.get(i);
                if (!String.valueOf(x[0]).equals(String.valueOf(y[0]))
                        || !near((Double) x[1], (Double) y[1])) {
                    System.out.println("   " + label + " 伤害[" + i + "] JS=" + Arrays.toString(x)
                            + " Java=" + Arrays.toString(y));
                    ok = false;
                    break;
                }
            }
        }
        if (js.locks.size() != jv.locks.size()) {
            System.out.println("   " + label + " 定身数 JS=" + js.locks.size() + " Java=" + jv.locks.size());
            ok = false;
        } else if (!js.locks.equals(jv.locks)) {
            System.out.println("   " + label + " 定身表 JS=" + js.locks + " Java=" + jv.locks);
            ok = false;
        }
        return ok;
    }

    static boolean nearPt(double[] a, double[] b) {
        return near(a[0], b[0]) && near(a[1], b[1]) && near(a[2], b[2]);
    }

    static boolean near(double a, double b) {
        if (a == b) {
            return true;
        }
        return Math.abs(a - b) <= 1e-9 * Math.max(1.0, Math.abs(a));
    }

    static int ringN(double r, double gap) {
        double g = (gap <= 0) ? 0.4 : gap;
        long n = Math.round((2.0 * Math.PI * r) / g);
        if (n < 8) {
            n = 8;
        }
        if (n > 200) {
            n = 200;
        }
        return (int) n;
    }

    static String join(String[] raw, int from, int to) {
        return String.join("\n", Arrays.copyOfRange(raw, from, to));
    }

    static int countOf(String hay, String needle) {
        int c = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            c++;
            i += needle.length();
        }
        return c;
    }

    /** 正则计数（词边界之类的场合用；{@code GS_EC_PE} 是 {@code GS_EC_PET} 的前缀，必须用这个）。 */
    static int countRe(String hay, String regex) {
        return (int) java.util.regex.Pattern.compile(regex).matcher(hay).results().count();
    }

    static int countOf(Class<?> c, String method) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(method)) {
                n++;
            }
        }
        return n;
    }

    /** 取 {@code from} 之后、{@code to} 之前的那一段（找不到就返回空串）。 */
    static String sub(String hay, String from, String to) {
        int i = hay.indexOf(from);
        if (i < 0) {
            return "";
        }
        int j = hay.indexOf(to, i + from.length());
        if (j < 0) {
            return hay.substring(i);
        }
        return hay.substring(i, j);
    }

    /**
     * 取一个函数的**函数体**（去掉首尾大括号），用来做「两份函数逐字相同」的断言。
     * 用大括号配平，忽略字符串字面量里的括号（原脚本里没有这种坑）。
     */
    static String bodyOf(String src, String header) {
        int i = src.indexOf(header);
        if (i < 0) {
            return "<没找到 " + header + ">";
        }
        int open = src.indexOf('{', i);
        if (open < 0) {
            return "<没有函数体>";
        }
        int depth = 0;
        for (int k = open; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(open + 1, k).replace("\r", "").trim();
                }
            }
        }
        return "<括号不配平>";
    }

    static String ecRegionText() {
        try {
            Path script = pick(
                    Path.of("..", "QHCGS", "GS", "GS", "scripts", "gs_listener.js"),
                    Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"),
                    Path.of("GS", "GS", "scripts", "gs_listener.js"));
            String[] raw = Files.readString(script, StandardCharsets.UTF_8).split("\n", -1);
            return join(raw, EC_FROM - 1, EC_TO);
        } catch (Throwable t) {
            return "";
        }
    }

    static String readDeliverable(String name) {
        try {
            Path p = pick(
                    Path.of("src", "main", "java", "com", "qinghuacisj", "gs", "script",
                            "module", "listener", name),
                    Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener", name));
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 把 spec 里的短实体 id 规范化成**规范 UUID 字符串**。
     *
     * <p>★ 为什么必须做：JS 侧 mock 的 {@code getUniqueId()} 直接返回 id 字符串，
     * Java 侧 mock 返回真 {@code UUID}（{@code String.valueOf} 出来是规范形式）
     * ⇒ 两边要对上，spec 里的 id 就得是规范 UUID。短 id（e1/e2/e3）在这里统一展开，
     * 于是所有调用点都不用改。
     */
    static String normSpec(String spec) {
        String[] parts = spec.split(";", -1);
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            sb.append(';');
            if (parts[i].isEmpty()) {
                continue;
            }
            String[] f = parts[i].split("\\|", -1);
            sb.append(uid(f[0]));
            for (int k = 1; k < f.length; k++) {
                sb.append('|').append(f[k]);
            }
        }
        return sb.toString();
    }

    /** 短 id → 规范 UUID 字符串（确定性，同一次运行里稳定）。 */
    static String uid(String id) {
        if (id.indexOf('-') >= 0) {
            return id;
        }
        int n = 0;
        for (int i = 0; i < id.length(); i++) {
            n = n * 31 + id.charAt(i);
        }
        return "00000000-0000-0000-0000-" + String.format("%012x", ((long) n) & 0xffffffffffffL);
    }

    static Path pick(Path... cands) {
        for (Path p : cands) {
            try {
                if (p != null && Files.exists(p)) {
                    return p;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    // ======================================================================
    // check / eq
    // ======================================================================

    static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  [OK] " + label);
        } else {
            fail++;
            System.out.println("  [XX] " + label);
        }
    }

    static void eq(String label, String expect, String actual) {
        if (expect == null) {
            check(label + "（期望值缺失）", false);
            return;
        }
        if (expect.equals(actual)) {
            check(label + "（" + expect.length() + " 字符，逐字节相等）", true);
        } else {
            fail++;
            System.out.println("  [XX] " + label);
            System.out.println("       期望(" + expect.length() + "): " + expect);
            System.out.println("       实得(" + (actual == null ? -1 : actual.length()) + "): " + actual);
            if (actual != null) {
                int k = 0;
                int m = Math.min(expect.length(), actual.length());
                while (k < m && expect.charAt(k) == actual.charAt(k)) {
                    k++;
                }
                System.out.println("       首个不同位置: " + k
                        + "  期望char=" + (k < expect.length() ? (int) expect.charAt(k) : -1)
                        + "  实得char=" + (k < actual.length() ? (int) actual.charAt(k) : -1));
            }
        }
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
