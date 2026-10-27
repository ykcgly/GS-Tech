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
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * {@code gs_boss_rider.js} 的 Java 化版本 —— 「枯骸骑将」。
 *
 * <p>右键 = 召唤一只 boss（<b>消耗品</b>：召唤成功才扣 1 个）。
 * 一只 <b>骷髅</b>「枯骸将军」（下界合金全套 + 主手金剑）骑在一匹 <b>骷髅马</b>「璇骊」上，
 * 骷髅 / 马各挂一条 <b>BossBar</b>（红 / 白，SEGMENTED_10），血 800 / 450，
 * 基础普攻 20 点，每个玩家同时只能有 1 只。
 *
 * <p><b>三个技能</b>（全程无提示词，冷却各自独立）：
 * <ul>
 *   <li><b>斩击</b>：{@link #BR_QI_DMG} 点 / 冷却 {@link #BR_QI_CD_MS} / 射程 {@link #BR_QI_RANGE}。
 *       先「举剑蓄势」{@link #BR_QI_WINDUP} 刻（收拢的 SWEEP_ATTACK 粒子），
 *       再把剑气从 boss 身上按 {@link #BR_QI_STEP} 格/刻<b>一格一格射出去</b>；
 *       <b>只有波前那一层（厚 {@link #BR_QI_BAND} 格）扫到的目标才吃伤害</b> ⇒ 玩家能躲。
 *       用 {@code ref.slashSeq} 当世代号，新的一刀让旧的波自动作废。</li>
 *   <li><b>冲刺</b>：{@link #BR_DASH_DMG} 点 / 冷却 {@link #BR_DASH_CD_MS} / 冲 {@link #BR_DASH_DIST} 格
 *       （{@link #BR_DASH_TICKS} 刻动画，不是瞬移）/ 目标超过 {@link #BR_DASH_RANGE} 格不冲。
 *       <b>无视防具</b>（{@link #BR_DASH_PIERCE}）：命中前把受害者登记进 {@code BR_PIERCE}，
 *       再在 HIGHEST 的伤害事件里把 ARMOR / MAGIC / RESISTANCE / BLOCKING / HARD_HAT 全部归零。
 *       ★ <b>撞墙不再穿墙</b>：1 格高当台阶踩上去继续冲，≥2 格直接停在墙前结束冲刺。</li>
 *   <li><b>伺骨</b>：冷却 {@link #BR_SV_CD_MS}，召 {@link #BR_SV_COUNT} 只金套骷髅「伺骨」，
 *       各骑一匹骷髅马「残驹」；<b>共生</b>（一个死另一个立刻同死）；
 *       只活 {@link #BR_SV_LIFE} 刻（30 秒）到点化烟。★ 残驹<b>自己不索敌</b>，
 *       位移 100% 由脚本每刻 teleport 决定。</li>
 * </ul>
 *
 * <p><b>狂暴</b>：战马比 boss 先死 ⇒ {@code ref.enraged = true}，解锁<b>凋零之首</b>
 * （黑色未蓄力头，{@link #BR_SKULL_DMG} 点 + 凋零 II {@link #BR_SKULL_WITHER_T} 刻，
 * 冷却 {@link #BR_SKULL_CD_MS}，追踪制导，命中伤害由我们自己改写成固定值）。
 * <b>boss 死 ⇒ 战马一起死</b>；<b>boss 死必掉「五骊矟」</b>（{@link #BR_DROP_ID}）。
 *
 * <p><b>阵营</b>（{@link #BR_ALLY}）：枯骸将军 / 璇骊 / 伺骨 / 残驹 都是自己人 ——
 * 三层保险：① 斩击 / 冲刺的命中列表里跳过自己人；② {@code EntityDamageByEntityEvent}
 * 里「自己人打自己人」直接免伤；③ {@code EntityTargetEvent} 里禁止自己人把自己人当目标。
 *
 * <p><b>出场 BGM</b>：boss {@link #BR_MUSIC_RANGE} 格内循环播放 {@link #BR_MUSIC_SOUND}
 * （Pigstep，{@link #BR_MUSIC_LOOP_MS} 毫秒重放一次）；走远立刻停、走近从头播、归天全停。
 *
 * <p>★★★ <b>5 个真实 HIGHEST 监听器</b>（原脚本一共写了 <b>10 处</b> {@code Priority.HIGHEST}，
 * 另 5 处是 {@code JavaAdapter} 退路 —— 见死代码清单）：
 * <table border="1">
 *   <caption>HIGHEST 逐处核对</caption>
 *   <tr><th>#</th><th>事件类</th><th>取消条件</th><th>本类实现位置</th></tr>
 *   <tr><td>1</td><td>{@code PlayerInteractEvent}</td>
 *       <td>{@code setCancelled(true)}：action 含 RIGHT_CLICK <b>且</b>手不是 OFF
 *           <b>且</b>{@code getItem() != null} <b>且</b>是本物品</td>
 *       <td>{@link InteractGuard}（共享类，只做这一件事）</td></tr>
 *   <tr><td>2</td><td>{@code EntityExplodeEvent}</td>
 *       <td><b>不取消</b>：只 {@code blockList().clear()} + {@code setYield(0)}
 *           （取消会把对生物的伤害一起掐掉）</td>
 *       <td>{@link ExplodeListener#onExplode} → {@link #brOnExplode}</td></tr>
 *   <tr><td>3</td><td>{@code EntityDeathEvent}</td>
 *       <td>不取消：{@code getDrops().clear()} ×2 + {@code setDroppedExp(0)} + 清装备 / 马鞍栏</td>
 *       <td>{@link DeathListener#onDeath} → {@link #brOnEntityDeath}</td></tr>
 *   <tr><td>4</td><td>{@code EntityDamageByEntityEvent}</td>
 *       <td>不取消（除非自己人误伤）：改写伤害为 20 点、归零减免修正器、
 *           创造/旁观免伤</td>
 *       <td>{@link DeathListener#onSkullDamage} → {@link #brOnSkullDamage}</td></tr>
 *   <tr><td>5</td><td>{@code EntityTargetEvent}</td>
 *       <td>{@code setCancelled(true)} + {@code setTarget(null)}：残驹一律不许索敌 /
 *           伺骨不许盯创造·旁观 / 自己人不许把自己人当目标</td>
 *       <td>{@link AllyListener#onAllyTarget} → {@link #brOnAllyTarget}</td></tr>
 * </table>
 *
 * <p>★★ <b>加载日志：4 条独立的「已启用」行</b>。原脚本是<b>四个独立的注册函数</b>
 * （{@code brRegisterGuard} / {@code brRegisterDeath} / {@code brRegisterExplode} /
 * {@code brRegisterAlly}），各自成功时各打一条 ⇒ 参考服日志里是 <b>4 行</b>，
 * 不是 1 行。本移植保留 4 个独立注册点、4 个独立成功标志、4 条独立日志行
 * （{@link #guardLine} / {@link #deathLine} / {@link #explodeLine} / {@link #allyLine}）。
 * 本脚本<b>没有</b> {@code gs_cursed} 那种 {@code %s} 拼接瑕疵 —— 五行全是纯字面量
 * （探针里有反向断言钉住这一点）。
 *
 * <p>★ <b>不照抄的死代码</b>（逐条给证据；均已在 1.21.11 的 paper-api 上用 {@code javap} 核对）：
 * <ol>
 *   <li><b>5 处 {@code JavaAdapter} 退路</b>（原脚本第 2221/2481/2515/2531/2570 行）：
 *       GraalJS 25.1.3 里 {@code JavaAdapter} <b>不存在</b>（{@code tools/GraalProbe.java} 实测）；
 *       而且正路（把 JS 函数当 {@code EventExecutor} 传给
 *       {@code PluginManager#registerEvent}）在参考服上<b>实测全部成功</b>
 *       —— 参考服日志里 4 条「已启用」都打出来了。⇒ 那 5 处恒不执行。</li>
 *   <li><b>{@code LootTables.EMPTY} 两处</b>（{@code brSpawnHorse} / {@code brSpawnBoss} /
 *       {@code brSvSpawnSkeleton} 各一处）：1.21.11 的 {@code org.bukkit.loot.LootTables}
 *       枚举里<b>没有 {@code EMPTY}</b>（javap 实测），GraalJS 取到 {@code undefined}，
 *       而 {@code undefined != null} 为假 ⇒ {@code setLootTable} <b>永不执行</b>；
 *       何况 {@code Entity} 上<b>根本没有 {@code setLootTable}</b>（javap 实测）—— 双重死。
 *       ⇒ 掉落物只靠 {@link #brOnEntityDeath} 兜底，与原脚本实际行为一致。</li>
 *   <li><b>{@code Particle.SMOKE_LARGE}</b>（{@code brSvPuff} / {@code brTick} 的狂暴特效）：
 *       1.21.11 已改名 {@code LARGE_SMOKE}（javap 实测 {@code SMOKE_LARGE} 不存在）
 *       ⇒ {@code P.SMOKE_LARGE} 抛、{@code P.valueOf('SMOKE_LARGE')} 抛
 *       ⇒ 落到第三档：{@code brSvPuff} 用 {@code CLOUD}、狂暴特效用 {@code SMOKE}。
 *       本移植<b>直接写第三档</b>（结果逐字一致，且不再白抛两次异常）。</li>
 *   <li><b>{@code brSaddle} 里的 {@code h.setSaddle(s)}</b>：1.21.11 的
 *       {@code AbstractHorse} <b>没有 {@code setSaddle}</b>（javap 实测）⇒ 恒抛 ⇒ 恒不生效。
 *       真正生效的是 {@code inv.setSaddle(s)}（{@code HorseInventory} 有这个方法）与
 *       {@code inv.setItem(0, s)}。</li>
 *   <li><b>{@code brSpawnHorse} 里的 {@code h.setTame(true)}</b>：
 *       {@code Tameable#setTamed(boolean)} 已成功 ⇒ 第二档恒不执行。</li>
 *   <li><b>{@code brPassableAt} 的第三档 {@code !mt.isSolid()}</b>：
 *       {@code Block#isPassable()} 在 1.21.11 上存在且不会抛（javap 实测）⇒ 恒不执行。</li>
 *   <li><b>{@code brMat} / {@code brSetBase} / {@code brBarMake} / {@code brFireSkull} /
 *       {@code brMusicSound} 的「旧版本 API 名字兜底」</b>（{@code GENERIC_MAX_HEALTH} /
 *       {@code GOLD_SWORD} / {@code ET.valueOf(...)} / {@code PET.valueOf(...)} /
 *       {@code sk.setCharged === 'function'} 之类）：1.21.11 上第一档全部命中
 *       ⇒ 这些兜底恒不执行。本移植直接写第一档，并在原位置留注释。</li>
 *   <li><b>{@code brOnPierce} 里 {@code DM == null} 的分支</b>：
 *       {@code EntityDamageEvent$DamageModifier} 一定存在 ⇒ 恒不执行。</li>
 *   <li><b>若干「恒真的 null 判定」</b>：{@code ref.music == null}（ref 字面量里已给 {@code {}}）、
 *       {@code sv.life == null} / {@code sv.tickN == null}（字面量里已给 0）、
 *       {@code ref.lx == null}（召唤时就写了坐标）、{@code ref.svAt == null} /
 *       {@code ref.skullAt == null}（字面量里已给数值）、
 *       {@code BR_PDT == null}（顶层 {@code Java.type} 不会失败）、
 *       {@code blk == null}（{@code World#getBlockAt} 不返回 null）、
 *       {@code gm.length === 0}（{@code getGameMode().name()} 不可能空串）。
 *       Java 侧用基本类型 / 直接调用替代，行为一致。</li>
 * </ol>
 *
 * <p>★ <b>与原脚本的两处「结构差异」</b>（行为不变，见交付说明）：
 * <ol>
 *   <li>{@code brAnnounce} 的降级正则（{@code msg.replace(/&(...)/g, '§$1')}）不照抄：
 *       RSC 的 {@code sendMessage(p, msg)} 就是 {@code Chat.t(msg)}，它<b>不会抛</b>
 *       ⇒ 降级分支恒不执行；且那段正则比 {@code Chat.t} 少处理 {@code &x} 十六进制，
 *       照抄反而会引入一个与原脚本不同的第二套翻译规则。</li>
 *   <li>{@code brRegisterDeath} 在原脚本里同时注册 {@code EntityDeathEvent} 与
 *       {@code EntityDamageByEntityEvent}（后者失败被吞掉、不影响 {@code BR_DEATH_OK}）。
 *       Java 里两者合在同一个 {@link DeathListener} 上 —— 一次 {@code registerEvents}
 *       要么全成、要么全败，比原脚本「半注册」更稳，且不会出现「死亡行打出来了、
 *       伤害改写没装上」的哑状态。</li>
 * </ol>
 *
 * <p>★ <b>注册闩锁是「实例级」而不是静态的</b>：原脚本的 {@code BR_DEATH_OK} /
 * {@code BR_EXPLODE_OK} / {@code BR_ALLY_OK} 是<b>一次 eval 一份</b>的脚本全局，
 * 重载后旧监听器仍挂在 Bukkit 上、旧全局却是空的 —— 本类用实例字段一一对应
 * （模块实例 = 一次 eval），行为与真 JS 相同。只有<b>无状态</b>的方块交互拦截走
 * {@link InteractGuard} 的静态去重（取消两次 = 取消一次）。
 */
public final class RiderModule extends AbstractModule {

    // ================================================================
    // 参数 —— 名字与 JS 顶层 var 逐字同名（常量对账探针按名反射比对）
    // ================================================================

    static final String BR_ITEM_ID = "GS_BONE_RIDER";

    /** ★ 实体名必须用 §（{@code &} 不会被翻译）⇒ 这里就是 §f 开头。 */
    static final String BR_BOSS_NAME = "\u00a7f枯骸将军";
    static final String BR_HORSE_NAME = "\u00a7f璇骊";

    /** 出场提示词（同世界所有在线玩家都会收到，{@code &} 颜色码交给 {@link Chat#t}）。 */
    static final String BR_MSG_BOSS = "&8【&f枯骸将军&8】&f枯骸未朽，骑将仍征";
    static final String BR_MSG_HORSE = "&8【&f璇骊&8】&f随将而行，一往无前";

    static final boolean BR_BAR = true;
    /** ★ true = <b>只有召唤它的那个玩家</b>看得到血条。 */
    static final boolean BR_BAR_ONLY_OWNER = true;
    static final double BR_BAR_RANGE = 64.0;

    static final double BR_BOSS_HP = 800.0;
    static final double BR_HORSE_HP = 450.0;
    static final double BR_HORSE_SPEED = 0.25;
    static final double BR_BASE_DMG = 20.0;
    /** 金剑自带攻击力（属性 base 要写成 {@code BR_BASE_DMG - BR_WEAPON_BONUS}）。 */
    static final double BR_WEAPON_BONUS = 4.0;

    // ---------------- 阵营 ----------------
    static final boolean BR_ALLY = true;
    /**
     * ★ 原脚本是<b>对象字面量</b> {@code {'bone_rider':1, ...}}（键 → 1）。
     * Java 里用等价的字符串数组（判定语义 = {@code BR_ALLY_TAGS[tag] === 1} ⇒ 成员判定）。
     * <br>⚠ 因为这个字段在 JS 侧是对象、在 Java 侧是数组，<b>没法按值自动对账</b>：
     * 若要把本模块加进 {@code tools/ConstantParityProbe.java} 的 {@code MAP}，
     * 请把 {@code BR_ALLY_TAGS} 加进它的 {@code SKIP}；本模块自己的探针按<b>键集合</b>比对。
     */
    static final String[] BR_ALLY_TAGS = {
            "bone_rider", "bone_rider_horse", "bone_rider_servant", "bone_rider_servant_horse"
    };

    // ---------------- 出场 BGM ----------------
    static final boolean BR_MUSIC = true;
    static final String BR_MUSIC_SOUND = "music_disc.pigstep";
    static final String BR_MUSIC_FALLBACK = "music_disc.ward";
    static final double BR_MUSIC_RANGE = 64.0;
    static final double BR_MUSIC_VOL = 4.0;
    /** ★ 循环间隔 = 曲目长度 2:28 = 148 秒。 */
    static final long BR_MUSIC_LOOP_MS = 148000L;

    // ---------------- 斩击 ----------------
    static final double BR_QI_DMG = 7.0;
    static final long BR_QI_CD_MS = 3500L;
    static final double BR_QI_RANGE = 10.0;
    static final double BR_QI_HALF_W = 2.5;
    static final double BR_QI_HALF_H = 2.0;
    static final double BR_QI_KB = 0.3;
    static final double BR_QI_KB_UP = 0.1;
    static final int BR_QI_WINDUP = 6;
    static final double BR_QI_STEP = 0.35;
    /** 波前的有效厚度（格）：只有波前扫过时才判定命中。 */
    static final double BR_QI_BAND = 1.2;

    // ---------------- 冲刺 ----------------
    static final double BR_DASH_DMG = 8.0;
    static final boolean BR_DASH_PIERCE = true;
    static final long BR_DASH_CD_MS = 30000L;
    static final double BR_DASH_DIST = 7.0;
    static final int BR_DASH_TICKS = 20;
    static final double BR_DASH_HALF_W = 2.5;
    static final double BR_DASH_RANGE = 20.0;
    static final int BR_DASH_STEP_UP = 1;
    /**
     * ★ <b>只写在文档里、代码里从不读</b>：撞墙判定实际靠 {@link #brDashStepY} 返回
     * {@code null}（{@code BR_DASH_STEP_UP} 抬不过去就是墙）。原脚本从头到尾没有读过它
     * （已全文件检索确认）⇒ Java 侧同样只保留常量、不参与任何判定。
     */
    static final int BR_DASH_STOP_H = 2;

    // ---------------- 狂暴：凋零之首 ----------------
    static final double BR_SKULL_DMG = 20.0;
    static final int BR_SKULL_WITHER_T = 100;
    static final int BR_SKULL_WITHER_AMP = 1;
    static final long BR_SKULL_CD_MS = 4000L;
    static final double BR_SKULL_SPEED = 1.1;
    static final double BR_SKULL_RANGE = 24.0;
    static final boolean BR_SKULL_HOME = true;
    static final int BR_SKULL_HOME_TICK = 2;
    static final double BR_SKULL_TURN = 0.32;
    static final double BR_SKULL_HOME_RANGE = 32.0;
    static final int BR_SKULL_LIFE = 100;

    // ---------------- 伺骨 ----------------
    static final long BR_SV_CD_MS = 30000L;
    static final long BR_SV_FIRST = 8000L;
    static final int BR_SV_COUNT = 3;
    static final int BR_SV_MAX_ALIVE = 3;
    static final double BR_SV_HP = 50.0;
    static final double BR_SV_HORSE_HP = 50.0;
    static final double BR_SV_DMG = 5.0;
    static final int BR_SV_LIFE = 600;
    /** ★ 旧参数，留着备用 —— 原脚本从未读取（位移实际用 {@link #BR_SV_STEP}）。 */
    static final double BR_SV_SPEED = 0.28;
    static final double BR_SV_STEP = 0.22;
    static final double BR_SV_STOP_R = 1.2;
    static final double BR_SV_SEEK = 32.0;
    static final boolean BR_SV_HORSE_NO_TARGET = true;
    static final boolean BR_SV_IGNORE_CREATIVE = true;
    static final boolean BR_SV_IGNORE_SPECTATOR = true;
    static final String BR_SV_NAME = "\u00a7f伺骨";
    static final String BR_SV_HORSE_NAME = "\u00a7f残驹";

    static final int BR_TICK = 5;
    static final double BR_SEEK_RANGE = 24.0;
    static final long BR_FIRST_DASH = 5000L;
    static final long BR_FIRST_QI = 5000L;
    static final double BR_SPAWN_AHEAD = 3.0;
    static final boolean BR_CONSUME = true;
    static final String BR_DROP_ID = "GS_WULI_SPEAR";

    /** 冲刺「无视防具」登记的<b>有效期</b>（毫秒），超时就当普通伤害处理。 */
    static final long BR_PIERCE_MS = 800L;

    // ---------------- 固定串（与 JS 里逐字相同，不许"顺手优化"） ----------------
    /** 死亡时马鞍栏 / 装备栏也要清掉的那两个 tag。 */
    private static final String TAG_HORSE = "bone_rider_horse";
    private static final String TAG_SERVANT_HORSE = "bone_rider_servant_horse";

    /** 减免类修正器：归零这些 ⇒ 盔甲/保护/抗性/举盾/头盔一点用都没有。 */
    private static final EntityDamageEvent.DamageModifier[] PIERCE_MODS = {
            EntityDamageEvent.DamageModifier.ARMOR,
            EntityDamageEvent.DamageModifier.MAGIC,
            EntityDamageEvent.DamageModifier.RESISTANCE,
            EntityDamageEvent.DamageModifier.BLOCKING,
            EntityDamageEvent.DamageModifier.HARD_HAT
    };

    /** 挑 BGM 音效时的候选表（原脚本字面量）。 */
    private static final String[] MUSIC_CANDS = {
            BR_MUSIC_SOUND, BR_MUSIC_FALLBACK, "music_disc.relic", "music_disc.11"
    };

    /** JS 的 {@code String.prototype.replace('music_disc.', '')} = 只替<b>第一处</b>字面量。 */
    private static final Pattern MUSIC_PREFIX = Pattern.compile("music_disc\\.");

    // ---------------- PDC 键 ----------------
    /**
     * ★ 原脚本是 {@code NamespacedKey.fromString('gs:boss')} / {@code 'gs:boss_skull'}
     * —— 与 {@code new NamespacedKey(plugin, "boss")} 得到的键<b>不是同一个</b>
     * （前者命名空间是 {@code minecraft} 之外的 {@code gs}，后者是插件名）。
     * 为了与真 JS 落在<b>同一个 PDC 键</b>上，这里逐字走 {@code fromString}。
     */
    private static final NamespacedKey BR_BOSS_KEY = NamespacedKey.fromString("gs:boss");
    private static final NamespacedKey BR_SKULL_KEY = NamespacedKey.fromString("gs:boss_skull");

    // ================================================================
    // 运行期状态 —— 非静态（实例 = 原脚本的一次顶层求值）
    // ================================================================

    /** boss uuid → ref。 */
    private final Map<String, Ref> bosses = new ConcurrentHashMap<>();
    /** 玩家 uuid → boss uuid（每个玩家同时 1 只）。 */
    private final Map<String, String> owner = new ConcurrentHashMap<>();
    /** 玩家 uuid → 上次召唤时间戳（防同一次点击被派发两遍）。 */
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    /** 战马 uuid → boss uuid。 */
    private final Map<String, String> h2b = new ConcurrentHashMap<>();
    /** 伺骨 uuid → sv。 */
    private final Map<String, Sv> servants = new ConcurrentHashMap<>();
    /** 残驹 uuid → 伺骨 uuid。 */
    private final Map<String, String> svH2S = new ConcurrentHashMap<>();
    /** 伺骨 uuid → 残驹 uuid。 */
    private final Map<String, String> svS2H = new ConcurrentHashMap<>();
    /** 受害者 uuid → 冲刺的期望伤害（登记一次、用掉即删）。 */
    private final Map<String, Pierce> pierce = new ConcurrentHashMap<>();

    /** 挑中的 BGM 音效（原脚本 {@code BR_MUSIC_PICKED}，挑一次缓存住）。 */
    private String musicPicked;

    // 注册闩锁（等价原脚本的 BR_GUARD_OK / BR_DEATH_OK / BR_EXPLODE_OK / BR_ALLY_OK）
    private boolean deathOk;
    private boolean explodeOk;
    private boolean allyOk;

    // ================================================================
    // 构造器 = 原脚本的顶层代码
    // ================================================================

    public RiderModule() {
        // ① 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info(loadLine());
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }

        // ② 方块交互拦截（顶层就注册；原脚本同样在顶层调 brRegisterGuard）
        try {
            if (registerGuard()) {
                try {
                    Bukkit.getLogger().info(guardLine());
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ③ 死亡掉落 / 不破坏地形 / 阵营 —— 三个独立注册点、三条独立日志
        try {
            if (registerDeath()) {
                try {
                    Bukkit.getLogger().info(deathLine());
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (registerExplode()) {
                try {
                    Bukkit.getLogger().info(explodeLine());
                } catch (Throwable ignored) {
                    // 静默
                }
            } else {
                try {
                    Bukkit.getLogger().warning(explodeFailLine());
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (registerAlly()) {
                try {
                    Bukkit.getLogger().info(allyLine());
                } catch (Throwable ignored) {
                    // 静默
                }
            } else {
                try {
                    Bukkit.getLogger().warning(allyFailLine());
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    @Override
    public String name() {
        return "gs_boss_rider";
    }

    // ================================================================
    // 日志行 —— 抽成方法只为了让探针能逐字节对账（不改变任何行为）
    // ================================================================

    static String loadLine() {
        return "[构思技艺] gs_boss_rider 已加载";
    }

    static String guardLine() {
        return "[构思技艺] 枯骸骑将：方块交互拦截 已启用";
    }

    static String deathLine() {
        return "[构思技艺] 枯骸骑将：死亡掉落处理 已启用";
    }

    static String explodeLine() {
        return "[构思技艺] 枯骸骑将：凋零之首 不破坏地形 已启用";
    }

    static String allyLine() {
        return "[构思技艺] 枯骸骑将：阵营（不打自己人）已启用";
    }

    static String explodeFailLine() {
        return "[构思技艺] 枯骸骑将：爆炸拦截注册失败，凋零之首会炸坏地形！";
    }

    static String allyFailLine() {
        return "[构思技艺] 枯骸骑将：阵营拦截注册失败，伺骨可能回头打 boss！";
    }

    // ================================================================
    // 纯函数区 —— 抽出来是为了让探针能在大量输入上与真 JS 逐值比对
    // ================================================================

    /**
     * 斩击波前的命中判定（原脚本 {@code brSlashWave} 里那段几何）。
     *
     * <p>★ 逐字保留原式的<b>写法与顺序</b>：{@code along} 用 {@code hx/hz}（水平单位向量）、
     * {@code lat} 用 {@code hz/-hx}（垂直的"横扫"轴）；三条 early-out 的顺序也不动。
     */
    static boolean slashHit(double rx, double ry, double rz, double hx, double hz, double d) {
        double along = rx * hx + rz * hz;
        double lat = rx * hz - rz * hx;
        if (Math.abs(lat) > BR_QI_HALF_W) {
            return false;
        }
        if (Math.abs(ry) > BR_QI_HALF_H) {
            return false;
        }
        if (along > d || along < d - BR_QI_BAND) {
            return false;
        }
        return true;
    }

    /** 冲刺路径的命中判定（原脚本 {@code brDashTick} 里那段几何，容差是写死的 2.0 / 3.0）。 */
    static boolean dashHit(double rx, double ry, double rz, double dirX, double dirZ) {
        double along = rx * dirX + rz * dirZ;
        double lat = rx * dirZ - rz * dirX;
        if (Math.abs(lat) > BR_DASH_HALF_W) {
            return false;
        }
        if (along > 2.0 || along < -2.0) {
            return false;
        }
        if (Math.abs(ry) > 3.0) {
            return false;
        }
        return true;
    }

    /** 举剑蓄势的粒子圈半径：{@code 1.30 - 0.85 * (i / steps)}，圈越收越紧 = 蓄力感。 */
    static double windupRadius(int i) {
        int steps = BR_QI_WINDUP;
        if (steps < 1) {
            steps = 1;
        }
        // ★ JS 的 i / steps 是**浮点**除法 ⇒ Java 必须 / (double)
        return 1.30 - 0.85 * (i / (double) steps);
    }

    /** 第 i 刻的波前距离：{@code (i + 1) * BR_QI_STEP}。 */
    static double waveDistance(int i) {
        return (i + 1) * BR_QI_STEP;
    }

    /** 冲刺进度：{@code (i + 1) / BR_DASH_TICKS}（★ 必须浮点除）。 */
    static double dashProgress(int i) {
        return (i + 1) / (double) BR_DASH_TICKS;
    }

    /** 伺骨的出生偏移角：{@code (yaw0 + (k - (COUNT-1)/2.0) * 45.0) * PI / 180}。 */
    static double servantAngle(double yaw0, int k) {
        return (yaw0 + (k - (BR_SV_COUNT - 1) / 2.0) * 45.0) * Math.PI / 180.0;
    }

    /**
     * 伺骨的出生点 + 朝向。
     *
     * @return {@code {px, pz, yaw}}，逐字对应原脚本的
     *         {@code px = base.x + (-sin(ang)) * 3.0} / {@code pz = base.z + cos(ang) * 3.0}
     *         / {@code yaw = (ang * 180/PI + 180.0) % 360.0}
     */
    static double[] servantPlacement(double baseX, double baseZ, double yaw0, int k) {
        double ang = servantAngle(yaw0, k);
        double px = baseX + (-Math.sin(ang)) * 3.0;
        double pz = baseZ + (Math.cos(ang)) * 3.0;
        double yaw = (ang * 180.0 / Math.PI + 180.0) % 360.0;
        return new double[]{px, pz, yaw};
    }

    /**
     * 残驹每刻该挪到哪：方向 = 伺骨的目标。
     *
     * @return {@code {nx, nz, yaw}}；不在「可追区间」({@code n > STOP_R && n <= SEEK}) 时返回 {@code null}
     */
    static double[] servantStep(double hlX, double hlZ, double tlX, double tlZ) {
        double dx = tlX - hlX;
        double dz = tlZ - hlZ;
        double n = Math.sqrt(dx * dx + dz * dz);
        if (!(n > BR_SV_STOP_R && n <= BR_SV_SEEK)) {
            return null;
        }
        dx /= n;
        dz /= n;
        double step = BR_SV_STEP;
        if (step > n - BR_SV_STOP_R) {
            step = n - BR_SV_STOP_R;
        }
        double nx = hlX + dx * step;
        double nz = hlZ + dz * step;
        double yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
        return new double[]{nx, nz, yaw};
    }

    /**
     * 凋零之首的转向：{@code 新方向 = 归一化(旧方向 × (1-TURN) + 指向目标 × TURN)}。
     *
     * @param ox 旧方向（已归一化的速度方向）
     * @param wx 指向目标的向量（<b>未归一化</b>，函数内自己归一化）
     * @return 归一化后的新方向；任何一步失败（长度 ≈ 0）返回 {@code null}
     */
    static double[] skullSteer(double ox, double oy, double oz,
                               double wx, double wy, double wz) {
        double wn = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (!(wn > 1.0e-4)) {
            return null;
        }
        wx /= wn;
        wy /= wn;
        wz /= wn;

        double k = BR_SKULL_TURN;
        if (k < 0) {
            k = 0;
        }
        if (k > 1) {
            k = 1;
        }
        double nx = ox * (1 - k) + wx * k;
        double ny = oy * (1 - k) + wy * k;
        double nz = oz * (1 - k) + wz * k;
        double nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(nn > 1.0e-4)) {
            return null;
        }
        return new double[]{nx / nn, ny / nn, nz / nn};
    }

    /** 血条进度：{@code hp / (mx > 0 ? mx : 1)} 再钳到 [0, 1]。 */
    static double barProgress(double hp, double mx) {
        double prog = hp / (mx > 0 ? mx : 1);
        if (prog < 0) {
            prog = 0;
        }
        if (prog > 1) {
            prog = 1;
        }
        return prog;
    }

    /**
     * boss 出生点：玩家身前 {@link #BR_SPAWN_AHEAD} 格。
     *
     * @return {@code {x, z}}
     */
    static double[] spawnAhead(double baseX, double baseZ, double yaw) {
        double rad = yaw * Math.PI / 180.0;
        return new double[]{
                baseX + (-Math.sin(rad)) * BR_SPAWN_AHEAD,
                baseZ + (Math.cos(rad)) * BR_SPAWN_AHEAD
        };
    }

    /** 冲刺的起手朝向：{@code atan2(-dx, dz) * 180 / PI}（MC：前向量 = (-sin yaw, cos yaw)）。 */
    static double yawFromDir(double dx, double dz) {
        return Math.atan2(-dx, dz) * 180.0 / Math.PI;
    }

    /** 由朝向反推前向量（原脚本在「目标与自己重合」时用这一档）。 */
    static double[] dirFromYaw(double yaw) {
        return new double[]{
                -Math.sin(yaw * Math.PI / 180.0),
                Math.cos(yaw * Math.PI / 180.0)
        };
    }

    /**
     * 音效名 → {@code Sound} 枚举常量名：{@code 'music_disc.pigstep' → 'MUSIC_DISC_PIGSTEP'}。
     *
     * <p>★ 原脚本是 {@code ('' + name).replace('music_disc.', '').toUpperCase()} ——
     * JS 的字符串 replace 只替<b>第一处</b>，所以这里用 {@link #MUSIC_PREFIX} +
     * {@code replaceFirst} 而不是 {@code String#replace}（后者会替全部，语义不同）。
     */
    static String musicEnumName(String sound) {
        return "MUSIC_DISC_" + MUSIC_PREFIX.matcher(sound).replaceFirst("")
                .toUpperCase(Locale.ROOT);
    }

    /** 地形探针 —— 让 {@link #dashStepY} 能被探针用假世界在大量输入上扫描。 */
    interface BlockProbe {
        boolean passable(int bx, int by, int bz);
    }

    /**
     * 走到 (x, z) 这一步时脚该落在哪个高度？
     * <ol>
     *   <li>脚下 + 头顶都空 ⇒ 平着走（返回 y）</li>
     *   <li>抬高 {@link #BR_DASH_STEP_UP} 格后能站 ⇒ 当台阶踩上去（返回 by + up）</li>
     *   <li>都不行（= 障碍 ≥ {@link #BR_DASH_STOP_H} 格）⇒ 返回 {@code null} 表示撞墙</li>
     * </ol>
     * ★ 返回 {@code Double}（可空）：{@code null} 就是原脚本的 {@code null}。
     */
    static Double dashStepY(BlockProbe world, double x, double y, double z) {
        try {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int by = (int) Math.floor(y);
            // ① 平着走
            if (world.passable(bx, by, bz) && world.passable(bx, by + 1, bz)) {
                return y;
            }
            // ② 上台阶（最多 BR_DASH_STEP_UP 格）
            for (int up = 1; up <= BR_DASH_STEP_UP; up++) {
                boolean ok = true;
                for (int c = 0; c <= 1; c++) {           // 脚 + 头 两格都要空
                    if (!world.passable(bx, by + up + c, bz)) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    return (double) (by + up);
                }
            }
            // ③ 撞墙
            return null;
        } catch (Throwable e) {
            return y;
        }
    }

    // ================================================================
    // PDC / 小工具
    // ================================================================

    /** 读实体上的 {@code gs:boss} 标记（值：{@code bone_rider} / {@code bone_rider_horse} / …）。 */
    private static String brTag(Entity ent) {
        try {
            if (ent == null) {
                return "";
            }
            String v = ent.getPersistentDataContainer().get(BR_BOSS_KEY, PersistentDataType.STRING);
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 材质名兼容：逐个试（1.21.11 上第一档就命中，后面的档位是原脚本的旧版本兜底）。 */
    private static Material brMat(String[] cands) {
        try {
            for (String c : cands) {
                try {
                    Material m = Material.valueOf(c);
                    if (m != null) {
                        return m;
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

    /** 造一个「永不掉耐久」的装备（{@code setUnbreakable(true)} ⇒ 不掉耐久、死亡也不掉）。 */
    private static ItemStack brGear(String[] cands) {
        Material m = brMat(cands);
        if (m == null) {
            return null;
        }
        try {
            ItemStack s = new ItemStack(m, 1);
            try {
                org.bukkit.inventory.meta.ItemMeta meta = s.getItemMeta();
                if (meta != null) {
                    try {
                        meta.setUnbreakable(true);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    s.setItemMeta(meta);
                }
            } catch (Throwable ignored) {
                // 静默
            }
            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 设属性 base 值。
     *
     * <p>★ 原脚本传的是<b>名字数组</b>（{@code ['MAX_HEALTH', 'GENERIC_MAX_HEALTH']}），
     * 逐个 {@code Attribute[...]} / {@code Attribute.valueOf(...)} 试。
     * 1.21.11 上第一档（{@code MAX_HEALTH} / {@code MOVEMENT_SPEED} / {@code ATTACK_DAMAGE}）
     * 全部存在（javap 实测）⇒ 后面的 {@code GENERIC_*} 旧名兜底恒不执行，这里直接用第一档。
     */
    private static boolean brSetBase(LivingEntity ent, Attribute attr, double v) {
        try {
            AttributeInstance inst = ent.getAttribute(attr);
            if (inst == null) {
                return false;
            }
            inst.setBaseValue(v);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static boolean brAlive(Entity ent) {
        try {
            if (ent == null) {
                return false;
            }
            return !(ent.isDead() || !ent.isValid());
        } catch (Throwable e) {
            return false;
        }
    }

    private static double brDist(Entity a, Entity b) {
        try {
            return a.getLocation().distance(b.getLocation());
        } catch (Throwable e) {
            return 999.0;
        }
    }

    /**
     * 出场提示词：发给<b>同世界所有在线玩家</b>。
     *
     * <p>★ 原脚本的降级分支（自己把 {@code &} 翻成 {@code §} 再发）不照抄：
     * RSC 的 {@code sendMessage(p, msg)} 就是 {@link Chat#t}，它不会抛 ⇒ 降级恒不执行
     * （见类注释）。
     */
    private static void brAnnounce(World world, String msg) {
        try {
            String wname = "";
            try {
                wname = String.valueOf(world.getName());
            } catch (Throwable e) {
                wname = "";
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!wname.isEmpty() && !String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                try {
                    p.sendMessage(Chat.t(msg));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 阵营判定
    // ================================================================

    /** 带 {@code gs:boss} 标记且值在 {@link #BR_ALLY_TAGS} 里 ⇒ 自己人。 */
    private static boolean brIsAlly(Entity ent) {
        try {
            if (!BR_ALLY) {
                return false;
            }
            if (ent == null) {
                return false;
            }
            PersistentDataContainer pd = ent.getPersistentDataContainer();
            if (pd == null) {
                return false;
            }
            if (!pd.has(BR_BOSS_KEY, PersistentDataType.STRING)) {
                return false;
            }
            String v = String.valueOf(pd.get(BR_BOSS_KEY, PersistentDataType.STRING));
            for (String tag : BR_ALLY_TAGS) {
                if (tag.equals(v)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 这是不是「残驹」（伺骨骑的那匹骷髅马）？索引查得到就快，否则看 PDC 标记。 */
    private boolean brIsServantHorse(Entity ent) {
        try {
            if (ent == null) {
                return false;
            }
            try {
                String uid = String.valueOf(ent.getUniqueId());
                if (svH2S.get(uid) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            PersistentDataContainer pd = ent.getPersistentDataContainer();
            if (pd == null) {
                return false;
            }
            if (!pd.has(BR_BOSS_KEY, PersistentDataType.STRING)) {
                return false;
            }
            return TAG_SERVANT_HORSE.equals(String.valueOf(pd.get(BR_BOSS_KEY, PersistentDataType.STRING)));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 这是不是「伺骨」本体？（伺骨不许盯创造 / 旁观 ⇒ 索敌与伤害都要认得出它） */
    private boolean brIsServant(Entity ent) {
        try {
            if (ent == null) {
                return false;
            }
            try {
                String uid = String.valueOf(ent.getUniqueId());
                if (servants.get(uid) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            PersistentDataContainer pd = ent.getPersistentDataContainer();
            if (pd == null) {
                return false;
            }
            if (!pd.has(BR_BOSS_KEY, PersistentDataType.STRING)) {
                return false;
            }
            return "bone_rider_servant".equals(
                    String.valueOf(pd.get(BR_BOSS_KEY, PersistentDataType.STRING)));
        } catch (Throwable e) {
            return false;
        }
    }

    /** 伺骨 / 残驹 打到 <b>创造·旁观</b> 的玩家 ⇒ 直接免伤（索敌已跳过，这是最后一道保险）。 */
    private void brOnServantDamage(EntityDamageByEntityEvent e) {
        try {
            if (e == null) {
                return;
            }
            Entity victim;
            Entity src;
            try {
                victim = e.getEntity();
            } catch (Throwable err) {
                return;
            }
            try {
                src = e.getDamager();
            } catch (Throwable err) {
                src = null;
            }
            if (victim == null || src == null) {
                return;
            }
            if (!brIsServant(src) && !brIsServantHorse(src)) {
                return;
            }
            if (!brSvBadGM(victim)) {
                return;                       // 只拦「创造 / 旁观」
            }
            try {
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                e.setDamage(0.0);
            } catch (Throwable ignored) {
                // 静默
            }
            if (src instanceof Mob m) {
                try {
                    m.setTarget(null);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 兜底：伤害来源是「自己人」（含它射出的弹体 / 带 {@code gs:boss_skull} 的头）⇒ 判定为友军。 */
    private static boolean brSrcIsAlly(Entity src) {
        try {
            if (!BR_ALLY || src == null) {
                return false;
            }
            if (brIsAlly(src)) {
                return true;
            }
            // 弹体：看射手（★ Entity 上没有 getShooter ⇒ 非弹体在 JS 里也是抛了被吞掉）
            if (src instanceof Projectile pr) {
                try {
                    org.bukkit.projectiles.ProjectileSource sh = pr.getShooter();
                    if (sh instanceof Entity se && brIsAlly(se)) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // 带 boss_skull 标记的凋零之首
            try {
                PersistentDataContainer pd = src.getPersistentDataContainer();
                if (pd != null && pd.has(BR_SKULL_KEY, PersistentDataType.STRING)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * 禁止「自己人把自己人当目标」（AI 层面的保险）。
     *
     * <p>★ 顺序照抄原脚本：残驹永不索敌 → 伺骨不许盯创造/旁观 → 自己人不许互盯。
     */
    private void brOnAllyTarget(EntityTargetEvent e) {
        try {
            if (e == null) {
                return;
            }
            Entity ent;
            Entity tgt;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                ent = null;
            }
            try {
                tgt = e.getTarget();
            } catch (Throwable err) {
                tgt = null;
            }

            // ★ 残驹**永不索敌**：不管盯上谁，一律掐掉 —— 它只听伺骨的
            if (BR_SV_HORSE_NO_TARGET && ent != null && brIsServantHorse(ent)) {
                try {
                    e.setCancelled(true);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    e.setTarget(null);
                } catch (Throwable ignored) {
                    // 静默
                }
                return;
            }

            // ★ 伺骨也不许盯**创造 / 旁观**
            if (ent != null && tgt != null && brIsServant(ent) && brSvBadGM(tgt)) {
                try {
                    e.setCancelled(true);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    e.setTarget(null);
                } catch (Throwable ignored) {
                    // 静默
                }
                return;
            }

            if (!BR_ALLY || ent == null || tgt == null) {
                return;
            }
            if (!brIsAlly(ent)) {
                return;
            }
            if (!brIsAlly(tgt)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                e.setTarget(null);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 禁止「自己人打自己人」（伤害层面的保险：近战 / 弓箭 / 爆炸全算）。 */
    private static void brOnAllyDamage(EntityDamageByEntityEvent e) {
        try {
            if (!BR_ALLY || e == null) {
                return;
            }
            Entity victim;
            Entity src;
            try {
                victim = e.getEntity();
            } catch (Throwable err) {
                victim = null;
            }
            try {
                src = e.getDamager();
            } catch (Throwable err) {
                src = null;
            }
            if (victim == null) {
                return;
            }
            if (!brIsAlly(victim)) {
                return;
            }
            if (src == null) {
                return;
            }
            if (!brSrcIsAlly(src)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                e.setDamage(0.0);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 判定「手里拿的是不是本物品」/ 消耗
    // ================================================================

    private static boolean brIsOurItem(ItemStack stack) {
        return Guards.isOurItem(stack, BR_ITEM_ID);
    }

    /** ★ 只认主手（Slimefun 对副手也会派发 onUse ⇒ 副手会「不消耗却生效」）。 */
    private static boolean brIsMainHandUse(PlayerRightClickEvent event, Player p) {
        return Guards.isMainHandUse(event, p, BR_ITEM_ID);
    }

    private static void brConsume(Player p) {
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

    // ================================================================
    // 马鞍
    // ================================================================

    /**
     * 给马装鞍。
     *
     * <p>★ 原脚本「四条写入路径全试一遍」，1.21.11 上实际生效的是
     * {@code HorseInventory#setSaddle} 与 {@code setItem(0, s)}（javap 实测
     * {@code AbstractHorse} <b>没有</b> {@code setSaddle} ⇒ 那条路恒抛、恒不生效，见类注释）。
     * <br>★ {@code wrote} 的语义照抄：只要有一条写成功就算「写过」，
     * 但只有<b>回读</b>确认鞍真的在，才返回 true。
     */
    private static boolean brSaddle(LivingEntity h) {
        try {
            if (h == null) {
                return false;
            }
            ItemStack s = brGear(new String[]{"SADDLE"});
            if (s == null) {
                return false;
            }

            org.bukkit.inventory.Inventory inv = null;
            if (h instanceof AbstractHorse ah) {
                try {
                    inv = ah.getInventory();
                } catch (Throwable e) {
                    inv = null;
                }
            }

            boolean wrote = false;
            if (inv != null) {
                try {
                    if (inv instanceof org.bukkit.inventory.HorseInventory hi) {
                        hi.setSaddle(s);
                        wrote = true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    inv.setItem(0, s);
                    wrote = true;
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    ItemStack cur = inv.getItem(0);
                    if (cur != null && String.valueOf(cur.getType().name()).contains("SADDLE")) {
                        wrote = true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // ★ 原脚本这里还有 `h.setSaddle(s)` —— AbstractHorse 上没有这个方法，恒抛，不照抄

            // 回读验证：真的装上了吗？
            try {
                if (inv != null) {
                    ItemStack chk = null;
                    if (inv instanceof org.bukkit.inventory.HorseInventory hi2) {
                        try {
                            chk = hi2.getSaddle();
                        } catch (Throwable e) {
                            chk = null;
                        }
                    }
                    if (chk == null) {
                        try {
                            chk = inv.getItem(0);
                        } catch (Throwable e) {
                            chk = null;
                        }
                    }
                    if (chk != null && String.valueOf(chk.getType().name()).contains("SADDLE")) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            return wrote;
        } catch (Throwable e) {
            return false;
        }
    }

    // ================================================================
    // 生成：战马（通用）/ boss / 伺骨
    // ================================================================

    /**
     * 生成一匹骷髅马。
     *
     * @param hp   {@code null} ⇒ {@link #BR_HORSE_HP}
     * @param name {@code null} ⇒ {@link #BR_HORSE_NAME}
     * @param tag  {@code null} ⇒ {@code bone_rider_horse}
     */
    private static LivingEntity brSpawnHorse(World world, Location loc,
                                             Double hp, String name, String tag) {
        try {
            double realHp = (hp == null ? BR_HORSE_HP : hp);
            String realName = (name == null ? BR_HORSE_NAME : name);
            String realTag = (tag == null ? TAG_HORSE : tag);

            Entity spawned = world.spawnEntity(loc, EntityType.SKELETON_HORSE);
            if (!(spawned instanceof LivingEntity h)) {
                return null;
            }

            try {
                // ★ setTamed 在 Tameable 上（AbstractHorse 继承它）；原脚本对非 Tameable 会抛、被吞
                if (h instanceof org.bukkit.entity.Tameable tm) {
                    tm.setTamed(true);
                }
            } catch (Throwable ignored) {
                // 原脚本这里还退到 setTame(true) —— 1.21.11 上第一档就成功，不照抄
            }

            // ★ 马鞍（见 brSaddle）：四条路全试 + 回读验证
            try {
                brSaddle(h);
            } catch (Throwable ignored) {
                // 静默
            }

            brSetBase(h, Attribute.MAX_HEALTH, realHp);
            try {
                h.setHealth(realHp);
            } catch (Throwable ignored) {
                // 静默
            }

            // 移速：只升不降
            try {
                AttributeInstance inst = h.getAttribute(Attribute.MOVEMENT_SPEED);
                if (inst != null) {
                    double cur = 0.0;
                    try {
                        cur = inst.getBaseValue();
                    } catch (Throwable e) {
                        cur = 0.0;
                    }
                    if (cur < BR_HORSE_SPEED) {
                        inst.setBaseValue(BR_HORSE_SPEED);
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                h.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                h.setPersistent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                h.setCustomName(realName);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                h.setCustomNameVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 原脚本这里想用 LootTables.EMPTY 清空战利品表 —— 1.21.11 上
            //   LootTables 没有 EMPTY、Entity 也没有 setLootTable ⇒ 恒不执行（见类注释）

            try {
                EntityEquipment eq = h.getEquipment();
                if (eq != null) {
                    try {
                        eq.clear();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                h.getPersistentDataContainer().set(BR_BOSS_KEY, PersistentDataType.STRING, realTag);
            } catch (Throwable ignored) {
                // 静默
            }

            return h;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 生成 boss（骷髅）：下界合金全套 + 主手金剑，全部永不掉耐久 + 死亡一件都不掉。 */
    private static LivingEntity brSpawnBoss(World world, Location loc) {
        try {
            Entity spawned = world.spawnEntity(loc, EntityType.SKELETON);
            if (!(spawned instanceof LivingEntity s)) {
                return null;
            }

            try {
                EntityEquipment eq = s.getEquipment();
                if (eq != null) {
                    ItemStack helmet = brGear(new String[]{"NETHERITE_HELMET"});
                    ItemStack chest = brGear(new String[]{"NETHERITE_CHESTPLATE"});
                    ItemStack legs = brGear(new String[]{"NETHERITE_LEGGINGS"});
                    ItemStack boots = brGear(new String[]{"NETHERITE_BOOTS"});
                    ItemStack sword = brGear(new String[]{"GOLDEN_SWORD"});

                    if (helmet != null) {
                        eq.setHelmet(helmet);
                    }
                    if (chest != null) {
                        eq.setChestplate(chest);
                    }
                    if (legs != null) {
                        eq.setLeggings(legs);
                    }
                    if (boots != null) {
                        eq.setBoots(boots);
                    }
                    eq.setItemInMainHand(sword);       // ★ 换掉弓（没弓 = 不再射箭）
                    eq.setItemInOffHand(null);

                    // 死亡一件都不掉（下界合金别白送）
                    try {
                        eq.setHelmetDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setChestplateDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setLeggingsDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setBootsDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setItemInMainHandDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setItemInOffHandDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            brSetBase(s, Attribute.MAX_HEALTH, BR_BOSS_HP);
            try {
                s.setHealth(BR_BOSS_HP);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 基础伤害 20 点：扣掉金剑自带的 +4，属性 base 写 16 ⇒ 最终才正好 20
            brSetBase(s, Attribute.ATTACK_DAMAGE, BR_BASE_DMG - BR_WEAPON_BONUS);

            // 白天别自燃（骷髅会烧起来）
            try {
                PotionEffectType pet = PotionEffectType.FIRE_RESISTANCE;
                try {
                    s.addPotionEffect(new PotionEffect(pet, 999999, 0, true, false));
                } catch (Throwable e1) {
                    try {
                        s.addPotionEffect(new PotionEffect(pet, 999999, 0));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                s.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setPersistent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setCustomName(BR_BOSS_NAME);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setCustomNameVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ LootTables.EMPTY 那段是死代码（见类注释）
            try {
                s.getPersistentDataContainer().set(BR_BOSS_KEY, PersistentDataType.STRING, "bone_rider");
            } catch (Throwable ignored) {
                // 静默
            }

            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 生成一只「伺骨」（金套 + 金剑，伤害 base 写 5-4=1）。 */
    private static LivingEntity brSvSpawnSkeleton(World world, Location loc) {
        try {
            Entity spawned = world.spawnEntity(loc, EntityType.SKELETON);
            if (!(spawned instanceof LivingEntity s)) {
                return null;
            }

            try {
                EntityEquipment eq = s.getEquipment();
                if (eq != null) {
                    ItemStack h = brGear(new String[]{"GOLDEN_HELMET"});
                    ItemStack c = brGear(new String[]{"GOLDEN_CHESTPLATE"});
                    ItemStack l = brGear(new String[]{"GOLDEN_LEGGINGS"});
                    ItemStack b = brGear(new String[]{"GOLDEN_BOOTS"});
                    ItemStack w = brGear(new String[]{"GOLDEN_SWORD"});
                    if (h != null) {
                        eq.setHelmet(h);
                    }
                    if (c != null) {
                        eq.setChestplate(c);
                    }
                    if (l != null) {
                        eq.setLeggings(l);
                    }
                    if (b != null) {
                        eq.setBoots(b);
                    }
                    eq.setItemInMainHand(w);          // ★ 换掉弓 ⇒ 不会射箭，只会近战
                    eq.setItemInOffHand(null);
                    try {
                        eq.setHelmetDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setChestplateDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setLeggingsDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setBootsDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setItemInMainHandDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        eq.setItemInOffHandDropChance(0.0f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            brSetBase(s, Attribute.MAX_HEALTH, BR_SV_HP);
            try {
                s.setHealth(BR_SV_HP);
            } catch (Throwable ignored) {
                // 静默
            }
            // 伤害 5 点（含金剑的 +4）
            brSetBase(s, Attribute.ATTACK_DAMAGE, BR_SV_DMG - BR_WEAPON_BONUS);

            // 白天别自燃
            try {
                PotionEffectType pet = PotionEffectType.FIRE_RESISTANCE;
                try {
                    s.addPotionEffect(new PotionEffect(pet, 999999, 0, true, false));
                } catch (Throwable e1) {
                    try {
                        s.addPotionEffect(new PotionEffect(pet, 999999, 0));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                s.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setPersistent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setCustomName(BR_SV_NAME);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                s.setCustomNameVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ LootTables.EMPTY 那段是死代码（见类注释）
            try {
                s.getPersistentDataContainer().set(BR_BOSS_KEY, PersistentDataType.STRING,
                        "bone_rider_servant");
            } catch (Throwable ignored) {
                // 静默
            }
            return s;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 把某个实体「干净地弄死」：清装备（⇒ 不掉金套）→ 卸乘客 → 扣血走死亡流程。 */
    private static void brSvKill(LivingEntity ent) {
        try {
            if (ent == null) {
                return;
            }
            if (!brAlive(ent)) {
                return;
            }
            try {
                EntityEquipment eq = ent.getEquipment();
                if (eq != null) {
                    try {
                        eq.clear();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 马鞍（在 HorseInventory 里）也一起清掉 ⇒ 死也不掉鞍
            //   原脚本写的是 ent.getInventory()：对骷髅是 TypeError（被吞）、对马才生效
            //   ⇒ Java 用 instanceof InventoryHolder 精确对应
            if (ent instanceof InventoryHolder ih) {
                try {
                    ih.getInventory().clear();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                ent.eject();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ent.setHealth(0.0);
            } catch (Throwable e) {
                try {
                    ent.remove();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 化烟消失（30 秒到点 / boss 归天时走这条：直接 remove，不走死亡 ⇒ 更不会产生掉落）。 */
    private static void brSvPuff(LivingEntity ent) {
        try {
            if (ent == null) {
                return;
            }
            World world = null;
            Location l = null;
            try {
                l = ent.getLocation();
                world = l.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                EntityEquipment eq = ent.getEquipment();
                if (eq != null) {
                    try {
                        eq.clear();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 马鞍也清掉（化烟不走死亡流程，本来就掉不出来，这里只是干净）
            if (ent instanceof InventoryHolder ih) {
                try {
                    ih.getInventory().clear();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                ent.eject();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ent.remove();
            } catch (Throwable ignored) {
                // 静默
            }
            if (world != null && l != null) {
                try {
                    // ★ 原脚本三档：SMOKE_LARGE（1.21.11 已改名 LARGE_SMOKE，取不到）
                    //   → valueOf('SMOKE_LARGE')（抛）→ CLOUD ✔（见类注释）
                    Particle pt = Particle.CLOUD;
                    for (int i = 0; i < 12; i++) {
                        world.spawnParticle(pt, l.getX(), l.getY() + 0.8 + i * 0.08, l.getZ(),
                                2, 0.3, 0.3, 0.3, 0.03);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 收尾：把 sv 从索引里摘掉，并把<b>另一半</b>（残驹 / 伺骨）一起带走。
     *
     * @param mode {@code "puff"} = 化烟（超时 / boss 归天）；{@code "kill"} = 走死亡流程（共生陪葬）
     */
    private void brSvDispose(Sv sv, String mode) {
        try {
            if (sv == null || sv.done) {
                return;
            }
            sv.done = true;

            LivingEntity sk = sv.sk;
            LivingEntity hs = sv.hs;

            if ("kill".equals(mode)) {
                // 共生：一个死了，**还活着的那个**立刻跟着死
                if (brAlive(sk)) {
                    brSvKill(sk);
                }
                if (brAlive(hs)) {
                    brSvKill(hs);
                }
            } else {
                brSvPuff(sk);
                brSvPuff(hs);
            }

            try {
                if (sk != null) {
                    servants.remove(String.valueOf(sk.getUniqueId()));
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                if (hs != null) {
                    svH2S.remove(String.valueOf(hs.getUniqueId()));
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                if (sk != null) {
                    svS2H.remove(String.valueOf(sk.getUniqueId()));
                }
            } catch (Throwable ignored) {
                // 静默
            }
            sv.sk = null;
            sv.hs = null;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★ 这个目标不该被伺骨盯上？（创造 / 旁观）
     *
     * <p>★ 原脚本对<b>非玩家</b>实体走的是 {@code getGameMode()} 抛异常 ⇒
     * {@code catch → return false}，这里用 {@code instanceof Player} 精确对应。
     * 另：{@code if (gm.length === 0) return false;} 恒不成立（{@code name()} 不会是空串）。
     */
    private static boolean brSvBadGM(Entity ent) {
        try {
            if (ent == null) {
                return true;
            }
            if (!(ent instanceof Player p)) {
                return false;
            }
            String gm;
            try {
                gm = p.getGameMode().name();
            } catch (Throwable e) {
                return false;
            }
            if (BR_SV_IGNORE_CREATIVE && gm.contains("CREATIVE")) {
                return true;
            }
            if (BR_SV_IGNORE_SPECTATOR && gm.contains("SPECTATOR")) {
                return true;
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★ 这个目标还能不能追？（活着 / 同世界 / 不是自己人 / 不是创造·旁观） */
    private static boolean brSvGoodTarget(Entity tgt, String wname) {
        try {
            if (tgt == null) {
                return false;
            }
            if (!brAlive(tgt)) {
                return false;
            }
            try {
                if (!String.valueOf(tgt.getWorld().getName()).equals(wname)) {
                    return false;
                }
            } catch (Throwable e) {
                return false;
            }
            if (BR_ALLY) {
                try {
                    if (brIsAlly(tgt)) {
                        return false;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (brSvBadGM(tgt)) {
                return false;      // ★ 创造 / 旁观 ⇒ 立刻放弃（中途切创造也马上松手）
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★ 以<b>伺骨</b>为圆心挑最近的玩家（不是以残驹为圆心 —— 那才是互相拖拽的根源）。 */
    private static Player brSvPickTarget(LivingEntity sk, String wname) {
        Player best = null;
        double bestD = 1e9;
        try {
            Location sl = sk.getLocation();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                if (brSvBadGM(p)) {
                    continue;                      // ★ 创造 / 旁观一律跳过
                }
                if (BR_ALLY) {
                    try {
                        if (brIsAlly(p)) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
                Location pl;
                try {
                    pl = p.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double d = pl.distance(sl);
                if (d < bestD) {
                    bestD = d;
                    best = p;
                }
            }
            if (best != null && bestD > BR_SV_SEEK) {
                best = null;
            }
        } catch (Throwable e) {
            best = null;
        }
        return best;
    }

    /**
     * 伺骨的心跳：共生检查 + 30 秒寿命 + 搬运残驹。
     *
     * <p>★ 残驹<b>彻底不索敌</b>：光「清目标 + 推速度」压不住马的 AI，
     * 所以每刻直接把残驹 teleport 到脚本算出来的位置 —— 位移 100% 由脚本说了算。
     */
    private void brSvTick(Sv sv) {
        try {
            if (sv == null || sv.done) {
                return;
            }

            LivingEntity sk = sv.sk;
            LivingEntity hs = sv.hs;
            if (!brAlive(sk)) {
                brSvDispose(sv, "kill");            // 伺骨没了 → 残驹陪葬
                return;
            }
            if (!brAlive(hs)) {
                brSvDispose(sv, "kill");            // 残驹没了 → 伺骨陪葬
                return;
            }

            sv.life = sv.life + 1;
            if (sv.life >= BR_SV_LIFE) {
                brSvDispose(sv, "puff");            // ★ 30 秒到点
                return;
            }

            try {
                World world = hs.getWorld();
                String wname = String.valueOf(world.getName());

                // ① 马自己不挑敌人：每刻把它的目标清掉
                if (BR_SV_HORSE_NO_TARGET) {
                    try {
                        if (hs instanceof Mob m) {
                            m.setTarget(null);
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }

                // ② 目标：每 BR_TICK 刻重挑一次并缓存（不用每刻遍历在线玩家，省性能）
                sv.tickN = sv.tickN + 1;
                if (sv.tickN % BR_TICK == 1 || !brSvGoodTarget(sv.tgt, wname)) {
                    Entity t = null;
                    try {
                        if (sk instanceof Mob m) {
                            t = m.getTarget();
                        }
                    } catch (Throwable e) {
                        t = null;
                    }
                    if (!brSvGoodTarget(t, wname)) {
                        t = brSvPickTarget(sk, wname);
                        if (t != null) {
                            try {
                                if (sk instanceof Mob m) {
                                    m.setTarget((LivingEntity) t);
                                }
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    }
                    sv.tgt = t;
                }

                // ③ 每刻搬运残驹（方向 = 伺骨的目标 ⇒ 马和骑手永远同向）
                Entity tgt = sv.tgt;
                if (tgt != null) {
                    Location hl = hs.getLocation();
                    Location tl = tgt.getLocation();
                    double[] step = servantStep(hl.getX(), hl.getZ(), tl.getX(), tl.getZ());
                    if (step != null) {
                        double nx = step[0];
                        double nz = step[1];
                        double yaw = step[2];
                        double ny = hl.getY();

                        // 爬坡 / 撞墙（1 格当台阶，≥2 格就原地不动）
                        Double sy = dashStepY((bx, by, bz) -> brPassableAt(world, bx, by, bz),
                                nx, ny, nz);
                        if (sy == null) {
                            nx = hl.getX();
                            nz = hl.getZ();
                        } else {
                            ny = sy;
                        }

                        try {
                            hs.teleport(new Location(world, nx, ny, nz, (float) yaw, hl.getPitch()));
                        } catch (Throwable ignored) {
                            // 静默
                        }

                        // ④ 兜底：万一 teleport 没把骑手带走（或它掉下来了），补一刀 + 重新扶上马
                        if (brAlive(sk)) {
                            boolean riding = false;
                            try {
                                Entity v = sk.getVehicle();
                                riding = (v != null && String.valueOf(v.getUniqueId())
                                        .equals(String.valueOf(hs.getUniqueId())));
                            } catch (Throwable e) {
                                riding = false;
                            }
                            if (!riding) {
                                try {
                                    sk.teleport(new Location(world, nx, ny + 1.35, nz, (float) yaw, 0.0f));
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                                try {
                                    hs.addPassenger(sk);
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ⑤ ★ 马鞍复查补装（每 20 刻一次 —— 万一被插件/死亡清空了，这里补回来）
            try {
                if (sv.life % 20 == 3) {
                    brSaddle(hs);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            GS.runLater(() -> brSvTick(sv), 1L);
        } catch (Throwable e) {
            try {
                brSvDispose(sv, "puff");
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** 召唤一组伺骨（返回真的召出来几只；满员时返回 0 且<b>不重置 CD</b>）。 */
    private int brSummonServants(Ref ref) {
        try {
            if (ref == null || !brAlive(ref.boss)) {
                return 0;
            }

            // 已满员 → 不召，也**不重置 CD**（下一拍再试）
            int aliveN = 0;
            try {
                for (Sv s0 : ref.servants) {
                    if (s0 != null && !s0.done && brAlive(s0.sk)) {
                        aliveN++;
                    }
                }
            } catch (Throwable e) {
                aliveN = 0;
            }
            if (aliveN >= BR_SV_MAX_ALIVE) {
                return 0;
            }

            World world = ref.boss.getWorld();
            Location base = ref.boss.getLocation();
            double yaw0 = 0;
            try {
                yaw0 = base.getYaw();
            } catch (Throwable e) {
                yaw0 = 0;
            }

            int n = 0;
            for (int k = 0; k < BR_SV_COUNT; k++) {
                double[] place = servantPlacement(base.getX(), base.getZ(), yaw0, k);
                Location loc = new Location(world, place[0], base.getY(), place[1]);
                try {
                    loc.setYaw((float) place[2]);
                    loc.setPitch(0.0f);
                } catch (Throwable ignored) {
                    // 静默
                }

                LivingEntity hs = brSpawnHorse(world, loc, BR_SV_HORSE_HP,
                        BR_SV_HORSE_NAME, TAG_SERVANT_HORSE);
                LivingEntity sk = brSvSpawnSkeleton(world, loc);
                if (sk == null) {
                    if (hs != null) {
                        try {
                            hs.remove();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                    continue;
                }
                if (hs != null) {
                    try {
                        hs.addPassenger(sk);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    // ★ 残驹自己**不索敌**：出生就把目标清掉，往后只听伺骨的
                    try {
                        if (BR_SV_HORSE_NO_TARGET && hs instanceof Mob m) {
                            m.setTarget(null);
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }

                Sv sv = new Sv();
                sv.sk = sk;
                sv.hs = hs;
                sv.bossRef = ref;
                sv.life = 0;
                sv.done = false;
                try {
                    servants.put(String.valueOf(sk.getUniqueId()), sv);
                    if (hs != null) {
                        svH2S.put(String.valueOf(hs.getUniqueId()), String.valueOf(sk.getUniqueId()));
                        svS2H.put(String.valueOf(sk.getUniqueId()), String.valueOf(hs.getUniqueId()));
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    ref.servants.add(sv);
                } catch (Throwable ignored) {
                    // 静默
                }
                n++;

                GS.runLater(() -> brSvTick(sv), BR_TICK);
            }

            if (n > 0) {
                try {
                    world.playSound(base, "entity.skeleton.hurt", 0.9f, 0.7f);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    world.playSound(base, "entity.wither.skeleton.step", 0.7f, 0.8f);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return n;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 枯骸骑将 召唤伺骨出错: " + e);
            } catch (Throwable ignored) {
                // 静默
            }
            return 0;
        }
    }

    // ================================================================
    // 目标选择
    // ================================================================

    /** ① 优先用怪物 AI 自己锁定的目标；② 没有就找最近的玩家（跳过旁观）。 */
    private static Entity brTarget(Ref ref) {
        try {
            LivingEntity b = ref.boss;
            if (!brAlive(b)) {
                return null;
            }

            try {
                if (b instanceof Mob m) {
                    Entity t = m.getTarget();
                    if (t != null && brAlive(t)) {
                        if (brDist(b, t) <= BR_SEEK_RANGE) {
                            return t;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            Player best = null;
            double bestD = 1e9;
            try {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == null) {
                        continue;
                    }
                    try {
                        if (p.getGameMode() != null
                                && p.getGameMode().name().contains("SPECTATOR")) {
                            continue;
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        if (!String.valueOf(p.getWorld().getName())
                                .equals(String.valueOf(b.getWorld().getName()))) {
                            continue;
                        }
                    } catch (Throwable e) {
                        continue;
                    }
                    double d = brDist(b, p);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            return (best != null && bestD <= BR_SEEK_RANGE) ? best : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ================================================================
    // 斩击（举剑蓄势 → 剑气飞行）
    // ================================================================

    private void brStartSlash(Ref ref, Entity tgt) {
        try {
            LivingEntity b = ref.boss;

            Location eye;
            try {
                eye = b.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Location tl;
            try {
                tl = tgt.getLocation();
            } catch (Throwable e) {
                return;
            }

            double dx = tl.getX() - eye.getX();
            double dy = (tl.getY() + 0.9) - eye.getY();
            double dz = tl.getZ() - eye.getZ();
            double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (n < 1.0e-4) {
                return;
            }
            dx /= n;
            dy /= n;
            dz /= n;

            // 水平方向（击退只用它，免得把人怼进地里）
            double hx = dx;
            double hz = dz;
            double hl = Math.sqrt(hx * hx + hz * hz);
            if (hl < 1.0e-4) {
                hx = 0.0;
                hz = 0.0;
            } else {
                hx /= hl;
                hz /= hl;
            }

            // 起手：boss 转头对准目标（只改朝向，不 teleport —— 它在马背上，tp 会掉下来）
            try {
                b.setRotation((float) yawFromDir(hx, hz), 0.0f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            ref.slashSeq = ref.slashSeq + 1;
            int wid = ref.slashSeq;

            Wave wave = new Wave();
            wave.wx = eye.getX();
            wave.wy = eye.getY();
            wave.wz = eye.getZ();
            wave.dx = dx;
            wave.dy = dy;
            wave.dz = dz;
            wave.hx = hx;
            wave.hz = hz;

            brSlashWindup(ref, wave, wid, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ① 举剑蓄势（只出粒子，不伤人）。 */
    private void brSlashWindup(Ref ref, Wave wave, int wid, int i) {
        try {
            if (ref == null || ref.slashSeq != wid) {
                return;
            }
            if (!brAlive(ref.boss)) {
                return;
            }

            LivingEntity b = ref.boss;
            World world = b.getWorld();

            Particle pt = Particle.SWEEP_ATTACK;
            int steps = BR_QI_WINDUP;
            if (steps < 1) {
                steps = 1;
            }
            double r = windupRadius(i);            // 圈越收越紧 = 蓄力感

            for (int k = 0; k < 5; k++) {
                double a = (2.0 * Math.PI * k) / 5.0 + i * 0.35;
                double ox = Math.cos(a) * r;
                double oz = Math.sin(a) * r;
                world.spawnParticle(pt,
                        wave.wx + wave.dx * 1.1 + ox,
                        wave.wy + 0.15 + Math.sin(a) * r * 0.5,
                        wave.wz + wave.dz * 1.1 + oz,
                        1, 0.0, 0.0, 0.0, 0.0);
            }

            if (i == 0) {
                try {
                    world.playSound(b.getLocation(), "entity.ender_dragon.flap", 0.35f, 1.9f);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            if (i + 1 >= steps) {
                brSlashWave(ref, wave, wid, 0);
                return;
            }
            final int next = i + 1;
            GS.runLater(() -> brSlashWindup(ref, wave, wid, next), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ② 剑气飞行：每刻推进 {@link #BR_QI_STEP} 格，只有波前扫到的才判定。 */
    private void brSlashWave(Ref ref, Wave wave, int wid, int i) {
        try {
            if (ref == null || ref.slashSeq != wid) {
                return;
            }
            if (!brAlive(ref.boss)) {
                return;
            }

            LivingEntity b = ref.boss;
            World world = b.getWorld();

            double d = waveDistance(i);
            if (d > BR_QI_RANGE) {
                return;                            // 飞到头了
            }

            // 波前：一道横着的弧
            try {
                Particle pt = Particle.SWEEP_ATTACK;
                double ux = -wave.hz;
                double uz = wave.hx;               // 垂直于飞行方向的「横扫」轴
                for (int s = -4; s <= 4; s++) {
                    double o = (s / 4.0) * BR_QI_HALF_W;
                    world.spawnParticle(pt,
                            wave.wx + wave.dx * d + ux * o,
                            wave.wy + wave.dy * d + 0.2,
                            wave.wz + wave.dz * d + uz * o,
                            1, 0.0, 0.0, 0.0, 0.0);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // 只有「波前这一层」命中 ⇒ 站远一点 / 绕到侧面都能躲开
            try {
                java.util.Collection<Entity> list = world.getNearbyEntities(
                        new Location(world, wave.wx, wave.wy, wave.wz),
                        BR_QI_RANGE + 1.0, BR_QI_HALF_H + 2.0, BR_QI_RANGE + 1.0);
                if (list != null) {
                    for (Entity ent : list) {
                        if (ent == null) {
                            continue;
                        }
                        if (!brAlive(ent)) {
                            continue;
                        }
                        if (ent == b) {
                            continue;
                        }
                        if (ref.horse != null && ent == ref.horse) {
                            continue;
                        }
                        if (brIsAlly(ent)) {
                            continue;              // ★ 自己人（璇骊 / 伺骨 / 残驹）不误伤
                        }

                        Location el;
                        try {
                            el = ent.getLocation();
                        } catch (Throwable e2) {
                            continue;
                        }
                        double rx = el.getX() - wave.wx;
                        double ry = (el.getY() + 0.9) - wave.wy;
                        double rz = el.getZ() - wave.wz;

                        if (!slashHit(rx, ry, rz, wave.hx, wave.hz, d)) {
                            continue;
                        }

                        String eid;
                        try {
                            eid = String.valueOf(ent.getUniqueId());
                        } catch (Throwable e3) {
                            continue;
                        }
                        if (wave.hit.get(eid) != null) {
                            continue;
                        }
                        wave.hit.put(eid, 1);

                        brHurt(b, ent, BR_QI_DMG,
                                wave.hx * BR_QI_KB, BR_QI_KB_UP, wave.hz * BR_QI_KB);
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            final int next = i + 1;
            GS.runLater(() -> brSlashWave(ref, wave, wid, next), 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 造成伤害（可带击退）。★ {@code if (kbx || kby || kbz)} 在 JS 里是「非 0 即真」。 */
    private static void brHurt(Entity src, Entity ent, double dmg,
                               double kbx, double kby, double kbz) {
        try {
            // ★ setNoDamageTicks / damage(...) 都在 LivingEntity 上（Entity 没有）
            //   —— 原脚本对非生物会抛、被吞，这里用 instanceof 精确对应
            if (ent instanceof LivingEntity le) {
                try {
                    le.setNoDamageTicks(0);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    le.damage(dmg, src);
                } catch (Throwable e1) {
                    try {
                        le.damage(dmg);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            if (kbx != 0.0 || kby != 0.0 || kbz != 0.0) {
                try {
                    ent.setVelocity(new Vector(kbx, kby, kbz));
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 冲刺伤害「<b>无视防具</b>」：命中前把受害者登记进 {@code BR_PIERCE}（记着期望伤害），
     * 然后在 HIGHEST 的 {@code EntityDamageByEntityEvent} 里把减免类修正器全部归零。
     *
     * <p>原版的伤害是在事件<b>之后</b>才按修正器结算的 ⇒ 归零了就等于盔甲一点用都没有。
     * （吸收金心属于事件后结算的另外一层，脚本插不进去，会照常先扣 —— 这是原版机制。）
     */
    private void brHurtPierce(Entity src, Entity ent, double dmg,
                              double kbx, double kby, double kbz) {
        try {
            if (BR_DASH_PIERCE) {
                try {
                    Pierce rec = new Pierce();
                    rec.d = dmg;
                    rec.t = Guards.now();
                    pierce.put(String.valueOf(ent.getUniqueId()), rec);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        brHurt(src, ent, dmg, kbx, kby, kbz);
    }

    /** 伤害事件里抹掉防具减伤（挂在 {@link DeathListener#onSkullDamage} 那个事件上）。 */
    private void brOnPierce(EntityDamageByEntityEvent e) {
        try {
            if (!BR_DASH_PIERCE || e == null) {
                return;
            }
            Entity victim;
            try {
                victim = e.getEntity();
            } catch (Throwable err) {
                return;
            }
            if (victim == null) {
                return;
            }

            String uid;
            try {
                uid = String.valueOf(victim.getUniqueId());
            } catch (Throwable err) {
                return;
            }
            Pierce rec = pierce.get(uid);
            if (rec == null) {
                return;
            }
            pierce.remove(uid);                        // ★ 一次性：用完就删
            if ((Guards.now() - rec.t) > BR_PIERCE_MS) {
                return;                                // 过期 ⇒ 当普通伤害
            }

            for (EntityDamageEvent.DamageModifier m : PIERCE_MODS) {
                try {
                    e.setDamage(m, 0.0);               // 减免量归零 ⇒ 不减伤
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                e.setDamage(rec.d);                    // 最终伤害按回我们要的数
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 狂暴：凋零之首
    // ================================================================

    /**
     * 发射凋零之首。
     *
     * <p>★ 用<b>黑色</b>（未蓄力）头：{@code setCharged(false)} ⇒ 命中只炸一下实体，
     * 但**原版爆炸强度是硬编码的**，{@code setYield(0)} 兜不住地形破坏 ⇒
     * 真正的保险是 {@link #brOnExplode} 里清空方块列表。
     */
    private void brFireSkull(Ref ref, Entity tgt) {
        try {
            LivingEntity b = ref.boss;
            World world = b.getWorld();

            Location eye;
            try {
                eye = b.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Location tl;
            try {
                tl = tgt.getLocation();
            } catch (Throwable e) {
                return;
            }

            double dx = tl.getX() - eye.getX();
            double dy = (tl.getY() + 1.0) - eye.getY();
            double dz = tl.getZ() - eye.getZ();
            double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (n < 1.0e-4) {
                return;
            }
            dx /= n;
            dy /= n;
            dz /= n;

            // 从头顶前方一点放出去，免得刚出生就撞到自己脚下的方块
            Location from = new Location(world,
                    eye.getX() + dx * 1.0,
                    eye.getY() + 0.3,
                    eye.getZ() + dz * 1.0);

            Entity spawned = world.spawnEntity(from, EntityType.WITHER_SKULL);
            if (!(spawned instanceof WitherSkull sk)) {
                return;
            }

            try {
                sk.setCharged(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                sk.setShooter(b);
            } catch (Throwable ignored) {
                // 静默
            }
            // 爆炸威力 / 是否点火：能设就设，但**别指望它**（见方法注释）
            try {
                sk.setYield(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                sk.setIsIncendiary(false);
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                Vector v = new Vector(dx * BR_SKULL_SPEED, dy * BR_SKULL_SPEED, dz * BR_SKULL_SPEED);
                try {
                    sk.setDirection(new Vector(dx, dy, dz));
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    sk.setVelocity(v);
                } catch (Throwable ignored) {
                    // 静默
                }
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                sk.getPersistentDataContainer().set(BR_SKULL_KEY, PersistentDataType.STRING, "1");
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                world.playSound(eye, "entity.wither.shoot", 0.9f, 1.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 追踪制导：让它一路咬着目标飞
            if (BR_SKULL_HOME) {
                brSkullGuide(sk, tgt, 0);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 凋零之首的追踪制导：每 {@link #BR_SKULL_HOME_TICK} 刻把飞行方向朝目标偏转一点。
     *
     * <p>★ 目标每拍重新找「离头颅最近的在线玩家」（原目标跑远了会自动换人），
     * 找不到就沿原方向直飞（不再修正）。
     * <br>★ {@link #BR_SKULL_LIFE} 刻后自毁：{@code remove()} 不触发爆炸 ⇒ 不炸地形。
     */
    private void brSkullGuide(Entity sk, Entity tgt, int step) {
        try {
            if (sk == null) {
                return;
            }
            try {
                if (sk.isDead()) {
                    return;
                }
            } catch (Throwable e) {
                return;
            }
            try {
                if (!sk.isValid()) {
                    return;
                }
            } catch (Throwable ignored) {
                // 静默
            }

            if (step >= BR_SKULL_LIFE) {
                try {
                    sk.remove();
                } catch (Throwable ignored) {
                    // 静默
                }
                return;
            }

            Location sl;
            try {
                sl = sk.getLocation();
            } catch (Throwable e) {
                sl = null;
            }
            if (sl == null) {
                return;
            }

            // ① 目标：优先原来的那个（还活着/还在线）；不行就换最近的玩家
            Entity t = null;
            try {
                if (tgt != null && brAlive(tgt)) {
                    // ★ JS 是 `tgt.isOnline == null || tgt.isOnline()`：非玩家没有 isOnline
                    //   ⇒ 直接沿用；玩家则要求在线。★ 调用抛异常时 JS 也退回「沿用」
                    try {
                        if (!(tgt instanceof Player pl) || pl.isOnline()) {
                            t = tgt;
                        }
                    } catch (Throwable e) {
                        t = tgt;
                    }
                }
            } catch (Throwable e) {
                t = null;
            }

            if (t == null) {
                Player best = null;
                double bestD = 1e9;
                try {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        try {
                            if (p.getGameMode().name().contains("SPECTATOR")) {
                                continue;
                            }
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        try {
                            if (!String.valueOf(p.getWorld().getName())
                                    .equals(String.valueOf(sl.getWorld().getName()))) {
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
                        double ax = pl.getX() - sl.getX();
                        double ay = pl.getY() + 1.0 - sl.getY();
                        double az = pl.getZ() - sl.getZ();
                        double d = Math.sqrt(ax * ax + ay * ay + az * az);
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                if (best != null && bestD <= BR_SKULL_HOME_RANGE) {
                    t = best;
                }
            }

            if (t != null) {
                Location tl;
                try {
                    tl = t.getLocation();
                } catch (Throwable e) {
                    tl = null;
                }
                if (tl != null) {
                    double wx = tl.getX() - sl.getX();
                    double wy = (tl.getY() + 1.0) - sl.getY();
                    double wz = tl.getZ() - sl.getZ();

                    // 旧方向（速度）：没速度就退化成「直接指向目标」
                    double ox = wx;
                    double oy = wy;
                    double oz = wz;
                    try {
                        Vector vv = sk.getVelocity();
                        double vn = Math.sqrt(vv.getX() * vv.getX()
                                + vv.getY() * vv.getY() + vv.getZ() * vv.getZ());
                        if (vn > 1.0e-4) {
                            ox = vv.getX() / vn;
                            oy = vv.getY() / vn;
                            oz = vv.getZ() / vn;
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }

                    double[] dir = skullSteer(ox, oy, oz, wx, wy, wz);
                    if (dir != null) {
                        try {
                            if (sk instanceof org.bukkit.entity.Fireball fb) {
                                fb.setDirection(new Vector(dir[0], dir[1], dir[2]));
                            }
                            sk.setVelocity(new Vector(dir[0] * BR_SKULL_SPEED,
                                    dir[1] * BR_SKULL_SPEED, dir[2] * BR_SKULL_SPEED));
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                }
            }

            final Entity next = t;
            GS.runLater(() -> brSkullGuide(sk, next, step + BR_SKULL_HOME_TICK),
                    BR_SKULL_HOME_TICK);
        } catch (Throwable e) {
            try {
                if (sk != null) {
                    sk.remove();
                }
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ================================================================
    // 冲刺
    // ================================================================

    /**
     * 这一格是不是「过得去」的（空气 / 水 / 草 / 火把 ⇒ true；玻璃 / 墙 / 半砖 ⇒ false）。
     *
     * <p>★ 原脚本第三档 {@code !mt.isSolid()} 是死代码：{@code Block#isPassable()}
     * 在 1.21.11 上存在且不抛（javap 实测）⇒ 恒不执行。
     */
    private static boolean brPassableAt(World world, int bx, int by, int bz) {
        try {
            Block blk = world.getBlockAt(bx, by, bz);
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
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                return blk.isPassable();
            } catch (Throwable ignored) {
                // 静默
            }
            if (mt != null) {
                try {
                    return !mt.isSolid();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    private void brStartDash(Ref ref, Entity tgt) {
        try {
            ref.mode = "dash";
            ref.hit.clear();
            ref.dashI = 0;

            Location l;
            try {
                l = ref.horse.getLocation();
            } catch (Throwable e) {
                l = ref.boss.getLocation();
            }
            ref.x0 = l.getX();
            ref.y0 = l.getY();
            ref.z0 = l.getZ();

            Location tl;
            try {
                tl = tgt.getLocation();
            } catch (Throwable e) {
                tl = null;
            }
            double dx = 0;
            double dz = 0;
            if (tl != null) {
                dx = tl.getX() - ref.x0;
                dz = tl.getZ() - ref.z0;
            }
            double n = Math.sqrt(dx * dx + dz * dz);
            if (n < 1.0e-4) {
                double yaw0 = 0;
                try {
                    yaw0 = l.getYaw();
                } catch (Throwable e) {
                    yaw0 = 0;
                }
                double[] dir = dirFromYaw(yaw0);
                dx = dir[0];
                dz = dir[1];
            } else {
                dx /= n;
                dz /= n;
            }

            ref.dirX = dx;
            ref.dirZ = dz;
            // MC：前向量 = (-sin(yaw), cos(yaw)) ⇒ yaw = atan2(-dirX, dirZ)
            ref.yaw = yawFromDir(dx, dz);

            try {
                ref.horse.getWorld().playSound(l, "entity.horse.gallop", 1.6f, 0.8f);
            } catch (Throwable ignored) {
                // 静默
            }
            brDashTick(ref);
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void brDashTick(Ref ref) {
        try {
            if (ref == null || !"dash".equals(ref.mode)) {
                return;
            }
            if (!brAlive(ref.boss)) {
                brCleanup(ref);
                return;
            }
            if (!brAlive(ref.horse)) {
                ref.mode = "idle";                 // 马没了就在地上打
                return;
            }

            World world = ref.horse.getWorld();
            int i = ref.dashI;
            double k = dashProgress(i);
            double x = ref.x0 + ref.dirX * BR_DASH_DIST * k;
            double z = ref.z0 + ref.dirZ * BR_DASH_DIST * k;
            double y = ref.y0;

            // ★ 撞墙规则：1 格 = 上台阶继续冲；≥2 格 = **停在墙前，冲刺结束**
            Double stepY = dashStepY((bx, by, bz) -> brPassableAt(world, bx, by, bz), x, y, z);
            if (stepY == null) {
                ref.mode = "idle";
                try {
                    Location wl = new Location(world, x, y, z);
                    try {
                        world.playSound(wl, "entity.horse.gallop", 0.8f, 0.6f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        world.spawnParticle(Particle.CLOUD, x, y + 1.0, z, 10, 0.4, 0.4, 0.4, 0.03);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                return;
            }
            y = stepY;

            try {
                Location loc = new Location(world, x, y, z);
                try {
                    loc.setYaw((float) ref.yaw);
                    loc.setPitch(0.0f);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.horse.teleport(loc);
                ref.horse.setVelocity(new Vector(0.0, 0.0, 0.0));
            } catch (Throwable ignored) {
                // 静默
            }

            // 路径伤害（宽 5 格，每个目标一次）
            try {
                java.util.Collection<Entity> list = world.getNearbyEntities(new Location(world, x, y, z),
                        BR_DASH_HALF_W + 2.0, 3.0, BR_DASH_HALF_W + 2.0);
                for (Entity ent : list) {
                    if (ent == null) {
                        continue;
                    }
                    if (!brAlive(ent)) {
                        continue;
                    }
                    if (ent == ref.boss || ent == ref.horse) {
                        continue;
                    }
                    if (brIsAlly(ent)) {
                        continue;                  // ★ 冲刺也不撞自己人
                    }

                    String eid;
                    try {
                        eid = String.valueOf(ent.getUniqueId());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (ref.hit.get(eid) != null) {
                        continue;
                    }

                    Location el;
                    try {
                        el = ent.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    double rx = el.getX() - x;
                    double rz = el.getZ() - z;
                    double ry = (el.getY() + 0.9) - y;

                    if (!dashHit(rx, ry, rz, ref.dirX, ref.dirZ)) {
                        continue;
                    }

                    ref.hit.put(eid, 1);
                    brHurtPierce(ref.boss, ent, BR_DASH_DMG, 0.0, 0.0, 0.0);   // ★ 无视防具
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // 拖尾
            try {
                Particle pt = Particle.CLOUD;
                for (int j = -2; j <= 2; j++) {
                    world.spawnParticle(pt, x + j * 1.0, y + 0.15, z + j * 1.0,
                            2, 0.2, 0.1, 0.2, 0.02);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            ref.dashI = i + 1;
            if (ref.dashI >= BR_DASH_TICKS) {
                ref.mode = "idle";
                return;
            }
            GS.runLater(() -> brDashTick(ref), 1L);
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ================================================================
    // AI 心跳
    // ================================================================

    private void brTick(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            if (!brAlive(ref.boss)) {
                brCleanup(ref);
                return;
            }

            // ★ 记下最后一刻的位置（boss 真没了的时候，掉落物要靠这个坐标）
            try {
                Location pl = ref.boss.getLocation();
                ref.lx = pl.getX();
                ref.ly = pl.getY();
                ref.lz = pl.getZ();
                ref.lw = pl.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 战马比 boss 先死 → **惹怒 boss**（解锁凋零之首）
            if (ref.horse != null && !brAlive(ref.horse)) {
                ref.horse = null;                  // 以后只能走路（冲刺用不了）
                if (!ref.enraged) {
                    ref.enraged = true;
                    try {
                        World wl = ref.lw;
                        if (wl == null) {
                            wl = ref.boss.getWorld();
                        }
                        wl.playSound(ref.boss.getLocation(), "entity.wither.spawn", 1.0f, 0.8f);
                        // ★ 原脚本三档：SMOKE_LARGE（取不到）→ valueOf（抛）→ SMOKE ✔
                        Particle pp = Particle.SMOKE;
                        for (int q = 0; q < 10; q++) {
                            wl.spawnParticle(pp,
                                    ref.lx, ref.ly + 1.0 + q * 0.12, ref.lz,
                                    2, 0.35, 0.35, 0.35, 0.05);
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            // ★ boss 血条：每拍刷新（枯骸将军 / 璇骊 各一条）
            try {
                brBarSync(ref.barBoss, ref.boss, BR_BOSS_HP, ref.barBossNear, ref.owner);
                brBarSync(ref.barHorse, ref.horse, BR_HORSE_HP, ref.barHorseNear, ref.owner);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 璇骊的马鞍复查补装（每 4 拍 = 1 秒一次）
            try {
                ref.tickN = ref.tickN + 1;
                if (ref.tickN % 4 == 2 && ref.horse != null) {
                    brSaddle(ref.horse);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 伺骨：把已经消散的从列表里剔掉，顺便数一下还活着几只
            try {
                List<Sv> keep = new ArrayList<>();
                for (Sv so : ref.servants) {
                    if (so != null && !so.done && brAlive(so.sk)) {
                        keep.add(so);
                    }
                }
                ref.servants.clear();
                ref.servants.addAll(keep);
            } catch (Throwable ignored) {
                // 静默
            }

            if (!"dash".equals(ref.mode)) {
                Entity tgt = brTarget(ref);
                if (tgt != null) {
                    long now = Guards.now();
                    double d = brDist(ref.boss, tgt);

                    // 斩击：★ 出场 BR_FIRST_QI 毫秒内不出斩击（ref.qiAt 初值已含这个延迟）
                    if (d <= BR_QI_RANGE && (now - ref.qiAt) >= BR_QI_CD_MS) {
                        ref.qiAt = now;
                        brStartSlash(ref, tgt);
                    }
                    // ★ 伺骨：冷却 30 秒（出场 BR_SV_FIRST 毫秒后才第一次召）
                    if ((now - ref.svAt) >= BR_SV_CD_MS) {
                        // ★ 只有真的召出来了才重置 CD（满员时下一拍再试）
                        if (brSummonServants(ref) > 0) {
                            ref.svAt = now;
                        }
                    }
                    // 冲刺：8 点 / 30 秒 / 20 格内（要马还在）
                    if (ref.horse != null && d <= BR_DASH_RANGE
                            && (now - ref.dashAt) >= BR_DASH_CD_MS) {
                        ref.dashAt = now;
                        brStartDash(ref, tgt);
                    }
                    // ★ 狂暴后：朝最近的玩家发射凋零之首
                    if (ref.enraged && d <= BR_SKULL_RANGE
                            && (now - ref.skullAt) >= BR_SKULL_CD_MS) {
                        ref.skullAt = now;
                        brFireSkull(ref, tgt);
                    }
                }
            }

            GS.runLater(() -> brTick(ref), BR_TICK);
        } catch (Throwable e) {
            // 出错也别让 boss 变成「傻站着」：只要还活着就继续下一拍
            try {
                if (ref != null && brAlive(ref.boss)) {
                    GS.runLater(() -> brTick(ref), BR_TICK);
                } else {
                    brCleanup(ref);
                }
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ================================================================
    // boss 死亡必掉「五骊矟」（只掉一次）
    // ================================================================

    private void brDropReward(Ref ref) {
        try {
            if (ref == null || ref.dropped) {
                return;
            }
            ref.dropped = true;

            World world = ref.lw;
            double x = ref.lx;
            double y = ref.ly;
            double z = ref.lz;
            if (world == null) {
                try {
                    Location l = ref.boss.getLocation();
                    world = l.getWorld();
                    x = l.getX();
                    y = l.getY();
                    z = l.getZ();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (world == null) {
                return;
            }

            ItemStack stack = null;
            try {
                SlimefunItem sf = GS.sfItem(BR_DROP_ID);
                if (sf != null) {
                    stack = sf.getItem().clone();
                }
            } catch (Throwable e) {
                stack = null;
            }

            if (stack == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 枯骸骑将：取不到 " + BR_DROP_ID + "，未掉落");
                } catch (Throwable ignored) {
                    // 静默
                }
                return;
            }

            world.dropItemNaturally(new Location(world, x, y + 0.5, z), stack);
            try {
                world.playSound(new Location(world, x, y, z), "entity.player.levelup", 0.6f, 1.2f);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 收尾：boss 没了 → 战马跟着一起死、伺骨一并化烟、血条与 BGM 全收、必掉五骊矟。 */
    private void brCleanup(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            LivingEntity b = ref.boss;
            LivingEntity h = ref.horse;

            // ★ boss 归天 → 伺骨也一并化烟（陪葬）
            try {
                for (Sv sv : ref.servants) {
                    brSvDispose(sv, "puff");
                }
                ref.servants.clear();
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 收掉两条 boss 血条
            try {
                brBarDrop(ref.barBoss, ref.barBossNear);
                ref.barBoss = null;
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                brBarDrop(ref.barHorse, ref.barHorseNear);
                ref.barHorse = null;
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 停掉 BGM（boss 没了，音乐也别再放）
            try {
                brMusicStopAll(ref);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ boss 死亡必掉五骊矟
            brDropReward(ref);

            // ★ 战马跟着一起**死掉**（不是凭空消失：先扣血走死亡流程，不行再兜底移除）
            if (h != null) {
                try {
                    h.eject();
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    EntityEquipment eq = h.getEquipment();
                    if (eq != null) {
                        try {
                            eq.clear();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    if (!h.isDead()) {
                        try {
                            h.setHealth(0.0);
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        GS.runLater(() -> {
                            try {
                                if (!h.isDead() && h.isValid()) {
                                    h.remove();
                                }
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }, 2L);
                    }
                } catch (Throwable e) {
                    try {
                        h.remove();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
                try {
                    h2b.remove(String.valueOf(h.getUniqueId()));
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.horse = null;
            }
            // boss 死了也别掉装备
            if (b != null) {
                try {
                    EntityEquipment eq2 = b.getEquipment();
                    if (eq2 != null) {
                        try {
                            eq2.clear();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            try {
                if (b != null) {
                    bosses.remove(String.valueOf(b.getUniqueId()));
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                if (ref.owner != null && owner.get(ref.owner) != null) {
                    owner.remove(ref.owner);
                }
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 召唤
    // ================================================================

    private boolean brSummon(Player p) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // ① 已经有一只活的 → 静默忽略（防连点刷爆）
            String oldId = owner.get(uuid);
            if (oldId != null) {
                Ref old = bosses.get(oldId);
                if (old != null && brAlive(old.boss)) {
                    return false;
                }
                owner.remove(uuid);
            }

            World world = p.getWorld();
            Location base = p.getLocation();

            // ② 出生点：玩家身前 3 格
            double yaw = 0;
            try {
                yaw = base.getYaw();
            } catch (Throwable e) {
                yaw = 0;
            }
            double[] ahead = spawnAhead(base.getX(), base.getZ(), yaw);
            Location loc = new Location(world, ahead[0], base.getY(), ahead[1]);
            try {
                loc.setYaw((float) yaw);
                loc.setPitch(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            LivingEntity horse = brSpawnHorse(world, loc, null, null, null);
            LivingEntity boss = brSpawnBoss(world, loc);
            if (boss == null) {
                // boss 没生成成功 → 把马也清掉，别留孤儿实体
                if (horse != null) {
                    try {
                        horse.remove();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
                return false;
            }
            if (horse != null) {
                try {
                    horse.addPassenger(boss);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            Ref ref = new Ref();
            ref.boss = boss;
            ref.horse = horse;
            ref.owner = uuid;
            ref.mode = "idle";
            // ★ 出场 5 秒后才允许第一次斩击（刚召唤就劈一刀，玩家根本反应不过来）
            ref.qiAt = Guards.now() - BR_QI_CD_MS + BR_FIRST_QI;
            ref.dashAt = Guards.now() - BR_DASH_CD_MS + BR_FIRST_DASH;
            ref.svAt = Guards.now() - BR_SV_CD_MS + BR_SV_FIRST;
            ref.skullAt = 0;
            ref.lx = loc.getX();
            ref.ly = loc.getY();
            ref.lz = loc.getZ();
            ref.lw = world;

            // ★ boss 血条：枯骸将军（红 · 10 段）/ 璇骊（白 · 10 段）
            try {
                ref.barBoss = brBarMake("枯骸将军", BarColor.RED, BarStyle.SEGMENTED_10);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.barHorse = brBarMake("璇骊", BarColor.WHITE, BarStyle.SEGMENTED_10);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                brBarSync(ref.barBoss, boss, BR_BOSS_HP, ref.barBossNear, uuid);
                brBarSync(ref.barHorse, horse, BR_HORSE_HP, ref.barHorseNear, uuid);
            } catch (Throwable ignored) {
                // 静默
            }

            String bid;
            try {
                bid = String.valueOf(boss.getUniqueId());
            } catch (Throwable e) {
                return false;
            }
            bosses.put(bid, ref);
            owner.put(uuid, bid);
            if (horse != null) {
                try {
                    h2b.put(String.valueOf(horse.getUniqueId()), bid);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            try {
                world.playSound(loc, "entity.wither.spawn", 0.5f, 1.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ BGM：boss 周围循环播放《Pigstep》（走进范围才播，走出去/归天立刻停）
            try {
                if (BR_MUSIC) {
                    brMusicTick(ref);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 出场提示词：先报枯骸将军，隔 0.5 秒再报璇骊
            try {
                brAnnounce(world, BR_MSG_BOSS);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                GS.runLater(() -> {
                    try {
                        brAnnounce(world, BR_MSG_HORSE);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }, 10L);
            } catch (Throwable ignored) {
                // 静默
            }

            GS.runLater(() -> brTick(ref), BR_TICK);
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 枯骸骑将 召唤出错: " + e);
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        }
    }

    // ================================================================
    // boss 血条
    // ================================================================

    /** 造一条血条（★ 标题<b>不带颜色码</b>：走 Adventure，塞 § 可能原样显示成乱码）。 */
    private static BossBar brBarMake(String title, BarColor c, BarStyle s) {
        if (!BR_BAR) {
            return null;
        }
        try {
            BossBar bar = Bukkit.createBossBar(title, c, s);
            if (bar == null) {
                return null;
            }
            try {
                bar.setVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable ignored) {
                // 静默
            }
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 同步一条血条：进度 + 观看者（nearMap 记着「已经加进来的人」，好在他们走远后 removePlayer）。
     *
     * @param ownerUuid 非空 ⇒ <b>只有这个玩家</b>看得到（{@link #BR_BAR_ONLY_OWNER}）
     */
    private static void brBarSync(BossBar bar, LivingEntity ent, double maxHp,
                                  Map<String, Player> nearMap, String ownerUuid) {
        try {
            if (bar == null) {
                return;
            }
            boolean alive = brAlive(ent);

            // ① 进度
            double prog = 0.0;
            if (alive) {
                try {
                    double hp = ent.getHealth();
                    double mx = maxHp;
                    try {
                        AttributeInstance inst = ent.getAttribute(Attribute.MAX_HEALTH);
                        if (inst != null) {
                            double v = inst.getValue();
                            if (v > 0) {
                                mx = v;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    prog = barProgress(hp, mx);
                } catch (Throwable e) {
                    prog = 0.0;
                }
            }
            try {
                bar.setProgress(prog);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                bar.setVisible(alive);
            } catch (Throwable ignored) {
                // 静默
            }

            // ② 观看者：范围内加进来，走出去 / 下线就移除
            Map<String, Player> want = new HashMap<>();
            if (alive) {
                try {
                    boolean only = (BR_BAR_ONLY_OWNER && ownerUuid != null && !ownerUuid.isEmpty());
                    String wname = String.valueOf(ent.getWorld().getName());
                    Location el = ent.getLocation();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        // ★ 只给召唤者看：不是本人直接跳过（下线了自然也不在在线列表里）
                        if (only) {
                            try {
                                if (!String.valueOf(p.getUniqueId()).equals(ownerUuid)) {
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
                        try {
                            String gm = p.getGameMode().name();
                            if (gm.contains("SPECTATOR")) {
                                continue;
                            }
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        Location pl;
                        try {
                            pl = p.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (pl.distance(el) > BR_BAR_RANGE) {
                            continue;
                        }
                        try {
                            want.put(String.valueOf(p.getUniqueId()), p);
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            for (Map.Entry<String, Player> en : want.entrySet()) {
                if (nearMap.get(en.getKey()) == null) {
                    try {
                        bar.addPlayer(en.getValue());
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    nearMap.put(en.getKey(), en.getValue());
                }
            }
            for (String k2 : new ArrayList<>(nearMap.keySet())) {
                if (want.get(k2) == null) {
                    try {
                        bar.removePlayer(nearMap.get(k2));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    nearMap.remove(k2);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 收摊：整条血条撤掉（boss 没了 / reload）。 */
    private static void brBarDrop(BossBar bar, Map<String, Player> nearMap) {
        try {
            if (bar == null) {
                return;
            }
            try {
                bar.setVisible(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                bar.removeAll();
            } catch (Throwable ignored) {
                // 静默
            }
            if (nearMap != null) {
                nearMap.clear();
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 出场 BGM
    // ================================================================

    /** 反射取 {@code Sound} 接口上的静态常量（等价 JS 的 {@code Sound[nm] != null}）。 */
    private static Sound soundOf(String name) {
        try {
            return (Sound) Sound.class.getField(name).get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 挑一个**这台服务器真的有**的唱片音效（1.16+ 有 pigstep，万一没有就往下退 …）。 */
    private String brMusicSound() {
        try {
            if (musicPicked != null) {
                return musicPicked;
            }
            for (String cand : MUSIC_CANDS) {
                String nm = musicEnumName(cand);
                if (soundOf(nm) != null) {
                    musicPicked = cand;
                    return musicPicked;
                }
            }
            musicPicked = BR_MUSIC_SOUND;
            return musicPicked;
        } catch (Throwable e) {
            return BR_MUSIC_SOUND;
        }
    }

    private Sound brMusicEnum() {
        try {
            return soundOf(musicEnumName(brMusicSound()));
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean brMusicPlayTo(Player p, Location loc) {
        try {
            if (p == null || loc == null) {
                return false;
            }
            String name = brMusicSound();
            try {
                p.playSound(loc, name, (float) BR_MUSIC_VOL, 1.0f);
                return true;
            } catch (Throwable ignored) {
                // 静默
            }
            Sound en = brMusicEnum();
            if (en != null) {
                try {
                    p.playSound(loc, en, (float) BR_MUSIC_VOL, 1.0f);
                    return true;
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                loc.getWorld().playSound(p, name, (float) BR_MUSIC_VOL, 1.0f);
                return true;
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void brMusicStopTo(Player p) {
        try {
            if (p == null) {
                return;
            }
            String name = brMusicSound();
            try {
                p.stopSound(name);
                return;
            } catch (Throwable ignored) {
                // 静默
            }
            Sound en = brMusicEnum();
            if (en != null) {
                try {
                    p.stopSound(en);
                    return;
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // 最后兜底：停掉整个「唱片」分类
            try {
                p.stopSound(org.bukkit.SoundCategory.RECORDS);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 每秒检查一次谁在范围内，**到点就重放** ⇒ 循环播放。 */
    private void brMusicTick(Ref ref) {
        try {
            if (!BR_MUSIC) {
                return;
            }
            if (ref == null || !brAlive(ref.boss)) {
                brMusicStopAll(ref);
                return;
            }

            long now = Guards.now();
            Location bl = null;
            String wname = "";
            try {
                bl = ref.boss.getLocation();
                wname = String.valueOf(bl.getWorld().getName());
            } catch (Throwable ignored) {
                // 静默
            }

            Map<String, Player> want = new HashMap<>();
            if (bl != null) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p == null) {
                        continue;
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
                    if (pl.distance(bl) > BR_MUSIC_RANGE) {
                        continue;
                    }
                    try {
                        want.put(String.valueOf(p.getUniqueId()), p);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            // ① 刚走进范围 ⇒ 从头播；② 已经听了 148 秒（= 曲目长度）⇒ 重放（循环）
            for (Map.Entry<String, Player> en : want.entrySet()) {
                MusicRec rec = ref.music.get(en.getKey());
                if (rec == null || (now - rec.at) >= BR_MUSIC_LOOP_MS) {
                    boolean okp;
                    try {
                        okp = brMusicPlayTo(en.getValue(), bl);
                    } catch (Throwable e) {
                        okp = false;
                    }
                    MusicRec fresh = new MusicRec();
                    fresh.p = en.getValue();
                    fresh.at = now;
                    ref.music.put(en.getKey(), fresh);
                    if (!okp && rec == null) {
                        // 这台服务器没有这个音效 ⇒ 记一笔，别每秒重试刷后台
                        try {
                            Bukkit.getLogger().info("[构思技艺] 枯骸骑将：本服没有音效 "
                                    + brMusicSound() + "，BGM 不播放");
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                }
            }
            // ③ 走出范围 / 下线 ⇒ 立刻停
            for (String k2 : new ArrayList<>(ref.music.keySet())) {
                if (want.get(k2) == null) {
                    try {
                        brMusicStopTo(ref.music.get(k2).p);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    ref.music.remove(k2);
                }
            }

            GS.runLater(() -> brMusicTick(ref), 20L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** boss 归天 / 收摊：把所有还在听的人的音乐停掉。 */
    private void brMusicStopAll(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            for (String k : new ArrayList<>(ref.music.keySet())) {
                try {
                    brMusicStopTo(ref.music.get(k).p);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.music.remove(k);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 监听器注册
    // ================================================================

    /**
     * ① 方块交互拦截 —— 只做「手持本物品右键方块 ⇒ 取消」这一件事，用共享的
     * {@link InteractGuard}（原脚本的 {@code brRegisterGuard} 与它逐字同构：
     * 同样的 RIGHT_CLICK / OFF 手 / {@code getItem()} 非空 / {@code brIsOurItem} 四条判定）。
     */
    private static boolean registerGuard() {
        return InteractGuard.register(BR_ITEM_ID);
    }

    /**
     * ② 死亡掉落处理 + 凋零之首伤害改写（原脚本 {@code brRegisterDeath} 一次注册两个事件）。
     *
     * <p>★ 闩锁是<b>实例级</b>的（= 原脚本一次 eval 一份的 {@code BR_DEATH_OK}），
     * 见类注释。
     */
    private boolean registerDeath() {
        if (deathOk) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new DeathListener(this), GS.plugin());
            deathOk = true;
            return true;
        } catch (Throwable e) {
            deathOk = false;
            return false;
        }
    }

    /** ③ 凋零之首「不破坏地形」（原脚本 {@code brRegisterExplode}）。 */
    private boolean registerExplode() {
        if (explodeOk) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new ExplodeListener(this), GS.plugin());
            explodeOk = true;
            return true;
        } catch (Throwable e) {
            explodeOk = false;
            return false;
        }
    }

    /** ④ 阵营：禁止「自己人把自己人当目标」（原脚本 {@code brRegisterAlly}）。 */
    private boolean registerAlly() {
        if (allyOk) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new AllyListener(this), GS.plugin());
            allyOk = true;
            return true;
        } catch (Throwable e) {
            allyOk = false;
            return false;
        }
    }

    /** 死亡事件 + 凋零之首伤害改写（同一个监听器，HIGHEST）。 */
    static final class DeathListener implements Listener {
        private final RiderModule m;

        DeathListener(RiderModule m) {
            this.m = m;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent e) {
            m.brOnEntityDeath(e);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSkullDamage(EntityDamageByEntityEvent e) {
            m.brOnSkullDamage(e);
        }
    }

    /** 爆炸拦截（HIGHEST，★ 只清方块列表、**不取消事件**）。 */
    static final class ExplodeListener implements Listener {
        private final RiderModule m;

        ExplodeListener(RiderModule m) {
            this.m = m;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onExplode(EntityExplodeEvent e) {
            m.brOnExplode(e);
        }
    }

    /** 索敌拦截（HIGHEST）。 */
    static final class AllyListener implements Listener {
        private final RiderModule m;

        AllyListener(RiderModule m) {
            this.m = m;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAllyTarget(EntityTargetEvent e) {
            m.brOnAllyTarget(e);
        }
    }

    // ================================================================
    // 死亡事件：删原版掉落 / 必掉五骊矟 / 马先死则狂暴
    // ================================================================

    private void brOnEntityDeath(EntityDeathEvent e) {
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

            String tag = brTag(ent);
            if (tag.isEmpty()) {
                return;                            // 不是我们的人 → 不管
            }

            // ★ 原版掉落物（骷髅的骨头 / 箭、骷髅马的骨头）**全部删除**
            try {
                e.getDrops().clear();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                e.setDroppedExp(0);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ **马鞍也不许掉** —— 马的鞍存在 HorseInventory 里（不在 Equipment）
            try {
                if (TAG_HORSE.equals(tag) || TAG_SERVANT_HORSE.equals(tag)) {
                    if (ent instanceof InventoryHolder hi) {
                        try {
                            hi.getInventory().clear();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                EntityEquipment eqd = (ent instanceof LivingEntity le) ? le.getEquipment() : null;
                if (eqd != null) {
                    try {
                        eqd.clear();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                e.getDrops().clear();              // 清完装备再清一次，双保险
            } catch (Throwable ignored) {
                // 静默
            }

            String eid;
            try {
                eid = String.valueOf(ent.getUniqueId());
            } catch (Throwable err) {
                return;
            }

            if ("bone_rider".equals(tag)) {
                Ref ref = bosses.get(eid);
                if (ref != null) {
                    try {
                        Location l = ent.getLocation();
                        ref.lx = l.getX();
                        ref.ly = l.getY();
                        ref.lz = l.getZ();
                        ref.lw = l.getWorld();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    brDropReward(ref);             // ★ 必掉五骊矟
                    // ★ 战马跟着一起死
                    LivingEntity h = ref.horse;
                    if (h != null && brAlive(h)) {
                        try {
                            h.eject();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        try {
                            EntityEquipment eq = h.getEquipment();
                            if (eq != null) {
                                try {
                                    eq.clear();
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                            }
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        try {
                            h.setHealth(0.0);
                        } catch (Throwable err) {
                            try {
                                h.remove();
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    }
                    brCleanup(ref);
                }
                bosses.remove(eid);

            } else if (TAG_HORSE.equals(tag)) {
                String bid = h2b.get(eid);
                if (bid != null) {
                    Ref ref2 = bosses.get(bid);
                    if (ref2 != null) {
                        ref2.horse = null;
                        if (!ref2.enraged) {       // ★ 马先死 → 惹怒 boss
                            ref2.enraged = true;
                            try {
                                ent.getWorld().playSound(ent.getLocation(),
                                        "entity.wither.spawn", 1.0f, 0.8f);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    }
                    h2b.remove(eid);
                }

            // ★ 伺骨 / 残驹：**共生** —— 一个死了，另一个立刻跟着死
            } else if ("bone_rider_servant".equals(tag)) {
                Sv sv1 = servants.get(eid);
                if (sv1 != null) {
                    brSvDispose(sv1, "kill");
                    servants.remove(eid);
                }

            } else if (TAG_SERVANT_HORSE.equals(tag)) {
                String sid = svH2S.get(eid);
                if (sid != null) {
                    Sv sv2 = servants.get(sid);
                    if (sv2 != null) {
                        brSvDispose(sv2, "kill");
                    }
                    svH2S.remove(eid);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 凋零之首命中：伤害固定 20 点 + 凋零 II 5 秒
    // ================================================================

    private void brOnSkullDamage(EntityDamageByEntityEvent e) {
        try {
            // ★ 先过一遍「自己人不打自己人」（本监听器挂在 EntityDamageByEntityEvent 上，
            //   顺手把 boss / 伺骨 之间的误伤 —— 平A、凋零之首、弹体 —— 全部免掉）
            try {
                brOnAllyDamage(e);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                brOnPierce(e);                     // ★ 冲刺伤害无视防具
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                brOnServantDamage(e);              // ★ 伺骨不许打创造 / 旁观
            } catch (Throwable ignored) {
                // 静默
            }

            Entity dm;
            try {
                dm = e.getDamager();
            } catch (Throwable err) {
                dm = null;
            }
            if (dm == null) {
                return;
            }

            String v;
            try {
                v = dm.getPersistentDataContainer().get(BR_SKULL_KEY, PersistentDataType.STRING);
            } catch (Throwable err) {
                return;
            }
            if (v == null) {
                return;                            // 不是我们发的头 → 不管
            }

            try {
                e.setDamage(BR_SKULL_DMG);         // ★ 固定 20 点
            } catch (Throwable ignored) {
                // 静默
            }

            Entity victim0;
            try {
                victim0 = e.getEntity();
            } catch (Throwable err) {
                victim0 = null;
            }
            if (victim0 == null) {
                return;
            }
            // ★ lambda 捕获：在 catch 里赋值的局部变量不是 effectively final（契约 §2.5）
            final Entity victim = victim0;

            // 凋零 II 5 秒：延后 1 刻**强制覆盖**（原版凋零头自带的那份更长，会被我们顶掉）
            GS.runLater(() -> {
                try {
                    if (!(victim instanceof LivingEntity le)) {
                        return;                    // 原脚本对非 LivingEntity 会抛、被吞
                    }
                    PotionEffectType t = PotionEffectType.WITHER;
                    try {
                        le.addPotionEffect(new PotionEffect(t, BR_SKULL_WITHER_T,
                                BR_SKULL_WITHER_AMP, true, true), true);
                    } catch (Throwable err1) {
                        try {
                            le.addPotionEffect(new PotionEffect(t, BR_SKULL_WITHER_T,
                                    BR_SKULL_WITHER_AMP));
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }, 1L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 凋零之首「不破坏地形」—— 真正的实现在这里
    // ================================================================

    /**
     * 这是不是我们发的凋零之首？双重判定：
     * <ol>
     *   <li>弹体自己带 {@code gs:boss_skull} 标记；</li>
     *   <li>弹体的发射者（shooter）带 {@code gs:boss} 标记（标记万一没写上去时的兜底）。</li>
     * </ol>
     */
    private static boolean brIsOurSkull(Entity ent) {
        try {
            if (ent == null) {
                return false;
            }

            // ① 弹体自己的标记
            try {
                if (ent.getPersistentDataContainer().get(BR_SKULL_KEY,
                        PersistentDataType.STRING) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ② 发射者是不是我们的 boss
            if (ent instanceof Projectile pr) {
                try {
                    org.bukkit.projectiles.ProjectileSource sh = pr.getShooter();
                    if (sh instanceof Entity se && se.getPersistentDataContainer()
                            .get(BR_BOSS_KEY, PersistentDataType.STRING) != null) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * 凋零之首「<b>不破坏地形</b>」。
     *
     * <p>★ <b>只清方块列表、不 {@code setCancelled(true)}</b>：取消事件有可能把
     * 「对生物的伤害」一起掐掉，伤害还是交给 {@link #brOnSkullDamage} 结算。
     * <br>Bukkit 爆炸流程是「算出待破坏方块 → 发事件 → 按<b>事件里的那份列表</b>去破坏」，
     * 所以清空列表 = 一个方块都不会被移除。
     */
    private void brOnExplode(EntityExplodeEvent e) {
        try {
            Entity ent;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                return;
            }
            if (!brIsOurSkull(ent)) {
                return;                            // 不是我们发的头 → 不管
            }

            // ① 主手段：清空待破坏方块列表 ⇒ 地形一个方块都不掉
            List<BlockSnap> snap = null;
            try {
                List<Block> list = e.blockList();
                if (list != null) {
                    try {
                        if (!list.isEmpty()) {
                            snap = new ArrayList<>();
                            for (Block bl : list) {
                                try {
                                    BlockSnap rec = new BlockSnap();
                                    rec.b = bl;
                                    rec.d = bl.getBlockData();
                                    snap.add(rec);
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    list.clear();
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // 顺带把掉落率设 0（只剩「不掉物品」这一层保险，与上面不冲突）
            try {
                e.setYield(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            // ② 兜底：某些分支下「清空列表」可能不被采纳（爆炸链走的是它自己那份集合）。
            //    那就 1 刻之后**照着快照把被炸掉的方块还原** —— 只在「方块真的变了」时才写回，
            //    所以正常情况下这一步是纯检查、一个方块都不动。
            if (snap != null && !snap.isEmpty()) {
                final List<BlockSnap> s = snap;
                GS.runLater(() -> {
                    try {
                        for (BlockSnap rec : s) {
                            try {
                                if (!String.valueOf(rec.b.getType().name())
                                        .equals(String.valueOf(rec.d.getMaterial().name()))) {
                                    rec.b.setBlockData(rec.d);
                                }
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }, 1L);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ================================================================
    // 入口
    // ================================================================

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            // ★ 惰性重试：脚本加载时插件可能还没就绪，这里在使用时再补一次（装好就是空操作）
            try {
                registerGuard();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                registerDeath();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                registerExplode();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                registerAlly();
            } catch (Throwable ignored) {
                // 静默
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

            // ★ 只认主手（副手触发会「不消耗却生效」）
            if (!brIsMainHandUse(event, p)) {
                return null;
            }

            // 防同一次点击被派发两遍（主手 + 副手 / 重复注册）
            String uuid = String.valueOf(p.getUniqueId());
            long now = Guards.now();
            Long lastAt = last.get(uuid);
            if (lastAt != null && (now - lastAt) < 500L) {
                return null;
            }
            last.put(uuid, now);

            // ★ 召唤成功才扣物品（已经是「每个玩家 1 只」的上限内）
            if (!brSummon(p)) {
                return null;
            }
            if (BR_CONSUME) {
                brConsume(p);
            }
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 枯骸骑将 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }

    /**
     * 玩家下线：不删 boss（别人可能正在打），只把「归属」让出来，方便他下次再召。
     */
    @Override
    protected Object onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        try {
            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }
            String uuid = String.valueOf(p.getUniqueId());
            String bid = owner.get(uuid);
            if (bid == null) {
                return null;
            }
            Ref ref = bosses.get(bid);
            if (ref != null && brAlive(ref.boss)) {
                return null;                       // 还活着 → 留着
            }
            owner.remove(uuid);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    // ================================================================
    // 数据结构
    // ================================================================

    /** 一只 boss 的全部状态（原脚本的 ref 字面量）。 */
    private static final class Ref {
        LivingEntity boss;
        LivingEntity horse;
        String owner;
        String mode = "idle";
        long qiAt;
        long dashAt;
        long svAt;
        final List<Sv> servants = new ArrayList<>();
        BossBar barBoss;
        BossBar barHorse;
        final Map<String, Player> barBossNear = new HashMap<>();
        final Map<String, Player> barHorseNear = new HashMap<>();
        final Map<String, MusicRec> music = new HashMap<>();
        final Map<String, Integer> hit = new HashMap<>();
        int dashI;
        double dirX;
        double dirZ;
        double x0;
        double y0;
        double z0;
        double yaw;
        boolean enraged;
        long skullAt;
        boolean dropped;
        int slashSeq;
        double lx;
        double ly;
        double lz;
        World lw;
        long tickN;
    }

    /** 一组伺骨（原脚本的 sv 字面量）。 */
    private static final class Sv {
        LivingEntity sk;
        LivingEntity hs;
        Ref bossRef;
        int life;
        boolean done;
        int tickN;
        Entity tgt;
    }

    /** 一道剑气波（原脚本的 wave 字面量）。 */
    private static final class Wave {
        double wx;
        double wy;
        double wz;
        double dx;
        double dy;
        double dz;
        double hx;
        double hz;
        final Map<String, Integer> hit = new HashMap<>();
    }

    /** BGM：某个玩家上次听到的时刻。 */
    private static final class MusicRec {
        Player p;
        long at;
    }

    /** 冲刺「无视防具」的登记记录。 */
    private static final class Pierce {
        double d;
        long t;
    }

    /** 爆炸前的方块快照（兜底还原用）。 */
    private static final class BlockSnap {
        Block b;
        org.bukkit.block.data.BlockData d;
    }
}
