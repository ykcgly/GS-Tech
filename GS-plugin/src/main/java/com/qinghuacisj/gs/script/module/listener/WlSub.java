package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.qinghuacisj.gs.util.JsNum;

/**
 * {@code gs_listener.js} 的「威龙」子系统（原脚本第 4835~5833 行）。
 *
 * <p><b>玩法</b>：主手铁锭「威龙」（{@code GS_WEILONG}）。
 * <ul>
 *   <li><b>左键</b> = 磁吸炸弹（7 格 · 3 秒引信 · 2 格半径 35 点 · 冷却 15 秒 · 1.5 格吸附）。</li>
 *   <li><b>右键</b> = 虎蹲炮（12 格 · 2 秒 · 3 格半径 1 点 + 击退 + 定身 4.5 秒 · 冷却 25 秒）。</li>
 *   <li><b>F</b> = 动力推进（水平 12 格 · 10 刻 · 冷却 8 秒 · <b>击杀任意生物立即清空</b>）。</li>
 *   <li>左键 / 右键 / 冲刺落地 → 速度 II 3 秒（自带 5 秒冷却）。</li>
 * </ul>
 *
 * <p>★★ <b>本类里几处不能"顺手改"的硬要求</b>：
 * <ol>
 *   <li><b>所有 TNT 永不引爆</b> —— 出生就 {@code setFuseTicks(200000)}，
 *       爆炸是<b>手搓演出</b>（{@code gsWlBoom} 里自己放粒子 / 音效 / 结算伤害），
 *       <b>绝不调 {@code createExplosion}</b> ⇒ 不炸方块 / 不炸展示框 / 不触发按钮。</li>
 *   <li><b>TNT 逐段碰撞检测（{@link #WL_BLOCK_STEP} = 0.25 格）</b> ——
 *       12 格一步跳过去会穿墙，必须拆小步；出生前还要先整段瞄一遍，
 *       视线被挡就把目标点截到障碍前面。</li>
 *   <li><b>手持时无法破坏方块</b>（{@link #WL_NO_BREAK}）—— 左键只用来扔炸弹，不误挖。</li>
 *   <li><b>冲刺只走水平方向</b>（{@link #WL_DASH2_FLAT}）—— 把视线水平分量归一化，
 *       抬头 / 低头只影响朝向，不影响冲刺方向。</li>
 *   <li><b>击杀任意生物即清空冲刺冷却</b> —— <b>不检查手里拿的是什么</b>。</li>
 *   <li>{@link #WL_HIT_PLAYERS} = false ⇒ 炸弹默认<b>不打玩家</b>（防误伤队友）。</li>
 * </ol>
 *
 * <p>★★ <b>监听器注册（与原脚本不同的唯一一处，且是修缺陷）</b>：
 * 原脚本用 {@code pm.registerEvent(...)} 在脚本里自己注册，{@code GS_WL_REGISTERED}
 * 是脚本级变量 ⇒ <b>每次 {@code /rsc reload} 都会再注册一遍</b>。
 * 原脚本为此专门写了 80ms 去抖 {@code gsWlClickOnce}（一次点击被处理 N 遍时，
 * 第二遍只会白送一句"CD"提示）。Java 版改成
 * {@code org.bukkit.event.Listener} + 幂等 {@code registerEvents}，
 * <b>去抖保留</b>（它同时防的是"右键放方块时客户端顺手发的挥手包被误判成左键"）。
 * <p>优先级与 {@code ignoreCancelled} 逐处核对：原脚本 {@code registerEvent} 只传 4 个参数
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 侧 {@code @EventHandler} 默认也是 false。
 * <p>★ 有一处<b>故意不检查 {@code e.isCancelled()}</b>：{@code PlayerInteractEvent}
 * —— 有些前置插件会先把 {@code *_CLICK_AIR} 取消掉，一检查就永远收不到
 * "对着空气按键" ⇒ 变成必须点方块才放得出来。这条在 Java 里<b>同样保留</b>。
 *
 * <p>★ <b>注册粒度差异</b>：原脚本逐个事件 {@code reg()}（一条失败其余照常），
 * Java 的 {@code registerEvents} 是一次全成功 / 全失败；本模块的 8 条监听器方法
 * 签名都是标准形式，不存在"某一条不合法"的情形。
 */
public final class WlSub {

    // ======================================================================
    // 物品 ID（原脚本 4840 行）
    // ======================================================================

    /** {@code GS_WL_ID} —— 威龙（铁锭）。 */
    public static final String GS_WL_ID = "GS_WEILONG";

    // ======================================================================
    // 磁吸炸弹（左键）（原脚本 4842~4854 行）
    // ======================================================================

    public static final double WL_BOMB_DIST = 7.0;
    public static final int WL_BOMB_FLY_TICKS = 14;
    public static final int WL_BOMB_FUSE_TICKS = 60;
    public static final double WL_BOMB_R = 3.0;
    public static final double WL_BOMB_DMG = 35.0;
    public static final int WL_BOMB_CD_MS = 15000;
    public static final double WL_BOMB_MAGNET_R = 1.5;
    public static final String WL_BOMB_MSG = "&a磁吸炸弹投出！";
    /** ★ {@code {s}} 会被替换成<b>剩余冷却秒数</b>（1 位小数）。 */
    public static final String WL_BOMB_MSG_CD = "&7磁吸炸弹CD &c{s}秒";
    public static final String[] WL_BOMB_SNDS_THROW =
            {"entity.player.levelup", "entity.experience_orb.pickup"};
    public static final String[] WL_BOOM_SNDS = {"entity.generic.explode"};
    public static final String[] WL_BOOM_PARTICLES =
            {"EXPLOSION_EMITTER", "EXPLOSION_LARGE", "EXPLOSION_HUGE", "SMOKE_LARGE"};

    // ======================================================================
    // 虎蹲炮（右键）（原脚本 4856~4871 行）
    // ======================================================================

    public static final double WL_CANNON_DIST = 12.0;
    public static final int WL_CANNON_FLY_TICKS = 18;
    public static final int WL_CANNON_FUSE_TICKS = 40;
    public static final double WL_CANNON_R = 3.0;
    public static final double WL_CANNON_DMG = 1.0;
    public static final double WL_CANNON_KB = 0.5;
    public static final double WL_CANNON_KB_UP = 0.18;
    public static final int WL_CANNON_STUN_MS = 4500;
    /** ★ 先让它被击退飞 0.25 秒，<b>之后</b>再定住（两个效果都看得见）。 */
    public static final int WL_CANNON_STUN_LOCK = 250;
    public static final int WL_CANNON_CD_MS = 25000;
    public static final String WL_CANNON_MSG_HIT = "&b目标失衡！";
    public static final String WL_CANNON_MSG_CD = "&7虎蹲炮CD &c{s}秒";
    public static final String[] WL_CANNON_SNDS_THROW =
            {"entity.wind_charge.throw", "entity.wind_charge.wind_burst", "entity.blaze.shoot"};
    public static final String[] WL_WIND_SNDS =
            {"entity.wind_charge.wind_burst", "entity.blaze.shoot"};
    public static final String[] WL_WIND_PARTICLES =
            {"WIND_BURST", "GUST", "GUST_EMITTER", "CLOUD", "SMOKE_NORMAL"};

    // ======================================================================
    // 动力推进（F 键）（原脚本 4873~4884 行）
    // ======================================================================

    public static final double WL_DASH2_DIST = 12.0;
    public static final int WL_DASH2_TICKS = 10;
    /** ★ 逐段碰撞检测的步长（12 格一步跳过去会穿墙，必须拆成小步）。 */
    public static final double WL_DASH2_STEP = 0.4;
    /** ★ 冲刺<b>只走水平方向</b>：抬头 / 低头都不会往上下冲。 */
    public static final boolean WL_DASH2_FLAT = true;
    /** true = 再吸附到东 / 南 / 西 / 北四个正方向之一（想格子化就开）。 */
    public static final boolean WL_DASH2_AXIS = false;
    public static final double WL_DASH2_HOP = 0.9;
    public static final int WL_DASH2_CD_MS = 8000;
    public static final String WL_DASH2_MSG = "&b动力推进";
    public static final String WL_DASH2_MSG_CD = "&7动力推进CD &c{s}秒";
    public static final String[] WL_DASH2_SNDS =
            {"entity.wind_charge.wind_burst", "entity.blaze.shoot"};
    /** 肩部持续冒的羊驼口水。 */
    public static final String[] WL_SPIT_PARTICLES = {"SPIT", "SMOKE_NORMAL", "CLOUD"};

    // ======================================================================
    // 加速 II（原脚本 4886~4894 行）
    // ======================================================================

    public static final int WL_SPEED_TICKS = 60;
    /** amplifier 1 = 速度 II。 */
    public static final int WL_SPEED_AMP = 1;
    public static final int WL_SPEED_CD_MS = 5000;
    public static final String[] WL_SPEED_SNDS = {"entity.blaze.shoot"};

    public static final boolean WL_HIT_PLAYERS = false;
    public static final boolean WL_NO_BREAK = true;
    /** ★ TNT 飞行轨迹的碰撞检测步长（防穿玻璃 / 穿墙）。 */
    public static final double WL_BLOCK_STEP = 0.25;

    // ======================================================================
    // 运行期状态（原脚本 4896~4905 行）
    // ======================================================================

    /** {@code GS_WL_CD_KEYS} —— 四个冷却键（惰性构造）。 */
    private Keys GS_WL_CD_KEYS;

    /** {@code GS_WL_BOMB_KEY} —— TNT 身上的标记。 */
    private NamespacedKey GS_WL_BOMB_KEY;

    /** {@code GS_WL_REGISTERED} —— 监听器闩锁。 */
    private boolean GS_WL_REGISTERED;

    /** {@code GS_WL_DAMAGING} —— uuid → true（这一下是本脚本结算的 ⇒ 近战拦截放行）。 */
    private final Map<String, Boolean> GS_WL_DAMAGING = new HashMap<>();

    /** {@code GS_WL_BOMBS} —— id → 炸弹状态。 */
    private final Map<String, Bomb> GS_WL_BOMBS = new HashMap<>();

    /** {@code GS_WL_BOMB_SEQ}。 */
    private long GS_WL_BOMB_SEQ = 0;

    /** {@code GS_WL_STUN} —— 实体 uuid → 定身状态。 */
    private final Map<String, Stun> GS_WL_STUN = new HashMap<>();

    /**
     * {@code GS_WL_CLICK_AT} —— {@code uuid|L} / {@code uuid|R} → 上次处理的毫秒。
     *
     * <p>★ {@code /rsc reload} 会重复注册监听器，不去抖的话一次点击会被处理 N 遍。
     */
    private final Map<String, Long> GS_WL_CLICK_AT = new HashMap<>();

    private final ListenerCore core;

    public WlSub(ListenerCore core) {
        this.core = core;
    }

    /** {@code GS_WL_CD_KEYS} 的载体。 */
    private static final class Keys {
        NamespacedKey bomb;
        NamespacedKey cannon;
        NamespacedKey dash;
        NamespacedKey speed;
    }

    /** {@code GS_WL_STUN[uuid]}。 */
    private static final class Stun {
        Entity ent;
        Location loc;
        long until;
        long lockAt;
        boolean ai;
    }

    /** {@code GS_WL_BOMBS[id]}。 */
    private static final class Bomb {
        long id;
        String kind;
        Player p;
        World world;
        TNTPrimed tnt;
        double sx, sy, sz, tx, ty, tz, cx, cy, cz;
        int flyTicks;
        int i;
        String phase = "fly";
        Entity attach;
        int fuse;
        int fallTicks;
        int stable;
        double lastY;
        boolean dead;
    }

    /** {@code gsWlDashTick} 的 {@code st}。 */
    private static final class Dash {
        Player p;
        World world;
        int i;
        int wait;
        boolean done;
        double x0, y0, z0;
        double px, py, pz;
        double dx, dy, dz;
        float yaw, pitch;
    }

    // ======================================================================
    // 键（原脚本 4907~4922 行）
    // ======================================================================

    /** {@code gsWlKeys()}。 */
    private Keys gsWlKeys() {
        if (GS_WL_CD_KEYS != null) {
            return GS_WL_CD_KEYS;
        }
        try {
            Keys k = new Keys();
            k.bomb = NamespacedKey.fromString("gs:wl_bomb_cd");
            k.cannon = NamespacedKey.fromString("gs:wl_cannon_cd");
            k.dash = NamespacedKey.fromString("gs:wl_dash_cd");
            k.speed = NamespacedKey.fromString("gs:wl_speed_cd");
            GS_WL_CD_KEYS = k;
            GS_WL_BOMB_KEY = NamespacedKey.fromString("gs:wl_tnt");
        } catch (Throwable e) {
            // ★ 与原脚本一致：这里**不设** GS_WL_BOMB_KEY
            Keys k = new Keys();
            GS_WL_CD_KEYS = k;
        }
        return GS_WL_CD_KEYS;
    }

    /** 按 kind 取键（{@code gsWlKeys()[kind]}）。 */
    private NamespacedKey keyOf(String kind) {
        Keys k = gsWlKeys();
        switch (kind) {
            case "bomb":
                return k.bomb;
            case "cannon":
                return k.cannon;
            case "dash":
                return k.dash;
            case "speed":
                return k.speed;
            default:
                return null;
        }
    }

    /** {@code gsWlIsWeilong(item)}。 */
    private static boolean gsWlIsWeilong(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_WL_ID);
    }

    /** {@code gsWlCdLeft(p, kind, cdMs)}。 */
    private long gsWlCdLeft(Player p, String kind, long cdMs) {
        try {
            return ListenerCore.gsWuliCdLeft(p, keyOf(kind), cdMs);
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsWlMarkCd(p, kind)}。 */
    private void gsWlMarkCd(Player p, String kind) {
        try {
            ListenerCore.gsWuliMarkCd(p, keyOf(kind));
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlClearCd(p, kind)}。 */
    private void gsWlClearCd(Player p, String kind) {
        try {
            NamespacedKey k = keyOf(kind);
            if (k == null) {
                return;
            }
            var pdc = p.getPersistentDataContainer();
            if (pdc != null) {
                pdc.remove(k);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlClickOnce(p, tag)} —— 一次点击只处理一遍（80ms 去抖）。 */
    private boolean gsWlClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_WL_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_WL_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * {@code gsWlCdMsg(base, leftMs)} —— 把「{@code {s}}」替换成剩余冷却秒数（1 位小数）。
     *
     * <p>★ 逐字复刻 JS：{@code Math.round(s * 10) / 10} 之后若是整数要<b>补 {@code .0}</b>
     * （JS 的 {@code '' + 15} 是 {@code "15"}，所以要手工补成 {@code "15.0"}）。
     */
    static String gsWlCdMsg(String base, long leftMs) {
        try {
            double s = leftMs / 1000.0;
            if (s < 0) {
                s = 0;
            }
            String txt = jsNum(Math.round(s * 10) / 10.0);
            if (txt.indexOf('.') < 0) {
                txt = txt + ".0";
            }
            // ★ JS 的 String.replace(字符串, 字符串) 只替换**第一处**
            int i = base.indexOf("{s}");
            if (i < 0) {
                return base;
            }
            return base.substring(0, i) + txt + base.substring(i + 3);
        } catch (Throwable e) {
            return base;
        }
    }

    /** JS 的 {@code '' + 数值} 语义（整数不带 {@code .0}）。 */
    static String jsNum(double v) {
        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。
        return JsNum.str(v);
    }

    /** {@code gsWlRecentRight(p, gapMs)} —— 刚刚（gapMs 内）是不是右键过。 */
    private boolean gsWlRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_WL_CLICK_AT.get(ListenerCore.uid(p) + "|R");
            if (last == null) {
                return false;
            }
            return (ListenerCore.now() - last) < gapMs;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsWlMsg(p, msg)} —— 与 RSC 的 {@code sendMessage} 等价（无占位符）。 */
    private static void gsWlMsg(Player p, String msg) {
        try {
            p.sendMessage(Chat.t(msg));
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlPlay(world, loc, names, vol, pit)} —— 候选链里第一个能播的。 */
    static void gsWlPlay(World world, Location loc, String[] names, float vol, float pit) {
        try {
            if (world == null || loc == null || names == null) {
                return;
            }
            for (String n : names) {
                try {
                    world.playSound(loc, n, vol, pit);
                    return;
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlParticle(names)} —— 取第一个存在的粒子类型。 */
    static Particle gsWlParticle(String[] names) {
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

    /** {@code gsWlBurst(world, x, y, z, names, count, ox, oy, oz, extra)}。 */
    private static void gsWlBurst(World world, double x, double y, double z, String[] names,
                                  int count, double ox, double oy, double oz, double extra) {
        try {
            Particle type = gsWlParticle(names);
            if (type == null || world == null) {
                return;
            }
            world.spawnParticle(type, x, y, z, count, ox, oy, oz, extra);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 目标筛选 / 伤害（原脚本 5012~5049 行）
    // ======================================================================

    /** {@code gsWlNotTarget(p, ent)}。 */
    private static boolean gsWlNotTarget(Player p, Entity ent) {
        try {
            if (ent == null) {
                return true;
            }
            if (!(ent instanceof Damageable)) {
                return true;                             // 只认有血量的活体
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
            if (tn.equals("PLAYER")) {
                if (!WL_HIT_PLAYERS) {
                    return true;                         // 默认不打玩家
                }
                if (p != null && ListenerCore.uid(ent).equals(ListenerCore.uid(p))) {
                    return true;
                }
            }
            if (ListenerCore.gsIsOurSummon(ent, WuliSub.GS_WULI_SUMMON_KEY)) {
                return true;
            }
            if (ListenerCore.gsIsMaiyouSummon(ent, WuliSub.GS_MAIYOU_KEY)) {
                return true;
            }
            return false;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsWlHurt(p, ent, dmg)}。 */
    private void gsWlHurt(Player p, Entity ent, double dmg) {
        try {
            if (ent == null) {
                return;
            }
            try {
                ((LivingEntity) ent).setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            GS_WL_DAMAGING.put(ListenerCore.uid(p), true);   // 放行近战拦截
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
                GS_WL_DAMAGING.put(ListenerCore.uid(p), false);
            }
        } catch (Throwable e) {
            try {
                GS_WL_DAMAGING.put(ListenerCore.uid(p), false);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code gsWlVel(ent, vx, vy, vz)}。 */
    private static void gsWlVel(Entity ent, double vx, double vy, double vz) {
        try {
            ent.setVelocity(new Vector(vx, vy, vz));
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlStun(ent, ms)} —— 击退先飞 0.25 秒，再锁死位置。 */
    private void gsWlStun(Entity ent, int ms) {
        try {
            if (ent == null) {
                return;
            }
            String tn;
            try {
                tn = ListenerCore.typeName(ent);
            } catch (Throwable e) {
                return;
            }
            if (tn.equals("PLAYER")) {
                return;                                  // 玩家不定身（默认也不打玩家）
            }
            boolean hadAi;
            try {
                hadAi = ((LivingEntity) ent).hasAI();
            } catch (Throwable e) {
                hadAi = true;
            }
            Stun s = new Stun();
            s.ent = ent;
            s.loc = null;
            s.ai = hadAi;
            s.lockAt = ListenerCore.now() + WL_CANNON_STUN_LOCK;
            s.until = ListenerCore.now() + ms;
            GS_WL_STUN.put(ListenerCore.uid(ent), s);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 常驻脉冲（原脚本 5070~5102 行）
    // ======================================================================

    /** {@code gsWlPulse()} —— 每 2 刻一次（★ 原脚本<b>没有</b> aura 世代检查）。 */
    public void gsWlPulse() {
        try {
            long now = ListenerCore.now();
            Iterator<Map.Entry<String, Stun>> it = GS_WL_STUN.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Stun> en = it.next();
                Stun s = en.getValue();
                if (s == null) {
                    it.remove();
                    continue;
                }
                Entity ent = s.ent;
                boolean alive;
                try {
                    alive = (ent != null && ent.isValid() && !ent.isDead());
                } catch (Throwable e) {
                    alive = false;
                }
                if (!alive || now >= s.until) {
                    try {
                        if (s.ai && alive) {
                            ((LivingEntity) ent).setAI(true);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    it.remove();
                    continue;
                }
                if (now < s.lockAt) {
                    continue;                            // 先让它被击退飞出去
                }
                if (s.loc == null) {
                    try {
                        s.loc = ent.getLocation().clone();
                        ((LivingEntity) ent).setAI(false);
                    } catch (Throwable e) {
                        continue;
                    }
                }
                gsWlVel(ent, 0.0, 0.0, 0.0);
                try {
                    ent.teleport(s.loc);                 // 位置锁死 = 定身
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // 兜底：清掉已经失效的炸弹条目
            Iterator<Map.Entry<String, Bomb>> bi = GS_WL_BOMBS.entrySet().iterator();
            while (bi.hasNext()) {
                Map.Entry<String, Bomb> en = bi.next();
                Bomb b = en.getValue();
                if (b == null || b.dead) {
                    bi.remove();
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 加速 II（原脚本 5105~5119 行）
    // ======================================================================

    /** {@code gsWlGrantSpeed(p)}。 */
    private void gsWlGrantSpeed(Player p) {
        try {
            if (p == null) {
                return;
            }
            if (gsWlCdLeft(p, "speed", WL_SPEED_CD_MS) > 0) {
                return;                                  // 冷却中静默
            }
            PotionEffectType t;
            try {
                t = PotionEffectType.SPEED;
            } catch (Throwable e) {
                t = null;
            }
            if (t == null) {
                try {
                    t = PotionEffectType.getByName("SPEED");
                } catch (Throwable e) {
                    t = null;
                }
            }
            if (t == null) {
                return;
            }
            p.addPotionEffect(new PotionEffect(t, WL_SPEED_TICKS, WL_SPEED_AMP, true, false));
            gsWlMarkCd(p, "speed");
            gsWlPlay(p.getWorld(), p.getLocation(), WL_SPEED_SNDS, 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 投出 TNT（原脚本 5121~5291 行）
    // ======================================================================

    /** {@code gsWlSpawnTnt(world, loc)}。 */
    private static TNTPrimed gsWlSpawnTnt(World world, Location loc) {
        try {
            EntityType t;
            try {
                t = EntityType.TNT;
            } catch (Throwable e) {
                t = null;
            }
            if (t == null) {
                try {
                    t = EntityType.valueOf("TNT");
                } catch (Throwable e) {
                    t = null;
                }
            }
            if (t == null) {
                return null;
            }
            Entity spawned = world.spawnEntity(loc, t);
            return (spawned instanceof TNTPrimed) ? (TNTPrimed) spawned : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsWlBlockedAt(world, x, y, z)} —— 这个坐标是不是"过不去"的？
     *
     * <p>★ 已由 {@link ListenerCore#gsWlBlockedAt} 提供（同一份实现），本类直接转发。
     */
    static boolean gsWlBlockedAt(World world, double x, double y, double z) {
        return ListenerCore.gsWlBlockedAt(world, x, y, z);
    }

    /** 射线命中点。 */
    static final class Hit {
        double x, y, z;

        Hit(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    /**
     * {@code gsWlRayHit(world, x0, y0, z0, x1, y1, z1)} —— 逐段扫描，
     * 撞到障碍就返回<b>撞击点之前</b>那个采样点，一路畅通返回 {@code null}。
     */
    static Hit gsWlRayHit(World world, double x0, double y0, double z0,
                          double x1, double y1, double z1) {
        try {
            double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-4) {
                return null;
            }
            long steps = (long) Math.ceil(len / WL_BLOCK_STEP);
            if (steps < 1) {
                steps = 1;
            }
            double lx = x0, ly = y0, lz = z0;
            for (long s = 1; s <= steps; s++) {
                double t = s / (double) steps;
                double px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t;
                if (gsWlBlockedAt(world, px, py, pz)) {
                    return new Hit(lx, ly, lz);
                }
                lx = px;
                ly = py;
                lz = pz;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsWlDashRay(...)} —— 同上，但按"玩家体积"判定（脚下 + 头部两处）。 */
    static Hit gsWlDashRay(World world, double x0, double y0, double z0,
                           double x1, double y1, double z1) {
        try {
            double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-4) {
                return null;
            }
            long steps = (long) Math.ceil(len / WL_DASH2_STEP);
            if (steps < 1) {
                steps = 1;
            }
            double lx = x0, ly = y0, lz = z0;
            for (long s = 1; s <= steps; s++) {
                double t = s / (double) steps;
                double px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t;
                if (gsWlBlockedAt(world, px, py + 0.1, pz)
                        || gsWlBlockedAt(world, px, py + 1.1, pz)) {
                    return new Hit(lx, ly, lz);
                }
                lx = px;
                ly = py;
                lz = pz;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsWlStuckAt(world, x, y, z)} —— 脚下 / 头部任一处过不去。 */
    static boolean gsWlStuckAt(World world, double x, double y, double z) {
        try {
            return gsWlBlockedAt(world, x, y + 0.1, z) || gsWlBlockedAt(world, x, y + 1.1, z);
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsWlSafeY(world, x, y, z)} —— 卡住就往上抬，最多找 6 格。 */
    static double gsWlSafeY(World world, double x, double y, double z) {
        try {
            if (!gsWlStuckAt(world, x, y, z)) {
                return y;
            }
            double yy = y;
            for (int s = 0; s < 6; s++) {
                yy += 1.0;
                if (!gsWlStuckAt(world, x, yy, z)) {
                    return yy;
                }
            }
            return y;
        } catch (Throwable e) {
            return y;
        }
    }

    /** {@code gsWlUnstuck(p)} —— 把陷进方块里的玩家拔出来。 */
    static void gsWlUnstuck(Player p) {
        try {
            Location l = p.getLocation();
            if (l == null) {
                return;
            }
            if (!gsWlStuckAt(l.getWorld(), l.getX(), l.getY(), l.getZ())) {
                return;
            }
            double sy = gsWlSafeY(l.getWorld(), l.getX(), l.getY(), l.getZ());
            try {
                p.teleport(new Location(l.getWorld(), l.getX(), sy, l.getZ(),
                        l.getYaw(), l.getPitch()));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                p.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlThrow(p, kind)}。 */
    private Bomb gsWlThrow(Player p, String kind) {
        try {
            World world = p.getWorld();
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            double dist = kind.equals("bomb") ? WL_BOMB_DIST : WL_CANNON_DIST;

            // ★ 出生点别推太远，免得贴着玻璃时直接生在玻璃后面
            double sx = eye.getX() + dir.getX() * 0.35;
            double sy = eye.getY() + dir.getY() * 0.35 - 0.2;
            double sz = eye.getZ() + dir.getZ() * 0.35;
            double tx = eye.getX() + dir.getX() * dist;
            double ty = eye.getY() + dir.getY() * dist;
            double tz = eye.getZ() + dir.getZ() * dist;

            // ★★ 出生前先整段瞄一遍：视线被墙 / 玻璃挡住 ⇒ 目标点直接截到障碍前面
            Hit aim = gsWlRayHit(world, sx, sy, sz, tx, ty, tz);
            if (aim != null) {
                tx = aim.x;
                ty = aim.y;
                tz = aim.z;
            }

            TNTPrimed tnt = gsWlSpawnTnt(world, new Location(world, sx, sy, sz));
            if (tnt == null) {
                return null;
            }

            // ★★ 永不引爆 + 不会被别的伤害清掉 + 不写盘
            try {
                tnt.setFuseTicks(200000);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                tnt.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                tnt.setGravity(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                tnt.setPersistent(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                gsWlKeys();
                if (GS_WL_BOMB_KEY != null) {
                    tnt.getPersistentDataContainer().set(
                            GS_WL_BOMB_KEY, PersistentDataType.STRING, kind);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            GS_WL_BOMB_SEQ++;
            Bomb b = new Bomb();
            b.id = GS_WL_BOMB_SEQ;
            b.kind = kind;
            b.p = p;
            b.world = world;
            b.tnt = tnt;
            b.sx = sx;
            b.sy = sy;
            b.sz = sz;
            b.tx = tx;
            b.ty = ty;
            b.tz = tz;
            b.cx = sx;
            b.cy = sy;
            b.cz = sz;                                   // ★ 当前坐标（逐段碰撞检测要用）
            b.flyTicks = kind.equals("bomb") ? WL_BOMB_FLY_TICKS : WL_CANNON_FLY_TICKS;
            b.i = 0;
            b.phase = "fly";
            b.attach = null;
            b.fuse = 0;
            b.fallTicks = 0;
            b.stable = 0;
            b.lastY = sy;
            b.dead = false;
            GS_WL_BOMBS.put(String.valueOf(b.id), b);

            // 抛出音效（★ 没有 TNT 点燃音：左键 = 获得经验，右键 = 风弹）
            if (kind.equals("bomb")) {
                gsWlPlay(world, p.getLocation(), WL_BOMB_SNDS_THROW, 1.0f, 1.3f);
            } else {
                gsWlPlay(world, p.getLocation(), WL_CANNON_SNDS_THROW, 1.0f, 1.0f);
            }

            GS.runLater(() -> gsWlBombTick(b), 0);
            return b;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsWlFindMagnet(tnt, p)} —— 找吸附半径内可以吸附的生物。 */
    private static Entity gsWlFindMagnet(TNTPrimed tnt, Player p) {
        try {
            Location l = tnt.getLocation();
            List<Entity> list = new ArrayList<>(tnt.getWorld().getNearbyEntities(
                    l, WL_BOMB_MAGNET_R, WL_BOMB_MAGNET_R, WL_BOMB_MAGNET_R));
            Entity best = null;
            double bestD = 1.0e9;
            for (Entity ent : list) {
                if (gsWlNotTarget(p, ent)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double dx = el.getX() - l.getX();
                double dy = (el.getY() + 0.9) - l.getY();
                double dz = el.getZ() - l.getZ();
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (d <= WL_BOMB_MAGNET_R && d < bestD) {
                    bestD = d;
                    best = ent;
                }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsWlBombEnd(b, remove)}。 */
    private void gsWlBombEnd(Bomb b, boolean remove) {
        try {
            if (b == null || b.dead) {
                return;
            }
            b.dead = true;
            GS_WL_BOMBS.remove(String.valueOf(b.id));
            if (remove && b.tnt != null) {
                try {
                    b.tnt.remove();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlBombTick(b)}。 */
    private void gsWlBombTick(Bomb b) {
        try {
            if (b == null || b.dead) {
                return;
            }
            TNTPrimed tnt = b.tnt;
            if (tnt == null) {
                gsWlBombEnd(b, false);
                return;
            }
            boolean alive;
            try {
                alive = tnt.isValid() && !tnt.isDead();
            } catch (Throwable e) {
                alive = false;
            }
            if (!alive) {
                gsWlBombEnd(b, false);
                return;
            }
            try {
                if (b.p == null || !b.p.isOnline()) {
                    gsWlBombEnd(b, true);
                    return;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ① 吸附（只有磁吸炸弹，且还没吸上）
            if (b.kind.equals("bomb") && b.attach == null) {
                Entity tgt = gsWlFindMagnet(tnt, b.p);
                if (tgt != null) {
                    b.attach = tgt;
                    if (!b.phase.equals("wait")) {
                        b.phase = "wait";
                        b.fuse = WL_BOMB_FUSE_TICKS;
                    }
                }
            }

            if (b.phase.equals("fly")) {
                // ② 飞行：脚本控制轨迹（每刻约 0.5 格 ⇒ 吸附判定不会漏）
                b.i++;
                double k = b.i / (double) b.flyTicks;
                if (k > 1) {
                    k = 1;
                }
                double fx = b.sx + (b.tx - b.sx) * k;
                double fy = b.sy + (b.ty - b.sy) * k + Math.sin(Math.PI * k) * 0.6;
                double fz = b.sz + (b.tz - b.sz) * k;

                // ★★ 逐段扫描这一段轨迹：撞到玻璃 / 墙 / 半砖… 就停在障碍前面
                Hit hit = gsWlRayHit(b.world, b.cx, b.cy, b.cz, fx, fy, fz);
                if (hit != null) {
                    b.cx = hit.x;
                    b.cy = hit.y;
                    b.cz = hit.z;
                    try {
                        tnt.teleport(new Location(b.world, b.cx, b.cy, b.cz));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    b.phase = "fall";                    // 撞上了 ⇒ 就地开始下落
                    b.fallTicks = 0;
                    b.lastY = b.cy;
                    try {
                        tnt.setGravity(true);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    gsWlVel(tnt, 0.0, -0.05, 0.0);
                } else {
                    b.cx = fx;
                    b.cy = fy;
                    b.cz = fz;
                    try {
                        tnt.teleport(new Location(b.world, b.cx, b.cy, b.cz));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    if (b.i >= b.flyTicks) {
                        b.phase = "fall";
                        b.fallTicks = 0;
                        b.lastY = b.cy;
                        try {
                            tnt.setGravity(true);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                        gsWlVel(tnt, 0.0, -0.05, 0.0);
                    }
                }
            } else if (b.phase.equals("fall")) {
                // ③ 下落：等它真正落地（真 · 落地判定：onGround 或连续两刻不动）
                b.fallTicks++;
                double y;
                try {
                    y = tnt.getLocation().getY();
                } catch (Throwable e) {
                    y = b.lastY;
                }
                if (Math.abs(y - b.lastY) < 0.001) {
                    b.stable++;
                } else {
                    b.stable = 0;
                }
                b.lastY = y;
                boolean onGround;
                try {
                    onGround = tnt.isOnGround();
                } catch (Throwable e) {
                    onGround = false;
                }
                if (onGround || b.stable >= 2 || b.fallTicks > 60) {
                    b.phase = "wait";
                    b.fuse = b.kind.equals("bomb") ? WL_BOMB_FUSE_TICKS : WL_CANNON_FUSE_TICKS;
                }
            } else if (b.phase.equals("wait")) {
                // ④ 计时中：吸住了就跟着目标走
                if (b.attach != null) {
                    try {
                        if (!b.attach.isValid() || b.attach.isDead()) {
                            b.attach = null;
                        } else {
                            Location al = b.attach.getLocation();
                            tnt.teleport(new Location(b.world,
                                    al.getX(), al.getY() + 0.9, al.getZ()));
                        }
                    } catch (Throwable e) {
                        b.attach = null;
                    }
                }
                b.fuse--;
                if (b.fuse <= 0) {
                    gsWlBoom(b);
                    return;
                }
            }

            GS.runLater(() -> gsWlBombTick(b), 1);
        } catch (Throwable e) {
            try {
                gsWlBombEnd(b, true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsWlBoom(b)} —— 爆炸（手搓演出，<b>绝不 createExplosion</b>）。
     */
    private void gsWlBoom(Bomb b) {
        try {
            if (b == null || b.dead) {
                return;
            }
            b.dead = true;
            GS_WL_BOMBS.remove(String.valueOf(b.id));

            TNTPrimed tnt = b.tnt;
            World world = b.world;
            Player p = b.p;
            Location l;
            try {
                l = tnt.getLocation();
            } catch (Throwable e) {
                l = null;
            }
            try {
                tnt.remove();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (l == null || world == null) {
                return;
            }

            double x = l.getX(), y = l.getY(), z = l.getZ();
            boolean isBomb = b.kind.equals("bomb");
            double r = isBomb ? WL_BOMB_R : WL_CANNON_R;
            double dmg = isBomb ? WL_BOMB_DMG : WL_CANNON_DMG;

            // ① 演出：粒子 + 音效
            if (isBomb) {
                gsWlBurst(world, x, y, z, WL_BOOM_PARTICLES, 26, 0.9, 0.9, 0.9, 0.12);
                gsWlPlay(world, l, WL_BOOM_SNDS, 2.5f, 0.9f);
            } else {
                gsWlBurst(world, x, y, z, WL_WIND_PARTICLES, 45, 1.3, 1.3, 1.3, 0.05);
                gsWlPlay(world, l, WL_WIND_SNDS, 2.0f, 1.0f);
            }

            // ② 结算伤害 / 击退 / 定身
            int hit = 0;
            List<Entity> list;
            try {
                list = new ArrayList<>(world.getNearbyEntities(
                        new Location(world, x, y, z), r, r, r));
            } catch (Throwable e) {
                list = null;
            }

            if (list != null) {
                for (Entity ent : list) {
                    if (gsWlNotTarget(p, ent)) {
                        continue;
                    }
                    Location el;
                    try {
                        el = ent.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    double dx = el.getX() - x;
                    double dy = (el.getY() + 0.9) - y;
                    double dz = el.getZ() - z;
                    double d2 = dx * dx + dy * dy + dz * dz;
                    if (d2 > r * r) {
                        continue;
                    }

                    hit++;
                    gsWlHurt(p, ent, dmg);

                    if (!isBomb) {
                        double len = Math.sqrt(d2);
                        double ux = dx, uy = dy, uz = dz;
                        if (len < 1.0e-4) {
                            ux = 0.0;
                            uy = 1.0;
                            uz = 0.0;
                            len = 1.0;
                        }
                        ux /= len;
                        uy /= len;
                        uz /= len;
                        gsWlVel(ent, ux * WL_CANNON_KB,
                                uy * WL_CANNON_KB * 0.4 + WL_CANNON_KB_UP,
                                uz * WL_CANNON_KB);
                        gsWlStun(ent, WL_CANNON_STUN_MS);
                    }
                }
            }

            // ③ 提示：虎蹲炮命中了才提示
            if (!isBomb && hit > 0 && p != null) {
                gsWlMsg(p, WL_CANNON_MSG_HIT);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 三个入口（原脚本 5479~5618 行）
    // ======================================================================

    /** {@code gsWlTryBomb(p)} —— 左键：磁吸炸弹。 */
    public void gsWlTryBomb(Player p) {
        try {
            long bl = gsWlCdLeft(p, "bomb", WL_BOMB_CD_MS);
            if (bl > 0) {
                gsWlMsg(p, gsWlCdMsg(WL_BOMB_MSG_CD, bl));
                return;
            }
            Bomb b = gsWlThrow(p, "bomb");
            if (b == null) {
                return;
            }
            gsWlMarkCd(p, "bomb");
            gsWlMsg(p, WL_BOMB_MSG);
            gsWlGrantSpeed(p);                           // 检测到左键 → 速度 II
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlTryCannon(p)} —— 右键：虎蹲炮。 */
    public void gsWlTryCannon(Player p) {
        try {
            long cl = gsWlCdLeft(p, "cannon", WL_CANNON_CD_MS);
            if (cl > 0) {
                gsWlMsg(p, gsWlCdMsg(WL_CANNON_MSG_CD, cl));
                return;
            }
            Bomb b = gsWlThrow(p, "cannon");
            if (b == null) {
                return;
            }
            gsWlMarkCd(p, "cannon");
            gsWlGrantSpeed(p);                           // 检测到右键 → 速度 II
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlSpit(p)} —— 肩部冒羊驼口水。 */
    private static void gsWlSpit(Player p) {
        try {
            Location l = p.getLocation();
            gsWlBurst(p.getWorld(), l.getX(), l.getY() + 1.35, l.getZ(),
                    WL_SPIT_PARTICLES, 3, 0.12, 0.12, 0.12, 0.02);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlTryDash(p)} —— F 键：动力推进。 */
    public void gsWlTryDash(Player p) {
        try {
            long dl = gsWlCdLeft(p, "dash", WL_DASH2_CD_MS);
            if (dl > 0) {
                gsWlMsg(p, gsWlCdMsg(WL_DASH2_MSG_CD, dl));
                return;
            }
            gsWlMarkCd(p, "dash");
            gsWlMsg(p, WL_DASH2_MSG);

            World world = p.getWorld();
            Location l = p.getLocation();
            double dx = 0.0, dy = 0.0, dz = 0.0;

            if (WL_DASH2_FLAT) {
                // ★★ 冲刺只走水平方向：把视线的水平分量归一化
                Vector dir = p.getEyeLocation().getDirection();
                double hx = dir.getX(), hz = dir.getZ();
                double hlen = Math.sqrt(hx * hx + hz * hz);
                if (hlen < 1.0e-4) {                     // 视线几乎垂直 ⇒ 用 yaw 兜底
                    double yr = l.getYaw() * Math.PI / 180.0;
                    hx = -Math.sin(yr);
                    hz = Math.cos(yr);
                    hlen = 1.0;
                }
                dx = hx / hlen;
                dz = hz / hlen;
                dy = 0.0;
                if (WL_DASH2_AXIS) {                     // 可选：吸附到东南西北
                    if (Math.abs(dx) >= Math.abs(dz)) {
                        dx = (dx >= 0 ? 1.0 : -1.0);
                        dz = 0.0;
                    } else {
                        dz = (dz >= 0 ? 1.0 : -1.0);
                        dx = 0.0;
                    }
                }
            } else {
                Vector d3 = p.getEyeLocation().getDirection();
                dx = d3.getX();
                dy = d3.getY();
                dz = d3.getZ();                          // 老行为：连视线俯仰一起冲
            }

            Dash st = new Dash();
            st.p = p;
            st.world = world;
            st.i = 0;
            st.wait = 0;
            st.done = false;
            st.x0 = l.getX();
            st.y0 = l.getY();
            st.z0 = l.getZ();
            st.px = l.getX();
            st.py = l.getY();
            st.pz = l.getZ();                            // ★ 上一刻的位置
            st.dx = dx;
            st.dy = dy;
            st.dz = dz;
            st.yaw = l.getYaw();
            st.pitch = l.getPitch();

            gsWlPlay(world, l, WL_DASH2_SNDS, 1.2f, 1.0f);   // 一个风弹的音效
            GS.runLater(() -> gsWlDashTick(p, st), 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsWlDashTick(p, st)}。 */
    private void gsWlDashTick(Player p, Dash st) {
        try {
            if (st == null || st.done) {
                return;
            }
            try {
                if (!p.isOnline()) {
                    st.done = true;
                    return;
                }
            } catch (Throwable e) {
                return;
            }

            if (st.i < WL_DASH2_TICKS) {
                st.i++;
                double k = st.i / (double) WL_DASH2_TICKS;
                double nx = st.x0 + st.dx * WL_DASH2_DIST * k;
                double ny = st.y0 + st.dy * WL_DASH2_DIST * k + Math.sin(Math.PI * k) * WL_DASH2_HOP;
                double nz = st.z0 + st.dz * WL_DASH2_DIST * k;
                ny = gsWlSafeY(st.world, nx, ny, nz);    // ★ 落点在方块里 ⇒ 抬到能站的高度

                // ★★ 12 格不能一步跳过去（会穿墙）⇒ 逐段扫一遍，撞墙就停在墙前面
                Hit hit = gsWlDashRay(st.world, st.px, st.py, st.pz, nx, ny, nz);
                if (hit != null) {
                    double hy = gsWlSafeY(st.world, hit.x, hit.y, hit.z);
                    try {
                        p.teleport(new Location(st.world, hit.x, hy, hit.z, st.yaw, st.pitch));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    st.px = hit.x;
                    st.py = hy;
                    st.pz = hit.z;
                    try {
                        p.setFallDistance(0.0f);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    gsWlSpit(p);
                    st.i = WL_DASH2_TICKS;               // 撞墙 ⇒ 提前收尾（进"等落地"）
                    GS.runLater(() -> gsWlDashTick(p, st), 1);
                    return;
                }
                st.px = nx;
                st.py = ny;
                st.pz = nz;
                try {
                    p.teleport(new Location(st.world, nx, ny, nz, st.yaw, st.pitch));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                gsWlUnstuck(p);                          // ★ 双保险：真陷进去了立刻拔出来
                try {
                    p.setFallDistance(0.0f);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                gsWlSpit(p);
                GS.runLater(() -> gsWlDashTick(p, st), 1);
                return;
            }

            // 冲刺动作走完 → 等落地（最多 40 刻 = 2 秒）→ 落地那一刻给速度 II
            gsWlUnstuck(p);
            boolean onGround;
            try {
                onGround = p.isOnGround();
            } catch (Throwable e) {
                onGround = true;
            }
            st.wait++;
            if (onGround || st.wait > 40) {
                st.done = true;
                try {
                    p.setFallDistance(0.0f);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                gsWlUnstuck(p);
                gsWlSpit(p);
                gsWlGrantSpeed(p);
                return;
            }
            try {
                p.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            gsWlSpit(p);
            GS.runLater(() -> gsWlDashTick(p, st), 1);
        } catch (Throwable e) {
            try {
                if (st != null) {
                    st.done = true;
                }
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            try {
                gsWlGrantSpeed(p);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code gsWlCleanupPlayer(p)} —— 玩家下线 / 死亡：收掉还在空中的 TNT。 */
    public void gsWlCleanupPlayer(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            List<Bomb> mine = new ArrayList<>();
            for (Bomb b : GS_WL_BOMBS.values()) {
                if (b == null) {
                    continue;
                }
                boolean same;
                try {
                    same = (b.p != null && ListenerCore.uid(b.p).equals(uuid));
                } catch (Throwable e) {
                    same = false;
                }
                if (same) {
                    mine.add(b);
                }
            }
            for (Bomb b : mine) {
                gsWlBombEnd(b, true);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 启动 + 日志行（原脚本 5810~5833 行）
    // ======================================================================

    /** 对应原脚本 {@code gsWlStart()}。 */
    public void start() {
        try {
            GS.runRepeating(this::gsWlPulse, 2, 2);
            gsWlRegisterSync();
            ListenerCore.gsDaLog(msgBomb());
            ListenerCore.gsDaLog(msgCannon());
            ListenerCore.gsDaLog(msgDash());
            ListenerCore.gsDaLog(msgSpeed());
            ListenerCore.gsDaLog(msgNoBoom());
            ListenerCore.gsDaLog(msgStep());
            ListenerCore.gsDaLog(msgNoBreak());
            ListenerCore.gsDaLog(msgFlat());
            ListenerCore.gsDaLog(msgKillClear());
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 威龙启动失败: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code [构思技艺] 威龙监听器注册成功（左键=磁吸炸弹 · 右键=虎蹲炮 · F=动力推进）} */
    public static String registerLine() {
        return ListenerCore.PREFIX + "威龙监听器注册成功（左键=磁吸炸弹 · 右键=虎蹲炮 · F=动力推进）";
    }

    /** 原脚本 5814~5815 行。★ 注意正文里的「2 格」是<b>原样硬编码</b>的（不是 {@link #WL_BOMB_R}）。 */
    public static String msgBomb() {
        return "威龙：磁吸炸弹已启用（7 格 · 3 秒 · 2 格 " + jsNum(WL_BOMB_DMG) + " 点 · 冷却 "
                + jsNum(WL_BOMB_CD_MS / 1000.0) + " 秒 · 吸附 " + jsNum(WL_BOMB_MAGNET_R) + " 格）";
    }

    /** 原脚本 5816~5817 行。 */
    public static String msgCannon() {
        return "威龙：虎蹲炮已启用（12 格 · 2 秒 · 3 格 " + jsNum(WL_CANNON_DMG)
                + " 点 + 击退 + 定身 " + jsNum(WL_CANNON_STUN_MS / 1000.0) + " 秒 · 冷却 "
                + jsNum(WL_CANNON_CD_MS / 1000.0) + " 秒）";
    }

    /** 原脚本 5818~5819 行。 */
    public static String msgDash() {
        return "威龙：动力推进已启用（F 键 · 水平 " + jsNum(WL_DASH2_DIST) + " 格 · 冷却 "
                + jsNum(WL_DASH2_CD_MS / 1000.0) + " 秒 · 击杀生物立即清空冷却）";
    }

    /** 原脚本 5820~5821 行。 */
    public static String msgSpeed() {
        return "威龙：左键/右键/冲刺落地 → 速度 II " + jsNum(WL_SPEED_TICKS / 20.0)
                + " 秒（自带 " + jsNum(WL_SPEED_CD_MS / 1000.0) + " 秒冷却 · 烈焰人火球音）";
    }

    /** 原脚本 5822 行。 */
    public static String msgNoBoom() {
        return "威龙：所有 TNT **永不引爆**、爆炸为手搓演出 ⇒ 不炸方块 / 不炸展示框 / 不触发按钮";
    }

    /** 原脚本 5823 行。 */
    public static String msgStep() {
        return "威龙：TNT 逐段碰撞检测（" + jsNum(WL_BLOCK_STEP) + " 格）⇒ 不穿玻璃 / 不穿墙";
    }

    /** 原脚本 5824 行。 */
    public static String msgNoBreak() {
        return "威龙：手持时**无法破坏方块**（左键 / 右键都不动方块），按键即放、不用点方块";
    }

    /** 原脚本 5825~5826 行。 */
    public static String msgFlat() {
        return "威龙：冲刺**只走水平方向**（抬头 / 低头不会往上下冲"
                + (WL_DASH2_AXIS ? "，并吸附到东/南/西/北" : "") + "）";
    }

    /** 原脚本 5827 行。 */
    public static String msgKillClear() {
        return "威龙：**击杀任意生物即清空冲刺冷却**（不要求手持威龙）";
    }

    // ======================================================================
    // 同步监听器注册（原脚本 5635~5808 行）
    // ======================================================================

    /**
     * {@code gsWlRegisterSync()} —— 幂等注册（原脚本是"每次都再注册一遍"）。
     *
     * @return 是否已注册
     */
    public boolean gsWlRegisterSync() {
        if (GS_WL_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new WlListener(this), GS.plugin());
            GS_WL_REGISTERED = true;
            try {
                Bukkit.getLogger().info(registerLine());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 威龙同步监听器注册异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    /**
     * 威龙的 8 条监听器。
     *
     * <p>★ 全部 {@code EventPriority.HIGHEST} 且 {@code ignoreCancelled = false}。
     */
    private static final class WlListener implements Listener {

        private final WlSub sub;

        WlListener(WlSub sub) {
            this.sub = sub;
        }

        /** ① F 键（换手事件）= 动力推进（★ 拦下原版换手）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSwapHands(PlayerSwapHandItemsEvent e) {
            try {
                if (e.isCancelled()) {
                    return;                          // 重复注册的实例 → 跳过
                }
                Player p = e.getPlayer();
                if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
                sub.gsWlTryDash(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② 左键 = 磁吸炸弹；右键 = 虎蹲炮。
         *
         * <p>★ <b>故意不检查 {@code e.isCancelled()}</b>：有些前置插件会先把
         * {@code *_CLICK_AIR} 取消掉，一检查就永远收不到"对着空气按键"。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) {
                    return;
                }
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (hs.length() > 0 && hs.contains("OFF")) {
                    return;                          // 只认主手
                }
                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }
                if (act.contains("RIGHT_CLICK")) {
                    if (!sub.gsWlClickOnce(p, "R")) {
                        return;                      // ★ 一次点击只处理一遍
                    }
                    e.setCancelled(true);            // 拦住原版交互
                    sub.gsWlTryCannon(p);
                } else if (act.contains("LEFT_CLICK")) {
                    if (!sub.gsWlClickOnce(p, "L")) {
                        return;
                    }
                    // ★ 手持威龙挖不动方块 ⇒ 左键点方块也一并取消
                    if (WL_NO_BREAK) {
                        e.setCancelled(true);
                    }
                    sub.gsWlTryBomb(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ②-b ★ 左键兜底：挥手动画（对着空气点也能收到）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                Player p = e.getPlayer();
                if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) {
                    return;
                }
                // 只认"挥手"这一种动画（老版本没有 getAnimationType()，拿不到就当它是挥手）
                try {
                    var at = e.getAnimationType();
                    if (at != null) {
                        String an = String.valueOf(at.name());
                        if (!an.contains("SWING") && !an.contains("ARM")) {
                            return;
                        }
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (sub.gsWlRecentRight(p, 300)) {
                    return;                          // ★ 右键附带的动画 → 忽略
                }
                if (!sub.gsWlClickOnce(p, "L")) {
                    return;                          // ★ 与 ② 共用去抖 ⇒ 不会放两次
                }
                sub.gsWlTryBomb(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ②-c ★ 手持威龙 → 无法破坏方块（双保险）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!WL_NO_BREAK) {
                    return;
                }
                if (!gsWlIsWeilong(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 近战：手持威龙时一律取消（左键只用来扔炸弹）。 */
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
                    return;
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
                Player pl = (Player) d;
                if (!gsWlIsWeilong(pl.getInventory().getItemInMainHand())) {
                    return;
                }
                if (Boolean.TRUE.equals(sub.GS_WL_DAMAGING.get(ListenerCore.uid(pl)))) {
                    return;                          // 自己结算的伤害放行
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ④ 击杀生物 → 立刻清空冲刺冷却。
         *
         * <p>★ <b>不检查物品</b>：不管手里拿的是什么，杀了生物就清空冲刺冷却。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onEntityDeath(EntityDeathEvent e) {
            try {
                LivingEntity victim = e.getEntity();
                Player killer;
                try {
                    killer = victim.getKiller();
                } catch (Throwable e2) {
                    killer = null;
                }
                if (killer == null) {
                    return;
                }
                sub.gsWlClearCd(killer, "dash");
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ⑤ 下线 → 收掉还在空中的 TNT。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onQuit(PlayerQuitEvent e) {
            try {
                sub.gsWlCleanupPlayer(e.getPlayer());
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }

        /** ⑤ 死亡 → 收掉还在空中的 TNT。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(PlayerDeathEvent e) {
            try {
                sub.gsWlCleanupPlayer(e.getEntity());
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 探针用的只读访问
    // ======================================================================

    /** 当前在飞的炸弹数（探针用）。 */
    public int bombCount() {
        return GS_WL_BOMBS.size();
    }

    /** {@code GS_WL_REGISTERED} 的当前值（探针用）。 */
    boolean isRegistered() {
        return GS_WL_REGISTERED;
    }

    /** 供探针读的冷却文案（纯函数，无副作用）。 */
    public static String cdMsg(String base, long leftMs) {
        return gsWlCdMsg(base, leftMs);
    }

    /** 供探针读的属性实例工具（未使用则返回 null）。 */
    static AttributeInstance attr(Entity ent, Attribute a) {
        return ListenerCore.attr(ent, a);
    }
}
