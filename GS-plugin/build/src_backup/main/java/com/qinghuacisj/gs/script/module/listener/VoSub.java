package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「致万千无言者」子系统
 * （原脚本第 <b>7791~8599</b> 行，顶层入口 {@code gsVoStart()}，第 8599 行）。
 *
 * <p><b>玩法</b>：构思武器第 2 格（石镐材质，{@code GS_VOICELESS}）。
 * <ul>
 *   <li><b>左键 = 挥砍</b>：前方「长 3 × 宽 3」的水平长条（上下容差
 *       {@code GS_VO_SWING_H = 1.6}），每刀造成 {@code gsVoDmg(打完之后的层数)} 点伤害
 *       （1 层 = 7 点，满 7 层 = 13 点，每层 +1）。命中 <b>≥1</b> 个生物 ⇒ 连击 +1。</li>
 *   <li><b>右键 = 蓄力 0.6 秒放「逝言」</b>：沿准星方向逐刻前进 0.5 格（{@code < 1.0}
 *       ⇒ 不会一步跨过整块墙），每拍判定一个「垂直于弹道的弧面」，最多穿透 4 个生物、
 *       最远 7 格、撞墙即止。每个生物 11 点。命中 ⇒ 连击 +1（一次攻击只 +1，
 *       不是每只 +1 —— 否则一发穿 4 个白送 4 层）。</li>
 *   <li><b>连击「言」窗口 3 秒</b>：★ <b>未命中逐层衰减（-1），不是清零</b> ——
 *       这是与「此刻万籁俱寂」的关键差异（那边是直接清 0）。</li>
 *   <li>血条标题 {@code 言 N/7}（整条灰色 {@code §7}）。</li>
 * </ul>
 *
 * <p>★★ <b>与「此刻万籁俱寂」的参数差异（别直接套 {@link ListenerCore#gsSiDmg}）</b>：
 * <pre>
 *   致万千无言者：BASE 7.0  PER 1.0  MAX 7    ⇒ 7~13 点   · 窗口 3 秒 · 未命中**掉一层**
 *   此刻万籁俱寂：BASE 17.0 PER 2.0  MAX 99   ⇒ 17~213 点 · 窗口 7 秒 · 未命中**清 0**
 * </pre>
 * 两边共用的是<b>算法本体</b>（{@code k} 钳到 {@code 1..MAX} 后 {@code BASE + PER*(k-1)}），
 * 所以本类调用 {@link ListenerCore#gsSiDmg(int, int, double, double)} 并<b>自己传参数</b>。
 *
 * <h2>★ 与原脚本的有意差异（修掉一个真实缺陷）</h2>
 * <b>原脚本 reload 会累积监听器，Java 版改为幂等注册。</b>
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} 在脚本里自己注册 4 个监听器，
 * 而 {@code /rsc reload} 会重新 eval 整个脚本 ⇒ 监听器每次 reload 都再注册一遍、
 * 不断累积（原脚本的 {@code GS_VO_REGISTERED} 闩锁是脚本级变量，reload 后归零，
 * 挡不住累积）。Java 版把监听器写成 {@link Listener} 实现类、用
 * {@code registerEvents} <b>只注册一次</b>：事件照常收到、优先级照常、取消照常，
 * 可观察行为相同，但 reload 不再累积。★ 这不是「顺手优化」，是修一个真实缺陷。
 *
 * <p>★ 优先级与 {@code ignoreCancelled} 逐处核对过：原脚本 4 处
 * {@code pm.registerEvent(类, plugin, Priority.HIGHEST, executor, plugin)} <b>都没有第 5 个参数</b>
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 的 {@code @EventHandler} 默认也是 false
 * ⇒ 不加该属性即等价（<b>绝不写成 true</b>）。
 *
 * <h2>死代码 / 走不到的分支（不照抄，在此存档）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 8423~8425 行）：原脚本在
 *       「直接传 JS 函数当 EventExecutor」失败时，退路是用 {@code new JavaAdapter(ListenerC, {})}
 *       造监听器。★ 已用 {@code tools/GraalProbe.java} 实测：GraalJS 25.1.3 里
 *       <b>{@code JavaAdapter} 是 undefined</b> ⇒ 这条退路<b>永远走不到</b>。
 *       Java 版直接用原生 {@link Listener}，两条路合一。</li>
 *   <li><b>{@code plugin} 四级回退</b>（原脚本 8406~8411 行：RSC 静态单例 →
 *       {@code RykenSlimeCustomizer} → {@code RykenSlimefunCustomizer} → {@code Slimefun}）：
 *       那是为了「拿不到 RSC 主类就退而求其次」拿任意一个 Plugin 实例来注册。
 *       Java 版自己有 Plugin 实例（{@link GS#plugin()}）⇒ 整条回退链作废；
 *       {@code if (plugin == null) return false;} 那条<b>恒不执行</b>。</li>
 *   <li><b>{@code GS_VO_PDT}</b>（原脚本 7841 行 {@code Java.type('...PersistentDataType')}
 *       的探测结果）：Java 里该类必然可用 ⇒ 只保留 {@code GS_VO_K_GEN != null} 这一半判断
 *       （{@code NamespacedKey.fromString} 恒非 null，所以那一半也恒真，
 *       保留它只为逐字对齐结构）。</li>
 *   <li><b>{@code gsVoEnchType()} 的 {@code P[name]} 分支</b>（原脚本 7865 行）：
 *       {@code Particle} 是 enum，它的公开静态字段恰好就是枚举常量集合 ⇒
 *       {@code P[name]} 与 {@code P.valueOf(name)} 取的是同一批名字。
 *       Java 侧合并成一次 {@code valueOf}（实现在 {@link ListenerCore#gsVoEnchType()}）。</li>
 *   <li><b>{@code gsVoRegisterSync()} 的失败分支</b>（原脚本 8580~8582 行的
 *       「致万千无言者启动失败：监听器没注册上」）：只有 plugin 拿不到才会走到，
 *       Java 里恒不执行。★ 但<b>日志文案照抄保留</b>，因为它是可观察行为的一部分
 *       （万一将来注册真的失败，必须打同一句话）。</li>
 * </ol>
 *
 * <h2>★ {@code GS_VO_REGISTERED} 闩锁</h2>
 * 原脚本里它<b>只写不读</b>（写：7827 行定义 + 8523 行置 true；读：只在 8401 行的
 * {@code if (GS_VO_REGISTERED) return true;} 早退里）。Java 里保留成<b>实例字段</b>：
 * 因为 {@code ListenerModule} 只有一个实例，它天然等价于「幂等注册一次」。
 */
public final class VoSub {

    // ======================================================================
    // 参数（名字必须与 JS 顶层 var 同名 —— 常量对账探针按名字反射比对）
    // ======================================================================

    static final String GS_VO_ID = "GS_VOICELESS";
    /** {@code true = 手持时挖不动方块（石镐不许挖矿）}。 */
    static final boolean GS_VO_NO_BREAK = true;
    /** {@code false = 挥砍 / 逝言不打其它玩家（只打生物），防误伤}。 */
    static final boolean GS_VO_HIT_PLAYERS = false;

    // ---- 被动：连击「言」 ----
    /** 最高连击数。 */
    static final int GS_VO_MAX = 7;
    /** 命中窗口（毫秒）= 3 秒。 */
    static final long GS_VO_WINDOW = 3000L;
    /** 1 连击时的伤害（= 初始伤害 7）。 */
    static final double GS_VO_DMG_BASE = 7.0;
    /** 每多一层连击的加成 ⇒ 满层 = 7 + 6×1 = 13。 */
    static final double GS_VO_DMG_PER = 1.0;
    /**
     * 血条颜色：原版 {@code BarColor} <b>没有灰色</b>（只有
     * PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE）⇒ 取最接近的 WHITE，
     * 「灰」由标题文字承担。
     */
    static final String GS_VO_BAR_COL = "WHITE";

    // ---- 左键：挥砍 ----
    /** 判定长度：前方 3 格。 */
    static final double GS_VO_SWING_R = 3.0;
    /** 判定宽度：共 3 格宽（左右各 1.5）。 */
    static final double GS_VO_SWING_W = 3.0;
    /** 判定上下高度（格）。 */
    static final double GS_VO_SWING_H = 1.6;
    /** 挥砍冷却（毫秒）。 */
    static final long GS_VO_SWING_CD = 320L;

    // ---- 右键：逝言 ----
    /** 蓄力刻数（12 刻 = 0.6 秒）。 */
    static final int GS_VO_QI_CHARGE = 12;
    /** 最远 7 格。 */
    static final double GS_VO_QI_RANGE = 7.0;
    /** 宽度 3 格（左右各 1.5）。 */
    static final double GS_VO_QI_WIDE = 3.0;
    /** 弧形斩击的上下判定高度（格）。 */
    static final double GS_VO_QI_H = 1.8;
    /** 最多穿透 4 个生物（打完第 4 个就散）。 */
    static final int GS_VO_QI_PIERCE = 4;
    /** 每个生物 11 点。 */
    static final double GS_VO_QI_DMG = 11.0;
    /** 每刻前进（格）—— {@code < 1.0} ⇒ 不会一步跨过整块墙。 */
    static final double GS_VO_QI_STEP = 0.5;
    /** 发射冷却（毫秒）。 */
    static final long GS_VO_QI_CD = 1200L;
    /** 弹道残留附魔文字（刻）⇒ 8 刻 ≈ 0.4 秒。 */
    static final int GS_VO_QI_TRAIL = 8;
    /** 逝言命中也叠连击（{@code false} = 只有左键挥砍叠）。 */
    static final boolean GS_VO_QI_STACK = true;
    /** 灰色弧形斩击（RGB）。 */
    static final int[] GS_VO_QI_COL = {150, 150, 150};

    /**
     * ★ 本模块<b>自己的</b>世界世代号键 {@code gs:vo_gen} —— 不复用月镰的 {@code gs:ms_gen}：
     * 复用就等于把「重载后旧实例停手」押在月镰模块上（月镰启动失败时它不会 bump），
     * 自己一份才能保证 {@code /rsc reload} 后本模块的旧实例一定会收手。
     */
    static final NamespacedKey GS_VO_K_GEN = NamespacedKey.fromString("gs:vo_gen");

    /**
     * {@code GS_MS_C} —— 月镰子系统的共享色表（原脚本 6171~6175 行）。
     *
     * <p>★★ {@link ListenerCore} 里<b>没有</b>它（见交付说明第 4 条），所以在用到它的
     * 三个类（VoSub / SiSub / EmSub）里各留一份<b>逐字副本</b>；
     * 集成者把它提到 {@code ListenerCore} 后，这三份副本应整体删除。
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
        return java.util.Collections.unmodifiableMap(m);
    }

    // ======================================================================
    // 运行期状态（原脚本的顶层 var，★ 非静态：实例 = 原脚本的一次顶层求值）
    // ======================================================================

    /**
     * ★★ <b>调度接缝</b> —— 只为可测性存在：默认<b>恒等于</b> {@link GS#runLater}。
     *
     * <p>为什么需要它：原脚本的「逝言」用 {@code runLater(step, 1)} 一拍一拍推进
     * （蓄力 12 拍 + 弹道每拍 0.5 格），这是<b>闭包递归</b>，没有 Bukkit 调度器就一步都跑不动。
     * 对账探针把它换成「入队 + 手动驱动」的版本，就能在没有服务器的环境里
     * <b>逐拍</b>把整条蓄力 / 弹道跑完，与真 JS 的输出逐值对账。
     *
     * <p>★ 生产路径上它<b>恒等于</b> {@code GS::runLater} ⇒ 不改变任何行为，
     * 也不改变 {@code try/catch} 结构（调用点仍在原来的 try 块里）。
     */
    static java.util.function.BiConsumer<Runnable, Long> gsVoRunLater = GS::runLater;

    /** {@code GS_VO_REGISTERED} —— 幂等闩锁（见类注释）。 */
    boolean GS_VO_REGISTERED = false;
    /** {@code GS_VO_CLICK_AT} —— {@code uuid|L} / {@code uuid|R} → 上次处理毫秒（去抖）。 */
    final Map<String, Long> GS_VO_CLICK_AT = new HashMap<>();
    /** {@code GS_VO_SWING_AT} —— uuid → 上次挥砍毫秒（冷却）。 */
    final Map<String, Long> GS_VO_SWING_AT = new HashMap<>();
    /** {@code GS_VO_FIRE_AT} —— uuid → 上次「逝言」发射毫秒（冷却）。 */
    final Map<String, Long> GS_VO_FIRE_AT = new HashMap<>();
    /** {@code GS_VO_CHARGING} —— uuid → {@code true}（正在蓄力，防重复触发）。 */
    final Map<String, Boolean> GS_VO_CHARGING = new HashMap<>();
    /** {@code GS_VO_ST} —— uuid → {@code { n, until }}（内存权威）。 */
    final Map<String, VoSt> GS_VO_ST = new HashMap<>();
    /** {@code GS_VO_BARS} —— uuid → {@code { b, p }}（★ 本模块专属表）。 */
    final Map<String, ListenerCore.BarRec> GS_VO_BARS = ListenerCore.newBarTable();
    /** {@code GS_VO_GEN} —— 加载那一刻的世界世代号（判 {@code /rsc reload} 旧实例）。 */
    int GS_VO_GEN = -1;

    private final ListenerCore core;

    public VoSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsVoStart();} 调用（原脚本第 8599 行）。 */
    public void start() {
        gsVoStart();
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    /** {@code gsVoIs(item)}。 */
    boolean gsVoIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_VO_ID);
    }

    /** {@code gsVoHand(p)} —— 主手物品（拿不到返回 null）。 */
    static ItemStack gsVoHand(Player p) {
        try {
            return p.getInventory().getItemInMainHand();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsMsBarStyle()} —— 原脚本 6252~6260 行（月镰子系统）。
     *
     * <p>★★ {@link ListenerCore} 里没有它（见交付说明第 4 条）。
     * 三段式：① {@code S.SEGMENTED_12} ② {@code valueOf('SEGMENTED_12')}
     * ③ {@code valueOf('SOLID')}。Java 里 ① 必然成功 ⇒ ②③ 走不到，
     * 但保留结构以便逐字对账。
     */
    static BarStyle gsMsBarStyle() {
        try {
            BarStyle s = BarStyle.SEGMENTED_12;
            if (s != null) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到下一档
        }
        try {
            BarStyle s2 = BarStyle.valueOf("SEGMENTED_12");
            if (s2 != null) {
                return s2;
            }
        } catch (Throwable ignored) {
            // 落到下一档
        }
        try {
            return BarStyle.valueOf("SOLID");
        } catch (Throwable ignored) {
            // 与原脚本一致：返回 null
        }
        return null;
    }

    /**
     * JS 的 {@code String(数字)} —— <b>最短可往返</b>表示：整数值不带 {@code .0}
     * （{@code String(1000) === "1000"}），指数记数法用小写 {@code e} 且带符号
     * （{@code String(1e21) === "1e+21"}）。
     *
     * <p>★ 为什么不能直接用 {@code String.valueOf(double)}：Java 的
     * {@code Double.toString} 会输出 {@code "7.0"} / {@code "1.0E21"} ——
     * 与 JS <b>逐字节不同</b>，而本类的加载日志里就拼了数字。
     * 做法：取 Java 的<b>最短</b>十进制数字串（Java 与 JS 的「最短可往返」
     * 位数集合一致），再按 ECMAScript {@code Number::toString} 的排版规则重排。
     * ★ 已由 {@code tools/ListenerESubParityProbe.java} 用真 GraalJS 在网格上对账。
     */
    static String jsNum(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0.0) {
            return "0";                                  // String(-0) === "0"
        }
        String sign = (v < 0) ? "-" : "";
        String s = Double.toString(Math.abs(v));
        int exp10 = 0;
        int e = s.indexOf('E');
        if (e >= 0) {
            exp10 = Integer.parseInt(s.substring(e + 1));
            s = s.substring(0, e);
        }
        int dot = s.indexOf('.');
        int intLen = (dot < 0) ? s.length() : dot;
        String digits = (dot < 0) ? s : s.substring(0, dot) + s.substring(dot + 1);
        int lead = 0;
        while (lead < digits.length() - 1 && digits.charAt(lead) == '0') {
            lead++;
        }
        digits = digits.substring(lead);
        int end = digits.length();
        while (end > 1 && digits.charAt(end - 1) == '0') {
            end--;
        }
        digits = digits.substring(0, end);
        int k = digits.length();
        int n = exp10 + intLen - lead;                   // 值 = 0.d1d2…dk × 10^n
        StringBuilder sb = new StringBuilder(sign);
        if (k <= n && n <= 21) {
            sb.append(digits);
            for (int i = 0; i < n - k; i++) {
                sb.append('0');
            }
        } else if (0 < n && n <= 21) {
            sb.append(digits, 0, n).append('.').append(digits, n, k);
        } else if (-6 < n && n <= 0) {
            sb.append("0.");
            for (int i = 0; i < -n; i++) {
                sb.append('0');
            }
            sb.append(digits);
        } else {
            int ex = n - 1;
            sb.append(digits.charAt(0));
            if (k > 1) {
                sb.append('.').append(digits, 1, k);
            }
            sb.append('e').append(ex >= 0 ? "+" : "-").append(Math.abs(ex));
        }
        return sb.toString();
    }

    // ======================================================================
    // 连击「言」：读写（内存权威，不写 PDC）
    // ======================================================================

    /** {@code GS_VO_ST[uuid]} 的记录（原脚本 7832 行的对象字面量 {@code { n, until }}）。 */
    static final class VoSt {
        int n;
        long until;
    }

    /** {@code gsVoGet(p)}。 */
    VoSt gsVoGet(Player p) {
        VoSt o = new VoSt();
        try {
            if (p == null) {
                return o;
            }
            VoSt m = GS_VO_ST.get(ListenerCore.uid(p));
            if (m != null) {
                o.n = m.n;
                o.until = m.until;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return o;
    }

    /** {@code gsVoSet(p, n, until)} —— {@code n <= 0} 时<b>删表</b>（不是写 0）。 */
    void gsVoSet(Player p, int n, long until) {
        try {
            if (p == null) {
                return;
            }
            String u = ListenerCore.uid(p);
            if (n <= 0) {
                GS_VO_ST.remove(u);
            } else {
                VoSt s = new VoSt();
                s.n = n;
                s.until = until;
                GS_VO_ST.put(u, s);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsVoDmg(n)} —— 当前连击对应的伤害：1 连击 = 7 点，每多一层 +1
     * ⇒ 满 7 层 = 13 点。
     *
     * <p>★ 参数是<b>本子系统自己的</b>（{@link #GS_VO_DMG_BASE} / {@link #GS_VO_DMG_PER}
     * / {@link #GS_VO_MAX}），与「此刻万籁俱寂」不同 —— 见类注释。
     */
    static double gsVoDmg(int n) {
        return ListenerCore.gsSiDmg(n, GS_VO_MAX, GS_VO_DMG_BASE, GS_VO_DMG_PER);
    }

    // ======================================================================
    // 连击「言」：BossBar
    // ======================================================================

    /**
     * {@code gsVoTitle(text)} —— ★ <b>整条灰色</b>。
     *
     * <p>★ 标题一律喂<b>纯字符串</b>（{@code §} 颜色码），绝不要把 Adventure Component
     * 塞进 {@code setTitle} —— 在月镰 / 蚀月上踩过这个坑，会被 GraalJS 隐式
     * {@code toString()} 成一长串 dump。
     */
    static String gsVoTitle(String text) {
        return GS_MS_C.get("GRAY") + "" + text;
    }

    /** {@code gsVoBarColor()} —— {@code BarColor.valueOf(GS_VO_BAR_COL)}，失败退字段。 */
    static BarColor gsVoBarColor() {
        try {
            String name = GS_VO_BAR_COL;
            try {
                BarColor c = BarColor.valueOf(name);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 落到字段访问
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsVoBarOf(p)} —— 取（或懒建）这个玩家的血条。 */
    BossBar gsVoBarOf(Player p) {
        try {
            String u = ListenerCore.uid(p);
            ListenerCore.BarRec rec = GS_VO_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsVoBarColor();
            BarStyle s = gsMsBarStyle();                  // 样式复用月镰的解析器
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            try {
                bar = Bukkit.createBossBar(gsVoTitle("言 0/" + GS_VO_MAX), c, s);
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
            ListenerCore.BarRec nr = new ListenerCore.BarRec();
            nr.b = bar;
            nr.p = p;
            GS_VO_BARS.put(u, nr);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsVoBarShow(p, n, left, total)} —— 进度 = {@code left / total}。
     *
     * <p>★★ 这里必须是<b>浮点</b>除法：JS 的 {@code left / total} 是浮点，
     * Java 的 {@code long / long} 会截断（3000 与 1500 会变成 0.5 → 0）。
     */
    void gsVoBarShow(Player p, int n, long left, long total) {
        try {
            BossBar b = gsVoBarOf(p);
            if (b == null) {
                return;
            }
            double prog = (total > 0) ? (left / (double) total) : 0;
            if (prog < 0) {
                prog = 0;
            }
            if (prog > 1) {
                prog = 1;
            }
            ListenerCore.gsBarTitleSet(b, gsVoTitle("言 " + n + "/" + GS_VO_MAX));
            try {
                b.setProgress(prog);
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

    /** {@code gsVoBarHide(p)}。 */
    void gsVoBarHide(Player p) {
        ListenerCore.gsBarTblHide(GS_VO_BARS, p);
    }

    /** {@code gsVoClear(p)} —— 连击归零 + 藏血条。 */
    void gsVoClear(Player p) {
        try {
            gsVoSet(p, 0, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsVoBarHide(p);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 连击「言」：加层 / 衰减
    // ======================================================================

    /** {@code gsVoAdd(p)} —— 命中 ≥1 个生物 ⇒ 连击 +1（封顶 {@link #GS_VO_MAX}）。 */
    void gsVoAdd(Player p) {
        try {
            long now = ListenerCore.now();
            VoSt st = gsVoGet(p);
            int n = st.n + 1;
            if (n > GS_VO_MAX) {
                n = GS_VO_MAX;
            }
            gsVoSet(p, n, now + GS_VO_WINDOW);
            gsVoBarShow(p, n, GS_VO_WINDOW, GS_VO_WINDOW);
            if (n >= GS_VO_MAX) {
                ListenerCore.gsMsSoundAt(p, "block.beacon.activate", 0.9f, 1.4f);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsVoPlayerTick(p)} —— 每 2 刻维护：血条倒计时；窗口过完 ⇒
     * ★★ <b>连击 -1（逐层衰减），不是清零</b>。
     *
     * <p>★★ 这条与「此刻万籁俱寂」是<b>可观察差异</b>（那边是 {@code gsSiClear} 直接清 0）。
     */
    void gsVoPlayerTick(Player p) {
        try {
            VoSt st = gsVoGet(p);
            if (st.n <= 0) {
                gsVoBarHide(p);
                return;
            }
            long now = ListenerCore.now();
            if (st.until <= 0) {                          // 自愈：层数在、计时丢了 ⇒ 补一份
                gsVoSet(p, st.n, now + GS_VO_WINDOW);
                gsVoBarShow(p, st.n, GS_VO_WINDOW, GS_VO_WINDOW);
                return;
            }
            long left = st.until - now;
            if (left <= 0) {
                int n = st.n - 1;                         // ★ 3 秒未命中 ⇒ 掉一层（逐层衰减）
                if (n <= 0) {
                    gsVoClear(p);
                    ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 0.6f, 1.4f);
                } else {
                    gsVoSet(p, n, now + GS_VO_WINDOW);    // 窗口重置 ⇒ 再过 3 秒才继续掉
                    gsVoBarShow(p, n, GS_VO_WINDOW, GS_VO_WINDOW);
                }
                return;
            }
            gsVoBarShow(p, st.n, left, GS_VO_WINDOW);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsVoBarsClean()}。 */
    void gsVoBarsClean() {
        ListenerCore.gsBarTblClean(GS_VO_BARS);
    }

    /**
     * {@code gsVoReleaseAll()} —— {@code /rsc reload} 后旧实例：把自己的血条收干净
     * （连击状态本来就在内存里，随实例一起消失）。
     */
    void gsVoReleaseAll() {
        try {
            for (ListenerCore.BarRec rec : new ArrayList<>(GS_VO_BARS.values())) {
                try {
                    rec.b.removeAll();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            GS_VO_BARS.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 命中判定 / 伤害器
    // ======================================================================

    /** {@code gsVoCanHit(ent, selfId)} —— 这个实体算不算「命中了一个敌人」。 */
    static boolean gsVoCanHit(Entity ent, String selfId) {
        try {
            if (ent == null) {
                return false;
            }
            String id;
            try {
                id = ListenerCore.uid(ent);
            } catch (Throwable e) {
                return false;
            }
            if (id.equals(selfId)) {
                return false;
            }
            String tn;
            try {
                tn = ListenerCore.typeName(ent);
            } catch (Throwable e) {
                return false;
            }
            if (tn.equals("PLAYER") && !GS_VO_HIT_PLAYERS) {
                return false;
            }
            // ★★ 非活物（掉落物 / 经验球 / 展示实体 / 盔甲架 …）**不算命中**
            if (!ListenerCore.gsIsCombatTarget(ent, tn)) {
                return false;
            }
            try {
                ((LivingEntity) ent).getHealth();
            } catch (Throwable e) {
                return false;
            }
            try {
                if (ent.isDead()) {
                    return false;
                }
            } catch (Throwable ignored) {
                // ★ 原脚本这里是空 catch（不 return）—— 保留
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsVoHurt(ent, dmg, p)} —— 复用月镰那套「只掉血、不击退、不留无敌帧」
     * 的伤害器（里面同时会 bump {@code GS_WPN_DMG_GUARD} ⇒ 末尾「取消原版近战」
     * 的监听器会放行这一次）。
     */
    void gsVoHurt(Entity ent, double dmg, Player p) {
        core.gsMsHurt(ent, dmg, p);
    }

    // ======================================================================
    // 左键：灰色弧光 + 挥砍
    // ======================================================================

    /** {@code gsVoSlashFx(world, loc, fx, fz, rx, rz)} —— 14 段灰色弧光。 */
    void gsVoSlashFx(World world, Location loc, double fx, double fz, double rx, double rz) {
        try {
            Particle pt = core.gsMsDustType();
            if (pt == null) {
                return;
            }
            Particle.DustOptions data = ListenerCore.gsMsDustOpts(
                    GS_VO_QI_COL[0], GS_VO_QI_COL[1], GS_VO_QI_COL[2], 1.1);
            int n = 14;
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double f = 0.6 + 2.4 * t;             // 前向 0.6 → 3.0 格
                double s = 1.50 - 3.00 * t;           // 横向 +1.5 → -1.5（正好 3 格宽）
                double h = 1.75 - 1.30 * t;           // 高度 1.75 → 0.45（斜着往下劈）
                ListenerCore.gsMsDust(world, pt, data,
                        loc.getX() + fx * f + rx * s,
                        loc.getY() + h,
                        loc.getZ() + fz * f + rz * s);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsVoSwing(p)} —— 左键挥砍。
     *
     * <p>★ 判定用的是<b>水平</b>前向 / 右向（只取 {@code dir} 的 X/Z 再归一化），
     * 上下只做 {@code |Δy| <= GS_VO_SWING_H} 的容差 —— 与「此刻万籁俱寂」的
     * 完整三维正交基<b>不同</b>，别混。
     *
     * <p>★ 伤害按<b>这一刀打出去之后</b>的层数算（所以第一刀 = 1 连击 = 7 点）。
     */
    void gsVoSwing(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_VO_SWING_AT.get(u);
            if (last != null && (now - last) < GS_VO_SWING_CD) {
                return;
            }
            GS_VO_SWING_AT.put(u, now);

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world = p.getWorld();
            org.bukkit.util.Vector dir;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                return;
            }

            double fx = dir.getX();
            double fz = dir.getZ();
            double fl = Math.sqrt(fx * fx + fz * fz);
            if (fl < 1.0e-4) {                            // 垂直看天/看地 ⇒ 退化用 +Z
                fx = 0.0;
                fz = 1.0;
                fl = 1.0;
            }
            fx /= fl;
            fz /= fl;
            double rx = -fz;
            double rz = fx;                               // 水平右向

            gsVoSlashFx(world, loc, fx, fz, rx, rz);
            ListenerCore.gsMsSound(world, loc, "entity.player.attack.sweep", 0.75f, 1.25f);

            // ★ 这一刀打出去之后连击会 +1 ⇒ 用"加过之后"的层数算伤害
            VoSt st = gsVoGet(p);
            double dmg = gsVoDmg(st.n + 1);

            String selfId = u;
            java.util.Set<String> hit = new java.util.HashSet<>();
            int count = 0;
            double halfW = GS_VO_SWING_W * 0.5;
            double px0 = loc.getX();
            double py0 = loc.getY();
            double pz0 = loc.getZ();
            Location cbox = ListenerCore.gsMsLoc(world, px0, py0 + 1.0, pz0);
            if (cbox != null) {
                List<Entity> list = null;
                try {
                    list = new ArrayList<>(world.getNearbyEntities(cbox,
                            GS_VO_SWING_R + 0.8, GS_VO_SWING_H, GS_VO_SWING_R + 0.8));
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    for (Entity ent : list) {
                        if (!gsVoCanHit(ent, selfId)) {
                            continue;
                        }
                        String eid;
                        try {
                            eid = ListenerCore.uid(ent);
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
                        double dx = el.getX() - px0;
                        double dz = el.getZ() - pz0;
                        double fwd = dx * fx + dz * fz;                 // 前向投影（长度）
                        double lat = dx * rx + dz * rz;                 // 左右投影（宽度）
                        if (fwd < -0.4 || fwd > GS_VO_SWING_R) {
                            continue;
                        }
                        if (lat < -halfW || lat > halfW) {
                            continue;
                        }
                        if (Math.abs((el.getY() + 0.9) - (py0 + 1.0)) > GS_VO_SWING_H) {
                            continue;
                        }
                        hit.add(eid);
                        gsVoHurt(ent, dmg, p);
                        count++;
                    }
                }
            }
            if (count > 0) {
                gsVoAdd(p);                              // ★ 命中 >= 1 个生物 ⇒ 连击 +1
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 右键「逝言」：蓄力
    // ======================================================================

    /**
     * {@code gsVoEnchBody(p)} —— 蓄力期间身上浮现附魔台漂浮文字
     * （绕身随机散布一圈 + 上下）。
     */
    void gsVoEnchBody(Player p) {
        try {
            Particle pt = core.gsVoEnchType();
            if (pt == null) {
                return;
            }
            Location loc = p.getLocation();
            World w = p.getWorld();
            for (int i = 0; i < 6; i++) {
                double a = Math.random() * Math.PI * 2.0;
                double r = 0.5 + Math.random() * 0.7;
                double h = 0.5 + Math.random() * 1.5;
                ListenerCore.gsMsDust(w, pt, null,
                        loc.getX() + Math.cos(a) * r,
                        loc.getY() + h,
                        loc.getZ() + Math.sin(a) * r);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsVoQi(p)} —— 右键起蓄力（12 刻 = 0.6 秒）。
     *
     * <p>★ 蓄力期间主手换掉武器 ⇒ 蓄力<b>作废、不挥出去，也不还冷却</b>（防连点刷）。
     */
    void gsVoQi(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            if (Boolean.TRUE.equals(GS_VO_CHARGING.get(u))) {
                return;                                  // 已在蓄力 ⇒ 忽略
            }
            Long last = GS_VO_FIRE_AT.get(u);
            if (last != null && (now - last) < GS_VO_QI_CD) {
                return;
            }
            GS_VO_FIRE_AT.put(u, now);
            GS_VO_CHARGING.put(u, Boolean.TRUE);

            try {
                World w0 = p.getWorld();
                Location l0 = p.getLocation();
                ListenerCore.gsMsSound(w0, l0, "block.enchantment_table.use", 0.9f, 1.2f);
                ListenerCore.gsMsSound(w0, l0, "entity.illusioner.cast_spell", 0.7f, 1.3f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            final int[] left = {GS_VO_QI_CHARGE};
            gsVoRunLater.accept(() -> gsVoChargeStep(p, u, left), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 原脚本 {@code gsVoQi} 里的局部函数 {@code charge()}。
     *
     * <p>★ 语义：共 12 拍（第 1 拍在 +1 刻、第 12 拍在 +12 刻），
     * 每拍先冒粒子再把 {@code left} 减 1；{@code left} 归零那一拍才真正放出去。
     */
    private void gsVoChargeStep(Player p, String u, int[] left) {
        try {
            boolean on = true;
            try {
                on = p.isOnline();
            } catch (Throwable e) {
                on = true;
            }
            if (!on) {
                GS_VO_CHARGING.remove(u);
                return;
            }
            if (left[0] <= 0) {
                GS_VO_CHARGING.remove(u);
                // ★ 主手已经换掉武器 ⇒ 蓄力作废，不挥出去（也不还冷却，防连点刷）
                if (gsVoIs(gsVoHand(p))) {
                    gsVoQiFire(p);
                }
                return;
            }
            gsVoEnchBody(p);
            left[0]--;
            gsVoRunLater.accept(() -> gsVoChargeStep(p, u, left), 1L);
        } catch (Throwable e) {
            GS_VO_CHARGING.remove(u);
        }
    }

    // ======================================================================
    // 右键「逝言」：灰色弧形斩击
    // ======================================================================

    /**
     * {@code gsVoArcFx(...)} —— 在「垂直于弹道」的平面上画一道新月形弧。
     *
     * <p>横向 ±{@code GS_VO_QI_WIDE/2}（= 3 格宽），竖向居中，
     * 中部再沿弹道方向前鼓一点 ⇒ 看起来是一道「弧形斩击」而不是一条直线。
     */
    static void gsVoArcFx(World world, Particle pt, Particle.DustOptions data,
                          double cx, double cy, double cz,
                          double rx, double rz, double dx, double dy, double dz) {
        try {
            if (pt == null) {
                return;
            }
            double half = GS_VO_QI_WIDE * 0.5;            // 1.5 格
            int n = 16;
            for (int i = 0; i <= n; i++) {
                double a = -Math.PI * 0.5 + Math.PI * (i / (double) n);  // -90° → +90°
                double lat = Math.sin(a) * half;                         // 横向 ±1.5（正好 3 格宽）
                double up = (Math.cos(a) - 0.5) * half;                  // 竖向 -0.75 → +0.75（居中）
                double bow = Math.cos(a) * 0.35;                         // 中部往前鼓 ⇒ 新月形
                ListenerCore.gsMsDust(world, pt, data,
                        cx + rx * lat + dx * bow,
                        cy + up + dy * bow,
                        cz + rz * lat + dz * bow);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 弹道残留点 {@code { x, y, z, until }}（原脚本 8271 行的对象字面量）。 */
    static final class Trail {
        double x;
        double y;
        double z;
        long until;
    }

    /** {@code gsVoTrailFx(world, ench, trail)} —— 把记下来的点重新撒一遍附魔台文字。 */
    static void gsVoTrailFx(World world, Particle ench, List<Trail> trail) {
        try {
            if (ench == null || trail == null) {
                return;
            }
            long now = ListenerCore.now();
            for (int i = 0; i < trail.size(); i++) {
                Trail t = trail.get(i);
                if (t.until <= now) {
                    continue;
                }
                for (int k = 0; k < 2; k++) {
                    ListenerCore.gsMsDust(world, ench, null,
                            t.x + (Math.random() - 0.5) * 0.5,
                            t.y + (Math.random() - 0.5) * 0.5,
                            t.z + (Math.random() - 0.5) * 0.5);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 「逝言」一次飞行的全部可变状态 —— 等价于原脚本 {@code gsVoQiFire} 里
     * 被 {@code step()} / {@code tail()} / {@code finish()} 三个局部函数
     * 闭包捕获的那些变量。
     */
    static final class QiShot {
        World world;
        Player p;
        double dx;
        double dy;
        double dz;
        double rx;
        double rz;
        double cx;
        double cy;
        double cz;
        double travelled;
        int pierced;
        final java.util.Set<String> hitIds = new java.util.HashSet<>();
        List<Trail> trail = new ArrayList<>();
        boolean tailOn;
        Particle pt;
        Particle.DustOptions data;
        Particle ench;
    }

    /**
     * {@code gsVoQiFire(p)} —— 蓄力结束的<b>这一刻</b>才锁方向（读条是给玩家的反应窗口）。
     *
     * <p>★ 结束条件四选一：穿透满 4 个 / 到 7 格尽头 / 撞墙 / 玩家下线。
     * ★ 撞墙判定用 {@code blk.getType().isSolid()}（{@code 0.5} 格一步 ⇒ 不会跨过整块墙）。
     */
    QiShot gsVoQiFire(Player p) {
        try {
            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return null;
            }
            World world = p.getWorld();
            org.bukkit.util.Vector dir;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                return null;
            }

            double dx = dir.getX();
            double dy = dir.getY();
            double dz = dir.getZ();
            double dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dl < 1.0e-4) {
                dx = 0.0;
                dy = 0.0;
                dz = 1.0;
                dl = 1.0;
            }
            dx /= dl;
            dy /= dl;
            dz /= dl;

            // 水平右向（铺开 3 格宽用）；正上/正下看时水平分量退化 ⇒ 退回 +X
            double hx = dx;
            double hz = dz;
            double hl = Math.sqrt(hx * hx + hz * hz);
            if (hl < 1.0e-4) {
                hx = 1.0;
                hz = 0.0;
                hl = 1.0;
            }
            hx /= hl;
            hz /= hl;
            double rx = -hz;
            double rz = hx;

            QiShot s = new QiShot();
            s.world = world;
            s.p = p;
            s.dx = dx;
            s.dy = dy;
            s.dz = dz;
            s.rx = rx;
            s.rz = rz;
            s.cx = loc.getX() + dx * 0.6;
            s.cy = loc.getY() + 1.1 + dy * 0.6;
            s.cz = loc.getZ() + dz * 0.6;
            s.pt = core.gsMsDustType();
            s.data = ListenerCore.gsMsDustOpts(
                    GS_VO_QI_COL[0], GS_VO_QI_COL[1], GS_VO_QI_COL[2], 1.2);
            s.ench = core.gsVoEnchType();

            ListenerCore.gsMsSound(world, loc, "entity.player.attack.sweep", 1.0f, 0.8f);
            ListenerCore.gsMsSound(world, loc, "entity.evoker.cast_spell", 0.8f, 0.7f);

            gsVoRunLater.accept(() -> gsVoQiStep(s), 1L);
            return s;                       // ★ 返回状态对象：只为让探针能逐拍驱动（见 gsVoRunLater）
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("逝言出错: " + e);
            return null;
        }
    }

    /** 原脚本 {@code gsVoQiFire} 里的局部函数 {@code finish()}。 */
    void gsVoQiFinish(QiShot s) {
        try {
            if (s.tailOn) {
                return;
            }
            s.tailOn = true;
            gsVoTrailFx(s.world, s.ench, s.trail);
            gsVoRunLater.accept(() -> gsVoQiTail(s), 2L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 原脚本 {@code gsVoQiFire} 里的局部函数 {@code tail()}。 */
    void gsVoQiTail(QiShot s) {
        try {
            gsVoTrailFx(s.world, s.ench, s.trail);
            long now = ListenerCore.now();
            List<Trail> keep = new ArrayList<>();
            for (int i = 0; i < s.trail.size(); i++) {
                if (s.trail.get(i).until > now) {
                    keep.add(s.trail.get(i));
                }
            }
            s.trail = keep;
            if (!keep.isEmpty()) {
                gsVoRunLater.accept(() -> gsVoQiTail(s), 2L);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 原脚本 {@code gsVoQiFire} 里的局部函数 {@code step()} —— 每刻一拍。 */
    void gsVoQiStep(QiShot s) {
        try {
            Player p = s.p;
            if (p == null) {
                return;
            }
            boolean on = true;
            try {
                on = p.isOnline();
            } catch (Throwable e) {
                on = true;
            }
            if (!on) {
                return;
            }

            s.travelled += GS_VO_QI_STEP;

            // ① 灰色弧形斩击
            gsVoArcFx(s.world, s.pt, s.data, s.cx, s.cy, s.cz, s.rx, s.rz, s.dx, s.dy, s.dz);

            // ② 命中判定：落在"这一拍的弧面"上的生物
            Location box = ListenerCore.gsMsLoc(s.world, s.cx, s.cy, s.cz);
            if (box != null) {
                List<Entity> list = null;
                try {
                    list = new ArrayList<>(s.world.getNearbyEntities(box,
                            GS_VO_QI_WIDE * 0.5 + 0.6, GS_VO_QI_H, GS_VO_QI_WIDE * 0.5 + 0.6));
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    int before = s.pierced;
                    for (Entity ent : list) {
                        if (!gsVoCanHit(ent, ListenerCore.uid(p))) {
                            continue;
                        }
                        String eid;
                        try {
                            eid = ListenerCore.uid(ent);
                        } catch (Throwable e) {
                            continue;
                        }
                        if (s.hitIds.contains(eid)) {
                            continue;                          // ★ 同一目标只吃一次
                        }
                        Location el;
                        try {
                            el = ent.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        double ex = el.getX() - s.cx;
                        double ey = (el.getY() + 0.9) - s.cy;
                        double ez = el.getZ() - s.cz;
                        double along = ex * s.dx + ey * s.dy + ez * s.dz;   // 沿弹道方向的分量
                        if (Math.abs(along) > 1.0) {
                            continue;                          // 必须贴在这一拍的弧面上
                        }
                        double lx = ex - along * s.dx;
                        double ly = ey - along * s.dy;
                        double lz = ez - along * s.dz;
                        if (Math.sqrt(lx * lx + ly * ly + lz * lz) > GS_VO_QI_WIDE * 0.5) {
                            continue;
                        }
                        s.hitIds.add(eid);
                        gsVoHurt(ent, GS_VO_QI_DMG, p);
                        s.pierced++;
                    }
                    // ★ 与左键挥砍同一条规则：**这一次攻击命中了 ≥1 个生物 ⇒ 连击 +1**
                    //   （不是"每命中一只 +1" —— 否则一发穿 4 个就白送 4 层）
                    if (s.pierced > before && GS_VO_QI_STACK) {
                        gsVoAdd(p);
                    }
                }
            }

            // ③ 记残留点 + 当拍补画
            Trail t = new Trail();
            t.x = s.cx;
            t.y = s.cy;
            t.z = s.cz;
            t.until = ListenerCore.now() + GS_VO_QI_TRAIL * 50L;
            s.trail.add(t);
            gsVoTrailFx(s.world, s.ench, s.trail);

            // ④ 结束条件：穿透满 4 个 / 到 7 格尽头 / 撞墙
            if (s.pierced >= GS_VO_QI_PIERCE) {
                gsVoQiFinish(s);
                return;
            }
            if (s.travelled >= GS_VO_QI_RANGE) {
                gsVoQiFinish(s);
                return;
            }

            double nx = s.cx + s.dx * GS_VO_QI_STEP;
            double ny = s.cy + s.dy * GS_VO_QI_STEP;
            double nz = s.cz + s.dz * GS_VO_QI_STEP;
            Location nb = ListenerCore.gsMsLoc(s.world, nx, ny, nz);
            boolean solid = false;
            if (nb != null) {
                try {
                    Block blk = nb.getBlock();
                    if (blk != null && blk.getType() != null && blk.getType().isSolid()) {
                        solid = true;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            if (solid) {
                gsVoQiFinish(s);                                 // ★ 撞墙即止（不穿墙）
                return;
            }
            s.cx = nx;
            s.cy = ny;
            s.cz = nz;

            gsVoRunLater.accept(() -> gsVoQiStep(s), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 去抖
    // ======================================================================

    /** {@code gsVoClickOnce(p, tag)} —— 同一次按键会连发两个事件，80ms 去抖。 */
    boolean gsVoClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_VO_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_VO_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsVoRecentRight(p, gapMs)} —— 最近是不是刚右键过（挥手兜底要用）。 */
    boolean gsVoRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_VO_CLICK_AT.get(ListenerCore.uid(p) + "|R");
            if (last == null) {
                return false;
            }
            return (ListenerCore.now() - last) < gapMs;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 心跳 / 世代号
    // ======================================================================

    /**
     * {@code gsVoGenNow()} —— 当前世界 PDC 上的世代号；读不到返回 <b>null</b>
     * （★ 注意不是 -1，与月镰那套不同）。
     */
    Integer gsVoGenNow() {
        try {
            World w = Bukkit.getServer().getWorlds().get(0);
            Integer v = w.getPersistentDataContainer().get(GS_VO_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? Integer.valueOf(0) : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsVoGenBump()} —— 世代号 +1 并写回 PDC。 */
    void gsVoGenBump() {
        try {
            Integer cur = gsVoGenNow();
            if (cur == null) {
                cur = Integer.valueOf(0);                 // 读不到就从 0 起算
            }
            GS_VO_GEN = cur + 1;
            World w = Bukkit.getServer().getWorlds().get(0);
            w.getPersistentDataContainer().set(GS_VO_K_GEN, PersistentDataType.INTEGER, GS_VO_GEN);
        } catch (Throwable e) {
            if (GS_VO_GEN <= 0) {
                GS_VO_GEN = 1;
            }
        }
    }

    /**
     * {@code gsVoPulse()} —— 每 2 刻跑一次。
     *
     * <p>★ 自带心跳（<b>不依赖月镰的 {@code gsMsPulse}</b>）：一旦月镰的监听器注册失败，
     * 它的 {@code gsMsStart} 会直接 return（<b>不</b>开心跳）⇒ 挂在它上面的模块会一起停摆。
     * 本模块用自己的 {@code runRepeating} + 自己的世界世代号，互不影响。
     */
    void gsVoPulse() {
        boolean mine = true;
        try {
            Integer g = gsVoGenNow();
            if (g != null && GS_VO_GEN != -1) {
                mine = (g == GS_VO_GEN);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (!mine) {
            gsVoReleaseAll();
            return;
        }
        try {
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                try {
                    gsVoPlayerTick(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默（单个玩家出错不影响其他人）
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsVoBarsClean();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 注册同步监听器（★ Java 版：幂等注册一次）
    // ======================================================================

    /**
     * {@code gsVoRegisterSync()} —— 注册 4 个 {@code HIGHEST} 监听器。
     *
     * <p>★ 返回 {@code true} 表示「注册成功或已经注册过」。
     * 原脚本的 {@code false} 分支（拿不到 Plugin）在 Java 里恒不执行（见类注释死代码）。
     */
    boolean gsVoRegisterSync() {
        if (GS_VO_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new Sync(), GS.plugin());
            GS_VO_REGISTERED = true;
            ListenerCore.gsDaLog("致万千无言者监听器注册成功（左键挥砍叠连击 / 右键蓄力 0.6 秒放“逝言”）");
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("致万千无言者监听器注册异常: " + e);
            return false;
        }
    }

    /** 本子系统的 4 个监听器（★ 全部 {@code HIGHEST}，{@code ignoreCancelled} 默认 false）。 */
    final class Sync implements Listener {

        /**
         * ① 左键 = 挥砍（同时挖不动方块）；右键 = 「逝言」蓄力。
         *
         * <p>★ 故意<b>不</b>检查 {@code e.isCancelled()}：有些前置插件会先取消
         * {@code *_CLICK_AIR}，一检查就变成「必须点方块才放得出来」。重复注册交给去抖。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsVoIs(gsVoHand(p))) {
                    return;
                }
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (hs.length() > 0 && hs.contains("OFF")) {
                    return;                                   // 只认主手
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }

                if (act.contains("RIGHT_CLICK")) {
                    // ★ 取消右键交互（石镐不许挖矿 / 不许开箱子 / 不许放方块）
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
                    if (!gsVoClickOnce(p, "R")) {
                        return;
                    }
                    gsVoQi(p);
                } else if (act.contains("LEFT_CLICK")) {
                    if (!gsVoClickOnce(p, "L")) {
                        return;
                    }
                    if (GS_VO_NO_BREAK) {
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
                    gsVoSwing(p);
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
                if (!gsVoIs(gsVoHand(p))) {
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
                if (gsVoRecentRight(p, 300)) {
                    return;                                   // 右键附带的挥手 → 忽略
                }
                if (!gsVoClickOnce(p, "L")) {
                    return;
                }
                gsVoSwing(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ 手持时挖不动方块（双保险：即使 ① 的取消被别的插件放行也挖不掉）。
         *
         * <p>★ 原脚本是「{@code if (GS_VO_NO_BREAK)} 才注册」；这里因为
         * {@link #GS_VO_NO_BREAK} 是编译期常量 {@code true}，注册进来 + 内部判断
         * 与「条件注册」等价（常量恒真 ⇒ 恒注册、恒取消）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!GS_VO_NO_BREAK) {
                    return;
                }
                if (!gsVoIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 同上（{@code BlockDamageEvent}）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockDamage(BlockDamageEvent e) {
            try {
                if (!GS_VO_NO_BREAK) {
                    return;
                }
                if (!gsVoIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 启动（原脚本 8575~8597 行）
    // ======================================================================

    /**
     * {@code gsVoStart()} —— bump 自己的世代号 + 注册监听器 + 排心跳 + 打加载日志。
     *
     * <p>★★ 心跳是 {@code runRepeating(gsVoPulse, 20, 2)} ——
     * <b>delay = 20 刻、period = 2 刻，两个参数不同</b>，别抄成一个。
     */
    public void gsVoStart() {
        try {
            // ★ bump 自己的世代号：/rsc reload 后旧实例读到新号 ⇒ 自己收手
            gsVoGenBump();

            if (!gsVoRegisterSync()) {
                ListenerCore.gsDaWarn("致万千无言者启动失败：监听器没注册上（连击血条仍会跑）");
            }
            GS.runRepeating(this::gsVoPulse, 20L, 2L);   // 每 2 刻（0.1 秒）维护连击血条 / 清理
            try {
                boolean enchOk = (core.gsVoEnchType() != null);
                ListenerCore.gsDaLog(gsVoStartLine(GS_VO_GEN, enchOk));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("致万千无言者启动异常: " + e);
        }
    }

    /**
     * 原脚本 8586~8592 行的加载日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 抽成静态方法<b>只为让探针逐字节对账</b>，不改任何行为。
     * 对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 35 行）：
     * <pre>
     * [构思技艺] 致万千无言者（GS_VOICELESS）已加载 · 挥砍 7~13 点（随连击 +1/层）/ 满 7 连击 /
     * 窗口 3 秒 · 逝言 蓄力 0.6 秒 / 11 点 / 射程 7 格 / 宽 3 格 / 穿透 4 个 / 冷却 1.2 秒 ·
     * 附魔文字粒子 可用 · 世界世代号 75
     * </pre>
     *
     * <p>★ 数字全部走 {@link #jsNum(double)} —— 与真 JS 的 {@code String(数字)}
     * 逐字节一致（见 {@code tools/ListenerESubParityProbe.java}）。
     *
     * @param gen     {@code GS_VO_GEN}（运行期动态值，探针里单独处理）
     * @param enchOk  附魔粒子探测结果
     */
    static String gsVoStartLine(int gen, boolean enchOk) {
        return "致万千无言者（" + GS_VO_ID + "）已加载 · 挥砍 "
                + jsNum(GS_VO_DMG_BASE) + "~" + jsNum(gsVoDmg(GS_VO_MAX)) + " 点（随连击 +" + jsNum(GS_VO_DMG_PER)
                + "/层）/ 满 " + GS_VO_MAX + " 连击 / 窗口 " + jsNum(GS_VO_WINDOW / 1000.0)
                + " 秒 · 逝言 蓄力 " + jsNum(GS_VO_QI_CHARGE / 20.0) + " 秒 / " + jsNum(GS_VO_QI_DMG)
                + " 点 / 射程 " + jsNum(GS_VO_QI_RANGE) + " 格 / 宽 " + jsNum(GS_VO_QI_WIDE)
                + " 格 / 穿透 " + GS_VO_QI_PIERCE + " 个 / 冷却 " + jsNum(GS_VO_QI_CD / 1000.0)
                + " 秒 · 附魔文字粒子 " + (enchOk ? "可用" : "不可用") + " · 世界世代号 " + gen;
    }

    /** 原脚本 8524 行的注册日志（不含前缀），供探针对账。 */
    static String gsVoRegisterLine() {
        return "致万千无言者监听器注册成功（左键挥砍叠连击 / 右键蓄力 0.6 秒放“逝言”）";
    }
}
