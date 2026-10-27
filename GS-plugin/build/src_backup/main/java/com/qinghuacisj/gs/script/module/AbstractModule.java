package com.qinghuacisj.gs.script.module;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 已 Java 化模块的基类：把「按函数名调用」翻译成「调用具体的 Java 方法」。
 *
 * <p>这样每个被转换的脚本只需要覆写它真正用到的那几个钩子，其余一律走空实现
 * —— 与原版「脚本没定义这个函数 ⇒ 调用返回 null」完全等价。
 *
 * <p>★ 参数顺序/类型严格照抄 Java 侧的调用点，不做任何「顺手规整」：
 * <ul>
 *   <li>{@code onUse(PlayerRightClickEvent, Object)} —— 第二个参数原版传的是
 *       {@code ItemReader} 实例，本附属 14 个 {@code onUse} 全是单参签名、
 *       没人读它，所以传 null。</li>
 *   <li>{@code onWeaponHit(EntityDamageByEntityEvent, Player, ItemStack)} ——
 *       顺序来自 Slimefun 的 {@code WeaponUseHandler#onHit}（已 javap 核对）。</li>
 *   <li>{@code onToolUse(BlockBreakEvent, ItemStack, int, List)} ——
 *       来自 {@code ToolUseHandler#onToolUse}。</li>
 *   <li>{@code onButtonGroupClick(Player, int, ItemStack, ClickAction, SlimefunGuideMode)}
 *       —— 第 4 个参数是 <b>ClickAction 对象</b>，不是动作字符串。</li>
 * </ul>
 */
public abstract class AbstractModule implements GsModule {

    @Override
    public Object call(String fnName, Object... args) {
        if (fnName == null) {
            return null;
        }
        switch (fnName) {
            case "init":
                return init();

            case "onUse":
                return onUse((PlayerRightClickEvent) args[0], arg(args, 1));

            case "onWeaponHit":
                return onWeaponHit((EntityDamageByEntityEvent) args[0],
                        (Player) args[1], (ItemStack) args[2]);

            case "onToolUse":
                return onToolUse((BlockBreakEvent) args[0], (ItemStack) args[1],
                        ((Number) args[2]).intValue(), asList(args[3]));

            case "onButtonGroupClick":
                return onButtonGroupClick((Player) args[0], ((Number) args[1]).intValue(),
                        (ItemStack) args[2], (ClickAction) args[3], (SlimefunGuideMode) args[4]);

            case "onPlayerJoin":
                return onPlayerJoin((PlayerJoinEvent) args[0]);
            case "onPlayerQuit":
                return onPlayerQuit((PlayerQuitEvent) args[0]);
            case "onPlayerDeath":
                return onPlayerDeath((PlayerDeathEvent) args[0]);
            case "onEntityDamage":
                return onEntityDamage((EntityDamageEvent) args[0]);

            default:
                // 未转换的钩子：与原版「脚本里没这个函数」等价
                return null;
        }
    }

    private static Object arg(Object[] args, int i) {
        return args.length > i ? args[i] : null;
    }

    @SuppressWarnings("unchecked")
    private static List<ItemStack> asList(Object o) {
        return o == null ? List.of() : (List<ItemStack>) o;
    }

    // ------------------------------------------------------------ 可覆写钩子

    /** 物品/机器注册时调用一次。 */
    protected Object init() {
        return null;
    }

    /** 右键使用物品。调用方在返回后<b>无条件</b>取消事件。 */
    protected Object onUse(PlayerRightClickEvent e, Object reader) {
        return null;
    }

    /** 作为武器命中实体。 */
    protected Object onWeaponHit(EntityDamageByEntityEvent e, Player p, ItemStack item) {
        return null;
    }

    /** 作为工具破坏方块。 */
    protected Object onToolUse(BlockBreakEvent e, ItemStack item, int fortune, List<ItemStack> drops) {
        return null;
    }

    /** 物品组按钮被点击。 */
    protected Object onButtonGroupClick(Player p, int slot, ItemStack clickedItem,
                                        ClickAction clickAction, SlimefunGuideMode mode) {
        return null;
    }

    protected Object onPlayerJoin(PlayerJoinEvent e) {
        return null;
    }

    protected Object onPlayerQuit(PlayerQuitEvent e) {
        return null;
    }

    protected Object onPlayerDeath(PlayerDeathEvent e) {
        return null;
    }

    protected Object onEntityDamage(EntityDamageEvent e) {
        return null;
    }
}
