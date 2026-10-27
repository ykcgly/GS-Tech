package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.GSPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Damageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「余念绝响（单手持冲锋枪）+ 念力感知（开镜）」子系统
 * （原脚本第 <b>1119~2044</b> 行，顶层入口 {@code gsPsychicStart();} 在第 2044 行）。
 *
 * <h2>玩法</h2>
 * 手持「余念绝响」（{@code GS_PSYCHIC_POWER}）时：
 * <ul>
 *   <li><b>左键 = 开火</b> —— 沿胸口朝准星方向逐 0.5 格推进的「冲击波射线」，
 *       撞墙停、命中生物立即结算伤害。</li>
 *   <li><b>右键 = 开 / 关瞄准镜</b>（★ 0.8 秒防抖、★ <b>不需要副手</b>）。</li>
 *   <li><b>F = 换弹</b>（拦 {@code PlayerSwapHandItemsEvent}）· <b>Q = 原版丢弃</b>（不拦）。</li>
 * </ul>
 *
 * <h2>★★ 两组伤害数字完全不是一回事（踩过坑，别合并）</h2>
 * <ul>
 *   <li>{@link #GS_PSY_DMG_MIN} = 3.0 / {@link #GS_PSY_DMG_MAX} = 6.0 —— <b>只出现在启动日志里</b>
 *       （日志写「伤害 3~6」）。全脚本对这两个常量<b>没有第二处引用</b>。</li>
 *   <li>真正的伤害是<b>硬编码在 {@code gsPsychicShoot} 里的</b>
 *       {@link #GS_PSY_DMG_HIP} = 37.0（腰射）/ {@link #GS_PSY_DMG_SCOPED} = 77.0（开镜），
 *       射程同理：腰射 {@link #GS_PSY_RANGE} = 25.0 / 开镜 {@link #GS_PSY_SCOPED_RANGE} = 77.0。</li>
 * </ul>
 * ⇒ 谁「顺手统一」这两组数字，谁就把枪的伤害改成 3~6 或把日志改成 37~77，两边都是可观察差异。
 *
 * <h2>★ 开镜（念力感知）</h2>
 * <ul>
 *   <li>状态存在<b>玩家 PDC</b>（{@code gs:psy_scoped}，★ 必须 STRING 不能 BYTE —— 见
 *       {@link #gsScopeSetScoped} 的注释），<b>副手一个格子都不碰</b>。</li>
 *   <li>开镜时给「缓慢 X（amplifier 9）」+「夜视」，<b>各一次给 30 秒（600 刻）、
 *       剩余低于 27 秒（540 刻）才续期</b>（不闪图标）。</li>
 *   <li>★★ 摘镜时<b>只收走本机制给的那一份</b>（靠 {@link #gsScopeFocusOn} /
 *       {@link #gsScopeNightOn} 记录）；玩家自己喝的夜视<b>一律不碰</b>。</li>
 *   <li>准星圆：半径 {@link #GS_SCOPE_RADIUS} = 0.22、距离 {@link #GS_SCOPE_DIST} = 1.6、
 *       圆周 {@link #GS_SCOPE_POINTS} = 12 个点（空心，圆心不挡视线）。</li>
 *   <li>{@link #gsScopePurge} —— 修正 5 之前那枚「念力感知」按钮的遗留清扫，
 *       <b>每人每次运行只扫一遍</b>（{@link #gsScopePurged}）。</li>
 * </ul>
 *
 * <h2>★★ 监听器注册：原脚本会累积，Java 版改为幂等</h2>
 * 原脚本在 {@code gsPsychicRegisterSync()} 里用 RSC 的
 * {@code pm.registerEvent(类, plugin, Priority.HIGHEST, function(l,e){...}, plugin)}
 * <b>自己注册</b>监听器，而且<b>只写不读</b> {@code GS_PSY_REGISTERED} 闩锁
 * （全脚本对该变量的引用只有「定义」与「置 true」两处，机械核对过）
 * ⇒ 每次 {@code /rsc reload} 都会<b>再注册一遍</b>，监听器<b>无限累积</b>：
 * 一次右键会被处理 N 遍。原脚本靠 {@code gsScopeToggle} 里的 0.8 秒防抖缓解
 * （翻转偶数次等于没翻转 ⇒ 玩家看到的就是「按右键没反应」）。
 * <b>Java 版把监听器写成 {@link Listener} 实现类、在 {@link #start()} 里注册一次</b>
 * —— 事件照常收到、优先级照常、取消照常，但 reload 不再累积。这是<b>修掉一个真实缺陷</b>，
 * 不是「顺手优化」。
 *
 * <p><b>优先级与 ignoreCancelled 逐处核对</b>：原脚本 4 处 {@code registerEvent} 全部是
 * {@code Priority.HIGHEST}，且<b>都没有第 5 个参数</b> ⇒ {@code ignoreCancelled} 默认
 * <b>false</b>。Java 侧 4 个 {@code @EventHandler(priority = EventPriority.HIGHEST)}
 * 也保持默认 {@code ignoreCancelled = false}，<b>没有一处写成 true</b>。
 * （① 换手事件里那句手写的 {@code if (e.isCancelled()) return;} 是<b>原脚本自己</b>写的，
 * 逐字保留 —— 它与「框架层 ignoreCancelled」不是同一件事。）
 *
 * <p><b>与原脚本的注册差异</b>：原脚本先解析 RSC/Slimefun 的 Plugin 实例，解析不到就
 * <b>整段不注册、也不打日志</b>；Java 版直接用自己的 {@link GSPlugin} 实例 ⇒
 * 永远注册成功。原版那条「拿不到 plugin 就放弃」的分支在原生版里是死代码。
 */
public final class PsychicSub {

    // ======================================================================
    // 参数：名字与原脚本顶层 var 逐字相同（常量对账探针按名字反射比对）
    // ======================================================================

    /** {@code GS_PSYCHIC_ID} —— 余念绝响（音乐唱片 5）。 */
    static final String GS_PSYCHIC_ID = "GS_PSYCHIC_POWER";

    /** 发射高度（脚底往上 1.2 格 = 胸口）。 */
    static final double GS_PSY_MUZZLE_Y = 1.2;

    static final int GS_PSY_AMMO = 37;              // 弹容量
    static final long GS_PSY_FIRE_MS = 30;          // 射击间隔（毫秒）—— 日志里换算成 2000 RPM
    static final long GS_PSY_RELOAD_MS = 1500;      // 换弹时长（毫秒）
    static final double GS_PSY_RANGE = 25.0;        // 射程（格）
    static final double GS_PSY_HIT_R = 0.8;         // 命中判定半径（格）
    static final double GS_PSY_DMG_MIN = 3.0;       // ★ 只用于启动日志
    static final double GS_PSY_DMG_MAX = 6.0;       // ★ 只用于启动日志

    // ---- 开镜 ----
    static final double GS_SCOPE_RADIUS = 0.22;     // 准星圆半径（格）
    static final double GS_SCOPE_DIST = 1.6;        // 圆环出现在眼前多远（格）
    static final int GS_SCOPE_POINTS = 12;          // 圆周上的粒子数
    static final long GS_SCOPE_DEBOUNCE_MS = 800;   // ★ 用户点名的 0.8 秒防抖
    static final int GS_SCOPE_FOCUS_TICKS = 600;    // 缓慢：一次给 30 秒
    static final int GS_SCOPE_FOCUS_REF = 540;      // 缓慢：剩余低于 27 秒才续期
    static final int GS_SCOPE_NV_TICKS = 600;       // 夜视：一次给 30 秒
    static final int GS_SCOPE_NV_REF = 540;         // 夜视：剩余低于 27 秒才续期
    static final int GS_SCOPE_FOCUS_AMP = 9;        // 「缓慢 X」的 amplifier

    // ---- ★ 真正的伤害 / 射程（硬编码在 gsPsychicShoot 里，与上面那对常量无关）----
    static final double GS_PSY_DMG_HIP = 37.0;
    static final double GS_PSY_DMG_SCOPED = 77.0;
    static final double GS_PSY_SCOPED_RANGE = 77.0;

    /** 弹道采样步长 —— 原脚本里是字面量 {@code d += 0.5;}，没有常量。 */
    static final double GS_PSY_STEP = 0.5;

    // ======================================================================
    // PDC 键（原脚本用 Java.type('NamespacedKey').fromString(...) 现造）
    // ======================================================================

    /** {@code gs:psy_scoped} —— 开镜状态（★ STRING，不是 BYTE）。 */
    static final NamespacedKey GS_SCOPE_SCOPED_KEY = NamespacedKey.fromString("gs:psy_scoped");

    /** {@code gs:psy_scope} —— 修正 5 之前那枚「念力感知」按钮的标记（只用来认遗留物品）。 */
    static final NamespacedKey GS_SCOPE_KEY = NamespacedKey.fromString("gs:psy_scope");

    /** {@code gsPsychicKeys()} 造出来的那四个键（原脚本的 {@code {ammo,lastShot,reload,shooting}}）。 */
    static final class Keys {
        NamespacedKey ammo;         // gs:psy_ammo
        NamespacedKey lastShot;     // gs:psy_lastshot
        NamespacedKey reload;       // gs:psy_reload
        NamespacedKey shooting;     // gs:psy_shooting
    }

    // ======================================================================
    // 运行期状态（= 原脚本的顶层 var；实例字段 = 同一次顶层求值）
    // ======================================================================

    private final ListenerCore core;

    /** {@code GS_PSY_KEYS} —— 懒加载缓存（造失败时<b>不缓存</b>，与原脚本一致）。 */
    private Keys GS_PSY_KEYS;

    /**
     * {@code GS_PSY_REGISTERED} —— 原脚本<b>只写不读</b>的闩锁。
     * Java 版把它当成「幂等注册」的闩锁用（见类注释）。
     */
    boolean GS_PSY_REGISTERED;

    /** {@code GS_SCOPE_TOGGLE_AT} —— uuid → 上次开/关的时间戳(ms)，防抖用。 */
    final Map<String, Long> GS_SCOPE_TOGGLE_AT = new HashMap<>();

    /** {@code gsScopePurged} —— uuid → true（本次运行已经清过遗留按钮）。 */
    final Map<String, Boolean> gsScopePurged = new HashMap<>();

    /** {@code gsScopeNightOn} —— uuid → true（这份夜视是本机制给的）。 */
    final Map<String, Boolean> gsScopeNightOn = new HashMap<>();

    /** {@code gsScopeFocusOn} —— uuid → true（这份缓慢是本机制给的）。 */
    final Map<String, Boolean> gsScopeFocusOn = new HashMap<>();

    public PsychicSub(ListenerCore core) {
        this.core = core;
    }

    // ======================================================================
    // 日志行（抽成静态方法好让探针逐字节对账）
    // ======================================================================

    /** 注册成功那行（原脚本 2022 行，整行字面量）。 */
    static String gsPsychicRegisterLine() {
        return ListenerCore.PREFIX
                + "余念绝响监听器注册成功（左键=开火 · 右键=开镜（开/关·0.8秒防抖·不需副手） · F=换弹 · Q=原版丢弃）";
    }

    /**
     * 启用那行（原脚本 2034~2038 行）。
     *
     * <p>★ 逐字照抄拼接：{@code (60000 / GS_PSY_FIRE_MS)} 是 JS 的<b>浮点</b>除法 ⇒
     * Java 里必须写 {@code 60000.0 / ...}（纪律 3），再用 {@link #jsNum} 按
     * JS 的 Number→String 规则格式化（{@code 3.0} 要打成 {@code 3}、{@code 25.0} 打成 {@code 25}）。
     * {@code (GS_SCOPE_DEBOUNCE_MS / 1000.0).toFixed(1)} 对应 {@link #toFixed1}。
     */
    static String gsPsychicStartMsg() {
        return "余念绝响：单手持冲锋枪已启用（" + GS_PSY_AMMO + " 发，射速 "
                + jsNum(60000.0 / GS_PSY_FIRE_MS) + " RPM，伤害 " + jsNum(GS_PSY_DMG_MIN) + "~" + jsNum(GS_PSY_DMG_MAX)
                + "，射程 " + jsNum(GS_PSY_RANGE) + "，左键开火，右键开镜（开/关，"
                + toFixed1(GS_SCOPE_DEBOUNCE_MS / 1000.0) + " 秒防抖，"
                + "★ 不需要副手），F 换弹，Q 原版丢弃）";
    }

    /** 带前缀的完整日志行（探针拿它与参考服日志逐字节比对）。 */
    static String gsPsychicStartLine() {
        return ListenerCore.PREFIX + gsPsychicStartMsg();
    }

    // ======================================================================
    // 数字格式化（JS 语义）
    // ======================================================================

    /**
     * JS 的 {@code Number → String}：整数值<b>不带小数点</b>（{@code 3.0} → {@code "3"}），
     * 其余走最短往返表示。Java 的 {@code Double.toString(3.0)} 是 {@code "3.0"} ⇒ 不能直接用。
     */
    static String jsNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0e21) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    /** JS 的 {@code (x).toFixed(1)}。 */
    static String toFixed1(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    // ======================================================================
    // PDC / 弹药 / 换弹 / 射速（原脚本 1154~1299 行）
    // ======================================================================

    /** {@code gsPsychicKeys()} —— 懒加载那四个键。 */
    Keys gsPsychicKeys() {
        if (GS_PSY_KEYS != null) {
            return GS_PSY_KEYS;
        }
        Keys o = new Keys();
        // ★ 原脚本外面包着 try/catch(Java.type) —— 在 Java 里类必然拿得到，
        //   唯一会「造不出键」的情形是串非法 ⇒ fromString 返回 null，
        //   下游一律有 `k.xxx == null` 的兜底，语义与「建键失败」等价。
        o.ammo = NamespacedKey.fromString("gs:psy_ammo");
        o.lastShot = NamespacedKey.fromString("gs:psy_lastshot");
        o.reload = NamespacedKey.fromString("gs:psy_reload");
        o.shooting = NamespacedKey.fromString("gs:psy_shooting");
        GS_PSY_KEYS = o;
        return GS_PSY_KEYS;
    }

    /** {@code gsPsychicPdc(p)}。 */
    static PersistentDataContainer gsPsychicPdc(Player p) {
        try {
            return p.getPersistentDataContainer();
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsPsychicIsPsychic(item)}。 */
    static boolean gsPsychicIsPsychic(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_PSYCHIC_ID);
    }

    /** {@code gsPsychicNow()} —— 毫秒时间戳。 */
    static long gsPsychicNow() {
        try {
            return Instant.now().toEpochMilli();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsPsychicAmmo(p)} —— 读弹药（没存过 = 满弹）。 */
    int gsPsychicAmmo(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.ammo == null) {
                return GS_PSY_AMMO;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return GS_PSY_AMMO;
            }
            Integer v = pdc.get(k.ammo, PersistentDataType.INTEGER);
            return (v == null) ? GS_PSY_AMMO : v;
        } catch (Throwable e) {
            return GS_PSY_AMMO;
        }
    }

    /** {@code gsPsychicSetAmmo(p, v)}。 */
    void gsPsychicSetAmmo(Player p, int v) {
        try {
            Keys k = gsPsychicKeys();
            if (k.ammo == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc != null) {
                pdc.set(k.ammo, PersistentDataType.INTEGER, v);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsPsychicIsReloading(p)}。 */
    boolean gsPsychicIsReloading(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.reload == null) {
                return false;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.reload, PersistentDataType.LONG);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsPsychicReloadLeft(p)} —— 剩余换弹毫秒。
     *
     * <p>★ 算式抽成 {@link #gsPsychicReloadLeftAt}（探针按同一算式大量输入比对）。
     */
    long gsPsychicReloadLeft(Player p) {
        try {
            if (!gsPsychicIsReloading(p)) {
                return 0;
            }
            Keys k = gsPsychicKeys();
            PersistentDataContainer pdc = gsPsychicPdc(p);
            Long start = pdc.get(k.reload, PersistentDataType.LONG);
            if (start == null) {
                return 0;
            }
            return gsPsychicReloadLeftAt(start, gsPsychicNow());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code left = GS_PSY_RELOAD_MS - (now - start); return left > 0 ? left : 0;}。 */
    static long gsPsychicReloadLeftAt(long start, long now) {
        long left = GS_PSY_RELOAD_MS - (now - start);
        return left > 0 ? left : 0;
    }

    /** {@code gsPsychicLastShotAgo(p)} —— 上次开火距今（毫秒）；没记录 = 999999。 */
    long gsPsychicLastShotAgo(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.lastShot == null) {
                return 999999;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return 999999;
            }
            Long last = pdc.get(k.lastShot, PersistentDataType.LONG);
            if (last == null) {
                return 999999;
            }
            return gsPsychicLastShotAgoAt(last, gsPsychicNow());
        } catch (Throwable e) {
            return 999999;
        }
    }

    /** {@code now - last}。 */
    static long gsPsychicLastShotAgoAt(long last, long now) {
        return now - last;
    }

    /** {@code gsPsychicMarkShot(p)}。 */
    void gsPsychicMarkShot(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.lastShot == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc != null) {
                pdc.set(k.lastShot, PersistentDataType.LONG, gsPsychicNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsPsychicMarkReload(p)}。 */
    void gsPsychicMarkReload(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.reload == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc != null) {
                pdc.set(k.reload, PersistentDataType.LONG, gsPsychicNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsPsychicClearReload(p)}。 */
    void gsPsychicClearReload(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.reload == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc != null) {
                pdc.remove(k.reload);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsPsychicSetShootingFlag(p, on)} —— 弹道造成带来源伤害时，
     * 让 {@code EntityDamageByEntityEvent} 监听器放行。
     *
     * <p>★★ 必须 <b>STRING</b> 而不是 BYTE：{@code pdc.set(key, PDT.BYTE, 1)} 的第三个参数
     * 擦除后是 Object，GraalJS 会把 JS 数字 1 装箱成 <b>Integer</b>，而 BytePersistentDataType
     * 的 bridge 方法要 checkcast Byte ⇒ ClassCastException，外面包着 try/catch ⇒
     * <b>静默失败</b>（标记永远写不进去 ⇒ 增伤 / 射程 / 准星圆环 / 缓慢 / 夜视一起消失）。
     * Java 里虽然不会装箱错，但<b>照抄 STRING</b> —— 旧存档里的标记也是 STRING。
     */
    void gsPsychicSetShootingFlag(Player p, boolean on) {
        try {
            Keys k = gsPsychicKeys();
            if (k.shooting == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
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

    /** {@code gsPsychicIsShooting(p)}。 */
    boolean gsPsychicIsShooting(Player p) {
        try {
            Keys k = gsPsychicKeys();
            if (k.shooting == null) {
                return false;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.shooting, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 物品名 / 开火（原脚本 1301~1368 行）
    // ======================================================================

    /** {@code gsPsychicColor(s)} —— {@code &} → {@code §}。 */
    static String gsPsychicColor(String s) {
        try {
            return ChatColor.translateAlternateColorCodes('&', s);
        } catch (Throwable e) {
            return s.replaceAll("&([0-9a-fk-or])", "\u00a7$1");
        }
    }

    /**
     * 物品名：正常「余念绝响&lt;弹药&gt;」，换弹中「余念绝响&lt;弹药ᴿ&gt;」。
     *
     * <p>★ {@code \u1d3f} = 上标小 R（ᴿ）。物品由「精神力」改名为「余念绝响」，
     * 这里两处显示名跟着改，<b>弹药逻辑一个字没动</b>。
     */
    static String gsPsychicName(int ammo, boolean reloading) {
        if (reloading) {
            return gsPsychicColor("&d余念绝响<" + ammo + "\u1d3f>");
        }
        return gsPsychicColor("&d余念绝响<" + ammo + ">");
    }

    /** {@code gsPsychicUpdateName(p)}。 */
    void gsPsychicUpdateName(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = null;
            try {
                hand = inv.getItemInMainHand();
            } catch (Throwable e) {
                hand = null;
            }
            if (hand == null || !gsPsychicIsPsychic(hand)) {
                return;
            }

            ItemMeta meta = null;
            try {
                meta = hand.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            if (meta == null) {
                return;
            }

            String name = gsPsychicName(gsPsychicAmmo(p), gsPsychicReloadLeft(p) > 0);
            try {
                meta.setDisplayName(name);
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

    /**
     * 射速闸门：{@code gsPsychicLastShotAgo(p) < GS_PSY_FIRE_MS} ⇒ 不开火。
     * （抽成方法，探针按同一比较大量输入比对。）
     */
    static boolean gsPsychicFireAllowed(long lastShotAgo) {
        return lastShotAgo >= GS_PSY_FIRE_MS;
    }

    /** {@code gsPsychicFire(p)} —— 左键开火。 */
    void gsPsychicFire(Player p) {
        try {
            if (p == null) {
                return;
            }

            // 射速限制
            if (!gsPsychicFireAllowed(gsPsychicLastShotAgo(p))) {
                return;
            }

            int a = gsPsychicAmmo(p);

            // ★ 弹药为 0 → 强制不能开火（不打断换弹、不重复触发换弹）
            if (a <= 0) {
                // 没在换弹才触发换弹；正在换弹就让它继续换，别打断
                if (gsPsychicReloadLeft(p) <= 0) {
                    gsPsychicStartReload(p);
                }
                return;
            }

            // ★ 有弹药时，换弹中开火 → 打断换弹（清除换弹标记），继续正常开火
            if (gsPsychicReloadLeft(p) > 0) {
                gsPsychicClearReload(p);
            }

            a--;
            gsPsychicSetAmmo(p, a);
            gsPsychicMarkShot(p);

            gsPsychicShoot(p);
            gsPsychicUpdateName(p);
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("余念绝响开火出错: " + e);
        }
    }

    // ======================================================================
    // 冲击波射线（原脚本 1370~1454 行）
    // ======================================================================

    /**
     * 射线上的第 d 格采样点（原脚本 {@code new Loc(world, sx + dir.getX()*d, sy + dir.getY()*d, sz + dir.getZ()*d)}）。
     */
    static double[] gsPsychicRayPoint(double sx, double sy, double sz, Vector dir, double d) {
        return new double[]{
                sx + dir.getX() * d,
                sy + dir.getY() * d,
                sz + dir.getZ() * d
        };
    }

    /**
     * {@code gsPsychicShoot(p)} —— 沿胸口射线检测，命中生物立即结算伤害。
     *
     * <p>★ 开镜时伤害 37→77、射程 25→77（见类注释：这两个数是<b>硬编码</b>的）。
     */
    void gsPsychicShoot(Player p) {
        try {
            World world = p.getWorld();
            Location eye;
            try {
                eye = p.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Vector dir = eye.getDirection();              // 方向 = 视线（准星）

            // ★ 发射起点改为「胸口」：从眼睛发射会挡住视线
            Location base;
            try {
                base = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            double sx = base.getX();
            double sy = base.getY() + GS_PSY_MUZZLE_Y;
            double sz = base.getZ();

            // ★ 开了瞄准镜（右键开 / 关）→ 伤害 37→77、射程 25→77
            boolean scoped = gsScopeHas(p);
            double dmg = scoped ? GS_PSY_DMG_SCOPED : GS_PSY_DMG_HIP;
            double range = scoped ? GS_PSY_SCOPED_RANGE : GS_PSY_RANGE;

            String selfId = ListenerCore.uid(p);

            // 音效：石按钮点击声（发射时，从胸口位置响；pitch 0.8 更闷、更沉）
            try {
                world.playSound(new Location(world, sx, sy, sz), "block.stone_button.click_on", 0.9f, 0.8f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // 冲击波「粒子线」：END_ROD 细光点（不再用大块 SONIC_BOOM，免得挡视线）
            Particle so = gsPsychicBeamParticle();

            // 沿视线逐格推进做射线检测（步长 0.5 格，保证命中精确）
            double d = 0.0;
            int step = 0;
            while (d <= range) {
                double[] c = gsPsychicRayPoint(sx, sy, sz, dir, d);
                Location pt = new Location(world, c[0], c[1], c[2]);

                // 冲击波粒子光柱（每隔 1 格放一个）
                step++;
                if (so != null && step % 2 == 1) {
                    try {
                        world.spawnParticle(so, pt, 1, 0.0, 0.0, 0.0, 0.0);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }

                // 撞墙停
                try {
                    Block b = pt.getBlock();
                    if (b != null && b.getType() != null && b.getType().isSolid()) {
                        return;
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }

                // 命中生物 → 立即伤害
                try {
                    java.util.Collection<Entity> list =
                            world.getNearbyEntities(pt, GS_PSY_HIT_R, GS_PSY_HIT_R, GS_PSY_HIT_R);
                    Entity ent = gsPsychicFirstTarget(selfId, list);
                    if (ent != null) {
                        gsPsychicHitDamage(p, ent, dmg);
                        return;                   // 命中一个就停
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }

                d += GS_PSY_STEP;                     // 原脚本是字面量 0.5
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code END_ROD}（拿不到就走 {@code valueOf} 兜底）。 */
    static Particle gsPsychicBeamParticle() {
        try {
            Particle t = Particle.END_ROD;
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试 valueOf
        }
        try {
            return Particle.valueOf("END_ROD");
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 候选实体筛选 —— 原脚本射线内层 {@code while (it.hasNext())} 里那串
     * 「跳过自己 / 跳过没有血条的 / 跳过死的 / 跳过展示类」的 {@code continue}，
     * <b>顺序逐条照抄</b>（顺序有意义：命中一个就停）。
     *
     * <p>★ JS 是鸭子类型：{@code try { ent.getHealth(); } catch { continue; }} 对
     * 「没有 getHealth 方法的实体」生效 ⇒ Java 里等价于 {@code ent instanceof Damageable}。
     */
    static Entity gsPsychicFirstTarget(String selfId, java.util.Collection<? extends Entity> list) {
        if (list == null) {
            return null;
        }
        for (Entity ent : list) {
            String eid;
            try {
                eid = ListenerCore.uid(ent);
            } catch (Throwable e1) {
                continue;
            }
            if (eid.equals(selfId)) {
                continue;
            }
            try {
                ((Damageable) ent).getHealth();
            } catch (Throwable e2) {
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
                String tn = ListenerCore.typeName(ent);
                if (tn.equals("ARMOR_STAND") || tn.equals("DROPPED_ITEM") || tn.equals("ITEM")) {
                    continue;
                }
            } catch (Throwable e4) {
                // 与原脚本一致：静默
            }
            return ent;
        }
        return null;
    }

    /**
     * {@code gsPsychicHitDamage(shooter, victim, dmg)} —— 清受击无敌帧后结算伤害。
     *
     * <p>★ {@code shooting} 标记在伤害<b>前后</b>各写一次：让
     * {@code EntityDamageByEntityEvent} 监听器认出「这是我们自己的弹道伤害」而放行。
     */
    void gsPsychicHitDamage(Player shooter, Entity victim, double dmg) {
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
            try {
                ((Damageable) victim).getHealth();
            } catch (Throwable e) {
                return;
            }

            // 清掉受击无敌帧 → 余念绝响伤害没有受击间隔
            try {
                ((LivingEntity) victim).setNoDamageTicks(0);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            gsPsychicSetShootingFlag(shooter, true);
            try {
                ((Damageable) victim).damage(dmg, shooter);
            } catch (Throwable e1) {
                try {
                    ((Damageable) victim).damage(dmg);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            gsPsychicSetShootingFlag(shooter, false);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 换弹（原脚本 1474~1503 行）
    // ======================================================================

    /** {@code gsPsychicStartReload(p)}。 */
    void gsPsychicStartReload(Player p) {
        try {
            // ★ 正在换弹 → 直接忽略，绝不重置换弹进度
            //   （否则连按换弹键会把计时一次次归零，弹永远换不满）
            if (gsPsychicReloadLeft(p) > 0) {
                return;
            }

            // ★ 弹夹已经满了 → 没必要换（静默返回，不提示）
            int ammo = gsPsychicAmmo(p);
            if (ammo >= GS_PSY_AMMO) {
                return;
            }

            gsPsychicMarkReload(p);
            gsPsychicUpdateName(p);

            // 换弹音效：弩装填（音量 3.0，比默认更响、传得更远）
            try {
                Location loc = p.getLocation();
                World world = p.getWorld();
                world.playSound(loc, "item.crossbow.loading_start", 3.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsPsychicFinishReload(p)}。 */
    void gsPsychicFinishReload(Player p) {
        try {
            gsPsychicSetAmmo(p, GS_PSY_AMMO);
            gsPsychicClearReload(p);
            gsPsychicUpdateName(p);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 开镜（原脚本 1505~1772 行）
    // ======================================================================

    /** {@code gsScopeIsButton(item)} —— 认出修正 5 之前那枚遗留按钮。 */
    static boolean gsScopeIsButton(ItemStack item) {
        if (item == null || GS_SCOPE_KEY == null) {
            return false;
        }
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            }
            String v = meta.getPersistentDataContainer().get(GS_SCOPE_KEY, PersistentDataType.STRING);
            return v != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsScopeScoped(p)} —— 读玩家 PDC 的 {@code gs:psy_scoped} 标记。
     * ★★ 必须 STRING（理由见 {@link #gsPsychicSetShootingFlag}）。
     */
    static boolean gsScopeScoped(Player p) {
        try {
            if (GS_SCOPE_SCOPED_KEY == null) {
                return false;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(GS_SCOPE_SCOPED_KEY, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsScopeSetScoped(p, on)}。 */
    static void gsScopeSetScoped(Player p, boolean on) {
        try {
            if (GS_SCOPE_SCOPED_KEY == null) {
                return;
            }
            PersistentDataContainer pdc = gsPsychicPdc(p);
            if (pdc == null) {
                return;
            }
            if (on) {
                pdc.set(GS_SCOPE_SCOPED_KEY, PersistentDataType.STRING, "1");
            } else {
                pdc.remove(GS_SCOPE_SCOPED_KEY);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsScopeHas(p)} —— ★ 名字保持不变：开火伤害/射程、准星圆环、专注、夜视
     * 全都靠它，只把内部实现从「查副手物品」换成「查 PDC 标记」，下游一处都不用改。
     */
    static boolean gsScopeHas(Player p) {
        return gsScopeScoped(p);
    }

    /**
     * {@code gsScopePurge(p)} —— 把修正 5 之前造的「念力感知」按钮从玩家身上清掉。
     * 每人<b>只扫一次</b>（{@link #gsScopePurged}）。那些按钮是本机制运行时现造的、
     * 打了 {@code gs:psy_scope} 标记 ⇒ 删掉绝对安全，也不会误伤玩家的任何真物品。
     */
    void gsScopePurge(Player p) {
        try {
            String uuid = ListenerCore.uid(p);
            if (Boolean.TRUE.equals(gsScopePurged.get(uuid))) {
                return;
            }
            gsScopePurged.put(uuid, Boolean.TRUE);
            PlayerInventory inv = p.getInventory();
            // getContents() 对玩家来说是 41 格（主背包 0-35 + 护甲 36-39 + 副手 40）
            ItemStack[] cont = inv.getContents();
            for (int i = 0; i < cont.length; i++) {
                if (gsScopeIsButton(cont[i])) {
                    try {
                        inv.setItem(i, null);
                    } catch (Throwable e1) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 防抖判定：{@code lastMs != null && (nowMs - lastMs) < GS_SCOPE_DEBOUNCE_MS} ⇒ 吞掉。 */
    static boolean gsScopeDebounced(Long lastMs, long nowMs) {
        return lastMs != null && (nowMs - lastMs) < GS_SCOPE_DEBOUNCE_MS;
    }

    /**
     * 防抖闸门（原脚本 {@code gsScopeToggle} 里那段的原样搬运）：
     * 够窗口就记下本次时间并放行，否则返回 false。
     */
    static boolean gsScopeToggleGate(Map<String, Long> table, String uuid, long nowMs) {
        Long lastMs = table.get(uuid);
        if (gsScopeDebounced(lastMs, nowMs)) {
            return false;
        }
        table.put(uuid, nowMs);
        return true;
    }

    /**
     * {@code gsScopeToggle(p)} —— 右键：开 / 关瞄准镜。
     *
     * <p>★★ 防抖 0.8 秒（用户「做一个0.8秒的防抖延迟」）：{@code /rsc reload} 之后脚本会
     * <b>再执行一次</b>、同一按键事件被多个监听器实例处理 N 遍 —— 而「开/关」是翻转操作，
     * 翻转偶数次就等于没变（玩家看到的就是「按右键没反应」）。
     * Java 版已经幂等注册（不会累积），但<b>防抖照留</b>：它同时挡掉玩家的连点。
     */
    void gsScopeToggle(Player p) {
        try {
            String uuid = ListenerCore.uid(p);
            long nowMs = gsPsychicNow();
            if (!gsScopeToggleGate(GS_SCOPE_TOGGLE_AT, uuid, nowMs)) {
                return;
            }

            if (gsScopeScoped(p)) {
                // ★ 关镜：标记一清，准星 / 专注 / 夜视 / 伤害加成**立刻全部消失**
                gsScopeSetScoped(p, false);
                gsScopeUnfocus(p);                 // ★ 关闭瞬间就解除「专注」（移除缓慢 + 夜视）
                try {
                    p.getWorld().playSound(p.getLocation(), "item.spyglass.stop_using", 2.0f, 1.0f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                return;
            }
            // ★ 开镜：只写标记 —— **副手不碰、不塞按钮、不动玩家原来的副手物品**
            gsScopeSetScoped(p, true);
            gsScopeFocus(p);                       // ★ 立刻上「专注」（不等下一跳心跳）
            try {
                p.getWorld().playSound(p.getLocation(), "item.spyglass.use", 2.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsScopeStash(p, stack)} —— 把副手原来的东西请出去：优先塞进背包，
     * 背包塞不下的部分掉在脚边。
     *
     * <p>★★ <b>跨子系统</b>：本函数在「余念绝响」这一段里定义，但<b>本子系统一次都不调用</b>
     * （修正 5 之后开镜不再需要副手按钮），真正的调用点在<b>五骊矟</b>子系统
     * （原脚本 3562 / 3609 行：{@code gsScopeStash(p, old)} / {@code gsScopeStash(p, st.stashed)}）。
     * 所以它是 {@code public static} —— 五骊矟那边直接 {@code PsychicSub.gsScopeStash(...)} 即可
     * （见交付说明的跨子系统依赖清单）。
     */
    public static void gsScopeStash(Player p, ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            String n = "";
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
            PlayerInventory inv = p.getInventory();
            HashMap<Integer, ItemStack> rest = inv.addItem(stack.clone());
            if (rest != null && !rest.isEmpty()) {
                for (ItemStack it : rest.values()) {
                    try {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    } catch (Throwable e2) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 「缓慢 X」的药效类型（1.20.5+ 叫 SLOWNESS，之前叫 SLOW）。 */
    static PotionEffectType gsScopeSlowType() {
        return ListenerCore.gsSetPotionType("SLOWNESS", "SLOW");
    }

    /** 夜视的药效类型。 */
    static PotionEffectType gsScopeNightType() {
        return ListenerCore.gsSetPotionType("NIGHT_VISION", null);
    }

    /**
     * 「缓慢这一轮还要不要再给」——原脚本：
     * {@code if (cur != null && cur.getAmplifier() === 9 && cur.getDuration() > GS_SCOPE_FOCUS_REF) needSlow = false;}
     *
     * <p>★ 用 {@code needSlow} 标记而不是直接 return —— 否则这一轮会<b>连夜视那段一起跳过</b>
     * （缓慢与夜视是两套独立效果，必须各自判断，不能互相带跑）。
     *
     * @param curNull 当前身上有没有这个药效（JS 的 {@code cur != null}）
     */
    static boolean gsScopeNeedSlow(boolean curNull, int curAmp, int curDur) {
        return !(!curNull && curAmp == GS_SCOPE_FOCUS_AMP && curDur > GS_SCOPE_FOCUS_REF);
    }

    /** 「夜视这一轮还要不要再给」——原脚本 {@code if (nvCur == null || nvLeft <= GS_SCOPE_NV_REF)}。 */
    static boolean gsScopeNeedNight(boolean curNull, int curLeft) {
        return curNull || curLeft <= GS_SCOPE_NV_REF;
    }

    /**
     * 「摘镜时该不该移除缓慢」——原脚本 {@code if (flagged || amp === 9)}。
     *
     * <p>★ 第二种情形是为了兜住「标记丢失」（比如中途 {@code /rsc reload} 过）：
     * 原版药水最高只到缓慢 IV(amp 3)，amp 9 不可能是玩家自己喝的，删了很安全。
     */
    static boolean gsScopeShouldRemoveSlow(boolean flagged, int amp) {
        return flagged || amp == GS_SCOPE_FOCUS_AMP;
    }

    /**
     * {@code gsScopeFocus(p)} —— 开镜时的「专注」：缓慢 X + 夜视。
     * 节奏：<b>一次给 30 秒，剩余低于 27 秒才续期</b>（不闪图标）。
     */
    void gsScopeFocus(Player p) {
        try {
            // ★ 已经关镜（PDC 标记没了）→ 立刻把这份缓慢磨掉
            if (!gsScopeHas(p)) {
                gsScopeUnfocus(p);
                return;
            }

            PotionEffectType t = gsScopeSlowType();
            if (t == null) {
                return;
            }

            // ★ 先登记"这份缓慢归本机制管" —— 必须放在「够新鲜就跳过」**之前**，
            //   否则续期的那些轮次不会登记，标记会漏掉（切走时就清不掉了）
            try {
                gsScopeFocusOn.put(ListenerCore.uid(p), Boolean.TRUE);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
            boolean curNull = true;
            int curAmp = 0;
            int curDur = 0;
            try {
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null) {
                    curNull = false;
                    curAmp = cur.getAmplifier();
                    curDur = cur.getDuration();
                }
            } catch (Throwable e) {
                curNull = true;
            }

            if (gsScopeNeedSlow(!curNull, curAmp, curDur)) {
                try {
                    p.addPotionEffect(new PotionEffect(t, GS_SCOPE_FOCUS_TICKS, GS_SCOPE_FOCUS_AMP, true, false), true);
                } catch (Throwable e) {
                    try {
                        p.addPotionEffect(new PotionEffect(t, GS_SCOPE_FOCUS_TICKS, GS_SCOPE_FOCUS_AMP, true, false));
                    } catch (Throwable e2) {
                        // 与原脚本一致：静默
                    }
                }
            }

            // ---------- ★ 夜视（一次 30 秒、剩余低于 27 秒续期）----------
            // ★ 玩家当前**已有夜视**（不是本机制给的）→ 什么都不做：不触发、不接管、不登记。
            PotionEffectType nvT = gsScopeNightType();
            if (nvT != null) {
                String nvUuid = ListenerCore.uid(p);
                boolean nvOwned = false;
                try {
                    nvOwned = Boolean.TRUE.equals(gsScopeNightOn.get(nvUuid));
                } catch (Throwable e) {
                    nvOwned = false;
                }
                PotionEffect nvCur = null;
                try {
                    nvCur = p.getPotionEffect(nvT);
                } catch (Throwable e) {
                    nvCur = null;
                }

                if (nvCur != null && !nvOwned) {
                    // 别人给的（自己喝的 / 梦境护腿）→ 完全不碰
                } else {
                    try {
                        gsScopeNightOn.put(nvUuid, Boolean.TRUE);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    int nvLeft = 0;
                    try {
                        nvLeft = nvCur.getDuration();
                    } catch (Throwable e) {
                        nvLeft = 0;
                    }
                    // 还够久（> 27 秒）→ 这次不续，避免图标 / 画面闪烁
                    if (gsScopeNeedNight(nvCur == null, nvLeft)) {
                        try {
                            p.addPotionEffect(new PotionEffect(nvT, GS_SCOPE_NV_TICKS, 0, true, false), true);
                        } catch (Throwable e) {
                            try {
                                p.addPotionEffect(new PotionEffect(nvT, GS_SCOPE_NV_TICKS, 0, true, false));
                            } catch (Throwable e2) {
                                // 与原脚本一致：静默
                            }
                        }
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsScopeUnfocus(p)} —— 关镜 / 切走本武器 → 立刻解除「专注」。
     *
     * <p>★★ 只收走<b>本机制给过</b>的那一份：缓慢靠 {@link #gsScopeFocusOn} 标记
     * （外加 amp === 9 兜底），夜视靠 {@link #gsScopeNightOn} 标记。
     * 玩家自己喝的夜视 / 梦境护腿给的夜视<b>一律不碰</b>。
     */
    void gsScopeUnfocus(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            boolean flagged = Boolean.TRUE.equals(gsScopeFocusOn.get(uuid));
            gsScopeFocusOn.put(uuid, Boolean.FALSE);

            // ★ 夜视：只有"本机制给过"的才摘（玩家自己喝的 / 梦境护腿给的 → 一律不碰）
            boolean nvFlagged = false;
            try {
                nvFlagged = Boolean.TRUE.equals(gsScopeNightOn.get(uuid));
            } catch (Throwable e) {
                nvFlagged = false;
            }
            try {
                gsScopeNightOn.put(uuid, Boolean.FALSE);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            if (nvFlagged) {
                PotionEffectType nvT = gsScopeNightType();
                if (nvT != null) {
                    try {
                        p.removePotionEffect(nvT);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }

            PotionEffectType t = gsScopeSlowType();
            if (t == null) {
                return;
            }

            // 两种情况都清掉：
            //   ① 本机制确实给过（uuid 标记还在）
            //   ② 当前身上正好是「缓慢 X（amplifier 9）」—— 这是本机制专属的等级
            int amp = -1;
            try {
                PotionEffect cur = p.getPotionEffect(t);
                if (cur != null) {
                    try {
                        amp = cur.getAmplifier();
                    } catch (Throwable e2) {
                        amp = -1;
                    }
                }
            } catch (Throwable e) {
                amp = -1;
            }

            if (gsScopeShouldRemoveSlow(flagged, amp)) {
                try {
                    p.removePotionEffect(t);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 准星圆环（原脚本 1774~1820 行）
    // ======================================================================

    /** 细粒子探测：{@code ELECTRIC_SPARK → CRIT → END_ROD}。 */
    static Particle gsScopeRingParticle() {
        try {
            Particle t = Particle.ELECTRIC_SPARK;
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试 valueOf
        }
        try {
            Particle t = Particle.valueOf("ELECTRIC_SPARK");
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试下一个
        }
        try {
            Particle t = Particle.CRIT;
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试 valueOf
        }
        try {
            Particle t = Particle.valueOf("CRIT");
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试 END_ROD
        }
        try {
            return Particle.END_ROD;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 圆环平面的两个基向量 —— 原脚本：
     * <pre>
     *   right = dir × up(0,1,0)；|right|² &lt; 1e-6 ⇒ right = (1,0,0)；再 normalize
     *   up2   = right × dir，再 normalize
     * </pre>
     * 返回 {@code {right, up2}}。
     */
    static Vector[] gsScopeBasis(Vector dir) {
        Vector up = new Vector(0, 1, 0);
        Vector right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 1.0E-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector up2 = right.clone().crossProduct(dir).normalize();
        return new Vector[]{right, up2};
    }

    /** 圆环上第 {@code i} 个点的坐标（原脚本 1812~1816 行的算式）。 */
    static double[] gsScopeRingPoint(double cx, double cy, double cz, Vector right, Vector up2, int i) {
        double th = (2.0 * Math.PI * i) / GS_SCOPE_POINTS;
        double co = Math.cos(th);
        double si = Math.sin(th);
        return new double[]{
                cx + GS_SCOPE_RADIUS * (co * right.getX() + si * up2.getX()),
                cy + GS_SCOPE_RADIUS * (co * right.getY() + si * up2.getY()),
                cz + GS_SCOPE_RADIUS * (co * right.getZ() + si * up2.getZ())
        };
    }

    /**
     * {@code gsScopeDraw(p)} —— 每 2 刻画一次准星圆环（空心：圆周一圈粒子，圆心不挡视线）。
     */
    void gsScopeDraw(Player p) {
        try {
            if (!gsScopeHas(p)) {
                return;
            }

            Particle t = gsScopeRingParticle();
            if (t == null) {
                return;
            }

            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();

            // 以视线为法线，构造圆环所在的平面坐标系
            Vector[] basis = gsScopeBasis(dir);
            Vector right = basis[0];
            Vector up2 = basis[1];

            double cx = eye.getX() + dir.getX() * GS_SCOPE_DIST;
            double cy = eye.getY() + dir.getY() * GS_SCOPE_DIST;
            double cz = eye.getZ() + dir.getZ() * GS_SCOPE_DIST;

            World world = p.getWorld();
            int n = GS_SCOPE_POINTS;
            for (int i = 0; i < n; i++) {
                double[] c = gsScopeRingPoint(cx, cy, cz, right, up2, i);
                try {
                    world.spawnParticle(t, c[0], c[1], c[2], 1, 0.0, 0.0, 0.0, 0.0);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 心跳（原脚本 1822~1864 行）—— 每 2 刻一次
    // ======================================================================

    /** {@code gsPsychicPulse()} —— 换弹进度 + 切换手持物中断换弹 + 开镜维护。 */
    void gsPsychicPulse() {
        try {
            if (!core.gsAuraIsCurrent()) {
                return;
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    // ★★★ 先把修正 5 之前遗留的「念力感知」按钮清掉（每人只扫一次）
                    gsScopePurge(p);

                    ItemStack hand = null;
                    try {
                        hand = p.getInventory().getItemInMainHand();
                    } catch (Throwable e) {
                        hand = null;
                    }
                    boolean holding = gsPsychicIsPsychic(hand);

                    if (!holding) {
                        if (gsPsychicReloadLeft(p) > 0) {
                            gsPsychicClearReload(p);
                        }
                        // ★★★ 修正 5：**切换手持物 ⇒ 开镜状态立刻消失**（用户点名）
                        //   · 清 PDC 标记 ⇒ 准星 / 伤害射程加成 / 专注 / 夜视一起消失
                        //   · 副手不用管 —— 修正 5 之后本来就没碰过它
                        gsScopeSetScoped(p, false);
                        gsScopeUnfocus(p);
                        continue;
                    }

                    if (gsPsychicIsReloading(p) && gsPsychicReloadLeft(p) <= 0) {
                        gsPsychicFinishReload(p);
                    }

                    // ★ 余念绝响**不再给任何手持药效**
                    //   （原抗性X / 生命恢复V / 速度II / 饱和I 已整段删除；这些增益改由「梦境套」提供）

                    // 瞄准镜：画准星圆环 + 进入「专注」（缓慢 X）
                    gsScopeDraw(p);
                    gsScopeFocus(p);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 监听器（原脚本 1866~2028 行）
    // ======================================================================

    /**
     * 原脚本 4 处 {@code registerEvent} 的 Java 版。
     *
     * <p>★ 全部 {@code Priority.HIGHEST}、{@code ignoreCancelled} 保持默认 <b>false</b>
     * —— 与 {@code registerEvent} 不传第 5 个参数逐处对应。
     */
    final class Sync implements Listener {

        /**
         * ① {@code F 键 = 换弹}（拦下原版换手）。
         *
         * <p>F 的原版行为是「主副手交换」⇒ 拦 {@code PlayerSwapHandItemsEvent}。
         * 主手拿着余念绝响 ⇒ 取消换手 + 走换弹；主手是别的东西 ⇒ 照常放行。
         * ★ 这里<b>不需要 runLater</b>：取消换手之后主手那件从头到尾没离开过。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSwapHandItems(PlayerSwapHandItemsEvent e) {
            try {
                if (e.isCancelled()) {
                    return;                        // 重复注册的实例 → 跳过
                }
                Player p = e.getPlayer();
                ItemStack hand = null;
                try {
                    hand = p.getInventory().getItemInMainHand();
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!gsPsychicIsPsychic(hand)) {
                    return;                        // 没拿枪 → 原版换手
                }
                e.setCancelled(true);              // ★ 拦下原版换手
                gsPsychicStartReload(p);           // ★ 换弹
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② 左键开火 / 右键开镜（{@code PlayerInteractEvent}）。
         *
         * <p>★★ 用户「换弹改成q…然后左键开火，右键开镜」+ 第六十四批「换弹从 Q 改到 F」
         * ⇒ 左键 = 开火 ｜ 右键 = 开 / 关瞄准镜 ｜ F = 换弹（见 ①）。
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
                if (!gsPsychicIsPsychic(hand)) {
                    return;
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (act.contains("RIGHT_CLICK")) {
                    // ★ 右键 = 开 / 关瞄准镜（从 F 键挪过来）
                    e.setCancelled(true);
                    gsScopeToggle(p);
                } else if (act.contains("LEFT_CLICK")) {
                    // ★ 左键 = 开火（原来是换弹）
                    e.setCancelled(true);
                    gsPsychicFire(p);
                }
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ {@code EntityDamageByEntityEvent}：弹道伤害放行 + <b>原版近战取消</b>。
         *
         * <p>★★ 用户「左键会出现原版伤害，这会导致无法开火」：玩家把准星怼在怪身上按左键时，
         * 客户端发的是<b>攻击包</b>而不是挥手包 ⇒ {@code PlayerInteractEvent} 收不到 ⇒
         * 枪不开火、反而结算了原版近战。这里把「手持余念绝响时的原版近战」整个取消：
         * 怪不掉血、不被击退、也不留 10 刻无敌帧（无敌帧会把紧接着的弹道伤害一起吃掉）。
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
                // ★ 类型名就是 PLAYER ⇒ 必然是 Player（原脚本是动态派发，这里显式转型）
                Player p = (Player) damager;
                if (gsPsychicIsShooting(p)) {
                    return;                        // 余念绝响弹道伤害 → 放行
                }
                // ★ 只碰原版近战（火焰 / 摔落 / 弹射物之类完全不插手）
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
                if (!gsPsychicIsPsychic(hand)) {
                    return;                        // 没拿枪 → 原版近战照常
                }
                e.setCancelled(true);              // ★ 原版近战整个取消
                gsPsychicFire(p);                  // ★ 兜底：这一下改成开火
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③-b 左键的<b>真正兜底</b> —— 挥手动画。
         *
         * <p>客户端<b>每次左键都会发一次挥手包</b>，不管准星对着空气、方块还是怪 ⇒
         * 保证「左键永远打得出来」。与 ② 的 {@code LEFT_CLICK_AIR} 同时命中也没关系：
         * {@code gsPsychicFire} 有射速限制，后到的那次静默返回（天然去重，不会双发）。
         *
         * <p>★★ <b>只认 {@code ARM_SWING}</b>：{@code OFF_ARM_SWING} 是<b>副手</b>挥
         * （F 换手那一下），不能当开火。
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
                if (!gsPsychicIsPsychic(hand)) {
                    return;                        // 没拿枪 → 不理会
                }
                gsPsychicFire(p);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }
    }

    /** ④ {@code Q 恢复原版丢弃} —— 原脚本<b>不再注册</b> {@code PlayerDropItemEvent}，这里同样没有。 */
    private Listener listener;

    /**
     * {@code gsPsychicRegisterSync()} —— 幂等注册（原脚本每次 reload 都累积，见类注释）。
     */
    void gsPsychicRegisterSync() {
        try {
            if (GS_PSY_REGISTERED) {
                return;                            // ★ Java 版幂等闩锁（原脚本只写不读）
            }
            PluginManager pm = Bukkit.getPluginManager();
            if (listener == null) {
                listener = new Sync();
            }
            pm.registerEvents(listener, GSPlugin.instance());
            GS_PSY_REGISTERED = true;
            try {
                Bukkit.getLogger().info(gsPsychicRegisterLine());
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("余念绝响同步监听器注册异常: " + e);
        }
    }

    // ======================================================================
    // 入口（= 原脚本顶层 gsPsychicStart(); 第 2044 行）
    // ======================================================================

    /** 对应原脚本顶层的 {@code gsPsychicStart();}。 */
    public void start() {
        try {
            GS.runRepeating(this::gsPsychicPulse, 2, 2);
            gsPsychicRegisterSync();
            ListenerCore.gsDaLog(gsPsychicStartMsg());
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("余念绝响启动失败: " + e);
        }
    }
}
