package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「此刻万籁俱寂」子系统
 * （原脚本第 <b>8601~9279</b> 行，顶层入口 {@code gsSiStart()}，第 9279 行）。
 *
 * <p><b>玩法</b>：构思武器第 6 格（金镐材质，{@code GS_SILENCE}）。
 * <ul>
 *   <li><b>左键 = 斩击</b>：整个「宽 3 × 长 5 × 高 2」的<b>长方体</b>（不是一条线），
 *       而且★★ 这个长方体<b>跟着准星一起转</b> —— 三个投影轴用的是
 *       {@code forward = 视线本身（含 Y）} / {@code right = 水平右向} /
 *       {@code up = right × forward}。判定原点 = 玩家<b>胸口</b>（脚 + 1.0），
 *       怪的取样点 = 「脚下 + 0.9」。
 *       ★ 水平看时 {@code fy = 0}、{@code up = (0,1,0)} ⇒ 三个投影退化成
 *       「水平前向 / 水平右向 / 世界 Y 差」，手感与改造前完全一致。</li>
 *   <li><b>右键 = 瞬发「寂灭」</b>：以<b>玩家自己</b>为圆心、半径 7 格的圆圈 AoE，
 *       圈内敌人直接吃 {@code gsSiDmg(当前层数) × 2} 点；判定形状 =
 *       <b>水平半径 ≤ 7 且竖直差 ≤ 7</b>（一个扁圆柱，免得隔着几层楼把头顶/脚下的怪
 *       也一起打了）。冷却 <b>15 秒</b>。★ 右键<b>不叠层</b>（层数只靠左键叠）。</li>
 *   <li><b>连击「寂」窗口 7 秒</b>：★★ <b>未命中直接清 0</b> ——
 *       这是与「致万千无言者」的关键可观察差异（那边是逐层 -1 衰减）。</li>
 *   <li>血条标题 {@code 寂 N}（★ <b>不用 {@code N/99}</b>）；
 *       「寂」亮红 {@code §c}、数字暗红 {@code §4}。</li>
 * </ul>
 *
 * <p>★ 旧的「按住蓄力 · 噤声 / 松手追踪 · 噤言」那一整套<b>已整体删除</b>
 * （它靠「客户端重复发右键包」来判松手，本身是个绕路方案；改成瞬发 AoE 之后
 * 连「松手判定」都不需要了）。所以本类里<b>没有</b> {@code gsSiNearest} /
 * {@code gsSiSilence} / {@code gsSiChase} 之类的东西，这是<b>有意的</b>。
 *
 * <h2>★ 与原脚本的有意差异（修掉一个真实缺陷）</h2>
 * <b>原脚本 reload 会累积监听器，Java 版改为幂等注册。</b>
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} 在脚本里自己注册 4 个监听器，
 * 而 {@code /rsc reload} 会重新 eval 整个脚本 ⇒ 监听器每次 reload 都再注册一遍、
 * 不断累积（{@code GS_SI_REGISTERED} 闩锁是脚本级变量，reload 后归零，挡不住累积）。
 * Java 版把监听器写成 {@link Listener} 实现类、{@code registerEvents} 只注册一次：
 * 事件照常收到、优先级照常、取消照常，可观察行为相同，但 reload 不再累积。
 * ★ 这不是「顺手优化」，是修一个真实缺陷。
 *
 * <p>★ 优先级与 {@code ignoreCancelled} 逐处核对过：原脚本 4 处
 * {@code pm.registerEvent(类, plugin, Priority.HIGHEST, executor, plugin)} <b>都没有第 5 个参数</b>
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 的 {@code @EventHandler} 默认也是 false
 * ⇒ 不加该属性即等价（<b>绝不写成 true</b>）。
 *
 * <h2>死代码 / 走不到的分支（不照抄，在此存档）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 9086~9093 行）：GraalJS 25.1.3 里
 *       {@code JavaAdapter} 是 {@code undefined}（{@code tools/GraalProbe.java} 实测）
 *       ⇒ 这条退路<b>永远走不到</b>。Java 版直接用原生 {@link Listener}。</li>
 *   <li><b>{@code plugin} 四级回退</b>（原脚本 9072~9077 行）：Java 版自己有 Plugin 实例
 *       （{@link GS#plugin()}）⇒ 整条回退链作废；{@code if (plugin == null) return false;}
 *       那条<b>恒不执行</b>。</li>
 *   <li><b>{@code GS_SI_PDT}</b>（原脚本 8654 行的 {@code Java.type} 探测结果）：
 *       Java 里该类必然可用 ⇒ 只保留 {@code GS_SI_K_GEN != null} 这一半判断
 *       （{@code NamespacedKey.fromString} 恒非 null，那一半也恒真）。</li>
 *   <li><b>{@code gsSiBurstDraw} 里的 {@code gsVoEnchType()}</b>：原脚本借用了
 *       「致万千无言者」的粒子解析器 ⇒ Java 侧同样复用
 *       {@link ListenerCore#gsVoEnchType()}（不是复制一份）。</li>
 *   <li><b>{@code gsSiRegisterSync()} 的失败分支</b>（原脚本 9258 行的
 *       「此刻万籁俱寂启动失败：监听器没注册上」）：只有 plugin 拿不到才会走到，
 *       Java 里恒不执行。★ 但<b>日志文案照抄保留</b>（万一将来注册真失败必须打同一句）。</li>
 * </ol>
 *
 * <h2>★ {@code GS_SI_REGISTERED} 闩锁</h2>
 * 原脚本里它<b>只写不读</b>（写：8644 行定义 + 9201 行置 true；读：只在 9067 行的
 * {@code if (GS_SI_REGISTERED) return true;} 早退里）。Java 里保留成<b>实例字段</b>：
 * 因为 {@code ListenerModule} 只有一个实例，它天然等价于「幂等注册一次」。
 */
public final class SiSub {

    // ======================================================================
    // 参数（名字必须与 JS 顶层 var 同名 —— 常量对账探针按名字反射比对）
    // ======================================================================

    static final String GS_SI_ID = "GS_SILENCE";
    /** {@code true = 手持时挖不动方块（金镐不许挖矿）}。 */
    static final boolean GS_SI_NO_BREAK = true;
    /** {@code false = 斩击 / 噤言不打其它玩家（只打生物），防误伤}。 */
    static final boolean GS_SI_HIT_PLAYERS = false;

    // ---- 被动：连击「寂」 ----
    /** 最高连击数。 */
    static final int GS_SI_MAX = 99;
    /** 命中窗口（毫秒）= <b>7 秒</b>（★ 第四十批：5 秒 → 7 秒）。 */
    static final long GS_SI_WINDOW = 7000L;
    /** 初始伤害（1 连击时）。 */
    static final double GS_SI_DMG_BASE = 17.0;
    /** 每多一层的加成 ⇒ 满 99 层 = 17 + 2×98 = 213。 */
    static final double GS_SI_DMG_PER = 2.0;
    /** 血条颜色（红，与「寂」的亮红呼应）。 */
    static final String GS_SI_BAR_COL = "RED";

    // ---- 左键：斩击（宽 3 × 长 5 × 高 2） ----
    /** 宽（左右各 1.5 格）。 */
    static final double GS_SI_WIDE = 3.0;
    /** 长（前方 5 格）。 */
    static final double GS_SI_RANGE = 5.0;
    /** 高（2 格）。 */
    static final double GS_SI_H = 2.0;
    /** 挥砍冷却（毫秒）。 */
    static final long GS_SI_SWING_CD = 320L;

    // ---- 右键「寂灭」（以玩家为圆心的 7 格圆圈 AoE） ----
    /** 圆圈半径（格）。 */
    static final double GS_SI_BURST_R = 7.0;
    /** 伤害倍率（基础伤害 ×2）。 */
    static final double GS_SI_BURST_MUL = 2.0;
    /** 冷却 15 秒（毫秒）。 */
    static final long GS_SI_BURST_CD = 15000L;
    /** 圆圈特效停留 0.4 秒（由心跳重画）。 */
    static final long GS_SI_BURST_FX_MS = 400L;
    /** 圆周采样点数。 */
    static final int GS_SI_BURST_FX_N = 96;

    /** ★ 本模块<b>自己的</b>世界世代号键 {@code gs:si_gen}（不复用月镰 / 致万千无言者的）。 */
    static final NamespacedKey GS_SI_K_GEN = NamespacedKey.fromString("gs:si_gen");

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

    /** {@code GS_SI_REGISTERED} —— 幂等闩锁（见类注释）。 */
    boolean GS_SI_REGISTERED = false;
    /** {@code GS_SI_CLICK_AT} —— {@code uuid|L} → 上次处理毫秒（去抖）。 */
    final Map<String, Long> GS_SI_CLICK_AT = new HashMap<>();
    /** {@code GS_SI_SWING_AT} —— uuid → 上次挥砍毫秒（冷却）。 */
    final Map<String, Long> GS_SI_SWING_AT = new HashMap<>();
    /** {@code GS_SI_SIDE} —— uuid → 0 / 1（斜劈左起 / 右起交替）。 */
    final Map<String, Integer> GS_SI_SIDE = new HashMap<>();
    /** {@code GS_SI_ST} —— uuid → {@code { n, until }}（内存权威）。 */
    final Map<String, SiSt> GS_SI_ST = new HashMap<>();
    /** {@code GS_SI_BARS} —— uuid → {@code { b, p }}（★ 本模块专属表）。 */
    final Map<String, ListenerCore.BarRec> GS_SI_BARS = ListenerCore.newBarTable();
    /** {@code GS_SI_BURST_AT} —— ★★ 第五十三批：uuid → 上次「寂灭」毫秒（15 秒冷却）。 */
    final Map<String, Long> GS_SI_BURST_AT = new HashMap<>();
    /** {@code GS_SI_BURST_FX} —— ★★ 第五十三批：正在淡出的圆圈特效。 */
    List<BurstFx> GS_SI_BURST_FX = new ArrayList<>();
    /** {@code GS_SI_GEN} —— 加载那一刻的世界世代号（判 {@code /rsc reload} 旧实例）。 */
    int GS_SI_GEN = -1;

    private final ListenerCore core;

    public SiSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsSiStart();} 调用（原脚本第 9279 行）。 */
    public void start() {
        gsSiStart();
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    /** {@code gsSiIs(item)}。 */
    boolean gsSiIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_SI_ID);
    }

    /** {@code gsSiHand(p)} —— 主手物品（拿不到返回 null）。 */
    static ItemStack gsSiHand(Player p) {
        try {
            return p.getInventory().getItemInMainHand();
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsMsBarStyle()} —— 原脚本 6252~6260 行。★ {@link ListenerCore} 里没有它。 */
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
     * JS 的 {@code String(数字)} —— 与 {@link VoSub#jsNum(double)} 逐字相同
     * （★ 本地副本；集成者应统一提到 {@code ListenerCore}，见交付说明第 4 条）。
     */
    static String jsNum(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0.0) {
            return "0";
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
        int n = exp10 + intLen - lead;
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
    // 连击「寂」：读写（内存权威，不写 PDC）
    // ======================================================================

    /** {@code GS_SI_ST[uuid]} 的记录（原脚本 8648 行的对象字面量 {@code { n, until }}）。 */
    static final class SiSt {
        int n;
        long until;
    }

    /** {@code gsSiGet(p)}。 */
    SiSt gsSiGet(Player p) {
        SiSt o = new SiSt();
        try {
            if (p == null) {
                return o;
            }
            SiSt m = GS_SI_ST.get(ListenerCore.uid(p));
            if (m != null) {
                o.n = m.n;
                o.until = m.until;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return o;
    }

    /** {@code gsSiSet(p, n, until)} —— {@code n <= 0} 时<b>删表</b>（不是写 0）。 */
    void gsSiSet(Player p, int n, long until) {
        try {
            if (p == null) {
                return;
            }
            String u = ListenerCore.uid(p);
            if (n <= 0) {
                GS_SI_ST.remove(u);
            } else {
                SiSt s = new SiSt();
                s.n = n;
                s.until = until;
                GS_SI_ST.put(u, s);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsSiDmg(n)} —— 当前连击对应的伤害：1 连击 = 17 点，每多一层 +2
     * ⇒ 满 99 层 = 213 点。
     *
     * <p>★ 本子系统用的参数<b>就是</b> {@link ListenerCore#gsSiDmg} 的默认参数
     * （BASE 17.0 / PER 2.0 / MAX 99），所以直接委托 ——
     * 而「致万千无言者」参数不同（7 / 1 / 7），它自己传参数。
     */
    static double gsSiDmg(int n) {
        return ListenerCore.gsSiDmg(n, GS_SI_MAX, GS_SI_DMG_BASE, GS_SI_DMG_PER);
    }

    // ======================================================================
    // 连击「寂」：BossBar
    // ======================================================================

    /**
     * {@code gsSiTitle(n)} —— ★ <b>不用 {@code N/99}</b>，直接 {@code 寂 N}；
     * 「寂」亮红 {@code §c}、数字暗红 {@code §4}。
     */
    static String gsSiTitle(int n) {
        return GS_MS_C.get("RED") + "寂 " + GS_MS_C.get("DARK_RED") + n;
    }

    /** {@code gsSiBarColor()}。 */
    static BarColor gsSiBarColor() {
        try {
            String name = GS_SI_BAR_COL;
            try {
                BarColor c = BarColor.valueOf(name);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 落到字段访问（enum 上与 valueOf 等价，见 VoSub 同处注释）
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsSiBarOf(p)} —— 取（或懒建）这个玩家的血条。 */
    BossBar gsSiBarOf(Player p) {
        try {
            String u = ListenerCore.uid(p);
            ListenerCore.BarRec rec = GS_SI_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsSiBarColor();
            BarStyle s = gsMsBarStyle();
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            try {
                bar = Bukkit.createBossBar(gsSiTitle(0), c, s);
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
            GS_SI_BARS.put(u, nr);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsSiBarShow(p, n, left, total)} —— ★ 进度必须是<b>浮点</b>除法。 */
    void gsSiBarShow(Player p, int n, long left, long total) {
        try {
            BossBar b = gsSiBarOf(p);
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
            ListenerCore.gsBarTitleSet(b, gsSiTitle(n));
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

    /** {@code gsSiBarHide(p)}。 */
    void gsSiBarHide(Player p) {
        ListenerCore.gsBarTblHide(GS_SI_BARS, p);
    }

    /** {@code gsSiClear(p)} —— 连击归零 + 藏血条。 */
    void gsSiClear(Player p) {
        try {
            gsSiSet(p, 0, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsSiBarHide(p);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 连击「寂」：加层 / 到点清零
    // ======================================================================

    /** {@code gsSiAdd(p)} —— 命中 ≥1 个生物 ⇒ 连击 +1（封顶 {@link #GS_SI_MAX}）。 */
    void gsSiAdd(Player p) {
        try {
            long now = ListenerCore.now();
            SiSt st = gsSiGet(p);
            int n = st.n + 1;
            if (n > GS_SI_MAX) {
                n = GS_SI_MAX;
            }
            gsSiSet(p, n, now + GS_SI_WINDOW);
            gsSiBarShow(p, n, GS_SI_WINDOW, GS_SI_WINDOW);
            if (n >= GS_SI_MAX) {
                ListenerCore.gsMsSoundAt(p, "block.beacon.activate", 0.9f, 1.2f);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsSiPlayerTick(p)} —— 每 2 刻维护：血条倒计时；窗口过完 ⇒
     * ★★ <b>连击直接清 0</b>（用户指定，不是逐层衰减）。
     *
     * <p>★★ 这条与「致万千无言者」是<b>可观察差异</b>（那边是 {@code n - 1} 逐层掉）。
     */
    void gsSiPlayerTick(Player p) {
        try {
            SiSt st = gsSiGet(p);
            if (st.n <= 0) {
                gsSiBarHide(p);
                return;
            }
            long now = ListenerCore.now();
            if (st.until <= 0) {                          // 自愈：层数在、计时丢了 ⇒ 补一份
                gsSiSet(p, st.n, now + GS_SI_WINDOW);
                gsSiBarShow(p, st.n, GS_SI_WINDOW, GS_SI_WINDOW);
                return;
            }
            long left = st.until - now;
            if (left <= 0) {
                gsSiClear(p);                             // ★ 7 秒未命中 ⇒ 连击**直接清 0**
                ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 0.6f, 1.2f);
                return;
            }
            gsSiBarShow(p, st.n, left, GS_SI_WINDOW);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsSiBarsClean()}。 */
    void gsSiBarsClean() {
        ListenerCore.gsBarTblClean(GS_SI_BARS);
    }

    /**
     * {@code gsSiReleaseAll()} —— {@code /rsc reload} 后旧实例：收血条 + 清「寂灭」冷却表
     * + 丢掉正在淡出的圆圈特效。
     */
    void gsSiReleaseAll() {
        try {
            for (ListenerCore.BarRec rec : new ArrayList<>(GS_SI_BARS.values())) {
                try {
                    rec.b.removeAll();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            GS_SI_BARS.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            GS_SI_BURST_AT.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            GS_SI_BURST_FX = new ArrayList<>();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 目标筛选 / 伤害器
    // ======================================================================

    /** {@code gsSiCanHit(ent, selfId)}。 */
    static boolean gsSiCanHit(Entity ent, String selfId) {
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
            if (tn.equals("PLAYER") && !GS_SI_HIT_PLAYERS) {
                return false;
            }
            // ★★ 非活物（掉落物 / 经验球 / 展示实体 / 盔甲架 …）**不算命中**
            //   —— 否则对着地上一堆掉落物挥几下就能把「寂」无痛叠满
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

    /** {@code gsSiHurt(ent, dmg, p)}。 */
    void gsSiHurt(Entity ent, double dmg, Player p) {
        core.gsMsHurt(ent, dmg, p);
    }

    // ======================================================================
    // 左键：斩击（附魔台文字 · 斜劈弧线，两刀一左一右交替）
    // ======================================================================

    /**
     * {@code gsSiSwing(p)} —— 左键斩击。
     *
     * <p>★★ <b>跟着准星</b>（3D 视线），不再只跟水平朝向。
     * 用户原话：「此刻万籁俱寂只会对朝向攻击，不会对玩家准星，改成对玩家准星攻击」。
     * 原来只取 {@code dir} 的 X/Z 再归一化（把 {@code dir.getY()} 直接丢掉）
     * ⇒ 抬头 / 低头砍出去的永远是<b>水平</b>那一条 ⇒ 「瞄着头顶 / 脚边的怪砍」砍不到。
     * 现在整条 3D 视线都算数。
     *
     * <p>★ 正交基（三个轴两两垂直，箱体跟着视线一起倾斜）：
     * <pre>
     *   forward = 视线本身（单位向量，含 Y）
     *   right   = forward × 世界上方 = (-fz, 0, fx) 归一化 ⇒ **永远水平**
     *   up      = right × forward                     ⇒ 与视线垂直的"上"
     * </pre>
     */
    void gsSiSwing(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_SI_SWING_AT.get(u);
            if (last != null && (now - last) < GS_SI_SWING_CD) {
                return;
            }
            GS_SI_SWING_AT.put(u, now);

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

            double fx = dir.getX();
            double fy = dir.getY();
            double fz = dir.getZ();
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

            double rx = -fz;
            double ry = 0.0;
            double rz = fx;                               // 水平右向（未归一化）
            double rl = Math.sqrt(rx * rx + rz * rz);
            if (rl < 1.0e-4) {
                rx = 1.0;
                ry = 0.0;
                rz = 0.0;                                 // 正上/正下看 ⇒ 退化用 +X
            } else {
                rx /= rl;
                rz /= rl;
            }

            double ux = ry * fz - rz * fy;                // up = right × forward
            double uy = rz * fx - rx * fz;
            double uz = rx * fy - ry * fx;

            // 斜劈交替：side 0 = 第一刀（左1 → 左3），side 1 = 第二刀（左3 → 左1）
            Integer sideBox = GS_SI_SIDE.get(u);
            int side = (sideBox == null) ? 0 : sideBox;
            GS_SI_SIDE.put(u, (side == 0) ? 1 : 0);

            // 特效：附魔台文字沿斜劈弧线
            //   第 1 刀：（长1 左1 高1）→（长5 左3 高2）
            //   第 2 刀：（长1 左3 高1）→（长5 左1 高2）
            // ★★ 「高」这一轴由**世界上方**改成**与视线垂直的上方 (ux,uy,uz)**
            //      ⇒ 整条弧线跟着准星一起倾斜（抬头砍就是往斜上划）。
            //      ★ 水平看时 up = (0,1,0) ⇒ 弧线坐标与改前**逐位相同**。
            Particle pt = core.gsVoEnchType();
            double latS = (side == 0) ? -1.0 : 1.0;       // 左1 = -1（最左）/ 左3 = +1（最右）
            double latE = (side == 0) ? 1.0 : -1.0;
            int n = 18;
            for (int i = 0; i <= n; i++) {
                double t = i / (double) n;
                double f = 1.0 + 4.0 * t;                 // 长 1 → 5
                double lat = latS + (latE - latS) * t;    // 左1 → 左3（或反向）
                double h = 1.0 + 1.0 * t;                 // 高 1 → 2
                ListenerCore.gsMsDust(world, pt, null,
                        loc.getX() + fx * f + rx * lat + ux * h,
                        loc.getY() + fy * f + ry * lat + uy * h,
                        loc.getZ() + fz * f + rz * lat + uz * h);
            }
            ListenerCore.gsMsSound(world, loc, "entity.player.attack.sweep", 0.8f, 1.1f);

            // ★ 伤害判定：整个 **宽 3 × 长 5 × 高 2** 的长方体（不是一条线）
            // ★★ 这个长方体现在**跟着准星一起转**（不再只是水平那一层）
            //      ★ 判定原点 = 玩家**胸口**（脚 + 1.0）。
            SiSt st = gsSiGet(p);
            double dmg = gsSiDmg(st.n + 1);               // ★ 按"这一刀打出去之后"的层数算
            String selfId = u;
            java.util.Set<String> hit = new java.util.HashSet<>();
            int count = 0;
            double halfW = GS_SI_WIDE * 0.5;              // 左右各 1.5 格
            double px0 = loc.getX();
            double py0 = loc.getY() + 1.0;
            double pz0 = loc.getZ();
            // 搜索盒：以**胸口**为中心、半径 GS_SI_RANGE + 1 的立方体
            //   ★ 视线可以指任何方向 ⇒ 不能再拿「前方 2.5 格」当中心
            //     （那样「低头砍脚下」会整个漏掉）。立方体一定包得住那个 5 格长的箱子。
            Location cbox = ListenerCore.gsMsLoc(world, px0, py0, pz0);
            if (cbox != null) {
                double half = GS_SI_RANGE + 1.0;
                List<Entity> list = null;
                try {
                    list = new ArrayList<>(world.getNearbyEntities(cbox, half, half, half));
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    for (Entity ent : list) {
                        if (!gsSiCanHit(ent, selfId)) {
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
                        double ex = el.getX() - px0;
                        double ey = (el.getY() + 0.9) - py0;   // 怪身体中心 vs 玩家胸口
                        double ez = el.getZ() - pz0;
                        double fwd = ex * fx + ey * fy + ez * fz;   // 沿准星（0~5）
                        double lat = ex * rx + ey * ry + ez * rz;   // 左右（±1.5）
                        double upj = ex * ux + ey * uy + ez * uz;   // 与准星垂直的上下（±1）
                        if (fwd < -0.4 || fwd > GS_SI_RANGE) {
                            continue;                              // 身后 0.4 容差
                        }
                        if (lat < -halfW || lat > halfW) {
                            continue;
                        }
                        if (Math.abs(upj) > (GS_SI_H * 0.5)) {
                            continue;
                        }
                        hit.add(eid);
                        gsSiHurt(ent, dmg, p);
                        count++;
                    }
                }
            }
            if (count > 0) {
                gsSiAdd(p);                               // ★ 命中 ≥ 1 个生物 ⇒ 连击 +1
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 右键「寂灭」
    // ======================================================================

    /**
     * {@code gsSiBurst(p)} —— ★★ 瞬发「寂灭」。
     *
     * <p>以<b>玩家自己</b>为圆心、半径 {@link #GS_SI_BURST_R}（7）的圆圈，圈内敌人
     * <b>直接</b>吃 {@code gsSiDmg(当前层数) × GS_SI_BURST_MUL}（2）点伤害；
     * 冷却 {@link #GS_SI_BURST_CD}（15 秒）。
     *
     * <p>★ 判定形状 = <b>水平半径 ≤ 7</b> 且 <b>竖直差 ≤ 7</b>（扁圆柱）。
     * ★ 伤害 = {@code gsSiDmg(当前层数) × 2} —— {@code gsSiDmg()} 会把 0 层夹到 1 层，
     * 所以<b>没叠层时也保底 17×2 = 34 点</b>；满 99 层 = 213×2 = <b>426 点</b>。
     * ★★ 右键<b>不叠层</b>（层数仍然只靠左键斩击叠）。
     */
    boolean gsSiBurst(Player p) {
        try {
            if (p == null) {
                return false;
            }
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_SI_BURST_AT.get(u);
            if (last != null && (now - last) < GS_SI_BURST_CD) {
                return false;                             // 冷却中
            }
            GS_SI_BURST_AT.put(u, now);

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return false;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return false;
            }

            // ★ 伤害按**当前层数**算；gsSiDmg 会把 0 层夹到 1 层 ⇒ 没叠层也保底 17×2 = 34
            SiSt st = gsSiGet(p);
            double dmg = gsSiDmg(st.n) * GS_SI_BURST_MUL;

            double px = loc.getX();
            double py = loc.getY();
            double pz = loc.getZ();
            String selfId = u;
            double r2 = GS_SI_BURST_R * GS_SI_BURST_R;
            java.util.Set<String> hit = new java.util.HashSet<>();
            int count = 0;

            // 先用一个立方体把附近的实体捞出来，再自己按"圆圈"精确筛
            Location cbox = ListenerCore.gsMsLoc(world, px, py + 1.0, pz);
            if (cbox != null) {
                List<Entity> list = null;
                try {
                    list = new ArrayList<>(world.getNearbyEntities(
                            cbox, GS_SI_BURST_R, GS_SI_BURST_R, GS_SI_BURST_R));
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    for (Entity ent : list) {
                        if (!gsSiCanHit(ent, selfId)) {
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
                        double dx = el.getX() - px;
                        double dz = el.getZ() - pz;
                        if (dx * dx + dz * dz > r2) {
                            continue;                         // 水平半径
                        }
                        if (Math.abs(el.getY() - py) > GS_SI_BURST_R) {
                            continue;                         // 竖直容差
                        }
                        hit.add(eid);
                        gsSiHurt(ent, dmg, p);
                        count++;
                    }
                }
            }

            // 特效 + 音效
            gsSiBurstDraw(world, px, py, pz);
            try {
                BurstFx f = new BurstFx();
                f.w = world;
                f.x = px;
                f.y = py;
                f.z = pz;
                f.until = now + GS_SI_BURST_FX_MS;
                GS_SI_BURST_FX.add(f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            ListenerCore.gsMsSoundAt(p, "entity.wither.spawn", 0.8f, 0.6f);
            ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 1.0f, 0.7f);
            ListenerCore.gsMsSoundAt(p, "entity.evoker.cast_spell", 1.0f, 0.5f);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code GS_SI_BURST_FX} 的一条（原脚本 8651 行的对象字面量）。 */
    static final class BurstFx {
        World w;
        double x;
        double y;
        double z;
        long until;
    }

    /**
     * {@code gsSiBurstDraw(world, x, y, z)} —— 画那圈 7 格半径的圆：
     * 脚边一圈 + 胸口一圈（半密度），看着是个「圈」而不是一条线。
     */
    void gsSiBurstDraw(World world, double x, double y, double z) {
        try {
            Particle pt = core.gsVoEnchType();
            if (pt == null) {
                return;
            }
            int n = GS_SI_BURST_FX_N;
            if (n < 8) {
                n = 8;
            }
            for (int i = 0; i < n; i++) {
                double a = (i / (double) n) * 2.0 * Math.PI;
                double cx = x + Math.cos(a) * GS_SI_BURST_R;
                double cz = z + Math.sin(a) * GS_SI_BURST_R;
                ListenerCore.gsMsDust(world, pt, null, cx, y + 0.3, cz);
                if ((i % 2) == 0) {
                    ListenerCore.gsMsDust(world, pt, null, cx, y + 1.8, cz);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsSiFxTick()} —— 圆圈特效的淡出（由心跳每 2 刻重画一次，到期就丢）。 */
    void gsSiFxTick() {
        try {
            if (GS_SI_BURST_FX.isEmpty()) {
                return;
            }
            long now = ListenerCore.now();
            List<BurstFx> keep = new ArrayList<>();
            for (int i = 0; i < GS_SI_BURST_FX.size(); i++) {
                BurstFx f = GS_SI_BURST_FX.get(i);
                if (f == null) {
                    continue;
                }
                if (now > f.until) {
                    continue;                             // 到期 ⇒ 不再重画
                }
                gsSiBurstDraw(f.w, f.x, f.y, f.z);
                keep.add(f);
            }
            GS_SI_BURST_FX = keep;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 去抖
    // ======================================================================

    /** {@code gsSiClickOnce(p, tag)}。 */
    boolean gsSiClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_SI_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_SI_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsSiRecentRight(p, gapMs)}。 */
    boolean gsSiRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_SI_CLICK_AT.get(ListenerCore.uid(p) + "|R");
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

    /** {@code gsSiGenNow()} —— 读不到返回 <b>null</b>（★ 不是 -1）。 */
    Integer gsSiGenNow() {
        try {
            World w = Bukkit.getServer().getWorlds().get(0);
            Integer v = w.getPersistentDataContainer().get(GS_SI_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? Integer.valueOf(0) : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsSiGenBump()}。 */
    void gsSiGenBump() {
        try {
            Integer cur = gsSiGenNow();
            if (cur == null) {
                cur = Integer.valueOf(0);
            }
            GS_SI_GEN = cur + 1;
            World w = Bukkit.getServer().getWorlds().get(0);
            w.getPersistentDataContainer().set(GS_SI_K_GEN, PersistentDataType.INTEGER, GS_SI_GEN);
        } catch (Throwable e) {
            if (GS_SI_GEN <= 0) {
                GS_SI_GEN = 1;
            }
        }
    }

    /** {@code gsSiGenOk()} —— ★ 读不到世代号 ⇒ 一律按自己的算（恒 true）。 */
    boolean gsSiGenOk() {
        try {
            Integer g = gsSiGenNow();
            if (g == null || GS_SI_GEN == -1) {
                return true;
            }
            return (g == GS_SI_GEN);
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsSiPulse()} —— 每 2 刻：维护连击血条 + 清血条 + 重画「寂灭」圆圈特效。 */
    void gsSiPulse() {
        if (!gsSiGenOk()) {
            gsSiReleaseAll();
            return;
        }
        try {
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                try {
                    gsSiPlayerTick(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默（单个玩家出错不影响其他人）
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsSiBarsClean();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsSiFxTick();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 注册同步监听器（★ Java 版：幂等注册一次）
    // ======================================================================

    /** {@code gsSiRegisterSync()} —— 注册 4 个 {@code HIGHEST} 监听器。 */
    boolean gsSiRegisterSync() {
        if (GS_SI_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new Sync(), GS.plugin());
            GS_SI_REGISTERED = true;
            ListenerCore.gsDaLog("此刻万籁俱寂监听器注册成功（左键 3×5×2 斩击叠连击 / 右键瞬发“寂灭” 7 格圆圈 AoE）");
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("此刻万籁俱寂监听器注册异常: " + e);
            return false;
        }
    }

    /** 本子系统的 4 个监听器（★ 全部 {@code HIGHEST}，{@code ignoreCancelled} 默认 false）。 */
    final class Sync implements Listener {

        /**
         * ① 左键 = 斩击；右键 = 瞬发「寂灭」。
         *
         * <p>★ 故意<b>不</b>检查 {@code e.isCancelled()}：有些前置插件会先取消
         * {@code *_CLICK_AIR}，一检查就变成「必须点方块才放得出来」。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsSiIs(gsSiHand(p))) {
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
                    // ★★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的事件），
                    //    但玩家真主手拿着本物品 ⇒ 那是本附属自己派发的假事件，放过，
                    //    否则机器每 0.5 秒就会替旁边的人**误刷一次蓄力心跳**。
                    //    真人右键时 event.getItem() 一定就是手里那件物品，不会误伤。
                    try {
                        ItemStack evItem = e.getItem();
                        String evName = (evItem == null) ? "" : String.valueOf(evItem.getType().name());
                        if (evName.length() == 0 || evName.equals("AIR")) {
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }

                    // ★ 取消右键交互（金镐不许挖矿 / 不许开箱子 / 不许放方块）
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
                    // ★★ 右键 = 瞬发「寂灭」（7 格圆圈 AoE，冷却 15 秒）
                    //   ★ 按住右键时客户端会**反复发包**，冷却会把重复的那些挡掉
                    GS_SI_CLICK_AT.put(ListenerCore.uid(p) + "|R", ListenerCore.now());
                    gsSiBurst(p);
                } else if (act.contains("LEFT_CLICK")) {
                    if (!gsSiClickOnce(p, "L")) {
                        return;
                    }
                    if (GS_SI_NO_BREAK) {
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
                    gsSiSwing(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ② 左键兜底：挥手动画（对着空气点也会发；与 ① 共用去抖 ⇒ 不会放两次）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsSiIs(gsSiHand(p))) {
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
                if (gsSiRecentRight(p, 300)) {
                    return;                                   // 右键附带的挥手 → 忽略
                }
                if (!gsSiClickOnce(p, "L")) {
                    return;
                }
                gsSiSwing(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ 手持时挖不动方块（双保险）。
         *
         * <p>★ 原脚本是「{@code if (GS_SI_NO_BREAK)} 才注册」；这里因为
         * {@link #GS_SI_NO_BREAK} 是编译期常量 {@code true}，注册进来 + 内部判断
         * 与「条件注册」等价。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!GS_SI_NO_BREAK) {
                    return;
                }
                if (!gsSiIs(e.getPlayer().getInventory().getItemInMainHand())) {
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
                if (!GS_SI_NO_BREAK) {
                    return;
                }
                if (!gsSiIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 启动（原脚本 9253~9277 行）
    // ======================================================================

    /**
     * {@code gsSiStart()} —— bump 世代号 + 注册监听器 + 排心跳 + 打加载日志。
     *
     * <p>★★ 心跳是 {@code runRepeating(gsSiPulse, 20, 2)} ——
     * <b>delay = 20 刻、period = 2 刻，两个参数不同</b>，别抄成一个。
     */
    public void gsSiStart() {
        try {
            gsSiGenBump();

            if (!gsSiRegisterSync()) {
                ListenerCore.gsDaWarn("此刻万籁俱寂启动失败：监听器没注册上（连击血条仍会跑）");
            }
            GS.runRepeating(this::gsSiPulse, 20L, 2L);   // 每 2 刻（0.1 秒）维护连击血条 / 清理
            try {
                boolean enchOk = (core.gsVoEnchType() != null);
                ListenerCore.gsDaLog(gsSiStartLine(GS_SI_GEN, enchOk));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("此刻万籁俱寂启动异常: " + e);
        }
    }

    /**
     * 原脚本 9263~9272 行的加载日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 抽成静态方法<b>只为让探针逐字节对账</b>，不改任何行为。
     * 对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 37 行）：
     * <pre>
     * [构思技艺] 此刻万籁俱寂（GS_SILENCE）已加载 · 斩击 17~213 点（随连击 +2/层 · 满 99 连击）/
     * 范围 宽3×长5×高2 / 窗口 7 秒（未命中**清 0**） · 寂灭 半径 7 格圆圈 × 2 倍伤害（34~426 点）·
     * 冷却 15 秒 · 不叠层 · 附魔文字粒子 可用 · 世界世代号 74
     * </pre>
     */
    static String gsSiStartLine(int gen, boolean enchOk) {
        return "此刻万籁俱寂（" + GS_SI_ID + "）已加载 · 斩击 "
                + jsNum(GS_SI_DMG_BASE) + "~" + jsNum(gsSiDmg(GS_SI_MAX)) + " 点（随连击 +" + jsNum(GS_SI_DMG_PER)
                + "/层 · 满 " + GS_SI_MAX + " 连击）/ 范围 宽" + jsNum(GS_SI_WIDE) + "×长" + jsNum(GS_SI_RANGE)
                + "×高" + jsNum(GS_SI_H) + " / 窗口 " + jsNum(GS_SI_WINDOW / 1000.0) + " 秒（未命中**清 0**）"
                + " · 寂灭 半径 " + jsNum(GS_SI_BURST_R) + " 格圆圈 × " + jsNum(GS_SI_BURST_MUL)
                + " 倍伤害（" + jsNum(gsSiDmg(1) * GS_SI_BURST_MUL) + "~"
                + jsNum(gsSiDmg(GS_SI_MAX) * GS_SI_BURST_MUL) + " 点）· 冷却 "
                + jsNum(GS_SI_BURST_CD / 1000.0) + " 秒 · 不叠层 · 附魔文字粒子 "
                + (enchOk ? "可用" : "不可用")
                + " · 世界世代号 " + gen;
    }

    /** 原脚本 9202 行的注册日志（不含前缀），供探针对账。 */
    static String gsSiRegisterLine() {
        return "此刻万籁俱寂监听器注册成功（左键 3×5×2 斩击叠连击 / 右键瞬发“寂灭” 7 格圆圈 AoE）";
    }
}
