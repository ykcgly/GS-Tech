package com.qinghuacisj.gs.script.module.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * E 组四个子系统的对账探针（原脚本 {@code gs_listener.js} 第 7791~10950 行）。
 *
 * <pre>
 *   致万千无言者  gsVo*  / GS_VO_*    7791~8599   （入口 gsVoStart();   @8599）
 *   此刻万籁俱寂  gsSi*  / GS_SI_*    8601~9279   （入口 gsSiStart();   @9279）
 *   阎魔刀·改     gsEm*  / GS_EM_*    9281~10837  （入口 gsEmStart();   @10837）
 *   武器禁近战    gsWpn* / GS_WPN_*   10839~10950 （入口 gsWpnNoMeleeStart(); @10950）
 * </pre>
 *
 * <h2>四类断言</h2>
 * <ol>
 *   <li><b>常量对账</b>：用<b>真 GraalJS</b> 跑原脚本（前 10950 行），读出这四个子系统的
 *       顶层 {@code var}，与 {@link VoSub} / {@link SiSub} / {@link EmSub} /
 *       {@link WpnNoMeleeSub} 的字段<b>按名按值</b>比对。</li>
 *   <li><b>日志行逐字节</b>：真 JS 打出来的行 vs Java 的 {@code xxxLine()} vs
 *       {@code docs/参考服日志_构思技艺.txt}。</li>
 *   <li><b>数值 / 几何</b>：把原脚本的<b>真函数</b>用 mock 世界 / mock 玩家跑起来，
 *       在<b>大量输入</b>上与 Java 侧同一算式逐值比对（伤害公式 / 判定箱 / 六刀形状 /
 *       圆圈半径 / 裂缝生命周期 / 窗口清空语义 / 药效续期判定 …）。</li>
 *   <li><b>反向断言</b>：钉住「不该存在的东西」。</li>
 * </ol>
 *
 * <h2>★ 桥接桩（原脚本顶层有副作用）</h2>
 * {@code gs_listener.js} 顶层会注册监听器、跑 {@code runRepeating}、写日志。所以求值前先把
 * {@code getServer()} / {@code runLater} / {@code runRepeating} / {@code getAddonConfig()}
 * 换成可控实现（{@link #JS_STUBS}），并把会触发静态初始化失败的类用 JS 侧 shim 包一层
 * （{@link #JS_SHIM}）。
 *
 * <h2>★★ Java 侧怎么在没有服务器的情况下跑起来</h2>
 * 两个关键手段（都是实测过的）：
 * <ol>
 *   <li>{@code org.bukkit.Bukkit} 的私有静态字段 {@code server} 用反射塞进一个
 *       {@link Server} 代理 = {@code Bukkit.getServer()} / {@code Bukkit.getLogger()} /
 *       {@code Bukkit.createBossBar()} 全部可用
 *       （★ 不能用 {@code Bukkit.setServer()}：它内部会读 Paper 的
 *       {@code ServerBuildInfo} 构建清单，独立运行时抛 {@code NoSuchElementException}）。</li>
 *   <li>{@link Location} 的 {@code getDirection()} <b>不是 final</b> = 用
 *       {@link FakeLoc} 子类直接指定朝向（含非单位向量，用来钉住「先归一化」这一步）。</li>
 * </ol>
 *
 * <h2>★★ 时钟</h2>
 * 四个子系统一律走 {@code gsMsNow()}（原脚本 6004 行）= JS 侧整体换成可控 {@code __now}；
 * Java 侧走 {@code ListenerCore.now() = System.currentTimeMillis()}，
 * 所以 Java 侧的网格<b>只喂相对量</b>（{@code until = now ± Δ}、{@code *_AT = now − Δ}），
 * 两边比的是<b>结论</b>（层数 / 是否清空 / 血条标题 / 进度），不是绝对时间戳。
 */
public final class ListenerESubParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 我负责的区间（原脚本 1-based，含两端）。 */
    private static final int SUB_FROM = 7791;
    private static final int SUB_TO = 10950;

    private static final String SELF_ID = "00000000-0000-0000-0000-0000000000e1";

    /**
     * 真 JS 侧三个子系统启动时的世界世代号。
     *
     * <p>★ 为什么是常量 1：世代号存在<b>世界的 PDC</b> 里，探针里那个假世界的 PDC 一开始是空的，
     * 所以第一次 {@code gsXxGenBump()} 必然读到 0、写出 1。这一点由
     * {@link #constants} 里「假 PDC 里确实写着 1」的断言钉住 ——
     * 不是「假设它是 1」，是<b>验过</b>它是 1。
     */
    private static final int GEN = 1;

    private static final Path SCRIPT = pick(
            Path.of("../QHCGS/GS/GS/scripts/gs_listener.js"),
            Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"));

    private static final Path LOG = pick(
            Path.of("docs/参考服日志_构思技艺.txt"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt"));

    /** 拆分契约（§3 里引用了那几行日志，其中几行是**截断**过的）。 */
    private static final Path CONTRACT = pick(
            Path.of("docs/gs_listener_拆分契约.md"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/gs_listener_拆分契约.md"));

    private static final Path VO_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/VoSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/VoSub.java"));
    private static final Path SI_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/SiSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/SiSub.java"));
    private static final Path EM_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/EmSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/EmSub.java"));
    private static final Path WPN_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/WpnNoMeleeSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/WpnNoMeleeSub.java"));

    // ======================================================================
    // 桥接桩
    // ======================================================================

    private static final String JS_STUBS = """
            var __log = [];          // getServer().getLogger() 收到的每一行
            var __online = [];       // getOnlinePlayers() 的返回值（可注入）
            var __bars = [];         // createBossBar 造出来的血条

            // ★ 世界世代号用的假 PDC —— 必须在**前缀求值之前**就位，
            //   否则 gsVoGenBump() 读不到世代号，日志行里的「世界世代号」对不上。
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
            var __wpdc = __mkPdc({});
            var __genWorld = { getName: function () { return 'world'; },
                               getPersistentDataContainer: function () { return __wpdc; } };

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
              getWorlds: function () { return { get: function (i) { return __genWorld; } }; },
              createBossBar: function (title, color, style) {
                var b = {
                  __title: String(title), __prog: 1.0, __vis: false, __added: 0, __removed: 0,
                  setTitle: function (t) { b.__title = String(t); },
                  getTitle: function () { return b.__title; },
                  setProgress: function (v) { b.__prog = v; },
                  getProgress: function () { return b.__prog; },
                  setVisible: function (v) { b.__vis = v; },
                  isVisible: function () { return b.__vis; },
                  addPlayer: function (p) { b.__added++; },
                  removePlayer: function (p) { },
                  removeAll: function () { b.__removed++; },
                  setColor: function (c) { }, setStyle: function (s) { }
                };
                __bars.push(b);
                return b;
              }
            };
            function getServer() { return __srv; }
            function getAddonConfig() { return null; }
            // ★ runLater 默认**不执行**（避免顶层副作用）；对账「逝言」弹道时切到队列模式。
            var __laterMode = 0;
            var __q = [];
            function runLater(fn, d) {
              if (__laterMode === 1) { __q.push({ f: fn, d: (d === undefined ? 1 : d) }); }
              return null;
            }
            function runRepeating(fn, d, p) { return null; }
            """;

    /**
     * {@code Java.type} 垫片。
     *
     * <p>★ 无服务器环境下这些类会 {@code ExceptionInInitializerError}（不是能被 JS
     * {@code try/catch} 抓住的普通异常）= 必须换掉：{@code Particle}（注册表枚举）、
     * {@code PotionEffectType}（注册表）、{@code PotionEffect}（构造要真类型）、
     * {@code Location}（构造要真 World）。
     *
     * <p>★ 保留真的 {@code BarColor} / {@code BarStyle}（枚举，独立可初始化，实测 OK）
     * 与真的 {@code NamespacedKey} / {@code PersistentDataType}（实测 OK）
     * —— 这样「血条颜色 / 样式解析」与「世代号键」两条路径用的是<b>真类</b>，不是替身。
     */
    private static final String JS_SHIM = """
            var __realJava = Java;
            var Java = { type: function (n) {
              if (n === 'org.bukkit.Particle') {
                return { DUST: 'P:DUST', REDSTONE: 'P:REDSTONE', ENCHANT: 'P:ENCHANT',
                         ENCHANTMENT_TABLE: 'P:ENCHANTMENT_TABLE', ENCHANTING_TABLE: 'P:ENCHANTING_TABLE',
                         valueOf: function (x) { return 'P:' + x; } };
              }
              if (n === 'org.bukkit.Particle$DustOptions') {
                return function (c, s) { return { __doc: true, c: c, size: s }; };
              }
              if (n === 'org.bukkit.Color') {
                return { fromRGB: function (r, g, b) { return { __color: true, r: r, g: g, b: b }; } };
              }
              if (n === 'org.bukkit.potion.PotionEffectType') {
                return { RESISTANCE: 'E:RESISTANCE', DAMAGE_RESISTANCE: 'E:DAMAGE_RESISTANCE',
                         REGENERATION: 'E:REGENERATION', SPEED: 'E:SPEED',
                         NIGHT_VISION: 'E:NIGHT_VISION',
                         valueOf: function (x) { return 'E:' + x; } };
              }
              if (n === 'org.bukkit.potion.PotionEffect') {
                return function (t, d, a, amb, part) {
                  return { __pe: true, type: t, duration: d, amplifier: a };
                };
              }
              if (n === 'org.bukkit.Location') {
                return function (w, x, y, z) {
                  var o = { __loc: true, __id: __pts.length, x: x, y: y, z: z, w: w,
                    getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                    getWorld: function () { return w; }, getYaw: function () { return 0.0; },
                    getDirection: function () { return __JSVec(0, 0, 1); },
                    getBlock: function () {
                      return { getType: function () { return { isSolid: function () {
                        return __solidFn ? __solidFn(x, y, z) : false; } }; } };
                    }
                  };
                  __pts.push(o);
                  return o;
                };
              }
              if (n === 'org.bukkit.util.Vector') { return __JSVec; }
              if (n === 'org.bukkit.entity.LivingEntity') { return { __cls: 'LivingEntity' }; }
              return __realJava.type(n);
            } };
            """;

    /** mock 世界 / 玩家 / 实体 —— 全部是 JS 鸭子类型对象。 */
    private static final String JS_HELPERS = """
            var __pts = [];                                   // Location 垫片登记表（取 __id）
            function __JSVec(x, y, z) {
              return { getX: function () { return x; }, getY: function () { return y; },
                       getZ: function () { return z; }, length: function () {
                         return Math.sqrt(x * x + y * y + z * z); } };
            }
            var __snd = [], __ptc = [], __dmg = [], __boxes = [];
            var __cands = [], __curPtId = -1, __solid = false, __solidFn = null;
            var __tp = [];
            var __SELF = '00000000-0000-0000-0000-0000000000e1';
            var __now = 1000000;
            var __hand = null;
            var __world = null;
            var __inv = {
              getItemInMainHand: function () { return __hand; },
              setItemInMainHand: function () { },
              getContents: function () { return []; }
            };

            // ★ 物品判定开个口子：让「主手拿着本武器」这条路能在 mock 里走通
            function __setItem(id) {
              gsIsItem = function (item, want) { return item === __hand && item !== null && (id === null || id === want); };
            }

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

            function __mkEnt(c) {
              var e = {
                getUniqueId: function () { return c.id; },
                isDead: function () { return !!c.dead; },
                getType: function () { return { name: function () { return c.type; } }; },
                getLocation: function () {
                  return { getX: function () { return c.x; }, getY: function () { return c.y; },
                           getZ: function () { return c.z; } };
                },
                getVelocity: function () { return __JSVec(0, 0, 0); },
                setVelocity: function (v) { },
                setNoDamageTicks: function () { },
                damage: function (d, src) {
                  __dmg.push([c.id, d]);
                }
              };
              if (!c.noHealth) { e.getHealth = function () { return 20.0; }; }
              return e;
            }

            function __mkWorld() {
              return {
                playSound: function (loc, name, vol, pitch) {
                  // ★★ 真 RSC 桥接里 playSound 的签名是 (Location, String, **float**, **float**)
                  //    ⇒ JS 的 double 0.6 到 Java 那边变成 0.6f，再被读回来是 0.6000000238418579。
                  //    mock 里不做这一步就会拿 JS 的 double 去跟 Java 的 float 比，纯属自找差异。
                  __snd.push([String(name), Math.fround(vol), Math.fround(pitch)]);
                },
                spawnParticle: function (t, x, y, z) { __ptc.push([x, y, z]); },
                getPersistentDataContainer: function () { return __wpdc; },
                getNearbyEntities: function (pt, rx, ry, rz) {
                  __boxes.push([rx, ry, rz, pt.x, pt.y, pt.z]);
                  __curPtId = (pt && pt.__id !== undefined) ? pt.__id : -1;
                  var out = [];
                  for (var i = 0; i < __cands.length; i++) {
                    var c = __cands[i];
                    if (Math.abs(c.x - pt.x) <= rx && Math.abs(c.y - pt.y) <= ry && Math.abs(c.z - pt.z) <= rz) {
                      out.push(__mkEnt(c));
                    }
                  }
                  var k = 0;
                  return { iterator: function () {
                    return { hasNext: function () { return k < out.length; }, next: function () { return out[k++]; } };
                  } };
                }
              };
            }

            function __mkPlayer(x, y, z, dx, dy, dz, pdc) {
              var dir = __JSVec(dx, dy, dz);
              var loc = { __id: __pts.length, x: x, y: y, z: z, w: __world,
                getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                getWorld: function () { return __world; }, getYaw: function () { return 0.0; },
                getDirection: function () { return dir; } };
              __pts.push(loc);
              return {
                getUniqueId: function () { return __SELF; },
                isOnline: function () { return true; },
                getGameMode: function () { return { name: function () { return 'SURVIVAL'; } }; },
                getLocation: function () { return loc; },
                getEyeLocation: function () {
                  return { getX: function () { return x; }, getY: function () { return y + 1.62; },
                           getZ: function () { return z; }, getDirection: function () { return dir; },
                           getYaw: function () { return 0.0; } };
                },
                getWorld: function () { return __world; },
                getPersistentDataContainer: function () { return pdc; },
                getInventory: function () { return __inv; },
                getAllowFlight: function () { return false; },
                setAllowFlight: function (v) { },
                isFlying: function () { return false; },
                setFlying: function (v) { },
                getPotionEffect: function (t) { return __fxCur['' + t] || null; },
                teleport: function (l) { __tp.push(l); return true; },
                addPotionEffect: function (pe, force) {
                  __fx.push(['' + pe.type, pe.duration, pe.amplifier]);
                  __fxCur['' + pe.type] = {
                    getDuration: function () { return pe.duration; },
                    getAmplifier: function () { return pe.amplifier; }
                  };
                },
                removePotionEffect: function (t) { __fxRem.push('' + t); delete __fxCur['' + t]; }
              };
            }

            var __fx = [], __fxRem = [], __fxCur = {};
            gsMsNow = function () { return __now; };

            function __reset(cands) {
              __snd = []; __ptc = []; __dmg = []; __boxes = []; __cands = cands || [];
              __curPtId = -1; __solid = false;
              __world = __mkWorld();
            }

            function __mkSelf(x, y, z, dx, dy, dz) {
              return __mkPlayer(x, y, z, dx, dy, dz, __mkPdc({}));
            }

            // ============ 捕获入口（每个都自清记录） ============

            function __voSwing(a) {
              __reset(a.cands);
              GS_VO_ST = {}; GS_VO_SWING_AT = {}; GS_VO_BARS = {};
              if (a.n > 0) { GS_VO_ST[__SELF] = { n: a.n, until: __now + 1000 }; }
              var p = __mkSelf(a.sx, a.sy, a.sz, a.dx, a.dy, a.dz);
              gsVoSwing(p);
              var st = GS_VO_ST[__SELF];
              return { ptc: __ptc, snd: __snd, dmg: __dmg, boxes: __boxes,
                       n: st ? st.n : 0, lastBoxPt: __curPtId };
            }

            function __siSwing(a) {
              __reset(a.cands);
              GS_SI_ST = {}; GS_SI_SWING_AT = {}; GS_SI_SIDE = {}; GS_SI_BARS = {};
              if (a.n > 0) { GS_SI_ST[__SELF] = { n: a.n, until: __now + 1000 }; }
              var p = __mkSelf(a.sx, a.sy, a.sz, a.dx, a.dy, a.dz);
              gsSiSwing(p);
              var st = GS_SI_ST[__SELF];
              return { ptc: __ptc, snd: __snd, dmg: __dmg, boxes: __boxes,
                       n: st ? st.n : 0, side: GS_SI_SIDE[__SELF] };
            }

            function __siBurst(a) {
              __reset(a.cands);
              GS_SI_ST = {}; GS_SI_BURST_AT = {}; GS_SI_BURST_FX = [];
              if (a.n > 0) { GS_SI_ST[__SELF] = { n: a.n, until: __now + 1000 }; }
              var p = __mkSelf(a.sx, a.sy, a.sz, a.dx, a.dy, a.dz);
              var ok = gsSiBurst(p);
              var st = GS_SI_ST[__SELF];
              return { ptc: __ptc, snd: __snd, dmg: __dmg, boxes: __boxes, ok: ok,
                       n: st ? st.n : 0, fx: GS_SI_BURST_FX.length };
            }

            function __emSwing(a) {
              __reset(a.cands);
              GS_EM_ST = {}; GS_EM_SWING_AT = {}; GS_EM_BARS = {};
              GS_EM_FIELDS = []; GS_EM_CRACKS = []; GS_EM_FX = [];
              if (a.n > 0) { GS_EM_ST[__SELF] = { n: a.n, until: __now + 1000 }; }
              var p = __mkSelf(a.sx, a.sy, a.sz, a.dx, a.dy, a.dz);
              gsEmSwing(p);
              var st = GS_EM_ST[__SELF];
              return { ptc: __ptc, snd: __snd, dmg: __dmg, boxes: __boxes,
                       n: st ? st.n : 0, fields: GS_EM_FIELDS.length, fx: GS_EM_FX.length,
                       cracks: GS_EM_CRACKS.length };
            }

            // 连击窗口语义：给定初始 (n, untilΔ)，跑一次 gsXxPlayerTick，报结论。
            //   ★ 只报**与时钟无关**的量（层数 / 存活 / 音效 / 血条标题 / 可见 / 进度是否恰好 1.0）
            //     —— Java 侧的时钟是 System.currentTimeMillis()，绝对时间戳比不了。
            //     「重置窗口 = 满格」这件事由 progOne 钉住（重置路径喂的是 left = total = WINDOW）。
            //   ★ 行以 JSON 字符串返回 = 两边直接字符串相等比较，不留浮点/格式的模糊地带。
            function __tickRow(ST) {
              var st = ST[__SELF];
              var b = __bars.length ? __bars[__bars.length - 1] : null;
              return JSON.stringify([st ? st.n : 0,
                      (st && st.until > __now) ? 1 : 0,
                      __snd.length ? (__snd[__snd.length - 1][0] + '@' + __snd[__snd.length - 1][1] + '/' + __snd[__snd.length - 1][2]) : '',
                      b ? b.__title : '',
                      b ? (b.__vis ? 1 : 0) : -1,
                      b ? (b.__prog === 1.0 ? 1 : 0) : -1]);
            }

            function __tickGrid(which, cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                // ★ 必须走 __reset：它会重建 __world。少了这一步 __mkSelf 的
                //   p.getWorld() 是 undefined ⇒ gsMsSoundAt 抛错被吞 ⇒ 音效记录恒为空。
                __reset([]);
                __now = 1000000;
                __bars = [];
                GS_VO_ST = {}; GS_SI_ST = {}; GS_EM_ST = {};
                GS_VO_BARS = {}; GS_SI_BARS = {}; GS_EM_BARS = {};
                var ST = (which === 'vo') ? GS_VO_ST : (which === 'si' ? GS_SI_ST : GS_EM_ST);
                if (c[0] > 0) {
                  ST[__SELF] = { n: c[0], until: (c[1] === null ? 0 : __now + c[1]) };
                }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                if (which === 'vo') { gsVoPlayerTick(p); }
                else if (which === 'si') { gsSiPlayerTick(p); }
                else { gsEmPlayerTick(p); }
                out.push(__tickRow(ST));
              }
              return out;
            }

            // 加层语义：给 (n) 加一次，报 (n, 存活, 音效, 血条标题, 可见, 满格)
            function __addGrid(which, cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                __reset([]);
                __now = 1000000;
                __bars = [];
                GS_VO_ST = {}; GS_SI_ST = {}; GS_VO_BARS = {}; GS_SI_BARS = {};
                var ST = (which === 'vo') ? GS_VO_ST : GS_SI_ST;
                if (cases[i][0] > 0) { ST[__SELF] = { n: cases[i][0], until: __now + 1000 }; }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                if (which === 'vo') { gsVoAdd(p); } else { gsSiAdd(p); }
                out.push(__tickRow(ST));
              }
              return out;
            }

            // 纯公式网格
            function __dmgGrid(which, ns) {
              var out = [];
              for (var i = 0; i < ns.length; i++) {
                out.push(which === 'vo' ? gsVoDmg(ns[i]) : gsSiDmg(ns[i]));
              }
              return out;
            }

            function __emNextGrid(ns) { var o = []; for (var i = 0; i < ns.length; i++) o.push(gsEmNext(ns[i])); return o; }

            function __titleGrid(which, ns) {
              var o = [];
              for (var i = 0; i < ns.length; i++) {
                o.push(which === 'vo' ? gsVoTitle('言 ' + ns[i] + '/' + GS_VO_MAX)
                                      : (which === 'si' ? gsSiTitle(ns[i]) : gsEmTitle(ns[i])));
              }
              return o;
            }

            // 朝向基向量（Em）
            function __basisGrid(dirs) {
              var o = [];
              for (var i = 0; i < dirs.length; i++) {
                var d = dirs[i];
                __pts = [];
                var loc = { getDirection: function () { return __JSVec(d[0], d[1], d[2]); },
                            getYaw: function () { return d[3]; } };
                var b = gsEmBasis(loc);
                o.push([b.fx, b.fy, b.fz, b.rx, b.ry, b.rz, b.ux, b.uy, b.uz]);
              }
              return o;
            }

            // 六刀形状（Em）：把 Shape 拍平成数值数组
            function __shapeFlat(sh) {
              if (sh.cir === 1) {
                return [1, sh.o[0], sh.o[1], sh.o[2], sh.r, sh.tilt, sh.a0, sh.a1];
              }
              return [0, sh.a[0], sh.a[1], sh.a[2], sh.b[0], sh.b[1], sh.b[2],
                      sh.c[0], sh.c[1], sh.c[2]];
            }

            function __shapeGrid(idxs) {
              var o = [];
              for (var i = 0; i < idxs.length; i++) {
                var arr = gsEmShape(idxs[i]);
                var row = [arr.length];
                for (var j = 0; j < arr.length; j++) {
                  var f = __shapeFlat(arr[j]);
                  for (var k = 0; k < f.length; k++) { row.push(f[k]); }
                }
                o.push(row);
              }
              return o;
            }

            // 圆弧采样点（Em）：gsEmCircDraw 撒出来的所有点
            function __circGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                var b = { w: __world, ox: c[0], oy: c[1], oz: c[2],
                          fx: c[3], fy: c[4], fz: c[5], rx: c[6], ry: c[7], rz: c[8],
                          ux: c[9], uy: c[10], uz: c[11] };
                var sh = gsEmCircle(c[12], c[13], c[14], c[15], c[16], c[17], c[18]);
                gsEmCircDraw(__world, b, sh, c[19], c[20]);
                o.push(__ptc);
              }
              return o;
            }

            // 贝塞尔弧采样点（Em）
            function __arcGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                var b = { w: __world, ox: c[0], oy: c[1], oz: c[2],
                          fx: c[3], fy: c[4], fz: c[5], rx: c[6], ry: c[7], rz: c[8],
                          ux: c[9], uy: c[10], uz: c[11] };
                var sh = gsEmArcTo([c[12], c[13], c[14]], [c[15], c[16], c[17]], [c[18], c[19], c[20]]);
                gsEmArcDraw(__world, b, sh, c[21], c[22]);
                o.push(__ptc);
              }
              return o;
            }

            // 第 6 刀圆圈采样点（Em）
            function __slash6Grid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                var b = { w: __world, ox: c[0], oy: c[1], oz: c[2],
                          fx: c[3], fy: c[4], fz: c[5], rx: c[6], ry: c[7], rz: c[8],
                          ux: c[9], uy: c[10], uz: c[11] };
                gsEmDrawSlash6(__world, b, c[12]);
                o.push(__ptc);
              }
              return o;
            }

            // 次元斩大圆环采样点（Em）
            function __bigGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                var b = { w: __world, ox: c[0], oy: c[1], oz: c[2],
                          fx: c[3], fy: c[4], fz: c[5], rx: c[6], ry: c[7], rz: c[8],
                          ux: c[9], uy: c[10], uz: c[11] };
                gsEmDrawBig(__world, c[12], c[13], c[14], b, c[15], c[16]);
                o.push(__ptc);
              }
              return o;
            }

            // 裂缝点（Em）：gsEmCrackPoint 的 (l, h, f) → 世界点
            function __crackPtGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                // ★ gsEmCrackPoint 读的是 c.bs.fx / c.bs.rx …（坐标系是**嵌在 bs 里**的）
                var cr = { w: __world, x: c[0], y: c[1], z: c[2],
                           bs: { fx: c[3], fy: c[4], fz: c[5], rx: c[6], ry: c[7], rz: c[8],
                                 ux: c[9], uy: c[10], uz: c[11] } };
                var pt = gsEmCrackPoint(cr, c[12], c[13], c[14]);
                o.push([pt.getX(), pt.getY(), pt.getZ()]);
              }
              return o;
            }

            // 裂缝生命周期（Em）：gsEmCrackTick 的返回值 + 撒点数
            function __crackTickGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];    // [used, ageMs, dtMs]
                __reset([]);
                var cr = { w: __world, owner: null, x: 0, y: 64, z: 0, born: 0, used: !!c[0],
                           bs: { fx: 0, fy: 0, fz: 1, rx: -1, ry: 0, rz: 0, ux: 0, uy: 1, uz: 0 } };
                __now = 1000000;
                cr.born = __now - c[1];
                var alive = gsEmCrackTick(cr, c[2]);
                o.push([alive ? 1 : 0, __ptc.length]);
              }
              return o;
            }

            // 次元斩领域每拍（Em）：gsEmFieldTick 的返回值 + 伤害
            function __fieldTickGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];    // [bornΔ, nextHitΔ, cands...]
                __reset([]);
                __now = 1000000;
                var f = { w: __world, owner: null, x: 0, y: 64, z: 0,
                          bs: { fx: 0, fy: 0, fz: 1, rx: -1, ry: 0, rz: 0, ux: 0, uy: 1, uz: 0 },
                          born: __now - c[0], nextHit: __now + c[1] };
                var alive = gsEmFieldTick(f, __now);
                o.push([alive ? 1 : 0, __dmg.length, __snd.length]);
              }
              return o;
            }

            // 领域生命周期：走**真心跳** gsEmPulse（寿命过滤 / 画面重画 / 伤害节奏都在里面）
            //   cases: [bornΔ, nextHitΔ, drawAtΔ, mobX, mobY, mobZ]
            function __fieldPulseGrid(cases) {
              var o = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __reset([]);
                __now = 1000000;
                __cands = [{ id: '00000000-0000-0000-0000-0000000000e2', type: 'ZOMBIE',
                             x: c[3], y: c[4], z: c[5], dead: false, noHealth: false }];
                GS_EM_ST = {}; GS_EM_BARS = {}; GS_EM_FX = []; GS_EM_CRACKS = [];
                GS_EM_FIELDS = [{ w: __world, owner: null, x: 0, y: 64, z: 0,
                    bs: { fx: 0, fy: 0, fz: 1, rx: -1, ry: 0, rz: 0, ux: 0, uy: 1, uz: 0 },
                    born: __now - c[0], nextHit: __now + c[1], drawAt: __now - c[2] }];
                GS_EM_LAST_PULSE = 0;
                gsEmPulse();
                o.push([GS_EM_FIELDS.length, __dmg.length, __ptc.length > 0 ? 1 : 0]);
              }
              return o;
            }

            function __logLines() { return __log; }

            // ============ 逝言弹道（★ 队列模式，逐拍手动驱动） ============

            function __voQiFire(a) {
              __reset(a.cands);
              GS_VO_ST = {}; GS_VO_FIRE_AT = {}; GS_VO_CHARGING = {};
              __q = []; __now = 1000000; __laterMode = 1;
              // 「撞墙」：墙垂直于 +Z，位于 z = sz + a.solidAt
              __solidFn = (a.solidAt === undefined || a.solidAt === null) ? null
                        : function (x, y, z) { return z >= a.sz + a.solidAt; };
              var p = __mkSelf(a.sx, a.sy, a.sz, a.dx, a.dy, a.dz);
              gsVoQiFire(p);
              var steps = 0, guard = 0;
              var perStep = [];
              while (__q.length > 0 && guard++ < 500) {
                var task = __q.shift();
                if (task.d >= 2) { break; }                 // 进入 tail 阶段 = 停（残留是随机的，不比）
                __now += 50;
                var before = __ptc.length;
                task.f();
                perStep.push(__ptc.length - before);
                steps++;
              }
              __laterMode = 0; __q = [];
              var st = GS_VO_ST[__SELF];
              return { dmg: __dmg, steps: steps, n: st ? st.n : 0, snd: __snd,
                       boxes: __boxes, perStep: perStep, firstPtc: __ptc.slice(0, 17) };
            }

            // 蓄力：GS_VO_QI_CHARGE 拍之后才发；中途把主手换掉 = 作废
            //   ★★ 「拍数」只能数**蓄力**那一段：放出去之后 step() 也挂在同一个队列里，
            //      一股脑数下去会把 14 拍弹道一起算进来（上一版就是这么数出 22 的）。
            //      蓄力结束的判据 = GS_VO_CHARGING 被删掉。
            function __voCharge(a) {
              __reset([]);
              GS_VO_ST = {}; GS_VO_FIRE_AT = {}; GS_VO_CHARGING = {};
              __q = []; __now = 1000000; __laterMode = 1;
              __hand = a.hand ? {} : null;
              __setItem('GS_VOICELESS');
              var p = __mkSelf(0, 64, 0, 0, 0, 1);
              gsVoQi(p);
              var ticks = 0, guard = 0, chargeTicks = -1;
              var chargingDuring = [];
              while (__q.length > 0 && guard++ < 500) {
                var task = __q.shift();
                __now += 50;
                if (ticks === a.dropAt) { __hand = null; }     // 中途换掉主手
                task.f();
                ticks++;
                var on = (GS_VO_CHARGING[__SELF] === true);
                chargingDuring.push(on ? 1 : 0);
                if (chargeTicks < 0 && !on) { chargeTicks = ticks; }
                if (chargeTicks >= 0) { break; }               // 蓄力段结束 ⇒ 后面的弹道不数
              }
              __laterMode = 0; __q = []; __hand = null;
              // fired 看「音效」而不是粒子：gsVoQiFire 一进去就先响两声，
              // 而弧形粒子要等下一拍 step() 才撒 ⇒ 用粒子判会晚一拍。
              return { ticks: chargeTicks < 0 ? ticks : chargeTicks, chargeTicks: chargeTicks,
                       fired: __snd.length >= 4 ? 1 : 0, charging: chargingDuring.join(''),
                       fireAt: GS_VO_FIRE_AT[__SELF] ? 1 : 0, snd: __snd.length };
            }

            // 去抖 / 冷却：给一组「上次触发在多少毫秒前」，看这次放不放行
            function __voGateGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];      // [swingAgo, fireAgo, charging]
                __reset([]);
                __now = 1000000;
                GS_VO_SWING_AT = {}; GS_VO_FIRE_AT = {}; GS_VO_CHARGING = {}; GS_VO_ST = {};
                if (c[0] !== null) { GS_VO_SWING_AT[__SELF] = __now - c[0]; }
                if (c[1] !== null) { GS_VO_FIRE_AT[__SELF] = __now - c[1]; }
                if (c[2]) { GS_VO_CHARGING[__SELF] = true; }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                gsVoSwing(p);
                gsVoQi(p);
                out.push([__ptc.length, __snd.length, GS_VO_FIRE_AT[__SELF] ? 1 : 0,
                          GS_VO_CHARGING[__SELF] === true ? 1 : 0]);
              }
              return out;
            }

            function __siGateGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];      // [swingAgo, burstAgo]
                __reset([]);
                __now = 1000000;
                GS_SI_SWING_AT = {}; GS_SI_BURST_AT = {}; GS_SI_ST = {}; GS_SI_BURST_FX = [];
                if (c[0] !== null) { GS_SI_SWING_AT[__SELF] = __now - c[0]; }
                if (c[1] !== null) { GS_SI_BURST_AT[__SELF] = __now - c[1]; }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                gsSiSwing(p);
                var ok = gsSiBurst(p);
                out.push([__ptc.length, __snd.length, ok ? 1 : 0, GS_SI_BURST_FX.length]);
              }
              return out;
            }

            function __emGateGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];      // [swingAgo, crackAgo]
                __reset([]);
                __now = 1000000;
                GS_EM_SWING_AT = {}; GS_EM_CRACK_AT = {}; GS_EM_ST = {};
                GS_EM_FX = []; GS_EM_CRACKS = []; GS_EM_FIELDS = {};
                if (c[0] !== null) { GS_EM_SWING_AT[__SELF] = __now - c[0]; }
                if (c[1] !== null) { GS_EM_CRACK_AT[__SELF] = __now - c[1]; }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                gsEmSwing(p);
                gsEmCrackOpen(p);
                out.push([__ptc.length, __snd.length, GS_EM_FX.length, GS_EM_CRACKS.length]);
              }
              return out;
            }

            // 药效续期判定：真脚本 gsEmBuffEffects 的内联逻辑 vs Java 抽出来的两个判定函数
            function __fxOf(dur, amp) {
              return { getDuration: function () { return dur; }, getAmplifier: function () { return amp; } };
            }

            function __emBuffGrid(cases) {
              var out = [];
              var names = ['E:RESISTANCE', 'E:REGENERATION', 'E:SPEED'];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];   // [has0,amp0,dur0, has1,amp1,dur1, has2,amp2,dur2, nvOwned,nvHas,nvDur]
                __fx = []; __fxRem = []; __fxCur = {};
                GS_EM_BUFF_EFF = {}; GS_EM_BUFF_NV = {};
                __now = 1000000;
                for (var j = 0; j < 3; j++) {
                  if (c[j * 3] === 1) { __fxCur[names[j]] = __fxOf(c[j * 3 + 2], c[j * 3 + 1]); }
                }
                if (c[9] === 1) { GS_EM_BUFF_NV[__SELF] = true; }
                if (c[10] === 1) { __fxCur['E:NIGHT_VISION'] = __fxOf(c[11], 0); }
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                gsEmBuffEffects(p, __SELF, true);
                var nv = 0;
                for (var k = 0; k < __fx.length; k++) { if (__fx[k][0] === 'E:NIGHT_VISION') { nv++; } }
                out.push([__fx.length - nv, nv, GS_EM_BUFF_NV[__SELF] === true ? 1 : 0]);
              }
              return out;
            }

            // 玩家自己那份更强的药效 = 不覆盖：真脚本的 gsEmBuffEffects 直接验
            function __emBuffClearGrid(cases) {
              var out = [];
              var names = ['E:RESISTANCE', 'E:REGENERATION', 'E:SPEED'];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];   // [amp0, amp1, amp2]（身上那份的 amp，-1 = 没有）
                __fx = []; __fxRem = []; __fxCur = {};
                GS_EM_BUFF_EFF = {}; GS_EM_BUFF_NV = {};
                __now = 1000000;
                var applied = [];
                for (var j = 0; j < 3; j++) {
                  if (c[j] >= 0) { __fxCur[names[j]] = __fxOf(600, c[j]); }
                  applied.push({ t: names[j], amp: [3, 2, 1][j] });
                }
                GS_EM_BUFF_EFF[__SELF] = applied;
                var p = __mkSelf(0, 64, 0, 0, 0, 1);
                gsEmBuffEffects(p, __SELF, false);
                out.push([__fxRem.length, __fxRem.join(',')]);
              }
              return out;
            }
            """;

    // ======================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== ListenerE 子系统对账探针：致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改 / 武器禁近战"
                + "（原脚本 7791~10950）===");
        System.out.println("脚本: " + SCRIPT);
        System.out.println("日志: " + LOG);
        System.out.println();

        String[] raw = readAll(SCRIPT);
        check("脚本存在且行数 > 10950（原脚本共 " + raw.length + " 行）", raw.length > SUB_TO);
        if (raw.length <= SUB_TO) {
            summary();
            return;
        }
        check("第 8599 行恰好是 gsVoStart();", raw[8598].trim().equals("gsVoStart();"));
        check("第 9279 行恰好是 gsSiStart();", raw[9278].trim().equals("gsSiStart();"));
        check("第 10837 行恰好是 gsEmStart();", raw[10836].trim().equals("gsEmStart();"));
        check("第 10950 行恰好是 gsWpnNoMeleeStart();", raw[10949].trim().equals("gsWpnNoMeleeStart();"));

        String region = String.join("\n", Arrays.copyOfRange(raw, SUB_FROM - 1, SUB_TO));
        String voRegion = String.join("\n", Arrays.copyOfRange(raw, 7790, 8599));
        String siRegion = String.join("\n", Arrays.copyOfRange(raw, 8600, 9279));
        String emRegion = String.join("\n", Arrays.copyOfRange(raw, 9280, 10837));
        String wpnRegion = String.join("\n", Arrays.copyOfRange(raw, 10838, 10950));

        String prefix = String.join("\n", Arrays.copyOfRange(raw, 0, SUB_TO));

        // ★★ 第二份前缀：把四个入口调用**置空** = 读到的是顶层 var 的「声明初值」。
        //    必须这样做的原因：第一份前缀跑完 gsXxStart() 之后，GS_VO_REGISTERED 变成 true、
        //    GS_VO_GEN 变成 1 …… 那是**运行期状态**，不是「常量对账」要比的东西。
        //    Java 侧比的是**新实例的字段初值**，两边必须都取「启动前」。
        String[] declRaw = Arrays.copyOf(raw, SUB_TO);
        declRaw[8598] = "// gsVoStart();  —— 探针置空：这里只要顶层 var 的声明初值";
        declRaw[9278] = "// gsSiStart();  —— 探针置空";
        declRaw[10836] = "// gsEmStart();  —— 探针置空";
        declRaw[10949] = "// gsWpnNoMeleeStart();  —— 探针置空";
        String declPrefix = String.join("\n", declRaw);

        installFakeRegistry();
        installFakeBukkit();

        Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        ctx.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());
        ctx.eval(Source.newBuilder("js", JS_SHIM, "shim").build());

        boolean evalOk = true;
        try {
            ctx.eval(Source.newBuilder("js", prefix, "gs_listener-prefix").build());
        } catch (Throwable t) {
            evalOk = false;
            System.out.println("!! 原脚本前缀求值抛错: " + t);
        }
        check("原脚本前 10950 行能在桥接桩下求值（真 JS）", evalOk);
        ctx.eval(Source.newBuilder("js", JS_HELPERS, "helpers").build());

        Context ctxDecl = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        ctxDecl.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());
        ctxDecl.eval(Source.newBuilder("js", JS_SHIM, "shim").build());
        boolean declOk = true;
        try {
            ctxDecl.eval(Source.newBuilder("js", declPrefix, "gs_listener-decl").build());
        } catch (Throwable t) {
            declOk = false;
            System.out.println("!! 原脚本前缀（入口置空）求值抛错: " + t);
        }
        check("原脚本前 10950 行在「四个入口置空」下也能求值（用于读声明初值）", declOk);

        constants(ctxDecl);
        logLines(ctx);
        geometry(ctx);
        reverse(ctx, region, voRegion, siRegion, emRegion, wpnRegion);

        ctx.close();
        ctxDecl.close();

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

    private static final String[] VO_NAMES = {
            "GS_VO_ID", "GS_VO_NO_BREAK", "GS_VO_HIT_PLAYERS", "GS_VO_MAX", "GS_VO_WINDOW",
            "GS_VO_DMG_BASE", "GS_VO_DMG_PER", "GS_VO_BAR_COL", "GS_VO_SWING_R", "GS_VO_SWING_W",
            "GS_VO_SWING_H", "GS_VO_SWING_CD", "GS_VO_QI_CHARGE", "GS_VO_QI_RANGE", "GS_VO_QI_WIDE",
            "GS_VO_QI_H", "GS_VO_QI_PIERCE", "GS_VO_QI_DMG", "GS_VO_QI_STEP", "GS_VO_QI_CD",
            "GS_VO_QI_TRAIL", "GS_VO_QI_STACK", "GS_VO_QI_COL", "GS_VO_REGISTERED",
            "GS_VO_CLICK_AT", "GS_VO_SWING_AT", "GS_VO_FIRE_AT", "GS_VO_CHARGING",
            "GS_VO_ST", "GS_VO_BARS", "GS_VO_GEN"
    };

    private static final String[] SI_NAMES = {
            "GS_SI_ID", "GS_SI_NO_BREAK", "GS_SI_HIT_PLAYERS", "GS_SI_MAX", "GS_SI_WINDOW",
            "GS_SI_DMG_BASE", "GS_SI_DMG_PER", "GS_SI_BAR_COL", "GS_SI_WIDE", "GS_SI_RANGE",
            "GS_SI_H", "GS_SI_SWING_CD", "GS_SI_BURST_R", "GS_SI_BURST_MUL", "GS_SI_BURST_CD",
            "GS_SI_BURST_FX_MS", "GS_SI_BURST_FX_N", "GS_SI_REGISTERED", "GS_SI_CLICK_AT",
            "GS_SI_SWING_AT", "GS_SI_SIDE", "GS_SI_ST", "GS_SI_BARS", "GS_SI_BURST_AT",
            "GS_SI_BURST_FX", "GS_SI_GEN"
    };

    private static final String[] EM_NAMES = {
            "GS_EM_ID", "GS_EM_NO_BREAK", "GS_EM_HIT_PLAYERS", "GS_EM_DMG", "GS_EM_WIDE",
            "GS_EM_RANGE", "GS_EM_H", "GS_EM_SLASH_N", "GS_EM_CD", "GS_EM_FX_MS",
            "GS_EM_RESET_MS", "GS_EM_BAR_COL", "GS_EM_ARCH", "GS_EM_S6_R", "GS_EM_S6_TILT",
            "GS_EM_S6_N", "GS_EM_S6_DMG", "GS_EM_S6_THICK", "GS_EM_DS_DIST", "GS_EM_DS_R",
            "GS_EM_DS_DMG", "GS_EM_DS_EVERY", "GS_EM_DS_LIFE", "GS_EM_DS_PULL", "GS_EM_DS_BIG",
            "GS_EM_DS_RING_N", "GS_EM_DS_DRAW_MS", "GS_EM_CRACK_DIST", "GS_EM_CRACK_HALF",
            "GS_EM_CRACK_H", "GS_EM_CRACK_MIDH", "GS_EM_CRACK_DRAW", "GS_EM_CRACK_KEEP",
            "GS_EM_CRACK_CD", "GS_EM_DIA_R", "GS_EM_DIA_H", "GS_EM_TOUCH_R", "GS_EM_BUFF_TICKS",
            "GS_EM_BUFF_REF", "GS_EM_S5_PF", "GS_EM_S5_PL", "GS_EM_S5_PH", "GS_EM_REGISTERED",
            "GS_EM_ST", "GS_EM_BARS", "GS_EM_SWING_AT", "GS_EM_CLICK_AT", "GS_EM_CRACK_AT",
            "GS_EM_ANCHOR", "GS_EM_BUFF_FLY", "GS_EM_BUFF_EFF", "GS_EM_BUFF_NV", "GS_EM_FX",
            "GS_EM_FIELDS", "GS_EM_CRACKS", "GS_EM_GEN", "GS_EM_C_PUR", "GS_EM_C_BLU",
            "GS_EM_C_DSR", "GS_EM_C_DSP", "GS_EM_LAST_PULSE"
    };

    private static final String[] WPN_NAMES = {
            "GS_WPN_NOMELEE", "GS_WPN_NL_PVP", "GS_WPN_IDS", "GS_WPN_REGISTERED"
    };

    private static void constants(Context ctx) throws Exception {
        System.out.println("[A] 常量对账：真 JS 顶层 var vs Java 字段（按名按值）");

        VoSub vo = new VoSub(new ListenerCore());
        SiSub si = new SiSub(new ListenerCore());
        EmSub em = new EmSub(new ListenerCore());
        WpnNoMeleeSub wpn = new WpnNoMeleeSub(new ListenerCore());
        Value b = ctx.getBindings("js");

        int n = 0;
        for (String name : VO_NAMES) {
            n += one(b, VoSub.class, vo, name);
        }
        for (String name : SI_NAMES) {
            n += one(b, SiSub.class, si, name);
        }
        for (String name : EM_NAMES) {
            n += one(b, EmSub.class, em, name);
        }
        for (String name : WPN_NAMES) {
            n += one(b, WpnNoMeleeSub.class, wpn, name);
        }
        int total = VO_NAMES.length + SI_NAMES.length + EM_NAMES.length + WPN_NAMES.length;
        check("常量对账条数 = " + n + "（应为 " + total + "）", n == total);

        // 三个世代号键（JS 侧是宿主对象 = 比 toString）
        check("GS_VO_K_GEN = gs:vo_gen",
                String.valueOf(b.getMember("GS_VO_K_GEN")).equals(VoSub.GS_VO_K_GEN.toString()));
        check("GS_SI_K_GEN = gs:si_gen",
                String.valueOf(b.getMember("GS_SI_K_GEN")).equals(SiSub.GS_SI_K_GEN.toString()));
        check("GS_EM_K_GEN = gs:em_gen",
                String.valueOf(b.getMember("GS_EM_K_GEN")).equals(EmSub.GS_EM_K_GEN.toString()));

        // ★★ Java 侧**故意省略**的三个顶层 var：PDT 与 ENCH 探测闩锁。
        //    真 JS 里 GS_VO_PDT / GS_SI_PDT / GS_EM_PDT 都非 null（PersistentDataType 独立可用）
        //    = 原脚本里「PDT == null」那一半判断在 Java 里恒为假，删掉不改变行为。
        check("★ GS_VO_PDT 在真 JS 里非 null = Java 省略这一半判断是有依据的",
                !b.getMember("GS_VO_PDT").isNull());
        check("★ GS_SI_PDT 在真 JS 里非 null", !b.getMember("GS_SI_PDT").isNull());
        check("★ GS_EM_PDT 在真 JS 里非 null", !b.getMember("GS_EM_PDT").isNull());
        check("★ VoSub 里没有 GS_VO_PDT 字段（PDT 必然可用 = 只留 K_GEN != null 这一半）",
                !hasField(VoSub.class, "GS_VO_PDT"));
        check("★ SiSub 里没有 GS_SI_PDT 字段", !hasField(SiSub.class, "GS_SI_PDT"));
        check("★ EmSub 里没有 GS_EM_PDT 字段", !hasField(EmSub.class, "GS_EM_PDT"));

        // 真 JS 里三个 ENCH 缓存字段在求值后都是 null（懒探测还没跑）
        check("★ GS_VO_ENCH 在真 JS 里初始为 null（懒探测）= Java 用闩锁 + 局部缓存",
                b.getMember("GS_VO_ENCH").isNull());
        check("★ GS_VO_ENCH_TRIED 在真 JS 里初始为 false", !b.getMember("GS_VO_ENCH_TRIED").asBoolean());

        // GS_WPN_DMG_GUARD 是**月镰那边**的共享顶层 var（脚本 5967 行，不在本区间）
        check("★ GS_WPN_DMG_GUARD 声明在脚本 5967 行（属 ListenerCore，不属本区间）",
                rawLine(5967).trim().equals("var GS_WPN_DMG_GUARD = 0;"));
        check("★ ListenerCore 已有 gsWpnDmgGuard 字段（WpnNoMeleeSub 直接读它）",
                hasField(ListenerCore.class, "gsWpnDmgGuard"));
        check("★ WpnNoMeleeSub 读的是 core.gsWpnDmgGuard（同一份守卫）",
                readText(WPN_SRC).contains("core.gsWpnDmgGuard"));

        // Java 侧独有的具名常量：原脚本是内联字面量 = 钉住脚本里的字面量
        check("Java VoSub.gsVoDmg 用的 GS_VO_DMG_BASE / PER / MAX ⇔ 脚本里 "
                        + "'GS_VO_DMG_BASE + GS_VO_DMG_PER * (k - 1)'",
                voRegionContains("return GS_VO_DMG_BASE + GS_VO_DMG_PER * (k - 1);"));
        check("Java SiSub.gsSiDmg ⇔ 脚本里 'GS_SI_DMG_BASE + GS_SI_DMG_PER * (k - 1)'",
                siRegionContains("return GS_SI_DMG_BASE + GS_SI_DMG_PER * (k - 1);"));
        check("Java GS_EM_ARCH = 0.62 ⇔ 脚本里 'sh.r * GS_EM_ARCH'",
                emRegionContains("var RF = sh.r * GS_EM_ARCH;"));
        check("★ 六刀一循环 = GS_EM_SLASH_N(6) ⇔ 脚本里 'idx >= GS_EM_SLASH_N'",
                emRegionContains("GS_EM_SLASH_N"));
        System.out.println();
    }

    private static boolean hasField(Class<?> cls, String name) {
        for (Field f : cls.getDeclaredFields()) {
            if (f.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 按名比对 JS 顶层 var 与 Java 字段（静态优先，其次实例字段）。 */
    private static int one(Value b, Class<?> cls, Object inst, String name) throws Exception {
        if (!b.hasMember(name)) {
            check(name + "（JS 里有这个顶层 var）", false);
            return 0;
        }
        Object jsVal = toJava(b.getMember(name));
        Field f = null;
        for (Field x : cls.getDeclaredFields()) {
            if (x.getName().equals(name)) {
                f = x;
                break;
            }
        }
        if (f == null) {
            check(name + "（Java 里有同名字段）", false);
            return 0;
        }
        f.setAccessible(true);
        Object javaVal = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(inst);
        boolean ok = sameValue(jsVal, javaVal);
        check(name + " = " + show(javaVal) + (ok ? "" : "   JS=" + show(jsVal)), ok);
        return 1;
    }

    private static String regionTextCache;
    private static String voRegionCache;
    private static String siRegionCache;
    private static String emRegionCache;
    private static String wpnRegionCache;

    private static String slice(int from, int to) {
        try {
            String[] raw = readAll(SCRIPT);
            return String.join("\n", Arrays.copyOfRange(raw, from - 1, to));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String regionText() {
        if (regionTextCache == null) {
            regionTextCache = slice(SUB_FROM, SUB_TO);
        }
        return regionTextCache;
    }

    private static String voRegionText() {
        if (voRegionCache == null) {
            voRegionCache = slice(7791, 8599);
        }
        return voRegionCache;
    }

    private static String siRegionText() {
        if (siRegionCache == null) {
            siRegionCache = slice(8601, 9279);
        }
        return siRegionCache;
    }

    private static String emRegionText() {
        if (emRegionCache == null) {
            emRegionCache = slice(9281, 10837);
        }
        return emRegionCache;
    }

    private static String wpnRegionText() {
        if (wpnRegionCache == null) {
            wpnRegionCache = slice(10839, 10950);
        }
        return wpnRegionCache;
    }

    private static boolean regionContains(String s) {
        return regionText().contains(s);
    }

    private static boolean voRegionContains(String s) {
        return voRegionText().contains(s);
    }

    private static boolean siRegionContains(String s) {
        return siRegionText().contains(s);
    }

    private static boolean emRegionContains(String s) {
        return emRegionText().contains(s);
    }

    private static boolean wpnRegionContains(String s) {
        return wpnRegionText().contains(s);
    }

    private static String rawLine(int lineNo) {
        try {
            return readAll(SCRIPT)[lineNo - 1];
        } catch (Throwable t) {
            return "<读不到第 " + lineNo + " 行>";
        }
    }

    // ======================================================================
    // [B] 日志行逐字节
    // ======================================================================

    private static void logLines(Context ctx) throws Exception {
        System.out.println("[B] 日志行逐字节：真 JS 打出来的 / Java 的 / 参考服日志的");

        Value log = ctx.eval("js", "__logLines()");
        List<String> js = new ArrayList<>();
        for (long i = 0; i < log.getArraySize(); i++) {
            js.add(log.getArrayElement(i).asString());
        }

        // ★★ 世界世代号：真 JS 侧三个子系统启动时都读到「空 PDC = 写出 1」
        //     —— 这是下面用 GEN = 1 的依据，先验再用（就在**跑过入口**的这个上下文里）。
        Value b = ctx.getBindings("js");
        check("★★ 真 JS 启动后 GS_VO_GEN = 1（空 PDC = 第一次 bump 写出 1）",
                b.getMember("GS_VO_GEN").asInt() == GEN);
        check("★★ 真 JS 启动后 GS_SI_GEN = 1", b.getMember("GS_SI_GEN").asInt() == GEN);
        check("★★ 真 JS 启动后 GS_EM_GEN = 1", b.getMember("GS_EM_GEN").asInt() == GEN);
        check("★★ 真 JS 的假世界 PDC 里确实写着 gs:vo_gen = 1",
                ctx.eval("js", "(function(){ var v = __wpdc.get(GS_VO_K_GEN, GS_VO_PDT.INTEGER);"
                        + " return v === null ? -999 : (v | 0); })()").asInt() == GEN);
        check("★★ 真 JS 的假世界 PDC 里确实写着 gs:si_gen = 1",
                ctx.eval("js", "(function(){ var v = __wpdc.get(GS_SI_K_GEN, GS_SI_PDT.INTEGER);"
                        + " return v === null ? -999 : (v | 0); })()").asInt() == GEN);
        check("★★ 真 JS 的假世界 PDC 里确实写着 gs:em_gen = 1",
                ctx.eval("js", "(function(){ var v = __wpdc.get(GS_EM_K_GEN, GS_EM_PDT.INTEGER);"
                        + " return v === null ? -999 : (v | 0); })()").asInt() == GEN);
        // Java 侧同一语义：空 PDC = 1；再 bump 一次 = 2（世代号真的在走）
        GLOBAL_PDC.m.remove("gs:vo_gen");
        VoSub vo2 = new VoSub(new ListenerCore());
        vo2.gsVoGenBump();
        boolean voGen1 = vo2.GS_VO_GEN == GEN && GLOBAL_PDC.m.get("gs:vo_gen") != null
                && ((Number) GLOBAL_PDC.m.get("gs:vo_gen")).intValue() == GEN;
        vo2.gsVoGenBump();
        boolean voGen2 = vo2.GS_VO_GEN == GEN + 1
                && ((Number) GLOBAL_PDC.m.get("gs:vo_gen")).intValue() == GEN + 1;
        GLOBAL_PDC.m.remove("gs:vo_gen");
        check("★ Java 侧空 PDC = gsVoGenBump() 得 1 并写回 PDC", voGen1);
        check("★ Java 侧再 bump 一次 = 得 2（世代号真的在走，不是常量）", voGen2);
        check("★ Java 侧三个子系统的世代号初值都是 -1（GS_VO_GEN / GS_SI_GEN / GS_EM_GEN）",
                new VoSub(new ListenerCore()).GS_VO_GEN == -1
                        && new SiSub(new ListenerCore()).GS_SI_GEN == -1
                        && new EmSub(new ListenerCore()).GS_EM_GEN == -1);

        String jsVoReg = findInfo(js, "致万千无言者监听器注册成功");
        String jsVoStart = findInfo(js, "致万千无言者（GS_VOICELESS）已加载");
        String jsSiReg = findInfo(js, "此刻万籁俱寂监听器注册成功");
        String jsSiStart = findInfo(js, "此刻万籁俱寂（GS_SILENCE）已加载");
        String jsEmReg = findInfo(js, "阎魔刀·改监听器注册成功");
        String jsEmStart = findInfo(js, "阎魔刀·改（GS_ENMA_BLADE）已加载");
        String jsWpn = findInfo(js, "武器原版近战已取消");

        check("真 JS 打出了「致万千无言者监听器注册成功」那一行", jsVoReg != null);
        check("真 JS 打出了「致万千无言者（GS_VOICELESS）已加载」那一行", jsVoStart != null);
        check("真 JS 打出了「此刻万籁俱寂监听器注册成功」那一行", jsSiReg != null);
        check("真 JS 打出了「此刻万籁俱寂（GS_SILENCE）已加载」那一行", jsSiStart != null);
        check("真 JS 打出了「阎魔刀·改监听器注册成功」那一行", jsEmReg != null);
        check("真 JS 打出了「阎魔刀·改（GS_ENMA_BLADE）已加载」那一行", jsEmStart != null);
        check("真 JS 打出了「武器原版近战已取消」那一行", jsWpn != null);

        String jVoReg = VoSub.gsVoRegisterLine();
        String jVoStart = VoSub.gsVoStartLine(GEN, true);
        String jSiReg = SiSub.gsSiRegisterLine();
        String jSiStart = SiSub.gsSiStartLine(GEN, true);
        String jEmReg = EmSub.gsEmRegisterLine();
        String jEmStart = EmSub.gsEmStartLine(GEN);
        String jWpn = WpnNoMeleeSub.gsWpnStartLine(true, false);

        // Java 的行**不含** [构思技艺] 前缀（前缀由 gsDaLog 统一加）= 比之前先把 JS 侧的前缀剥掉
        check("Java 注册行 == 真 JS 注册行（致万千无言者）", jVoReg.equals(body(jsVoReg)));
        check("Java 已加载行 == 真 JS 已加载行（致万千无言者）", jVoStart.equals(body(jsVoStart)));
        check("Java 注册行 == 真 JS 注册行（此刻万籁俱寂）", jSiReg.equals(body(jsSiReg)));
        check("Java 已加载行 == 真 JS 已加载行（此刻万籁俱寂）", jSiStart.equals(body(jsSiStart)));
        check("Java 注册行 == 真 JS 注册行（阎魔刀·改）", jEmReg.equals(body(jsEmReg)));
        check("Java 已加载行 == 真 JS 已加载行（阎魔刀·改）", jEmStart.equals(body(jsEmStart)));
        check("Java 取消近战行 == 真 JS 那一行（武器禁近战）", jWpn.equals(body(jsWpn)));
        check("★ 四行都带 [构思技艺] 前缀（由 gsDaLog 统一加，Java 行方法里不重复）",
                jsVoReg.startsWith(LOG_PREFIX) && jsSiReg.startsWith(LOG_PREFIX)
                        && jsEmReg.startsWith(LOG_PREFIX) && jsWpn.startsWith(LOG_PREFIX));

        // 参考服日志
        String[] ref = readAll(LOG);
        String refVoReg = findLine(ref, "致万千无言者监听器注册成功");
        String refVoStart = findLine(ref, "致万千无言者（GS_VOICELESS）已加载");
        String refSiReg = findLine(ref, "此刻万籁俱寂监听器注册成功");
        String refSiStart = findLine(ref, "此刻万籁俱寂（GS_SILENCE）已加载");
        String refEmReg = findLine(ref, "阎魔刀·改监听器注册成功");
        String refEmStart = findLine(ref, "阎魔刀·改（GS_ENMA_BLADE）已加载");
        String refWpn = findLine(ref, "武器原版近战已取消");

        check("参考服日志有「致万千无言者监听器注册成功」", refVoReg != null);
        check("参考服日志有「致万千无言者已加载」", refVoStart != null);
        check("参考服日志有「此刻万籁俱寂监听器注册成功」", refSiReg != null);
        check("参考服日志有「此刻万籁俱寂已加载」", refSiStart != null);
        check("参考服日志有「阎魔刀·改监听器注册成功」", refEmReg != null);
        check("参考服日志有「阎魔刀·改已加载」", refEmStart != null);
        check("参考服日志有「武器原版近战已取消」", refWpn != null);

        check("Java 注册行 == 参考服日志行（致万千无言者）", jVoReg.equals(body(refVoReg)));
        check("Java 注册行 == 参考服日志行（此刻万籁俱寂）", jSiReg.equals(body(refSiReg)));
        check("Java 注册行 == 参考服日志行（阎魔刀·改）", jEmReg.equals(body(refEmReg)));
        check("Java 取消近战行 == 参考服日志行（武器禁近战）", jWpn.equals(body(refWpn)));

        // ★★ 三行「已加载」末尾都带「世界世代号 N」—— N 是**运行期动态值**
        //    （参考服那台机器上 reload 过很多次，所以是 75/74/72）。
        //    做法：把参考服那一行的 N 换成探针的 N，再要求**逐字节相等** ——
        //    这样除了 N 以外的每一个字节都必须一致，且 N 的位置/分隔符也被钉住。
        check("★★ 参考服日志的世代号是运行期动态值（Vo=75 / Si=74 / Em=72，非 1）",
                "75".equals(genPart(refVoStart)) && "74".equals(genPart(refSiStart))
                        && "72".equals(genPart(refEmStart)));
        check("★★ 探针里真 JS 的世代号是 1（空 PDC = 首轮）",
                String.valueOf(GEN).equals(genPart(jsVoStart))
                        && String.valueOf(GEN).equals(genPart(jsSiStart))
                        && String.valueOf(GEN).equals(genPart(jsEmStart)));
        check("★★ Java 已加载行 == 真 JS 已加载行（致万千无言者）",
                jVoStart.equals(body(jsVoStart)));
        check("★★ 参考服「致万千无言者已加载」行把世代号换成 1 后 == Java 行（逐字节）",
                withGen(body(refVoStart), GEN).equals(jVoStart));
        check("★★ 参考服「此刻万籁俱寂已加载」行把世代号换成 1 后 == Java 行（逐字节）",
                withGen(body(refSiStart), GEN).equals(jSiStart));
        check("★★ 参考服「阎魔刀·改已加载」行把世代号换成 1 后 == Java 行（逐字节）",
                withGen(body(refEmStart), GEN).equals(jEmStart));

        // ★★ 契约 §3 里引用的那几行是**被「…」截断过的**（契约作者摘录时截的）
        //    = 只能断言「截断后的那一段是 Java 行的前缀」。
        //    这正好把「★ 有些行在参考日志里被截断了」这条要求钉住：
        //    证明截断的是**同一行**，而不是「文案变了导致前缀巧合相同」。
        String[] contract = readAll(CONTRACT);
        check("★★ 契约 §3 的「致万千无言者已加载」引用行带「…」（确实被截断过）",
                endsWithEllipsis(findLine(contract, "致万千无言者（GS_VOICELESS）已加载")));
        check("★★ 契约 §3 的「此刻万籁俱寂已加载」引用行带「…」",
                endsWithEllipsis(findLine(contract, "此刻万籁俱寂（GS_SILENCE）已加载")));
        check("★★ 契约 §3 的「阎魔刀·改已加载」引用行带「…」",
                endsWithEllipsis(findLine(contract, "阎魔刀·改（GS_ENMA_BLADE）已加载")));
        check("★★ 契约截断行（去「…」后）是 Java 行的前缀 —— 致万千无言者",
                contractPrefixOf(contract, "致万千无言者（GS_VOICELESS）已加载", jVoStart));
        check("★★ 契约截断行（去「…」后）是 Java 行的前缀 —— 此刻万籁俱寂",
                contractPrefixOf(contract, "此刻万籁俱寂（GS_SILENCE）已加载", jSiStart));
        check("★★ 契约截断行（去「…」后）是 Java 行的前缀 —— 阎魔刀·改",
                contractPrefixOf(contract, "阎魔刀·改（GS_ENMA_BLADE）已加载", jEmStart));
        check("★ 契约 §3 的「武器原版近战已取消」引用行**没有**「…」= 完整行逐字节相等",
                !endsWithEllipsis(findLine(contract, "武器原版近战已取消"))
                        && contractPrefixOf(contract, "武器原版近战已取消", jWpn));
        check("★ 契约 §3 的「阎魔刀·改已加载」引用行确实比 Java 行短（真被截断了）",
                findLine(contract, "阎魔刀·改（GS_ENMA_BLADE）已加载").length() < jEmStart.length());
        // 契约里写明了截断这件事，而不是让读者自己猜
        check("★ 契约里明确写了「被截断 = 只能比对前缀」（不是靠猜）",
                readText(CONTRACT).contains("被**截断**") && readText(CONTRACT).contains("只能比对**前缀**"));

        // 武器禁近战**没有**「已加载」行（它只打注册成功那一行）
        check("★ 武器禁近战没有「已加载」行（参考服日志里查不到）",
                findLine(ref, "武器禁近战") == null && findLine(ref, "GS_WPN") == null);
        check("★ 武器禁近战没有「已加载」行（真 JS 也没打）",
                js.stream().noneMatch(s -> s.startsWith("INFO ") && s.contains("武器禁近战")));

        // 逐字节展示，便于人工核对
        System.out.println("      · 真 JS 致万千无言者已加载行: " + jsVoStart);
        System.out.println("      · Java 致万千无言者已加载行: " + LOG_PREFIX + jVoStart);
        System.out.println("      · 参考服 致万千无言者那一行 : " + refVoStart);
        System.out.println("      · 真 JS 此刻万籁俱寂已加载行: " + jsSiStart);
        System.out.println("      · Java 此刻万籁俱寂已加载行: " + LOG_PREFIX + jSiStart);
        System.out.println("      · 参考服 此刻万籁俱寂那一行 : " + refSiStart);
        System.out.println("      · 真 JS 阎魔刀·改已加载行   : " + jsEmStart);
        System.out.println("      · Java 阎魔刀·改已加载行   : " + LOG_PREFIX + jEmStart);
        System.out.println("      · 参考服 阎魔刀·改那一行    : " + refEmStart);
        System.out.println("      · 契约 §3 阎魔刀·改引用行   : "
                + findLine(contract, "阎魔刀·改（GS_ENMA_BLADE）已加载"));
        System.out.println();
    }

    private static final String LOG_PREFIX = "[构思技艺] ";
    private static final String GEN_SUFFIX = " · 世界世代号 ";

    /** 剥掉 {@code [构思技艺] } 前缀（Java 的行方法只返回正文）。 */
    private static String body(String line) {
        if (line == null) {
            return null;
        }
        return line.startsWith(LOG_PREFIX) ? line.substring(LOG_PREFIX.length()) : line;
    }

    /** 取「 · 世界世代号 N」里的 N；没有这一截就返回 {@code null}。 */
    private static String genPart(String line) {
        if (line == null) {
            return null;
        }
        int i = line.lastIndexOf(GEN_SUFFIX);
        return i < 0 ? null : line.substring(i + GEN_SUFFIX.length());
    }

    /** 去掉「 · 世界世代号 N」尾巴。 */
    private static String genHead(String line) {
        if (line == null) {
            return null;
        }
        int i = line.lastIndexOf(GEN_SUFFIX);
        return i < 0 ? line : line.substring(0, i);
    }

    private static String withGen(String line, int gen) {
        return genHead(line) + GEN_SUFFIX + gen;
    }

    private static boolean endsWithEllipsis(String s) {
        return s != null && s.endsWith("…");
    }

    /** 契约里引用的那一行（可能带尾部「…」）= 去「…」后是否为 Java 行的前缀。 */
    private static boolean contractPrefixOf(String[] contract, String needle, String javaLine) {
        String c = findLine(contract, needle);
        if (c == null || javaLine == null) {
            return false;
        }
        if (c.endsWith("…")) {
            c = c.substring(0, c.length() - 1);
        }
        return javaLine.startsWith(body(c));
    }

    private static String findInfo(List<String> logs, String needle) {
        for (String s : logs) {
            if (s.startsWith("INFO ") && s.contains(needle)) {
                return s.substring(5);
            }
        }
        return null;
    }

    private static String findLine(String[] lines, String needle) {
        for (String s : lines) {
            if (s.contains(needle)) {
                return s;
            }
        }
        return null;
    }

    // ======================================================================
    // [C] 数值 / 几何
    // ======================================================================

    private static void geometry(Context ctx) throws Exception {
        System.out.println("[C] 数值 / 几何算式：真 JS 函数（mock 世界 / mock 玩家）vs Java 同一算式");

        damageFormulas(ctx);
        comboWindows(ctx);
        titlesAndColors(ctx);
        voSwing(ctx);
        siSwing(ctx);
        siBurst(ctx);
        emBasisAndShapes(ctx);
        emSwing(ctx);
        emDimension(ctx);
        emCrack(ctx);
        emBuffDecide(ctx);
        voQi(ctx);
        jsNumHelper(ctx);
        wpnGate(ctx);

        System.out.println();
    }

    // ---------- 小工具：把 JS 的行 / 点列读出来 ----------

    private static Value jsCall(Context ctx, String expr) {
        return ctx.eval("js", expr);
    }

    private static List<double[]> rows(Value v) {
        List<double[]> out = new ArrayList<>();
        for (long i = 0; i < v.getArraySize(); i++) {
            Value row = v.getArrayElement(i);
            double[] r = new double[(int) row.getArraySize()];
            for (int j = 0; j < r.length; j++) {
                Value e = row.getArrayElement(j);
                r[j] = e.isNumber() ? e.asDouble() : Double.NaN;
            }
            out.add(r);
        }
        return out;
    }

    /** 一维数值数组 = {@code double[]}。 */
    private static double[] nums(Value v) {
        double[] out = new double[(int) v.getArraySize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = v.getArrayElement(i).asDouble();
        }
        return out;
    }

    private static String rowsKey(List<double[]> rs) {
        StringBuilder sb = new StringBuilder();
        for (double[] r : rs) {
            sb.append('[');
            for (int i = 0; i < r.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(num(r[i]));
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static String num(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1.0e15) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private static List<double[]> pts(Value v) {
        List<double[]> out = new ArrayList<>();
        for (long i = 0; i < v.getArraySize(); i++) {
            Value e = v.getArrayElement(i);
            out.add(new double[]{e.getArrayElement(0).asDouble(), e.getArrayElement(1).asDouble(),
                    e.getArrayElement(2).asDouble()});
        }
        return out;
    }

    private static String ptsKey(List<double[]> ps) {
        StringBuilder sb = new StringBuilder();
        for (double[] p : ps) {
            sb.append('(').append(num(p[0])).append(',').append(num(p[1])).append(',').append(num(p[2])).append(')');
        }
        return sb.toString();
    }

    private static List<double[]> recPts(Rec r) {
        return r.ptc;
    }

    // ---------- C1 伤害公式 ----------

    private static void damageFormulas(Context ctx) {
        int[] ns = {0, 1, 2, 3, 5, 6, 7, 8, 9, 13, 20, 50, 98, 99, 100, 213, 1000, -1, -7};

        Value voJs = jsCall(ctx, "__dmgGrid('vo', " + ints(ns) + ")");
        Value siJs = jsCall(ctx, "__dmgGrid('si', " + ints(ns) + ")");
        double[] voRows = nums(voJs);
        double[] siRows = nums(siJs);

        boolean voOk = true;
        boolean siOk = true;
        for (int i = 0; i < ns.length; i++) {
            if (voRows[i] != VoSub.gsVoDmg(ns[i])) {
                voOk = false;
            }
            if (siRows[i] != SiSub.gsSiDmg(ns[i])) {
                siOk = false;
            }
        }
        check("★ gsVoDmg(n) 全网格逐值相同（" + ns.length + " 个输入：0/1/7/8/99/负数…）", voOk);
        check("★ gsSiDmg(n) 全网格逐值相同（" + ns.length + " 个输入）", siOk);
        check("★ gsVoDmg 满层 = 13.0（7 + 6×1）", VoSub.gsVoDmg(7) == 13.0);
        check("★ gsVoDmg 超上限仍夹到 13.0", VoSub.gsVoDmg(99) == 13.0);
        check("★ gsSiDmg 满层 = 213.0（17 + 2×98）", SiSub.gsSiDmg(99) == 213.0);
        check("★ gsSiDmg 超上限仍夹到 213.0", SiSub.gsSiDmg(1000) == 213.0);
        check("★ 两者下界都夹到第 1 层（gsVoDmg(0) = 7.0 / gsSiDmg(0) = 17.0）",
                VoSub.gsVoDmg(0) == 7.0 && SiSub.gsSiDmg(0) == 17.0);

        // gsEmNext：0 → 1 → … → 5 → 0（六刀一循环）
        int[] ens = {0, 1, 2, 3, 4, 5, 6, 7, 8, 11, 12, 13, 100, -1};
        Value emJs = jsCall(ctx, "__emNextGrid(" + ints(ens) + ")");
        boolean emOk = true;
        for (int i = 0; i < ens.length; i++) {
            if (emJs.getArrayElement(i).asInt() != EmSub.gsEmNext(ens[i])) {
                emOk = false;
            }
        }
        check("★ gsEmNext(n) 全网格逐值相同（" + ens.length + " 个输入）", emOk);
        check("★ 六刀一循环：gsEmNext(0..6) = 1,2,3,4,5,6,1（k<=0 → 1；否则 k%6+1）",
                EmSub.gsEmNext(0) == 1 && EmSub.gsEmNext(1) == 2 && EmSub.gsEmNext(2) == 3
                        && EmSub.gsEmNext(3) == 4 && EmSub.gsEmNext(4) == 5 && EmSub.gsEmNext(5) == 6
                        && EmSub.gsEmNext(6) == 1);
        check("★ 真 JS 的 gsEmNext 同一条式子（0→1 / 5→6 / 6→1 / 负数→1）",
                jsCall(ctx, "gsEmNext(0)").asInt() == 1 && jsCall(ctx, "gsEmNext(5)").asInt() == 6
                        && jsCall(ctx, "gsEmNext(6)").asInt() == 1
                        && jsCall(ctx, "gsEmNext(-3)").asInt() == 1);
    }

    private static String ints(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i]);
        }
        return sb.append(']').toString();
    }

    // ---------- C2 连击窗口语义（Vo 衰减 / Si 清 0 / Em 复位） ----------

    /**
     * {@code [n, untilΔ]}：{@code untilΔ = null} = {@code until = 0}（自愈路径）。
     * <p>★★ 这里**不能**放刀口值（{@code untilΔ = 1} / {@code = 3000}）：
     * Java 侧先取一次真实时钟写 {@code until}，{@code gsXxPlayerTick} 里再取一次，
     * 差个一两毫秒就会把「还没过期」翻成「已过期」⇒ 探针偶发红。
     * 刀口（2999 / 3000 / 3001）单独钉在真 JS 上（{@code __now} 冻结）。
     */
    private static final Object[][] TICK_CASES = {
            {0, null}, {1, null}, {3, null}, {7, null},
            {1, -1000}, {2, -1000}, {3, -1000}, {7, -1000}, {99, -1000},
            {1, 0}, {3, 0}, {7, 0},
            {1, 100}, {3, 100}, {7, 100}, {7, 1500}, {7, 2999}, {7, 3100},
    };

    private static void comboWindows(Context ctx) {
        for (String which : new String[]{"vo", "si"}) {
            Value js = jsCall(ctx, "__tickGrid('" + which + "', " + tickCasesLiteral() + ")");
            List<String> jsRows = strRows(js);
            List<String> javaRows = new ArrayList<>();
            for (Object[] c : TICK_CASES) {
                javaRows.add(javaTickRow(which, (Integer) c[0], (Integer) c[1]));
            }
            check("★ " + which + " 窗口语义全网格逐值相同（" + TICK_CASES.length + " 组）", jsRows.equals(javaRows));
            if (!jsRows.equals(javaRows)) {
                for (int i = 0; i < jsRows.size(); i++) {
                    if (!jsRows.get(i).equals(javaRows.get(i))) {
                        System.out.println("      · 差异 @case" + i + " " + Arrays.toString(TICK_CASES[i])
                                + "  JS=" + jsRows.get(i) + "  Java=" + javaRows.get(i));
                    }
                }
            }
        }

        // ★★ 语义对照：同样是「窗口过期」，Vo 掉一层、Si 清 0
        //   ★ Vo 窗口 3000ms 的**精确刀口**只能钉真 JS（Java 侧时钟会漂）：
        //     untilΔ = 0 ⇒ until 恰好 == now ⇒ 过期；untilΔ = 1 ⇒ 还差 1ms ⇒ 没过期
        List<String> voEdge = strRows(jsCall(ctx, "__tickGrid('vo', [[7,0],[7,1]])"));
        check("★★ Vo 窗口刀口（真 JS）：until == now 就过期（7→6）/ 差 1ms 还没过期（7 保持）",
                voEdge.get(0).startsWith("[6,1,") && voEdge.get(1).startsWith("[7,1,"));
        Value voExpire = jsCall(ctx, "__tickGrid('vo', [[3,-1000]])");
        Value siExpire = jsCall(ctx, "__tickGrid('si', [[3,-1000]])");
        check("★★ Vo 3 层过期 = 剩 2 层（逐层衰减）",
                strRows(voExpire).get(0).startsWith("[2,1,"));
        check("★★ Si 3 层过期 = 剩 0 层（直接清 0）",
                strRows(siExpire).get(0).startsWith("[0,0,"));
        check("★★ Java 侧同一对照：Vo 剩 2 层 / Si 剩 0 层",
                javaTickRow("vo", 3, -1000).startsWith("[2,1,")
                        && javaTickRow("si", 3, -1000).startsWith("[0,0,"));
        check("★★ Vo 掉到 0 的那一下才响 " + sndOf("block.beacon.deactivate", 0.6f, 1.4f),
                javaTickRow("vo", 1, -1000).contains(sndOf("block.beacon.deactivate", 0.6f, 1.4f))
                        && !javaTickRow("vo", 3, -1000).contains("block.beacon.deactivate"));
        check("★★ Si 清 0 时响 " + sndOf("block.beacon.deactivate", 0.6f, 1.2f),
                javaTickRow("si", 3, -1000).contains(sndOf("block.beacon.deactivate", 0.6f, 1.2f)));
        check("★★ Vo 掉到 0 时血条被藏起来（没有新建血条 = vis = -1）",
                javaTickRow("vo", 1, -1000).endsWith(",-1,-1]"));
        check("★★ Vo 掉到非 0 时血条重置成满格（progOne = 1）",
                javaTickRow("vo", 3, -1000).endsWith(",1,1]"));

        // 加层：封顶 + 满层音效
        Integer[] addNs = {0, 1, 2, 6, 7, 8, 98, 99, 100};
        for (String which : new String[]{"vo", "si"}) {
            Value js = jsCall(ctx, "__addGrid('" + which + "', " + addCasesLiteral(addNs) + ")");
            List<String> jsRows = strRows(js);
            List<String> javaRows = new ArrayList<>();
            for (Integer n : addNs) {
                javaRows.add(javaAddRow(which, n));
            }
            check("★ " + which + " 加层语义全网格逐值相同（" + addNs.length + " 组）", jsRows.equals(javaRows));
            if (!jsRows.equals(javaRows)) {
                for (int i = 0; i < jsRows.size(); i++) {
                    if (!jsRows.get(i).equals(javaRows.get(i))) {
                        System.out.println("      · 差异 @n=" + addNs[i] + "  JS=" + jsRows.get(i)
                                + "  Java=" + javaRows.get(i));
                    }
                }
            }
        }
        check("★ Vo 满层音效 " + sndOf("block.beacon.activate", 0.9f, 1.4f),
                javaAddRow("vo", 6).contains(sndOf("block.beacon.activate", 0.9f, 1.4f)));
        check("★ Si 满层音效 " + sndOf("block.beacon.activate", 0.9f, 1.2f),
                javaAddRow("si", 98).contains(sndOf("block.beacon.activate", 0.9f, 1.2f)));
        check("★ Vo 封顶 7：gsVoAdd(7) 后仍是 7", javaAddRow("vo", 7).startsWith("[7,"));
        check("★ Si 封顶 99：gsSiAdd(99) 后仍是 99", javaAddRow("si", 99).startsWith("[99,"));

        // Em 复位（3 秒没出刀 = 清空回到第 1 刀）
        Value emJs = jsCall(ctx, "__tickGrid('em', " + tickCasesLiteral() + ")");
        List<String> emJava = new ArrayList<>();
        for (Object[] c : TICK_CASES) {
            emJava.add(javaTickRow("em", (Integer) c[0], (Integer) c[1]));
        }
        check("★ em 窗口语义全网格逐值相同（" + TICK_CASES.length + " 组）", strRows(emJs).equals(emJava));
        check("★ Em 过期 = 清 0 并响 " + sndOf("block.beacon.deactivate", 0.5f, 1.4f),
                javaTickRow("em", 3, -1000).startsWith("[0,0,")
                        && javaTickRow("em", 3, -1000).contains(sndOf("block.beacon.deactivate", 0.5f, 1.4f)));
    }

    private static List<String> strRows(Value v) {
        List<String> out = new ArrayList<>();
        for (long i = 0; i < v.getArraySize(); i++) {
            out.add(v.getArrayElement(i).asString());
        }
        return out;
    }

    private static String tickCasesLiteral() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < TICK_CASES.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(TICK_CASES[i][0]).append(',')
                    .append(TICK_CASES[i][1] == null ? "null" : TICK_CASES[i][1]).append(']');
        }
        return sb.append(']').toString();
    }

    private static String addCasesLiteral(Integer[] ns) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ns.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(ns[i]).append(']');
        }
        return sb.append(']').toString();
    }

    /**
     * Java 侧的「跑一次 gsXxPlayerTick」—— 返回与 JS {@code __tickRow} <b>同一格式的 JSON 串</b>：
     * {@code [n, 存活, 音效, 血条标题, 可见, 进度是否恰好 1.0]}。
     *
     * <p>★ 时钟：{@code until} 用 {@code ListenerCore.now() ± Δ} 表达，比的是结论不是时间戳。
     */
    private static String javaTickRow(String which, int n, Integer untilDelta) {
        GLOBAL_PDC.barTitles.clear();
        GLOBAL_PDC.barProg.clear();
        GLOBAL_PDC.barVis.clear();
        ListenerCore core = new ListenerCore();
        VoSub vo = new VoSub(core);
        SiSub si = new SiSub(core);
        EmSub em = new EmSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        long now = ListenerCore.now();
        long until = untilDelta == null ? 0L : now + untilDelta;

        int nAfter;
        boolean alive;
        if ("vo".equals(which)) {
            if (n > 0) {
                VoSub.VoSt st = new VoSub.VoSt();
                st.n = n;
                st.until = until;
                vo.GS_VO_ST.put(SELF_ID, st);
            }
            vo.gsVoPlayerTick(p);
            VoSub.VoSt st = vo.GS_VO_ST.get(SELF_ID);
            nAfter = st == null ? 0 : st.n;
            alive = st != null && st.until > ListenerCore.now();
        } else if ("si".equals(which)) {
            if (n > 0) {
                SiSub.SiSt st = new SiSub.SiSt();
                st.n = n;
                st.until = until;
                si.GS_SI_ST.put(SELF_ID, st);
            }
            si.gsSiPlayerTick(p);
            SiSub.SiSt st = si.GS_SI_ST.get(SELF_ID);
            nAfter = st == null ? 0 : st.n;
            alive = st != null && st.until > ListenerCore.now();
        } else {
            if (n > 0) {
                EmSub.EmSt st = new EmSub.EmSt();
                st.n = n;
                st.until = until;
                em.GS_EM_ST.put(SELF_ID, st);
            }
            em.gsEmPlayerTick(p);
            EmSub.EmSt st = em.GS_EM_ST.get(SELF_ID);
            nAfter = st == null ? 0 : st.n;
            alive = st != null && st.until > ListenerCore.now();
        }
        return jrow(nAfter, alive ? 1 : 0, lastSnd(r), lastTitle(), lastVis(), lastProgOne());
    }

    /** Java 侧的「加一层」—— 与 JS {@code __addGrid} 同一格式。 */
    private static String javaAddRow(String which, int n) {
        GLOBAL_PDC.barTitles.clear();
        GLOBAL_PDC.barProg.clear();
        GLOBAL_PDC.barVis.clear();
        ListenerCore core = new ListenerCore();
        VoSub vo = new VoSub(core);
        SiSub si = new SiSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        long now = ListenerCore.now();

        int nAfter;
        boolean alive;
        if ("vo".equals(which)) {
            if (n > 0) {
                VoSub.VoSt st = new VoSub.VoSt();
                st.n = n;
                st.until = now + 1000;
                vo.GS_VO_ST.put(SELF_ID, st);
            }
            vo.gsVoAdd(p);
            VoSub.VoSt st = vo.GS_VO_ST.get(SELF_ID);
            nAfter = st == null ? 0 : st.n;
            alive = st != null && st.until > ListenerCore.now();
        } else {
            if (n > 0) {
                SiSub.SiSt st = new SiSub.SiSt();
                st.n = n;
                st.until = now + 1000;
                si.GS_SI_ST.put(SELF_ID, st);
            }
            si.gsSiAdd(p);
            SiSub.SiSt st = si.GS_SI_ST.get(SELF_ID);
            nAfter = st == null ? 0 : st.n;
            alive = st != null && st.until > ListenerCore.now();
        }
        return jrow(nAfter, alive ? 1 : 0, lastSnd(r), lastTitle(), lastVis(), lastProgOne());
    }

    private static String lastSnd(Rec r) {
        if (r.snd.isEmpty()) {
            return "";
        }
        Object[] s = r.snd.get(r.snd.size() - 1);
        // ★ 桥接的形参是 float ⇒ 读回来先经 float 再展宽，跟 JS 侧的 Math.fround 对齐
        return s[0] + "@" + num(((Number) s[1]).floatValue()) + "/" + num(((Number) s[2]).floatValue());
    }

    /** 与 JS {@code name@vol/pitch} 同格式；vol/pitch 按桥接的 float 形参走一遍。 */
    private static String sndOf(String name, float vol, float pitch) {
        return name + "@" + num(vol) + "/" + num(pitch);
    }

    private static String num(float f) {
        return num((double) f);
    }

    private static String lastTitle() {
        return GLOBAL_PDC.barTitles.isEmpty() ? "" : GLOBAL_PDC.barTitles.get(GLOBAL_PDC.barTitles.size() - 1);
    }

    private static int lastVis() {
        return GLOBAL_PDC.barVis.isEmpty() ? -1 : (GLOBAL_PDC.barVis.get(GLOBAL_PDC.barVis.size() - 1) ? 1 : 0);
    }

    private static int lastProgOne() {
        if (GLOBAL_PDC.barProg.isEmpty()) {
            return -1;
        }
        return GLOBAL_PDC.barProg.get(GLOBAL_PDC.barProg.size() - 1) == 1.0 ? 1 : 0;
    }

    /** 与 JS {@code JSON.stringify([...])} 同格式：数字照写、字符串带引号。 */
    private static String jrow(Object... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object o = items[i];
            if (o instanceof String s) {
                sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            } else if (o instanceof Number nn) {
                sb.append(num(nn.doubleValue()));
            } else {
                sb.append(String.valueOf(o));
            }
        }
        return sb.append(']').toString();
    }

    // ---------- C3 标题 / 颜色 ----------

    private static void titlesAndColors(Context ctx) {
        int[] ns = {0, 1, 2, 6, 7, 8, 42, 99, 100, 999};
        for (String which : new String[]{"vo", "si", "em"}) {
            Value js = jsCall(ctx, "__titleGrid('" + which + "', " + ints(ns) + ")");
            boolean ok = true;
            for (int i = 0; i < ns.length; i++) {
                String j;
                if ("vo".equals(which)) {
                    j = VoSub.gsVoTitle("言 " + ns[i] + "/" + VoSub.GS_VO_MAX);
                } else if ("si".equals(which)) {
                    j = SiSub.gsSiTitle(ns[i]);
                } else {
                    j = EmSub.gsEmTitle(ns[i]);
                }
                if (!j.equals(js.getArrayElement(i).asString())) {
                    ok = false;
                }
            }
            check("★ " + which + " 血条标题全网格逐字节相同（" + ns.length + " 组）", ok);
        }
        check("★ Vo 标题整条灰色：§7 前缀", VoSub.gsVoTitle("言 1/7").equals("§7言 1/7"));
        check("★ Si 标题「寂 N」不带 /99（§c 寂 + §4 数字）",
                SiSub.gsSiTitle(3).equals("§c寂 §43"));
        check("★ Em 标题「居合 N/6」（§5 前缀）", EmSub.gsEmTitle(3).equals("§5居合 §d3§5/§d6"));

        // 血条颜色 / 样式
        Value voC = jsCall(ctx, "String(gsVoBarColor())");
        Value siC = jsCall(ctx, "String(gsSiBarColor())");
        Value emC = jsCall(ctx, "String(gsEmBarColor())");
        Value style = jsCall(ctx, "String(gsMsBarStyle())");
        check("★ gsVoBarColor() = WHITE", voC.asString().equals(String.valueOf(VoSub.gsVoBarColor())));
        check("★ gsSiBarColor() = RED", siC.asString().equals(String.valueOf(SiSub.gsSiBarColor())));
        check("★ gsEmBarColor() = PURPLE", emC.asString().equals(String.valueOf(EmSub.gsEmBarColor())));
        check("★ gsMsBarStyle() = SEGMENTED_12", style.asString().equals(String.valueOf(VoSub.gsMsBarStyle())));
        check("★ 三处 gsMsBarStyle() 一致（Vo/Si/Em 各自一份副本）",
                VoSub.gsMsBarStyle() == SiSub.gsMsBarStyle() && SiSub.gsMsBarStyle() == EmSub.gsMsBarStyle());
        check("★ Vo 用 WHITE（原版 BarColor 没有灰色）", VoSub.gsVoBarColor() == BarColor.WHITE);
        check("★ Si 用 RED", SiSub.gsSiBarColor() == BarColor.RED);
        check("★ Em 用 PURPLE", EmSub.gsEmBarColor() == BarColor.PURPLE);

        // GS_MS_C 色表逐项
        Value c = jsCall(ctx, "GS_MS_C");
        boolean msOk = true;
        for (String k : new String[]{"RED", "DARK_RED", "GOLD", "YELLOW", "GREEN", "AQUA", "BLUE",
                "LIGHT_PURPLE", "PURPLE", "WHITE", "GRAY", "DARK_GRAY", "BLACK"}) {
            if (!c.getMember(k).asString().equals(VoSub.GS_MS_C.get(k))) {
                msOk = false;
            }
        }
        check("★ GS_MS_C 13 个色码逐项相同（VoSub 副本 vs 真 JS）", msOk);
        check("★ SiSub / EmSub 的 GS_MS_C 副本与 VoSub 完全相同",
                SiSub.GS_MS_C.equals(VoSub.GS_MS_C) && EmSub.GS_MS_C.equals(VoSub.GS_MS_C));
    }

    // ---------- C4 致万千无言者：左键挥砍 ----------

    private static void voSwing(Context ctx) {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {-0.7071067811865476, 0, 0.7071067811865476},
                {0, 1, 0}, {0, -1, 0}, {1.0e-5, 0, 1}, {0, 0, 1.0e-5},
        };
        // 候选怪：用 (fwd, lat, dy) 网格铺开，覆盖「前 3 格 × 左右 ±1.5 × 上下 ±1.6」的边界
        double[] fwds = {-0.5, -0.4, 0.0, 1.5, 2.9999, 3.0, 3.0001, 5.0};
        double[] lats = {-1.6, -1.5, -1.4999, 0.0, 1.4999, 1.5, 1.6};
        double[] dys = {-1.7, -1.6, -1.5999, 0.0, 1.5999, 1.6, 1.7};

        int cases = 0;
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        for (int di = 0; di < dirs.length; di++) {
            double[] d = dirs[di];
            for (double f : fwds) {
                for (double l : lats) {
                    for (double dy : dys) {
                        // 只取一部分组合，避免 10×8×7×7 = 3920 组太大
                        if (((int) Math.abs(f * 7 + l * 3 + dy * 11)) % 5 != 0) {
                            continue;
                        }
                        double sx = 0.5, sy = 64.0, sz = -3.25;
                        // 水平基：fx,fz 归一化（退化用 +Z）；rx = -fz, rz = fx
                        double fl = Math.sqrt(d[0] * d[0] + d[2] * d[2]);
                        double fx = d[0], fz = d[2];
                        if (fl < 1.0e-4) {
                            fx = 0.0;
                            fz = 1.0;
                            fl = 1.0;
                        }
                        fx /= fl;
                        fz /= fl;
                        double rx = -fz, rz = fx;
                        // 目标位置：fwd 沿 (fx,fz)、lat 沿 (rx,rz)；怪脚 y = sy + dy
                        double ex = sx + fx * f + rx * l;
                        double ez = sz + fz * f + rz * l;
                        double ey = sy + dy;
                        if (cases > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"sx\":").append(sx).append(",\"sy\":").append(sy).append(",\"sz\":").append(sz)
                                .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1])
                                .append(",\"dz\":").append(d[2]).append(",\"n\":1,\"cands\":[")
                                .append("{\"id\":\"").append(MOB_ID).append("\",\"type\":\"ZOMBIE\",\"x\":")
                                .append(ex).append(",\"y\":").append(ey).append(",\"z\":").append(ez)
                                .append(",\"noHealth\":false,\"dead\":false}]}");
                        meta.add(new double[]{di, f, l, dy, ex, ey, ez});
                        cases++;
                    }
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = jsCall(ctx, "__ARG = JSON.parse(__ARG); (function(){ var r = [];"
                + " for (var i = 0; i < __ARG.length; i++) { r.push(__voSwing(__ARG[i])); } return r; })()");

        boolean hitOk = true;
        boolean fxOk = true;
        boolean boxOk = true;
        boolean sndOk = true;
        int hitCount = 0;
        int missCount = 0;
        int mismatch = 0;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[] m = meta.get(i);
            double[] d = dirs[(int) m[0]];
            double f = m[1], l = m[2], dy = m[3];

            // 期望：脚本里的三条判定（fwd ∈ [-0.4, 3.0]、|lat| ≤ 1.5、|(脚+0.9) - (脚+1.0)| ≤ 1.6）
            //   ★ 第三条的正确式子是 |dy - 0.1| ≤ 1.6（判定原点是脚 + 1.0，怪身中心是脚 + 0.9）。
            boolean expectHit = f >= -0.4 && f <= VoSub.GS_VO_SWING_R
                    && Math.abs(l) <= VoSub.GS_VO_SWING_W * 0.5
                    && Math.abs(dy - 0.1) <= VoSub.GS_VO_SWING_H;
            // 边界上的那一两格（3.0 / ±1.5 / 1.7）浮点投影可能落在哪一侧都不奇怪
            //   ⇒ 只在**离边界 > 1e-6** 的格子上要求解析期望成立；
            //     而 JS vs Java 的**一致性**对每一格都要求。
            boolean offBoundary = Math.abs(f + 0.4) > 1e-6 && Math.abs(f - VoSub.GS_VO_SWING_R) > 1e-6
                    && Math.abs(Math.abs(l) - VoSub.GS_VO_SWING_W * 0.5) > 1e-6
                    && Math.abs(Math.abs(dy - 0.1) - VoSub.GS_VO_SWING_H) > 1e-6;
            int jsDmgCount = (int) r.getMember("dmg").getArraySize();

            Rec rec = new Rec();
            rec.at(0.5, 64.0, -3.25, d[0], d[1], d[2]);
            rec.cands.add(new Object[]{MOB_ID, "ZOMBIE", m[4], m[5], m[6], 20.0, false});
            javaVoSwing(rec, 1);
            int javaDmgCount = rec.dmg.size();

            if (jsDmgCount != javaDmgCount) {
                mismatch++;
                hitOk = false;
            }
            if (offBoundary && expectHit != (jsDmgCount == 1)) {
                hitOk = false;
            }
            if (jsDmgCount == 1) {
                hitCount++;
            } else {
                missCount++;
            }

            // 特效点：14 段弧光
            Value jsPtc = r.getMember("ptc");
            List<double[]> jp = pts(jsPtc);
            if (jp.size() != rec.ptc.size() || !ptsKey(jp).equals(ptsKey(rec.ptc))) {
                fxOk = false;
            }

            // 搜索盒：中心 = 脚 + 1.0，半长 = (R+0.8, H, R+0.8)
            Value jsBox = r.getMember("boxes");
            if (jsBox.getArraySize() != 1) {
                boxOk = false;
            } else {
                Value bx = jsBox.getArrayElement(0);
                double[] jb = new double[]{bx.getArrayElement(0).asDouble(), bx.getArrayElement(1).asDouble(),
                        bx.getArrayElement(2).asDouble(), bx.getArrayElement(3).asDouble(),
                        bx.getArrayElement(4).asDouble(), bx.getArrayElement(5).asDouble()};
                if (rec.boxes.size() != 1 || !rowsKey(List.of(jb)).equals(rowsKey(rec.boxes))) {
                    boxOk = false;
                }
            }

            // 音效
            Value jsSnd = r.getMember("snd");
            if (jsSnd.getArraySize() != rec.snd.size() || (jsSnd.getArraySize() > 0
                    && !String.valueOf(jsSnd.getArrayElement(0).getArrayElement(0))
                    .equals(String.valueOf(rec.snd.get(0)[0])))) {
                sndOk = false;
            }
        }
        check("★★ Vo 挥砍命中判定：真 JS 与 Java 逐格一致（" + cases + " 组：命中 " + hitCount
                + " / 未命中 " + missCount + "，不一致 " + mismatch + "）", hitOk);
        check("★★ Vo 挥砍 14 段弧光粒子逐点一致（" + cases + " 组）", fxOk);
        check("★★ Vo 挥砍搜索盒（中心 = 脚 + 1.0、半长 = (3.8, 1.6, 3.8)）一致", boxOk);
        check("★ Vo 挥砍音效 entity.player.attack.sweep 0.75/1.25 一致", sndOk);
        check("★ 网格确实同时覆盖了命中和未命中（不是一边倒）", hitCount > 0 && missCount > 0);
    }

    private static final String MOB_ID = "00000000-0000-0000-0000-0000000000e2";

    /** Java 侧跑一次 {@code gsVoSwing}；返回 {@code [count]}（伤害笔数另从 rec 读）。 */
    private static double[] javaVoSwing(Rec r, int n) {
        ListenerCore core = new ListenerCore();
        VoSub vo = new VoSub(core);
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        if (n > 0) {
            VoSub.VoSt st = new VoSub.VoSt();
            st.n = n;
            st.until = ListenerCore.now() + 1000;
            vo.GS_VO_ST.put(r.selfId, st);
        }
        vo.gsVoSwing(p);
        VoSub.VoSt st = vo.GS_VO_ST.get(r.selfId);
        return new double[]{st == null ? 0 : st.n};
    }

    // ---------- C5 此刻万籁俱寂：左键斩击 ----------

    /** 与脚本 8851~8863 行逐字相同的三维正交基（只用来**摆放**候选怪，不参与判定）。 */
    private static double[] siBasis(double dx, double dy, double dz) {
        double fx = dx, fy = dy, fz = dz;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0e-4) {
            fx = 0.0;
            fy = 0.0;
            fz = 1.0;
            fl = 1.0;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;
        double rx = -fz, ry = 0.0, rz = fx;
        double rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1.0e-4) {
            rx = 1.0;
            ry = 0.0;
            rz = 0.0;
        } else {
            rx /= rl;
            rz /= rl;
        }
        double ux = ry * fz - rz * fy;
        double uy = rz * fx - rx * fz;
        double uz = rx * fy - ry * fx;
        return new double[]{fx, fy, fz, rx, ry, rz, ux, uy, uz};
    }

    private static void siSwing(Context ctx) {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {0, 1, 0}, {0, -1, 0}, {0.6, 0.8, 0}, {0, 0.5, 0.8660254037844386},
        };
        double[] fwds = {-0.5, -0.4, 0.0, 2.5, 4.9999, 5.0, 5.0001, 7.0};
        double[] lats = {-1.6, -1.5, 0.0, 1.5, 1.6};
        double[] ups = {-1.1, -1.0, 0.0, 1.0, 1.1};

        int cases = 0;
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        for (int di = 0; di < dirs.length; di++) {
            double[] d = dirs[di];
            for (double f : fwds) {
                for (double l : lats) {
                    for (double up : ups) {
                        if (((int) Math.abs(f * 5 + l * 3 + up * 7)) % 3 != 0) {
                            continue;
                        }
                        double sx = 0.5, sy = 64.0, sz = -3.25;
                        double[] b = siBasis(d[0], d[1], d[2]);
                        double px0 = sx, py0 = sy + 1.0, pz0 = sz;
                        double ex = px0 + b[0] * f + b[3] * l + b[6] * up;
                        double ez = pz0 + b[2] * f + b[5] * l + b[8] * up;
                        double ey = py0 - 0.9 + b[1] * f + b[4] * l + b[7] * up;
                        if (cases > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"sx\":").append(sx).append(",\"sy\":").append(sy).append(",\"sz\":").append(sz)
                                .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1])
                                .append(",\"dz\":").append(d[2]).append(",\"n\":1,\"cands\":[")
                                .append("{\"id\":\"").append(MOB_ID).append("\",\"type\":\"ZOMBIE\",\"x\":")
                                .append(ex).append(",\"y\":").append(ey).append(",\"z\":").append(ez)
                                .append(",\"noHealth\":false,\"dead\":false}]}");
                        meta.add(new double[]{di, f, l, up, ex, ey, ez});
                        cases++;
                    }
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = jsCall(ctx, "__ARG = JSON.parse(__ARG); (function(){ var r = [];"
                + " for (var i = 0; i < __ARG.length; i++) { r.push(__siSwing(__ARG[i])); } return r; })()");

        boolean hitOk = true;
        boolean fxOk = true;
        boolean boxOk = true;
        boolean sndOk = true;
        int hitCount = 0;
        int mismatch = 0;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[] m = meta.get(i);
            double[] d = dirs[(int) m[0]];
            double f = m[1], l = m[2], up = m[3];
            boolean expectHit = f >= -0.4 && f <= SiSub.GS_SI_RANGE
                    && Math.abs(l) <= SiSub.GS_SI_WIDE * 0.5
                    && Math.abs(up) <= SiSub.GS_SI_H * 0.5;
            // 边界格（5.0 / ±1.5 / ±1.0）上的浮点投影落在哪一侧都不奇怪
            //   ⇒ 解析期望只在离边界 > 1e-6 的格子上要求；JS vs Java 一致性每格都要求。
            boolean offBoundary = Math.abs(f + 0.4) > 1e-6 && Math.abs(f - SiSub.GS_SI_RANGE) > 1e-6
                    && Math.abs(Math.abs(l) - SiSub.GS_SI_WIDE * 0.5) > 1e-6
                    && Math.abs(Math.abs(up) - SiSub.GS_SI_H * 0.5) > 1e-6;

            Rec rec = new Rec();
            rec.at(0.5, 64.0, -3.25, d[0], d[1], d[2]);
            rec.cands.add(new Object[]{MOB_ID, "ZOMBIE", m[4], m[5], m[6], 20.0, false});
            javaSiSwing(rec, 1, null);
            boolean jsHit = r.getMember("dmg").getArraySize() == 1;
            boolean javaHit = rec.dmg.size() == 1;
            if (jsHit != javaHit) {
                mismatch++;
                hitOk = false;
            }
            if (offBoundary && expectHit != jsHit) {
                hitOk = false;
            }
            if (jsHit) {
                hitCount++;
            }

            if (!ptsKey(pts(r.getMember("ptc"))).equals(ptsKey(rec.ptc))) {
                fxOk = false;
            }
            Value jsBox = r.getMember("boxes");
            if (jsBox.getArraySize() != 1 || rec.boxes.size() != 1) {
                boxOk = false;
            } else {
                Value bx = jsBox.getArrayElement(0);
                double[] jb = new double[6];
                for (int k = 0; k < 6; k++) {
                    jb[k] = bx.getArrayElement(k).asDouble();
                }
                if (!rowsKey(List.of(jb)).equals(rowsKey(rec.boxes))) {
                    boxOk = false;
                }
            }
            Value jsSnd = r.getMember("snd");
            if (jsSnd.getArraySize() != rec.snd.size()) {
                sndOk = false;
            }
        }
        check("★★ Si 斩击命中判定：真 JS 与 Java 逐格一致（" + cases + " 组：命中 " + hitCount
                + "，不一致 " + mismatch + "）", hitOk);
        check("★★ Si 斩击 18 段弧线粒子逐点一致（" + cases + " 组）", fxOk);
        check("★★ Si 斩击搜索盒（中心 = 胸口、半长 = GS_SI_RANGE+1 = 6.0 立方体）一致", boxOk);
        check("★ Si 斩击音效 entity.player.attack.sweep 0.8/1.1 一致", sndOk);
        check("★ 网格覆盖了命中与未命中两侧", hitCount > 0 && hitCount < cases);

        // 斜劈交替：side 0 → 1 → 0
        Value sides = jsCall(ctx, "(function(){ var o = []; GS_SI_SIDE = {};"
                + " for (var i = 0; i < 4; i++) { __reset([]); GS_SI_SWING_AT = {};"
                + " var p = __mkSelf(0,64,0,0,0,1);"
                + " gsSiSwing(p); o.push(GS_SI_SIDE[__SELF]); } return o; })()");
        Rec rec2 = new Rec();
        ListenerCore core2 = new ListenerCore();
        SiSub si2 = new SiSub(core2);
        World w2 = mockWorld(rec2);
        Player p2 = mockPlayer(rec2, w2);
        List<Integer> javaSides = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            // 真 JS 侧每轮都 __reset（清 GS_SI_SWING_AT）；Java 侧必须同样清，
            // 否则第 2~4 刀落在 320ms 挥砍冷却里直接 return，GS_SI_SIDE 根本没写。
            si2.GS_SI_SWING_AT.clear();
            si2.GS_SI_ST.clear();
            si2.gsSiSwing(p2);
            javaSides.add(si2.GS_SI_SIDE.get(SELF_ID));
        }
        StringBuilder jsSideStr = new StringBuilder();
        for (long i = 0; i < sides.getArraySize(); i++) {
            jsSideStr.append(sides.getArrayElement(i).asInt());
        }
        check("★ Si 斜劈左右交替（真 JS " + jsSideStr + " / Java " + javaSides
                        + "；写进去的是**下一次**的值，所以首刀就是 1）",
                jsSideStr.toString().equals("1010") && javaSides.toString().equals("[1, 0, 1, 0]"));

        // 冷却闸
        // ★ 网格里只用**离刀口远**的值：Java 侧 gsXxSwing 会自己再取一次真实时钟，
        //   「上次触发在 319ms 前」这种刀口值会漂到 320 ⇒ 偶发翻车。
        //   精确刀口钉在真 JS 上（__now 冻结）。
        Integer[][] siGates = {{null, null}, {100, null}, {400, null}, {1000, null},
                {null, 100}, {null, 15000}, {null, 20000}, {100, 100}};
        Value jsGate = jsCall(ctx, "__siGateGrid(" + gateCases(siGates) + ")");
        List<String> jsG = strRows2(jsGate);
        List<String> javaG = new ArrayList<>();
        for (Integer[] c : siGates) {
            javaG.add(javaSiGateRow(c[0], c[1]));
        }
        check("★ Si 挥砍 / 寂灭冷却闸全网格一致（" + siGates.length + " 组）", jsG.equals(javaG));
        if (!jsG.equals(javaG)) {
            System.out.println("      · JS  : " + jsG);
            System.out.println("      · Java: " + javaG);
        }
        // ★ N = 18 ⇒ 挥砍弧线 i 从 0 到 18 闭区间 = **19** 个点；寂灭另画 144 个点。
        //   为了只看挥砍那一道闸，把寂灭也一起关掉（burstAgo = 0 ⇒ 15 秒冷却里）。
        check("★★ Si 挥砍冷却 320ms 刀口（真 JS）：319ms 拒绝 / 320ms 放行",
                strRows2(jsCall(ctx, "__siGateGrid([[319,0],[320,0]])")).get(0).equals("[0,0,0,0]")
                        && strRows2(jsCall(ctx, "__siGateGrid([[319,0],[320,0]])")).get(1).equals("[19,1,0,0]"));
        check("★ Si 挥砍冷却（Java 侧同结论，用离刀口的值）",
                javaSiGateRow(100, 0).equals("[0,0,0,0]") && javaSiGateRow(400, 0).equals("[19,1,0,0]"));
    }

    private static String gateCases(Integer[][] g) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < g.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(g[i][0] == null ? "null" : g[i][0]).append(',')
                    .append(g[i][1] == null ? "null" : g[i][1]).append(']');
        }
        return sb.append(']').toString();
    }

    private static List<String> strRows2(Value v) {
        List<String> out = new ArrayList<>();
        for (long i = 0; i < v.getArraySize(); i++) {
            Value row = v.getArrayElement(i);
            StringBuilder sb = new StringBuilder("[");
            for (int j = 0; j < row.getArraySize(); j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(num(row.getArrayElement(j).asDouble()));
            }
            out.add(sb.append(']').toString());
        }
        return out;
    }

    private static void javaSiSwing(Rec r, int n, Long burstAgo) {
        ListenerCore core = new ListenerCore();
        SiSub si = new SiSub(core);
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        if (n > 0) {
            SiSub.SiSt st = new SiSub.SiSt();
            st.n = n;
            st.until = ListenerCore.now() + 1000;
            si.GS_SI_ST.put(r.selfId, st);
        }
        si.gsSiSwing(p);
    }

    private static String javaSiGateRow(Integer swingAgo, Integer burstAgo) {
        ListenerCore core = new ListenerCore();
        SiSub si = new SiSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        long now = ListenerCore.now();
        if (swingAgo != null) {
            si.GS_SI_SWING_AT.put(SELF_ID, now - swingAgo);
        }
        if (burstAgo != null) {
            si.GS_SI_BURST_AT.put(SELF_ID, now - burstAgo);
        }
        si.gsSiSwing(p);
        boolean ok = si.gsSiBurst(p);
        return "[" + r.ptc.size() + "," + r.snd.size() + "," + (ok ? 1 : 0) + ","
                + si.GS_SI_BURST_FX.size() + "]";
    }

    // ---------- C6 寂灭 ----------

    private static void siBurst(Context ctx) {
        // 半径 7 / 竖直 7 的扁圆柱；伤害 = gsSiDmg(层数) × 2；右键**不叠层**
        double[] rads = {0.0, 3.0, 6.9999, 7.0, 7.0001, 10.0};
        double[] dys = {-7.0001, -7.0, -3.0, 0.0, 3.0, 7.0, 7.0001};
        int[] ns = {0, 1, 5, 99};

        int cases = 0;
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        for (int n : ns) {
            for (double rad : rads) {
                for (double dy : dys) {
                    double sx = 0.5, sy = 64.0, sz = -3.25;
                    double ex = sx + rad;
                    double ez = sz;
                    double ey = sy + dy;
                    if (cases > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"sx\":").append(sx).append(",\"sy\":").append(sy).append(",\"sz\":").append(sz)
                            .append(",\"dx\":0,\"dy\":0,\"dz\":1,\"n\":").append(n).append(",\"cands\":[")
                            .append("{\"id\":\"").append(MOB_ID).append("\",\"type\":\"ZOMBIE\",\"x\":")
                            .append(ex).append(",\"y\":").append(ey).append(",\"z\":").append(ez)
                            .append(",\"noHealth\":false,\"dead\":false}]}");
                    meta.add(new double[]{n, rad, dy});
                    cases++;
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = jsCall(ctx, "__ARG = JSON.parse(__ARG); (function(){ var r = [];"
                + " for (var i = 0; i < __ARG.length; i++) { r.push(__siBurst(__ARG[i])); } return r; })()");

        boolean hitOk = true;
        boolean dmgOk = true;
        boolean stackOk = true;
        boolean ringOk = true;
        int hitCount = 0;
        int mismatch = 0;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[] m = meta.get(i);
            int n = (int) m[0];
            double rad = m[1], dy = m[2];
            // ★ 判定原点 = 玩家**脚**（py），搜索盒中心 = py + 1.0
            //   ⇒ 两条约束：① 搜索盒 |Δx| ≤ 7 且 |Δy - 1| ≤ 7；② 圆圈 rad² ≤ 49 且 |Δy| ≤ 7
            //   合起来竖直方向实际是 [-6, 7]（dy = -7 被搜索盒挡掉）。
            boolean inBox = Math.abs(rad) <= SiSub.GS_SI_BURST_R
                    && Math.abs(dy - 1.0) <= SiSub.GS_SI_BURST_R;
            boolean inCircle = rad * rad <= SiSub.GS_SI_BURST_R * SiSub.GS_SI_BURST_R
                    && Math.abs(dy) <= SiSub.GS_SI_BURST_R;
            boolean expectHit = inBox && inCircle;
            boolean offBoundary = Math.abs(Math.abs(rad) - SiSub.GS_SI_BURST_R) > 1e-6
                    && Math.abs(Math.abs(dy) - SiSub.GS_SI_BURST_R) > 1e-6
                    && Math.abs(Math.abs(dy - 1.0) - SiSub.GS_SI_BURST_R) > 1e-6;

            Rec rec = new Rec();
            rec.at(0.5, 64.0, -3.25, 0, 0, 1);
            rec.cands.add(new Object[]{MOB_ID, "ZOMBIE", 0.5 + rad, 64.0 + dy, -3.25, 20.0, false});
            javaSiBurst(rec, n);
            boolean jsHit = r.getMember("dmg").getArraySize() == 1;
            boolean javaHit = rec.dmg.size() == 1;
            if (jsHit != javaHit) {
                mismatch++;
                hitOk = false;
            }
            if (offBoundary && expectHit != jsHit) {
                hitOk = false;
            }
            if (jsHit) {
                hitCount++;
            }

            // 伤害 = gsSiDmg(n) × 2（n 会被夹到 ≥ 1）
            double want = SiSub.gsSiDmg(n) * SiSub.GS_SI_BURST_MUL;
            if (jsHit && r.getMember("dmg").getArrayElement(0).getArrayElement(1).asDouble() != want) {
                dmgOk = false;
            }
            if (javaHit && ((Number) rec.dmg.get(0)[1]).doubleValue() != want) {
                dmgOk = false;
            }

            // 右键**不叠层**
            int jsN = r.getMember("n").asInt();
            SiSub.SiSt st = null;
            if (jsN != n) {
                stackOk = false;
            }
            // 圆圈特效：96 个点（每点 1 个，偶数索引再加胸口圈）
            if (r.getMember("ptc").getArraySize() != ringOkCount()) {
                ringOk = false;
            }
        }
        check("★★ 寂灭命中判定：真 JS 与 Java 逐格一致（" + cases + " 组：命中 " + hitCount
                + "，不一致 " + mismatch + "）", hitOk);
        check("★★ 寂灭伤害 = gsSiDmg(层数) × 2（0 层保底 34 点）", dmgOk);
        check("★★ 寂灭右键**不叠层**（层数原样）", stackOk);
        check("★ 寂灭圆圈特效点数 = " + ringOkCount() + "（96 点 / 偶数索引补胸口圈）", ringOk);
        check("★ 寂灭 0 层时伤害 = 34.0", SiSub.gsSiDmg(0) * SiSub.GS_SI_BURST_MUL == 34.0);
        check("★ 寂灭满层（99）伤害 = 426.0", SiSub.gsSiDmg(99) * SiSub.GS_SI_BURST_MUL == 426.0);

        // 冷却 15 秒
        Value jsCd = jsCall(ctx, "__siGateGrid([[null,null],[null,14999],[null,15000],[null,15001]])");
        List<String> jsRows = strRows2(jsCd);
        check("★ 寂灭 15 秒冷却：14999ms 拒绝 / 15000ms 放行（真 JS）",
                jsRows.get(1).contains(",0,") && jsRows.get(2).contains(",1,")
                        && jsRows.get(3).contains(",1,"));
        check("★ 寂灭 15 秒冷却（Java 侧同结论）",
                javaSiGateRow(null, 14999).contains(",0,") && javaSiGateRow(null, 15000).contains(",1,")
                        && javaSiGateRow(null, 15001).contains(",1,"));
    }

    private static int ringOkCount() {
        int n = SiSub.GS_SI_BURST_FX_N;
        return n + (n + 1) / 2;
    }

    private static void javaSiBurst(Rec r, int n) {
        ListenerCore core = new ListenerCore();
        SiSub si = new SiSub(core);
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        if (n > 0) {
            SiSub.SiSt st = new SiSub.SiSt();
            st.n = n;
            st.until = ListenerCore.now() + 1000;
            si.GS_SI_ST.put(r.selfId, st);
        }
        si.gsSiBurst(p);
    }

    // ---------- C7 阎魔刀·改：朝向基 + 六刀形状 ----------

    private static void emBasisAndShapes(Context ctx) {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {-0.7071067811865476, 0, 0.7071067811865476},
                {0.6, 0.8, 0}, {0.3, -0.4, 0.8660254037844386},
                {0, 0, 1.0e-5}, {0, 1.0e-7, 0}, {0.5, 0.5, 0.7071067811865476},
                {2, 0, 0}, {0, 0, 5},
        };
        // 每个方向配 3 个 yaw（正上/正下看时退化用 yaw 那一支）
        double[] yaws = {0, 90, -45};
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        int k = 0;
        for (double[] d : dirs) {
            for (double yaw : yaws) {
                if (k++ > 0) {
                    sb.append(',');
                }
                sb.append('[').append(d[0]).append(',').append(d[1]).append(',').append(d[2])
                        .append(',').append(yaw).append(']');
                meta.add(new double[]{d[0], d[1], d[2], yaw});
            }
        }
        sb.append(']');
        Value js = jsCall(ctx, "__basisGrid(" + sb + ")");
        boolean ok = true;
        for (int i = 0; i < meta.size(); i++) {
            double[] m = meta.get(i);
            EmSub.Frame f = EmSub.gsEmBasis(
                    new FakeLoc(null, 0, 0, 0, (float) m[3], 0f, new Vector(m[0], m[1], m[2])));
            double[] jv = {f.fx, f.fy, f.fz, f.rx, f.ry, f.rz, f.ux, f.uy, f.uz};
            Value row = js.getArrayElement(i);
            for (int j = 0; j < 9; j++) {
                if (row.getArrayElement(j).asDouble() != jv[j]) {
                    ok = false;
                }
            }
        }
        check("★★ gsEmBasis 三维朝向基全网格逐值相同（" + meta.size() + " 组方向 × yaw）", ok);

        // 六刀形状
        Value shapes = jsCall(ctx, "__shapeGrid([0,1,2,3,4,5])");
        boolean shapeOk = true;
        StringBuilder jsShape = new StringBuilder();
        StringBuilder javaShape = new StringBuilder();
        for (int idx = 0; idx <= 5; idx++) {
            List<EmSub.Shape> jl = EmSub.gsEmShape(idx);
            double[] jf = flatten(jl);
            Value row = shapes.getArrayElement(idx);
            double[] jsv = new double[(int) row.getArraySize()];
            for (int i = 0; i < jsv.length; i++) {
                jsv[i] = row.getArrayElement(i).asDouble();
            }
            jsShape.append(Arrays.toString(jsv)).append('\n');
            javaShape.append(Arrays.toString(jf)).append('\n');
            if (!Arrays.equals(jsv, jf)) {
                shapeOk = false;
            }
        }
        check("★★ gsEmShape(0..5) 六刀形状逐值相同（圆弧圆心/半径/倾角/起止角 + 三条弧的两端与控制点）",
                shapeOk);
        if (!shapeOk) {
            System.out.println("      · JS  :\n" + jsShape);
            System.out.println("      · Java:\n" + javaShape);
        }
        check("★ 第 1~4 刀都是**圆弧**（cir = 1），第 5 刀是 3 条贝塞尔弧，第 6 刀为空（单独处理）",
                EmSub.gsEmShape(0).get(0).cir && EmSub.gsEmShape(1).get(0).cir
                        && EmSub.gsEmShape(2).get(0).cir && EmSub.gsEmShape(3).get(0).cir
                        && !EmSub.gsEmShape(4).get(0).cir && EmSub.gsEmShape(4).size() == 3
                        && EmSub.gsEmShape(5).isEmpty());
        check("★ 第 5 刀三条弧的弧顶都落在同一个点 (GS_EM_S5_PF, PL, PH) = (4.6, 0, 0)",
                EmSub.GS_EM_S5_PF == 4.6 && EmSub.GS_EM_S5_PL == 0.0 && EmSub.GS_EM_S5_PH == 0.0
                        && apexOf(EmSub.gsEmShape(4).get(0))[0] == 4.6
                        && apexOf(EmSub.gsEmShape(4).get(1))[0] == 4.6
                        && apexOf(EmSub.gsEmShape(4).get(2))[0] == 4.6);
        check("★ 第 5 刀三条弧的弧顶互相重合（「顶点相撞」）",
                Arrays.equals(apexOf(EmSub.gsEmShape(4).get(0)), apexOf(EmSub.gsEmShape(4).get(1)))
                        && Arrays.equals(apexOf(EmSub.gsEmShape(4).get(1)), apexOf(EmSub.gsEmShape(4).get(2))));

        // 圆弧采样点
        double[][] circCases = {
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.2, 0, 0, 2.6, 0, 180, 0, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 3.6, 0, 0, 2.4, 90, 0, 180, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.2, 0, 0, 2.8, -45, 0, 180, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.6, 0, 0, 3.0, 90, 180, 0, 18, 1.0},
                {1.5, 70, -2, 0, 0.6, 0.8, -0.8, 0, 0.6, 0, 1, 0, 2.2, 0, 0, 2.6, 0, 180, 0, 18, 0.3},
                {1.5, 70, -2, 0, 0.6, 0.8, -0.8, 0, 0.6, 0, 1, 0, 2.2, 0, 0, 2.6, 0, 180, 0, 18, 0.44},
                {1.5, 70, -2, 0, 0.6, 0.8, -0.8, 0, 0.6, 0, 1, 0, 2.2, 0, 0, 2.6, 0, 180, 0, 18, 0.45},
        };
        Value jsCirc = jsCall(ctx, "__circGrid(" + circLiteral(circCases) + ")");
        boolean circOk = true;
        for (int i = 0; i < circCases.length; i++) {
            double[] c = circCases[i];
            Rec rec = new Rec();
            World w = mockWorld(rec);
            EmSub.Frame b = frame(w, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                    c[9], c[10], c[11]);
            EmSub.Shape sh = EmSub.gsEmCircle(c[12], c[13], c[14], c[15], c[16], c[17], c[18]);
            new EmSub(new ListenerCore()).gsEmCircDraw(w, b, sh, (int) c[19], c[20]);
            if (!ptsKey(pts(jsCirc.getArrayElement(i))).equals(ptsKey(rec.ptc))) {
                circOk = false;
            }
        }
        check("★★ 圆弧采样点逐点相同（含 fade < 0.45 = 隔点画那一档）", circOk);

        // 贝塞尔弧采样点
        double[][] arcCases = {
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.2, -2.4, 1.4, 2.2, 2.4, -1.4, 4.6, 0, 0, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.2, 2.4, 1.4, 2.2, -2.4, -1.4, 4.6, 0, 0, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 3.0, 0, -1.6, 3.0, 0, 1.6, 4.6, 0, 0, 18, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 2.2, -2.4, 1.4, 2.2, 2.4, -1.4, 4.6, 0, 0, 18, 0.2},
        };
        Value jsArc = jsCall(ctx, "__arcGrid(" + circLiteral(arcCases) + ")");
        boolean arcOk = true;
        for (int i = 0; i < arcCases.length; i++) {
            double[] c = arcCases[i];
            Rec rec = new Rec();
            World w = mockWorld(rec);
            EmSub.Frame b = frame(w, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                    c[9], c[10], c[11]);
            EmSub.Shape sh = EmSub.gsEmArcTo(new double[]{c[12], c[13], c[14]},
                    new double[]{c[15], c[16], c[17]}, new double[]{c[18], c[19], c[20]});
            new EmSub(new ListenerCore()).gsEmArcDraw(w, b, sh, (int) c[21], c[22]);
            if (!ptsKey(pts(jsArc.getArrayElement(i))).equals(ptsKey(rec.ptc))) {
                arcOk = false;
            }
        }
        check("★★ 二次贝塞尔弧采样点逐点相同（第 5 刀三条弧）", arcOk);

        // 第 6 刀圆圈
        //   ★ 第 4 组必须用**标准正交**的 (f, r, u)：圆圈半径恒为 R 的前提就是
        //     e1 = r·cos + u·sin 与 f 正交且都是单位向量。
        //     随手写一组不正交的基，画出来的圆会被拉成椭圆（半径 > R），那是探针的错。
        double[][] s6 = {
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0.44},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0.45},
                {2, 70, -1, 0.6, 0, 0.8, -0.8, 0, 0.6, 0, 1, 0, 1.0},
        };
        Value jsS6 = jsCall(ctx, "__slash6Grid(" + circLiteral(s6) + ")");
        boolean s6Ok = true;
        boolean orthoOk = true;
        double maxR = 0.0;
        for (int i = 0; i < s6.length; i++) {
            double[] c = s6[i];
            // 这四组基都必须是标准正交的（否则半径断言没意义）
            double[] fv = {c[3], c[4], c[5]}, rv = {c[6], c[7], c[8]}, uv = {c[9], c[10], c[11]};
            if (Math.abs(dot(fv, fv) - 1.0) > 1e-9 || Math.abs(dot(rv, rv) - 1.0) > 1e-9
                    || Math.abs(dot(uv, uv) - 1.0) > 1e-9 || Math.abs(dot(fv, rv)) > 1e-9
                    || Math.abs(dot(fv, uv)) > 1e-9 || Math.abs(dot(rv, uv)) > 1e-9) {
                orthoOk = false;
            }
            Rec rec = new Rec();
            World w = mockWorld(rec);
            EmSub.Frame b = frame(w, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                    c[9], c[10], c[11]);
            new EmSub(new ListenerCore()).gsEmDrawSlash6(w, b, c[12]);
            if (!ptsKey(pts(jsS6.getArrayElement(i))).equals(ptsKey(rec.ptc))) {
                s6Ok = false;
            }
            for (double[] p : rec.ptc) {
                double dx = p[0] - c[0], dy = p[1] - c[1], dz = p[2] - c[2];
                maxR = Math.max(maxR, Math.sqrt(dx * dx + dy * dy + dz * dz));
            }
        }
        check("★★ 第 6 刀圆圈采样点逐点相同（含 fade 两档 + 倾斜坐标系）", s6Ok);
        check("★ 第 6 刀那几组基都是标准正交的（半径断言的前提）", orthoOk);
        check("★★ 第 6 刀圆圈半径恒为 GS_EM_S6_R = 4.5（实测最大 " + String.format("%.4f", maxR) + "）",
                Math.abs(maxR - EmSub.GS_EM_S6_R) < 1.0e-9);
        check("★ 第 6 刀采样点数 = GS_EM_S6_N = 64（fade 1.0 时）", s6PointCount(1.0) == 64);
        check("★ 第 6 刀 fade < 0.45 时隔点画 = 32 点", s6PointCount(0.44) == 32);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static int s6PointCount(double fade) {
        Rec rec = new Rec();
        World w = mockWorld(rec);
        EmSub.Frame b = frame(w, 0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0);
        new EmSub(new ListenerCore()).gsEmDrawSlash6(w, b, fade);
        return rec.ptc.size();
    }

    private static double[] flatten(List<EmSub.Shape> l) {
        List<Double> out = new ArrayList<>();
        out.add((double) l.size());
        for (EmSub.Shape s : l) {
            if (s.cir) {
                out.add(1.0);
                out.add(s.o[0]);
                out.add(s.o[1]);
                out.add(s.o[2]);
                out.add(s.r);
                out.add(s.tilt);
                out.add(s.a0);
                out.add(s.a1);
            } else {
                out.add(0.0);
                for (int i = 0; i < 3; i++) {
                    out.add(s.a[i]);
                }
                for (int i = 0; i < 3; i++) {
                    out.add(s.b[i]);
                }
                for (int i = 0; i < 3; i++) {
                    out.add(s.c[i]);
                }
            }
        }
        double[] r = new double[out.size()];
        for (int i = 0; i < r.length; i++) {
            r[i] = out.get(i);
        }
        return r;
    }

    /** 二次贝塞尔弧的弧顶 = (a + b)/4 + c/2（脚本注释里的公式）。 */
    private static double[] apexOf(EmSub.Shape s) {
        double[] r = new double[3];
        for (int i = 0; i < 3; i++) {
            r[i] = (s.a[i] + s.b[i]) * 0.25 + s.c[i] * 0.5;
        }
        return r;
    }

    private static String circLiteral(double[][] cs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            for (int j = 0; j < cs[i].length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(cs[i][j]);
            }
            sb.append(']');
        }
        return sb.append(']').toString();
    }

    private static EmSub.Frame frame(World w, double ox, double oy, double oz,
                                     double fx, double fy, double fz,
                                     double rx, double ry, double rz,
                                     double ux, double uy, double uz) {
        EmSub.Frame f = new EmSub.Frame();
        f.w = w;
        f.ox = ox;
        f.oy = oy;
        f.oz = oz;
        f.fx = fx;
        f.fy = fy;
        f.fz = fz;
        f.rx = rx;
        f.ry = ry;
        f.rz = rz;
        f.ux = ux;
        f.uy = uy;
        f.uz = uz;
        return f;
    }

    // ---------- C8 阎魔刀·改：出刀 / 次元斩 ----------

    private static void emSwing(Context ctx) {
        // 六刀循环 + 命中判定（宽 5 × 长 9 × 高 3，容差 +0.6）
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0},
                {0.6, 0.8, 0}, {0.3, -0.4, 0.8660254037844386},
        };
        double[] fwds = {-0.5, -0.4, 0.0, 4.5, 8.9999, 9.0, 9.0001, 12.0};
        double[] lats = {-2.6, -2.5, 0.0, 2.5, 2.6};
        double[] ups = {-2.2, -2.1, 0.0, 2.1, 2.2};

        int cases = 0;
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        for (int di = 0; di < dirs.length; di++) {
            double[] d = dirs[di];
            for (double f : fwds) {
                for (double l : lats) {
                    for (double up : ups) {
                        if (((int) Math.abs(f * 5 + l * 3 + up * 7)) % 3 != 0) {
                            continue;
                        }
                        double sx = 0.5, sy = 64.0, sz = -3.25;
                        double[] b = siBasis(d[0], d[1], d[2]);
                        double ox = sx, oy = sy + 1.62, oz = sz;
                        double ex = ox + b[0] * f + b[3] * l + b[6] * up;
                        double ez = oz + b[2] * f + b[5] * l + b[8] * up;
                        double ey = oy - 0.9 + b[1] * f + b[4] * l + b[7] * up;
                        if (cases > 0) {
                            sb.append(',');
                        }
                        sb.append("{\"sx\":").append(sx).append(",\"sy\":").append(sy).append(",\"sz\":").append(sz)
                                .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1])
                                .append(",\"dz\":").append(d[2]).append(",\"n\":1,\"cands\":[")
                                .append("{\"id\":\"").append(MOB_ID).append("\",\"type\":\"ZOMBIE\",\"x\":")
                                .append(ex).append(",\"y\":").append(ey).append(",\"z\":").append(ez)
                                .append(",\"noHealth\":false,\"dead\":false}]}");
                        meta.add(new double[]{di, f, l, up, ex, ey, ez});
                        cases++;
                    }
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = jsCall(ctx, "__ARG = JSON.parse(__ARG); (function(){ var r = [];"
                + " for (var i = 0; i < __ARG.length; i++) { r.push(__emSwing(__ARG[i])); } return r; })()");

        boolean hitOk = true;
        boolean fxOk = true;
        boolean boxOk = true;
        int hitCount = 0;
        int mismatch = 0;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[] m = meta.get(i);
            double[] d = dirs[(int) m[0]];
            double f = m[1], l = m[2], up = m[3];
            double halfH = EmSub.GS_EM_H * 0.5 + 0.6;
            // ★★ 判定前先要**被搜索盒捞出来**：盒心 = 眼 + 准星×4.5、半长 = (5.3, 3.1, 5.3)。
            //    倾斜视线时盒会把「形状上明明该中」的目标挡在外面 —— 那是真行为，不是探针的锅。
            double[] b = siBasis(d[0], d[1], d[2]);
            double ox = 0.5, oy = 64.0 + 1.62, oz = -3.25;
            double boxX = ox + b[0] * (EmSub.GS_EM_RANGE * 0.5);
            double boxY = oy + b[1] * (EmSub.GS_EM_RANGE * 0.5);
            double boxZ = oz + b[2] * (EmSub.GS_EM_RANGE * 0.5);
            double rxx = EmSub.GS_EM_RANGE * 0.5 + 0.8;
            double ryy = EmSub.GS_EM_H * 0.5 + 1.6;
            boolean inBox = Math.abs(m[4] - boxX) <= rxx && Math.abs(m[5] - boxY) <= ryy
                    && Math.abs(m[6] - boxZ) <= rxx;
            boolean inShape = f >= -0.4 && f <= EmSub.GS_EM_RANGE
                    && Math.abs(l) <= EmSub.GS_EM_WIDE * 0.5
                    && Math.abs(up) <= halfH;
            boolean expectHit = inBox && inShape;
            boolean offBoundary = Math.abs(f + 0.4) > 1e-6 && Math.abs(f - EmSub.GS_EM_RANGE) > 1e-6
                    && Math.abs(Math.abs(l) - EmSub.GS_EM_WIDE * 0.5) > 1e-6
                    && Math.abs(Math.abs(up) - halfH) > 1e-6
                    && Math.abs(Math.abs(m[4] - boxX) - rxx) > 1e-6
                    && Math.abs(Math.abs(m[5] - boxY) - ryy) > 1e-6
                    && Math.abs(Math.abs(m[6] - boxZ) - rxx) > 1e-6;

            Rec rec = new Rec();
            rec.at(0.5, 64.0, -3.25, d[0], d[1], d[2]);
            rec.cands.add(new Object[]{MOB_ID, "ZOMBIE", m[4], m[5], m[6], 20.0, false});
            javaEmSwing(rec, 1);
            boolean jsHit = r.getMember("dmg").getArraySize() >= 1;
            boolean javaHit = rec.dmg.size() >= 1;
            if (jsHit != javaHit) {
                mismatch++;
                hitOk = false;
            }
            if (offBoundary && expectHit != jsHit) {
                hitOk = false;
            }
            if (jsHit) {
                hitCount++;
            }
            if (!ptsKey(pts(r.getMember("ptc"))).equals(ptsKey(rec.ptc))) {
                fxOk = false;
            }
            Value jsBox = r.getMember("boxes");
            if (jsBox.getArraySize() != rec.boxes.size()) {
                boxOk = false;
            } else {
                for (int k = 0; k < rec.boxes.size(); k++) {
                    Value bx = jsBox.getArrayElement(k);
                    double[] jb = new double[6];
                    for (int q = 0; q < 6; q++) {
                        jb[q] = bx.getArrayElement(q).asDouble();
                    }
                    if (!rowsKey(List.of(jb)).equals(rowsKey(List.of(rec.boxes.get(k))))) {
                        boxOk = false;
                    }
                }
            }
        }
        check("★★ Em 出刀命中判定：真 JS 与 Java 逐格一致（" + cases + " 组：命中 " + hitCount
                + "，不一致 " + mismatch + "；形状宽5×长9×高3+0.6 容差，且先过搜索盒）", hitOk);
        check("★★ Em 出刀特效粒子逐点一致（六刀形状 + 淡出队列）", fxOk);
        check("★★ Em 出刀搜索盒一致（中心 = 眼 + 准星×4.5、半长 = (5.3, 3.1, 5.3)）", boxOk);

        // 六刀循环推进 + 音效 + 第 6 刀专属
        Value seq = jsCall(ctx, "(function(){ var o = [];"
                + " for (var i = 0; i < 7; i++) {"
                + "   __reset([]); GS_EM_ST = {}; GS_EM_SWING_AT = {}; GS_EM_FX = []; GS_EM_FIELDS = [];"
                + "   GS_EM_CRACKS = []; GS_EM_BARS = {};"
                + "   if (i > 0) { GS_EM_ST[__SELF] = { n: i, until: __now + 1000 }; }"
                + "   var p = __mkSelf(0,64,0,0,0,1); gsEmSwing(p);"
                + "   o.push([GS_EM_ST[__SELF] ? GS_EM_ST[__SELF].n : 0, __snd.length,"
                + "           GS_EM_FX.length, GS_EM_FIELDS.length, __ptc.length]); } return o; })()");
        List<String> jsSeq = strRows2(seq);
        List<String> javaSeq = new ArrayList<>();
        int[][] javaSeqNums = new int[7][];
        for (int i = 0; i < 7; i++) {
            Rec rec = new Rec();
            ListenerCore core = new ListenerCore();
            EmSub em = new EmSub(core);
            World w = mockWorld(rec);
            Player p = mockPlayer(rec, w);
            if (i > 0) {
                EmSub.EmSt st = new EmSub.EmSt();
                st.n = i;
                st.until = ListenerCore.now() + 1000;
                em.GS_EM_ST.put(SELF_ID, st);
            }
            em.gsEmSwing(p);
            EmSub.EmSt st = em.GS_EM_ST.get(SELF_ID);
            int[] nums = {st == null ? 0 : st.n, rec.snd.size(), em.GS_EM_FX.size(),
                    em.GS_EM_FIELDS.size(), rec.ptc.size()};
            javaSeqNums[i] = nums;
            javaSeq.add("[" + nums[0] + "," + nums[1] + "," + nums[2] + "," + nums[3] + "," + nums[4] + "]");
        }
        check("★★ 六刀一循环推进 + 音效 + 淡出队列 + 次元斩触发全网格一致（7 次出刀）",
                jsSeq.equals(javaSeq));
        if (!jsSeq.equals(javaSeq)) {
            System.out.println("      · JS  : " + jsSeq);
            System.out.println("      · Java: " + javaSeq);
        }
        check("★ 计数器走 1,2,3,4,5,6,1（第 6 刀之后回到第 1 刀）",
                javaSeqNums[0][0] == 1 && javaSeqNums[1][0] == 2 && javaSeqNums[2][0] == 3
                        && javaSeqNums[3][0] == 4 && javaSeqNums[4][0] == 5 && javaSeqNums[5][0] == 6
                        && javaSeqNums[6][0] == 1);
        check("★★ 只有第 6 刀才砍出次元斩（GS_EM_FIELDS 只在第 6 刀 +1；实际 "
                        + javaSeqNums[0][3] + "/" + javaSeqNums[4][3] + "/" + javaSeqNums[5][3]
                        + "/" + javaSeqNums[6][3] + "）",
                javaSeqNums[0][3] == 0 && javaSeqNums[4][3] == 0
                        && javaSeqNums[5][3] == 1 && javaSeqNums[6][3] == 0);
        check("★ 第 6 刀多响一声 entity.evoker.cast_spell（实际 前五刀 " + javaSeqNums[0][1]
                        + " 声 / 第 6 刀 " + javaSeqNums[5][1]
                        + " 声 = 挥砍 + 第6刀专属 + 次元斩两声）",
                javaSeqNums[0][1] == 1 && javaSeqNums[5][1] == 4);

        // 出刀冷却 200ms / 裂缝冷却 5 秒（★ 网格用离刀口远的值，刀口钉真 JS）
        Integer[][] emGates = {{null, null}, {100, null}, {400, null}, {1000, null},
                {null, 4000}, {null, 6000}, {null, 20000}};
        Value jsGate = jsCall(ctx, "__emGateGrid(" + gateCases(emGates) + ")");
        List<String> jsG = strRows2(jsGate);
        List<String> javaG = new ArrayList<>();
        for (Integer[] c : emGates) {
            javaG.add(javaEmGateRow(c[0], c[1]));
        }
        check("★ Em 出刀 / 裂缝冷却闸全网格一致（" + emGates.length + " 组）", jsG.equals(javaG));
        if (!jsG.equals(javaG)) {
            System.out.println("      · JS  : " + jsG);
            System.out.println("      · Java: " + javaG);
        }
        List<String> emExact = strRows2(jsCall(ctx,
                "__emGateGrid([[199,null],[200,null],[null,4999],[null,5000],[null,5001]])"));
        check("★★ Em 出刀冷却 200ms 刀口（真 JS）：199ms 拒绝 / 200ms 放行（" + emExact.get(0)
                        + " / " + emExact.get(1) + "）",
                emExact.get(0).startsWith("[0,") && !emExact.get(1).startsWith("[0,"));
        check("★★ Em 裂缝冷却 5 秒刀口（真 JS）：4999ms 拒绝 / 5000ms 放行 / 5001ms 放行",
                emExact.get(2).endsWith(",0]") && emExact.get(3).endsWith(",1]")
                        && emExact.get(4).endsWith(",1]"));
        check("★ Em 出刀冷却（Java 侧同结论，用离刀口的值）：100ms 拒绝 / 400ms 放行",
                javaEmGateRow(100, null).startsWith("[0,")
                        && javaEmGateRow(400, null).equals(javaEmGateRow(null, null)));
        check("★ Em 裂缝冷却（Java 侧同结论）：4000ms 拒绝 / 6000ms 放行",
                javaEmGateRow(null, 4000).endsWith(",0]") && javaEmGateRow(null, 6000).endsWith(",1]"));
    }

    private static void javaEmSwing(Rec r, int n) {
        ListenerCore core = new ListenerCore();
        EmSub em = new EmSub(core);
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        if (n > 0) {
            EmSub.EmSt st = new EmSub.EmSt();
            st.n = n;
            st.until = ListenerCore.now() + 1000;
            em.GS_EM_ST.put(r.selfId, st);
        }
        em.gsEmSwing(p);
    }

    private static String javaEmGateRow(Integer swingAgo, Integer crackAgo) {
        ListenerCore core = new ListenerCore();
        EmSub em = new EmSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        long now = ListenerCore.now();
        if (swingAgo != null) {
            em.GS_EM_SWING_AT.put(SELF_ID, now - swingAgo);
        }
        if (crackAgo != null) {
            em.GS_EM_CRACK_AT.put(SELF_ID, now - crackAgo);
        }
        em.gsEmSwing(p);
        em.gsEmCrackOpen(p);
        return "[" + r.ptc.size() + "," + r.snd.size() + "," + em.GS_EM_FX.size() + ","
                + em.GS_EM_CRACKS.size() + "]";
    }

    // ---------- C9 次元斩 ----------

    private static void emDimension(Context ctx) {
        double[][] bigs = {
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 64, 0, 0.0, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 64, 0, 0.5, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 64, 0, 0.39, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 64, 0, 0.4, 1.0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 64, 0, 1.0, 0.2},
                {1, 70, -2, 0.6, 0.8, 0, -0.8, 0, 0.6, 0, 1, 0, 4, 70, -2, 0.3, 0.6},
        };
        Value jsBig = jsCall(ctx, "__bigGrid(" + circLiteral(bigs) + ")");
        boolean bigOk = true;
        for (int i = 0; i < bigs.length; i++) {
            double[] c = bigs[i];
            Rec rec = new Rec();
            World w = mockWorld(rec);
            EmSub.Frame b = frame(w, c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8],
                    c[9], c[10], c[11]);
            new EmSub(new ListenerCore()).gsEmDrawBig(w, c[12], c[13], c[14], b, c[15], c[16]);
            if (!ptsKey(pts(jsBig.getArrayElement(i))).equals(ptsKey(rec.ptc))) {
                bigOk = false;
            }
        }
        check("★★ 次元斩大圆环采样点逐点相同（含 fade / rot 相位两档）", bigOk);

        // 大圆环：正圆（只用 r / u 两个基向量，没有 f 分量）= 半径恒为 GS_EM_DS_BIG
        Rec rec = new Rec();
        World w = mockWorld(rec);
        EmSub.Frame b = frame(w, 3, 64, 4, 0.6, 0.8, 0, -0.8, 0, 0.6, 0, 1, 0);
        new EmSub(new ListenerCore()).gsEmDrawBig(w, 3, 64, 4, b, 0.0, 1.0);
        double maxR = 0;
        double minR = Double.MAX_VALUE;
        for (double[] p : rec.ptc) {
            double dx = p[0] - 3, dy = p[1] - 64, dz = p[2] - 4;
            double rr = Math.sqrt(dx * dx + dy * dy + dz * dz);
            maxR = Math.max(maxR, rr);
            minR = Math.min(minR, rr);
        }
        check("★★ 次元斩大圆环是**正圆**（半径恒为 GS_EM_DS_BIG = 3.0，实测 " 
                        + String.format("%.4f", minR) + "~" + String.format("%.4f", maxR) + "）",
                Math.abs(maxR - EmSub.GS_EM_DS_BIG) < 1.0e-9 && Math.abs(minR - EmSub.GS_EM_DS_BIG) < 1.0e-9);
        check("★ 大圆环点数 = GS_EM_DS_RING_N = 48（fade 1.0 时）",
                bigPointCount(1.0) == 48);
        check("★ 大圆环 fade < 0.4 时隔点画 = 24 点", bigPointCount(0.2) == 24);

        // 领域每拍：伤害 25 点 / 0.5 秒；1.2 秒后消失；吸引
        Integer[][] ft = {
                {0, 500}, {0, 499}, {0, 0}, {0, -1}, {1199, 0}, {1200, 0}, {1201, 0}, {2000, 0},
        };
        Value jsFt = jsCall(ctx, "__fieldTickGrid(" + gateCases(ft) + ")");
        List<String> jsRows = strRows2(jsFt);
        List<String> javaRows = new ArrayList<>();
        for (Integer[] c : ft) {
            javaRows.add(javaFieldTickRow(c[0], c[1]));
        }
        check("★ 次元斩领域单拍（存活 / 伤害 / 音效）全网格一致（" + ft.length + " 组）",
                jsRows.equals(javaRows));
        if (!jsRows.equals(javaRows)) {
            System.out.println("      · JS  : " + jsRows);
            System.out.println("      · Java: " + javaRows);
        }
        check("★ 领域单拍时 gsEmFieldTick 恒返回「留着」（寿命过滤在心跳里，不在这里）",
                javaFieldTickRow(1199, 0).startsWith("[1,") && javaFieldTickRow(5000, 0).startsWith("[1,"));

        // ★★ 走真心跳 gsEmPulse：寿命 1.2 秒 / 每 0.5 秒 25 点 / 每 0.25 秒重画
        //    cases: [bornΔ, nextHitΔ, drawAtΔ, mobX, mobY, mobZ]
        //   ★ 刀口（1199/1200、249/250、0/+1）只钉在**真 JS** 上 —— JS 的 __now 是冻结的
        //     1000000，而 Java 侧 gsEmPulse 自己再取一次真实时钟 ⇒ 有几毫秒漂移。
        //     把 Java 断言压在刀口上会偶发翻车（第一版就是这样红了一格）。
        double[][] ex = {
                {1199, -100, 200, 0, 64, 0},   // 寿命差 1199 ⇒ 还活着
                {1200, -100, 200, 0, 64, 0},   // 寿命差 1200 ⇒ 被丢掉
                {0, 0, 200, 0, 64, 0},         // nextHit 恰好到点 ⇒ 打
                {0, -1, 200, 0, 64, 0},        // nextHit 已过 ⇒ 打
                {0, 1, 200, 0, 64, 0},         // nextHit 还差 1ms ⇒ 不打
                {0, 500, 249, 0, 64, 0},       // 画面差 249 ⇒ 不重画
                {0, 500, 250, 0, 64, 0},       // 画面差 250 ⇒ 重画
        };
        List<String> exRows = strRows2(jsCall(ctx, "__fieldPulseGrid(" + circLiteral(ex) + ")"));
        check("★★ 真 JS 刀口：领域寿命差 1199 还活着 / 1200 就被心跳丢掉",
                exRows.get(0).startsWith("[1,") && exRows.get(1).startsWith("[0,"));
        check("★★ 真 JS 刀口：nextHit 差 0 与 -1 都打、差 +1 不打",
                exRows.get(2).startsWith("[1,1,") && exRows.get(3).startsWith("[1,1,")
                        && exRows.get(4).startsWith("[1,0,"));
        check("★★ 真 JS 刀口：画面差 249 不重画 / 250 重画（阈值就是 GS_EM_DS_DRAW_MS = 250）",
                exRows.get(5).endsWith(",0]") && exRows.get(6).endsWith(",1]"));

        // 容差网格：所有取值离刀口 ≥ 100ms，真实时钟漂移不可能翻盘
        double[][] fp = {
                {0, 500, 50, 0, 64, 0},
                {1000, 500, 50, 0, 64, 0},
                {1200, 500, 50, 0, 64, 0},
                {1300, 500, 50, 0, 64, 0},
                {2000, 500, 50, 0, 64, 0},
                {0, 0, 50, 0, 64, 0},
                {0, -100, 50, 0, 64, 0},
                {0, 100, 50, 0, 64, 0},
                {0, 500, 400, 0, 64, 0},
                {0, 500, 50, 9, 64, 0},
                {0, 500, 50, 0, 68, 0},
        };
        Value jsPulse = jsCall(ctx, "__fieldPulseGrid(" + circLiteral(fp) + ")");
        List<String> jsPulseRows = strRows2(jsPulse);
        List<String> javaPulseRows = new ArrayList<>();
        for (double[] c : fp) {
            javaPulseRows.add(javaFieldPulseRow((int) c[0], (int) c[1], (int) c[2], c[3], c[4], c[5]));
        }
        check("★★ 次元斩领域真心跳 gsEmPulse 全网格一致（" + fp.length + " 组：存活 / 伤害 / 重画）",
                jsPulseRows.equals(javaPulseRows));
        if (!jsPulseRows.equals(javaPulseRows)) {
            for (int i = 0; i < fp.length; i++) {
                if (!jsPulseRows.get(i).equals(javaPulseRows.get(i))) {
                    System.out.println("      · 差异 @case" + i + "  JS=" + jsPulseRows.get(i)
                            + "  Java=" + javaPulseRows.get(i));
                }
            }
        }
        check("★ 领域寿命 1.2 秒：1.0 秒还在 / 1.2 秒起被心跳丢掉",
                javaFieldPulseRow(1000, 500, 50, 0, 64, 0).startsWith("[1,")
                        && javaFieldPulseRow(1200, 500, 50, 0, 64, 0).startsWith("[0,")
                        && javaFieldPulseRow(1300, 500, 50, 0, 64, 0).startsWith("[0,"));
        check("★ 领域每 0.5 秒造成 25 点伤害（nextHit 到点那一拍才打）",
                javaFieldPulseRow(0, 0, 50, 0, 64, 0).startsWith("[1,1,")
                        && javaFieldPulseRow(0, -100, 50, 0, 64, 0).startsWith("[1,1,")
                        && javaFieldPulseRow(0, 100, 50, 0, 64, 0).startsWith("[1,0,"));
        check("★ 领域画面每 0.25 秒重画一次（差 50ms 不重画 / 差 400ms 重画）",
                javaFieldPulseRow(0, 500, 50, 0, 64, 0).endsWith(",0]")
                        && javaFieldPulseRow(0, 500, 400, 0, 64, 0).endsWith(",1]"));
        check("★ 领域只吃半径 GS_EM_DS_R = 3 以内的目标（9 格外的怪不吃伤害）",
                javaFieldPulseRow(0, 0, 250, 9, 64, 0).startsWith("[1,0,"));
        check("★ 领域伤害 = GS_EM_DS_DMG = 25.0 点",
                EmSub.GS_EM_DS_DMG == 25.0 && jsCall(ctx, "GS_EM_DS_DMG").asDouble() == 25.0);
        check("★ 领域寿命常量 GS_EM_DS_LIFE = 1200（真 JS 与 Java 同值）",
                EmSub.GS_EM_DS_LIFE == 1200 && jsCall(ctx, "GS_EM_DS_LIFE").asInt() == 1200);
        check("★ 领域伤害间隔常量 GS_EM_DS_EVERY = 500（真 JS 与 Java 同值）",
                EmSub.GS_EM_DS_EVERY == 500 && jsCall(ctx, "GS_EM_DS_EVERY").asInt() == 500);
    }

    /** 真心跳 {@code gsEmPulse} 里领域那一份：{@code [存活, 伤害笔数, 是否重画]}。 */
    private static String javaFieldPulseRow(int bornAgo, int nextHitDelta, int drawAtAgo,
                                            double mx, double my, double mz) {
        ListenerCore core = new ListenerCore();
        EmSub em = new EmSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        r.cands.add(new Object[]{MOB_ID, "ZOMBIE", mx, my, mz, 20.0, false});
        long now = ListenerCore.now();
        EmSub.DField f = new EmSub.DField();
        f.w = w;
        f.owner = null;
        f.x = 0;
        f.y = 64;
        f.z = 0;
        f.bs = frame(w, 0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0);
        f.born = now - bornAgo;
        f.nextHit = now + nextHitDelta;
        f.drawAt = now - drawAtAgo;
        em.GS_EM_FIELDS.add(f);
        em.gsEmPulse();
        return "[" + em.GS_EM_FIELDS.size() + "," + r.dmg.size() + "," + (r.ptc.isEmpty() ? 0 : 1) + "]";
    }

    private static int bigPointCount(double fade) {
        Rec rec = new Rec();
        World w = mockWorld(rec);
        EmSub.Frame b = frame(w, 0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0);
        new EmSub(new ListenerCore()).gsEmDrawBig(w, 0, 64, 0, b, 0.0, fade);
        return rec.ptc.size();
    }

    private static String javaFieldTickRow(int bornAgo, int nextHitDelta) {
        ListenerCore core = new ListenerCore();
        EmSub em = new EmSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        long now = ListenerCore.now();
        EmSub.DField f = new EmSub.DField();
        f.w = w;
        f.owner = null;
        f.x = 0;
        f.y = 64;
        f.z = 0;
        f.bs = frame(w, 0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0);
        f.born = now - bornAgo;
        f.nextHit = now + nextHitDelta;
        boolean alive = em.gsEmFieldTick(f, now);
        return "[" + (alive ? 1 : 0) + "," + r.dmg.size() + "," + r.snd.size() + "]";
    }

    // ---------- C10 裂缝 ----------

    private static void emCrack(Context ctx) {
        // 裂缝点：l（横向，负 = 左）/ h（竖直）/ f（前后）
        double[][] pts = {
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, -3.5, 0, 0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 3.5, 0, 0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 3.0, 0},
                {0, 64, 0, 0, 0, 1, -1, 0, 0, 0, 1, 0, 0, 1.5, 0.5},
                {2, 70, -1, 0.6, 0.8, 0, -0.8, 0, 0.6, 0, 1, 0, -2.5, 1.5, 0.25},
                {2, 70, -1, 0, 0, 1, -1, 0, 0, 0, 1, 0, 1.75, 0.75, -0.5},
        };
        Value js = jsCall(ctx, "__crackPtGrid(" + circLiteral(pts) + ")");
        boolean ptOk = true;
        for (int i = 0; i < pts.length; i++) {
            double[] c = pts[i];
            EmSub.Crack cr = new EmSub.Crack();
            cr.w = null;
            cr.x = c[0];
            cr.y = c[1];
            cr.z = c[2];
            cr.fx = c[3];
            cr.fz = c[5];
            cr.rx = c[6];
            cr.rz = c[8];
            Location l = EmSub.gsEmCrackPoint(cr, c[12], c[13], c[14]);
            Value row = js.getArrayElement(i);
            if (l == null || row.getArrayElement(0).asDouble() != l.getX()
                    || row.getArrayElement(1).asDouble() != l.getY()
                    || row.getArrayElement(2).asDouble() != l.getZ()) {
                ptOk = false;
            }
        }
        check("★★ gsEmCrackPoint(l, h, f) 全网格逐值相同（长 7 / 宽 1 / 高 3 的立墙）", ptOk);

        // 生命周期：绘画 2 秒（前一半横线 / 后一半竖线）→ 菱形 → 留存 3 秒
        Integer[][] cases = {
                {0, 0, 100}, {0, 0, 999}, {0, 0, 1000}, {0, 0, 1001}, {0, 0, 1999},
                {0, 0, 2000}, {0, 0, 2001}, {0, 0, 4999}, {0, 0, 5000}, {0, 0, 5001},
                {0, 0, 9000},
        };
        Value jsT = jsCall(ctx, "__crackTickGrid(" + tickCases(cases) + ")");
        List<String> jsRows = strRows2(jsT);
        List<String> javaRows = new ArrayList<>();
        for (Integer[] c : cases) {
            javaRows.add(javaCrackTickRow(c[0] == 1, c[1], c[2]));
        }
        check("★ 裂缝生命周期（存活 / 撒点数）全网格一致（" + cases.length + " 组）", jsRows.equals(javaRows));
        if (!jsRows.equals(javaRows)) {
            System.out.println("      · JS  : " + jsRows);
            System.out.println("      · Java: " + javaRows);
        }
        // ★ 横线 N=40 ⇒ i 从 0 到 floor(40*prog) 闭区间；竖线 N=24 同理。
        check("★ 绘画前半段只画横线：100ms（进度 0.1）5 点 / 1000ms（进度 1.0）41 点",
                javaCrackTickRow(false, 0, 100).equals("[1,5]")
                        && javaCrackTickRow(false, 0, 1000).equals("[1,41]"));
        check("★ 过了 1000ms 才开始补竖线：1001ms 时 41 + 1 = 42 点",
                javaCrackTickRow(false, 0, 1001).equals("[1,42]"));
        int ptsAt2000 = Integer.parseInt(javaCrackTickRow(false, 0, 2000).split(",")[1].replace("]", ""));
        check("★ 2000ms 起画满横线(41) + 竖线(25) + 菱形（实测 " + ptsAt2000 + " 点 > 66）",
                ptsAt2000 > 66 && javaCrackTickRow(false, 0, 2000).equals(jsRows.get(5)));
        check("★ 留存 3 秒：5000ms 仍存活 / 5001ms 消失",
                javaCrackTickRow(false, 0, 5000).startsWith("[1,")
                        && javaCrackTickRow(false, 0, 5001).startsWith("[0,"));
        check("★ c.used = true = 立刻返回 false（已用掉的裂缝直接回收）",
                javaCrackTickRow(true, 0, 100).startsWith("[0,"));
    }

    private static String tickCases(Integer[][] cs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cs.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(cs[i][0]).append(',').append(cs[i][1]).append(',')
                    .append(cs[i][2]).append(']');
        }
        return sb.append(']').toString();
    }

    private static String javaCrackTickRow(boolean used, int ageMs, int dtMs) {
        ListenerCore core = new ListenerCore();
        EmSub em = new EmSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        EmSub.Crack c = new EmSub.Crack();
        c.w = w;
        c.owner = null;
        c.x = 0;
        c.y = 64;
        c.z = 0;
        c.fx = 0;
        c.fz = 1;
        c.rx = -1;
        c.rz = 0;
        c.t = 0;
        c.used = used;
        boolean alive = em.gsEmCrackTick(c, dtMs);
        return "[" + (alive ? 1 : 0) + "," + r.ptc.size() + "]";
    }

    // ---------- C11 药效续期判定 ----------

    private static void emBuffDecide(Context ctx) {
        List<Integer[]> cases = new ArrayList<>();
        for (int has0 = 0; has0 <= 1; has0++) {
            for (int amp0 : new int[]{-1, 0, 1, 2, 3, 4}) {
                for (int dur0 : new int[]{0, 100, 539, 540, 541, 600}) {
                    for (int nvOwned = 0; nvOwned <= 1; nvOwned++) {
                        for (int nvHas = 0; nvHas <= 1; nvHas++) {
                            for (int nvDur : new int[]{0, 539, 541}) {
                                cases.add(new Integer[]{has0, amp0, dur0, 0, -1, 0, 0, -1, 0,
                                        nvOwned, nvHas, nvDur});
                            }
                        }
                    }
                }
            }
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[');
            Integer[] c = cases.get(i);
            for (int j = 0; j < c.length; j++) {
                if (j > 0) {
                    sb.append(',');
                }
                sb.append(c[j]);
            }
            sb.append(']');
        }
        sb.append(']');
        Value js = jsCall(ctx, "__emBuffGrid(" + sb + ")");
        boolean ok = true;
        int mismatchPrinted = 0;
        for (int i = 0; i < cases.size(); i++) {
            Integer[] c = cases.get(i);
            // ★★ 必须按**这一格**的 (has, amp, dur) 算，不能拿常量算 —— 否则 864 组全在比同一个值
            int count = 0;
            for (int j = 0; j < 3; j++) {
                int want = new int[]{3, 2, 1}[j];
                boolean has = c[j * 3] == 1;
                int curAmp = has ? c[j * 3 + 1] : -1;
                int dur = has ? c[j * 3 + 2] : 0;
                if (EmSub.gsEmBuffShouldApply(curAmp, dur, want, EmSub.GS_EM_BUFF_REF)) {
                    count++;
                }
            }
            int nvAct = EmSub.gsEmNvDecide(c[9] == 1, c[10] == 1, c[11], EmSub.GS_EM_BUFF_REF);
            Value row = js.getArrayElement(i);
            if (row.getArrayElement(0).asInt() != count
                    || row.getArrayElement(1).asInt() != (nvAct == 2 ? 1 : 0)
                    || row.getArrayElement(2).asInt() != (nvAct != 0 ? 1 : 0)) {
                ok = false;
                if (mismatchPrinted++ < 6) {
                    System.out.println("      · 差异 @case" + i + " [" + c[0] + "," + c[1] + "," + c[2]
                            + "," + c[9] + "," + c[10] + "," + c[11] + "]  JS=[" + row.getArrayElement(0)
                            + "," + row.getArrayElement(1) + "," + row.getArrayElement(2)
                            + "]  Java=[" + count + "," + (nvAct == 2 ? 1 : 0) + ","
                            + (nvAct != 0 ? 1 : 0) + "]");
                }
            }
        }
        check("★★ 药效续期判定（3 条主药效 + 夜视）全网格一致（" + cases.size() + " 组）", ok);

        // 主药效：身上那份更强的 = 不覆盖；同级但还够久 = 不补
        boolean mainOk = true;
        for (int amp = -1; amp <= 5; amp++) {
            for (int dur : new int[]{0, 539, 540, 541, 600}) {
                boolean apply = EmSub.gsEmBuffShouldApply(amp, dur, 3, EmSub.GS_EM_BUFF_REF);
                boolean want = !(amp > 3) && !(amp == 3 && dur > 540);
                if (apply != want) {
                    mainOk = false;
                }
            }
        }
        check("★★ 主药效「更强的同种药效不覆盖 / 同级且 > 27 秒不补」判定正确", mainOk);
        check("★ 玩家身上速度 V（amp 4）时不再压成速度 II（amp 1）",
                !EmSub.gsEmBuffShouldApply(4, 600, 1, EmSub.GS_EM_BUFF_REF));
        check("★ 玩家身上同种药效剩 541 刻（> 540）= 不补发",
                !EmSub.gsEmBuffShouldApply(3, 541, 3, EmSub.GS_EM_BUFF_REF));
        check("★ 剩 540 刻（恰好 = REF）= 补发",
                EmSub.gsEmBuffShouldApply(3, 540, 3, EmSub.GS_EM_BUFF_REF));

        // 夜视：别人给的完全不碰
        check("★★ 夜视「别人给的 = 不触发 / 不接管 / 不登记」",
                EmSub.gsEmNvDecide(false, true, 600, EmSub.GS_EM_BUFF_REF) == 0);
        check("★ 夜视自己那份剩 541 刻 = 接管但不补（1）",
                EmSub.gsEmNvDecide(true, true, 541, EmSub.GS_EM_BUFF_REF) == 1);
        check("★ 夜视自己那份剩 540 刻 = 接管并补（2）",
                EmSub.gsEmNvDecide(true, true, 540, EmSub.GS_EM_BUFF_REF) == 2);
        check("★ 没有夜视 = 接管并补（2）",
                EmSub.gsEmNvDecide(false, false, 0, EmSub.GS_EM_BUFF_REF) == 2);

        // 脱手回收：只收本机制给的那一份（amp 对得上才收）
        Value clearJs = jsCall(ctx, "__emBuffClearGrid([[-1,-1,-1],[3,2,1],[4,5,6],[0,0,0]])");
        boolean clearOk = true;
        int[][] ampCases = {{-1, -1, -1}, {3, 2, 1}, {4, 5, 6}, {0, 0, 0}};
        for (int i = 0; i < 4; i++) {
            int[] amps = ampCases[i];
            int[] want = {3, 2, 1};
            int expect = 0;
            for (int j = 0; j < 3; j++) {
                if (amps[j] == want[j]) {
                    expect++;
                }
            }
            if (clearJs.getArrayElement(i).getArrayElement(0).asInt() != expect) {
                clearOk = false;
            }
        }
        check("★★ 脱手回收只收本机制给的那一份（getAmplifier() 对不上就跳过 = 玩家自己那份原样保留）",
                clearOk);
    }

    // ---------- C12 逝言弹道 ----------

    private static void voQi(Context ctx) {
        // 命中：沿弹道 |along| ≤ 1.0 且垂距 ≤ 1.5；每目标只吃一次；穿透上限 4；射程 7；撞墙即止
        //   玩家在 (0.5, 64, -3.25)、朝 +Z = 弹道起点 (0.5, 65.1, -2.65)，每拍 +0.5 格
        List<double[][]> groups = new ArrayList<>();
        List<Double> solidAts = new ArrayList<>();
        // ① 正前方 3 格一只 = 命中
        groups.add(new double[][]{{0.0, 64.0, 3.0}});
        solidAts.add(null);
        // ② 空场 = 0 伤害、走满 14 拍
        groups.add(new double[][]{});
        solidAts.add(null);
        // ③ 6.9 格（射程内）
        groups.add(new double[][]{{0.0, 64.0, 6.9}});
        solidAts.add(null);
        // ④ 8.0 格（超出 7 格射程）
        groups.add(new double[][]{{0.0, 64.0, 8.0}});
        solidAts.add(null);
        // ⑤ 横向 2.2（★ 判定的是**到弹道轴**的垂距：轴在 x=0.5 ⇒ 垂距 1.7 > 1.5）= 擦过
        groups.add(new double[][]{{2.2, 64.0, 3.0}});
        solidAts.add(null);
        // ⑥ 横向 1.9（垂距 1.4 ≤ 1.5）= 命中
        groups.add(new double[][]{{1.9, 64.0, 3.0}});
        solidAts.add(null);
        // ⑦ 抬到 66.0：到弹道轴的垂距 = |ey| = (66.0+0.9) - 65.1 = 1.8 > 1.5 ⇒ 擦过
        groups.add(new double[][]{{0.0, 66.0, 3.0}});
        solidAts.add(null);
        // ⑧ 压到 64.5：ey = (64.5+0.9) - 65.1 = 0.3 ⇒ 命中
        groups.add(new double[][]{{0.0, 64.5, 3.0}});
        solidAts.add(null);
        // ⑨ 四个怪**站在同一拍上**：一次 step 内全中 ⇒ 伤害 4 笔、连击只 +1
        groups.add(new double[][]{{0.0, 64.0, 3.0}, {0.4, 64.0, 3.0},
                {0.9, 64.0, 3.0}, {1.4, 64.0, 3.0}});
        solidAts.add(null);
        // ⑩ 五个怪**分散在不同拍上** ⇒ 命中 4 只（第 5 只没轮到就散了）、连击 +4
        groups.add(new double[][]{{0.0, 64.0, 1.0}, {0.0, 64.0, 2.0}, {0.0, 64.0, 3.0},
                {0.0, 64.0, 4.0}, {0.0, 64.0, 5.0}});
        solidAts.add(null);
        // ⑪ 墙在 3 格外 = 只走 5 拍
        groups.add(new double[][]{{0.0, 64.0, 2.0}});
        solidAts.add(3.0);

        int cases = groups.size();
        StringBuilder sb = new StringBuilder("[");
        for (int g = 0; g < cases; g++) {
            double[][] cs = groups.get(g);
            if (g > 0) {
                sb.append(',');
            }
            sb.append("{\"sx\":0.5,\"sy\":64,\"sz\":-3.25,\"dx\":0,\"dy\":0,\"dz\":1,\"n\":1,\"cands\":[");
            for (int i = 0; i < cs.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append("{\"id\":\"").append(mobId(i)).append("\",\"type\":\"ZOMBIE\",\"x\":")
                        .append(cs[i][0]).append(",\"y\":").append(cs[i][1]).append(",\"z\":").append(cs[i][2])
                        .append(",\"noHealth\":false,\"dead\":false}");
            }
            sb.append(']');
            if (solidAts.get(g) != null) {
                sb.append(",\"solidAt\":").append(solidAts.get(g));
            }
            sb.append('}');
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = jsCall(ctx, "__ARG = JSON.parse(__ARG); (function(){ var r = [];"
                + " for (var i = 0; i < __ARG.length; i++) { r.push(__voQiFire(__ARG[i])); } return r; })()");

        boolean dmgOk = true;
        boolean stepOk = true;
        boolean fxOk = true;
        boolean stackOk = true;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[][] cs = groups.get(i);

            Rec rec = new Rec();
            rec.at(0.5, 64.0, -3.25, 0, 0, 1);
            for (int j = 0; j < cs.length; j++) {
                rec.cands.add(new Object[]{mobId(j), "ZOMBIE", cs[j][0], cs[j][1], cs[j][2], 20.0, false});
            }
            rec.solidAt = solidAts.get(i);
            long[] javaRes = javaVoQiFire(rec, 0);

            // 伤害笔数与每笔数值
            Value jsDmg = r.getMember("dmg");
            if (jsDmg.getArraySize() != rec.dmg.size()) {
                dmgOk = false;
                System.out.println("      · 差异 @case" + i + " 伤害笔数 JS=" + jsDmg.getArraySize()
                        + " Java=" + rec.dmg.size());
            } else {
                for (int j = 0; j < rec.dmg.size(); j++) {
                    if (jsDmg.getArrayElement(j).getArrayElement(1).asDouble()
                            != ((Number) rec.dmg.get(j)[1]).doubleValue()) {
                        dmgOk = false;
                    }
                }
            }
            // 步数
            if (r.getMember("steps").asInt() != javaRes[0]) {
                stepOk = false;
                System.out.println("      · 差异 @case" + i + " 步数 JS=" + r.getMember("steps").asInt()
                        + " Java=" + javaRes[0]);
            }
            // 第一拍的灰色弧形斩击（17 点，位置确定；残留点是随机的，故只取前 17 个）
            if (!ptsKey(pts(r.getMember("firstPtc"))).equals(ptsKey(rec.ptc.subList(0,
                    Math.min(17, rec.ptc.size()))))) {
                fxOk = false;
            }
            // 连击：这一发命中 ≥1 = +1（且只 +1）
            if (r.getMember("n").asInt() != javaRes[1]) {
                stackOk = false;
            }
        }
        check("★★ 逝言弹道伤害全网格一致（11 点 / 每目标只吃一次 / 穿透上限 4）", dmgOk);
        check("★★ 逝言弹道步数全网格一致（射程 7 / 0.5 一格 / 撞墙即止）", stepOk);
        check("★★ 逝言第一拍灰色弧形斩击 17 点逐点一致", fxOk);
        check("★★ 逝言命中 ≥1 = 连击 +1（且只 +1，不是每命中一只 +1）", stackOk);

        // 逐条钉住
        Value c0 = out.getArrayElement(0);
        Value c1 = out.getArrayElement(1);
        Value c3 = out.getArrayElement(3);
        Value c4 = out.getArrayElement(4);
        Value c5 = out.getArrayElement(5);
        Value c6 = out.getArrayElement(6);
        Value c7 = out.getArrayElement(7);
        Value c8 = out.getArrayElement(8);
        Value c9 = out.getArrayElement(9);
        Value c10 = out.getArrayElement(10);
        check("★ 正前方 3 格命中 1 只、每只 11 点（真 JS）",
                c0.getMember("dmg").getArraySize() == 1
                        && c0.getMember("dmg").getArrayElement(0).getArrayElement(1).asDouble() == 11.0);
        check("★ 空场 0 伤害且走满 14 拍（射程 7 / 每拍 0.5）（真 JS）",
                c1.getMember("dmg").getArraySize() == 0 && c1.getMember("steps").asInt() == 14);
        check("★ 8 格超出射程 = 0 伤害（真 JS）", c3.getMember("dmg").getArraySize() == 0);
        check("★ 横向 2.2（到弹道轴垂距 1.7 > 1.5）擦过 = 0 伤害（真 JS）",
                c4.getMember("dmg").getArraySize() == 0);
        check("★ 横向 1.9（垂距 1.4 ≤ 1.5）= 命中（真 JS）", c5.getMember("dmg").getArraySize() == 1);
        check("★ 抬到 66.0（垂距 1.8 > 1.5）= 0 伤害（真 JS）",
                c6.getMember("dmg").getArraySize() == 0);
        check("★ 压到 64.5（垂距 0.3）= 命中（真 JS）", c7.getMember("dmg").getArraySize() == 1);
        check("★★ 穿透上限 4：5 个怪分散在不同拍上只吃 4 个（真 JS）",
                c9.getMember("dmg").getArraySize() == 4);
        check("★ 撞墙即止（不穿墙）：墙在 3 格外时只走 5 拍（真 JS）", c10.getMember("steps").asInt() == 5);
        check("★★ 「命中 ≥1 = 连击 +1」是**按拍**算的，不是按怪：4 只站在同一拍 ⇒ 伤害 4 笔但 n = "
                        + c8.getMember("n").asInt() + "；分散在 4 拍 ⇒ n = " + c9.getMember("n").asInt(),
                c0.getMember("n").asInt() == 1 && c8.getMember("n").asInt() == 1
                        && c9.getMember("n").asInt() == 4);

        // 蓄力 GS_VO_QI_CHARGE = 12 刻
        //   ★ 12 拍都在「蓄力中」，第 13 拍才判定 left <= 0 并放出去 ⇒ 一共 13 拍
        Value charge = jsCall(ctx, "__voCharge({\"hand\":true,\"dropAt\":-1})");
        check("★ 蓄力 12 刻之后才放出去：真 JS 共 " + charge.getMember("chargeTicks").asInt()
                        + " 拍（12 拍蓄力 + 第 13 拍判定发射）、音效 " + charge.getMember("snd").asInt() + " 声",
                charge.getMember("chargeTicks").asInt() == 13 && charge.getMember("fired").asInt() == 1
                        && charge.getMember("snd").asInt() == 4);
        Value drop = jsCall(ctx, "__voCharge({\"hand\":true,\"dropAt\":3})");
        check("★★ 蓄力期间把主手换掉 = 蓄力作废、不挥出去（也不还冷却：GS_VO_FIRE_AT 早已记下）",
                drop.getMember("fired").asInt() == 0 && drop.getMember("fireAt").asInt() == 1
                        && drop.getMember("chargeTicks").asInt() == 13);
        check("★ 蓄力期间 GS_VO_CHARGING 一直是 true（防重复触发），直到最后一拍删掉（"
                        + charge.getMember("charging").asString() + "）",
                charge.getMember("charging").asString().equals("1111111111110"));

        // 冷却闸（★ 网格用离刀口远的值，刀口钉真 JS）
        Integer[][] voGates = {{null, null, 0}, {100, null, 0}, {400, null, 0},
                {null, 1000, 0}, {null, 1400, 0}, {null, null, 1}};
        Value jsGate = jsCall(ctx, "__voGateGrid(" + voGateCases(voGates) + ")");
        List<String> jsG = strRows2(jsGate);
        List<String> javaG = new ArrayList<>();
        for (Integer[] c : voGates) {
            javaG.add(javaVoGateRow(c[0], c[1], c[2] == 1));
        }
        check("★ Vo 挥砍 / 逝言冷却 + 蓄力中闸全网格一致（" + voGates.length + " 组）", jsG.equals(javaG));
        if (!jsG.equals(javaG)) {
            System.out.println("      · JS  : " + jsG);
            System.out.println("      · Java: " + javaG);
        }
        List<String> voExact = strRows2(jsCall(ctx,
                "__voGateGrid([[319,null,0],[320,null,0],[null,1199,0],[null,1200,0]])"));
        check("★★ Vo 挥砍 320ms 刀口（真 JS）：319ms 拒绝（" + voExact.get(0)
                        + "） / 320ms 放行（" + voExact.get(1) + "）",
                voExact.get(0).startsWith("[0,") && !voExact.get(1).startsWith("[0,"));
        check("★★ Vo 逝言 1200ms 刀口（真 JS）：1199ms 拒绝（" + voExact.get(2)
                        + "） / 1200ms 放行（" + voExact.get(3) + "）",
                voExact.get(2).endsWith(",1,0]") && voExact.get(3).endsWith(",1,1]"));
    }

    private static String mobId(int i) {
        return String.format("00000000-0000-0000-0000-0000000000%02x", 0x10 + i);
    }

    private static String fiveLiteral() {
        StringBuilder sb = new StringBuilder("{\"sx\":0.5,\"sy\":64,\"sz\":-3.25,\"dx\":0,\"dy\":0,\"dz\":1,"
                + "\"n\":1,\"cands\":[");
        for (int j = 0; j < 5; j++) {
            if (j > 0) {
                sb.append(',');
            }
            sb.append("{\"id\":\"").append(mobId(j)).append("\",\"type\":\"ZOMBIE\",\"x\":0,\"y\":64,\"z\":")
                    .append(1.0 + j).append(",\"noHealth\":false,\"dead\":false}");
        }
        return sb.append("]}").toString();
    }

    private static String voGateCases(Integer[][] g) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < g.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('[').append(g[i][0] == null ? "null" : g[i][0]).append(',')
                    .append(g[i][1] == null ? "null" : g[i][1]).append(',').append(g[i][2]).append(']');
        }
        return sb.append(']').toString();
    }

    /**
     * Java 侧跑一发「逝言」：把 {@code gsVoRunLater} 换成队列，手动逐拍驱动到 tail 阶段为止。
     *
     * @return {@code [步数, 连击层数]}
     */
    private static long[] javaVoQiFire(Rec r, int n) {
        ListenerCore core = new ListenerCore();
        VoSub vo = new VoSub(core);
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        if (n > 0) {
            VoSub.VoSt st = new VoSub.VoSt();
            st.n = n;
            st.until = ListenerCore.now() + 1000;
            vo.GS_VO_ST.put(r.selfId, st);
        }
        List<Object[]> q = new ArrayList<>();
        java.util.function.BiConsumer<Runnable, Long> old = VoSub.gsVoRunLater;
        VoSub.gsVoRunLater = (task, delay) -> q.add(new Object[]{task, delay});
        int steps = 0;
        try {
            vo.gsVoQiFire(p);
            int guard = 0;
            while (!q.isEmpty() && guard++ < 500) {
                Object[] task = q.remove(0);
                if (((Long) task[1]) >= 2L) {
                    break;                                   // 进入 tail 阶段 = 停
                }
                ((Runnable) task[0]).run();
                steps++;
            }
        } finally {
            VoSub.gsVoRunLater = old;
        }
        VoSub.VoSt st = vo.GS_VO_ST.get(r.selfId);
        return new long[]{steps, st == null ? 0 : st.n};
    }

    private static String javaVoGateRow(Integer swingAgo, Integer fireAgo, boolean charging) {
        ListenerCore core = new ListenerCore();
        VoSub vo = new VoSub(core);
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        long now = ListenerCore.now();
        if (swingAgo != null) {
            vo.GS_VO_SWING_AT.put(SELF_ID, now - swingAgo);
        }
        if (fireAgo != null) {
            vo.GS_VO_FIRE_AT.put(SELF_ID, now - fireAgo);
        }
        if (charging) {
            vo.GS_VO_CHARGING.put(SELF_ID, Boolean.TRUE);
        }
        vo.gsVoSwing(p);
        vo.gsVoQi(p);
        return "[" + r.ptc.size() + "," + r.snd.size() + ","
                + (vo.GS_VO_FIRE_AT.containsKey(SELF_ID) ? 1 : 0) + ","
                + (Boolean.TRUE.equals(vo.GS_VO_CHARGING.get(SELF_ID)) ? 1 : 0) + "]";
    }

    // ---------- C13 jsNum（Java 独有助手：ECMAScript Number::toString） ----------

    private static void jsNumHelper(Context ctx) {
        double[] vals = {0, 1, -1, 7, 13, 17, 34, 37, 99, 213, 426, 0.5, 1.5, -0.5,
                1.0 / 3.0, 3000, 7000, 0.62, 4.5, -0.4, 1.0e-4, 1.0e21, 1.0e-7,
                123456789.0, 0.1 + 0.2, 1.1, 2.5, 1.0e16, 1.0e-6, 5.0e-7};
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vals[i]);
        }
        sb.append(']');
        Value js = jsCall(ctx, "(function(){ var a = " + sb + "; var o = [];"
                + " for (var i = 0; i < a.length; i++) { o.push(String(a[i])); } return o; })()");
        boolean ok = true;
        for (int i = 0; i < vals.length; i++) {
            String j = VoSub.jsNum(vals[i]);
            String s = js.getArrayElement(i).asString();
            if (!j.equals(s)) {
                ok = false;
                System.out.println("      · jsNum(" + vals[i] + ") Java=" + j + " JS=" + s);
            }
        }
        check("★★ Java 的 jsNum(double) 与 JS String(x) 逐值相同（" + vals.length + " 个）", ok);
        check("★ jsNum(7.0) = \"7\"（整数值不带 .0）", VoSub.jsNum(7.0).equals("7"));
        check("★ jsNum(0.62) = \"0.62\"", VoSub.jsNum(0.62).equals("0.62"));
        check("★ jsNum(1.0e21) 走指数写法（小写 e + 正号）", VoSub.jsNum(1.0e21).equals("1e+21"));
        check("★ SiSub / EmSub 的 jsNum 与 VoSub 同结论",
                SiSub.jsNum(0.62).equals(VoSub.jsNum(0.62)) && EmSub.jsNum(0.62).equals(VoSub.jsNum(0.62)));
    }

    // ---------- C14 武器禁近战 ----------

    private static void wpnGate(Context ctx) {
        check("★ GS_WPN_IDS 五项逐项正确（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改）",
                WpnNoMeleeSub.GS_WPN_IDS.equals(Arrays.asList("GS_MOON_SCYTHE", "GS_ECLIPSE_SCYTHE",
                        "GS_VOICELESS", "GS_SILENCE", "GS_ENMA_BLADE")));
        check("★ GS_WPN_IDS 是不可变列表（防误改）",
                throwsOnModify(WpnNoMeleeSub.GS_WPN_IDS));

        // ★ 物品判定依赖 Slimefun 注册表（getSfItemByItem）= 无服务器环境下必然 false。
        //   ★★ 这里只能钉「手里没东西」那条**不经过物品判定**的路径：
        //      探针为了驱动别的用例，把 JS 的 gsIsItem 换成了「比对 __hand」的桩，
        //      手里塞个假对象的话桩会返回 true，那是探针的假象、不是脚本行为。
        Value jsHold = jsCall(ctx, "(function(){ __hand = null;"
                + " return gsWpnHold(__mkSelf(0,64,0,0,0,1)); })()");
        Rec r0 = new Rec();
        r0.hand = null;
        boolean javaHold = new WpnNoMeleeSub(new ListenerCore())
                .gsWpnHold(mockPlayer(r0, mockWorld(r0)));
        check("★ 手里没东西时 gsWpnHold = false（真 JS 与 Java 同结论）",
                !jsHold.asBoolean() && !javaHold);
        check("★ gsWpnHold 的 null 入参也安全返回 false（脚本里第一道就是 if (p == null) return false）",
                !new WpnNoMeleeSub(new ListenerCore()).gsWpnHold(null));

        // 守卫：gsMsHurt 期间 gsWpnDmgGuard > 0
        ListenerCore core = new ListenerCore();
        Rec r = new Rec();
        World w = mockWorld(r);
        Player p = mockPlayer(r, w);
        check("★ ListenerCore.gsWpnDmgGuard 初值 = 0", core.gsWpnDmgGuard == 0);
        Rec r2 = new Rec();
        r2.cands.add(new Object[]{MOB_ID, "ZOMBIE", 0, 64, 0, 20.0, false});
        Entity ent = mockEnt(r2, r2.cands.get(0));
        core.gsMsHurt(ent, 5.0, p);
        check("★ gsMsHurt 之后 guard 归零（try/finally 成对）", core.gsWpnDmgGuard == 0);
        check("★ gsMsHurt 确实打出了伤害（guard 就是给这一下放行的）", r2.dmg.size() == 1);

        // 开关 / PvP 文案
        check("★ GS_WPN_NOMELEE = true / GS_WPN_NL_PVP = false",
                WpnNoMeleeSub.GS_WPN_NOMELEE && !WpnNoMeleeSub.GS_WPN_NL_PVP);
        check("★ 文案：已启用 · 对玩家 照常",
                WpnNoMeleeSub.gsWpnStartLine(true, false).endsWith("已启用 · 对玩家 照常"));
        check("★ 文案：已停用 · 对玩家 同样取消",
                WpnNoMeleeSub.gsWpnStartLine(false, true).endsWith("已停用 · 对玩家 同样取消"));
        check("★ 注册失败时**不打**成功行（Java 里 gsWpnNoMeleeStart 返回 false 就不打）",
                readText(WPN_SRC).contains("if (ok)") && readText(WPN_SRC).contains("gsWpnStartLine"));
    }

    private static boolean throwsOnModify(List<String> l) {
        try {
            l.add("X");
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static ItemStack fakeItem(String id) {
        // ★ ItemStack 在无服务器环境下无法实例化（Registry 静态初始化失败）
        //   = 用一个**不能**被 SlimefunItem.getByItem 认出来的替身（null 语义等价）
        return null;
    }

    // ======================================================================
    // [D] 反向断言
    // ======================================================================

    private static void reverse(Context ctx, String region, String voRegion, String siRegion,
                               String emRegion, String wpnRegion) throws Exception {
        System.out.println("[D] 反向断言：钉住「不该存在的东西」");

        String regionCode = codeOnly(region);
        String voCode = codeOnly(voRegion);
        String siCode = codeOnly(siRegion);
        String emCode = codeOnly(emRegion);
        String wpnCode = codeOnly(wpnRegion);

        String voSrc = readText(VO_SRC);
        String siSrc = readText(SI_SRC);
        String emSrc = readText(EM_SRC);
        String wpnSrc = readText(WPN_SRC);

        // ★★ 「不该存在的东西」必须扫**去掉注释的代码**：
        //    四个 Java 类的类注释里**刻意**写了「本类里没有 PlayerSwapHandItemsEvent /
        //    gsSiNearest / GS_EM_BUFF_KB」这类说明（那正是「有意不接管」的证据）。
        //    拿原始源码去 contains，会把「说明自己没有」当成「自己有」。
        String voJava = javaCodeOnly(voSrc);
        String siJava = javaCodeOnly(siSrc);
        String emJava = javaCodeOnly(emSrc);
        String wpnJava = javaCodeOnly(wpnSrc);

        // ---- D1 注册方式 ----
        //   ★ 4 个主注册 + 4 个 JavaAdapter 兜底注册（原脚本两条路都留着）
        check("★ 四段脚本都用 pm.registerEvent(...)（4 主路 + 4 兜底 = 8 处）"
                        + "且**没有第 5 个参数**（= ignoreCancelled 默认 false）",
                countOf(regionCode, "pm.registerEvent(") == 8
                        && countOf(regionCode, "ignoreCancelled") == 0);
        check("★ Java 侧四处都没有写 ignoreCancelled = true（默认 false 才对齐）",
                !voJava.contains("ignoreCancelled") && !siJava.contains("ignoreCancelled")
                        && !emJava.contains("ignoreCancelled") && !wpnJava.contains("ignoreCancelled"));
        check("★ 四段脚本都用了 EventPriority.HIGHEST（主路 + 兜底各 4 = 8 处）",
                countOf(regionCode, "Priority.HIGHEST") == 8);
        check("★ Java 侧四个监听器类都用 @EventHandler(priority = EventPriority.HIGHEST)",
                countOf(voJava, "EventPriority.HIGHEST") == 4
                        && countOf(siJava, "EventPriority.HIGHEST") == 4
                        && countOf(emJava, "EventPriority.HIGHEST") == 7
                        && countOf(wpnJava, "EventPriority.HIGHEST") == 1);

        // ---- D2 心跳 ----
        check("★ 四段脚本一共 3 处 runRepeating（武器禁近战没有心跳）",
                countOf(regionCode, "runRepeating(") == 3);
        check("★ 三处心跳都是 runRepeating(xxxPulse, 20, 2)（delay 20 / period 2）",
                countOf(regionCode, "runRepeating(gsVoPulse, 20, 2)") == 1
                        && countOf(regionCode, "runRepeating(gsSiPulse, 20, 2)") == 1
                        && countOf(regionCode, "runRepeating(gsEmPulse, 20, 2)") == 1);
        check("★ Java 侧三处 runRepeating 都是 (…, 20L, 2L)",
                countOf(voJava, "runRepeating(this::gsVoPulse, 20L, 2L)") == 1
                        && countOf(siJava, "runRepeating(this::gsSiPulse, 20L, 2L)") == 1
                        && countOf(emJava, "runRepeating(this::gsEmPulse, 20L, 2L)") == 1);
        check("★ 武器禁近战没有心跳（Java 侧也没有 runRepeating）",
                countOf(wpnJava, "runRepeating") == 0);

        // ---- D3 阎魔刀·改：F = 原版换手 = **没有** PlayerSwapHandItemsEvent ----
        check("★★ 阎魔刀·改不接管 F 键 = 脚本里没有 PlayerSwapHandItemsEvent",
                countOf(regionCode, "PlayerSwapHandItemsEvent") == 0);
        check("★★ Java EmSub 的**代码**里也没有 PlayerSwapHandItemsEvent（只在注释里说明「不注册」）",
                !emJava.contains("PlayerSwapHandItemsEvent")
                        && emSrc.contains("不再注册") && emSrc.contains("PlayerSwapHandItemsEvent"));
        check("★ 脚本注释里明确写着「F=原版换手」（证明这是刻意不接管）",
                regionContains("F=原版换手"));

        // ---- D4 武器禁近战：日志里只列四把武器，但 GS_WPN_IDS 有五项 ----
        check("★ GS_WPN_IDS 有 5 项（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改）",
                WpnNoMeleeSub.GS_WPN_IDS.size() == 5);
        check("★ 但日志文案里只列 4 把（照抄原脚本，不「修正」）",
                countOf(regionCode, "月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂") == 1
                        && !regionCode.contains("月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改"));
        check("★ Java 日志文案也只列 4 把",
                WpnNoMeleeSub.gsWpnStartLine(true, false)
                        .contains("月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂")
                        && !WpnNoMeleeSub.gsWpnStartLine(true, false).contains("阎魔刀·改）·"));

        // ---- D5 没有 System.out.println ----
        check("★ 四个 Java 类里没有 System.out.println / System.err",
                !voJava.contains("System.out") && !siJava.contains("System.out")
                        && !emJava.contains("System.out") && !wpnJava.contains("System.out")
                        && !voJava.contains("System.err") && !siJava.contains("System.err")
                        && !emJava.contains("System.err") && !wpnJava.contains("System.err"));

        // ---- D6 连击窗口语义的**方向**：Vo 衰减 / Si 清 0 ----
        check("★★ Vo 的窗口过期是**掉一层**（不是清零）—— 脚本里 'var n = (st.n | 0) - 1'",
                voRegionContains("var n = (st.n | 0) - 1;"));
        check("★★ Si 的窗口过期是**直接清 0** —— 脚本里 gsSiClear(p)",
                siRegionContains("gsSiClear(p);"));
        check("★★ Java VoSub 也是 -1（逐层衰减）",
                voJava.contains("int n = st.n - 1;"));
        check("★★ Java SiSub 也是 gsSiClear（清 0）",
                siJava.contains("gsSiClear(p);"));

        // ---- D7 已删除 / 不存在的键名 ----
        check("★ 脚本里没有 gsSiNearest / gsSiSilence / gsSiChase 这些被删掉的名字",
                !siCode.contains("gsSiNearest") && !siCode.contains("gsSiSilence")
                        && !siCode.contains("gsSiChase"));
        check("★ Java SiSub 的**代码**里也没有这些名字（注释里点名说明「本类里没有」）",
                !siJava.contains("gsSiNearest") && !siJava.contains("gsSiSilence")
                        && !siJava.contains("gsSiChase") && siSrc.contains("gsSiNearest"));
        check("★ 脚本里没有 GS_EM_DS_AT（次元斩改成挂在领域对象上的 nextHit）",
                !emCode.contains("GS_EM_DS_AT"));
        check("★ Java EmSub 的代码里也没有 GS_EM_DS_AT", !emJava.contains("GS_EM_DS_AT"));
        check("★ 脚本里没有 GS_EM_BUFF_KB（击退那套已删除）", !emCode.contains("GS_EM_BUFF_KB"));
        check("★ Java EmSub 的代码里也没有 GS_EM_BUFF_KB（注释里点名说明已删除）",
                !emJava.contains("GS_EM_BUFF_KB") && emSrc.contains("GS_EM_BUFF_KB"));

        // ---- D8 武器禁近战：5 道闸 ----
        check("★ 脚本的 gsWpnNoMelee 有 5 道闸：开关 / 守卫 / 取消 / 攻击者 / 武器",
                wpnRegionContains("if (!GS_WPN_NOMELEE) return;")
                        && wpnRegionContains("if (GS_WPN_DMG_GUARD > 0) return;")
                        && wpnRegionContains("e.setCancelled(true);")
                        && wpnRegionContains("GS_WPN_NL_PVP")
                        && wpnRegionContains("gsWpnHold("));
        check("★ Java WpnNoMeleeSub 同样 5 道闸",
                wpnJava.contains("!GS_WPN_NOMELEE") && wpnJava.contains("gsWpnDmgGuard > 0")
                        && wpnJava.contains("e.setCancelled(true)") && wpnJava.contains("GS_WPN_NL_PVP")
                        && wpnJava.contains("gsWpnHold("));

        // ---- D9 副作用面：setCancelled 的**处数**必须与原脚本逐段对齐 ----
        //   ★ 阎魔刀**确实**会取消事件（右键锚点/裂缝、持刀时禁止破坏方块），
        //     所以这里比的不是「有没有」，而是「处数一样」——多一处少一处都是行为漂移。
        check("★★ setCancelled(true) 处数逐段对齐：脚本 Vo4/Si4/Em4/Wpn1 = 13，Java 同数",
                countOf(voCode, "setCancelled(true)") == 4 && countOf(siCode, "setCancelled(true)") == 4
                        && countOf(emCode, "setCancelled(true)") == 4
                        && countOf(wpnCode, "setCancelled(true)") == 1
                        && countOf(voJava, "setCancelled(true)") == 4
                        && countOf(siJava, "setCancelled(true)") == 4
                        && countOf(emJava, "setCancelled(true)") == 4
                        && countOf(wpnJava, "setCancelled(true)") == 1);
        check("★ WpnNoMeleeSub 里唯一的 setCancelled 就在 gsWpnNoMelee 方法体里",
                countOf(wpnJava, "setCancelled") == 1
                        && bodyOf(wpnJava, "void gsWpnNoMelee(").contains("setCancelled"));

        System.out.println();
    }

    /**
     * 只保留代码：去掉整行注释与行尾注释。
     *
     * <p>★ 为什么需要：原脚本的注释里**反复点名**那些「已删除」的键与函数，
     * 直接 contains 会假阳性。
     */
    /** 去掉行注释与块注释（含 javadoc）。 */
    private static String javaCodeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        boolean inBlock = false;
        for (String line : src.split("\n", -1)) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < line.length()) {
                if (inBlock) {
                    int e = line.indexOf("*/", i);
                    if (e < 0) {
                        i = line.length();
                    } else {
                        inBlock = false;
                        i = e + 2;
                    }
                } else if (line.startsWith("/*", i)) {
                    inBlock = true;
                    i += 2;
                } else if (line.startsWith("//", i)) {
                    break;
                } else {
                    out.append(line.charAt(i));
                    i++;
                }
            }
            sb.append(out).append('\n');
        }
        return sb.toString();
    }

    /** 粗略截出 {@code name} 之后的 2000 个字符（用来做「某处调用只出现在某方法里」的粗查）。 */
    private static String bodyOf(String src, String name) {
        int i = src.indexOf(name);
        if (i < 0) {
            return "";
        }
        return src.substring(i, Math.min(src.length(), i + 2000));
    }

    private static String codeOnly(String src) {
        StringBuilder sb = new StringBuilder();
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("//")) {
                continue;
            }
            int c = line.indexOf("//");
            sb.append(c >= 0 ? line.substring(0, c) : line).append('\n');
        }
        return sb.toString();
    }

    // ======================================================================
    // 工具
    // ======================================================================

    private static Path pick(Path a, Path b) {
        if (Files.isRegularFile(a)) {
            return a;
        }
        return b;
    }

    private static String[] readAll(Path p) throws Exception {
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8).split("\n", -1);
    }

    private static String readText(Path p) {
        try {
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "<读不到 " + p + ">";
        }
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
        // ★ JS 的普通对象（原脚本里一堆 `= {}` 的顶层 var）= 转成 Map，
        //   好跟 Java 侧的 HashMap / newBarTable() 按同一种类型比。
        if (!v.isHostObject() && v.hasMembers()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : v.getMemberKeys()) {
                m.put(k, toJava(v.getMember(k)));
            }
            return m;
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
        // ★ 字符串数组 vs 字符串集合（GS_WPN_IDS：JS 是数组，Java 是不可变 List）= 逐项比内容
        if (a instanceof String[] sa && b instanceof Collection<?> cb) {
            return sameStrings(sa, cb);
        }
        if (b instanceof String[] sb && a instanceof Collection<?> ca) {
            return sameStrings(sb, ca);
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
        // ★ 数值数组 vs 集合（GS_SI_BURST_FX / GS_EM_FX / GS_EM_FIELDS / GS_EM_CRACKS：
        //   JS 是 []（空数组），Java 是 ArrayList）= 比长度
        if (na != null && b instanceof Collection<?> cb2) {
            return na.length == cb2.size();
        }
        if (nb != null && a instanceof Collection<?> ca2) {
            return ca2.size() == nb.length;
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
        if (a instanceof Collection<?> ca && b instanceof Collection<?> cb) {
            return ca.size() == cb.size();
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            return ma.size() == mb.size();
        }
        return a.equals(b);
    }

    private static boolean sameStrings(String[] arr, Collection<?> col) {
        if (arr.length != col.size()) {
            return false;
        }
        int i = 0;
        for (Object o : col) {
            if (!String.valueOf(o).equals(arr[i++])) {
                return false;
            }
        }
        return true;
    }

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
        double[] na = asNumArray(o);
        if (na != null) {
            StringBuilder sb = new StringBuilder("[");
            int lim = Math.min(na.length, 8);
            for (int i = 0; i < lim; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(na[i] == Math.rint(na[i]) ? String.valueOf((long) na[i]) : String.valueOf(na[i]));
            }
            if (na.length > lim) {
                sb.append(", … 共 ").append(na.length).append(" 项");
            }
            return sb.append(']').toString();
        }
        if (o instanceof Map<?, ?> m) {
            return "map(" + m.size() + ")";
        }
        if (o instanceof Collection<?> c) {
            return "list(" + c.size() + ")";
        }
        return String.valueOf(o);
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

    private static Object def(Class<?> rt) {
        if (rt == boolean.class) {
            return false;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == double.class) {
            return 0.0;
        }
        if (rt == float.class) {
            return 0.0f;
        }
        if (rt == short.class) {
            return (short) 0;
        }
        if (rt == byte.class) {
            return (byte) 0;
        }
        if (rt == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ======================================================================
    // Java 侧 mock：把 Bukkit 静态入口接到假服务器上
    // ======================================================================

    /** mock 记录板 —— 一次 Java 侧调用的全部可观测量。 */
    static final class Rec {
        final List<double[]> ptc = new ArrayList<>();
        final List<Object[]> snd = new ArrayList<>();
        final List<Object[]> dmg = new ArrayList<>();
        final List<double[]> boxes = new ArrayList<>();
        final List<String> barTitles = new ArrayList<>();
        final List<Double> barProg = new ArrayList<>();
        final List<Boolean> barVis = new ArrayList<>();
        final Map<String, Object> pdc = new LinkedHashMap<>();
        List<Object[]> cands = new ArrayList<>();
        ItemStack hand;
        /** 默认位姿与 JS 侧 {@code __mkSelf(0, 64, 0, 0, 0, 1)} 一致。 */
        double px, py = 64.0, pz;
        double dx, dy, dz = 1.0;
        boolean solid;
        /** {@code null} = 哪里都不实心；非 null = {@code z >= pz + solidAt} 处是实心墙。 */
        Double solidAt;
        boolean online = true;
        String selfId = SELF_ID;

        /** 摆好玩家位姿（脚底坐标 + 朝向）。 */
        Rec at(double x, double y, double z, double fx, double fy, double fz) {
            this.px = x;
            this.py = y;
            this.pz = z;
            this.dx = fx;
            this.dy = fy;
            this.dz = fz;
            return this;
        }
    }

    private static final Logger LOGGER = Logger.getLogger("GS-E-PROBE");

    static {
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                capturedLog.add(r.getLevel() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    private static final List<String> capturedLog = new ArrayList<>();

    /** {@code getDirection()} 不是 final = 直接指定朝向（含非单位向量）。 */
    static final class FakeLoc extends Location {
        private final Vector dir;

        FakeLoc(World w, double x, double y, double z, Vector d) {
            super(w, x, y, z);
            this.dir = d;
        }

        FakeLoc(World w, double x, double y, double z, float yaw, float pitch, Vector d) {
            super(w, x, y, z, yaw, pitch);
            this.dir = d;
        }

        @Override
        public Vector getDirection() {
            return dir;
        }
    }

    private static final FakePdc GLOBAL_PDC = new FakePdc();

    private static void installFakeBukkit() throws Exception {
        World world = (World) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getPersistentDataContainer":
                            return FakePdc.make(GLOBAL_PDC);
                        case "getBlockAt":
                            return blockProxy(false);
                        case "getName":
                            return "world";
                        default:
                            return def(m.getReturnType());
                    }
                });
        Server srv = (Server) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{Server.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getWorlds":
                            return new ArrayList<>(List.of(world));
                        case "getLogger":
                            return LOGGER;
                        case "getOnlinePlayers":
                            return new ArrayList<Player>();
                        case "createBossBar":
                            return bossBarProxy();
                        default:
                            return def(m.getReturnType());
                    }
                });
        Field sf = Bukkit.class.getDeclaredField("server");
        sf.setAccessible(true);
        sf.set(null, srv);
        check("★ 假服务器注入成功（Bukkit 私有静态字段 server）", Bukkit.getServer() == srv);
    }

    /**
     * 方块替身。
     * <p>★ 这里**不能**给 {@code org.bukkit.Material} 做动态代理 —— 它是 enum，不是接口，
     * {@code Proxy.newProxyInstance} 会直接抛 {@code IllegalArgumentException}。
     * 一旦抛了，{@code gsVoQiStep} 里那圈 {@code try/catch} 会把「撞墙」判成 false，
     * 弹道就会一路穿墙飞到底。所以这里直接用**真枚举值**。
     * <p>前提是 {@link #installFakeRegistry()} 已经把 {@code org.bukkit.Registry} 撑起来了
     * —— 1.21 的 {@code Material.isSolid()} 是 {@code asBlockType().isSolid()}，
     * 而 {@code asBlockType()} 会去查 {@code Registry.BLOCK}。
     */
    private static Object blockProxy(boolean solid) {
        final Material mat = solid ? Material.STONE : Material.AIR;
        return Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.Block.class},
                (p, m, ar) -> {
                    if (m.getName().equals("getType")) {
                        return mat;
                    }
                    if (m.getName().equals("isEmpty")) {
                        return !solid;
                    }
                    return def(m.getReturnType());
                });
    }

    /**
     * 给 {@code io.papermc.paper.registry.RegistryAccessHolder.INSTANCE} 塞一个假实现。
     * <p>standalone 跑 Paper API 时 {@code Registry.<clinit>} 会抛
     * {@code IllegalStateException: No RegistryAccess implementation found}，
     * 连带 {@code Material.isSolid()} / {@code PotionEffectType} 这类东西全部不可用。
     * 探针只是**替运行环境**补上这一层，不碰任何被测代码。
     * <p>★ {@code INSTANCE} 是 {@code static final Optional}，普通反射改不动 ⇒ 走 Unsafe。
     */
    private static void installFakeRegistry() {
        try {
            ClassLoader cl = ListenerESubParityProbe.class.getClassLoader();
            // ★ Class.forName(String) 等价于 Class.forName(name, true, ...) —— **会触发静态初始化**！
            //   这里必须先只 load（false），等假实现塞进去之后再让 Registry 自己 <clinit>。
            Class<?> regCls = Class.forName("org.bukkit.Registry", false, cl);
            Class<?> raCls = Class.forName("io.papermc.paper.registry.RegistryAccess", false, cl);
            Class<?> holderCls = Class.forName("io.papermc.paper.registry.RegistryAccessHolder", false, cl);

            Object access = Proxy.newProxyInstance(cl, new Class<?>[]{raCls}, (p, m, ar) -> {
                if (m.getName().equals("getRegistry") && ar != null && ar.length == 1) {
                    String name = registryNameOf(ar[0]);
                    return registryProxy(cl, regCls, name);
                }
                return def(m.getReturnType());
            });

            Class.forName("io.papermc.paper.registry.RegistryAccessHolder", true, cl);
            Field f = holderCls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            putStatic(f, java.util.Optional.of(access));

            Object got = raCls.getMethod("registryAccess").invoke(null);
            check("★ 假 RegistryAccess 注入成功（撑起 org.bukkit.Registry 的静态初始化）", got == access);
            // ★ 顺带确认 Material.isSolid() 真的活了 —— 后面 voQi 的「撞墙」用例全靠它
            check("★ 注入之后 Material.STONE.isSolid() = true / Material.AIR.isSolid() = false",
                    Material.STONE.isSolid() && !Material.AIR.isSolid());
        } catch (Throwable t) {
            check("★ 假 RegistryAccess 注入成功（撑起 org.bukkit.Registry）", false);
            for (Throwable c = t; c != null; c = c.getCause()) {
                System.out.println("      · " + c);
                StackTraceElement[] st = c.getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++) {
                    System.out.println("          at " + st[i]);
                }
            }
        }
    }

    /** 从 {@code RegistryKey} 或 {@code Class} 里猜出注册表名（{@code block} / {@code item} …）。 */
    private static String registryNameOf(Object key) {
        try {
            Object k = key.getClass().getMethod("getKey").invoke(key);
            String s = String.valueOf(k.getClass().getMethod("value").invoke(k));
            if (s != null && s.length() > 0) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到下面按类名猜
        }
        String n = (key instanceof Class<?> c) ? c.getSimpleName() : String.valueOf(key);
        return n.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 假注册表：只有 {@code block} 那张表会给出结果，其它一律 {@code null}
     * —— 免得把 {@code EntityType} / {@code Enchantment} 这类静态初始化搅乱。
     */
    private static Object registryProxy(ClassLoader cl, Class<?> regCls, String name) {
        final boolean isBlock = name != null && name.contains("block");
        return Proxy.newProxyInstance(cl, new Class<?>[]{regCls}, (p, m, ar) -> {
            switch (m.getName()) {
                case "get":
                    if (isBlock && ar != null && ar.length == 1 && ar[0] instanceof NamespacedKey nk) {
                        return blockTypeProxy(cl, nk.getKey());
                    }
                    return null;
                case "iterator":
                    return java.util.Collections.emptyIterator();
                case "stream":
                    return java.util.stream.Stream.empty();
                case "toString":
                    return "FakeRegistry(" + name + ")";
                case "hashCode":
                    return name == null ? 0 : name.hashCode();
                case "equals":
                    return p == (ar == null ? null : ar[0]);
                default:
                    return def(m.getReturnType());
            }
        });
    }

    /** 假 {@code BlockType}：只有 {@code minecraft:stone} 是实心的。 */
    private static Object blockTypeProxy(ClassLoader cl, String key) {
        final boolean solid = "stone".equals(key);
        final boolean air = "air".equals(key);
        return Proxy.newProxyInstance(cl, new Class<?>[]{org.bukkit.block.BlockType.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "isSolid":
                            return solid;
                        case "isAir":
                            return air;
                        case "getKey":
                            return NamespacedKey.minecraft(key);
                        case "toString":
                            return "FakeBlockType(" + key + ")";
                        case "hashCode":
                            return key.hashCode();
                        case "equals":
                            return p == (ar == null ? null : ar[0]);
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    /** 用 {@code sun.misc.Unsafe} 改写 {@code static final} 字段（普通反射改不动）。 */
    private static void putStatic(Field f, Object value) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        Object base = u.staticFieldBase(f);
        long off = u.staticFieldOffset(f);
        u.putObject(base, off, value);
    }

    private static BossBar bossBarProxy() {
        return (BossBar) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{BossBar.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "setTitle":
                            GLOBAL_PDC.barTitles.add(String.valueOf(ar[0]));
                            return null;
                        case "setProgress":
                            GLOBAL_PDC.barProg.add(((Number) ar[0]).doubleValue());
                            return null;
                        case "setVisible":
                            GLOBAL_PDC.barVis.add((Boolean) ar[0]);
                            return null;
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    /** 假 PDC：一个 {@code Map<String,Object>} + 血条记录。 */
    private static final class FakePdc implements InvocationHandler {
        final Map<String, Object> m = new LinkedHashMap<>();
        final List<String> barTitles = new ArrayList<>();
        final List<Double> barProg = new ArrayList<>();
        final List<Boolean> barVis = new ArrayList<>();

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "set":
                    m.put(String.valueOf(args[0]), args[2]);
                    return null;
                case "get":
                    return m.get(String.valueOf(args[0]));
                case "has":
                    return m.containsKey(String.valueOf(args[0]));
                case "remove":
                    m.remove(String.valueOf(args[0]));
                    return null;
                case "isEmpty":
                    return m.isEmpty();
                case "getKeys":
                    return java.util.Set.of();
                case "getOrDefault":
                    return m.containsKey(String.valueOf(args[0])) ? m.get(String.valueOf(args[0])) : args[2];
                default:
                    return def(method.getReturnType());
            }
        }

        static PersistentDataContainer make(FakePdc h) {
            return (PersistentDataContainer) Proxy.newProxyInstance(
                    ListenerESubParityProbe.class.getClassLoader(),
                    new Class<?>[]{PersistentDataContainer.class}, h);
        }
    }

    // ---------- Java 侧 mock 世界 / 玩家 / 实体 ----------

    private static World mockWorld(Rec r) {
        return (World) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "spawnParticle":
                            r.ptc.add(new double[]{((Number) ar[1]).doubleValue(),
                                    ((Number) ar[2]).doubleValue(), ((Number) ar[3]).doubleValue()});
                            return null;
                        case "playSound":
                            r.snd.add(new Object[]{String.valueOf(ar[1]), ar[2], ar[3]});
                            return null;
                        case "getNearbyEntities":
                            Location q = (Location) ar[0];
                            double rx = ((Number) ar[1]).doubleValue();
                            double ry = ((Number) ar[2]).doubleValue();
                            double rz = ((Number) ar[3]).doubleValue();
                            r.boxes.add(new double[]{rx, ry, rz, q.getX(), q.getY(), q.getZ()});
                            List<Entity> out = new ArrayList<>();
                            for (Object[] c : r.cands) {
                                if (Math.abs(((Number) c[2]).doubleValue() - q.getX()) <= rx
                                        && Math.abs(((Number) c[3]).doubleValue() - q.getY()) <= ry
                                        && Math.abs(((Number) c[4]).doubleValue() - q.getZ()) <= rz) {
                                    out.add(mockEnt(r, c));
                                }
                            }
                            return out;
                        case "getPersistentDataContainer":
                            return FakePdc.make(GLOBAL_PDC);
                        case "getBlockAt":
                            // 逝言弹道的「撞墙」测试：墙垂直于 +Z，位置由 r.solidAt 指定
                            if (r.solidAt == null) {
                                return blockProxy(r.solid);
                            }
                            return blockProxy(((Location) ar[0]).getZ() >= r.pz + r.solidAt);
                        case "getName":
                            return "world";
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    /** {@code c = {id, type, x, y, z, health, dead}}（health = null = 没有 getHealth）。 */
    private static Entity mockEnt(Rec r, Object[] c) {
        return (Entity) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{LivingEntity.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(String.valueOf(c[0]));
                        case "getType":
                            return EntityType.valueOf(String.valueOf(c[1]));
                        case "getLocation":
                            return new Location(null, ((Number) c[2]).doubleValue(),
                                    ((Number) c[3]).doubleValue(), ((Number) c[4]).doubleValue());
                        case "isDead":
                            return Boolean.TRUE.equals(c[6]);
                        case "getHealth":
                            if (c[5] == null) {
                                throw new IllegalStateException("no health");
                            }
                            return ((Number) c[5]).doubleValue();
                        case "getVelocity":
                            return new Vector(0, 0, 0);
                        case "damage":
                            r.dmg.add(new Object[]{String.valueOf(c[0]), ar[0]});
                            return null;
                        case "setVelocity":
                        case "setNoDamageTicks":
                            return null;
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    private static Player mockPlayer(Rec r, World w) {
        return (Player) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{Player.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(r.selfId);
                        case "getLocation":
                            return new FakeLoc(w, r.px, r.py, r.pz, new Vector(r.dx, r.dy, r.dz));
                        case "getEyeLocation":
                            return new FakeLoc(w, r.px, r.py + 1.62, r.pz, new Vector(r.dx, r.dy, r.dz));
                        case "getWorld":
                            return w;
                        case "getInventory":
                            return mockInv(r);
                        case "getPersistentDataContainer":
                            return FakePdc.make(GLOBAL_PDC);
                        case "isOnline":
                            return r.online;
                        case "getGameMode":
                            return org.bukkit.GameMode.SURVIVAL;
                        case "getAllowFlight":
                            return false;
                        case "getPotionEffect":
                            return null;
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    private static PlayerInventory mockInv(Rec r) {
        return (PlayerInventory) Proxy.newProxyInstance(
                ListenerESubParityProbe.class.getClassLoader(), new Class<?>[]{PlayerInventory.class},
                (p, m, ar) -> {
                    switch (m.getName()) {
                        case "getItemInMainHand":
                            return r.hand;
                        case "getContents":
                            return new ItemStack[0];
                        default:
                            return def(m.getReturnType());
                    }
                });
    }

    /** 把 {@link Rec} 的记录整理成与 JS 侧同构的行。 */
    private static List<Object> summaryOf(Rec r) {
        List<Object> o = new ArrayList<>();
        o.add(r.ptc.size());
        o.add(r.snd.size());
        o.add(r.dmg.size());
        return o;
    }
}
