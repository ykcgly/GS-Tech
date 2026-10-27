package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.SkeletonHorse;
import org.bukkit.entity.Tameable;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code gs_listener.js} 的「五骊矟（五骊骑阵）」子系统（原脚本第 2811~4833 行）。
 *
 * <p><b>玩法</b>：主手金剑「五骊矟」（{@code GS_WULI_SPEAR}）+ 副手拴绳「五骊鞚」= 已开机。
 * <ul>
 *   <li><b>F</b>（换手事件）= 上马 / 收阵。第一次按 → 正前方 7 格冲出一匹骷髅马，
 *       1.5 秒内撞到玩家（0.9 格内判定）→ 副手出现「五骊鞚」+ 扶上马；
 *       再按 → 播下马动画（4 匹护航马化烟 + 队长马朝前方 5 格奔出后化烟）。</li>
 *   <li><b>左键</b> = 剑气（7 点 / 0.5 秒冷却 / <b>完全静默</b> / 末影龙飞行 + 末影人传送音）。</li>
 *   <li><b>右键</b> = 冲锋（蓄力 1 秒<b>无敌</b> → 冲刺 15 格 / 40 刻动画 / 整段 3.0 秒 /
 *       冷却 10 秒 / 路径 15 点伤害 / 冲刺期间自动斩 4 道剑气且<b>无击退</b>）。</li>
 * </ul>
 *
 * <p>★★ <b>本类里几处不能"顺手改"的硬要求</b>：
 * <ol>
 *   <li><b>蓄力无敌 = true · 冲刺无敌 = false</b> —— 这个不对称是需求指定的。
 *       而且<b>收阵 / 下线 / 死亡 / 冲刺结束都会无条件解除</b>（{@code invulnerable}
 *       不会自己过期，漏一处就是永久免伤）。</li>
 *   <li><b>坐骑移速下限 0.28（只升不降）</b> —— 已经更快的马保持原速。</li>
 *   <li><b>开机动画的朝向对齐</b> —— 冲过来的那匹是"引路的马"，撞到后<b>直接删除</b>，
 *       3 刻后在玩家<b>原地</b>另生成一匹 yaw = 玩家 yaw 的新马。所以客户端
 *       "坐上坐骑就把镜头对齐坐骑"这一步对齐到的是玩家本来朝向 ⇒ 不掰镜头。
 *       ★ 骑乘中<b>绝不</b>用 {@code teleport} 纠正朝向（会把玩家锁死、画面发顿）。</li>
 *   <li><b>下马动画不碰玩家</b> —— 只动马，玩家的位置 / 朝向一个字节都不碰。</li>
 *   <li>冷却存<b>玩家 PDC</b>（{@code /rsc reload} 不丢）——
 *       {@link ListenerCore#gsWuliCdLeft} / {@link ListenerCore#gsWuliMarkCd}。</li>
 * </ol>
 *
 * <p>★★ <b>监听器注册（与原脚本不同的唯一一处，且是修缺陷）</b>：
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} <b>在脚本里自己注册</b>，
 * 而 {@code GS_WULI_REGISTERED} 闩锁是脚本级变量 ⇒ <b>每次 {@code /rsc reload}
 * 都会再注册一遍，监听器会累积</b>（脚本自己靠 250ms / 80ms 去抖与
 * {@code e.isCancelled()} 兜着）。Java 版改成
 * {@code org.bukkit.event.Listener} 实现类 + {@code registerEvents} <b>幂等注册一次</b>，
 * 可观察行为（收到的事件、优先级、取消）完全一致，只是不再累积。
 * <p>优先级与 {@code ignoreCancelled} 逐处核对：原脚本 {@code registerEvent} 只传了
 * 4 个参数（{@code eventClass, listener, Priority.HIGHEST, executor}），
 * <b>没有第 5 个参数 ⇒ ignoreCancelled 默认 false</b>；Java 侧
 * {@code @EventHandler(priority = EventPriority.HIGHEST)} 默认也是 false，<b>没有写 true</b>。
 *
 * <p>★ <b>注册粒度差异</b>：原脚本逐个事件 {@code reg()}，每条各自 try/catch
 * （一条失败其余照常）；Java 的 {@code registerEvents(一个 Listener, ...)} 是
 * <b>一次全成功 / 全失败</b>。本模块的 11 条监听器方法签名都是标准形式，
 * 不存在"某一条不合法"的情形，所以两者可观察行为相同。
 *
 * <p>★★ <b>与模块本体的耦合（必须由集成者保留）</b>：
 * 开机动画里冲过来引路的那匹马靠<b>记分板标签</b> {@link #WL_RIDE_TAG}
 * （{@code 'gs_ridein'}）判定「必须全程免伤」，判定点在
 * {@code gsOnEntityDamageSync}（原脚本 347~354 行）里，且
 * <b>必须在 {@code en !== 'PLAYER'} 那一行之前判</b>。
 * 本类只负责<b>导出这个标签常量</b>并往马身上打标签；
 * 伤害拦截那一段属于模块本体（{@code ListenerModule.onEntityDamage}）。
 */
public final class WuliSub {

    // ======================================================================
    // 物品 ID 与 PDC 键（原脚本 2825~2837 行）
    // ======================================================================

    /** {@code GS_WULI_ID} —— 五骊矟（金剑）。 */
    public static final String GS_WULI_ID = "GS_WULI_SPEAR";

    /** {@code GS_WULI_REIN_KEY} —— 副手拴绳「五骊鞚」的物品标记。 */
    public static final NamespacedKey GS_WULI_REIN_KEY = key("gs:wuli_rein");

    /** {@code GS_WULI_SUMMON_KEY} —— 召唤物标记（护腿光环靠它跳过）。 */
    public static final NamespacedKey GS_WULI_SUMMON_KEY = key("gs:summon");

    /** {@code GS_WULI_QI_KEY} —— 剑气冷却（玩家 PDC）。 */
    public static final NamespacedKey GS_WULI_QI_KEY = key("gs:wuli_qi");

    /** {@code GS_WULI_DASH_KEY} —— 冲锋冷却（玩家 PDC）。 */
    public static final NamespacedKey GS_WULI_DASH_KEY = key("gs:wuli_dash");

    /**
     * {@code GS_MAIYOU_KEY} —— 「麦友的酒」召唤物标记。
     *
     * <p>★ 它在原脚本里的定义点在 <b>1122~1123 行</b>（不在本子系统区间内），
     * 但 {@code gsIsMaiyouSummon} 被剑气 / 冲锋的目标筛选用到 ⇒ 本类自带一份。
     * {@code ListenerCore.gsIsMaiyouSummon(ent, key)} 需要这个 key 作参数，
     * 而 {@code ListenerCore} 里没有它 —— 已在交付说明里列给集成者。
     */
    public static final NamespacedKey GS_MAIYOU_KEY = key("gs:maiyou_fish");

    /** 原脚本的 {@code try { NamespacedKey.fromString(...) } catch (e) { } } 结构。 */
    private static NamespacedKey key(String s) {
        try {
            return NamespacedKey.fromString(s);
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 参数（原脚本 2840~2975 行）—— 名字必须与 JS 顶层 var 同名
    // ======================================================================

    /** {@code WL_REIN_NAME} —— 副手拴绳的名字。 */
    public static final String WL_REIN_NAME = "\u00a7e五骊鞚";

    /** {@code WL_REIN_LORE} —— 副手拴绳的 lore（★ 由 1 行改成 4 行）。 */
    public static final String[] WL_REIN_LORE = {
            "\u00a78五骊之缰 · 战魂所系",
            "\u00a77唯主手执 \u00a7f五骊矟 \u00a77时",
            "\u00a77才可驭使五骊马阵",
            "\u00a78“鞚在手，骊在侧”"
    };

    /** {@code WL_PULSE} —— 骑阵任务间隔（刻）；2 刻 = 0.1 秒。 */
    public static final int WL_PULSE = 2;

    /** {@code WL_HORSE_SPEED} —— 马的移速<b>只升不降</b>的下限（属性值，不是 m/s）。 */
    public static final double WL_HORSE_SPEED = 0.28;

    /** {@code WL_REAR_PITCH} —— 蓄力时"前蹄抬起"的姿势（pitch 仰角）。 */
    public static final double WL_REAR_PITCH = -42.0;

    /** {@code WL_FORM} —— 骑阵 ^ 队形：back = 身后几格，side = 偏离中线几格。 */
    public static final Form[] WL_FORM = {
            new Form(1.5, -1.6),   // 中间左
            new Form(1.5, 1.6),    // 中间右
            new Form(3.0, -3.2),   // 最外侧左
            new Form(3.0, 3.2)     // 最外侧右
    };

    // ---- 剑气（左键）----
    public static final int WL_QI_CD_MS = 500;
    public static final String WL_QI_SND1 = "entity.ender_dragon.flap";
    public static final String WL_QI_SND2 = "entity.enderman.teleport";
    public static final double WL_QI_SND1_VOL = 0.8;
    public static final double WL_QI_SND1_PIT = 1.4;
    public static final double WL_QI_SND2_VOL = 1.0;
    public static final double WL_QI_SND2_PIT = 1.0;
    public static final double WL_QI_DMG = 7.0;
    public static final double WL_QI_RANGE = 10.0;
    public static final double WL_QI_HALF_W = 2.5;
    public static final double WL_QI_HALF_T = 0.75;
    public static final double WL_QI_HALF_H = 1.5;
    public static final double WL_QI_STEP = 0.5;
    public static final double WL_QI_KB = 0.35;
    public static final double WL_QI_KB_UP = 0.12;
    public static final boolean WL_QI_NO_BLOCK = true;

    // ---- 冲锋（右键）----
    public static final int WL_DASH_CD_MS = 10000;
    public static final int WL_CHARGE_MS = 1000;
    public static final double WL_DASH_DIST = 15.0;
    public static final int WL_DASH_TICKS = 40;
    public static final boolean WL_DASH_LOCK_LOOK = false;
    public static final double WL_LOOK_EPS = 0.5;
    public static final boolean WL_CHARGE_INVULN = true;
    public static final boolean WL_DASH_INVULN = false;
    public static final double WL_DASH_DMG = 15.0;
    public static final double WL_DASH_HALF_W = 2.5;
    public static final double WL_DASH_HALF_H = 2.0;
    public static final int WL_DASH_STEP_UP = 1;

    // ---- 蓄力时头顶的粒子马 ----
    public static final boolean WL_FXHORSE_ON = true;
    public static final double WL_FXHORSE_Y = 2.0;
    public static final double WL_FXHORSE_FRONT = 0.9;
    public static final double WL_FXHORSE_STEP = 0.11;
    public static final int WL_FXHORSE_EVERY = 1;
    public static final String[] WL_FXHORSE_PARTICLES =
            {"CRIT", "ENCHANTED_HIT", "END_ROD", "FIREWORK_SPARK"};
    public static final String[] WL_FXHORSE_EYE = {"FLAME", "FIREWORK", "CLOUD"};

    // ---- 下马动画 ----
    public static final int WL_DISSMOKE_TICKS = 8;
    public static final double WL_DISSMOKE_UP = 0.42;
    public static final double WL_FLEE_DIST = 5.0;
    public static final int WL_FLEE_TICKS = 10;
    public static final boolean WL_FLEE_SMOKE = true;
    public static final int WL_FLEE_REAR_TICKS = 10;
    public static final boolean WL_FLEE_REAR_KEEP = true;
    public static final String[] WL_SMOKE_PARTICLES =
            {"SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL", "SMOKE", "CLOUD"};
    public static final String[] WL_SMOKE_SNDS =
            {"entity.horse.gallop", "entity.generic.explode", "ENTITY_HORSE_GALLOP"};
    public static final double WL_SMOKE_SND_VOL = 0.6;
    public static final double WL_SMOKE_SND_PIT = 1.6;

    // ---- F 键开机动画 ----
    /**
     * {@code WL_RIDE_TAG} —— ★ 记分板标签：冲过来引路的那匹马身上有它
     * ⇒ {@code gsOnEntityDamageSync} 的伤害拦截器一律放行保护。
     *
     * <p>★★ <b>耦合点</b>：判定在模块本体的 {@code onEntityDamage} 里，
     * 且<b>必须在 {@code en !== 'PLAYER'} 那一行之前判</b>（原脚本 347~354 行）。
     */
    public static final String WL_RIDE_TAG = "gs_ridein";

    public static final double WL_RIDE_DIST = 7.0;
    public static final int WL_RIDE_TICKS = 30;
    public static final double WL_RIDE_HIT_R = 0.9;
    public static final double WL_RIDE_REAR_R = 3.2;
    public static final double WL_RIDE_EASE = 1.7;
    public static final String[] WL_RIDE_SND = {"entity.horse.gallop", "ENTITY_HORSE_GALLOP"};
    public static final int WL_RIDE_SWAP_TICKS = 3;
    public static final boolean WL_RIDE_SPAWN_FX = true;
    public static final int WL_RIDE_SND_EVERY = 4;
    public static final double WL_RIDE_SND_VOL = 0.9;
    public static final double WL_RIDE_SND_PIT = 1.15;
    public static final String[] WL_RIDE_PARTICLE = {"CLOUD", "SMOKE_NORMAL", "SPELL", "FLAME"};

    // ---- 冲刺期间自动斩出的 4 道剑气 ----
    public static final boolean WL_DASH_QI_ON = true;
    public static final int[] WL_DASH_QI_AT = {0, 6, 12, 18};
    public static final boolean WL_DASH_QI_SOUND = true;
    public static final double WL_DASH_QI_KB_MUL = 0.0;

    public static final String WL_MSG_USE = "&6冲锋！";
    public static final String WL_MSG_CD = "&7奔袭调息，静待时机。";

    /** {@code WL_FORM[i]} 的一条。 */
    public static final class Form {
        public final double back;
        public final double side;

        Form(double back, double side) {
            this.back = back;
            this.side = side;
        }
    }

    /**
     * ★ 一匹"抬起前蹄"的马的骨架线段表：每项 = {@code [u1, v1, u2, v2]}
     * （原脚本 {@code wlFxHorseSegs()}，4167~4199 行）。
     */
    public static final double[][] WL_FXHORSE_SEGS = {
            // ① 后腿（两条，站在地上）
            {-0.52, 0.00, -0.34, 0.46},
            {-0.40, 0.00, -0.24, 0.44},
            // ② 臀 → 背 → 鬐甲（微微上扬的背线）
            {-0.34, 0.46, -0.30, 0.72},
            {-0.30, 0.72, 0.06, 0.94},
            {0.06, 0.94, 0.40, 1.12},
            // ③ 肚子
            {-0.30, 0.60, 0.06, 0.66},
            {0.06, 0.66, 0.36, 0.86},
            // ④ 脖子（斜向上）
            {0.40, 1.12, 0.66, 1.42},
            // ⑤ 头 + 嘴
            {0.66, 1.42, 0.92, 1.50},
            {0.92, 1.50, 1.06, 1.44},
            // ⑥ 耳朵
            {0.70, 1.46, 0.72, 1.60},
            // ⑦ ★ 前腿（两条，**抬起来**、蹄朝上）
            {0.36, 1.02, 0.56, 0.88},
            {0.56, 0.88, 0.66, 1.14},
            {0.66, 1.14, 0.78, 1.38},
            {0.28, 0.96, 0.46, 0.82},
            {0.46, 0.82, 0.56, 1.06},
            // ⑧ 尾巴（垂下来再翘一点）
            {-0.30, 0.72, -0.58, 0.54},
            {-0.58, 0.54, -0.74, 0.30},
            // ⑨ 鬃毛（几道短线）
            {0.42, 1.16, 0.34, 1.26},
            {0.54, 1.30, 0.46, 1.40}
    };

    // ======================================================================
    // 运行期状态（原脚本 2977~2990 行）—— 非静态：实例 = 原脚本的一次顶层求值
    // ======================================================================

    /** {@code GS_WULI_STATE} —— uuid → 骑阵状态。 */
    private final Map<String, St> GS_WULI_STATE = new HashMap<>();

    /** {@code GS_WULI_TOGGLE_AT} —— uuid → 上次开关时间戳（250ms 去抖）。 */
    private final Map<String, Long> GS_WULI_TOGGLE_AT = new HashMap<>();

    /** {@code GS_WULI_SUMMONING} —— uuid → 1 = "马冲过来"演出正在播。 */
    private final Map<String, Integer> GS_WULI_SUMMONING = new HashMap<>();

    /** {@code GS_WULI_RIDE} —— uuid → 冲过来的那匹马的状态。 */
    private final Map<String, Ride> GS_WULI_RIDE = new HashMap<>();

    /**
     * {@code GS_WULI_DAMAGING} —— ★★「本脚本自己造成的伤害」白名单。
     *
     * <p>极其重要：手持五骊矟时监听器会把"玩家近战"取消掉、换成剑气；
     * 而剑气 / 冲锋 / 梦境护腿光环都是脚本自己调 {@code ent.damage(x, p)} 打出来的，
     * damager 同样是这个玩家 ⇒ 不打招呼的话会被自己的监听器当成"近战"取消，
     * 伤害全部落空。所以每次自己结算伤害前先在这里打个勾，监听器看到勾就放行。
     */
    private final Map<String, Boolean> GS_WULI_DAMAGING = new HashMap<>();

    /** {@code GS_WULI_REGISTERED} —— 监听器闩锁（Java 里等价于"只注册一次"）。 */
    private boolean GS_WULI_REGISTERED;

    private final ListenerCore core;

    public WuliSub(ListenerCore core) {
        this.core = core;
    }

    // ======================================================================
    // 骑阵状态载体
    // ======================================================================

    /** 原脚本 {@code st = { lead, escorts, stashed, mode, ... }}（3534~3535 行）。 */
    private static final class St {
        SkeletonHorse lead;
        List<SkeletonHorse> escorts =
                new ArrayList<>(Collections.nCopies(WL_FORM.length, (SkeletonHorse) null));
        /**
         * ★ 原脚本的死代码：{@code st.stashed} <b>从未被赋过非 null 值</b>
         * （全脚本只有 {@code gsWuliSummon} 的 {@code stashed: null} 与
         * {@code gsWuliDismiss} 的 {@code st.stashed = null}），所以
         * {@code if (restore && p != null && st.stashed != null) gsScopeStash(p, st.stashed);}
         * 这一支永远走不到 ⇒ 这里<b>不照抄</b>该分支，只保留字段与置空语义。
         */
        ItemStack stashed;
        String mode = "follow";
        boolean dead;
        double heading;
        double fxTick;

        // 蓄力 / 冲刺期才写入的字段（JS 里是动态加属性）
        double anchorX, anchorY, anchorZ, anchorYaw;
        long chargeEnd;
        double lockYaw, lockPitch;
        double dirX, dirZ;
        double dashX0, dashY0, dashZ0;
        int dashI;
        Map<String, Integer> hit = new HashMap<>();

        /**
         * ★★ {@code st.chargeFx} 在 JS 里<b>第一次是 undefined</b>
         * （{@code gsWuliTryCharge} 只设了 {@code st.fxTick = 0}），
         * 而 {@code undefined % 1} = {@code NaN}，{@code NaN === 0} 为 <b>false</b>
         * ⇒ <b>蓄力的第一刻不画头顶的粒子马</b>。所以这里必须是可空的 {@link Integer}。
         */
        Integer chargeFx;
    }

    /** 原脚本 {@code c = { h, sx, sy, sz, i, done }}（3713~3717 行）。 */
    private static final class Ride {
        SkeletonHorse h;
        double sx, sy, sz;
        int i;
        boolean done;
    }

    /** 原脚本 {@code { x, y, z }} 这种点（剑气基点）。 */
    private static final class Pt {
        double x, y, z;
    }

    /** 原脚本 {@code wlAxes(dir)} 返回的正交基。 */
    static final class Axes {
        double lx, ly, lz, ux, uy, uz;
    }

    // ======================================================================
    // 顶层入口（对应原脚本第 4833 行的 {@code gsWuliStart();}）
    // ======================================================================

    /** 对应原脚本 {@code gsWuliStart()}（4810~4831 行）。 */
    public void start() {
        try {
            GS.runRepeating(this::gsWuliPulse, WL_PULSE, WL_PULSE);
            gsWuliRegisterSync();
            ListenerCore.gsDaLog(msgEnabled());
            ListenerCore.gsDaLog(msgSpeedFloor());
            ListenerCore.gsDaLog(msgInvuln());
            ListenerCore.gsDaLog(msgRideAnim());
            ListenerCore.gsDaLog(msgDismissAnim());
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 五骊矟启动失败: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 加载日志行（供探针逐字节对账）
    // ======================================================================

    /** {@code [构思技艺] 五骊矟监听器注册成功（F=上马/收阵 · 左键=剑气 · 右键=冲锋）} */
    public static String registerLine() {
        return ListenerCore.PREFIX + "五骊矟监听器注册成功（F=上马/收阵 · 左键=剑气 · 右键=冲锋）";
    }

    /** 原脚本 4814~4819 行的日志正文（不含前缀）。 */
    public static String msgEnabled() {
        return "五骊矟：五骊骑阵已启用（F = 上马/收阵；左键剑气 7 点·"
                + jsNum(WL_QI_CD_MS / 1000.0) + " 秒·静默·龙吟+传送音；"
                + "右键冲锋 15 点·蓄力 " + jsNum(WL_CHARGE_MS / 1000.0) + " 秒（无敌）·冲刺 "
                + jsNum(WL_DASH_DIST) + " 格（" + jsNum(WL_DASH_TICKS / 20.0) + " 秒动画，整段 "
                + toFixed1((WL_CHARGE_MS / 1000.0) + (WL_DASH_TICKS / 20.0)) + " 秒）·冷却 "
                + jsNum(WL_DASH_CD_MS / 1000.0) + " 秒）";
    }

    /** 原脚本 4820 行的日志正文。 */
    public static String msgSpeedFloor() {
        return "五骊矟：坐骑移速下限 " + jsNum(WL_HORSE_SPEED) + "（≈ 12 格/秒；只升不降）";
    }

    /** 原脚本 4821~4822 行的日志正文。 */
    public static String msgInvuln() {
        return "五骊矟：蓄力无敌 = " + WL_CHARGE_INVULN + " · 冲刺无敌 = " + WL_DASH_INVULN
                + "（收阵/下线/死亡/冲刺结束都会无条件解除）";
    }

    /** 原脚本 4823~4825 行的日志正文。 */
    public static String msgRideAnim() {
        return "五骊矟：开机动画 = **正前方** " + jsNum(WL_RIDE_DIST) + " 格冲出一匹骷髅马，"
                + jsNum(WL_RIDE_TICKS / 20.0) + " 秒内撞到玩家（" + jsNum(WL_RIDE_HIT_R)
                + " 格内判定）→ 副手出现「五骊鞚」+ 扶上马；坐骑朝向已对齐玩家视角，不会掰镜头";
    }

    /** 原脚本 4826~4827 行的日志正文。 */
    public static String msgDismissAnim() {
        return "五骊矟：下马动画 = 4 匹护航马化烟 + 队长马朝前方 " + jsNum(WL_FLEE_DIST)
                + " 格奔出后化烟（" + jsNum(WL_FLEE_TICKS / 20.0) + " 秒）；玩家全程可自由移动";
    }

    /**
     * JS 的 {@code '' + 数值} 语义：整数<b>不带</b> {@code .0}。
     *
     * <p>原脚本这些日志行全是<b>字符串拼接</b>而不是参数化日志，所以
     * {@code 15.0} 拼出来是 {@code "15"}、{@code 0.5} 拼出来是 {@code "0.5"}。
     */
    static String jsNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e21) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** JS 的 {@code Number.prototype.toFixed(1)}（本处输入恒为 3.0）。 */
    static String toFixed1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ======================================================================
    // 工具（原脚本 2992~3001 行）
    // ======================================================================

    /** {@code gsMarkScriptDamage(p, on)}。 */
    private void gsMarkScriptDamage(Player p, boolean on) {
        try {
            if (p == null) {
                return;
            }
            GS_WULI_DAMAGING.put(ListenerCore.uid(p), on);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 副手拴绳「五骊鞚」（原脚本 3025~3071 行）
    // ======================================================================

    /** {@code wlMakeRein()} —— 造一根「五骊鞚」。 */
    public static ItemStack wlMakeRein() {
        try {
            Material mat = Material.LEAD;
            ItemStack st = new ItemStack(mat, 1);
            ItemMeta meta;
            try {
                meta = st.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            if (meta == null) {
                return st;
            }

            try {
                meta.setDisplayName(WL_REIN_NAME);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                List<String> l = new ArrayList<>();
                // ★ WL_REIN_LORE 是数组，逐行写入
                for (String s : WL_REIN_LORE) {
                    l.add(s);
                }
                meta.setLore(l);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ 标记：靠它判定"手里这根是不是我们的拴绳"（也靠它拦丢弃 / 拖动 / 换手）
            try {
                if (GS_WULI_REIN_KEY != null) {
                    meta.getPersistentDataContainer().set(
                            GS_WULI_REIN_KEY, PersistentDataType.STRING, "1");
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // 双保险：这根绳子是运行中临时造的，顺手设永不损耗
            try {
                meta.setUnbreakable(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                st.setItemMeta(meta);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return st;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsWuliIsRein(item)} —— 手里这根是不是「五骊鞚」。 */
    public static boolean gsWuliIsRein(ItemStack item) {
        try {
            if (item == null || GS_WULI_REIN_KEY == null) {
                return false;
            }
            ItemMeta meta;
            try {
                meta = item.getItemMeta();
            } catch (Throwable e) {
                return false;
            }
            if (meta == null) {
                return false;
            }
            return meta.getPersistentDataContainer().has(GS_WULI_REIN_KEY, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 召唤物标记 / 移速 / 抬蹄 / 无敌（原脚本 3074~3135 行）
    // ======================================================================

    /** {@code gsIsOurSummon(ent)} —— 是不是本子系统召唤的马。 */
    private static boolean gsIsOurSummon(Entity ent) {
        return ListenerCore.gsIsOurSummon(ent, GS_WULI_SUMMON_KEY);
    }

    /** {@code wlSetHorseSpeed(h)} —— 移速抬到至少 {@link #WL_HORSE_SPEED}（只升不降）。 */
    public static boolean wlSetHorseSpeed(AbstractHorse h) {
        try {
            if (h == null) {
                return false;
            }
            AttributeInstance inst = h.getAttribute(Attribute.MOVEMENT_SPEED);
            if (inst == null) {
                return false;
            }
            double cur = 0.0;
            try {
                cur = inst.getBaseValue();
            } catch (Throwable e) {
                cur = 0.0;
            }
            if (cur >= WL_HORSE_SPEED) {
                return true;                            // ★ 只升不降
            }
            inst.setBaseValue(WL_HORSE_SPEED);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code wlSetRear(h, on)} —— 前蹄抬起 / 落下。
     *
     * <p>★ 只在"状态真的变了"时才写：{@code setRearing} 每次写都会刷实体数据，
     * 重复写同一个值 = 每 0.1 秒白送 5 个同步包。
     */
    public static void wlSetRear(AbstractHorse h, boolean on) {
        try {
            if (h == null) {
                return;
            }
            try {
                if (h.isRearing() == on) {
                    return;
                }
            } catch (Throwable ignored) {
                // 拿不到状态 → 照写（与原脚本一致）
            }
            h.setRearing(on);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code wlSetInvuln(p, on)} —— 玩家无敌开关（蓄力 / 冲刺）。
     *
     * <p>★ 一定要记下"我把它打开了"：收阵 / 下线 / 死亡 / 主手换掉时无条件关掉，
     * 否则一次异常退出就会让玩家永久免伤（{@code invulnerable} 不会自己过期）。
     */
    public static void wlSetInvuln(Player p, boolean on) {
        try {
            if (p == null) {
                return;
            }
            try {
                if (p.isInvulnerable() == on) {
                    return;
                }
            } catch (Throwable ignored) {
                // 拿不到状态 → 照写（与原脚本一致）
            }
            p.setInvulnerable(on);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 视角锁定（原脚本 3138~3181 行）
    // ======================================================================

    /** {@code wlAngDiff(a, b)} —— 两个角度的最小差值（考虑 ±360° 环绕）。 */
    static double wlAngDiff(double a, double b) {
        double d = (a - b) % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return Math.abs(d);
    }

    /**
     * {@code wlLockLook(p, st, yaw, pitch)} —— 冲刺期间把玩家视角钉住。
     *
     * <p>★ 默认关闭（{@link #WL_DASH_LOCK_LOOK} = false）：锁视角靠"每刻 teleport
     * 到当前坐标"实现，而玩家正骑在马上 ⇒ 客户端镜头被反复拽回，画面明显发顿。
     */
    private void wlLockLook(Player p, St st, double yaw, double pitch) {
        try {
            if (p == null || !WL_DASH_LOCK_LOOK) {
                return;
            }

            Location cur;
            try {
                cur = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            if (cur == null) {
                return;
            }

            // 没漂 → 什么都不做（大多数时刻：玩家根本没动鼠标）
            try {
                if (wlAngDiff(cur.getYaw(), yaw) < WL_LOOK_EPS
                        && wlAngDiff(cur.getPitch(), pitch) < WL_LOOK_EPS) {
                    return;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ① 先改服务端朝向（最轻量）
            try {
                p.setRotation((float) yaw, (float) pitch);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 再强制同步客户端：teleport 到"当前位置 + 锁定朝向"
            try {
                p.teleport(new Location(p.getWorld(), cur.getX(), cur.getY(), cur.getZ(),
                        (float) yaw, (float) pitch));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ 万一被甩下马 → 当场塞回去
            try {
                Entity veh = p.getVehicle();
                if (veh == null && st != null && st.lead != null) {
                    boolean ok;
                    try {
                        ok = !(st.lead.isDead() || !st.lead.isValid());
                    } catch (Throwable e1) {
                        ok = false;
                    }
                    if (ok) {
                        st.lead.addPassenger(p);
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 生成 / 移除骷髅马（原脚本 3183~3250 行）
    // ======================================================================

    /**
     * {@code wlSpawnHorse(world, x, y, z, yaw, isLead)}。
     *
     * <p>{@code isLead = true}（玩家骑的那匹）：保留 AI、保留重力；
     * {@code isLead = false}（护航马）：关 AI + 关重力 —— 反正每 2 刻都被钉回队形位置。
     */
    public static SkeletonHorse wlSpawnHorse(World world, double x, double y, double z,
                                             double yaw, boolean isLead) {
        try {
            if (world == null) {
                return null;
            }

            Location loc = new Location(world, x, y, z);
            try {
                loc.setYaw((float) yaw);
                loc.setPitch(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Entity spawned = world.spawnEntity(loc, EntityType.SKELETON_HORSE);
            if (!(spawned instanceof SkeletonHorse)) {
                return null;
            }
            SkeletonHorse h = (SkeletonHorse) spawned;

            // 已驯服 + 上好鞍（直接塞进鞍位，保证"骑得上去"）
            try {
                ((Tameable) h).setTamed(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.getInventory().setItem(0, new ItemStack(Material.SADDLE, 1));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ★ 移速：抬到「玩家速度 IV」那一档以上（只升不降）
            try {
                wlSetHorseSpeed(h);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            try {
                h.setSilent(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setInvulnerable(true);                    // ★ 无敌（护腿光环也会跳过）
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setCollidable(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setRemoveWhenFarAway(false);              // 附近没人也不消失
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setPersistent(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (!isLead) {
                try {
                    h.setAI(false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    h.setGravity(false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // ★ 空战利品表：万一死了也一个都不掉。
            //   ★★ 死代码：原脚本写的是 `if (LootTables.EMPTY != null) h.setLootTable(LootTables.EMPTY);`，
            //   而 paper-api 1.21.11 的 org.bukkit.loot.LootTables 里**没有 EMPTY 这个常量**
            //   （已 javap 核对：只有 ABANDONED_MINESHAFT … TRIAL_CHAMBERS_* 等），
            //   JS 侧 `LootTables.EMPTY` 是 undefined ⇒ `!= null` 为 false ⇒ 这个分支
            //   在真 JS 里**也从不执行**。所以 Java 侧整条删掉，可观察行为不变。

            // ★ 召唤物标记 → 各处伤害判定会跳过它（不伤、不弹）
            try {
                if (GS_WULI_SUMMON_KEY != null) {
                    h.getPersistentDataContainer().set(
                            GS_WULI_SUMMON_KEY, PersistentDataType.STRING, "wuli");
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            return h;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code wlRemoveHorse(h)}。 */
    public static void wlRemoveHorse(AbstractHorse h) {
        if (h == null) {
            return;
        }
        try {
            h.eject();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            var eq = h.getEquipment();
            if (eq != null) {
                try {
                    eq.clear();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            if (!h.isDead()) {
                h.remove();
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 队形跟随（原脚本 3252~3296 行）
    // ======================================================================

    /**
     * {@code wlFollowFormation(st, world, cx, cy, cz, yaw, pitch, rear)}。
     *
     * <p>朝向换算（MC：yaw 0 = +Z 南、90 = -X 西）：前向量 = (-sin, 0, cos)、
     * 左向量 = (cos, 0, sin)。
     */
    private static void wlFollowFormation(St st, World world, double cx, double cy, double cz,
                                          double yaw, double pitch, boolean rear) {
        try {
            if (st == null || world == null) {
                return;
            }
            double rad = yaw * Math.PI / 180.0;
            double fx = -Math.sin(rad), fz = Math.cos(rad);
            double lx = Math.cos(rad), lz = Math.sin(rad);

            for (int i = 0; i < WL_FORM.length; i++) {
                AbstractHorse h = (st.escorts == null || i >= st.escorts.size())
                        ? null : st.escorts.get(i);
                if (h == null) {
                    continue;
                }
                boolean ok;
                try {
                    ok = !(h.isDead() || !h.isValid());
                } catch (Throwable e) {
                    ok = false;
                }
                if (!ok) {
                    continue;
                }

                // 前蹄抬起 / 落下（蓄力时 true，其它时候 false）
                wlSetRear(h, rear);

                Form f = WL_FORM[i];
                double tx = cx + fx * (-f.back) + lx * f.side;
                double tz = cz + fz * (-f.back) + lz * f.side;

                // ★ 已经站在目标点上（静止不动时）→ 不发传送包，白省一半开销
                //   注意 pitch 也要一起比：否则"抬蹄"这种只改姿势的更新会被跳过
                boolean near;
                try {
                    Location cur = h.getLocation();
                    double ex = cur.getX() - tx, ey = cur.getY() - cy, ez = cur.getZ() - tz;
                    near = (ex * ex + ey * ey + ez * ez) < 0.0009
                            && Math.abs(cur.getYaw() - yaw) < 1.0
                            && Math.abs(cur.getPitch() - pitch) < 1.0;
                } catch (Throwable e) {
                    near = false;
                }
                if (near) {
                    continue;
                }

                Location loc = new Location(world, tx, cy, tz);
                try {
                    loc.setYaw((float) yaw);
                    loc.setPitch((float) pitch);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    h.teleport(loc);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code wlAlignMount(horse, p)} —— ★★ 上马时绝不能动玩家的视角、也绝不能碰玩家的位置。
     *
     * <p>坑 1：客户端一坐上坐骑就会把镜头 yaw 对齐到<b>坐骑的 yaw</b>，服务端拦不住。
     * 而这匹马最后几刻是"朝着玩家冲"的 ⇒ 它的 yaw 跟玩家正好相反 180°
     * ⇒ 一 {@code addPassenger} 镜头就被掰到背后。正解：<b>上马前</b>把坐骑 yaw
     * 刷成玩家当前的 yaw。
     * <p>坑 2：★ 玩家在骑乘状态下 {@code p.teleport(loc)} 会把玩家锁死 ⇒
     * 骑乘中<b>绝对不要</b>用 teleport 纠正朝向。
     */
    private static void wlAlignMount(AbstractHorse horse, Player p) {
        try {
            if (horse == null || p == null) {
                return;
            }
            Location l = p.getLocation();
            horse.teleport(new Location(l.getWorld(), l.getX(), l.getY(), l.getZ(),
                    l.getYaw(), 0.0f));
            // （顺手清一下速度，别带着冲过来的惯性）
            try {
                horse.setVelocity(new Vector(0.0, 0.0, 0.0));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                horse.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 下马动画：化烟 + 奔出（原脚本 3322~3521 行）
    // ======================================================================

    /** {@code wlSmokeType()} —— 大团白烟粒子。 */
    private static Particle wlSmokeType() {
        return firstParticle(WL_SMOKE_PARTICLES);
    }

    /** 原脚本 {@code wlSmokeType} / {@code wlRideParticle} / {@code wlFxHorseType} 的共同语义。 */
    private static Particle firstParticle(String[] names) {
        try {
            for (String n : names) {
                try {
                    Particle t = Particle.valueOf(n);
                    if (t != null) {
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code wlPuff(world, x, y, z, seed)} —— 在某点冒一缕"分散的烟"。 */
    private static void wlPuff(World world, double x, double y, double z, int seed) {
        try {
            Particle pt = wlSmokeType();
            if (pt == null) {
                return;
            }
            for (int i = 0; i < 6; i++) {
                double a = (i * 2.39996) + seed;              // 黄金角 → 均匀散开不重叠
                double r = 0.16 + ((i * 7 + seed * 3) % 5) * 0.05;
                try {
                    world.spawnParticle(pt,
                            x + Math.cos(a) * r,
                            y + ((i * 5 + seed) % 4) * 0.08,
                            z + Math.sin(a) * r,
                            1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlSmokeSound(world, loc)}。 */
    private static void wlSmokeSound(World world, Location loc) {
        try {
            if (world == null || loc == null) {
                return;
            }
            for (String s : WL_SMOKE_SNDS) {
                try {
                    world.playSound(loc, s, (float) WL_SMOKE_SND_VOL, (float) WL_SMOKE_SND_PIT);
                    return;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code wlSmokeHorseTick(h, i, keepRear)} —— 化烟：每刻冒一缕往上飘，化完直接消失。
     *
     * <p>{@code keepRear = true} ⇒ 化烟时也保持前蹄抬起（队长马"抬着蹄化掉"才好看）。
     */
    private void wlSmokeHorseTick(AbstractHorse h, int i, boolean keepRear) {
        try {
            if (h == null) {
                return;
            }
            boolean bad;
            try {
                bad = h.isDead() || !h.isValid();
            } catch (Throwable e) {
                bad = true;
            }
            if (bad) {
                return;
            }
            Location l = h.getLocation();
            try {
                wlSetRear(h, keepRear && WL_FLEE_REAR_KEEP);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            wlPuff(l.getWorld(), l.getX(), l.getY() + 0.35 + i * WL_DISSMOKE_UP, l.getZ(), i);
            if (i == 0) {
                wlSmokeSound(l.getWorld(), l);
            }
            if (i >= WL_DISSMOKE_TICKS) {
                wlRemoveHorse(h);
                return;
            }
            final boolean kr = keepRear && WL_FLEE_REAR_KEEP;
            GS.runLater(() -> wlSmokeHorseTick(h, i + 1, kr), 1);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlEndFlee(h, onEnd)} —— 奔出之后的收尾：化烟（或直接消失）+ 回调。 */
    private void wlEndFlee(AbstractHorse h, Runnable onEnd) {
        try {
            if (h != null) {
                if (WL_FLEE_SMOKE) {
                    try {
                        wlSmokeHorseTick(h, 0, true);
                    } catch (Throwable e) {
                        wlRemoveHorse(h);
                    }
                } else {
                    wlRemoveHorse(h);
                }
            }
        } catch (Throwable e) {
            try {
                wlRemoveHorse(h);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
        try {
            if (onEnd != null) {
                onEnd.run();
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlFleeRearTick(h, i, onEnd)} —— 跑到第 5 格后原地抬前蹄半秒，然后再化白烟。 */
    private void wlFleeRearTick(AbstractHorse h, int i, Runnable onEnd) {
        try {
            if (h == null) {
                wlEndFlee(h, onEnd);
                return;
            }
            boolean bad;
            try {
                bad = h.isDead() || !h.isValid();
            } catch (Throwable e) {
                bad = true;
            }
            if (bad) {
                wlEndFlee(h, onEnd);
                return;
            }

            World world;
            Location l;
            try {
                world = h.getWorld();
                l = h.getLocation();
            } catch (Throwable e) {
                world = null;
                l = null;
            }
            if (world == null || l == null) {
                wlEndFlee(h, onEnd);
                return;
            }

            try {
                wlSetRear(h, true);                     // ★ 前蹄抬起
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setVelocity(new Vector(0.0, 0.0, 0.0));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 抬蹄那一刻蹬一下地面（扬尘 + 一声嘶）
            if (i == 0) {
                try {
                    wlSmokeSound(world, l);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                Particle pt = wlSmokeType();
                if (pt != null) {
                    try {
                        world.spawnParticle(pt, l.getX(), l.getY() + 0.12, l.getZ(),
                                6, 0.25, 0.06, 0.25, 0.0);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }

            if (i + 1 >= WL_FLEE_REAR_TICKS) {
                wlEndFlee(h, onEnd);
                return;
            }
            final World fw = world;
            final Location fl = l;
            GS.runLater(() -> wlFleeRearTick(h, i + 1, onEnd), 1);
            if (fw == null || fl == null) {
                // 仅为让上面的 final 局部变量有意义（永不进入）
                return;
            }
        } catch (Throwable e) {
            wlEndFlee(h, onEnd);
        }
    }

    /** {@code wlFleeHorseTick(h, sx, sy, sz, dirx, dirz, i, onEnd)} —— 队长马朝前方奔出。 */
    private void wlFleeHorseTick(AbstractHorse h, double sx, double sy, double sz,
                                 double dirx, double dirz, int i, Runnable onEnd) {
        try {
            if (h == null) {
                try {
                    if (onEnd != null) {
                        onEnd.run();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            boolean bad;
            try {
                bad = h.isDead() || !h.isValid();
            } catch (Throwable e) {
                bad = true;
            }
            if (bad) {
                try {
                    if (onEnd != null) {
                        onEnd.run();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }

            World world;
            Location l;
            try {
                world = h.getWorld();
                l = h.getLocation();
            } catch (Throwable e) {
                world = null;
                l = null;
            }
            if (world == null || l == null) {
                try {
                    if (onEnd != null) {
                        onEnd.run();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }

            double k = (i + 1) / (double) WL_FLEE_TICKS;
            if (k > 1) {
                k = 1;
            }
            double e = 1.0 - Math.pow(1.0 - k, 2.2);          // 起步慢、越跑越快
            double nx = sx + dirx * WL_FLEE_DIST * e;
            double nz = sz + dirz * WL_FLEE_DIST * e;
            double ny = sy;

            // 朝奔出去的方向
            double yaw = Math.atan2(-dirx, dirz) * 180.0 / Math.PI;   // MC：yaw 0 = +Z
            try {
                h.teleport(new Location(world, nx, ny, nz, (float) yaw, 0.0f));
                h.setVelocity(new Vector(0.0, 0.0, 0.0));
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }

            // 蹄下扬尘 + 马蹄声
            Particle pt = wlSmokeType();
            if (pt != null && (i % 2) == 0) {
                try {
                    world.spawnParticle(pt, nx, ny + 0.12, nz, 4, 0.16, 0.05, 0.16, 0.0);
                } catch (Throwable e3) {
                    // 与原脚本一致：静默
                }
            }
            if (i % 3 == 0) {
                try {
                    world.playSound(new Location(world, nx, ny, nz), WL_SMOKE_SNDS[0], 0.5f, 1.2f);
                } catch (Throwable e4) {
                    // 与原脚本一致：静默
                }
            }

            if (i + 1 >= WL_FLEE_TICKS) {
                // ★ 跑到第 WL_FLEE_DIST 格 ⇒ 先抬前蹄站半秒，再化白烟
                if (WL_FLEE_REAR_TICKS > 0) {
                    try {
                        wlFleeRearTick(h, 0, onEnd);
                    } catch (Throwable e5) {
                        wlEndFlee(h, onEnd);
                    }
                } else {
                    wlEndFlee(h, onEnd);
                }
                return;
            }
            GS.runLater(() -> wlFleeHorseTick(h, sx, sy, sz, dirx, dirz, i + 1, onEnd), 1);
        } catch (Throwable e) {
            try {
                if (onEnd != null) {
                    onEnd.run();
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsWuliDismissAnim(p, st)} —— 收阵动画总入口。
     *
     * <p>★ 玩家的位置 / 朝向<b>一个字节都不碰</b>（只动马）⇒ 不锁移动、不掰视角。
     */
    private void gsWuliDismissAnim(Player p, St st) {
        try {
            if (st == null) {
                return;
            }

            // ① 先卸下玩家（不传送、不改朝向）—— 玩家只是"从马上下来"，站原地
            try {
                if (p != null) {
                    p.leaveVehicle();
                }
            } catch (Throwable e) {
                try {
                    if (p != null) {
                        p.eject();
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }

            // ② 玩家当前朝向（只用来定队长马往哪边跑）
            double fx = 0.0, fz = 1.0;
            try {
                Location pl = p.getLocation();
                double rad = pl.getYaw() * Math.PI / 180.0;
                fx = -Math.sin(rad);
                fz = Math.cos(rad);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ 4 匹护航马 → 当场化烟
            if (st.escorts != null) {
                for (SkeletonHorse h : st.escorts) {
                    if (h == null) {
                        continue;
                    }
                    wlSmokeHorseTick(h, 0, false);
                }
            }
            st.escorts = new ArrayList<>();

            // ④ 队长马 → 朝玩家前方奔出 WL_FLEE_DIST 格，跑到头再化烟
            SkeletonHorse lead = st.lead;
            st.lead = null;
            if (lead != null) {
                boolean ok;
                try {
                    ok = !(lead.isDead() || !lead.isValid());
                } catch (Throwable e) {
                    ok = false;
                }
                if (ok) {
                    try {
                        Location ll = lead.getLocation();
                        wlFleeHorseTick(lead, ll.getX(), ll.getY(), ll.getZ(), fx, fz, 0, null);
                    } catch (Throwable e) {
                        wlRemoveHorse(lead);
                    }
                } else {
                    wlRemoveHorse(lead);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 开机 / 关机（原脚本 3523~3791 行）
    // ======================================================================

    /**
     * {@code gsWuliSummon(p)} —— 只生成"玩家要骑的那一匹 + 4 匹护航马"。
     *
     * <p>★ 冲过来引路的那匹已经在 {@code wlRideFinish} 里删掉了，所以这里永远是
     * <b>全新 spawn</b>，天生就在玩家位置、朝向 = 玩家朝向。
     */
    private St gsWuliSummon(Player p) {
        try {
            String uuid = ListenerCore.uid(p);
            World world = p.getWorld();
            Location base = p.getLocation();
            double yaw = 0;
            try {
                yaw = base.getYaw();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            St st = new St();
            st.mode = "follow";
            st.heading = yaw;

            double rad = yaw * Math.PI / 180.0;
            double fx = -Math.sin(rad), fz = Math.cos(rad);
            double lx = Math.cos(rad), lz = Math.sin(rad);

            // 队长马（玩家骑的那匹）：★ 全新生成在玩家位置、朝向 = 玩家当前 yaw
            st.lead = wlSpawnHorse(world, base.getX(), base.getY(), base.getZ(), yaw, true);

            // 4 匹护航马（^ 队形，左右各 2）
            for (int i = 0; i < WL_FORM.length; i++) {
                Form f = WL_FORM[i];
                st.escorts.set(i, wlSpawnHorse(world,
                        base.getX() + fx * (-f.back) + lx * f.side,
                        base.getY(),
                        base.getZ() + fz * (-f.back) + lz * f.side,
                        yaw, false));
            }

            // 副手：先把原来的东西"请出去"（背包塞不下掉脚边），再放上拴绳
            try {
                var inv = p.getInventory();
                ItemStack old = null;
                try {
                    old = inv.getItemInOffHand();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    inv.setItemInOffHand(null);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                if (old != null && !gsWuliIsRein(old)) {
                    gsScopeStash(p, old);
                }
                ItemStack rein = wlMakeRein();
                if (rein != null) {
                    inv.setItemInOffHand(rein);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 骑上队长马（★ 上马前已把坐骑 yaw 对齐玩家朝向 ⇒ 镜头不会掰）
            try {
                if (st.lead != null) {
                    st.lead.addPassenger(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            GS_WULI_STATE.put(uuid, st);
            try {
                world.playSound(base, "entity.horse.saddle", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return st;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsWuliDismiss(p, st, restore, anim)}。
     *
     * <p>{@code anim = true} → 播下马动画（护航马化烟 / 队长马奔出）；
     * {@code false} → 立刻消失（死亡、下线、换武器）。
     */
    private void gsWuliDismiss(Player p, St st, boolean restore, boolean anim) {
        try {
            if (st == null) {
                return;
            }
            st.dead = true;
            st.mode = "follow";

            // ★ 收阵 = 无条件解除玩家无敌（蓄力无敌只在这段动作里有效，绝不能带出去）
            try {
                if (p != null) {
                    wlSetInvuln(p, false);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            if (anim) {
                // ★ 走动画：马自己散场，玩家的位置 / 朝向一点都不碰
                gsWuliDismissAnim(p, st);
            } else {
                wlRemoveHorse(st.lead);
                if (st.escorts != null) {
                    for (SkeletonHorse h : st.escorts) {
                        wlRemoveHorse(h);
                    }
                }
                st.lead = null;
                st.escorts = new ArrayList<>();
                // 把玩家从马上卸下来
                try {
                    if (p != null) {
                        p.leaveVehicle();
                    }
                } catch (Throwable e) {
                    try {
                        if (p != null) {
                            p.eject();
                        }
                    } catch (Throwable e2) {
                        // 与原脚本一致：静默
                    }
                }
            }

            // 副手拴绳收走 + 还回原来那件东西
            try {
                if (p != null) {
                    var inv = p.getInventory();
                    ItemStack off = inv.getItemInOffHand();
                    if (gsWuliIsRein(off)) {
                        inv.setItemInOffHand(null);
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ 原脚本的死代码：st.stashed 永远是 null ⇒ 下面这一支走不到，故不照抄：
            //   if (restore && p != null && st.stashed != null) gsScopeStash(p, st.stashed);
            st.stashed = null;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWuliQuit(p)} —— 下线 / 死亡收阵。 */
    public void gsWuliQuit(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);

            // ★ "马正在冲过来"的半途状态 → 先把这匹野马收掉
            try {
                Ride rc = GS_WULI_RIDE.get(uuid);
                if (rc != null) {
                    wlRemoveHorse(rc.h);
                    GS_WULI_RIDE.remove(uuid);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            try {
                GS_WULI_SUMMONING.remove(uuid);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            St st = GS_WULI_STATE.get(uuid);
            if (st == null) {
                return;
            }
            gsWuliDismiss(p, st, true, false);
            GS_WULI_STATE.remove(uuid);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsWuliToggle(p)} —— F 键 = 开机 / 关机。
     *
     * <p>带 250ms 去抖：{@code /rsc reload} 会重复注册监听器，一次按键被处理 N 遍时
     * "翻转偶数次 = 没反应"。Java 版已经幂等注册，但去抖<b>保留</b>
     * （它同时防的是"手抖连按两次 F 把刚上的马立刻收掉"）。
     */
    public void gsWuliToggle(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);

            long now = ListenerCore.now();
            Long last = GS_WULI_TOGGLE_AT.get(uuid);
            if (last != null && (now - last) < 250) {
                return;
            }
            GS_WULI_TOGGLE_AT.put(uuid, now);

            St st = GS_WULI_STATE.get(uuid);
            if (st != null) {                 // 已开机 → 关机（★ 播下马动画：化烟 + 奔出）
                gsWuliDismiss(p, st, true, true);
                GS_WULI_STATE.remove(uuid);
                return;
            }
            wlRideIn(p);                      // 没开机 → 正前方 7 格冲出一匹马，撞到才上马
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // F 键开机演出（原脚本 3656~3868 行）
    // ======================================================================

    /** {@code wlRideSound(world, loc, chain, vol, pit)} —— 候选链里第一个能播的。 */
    private static void wlRideSound(World world, Location loc, String[] chain,
                                    float vol, float pit) {
        try {
            if (world == null || loc == null) {
                return;
            }
            for (String s : chain) {
                try {
                    world.playSound(loc, s, vol, pit);
                    return;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlRideParticle()}。 */
    private static Particle wlRideParticle() {
        return firstParticle(WL_RIDE_PARTICLE);
    }

    /** {@code wlRideSpawnLoc(p)} —— ★ 正前方几格的那个坐标（沿玩家朝向往前推）。 */
    private static Location wlRideSpawnLoc(Player p) {
        try {
            Location l = p.getLocation();
            double rad = l.getYaw() * Math.PI / 180.0;
            double fx = -Math.sin(rad), fz = Math.cos(rad);   // 朝向（MC：yaw 0 = +Z）
            return new Location(l.getWorld(),
                    l.getX() + fx * WL_RIDE_DIST,
                    l.getY(),
                    l.getZ() + fz * WL_RIDE_DIST);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code wlRideIn(p)} —— 开始冲。
     *
     * <p>{@code GS_WULI_SUMMONING} 兼做"正在冲"标记：演出期间再按 F 直接忽略。
     */
    public void wlRideIn(Player p) {
        try {
            if (p == null || !p.isOnline()) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            if (Integer.valueOf(1).equals(GS_WULI_SUMMONING.get(uuid))) {
                return;                                   // 已经在冲了 → 忽略
            }
            World world = p.getWorld();

            // 生成失败（世界禁刷怪等）⇒ 直接走老流程，绝不把玩家卡在"按了 F 没反应"
            Location loc = wlRideSpawnLoc(p);
            if (loc == null) {
                gsWuliSummon(p);
                return;
            }
            SkeletonHorse h = wlSpawnHorse(world, loc.getX(), loc.getY(), loc.getZ(), 0, true);
            if (h == null) {
                gsWuliSummon(p);
                return;
            }

            // ★★ 冲过来这 1.5 秒里绝对不能死（死了就永远上不了马），三层保险：
            //   ① setInvulnerable(true)（wlSpawnHorse 里已设，这里再设一次）
            //   ② 血量直接顶到上限
            //   ③ 记分板标签 gs_ridein —— 伤害事件拦截器看到就直接 cancel
            try {
                h.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.setHealth(h.getMaxHealth());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.addScoreboardTag(WL_RIDE_TAG);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            GS_WULI_SUMMONING.put(uuid, 1);
            Ride c = new Ride();
            c.h = h;
            c.sx = loc.getX();
            c.sy = loc.getY();
            c.sz = loc.getZ();
            c.i = 0;
            c.done = false;
            gsWuliRideTick(p, c);
        } catch (Throwable e) {
            try {
                GS_WULI_SUMMONING.remove(ListenerCore.uid(p));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                gsWuliSummon(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code wlRideFinish(p, c)} —— ★★ 收场：冲过来的那匹马<b>直接删除</b>，
     * 等 3 刻后在玩家<b>原地</b>另生成一匹新的已驯服骷髅马（朝向 = 玩家朝向）给玩家骑。
     */
    private void wlRideFinish(Player p, Ride c) {
        try {
            String uuid = ListenerCore.uid(p);
            try {
                GS_WULI_RIDE.remove(uuid);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ 注意：这里故意不清 GS_WULI_SUMMONING —— 换马延迟的 3 刻内保持"演出中"，
            //   玩家连按 F 不会又触发一次冲马。等新马生成完才清。
            if (p == null || !p.isOnline()) {
                try {
                    GS_WULI_SUMMONING.remove(uuid);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    wlRemoveHorse(c == null ? null : c.h);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }

            // ① 记下冲过来那匹马的位置（它马上要没了）
            Double hx = null, hy = null, hz = null;
            World hw = null;
            try {
                if (c != null && c.h != null) {
                    Location hl = c.h.getLocation();
                    hx = hl.getX();
                    hy = hl.getY();
                    hz = hl.getZ();
                    hw = hl.getWorld();
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② ★ 撞到的那一下：一圈冲击尘 + 一声闷响（"撞上了"的实感）
            try {
                World w2 = p.getWorld();
                Location l2 = p.getLocation();
                Particle pt2 = wlRideParticle();
                if (pt2 != null) {
                    for (int ci = 0; ci < 10; ci++) {
                        double a2 = Math.PI * 2.0 * ci / 10.0;
                        w2.spawnParticle(pt2,
                                l2.getX() + Math.cos(a2) * 0.7,
                                l2.getY() + 0.3,
                                l2.getZ() + Math.sin(a2) * 0.7,
                                1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
                wlRideSound(w2, l2, new String[]{"block.dirt.land", "entity.horse.gallop"},
                        0.7f, 0.8f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ ★★ 冲过来的马直接删除（原地冒一缕烟，强调"它只是来带路的"）
            try {
                if (WL_RIDE_SPAWN_FX && hw != null) {
                    for (int si = 0; si < 3; si++) {
                        try {
                            wlPuff(hw, hx, hy + 0.4 + si * 0.3, hz, si);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                wlRemoveHorse(c == null ? null : c.h);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ④ 等 3 刻（让"删除"看得见），在玩家原地另生成一匹新马并开机
            long delay = (WL_RIDE_SWAP_TICKS > 0) ? WL_RIDE_SWAP_TICKS : 1;
            final Player fp = p;
            final String fu = uuid;
            GS.runLater(() -> {
                try {
                    if (fp == null || !fp.isOnline() || fp.isDead()) {
                        try {
                            GS_WULI_SUMMONING.remove(fu);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                        return;                              // 人没了就别生成
                    }
                    gsWuliSummon(fp);
                    GS_WULI_SUMMONING.remove(fu);            // ★ 这时才清标记
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, delay);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWuliRideTick(p, c)} —— 冲过来那匹马的每刻推进。 */
    private void gsWuliRideTick(Player p, Ride c) {
        try {
            if (c == null || c.done) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            GS_WULI_RIDE.put(uuid, c);

            // 玩家掉线 / 死亡 → 收马走人（绝不在世界里留一匹野马）
            if (p == null || !p.isOnline() || p.isDead()) {
                wlRemoveHorse(c.h);
                try {
                    GS_WULI_RIDE.remove(uuid);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    GS_WULI_SUMMONING.remove(uuid);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            SkeletonHorse h = c.h;
            boolean bad = (h == null);
            if (!bad) {
                try {
                    bad = h.isDead() || !h.isValid();
                } catch (Throwable e) {
                    bad = true;
                }
            }
            if (bad) {
                wlRideFinish(p, null);                       // 马中途没了 → 照样给拴绳
                return;
            }

            World world = p.getWorld();
            Location pl = p.getLocation();
            double k = (c.i + 1) / (double) WL_RIDE_TICKS;
            if (k > 1) {
                k = 1;
            }
            double ease = (WL_RIDE_EASE <= 1) ? k : Math.pow(k, WL_RIDE_EASE);   // 越冲越快

            // 目标 = 玩家此刻的位置（玩家可以一边被撞一边走）
            double tx = pl.getX(), ty = pl.getY(), tz = pl.getZ();
            double nx = c.sx + (tx - c.sx) * ease;
            double ny = c.sy + (ty - c.sy) * ease;
            double nz = c.sz + (tz - c.sz) * ease;

            // 撞上了？（马身有宽度，用 1.9 格比较稳）⇒ 提前收场
            double dx = tx - nx, dy = ty - ny, dz = tz - nz;
            double d2 = dx * dx + dy * dy + dz * dz;
            double dist = Math.sqrt(d2);

            // ★ 每刻续一次命：invulnerable 不是永久的
            try {
                h.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                if (h.getHealth() < h.getMaxHealth()) {
                    h.setHealth(h.getMaxHealth());
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                h.addScoreboardTag(WL_RIDE_TAG);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 朝向 = 冲向玩家的方向
            double yaw = pl.getYaw();
            if (dist > 0.05) {
                yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;   // MC：yaw 0 = +Z
            }
            try {
                h.teleport(new Location(world, nx, ny, nz, (float) yaw, 0.0f));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 临撞前抬前蹄（花活）+ 身后扬尘
            try {
                wlSetRear(h, dist <= WL_RIDE_REAR_R);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                Particle pt = wlRideParticle();
                if (pt != null && (c.i % 2) == 0) {
                    world.spawnParticle(pt, nx, ny + 0.15, nz, 3, 0.18, 0.05, 0.18, 0.0);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // 马蹄声
            if (WL_RIDE_SND_EVERY > 0 && (c.i % WL_RIDE_SND_EVERY) == 0) {
                try {
                    wlRideSound(world, new Location(world, nx, ny, nz), WL_RIDE_SND,
                            (float) WL_RIDE_SND_VOL, (float) WL_RIDE_SND_PIT);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            c.i++;
            if (dist <= WL_RIDE_HIT_R || c.i >= WL_RIDE_TICKS) {
                // ★ 撞到 ⇒ 副手出现「五骊鞚」+ 扶上马（1.5 秒内没撞上也会到点强制收）
                try {
                    wlSetRear(h, false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                wlRideFinish(p, c);
                return;
            }
            GS.runLater(() -> gsWuliRideTick(p, c), 1);
        } catch (Throwable e) {
            try {
                wlRideFinish(p, c);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 目标筛选 / 伤害（原脚本 3871~3922 行）
    // ======================================================================

    /** {@code wlNotTarget(p, ent)} —— 这个实体不该被剑气 / 冲锋打到。 */
    private static boolean wlNotTarget(Player p, Entity ent) {
        try {
            if (ent == null) {
                return true;
            }
            String eid = ListenerCore.uid(ent);
            String sid = ListenerCore.uid(p);
            if (eid.equals(sid)) {
                return true;                                 // 不打自己
            }
            if (!(ent instanceof Damageable)) {
                return true;                                 // 只认活体
            }
            try {
                if (ent.isDead()) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            String tn = ListenerCore.typeName(ent);
            if (tn.equals("ARMOR_STAND") || tn.equals("DROPPED_ITEM")
                    || tn.equals("ITEM") || tn.equals("EXPERIENCE_ORB")) {
                return true;
            }
            if (gsIsOurSummon(ent)) {
                return true;                                 // 自己的召唤物（五骊马 / 悦灵）
            }
            if (ListenerCore.gsIsMaiyouSummon(ent, GS_MAIYOU_KEY)) {
                return true;                                 // 麦友的酒
            }
            return false;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * {@code wlHurt(p, ent, dmg, kbx, kby, kbz)} —— 造成伤害，并自己决定击退多少。
     *
     * <p>· 不传 kb（冲锋）：先记下当前速度，打完原样塞回去 ⇒ 完全没有击退；
     * <br>· 传 kb（剑气）：还原的速度上再叠加一点 kb 冲量 ⇒ 轻微击退。
     */
    private void wlHurt(Player p, Entity ent, double dmg, Double kbx, Double kby, Double kbz) {
        try {
            if (ent == null) {
                return;
            }
            try {
                ((LivingEntity) ent).setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            double vx = 0, vy = 0, vz = 0;
            boolean hasV = false;
            try {
                Vector v = ent.getVelocity();
                vx = v.getX();
                vy = v.getY();
                vz = v.getZ();
                hasV = true;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ★ 打勾：这是脚本自己结算的伤害，"禁止近战"的监听器必须放行
            gsMarkScriptDamage(p, true);
            try {
                try {
                    ((Damageable) ent).damage(dmg, p);
                } catch (Throwable e1) {
                    try {
                        ((Damageable) ent).damage(dmg);
                    } catch (Throwable e2) {
                        // 与原脚本一致：静默
                    }
                }
            } finally {
                gsMarkScriptDamage(p, false);
            }

            double kx = (kbx == null ? 0.0 : kbx);
            double ky = (kby == null ? 0.0 : kby);
            double kz = (kbz == null ? 0.0 : kbz);
            boolean anyKb = (kx != 0.0 || ky != 0.0 || kz != 0.0);

            if (hasV || anyKb) {
                try {
                    ent.setVelocity(new Vector(vx + kx, vy + ky, vz + kz));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 三参版 {@code wlHurt(p, ent, dmg)} —— 完全没有击退。 */
    private void wlHurt(Player p, Entity ent, double dmg) {
        wlHurt(p, ent, dmg, null, null, null);
    }

    /** {@code wlAxes(dir)} —— 以 dir 为前进轴的一组正交基。 */
    static Axes wlAxes(Vector dir) {
        Axes a = new Axes();
        double dz = dir.getZ(), dx = dir.getX(), dy = dir.getY();
        // lat = dir × (0,1,0) = (-dz, 0, dx)
        double lx = -dz, ly = 0.0, lz = dx;
        double ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (ln < 1.0e-4) {
            lx = 1.0;
            ly = 0.0;
            lz = 0.0;
            ln = 1.0;
        }
        lx /= ln;
        ly /= ln;
        lz /= ln;
        // up2 = dir × lat
        double ux = dy * lz - dz * ly;
        double uy = dz * lx - dx * lz;
        double uz = dx * ly - dy * lx;
        double un = Math.sqrt(ux * ux + uy * uy + uz * uz);
        if (un < 1.0e-4) {
            ux = 0.0;
            uy = 1.0;
            uz = 0.0;
            un = 1.0;
        }
        ux /= un;
        uy /= un;
        uz /= un;
        a.lx = lx;
        a.ly = ly;
        a.lz = lz;
        a.ux = ux;
        a.uy = uy;
        a.uz = uz;
        return a;
    }

    // ======================================================================
    // 剑气（原脚本 3942~4052 行）
    // ======================================================================

    /** {@code gsWuliTryQi(p)} —— 左键斩剑气（冷却中完全静默）。 */
    public void gsWuliTryQi(Player p) {
        try {
            // 冷却中完全静默（不提示、不出声、不出剑气）
            if (ListenerCore.gsWuliCdLeft(p, GS_WULI_QI_KEY, WL_QI_CD_MS) > 0) {
                return;
            }
            ListenerCore.gsWuliMarkCd(p, GS_WULI_QI_KEY);

            // ★ 斩击音效：末影龙飞行 + 末影人传送（同一时刻一起响）
            try {
                World w = p.getWorld();
                Location l = p.getLocation();
                w.playSound(l, WL_QI_SND1, (float) WL_QI_SND1_VOL, (float) WL_QI_SND1_PIT);
                w.playSound(l, WL_QI_SND2, (float) WL_QI_SND2_VOL, (float) WL_QI_SND2_PIT);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Location eye;
            try {
                eye = p.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Vector dir;
            try {
                dir = eye.getDirection();
            } catch (Throwable e) {
                return;
            }
            if (dir == null) {
                return;
            }

            Pt base = new Pt();
            base.x = eye.getX();
            base.y = eye.getY() - 0.35;
            base.z = eye.getZ();
            Axes ax = wlAxes(dir);
            wlQiStep(p, base, dir, ax, 0.5, new HashMap<>(), 1.0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code wlQiStep(p, base, dir, ax, d, hit, kbMul)} —— 剑气一步步往前推
     * （自终止的 runLater 链，不需要 cancel）。
     *
     * <p>{@code kbMul}：击退倍率（冲刺自动斩的那 4 道传 0 ⇒ 完全无击退；手动左键默认 1）。
     */
    private void wlQiStep(Player p, Pt base, Vector dir, Axes ax, double d,
                          Map<String, Integer> hit, Double kbMul) {
        try {
            if (d > WL_QI_RANGE) {
                return;                                   // 飞满 10 格 → 结束
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            double dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();

            // 击退方向 = 剑气的水平方向（低头/抬头砍时不要把目标往地里/天上怼）
            double hx = dx, hz = dz;
            double hl = Math.sqrt(hx * hx + hz * hz);
            if (hl < 1.0e-4) {
                hx = 0.0;
                hz = 0.0;
            } else {
                hx /= hl;
                hz /= hl;
            }

            // ① 画弧形剑气：在 d 处横着扫一道 5 格长的弧
            Particle pt1 = null, pt2 = null;
            try {
                pt1 = Particle.SWEEP_ATTACK;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (pt1 == null) {
                try {
                    pt1 = Particle.valueOf("SWEEP_ATTACK");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            try {
                pt2 = Particle.CRIT;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            for (int i = -5; i <= 5; i++) {
                double t = i / 5.0;
                double off = t * WL_QI_HALF_W;            // 左右展开 ±2.5 格
                double bulge = (1.0 - t * t) * 0.45;      // 中间略往前凸 → 弧形
                double px = base.x + dx * (d + bulge) + ax.lx * off;
                double py = base.y + dy * (d + bulge) + ax.ly * off;
                double pz = base.z + dz * (d + bulge) + ax.lz * off;
                if (pt1 != null) {
                    try {
                        world.spawnParticle(pt1, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                if (pt2 != null && (i % 2) == 0) {
                    try {
                        world.spawnParticle(pt2, px, py, pz, 1, 0.05, 0.05, 0.05, 0.0);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }

            // ② 命中判定：d 前后 ±厚度、左右 ±2.5、上下 ±1.5 的"弧形薄片"里的活体
            double cx = base.x + dx * d, cy = base.y + dy * d, cz = base.z + dz * d;
            double r = WL_QI_HALF_W + WL_QI_HALF_T + 1.0;
            Collection<Entity> list;
            try {
                list = world.getNearbyEntities(new Location(world, cx, cy, cz),
                        r, WL_QI_HALF_H + 1.0, r);
            } catch (Throwable e) {
                list = null;
            }

            if (list != null) {
                for (Entity ent : list) {
                    if (ent == null) {
                        continue;
                    }
                    String eid;
                    try {
                        eid = ListenerCore.uid(ent);
                    } catch (Throwable e) {
                        continue;
                    }
                    if (Integer.valueOf(1).equals(hit.get(eid))) {
                        continue;                         // 同一道剑气每个目标只吃一次
                    }

                    if (wlNotTarget(p, ent)) {
                        continue;
                    }

                    double relx, rely, relz;
                    try {
                        Location el = ent.getLocation();
                        relx = el.getX() - base.x;
                        rely = (el.getY() + 0.9) - base.y;
                        relz = el.getZ() - base.z;
                    } catch (Throwable e) {
                        continue;
                    }

                    double along = relx * dx + rely * dy + relz * dz;
                    double lat = relx * ax.lx + rely * ax.ly + relz * ax.lz;
                    double vup = relx * ax.ux + rely * ax.uy + relz * ax.uz;

                    if (Math.abs(along - d) > WL_QI_HALF_T) {
                        continue;
                    }
                    if (Math.abs(lat) > WL_QI_HALF_W) {
                        continue;
                    }
                    if (Math.abs(vup) > WL_QI_HALF_H) {
                        continue;
                    }

                    hit.put(eid, 1);
                    // 7 点伤害 + 击退（kbMul = 0 ⇒ 不传 kb ⇒ wlHurt 会把原速度塞回去，完全无击退）
                    double km = (kbMul == null ? 1.0 : kbMul);
                    if (km <= 0) {
                        wlHurt(p, ent, WL_QI_DMG);
                    } else {
                        wlHurt(p, ent, WL_QI_DMG,
                                hx * WL_QI_KB * km, WL_QI_KB_UP * km, hz * WL_QI_KB * km);
                    }
                }
            }

            GS.runLater(() -> wlQiStep(p, base, dir, ax, d + WL_QI_STEP, hit, kbMul), 1);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 冲锋（原脚本 4054~4418 行）
    // ======================================================================

    /** {@code gsWuliTryCharge(p)} —— 右键蓄力 1 秒（无敌）。 */
    public void gsWuliTryCharge(Player p) {
        try {
            String uuid = ListenerCore.uid(p);
            St st = GS_WULI_STATE.get(uuid);
            if (st == null) {
                return;                                  // 没开机 → 右键什么都不做
            }

            // 正在蓄力 / 冲刺中 → 静默忽略（这时候冷却还没走完，不该刷"冷却中"的提示）
            if (!"follow".equals(st.mode)) {
                return;
            }

            if (ListenerCore.gsWuliCdLeft(p, GS_WULI_DASH_KEY, WL_DASH_CD_MS) > 0) {
                sendMessage(p, WL_MSG_CD);               // 冷却提示
                return;
            }

            ListenerCore.gsWuliMarkCd(p, GS_WULI_DASH_KEY);
            st.mode = "charge";
            st.fxTick = 0;

            Location l;
            try {
                l = (st.lead != null) ? st.lead.getLocation() : p.getLocation();
            } catch (Throwable e) {
                l = p.getLocation();
            }
            st.anchorX = l.getX();
            st.anchorY = l.getY();
            st.anchorZ = l.getZ();
            try {
                st.anchorYaw = p.getLocation().getYaw();
            } catch (Throwable e) {
                st.anchorYaw = 0;
            }
            st.chargeEnd = ListenerCore.now() + WL_CHARGE_MS;

            // ★ 蓄力这 1 秒玩家无敌（站桩不能动、也不能跑，再挨打就太亏了）
            if (WL_CHARGE_INVULN) {
                try {
                    wlSetInvuln(p, true);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            sendMessage(p, WL_MSG_USE);                  // 「冲锋！」
            try {
                p.getWorld().playSound(p.getLocation(), "entity.horse.gallop", 1.5f, 1.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlChargeFx(p, st)} —— 蓄力期间一圈往内收的粒子。 */
    private static void wlChargeFx(Player p, St st) {
        try {
            Particle particle;
            try {
                particle = Particle.CRIT;
            } catch (Throwable e) {
                particle = null;
            }
            if (particle == null) {
                try {
                    particle = Particle.valueOf("CRIT");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            if (particle == null) {
                return;
            }

            World world = p.getWorld();
            Location l = p.getLocation();
            double tk = st.fxTick + 1;
            st.fxTick = tk;

            int n = 10;
            double r = 1.6 - (tk % 8) * 0.15;
            if (r < 0.5) {
                r = 0.5;
            }
            for (int i = 0; i < n; i++) {
                double a = (Math.PI * 2.0 * i) / n + tk * 0.25;
                double h = 0.2 + ((tk + i * 2) % 12) * 0.13;
                try {
                    world.spawnParticle(particle,
                            l.getX() + Math.cos(a) * r,
                            l.getY() + h,
                            l.getZ() + Math.sin(a) * r,
                            1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWuliStartDash(p, st)} —— 蓄力结束 → 冲刺。 */
    public void gsWuliStartDash(Player p, St st) {
        try {
            if (st == null || st.dead) {
                return;
            }

            double yaw;
            try {
                yaw = st.lead.getLocation().getYaw();
            } catch (Throwable e) {
                yaw = st.anchorYaw;
            }
            st.heading = yaw;

            // ★ 锁定冲刺期间的视角：方向 = 松手那一刻的朝向（yaw），俯仰 = 当时的 pitch
            st.lockYaw = yaw;
            try {
                st.lockPitch = p.getLocation().getPitch();
            } catch (Throwable e) {
                st.lockPitch = 0.0;
            }

            double rad = yaw * Math.PI / 180.0;
            st.dirX = -Math.sin(rad);                    // MC：yaw 0 = +Z，90 = -X
            st.dirZ = Math.cos(rad);

            st.dashX0 = st.anchorX;
            st.dashY0 = st.anchorY;
            st.dashZ0 = st.anchorZ;
            st.dashI = 0;
            st.hit = new HashMap<>();
            st.mode = "dash";

            // 蓄力结束 → 无敌按配置决定去留（默认 WL_DASH_INVULN = false ⇒ 这里解除）
            try {
                wlSetInvuln(p, WL_DASH_INVULN);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            try {
                p.getWorld().playSound(p.getLocation(), "entity.horse.gallop", 2.5f, 0.8f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            wlDashTick(p, st);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlFxHorseType(chain)} —— 取第一个存在的粒子类型。 */
    private static Particle wlFxHorseType(String[] chain) {
        return firstParticle(chain == null ? WL_FXHORSE_PARTICLES : chain);
    }

    /** {@code wlFxHorseFx(world, x, y, z, yaw)} —— 在玩家头顶 2 格画一匹抬起前蹄的粒子马。 */
    static void wlFxHorseFx(World world, double x, double y, double z, double yaw) {
        try {
            if (!WL_FXHORSE_ON) {
                return;
            }
            Particle pt = wlFxHorseType(null);
            if (pt == null) {
                return;
            }

            double rad = yaw * Math.PI / 180.0;
            double fx = -Math.sin(rad), fz = Math.cos(rad);   // 朝向（MC：yaw 0 = +Z）
            double rx = -Math.cos(rad), rz = -Math.sin(rad);  // 右手方向
            double s = WL_FXHORSE_STEP;

            // 马底 = 玩家脚底 + Y 偏移，中心在前方 WL_FXHORSE_FRONT
            double bx = x + fx * WL_FXHORSE_FRONT;
            double by = y + WL_FXHORSE_Y;
            double bz = z + fz * WL_FXHORSE_FRONT;

            // ① 骨架：每条线段按 WL_FXHORSE_STEP 均匀采样
            for (double[] g : WL_FXHORSE_SEGS) {
                double du = g[2] - g[0], dv = g[3] - g[1];
                double len = Math.sqrt(du * du + dv * dv);
                long n = Math.round(len / s);
                if (n < 1) {
                    n = 1;
                }
                for (long i = 0; i <= n; i++) {
                    double t = i / (double) n;
                    double u = g[0] + du * t;
                    double v = g[1] + dv * t;
                    try {
                        world.spawnParticle(pt, bx + rx * u, by + v, bz + rz * u,
                                1, 0.0, 0.0, 0.0, 0.0);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }

            // ② 眼睛：换一种粒子，让马看起来是"活"的
            Particle ep = wlFxHorseType(WL_FXHORSE_EYE);
            if (ep != null) {
                try {
                    world.spawnParticle(ep, bx + rx * 0.82, by + 1.52, bz + rz * 0.82,
                            1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlDashQi(p, st)} —— 冲刺期间的一道剑气（朝向 = 冲刺方向，不吃左键冷却）。 */
    private void wlDashQi(Player p, St st) {
        try {
            Location eye = p.getEyeLocation();
            Vector dir = new Vector(st.dirX, 0.0, st.dirZ);
            try {
                dir = dir.normalize();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            if (WL_DASH_QI_SOUND) {
                try {
                    World w = p.getWorld();
                    w.playSound(eye, WL_QI_SND1,
                            (float) (WL_QI_SND1_VOL * 0.8), (float) WL_QI_SND1_PIT);
                    w.playSound(eye, WL_QI_SND2,
                            (float) (WL_QI_SND2_VOL * 0.8), (float) WL_QI_SND2_PIT);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            Pt base = new Pt();
            base.x = eye.getX();
            base.y = eye.getY() - 0.35;
            base.z = eye.getZ();
            wlQiStep(p, base, dir, wlAxes(dir), 0.5, new HashMap<>(), WL_DASH_QI_KB_MUL);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code wlDashFx(world, x, y, z, st)} —— 冲刺拖尾。 */
    private static void wlDashFx(World world, double x, double y, double z) {
        try {
            Particle particle;
            try {
                particle = Particle.CLOUD;
            } catch (Throwable e) {
                particle = null;
            }
            if (particle == null) {
                try {
                    particle = Particle.valueOf("CLOUD");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            if (particle == null) {
                return;
            }
            for (int i = -2; i <= 2; i++) {
                try {
                    world.spawnParticle(particle,
                            x + i * 1.0, y + 0.15, z + i * 1.0, 2, 0.2, 0.1, 0.2, 0.02);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code wlDashStepY(world, x, y, z)} —— 冲锋走到这一步时脚该落在哪个高度？
     *
     * <p>① 脚下 + 头顶都空 ⇒ 平着走；② 抬高 {@link #WL_DASH_STEP_UP} 格后站得住
     * ⇒ 当台阶踩上去继续冲；③ 都不行（= 障碍 ≥ 2 格）⇒ 返回 <b>null</b> 表示撞墙。
     */
    static Double wlDashStepY(World world, double x, double y, double z) {
        try {
            double bx = Math.floor(x), bz = Math.floor(z), by = Math.floor(y);
            if (!ListenerCore.gsWlBlockedAt(world, bx, by, bz)
                    && !ListenerCore.gsWlBlockedAt(world, bx, by + 1, bz)) {
                return y;
            }
            for (int up = 1; up <= WL_DASH_STEP_UP; up++) {
                boolean ok = true;
                for (int c = 0; c <= 1; c++) {
                    if (ListenerCore.gsWlBlockedAt(world, bx, by + up + c, bz)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return by + up;
                }
            }
            return null;
        } catch (Throwable e) {
            return y;
        }
    }

    /** {@code wlDashTick(p, st)} —— 冲刺：1 刻一步，整队一起往前推。 */
    private void wlDashTick(Player p, St st) {
        try {
            if (st == null || st.dead) {
                return;
            }
            if (!"dash".equals(st.mode)) {
                return;
            }

            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            int i = st.dashI;
            double k = (i + 1) / (double) WL_DASH_TICKS;
            double x = st.dashX0 + st.dirX * WL_DASH_DIST * k;
            double z = st.dashZ0 + st.dirZ * WL_DASH_DIST * k;
            double y = st.dashY0;

            // ★ 撞墙规则：1 格 = 上台阶继续冲；≥2 格 = 停在墙前，冲锋结束
            Double stepY = wlDashStepY(world, x, y, z);
            if (stepY == null) {
                st.mode = "follow";
                try {
                    wlSetInvuln(p, false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    Location wl = new Location(world, x, y, z);
                    try {
                        world.playSound(wl, "entity.horse.gallop", 0.8f, 0.6f);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        Particle pw = Particle.CLOUD;
                        if (pw != null) {
                            world.spawnParticle(pw, x, y + 1.0, z, 10, 0.4, 0.4, 0.4, 0.03);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            y = stepY;

            // ① 队长马（玩家）往前推（冲刺开始 = 前蹄落下）
            try {
                st.lead.teleport(new Location(world, x, y, z, (float) st.heading, 0.0f));
                st.lead.setVelocity(new Vector(0.0, 0.0, 0.0));
                wlSetRear(st.lead, false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 护航马跟队形（冲刺开始 = 前蹄落下，pitch 回 0）
            wlFollowFormation(st, world, x, y, z, st.heading, 0.0, false);

            // ②′ 锁视角（默认关闭）
            wlLockLook(p, st, st.lockYaw, st.lockPitch);

            // ③ 路径伤害：宽 5 格（左右各 2.5）、前后各 2 格
            double r = WL_DASH_HALF_W + 2.0;
            Collection<Entity> list;
            try {
                list = world.getNearbyEntities(new Location(world, x, y, z),
                        r, WL_DASH_HALF_H + 1.0, r);
            } catch (Throwable e) {
                list = null;
            }

            if (list != null) {
                for (Entity ent : list) {
                    if (ent == null) {
                        continue;
                    }
                    String eid;
                    try {
                        eid = ListenerCore.uid(ent);
                    } catch (Throwable e) {
                        continue;
                    }
                    if (Integer.valueOf(1).equals(st.hit.get(eid))) {
                        continue;                        // 一次冲锋每个目标只吃一次
                    }
                    if (wlNotTarget(p, ent)) {
                        continue;
                    }

                    double relx, rely, relz;
                    try {
                        Location el = ent.getLocation();
                        relx = el.getX() - x;
                        rely = (el.getY() + 0.9) - y;
                        relz = el.getZ() - z;
                    } catch (Throwable e) {
                        continue;
                    }

                    double along = relx * st.dirX + relz * st.dirZ;
                    double lat = relx * st.dirZ - relz * st.dirX;
                    if (Math.abs(lat) > WL_DASH_HALF_W) {
                        continue;
                    }
                    if (along > 2.0 || along < -2.0) {
                        continue;
                    }
                    if (Math.abs(rely) > WL_DASH_HALF_H) {
                        continue;
                    }

                    st.hit.put(eid, 1);
                    wlHurt(p, ent, WL_DASH_DMG);         // 15 点
                }
            }

            // ④ 冲刺拖尾
            wlDashFx(world, x, y, z);

            // ⑤ ★ 冲刺期间自动斩 4 道剑气（不用按左键），朝向 = 冲刺方向
            if (WL_DASH_QI_ON) {
                for (int at : WL_DASH_QI_AT) {
                    if (at == i) {
                        wlDashQi(p, st);
                        break;
                    }
                }
            }

            st.dashI = i + 1;
            // 冲完 → 回到跟随，并无条件解除无敌（万一 WL_DASH_INVULN 开着也要在这里收掉）
            if (st.dashI >= WL_DASH_TICKS) {
                st.mode = "follow";
                try {
                    wlSetInvuln(p, false);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            GS.runLater(() -> wlDashTick(p, st), 1);
        } catch (Throwable e) {
            try {
                if (st != null) {
                    st.mode = "follow";
                }
                wlSetInvuln(p, false);       // 异常退出也要收掉，绝不留"永久免伤"
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 常驻：骑阵跟随 / 条件检查（原脚本 4420~4545 行）
    // ======================================================================

    /** {@code gsWuliTickPlayer(p)} —— 每 2 刻一次。 */
    public void gsWuliTickPlayer(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            St st = GS_WULI_STATE.get(uuid);
            if (st == null) {
                return;
            }

            if (st.dead) {
                GS_WULI_STATE.remove(uuid);
                return;
            }

            // 玩家死了 → 交给 onPlayerDeath 收场
            try {
                if (p.isDead()) {
                    return;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            org.bukkit.inventory.PlayerInventory inv;
            try {
                inv = p.getInventory();
            } catch (Throwable e) {
                return;
            }
            ItemStack main = null, off = null;
            try {
                main = inv.getItemInMainHand();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                off = inv.getItemInOffHand();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ★★ 主手不再是五骊矟 / 副手不再是五骊鞚 → 马与拴绳散场（也播化烟动画）
            if (!ListenerCore.gsIsItem(main, GS_WULI_ID) || !gsWuliIsRein(off)) {
                gsWuliDismiss(p, st, true, true);
                GS_WULI_STATE.remove(uuid);
                return;
            }

            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            // 队长马：丢了补一匹、换世界跟着换
            boolean leadBad = (st.lead == null);
            if (!leadBad) {
                try {
                    leadBad = st.lead.isDead() || !st.lead.isValid();
                } catch (Throwable e) {
                    leadBad = true;
                }
            }
            if (leadBad) {
                Location l0 = p.getLocation();
                st.lead = wlSpawnHorse(world, l0.getX(), l0.getY(), l0.getZ(), l0.getYaw(), true);
                try {
                    wlAlignMount(st.lead, p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            } else {
                try {
                    String lw = null;
                    try {
                        lw = String.valueOf(st.lead.getWorld().getName());
                    } catch (Throwable e) {
                        lw = null;
                    }
                    String pw = String.valueOf(world.getName());
                    if (lw != null && !lw.equals(pw)) {
                        st.lead.teleport(p.getLocation());
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // 护航马：缺了就补
            for (int i = 0; i < WL_FORM.length; i++) {
                SkeletonHorse h = (i < st.escorts.size()) ? st.escorts.get(i) : null;
                boolean ok = (h != null);
                if (ok) {
                    try {
                        ok = !(h.isDead() || !h.isValid());
                    } catch (Throwable e) {
                        ok = false;
                    }
                }
                if (!ok) {
                    Location l1 = p.getLocation();
                    st.escorts.set(i, wlSpawnHorse(world, l1.getX(), l1.getY(), l1.getZ(),
                            l1.getYaw(), false));
                }
            }

            // 骑上队长马（被弄下来了就再骑上去 —— F / 换掉金剑才是唯一的"下车"方式）
            try {
                Entity veh = p.getVehicle();
                boolean same = false;
                if (veh != null && st.lead != null) {
                    try {
                        same = ListenerCore.uid(veh).equals(ListenerCore.uid(st.lead));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                if (!same && st.lead != null) {
                    try {
                        p.leaveVehicle();
                    } catch (Throwable e) {
                        try {
                            p.eject();
                        } catch (Throwable e2) {
                            // 与原脚本一致：静默
                        }
                    }
                    // ★ 重新扶上马前先对齐坐骑朝向（否则镜头会被掰）
                    try {
                        wlAlignMount(st.lead, p);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        st.lead.addPassenger(p);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            if (st.lead == null) {
                return;
            }

            // ---- 分模式 ----
            if ("charge".equals(st.mode)) {
                // 蓄力：钉在原地（只允许转向，所以每拍把马的朝向刷成玩家视角）
                double pyaw;
                try {
                    pyaw = p.getLocation().getYaw();
                } catch (Throwable e) {
                    pyaw = st.anchorYaw;
                }
                st.heading = pyaw;
                try {
                    // ★ 蓄力的那 1 秒：五匹马全部"前蹄抬起"
                    st.lead.teleport(new Location(world, st.anchorX, st.anchorY, st.anchorZ,
                            (float) pyaw, (float) WL_REAR_PITCH));
                    st.lead.setVelocity(new Vector(0.0, 0.0, 0.0));
                    wlSetRear(st.lead, true);           // ★ 队长马也一起抬蹄
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                wlFollowFormation(st, world, st.anchorX, st.anchorY, st.anchorZ,
                        pyaw, WL_REAR_PITCH, true);
                wlChargeFx(p, st);
                // ★ 蓄力期间：头顶 2 格浮出一匹抬起前蹄的粒子马（冲刺一开始就没了）
                //   ★★ 第一刻 st.chargeFx 在 JS 里是 undefined ⇒ NaN % 1 ≠ 0 ⇒ 不画。
                if (WL_FXHORSE_ON && st.chargeFx != null && (st.chargeFx % WL_FXHORSE_EVERY) == 0) {
                    try {
                        Location bl2 = p.getLocation();
                        wlFxHorseFx(world, bl2.getX(), bl2.getY(), bl2.getZ(), bl2.getYaw());
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                st.chargeFx = (st.chargeFx == null ? 0 : st.chargeFx) + 1;
                if (ListenerCore.now() >= st.chargeEnd) {
                    gsWuliStartDash(p, st);
                }
                return;
            }
            if ("dash".equals(st.mode)) {
                return;                                  // 冲刺由 1 刻链自己推进
            }

            // 跟随：以队长马的位置 + 朝向摆 ^ 队形（平时四蹄落地 → pitch 0 / rear false）
            Location ll;
            try {
                ll = st.lead.getLocation();
            } catch (Throwable e) {
                ll = null;
            }
            if (ll != null) {
                wlSetRear(st.lead, false);
                wlFollowFormation(st, world, ll.getX(), ll.getY(), ll.getZ(),
                        ll.getYaw(), 0.0, false);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWuliPulse()} —— 每 {@link #WL_PULSE} 刻一次。 */
    public void gsWuliPulse() {
        try {
            if (!core.gsAuraIsCurrent()) {
                return;                                  // /rsc reload 后的旧任务 → 停手
            }
            // 惰性重试：附属加载期插件可能还没就绪，这里补注册
            if (!GS_WULI_REGISTERED) {
                gsWuliRegisterSync();
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    gsWuliTickPlayer(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 同步监听器注册（原脚本 4547~4808 行）
    // ======================================================================

    /** 手上是不是「五骊矟」+ 副手是不是「五骊鞚」（= 已开机）。 */
    private static Player armedPlayer(Player p) {
        try {
            if (p == null) {
                return null;
            }
            var inv = p.getInventory();
            if (!ListenerCore.gsIsItem(inv.getItemInMainHand(), GS_WULI_ID)) {
                return null;
            }
            if (!gsWuliIsRein(inv.getItemInOffHand())) {
                return null;
            }
            return p;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsWuliRegisterSync()} —— 幂等注册（原脚本是"每次都再注册一遍"）。
     *
     * @return 是否已注册
     */
    public boolean gsWuliRegisterSync() {
        if (GS_WULI_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new WuliListener(this), GS.plugin());
            GS_WULI_REGISTERED = true;
            try {
                Bukkit.getLogger().info(registerLine());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 五骊矟同步监听器注册异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    /**
     * 五骊矟的 11 条监听器。
     *
     * <p>★ 全部 {@code EventPriority.HIGHEST} 且 {@code ignoreCancelled = false}
     * （= 原脚本 {@code registerEvent} 只传 4 个参数时的默认值），逐处核对过。
     */
    private static final class WuliListener implements Listener {

        private final WuliSub sub;

        WuliSub get() {
            return sub;
        }

        WuliListener(WuliSub sub) {
            this.sub = sub;
        }

        /** ① F 键（换手事件）= 开机 / 关机。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSwapHands(PlayerSwapHandItemsEvent e) {
            try {
                if (e.isCancelled()) {
                    return;                          // 重复注册的实例 → 跳过
                }
                Player p = e.getPlayer();
                var inv = p.getInventory();
                // 主手是五骊矟 → 拦下原版换手，改走开机 / 关机
                if (ListenerCore.gsIsItem(inv.getItemInMainHand(), GS_WULI_ID)) {
                    e.setCancelled(true);
                    sub.gsWuliToggle(p);
                    return;
                }
                // 没拿五骊矟时：副手那根「五骊鞚」也不许被换走
                if (gsWuliIsRein(inv.getItemInOffHand())) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ② 鼠标左键 = 剑气；右键 = 冲锋。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = armedPlayer(e.getPlayer());
                if (p == null) {
                    return;
                }

                // 只认主手（副手也会派发同一个事件）
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (hs.length() > 0 && hs.contains("OFF")) {
                    return;
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }

                if (act.contains("RIGHT_CLICK")) {
                    // ★★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的事件），
                    //    但玩家真主手拿着五骊矟 → 这是本附属自己派发的假事件，放过。
                    //    ★ 这条"物品是空气就放过"的判据只对右键生效。
                    try {
                        ItemStack evItem = e.getItem();
                        String evName = (evItem == null)
                                ? "" : String.valueOf(evItem.getType().name());
                        if (evName.length() == 0 || evName.equals("AIR")) {
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }

                    e.setCancelled(true);            // 拦住原版右键（放方块 / 交互）
                    sub.gsWuliTryCharge(p);
                } else if (act.contains("LEFT_CLICK")) {
                    // ★★ 左键点到方块 → 改成"不对区块做任何交互"
                    if (WL_QI_NO_BLOCK && act.contains("BLOCK")) {
                        e.setCancelled(true);
                        try {
                            e.setUseInteractedBlock(Event.Result.DENY);
                            e.setUseItemInHand(Event.Result.DENY);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                    sub.gsWuliTryQi(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ②-b ★★ 左键的真正兜底：挥手动画（每次左键都会发一次挥手包）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                String type = "";
                try {
                    type = String.valueOf(e.getAnimationType().name());
                } catch (Throwable e2) {
                    type = "";
                }
                if (!type.equals("ARM_SWING")) {
                    return;                          // 只认主手挥手
                }
                Player pa = armedPlayer(e.getPlayer());
                if (pa == null) {
                    return;
                }
                sub.gsWuliTryQi(pa);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ②-c 手持五骊矟时彻底不碰方块（挖不动、也不出挖掘进度条）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (WL_QI_NO_BLOCK && armedPlayer(e.getPlayer()) != null) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ②-c 同上（BlockDamageEvent）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockDamage(BlockDamageEvent e) {
            try {
                if (WL_QI_NO_BLOCK && armedPlayer(e.getPlayer()) != null) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 左键打到生物（近战）→ 取消近战，改成斩出剑气。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamageByEntity(EntityDamageByEntityEvent e) {
            try {
                if (e.isCancelled()) {
                    return;
                }
                Entity d;
                try {
                    d = e.getDamager();
                } catch (Throwable e2) {
                    d = null;
                }
                if (d == null) {
                    return;
                }
                String dn;
                try {
                    dn = String.valueOf(d.getType().name());
                } catch (Throwable e2) {
                    return;
                }
                if (!dn.equals("PLAYER")) {
                    return;
                }
                Player p = armedPlayer((Player) d);  // 近战事件没有 getPlayer()，直接拿 damager
                if (p == null) {
                    return;
                }
                // ★ 脚本自己结算的伤害（剑气 / 冲锋 / 护腿光环）→ 放行
                if (Boolean.TRUE.equals(sub.GS_WULI_DAMAGING.get(ListenerCore.uid(p)))) {
                    return;
                }
                e.setCancelled(true);
                sub.gsWuliTryQi(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ④ 右击生物 → 拦住（主副手任一拿着五骊鞚就取消）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteractEntity(PlayerInteractEntityEvent e) {
            try {
                Player p = e.getPlayer();
                var inv = p.getInventory();
                if (gsWuliIsRein(inv.getItemInOffHand())
                        || gsWuliIsRein(inv.getItemInMainHand())) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ④-b 拴绳拴到栅栏：走的是 PlayerLeashEntityEvent，单独拦。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onLeashEntity(PlayerLeashEntityEvent e) {
            try {
                Player p = e.getPlayer();
                var inv = p.getInventory();
                if (gsWuliIsRein(inv.getItemInOffHand())
                        || gsWuliIsRein(inv.getItemInMainHand())) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ⑤ 「五骊鞚」不可以丢掉。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDropItem(PlayerDropItemEvent e) {
            try {
                ItemStack dropped = null;
                try {
                    dropped = e.getItemDrop().getItemStack();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                if (gsWuliIsRein(dropped)) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ⑤ 「五骊鞚」不可以被点击移动。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryClick(InventoryClickEvent e) {
            try {
                if (gsWuliIsRein(e.getCurrentItem()) || gsWuliIsRein(e.getCursor())) {
                    e.setCancelled(true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ⑤ 「五骊鞚」不可以被拖拽。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryDrag(InventoryDragEvent e) {
            try {
                Map<Integer, ItemStack> items;
                try {
                    items = e.getNewItems();
                } catch (Throwable e2) {
                    items = null;
                }
                if (items == null) {
                    return;
                }
                for (ItemStack it : items.values()) {
                    if (gsWuliIsRein(it)) {
                        e.setCancelled(true);
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 本子系统用到、但 ListenerCore 里没有的共享工具
    // ======================================================================

    /**
     * {@code gsScopeStash(p, stack)} —— 把副手原来的东西请出去：
     * 优先塞进背包，背包塞不下的部分掉在脚边（原脚本 1630~1647 行）。
     *
     * <p>★ 它定义在原脚本 1630 行（不在本子系统区间内），但<b>全脚本只有本子系统用到</b>
     * （3562 / 3609 行）⇒ 这里自带一份。若集成者要收进 {@code ListenerCore}，
     * 本类直接改调用即可。
     */
    static void gsScopeStash(Player p, ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            String n;
            try {
                n = String.valueOf(stack.getType().name());
            } catch (Throwable e) {
                n = "";
            }
            if (n.equals("AIR") || n.equals("CAVE_AIR") || n.equals("VOID_AIR")) {
                return;
            }
        } catch (Throwable e) {
            return;
        }
        try {
            var inv = p.getInventory();
            Map<Integer, ItemStack> rest = inv.addItem(stack.clone());
            if (rest != null && !rest.isEmpty()) {
                for (ItemStack it : rest.values()) {
                    try {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * RSC 全局函数 {@code sendMessage(p, msg)}（原脚本 4065 / 4083 行用）。
     *
     * <p>RSC 的实现是 {@code p.sendMessage(CMIChatColor.translate(parsePlaceholder(p, s)))}；
     * 本附属的两条消息（{@code &6冲锋！} / {@code &7奔袭调息，静待时机。}）里
     * <b>没有任何 {@code %player%} 或 PlaceholderAPI 占位符</b>，
     * 所以等价于 {@link Chat#t(String)}。
     */
    private static void sendMessage(Player p, String msg) {
        try {
            p.sendMessage(Chat.t(msg));
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 探针用的只读访问（不改可观察行为）
    // ======================================================================

    /** 当前已开机的玩家数（探针用）。 */
    public int stateCount() {
        return GS_WULI_STATE.size();
    }

    /** {@code GS_WULI_REGISTERED} 的当前值（探针用）。 */
    boolean isRegistered() {
        return GS_WULI_REGISTERED;
    }

    /** 供探针读的粒子候选名（与 JS 顶层 var 同名）。 */
    public static Set<String> particleCandidates() {
        return Set.of(WL_FXHORSE_PARTICLES);
    }
}
