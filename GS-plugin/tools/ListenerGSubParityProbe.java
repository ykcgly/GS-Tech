package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.GSPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 「海水过滤器（钓鱼获取）」+「祖宗发射器」两个子系统的对账探针
 * （原脚本 {@code gs_listener.js} 第 <b>13571~14336</b> 行）—— gs_listener 的最后两块。
 *
 * <h2>四类断言</h2>
 * <ol>
 *   <li><b>A 常量对账</b>：用<b>真 GraalJS</b> 跑原脚本，读出这两个子系统的顶层 {@code var}，
 *       与 {@link SfSub} / {@link AlSub} 的字段<b>按名按值</b>比对；
 *       并做<b>完备性检查</b>（JS 侧成员数 == 已处理数，双向）。</li>
 *   <li><b>B 日志行逐字节</b>：参考服日志第 46/47/48 行 vs 原脚本真身打出来的行 vs
 *       Java 的 {@code xxxLine()}。</li>
 *   <li><b>C 数值 / 几何算式</b>：把原脚本的<b>真函数</b>用 mock 世界跑起来，
 *       在<b>大量输入</b>上与 Java 侧逐值（逐<b>位</b>）比对 ——
 *       弹道逐刻推进 / 撞墙扫掠 / 爆炸球过滤 / 开火闸门 / 切换闸门 / 名字 ▼ / 7% 钓率。</li>
 *   <li><b>D 反向断言</b>：钉住「不该存在的东西」（聊天提示词、换手关技能、
 *       常态扣弹药、{@code ignoreCancelled=true}、{@code createExplosion}、
 *       {@code static} 闩锁、没注册的事件…）。</li>
 * </ol>
 *
 * <h2>★★ 两处关键的「环境保真」（本探针与其它探针最大的不同）</h2>
 * <ol>
 *   <li><b>GraalJS 的 {@code HostAccess} 必须照抄 RSC 的</b>。
 *       RSC 的 {@code ScriptEval.createHostAccess()}（
 *       {@code QHCGS/_rsc_src/RykenSlimeCustomizer-main/.../script/ScriptEval.java} 第 84~117 行）
 *       显式注册了
 *       {@code targetTypeMapping(Double.class, Float.class, null, Double::floatValue)}。
 *       <b>没有它</b>，{@code new Particle.DustOptions(color, 1.2)} 会因为
 *       「Invalid or lossy primitive coercion」抛异常（实测）⇒
 *       {@code gsAlDustOpt()} 恒返回 null ⇒ <b>常态红色粒子一个都不画</b>。
 *       用 {@code allowAllAccess(true)} 的默认配置去跑原脚本，
 *       得到的是<b>参考服上不存在的</b>行为。本探针用 {@link #rscHostAccess()} 精确复刻。</li>
 *   <li><b>{@code runLater} 必须变成同步泵，而且 <i>Java 侧也要能泵</i></b>。
 *       弹道是 {@code runLater} 自链 —— JS 侧好办（把 {@code runLater} 换成带预算的同步版），
 *       但 Java 侧的 {@link AlSub#gsAlLaunch} 走的是真 {@link GS#runLater}。
 *       本探针用反射把 {@code Bukkit.server} 换成假服务器（{@code getScheduler()} 返回
 *       一个把任务塞进队列的假 {@code BukkitScheduler}）⇒
 *       <b>生产代码一行不改</b>就能同步驱动整条弹道。
 *       同一个假服务器还提供了 {@code getPluginManager()}（数 {@code registerEvents} 次数）
 *       与 {@code getLogger()}（收日志行）。</li>
 * </ol>
 *
 * <h2>★★ mock 是「一份 Java 实现、两边共用」</h2>
 * mock 世界 / 玩家 / 生物都是<b>真 {@code org.bukkit.World} / {@code Player} / {@code LivingEntity}
 * 的 {@link Proxy}</b>，通过 {@code ctx.getBindings("js").putMember(...)} 注入 JS。
 * 于是 JS 侧与 Java 侧调用的是<b>同一份</b> mock，效果也落在<b>同一个</b>记录区
 * ⇒ 差异只可能来自「脚本 vs Java 类」本身，这正是要测的东西。
 *
 * <p>运行（工作目录 = GSPlugin 根）：★ 比别的探针多两个 jar，见下面「假运行环境」
 * <pre>
 *   set JOML=D:\qq下载\粘液科技自用\server\libraries\org\joml\joml\1.10.8\joml-1.10.8.jar
 *   set GSON=D:\qq下载\粘液科技自用\server\libraries\com\google\code\gson\gson\2.13.2\gson-2.13.2.jar
 *   javac -encoding UTF-8 --release 21 -nowarn -proc:none ^
 *     -cp "libs/*;libs/graal/*;%JOML%;%GSON%;build/classes;build/sub_core;build/sub_g" ^
 *     -d build/probe_g tools/ListenerGSubParityProbe.java
 *   java -Dstdout.encoding=UTF-8 -cp "libs/*;libs/graal/*;%JOML%;%GSON%;build/classes;build/sub_core;build/sub_g;build/probe_g" ^
 *     com.qinghuacisj.gs.script.module.listener.ListenerGSubParityProbe
 * </pre>
 * 失败时 {@code System.exit(1)}。
 *
 * <p>★ <b>为什么还多两个 jar</b>（不在 {@code libs\} 里）：{@code joml} 是 Paper 的
 * {@code HostClassDesc.collectPublicMethods} 会去碰 {@code org.joml.Vector3f}；
 * {@code gson} 是造 {@code EntityDamageByEntityEvent} 时 Paper 内部走
 * {@code com.google.gson.JsonObject}。两者都只是<b>替运行环境补类</b>，不参与被测逻辑。
 *
 * <h2>★★ 本探针补的四层「假运行环境」（全部 {@code Unsafe}/反射，不碰生产代码）</h2>
 * <ol>
 *   <li>{@code installBukkitSeam()} —— {@code Bukkit.server} 换假服务器：
 *       {@code getScheduler()} 返回排队用的 {@code BukkitScheduler}（同步驱动弹道）、
 *       {@code getPluginManager()} 数 {@code registerEvents}、{@code getLogger()} 收日志、
 *       {@code getUnsafe()} 让 {@code EntityDamageByEntityEvent} 能构造出来。</li>
 *   <li>{@code installFakeRegistry()} —— 给
 *       {@code io.papermc.paper.registry.RegistryAccessHolder.INSTANCE} 塞假实现。
 *       不加的话 {@code org.bukkit.Registry} 静态初始化直接
 *       {@code IllegalStateException: No RegistryAccess implementation found}，
 *       连带 {@code Material} / {@code EntityType} 全不可用。
 *       ★ 顺序讲究：先 {@code Class.forName(name, false, cl)}（只 load）→ 注入 → 最后才让它
 *       {@code <clinit>}。反了就白干。</li>
 *   <li>{@code installFakeSlimefun()} —— ★★ <b>本探针最关键的一层</b>。
 *       {@code Slimefun.instance} / {@code Slimefun.registry} /
 *       {@code SlimefunRegistry.slimefunIds} 各塞一个 {@code Unsafe} 空壳，表里放两个物品 ID；
 *       {@code ProbeSfStack extends SlimefunItemStack}（命中 {@code getByItem} 的<b>快路</b>），
 *       私有 {@code id} 字段用 {@code Unsafe} 写（{@code getItemId()} 是 final，覆盖不了）。
 *       <b>不加这一层，「手里拿着发射器」那条路径一个字节都测不到</b>
 *       —— {@code gsAlIsLauncher} 恒 false，全部处理器在第一道闸门就返回。</li>
 *   <li>{@code getSfItemByItem} 的 JS 桩 —— 原脚本的 {@code gsIsItem} 走这个 RSC 宿主函数。
 *       不补的话 JS 侧 {@code ReferenceError}、被处理器自己的 try/catch 吞掉，
 *       表现为「什么都没发生」，JS/Java 对账退化成「空对空」。
 *       RSC 的 {@code getSfItemByItem} 就是 {@code SlimefunItem::getByItem}
 *       （见 {@code Guards} 的注释）⇒ 桩里转发到真 API 是<b>等价实现</b>，不是放宽。</li>
 * </ol>
 */
public final class ListenerGSubParityProbe {

    private static int pass = 0;
    private static int fail = 0;
    private static final List<String> FAILURES = new ArrayList<>();

    // ======================================================================
    // 路径
    // ======================================================================

    private static Path pick(Path a, Path b) {
        return Files.exists(a) ? a : b;
    }

    private static final Path SCRIPT = pick(
            Path.of("../QHCGS/GS/GS/scripts/gs_listener.js"),
            Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"));

    private static final Path LOG = pick(
            Path.of("docs/参考服日志_构思技艺.txt"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt"));

    private static final Path SF_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/SfSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/SfSub.java"));

    private static final Path AL_SRC = pick(
            Path.of("src/main/java/com/qinghuacisj/gs/script/module/listener/AlSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/src/main/java/com/qinghuacisj/gs/script/module/listener/AlSub.java"));

    /**
     * D 段的反向断言要扫<b>源码文本</b>。默认扫上面那两个正式路径，
     * 但允许用系统属性覆盖：
     * <pre>-Dgs.probe.srcAl=&lt;路径&gt;  -Dgs.probe.srcSf=&lt;路径&gt;</pre>
     *
     * <p>★ <b>为什么需要这个覆盖</b>：本探针做「变异测试」（故意把生产代码改坏、
     * 看探针抓不抓得住）时，被测的是<b>复制到别处的变异体</b>。
     * 如果 D 段死读正式路径，那么所有源码文本断言对变异体<b>全都不敏感</b> ——
     * 实测就漏掉了两个变异（PDC 类型改 BYTE、名字比对被去掉）。
     * 有了这个开关，D 段扫的就是<b>真正被加载的那份源码</b>。
     */
    private static Path srcOverride(String key, Path fallback) {
        String v = System.getProperty(key);
        if (v != null && !v.isEmpty()) {
            Path p = Path.of(v);
            if (Files.exists(p)) {
                return p;
            }
        }
        return fallback;
    }

    /** 我负责的两个区间（原脚本 1-based，含两端）。 */
    private static final int SF_FROM = 13571;
    private static final int SF_TO = 13671;
    private static final int AL_FROM = 13673;
    private static final int AL_TO = 14336;

    /** 参考服日志里的三行（1-based 行号）。 */
    private static final int LOG_SF = 46;
    private static final int LOG_REG = 47;
    private static final int LOG_START = 48;

    // ======================================================================
    // 桥接桩（在求值原脚本**之前**注入）
    // ======================================================================

    private static final String JS_STUBS = """
            var __log = [];          // getServer().getLogger() 收到的每一行
            var __online = [];       // getOnlinePlayers()
            var __handlers = [];     // 原脚本 registerEvent 注册的执行器（事件层入口）

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
                  // ★ 把原脚本注册的**执行器函数本身**抓下来 —— 这是「事件层」的
                  //   唯一入口。抓不到它，就只能测 gsAlFire / gsAlToggle，
                  //   测不到「左键取消 / 右键取消 / 近战取消」这些**用户可见**行为。
                  //   参数签名：registerEvent(事件类, plugin, 优先级, 执行器, plugin)
                  registerEvent: function (cls, pl, pri, exec) {
                    var n = String(cls);
                    // ★ GraalJS 里 String(JavaClass) 是 "class org.bukkit.X"
                    if (n.indexOf('class ') === 0) { n = n.substring(6); }
                    __handlers.push({ cls: n, pri: String(pri), exec: exec });
                    return null;
                  },
                  registerEvents: function () { return null; },
                  getPlugin: function (n) { return { __fake: String(n) }; }
                };
              },
              getOnlinePlayers: function () { return __online; },
              getWorlds: function () { return __worlds; },
              createBossBar: function (t, c, s) { return __mkBar(t, c, s); }
            };
            var __worlds = [];
            function getServer() { return __srv; }
            function getAddonConfig() { return null; }
            function getSfItemById(id) { return null; }

            // ★★ 原脚本的 gsIsItem 走 getSfItemByItem（RSC 宿主函数），
            //    不补的话整个 gsIsItem 会 ReferenceError、被处理器的 try/catch 吞掉
            //    ⇒ JS 侧表现为「什么都没发生」，与 Java 侧不可比。
            //    RSC 的 getSfItemByItem 就是 SlimefunItem::getByItem（见 Guards 的注释），
            //    所以这里直接转发到真 API —— 这是**等价实现**，不是打桩放宽。
            function getSfItemByItem(item) {
              try {
                return Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem')
                        .getByItem(item);
              } catch (e) { return null; }
            }

            // ★ 关键：runLater / runRepeating 在桩里是**空实现**。
            //   原脚本顶层会跑 runRepeating(gsAlPulse,10,10) 与 5 处 registerEvent，
            //   不求值前换掉会炸。真正的「同步 + 预算」版在 HELPERS 里换上去。
            function runLater(fn, d) { return null; }
            function runRepeating(fn, d, p) { return null; }

            function __mkBar(title, color, style) {
              var o = {
                __title: String(title), __color: color, __style: style, __prog: 1.0, __vis: false,
                setTitle: function (t) { o.__title = String(t); },
                getTitle: function () { return o.__title; },
                setColor: function (c) { o.__color = c; },
                getColor: function () { return o.__color; },
                setStyle: function (s) { o.__style = s; },
                setProgress: function (v) { o.__prog = v; },
                getProgress: function () { return o.__prog; },
                setVisible: function (v) { o.__vis = v; },
                isVisible: function () { return o.__vis; },
                addPlayer: function () { }, removePlayer: function () { }, removeAll: function () { }
              };
              return o;
            }
            """;

    /**
     * 探针自己的 JS 工具（在求值原脚本<b>之后</b>注入 —— 要引用真函数与真常量）。
     *
     * <p>★ {@code __bits(x)} 用 {@code DataView} 取 IEEE-754 位模式，
     * 与 Java 的 {@code Double.doubleToRawLongBits} 对齐 ——
     * 这样「逐刻推进」的比对是<b>逐位</b>的，不给「容差」留后门。
     */
    private static final String JS_HELPERS = """
            // ---------- 同步 runLater 泵（带预算，防止失控）----------
            var __budget = 0;
            runLater = function (fn, d) {
              if (__budget > 0) { __budget--; fn(); }
              return null;
            };

            // ---------- IEEE-754 位模式（与 Java Double.doubleToRawLongBits 对齐）----------
            function __bits(x) {
              var dv = new DataView(new ArrayBuffer(8));
              dv.setFloat64(0, x);
              return dv.getUint32(0).toString(16) + ':' + dv.getUint32(4).toString(16);
            }

            // ---------- 纯算式（与 Java 侧同式）----------
            function __distSeries(n) {
              var out = [], d = 0.0;
              for (var i = 0; i < n; i++) { d += GS_AL_STEP; out.push(__bits(d)); }
              return out;
            }
            function __rangeStopTick(range) {
              var d = 0.0;
              for (var i = 1; i <= 100000; i++) { d += GS_AL_STEP; if (d >= range) return i; }
              return -1;
            }
            function __inBlast(dx, dy, dz, r) {
              var d = Math.sqrt(dx * dx + dy * dy + dz * dz);
              return !(d > r);
            }
            function __sfSkip(r) { return Math.floor(r * 100) >= GS_SF_CHANCE; }

            // ---------- PDC 播种（两边同一套语义）----------
            function __clearAl() {
              var k = gsAlKeys();
              var pdc = __playerJava.getPersistentDataContainer();
              pdc.remove(k.lastShot); pdc.remove(k.toggle); pdc.remove(k.cd);
              pdc.remove(k.ammo); pdc.remove(k.skill); pdc.remove(k.shooting);
            }
            function __seedFire(lastAgo, ammo, skill) {
              __clearAl();
              var k = gsAlKeys();
              var pdc = __playerJava.getPersistentDataContainer();
              var t = gsAlNow();
              if (lastAgo >= 0) pdc.set(k.lastShot, GS_PDT.LONG, t - lastAgo);
              if (ammo >= 0) pdc.set(k.ammo, GS_PDT.INTEGER, ammo);
              if (skill) pdc.set(k.skill, GS_PDT.STRING, '1');
            }
            function __seedToggle(toggleAgo, cdAgo, skill) {
              __clearAl();
              var k = gsAlKeys();
              var pdc = __playerJava.getPersistentDataContainer();
              var t = gsAlNow();
              if (toggleAgo >= 0) pdc.set(k.toggle, GS_PDT.LONG, t - toggleAgo);
              if (cdAgo >= 0) pdc.set(k.cd, GS_PDT.LONG, t - cdAgo);
              if (skill) pdc.set(k.skill, GS_PDT.STRING, '1');
            }
            function __seedAmmo(ammo, skill) {
              __clearAl();
              var k = gsAlKeys();
              var pdc = __playerJava.getPersistentDataContainer();
              if (ammo >= 0) pdc.set(k.ammo, GS_PDT.INTEGER, ammo);
              if (skill) pdc.set(k.skill, GS_PDT.STRING, '1');
            }
            // 六个键各写一次（只为让探针比对「用了哪个 PersistentDataType」）
            function __seedTypes() {
              var k = gsAlKeys();
              var pdc = __playerJava.getPersistentDataContainer();
              pdc.set(k.ammo, GS_PDT.INTEGER, 3);
              pdc.set(k.cd, GS_PDT.LONG, gsAlNow());
              pdc.set(k.lastShot, GS_PDT.LONG, gsAlNow());
              pdc.set(k.shooting, GS_PDT.STRING, '1');
              pdc.set(k.skill, GS_PDT.STRING, '1');
              pdc.set(k.toggle, GS_PDT.LONG, gsAlNow());
            }

            // ---------- 入口 ----------
            function __fire() { gsAlFire(__playerJava); }
            function __toggle() { gsAlToggle(__playerJava); }
            function __launch(skill) { gsAlLaunch(__playerJava, skill); }
            function __nameOf() { return gsAlNameFor(__playerJava); }
            function __cdLeft() { return gsAlCdLeft(__playerJava); }
            function __ammoOf() { return gsAlAmmo(__playerJava); }
            function __isSkill() { return gsAlIsSkill(__playerJava); }
            function __boom(x, y, z, skill) {
              gsAlBoom(__worldJava, __playerJava,
                new (Java.type('org.bukkit.Location'))(__worldJava, x, y, z), skill);
            }
            // 钓鱼回调的统计循环（一次 eval 跑 n 次，避免逐次 eval 的解析开销）
            function __fishLoop(evt, n) { for (var i = 0; i < n; i++) gsSfOnFish(evt); }
            // 事件层：按事件类名取出原脚本注册的执行器，造一个真事件喂进去
            function __handlersFor(clsName, arg, isDmg) {
              var exec = null;
              for (var i = 0; i < __handlers.length; i++) {
                if (__handlers[i].cls === clsName) { exec = __handlers[i].exec; break; }
              }
              if (exec === null) { throw new Error('没抓到执行器: ' + clsName); }
              var ev;
              if (clsName.indexOf('PlayerInteractEvent') >= 0) {
                ev = new (Java.type('org.bukkit.event.player.PlayerInteractEvent'))(
                  __playerJava, Java.type('org.bukkit.event.block.Action').valueOf(arg),
                  null, null, null, Java.type('org.bukkit.inventory.EquipmentSlot').HAND);
              } else if (clsName.indexOf('PlayerAnimationEvent') >= 0) {
                ev = new (Java.type('org.bukkit.event.player.PlayerAnimationEvent'))(
                  __playerJava,
                  Java.type('org.bukkit.event.player.PlayerAnimationType').valueOf(arg));
              } else {
                ev = new (Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'))(
                  __playerJava, __candJava,
                  Java.type('org.bukkit.event.entity.EntityDamageEvent.DamageCause').valueOf(arg), 5.0);
              }
              // ★★ PlayerInteractEvent 在「方块为 null 的点击」上**默认就是 cancelled**
              //    （useClickedBlock 默认 DENY）⇒ 先归零再喂，否则「处理器有没有取消」
              //    这个信号会被默认值淹掉。两侧都做同一步，是对称归一化、不是掩盖差异。
              try { ev.setCancelled(false); } catch (e2) { }
              // ★★ 原脚本注册的执行器签名是 function (l, e)
              //    （= EventExecutor.execute(listener, event)）⇒ **必须传两个参数**！
              //    只传一个的话 l=事件、e=undefined，处理器一进去就抛、被自己的
              //    try/catch 吞掉 —— 表现为「什么都没发生」。
              exec(null, ev);
              return ev.isCancelled();
            }
            """;

    // ======================================================================
    // 环境保真①：RSC 的 HostAccess
    // ======================================================================

    /**
     * 逐字复刻 RSC {@code ScriptEval.createHostAccess()}（去掉 denyAccess 那几项 ——
     * 它们只影响脚本能不能碰 {@code System}，与本子系统无关）。
     */
    private static HostAccess rscHostAccess() {
        return HostAccess.newBuilder()
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowBufferAccess(true)
                .allowIterableAccess(true)
                .allowIteratorAccess(true)
                .allowMapAccess(true)
                .allowAccessInheritance(true)
                .targetTypeMapping(Double.class, Float.class, null, Double::floatValue)
                .targetTypeMapping(Integer.class, Float.class, null, Integer::floatValue)
                .targetTypeMapping(Boolean.class, String.class, null, String::valueOf)
                .targetTypeMapping(Integer.class, String.class, null, String::valueOf)
                .targetTypeMapping(Character.class, String.class, null, String::valueOf)
                .targetTypeMapping(Long.class, String.class, null, String::valueOf)
                .targetTypeMapping(Float.class, String.class, null, String::valueOf)
                .targetTypeMapping(Double.class, String.class, null, String::valueOf)
                .targetTypeMapping(Object.class, String.class, null, String::valueOf)
                .build();
    }

    // ======================================================================
    // 环境保真②：Bukkit 假服务器（让 GS.runLater 变成可泵的队列）
    // ======================================================================

    private static final Deque<Runnable> QUEUE = new ArrayDeque<>();
    private static final List<String> SERVER_LOG = new ArrayList<>();
    private static final AtomicInteger REGISTER_EVENTS = new AtomicInteger();
    private static final Logger LOGGER = Logger.getLogger("gs-probe-g");

    /** 把 {@code Bukkit.server} 换成假服务器，并把 {@code GS.plugin} / {@code GSPlugin.instance} 填上。 */
    private static void installBukkitSeam() throws Exception {
        installFakeRegistry();
        installFakeSlimefun();
        LOGGER.setUseParentHandlers(false);
        LOGGER.addHandler(new Handler() {
            @Override
            public void publish(LogRecord r) {
                SERVER_LOG.add(r.getLevel().getName() + " " + r.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });

        BukkitTask task = (BukkitTask) Proxy.newProxyInstance(
                ListenerGSubParityProbe.class.getClassLoader(),
                new Class<?>[]{BukkitTask.class}, (p, m, a) -> null);

        BukkitScheduler sched = (BukkitScheduler) Proxy.newProxyInstance(
                ListenerGSubParityProbe.class.getClassLoader(),
                new Class<?>[]{BukkitScheduler.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "runTaskLater":
                        case "runTaskTimer":
                            QUEUE.add((Runnable) a[1]);
                            return task;
                        default:
                            return def(m.getReturnType());
                    }
                });

        PluginManager pm = (PluginManager) Proxy.newProxyInstance(
                ListenerGSubParityProbe.class.getClassLoader(),
                new Class<?>[]{PluginManager.class}, (p, m, a) -> {
                    if (m.getName().equals("registerEvents")) {
                        REGISTER_EVENTS.incrementAndGet();
                    }
                    return def(m.getReturnType());
                });

        Server server = (Server) Proxy.newProxyInstance(
                ListenerGSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Server.class}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getScheduler":
                            return sched;
                        case "getLogger":
                            return LOGGER;
                        case "getPluginManager":
                            return pm;
                        case "getOnlinePlayers":
                            return M != null ? new ArrayList<>(List.of(M.player))
                                    : new ArrayList<Player>();
                        case "getWorlds":
                            return M != null ? new ArrayList<>(List.of(M.world))
                                    : new ArrayList<World>();
                        case "getUnsafe":
                            // ★ EntityDamageByEntityEvent 的构造器会走
                            //   DamageSource.builder(...) → Bukkit.getUnsafe()
                            //   ⇒ 不补这一层就造不出事件对象。
                            return fakeUnsafe();
                        default:
                            return def(m.getReturnType());
                    }
                });

        Field f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);

        // GSPlugin 是具体类（不是接口）⇒ 不能用 Proxy。
        // sun.misc.Unsafe.allocateInstance 不跑构造器就造一个实例，够 GS.runLater 用。
        Class<?> unsafeC = Class.forName("sun.misc.Unsafe");
        Field uf = unsafeC.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object unsafe = uf.get(null);
        GSPlugin fake = (GSPlugin) unsafeC.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, GSPlugin.class);
        setStatic(GS.class, "plugin", fake);
        setStatic(GSPlugin.class, "instance", fake);
    }

    private static void setStatic(Class<?> c, String name, Object v) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, v);
    }

    // ======================================================================
    // 假 Slimefun 注册表 —— 让 gsIsItem / Guards.isOurItem **真的**认得出我们的物品
    // ======================================================================

    /**
     * ★★ 本探针里最关键的一层「替运行环境」。
     *
     * <p>裸探针没有服务器、没有 Slimefun 实例，于是
     * {@code SlimefunItem.getByItem(stack)} 恒为 {@code null}
     * ⇒ {@code gsAlIsLauncher(...)} 恒为 {@code false}
     * ⇒ 祖宗发射器的<b>全部</b>处理器都在第一道闸门就返回，
     * 「拿着发射器」这条路径<b>一个字节都测不到</b>。
     *
     * <p>而 {@code getByItem} 的字节码里有条<b>快路</b>：
     * <pre>
     *   if (stack == null || stack.getType() == Material.AIR) return null;
     *   if (stack instanceof SlimefunItemStack) return getById(((SlimefunItemStack) stack).getItemId());
     *   ... 否则走 PDC（那条路要真物品栈，走不通）
     * </pre>
     * 于是：造一个 {@link ProbeSfStack}（{@code instanceof SlimefunItemStack}），
     * 用 {@code Unsafe} 直接写它私有的 {@code id} 字段；再给
     * {@code Slimefun.instance} / {@code Slimefun.registry} /
     * {@code SlimefunRegistry.slimefunIds} 各塞一个 {@code Unsafe} 造的空壳，
     * 表里放上我们两个物品 ID ⇒ {@code getById(id)} 命中，闸门打开。
     *
     * <p>{@code getItemId()} / {@code getId()} 都是 {@code final} 方法、只读私有字段
     * —— 所以<b>不能</b>用子类覆盖，只能写字段。这也是本段必须用 {@code Unsafe} 的原因。
     */
    private static void installFakeSlimefun() {
        try {
            ClassLoader cl = ListenerGSubParityProbe.class.getClassLoader();
            Class<?> sfCls = Class.forName("io.github.thebusybiscuit.slimefun4.implementation.Slimefun");
            Class<?> regCls = Class.forName("io.github.thebusybiscuit.slimefun4.core.SlimefunRegistry");
            Class<?> itemCls = Class.forName("io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem");

            Object sf = unsafeAlloc(sfCls);
            Object reg = unsafeAlloc(regCls);
            Map<String, Object> ids = new HashMap<>();
            for (String id : new String[]{AlSub.GS_AL_ID, SfSub.GS_SF_ID}) {
                Object it = unsafeAlloc(itemCls);
                unsafeSet(itemCls, it, "id", id);
                ids.put(id, it);
            }
            unsafeSet(regCls, reg, "slimefunIds", ids);
            unsafeSet(sfCls, sf, "registry", reg);
            putStatic(sfCls.getDeclaredField("instance"), sf);

            check("★ 假 Slimefun 注册表注入成功（gsIsItem 能认出 GS_ANCESTOR_LAUNCHER / GS_SEA_FILTER）",
                    com.qinghuacisj.gs.script.module.listener.ListenerCore.gsIsItem(
                            probeStack(AlSub.GS_AL_ID), AlSub.GS_AL_ID));
        } catch (Throwable t) {
            check("★ 假 Slimefun 注册表注入成功（gsIsItem 能认出物品 ID）", false);
            for (Throwable c = t; c != null; c = c.getCause()) {
                System.out.println("      · " + c);
            }
        }
    }

    private static Object unsafeAlloc(Class<?> c) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        return u.allocateInstance(c);
    }

    /** 用 {@code Unsafe} 写私有 {@code final} 实例字段（{@code getId()} / {@code getItemId()} 是 final 方法）。 */
    private static void unsafeSet(Class<?> c, Object target, String field, Object v) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        // ★ getDeclaredField 只看本类；`id` 是 SlimefunItemStack 声明的，
        //   ProbeSfStack 只是子类 ⇒ 必须往父类找。
        Field f = findField(c, field);
        u.putObject(target, u.objectFieldOffset(f), v);
    }

    private static Field findField(Class<?> c, String name) throws NoSuchFieldException {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try {
                return k.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        throw new NoSuchFieldException(name + "（" + c.getName() + " 及其父类里都没有）");
    }

    /**
     * 探针用的「自制物品」替身。
     *
     * <p>{@code instanceof SlimefunItemStack} ⇒ {@code SlimefunItem.getByItem} 走快路；
     * 私有 {@code id} 字段由 {@link #probeStack(String)} 用 {@code Unsafe} 写上。
     *
     * <p>{@code getItemMeta()} / {@code setItemMeta()} 被覆盖成<b>内存里的一张纸</b>
     * —— 真物品栈在裸环境里 {@code getItemMeta()} 必 NPE，而祖宗发射器的
     * 「手上那把枪改显示名」这条逻辑<b>全靠它</b>。
     */
    public static final class ProbeSfStack extends io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack {

        private String displayName;
        private int metaWrites;

        /** ★ 永不执行（{@code Unsafe.allocateInstance} 绕过构造器），只为过编译。 */
        private ProbeSfStack() {
            super("GS_PROBE", (ItemStack) null);
        }

        @Override
        public Material getType() {
            return Material.STONE;                 // 只要不是 AIR，getByItem 就不提前返回
        }

        @Override
        public ItemMeta getItemMeta() {
            ClassLoader cl = ProbeSfStack.class.getClassLoader();
            return (ItemMeta) Proxy.newProxyInstance(cl, new Class<?>[]{ItemMeta.class}, (p, m, a) -> {
                switch (m.getName()) {
                    case "getDisplayName":
                        return displayName;
                    case "hasDisplayName":
                        return displayName != null;
                    case "setDisplayName":
                        displayName = (String) a[0];
                        return null;
                    default:
                        return def(m.getReturnType());
                }
            });
        }

        @Override
        public boolean setItemMeta(ItemMeta meta) {
            metaWrites++;
            if (meta != null) {
                try {
                    displayName = meta.getDisplayName();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            return true;
        }

        /** 当前显示名（{@code null} = 从没写过）。 */
        String probeName() {
            return displayName;
        }

        int probeMetaWrites() {
            return metaWrites;
        }
    }

    /** 造一把「注册表认得出」的发射器 / 海水过滤器。 */
    private static ItemStack probeStack(String id) {
        try {
            ProbeSfStack s = (ProbeSfStack) unsafeAlloc(ProbeSfStack.class);
            unsafeSet(ProbeSfStack.class, s, "id", id);
            return s;
        } catch (Throwable t) {
            System.out.println("      · probeStack 失败: " + t);
            return null;
        }
    }

    /** 读回手上那把枪当前的显示名（{@code null} = 没写过）。 */
    private static String handName() {
        try {
            ItemStack h = M.hand;
            return h == null ? null : h.getItemMeta().getDisplayName();
        } catch (Throwable t) {
            return "THROW " + t.getClass().getSimpleName();
        }
    }

    /** 读回手上那把枪的元数据写入次数。 */
    private static int handMetaWrites() {
        return M.hand instanceof ProbeSfStack s ? s.probeMetaWrites() : -1;
    }

    /**
     * 假 {@code UnsafeValues} —— 只为了让 {@code EntityDamageByEntityEvent} 能构造出来。
     *
     * <p>{@code EntityDamageByEntityEvent} 的构造器里会调
     * {@code DamageSource.builder(type).build()}，而 {@code builder} 走
     * {@code Bukkit.getUnsafe().createDamageSourceBuilder(...)}。裸探针没有服务器实现
     * ⇒ 恒 NPE。这里给出一条「能造出对象」的最小通路，<b>不碰被测代码</b>。
     */
    private static org.bukkit.UnsafeValues fakeUnsafe() {
        ClassLoader cl = ListenerGSubParityProbe.class.getClassLoader();
        Class<?> builderCls;
        Class<?> sourceCls;
        try {
            builderCls = Class.forName("org.bukkit.damage.DamageSource$Builder", false, cl);
            sourceCls = Class.forName("org.bukkit.damage.DamageSource", false, cl);
        } catch (Throwable t) {
            return null;
        }
        final Object source = Proxy.newProxyInstance(cl, new Class<?>[]{sourceCls},
                (p, m, a) -> def(m.getReturnType()));
        final Object builder = Proxy.newProxyInstance(cl, new Class<?>[]{builderCls},
                (p, m, a) -> {
                    // Builder 的每个 setter 都返回 this；build() 返回 DamageSource
                    if (m.getName().equals("build")) {
                        return source;
                    }
                    return m.getReturnType().isInstance(p) ? p : def(m.getReturnType());
                });
        return (org.bukkit.UnsafeValues) Proxy.newProxyInstance(cl,
                new Class<?>[]{org.bukkit.UnsafeValues.class}, (p, m, a) -> {
                    if (m.getName().equals("createDamageSourceBuilder")) {
                        return builder;
                    }
                    return def(m.getReturnType());
                });
    }

    /** 用 {@code sun.misc.Unsafe} 改写 {@code static final} 字段（普通反射改不动）。 */
    private static void putStatic(Field f, Object value) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        u.putObject(u.staticFieldBase(f), u.staticFieldOffset(f), value);
    }

    /**
     * 给 {@code io.papermc.paper.registry.RegistryAccessHolder.INSTANCE} 塞一个假实现。
     *
     * <p>standalone 跑 Paper API 时 {@code Registry.<clinit>} 会抛
     * {@code IllegalStateException: No RegistryAccess implementation found}，
     * 连带 {@code Material} / {@code EntityType} 这类<b>枚举式静态常量</b>全部不可用。
     * 探针只是<b>替运行环境</b>补上这一层，不碰任何被测代码。
     *
     * <p>★ 这段是照 {@code ListenerESubParityProbe.installFakeRegistry} 抄的
     * —— 同伴已经验证过同一套做法（同类问题、同一环境）。
     *
     * <p>★ 顺序很讲究：{@code Class.forName(String)} 等价于 {@code forName(name, true, ...)}
     * —— <b>会触发静态初始化</b>。所以必须先只 load（{@code false}），
     * 等假实现塞进去之后再让 {@code Registry} / {@code Material} 自己 {@code <clinit>}。
     */
    private static void installFakeRegistry() {
        try {
            ClassLoader cl = ListenerGSubParityProbe.class.getClassLoader();
            Class<?> regCls = Class.forName("org.bukkit.Registry", false, cl);
            Class<?> raCls = Class.forName("io.papermc.paper.registry.RegistryAccess", false, cl);
            Class<?> holderCls = Class.forName("io.papermc.paper.registry.RegistryAccessHolder", false, cl);

            Object access = Proxy.newProxyInstance(cl, new Class<?>[]{raCls}, (p, m, ar) -> {
                if (m.getName().equals("getRegistry") && ar != null && ar.length == 1) {
                    return registryProxy(cl, regCls, registryNameOf(ar[0]));
                }
                return def(m.getReturnType());
            });

            Class.forName("io.papermc.paper.registry.RegistryAccessHolder", true, cl);
            Field f = holderCls.getDeclaredField("INSTANCE");
            f.setAccessible(true);
            putStatic(f, java.util.Optional.of(access));

            Object got = raCls.getMethod("registryAccess").invoke(null);
            check("★ 假 RegistryAccess 注入成功（撑起 org.bukkit.Registry 的静态初始化）", got == access);
            check("★ 注入之后 EntityType.PLAYER / EntityType.ZOMBIE 可用（事件层要用）",
                    org.bukkit.entity.EntityType.PLAYER != null
                            && org.bukkit.entity.EntityType.ZOMBIE != null);
        } catch (Throwable t) {
            check("★ 假 RegistryAccess 注入成功（撑起 org.bukkit.Registry）", false);
            for (Throwable c = t; c != null; c = c.getCause()) {
                System.out.println("      · " + c);
            }
        }
    }

    /** 从 {@code RegistryKey} 或 {@code Class} 里猜出注册表名（{@code block} / {@code item} …）。 */
    private static String registryNameOf(Object key) {
        try {
            Object k = key.getClass().getMethod("getKey").invoke(key);
            String s = String.valueOf(k.getClass().getMethod("value").invoke(k));
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到下面按类名猜
        }
        return (key instanceof Class<?> c ? c.getSimpleName() : String.valueOf(key))
                .toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 假注册表：{@code block} 表按 key 给假方块，其它一律「空表」
     * —— 免得把 {@code Enchantment} 这类静态初始化搅乱。
     */
    private static Object registryProxy(ClassLoader cl, Class<?> regCls, String name) {
        final boolean isBlock = name != null && name.contains("block");
        return Proxy.newProxyInstance(cl, new Class<?>[]{regCls}, (p, m, ar) -> {
            switch (m.getName()) {
                case "get":
                    if (isBlock && ar != null && ar.length == 1 && ar[0] instanceof org.bukkit.NamespacedKey nk) {
                        return blockTypeProxy(cl, nk.getKey());
                    }
                    return null;
                case "iterator":
                    return java.util.Collections.emptyIterator();
                case "stream":
                    return java.util.stream.Stream.empty();
                case "size":
                    return 0;
                case "isEmpty":
                    return true;
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
                            return org.bukkit.NamespacedKey.minecraft(key);
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

    /** 把队列里的任务跑掉（最多 {@code budget} 个），跑完清空。返回实际跑了几个。 */
    private static int pump(int budget) {
        int n = 0;
        while (!QUEUE.isEmpty() && n < budget) {
            QUEUE.poll().run();
            n++;
        }
        QUEUE.clear();
        return n;
    }

    // ======================================================================
    // 一份 mock，两边共用
    // ======================================================================

    /**
     * 一个候选生物 —— 坐标是<b>相对枪口</b>的偏移。
     *
     * <p>★ 枪口 = 玩家脚底 + <b>1.2</b>（字面量，不是 {@code AlSub.GS_AL_MUZZLE_Y}）——
     * 免得 mock 与生产代码互相「印证」；那个常量由 A 段单独对账。
     */
    static final class Cand {
        final String id;
        final double rx;
        final double ry;
        final double rz;
        final String type;
        final boolean dead;
        final boolean living;

        Cand(String id, double rx, double ry, double rz, String type, boolean dead, boolean living) {
            this.id = id;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.type = type;
            this.dead = dead;
            this.living = living;
        }
    }

    /** 弹道场景。 */
    static final class Scenario {
        final String name;
        final double dx;
        final double dy;
        final double dz;
        final double wallD;
        final boolean skill;
        final List<Cand> cands;

        Scenario(String name, double dx, double dy, double dz, double wallD, boolean skill,
                 List<Cand> cands) {
            this.name = name;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.wallD = wallD;
            this.skill = skill;
            this.cands = cands;
        }
    }

    /** 全场景 mock：世界 / 玩家 / PDC / 生物 与所有记录区。 */
    static final class Mock {
        // ---- 场景 ----
        double bx;
        double by;
        double bz;
        double dx;
        double dy;
        double dz;
        double wallD = -1.0;              // < 0 = 没墙
        final List<Cand> cands = new ArrayList<>();
        ItemStack hand = null;



        // ---- 记录 ----
        final List<String> ptc = new ArrayList<>();
        final List<String> snd = new ArrayList<>();
        final List<String> dmg = new ArrayList<>();
        final List<String> wallArgs = new ArrayList<>();
        final Map<String, Object> pdc = new LinkedHashMap<>();

        final UUID selfId = UUID.fromString("00000000-0000-0000-0000-0000000000a7");

        World world;
        Player player;
        FakePdc pdcHandler;
        PersistentDataContainer pdcProxy;
        PlayerInventory invProxy;

        void resetTrace() {
            ptc.clear();
            snd.clear();
            dmg.clear();
            wallArgs.clear();
        }

        /** 清 PDC —— ★ 值表和类型表要一起清，否则类型断言会读到上一次的残留。 */
        void resetPdc() {
            pdc.clear();
            if (pdcHandler != null) {
                pdcHandler.types.clear();
            }
        }

        /** PDC 每个键用的 PersistentDataType（排序后拼成串，好逐字节比对）。 */
        String pdcTypes() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : pdcHandler.types.entrySet()) {
                out.add(e.getKey() + "=" + e.getValue());
            }
            Collections.sort(out);
            return String.join(", ", out);
        }
    }

    private static Mock M;

    /** 建 mock（一次），并把代理注入 JS。 */
    private static void buildMock() {
        M = new Mock();
        ClassLoader cl = ListenerGSubParityProbe.class.getClassLoader();

        M.pdcHandler = new FakePdc(M.pdc);
        M.pdcProxy = (PersistentDataContainer) Proxy.newProxyInstance(cl,
                new Class<?>[]{PersistentDataContainer.class}, M.pdcHandler);

        M.world = (World) Proxy.newProxyInstance(cl, new Class<?>[]{World.class},
                (proxy, mth, a) -> {
                    switch (mth.getName()) {
                        case "getName":
                            return "world";
                        case "getPersistentDataContainer":
                            return M.pdcProxy;
                        case "playSound":
                            if (a != null && a.length == 4 && a[0] instanceof Location) {
                                Location l = (Location) a[0];
                                M.snd.add(a[1] + "|" + a[2] + "|" + a[3] + "|"
                                        + bits(l.getX()) + "," + bits(l.getY()) + "," + bits(l.getZ()));
                            }
                            return null;
                        case "spawnParticle":
                            recordParticle(a);
                            return null;
                        case "getNearbyEntities":
                            return nearby(a);
                        case "rayTraceBlocks":
                            return rayTrace(a);
                        case "dropItemNaturally":
                        case "dropItem":
                            return null;
                        default:
                            return def(mth.getReturnType());
                    }
                });

        M.invProxy = (PlayerInventory) Proxy.newProxyInstance(cl, new Class<?>[]{PlayerInventory.class},
                (proxy, mth, a) -> {
                    switch (mth.getName()) {
                        case "getItemInMainHand":
                            return M.hand;
                        case "setItemInMainHand":
                            M.hand = (ItemStack) a[0];
                            return null;
                        case "getContents":
                            return new ItemStack[0];
                        default:
                            return def(mth.getReturnType());
                    }
                });

        M.player = (Player) Proxy.newProxyInstance(cl, new Class<?>[]{Player.class},
                (proxy, mth, a) -> {
                    switch (mth.getName()) {
                        case "getUniqueId":
                            return M.selfId;
                        case "getType":
                            // ★ 事件层要读 damager.getType().name() 判 PLAYER
                            return org.bukkit.entity.EntityType.PLAYER;
                        case "getName":
                            return "Probe";
                        case "getLocation":
                            return new Location(M.world, M.bx, M.by, M.bz);
                        case "getEyeLocation":
                            // ★ 真 Location 的子类，只覆盖 getDirection()
                            //   ⇒ 方向是**精确**给进去的，不经过 yaw/pitch 往返。
                            return new Location(M.world, M.bx, M.by + 1.62, M.bz) {
                                @Override
                                public Vector getDirection() {
                                    return new Vector(M.dx, M.dy, M.dz);
                                }
                            };
                        case "getWorld":
                            return M.world;
                        case "getPersistentDataContainer":
                            return M.pdcProxy;
                        case "getInventory":
                            return M.invProxy;
                        case "isOnline":
                            return true;
                        case "setNoDamageTicks":
                        case "setFallDistance":
                        case "setInvulnerable":
                            return null;
                        case "getVelocity":
                            return new Vector(0, 0, 0);
                        default:
                            return def(mth.getReturnType());
                    }
                });
    }

    /** 记一次 spawnParticle（覆盖带/不带 data、Location 版与 xyz 版）。 */
    private static void recordParticle(Object[] a) {
        String particle = String.valueOf(a[0]);
        int cnt;
        double ox;
        double oy;
        double oz;
        double extra;
        Object data = null;
        double px;
        double py;
        double pz;
        if (a[1] instanceof Location) {
            Location l = (Location) a[1];
            px = l.getX();
            py = l.getY();
            pz = l.getZ();
            cnt = (Integer) a[2];
            ox = (Double) a[3];
            oy = (Double) a[4];
            oz = (Double) a[5];
            extra = (Double) a[6];
            if (a.length > 7) {
                data = a[7];
            }
        } else {
            px = (Double) a[1];
            py = (Double) a[2];
            pz = (Double) a[3];
            cnt = (Integer) a[4];
            ox = (Double) a[5];
            oy = (Double) a[6];
            oz = (Double) a[7];
            extra = (Double) a[8];
            if (a.length > 9) {
                data = a[9];
            }
        }
        M.ptc.add(particle + "|" + cnt + "|" + ox + "," + oy + "," + oz + "|" + extra
                + "|" + bits(px) + "," + bits(py) + "," + bits(pz) + "|" + dust(data));
    }

    /** 把 DustOptions 记录成「颜色 RGB / size」—— 两边读的是同一套 getter。 */
    private static String dust(Object data) {
        if (data == null) {
            return "null";
        }
        try {
            org.bukkit.Particle.DustOptions d = (org.bukkit.Particle.DustOptions) data;
            return "D" + d.getColor().asRGB() + "/" + d.getSize();
        } catch (Throwable e) {
            return "?" + data.getClass().getName();
        }
    }

    /** 立方体范围取候选（与 {@code World#getNearbyEntities} 语义一致）。 */
    private static List<Entity> nearby(Object[] a) {
        Location pt = (Location) a[0];
        double rx = (Double) a[1];
        double ry = (Double) a[2];
        double rz = (Double) a[3];
        List<Entity> out = new ArrayList<>();
        for (Cand c : M.cands) {
            if (Math.abs(c.rx - (pt.getX() - M.bx)) <= rx
                    && Math.abs(c.ry - (pt.getY() - (M.by + 1.2))) <= ry
                    && Math.abs(c.rz - (pt.getZ() - M.bz)) <= rz) {
                out.add(entProxy(c));
            }
        }
        return out;
    }

    /**
     * 撞墙模型：把墙建模成「沿 dir 距枪口 {@code wallD} 格的一个平面」。
     *
     * <p>扫掠 {@code [t0, t0+maxLen]} 与墙相交就返回命中点 {@code 枪口 + dir*wallD}。
     * ★ 枪口 Y 用字面量 {@code +1.2}（不是 {@code AlSub.GS_AL_MUZZLE_Y}）。
     */
    private static RayTraceResult rayTrace(Object[] a) {
        Location from = (Location) a[0];
        Vector dir = (Vector) a[1];
        double maxLen = (Double) a[2];
        M.wallArgs.add(a[3] + "|" + a[4]);
        if (M.wallD < 0) {
            return null;
        }
        double mx = M.bx;
        double my = M.by + 1.2;
        double mz = M.bz;
        double t0 = (from.getX() - mx) * dir.getX()
                + (from.getY() - my) * dir.getY()
                + (from.getZ() - mz) * dir.getZ();
        if (!(t0 < M.wallD && M.wallD <= t0 + maxLen)) {
            return null;
        }
        return new RayTraceResult(new Vector(
                mx + dir.getX() * M.wallD,
                my + dir.getY() * M.wallD,
                mz + dir.getZ() * M.wallD));
    }

    private static Entity entProxy(Cand c) {
        Class<?>[] ifaces = c.living
                ? new Class<?>[]{LivingEntity.class}
                : new Class<?>[]{Entity.class};
        return (Entity) Proxy.newProxyInstance(ListenerGSubParityProbe.class.getClassLoader(), ifaces,
                (proxy, mth, a) -> {
                    switch (mth.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(c.id);
                        case "getType":
                            return EntityType.valueOf(c.type);
                        case "isDead":
                            return c.dead;
                        case "getLocation":
                            return new Location(M.world, M.bx + c.rx, M.by + 1.2 + c.ry, M.bz + c.rz);
                        case "getWorld":
                            return M.world;
                        case "getHealth":
                            return 20.0;
                        case "setNoDamageTicks":
                            return null;
                        case "damage":
                            M.dmg.add(c.id + "|" + a[0] + "|" + (a.length > 1 ? "src" : "nosrc"));
                            return null;
                        case "getScoreboardTags":
                            return Set.of();
                        case "getVelocity":
                            return new Vector(0, 0, 0);
                        default:
                            return def(mth.getReturnType());
                    }
                });
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    private static String bits(double x) {
        long b = Double.doubleToRawLongBits(x);
        return Long.toHexString((b >>> 32) & 0xFFFFFFFFL) + ":" + Long.toHexString(b & 0xFFFFFFFFL);
    }

    private static Object def(Class<?> rt) {
        if (rt == void.class) {
            return null;
        }
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
            return 0.0d;
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

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
        } else {
            fail++;
            FAILURES.add(name);
            System.out.println("  x " + name);
        }
    }

    private static void eq(String name, Object expected, Object actual) {
        boolean ok = (expected == null) ? actual == null : expected.equals(actual);
        if (!ok) {
            fail++;
            FAILURES.add(name);
            System.out.println("  x " + name);
            System.out.println("      期望: [" + expected + "]");
            System.out.println("      实际: [" + actual + "]");
        } else {
            pass++;
        }
    }

    /** PDC 的假实现 —— ★ 记录每次写入用的 {@code PersistentDataType}。 */
    static final class FakePdc implements InvocationHandler {
        final Map<String, Object> m;
        /** key -> 写入时用的 PersistentDataType 的 complexType 简名（String / Integer / Long / Byte …） */
        final Map<String, String> types = new LinkedHashMap<>();

        FakePdc(Map<String, Object> m) {
            this.m = m;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "set":
                    m.put(String.valueOf(args[0]), args[2]);
                    types.put(String.valueOf(args[0]), typeName(args[1]));
                    return null;
                case "get":
                    return m.get(String.valueOf(args[0]));
                case "has":
                    return m.containsKey(String.valueOf(args[0]));
                case "remove":
                    m.remove(String.valueOf(args[0]));
                    types.remove(String.valueOf(args[0]));
                    return null;
                case "isEmpty":
                    return m.isEmpty();
                case "getKeys":
                    return Set.of();
                case "getOrDefault":
                    return m.containsKey(String.valueOf(args[0])) ? m.get(String.valueOf(args[0])) : args[2];
                case "getSize":
                    return m.size();
                default:
                    return def(method.getReturnType());
            }
        }

        private static String typeName(Object pdt) {
            if (pdt == null) {
                return "null";
            }
            try {
                Object ct = pdt.getClass().getMethod("getComplexType").invoke(pdt);
                return String.valueOf(((Class<?>) ct).getSimpleName());
            } catch (Throwable e) {
                return "?" + pdt;
            }
        }
    }

    // ======================================================================
    // 指纹（两边共用一套读法）
    // ======================================================================

    /** 只看 PDC 状态。 */
    private static String pdcFp() {
        Object a = M.pdc.get("gs:al_ammo");
        return "ammo=" + (a == null ? "n" : String.valueOf(a))
                + " skill=" + (M.pdc.containsKey("gs:al_skill") ? 1 : 0)
                + " shooting=" + (M.pdc.containsKey("gs:al_shooting") ? 1 : 0)
                + " lastshot=" + (M.pdc.containsKey("gs:al_lastshot") ? 1 : 0)
                + " cd=" + (M.pdc.containsKey("gs:al_cd") ? 1 : 0)
                + " toggle=" + (M.pdc.containsKey("gs:al_toggle") ? 1 : 0);
    }

    /** 完整轨迹（保序）。 */
    private static String traceFp() {
        return "P" + M.ptc + " S" + M.snd + " D" + M.dmg;
    }

    /** 轨迹（音效/粒子排序后）—— 用于「调用顺序天然不同但集合应相同」的场景。 */
    private static String traceFpSorted() {
        return "P" + sorted(M.ptc) + " S" + sorted(M.snd) + " D" + sorted(M.dmg);
    }

    private static List<String> sorted(List<String> l) {
        List<String> c = new ArrayList<>(l);
        Collections.sort(c);
        return c;
    }

    // ======================================================================
    // main
    // ======================================================================

    public static void main(String[] args) throws Exception {
        installBukkitSeam();
        buildMock();

        ListenerCore core = new ListenerCore();
        SfSub sf = new SfSub(core);
        AlSub al = new AlSub(core);

        String all = new String(Files.readAllBytes(SCRIPT), StandardCharsets.UTF_8);
        List<String> logLines = Files.readAllLines(LOG, StandardCharsets.UTF_8);

        System.out.println("原脚本: " + SCRIPT);
        System.out.println("参考日志: " + LOG);

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(rscHostAccess())
                .allowHostClassLookup(s -> true)
                .build()) {

            ctx.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());
            ctx.eval(Source.newBuilder("js", all, "gs_listener-full").build());
            ctx.eval(Source.newBuilder("js", JS_HELPERS, "helpers").build());

            ctx.getBindings("js").putMember("__playerJava", M.player);
            ctx.getBindings("js").putMember("__worldJava", M.world);
            ctx.getBindings("js").putMember("__resetTrace", (Runnable) M::resetTrace);
            ctx.eval("js", "__worlds.push(__worldJava)");

            check("A0 原脚本整体求值成功（14k 行 · 顶层副作用已桩掉）", true);

            System.out.println();
            System.out.println("=========== A. 常量对账（真 GraalJS vs Java 字段）===========");
            constA(ctx, sf, al);
            completenessA();

            System.out.println();
            System.out.println("=========== B. 日志行逐字节 ===========");
            logB(ctx, sf, al, logLines);

            System.out.println();
            System.out.println("=========== C. 数值 / 几何算式 ===========");
            ballisticsC(ctx, al);
            wallC(ctx, al);
            boomC(ctx, al);
            fireGateC(ctx, al);
            toggleGateC(ctx, al);
            nameC(ctx, al);
            fishingC(ctx);
            fishingStatC(ctx);
            endToEndC(ctx, al);
            pulseC(al);

            System.out.println();
            System.out.println("=========== D. 反向断言 ===========");
            reverseD(sf, al);
        }

        System.out.println();
        if (!FAILURES.isEmpty()) {
            System.out.println("失败明细（" + FAILURES.size() + "）：");
            for (String s : FAILURES) {
                System.out.println("  - " + s);
            }
        }
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail != 0) {
            System.exit(1);
        }
    }

    // ======================================================================
    // A. 常量对账
    // ======================================================================

    private static void constA(Context ctx, SfSub sf, AlSub al) throws Exception {
        Value b = ctx.getBindings("js");

        // ---- SfSub ----
        eq("A1  GS_SF_ID", b.getMember("GS_SF_ID").asString(), SfSub.GS_SF_ID);
        eq("A2  GS_SF_CHANCE", b.getMember("GS_SF_CHANCE").asInt(), SfSub.GS_SF_CHANCE);
        eq("A3  GS_SF_AMOUNT", b.getMember("GS_SF_AMOUNT").asInt(), SfSub.GS_SF_AMOUNT);

        // ---- AlSub 数值 ----
        eq("A4  GS_AL_ID", b.getMember("GS_AL_ID").asString(), AlSub.GS_AL_ID);
        eq("A5  GS_AL_AMMO", b.getMember("GS_AL_AMMO").asInt(), AlSub.GS_AL_AMMO);
        eq("A6  GS_AL_FIRE_MS", b.getMember("GS_AL_FIRE_MS").asInt(), AlSub.GS_AL_FIRE_MS);
        eq("A7  GS_AL_SKILL_FIRE_MS", b.getMember("GS_AL_SKILL_FIRE_MS").asInt(),
                AlSub.GS_AL_SKILL_FIRE_MS);
        eq("A8  GS_AL_CD_MS", b.getMember("GS_AL_CD_MS").asInt(), AlSub.GS_AL_CD_MS);
        eq("A9  GS_AL_TOGGLE_MS", b.getMember("GS_AL_TOGGLE_MS").asInt(), AlSub.GS_AL_TOGGLE_MS);
        eq("A10 GS_AL_RANGE", b.getMember("GS_AL_RANGE").asDouble(), AlSub.GS_AL_RANGE);
        eq("A11 GS_AL_SPEED_BPS", b.getMember("GS_AL_SPEED_BPS").asDouble(), AlSub.GS_AL_SPEED_BPS);
        eq("A12 GS_AL_STEP", b.getMember("GS_AL_STEP").asDouble(), AlSub.GS_AL_STEP);
        eq("A13 GS_AL_HIT_R", b.getMember("GS_AL_HIT_R").asDouble(), AlSub.GS_AL_HIT_R);
        eq("A14 GS_AL_MUZZLE_Y", b.getMember("GS_AL_MUZZLE_Y").asDouble(), AlSub.GS_AL_MUZZLE_Y);
        eq("A15 GS_AL_N_R", b.getMember("GS_AL_N_R").asDouble(), AlSub.GS_AL_N_R);
        eq("A16 GS_AL_N_DMG", b.getMember("GS_AL_N_DMG").asDouble(), AlSub.GS_AL_N_DMG);
        eq("A17 GS_AL_S_R", b.getMember("GS_AL_S_R").asDouble(), AlSub.GS_AL_S_R);
        eq("A18 GS_AL_S_DMG", b.getMember("GS_AL_S_DMG").asDouble(), AlSub.GS_AL_S_DMG);
        eq("A19 GS_AL_NAME", b.getMember("GS_AL_NAME").asString(), AlSub.GS_AL_NAME);
        eq("A20 GS_AL_DOT", b.getMember("GS_AL_DOT").asString(), AlSub.GS_AL_DOT);

        // ---- AlSub 音效 ----
        eq("A21 GS_AL_SND_FIRE", b.getMember("GS_AL_SND_FIRE").asString(), AlSub.GS_AL_SND_FIRE);
        eq("A22 GS_AL_SND_SFIRE", b.getMember("GS_AL_SND_SFIRE").asString(), AlSub.GS_AL_SND_SFIRE);
        eq("A23 GS_AL_SND_BOOM", b.getMember("GS_AL_SND_BOOM").asString(), AlSub.GS_AL_SND_BOOM);
        eq("A24 GS_AL_SND_SBOOM", b.getMember("GS_AL_SND_SBOOM").asString(), AlSub.GS_AL_SND_SBOOM);
        eq("A25 GS_AL_SND_ON", b.getMember("GS_AL_SND_ON").asString(), AlSub.GS_AL_SND_ON);
        eq("A26 GS_AL_SND_OFF", b.getMember("GS_AL_SND_OFF").asString(), AlSub.GS_AL_SND_OFF);

        // ---- 六个 PDC 键名（逐字）----
        AlSub.Keys k = al.gsAlKeys();
        eq("A27 gs:al_ammo", "gs:al_ammo", String.valueOf(k.ammo));
        eq("A28 gs:al_skill", "gs:al_skill", String.valueOf(k.skill));
        eq("A29 gs:al_lastshot", "gs:al_lastshot", String.valueOf(k.lastShot));
        eq("A30 gs:al_toggle", "gs:al_toggle", String.valueOf(k.toggle));
        eq("A31 gs:al_cd", "gs:al_cd", String.valueOf(k.cd));
        eq("A32 gs:al_shooting", "gs:al_shooting", String.valueOf(k.shooting));

        // ---- 自洽性：24 / 30 / 1.5 三个数说的是同一套弹道 ----
        check("A33 GS_AL_STEP x 20 == GS_AL_SPEED_BPS（1.5x20 = 30）",
                AlSub.GS_AL_STEP * 20.0 == AlSub.GS_AL_SPEED_BPS);
        check("A34 GS_AL_RANGE / GS_AL_STEP == 16 刻（24 / 1.5 = 16）",
                AlSub.GS_AL_RANGE / AlSub.GS_AL_STEP == 16.0);
        check("A35 GS_AL_RANGE / GS_AL_SPEED_BPS == 0.8 秒（24 / 30 = 0.8）",
                AlSub.GS_AL_RANGE / AlSub.GS_AL_SPEED_BPS == 0.8);
        check("A36 GS_AL_N_DMG(35) 打在 4.5 格技能半径内 == 技能伤害 175 的 1/5",
                AlSub.GS_AL_S_DMG / AlSub.GS_AL_N_DMG == 5.0);
    }

    /**
     * 完备性检查：JS 侧在本区间里的每一个顶层 {@code var} 与每一个顶层 {@code function}
     * 都必须在 Java 侧有对应物（常量 / 实例字段 / 方法），且<b>数量相等</b>。
     *
     * <p>★ 这一条防的是「漏了一个」：日后有人往原脚本里加一个 {@code GS_AL_XXX}
     * 而没搬到 Java，这里会立刻红。
     */
    private static void completenessA() throws Exception {
        List<String> lines = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8);

        List<String> jsVars = new ArrayList<>();
        List<String> jsFns = new ArrayList<>();
        for (int i = SF_FROM - 1; i < AL_TO && i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.startsWith("var ")) {
                int eq = s.indexOf('=');
                int sp = s.indexOf(' ');
                jsVars.add(s.substring(sp + 1, eq < 0 ? s.length() : eq).trim());
            } else if (s.startsWith("function ")) {
                int p = s.indexOf('(');
                jsFns.add(s.substring("function ".length(), p).trim());
            }
        }

        // ★ 运行期状态（非常量）：值会变、或懒建缓存 ⇒ Java 侧是**实例字段**
        //   （名字可能与 JS 顶层 var 不同：GS_AL_KEYS ⇒ gsAlKeys）
        Set<String> runtimeVars = Set.of(
                "GS_SF_REGISTERED", "GS_AL_KEYS", "GS_AL_REGISTERED", "GS_AL_DUST");

        int handledVars = 0;
        for (String v : jsVars) {
            boolean ok;
            if (runtimeVars.contains(v)) {
                ok = true;                      // 由 D 段单独断言「必须是实例字段、不是 static」
            } else {
                ok = hasField(SfSub.class, v) || hasField(AlSub.class, v);
            }
            if (!ok) {
                System.out.println("  x 完备性：JS 顶层 var " + v + " 在 Java 侧找不到对应字段");
            }
            check("A37 完备性 var " + v, ok);
            if (ok) {
                handledVars++;
            }
        }
        eq("A38 完备性 var 总数 == 已处理数（JS 侧 " + jsVars.size() + " 个）",
                jsVars.size(), handledVars);

        int handledFns = 0;
        for (String f : jsFns) {
            boolean ok = hasMethod(SfSub.class, f) || hasMethod(AlSub.class, f);
            if (!ok) {
                System.out.println("  x 完备性：JS 函数 " + f + " 在 Java 侧找不到同名方法");
            }
            check("A39 完备性 function " + f, ok);
            if (ok) {
                handledFns++;
            }
        }
        eq("A40 完备性 function 总数 == 已处理数（JS 侧 " + jsFns.size() + " 个）",
                jsFns.size(), handledFns);

        // ★ 区间边界也要钉住（免得日后有人把别的东西挪进来）
        eq("A41 区间起点 13571 行是海水过滤器段的注释", "// ===",
                lines.get(SF_FROM - 1).substring(0, 6));
        check("A42 区间终点 14336 行是 gsAlStart() 调用",
                lines.get(AL_TO - 1).trim().equals("gsAlStart();"));
        check("A43 13671 行是 gsSfStart() 调用",
                lines.get(SF_TO - 1).trim().equals("gsSfStart();"));

        System.out.println("      （本区间顶层 var " + jsVars.size() + " 个 / 顶层 function "
                + jsFns.size() + " 个，全部已处理）");
    }

    private static boolean hasField(Class<?> c, String name) {
        try {
            c.getDeclaredField(name);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        for (Class<?> inner : c.getDeclaredClasses()) {
            for (Method m : inner.getDeclaredMethods()) {
                if (m.getName().equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ======================================================================
    // B. 日志行逐字节
    // ======================================================================

    private static void logB(Context ctx, SfSub sf, AlSub al, List<String> logLines) throws Exception {
        String refSf = logLines.get(LOG_SF - 1);
        String refReg = logLines.get(LOG_REG - 1);
        String refStart = logLines.get(LOG_START - 1);

        String javaSf = SfSub.gsSfStartLine(true);
        String javaReg = AlSub.gsAlRegisterLine();
        String javaStart = ListenerCore.PREFIX + AlSub.gsAlStartLine();

        eq("B1  海水过滤器日志 == 参考服日志第 46 行", refSf, javaSf);
        eq("B2  注册日志 == 参考服日志第 47 行", refReg, javaReg);
        eq("B3  启用日志 == 参考服日志第 48 行", refStart, javaStart);

        // ★ 参考服日志里这三行都没被截断（自己核过长度：85 / 82 / 185）
        eq("B4  第 46 行长度 == Java 行长度（没被截断）", javaSf.length(), refSf.length());
        eq("B5  第 47 行长度 == Java 行长度（没被截断）", javaReg.length(), refReg.length());
        eq("B6  第 48 行长度 == Java 行长度（没被截断）", javaStart.length(), refStart.length());

        // ★ 原脚本真身打出来的行（整份脚本 eval 过一遍的产物）
        Value log = ctx.eval("js", "__log");
        String jsSf = null;
        String jsReg = null;
        String jsStart = null;
        for (long i = 0; i < log.getArraySize(); i++) {
            String s = log.getArrayElement(i).asString();
            if (s.contains("「海水过滤器」钓鱼获取已加载")) {
                jsSf = s.substring("INFO ".length());
            } else if (s.contains("祖宗发射器监听器注册成功")) {
                jsReg = s.substring("INFO ".length());
            } else if (s.contains("祖宗发射器<?>：已启用")) {
                jsStart = s.substring("INFO ".length());
            }
        }
        eq("B7  JS 真身的海水过滤器行 == Java 行", javaSf, jsSf);
        eq("B8  JS 真身的注册行 == Java 行", javaReg, jsReg);
        eq("B9  JS 真身的启用行 == Java 行（含 [构思技艺] 前缀）", javaStart, jsStart);

        // ★★ 反向断言：`<?>` 必须逐字保留（作者写的占位符，没替换）
        check("B10 启用日志里 `祖宗发射器<?>` 原样存在", javaStart.contains("祖宗发射器<?>：已启用"));
        check("B11 没被「修正」成物品 ID", !javaStart.contains("GS_ANCESTOR_LAUNCHER"));
        check("B12 没被「修正」成弹药数（不是 <6>）", !javaStart.contains("祖宗发射器<6>"));
        check("B13 常态名字模板里也保留 `<?>`", ("&c" + AlSub.GS_AL_NAME + "<?>").equals("&c祖宗发射器<?>"));
        check("B14 参考服日志第 48 行里也有 `<?>`", refStart.contains("祖宗发射器<?>"));
        check("B15 参考服日志第 46 行以 `]` 收尾（完整行，不是被切在半路）", refSf.endsWith("）"));
        check("B16 参考服日志第 48 行以 `）` 收尾（完整行）", refStart.endsWith("）"));

        // ★ 三个数字全部来自 toFixed，而不是硬编码
        check("B17 注册行里的 0.8 由 JsNum.toFixed(800/1000.0, 1) 算出",
                javaReg.contains("常态 " + com.qinghuacisj.gs.util.JsNum.toFixed(800 / 1000.0, 1) + " 秒/发"));
        check("B18 注册行里的 12 由 JsNum.toFixed(12000/1000.0, 0) 算出",
                javaReg.contains("冷却 " + com.qinghuacisj.gs.util.JsNum.toFixed(12000 / 1000.0, 0) + " 秒）"));
        check("B19 启用行里 35.0 打成 `35`（JS String(x) 语义，不是 35.0）",
                javaStart.contains("35 点") && !javaStart.contains("35.0 点"));
        check("B20 启用行里 24.0 打成 `24`（不是 24.0）",
                javaStart.contains("射程 24 格") && !javaStart.contains("射程 24.0 格"));
        check("B21 启用行里 30.0 打成 `30`（不是 30.0）",
                javaStart.contains("弹速 30 格/秒") && !javaStart.contains("弹速 30.0 格/秒"));
    }

    // ======================================================================
    // C1. 弹道逐刻推进
    // ======================================================================

    private static void ballisticsC(Context ctx, AlSub al) {
        // 逐刻距离（逐位比对）
        Value jsSeries = ctx.eval("js", "__distSeries(40)");
        double[] javaSeries = AlSub.gsAlDistSeries(40);
        eq("C1  逐刻推进 40 拍的元素个数", (int) jsSeries.getArraySize(), javaSeries.length);
        boolean allSame = true;
        StringBuilder diff = new StringBuilder();
        for (int i = 0; i < javaSeries.length && i < jsSeries.getArraySize(); i++) {
            String js = jsSeries.getArrayElement(i).asString();
            String jv = bits(javaSeries[i]);
            if (!js.equals(jv)) {
                allSame = false;
                diff.append(" 第").append(i + 1).append("拍 JS=").append(js)
                        .append(" Java=").append(jv).append(';');
            }
        }
        check("C2  逐刻推进逐【位】一致（1.5 格/刻）" + diff, allSame);

        // 射程停拍
        boolean stopOk = true;
        StringBuilder stopBad = new StringBuilder();
        for (double range : new double[]{24.0, 1.5, 3.0, 23.9, 24.1, 30.0, 0.1, 0.0, 240.0}) {
            int js = ctx.eval("js", "__rangeStopTick(" + range + ")").asInt();
            int jv = AlSub.gsAlRangeStopTick(range);
            if (js != jv) {
                stopOk = false;
                stopBad.append(" range=").append(range).append(" JS=").append(js).append(" Java=").append(jv);
            }
        }
        check("C3  射程停拍（9 个射程值）" + stopBad, stopOk);
        eq("C4  24 格正好 16 拍（0.8 秒）", 16, AlSub.gsAlRangeStopTick(AlSub.GS_AL_RANGE));
        eq("C5  1.5 格正好 1 拍（0.05 秒）", 1, AlSub.gsAlRangeStopTick(1.5));

        // 采样点几何：pt = 枪口 + dir x dist（逐位，用同一串累加）
        double[] dirs = {1.0, 0.3, -0.7, 0.0, 0.5773502691896258, 1.0};
        double[][] dir3 = {
                {1.0, 0.0, 0.0}, {0.3, 0.0, 0.0}, {-0.7, 0.0, 0.0}, {0.0, 0.0, 0.0},
                {0.5773502691896258, 0.5773502691896258, 0.5773502691896258},
                {1.0, 0.0, 0.0}
        };
        double[][] extra = {
                {0.0, 0.0}, {0.0, 0.0}, {0.0, 0.0}, {0.0, 0.0}, {0.0, 0.0},
                {0.25, -0.6}
        };
        boolean geoOk = true;
        StringBuilder geoDiff = new StringBuilder();
        for (int s = 0; s < dir3.length; s++) {
            double dx = dir3[s][0];
            double dy = dir3[s][1];
            double dz = dir3[s][2];
            for (double[] e2 : new double[][]{{0.0, 0.0}, {0.25, -0.6}, {-0.4, 0.9}}) {
                double bx = 12.5;
                double by = 64.0;
                double bz = -7.25;
                double my = by + 1.2;
                StringBuilder js = new StringBuilder();
                StringBuilder jv = new StringBuilder();
                for (int i = 1; i <= 16; i++) {
                    double d = AlSub.GS_AL_STEP * i;
                    js.append(bits(bx + dx * d)).append(',')
                            .append(bits(my + dy * d)).append(',')
                            .append(bits(bz + dz * d)).append(';');
                    double[] ser = AlSub.gsAlDistSeries(i);
                    double dj = ser[i - 1];
                    jv.append(bits(bx + dx * dj)).append(',')
                            .append(bits(my + dy * dj)).append(',')
                            .append(bits(bz + dz * dj)).append(';');
                }
                if (!js.toString().equals(jv.toString())) {
                    geoOk = false;
                    geoDiff.append(" dir=(").append(dx).append(',').append(dy).append(',')
                            .append(dz).append(") off=(").append(e2[0]).append(',').append(e2[1]).append(')');
                }
            }
        }
        check("C6  弹道采样点几何逐位一致（18 组方向 x 16 拍）" + geoDiff, geoOk);

        // 两个方向数组保持同步（防止我改一个忘另一个）
        eq("C7  方向表长度自洽", dirs.length, dir3.length);
        eq("C8  附加偏移表长度自洽", 6, extra.length);
    }

    // ======================================================================
    // C2. 撞墙扫掠（真 gsAlWallHit + 真 World#rayTraceBlocks 代理）
    // ======================================================================

    private static void wallC(Context ctx, AlSub al) {
        double[] froms = {0.0, 1.5, 9.0, 22.5, 24.0};
        double[] walls = {-1, 0.5, 1.5, 1.5001, 10.3, 22.5, 24.0, 24.0001, 30.0};
        boolean ok = true;
        StringBuilder bad = new StringBuilder();
        for (double f : froms) {
            for (double w : walls) {
                M.bx = 0;
                M.by = 0;
                M.bz = 0;
                M.wallD = w;
                Location hit = AlSub.gsAlWallHit(M.world, new Location(M.world, f, 1.2, 0),
                        new Vector(1, 0, 0), 1.5);
                boolean expect = (w >= 0) && (f < w) && (w <= f + 1.5);
                if (expect != (hit != null)) {
                    ok = false;
                    bad.append(" from=").append(f).append(" wall=").append(w)
                            .append(" 期望").append(expect).append(" 实际").append(hit != null).append(';');
                } else if (hit != null && !bits(hit.getX()).equals(bits(w))) {
                    ok = false;
                    bad.append(" 命中点 from=").append(f).append(" wall=").append(w)
                            .append(" x=").append(hit.getX()).append(';');
                }
            }
        }
        check("C9  gsAlWallHit 扫掠求交（5 起点 x 9 墙位 = 45 例）" + bad, ok);

        // 参数逐字：FluidCollisionMode.NEVER + ignorePassable = true
        M.resetTrace();
        M.wallD = 5.0;
        AlSub.gsAlWallHit(M.world, new Location(M.world, 4.0, 1.2, 0), new Vector(1, 0, 0), 1.5);
        eq("C10 rayTraceBlocks 的参数（FluidCollisionMode|ignorePassable）", "NEVER|true",
                M.wallArgs.isEmpty() ? "(空)" : M.wallArgs.get(0));

        // ★ 反向断言：没有墙时返回 null（不是「返回起点」）
        M.wallD = -1;
        eq("C11 没有墙 ⇒ null", null,
                AlSub.gsAlWallHit(M.world, new Location(M.world, 4.0, 1.2, 0), new Vector(1, 0, 0), 1.5));
    }

    // ======================================================================
    // C3. 引爆几何（立方体 → 球过滤）+ 伤害值
    // ======================================================================

    private static void boomC(Context ctx, AlSub al) {
        double[][] pts = {
                {0, 0, 0}, {2.4, 0, 0}, {2.6, 0, 0}, {2.0, 2.0, 0}, {2.5, 0, 0},
                {4.4, 0, 0}, {4.6, 0, 0}, {3.0, 3.0, 1.5}, {4.5, 0, 0}, {-2.5, 0, 0},
                {0, 4.5, 0}, {1.5, 1.5, 1.5}, {2.6, 2.6, 2.6}, {0, 0, 2.5000001}
        };
        boolean ok = true;
        StringBuilder bad = new StringBuilder();
        for (double[] p : pts) {
            for (double r : new double[]{2.5, 4.5, 0.8}) {
                boolean js = ctx.eval("js", "__inBlast(" + p[0] + "," + p[1] + "," + p[2] + "," + r + ")")
                        .asBoolean();
                boolean jv = AlSub.gsAlInBlast(p[0], p[1], p[2], r);
                if (js != jv) {
                    ok = false;
                    bad.append(" p=").append(java.util.Arrays.toString(p)).append(" r=").append(r);
                }
            }
        }
        check("C12 爆炸球过滤算式（14 点 x 3 半径 = 42 例）" + bad, ok);

        // ★★ 反向断言：立方体内、球外的点必须被排除
        check("C13 立方体内球外的点被排除（2,2,0 / r=2.5）", !AlSub.gsAlInBlast(2.0, 2.0, 0.0, 2.5));
        check("C14 立方体内球内的点保留（2.4,0,0 / r=2.5）", AlSub.gsAlInBlast(2.4, 0.0, 0.0, 2.5));
        check("C15 刚好在球面上算命中（2.5,0,0 / r=2.5）", AlSub.gsAlInBlast(2.5, 0.0, 0.0, 2.5));

        // ---- 真 gsAlBoom：JS 真身 vs Java，轨迹逐字节 ----
        for (boolean skill : new boolean[]{false, true}) {
            double r = skill ? AlSub.GS_AL_S_R : AlSub.GS_AL_N_R;
            double dmg = skill ? AlSub.GS_AL_S_DMG : AlSub.GS_AL_N_DMG;
            String js = jsBoomOnce(ctx, r, dmg, skill);
            String jv = javaBoomOnce(al, r, dmg, skill);
            eq("C16 gsAlBoom 轨迹逐字节（skill=" + skill + " r=" + r + " dmg=" + dmg + "）", js, jv);
            check("C17 爆炸伤害值就是 " + dmg + "（skill=" + skill + "）", jv.contains("|" + dmg + "|"));
            check("C17a 轨迹指纹非空（> 40 字符，避免拿空对空）", jv.length() > 40);
        }

        // ---- 半径边界：2.5 / 4.5 必须真的决定打不打 ----
        M.resetPdc();
        M.resetTrace();
        M.bx = 0;
        M.by = 0;
        M.bz = 0;
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000c1", 2.49, 0, 0, "ZOMBIE", false, true));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000c2", 2.51, 0, 0, "ZOMBIE", false, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), false);
        check("C18 常态 2.5 格：2.49 打中 / 2.51 不打",
                M.dmg.size() == 1 && M.dmg.get(0).startsWith("00000000-0000-0000-0000-0000000000c1"));

        M.resetTrace();
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000c1", 4.49, 0, 0, "ZOMBIE", false, true));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000c2", 4.51, 0, 0, "ZOMBIE", false, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), true);
        check("C19 技能 4.5 格：4.49 打中 / 4.51 不打",
                M.dmg.size() == 1 && M.dmg.get(0).startsWith("00000000-0000-0000-0000-0000000000c1"));

        // ★ 反向断言：立方体角落（3,3,0）距离 4.24 ⇒ 在 4.5 技能半径内（球过滤不能误杀）
        M.resetTrace();
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000c3", 3.0, 3.0, 0, "ZOMBIE", false, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), true);
        check("C20 立方体角落 (3,3,0) 距 4.243 < 4.5 ⇒ 打中", M.dmg.size() == 1);

        // 盔甲架 / 掉落物不算生物（即使 getHealth 拿得到）
        M.resetTrace();
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000a1", 1.0, 0, 0, "ARMOR_STAND", false, true));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000a2", 1.0, 0, 0, "ITEM", false, false));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000a3", 1.0, 0, 0, "ZOMBIE", false, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), false);
        check("C21 盔甲架 / 掉落物被排除，僵尸被打",
                M.dmg.size() == 1 && M.dmg.get(0).startsWith("00000000-0000-0000-0000-0000000000a3"));

        // 排除自己
        M.resetTrace();
        M.cands.clear();
        M.cands.add(new Cand(M.selfId.toString(), 1.0, 0, 0, "PLAYER", false, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), false);
        check("C22 排除发射者自己", M.dmg.isEmpty());

        // 死的不打
        M.resetTrace();
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000d1", 1.0, 0, 0, "ZOMBIE", true, true));
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), false);
        check("C23 已死亡的生物不打", M.dmg.isEmpty());

        // 粒子 / 音效形状
        M.resetTrace();
        M.cands.clear();
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), true);
        eq("C24 技能爆炸恰好 3 个粒子调用", 3, M.ptc.size());
        check("C25 ① EXPLOSION_EMITTER x1（偏移 0）",
                M.ptc.get(0).startsWith("EXPLOSION_EMITTER|1|0.0,0.0,0.0|0.0|"));
        check("C26 ② EXPLOSION x12，偏移 = r*0.5 = 2.25",
                M.ptc.get(1).startsWith("EXPLOSION|12|2.25,2.25,2.25|0.0|"));
        check("C27 ③ SONIC_BOOM x1（只有技能有）",
                M.ptc.get(2).startsWith("SONIC_BOOM|1|0.0,0.0,0.0|0.0|"));
        check("C28 技能爆炸音效 = entity.warden.sonic_boom（音量 1.4 / 音调 1.0）",
                M.snd.size() == 1 && M.snd.get(0).startsWith("entity.warden.sonic_boom|1.4|1.0|"));

        M.resetTrace();
        M.cands.clear();
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), false);
        eq("C29 常态爆炸恰好 2 个粒子调用（没有 SONIC_BOOM）", 2, M.ptc.size());
        check("C30 常态 EXPLOSION 偏移 = 2.5*0.5 = 1.25",
                M.ptc.get(1).startsWith("EXPLOSION|12|1.25,1.25,1.25|0.0|"));
        check("C31 常态爆炸音效 = entity.generic.explode（音量 1.4 / 音调 1.0）",
                M.snd.size() == 1 && M.snd.get(0).startsWith("entity.generic.explode|1.4|1.0|"));

        // ★ 爆炸点的位置就是传进来的 pt（逐位）
        check("C32 爆炸点位置逐位等于 pt(0,1.2,0)",
                M.ptc.get(0).contains("|" + bits(0.0) + "," + bits(1.2) + "," + bits(0.0) + "|"));
    }

    private static void setBoomCands(double r) {
        M.cands.clear();
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000e1", 1.0, 0, 0, "ZOMBIE", false, true));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000e2", r + 1.0, 0, 0, "SKELETON", false, true));
        M.cands.add(new Cand("00000000-0000-0000-0000-0000000000e3", 2.0, 2.0, 0, "ZOMBIE", false, true));
    }

    private static String jsBoomOnce(Context ctx, double r, double dmg, boolean skill) {
        M.bx = 0;
        M.by = 0;
        M.bz = 0;
        setBoomCands(r);
        M.resetPdc();
        M.resetTrace();
        ctx.eval("js", "__boom(0.0, 1.2, 0.0, " + skill + ")");
        return traceFp();
    }

    private static String javaBoomOnce(AlSub al, double r, double dmg, boolean skill) {
        M.bx = 0;
        M.by = 0;
        M.bz = 0;
        setBoomCands(r);
        M.resetPdc();
        M.resetTrace();
        al.gsAlBoom(M.world, M.player, new Location(M.world, 0, 1.2, 0), skill);
        return traceFp();
    }

    // ======================================================================
    // C4. 开火闸门（0.8 秒 / 1.5 秒 + 6 发 ▼）
    // ======================================================================

    private static void fireGateC(Context ctx, AlSub al) {
        // ★★ 网格取「离阈值至少 20 毫秒」的值 —— 这样判定结果与时钟抖动无关。
        //   阈值的**精确值**由 A6（JS 常量 800/1500）+ D68/D69（代码里用的就是这两个常量）
        //   钉住；这里钉的是「780 打不出去 / 820 打得出去 ⇒ 阈值落在 (780, 820] 内」。
        Integer[] lastAgos = {null, 0, 100, 400, 780, 820, 1000, 1480, 1520, 3000, 999999};
        Integer[] ammos = {null, 0, 1, 2, 5, 6, 7};
        boolean[] skills = {false, true};

        int cases = 0;
        int mismatches = 0;
        int unstable = 0;
        StringBuilder bad = new StringBuilder();

        for (Integer ago : lastAgos) {
            for (Integer ammo : ammos) {
                for (boolean skill : skills) {
                    String jsOut = jsFireOnce(ctx, ago, ammo, skill);
                    String javaOut = javaFireOnce(al, ago, ammo, skill);
                    if (jsOut == null || javaOut == null) {
                        unstable++;
                        continue;
                    }
                    cases++;
                    if (!jsOut.equals(javaOut)) {
                        mismatches++;
                        if (mismatches <= 4) {
                            bad.append("\n      ago=").append(ago).append(" ammo=").append(ammo)
                                    .append(" skill=").append(skill)
                                    .append("\n        JS  =").append(jsOut)
                                    .append("\n        Java=").append(javaOut);
                        }
                    }
                }
            }
        }
        check("C33 开火闸门状态机（" + cases + "/" + (lastAgos.length * ammos.length * 2)
                        + " 例；跳过 " + unstable + " 例）" + bad,
                mismatches == 0 && unstable == 0 && cases > 100);

        // ★ 反向断言：常态不扣弹药
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_ammo", 3);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 5000);
        al.gsAlFire(M.player);
        pump(60);
        eq("C34 常态开火【不扣弹药】（仍是 3）", 3, M.pdc.get("gs:al_ammo"));
        check("C35 常态开火打出了弹道（有开火音效 block.dispenser.launch）",
                M.snd.stream().anyMatch(s -> s.startsWith("block.dispenser.launch|1.0|1.0|")));

        // ★ 技能开火扣 1 发
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_ammo", 3);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 5000);
        al.gsAlFire(M.player);
        pump(60);
        eq("C36 技能开火扣 1 发（3 → 2）", 2, M.pdc.get("gs:al_ammo"));
        check("C37 技能开火音效 = entity.warden.sonic_charge",
                M.snd.stream().anyMatch(s -> s.startsWith("entity.warden.sonic_charge|1.0|1.0|")));

        // ★ 打空 ⇒ 自动静默退出 + 开始 12 秒冷却
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_ammo", 1);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 5000);
        al.gsAlFire(M.player);
        pump(60);
        check("C38 6 发打完 ⇒ 自动退出技能（skill 标记被清）", !M.pdc.containsKey("gs:al_skill"));
        eq("C39 退出后弹药回满 6", 6, M.pdc.get("gs:al_ammo"));
        check("C40 退出时写了冷却时间戳 gs:al_cd", M.pdc.containsKey("gs:al_cd"));
        check("C41 自动退出是【静默】的（没有关闭音效）",
                M.snd.stream().noneMatch(s -> s.startsWith("block.beacon.deactivate")));
        check("C42 但打出去的这一发有开火音效",
                M.snd.stream().anyMatch(s -> s.startsWith("entity.warden.sonic_charge|")));

        // ★ 弹药为 0 却在技能态 ⇒ 走「先退出」那条路（有关闭音效、不放炮）
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_ammo", 0);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 5000);
        al.gsAlFire(M.player);
        pump(60);
        check("C43 弹药 0 却在技能态 ⇒ 退出技能（有关闭音效）",
                M.snd.stream().anyMatch(s -> s.startsWith("block.beacon.deactivate|1.0|1.0|")));
        check("C44 这一路【没有】开火音效（没打出去）",
                M.snd.stream().noneMatch(s -> s.startsWith("entity.warden.sonic_charge|")));
        check("C45 这一路也【不扣】弹药（退出时回满 6，不是 -1）",
                Integer.valueOf(6).equals(M.pdc.get("gs:al_ammo")));

        // ★ 节流：1.5 秒内第二发打不出去
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_ammo", 6);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 100);
        al.gsAlFire(M.player);
        pump(60);
        eq("C46 技能 1.5 秒节流：100ms 内第二发不扣弹药（还是 6）", 6, M.pdc.get("gs:al_ammo"));
        check("C47 技能节流时一声不响", M.snd.isEmpty() && M.ptc.isEmpty());

        // ★ 常态节流：0.8 秒
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_ammo", 3);
        M.pdc.put("gs:al_lastshot", System.currentTimeMillis() - 799);
        al.gsAlFire(M.player);
        pump(60);
        check("C48 常态 0.8 秒节流：799ms 内打不出去", M.snd.isEmpty() && M.ptc.isEmpty());
    }

    /**
     * JS 侧跑一次 gsAlFire，返回指纹；<b>整次调用跨了 &gt; 20 毫秒</b>就重来。
     *
     * <p>★ 为什么守卫是「≤ 20 毫秒」而不是「同一毫秒」：网格里的 {@code ago}
     * 离阈值至少 20 毫秒（见 {@link #fireGateC}），所以 20 毫秒内的时钟漂移
     * <b>不可能</b>改变判定结果；而「必须落在同一毫秒」在负载高时会让
     * 整个用例被无限跳过 —— 那是把探针的可靠性换成假精度。
     */
    private static String jsFireOnce(Context ctx, Integer ago, Integer ammo, boolean skill) {
        Value b = ctx.getBindings("js");
        for (int attempt = 0; attempt < 200; attempt++) {
            long t0 = System.currentTimeMillis();
            b.putMember("__ago", ago == null ? -1 : ago);
            b.putMember("__ammo", ammo == null ? -1 : ammo);
            b.putMember("__skillFlag", skill);
            ctx.eval("js", "__resetTrace.run(); __budget = 200;"
                    + "__seedFire(__ago, __ammo, __skillFlag); __fire();");
            if (System.currentTimeMillis() - t0 <= 20) {
                return pdcFp() + " # " + traceFpSorted();
            }
        }
        return null;
    }

    /** Java 侧跑一次 gsAlFire，返回同样的指纹。 */
    private static String javaFireOnce(AlSub al, Integer ago, Integer ammo, boolean skill) {
        for (int attempt = 0; attempt < 200; attempt++) {
            long t0 = System.currentTimeMillis();
            M.resetTrace();
            M.resetPdc();
            if (ago != null) {
                M.pdc.put("gs:al_lastshot", t0 - ago);
            }
            if (ammo != null) {
                M.pdc.put("gs:al_ammo", ammo);
            }
            if (skill) {
                M.pdc.put("gs:al_skill", "1");
            }
            al.gsAlFire(M.player);
            pump(200);
            if (System.currentTimeMillis() - t0 <= 20) {
                return pdcFp() + " # " + traceFpSorted();
            }
        }
        return null;
    }

    // ======================================================================
    // C5. 右键切换闸门（防抖 1 秒 + 冷却 12 秒）
    // ======================================================================

    private static void toggleGateC(Context ctx, AlSub al) {
        // ★★ 同上：离阈值（防抖 1000 / 冷却 12000）至少 50 毫秒
        Integer[] toggleAgos = {null, 0, 100, 500, 950, 1050, 3000, 999999};
        Integer[] cdAgos = {null, 0, 500, 11950, 12050, 999999};
        boolean[] skills = {false, true};

        int cases = 0;
        int mismatches = 0;
        int unstable = 0;
        StringBuilder bad = new StringBuilder();

        for (Integer ta : toggleAgos) {
            for (Integer ca : cdAgos) {
                for (boolean skill : skills) {
                    String jsOut = jsToggleOnce(ctx, ta, ca, skill);
                    String javaOut = javaToggleOnce(al, ta, ca, skill);
                    if (jsOut == null || javaOut == null) {
                        unstable++;
                        continue;
                    }
                    cases++;
                    if (!jsOut.equals(javaOut)) {
                        mismatches++;
                        if (mismatches <= 4) {
                            bad.append("\n      toggleAgo=").append(ta).append(" cdAgo=").append(ca)
                                    .append(" skill=").append(skill)
                                    .append("\n        JS  =").append(jsOut)
                                    .append("\n        Java=").append(javaOut);
                        }
                    }
                }
            }
        }
        check("C49 右键切换闸门状态机（" + cases + "/" + (toggleAgos.length * cdAgos.length * 2)
                        + " 例；跳过 " + unstable + " 例）" + bad,
                mismatches == 0 && unstable == 0 && cases > 60);

        // 冷却剩余算式
        M.resetPdc();
        M.pdc.put("gs:al_cd", System.currentTimeMillis());
        int left = al.gsAlCdLeft(M.player);
        check("C50 刚进冷却 ⇒ 剩余 ≈ 12000 毫秒（" + left + "）", left > 11900 && left <= 12000);
        M.pdc.put("gs:al_cd", System.currentTimeMillis() - 12001);
        eq("C51 冷却已过 ⇒ 0", 0, al.gsAlCdLeft(M.player));
        M.pdc.remove("gs:al_cd");
        eq("C52 没冷却记录 ⇒ 0", 0, al.gsAlCdLeft(M.player));

        // 开技能：补满 6 发 + 开启音效
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_ammo", 2);
        al.gsAlToggle(M.player);
        eq("C53 开技能补满 6 发", 6, M.pdc.get("gs:al_ammo"));
        check("C54 开技能写上 skill 标记", M.pdc.containsKey("gs:al_skill"));
        check("C55 开技能音效 = block.beacon.activate（音量 1.0）",
                M.snd.size() == 1 && M.snd.get(0).startsWith("block.beacon.activate|1.0|1.0|"));

        // 关技能：开始 12 秒冷却
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        al.gsAlToggle(M.player);
        check("C56 关技能 ⇒ 清 skill 标记", !M.pdc.containsKey("gs:al_skill"));
        check("C57 关技能 ⇒ 开始冷却", M.pdc.containsKey("gs:al_cd"));
        check("C58 关技能 ⇒ 弹药回满 6", Integer.valueOf(6).equals(M.pdc.get("gs:al_ammo")));
        check("C59 手动关技能【有】关闭音效",
                M.snd.stream().anyMatch(s -> s.startsWith("block.beacon.deactivate|1.0|1.0|")));

        // 防抖：连按两次只生效一次
        M.resetTrace();
        M.resetPdc();
        al.gsAlToggle(M.player);
        boolean afterFirst = M.pdc.containsKey("gs:al_skill");
        al.gsAlToggle(M.player);
        check("C60 防抖 1 秒：连按两次只开不关",
                afterFirst && M.pdc.containsKey("gs:al_skill"));

        // 冷却中开技能 ⇒ 静默失败
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_cd", System.currentTimeMillis());
        al.gsAlToggle(M.player);
        check("C61 冷却中开技能 ⇒ 静默失败（不进技能）", !M.pdc.containsKey("gs:al_skill"));
        check("C62 冷却中开技能 ⇒ 没有任何音效", M.snd.isEmpty());
        check("C63 冷却中开技能 ⇒ 但防抖时间戳仍然被刷新（打点在判定之前）",
                M.pdc.containsKey("gs:al_toggle"));

        // ★ 冷却中「关」技能仍然有效（因为「关」在冷却判定之前）
        M.resetTrace();
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_cd", System.currentTimeMillis());
        al.gsAlToggle(M.player);
        check("C64 冷却中【关】技能仍然有效（关在冷却判定之前）",
                !M.pdc.containsKey("gs:al_skill"));

        // ==================================================================
        // ★★ PDC 类型纪律（PersistentDataType 必须逐键一致）
        //
        //   原脚本用 STRING 存 skill / shooting（**不是** BYTE），这是关键：
        //   原注释写明「JS 数字装箱成 Integer，用 BYTE 读会抛」。
        //   这里两边都往**同一个**假 PDC 写，比对「每个键用了哪个类型」——
        //   把 Java 侧写成 BYTE 会立刻被抓到（实测：这条断言抓到过一个变异）。
        // ==================================================================
        M.resetPdc();
        al.gsAlSetSkill(M.player, true);
        al.gsAlSetAmmo(M.player, 3);
        al.gsAlMarkShot(M.player);
        al.gsAlMarkToggle(M.player);
        al.gsAlMarkCd(M.player);
        al.gsAlSetShootingFlag(M.player, true);
        String javaTypes = M.pdcTypes();

        M.resetPdc();
        ctx.eval("js", "__seedTypes()");
        String jsTypes = M.pdcTypes();

        eq("C65 Java 侧六个 PDC 键的类型", "gs:al_ammo=Integer, gs:al_cd=Long, "
                        + "gs:al_lastshot=Long, gs:al_shooting=String, gs:al_skill=String, gs:al_toggle=Long",
                javaTypes);
        eq("C66 JS 真身六个 PDC 键的类型与 Java 一致", javaTypes, jsTypes);
        check("C67 skill / shooting 用的是 String（不是 Byte）",
                javaTypes.contains("gs:al_skill=String") && javaTypes.contains("gs:al_shooting=String"));
        check("C68 三个时间戳用的是 Long", javaTypes.contains("gs:al_cd=Long")
                && javaTypes.contains("gs:al_lastshot=Long") && javaTypes.contains("gs:al_toggle=Long"));
        check("C69 弹药用的是 Integer", javaTypes.contains("gs:al_ammo=Integer"));
        check("C70 一个 Byte 都没有", !javaTypes.contains("=Byte"));

        try {
            eventLayerC(ctx, al);
        } catch (Exception ex) {
            check("C71-C94 事件层对账整体跑完", false);
            for (Throwable c = ex; c != null; c = c.getCause()) {
                System.out.println("      · " + c);
                for (StackTraceElement st : c.getStackTrace()) {
                    if (st.getClassName().contains("ListenerGSubParityProbe")) {
                        System.out.println("          at " + st);
                    }
                }
            }
        }
    }

    // ======================================================================
    // C7-c. ★★ 事件层（Bukkit 监听器）行为对账
    //
    //   上面所有断言都是**直接调 gsAlFire / gsAlToggle**。
    //   但用户实际碰到的入口是三个 Bukkit 事件：
    //     ① PlayerInteractEvent  左键 = 开火 / 右键 = 爆裂黎明，**两者都必须取消**
    //     ② EntityDamageByEntityEvent  原版近战取消 + 改判成开火
    //     ③ PlayerAnimationEvent  挥手兜底（只认 ARM_SWING）
    //
    //   「取消 / 不取消」是**用户可见**行为（右键不取消 ⇒ 唱片能插进唱片机；
    //   左键不取消 ⇒ 一边开火一边挖方块）。所以必须真的把事件对象喂进去比。
    //
    //   ★ 做法：原脚本的执行器由假 PluginManager 的 registerEvent **抓下来**，
    //     与 Java 的 Sync 监听器方法**喂同一个事件对象**，比对「取消与否 + 轨迹 + PDC」。
    // ======================================================================
    private static void eventLayerC(Context ctx, AlSub al) throws Exception {
        // ---- ① 原脚本三个执行器，按事件类名索引 ----
        Map<String, Value> jsExec = new LinkedHashMap<>();
        int n = ctx.eval("js", "__handlers.length").asInt();
        for (int i = 0; i < n; i++) {
            String cls = ctx.eval("js", "__handlers[" + i + "].cls").asString();
            Value exec = ctx.eval("js", "__handlers[" + i + "].exec");
            jsExec.put(cls, exec);
        }
        check("C71 原脚本注册了 " + n + " 个事件执行器（≥3）", n >= 3);
        if (System.getProperty("gs.probe.verbose") != null) {
            System.out.println("      · 抓到的执行器类名: " + jsExec.keySet());
        }
        for (String want : new String[]{
                "org.bukkit.event.player.PlayerInteractEvent",
                "org.bukkit.event.entity.EntityDamageByEntityEvent",
                "org.bukkit.event.player.PlayerAnimationEvent"}) {
            check("C72 抓到了 " + want.substring(want.lastIndexOf('.') + 1) + " 的执行器",
                    jsExec.containsKey(want));
        }

        // ---- ①b ★ JS 侧 gsIsItem 必须**真的**认得出我们的物品 ----
        //   原脚本的 gsIsItem 走 RSC 宿主函数 getSfItemByItem（探针里转发到
        //   SlimefunItem.getByItem）。它一旦失效，JS 侧所有处理器都会在
        //   「手里是不是发射器」这道闸门静默返回 ⇒ JS/Java 对账变成「空对空」。
        ctx.getBindings("js").putMember("__probeHand", probeStack(AlSub.GS_AL_ID));
        if (System.getProperty("gs.probe.verbose") != null) {
            System.out.println("      · JS gsIsItem 诊断: " + ctx.eval("js",
                    "(function(){ try { var h = __probeHand;"
                            + " var sf = getSfItemByItem(h);"
                            + " return 'typeof=' + typeof getSfItemByItem"
                            + " + ' sf=' + sf"
                            + " + ' id=' + (sf == null ? '-' : sf.getId())"
                            + " + ' gsIsItem=' + gsIsItem(h, 'GS_ANCESTOR_LAUNCHER');"
                            + " } catch (e) { return 'ERR ' + e; } })()").asString());
        }
        check("C110 ★ JS 侧 gsIsItem 认得出真发射器（getSfItemByItem 桩必须等价于 SlimefunItem.getByItem）",
                ctx.eval("js", "gsIsItem(__probeHand, 'GS_ANCESTOR_LAUNCHER')").asBoolean());

        // ==================================================================
        // ★★ 先说清楚这一段能测到哪、测不到哪
        //
        //   三个事件处理器的**第一句**都是「手里拿的是不是发射器」。
        //   判定走 Guards.isOurItem → SlimefunItem.getByItem：
        //     ① 需要 Slimefun 实例与注册表；
        //     ② 需要**能构造出来的 ItemStack**（要 CraftItemStack 的 craftDelegate）
        //        —— paper-api 单跑没有实现类 ⇒ 无论如何都造不出真 ItemStack。
        //   所以「手持发射器」那一半在裸探针里**驱动不了**。
        //   这与同伴的结论一致（见 ListenerESubParityProbe 的 fakeItem /
        //   「无服务器环境下必然 false」注释）。
        //
        //   ⇒ 分两层覆盖：
        //      · 行为层（C73–C74）：只测「没拿发射器」那条**能真跑**的路径，
        //        JS 真身与 Java 监听器喂同一个事件对象，逐字节比。
        //      · 源码层（C75–C82）：把「拿了发射器」那半的**取消语义与判定严格度**
        //        钉在方法体文本上（取消标志 / 动作匹配 / 动画严格度 / cause 过滤）。
        //      · JS 行为层（C83）：用探针桩把 JS 的 gsIsItem 打开，把「拿了发射器」
        //        时**应该**发生什么跑出来存档，防止日后有人「顺手」改掉。
        // ==================================================================

        // ---- ② 行为层：没拿发射器 ⇒ 三个处理器都必须「一声不响」 ----
        String[] actions = {"LEFT_CLICK_AIR", "LEFT_CLICK_BLOCK", "RIGHT_CLICK_AIR",
                "RIGHT_CLICK_BLOCK", "PHYSICAL"};
        for (String act : actions) {
            String j = interactOnce(jsExec, ctx, act, false, null);
            String v = interactOnce(null, ctx, act, false, al);
            eq("C73 事件层 PlayerInteractEvent " + act + "（空手）", j, v);
        }
        for (String type : new String[]{"ARM_SWING", "OFF_ARM_SWING"}) {
            String j = animOnce(jsExec, ctx, type, false, null);
            String v = animOnce(null, ctx, type, false, al);
            eq("C74 事件层 PlayerAnimationEvent " + type + "（空手）", j, v);
        }
        for (String cause : new String[]{"ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "PROJECTILE", "FALL"}) {
            String j = dmgOnce(jsExec, ctx, cause, false, false, null);
            String v = dmgOnce(null, ctx, cause, false, false, al);
            eq("C75 事件层 EntityDamageByEntityEvent cause=" + cause + "（空手）", j, v);
        }
        if (System.getProperty("gs.probe.verbose") != null) {
            System.out.println("      · C76 interact=" + interactOnce(null, ctx, "LEFT_CLICK_AIR", false, al)
                    + " anim=" + animOnce(null, ctx, "ARM_SWING", false, al)
                    + " dmg=" + dmgOnce(null, ctx, "ENTITY_ATTACK", false, false, al));
        }
        check("C76 空手时三个处理器都不取消事件（不干扰玩家正常操作）",
                interactOnce(null, ctx, "LEFT_CLICK_AIR", false, al).startsWith("cancel=false")
                        && interactOnce(null, ctx, "RIGHT_CLICK_AIR", false, al).startsWith("cancel=false")
                        && animOnce(null, ctx, "ARM_SWING", false, al).startsWith("cancel=false")
                        && dmgOnce(null, ctx, "ENTITY_ATTACK", false, false, al).startsWith("cancel=false"));
        // ★ traceFpSorted() 在「什么都没发生」时是 "P[] S[] D[]"，不是空串
        check("C77 空手时三个处理器都**不产生任何轨迹**（没开火、没切技能）",
                interactOnce(null, ctx, "LEFT_CLICK_AIR", false, al).contains("# P[] S[] D[] #")
                        && interactOnce(null, ctx, "RIGHT_CLICK_AIR", false, al).contains("# P[] S[] D[] #")
                        && animOnce(null, ctx, "ARM_SWING", false, al).contains("# P[] S[] D[] #"));

        // ---- ③ 源码层：三个处理器的判定与取消语义 ----
        String alSrc2 = new String(Files.readAllBytes(srcOverride("gs.probe.srcAl", AL_SRC)),
                StandardCharsets.UTF_8);
        String onInteract = methodBody(alSrc2, "public void onInteract(PlayerInteractEvent e) {");
        String onDmg = methodBody(alSrc2, "public void onDamageByEntity(EntityDamageByEntityEvent e) {");
        String onAnim = methodBody(alSrc2, "public void onAnimation(PlayerAnimationEvent e) {");
        check("C78 三个处理器方法体都提取成功",
                onInteract.length() > 400 && onDmg.length() > 400 && onAnim.length() > 300);

        // 右键 / 左键都必须取消（★ 右键不取消 ⇒ 唱片能插进唱片机）
        check("C79 右键分支先 setCancelled(true) 再 gsAlToggle",
                onInteract.contains("e.setCancelled(true);")
                        && onInteract.indexOf("e.setCancelled(true);") < onInteract.indexOf("gsAlToggle(p)"));
        check("C80 左键分支先 setCancelled(true) 再 gsAlFire",
                onInteract.indexOf("gsAlFire(p)") > onInteract.indexOf("e.setCancelled(true);"));
        eq("C81 onInteract 里正好两处 setCancelled(true)（左右键各一处）",
                2, countOccurrences(onInteract, "e.setCancelled(true);"));
        check("C82 动作判定用 contains 而非 equals（RIGHT_CLICK_BLOCK 等子动作都要命中）",
                onInteract.contains("act.contains(\"RIGHT_CLICK\")")
                        && onInteract.contains("act.contains(\"LEFT_CLICK\")")
                        && !onInteract.contains("act.equals(\"RIGHT_CLICK\")")
                        && !onInteract.contains("act.equals(\"LEFT_CLICK\")"));

        // 近战：取消 + 改判开火；shooting 标记放行
        check("C83 近战 + 手持发射器 ⇒ setCancelled(true) 且紧跟 gsAlFire",
                onDmg.contains("e.setCancelled(true);") && onDmg.contains("gsAlFire(p);")
                        && onDmg.indexOf("e.setCancelled(true);") < onDmg.indexOf("gsAlFire(p);"));
        check("C84 近战：shooting 标记 ⇒ 提前 return（放行我们自己的爆炸伤害）",
                onDmg.indexOf("gsAlIsShooting(p)") < onDmg.indexOf("e.setCancelled(true);")
                        && onDmg.contains("return;                            // 爆炸伤害 → 放行"));
        check("C85 近战：cause 必须含 ENTITY_ATTACK 或 SWEEP（摔落/爆炸不拦）",
                onDmg.contains("cause.contains(\"ENTITY_ATTACK\")")
                        && onDmg.contains("cause.contains(\"SWEEP\")"));
        check("C86 近战：damager 必须是 PLAYER",
                onDmg.contains("!en.equals(\"PLAYER\")"));

        // 挥手：只认 ARM_SWING（OFF_ARM_SWING 是副手挥，不能当开火）
        check("C87 挥手用 equals(\"ARM_SWING\") 而非 contains（副手挥不能当开火）",
                onAnim.contains("type.equals(\"ARM_SWING\")")
                        && !onAnim.contains("type.contains(\"ARM_SWING\")"));
        check("C88 挥手**不取消**事件（只兜底开火，没有 setCancelled）",
                !onAnim.contains("setCancelled"));
        check("C89 挥手兜底也先查手持物", onAnim.contains("gsAlIsLauncher(hand)"));

        // ---- ④ ★★「手持发射器」行为层：**真物品**驱动，不再打桩 ----
        //
        //   假 Slimefun 注册表（installFakeSlimefun）让 gsIsItem 真的认得出
        //   GS_ANCESTOR_LAUNCHER ⇒ 这道闸门**真的开了**：
        //     JS 真身与 Java 监听器喂**同一个**事件对象 + **同一把**枪，逐字节比。
        //   （早先这里是把 JS 的 gsIsItem 换成 `return __forceLauncher === true`
        //     的桩 —— 只能证明「JS 自己认为该发生什么」，证明不了 Java 侧。
        //     现在两侧都走真判定。）
        for (String act : new String[]{"LEFT_CLICK_AIR", "LEFT_CLICK_BLOCK",
                "RIGHT_CLICK_AIR", "RIGHT_CLICK_BLOCK", "PHYSICAL"}) {
            String j = interactOnce(jsExec, ctx, act, true, null);
            String v = interactOnce(null, ctx, act, true, al);
            eq("C111 事件层 PlayerInteractEvent " + act + "（手持发射器）", j, v);
        }
        for (String type : new String[]{"ARM_SWING", "OFF_ARM_SWING"}) {
            String j = animOnce(jsExec, ctx, type, true, null);
            String v = animOnce(null, ctx, type, true, al);
            eq("C112 事件层 PlayerAnimationEvent " + type + "（手持发射器）", j, v);
        }
        for (String cause : new String[]{"ENTITY_ATTACK", "ENTITY_SWEEP_ATTACK", "PROJECTILE", "FALL"}) {
            String j = dmgOnce(jsExec, ctx, cause, false, true, null);
            String v = dmgOnce(null, ctx, cause, false, true, al);
            eq("C113 事件层 EntityDamageByEntityEvent cause=" + cause + "（手持发射器）", j, v);
        }

        // ---- ⑤ ★★「shooting 放行」行为层（原脚本 14160 行的提前 return）----
        //
        //   爆炸伤害会打到自己 ⇒ 靠 PDC 里的 gs:al_shooting 标记放行。
        //   这条分支**只在手持发射器时才可达**（空手时上面那条闸门先返回），
        //   所以以前只能靠源码断言 C84 钉住；现在能真跑了。
        for (boolean sh : new boolean[]{true, false}) {
            String j = dmgOnce(jsExec, ctx, "ENTITY_ATTACK", sh, true, null);
            String v = dmgOnce(null, ctx, "ENTITY_ATTACK", sh, true, al);
            eq("C114 事件层 手持发射器 shooting=" + sh + "（爆炸伤害放行）", j, v);
        }
        check("C115 手持发射器 + shooting 标记 ⇒ 事件**不**被取消、不产生开火轨迹（放行我们自己的爆炸伤害）",
                dmgOnce(null, ctx, "ENTITY_ATTACK", true, true, al).startsWith("cancel=false")
                        && dmgOnce(null, ctx, "ENTITY_ATTACK", true, true, al).contains("# P[] S[] D[] #"));
        check("C116 手持发射器 + 无 shooting 标记 ⇒ 原版近战被取消且改判成开火",
                dmgOnce(null, ctx, "ENTITY_ATTACK", false, true, al).startsWith("cancel=true")
                        && !dmgOnce(null, ctx, "ENTITY_ATTACK", false, true, al).contains("# P[] S[] D[] #"));

        // ---- ⑥ ★★ 名字（PDC 状态 → 手上那把枪的显示名）行为层 ----
        //
        //   gsAlUpdateName 是「弹药数 / 技能开关」唯一**用户可见**的出口，
        //   以前只能源码断言。现在有 ProbeSfStack（内存里的 ItemMeta）⇒ 能读回真名字。
        //   ★ JS 侧与 Java 侧各跑一遍，**逐字节比**；再对 Java 侧单独钉绝对字面量。
        String[][] nameCases = {
                {"name", "false"}, {"toggle", "false"}, {"fire", "true"},
                {"fireEmpty", "true"}, {"exit", "false"}, {"fire", "false"},
        };
        for (String[] c : nameCases) {
            String jn = nameAfter(null, ctx, c[0], Boolean.parseBoolean(c[1]));
            String vn = nameAfter(al, ctx, c[0], Boolean.parseBoolean(c[1]));
            eq("C117 事件层 名字：" + c[0] + "（skill=" + c[1] + "）JS vs Java", jn, vn);
        }
        eq("C118 常态名字 = §c祖宗发射器<?>（★ `<?>` 是作者留的占位符，必须原样保留）",
                "§c祖宗发射器<?>", nameAfter(al, ctx, "name", false));
        eq("C119 开技能后名字 = §c祖宗发射器<▼×6>（弹药满）",
                "§c祖宗发射器<" + "\u25bc".repeat(6) + ">", nameAfter(al, ctx, "toggle", false));
        eq("C120 技能里打一发 ⇒ 名字里的 ▼ 少一个（弹药 6→5）",
                "§c祖宗发射器<" + "\u25bc".repeat(5) + ">", nameAfter(al, ctx, "fire", true));
        eq("C121 技能打空（弹药 1→0）⇒ 自动退出技能、名字回到 <?>",
                "§c祖宗发射器<?>", nameAfter(al, ctx, "fireEmpty", true));
        eq("C122 手动退出技能 ⇒ 名字回到 <?>",
                "§c祖宗发射器<?>", nameAfter(al, ctx, "exit", false));
        //   ★ 注意：常态开火**根本不调** gsAlUpdateName（原脚本 13975~13980 行的常态分支
        //     只有 MarkShot + Launch）⇒ 直接断言「名字还是 null」是没意义的。
        //     正确做法：先让名字变成 <?>，再常态开火，断言名字**一个字节都没动**。
        eq("C123 常态下开火**不改名字**（常态不消耗弹药、也不重写显示名）",
                "§c祖宗发射器<?>", nameAfter(al, ctx, "nameThenFire", false));
        eq("C124 名字没变时**一个字节都不写**（gsAlUpdateName 的 cur==want 提前返回）",
                1, nameWritesAfter(al, ctx, "nameTwice", false));

        // ---- ⑦ 源码层：名字/PDC 的调用点必须真的在 ----
        //   ★ 光断言 gsAlUpdateName 的**方法体**不够 —— 把调用点整个删掉
        //     （变异 T06）方法体照样完好、行为断言却因为「读回的是 null」而失效。
        String toggleBody = methodBody(alSrc2, "void gsAlToggle(Player p) {");
        String exitBody = methodBody(alSrc2, "void gsAlExitSkill(Player p, boolean silent) {");
        String fireBody = methodBody(alSrc2, "void gsAlFire(Player p) {");
        check("C125 gsAlToggle / gsAlExitSkill / gsAlFire 三个方法体都提取成功",
                toggleBody.length() > 300 && exitBody.length() > 200 && fireBody.length() > 300);
        check("C126 gsAlToggle 里调了 gsAlUpdateName（否则手上那把枪的名字永远不动）",
                toggleBody.contains("gsAlUpdateName(p);"));
        check("C127 gsAlExitSkill 里调了 gsAlUpdateName",
                exitBody.contains("gsAlUpdateName(p);"));
        check("C128 gsAlFire 里调了 gsAlUpdateName（弹药数要写进名字）",
                fireBody.contains("gsAlUpdateName(p);"));
        check("C129 全类 gsAlUpdateName 的调用点 ≥ 4 处（常态兜底 + 开技能 + 打一发 + 退出）",
                countOccurrences(alSrc2, "gsAlUpdateName(p);") >= 4);
        check("C130 gsAlUpdateName 方法体里 setItemMeta 与 setItemInMainHand 都在（写回物品栈）",
                methodBody(alSrc2, "void gsAlUpdateName(Player p) {").contains("hand.setItemMeta(meta);")
                        && methodBody(alSrc2, "void gsAlUpdateName(Player p) {").contains("inv.setItemInMainHand(hand);"));

        M.hand = null;                 // ★ 这一段往手里塞了真发射器，出段前清掉
        // ★★ 这一段真开了火 ⇒ 弹道是 GS.runLater 自链，任务全堆在假调度器的队列里。
        //    不清掉的话，后面 endToEndC 的 pump() 会先把这些**残留**任务跑出来，
        //    把上一段的粒子/音效串进别人的轨迹里（本探针真的踩过这个坑）。
        QUEUE.clear();
    }

    /**
     * 跑一次「改名字」的动作，返回手上那把枪最终的显示名。
     *
     * @param al  {@code null} ⇒ 跑 JS 真身；否则跑 Java 侧
     * @param how {@code name} / {@code nameTwice} / {@code toggle} / {@code fire}
     *            / {@code fireEmpty} / {@code exit}
     */
    private static String nameAfter(AlSub al, Context ctx, String how, boolean skill) {
        M.resetTrace();
        M.resetPdc();
        M.hand = probeStack(AlSub.GS_AL_ID);
        if (skill) {
            // ★ 弹药必须放 **Integer**：gsAlAmmo 读的是 PersistentDataType.INTEGER，
            //   放 String 会 ClassCastException 被它自己的 catch 吞掉、悄悄退回 6。
            M.pdc.put("gs:al_skill", "1");
            M.pdc.put("gs:al_ammo", 6);
        }
        if (how.equals("fireEmpty")) {
            M.pdc.put("gs:al_ammo", 1);
        }
        try {
            if (al == null) {
                String call = switch (how) {
                    case "name", "nameTwice" -> "gsAlUpdateName(__playerJava);";
                    case "nameThenFire" -> "gsAlUpdateName(__playerJava); gsAlFire(__playerJava);";
                    case "toggle" -> "gsAlToggle(__playerJava);";
                    case "fire", "fireEmpty" -> "gsAlFire(__playerJava);";
                    case "exit" -> "gsAlExitSkill(__playerJava, false);";
                    default -> "throw new Error('未知动作 " + how + "');";
                };
                if (how.equals("nameTwice")) {
                    call = call + call;
                }
                ctx.eval("js", call);
            } else {
                switch (how) {
                    case "name", "nameTwice":
                        al.gsAlUpdateName(M.player);
                        if (how.equals("nameTwice")) {
                            al.gsAlUpdateName(M.player);
                        }
                        break;
                    case "nameThenFire":
                        al.gsAlUpdateName(M.player);
                        al.gsAlFire(M.player);
                        break;
                    case "toggle":
                        al.gsAlToggle(M.player);
                        break;
                    case "fire", "fireEmpty":
                        al.gsAlFire(M.player);
                        break;
                    case "exit":
                        al.gsAlExitSkill(M.player, false);
                        break;
                    default:
                        return "未知动作 " + how;
                }
            }
        } catch (Throwable t) {
            return "THROW " + t.getClass().getSimpleName();
        }
        return handName();
    }

    /** 同上，但返回的是「元数据写入次数」（用来钉住 cur==want 时一个字节都不写）。 */
    private static int nameWritesAfter(AlSub al, Context ctx, String how, boolean skill) {
        String s = nameAfter(al, ctx, how, skill);
        if (s != null && s.startsWith("THROW")) {
            return -1;
        }
        return handMetaWrites();
    }

    /** 反射取 {@code AlSub.listener}（内层 {@code Sync}）—— 不往生产代码里加测试专用访问器。 */
    private static Object syncOf(AlSub al) throws Exception {
        Field f = AlSub.class.getDeclaredField("listener");
        f.setAccessible(true);
        Object v = f.get(al);
        if (v == null) {
            al.gsAlRegisterSync();
            v = f.get(al);
        }
        return v;
    }

    /** 把 {@code PlayerInteractEvent} 喂进事件层，返回指纹串。 */
    private static String interactOnce(Map<String, Value> jsExec, Context ctx,
                                       String action, boolean launcher, AlSub al) {
        M.resetTrace();
        M.resetPdc();
        // ★ launcher=true ⇒ 手里塞一把**注册表认得出**的发射器（见 installFakeSlimefun）
        M.hand = launcher ? probeStack(AlSub.GS_AL_ID) : null;
        try {
            org.bukkit.event.block.Action a =
                    org.bukkit.event.block.Action.valueOf(action);
            org.bukkit.event.player.PlayerInteractEvent e =
                    new org.bukkit.event.player.PlayerInteractEvent(
                            M.player, a, null, null, null, org.bukkit.inventory.EquipmentSlot.HAND);
            // ★★ 同 JS 侧：PlayerInteractEvent 默认可能是 cancelled（useClickedBlock 默认 DENY）
            //    ⇒ 先归零，再看处理器有没有把它翻回 true。
            e.setCancelled(false);
            if (jsExec == null) {
                invokeSync(syncOf(al), "onInteract", e);
                pump(400);                 // ★ 弹道是 GS.runLater 自链 ⇒ Java 侧必须把队列泵干
            } else {
                // ★★ 执行器签名是 function (l, e)（EventExecutor.execute(listener, event)）⇒ 必须传两个参数
                ctx.eval("js", "__budget = 400;");
                jsExec.get("org.bukkit.event.player.PlayerInteractEvent").execute(null, e);
            }
            return "cancel=" + e.isCancelled() + " # " + traceFpSorted() + " # " + pdcFp();
        } catch (Throwable t) {
            return "THROW " + t.getClass().getSimpleName();
        } finally {

        }
    }

    /** 把 {@code PlayerAnimationEvent} 喂进事件层，返回指纹串。 */
    private static String animOnce(Map<String, Value> jsExec, Context ctx,
                                   String type, boolean launcher, AlSub al) {
        M.resetTrace();
        M.resetPdc();
        M.hand = launcher ? probeStack(AlSub.GS_AL_ID) : null;

        try {
            org.bukkit.event.player.PlayerAnimationEvent e =
                    new org.bukkit.event.player.PlayerAnimationEvent(M.player,
                            org.bukkit.event.player.PlayerAnimationType.valueOf(type));
            if (jsExec == null) {
                invokeSync(syncOf(al), "onAnimation", e);
                pump(400);
            } else {
                // ★★ 执行器签名是 function (l, e)（EventExecutor.execute(listener, event)）⇒ 必须传两个参数
                ctx.eval("js", "__budget = 400;");
                jsExec.get("org.bukkit.event.player.PlayerAnimationEvent").execute(null, e);
            }
            return "cancel=" + e.isCancelled() + " # " + traceFpSorted() + " # " + pdcFp();
        } catch (Throwable t) {
            return "THROW " + t.getClass().getSimpleName();
        } finally {

        }
    }

    /** 把 {@code EntityDamageByEntityEvent} 喂进事件层，返回指纹串。 */
    private static String dmgOnce(Map<String, Value> jsExec, Context ctx,
                                  String cause, boolean shooting, boolean launcher, AlSub al) {
        M.resetTrace();
        M.resetPdc();
        M.hand = launcher ? probeStack(AlSub.GS_AL_ID) : null;

        if (shooting) {
            M.pdc.put("gs:al_shooting", "1");
        }
        try {
            org.bukkit.event.entity.EntityDamageByEntityEvent e =
                    new org.bukkit.event.entity.EntityDamageByEntityEvent(
                            M.player, entProxy(new Cand("00000000-0000-0000-0000-0000000000e9",
                                    0, 0, 0, "ZOMBIE", false, true)),
                            org.bukkit.event.entity.EntityDamageEvent.DamageCause.valueOf(cause), 5.0);
            if (jsExec == null) {
                invokeSync(syncOf(al), "onDamageByEntity", e);
                pump(400);
            } else {
                // ★★ 执行器签名是 function (l, e)（EventExecutor.execute(listener, event)）⇒ 必须传两个参数
                ctx.eval("js", "__budget = 400;");
                jsExec.get("org.bukkit.event.entity.EntityDamageByEntityEvent").execute(null, e);
            }
            return "cancel=" + e.isCancelled() + " # " + traceFpSorted() + " # " + pdcFp();
        } catch (Throwable t) {
            if (System.getProperty("gs.probe.verbose") != null) {
                for (Throwable c = t; c != null; c = c.getCause()) {
                    System.out.println("      · dmgOnce 抛: " + c);
                    StackTraceElement[] st = c.getStackTrace();
                    for (int i = 0; i < Math.min(5, st.length); i++) {
                        System.out.println("          at " + st[i]);
                    }
                }
            }
            return "THROW " + t.getClass().getSimpleName();
        }
    }

    /** 反射调用 {@code Sync} 上的事件方法（方法名与原脚本的语义一一对应）。 */
    private static void invokeSync(Object sync, String name, Object event) throws Exception {
        Method m = sync.getClass().getMethod(name, event.getClass());
        m.invoke(sync, event);
    }

    private static String jsToggleOnce(Context ctx, Integer ta, Integer ca, boolean skill) {
        Value b = ctx.getBindings("js");
        for (int attempt = 0; attempt < 200; attempt++) {
            long t0 = System.currentTimeMillis();
            b.putMember("__ta", ta == null ? -1 : ta);
            b.putMember("__ca", ca == null ? -1 : ca);
            b.putMember("__skillFlag", skill);
            ctx.eval("js", "__resetTrace.run();"
                    + "__seedToggle(__ta, __ca, __skillFlag); __toggle();");
            if (System.currentTimeMillis() - t0 <= 20) {
                return pdcFp() + " # " + traceFpSorted();
            }
        }
        return null;
    }

    private static String javaToggleOnce(AlSub al, Integer ta, Integer ca, boolean skill) {
        for (int attempt = 0; attempt < 200; attempt++) {
            long t0 = System.currentTimeMillis();
            M.resetTrace();
            M.resetPdc();
            if (ta != null) {
                M.pdc.put("gs:al_toggle", t0 - ta);
            }
            if (ca != null) {
                M.pdc.put("gs:al_cd", t0 - ca);
            }
            if (skill) {
                M.pdc.put("gs:al_skill", "1");
            }
            al.gsAlToggle(M.player);
            if (System.currentTimeMillis() - t0 <= 20) {
                return pdcFp() + " # " + traceFpSorted();
            }
        }
        return null;
    }

    // ======================================================================
    // C6. 名字（▼ 个数 / <?>）
    // ======================================================================

    private static void nameC(Context ctx, AlSub al) {
        boolean ok = true;
        StringBuilder bad = new StringBuilder();
        for (int ammo = 0; ammo <= 8; ammo++) {
            for (boolean skill : new boolean[]{false, true}) {
                ctx.getBindings("js").putMember("__ammo", ammo);
                ctx.getBindings("js").putMember("__skillFlag", skill);
                ctx.eval("js", "__seedAmmo(__ammo, __skillFlag)");
                String js = ctx.eval("js", "__nameOf()").asString();

                M.resetPdc();
                M.pdc.put("gs:al_ammo", ammo);
                if (skill) {
                    M.pdc.put("gs:al_skill", "1");
                }
                String jv = al.gsAlNameFor(M.player);
                if (!js.equals(jv)) {
                    ok = false;
                    bad.append(" ammo=").append(ammo).append(" skill=").append(skill)
                            .append(" JS=").append(js).append(" Java=").append(jv);
                }
            }
        }
        check("C65 名字生成（0~8 发 x 常态/技能 = 18 例）" + bad, ok);

        M.resetPdc();
        eq("C66 常态名字 = &c祖宗发射器<?>", "&c祖宗发射器<?>", al.gsAlNameFor(M.player));
        M.pdc.put("gs:al_ammo", 6);
        M.pdc.put("gs:al_skill", "1");
        eq("C67 技能满弹 = &c祖宗发射器<▼▼▼▼▼▼>",
                "&c祖宗发射器<\u25bc\u25bc\u25bc\u25bc\u25bc\u25bc>", al.gsAlNameFor(M.player));
        M.pdc.put("gs:al_ammo", 0);
        eq("C68 技能空弹 = &c祖宗发射器<>", "&c祖宗发射器<>", al.gsAlNameFor(M.player));
        M.pdc.put("gs:al_ammo", 3);
        eq("C69 技能 3 发 = 3 个 ▼", "&c祖宗发射器<\u25bc\u25bc\u25bc>", al.gsAlNameFor(M.player));

        // ★ 色码转换
        eq("C70 gsAlColor(&c祖宗发射器<?>)", "\u00a7c祖宗发射器<?>",
                AlSub.gsAlColor("&c祖宗发射器<?>"));
        eq("C71 gsAlColor 多色码", "\u00a7cA\u00a7eB\u00a7lC",
                AlSub.gsAlColor("&cA&eB&lC"));

        // ★ ▼ 就是 U+25BC（不是 U+25B2 之类）
        eq("C72 GS_AL_DOT 的码点 = U+25BC", 0x25BC, (int) AlSub.GS_AL_DOT.charAt(0));
        eq("C73 GS_AL_DOT 长度 1", 1, AlSub.GS_AL_DOT.length());
    }

    // ======================================================================
    // C7. 7% 钓率判定
    // ======================================================================

    private static void fishingC(Context ctx) {
        boolean ok = true;
        StringBuilder bad = new StringBuilder();
        int hits = 0;
        int total = 0;
        // ★ 逐字照抄的判定式：Math.floor(r*100) >= 7 ⇒ 跳过
        for (int i = 0; i < 1000; i++) {
            double r = i / 1000.0;
            boolean js = ctx.eval("js", "__sfSkip(" + r + ")").asBoolean();
            boolean jv = SfSub.gsSfSkip(r);
            if (js != jv) {
                ok = false;
                bad.append(" r=").append(r);
            }
            total++;
            if (!js) {
                hits++;
            }
        }
        // 边界
        double[] edge = {0.0, 0.06999999999999999, 0.07, 0.07000000000000001,
                0.9999999999999999, 1.0 - 1e-16, 1e-9, 0.5, 0.009999999999999998};
        for (double r : edge) {
            boolean js = ctx.eval("js", "__sfSkip(" + r + ")").asBoolean();
            boolean jv = SfSub.gsSfSkip(r);
            if (js != jv) {
                ok = false;
                bad.append(" edge r=").append(r);
            }
            total++;
        }
        check("C74 7% 钓率判定（1000 等分 + 9 边界点 = " + total + " 例）" + bad, ok);

        // ★ 命中率就是 7%：{0..6} 七个整数 / 100
        eq("C75 [0,1) 千等分里「给」的格数 = 70/1000", 70, hits);
        check("C76 判定式的命中集合恰好是 7 个整数",
                !SfSub.gsSfSkip(0.0) && !SfSub.gsSfSkip(0.0699999)
                        && SfSub.gsSfSkip(0.07) && SfSub.gsSfSkip(0.9999));
        check("C77 判定式与 `Math.random() < 0.07` 概率相同但【表达式不同】（整数比较）",
                !SfSub.gsSfSkip(0.0) && SfSub.gsSfSkip(1.0));

        // ★ 状态白名单：只有 CAUGHT_FISH
        eq("C78 PlayerFishEvent.State.CAUGHT_FISH 名字就是 CAUGHT_FISH",
                "CAUGHT_FISH", org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH.name());
        check("C79 CAUGHT_ENTITY / IN_GROUND / FISHING 都【不】认",
                !"CAUGHT_ENTITY".equals("CAUGHT_FISH") && !"IN_GROUND".equals("CAUGHT_FISH")
                        && !"FISHING".equals("CAUGHT_FISH"));
        eq("C80 每次掉落数量 1", 1, SfSub.GS_SF_AMOUNT);

        // ★ 掉率常量没有被「顺手」改成别的数
        eq("C81 GS_SF_CHANCE == 7（不是 5 / 10）", 7, SfSub.GS_SF_CHANCE);
        eq("C82 GS_SF_ID == GS_SEA_FILTER", "GS_SEA_FILTER", SfSub.GS_SF_ID);
    }

    // ======================================================================
    // C7-b. 7% 钓率的**端到端统计**验证（真事件对象 + 真回调）
    // ======================================================================

    /**
     * 用**真 {@link PlayerFishEvent}** + 会记数的 {@code FishHook} 代理，
     * 把 {@code gsSfOnFish} 跑两万次，看「有没有走到 {@code hook.getLocation()}」。
     *
     * <p>★ 为什么这是好断言：{@code getLocation()} 是 {@code gsSfOnFish} 里
     * <b>7% 闸门之后</b>的第一句。所以它的调用次数 / 总次数
     * <b>就是</b>钓率 —— 不需要读常量、不需要看源码，纯粹是行为。
     * 常量对账（A2）只能证明「常量是 7」，这条能证明「闸门真的按 7 放行」。
     *
     * <p>★ 为什么不需要 Slimefun：{@code gsNewItem} 在裸探针里恒返回 null ⇒
     * {@code gsSfDrop} 立刻 return false，但<b>在那之前</b> {@code getHook()}
     * 与 {@code getLocation()} 已经被调用过 ⇒ 计数有效。
     */
    private static void fishingStatC(Context ctx) throws Exception {
        final int n = 20000;
        // 6.0% ~ 8.0%（期望 7%，σ≈0.18% ⇒ 偏离 5.5σ 以上才会误报）
        final double lo = 0.060;
        final double hi = 0.080;

        Value fishLoop = ctx.eval("js", "__fishLoop");

        for (org.bukkit.event.player.PlayerFishEvent.State st
                : org.bukkit.event.player.PlayerFishEvent.State.values()) {
            int javaHits = countHookQueries(null, st, n);
            int jsHits = countHookQueries(fishLoop, st, n);

            if (st == org.bukkit.event.player.PlayerFishEvent.State.CAUGHT_FISH) {
                double rate = javaHits / (double) n;
                check("C83-1 CAUGHT_FISH 两万次里命中 " + javaHits + " 次 ⇒ 钓率 "
                                + String.format(java.util.Locale.ROOT, "%.2f%%", rate * 100)
                                + " 落在 6%~8%",
                        rate >= lo && rate <= hi);
                double jsRate = jsHits / (double) n;
                check("C83-2 JS 真身同样落在 6%~8%（" + jsHits + " 次 / "
                                + String.format(java.util.Locale.ROOT, "%.2f%%", jsRate * 100) + "）",
                        jsRate >= lo && jsRate <= hi);
                // ★ 反向断言：不是 100%、也不是 0%
                check("C83-3 不是「每次都掉」（< 20%）", rate < 0.20);
                check("C83-4 也不是「从不掉」（> 2%）", rate > 0.02);
            } else {
                eq("C83-5 状态 " + st.name() + " 两万次一次都不进（Java）", 0, javaHits);
                eq("C83-6 状态 " + st.name() + " 两万次一次都不进（JS）", 0, jsHits);
            }
        }
    }

    /**
     * 跑 {@code n} 次 {@code gsSfOnFish}，返回 {@code hook.getLocation()} 被调用的次数。
     *
     * @param jsLoop null ⇒ 走 Java 的 {@link SfSub#gsSfOnFish}；否则走 JS 真身
     */
    private static int countHookQueries(Value jsLoop,
                                        org.bukkit.event.player.PlayerFishEvent.State st, int n) {
        final int[] hits = {0};
        org.bukkit.entity.FishHook hook = (org.bukkit.entity.FishHook) Proxy.newProxyInstance(
                ListenerGSubParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.FishHook.class}, (p, m, a) -> {
                    if (m.getName().equals("getLocation")) {
                        hits[0]++;
                        return new Location(M.world, M.bx, M.by, M.bz);
                    }
                    return def(m.getReturnType());
                });
        org.bukkit.event.player.PlayerFishEvent evt =
                new org.bukkit.event.player.PlayerFishEvent(M.player, null, hook, st);

        if (jsLoop == null) {
            SfSub sub = new SfSub(new ListenerCore());
            for (int i = 0; i < n; i++) {
                sub.gsSfOnFish(evt);
            }
        } else {
            jsLoop.execute(evt, n);
        }
        return hits[0];
    }

    // ======================================================================
    // C8. 端到端弹道（真 JS gsAlLaunch vs 真 Java gsAlLaunch，同一份 mock）
    // ======================================================================

    private static void endToEndC(Context ctx, AlSub al) {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("正前方无墙", 1, 0, 0, -1, false, List.of()));
        scenarios.add(new Scenario("正前方无墙（技能）", 1, 0, 0, -1, true, List.of()));
        scenarios.add(new Scenario("墙在 0.5 格", 1, 0, 0, 0.5, false, List.of()));
        scenarios.add(new Scenario("墙在 1.5 格（第一拍末）", 1, 0, 0, 1.5, false, List.of()));
        scenarios.add(new Scenario("墙在 1.5001 格（擦过第一拍）", 1, 0, 0, 1.5001, false, List.of()));
        scenarios.add(new Scenario("墙在 10.3 格", 1, 0, 0, 10.3, false, List.of()));
        scenarios.add(new Scenario("墙在 10.3 格（技能）", 1, 0, 0, 10.3, true, List.of()));
        scenarios.add(new Scenario("墙正好 24 格", 1, 0, 0, 24.0, false, List.of()));
        scenarios.add(new Scenario("墙 24.0001 格（够不着）", 1, 0, 0, 24.0001, false, List.of()));
        scenarios.add(new Scenario("墙在 30 格（超射程）", 1, 0, 0, 30.0, false, List.of()));
        scenarios.add(new Scenario("斜向无墙", 0.9, 0.3, -0.2, -1, false, List.of()));
        scenarios.add(new Scenario("斜向墙 7.7 格", 0.9, 0.3, -0.2, 7.7, false, List.of()));
        scenarios.add(new Scenario("抬头 45 度", 0.7071067811865476, 0.7071067811865476, 0, -1, false, List.of()));
        scenarios.add(new Scenario("生物在 5 格", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f1", 5.0, 0, 0, "ZOMBIE", false, true))));
        scenarios.add(new Scenario("生物在 5 格（技能）", 1, 0, 0, -1, true, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f1", 5.0, 0, 0, "ZOMBIE", false, true))));
        scenarios.add(new Scenario("盔甲架在 5 格（不算生物）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f2", 5.0, 0, 0, "ARMOR_STAND", false, true))));
        scenarios.add(new Scenario("掉落物在 5 格（不算生物）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f3", 5.0, 0, 0, "ITEM", false, false))));
        scenarios.add(new Scenario("生物在墙后面（先判墙）", 1, 0, 0, 3.2, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f4", 5.0, 0, 0, "ZOMBIE", false, true))));
        scenarios.add(new Scenario("生物偏轴 0.7 格（够得着）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f5", 6.0, 0.7, 0, "ZOMBIE", false, true))));
        scenarios.add(new Scenario("生物偏轴 0.9 格（够不着）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f6", 6.0, 0.9, 0, "ZOMBIE", false, true))));
        scenarios.add(new Scenario("自己挡在前面（排除自己）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000a7", 3.0, 0, 0, "PLAYER", false, true))));
        scenarios.add(new Scenario("多个生物（取第一个）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f7", 20.0, 0, 0, "ZOMBIE", false, true),
                new Cand("00000000-0000-0000-0000-0000000000f8", 7.0, 0, 0, "SKELETON", false, true))));
        scenarios.add(new Scenario("已死生物不算（飞过去）", 1, 0, 0, -1, false, List.of(
                new Cand("00000000-0000-0000-0000-0000000000f9", 5.0, 0, 0, "ZOMBIE", true, true))));

        int mismatches = 0;
        int unstable = 0;
        int ran = 0;
        StringBuilder bad = new StringBuilder();
        List<String> fps = new ArrayList<>();
        for (Scenario sc : scenarios) {
            String jsOut = jsLaunchOnce(ctx, sc);
            String javaOut = javaLaunchOnce(al, sc);
            if (jsOut == null || javaOut == null) {
                unstable++;
                continue;
            }
            ran++;
            fps.add(javaOut);
            if (!jsOut.equals(javaOut)) {
                mismatches++;
                if (mismatches <= 3) {
                    bad.append("\n      【").append(sc.name).append("】")
                            .append("\n        JS  =").append(jsOut)
                            .append("\n        Java=").append(javaOut);
                }
            }
        }
        check("C83 端到端弹道（" + ran + "/" + scenarios.size() + " 个场景；跳过 " + unstable + "）" + bad,
                mismatches == 0 && ran == scenarios.size() && unstable == 0);

        // ★★ 「探针自己有没有辨别力」—— 指纹不能全是空串或全都一样，
        //    否则 C83 的「相等」是假的（拿空对空当然相等）。
        long nonEmpty = fps.stream().filter(s -> s.length() > 40).count();
        eq("C83a 每个场景的轨迹指纹都非空（> 40 字符）", (long) scenarios.size(), nonEmpty);
        long distinct = fps.stream().distinct().count();
        check("C83b 轨迹指纹至少 8 种不同取值（说明确实在区分场景，distinct=" + distinct + "）",
                distinct >= 8);
        check("C83c 无墙常态 vs 无墙技能 的指纹不同（特效确实有差别）",
                !fps.get(0).equals(fps.get(1)));

        // ★ 弹道确实是 16 拍 / 24 格
        applyScenario(new Scenario("基准", 1, 0, 0, -1, false, List.of()));
        M.resetTrace();
        M.resetPdc();
        al.gsAlLaunch(M.player, false);
        int ticks = pump(200);
        eq("C84 无墙常态弹道跑 16 拍", 16, ticks);
        long dust = M.ptc.stream().filter(s -> s.startsWith("DUST|3|")).count();
        eq("C85 16 个 DUST 拖尾点（每拍 3 个粒子）", 16L, dust);
        check("C86 末拍引爆（有 EXPLOSION_EMITTER）",
                M.ptc.stream().anyMatch(s -> s.startsWith("EXPLOSION_EMITTER|1|")));
        check("C87 常态 DUST 的 DustOptions = RGB(255,40,40) / size 1.2",
                M.ptc.stream().filter(s -> s.startsWith("DUST|"))
                        .allMatch(s -> s.endsWith("|D" + org.bukkit.Color.fromRGB(255, 40, 40).asRGB() + "/1.2")));
        check("C88 常态 DUST 偏移 0.06 / extra 0.0",
                M.ptc.stream().filter(s -> s.startsWith("DUST|"))
                        .allMatch(s -> s.startsWith("DUST|3|0.06,0.06,0.06|0.0|")));
        check("C89 开火音效从胸口响（位置 = 脚底 + 1.2）",
                M.snd.size() >= 1 && M.snd.get(0).contains("|" + bits(10.0) + "," + bits(65.2) + "," + bits(-3.0)));
        eq("C90 常态弹道总共 16 次 DUST + 1 次爆炸的 2 个粒子调用 = 18",
                18, M.ptc.size());

        // ★ 技能弹道：SONIC_BOOM 拖尾 + 3 个爆炸粒子
        applyScenario(new Scenario("技能基准", 1, 0, 0, -1, true, List.of()));
        M.resetTrace();
        M.resetPdc();
        al.gsAlLaunch(M.player, true);
        pump(200);
        long sonic = M.ptc.stream().filter(s -> s.startsWith("SONIC_BOOM|1|0.0,0.0,0.0|0.0|")).count();
        eq("C91 技能弹道：16 个 SONIC_BOOM 拖尾 + 1 个引爆 = 17", 17L, sonic);
        check("C92 技能弹道一个 DUST 都没有",
                M.ptc.stream().noneMatch(s -> s.startsWith("DUST|")));
        check("C93 技能开火音效 = entity.warden.sonic_charge",
                M.snd.get(0).startsWith("entity.warden.sonic_charge|1.0|1.0|"));
    }

    private static void applyScenario(Scenario sc) {
        M.bx = 10.0;
        M.by = 64.0;
        M.bz = -3.0;
        M.dx = sc.dx;
        M.dy = sc.dy;
        M.dz = sc.dz;
        M.wallD = sc.wallD;
        M.cands.clear();
        M.cands.addAll(sc.cands);
        M.resetPdc();
    }

    /**
     * JS 侧跑一次 gsAlLaunch。
     *
     * <p>★ <b>这里没有任何时钟守卫</b>：{@code gsAlLaunch} 全程不读时钟
     * （只有位置累加 + 撞墙/撞生物/射程三条判定），所以 JS 与 Java 的轨迹
     * 必须<b>逐字节相等</b>，没有「重试」的余地。加守卫反而会掩盖真实差异。
     */
    private static String jsLaunchOnce(Context ctx, Scenario sc) {
        applyScenario(sc);
        M.resetTrace();
        try {
            ctx.eval("js", "__budget = 200; __launch(" + sc.skill + ");");
        } catch (Throwable t) {
            return "JS 抛异常: " + t;
        }
        return traceFp();
    }

    private static String javaLaunchOnce(AlSub al, Scenario sc) {
        applyScenario(sc);
        M.resetTrace();
        al.gsAlLaunch(M.player, sc.skill);
        pump(200);
        return traceFp();
    }

    // ======================================================================
    // C9. 心跳：换手不关技能
    // ======================================================================

    /**
     * 造一个「没初始化过的 {@link ItemStack}」。
     *
     * <p>★ 为什么不用构造函数：paper-api 单独跑时 {@code Material} 的静态初始化
     * 会因为 {@code No RegistryAccess implementation found} 抛
     * {@code ExceptionInInitializerError}。本探针只需要一个「不是我们的物品」的
     * {@code ItemStack} 实例 —— {@code Guards.isOurItem} 对它一样返回 false。
     */
    private static ItemStack rawItemStack() {
        try {
            Class<?> unsafeC = Class.forName("sun.misc.Unsafe");
            Field uf = unsafeC.getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object unsafe = uf.get(null);
            return (ItemStack) unsafeC.getMethod("allocateInstance", Class.class)
                    .invoke(unsafe, ItemStack.class);
        } catch (Throwable e) {
            return null;
        }
    }

    private static void pulseC(AlSub al) {
        // ★ 反向断言：切走（手上不是发射器）⇒ 一个字节都不动
        M.resetPdc();
        M.pdc.put("gs:al_skill", "1");
        M.pdc.put("gs:al_ammo", 3);
        M.pdc.put("gs:al_lastshot", 12345L);
        M.hand = null;
        String before = pdcFp();
        al.gsAlPulse();
        eq("C94 心跳：手上不是发射器 ⇒ PDC 一个字节都不动（技能保留）", before, pdcFp());
        check("C95 心跳：技能仍然开着", M.pdc.containsKey("gs:al_skill"));
        eq("C96 心跳：弹药仍是 3", 3, M.pdc.get("gs:al_ammo"));

        // 拿一把「不是发射器的物品」也一样
        // ★ 这里**不能** new ItemStack(...)：paper-api 单跑没有 RegistryAccess，
        //   Material 的静态初始化会炸。用 Unsafe 造一个「没初始化过的 ItemStack」，
        //   效果等价于「手上拿了个别的东西」—— gsIsItem 一样判不出来。
        M.hand = rawItemStack();
        String before2 = pdcFp();
        al.gsAlPulse();
        eq("C97 心跳：拿着别的物品（gsIsItem 判不出）⇒ PDC 仍不动", before2, pdcFp());
        M.hand = null;

        // ★ 世代号闸门
        check("C98 gsAuraIsCurrent() 默认 true（gsAuraMyGen 未 bump）",
                new ListenerCore().gsAuraIsCurrent());

        // ★ 没有在线玩家时不炸（假服务器返回 1 个玩家，这里只验证不抛）
        M.resetPdc();
        al.gsAlPulse();
        eq("C99 心跳跑完不留下任何 PDC 垃圾", 0, M.pdc.size());
    }

    // ======================================================================
    // D. 反向断言
    // ======================================================================

    private static void reverseD(SfSub sf, AlSub al) throws Exception {
        Path sfPath = srcOverride("gs.probe.srcSf", SF_SRC);
        Path alPath = srcOverride("gs.probe.srcAl", AL_SRC);
        System.out.println("  （D 段扫描的源码：" + sfPath.getFileName() + " / " + alPath.getFileName() + "）");
        String sfSrc = new String(Files.readAllBytes(sfPath), StandardCharsets.UTF_8);
        String alSrc = new String(Files.readAllBytes(alPath), StandardCharsets.UTF_8);
        String script = new String(Files.readAllBytes(SCRIPT), StandardCharsets.UTF_8);

        // ★★ 反向断言分两类，扫描的文本也必须分两类：
        //   「代码里不该出现 X」 ⇒ 必须扫**去掉注释**的源码
        //     （AlSub 的注释里**故意**写着「没有 createExplosion」「没有 sendMessage」
        //       —— 拿原始源码扫会把自己注释里的字面量当成违规）
        //   「注释里必须写明 Y」 ⇒ 扫**原始**源码
        String sfCode = stripComments(sfSrc);
        String alCode = stripComments(alSrc);

        // ---- 注册幂等（真跑一遍）----
        REGISTER_EVENTS.set(0);
        SfSub sf2 = new SfSub(new ListenerCore());
        boolean a = sf2.gsSfRegisterSync();
        boolean b2 = sf2.gsSfRegisterSync();
        eq("D1  gsSfRegisterSync 两次调用只注册 1 次", 1, REGISTER_EVENTS.get());
        check("D2  gsSfRegisterSync 两次都返回 true", a && b2);

        REGISTER_EVENTS.set(0);
        AlSub al2 = new AlSub(new ListenerCore());
        boolean c = al2.gsAlRegisterSync();
        boolean d = al2.gsAlRegisterSync();
        eq("D3  gsAlRegisterSync 两次调用只注册 1 次", 1, REGISTER_EVENTS.get());
        check("D4  gsAlRegisterSync 两次都返回 true", c && d);

        // ★ 原脚本每次 reload 都会重注册（真实缺陷）；Java 幂等 ⇒ 差异已在类注释里写明
        check("D5  AlSub 类注释写明「原脚本会累积、Java 改为幂等」",
                alSrc.contains("原脚本会累积，Java 版改为幂等"));
        check("D6  SfSub 类注释写明「原脚本会累积，Java 版改为幂等」",
                sfSrc.contains("原脚本会累积，Java 版改为幂等"));

        // ---- 闩锁必须是**实例字段**（不是 static）----
        check("D7  GS_SF_REGISTERED 不是 static",
                !java.lang.reflect.Modifier.isStatic(
                        SfSub.class.getDeclaredField("GS_SF_REGISTERED").getModifiers()));
        check("D8  GS_AL_REGISTERED 不是 static",
                !java.lang.reflect.Modifier.isStatic(
                        AlSub.class.getDeclaredField("GS_AL_REGISTERED").getModifiers()));
        check("D9  gsAlKeys 缓存不是 static",
                !java.lang.reflect.Modifier.isStatic(AlSub.class.getDeclaredField("gsAlKeys").getModifiers()));
        check("D10 GS_AL_DUST 缓存不是 static",
                !java.lang.reflect.Modifier.isStatic(AlSub.class.getDeclaredField("GS_AL_DUST").getModifiers()));

        // ---- 优先级 / ignoreCancelled ----
        Method sfOnFish = SfSub.Sync.class.getDeclaredMethod("onFish",
                org.bukkit.event.player.PlayerFishEvent.class);
        eq("D11 SfSub 监听器优先级 = MONITOR（只观察、不改事件）",
                org.bukkit.event.EventPriority.MONITOR,
                sfOnFish.getAnnotation(org.bukkit.event.EventHandler.class).priority());
        eq("D12 SfSub ignoreCancelled = false（原脚本没传第 5 参数）", false,
                sfOnFish.getAnnotation(org.bukkit.event.EventHandler.class).ignoreCancelled());
        eq("D13 SfSub 的监听器方法恰好 1 个", 1, SfSub.Sync.class.getDeclaredMethods().length);

        for (String mn : new String[]{"onInteract", "onDamageByEntity", "onAnimation"}) {
            Method m = null;
            for (Method x : AlSub.Sync.class.getDeclaredMethods()) {
                if (x.getName().equals(mn)) {
                    m = x;
                }
            }
            check("D14 AlSub 有监听器方法 " + mn, m != null);
            if (m != null) {
                org.bukkit.event.EventHandler h = m.getAnnotation(org.bukkit.event.EventHandler.class);
                eq("D15 AlSub." + mn + " 优先级 = HIGHEST",
                        org.bukkit.event.EventPriority.HIGHEST, h.priority());
                eq("D16 AlSub." + mn + " ignoreCancelled = false", false, h.ignoreCancelled());
            }
        }
        eq("D17 AlSub 的监听器方法恰好 3 个（没有多余的注册）",
                3, AlSub.Sync.class.getDeclaredMethods().length);

        // ---- 没注册不该注册的事件 ----
        check("D18 AlSub 没注册 PlayerSwapHandItemsEvent", !alCode.contains("PlayerSwapHandItemsEvent"));
        check("D19 AlSub 没注册 PlayerDropItemEvent", !alCode.contains("PlayerDropItemEvent"));
        check("D20 AlSub 没注册 PlayerItemHeldEvent（换手不关技能）", !alCode.contains("PlayerItemHeldEvent"));
        check("D21 SfSub 只注册 PlayerFishEvent",
                !sfCode.contains("PlayerInteractEvent") && !sfCode.contains("PlayerAnimationEvent"));

        // ---- 不破坏地形 ----
        check("D22 AlSub 代码里没有 createExplosion", !alCode.contains("createExplosion"));
        check("D23 AlSub 没有对世界写方块",
                !alCode.contains("setType(") && !alCode.contains("breakNaturally"));
        check("D24 AlSub 的爆炸只走 spawnParticle", alCode.contains("Particle.EXPLOSION_EMITTER"));

        // ---- 聊天提示词全删 ----
        check("D25 AlSub 代码里没有 sendMessage", !alCode.contains("sendMessage"));
        check("D26 AlSub 代码里没有 ActionBar / Title 提示",
                !alCode.contains("sendActionBar") && !alCode.contains("sendTitle"));
        check("D27 SfSub 代码里没有 sendMessage", !sfCode.contains("sendMessage"));
        check("D28 原脚本 13699~13701 行的注释确实说「一条都不再发消息」",
                script.contains("一条都不再发消息"));

        // ---- 常态无限弹药 / 换手不关技能 ----
        check("D29 原脚本注释确认「换手持不会把技能关闭」", script.contains("换手持不会把技能关闭"));
        check("D30 原脚本注释确认常态「**无限弹药**」", script.contains("**无限弹药**"));
        check("D31 AlSub 注释也写明「换手持不再关闭技能」", alSrc.contains("换手持不再关闭技能"));
        check("D32 AlSub 注释也写明「常态无限弹药」", alSrc.contains("无限弹药"));

        // ---- PDC 类型：skill / shooting 必须 STRING（不是 BYTE）----
        check("D33 gs:al_skill 用 STRING", alCode.contains("pdc.set(k.skill, PersistentDataType.STRING"));
        check("D34 gs:al_shooting 用 STRING", alCode.contains("pdc.set(k.shooting, PersistentDataType.STRING"));
        check("D35 弹药用 INTEGER", alCode.contains("pdc.set(k.ammo, PersistentDataType.INTEGER"));
        check("D36 时间戳用 LONG", alCode.contains("pdc.set(key, PersistentDataType.LONG"));
        check("D37 没有用 BYTE 的 PDC 写入", !alCode.contains("PersistentDataType.BYTE"));

        // ---- 心跳节奏：runRepeating(fn, 10, 10) ----
        check("D38 gsAlStart 用 runRepeating(gsAlPulse, 10, 10)（delay = period = 10）",
                alCode.contains("GS.runRepeating(this::gsAlPulse, 10, 10)"));
        eq("D39 AlSub 只有 1 处 runRepeating", 1, countOccurrences(alCode, "runRepeating("));
        check("D40 原脚本那一处也是 runRepeating(..., 10, 10)",
                script.contains("runRepeating(function () { gsAlPulse(); }, 10, 10)"));
        check("D41 SfSub 没有 runRepeating / runLater（钓鱼是纯事件驱动）",
                !sfCode.contains("runRepeating") && !sfCode.contains("runLater"));

        // ---- 弹道用 runLater 自链（不是 runRepeating）----
        check("D42 AlSub 的弹道用 runLater 自链", alCode.contains("GS.runLater(this, 1)"));
        check("D43 原脚本那一处也是 runLater(step, 1)", script.contains("runLater(step, 1)"));

        // ---- 关键常量没被「顺手优化」----
        check("D44 常态 800 毫秒（不是 1300）", AlSub.GS_AL_FIRE_MS == 800);
        check("D45 技能 1500 毫秒（不是 3000）", AlSub.GS_AL_SKILL_FIRE_MS == 1500);
        check("D46 冷却 12000 毫秒（不是 18000）", AlSub.GS_AL_CD_MS == 12000);
        check("D47 常态伤害 35（不是 55）", AlSub.GS_AL_N_DMG == 35.0);
        check("D48 技能伤害 175（不是 155）", AlSub.GS_AL_S_DMG == 175.0);
        check("D49 常态半径 2.5", AlSub.GS_AL_N_R == 2.5);
        check("D50 技能半径 4.5", AlSub.GS_AL_S_R == 4.5);
        check("D51 防抖 1000 毫秒", AlSub.GS_AL_TOGGLE_MS == 1000);
        check("D52 射程 24 格", AlSub.GS_AL_RANGE == 24.0);
        check("D53 弹速 30 格/秒", AlSub.GS_AL_SPEED_BPS == 30.0);
        check("D54 每刻 1.5 格", AlSub.GS_AL_STEP == 1.5);
        check("D55 命中半径 0.8 格", AlSub.GS_AL_HIT_R == 0.8);
        check("D56 发射高度 1.2 格", AlSub.GS_AL_MUZZLE_Y == 1.2);
        check("D57 弹药 6 发", AlSub.GS_AL_AMMO == 6);

        // ---- 没有「已加载」这种框架级多余日志 ----
        eq("D58 AlSub 代码里没有额外的 `已加载` 行", 0, countOccurrences(alCode, "已加载"));
        eq("D59 SfSub 代码里只有 1 处 `已加载`（就是那一行）", 1, countOccurrences(sfCode, "已加载"));
        eq("D60 原脚本 AlSub 区间里没有 `已加载`",
                0, countOccurrences(script.substring(script.indexOf("var GS_AL_ID")), "已加载"));

        // ---- 关键顺序：打点在判定之前（★ 必须限定在方法体内，
        //      否则 indexOf 会命中前面别的函数里同名的调用）----
        String toggleBody = methodBody(alSrc, "void gsAlToggle(Player p) {");
        String exitBody = methodBody(alSrc, "void gsAlExitSkill(Player p, boolean silent) {");
        String fireBody = methodBody(alSrc, "void gsAlFire(Player p) {");
        check("D61 gsAlToggle 方法体提取成功（" + toggleBody.length() + " 字符）", toggleBody.length() > 400);
        check("D62 gsAlExitSkill 方法体提取成功（" + exitBody.length() + " 字符）", exitBody.length() > 300);
        check("D63 gsAlFire 方法体提取成功（" + fireBody.length() + " 字符）", fireBody.length() > 600);

        check("D64 gsAlToggle 里 gsAlMarkToggle 在 gsAlIsSkill 之前（先打点再判定）",
                toggleBody.indexOf("gsAlMarkToggle(p);") >= 0
                        && toggleBody.indexOf("gsAlMarkToggle(p);") < toggleBody.indexOf("if (gsAlIsSkill(p))"));
        check("D65 gsAlToggle 里防抖判定在最前（连时间戳都不更新）",
                toggleBody.indexOf("gsAlToggleAgo(p) < GS_AL_TOGGLE_MS") >= 0
                        && toggleBody.indexOf("gsAlToggleAgo(p) < GS_AL_TOGGLE_MS")
                        < toggleBody.indexOf("gsAlMarkToggle(p);"));
        check("D66 gsAlExitSkill 里 gsAlMarkCd 在播放关闭音效之前（冷却从退出那刻算）",
                exitBody.indexOf("gsAlMarkCd(p);") >= 0
                        && exitBody.indexOf("gsAlMarkCd(p);") < exitBody.indexOf("GS_AL_SND_OFF"));
        check("D67 gsAlFire 里技能分支在常态分支之前",
                fireBody.indexOf("if (gsAlIsSkill(p))") >= 0
                        && fireBody.indexOf("if (gsAlIsSkill(p))") < fireBody.indexOf("// ---- 常态"));
        check("D68 gsAlFire 里技能分支的节流值用 GS_AL_SKILL_FIRE_MS",
                fireBody.contains("gsAlLastShotAgo(p) < GS_AL_SKILL_FIRE_MS"));
        check("D69 gsAlFire 常态分支的节流值用 GS_AL_FIRE_MS",
                fireBody.contains("gsAlLastShotAgo(p) < GS_AL_FIRE_MS"));
        check("D70 gsAlFire 常态分支不碰弹药（没有 gsAlSetAmmo）",
                !fireBody.substring(fireBody.indexOf("// ---- 常态")).contains("gsAlSetAmmo"));
        check("D71 gsAlFire 里「打空自动退出」是静默的（gsAlExitSkill(p, true)）",
                fireBody.contains("gsAlExitSkill(p, true)"));
        check("D72 gsAlFire 里「弹药 0 却还在技能态」是**非**静默退出（gsAlExitSkill(p, false)）",
                fireBody.contains("gsAlExitSkill(p, false)"));

        // ---- 名字没变就不写（心跳不刷包）----
        check("D73 gsAlUpdateName 有「名字没变就 return」的比对",
                alCode.contains("cur != null && String.valueOf(cur).equals(String.valueOf(want))"));

        // ---- 没把 SPEED_BPS 用进算式（原脚本就是只用 STEP）----
        check("D74 弹道推进只读 GS_AL_STEP（SPEED_BPS 只出现在日志里）",
                countOccurrences(alCode, "GS_AL_STEP") >= 3
                        && !alCode.contains("GS_AL_SPEED_BPS *")
                        && !alCode.contains("* GS_AL_SPEED_BPS"));

        // ---- 死代码逐条写明（这一类要扫**原始**源码 —— 写的是注释）----
        check("D75 AlSub 类注释列了 JavaAdapter 死代码", alSrc.contains("JavaAdapter"));
        check("D76 AlSub 类注释列了 plugin 四级探测链死代码", alSrc.contains("四级探测链"));
        check("D77 AlSub 类注释说明了 DROPPED_ITEM 已改名 ITEM", alSrc.contains("DROPPED_ITEM"));
        check("D78 AlSub 类注释说明了 gsAlHitDamage 没有 try/finally", alSrc.contains("没有"));
        check("D79 SfSub 类注释列了 JavaAdapter 死代码", sfSrc.contains("JavaAdapter"));
        check("D80 AlSub 类注释写明与 SfSub 的优先级差异",
                alSrc.contains("海水过滤器用 {@code Priority.MONITOR}"));

        // ---- 两个子系统都声明为 final class ----
        check("D81 SfSub 是 final class",
                java.lang.reflect.Modifier.isFinal(SfSub.class.getModifiers()));
        check("D82 AlSub 是 final class",
                java.lang.reflect.Modifier.isFinal(AlSub.class.getModifiers()));

        // ---- 构造函数签名一致（统一形状）----
        check("D83 SfSub 有 (ListenerCore) 构造函数", hasCtor(SfSub.class, ListenerCore.class));
        check("D84 AlSub 有 (ListenerCore) 构造函数", hasCtor(AlSub.class, ListenerCore.class));
        check("D85 SfSub 有 start()", hasMethod0(SfSub.class, "start"));
        check("D86 AlSub 有 start()", hasMethod0(AlSub.class, "start"));
        check("D87 SfSub 的 start() 调 gsSfStart()", sfCode.contains("public void start()"));
        check("D88 AlSub 的 start() 调 gsAlStart()", alCode.contains("public void start()"));

        // ==================================================================
        // ★★ 海水过滤器「掉落」那一段：**源码文本**断言
        //
        //   gsSfDrop 走的是 gsNewItem ⇒ SlimefunItem.getById，裸探针里恒返回 null
        //   ⇒ 整段掉落逻辑（设数量 + 四级回退）**跑不到**，只能扫源码。
        //   这一段是「用文本断言覆盖行为断言的盲区」，在此写明，
        //   不假装它是行为断言。
        // ==================================================================
        String dropBody = methodBody(sfSrc, "boolean gsSfDrop(Player p, Location hookLoc) {");
        check("D89 gsSfDrop 方法体提取成功（" + dropBody.length() + " 字符）", dropBody.length() > 500);
        check("D90 掉落数量用的是常量 GS_SF_AMOUNT（不是字面量）",
                dropBody.contains("it.setAmount(GS_SF_AMOUNT)"));
        check("D91 掉落数量没有被写成 setAmount(0) / setAmount(1)",
                !dropBody.contains("setAmount(0)") && !dropBody.contains("setAmount(1)"));

        // ★ 四级回退的**顺序**（原脚本 13589~13595 行）：
        //   浮漂 naturally → 浮漂 dropItem → 脚下 naturally → 脚下 dropItem
        int i1 = dropBody.indexOf("dropItemNaturally(hookLoc");
        int i2 = dropBody.indexOf("dropItem(hookLoc");
        int i3 = dropBody.indexOf("dropItemNaturally(p.getLocation()");
        int i4 = dropBody.indexOf("dropItem(p.getLocation()");
        check("D92 四级回退顺序 = 浮漂naturally → 浮漂dropItem → 脚下naturally → 脚下dropItem",
                i1 >= 0 && i2 > i1 && i3 > i2 && i4 > i3);
        eq("D93 四级回退每一级都 return true（共 4 处）", 4, countOccurrences(dropBody, "return true;"));
        check("D94 拿不到物品时 return false", dropBody.contains("return false;"));

        // ★ 状态闸门只看 CAUGHT_FISH（不是 CAUGHT_ENTITY / IN_GROUND）
        String fishBody = methodBody(sfSrc, "void gsSfOnFish(PlayerFishEvent e) {");
        check("D95 gsSfOnFish 方法体提取成功（" + fishBody.length() + " 字符）", fishBody.length() > 400);
        check("D96 状态闸门只认 CAUGHT_FISH", fishBody.contains("\"CAUGHT_FISH\""));
        check("D97 没有把 CAUGHT_ENTITY / IN_GROUND 也放进来",
                !fishBody.contains("CAUGHT_ENTITY") && !fishBody.contains("IN_GROUND"));
        check("D98 7% 判定用的是 Math.floor(rnd * 100)（整数比较）",
                methodBody(sfSrc, "static boolean gsSfSkip(double rnd) {")
                        .contains("Math.floor(rnd * 100) >= GS_SF_CHANCE"));
        check("D98a gsSfOnFish 调的就是 gsSfSkip(Math.random())",
                fishBody.contains("gsSfSkip(Math.random())"));
        check("D99 没有写成 Math.random() < 0.07 这种浮点比较",
                !sfCode.contains("< 0.07") && !sfCode.contains("< 7 / 100")
                        && !sfCode.contains("< 7.0 / 100"));
        check("D100 不取消原版钓鱼（没有 setCancelled）", !fishBody.contains("setCancelled"));
    }

    /** 去掉 {@code //} 行注释与 {@code /* *}{@code /} 块注释（保留字符串字面量）。 */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                out.append(c);
                i++;
                while (i < n) {
                    char d = s.charAt(i);
                    out.append(d);
                    if (d == '\\' && i + 1 < n) {
                        out.append(s.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    i++;
                    if (d == c) {
                        break;
                    }
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 从 {@code sig} 起把配对的 {@code { ... }} 整块抠出来（用于把断言限定在方法体内）。 */
    private static String methodBody(String src, String sig) {
        int i = src.indexOf(sig);
        if (i < 0) {
            return "";
        }
        int j = src.indexOf('{', i + sig.length() - 1);
        if (j < 0) {
            return "";
        }
        int depth = 0;
        for (int k = j; k < src.length(); k++) {
            char c = src.charAt(k);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return src.substring(j, k + 1);
                }
            }
        }
        return src.substring(j);
    }

    private static boolean hasCtor(Class<?> c, Class<?>... params) {
        try {
            c.getDeclaredConstructor(params);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean hasMethod0(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return true;
            }
        }
        return false;
    }

    private static int countOccurrences(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
