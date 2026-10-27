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
import org.bukkit.util.Vector;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「残樱」子系统（原脚本第 <b>2046~2809</b> 行，
 * 顶层入口 {@code gsCherryStart();} 在第 2809 行）—— 单手持手枪，★ 第六十二批新增。
 *
 * <h2>玩法</h2>
 * 手持「残樱」（{@code GS_CHERRY}，音乐唱片残片）时：
 * <ul>
 *   <li><b>左键 = 樱花射线</b>（沿胸口朝准星方向逐 0.5 格推进，撞墙 / 撞生物停）。</li>
 *   <li><b>右键 = 切换单发 / 三连发散射</b>（1 秒冷却）。</li>
 *   <li><b>F = 换弹</b>（拦 {@code PlayerSwapHandItemsEvent}）· <b>Q = 原版丢弃</b>（不拦）。</li>
 * </ul>
 *
 * <h2>★★ 三连发：3 发「同时出膛」、横向偏移固定 0.20 格、射程缩到 6 格</h2>
 * <ul>
 *   <li>一次扳机 <b>3 发同时出膛</b> —— <b>没有</b> 0.1 秒的发间隔、<b>没有</b>
 *       「本轮还剩几发」的状态：{@code GS_CH_BURST_GAP} / {@code burst} / {@code burstAt}
 *       两个 PDC 键与四个函数（{@code gsCherryBurstLeft} / {@code gsCherrySetBurstLeft} /
 *       {@code gsCherryBurstAgo} / {@code gsCherryMarkBurst}）在原脚本里就<b>连声明一起删掉了</b>，
 *       这里同样<b>一个都不存在</b>（探针里有反向断言）。</li>
 *   <li>横向偏移 {@code off = GS_CH_SPREAD_BASE * side} = <b>0.20 格，全程恒定</b>
 *       （第 1 格到射程尽头都一样）—— <b>不随距离散布</b>。
 *       旧的 {@code GS_CH_SPREAD_D0} / {@code GS_CH_SPREAD_K} 与 {@code gsCherrySpreadAt()}
 *       在原脚本里已删除。</li>
 *   <li>横向序号 {@link #GS_CH_SPREAD_SIDES} = {@code [0, -1, 1]}：<b>正中 → 左 → 右</b>，
 *       弹药只剩 1~2 发时先保证<b>正中</b>那一发打出去。</li>
 *   <li>单发用 {@link #GS_CH_RANGE} = 16 格；<b>三连发缩到</b> {@link #GS_CH_BURST_RANGE} = 6 格
 *       （主打近距爆发）。一轮间隔 {@link #GS_CH_BURST_MS} = 300 毫秒。</li>
 * </ul>
 *
 * <h2>★ 残樱没有开镜、也没有任何药效</h2>
 * 心跳里<b>不画准星、不给药效、不注册换手以外的任何键</b>（F 键在第六十五批才被赋予「换弹」）。
 * 探针里有反向断言：本子系统的源码区间里<b>不出现</b> {@code gsScope} / {@code GS_SCOPE_} 任何一个符号。
 *
 * <h2>★★ 监听器注册：原脚本会累积，Java 版改为幂等</h2>
 * 与「余念绝响」同因同形：原脚本在 {@code gsCherryRegisterSync()} 里自己
 * {@code pm.registerEvent(...)}，而 {@code GS_CH_REGISTERED} 闩锁<b>只写不读</b>
 * （机械核对：全脚本只有「定义」与「置 true」两处引用）⇒ 每次 {@code /rsc reload}
 * 都会<b>再注册一遍</b>、监听器<b>无限累积</b>（一次左键被处理 N 遍；好在
 * {@code gsCherryFire} 有射速/一轮间隔闸门，重复调用被静默挡掉，所以症状比开镜轻）。
 * <b>Java 版写成 {@link Listener} 实现类、在 {@link #start()} 里注册一次</b> ——
 * 事件、优先级、取消行为都不变，但 reload 不再累积。这是<b>修掉一个真实缺陷</b>。
 *
 * <p><b>优先级与 ignoreCancelled 逐处核对</b>：原脚本 4 处 {@code registerEvent} 全部是
 * {@code Priority.HIGHEST}，<b>都没有第 5 个参数</b> ⇒ {@code ignoreCancelled} 默认
 * <b>false</b>。Java 侧 4 个 {@code @EventHandler(priority = EventPriority.HIGHEST)}
 * 也保持默认 {@code ignoreCancelled = false}。
 *
 * <p>★ 注册顺序也照抄：① {@code PlayerInteractEvent} → ② {@code EntityDamageByEntityEvent}
 * → ②-b {@code PlayerAnimationEvent} → ③ {@code PlayerSwapHandItemsEvent}。
 */
public final class CherrySub {

    // ======================================================================
    // 参数：名字与原脚本顶层 var 逐字相同（常量对账探针按名字反射比对）
    // ======================================================================

    /** {@code GS_CH_ID} —— 残樱（音乐唱片残片）。 */
    static final String GS_CH_ID = "GS_CHERRY";

    static final int GS_CH_AMMO = 12;               // ★ 第六十四批：弹容量 14 → 12
    static final long GS_CH_FIRE_MS = 200;          // ★ 单发射速 700 → 200（0.2 秒/发）
    static final long GS_CH_RELOAD_MS = 1800;       // ★ 换弹 1.8 秒
    static final double GS_CH_RANGE = 16.0;         // ★ 单发射程 16 格
    static final double GS_CH_DMG = 8.0;            // ★ 伤害 8 点
    static final double GS_CH_MUZZLE_Y = 1.2;       // 发射高度（脚底往上 1.2 格 = 胸口）
    static final double GS_CH_STEP = 0.5;           // 弹道采样步长（格）
    static final double GS_CH_HIT_R = 0.8;          // 命中判定半径（格）

    static final int GS_CH_BURST_N = 3;             // 三连发的发数（散射 = 一次出膛 3 发）
    static final long GS_CH_BURST_MS = 300;         // ★ 第六十六批：一轮的间隔 500 → 300
    static final double GS_CH_BURST_RANGE = 6.0;    // ★ 三发模式的射程（单发仍用 GS_CH_RANGE）
    static final double GS_CH_SPREAD_BASE = 0.20;   // ★ 三发的固定横向偏移（格，全程恒定）

    /**
     * 三发的横向序号：0 = 正中（永远指哪打哪）· -1 = 左 · +1 = 右。
     * ★ 顺序固定为「正中 → 左 → 右」⇒ 弹药只剩 1~2 发时，先保证<b>正中</b>那一发打出去。
     */
    static final int[] GS_CH_SPREAD_SIDES = {0, -1, 1};

    static final long GS_CH_SWITCH_MS = 1000;       // ★ 切换开火方式的冷却 1 秒

    /** 音效（用户「音效为铜箱子关闭，换弹为铜箱子打开」）。 */
    static final String GS_CH_SND_FIRE = "block.copper_chest.close";
    static final String GS_CH_SND_RELOAD = "block.copper_chest.open";
    /** 切换开火方式的音效：拉杆"咔哒"一声（不占用上面两个用户点名的音效）。 */
    static final String GS_CH_SND_SWITCH = "block.lever.click";

    // ======================================================================
    // PDC 键
    // ======================================================================

    /** {@code gsCherryKeys()} 造出来的那六个键（原脚本的 {@code {ammo,lastShot,reload,shooting,mode,switchAt}}）。 */
    static final class Keys {
        NamespacedKey ammo;         // gs:ch_ammo
        NamespacedKey lastShot;     // gs:ch_lastshot
        NamespacedKey reload;       // gs:ch_reload
        NamespacedKey shooting;     // gs:ch_shooting
        NamespacedKey mode;         // gs:ch_mode      （0 = 单发，1 = 三连发）
        NamespacedKey switchAt;     // gs:ch_switch    （上次切换开火方式的时间）
    }

    // ======================================================================
    // 运行期状态（= 原脚本的顶层 var；实例字段 = 同一次顶层求值）
    // ======================================================================

    private final ListenerCore core;

    /** {@code GS_CH_KEYS} —— 懒加载缓存（造失败时<b>不缓存</b>，与原脚本一致）。 */
    private Keys GS_CH_KEYS;

    /**
     * {@code GS_CH_REGISTERED} —— 原脚本<b>只写不读</b>的闩锁。
     * Java 版把它当成「幂等注册」的闩锁用（见类注释）。
     */
    boolean GS_CH_REGISTERED;

    private Listener listener;

    public CherrySub(ListenerCore core) {
        this.core = core;
    }

    // ======================================================================
    // 日志行（抽成静态方法好让探针逐字节对账）
    // ======================================================================

    /** 注册成功那行（原脚本 2783 行，整行字面量）。 */
    static String gsCherryRegisterLine() {
        return ListenerCore.PREFIX
                + "残樱监听器注册成功（左键=樱花射线 · 右键=切换单发/三连发散射 · F=换弹 · Q=原版丢弃）";
    }

    /**
     * 启用那行（原脚本 2795~2803 行）。
     *
     * <p>★★ <b>逐字照抄拼接，包括结尾</b>：原脚本的拼接是
     * {@code ... + ' 秒，左键开火，右键切换发射方式，F 换弹）'} —— 收尾<b>有</b>右括号。
     * （拆分契约 §3 里转写的那一版把结尾截断了，以<b>脚本 + 参考服日志</b>为准；
     * 探针会同时钉住「JS 真身打出来的行」与「参考服日志第 8 行」两条。）
     */
    static String gsCherryStartMsg() {
        return "残樱：单手持手枪已启用（" + GS_CH_AMMO + " 发，单发 "
                + PsychicSub.toFixed1(GS_CH_FIRE_MS / 1000.0) + " 秒/发 · 射程 " + PsychicSub.jsNum(GS_CH_RANGE)
                + "，三连发 " + GS_CH_BURST_N + " 发**同时出膛**（横向偏移固定 "
                + toFixed2(GS_CH_SPREAD_BASE) + " 格，不随距离散布）· 射程 "
                + PsychicSub.jsNum(GS_CH_BURST_RANGE) + " · "
                + PsychicSub.toFixed1(GS_CH_BURST_MS / 1000.0)
                + " 秒一轮，伤害 " + PsychicSub.jsNum(GS_CH_DMG)
                + "，换弹 " + PsychicSub.toFixed1(GS_CH_RELOAD_MS / 1000.0)
                + " 秒，左键开火，右键切换发射方式，F 换弹）";
    }

    /** 带前缀的完整日志行（探针拿它与参考服日志逐字节比对）。 */
    static String gsCherryStartLine() {
        return ListenerCore.PREFIX + gsCherryStartMsg();
    }

    /** JS 的 {@code (x).toFixed(2)} —— {@code 0.2} 要打成 {@code "0.20"}。 */
    static String toFixed2(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ======================================================================
    // PDC / 弹药 / 换弹 / 开火方式（原脚本 2099~2312 行）
    // ======================================================================

    /** {@code gsCherryKeys()} —— 懒加载那六个键。 */
    Keys gsCherryKeys() {
        if (GS_CH_KEYS != null) {
            return GS_CH_KEYS;
        }
        Keys o = new Keys();
        o.ammo = NamespacedKey.fromString("gs:ch_ammo");
        o.lastShot = NamespacedKey.fromString("gs:ch_lastshot");
        o.reload = NamespacedKey.fromString("gs:ch_reload");
        o.shooting = NamespacedKey.fromString("gs:ch_shooting");
        // ★★ 开火方式 / 切换时间
        //   （★ 修正 4：gs:ch_burst 与 gs:ch_burstat 已随"同时出膛"一起删除）
        o.mode = NamespacedKey.fromString("gs:ch_mode");
        o.switchAt = NamespacedKey.fromString("gs:ch_switch");
        GS_CH_KEYS = o;
        return GS_CH_KEYS;
    }

    /** {@code gsCherryPdc(p)}。 */
    static PersistentDataContainer gsCherryPdc(Player p) {
        try {
            return p.getPersistentDataContainer();
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsCherryIsCherry(item)}。 */
    static boolean gsCherryIsCherry(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_CH_ID);
    }

    /** {@code gsCherryNow()} —— 毫秒时间戳。 */
    static long gsCherryNow() {
        try {
            return Instant.now().toEpochMilli();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsCherryAmmo(p)} —— 读弹药（没存过 = 满弹）。 */
    int gsCherryAmmo(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.ammo == null) {
                return GS_CH_AMMO;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return GS_CH_AMMO;
            }
            Integer v = pdc.get(k.ammo, PersistentDataType.INTEGER);
            return (v == null) ? GS_CH_AMMO : v;
        } catch (Throwable e) {
            return GS_CH_AMMO;
        }
    }

    /** {@code gsCherrySetAmmo(p, v)}。 */
    void gsCherrySetAmmo(Player p, int v) {
        try {
            Keys k = gsCherryKeys();
            if (k.ammo == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.set(k.ammo, PersistentDataType.INTEGER, v);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsCherryIsReloading(p)}。 */
    boolean gsCherryIsReloading(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.reload == null) {
                return false;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.reload, PersistentDataType.LONG);
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsCherryReloadLeft(p)} —— 剩余换弹毫秒。 */
    long gsCherryReloadLeft(Player p) {
        try {
            if (!gsCherryIsReloading(p)) {
                return 0;
            }
            Keys k = gsCherryKeys();
            PersistentDataContainer pdc = gsCherryPdc(p);
            Long start = pdc.get(k.reload, PersistentDataType.LONG);
            if (start == null) {
                return 0;
            }
            return gsCherryReloadLeftAt(start, gsCherryNow());
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code left = GS_CH_RELOAD_MS - (now - start); return left > 0 ? left : 0;}。 */
    static long gsCherryReloadLeftAt(long start, long now) {
        long left = GS_CH_RELOAD_MS - (now - start);
        return left > 0 ? left : 0;
    }

    /** {@code gsCherryLastShotAgo(p)} —— 上次开火距今（毫秒）；没记录 = 999999。 */
    long gsCherryLastShotAgo(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.lastShot == null) {
                return 999999;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return 999999;
            }
            Long last = pdc.get(k.lastShot, PersistentDataType.LONG);
            if (last == null) {
                return 999999;
            }
            return gsCherryLastShotAgoAt(last, gsCherryNow());
        } catch (Throwable e) {
            return 999999;
        }
    }

    /** {@code now - last}。 */
    static long gsCherryLastShotAgoAt(long last, long now) {
        return now - last;
    }

    /** {@code gsCherryMarkShot(p)}。 */
    void gsCherryMarkShot(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.lastShot == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.set(k.lastShot, PersistentDataType.LONG, gsCherryNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsCherryMarkReload(p)}。 */
    void gsCherryMarkReload(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.reload == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.set(k.reload, PersistentDataType.LONG, gsCherryNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsCherryClearReload(p)}。 */
    void gsCherryClearReload(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.reload == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.remove(k.reload);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsCherrySetShootingFlag(p, on)} —— ★★ 必须 <b>STRING</b> 而不是 BYTE
     * （理由与「余念绝响」的 {@link PsychicSub#gsPsychicSetShootingFlag} 完全相同：
     * BYTE + JS 数字会装箱成 Integer ⇒ checkcast Byte 失败 ⇒ <b>静默失败</b>）。
     */
    void gsCherrySetShootingFlag(Player p, boolean on) {
        try {
            Keys k = gsCherryKeys();
            if (k.shooting == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
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

    /** {@code gsCherryIsShooting(p)}。 */
    boolean gsCherryIsShooting(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.shooting == null) {
                return false;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return false;
            }
            return pdc.has(k.shooting, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    // ---- ★★ 开火方式（单发 / 三连发）：mode 存 PDC ⇒ 跨 /rsc reload 不丢 ----

    /** {@code gsCherryMode(p)} —— 0 = 单发，1 = 三连发（没存过 = 0）。 */
    int gsCherryMode(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.mode == null) {
                return 0;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return 0;
            }
            Integer v = pdc.get(k.mode, PersistentDataType.INTEGER);
            return (v == null) ? 0 : v;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsCherrySetMode(p, m)}。 */
    void gsCherrySetMode(Player p, int m) {
        try {
            Keys k = gsCherryKeys();
            if (k.mode == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.set(k.mode, PersistentDataType.INTEGER, m);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsCherryIsBurstMode(p)}。 */
    boolean gsCherryIsBurstMode(Player p) {
        return gsCherryMode(p) == 1;
    }

    // ★★★ 原脚本这里原来有四个函数（gsCherryBurstLeft / gsCherrySetBurstLeft /
    //   gsCherryBurstAgo / gsCherryMarkBurst，管"本轮三连发还剩几发、上一发什么时候打的"）。
    //   三发改成**同一刻全部出膛**之后这三件事都不存在了 ⇒ 原脚本**连声明一起删除**，
    //   Java 版同样一个都没有（探针里有反向断言）。

    /** {@code gsCherrySwitchAgo(p)} —— 上次切换开火方式距今（毫秒）；没记录 = 999999。 */
    long gsCherrySwitchAgo(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.switchAt == null) {
                return 999999;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc == null) {
                return 999999;
            }
            Long last = pdc.get(k.switchAt, PersistentDataType.LONG);
            if (last == null) {
                return 999999;
            }
            return gsCherryNow() - last;
        } catch (Throwable e) {
            return 999999;
        }
    }

    /** {@code gsCherryMarkSwitch(p)}。 */
    void gsCherryMarkSwitch(Player p) {
        try {
            Keys k = gsCherryKeys();
            if (k.switchAt == null) {
                return;
            }
            PersistentDataContainer pdc = gsCherryPdc(p);
            if (pdc != null) {
                pdc.set(k.switchAt, PersistentDataType.LONG, gsCherryNow());
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 切换冷却闸门：{@code gsCherrySwitchAgo(p) < GS_CH_SWITCH_MS} ⇒ 不切。 */
    static boolean gsCherrySwitchAllowed(long switchAgo) {
        return switchAgo >= GS_CH_SWITCH_MS;
    }

    /**
     * {@code gsCherryToggleMode(p)} —— 右键：切换开火方式（1 秒冷却）。
     * ★ 修正 4：不用再"清掉半截三连发"了 —— 三发是同时出膛的，切换那一刻没有待发射的余弹。
     */
    void gsCherryToggleMode(Player p) {
        try {
            if (p == null) {
                return;
            }
            if (!gsCherrySwitchAllowed(gsCherrySwitchAgo(p))) {
                return;                            // ★ 切换冷却 1 秒
            }
            gsCherryMarkSwitch(p);
            int m = gsCherryIsBurstMode(p) ? 0 : 1;
            gsCherrySetMode(p, m);
            gsCherryUpdateName(p);
            try {
                World world = p.getWorld();
                world.playSound(p.getLocation(), GS_CH_SND_SWITCH, 1.0f, (m == 1) ? 1.6f : 0.9f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 物品名 / 开火（原脚本 2314~2438 行）
    // ======================================================================

    /** {@code gsCherryColor(s)} —— {@code &} → {@code §}。 */
    static String gsCherryColor(String s) {
        try {
            return ChatColor.translateAlternateColorCodes('&', s);
        } catch (Throwable e) {
            return s.replaceAll("&([0-9a-fk-or])", "\u00a7$1");
        }
    }

    /**
     * 物品名：正常「残樱&lt;弹药&gt;」，换弹中「残樱&lt;弹药ᴿ&gt;」，
     * ★★ 三连发模式再挂一个「≡」（{@code \u2261}，一眼看出现在是哪种开火方式）。
     */
    static String gsCherryName(int ammo, boolean burst, boolean reloading) {
        String tail = burst ? "\u2261" : "";
        if (reloading) {
            return gsCherryColor("&d残樱<" + ammo + tail + "\u1d3f>");
        }
        return gsCherryColor("&d残樱<" + ammo + tail + ">");
    }

    /** {@code gsCherryUpdateName(p)}。 */
    void gsCherryUpdateName(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = null;
            try {
                hand = inv.getItemInMainHand();
            } catch (Throwable e) {
                hand = null;
            }
            if (hand == null || !gsCherryIsCherry(hand)) {
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

            String name = gsCherryName(gsCherryAmmo(p), gsCherryIsBurstMode(p), gsCherryReloadLeft(p) > 0);
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

    /** 射速 / 一轮间隔闸门：{@code gsCherryLastShotAgo(p) < ms} ⇒ 不开火。 */
    static boolean gsCherryFireAllowed(long lastShotAgo, long ms) {
        return lastShotAgo >= ms;
    }

    /** 一次扳机打几发：{@code n = (a < GS_CH_BURST_N) ? a : GS_CH_BURST_N}。 */
    static int gsCherryBurstCount(int ammo) {
        return (ammo < GS_CH_BURST_N) ? ammo : GS_CH_BURST_N;
    }

    /**
     * {@code gsCherryFire(p)} —— 左键开火，按开火方式分两条路。
     *
     * <ul>
     *   <li><b>三连发</b>：只用 <b>0.3 秒的一轮间隔</b>节流（直接拿 lastShot 算），
     *       一次扳机 3 发同时出膛。</li>
     *   <li><b>单发</b>：0.2 秒/发（手枪的点射节奏）。</li>
     * </ul>
     */
    void gsCherryFire(Player p) {
        try {
            if (p == null) {
                return;
            }

            if (gsCherryIsBurstMode(p)) {
                // ★★★ 三发**同时**出膛 ⇒ 没有"本轮还没打完"这回事（不用查 burstLeft），
                //   也没有 0.1 秒的发间隔（不用查 burstAgo）——
                //   只用 **0.3 秒的一轮间隔**节流，直接拿 lastShot 算。
                if (!gsCherryFireAllowed(gsCherryLastShotAgo(p), GS_CH_BURST_MS)) {
                    return;
                }
                // ★ 空弹 ⇒ 触发换弹，不开火
                if (gsCherryAmmo(p) <= 0) {
                    if (gsCherryReloadLeft(p) <= 0) {
                        gsCherryStartReload(p);
                    }
                    return;
                }
                // ★ 换弹中开火 ⇒ 打断换弹
                if (gsCherryReloadLeft(p) > 0) {
                    gsCherryClearReload(p);
                }
                // ★ 一次扳机 = 三发同时出膛（横向固定偏移）
                gsCherryShootScatter(p);
                return;
            }

            // ---- 单发 ----
            // ★ 射速限制：0.2 秒/发（手枪的点射节奏）
            if (!gsCherryFireAllowed(gsCherryLastShotAgo(p), GS_CH_FIRE_MS)) {
                return;
            }

            int a = gsCherryAmmo(p);

            // ★ 弹药为 0 → 强制不能开火（不打断换弹、不重复触发换弹）
            if (a <= 0) {
                if (gsCherryReloadLeft(p) <= 0) {
                    gsCherryStartReload(p);
                }
                return;
            }

            // ★ 有弹药时，换弹中开火 → 打断换弹，继续正常开火
            if (gsCherryReloadLeft(p) > 0) {
                gsCherryClearReload(p);
            }

            gsCherryShootOnce(p);
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("残樱开火出错: " + e);
        }
    }

    /** {@code gsCherryShootOnce(p)} —— 单发模式专用：扣 1 发 → 出 1 道正中射线 → 刷新名字。 */
    boolean gsCherryShootOnce(Player p) {
        try {
            int a = gsCherryAmmo(p);
            if (a <= 0) {
                return false;
            }
            gsCherrySetAmmo(p, a - 1);
            gsCherryMarkShot(p);
            gsCherryShootRay(p, 0, GS_CH_RANGE);
            gsCherryUpdateName(p);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsCherryShootScatter(p)} —— 三连发模式专用：一次扳机 <b>3 发同时出膛</b>。
     *
     * <p>· 弹药不足 3 发时：<b>有几发打几发</b>（顺序 正中 → 左 → 右，先保证正中那发），
     * 打完由调用方在下一拍去换弹。
     * · {@code lastShot 只打点一次}（三发是同一刻，不需要三个时间戳）。
     * · ★ 这里<b>没有</b>「本轮还剩几发」的状态 —— 三发在同一刻全部出膛。
     */
    boolean gsCherryShootScatter(Player p) {
        try {
            int a = gsCherryAmmo(p);
            if (a <= 0) {
                return false;
            }
            int n = gsCherryBurstCount(a);
            gsCherrySetAmmo(p, a - n);
            gsCherryMarkShot(p);
            for (int k = 0; k < n; k++) {
                gsCherryShootRay(p, GS_CH_SPREAD_SIDES[k], GS_CH_BURST_RANGE);
            }
            gsCherryUpdateName(p);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 樱花射线（原脚本 2440~2539 行）
    // ======================================================================

    /** {@code var off = GS_CH_SPREAD_BASE * sd;} —— ★ 全程恒定，不随距离变。 */
    static double gsCherryRayOffset(int side) {
        return GS_CH_SPREAD_BASE * side;
    }

    /**
     * 横向偏移用的基向量：<b>水平右向</b> {@code r = normalize(-dz, 0, dx)}
     * （垂直于准星中线、且始终水平 ⇒ 散布是一个水平的扇形，好瞄）。
     *
     * <p>★ 垂直往上看/看下时（{@code hl < 1e-4}）兜底 {@code (1, 0)}。
     *
     * @return {@code {rxv, rzv}}
     */
    static double[] gsCherryRightBasis(double dx, double dz) {
        double hl = Math.sqrt(dx * dx + dz * dz);
        if (hl < 1.0e-4) {
            return new double[]{1.0, 0.0};
        }
        return new double[]{-dz / hl, dx / hl};
    }

    /** 射线上第 d 格采样点（含横向偏移 off）——原脚本 {@code new Loc(world, ...)} 的三个参数。 */
    static double[] gsCherryRayPoint(double sx, double sy, double sz, Vector dir,
                                     double rxv, double rzv, double off, double d) {
        return new double[]{
                sx + dir.getX() * d + rxv * off,
                sy + dir.getY() * d,
                sz + dir.getZ() * d + rzv * off
        };
    }

    /** 樱花花瓣粒子（1.21.11 里确认存在；拿不到就走 {@code valueOf} 兜底）。 */
    static Particle gsCherryLeafParticle() {
        try {
            Particle t = Particle.CHERRY_LEAVES;
            if (t != null) {
                return t;
            }
        } catch (Throwable e) {
            // 试 valueOf
        }
        try {
            return Particle.valueOf("CHERRY_LEAVES");
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsCherryShootRay(p, side, range)} —— 樱花射线。
     *
     * <p>★ 射程由<b>调用方</b>给（单发 16 格 / 三发 6 格）· 粒子 = {@code CHERRY_LEAVES} ·
     * 撞墙 / 撞生物就停。{@code side}：0 = 正中（指哪打哪）· -1 = 左 · +1 = 右。
     * ★★ 发射音效<b>只在 side === 0 那一发放</b> ⇒ 三发同时出膛<b>只响一声</b>（不叠三次）。
     */
    void gsCherryShootRay(Player p, int side, double range) {
        try {
            int sd = side;                        // 原脚本 `side | 0`（Java 里本来就是 int）
            double rg = range;                    // ★★ 第六十六批：本次射线的射程
            World world = p.getWorld();
            Location eye;
            try {
                eye = p.getEyeLocation();
            } catch (Throwable e) {
                return;
            }
            Vector dir = eye.getDirection();      // 方向 = 视线（准星）

            // ★ 发射起点 = 「胸口」：从眼睛发射会挡住视线
            Location base;
            try {
                base = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            double sx = base.getX();
            double sy = base.getY() + GS_CH_MUZZLE_Y;
            double sz = base.getZ();

            String selfId = ListenerCore.uid(p);

            // ★ 横向偏移用的基向量：**水平右向**
            double[] r = gsCherryRightBasis(dir.getX(), dir.getZ());
            double rxv = r[0];
            double rzv = r[1];

            // 音效：**铜箱子关闭**（发射时，从胸口位置响）· ★ 只由正中那一发响
            if (sd == 0) {
                try {
                    world.playSound(new Location(world, sx, sy, sz), GS_CH_SND_FIRE, 1.0f, 1.4f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }

            // 樱花花瓣粒子
            Particle so = gsCherryLeafParticle();

            // 沿视线逐格推进做射线检测（步长 0.5 格，保证命中精确）
            double d = 0.0;
            int step = 0;
            while (d <= rg) {
                // ★★★ 横向偏移**全程恒定 0.20 格**（不再随距离变大）
                double off = gsCherryRayOffset(sd);
                double[] c = gsCherryRayPoint(sx, sy, sz, dir, rxv, rzv, off, d);
                Location pt = new Location(world, c[0], c[1], c[2]);

                // 樱花射线（每隔 1 格放一个）
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
                            world.getNearbyEntities(pt, GS_CH_HIT_R, GS_CH_HIT_R, GS_CH_HIT_R);
                    Entity ent = gsCherryFirstTarget(selfId, list);
                    if (ent != null) {
                        gsCherryHitDamage(p, ent, GS_CH_DMG);
                        return;                   // 命中一个就停
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }

                d += GS_CH_STEP;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 候选实体筛选 —— 原脚本射线内层 {@code while (it.hasNext())} 里那串
     * 「跳过自己 / 跳过没有血条的 / 跳过死的 / 跳过展示类」的 {@code continue}，
     * <b>顺序逐条照抄</b>（与「余念绝响」那边是同一套判定，两边各自保留一份）。
     */
    static Entity gsCherryFirstTarget(String selfId, java.util.Collection<? extends Entity> list) {
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

    /** {@code gsCherryHitDamage(shooter, victim, dmg)} —— 清受击无敌帧后结算伤害。 */
    void gsCherryHitDamage(Player shooter, Entity victim, double dmg) {
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

            try {
                ((LivingEntity) victim).setNoDamageTicks(0);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }

            gsCherrySetShootingFlag(shooter, true);
            try {
                ((Damageable) victim).damage(dmg, shooter);
            } catch (Throwable e1) {
                try {
                    ((Damageable) victim).damage(dmg);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            gsCherrySetShootingFlag(shooter, false);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 换弹（原脚本 2558~2590 行）
    // ======================================================================

    /** {@code gsCherryStartReload(p)} —— F 键换弹（1.8 秒）。 */
    void gsCherryStartReload(Player p) {
        try {
            // ★ 正在换弹 → 直接忽略，绝不重置换弹进度
            if (gsCherryReloadLeft(p) > 0) {
                return;
            }

            // ★ 弹夹已经满了 → 没必要换（静默返回，不提示）
            int ammo = gsCherryAmmo(p);
            if (ammo >= GS_CH_AMMO) {
                return;
            }

            // ★★★ 原脚本这里原来要"作废没打完的三连发"（修正 2 加的）——
            //   现在三发是**同一刻**全部出膛的，按 F 的那一刻不存在待发射的余弹
            //   ⇒ 这一段整个删掉。

            gsCherryMarkReload(p);
            gsCherryUpdateName(p);

            // 换弹音效：**铜箱子打开**
            try {
                Location loc = p.getLocation();
                World world = p.getWorld();
                world.playSound(loc, GS_CH_SND_RELOAD, 1.0f, 1.0f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsCherryFinishReload(p)}。 */
    void gsCherryFinishReload(Player p) {
        try {
            gsCherrySetAmmo(p, GS_CH_AMMO);
            gsCherryClearReload(p);
            gsCherryUpdateName(p);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 心跳（原脚本 2592~2633 行）—— 每 2 刻 = 0.1 秒
    // ======================================================================

    /** {@code gsCherryPulse()}。 */
    void gsCherryPulse() {
        try {
            if (!core.gsAuraIsCurrent()) {
                return;
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    ItemStack hand = null;
                    try {
                        hand = p.getInventory().getItemInMainHand();
                    } catch (Throwable e) {
                        hand = null;
                    }
                    boolean holding = gsCherryIsCherry(hand);

                    if (!holding) {
                        // ★ 切走残樱（换格子 / 换手持物）⇒ 中断换弹、重新计时
                        //   （★ 修正 4：不用再作废"没打完的三连发"了 —— 三发同时出膛）
                        if (gsCherryReloadLeft(p) > 0) {
                            gsCherryClearReload(p);
                        }
                        continue;
                    }

                    // 换弹时间到 ⇒ 填满弹夹
                    if (gsCherryIsReloading(p) && gsCherryReloadLeft(p) <= 0) {
                        gsCherryFinishReload(p);
                    }

                    // ★★★ 原脚本这里原来有"三连发的后续几发由心跳推进"（每 0.1 秒一拍打一发）。
                    //   现在三发是**同一刻**全部出膛的，心跳**什么都不用推** ⇒ 整段删除。
                    //
                    // ★ 但"打到没弹了自动换弹"这件事还得有人管：散射一次扣 3 发，
                    //   弹药可能正好被打空 ⇒ 这里补一拍检查。
                    if (gsCherryIsBurstMode(p) && gsCherryAmmo(p) <= 0
                            && gsCherryReloadLeft(p) <= 0) {
                        gsCherryStartReload(p);
                    }
                    // ★ 残樱**没有 F 键 / 没有开镜** ⇒ 这里不画准星、不给任何药效。
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 监听器（原脚本 2635~2789 行）
    // ======================================================================

    /**
     * 原脚本 4 处 {@code registerEvent} 的 Java 版。
     *
     * <p>★ 全部 {@code Priority.HIGHEST}、{@code ignoreCancelled} 保持默认 <b>false</b>。
     */
    final class Sync implements Listener {

        /**
         * ① 左键开火 / 右键切换开火方式（{@code PlayerInteractEvent}）。
         * ★★ 按键重排（用户「左键开火…右键切换发射方式」）；换弹从 Q 改到 F（见 ③）。
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
                if (!gsCherryIsCherry(hand)) {
                    return;
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (act.contains("RIGHT_CLICK")) {
                    // ★ 右键 = 切换开火方式（单发 ↔ 三连发，1 秒冷却）
                    e.setCancelled(true);
                    gsCherryToggleMode(p);
                } else if (act.contains("LEFT_CLICK")) {
                    // ★ 左键 = 开火（樱花射线）
                    e.setCancelled(true);
                    gsCherryFire(p);
                }
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② {@code EntityDamageByEntityEvent}：弹道伤害放行 + <b>原版近战取消</b>。
         * （与余念绝响同因：准星怼在怪身上时客户端发的是攻击包，
         * {@code PlayerInteractEvent} 收不到 ⇒ 不开火、反而结算原版近战。）
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
                if (gsCherryIsShooting(p)) {
                    return;                        // 残樱弹道伤害 → 放行
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
                if (!gsCherryIsCherry(hand)) {
                    return;                        // 没拿枪 → 原版近战照常
                }
                e.setCancelled(true);              // ★ 原版近战整个取消
                gsCherryFire(p);                   // ★ 兜底：这一下改成开火
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ②-b 左键的<b>真正兜底</b> —— 挥手动画。
         * ★★ <b>只认 {@code ARM_SWING}</b>：{@code OFF_ARM_SWING} 是<b>副手</b>挥，不能当开火。
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
                if (!gsCherryIsCherry(hand)) {
                    return;                        // 没拿枪 → 不理会
                }
                gsCherryFire(p);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ {@code F 键 = 换弹}（拦下原版换手）—— 第六十五批新增。
         *
         * <p>★ 原脚本注释记着：以前残樱是「没有 F 键功能」（<b>不注册</b>换手事件），
         * 本批 F 改成换弹 ⇒ <b>必须注册</b>（拦下来做的是换弹，不是开镜）。
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
                if (!gsCherryIsCherry(hand)) {
                    return;                        // 没拿枪 → 原版换手
                }
                e.setCancelled(true);              // ★ 拦下原版换手
                gsCherryStartReload(p);            // ★ 换弹
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }
    }

    /** ④ {@code Q 恢复原版丢弃} —— 原脚本<b>不再注册</b> {@code PlayerDropItemEvent}，这里同样没有。 */

    /** {@code gsCherryRegisterSync()} —— 幂等注册（原脚本每次 reload 都累积，见类注释）。 */
    void gsCherryRegisterSync() {
        try {
            if (GS_CH_REGISTERED) {
                return;                            // ★ Java 版幂等闩锁（原脚本只写不读）
            }
            PluginManager pm = Bukkit.getPluginManager();
            if (listener == null) {
                listener = new Sync();
            }
            pm.registerEvents(listener, GSPlugin.instance());
            GS_CH_REGISTERED = true;
            try {
                Bukkit.getLogger().info(gsCherryRegisterLine());
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("残樱同步监听器注册异常: " + e);
        }
    }

    // ======================================================================
    // 入口（= 原脚本顶层 gsCherryStart(); 第 2809 行）
    // ======================================================================

    /** 对应原脚本顶层的 {@code gsCherryStart();}。 */
    public void start() {
        try {
            GS.runRepeating(this::gsCherryPulse, 2, 2);
            gsCherryRegisterSync();
            ListenerCore.gsDaLog(gsCherryStartMsg());
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("残樱启动失败: " + e);
        }
    }
}
