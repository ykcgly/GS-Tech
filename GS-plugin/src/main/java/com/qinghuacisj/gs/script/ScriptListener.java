package com.qinghuacisj.gs.script;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 脚本监听器 —— 等价于 RSC 的 {@code ScriptableEventListener}。
 *
 * <p><b>RSC 那边注册了约 250 个事件类</b>（把 Bukkit + Slimefun 的事件几乎全列了一遍），
 * 每个都转发到 {@code "on" + 事件简名去掉"Event"}。但 {@code gs_listener.js}
 * <b>只定义了 4 个</b> {@code on*} 顶层函数（已全量扫描确认）：
 * <pre>
 *   onPlayerJoin    ← PlayerJoinEvent
 *   onPlayerQuit    ← PlayerQuitEvent
 *   onPlayerDeath   ← PlayerDeathEvent
 *   onEntityDamage  ← EntityDamageEvent
 * </pre>
 * 其余 246 个事件的转发在原版里就是<b>每次事件都白跑一趟调度器</b>。
 * 原生版只注册这 4 个 —— 这不是「优化掉功能」，而是把原版纯粹的空转去掉。
 *
 * <p>★★ 三个必须逐字保留的派发细节：
 * <ol>
 *   <li><b>延迟 1 tick</b>（原版是 {@code Bukkit.getScheduler().runTask(...)}）——
 *       不是同步执行。改成立即执行会改变与其它插件的时序关系。</li>
 *   <li>优先级 {@code NORMAL}、{@code ignoreCancelled = false}（Bukkit 的
 *       {@code @EventHandler} 默认值，原版也没显式指定）。</li>
 *   <li>函数名是 {@code "on" + 简名.replace("Event", "")}，注意
 *       {@code replace} 是<b>全局替换</b>。对这四个类名来说结果与只去尾部一致，
 *       但若将来加了名字里含 "Event" 的事件类，必须照抄这个全局替换。</li>
 * </ol>
 *
 * <p>★ <b>不要</b>在这里补 {@code onEntityDamageByEntity}：
 * {@code EntityDamageByEntityEvent} 有<b>自己的</b> HandlerList，注册父类
 * {@code EntityDamageEvent} 收不到它。原版同样是分开注册两个类的，
 * 而脚本没定义 by-entity 那个 ⇒ 玩家对玩家的伤害走的是脚本自己注册的
 * HIGHEST 优先级监听器，不是这条路。
 */
public final class ScriptListener implements Listener {

    private final Script script;

    public ScriptListener(Script script) {
        this.script = script;
    }

    /** 注册这 4 个监听器。 */
    public static void register(Script script) {
        Bukkit.getPluginManager().registerEvents(new ScriptListener(script), GS.plugin());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        dispatch(e);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        dispatch(e);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        dispatch(e);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        dispatch(e);
    }

    /** 延迟 1 tick 派发到脚本的 {@code on<事件名>}。 */
    private void dispatch(Event event) {
        if (script == null) {
            return;
        }
        String fn = "on" + event.getClass().getSimpleName().replace("Event", "");
        Bukkit.getScheduler().runTask(GS.plugin(), () -> script.call(fn, event));
    }
}
