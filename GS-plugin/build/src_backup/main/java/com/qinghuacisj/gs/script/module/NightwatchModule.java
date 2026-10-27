package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Warden;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_boss_nightwatch.js} 的 Java 化版本 —— 两阶段 boss「守夜人的提灯」。
 *
 * <p>物品 {@code GS_NIGHT_LANTERN}（灵魂灯笼）。<b>手持右键</b>召唤（消耗 1 个，
 * {@link #NW_CONSUME} = true）；同一条线上还有没打完的 boss 时<b>静默忽略</b>
 * （两阶段全死才能再召）。右键后 <b>4 秒</b>（{@link #NW_FIRST_MS}）才开火。
 *
 * <pre>
 * 一阶段 巡夜守卫（洞穴蜘蛛 · 血 450 · 伤害 17）
 *   每 7 秒  弹道箭矢（箭伤 0，命中玩家给 中毒II / 缓慢III / 失明 各 3 秒）
 *   每 9 秒  白色粒子飞天（0.5 秒 20 格）⇒ 直接出现在玩家背后
 * 二阶段 守夜人（坚守者 · 血 900 · 伤害 44）
 *   出场     原版坚守者 EMERGING 动画 134 刻（在一阶段的**死亡位置**爬出来）
 *   每 8 秒  把最近的玩家拉到身前 2 格 + 控视角 1 秒 + 20 点伤害
 *   每 15 秒 召 7 只「守夜蝇」（幻翼 · 血 70 · 伤害 10 · **整波换新** · 防火 15 秒）
 *   每 90 秒 「夜深了」996 点（★ 从**爬完**开始计时）
 * 掉落
 *   一阶段死  ⇒ 固定 1 把「月镰」GS_MOON_SCYTHE
 *   二阶段死  ⇒ 固定 6 个「血夜粒」GS_BLOOD_NIGHT_GRAIN
 *   掉落点 = 各阶段的**死亡位置**
 * </pre>
 *
 * <p>★★ <b>本模块的三大反制（都是「靠 NMS 记忆按住原版 AI」）</b>：
 * <ol>
 *   <li><b>取消原版冲击波</b>：{@code SonicBoom} 行为的构造器要求
 *       {@code SONIC_BOOM_COOLDOWN} = <b>VALUE_ABSENT</b> ⇒ 每拍写一次
 *       {@link #NW_S2_MEM_HOLD}（200 刻）把它按死；外加
 *       {@code EntityDamageEvent} 里 {@code cause == SONIC_BOOM} 的兜底取消。</li>
 *   <li><b>只对最近的玩家有仇恨</b>：坚守者的 {@code getTarget()} 读的是 brain 里的
 *       {@code ATTACK_TARGET}，而 Bukkit 的 {@code Mob#setTarget} 只写 {@code Mob.target}
 *       字段（<b>对坚守者无效</b>）⇒ 每拍走 {@code getHandle().setAttackTarget(p)} 动态派发，
 *       并挂两道保险：索敌事件里「目标不是最近的玩家 ⇒ 取消」+ 每拍
 *       {@code Warden#clearAnger(其他玩家)}。</li>
 *   <li><b>不许钻地跑路</b>：{@code DIG_COOLDOWN} 记忆常在
 *       （{@code Digging} 行为到点会把自己 {@code remove()} 掉）。</li>
 * </ol>
 *
 * <p>★★★ <b>NMS 全部走反射</b>（编译期只有 paper-api，{@code net.minecraft.*} 不在
 * classpath 上）：{@link #nwNmsInit()} 探测 5 个类，探测结果与真 JS 的
 * {@code Java.type} 探测<b>逐项对应</b>；探测失败时行为与真 JS 的降级路径一致
 * （{@code setAttackTarget} 退化成 {@code Mob#setTarget}、出场动画退化成
 * 「原地定住 134 刻」、冲击波只靠伤害事件兜底）。已核对：
 * {@code Warden#clearAnger(Entity)} 在 1.21.11 的 Bukkit API 里<b>存在</b>
 * （只在 {@code org.bukkit.entity.Warden} 上，不在 {@code Mob} 上）⇒ 真 JS 那条
 * 动态派发是<b>真的生效</b>的，不是死代码，因此本移植用
 * {@code instanceof Warden} 等价实现。
 *
 * <p>★ <b>不照抄的死代码</b>（逐条实测确认，删掉不改变可观察行为）：
 * <ol>
 *   <li>{@code nwTitle(p, title)} —— 定义了<b>从未被调用</b>（全脚本 grep 只有定义行）。</li>
 *   <li>{@code nwPSafe()} + 顶层 {@code NW_P_SAFE} —— 同样<b>从未被调用</b>
 *       （真正用的是 {@code nwP(cands)}）；它的「缓存粒子对象」也是写而不读。</li>
 *   <li>{@code NW_TELE_AHEAD = 2.0} —— 顶层声明后<b>从未被读</b>
 *       （真正用的是 {@code NW_S2_GRAB_AHEAD}）。★ 常量仍然保留（常量对账要按名按值比）。</li>
 *   <li>{@code nwNext(ref, stage, …)} 的 <b>{@code stage === 1} 整支</b> ——
 *       唯一的调用点是 {@code nwPhaseEnd} 里的 {@code nwNext(ref, 2, …)}，
 *       所以「stage 1 的 name/hp/dmg/tag/typeNames 选择」与
 *       「{@code cdArrow/cdSky} 初始化」永远走不到（一阶段的这两个冷却在
 *       {@code nwSummon} 里就设好了）。</li>
 *   <li>{@code nwTransFx(…, kind)} 的 {@code kind} 形参 —— 传进去<b>从不读</b>。</li>
 *   <li>{@code nwFindMaxAttr()} 的「模糊匹配 {@code Attribute.values()}
 *       里找含 {@code max_health} 的项」兜底 —— 1.21.11 上
 *       {@code Attribute.MAX_HEALTH} 一定存在（已 javap 核对），
 *       第一个候选就命中 ⇒ 兜底不可达。</li>
 *   <li>{@code nwSpawnMob} 里的 {@code e.getInventory().clear()} ——
 *       本脚本只生成 CAVE_SPIDER / WARDEN / PHANTOM，三者<b>都不是
 *       {@code InventoryHolder}</b> ⇒ 真 JS 每次都抛异常被吞（已按
 *       {@code instanceof InventoryHolder} 等价实现，效果同为「什么都不做」）。</li>
 *   <li>{@code nwPlugin()} 的「RSC → RSC.INSTANCE → Slimefun」回退链 ——
 *       原生版没有 RSC（同 {@code GS.java} 的说明），直接用本插件实例。</li>
 *   <li>{@code nwBarSync} 里 {@code if (!NW_BAR_ONLY_OWNER)} 那一支 ——
 *       {@link #NW_BAR_ONLY_OWNER} 恒为 true。</li>
 * </ol>
 *
 * <p>★ <b>与原脚本的已知差异</b>（详见交付说明）：
 * <ul>
 *   <li>数字进日志时统一走 {@link #nwNum(double)} —— JS 的 {@code 20.0} 打成
 *       {@code "20"}，Java 直接拼会打成 {@code "20.0"}。这是<b>为了逐字节对上
 *       参考服日志</b>而必须做的格式化，不是数值改动。</li>
 *   <li>{@code nwEmergeTicks()} 返回 {@code int}：真 JS 会把
 *       {@code WardenAi.EMERGE_DURATION} 原样拼进日志（日志实测是 {@code 134}）。</li>
 *   <li>{@code nwCalmOthers} 用 {@code instanceof Warden} 取代动态派发。</li>
 * </ul>
 *
 * <p>★ <b>加载日志</b>：与参考服日志逐字节一致的 6 行见
 * {@link #nmsLine} / {@link #hpLine} / {@link #s1Line} / {@link #s2Line} /
 * {@link #counterLine} / {@link #dropLine}。
 * <br>⚠ 原脚本<b>没有</b> {@code [构思技艺] gs_boss_nightwatch 已加载} 这一行
 * （全脚本搜 {@code 已加载} 零命中；参考服日志里也没有），所以本模块<b>不打</b>
 * 这一行 —— 打了就是比真 JS 多一条可观察日志。
 */
public final class NightwatchModule extends AbstractModule {

    // ==================================================================
    // 参数（★ 名字与 JS 顶层 var 完全同名 —— 常量对账探针按名反射比对）
    // ==================================================================

    static final String NW_ITEM_ID = "GS_NIGHT_LANTERN";   // items.yml 里的物品 id
    static final boolean NW_CONSUME = true;                // 右键消耗 1 个
    static final double NW_SPAWN_AHEAD = 3.0;              // boss 生成在玩家正前方几格
    static final int NW_TICK = 5;                          // 主循环间隔（刻）= 0.25 秒
    static final long NW_FIRST_MS = 4000L;                 // 出场后多久才开火（毫秒）
    static final double NW_SEEK = 48.0;                    // 索敌半径
    static final long NW_HP_FIX_MS = 8000L;                // 出生后这段时间内每拍重试写血量
    static final int NW_TRANS_TICKS = 40;                  // 阶段切换的过渡特效时长（刻）
    static final double NW_BAR_RANGE = 48.0;               // 血条可见半径
    static final boolean NW_BAR_ONLY_OWNER = true;         // 只给召唤者看
    /** ★ 声明后从未被读（真正用的是 {@link #NW_S2_GRAB_AHEAD}）—— 见类注释死代码 ③。 */
    static final double NW_TELE_AHEAD = 2.0;

    // ---------------- 一阶段：巡夜守卫（洞穴蜘蛛） ----------------
    static final String NW_S1_NAME = "§5巡夜守卫";
    static final double NW_S1_HP = 450.0;
    static final double NW_S1_DMG = 17.0;
    static final long NW_S1_ARROW_CD = 7000L;              // 弹道箭矢冷却
    static final double NW_S1_ARROW_SPEED = 2.0;           // 箭速
    static final double NW_S1_ARROW_LIFT = 0.06;           // 水平距离 × 这个数 = 抬高
    static final int NW_S1_DEBUFF_TICKS = 60;              // 三种 debuff 各 3 秒
    static final int NW_S1_POISON_AMP = 1;                 // 中毒 II
    static final int NW_S1_SLOW_AMP = 2;                   // 缓慢 III
    static final int NW_S1_BLIND_AMP = 0;                  // 失明 I
    static final long NW_S1_SKY_CD = 9000L;                // 飞天冷却
    static final double NW_S1_SKY_BLOCKS = 20.0;           // 飞多高
    static final int NW_S1_SKY_TICKS = 10;                 // 用几刻飞完（= 0.5 秒）
    static final double NW_S1_BEHIND = 1.5;                // 出现在玩家背后几格

    // ---------------- 二阶段：守夜人（坚守者） ----------------
    static final String NW_S2_NAME = "§5守夜人";
    static final double NW_S2_HP = 900.0;
    static final double NW_S2_DMG = 44.0;
    static final long NW_S2_GRAB_CD = 8000L;               // 拉到身前冷却
    static final double NW_S2_GRAB_DMG = 20.0;             // 拉过来那一下的伤害
    static final int NW_S2_GRAB_LOCK_TICKS = 20;           // 控视角 1 秒
    static final double NW_S2_GRAB_AHEAD = 2.0;            // 拉到 boss 正前方几格
    static final long NW_S2_FLY_CD = 15000L;               // 守夜蝇冷却
    static final int NW_S2_FLY_N = 7;                      // 每波 7 只
    static final String NW_S2_FLY_NAME = "§5守夜蝇";
    static final double NW_S2_FLY_HP = 70.0;
    static final double NW_S2_FLY_DMG = 10.0;
    /** ★ = {@link #NW_S2_FLY_CD}：这一波守夜蝇的「防火窗口」长度。 */
    static final long NW_S2_FLY_FIRE_MS = NW_S2_FLY_CD;
    static final long NW_S2_NIGHT_CD = 90000L;             // 夜深了冷却
    static final double NW_S2_NIGHT_DMG = 996.0;           // 夜深了伤害
    static final String NW_S2_NIGHT_MSG = "§5夜深了";
    static final int NW_S2_MEM_HOLD = 200;                 // 两条原版记忆按住多少刻
    static final int NW_EMERGE_FALLBACK_TICKS = 134;       // 出场动画时长兜底（读不到 WardenAi 时用）

    // ---------------- 掉落 ----------------
    static final String NW_DROP_S1_ID = "GS_MOON_SCYTHE";
    static final int NW_DROP_S1_N = 1;
    static final String NW_DROP_S2_ID = "GS_BLOOD_NIGHT_GRAIN";
    static final int NW_DROP_S2_N = 6;

    // ==================================================================
    // 运行期状态（★ 对应 JS 的顶层变量 —— 名字保持同名，便于按名对账）
    // ==================================================================

    /** JS {@code NW_ACTIVE}：召唤者 uuid → 这条线的状态。 */
    private final Map<String, Ref> NW_ACTIVE = new HashMap<>();
    /** JS {@code NW_LAST}：召唤者 uuid → 上次右键时刻（500ms 去抖）。 */
    private final Map<String, Long> NW_LAST = new HashMap<>();
    /** JS {@code NW_HP_DIAG}：已经就血量问题告过警的实体。 */
    private final Map<String, Boolean> NW_HP_DIAG = new HashMap<>();

    private final NamespacedKey NW_KEY;       // gs:nightwatch        → 'boss1' / 'boss2' / 'fly'
    private final NamespacedKey NW_PROJ_KEY;  // gs:nightwatch_proj   → 'arrow'
    private final NamespacedKey NW_OWN_KEY;   // gs:nightwatch_owner  → 召唤者 uuid

    /** JS {@code NW_NMS_TRIED}：NMS 探测只做一次。 */
    private boolean NW_NMS_TRIED = false;
    /** JS {@code NW_NMS_OK}：{@code NW_MMT != null && NW_UNIT != null}。 */
    private boolean NW_NMS_OK = false;
    /** JS {@code NW_MMT} = {@code Java.type('net.minecraft...MemoryModuleType')}。 */
    private Class<?> NW_MMT = null;
    /** JS {@code NW_UNIT} = {@code Java.type('net.minecraft.util.Unit')}。 */
    private Class<?> NW_UNIT = null;
    /** JS {@code NW_POSE} = {@code Java.type('net.minecraft.world.entity.Pose')}。 */
    private Class<?> NW_POSE = null;
    /** JS {@code NW_SONIC} = {@code Java.type('...behavior.warden.SonicBoom')}。 */
    private Class<?> NW_SONIC = null;
    /** JS {@code NW_WARDEN_AI} = {@code Java.type('...monster.warden.WardenAi')}。 */
    private Class<?> NW_WARDEN_AI = null;
    /** JS {@code NW_MAXATTR}：血量属性对象的缓存（只读不写死）。 */
    private Attribute NW_MAXATTR = null;

    /** JS {@code NW_REG_DEATH}：死亡掉落拦截是否已注册。 */
    private boolean NW_REG_DEATH = false;
    /** JS {@code NW_REG_SPELL}：弹道 / 反冲击波 / 索敌限制是否已注册。 */
    private boolean NW_REG_SPELL = false;
    /** JS {@code NW_REG_GUARD}：方块交互拦截是否已注册。 */
    private boolean NW_REG_GUARD = false;

    // ==================================================================
    // 构造器 = 原脚本的顶层代码
    // ==================================================================

    public NightwatchModule() {
        // ① 顶层第一件事：初始化 PDC 键（原脚本 199~204 行）
        this.NW_KEY = nwKey("gs:nightwatch");
        this.NW_PROJ_KEY = nwKey("gs:nightwatch_proj");
        this.NW_OWN_KEY = nwKey("gs:nightwatch_owner");

        // ② 顶层：nwNmsInit()（它自己会打「NMS 通道」那一行）
        try {
            nwNmsInit();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ③ 顶层：6 行加载日志（与参考服日志逐字节一致）
        nwLog(nmsLine(NW_NMS_OK, NW_SONIC != null, NW_WARDEN_AI != null));
        nwLog(hpLine());
        nwLog(s1Line());
        nwLog(s2Line(nwEmergeTicks()));
        nwLog(counterLine());
        nwLog(dropLine());

        // ④ 顶层：3 秒后清掉上一份脚本残留的实体
        try {
            GS.runLater(() -> {
                try {
                    nwSweepLeftovers();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, 60L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默（原脚本这里是 try/catch 包着 runLater）
        }
    }

    @Override
    public String name() {
        return "gs_boss_nightwatch";
    }

    // ==================================================================
    // 加载日志行（★ 抽成静态方法，好让探针逐字节对账）
    // ==================================================================

    /** 打一条 {@code [构思技艺]} 日志（原脚本是 {@code getServer().getLogger().info(...)}）。 */
    private static void nwLog(String line) {
        try {
            Bukkit.getLogger().info(line);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * JS 的 {@code Number} → 字符串。
     *
     * <p>★★ <b>为什么必须有它</b>：JS 里所有数字都是 double，
     * 但 {@code 20.0} 拼进字符串是 {@code "20"}；Java 的 {@code double}
     * 直接拼会得到 {@code "20.0"} ⇒ 日志就对不上了。
     * 整数一律打成整数形式，其余交给 {@code Double.toString}（同为最短往返表示）。
     */
    static String nwNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0e21) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /**
     * 第 1 行：NMS 通道自检。
     *
     * <p>★ 三个布尔量在真机上是 {@link #nwNmsInit()} 的探测结果；
     * 参考服日志那一行对应 {@code (true, true, true)}。
     * 抽成纯函数是为了让探针能拿真值逐字节比对。
     */
    static String nmsLine(boolean ok, boolean sonicOk, boolean wardenAiOk) {
        return "[构思技艺] 守夜人的提灯 NMS 通道："
                + (ok ? "可用" : "不可用（降级：只靠 Bukkit 事件兜底）")
                + " ｜ SonicBoom=" + (sonicOk ? "ok" : "null")
                + " ｜ WardenAi=" + (wardenAiOk ? "ok" : "null");
    }

    /** 第 2 行：两阶段目标血量。 */
    static String hpLine() {
        return "[构思技艺] 守夜人的提灯 两阶段目标血量：巡夜守卫=" + nwNum(NW_S1_HP)
                + "（洞穴蜘蛛·伤害 " + nwNum(NW_S1_DMG) + "） 守夜人=" + nwNum(NW_S2_HP)
                + "（坚守者·伤害 " + nwNum(NW_S2_DMG) + "）";
    }

    /** 第 3 行：一阶段技能。 */
    static String s1Line() {
        return "[构思技艺] 巡夜守卫：弹道箭矢每 " + (NW_S1_ARROW_CD / 1000) + "秒"
                + "（箭伤 0，命中给 中毒II / 缓慢III / 失明 各 " + nwNum(NW_S1_DEBUFF_TICKS / 20.0) + "秒）"
                + " ｜ 白色粒子飞天每 " + (NW_S1_SKY_CD / 1000) + "秒 " + nwNum(NW_S1_SKY_BLOCKS)
                + "格 / " + nwNum(NW_S1_SKY_TICKS / 20.0) + "秒 ⇒ 直接出现在玩家背后";
    }

    /** 第 4 行：二阶段技能（出场刻数由 {@link #nwEmergeTicks()} 传进来）。 */
    static String s2Line(int emergeTicks) {
        return "[构思技艺] 守夜人：出场动画 " + emergeTicks + " 刻（原版坚守者 EMERGING）"
                + " ｜ 拉到身前每 " + (NW_S2_GRAB_CD / 1000) + "秒（控视角 "
                + nwNum(NW_S2_GRAB_LOCK_TICKS / 20.0) + "秒 + " + nwNum(NW_S2_GRAB_DMG) + " 点伤害）"
                + " ｜ 守夜蝇每 " + (NW_S2_FLY_CD / 1000) + "秒 " + NW_S2_FLY_N + "只（幻翼·血 "
                + nwNum(NW_S2_FLY_HP) + "·伤害 " + nwNum(NW_S2_FLY_DMG)
                + "·**整波换新**·防火 " + (NW_S2_FLY_FIRE_MS / 1000) + "秒）"
                + " ｜ 夜深了每 " + (NW_S2_NIGHT_CD / 1000) + "秒（" + nwNum(NW_S2_NIGHT_DMG)
                + " 点，从爬出来开始计时）";
    }

    /** 第 5 行：反制手段。 */
    static String counterLine() {
        return "[构思技艺] 守夜人 反制：**取消原版冲击波**（SONIC_BOOM_COOLDOWN 记忆按死 "
                + NW_S2_MEM_HOLD + " 刻 + SONIC_BOOM 伤害取消）"
                + " ｜ **只对最近的玩家有仇恨**（brain ATTACK_TARGET 每拍改写 + 索敌事件取消 + 清他人愤怒）"
                + " ｜ 不许钻地跑路（DIG_COOLDOWN 常在）";
    }

    /** 第 6 行：掉落。 */
    static String dropLine() {
        return "[构思技艺] 守夜人的提灯 掉落：一阶段固定 " + NW_DROP_S1_N + " 把「月镰」(" + NW_DROP_S1_ID + ")"
                + " ｜ 二阶段固定 " + NW_DROP_S2_N + " 个「血夜粒」(" + NW_DROP_S2_ID + ")"
                + " ｜ 掉落点 = 各阶段的**死亡位置**";
    }

    // ==================================================================
    // 纯算式（★ 抽成静态方法：真实代码与探针走同一条路径，不做两份实现）
    // ==================================================================

    /** {@code yaw * Math.PI / 180.0}。 */
    static double nwRad(double yaw) {
        return yaw * Math.PI / 180.0;
    }

    /** 「正前方 dist 格」的 X（JS：{@code x + (-Math.sin(rad)) * dist}）。 */
    static double nwFrontX(double x, double rad, double dist) {
        return x + (-Math.sin(rad)) * dist;
    }

    /** 「正前方 dist 格」的 Z（JS：{@code z + (Math.cos(rad)) * dist}）。 */
    static double nwFrontZ(double z, double rad, double dist) {
        return z + (Math.cos(rad)) * dist;
    }

    /** 「背后 dist 格」的 X（JS：{@code tx + Math.sin(rad) * dist}）。 */
    static double nwBehindX(double tx, double rad, double dist) {
        return tx + Math.sin(rad) * dist;
    }

    /** 「背后 dist 格」的 Z（JS：{@code tz - Math.cos(rad) * dist}）。 */
    static double nwBehindZ(double tz, double rad, double dist) {
        return tz - Math.cos(rad) * dist;
    }

    /** 水平距离（JS：{@code Math.sqrt(dx*dx + dz*dz)}）。 */
    static double nwFlat(double dx, double dz) {
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * 箭矢初速。
     *
     * <p>原脚本（{@code gs_boss_nightwatch.js:831-835}）：
     * <pre>
     *   var lift = flat * NW_S1_ARROW_LIFT;
     *   var dir = nwVec(dx, dy + lift, dz);
     *   if (dir == null) return false;
     *   dir.normalize();                          // ← 看起来"返回值被丢掉了"
     *   ar.setVelocity(dir.multiply(NW_S1_ARROW_SPEED));
     * </pre>
     *
     * <p>★★ <b>这里有一个很容易踩的坑，我实测过才敢下结论</b>：
     * 老版本 Bukkit 的 {@code Vector#normalize()} 是<b>不可变</b>的
     * （{@code return multiply(1 / length())}），照那个印象读，
     * 会以为 {@code dir.normalize()} 是空操作、箭速其实没归一化。
     * <b>但本项目锁定的 paper-api 1.21.11 里它是原地修改的</b> ——
     * {@code javap -p -c org.bukkit.util.Vector} 的字节码是：
     * <pre>
     *   public org.bukkit.util.Vector normalize();
     *      0: aload_0
     *      1: invokevirtual #34   // Method length:()D
     *      4: dstore_1
     *      5: aload_0
     *      6: dup
     *      7: getfield      #7    // Field x:D
     *     10: dload_1
     *     11: ddiv
     *     12: putfield      #7    // Field x:D      ← 直接写回字段
     *     ...
     * </pre>
     * 所以 {@code dir} <b>确实被归一化了</b>，初速就是
     * {@code unit(dx, dy+lift, dz) × NW_S1_ARROW_SPEED}。
     * （{@code multiply} 同样是原地修改。）
     *
     * <p>运算顺序也照抄：先 {@code len = length()}，再逐分量
     * {@code x = x / len}，最后 {@code x = x * speed}。
     * 不能写成 {@code x * (1/len) * speed} —— 结合律不同，末位会差。
     * 已由 {@code NightwatchParityProbe} 的 {@code [C1]} 用<b>真脚本自己的
     * nwFireArrow</b>（GraalJS 里调真的 {@code org.bukkit.util.Vector}）
     * 在 96 组输入上逐值钉住。
     *
     * @return {@code {vx, vy, vz}}
     */
    static double[] nwArrowVel(double dx, double dy, double dz, double flat) {
        double y = dy + flat * NW_S1_ARROW_LIFT;
        double len = Math.sqrt(dx * dx + y * y + dz * dz);
        // dir.normalize()（原地）⇒ dir.multiply(NW_S1_ARROW_SPEED)（原地）
        return new double[]{(dx / len) * NW_S1_ARROW_SPEED,
                (y / len) * NW_S1_ARROW_SPEED,
                (dz / len) * NW_S1_ARROW_SPEED};
    }

    /** 控视角用的 yaw（JS：{@code Math.atan2(-dx, dz) * 180.0 / Math.PI}）。 */
    static double nwLookYaw(double dx, double dz) {
        return Math.atan2(-dx, dz) * 180.0 / Math.PI;
    }

    /** 控视角用的 pitch（JS：{@code -Math.atan2(dy, flat) * 180.0 / Math.PI}）。 */
    static double nwLookPitch(double dy, double flat) {
        return -Math.atan2(dy, flat) * 180.0 / Math.PI;
    }

    /**
     * 血条进度的分子分母（JS：{@code if (mx <= 0) mx = ref.maxHp; prog = hp / (mx > 0 ? mx : 1)}）。
     *
     * @param fallback {@code mx <= 0} 时改用的 {@code ref.maxHp}
     */
    static double nwBarProg(double hp, double mx, double fallback) {
        if (mx <= 0) {
            mx = fallback;
        }
        return hp / (mx > 0 ? mx : 1.0);
    }

    /** 血条进度钳制（JS：{@code if (prog < 0) prog = 0; if (prog > 1) prog = 1;}）。 */
    static double nwBarClamp(double prog) {
        if (prog < 0) {
            prog = 0;
        }
        if (prog > 1) {
            prog = 1;
        }
        return prog;
    }

    /** 一阶段/二阶段开场冷却的写法（JS：{@code now - CD + NW_FIRST_MS}）。 */
    static long nwFirstCd(long now, long cd) {
        return now - cd + NW_FIRST_MS;
    }

    /** 飞天每一刻上升几格（JS：{@code NW_S1_SKY_BLOCKS / NW_S1_SKY_TICKS}）。 */
    static double nwSkyStep() {
        return NW_S1_SKY_BLOCKS / NW_S1_SKY_TICKS;
    }

    /** 阶段过渡特效第 i 环的半径（JS：{@code 1.0 + frac * 2.6}）。 */
    static double nwTransRadius(double frac) {
        return 1.0 + frac * 2.6;
    }

    /** 阶段过渡特效的角度（JS：{@code a * (Math.PI / 6.0) + frac * 2.0}）。 */
    static double nwTransAngle(int a, double frac) {
        return a * (Math.PI / 6.0) + frac * 2.0;
    }

    /** 守夜蝇绕 boss 一圈的角度（JS：{@code i * 2.0 * Math.PI / NW_S2_FLY_N}）。 */
    static double nwRingAngle(int i) {
        return i * 2.0 * Math.PI / NW_S2_FLY_N;
    }

    /** 守夜蝇生成点的 X 偏移（JS：{@code Math.cos(ang) * 2.0}）。 */
    static double nwRingX(double ang) {
        return Math.cos(ang) * 2.0;
    }

    /** 守夜蝇生成点的 Z 偏移（JS：{@code Math.sin(ang) * 2.0}）。 */
    static double nwRingZ(double ang) {
        return Math.sin(ang) * 2.0;
    }

    // ==================================================================
    // 世界 / 实体小工具（逐字对应原脚本的 nw* 工具函数）
    // ==================================================================

    private static NamespacedKey nwKey(String s) {
        try {
            return NamespacedKey.fromString(s);
        } catch (Throwable e) {
            return null;
        }
    }

    private static long nwNow() {
        return Guards.now();
    }

    private static boolean nwAlive(Entity e) {
        try {
            return e != null && !e.isDead() && e.isValid();
        } catch (Throwable err) {
            return false;
        }
    }

    static Location nwLoc(World w, double x, double y, double z, float yaw, float pitch) {
        try {
            return new Location(w, x, y, z, yaw, pitch);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Vector nwVec(double x, double y, double z) {
        try {
            return new Vector(x, y, z);
        } catch (Throwable e) {
            return null;
        }
    }

    /** JS {@code nwTagOf}：读 PDC 字符串，拿不到就是空串。 */
    private static String nwTagOf(Entity e, NamespacedKey key) {
        try {
            if (e == null || key == null) {
                return "";
            }
            String v = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            return v == null ? "" : String.valueOf(v);
        } catch (Throwable err) {
            return "";
        }
    }

    private String nwTag(Entity e) {
        return nwTagOf(e, NW_KEY);
    }

    private String nwProjTag(Entity e) {
        return nwTagOf(e, NW_PROJ_KEY);
    }

    private String nwOwnerOf(Entity e) {
        return nwTagOf(e, NW_OWN_KEY);
    }

    private void nwSetTagOf(Entity e, NamespacedKey key, String val) {
        try {
            if (e == null || key == null) {
                return;
            }
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, val);
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    private boolean nwOurs(Entity e) {
        try {
            if (e == null || NW_KEY == null) {
                return false;
            }
            if (e.getPersistentDataContainer().has(NW_KEY, PersistentDataType.STRING)) {
                return true;
            }
            if (NW_PROJ_KEY != null
                    && e.getPersistentDataContainer().has(NW_PROJ_KEY, PersistentDataType.STRING)) {
                return true;
            }
            return false;
        } catch (Throwable err) {
            return false;
        }
    }

    /**
     * 指定坐标能不能站人。
     *
     * <p>★ 原脚本的顺序：{@code getBlockAt} → {@code getType().isAir()} → {@code isPassable()}
     * → {@code !isSolid()}。{@code isPassable()} 那一句<b>不在 try 里包着返回值</b>，
     * 只有它抛异常才会落到 {@code !isSolid()}。
     */
    private static boolean nwPassableAt(World world, int bx, int by, int bz) {
        try {
            if (world == null) {
                return true;
            }
            Block blk = world.getBlockAt(bx, by, bz);
            if (blk == null) {
                return true;
            }
            Material mt = null;
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
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            try {
                return blk.isPassable();
            } catch (Throwable e) {
                // 与原脚本一致：静默，落到下面的兜底
            }
            if (mt != null) {
                try {
                    return !mt.isSolid();
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** 从 (bx, by, bz) 往上数 height 格是否都站得下。 */
    private static boolean nwHeadroom(World world, int bx, int bz, int by, int height) {
        for (int i = 0; i < height; i++) {
            if (!nwPassableAt(world, bx, by + i, bz)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 找一个能站人的 y。
     *
     * <p>★ 原脚本用 {@code Math.floor}（返回 double）算循环边界；坐标恒为有限值时
     * 与整型写法<b>逐点等价</b>。非有限值（NaN / ±Inf）在 JS 里两段循环都不进、
     * 直接 {@code return y0} ⇒ 这里显式挡掉，保持同一条出口。
     */
    static double nwSafeY(World world, double x, double z, double y0, int height) {
        try {
            if (world == null) {
                return y0;
            }
            if (!Double.isFinite(x) || !Double.isFinite(z) || !Double.isFinite(y0)) {
                return y0;
            }
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
            for (int y = start; y >= lo; y--) {
                if (nwHeadroom(world, bx, bz, y, height) && !nwPassableAt(world, bx, y - 1, bz)) {
                    return y;
                }
            }
            for (int y = start; y <= hi; y++) {
                if (nwHeadroom(world, bx, bz, y, height)) {
                    return y;
                }
            }
            return y0;
        } catch (Throwable e) {
            return y0;
        }
    }

    /**
     * 按名字取粒子。
     *
     * <p>★ 原脚本先试静态字段 {@code P[name]}、再试 {@code P.valueOf(name)}。
     * 对枚举常量两者<b>必然同值</b>，所以这里只用 {@code valueOf}（语义等价、少一层反射）。
     */
    private static Particle nwP(String[] cands) {
        try {
            for (String name : cands) {
                try {
                    Particle p = Particle.valueOf(name);
                    if (p != null) {
                        return p;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默，试下一个候选
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    private static EntityType nwET(String[] cands) {
        try {
            for (String name : cands) {
                try {
                    EntityType t = EntityType.valueOf(name);
                    if (t != null) {
                        return t;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默，试下一个候选
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * 放音效。
     *
     * <p>★ 原脚本直接走 {@code world.playSound(loc, name, vol, pitch)} 的
     * <b>字符串重载</b>，失败才退回 {@code loc.getWorld().playSound(...)} ——
     * 本脚本用的全是 {@code entity.xxx.yyy} 这种原版键名，没有同名 Sound 常量。
     */
    static void nwSound(World world, Location at, String name, float vol, float pitch) {
        try {
            try {
                world.playSound(at, name, vol, pitch);
            } catch (Throwable e1) {
                try {
                    at.getWorld().playSound(at, name, vol, pitch);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private static void nwFx(World world, Particle pt, double x, double y, double z,
                             int n, double dx, double dy, double dz, double sp) {
        try {
            if (world == null || pt == null) {
                return;
            }
            world.spawnParticle(pt, x, y, z, n, dx, dy, dz, sp);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private static void nwMsg(Player p, String msg) {
        // ★ 原脚本第一步走 RSC 的全局 sendMessage(p, msg)（会翻译 & 颜色码）；
        //   原生版等价物就是 Chat.t + p.sendMessage。
        try {
            p.sendMessage(Chat.t(msg));
            return;
        } catch (Throwable e) {
            // 与原脚本一致：静默，落到下面的正则兜底
        }
        try {
            // 与原脚本同一条正则：&([0-9a-fA-Fk-oK-R]) → §$1
            p.sendMessage(msg.replaceAll("&([0-9a-fA-Fk-oK-OrR])", "§$1"));
            return;
        } catch (Throwable e2) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // NMS 通道（★ 全反射：编译期只有 paper-api）
    // ==================================================================

    private static Class<?> nwClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object nwStaticField(Class<?> c, String name) {
        if (c == null) {
            return null;
        }
        try {
            Field f = c.getField(name);
            return f.get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 按「名字 + 参数个数」找 public 方法 —— 对应 GraalJS 的按名动态派发。
     *
     * <p>优先返回参数类型与实际实参相容的那个重载（避免多重重载时挑错）。
     */
    private static Method nwMethod(Class<?> c, String name, int arity, Object[] args) {
        if (c == null) {
            return null;
        }
        try {
            Method fallback = null;
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != arity) {
                    continue;
                }
                if (fallback == null) {
                    fallback = m;
                }
                Class<?>[] ps = m.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < arity; i++) {
                    Class<?> p = ps[i];
                    Object a = args[i];
                    if (p.isPrimitive()) {
                        if (p == boolean.class) {
                            if (!(a instanceof Boolean)) {
                                ok = false;
                            }
                        } else if (!(a instanceof Number)) {
                            ok = false;
                        }
                    } else if (a != null && !p.isInstance(a)) {
                        ok = false;
                    }
                    if (!ok) {
                        break;
                    }
                }
                if (ok) {
                    return m;
                }
            }
            return fallback;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 调用一个反射方法，并把「整型实参」按形参类型装箱。
     *
     * <p>★ 必须做这件事：{@code setMemoryWithExpiry} 的过期时间是 {@code int}
     * 还是 {@code long} 随版本变过，传错装箱类型会 {@code IllegalArgumentException}。
     */
    private static Object nwInvoke(Method m, Object target, Object... args) throws Throwable {
        Class<?>[] ps = m.getParameterTypes();
        Object[] conv = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            Class<?> p = ps[i];
            if (a instanceof Number n) {
                if (p == long.class || p == Long.class) {
                    conv[i] = n.longValue();
                } else if (p == int.class || p == Integer.class) {
                    conv[i] = n.intValue();
                } else if (p == double.class || p == Double.class) {
                    conv[i] = n.doubleValue();
                } else if (p == float.class || p == Float.class) {
                    conv[i] = n.floatValue();
                } else {
                    conv[i] = a;
                }
            } else {
                conv[i] = a;
            }
        }
        return m.invoke(target, conv);
    }

    /** 取 CraftEntity 的 NMS 句柄（{@code ent.getHandle()}）。 */
    private static Object nwHandle(Entity ent) throws Throwable {
        Method m = nwMethod(ent.getClass(), "getHandle", 0, new Object[0]);
        if (m == null) {
            return null;
        }
        return m.invoke(ent);
    }

    /**
     * NMS 通道探测 —— 与真 JS 的 {@code nwNmsInit()} 逐项对应。
     *
     * <p>★ 只探测一次（{@link #NW_NMS_TRIED} 闩锁），日志也只打一次 —— 与原脚本一致。
     */
    boolean nwNmsInit() {
        try {
            if (NW_NMS_TRIED) {
                return NW_NMS_OK;
            }
            NW_NMS_TRIED = true;
            try {
                NW_MMT = nwClass("net.minecraft.world.entity.ai.memory.MemoryModuleType");
            } catch (Throwable e) {
                NW_MMT = null;
            }
            try {
                NW_UNIT = nwClass("net.minecraft.util.Unit");
            } catch (Throwable e) {
                NW_UNIT = null;
            }
            try {
                NW_POSE = nwClass("net.minecraft.world.entity.Pose");
            } catch (Throwable e) {
                NW_POSE = null;
            }
            try {
                NW_SONIC = nwClass("net.minecraft.world.entity.ai.behavior.warden.SonicBoom");
            } catch (Throwable e) {
                NW_SONIC = null;
            }
            try {
                NW_WARDEN_AI = nwClass("net.minecraft.world.entity.monster.warden.WardenAi");
            } catch (Throwable e) {
                NW_WARDEN_AI = null;
            }
            NW_NMS_OK = (NW_MMT != null && NW_UNIT != null);
            try {
                Bukkit.getLogger().info(
                        nmsLine(NW_NMS_OK, NW_SONIC != null, NW_WARDEN_AI != null));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            NW_NMS_OK = false;
        }
        return NW_NMS_OK;
    }

    /**
     * 出场动画时长（刻）。
     *
     * <p>★ 原脚本：{@code v = WardenAi.EMERGE_DURATION; if (v != null && v > 0) return v;}
     * 读不到就用兜底值 {@link #NW_EMERGE_FALLBACK_TICKS}。
     * {@code v} 若不是数字，JS 的 {@code v > 0} 会得到 false ⇒ 同样走兜底 —— 这里用
     * {@code instanceof Number} 等价表达。
     */
    int nwEmergeTicks() {
        try {
            nwNmsInit();
            if (NW_WARDEN_AI != null) {
                Object v = nwStaticField(NW_WARDEN_AI, "EMERGE_DURATION");
                if (v instanceof Number n && n.doubleValue() > 0) {
                    return (int) n.doubleValue();
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return NW_EMERGE_FALLBACK_TICKS;
    }

    /**
     * ★★★ 「在一阶段死亡位置爬出来（原版坚守者出场动画）」。
     *
     * <p>照抄 1.21.11 {@code Warden#finalizeSpawn} 里 {@code EntitySpawnReason.TRIGGERED}
     * 那一支：{@code setPose(Pose.EMERGING)} +
     * {@code brain.setMemoryWithExpiry(IS_EMERGING, Unit.INSTANCE, WardenAi.EMERGE_DURATION)}。
     */
    boolean nwEmerge(LivingEntity ent) {
        try {
            if (!nwNmsInit()) {
                return false;
            }
            Object hn = nwHandle(ent);
            if (hn == null) {
                return false;
            }
            Object brain = nwInvoke(nwMethod(hn.getClass(), "getBrain", 0, new Object[0]), hn);
            if (brain == null) {
                return false;
            }
            Object mmt = nwStaticField(NW_MMT, "IS_EMERGING");
            Object unit = nwStaticField(NW_UNIT, "INSTANCE");
            Method set = nwMethod(brain.getClass(), "setMemoryWithExpiry", 3,
                    new Object[]{mmt, unit, nwEmergeTicks()});
            if (set == null) {
                return false;
            }
            nwInvoke(set, brain, mmt, unit, nwEmergeTicks());
            if (NW_POSE != null) {
                try {
                    Object pose = nwStaticField(NW_POSE, "EMERGING");
                    Method setPose = nwMethod(hn.getClass(), "setPose", 1, new Object[]{pose});
                    if (setPose != null) {
                        nwInvoke(setPose, hn, pose);
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * ★★★ 把两条「原版记忆」按住：
     * <ol>
     *   <li>{@code DIG_COOLDOWN} 常在 ⇒ 不会「钻地跑路」；</li>
     *   <li>{@code SONIC_BOOM_COOLDOWN} 常在（走 {@code SonicBoom.setCooldown}）
     *       ⇒ 冲击波行为永远起不来。</li>
     * </ol>
     */
    boolean nwHoldMemories(LivingEntity ent) {
        try {
            if (!nwNmsInit()) {
                return false;
            }
            Object hn = nwHandle(ent);
            if (hn == null) {
                return false;
            }
            Object brain = nwInvoke(nwMethod(hn.getClass(), "getBrain", 0, new Object[0]), hn);
            if (brain == null) {
                return false;
            }
            try {
                Object mmt = nwStaticField(NW_MMT, "DIG_COOLDOWN");
                Object unit = nwStaticField(NW_UNIT, "INSTANCE");
                Method set = nwMethod(brain.getClass(), "setMemoryWithExpiry", 3,
                        new Object[]{mmt, unit, NW_S2_MEM_HOLD});
                if (set != null) {
                    nwInvoke(set, brain, mmt, unit, NW_S2_MEM_HOLD);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                if (NW_SONIC != null) {
                    Method cd = nwMethod(NW_SONIC, "setCooldown", 2, new Object[]{hn, NW_S2_MEM_HOLD});
                    if (cd != null) {
                        nwInvoke(cd, null, hn, NW_S2_MEM_HOLD);
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * ★★★ 「只对最近的玩家有仇恨」。
     *
     * <p>坚守者的 {@code getTarget()} 读 brain 里的 {@code ATTACK_TARGET}，
     * 而 Bukkit 的 {@code Mob#setTarget} 只写 {@code Mob.target} 字段 ⇒ 对坚守者无效。
     * 真正的入口是 {@code Warden#setAttackTarget(LivingEntity)} ⇒ 走 {@code getHandle()}
     * 动态派发。拿不到 NMS 时退回 {@code Mob#setTarget}。
     */
    boolean nwSetTarget(Ref ref, Player p) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return false;
            }
            Object pn = null;
            try {
                pn = (p == null ? null : nwHandle(p));
            } catch (Throwable e) {
                pn = null;
            }
            boolean done = false;
            try {
                Object hn = nwHandle(boss);
                if (hn != null) {
                    Method m = nwMethod(hn.getClass(), "setAttackTarget", 1, new Object[]{pn});
                    if (m != null) {
                        nwInvoke(m, hn, pn);
                        done = true;
                    }
                }
            } catch (Throwable e) {
                done = false;
            }
            if (!done) {
                nwSetMobTarget(boss, p);
            }
            nwHoldMemories(boss);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 原版愤怒系统那边也别记恨别人（和 {@code ATTACK_TARGET} 是两条独立的路）。
     *
     * <p>★ 真 JS 是 {@code boss.clearAnger(p)} 的<b>动态派发</b>：
     * {@code clearAnger} 只存在于 {@code org.bukkit.entity.Warden}（已 javap 核对），
     * 所以一阶段的洞穴蜘蛛会抛异常被吞 —— 而本函数只在二阶段调用，
     * {@code instanceof Warden} 与之一一对应。
     */
    void nwCalmOthers(Ref ref, Player keep) {
        try {
            if (ref == null) {
                return;
            }
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return;
            }
            World world = ref.lw;
            if (world == null) {
                return;
            }
            for (Player p : world.getPlayers()) {
                if (p == null) {
                    continue;
                }
                boolean same = false;
                try {
                    same = (keep != null)
                            && String.valueOf(p.getUniqueId()).equals(String.valueOf(keep.getUniqueId()));
                } catch (Throwable e) {
                    same = false;
                }
                if (same) {
                    continue;
                }
                try {
                    if (boss instanceof Warden w) {
                        w.clearAnger(p);
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ---------------- 属性（血量 / 伤害） ----------------

    /**
     * 按候选名取属性对象。
     *
     * <p>★ 1.21.11 上 {@code ATTACK_DAMAGE} / {@code MAX_HEALTH} 都在，
     * 所以 {@code GENERIC_*} 那一批候选永远轮不到（真 JS 也一样）。
     */
    private static Attribute nwAttrKey(String[] names) {
        try {
            for (String name : names) {
                try {
                    Attribute a = (Attribute) Attribute.class.getField(name).get(null);
                    if (a != null) {
                        return a;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默，试下一个
                }
                try {
                    Attribute b = Attribute.valueOf(name);
                    if (b != null) {
                        return b;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默，试下一个
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    private Attribute nwFindMaxAttr() {
        try {
            if (NW_MAXATTR != null) {
                return NW_MAXATTR;
            }
            Attribute k = nwAttrKey(new String[]{"MAX_HEALTH", "GENERIC_MAX_HEALTH"});
            if (k != null) {
                NW_MAXATTR = k;
                return k;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    private double nwMaxHp(LivingEntity ent) {
        try {
            Attribute k = nwFindMaxAttr();
            if (k == null) {
                return 0.0;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return 0.0;
            }
            return inst.getValue();
        } catch (Throwable e) {
            return 0.0;
        }
    }

    /**
     * 强设血量上限。
     *
     * <p>★ 先把属性上已有的修饰符摘掉（否则「+20 最大生命」这类会顶掉目标值），
     * 再 {@code setBaseValue}；最后<b>无论成败</b>都补一刀 {@code setMaxHealth}
     * （与原脚本一致 —— 两条路都试）。
     */
    private boolean nwForceMax(LivingEntity ent, double hp) {
        boolean ok = false;
        try {
            Attribute k = nwFindMaxAttr();
            if (k != null) {
                AttributeInstance inst = ent.getAttribute(k);
                if (inst != null) {
                    try {
                        // ★ 先快照再删：直接边遍历边删会 ConcurrentModificationException
                        List<org.bukkit.attribute.AttributeModifier> snap =
                                new ArrayList<>(inst.getModifiers());
                        for (org.bukkit.attribute.AttributeModifier m : snap) {
                            try {
                                inst.removeModifier(m);
                            } catch (Throwable e) {
                                // 与原脚本一致：静默
                            }
                        }
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        inst.setBaseValue(hp);
                        ok = true;
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            ent.setMaxHealth(hp);
            ok = true;
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return ok;
    }

    private void nwSetDmg(LivingEntity ent, double want) {
        try {
            Attribute k = nwAttrKey(new String[]{"ATTACK_DAMAGE", "GENERIC_ATTACK_DAMAGE"});
            if (k == null) {
                return;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return;
            }
            double cur = inst.getValue();
            double base = inst.getBaseValue();
            inst.setBaseValue(base + (want - cur));
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 血量设置失败时的那一条 warning（★ 同一实体只报一次）。
     *
     * <p>逐字照抄原脚本的拼接（{@code why} / 实体类型 / 目标 / 上限 / 实际 / 属性键）。
     */
    private void nwHpDiag(LivingEntity ent, double want, double max, double cur, String why) {
        try {
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                id = "unknown";
            }
            if (NW_HP_DIAG.get(id) != null) {
                return;
            }
            NW_HP_DIAG.put(id, Boolean.TRUE);
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                tn = "?";
            }
            String kname = "null";
            try {
                Attribute k = nwFindMaxAttr();
                if (k != null) {
                    kname = String.valueOf(k.name());
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯 血量设置失败(" + why + ")：实体="
                        + tn + " 目标=" + nwNum(want) + " 实际上限=" + nwNum(max)
                        + " 实际血量=" + nwNum(cur) + " MAX_HEALTH键=" + kname);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 打一下某个实体（JS {@code nwHurt(src, ent, dmg)}）。
     *
     * <p>★ 先清无敌帧，再 {@code damage(dmg, src)}；后者失败才退回 {@code damage(dmg)}。
     */
    private static void nwHurt(LivingEntity src, LivingEntity ent, double dmg) {
        try {
            try {
                ent.setNoDamageTicks(0);
            } catch (Throwable e) {
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
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private boolean nwApplyHpOnce(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return false;
            }
            nwForceMax(ent, hp);
            double mx = nwMaxHp(ent);
            if (mx < hp - 0.5) {
                nwHpDiag(ent, hp, mx, -1.0, "上限没上去");
                return false;
            }
            try {
                ent.setHealth(hp);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            double now = -1.0;
            try {
                now = ent.getHealth();
            } catch (Throwable e) {
                now = -1.0;
            }
            if (now >= hp - 0.5) {
                return true;
            }
            nwHpDiag(ent, hp, mx, now, "血量没回满");
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 写血量：失败就排一串延迟重试（与原脚本同一串 {@code [1,3,6,10,20,40]}）。 */
    private void nwApplyHp(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return;
            }
            if (nwApplyHpOnce(ent, hp)) {
                return;
            }
            int[] delays = {1, 3, 6, 10, 20, 40};
            final LivingEntity e0 = ent;
            final double h0 = hp;
            for (int d : delays) {
                GS.runLater(() -> {
                    try {
                        if (!nwAlive(e0)) {
                            return;
                        }
                        nwApplyHpOnce(e0, h0);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }, d);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ---------------- 血条 ----------------

    /**
     * 血条标题 —— 原脚本就是<b>直接返回阶段名</b>（不拼血量）。
     *
     * <p>★ 那两个 {@code hp}/{@code mx} 局部变量在原脚本里算了但没用上（只写不读）。
     */
    private String nwBarTitle(int stage) {
        if (stage == 1) {
            return NW_S1_NAME;      // 巡夜守卫
        }
        return NW_S2_NAME;          // 守夜人
    }

    private static BossBar nwBarMake(String title) {
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
            } catch (Throwable e) {
                c = null;
            }
        }
        if (c == null) {
            try {
                c = BarColor.WHITE;
            } catch (Throwable e) {
                c = null;
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
            } catch (Throwable e) {
                s = null;
            }
        }
        if (c == null || s == null) {
            return null;
        }
        BossBar bar = null;
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
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            bar.setProgress(1.0);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return bar;
    }

    /**
     * 血条同步。
     *
     * <p>★ 只在「血条可见」时重建 {@code want} —— 不可见就直接清空，
     * 否则残留的观察者会一直挂着（原脚本注释里点明了这一点）。
     *
     * <p>★ {@code prog} 的计算<b>整段在 try 里</b>，失败时归 0（原脚本 535~540 行）；
     * 玩家筛选里的 {@code pl.distance(el)} <b>没有</b>单条 try 包着 ⇒
     * 它一抛异常整个扫描就中断（已装进 {@code want} 的照旧生效）。
     */
    private void nwBarSync(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            LivingEntity ent = ref.ent;
            boolean alive = nwAlive(ent);

            double prog = 0.0;
            if (alive) {
                try {
                    double hp = ent.getHealth();
                    double mx = nwMaxHp(ent);
                    prog = nwBarProg(hp, mx, ref.maxHp);
                } catch (Throwable e) {
                    prog = 0.0;
                }
            }
            prog = nwBarClamp(prog);
            try {
                ref.bar.setProgress(prog);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.bar.setVisible(alive);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            Map<String, Player> want = new LinkedHashMap<>();
            if (alive) {
                try {
                    String wname = String.valueOf(ent.getWorld().getName());
                    Location el = ent.getLocation();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        if (NW_BAR_ONLY_OWNER) {
                            try {
                                if (!String.valueOf(p.getUniqueId())
                                        .equals(String.valueOf(ref.owner))) {
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
                        // ★ 这一句在原脚本里**没有**单条 try ⇒ 抛出去会中断整个扫描
                        if (pl.distance(el) > NW_BAR_RANGE) {
                            continue;
                        }
                        try {
                            want.put(String.valueOf(p.getUniqueId()), p);
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默（want 里已装进去的照样生效）
                }
            }

            for (Map.Entry<String, Player> en : want.entrySet()) {
                String k = en.getKey();
                if (ref.barNear.get(k) == null) {
                    try {
                        ref.bar.addPlayer(en.getValue());
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    ref.barNear.put(k, en.getValue());
                }
            }
            for (String k : new ArrayList<>(ref.barNear.keySet())) {
                if (want.get(k) == null) {
                    try {
                        ref.bar.removePlayer(ref.barNear.get(k));
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        ref.barNear.remove(k);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private void nwBarDrop(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            try {
                ref.bar.setVisible(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.bar.removeAll();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.barNear.clear();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            ref.bar = null;
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ---------------- 索敌 / 反查 ----------------

    private Player nwNearestPlayer(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return null;
            }
            World world = ref.lw;
            if (world == null) {
                return null;
            }
            Location bl = boss.getLocation();
            if (bl == null) {
                return null;
            }
            Player best = null;
            double bestD = NW_SEEK;
            for (Player p : world.getPlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (p.isDead()) {
                        continue;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默，继续判定
                }
                try {
                    String gm = String.valueOf(p.getGameMode().name());
                    if ("SPECTATOR".equals(gm)) {
                        continue;
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                try {
                    double d = p.getLocation().distance(bl);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                } catch (Throwable e2) {
                    continue;
                }
            }
            return best;
        } catch (Throwable e) {
            return null;
        }
    }

    private Ref nwRefOf(Entity ent) {
        try {
            if (ent == null) {
                return null;
            }
            String u = nwOwnerOf(ent);
            if (u == null || u.isEmpty()) {
                return null;
            }
            return NW_ACTIVE.get(u);
        } catch (Throwable e) {
            return null;
        }
    }

    // ---------------- 生成生物 ----------------

    private LivingEntity nwSpawnMob(World world, Location loc, String[] typeNames, String name,
                                    double hp, double dmg, String tagValue, boolean aware,
                                    String ownerUuid) {
        try {
            EntityType t = nwET(typeNames);
            if (t == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯：找不到实体类型 "
                            + typeNames[0]);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                return null;
            }
            Entity e = world.spawnEntity(loc, t);
            if (e == null) {
                return null;
            }
            if (!(e instanceof LivingEntity le)) {
                // ★ 真 JS 会把这个非生物实体原样返回（随后每次调用都抛异常被吞）；
                //   本脚本只生成 CAVE_SPIDER / WARDEN / PHANTOM，走不到这里。
                return null;
            }
            nwApplyHp(le, hp);
            if (dmg >= 0) {
                nwSetDmg(le, dmg);
            }
            try {
                le.setCustomName(name);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                le.setCustomNameVisible(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                le.setRemoveWhenFarAway(false);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                le.setPersistent(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                le.setCanPickupItems(false);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                if (le instanceof Mob mob) {
                    mob.setAware(aware);
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                le.setSilent(false);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                EntityEquipment eq = le.getEquipment();
                if (eq != null) {
                    eq.clear();
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            try {
                // ★ 本脚本生成的三种生物都不是 InventoryHolder ⇒ 真 JS 每次都抛异常被吞，
                //   效果同为「什么都不做」（见类注释死代码 ⑦）。
                if (le instanceof InventoryHolder ih) {
                    Inventory inv = ih.getInventory();
                    if (inv != null) {
                        inv.clear();
                    }
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
            nwSetTagOf(le, NW_KEY, tagValue);
            if (ownerUuid != null) {
                nwSetTagOf(le, NW_OWN_KEY, ownerUuid);
            }
            return le;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯：生成 " + typeNames[0]
                        + " 出错 " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return null;
        }
    }

    // ---------------- 掉落 ----------------

    private static ItemStack nwSfStack(String id) {
        try {
            SlimefunItem sf = SlimefunItem.getById(id);
            if (sf != null) {
                return sf.getItem().clone();
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    private static boolean nwDropOne(World world, Double x, Double y, Double z, String id, Integer amount) {
        try {
            if (world == null || id == null) {
                return false;
            }
            if (x == null || z == null) {
                return false;
            }
            ItemStack stack = nwSfStack(id);
            if (stack == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯：取不到 " + id + "，未掉落");
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                return false;
            }
            try {
                stack.setAmount(amount == null || amount <= 0 ? 1 : amount);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            world.dropItemNaturally(
                    new Location(world, x, (y == null ? 0.0 : y) + 0.5, z), stack);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------- 阶段推进 ----------------

    private void nwTransFx(World world, double x, double y, double z, int i, int kind) {
        try {
            if (world == null || i >= 6) {
                return;
            }
            Particle pt = nwP(new String[]{"SCULK_SOUL", "SOUL_FIRE_FLAME", "SMOKE"});
            Particle pt2 = nwP(new String[]{"END_ROD", "WHITE_ASH", "SNOWFLAKE", "CLOUD"});
            double frac = i / 6.0;
            double r = nwTransRadius(frac);
            for (int a = 0; a < 12; a++) {
                double ang = nwTransAngle(a, frac);
                nwFx(world, pt, x + Math.cos(ang) * r, y + 0.6 + frac * 1.5, z + Math.sin(ang) * r,
                        3, 0.1, 0.2, 0.1, 0.3);
                nwFx(world, pt2, x + Math.cos(ang) * r * 0.7, y + 1.2 + frac * 2.2,
                        z + Math.sin(ang) * r * 0.7, 2, 0.1, 0.2, 0.1, 0.05);
            }
            if (i == 1) {
                try {
                    nwSound(world, nwLoc(world, x, y, z, 0f, 0f), "entity.spider.death", 1.2f, 0.7f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            if (i == 3) {
                try {
                    nwSound(world, nwLoc(world, x, y, z, 0f, 0f), "entity.warden.nearby_closer", 1.2f, 1.0f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            final World w0 = world;
            final double x0 = x;
            final double y0 = y;
            final double z0 = z;
            final int i0 = i;
            GS.runLater(() -> nwTransFx(w0, x0, y0, z0, i0 + 1, kind), 1L);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 阶段结束（一阶段死 → 二阶段爬出来；二阶段死 → 收摊）。
     *
     * <p>★ 掉落点 = <b>各阶段的死亡位置</b>：优先用死亡事件写下的精确坐标
     * （{@code ref.dx/dy/dz}），没有就用召唤时记下的坐标兜底。
     */
    void nwPhaseEnd(Ref ref) {
        try {
            if (ref == null || ref.transitioning) {
                return;
            }
            ref.transitioning = true;
            try {
                nwBarDrop(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.skyGen = ref.skyGen + 1;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwPurgeMinions(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            int stage = ref.stage;
            final World world = ref.lw;
            final double x;
            final double y;
            final double z;
            if (ref.dx != null && ref.dy != null && ref.dz != null) {
                x = ref.dx;
                y = ref.dy;
                z = ref.dz;
            } else {
                x = (ref.lx == null ? 0.0 : ref.lx);
                y = (ref.ly == null ? 64.0 : ref.ly);
                z = (ref.lz == null ? 0.0 : ref.lz);
            }

            if (stage == 1) {
                nwDropOne(world, x, y, z, NW_DROP_S1_ID, NW_DROP_S1_N);
            } else if (stage >= 2) {
                nwDropOne(world, x, y, z, NW_DROP_S2_ID, NW_DROP_S2_N);
            }

            if (stage >= 2) {
                nwCleanup(ref);
                return;
            }

            nwTransFx(world, x, y, z, 0, 1);
            GS.runLater(() -> {
                try {
                    nwNext(ref, 2, world, x, y, z);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }, NW_TRANS_TICKS);
        } catch (Throwable e) {
            nwCleanup(ref);
        }
    }

    /**
     * 生成下一阶段（二阶段 = 坚守者「守夜人」，带原版出场动画）。
     *
     * <p>★ 唯一的调用点是 {@link #nwPhaseEnd} 里的 {@code stage = 2}
     * ⇒ 原脚本里 {@code stage === 1} 那一整支是<b>死代码</b>，未照抄（见类注释死代码 ④）。
     */
    void nwNext(Ref ref, int stage, World world, double x, double y, double z) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (world == null) {
                nwCleanup(ref);
                return;
            }

            int height = 3;                    // 坚守者 3 格（stage 1 的洞穴蜘蛛 1 格走不到）
            double sy = nwSafeY(world, x, z, y, height);
            Location loc = nwLoc(world, x, sy, z, 0f, 0f);

            String name = NW_S2_NAME;
            double hp = NW_S2_HP;
            double dmg = NW_S2_DMG;
            String tag = "boss2";
            String[] typeNames = {"WARDEN"};

            LivingEntity ent = nwSpawnMob(world, loc, typeNames, name, hp, dmg, tag, true, ref.owner);
            if (ent == null) {
                nwCleanup(ref);
                return;
            }

            try {
                ent.setCanPickupItems(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            ref.stage = stage;
            ref.ent = ent;
            ref.name = name;
            ref.maxHp = hp;

            long now = nwNow();
            ref.hpFixUntil = now + NW_HP_FIX_MS;
            ref.hpOk = false;
            ref.readyAt = now + NW_FIRST_MS;
            ref.transitioning = false;

            // （原脚本此处是 if (stage === 1) … else …；stage 恒为 2 ⇒ 只保留 else 支）
            ref.flies = new LinkedHashMap<>();
            ref.cdGrab = nwFirstCd(now, NW_S2_GRAB_CD);
            ref.cdFly = nwFirstCd(now, NW_S2_FLY_CD);

            // ★ 血量 / 伤害再压一次（坚守者自带的上限很高，必须强设）
            try {
                nwApplyHp(ent, NW_S2_HP);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSetDmg(ent, NW_S2_DMG);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ★ 压住「钻地跑路」+「原版冲击波」
            try {
                nwHoldMemories(ent);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ★★★ 原版坚守者出场动画（在一阶段的死亡位置「爬出来」）
            int et = nwEmergeTicks();
            boolean okEmerge = false;
            try {
                okEmerge = nwEmerge(ent);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            if (!okEmerge) {
                // 拿不到 NMS ⇒ 退化成「原地定住这么久」，至少节奏和动画一致
                try {
                    ent.setAI(false);
                    final LivingEntity e0 = ent;
                    GS.runLater(() -> {
                        try {
                            e0.setAI(true);
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                    }, et);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            ref.emergeUntil = now + et * 50L;
            // ★ 用户：「从地里爬出来开始计时90秒」⇒ 计时从**爬完**开始
            ref.cdNight = ref.emergeUntil + NW_S2_NIGHT_CD;

            try {
                nwSound(world, loc, "entity.warden.emerge", 2.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, loc, "entity.warden.agitated", 2.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                Particle pt = nwP(new String[]{"SCULK_SOUL", "SOUL_FIRE_FLAME", "SMOKE"});
                nwFx(world, pt, x, sy + 0.4, z, 50, 0.9, 0.6, 0.9, 0.12);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            ref.bar = nwBarMake(nwBarTitle(stage));
            try {
                nwBarSync(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            GS.runLater(() -> nwTick(ref), NW_TICK);
        } catch (Throwable e) {
            nwCleanup(ref);
        }
    }

    private void nwCleanup(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            ref.done = true;
            ref.transitioning = false;
            try {
                nwBarDrop(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.skyGen = (ref.skyGen) + 1;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwPurgeMinions(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // 万一还留着残兵
            try {
                if (nwAlive(ref.ent)) {
                    try {
                        ref.ent.getEquipment().clear();
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        ref.ent.remove();
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            ref.ent = null;

            // 解锁「这个玩家还能再召唤」
            try {
                if (ref.owner != null && NW_ACTIVE.get(ref.owner) == ref) {
                    NW_ACTIVE.remove(ref.owner);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能：一阶段
    // ==================================================================

    /**
     * 弹道箭矢（冷却 7 秒）。
     *
     * <p>★ 箭伤按 <b>0</b> 处理（用户只给了三种 debuff，没给箭伤）。
     * 命中玩家 ⇒ 中毒 II / 缓慢 III / 失明，各 3 秒。
     */
    void nwFireArrow(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return;
            }
            Player tgt = nwNearestPlayer(ref);
            if (tgt == null) {
                return;
            }
            World world = ref.lw;

            Location from;
            try {
                from = boss.getEyeLocation();
            } catch (Throwable e) {
                from = boss.getLocation();
            }
            Location to = tgt.getLocation();

            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double dy = (to.getY() + 1.0) - from.getY();
            double flat = nwFlat(dx, dz);
            if (flat < 0.05 && Math.abs(dy) < 0.05) {
                return;
            }

            Arrow ar;
            try {
                ar = world.spawn(from, Arrow.class);
            } catch (Throwable e) {
                ar = null;
            }
            if (ar == null) {
                return;
            }

            try {
                ar.setShooter(boss);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ar.setDamage(0.0);          // ★ 箭伤 0：debuff 才是它的活
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ar.setKnockbackStrength(0);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ar.setCritical(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ar.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ★ 弹道补偿：按水平距离抬一点枪口，让抛物线落在玩家身上
            double[] v = nwArrowVel(dx, dy, dz, flat);
            if (v == null) {
                return;
            }
            try {
                ar.setVelocity(nwVec(v[0], v[1], v[2]));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            nwSetTagOf(ar, NW_PROJ_KEY, "arrow");
            nwSetTagOf(ar, NW_OWN_KEY, ref.owner);
            ref.arrow = ar;

            try {
                nwSound(world, from, "entity.arrow.shoot", 1.0f, 1.1f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private static void nwApplyDebuff(Player p) {
        try {
            if (p == null) {
                return;
            }
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.POISON,
                        NW_S1_DEBUFF_TICKS, NW_S1_POISON_AMP, false, true, true));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                        NW_S1_DEBUFF_TICKS, NW_S1_SLOW_AMP, false, true, true));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                        NW_S1_DEBUFF_TICKS, NW_S1_BLIND_AMP, false, true, true));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 白色粒子飞天：0.5 秒 20 格，然后**直接出现在玩家背后**。
     *
     * <p>★ 世代号（{@code skyGen}）负责让「换阶段 / 收摊」时旧链自己停手 ——
     * {@code runLater} 拿不到句柄、cancel 不掉，只能这么停。
     */
    boolean nwSkyAscend(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return false;
            }
            World world = ref.lw;
            Location bl = boss.getLocation();
            final double sx = bl.getX();
            final double sy = bl.getY();
            final double sz = bl.getZ();
            ref.skyGen = ref.skyGen + 1;
            final long gen = ref.skyGen;
            final double stepUp = nwSkyStep();
            nwSound(world, bl, "entity.illusioner.mirror_move", 1.0f, 1.4f);

            class Ascend {
                int i = 0;

                void step() {
                    try {
                        if (ref.done || ref.skyGen != gen) {
                            return;
                        }
                        if (!nwAlive(boss)) {
                            return;
                        }
                        if (i >= NW_S1_SKY_TICKS) {
                            nwAppearBehind(ref);
                            return;
                        }
                        i++;
                        double y = sy + stepUp * i;
                        Location at = nwLoc(world, sx, y, sz, 0f, 0f);
                        try {
                            boss.setVelocity(nwVec(0, 0, 0));
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                        try {
                            boss.teleport(at);
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                        Particle pt = nwP(new String[]{"END_ROD", "WHITE_ASH", "SNOWFLAKE", "CLOUD"});
                        nwFx(world, pt, sx, y + 0.4, sz, 16, 0.3, 0.3, 0.3, 0.02);
                        try {
                            nwSound(world, at, "entity.phantom.flap", 0.7f, 1.6f);
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                        GS.runLater(this::step, 1L);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
            new Ascend().step();
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 飞到玩家**背后** {@link #NW_S1_BEHIND} 格处落地（朝向保持原样）。 */
    void nwAppearBehind(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return;
            }
            World world = ref.lw;
            Player tgt = nwNearestPlayer(ref);
            if (tgt == null) {
                return;
            }

            Location tl = tgt.getLocation();
            double yaw = 0.0;
            try {
                yaw = tl.getYaw();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            double rad = nwRad(yaw);
            // 玩家正前方 = (-sin, cos) ⇒ 背后取反
            double bx = nwBehindX(tl.getX(), rad, NW_S1_BEHIND);
            double bz = nwBehindZ(tl.getZ(), rad, NW_S1_BEHIND);
            double by = nwSafeY(world, bx, bz, tl.getY(), 1);
            Location at = nwLoc(world, bx, by, bz, (float) yaw, 0f);

            // 出发点（天上）的白色粒子收个尾
            try {
                Location bl = boss.getLocation();
                Particle pt0 = nwP(new String[]{"END_ROD", "WHITE_ASH", "SNOWFLAKE", "CLOUD"});
                nwFx(world, pt0, bl.getX(), bl.getY() + 0.4, bl.getZ(), 30, 0.4, 0.4, 0.4, 0.06);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            try {
                boss.teleport(at);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                if (boss instanceof Mob mob) {
                    mob.setTarget(tgt);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // 落点特效
            try {
                Particle pt1 = nwP(new String[]{"END_ROD", "WHITE_ASH", "SNOWFLAKE", "CLOUD"});
                nwFx(world, pt1, bx, by + 0.4, bz, 36, 0.4, 0.7, 0.4, 0.10);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, at, "entity.enderman.teleport", 1.0f, 1.4f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, at, "entity.spider.ambient", 1.0f, 1.5f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能：二阶段
    // ==================================================================

    /** 把玩家拉到 boss 正前方 {@link #NW_S2_GRAB_AHEAD} 格 + 控视角 1 秒 + 20 点伤害。 */
    void nwGrabPlayer(Ref ref, Player p) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss) || p == null) {
                return;
            }
            World world = ref.lw;
            Location bl = boss.getLocation();
            double yaw = 0.0;
            try {
                yaw = bl.getYaw();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            double rad = nwRad(yaw);
            double fx = nwFrontX(bl.getX(), rad, NW_S2_GRAB_AHEAD);
            double fz = nwFrontZ(bl.getZ(), rad, NW_S2_GRAB_AHEAD);
            double fy = nwSafeY(world, fx, fz, bl.getY(), 2);

            Location from;
            try {
                from = p.getLocation();
            } catch (Throwable e) {
                from = null;
            }

            // 出发点特效（被拽走）
            if (from != null) {
                try {
                    Particle ptOut = nwP(new String[]{"SCULK_SOUL", "REVERSE_PORTAL", "PORTAL",
                            "SOUL_FIRE_FLAME"});
                    nwFx(world, ptOut, from.getX(), from.getY() + 1.0, from.getZ(),
                            26, 0.35, 0.6, 0.35, 0.08);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    nwSound(world, from, "entity.enderman.teleport", 1.0f, 0.7f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }

            // ★ 面向 boss（yaw + 180 ⇒ 玩家正对坚守者的脸）
            Location loc = nwLoc(world, fx, fy, fz, (float) (yaw + 180.0), 0f);
            try {
                p.teleport(loc);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // 落点特效
            try {
                Particle ptIn = nwP(new String[]{"SCULK_SOUL", "PORTAL", "SOUL_FIRE_FLAME"});
                nwFx(world, ptIn, fx, fy + 1.0, fz, 30, 0.35, 0.7, 0.35, 0.12);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, loc, "entity.warden.angry", 1.2f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ★ 控视角 1 秒（每刻强行把玩家的视线掰向 boss）
            nwLockView(ref, p, NW_S2_GRAB_LOCK_TICKS);

            // ★ 20 点伤害
            nwHurt(boss, p, NW_S2_GRAB_DMG);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 控视角 {@code ticks} 刻：每刻把玩家的朝向掰向 boss。 */
    void nwLockView(Ref ref, Player p, int ticks) {
        try {
            class Lock {
                int left = ticks;

                void step() {
                    try {
                        if (ref == null || ref.done) {
                            return;
                        }
                        if (left <= 0) {
                            return;
                        }
                        left--;
                        LivingEntity boss = ref.ent;
                        if (!nwAlive(boss) || !nwAlive(p)) {
                            return;
                        }
                        Location bl = boss.getLocation();
                        Location pl = p.getLocation();
                        double dx = bl.getX() - pl.getX();
                        double dz = bl.getZ() - pl.getZ();
                        double dy = (bl.getY() + 1.4) - (pl.getY() + 1.62);
                        double flat = nwFlat(dx, dz);
                        // Minecraft：yaw 0 = +Z（南），yaw = atan2(-dx, dz)
                        double yaw = nwLookYaw(dx, dz);
                        double pitch = nwLookPitch(dy, flat);
                        try {
                            p.setRotation((float) yaw, (float) pitch);
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                        GS.runLater(this::step, 1L);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
            new Lock().step();
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 召 7 只「守夜蝇」（幻翼）。
     *
     * <p>★ <b>整波换新</b>：先把上一波全清掉再召；这一波在
     * {@link #NW_S2_FLY_FIRE_MS} 内不会被烧（窗口一过恢复原版「白天会烧」）。
     */
    void nwSummonFlies(Ref ref) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return;
            }
            World world = ref.lw;
            Location bl = boss.getLocation();
            // ★★★ **整波换新** —— 先把上一波守夜蝇全部清掉再召新的，
            //   免得一波波累加（每 15 秒 7 只 ⇒ 一分钟能堆到 28 只）。
            nwClearFlies(ref);
            // ★ 这一波的「防火」截止时刻 = 召唤时刻 + NW_S2_FLY_FIRE_MS
            ref.flyFireUntil = nwNow() + NW_S2_FLY_FIRE_MS;
            int n = 0;
            for (int i = 0; i < NW_S2_FLY_N; i++) {
                double ang = nwRingAngle(i);
                double fx = bl.getX() + nwRingX(ang);
                double fz = bl.getZ() + nwRingZ(ang);
                double fy = nwSafeY(world, fx, fz, bl.getY() + 1.0, 1);
                Location at = nwLoc(world, fx, fy, fz, 0f, 0f);

                LivingEntity m = nwSpawnMob(world, at, new String[]{"PHANTOM"}, NW_S2_FLY_NAME,
                        NW_S2_FLY_HP, NW_S2_FLY_DMG, "fly", true, ref.owner);
                if (m == null) {
                    continue;
                }
                // ★ setSize 会重算上限 ⇒ 改完尺寸再压一次血量
                try {
                    if (m instanceof Phantom ph) {
                        ph.setSize(1);
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                nwApplyHp(m, NW_S2_FLY_HP);
                ref.flies.put(String.valueOf(m.getUniqueId()), m);
                n++;
            }
            if (n > 0) {
                try {
                    Particle pt = nwP(new String[]{"SCULK_SOUL", "SOUL_FIRE_FLAME", "SMOKE"});
                    nwFx(world, pt, bl.getX(), bl.getY() + 1.4, bl.getZ(), 40, 1.2, 0.5, 1.2, 0.10);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    nwSound(world, bl, "entity.phantom.flap", 1.4f, 0.8f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    nwSound(world, bl, "entity.warden.roar", 1.0f, 1.2f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 清掉这一波守夜蝇，返回清掉的只数。 */
    int nwClearFlies(Ref ref) {
        try {
            if (ref == null) {
                return 0;
            }
            int n = 0;
            for (String k : new ArrayList<>(ref.flies.keySet())) {
                LivingEntity f = ref.flies.get(k);
                if (f != null && nwAlive(f)) {
                    try {
                        f.remove();
                        n++;
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
                try {
                    ref.flies.remove(k);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            return n;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 清掉小怪 + 那一发箭。 */
    void nwPurgeMinions(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            nwClearFlies(ref);
            try {
                if (ref.arrow != null && nwAlive(ref.arrow)) {
                    ref.arrow.remove();
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                ref.arrow = null;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 「夜深了」：996 点 + 一句提示（+ 一圈冲击波音画，但那**不是**原版冲击波）。 */
    void nwNightStrike(Ref ref, Player tgt) {
        try {
            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                return;
            }
            World world = ref.lw;
            Location bl;
            try {
                bl = boss.getLocation();
            } catch (Throwable e) {
                bl = null;
            }

            // 演出：坚守者的冲击波音效 + 一团灵魂粒子（**不是**原版冲击波，只是音画）
            if (bl != null) {
                try {
                    nwSound(world, bl, "entity.warden.sonic_charge", 2.0f, 0.7f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    nwSound(world, bl, "entity.warden.sonic_boom", 2.0f, 0.6f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    nwSound(world, bl, "block.sculk_shrieker.shriek", 2.0f, 0.8f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    Particle pt = nwP(new String[]{"SONIC_BOOM", "SCULK_SOUL", "SOUL_FIRE_FLAME"});
                    nwFx(world, pt, bl.getX(), bl.getY() + 1.6, bl.getZ(),
                            60, 1.6, 1.0, 1.6, 0.20);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }

            if (tgt != null) {
                // ★ 996 点伤害
                nwHurt(boss, tgt, NW_S2_NIGHT_DMG);
                // ★ 提示「夜深了」
                nwMsg(tgt, NW_S2_NIGHT_MSG);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 主循环
    // ==================================================================

    void nwTick(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (ref.transitioning) {
                return;
            }

            LivingEntity boss = ref.ent;
            if (!nwAlive(boss)) {
                nwPhaseEnd(ref);
                return;
            }

            long now = nwNow();

            // ⓪ 记下「这一拍它在哪」—— 二阶段要生成在**一阶段的死亡位置**，
            //    boss 死透之后再 getLocation() 就不一定准了 ⇒ 每拍先存一份兜底。
            try {
                Location bl0 = boss.getLocation();
                ref.dx = bl0.getX();
                ref.dy = bl0.getY();
                ref.dz = bl0.getZ();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ① 血量兜底（出生后 NW_HP_FIX_MS 内每拍重试，直到确认写成功）
            try {
                if (!ref.hpOk && now < ref.hpFixUntil) {
                    if (nwApplyHpOnce(boss, ref.maxHp)) {
                        ref.hpOk = true;
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ② 名字 / 血量条
            try {
                String cn = "";
                try {
                    cn = String.valueOf(boss.getCustomName());
                } catch (Throwable e) {
                    cn = "";
                }
                if (cn.indexOf(ref.name) < 0) {
                    boss.setCustomName(ref.name);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwBarSync(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            Player target = null;
            try {
                target = nwNearestPlayer(ref);
            } catch (Throwable e) {
                target = null;
            }

            // ================= 一阶段：巡夜守卫（洞穴蜘蛛）=================
            if (ref.stage == 1) {
                // ③ 主动索敌（洞穴蜘蛛自己会追，这里只是保证「盯的是最近的那个」）
                try {
                    LivingEntity cur = nwGetMobTarget(boss);
                    if (target != null) {
                        boolean need = (cur == null);
                        if (!need) {
                            try {
                                need = !String.valueOf(cur.getUniqueId())
                                        .equals(String.valueOf(target.getUniqueId()));
                            } catch (Throwable e) {
                                need = true;
                            }
                        }
                        if (need) {
                            nwSetMobTarget(boss, target);
                        }
                    } else if (cur != null) {
                        nwSetMobTarget(boss, null);
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }

                boolean canCast = (now >= ref.readyAt) && (target != null);

                // · 冷却 7 秒：弹道箭矢（中毒 II / 缓慢 III / 失明 各 3 秒）
                if (canCast && now - ref.cdArrow >= NW_S1_ARROW_CD) {
                    ref.cdArrow = now;
                    nwFireArrow(ref);
                }
                // · 冷却 9 秒：白色粒子飞天（0.5 秒 20 格）→ 出现在玩家背后
                if (canCast && now - ref.cdSky >= NW_S1_SKY_CD) {
                    ref.cdSky = now;
                    nwSkyAscend(ref);
                }
            }

            // ================= 二阶段：守夜人（坚守者）=================
            else if (ref.stage == 2) {
                boolean emerging = (now < ref.emergeUntil);

                // ★ 出场动画期间**不塞索敌目标** ——
                //   `Emerging` 行为的构造器要求 `WALK_TARGET` = VALUE_ABSENT，
                //   塞了目标就会让 Activity.EMERGE 起不来、动画直接没了。
                if (!emerging) {
                    try {
                        nwSetTarget(ref, target);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        nwCalmOthers(ref, target);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }

                boolean canCast2 = (now >= ref.readyAt) && (target != null) && !emerging;

                // · 冷却 8 秒：拉到身前 + 控视角 1 秒 + 20 点伤害
                if (canCast2 && now - ref.cdGrab >= NW_S2_GRAB_CD) {
                    ref.cdGrab = now;
                    nwGrabPlayer(ref, target);
                }
                // · 冷却 15 秒：7 只「守夜蝇」
                if (canCast2 && now - ref.cdFly >= NW_S2_FLY_CD) {
                    ref.cdFly = now;
                    nwSummonFlies(ref);
                }
                // · 冷却 90 秒：「夜深了」（★ 从爬出来开始计时）
                if (!emerging && now >= ref.cdNight) {
                    ref.cdNight = now + NW_S2_NIGHT_CD;
                    if (target != null) {
                        nwNightStrike(ref, target);
                    }
                }

                // ④ 「守夜蝇」维护：**防火窗口内**不烧、始终咬最近的玩家
                try {
                    for (String fk : new ArrayList<>(ref.flies.keySet())) {
                        LivingEntity f = ref.flies.get(fk);
                        if (f == null || !nwAlive(f)) {
                            ref.flies.remove(fk);
                            continue;
                        }
                        // ★★★ 灭火**只在防火窗口内**兜底 ——
                        //   窗口 = 召唤时刻 + NW_S2_FLY_FIRE_MS（= 下个召唤技能冷却结束）。
                        //   窗口一过就**不再兜底** ⇒ 幻翼恢复原版「白天会烧」。
                        try {
                            if (now < ref.flyFireUntil && f.getFireTicks() > 0) {
                                f.setFireTicks(0);
                            }
                        } catch (Throwable e) {
                            // 与原脚本一致：静默
                        }
                        if (target == null) {
                            continue;
                        }
                        LivingEntity fc = nwGetMobTarget(f);
                        boolean need2 = (fc == null);
                        if (!need2) {
                            try {
                                need2 = !String.valueOf(fc.getUniqueId())
                                        .equals(String.valueOf(target.getUniqueId()));
                            } catch (Throwable e) {
                                need2 = true;
                            }
                        }
                        if (need2) {
                            nwSetMobTarget(f, target);
                        }
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }

            // ⑤ 继续
            GS.runLater(() -> nwTick(ref), NW_TICK);
        } catch (Throwable e) {
            try {
                if (ref != null && nwAlive(ref.ent)) {
                    GS.runLater(() -> nwTick(ref), NW_TICK);
                } else {
                    nwCleanup(ref);
                }
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code Mob#getTarget} 的等价物（非 Mob 时真 JS 抛异常被吞 ⇒ 这里给 null）。 */
    private static LivingEntity nwGetMobTarget(LivingEntity ent) {
        try {
            if (ent instanceof Mob mob) {
                return mob.getTarget();
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code Mob#setTarget} 的等价物（非 Mob 时真 JS 抛异常被吞 ⇒ 这里什么都不做）。 */
    private static void nwSetMobTarget(LivingEntity ent, LivingEntity t) {
        try {
            if (ent instanceof Mob mob) {
                mob.setTarget(t);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 召唤入口
    // ==================================================================

    boolean nwSummon(Player p) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // 这条线上还有没打完的 → 静默忽略（两阶段全死才能再召）
            Ref old = NW_ACTIVE.get(uuid);
            if (old != null && !old.done) {
                return false;
            }

            World world = p.getWorld();
            Location base = p.getLocation();
            double yaw = 0.0;
            try {
                yaw = base.getYaw();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            double rad = nwRad(yaw);
            double lx = nwFrontX(base.getX(), rad, NW_SPAWN_AHEAD);
            double lz = nwFrontZ(base.getZ(), rad, NW_SPAWN_AHEAD);
            double ly = nwSafeY(world, lx, lz, base.getY(), 1);
            Location loc = nwLoc(world, lx, ly, lz, (float) yaw, 0f);

            long now = nwNow();
            Ref ref = new Ref();
            ref.owner = uuid;
            ref.stage = 1;
            ref.ent = null;
            ref.name = NW_S1_NAME;
            ref.maxHp = NW_S1_HP;
            ref.bar = null;
            ref.arrow = null;
            ref.flyFireUntil = 0L;
            ref.cdArrow = nwFirstCd(now, NW_S1_ARROW_CD);
            ref.cdSky = nwFirstCd(now, NW_S1_SKY_CD);
            ref.cdGrab = now;
            ref.cdFly = now;
            ref.cdNight = now;
            ref.readyAt = now + NW_FIRST_MS;
            ref.skyGen = 0L;
            ref.dx = null;
            ref.dy = null;
            ref.dz = null;
            ref.emergeUntil = 0L;
            ref.hpFixUntil = now + NW_HP_FIX_MS;
            ref.hpOk = false;
            ref.transitioning = false;
            ref.done = false;
            ref.lw = world;
            ref.lx = lx;
            ref.ly = ly;
            ref.lz = lz;

            LivingEntity ent = nwSpawnMob(world, loc, new String[]{"CAVE_SPIDER"}, NW_S1_NAME,
                    NW_S1_HP, NW_S1_DMG, "boss1", true, uuid);
            if (ent == null) {
                return false;
            }
            ref.ent = ent;

            ref.bar = nwBarMake(nwBarTitle(1));
            try {
                nwBarSync(ref);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            NW_ACTIVE.put(uuid, ref);

            try {
                nwSound(world, loc, "entity.spider.ambient", 1.0f, 0.8f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, loc, "entity.wither.spawn", 0.5f, 1.5f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwSound(world, loc, "entity.warden.nearby_closer", 0.9f, 1.4f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                Particle pt = nwP(new String[]{"SCULK_SOUL", "SOUL_FIRE_FLAME", "SMOKE"});
                nwFx(world, pt, lx, ly + 0.5, lz, 40, 0.6, 0.5, 0.6, 0.15);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            GS.runLater(() -> nwTick(ref), NW_TICK);
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯：召唤出错 " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    private static void nwConsume(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
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
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器注册（★ 原脚本是**第一次右键时才注册**的，这里照抄这个时序）
    // ==================================================================

    private static Plugin nwPlugin() {
        // ★ 原脚本的回退链（RSC → RSC.INSTANCE → Slimefun）在原生版整体作废
        //   （见 GS.java 的说明）：这里直接用本插件实例。
        try {
            return GS.plugin();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 死亡掉落拦截（HIGHEST）。 */
    boolean nwRegisterDeath() {
        if (NW_REG_DEATH) {
            return true;
        }
        try {
            Plugin plugin = nwPlugin();
            if (plugin == null) {
                return false;
            }
            Bukkit.getPluginManager().registerEvents(new DeathListener(), plugin);
            NW_REG_DEATH = true;
            return true;
        } catch (Throwable e1) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯：死亡掉落拦截注册失败 " + e1);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    /**
     * 弹道 / 反冲击波 / 索敌限制（共 7 条）。
     *
     * <p>★ 原脚本逐条 {@code registerEvent} 并数成功条数（{@code ok}），
     * 全部成功时是 <b>7</b>（索敌那两个事件类不同 ⇒ 算两条）。这里照抄这个计数。
     */
    boolean nwRegisterSpell() {
        if (NW_REG_SPELL) {
            return true;
        }
        int ok = 0;
        try {
            Plugin plugin = nwPlugin();
            if (plugin == null) {
                return false;
            }
            // ① 弹道命中（箭伤 0，命中玩家给三种 debuff）
            try {
                Bukkit.getPluginManager().registerEvents(new HitListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ② 取消原版冲击波（SONIC_BOOM 伤害）
            try {
                Bukkit.getPluginManager().registerEvents(new SonicListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ③④ 索敌限制（两个事件类，各自一条）
            try {
                Bukkit.getPluginManager().registerEvents(new TargetLivingListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                Bukkit.getPluginManager().registerEvents(new TargetListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ⑤ 箭矢伤害归零
            try {
                Bukkit.getPluginManager().registerEvents(new DmgByEntityListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ⑥ 弹体不炸地形
            try {
                Bukkit.getPluginManager().registerEvents(new ExplodeListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ⑦ 弹体不改方块
            try {
                Bukkit.getPluginManager().registerEvents(new ChangeBlockListener(), plugin);
                ok++;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            NW_REG_SPELL = (ok > 0);
            try {
                Bukkit.getLogger().info("[构思技艺] 守夜人的提灯：弹道 / 反冲击波 / 索敌限制 已启用（"
                        + ok + " 条）");
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return NW_REG_SPELL;
        } catch (Throwable e1) {
            // 与原脚本一致：静默（原脚本的外层 catch 是 `catch (e) { return false; }`，
            // 不打任何日志 —— 这里多打一条 warning 就是多一条可观察输出，不能加）
            return false;
        }
    }

    /**
     * 方块交互拦截。
     *
     * <p>★ 原脚本的 {@code nwRegisterGuard} 只做一件事：「手持本物品右键方块 ⇒ 取消」
     * ⇒ 直接用现成的 {@link InteractGuard}（HIGHEST 拦截器，与 4 个脚本共用）。
     * 去抖/去重语义由 {@code InteractGuard} 的静态注册表保证（取消两次 = 取消一次）。
     */
    boolean nwRegisterGuard() {
        if (NW_REG_GUARD) {
            return true;
        }
        try {
            boolean ok = InteractGuard.register(NW_ITEM_ID);
            if (ok) {
                NW_REG_GUARD = true;
            }
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    // ---------------- 各监听器（一个事件类一个，便于逐条数 ok） ----------------

    /** 死亡：清掉落 + 记下死亡位置（掉落点 = 各阶段的死亡位置）。 */
    private static final class DeathListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent e) {
            try {
                LivingEntity ent = e.getEntity();
                if (ent == null) {
                    return;
                }
                // 只处理「我们这条线」的实体
                NightwatchModule m = NW_HOLDER;
                if (m == null || !m.nwOurs(ent)) {
                    return;
                }
                String tag = m.nwTag(ent);
                Location loc = null;
                try {
                    loc = ent.getLocation();
                } catch (Throwable err) {
                    loc = null;
                }
                // ★ 清三处：装备 / 背包 / 掉落表（1.21.11 没有 EMPTY 战利品表）
                try {
                    EntityEquipment eq = ent.getEquipment();
                    if (eq != null) {
                        eq.clear();
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    if (ent instanceof InventoryHolder ih) {
                        Inventory inv = ih.getInventory();
                        if (inv != null) {
                            inv.clear();
                        }
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    e.getDrops().clear();
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    // 清完装备再清一遍（装备被清时会补掉落）
                    e.getDrops().clear();
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    e.setDroppedExp(0);
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                // ★ 记下**精确**的死亡位置（二阶段要在这里爬出来）
                if ("boss1".equals(tag) || "boss2".equals(tag)) {
                    Ref r0 = m.nwRefOf(ent);
                    if (r0 != null && loc != null) {
                        r0.dx = loc.getX();
                        r0.dy = loc.getY();
                        r0.dz = loc.getZ();
                    }
                }
                // 守夜蝇死了就从这一波里剔掉
                if ("fly".equals(tag)) {
                    Ref r1 = m.nwRefOf(ent);
                    if (r1 != null) {
                        r1.flies.remove(String.valueOf(ent.getUniqueId()));
                    }
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 弹道命中：命中玩家给三种 debuff，然后箭自己消失。 */
    private static final class HitListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onHit(ProjectileHitEvent e) {
            try {
                Projectile proj = e.getEntity();
                if (proj == null) {
                    return;
                }
                NightwatchModule m = NW_HOLDER;
                if (m == null) {
                    return;
                }
                String tag = m.nwProjTag(proj);
                if (tag.isEmpty() || !"arrow".equals(tag)) {
                    return;
                }
                Entity he = e.getHitEntity();
                if (he != null) {
                    boolean isPlayer = false;
                    try {
                        isPlayer = "PLAYER".equals(String.valueOf(he.getType().name()));
                    } catch (Throwable err) {
                        isPlayer = false;
                    }
                    if (isPlayer && he instanceof Player pl) {
                        nwApplyDebuff(pl);
                    }
                }
                Ref ref = m.nwRefOf(proj);
                try {
                    if (ref != null && ref.arrow == proj) {
                        ref.arrow = null;
                    }
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
                try {
                    proj.remove();
                } catch (Throwable err) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 反制 ①：取消原版冲击波（SONIC_BOOM 伤害）。 */
    private static final class SonicListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageEvent e) {
            try {
                if (e == null) {
                    return;
                }
                String cause = "";
                try {
                    cause = String.valueOf(e.getCause().name());
                } catch (Throwable err) {
                    cause = "";
                }
                if (cause.indexOf("SONIC_BOOM") < 0) {
                    return;
                }
                DamageSource ds = null;
                try {
                    ds = e.getDamageSource();
                } catch (Throwable err) {
                    ds = null;
                }
                if (ds == null) {
                    return;
                }
                Entity de = null;
                Entity ce = null;
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
                NightwatchModule m = NW_HOLDER;
                if (m == null) {
                    return;
                }
                boolean ours = false;
                if (de != null && "boss2".equals(m.nwTag(de))) {
                    ours = true;
                }
                if (!ours && ce != null && "boss2".equals(m.nwTag(ce))) {
                    ours = true;
                }
                if (ours) {
                    e.setCancelled(true);
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 反制 ②：索敌事件里「目标不是最近的玩家 ⇒ 取消」（LivingEntity 版）。 */
    private static final class TargetLivingListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onTarget(EntityTargetLivingEntityEvent e) {
            nwTargetGuard(e);
        }
    }

    /** 反制 ②：索敌事件里「目标不是最近的玩家 ⇒ 取消」（Entity 版）。 */
    private static final class TargetListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onTarget(EntityTargetEvent e) {
            nwTargetGuard(e);
        }
    }

    /** 两个索敌事件共用的判定。 */
    private static void nwTargetGuard(EntityTargetEvent e) {
        try {
            if (e == null) {
                return;
            }
            Entity ent = e.getEntity();
            if (ent == null) {
                return;
            }
            NightwatchModule m = NW_HOLDER;
            if (m == null || !"boss2".equals(m.nwTag(ent))) {
                return;
            }
            Entity tgt = e.getTarget();
            if (tgt == null) {
                return;
            }
            Ref ref = m.nwRefOf(ent);
            if (ref == null || ref.done) {
                return;
            }
            Player near = m.nwNearestPlayer(ref);
            if (near == null) {
                e.setCancelled(true);
                return;
            }
            boolean same = false;
            try {
                same = String.valueOf(tgt.getUniqueId()).equals(String.valueOf(near.getUniqueId()));
            } catch (Throwable err) {
                same = false;
            }
            if (!same) {
                e.setCancelled(true);
                e.setTarget(null);
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** 反制 ③：箭矢伤害归零。 */
    private static final class DmgByEntityListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageByEntityEvent e) {
            try {
                Entity dam = e.getDamager();
                if (dam == null) {
                    return;
                }
                NightwatchModule m = NW_HOLDER;
                if (m == null) {
                    return;
                }
                if (!"arrow".equals(m.nwProjTag(dam))) {
                    return;
                }
                e.setDamage(0.0);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 反制 ④：弹体不炸地形。 */
    private static final class ExplodeListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onExplode(EntityExplodeEvent e) {
            try {
                NightwatchModule m = NW_HOLDER;
                if (m == null || !m.nwOurs(e.getEntity())) {
                    return;
                }
                e.setCancelled(true);
                e.blockList().clear();
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** 反制 ⑤：弹体不改方块。 */
    private static final class ChangeBlockListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onChangeBlock(EntityChangeBlockEvent e) {
            try {
                NightwatchModule m = NW_HOLDER;
                if (m == null || !m.nwOurs(e.getEntity())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    // ==================================================================
    // 残留清扫
    // ==================================================================

    /**
     * 启动 3 秒后清掉「上一份脚本留下的、已经没人管的」实体。
     *
     * <p>★ 正在被管理的线（召唤者还在 {@code NW_ACTIVE} 里）<b>不动</b>。
     */
    void nwSweepLeftovers() {
        try {
            int n = 0;
            for (World w : Bukkit.getWorlds()) {
                List<Entity> arr = new ArrayList<>();
                try {
                    for (Entity ent : w.getEntities()) {
                        arr.add(ent);
                    }
                } catch (Throwable e) {
                    continue;
                }
                for (Entity ent : arr) {
                    try {
                        if (ent == null) {
                            continue;
                        }
                        if (!nwOurs(ent)) {
                            continue;
                        }
                        // ★★ 正在被管理的线**别动**
                        String own = nwOwnerOf(ent);
                        if (own != null && !own.isEmpty() && NW_ACTIVE.get(own) != null) {
                            continue;
                        }
                        ent.remove();
                        n++;
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
            if (n > 0) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 守夜人的提灯：清掉上一份脚本残留实体 " + n + " 个");
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            // ★ 三条监听器都在**第一次右键时**注册（与原脚本同一时序）
            try {
                nwRegisterDeath();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwRegisterSpell();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                nwRegisterGuard();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            Player p = event.getPlayer();
            if (p == null) {
                return null;
            }
            if (!Guards.isMainHandUse(event, p, NW_ITEM_ID)) {
                return null;
            }

            String uuid = String.valueOf(p.getUniqueId());
            long now = nwNow();
            Long last = NW_LAST.get(uuid);
            if (last != null && (now - last) < 500L) {
                return null;
            }
            NW_LAST.put(uuid, now);

            if (!nwSummon(p)) {
                return null;
            }
            if (NW_CONSUME) {
                nwConsume(p);
            }
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 守夜人的提灯 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }

    // ==================================================================
    // 这条线的状态（= 原脚本里那个 ref 对象字面量）
    // ==================================================================

    static final class Ref {
        String owner;
        int stage;
        LivingEntity ent;
        String name;
        double maxHp;
        BossBar bar;
        final Map<String, Player> barNear = new LinkedHashMap<>();
        Entity arrow;
        /** JS {@code ref.flies}：uuid → 守夜蝇。 */
        Map<String, LivingEntity> flies = new LinkedHashMap<>();
        long flyFireUntil;
        long cdArrow;
        long cdSky;
        long cdGrab;
        long cdFly;
        long cdNight;
        long readyAt;
        long skyGen;
        Double dx;
        Double dy;
        Double dz;
        long emergeUntil;
        long hpFixUntil;
        boolean hpOk;
        boolean transitioning;
        boolean done;
        World lw;
        Double lx;
        Double ly;
        Double lz;
    }

    /**
     * 监听器要回调实例方法（{@code nwOurs} / {@code nwTag} …），而 Bukkit 只会 new 一个
     * 无参监听器 ⇒ 用「最近构造的实例」做持有者。
     *
     * <p>★ 语义安全：模块是按脚本名缓存的<b>单例</b>（{@code Modules.acquire} 的
     * {@code computeIfAbsent}），所以永远只有一个实例；这里的持有者与它恒等。
     */
    private static volatile NightwatchModule NW_HOLDER;

    {
        NW_HOLDER = this;
    }
}
