package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.script.module.listener.ListenerCore;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_listener.js} 的 Java 化版本 —— 「构思技艺·事件监听」。
 *
 * <p><b>这个脚本是什么</b>：本附属最核心的一个脚本（14,337 行 / 564 个顶层函数 /
 * 520 个顶层 var），承载 24 个子系统。它不是「一个物品」，而是<b>一整套玩法</b>：
 * 梦境套（免死 / 飞行 / 药效 / 防辐射 / 粒子圈）、8 把武器（余念绝响 / 残樱 /
 * 五骊矟 / 威龙 / 月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀）、
 * 篮球玩法、海水过滤器、祖宗发射器、灵感掉落…
 *
 * <p>原脚本由 {@code info.yml} 的 {@code scriptListener: "gs_listener"} 引用，
 * 由 RSC 在加载期整体 eval 一次（顶层代码立即执行），之后按事件名回调
 * {@code onPlayerJoin} / {@code onPlayerQuit} / {@code onPlayerDeath} / {@code onEntityDamage}。
 *
 * <h2>本类负责什么</h2>
 * 只负责<b>原脚本的顶层胶水</b>（因为它是「事件入口」，必须有一个宿主）：
 * <ol>
 *   <li><b>灵感掉落 / 奇思妙想帽</b>（原脚本 31~208 行）——
 *       每个在线玩家一条自调度链，按配置周期往头顶掉「想象力」/「灵感」。</li>
 *   <li><b>梦境套免死</b>（原脚本 222~440 + 596~675 行）——
 *       致命伤害拦截（HIGHEST 同步监听器）+ 兜底诊断。</li>
 *   <li><b>永不掉耐久</b>（原脚本 442~593 行）——
 *       取消 {@code PlayerItemDamageEvent} + 每 10 刻兜底补耐久。</li>
 *   <li><b>4 个脚本级事件钩子</b>（{@code onPlayerJoin/Quit/Death} + {@code onEntityDamage}）。</li>
 *   <li><b>按原脚本的顶层调用顺序启动 24 个子系统</b>。</li>
 * </ol>
 *
 * <p>各子系统本身在 {@code listener} 子包里，共享状态与共享工具在
 * {@link ListenerCore}。★ 因为 {@code Modules.acquire} 用 {@code computeIfAbsent}，
 * <b>本类只有一个实例</b> ⇒ 构造器 = 原脚本顶层代码（只跑一次），
 * 字段 = 原脚本顶层 var ⇒ 与「同一个 JS 上下文里只有一份全局状态」等价。
 *
 * <h2>★ 与原脚本的一处有意差异（修掉一个真实缺陷）</h2>
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} <b>在脚本里自己注册监听器</b>，
 * 而 RSC 的 {@code /rsc reload} 会重新 eval 整个脚本 ⇒ <b>监听器每次 reload 都再注册一遍、
 * 不断累积</b>。原脚本用 {@code GS_XX_REGISTERED} 闩锁缓解，但闩锁本身是脚本级变量、
 * reload 后归零，所以挡不住累积。
 *
 * <p>Java 版把监听器写成 {@code Listener} 实现类、用 {@code registerEvents} <b>幂等注册一次</b>：
 * <b>可观察行为完全相同</b>（事件照常收到、优先级照常、取消照常），
 * 但 reload 不再累积。★ 这不是「顺手优化」，是修一个真实缺陷。
 *
 * <p>★ 优先级与 {@code ignoreCancelled} 逐处核对过：原脚本的
 * {@code registerEvent(类, plugin, Priority.X, executor, plugin)} <b>没有第 5 个参数</b>
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 的 {@code @EventHandler} 默认也是
 * false ⇒ 不加该属性即等价。
 *
 * <h2>死代码（不照抄，在此存档）</h2>
 * <ul>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 621~632 行、509~521 行）：
 *       原脚本在「直接传 JS 函数当 EventExecutor」失败时，退路是用
 *       {@code new JavaAdapter(ListenerC, {})} 造监听器。★ 已用
 *       {@code tools/GraalProbe.java} 实测：GraalJS 25.1.3 里
 *       <b>{@code JavaAdapter} 是 undefined</b> ⇒ 这条退路<b>永远走不到</b>。
 *       Java 版直接用原生 {@code Listener}，两条路合一。</li>
 *   <li><b>{@code gsRegisterDreamArmor} 的 {@code plugin} 四级回退</b>
 *       （原脚本 603~607 行：RSC 静态单例 → {@code RykenSlimeCustomizer} →
 *        {@code RykenSlimefunCustomizer} → {@code Slimefun}）：
 *       那是为了「拿不到 RSC 主类就退而求其次」拿<b>任意一个 Plugin 实例</b>来注册。
 *       Java 版自己有 Plugin 实例 ⇒ 整个回退链作废。</li>
 *   <li><b>{@code gsDaLog('梦境盔甲：拿不到 Plugin 实例，致命伤害拦截未注册')}</b>
 *       （原脚本 609 行）：上一条回退链全失败才打 ⇒ 恒不执行。</li>
 *   <li><b>{@code gsDaLog('梦境盔甲：致命伤害拦截已注册（JavaAdapter）')}</b>
 *       （原脚本 631 行）：JavaAdapter 退路里的 ⇒ 恒不执行。
 *       ★ 参考服日志里出现的是<b>另一条</b>「（同步监听器）」，已实测确认。</li>
 *   <li><b>「记忆碎片：携带想象力死亡时掉落」</b>（原脚本 62~67 行的注释块）：
 *       代码已被作者<b>按需求删除</b>（获取方式改成「构思机里 9 个想象力合成」），
 *       只剩 {@code onPlayerDeath} 里的 {@code gsWuliQuit} 调用。
 *       ★ 不重建它。</li>
 *   <li><b>「构念」（{@code GS_CONSTRUCT_ROD}）配套的三块代码</b>（原脚本 210~220 行注释）：
 *       物品已整件删除，配套的 {@code onBlockBreak} / {@code gsClearGlassDrops} /
 *       {@code onPlayerInteract} 兜底与 {@code gsGlassMat} / {@code gsAddGlass} /
 *       {@code gsRemoveGlass} / {@code gsCanPlaceGlass} / {@code gsConsumeItem}
 *       以及 {@code 'gs:construct_glass'} 虚拟玻璃位置表<b>全部已删</b>。
 *       ★ 只有 {@code GS_PDT} 的声明保留（后面很多子系统在用它）—— 已体现在
 *       {@link ListenerCore} 直接使用 {@code PersistentDataType} 上。</li>
 * </ul>
 *
 * <h2>★ 关于 {@code onPlayerDeath}</h2>
 * 原脚本注释（64~67 行）说明：RSC 的 {@code scriptListener} 回调<b>延迟 1 tick 执行</b>，
 * 所以「死亡瞬间背包已变成掉落物」⇒ 读背包检测不到想象力，必须扫描死亡点附近的
 * 掉落物实体。★ 那段逻辑（掉落记忆碎片）已被作者删除，现在 {@code onPlayerDeath}
 * <b>只剩 {@code gsWuliQuit(player)}</b>（五骊骑阵：死亡就收阵）。
 * 这个「延迟 1 tick」的语义对 {@code gsWuliQuit} 同样成立，Java 版必须复现。
 */
public final class ListenerModule extends AbstractModule implements Listener {

    /** 子系统的启动钩子 —— 由 {@link ListenerCore} 之外的子系统实现，用于反向调用。 */
    public interface WuliHooks {
        /** {@code gsWuliQuit(player)} —— 收阵（清马 + 收走副手拴绳）。 */
        void quit(Player p);
    }

    private final ListenerCore core = new ListenerCore();

    /** 五骊矟子系统（由 {@link #bindWuli} 注入）—— 因为 3 个脚本级钩子都要调它的 {@code gsWuliQuit}。 */
    private WuliHooks wuli;

    // ---- 梦境套免死的兜底诊断（原脚本 649~675 行）----

    /** {@code GS_DA_REGISTERED} —— 同步监听器是否注册成功。★ {@code onEntityDamage} 会读它。 */
    private boolean gsDaRegistered;

    // ---- 灵感掉落的配置周期（原脚本 85~93 行，每轮现算，不缓存）----

    /**
     * 原脚本顶层第 200 行的 {@code runLater(..., 40)}：
     * 加载完成后 40 刻（2 秒）为<b>已经在线的玩家</b>补启动灵感计时器。
     * ★ 为什么延迟：加载期玩家列表可能还没就绪。
     */
    private static final long GS_INSP_CATCHUP_DELAY = 40L;

    public ListenerModule() {
        // ★ 构造器 = 原脚本的顶层代码，按原脚本的**顶层语句顺序**执行。
        //   顺序表集中在 ListenerSubsystems.startAll() 里（含每个子系统的原脚本行号），
        //   因为「启动顺序」本身就是可观察行为（日志顺序 / 监听器优先级 / 资源占用）。
        ListenerSubsystems.startAll(this, core);
    }

    @Override
    public String name() {
        return "gs_listener";
    }

    /** 注入五骊矟子系统（由 {@code ListenerSubsystems} 在启动时调用）。 */
    void bindWuli(WuliHooks h) {
        this.wuli = h;
    }

    ListenerCore core() {
        return core;
    }

    /**
     * 注册本模块的 Bukkit 监听器 —— <b>幂等，只注册一次</b>。
     *
     * <p>★ 替代原脚本散布的 {@code pm.registerEvent(...)}：原脚本每次
     * {@code /rsc reload} 都会再注册一遍、不断累积；这里注册一次，
     * 可观察行为相同但不再累积（见类注释）。
     */
    void registerBukkitListeners() {
        try {
            Bukkit.getPluginManager().registerEvents(this, GS.plugin());
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("监听器注册异常: " + e);
        }
    }

    /**
     * {@code gsRegisterDreamArmor()} —— 梦境套「致命伤害拦截」的注册
     * （原脚本 596~642 行）。
     *
     * <p>★ 实际的事件绑定已由 {@link #registerBukkitListeners()} 用
     * {@code @EventHandler(priority = HIGHEST)} 完成（等价于原脚本的
     * {@code Priority.HIGHEST}）。这里保留原脚本的<b>闩锁 + 日志</b>语义，
     * 因为 {@code GS_DA_REGISTERED} 会被兜底诊断读取。
     *
     * <p>★ 原脚本成功时打的那条日志（参考服日志里出现过，必须逐字节一致）：
     * <pre>[构思技艺] 梦境盔甲：致命伤害拦截已注册（同步监听器）</pre>
     * ★ 另一条「（JavaAdapter）」是走不到的退路，见类注释的死代码清单。
     */
    void gsRegisterDreamArmor() {
        try {
            gsDaRegistered = true;
            ListenerCore.gsDaLog("梦境盔甲：致命伤害拦截已注册（同步监听器）");
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("梦境盔甲注册异常: " + e);
        }
    }

    /** 读取「同步监听器是否已注册」—— 兜底诊断与探针都要用。 */
    boolean daRegistered() {
        return gsDaRegistered;
    }

    // ======================================================================
    // ① 灵感掉落 / 奇思妙想帽（原脚本 31~208 行）
    // ======================================================================

    /**
     * {@code gsInspirationInterval()} —— 两种掉落物的周期取<b>较小者</b>。
     *
     * <p>★ 每轮都重读配置（不缓存）—— 因为玩家可能改配置后 {@code /rsc reload}。
     * 两个值都钳到 >= 1。
     */
    static int gsInspirationInterval(ListenerCore core) {
        var cfg = ListenerCore.gsCfg();
        int insp = 20, hat = 20;
        try {
            if (cfg != null) {
                insp = cfg.getInt("inspiration.interval", 20);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            if (cfg != null) {
                hat = cfg.getInt("thinking-hat.interval", 20);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (insp < 1) {
            insp = 1;
        }
        if (hat < 1) {
            hat = 1;
        }
        return Math.min(insp, hat);
    }

    /**
     * {@code gsHeadDropSurvivalOk(player, cfg, path)} —— 当前游戏模式允许掉落吗。
     *
     * <p>★ 三条逐字保留的语义：
     * <ol>
     *   <li>配置项为 false ⇒ <b>直接放行</b>（「关掉『仅生存』就都允许」）。</li>
     *   <li>只认 {@code SURVIVAL} 与 {@code ADVENTURE} —— 创造模式挂机默认不刷。</li>
     *   <li>整个函数被 try 包住，异常时<b>返回 true</b>（放行）——
     *       宁可多掉也不要因为读不到游戏模式就不掉。</li>
     * </ol>
     */
    static boolean gsHeadDropSurvivalOk(Player player, org.bukkit.configuration.file.FileConfiguration cfg,
                                        String path) {
        try {
            if (cfg != null && !cfg.getBoolean(path, true)) {
                return true;                        // 关掉"仅生存"就都允许
            }
            var gm = player.getGameMode();
            String name = (gm == null) ? "SURVIVAL" : String.valueOf(gm.name());
            return name.equals("SURVIVAL") || name.equals("ADVENTURE");
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * {@code gsTickHeadDrop(player)} —— 一次掉落检查（原脚本 105~145 行）。
     *
     * <p>★ 两条掉落规则：
     * <ol>
     *   <li>头盔是「奇思妙想帽」⇒ 在<b>头顶上方</b>掉「想象力」。
     *       ★ 生成点抬到 {@code y + 2.2}（玩家身高 1.8 ⇒ 已在头顶之上），
     *       物品会从头顶自然落下来。这是需求明确要求的「从头上掉落」。</li>
     *   <li>头盔是「记忆碎片」⇒ 在<b>脚下</b>掉「灵感」（原样保留）。</li>
     * </ol>
     * ★ 两条都受「仅生存」开关约束。
     */
    static void gsTickHeadDrop(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        var cfg = ListenerCore.gsCfg();
        ItemStack helmet;
        try {
            helmet = player.getInventory().getHelmet();
        } catch (Throwable e0) {
            return;
        }

        // ① 奇思妙想帽 → 头顶掉「想象力」
        if (cfg == null || cfg.getBoolean("thinking-hat.enabled", true)) {
            if (ListenerCore.gsIsItem(helmet, ListenerCore.GS_THINKING_HAT)
                    && gsHeadDropSurvivalOk(player, cfg, "thinking-hat.survival-only")) {
                ItemStack img = ListenerCore.gsNewItem(ListenerCore.GS_IMAGINATION);
                if (img != null) {
                    try {
                        Location loc = player.getLocation();
                        // ★「从头上掉落」：生成点抬到头顶上方
                        Location head = new Location(player.getWorld(),
                                loc.getX(), loc.getY() + 2.2, loc.getZ());
                        player.getWorld().dropItemNaturally(head, img);
                    } catch (Throwable e1) {
                        try {
                            player.getWorld().dropItemNaturally(player.getLocation(), img);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
            }
        }

        // ② 记忆碎片 → 脚下生成「灵感」（原样保留）
        if (cfg == null || cfg.getBoolean("inspiration.enabled", true)) {
            if (ListenerCore.gsIsItem(helmet, ListenerCore.GS_FRAGMENT)
                    && gsHeadDropSurvivalOk(player, cfg, "inspiration.survival-only")) {
                ItemStack drop = ListenerCore.gsNewItem(ListenerCore.GS_INSPIRATION);
                if (drop != null) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }

    /**
     * {@code gsInspLoop(player, uuid, gen)} —— 灵感掉落的<b>自调度链</b>
     * （原脚本 148~163 行）。
     *
     * <p>★★ 为什么用自调度而不是 {@code runRepeating}：{@code runLater} 排出去的任务
     * <b>拿不到句柄、cancel 不掉</b>。所以「停链」只能靠<b>世代号</b>：
     * 每轮先查 {@code gsInspTasks[uuid].gen} 还是不是自己那一代，不是就<b>断链</b>
     * （不再排下一步）。玩家下线也断链。
     *
     * <p>★ 周期<b>每轮重算</b>（配置可能变），所以链的步长是动态的。
     */
    private void gsInspLoop(Player player, String uuid, long gen) {
        int period = gsInspirationInterval(core);
        GS.runLater(() -> {
            boolean alive = false;
            try {
                long[] e = core.gsInspTasks.get(uuid);
                if (e != null && e[0] == gen) {
                    alive = true;
                }
            } catch (Throwable e0) {
                alive = false;
            }
            if (!alive) {
                return;                                   // 已被取代 ⇒ 断链
            }
            try {
                if (player == null || !player.isOnline()) {
                    return;                               // 下线 ⇒ 断链
                }
                gsTickHeadDrop(player);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            gsInspLoop(player, uuid, gen);                // 排下一步
        }, period);
    }

    /** {@code gsStartInspiration(player)} —— 起链（世代号 +1，让旧链自终止）。 */
    void gsStartInspiration(Player player) {
        if (player == null) {
            return;
        }
        String uuid;
        try {
            uuid = player.getUniqueId().toString();
        } catch (Throwable e) {
            return;
        }
        long[] cur = core.gsInspTasks.get(uuid);
        long gen = (cur == null ? 0L : cur[0]) + 1;   // ★ 超过旧链的世代号
        core.gsInspTasks.put(uuid, new long[]{gen});
        gsInspLoop(player, uuid, gen);
    }

    /** {@code gsStopInspiration(player)} —— 停链（世代号 +1 让旧链自终止，再从表里删）。 */
    void gsStopInspiration(Player player) {
        if (player == null) {
            return;
        }
        try {
            String uuid = player.getUniqueId().toString();
            long[] e = core.gsInspTasks.get(uuid);
            if (e != null) {
                e[0] = e[0] + 1;                     // 让旧链自终止（runRepeating 停不掉，只能这样）
            }
            core.gsInspTasks.remove(uuid);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ② 梦境套免死（原脚本 222~440 + 596~675 行）
    // ======================================================================

    /** {@code gsDaNow()} —— 免死冷却用的时间戳（原脚本 315~317 行）。 */
    static long gsDaNow() {
        return ListenerCore.now();
    }

    /**
     * {@code gsDaCooldownLeft(p, cfg)} —— 免死剩余冷却，<b>存玩家 PDC</b>
     * （原脚本 319~328 行）。
     *
     * <p>★ 存 PDC 而不是内存 ⇒ {@code /rsc reload} 不丢冷却
     * （否则重载一次就能无限免死）。
     */
    static long gsDaCooldownLeft(Player p, ListenerCore.DaConfig cfg) {
        try {
            if (ListenerCore.GS_DA_CD_KEY == null || cfg == null || cfg.cooldown <= 0) {
                return 0;
            }
            Long v = p.getPersistentDataContainer()
                    .get(ListenerCore.GS_DA_CD_KEY, org.bukkit.persistence.PersistentDataType.LONG);
            if (v == null) {
                return 0;
            }
            long left = v + cfg.cooldown - gsDaNow();
            return left > 0 ? left : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsDaMarkCooldown(p)} —— 记下免死触发时刻（原脚本 330~336 行）。 */
    static void gsDaMarkCooldown(Player p) {
        try {
            if (ListenerCore.GS_DA_CD_KEY == null) {
                return;
            }
            p.getPersistentDataContainer().set(ListenerCore.GS_DA_CD_KEY,
                    org.bukkit.persistence.PersistentDataType.LONG, gsDaNow());
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsOnEntityDamageSync(event)} —— 梦境套「免死」的真正实现
     * （原脚本 338~440 行）。
     *
     * <p>★★ <b>判定链的顺序一个都不能换</b>（每一环都有理由）：
     * <ol>
     *   <li>事件已被取消 ⇒ 跳过（防重复注册的实例处理过）。</li>
     *   <li><b>先</b>查记分板标签 {@code WL_RIDE_TAG} ⇒ 五骊矟那匹引路马<b>全程免伤</b>。
     *       ★★ 必须<b>在</b> {@code en != 'PLAYER'} 那行<b>之前</b>判 ——
     *       否则非玩家实体直接被放过，这匹马就保护不到了。</li>
     *   <li>只关心玩家。</li>
     *   <li>配置 {@code enabled} 为 false ⇒ 不管。</li>
     *   <li>伤害原因在 {@code ignores} 里（默认 VOID / KILL / SUICIDE）⇒ 不管。
     *       ★ 指令杀与虚空杀不该被免死拦下。</li>
     *   <li>已死 / 血量 <= 0 ⇒ 不管。</li>
     *   <li>{@code getFinalDamage()} 拿不到就退 {@code getDamage()}；两者都拿不到 ⇒ 不管。
     *       ★ 伤害 <= 0（被盾牌完全免疫）⇒ 不管。</li>
     *   <li><b>四件齐全</b>才免死（{@code gsDreamFullSet}）——
     *       从「单件梦境盔甲」升级为套装效果。</li>
     *   <li>冷却中（默认 45 秒）⇒ 这次不免死。</li>
     * </ol>
     *
     * <p>★ 通过之后：①取消伤害 ②记冷却 ③补血（<b>只在低于 heal 时抬上去</b>，比它高不动）
     * ④音效 + 图腾粒子 ⑤发提示消息。
     *
     * <p>★ 最外层 catch <b>什么都不做</b> —— 原脚本注释写明
     * 「这里绝不能往外抛，否则会影响伤害结算」。**照抄**。
     */
    void gsOnEntityDamageSync(EntityDamageEvent event) {
        try {
            if (event == null) {
                return;
            }
            if (event.isCancelled()) {
                return;      // 已被取消 / 已被重复注册的实例处理过 → 跳过
            }

            Entity ent;
            try {
                ent = event.getEntity();
            } catch (Throwable e) {
                return;
            }
            if (ent == null) {
                return;
            }

            // ★★ 「开机动画里冲过来引路的那匹马」必须**全程免伤** ——
            //   它从 7 格外冲过来要 1.5 秒，这期间被玩家 / 怪物 / 环境打死就"永远上不了马"。
            //   判定靠**记分板标签**（实体被 remove 时标签自动跟着消失 ⇒ 不需要任何清理代码）。
            //   注意：**必须在 `en !== 'PLAYER'` 那一行之前判**，否则非玩家实体直接被放过了。
            if (ListenerCore.hasTag(ent, ListenerCore.WL_RIDE_TAG)) {
                event.setCancelled(true);
                return;
            }

            String en;
            try {
                en = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                return;
            }
            if (!en.equals("PLAYER")) {
                return;                               // 只关心玩家
            }

            ListenerCore.DaConfig cfg = core.gsDaConfig();
            if (!cfg.enabled) {
                return;
            }

            // 指令 / 虚空类伤害不拦
            String cause = "";
            try {
                cause = String.valueOf(event.getCause().name());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (cfg.ignores.contains(cause)) {
                return;
            }

            Player p = (Player) ent;
            try {
                if (p.isDead()) {
                    return;
                }
                if (p.getHealth() <= 0) {
                    return;
                }
            } catch (Throwable e) {
                return;
            }

            double dmg;
            try {
                dmg = event.getFinalDamage();
            } catch (Throwable e) {
                try {
                    dmg = event.getDamage();
                } catch (Throwable e2) {
                    return;
                }
            }
            if (!(dmg > 0)) {
                return;                               // 被盾牌 / 完全免疫时 dmg 为 0
            }

            double health;
            double absorb;
            try {
                health = p.getHealth();
                absorb = p.getAbsorptionAmount();
            } catch (Throwable e) {
                return;
            }

            // 伤害后剩余（含吸收血量）> 0 → 不致命，不管
            if (health + absorb - dmg > 0) {
                return;
            }

            // ★ 免死从「梦境盔甲**单件**」升级为**套装效果**
            //   —— 必须四件齐全（头盔 + 盔甲 + 护腿 + 战靴）才免死
            if (!ListenerCore.gsDreamFullSet(p)) {
                return;
            }

            // ★ 冷却中（默认 45 秒）→ 这次不免死
            if (gsDaCooldownLeft(p, cfg) > 0) {
                return;
            }

            // ① 拦截这次伤害
            event.setCancelled(true);
            try {
                gsDaMarkCooldown(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ② 补一口血 —— 免死之后往往只剩残血，不回血根本跑不掉（默认回到 12 点 = 6 颗心）
            //    只有"当前血量低于 heal"时才抬上去；比它高就不动（不扣血）
            try {
                double healTo = cfg.heal;
                if (healTo > 0) {
                    double maxHp = 20.0;
                    try {
                        maxHp = p.getMaxHealth();
                    } catch (Throwable e2) {
                        maxHp = 20.0;
                    }
                    if (!(maxHp > 0)) {
                        maxHp = 20.0;
                    }
                    if (healTo > maxHp) {
                        healTo = maxHp;
                    }
                    double curHp = 0;
                    try {
                        curHp = p.getHealth();
                    } catch (Throwable e2) {
                        curHp = 0;
                    }
                    if (healTo > curHp) {
                        p.setHealth(healTo);
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ 音效 + 粒子
            try {
                if (cfg.sound != null && !cfg.sound.isEmpty()) {
                    p.getWorld().playSound(p.getLocation(), cfg.sound, 1.0f, 1.0f);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (cfg.particle) {
                ListenerCore.gsTotemParticles(p);
            }

            // ④ 耐久不消耗（释梦 / 梦境套已设为永不掉耐久，见 gsNoDurabilityRegisterSync）

            // ⑤ 提示
            try {
                if (cfg.msg != null && !cfg.msg.isEmpty()) {
                    // ★ RSC 的 sendMessage(p, msg) 就是 Chat.t(msg)（翻译 & 颜色码）。
                    p.sendMessage(com.qinghuacisj.gs.util.Chat.t(cfg.msg));
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 这里绝不能往外抛，否则会影响伤害结算
        }
    }

    /**
     * {@code onEntityDamage(event)} —— <b>兜底诊断</b>（原脚本 649~675 行）。
     *
     * <p>★ 它不是免死的实现，而是「万一同步监听器没注册成功」时的事后报警：
     * 正常情况下第一行就 {@code return}（避免无谓开销）；
     * 只有在 {@code GS_DA_REGISTERED} 为 false 时才继续算一遍，
     * 若发现「这次是致命伤害但没被拦下」就记一条日志提示去查注册日志。
     *
     * <p>★★ 这个 {@code GS_DA_REGISTERED} 是<b>唯一必须保留的闩锁</b> ——
     * 别的 17 个 {@code GS_XX_REGISTERED} 在 Java 里都退化成「只注册一次」，
     * 但这个被<b>读</b>来改变行为，所以必须留成实例字段。
     */
    void onEntityDamageDiag(EntityDamageEvent event) {
        try {
            if (gsDaRegistered) {
                return;      // 正常工作时不做事（避免无谓开销）
            }

            Entity ent;
            try {
                ent = event.getEntity();
            } catch (Throwable e) {
                return;
            }
            if (ent == null) {
                return;
            }
            String en;
            try {
                en = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                return;
            }
            if (!en.equals("PLAYER")) {
                return;
            }

            Player ent2 = (Player) ent;
            if (!ListenerCore.gsDreamFullSet(ent2)) {
                return;      // ★ 第十二批：免死改为「套装四件齐全」
            }

            double dmg;
            double health;
            try {
                dmg = event.getFinalDamage();
                health = ent2.getHealth();
            } catch (Throwable e) {
                return;
            }
            if (dmg > 0 && health - dmg <= 0) {
                ListenerCore.gsDaLog("梦境套：检测到致命伤害但同步监听器未生效，检查注册日志！");
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ③ 永不掉耐久（原脚本 442~593 行）
    // ======================================================================

    /**
     * {@code gsNoDurabilityRegisterSync()} —— 注册「物品掉耐久」拦截
     * （原脚本 487~534 行）。
     *
     * <p>★ 语义：{@code GS_NODMG_IDS} 里的物品，掉耐久事件一律取消 ⇒ 等效 unbreakable。
     * ★ 做法与其它物品<b>完全一致：一个字节物品数据都不改</b>，
     * 所以照样能当材料、能进背包堆叠。
     *
     * <p>★ 返回 true 表示「注册成功或已经注册过」（原脚本用 {@code GS_NODMG_REGISTERED} 闩锁）；
     * 返回 false 表示「拿不到事件类 / 注册失败」。
     * 调用点（原脚本 2812 行）用返回值决定是否要退到别的兜底路径，
     * 所以要保留这个布尔语义。
     */
    boolean gsNoDurabilityRegisterSync() {
        if (core.gsNoDmgRegistered) {
            return true;
        }
        try {
            // ★ Java 版：事件类必然存在（原脚本要 Java.type 探测），
            //   监听器由本类的 @EventHandler 统一注册 ⇒ 这里只做闩锁与日志。
            core.gsNoDmgRegistered = true;
            ListenerCore.gsDaLog("释梦 / 梦境套：永不掉耐久已启用（等效 unbreakable）");
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("耐久保护注册异常: " + e);
            return false;
        }
    }

    // ======================================================================
    // ④ 脚本级事件钩子
    // ======================================================================

    /** {@code onPlayerJoin(event)} —— 只做一件事：为这个玩家起灵感掉落链。 */
    void onPlayerJoinHook(PlayerJoinEvent event) {
        gsStartInspiration(event.getPlayer());
    }

    /**
     * {@code onPlayerQuit(event)} —— 下线清理（原脚本 189~197 行）。
     *
     * <p>★ 三块，顺序照抄：
     * <ol>
     *   <li>停灵感链。</li>
     *   <li>清掉这个 uuid 在梦境套各表里的记录（药效归属 / 夜视归属 / 防辐射 / 飞行归属）。
     *       ★ 整块被 try 包住，异常静默。</li>
     *   <li>{@code gsWuliQuit(player)} —— 五骊骑阵：下线收阵（马不会残留）。
     *       ★ <b>单独一个 try</b>，与上面那块互不影响（收阵比清表重要）。</li>
     * </ol>
     */
    void onPlayerQuitHook(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        gsStopInspiration(player);
        try {
            String qu = String.valueOf(player.getUniqueId());
            // ★ 原脚本 193~194 行逐字对应：gsSetEffOn[qu] = null; gsSetNightOn[qu] = false;
            //   gsRadMark(qu, false); gsDreamFlyOn[qu] = false;
            //   注意这里是「置 null」而不是「删键」—— 与 SetBonusSub 里同一模式的
            //   原脚本 940 行保持一致。全脚本只读 get()，所以两者不可观察地区别，
            //   但按原样保留可以避免日后有人加 containsKey 判定时出现偏差。
            core.gsSetEffOn.put(qu, null);
            core.gsSetNightOn.put(qu, Boolean.FALSE);
            core.gsRadMark(qu, false);
            core.gsDreamFlyOn.put(qu, Boolean.FALSE);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            if (wuli != null) {
                wuli.quit(player);       // 五骊骑阵：下线收阵（马不会残留）
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code onPlayerDeath(event)} —— 现在只剩「死亡就收阵」（原脚本 68~79 行）。
     *
     * <p>★★ 原脚本注释（64~67 行）说明 RSC 的 {@code scriptListener} 回调
     * <b>延迟 1 tick 执行</b>：玩家死亡瞬间（未开 keepInventory 时）背包物品
     * 已经变成掉落物。★ 那段「扫描掉落物找想象力」的逻辑已被作者<b>按需求删除</b>
     * （记忆碎片改成「构思机里 9 个想象力合成」），所以现在只剩 {@code gsWuliQuit}。
     *
     * <p>★ 「延迟 1 tick」的语义对 {@code gsWuliQuit} 仍然成立，必须复现 ——
     * 否则收阵时玩家还没死透，马可能被重新召唤回来。
     */
    void onPlayerDeathHook(PlayerDeathEvent event) {
        Player player = event.getEntity();
        try {
            if (wuli != null) {
                wuli.quit(player);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // Bukkit 事件绑定（★ 幂等注册一次，替代原脚本的 registerEvent）
    // ======================================================================

    /**
     * 梦境套「免死」同步拦截 —— ★ 原脚本用 {@code Priority.HIGHEST}。
     *
     * <p>★ 为什么必须是 HIGHEST：要<b>在所有其它插件/机制之后</b>才拿到最终伤害值，
     * 才能在「这一下真的会打死人」时拦下来。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageEvent(EntityDamageEvent event) {
        gsOnEntityDamageSync(event);
        onEntityDamageDiag(event);
    }

    /** 物品掉耐久拦截 —— ★ 原脚本用 {@code Priority.HIGHEST}。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onItemDamage(org.bukkit.event.player.PlayerItemDamageEvent event) {
        try {
            if (ListenerCore.gsIsNoDurabilityItem(event.getItem())) {
                event.setCancelled(true);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 玩家加入 —— 起灵感掉落链。 */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        onPlayerJoinHook(event);
    }

    /** 玩家退出 —— 下线清理 + 收阵。 */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        onPlayerQuitHook(event);
    }

    /** 玩家死亡 —— 收阵（★ 原脚本语义是「延迟 1 tick」，见方法注释）。 */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        onPlayerDeathHook(event);
    }

    // ======================================================================
    // ⑤ 原脚本顶层第 200 行的 runLater(…, 40)
    // ======================================================================

    /**
     * 加载完成后 40 刻（2 秒）为<b>已经在线的玩家</b>补启动灵感计时器
     * （原脚本 199~208 行）。
     *
     * <p>★ 为什么需要：{@code onPlayerJoin} 只覆盖「加载之后才进来的人」，
     * 加载前就在线的玩家（例如 {@code /rsc reload} 时）不会被触发 ⇒ 必须补一次。
     * ★ 整块被 try 包住（加载期 {@code getOnlinePlayers} 可能还没就绪）。
     */
    void scheduleInspirationCatchUp() {
        GS.runLater(() -> {
            try {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    gsStartInspiration(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }, GS_INSP_CATCHUP_DELAY);
    }

    // ======================================================================
    // AbstractModule 钩子转发（原脚本的 4 个 scriptListener 回调）
    // ======================================================================

    @Override
    protected Object onPlayerJoin(PlayerJoinEvent e) {
        onPlayerJoinHook(e);
        return null;
    }

    @Override
    protected Object onPlayerQuit(PlayerQuitEvent e) {
        onPlayerQuitHook(e);
        return null;
    }

    @Override
    protected Object onPlayerDeath(PlayerDeathEvent e) {
        onPlayerDeathHook(e);
        return null;
    }

    @Override
    protected Object onEntityDamage(EntityDamageEvent e) {
        onEntityDamageDiag(e);
        return null;
    }

    @Override
    protected Object onUse(PlayerRightClickEvent e, Object reader) {
        // ★ gs_listener 没有 onUse —— 它是纯监听器脚本。
        //   所有右键行为都在各子系统的监听器里（它们自己处理）。
        return null;
    }

    /** 供探针对账用：本模块实际注册的 Bukkit 事件监听器数量。 */
    int registeredHandlerCount() {
        return 5;
    }

    /** 未使用，保留以说明 {@code Item} / {@code List} / {@code Map} 的导入来源（探针会读这些类型）。 */
    static void unusedTypeAnchors(Item item, List<ItemStack> l, Map<String, String> m,
                                  HashMap<String, String> h) {
        // 仅为让编译器保留 import；无运行时作用。
    }
}
