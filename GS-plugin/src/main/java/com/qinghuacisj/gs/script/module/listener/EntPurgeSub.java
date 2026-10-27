package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code gs_listener.js} 的「展示实体『孤儿球』清扫」子系统
 * （定义在原脚本第 <b>11568~11759</b> 行；<b>顶层调用在 13569 行</b>，
 * 也就是全脚本<b>最后一个</b>顶层调用 —— 见下面的「必须最后跑」）。
 *
 * <h2>为什么需要它</h2>
 * 球是用 {@code ItemDisplay}（展示实体）渲染的，而 {@code setPersistent(false)} 只保证
 * <b>不写进区块存档</b>；脚本自己重载 / 崩了 / 关服没走干净时，实体仍会留在内存里
 * （区块一卸载才消失）。于是：
 * <ul>
 *   <li>① <b>启动清扫</b>：这一刻内存里没有任何合法球 ⇒ <b>全删</b>
 *       （各模块随后自己重建）。这就是日志里「启动清了 N 个残留」。</li>
 *   <li>② <b>周期清扫</b>：每 {@code GS_ENT_PURGE_EVERY} 刻（20 刻 = 1 秒）删一次
 *       <b>不在状态表里</b>的孤儿（正在用的球放过）。</li>
 *   <li>③ <b>关服前再全清一遍</b>：监听 {@code PluginDisableEvent}。</li>
 * </ul>
 *
 * <h2>★★★ {@code gsEntPurgeStart()} 必须是<b>最后</b>一个顶层调用</h2>
 * 原脚本第 13565~13568 行的注释原文：
 * <pre>
 *   ★★★ 孤儿球清扫**必须最后启动**（排在 gsBbStart / gsBgStart 之后）：
 *        它的「启动清扫」会把内存里所有展示实体全删一遍，
 *        所以要先让律师函 / 生成器把状态表建好，清扫才知道哪些是「正在用的」。
 * </pre>
 * ⇒ 集成方在 {@code ListenerModule} 里的调用顺序必须是
 * {@code gsBbStart()} → {@code gsBgStart()} → {@code gsKlStart()} → {@code gsEntPurgeStart()}。
 *
 * <h2>★★ 「认球只认『我们的标记 + 专属头颅』⇒ 别人的展示实体一律不碰」</h2>
 * {@link #gsEntIsOurs(Entity)} 的判定链（<b>两道，缺一不可</b>）：
 * <ol>
 *   <li>类型名必须含 {@code DISPLAY}（只处理展示实体这一族：
 *       {@code ItemDisplay} / {@code BlockDisplay} / {@code TextDisplay}…）；
 *       这一条就挡掉了所有「别人的普通实体」。</li>
 *   <li>① 身上有<b>我们自己打的标记</b> {@code GS_ENT_TAG}（PDC {@code gs:ent_ball}，
 *       见 {@link BbSub#GS_ENT_TAG}）；
 *       <b>或者</b>② 退路：{@code getItemStack()} 是<b>我们那颗专属头颅</b>
 *       （{@link #gsEntItemIsOurs}：严格只认 {@code PLAYER_HEAD}/{@code SKULL} +
 *       profile UUID 相同，读不出 profile 时再退一步用 {@code isSimilar} 与
 *       {@link BbSub#gsBbHeadItem} 比对）。
 *       ★ ② 这条退路是给<b>本批之前造的孤儿</b>准备的 —— 那时还没打标记。</li>
 * </ol>
 * ⇒ 别的插件 / 别的模块的展示实体（包括别家的玩家头颅展示实体）<b>一律不会被删</b>。
 *
 * <h2>★ 死代码清单（逐条，均已在 Java 里等价处理）</h2>
 * <ol>
 *   <li>{@code JavaAdapter} 退路（{@code PluginDisableEvent} 那条）—— GraalJS 25.1.3
 *       没有 {@code JavaAdapter} ⇒ 恒失败，死代码。★ 但 {@code ok} 的语义要保留：
 *       成功注册 ⇒ {@code GS_ENT_DIS_OK = true}，失败 ⇒ {@code false}，
 *       而 {@code false} 时日志打「没注册上（不影响，启动时照样会清）」。</li>
 *   <li>{@code gsEntPurge} 里「{@code worlds.iterator()} 拿不到 ⇒ {@code size()+get(i)}」
 *       的降级 —— {@code Bukkit.getWorlds()} 返回 {@code List<World>}，迭代器必然可用
 *       （编译验证）⇒ 降级走不到。保留结构（3 行、无副作用）。</li>
 *   <li>{@code gsEntListIn} 里「{@code getEntitiesByClass} 拿不到 ⇒ {@code getEntities()}」
 *       的降级 —— {@code World#getEntitiesByClass(Class)} 在 1.21.11 必然可用
 *       （编译验证）⇒ 走不到。<b>但保留</b>：它是「一个世界都扫不到实体」的最后一道保险。</li>
 *   <li>{@code gsEntUuidOf} 的 {@code try/catch} —— JS 里 {@code getUniqueId()} 可能抛；
 *       Java 里 {@code Entity#getUniqueId()} 不会抛，但保留 catch 结构（无副作用）。</li>
 *   <li>{@code gsEntIsOurs} 里 {@code '' + e.getType()} 的二次降级 ——
 *       {@code Entity#getType()} 必然返回 {@code EntityType} ⇒ 走不到，已删。</li>
 *   <li>{@code gsEntIsOurs} 的 {@code GS_ENT_TAG != null &amp;&amp; GS_BB_PDT != null} 判空 ——
 *       两个都是 {@code static final} 常量（编译期非 null）⇒ 条件恒真。保留原样
 *       （常量对账探针要读它们，而且哪天改成可空配置时这一层就是保险）。</li>
 * </ol>
 *
 * <h2>★ 监听器注册：与原脚本的差异</h2>
 * 原脚本自己 {@code pm.registerEvent(PDEv, plugin, Priority.MONITOR, …)}，
 * <b>每次 {@code /gs reload} 都会再注册一遍</b>（真实缺陷：reload 5 次 ⇒ 关服时清 5 遍）。
 * Java 侧改成 {@link ListenerImpl}（{@code org.bukkit.event.Listener}）+ {@code registerEvents}，
 * 用 {@link #GS_ENT_DIS_OK} 闩锁保证<b>只注册一次</b>。
 * <p>★★ <b>宿主插件身份的差异</b>：原脚本把监听器挂在 <b>RSC 插件</b>（{@code RykenSlimefunCustomizer}）
 * 名下，并判 {@code e.getPlugin() != plugin ⇒ return}（只管「我们自己下线那一刻」）。
 * Java 侧宿主插件换成了 {@link GS} 插件本身 ⇒ 判定等价地变成
 * 「{@code e.getPlugin() != 我们} ⇒ return」。语义<b>完全一致</b>
 * （都是「只有承载本脚本的那个插件被禁用时才清」）。
 * <p>★ 优先级 {@code MONITOR}（<b>不是</b> HIGHEST —— 这一条是 MONITOR，逐处核对过），
 * {@code ignoreCancelled} 取默认 <b>false</b>（原脚本没传第 5 个参数；Java 注解默认也是 false）。
 */
public final class EntPurgeSub {

    private final ListenerCore core;
    private final BbSub bb;
    private final BgSub bg;

    public EntPurgeSub(ListenerCore core, BbSub bb, BgSub bg) {
        this.core = core;
        this.bb = bb;
        this.bg = bg;
    }

    /**
     * 对应原脚本顶层的 {@code gsEntPurgeStart();} 调用
     * （原脚本第 13569 行，<b>全脚本最后一个</b>顶层调用 —— 见类注释）。
     */
    public void start() {
        gsEntPurgeStart();
    }

    // ======================================================================
    // 顶层常量（原脚本 11568~11572 行）
    // ======================================================================

    /** 周期清扫间隔（刻）：20 刻 = 1 秒。 */
    static final int GS_ENT_PURGE_EVERY = 20;
    /** 总开关（万一哪天觉得太勤，改成 false 即可）。 */
    static final boolean GS_ENT_PURGE_ON = true;
    /** 启动那一次清掉了几个（启动日志 / 自检用）。 */
    int GS_ENT_BOOT_N = 0;
    /** 最近一次周期清扫清掉了几个。 */
    int GS_ENT_LAST_N = 0;
    /** 关服前清扫那条监听器注册上没有。★ {@code Boolean} 三态（{@code null} = 还没试过）。 */
    Boolean GS_ENT_DIS_OK = null;

    // ======================================================================
    // 判定（原脚本 11574~11648 行）
    // ======================================================================

    /** {@code gsEntUuidOf(e)} —— 实体的 UUID 字符串（拿不到就 null ⇒ 当成「没有身份」，不保护）。 */
    static String gsEntUuidOf(Entity e) {
        try {
            if (e == null) {
                return null;
            }
            UUID u = e.getUniqueId();
            return (u == null) ? null : u.toString();
        } catch (Throwable e2) {
            return null;
        }
    }

    /**
     * {@code gsEntLiveSet()} —— 当前<b>正在被脚本管理</b>的球实体 UUID 表（这些不能删）。
     *
     * <p>★ 两张来源：{@link BbSub#GS_BB_ST}（律师函那颗）与
     * {@link BgSub#GS_BG_NODES}（每台生成器的球）。
     */
    Map<String, Boolean> gsEntLiveSet() {
        Map<String, Boolean> s = new HashMap<>();
        try {
            for (String k : new ArrayList<>(bb.GS_BB_ST.keySet())) {
                BbSub.Ball st = bb.GS_BB_ST.get(k);
                if (st == null || st.ent == null) {
                    continue;
                }
                String u = gsEntUuidOf(st.ent);
                if (u != null) {
                    s.put(u, Boolean.TRUE);
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            for (String k2 : new ArrayList<>(bg.GS_BG_NODES.keySet())) {
                BgSub.BgNode nd = bg.GS_BG_NODES.get(k2);
                if (nd == null) {
                    continue;
                }
                for (int i = 0; i < nd.balls.size(); i++) {
                    BbSub.Ball b = nd.balls.get(i);
                    if (b == null || b.ent == null) {
                        continue;
                    }
                    String u2 = gsEntUuidOf(b.ent);
                    if (u2 != null) {
                        s.put(u2, Boolean.TRUE);
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return s;
    }

    /**
     * {@code gsEntIsOurs(e)} —— 这颗实体是不是「我们的球」：
     * ① 身上有我们的标记 ② 就是那颗专属头颅。★ 别人的展示实体<b>一律不碰</b>。
     */
    boolean gsEntIsOurs(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            String tn = "";
            try {
                tn = e.getType().name();
            } catch (Throwable e1) {
                tn = "";
            }
            // ★ 只处理展示实体这一族（ItemDisplay / BlockDisplay / TextDisplay…）
            if (tn.indexOf("DISPLAY") < 0) {
                return false;
            }
            // ① 我们自己打的标记
            try {
                if (BbSub.GS_ENT_TAG != null && BbSub.GS_BB_PDT != null) {
                    Integer v = e.getPersistentDataContainer()
                            .get(BbSub.GS_ENT_TAG, PersistentDataType.INTEGER);
                    if (v != null) {
                        return true;
                    }
                }
            } catch (Throwable e3) {
                // 落到退路
            }
            // ② 退路：**老残留没有标记**（本批之前造的孤儿）⇒ 靠那颗专属头颅认
            ItemStack it = null;
            try {
                it = (e instanceof ItemDisplay) ? ((ItemDisplay) e).getItemStack() : null;
            } catch (Throwable e4) {
                it = null;
            }
            return gsEntItemIsOurs(it);
        } catch (Throwable ex) {
            return false;
        }
    }

    /**
     * {@code gsEntItemIsOurs(item)} —— 这件物品是不是我们那颗「篮球头颅」
     * （严格：只认 {@code PLAYER_HEAD} + profile UUID 相同）。
     */
    boolean gsEntItemIsOurs(ItemStack item) {
        try {
            if (item == null) {
                return false;
            }
            String tn = "";
            try {
                tn = item.getType().name();
            } catch (Throwable e0) {
                return false;
            }
            if (tn.indexOf("PLAYER_HEAD") < 0 && tn.indexOf("SKULL") < 0) {
                return false;
            }
            ItemMeta meta = null;
            try {
                meta = item.getItemMeta();
            } catch (Throwable e1) {
                meta = null;
            }
            if (meta == null) {
                return false;
            }
            UUID id = null;
            try {
                if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                    com.destroystokyo.paper.profile.PlayerProfile pr =
                            ((org.bukkit.inventory.meta.SkullMeta) meta).getPlayerProfile();
                    if (pr != null) {
                        id = pr.getId();
                    }
                }
            } catch (Throwable e2) {
                id = null;
            }
            if (id == null) {
                try {
                    if (meta instanceof org.bukkit.inventory.meta.SkullMeta) {
                        org.bukkit.profile.PlayerProfile op =
                                ((org.bukkit.inventory.meta.SkullMeta) meta).getOwnerProfile();
                        if (op != null) {
                            id = op.getUniqueId();
                        }
                    }
                } catch (Throwable e3) {
                    id = null;
                }
            }
            if (id != null && id.toString().toLowerCase(java.util.Locale.ROOT)
                    .equals(BbSub.GS_BB_HEAD_UUID.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
            // ★ profile 读不出来时再退一步：整件物品和我们那颗头颅「几乎相同」才算
            //   （同样的材质 + 同样的皮肤 ⇒ 实际只可能是我们自己的球）
            ItemStack our = bb.gsBbHeadItem();
            if (our != null) {
                try {
                    if (item.isSimilar(our)) {
                        return true;
                    }
                } catch (Throwable e4) {
                    // 与原脚本一致：静默
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsEntListIn(w)} —— 取一个世界里能拿到的展示实体集合。 */
    static Collection<? extends Entity> gsEntListIn(World w) {
        try {
            try {
                Collection<ItemDisplay> c = w.getEntitiesByClass(ItemDisplay.class);
                if (c != null) {
                    return c;
                }
            } catch (Throwable e) {
                // 落到退路
            }
            return w.getEntities();
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 清扫（原脚本 11662~11706 行）
    // ======================================================================

    /**
     * {@code gsEntPurge(force)} —— ★ 清扫。
     *
     * @param force {@code true} ⇒ <b>全删</b>（启动 / 关服）；
     *              {@code false} ⇒ 只删「不在状态表里」的孤儿
     * @return 清掉了几个
     */
    int gsEntPurge(boolean force) {
        int n = 0;
        try {
            if (!GS_ENT_PURGE_ON) {
                return 0;
            }
            Map<String, Boolean> live = force ? null : gsEntLiveSet();

            // 世界列表
            List<World> wl = new ArrayList<>();
            try {
                List<World> worlds = Bukkit.getServer().getWorlds();
                try {
                    Iterator<World> itw = worlds.iterator();
                    while (itw.hasNext()) {
                        wl.add(itw.next());
                    }
                } catch (Throwable e1) {
                    // ★ 降级：size + get（见类注释死代码第 2 条）
                    int wn = 0;
                    try {
                        wn = worlds.size();
                    } catch (Throwable e1b) {
                        wn = 0;
                    }
                    for (int wi = 0; wi < wn; wi++) {
                        wl.add(worlds.get(wi));
                    }
                }
            } catch (Throwable e0) {
                wl = new ArrayList<>();
            }

            for (int i = 0; i < wl.size(); i++) {
                World w = wl.get(i);
                if (w == null) {
                    continue;
                }
                Collection<? extends Entity> list = gsEntListIn(w);
                if (list == null) {
                    continue;
                }
                // 先拷成数组再删（「边遍历边删」在真机上会漏项 / 抛异常）
                List<Entity> arr = new ArrayList<>();
                try {
                    Iterator<? extends Entity> it = list.iterator();
                    while (it.hasNext()) {
                        arr.add(it.next());
                    }
                } catch (Throwable e2) {
                    continue;
                }
                for (int j = 0; j < arr.size(); j++) {
                    Entity e = arr.get(j);
                    if (!gsEntIsOurs(e)) {
                        continue;
                    }
                    if (!force && live != null) {
                        String u = gsEntUuidOf(e);
                        if (u != null && Boolean.TRUE.equals(live.get(u))) {
                            continue;                  // 正在用的 ⇒ 放过
                        }
                    }
                    try {
                        e.remove();
                        n++;
                    } catch (Throwable e5) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return n;
    }

    /** {@code gsEntPulse()} —— 周期清扫（挂在心跳上）。 */
    void gsEntPulse() {
        try {
            GS_ENT_LAST_N = gsEntPurge(false);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 关服清扫监听器（原脚本 11714~11748 行）
    // ======================================================================

    /**
     * 关服 / reload 前再全清一遍（宿主插件被禁用 = 我们要下线了）。
     *
     * <p>★ 优先级是 {@code MONITOR}（<b>不是</b> HIGHEST —— 这一条与其它子系统不同，
     * 逐处核对过），{@code ignoreCancelled} = false。
     */
    static final class ListenerImpl implements Listener {

        private final EntPurgeSub sub;

        ListenerImpl(EntPurgeSub sub) {
            this.sub = sub;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onPluginDisable(PluginDisableEvent e) {
            try {
                Plugin pl = null;
                try {
                    pl = e.getPlugin();
                } catch (Throwable e1) {
                    pl = null;
                }
                Plugin self = GS.pluginOrNull();
                if (pl != null && self != null && !pl.equals(self)) {
                    return;                            // ★ 只管「我们自己」下线那一刻
                }
                sub.gsEntPurge(true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 注册关服清扫监听器。
     *
     * <p>★ 与原脚本一致：成功 ⇒ {@link #GS_ENT_DIS_OK} = {@code true}，
     * 失败 ⇒ {@code false}（日志会打「没注册上（不影响，启动时照样会清）」）。
     * ★ Java 侧用闩锁保证只注册一次（原脚本每次 reload 都再注册一遍 ⇒ 关服时清 N 遍）。
     *
     * @return 注册是否成功
     */
    boolean gsEntRegisterDisable() {
        if (Boolean.TRUE.equals(GS_ENT_DIS_OK)) {
            return true;
        }
        try {
            Plugin plugin = GS.pluginOrNull();
            if (plugin == null) {
                GS_ENT_DIS_OK = Boolean.FALSE;
                return false;
            }
            try {
                Bukkit.getPluginManager().registerEvents(new ListenerImpl(this), plugin);
                GS_ENT_DIS_OK = Boolean.TRUE;
                return true;
            } catch (Throwable e3) {
                // ★ 原脚本的 JavaAdapter 退路在 GraalJS 25.1.3 里不存在 ⇒ 死代码。
                GS_ENT_DIS_OK = Boolean.FALSE;
                return false;
            }
        } catch (Throwable e) {
            GS_ENT_DIS_OK = Boolean.FALSE;
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 11708~11759 行）
    // ======================================================================

    /**
     * 原脚本 {@code gsEntPurgeStart()}。
     *
     * <p>★★ <b>必须最后调用</b>（见类注释）。
     * <p>★ 周期清扫用<b>双参同值</b>形式：{@code runRepeating(gsEntPulse, 20, 20)}
     * —— 首次延迟 20 刻、之后每 20 刻一次（<b>不是</b> 1 刻，与其它三个子系统不同）。
     */
    void gsEntPurgeStart() {
        try {
            // ① 启动清扫：这一刻内存里没有任何合法球 ⇒ 全删（各模块随后自己重建）
            GS_ENT_BOOT_N = gsEntPurge(true);
            // ② 周期清扫
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsEntPulse();
                }
            }, GS_ENT_PURGE_EVERY, GS_ENT_PURGE_EVERY);
            // ③ 关服 / reload 前再全删一遍（宿主插件被禁用 = 我们要下线了）
            gsEntRegisterDisable();
            ListenerCore.gsDaLog(gsEntPurgeStartLine(GS_ENT_BOOT_N, GS_ENT_DIS_OK));
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("孤儿球清扫启动异常: " + e);
        }
    }

    // ======================================================================
    // 日志行（★ 抽成静态方法只为让探针逐字节对账，不改任何行为）
    // ======================================================================

    /**
     * 加载那行（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 两个数字都要走 {@link JsNum}：
     * {@code GS_ENT_BOOT_N} 是整数（{@code 0} → {@code "0"}）；
     * {@code GS_ENT_PURGE_EVERY / 20.0} 在 JS 里是<b>浮点除法</b> ⇒ {@code 20 / 20.0 = 1}
     * ⇒ 拼出 {@code "1"}（<b>不是</b> {@code "1.0"}）。Java 里必须写成
     * {@code (double) GS_ENT_PURGE_EVERY / 20.0} —— 写成 {@code / 20} 会得到 int 1，
     * 恰好也对，但语义不同（改周期就错）。
     * <p>★ 条件文案：{@code GS_ENT_DIS_OK === true} ⇒ {@code 已注册}，
     * 否则 {@code 没注册上（不影响，启动时照样会清）}。
     * 对账金标准：{@code docs/参考服日志_构思技艺.txt} 第 45 行
     * （真 JS 打出来的就是 {@code 启动清了 0 个残留} + {@code 之后每 1 秒扫一次孤儿} + {@code 已注册}）。
     */
    static String gsEntPurgeStartLine(int bootN, Boolean disOk) {
        return "展示实体「孤儿球」清扫已加载 · 启动清了 " + JsNum.str((double) bootN) + " 个残留"
                + " / 之后每 " + JsNum.str((double) GS_ENT_PURGE_EVERY / 20.0) + " 秒扫一次孤儿"
                + " / 关服前再全清一遍（关服清扫监听 = "
                + (Boolean.TRUE.equals(disOk) ? "已注册" : "没注册上（不影响，启动时照样会清）") + "）"
                + " / ★ 认球只认「我们的标记 + 专属头颅」⇒ 别人的展示实体一律不碰";
    }
}
