package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code gs_listener.js} 的「<b>月镰</b>」子系统
 * （原脚本第 <b>5946~6846</b> 行，顶层入口 {@code gsMsStart();} 在第 6846 行）。
 *
 * <h2>玩法</h2>
 * 手持月镰（{@code GS_MOON_SCYTHE}，铁锄）时：
 * <ul>
 *   <li><b>左键</b> = 斜劈（星星粒子，左斜 / 右斜交替）⇒ 打中
 *       <b>前方 3 格长 × 3 格宽 × 上下 ±1.6 格</b>矩形柱里的生物，每只 <b>11</b> 点伤害；
 *       只要命中 ≥1 只就叠一层「梦」。左键还会<b>取消原版交互</b>（挖不动方块）。</li>
 *   <li><b>叠层</b>：每层窗口 <b>7 秒</b>（{@code GS_MS_WINDOW}）；满 <b>12</b> 层后改成
 *       <b>30 秒</b>倒计时（{@code GS_MS_FULL_WIN}），命中只刷新倒计时、不再加层。
 *       血条金色（{@code §6}），满层时标题补一句「右键释放“噩梦”」，颜色转红（{@code RED}）。</li>
 *   <li><b>右键</b>（满 12 层）= <b>噩梦</b>：24 根紫色尘埃射线（半径 <b>5</b> 格、
 *       起点在<b>大腿根部</b> {@code y + 0.9}）＋ 半径 5 格内的生物吃 <b>35</b> 点伤害
 *       并被<b>定身 3 秒</b>；未满 12 层时右键什么都不做（但仍然取消原版交互）。</li>
 * </ul>
 *
 * <h2>★★ 层数的权威状态：脚本内存（不是 PDC）—— 原脚本的 bug 修复必须完整保留</h2>
 * 旧版把「梦」的层数<b>只</b>存在玩家 PDC 上（{@code gsMsGet} 直接读 PDC、{@code gsMsSet}
 * 直接写 PDC）。而 PDC 的写入 / 回读在 GraalJS 这一侧任何一环静默失败（异常被
 * {@code try/catch} 吞掉），就变成「<b>每次读回来都是 0</b>」⇒ 每次都从 0 加到 1
 * ⇒ 血条标题<b>恒为 {@code 1/12}</b>。
 *
 * <p>修复后的语义（{@link #gsMsGet} / {@link #gsMsSet}，逐字照抄）：
 * <ul>
 *   <li><b>读</b>：内存（{@code GS_MS_ST}）<b>优先</b>；内存里没有（服务器刚重启 /
 *       玩家刚上线）才<b>尝试从 PDC 恢复一次</b>，且只有「层数 &gt; 0 且计时还没过」
 *       才采纳。</li>
 *   <li><b>写</b>：<b>先写内存</b>（必定成功）⇒ 再<b>尽力</b>写 PDC
 *       （失败不影响逻辑，只为跨重启留个备份）。</li>
 * </ul>
 * <p>★ 探针里有一条<b>行为级</b>反向断言钉住这件事：注入一个「读永远返回 null、
 * 写永远抛异常」的 PDC，写入 5 层后必须读回 5 层（Java 与真 JS 两侧同时跑）。
 * 如果谁把它改回「只存 PDC」，这条断言立刻红。
 *
 * <h2>★ 监听器：原脚本 reload 会累积，Java 版改为幂等注册</h2>
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} 在脚本里自己注册监听器，
 * 而且 {@code GS_MS_REGISTERED} 闩锁是<b>脚本级变量</b>（reload 后归零）
 * ⇒ <b>每次 {@code /rsc reload} 都会再注册一遍，监听器会累积</b>（这是真实缺陷：
 * 同一次挥砍会被处理 N 次，只靠 80 毫秒去抖挡着）。
 * Java 版改成 {@code org.bukkit.event.Listener} 实现类 + {@code registerEvents}
 * <b>只注册一次</b>（{@link #GS_MS_REGISTERED} 保留为实例级幂等闩锁）。
 * 优先级逐处核对：原脚本 4 处 {@code registerEvent} 全部 {@code Priority.HIGHEST}，
 * 且<b>没有第 5 个参数</b> ⇒ {@code ignoreCancelled} 默认 <b>false</b>；
 * Java 的 {@code @EventHandler} 默认也是 false，<b>不要写成 true</b>。
 *
 * <h2>★ 血条标题只喂纯字符串</h2>
 * 原脚本用一整段注释强调：把 Adventure {@code Component} 塞进 {@code BossBar#setTitle}
 * 会被 GraalJS 隐式 {@code toString()} 成
 * {@code TextImpl{obfuscated=not_set, …, color=NamedTextColor{name="red",…}}}
 * 一长串 dump（月镰 / 蚀月的血条标题线上事故）。所以标题一律用<b>传统 {@code §} 颜色码</b>
 * 拼成字符串，再走 {@link ListenerCore#gsBarTitleSet}（内部再 {@code String.valueOf} 兜一层）。
 * 月镰的标题格式是<b>整条金色</b>：{@code §6梦 n/12}（满层补 {@code §6 右键释放“噩梦”}）。
 * ★ 「{@code §c} 亮红 + {@code §4} 暗红」那套是「此刻万籁俱寂」的，别抄过来。
 *
 * <h2>死代码 / 降级分支（逐条列出，均已在 Java 里等价处理）</h2>
 * <ol>
 *   <li>原脚本 5990 行 {@code GS_MS_VECC / GS_MS_LOCC / GS_MS_DOC / GS_MS_COLORC}
 *       —— 只是 {@code Java.type} 的<b>类缓存</b>（为了少查几次类）。Java 里
 *       {@code new Location(...)} 直接可用 ⇒ 这四个变量<b>不移植</b>（只写不读的载体）。</li>
 *   <li>原脚本 6709~6718 行 {@code reg()} 的<b>降级分支</b>用
 *       {@code JavaAdapter} 造 {@code Listener} / {@code EventExecutor}。
 *       {@code tools/GraalProbe.java} 已实测 GraalJS 25.1.3 <b>没有 {@code JavaAdapter}</b>
 *       ⇒ 那条路<b>永远走不到</b>。Java 版用 {@code registerEvents} 一次到位。</li>
 *   <li>原脚本 6698~6702 行的 {@code plugin} 探测链（RSC INSTANCE → getPlugin("RykenSlimeCustomizer")
 *       → getPlugin("RykenSlimefunCustomizer") → getPlugin("Slimefun")）在 Java 里
 *       由 {@code GSPlugin.instance()} 取代 —— 原生附属的 plugin 实例是确定的。</li>
 *   <li>原脚本 6246~6247 / 6255~6257 行的「先 {@code C.valueOf(name)} 再 {@code C[name]}」
 *       是<b>同一件事写两遍</b>（enum 的静态字段恰好就是枚举常量集合）。Java 里合并成一次
 *       {@code valueOf}；能取到的名字集合与取不到时的返回值都相同。</li>
 * </ol>
 *
 * <h2>与原脚本的结构差异（不影响可观察行为）</h2>
 * <ol>
 *   <li>{@code GS_MS_LOCKS} 在共享层 {@link ListenerCore#gsMsLocks}（蚀月也用它）。
 *       原脚本 {@code gsMsLockTick} 结尾做的是 {@code GS_MS_LOCKS = keep}（<b>换一个新数组</b>）；
 *       Java 里 {@code gsMsLocks} 是 {@code final} 列表 ⇒ 改成「先收集再
 *       {@code clear() + addAll()}」。所有读者都走同一个列表对象，语义相同。</li>
 *   <li>{@code GS_MS_NM_COL}（{@code [150,60,255]} 的 JS 数组）在共享层的定身记录里
 *       是 {@link Color}；画粒子柱时读 {@code getRed()/getGreen()/getBlue()} —— 与
 *       {@code col[0]/col[1]/col[2]} 逐值相同（都落在 0~255）。</li>
 *   <li>{@code gsMsHurt} 与蚀月的 {@code gsEcHurtNoKb} 在原脚本里是<b>逐字相同的两份</b>
 *       ⇒ Java 统一调 {@link ListenerCore#gsMsHurt}（三个细节：先 {@code setNoDamageTicks(0)}
 *       → 读速度 → 打伤害 → 写回速度；{@code GS_WPN_DMG_GUARD++/--} 用 try/finally；
 *       打完再 {@code setNoDamageTicks(0)}，全部已在共享层保留）。</li>
 *   <li>{@code gsMsCanHit} 的「{@code try { ent.getHealth(); } catch { return false; }}」
 *       探针用<b>反射</b>复现（见 {@link #gsMsProbeHealth}）—— <b>不能</b>写成
 *       {@code instanceof Damageable}，否则掉落物就打不中了（详见该方法注释）。</li>
 * </ol>
 */
public final class MsSub {

    private final ListenerCore core;

    /**
     * 蚀月子系统的引用 —— 原脚本 {@code gsMsPulse}（第 6662 / 6667 行）
     * <b>顺手驱动了蚀月的血条</b>（{@code gsEcPlayerTick} / {@code gsEcBarsClean}），
     * 而 {@code gsEcStart} 自己<b>不</b>注册任何 {@code runRepeating}。
     *
     * <p>★ 这是本子系统<b>唯一</b>的跨子系统调用（两者同属 D 组，见交付说明）。
     * 由集成者调 {@link #bindEc} 注入；未注入时心跳仍然工作，只是蚀月血条不被驱动
     * （所以集成时<b>必须</b>注入，集成片段见交付说明）。
     */
    private EcSub ec;

    // ======================================================================
    // 参数（原脚本 5952~5975 行）—— 字段名 = 原脚本顶层 var 名，供常量对账探针反射比对
    // ======================================================================

    /** 月镰的物品 ID。 */
    static final String GS_MS_ID = "GS_MOON_SCYTHE";

    /** 挥砍范围：前方 3 格（**长度**）。 */
    static final double GS_MS_REACH = 3.0;

    /** ★ 第十七批：挥砍宽度（左右各 1.5 格 ⇒ 共 3 格宽）。 */
    static final double GS_MS_WIDE = 3.0;

    /** ★ 第十七批：挥砍上下判定高度（格）。 */
    static final double GS_MS_TALL = 1.6;

    /** 挥砍伤害。 */
    static final double GS_MS_DMG = 11.0;

    /** 挥砍冷却（毫秒）。 */
    static final long GS_MS_SWING_CD = 320;

    /** 「梦」满层。 */
    static final int GS_MS_MAX = 12;

    /** 未满层时每层的存活窗口（毫秒）。 */
    static final long GS_MS_WINDOW = 7000;

    /** 满 12 层后的倒计时（毫秒）。 */
    static final long GS_MS_FULL_WIN = 30000;

    /** true = 手持月镰挖不动方块。 */
    static final boolean GS_MS_NO_BREAK = true;

    /** false = 挥砍 / 噩梦不打其它玩家（只打生物），防误伤。 */
    static final boolean GS_MS_HIT_PLAYERS = false;

    /** 噩梦射线根数。 */
    static final int GS_MS_NM_RAYS = 24;

    /** 噩梦半径（格）。 */
    static final double GS_MS_NM_R = 5.0;

    /** 射线起点高度：脚底 + 0.9 ≈ 玩家大腿根部。 */
    static final double GS_MS_NM_Y = 0.9;

    /** 噩梦伤害。 */
    static final double GS_MS_NM_DMG = 35.0;

    /** 定身时长（毫秒）。 */
    static final long GS_MS_NM_LOCK = 3000;

    /** 噩梦紫色（原脚本是 JS 数组 {@code [150, 60, 255]}）。 */
    static final int[] GS_MS_NM_COL = {150, 60, 255};

    /**
     * 定身粒子柱高度（格）。
     *
     * <p>★ 原脚本 5976 行的批注原文（独立成行，紧跟在 {@code GS_MS_LOCK_STICK} 之后）：
     * <pre>
     *  // ★ 2.2 → 4.0（用户要求"最起码 4 格高"，否则太不显眼）
     * </pre>
     * 即：2.2 → 4.0（用户要求"最起码 4 格高"，否则太不显眼）。
     */
    static final double GS_MS_LOCK_STICK = 4.0;

    // ---- PDC 键（原脚本 5994~6002 行）----
    // ★★★ 必须 fromString —— new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败

    /** {@code gs:ms_dream} —— 「梦」层数（跨重启备份）。 */
    static final NamespacedKey GS_MS_K_DREAM = NamespacedKey.fromString("gs:ms_dream");

    /** {@code gs:ms_until} —— 层数计时到期时刻（毫秒）。 */
    static final NamespacedKey GS_MS_K_UNTIL = NamespacedKey.fromString("gs:ms_until");

    /** {@code gs:ms_full} —— 是否已满层（0 / 1）。 */
    static final NamespacedKey GS_MS_K_FULL = NamespacedKey.fromString("gs:ms_full");

    /** {@code gs:ms_gen} —— 心跳世代号（存<b>第一个世界</b>的 PDC，多实例去重）。 */
    static final NamespacedKey GS_MS_K_GEN = NamespacedKey.fromString("gs:ms_gen");

    /**
     * 原脚本的 {@code GS_MS_PDT = Java.type('org.bukkit.persistence.PersistentDataType')}。
     *
     * <p>Java 里这个类必然可用 ⇒ 恒非 null。保留字段只为让「PDC 备份 可用/不可用」
     * 这条探测分支（{@link #gsMsPdcOk()}）与原脚本逐字对应。
     */
    static final Class<?> GS_MS_PDT = PersistentDataType.class;

    // ======================================================================
    // 血条颜色码（原脚本 6171~6191 行）
    // ======================================================================

    /**
     * {@code GS_MS_C} —— 传统 {@code §} 颜色码表，<b>逐字照抄</b>。
     *
     * <p>★ 它只服务 {@link #gsMsCode} / {@link #gsMsTitle} / {@link #gsMsTitle2}；
     * 后两个被蚀月复用（原脚本就把它们定义在月镰这一段里）。
     */
    static final Map<String, String> GS_MS_C = buildMsColors();

    private static Map<String, String> buildMsColors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("RED", "§c");
        m.put("DARK_RED", "§4");
        m.put("GOLD", "§6");
        m.put("YELLOW", "§e");
        m.put("GREEN", "§a");
        m.put("AQUA", "§b");
        m.put("BLUE", "§9");
        m.put("LIGHT_PURPLE", "§d");
        m.put("PURPLE", "§5");
        m.put("WHITE", "§f");
        m.put("GRAY", "§7");
        m.put("DARK_GRAY", "§8");
        m.put("BLACK", "§0");
        return m;
    }

    /** 定身粒子柱的颜色（{@link #GS_MS_NM_COL} 的 {@link Color} 形式）。 */
    private static final Color NM_COLOR = Color.fromRGB(
            GS_MS_NM_COL[0], GS_MS_NM_COL[1], GS_MS_NM_COL[2]);

    // ======================================================================
    // 运行期状态（原脚本顶层 var，非静态：实例 = 原脚本的一次顶层求值）
    // ======================================================================

    /** {@code GS_MS_REGISTERED} —— 原脚本<b>只写不读</b>的闩锁；Java 里用作幂等注册闩锁。 */
    boolean GS_MS_REGISTERED;

    /** {@code GS_MS_CLICK_AT} —— {@code uuid|L / uuid|R} → 上次处理的毫秒（去抖）。 */
    final Map<String, Long> GS_MS_CLICK_AT = new HashMap<>();

    /** {@code GS_MS_SWING_AT} —— uuid → 上次挥砍毫秒（冷却）。 */
    final Map<String, Long> GS_MS_SWING_AT = new HashMap<>();

    /** {@code GS_MS_SIDE} —— uuid → 0 / 1（左斜 / 右斜交替）。 */
    final Map<String, Integer> GS_MS_SIDE = new HashMap<>();

    /** {@code GS_MS_BARS} —— uuid → 血条记录（本族自己一张表）。 */
    final Map<String, ListenerCore.BarRec> GS_MS_BARS = ListenerCore.newBarTable();

    /**
     * {@code GS_MS_ST} —— uuid → {@code { n, until, full }}。
     *
     * <p>★★ <b>这是「梦」层数的唯一权威状态</b>（PDC 只是跨重启备份）。见类注释。
     */
    final Map<String, St> GS_MS_ST = new HashMap<>();

    /** {@code GS_MS_GEN} —— 心跳世代号（{@code /rsc reload} 后旧实例自动停手）。 */
    long GS_MS_GEN = -1;

    /** 监听器实例（懒建，{@code registerEvents} 只调一次）。 */
    private Listener listener;

    /** 原脚本 {@code GS_MS_ST[uuid] = { n, until, full }}。 */
    static final class St {
        int n;
        long until;
        int full;
    }

    public MsSub(ListenerCore core) {
        this.core = core;
    }

    /** 注入蚀月子系统的引用（原脚本 {@code gsMsPulse} 要驱动蚀月血条）。见 {@link #ec}。 */
    public void bindEc(EcSub ec) {
        this.ec = ec;
    }

    /** 对应原脚本顶层的 {@code gsMsStart();} 调用（原脚本第 6846 行）。 */
    public void start() {
        gsMsStart();
    }

    // ======================================================================
    // 小工具（原脚本 6004~6013 行）
    // ======================================================================

    /** {@code gsMsIs(item)} —— 是不是月镰。 */
    static boolean gsMsIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_MS_ID);
    }

    /** {@code gsMsHand(p)} —— 主手物品。 */
    static ItemStack gsMsHand(Player p) {
        try {
            return p.getInventory().getItemInMainHand();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * JS 的 {@code x | 0}（{@code ToInt32}）的精确等价。
     *
     * <p>★ 不能写成 {@code (int) x}：Java 的 double→int 是<b>饱和</b>转换
     * （{@code 1e10} → {@code 2147483647}），而 JS 是<b>取模 2³²</b>
     * （{@code 1e10 | 0} → {@code 1410065408}）。
     * 实际用到的值都落在 0~12，但探针会在大范围输入上与真 JS 逐值对账。
     */
    static int toInt32(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0;
        }
        double t = (v < 0) ? -Math.floor(-v) : Math.floor(v);   // ToIntegerOrInfinity：向零取整
        double m = t % 4294967296.0;
        if (m < 0) {
            m += 4294967296.0;
        }
        long l = (long) m;
        if (l >= 2147483648L) {
            l -= 4294967296L;
        }
        return (int) l;
    }

    /**
     * JS 的 {@code Math.floor(0 + v)} 后走 {@code Long.valueOf('' + n)}（原脚本 6131~6137 行）。
     *
     * <p>★ 原脚本<b>故意</b>走 String 重载（{@code Long.valueOf('' + n)}）来避掉 GraalJS 的
     * {@code valueOf} 重载歧义。Java 侧等价：JS 的数值→字符串在
     * {@code |n| ≥ 2⁶³} 时会带 {@code E}（如 {@code "1e+21"}）或超出 long 范围
     * ⇒ {@code Long.valueOf} 抛异常 ⇒ 原脚本返回 {@code 0}。
     */
    static long gsMsLong(double v) {
        try {
            double n = Math.floor(v);
            if (Double.isNaN(n)) {
                n = 0;
            }
            // 9.223372036854776E18 == (double) Long.MAX_VALUE + 1（2⁶³）
            if (n >= 9.223372036854776E18 || n < -9.223372036854776E18) {
                return 0;
            }
            return (long) n;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ======================================================================
    // 梦：层数读写（★ 内存权威 + PDC 尽力备份）—— 原脚本 6090~6158 行
    // ======================================================================

    /**
     * PDC 取值来源（只为让探针能注入「PDC 静默失败」的场景）。
     *
     * <p>★ 生产路径就是 {@code p::getPersistentDataContainer}；
     * 探针用一个 {@code get()} 抛异常的 {@link PersistentDataContainer} 来复现
     * 原脚本修掉的那个 bug（写入 / 回读静默失败）。
     * <b>允许抛异常</b>：原脚本在 {@code p.getPersistentDataContainer()} 这一步抛，
     * 整个 try 块就被跳过（而不是逐项降级）—— 这个「一次包住三项读写」的结构必须保留。
     */
    interface MsPdcSource {
        PersistentDataContainer get() throws Throwable;
    }

    /** {@code gsMsGet(p)} —— 读「梦」的层数（内存优先，缺失才试 PDC）。 */
    St gsMsGet(Player p) {
        St o = new St();
        try {
            if (p == null) {
                return o;
            }
            String u = String.valueOf(p.getUniqueId());
            return gsMsGet0(u, p::getPersistentDataContainer);
        } catch (Throwable e) {
            return o;
        }
    }

    /**
     * {@code gsMsGet} 的本体（原脚本 6099~6128 行）—— <b>逐字照抄</b>。
     *
     * <p>★ ① 内存优先（权威）② 内存没有才尝试从 PDC 恢复一次
     * ③ 只有「层数 &gt; 0 且计时还没过」才采纳。
     */
    St gsMsGet0(String u, MsPdcSource src) {
        St o = new St();
        try {
            // ① 内存优先
            St m = GS_MS_ST.get(u);
            if (m != null) {
                o.n = m.n;
                o.until = m.until;
                o.full = m.full;
                return o;
            }

            // ② 内存没有（服务器刚重启 / 玩家刚上线）⇒ 试着从 PDC 恢复一次
            if (GS_MS_PDT == null || GS_MS_K_DREAM == null) {
                return o;
            }
            try {
                PersistentDataContainer pdc = src.get();
                Integer n = pdc.get(GS_MS_K_DREAM, PersistentDataType.INTEGER);
                Long un = pdc.get(GS_MS_K_UNTIL, PersistentDataType.LONG);
                Integer f = pdc.get(GS_MS_K_FULL, PersistentDataType.INTEGER);
                if (n != null) {
                    o.n = toInt32(n);
                }
                if (un != null) {
                    o.until = un;
                }
                if (f != null) {
                    o.full = toInt32(f);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // 只有「层数 > 0 且 计时还没过」才恢复，否则当作没有
            if (o.n > 0 && o.until > ListenerCore.now()) {
                St s = new St();
                s.n = o.n;
                s.until = o.until;
                s.full = o.full;
                GS_MS_ST.put(u, s);
            } else {
                o.n = 0;
                o.until = 0;
                o.full = 0;
            }
            return o;
        } catch (Throwable e) {
            return o;
        }
    }

    /** {@code gsMsSet(p, n, until, full)} —— 写层数（先内存，再尽力写 PDC）。 */
    void gsMsSet(Player p, double n, double until, double full) {
        try {
            if (p == null) {
                return;
            }
            String u = String.valueOf(p.getUniqueId());
            gsMsSet0(u, n, until, full, p::getPersistentDataContainer);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsMsSet} 的本体（原脚本 6139~6158 行）—— <b>逐字照抄</b>。
     *
     * <p>★★ 顺序是行为：<b>先写内存（权威）</b>，再尽力写 PDC。
     */
    void gsMsSet0(String u, double n, double until, double full, MsPdcSource src) {
        try {
            int nn = toInt32(n);
            long uu = (long) until;
            int ff = toInt32(full);

            // ① 写内存（权威）
            if (nn <= 0) {
                GS_MS_ST.remove(u);
            } else {
                St s = new St();
                s.n = nn;
                s.until = uu;
                s.full = ff;
                GS_MS_ST.put(u, s);
            }

            // ② 尽力写 PDC（只为跨重启保留；任何失败都不影响上面的内存态）
            if (GS_MS_PDT == null || GS_MS_K_DREAM == null) {
                return;
            }
            try {
                PersistentDataContainer pdc = src.get();
                pdc.set(GS_MS_K_DREAM, PersistentDataType.INTEGER, nn);
                pdc.set(GS_MS_K_UNTIL, PersistentDataType.LONG, gsMsLong(uu));
                pdc.set(GS_MS_K_FULL, PersistentDataType.INTEGER, ff);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 梦：BossBar（原脚本 6160~6305 行）
    // ======================================================================

    /** {@code gsMsCode(name)} —— 颜色名 → {@code §} 码（取不到时 {@code §f}）。 */
    static String gsMsCode(String name) {
        String k = String.valueOf(name == null ? "WHITE" : name);
        k = k.toUpperCase(java.util.Locale.ROOT);
        String v = GS_MS_C.get(k);
        return (v != null) ? v : "§f";
    }

    /** {@code gsMsTitle(text)} —— 单色标题（月镰：整条金色）。 */
    static String gsMsTitle(String text) {
        return GS_MS_C.get("GOLD") + "" + text;
    }

    /** {@code gsMsTitle2(text, suffix, mainColorName)} —— 双色标题（蚀月用）。 */
    static String gsMsTitle2(String text, String suffix, String mainColorName) {
        String s = gsMsCode(mainColorName) + "" + text;
        if (suffix != null && suffix.length() > 0) {
            s += GS_MS_C.get("GOLD") + "" + suffix;
        }
        return s;
    }

    /**
     * JS 的 {@code '' + number}（{@code Number::toString}）。
     *
     * <p>★ 日志行里全是数字拼接：{@code '' + 11.0} 在 JS 里是 {@code "11"}，
     * 而 Java 的 {@code Double.toString(11.0)} 是 {@code "11.0"} ⇒ 不能直接用。
     * 走已验证过的 {@link JsNum#str(double)}（ECMA-262 {@code Number::toString} 的等价实现）。
     */
    static String jsNum(double v) {
        return JsNum.str(v);
    }

    /** {@code gsMsBarColor(full)} —— 满层红 / 未满紫。 */
    static BarColor gsMsBarColor(int full) {
        try {
            String name = (full == 1) ? "RED" : "PURPLE";
            try {
                BarColor c = BarColor.valueOf(name);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 落到下面
            }
            try {
                // 原脚本第二条路是 C[name]（enum 静态字段）—— 与 valueOf 取的是同一个常量
                return BarColor.valueOf(name);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsMsBarStyle()} —— 优先 SEGMENTED_12，其次 SOLID。 */
    static BarStyle gsMsBarStyle() {
        try {
            try {
                BarStyle s = BarStyle.SEGMENTED_12;
                if (s != null) {
                    return s;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
            try {
                BarStyle s2 = BarStyle.valueOf("SEGMENTED_12");
                if (s2 != null) {
                    return s2;
                }
            } catch (Throwable ignored) {
                // 试下一个
            }
            try {
                return BarStyle.valueOf("SOLID");
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsMsBarOf(p)} —— 取 / 建这名玩家的血条。 */
    BossBar gsMsBarOf(Player p) {
        try {
            String u = String.valueOf(p.getUniqueId());
            ListenerCore.BarRec rec = GS_MS_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsMsBarColor(0);
            BarStyle s = gsMsBarStyle();
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            // ★ 标题只喂纯字符串（§ 颜色码没问题，见类注释）
            try {
                bar = Bukkit.getServer().createBossBar(gsMsTitle(gsMsBarText(0)), c, s);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                bar.setVisible(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                bar.addPlayer(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            ListenerCore.BarRec r = new ListenerCore.BarRec();
            r.b = bar;
            r.p = p;
            GS_MS_BARS.put(u, r);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 血条标题正文（原脚本 6291~6292 行）：{@code '梦 ' + nv + '/' + GS_MS_MAX}，
     * 满层再补一句「右键释放“噩梦”」。
     */
    static String gsMsBarText(int nv) {
        String txt = "梦 " + nv + "/" + GS_MS_MAX;
        if (nv >= GS_MS_MAX) {
            txt = txt + " 右键释放“噩梦”";
        }
        return txt;
    }

    /** {@code gsMsBarShow(p, n, left, total, full)} —— 刷血条（标题 / 进度 / 颜色）。 */
    void gsMsBarShow(Player p, int n, double left, double total, int full) {
        try {
            BossBar b = gsMsBarOf(p);
            if (b == null) {
                return;
            }
            double prog = (total > 0) ? (left / total) : 0;
            if (prog < 0) {
                prog = 0;
            }
            if (prog > 1) {
                prog = 1;
            }
            int nv = toInt32(n);
            gsBarTitleSet(b, gsMsTitle(gsMsBarText(nv)));
            try {
                b.setProgress(prog);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                BarColor col = gsMsBarColor(full);
                if (col != null) {
                    b.setColor(col);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                b.setVisible(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsMsBarHide(p)} —— 隐藏（不回收）。 */
    void gsMsBarHide(Player p) {
        ListenerCore.gsBarTblHide(GS_MS_BARS, p);
    }

    /** {@code gsMsClear(p)} —— 层数清零 + 藏血条。 */
    void gsMsClear(Player p) {
        try {
            gsMsSet(p, 0, 0, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsMsBarHide(p);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 局部转调（原脚本用的是全局 {@code gsBarTitleSet}，实现在共享层）。 */
    static void gsBarTitleSet(BossBar bar, String title) {
        ListenerCore.gsBarTitleSet(bar, title);
    }

    // ======================================================================
    // 梦：加层 / 每拍维护（原脚本 6307~6350 行）
    // ======================================================================

    /** {@code gsMsAdd(p)} —— 命中一次叠一层（满层只刷新 30 秒倒计时）。 */
    void gsMsAdd(Player p) {
        try {
            long now = ListenerCore.now();
            St st = gsMsGet(p);
            if (st.n >= GS_MS_MAX) {
                // 已满层：命中只刷新 30 秒倒计时，层数不再增加
                gsMsSet(p, GS_MS_MAX, now + GS_MS_FULL_WIN, 1);
                gsMsBarShow(p, GS_MS_MAX, GS_MS_FULL_WIN, GS_MS_FULL_WIN, 1);
                return;
            }
            int n = st.n + 1;
            if (n >= GS_MS_MAX) {
                gsMsSet(p, GS_MS_MAX, now + GS_MS_FULL_WIN, 1);
                gsMsBarShow(p, GS_MS_MAX, GS_MS_FULL_WIN, GS_MS_FULL_WIN, 1);
                ListenerCore.gsMsSoundAt(p, "block.beacon.activate", 0.9f, 1.4f);
            } else {
                gsMsSet(p, n, now + GS_MS_WINDOW, 0);
                gsMsBarShow(p, n, GS_MS_WINDOW, GS_MS_WINDOW, 0);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsMsPlayerTick(p)} —— 每 2 刻维护一次血条。 */
    void gsMsPlayerTick(Player p) {
        try {
            St st = gsMsGet(p);
            if (st.n <= 0) {
                gsMsBarHide(p);
                return;
            }
            double total = (st.full == 1) ? GS_MS_FULL_WIN : GS_MS_WINDOW;
            long now = ListenerCore.now();
            // ★ 自愈：层数在、但计时丢了（重启用 PDC 恢复失败等）⇒ 按当前阶段补一份，不直接清零
            if (st.until <= 0) {
                gsMsSet(p, st.n, now + total, st.full);
                gsMsBarShow(p, st.n, total, total, st.full);
                return;
            }
            long left = st.until - now;
            if (left <= 0) {
                gsMsClear(p);
                ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 0.6f, 1.4f);
                return;
            }
            gsMsBarShow(p, st.n, left, total, st.full);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 挥砍（原脚本 6352~6487 行）
    // ======================================================================

    /** {@code gsMsCanHit(ent, selfId)} —— 这只生物能不能被挥砍打到。 */
    static boolean gsMsCanHit(Entity ent, String selfId) {
        try {
            if (ent == null) {
                return false;
            }
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                return false;
            }
            if (id.equals(selfId)) {
                return false;
            }
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                return false;
            }
            if (tn.equals("PLAYER") && !GS_MS_HIT_PLAYERS) {
                return false;
            }
            if (tn.equals("ARMOR_STAND")) {
                return false;
            }
            try {
                gsMsProbeHealth(ent);
            } catch (Throwable e) {
                return false;
            }
            try {
                if (ent.isDead()) {
                    return false;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 原脚本 6363 行的探针：{@code try { ent.getHealth(); } catch (e) { return false; }}。
     *
     * <p>★★ <b>必须按「方法是否存在」判定，不能写 {@code ent instanceof Damageable}</b>：
     * {@code javap} 实测 Paper 1.21.11 的 {@code org.bukkit.entity.Item}（掉落物）
     * <b>自己声明了</b> {@code public abstract int getHealth();}（<b>不</b>实现
     * {@code Damageable}）⇒ 掉落物能骗过这个探针、被月镰挥砍打中。
     * 这是原脚本的<b>真实行为</b>：作者在蚀月那边发现后<b>只修了蚀月</b>
     * （{@code gsEcCanHit} 改走 {@code gsIsCombatTarget}），月镰这边保留原样。
     * 用 {@code instanceof Damageable} 会「顺手修好」这个缺陷 ⇒ 属于行为改变，不许。
     */
    private static void gsMsProbeHealth(Entity ent) throws Throwable {
        ent.getClass().getMethod("getHealth").invoke(ent);
    }

    /**
     * 挥砍命中判定（原脚本 6454~6477 行的算式本体，<b>逐字照抄</b>）。
     *
     * <pre>
     *   rx = -fz, rz = fx              // 水平右向
     *   halfW = GS_MS_WIDE * 0.5       // 左右各 1.5 格
     *   fwd = dx*fx + dz*fz            // 前向投影（长度）
     *   lat = dx*rx + dz*rz            // 左右投影（宽度）
     *   fwd ∈ [-0.4, GS_MS_REACH]      // 前方 3 格（身后 0.4 容差）
   *   lat ∈ [-halfW, halfW]
     *   |(ey + 0.9) - (py0 + 1.0)| ≤ GS_MS_TALL
     * </pre>
     *
     * <p>★ 抽成静态方法<b>只为让探针在大量输入上与真 JS 逐值对账</b>，算式一字不改。
     */
    static boolean gsMsSwingHits(double px0, double py0, double pz0, double fx, double fz,
                                 double ex, double ey, double ez) {
        double rx = -fz;
        double rz = fx;
        double halfW = GS_MS_WIDE * 0.5;
        double dx = ex - px0;
        double dz = ez - pz0;
        double fwd = dx * fx + dz * fz;
        double lat = dx * rx + dz * rz;
        if (fwd < -0.4 || fwd > GS_MS_REACH) {
            return false;
        }
        if (lat < -halfW || lat > halfW) {
            return false;
        }
        if (Math.abs((ey + 0.9) - (py0 + 1.0)) > GS_MS_TALL) {
            return false;
        }
        return true;
    }

    /** {@code gsMsSwing(p)} —— 左键挥砍。 */
    void gsMsSwing(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_MS_SWING_AT.get(u);
            if (last != null && (now - last) < GS_MS_SWING_CD) {
                return;
            }
            GS_MS_SWING_AT.put(u, now);

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world = p.getWorld();
            Vector dir;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                return;
            }

            // 水平前向 + 右向（用于摆斜劈的轨迹）
            double fx = dir.getX();
            double fz = dir.getZ();
            double fl = Math.sqrt(fx * fx + fz * fz);
            if (fl < 1.0e-4) {
                fx = 0.0;
                fz = 1.0;
                fl = 1.0;
            }
            fx /= fl;
            fz /= fl;
            double fwx = fx;
            double fwz = fz;
            double rtx = -fz;
            double rtz = fx;

            Integer sideObj = GS_MS_SIDE.get(u);
            int side = (sideObj == null) ? 0 : sideObj;
            GS_MS_SIDE.put(u, (side == 0) ? 1 : 0);      // ★ 左斜 / 右斜交替

            Particle pt = core.gsMsStarType();
            if (pt != null) {
                try {
                    world.spawnParticle(pt, loc.getX() + fwx * 1.2, loc.getY() + 1.2,
                            loc.getZ() + fwz * 1.2, 4, 0.3, 0.3, 0.3, 0.0);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            gsMsSlashFx(world, loc, fwx, fwz, rtx, rtz, side);
            ListenerCore.gsMsSound(world, loc, "entity.player.attack.sweep", 0.75f, 1.25f);

            // ★ 命中范围 = **前方 3 格长 × 3 格宽**的矩形柱
            //   （原先是"沿视线每 0.6 格采一个 1.8 粗的柱"，等于一条线 ⇒ 打不到侧前方的怪）
            //   判定用**水平**前向 / 右向做投影；高度上下各 ±GS_MS_TALL（怪身体中心 vs 玩家胸口）
            String selfId = u;
            Set<String> hit = new HashSet<>();
            int count = 0;
            double px0 = loc.getX();
            double py0 = loc.getY();
            double pz0 = loc.getZ();
            Location cbox = ListenerCore.gsMsLoc(world, px0, py0 + 1.0, pz0);
            if (cbox != null) {
                // 搜索盒：把整个 3×3 区域包进来（对角最远 √(3²+1.5²)≈3.35 ⇒ 取 3.8 保险）
                Collection<Entity> list = null;
                try {
                    list = world.getNearbyEntities(cbox, GS_MS_REACH + 0.8, GS_MS_TALL, GS_MS_REACH + 0.8);
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    Iterator<Entity> it = list.iterator();
                    while (it.hasNext()) {
                        Entity ent = it.next();
                        if (!gsMsCanHit(ent, selfId)) {
                            continue;
                        }
                        String eid;
                        try {
                            eid = String.valueOf(ent.getUniqueId());
                        } catch (Throwable e) {
                            continue;
                        }
                        if (hit.contains(eid)) {
                            continue;
                        }
                        Location el;
                        try {
                            el = ent.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (!gsMsSwingHits(px0, py0, pz0, fx, fz, el.getX(), el.getY(), el.getZ())) {
                            continue;
                        }
                        hit.add(eid);
                        core.gsMsHurt(ent, GS_MS_DMG, p);
                        count++;
                    }
                }
            }
            // ★ 命中 >= 1 个生物 ⇒ 叠一层「梦」
            if (count > 0) {
                gsMsAdd(p);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsMsSlashFx(world, loc, fw, rt, side)} 的第 {@code i} 个采样点
     * （原脚本 6400~6404 行，{@code i = 0..14}）。
     *
     * @return {@code {f, s, h}}：前向距离 / 横向偏移 / 高度
     */
    static double[] gsMsSlashPoint(int i, int n, int side) {
        double t = (double) i / n;                     // ★ JS 浮点除法（纪律 3）
        double f = 0.6 + 2.4 * t;                      // 前向 0.6 → 3.0 格
        double sgn = (side == 0) ? 1.0 : -1.0;
        double s = sgn * (1.50 - 3.00 * t);            // 横向：从 +1.5 到 -1.5（正好 3 格宽）
        double h = 1.75 - 1.30 * t;                    // 高度：1.75 → 0.45（斜着往下劈）
        return new double[]{f, s, h};
    }

    /** {@code gsMsSlashFx(world, loc, fw, rt, side)} —— 斜劈粒子（星星粒子）。 */
    void gsMsSlashFx(World world, Location loc, double fwx, double fwz,
                     double rtx, double rtz, int side) {
        try {
            Particle pt = core.gsMsStarType();
            if (pt == null) {
                return;
            }
            int n = 14;
            for (int i = 0; i <= n; i++) {
                double[] p = gsMsSlashPoint(i, n, side);
                double f = p[0];
                double s = p[1];
                double h = p[2];
                try {
                    world.spawnParticle(pt,
                            loc.getX() + fwx * f + rtx * s,
                            loc.getY() + h,
                            loc.getZ() + fwz * f + rtz * s,
                            1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 噩梦（原脚本 6489~6616 行）
    // ======================================================================

    /**
     * {@code gsMsLock(ent)} —— 定身 3 秒 + 紫色粒子柱。
     *
     * <p>★ 定身机制本身在共享层 {@link ListenerCore#gsMsLockFor}（蚀月轮 / 审判法阵也复用它）：
     * ①已在名单里只刷新到期时间、位置钉在<b>第一次</b>锁定的那一点；
     * ②记录原来的 {@code hasAI()}，解锁时原样还原。
     */
    void gsMsLock(Entity ent) {
        core.gsMsLockFor(ent, GS_MS_NM_LOCK, NM_COLOR);
    }

    /** {@code gsMsLockTick()} —— 每 2 刻把被定身的生物钉回原位 + 画粒子柱。 */
    void gsMsLockTick() {
        try {
            if (core.gsMsLocks.isEmpty()) {
                return;
            }
            long now = ListenerCore.now();
            List<ListenerCore.Lock> keep = new ArrayList<>();
            Particle dust = core.gsMsDustType();
            for (ListenerCore.Lock r : core.gsMsLocks) {
                boolean alive;
                try {
                    alive = !(r.e.isDead() || !r.e.isValid());
                } catch (Throwable e) {
                    alive = false;
                }
                if (!alive || now >= r.until) {
                    try {
                        if (r.e instanceof LivingEntity) {
                            ((LivingEntity) r.e).setAI(r.ai);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    continue;
                }
                // 钉在原位（teleport 才拉得住，setVelocity 会被 AI 顶回去）
                Location back = ListenerCore.gsMsLoc(r.w, r.x, r.y, r.z);
                if (back != null) {
                    try {
                        r.e.teleport(back);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                try {
                    r.e.setVelocity(ListenerCore.gsMsVec(0.0, 0.0, 0.0));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                // 粒子柱（颜色随记录走；col == null ⇒ 只定身、不画柱）
                if (dust != null && r.col != null) {
                    Particle.DustOptions data = ListenerCore.gsMsDustOpts(
                            r.col.getRed(), r.col.getGreen(), r.col.getBlue(), 1.2);
                    for (double h = 0.0; h <= GS_MS_LOCK_STICK + 0.01; h += 0.2) {
                        ListenerCore.gsMsDust(r.w, dust, data, r.x, r.y + h, r.z);
                    }
                }
                keep.add(r);
            }
            // ★ 原脚本是 `GS_MS_LOCKS = keep`（换一个新数组）；Java 里 gsMsLocks 是 final 列表
            //   ⇒ 先收集再 clear + addAll。所有读者都走同一个列表对象，语义相同。
            core.gsMsLocks.clear();
            core.gsMsLocks.addAll(keep);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsMsReleaseAll()} —— 旧实例被顶替时，先把自己锁住的生物放掉（避免 reload 后永久定身）。 */
    void gsMsReleaseAll() {
        try {
            for (ListenerCore.Lock r : core.gsMsLocks) {
                try {
                    if (r.e instanceof LivingEntity) {
                        ((LivingEntity) r.e).setAI(r.ai);
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            core.gsMsLocks.clear();
            for (ListenerCore.BarRec rec : GS_MS_BARS.values()) {
                try {
                    rec.b.removeAll();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            GS_MS_BARS.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 噩梦 24 根射线在半径 {@code d} 上的采样点序列（原脚本 6586 行的 {@code d} 循环）。 */
    static double[] gsMsNightmareRayDists() {
        List<Double> out = new ArrayList<>();
        for (double d = 0.4; d <= GS_MS_NM_R + 0.01; d += 0.3) {
            out.add(d);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /** 噩梦第 {@code i} 根射线的方向（原脚本 6584~6585 行）。 */
    static double[] gsMsNightmareRayDir(int i) {
        double a = (Math.PI * 2.0 * i) / GS_MS_NM_RAYS;
        return new double[]{Math.cos(a), Math.sin(a)};
    }

    /** 噩梦的**圆形**判定（原脚本 6607~6609 行）：水平距离 ≤ 5 格 且 垂直 ≤ 2.5 格。 */
    static boolean gsMsNightmareInRange(double ex, double ey, double ez,
                                        double bx, double by, double bz) {
        double hx = ex - bx;
        double hz = ez - bz;
        if (Math.sqrt(hx * hx + hz * hz) > GS_MS_NM_R) {
            return false;
        }
        if (Math.abs(ey - by) > 2.5) {
            return false;
        }
        return true;
    }

    /** {@code gsMsNightmare(p)} —— 满 12 层右键：24 根紫色射线 + 35 点伤害 + 定身 3 秒。 */
    boolean gsMsNightmare(Player p) {
        try {
            St st = gsMsGet(p);
            if (st.n < GS_MS_MAX) {
                return false;                      // 未满 12 层 ⇒ 右键不做任何事
            }
            gsMsClear(p);                          // 先清空，防重复触发

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return true;
            }
            World world = p.getWorld();
            double bx = loc.getX();
            double by = loc.getY() + GS_MS_NM_Y;   // ★ 大腿根部
            double bz = loc.getZ();

            // ① 24 根紫色射线（水平均分，半径 5 格）
            Particle pt = core.gsMsDustType();
            Particle.DustOptions data = ListenerCore.gsMsDustOpts(
                    GS_MS_NM_COL[0], GS_MS_NM_COL[1], GS_MS_NM_COL[2], 1.0);
            if (pt != null) {
                double[] dists = gsMsNightmareRayDists();
                for (int i = 0; i < GS_MS_NM_RAYS; i++) {
                    double[] dir = gsMsNightmareRayDir(i);
                    for (double d : dists) {
                        ListenerCore.gsMsDust(world, pt, data, bx + dir[0] * d, by, bz + dir[1] * d);
                    }
                }
            }

            ListenerCore.gsMsSound(world, loc, "entity.warden.sonic_boom", 1.5f, 0.8f);
            ListenerCore.gsMsSound(world, loc, "entity.ender_dragon.growl", 1.2f, 1.2f);

            // ② 半径 5 格内的生物：35 点伤害 + 定身
            String selfId = ListenerCore.uid(p);
            Location box = ListenerCore.gsMsLoc(world, bx, by, bz);
            Collection<Entity> list = null;
            try {
                list = world.getNearbyEntities(box, GS_MS_NM_R, 2.5, GS_MS_NM_R);
            } catch (Throwable e) {
                list = null;
            }
            if (list != null) {
                Iterator<Entity> it = list.iterator();
                while (it.hasNext()) {
                    Entity ent = it.next();
                    if (!gsMsCanHit(ent, selfId)) {
                        continue;
                    }
                    Location el;
                    try {
                        el = ent.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    if (!gsMsNightmareInRange(el.getX(), el.getY(), el.getZ(), bx, by, bz)) {
                        continue;                  // 圆形判定，不是方盒
                    }
                    core.gsMsHurt(ent, GS_MS_NM_DMG, p);
                    gsMsLock(ent);
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 心跳 / 世代号（原脚本 6618~6668 行）
    // ======================================================================

    /**
     * {@code gsMsGenNow()} —— 当前世界 PDC 上的世代号；<b>读不到返回 null</b>。
     *
     * <p>★ 返回 null 表示「读不到 / 无法判断」——调用方据此<b>不要</b>把当前实例当成旧实例，
     * 否则 PDC 一旦不可用，世代号校验会把心跳整个停掉（血条 / 定身都再不维护）。
     */
    Integer gsMsGenNow() {
        try {
            if (GS_MS_PDT == null || GS_MS_K_GEN == null) {
                return null;
            }
            World w = Bukkit.getServer().getWorlds().get(0);
            Integer v = w.getPersistentDataContainer().get(GS_MS_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? 0 : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsMsGenBump()} —— 世代号 +1 并写回世界 PDC。 */
    void gsMsGenBump() {
        try {
            Integer cur = gsMsGenNow();
            if (cur == null) {
                cur = 0;                           // 读不到就从 0 起算
            }
            GS_MS_GEN = toInt32(cur) + 1;
            if (GS_MS_PDT == null || GS_MS_K_GEN == null) {
                return;
            }
            World w = Bukkit.getServer().getWorlds().get(0);
            w.getPersistentDataContainer().set(GS_MS_K_GEN, PersistentDataType.INTEGER, (int) GS_MS_GEN);
        } catch (Throwable e) {
            if (GS_MS_GEN <= 0) {
                GS_MS_GEN = 1;
            }
        }
    }

    /** {@code gsMsBarsClean()} —— 清扫已下线玩家的血条。 */
    void gsMsBarsClean() {
        ListenerCore.gsBarTblClean(GS_MS_BARS);
    }

    /**
     * {@code gsMsPulse()} —— 每 <b>2</b> 刻跑一次的心跳（原脚本 6829 行
     * {@code runRepeating(gsMsPulse, 20, 2)}：<b>delay = 20、period = 2</b>，两个参数不同）。
     *
     * <p>★ 它<b>同时驱动蚀月</b>的血条（原脚本 6662 / 6667 行），见 {@link #ec}。
     */
    void gsMsPulse() {
        boolean mine = true;
        try {
            Integer g = gsMsGenNow();
            // ★ 只有「确实读到了世代号，且和我的不一样」才认为自己是旧实例；
            //   读不到（null）时一律按自己的算，保证心跳永远在跑。
            if (g != null && GS_MS_GEN != -1) {
                mine = (g.intValue() == GS_MS_GEN);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (!mine) {
            // /rsc reload 后旧实例：把自己的定身和血条收干净，然后彻底停手
            gsMsReleaseAll();
            return;
        }
        try {
            Collection<? extends Player> players = Bukkit.getServer().getOnlinePlayers();
            Iterator<? extends Player> it = players.iterator();
            while (it.hasNext()) {
                Player p = it.next();
                try {
                    gsMsPlayerTick(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    EcSub e = ec;                  // ★ 第十五批：蚀月「血」血条
                    if (e != null) {
                        e.gsEcPlayerTick(p);
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsMsLockTick();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsMsBarsClean();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            EcSub e = ec;                          // ★ 第十五批：蚀月血条清理
            if (e != null) {
                e.gsEcBarsClean();
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 去抖（原脚本 6670~6688 行）
    // ======================================================================

    /** {@code gsMsClickOnce(p, tag)} —— 80 毫秒内同一键只处理一次。 */
    boolean gsMsClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_MS_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_MS_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsMsRecentRight(p, gapMs)} —— 刚刚处理过右键吗（右键附带的挥手要忽略）。 */
    boolean gsMsRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_MS_CLICK_AT.get(ListenerCore.uid(p) + "|R");
            if (last == null) {
                return false;
            }
            return (ListenerCore.now() - last) < gapMs;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 监听器（原脚本 6724~6811 行）—— 4 处 registerEvent 的 Java 版
    // ======================================================================

    /**
     * 原脚本 4 处 {@code registerEvent} 的 Java 版。
     *
     * <p>★ 全部 {@code Priority.HIGHEST}、{@code ignoreCancelled} 保持默认 <b>false</b>
     * —— 与 {@code registerEvent} 不传第 5 个参数逐处对应。
     *
     * <p>★★ ① {@code PlayerInteractEvent} <b>故意不检查</b> {@code e.isCancelled()}：
     * 有些前置插件会先取消 {@code *_CLICK_AIR}，一检查就变成"必须点方块才放得出来"。
     * 重复注册交给去抖（Java 版改成幂等注册后这一层更干净）。
     */
    final class Sync implements Listener {

        /**
         * ① 左键 = 挥砍（同时挖不动方块）；右键 = 满 12 层放噩梦（其余情况只取消原版交互）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsMsIs(gsMsHand(p))) {
                    return;
                }
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (!hs.isEmpty() && hs.contains("OFF")) {
                    return;                        // 只认主手
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }

                if (act.contains("RIGHT_CLICK")) {
                    // ★ 取消右键交互（铁锄不许耕地、不许开箱子 / 放方块）
                    e.setCancelled(true);
                    try {
                        e.setUseInteractedBlock(Event.Result.DENY);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        e.setUseItemInHand(Event.Result.DENY);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    if (!gsMsClickOnce(p, "R")) {
                        return;
                    }
                    gsMsNightmare(p);              // 未满 12 层时内部直接返回
                } else if (act.contains("LEFT_CLICK")) {
                    if (!gsMsClickOnce(p, "L")) {
                        return;
                    }
                    if (GS_MS_NO_BREAK) {
                        e.setCancelled(true);
                        try {
                            e.setUseInteractedBlock(Event.Result.DENY);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                        try {
                            e.setUseItemInHand(Event.Result.DENY);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                    gsMsSwing(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② 左键兜底：挥手动画（对着空气点也会发；与 ① 共用去抖 ⇒ 不会放两次）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsMsIs(gsMsHand(p))) {
                    return;
                }
                try {
                    org.bukkit.event.player.PlayerAnimationType at = e.getAnimationType();
                    if (at != null) {
                        String an = String.valueOf(at.name());
                        if (!an.contains("SWING") && !an.contains("ARM")) {
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                if (gsMsRecentRight(p, 300)) {
                    return;                        // 右键附带的挥手 → 忽略
                }
                if (!gsMsClickOnce(p, "L")) {
                    return;
                }
                gsMsSwing(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ 手持月镰 → 挖不动方块（双保险：即使 ① 的取消被别的插件放行也挖不掉）。
         *
         * <p>★ 第一行的 {@code GS_MS_NO_BREAK} 判断是<b>结构性差异</b>：原脚本是在
         * <b>注册时</b>判一次（6790 行 {@code if (GS_MS_NO_BREAK) { ... reg(BreakEv, ...) ... }}），
         * 常量恒 {@code true} ⇒ 处理体总是被注册。Java 的 {@code @EventHandler} 是编译期
         * 确定的，没法表达「注册时条件开关」，所以把开关挪进处理体第一行。
         * 行为等价（常量恒 true ⇒ 这行永不生效），位置不同。蚀月侧同款。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!GS_MS_NO_BREAK) {
                    return;                            // ← 原脚本的注册期开关
                }
                if (!gsMsIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 之二：{@code BlockDamageEvent}（左键点方块的瞬间）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockDamage(BlockDamageEvent e) {
            try {
                if (!GS_MS_NO_BREAK) {
                    return;                            // ← 原脚本的注册期开关
                }
                if (!gsMsIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsMsRegisterSync()} —— 注册同步监听器（<b>幂等</b>）。
     *
     * <p>★ 返回 false 表示「没注册上」（原脚本在找不到 plugin 时返回 false，
     * {@code gsMsStart} 会打一条「启动失败」并 return）。
     */
    boolean gsMsRegisterSync() {
        if (GS_MS_REGISTERED) {
            return true;
        }
        try {
            if (listener == null) {
                listener = new Sync();
            }
            Bukkit.getPluginManager().registerEvents(listener, com.qinghuacisj.gs.GSPlugin.instance());
            GS_MS_REGISTERED = true;
            try {
                Bukkit.getLogger().info(gsMsRegisterLine());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning(ListenerCore.PREFIX + "月镰监听器注册异常: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    // ======================================================================
    // 日志行（抽成静态方法，只为让探针逐字节对账）
    // ======================================================================

    /** 原脚本 6814 行的注册行（<b>含</b>前缀）。 */
    static String gsMsRegisterLine() {
        return ListenerCore.PREFIX + "月镰监听器注册成功（左键挥砍叠梦 / 满 12 层右键噩梦）";
    }

    /**
     * 原脚本 6831~6833 行的状态行（<b>不含</b>前缀）。
     *
     * <p>★ {@code pdcOk} 是脚本自己探测的结果（{@code GS_MS_PDT / GS_MS_K_* } 四个都非 null），
     * <b>必须保留这个分支</b>（{@link #gsMsPdcOk()}）。
     *
     * <p>★ {@code gen} 是<b>运行期动态值</b>（世界世代号，每 reload +1）——
     * 参考服日志里是 {@code 96}，别硬编码。
     */
    static String gsMsStatusMsg(int gen, boolean pdcOk) {
        return "月镰状态：层数存内存（权威）· PDC 备份 " + (pdcOk ? "可用" : "不可用")
                + " · 世界世代号 " + gen;
    }

    /** 带前缀的完整状态行。 */
    static String gsMsStatusLine(int gen, boolean pdcOk) {
        return ListenerCore.PREFIX + gsMsStatusMsg(gen, pdcOk);
    }

    /** {@code pdcOk} 探测（原脚本 6831 行）。 */
    boolean gsMsPdcOk() {
        return (GS_MS_PDT != null) && (GS_MS_K_DREAM != null)
                && (GS_MS_K_UNTIL != null) && (GS_MS_K_FULL != null);
    }

    /**
     * 原脚本 6836~6839 行的加载行（<b>不含</b>前缀）。
     *
     * <p>★ 所有数值都走 {@link #jsNum}（JS 的 {@code Number::toString}）：
     * {@code '' + 11.0} 在 JS 里是 {@code "11"}，Java 的 {@code Double.toString} 是 {@code "11.0"}。
     * {@code GS_MS_NM_LOCK / 1000} 是 JS 的<b>浮点</b>除法 ⇒ Java 写 {@code / 1000.0}（纪律 3）。
     */
    static String gsMsLoadMsg() {
        return "月镰（" + GS_MS_ID + "）已加载 · 挥砍 "
                + jsNum(GS_MS_DMG) + " 点 / 范围 " + jsNum(GS_MS_REACH) + " 格长 × " + jsNum(GS_MS_WIDE)
                + " 格宽 / 满 " + GS_MS_MAX + " 层 / 噩梦 " + jsNum(GS_MS_NM_DMG)
                + " 点 · 半径 " + jsNum(GS_MS_NM_R) + " 格 / 定身 " + jsNum(GS_MS_NM_LOCK / 1000.0) + " 秒";
    }

    /** 带前缀的完整加载行。 */
    static String gsMsLoadLine() {
        return ListenerCore.PREFIX + gsMsLoadMsg();
    }

    // ======================================================================
    // 入口（= 原脚本顶层 gsMsStart(); 第 6846 行）
    // ======================================================================

    /** {@code gsMsStart()} —— 抬世代号 + 注册监听器 + 排心跳 + 打加载日志。 */
    void gsMsStart() {
        try {
            gsMsGenBump();
            if (!gsMsRegisterSync()) {
                try {
                    Bukkit.getLogger().warning(ListenerCore.PREFIX + "月镰启动失败：监听器没注册上");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            // ★ delay = 20 刻、period = 2 刻（两个参数不同，别抄成一个）
            GS.runRepeating(this::gsMsPulse, 20, 2);
            try {
                ListenerCore.gsDaLog(gsMsStatusMsg((int) GS_MS_GEN, gsMsPdcOk()));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ListenerCore.gsDaLog(gsMsLoadMsg());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning(ListenerCore.PREFIX + "月镰启动异常: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 供探针断言「GS_MS_C 表逐项一致」用。 */
    static Map<String, String> msColorTable() {
        return GS_MS_C;
    }
}
