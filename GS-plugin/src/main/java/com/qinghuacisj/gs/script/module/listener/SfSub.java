package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GSPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code gs_listener.js} 的「海水过滤器（钓鱼获取）」子系统
 * （原脚本第 <b>13571~13671</b> 行，顶层入口 {@code gsSfStart();} 在第 13671 行）——
 * ★ 第五十八批（2026-10-11）新增。
 *
 * <h2>玩法</h2>
 * 钓鱼钓上来东西时（{@code PlayerFishEvent} 的 {@code CAUGHT_FISH} 状态）
 * 有 <b>7%</b> 概率额外掉 1 个「海水过滤器」（{@code GS_SEA_FILTER}）。
 * ★ <b>不取消原版钓鱼</b> —— 鱼照常进背包，过滤器是<b>额外</b>掉在地上的。
 *
 * <h2>★★ 7% 的判定方式（逐字照抄，别改成 {@code Math.random() < 0.07}）</h2>
 * 原脚本第 13608 行是：
 * <pre>
 *   if (Math.floor(Math.random() * 100) &gt;= GS_SF_CHANCE) return;   // 93% 直接不给
 * </pre>
 * 也就是「<b>把 {@code [0,1)} 的随机数放大 100 倍后向下取整，落在 {@code [0,100)}
 * 的整数上，&gt;= 7 就跳过</b>」⇒ 命中集合是 {@code {0,1,2,3,4,5,6}}，
 * 恰好 7 个整数 / 100 ⇒ <b>7%</b>。
 * <p>★ 这里<b>不</b>用 {@code Math.random() < 7/100.0}：
 * 两者虽然概率相同，但<b>判定表达式不同</b>（浮点比较 vs 整数比较），
 * 在 {@code Math.random()} 恰好落在边界附近的实现上会有可观察差异。
 * Java 侧逐字保留 {@link #gsSfSkip(double)} 这个<b>同一个表达式</b>
 * （抽成静态方法只为让探针能在网格上对账）。
 *
 * <h2>★ 掉落位置：优先浮漂，兜底脚下</h2>
 * <ol>
 *   <li>拿得到 {@code e.getHook()} ⇒ 用<b>浮漂位置</b>（= 原版鱼掉落的地方），
 *       先 {@code dropItemNaturally} 再降级 {@code dropItem}。</li>
 *   <li>拿不到 / 两次都抛 ⇒ 兜底掉在<b>玩家脚下</b>（同样先 naturally 再 dropItem）。</li>
 * </ol>
 * 四级 {@code try/catch} 的<b>顺序与粒度</b>逐字保留：任一级成功就 {@code return true}，
 * 后面几级不再尝试。
 *
 * <h2>★ 监听器注册：原脚本会累积，Java 版改为幂等</h2>
 * 原脚本用 {@code pm.registerEvent(FishEv, plugin, Priority.MONITOR, fn, plugin)}
 * 在脚本里<b>自己注册</b>，而且 {@code GS_SF_REGISTERED} 闩锁是<b>脚本级变量</b>
 * ⇒ {@code /rsc reload} 之后闩锁归零、监听器<b>再注册一遍</b>（真实缺陷：每次
 * reload 多收一份事件，7% 会变成 14% / 21% …）。
 * <p>Java 版写成 {@link Listener} 实现类 + {@code registerEvents}，
 * 由实例字段 {@link #GS_SF_REGISTERED} <b>幂等注册一次</b> ——
 * 事件照常收到、优先级照常（{@code MONITOR}）、取消照常，
 * 但 reload 不再累积。这是<b>修掉一个真实缺陷</b>，不是「顺手优化」。
 *
 * <p>★ <b>{@code ignoreCancelled} 逐处核对</b>：原脚本的 {@code registerEvent}
 * <b>没有第 5 个参数</b> ⇒ {@code ignoreCancelled} 默认 <b>false</b>。
 * Java 的 {@code @EventHandler(priority = ...)} 默认也是 false ⇒
 * 这里<b>不写</b> {@code ignoreCancelled}（写了 {@code true} 就是行为改变）。
 * <p>★ 原脚本还试过一条 {@code JavaAdapter} 退路（第 13644~13647 行）——
 * 见类注释末尾的「死代码」。
 *
 * <h2>★ 死代码清单（逐条）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 13641~13648 行）：正路
 *       {@code pm.registerEvent(...)} 失败时才走。GraalJS 25.1.3 里
 *       {@code JavaAdapter} <b>不存在</b>（{@code tools/GraalProbe.java} 实测），
 *       所以这条路在真 JS 里也是<b>必然抛异常</b>的那条（抛了就被 {@code catch(e3)}
 *       吞掉并置 {@code GS_SF_REGISTERED = false}）。
 *       Java 侧 {@code registerEvents} 一次到位 ⇒ 该退路不存在。
 *       <b>删掉它不改变可观察行为</b>：只有「正路失败」时才会走到，
 *       而正路在 Java 里不可能失败（{@code PlayerFishEvent} 必然存在）。</li>
 *   <li><b>plugin 四级探测链</b>（原脚本 13625~13630 行：
 *       {@code RykenSlimefunCustomizer.INSTANCE} → {@code getPlugin('RykenSlimeCustomizer')}
 *       → {@code getPlugin('RykenSlimefunCustomizer')} → {@code getPlugin('Slimefun')}）：
 *       那是 RSC 脚本为了「拿到一个合法的 plugin 实例」而做的兜底，
 *       在原生附属里 {@code GSPlugin.instance()} 就是确定的那个实例 ⇒ 整链删除。</li>
 *   <li><b>{@code FishEv == null} 的早退</b>（原脚本 13632~13634 行）：
 *       {@code Java.type('org.bukkit.event.player.PlayerFishEvent')} 在 1.21.11 上
 *       必然成功 ⇒ 该分支恒不执行。</li>
 *   <li><b>{@code if (e == null) return;} / {@code if (st == null) return;} /
 *       {@code if (p == null) return;}</b>（13602/13604/13607 行）：Java 的事件
 *       参数与 {@code getPlayer()} 在真实调度下恒非 null —— 但<b>保留</b>了，
 *       因为它们是<b>防御式早退</b>而不是不可达分支（探针会直接调 {@code gsSfOnFish(null)}）。</li>
 * </ol>
 */
public final class SfSub {

    // ======================================================================
    // 参数：名字与 JS 顶层 var 同名（常量对账探针按名字反射比对）
    // ======================================================================

    /** {@code GS_SF_ID = 'GS_SEA_FILTER'}（原脚本 13578 行）。 */
    static final String GS_SF_ID = "GS_SEA_FILTER";

    /** {@code GS_SF_CHANCE = 7} —— 钓率 7%（原脚本 13579 行）。 */
    static final int GS_SF_CHANCE = 7;

    /** {@code GS_SF_AMOUNT = 1} —— 每次掉 1 个（原脚本 13580 行）。 */
    static final int GS_SF_AMOUNT = 1;

    /**
     * {@code GS_SF_REGISTERED} —— 注册闩锁（原脚本 13581 行）。
     *
     * <p>★ 原脚本里它<b>只写不读</b>（唯一一处读是 {@code if (GS_SF_REGISTERED) return true;}
     * 在 {@link #gsSfRegisterSync()} 开头），而且 reload 后归零 ⇒ 挡不住监听器累积。
     * Java 侧做成<b>实例字段</b>：{@code ListenerModule} 只有一个实例，
     * 「实例字段 + 只注册一次」天然等价于原脚本想要的闩锁语义。
     */
    boolean GS_SF_REGISTERED = false;

    private final ListenerCore core;

    /** 监听器实例（懒建，{@code registerEvents} 只调一次）。 */
    private Sync listener;

    public SfSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsSfStart();} 调用（原脚本第 13671 行）。 */
    public void start() {
        gsSfStart();
    }

    // ======================================================================
    // 掉落（原脚本 13584~13597 行）
    // ======================================================================

    /**
     * {@code gsSfDrop(p, hookLoc)} —— 掉 1 个海水过滤器。
     *
     * <p>★ 四级降级<b>逐字保留</b>：① 浮漂 + {@code dropItemNaturally}
     * ② 浮漂 + {@code dropItem} ③ 脚下 + {@code dropItemNaturally}
     * ④ 脚下 + {@code dropItem}。任一级成功立即 {@code return true}。
     *
     * @return 有没有掉出去（原脚本的返回值只被忽略，但保留签名以便对账）
     */
    static boolean gsSfDrop(Player p, Location hookLoc) {
        ItemStack it = ListenerCore.gsNewItem(GS_SF_ID);
        if (it == null) {
            return false;
        }
        try {
            it.setAmount(GS_SF_AMOUNT);
        } catch (Throwable e0) {
            // 与原脚本一致：静默
        }
        // ① 优先浮漂位置（= 原版鱼掉落的地方）
        if (hookLoc != null) {
            try {
                p.getWorld().dropItemNaturally(hookLoc, it);
                return true;
            } catch (Throwable e1) {
                // 降级到 dropItem
            }
            try {
                p.getWorld().dropItem(hookLoc, it);
                return true;
            } catch (Throwable e2) {
                // 降级到玩家脚下
            }
        }
        // ② 兜底：玩家脚下
        try {
            p.getWorld().dropItemNaturally(p.getLocation(), it);
            return true;
        } catch (Throwable e3) {
            // 降级到 dropItem
        }
        try {
            p.getWorld().dropItem(p.getLocation(), it);
            return true;
        } catch (Throwable e4) {
            // 与原脚本一致：静默
        }
        return false;
    }

    // ======================================================================
    // 钓鱼回调（原脚本 13600~13616 行）
    // ======================================================================

    /**
     * {@code gsSfOnFish(e)} —— 钓鱼回调。
     *
     * <p>★ 只认 {@code CAUGHT_FISH}：原脚本写的是
     * {@code if (('' + st) !== 'CAUGHT_FISH') return;} ——
     * 枚举 → 字符串取的是 {@code name()}。Java 用
     * {@code String.valueOf(st).equals("CAUGHT_FISH")}，逐字等价。
     * <p>★ 原脚本注释点名的那几个状态（{@code CAUGHT_ENTITY / IN_GROUND / FISHING}）
     * <b>一个都不认</b>，别「顺手」加上。
     */
    void gsSfOnFish(PlayerFishEvent e) {
        try {
            if (e == null) {
                return;
            }
            PlayerFishEvent.State st = e.getState();
            if (st == null) {
                return;
            }
            if (!String.valueOf(st).equals("CAUGHT_FISH")) {
                return;                            // 只认「钓上来了」
            }
            Player p = e.getPlayer();
            if (p == null) {
                return;
            }
            if (gsSfSkip(Math.random())) {
                return;                            // 93% 直接不给
            }
            Location hookLoc = null;
            try {
                FishHook hook = e.getHook();
                if (hook != null) {
                    hookLoc = hook.getLocation();
                }
            } catch (Throwable eH) {
                hookLoc = null;
            }
            gsSfDrop(p, hookLoc);
        } catch (Throwable e2) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 原脚本第 13608 行那个判定式的<b>本体</b>，抽成静态方法只为让探针能在网格上对账：
     * <pre>
     *   Math.floor(Math.random() * 100) &gt;= GS_SF_CHANCE
     * </pre>
     *
     * <p>★ 参数是 {@code Math.random()} 的<b>返回值</b>（{@code [0,1)}），
     * 返回值 {@code true} 表示「跳过（不给）」。
     * <p>★ 为什么不用 {@code Math.random() < 0.07}：概率相同但表达式不同，
     * 详见类注释。这里连 {@code Math.floor} 都照抄（{@code rnd * 100} 恒非负，
     * {@code floor} 与 {@code (int)} 在 {@code [0,100)} 上等价，
     * 但保留 {@code floor} 才能与真 JS 逐字对账）。
     */
    static boolean gsSfSkip(double rnd) {
        return Math.floor(rnd * 100) >= GS_SF_CHANCE;
    }

    // ======================================================================
    // 监听器（原脚本 13618~13655 行的 registerEvent）
    // ======================================================================

    /**
     * 原脚本第 13637~13638 行那一处 {@code registerEvent} 的 Java 版。
     *
     * <p>★ 优先级 {@code Priority.MONITOR}（<b>不是</b> HIGHEST —— 这个子系统
     * 只看结果、不改事件），{@code ignoreCancelled} 保持默认 <b>false</b>。
     * <p>★ {@code MONITOR} 这一档 Bukkit 有额外约束：<b>不许</b>在 MONITOR 里
     * 修改事件。本类只读事件、往世界里掉物品 ⇒ 合法（原脚本也是这么写的）。
     */
    final class Sync implements Listener {

        /** ① {@code PlayerFishEvent}：钓上来了 → 7% 额外掉一个过滤器。 */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onFish(PlayerFishEvent e) {
            gsSfOnFish(e);
        }
    }

    /**
     * {@code gsSfRegisterSync()} —— 幂等注册（原脚本 13619~13655 行）。
     *
     * <p>★ 原脚本的回退链（{@code JavaAdapter}）与 plugin 探测链已在类注释里
     * 逐条说明为什么可以删；返回值的语义<b>保留</b>：
     * {@code true} = 注册成功（⇒ 日志打「监听 PlayerFishEvent · 状态 CAUGHT_FISH」），
     * {@code false} = 没注册上（⇒ 日志打「★监听没注册上★」）。
     */
    boolean gsSfRegisterSync() {
        if (GS_SF_REGISTERED) {
            return true;                           // ★ Java 版幂等闩锁（原脚本只写不读）
        }
        try {
            if (listener == null) {
                listener = new Sync();
            }
            Bukkit.getPluginManager().registerEvents(listener, GSPlugin.instance());
            GS_SF_REGISTERED = true;
            return GS_SF_REGISTERED;
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 海水过滤器钓鱼监听注册异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 13657~13671 行）
    // ======================================================================

    /**
     * {@code gsSfStart()} —— 注册监听器 + 打加载日志（原脚本 13657~13669 行）。
     *
     * <p>★ 日志走 {@code getServer().getLogger().info(...)} 而<b>不是</b>
     * {@code gsDaLog}：因为字符串里已经带了 {@code [构思技艺] } 前缀，
     * 而 {@code gsDaLog} 会再补一个。逐字照抄原脚本的写法。
     * <p>★ 内层那个 {@code try/catch}（只包住打日志）也必须保留 ——
     * 它保证「日志打不出来」不会让外层 catch 再打一条「启动异常」。
     */
    public void gsSfStart() {
        try {
            boolean ok = gsSfRegisterSync();
            try {
                Bukkit.getServer().getLogger().info(gsSfStartLine(ok));
            } catch (Throwable e1) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 海水过滤器钓鱼启动异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 原脚本 13661~13664 行的加载日志（<b>含</b> {@code [构思技艺] } 前缀）。
     *
     * <p>★ 抽成静态方法<b>只为让探针逐字节对账</b>，不改任何行为。
     * 对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 46 行）：
     * <pre>
     * [构思技艺] 「海水过滤器」钓鱼获取已加载（监听 PlayerFishEvent · 状态 CAUGHT_FISH · 钓率 7% · 每次 1 个 · 不取消原版钓鱼）
     * </pre>
     * ★ 注意参考服日志里这一行<b>没有被截断</b>（自己核过：整行 56 个字，
     * 完整落在 RSC 的日志长度上限内）—— 所以<b>整行</b>逐字节比对。
     *
     * @param ok 监听器注册成功 ⇒ 正常文案；失败 ⇒ {@code ★监听没注册上★}（原样保留星号）
     */
    static String gsSfStartLine(boolean ok) {
        return "[构思技艺] 「海水过滤器」钓鱼获取已加载（"
                + (ok ? "监听 PlayerFishEvent · 状态 CAUGHT_FISH" : "★监听没注册上★")
                + " · 钓率 " + GS_SF_CHANCE + "% · 每次 " + GS_SF_AMOUNT + " 个"
                + " · 不取消原版钓鱼）";
    }
}
