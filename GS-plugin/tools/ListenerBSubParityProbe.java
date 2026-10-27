package com.qinghuacisj.gs.script.module.listener;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「开镜 + 余念绝响 / 残樱」两个子系统的对账探针（原脚本 {@code gs_listener.js} 第 1119~2809 行）。
 *
 * <h2>四类断言</h2>
 * <ol>
 *   <li><b>常量对账</b>：用<b>真 GraalJS</b> 跑原脚本（前 2809 行），读出这两个子系统的顶层
 *       {@code var}，与 {@link PsychicSub} / {@link CherrySub} 的字段<b>按名按值</b>比对。</li>
 *   <li><b>日志行逐字节</b>：原脚本真身打出来的行 vs Java 的 {@code xxxLine()} vs
 *       {@code docs/参考服日志_构思技艺.txt} 里那几行。</li>
 *   <li><b>数值 / 几何算式</b>：把原脚本的<b>真函数</b>（{@code gsCherryShootRay} /
 *       {@code gsPsychicShoot} / {@code gsScopeDraw} / {@code gsScopeToggle} /
 *       {@code gsPsychicFire} / {@code gsCherryFire}）用 mock 世界跑起来，
 *       在<b>大量输入</b>上与 Java 侧同一算式逐值比对。</li>
 *   <li><b>反向断言</b>：钉住「不该存在的东西」（没有 {@code 已加载} 行、没有 Q 拦截、
 *       没有副手按钮、没有三连发的逐发延迟、没有开火方式以外的键…）。</li>
 * </ol>
 *
 * <h2>★ 桥接桩（原脚本顶层有副作用）</h2>
 * {@code gs_listener.js} 顶层会注册监听器、跑 {@code runRepeating}、写日志。所以求值前先把
 * {@code getServer()} / {@code runLater} / {@code runRepeating} / {@code getAddonConfig()}
 * 换成空实现（{@link #JS_STUBS}），并把会触发静态初始化失败的类（{@code Attribute} /
 * {@code Particle} / {@code PotionEffectType} / {@code PotionEffect} / {@code Location}）
 * 用 JS 侧 shim 包一层（{@link #JS_SHIM}）—— 与 {@code tools/ConstantParityProbe.java}
 * 的 {@code SHIM} 同一套路。
 *
 * <h2>★ 只求值前 2809 行</h2>
 * 前缀正好停在 {@code gsCherryStart();}（第 2809 行）—— 覆盖本子系统依赖的<b>全部</b>定义
 * （{@code GS_PDT} / {@code gsIsItem} / {@code gsDaLog} / {@code gsAuraIsCurrent} …），
 * 又避开尾部 11,000 行其它子系统在无服务器环境下的报错。
 */
public final class ListenerBSubParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 我负责的区间（原脚本 1-based，含两端）。 */
    private static final int SUB_FROM = 1119;
    private static final int SUB_TO = 2809;

    /** 残樱那一段（2046~2809）—— 反向断言要用。 */
    private static final int CHERRY_FROM = 2046;

    private static final String SELF_ID = "00000000-0000-0000-0000-0000000000b1";

    private static final Path SCRIPT = pick(
            Path.of("../QHCGS/GS/GS/scripts/gs_listener.js"),
            Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"));

    private static final Path LOG = pick(
            Path.of("docs/参考服日志_构思技艺.txt"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt"));

    private static final Path PSY_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/PsychicSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/PsychicSub.java"));

    private static final Path CH_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/CherrySub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/CherrySub.java"));

    // ======================================================================
    // 桥接桩
    // ======================================================================

    private static final String JS_STUBS = """
            var __log = [];          // getServer().getLogger() 收到的每一行
            var __online = [];       // getOnlinePlayers() 的返回值（可注入）
            var __pts = [];          // mock Location 构造记录
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
              getWorlds: function () { return []; }
            };
            function getServer() { return __srv; }
            function getAddonConfig() { return null; }
            function runLater(fn, d) { return null; }
            function runRepeating(fn, d, p) { return null; }

            // ★ 纯 JS 的 org.bukkit.util.Vector 替身。
            //   为什么不能用真 Vector：paper-api 不带 org.joml，而 GraalJS 暴露宿主对象时
            //   会扫 Vector 的全部公开方法（含 toVector3f():org.joml.Vector3f）
            //   ⇒ NoClassDefFoundError: org/joml/Vector3f（已实测）。
            //   语义与 Bukkit 的 Vector 逐条对齐（crossProduct/normalize 原地改并返回 this）。
            function __JSVec(x, y, z) {
              return {
                __v: true,
                getX: function () { return x; },
                getY: function () { return y; },
                getZ: function () { return z; },
                setX: function (v) { x = v; return this; },
                setY: function (v) { y = v; return this; },
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
     * <p>★ 无服务器环境下这些类会 {@code ExceptionInInitializerError}（不是能被 JS
     * {@code try/catch} 抓住的普通异常）⇒ 必须换掉：
     * {@code Attribute}（ConstantParityProbe 已记录）、{@code Particle}（注册表枚举）、
     * {@code PotionEffectType}（注册表）、{@code PotionEffect}（构造要真类型）、
     * {@code Location}（构造要真 World）。
     */
    private static final String JS_SHIM = """
            var __realJava = Java;
            var Java = { type: function (n) {
              if (n === 'org.bukkit.attribute.Attribute') {
                return { SCALE: null, valueOf: function () { return null; } };
              }
              if (n === 'org.bukkit.Particle') {
                return { CHERRY_LEAVES: 'P:CHERRY_LEAVES', END_ROD: 'P:END_ROD',
                         ELECTRIC_SPARK: 'P:ELECTRIC_SPARK', CRIT: 'P:CRIT',
                         valueOf: function (x) { return 'P:' + x; } };
              }
              if (n === 'org.bukkit.potion.PotionEffectType') {
                return { SLOWNESS: 'E:SLOWNESS', SLOW: 'E:SLOW', NIGHT_VISION: 'E:NIGHT_VISION',
                         valueOf: function (x) { return 'E:' + x; } };
              }
              if (n === 'org.bukkit.potion.PotionEffect') {
                return function (t, d, a, amb, part) {
                  return { __pe: true, type: t, duration: d, amplifier: a };
                };
              }
              if (n === 'org.bukkit.Location') {
                return function (w, x, y, z) {
                  var o = { __loc: true, __id: __pts.length, x: x, y: y, z: z,
                    getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                    getWorld: function () { return w; },
                    getBlock: function () { return { getType: function () { return { isSolid: function () { return false; } }; } }; }
                  };
                  __pts.push(o);
                  return o;
                };
              }
              if (n === 'org.bukkit.util.Vector') {
                return __JSVec;
              }
              if (n.indexOf('io.github.thebusybiscuit.slimefun4.implementation.tasks.armor') === 0) { return null; }
              return __realJava.type(n);
            } };
            """;

    /** mock 世界 / 玩家 / 实体 / PDC —— 全部是 JS 鸭子类型对象。 */
    private static final String JS_HELPERS = """
            var __snd = [], __ptc = [], __dmg = [], __shots = [], __fx = [], __fxRem = [], __fxCur = {};
            var __cands = [], __box = null, __curPtId = -1, __hitPtId = -1;
            var __SELF = '00000000-0000-0000-0000-0000000000b1';
            var __now = 0;
            var __hand = null;
            var __gunId = null;
            var __world = null;
            var __GUN = {
              __gun: true, __name: null,
              getItemMeta: function () { return { setDisplayName: function (n) { __GUN.__name = n; } }; },
              setItemMeta: function (m) { },
              clone: function () { return __GUN; },
              getType: function () { return { name: function () { return 'MUSIC_DISC_5'; } }; }
            };
            var __inv = {
              getItemInMainHand: function () { return __hand; },
              setItemInMainHand: function () { },
              getContents: function () { return []; },
              setItem: function () { },
              addItem: function () { return null; }
            };

            // ★ 物品判定开个口子：让「主手拿着枪」这条路能在 mock 里走通
            function __setGun(id) {
              __gunId = id;
              gsIsItem = function (item, want) { return item === __GUN && (__gunId === null || __gunId === want); };
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
                setNoDamageTicks: function () { },
                damage: function (d, src) {
                  __dmg.push({ id: c.id, dmg: d, src: (src !== undefined && src !== null) });
                  __hitPtId = __curPtId;
                }
              };
              if (!c.noHealth) { e.getHealth = function () { return 20.0; }; }
              return e;
            }

            function __mkWorld() {
              return {
                playSound: function (loc, name, vol, pitch) {
                  __snd.push({ id: (loc && loc.__id !== undefined) ? loc.__id : -1,
                               name: String(name), vol: vol, pitch: pitch });
                },
                spawnParticle: function (t, x, y, z) { __ptc.push([x, y, z]); },
                dropItemNaturally: function () { return null; },
                getNearbyEntities: function (pt, rx, ry, rz) {
                  __box = [rx, ry, rz];
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
              return {
                getUniqueId: function () { return __SELF; },
                getLocation: function () {
                  return { getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; } };
                },
                getEyeLocation: function () {
                  return { getX: function () { return x; }, getY: function () { return y + 1.62; }, getZ: function () { return z; },
                           getDirection: function () { return dir; } };
                },
                getWorld: function () { return __world; },
                getPersistentDataContainer: function () { return pdc; },
                getInventory: function () { return __inv; },
                getPotionEffect: function (t) { return __fxCur['' + t] || null; },
                addPotionEffect: function (pe, force) {
                  __fx.push({ t: '' + pe.type, d: pe.duration, a: pe.amplifier, force: force === true });
                  __fxCur['' + pe.type] = {
                    getDuration: function () { return pe.duration; },
                    getAmplifier: function () { return pe.amplifier; }
                  };
                },
                removePotionEffect: function (t) { __fxRem.push('' + t); delete __fxCur['' + t]; }
              };
            }

            // 可控时钟
            gsPsychicNow = function () { return __now; };
            gsCherryNow = function () { return __now; };

            // ★ 记「真的打了几发、每发的 (side, range)」——
            //   开火入口 gsPsychicFire / gsCherryFire 只做节流，出膛都经过这两个函数。
            var __realPsyShoot = gsPsychicShoot;
            var __realCherryRay = gsCherryShootRay;
            gsPsychicShoot = function (p) { __shots.push([0, 'psy']); return __realPsyShoot(p); };
            gsCherryShootRay = function (p, side, range) { __shots.push([side, range]); return __realCherryRay(p, side, range); };

            // ============ 捕获入口 ============

            // ★ 直接调这两个入口时也要清空记录，免得污染 __psyFireGrid / __chFireGrid
            function __cherryRay(arg) {
              __pts = []; __snd = []; __ptc = []; __dmg = []; __cands = arg.cands || [];
              __shots = [];
              __box = null; __hitPtId = -1; __curPtId = -1;
              __world = __mkWorld();
              var p = __mkPlayer(arg.sx, arg.sy, arg.sz, arg.dx, arg.dy, arg.dz, __mkPdc({}));
              __realCherryRay(p, arg.side, arg.range);
              return { pts: __pts, snd: __snd, ptc: __ptc, dmg: __dmg, box: __box, hitPtId: __hitPtId };
            }

            function __psyRay(arg) {
              __pts = []; __snd = []; __ptc = []; __dmg = []; __cands = arg.cands || [];
              __shots = [];
              __box = null; __hitPtId = -1; __curPtId = -1;
              __world = __mkWorld();
              var init = {};
              if (arg.scoped) { init['gs:psy_scoped'] = '1'; }
              var p = __mkPlayer(arg.sx, arg.sy, arg.sz, arg.dx, arg.dy, arg.dz, __mkPdc(init));
              __realPsyShoot(p);
              return { pts: __pts, snd: __snd, ptc: __ptc, dmg: __dmg, box: __box, hitPtId: __hitPtId };
            }

            function __scopeRing(arg) {
              __pts = []; __ptc = []; __snd = [];
              __world = __mkWorld();
              var p = __mkPlayer(arg.sx, arg.sy, arg.sz, arg.dx, arg.dy, arg.dz, __mkPdc({ 'gs:psy_scoped': '1' }));
              gsScopeDraw(p);
              return { ptc: __ptc, pts: __pts, snd: __snd };
            }

            function __scopeToggleSeq(times) {
              GS_SCOPE_TOGGLE_AT = {};
              var pdc = __mkPdc({});
              var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
              var out = [];
              for (var i = 0; i < times.length; i++) {
                __now = times[i];
                gsScopeToggle(p);
                out.push(pdc.has('gs:psy_scoped', null) ? 1 : 0);
              }
              return out;
            }

            function __psyFireGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __shots = []; __now = 1000000;
                var init = {};
                if (c[0] !== null) { init['gs:psy_ammo'] = c[0]; }
                if (c[1] !== null) { init['gs:psy_lastshot'] = __now - c[1]; }
                if (c[2] !== null) { init['gs:psy_reload'] = __now - c[2]; }
                var pdc = __mkPdc(init);
                var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
                gsPsychicFire(p);
                out.push([
                  pdc.has('gs:psy_ammo', null) ? pdc.get('gs:psy_ammo', null) : -1,
                  pdc.has('gs:psy_lastshot', null) ? 1 : 0,
                  pdc.has('gs:psy_reload', null) ? 1 : 0,
                  __shots.length
                ]);
              }
              return out;
            }

            function __chFireGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __shots = []; __now = 1000000;
                var init = {};
                if (c[0] !== null) { init['gs:ch_ammo'] = c[0]; }
                if (c[1] !== null) { init['gs:ch_lastshot'] = __now - c[1]; }
                if (c[2] !== null) { init['gs:ch_reload'] = __now - c[2]; }
                if (c[3] !== null) { init['gs:ch_mode'] = c[3]; }
                var pdc = __mkPdc(init);
                var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
                gsCherryFire(p);
                var rays = [];
                for (var j = 0; j < __shots.length; j++) { rays.push(__shots[j][0] + ':' + __shots[j][1]); }
                out.push([
                  pdc.has('gs:ch_ammo', null) ? pdc.get('gs:ch_ammo', null) : -1,
                  pdc.has('gs:ch_lastshot', null) ? 1 : 0,
                  pdc.has('gs:ch_reload', null) ? 1 : 0,
                  __shots.length,
                  rays.join(',')
                ]);
              }
              return out;
            }

            function __chToggleGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                __now = 1000000;
                var init = {};
                if (c[0] !== null) { init['gs:ch_mode'] = c[0]; }
                if (c[1] !== null) { init['gs:ch_switch'] = __now - c[1]; }
                var pdc = __mkPdc(init);
                var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
                gsCherryToggleMode(p);
                out.push([
                  pdc.has('gs:ch_mode', null) ? pdc.get('gs:ch_mode', null) : -1,
                  pdc.has('gs:ch_switch', null) ? 1 : 0
                ]);
              }
              return out;
            }

            // 开镜药效：每步给一组「当前身上有什么」，看本机制这一轮加了几个
            //   c = [slowNull, slowDur, slowAmp, nvNull, nvDur]
            function __scopeFocusGrid(cases, resetOwned) {
              __fx = []; __fxRem = []; __fxCur = {};
              gsScopeFocusOn = {}; gsScopeNightOn = {};
              var pdc = __mkPdc({ 'gs:psy_scoped': '1' });
              var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];
                if (resetOwned) { gsScopeNightOn = {}; gsScopeFocusOn = {}; }
                __fxCur = {};
                if (!c[0]) { __fxCur['E:SLOWNESS'] = { getDuration: function () { return c[1]; }, getAmplifier: function () { return c[2]; } }; }
                if (!c[3]) { __fxCur['E:NIGHT_VISION'] = { getDuration: function () { return c[4]; }, getAmplifier: function () { return 0; } }; }
                var before = __fx.length;
                gsScopeFocus(p);
                out.push(__fx.length - before);
              }
              return out;
            }

            // 摘镜：只收走本机制给的那一份
            function __scopeUnfocusGrid(cases) {
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];   // [focusFlagged, slowAmp, nvFlagged]
                __fxRem = []; __fxCur = {};
                gsScopeFocusOn = {}; gsScopeNightOn = {};
                if (c[0]) { gsScopeFocusOn[__SELF] = true; }
                if (c[2]) { gsScopeNightOn[__SELF] = true; }
                if (c[1] >= 0) { __fxCur['E:SLOWNESS'] = { getDuration: function () { return 100; }, getAmplifier: function () { return c[1]; } }; }
                if (c[2]) { __fxCur['E:NIGHT_VISION'] = { getDuration: function () { return 100; }, getAmplifier: function () { return 0; } }; }
                var p = __mkPlayer(0, 0, 0, 0, 0, 1, __mkPdc({ 'gs:psy_scoped': '1' }));
                gsScopeUnfocus(p);
                out.push(__fxRem.join(','));
              }
              return out;
            }

            // 物品名（真 gsPsychicUpdateName / gsCherryUpdateName 跑出来的显示名）
            function __names(cases) {
              __setGun(null);
              var out = [];
              for (var i = 0; i < cases.length; i++) {
                var c = cases[i];   // [which, ammo, mode, reloadAgo]
                __now = 1000000;
                var init = {};
                if (c[1] !== null) { init['gs:psy_ammo'] = c[1]; }
                if (c[1] !== null) { init['gs:ch_ammo'] = c[1]; }
                if (c[2] !== null) { init['gs:ch_mode'] = c[2]; }
                if (c[3] !== null) {
                  init['gs:psy_reload'] = __now - c[3];
                  init['gs:ch_reload'] = __now - c[3];
                }
                var pdc = __mkPdc(init);
                var p = __mkPlayer(0, 0, 0, 0, 0, 1, pdc);
                __hand = __GUN;
                __GUN.__name = null;
                if (c[0] === 'psy') { gsPsychicUpdateName(p); }
                else { gsCherryUpdateName(p); }
                out.push(__GUN.__name === null ? '' : __GUN.__name);
              }
              __hand = null;
              return out;
            }

            // PDC 键名
            function __keyNames() {
              var a = gsPsychicKeys(), b = gsCherryKeys();
              return {
                psy: ['' + a.ammo, '' + a.lastShot, '' + a.reload, '' + a.shooting],
                ch: ['' + b.ammo, '' + b.lastShot, '' + b.reload, '' + b.shooting, '' + b.mode, '' + b.switchAt],
                scoped: '' + GS_SCOPE_SCOPED_KEY,
                scope: '' + GS_SCOPE_KEY
              };
            }

            function __logLines() { return __log; }
            """;

    // ======================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== ListenerB 子系统对账探针：开镜+余念绝响 / 残樱（原脚本 1119~2809）===");
        System.out.println("脚本: " + SCRIPT);
        System.out.println("日志: " + LOG);
        System.out.println();

        String[] raw = readAll(SCRIPT);
        check("脚本存在且行数 > 2809（原脚本共 " + raw.length + " 行）", raw.length > SUB_TO);
        if (raw.length <= SUB_TO) {
            summary();
            return;
        }
        check("第 2044 行恰好是 gsPsychicStart();", raw[2043].trim().equals("gsPsychicStart();"));
        check("第 2809 行恰好是 gsCherryStart();", raw[2808].trim().equals("gsCherryStart();"));

        String region = String.join("\n", Arrays.copyOfRange(raw, SUB_FROM - 1, SUB_TO));
        String cherryRegion = String.join("\n", Arrays.copyOfRange(raw, CHERRY_FROM - 1, SUB_TO));

        String prefix = String.join("\n", Arrays.copyOfRange(raw, 0, SUB_TO));

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
        check("原脚本前 2809 行能在桥接桩下求值（真 JS）", evalOk);
        ctx.eval(Source.newBuilder("js", JS_HELPERS, "helpers").build());

        constants(ctx);
        logLines(ctx);
        geometry(ctx);
        reverse(ctx, region, cherryRegion);

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
        System.out.println("[A] 常量对账：真 JS 顶层 var vs Java 字段（按名按值）");

        PsychicSub psy = new PsychicSub(new ListenerCore());
        CherrySub ch = new CherrySub(new ListenerCore());
        Value b = ctx.getBindings("js");

        String[] psyNames = {
                "GS_PSYCHIC_ID", "GS_PSY_MUZZLE_Y", "GS_PSY_AMMO", "GS_PSY_FIRE_MS", "GS_PSY_RELOAD_MS",
                "GS_PSY_RANGE", "GS_PSY_HIT_R", "GS_PSY_DMG_MIN", "GS_PSY_DMG_MAX",
                "GS_SCOPE_RADIUS", "GS_SCOPE_DIST", "GS_SCOPE_POINTS", "GS_SCOPE_DEBOUNCE_MS",
                "GS_SCOPE_FOCUS_TICKS", "GS_SCOPE_FOCUS_REF", "GS_SCOPE_NV_TICKS", "GS_SCOPE_NV_REF"
        };
        String[] chNames = {
                "GS_CH_ID", "GS_CH_AMMO", "GS_CH_FIRE_MS", "GS_CH_RELOAD_MS", "GS_CH_RANGE", "GS_CH_DMG",
                "GS_CH_MUZZLE_Y", "GS_CH_STEP", "GS_CH_HIT_R", "GS_CH_BURST_N", "GS_CH_BURST_MS",
                "GS_CH_BURST_RANGE", "GS_CH_SPREAD_BASE", "GS_CH_SPREAD_SIDES", "GS_CH_SWITCH_MS",
                "GS_CH_SND_FIRE", "GS_CH_SND_RELOAD", "GS_CH_SND_SWITCH"
        };

        int n = 0;
        for (String name : psyNames) {
            n += one(b, PsychicSub.class, psy, name);
        }
        for (String name : chNames) {
            n += one(b, CherrySub.class, ch, name);
        }
        check("常量对账条数 = " + n + "（应为 " + (psyNames.length + chNames.length) + "）",
                n == psyNames.length + chNames.length);

        // 两个 NamespacedKey（JS 侧是宿主对象 ⇒ 比 toString）
        check("GS_SCOPE_SCOPED_KEY = gs:psy_scoped",
                String.valueOf(b.getMember("GS_SCOPE_SCOPED_KEY")).equals(PsychicSub.GS_SCOPE_SCOPED_KEY.toString()));
        check("GS_SCOPE_KEY = gs:psy_scope",
                String.valueOf(b.getMember("GS_SCOPE_KEY")).equals(PsychicSub.GS_SCOPE_KEY.toString()));

        // PDC 键名（GS_PSY_KEYS / GS_CH_KEYS 是懒加载对象，调真 JS 函数造出来）
        Value keys = ctx.eval("js", "__keyNames()");
        Value psyK = keys.getMember("psy");
        Value chK = keys.getMember("ch");
        PsychicSub.Keys jp = psy.gsPsychicKeys();
        CherrySub.Keys jc = ch.gsCherryKeys();
        check("gs:psy_ammo", psyK.getArrayElement(0).asString().equals(jp.ammo.toString()));
        check("gs:psy_lastshot", psyK.getArrayElement(1).asString().equals(jp.lastShot.toString()));
        check("gs:psy_reload", psyK.getArrayElement(2).asString().equals(jp.reload.toString()));
        check("gs:psy_shooting", psyK.getArrayElement(3).asString().equals(jp.shooting.toString()));
        check("gs:ch_ammo", chK.getArrayElement(0).asString().equals(jc.ammo.toString()));
        check("gs:ch_lastshot", chK.getArrayElement(1).asString().equals(jc.lastShot.toString()));
        check("gs:ch_reload", chK.getArrayElement(2).asString().equals(jc.reload.toString()));
        check("gs:ch_shooting", chK.getArrayElement(3).asString().equals(jc.shooting.toString()));
        check("gs:ch_mode", chK.getArrayElement(4).asString().equals(jc.mode.toString()));
        check("gs:ch_switch", chK.getArrayElement(5).asString().equals(jc.switchAt.toString()));

        // ★ Java 侧独有的具名常量：原脚本是内联字面量 ⇒ 直接钉住脚本里的字面量
        check("Java GS_PSY_DMG_HIP = 37.0 ⇔ 脚本里 'dmg = scoped ? 77.0 : 37.0'",
                regionContains("dmg = scoped ? 77.0 : 37.0"));
        check("Java GS_PSY_SCOPED_RANGE = 77.0 ⇔ 脚本里 'range = scoped ? 77.0 : GS_PSY_RANGE'",
                regionContains("range = scoped ? 77.0 : GS_PSY_RANGE"));
        check("Java GS_PSY_STEP = 0.5 ⇔ 脚本里 'd += 0.5;'（余念绝响用字面量）",
                regionContains("d += 0.5;"));
        check("Java GS_SCOPE_FOCUS_AMP = 9 ⇔ 脚本里 'cur.getAmplifier() === 9'",
                regionContains("cur.getAmplifier() === 9"));
        check("★ GS_PSY_DMG_MIN/MAX 只出现在日志拼接里（脚本里无第二处引用）",
                countOf(regionText(), "GS_PSY_DMG_MIN") == 2 && countOf(regionText(), "GS_PSY_DMG_MAX") == 2);
        System.out.println();
    }

    /** 按名比对 JS 顶层 var 与 Java 字段（静态优先，其次实例字段）。 */
    private static int one(Value b, Class<?> cls, Object inst, String name) throws Exception {
        if (!b.hasMember(name)) {
            check(name + "（JS 里有这个顶层 var）", false);
            return 0;
        }
        Object jsVal = toJava(b.getMember(name));
        java.lang.reflect.Field f = null;
        for (java.lang.reflect.Field x : cls.getDeclaredFields()) {
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

    private static String regionText() {
        if (regionTextCache == null) {
            try {
                String[] raw = readAll(SCRIPT);
                regionTextCache = String.join("\n", Arrays.copyOfRange(raw, SUB_FROM - 1, SUB_TO));
            } catch (Throwable t) {
                regionTextCache = "";
            }
        }
        return regionTextCache;
    }

    private static boolean regionContains(String s) {
        return regionText().contains(s);
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

        String jsPsyReg = findInfo(js, "余念绝响监听器注册成功");
        String jsPsyStart = findInfo(js, "余念绝响：单手持冲锋枪已启用");
        String jsChReg = findInfo(js, "残樱监听器注册成功");
        String jsChStart = findInfo(js, "残樱：单手持手枪已启用");

        check("真 JS 打出了「余念绝响监听器注册成功」那一行", jsPsyReg != null);
        check("真 JS 打出了「余念绝响：单手持冲锋枪已启用」那一行", jsPsyStart != null);
        check("真 JS 打出了「残樱监听器注册成功」那一行", jsChReg != null);
        check("真 JS 打出了「残樱：单手持手枪已启用」那一行", jsChStart != null);

        check("Java 注册行 == 真 JS 注册行", PsychicSub.gsPsychicRegisterLine().equals(jsPsyReg));
        check("Java 启用行 == 真 JS 启用行", PsychicSub.gsPsychicStartLine().equals(jsPsyStart));
        check("Java 注册行 == 真 JS 注册行（残樱）", CherrySub.gsCherryRegisterLine().equals(jsChReg));
        check("Java 启用行 == 真 JS 启用行（残樱）", CherrySub.gsCherryStartLine().equals(jsChStart));

        // 参考服日志
        String[] ref = readAll(LOG);
        String refPsyReg = findLine(ref, "余念绝响监听器注册成功");
        String refPsyStart = findLine(ref, "余念绝响：单手持冲锋枪已启用");
        String refChReg = findLine(ref, "残樱监听器注册成功");
        String refChStart = findLine(ref, "残樱：单手持手枪已启用");

        check("参考服日志有「余念绝响监听器注册成功」", refPsyReg != null);
        check("参考服日志有「余念绝响：单手持冲锋枪已启用」", refPsyStart != null);
        check("参考服日志有「残樱监听器注册成功」", refChReg != null);
        check("参考服日志有「残樱：单手持手枪已启用」", refChStart != null);

        check("Java 注册行 == 参考服日志行（余念绝响）", PsychicSub.gsPsychicRegisterLine().equals(refPsyReg));
        check("Java 启用行 == 参考服日志行（余念绝响）", PsychicSub.gsPsychicStartLine().equals(refPsyStart));
        check("Java 注册行 == 参考服日志行（残樱）", CherrySub.gsCherryRegisterLine().equals(refChReg));
        check("Java 启用行 == 参考服日志行（残樱）", CherrySub.gsCherryStartLine().equals(refChStart));

        // ★★ 残樱那行的结尾：拆分契约的转写把它截断了，脚本与参考日志都以「F 换弹）」收尾
        check("★ 残樱启用行以「F 换弹）」收尾（不是截断的「换弹 1.8 秒」）",
                CherrySub.gsCherryStartLine().endsWith("，左键开火，右键切换发射方式，F 换弹）"));
        check("★ 参考服日志那一行也以「F 换弹）」收尾",
                refChStart != null && refChStart.endsWith("，左键开火，右键切换发射方式，F 换弹）"));

        // 逐字节展示（前 2 条），便于人工核对
        System.out.println("      · JS 余念绝响启用行: " + jsPsyStart);
        System.out.println("      · JS 残樱启用行    : " + jsChStart);
        System.out.println();
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
    // [C] 几何 / 算式
    // ======================================================================

    private static void geometry(Context ctx) throws Exception {
        System.out.println("[C] 数值 / 几何算式：真 JS 函数（mock 世界）vs Java 同一算式");

        cherryRay(ctx);
        cherryHit(ctx);
        cherryFire(ctx);
        psychicRay(ctx);
        scopeRing(ctx);
        scopeToggle(ctx);
        scopeFocus(ctx);
        psyFire(ctx);
        names(ctx);

        System.out.println();
    }

    // ---- C1 樱花射线：采样点 / 横向偏移 / 音效 ----

    private static void cherryRay(Context ctx) throws Exception {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {-0.7071067811865476, 0, 0.7071067811865476},
                {0, 1, 0}, {0, -1, 0}, {0.6, 0.8, 0},
                {0.3, -0.4, 0.8660254037844386},
                {1.0e-5, 0, 1}, {0, 0, 1.0e-5}
        };
        int[] sides = {0, -1, 1};
        double[] ranges = {16.0, 6.0, 3.0};

        StringBuilder sb = new StringBuilder("[");
        List<double[]> expect = new ArrayList<>();
        List<double[]> meta = new ArrayList<>();
        boolean first = true;
        int cases = 0;
        for (double[] d : dirs) {
            for (int side : sides) {
                for (double range : ranges) {
                    double sx = 0.5, sy = 64.0, sz = -3.25;
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append("{\"sx\":").append(sx).append(",\"sy\":").append(sy).append(",\"sz\":").append(sz)
                            .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1]).append(",\"dz\":").append(d[2])
                            .append(",\"side\":").append(side).append(",\"range\":").append(range).append(",\"cands\":[]}");
                    meta.add(new double[]{sx, sy, sz, d[0], d[1], d[2], side, range});
                    cases++;
                }
            }
        }
        sb.append(']');

        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__ARG = JSON.parse(__ARG); (function(){ var r = []; for (var i = 0; i < __ARG.length; i++) { r.push(__cherryRay(__ARG[i])); } return r; })()");

        int bad = 0;
        int ptsTotal = 0;
        boolean offsetConst = true;
        boolean soundRule = true;
        for (int i = 0; i < cases; i++) {
            Value r = out.getArrayElement(i);
            double[] m = meta.get(i);
            double sx = m[0], sy = m[1], sz = m[2], side = m[6], range = m[7];
            // ★ 脚本里发射起点是「胸口」：base.getY() + GS_CH_MUZZLE_Y
            double my = sy + CherrySub.GS_CH_MUZZLE_Y;
            Vector dir = new Vector(m[3], m[4], m[5]);
            double[] basis = CherrySub.gsCherryRightBasis(m[3], m[5]);
            double off = CherrySub.gsCherryRayOffset((int) side);

            // 音效：只有 side === 0 会响，且响在胸口
            Value snd = r.getMember("snd");
            if (side == 0) {
                if (snd.getArraySize() != 1) {
                    soundRule = false;
                } else {
                    Value s0 = snd.getArrayElement(0);
                    if (Math.abs(s0.getMember("vol").asDouble() - 1.0) > 0
                            || Math.abs(s0.getMember("pitch").asDouble() - 1.4) > 0
                            || !s0.getMember("name").asString().equals(CherrySub.GS_CH_SND_FIRE)) {
                        soundRule = false;
                    }
                }
            } else if (snd.getArraySize() != 0) {
                soundRule = false;
            }

            // 采样点：pts 里第一个是音效 Location（side==0 时）
            Value pts = r.getMember("pts");
            int soundCount = (side == 0) ? 1 : 0;
            int expectN = 0;
            for (double d = 0.0; d <= range; d += CherrySub.GS_CH_STEP) {
                expectN++;
            }
            ptsTotal += expectN;
            if (pts.getArraySize() != expectN + soundCount) {
                bad++;
                continue;
            }
            int k = 0;
            double prevLat = Double.NaN;
            for (double d = 0.0; d <= range; d += CherrySub.GS_CH_STEP) {
                double[] c = CherrySub.gsCherryRayPoint(sx, my, sz, dir, basis[0], basis[1], off, d);
                Value p = pts.getArrayElement(soundCount + k);
                double jx = p.getMember("x").asDouble();
                double jy = p.getMember("y").asDouble();
                double jz = p.getMember("z").asDouble();
                if (jx != c[0] || jy != c[1] || jz != c[2]) {
                    bad++;
                    if (bad <= 3) {
                        System.out.println("      差异 side=" + side + " range=" + range + " d=" + d
                                + " JS=(" + jx + "," + jy + "," + jz + ") Java=(" + c[0] + "," + c[1] + "," + c[2] + ")");
                    }
                }
                // ★ 横向偏移全程恒定：每个采样点到中线的横向距离都一样
                double lat = Math.hypot(jx - (sx + dir.getX() * d), jz - (sz + dir.getZ() * d));                if (!Double.isNaN(prevLat) && Math.abs(lat - prevLat) > 1.0e-12) {
                    offsetConst = false;
                }
                prevLat = lat;
                k++;
            }
        }
        check("樱花射线采样点与 Java 算式逐值一致（" + cases + " 组方向/侧向/射程，共 " + ptsTotal + " 个采样点）", bad == 0);
        check("★ 横向偏移全程恒定（不随距离散布）", offsetConst);
        check("★ 发射音效只在 side===0 那一发响一次（铜箱子关闭 1.0/1.4）", soundRule);

        // 箱体半径 = GS_CH_HIT_R（三轴同值）
        boolean boxOk = true;
        for (int i = 0; i < cases; i++) {
            Value bx = out.getArrayElement(i).getMember("box");
            if (bx.isNull() || Math.abs(bx.getArrayElement(0).asDouble() - CherrySub.GS_CH_HIT_R) > 0
                    || Math.abs(bx.getArrayElement(1).asDouble() - CherrySub.GS_CH_HIT_R) > 0
                    || Math.abs(bx.getArrayElement(2).asDouble() - CherrySub.GS_CH_HIT_R) > 0) {
                boxOk = false;
            }
        }
        check("命中判定盒三轴都传 GS_CH_HIT_R = 0.8", boxOk);
    }

    // ---- C2 命中筛选（跳过自己 / 死物 / 无血条 / 展示类）----

    private static void cherryHit(Context ctx) throws Exception {
        // 目标放在正中射线的 d=5.0 处
        double tx = 0.5, ty = 64.0 + 1.2, tz = -3.25 + 5.0;
        String[][] candSets = {
                {},
                {ent(SELF_ID, tx, ty, tz, "ZOMBIE", false, false)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ZOMBIE", false, false)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ARMOR_STAND", false, false)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ITEM", false, false)},
                // ★ DROPPED_ITEM 在 1.21.11 已改名 ITEM ⇒ 拿不到这个枚举值，
                //   那一支只能做静态断言（见 [D]），这里换成"不在射线上"的漏靶组。
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz + 9.0, "ZOMBIE", false, false)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ZOMBIE", true, false)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ZOMBIE", false, true)},
                {ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ARMOR_STAND", false, false),
                 ent("00000000-0000-0000-0000-0000000000c2", tx, ty, tz, "ZOMBIE", false, false)},
                {ent(SELF_ID, tx, ty, tz, "ZOMBIE", false, false),
                 ent("00000000-0000-0000-0000-0000000000c2", tx, ty, tz, "ITEM", false, false),
                 ent("00000000-0000-0000-0000-0000000000c3", tx, ty, tz, "SKELETON", false, false)}
        };

        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < candSets.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"sx\":0.5,\"sy\":64.0,\"sz\":-3.25,\"dx\":0,\"dy\":0,\"dz\":1,\"side\":0,\"range\":16.0,\"cands\":[");
            sb.append(String.join(",", candSets[i]));
            sb.append("]}");
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__ARG = JSON.parse(__ARG); (function(){ var r = []; for (var i = 0; i < __ARG.length; i++) { r.push(__cherryRay(__ARG[i])); } return r; })()");

        int agree = 0;
        for (int i = 0; i < candSets.length; i++) {
            Value r = out.getArrayElement(i);
            // JS 侧：命中的采样序号 = hitPtId - 1（side=0 ⇒ 第 0 个 pts 是音效点）
            int jsIdx = -1;
            Value hit = r.getMember("hitPtId");
            if (!hit.isNull() && hit.asInt() >= 0) {
                jsIdx = hit.asInt() - 1;
            }
            // Java 侧：同一算法（生产代码的 gsCherryFirstTarget + 同一采样序列）
            int jIdx = javaFirstHitIndex(candSets[i]);
            boolean ok = jsIdx == jIdx;
            check("命中筛选 [" + i + "] 命中序号 JS=" + jsIdx + " Java=" + jIdx, ok);
            if (ok) {
                agree++;
            }
            // 伤害值 8.0，且带来源
            Value dmg = r.getMember("dmg");
            if (jsIdx >= 0) {
                check("命中 [" + i + "] 伤害 = GS_CH_DMG(8.0) 且带来源",
                        dmg.getArraySize() == 1
                                && Math.abs(dmg.getArrayElement(0).getMember("dmg").asDouble() - CherrySub.GS_CH_DMG) < 1.0e-12
                                && dmg.getArrayElement(0).getMember("src").asBoolean());
            } else {
                check("未命中 [" + i + "] 没有结算任何伤害", dmg.getArraySize() == 0);
            }
        }
        check("命中筛选 " + agree + "/" + candSets.length + " 组与 Java 判定一致", agree == candSets.length);
    }

    /** Java 侧复算：同一采样序列 + 生产代码的 gsCherryFirstTarget。 */
    private static int javaFirstHitIndex(String[] cands) {
        double sx = 0.5, sy = 64.0 + CherrySub.GS_CH_MUZZLE_Y, sz = -3.25;
        Vector dir = new Vector(0, 0, 1);
        double[] basis = CherrySub.gsCherryRightBasis(0, 1);
        double off = CherrySub.gsCherryRayOffset(0);
        int idx = 0;
        for (double d = 0.0; d <= CherrySub.GS_CH_RANGE; d += CherrySub.GS_CH_STEP) {
            double[] c = CherrySub.gsCherryRayPoint(sx, sy, sz, dir, basis[0], basis[1], off, d);
            List<Entity> in = new ArrayList<>();
            for (String s : cands) {
                double[] e = parseEnt(s);
                if (Math.abs(e[0] - c[0]) <= CherrySub.GS_CH_HIT_R
                        && Math.abs(e[1] - c[1]) <= CherrySub.GS_CH_HIT_R
                        && Math.abs(e[2] - c[2]) <= CherrySub.GS_CH_HIT_R) {
                    in.add(fakeEntity(e, entId(s), entType(s)));
                }
            }
            if (CherrySub.gsCherryFirstTarget(SELF_ID, in) != null) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    private static String ent(String id, double x, double y, double z, String type, boolean dead, boolean noHealth) {
        return "{\"id\":\"" + id + "\",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"type\":\"" + type + "\",\"dead\":" + dead + ",\"noHealth\":" + noHealth + "}";
    }

    /** {@code {id,x,y,z,type,dead,noHealth}} → double[5] = {x,y,z,dead,noHealth}（id 单独取）。 */
    private static double[] parseEnt(String json) {
        double x = num(json, "x");
        double y = num(json, "y");
        double z = num(json, "z");
        double dead = json.contains("\"dead\":true") ? 1 : 0;
        double noHealth = json.contains("\"noHealth\":true") ? 1 : 0;
        return new double[]{x, y, z, dead, noHealth};
    }

    private static String entId(String json) {
        int i = json.indexOf("\"id\":\"");
        return json.substring(i + 6, json.indexOf('"', i + 6));
    }

    private static String entType(String json) {
        int i = json.indexOf("\"type\":\"");
        return json.substring(i + 8, json.indexOf('"', i + 8));
    }

    private static double num(String json, String key) {
        int i = json.indexOf("\"" + key + "\":");
        int j = i + key.length() + 3;
        int k = j;
        while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-'
                || json.charAt(k) == '.' || json.charAt(k) == 'E' || json.charAt(k) == 'e')) {
            k++;
        }
        return Double.parseDouble(json.substring(j, k));
    }

    private static Entity fakeEntity(double[] e, String id, String type) {
        return (Entity) Proxy.newProxyInstance(ListenerBSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Entity.class, Damageable.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(id);
                        case "isDead":
                            return e[3] != 0;
                        case "getHealth":
                            if (e[4] != 0) {
                                throw new UnsupportedOperationException("no getHealth");
                            }
                            return 20.0;
                        case "getType":
                            return org.bukkit.entity.EntityType.valueOf(type);
                        case "getScoreboardTags":
                            return java.util.Set.of();
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    // ---- C3 残樱开火状态机 ----

    private static void cherryFire(Context ctx) throws Exception {
        Integer[] ammos = {null, 0, 1, 2, 3, 11, 12, 13};
        Integer[] lastAgos = {null, 0, 15, 25, 30, 31, 100, 199, 200, 201, 290, 300, 301, 999999};
        Integer[] reloadAgos = {null, 0, 100, 1000, 1700, 1800, 1801, 3000};
        Integer[] modes = {null, 0, 1};

        StringBuilder sb = new StringBuilder("[");
        List<Integer[]> cases = new ArrayList<>();
        boolean first = true;
        for (Integer a : ammos) {
            for (Integer l : lastAgos) {
                for (Integer r : reloadAgos) {
                    for (Integer m : modes) {
                        if (!first) {
                            sb.append(',');
                        }
                        first = false;
                        sb.append('[').append(a == null ? "null" : a).append(',')
                                .append(l == null ? "null" : l).append(',')
                                .append(r == null ? "null" : r).append(',')
                                .append(m == null ? "null" : m).append(']');
                        cases.add(new Integer[]{a, l, r, m});
                    }
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__chFireGrid(JSON.parse(__ARG))");

        CherrySub ch = new CherrySub(new ListenerCore());
        int mismatch = 0;
        int firedCases = 0;
        int burstRayOk = 0;
        int burstRayBad = 0;
        int unstable = 0;
        for (int i = 0; i < cases.size(); i++) {
            Integer[] c = cases.get(i);

            // ★ 时钟稳定化（同 psyFire）：用同一个 t0 写时间戳与判定，
            //   并要求整段落在同一毫秒内 —— 这样 lastShotAgo / reloadLeft 必然等于给定值。
            int jAmmo = -1, jLast = 0, jReload = 0;
            boolean stable = false;
            for (int attempt = 0; attempt < 500 && !stable; attempt++) {
                long t0 = System.currentTimeMillis();
                FakePdc pdc = new FakePdc();
                if (c[0] != null) {
                    pdc.m.put("gs:ch_ammo", c[0]);
                }
                if (c[1] != null) {
                    pdc.m.put("gs:ch_lastshot", t0 - c[1]);
                }
                if (c[2] != null) {
                    pdc.m.put("gs:ch_reload", t0 - c[2]);
                }
                if (c[3] != null) {
                    pdc.m.put("gs:ch_mode", c[3]);
                }
                Player p = fakePlayer(pdc);
                ch.gsCherryFire(p);
                if (System.currentTimeMillis() != t0) {
                    continue;
                }
                jAmmo = pdc.m.containsKey("gs:ch_ammo") ? ((Number) pdc.m.get("gs:ch_ammo")).intValue() : -1;
                jLast = pdc.m.containsKey("gs:ch_lastshot") ? 1 : 0;
                jReload = pdc.m.containsKey("gs:ch_reload") ? 1 : 0;
                stable = true;
            }
            if (!stable) {
                unstable++;
                continue;
            }

            int ammoBefore = c[0] == null ? CherrySub.GS_CH_AMMO : c[0];

            Value r = out.getArrayElement(i);
            int sAmmo = r.getArrayElement(0).asInt();
            int sLast = r.getArrayElement(1).asInt();
            int sReload = r.getArrayElement(2).asInt();
            int sShots = r.getArrayElement(3).asInt();
            String sRays = r.getArrayElement(4).asString();

            boolean ok = sAmmo == jAmmo && sLast == jLast && sReload == jReload;
            if (!ok) {
                mismatch++;
                if (mismatch <= 5) {
                    System.out.println("      差异 case=" + Arrays.toString(c)
                            + " JS(ammo,last,reload)=(" + sAmmo + "," + sLast + "," + sReload + ")"
                            + " Java=(" + jAmmo + "," + jLast + "," + jReload + ")");
                }
            }
            if (sShots > 0) {
                firedCases++;
            }
            // 三连发：rays 必须恰好是 [0:6, -1:6, 1:6]（有几发打几发）
            boolean burst = c[3] != null && c[3] == 1;
            if (burst && sShots > 0) {
                int n = CherrySub.gsCherryBurstCount(ammoBefore);
                StringBuilder want = new StringBuilder();
                for (int k = 0; k < n; k++) {
                    if (k > 0) {
                        want.append(',');
                    }
                    want.append(CherrySub.GS_CH_SPREAD_SIDES[k]).append(':').append((int) CherrySub.GS_CH_BURST_RANGE);
                }
                if (sRays.equals(want.toString())) {
                    burstRayOk++;
                } else {
                    burstRayBad++;
                    if (burstRayBad <= 3) {
                        System.out.println("      三连发射线差异 case=" + Arrays.toString(c)
                                + " JS=[" + sRays + "] 期望=[" + want + "]");
                    }
                }
            } else if (!burst && sShots > 0) {
                // 单发：一条正中射线，射程 16
                if (!sRays.equals("0:16")) {
                    burstRayBad++;
                } else {
                    burstRayOk++;
                }
            }
        }
        check("残樱开火状态机（" + cases.size() + " 组预状态：ammo×lastShotAgo×reloadAgo×mode）JS 与 Java 完全一致",
                mismatch == 0);
        check("时钟稳定化：没有卡在同一毫秒外的组（不稳定 " + unstable + " 组）", unstable == 0);
        check("残樱开火确实发生过的组数 = " + firedCases + "（> 0）", firedCases > 0);
        check("射线参数与 Java 算式一致（三连发 正中/左/右 × 6 格 · 单发 正中 × 16 格）",
                burstRayBad == 0 && burstRayOk > 0);

        // 切换开火方式（1 秒冷却）
        StringBuilder tb = new StringBuilder("[");
        List<Integer[]> tc = new ArrayList<>();
        boolean f2 = true;
        for (Integer m : new Integer[]{null, 0, 1}) {
            for (Integer s : new Integer[]{null, 0, 500, 999, 1000, 1001, 5000}) {
                if (!f2) {
                    tb.append(',');
                }
                f2 = false;
                tb.append('[').append(m == null ? "null" : m).append(',').append(s == null ? "null" : s).append(']');
                tc.add(new Integer[]{m, s});
            }
        }
        tb.append(']');
        ctx.getBindings("js").putMember("__ARG", tb.toString());
        Value tout = ctx.eval("js", "__chToggleGrid(JSON.parse(__ARG))");
        int tBad = 0;
        int tUnstable = 0;
        for (int i = 0; i < tc.size(); i++) {
            Integer[] c = tc.get(i);
            int jMode = -1, jSwitch = 0;
            boolean stable = false;
            for (int attempt = 0; attempt < 500 && !stable; attempt++) {
                long t0 = System.currentTimeMillis();
                FakePdc pdc = new FakePdc();
                if (c[0] != null) {
                    pdc.m.put("gs:ch_mode", c[0]);
                }
                if (c[1] != null) {
                    pdc.m.put("gs:ch_switch", t0 - c[1]);
                }
                Player p = fakePlayer(pdc);
                ch.gsCherryToggleMode(p);
                if (System.currentTimeMillis() != t0) {
                    continue;
                }
                jMode = pdc.m.containsKey("gs:ch_mode") ? ((Number) pdc.m.get("gs:ch_mode")).intValue() : -1;
                jSwitch = pdc.m.containsKey("gs:ch_switch") ? 1 : 0;
                stable = true;
            }
            if (!stable) {
                tUnstable++;
                continue;
            }
            Value r = tout.getArrayElement(i);
            if (r.getArrayElement(0).asInt() != jMode || r.getArrayElement(1).asInt() != jSwitch) {
                tBad++;
            }
        }
        check("残樱切换开火方式（" + tc.size() + " 组：mode×switchAgo）JS 与 Java 完全一致", tBad == 0);
        check("切换方式时钟稳定化（不稳定 " + tUnstable + " 组）", tUnstable == 0);
    }

    // ---- C4 余念绝响射线 ----

    private static void psychicRay(Context ctx) throws Exception {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {0, 1, 0}, {0, -1, 0}, {0.6, 0.8, 0}, {0.3, -0.4, 0.8660254037844386}
        };
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        boolean first = true;
        for (boolean scoped : new boolean[]{false, true}) {
            for (double[] d : dirs) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"scoped\":").append(scoped)
                        .append(",\"sx\":0.5,\"sy\":64.0,\"sz\":-3.25")
                        .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1]).append(",\"dz\":").append(d[2])
                        .append(",\"cands\":[]}");
                meta.add(new double[]{scoped ? 1 : 0, d[0], d[1], d[2]});
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__ARG = JSON.parse(__ARG); (function(){ var r = []; for (var i = 0; i < __ARG.length; i++) { r.push(__psyRay(__ARG[i])); } return r; })()");

        int bad = 0;
        int total = 0;
        boolean soundOk = true;
        for (int i = 0; i < meta.size(); i++) {
            double[] m = meta.get(i);
            boolean scoped = m[0] == 1;
            double range = scoped ? PsychicSub.GS_PSY_SCOPED_RANGE : PsychicSub.GS_PSY_RANGE;
            Vector dir = new Vector(m[1], m[2], m[3]);
            Value r = out.getArrayElement(i);
            Value pts = r.getMember("pts");
            int expectN = 0;
            for (double d = 0.0; d <= range; d += PsychicSub.GS_PSY_STEP) {
                expectN++;
            }
            total += expectN;
            // pts[0] 是音效 Location
            if (pts.getArraySize() != expectN + 1) {
                bad++;
                continue;
            }
            Value s0 = r.getMember("snd").getArrayElement(0);
            if (!s0.getMember("name").asString().equals("block.stone_button.click_on")
                    || Math.abs(s0.getMember("vol").asDouble() - 0.9) > 0
                    || Math.abs(s0.getMember("pitch").asDouble() - 0.8) > 0) {
                soundOk = false;
            }
            int k = 0;
            for (double d = 0.0; d <= range; d += PsychicSub.GS_PSY_STEP) {
                double[] c = PsychicSub.gsPsychicRayPoint(0.5, 64.0 + PsychicSub.GS_PSY_MUZZLE_Y, -3.25, dir, d);
                Value p = pts.getArrayElement(1 + k);
                if (p.getMember("x").asDouble() != c[0]
                        || p.getMember("y").asDouble() != c[1]
                        || p.getMember("z").asDouble() != c[2]) {
                    bad++;
                    if (bad <= 3) {
                        System.out.println("      差异 scoped=" + scoped + " d=" + d);
                    }
                }
                k++;
            }
        }
        check("余念绝响射线采样点与 Java 算式逐值一致（腰射 25 格 / 开镜 77 格，共 " + total + " 个采样点）", bad == 0);
        check("发射音效 = block.stone_button.click_on 1.0/0.8，且从胸口响", soundOk);

        // ★ 伤害：开镜 77 / 腰射 37（硬编码，不是 GS_PSY_DMG_MIN/MAX）
        double tx = 0.5, ty = 64.0 + PsychicSub.GS_PSY_MUZZLE_Y, tz = -3.25 + 5.0;
        String cand = ent("00000000-0000-0000-0000-0000000000c1", tx, ty, tz, "ZOMBIE", false, false);
        for (boolean scoped : new boolean[]{false, true}) {
            String arg = "{\"scoped\":" + scoped + ",\"sx\":0.5,\"sy\":64.0,\"sz\":-3.25,\"dx\":0,\"dy\":0,\"dz\":1,"
                    + "\"cands\":[" + cand + "]}";
            ctx.getBindings("js").putMember("__ARG", arg);
            Value r = ctx.eval("js", "__psyRay(JSON.parse(__ARG))");
            Value dmg = r.getMember("dmg");
            double want = scoped ? PsychicSub.GS_PSY_DMG_SCOPED : PsychicSub.GS_PSY_DMG_HIP;
            boolean ok = dmg.getArraySize() == 1
                    && Math.abs(dmg.getArrayElement(0).getMember("dmg").asDouble() - want) < 1.0e-12;
            check("余念绝响命中伤害 " + (scoped ? "开镜 = 77.0" : "腰射 = 37.0") + "（硬编码，与 GS_PSY_DMG_MIN/MAX 无关）", ok);
        }
    }

    // ---- C5 开镜准星圆环 ----

    private static void scopeRing(Context ctx) throws Exception {
        double[][] dirs = {
                {0, 0, 1}, {0, 0, -1}, {1, 0, 0}, {-1, 0, 0},
                {0.7071067811865476, 0, 0.7071067811865476},
                {0, 1, 0}, {0, -1, 0},
                {0.6, 0.8, 0}, {0.3, -0.4, 0.8660254037844386},
                {0.5773502691896258, 0.5773502691896258, 0.5773502691896258}
        };
        StringBuilder sb = new StringBuilder("[");
        List<double[]> meta = new ArrayList<>();
        boolean first = true;
        for (double[] d : dirs) {
            for (double sx : new double[]{0.5, -12.0}) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append("{\"sx\":").append(sx).append(",\"sy\":64.0,\"sz\":-3.25")
                        .append(",\"dx\":").append(d[0]).append(",\"dy\":").append(d[1]).append(",\"dz\":").append(d[2]).append('}');
                meta.add(new double[]{sx, d[0], d[1], d[2]});
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__ARG = JSON.parse(__ARG); (function(){ var r = []; for (var i = 0; i < __ARG.length; i++) { r.push(__scopeRing(__ARG[i])); } return r; })()");

        int bad = 0;
        int total = 0;
        double maxDelta = 0;
        for (int i = 0; i < meta.size(); i++) {
            double[] m = meta.get(i);
            double sx = m[0];
            Vector dir = new Vector(m[1], m[2], m[3]);
            Value ptc = out.getArrayElement(i).getMember("ptc");
            if (ptc.getArraySize() != PsychicSub.GS_SCOPE_POINTS) {
                bad++;
                continue;
            }
            Vector[] basis = PsychicSub.gsScopeBasis(dir);
            double ex = sx, ey = 64.0 + 1.62, ez = -3.25;
            double cx = ex + dir.getX() * PsychicSub.GS_SCOPE_DIST;
            double cy = ey + dir.getY() * PsychicSub.GS_SCOPE_DIST;
            double cz = ez + dir.getZ() * PsychicSub.GS_SCOPE_DIST;
            for (int k = 0; k < PsychicSub.GS_SCOPE_POINTS; k++) {
                double[] c = PsychicSub.gsScopeRingPoint(cx, cy, cz, basis[0], basis[1], k);
                Value p = ptc.getArrayElement(k);
                double dx = Math.abs(p.getArrayElement(0).asDouble() - c[0]);
                double dy = Math.abs(p.getArrayElement(1).asDouble() - c[1]);
                double dz = Math.abs(p.getArrayElement(2).asDouble() - c[2]);
                maxDelta = Math.max(maxDelta, Math.max(dx, Math.max(dy, dz)));
                total++;
                if (dx > 1.0e-9 || dy > 1.0e-9 || dz > 1.0e-9) {
                    bad++;
                }
            }
            // ★ 半径必须恒为 0.22（圆周点到圆心距离）
            for (int k = 0; k < PsychicSub.GS_SCOPE_POINTS; k++) {
                Value p = ptc.getArrayElement(k);
                double r = Math.sqrt(Math.pow(p.getArrayElement(0).asDouble() - cx, 2)
                        + Math.pow(p.getArrayElement(1).asDouble() - cy, 2)
                        + Math.pow(p.getArrayElement(2).asDouble() - cz, 2));
                if (Math.abs(r - PsychicSub.GS_SCOPE_RADIUS) > 1.0e-9) {
                    bad++;
                }
            }
        }
        check("开镜准星圆周坐标与 Java 算式一致（" + meta.size() + " 个视线 × " + PsychicSub.GS_SCOPE_POINTS
                + " 个点 = " + total + " 点，最大偏差 " + String.format(java.util.Locale.ROOT, "%.3e", maxDelta) + "）", bad == 0);
        check("圆周点数 = GS_SCOPE_POINTS = 12", PsychicSub.GS_SCOPE_POINTS == 12);
        check("圆环半径 = GS_SCOPE_RADIUS = 0.22 / 距离 = 1.6",
                PsychicSub.GS_SCOPE_RADIUS == 0.22 && PsychicSub.GS_SCOPE_DIST == 1.6);
    }

    // ---- C6 开镜防抖 ----

    private static void scopeToggle(Context ctx) throws Exception {
        long[] times = {0, 100, 300, 700, 799, 800, 801, 1500, 1600, 1601, 2400, 3201};
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < times.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(times[i]);
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__scopeToggleSeq(JSON.parse(__ARG))");

        PsychicSub psy = new PsychicSub(new ListenerCore());
        Map<String, Long> table = new LinkedHashMap<>();
        boolean scoped = false;
        int bad = 0;
        StringBuilder seq = new StringBuilder();
        for (int i = 0; i < times.length; i++) {
            if (PsychicSub.gsScopeToggleGate(table, "u", times[i])) {
                scoped = !scoped;
            }
            int jv = scoped ? 1 : 0;
            int sv = out.getArrayElement(i).asInt();
            seq.append(sv);
            if (jv != sv) {
                bad++;
                System.out.println("      防抖差异 t=" + times[i] + " JS=" + sv + " Java=" + jv);
            }
        }
        check("开镜 0.8 秒防抖时序与 Java 闸门一致（t=" + Arrays.toString(times) + " ⇒ " + seq + "）", bad == 0);
        check("防抖窗口 = GS_SCOPE_DEBOUNCE_MS = 800", PsychicSub.GS_SCOPE_DEBOUNCE_MS == 800);
        check("防抖判定：799ms 吞掉 / 800ms 放行",
                PsychicSub.gsScopeDebounced(0L, 799) && !PsychicSub.gsScopeDebounced(0L, 800));
        check("防抖判定：没记录过 ⇒ 放行", !PsychicSub.gsScopeDebounced(null, 0));
    }

    // ---- C7 开镜「专注」续期节奏 ----

    private static void scopeFocus(Context ctx) throws Exception {
        // c = [slowNull, slowDur, slowAmp, nvNull, nvDur]
        String[][] cases = {
                {"true", "0", "0", "true", "0"},
                {"false", "600", "9", "false", "600"},
                {"false", "541", "9", "false", "541"},
                {"false", "540", "9", "false", "540"},
                {"false", "539", "9", "false", "539"},
                {"false", "100", "9", "true", "0"},
                {"false", "600", "8", "false", "600"},
                {"false", "600", "10", "false", "600"},
                {"true", "0", "0", "false", "600"},
                {"true", "0", "0", "false", "100"},
                {"false", "100", "9", "false", "100"}
        };
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String[] c = cases[i];
            sb.append('[').append(c[0]).append(',').append(c[1]).append(',').append(c[2]).append(',')
                    .append(c[3]).append(',').append(c[4]).append(']');
        }
        sb.append(']');

        for (boolean resetOwned : new boolean[]{false, true}) {
            ctx.getBindings("js").putMember("__ARG", sb.toString());
            ctx.getBindings("js").putMember("__RST", resetOwned);
            Value out = ctx.eval("js", "__scopeFocusGrid(JSON.parse(__ARG), __RST)");

            boolean owned = false;
            int bad = 0;
            for (int i = 0; i < cases.length; i++) {
                String[] c = cases[i];
                if (resetOwned) {
                    owned = false;
                }
                boolean slowNull = Boolean.parseBoolean(c[0]);
                int slowDur = Integer.parseInt(c[1]);
                int slowAmp = Integer.parseInt(c[2]);
                boolean nvNull = Boolean.parseBoolean(c[3]);
                int nvDur = Integer.parseInt(c[4]);

                // ★ gsScopeNeedSlow 的第一个参数是 curNull（"身上没有"），不是"身上有"
                int adds = 0;
                if (PsychicSub.gsScopeNeedSlow(slowNull, slowAmp, slowDur)) {
                    adds++;
                }
                if (!(!nvNull && !owned)) {
                    owned = true;
                    if (PsychicSub.gsScopeNeedNight(nvNull, nvDur)) {
                        adds++;
                    }
                }
                int sv = out.getArrayElement(i).asInt();
                if (sv != adds) {
                    bad++;
                    System.out.println("      专注差异 case=" + Arrays.toString(c) + " resetOwned=" + resetOwned
                            + " JS=" + sv + " Java=" + adds);
                }
            }
            check("开镜「专注」续期节奏（resetOwned=" + resetOwned + "，" + cases.length
                    + " 组：缓慢/夜视各自判断，30 秒一次、低于 27 秒续期）", bad == 0);
        }

        // 摘镜：只收走本机制给的那一份
        String[][] uc = {
                {"true", "9", "true"},
                {"true", "0", "false"},
                {"false", "9", "false"},
                {"false", "3", "false"},
                {"false", "0", "true"},
                {"false", "-1", "false"}
        };
        StringBuilder ub = new StringBuilder("[");
        for (int i = 0; i < uc.length; i++) {
            if (i > 0) {
                ub.append(',');
            }
            ub.append('[').append(uc[i][0]).append(',').append(uc[i][1]).append(',').append(uc[i][2]).append(']');
        }
        ub.append(']');
        ctx.getBindings("js").putMember("__ARG", ub.toString());
        Value uout = ctx.eval("js", "__scopeUnfocusGrid(JSON.parse(__ARG))");

        PsychicSub psy = new PsychicSub(new ListenerCore());
        int ubad = 0;
        for (int i = 0; i < uc.length; i++) {
            boolean flagged = Boolean.parseBoolean(uc[i][0]);
            int amp = Integer.parseInt(uc[i][1]);
            boolean nvFlagged = Boolean.parseBoolean(uc[i][2]);
            StringBuilder want = new StringBuilder();
            if (nvFlagged) {
                want.append("E:NIGHT_VISION");
            }
            if (PsychicSub.gsScopeShouldRemoveSlow(flagged, amp)) {
                if (want.length() > 0) {
                    want.append(',');
                }
                want.append("E:SLOWNESS");
            }
            String sv = uout.getArrayElement(i).asString();
            if (!sv.equals(want.toString())) {
                ubad++;
                System.out.println("      摘镜差异 case=" + Arrays.toString(uc[i]) + " JS=[" + sv + "] Java=[" + want + "]");
            }
        }
        check("摘镜只收走本机制给的那一份（缓慢靠标记+amp9 兜底 / 夜视只认自己的标记）", ubad == 0);
        // ★ 反向：非本机制给的夜视（nvFlagged=false）⇒ 摘镜时一根汗毛都不许动
        boolean nvUntouched = true;
        for (int i = 0; i < uc.length; i++) {
            if (!Boolean.parseBoolean(uc[i][2]) && uout.getArrayElement(i).asString().contains("NIGHT_VISION")) {
                nvUntouched = false;
            }
        }
        check("★ 玩家自己给的夜视（非本机制）摘镜时绝不碰", nvUntouched);
    }

    // ---- C8 余念绝响开火状态机 ----

    private static void psyFire(Context ctx) throws Exception {
        Integer[] ammos = {null, 0, 1, 2, 36, 37, 38};
        Integer[] lastAgos = {null, 0, 15, 25, 30, 31, 100, 1000, 999999};
        Integer[] reloadAgos = {null, 0, 100, 1000, 1490, 1500, 1501, 3000};

        StringBuilder sb = new StringBuilder("[");
        List<Integer[]> cases = new ArrayList<>();
        boolean first = true;
        for (Integer a : ammos) {
            for (Integer l : lastAgos) {
                for (Integer r : reloadAgos) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    sb.append('[').append(a == null ? "null" : a).append(',')
                            .append(l == null ? "null" : l).append(',')
                            .append(r == null ? "null" : r).append(']');
                    cases.add(new Integer[]{a, l, r});
                }
            }
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__psyFireGrid(JSON.parse(__ARG))");

        PsychicSub psy = new PsychicSub(new ListenerCore());
        int mismatch = 0;
        int fired = 0;
        int shotsConsistent = 0;
        int unstable = 0;
        for (int i = 0; i < cases.size(); i++) {
            Integer[] c = cases.get(i);

            // ★ 时钟稳定化：gsPsychicNow() 读的是真实挂钟，而"打点 → 判定"之间
            //   哪怕跨 1 毫秒都会把 29/30 这种边界组算歪。
            //   ⇒ 用**同一个** t0 既写时间戳又做判定，并要求整段落在同一毫秒内。
            int jAmmo = -1, jLast = 0, jReload = 0;
            boolean stable = false;
            for (int attempt = 0; attempt < 500 && !stable; attempt++) {
                long t0 = System.currentTimeMillis();
                FakePdc pdc = new FakePdc();
                if (c[0] != null) {
                    pdc.m.put("gs:psy_ammo", c[0]);
                }
                if (c[1] != null) {
                    pdc.m.put("gs:psy_lastshot", t0 - c[1]);
                }
                if (c[2] != null) {
                    pdc.m.put("gs:psy_reload", t0 - c[2]);
                }
                Player p = fakePlayer(pdc);
                psy.gsPsychicFire(p);
                if (System.currentTimeMillis() != t0) {
                    continue;
                }
                jAmmo = pdc.m.containsKey("gs:psy_ammo") ? ((Number) pdc.m.get("gs:psy_ammo")).intValue() : -1;
                jLast = pdc.m.containsKey("gs:psy_lastshot") ? 1 : 0;
                jReload = pdc.m.containsKey("gs:psy_reload") ? 1 : 0;
                stable = true;
            }
            if (!stable) {
                unstable++;
                continue;
            }

            Value r = out.getArrayElement(i);
            int sAmmo = r.getArrayElement(0).asInt();
            int sLast = r.getArrayElement(1).asInt();
            int sReload = r.getArrayElement(2).asInt();
            int sShots = r.getArrayElement(3).asInt();

            if (sAmmo != jAmmo || sLast != jLast || sReload != jReload) {
                mismatch++;
                if (mismatch <= 5) {
                    System.out.println("      差异 case=" + Arrays.toString(c)
                            + " JS(ammo,last,reload)=(" + sAmmo + "," + sLast + "," + sReload + ")"
                            + " Java=(" + jAmmo + "," + jLast + "," + jReload + ")");
                }
            }
            if (sShots > 0) {
                fired++;
                // 开火 ⇒ 弹药恰好 -1
                int before = c[0] == null ? PsychicSub.GS_PSY_AMMO : c[0];
                if (sAmmo == before - 1 && sLast == 1) {
                    shotsConsistent++;
                }
            } else {
                // 没开火 ⇒ 弹药不变。★ 原脚本的"默认 37"是**读**出来的、不写回 PDC，
                //   所以预设为 null 时键仍然不存在（sAmmo = -1）也算"没变"。
                int before = c[0] == null ? PsychicSub.GS_PSY_AMMO : c[0];
                int eff = sAmmo == -1 ? PsychicSub.GS_PSY_AMMO : sAmmo;
                if (eff == before) {
                    shotsConsistent++;
                }
            }
        }
        check("余念绝响开火状态机（" + cases.size() + " 组预状态：ammo×lastShotAgo×reloadAgo）JS 与 Java 完全一致",
                mismatch == 0);
        check("时钟稳定化：没有卡在同一毫秒外的组（不稳定 " + unstable + " 组）", unstable == 0);
        check("开火过的组数 = " + fired + "（> 0）", fired > 0);
        check("开火 ⇒ 弹药恰好 -1 且 lastShot 打点（" + shotsConsistent + "/" + cases.size() + "）",
                shotsConsistent == cases.size());
    }

    // ---- C9 物品显示名 ----

    private static void names(Context ctx) throws Exception {
        ctx.eval("js", "__setGun(null)");
        String[][] cases = {
                {"psy", "37", "0", null},
                {"psy", "0", "0", null},
                {"psy", "5", "0", "0"},
                {"psy", "5", "0", "100"},
                {"psy", "5", "0", "1499"},
                {"psy", "5", "0", "1500"},
                {"psy", "12", "0", "0"},
                {"ch", "12", "0", null},
                {"ch", "12", "1", null},
                {"ch", "0", "1", null},
                {"ch", "3", "1", "0"},
                {"ch", "3", "0", "0"},
                {"ch", "3", "1", "1799"},
                {"ch", "3", "1", "1800"},
                {"ch", "0", "0", null}
        };
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            String[] c = cases[i];
            sb.append("[\"").append(c[0]).append("\",")
                    .append(c[1] == null ? "null" : c[1]).append(',')
                    .append(c[2] == null ? "null" : c[2]).append(',')
                    .append(c[3] == null ? "null" : c[3]).append(']');
        }
        sb.append(']');
        ctx.getBindings("js").putMember("__ARG", sb.toString());
        Value out = ctx.eval("js", "__names(JSON.parse(__ARG))");

        int bad = 0;
        for (int i = 0; i < cases.length; i++) {
            String[] c = cases[i];
            int ammo = Integer.parseInt(c[1]);
            boolean reloading = c[3] != null && Integer.parseInt(c[3]) < (c[0].equals("psy") ? 1500 : 1800);
            String want;
            if (c[0].equals("psy")) {
                want = PsychicSub.gsPsychicName(ammo, reloading);
            } else {
                want = CherrySub.gsCherryName(ammo, "1".equals(c[2]), reloading);
            }
            String sv = out.getArrayElement(i).asString();
            if (!sv.equals(want)) {
                bad++;
                System.out.println("      名字差异 case=" + Arrays.toString(c) + " JS=[" + sv + "] Java=[" + want + "]");
            }
        }
        check("物品显示名与 Java 算式一致（" + cases.length + " 组：弹药 / 换弹中 ᴿ / 三连发 ≡）", bad == 0);
        check("余念绝响名 = §d余念绝响<37>", PsychicSub.gsPsychicName(37, false).equals("\u00a7d余念绝响<37>"));
        check("残樱三连发名 = §d残樱<12≡>", CherrySub.gsCherryName(12, true, false).equals("\u00a7d残樱<12\u2261>"));
        check("残樱换弹中名 = §d残樱<12ᴿ>", CherrySub.gsCherryName(12, false, true).equals("\u00a7d残樱<12\u1d3f>"));
    }

    // ======================================================================
    // [D] 反向断言
    // ======================================================================

    private static void reverse(Context ctx, String region, String cherryRegion) throws Exception {
        System.out.println("[D] 反向断言：钉住「不该存在的东西」");

        // ★ 没有「gs_xxx 已加载」这一行（移植契约新增那一节）
        Value log = ctx.eval("js", "__logLines()");
        boolean hasLoaded = false;
        for (long i = 0; i < log.getArraySize(); i++) {
            String s = log.getArrayElement(i).asString();
            if (s.contains("已加载") && (s.contains("余念绝响") || s.contains("残樱"))) {
                hasLoaded = true;
            }
        }
        check("★ 真 JS 没打「余念绝响/残樱 已加载」这种行", !hasLoaded);
        check("★ 脚本本子系统区间里没有『已加载』字样", !region.contains("已加载"));
        check("★ Java 的 PsychicSub/CherrySub 源码里没有『已加载』字样",
                !readText(PSY_SRC).contains("已加载") && !readText(CH_SRC).contains("已加载"));
        int mine = 0;
        for (long i = 0; i < log.getArraySize(); i++) {
            String s = log.getArrayElement(i).asString();
            if (s.contains("余念绝响") || s.contains("残樱")) {
                mine++;
            }
        }
        check("★ 这两个子系统一共只打 4 行日志（2 注册 + 2 启用），实际 " + mine, mine == 4);

        // 已删除的键 / 函数（原脚本注释点名删掉的）
        //   ★ 这些名字在**注释**里被反复点名（"XX 已删除"），所以只查**代码**部分。
        String regionCode = codeOnly(region);
        String cherryCode = codeOnly(cherryRegion);
        String[] gone = {"gs:ch_burst", "gs:ch_burstat", "GS_CH_BURST_GAP", "GS_CH_SPREAD_D0",
                "GS_CH_SPREAD_K", "gsCherrySpreadAt", "gsCherryBurstLeft", "gsCherrySetBurstLeft",
                "gsCherryBurstAgo", "gsCherryMarkBurst", "GS_PSY_Q_AT", "GS_PSY_Q_GAP",
                "gsPsychicMarkQ", "gsPsychicJustQ", "GS_CH_Q_AT", "GS_CH_Q_GAP",
                "gsCherryMarkQ", "gsCherryJustQ", "gsScopeMakeButton"};
        int goneOk = 0;
        for (String g : gone) {
            if (!regionCode.contains(g)) {
                goneOk++;
            } else {
                System.out.println("      · 代码里仍有 " + g);
            }
        }
        check("★ 原脚本区间里「已删除的键 / 函数」一个都没留下（" + goneOk + "/" + gone.length + "）",
                goneOk == gone.length);
        for (String g : new String[]{"GS_CH_BURST_GAP", "GS_CH_SPREAD_D0", "GS_CH_SPREAD_K",
                "gsCherrySpreadAt", "gsCherryBurstLeft", "gsCherrySetBurstLeft", "gsCherryBurstAgo",
                "gsCherryMarkBurst", "gsPsychicMarkQ", "gsPsychicJustQ", "gsCherryMarkQ", "gsCherryJustQ",
                "gsScopeMakeButton"}) {
            boolean found = false;
            for (java.lang.reflect.Method m : CherrySub.class.getDeclaredMethods()) {
                if (m.getName().equals(g)) {
                    found = true;
                }
            }
            for (java.lang.reflect.Method m : PsychicSub.class.getDeclaredMethods()) {
                if (m.getName().equals(g)) {
                    found = true;
                }
            }
            check("Java 里也没有方法「" + g + "」", !found);
        }
        for (String g : new String[]{"GS_CH_BURST_GAP", "GS_CH_SPREAD_D0", "GS_CH_SPREAD_K",
                "GS_PSY_Q_AT", "GS_PSY_Q_GAP", "GS_CH_Q_AT", "GS_CH_Q_GAP"}) {
            boolean found = false;
            for (java.lang.reflect.Field f : CherrySub.class.getDeclaredFields()) {
                if (f.getName().equals(g)) {
                    found = true;
                }
            }
            for (java.lang.reflect.Field f : PsychicSub.class.getDeclaredFields()) {
                if (f.getName().equals(g)) {
                    found = true;
                }
            }
            check("Java 里也没有字段「" + g + "」", !found);
        }

        // 残樱没有开镜 / 没有药效 / 没有副手
        //   ★ 2225 行的**注释**里提了一句 gsScopeSetScoped（解释 STRING/BYTE 那个坑），
        //     所以只查代码：残樱的代码里不许出现任何 gsScope* 调用。
        check("★ 残樱的代码里完全不出现 gsScope* / GS_SCOPE_",
                !cherryCode.contains("gsScope") && !cherryCode.contains("GS_SCOPE_"));
        check("★ 残樱的代码里没有 addPotionEffect（不给任何药效）", !cherryCode.contains("addPotionEffect"));
        check("★ 残樱的代码里没有准星圆环（spawnParticle 只出现 1 次 = 画樱花）",
                countOf(cherryCode, "spawnParticle") == 1);

        // 没有 Q 拦截 / 没有副手锁
        check("★ 两个子系统都不注册 PlayerDropItemEvent（Q = 原版丢弃）",
                !regionCode.contains("PlayerDropItemEvent"));
        check("★ 两个子系统都不注册 InventoryClickEvent / InventoryDragEvent（副手按钮已删）",
                !regionCode.contains("InventoryClickEvent") && !regionCode.contains("InventoryDragEvent"));

        // 只认 ARM_SWING
        check("★ 挥手监听只认 ARM_SWING（脚本里 'type !== \\'ARM_SWING\\'' 出现 2 次）",
                countOf(regionCode, "type !== 'ARM_SWING'") == 2);
        check("★ Java 也只认 ARM_SWING（两处 !type.equals(\"ARM_SWING\")）",
                readText(PSY_SRC).contains("!type.equals(\"ARM_SWING\")")
                        && readText(CH_SRC).contains("!type.equals(\"ARM_SWING\")"));
        check("★ Java 里没有任何「接受 OFF_ARM_SWING」的判定",
                !readText(PSY_SRC).contains("equals(\"OFF_ARM_SWING\")")
                        && !readText(CH_SRC).contains("equals(\"OFF_ARM_SWING\")"));

        // 余念绝响不再给手持药效
        int potionNames = 0;
        for (String s : new String[]{"RESISTANCE", "REGENERATION", "SATURATION", "SPEED"}) {
            if (!regionCode.contains(s)) {
                potionNames++;
            }
        }
        check("★ 余念绝响区间里没有任何手持药效名（抗性/生命恢复/饱和/速度 已整段删除）",
                potionNames == 4);

        // ignoreCancelled 不许写成 true
        check("★ Java 两个文件都没有 ignoreCancelled = true",
                !readText(PSY_SRC).contains("ignoreCancelled = true")
                        && !readText(CH_SRC).contains("ignoreCancelled = true"));

        // 监听器：恰好 4 个 @EventHandler，全部 HIGHEST、ignoreCancelled 默认 false
        for (Class<?> sub : new Class<?>[]{PsychicSub.class, CherrySub.class}) {
            int count = 0;
            boolean allHighest = true;
            boolean allNotIgnoreCancelled = true;
            for (Class<?> inner : sub.getDeclaredClasses()) {
                if (!org.bukkit.event.Listener.class.isAssignableFrom(inner)) {
                    continue;
                }
                for (Method m : inner.getDeclaredMethods()) {
                    EventHandler h = m.getAnnotation(EventHandler.class);
                    if (h == null) {
                        continue;
                    }
                    count++;
                    if (h.priority() != EventPriority.HIGHEST) {
                        allHighest = false;
                    }
                    if (h.ignoreCancelled()) {
                        allNotIgnoreCancelled = false;
                    }
                }
            }
            check("★ " + sub.getSimpleName() + " 恰好注册 4 个监听器（原脚本 4 处 registerEvent）", count == 4);
            check("★ " + sub.getSimpleName() + " 4 个监听器全部 EventPriority.HIGHEST", allHighest);
            check("★ " + sub.getSimpleName() + " 4 个监听器 ignoreCancelled 都是默认 false", allNotIgnoreCancelled);
        }

        // 幂等注册：闩锁只注册一次
        java.lang.reflect.Field psyLatch = PsychicSub.class.getDeclaredField("GS_PSY_REGISTERED");
        java.lang.reflect.Field chLatch = CherrySub.class.getDeclaredField("GS_CH_REGISTERED");
        check("★ GS_PSY_REGISTERED / GS_CH_REGISTERED 是**实例**字段（幂等闩锁，不是静态共享）",
                !Modifier.isStatic(psyLatch.getModifiers()) && !Modifier.isStatic(chLatch.getModifiers()));
        check("★ 类注释里写明了「原脚本 reload 会累积监听器，Java 版改为幂等注册」",
                readText(PSY_SRC).contains("监听器注册：原脚本会累积，Java 版改为幂等")
                        && readText(CH_SRC).contains("监听器注册：原脚本会累积，Java 版改为幂等")
                        && readText(PSY_SRC).contains("幂等注册（原脚本每次 reload 都累积")
                        && readText(CH_SRC).contains("幂等注册（原脚本每次 reload 都累积"));
        check("★ 注册入口有幂等早退（if (GS_PSY_REGISTERED) return; / if (GS_CH_REGISTERED) return;）",
                readText(PSY_SRC).contains("if (GS_PSY_REGISTERED) {")
                        && readText(CH_SRC).contains("if (GS_CH_REGISTERED) {"));

        // GS_PSY_REGISTERED / GS_CH_REGISTERED 在原脚本里只写不读
        check("原脚本里 GS_PSY_REGISTERED 只出现在定义与置 true 两处",
                countOf(regionCode, "GS_PSY_REGISTERED") == 2);
        check("原脚本里 GS_CH_REGISTERED 只出现在定义与置 true 两处",
                countOf(regionCode, "GS_CH_REGISTERED") == 2);

        // 伤害：真正的 37/77 硬编码在 shoot 里
        check("★ 脚本里 gsPsychicShoot 用硬编码 37.0/77.0（不是 GS_PSY_DMG_MIN/MAX）",
                regionCode.contains("dmg = scoped ? 77.0 : 37.0"));
        check("★ Java 的 gsPsychicShoot 同样用 GS_PSY_DMG_SCOPED/GS_PSY_DMG_HIP",
                readText(PSY_SRC).contains("scoped ? GS_PSY_DMG_SCOPED : GS_PSY_DMG_HIP"));

        // 三连发不是逐发延迟
        check("★ 脚本里三连发是 for 循环一次性出膛（没有 0.1 秒发间隔）",
                regionCode.contains("gsCherryShootRay(p, GS_CH_SPREAD_SIDES[k], GS_CH_BURST_RANGE);"));
        check("★ Java 里三连发也是同一个 for 循环",
                readText(CH_SRC).contains("gsCherryShootRay(p, GS_CH_SPREAD_SIDES[k], GS_CH_BURST_RANGE);"));

        // ★ 命中筛选里那条**历史分支** DROPPED_ITEM：1.21.11 已把它改名成 ITEM，
        //   枚举值取不到（运行时无法构造），只能静态断言两边都照抄了这三个名字。
        check("★ 命中筛选保留了历史分支 ARMOR_STAND / DROPPED_ITEM / ITEM（脚本 2 处 + Java 2 处）",
                countOf(regionCode, "'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM'") == 2
                        && readText(PSY_SRC).contains("ARMOR_STAND\") || tn.equals(\"DROPPED_ITEM\") || tn.equals(\"ITEM\"")
                        && readText(CH_SRC).contains("ARMOR_STAND\") || tn.equals(\"DROPPED_ITEM\") || tn.equals(\"ITEM\""));

        System.out.println();
    }

    /**
     * 只保留代码：去掉整行注释与行尾注释。
     *
     * <p>★ 为什么需要：原脚本的注释里**反复点名**那些「已删除」的键与函数
     * （"GS_CH_BURST_GAP 已删除"），直接 contains 会假阳性。
     */
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
        if (a instanceof String[] sa && b instanceof String[] sb) {
            return Arrays.equals(sa, sb);
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
            if (a instanceof Long && b instanceof Long) {
                return ((Long) a).longValue() == ((Long) b).longValue();
            }
            return true;
        }
        return a.equals(b);
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

    /** 假 PDC：一个 {@code Map<String,Object>}。 */
    private static final class FakePdc implements InvocationHandler {
        final Map<String, Object> m = new LinkedHashMap<>();

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
                    ListenerBSubParityProbe.class.getClassLoader(),
                    new Class<?>[]{PersistentDataContainer.class}, h);
        }
    }

    private static Player fakePlayer(FakePdc pdc) {
        PersistentDataContainer container = FakePdc.make(pdc);
        World world = (World) Proxy.newProxyInstance(ListenerBSubParityProbe.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> def(method.getReturnType()));
        PlayerInventory inv = (PlayerInventory) Proxy.newProxyInstance(ListenerBSubParityProbe.class.getClassLoader(),
                new Class<?>[]{PlayerInventory.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getItemInMainHand":
                            return null;
                        case "getContents":
                            return new org.bukkit.inventory.ItemStack[0];
                        default:
                            return def(method.getReturnType());
                    }
                });
        return (Player) Proxy.newProxyInstance(ListenerBSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(SELF_ID);
                        case "getPersistentDataContainer":
                            return container;
                        case "getWorld":
                            return world;
                        case "getInventory":
                            return inv;
                        case "isOnline":
                            return true;
                        default:
                            return def(method.getReturnType());
                    }
                });
    }
}
