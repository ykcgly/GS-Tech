package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.GSPlugin;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;

/**
 * {@code gs_listener.js} 的「祖宗发射器」子系统
 * （原脚本第 <b>13673~14336</b> 行，顶层入口 {@code gsAlStart();} 在第 14336 行）——
 * ★ 第七十六批（2026-10-12）新增。
 *
 * <h2>玩法</h2>
 * 手持「祖宗发射器&lt;?&gt;」（{@code GS_ANCESTOR_LAUNCHER}，岩浆鸡唱片）时：
 * <ul>
 *   <li><b>左键 = 开火</b>：从<b>胸口</b>朝准星方向打出一发弹道，
 *       每刻前进 {@link #GS_AL_STEP} 格，撞墙 / 撞生物 / 到射程尽头 ⇒ 引爆。</li>
 *   <li><b>右键 = 爆裂黎明 开 / 关</b>（1 秒防抖；关掉时开始 12 秒冷却）。</li>
 *   <li>物品名字里的 {@code <...>} 就是弹药显示：常态 {@code <?>}、技能 {@code <▼▼▼▼▼▼>}。</li>
 * </ul>
 *
 * <h2>★★ 两套状态（用户逐条确认过的数值，第七十八 / 七十九批改过）</h2>
 * <table border="1">
 *   <tr><th></th><th>常态</th><th>爆裂黎明（技能）</th></tr>
 *   <tr><td>射速</td><td>{@link #GS_AL_FIRE_MS} = 800（0.8 秒/发）</td>
 *       <td>{@link #GS_AL_SKILL_FIRE_MS} = 1500（1.5 秒/发）</td></tr>
 *   <tr><td>弹药</td><td><b>无限</b>（不扣）</td><td>{@link #GS_AL_AMMO} = 6 发，打一发少一个 ▼</td></tr>
 *   <tr><td>拖尾</td><td>红色 {@code DUST}（{@code Color.fromRGB(255,40,40)}，size 1.2）</td>
 *       <td>{@code SONIC_BOOM}（坚守者冲击波）</td></tr>
 *   <tr><td>爆炸半径</td><td>{@link #GS_AL_N_R} = 2.5 格</td><td>{@link #GS_AL_S_R} = 4.5 格</td></tr>
 *   <tr><td>爆炸伤害</td><td>{@link #GS_AL_N_DMG} = 35 点</td><td>{@link #GS_AL_S_DMG} = 175 点</td></tr>
 *   <tr><td>爆炸音效</td><td>{@code entity.generic.explode}</td>
 *       <td>{@code entity.warden.sonic_boom}</td></tr>
 *   <tr><td>开火音效</td><td>{@code block.dispenser.launch}</td>
 *       <td>{@code entity.warden.sonic_charge}</td></tr>
 * </table>
 * <p>两套状态<b>共用</b>：射程 {@link #GS_AL_RANGE} = 24 格、命中半径 {@link #GS_AL_HIT_R} = 0.8 格、
 * 发射高度 {@link #GS_AL_MUZZLE_Y} = 1.2 格（胸口）。
 *
 * <h2>★★ 弹道：24 格 / 30 格每秒 —— 两个数是同一套参数</h2>
 * <pre>
 *   GS_AL_STEP     = 1.5 格/刻      （每刻前进）
 *   GS_AL_SPEED_BPS = 30 格/秒      = 1.5 × 20 刻/秒
 *   GS_AL_RANGE    = 24 格          = 1.5 × 16 刻 = 0.8 秒
 * </pre>
 * ★ 三者<b>互相自洽</b>：{@code 1.5 × 20 = 30}、{@code 30 × 0.8 = 24}、{@code 24 / 1.5 = 16 刻}。
 * 所以「0.8 秒飞 24 格」和「弹速 30 格/秒」不是两套独立的说法，而是同一个 {@code STEP} 的两种表述。
 * <p>★ {@link #GS_AL_SPEED_BPS} 在代码里<b>只出现在日志字符串</b>里，弹道推进实际只读
 * {@code GS_AL_STEP}（逐字照抄原脚本：它也没把 SPEED_BPS 用进算式）。
 *
 * <h2>★★ 推进节奏：{@code runLater} 自链，<b>不是</b> {@code runRepeating}</h2>
 * 原脚本用 {@code runLater(step, 1)} 自我调度（同 {@code gs_release_dream.js}），
 * 因为每发弹的起点 / 方向都不同，共用一个周期任务反而要自己维护「这一拍推哪几发」。
 * Java 侧同样用 {@link GS#runLater}。
 *
 * <h2>★ 撞墙判定：扫掠求交，不是单点采样</h2>
 * 每一刻用<b>上一刻到这一刻</b>这一段做 {@code world.rayTraceBlocks(prev, dir, stepLen, NEVER, true)}，
 * 而不是「只看终点那一个方块」。★ 顺序上<b>先判方块、后判生物</b> ——
 * 否则站在薄墙后面的怪会被<b>隔墙打中</b>（原脚本 14039 行专门写了这条注释）。
 *
 * <h2>★ 不破坏地形</h2>
 * 全程<b>不用</b> {@code createExplosion}：视觉是 {@code EXPLOSION_EMITTER} +
 * {@code EXPLOSION} 粒子，伤害是手搓的球形范围结算 ⇒ <b>不炸方块</b>。
 *
 * <h2>★★ 删除的聊天提示词（逐字照抄原脚本的「一条都不发」）</h2>
 * 原脚本 13699~13701 行的注释明确：<b>冷却中 / 已启动 / 已关闭 / 弹药耗尽 —— 一条都不再发消息</b>，
 * 状态<b>只看物品名字</b>。所以本类里<b>没有</b> {@code sendMessage}、
 * 也<b>没有</b>任何「提示」函数（探针里有反向断言钉住这件事）。
 * <p>★ 连带的一个细节：{@code gsAlExitSkill(p, silent)} 的 {@code silent} 参数
 * <b>现在只决定「放不放关闭音效」</b>，不再决定「发不发消息」（原脚本 13941 行注释）。
 *
 * <h2>★ 换手持不再关闭技能（第七十七批）</h2>
 * 心跳 {@link #gsAlPulse()} 只做一件事：拿着发射器时把名字刷成当前状态。
 * <b>切走时什么都不做</b> —— 技能 / 弹药 / 冷却全部原样保留，切回来接着用
 * （原脚本 14188~14190 行）。<b>不要</b>「顺手」在切走时收掉技能。
 *
 * <h2>★ PDC 里为什么用 STRING 而不是 BYTE</h2>
 * 原脚本 13787~13788 行的注释：{@code BYTE} + JS 数字会装箱成 {@code Integer}
 * ⇒ {@code checkcast Byte} 失败 ⇒ <b>静默失败</b>（同 {@code gsCherrySetShootingFlag}）。
 * 所以 {@code gs:al_skill} / {@code gs:al_shooting} 都是 STRING 标记。
 * ★ 反过来 {@code gs:al_ammo} 用 {@code INTEGER}、时间戳用 {@code LONG}。
 *
 * <h2>★ 监听器注册：原脚本会累积，Java 版改为幂等</h2>
 * 原脚本用 {@code pm.registerEvent(...)} 在脚本里<b>自己注册</b> 4 处监听器，
 * {@code GS_AL_REGISTERED} 闩锁是脚本级变量 ⇒ {@code /rsc reload} 后归零、
 * 监听器<b>再注册一遍</b>（真实缺陷：一次左键会被处理多次 ⇒ 一次挥砍打多发的观感）。
 * Java 版写成 {@link Listener} 实现类 + {@code registerEvents}，由实例字段
 * {@link #GS_AL_REGISTERED} <b>幂等注册一次</b>：事件照常收到、优先级照常（{@code HIGHEST}）、
 * 取消照常，但 reload 不再累积。这是<b>修掉一个真实缺陷</b>，不是「顺手优化」。
 *
 * <p>★ <b>{@code ignoreCancelled} 逐处核对</b>：原脚本的 {@code reg(...)} 辅助函数
 * 调 {@code pm.registerEvent(eventClass, plugin, Priority.HIGHEST, fn, plugin)} ——
 * <b>没有第 5 个参数</b> ⇒ {@code ignoreCancelled} 默认 <b>false</b>。
 * Java 的 {@code @EventHandler(priority = ...)} 默认也是 false ⇒ 这里<b>不写</b>
 * {@code ignoreCancelled}（写了 {@code true} 就是行为改变）。
 *
 * <h2>★ 死代码清单（逐条）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 14219~14229 行的 {@code reg} 兜底）：
 *       正路 {@code pm.registerEvent(...)} 失败时才走。GraalJS 25.1.3 里
 *       {@code JavaAdapter} <b>不存在</b>（{@code tools/GraalProbe.java} 实测）
 *       ⇒ 那条路在真 JS 里也是必然抛异常的那条（抛了就被 {@code catch(e2)} 吞掉、
 *       {@code reg} 返回 false）。Java 侧 {@code registerEvents} 一次到位。
 *       <b>删掉不改变可观察行为</b>：只有「正路失败」才会走到，而正路在 Java 里不可能失败。</li>
 *   <li><b>{@code plugin} 四级探测链</b>（原脚本 14205~14210 行）：
 *       RSC 脚本为了拿一个合法 plugin 实例而做的兜底；
 *       原生附属里 {@code GSPlugin.instance()} 就是确定的那个 ⇒ 整链删除。</li>
 *   <li><b>{@code Java.type(...) == null} 的四处早退</b>（{@code InterEv} / {@code DmgEv} /
 *       {@code AnimEv}）：{@code PlayerInteractEvent} / {@code EntityDamageByEntityEvent} /
 *       {@code PlayerAnimationEvent} 在 1.21.11 上必然存在 ⇒ 恒不执行。
 *       <b>注意</b>：{@code reg()} 返回 false 时原脚本<b>不检查</b>（4 处都是
 *       {@code if (X != null) { reg(X, fn); }}），所以「某一条注册失败」
 *       在原脚本里是<b>静默继续</b>；Java 侧 {@code registerEvents} 是「一次全成功 / 全失败」，
 *       这里保留原脚本的「不检查」结构（见「与原脚本的差异」）。</li>
 *   <li><b>{@code gsAlWallHit} 里 {@code res.getHitPosition() == null} 的分支</b>
 *       （原脚本 13990 行）：{@code rayTraceBlocks} 返回非 null 时
 *       {@code getHitPosition()} 在 Paper 上恒非 null ⇒ 该分支实际走不到，但<b>保留</b>
 *       （它是防御式判空，删掉会让「万一为 null」变成 NPE 而改变行为）。</li>
 * </ol>
 *
 * <h2>★ 与 {@link SfSub} 的差异（同一批次的姊妹子系统）</h2>
 * 海水过滤器用 {@code Priority.MONITOR}（只观察、不改事件）；
 * 祖宗发射器用 {@code Priority.HIGHEST}（<b>要</b> {@code setCancelled(true)}）。
 * 两者都<b>不</b>写 {@code ignoreCancelled}。
 */
public final class AlSub {

    // ======================================================================
    // 参数：名字与 JS 顶层 var 同名（常量对账探针按名字反射比对）
    // ======================================================================

    /** {@code GS_AL_ID = "GS_ANCESTOR_LAUNCHER"}（原脚本 13682 行）。 */
    static final String GS_AL_ID = "GS_ANCESTOR_LAUNCHER";

    /** {@code GS_AL_AMMO = 6} —— ★ 爆裂黎明 6 发（名字里 6 个 ▼）。 */
    static final int GS_AL_AMMO = 6;

    /** {@code GS_AL_FIRE_MS = 800} —— ★ 常态射速 0.8 秒/发（第七十八批改；原 1.3 秒）。 */
    static final int GS_AL_FIRE_MS = 800;

    /** {@code GS_AL_SKILL_FIRE_MS = 1500} —— ★ 技能射速 1.5 秒/发（第七十八批改；原 3 秒）。 */
    static final int GS_AL_SKILL_FIRE_MS = 1500;

    /** {@code GS_AL_CD_MS = 12000} —— ★ 技能冷却 12 秒（第七十九批改；原 18 秒）。 */
    static final int GS_AL_CD_MS = 12000;

    /** {@code GS_AL_TOGGLE_MS = 1000} —— ★ 右键切换防抖 1 秒。 */
    static final int GS_AL_TOGGLE_MS = 1000;

    /** {@code GS_AL_RANGE = 24.0} —— ★ 射程 24 格（两种状态<b>相同</b>）。 */
    static final double GS_AL_RANGE = 24.0;

    /** {@code GS_AL_SPEED_BPS = 30.0} —— 弹速 30 格/秒 = 24 格 / 0.8 秒。 */
    static final double GS_AL_SPEED_BPS = 30.0;

    /** {@code GS_AL_STEP = 1.5} —— 每刻前进 1.5 格（20 tick/秒 ⇒ 30 格/秒）。 */
    static final double GS_AL_STEP = 1.5;

    /** {@code GS_AL_HIT_R = 0.8} —— 弹道命中生物的判定半径（格）。 */
    static final double GS_AL_HIT_R = 0.8;

    /** {@code GS_AL_MUZZLE_Y = 1.2} —— 发射高度（脚底往上 1.2 格 = 胸口）。 */
    static final double GS_AL_MUZZLE_Y = 1.2;

    /** {@code GS_AL_N_R = 2.5} —— ★ 常态爆炸半径 2.5 格。 */
    static final double GS_AL_N_R = 2.5;

    /** {@code GS_AL_N_DMG = 35.0} —— ★ 常态爆炸伤害 35 点（第七十九批改；原 55 点）。 */
    static final double GS_AL_N_DMG = 35.0;

    /** {@code GS_AL_S_R = 4.5} —— ★ 技能爆炸半径 4.5 格。 */
    static final double GS_AL_S_R = 4.5;

    /** {@code GS_AL_S_DMG = 175.0} —— ★ 技能爆炸伤害 175 点（第七十九批改；原 155 点）。 */
    static final double GS_AL_S_DMG = 175.0;

    /**
     * {@code GS_AL_NAME = '祖宗发射器'} —— 名字前缀（{@code <...>} 里放弹药）。
     *
     * <p>★ 注意日志行里那个 {@code 祖宗发射器<?>} 是<b>硬编码的字面量</b>
     * （原脚本 14323 行直接写死），<b>不是</b>由本常量拼出来的 ——
     * 逐字保留那个「作者写了占位符没替换」的瑕疵。
     */
    static final String GS_AL_NAME = "祖宗发射器";

    /** {@code GS_AL_DOT = '\u25bc'} —— ▼（U+25BC）；名字里一个 ▼ 代表一发弹药。 */
    static final String GS_AL_DOT = "\u25bc";

    /** {@code GS_AL_SND_FIRE = 'block.dispenser.launch'} —— 常态开火。 */
    static final String GS_AL_SND_FIRE = "block.dispenser.launch";

    /** {@code GS_AL_SND_SFIRE = 'entity.warden.sonic_charge'} —— 技能开火：坚守者蓄力。 */
    static final String GS_AL_SND_SFIRE = "entity.warden.sonic_charge";

    /** {@code GS_AL_SND_BOOM = 'entity.generic.explode'} —— 常态爆炸。 */
    static final String GS_AL_SND_BOOM = "entity.generic.explode";

    /** {@code GS_AL_SND_SBOOM = 'entity.warden.sonic_boom'} —— 技能爆炸：坚守者冲击波。 */
    static final String GS_AL_SND_SBOOM = "entity.warden.sonic_boom";

    /** {@code GS_AL_SND_ON = 'block.beacon.activate'} —— 开技能。 */
    static final String GS_AL_SND_ON = "block.beacon.activate";

    /** {@code GS_AL_SND_OFF = 'block.beacon.deactivate'} —— 关技能。 */
    static final String GS_AL_SND_OFF = "block.beacon.deactivate";

    // ======================================================================
    // 运行期状态（原脚本的顶层 var）
    // ======================================================================

    /** {@code GS_AL_KEYS} —— 六个 PDC 键（懒加载，只建一次）。 */
    Keys gsAlKeys;

    /**
     * {@code GS_AL_REGISTERED} —— 注册闩锁。
     *
     * <p>★ 原脚本里它<b>只写不读</b>（唯一一处读是 {@code if (GS_AL_REGISTERED) return true;}
     * 在 {@link #gsAlRegisterSync()} 开头），reload 后归零 ⇒ 挡不住累积。
     * Java 侧做成<b>实例字段</b>，等价于「只注册一次」。
     */
    boolean GS_AL_REGISTERED = false;

    /** {@code GS_AL_DUST} —— 常态红色粒子的 {@code DustOptions} 缓存（只建一次）。 */
    Particle.DustOptions GS_AL_DUST;

    private final ListenerCore core;

    /** 监听器实例（懒建，{@code registerEvents} 只调一次）。 */
    private Sync listener;

    public AlSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsAlStart();} 调用（原脚本第 14336 行）。 */
    public void start() {
        gsAlStart();
    }

    /** {@code GS_AL_KEYS} 的形状（原脚本 13718 行的对象字面量）。 */
    static final class Keys {
        NamespacedKey ammo;
        NamespacedKey skill;
        NamespacedKey lastShot;
        NamespacedKey toggle;
        NamespacedKey cd;
        NamespacedKey shooting;
    }

    // ======================================================================
    // 基础工具（原脚本 13716~13764 行）
    // ======================================================================

    /**
     * {@code gsAlKeys()} —— 六个 PDC 键，懒加载。
     *
     * <p>★ 原脚本的 {@code try/catch} 语义：建键时任何一步抛异常 ⇒ 返回<b>那个已经
     * 填了一半的对象</b>且<b>不写缓存</b>（下次调用重来）。Java 里
     * {@code NamespacedKey.fromString} 不会抛（字符串都是合法格式），
     * 但保留同样的结构与「失败不缓存」。
     */
    Keys gsAlKeys() {
        if (gsAlKeys != null) {
            return gsAlKeys;
        }
        Keys o = new Keys();
        try {
            o.ammo = NamespacedKey.fromString("gs:al_ammo");          // 技能剩余弹药（0~6）
            o.skill = NamespacedKey.fromString("gs:al_skill");        // 是否处于「爆裂黎明」
            o.lastShot = NamespacedKey.fromString("gs:al_lastshot");  // 上次开火时间戳
            o.toggle = NamespacedKey.fromString("gs:al_toggle");      // 上次右键切换时间戳（防抖用）
            o.cd = NamespacedKey.fromString("gs:al_cd");              // 技能结束的时间戳（12 秒冷却起点）
            o.shooting = NamespacedKey.fromString("gs:al_shooting");  // 弹道伤害放行标记
        } catch (Throwable e) {
            return o;                                                  // 不缓存
        }
        gsAlKeys = o;
        return gsAlKeys;
    }

    /** {@code gsAlPdc(p)} —— 玩家 PDC（拿不到返回 null）。 */
    static PersistentDataContainer gsAlPdc(Player p) {
        try {
            return p.getPersistentDataContainer();
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsAlIsLauncher(item)} —— 手上这件是不是祖宗发射器。 */
    static boolean gsAlIsLauncher(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_AL_ID);
    }

    /**
     * {@code gsAlNow()} —— 毫秒时间戳。
     *
     * <p>★ 原脚本走 {@code Java.type('java.time.Instant').now().toEpochMilli()}，
     * 抛异常时返回 <b>0</b>（不是 {@code Date.now()}）。
     * Java 侧 {@link ListenerCore#now()} 就是同一个值。
     */
    static long gsAlNow() {
        try {
            return java.time.Instant.now().toEpochMilli();
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * {@code gsAlColor(s)} —— {@code &} 色码 → {@code §}。
     *
     * <p>★ 降级路（{@code ('&amp;' + s).replace(/&amp;([0-9a-fk-or])/g, '§$1')}）
     * 在 Java 里<b>不需要</b>：{@code ChatColor.translateAlternateColorCodes} 必然可用
     * （原脚本那次探测是为了绕开类加载失败）。Java 侧直接用它 ——
     * 两者对 {@code &c} 这种输入结果逐字相同。
     */
    static String gsAlColor(String s) {
        try {
            return ChatColor.translateAlternateColorCodes('&', s);
        } catch (Throwable e) {
            return String.valueOf(s).replaceAll("&([0-9a-fk-or])", "\u00a7$1");
        }
    }

    /**
     * {@code gsAlDustOpt()} —— 常态红色粒子的 {@code DustOptions}（只建一次）。
     *
     * <p>★ 颜色 {@code Color.fromRGB(255, 40, 40)}、size <b>1.2</b>，逐字照抄。
     * <p>★ <b>「只建一次」的语义</b>：建失败时 {@code GS_AL_DUST} 保持 null
     * ⇒ 下次调用<b>还会再试</b>（原脚本就是每次重试，没有 {@code TRIED} 闩锁）。
     */
    Particle.DustOptions gsAlDustOpt() {
        if (GS_AL_DUST != null) {
            return GS_AL_DUST;
        }
        try {
            GS_AL_DUST = new Particle.DustOptions(Color.fromRGB(255, 40, 40), 1.2f);
        } catch (Throwable e) {
            GS_AL_DUST = null;
        }
        return GS_AL_DUST;
    }

    // ======================================================================
    // PDC 状态读写（原脚本 13767~13870 行）
    // ======================================================================

    /**
     * {@code gsAlAmmo(p)} —— 技能剩余弹药。
     *
     * <p>★ 三个「读不到就返回满弹」的分支（键为 null / PDC 为 null / 没存过）
     * 逐字保留 —— 它们决定了「第一次拿发射器时名字里是 6 个 ▼ 还是 0 个」。
     */
    int gsAlAmmo(Player p) {
        try {
            Keys k = gsAlKeys();
            if (k.ammo == null) {
                return GS_AL_AMMO;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return GS_AL_AMMO;
            }
            Integer v = pdc.get(k.ammo, PersistentDataType.INTEGER);
            return (v == null) ? GS_AL_AMMO : (v | 0);
        } catch (Throwable e) {
            return GS_AL_AMMO;
        }
    }

    /** {@code gsAlSetAmmo(p, v)} —— 写弹药（{@code v | 0} = 截断成 int）。 */
    void gsAlSetAmmo(Player p, int v) {
        try {
            Keys k = gsAlKeys();
            if (k.ammo == null) {
                return;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc != null) {
                pdc.set(k.ammo, PersistentDataType.INTEGER, v | 0);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsAlIsSkill(p)} —— 是否处于「爆裂黎明」。
     *
     * <p>★★ 用 {@code STRING} 而不是 {@code BYTE}：{@code BYTE} + JS 数字会装箱成
     * {@code Integer} ⇒ {@code checkcast Byte} 失败 ⇒ <b>静默失败</b>
     * （原脚本 13787~13788 行点名了这个坑）。<b>不要</b>改成 BYTE。
     */
    boolean gsAlIsSkill(Player p) {
        try {
            Keys k = gsAlKeys();
            if (k.skill == null) {
                return false;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.skill, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsAlSetSkill(p, on)} —— 开/关技能标记（开写 {@code '1'}，关 {@code remove}）。 */
    void gsAlSetSkill(Player p, boolean on) {
        try {
            Keys k = gsAlKeys();
            if (k.skill == null) {
                return;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return;
            }
            if (on) {
                pdc.set(k.skill, PersistentDataType.STRING, "1");
            } else {
                pdc.remove(k.skill);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsAlSetShootingFlag(p, on)} —— 弹道伤害放行标记（同样用 STRING）。 */
    void gsAlSetShootingFlag(Player p, boolean on) {
        try {
            Keys k = gsAlKeys();
            if (k.shooting == null) {
                return;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return;
            }
            if (on) {
                pdc.set(k.shooting, PersistentDataType.STRING, "1");
            } else {
                pdc.remove(k.shooting);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsAlIsShooting(p)} —— 这一下伤害是不是我们自己打的。 */
    boolean gsAlIsShooting(Player p) {
        try {
            Keys k = gsAlKeys();
            if (k.shooting == null) {
                return false;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.shooting, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsAlAgoOf(p, key)} —— 「距今多少毫秒」，没存过 = <b>999999</b>。
     *
     * <p>★ {@code 999999} 这个哨兵值逐字保留：它比所有节流窗口都大
     * ⇒「没记录过」等价于「很久以前」，第一次开火必然放行。
     */
    long gsAlAgoOf(Player p, NamespacedKey key) {
        try {
            if (key == null) {
                return 999999;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return 999999;
            }
            Long v = pdc.get(key, PersistentDataType.LONG);
            if (v == null) {
                return 999999;
            }
            return gsAlNow() - (0 + v);
        } catch (Throwable e) {
            return 999999;
        }
    }

    /** {@code gsAlLastShotAgo(p)} —— 距上次开火多少毫秒。 */
    long gsAlLastShotAgo(Player p) {
        return gsAlAgoOf(p, gsAlKeys().lastShot);
    }

    /** {@code gsAlToggleAgo(p)} —— 距上次右键切换多少毫秒。 */
    long gsAlToggleAgo(Player p) {
        return gsAlAgoOf(p, gsAlKeys().toggle);
    }

    /** {@code gsAlMark(p, key)} —— 记下「刚用过」。 */
    void gsAlMark(Player p, NamespacedKey key) {
        try {
            if (key == null) {
                return;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc != null) {
                pdc.set(key, PersistentDataType.LONG, gsAlNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsAlMarkShot(p)}。 */
    void gsAlMarkShot(Player p) {
        gsAlMark(p, gsAlKeys().lastShot);
    }

    /** {@code gsAlMarkToggle(p)}。 */
    void gsAlMarkToggle(Player p) {
        gsAlMark(p, gsAlKeys().toggle);
    }

    /** {@code gsAlMarkCd(p)} —— ★ 冷却从这里开始算。 */
    void gsAlMarkCd(Player p) {
        gsAlMark(p, gsAlKeys().cd);
    }

    /** {@code gsAlCdLeft(p)} —— 技能冷却剩余毫秒；0 = 可用。 */
    int gsAlCdLeft(Player p) {
        try {
            Keys k = gsAlKeys();
            if (k.cd == null) {
                return 0;
            }
            PersistentDataContainer pdc = gsAlPdc(p);
            if (pdc == null) {
                return 0;
            }
            Long at = pdc.get(k.cd, PersistentDataType.LONG);
            if (at == null) {
                return 0;
            }
            long left = GS_AL_CD_MS - (gsAlNow() - (0 + at));
            return left > 0 ? (int) left : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ======================================================================
    // 名字（原脚本 13872~13908 行）
    // ======================================================================

    /**
     * {@code gsAlNameFor(p)} —— 当前该显示的名字。
     *
     * <pre>
     *   常态 = &amp;c祖宗发射器&lt;?&gt;            （无限弹药，用户「不改变?」）
     *   技能 = &amp;c祖宗发射器&lt;▼▼▼▼▼▼&gt;      （6 个 ▼，打一发少一个）
     * </pre>
     *
     * <p>★ {@code <?>} 那个 <b>{@code ?}</b> 是作者<b>故意</b>留的占位符
     * （原脚本 13701 行注释：「状态看名字就够了：常态 {@code <?>}、技能 {@code <▼▼▼▼▼▼>}」）。
     * <b>不要</b>「修正」成物品名或数字。
     * <p>★ ▼ 的个数 = {@code gsAlAmmo(p)}，为 0 时就是 {@code <>}（空尖括号）。
     */
    String gsAlNameFor(Player p) {
        if (gsAlIsSkill(p)) {
            int a = gsAlAmmo(p);
            StringBuilder dots = new StringBuilder();
            for (int i = 0; i < a; i++) {
                dots.append(GS_AL_DOT);
            }
            return "&c" + GS_AL_NAME + "<" + dots + ">";
        }
        return "&c" + GS_AL_NAME + "<?>";
    }

    /**
     * {@code gsAlUpdateName(p)} —— 把主手那件的显示名刷成当前状态。
     *
     * <p>★★ <b>「名字没变就一个字节都不写」</b>（原脚本 13894~13902 行）：
     * 心跳每 10 刻会调到这里，而每次 {@code setItemInMainHand} 都会让客户端
     * 重新同步那一格（白白刷包、还可能打断客户端的物品动画）⇒ <b>必须先比对再写</b>。
     * <p>★ 比对用的是 {@code ('' + cur) === ('' + want)} —— 即<b>字符串比较</b>，
     * 不是「对象相同」。Java 侧 {@code String.valueOf(...).equals(...)} 逐字等价。
     * <p>★ {@code cur == null}（没有显示名）时<b>不</b>提前返回，照样写。
     */
    void gsAlUpdateName(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = null;
            try {
                hand = inv.getItemInMainHand();
            } catch (Throwable e) {
                hand = null;
            }
            if (hand == null || !gsAlIsLauncher(hand)) {
                return;
            }

            String want = gsAlColor(gsAlNameFor(p));

            ItemMeta meta = null;
            try {
                meta = hand.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            if (meta == null) {
                return;
            }
            String cur = null;
            try {
                cur = meta.getDisplayName();
            } catch (Throwable e) {
                cur = null;
            }
            if (cur != null && String.valueOf(cur).equals(String.valueOf(want))) {
                return;                            // ★ 名字没变 ⇒ 一个字节都不写
            }

            try {
                meta.setDisplayName(want);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            hand.setItemMeta(meta);
            try {
                inv.setItemInMainHand(hand);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 技能开关（原脚本 13910~13946 行）
    // ======================================================================

    /**
     * {@code gsAlToggle(p)} —— 右键：开 / 关「爆裂黎明」。
     *
     * <p>★ 四个逐字保留的细节：
     * <ol>
     *   <li><b>先防抖、先打点</b>：{@code gsAlToggleAgo(p) < GS_AL_TOGGLE_MS} ⇒ 直接 return
     *       （<b>连时间戳都不更新</b>）；放行则<b>立刻</b> {@code gsAlMarkToggle(p)}。
     *       注意打点在「判断是否已在技能中」<b>之前</b> —— 所以「关」这一下也会刷新防抖。</li>
     *   <li>已经在技能里 ⇒ 直接 {@code gsAlExitSkill(p, false)}（<b>放</b>关闭音效）。</li>
     *   <li>进技能前查冷却，{@code cd > 0} ⇒ <b>静默失败</b>（第七十七批：不发消息）。</li>
     *   <li>开启时<b>补满 6 发</b> + 刷名字 + 放开启音效（{@code block.beacon.activate}）。</li>
     * </ol>
     */
    void gsAlToggle(Player p) {
        try {
            if (p == null) {
                return;
            }

            // ★ 防抖 1 秒：一下右键被服务器识别成两次时不至于"刚开就关"
            if (gsAlToggleAgo(p) < GS_AL_TOGGLE_MS) {
                return;
            }
            gsAlMarkToggle(p);

            if (gsAlIsSkill(p)) {
                gsAlExitSkill(p, false);
                return;
            }

            // 进技能前查冷却
            int cd = gsAlCdLeft(p);
            if (cd > 0) {
                return;                            // ★ 冷却中静默失败（不发消息）
            }

            gsAlSetSkill(p, true);
            gsAlSetAmmo(p, GS_AL_AMMO);            // ★ 每次开启都补满 6 发
            gsAlUpdateName(p);
            try {
                p.getWorld().playSound(p.getLocation(), GS_AL_SND_ON, 1.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 祖宗发射器切换出错: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsAlExitSkill(p, silent)} —— 退出技能 ⇒ 回常态 + <b>开始 12 秒冷却</b>。
     *
     * <p>★ {@code gsAlMarkCd(p)} 是<b>冷却的唯一起点</b>：所以「6 发打空自动退出」与
     * 「右键手动关」<b>都会</b>触发冷却，而且是从<b>退出那一刻</b>开始算。
     * <p>★ 弹药回满 6 发（常态虽然不显示，但下次开技能会再补一次 —— 两处都保留）。
     * <p>★ {@code silent} <b>只决定放不放关闭音效</b>，不再决定发不发消息。
     */
    void gsAlExitSkill(Player p, boolean silent) {
        try {
            gsAlSetSkill(p, false);
            gsAlSetAmmo(p, GS_AL_AMMO);
            gsAlMarkCd(p);                         // ★ 冷却从这里开始算
            gsAlUpdateName(p);
            // ★ 不再发「已关闭」消息；silent 现在只决定**放不放关闭音效**
            if (!silent) {
                try {
                    p.getWorld().playSound(p.getLocation(), GS_AL_SND_OFF, 1.0f, 1.0f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 开火（原脚本 13948~13978 行）
    // ======================================================================

    /**
     * {@code gsAlFire(p)} —— 左键：开火。
     *
     * <p>★★ <b>技能分支</b>（逐字保留的六步）：
     * <ol>
     *   <li>{@code gsAlLastShotAgo(p) < GS_AL_SKILL_FIRE_MS} ⇒ 节流返回
     *       （1.5 秒/发）。</li>
     *   <li>{@code a = gsAlAmmo(p)}；{@code a <= 0} ⇒ <b>先退出技能</b>再 return
     *       （放关闭音效）—— 这是「弹药为 0 却还在技能态」的兜底。</li>
     *   <li>扣 1 发 + 打点 + 出膛（{@code skill = true}）+ 刷名字。</li>
     *   <li>再读一次弹药，{@code <= 0} ⇒ {@code gsAlExitSkill(p, true)}
     *       （★ <b>静默</b>退出：不放关闭音效、不发消息）并开始 12 秒冷却。</li>
     * </ol>
     * <p>★★ <b>常态分支</b>：{@code gsAlLastShotAgo(p) < GS_AL_FIRE_MS} ⇒ 节流
     * （0.8 秒/发）；<b>无限弹药</b> —— 不扣、不查、不退出，只打点 + 出膛。
     */
    void gsAlFire(Player p) {
        try {
            if (p == null) {
                return;
            }

            if (gsAlIsSkill(p)) {
                // ★ 技能：1.5 秒一发
                if (gsAlLastShotAgo(p) < GS_AL_SKILL_FIRE_MS) {
                    return;
                }
                int a = gsAlAmmo(p);
                if (a <= 0) {
                    gsAlExitSkill(p, false);
                    return;
                }

                gsAlSetAmmo(p, a - 1);             // ★ 打一发少一个 ▼
                gsAlMarkShot(p);
                gsAlLaunch(p, true);
                gsAlUpdateName(p);

                // ★ 6 发打完 ⇒ **自动退出技能，回常态**（用户已确认），并开始 12 秒冷却
                if (gsAlAmmo(p) <= 0) {
                    gsAlExitSkill(p, true);        // ★ 静默退出（不放关闭音效）
                }
                return;
            }

            // ---- 常态：0.8 秒一发，**无限弹药** ----
            if (gsAlLastShotAgo(p) < GS_AL_FIRE_MS) {
                return;
            }
            gsAlMarkShot(p);
            gsAlLaunch(p, false);
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 祖宗发射器开火出错: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 弹道（原脚本 13980~14104 行）
    // ======================================================================

    /**
     * {@code gsAlWallHit(world, from, dir, maxLen)} —— 这一段路上有没有方块挡着。
     *
     * <p>★★ 用 {@code rayTraceBlocks} 做<b>扫掠求交</b>，不是「只看终点那一个方块」：
     * 只看终点的话，1.5 格/刻的弹道会<b>穿过薄墙</b>（墙厚 1 格、弹道一步跨 1.5 格）。
     * <p>★ {@code FluidCollisionMode.NEVER} + {@code ignorePassableBlocks = true}：
     * 水和草不算墙。这两个参数逐字保留。
     * <p>★ 返回的是<b>命中点</b>的 {@code Location}（不是方块坐标）——
     * 所以爆炸就炸在墙上那一点，而不是墙的中心。
     */
    static Location gsAlWallHit(World world, Location from, Vector dir, double maxLen) {
        try {
            RayTraceResult res = world.rayTraceBlocks(from, dir, maxLen, FluidCollisionMode.NEVER, true);
            if (res == null) {
                return null;
            }
            Vector hp = res.getHitPosition();
            if (hp == null) {
                return null;
            }
            return new Location(world, hp.getX(), hp.getY(), hp.getZ());
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsAlLaunch(p, skill)} —— 打出一发弹道（{@code runLater} 自链，真的会飞）。
     *
     * <p>★★ <b>每刻前进 {@link #GS_AL_STEP}(1.5) 格 ⇒ 24 格正好 16 刻 = 0.8 秒</b>。
     * 撞到生物 / 撞墙 / 飞到射程尽头 ⇒ 都在<b>同一处</b>引爆（三种条件都炸）。
     *
     * <p>★ 逐字保留的细节：
     * <ol>
     *   <li><b>方向 = 视线</b>（{@code eye.getDirection()}），<b>起点 = 胸口</b>
     *       （{@code base.getY() + GS_AL_MUZZLE_Y}）—— 从眼睛发射会挡住视线（同残樱）。</li>
     *   <li>开火音效从<b>胸口</b>响（不是眼睛）。</li>
     *   <li>循环<b>第一拍就是 {@code runLater(step, 1)}</b> ⇒ 实际第一次推进在 1 刻后，
     *       起点那一拍<b>不</b>做任何判定（原脚本 14061 行）。</li>
     *   <li>每一拍算的是「<b>上一刻的位置 → 这一刻的位置</b>」这一段：
     *       {@code prev} 用<b>推进前</b>的 {@code dist}，{@code pt} 用<b>推进后</b>的。
     *       ★ 所以墙的扫掠长度恒为 {@code stepLen}(1.5)，与 {@code maxLen} 参数一致。</li>
     *   <li><b>顺序</b>：① 墙（有 ⇒ 在<b>墙的命中点</b>拖尾 + 引爆，return）
     *       ② 在 {@code pt} 拖尾 ③ 生物（有 ⇒ 在 {@code pt} 引爆）
     *       ④ {@code dist >= GS_AL_RANGE} ⇒ 在 {@code pt} 引爆。</li>
     *   <li>{@code selfId} 在发射时就取好，整条弹道共用（不每拍重取）。</li>
     * </ol>
     */
    void gsAlLaunch(Player p, boolean skill) {
        try {
            World world = p.getWorld();
            Location eye;
            try {
                eye = p.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Vector dir = eye.getDirection();               // 方向 = 视线（准星）

            // ★ 发射起点 = 「胸口」（从眼睛发射会挡住视线，同残樱）
            Location base;
            try {
                base = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            final double sx = base.getX();
            final double sy = base.getY() + GS_AL_MUZZLE_Y;
            final double sz = base.getZ();

            final String selfId = String.valueOf(p.getUniqueId());
            final World fworld = world;
            final Vector fdir = dir;

            // 开火音效（从胸口响）
            try {
                world.playSound(new Location(world, sx, sy, sz),
                        skill ? GS_AL_SND_SFIRE : GS_AL_SND_FIRE, 1.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            Runnable step = new Runnable() {
                /** ★ 用数组做可变闭包（原脚本的 {@code var dist} 是同一份）。 */
                final double[] dist = new double[]{0.0};

                @Override
                public void run() {
                    try {
                        double stepLen = GS_AL_STEP;
                        // ★ 上一刻的位置 = 这一段扫掠的起点
                        Location prev = new Location(fworld,
                                sx + fdir.getX() * dist[0],
                                sy + fdir.getY() * dist[0],
                                sz + fdir.getZ() * dist[0]);
                        dist[0] += stepLen;
                        double px = sx + fdir.getX() * dist[0];
                        double py = sy + fdir.getY() * dist[0];
                        double pz = sz + fdir.getZ() * dist[0];
                        Location pt = new Location(fworld, px, py, pz);

                        // ① 这一段路上有没有方块挡着（★ 扫掠求交，不是只看终点那一个方块）
                        //    ★★ 先判方块、后判生物：否则站在薄墙后面的怪会被**隔墙打中**。
                        Location wall = gsAlWallHit(fworld, prev, fdir, stepLen);
                        if (wall != null) {
                            gsAlTrail(fworld, wall, skill);
                            gsAlBoom(fworld, p, wall, skill);
                            return;
                        }

                        // 拖尾：常态红色粒子 / 技能坚守者冲击波
                        gsAlTrail(fworld, pt, skill);

                        // ② 命中生物 ⇒ 引爆
                        Entity victim = gsAlFindLiving(fworld, pt, GS_AL_HIT_R, selfId);
                        if (victim != null) {
                            gsAlBoom(fworld, p, pt, skill);
                            return;
                        }

                        // ③ 飞到射程尽头 ⇒ 引爆
                        if (dist[0] >= GS_AL_RANGE) {
                            gsAlBoom(fworld, p, pt, skill);
                            return;
                        }

                        GS.runLater(this, 1);          // 继续飞
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            };

            GS.runLater(step, 1);
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 祖宗发射器弹道出错: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsAlTrail(world, pt, skill)} —— 拖尾特效。
     *
     * <p>★ 技能：{@code SONIC_BOOM} <b>1 个</b>（坚守者冲击波）。
     * ★ 常态：{@code DUST} <b>3 个</b>，偏移 {@code 0.06}、extra {@code 0.0}、带 {@code DustOptions}。
     * <p>★ {@code so == null}（DustOptions 建不出来）⇒ <b>什么都不画</b> ——
     * 原脚本这里<b>没有</b>降级到「不带 data 的粒子」（对比 {@code gsMsDust} 有降级）。
     * 逐字保留：不给 DUST 传 data 会抛异常，画不出东西，效果一样但路径不同。
     */
    void gsAlTrail(World world, Location pt, boolean skill) {
        try {
            if (skill) {
                // ★ 坚守者冲击波（弹体本身**没有伤害**，伤害全在引爆那一下）
                try {
                    world.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            } else {
                // ★ 常态：红色粒子
                Particle.DustOptions so = gsAlDustOpt();
                if (so != null) {
                    try {
                        world.spawnParticle(Particle.DUST, pt, 3, 0.06, 0.06, 0.06, 0.0, so);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsAlFindLiving(world, pt, radius, selfId)} —— 找一个「活着的生物」。
     *
     * <p>★★ 五道过滤，<b>顺序</b>逐字保留（顺序决定「先撞到谁」）：
     * <ol>
     *   <li>排除<b>自己</b>（uuid 相等）。</li>
     *   <li>{@code ent.getHealth()} 抛异常 ⇒ 跳过 —— ★ 这是<b>天然的类型过滤</b>：
     *       掉落物 / 箭矢 / 展示实体没有这个方法。原脚本注释点名了这个技巧。</li>
     *   <li>{@code isDead()} ⇒ 跳过。</li>
     *   <li>类型名是 {@code ARMOR_STAND} / {@code DROPPED_ITEM} / {@code ITEM} ⇒ 跳过。
     *       ★ <b>必须有这一条</b>：盔甲架在 Bukkit 里<b>是</b> {@code LivingEntity}、
     *       {@code getHealth()} 拿得到 ⇒ 光靠第 2 条拦不住它。</li>
     *   <li>第一个通过的就返回（{@code getNearbyEntities} 的迭代顺序 = 原样）。</li>
     * </ol>
     * <p>★ 用的是<b>立方体</b>范围（{@code getNearbyEntities} 本身就是立方体），
     * 这里<b>不</b>再做球形过滤 —— 与 {@link #gsAlBoom} 不同（那边有球过滤）。
     * 逐字保留这个不对称。
     * <p>★ {@code DROPPED_ITEM} 在 1.21.11 已改名为 {@code ITEM} ⇒ 那一个分支
     * 实际取不到（见类注释「死代码」），但<b>照抄保留</b>。
     */
    static Entity gsAlFindLiving(World world, Location pt, double radius, String selfId) {
        try {
            Collection<Entity> list = world.getNearbyEntities(pt, radius, radius, radius);
            for (Entity ent : list) {
                String eid;
                try {
                    eid = String.valueOf(ent.getUniqueId());
                } catch (Throwable e1) {
                    continue;
                }
                if (eid.equals(selfId)) {
                    continue;
                }
                // ★ 原脚本用 `ent.getHealth()` 做「是不是生物」的探针：非生物会抛异常
                //   ⇒ 被 continue 掉，这是**天然的类型过滤**（掉落物 / 箭矢 / 展示实体
                //   都没有 getHealth）。Java 侧 getHealth() 声明在 LivingEntity 上
                //   ⇒ 那个 try/catch 的静态化就是 instanceof LivingEntity，语义逐字等价。
                if (!(ent instanceof LivingEntity)) {
                    continue;
                }
                try {
                    if (ent.isDead()) {
                        continue;
                    }
                } catch (Throwable e3) {
                    // 与原脚本一致：静默
                }
                try {
                    String tn = String.valueOf(ent.getType().name());
                    if (tn.equals("ARMOR_STAND") || tn.equals("DROPPED_ITEM") || tn.equals("ITEM")) {
                        continue;
                    }
                } catch (Throwable e4) {
                    // 与原脚本一致：静默
                }
                return ent;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsAlBoom(world, shooter, pt, skill)} —— 引爆（两种状态共用）。
     *
     * <p>★★ <b>视觉不破坏地形</b>：不用 {@code createExplosion}，只画
     * {@code EXPLOSION_EMITTER}(1) + {@code EXPLOSION}(12, 偏移 {@code r*0.5})，
     * 技能再加一个 {@code SONIC_BOOM}。
     *
     * <p>★★ <b>伤害结算</b>：{@code getNearbyEntities(pt, r, r, r)} 是<b>立方体</b>
     * ⇒ 必须再按<b>真实球距离</b>过滤一次（{@code ent.getLocation().distance(pt) > r} 跳过）。
     * ★ 这条球过滤是「半径 2.5 / 4.5」能名副其实的关键，别省。
     *
     * <p>★ 过滤顺序：自己 → {@code getHealth()} 探针 → {@code isDead()} →
     * 类型名（盔甲架 / 掉落物）→ <b>球距离</b>。与 {@link #gsAlFindLiving}
     * 保持<b>同一套</b>类型过滤（原脚本 14137~14139 行专门注释了这件事）。
     */
    void gsAlBoom(World world, Player shooter, Location pt, boolean skill) {
        try {
            double r = skill ? GS_AL_S_R : GS_AL_N_R;
            double dmg = skill ? GS_AL_S_DMG : GS_AL_N_DMG;
            String selfId;
            try {
                selfId = String.valueOf(shooter.getUniqueId());
            } catch (Throwable e) {
                selfId = "";
            }

            // ---- 视觉：原版爆炸粒子（**不用 createExplosion** ⇒ 不破坏地形）----
            try {
                try {
                    world.spawnParticle(Particle.EXPLOSION_EMITTER, pt, 1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    world.spawnParticle(Particle.EXPLOSION, pt, 12, r * 0.5, r * 0.5, r * 0.5, 0.0);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                if (skill) {
                    try {
                        world.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0.0, 0.0, 0.0, 0.0);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ---- 音效 ----
            try {
                world.playSound(pt, skill ? GS_AL_SND_SBOOM : GS_AL_SND_BOOM, 1.4f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // ---- 伤害：球形范围，**排除自己**，**只打生物** ----
            Collection<Entity> list = world.getNearbyEntities(pt, r, r, r);
            for (Entity ent : list) {
                String eid;
                try {
                    eid = String.valueOf(ent.getUniqueId());
                } catch (Throwable e1) {
                    continue;
                }
                if (eid.equals(selfId)) {
                    continue;                          // ★ 不包括自己
                }
                // ★ 只打生物 —— 同样是 `getHealth()` 探针的静态化（见 gsAlFindLiving）。
                if (!(ent instanceof LivingEntity)) {
                    continue;
                }
                try {
                    if (ent.isDead()) {
                        continue;
                    }
                } catch (Throwable e3) {
                    // 与原脚本一致：静默
                }
                // ★ 与 gsAlFindLiving 保持**同一套**过滤：盔甲架 / 掉落物不算「生物」。
                //   （★ 盔甲架在 Bukkit 里**是** LivingEntity、getHealth() 拿得到，
                //     所以光靠上面那句 getHealth 是拦不住它的，必须按类型名再筛一次。）
                try {
                    String tn2 = String.valueOf(ent.getType().name());
                    if (tn2.equals("ARMOR_STAND") || tn2.equals("DROPPED_ITEM") || tn2.equals("ITEM")) {
                        continue;
                    }
                } catch (Throwable e4) {
                    // 与原脚本一致：静默
                }
                // ★ getNearbyEntities 给的是**立方体** ⇒ 再按真实**球**距离过滤一次
                try {
                    if (ent.getLocation().distance(pt) > r) {
                        continue;
                    }
                } catch (Throwable e5) {
                    // 与原脚本一致：静默
                }
                gsAlHitDamage(shooter, ent, dmg);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsAlHitDamage(shooter, victim, dmg)} —— 结算伤害。
     *
     * <p>★★ 四个逐字保留的细节：
     * <ol>
     *   <li>三道前置检查（null / {@code isDead()} / {@code getHealth()} 探针）——
     *       {@code isDead()} 与 {@code getHealth()} 抛异常时<b>直接 return</b>（不是 continue，
     *       这里只有一个目标）。</li>
     *   <li>{@code setNoDamageTicks(0)} —— <b>清受击无敌帧</b>，让伤害立即生效（同残樱）。
     *       ★ 注意这里<b>没有</b> {@code gsMsHurt} 那套「打前打后各清一次 + 保存速度」
     *       （原脚本这里就是简单一句）。</li>
     *   <li>{@code gsAlSetShootingFlag(shooter, true)} → {@code damage(dmg, shooter)}
     *       → {@code gsAlSetShootingFlag(shooter, false)}：靠 PDC 标记让
     *       {@code EntityDamageByEntityEvent} 监听器<b>放行</b>自家伤害。
     *       ★ 这里<b>没有</b> {@code try/finally}（对比 {@code gsMsHurt} 的
     *       {@code GS_WPN_DMG_GUARD++/--}）—— 如果 {@code damage} 抛异常，
     *       标记会<b>留在 PDC 里</b>（下一发真实近战会被放行）。逐字保留这个瑕疵。</li>
     *   <li>{@code damage(dmg, shooter)} 抛异常 ⇒ 降级 {@code damage(dmg)}（不带来源）。</li>
     * </ol>
     */
    void gsAlHitDamage(Player shooter, Entity victim, double dmg) {
        try {
            if (victim == null) {
                return;
            }
            try {
                if (victim.isDead()) {
                    return;
                }
            } catch (Throwable e) {
                return;
            }
            // ★ 原脚本第三道检查是 `victim.getHealth()`（不是生物就 return）
            //   ⇒ 静态化为 instanceof LivingEntity（同 gsAlFindLiving）。
            if (!(victim instanceof LivingEntity)) {
                return;
            }

            try {
                if (victim instanceof LivingEntity) {
                    ((LivingEntity) victim).setNoDamageTicks(0);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            gsAlSetShootingFlag(shooter, true);
            try {
                // ★ 原脚本是 JS 动态派发：不是生物时 victim.damage(...) 直接抛
                //   ⇒ 被下面的 catch 吞掉、什么都不发生。Java 里用 instanceof
                //   把这个「运行期类型检查」静态化，语义逐字等价。
                if (victim instanceof LivingEntity) {
                    ((LivingEntity) victim).damage(dmg, shooter);
                }
            } catch (Throwable e1) {
                try {
                    if (victim instanceof LivingEntity) {
                        ((LivingEntity) victim).damage(dmg);
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            gsAlSetShootingFlag(shooter, false);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 心跳（原脚本 14170~14196 行）
    // ======================================================================

    /**
     * {@code gsAlPulse()} —— 每 10 刻一次。
     *
     * <p>★ 只做一件事：拿着发射器时把名字刷成当前状态（丢过 / 捡回后也能自愈）。
     * <p>★★ <b>换手持不再关闭技能</b>（用户「换手持不会把技能关闭」）：
     * {@code if (!holding) continue;} —— 切走了<b>什么都不做</b>，
     * 技能 / 弹药 / 冷却全部原样保留，切回来接着用。
     * <p>★ 第一行是 {@code if (!gsAuraIsCurrent()) return;} ——
     * 借<b>梦境套光环</b>的世界世代号做「reload 后旧任务自终止」。
     * 这是跨子系统的共享机制（在 {@link ListenerCore} 里），
     * 语义：不是当前那一份 ⇒ 整轮心跳直接停手。
     */
    public void gsAlPulse() {
        try {
            if (!core.gsAuraIsCurrent()) {
                return;
            }

            Collection<? extends Player> players = Bukkit.getServer().getOnlinePlayers();
            for (Player p : players) {
                try {
                    ItemStack hand = null;
                    try {
                        hand = p.getInventory().getItemInMainHand();
                    } catch (Throwable e) {
                        hand = null;
                    }
                    boolean holding = gsAlIsLauncher(hand);

                    // ★★★ 切走了**什么都不做** ——
                    //   技能 / 弹药 / 冷却全部原样保留，切回来接着用。
                    if (!holding) {
                        continue;
                    }

                    gsAlUpdateName(p);
                } catch (Throwable e) {
                    // 与原脚本一致：静默（单个玩家出错不影响其他人）
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 监听器（原脚本 14198~14317 行）
    // ======================================================================

    /**
     * 原脚本 4 处 {@code registerEvent} 的 Java 版。
     *
     * <p>★ 全部 {@code Priority.HIGHEST}、{@code ignoreCancelled} 保持默认 <b>false</b>。
     * <p>★ {@code reg()} 在原脚本里对 4 条监听器<b>逐个</b>调用、
     * 且<b>不检查</b>返回值 ⇒ Java 侧用一个 {@code Listener} 一次性注册
     * （差异见类注释「与原脚本的差异」）。
     */
    final class Sync implements Listener {

        /**
         * ① 左键开火 / 右键切换技能（{@code PlayerInteractEvent}）。
         *
         * <p>★ 右键<b>必须</b>取消：唱片右键可以插进唱片机。
         * ★ 左键也取消（避免同时挖方块）。
         * <p>★ 判定用 {@code act.indexOf('RIGHT_CLICK') >= 0} / {@code indexOf('LEFT_CLICK') >= 0}
         * —— 用 <b>indexOf 而不是相等</b>，所以 {@code RIGHT_CLICK_BLOCK} /
         * {@code LEFT_CLICK_AIR} 等所有子动作都命中。Java 侧逐字用 {@code contains}。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                ItemStack hand = null;
                try {
                    hand = p.getInventory().getItemInMainHand();
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!gsAlIsLauncher(hand)) {
                    return;
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (act.contains("RIGHT_CLICK")) {
                    e.setCancelled(true);
                    gsAlToggle(p);                     // ★ 右键 = 爆裂黎明 开 / 关
                } else if (act.contains("LEFT_CLICK")) {
                    e.setCancelled(true);
                    gsAlFire(p);                       // ★ 左键 = 开火
                }
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② {@code EntityDamageByEntityEvent}：爆炸伤害放行 + <b>原版近战取消</b>。
         *
         * <p>（准星怼在怪身上时客户端发的是攻击包，{@code PlayerInteractEvent} 收不到
         * ⇒ 不开火、反而结算原版近战。）
         * <p>★ 放行判据是 {@code gsAlIsShooting(p)}（PDC 标记），
         * 所以<b>只有我们自己结算的那一下</b>被放行，玩家自己的近战仍会被取消。
         * <p>★ {@code cause} 必须含 {@code ENTITY_ATTACK} 或 {@code SWEEP}
         * （否则是别的来源，比如摔落 / 爆炸，不该拦）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamageByEntity(EntityDamageByEntityEvent e) {
            try {
                Entity damager = e.getDamager();
                if (damager == null) {
                    return;
                }
                String en = "";
                try {
                    en = String.valueOf(damager.getType().name());
                } catch (Throwable e2) {
                    return;
                }
                if (!en.equals("PLAYER")) {
                    return;
                }
                Player p = (Player) damager;
                if (gsAlIsShooting(p)) {
                    return;                            // 爆炸伤害 → 放行
                }
                String cause = "";
                try {
                    cause = String.valueOf(e.getCause().name());
                } catch (Throwable e2) {
                    return;
                }
                if (!cause.contains("ENTITY_ATTACK") && !cause.contains("SWEEP")) {
                    return;
                }
                ItemStack hand = null;
                try {
                    hand = p.getInventory().getItemInMainHand();
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!gsAlIsLauncher(hand)) {
                    return;                            // 没拿发射器 → 原版近战照常
                }
                e.setCancelled(true);                  // ★ 原版近战整个取消
                gsAlFire(p);                           // ★ 兜底：这一下改成开火
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ②-b 左键的<b>真正兜底</b> —— 挥手动画（同残樱 / 五骊矟 / 阎魔刀）。
         *
         * <p>★★ <b>只认 {@code ARM_SWING}</b>：{@code OFF_ARM_SWING} 是<b>副手</b>挥，
         * 不能当开火。Java 侧用<b>相等</b>判定（{@code type !== 'ARM_SWING'}）。
         */
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
                    return;
                }
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                ItemStack hand = null;
                try {
                    hand = p.getInventory().getItemInMainHand();
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!gsAlIsLauncher(hand)) {
                    return;                            // 没拿发射器 → 不理会
                }
                gsAlFire(p);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * {@code gsAlRegisterSync()} —— 幂等注册（原脚本 14199~14317 行）。
     *
     * <p>★ 顺序逐字保留：注册 3 个事件 → 置闩锁 → <b>在注册函数内部</b>打「监听器注册成功」
     * 那一行日志（原脚本 14306~14310 行就在 {@code gsAlRegisterSync} 里，
     * 不在 {@code gsAlStart} 里 —— 所以「注册失败」时这一行<b>不会</b>出现）。
     * <p>★ 日志里的三个数字都是 {@code (X / 1000.0).toFixed(n)}：
     * 800/1000 → {@code "0.8"}、1500/1000 → {@code "1.5"}、12000/1000 → {@code "12"}。
     * 用 {@link JsNum#toFixed(double, int)} 而不是 {@code String.format}（见移植契约第 7 条）。
     */
    boolean gsAlRegisterSync() {
        if (GS_AL_REGISTERED) {
            return true;                               // ★ Java 版幂等闩锁（原脚本只写不读）
        }
        try {
            if (listener == null) {
                listener = new Sync();
            }
            Bukkit.getPluginManager().registerEvents(listener, GSPlugin.instance());

            GS_AL_REGISTERED = true;
            try {
                Bukkit.getServer().getLogger().info(gsAlRegisterLine());
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 祖宗发射器同步监听器注册异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 14319~14336 行）
    // ======================================================================

    /**
     * {@code gsAlStart()} —— 排心跳 + 注册监听器 + 打启用日志（原脚本 14319~14334 行）。
     *
     * <p>★★ <b>{@code runRepeating(fn, 10, 10)}</b>：delay = 10、period = 10，
     * 两个参数<b>都是 10</b>（逐处核对过，不是抄成一个 10）。
     * <p>★ 顺序：<b>先排心跳、再注册监听器、最后打启用日志</b>。
     * 启用日志用 {@code gsDaLog}（会补 {@code [构思技艺] } 前缀），
     * 与注册日志（自己带前缀 + {@code getLogger().info}）的写法不同 —— 逐字照抄。
     * <p>★ 日志里那个 <b>{@code 祖宗发射器<?>}</b> 是<b>硬编码字面量</b>
     * （原脚本 14323 行），<b>不是</b> {@code GS_AL_NAME} 拼 {@code <?>}。
     * 逐字保留那个「占位符没替换」的瑕疵。
     */
    public void gsAlStart() {
        try {
            GS.runRepeating(this::gsAlPulse, 10, 10);
            gsAlRegisterSync();
            ListenerCore.gsDaLog(gsAlStartLine());
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 祖宗发射器启动失败: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 原脚本 14306~14310 行的注册日志（<b>含</b> {@code [构思技艺] } 前缀）。
     *
     * <p>对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 47 行）：
     * <pre>
     * [构思技艺] 祖宗发射器监听器注册成功（左键=开火 · 右键=爆裂黎明 开/关 · 常态 0.8 秒/发 · 技能 1.5 秒/发 · 6 发 · 冷却 12 秒）
     * </pre>
     */
    static String gsAlRegisterLine() {
        return "[构思技艺] 祖宗发射器监听器注册成功"
                + "（左键=开火 · 右键=爆裂黎明 开/关 · 常态 "
                + JsNum.toFixed(GS_AL_FIRE_MS / 1000.0, 1) + " 秒/发 · 技能 "
                + JsNum.toFixed(GS_AL_SKILL_FIRE_MS / 1000.0, 1) + " 秒/发 · "
                + GS_AL_AMMO + " 发 · 冷却 " + JsNum.toFixed(GS_AL_CD_MS / 1000.0, 0) + " 秒）";
    }

    /**
     * 原脚本 14323~14330 行的启用日志（<b>不含</b>前缀 —— 走 {@code gsDaLog} 补）。
     *
     * <p>对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 48 行，<b>完整未截断</b>）：
     * <pre>
     * [构思技艺] 祖宗发射器&lt;?&gt;：已启用（常态 0.8 秒/发 · **无限弹药** · 红色粒子 · 爆炸 2.5 格 / 35 点；
     * 右键「爆裂黎明」⇒ 6 发 ▼ · 1.5 秒/发 · 坚守者冲击波 · 爆炸 4.5 格 / 175 点 · 冷却 12 秒 · 防抖 1 秒；
     * 射程 24 格 · 弹速 30 格/秒（0.8 秒 24 格）· 不穿墙 · 不破坏地形）
     * </pre>
     * ★★ 三处「数字 → 字符串」全部用 {@link JsNum}：
     * {@code 2.5} / {@code 35.0} / {@code 4.5} / {@code 175.0} / {@code 24.0} / {@code 30.0}
     * 走 {@link JsNum#str(double)}（JS 的 {@code '' + x} ⇒ 整数不带 {@code .0}），
     * 而 {@code 0.8} / {@code 1.5} / {@code 12} / {@code 1} 走
     * {@link JsNum#toFixed(double, int)}。
     * <p>★ 注意 {@code GS_AL_N_DMG = 35.0} 打出来是 <b>{@code 35}</b>（不是 {@code 35.0}）、
     * {@code GS_AL_RANGE = 24.0} 打出来是 <b>{@code 24}</b> —— 这正是 JS {@code String(x)} 的语义。
     * <p>★ 「{@code （0.8 秒 24 格）}」里的 {@code 0.8} 和 {@code 24} 是<b>硬编码字面量</b>
     * （原脚本 14330 行），<b>不是</b>算出来的 —— 逐字保留。
     */
    static String gsAlStartLine() {
        return "祖宗发射器<?>：已启用（常态 " + JsNum.toFixed(GS_AL_FIRE_MS / 1000.0, 1)
                + " 秒/发 · **无限弹药** · 红色粒子 · 爆炸 " + JsNum.str(GS_AL_N_R) + " 格 / "
                + JsNum.str(GS_AL_N_DMG) + " 点；右键「爆裂黎明」⇒ " + GS_AL_AMMO + " 发 ▼ · "
                + JsNum.toFixed(GS_AL_SKILL_FIRE_MS / 1000.0, 1) + " 秒/发 · 坚守者冲击波 · 爆炸 "
                + JsNum.str(GS_AL_S_R) + " 格 / " + JsNum.str(GS_AL_S_DMG) + " 点 · 冷却 "
                + JsNum.toFixed(GS_AL_CD_MS / 1000.0, 0) + " 秒 · 防抖 "
                + JsNum.toFixed(GS_AL_TOGGLE_MS / 1000.0, 0) + " 秒；射程 " + JsNum.str(GS_AL_RANGE)
                + " 格 · 弹速 " + JsNum.str(GS_AL_SPEED_BPS) + " 格/秒（0.8 秒 24 格）· 不穿墙 · 不破坏地形）";
    }

    // ======================================================================
    // 给探针用的纯算式（只为在大量输入上对账，生产代码不调用）
    // ======================================================================

    /**
     * 弹道第 {@code n} 拍（n 从 1 开始）的推进距离。
     *
     * <p>★ 逐字复刻 {@code gsAlLaunch} 里 {@code dist} 的累加：
     * {@code dist = n * GS_AL_STEP}，并且<b>用同一串浮点加法</b>
     * （{@code dist += 1.5} 做 n 次）—— 不是 {@code n * 1.5}。
     * 两者在 IEEE-754 下对 {@code 1.5}（二进制精确）恰好相同，
     * 但保留累加形式才能与真 JS 的循环逐位对齐。
     */
    static double[] gsAlDistSeries(int n) {
        double[] out = new double[n];
        double d = 0.0;
        for (int i = 0; i < n; i++) {
            d += GS_AL_STEP;
            out[i] = d;
        }
        return out;
    }

    /**
     * 弹道在第几拍会因「到射程尽头」而引爆 —— 逐字复刻 {@code if (dist >= GS_AL_RANGE)}。
     *
     * <p>★ 返回 1-based 的拍号；永不满足返回 <b>-1</b>（正常参数下是第 16 拍）。
     * <p>★ 注意判定发生在<b>推进之后</b>、且墙 / 生物的判定<b>更早</b>
     * ⇒ 这个函数只回答「没有墙也没有生物时，第几拍炸」。
     */
    static int gsAlRangeStopTick(double range) {
        double d = 0.0;
        for (int i = 1; i <= 100000; i++) {
            d += GS_AL_STEP;
            if (d >= range) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 爆炸球形过滤的<b>唯一</b>判据（原脚本 14146 行）：
     * {@code ent.getLocation().distance(pt) > r ⇒ 跳过}。
     *
     * <p>★ 抽出来只为让探针能拿「立方体范围内的一批点」跑网格；
     * 生产代码里内联在 {@link #gsAlBoom} 里（那里还要考虑异常分支）。
     */
    static boolean gsAlInBlast(double dx, double dy, double dz, double r) {
        double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return !(d > r);
    }
}
