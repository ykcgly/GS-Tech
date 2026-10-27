package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「手持本物品右键方块 → 方块功能一律不触发」的拦截器。
 *
 * <p><b>为什么值得抽出来</b>：已机械比对确认，这套拦截器在<b>4 个脚本</b>里重复出现
 * （{@code gs_song} / {@code gs_cursed} / {@code gs_bad_apple} / {@code gs_boss_rider}，
 * 前三个连函数名都叫 {@code sgRegisterGuard} —— 是从同一份抄出来的）。
 * 后面的 boss 脚本还会继续用到。
 *
 * <p>★★ <b>原理（照抄原脚本注释）</b>：Slimefun 在 <b>NORMAL</b> 优先级发
 * {@link PlayerRightClickEvent}（触发脚本的 {@code onUse}），我们注册在
 * <b>HIGHEST</b> ⇒ 一定在 {@code onUse} <b>之后</b>才 {@code setCancelled}，
 * 所以「音效/效果照放、方块交互被拦」，两者互不影响。
 *
 * <p>★ 每个脚本注册<b>自己</b>的拦截器、只认<b>自己</b>的物品 id —— 与原脚本一致。
 * 已实测 4 个脚本各自注册成功（日志里 4 条「方块交互拦截 已启用」）。
 *
 * <p>★ 去重：原脚本用每个脚本自己的 {@code SG_GUARD_OK} 闩锁防重复注册。
 * 那个闩锁是<b>脚本级</b>的，所以在 RSC 里 <b>reload 会再注册一遍</b>（监听器泄漏）。
 * 这里用<b>按物品 id 的静态去重</b> —— 效果上完全等价（取消两次 = 取消一次），
 * 但不会随 reload 累积监听器。
 */
public final class InteractGuard {

    /** 已注册过的物品 id。★ 故意<b>不</b>在 reload 时清空：监听器还挂在 Bukkit 上。 */
    private static final Set<String> REGISTERED = ConcurrentHashMap.newKeySet();

    private InteractGuard() {
    }

    /**
     * 注册拦截器。
     *
     * @param itemId 只拦这个物品
     * @return 是否已就绪（已注册过也返回 {@code true}，等价原脚本闩锁的早退）
     */
    public static synchronized boolean register(String itemId) {
        if (REGISTERED.contains(itemId)) {
            return true;
        }
        try {
            // ★ 原脚本注册在 RSC 插件名下（解析链：RSC → RSC.INSTANCE → Slimefun）。
            //   原生版注册在<b>本插件</b>名下 —— 附属自己的监听器挂在自己的插件上，这是等价物。
            Bukkit.getPluginManager().registerEvents(new Guard(itemId), GS.plugin());
            REGISTERED.add(itemId);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** HIGHEST 优先级的方块交互拦截器，只认一个物品 id。 */
    private static final class Guard implements Listener {

        private final String itemId;

        Guard(String itemId) {
            this.itemId = itemId;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }
                if (!act.contains("RIGHT_CLICK")) {
                    return;      // 只拦右键（左键留给玩家正常挖方块）
                }

                // ★ 只认主手
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (!hs.isEmpty() && hs.contains("OFF")) {
                    return;
                }

                ItemStack h;
                try {
                    h = e.getItem();
                } catch (Throwable e2) {
                    h = null;
                }
                if (h == null) {
                    return;
                }
                if (!Guards.isOurItem(h, itemId)) {
                    return;
                }

                e.setCancelled(true);   // 方块功能不触发（箱子/耕地/按钮/放置…全拦）
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }
}
