package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.DragonFireball;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.qinghuacisj.gs.util.JsNum;

/**
 * {@code gs_boss_shadow.js} 的 Java 化版本 —— 三阶段 boss「暗影虫卵」（{@code GS_SHADOW_EGG}）。
 *
 * <p>手持本物品<b>右键</b>召唤（{@code SB_CONSUME = true} ⇒ 召唤成功才扣 1 个），
 * 一条线一个玩家，三阶段全死才能再召：
 * <ol>
 *   <li><b>一阶段</b> 末影螨「暗影巢虫」：血 150、伤害 16、每次打中玩家给<b>凋零 II 5 秒</b></li>
 *   <li><b>二阶段</b> 末影人「暗影猎手」：血 650、伤害 25、每 8 秒一发<b>追踪龙息弹</b>（伤害 40、
 *       半径 3、<b>不留龙息团</b>）、每 11 秒把玩家瞬移到身前（<b>独立冷却</b>、开场先来一次）</li>
 *   <li><b>三阶段</b> 末影人「暗影女皇」：血 1000、伤害 33、每 7 秒瞬移玩家、每 10 秒弹道箭
 *       （伤害 20 ⇒ 落点生成「暗影蠕虫」血 30、接触女皇死亡并回 77 血）、
 *       每 15 秒脚下 4 只「暗影蛆虫」（血 90、伤害 8、活 15 秒，死时留<b>原版那团龙息</b>）</li>
 * </ol>
 *
 * <p>★★ <b>核心行为 ①：阶段推进用「前一阶段的死亡位置」</b>。
 * 死亡事件里把<b>精确</b>死亡坐标写进 {@code ref.dx/dy/dz}（最准），主循环每拍再写一份
 * 「活着的最后位置」兜底 ⇒ {@code sbPhaseEnd} 优先用前者、没有才退回召唤点。
 * <b>掉落点用的也是同一组坐标</b>（二阶段掉 1 把 {@code GS_VOICELESS}、
 * 三阶段掉 6 个 {@code GS_SHADOW_ORB}、一阶段不掉）。
 *
 * <p>★★ <b>核心行为 ②：「龙息团」= 原版末影龙喷出的那团</b>
 * （{@code AreaEffectCloud}）。建云的 7 步逐条对齐 1.21.11 {@code DragonFireball#onHit}，
 * 但<b>范围与时长按用户给的值</b>：半径<b>固定 4</b>（{@code radiusPerTick = 0}）、
 * 存在 <b>8 秒 = 160 刻</b>、{@code INSTANT_DAMAGE} 等级 1（= {@code 6 << 1} = 12 点）、
 * {@code waitTime} <b>保持类默认 20</b>（故意不调 {@code setWaitTime}）。
 * 唯一在 Bukkit 侧没有对应 API 的是 {@code setPotionDurationScale(0.25f)} —— 它只缩放
 * 「药水效果的持续时间」，而这里唯一的效果是瞬间伤害（duration 1）⇒ 缩放与否无可观察差异。
 * <br>★★ 原版那团云<b>六亲不认</b>，会连女皇和她自己的蛆虫一起烤 ⇒
 * 「只对玩家造成伤害 / 不伤自己人」靠 {@code sbCloudGuard()}：受击者<b>是我们这条线上的人</b>
 * 且伤害来源是<b>我们打过记号的云</b>（或云的主人）⇒ 取消。玩家那边一点不受影响。
 *
 * <p>★★ <b>反制 5 条</b>（逐条对应一个监听器）：
 * <ol>
 *   <li><b>末影人不索敌我们的末影螨</b>：原版 {@code EnderMan.registerGoals()} 里有
 *       {@code NearestAttackableTargetGoal<Endermite>}（<b>无条件</b>、与 {@code isPlayerSpawned} 无关）
 *       ⇒ 监听 {@code EntityTargetLivingEntityEvent} + {@code EntityTargetEvent}，
 *       目标是我们的末影螨就取消。</li>
 *   <li><b>boss 不乱传送</b>：{@code Entity#randomTeleport} 会发 {@code EntityTeleportEvent}
 *       并检查 {@code isCancelled()} ⇒ 我们的 boss2/boss3 一律取消。
 *       （我们自己的技能只传送<b>玩家</b>，走 {@code PlayerTeleportEvent}，不受影响。）</li>
 *   <li><b>boss 不搬方块</b>：{@code EntityChangeBlockEvent} + {@code sbOurs} ⇒ 取消。</li>
 *   <li><b>弹体不炸地形</b>：{@code EntityExplodeEvent} + {@code sbOurs} ⇒ 取消 + 清 blockList。</li>
 *   <li><b>龙息不伤自己人</b>：{@code sbCloudGuard()}（见上）。</li>
 * </ol>
 *
 * <p>★ <b>「不会留下龙息团」靠取消 {@code ProjectileHitEvent}</b>（不是 {@code remove()}）：
 * 1.21.11 的 {@code Projectile#preHitTargetOrDeflectSelf} 在事件被取消且命中类型不是 BLOCK 时
 * <b>直接返回</b>，NMS 的 {@code onHit} 整个不跑 ⇒ 既没有云也没有原版伤害，
 * 伤害 40 完全由 {@code sbBreathBoom} 自己结算。
 *
 * <p>★★★ <b>本模块<b>不</b>打 {@code gs_boss_shadow 已加载}</b>：
 * 原脚本全文<b>没有</b>这行（实测 {@code Select-String '已加载'} 零命中），
 * 参考服日志里也没有。本脚本的加载标记是那 6 行参数自检（见 {@link #loadLines()}）——
 * 打了「已加载」就是<b>比真 JS 多一条可观察日志</b>。
 *
 * <p>★ <b>不照抄的死代码</b>（逐条实测过，删掉不改变可观察行为）：
 * <ol>
 *   <li>{@code sbDist(a, b)} —— 定义了<b>从未被调用</b>（全文只有定义处 1 次出现）。</li>
 *   <li>{@code sbPassableAt} 里的 {@code if (mt != null) { if (!mt.isSolid()) return true; }}
 *       —— 上面那行 {@code try { return !!blk.isPassable(); } catch (e) { }} 在 1.21.11 上
 *       <b>必定成功返回</b>（{@code Block#isPassable()} 存在，已 javap 核对），
 *       所以后面那句<b>永远走不到</b>。</li>
 *   <li>{@code sbSpawnMob} 与死亡事件里的 {@code e.getInventory().clear()} ——
 *       {@code getInventory()} 在 1.21.11 的<b>整个生物继承链上都不存在</b>
 *       （已 javap 核对 {@code LivingEntity} / {@code Mob} / {@code CraftLivingEntity} /
 *       {@code CraftMob} / {@code CraftEntity} / {@code CraftEndermite} 全部没有），
 *       JS 每次调用都抛 {@code TypeError} 并被紧邻的 {@code catch} 吞掉 ⇒ <b>恒不执行</b>。</li>
 *   <li>{@code sbPSafe()} 的 {@code ['SMOKE_NORMAL']} 候选 —— 1.21.11 里
 *       {@code Particle.SMOKE_NORMAL} 已不存在（已实测 {@code valueOf} 抛
 *       {@code IllegalArgumentException}），只是候选表里的一个死项（前两个候选已命中）。</li>
 *   <li>{@code sbBreathParticle(world)} 的形参 {@code world} 从未被读（只是形参）。</li>
 * </ol>
 *
 * <p>★ <b>与原脚本的结构性差异</b>（都不改变可观察行为，逐条见交付说明）：
 * <ul>
 *   <li>{@code sbNow()} → {@link Guards#now()}（同一语义：{@code Instant.now().toEpochMilli()}）。</li>
 *   <li>{@code sbIsOurItem} / {@code sbIsMainHandUse} → {@link Guards}（已机械比对，与
 *       {@code gs_boss_shadow} 的这两段逐字相同，只有物品 ID 从常量变成参数）。</li>
 *   <li>{@code sbRegisterGuard} → {@link InteractGuard}（行为逐条一致：只拦 RIGHT_CLICK、
 *       跳过副手、认本物品、HIGHEST、{@code setCancelled}）。</li>
 *   <li>三处监听器注册的<b>闩锁</b>从「实例字段」改成「静态集合」：原脚本是脚本级闩锁，
 *       在 RSC 里 reload 会再注册一遍（监听器泄漏）；静态去重后「取消两次 = 取消一次」，
 *       可观察行为完全等价，但不随 reload 累积。与 {@code MaiyouWineModule} /
 *       {@code InteractGuard} 的既有做法一致。</li>
 *   <li>纯算式（{@code frontXZ} / {@code wormVel} / {@code maggotRingXZ}）抽成静态方法，
 *       只为了让探针能拿真 JS 的同一算式在大量输入上逐值对账；调用点与算式逐字保留。</li>
 * </ul>
 */
public final class ShadowModule extends AbstractModule {

    // ==================================================================
    // 常量（★ 名字与 JS 顶层 var 同名 —— 常量对账探针按名字反射比对）
    // ==================================================================

    static final String SB_ITEM_ID = "GS_SHADOW_EGG";
    static final boolean SB_CONSUME = true;
    static final double SB_SPAWN_AHEAD = 3.0;
    static final int SB_TICK = 5;
    static final int SB_FIRST_MS = 4000;
    static final double SB_SEEK = 48.0;
    static final int SB_HP_FIX_MS = 8000;
    static final int SB_TRANS_TICKS = 40;
    static final double SB_BAR_RANGE = 48.0;
    static final boolean SB_BAR_ONLY_OWNER = true;
    static final double SB_TELE_AHEAD = 2.0;

    // ---- 一阶段：末影螨「暗影巢虫」----
    static final String SB_S1_NAME = "\u00a75\u6697\u5f71\u5de2\u866b";
    static final int SB_S1_HP = 150;
    static final int SB_S1_DMG = 16;
    static final int SB_S1_WITHER_TICKS = 100;
    static final int SB_S1_WITHER_AMP = 1;

    // ---- 二阶段：末影人「暗影猎手」----
    static final String SB_S2_NAME = "\u00a75\u6697\u5f71\u730e\u624b";
    static final int SB_S2_HP = 650;
    static final int SB_S2_DMG = 25;
    static final int SB_S2_BREATH_CD = 8000;
    static final int SB_S2_BREATH_DMG = 40;
    static final double SB_S2_BREATH_R = 3.0;
    static final double SB_S2_BREATH_SPEED = 0.9;
    static final int SB_S2_BREATH_LIFE_MS = 8000;
    static final int SB_S2_TELE_CD = 11000;

    // ---- 三阶段：末影人「暗影女皇」----
    static final String SB_S3_NAME = "\u00a75\u6697\u5f71\u5973\u7687";
    static final int SB_S3_HP = 1000;
    static final int SB_S3_DMG = 33;
    static final int SB_S3_TELE_CD = 7000;
    static final int SB_S3_ARROW_CD = 10000;
    static final int SB_S3_ARROW_DMG = 20;
    static final double SB_S3_ARROW_SPEED = 1.6;
    static final double SB_S3_ARROW_SPREAD = 0.0;

    static final String SB_S3_WORM_NAME = "\u00a75\u6697\u5f71\u8815\u866b";
    static final int SB_S3_WORM_HP = 30;
    static final int SB_S3_WORM_HEAL = 77;
    static final double SB_S3_WORM_SPEED = 0.30;
    static final double SB_S3_WORM_TOUCH = 1.6;

    static final String SB_S3_MAGGOT_NAME = "\u00a75\u6697\u5f71\u86c6\u866b";
    static final int SB_S3_MAGGOT_HP = 90;
    static final int SB_S3_MAGGOT_DMG = 8;
    static final int SB_S3_MAGGOT_CD = 15000;
    static final int SB_S3_MAGGOT_N = 4;
    static final int SB_S3_MAGGOT_LIFE_MS = 15000;

    /** ★ 固定半径 4（用户：范围固定4）—— 原版是 3 → 7 一路扩大。 */
    static final double SB_S3_CLOUD_R = 4.0;
    /** ★ 存在 8 秒 = 160 刻（用户：存在时间8秒）—— 原版是 600 刻。 */
    static final int SB_S3_CLOUD_TICKS = 160;
    /** 原版 {@code MobEffectInstance(INSTANT_DAMAGE, 1, 1)} 的 amplifier。 */
    static final int SB_S3_CLOUD_AMP = 1;
    /** 原版 {@code DRAGON_BREATH.create(1.0f)} 的强度。 */
    static final double SB_S3_CLOUD_POWER = 1.0;

    // ---- 掉落物 ----
    static final String SB_DROP_ORB_ID = "GS_SHADOW_ORB";
    static final int SB_DROP_ORB_N = 6;
    static final String SB_DROP_VOICE_ID = "GS_VOICELESS";
    static final int SB_DROP_VOICE_N = 1;

    // ==================================================================
    // PDC 键（原脚本用 NamespacedKey.fromString('gs:xxx')）
    // ==================================================================
    /**
     * 原脚本的 {@code SB_PDT}（{@code Java.type('org.bukkit.persistence.PersistentDataType')}）——
     * 保留成字段只为「同名同物」：JS 侧拿到的是宿主 {@code Class} 引用，值比不了，
     * 由探针按 {@code STRING} 常量单独断言。下面所有 {@code SB_PDT == null} 的判定与它一一对应。
     */
    static final Class<?> SB_PDT = PersistentDataType.class;
    static final NamespacedKey SB_KEY = keyOrNull("gs:shadowboss");
    static final NamespacedKey SB_PROJ_KEY = keyOrNull("gs:shadowboss_proj");
    static final NamespacedKey SB_OWN_KEY = keyOrNull("gs:shadowboss_owner");
    /**
     * ★★ 龙息团的标记。<b>故意用第三个 key</b> —— {@code sbOurs()} 只认上面两个
     * ⇒ 云不会被 {@code sbSweepLeftovers()} 当成「我们的人」扫掉（原版云自己会到点消散）。
     */
    static final NamespacedKey SB_CLOUD_KEY = keyOrNull("gs:shadowboss_cloud");
    private static NamespacedKey keyOrNull(String s) {
        try {
            return NamespacedKey.fromString(s);
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 状态（★ 非静态：实例 = 原脚本的一次顶层求值）
    // ==================================================================

    /** 召唤者 uuid → ref（每个玩家同时只能有一条线）。 */
    private final Map<String, Ref> SB_ACTIVE = new ConcurrentHashMap<>();
    /** 召唤者 uuid → 上次点击时间戳（防一次点击被派发两遍）。 */
    private final Map<String, Long> SB_LAST = new ConcurrentHashMap<>();
    /** 血量诊断去重（每个实体只报一次）。 */
    private final Set<String> SB_HP_DIAG = ConcurrentHashMap.newKeySet();

    /** 注册闩锁（★ 静态：见类注释「结构性差异」）。 */
    private static final Set<String> SB_REG_DEATH = ConcurrentHashMap.newKeySet();
    private static final Set<String> SB_REG_SPELL = ConcurrentHashMap.newKeySet();
    private static final Set<String> SB_REG_GUARD = ConcurrentHashMap.newKeySet();
    private static final String REG_KEY = "gs_boss_shadow";

    /** 粒子探测缓存（原脚本是顶层 var ⇒ 静态等价）。 */
    private static volatile Particle SB_P_SAFE;
    private static volatile Particle SB_P_BREATH;
    private static volatile Float SB_P_BREATH_DATA;
    private static volatile boolean SB_P_BREATH_TRIED;
    /** MAX_HEALTH 属性键的探测缓存。 */
    private static volatile Attribute SB_MAXATTR;
    private static volatile boolean SB_MAXATTR_TRIED;

    // ==================================================================
    // 构造器 = 原脚本的顶层代码
    // ==================================================================

    public ShadowModule() {
        // ★ 6 行参数自检（每行一个 try/catch —— 与原脚本一致）
        for (String line : loadLines()) {
            try {
                Bukkit.getLogger().info(line);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
        // ★ 启动 3 秒后扫一遍全世界的残留（原脚本顶层 runLater(..., 60)）
        try {
            GS.runLater(this::sbSweepLeftovers, 60L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    @Override
    public String name() {
        return "gs_boss_shadow";
    }

    // ==================================================================
    // 加载日志行（★ 抽成方法只为了让探针能逐字节对账，不改变任何行为）
    // ==================================================================

    /**
     * 本脚本的<b>全部</b>加载日志行，按原脚本的打印顺序。
     *
     * <p>★★ 只有这 6 行 —— 原脚本<b>没有</b> {@code gs_boss_shadow 已加载}（见类注释）。
     * 探针会拿这 6 行与参考服日志<b>逐字节</b>比对，并反向断言第 7 行不存在。
     *
     * <p>★ 每个数字都走 {@link #jsNum(double)}：JS 里 {@code '' + 3.0} 是 <b>"3"</b> 而不是
     * "3.0" —— 直接拼 Java 的 {@code double} 会让日志多出一个小数点（探针抓到过一次）。
     */
    static String[] loadLines() {
        return new String[]{
                "[构思技艺] 暗影虫卵 三阶段目标血量：暗影巢虫=" + jsNum(SB_S1_HP)
                        + "（末影螨·凋零II 5秒·伤害 " + jsNum(SB_S1_DMG) + "） 暗影猎手=" + jsNum(SB_S2_HP)
                        + "（末影人·伤害 " + jsNum(SB_S2_DMG) + "） 暗影女皇=" + jsNum(SB_S3_HP)
                        + "（末影人·伤害 " + jsNum(SB_S3_DMG) + "）",

                "[构思技艺] 暗影猎手 追踪龙息弹：每 " + jsNum(SB_S2_BREATH_CD / 1000.0)
                        + "秒 伤害=" + jsNum(SB_S2_BREATH_DMG) + " 半径=" + jsNum(SB_S2_BREATH_R)
                        + " **不留龙息团**（取消 ProjectileHitEvent ⇒ 原版 onHit 不跑）"
                        + " ｜ 瞬移玩家：每 " + jsNum(SB_S2_TELE_CD / 1000.0)
                        + "秒（**独立冷却**，阶段开场先来一次）",

                "[构思技艺] 暗影女皇：瞬移玩家每 " + jsNum(SB_S3_TELE_CD / 1000.0)
                        + "秒（**独立冷却**，阶段开场先来一次） ｜ 弹道箭每 " + jsNum(SB_S3_ARROW_CD / 1000.0)
                        + "秒 伤害=" + jsNum(SB_S3_ARROW_DMG)
                        + " ⇒ 落点生成「暗影蠕虫」血=" + jsNum(SB_S3_WORM_HP)
                        + " 接触回血=" + jsNum(SB_S3_WORM_HEAL)
                        + " ｜ 蛆虫每 " + jsNum(SB_S3_MAGGOT_CD / 1000.0) + "秒 " + jsNum(SB_S3_MAGGOT_N)
                        + "只 血=" + jsNum(SB_S3_MAGGOT_HP) + " 伤害=" + jsNum(SB_S3_MAGGOT_DMG)
                        + " 存活=" + jsNum(SB_S3_MAGGOT_LIFE_MS / 1000.0) + "秒",

                "[构思技艺] 暗影虫卵 阶段推进：二/三阶段生成在**前一阶段的死亡位置**"
                        + " ｜ 蛆虫死亡留**原版末影龙那团龙息**：半径固定 " + jsNum(SB_S3_CLOUD_R)
                        + " · 存在 " + jsNum(SB_S3_CLOUD_TICKS / 20.0)
                        + "秒 · INSTANT_DAMAGE 等级 " + jsNum(SB_S3_CLOUD_AMP)
                        + "（= " + jsNum(6 << SB_S3_CLOUD_AMP) + " 点）**只对玩家造成伤害**",

                "[构思技艺] 暗影虫卵 反制：末影人不索敌我们的末影螨 / "
                        + "boss 不乱传送 / boss 不搬方块 / 弹体不炸地形 / **龙息不伤自己人**",

                "[构思技艺] 暗影虫卵 掉落：**二阶段（暗影猎手）**掉 "
                        + jsNum(SB_DROP_VOICE_N) + " 把「致万千无言者」(" + SB_DROP_VOICE_ID + ")"
                        + " ｜ **三阶段（暗影女皇）**掉 " + jsNum(SB_DROP_ORB_N)
                        + " 个「暗影宝珠」(" + SB_DROP_ORB_ID + ")"
                        + " ｜ 一阶段不掉 ｜ 掉落点 = 各阶段的**死亡位置**",
        };
    }

    /**
     * JS 的 {@code '' + 数字}。
     *
     * <p>★ 与原脚本的日志逐字节对账必需：JS 的 {@code 3.0} 拼进字符串是 <b>{@code "3"}</b>
     * （ECMAScript 的 Number::toString 对整数值不带小数点），而 Java 的
     * {@code "" + 3.0d} 是 {@code "3.0"}。本脚本日志里的数字<b>全是整数</b>，
     * 所以整数走 {@code long} 分支即可完全等价；非整数回落 {@code Double.toString}
     * （本脚本的日志里不会出现）。
     */
    static String jsNum(double v) {
        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。
        return JsNum.str(v);
    }

    // ==================================================================
    // 状态对象
    // ==================================================================

    /** 一只「暗影蛆虫」：实体 + 到点自爆时刻。 */
    private static final class Maggot {
        Entity e;
        long dieAt;
    }

    /** 一条 boss 线的全部状态（原脚本的那个 JS 字面量对象）。 */
    private static final class Ref {
        String owner;
        int stage;
        LivingEntity ent;
        String name;
        int maxHp;
        BossBar bar;
        final Map<String, Player> barNear = new HashMap<>();
        DragonFireball breath;
        int breathGen;
        final Map<String, Entity> worms = new HashMap<>();
        final Map<String, Maggot> maggots = new HashMap<>();
        long cdBreath;
        long cdTele;
        long cdArrow;
        long cdMaggot;
        long readyAt;
        long teleReadyAt;
        /** ★★ 死亡位置（死亡事件写精确值，主循环每拍写兜底值）。 */
        Double dx;
        Double dy;
        Double dz;
        long hpFixUntil;
        boolean hpOk;
        boolean transitioning;
        boolean done;
        World lw;
        double lx;
        double ly;
        double lz;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static long sbNow() {
        return Guards.now();
    }

    private static boolean sbAlive(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            return !(e.isDead() || !e.isValid());
        } catch (Throwable e2) {
            return false;
        }
    }

    private static String sbTagOf(Entity e, NamespacedKey key) {
        try {
            if (e == null || key == null || SB_PDT == null) {
                return "";
            }
            String v = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable err) {
            return "";
        }
    }

    private static String sbTag(Entity e) {
        return sbTagOf(e, SB_KEY);
    }

    private static String sbProjTag(Entity e) {
        return sbTagOf(e, SB_PROJ_KEY);
    }

    private static String sbOwnerOf(Entity e) {
        return sbTagOf(e, SB_OWN_KEY);
    }

    /** ★★ 这是我们造的「龙息团」吗（「只对玩家造成伤害」的判定用）。 */
    private static String sbCloudTag(Entity e) {
        return sbTagOf(e, SB_CLOUD_KEY);
    }

    private static boolean sbSetTagOf(Entity e, NamespacedKey key, String val) {
        try {
            if (e == null || key == null || SB_PDT == null) {
                return false;
            }
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, String.valueOf(val));
            return true;
        } catch (Throwable err) {
            return false;
        }
    }

    /** 这是我们这条 boss 线上的东西吗（boss / 小怪 / 弹体都算）。 */
    private static boolean sbOurs(Entity e) {
        try {
            if (e == null || SB_KEY == null || SB_PDT == null) {
                return false;
            }
            if (e.getPersistentDataContainer().has(SB_KEY, PersistentDataType.STRING)) {
                return true;
            }
            return SB_PROJ_KEY != null
                    && e.getPersistentDataContainer().has(SB_PROJ_KEY, PersistentDataType.STRING);
        } catch (Throwable err) {
            return false;
        }
    }

    /**
     * 这是我们这条线上的<b>末影螨</b>吗（「不吸引末影人仇恨」的判定用）。
     *
     * <p>★ 只要「带我们的标记」且「本体是末影螨」就算 —— 一阶段巢虫、三阶段的蠕虫 / 蛆虫
     * 全是末影螨，都要保护；二 / 三阶段的 boss 是<b>末影人</b>，类型这一关天然排除掉。
     */
    private static boolean sbIsOurMite(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            if (sbTag(e).isEmpty()) {
                return false;
            }
            return String.valueOf(e.getType().name()).contains("ENDERMITE");
        } catch (Throwable err) {
            return false;
        }
    }

    private static Vector sbVec(double x, double y, double z) {
        return new Vector(x, y, z);
    }

    private static Location sbLoc(World world, double x, double y, double z, float yaw, float pitch) {
        return new Location(world, x, y, z, yaw, pitch);
    }

    /** 方块的通行判定（★ 用 {@code isPassable}：<b>玻璃挡得住</b>）。 */
    static boolean sbPassableAt(World world, int bx, int by, int bz) {
        try {
            Block blk = world.getBlockAt(bx, by, bz);
            if (blk == null) {
                return true;
            }
            org.bukkit.Material mt = null;
            try {
                mt = blk.getType();
            } catch (Throwable e) {
                mt = null;
            }
            if (mt != null) {
                try {
                    if (mt.isAir()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            try {
                return blk.isPassable();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默（下面那句因此永远走不到，见类注释「死代码」）
            }
            if (mt != null) {
                try {
                    return !mt.isSolid();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    static boolean sbHeadroom(World world, int bx, int bz, int by, int height) {
        try {
            for (int i = 0; i < height; i++) {
                if (!sbPassableAt(world, bx, by + i, bz)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 找一个能站的落地高度（先往下找实地，找不到就往上抬）。
     *
     * <p>★ 抽成静态方法只为了探针能对账：算式、搜索顺序、边界钳制（{@code -60} / {@code 250}、
     * 往下 4 格 / 往上 8 格）逐字保留。
     */
    static double sbSafeY(World world, double x, double z, double y0, int height) {
        try {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int start = (int) Math.floor(y0);
            int lo = start - 4;
            int hi = start + 8;
            if (lo < -60) {
                lo = -60;
            }
            if (hi > 250) {
                hi = 250;
            }
            int y;
            for (y = start; y >= lo; y--) {
                if (sbHeadroom(world, bx, bz, y, height) && !sbPassableAt(world, bx, y - 1, bz)) {
                    return y;
                }
            }
            for (y = start; y <= hi; y++) {
                if (sbHeadroom(world, bx, bz, y, height)) {
                    return y;
                }
            }
            return y0;
        } catch (Throwable e) {
            return y0;
        }
    }

    /** 粒子名解析（多个候选，逐个试）。 */
    private static Particle sbP(String[] cands) {
        try {
            for (String c : cands) {
                try {
                    Particle p = Particle.valueOf(c);
                    if (p != null) {
                        return p;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * ★ 只挑「不需要附加数据」的粒子（{@code SimpleParticleType}）——
     * {@code DRAGON_BREATH} 这类要 data 的粒子直接 {@code spawnParticle} 会抛异常。
     */
    private static Particle sbPSafe() {
        try {
            Particle cached = SB_P_SAFE;
            if (cached != null) {
                return cached;
            }
            cached = sbP(new String[]{"SOUL_FIRE_FLAME", "PORTAL", "SMOKE", "SMOKE_NORMAL", "FLAME"});
            SB_P_SAFE = cached;
            return cached;
        } catch (Throwable e) {
            return null;
        }
    }

    private static void sbFx(World world, Particle pt, double x, double y, double z,
                             int n, double dx, double dy, double dz, double speed) {
        try {
            if (world == null || pt == null) {
                return;
            }
            world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★ 龙息粒子：{@code Particle.DRAGON_BREATH} 的 data 类型在 1.21.11 上是
     * <b>{@code java.lang.Float}</b>（已实测 {@code getDataType().getName()}），
     * 探一次并缓存。
     */
    private static Particle sbBreathParticle() {
        try {
            if (SB_P_BREATH_TRIED) {
                return SB_P_BREATH;
            }
            SB_P_BREATH_TRIED = true;
            Particle pt;
            try {
                pt = Particle.DRAGON_BREATH;
            } catch (Throwable e) {
                pt = null;
            }
            if (pt == null) {
                SB_P_BREATH = null;
                return null;
            }

            String dn = "";
            try {
                Class<?> dt = pt.getDataType();
                dn = dt == null ? "" : String.valueOf(dt.getName());
            } catch (Throwable e) {
                dn = "";
            }

            if (dn.isEmpty() || "java.lang.Void".equals(dn)) {
                SB_P_BREATH = pt;
                SB_P_BREATH_DATA = null;
                return pt;
            }
            if ("java.lang.Float".equals(dn)) {
                SB_P_BREATH = pt;
                SB_P_BREATH_DATA = 1.0f;
                return pt;
            }
            // 其它 data 类型（DustOptions / BlockData …）本脚本不用，别每拍抛异常
            SB_P_BREATH = null;
            return null;
        } catch (Throwable e) {
            SB_P_BREATH = null;
            return null;
        }
    }

    private static void sbBreathFx(World world, double x, double y, double z, int n) {
        try {
            if (world == null) {
                return;
            }
            Particle pt = sbBreathParticle();
            if (pt != null) {
                if (SB_P_BREATH_DATA == null) {
                    world.spawnParticle(pt, x, y, z, n, 0.08, 0.08, 0.08, 0.0);
                } else {
                    world.spawnParticle(pt, x, y, z, n, 0.08, 0.08, 0.08, 0.0, (Object) SB_P_BREATH_DATA);
                }
                return;
            }
            sbFx(world, sbPSafe(), x, y, z, n, 0.08, 0.08, 0.08, 0.01);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void sbSound(World world, Location loc, String name, float vol, float pitch) {
        try {
            try {
                world.playSound(loc, name, vol, pitch);
            } catch (Throwable e1) {
                try {
                    loc.getWorld().playSound(loc, name, vol, pitch);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static EntityType sbET(String[] names) {
        try {
            for (String n : names) {
                try {
                    EntityType t = EntityType.valueOf(n);
                    if (t != null) {
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    // ---------------- 属性 ----------------

    private static Attribute sbAttrKey(String[] names) {
        try {
            for (String n : names) {
                try {
                    Attribute k = Attribute.valueOf(n);
                    if (k != null) {
                        return k;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * ★★ 专门找 <b>MAX_HEALTH</b>（{@code GENERIC_MAX_HEALTH} ≤1.21.2 → {@code MAX_HEALTH} 1.21.3+），
     * 名字怎么改都能找到：两个名字都试，再遍历 {@code values()} 模糊匹配兜底。
     */
    private static Attribute sbFindMaxAttr() {
        try {
            Attribute cached = SB_MAXATTR;
            if (cached != null) {
                return cached;
            }
            Attribute k = sbAttrKey(new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"});
            if (k != null) {
                SB_MAXATTR = k;
                return k;
            }
            if (SB_MAXATTR_TRIED) {
                return null;
            }
            SB_MAXATTR_TRIED = true;
            Attribute[] all = Attribute.values();
            for (Attribute a : all) {
                String nm = "";
                String key = "";
                try {
                    nm = String.valueOf(a.name());
                } catch (Throwable e) {
                    nm = "";
                }
                try {
                    key = String.valueOf(a.getKey().getKey());
                } catch (Throwable e) {
                    key = "";
                }
                if (nm.contains("MAX_HEALTH") || key.contains("max_health")) {
                    SB_MAXATTR = a;
                    return a;
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** 强设血量上限：删修改器 → 属性 baseValue → 旧 API setMaxHealth 兜底。 */
    private static boolean sbForceMax(LivingEntity ent, double hp) {
        boolean ok = false;
        try {
            Attribute k = sbFindMaxAttr();
            if (k != null) {
                AttributeInstance inst = ent.getAttribute(k);
                if (inst != null) {
                    try {
                        List<AttributeModifier> snap = new ArrayList<>(inst.getModifiers());
                        for (AttributeModifier m : snap) {
                            try {
                                inst.removeModifier(m);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        inst.setBaseValue(hp);
                        ok = true;
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            ent.setMaxHealth(hp);
            ok = true;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return ok;
    }

    /** 把<b>最终伤害</b>（含手里武器的加成）精确设成 want 点。 */
    private static boolean sbSetDmg(LivingEntity ent, double want) {
        try {
            Attribute k = sbAttrKey(new String[]{"ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"});
            if (k == null) {
                return false;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return false;
            }
            double cur = inst.getValue();
            double base = inst.getBaseValue();
            inst.setBaseValue(base + (want - cur));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static double sbMaxHp(LivingEntity ent) {
        try {
            Attribute k = sbFindMaxAttr();
            if (k == null) {
                return 0;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return 0;
            }
            return inst.getValue();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void sbHurt(Entity src, LivingEntity ent, double dmg) {
        try {
            try {
                ent.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ent.damage(dmg, src);
            } catch (Throwable e1) {
                try {
                    ent.damage(dmg);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 直接改血（回血用，绕过无敌帧与护甲）。 */
    private static boolean sbHeal(LivingEntity ent, double amount) {
        try {
            if (ent == null || !sbAlive(ent)) {
                return false;
            }
            double hp = ent.getHealth();
            double mx = sbMaxHp(ent);
            if (mx <= 0) {
                mx = hp + amount;
            }
            double n = hp + amount;
            if (n > mx) {
                n = mx;
            }
            ent.setHealth(n);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★★ 血量写入：先确认上限到位，再回满血；失败就延迟重试 + 每拍重试。 */
    private void sbHpDiag(LivingEntity ent, double want, double max, double cur, String why) {
        try {
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                id = "unknown";
            }
            if (!SB_HP_DIAG.add(id)) {
                return;
            }
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                tn = "?";
            }
            String kname = "null";
            try {
                Attribute k = sbFindMaxAttr();
                if (k != null) {
                    kname = String.valueOf(k.name());
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 暗影虫卵 血量设置失败(" + why + ")：实体="
                        + tn + " 目标=" + want + " 实际上限=" + max + " 实际血量=" + cur
                        + " MAX_HEALTH键=" + kname);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private boolean sbApplyHpOnce(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return false;
            }
            sbForceMax(ent, hp);
            double mx = sbMaxHp(ent);
            if (mx < hp - 0.5) {
                sbHpDiag(ent, hp, mx, -1, "上限没上去");
                return false;
            }
            try {
                ent.setHealth(hp);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            double now;
            try {
                now = ent.getHealth();
            } catch (Throwable e) {
                now = -1;
            }
            if (now >= hp - 0.5) {
                return true;
            }
            sbHpDiag(ent, hp, mx, now, "血量没回满");
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void sbApplyHp(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return;
            }
            if (sbApplyHpOnce(ent, hp)) {
                return;
            }
            long[] delays = {1L, 3L, 6L, 10L, 20L, 40L};
            for (long d : delays) {
                try {
                    GS.runLater(() -> {
                        try {
                            if (!sbAlive(ent)) {
                                return;
                            }
                            sbApplyHpOnce(ent, hp);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }, d);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // BossBar（血条）
    // ==================================================================

    private static BossBar sbBarMake(String title) {
        try {
            BarColor c = null;
            BarStyle s = null;
            try {
                c = BarColor.PURPLE;
            } catch (Throwable e) {
                c = null;
            }
            if (c == null) {
                try {
                    c = BarColor.valueOf("PURPLE");
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            if (c == null) {
                try {
                    c = BarColor.RED;
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            try {
                s = BarStyle.SEGMENTED_10;
            } catch (Throwable e) {
                s = null;
            }
            if (s == null) {
                try {
                    s = BarStyle.valueOf("SEGMENTED_10");
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            if (c == null || s == null) {
                return null;
            }
            BossBar bar;
            try {
                bar = Bukkit.createBossBar(title, c, s);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setVisible(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    private void sbBarSync(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            LivingEntity ent = ref.ent;
            boolean alive = sbAlive(ent);

            double prog = 0.0;
            if (alive) {
                try {
                    double hp = ent.getHealth();
                    double mx = sbMaxHp(ent);
                    if (mx <= 0) {
                        mx = ref.maxHp;
                    }
                    prog = hp / (mx > 0 ? mx : 1);
                } catch (Throwable e) {
                    prog = 0.0;
                }
            }
            if (prog < 0) {
                prog = 0;
            }
            if (prog > 1) {
                prog = 1;
            }
            try {
                ref.bar.setProgress(prog);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ref.bar.setVisible(alive);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Map<String, Player> want = new HashMap<>();
            if (alive) {
                try {
                    String wname = String.valueOf(ent.getWorld().getName());
                    Location el = ent.getLocation();
                    Collection<? extends Player> players = Bukkit.getOnlinePlayers();
                    for (Player p : players) {
                        if (p == null) {
                            continue;
                        }
                        if (SB_BAR_ONLY_OWNER) {
                            try {
                                if (!String.valueOf(p.getUniqueId()).equals(String.valueOf(ref.owner))) {
                                    continue;
                                }
                            } catch (Throwable e) {
                                continue;
                            }
                        }
                        try {
                            if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                                continue;
                            }
                        } catch (Throwable e) {
                            continue;
                        }
                        Location pl;
                        try {
                            pl = p.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (pl.distance(el) > SB_BAR_RANGE) {
                            continue;
                        }
                        try {
                            want.put(String.valueOf(p.getUniqueId()), p);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            for (Map.Entry<String, Player> en : want.entrySet()) {
                if (ref.barNear.get(en.getKey()) == null) {
                    try {
                        ref.bar.addPlayer(en.getValue());
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    ref.barNear.put(en.getKey(), en.getValue());
                }
            }
            Iterator<Map.Entry<String, Player>> it = ref.barNear.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Player> en = it.next();
                if (want.get(en.getKey()) == null) {
                    try {
                        ref.bar.removePlayer(en.getValue());
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    it.remove();
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void sbBarDrop(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            try {
                ref.bar.setVisible(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ref.bar.removeAll();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ref.barNear.clear();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            ref.bar = null;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 找目标 / 找 ref
    // ==================================================================

    /** 最近的玩家（同世界、{@code SB_SEEK} 格内）。 */
    private static Player sbNearestPlayer(Ref ref) {
        try {
            if (ref == null) {
                return null;
            }
            World world = ref.lw;
            if (world == null) {
                return null;
            }
            LivingEntity boss = ref.ent;
            if (!sbAlive(boss)) {
                return null;
            }
            Location bl = boss.getLocation();
            Player best = null;
            double bestD = SB_SEEK;
            List<Player> players = world.getPlayers();
            for (Player p : players) {
                if (p == null) {
                    continue;
                }
                try {
                    if (p.isDead()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    if (p.getGameMode() != null
                            && String.valueOf(p.getGameMode().name()).equals("SPECTATOR")) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                double d;
                try {
                    d = p.getLocation().distance(bl);
                } catch (Throwable e) {
                    continue;
                }
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从弹体 / 小怪反查它属于哪条线（PDC 里记了召唤者 uuid）。 */
    private Ref sbRefOf(Entity ent) {
        try {
            String u = sbOwnerOf(ent);
            if (u == null || u.isEmpty()) {
                return null;
            }
            return SB_ACTIVE.get(u);
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 生成实体
    // ==================================================================

    private LivingEntity sbSpawnMob(World world, Location loc, String[] typeNames, String name,
                                    double hp, Double dmg, String tagValue, boolean aware, String ownerUuid) {
        try {
            EntityType t = sbET(typeNames);
            if (t == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 暗影虫卵：找不到实体类型 " + typeNames[0]);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return null;
            }
            Entity spawned = world.spawnEntity(loc, t);
            if (spawned == null) {
                return null;
            }
            if (!(spawned instanceof LivingEntity e)) {
                return null;
            }

            sbApplyHp(e, hp);
            if (dmg != null && dmg >= 0) {
                sbSetDmg(e, dmg);
            }

            try {
                e.setCustomName(name);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                e.setCustomNameVisible(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                e.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                e.setPersistent(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                e.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ((Mob) e).setAware(aware);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默（非 Mob 时 JS 也是抛了被吞）
            }
            try {
                e.setSilent(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                e.getEquipment().clear();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ 原脚本这里还有一句 e.getInventory().clear() —— 1.21.11 上该方法
            //   在整个生物继承链上都不存在（javap 核对），JS 每次都抛并被吞 ⇒ 死代码，不照抄。

            sbSetTagOf(e, SB_KEY, tagValue);
            if (ownerUuid != null) {
                sbSetTagOf(e, SB_OWN_KEY, ownerUuid);
            }

            return e;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 暗影虫卵：生成 " + typeNames[0] + " 出错 " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return null;
        }
    }

    // ==================================================================
    // 纯算式（★ 抽出来只为了探针能拿真 JS 的同一算式逐值对账）
    // ==================================================================

    /**
     * 「在 (bx,bz) 处朝 yaw 方向、前方 ahead 格」的水平坐标。
     *
     * <p>原脚本里这段在 {@code sbSummon}（生成）与 {@code sbPullToFront}（瞬移）各出现一次，
     * 算式逐字相同：{@code rad = yaw * PI / 180; x + (-sin(rad)) * ahead; z + cos(rad) * ahead}。
     *
     * @return {@code {x, z}}
     */
    static double[] frontXZ(double bx, double bz, double yaw, double ahead) {
        double rad = yaw * Math.PI / 180.0;
        double fx = bx + (-Math.sin(rad)) * ahead;
        double fz = bz + (Math.cos(rad)) * ahead;
        return new double[]{fx, fz};
    }

    /** 「暗影蛆虫」环：第 i 只（共 n 只）相对女皇的 (dx, dz)。 */
    static double[] maggotRingXZ(double x, double z, int i, int n) {
        double ang = (i * 2.0 * Math.PI) / n;
        return new double[]{x + Math.cos(ang) * 1.2, z + Math.sin(ang) * 1.2};
    }

    /** 「暗影蠕虫」每刻的追赶速度（dx/dz 是「女皇 − 虫子」的差，flat 是水平距离）。 */
    static double[] wormVel(double dx, double dy, double dz, double flat) {
        double inv = 1.0 / flat;
        double vy = 0.0;
        if (dy > 0.6) {
            vy = 0.22;
        } else if (dy < -0.6) {
            vy = -0.22;
        }
        return new double[]{dx * inv * SB_S3_WORM_SPEED, vy, dz * inv * SB_S3_WORM_SPEED};
    }

    /** 「暗影蠕虫」与女皇的接触判定：{@code flat ≤ SB_S3_WORM_TOUCH && |dy| ≤ 2.0}。 */
    static boolean wormTouch(double flat, double dy) {
        return flat <= SB_S3_WORM_TOUCH && Math.abs(dy) <= 2.0;
    }

    /** 弹道箭的瞄准向量分量 —— ★ 比龙息弹<b>多抬 0.35</b>（「弹道」的抛物线味道）。 */
    static double[] arrowAim(double fx, double fy, double fz, double tx, double ty, double tz) {
        return new double[]{tx - fx, (ty + 1.0) - fy + 0.35, tz - fz};
    }

    /** 追踪龙息弹的瞄准向量分量 —— ★ <b>不</b>多抬那 0.35。 */
    static double[] breathAim(double fx, double fy, double fz, double tx, double ty, double tz) {
        return new double[]{tx - fx, (ty + 1.0) - fy, tz - fz};
    }

    // ==================================================================
    // 技能：把玩家瞬移到 boss 身前
    // ==================================================================

    private boolean sbPullToFront(Ref ref, Player p) {
        try {
            LivingEntity boss = ref.ent;
            if (!sbAlive(boss) || p == null) {
                return false;
            }
            World world = ref.lw;
            Location bl = boss.getLocation();
            double yaw = 0.0;
            try {
                yaw = bl.getYaw();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            double[] f = frontXZ(bl.getX(), bl.getZ(), yaw, SB_TELE_AHEAD);
            double fx = f[0];
            double fz = f[1];
            double fy = sbSafeY(world, fx, fz, bl.getY(), 2);

            Location from;
            try {
                from = p.getLocation();
            } catch (Throwable e) {
                from = null;
            }

            // 出发点特效（反向传送门 = 被吸走）
            if (from != null) {
                try {
                    Particle ptOut = sbP(new String[]{"REVERSE_PORTAL", "PORTAL", "SMOKE_NORMAL"});
                    sbFx(world, ptOut, from.getX(), from.getY() + 1.0, from.getZ(),
                            24, 0.35, 0.6, 0.35, 0.08);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    sbSound(world, from, "entity.enderman.teleport", 1.0f, 0.8f);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            Location loc = sbLoc(world, fx, fy, fz, (float) (yaw + 180.0), 0.0f);
            boolean ok;
            try {
                ok = p.teleport(loc);
            } catch (Throwable e) {
                ok = false;
            }

            // 落点特效
            try {
                Particle ptIn = sbP(new String[]{"PORTAL", "REVERSE_PORTAL", "SMOKE_NORMAL"});
                sbFx(world, ptIn, fx, fy + 1.0, fz, 30, 0.35, 0.7, 0.35, 0.12);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.enderman.teleport", 1.0f, 1.3f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // 技能：追踪龙息弹（二阶段）
    // ==================================================================

    private boolean sbFireBreath(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!sbAlive(boss)) {
                return false;
            }
            Player tgt = sbNearestPlayer(ref);
            if (tgt == null) {
                return false;
            }
            World world = ref.lw;

            Location from;
            try {
                from = boss.getEyeLocation();
            } catch (Throwable e) {
                from = boss.getLocation();
            }
            Location to = tgt.getLocation();
            double[] db = breathAim(from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ());
            Vector dir = sbVec(db[0], db[1], db[2]);
            if (dir == null || dir.lengthSquared() < 0.01) {
                return false;
            }
            dir.normalize();

            DragonFireball fb;
            try {
                fb = world.spawn(from, DragonFireball.class);
            } catch (Throwable e) {
                fb = null;
            }
            if (fb == null) {
                return false;
            }

            try {
                fb.setShooter(boss);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ yield 只是兜底：真正生效的是「取消 ProjectileHitEvent」⇒ 连爆炸带云全没了
            try {
                fb.setYield(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                fb.setIsIncendiary(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                fb.setDirection(dir.multiply(SB_S2_BREATH_SPEED));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            sbSetTagOf(fb, SB_PROJ_KEY, "breath");
            sbSetTagOf(fb, SB_OWN_KEY, ref.owner);

            try {
                sbSound(world, from, "entity.ender_dragon.shoot", 1.0f, 1.35f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, from, "entity.ender_dragon.ambient", 0.6f, 1.5f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            ref.breath = fb;
            ref.breathGen = ref.breathGen + 1;
            sbBreathHoming(ref, fb, ref.breathGen, sbNow());
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 每刻把弹体掰回玩家身上 ⇒ 「追踪」。 */
    private void sbBreathHoming(Ref ref, DragonFireball fb, int gen, long born) {
        try {
            if (ref == null || ref.breathGen != gen) {
                return;
            }
            if (!sbAlive(fb)) {
                return;
            }
            if (sbNow() - born > SB_S2_BREATH_LIFE_MS) {
                try {
                    fb.remove();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    if (ref.breath == fb) {
                        ref.breath = null;
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }
            Player tgt = sbNearestPlayer(ref);
            if (tgt != null) {
                Location fl;
                try {
                    fl = fb.getLocation();
                } catch (Throwable e) {
                    fl = null;
                }
                Location tl;
                try {
                    tl = tgt.getLocation();
                } catch (Throwable e) {
                    tl = null;
                }
                if (fl != null && tl != null) {
                    Vector d = sbVec(tl.getX() - fl.getX(),
                            (tl.getY() + 1.0) - fl.getY(),
                            tl.getZ() - fl.getZ());
                    if (d != null && d.lengthSquared() > 0.01) {
                        d.normalize();
                        try {
                            fb.setDirection(d.multiply(SB_S2_BREATH_SPEED));
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
            }
            // 拖尾（弹体自己有原版拖尾，这里再补一圈，命中判定前的观感更足）
            try {
                Location fl2 = fb.getLocation();
                sbBreathFx(ref.lw, fl2.getX(), fl2.getY(), fl2.getZ(), 2);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            GS.runLater(() -> sbBreathHoming(ref, fb, gen, born), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 龙息弹命中：范围伤害 40 + 特效（★ 绝不留龙息团）。 */
    private int sbBreathBoom(Ref ref, Location loc) {
        try {
            if (loc == null) {
                return 0;
            }
            World world;
            try {
                world = loc.getWorld();
            } catch (Throwable e) {
                world = null;
            }
            if (world == null) {
                return 0;
            }

            LivingEntity boss = ref == null ? null : ref.ent;

            // ① 伤害：命中点 SB_S2_BREATH_R 格内的**玩家**，每人 40
            int hit = 0;
            try {
                Collection<Entity> list = world.getNearbyEntities(loc,
                        SB_S2_BREATH_R, SB_S2_BREATH_R + 1.0, SB_S2_BREATH_R);
                for (Entity ent : list) {
                    if (ent == null) {
                        continue;
                    }
                    String tn;
                    try {
                        tn = String.valueOf(ent.getType().name());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (!"PLAYER".equals(tn)) {
                        continue;
                    }
                    try {
                        if (ent.isDead()) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    // ★ 不要打到自己人
                    if (sbOurs(ent)) {
                        continue;
                    }
                    sbHurt(boss, (LivingEntity) ent, SB_S2_BREATH_DMG);
                    hit++;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 视觉：龙息 + 灵魂火 + 末影粒子
            try {
                double x = loc.getX();
                double y = loc.getY();
                double z = loc.getZ();
                sbBreathFx(world, x, y, z, 60);
                sbFx(world, sbP(new String[]{"SOUL_FIRE_FLAME", "SOUL", "FLAME"}),
                        x, y, z, 30, 0.6, 0.6, 0.6, 0.06);
                sbFx(world, sbP(new String[]{"PORTAL"}), x, y, z, 40, 0.8, 0.8, 0.8, 0.5);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ 音效
            try {
                sbSound(world, loc, "entity.dragon_fireball.explode", 1.2f, 1.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.ender_dragon.hurt", 0.6f, 1.6f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ★★ 这里**故意不建 AreaEffectCloud** —— 用户要求「不会留下龙息团」
            return hit;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ==================================================================
    // 技能：弹道箭矢（三阶段）
    // ==================================================================

    private boolean sbFireArrow(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!sbAlive(boss)) {
                return false;
            }
            Player tgt = sbNearestPlayer(ref);
            if (tgt == null) {
                return false;
            }
            World world = ref.lw;

            Location from;
            try {
                from = boss.getEyeLocation();
            } catch (Throwable e) {
                from = boss.getLocation();
            }
            Location to = tgt.getLocation();
            // ★ 稍微往上抬一点，让「弹道」有抛物线的味道
            double[] da = arrowAim(from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ());
            Vector dir = sbVec(da[0], da[1], da[2]);
            if (dir == null || dir.lengthSquared() < 0.01) {
                return false;
            }
            dir.normalize();

            AbstractArrow arrow;
            try {
                arrow = world.spawnArrow(from, dir, (float) SB_S3_ARROW_SPEED, (float) SB_S3_ARROW_SPREAD);
            } catch (Throwable e) {
                arrow = null;
            }
            if (arrow == null) {
                return false;
            }

            try {
                arrow.setShooter(boss);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                arrow.setDamage(SB_S3_ARROW_DMG);   // 兜底，真正的定值在伤害事件里
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                arrow.setKnockbackStrength(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                arrow.setCritical(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                AbstractArrow.PickupStatus ps = AbstractArrow.PickupStatus.DISALLOWED;
                if (ps != null) {
                    arrow.setPickupStatus(ps);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            sbSetTagOf(arrow, SB_PROJ_KEY, "arrow");
            sbSetTagOf(arrow, SB_OWN_KEY, ref.owner);

            try {
                sbSound(world, from, "entity.arrow.shoot", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, from, "entity.enderman.ambient", 0.7f, 1.4f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // 小怪：暗影蠕虫（箭矢落点）/ 暗影蛆虫（女皇脚下）
    // ==================================================================

    private LivingEntity sbSpawnWorm(Ref ref, Location loc) {
        try {
            if (ref == null || loc == null) {
                return null;
            }
            World world = ref.lw;
            double my = sbSafeY(world, loc.getX(), loc.getZ(), loc.getY(), 1);
            Location at = sbLoc(world, loc.getX(), my, loc.getZ(), 0.0f, 0.0f);
            // ★ aware = false：它不追玩家，只被脚本驱赶着往女皇爬
            LivingEntity w = sbSpawnMob(world, at, new String[]{"ENDERMITE"}, SB_S3_WORM_NAME,
                    SB_S3_WORM_HP, 0.0, "worm", false, ref.owner);
            if (w == null) {
                return null;
            }
            ref.worms.put(String.valueOf(w.getUniqueId()), w);
            try {
                Particle pt = sbP(new String[]{"PORTAL", "REVERSE_PORTAL", "SMOKE_NORMAL"});
                sbFx(world, pt, at.getX(), at.getY() + 0.3, at.getZ(), 18, 0.3, 0.3, 0.3, 0.08);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, at, "entity.endermite.ambient", 0.8f, 1.4f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return w;
        } catch (Throwable e) {
            return null;
        }
    }

    private int sbSpawnMaggotWave(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!sbAlive(boss)) {
                return 0;
            }
            World world = ref.lw;
            Location bl = boss.getLocation();
            int n = 0;
            for (int i = 0; i < SB_S3_MAGGOT_N; i++) {
                double[] m0 = maggotRingXZ(bl.getX(), bl.getZ(), i, SB_S3_MAGGOT_N);
                double mx = m0[0];
                double mz = m0[1];
                double my = sbSafeY(world, mx, mz, bl.getY(), 1);
                Location at = sbLoc(world, mx, my, mz, 0.0f, 0.0f);
                LivingEntity m = sbSpawnMob(world, at, new String[]{"ENDERMITE"}, SB_S3_MAGGOT_NAME,
                        SB_S3_MAGGOT_HP, (double) SB_S3_MAGGOT_DMG, "maggot", true, ref.owner);
                if (m == null) {
                    continue;
                }
                Maggot mo = new Maggot();
                mo.e = m;
                mo.dieAt = sbNow() + SB_S3_MAGGOT_LIFE_MS;
                ref.maggots.put(String.valueOf(m.getUniqueId()), mo);
                n++;
            }
            if (n > 0) {
                try {
                    Particle pt = sbP(new String[]{"PORTAL", "REVERSE_PORTAL", "SMOKE_NORMAL"});
                    sbFx(world, pt, bl.getX(), bl.getY() + 0.3, bl.getZ(), 40, 1.2, 0.3, 1.2, 0.12);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    sbSound(world, bl, "entity.endermite.ambient", 1.0f, 0.7f);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            return n;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * ★★★ 蛆虫死亡留下的「龙息团」= <b>原版末影龙喷出的那团龙息</b>。
     *
     * <p>参数逐条抄 1.21.11 {@code DragonFireball#onHit}（见类注释与文件头对照表）。
     * ★★ 伤害<b>不是</b>这里筛的 —— 原版云靠 {@code INSTANT_DAMAGE} 结算，
     * 它连女皇和她自己的蛆虫一起烤；「只对玩家造成伤害」由 {@link #sbCloudGuard} 兜住。
     */
    private AreaEffectCloud sbBreathCloud(World world, Location loc, LivingEntity owner) {
        try {
            if (world == null || loc == null) {
                return null;
            }
            AreaEffectCloud cloud;
            try {
                cloud = world.spawn(loc, AreaEffectCloud.class);
            } catch (Throwable e) {
                cloud = null;
            }
            if (cloud == null) {
                // 拿不到云就退化成一次性粒子（不该发生，留个退路）
                sbBreathFx(world, loc.getX(), loc.getY() + 0.4, loc.getZ(), 40);
                return null;
            }

            // ★★ 给我们造的云打个记号 ⇒ sbCloudGuard() 靠它认出「这伤害是龙息团来的」
            try {
                sbSetTagOf(cloud, SB_CLOUD_KEY, "1");
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② setOwner(owner) —— 原版只在 owner 是 LivingEntity 时才设
            try {
                if (owner != null) {
                    cloud.setSource(owner);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ setCustomParticle(DRAGON_BREATH.create(1.0f))
            boolean got = false;
            try {
                Particle pt = sbBreathParticle();
                if (pt != null) {
                    if (SB_P_BREATH_DATA == null) {
                        // 探到 DRAGON_BREATH 的 data 是 Void ⇒ 不带 data 调用
                        try {
                            cloud.setParticle(pt);
                            got = true;
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    } else {
                        // ★★ DRAGON_BREATH 的 data 类型是 java.lang.Float ⇒ 必须**显式**给 Float：
                        //    给 Double 会被 CraftParticle 的 dataType.isInstance(data) 挡下来 ⇒ 云没有粒子。
                        try {
                            cloud.setParticle(pt, Float.valueOf((float) SB_S3_CLOUD_POWER));
                            got = true;
                        } catch (Throwable e) {
                            try {
                                cloud.setParticle(pt);
                                got = true;
                            } catch (Throwable e2) {
                                // 与原脚本一致：静默
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (!got) {
                try {
                    cloud.setParticle(sbPSafe());
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // ④⑤⑥ ★★「龙息的范围固定4，存在时间8秒」
            try {
                cloud.setRadius((float) SB_S3_CLOUD_R);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                cloud.setDuration(SB_S3_CLOUD_TICKS);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                cloud.setRadiusPerTick(0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ★ **故意不调 setWaitTime()** —— 原版那团云用的是默认值 20 刻（先蓄 1 秒）。

            // ⑦ addEffect(new MobEffectInstance(INSTANT_DAMAGE, 1, 1))
            try {
                PotionEffectType id = PotionEffectType.INSTANT_DAMAGE;
                if (id != null) {
                    cloud.addCustomEffect(new PotionEffect(id, 1, SB_S3_CLOUD_AMP, false, true, true), true);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            try {
                sbSound(world, loc, "entity.ender_dragon.ambient", 0.5f, 1.7f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return cloud;
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 阶段切换演出
    // ==================================================================

    private void sbTransFx(World world, double x, double y, double z, int i, int kind) {
        try {
            if (world == null) {
                return;
            }
            if (i >= SB_TRANS_TICKS) {
                return;
            }
            double frac = i / (double) SB_TRANS_TICKS;
            if (kind == 1) {
                // 一阶段（末影螨）之死：末影粒子柱往上窜
                Particle pt = sbP(new String[]{"PORTAL", "REVERSE_PORTAL", "SMOKE_NORMAL"});
                double top = 6.0 * frac;
                for (double h = 0; h <= top; h += 0.5) {
                    sbFx(world, pt, x, y + h + 0.2, z, 2, 0.15, 0.02, 0.15, 0.4);
                }
                sbFx(world, pt, x, y + 0.3, z, 8, 0.6, 0.1, 0.6, 0.3);
                if (i == 1) {
                    try {
                        sbSound(world, sbLoc(world, x, y, z, 0.0f, 0.0f), "entity.endermite.death", 1.0f, 0.8f);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            } else {
                // 二阶段（末影人）之死：一圈反向传送门 + 龙息环
                Particle pt2 = sbP(new String[]{"REVERSE_PORTAL", "PORTAL", "SMOKE_NORMAL"});
                double r = 1.0 + frac * 3.0;
                for (int a = 0; a < 12; a++) {
                    double ang = a * (Math.PI / 6.0) + frac * 2.0;
                    sbFx(world, pt2, x + Math.cos(ang) * r, y + 0.6 + frac * 1.5, z + Math.sin(ang) * r,
                            3, 0.1, 0.2, 0.1, 0.3);
                }
                sbBreathFx(world, x, y + 1.0 + frac * 2.0, z, 6);
                if (i == 1) {
                    try {
                        sbSound(world, sbLoc(world, x, y, z, 0.0f, 0.0f), "entity.enderman.death", 1.2f, 0.7f);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
            GS.runLater(() -> sbTransFx(world, x, y, z, i + 1, kind), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    private void sbTick(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (ref.transitioning) {
                return;
            }

            LivingEntity boss = ref.ent;
            if (!sbAlive(boss)) {
                sbPhaseEnd(ref);
                return;
            }

            long now = sbNow();

            // ⓪ ★★ 记下「这一拍它在哪」—— 二 / 三阶段要生成在**前一个阶段的死亡位置**，
            //    boss 死透之后再 getLocation() 就不一定准了 ⇒ 每拍先存一份兜底值。
            try {
                Location bl0 = boss.getLocation();
                ref.dx = bl0.getX();
                ref.dy = bl0.getY();
                ref.dz = bl0.getZ();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ① 血量兜底（出生后 SB_HP_FIX_MS 内每拍重试，直到确认写成功）
            try {
                if (!ref.hpOk && now < ref.hpFixUntil) {
                    if (sbApplyHpOnce(boss, ref.maxHp)) {
                        ref.hpOk = true;
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 名字 / 血量条
            try {
                String cn;
                try {
                    cn = String.valueOf((Object) boss.getCustomName());
                } catch (Throwable e) {
                    cn = "";
                }
                if (!cn.contains(ref.name)) {
                    boss.setCustomName(ref.name);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbBarSync(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Player target;
            try {
                target = sbNearestPlayer(ref);
            } catch (Throwable e) {
                target = null;
            }

            // ③ 主动索敌（★ 末影人不会自己盯上玩家，必须每拍塞一次目标）
            try {
                LivingEntity cur;
                try {
                    cur = (boss instanceof Mob m) ? m.getTarget() : null;
                } catch (Throwable e) {
                    cur = null;
                }
                if (target != null) {
                    boolean need = (cur == null);
                    if (!need) {
                        try {
                            need = !String.valueOf(cur.getUniqueId()).equals(String.valueOf(target.getUniqueId()));
                        } catch (Throwable e) {
                            need = true;
                        }
                    }
                    if (need && boss instanceof Mob m2) {
                        m2.setTarget(target);
                    }
                } else if (cur != null) {
                    try {
                        if (boss instanceof Mob m3) {
                            m3.setTarget(null);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ④ 出场保护期内不放技能
            boolean canCast = (now >= ref.readyAt) && (target != null);
            // ★★★「瞬移」是**独立**的一条冷却 —— 它不吃 SB_FIRST_MS 那 4 秒保护期，
            //    用自己的一份 teleReadyAt（阶段一开始就等于 now）。
            boolean canTele = (now >= ref.teleReadyAt) && (target != null);

            // ================= 一阶段：暗影巢虫 =================
            if (ref.stage == 1) {
                // 只有普攻（16 点）+ 命中时给玩家凋零 II 5 秒（在伤害事件里加）
            } else if (ref.stage == 2) {
                // · 每 8 秒一发追踪龙息弹（伤害 40、不留龙息团）
                if (canCast && now - ref.cdBreath >= SB_S2_BREATH_CD) {
                    ref.cdBreath = now;
                    sbFireBreath(ref);
                }
                // · 每 11 秒把玩家瞬移到身前（★ 独立冷却，开场就来一次）
                if (canTele && now - ref.cdTele >= SB_S2_TELE_CD) {
                    ref.cdTele = now;
                    sbPullToFront(ref, target);
                }
            } else if (ref.stage == 3) {
                // · 每 7 秒把玩家瞬移到身前（★ 独立冷却，开场就来一次）
                if (canTele && now - ref.cdTele >= SB_S3_TELE_CD) {
                    ref.cdTele = now;
                    sbPullToFront(ref, target);
                }
                // · 每 10 秒一支弹道箭（落点生成「暗影蠕虫」）
                if (canCast && now - ref.cdArrow >= SB_S3_ARROW_CD) {
                    ref.cdArrow = now;
                    sbFireArrow(ref);
                }
                // · 每 15 秒脚下 4 只「暗影蛆虫」
                if (canCast && now - ref.cdMaggot >= SB_S3_MAGGOT_CD) {
                    ref.cdMaggot = now;
                    sbSpawnMaggotWave(ref);
                }
            }

            // ⑤ 三阶段：小怪维护
            if (ref.stage == 3) {
                // 「暗影蛆虫」到点自爆（死亡事件里会留下龙息团）
                try {
                    List<String> dead = new ArrayList<>();
                    for (Map.Entry<String, Maggot> en : ref.maggots.entrySet()) {
                        Maggot mo = en.getValue();
                        if (mo == null) {
                            dead.add(en.getKey());
                            continue;
                        }
                        Entity me = mo.e;
                        if (!sbAlive(me)) {
                            dead.add(en.getKey());
                            continue;
                        }
                        if (now >= mo.dieAt) {
                            dead.add(en.getKey());
                            sbHurt(null, (LivingEntity) me, 9999.0);  // ★ 走伤害管线 ⇒ 触发死亡事件 ⇒ 留龙息团
                        }
                    }
                    for (String k : dead) {
                        ref.maggots.remove(k);
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }

                // 「暗影蠕虫」爬向女皇，接触即死 + 给女皇回 77 血
                try {
                    Location bl = boss.getLocation();
                    List<String> gone = new ArrayList<>();
                    for (Map.Entry<String, Entity> en : ref.worms.entrySet()) {
                        Entity w = en.getValue();
                        if (w == null || !sbAlive(w)) {
                            gone.add(en.getKey());
                            continue;
                        }
                        Location wl;
                        try {
                            wl = w.getLocation();
                        } catch (Throwable e) {
                            wl = null;
                        }
                        if (wl == null) {
                            gone.add(en.getKey());
                            continue;
                        }
                        double dx = bl.getX() - wl.getX();
                        double dy = bl.getY() - wl.getY();
                        double dz = bl.getZ() - wl.getZ();
                        double flat = Math.sqrt(dx * dx + dz * dz);
                        if (wormTouch(flat, dy)) {
                            // ★ 接触 ⇒ 死亡 + 女皇回血 77
                            gone.add(en.getKey());
                            sbHurt(null, (LivingEntity) w, 9999.0);
                            sbHeal(boss, SB_S3_WORM_HEAL);
                            try {
                                Particle pt = sbP(new String[]{"HEART", "VILLAGER_HAPPY", "HAPPY_VILLAGER"});
                                sbFx(ref.lw, pt, bl.getX(), bl.getY() + 2.2, bl.getZ(),
                                        10, 0.5, 0.4, 0.5, 0.0);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                            try {
                                sbSound(ref.lw, bl, "entity.zombie_villager.cure", 1.0f, 1.5f);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                            continue;
                        }
                        if (flat > 0.01) {
                            double[] v = wormVel(dx, dy, dz, flat);
                            try {
                                w.setVelocity(sbVec(v[0], v[1], v[2]));
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                    }
                    for (String k : gone) {
                        ref.worms.remove(k);
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // ⑥ 继续
            GS.runLater(() -> sbTick(ref), SB_TICK);
        } catch (Throwable e) {
            try {
                if (ref != null && sbAlive(ref.ent)) {
                    GS.runLater(() -> sbTick(ref), SB_TICK);
                } else {
                    sbCleanup(ref);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ==================================================================
    // 阶段结束 / 收摊
    // ==================================================================

    private static void sbPurgeMinions(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            for (Maggot mo : new ArrayList<>(ref.maggots.values())) {
                try {
                    if (mo != null && sbAlive(mo.e)) {
                        mo.e.remove();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            ref.maggots.clear();
            for (Entity w : new ArrayList<>(ref.worms.values())) {
                try {
                    if (w != null && sbAlive(w)) {
                        w.remove();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            ref.worms.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★★★ 掉一个 Slimefun 物品。写法照抄「妄念」那边的 {@code btDropOne()}：
     * 取物品 / 落地 / 报错兜底都是同一条路。取不到物品时<b>只往后台打一行日志</b>。
     */
    private static boolean sbDropOne(World world, Double x, Double y, Double z, String id, Integer amount) {
        try {
            if (world == null || id == null) {
                return false;
            }
            if (x == null || z == null) {
                return false;
            }

            ItemStack stack = null;
            try {
                SlimefunItem sf = SlimefunItem.getById(id);
                if (sf != null) {
                    stack = sf.getItem().clone();
                }
            } catch (Throwable e) {
                stack = null;
            }
            if (stack == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 暗影虫卵：取不到 " + id + "，未掉落");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return false;
            }
            try {
                stack.setAmount(amount == null || amount <= 0 ? 1 : amount);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            world.dropItemNaturally(new Location(world, x, (y == null ? 0 : y) + 0.5, z), stack);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★ 第三阶段（暗影女皇）死掉 ⇒ 原地掉 {@code SB_DROP_ORB_N} 个「暗影宝珠」。 */
    private static void sbDropOrbs(World world, Double x, Double y, Double z) {
        try {
            double yy = (y == null ? 0 : y);
            if (!sbDropOne(world, x, yy, z, SB_DROP_ORB_ID, SB_DROP_ORB_N)) {
                return;
            }
            try {
                sbSound(world, new Location(world, x, yy, z), "entity.item.pickup", 0.8f, 1.2f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private void sbPhaseEnd(Ref ref) {
        try {
            if (ref == null || ref.transitioning) {
                return;
            }
            ref.transitioning = true;
            sbBarDrop(ref);

            // 正在飞的龙息弹作废（世代号让旧链停手）
            try {
                ref.breathGen = ref.breathGen + 1;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                if (ref.breath != null) {
                    ref.breath.remove();
                    ref.breath = null;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // 本阶段的小怪一并清掉（换阶段不该留着上一阶段的兵）
            try {
                sbPurgeMinions(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            int stage = ref.stage;
            World world = ref.lw;

            // ★★★ 二 / 三阶段生成在**前一个阶段的死亡位置**（用户要求）。
            //   优先级：① 死亡事件写下的精确死亡坐标 ② 主循环每拍记下的最后位置
            //           ③ 都没有 ⇒ 退回召唤点
            double x;
            double y;
            double z;
            if (ref.dx != null && ref.dy != null && ref.dz != null) {
                x = ref.dx;
                y = ref.dy;
                z = ref.dz;
            } else {
                x = ref.lx;
                y = ref.ly;
                z = ref.lz;
            }

            // ★★★ 掉落物：二阶段掉 1 把「致万千无言者」，三阶段掉 6 个「暗影宝珠」，
            //   一阶段什么都不掉。用的 (x,y,z) 就是**死亡位置**。
            if (stage == 2) {
                try {
                    sbDropOne(world, x, y, z, SB_DROP_VOICE_ID, SB_DROP_VOICE_N);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            if (stage >= 3) {
                try {
                    sbDropOrbs(world, x, y, z);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            if (stage >= 3) {
                // 三阶段全部打完 ⇒ 收摊
                sbCleanup(ref);
                return;
            }

            int kind = (stage == 1 ? 1 : 2);
            sbTransFx(world, x, y, z, 0, kind);
            GS.runLater(() -> {
                try {
                    sbNext(ref, stage + 1, world, x, y, z);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, SB_TRANS_TICKS);
        } catch (Throwable e) {
            sbCleanup(ref);
        }
    }

    /** 生成下一阶段。 */
    private void sbNext(Ref ref, int stage, World world, double x, double y, double z) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (world == null) {
                sbCleanup(ref);
                return;
            }

            int height = (stage == 1 ? 1 : 3);     // 末影螨 1 格、末影人 3 格
            double sy = sbSafeY(world, x, z, y, height);
            Location loc = sbLoc(world, x, sy, z, 0.0f, 0.0f);

            String name;
            int hp;
            int dmg;
            String tag;
            if (stage == 1) {
                name = SB_S1_NAME;
                hp = SB_S1_HP;
                dmg = SB_S1_DMG;
                tag = "boss1";
            } else if (stage == 2) {
                name = SB_S2_NAME;
                hp = SB_S2_HP;
                dmg = SB_S2_DMG;
                tag = "boss2";
            } else {
                name = SB_S3_NAME;
                hp = SB_S3_HP;
                dmg = SB_S3_DMG;
                tag = "boss3";
            }

            String[] typeNames = (stage == 1 ? new String[]{"ENDERMITE"} : new String[]{"ENDERMAN"});
            LivingEntity ent = sbSpawnMob(world, loc, typeNames, name, hp, (double) dmg, tag, true, ref.owner);
            if (ent == null) {
                sbCleanup(ref);
                return;
            }

            // 末影人：不许搬方块（EntityChangeBlockEvent）、不许乱传送（EntityTeleportEvent）
            try {
                ent.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                if (ent instanceof org.bukkit.entity.Enderman em) {
                    em.setScreaming(false);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            ref.stage = stage;
            ref.ent = ent;
            ref.name = name;
            ref.maxHp = hp;
            ref.transitioning = false;
            ref.hpOk = false;
            ref.hpFixUntil = sbNow() + SB_HP_FIX_MS;

            long now = sbNow();
            long teleCd = (stage == 3 ? SB_S3_TELE_CD : SB_S2_TELE_CD);
            // ★ 其它技能：出场保护期 SB_FIRST_MS（4 秒）照旧
            ref.readyAt = now + SB_FIRST_MS;
            ref.cdBreath = now - SB_S2_BREATH_CD + SB_FIRST_MS;
            ref.cdArrow = now - SB_S3_ARROW_CD + SB_FIRST_MS;
            ref.cdMaggot = now - SB_S3_MAGGOT_CD + SB_FIRST_MS;
            // ★★★「瞬移」单独算冷却：teleReadyAt 不含那 4 秒；cdTele 先拨到「刚好走完」
            //   ⇒ 下一拍判定立刻成立 ⇒ **开场先瞬移一次**。
            ref.teleReadyAt = now;
            ref.cdTele = now - teleCd;

            ref.bar = sbBarMake(sbBarTitle(stage));
            try {
                sbBarSync(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 出场特效
            try {
                Particle pt = sbP(new String[]{"REVERSE_PORTAL", "PORTAL", "SMOKE_NORMAL"});
                sbFx(world, pt, x, sy + 1.0, z, 40, 0.6, 0.9, 0.6, 0.25);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.enderman.teleport", 1.2f, 0.7f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.wither.spawn", 0.5f, 1.6f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.ender_dragon.growl", 0.8f, 1.4f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            GS.runLater(() -> sbTick(ref), SB_TICK);
        } catch (Throwable e) {
            sbCleanup(ref);
        }
    }

    static String sbBarTitle(int stage) {
        if (stage == 1) {
            return "\u6697\u5f71\u5de2\u866b";      // 暗影巢虫
        }
        if (stage == 2) {
            return "\u6697\u5f71\u730e\u624b";      // 暗影猎手
        }
        return "\u6697\u5f71\u5973\u7687";           // 暗影女皇
    }

    private void sbCleanup(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            ref.done = true;
            ref.transitioning = false;
            try {
                sbBarDrop(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ref.breathGen = ref.breathGen + 1;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                if (ref.breath != null) {
                    ref.breath.remove();
                    ref.breath = null;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbPurgeMinions(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 万一还留着残兵
            try {
                if (sbAlive(ref.ent)) {
                    try {
                        ref.ent.getEquipment().clear();
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        ref.ent.remove();
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            ref.ent = null;

            // 解锁「这个玩家还能再召唤」
            try {
                if (ref.owner != null && SB_ACTIVE.get(ref.owner) == ref) {
                    SB_ACTIVE.remove(ref.owner);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 召唤入口
    // ==================================================================

    private boolean sbSummon(Player p) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // 这条线上还有没打完的 → 静默忽略（三阶段全死才能再召）
            Ref old = SB_ACTIVE.get(uuid);
            if (old != null && !old.done) {
                return false;
            }

            World world = p.getWorld();
            Location base = p.getLocation();
            double yaw = 0;
            try {
                yaw = base.getYaw();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            double[] f = frontXZ(base.getX(), base.getZ(), yaw, SB_SPAWN_AHEAD);
            double lx = f[0];
            double lz = f[1];
            double ly = sbSafeY(world, lx, lz, base.getY(), 1);
            Location loc = sbLoc(world, lx, ly, lz, (float) yaw, 0.0f);

            long now = sbNow();
            Ref ref = new Ref();
            ref.owner = uuid;
            ref.stage = 1;
            ref.ent = null;
            ref.name = SB_S1_NAME;
            ref.maxHp = SB_S1_HP;
            ref.bar = null;
            ref.breath = null;
            ref.breathGen = 0;
            ref.cdBreath = now - SB_S2_BREATH_CD + SB_FIRST_MS;
            ref.cdTele = now - SB_S2_TELE_CD;
            ref.cdArrow = now - SB_S3_ARROW_CD + SB_FIRST_MS;
            ref.cdMaggot = now - SB_S3_MAGGOT_CD + SB_FIRST_MS;
            ref.readyAt = now + SB_FIRST_MS;
            ref.teleReadyAt = now;
            ref.dx = null;
            ref.dy = null;
            ref.dz = null;
            ref.hpFixUntil = now + SB_HP_FIX_MS;
            ref.hpOk = false;
            ref.transitioning = false;
            ref.done = false;
            ref.lw = world;
            ref.lx = lx;
            ref.ly = ly;
            ref.lz = lz;

            LivingEntity ent = sbSpawnMob(world, loc, new String[]{"ENDERMITE"}, SB_S1_NAME,
                    SB_S1_HP, (double) SB_S1_DMG, "boss1", true, uuid);
            if (ent == null) {
                return false;
            }
            ref.ent = ent;

            ref.bar = sbBarMake(sbBarTitle(1));
            try {
                sbBarSync(ref);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            SB_ACTIVE.put(uuid, ref);

            try {
                sbSound(world, loc, "entity.endermite.ambient", 1.0f, 0.8f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.wither.spawn", 0.5f, 1.5f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbSound(world, loc, "entity.ender_dragon.growl", 0.7f, 1.5f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                Particle pt = sbP(new String[]{"REVERSE_PORTAL", "PORTAL", "SMOKE_NORMAL"});
                sbFx(world, pt, lx, ly + 0.5, lz, 40, 0.6, 0.5, 0.6, 0.2);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            GS.runLater(() -> sbTick(ref), SB_TICK);
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 暗影虫卵：召唤出错 " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    /** 消耗主手 1 个。 */
    private static void sbConsume(Player p) {
        try {
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand == null) {
                return;
            }
            int amt = 0;
            try {
                amt = hand.getAmount();
            } catch (Throwable e) {
                amt = 0;
            }
            if (amt > 1) {
                hand.setAmount(amt - 1);
            } else {
                inv.setItemInMainHand(null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器 ① 清掉落 + 死亡位置 + 蛆虫留龙息团
    // ==================================================================

    private boolean sbRegisterDeath() {
        if (SB_REG_DEATH.contains(REG_KEY)) {
            return true;
        }
        try {
            if (GS.pluginOrNull() == null) {
                return false;
            }
            try {
                Bukkit.getPluginManager().registerEvent(EntityDeathEvent.class,
                        new Listener(), EventPriority.HIGHEST,
                        (l, e) -> sbDeath((EntityDeathEvent) e), GS.plugin());
                SB_REG_DEATH.add(REG_KEY);
                return true;
            } catch (Throwable e1) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 暗影虫卵：死亡掉落拦截注册失败 " + e1);
                } catch (Throwable e3) {
                    // 与原脚本一致：静默
                }
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
    }

    private void sbDeath(EntityDeathEvent e) {
        try {
            LivingEntity ent = e.getEntity();
            if (ent == null) {
                return;
            }
            if (!sbOurs(ent)) {
                return;
            }

            String tag = sbTag(ent);
            Location loc;
            try {
                loc = ent.getLocation();
            } catch (Throwable err) {
                loc = null;
            }

            // ★ 原版掉落全部删除：装备 + 背包 + 战利品 + 经验
            try {
                ent.getEquipment().clear();
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            // ★ 原脚本这里还有一句 ent.getInventory().clear() —— 1.21.11 上该方法
            //   在整个生物继承链上都不存在（javap 核对）⇒ JS 每次都抛并被吞 ⇒ 死代码，不照抄。
            try {
                e.getDrops().clear();
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                e.getDrops().clear();   // 清完装备再清一遍
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                e.setDroppedExp(0);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }

            // ★★ boss 死了 ⇒ 把**死亡位置**记进这条线，下一阶段就生成在这儿
            if ("boss1".equals(tag) || "boss2".equals(tag) || "boss3".equals(tag)) {
                try {
                    Ref r0 = sbRefOf(ent);
                    if (r0 != null && loc != null) {
                        r0.dx = loc.getX();
                        r0.dy = loc.getY();
                        r0.dz = loc.getZ();
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
            }

            // ★ 「暗影蛆虫」死亡 ⇒ 死亡位置留下**原版那团龙息**
            if ("maggot".equals(tag) && loc != null) {
                try {
                    Ref r1 = sbRefOf(ent);
                    LivingEntity own = (r1 == null ? null : r1.ent);   // 云的主人 = 这条线的 boss
                    sbBreathCloud(loc.getWorld(), loc, own);
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
            }
            // 「暗影蠕虫」死亡不留云（用户只要求蛆虫留）
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器 ② 反制原版末影人行为 + 弹道命中 + 伤害定值 + 阵营保护
    // ==================================================================

    private boolean sbRegisterSpell() {
        if (SB_REG_SPELL.contains(REG_KEY)) {
            return true;
        }
        try {
            if (GS.pluginOrNull() == null) {
                return false;
            }
            int ok = 0;

            // ②-1 ★★「不吸引末影人仇恨」：目标是我们的末影螨 ⇒ 取消锁定
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityTargetLivingEntityEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbTarget((EntityTargetEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
                try {
                    Bukkit.getPluginManager().registerEvent(EntityTargetEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbTarget((EntityTargetEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-2 ★★ boss 不许乱传送
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityTeleportEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbTeleport((EntityTeleportEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-3 ★★ boss 不许搬方块 / 放方块
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityChangeBlockEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbChangeBlock((EntityChangeBlockEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-4 ★★ 弹道命中
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(ProjectileHitEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbProjectileHit((ProjectileHitEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-5 ★ 伤害定值 / 凋零
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityDamageByEntityEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbDamageByEntity((EntityDamageByEntityEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-6 ★ 兜底：我们的弹体哪怕真的炸了，也不许动地形
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityExplodeEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbExplode((EntityExplodeEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ②-7 ★★★ 龙息团只对玩家造成伤害
            //   ★ 只注册基类 EntityDamageEvent 就够了 —— EntityDamageByEntityEvent 没有自己的
            //     HandlerList（已 javap 核对：它不重写 getHandlers()），一份注册两条路都收到。
            try {
                try {
                    Bukkit.getPluginManager().registerEvent(EntityDamageEvent.class,
                            new Listener(), EventPriority.HIGHEST,
                            (l, e) -> sbCloudGuard((EntityDamageEvent) e), GS.plugin());
                    ok++;
                } catch (Throwable e1) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            boolean done = ok > 0;
            if (done) {
                SB_REG_SPELL.add(REG_KEY);
                try {
                    Bukkit.getLogger().info("[构思技艺] 暗影虫卵：反制 / 弹道 / 伤害拦截 已启用（" + ok + " 条）");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            return done;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ②-1 目标是我们的末影螨 ⇒ 取消锁定。 */
    private static void sbTarget(EntityTargetEvent e) {
        try {
            Entity tgt;
            try {
                tgt = e.getTarget();
            } catch (Throwable err) {
                tgt = null;
            }
            if (tgt == null) {
                return;
            }
            if (!sbIsOurMite(tgt)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                e.setTarget(null);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** ②-2 我们的 boss2 / boss3 一律不许传送。 */
    private static void sbTeleport(EntityTeleportEvent e) {
        try {
            Entity ent;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                ent = null;
            }
            if (ent == null) {
                return;
            }
            String tag = sbTag(ent);
            if (!"boss2".equals(tag) && !"boss3".equals(tag)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** ②-3 我们的实体不许改方块。 */
    private static void sbChangeBlock(EntityChangeBlockEvent e) {
        try {
            Entity ent;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                ent = null;
            }
            if (ent == null) {
                return;
            }
            if (!sbOurs(ent)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** ②-6 我们的弹体不许动地形。 */
    private static void sbExplode(EntityExplodeEvent e) {
        try {
            Entity ent;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                ent = null;
            }
            if (ent == null) {
                return;
            }
            if (!sbOurs(ent)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                e.blockList().clear();
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ②-4 弹道命中：
     * <ul>
     *   <li>{@code 'breath'} → <b>取消事件</b> ⇒ NMS 的 onHit 整个不跑 ⇒ 不留龙息团、无原版伤害，
     *       伤害 40 由 {@link #sbBreathBoom} 自己结算</li>
     *   <li>{@code 'arrow'} → 不取消（箭照常扎上去），只在<b>落点</b>生成「暗影蠕虫」</li>
     * </ul>
     */
    private void sbProjectileHit(ProjectileHitEvent e) {
        try {
            Projectile proj;
            try {
                proj = e.getEntity();
            } catch (Throwable err) {
                proj = null;
            }
            if (proj == null) {
                return;
            }
            String tag = sbProjTag(proj);
            if (tag.isEmpty()) {
                return;
            }

            Ref ref = sbRefOf(proj);

            if ("breath".equals(tag)) {
                try {
                    e.setCancelled(true);   // ★★ 关键一行
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                Location at = null;
                try {
                    Block hb = e.getHitBlock();
                    if (hb != null) {
                        at = hb.getLocation().add(0.5, 0.5, 0.5);
                    }
                } catch (Throwable err) {
                    at = null;
                }
                if (at == null) {
                    try {
                        Entity he = e.getHitEntity();
                        if (he != null) {
                            at = he.getLocation();
                        }
                    } catch (Throwable err) {
                        at = null;
                    }
                }
                if (at == null) {
                    try {
                        at = proj.getLocation();
                    } catch (Throwable err) {
                        at = null;
                    }
                }
                try {
                    if (at != null) {
                        sbBreathBoom(ref, at);
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    proj.remove();
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    if (ref != null && ref.breath == proj) {
                        ref.breath = null;
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                return;
            }

            if ("arrow".equals(tag)) {
                Location at2 = null;
                try {
                    Entity he2 = e.getHitEntity();
                    if (he2 != null) {
                        at2 = he2.getLocation();
                    }
                } catch (Throwable err) {
                    at2 = null;
                }
                if (at2 == null) {
                    try {
                        Block hb2 = e.getHitBlock();
                        if (hb2 != null) {
                            at2 = hb2.getLocation().add(0.5, 1.0, 0.5);
                        }
                    } catch (Throwable err) {
                        at2 = null;
                    }
                }
                if (at2 == null) {
                    try {
                        at2 = proj.getLocation();
                    } catch (Throwable err) {
                        at2 = null;
                    }
                }
                // ★ 只有三阶段的箭才生虫
                try {
                    if (ref != null && ref.stage == 3 && !ref.done && at2 != null) {
                        sbSpawnWorm(ref, at2);
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    proj.remove();   // 箭插地上就收掉，别留一地箭
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ②-5 伤害定值 / 凋零：
     * <ul>
     *   <li>{@code 'arrow'}（我们射的箭）→ 伤害<b>固定 20</b></li>
     *   <li>{@code 'boss1'}（暗影巢虫）打到玩家 → 玩家获得<b>凋零 II 5 秒</b></li>
     * </ul>
     */
    private static void sbDamageByEntity(EntityDamageByEntityEvent e) {
        try {
            Entity dam;
            try {
                dam = e.getDamager();
            } catch (Throwable err) {
                dam = null;
            }
            if (dam == null) {
                return;
            }

            String ptag = sbProjTag(dam);
            if ("arrow".equals(ptag)) {
                try {
                    e.setDamage(SB_S3_ARROW_DMG);
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                return;
            }

            String tag = sbTag(dam);
            if (!"boss1".equals(tag)) {
                return;
            }

            Entity vic;
            try {
                vic = e.getEntity();
            } catch (Throwable err) {
                vic = null;
            }
            if (vic == null) {
                return;
            }
            try {
                if (!String.valueOf(vic.getType().name()).equals("PLAYER")) {
                    return;
                }
            } catch (Throwable err) {
                return;
            }

            // ★ 每次攻击玩家 ⇒ 凋零 II 5 秒
            try {
                PotionEffectType wt = PotionEffectType.WITHER;
                if (wt != null && vic instanceof LivingEntity le) {
                    le.addPotionEffect(new PotionEffect(wt, SB_S1_WITHER_TICKS,
                            SB_S1_WITHER_AMP, false, true, true));
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★★★ 第八十三批：「龙息只对玩家造成伤害，不会对 boss 以及对应小怪造成伤害」。
     *
     * <p>原版云靠 {@code INSTANT_DAMAGE} 结算，{@code DamageSource} 的
     * <b>directEntity = 云</b>、<b>causingEntity = 云的主人</b>；而原版云<b>不认阵营</b>。
     *
     * <p>闸门逻辑（<b>只保护我们自己人</b>，玩家那边一点都不受影响）：
     * <ul>
     *   <li>受击者不在我们这条线上 ⇒ 直接放过；</li>
     *   <li>伤害的「直接来源」是我们打过记号的龙息团 ⇒ 取消；</li>
     *   <li>兜底：伤害的「元凶」是这条线上的人（= 云的主人）⇒ 取消。</li>
     * </ul>
     * ★ 顺带把「自己人误伤自己人」也一起挡了（boss 的普攻 / 我们自己射的箭）。
     */
    private static void sbCloudGuard(EntityDamageEvent e) {
        try {
            if (e == null) {
                return;
            }
            Entity vic;
            try {
                vic = e.getEntity();
            } catch (Throwable err) {
                vic = null;
            }
            if (vic == null) {
                return;
            }
            if (!sbOurs(vic)) {
                return;          // ★ 只保护我们自己人
            }

            org.bukkit.damage.DamageSource ds;
            try {
                ds = e.getDamageSource();
            } catch (Throwable err) {
                ds = null;
            }
            if (ds == null) {
                return;
            }

            Entity de;
            Entity ce;
            try {
                de = ds.getDirectEntity();
            } catch (Throwable err) {
                de = null;
            }
            try {
                ce = ds.getCausingEntity();
            } catch (Throwable err) {
                ce = null;
            }

            // ① 伤害的「直接来源」就是我们造的那团龙息
            if (de != null && !sbCloudTag(de).isEmpty()) {
                e.setCancelled(true);
                return;
            }
            // ② 兜底：伤害的「元凶」是这条线上的人（云的主人 = 本阶段的 boss）
            if (ce != null && sbOurs(ce)) {
                e.setCancelled(true);
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器 ③ 手持本物品右键方块 → 方块交互一律取消
    // ==================================================================

    private boolean sbRegisterGuard() {
        if (SB_REG_GUARD.contains(REG_KEY)) {
            return true;
        }
        try {
            if (!InteractGuard.register(SB_ITEM_ID)) {
                return false;
            }
            SB_REG_GUARD.add(REG_KEY);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            try {
                sbRegisterDeath();      // 惰性重试
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbRegisterSpell();      // 惰性重试
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                sbRegisterGuard();      // 惰性重试
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }

            if (!Guards.isMainHandUse(event, p, SB_ITEM_ID)) {
                return null;
            }

            String uuid = String.valueOf(p.getUniqueId());
            long now = sbNow();
            Long last = SB_LAST.get(uuid);
            if (last != null && (now - last) < 500) {
                return null;
            }
            SB_LAST.put(uuid, now);

            if (!sbSummon(p)) {
                return null;
            }
            if (SB_CONSUME) {
                sbConsume(p);
            }
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 暗影虫卵 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
        return null;
    }

    // ==================================================================
    // reload / 重启残留清扫
    // ==================================================================

    /**
     * 启动 3 秒后扫一遍全世界，把上一份脚本留下的「我们这条线」的实体清掉。
     *
     * <p>★★ 但<b>当前 ref 表里还活着的线</b>必须放过：否则「刚开服 3 秒内召唤的 boss」
     * 会被这把扫帚一起扫掉。
     */
    private void sbSweepLeftovers() {
        try {
            List<World> worlds = Bukkit.getWorlds();
            int n = 0;
            for (World w : worlds) {
                if (w == null) {
                    continue;
                }
                // ★ 先快照再删（边遍历边 remove 会 ConcurrentModificationException）
                List<Entity> arr = new ArrayList<>();
                try {
                    arr.addAll(w.getEntities());
                } catch (Throwable e) {
                    continue;
                }
                for (Entity ent : arr) {
                    try {
                        if (ent == null) {
                            continue;
                        }
                        if (!sbOurs(ent)) {
                            continue;
                        }
                        // ★★ 正在被管理的线**别动**
                        String own = sbOwnerOf(ent);
                        if (own != null && !own.isEmpty() && SB_ACTIVE.get(own) != null) {
                            continue;
                        }
                        ent.remove();
                        n++;
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
            if (n > 0) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 暗影虫卵：清掉上一份脚本残留实体 " + n + " 个");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 只为 {@code registerEvent} 的 {@code Listener} 形参而存在（无 {@code @EventHandler}）。 */
    private static final class Listener implements org.bukkit.event.Listener {
    }
}
