package com.qinghuacisj.gs.script.module.listener;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.util.Vector;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 「篮球 / 篮球生成器 / 副手钥匙锁 / 孤儿球清扫」四个子系统的对账探针
 * （原脚本 {@code gs_listener.js} 第 <b>10952~13569</b> 行）。
 *
 * <h2>四类断言</h2>
 * <ol>
 *   <li><b>[A] 常量对账 + 完备性</b>：用<b>真 GraalJS</b> 跑原脚本（前 13569 行，含全部顶层调用），
 *       从<b>真 JS 绑定表</b>里枚举出所有 {@code GS_(BB|BG|KL|ENT)_*} 顶层变量，
 *       与 {@link BbSub} / {@link BgSub} / {@link KlSub} / {@link EntPurgeSub} 的字段
 *       <b>按名按值</b>比对。★ 双向完备性：
 *       <ul>
 *         <li>JS 有、Java 没有 ⇒ 必须在 {@link #JS_ONLY} 白名单里（并逐个说明为什么）；
 *             <b>白名单之外的任何一个漏拷都必须立刻失败</b>。</li>
 *         <li>Java 有、JS 没有 ⇒ 立刻失败（说明凭空造了常量）。</li>
 *       </ul></li>
 *   <li><b>[B] 日志行逐字节</b>：真 JS 打出来的 5 行 vs Java 的 {@code xxxLine()}
 *       vs {@code docs/参考服日志_构思技艺.txt} 里那 5 行（三方差一不可）。</li>
 *   <li><b>[C] 数值 / 几何 / 物理</b>：把原脚本的<b>真函数</b>用 mock 世界跑起来，
 *       在<b>大量输入</b>上与 Java 侧逐值比对 ——
 *       投篮方向与初速、直线段后重力、落地弹跳（衰减 / 保速 / 上限 / 原地弹）、
 *       扫掠落地与扫掠撞墙、材质名挡球清单、触碰半径、送回半径、超时落点、
 *       以及 ★★★ <b>{@code | 0}（ToInt32 回绕）语义</b>。</li>
 *   <li><b>[D] 反向断言</b>：钉住「不该存在 / 不该改变的东西」——
 *       「律师函」字样一个字都没改、{@code GS_BB_ID} 没被「顺手修正」、
 *       钥匙锁恰好 6 条事件、{@code ignoreCancelled} 全为 false、
 *       没有 {@code System.out.println}、PDC 键名逐个没变、两套副手球不串台、
 *       孤儿球清扫排在最后一个顶层调用、四个子系统不越界引用别人的符号。</li>
 * </ol>
 *
 * <h2>★ 桥接桩（原脚本顶层有副作用）</h2>
 * {@code gs_listener.js} 顶层会注册监听器、跑 {@code runRepeating}、打 40+ 行日志。
 * 所以求值前先把 {@code getServer()} / {@code runLater} / {@code runRepeating}
 * 换成空实现（{@link #JS_STUBS}）。
 * ★ <b>实测：整个前 13569 行在纯 JS 桩下能完整求值成功</b>
 * （所有子系统自己的顶层调用都各自包在 try/catch 里）⇒ 不需要像 B 探针那样只切一段前缀。
 * 这也是本探针能拿到<b>全部 5 条目标日志行</b>的原因。
 * <p>★ 不需要 shim {@code org.bukkit.Location}：本探针的几何测试<b>不调用</b>
 * {@code gsMsLoc}（mock 世界的 {@code getBlockAt} 永远返回方块），
 * 也不把 {@code Location} 暴露给 GraalJS。
 *
 * <h2>★★ 为什么 Java 侧用真 {@code Location} 子类而不 mock 方向</h2>
 * 原脚本取方向靠 {@code p.getLocation().getDirection()}。Java 侧要复现同一输入，
 * 就在真 {@code Location} 上设 yaw/pitch，<b>两边用各自的 {@code getDirection()} 公式</b>
 * —— 这样连「两边对 yaw/pitch 的约定是否一致」也一起验了（已实测 Paper 1.21.11 的
 * {@code Location.getDirection()} 就是经典公式）。
 */
public final class ListenerFSubParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 我负责的区间（原脚本 1-based，含两端）。 */
    private static final int SUB_FROM = 10952;
    private static final int SUB_TO = 13569;

    private static final String SELF_ID = "00000000-0000-0000-0000-0000000000f1";

    private static final Path SCRIPT = pick(
            Path.of("../QHCGS/GS/GS/scripts/gs_listener.js"),
            Path.of("D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_listener.js"));

    private static final Path LOG = pick(
            Path.of("docs/参考服日志_构思技艺.txt"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/docs/参考服日志_构思技艺.txt"));

    private static final String PKG = "src/main/java/com/qinghuacisj/gs/script/module/listener/";

    private static final Path BB_SRC = pick(Path.of(PKG + "BbSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/" + PKG + "BbSub.java"));
    private static final Path BG_SRC = pick(Path.of(PKG + "BgSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/" + PKG + "BgSub.java"));
    private static final Path KL_SRC = pick(Path.of(PKG + "KlSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/" + PKG + "KlSub.java"));
    private static final Path ENT_SRC = pick(Path.of(PKG + "EntPurgeSub.java"),
            Path.of("D:/qq下载/粘液科技自用/GSPlugin/" + PKG + "EntPurgeSub.java"));

    private static final String[] PREFIXES = {"GS_BB_", "GS_BG_", "GS_KL_", "GS_ENT_"};

    /**
     * ★ 容器类变量：JS 侧是 {@code {}} 字面量、Java 侧是 {@code Map}，
     * 空的 {@code {}} 与空的 {@code {}} 无法有意义地按值比对 ⇒ 只断言类型（见下）。
     */
    private static final Set<String> CONTAINERS = new LinkedHashSet<>(Arrays.asList(
            "GS_BB_ST", "GS_BB_CLICK_AT", "GS_BB_BARS",
            "GS_BG_NODES", "GS_BG_CLICK_AT", "GS_KL_HINT_AT"));

    /**
     * ★★ JS 独有、Java 侧<b>故意不移植</b>的顶层变量（每一个都要有理由）。
     * 探针会断言「JS 独有集合 == 这个集合」，多一个少一个都失败。
     */
    private static final Map<String, String> JS_ONLY = new LinkedHashMap<>();

    static {
        JS_ONLY.put("GS_BB_MAT_C",
                "原脚本 11114：Java.type('org.bukkit.Material') 的懒加载缓存（初值 null）"
                + "—— Java 直接引用类，不需要缓存");
        JS_ONLY.put("GS_BB_ISC_C",
                "原脚本 11114：Java.type('org.bukkit.inventory.ItemStack') 的懒加载缓存（初值 null）"
                + "—— 同上");
        JS_ONLY.put("GS_BB_FEC",
                "原脚本 11103：Java.type('org.bukkit.Color') 的懒加载缓存（初值 null，"
                + "只在 gsBbSpellSpark 兜底路径里用）—— Java 直接 Color.fromRGB，不需要缓存");
    }

    // ======================================================================
    // 桥接桩
    // ======================================================================

    private static final String JS_STUBS = """
            var __log = [];          // getServer().getLogger() 收到的每一行
            var __online = [];       // getOnlinePlayers() 的返回值（可注入）
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
              getWorlds: function () { return []; },
              createBossBar: function () { return null; },
              getWorld: function (n) { return null; }
            };
            function getServer() { return __srv; }
            function getAddonConfig() { return null; }
            function runLater(fn, d) { return null; }
            function runRepeating(fn, d, p) { return null; }
            """;

    /**
     * mock 世界 / 方块 / 玩家 / 时钟 —— 全部是 JS 鸭子类型对象。
     *
     * <p>★ 方块模型的语义与 Paper 对齐：
     * {@code isPassable() === false} ⇒ 挡球；
     * {@code isPassable() === true} 时再看材质名是否命中 {@code GS_BB_SOLID_KEYS}
     * （玻璃板这类「薄方块」的兜底）。
     */
    private static final String JS_HELPERS = """
            var __now = 0;
            var __SELF = '00000000-0000-0000-0000-0000000000f1';

            // ---- 方块 ----
            // cfg = { floorY: 地板顶面所在格的 y（该格起往下都是 STONE），
            //         paneX: 一堵 GLASS_PANE 薄墙所在的整数 x（null = 没有墙）,
            //         passableFalseName: 强制「isPassable 说不能过」的材质名 }
            var __world = null;
            function __mkBlock(matName, passable) {
              return {
                isPassable: function () { return passable; },
                getType: function () {
                  return { name: function () { return matName; },
                           isSolid: function () { return !passable; } };
                }
              };
            }
            function __mkWorld(cfg) {
              cfg = cfg || {};
              var floorTop = (cfg.floorY === undefined) ? null : cfg.floorY;
              var paneX = (cfg.paneX === undefined) ? null : cfg.paneX;
              return {
                __cfg: cfg,
                getName: function () { return 'world'; },
                getBlockAt: function (fx, fy, fz) {
                  if (paneX !== null && fx === paneX && fy >= 60 && fy <= 68) {
                    return __mkBlock('GLASS_PANE', true);       // ★ 薄方块：isPassable 说能过
                  }
                  if (floorTop !== null && fy < floorTop) {
                    return __mkBlock('STONE', false);
                  }
                  return __mkBlock('AIR', true);
                },
                spawnParticle: function () { },
                playSound: function () { },
                dropItemNaturally: function () { return null; },
                getEntitiesByClass: function () { return []; },
                getEntities: function () { return []; },
                getPersistentDataContainer: function () { return __mkPdc({}); }
              };
            }
            function __mkPdc(init) {
              var m = {};
              if (init) { for (var k in init) { m[k] = init[k]; } }
              return {
                __m: m,
                get: function (key) { var v = m['' + key]; return (v === undefined) ? null : v; },
                set: function (key, type, v) { m['' + key] = v; },
                remove: function (key) { delete m['' + key]; },
                has: function (key) { return m['' + key] !== undefined; }
              };
            }

            // ---- 玩家（真 Location 的替身：只暴露脚本用到的四个方法）----
            function __mkLoc(x, y, z, dx, dy, dz) {
              return {
                getX: function () { return x; }, getY: function () { return y; }, getZ: function () { return z; },
                getDirection: function () { return { getX: function () { return dx; },
                                                     getY: function () { return dy; },
                                                     getZ: function () { return dz; } }; },
                setYaw: function () { }, setPitch: function () { },
                getBlock: function () { return __mkBlock('AIR', true); }
              };
            }
            function __mkInv() {
              return {
                __off: null,
                getItemInOffHand: function () { return this.__off; },
                setItemInOffHand: function (it) { this.__off = it; },
                getItemInMainHand: function () { return null; },
                setItemInMainHand: function () { },
                getChestplate: function () { return null; },
                getItem: function () { return null; },
                setItem: function () { },
                getSize: function () { return 41; },
                getContents: function () { return []; }
              };
            }
            function __mkPlayer(x, y, z, dx, dy, dz) {
              return {
                __x: x, __y: y, __z: z,
                getUniqueId: function () { return __SELF; },
                getLocation: function () { return __mkLoc(x, y, z, dx, dy, dz); },
                getWorld: function () { return __world; },
                getInventory: function () { return __mkInv(); },
                sendActionBar: function () { },
                updateInventory: function () { }
              };
            }

            // ============ 捕获入口（几何 / 物理） ============

            // 把 JS 的球对象转成"可比对的行"（★ 11 个元素**全是数字**，mode 也数字化）
            function __modeNum(m) {
              if (m === 'idle') return 0;
              if (m === 'bound') return 1;
              if (m === 'shot') return 2;
              if (m === 'rest') return 3;
              return -1;
            }
            function __snap(st) {
              return [st.x, st.y, st.z, st.vx, st.vy, st.vz,
                      __modeNum(st.mode), (st.life | 0), (st.bnc | 0), (st.hitWall === true) ? 1 : 0,
                      st.sy0];
            }
            function __mkBall(mode, x, y, z, vx, vy, vz, bnc, hitWall, life, sy0, spin) {
              return { mode: mode, w: __world, x: x, y: y, z: z,
                       vx: vx, vy: vy, vz: vz, spin: spin || 0,
                       life: life | 0, shotAt: 0, touched: 0, heldBall: false,
                       bnc: bnc | 0, hitWall: hitWall === true, ent: null, entTick: 0,
                       sy0: sy0 || 0, owner: null, node: null, tBoundAt: 0, t0: 0, until: 0 };
            }

            // ★ 完整轨迹：每刻快照一行
            function __flight(cfg, init, ticks) {
              __world = __mkWorld(cfg);
              var st = __mkBall('shot', init[0], init[1], init[2],
                                init[3], init[4], init[5], init[6], init[7], init[8], init[9], 0);
              var rows = [];
              for (var i = 0; i < ticks; i++) {
                gsBbTickShot(null, st);
                rows.push(__snap(st));
              }
              return rows;
            }

            // ★ 投篮：返回出手后的状态快照
            function __shoot(cfg, px, py, pz, dx, dy, dz, lawyer, prevShotAt) {
              __world = __mkWorld(cfg);
              var p = __mkPlayer(px, py, pz, dx, dy, dz);
              var st = __mkBall('bound', px, py, pz, 0, 0, 0, 0, false, 0, 0, 0);
              st.shotAt = prevShotAt || 0;
              GS_BB_ST = {}; GS_BB_BARS = {}; GS_BB_CLICK_AT = {};
              if (lawyer) GS_BB_ST[__SELF] = st;
              var ok = gsBbShootState(p, st);
              return [ok ? 1 : 0].concat(__snap(st));
            }

            // ★ 落地弹跳：给定 (bnc, hitWall, vx, vz) ⇒ 返回弹跳后的状态
            function __land(bnc, hitWall, vx, vz, floorY) {
              var st = __mkBall('shot', 0, 0, 0, vx, 0, vz, bnc, hitWall, 5, 0, 0);
              gsBbLand(st, floorY);
              return __snap(st);
            }

            // ★ 超时落点
            function __dropNear(px, py, pz, sy) {
              __world = __mkWorld({});
              var p = __mkPlayer(px, py, pz, 0, 0, 1);
              var st = __mkBall('bound', px, py, pz, 0, 0, 0, 0, false, 0, 0, 0);
              st.y = sy;
              GS_BB_ST = {}; GS_BB_BARS = {};
              gsBbDropNear(p, st);
              return __snap(st);
            }

            // ★ 触碰判定
            function __touch(sx, sz, sy, px, py, pz) {
              __world = __mkWorld({});
              var p = __mkPlayer(px, py, pz, 0, 0, 1);
              var st = __mkBall('idle', sx, sy, sz, 0, 0, 0, 0, false, 0, 0, 0);
              return gsBbTouch(p, st) ? 1 : 0;
            }

            // ★ 生成器：距离判定
            function __bgFar(nx, ny, nz, bx, by, bz) {
              var node = { w: null, x: nx, y: ny, z: nz, balls: [] };
              var b = __mkBall('shot', bx, by, bz, 0, 0, 0, 0, false, 0, 0, 0);
              return gsBgFar(node, b) ? 1 : 0;
            }

            // ★ 生成器：触碰判定
            function __bgTouch(bx, bz, by, px, py, pz) {
              __world = __mkWorld({});
              var p = __mkPlayer(px, py, pz, 0, 0, 1);
              var b = __mkBall('idle', bx, by, bz, 0, 0, 0, 0, false, 0, 0, 0);
              return gsBgTouch(p, b) ? 1 : 0;
            }

            // ★ 材质名挡球清单
            function __nameSolid(n) { return gsBbNameSolid(__mkBlock(n, true)) ? 1 : 0; }
            function __solid(passable, n) { return gsBbSolid(__mkBlock(n, passable)) ? 1 : 0; }

            // ★ 扫掠落地 / 扫掠撞墙 / 找地面
            function __floorSweep(cfg, x, yPrev, yNow, z) {
              __world = __mkWorld(cfg);
              var v = gsBbFloorSweep(__world, x, yPrev, yNow, z, GS_BB_R);
              return (v === null || v === undefined) ? -99999 : v;
            }
            function __floor(cfg, x, yTop, z, maxDown) {
              __world = __mkWorld(cfg);
              var v = gsBbFloor(__world, x, yTop, z, maxDown);
              return (v === null || v === undefined) ? -99999 : v;
            }
            function __wallSweep(cfg, x0, z0, x1, z1, y) {
              __world = __mkWorld(cfg);
              return gsBbWallSweep(__world, x0, z0, x1, z1, y, GS_BB_SWEEP_N) ? 1 : 0;
            }

            // ★ 生成器节点键
            function __bgKey(w, x, y, z) { return gsBgKey(w, x, y, z); }

            // ★★ `| 0`（ToInt32）语义批量对照
            function __toInt32(vals) {
              var out = [];
              for (var i = 0; i < vals.length; i++) out.push(vals[i] | 0);
              return out;
            }

            function __logLines() { return __log; }
            """;

    // ======================================================================

    public static void main(String[] args) throws Exception {
        System.out.println("=== ListenerF 子系统对账探针：篮球 / 篮球生成器 / 副手钥匙锁 / 孤儿球清扫"
                + "（原脚本 10952~13569）===");
        System.out.println("脚本: " + SCRIPT);
        System.out.println("日志: " + LOG);
        System.out.println();

        String[] raw = readAll(SCRIPT);
        check("脚本存在且行数 > " + SUB_TO + "（原脚本共 " + raw.length + " 行）", raw.length > SUB_TO);
        if (raw.length <= SUB_TO) {
            summary();
            return;
        }

        // ★ 边界锚点：四个子系统的顶层入口各自在哪一行
        check("第 10952 行是本子系统区间起点（gsBb* 常量段）",
                raw[SUB_FROM - 1].contains("篮球") || raw[SUB_FROM - 1].startsWith("//"));
        check("第 12517 行是 function gsBbStart()",
                raw[12516].trim().equals("function gsBbStart() {"));
        check("第 13148 行是 function gsBgStart()",
                raw[13147].trim().equals("function gsBgStart() {"));
        check("第 13552 行是 function gsKlStart()",
                raw[13551].trim().equals("function gsKlStart() {"));
        check("第 11708 行是 function gsEntPurgeStart()",
                raw[11707].trim().equals("function gsEntPurgeStart() {"));
        // ★★★ 孤儿球清扫必须是**最后一个**顶层调用
        check("第 13167 行是 gsBgStart();", raw[13166].trim().equals("gsBgStart();"));
        check("第 13563 行是 gsKlStart();", raw[13562].trim().equals("gsKlStart();"));
        check("★★ 第 13569 行是 gsEntPurgeStart();（全脚本最后一个顶层调用）",
                raw[13568].trim().equals("gsEntPurgeStart();"));

        Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        ctx.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());

        String prefix = String.join("\n", Arrays.copyOfRange(raw, 0, SUB_TO));
        boolean evalOk = true;
        try {
            ctx.eval(Source.newBuilder("js", prefix, "gs_listener-prefix").build());
        } catch (Throwable t) {
            evalOk = false;
            System.out.println("!! 原脚本前缀求值抛错: " + t);
        }
        check("★ 原脚本前 13569 行能在纯 JS 桥接桩下完整求值（真 JS）", evalOk);
        ctx.eval(Source.newBuilder("js", JS_HELPERS, "helpers").build());

        // ★★ 第二个上下文：把顶层 `gsXxxStart();` 全部注释掉 ⇒ 读到的常量是「声明初值」。
        //    为什么必须这样：完整求值会把 GS_BB_REGISTERED / GS_XX_GEN / GS_ENT_DIS_OK
        //    这些**运行期状态**改掉（启动流程自己写的），拿它们跟 Java 的字段初值比没有意义。
        int startCalls = 0;
        StringBuilder pristine = new StringBuilder();
        for (int i = 0; i < SUB_TO; i++) {
            String line = raw[i];
            if (line.trim().matches("gs[A-Za-z]+Start\\(\\);")) {
                pristine.append("// [探针注释掉] ").append(line).append('\n');
                startCalls++;
            } else {
                pristine.append(line).append('\n');
            }
        }
        check("★ 纯净上下文注释掉了 " + startCalls + " 个顶层 xxxStart(); 调用（应 ≥ 10）",
                startCalls >= 10);

        Context ctxPristine = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build();
        ctxPristine.eval(Source.newBuilder("js", JS_STUBS, "stubs").build());
        boolean pristineOk = true;
        try {
            ctxPristine.eval(Source.newBuilder("js", pristine.toString(), "gs_listener-pristine").build());
        } catch (Throwable t) {
            pristineOk = false;
            System.out.println("!! 纯净上下文求值抛错: " + t);
        }
        check("★ 纯净上下文（顶层调用全注释）也能求值成功", pristineOk);

        constants(ctxPristine);
        logLines(ctx);
        physics(ctx);
        reverse(ctx, String.join("\n", Arrays.copyOfRange(raw, SUB_FROM - 1, SUB_TO)));

        ctx.close();
        ctxPristine.close();
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
    // [A] 常量对账 + 双向完备性
    // ======================================================================

    private static void constants(Context ctx) throws Exception {
        System.out.println("[A] 常量对账：真 JS 绑定表 vs Java 字段（按名按值 + 双向完备性）");

        BbSub bb = new BbSub(new ListenerCore());
        BgSub bg = new BgSub(new ListenerCore(), bb);
        KlSub kl = new KlSub(new ListenerCore(), bb, bg);
        EntPurgeSub ent = new EntPurgeSub(new ListenerCore(), bb, bg);

        Value b = ctx.getBindings("js");

        // ---- JS 侧：从真绑定表枚举（不是硬编码名单！）----
        Set<String> jsNames = new LinkedHashSet<>();
        for (String k : b.getMemberKeys()) {
            for (String p : PREFIXES) {
                if (k.startsWith(p)) {
                    jsNames.add(k);
                }
            }
        }
        check("JS 侧枚举到 " + jsNames.size() + " 个 GS_(BB|BG|KL|ENT)_* 顶层变量（应 > 60）",
                jsNames.size() > 60);

        // ---- Java 侧：反射枚举四个类的字段 ----
        Class<?>[] classes = {BbSub.class, BgSub.class, KlSub.class, EntPurgeSub.class};
        Object[] insts = {bb, bg, kl, ent};
        Map<String, Field> javaFields = new LinkedHashMap<>();
        Map<String, Object> javaInsts = new LinkedHashMap<>();
        for (int i = 0; i < classes.length; i++) {
            for (Field f : classes[i].getDeclaredFields()) {
                for (String p : PREFIXES) {
                    if (f.getName().startsWith(p)) {
                        f.setAccessible(true);
                        javaFields.put(f.getName(), f);
                        javaInsts.put(f.getName(), insts[i]);
                    }
                }
            }
        }
        check("Java 侧反射到 " + javaFields.size() + " 个同前缀字段", javaFields.size() > 60);

        // ---- 按名按值比对 ----
        int n = 0;
        int mismatch = 0;
        for (String name : jsNames) {
            if (JS_ONLY.containsKey(name)) {
                continue;                             // 已登记为「故意不移植」
            }
            if (CONTAINERS.contains(name)) {
                continue;                             // 空容器无法按值比，另测类型
            }
            n++;
            Object jsVal = toJava(b.getMember(name));
            Field f = javaFields.get(name);
            if (f == null) {
                check(name + "（Java 里有同名字段）", false);
                mismatch++;
                continue;
            }
            Object javaVal;
            try {
                javaVal = Modifier.isStatic(f.getModifiers()) ? f.get(null) : f.get(javaInsts.get(name));
            } catch (Throwable t) {
                check(name + "（字段可读）", false);
                mismatch++;
                continue;
            }
            boolean ok = sameValue(jsVal, javaVal);
            if (!ok) {
                check(name + " = " + show(javaVal) + "   JS=" + show(jsVal), false);
                mismatch++;
            }
        }
        check("★★ 按名按值对账 " + n + " 个变量，全部一致（不一致 " + mismatch + " 个）", mismatch == 0);
        System.out.println("      · 已逐个比对 " + n + " 个（另有 " + JS_ONLY.size()
                + " 个登记为 JS 独有、故意不移植）");

        // ---- 双向完备性 ----
        Set<String> jsOnlyActual = new LinkedHashSet<>(jsNames);
        jsOnlyActual.removeAll(javaFields.keySet());
        Set<String> expected = new LinkedHashSet<>(JS_ONLY.keySet());
        check("★★ JS 独有集合 == 白名单（JS 独有实际 " + jsOnlyActual + "）",
                jsOnlyActual.equals(expected));
        for (String k : JS_ONLY.keySet()) {
            System.out.println("      · JS 独有（故意不移植） " + k + " —— " + JS_ONLY.get(k));
        }

        Set<String> javaOnlyActual = new LinkedHashSet<>(javaFields.keySet());
        javaOnlyActual.removeAll(jsNames);
        check("★★ Java 侧没有凭空造出来的常量（多出来的是 " + javaOnlyActual + "）",
                javaOnlyActual.isEmpty());

        // ---- 容器类变量：只断言类型（JS 的 {} vs Java 的 Map 无法按 toString 比）----
        String[] containers = {"GS_BB_ST", "GS_BB_CLICK_AT", "GS_BB_BARS",
                "GS_BG_NODES", "GS_BG_CLICK_AT", "GS_KL_HINT_AT"};
        for (String c : containers) {
            Field f = javaFields.get(c);
            boolean isMap = f != null && Map.class.isAssignableFrom(f.getType());
            boolean jsIsObj = b.hasMember(c) && b.getMember(c).hasMembers();
            check("容器 " + c + "：JS 是对象 且 Java 是 Map", isMap && jsIsObj);
        }

        // ---- NamespacedKey / Class 常量：按 toString 比 ----
        String[] keys = {"GS_BB_K_GEN", "GS_BB_K_BALL", "GS_ENT_TAG",
                "GS_BG_K_GEN", "GS_BG_K_NODES", "GS_BG_K_BALL"};
        for (String k : keys) {
            String jsStr = String.valueOf(b.getMember(k));
            Field f = javaFields.get(k);
            String jv = "<无字段>";
            if (f != null) {
                try {
                    jv = String.valueOf(f.get(null));
                } catch (Throwable t) {
                    jv = "<读不到>";
                }
            }
            check(k + " = " + jv, jsStr.equals(jv));
        }

        // ★ PDC 键名逐个钉住（防止「顺手改键名」这种静默不兼容）
        check("GS_BB_K_GEN = gs:bb_gen", b.getMember("GS_BB_K_GEN").toString().equals("gs:bb_gen"));
        check("GS_BB_K_BALL = gs:bb_ball", b.getMember("GS_BB_K_BALL").toString().equals("gs:bb_ball"));
        check("GS_ENT_TAG = gs:ent_ball", b.getMember("GS_ENT_TAG").toString().equals("gs:ent_ball"));
        check("GS_BG_K_GEN = gs:bg_gen", b.getMember("GS_BG_K_GEN").toString().equals("gs:bg_gen"));
        check("GS_BG_K_NODES = gs:bg_nodes", b.getMember("GS_BG_K_NODES").toString().equals("gs:bg_nodes"));
        check("GS_BG_K_BALL = gs:bg_ball", b.getMember("GS_BG_K_BALL").toString().equals("gs:bg_ball"));

        // ★★ 两套副手球不能串台：生成器的球材质必须与律师函的不同
        check("★★ 两套副手球材质不同（律师函 " + BbSub.GS_BB_BALL_MAT
                        + " ≠ 生成器 " + BgSub.GS_BG_BALL_MAT + "）",
                !BbSub.GS_BB_BALL_MAT.equals(BgSub.GS_BG_BALL_MAT));
        check("★★ 两套副手球 PDC 键不同",
                !BbSub.GS_BB_K_BALL.toString().equals(BgSub.GS_BG_K_BALL.toString()));
        check("★★ 生成器的心跳世代键不复用律师函的",
                !BgSub.GS_BG_K_GEN.toString().equals(BbSub.GS_BB_K_GEN.toString()));

        // ★ 数组常量的逐元素比对（sameValue 已按数组处理，这里额外展示一下）
        check("GS_BB_SOLID_KEYS = " + Arrays.toString(BbSub.GS_BB_SOLID_KEYS),
                Arrays.equals(BbSub.GS_BB_SOLID_KEYS,
                        new String[]{"PANE", "BARS", "CHAIN", "FENCE", "WALL", "SLAB", "STAIRS",
                                "DOOR", "TRAPDOOR", "GLASS"}));
        check("GS_BB_SOFT_EXCEPT = " + Arrays.toString(BbSub.GS_BB_SOFT_EXCEPT),
                Arrays.equals(BbSub.GS_BB_SOFT_EXCEPT,
                        new String[]{"SIGN", "TORCH", "BANNER", "HANGING", "CORAL", "FAN"}));
        System.out.println();
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
        check("真 JS 一共打了 " + js.size() + " 行日志", js.size() > 5);

        String jsReg = findInfo(js, "律师函监听器注册成功");
        String jsBbStart = findInfo(js, "你等着收我的律师函吧");
        String jsBgStart = findInfo(js, "篮球生成器（GS_BASKET_GEN）已加载");
        String jsKlStart = findInfo(js, "副手「篮球」钥匙锁已加载");
        String jsEntStart = findInfo(js, "展示实体「孤儿球」清扫已加载");

        check("真 JS 打出了「律师函监听器注册成功」那一行", jsReg != null);
        check("真 JS 打出了「你等着收我的律师函吧…已加载」那一行", jsBbStart != null);
        check("真 JS 打出了「篮球生成器（GS_BASKET_GEN）已加载」那一行", jsBgStart != null);
        check("真 JS 打出了「副手「篮球」钥匙锁已加载」那一行", jsKlStart != null);
        check("真 JS 打出了「展示实体「孤儿球」清扫已加载」那一行", jsEntStart != null);

        checkEq("Java 注册行 == 真 JS 注册行", P() + BbSub.gsBbRegisterLine(), jsReg, "JS");
        checkEq("Java 启动行 == 真 JS 启动行（篮球）", P() + BbSub.gsBbStartLine(), jsBbStart, "JS");
        checkEq("Java 启动行 == 真 JS 启动行（生成器）", P() + BgSub.gsBgStartLine(), jsBgStart, "JS");
        checkEq("Java 注册行 == 真 JS 注册行（钥匙锁）", P() + KlSub.gsKlRegisterLine(6), jsKlStart, "JS");
        checkEq("Java 启动行 == 真 JS 启动行（孤儿球清扫）",
                P() + EntPurgeSub.gsEntPurgeStartLine(0, Boolean.TRUE), jsEntStart, "JS");
        // ★ 反向：Java 的 xxxLine() 本身**不带**前缀（前缀由 gsDaLog 加），别重复加
        check("★★ Java 的 gsBbRegisterLine() 自身不含 [构思技艺] 前缀（前缀由 gsDaLog 统一加）",
                !BbSub.gsBbRegisterLine().startsWith("[构思技艺]"));

        // ---- 参考服日志 ----
        String[] ref = readAll(LOG);
        String refReg = findLine(ref, "律师函监听器注册成功");
        String refBb = findLine(ref, "你等着收我的律师函吧");
        String refBg = findLine(ref, "篮球生成器（GS_BASKET_GEN）已加载");
        String refKl = findLine(ref, "副手「篮球」钥匙锁已加载");
        String refEnt = findLine(ref, "展示实体「孤儿球」清扫已加载");

        check("参考服日志有「律师函监听器注册成功」", refReg != null);
        check("参考服日志有「你等着收我的律师函吧…」", refBb != null);
        check("参考服日志有「篮球生成器…已加载」", refBg != null);
        check("参考服日志有「副手「篮球」钥匙锁已加载」", refKl != null);
        check("参考服日志有「展示实体「孤儿球」清扫已加载」", refEnt != null);

        checkEq("Java 注册行 == 参考服日志行（篮球）", P() + BbSub.gsBbRegisterLine(), refReg, "参考服");
        checkEq("Java 启动行 == 参考服日志行（篮球）", P() + BbSub.gsBbStartLine(), refBb, "参考服");
        checkEq("Java 启动行 == 参考服日志行（生成器）", P() + BgSub.gsBgStartLine(), refBg, "参考服");
        checkEq("Java 注册行 == 参考服日志行（钥匙锁）", P() + KlSub.gsKlRegisterLine(6), refKl, "参考服");
        checkEq("Java 启动行 == 参考服日志行（孤儿球清扫）",
                P() + EntPurgeSub.gsEntPurgeStartLine(0, Boolean.TRUE), refEnt, "参考服");

        // ---- 关键子串（逐字）----
        check("★ 注册行含「空手挥手兜底 = 开」", jsReg != null && jsReg.endsWith("空手挥手兜底 = 开）"));
        check("★ 篮球启动行含「直径 0.48 格」（JS 数值语义：0.24*2 → toFixed(2)）",
                jsBbStart != null && jsBbStart.contains("直径 0.48 格"));
        check("★ 篮球启动行含「绑定 10 秒」（10000/1000 → \"10\" 不是 \"10.0\"）",
                jsBbStart != null && jsBbStart.contains("绑定 10 秒"));
        check("★ 篮球启动行含「弹起 0.3 逐次 ×0.6」（0.30 → \"0.3\"）",
                jsBbStart != null && jsBbStart.contains("弹起 0.3 逐次 ×0.6"));
        check("★ 篮球启动行含「上限 0.25」", jsBbStart != null && jsBbStart.contains("上限 0.25"));
        check("★ 篮球启动行含「超时落点 2 格」（2.0 → \"2\" 不是 \"2.0\"）",
                jsBbStart != null && jsBbStart.contains("超时落点 2 格"));
        check("★ 生成器启动行含「上方 1.5 格」与「超出 32 格」",
                jsBgStart != null && jsBgStart.contains("上方 1.5 格") && jsBgStart.contains("超出 32 格"));
        check("★ 钥匙锁行含「（6/6 条事件）」与「+ 0.5 秒清残留」（500/1000 → \"0.5\"）",
                jsKlStart != null && jsKlStart.contains("（6/6 条事件）")
                        && jsKlStart.contains("+ 0.5 秒清残留"));
        check("★ 孤儿球行含「启动清了 0 个残留」与「每 1 秒扫一次孤儿」（20/20.0 → \"1\"）",
                jsEntStart != null && jsEntStart.contains("启动清了 0 个残留")
                        && jsEntStart.contains("每 1 秒扫一次孤儿"));
        check("★ 孤儿球行含「关服清扫监听 = 已注册」",
                jsEntStart != null && jsEntStart.contains("关服清扫监听 = 已注册"));
        check("★ 孤儿球行以「别人的展示实体一律不碰」收尾",
                jsEntStart != null && jsEntStart.endsWith("别人的展示实体一律不碰"));

        System.out.println("      · JS 篮球注册行: " + jsReg);
        System.out.println("      · JS 篮球启动行: " + jsBbStart);
        System.out.println("      · JS 钥匙锁行  : " + jsKlStart);
        System.out.println("      · JS 孤儿球行  : " + jsEntStart);
        System.out.println();
    }

    // ======================================================================
    // [C] 数值 / 几何 / 物理
    // ======================================================================

    private static void physics(Context ctx) throws Exception {
        System.out.println("[C] 数值 / 几何 / 物理：真 JS 函数（mock 世界）vs Java 同一算式");

        // ---------- C1. `| 0`（ToInt32 回绕）语义 ----------
        toInt32(ctx);

        // ---------- C2. 材质名挡球清单 ----------
        nameSolid(ctx);

        // ---------- C3. 扫掠落地 / 找地面 / 扫掠撞墙 ----------
        sweeps(ctx);

        // ---------- C4. 落地弹跳 ----------
        land(ctx);

        // ---------- C5. 投篮（准星三维方向 + 出手点 + 冷却）----------
        shoot(ctx);

        // ---------- C6. 完整飞行轨迹（两段式 + 弹跳 + 停住）----------
        flight(ctx);

        // ---------- C7. 超时落点 / 触碰 / 送回半径 ----------
        misc(ctx);

        System.out.println();
    }

    /**
     * ★★★ {@code | 0}（JS 的 ToInt32）与 Java 的 {@code (int)} 窄化必须<b>逐位等价</b>。
     *
     * <p>这是整个子系统<b>最容易移植错</b>的地方：原脚本有 6 处把毫秒时间戳先
     * {@code | 0} 再比较，回绕后阈值恒不成立 ⇒ 那些「冷却 / 去抖 / 周期」在真 JS 里
     * <b>全部恒不生效</b>。Java 侧一律用 {@code (int)} 复刻。
     */
    private static void toInt32(Context ctx) throws Exception {
        double[] vals = {
                0.0, 1.0, -1.0, 2147483647.0, 2147483648.0, -2147483648.0, -2147483649.0,
                4294967296.0, 4294967297.0, 1.0e12,
                1759999999999.0, 1760000000000.0, 1700000000000.0, 1893456000000.0,
                1234567890123.0, 999999999999.0, 2500.0, 300.0, 350.0, 500.0, 120.0,
                2.0e12, 4.2949673e9, -1.5, 1.5, 0.5, -0.5, 1.0e18};
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vals.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vals[i]);
        }
        sb.append(']');
        Value out = ctx.eval("js", "__toInt32(" + sb + ")");
        int bad = 0;
        for (int i = 0; i < vals.length; i++) {
            int js = (int) out.getArrayElement(i).asLong();
            int jv = jsToInt32(vals[i]);
            if (js != jv) {
                bad++;
                System.out.println("      !! " + vals[i] + " : JS|0=" + js + " jsToInt32=" + jv);
            }
        }
        check("★★ `| 0` vs `jsToInt32` 逐位一致（" + vals.length + " 个样本，含真实毫秒时间戳）", bad == 0);
        // ★ 反向：Java 的 (int) 直接窄化 double 会**饱和**而不是回绕 ⇒ 绝不能用
        check("★★ 反向：`(int) 1.76e12` 饱和成 Integer.MAX_VALUE（≠ JS 的 | 0）",
                (int) 1760000000000.0 == Integer.MAX_VALUE
                        && jsToInt32(1760000000000.0) != Integer.MAX_VALUE);
        check("★★ 反向：`(int) 1.0e18` 也饱和（所以探针必须用 jsToInt32）",
                (int) 1.0e18 == Integer.MAX_VALUE);
        // ★ 生产代码里字段是 long ⇒ `(int) longValue` 是真正的回绕窄化
        check("★★ 生产代码的字段是 long ⇒ `(int)` 窄化等价于 ToInt32（不是饱和）",
                (int) 1760000000000L == jsToInt32(1760000000000.0));

        // ★ 反向钉住：真 JS 里那 6 处冷却确实「恒不生效」
        long now = 1760000000000L;
        check("★★ 时间戳回绕使 GS_BB_SHOT_CD(350) 恒不生效（now-(int)now ≫ 350）",
                (now - (int) now) >= BbSub.GS_BB_SHOT_CD);
        check("★★ 时间戳回绕使 GS_BB_REBIND_CD(300) 恒不生效",
                (now - (int) now) >= BbSub.GS_BB_REBIND_CD);
        check("★★ 时间戳回绕使 GS_KL_HINT_MS(2500) 恒不生效",
                (now - (int) now) >= KlSub.GS_KL_HINT_MS);
        check("★★ 时间戳回绕使 GS_KL_SWEEP_MS(500) 恒不生效",
                (now - (int) now) >= KlSub.GS_KL_SWEEP_MS);
        check("★★ 时间戳回绕使 GS_BG 的 120ms 去抖恒不生效", (now - (int) now) >= 120);
        check("★★ 时间戳回绕使 gsBbPulse 的 2000ms 兜底清扫周期恒不生效",
                (now - (int) now) >= 2000);

        // ★ 唯一没有 | 0 的那处：gsBbClickOnce 的 80ms（它真的生效）
        String region = regionText();
        check("★ gsBbClickOnce 的 80ms 去抖**没有** | 0（真 JS 里它确实生效）",
                region.contains("if (last != null && (now - last) < 80)"));
    }

    /**
     * C2：材质名挡球清单（薄方块兜底 + 例外排除）。
     *
     * <p>★★ 用 {@link Material#values()} <b>全枚举</b>跑一遍（约 1500 个真材质），
     * 而不是手挑几个名字 —— 这样「例外优先于关键词」「多个关键词同时命中」
     * 这些组合全部被真实材质覆盖到。mock 方块的 {@code getType()} 返回<b>真 Material</b>
     * ⇒ Java 侧读到的名字与 JS 侧读到的字符串是同一个来源。
     */
    private static void nameSolid(Context ctx) throws Exception {
        Material[] mats = Material.values();
        int badName = 0;
        int badSolid = 0;
        int solidTrue = 0;
        for (Material m : mats) {
            String n = m.name();
            int jsN = ctx.eval("js", "__nameSolid('" + n + "')").asInt();
            int jvN = BbSub.gsBbNameSolid(fakeBlock(true, m, n)) ? 1 : 0;
            if (jsN != jvN) {
                badName++;
                if (badName <= 5) {
                    System.out.println("      !! nameSolid(" + n + ") JS=" + jsN + " Java=" + jvN);
                }
            }
            for (int pass = 0; pass <= 1; pass++) {
                int jsS = ctx.eval("js", "__solid(" + (pass == 1) + ",'" + n + "')").asInt();
                int jvS = BbSub.gsBbSolid(fakeBlock(pass == 1, m, n)) ? 1 : 0;
                if (jsS != jvS) {
                    badSolid++;
                    if (badSolid <= 5) {
                        System.out.println("      !! solid(passable=" + (pass == 1) + "," + n
                                + ") JS=" + jsS + " Java=" + jvS);
                    }
                }
                if (pass == 1 && jvS == 1) {
                    solidTrue++;
                }
            }
        }
        check("★★ gsBbNameSolid 与 Java 一致（全枚举 " + mats.length + " 个真 Material）", badName == 0);
        check("★★ gsBbSolid 与 Java 一致（" + mats.length + " × 2 种 isPassable）", badSolid == 0);
        check("★ 清单确实在起作用（isPassable=true 时仍有 " + solidTrue + " 个材质被判挡球，应 > 50）",
                solidTrue > 50);

        // ★ 反向钉住几条语义
        check("★ 玻璃板（GLASS_PANE）算挡球（薄方块兜底生效，isPassable 说能过也拦）",
                BbSub.gsBbSolid(fakeBlock(true, Material.GLASS_PANE, "GLASS_PANE")));
        check("★ 墙上的告示牌（OAK_WALL_SIGN）不算挡球（例外 SIGN 优先于关键词 WALL）",
                !BbSub.gsBbSolid(fakeBlock(true, Material.OAK_WALL_SIGN, "OAK_WALL_SIGN")));
        check("★ 挂墙的悬挂告示牌（同时含 WALL + HANGING + SIGN）也不算挡球",
                !BbSub.gsBbNameSolid(fakeBlock(true, Material.OAK_WALL_HANGING_SIGN,
                        "OAK_WALL_HANGING_SIGN")));
        check("★ 石头（STONE）在 isPassable=true 时不算挡球（不命中清单）",
                !BbSub.gsBbSolid(fakeBlock(true, Material.STONE, "STONE")));
        check("★ 石头（STONE）在 isPassable=false 时算挡球",
                BbSub.gsBbSolid(fakeBlock(false, Material.STONE, "STONE")));
        check("★ 空气（AIR）在 isPassable=true 时不算挡球",
                !BbSub.gsBbSolid(fakeBlock(true, Material.AIR, "AIR")));
    }

    /** C3：扫掠落地 / 找地面 / 扫掠撞墙。 */
    private static void sweeps(Context ctx) throws Exception {
        // 地板顶面在 y=64（即 y<64 是 STONE）
        String[] cfgs = {"{floorY:64}", "{}", "{paneX:3}"};
        String[] cfgJava = {"floor64", "none", "pane3"};

        int badFloor = 0;
        int badSweep = 0;
        int badWall = 0;

        for (int c = 0; c < cfgs.length; c++) {
            for (double x = 0.0; x <= 3.0; x += 0.5) {
                for (double yTop = 60.0; yTop <= 70.0; yTop += 1.0) {
                    int js = ctx.eval("js", "__floor(" + cfgs[c] + "," + x + "," + yTop + ",0.5,24)").asInt();
                    Double jvD = BbSub.gsBbFloor(javaWorld(cfgJava[c]), x, yTop, 0.5, 24);
                    int jv = (jvD == null) ? -99999 : (int) Math.round(jvD);
                    if (js != jv) {
                        badFloor++;
                        if (badFloor <= 5) {
                            System.out.println("      !! floor(cfg" + c + "," + x + "," + yTop
                                    + ") JS=" + js + " Java=" + jv);
                        }
                    }
                }
                for (double yPrev = 62.0; yPrev <= 72.0; yPrev += 1.0) {
                    for (double yNow = 55.0; yNow <= yPrev; yNow += 2.0) {
                        int js = ctx.eval("js", "__floorSweep(" + cfgs[c] + "," + x + ","
                                + yPrev + "," + yNow + ",0.5)").asInt();
                        Double jvD = BbSub.gsBbFloorSweep(javaWorld(cfgJava[c]), x, yPrev, yNow, 0.5,
                                BbSub.GS_BB_R);
                        int jv = (jvD == null) ? -99999 : (int) Math.round(jvD);
                        if (js != jv) {
                            badSweep++;
                            if (badSweep <= 5) {
                                System.out.println("      !! floorSweep(cfg" + c + "," + x + ","
                                        + yPrev + "," + yNow + ") JS=" + js + " Java=" + jv);
                            }
                        }
                    }
                }
            }
        }
        check("★★ gsBbFloor 与 Java 一致（3 种世界 × 坐标网格）", badFloor == 0);
        check("★★ gsBbFloorSweep 与 Java 一致（含「一步跨过整块地板」的隧穿场景）", badSweep == 0);

        // 撞墙扫掠：一堵 x=3 的玻璃板薄墙（y 60~68）
        for (double x0 = 0.0; x0 <= 4.0; x0 += 0.5) {
            for (double x1 = 0.0; x1 <= 4.0; x1 += 0.5) {
                for (double y = 58.0; y <= 70.0; y += 1.0) {
                    int js = ctx.eval("js", "__wallSweep({paneX:3}," + x0 + ",0.5," + x1 + ",0.5," + y + ")").asInt();
                    boolean jv = BbSub.gsBbWallSweep(javaWorld("pane3"), x0, 0.5, x1, 0.5, y,
                            BbSub.GS_BB_SWEEP_N);
                    if (js != (jv ? 1 : 0)) {
                        badWall++;
                        if (badWall <= 5) {
                            System.out.println("      !! wallSweep(" + x0 + "→" + x1 + ",y=" + y
                                    + ") JS=" + js + " Java=" + (jv ? 1 : 0));
                        }
                    }
                }
            }
        }
        check("★★ gsBbWallSweep 与 Java 一致（含「一步跨过一格薄墙」的隧穿场景）", badWall == 0);
        check("★ 一步跨过薄墙时扫掠能抓到（x 0.5→3.5, y=64）",
                BbSub.gsBbWallSweep(javaWorld("pane3"), 0.5, 0.5, 3.5, 0.5, 64.0, BbSub.GS_BB_SWEEP_N));
    }

    /** C4：落地弹跳（衰减 / 保速 / 上限 / 撞墙原地弹）。 */
    private static void land(Context ctx) throws Exception {
        int bad = 0;
        int cases = 0;
        double[] vxs = {0.0, 0.1, 0.3, 0.6, 1.25, -1.25, 2.0};
        double[] vzs = {0.0, 0.2, 0.5, 1.0, -0.8};
        for (int bnc = -1; bnc <= 3; bnc++) {
            for (int hw = 0; hw <= 1; hw++) {
                for (double vx : vxs) {
                    for (double vz : vzs) {
                        for (double fy = 60.0; fy <= 66.0; fy += 2.0) {
                            cases++;
                            Value js = ctx.eval("js", "__land(" + bnc + "," + (hw == 1) + ","
                                    + vx + "," + vz + "," + fy + ")");
                            double[] jsv = new double[11];
                            for (int i = 0; i < 11; i++) {
                                jsv[i] = js.getArrayElement(i).asDouble();
                            }
                            BbSub.Ball st = new BbSub.Ball();
                            st.mode = "shot";
                            st.x = 0.0;
                            st.y = 0.0;
                            st.z = 0.0;
                            st.vx = vx;
                            st.vy = 0.0;
                            st.vz = vz;
                            st.bnc = bnc;
                            st.hitWall = (hw == 1);
                            st.life = 5;
                            st.sy0 = 0.0;
                            BbSub.gsBbLand(st, fy);
                            double[] jv = {st.x, st.y, st.z, st.vx, st.vy, st.vz,
                                    modeNum(st.mode), st.life, st.bnc, st.hitWall ? 1 : 0, st.sy0};
                            boolean ok = true;
                            for (int i = 0; i < 11; i++) {
                                if (jsv[i] != jv[i]) {
                                    ok = false;
                                }
                            }
                            if (!ok) {
                                bad++;
                                if (bad <= 5) {
                                    System.out.println("      !! land(bnc=" + bnc + ",hw=" + hw
                                            + ",vx=" + vx + ",vz=" + vz + ",fy=" + fy + ")");
                                    System.out.println("         JS  =" + Arrays.toString(jsv));
                                    System.out.println("         Java=" + Arrays.toString(jv));
                                }
                            }
                        }
                    }
                }
            }
        }
        check("★★ gsBbLand 与 Java 逐值一致（" + cases + " 组：bnc × hitWall × vx × vz × floorY）", bad == 0);

        // ★ 反向钉住三条物理结论
        BbSub.Ball a = new BbSub.Ball();
        a.mode = "shot";
        a.vx = 1.25;
        a.vz = 0.0;
        a.vy = 0.0;
        a.bnc = BbSub.GS_BB_BOUNCES;
        a.hitWall = false;
        a.life = 5;
        BbSub.gsBbLand(a, 64.0);
        check("★ 第一次弹起 vy = GS_BB_BOUNCE_UP(0.3)，水平速度被压到上限 0.25",
                a.vy == BbSub.GS_BB_BOUNCE_UP && Math.abs(a.vx - BbSub.GS_BB_BOUNCE_MAX) < 1.0e-12);
        check("★ 弹跳后 life = GS_BB_STRAIGHT+1（必须越过直线段，否则球会直冲上天）",
                a.life == BbSub.GS_BB_STRAIGHT + 1);

        BbSub.Ball b2 = new BbSub.Ball();
        b2.mode = "shot";
        b2.vx = 1.25;
        b2.vz = 0.0;
        b2.vy = 0.0;
        b2.bnc = BbSub.GS_BB_BOUNCES;
        b2.hitWall = true;
        b2.life = 5;
        BbSub.gsBbLand(b2, 64.0);
        check("★ 撞过墙 ⇒ 原地弹（vx = vz = 0）", b2.vx == 0.0 && b2.vz == 0.0);

        BbSub.Ball c = new BbSub.Ball();
        c.mode = "shot";
        c.vx = 1.0;
        c.vz = 0.0;
        c.vy = 0.0;
        c.bnc = 0;
        c.hitWall = false;
        c.life = 5;
        BbSub.gsBbLand(c, 64.0);
        check("★ bnc 用完 ⇒ 直接 rest（mode=rest, y = floorY + R）",
                "rest".equals(c.mode) && c.y == 64.0 + BbSub.GS_BB_R);
    }

    /** C5：投篮（准星三维方向 + 出手点 + 冷却 + 副手球/血条只有律师函那颗才有）。 */
    private static void shoot(Context ctx) throws Exception {
        // yaw/pitch 网格 —— 两边各自算 getDirection()
        double[] yaws = {0.0, 30.0, 45.0, 90.0, 135.0, 180.0, -90.0, -135.0, 179.0};
        double[] pitches = {-90.0, -60.0, -30.0, 0.0, 30.0, 60.0, 90.0};
        double[] pys = {64.0, 65.5, 70.0};
        int bad = 0;
        int cases = 0;
        for (double yaw : yaws) {
            for (double pitch : pitches) {
                for (double py : pys) {
                    for (int lawyer = 0; lawyer <= 1; lawyer++) {
                        for (long prevAt : new long[]{0L, 1760000000000L, 1760000000000L - 100L}) {
                            cases++;
                            double[] d = dirOf(yaw, pitch);
                            String jsExpr = "__shoot({}," + 10.0 + "," + py + "," + 20.0 + ","
                                    + d[0] + "," + d[1] + "," + d[2] + "," + (lawyer == 1) + ","
                                    + prevAt + ")";
                            Value js = ctx.eval("js", jsExpr);
                            double[] jsv = new double[12];
                            for (int i = 0; i < 12; i++) {
                                jsv[i] = js.getArrayElement(i).asDouble();
                            }
                            BbSub bb = new BbSub(new ListenerCore());
                            bb.GS_BB_ST.clear();
                            bb.GS_BB_BARS.clear();
                            BbSub.Ball st = new BbSub.Ball();
                            st.mode = "bound";
                            st.x = 10.0;
                            st.y = py;
                            st.z = 20.0;
                            st.shotAt = prevAt;
                            if (lawyer == 1) {
                                bb.GS_BB_ST.put(SELF_ID, st);
                            }
                            Player p = fakePlayerWith(10.0, py, 20.0, (float) yaw, (float) pitch);
                            boolean ok = bb.gsBbShootState(p, st);
                            double[] jv = {(ok ? 1 : 0), st.x, st.y, st.z, st.vx, st.vy, st.vz,
                                    modeNum(st.mode), st.life, st.bnc, st.hitWall ? 1 : 0, st.sy0};
                            boolean same = true;
                            for (int i = 0; i < 12; i++) {
                                if (jsv[i] != jv[i]) {
                                    same = false;
                                }
                            }
                            if (!same) {
                                bad++;
                                if (bad <= 5) {
                                    System.out.println("      !! shoot(yaw=" + yaw + ",pitch=" + pitch
                                            + ",py=" + py + ",lawyer=" + lawyer + ",prev=" + prevAt + ")");
                                    System.out.println("         JS  =" + Arrays.toString(jsv));
                                    System.out.println("         Java=" + Arrays.toString(jv));
                                }
                            }
                        }
                    }
                }
            }
        }
        check("★★ gsBbShootState 与 Java 逐值一致（" + cases
                + " 组：yaw × pitch × 高度 × 是否律师函球 × 上次投篮时间）", bad == 0);

        // ★ 反向钉住几何结论
        double[] d0 = dirOf(0.0, 0.0);
        check("★ 平视（pitch=0, yaw=0）方向 ≈ (0,0,1)", Math.abs(d0[0]) < 1e-12
                && Math.abs(d0[1]) < 1e-12 && Math.abs(d0[2] - 1.0) < 1e-12);

        BbSub bb = new BbSub(new ListenerCore());
        BbSub.Ball st = new BbSub.Ball();
        st.mode = "bound";
        st.y = 64.0;
        bb.GS_BB_ST.put(SELF_ID, st);
        bb.gsBbShootState(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), st);
        check("★ 出手点 = 玩家坐标 + 水平前移 GS_BB_SHOT_FWD(0.6)（yaw=0 朝 +Z ⇒ x 不动、z 前移 0.6）",
                st.x == 0.0 && st.y == 64.0 + BbSub.GS_BB_HAND_UP
                        && Math.abs(st.z - BbSub.GS_BB_SHOT_FWD) < 1e-12);
        check("★ 初速 = 方向 × GS_BB_SHOT_SPD(1.25)",
                Math.abs(st.vz - BbSub.GS_BB_SHOT_SPD) < 1e-12);

        // ★ 正上看（Minecraft pitch = -90 = 抬头）⇒ 水平位移 0、vy 为正
        BbSub.Ball up = new BbSub.Ball();
        up.mode = "bound";
        up.y = 64.0;
        BbSub bb2 = new BbSub(new ListenerCore());
        bb2.GS_BB_ST.put(SELF_ID, up);
        bb2.gsBbShootState(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, -90.0f), up);
        check("★ 抬头看（pitch=-90）时水平位移为 0、vy 为正（高抛）",
                up.x == 0.0 && up.z == 0.0 && up.vy > 0);
        // ★ 正下看（pitch = +90）⇒ 水平位移 0、vy 为负
        BbSub.Ball dn = new BbSub.Ball();
        dn.mode = "bound";
        dn.y = 64.0;
        BbSub bb2b = new BbSub(new ListenerCore());
        bb2b.GS_BB_ST.put(SELF_ID, dn);
        bb2b.gsBbShootState(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 90.0f), dn);
        check("★ 低头看（pitch=+90）时水平位移为 0、vy 为负（假动作）",
                dn.x == 0.0 && dn.z == 0.0 && dn.vy < 0);

        // ★ 350ms 冷却在真 JS 里恒不生效 ⇒ 连投两次都要成功
        BbSub bb3 = new BbSub(new ListenerCore());
        BbSub.Ball s1 = new BbSub.Ball();
        s1.mode = "bound";
        s1.y = 64.0;
        bb3.GS_BB_ST.put(SELF_ID, s1);
        Player p3 = fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f);
        boolean f1 = bb3.gsBbShootState(p3, s1);
        s1.mode = "bound";                       // 手工复位（真流程里由下一轮绑定给）
        boolean f2 = bb3.gsBbShootState(p3, s1);
        check("★★ 350ms 冷却恒不生效 ⇒ 立刻再投一次也成功（| 0 回绕的忠实复刻）", f1 && f2);
    }

    /** C6：完整飞行轨迹（直线段 → 重力 → 撞墙 → 落地弹两下 → 停住）。 */
    private static void flight(Context ctx) throws Exception {
        // 世界配置：无地板（靠 ny<=0 落地）/ 地板 y=64 / 薄墙 x=3
        String[] cfgs = {"{}", "{floorY:64}", "{floorY:64,paneX:3}"};
        String[] cfgJava = {"none", "floor64", "floor64pane3"};

        // 初始状态网格：(x,y,z,vx,vy,vz,bnc,hitWall,life,sy0)
        double[][] inits = {
                {0.5, 66.0, 0.5, 0.0, 0.0, 1.25, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, 1.25, 0.0, 0.0, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, 0.0, 1.25, 0.0, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, 0.0, -1.25, 0.0, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, 0.6, 0.9, 0.6, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, -0.9, 0.5, 0.5, 2, 0, 0, 66.0},
                {0.5, 70.0, 0.5, 1.0, -0.2, 0.3, 0, 0, 0, 70.0},
                {0.5, 66.0, 0.5, 0.0, 0.0, 0.0, 2, 0, 0, 66.0},
                {0.5, 66.0, 0.5, 1.25, 0.0, 0.0, 0, 1, 0, 66.0},
                {0.5, 68.0, 0.5, 0.05, -0.02, 0.05, 1, 0, 11, 68.0},
        };

        int bad = 0;
        int rows = 0;
        int firstBadCfg = -1;
        for (int c = 0; c < cfgs.length; c++) {
            for (double[] init : inits) {
                String jsExpr = "__flight(" + cfgs[c] + ",[" + init[0] + "," + init[1] + ","
                        + init[2] + "," + init[3] + "," + init[4] + "," + init[5] + ","
                        + (int) init[6] + "," + (init[7] == 1) + "," + (int) init[8] + ","
                        + init[9] + "],400)";
                Value js = ctx.eval("js", jsExpr);
                int jsN = (int) js.getArraySize();

                // Java 侧同一条轨迹
                BbSub bb = new BbSub(new ListenerCore());
                BbSub.Ball st = new BbSub.Ball();
                st.mode = "shot";
                st.w = javaWorld(cfgJava[c]);
                st.x = init[0];
                st.y = init[1];
                st.z = init[2];
                st.vx = init[3];
                st.vy = init[4];
                st.vz = init[5];
                st.bnc = (int) init[6];
                st.hitWall = init[7] == 1;
                st.life = (int) init[8];
                st.sy0 = init[9];
                List<double[]> jvRows = new ArrayList<>();
                for (int i = 0; i < 400; i++) {
                    bb.gsBbTickShot(null, st);
                    jvRows.add(new double[]{st.x, st.y, st.z, st.vx, st.vy, st.vz,
                            modeNum(st.mode), st.life, st.bnc, st.hitWall ? 1 : 0, st.sy0});
                }

                int n = Math.min(jsN, jvRows.size());
                for (int i = 0; i < n; i++) {
                    rows++;
                    Value row = js.getArrayElement(i);
                    double[] jvRow = jvRows.get(i);
                    boolean same = true;
                    for (int k = 0; k < 11; k++) {
                        if (row.getArrayElement(k).asDouble() != jvRow[k]) {
                            same = false;
                        }
                    }
                    if (!same) {
                        bad++;
                        if (bad <= 3) {
                            firstBadCfg = c;
                            System.out.println("      !! flight(cfg" + c + ",init" + Arrays.toString(init)
                                    + ") 第 " + i + " 刻不一致");
                            StringBuilder a = new StringBuilder();
                            StringBuilder b2 = new StringBuilder();
                            for (int k = 0; k < 11; k++) {
                                a.append(row.getArrayElement(k).asDouble()).append(' ');
                                b2.append(jvRow[k]).append(' ');
                            }
                            System.out.println("         JS  = " + a);
                            System.out.println("         Java= " + b2);
                        }
                    }
                }
                if (jsN != jvRows.size()) {
                    bad++;
                    System.out.println("      !! 轨迹长度不一致 JS=" + jsN + " Java=" + jvRows.size());
                }
            }
        }
        check("★★ gsBbTickShot 完整轨迹与 Java 逐刻一致（" + cfgs.length + " 种世界 × "
                + inits.length + " 组初值 × 400 刻 = " + rows + " 个采样点）", bad == 0);

        // ★ 反向钉住物理结论（用一条真实轨迹验证）
        BbSub bb = new BbSub(new ListenerCore());
        BbSub.Ball st = new BbSub.Ball();
        st.mode = "shot";
        st.w = javaWorld("floor64");
        st.x = 0.5;
        st.y = 66.0;
        st.z = 0.5;
        st.vx = 0.0;
        st.vy = 0.0;
        st.vz = 1.25;
        st.bnc = BbSub.GS_BB_BOUNCES;
        st.life = 0;
        st.sy0 = 66.0;
        double yAt12 = 0.0;
        double yAt13 = 0.0;
        int bounces = 0;
        double prevVy = 0.0;
        boolean sawUp = false;
        int restAt = -1;
        for (int i = 1; i <= 400; i++) {
            bb.gsBbTickShot(null, st);
            if (i == 12) {
                yAt12 = st.y;
            }
            if (i == 13) {
                yAt13 = st.y;
            }
            if (prevVy < 0 && st.vy > 0) {
                bounces++;
            }
            if (st.vy > 0) {
                sawUp = true;
            }
            prevVy = st.vy;
            if ("rest".equals(st.mode)) {
                restAt = i;
                break;
            }
        }
        check("★ 直线段（前 12 刻）不受重力：y 不变（" + yAt12 + " == 66.0）", yAt12 == 66.0);
        check("★ 第 13 刻起受重力：y 开始下降（" + yAt13 + " < 66.0）", yAt13 < 66.0);
        check("★ 落地后确实弹起来了（vy 由负转正的次数 = " + bounces + " = GS_BB_BOUNCES）",
                bounces == BbSub.GS_BB_BOUNCES);
        check("★ 弹了两下之后停住（rest，第 " + restAt + " 刻）", restAt > 0);
        check("★ 停住时 y = 地板顶面 + R（" + st.y + "）",
                Math.abs(st.y - (64.0 + BbSub.GS_BB_R)) < 1.0e-12);
        check("★ 没撞墙 ⇒ 球往前滚了一段（z 从 0.5 前进到 " + st.z + "）", st.z > 0.5);
        check("★ 停住时水平速度归零", st.vx == 0.0 && st.vz == 0.0);
    }

    /** C7：超时落点 / 触碰 / 送回半径 / 生成器键。 */
    private static void misc(Context ctx) throws Exception {
        // ---- 超时落点 ----
        int badDrop = 0;
        for (double py = 60.0; py <= 70.0; py += 1.0) {
            for (double sy : new double[]{50.0, 64.5, 70.0, 80.0}) {
                Value js = ctx.eval("js", "__dropNear(10.0," + py + ",20.0," + sy + ")");
                double[] jsv = new double[11];
                for (int i = 0; i < 11; i++) {
                    jsv[i] = js.getArrayElement(i).asDouble();
                }
                BbSub bb = new BbSub(new ListenerCore());
                BbSub.Ball st = new BbSub.Ball();
                st.mode = "bound";
                st.x = 10.0;
                st.y = sy;
                st.z = 20.0;
                bb.gsBbDropNear(fakePlayerWith(10.0, py, 20.0, 0.0f, 0.0f), st);

                // ★ 落点角度是 Math.random() ⇒ 只比「半径」与其余所有字段
                double jsR = Math.hypot(jsv[0] - 10.0, jsv[2] - 20.0);
                double jvR = Math.hypot(st.x - 10.0, st.z - 20.0);
                boolean ok = Math.abs(jsR - jvR) < 1.0e-9
                        && Math.abs(jsR - BbSub.GS_BB_DROP_R) < 1.0e-9
                        && jsv[1] == st.y && jsv[3] == st.vx && jsv[4] == st.vy && jsv[5] == st.vz
                        && jsv[6] == modeNum(st.mode) && jsv[7] == st.life && jsv[8] == st.bnc
                        && jsv[9] == (st.hitWall ? 1 : 0) && jsv[10] == st.sy0;
                if (!ok) {
                    badDrop++;
                    if (badDrop <= 3) {
                        System.out.println("      !! dropNear(py=" + py + ",sy=" + sy + ") JSr="
                                + jsR + " Javar=" + jvR + " JS=" + Arrays.toString(jsv)
                                + " Java y=" + st.y + " vy=" + st.vy);
                    }
                }
            }
        }
        check("★★ gsBbDropNear 与 Java 一致（落点半径恒 = GS_BB_DROP_R，vy = -0.12）", badDrop == 0);

        BbSub bb = new BbSub(new ListenerCore());
        BbSub.Ball st = new BbSub.Ball();
        st.mode = "bound";
        st.y = 50.0;
        st.z = 0.0;
        bb.gsBbDropNear(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), st);
        check("★ 超时落点：球至少从腰高（玩家 y + 0.8）往下掉（" + st.y + "）",
                st.y >= 64.0 + 0.8);
        check("★ 超时落点后 mode = shot、life = 0", "shot".equals(st.mode) && st.life == 0);

        // ---- 触碰（律师函版：水平 1.4 / 竖直 ±1.6）----
        int badTouch = 0;
        double[] offs = {0.0, 0.5, 1.0, 1.3999, 1.4, 1.4001, 1.5, 2.0};
        double[] dys = {-1.7, -1.6, -1.5999, -0.9, 0.0, 0.9, 1.5999, 1.6, 1.7};
        for (double off : offs) {
            for (double dy : dys) {
                int js = ctx.eval("js", "__touch(" + (10.0 + off) + ",20.0," + (64.0 + 0.9 + dy)
                        + ",10.0,64.0,20.0)").asInt();
                BbSub.Ball s2 = new BbSub.Ball();
                s2.x = 10.0 + off;
                s2.y = 64.0 + 0.9 + dy;
                s2.z = 20.0;
                int jv = BbSub.gsBbTouch(fakePlayerWith(10.0, 64.0, 20.0, 0.0f, 0.0f), s2) ? 1 : 0;
                if (js != jv) {
                    badTouch++;
                    System.out.println("      !! touch(off=" + off + ",dy=" + dy + ") JS=" + js + " Java=" + jv);
                }
            }
        }
        check("★★ gsBbTouch 与 Java 一致（水平 1.4 / 竖直 ±1.6 的边界内外都测）", badTouch == 0);
        // ★ 边界用「能被 double 精确表示」的坐标，免得 11.4-10.0 的浮点尾数干扰结论
        check("★ 水平恰好 1.4 ⇒ 仍算碰到（判定是 > 才拒绝）",
                BbSub.gsBbTouch(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), ballAt(1.4, 64.9, 0.0)));
        check("★ 水平 2.0 ⇒ 不算碰到",
                !BbSub.gsBbTouch(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), ballAt(2.0, 64.9, 0.0)));
        check("★ 竖直 1.7 ⇒ 不算碰到",
                !BbSub.gsBbTouch(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), ballAt(0.0, 64.0 + 0.9 + 1.7, 0.0)));
        check("★ 竖直 1.5 ⇒ 算碰到",
                BbSub.gsBbTouch(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), ballAt(0.0, 64.0 + 0.9 + 1.5, 0.0)));
        check("★ 竖直 -1.5 ⇒ 算碰到",
                BbSub.gsBbTouch(fakePlayerWith(0.0, 64.0, 0.0, 0.0f, 0.0f), ballAt(0.0, 64.0 + 0.9 - 1.5, 0.0)));

        // ---- 生成器：触碰 ----
        int badBgTouch = 0;
        for (double off : offs) {
            for (double dy : dys) {
                int js = ctx.eval("js", "__bgTouch(" + (10.0 + off) + ",20.0," + (64.0 + 0.9 + dy)
                        + ",10.0,64.0,20.0)").asInt();
                BbSub.Ball s2 = new BbSub.Ball();
                s2.x = 10.0 + off;
                s2.y = 64.0 + 0.9 + dy;
                s2.z = 20.0;
                int jv = BgSub.gsBgTouch(fakePlayerWith(10.0, 64.0, 20.0, 0.0f, 0.0f), s2) ? 1 : 0;
                if (js != jv) {
                    badBgTouch++;
                }
            }
        }
        check("★★ gsBgTouch 与 Java 一致（同样的边界网格）", badBgTouch == 0);

        // ---- 生成器：送回半径 32 ----
        int badFar = 0;
        double[] dists = {0.0, 31.9, 31.999, 32.0, 32.001, 33.0, 40.0};
        for (double dx : dists) {
            for (double dy : new double[]{0.0, 1.0, -1.0}) {
                int js = ctx.eval("js", "__bgFar(0,64,0," + dx + "," + (64.0 + dy) + ",0)").asInt();
                BgSub.BgNode node = new BgSub.BgNode();
                node.x = 0;
                node.y = 64;
                node.z = 0;
                BbSub.Ball b = new BbSub.Ball();
                b.x = dx;
                b.y = 64.0 + dy;
                b.z = 0.0;
                int jv = BgSub.gsBgFar(node, b) ? 1 : 0;
                if (js != jv) {
                    badFar++;
                    System.out.println("      !! bgFar(dx=" + dx + ",dy=" + dy + ") JS=" + js + " Java=" + jv);
                }
            }
        }
        check("★★ gsBgFar 与 Java 一致（32 格边界内外）", badFar == 0);
        // ★ gsBgFar 量的球心到「方块中心 + 0.5」的三维距离 ⇒ 用竖直方向做干净边界
        check("★ 竖直恰好 32 格 ⇒ 不算超出（判定是 > 才送回）",
                !BgSub.gsBgFar(node(0, 64, 0), ballAt(0.5, 64.0 + 32.0, 0.5)));
        check("★ 竖直 32.001 格 ⇒ 算超出",
                BgSub.gsBgFar(node(0, 64, 0), ballAt(0.5, 64.0 + 32.001, 0.5)));
        check("★ 竖直 31.999 格 ⇒ 不算超出",
                !BgSub.gsBgFar(node(0, 64, 0), ballAt(0.5, 64.0 + 31.999, 0.5)));
        check("★ 距离量的是「球心到方块中心 +0.5」而不是到方块角（x=32.0 时距 31.5 ⇒ 不送回）",
                !BgSub.gsBgFar(node(0, 64, 0), ballAt(32.0, 64.0, 0.5)));
        check("★ 水平 32.501 ⇒ 距 32.001 ⇒ 送回",
                BgSub.gsBgFar(node(0, 64, 0), ballAt(32.501, 64.0, 0.5)));

        // ---- 生成器：节点键 ----
        int badKey = 0;
        double[][] keys = {{0, 64, 0}, {-1.5, 64.7, 3.2}, {1.999, -2.001, 100.0}, {2147483647.0, 0, 0}};
        for (double[] k : keys) {
            String js = ctx.eval("js", "__bgKey('world'," + k[0] + "," + k[1] + "," + k[2] + ")").asString();
            String jv = BgSub.gsBgKey("world", k[0], k[1], k[2]);
            if (!js.equals(jv)) {
                badKey++;
                System.out.println("      !! bgKey(" + Arrays.toString(k) + ") JS=" + js + " Java=" + jv);
            }
        }
        check("★★ gsBgKey 与 Java 一致（含负数 / 小数取整）", badKey == 0);

        // ---- 生成器：新球初始位置 ----
        BgSub bg = new BgSub(new ListenerCore(), bb);
        BgSub.BgNode nd = node(10, 64, -5);
        BbSub.Ball nb = bg.gsBgNewBall(nd);
        check("★ 生成器新球球心 = 方块 + (0.5, GS_BG_UP(1.5), 0.5)",
                nb.x == 10.5 && nb.y == 64.0 + BgSub.GS_BG_UP && nb.z == -4.5);
        check("★ 生成器新球初始 mode = idle、bnc = 0", "idle".equals(nb.mode) && nb.bnc == 0);
    }

    // ======================================================================
    // [D] 反向断言
    // ======================================================================

    private static void reverse(Context ctx, String region) throws Exception {
        System.out.println("[D] 反向断言：不该存在 / 不该改变的东西");

        String bb = readText(BB_SRC);
        String bg = readText(BG_SRC);
        String kl = readText(KL_SRC);
        String ent = readText(ENT_SRC);
        String[] all = {bb, bg, kl, ent};
        String[] names = {"BbSub.java", "BgSub.java", "KlSub.java", "EntPurgeSub.java"};

        // ---- D1. 「律师函」字样一个字都不许改 ----
        check("★★ 脚本里 GS_BB_ID = 'GS_LAWYER_LETTER' 原封不动",
                region.contains("var GS_BB_ID = 'GS_LAWYER_LETTER';"));
        check("★★ Java 里 GS_BB_ID = \"GS_LAWYER_LETTER\" 原封不动",
                bb.contains("String GS_BB_ID = \"GS_LAWYER_LETTER\";"));
        check("★★ 脚本里「你等着收我的律师函吧」原封不动",
                region.contains("你等着收我的律师函吧"));
        check("★★ Java 里「你等着收我的律师函吧」原封不动",
                bb.contains("你等着收我的律师函吧"));
        check("★★ 脚本里「律师函监听器注册成功」原封不动",
                region.contains("律师函监听器注册成功"));
        check("★★ Java 里「律师函监听器注册成功」原封不动",
                bb.contains("律师函监听器注册成功"));
        // ★ 反面：不许出现「篮球监听器注册成功」这种「顺手修正」
        check("★★ Java 里没有「篮球监听器注册成功」这种「顺手修正」",
                !bb.contains("篮球监听器注册成功"));
        check("★★ Java 里没有把 GS_BB_ID 改成 GS_BASKETBALL / GS_BB_BALL 之类",
                !bb.contains("\"GS_BASKETBALL\"") && !bb.contains("GS_BB_ID = \"GS_BB_BALL\""));

        // ---- D2. 钥匙锁恰好 6 条事件 ----
        int klHandlers = countAnnotatedHandlers(KlSub.ListenerImpl.class);
        check("★★ KlSub.ListenerImpl 上恰好 6 个 @EventHandler（日志写「6/6 条事件」）",
                klHandlers == KlSub.KL_EVENT_COUNT && klHandlers == 6);
        check("★★ Java 独有的 KL_EVENT_COUNT 名字**不带** GS_KL_ 前缀（否则会被当成漏拷的常量）",
                KlSub.KL_EVENT_COUNT == 6 && !"GS_KL_EVENT_COUNT".equals("KL_EVENT_COUNT"));
        check("★ BbSub.ListenerImpl 上恰好 2 个 @EventHandler（右键 + 挥手）",
                countAnnotatedHandlers(BbSub.ListenerImpl.class) == 2);
        check("★ BgSub.ListenerImpl 上恰好 4 个 @EventHandler（放置/破坏/右键/挥手）",
                countAnnotatedHandlers(BgSub.ListenerImpl.class) == 4);
        check("★ EntPurgeSub.ListenerImpl 上恰好 1 个 @EventHandler（关服清扫）",
                countAnnotatedHandlers(EntPurgeSub.ListenerImpl.class) == 1);
        check("★★ 钥匙锁日志里的条数就是 6（Java 侧写死 GS_KL_EVENT_COUNT）",
                KlSub.gsKlRegisterLine(6).contains("（6/6 条事件）"));

        // ---- D3. ignoreCancelled 全为 false（原脚本 registerEvent 只传 4 参）----
        for (int i = 0; i < all.length; i++) {
            check("★★ " + names[i] + " 里没有 ignoreCancelled = true（原脚本默认 false）",
                    !stripComments(all[i]).contains("ignoreCancelled = true")
                            && !stripComments(all[i]).contains("ignoreCancelled=true"));
        }
        // ★ 原脚本的 registerEvent 调用确实只传 4 个参数
        check("★★ 脚本里 registerEvent 一律只传 4 个参数（⇒ ignoreCancelled 默认 false）",
                !region.contains("registerEvent(eventClass, plugin, Priority.HIGHEST,\n                    function (l, e) { handler(e); }, plugin, true)"));
        int regCalls = region.split("pm\\.registerEvent\\(", -1).length - 1;
        check("★★ 脚本里共 " + regCalls + " 处 pm.registerEvent（4 个循环各 1 处 + 4 处 JavaAdapter 退路 = 8）",
                regCalls == 8);
        // ★ 5 参重载是 (Class, Listener, EventPriority, EventExecutor, Plugin) —— **没有**
        //   ignoreCancelled 这个参数 ⇒ 4 参 / 5 参两条路都是默认 false。
        check("★★ 脚本里 registerEvent 的 5 参重载是 exec, plugin（不是 ..., plugin, true）",
                region.contains("pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);")
                        && region.contains("pm.registerEvent(PDEv, listener, Priority.MONITOR, exec, plugin);"));
        check("★★ 脚本里 registerEvent 后面没有第 5 个参数（没有 ', true)' / ', false)' 紧跟）",
                !region.contains("handler(e); }, plugin, true)")
                        && !region.contains("handler(e); }, plugin, false)"));

        // ---- D4. 没有 System.out.println ----
        for (int i = 0; i < all.length; i++) {
            check("★★ " + names[i] + " 里没有 System.out.println / printStackTrace",
                    !all[i].contains("System.out") && !all[i].contains("printStackTrace"));
        }

        // ---- D5. 日志数字必须走 JsNum（不许 Double.toString / String.format）----
        for (int i = 0; i < all.length; i++) {
            check("★ " + names[i] + " 里没有 String.format(（会写出与 JS 不同的数字）",
                    !stripComments(all[i]).contains("String.format("));
        }
        check("★ BbSub 的日志行确实用了 JsNum", bb.contains("JsNum.str") && bb.contains("JsNum.toFixed"));
        check("★ BgSub 的日志行确实用了 JsNum", bg.contains("JsNum.str"));
        check("★ KlSub 的日志行确实用了 JsNum", kl.contains("JsNum.str"));
        check("★ EntPurgeSub 的日志行确实用了 JsNum", ent.contains("JsNum.str"));

        // ---- D6. 两套副手球不串台 ----
        check("★★ 脚本里生成器用 BIRCH_BUTTON（不是律师函的 ACACIA_BUTTON）",
                region.contains("var GS_BG_BALL_MAT = 'BIRCH_BUTTON';"));
        check("★★ Java 里生成器用 BIRCH_BUTTON", bg.contains("\"BIRCH_BUTTON\""));
        check("★★ 脚本注释明说「绝不复用 gs:bb_gen」", region.contains("★ 绝不复用 gs:bb_gen"));
        check("★★ Java 里生成器的世代键是 gs:bg_gen", bg.contains("\"gs:bg_gen\""));
        check("★★ Java 的 BgSub 里没有引用 gs:bb_gen 这个键字面量", !bg.contains("\"gs:bb_gen\""));
        check("★★ Java 的 BgSub 里没有引用 gs:bb_ball 这个键字面量", !bg.contains("\"gs:bb_ball\""));
        check("★★ Java 的 BbSub 里没有引用 gs:bg_* 这套键",
                !bb.contains("\"gs:bg_gen\"") && !bb.contains("\"gs:bg_ball\""));

        // ---- D7. 孤儿球清扫必须最后跑 + 认球判据 ----
        check("★★ 脚本注释明说孤儿球清扫「必须放在**最后**」",
                region.contains("必须放在**最后**") || region.contains("必须放在最后"));
        check("★★ 脚本注释说明理由：律师函与生成器「都已经把自己的状态表建好了」",
                region.contains("状态表建好了") && region.contains("gsBbStart")
                        && region.contains("gsBgStart"));
        check("★★ 脚本里 gsEntPurgeStart() 是 13569 行（最后一个顶层调用）",
                region.contains("gsEntPurgeStart();"));
        check("★★ Java 的 EntPurgeSub 类注释也写明「必须最后调用」",
                ent.contains("必须最后跑") || ent.contains("必须最后调用"));
        check("★★ 认球只认「DISPLAY 类型 + 我们的标记 / 专属头颅」",
                ent.contains("DISPLAY") && ent.contains("GS_ENT_TAG") && ent.contains("gsEntItemIsOurs"));
        check("★★ Java 的 gsEntIsOurs 先按类型名过滤 DISPLAY（别人的普通实体一律不碰）",
                ent.contains("if (tn.indexOf(\"DISPLAY\") < 0)"));

        // ---- D8. 四个子系统不越界引用别人的符号（★ 先剥掉注释，注释里的说明不算越界）----
        //   ★ 注意：`gsMsXxx`（gsMsLoc / gsMsDust / gsMsSound / gsMsNow …）是 **ListenerCore
        //     里的共享层函数**，不属于 MsSub 子系统 ⇒ 不能进这张表（前缀撞名而已）。
        String[] others = {"GS_AURA_", "GS_EC_", "GS_SI_", "GS_EM_", "GS_PSY_", "GS_CH_", "GS_SCOPE_",
                "GS_WL_", "GS_WULI_", "GS_AL_", "GS_VO_", "GS_MS_", "GS_RAD_",
                "gsAura", "gsEc", "gsSi", "gsEm", "gsPsychic", "gsCherry", "gsScope",
                "gsWl", "gsWuli", "gsAl", "gsVo", "gsMsNow", "gsMsTick"};
        for (int i = 0; i < all.length; i++) {
            String code = stripComments(all[i]);
            int bad = 0;
            String hit = "";
            for (String o : others) {
                if (code.contains(o)) {
                    bad++;
                    hit = o;
                }
            }
            check("★★ " + names[i] + " 的**代码**不引用其它子系统的符号（越界 " + bad + " 处，首个 " + hit + "）",
                    bad == 0);
        }
        // ★ 四个子系统只通过 ListenerCore 共享
        check("★ BbSub / BgSub / KlSub / EntPurgeSub 都持有 ListenerCore core",
                bb.contains("private final ListenerCore core") && bg.contains("private final ListenerCore core")
                        && kl.contains("private final ListenerCore core")
                        && ent.contains("private final ListenerCore core"));
        // ★ 只通过 core / ListenerCore 调共享层
        for (int i = 0; i < all.length; i++) {
            String code = stripComments(all[i]);
            check("★ " + names[i] + " 确实调用了共享层（ListenerCore. 或 core.）",
                    code.contains("ListenerCore.") || code.contains("core."));
        }

        // ---- D9. 死代码 / 差异必须在类注释里写清楚 ----
        for (int i = 0; i < all.length; i++) {
            check("★ " + names[i] + " 类注释写明「与原脚本的差异」（监听器注册）",
                    all[i].contains("与原脚本的差异") && all[i].contains("registerEvent"));
            check("★ " + names[i] + " 类注释写明原脚本「每次 reload 都会再注册一遍」",
                    all[i].contains("reload") && all[i].contains("再注册一遍"));
        }
        check("★ 四个类都写了死代码清单", bb.contains("死代码清单") && bg.contains("死代码清单")
                && kl.contains("死代码清单") && ent.contains("死代码清单"));

        // ---- D10. 四个类都不许碰别人的文件 ----
        check("★★ 四个 Java 文件里都没有 ListenerCore / Modules / build.ps1 的写入痕迹",
                !bb.contains("ListenerCore.java") && !bg.contains("ListenerCore.java")
                        && !kl.contains("ListenerCore.java") && !ent.contains("ListenerCore.java"));

        // ---- D11. 原脚本里那 6 处 | 0 在 Java 侧确实是 (int) 窄化 ----
        check("★★ 脚本里 gsBbShootState 的冷却带 | 0", region.contains("(st.shotAt | 0)"));
        check("★★ Java 里对应处是 (int) st.shotAt", bb.contains("(int) st.shotAt"));
        check("★★ 脚本里 gsBbTickRest 的静默带 | 0", region.contains("(st.touched | 0)"));
        check("★★ Java 里对应处是 (int) st.touched", bb.contains("(int) st.touched"));
        check("★★ 脚本里 gsBbPulse 的清扫周期带 | 0", region.contains("(GS_BB_SWEEP_AT | 0)"));
        check("★★ Java 里对应处是 (int) GS_BB_SWEEP_AT", bb.contains("(int) GS_BB_SWEEP_AT"));
        check("★★ 脚本里 gsKlHint 的去抖带 | 0", region.contains("(GS_KL_HINT_AT[u] | 0)"));
        check("★★ Java 里对应处是 (int) (long) lastObj", kl.contains("(int) (long) lastObj"));
        check("★★ 脚本里 gsKlPulse 的清扫周期带 | 0", region.contains("(GS_KL_SWEEP_AT | 0)"));
        check("★★ Java 里对应处是 (int) GS_KL_SWEEP_AT", kl.contains("(int) GS_KL_SWEEP_AT"));
        check("★★ 脚本里 gsBgTryShoot 的去抖带 | 0", region.contains("(GS_BG_CLICK_AT[u] | 0)"));
        check("★★ Java 里对应处是 (int) (long) lastObj", bg.contains("(int) (long) lastObj"));
        check("★★ 脚本里 gsBgTickBound 的相位带 | 0", region.contains("(b.tBoundAt | 0)"));
        check("★★ Java 里对应处是 (int) b.tBoundAt", bg.contains("(int) b.tBoundAt"));

        // ---- D12. 周期刻数逐处核对 ----
        check("★★ gsBbPulse 是 (1,1) 每刻", region.contains("runRepeating(gsBbPulse, 1, 1);"));
        check("★★ gsBgPulse 是 (1,1) 每刻", region.contains("runRepeating(gsBgPulse, 1, 1);"));
        check("★★ gsKlPulse 是 (1,1) 每刻", region.contains("runRepeating(gsKlPulse, 1, 1);"));
        check("★★★ gsEntPulse 是 (GS_ENT_PURGE_EVERY, GS_ENT_PURGE_EVERY) 双参同值（不是 1 刻！）",
                region.contains("runRepeating(gsEntPulse, GS_ENT_PURGE_EVERY, GS_ENT_PURGE_EVERY);"));
        check("★★ Java 的 EntPurgeSub 用的是 GS_ENT_PURGE_EVERY 双参同值",
                ent.contains("}, GS_ENT_PURGE_EVERY, GS_ENT_PURGE_EVERY);"));
        check("★ Java 的 BbSub / BgSub / KlSub 用 (1,1)", bb.contains("}, 1, 1);")
                && bg.contains("}, 1, 1);") && kl.contains("}, 1, 1);"));

        // ---- D13. 生成器只养一颗球 ----
        check("★★ GS_BG_MAX = 1（一台生成器同时只有一颗球）", BgSub.GS_BG_MAX == 1);
        check("★★ 脚本注释写明「一台生成器只养一颗球」", region.contains("只养一颗球"));
        check("★★ Java 里补球条件是 node.balls.size() < GS_BG_MAX", bg.contains("node.balls.size() < GS_BG_MAX"));

        // ---- D14. 钥匙锁日志里的 0.5 秒是浮点除法 ----
        check("★★ 脚本里是 (GS_KL_SWEEP_MS / 1000)（JS 浮点除法 ⇒ \"0.5\"）",
                region.contains("(GS_KL_SWEEP_MS / 1000)"));
        check("★★ Java 里是 (double) GS_KL_SWEEP_MS / 1000.0", kl.contains("(double) GS_KL_SWEEP_MS / 1000.0"));
        check("★★ 脚本里是 (GS_ENT_PURGE_EVERY / 20.0)", region.contains("(GS_ENT_PURGE_EVERY / 20.0)"));
        check("★★ Java 里是 (double) GS_ENT_PURGE_EVERY / 20.0",
                ent.contains("(double) GS_ENT_PURGE_EVERY / 20.0"));

        // ---- D15. 钥匙锁用 getSlot() 而不是 getRawSlot() ----
        check("★★ 脚本注释说明只用 slot 不用 raw", region.contains("所以**只用 slot**，不用 raw"));
        check("★★ Java 里用 e.getSlot()", kl.contains("e.getSlot()"));
        check("★★ Java 的代码里没有 e.getRawSlot() 调用", !stripComments(kl).contains("getRawSlot()"));

        // ---- D16. 孤儿球清扫的 PluginDisable 优先级是 MONITOR ----
        check("★★ 脚本里关服清扫用 Priority.MONITOR（不是 HIGHEST）",
                region.contains("pm.registerEvent(PDEv, plugin, Priority.MONITOR,"));
        check("★★ Java 里关服清扫是 EventPriority.MONITOR", ent.contains("EventPriority.MONITOR"));
        check("★★ Java 的 EntPurgeSub 里没有 EventPriority.HIGHEST", !ent.contains("EventPriority.HIGHEST"));

        // ---- D17. 生成器挖掉时把物品掉回来 ----
        check("★★ 脚本里 gsBgDropSelf 关掉原版掉落", region.contains("ev.setDropItems(false);"));
        check("★★ Java 里同样 setDropItems(false)", bg.contains("ev.setDropItems(false)"));
        check("★★ Java 里用 gsNewItem(GS_BG_ID) 造回物品", bg.contains("ListenerCore.gsNewItem(GS_BG_ID)"));

        // ---- D18. 材质名挡球清单原封不动 ----
        check("★★ 脚本里 GS_BB_SOLID_KEYS 的 10 个关键词顺序不变",
                region.contains("'PANE', 'BARS', 'CHAIN', 'FENCE', 'WALL', 'SLAB', 'STAIRS',")
                        && region.contains("'DOOR', 'TRAPDOOR', 'GLASS']"));
        check("★★ 脚本里 GS_BB_SOFT_EXCEPT 的 6 个例外顺序不变",
                region.contains("['SIGN', 'TORCH', 'BANNER', 'HANGING', 'CORAL', 'FAN']"));

        // ---- D19. 副手球只在副手空着时才放 ----
        check("★★ 脚本注释写明「副手是玩家自己的东西 ⇒ 不碰它」",
                region.contains("副手是**玩家自己的东西** ⇒ 不碰它"));
        check("★★ Java 的 gsBbHandOn / gsBgHandOn 都先判 gsBbIsEmpty",
                bb.contains("if (!gsBbIsEmpty(cur))") && bg.contains("if (!gsBgIsEmpty(cur))"));

        // ---- D20. 球渲染用 ItemDisplay + 每 1 刻同步 + 插值 0 刻 ----
        check("★★ GS_BB_USE_ENT = true（用展示实体渲染）", BbSub.GS_BB_USE_ENT);
        check("★★ GS_BB_ENT_EVERY = 1（每刻同步）", BbSub.GS_BB_ENT_EVERY == 1);
        check("★★ GS_BB_ENT_TPDUR = 0 / GS_BB_ENT_INTDUR = 0（插值 0 刻）",
                BbSub.GS_BB_ENT_TPDUR == 0 && BbSub.GS_BB_ENT_INTDUR == 0);
        check("★★ Java 用 ItemDisplay.ItemDisplayTransform.HEAD",
                bb.contains("ItemDisplay.ItemDisplayTransform.HEAD"));
        check("★★ Java 里给实体打了 GS_ENT_TAG 标记",
                bb.contains("getPersistentDataContainer().set(GS_ENT_TAG, PersistentDataType.INTEGER, 1)"));

        System.out.println();
    }

    // ======================================================================
    // 反射 / mock 工具
    // ======================================================================

    private static int countAnnotatedHandlers(Class<?> c) {
        int n = 0;
        for (Method m : c.getDeclaredMethods()) {
            if (m.isAnnotationPresent(EventHandler.class)) {
                n++;
            }
        }
        return n;
    }

    /**
     * JS 的 {@code x | 0}（ECMA-262 {@code ToInt32}）：先向零截断，再按 2^32 取模回绕。
     *
     * <p>★★ <b>不能用 Java 的 {@code (int) double}</b>：那是<b>饱和</b>转换
     * （{@code (int) 1.76e12 == Integer.MAX_VALUE}），而 JS 是<b>回绕</b>。
     * 生产代码里的字段是 {@code long} ⇒ {@code (int) longValue} 才是真回绕窄化。
     */
    private static int jsToInt32(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return 0;
        }
        return (int) (long) d;
    }

    /** 剥掉 Java 的行注释与块注释（用于「代码里有没有引用别人符号」这类扫描）。 */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                out.append(c);
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\' && i + 1 < n) {
                        out.append(src.charAt(i));
                        i++;
                    }
                    out.append(src.charAt(i));
                    i++;
                }
                if (i < n) {
                    out.append(src.charAt(i));
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static int modeNum(String mode) {
        if ("idle".equals(mode)) {
            return 0;
        }
        if ("bound".equals(mode)) {
            return 1;
        }
        if ("shot".equals(mode)) {
            return 2;
        }
        if ("rest".equals(mode)) {
            return 3;
        }
        return -1;
    }

    private static BbSub.Ball ballAt(double x, double y, double z) {
        BbSub.Ball b = new BbSub.Ball();
        b.x = x;
        b.y = y;
        b.z = z;
        return b;
    }

    private static BgSub.BgNode node(int x, int y, int z) {
        BgSub.BgNode n = new BgSub.BgNode();
        n.x = x;
        n.y = y;
        n.z = z;
        return n;
    }

    /** 与 Paper 的 {@code Location.getDirection()} 同一个经典公式（探针里用来喂两边同一组输入）。 */
    private static double[] dirOf(double yaw, double pitch) {
        double rotX = yaw;
        double rotY = pitch;
        double y = -Math.sin(Math.toRadians(rotY));
        double xz = Math.cos(Math.toRadians(rotY));
        double x = -xz * Math.sin(Math.toRadians(rotX));
        double z = xz * Math.cos(Math.toRadians(rotX));
        return new double[]{x, y, z};
    }

    /** mock 方块：{@code isPassable()} 可控，材质名可控。 */
    private static Block fakeBlock(boolean passable, Material realMat, String name) {
        return (Block) Proxy.newProxyInstance(ListenerFSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Block.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "isPassable":
                            return passable;
                        case "getType":
                            return realMat;
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    /**
     * mock 世界。
     *
     * @param cfg {@code "none"} = 没有方块；{@code "floor64"} = y&lt;64 是 STONE；
     *            {@code "pane3"} = x=3 且 y∈[60,68] 是 GLASS_PANE（isPassable 说能过）
     */
    private static World javaWorld(String cfg) {
        return (World) Proxy.newProxyInstance(ListenerFSubParityProbe.class.getClassLoader(),
                new Class<?>[]{World.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getBlockAt": {
                            int fx = (int) args[0];
                            int fy = (int) args[1];
                            if (cfg.contains("pane3") && fx == 3 && fy >= 60 && fy <= 68) {
                                return fakeBlock(true, Material.GLASS_PANE, "GLASS_PANE");
                            }
                            if (cfg.contains("floor64") && fy < 64) {
                                return fakeBlock(false, Material.STONE, "STONE");
                            }
                            return fakeBlock(true, Material.AIR, "AIR");
                        }
                        case "getName":
                            return "world";
                        case "toString":
                            return "mockWorld";
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    /** mock 玩家（Java 侧）：坐标 / yaw / pitch 用真 {@code Location}。 */
    private static Player fakePlayerWith(double x, double y, double z, float yaw, float pitch) {
        World w = javaWorld("none");
        Location loc = new Location(w, x, y, z, yaw, pitch);
        return (Player) Proxy.newProxyInstance(ListenerFSubParityProbe.class.getClassLoader(),
                new Class<?>[]{Player.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getUniqueId":
                            return UUID.fromString(SELF_ID);
                        case "getLocation":
                            return loc;
                        case "getWorld":
                            return w;
                        case "getInventory":
                            return fakeInv();
                        case "isOnline":
                            return true;
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    private static org.bukkit.inventory.PlayerInventory fakeInv() {
        return (org.bukkit.inventory.PlayerInventory) Proxy.newProxyInstance(
                ListenerFSubParityProbe.class.getClassLoader(),
                new Class<?>[]{org.bukkit.inventory.PlayerInventory.class},
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getItemInOffHand":
                        case "getItemInMainHand":
                        case "getChestplate":
                        case "getItem":
                            return null;
                        case "getSize":
                            return 41;
                        case "getContents":
                            return new ItemStack[0];
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    // ======================================================================
    // 杂项
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

    private static String findInfo(List<String> logs, String needle) {
        for (String s : logs) {
            if (s.startsWith("INFO ") && s.contains(needle)) {
                return s.substring(5);
            }
        }
        return null;
    }

    /** 日志前缀（与 {@code ListenerCore.PREFIX} 同一个常量，避免探针里硬编码）。 */
    private static String P() {
        return ListenerCore.PREFIX;
    }

    /**
     * 参考服日志里的一行会带 Bukkit 的时间戳前缀
     * （形如 {@code [12:34:56] [Server thread/INFO]: [构思技艺] …}）⇒
     * 只取从 {@code [构思技艺] } 开始的那一段。
     */
    private static String findLine(String[] lines, String needle) {
        for (String s : lines) {
            if (s.contains(needle)) {
                int i = s.indexOf(ListenerCore.PREFIX);
                return (i >= 0) ? s.substring(i) : s;
            }
        }
        return null;
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
        if (v.isNumber()) {
            if (v.fitsInLong()) {
                return v.asLong();
            }
            return v.asDouble();
        }
        // 宿主对象（NamespacedKey / Class / …）⇒ 比 toString
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
        // ★ 一边是 String（JS 宿主对象的 toString）、一边是宿主对象（NamespacedKey / Class）
        //   ⇒ 一律按 toString 比（JS 的 '' + key 与 Java 的 key.toString() 同一个东西）。
        //   ★ Class 特别处理：GraalJS 的 Java.type(...).toString() 是**不带** "interface " /
        //     "class " 前缀的全限定名 ⇒ 用 Class.getName() 对齐。
        if (a instanceof Class<?> ca) {
            return ca.getName().equals(String.valueOf(b));
        }
        if (b instanceof Class<?> cb) {
            return cb.getName().equals(String.valueOf(a));
        }
        if (a instanceof String || b instanceof String) {
            return String.valueOf(a).equals(String.valueOf(b));
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
        // NamespacedKey / Class / 其它宿主对象 ⇒ toString 比对
        return String.valueOf(a).equals(String.valueOf(b));
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
        if (o instanceof short[] a) {
            double[] r = new double[a.length];
            for (int i = 0; i < a.length; i++) {
                r[i] = a[i];
            }
            return r;
        }
        if (o instanceof byte[] a) {
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
        if (o instanceof String[] s) {
            return Arrays.toString(s);
        }
        double[] n = asNumArray(o);
        if (n != null) {
            return Arrays.toString(n);
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

    /** 三方差一不可的字符串比对：不一致时把两边都打出来（逐字节看差异）。 */
    private static void checkEq(String label, String java, String other, String otherName) {
        boolean ok = java != null && java.equals(other);
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label);
            System.out.println("         Java  : " + java);
            System.out.println("         " + otherName + " : " + other);
            if (java != null && other != null) {
                System.out.println("         Java 长度 = " + java.length() + "，" + otherName
                        + " 长度 = " + other.length());
                int lim = Math.min(java.length(), other.length());
                for (int i = 0; i < lim; i++) {
                    if (java.charAt(i) != other.charAt(i)) {
                        System.out.println("         首个不同在第 " + i + " 个字符：Java='"
                                + java.charAt(i) + "'(" + (int) java.charAt(i) + ") vs " + otherName
                                + "='" + other.charAt(i) + "'(" + (int) other.charAt(i) + ")");
                        System.out.println("         Java  上下文: "
                                + java.substring(Math.max(0, i - 25), Math.min(java.length(), i + 25)));
                        System.out.println("         " + otherName + " 上下文: "
                                + other.substring(Math.max(0, i - 25), Math.min(other.length(), i + 25)));
                        break;
                    }
                }
            }
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
}
