package com.qinghuacisj.gs.script.module.listener;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A 子系统（梦境套光环 + 梦境套套装效果）的一致性探针。
 *
 * <p>用<b>真 GraalJS</b> 跑 {@code gs_listener.js}（14,336 行）并把结果与 Java 侧
 * （{@link AuraSub} / {@link SetBonusSub} / {@link ListenerCore}）逐项比对。
 *
 * <h2>为什么能跑起来</h2>
 * 脚本顶层有副作用（注册监听器、跑 {@code runRepeating}、启动 20+ 个子系统），
 * 所以 eval 之前先用 {@link #PRELUDE} 把 RSC 的桥接函数
 * （{@code getServer} / {@code getSfItemById} / {@code getAddonConfig} /
 * {@code runLater} / {@code runRepeating}）换成空实现：
 * <ul>
 *   <li>{@code getServer().getWorlds()} 返回<b>空表</b> ⇒ {@code getWorlds().get(0)} 抛
 *       {@code IndexOutOfBounds}，被原脚本自己的 {@code try/catch} 吞掉
 *       （这正是「无世界」的等价情形）；</li>
 *   <li>{@code getServer().getOnlinePlayers()} 返回<b>空表</b> ⇒ 脉冲不会碰到任何玩家；</li>
 *   <li>{@code pm.getPlugin(...)} 恒返回 null ⇒ 所有 {@code registerEvent} 分支提前 return，
 *       一个监听器都不会注册（探针无副作用）；</li>
 *   <li>{@code runLater} / {@code runRepeating} 是空实现 ⇒ 没有任务真的跑起来。</li>
 * </ul>
 *
 * <h2>四类断言</h2>
 * <ol>
 *   <li><b>常量对账</b>：JS 顶层 var ↔ Java 字段/实例状态，按名按值。</li>
 *   <li><b>加载日志行逐字节</b>：真 JS 跑出来的行 == {@code docs/参考服日志_构思技艺.txt}
 *       里的行 == Java 的 {@code static String xxxLine()}。</li>
 *   <li><b>数值 / 几何大量输入比对</b>：粒子圆周角度分布、药效续期阈值与等级、
 *       收走判定、夜视三分支、世代号逻辑。</li>
 *   <li><b>反向断言</b>：钉住「不该存在的东西」（运行期条件日志不该出现在启动日志里、
 *       本子系统不该注册监听器、不该有提示词、不该取消事件、不该多出常量）。</li>
 * </ol>
 *
 * <p>★ 药效垫片：{@code org.bukkit.potion.PotionEffectType} 在无服务器环境下
 * 静态初始化会抛 {@code ExceptionInInitializerError}（Paper 的注册表没起来），
 * 所以 Run B 用 {@link #POTION_SHIM} 把 {@code Java.type('...PotionEffectType')} /
 * {@code Java.type('...PotionEffect')} 换成<b>JS 假实现</b>，
 * 好让 {@code gsSetEffects} 的<b>真实逻辑</b>（阈值 / 等级 / 顺序 / 登记）跑起来可观测。
 * 垫片只替换这两个类，其余 {@code Java.type} 照走真身。
 *
 * <p>最后打印一行 {@code 通过 N ｜ 失败 M}；失败时 {@code System.exit(1)}。
 */
public final class ListenerASubParityProbe {

    // ======================================================================
    // 路径
    // ======================================================================

    static final Path SCRIPT = Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js");
    static final Path REFLOG = Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt");
    static final Path CORE_SRC = Path.of(
            "D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/ListenerCore.java");

    /** 本子系统在原脚本里的行区间（1-based，闭区间）。 */
    static final int SUB_FROM = 677;
    static final int SUB_TO = 1117;

    // ======================================================================
    // 捕获
    // ======================================================================

    static final List<String> LOG_A = new ArrayList<>();   // Run A（无垫片）抓到的日志行
    static final List<String> LOG_B = new ArrayList<>();   // Run B（药效垫片）抓到的日志行
    static final List<String> OUT_B = new ArrayList<>();   // Run B 的药效观测

    static int pass = 0;
    static int fail = 0;

    /** Run A 的上下文（个别零散算式要回真 JS 里求值）。 */
    static Context RUN_A;

    // ======================================================================
    // 桥接桩（RSC 注入的全局函数）
    // ======================================================================

    static final String PRELUDE =
            "var __empty = new (Java.type('java.util.ArrayList'))();\n"
                    + "var __log = { info: function(s){ try { __sink.add('' + s); } catch(e){} },\n"
                    + "              warning: function(s){}, severe: function(s){}, log: function(a,b){} };\n"
                    + "var __pm = { registerEvent: function(){}, registerEvents: function(){},\n"
                    + "             getPlugin: function(n){ return null; } };\n"
                    + "var __srv = {\n"
                    + "  getWorlds: function(){ return __empty; },\n"
                    + "  getWorld: function(n){ return null; },\n"
                    + "  getOnlinePlayers: function(){ return __empty; },\n"
                    + "  getLogger: function(){ return __log; },\n"
                    + "  getPluginManager: function(){ return __pm; },\n"
                    + "  createBossBar: function(){ return null; },\n"
                    + "  getScheduler: function(){ return null; }\n"
                    + "};\n"
                    + "var getServer = function(){ return __srv; };\n"
                    + "var getSfItemById = function(id){ return null; };\n"
                    + "var getAddonConfig = function(){ return null; };\n"
                    + "var runLater = function(fn, d){ return null; };\n"
                    + "var runRepeating = function(fn, d, p){ return null; };\n";

    // ======================================================================
    // Run A 的辅助（算式逐字抄自原脚本 809~836 / 978~983 / 1005 / 945~948 / 782 行）
    // ======================================================================

    static final String HELPERS_A =
            "function __pPoints(total, n, radius, hh, hl) {\n"
                    + "  var out = [];\n"
                    + "  var stepA = (Math.PI * 2.0) / total;\n"
                    + "  for (var i = 0; i < total; i++) {\n"
                    + "    var idx = i % n;\n"
                    + "    var a = i * stepA;\n"
                    + "    out.push(Math.cos(a) * radius);\n"
                    + "    out.push((idx === 0 ? hh : hl));\n"
                    + "    out.push(Math.sin(a) * radius);\n"
                    + "  }\n"
                    + "  return out;\n"
                    + "}\n"
                    + "function __pEffNeed(has, curAmp, curDur, wantAmp) {\n"
                    + "  var need = true;\n"
                    + "  if (has && curAmp === wantAmp && curDur > GS_SET_EFF_REF) need = false;\n"
                    + "  return need;\n"
                    + "}\n"
                    + "function __pNightNeed(has, left) { return !has || left <= GS_SET_EFF_REF; }\n"
                    + "function __pNightAction(has, owned, left) {\n"
                    + "  if (has && !owned) return 0;\n"
                    + "  return (__pNightNeed(has, left) ? 2 : 1);\n"
                    + "}\n"
                    + "function __pTakeAway(has, curAmp, recAmp) { return has && curAmp === recAmp; }\n"
                    + "function __pIsCurrent(my, now) { return (my === -1) || (now === my); }\n"
                    // ===== 粒子脉冲端到端：把 gsAuraPulse 真的跑一遍，抓下每一次 spawnParticle =====
                    // 这是本子系统**最强**的一条对账：它同时钉住
                    //   ① gsAuraParticle 的取名（含老版本换名兜底、取不到就跳过）
                    //   ② idx = i % n 的交叉排列  ③ 角度均分与半径/高低差
                    //   ④ spawnParticle 的 9 个实参（1, 0.02, 0.02, 0.02, 0.0）
                    // 注入点：gsAuraConfig / gsDreamFullSet / getServer 都是顶层 function/var 绑定，
                    // 可以在 eval 出来的作用域里直接换掉（真 JS 里它们就是可重绑定的 var）。
                    + "var __spawns = [];\n"
                    + "var __fakeWorld = { spawnParticle: function(){\n"
                    + "  __spawns.push('' + arguments[0]);\n"
                    + "  for (var k = 1; k < arguments.length; k++) __spawns.push(arguments[k]);\n"
                    + "} };\n"
                    + "var __fakePl = { getLocation: function(){ return {\n"
                    + "    getX: function(){ return 100.5; }, getY: function(){ return 64.0; },\n"
                    + "    getZ: function(){ return -20.25; } }; },\n"
                    + "  getWorld: function(){ return __fakeWorld; } };\n"
                    // ★ 必须返回**Java 集合**：原脚本紧接着调 players.iterator()，
                    //   JS 数组没有 iterator() ⇒ 会抛异常并被外层 try 吞掉（一次都不刷）。
                    + "var __fakePlayers = new (Java.type('java.util.ArrayList'))();\n"
                    + "__fakePlayers.add(__fakePl);\n"
                    + "function __runAura(enabled, particles, eachCount, radius, hh, hl) {\n"
                    + "  var sCfg = gsAuraConfig, sFull = gsDreamFullSet, sSrv = getServer;\n"
                    + "  __spawns = [];\n"
                    + "  try {\n"
                    + "    gsAuraConfig = function(){ return { enabled: enabled, particles: particles,\n"
                    + "      eachCount: eachCount, radius: radius, heightHigh: hh, heightLow: hl }; };\n"
                    + "    gsDreamFullSet = function(p){ return true; };\n"
                    + "    getServer = function(){ return { getOnlinePlayers: function(){ return __fakePlayers; } }; };\n"
                    + "    gsAuraPulse();\n"
                    + "  } finally { gsAuraConfig = sCfg; gsDreamFullSet = sFull; getServer = sSrv; }\n"
                    + "  return __spawns;\n"
                    + "}\n"
                    // 世代号端到端：假世界 + 假 PDC（GS_PDT.INTEGER 用标记对象顶替，
                    // 因为 Paper 的 PersistentDataType 在无服务器环境下也起不来）
                    + "function __pGenScenario() {\n"
                    + "  var store = {};\n"
                    + "  var pdc = { get: function(k, t){ var s = '' + k; return (s in store) ? store[s] : null; },\n"
                    + "              set: function(k, t, v){ store['' + k] = v; } };\n"
                    + "  var world = { getPersistentDataContainer: function(){ return pdc; } };\n"
                    + "  var srv2 = { getWorlds: function(){ return { get: function(i){ return i === 0 ? world : null; } }; } };\n"
                    + "  var savedServer = getServer;\n"
                    + "  var savedPdt = GS_PDT;\n"
                    + "  var savedGen = GS_AURA_MY_GEN;\n"
                    + "  var out = [];\n"
                    + "  try {\n"
                    + "    getServer = function(){ return srv2; };\n"
                    + "    GS_PDT = { INTEGER: { m: 'INTEGER' } };\n"
                    + "    GS_AURA_MY_GEN = -1;\n"
                    + "    gsAuraGenBump();\n"
                    + "    out.push(GS_AURA_MY_GEN + ':' + gsAuraGenNow() + ':' + gsAuraIsCurrent());\n"
                    + "    gsAuraGenBump();\n"
                    + "    out.push(GS_AURA_MY_GEN + ':' + gsAuraGenNow() + ':' + gsAuraIsCurrent());\n"
                    + "    store['gs:aura_gen'] = 3;\n"          // 模拟 /rsc reload 后新实例抬号
                    + "    out.push(GS_AURA_MY_GEN + ':' + gsAuraGenNow() + ':' + gsAuraIsCurrent());\n"
                    + "    getServer = savedServer;\n"           // 回到「无世界」
                    + "    GS_AURA_MY_GEN = -1;\n"
                    + "    gsAuraGenBump();\n"
                    + "    out.push(GS_AURA_MY_GEN + ':' + gsAuraGenNow() + ':' + gsAuraIsCurrent());\n"
                    + "    GS_AURA_MY_GEN = 7;\n"
                    + "    out.push(GS_AURA_MY_GEN + ':' + gsAuraGenNow() + ':' + gsAuraIsCurrent());\n"
                    + "  } finally {\n"
                    + "    getServer = savedServer; GS_PDT = savedPdt; GS_AURA_MY_GEN = savedGen;\n"
                    + "  }\n"
                    + "  return out.join('|');\n"
                    + "}\n";

    // ======================================================================
    // Run B：药效垫片 + 药效探针
    // ======================================================================

    static final String POTION_SHIM =
            "var __realJava = Java;\n"
                    + "var __fakeTypes = {};\n"
                    + "function __mkType(n) {\n"
                    + "  if (n == null) return null;\n"
                    + "  if (!__fakeTypes[n]) {\n"
                    + "    var o = {};\n"
                    + "    o.getName = function(){ return n; };\n"
                    + "    o.getKey = function(){ return n; };\n"
                    + "    o.toString = function(){ return n; };\n"
                    + "    __fakeTypes[n] = o;\n"
                    + "  }\n"
                    + "  return __fakeTypes[n];\n"
                    + "}\n"
                    + "var __PET = { valueOf: function(n){ return __mkType(n); },\n"
                    + "             getByName: function(n){ return __mkType(n); } };\n"
                    + "['REGENERATION','SPEED','SATURATION','RESISTANCE','DAMAGE_RESISTANCE','HASTE',\n"
                    + " 'FAST_DIGGING','NIGHT_VISION'].forEach(function(n){ __PET[n] = __mkType(n); });\n"
                    + "function __PE(t, dur, amp, amb, part) {\n"
                    + "  this.__t = t; this.__dur = dur; this.__amp = amp; this.__amb = amb; this.__part = part;\n"
                    + "}\n"
                    + "__PE.prototype.getType = function(){ return this.__t; };\n"
                    + "__PE.prototype.getDuration = function(){ return this.__dur; };\n"
                    + "__PE.prototype.getAmplifier = function(){ return this.__amp; };\n"
                    + "__PE.prototype.isAmbient = function(){ return this.__amb; };\n"
                    + "__PE.prototype.hasParticles = function(){ return this.__part; };\n"
                    + "var Java = { type: function(n) {\n"
                    + "  if (n === 'org.bukkit.potion.PotionEffectType') return __PET;\n"
                    + "  if (n === 'org.bukkit.potion.PotionEffect') return __PE;\n"
                    + "  return __realJava.type(n);\n"
                    + "} };\n";

    static final String POTION_PROBE =
            "var __obs = { adds: [], removes: [], held: {} };\n"
                    + "function __tname(t) { if (t == null) return 'null';\n"
                    + "  try { return '' + t.getName(); } catch (e) {}\n"
                    + "  try { return '' + t.getKey(); } catch (e) {}\n"
                    + "  return '' + t; }\n"
                    + "var __fakeP = {\n"
                    + "  getUniqueId: function(){ return 'fake-uuid'; },\n"
                    + "  getPotionEffect: function(t){ var k = __tname(t); return __obs.held[k] || null; },\n"
                    + "  addPotionEffect: function(pe, force){\n"
                    + "     var k = __tname(pe.getType());\n"
                    + "     __obs.adds.push(k + '|' + pe.getDuration() + '|' + pe.getAmplifier() + '|'\n"
                    + "        + pe.isAmbient() + '|' + pe.hasParticles() + '|force=' + (force === true));\n"
                    + "     __obs.held[k] = pe;\n"
                    + "     return true; },\n"
                    + "  removePotionEffect: function(t){ var k = __tname(t); __obs.removes.push(k); delete __obs.held[k]; }\n"
                    + "};\n"
                    + "function __reset(){ __obs.adds = []; __obs.removes = []; __obs.held = {};\n"
                    + "  gsSetEffOn['fake-uuid'] = null; gsSetNightOn['fake-uuid'] = false; }\n"
                    + "function __seedMain() {\n"
                    + "  var pre = [['REGENERATION',1],['SPEED',1],['SATURATION',2],['RESISTANCE',3],['HASTE',2]];\n"
                    + "  for (var i = 0; i < pre.length; i++)\n"
                    + "    __obs.held[pre[i][0]] = new __PE(__mkType(pre[i][0]), 600, pre[i][1], true, false);\n"
                    + "}\n"
                    + "function __nvCase(hasNv, dur, owned) {\n"
                    + "  __reset(); __seedMain();\n"
                    + "  if (hasNv) __obs.held['NIGHT_VISION'] = new __PE(__mkType('NIGHT_VISION'), dur, 0, true, false);\n"
                    + "  gsSetNightOn['fake-uuid'] = owned;\n"
                    + "  gsSetEffects(__fakeP, true);\n"
                    + "  var gave = false;\n"
                    + "  for (var i = 0; i < __obs.adds.length; i++)\n"
                    + "    if (__obs.adds[i].indexOf('NIGHT_VISION|') === 0) gave = true;\n"
                    + "  return 'NV|' + (hasNv ? 1 : 0) + '|' + (owned ? 1 : 0) + '|' + (hasNv ? dur : -1)\n"
                    + "       + '|' + (gave ? 1 : 0) + '|' + (gsSetNightOn['fake-uuid'] === true ? 1 : 0);\n"
                    + "}\n"
                    + "function __runPotionProbe() {\n"
                    + "  __reset();\n"
                    + "  gsSetEffects(__fakeP, true);\n"
                    + "  for (var i = 0; i < __obs.adds.length; i++) __out.add('GIVE1|' + __obs.adds[i]);\n"
                    + "  var rec = gsSetEffOn['fake-uuid'] || [];\n"
                    + "  for (var i = 0; i < rec.length; i++) __out.add('REC|' + __tname(rec[i].t) + '|' + rec[i].amp);\n"
                    + "  __obs.adds = [];\n"
                    + "  gsSetEffects(__fakeP, true);\n"
                    + "  __out.add('GIVE2|' + __obs.adds.join(','));\n"
                    // 续期边界：剩余 541 / 540 / 600(等级不符)
                    + "  var bnd = [[541,1],[540,1],[600,3],[0,1]];\n"
                    + "  for (var i = 0; i < bnd.length; i++) {\n"
                    + "    __obs.adds = [];\n"
                    + "    __obs.held['REGENERATION'] = new __PE(__mkType('REGENERATION'), bnd[i][0], bnd[i][1], true, false);\n"
                    + "    gsSetEffects(__fakeP, true);\n"
                    + "    __out.add('BOUND|' + bnd[i][0] + '|' + bnd[i][1] + '|' + (__obs.adds.length > 0 ? 1 : 0));\n"
                    + "  }\n"
                    // 夜视三分支
                    + "  __out.add(__nvCase(true, 600, false));\n"
                    + "  __out.add(__nvCase(true, 600, true));\n"
                    + "  __out.add(__nvCase(true, 100, true));\n"
                    + "  __out.add(__nvCase(false, -1, true));\n"
                    + "  __out.add(__nvCase(false, -1, false));\n"
                    // 收走：只收本机制给的那一份
                    + "  __reset();\n"
                    + "  gsSetEffects(__fakeP, true);\n"
                    + "  __obs.removes = [];\n"
                    + "  gsSetEffects(__fakeP, false);\n"
                    + "  for (var i = 0; i < __obs.removes.length; i++) __out.add('TAKE|' + __obs.removes[i]);\n"
                    + "  __out.add('TAKE-LEFT|' + Object.keys(__obs.held).length);\n"
                    + "  __out.add('TAKE-REC|' + (gsSetEffOn['fake-uuid'] === null ? 1 : 0));\n"
                    // 收走时等级不符 ⇒ 不收（玩家自己喝了更高等级的同种药水）
                    + "  __reset();\n"
                    + "  gsSetEffects(__fakeP, true);\n"
                    + "  __obs.held['REGENERATION'] = new __PE(__mkType('REGENERATION'), 600, 5, true, false);\n"
                    + "  __obs.removes = [];\n"
                    + "  gsSetEffects(__fakeP, false);\n"
                    + "  __out.add('KEEP|' + (__obs.removes.indexOf('REGENERATION') < 0 ? 1 : 0));\n"
                    + "}\n"
                    // 退化环境：把 PotionEffectType 换成「拿不到」（= 原脚本 PET == null 的分支）。
                    // ★ 为什么不用真的 Java.type：无服务器环境下 PotionEffectType 的静态初始化会抛
                    //   ExceptionInInitializerError，而**这个错误逃不出 GraalJS**（不是普通异常，
                    //   会作为 Truffle internal error 直接冒到顶，JS 的 try/catch 抓不住）⇒
                    //   只能从「拿不到这个类」这一侧复刻同一个可观察结果。
                    + "function __runDegenerate() {\n"
                    + "  var saved = Java;\n"
                    + "  var r;\n"
                    + "  try {\n"
                    + "    Java = { type: function(n) {\n"
                    + "      if (n === 'org.bukkit.potion.PotionEffectType') return null;\n"
                    + "      return __realJava.type(n);\n"
                    + "    } };\n"
                    + "    gsSetEffOn['fake-uuid'] = null; gsSetNightOn['fake-uuid'] = false;\n"
                    + "    gsSetEffects(__fakeP, true);\n"
                    + "    var a = gsSetEffOn['fake-uuid'];\n"
                    + "    var giveLen = (a == null ? -1 : a.length);\n"
                    + "    gsSetEffects(__fakeP, false);\n"
                    + "    var b = gsSetEffOn['fake-uuid'];\n"
                    + "    r = giveLen + '|' + (b === null ? 1 : 0) + '|' + (gsSetNightOn['fake-uuid'] === true ? 1 : 0);\n"
                    + "  } finally { Java = saved; }\n"
                    + "  __out.add('DEG|' + r);\n"
                    + "}\n";

    // ======================================================================

    public static void main(String[] args) throws Exception {
        if (!Files.isRegularFile(SCRIPT)) {
            System.out.println("!! 找不到原脚本: " + SCRIPT);
            System.exit(1);
        }
        String jsSrc = new String(Files.readAllBytes(SCRIPT), StandardCharsets.UTF_8);
        List<String> jsLines = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8);
        List<String> ref = Files.readAllLines(REFLOG, StandardCharsets.UTF_8);

        System.out.println("=== A 子系统（梦境套光环 + 套装效果）一致性探针 ===");
        System.out.println("原脚本: " + SCRIPT);
        System.out.println("参考日志: " + REFLOG);
        System.out.println();

        // ---------------- Run A：真 JS（无垫片） ----------------
        System.out.println("[Run A] 真 GraalJS 求值 gs_listener.js（桥接桩为空实现）…");
        Context runA = newJs();
        RUN_A = runA;
        runA.getBindings("js").putMember("__sink", LOG_A);
        runA.eval(Source.newBuilder("js", PRELUDE + jsSrc + HELPERS_A, "JavaScript").build());
        System.out.println("        捕获到 " + LOG_A.size() + " 行 getServer().getLogger().info(...) 输出");
        System.out.println();

        // ---------------- Run B：真 JS + 药效垫片 ----------------
        System.out.println("[Run B] 真 GraalJS + 药效垫片（PotionEffectType / PotionEffect → JS 假实现）…");
        Context runB = newJs();
        runB.getBindings("js").putMember("__sink", LOG_B);
        runB.getBindings("js").putMember("__out", OUT_B);
        runB.eval(Source.newBuilder("js", PRELUDE + POTION_SHIM + jsSrc + POTION_PROBE, "JavaScript").build());
        runB.eval("js", "__runPotionProbe(); __runDegenerate();");
        System.out.println("        捕获到 " + LOG_B.size() + " 行日志 · " + OUT_B.size() + " 条药效观测");
        System.out.println();

        Value a = runA.getBindings("js");

        section("A. 常量对账（JS 顶层 var ↔ Java 字段 / 实例状态）");
        constants(a);

        section("B. 加载日志行逐字节对账");
        logs(LOG_A, LOG_B, ref);

        section("C. 反向断言（钉住「不该存在的东西」）");
        reverse(ref, jsLines, jsSrc);

        section("D. 数值 / 几何算式大量输入比对");
        geometry(a);
        pulse(a);
        generation(a);
        potions(a);

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ======================================================================
    // A. 常量对账
    // ======================================================================

    static void constants(Value js) throws Exception {
        ListenerCore core = new ListenerCore();

        // ---- 原脚本 677~1117 行里的 12 个顶层 var ----
        eq("GS_AURA_KEY（NamespacedKey.toString）", str(js, "GS_AURA_KEY"), "gs:aura_gen");
        eq("ListenerCore.GS_AURA_KEY.toString", String.valueOf(ListenerCore.GS_AURA_KEY), "gs:aura_gen");
        eq("GS_AURA_MY_GEN（初始值）", num(js, "GS_AURA_MY_GEN"), (double) core.gsAuraMyGen);
        eq("GS_AURA_MY_GEN 初始值 == -1", num(js, "GS_AURA_MY_GEN"), -1.0);
        isJsNull("GS_AURA_CFG（初始 null）", js.getMember("GS_AURA_CFG"));
        check("ListenerCore.gsAuraCfg（初始 null）", core.gsAuraCfg == null);

        eq("gsDreamFlyOn 初始为空表", memberCount(js.getMember("gsDreamFlyOn")), 0.0);
        eq("ListenerCore.gsDreamFlyOn 初始为空", (double) core.gsDreamFlyOn.size(), 0.0);
        eq("gsSetEffOn 初始为空表", memberCount(js.getMember("gsSetEffOn")), 0.0);
        eq("ListenerCore.gsSetEffOn 初始为空", (double) core.gsSetEffOn.size(), 0.0);
        eq("gsSetNightOn 初始为空表", memberCount(js.getMember("gsSetNightOn")), 0.0);
        eq("ListenerCore.gsSetNightOn 初始为空", (double) core.gsSetNightOn.size(), 0.0);
        eq("gsRadSafe 初始为空表", memberCount(js.getMember("gsRadSafe")), 0.0);
        eq("ListenerCore.gsRadSafe 初始为空", (double) core.gsRadSafe.size(), 0.0);

        eq("GS_SET_EFF_TICKS", num(js, "GS_SET_EFF_TICKS"), (double) SetBonusSub.GS_SET_EFF_TICKS);
        eq("GS_SET_EFF_TICKS == 600", (double) SetBonusSub.GS_SET_EFF_TICKS, 600.0);
        eq("GS_SET_EFF_REF", num(js, "GS_SET_EFF_REF"), (double) SetBonusSub.GS_SET_EFF_REF);
        eq("GS_SET_EFF_REF == 540", (double) SetBonusSub.GS_SET_EFF_REF, 540.0);
        eq("GS_RAD_GRACE_MS", num(js, "GS_RAD_GRACE_MS"), (double) SetBonusSub.GS_RAD_GRACE_MS);
        eq("GS_RAD_GRACE_MS == 15000", (double) SetBonusSub.GS_RAD_GRACE_MS, 15000.0);

        // ---- RT_RAD / RU_RAD：原脚本是 Java.type 探测，可能为 null ----
        boolean jsRt = js.getMember("RT_RAD") != null && !js.getMember("RT_RAD").isNull();
        boolean jsRu = js.getMember("RU_RAD") != null && !js.getMember("RU_RAD").isNull();
        check("RT_RAD 可用性一致（JS=" + jsRt + " · Java=" + (SetBonusSub.RT_RAD != null) + "）",
                jsRt == (SetBonusSub.RT_RAD != null));
        check("RU_RAD 可用性一致（JS=" + jsRu + " · Java=" + (SetBonusSub.RU_RAD != null) + "）",
                jsRu == (SetBonusSub.RU_RAD != null));
        check("RT_RAD 类名一致",
                jsRt && String.valueOf(SetBonusSub.RT_RAD.getName())
                        .equals(js.getMember("RT_RAD").toString()));
        check("RU_RAD 类名一致",
                jsRu && String.valueOf(SetBonusSub.RU_RAD.getName())
                        .equals(js.getMember("RU_RAD").toString()));

        // ---- 反向：Java 侧不许「顺手多出」常量 ----
        Set<String> sbFinals = staticFinals(SetBonusSub.class);
        Set<String> wantSb = new LinkedHashSet<>(List.of(
                "GS_SET_EFF_TICKS", "GS_SET_EFF_REF", "GS_RAD_GRACE_MS", "RT_RAD", "RU_RAD",
                // GS_SET_EFFS：JS 里是 gsSetEffects 的**函数内局部表**（没有同名顶层 var），
                // 所以它只可能是 Java 侧多出来的那一个 —— 显式登记，不算「自造常量」。
                "GS_SET_EFFS"));
        eq("SetBonusSub 的 static final 集合恰好 == 5 个 JS var + GS_SET_EFFS（不多不少）",
                sbFinals.toString(), wantSb.toString());
        eq("AuraSub 没有任何 static final（反向：不许自造常量）",
                staticFinals(AuraSub.class).toString(), "[]");
    }

    // ======================================================================
    // B. 加载日志行
    // ======================================================================

    static void logs(List<String> logA, List<String> logB, List<String> ref) {
        String auraRef = refLine(ref, "梦境套：常驻粒子已启动");
        String setRef = refLine(ref, "梦境套：套装效果已就绪");

        check("参考服日志里存在「常驻粒子已启动」行", auraRef != null);
        check("参考服日志里存在「套装效果已就绪」行", setRef != null);
        if (auraRef == null || setRef == null) {
            return;
        }

        String auraJava = ListenerCore.PREFIX + AuraSub.gsAuraStartLine(3);
        String setJava = ListenerCore.PREFIX + SetBonusSub.gsSetBonusStartLine(45000);

        eq("Java gsAuraStartLine(3) == 参考服日志行", auraJava, auraRef);
        eq("Java gsSetBonusStartLine(45000) == 参考服日志行", setJava, setRef);

        eq("真 JS 跑出来的「常驻粒子」行 == 参考服日志行", one(logA, "梦境套：常驻粒子已启动"), auraRef);
        eq("真 JS 跑出来的「套装效果」行 == 参考服日志行", one(logA, "梦境套：套装效果已就绪"), setRef);
        eq("Run B（垫片）跑出来的「常驻粒子」行 == Run A", one(logB, "梦境套：常驻粒子已启动"), auraRef);
        eq("Run B（垫片）跑出来的「套装效果」行 == Run A", one(logB, "梦境套：套装效果已就绪"), setRef);

        eq("参考服日志里「常驻粒子」行恰好 1 条", (double) countContains(ref, "梦境套：常驻粒子已启动"), 1.0);
        eq("参考服日志里「套装效果」行恰好 1 条", (double) countContains(ref, "梦境套：套装效果已就绪"), 1.0);
        eq("Run A 里「常驻粒子」行恰好 1 条", (double) countContains(logA, "梦境套：常驻粒子已启动"), 1.0);
        eq("Run A 里「套装效果」行恰好 1 条", (double) countContains(logA, "梦境套：套装效果已就绪"), 1.0);

        // 运行期条件日志：字面必须与 Java 一致，但**不该**出现在启动日志里
        eq("Java gsRadShieldLine() 字面",
                ListenerCore.PREFIX + SetBonusSub.gsRadShieldLine(),
                "[构思技艺] 梦境套：防辐射已生效 —— 宽限期持续刷新（每 0.5 秒一次，窗口 15 秒）");
        eq("Java gsRadUnavailableLine() 字面",
                ListenerCore.PREFIX + SetBonusSub.gsRadUnavailableLine(),
                "[构思技艺] 梦境套：⚠ 拿不到 RadiationTask → 防辐射不可用");

        // ---- JS 浮点除法：cooldown / 1000（配成 1000 的非整数倍时现网可见）----
        // 原脚本 1109 行：'免死 ' + (gsDaConfig().cooldown / 1000) + ' 秒'
        // JS 的 / 是**浮点**除法 ⇒ 45500 打出 "45.5"；Java 写 cooldown/1000 会错成 "45"。
        Value div = RUN_A.eval("js",
                "(function(){ var cd = [45000, 45500, 45001, 999, 0, 1000, 60480000];\n"
                        + "  var out = [];\n"
                        + "  for (var i = 0; i < cd.length; i++) out.push('' + (cd[i] / 1000));\n"
                        + "  return out.join('|'); })()");
        String[] want = div.asString().split("\\|", -1);
        long[] cds = {45000L, 45500L, 45001L, 999L, 0L, 1000L, 60480000L};
        eq("JS 浮点除法样本数 == 7", (double) want.length, 7.0);
        for (int i = 0; i < cds.length && i < want.length; i++) {
            String line = SetBonusSub.gsSetBonusStartLine(cds[i]);
            int a0 = line.indexOf("免死 ") + 3;
            int a1 = line.indexOf(" 秒");
            String got = (a0 >= 3 && a1 > a0) ? line.substring(a0, a1) : "<抠不出>";
            eq("免死秒数（JS 浮点除法）cooldown=" + cds[i], got, want[i]);
        }
        check("默认 cooldown=45000 ⇒ 「免死 45 秒」（不是 45.0）",
                SetBonusSub.gsSetBonusStartLine(45000L).contains("免死 45 秒"));
        check("非整数倍 cooldown=45500 ⇒ 「免死 45.5 秒」（整数除法会错成 45）",
                SetBonusSub.gsSetBonusStartLine(45500L).contains("免死 45.5 秒"));

        // ---- 同类：防辐射那行的 GS_RAD_GRACE_MS / 1000 ----
        Value grace = RUN_A.eval("js", "'' + (GS_RAD_GRACE_MS / 1000)");
        eq("JS GS_RAD_GRACE_MS / 1000 == \"15\"", grace.asString(), "15");
        check("Java 同判（含「窗口 15 秒」）",
                SetBonusSub.gsRadShieldLine().contains("窗口 15 秒"));
    }

    // ======================================================================
    // C. 反向断言
    // ======================================================================

    static void reverse(List<String> ref, List<String> jsLines, String jsSrc) throws Exception {
        // ① 两条「运行期条件」日志不该出现在参考服启动日志里
        check("参考服日志里**没有**「防辐射已生效」（运行期条件：要有玩家穿全套）",
                countContains(ref, "梦境套：防辐射已生效") == 0);
        check("参考服日志里**没有**「拿不到 RadiationTask」（运行期条件：拿不到才打）",
                countContains(ref, "拿不到 RadiationTask") == 0);
        check("真 JS（空服）也没打出「防辐射已生效」",
                countContains(LOG_A, "梦境套：防辐射已生效") == 0);
        check("真 JS（空服）也没打出「拿不到 RadiationTask」",
                countContains(LOG_A, "拿不到 RadiationTask") == 0);

        // ② gs_listener 没有「已加载」行 ⇒ Java 里也不许有
        check("参考服日志里**没有**「gs_listener 已加载」",
                countContains(ref, "gs_listener 已加载") == 0);
        check("原脚本源码里**没有**「gs_listener 已加载」",
                jsSrc.indexOf("gs_listener 已加载") < 0);

        // ③ 本子系统不注册任何监听器
        int reg = 0;
        for (int i = SUB_FROM - 1; i < SUB_TO && i < jsLines.size(); i++) {
            int at = 0;
            while ((at = jsLines.get(i).indexOf("registerEvent", at)) >= 0) {
                reg++;
                at += "registerEvent".length();
            }
        }
        eq("原脚本 677~1117 行里 registerEvent 出现 0 次", (double) reg, 0.0);
        check("AuraSub 不 implements org.bukkit.event.Listener",
                !org.bukkit.event.Listener.class.isAssignableFrom(AuraSub.class));
        check("SetBonusSub 不 implements org.bukkit.event.Listener",
                !org.bukkit.event.Listener.class.isAssignableFrom(SetBonusSub.class));

        // ④ 本子系统不提示、不取消事件（「入梦」只开飞行、不写字）
        String sub = String.join("\n", jsLines.subList(SUB_FROM - 1, Math.min(SUB_TO, jsLines.size())));
        check("本子系统不发聊天/动作栏/标题提示（sendMessage/sendActionBar/sendTitle 0 次）",
                !sub.contains("sendMessage") && !sub.contains("sendActionBar") && !sub.contains("sendTitle"));
        check("本子系统不取消任何事件（setCancelled 0 次）", !sub.contains("setCancelled"));

        // ⑤ 算式转写核对：探针的 JS 辅助确实逐字来自原脚本
        check("原脚本含 `var stepA = (Math.PI * 2.0) / total;`",
                jsSrc.contains("var stepA = (Math.PI * 2.0) / total;"));
        check("原脚本含 `var px = x + Math.cos(a) * cfg.radius;`",
                jsSrc.contains("var px = x + Math.cos(a) * cfg.radius;"));
        check("原脚本含 `var pz = z + Math.sin(a) * cfg.radius;`",
                jsSrc.contains("var pz = z + Math.sin(a) * cfg.radius;"));
        check("原脚本含 `var py = y + (idx === 0 ? cfg.heightHigh : cfg.heightLow);`",
                jsSrc.contains("var py = y + (idx === 0 ? cfg.heightHigh : cfg.heightLow);"));
        check("原脚本含 `c.getAmplifier() === ef.amp && c.getDuration() > GS_SET_EFF_REF`",
                jsSrc.contains("c.getAmplifier() === ef.amp && c.getDuration() > GS_SET_EFF_REF"));
        check("原脚本含 `nvCur == null || left <= GS_SET_EFF_REF`",
                jsSrc.contains("nvCur == null || left <= GS_SET_EFF_REF"));
        check("原脚本含 `if (cur.getAmplifier() !== arr[i].amp) continue;`",
                jsSrc.contains("if (cur.getAmplifier() !== arr[i].amp) continue;"));
        check("原脚本含 `return (GS_AURA_MY_GEN === -1) || (gsAuraGenNow() === GS_AURA_MY_GEN);`",
                jsSrc.contains("return (GS_AURA_MY_GEN === -1) || (gsAuraGenNow() === GS_AURA_MY_GEN);"));
        check("原脚本含 `if (n.indexOf('HEART') >= 0) continue;`（爱心粒子强制跳过）",
                jsSrc.contains("if (n.indexOf('HEART') >= 0) continue;"));

        // ⑥ ListenerCore 的世代号判定转写核对（共享层，本模块不许改，只能钉住）
        String core = new String(Files.readAllBytes(CORE_SRC), StandardCharsets.UTF_8);
        check("ListenerCore.gsAuraIsCurrent 的公式与 JS 逐字对应",
                core.contains("return (gsAuraMyGen == -1) || (gsAuraGenNow() == gsAuraMyGen);"));

        // ⑦ 跨子系统写入点：原脚本 193~194 行（onPlayerQuit）会清掉本子系统的四份状态。
        //    它只写 null / false，**从不读** ⇒ 我对 gsSetEffOn 的内部编码不会外泄。
        check("原脚本 onPlayerQuit 清状态的那两行逐字存在",
                jsSrc.contains("gsSetEffOn[qu] = null; gsSetNightOn[qu] = false;")
                        && jsSrc.contains("gsRadMark(qu, false); gsDreamFlyOn[qu] = false;"));
        int otherReaders = 0;
        for (int i = 0; i < jsLines.size(); i++) {
            if (i + 1 >= 677 && i + 1 <= 1117) {
                continue;                                  // 本子系统自己的行
            }
            String s = jsLines.get(i);
            // 别处**读**这两张表的地方（写 null/false 不算）
            if (s.contains("gsSetEffOn[") && !s.contains("gsSetEffOn[qu] = null")
                    && !s.contains("gsSetEffOn[uuid] = null") && !s.contains("gsSetEffOn[uuid] = applied")) {
                otherReaders++;
            }
        }
        eq("本子系统之外没有任何地方**读** gsSetEffOn（⇒ 内部编码 int[]{下标,amp} 不外泄）",
                (double) otherReaders, 0.0);

        // ⑧ 防辐射两个类的**方法签名**真的能反射到（否则 gsRadShield 里的调用会静默失败）
        check("RT_RAD.addGracePeriod(Player) 反射可解析",
                hasStaticMethod(SetBonusSub.RT_RAD, "addGracePeriod", Player.class));
        check("RU_RAD.clearExposure(Player) 反射可解析",
                hasStaticMethod(SetBonusSub.RU_RAD, "clearExposure", Player.class));

        // ⑨ 药效名：5 个主名 + 夜视在 1.21.11 上都是 PotionEffectType 的静态字段
        //    ★ 只用 getDeclaredFields() / getField()（**不触发** <clinit>）——
        //      Paper 的 PotionEffectType 静态初始化需要 RegistryAccess，无服务器会抛
        //      ExceptionInInitializerError。所以这里能证明「字段名存在」，
        //      但**不能**证明「getByName 在真服上解析得到」。
        for (String n : new String[]{"REGENERATION", "SPEED", "SATURATION", "RESISTANCE",
                "HASTE", "NIGHT_VISION"}) {
            check("PotionEffectType 有静态字段 " + n + "（主名，必然命中）",
                    hasStaticField("org.bukkit.potion.PotionEffectType", n));
        }
        // ★ 反向：两个 alt 在 1.21.11 上**已经不存在** ⇒ 那两条兜底永远走不到（死代码）
        for (String n : new String[]{"DAMAGE_RESISTANCE", "FAST_DIGGING"}) {
            check("PotionEffectType 已无静态字段 " + n + "（⇒ effs 表的 alt 兜底在 1.21.11 上是死代码）",
                    !hasStaticField("org.bukkit.potion.PotionEffectType", n));
        }
        check("JS 侧 effs 表里确实写着这两个旧名（照抄自原脚本 964~965 行）",
                jsSrc.contains("alt: 'DAMAGE_RESISTANCE'") && jsSrc.contains("alt: 'FAST_DIGGING'"));
    }

    /** 该类是否有这个 public static 方法（不触发静态初始化）。 */
    static boolean hasStaticMethod(Class<?> c, String name, Class<?>... params) {
        if (c == null) {
            return false;
        }
        try {
            java.lang.reflect.Method m = c.getMethod(name, params);
            return java.lang.reflect.Modifier.isStatic(m.getModifiers());
        } catch (Throwable e) {
            return false;
        }
    }

    /** 该类是否有这个静态字段（不触发静态初始化）。 */
    static boolean hasStaticField(String className, String field) {
        try {
            Class<?> c = Class.forName(className, false, ListenerASubParityProbe.class.getClassLoader());
            java.lang.reflect.Field f = c.getField(field);
            return java.lang.reflect.Modifier.isStatic(f.getModifiers());
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // D1. 粒子圆周角度分布
    // ======================================================================

    static void geometry(Value js) {
        Value fn = js.getMember("__pPoints");
        double[][] radii = {{0.80, 0.10, 0.10}, {1.00, 0.25, 0.05}, {2.50, 0.05, 0.25}};
        int combos = 0;
        long points = 0;
        double maxDiff = 0.0;
        int mismatches = 0;

        for (int eachCount = 1; eachCount <= 24; eachCount++) {
            for (int n = 1; n <= 3; n++) {
                int total = n * eachCount;
                for (double[] r : radii) {
                    combos++;
                    Value got = fn.execute(total, n, r[0], r[1], r[2]);
                    long sz = got.getArraySize();
                    if (sz != (long) total * 3L) {
                        mismatches++;
                        continue;
                    }
                    for (int i = 0; i < total; i++) {
                        double[] want = AuraSub.gsAuraPoint(i, total, n, r[0], r[1], r[2]);
                        for (int k = 0; k < 3; k++) {
                            double jv = got.getArrayElement(i * 3L + k).asDouble();
                            double jj = want[k];
                            double d = Math.abs(jv - jj);
                            if (d > maxDiff) {
                                maxDiff = d;
                            }
                            if (!(d <= 1.0e-12 * Math.max(1.0, Math.abs(jj)))) {
                                mismatches++;
                            }
                            points++;
                        }
                    }
                }
            }
        }
        eq("粒子圆周扫描组合数 == 24×3×3 = 216", (double) combos, 216.0);
        // 每个组合的点数 = n*eachCount（n=1..3）⇒ 单半径 6*Σ(1..24) = 1800 点，×3 半径 ×3 分量
        eq("粒子圆周扫描点数 == 5400×3", (double) points, 5400.0 * 3.0);
        eq("粒子圆周逐点差异（>1e-12 的个数）== 0", (double) mismatches, 0.0);
        check("粒子圆周最大绝对差 " + String.format(Locale.ROOT, "%.3e", maxDiff) + " <= 1e-12",
                maxDiff <= 1.0e-12);

        // 角度均分：total 个点的角度两两不同、且首尾差恰好是 2π/total 的整数倍
        double step = AuraSub.gsAuraPoint(1, 6, 1, 0.80, 0.10, 0.10)[0]
                - AuraSub.gsAuraPoint(0, 6, 1, 0.80, 0.10, 0.10)[0];
        check("total=6 时相邻点 x 位移不同（确实在转圈）", Math.abs(step) > 1.0e-9);
        double dy0 = AuraSub.gsAuraPoint(0, 6, 2, 0.80, 0.25, 0.05)[1];
        double dy1 = AuraSub.gsAuraPoint(1, 6, 2, 0.80, 0.25, 0.05)[1];
        eq("idx==0 走 heightHigh", dy0, 0.25);
        eq("idx!=0 走 heightLow", dy1, 0.05);
        eq("n==1 时所有点都走 heightHigh",
                AuraSub.gsAuraPoint(5, 6, 1, 0.80, 0.25, 0.05)[1], 0.25);
    }

    // ======================================================================
    // D1b. 粒子脉冲端到端（真 JS gsAuraPulse ↔ Java 侧同一段循环骨架）
    // ======================================================================

    /**
     * 让真 JS 的 {@code gsAuraPulse()} 在一个假世界/假玩家上跑一遍，抓下每一次
     * {@code spawnParticle} 的实参；再让 Java 侧的 {@link AuraSub#gsAuraParticle} +
     * {@link AuraSub#gsAuraPoint} 按<b>同一段循环骨架</b>跑一遍，逐值比对。
     *
     * <p>循环骨架（{@code total / idx / type==null continue}）在两个 Java 方法里是写死的，
     * 这里照抄一遍只为对账 —— 骨架本身由源码评审保证与 JS 一致（探针 C 段钉了源码字面）。
     */
    static void pulse(Value js) {
        Value fn = js.getMember("__runAura");

        Object[][] cases = {
                // enabled, particles,                eachCount, radius, hh,    hl
                {true, new String[]{"SOUL"}, 6, 0.80, 0.10, 0.10},
                {true, new String[]{"SOUL", "END_ROD"}, 4, 1.25, 0.30, 0.05},
                {true, new String[]{"HEART", "SOUL"}, 3, 0.50, 0.10, 0.10},
                {true, new String[]{"BOGUS_PARTICLE_NAME", "SOUL"}, 3, 0.75, 0.20, 0.20},
                {true, new String[]{"SCULK_SOUL"}, 5, 0.80, 0.10, 0.10},
                {true, new String[]{"END_ROD"}, 5, 0.80, 0.10, 0.10},
                {true, new String[]{"SOUL", "HEART", "POOF"}, 8, 2.00, 0.40, 0.02},
                {false, new String[]{"SOUL"}, 6, 0.80, 0.10, 0.10},
                {true, new String[]{}, 6, 0.80, 0.10, 0.10},
        };

        double X = 100.5;
        double Y = 64.0;
        double Z = -20.25;
        int totalSpawns = 0;

        for (Object[] c : cases) {
            boolean enabled = (Boolean) c[0];
            String[] ps = (String[]) c[1];
            int each = (Integer) c[2];
            double r = (Double) c[3];
            double hh = (Double) c[4];
            double hl = (Double) c[5];

            Value got = fn.execute(enabled, ps, each, r, hh, hl);
            long sz = got.getArraySize();

            List<Object> java = javaAuraSpawns(enabled, ps, each, r, hh, hl, X, Y, Z);

            String label = "脉冲 enabled=" + enabled + " particles=" + String.join(",", ps)
                    + " eachCount=" + each + " radius=" + r;

            if (sz != java.size()) {
                eq(label + " 的 spawnParticle 次数", (double) sz, (double) java.size());
                continue;
            }
            totalSpawns += (int) sz;

            boolean allOk = true;
            StringBuilder diff = new StringBuilder();
            for (int i = 0; i < sz; i++) {
                Value v = got.getArrayElement(i);
                Object want = java.get(i);
                if (want instanceof String) {
                    String jsS = v.isString() ? v.asString() : String.valueOf(v);
                    if (!jsS.equals(want)) {
                        allOk = false;
                        diff.append(" [").append(i).append("] ").append(jsS).append("≠").append(want);
                    }
                } else {
                    double jv = v.asDouble();
                    double wv = (Double) want;
                    if (!(Math.abs(jv - wv) <= 1.0e-12 * Math.max(1.0, Math.abs(wv)))) {
                        allOk = false;
                        diff.append(" [").append(i).append("] ").append(jv).append("≠").append(wv);
                    }
                }
            }
            check(label + " 的每一次 spawnParticle 实参逐值一致（" + (sz / 9) + " 次）"
                            + (allOk ? "" : "  ← " + diff),
                    allOk);
        }

        eq("脉冲端到端总点数（9 组配置）", (double) totalSpawns, 513.0);

        // 单点核对：默认配置下第一次 spawn 的 9 个实参
        Value one = fn.execute(true, new String[]{"SOUL"}, 6, 0.80, 0.10, 0.10);
        eq("默认配置 spawnParticle 的粒子名 = SOUL", one.getArrayElement(0).asString(), "SOUL");
        eq("默认配置 count 实参 = 1", one.getArrayElement(4).asDouble(), 1.0);
        eq("默认配置 offsetX 实参 = 0.02", one.getArrayElement(5).asDouble(), 0.02);
        eq("默认配置 offsetY 实参 = 0.02", one.getArrayElement(6).asDouble(), 0.02);
        eq("默认配置 offsetZ 实参 = 0.02", one.getArrayElement(7).asDouble(), 0.02);
        eq("默认配置 extra 实参 = 0.0", one.getArrayElement(8).asDouble(), 0.0);
        eq("默认配置第 1 点的 py = 64.0 + 0.10", one.getArrayElement(2).asDouble(), 64.10);
        eq("默认配置第 1 点的 px = 100.5 + 0.80", one.getArrayElement(1).asDouble(), 101.30);
        eq("默认配置第 1 点的 pz = -20.25 + 0.0", one.getArrayElement(3).asDouble(), -20.25);

        // ---- 反向：粒子过滤点到底在哪一层 ----
        // ★ HEART / END_ROD / DRAGON_BREATH / SCULK_SOUL 在 1.21.11 的 Particle 里**都存在**
        //   （javap 实测）⇒ 传进 gsAuraPulse 时照刷 —— 过滤只能发生在上游 gsAuraConfig。
        check("Particle.HEART 存在 ⇒ gsAuraPulse 收到 HEART 就照刷（过滤点确实在上游 gsAuraConfig）",
                fn.execute(true, new String[]{"HEART", "SOUL"}, 3, 0.50, 0.10, 0.10)
                        .getArrayElement(0).asString().contains("HEART"));
        check("Java 侧同判：AuraSub.gsAuraParticle(\"HEART\") 非 null",
                AuraSub.gsAuraParticle("HEART") != null);
        check("Particle.END_ROD 存在 ⇒ valueOf 直接成功，alts 里那两条恒等项**永远走不到**（死代码）",
                fn.execute(true, new String[]{"END_ROD"}, 5, 0.80, 0.10, 0.10).getArraySize() == 45L);
        check("Java 侧同判：END_ROD 解析成功（不是 null）",
                AuraSub.gsAuraParticle("END_ROD") != null);
        check("BOGUS 粒子名取不到 ⇒ 该点整点跳过（点数比 n=1 时少一半）",
                fn.execute(true, new String[]{"BOGUS_PARTICLE_NAME", "SOUL"}, 3, 0.75, 0.20, 0.20)
                        .getArraySize() == 27L);
        check("Java 侧同判：BOGUS 解析为 null", AuraSub.gsAuraParticle("BOGUS_PARTICLE_NAME") == null);
        check("enabled=false ⇒ 一次都不刷",
                fn.execute(false, new String[]{"SOUL"}, 6, 0.80, 0.10, 0.10).getArraySize() == 0L);
        check("空粒子表 ⇒ 一次都不刷（n < 1 提前 return）",
                fn.execute(true, new String[]{}, 6, 0.80, 0.10, 0.10).getArraySize() == 0L);
    }

    /**
     * Java 侧同一段循环骨架（照抄 {@link AuraSub#gsAuraPulse} 的循环体）产出的
     * {@code spawnParticle} 实参扁平表：每次 9 项 —— 粒子名 + 8 个数值。
     */
    static List<Object> javaAuraSpawns(boolean enabled, String[] particles, int eachCount,
                                       double radius, double hh, double hl,
                                       double x, double y, double z) {
        List<Object> out = new ArrayList<>();
        if (!enabled) {
            return out;
        }
        int n = particles.length;
        if (n < 1) {
            return out;
        }
        int total = n * eachCount;
        for (int i = 0; i < total; i++) {
            int idx = i % n;
            Particle type = AuraSub.gsAuraParticle(particles[idx]);
            if (type == null) {
                continue;
            }
            double[] pt = AuraSub.gsAuraPoint(i, total, n, radius, hh, hl);
            out.add(type.name());
            out.add(x + pt[0]);
            out.add(y + pt[1]);
            out.add(z + pt[2]);
            out.add(1.0);
            out.add(0.02);
            out.add(0.02);
            out.add(0.02);
            out.add(0.0);
        }
        return out;
    }

    // ======================================================================
    // D2. 世代号逻辑
    // ======================================================================

    static void generation(Value js) throws Exception {
        // ① 纯公式：JS 与 Java 转写在大网格上逐值一致
        Value f = js.getMember("__pIsCurrent");
        int[] gens = {-1, 0, 1, 2, 3, 7, 96, 1000, Integer.MAX_VALUE};
        int bad = 0;
        int n = 0;
        for (int my : gens) {
            for (int now : gens) {
                boolean jsV = f.execute(my, now).asBoolean();
                boolean jV = isCurrentFormula(my, now);
                n++;
                if (jsV != jV) {
                    bad++;
                }
            }
        }
        eq("世代号公式网格点数 == 81", (double) n, 81.0);
        eq("世代号公式不一致数 == 0", (double) bad, 0.0);

        // ② 端到端（真 JS + 假世界 PDC）：bump → 当前；再 bump → 当前；被抬号 → 失效
        String sc = js.getMember("__pGenScenario").execute().asString();
        eq("世代号端到端（真 JS）",
                sc, "1:1:true|2:2:true|2:3:false|-1:-1:true|7:-1:false");

        // ③ Java 侧在「无世界」环境下的等价行为
        ListenerCore core = new ListenerCore();
        eq("无世界时 gsAuraGenNow() == -1", (double) ListenerCore.gsAuraGenNow(), -1.0);
        core.gsAuraGenBump();
        eq("无世界时 gsAuraGenBump() 不改 MY_GEN（仍是 -1）", (double) core.gsAuraMyGen, -1.0);
        check("无世界且 MY_GEN == -1 ⇒ gsAuraIsCurrent() == true", core.gsAuraIsCurrent());
        core.gsAuraMyGen = 7;
        check("无世界且 MY_GEN == 7 ⇒ gsAuraIsCurrent() == false（与真 JS 场景 5 一致）",
                !core.gsAuraIsCurrent());
    }

    /** {@code gsAuraIsCurrent} 的纯公式（与 {@link ListenerCore} 逐字对应，仅用于网格对账）。 */
    static boolean isCurrentFormula(int myGen, int nowGen) {
        return (myGen == -1) || (nowGen == myGen);
    }

    // ======================================================================
    // D3. 药效：等级 / 时长 / 续期阈值 / 收走 / 夜视
    // ======================================================================

    static void potions(Value js) {
        // ---- ① 给药表：真 JS 的 6 次 addPotionEffect 与 Java 的 GS_SET_EFFS 逐项比对 ----
        List<String> give1 = new ArrayList<>();
        for (String s : OUT_B) {
            if (s.startsWith("GIVE1|")) {
                give1.add(s.substring("GIVE1|".length()));
            }
        }
        eq("真 JS 一次给 6 种药效（5 主 + 夜视）", (double) give1.size(), 6.0);
        for (int i = 0; i < SetBonusSub.GS_SET_EFFS.size() && i < give1.size(); i++) {
            SetBonusSub.Eff ef = SetBonusSub.GS_SET_EFFS.get(i);
            eq("第 " + (i + 1) + " 种药效（" + ef.name + "）名称/时长/等级/ambient/particles/force",
                    give1.get(i),
                    ef.name + "|" + SetBonusSub.GS_SET_EFF_TICKS + "|" + ef.amp + "|true|false|force=true");
        }
        if (give1.size() == 6) {
            eq("第 6 种药效（夜视）名称/时长/等级/ambient/particles/force",
                    give1.get(5),
                    "NIGHT_VISION|" + SetBonusSub.GS_SET_EFF_TICKS + "|0|true|false|force=true");
        }

        // ---- ② 登记表 gsSetEffOn：真 JS 与 Java 的表同名同等级同顺序 ----
        List<String> rec = new ArrayList<>();
        for (String s : OUT_B) {
            if (s.startsWith("REC|")) {
                rec.add(s.substring("REC|".length()));
            }
        }
        eq("真 JS 登记 5 条（夜视单独一套标记、不进这张表）", (double) rec.size(), 5.0);
        for (int i = 0; i < SetBonusSub.GS_SET_EFFS.size() && i < rec.size(); i++) {
            SetBonusSub.Eff ef = SetBonusSub.GS_SET_EFFS.get(i);
            eq("登记第 " + (i + 1) + " 条", rec.get(i), ef.name + "|" + ef.amp);
        }

        // ---- ③ 续期阈值：真 JS 的边界行为 == Java gsSetEffNeed ----
        int bndN = 0;
        for (String s : OUT_B) {
            if (!s.startsWith("BOUND|")) {
                continue;
            }
            bndN++;
            String[] p = s.split("\\|");
            int dur = Integer.parseInt(p[1]);
            int amp = Integer.parseInt(p[2]);
            int jsGave = Integer.parseInt(p[3]);
            int javaGave = SetBonusSub.gsSetEffNeed(true, amp, dur, 1) ? 1 : 0;
            eq("续期边界 dur=" + dur + " amp=" + amp, (double) jsGave, (double) javaGave);
        }
        eq("续期边界场景数 == 4", (double) bndN, 4.0);

        // ---- ④ 大网格：gsSetEffNeed / gsSetNightNeed / gsSetTakeAway / gsSetNightAction ----
        Value fNeed = js.getMember("__pEffNeed");
        Value fNight = js.getMember("__pNightNeed");
        Value fNightAct = js.getMember("__pNightAction");
        Value fTake = js.getMember("__pTakeAway");
        int[] amps = {-1, 0, 1, 2, 3, 4, 5};
        int[] durs = {0, 1, 539, 540, 541, 599, 600, 1200};
        int bad = 0;
        int cnt = 0;
        for (int hasI = 0; hasI <= 1; hasI++) {
            for (int curAmp : amps) {
                for (int dur : durs) {
                    for (int want : amps) {
                        boolean has = hasI == 1;
                        boolean jsV = fNeed.execute(has, curAmp, dur, want).asBoolean();
                        boolean jV = SetBonusSub.gsSetEffNeed(has, curAmp, dur, want);
                        cnt++;
                        if (jsV != jV) {
                            bad++;
                        }
                        boolean jsN = fNight.execute(has, dur).asBoolean();
                        boolean jN = SetBonusSub.gsSetNightNeed(has, dur);
                        cnt++;
                        if (jsN != jN) {
                            bad++;
                        }
                        boolean jsT = fTake.execute(has, curAmp, want).asBoolean();
                        boolean jT = SetBonusSub.gsSetTakeAway(has, curAmp, want);
                        cnt++;
                        if (jsT != jT) {
                            bad++;
                        }
                        for (int ownedI = 0; ownedI <= 1; ownedI++) {
                            boolean owned = ownedI == 1;
                            int jsA = fNightAct.execute(has, owned, dur).asInt();
                            int jA = SetBonusSub.gsSetNightAction(has, owned, dur);
                            cnt++;
                            if (jsA != jA) {
                                bad++;
                            }
                        }
                    }
                }
            }
        }
        eq("药效判定网格点数 == 2×7×8×7×5 = 3920", (double) cnt, 3920.0);
        eq("药效判定不一致数 == 0", (double) bad, 0.0);

        // ---- ⑤ 夜视三分支：真 JS 的 5 个场景 == Java ----
        List<String> nv = new ArrayList<>();
        for (String s : OUT_B) {
            if (s.startsWith("NV|")) {
                nv.add(s);
            }
        }
        eq("夜视场景数 == 5", (double) nv.size(), 5.0);
        if (nv.size() == 5) {
            // 字段顺序：NV|has|owned|dur|gave|registered
            eq("夜视：玩家自己喝的（has=1 owned=0）→ 不碰、不登记", nv.get(0), "NV|1|0|600|0|0");
            eq("夜视：本机制的且还剩 600 ⇒ 登记但不补发", nv.get(1), "NV|1|1|600|0|1");
            eq("夜视：本机制的但只剩 100 ⇒ 登记且补发", nv.get(2), "NV|1|1|100|1|1");
            eq("夜视：没有夜视且是本机制的 ⇒ 登记且补发", nv.get(3), "NV|0|1|-1|1|1");
            eq("夜视：没有夜视、也不是本机制的 ⇒ 登记且补发", nv.get(4), "NV|0|0|-1|1|1");
            eq("Java 复算场景 1", (double) SetBonusSub.gsSetNightAction(true, false, 600), 0.0);
            eq("Java 复算场景 2", (double) SetBonusSub.gsSetNightAction(true, true, 600), 1.0);
            eq("Java 复算场景 3", (double) SetBonusSub.gsSetNightAction(true, true, 100), 2.0);
            eq("Java 复算场景 4", (double) SetBonusSub.gsSetNightAction(false, true, 0), 2.0);
            eq("Java 复算场景 5", (double) SetBonusSub.gsSetNightAction(false, false, 0), 2.0);
        }

        // ---- ⑥ 收走：只收本机制给的那一份（顺序 + 清单清空） ----
        List<String> take = new ArrayList<>();
        for (String s : OUT_B) {
            if (s.startsWith("TAKE|")) {
                take.add(s.substring("TAKE|".length()));
            }
        }
        eq("收走 6 种（夜视 + 5 主）", (double) take.size(), 6.0);
        if (take.size() == 6) {
            eq("收走顺序第 1 个 = 夜视（原脚本先收夜视）", take.get(0), "NIGHT_VISION");
            for (int i = 0; i < SetBonusSub.GS_SET_EFFS.size(); i++) {
                eq("收走顺序第 " + (i + 2) + " 个", take.get(i + 1), SetBonusSub.GS_SET_EFFS.get(i).name);
            }
        }
        eq("收走后玩家身上一种都不剩", one(OUT_B, "TAKE-LEFT|"), "TAKE-LEFT|0");
        eq("收走后 gsSetEffOn[uuid] 被置为 null", one(OUT_B, "TAKE-REC|"), "TAKE-REC|1");

        // ---- ⑦ 收走时等级不符 ⇒ 不收（玩家自己喝了更高等级的同种药水） ----
        eq("玩家把生命恢复喝到 V 级后摘套 ⇒ 不收走它", one(OUT_B, "KEEP|"), "KEEP|1");
        check("Java 复算：等级不符不收", !SetBonusSub.gsSetTakeAway(true, 5, 1));
        check("Java 复算：等级相符才收", SetBonusSub.gsSetTakeAway(true, 1, 1));

        // ---- ⑧ 第二次脉冲（药效都还在）⇒ 一个都不重发（不闪图标） ----
        eq("第二次脉冲不重发任何药效（不闪图标 / 不闪画面）",
                one(OUT_B, "GIVE2|"), "GIVE2|");

        // ---- ⑨ 退化环境（拿不到药效注册表）下的端到端：真 JS 与 Java 结果一致 ----
        eq("真 JS 拿不到 PotionEffectType 时：给不出药效 / 登记空表 / 收走后置 null / 夜视不登记",
                one(OUT_B, "DEG|"), "DEG|0|1|0");
        String uuid = "00000000-0000-0000-0000-0000000000de";
        ListenerCore core = new ListenerCore();
        SetBonusSub sub = new SetBonusSub(core);
        Player fp = fakePlayer(uuid);
        sub.gsSetEffects(fp, true);
        List<int[]> got = core.gsSetEffOn.get(uuid);
        check("Java 同样给不出药效但登记了空表（与真 JS 的 length==0 一致）",
                got != null && got.isEmpty());
        check("Java 同样不登记夜视", core.gsSetNightOn.get(uuid) != Boolean.TRUE);
        sub.gsSetEffects(fp, false);
        check("Java 收走后 gsSetEffOn[uuid] 被置为 null（与真 JS 一致）",
                core.gsSetEffOn.get(uuid) == null);
    }

    /** 用 {@link java.lang.reflect.Proxy} 造一个「只认 uuid」的假玩家。 */
    static Player fakePlayer(String uuidStr) {
        final java.util.UUID id = java.util.UUID.fromString(uuidStr);
        return (Player) java.lang.reflect.Proxy.newProxyInstance(
                ListenerASubParityProbe.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, margs) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return id;
                        case "toString":
                            return "fake-player";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == (margs == null || margs.length == 0 ? null : margs[0]);
                        default:
                            return zero(method.getReturnType());
                    }
                });
    }

    static Object zero(Class<?> t) {
        if (!t.isPrimitive()) {
            return null;
        }
        if (t == boolean.class) {
            return Boolean.FALSE;
        }
        if (t == int.class) {
            return 0;
        }
        if (t == long.class) {
            return 0L;
        }
        if (t == double.class) {
            return 0.0d;
        }
        if (t == float.class) {
            return 0.0f;
        }
        if (t == short.class) {
            return (short) 0;
        }
        if (t == byte.class) {
            return (byte) 0;
        }
        if (t == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ======================================================================
    // 工具
    // ======================================================================

    static Context newJs() {
        return Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
    }

    static void section(String title) {
        System.out.println("---- " + title + " ----");
    }

    static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  OK   " + label);
        } else {
            fail++;
            System.out.println("  失败 " + label);
        }
    }

    static void eq(String label, String got, String want) {
        boolean ok = (got == null ? want == null : got.equals(want));
        if (ok) {
            pass++;
            System.out.println("  OK   " + label);
        } else {
            fail++;
            System.out.println("  失败 " + label);
            System.out.println("         JS/实际 = " + show(got));
            System.out.println("         Java/期望 = " + show(want));
        }
    }

    static void eq(String label, double got, double want) {
        boolean ok = got == want;
        if (ok) {
            pass++;
            System.out.println("  OK   " + label);
        } else {
            fail++;
            System.out.println("  失败 " + label + "  实际=" + got + "  期望=" + want);
        }
    }

    static void isJsNull(String label, Value v) {
        check(label, v == null || v.isNull());
    }

    static String show(String s) {
        return s == null ? "<null>" : "\"" + s + "\"";
    }

    /** 取 JS 顶层 var 的字符串表示。 */
    static String str(Value js, String name) {
        Value v = js.getMember(name);
        return v == null || v.isNull() ? null : v.toString();
    }

    /** 取 JS 顶层数值 var。 */
    static double num(Value js, String name) {
        Value v = js.getMember(name);
        return v == null ? Double.NaN : v.asDouble();
    }

    /** JS 对象的自有成员个数（用于断言 {} 是空表）。 */
    static double memberCount(Value v) {
        if (v == null || v.isNull()) {
            return -1.0;
        }
        double n = 0;
        for (String k : v.getMemberKeys()) {
            n++;
        }
        return n;
    }

    static String refLine(List<String> ref, String needle) {
        for (String s : ref) {
            if (s.contains(needle)) {
                return s;
            }
        }
        return null;
    }

    /** 取第一条<b>包含</b>该子串的行（日志行带 {@code [构思技艺] } 前缀，不能用 startsWith）。 */
    static String one(List<String> list, String needle) {
        for (String s : list) {
            if (s.contains(needle)) {
                return s;
            }
        }
        return null;
    }

    static int countContains(List<String> list, String needle) {
        int n = 0;
        for (String s : list) {
            if (s.contains(needle)) {
                n++;
            }
        }
        return n;
    }

    /** 一个类里所有 {@code static final} 字段名（按声明顺序）。 */
    static Set<String> staticFinals(Class<?> c) {
        Set<String> out = new LinkedHashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers())) {
                out.add(f.getName());
            }
        }
        return out;
    }
}
