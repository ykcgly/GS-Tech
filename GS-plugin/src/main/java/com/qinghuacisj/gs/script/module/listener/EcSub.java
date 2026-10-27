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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「<b>蚀月·死神梦镰</b>」子系统
 * （原脚本第 <b>6848~7789</b> 行，顶层入口 {@code gsEcStart();} 在第 7789 行）。
 *
 * <h2>玩法</h2>
 * 手持蚀月·死神梦镰（{@code GS_ECLIPSE_SCYTHE}，下界合金锄）时：
 * <ul>
 *   <li><b>左键</b> = 发射<b>蚀月轮</b>：一个<b>恒为水平</b>的红色尘埃圆环
 *       （半径 1 格、点距 0.35），沿<b>准星完整三维方向</b>每刻前进 <b>0.5</b> 格，
 *       射程 <b>5</b> 格；路上碰到生物、撞墙、或到射程尽头就<b>停下</b>，
 *       停下后原地存在 <b>1.5 秒</b>，期间每 0.5 秒一发 <b>14</b> 点伤害脉冲
 *       （圆环平面 ±1.8 格内所有生物，无击退、不留无敌帧）。发射冷却 <b>1.2 秒</b>。</li>
 *   <li><b>定身</b>：蚀月轮命中时定身 <b>0.6 秒</b>，但<b>同一目标 3 秒内只定一次</b>
 *       （之后 3 秒内再打中只掉血、不再定身 —— 否则「每 0.5 秒定身 0.6 秒」会把怪钉死）。</li>
 *   <li><b>叠层「血」</b>：每次伤害脉冲 +1 层；<b>普通左键</b>还额外补一次
 *       「前方 3 格长 × 3 格宽」的命中判定，命中 ≥1 只也 +1 层
 *       （★ 只叠层、<b>不额外造成伤害</b> —— 贴脸时原版近战已经打过了，
 *       避免伤害三连爆）。每层窗口 <b>7 秒</b>，满 <b>23</b> 层后改成 <b>35 秒</b>倒计时。</li>
 *   <li><b>右键</b>（满 23 层）= <b>审判</b>：立刻无敌、原地起半径 <b>8</b> 格的三色法阵
 *       （外红 / 中紫 / 内白 + 8 条旋转辐条），<b>蓄力 1.8 秒</b>读条（血条标题只有「审判」二字），
 *       读条结束才天降圆柱粒子砸下 <b>233</b> 点伤害（最多 60 个目标、每根错开 2 毫秒），
 *       无敌 <b>1.8 秒</b>后解除。<b>不锁视角、不漂浮</b>。</li>
 * </ul>
 *
 * <h2>★★ 层数的权威状态：脚本内存（不是 PDC）</h2>
 * 与月镰同一套修复（原脚本 7251~7290 行的注释与代码）：旧版把「血」的层数<b>只</b>存在玩家
 * PDC 上 ⇒ PDC 写入 / 回读任何一环在 GraalJS 侧静默失败就「每次读回来都是 0」。
 * 现在：<b>读</b>内存（{@code GS_EC_ST}）优先、内存没有才尝试从 PDC 恢复一次；
 * <b>写</b>先写内存（必定成功）再尽力写 PDC。探针里有行为级反向断言钉住它。
 *
 * <h2>★ 心跳由月镰驱动（本子系统<b>不</b>注册 runRepeating）</h2>
 * 原脚本 {@code gsEcStart()} 里<b>没有</b> {@code runRepeating}；蚀月的血条维护
 * （{@code gsEcPlayerTick}）与血条清理（{@code gsEcBarsClean}）是<b>月镰</b>的心跳
 * {@code gsMsPulse}（第 6662 / 6667 行）顺手调用的。Java 版保持同样的调用关系
 * （{@link MsSub#bindEc}）。
 *
 * <h2>★ 监听器：原脚本 reload 会累积，Java 版改为幂等注册</h2>
 * 与月镰同样的真实缺陷：原脚本用 RSC 的 {@code pm.registerEvent(...)} 自己注册，
 * {@code GS_EC_REGISTERED} 闩锁是脚本级变量（reload 归零）⇒ 每次 reload 再注册一遍。
 * Java 版用 {@code Listener} 实现类 + {@code registerEvents} <b>只注册一次</b>。
 * 优先级逐处核对：原脚本 4 处 {@code registerEvent} 全部 {@code Priority.HIGHEST}，
 * 没有第 5 个参数 ⇒ {@code ignoreCancelled} 默认 <b>false</b>（Java 默认也是 false）。
 *
 * <h2>★ 唯一的「结构性差异」：{@code GS_EC_NO_BREAK} 的开关时机</h2>
 * 原脚本在<b>注册时</b>判一次（7729 行的 {@code if (GS_EC_NO_BREAK) { ... reg(BreakEv, ...)
 * ... reg(DmgBlkEv, ...) ... }}）—— 常量恒为 {@code true} ⇒ 那两个处理体<b>总是</b>被注册；
 * 但「注册时条件开关」这件事在 Java 的注解式注册里没法表达
 * （{@code @EventHandler} 是编译期确定的）。Java 版把开关<b>挪进处理体第一行</b>：
 * <pre>{@code
 * public void onBlockBreak(BlockBreakEvent e) {
 *     if (!GS_EC_NO_BREAK) return;   // 等价于原脚本「注册时判一次」
 *     ...
 * }</pre>
 * 行为等价（常量恒 {@code true} ⇒ 这行永不生效），但**位置**与脚本不同 —— 这是
 * 本子系统<b>唯一</b>一处刻意偏离逐字照抄的地方，探针里单独钉住。
 * 月镰侧同理（{@code GS_MS_NO_BREAK}，原脚本 6780 行同款写法）。
 *
 * <h2>★ 血条标题只喂纯字符串</h2>
 * 与月镰同一段事故注释：Adventure {@code Component} 塞进 {@code setTitle} 会被
 * GraalJS 隐式 {@code toString()} 成一长串 dump。蚀月标题是<b>双色</b>：
 * 主体用 {@link #GS_EC_BLOOD_COL}（{@code RED} ⇒ {@code §c}）、后缀金色（{@code §6}）
 * —— {@code §c血 5/23}、满层 {@code §c血 23/23§6 右键释放“审判”}、
 * 审判读条 {@code §f审判}。★ 走 {@link MsSub#gsMsTitle2}（原脚本把它定义在月镰那一段）。
 *
 * <h2>死代码 / 未移植项（逐条列出）</h2>
 * <ol>
 *   <li>原脚本 6930~6932 行 {@code GS_EC_PET} / {@code GS_EC_PE}
 *       （{@code Java.type} 取的 {@code PotionEffectType} / {@code PotionEffect}）
 *       —— 机械统计：{@code GS_EC_PET} 全文出现 <b>2</b> 次、{@code GS_EC_PE} <b>3</b> 次，
 *       <b>全部集中在这三行声明 / 赋值里，没有任何一处读取</b> ⇒ 纯死代码（只写不读），
 *       删掉不改变任何可观察行为。审判<b>不</b>用漂浮药效（注释里写明了「无漂浮」）。</li>
 *   <li>原脚本 7550 行 {@code // ★ 视角锁定已删除（原 gsEcLockView）}、
 *       7552~7553 行 {@code // ★ 「无敌保持到落地」已删除（原 gsEcInvulnUntilLand）}
 *       —— 这两个函数在脚本里<b>已经不存在</b>，Java 版同样没有。</li>
 *   <li>原脚本 7339 行 {@code var plain = '' + main + (suffix == null ? '' : '' + suffix);}
 *       —— 算完<b>从不使用</b>（真正的标题走下一行的 {@code gsMsTitle2}）⇒ 死局部变量，
 *       Java 版不写它。</li>
 *   <li>原脚本 7650~7659 行 {@code reg()} 的 {@code JavaAdapter} 降级分支 ——
 *       GraalJS 25.1.3 没有 {@code JavaAdapter}（{@code tools/GraalProbe.java} 实测）
 *       ⇒ 永远走不到。</li>
 *   <li>原脚本 7639~7643 行的 {@code plugin} 探测链由 {@code GSPlugin.instance()} 取代。</li>
 * </ol>
 *
 * <h2>★ 一处「注释与代码不一致」的记录（<b>保留代码</b>）</h2>
 * 原脚本 7228~7230 行的注释写「每 0.5 秒一发」「第 1 / 11 / 21 刻各一发」，
 * 但代码里 {@code per = 10} 之后<b>每拍都先判断再 {@code nextPulse -= 2}</b>
 * ⇒ 实际间隔是 <b>5 刻（0.25 秒）</b>，1.5 秒生命里共 <b>3</b> 发、落在第 <b>1 / 6 / 11</b> 刻。
 * 按红线 1「数值逐字保留」⇒ <b>照抄代码</b>（{@link #gsEcHoldPulseTicks} 用大量输入与真 JS 对账），
 * 不按注释改。
 */
public final class EcSub {

    private final ListenerCore core;

    // ======================================================================
    // 参数（原脚本 6852~6910 行）—— 字段名 = 原脚本顶层 var 名
    // ======================================================================

    /** 蚀月·死神梦镰的物品 ID。 */
    static final String GS_EC_ID = "GS_ECLIPSE_SCYTHE";

    /** true = 手持蚀月挖不动方块。 */
    static final boolean GS_EC_NO_BREAK = true;

    /** false = 蚀月轮 / 审判不打其它玩家。 */
    static final boolean GS_EC_HIT_PLAYERS = false;

    // ---- 蚀月轮 ----

    /** 射程（格，**三维距离**：能朝天上打）。 */
    static final double GS_EC_W_RANGE = 5.0;

    /** 圆环半径（约 1 格）。 */
    static final double GS_EC_W_R = 1.0;

    /** ★ 第三十五批：每刻前进（格）—— 原 0.35 ⇒ 弹速快约 43%。 */
    static final double GS_EC_W_STEP = 0.5;

    /** ★ 第十六批：每次伤害脉冲的伤害 = 14 点。 */
    static final double GS_EC_W_DMG = 14.0;

    /** ★ 第三十五批：每 **0.5 秒**造成一次伤害（毫秒）—— 原 1 秒。 */
    static final long GS_EC_W_PULSE = 500;

    /** ★ 第三十五批：命中定身（毫秒）= **0.6 秒** —— 原 0.5 秒。 */
    static final long GS_EC_W_LOCK = 600;

    /** ★ 第十九批：同一目标两次定身之间的**间隔**（毫秒）= 3 秒。 */
    static final long GS_EC_W_LOCK_CD = 3000;

    /** ★ 伤害判定高度（圆环平面 ± 格）。 */
    static final double GS_EC_W_H = 1.8;

    /** false = 每次伤害脉冲 +1 层「血」（默认）；true = 每个被命中的生物各 +1 层。 */
    static final boolean GS_EC_W_STACK_PER_HIT = false;

    /** ★ 第三十五批：停下（落地）后原地存在（毫秒）= **1.5 秒**（原 3 秒）。 */
    static final long GS_EC_W_LIFE = 1500;

    /** 发射冷却（毫秒）= 1.2 秒。 */
    static final long GS_EC_W_CD = 1200;

    /** 圆环点距（越小越密）。 */
    static final double GS_EC_W_GAP = 0.35;

    /** 蚀月轮红色。 */
    static final int[] GS_EC_W_COL = {235, 30, 40};

    // ---- 普通左键（近战挥砍）：只叠层、不额外造成伤害 ----

    /** 普通左键命中是否叠层（false = 关掉）。 */
    static final boolean GS_EC_SW_HIT = true;

    /** 判定长度：前方 3 格。 */
    static final double GS_EC_SW_R = 3.0;

    /** 判定宽度：共 3 格宽（左右各 1.5）。 */
    static final double GS_EC_SW_W = 3.0;

    /** 判定上下高度（格）。 */
    static final double GS_EC_SW_H = 1.6;

    // ---- 「血」----

    /** 满层。 */
    static final int GS_EC_MAX = 23;

    /** 未满层窗口（毫秒）= 7 秒。 */
    static final long GS_EC_WINDOW = 7000;

    /** 满层倒计时（毫秒）= 35 秒。 */
    static final long GS_EC_FULL_WIN = 35000;

    /** 「血」的血条颜色 + 标题主色 = 红。 */
    static final String GS_EC_BLOOD_COL = "RED";

    // ---- 审判 ----

    /** 法阵半径（格）。 */
    static final double GS_EC_J_R = 8.0;

    /** ★ 第三十五批：法阵持续 / **蓄力读条**（刻）= **1.8 秒**。 */
    static final int GS_EC_J_TICKS = 36;

    /** 蓄力倒计时结束时从天而降的伤害。 */
    static final double GS_EC_J_DMG = 233.0;

    /** ★ 第三十五批：无敌时长（刻）= **1.8 秒**（与蓄力同步解除）。 */
    static final int GS_EC_J_INV = 36;

    /** 法阵红。 */
    static final int[] GS_EC_J_COL_R = {235, 40, 40};

    /** 法阵紫。 */
    static final int[] GS_EC_J_COL_P = {150, 60, 255};

    /** 法阵白。 */
    static final int[] GS_EC_J_COL_W = {245, 245, 245};

    /** ★ 第二十批：法阵蓝（审判圆柱粒子新增）。 */
    static final int[] GS_EC_J_COL_B = {70, 110, 255};

    /** ★ 第七十批：审判**最多**砸几个目标（安全阀）。 */
    static final int GS_EC_J_MAX_TARGETS = 60;

    // ---- PDC 键（原脚本 6920~6928 行）----

    /** {@code gs:ec_blood} —— 「血」层数（跨重启备份）。 */
    static final NamespacedKey GS_EC_K_BLOOD = NamespacedKey.fromString("gs:ec_blood");

    /** {@code gs:ec_until} —— 层数计时到期时刻（毫秒）。 */
    static final NamespacedKey GS_EC_K_UNTIL = NamespacedKey.fromString("gs:ec_until");

    /** {@code gs:ec_full} —— 是否已满层（0 / 1）。 */
    static final NamespacedKey GS_EC_K_FULL = NamespacedKey.fromString("gs:ec_full");

    /** 原脚本的 {@code GS_EC_PDT}；Java 里该类必然可用 ⇒ 恒非 null（保留探测分支用）。 */
    static final Class<?> GS_EC_PDT = PersistentDataType.class;

    // ======================================================================
    // 运行期状态（原脚本顶层 var）
    // ======================================================================

    /** {@code GS_EC_REGISTERED} —— 原脚本<b>只写不读</b>的闩锁；Java 里用作幂等注册闩锁。 */
    boolean GS_EC_REGISTERED;

    /** {@code GS_EC_CLICK_AT} —— {@code uuid|L / uuid|R} → 上次处理毫秒（去抖）。 */
    final Map<String, Long> GS_EC_CLICK_AT = new HashMap<>();

    /** ★ 第十九批：{@code GS_EC_LOCK_AT} —— uuid → 上次「蚀月轮定身」的毫秒。 */
    final Map<String, Long> GS_EC_LOCK_AT = new HashMap<>();

    /** {@code GS_EC_FIRE_AT} —— uuid → 上次发射蚀月轮的毫秒（冷却）。 */
    final Map<String, Long> GS_EC_FIRE_AT = new HashMap<>();

    /** {@code GS_EC_ST} —— uuid → {@code { n, until, full }}（<b>内存权威</b>）。 */
    final Map<String, St> GS_EC_ST = new HashMap<>();

    /** {@code GS_EC_BARS} —— uuid → {@code { b, p }}。 */
    final Map<String, ListenerCore.BarRec> GS_EC_BARS = ListenerCore.newBarTable();

    /** {@code GS_EC_JUDGING} —— uuid → true（审判进行中）。 */
    final Map<String, Boolean> GS_EC_JUDGING = new HashMap<>();

    /** 监听器实例（懒建，{@code registerEvents} 只调一次）。 */
    private Listener listener;

    /** 原脚本 {@code GS_EC_ST[uuid] = { n, until, full }}。 */
    static final class St {
        int n;
        long until;
        int full;
    }

    public EcSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsEcStart();} 调用（原脚本第 7789 行）。 */
    public void start() {
        gsEcStart();
    }

    // ======================================================================
    // 小工具（原脚本 6934~6938 行）
    // ======================================================================

    /** {@code gsEcIs(item)} —— 是不是蚀月·死神梦镰。 */
    static boolean gsEcIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_EC_ID);
    }

    /** {@code gsEcHand(p)} —— 主手物品。 */
    static ItemStack gsEcHand(Player p) {
        try {
            return p.getInventory().getItemInMainHand();
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 命中判定 / 无击退伤害（原脚本 6940~6986 行）
    // ======================================================================

    /**
     * {@code gsEcCanHit(ent, selfId)} —— 这只生物能不能被蚀月打中。
     *
     * <p>★★ 与月镰的 {@code gsMsCanHit} <b>关键差别</b>：这里多了一道
     * {@link ListenerCore#gsIsCombatTarget}（显式黑名单 {@code GS_NOT_ENEMY_TYPES}
     * + {@code LivingEntity} 判定），必须排在 {@code PLAYER} / {@code ARMOR_STAND}
     * 之后、{@code getHealth} 探针之前。
     *
     * <p>★ 为什么那道 {@code getHealth} 探针不够（原脚本注释原文）：
     * Paper 1.21.11 的 {@code org.bukkit.entity.Item} <b>自己声明了 {@code getHealth()}</b>
     * （不实现 {@code Damageable}，但方法在）⇒ <b>掉落物能骗过探针</b> ⇒
     * 审判会对着一地的掉落物逐个降光柱（每根 ≈ 212 个粒子）⇒ 卡服。
     */
    static boolean gsEcCanHit(Entity ent, String selfId) {
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
            if (tn.equals("PLAYER") && !GS_EC_HIT_PLAYERS) {
                return false;
            }
            if (tn.equals("ARMOR_STAND")) {
                return false;
            }
            // ★★★ 非活物不算命中（统一走那套 gsIsCombatTarget）
            if (!ListenerCore.gsIsCombatTarget(ent, tn)) {
                return false;
            }
            try {
                ((org.bukkit.entity.Damageable) ent).getHealth();
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
     * {@code gsEcHurtNoKb(ent, dmg, p)} —— 只掉血、不产生任何击退、也不留无敌帧。
     *
     * <p>★ 原脚本里这个函数与月镰的 {@code gsMsHurt} 是<b>逐字相同的两份</b>
     * （连三个细节与注释都一样）⇒ Java 版统一走 {@link ListenerCore#gsMsHurt}：
     * ①先 {@code setNoDamageTicks(0)} → 读速度 → 打伤害 → 写回速度；
     * ②{@code GS_WPN_DMG_GUARD++/--} 用 try/finally；③打完再 {@code setNoDamageTicks(0)}。
     */
    void gsEcHurtNoKb(Entity ent, double dmg, Player p) {
        core.gsMsHurt(ent, dmg, p);
    }

    // ======================================================================
    // 圆环绘制（原脚本 6988~7001 行）
    // ======================================================================

    /**
     * 圆环点数（原脚本 6992~6995 行）：{@code round(2πr / gap)} 后钳到 <b>[8, 200]</b>；
     * {@code gap == null || gap <= 0} ⇒ 用 <b>0.4</b>。
     */
    static int gsEcRingCount(double r, double gap) {
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

    /** 圆环第 {@code i} 个点相对圆心的偏移 {@code {dx, dz}}（原脚本 6996~6998 行）。 */
    static double[] gsEcRingPoint(int i, int n, double r) {
        double a = (2.0 * Math.PI * i) / n;
        return new double[]{Math.cos(a) * r, Math.sin(a) * r};
    }

    /**
     * {@code gsEcRingAt(world, pt, data, cx, cy, cz, r, gap)} —— 画一个<b>水平</b>圆环
     * （法向量 = Y 轴 ⇒ 环的平面永远平行于地面）。
     */
    void gsEcRingAt(World world, Particle pt, Particle.DustOptions data,
                    double cx, double cy, double cz, double r, double gap) {
        try {
            if (pt == null) {
                return;
            }
            int n = gsEcRingCount(r, gap);
            for (int i = 0; i < n; i++) {
                double[] p = gsEcRingPoint(i, n, r);
                ListenerCore.gsMsDust(world, pt, data, cx + p[0], cy, cz + p[1]);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 普通左键（近战挥砍）：叠「血」（原脚本 7003~7051 行）
    // ======================================================================

    /**
     * 普通左键的命中判定（原脚本 7040~7045 行的算式本体，<b>逐字照抄</b>）：
     * <b>前方 3 格长 × 3 格宽</b>、上下 ±{@code GS_EC_SW_H}。
     */
    static boolean gsEcMeleeHits(double px0, double py0, double pz0, double fx, double fz,
                                 double ex, double ey, double ez) {
        double rx = -fz;
        double rz = fx;                                // 水平右向
        double halfW = GS_EC_SW_W * 0.5;
        double dx = ex - px0;
        double dz = ez - pz0;
        double fwd = dx * fx + dz * fz;                // 前向投影（长度）
        double lat = dx * rx + dz * rz;                // 左右投影（宽度）
        if (fwd < -0.4 || fwd > GS_EC_SW_R) {
            return false;
        }
        if (lat < -halfW || lat > halfW) {
            return false;
        }
        if (Math.abs((ey + 0.9) - (py0 + 1.0)) > GS_EC_SW_H) {
            return false;
        }
        return true;
    }

    /**
     * {@code gsEcMelee(p)} —— 普通左键补一次命中判定：命中 ≥1 只 ⇒ +1 层「血」。
     *
     * <p>★ 修「贴脸按左键时，蚀月轮第一发脉冲不叠层」：原版近战先把怪打了
     * （击退 + 受击无敌帧），等蚀月轮停下打脉冲时那只怪常常已经出了环外 / 已死
     * ⇒ hit = 0 ⇒ 不叠层。<b>只叠层、不额外造成伤害</b>。
     *
     * @return 命中的生物个数（{@code GS_EC_SW_HIT == false} 时恒为 0）
     */
    int gsEcMelee(Player p) {
        try {
            if (!GS_EC_SW_HIT) {
                return 0;
            }
            if (p == null) {
                return 0;
            }
            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return 0;
            }
            if (loc == null) {
                return 0;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return 0;
            }
            Vector dir;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                return 0;
            }
            double fx = dir.getX();
            double fz = dir.getZ();
            double fl = Math.sqrt(fx * fx + fz * fz);
            if (fl < 1.0e-4) {
                return 0;                              // 垂直看天/看地 ⇒ 谈不上"水平 3 格"
            }
            fx /= fl;
            fz /= fl;
            double px0 = loc.getX();
            double py0 = loc.getY();
            double pz0 = loc.getZ();
            String selfId = ListenerCore.uid(p);
            Location cbox = ListenerCore.gsMsLoc(world, px0, py0 + 1.0, pz0);
            if (cbox == null) {
                return 0;
            }
            Collection<Entity> list;
            try {
                list = world.getNearbyEntities(cbox, GS_EC_SW_R + 0.8, GS_EC_SW_H, GS_EC_SW_R + 0.8);
            } catch (Throwable e) {
                list = null;
            }
            if (list == null) {
                return 0;
            }
            int n = 0;
            Iterator<Entity> it = list.iterator();
            while (it.hasNext()) {
                Entity ent = it.next();
                if (!gsEcCanHit(ent, selfId)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                if (!gsEcMeleeHits(px0, py0, pz0, fx, fz, el.getX(), el.getY(), el.getZ())) {
                    continue;
                }
                n++;
            }
            if (n > 0) {
                gsEcAdd(p);
            }
            return n;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ======================================================================
    // 蚀月轮（原脚本 7053~7158 行）
    // ======================================================================

    /**
     * {@code gsEcWheel(p)} —— 发射蚀月轮。
     *
     * <p>★ 飞行方向 = 准星方向的<b>完整三维向量</b>（含俯仰）⇒ 抬头就能把环打到天上去
     * （"水平"指的是<b>环本身恒为水平圆</b>，不是只能水平投出）。
     * 起点 = 齐腰高度（{@code y + 1.1}）+ 朝准星方向前移 0.6 格。
     */
    void gsEcWheel(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_EC_FIRE_AT.get(u);
            if (last != null && (now - last) < GS_EC_W_CD) {
                return;                                // 冷却 1.2 秒（第十九批）
            }
            GS_EC_FIRE_AT.put(u, now);

            // ★ 普通左键（近战挥砍）命中 ⇒ 先叠一层「血」
            gsEcMelee(p);

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

            final String selfId = u;
            final double fdx = dx;
            final double fdy = dy;
            final double fdz = dz;
            final World fworld = world;
            final Player fp = p;
            // 起点：齐腰高度 + 朝准星方向前移 0.6 格（抬头打 → 起点也抬高 y）
            final double[] pos = {loc.getX() + fdx * 0.6,
                    loc.getY() + 1.1 + fdy * 0.6,
                    loc.getZ() + fdz * 0.6};
            final double[] travelled = {0.0};

            final Particle pt = core.gsMsDustType();
            final Particle.DustOptions data = ListenerCore.gsMsDustOpts(
                    GS_EC_W_COL[0], GS_EC_W_COL[1], GS_EC_W_COL[2], 1.0);

            ListenerCore.gsMsSound(world, loc, "entity.evoker.cast_spell", 0.9f, 1.3f);

            Runnable step = new Runnable() {
                @Override
                public void run() {
                    try {
                        if (fp == null) {
                            return;
                        }
                        boolean online = true;
                        try {
                            online = fp.isOnline();
                        } catch (Throwable e) {
                            online = true;
                        }
                        if (!online) {
                            return;
                        }

                        travelled[0] += GS_EC_W_STEP;
                        gsEcRingAt(fworld, pt, data, pos[0], pos[1], pos[2], GS_EC_W_R, GS_EC_W_GAP);

                        // ① 飞出去的路上**碰到生物就停**（停下后才开始每秒伤害）
                        boolean touch = false;
                        Location box = ListenerCore.gsMsLoc(fworld, pos[0], pos[1], pos[2]);
                        if (box != null) {
                            Collection<Entity> list = null;
                            try {
                                list = fworld.getNearbyEntities(box, GS_EC_W_R, GS_EC_W_H, GS_EC_W_R);
                            } catch (Throwable e) {
                                list = null;
                            }
                            if (list != null) {
                                Iterator<Entity> it = list.iterator();
                                while (it.hasNext()) {
                                    Entity ent = it.next();
                                    if (!gsEcCanHit(ent, selfId)) {
                                        continue;
                                    }
                                    Location el;
                                    try {
                                        el = ent.getLocation();
                                    } catch (Throwable e) {
                                        continue;
                                    }
                                    double hx = el.getX() - pos[0];
                                    double hz = el.getZ() - pos[2];
                                    if (Math.sqrt(hx * hx + hz * hz) > GS_EC_W_R) {
                                        continue;
                                    }
                                    if (Math.abs((el.getY() + 0.9) - pos[1]) > GS_EC_W_H) {
                                        continue;
                                    }
                                    touch = true;
                                    break;
                                }
                            }
                        }

                        boolean stop = false;
                        if (touch) {
                            stop = true;                   // 撞到生物：停下
                        } else if (travelled[0] >= GS_EC_W_RANGE) {
                            stop = true;                   // 到射程尽头
                        } else {
                            // ② 下一格是不是墙？（不穿墙 ⇒ 撞上就停）
                            double nx = pos[0] + fdx * GS_EC_W_STEP;
                            double ny = pos[1] + fdy * GS_EC_W_STEP;
                            double nz = pos[2] + fdz * GS_EC_W_STEP;
                            Location nb = ListenerCore.gsMsLoc(fworld, nx, ny, nz);
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
                                stop = true;
                            } else {
                                pos[0] = nx;
                                pos[1] = ny;
                                pos[2] = nz;
                            }
                        }

                        if (stop) {
                            gsEcHold(fworld, pt, data, fp, selfId, pos[0], pos[1], pos[2], GS_EC_W_LIFE);
                            return;
                        }
                        GS.runLater(this, 1);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            };

            GS.runLater(step, 1);
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning(ListenerCore.PREFIX + "蚀月轮出错: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 蚀月轮停下后，第几拍会打伤害脉冲（1-based）—— 原脚本 7231~7248 行循环的<b>本体</b>。
     *
     * <p>★ 抽成静态方法<b>只为与真 JS 在大量输入上对账</b>；见类注释里
     * 「注释与代码不一致」那一段：实际是<b>每 5 刻</b>一发，不是注释说的每 10 刻。
     */
    static int[] gsEcHoldPulseTicks(double ms, double pulseMs) {
        int left = (int) Math.round(ms / 50.0);
        int per = (int) Math.max(2L, Math.round(pulseMs / 50.0));
        int nextPulse = 0;                             // 0 ⇒ 这一拍就打
        List<Integer> out = new ArrayList<>();
        int tickNo = 0;
        while (left > 0) {
            tickNo++;
            if (nextPulse <= 0) {
                out.add(tickNo);
                nextPulse = per;
            }
            nextPulse -= 2;
            left -= 2;
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * 蚀月轮停下后总共画几拍环。
     *
     * <p>★ 与 {@link #gsEcHold} 里那条 {@code while (st[0] > 0) { ... st[0] -= 2; }} 同构
     * ⇒ 拍数是 <b>{@code ceil(round(ms/50) / 2)}</b>，<b>不是</b>整数除法截断。
     * 奇数拍（如 {@code ms=50 ⇒ left=1}、{@code ms=1250 ⇒ left=25}）会被截断少画一拍，
     * 那是探针对账时抓出来的，照脚本语义取 ceil。
     */
    static int gsEcHoldTicks(double ms) {
        int left = (int) Math.round(ms / 50.0);
        return (left + 1) / 2;
    }

    /**
     * 蚀月轮飞行第 {@code tick} 拍（1-based）时的累计前进距离
     * （{@code travelled += GS_EC_W_STEP}，原脚本 7099 行）。
     */
    static double gsEcWheelTravelled(int tick) {
        return tick * GS_EC_W_STEP;
    }

    /**
     * 蚀月轮飞到第几拍会因「到射程尽头」而停（原脚本 7127 行
     * {@code travelled >= GS_EC_W_RANGE}）—— 无碰撞、无生物时的拍数。
     */
    static int gsEcWheelStopTick() {
        int k = 1;
        while (gsEcWheelTravelled(k) < GS_EC_W_RANGE) {
            k++;
        }
        return k;
    }

    /**
     * {@code gsEcHold(world, pt, data, p, selfId, cx, cy, cz, ms)} —— 停下后原地存在
     * {@code ms} 毫秒（每 2 刻画一次环），期间按 {@link #gsEcHoldPulseTicks} 打伤害脉冲。
     */
    void gsEcHold(World world, Particle pt, Particle.DustOptions data, Player p, String selfId,
                  double cx, double cy, double cz, long ms) {
        final World fworld = world;
        final Player fp = p;
        final double fx = cx;
        final double fy = cy;
        final double fz = cz;
        // st = { left, per, nextPulse }
        final int[] st = {(int) Math.round(ms / 50.0),
                (int) Math.max(2L, Math.round(GS_EC_W_PULSE / 50.0)), 0};
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                try {
                    if (st[0] <= 0) {
                        return;
                    }
                    gsEcRingAt(fworld, pt, data, fx, fy, fz, GS_EC_W_R, GS_EC_W_GAP);
                    if (st[2] <= 0) {
                        gsEcPulse(fworld, fp, selfId, fx, fy, fz);
                        st[2] = st[1];
                    }
                    st[2] -= 2;
                    st[0] -= 2;
                    GS.runLater(this, 2);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        };
        GS.runLater(tick, 2);
    }

    // ======================================================================
    // 「蚀月轮」定身间隔（原脚本 7160~7189 行）
    // ======================================================================

    /**
     * 定身间隔判定（原脚本 7184 行）：距上次定身不足 {@code GS_EC_W_LOCK_CD}（3 秒）
     * ⇒ <b>跳过这次定身</b>（伤害照常结算）。
     *
     * <p>★ {@code last == null}（从没定过）⇒ 允许。
     */
    static boolean gsEcLockGapOk(Long last, long now) {
        return !(last != null && (now - last) < GS_EC_W_LOCK_CD);
    }

    /** {@code gsEcLockPrune(now)} —— 顺手清掉过期条目（表很小，不会攒内存）。 */
    void gsEcLockPrune(long now) {
        try {
            Map<String, Long> keep = new HashMap<>();
            for (Map.Entry<String, Long> en : GS_EC_LOCK_AT.entrySet()) {
                if ((now - en.getValue()) < GS_EC_W_LOCK_CD) {
                    keep.put(en.getKey(), en.getValue());
                }
            }
            // ★ 原脚本是 `GS_EC_LOCK_AT = keep`（换一个新对象）；Java 用 clear + putAll。
            GS_EC_LOCK_AT.clear();
            GS_EC_LOCK_AT.putAll(keep);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEcLockOnce(ent, now)} —— 命中一次：3 秒内只定身一次。
     *
     * @return true = 本次真的定了身
     */
    boolean gsEcLockOnce(Entity ent, long now) {
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
            if (id.isEmpty()) {
                return false;
            }
            Long last = GS_EC_LOCK_AT.get(id);
            if (!gsEcLockGapOk(last, now)) {
                return false;                          // 定身间隔内
            }
            GS_EC_LOCK_AT.put(id, now);
            core.gsMsLockFor(ent, GS_EC_W_LOCK, null);  // ★ col = null ⇒ 只定身、不画柱
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 伤害脉冲（原脚本 7191~7226 行）
    // ======================================================================

    /**
     * {@code gsEcPulse(world, p, selfId, cx, cy, cz)} —— 一次伤害脉冲：圆环平面
     * ±{@code GS_EC_W_H} 内<b>所有</b>生物吃 14 点伤害 + 定身 0.6 秒 + 无击退。
     *
     * <p>★ 每次伤害都叠「血」：默认<b>每次脉冲 +1 层</b>；
     * {@code GS_EC_W_STACK_PER_HIT = true} 时每个被命中的生物各 +1 层（默认 false ⇒ 死代码分支）。
     *
     * @return 本次命中的生物个数
     */
    int gsEcPulse(World world, Player p, String selfId, double cx, double cy, double cz) {
        int hit = 0;
        try {
            long now = ListenerCore.now();
            gsEcLockPrune(now);
            Location box = ListenerCore.gsMsLoc(world, cx, cy, cz);
            if (box == null) {
                return 0;
            }
            Collection<Entity> list = null;
            try {
                list = world.getNearbyEntities(box, GS_EC_W_R, GS_EC_W_H, GS_EC_W_R);
            } catch (Throwable e) {
                list = null;
            }
            if (list == null) {
                return 0;
            }
            Iterator<Entity> it = list.iterator();
            while (it.hasNext()) {
                Entity ent = it.next();
                if (!gsEcCanHit(ent, selfId)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double hx = el.getX() - cx;
                double hz = el.getZ() - cz;
                if (Math.sqrt(hx * hx + hz * hz) > GS_EC_W_R) {
                    continue;
                }
                if (Math.abs((el.getY() + 0.9) - cy) > GS_EC_W_H) {
                    continue;
                }
                gsEcHurtNoKb(ent, GS_EC_W_DMG, p);
                gsEcLockOnce(ent, now);                // ★ 3 秒内只定身一次（伤害照常）
                hit++;
                if (GS_EC_W_STACK_PER_HIT) {
                    gsEcAdd(p);
                }
            }
            if (hit > 0) {
                gsEcAdd(p);                            // 每次伤害脉冲 +1 层
                Location loc = ListenerCore.gsMsLoc(world, cx, cy, cz);
                ListenerCore.gsMsSound(world, loc, "entity.player.attack.crit", 0.9f, 1.2f);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return hit;
    }

    // ======================================================================
    // 「血」：层数读写（内存权威 + PDC 备份）—— 原脚本 7251~7290 行
    // ======================================================================

    /** 与 {@link MsSub.MsPdcSource} 同形（蚀月自己一份，保持两个子系统各自自足）。 */
    interface EcPdcSource {
        PersistentDataContainer get() throws Throwable;
    }

    /** {@code gsEcGet(p)} —— 读「血」的层数（内存优先，缺失才试 PDC）。 */
    St gsEcGet(Player p) {
        St o = new St();
        try {
            if (p == null) {
                return o;
            }
            String u = String.valueOf(p.getUniqueId());
            return gsEcGet0(u, p::getPersistentDataContainer);
        } catch (Throwable e) {
            return o;
        }
    }

    /** {@code gsEcGet} 的本体（原脚本 7252~7273 行）—— <b>逐字照抄</b>。 */
    St gsEcGet0(String u, EcPdcSource src) {
        St o = new St();
        try {
            St m = GS_EC_ST.get(u);
            if (m != null) {
                o.n = m.n;
                o.until = m.until;
                o.full = m.full;
                return o;
            }
            if (GS_EC_PDT == null || GS_EC_K_BLOOD == null) {
                return o;
            }
            try {
                PersistentDataContainer pdc = src.get();
                Integer n = pdc.get(GS_EC_K_BLOOD, PersistentDataType.INTEGER);
                Long un = pdc.get(GS_EC_K_UNTIL, PersistentDataType.LONG);
                Integer f = pdc.get(GS_EC_K_FULL, PersistentDataType.INTEGER);
                if (n != null) {
                    o.n = MsSub.toInt32(n);
                }
                if (un != null) {
                    o.until = un;
                }
                if (f != null) {
                    o.full = MsSub.toInt32(f);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (o.n > 0 && o.until > ListenerCore.now()) {
                St s = new St();
                s.n = o.n;
                s.until = o.until;
                s.full = o.full;
                GS_EC_ST.put(u, s);
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

    /** {@code gsEcSet(p, n, until, full)} —— 写层数（先内存，再尽力写 PDC）。 */
    void gsEcSet(Player p, double n, double until, double full) {
        try {
            if (p == null) {
                return;
            }
            String u = String.valueOf(p.getUniqueId());
            gsEcSet0(u, n, until, full, p::getPersistentDataContainer);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEcSet} 的本体（原脚本 7275~7290 行）—— <b>逐字照抄</b>（先内存、后 PDC）。 */
    void gsEcSet0(String u, double n, double until, double full, EcPdcSource src) {
        try {
            int nn = MsSub.toInt32(n);
            long uu = (long) until;
            int ff = MsSub.toInt32(full);
            if (nn <= 0) {
                GS_EC_ST.remove(u);
            } else {
                St s = new St();
                s.n = nn;
                s.until = uu;
                s.full = ff;
                GS_EC_ST.put(u, s);
            }
            if (GS_EC_PDT == null || GS_EC_K_BLOOD == null) {
                return;
            }
            try {
                PersistentDataContainer pdc = src.get();
                pdc.set(GS_EC_K_BLOOD, PersistentDataType.INTEGER, nn);
                pdc.set(GS_EC_K_UNTIL, PersistentDataType.LONG, MsSub.gsMsLong(uu));
                pdc.set(GS_EC_K_FULL, PersistentDataType.INTEGER, ff);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 「血」：BossBar（原脚本 7292~7407 行）
    // ======================================================================

    /** {@code gsEcBarColor(colorName)} —— 颜色名 → BarColor；取不到时回落 {@code RED}。 */
    static BarColor gsEcBarColor(String colorName) {
        try {
            String n = String.valueOf(colorName == null ? GS_EC_BLOOD_COL : colorName);
            BarColor c = null;
            try {
                c = BarColor.valueOf(n);
            } catch (Throwable e) {
                c = null;
            }
            if (c == null) {
                try {
                    // 原脚本第二条路是 C[n]（enum 静态字段）—— 与 valueOf 取的是同一个常量
                    c = BarColor.valueOf(n);
                } catch (Throwable e) {
                    c = null;
                }
            }
            if (c == null) {
                try {
                    c = BarColor.RED;
                } catch (Throwable e) {
                    c = null;
                }
            }
            return c;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsEcBarStyle()} —— 优先 SEGMENTED_12，其次 SOLID。 */
    static BarStyle gsEcBarStyle() {
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
                return BarStyle.valueOf("SOLID");
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsEcBarOf(p, colorName)} —— 取 / 建这名玩家的「血」血条。 */
    BossBar gsEcBarOf(Player p, String colorName) {
        try {
            String u = String.valueOf(p.getUniqueId());
            ListenerCore.BarRec rec = GS_EC_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsEcBarColor(colorName);
            BarStyle s = gsEcBarStyle();
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            // ★ 标题只喂纯字符串（§c 红 / §6 金，见类注释）
            try {
                bar = Bukkit.getServer().createBossBar(
                        MsSub.gsMsTitle2("血 0/" + GS_EC_MAX, null, GS_EC_BLOOD_COL), c, s);
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
            GS_EC_BARS.put(u, r);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsEcBarShow(p, main, suffix, colorName, prog)} —— 刷「血」血条。
     *
     * <p>★ 原脚本 7339 行还算了 {@code var plain = '' + main + …}，<b>从不使用</b>
     * ⇒ 死局部变量，Java 版不写（见类注释）。
     */
    void gsEcBarShow(Player p, String main, String suffix, String colorName, double prog) {
        try {
            BossBar b = gsEcBarOf(p, colorName);
            if (b == null) {
                return;
            }
            double pr = prog;
            if (pr < 0) {
                pr = 0;
            }
            if (pr > 1) {
                pr = 1;
            }
            ListenerCore.gsBarTitleSet(b, MsSub.gsMsTitle2(main, suffix, colorName));
            try {
                b.setProgress(pr);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                BarColor col = gsEcBarColor(colorName);
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

    /** {@code gsEcBarHide(p)} —— 隐藏（不回收）。 */
    void gsEcBarHide(Player p) {
        ListenerCore.gsBarTblHide(GS_EC_BARS, p);
    }

    /** {@code gsEcClear(p)} —— 层数清零 + 藏血条。 */
    void gsEcClear(Player p) {
        try {
            gsEcSet(p, 0, 0, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsEcBarHide(p);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 「血」的标题正文（原脚本 7362 / 7369 / 7374 / 7391 / 7401 行）：{@code 血 n/23}。 */
    static String gsEcBloodText(int n) {
        return "血 " + n + "/" + GS_EC_MAX;
    }

    // ★ 满层标题的后缀 {@code ' 右键释放“审判”'} 在原脚本里是**字面量**（7362 / 7369 /
    //   7392 / 7402 行各写一次）⇒ Java 版同样**逐字内联**，不抽成常量
    //   （抽成常量会让「原脚本常量表 vs Java 常量表」的对账多出一个脚本里没有的名字）。

    /** {@code gsEcAdd(p)} —— 加一层「血」。 */
    void gsEcAdd(Player p) {
        try {
            long now = ListenerCore.now();
            St st = gsEcGet(p);
            if (st.n >= GS_EC_MAX) {
                // 已满层：命中只刷新 35 秒倒计时，层数不再增加
                gsEcSet(p, GS_EC_MAX, now + GS_EC_FULL_WIN, 1);
                gsEcBarShow(p, gsEcBloodText(GS_EC_MAX), " 右键释放“审判”", GS_EC_BLOOD_COL, 1.0);
                return;
            }
            int n = st.n + 1;
            if (n >= GS_EC_MAX) {
                gsEcSet(p, GS_EC_MAX, now + GS_EC_FULL_WIN, 1);
                gsEcBarShow(p, gsEcBloodText(GS_EC_MAX), " 右键释放“审判”", GS_EC_BLOOD_COL, 1.0);
                ListenerCore.gsMsSoundAt(p, "block.beacon.activate", 0.9f, 1.2f);
            } else {
                gsEcSet(p, n, now + GS_EC_WINDOW, 0);
                gsEcBarShow(p, gsEcBloodText(n), null, GS_EC_BLOOD_COL, 1.0);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEcPlayerTick(p)} —— 每拍维护「血」血条
     * （审判进行中时跳过 —— 那时血条被白色审判倒计时占用）。
     *
     * <p>★ 由月镰的心跳调用（见类注释）。
     */
    void gsEcPlayerTick(Player p) {
        try {
            String u = "";
            try {
                u = String.valueOf(p.getUniqueId());
            } catch (Throwable e) {
                return;
            }
            if (Boolean.TRUE.equals(GS_EC_JUDGING.get(u))) {
                return;
            }
            St st = gsEcGet(p);
            if (st.n <= 0) {
                gsEcBarHide(p);
                return;
            }
            double total = (st.full == 1) ? GS_EC_FULL_WIN : GS_EC_WINDOW;
            long now = ListenerCore.now();
            if (st.until <= 0) {                       // 自愈：层数在、计时丢了
                gsEcSet(p, st.n, now + total, st.full);
                gsEcBarShow(p, gsEcBloodText(st.n),
                        (st.full == 1) ? " 右键释放“审判”" : null, GS_EC_BLOOD_COL, 1.0);
                return;
            }
            long left = st.until - now;
            if (left <= 0) {
                gsEcClear(p);
                ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 0.6f, 1.4f);
                return;
            }
            gsEcBarShow(p, gsEcBloodText(st.n),
                    (st.full == 1) ? " 右键释放“审判”" : null,
                    GS_EC_BLOOD_COL, (total > 0) ? (left / total) : 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEcBarsClean()} —— 清扫已下线玩家的血条。 */
    void gsEcBarsClean() {
        ListenerCore.gsBarTblClean(GS_EC_BARS);
    }

    // ======================================================================
    // 审判（原脚本 7409~7609 行）
    // ======================================================================

    /** {@code gsEcDrawField(world, cx, cy, cz)} —— 三圈同心圆 + 8 条旋转辐条 + 中心白。 */
    void gsEcDrawField(World world, double cx, double cy, double cz) {
        try {
            Particle dust = core.gsMsDustType();
            if (dust == null) {
                return;
            }
            Particle.DustOptions dr = ListenerCore.gsMsDustOpts(
                    GS_EC_J_COL_R[0], GS_EC_J_COL_R[1], GS_EC_J_COL_R[2], 1.2);
            Particle.DustOptions dp = ListenerCore.gsMsDustOpts(
                    GS_EC_J_COL_P[0], GS_EC_J_COL_P[1], GS_EC_J_COL_P[2], 1.2);
            Particle.DustOptions dw = ListenerCore.gsMsDustOpts(
                    GS_EC_J_COL_W[0], GS_EC_J_COL_W[1], GS_EC_J_COL_W[2], 1.0);
            double y = cy + 0.15;
            // 三圈同心圆：外红 / 中紫 / 内白
            gsEcRingAt(world, dust, dr, cx, y, cz, GS_EC_J_R, 0.8);
            gsEcRingAt(world, dust, dp, cx, y, cz, GS_EC_J_R * 0.68, 0.7);
            gsEcRingAt(world, dust, dw, cx, y, cz, GS_EC_J_R * 0.36, 0.6);
            // 8 条缓缓旋转的紫色辐条
            double a0 = ((ListenerCore.now() % 4000) / 4000.0) * Math.PI * 2.0;
            for (int k = 0; k < 8; k++) {
                double a = a0 + (Math.PI * 2.0 * k) / 8.0;
                double ux = Math.cos(a);
                double uz = Math.sin(a);
                for (double rr = 1.2; rr <= GS_EC_J_R + 0.01; rr += 1.0) {
                    ListenerCore.gsMsDust(world, dust, dp, cx + ux * rr, y, cz + uz * rr);
                }
            }
            // 中心一簇白
            ListenerCore.gsMsDust(world, dust, dw, cx, y + 0.25, cz);
            ListenerCore.gsMsDust(world, dust, dw, cx, y + 0.65, cz);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEcFieldLock(p, world, cx, cy, cz)} —— 阵内生物全部定身
     * （短定身 800 毫秒 + 每 2 刻刷新 ⇒ 全程钉住；不画柱，视觉交给法阵）。
     */
    void gsEcFieldLock(Player p, World world, double cx, double cy, double cz) {
        try {
            String selfId = ListenerCore.uid(p);
            Location box = ListenerCore.gsMsLoc(world, cx, cy + 1.0, cz);
            if (box == null) {
                return;
            }
            Collection<Entity> list = null;
            try {
                list = world.getNearbyEntities(box, GS_EC_J_R, 20.0, GS_EC_J_R);
            } catch (Throwable e) {
                list = null;
            }
            if (list == null) {
                return;
            }
            Iterator<Entity> it = list.iterator();
            while (it.hasNext()) {
                Entity ent = it.next();
                if (!gsEcCanHit(ent, selfId)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double hx = el.getX() - cx;
                double hz = el.getZ() - cz;
                if (Math.sqrt(hx * hx + hz * hz) > GS_EC_J_R) {
                    continue;
                }
                core.gsMsLockFor(ent, 800, null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 天降圆柱粒子的层高序列（原脚本 7480 行 {@code h = 16.0; h >= -0.2; h -= 0.7}）。 */
    static double[] gsEcSkyHeights() {
        List<Double> out = new ArrayList<>();
        for (double h = 16.0; h >= -0.2; h -= 0.7) {
            out.add(h);
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    /**
     * {@code gsEcSky(victim, x, y, z, delay)} —— 天降圆柱粒子 → 233 点伤害。
     *
     * <p>★ 圆柱除原来的「星星」外，再叠一圈<b>紫 / 蓝 / 红</b>三色尘埃（逐层轮换）。
     */
    void gsEcSky(Entity victim, double x, double y, double z, long delay) {
        GS.runLater(new Runnable() {
            @Override
            public void run() {
                try {
                    World world = null;
                    try {
                        world = victim.getWorld();
                    } catch (Throwable e) {
                        world = null;
                    }
                    if (world == null) {
                        return;
                    }
                    Particle pt = core.gsMsStarType();
                    // ★ 三色尘埃（紫 / 蓝 / 红）——每层轮换一种颜色
                    Particle dust = core.gsMsDustType();
                    Particle.DustOptions[] dcols = null;
                    if (dust != null) {
                        dcols = new Particle.DustOptions[]{
                                ListenerCore.gsMsDustOpts(GS_EC_J_COL_P[0], GS_EC_J_COL_P[1], GS_EC_J_COL_P[2], 1.2),
                                ListenerCore.gsMsDustOpts(GS_EC_J_COL_B[0], GS_EC_J_COL_B[1], GS_EC_J_COL_B[2], 1.2),
                                ListenerCore.gsMsDustOpts(GS_EC_J_COL_R[0], GS_EC_J_COL_R[1], GS_EC_J_COL_R[2], 1.2)
                        };
                    }
                    // 圆柱：半径 1，从上方 16 格一路降下来
                    int lv = 0;
                    for (double h : gsEcSkyHeights()) {
                        if (pt != null) {
                            for (int k = 0; k < 5; k++) {
                                double a = (Math.PI * 2.0 * k) / 5.0;
                                try {
                                    world.spawnParticle(pt, x + Math.cos(a) * 1.0, y + h,
                                            z + Math.sin(a) * 1.0, 1, 0.05, 0.05, 0.05, 0.0);
                                } catch (Throwable ignored) {
                                    // 与原脚本一致：静默
                                }
                            }
                        }
                        if (dcols != null) {           // ★ 紫/蓝/红三色尘埃，随层号轮换 + 轻微旋转
                            for (int k3 = 0; k3 < 3; k3++) {
                                double a3 = (Math.PI * 2.0 * k3) / 3.0 + lv * 0.42;
                                ListenerCore.gsMsDust(world, dust, dcols[(k3 + lv) % 3],
                                        x + Math.cos(a3) * 1.0, y + h, z + Math.sin(a3) * 1.0);
                            }
                        }
                        lv++;
                    }
                    // 落地闪光一圈（星 + 三色尘埃）
                    for (int k2 = 0; k2 < 14; k2++) {
                        double a2 = (Math.PI * 2.0 * k2) / 14.0;
                        if (pt != null) {
                            try {
                                world.spawnParticle(pt, x + Math.cos(a2) * 1.7, y + 0.6,
                                        z + Math.sin(a2) * 1.7, 1, 0.0, 0.0, 0.0, 0.0);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                        if (dcols != null) {
                            ListenerCore.gsMsDust(world, dust, dcols[k2 % 3],
                                    x + Math.cos(a2) * 1.7, y + 0.6, z + Math.sin(a2) * 1.7);
                        }
                    }
                    try {
                        Location l = ListenerCore.gsMsLoc(world, x, y, z);
                        if (l != null) {
                            world.playSound(l, "entity.lightning_bolt.impact", 1.0f, 1.2f);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    gsEcHurtNoKb(victim, GS_EC_J_DMG, null);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        }, delay);
    }

    /**
     * {@code gsEcSmite(p, world, cx, cy, cz)} —— 蓄力结束时把阵内所有生物砸一遍。
     *
     * <p>★ 安全阀 {@code GS_EC_J_MAX_TARGETS}：{@code n++} <b>之后</b>才判断
     * {@code n > 60} 就 break ⇒ 实际最多处理 <b>61</b> 个目标（照抄，别"修"）。
     * 每根光柱错开 2 毫秒（{@code n * 2}）⇒ 摊在 ~120 毫秒里，不会一 tick 全爆。
     */
    void gsEcSmite(Player p, World world, double cx, double cy, double cz) {
        try {
            String selfId = ListenerCore.uid(p);
            Location box = ListenerCore.gsMsLoc(world, cx, cy + 1.0, cz);
            if (box == null) {
                return;
            }
            Collection<Entity> list = null;
            try {
                list = world.getNearbyEntities(box, GS_EC_J_R, 20.0, GS_EC_J_R);
            } catch (Throwable e) {
                list = null;
            }
            if (list == null) {
                return;
            }
            List<Entity> targets = new ArrayList<>();
            Iterator<Entity> it = list.iterator();
            while (it.hasNext()) {
                Entity ent = it.next();
                if (!gsEcCanHit(ent, selfId)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double hx = el.getX() - cx;
                double hz = el.getZ() - cz;
                if (Math.sqrt(hx * hx + hz * hz) > GS_EC_J_R) {
                    continue;
                }
                targets.add(ent);
            }
            int n = 0;
            for (Entity t : targets) {
                Location tl;
                try {
                    tl = t.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                gsEcSky(t, tl.getX(), tl.getY(), tl.getZ(), n * 2L);
                n++;
                if (n > GS_EC_J_MAX_TARGETS) {
                    break;                             // ★ 第七十批：安全阀
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ★ **视角锁定已删除**（原 gsEcLockView）——审判期间玩家可自由转视角
    // ★ **「无敌保持到落地」已删除**（原 gsEcInvulnUntilLand）——
    //   改为**固定 1.8 秒无敌**（GS_EC_J_INV，与蓄力同步），在 gsEcJudge 里读条结束时一并解除

    /**
     * {@code gsEcField(p, world, cx, cy, cz, ticksLeft, lock)} —— 法阵主循环：
     * 每 2 刻画一次，同时刷新定身 + 白色血条<b>蓄力读条</b>。
     *
     * <p>★ 标题<b>只显示「审判」二字</b>（不再带秒数）；读条长度仍按剩余时间走。
     * ★ {@code runLater} 在 try/catch <b>之外</b> ⇒ 即使这一拍抛错，链条也继续。
     */
    void gsEcField(Player p, World world, double cx, double cy, double cz, int ticksLeft, boolean lock) {
        try {
            if (ticksLeft <= 0) {
                return;
            }
            gsEcDrawField(world, cx, cy, cz);
            if (lock) {
                gsEcFieldLock(p, world, cx, cy, cz);
            }
            try {
                gsEcBarShow(p, "审判", null, "WHITE", ticksLeft / (double) GS_EC_J_TICKS);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        GS.runLater(() -> gsEcField(p, world, cx, cy, cz, ticksLeft - 2, lock), 2);
    }

    /** {@code gsEcJudge(p)} —— 满 23 层右键：审判。 */
    boolean gsEcJudge(Player p) {
        try {
            St st = gsEcGet(p);
            if (st.n < GS_EC_MAX) {
                return false;                          // 未满 23 层 ⇒ 右键不做任何事
            }
            final String u = ListenerCore.uid(p);
            if (Boolean.TRUE.equals(GS_EC_JUDGING.get(u))) {
                return true;                           // 审判进行中，忽略重复
            }
            gsEcClear(p);                              // 先清空「血」

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return true;
            }
            final World world = p.getWorld();
            final double cx = loc.getX();
            final double cy = loc.getY();
            final double cz = loc.getZ();
            final Player fp = p;

            GS_EC_JUDGING.put(u, Boolean.TRUE);

            // ① ★ 立刻无敌（固定 1.8 秒；**不再漂浮、不再锁视角**）
            try {
                p.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                p.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 音效
            ListenerCore.gsMsSound(world, loc, "entity.wither.spawn", 1.4f, 0.7f);
            ListenerCore.gsMsSound(world, loc, "entity.ender_dragon.growl", 1.3f, 0.8f);

            // ③ 法阵：半径 8 / 蓄力 1.8 秒（内含定身 + 白色「审判」读条）
            gsEcField(p, world, cx, cy, cz, GS_EC_J_TICKS, true);

            // ④ ★ **蓄力满 1.8 秒才出伤**（天降 233 点），随后收尾
            GS.runLater(() -> {
                try {
                    gsEcSmite(fp, world, cx, cy, cz);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    GS_EC_JUDGING.put(u, Boolean.FALSE);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    gsEcBarHide(fp);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, GS_EC_J_TICKS);

            // ⑤ ★ 无敌固定 1.8 秒（GS_EC_J_INV，与蓄力同步）后解除
            GS.runLater(() -> {
                try {
                    fp.setInvulnerable(false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, GS_EC_J_INV);

            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 去抖（原脚本 7611~7629 行）
    // ======================================================================

    /** {@code gsEcClickOnce(p, tag)} —— 80 毫秒内同一键只处理一次。 */
    boolean gsEcClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_EC_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_EC_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsEcRecentRight(p, gapMs)} —— 刚刚处理过右键吗（右键附带的挥手要忽略）。 */
    boolean gsEcRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_EC_CLICK_AT.get(ListenerCore.uid(p) + "|R");
            if (last == null) {
                return false;
            }
            return (ListenerCore.now() - last) < gapMs;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 监听器（原脚本 7665~7749 行）—— 4 处 registerEvent 的 Java 版
    // ======================================================================

    /**
     * 原脚本 4 处 {@code registerEvent} 的 Java 版。
     * 全部 {@code Priority.HIGHEST}、{@code ignoreCancelled} 默认 <b>false</b>。
     */
    final class Sync implements Listener {

        /** ① 左键 = 蚀月轮；右键 = 满 23 层放审判（其余情况只取消原版交互）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsEcIs(gsEcHand(p))) {
                    return;
                }
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (!hs.isEmpty() && hs.contains("OFF")) {
                    return;                            // 只认主手
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }

                if (act.contains("RIGHT_CLICK")) {
                    // 取消右键交互（锄类不许耕地 / 不许开箱子）
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
                    if (!gsEcClickOnce(p, "R")) {
                        return;
                    }
                    gsEcJudge(p);                      // 未满 23 层时内部直接返回
                } else if (act.contains("LEFT_CLICK")) {
                    if (!gsEcClickOnce(p, "L")) {
                        return;
                    }
                    if (GS_EC_NO_BREAK) {
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
                    gsEcWheel(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ② 左键兜底：挥手动画（与 ① 共用去抖 ⇒ 不会放两次）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsEcIs(gsEcHand(p))) {
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
                if (gsEcRecentRight(p, 300)) {
                    return;                            // 右键附带的挥手 → 忽略
                }
                if (!gsEcClickOnce(p, "L")) {
                    return;
                }
                gsEcWheel(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ 手持蚀月 → 挖不动方块（双保险）。
         *
         * <p>★ 原脚本这两处 {@code reg(...)} 被包在 {@code if (GS_EC_NO_BREAK)} 里
         * （第 7729 / 7750 行）—— 关掉开关时<b>连监听器都不注册</b>。
         * Java 的 {@code @EventHandler} 是编译期注册、没法条件注册 ⇒
         * 把那个开关搬进<b>处理体第一行</b>（{@code GS_EC_NO_BREAK} 是
         * {@code static final true}，行为逐字等价）。这是<b>结构性差异</b>，
         * 已记在类注释里。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!GS_EC_NO_BREAK) {
                    return;                            // ← 原脚本的注册期开关
                }
                if (!gsEcIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 之二：{@code BlockDamageEvent}（同上，注册期开关搬进处理体）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockDamage(BlockDamageEvent e) {
            try {
                if (!GS_EC_NO_BREAK) {
                    return;                            // ← 原脚本的注册期开关
                }
                if (!gsEcIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code gsEcRegisterSync()} —— 注册同步监听器（<b>幂等</b>）。 */
    boolean gsEcRegisterSync() {
        if (GS_EC_REGISTERED) {
            return true;
        }
        try {
            if (listener == null) {
                listener = new Sync();
            }
            Bukkit.getPluginManager().registerEvents(listener, com.qinghuacisj.gs.GSPlugin.instance());
            GS_EC_REGISTERED = true;
            try {
                Bukkit.getLogger().info(gsEcRegisterLine());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning(ListenerCore.PREFIX + "蚀月监听器注册异常: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    // ======================================================================
    // 日志行（抽成静态方法，只为让探针逐字节对账）
    // ======================================================================

    /** 原脚本 7753 行的注册行（<b>含</b>前缀）。 */
    static String gsEcRegisterLine() {
        return ListenerCore.PREFIX + "蚀月·死神梦镰监听器注册成功（左键蚀月轮叠血 / 满 23 层右键审判）";
    }

    /**
     * 原脚本 7767~7770 行的状态行（<b>不含</b>前缀）。
     *
     * <p>★ {@code pdcOk} 是脚本自己探测的结果，<b>必须保留这个分支</b>。
     */
    static String gsEcStatusMsg(boolean pdcOk) {
        return "蚀月状态：层数存内存（权威）· PDC 备份 " + (pdcOk ? "可用" : "不可用");
    }

    /** 带前缀的完整状态行。 */
    static String gsEcStatusLine(boolean pdcOk) {
        return ListenerCore.PREFIX + gsEcStatusMsg(pdcOk);
    }

    /** {@code pdcOk} 探测（原脚本 7768 行）。 */
    boolean gsEcPdcOk() {
        return (GS_EC_PDT != null) && (GS_EC_K_BLOOD != null)
                && (GS_EC_K_UNTIL != null) && (GS_EC_K_FULL != null);
    }

    /**
     * 原脚本 7772~7782 行的加载行（<b>不含</b>前缀）。
     *
     * <p>★ 全部数值走 {@link MsSub#jsNum}（JS 的 {@code Number::toString}）；
     * {@code GS_EC_W_PULSE / 1000} 之类是 JS 的<b>浮点</b>除法 ⇒ Java 写 {@code / 1000.0}。
     * {@code (GS_EC_SW_HIT ? '开' : '关')} 是三元表达式 ⇒ Java 用三元。
     */
    static String gsEcLoadMsg() {
        return "蚀月·死神梦镰（" + GS_EC_ID + "）已加载 · 蚀月轮 "
                + MsSub.jsNum(GS_EC_W_DMG) + " 点 / 每 " + MsSub.jsNum(GS_EC_W_PULSE / 1000.0) + " 秒 1 次 / 射程 "
                + MsSub.jsNum(GS_EC_W_RANGE) + " 格 / 每刻 " + MsSub.jsNum(GS_EC_W_STEP) + " 格 / 落地后留 "
                + MsSub.jsNum(GS_EC_W_LIFE / 1000.0) + " 秒 / 定身 "
                + MsSub.jsNum(GS_EC_W_LOCK / 1000.0) + " 秒（同目标间隔 " + MsSub.jsNum(GS_EC_W_LOCK_CD / 1000.0)
                + " 秒）/ 冷却 " + MsSub.jsNum(GS_EC_W_CD / 1000.0)
                + " 秒 / 普通左键叠层 " + (GS_EC_SW_HIT ? "开" : "关")
                + " / 满 " + GS_EC_MAX + " 层 · 审判 " + MsSub.jsNum(GS_EC_J_DMG) + " 点 / 半径 " + MsSub.jsNum(GS_EC_J_R)
                + " 格 / 蓄力 " + MsSub.jsNum(GS_EC_J_TICKS / 20.0) + " 秒后出伤 / 无敌 " + MsSub.jsNum(GS_EC_J_INV / 20.0)
                + " 秒 / 不锁视角 / 无漂浮 / 圆柱粒子 紫蓝红";
    }

    /** 带前缀的完整加载行。 */
    static String gsEcLoadLine() {
        return ListenerCore.PREFIX + gsEcLoadMsg();
    }

    // ======================================================================
    // 入口（= 原脚本顶层 gsEcStart(); 第 7789 行）
    // ======================================================================

    /**
     * {@code gsEcStart()} —— 注册监听器 + 打加载日志。
     *
     * <p>★ <b>没有</b> {@code runRepeating}：蚀月的血条维护由月镰的心跳驱动（见类注释）。
     */
    void gsEcStart() {
        try {
            if (!gsEcRegisterSync()) {
                try {
                    Bukkit.getLogger().warning(ListenerCore.PREFIX + "蚀月启动失败：监听器没注册上");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            try {
                ListenerCore.gsDaLog(gsEcStatusMsg(gsEcPdcOk()));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ListenerCore.gsDaLog(gsEcLoadMsg());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning(ListenerCore.PREFIX + "蚀月启动异常: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }
}
