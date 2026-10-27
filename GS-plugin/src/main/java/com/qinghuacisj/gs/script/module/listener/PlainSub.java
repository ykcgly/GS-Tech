package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * {@code gs_listener.js} 的「基础构思 · 纯材料」子系统（原脚本第 5835~5944 行）。
 *
 * <p><b>玩法</b>：基础构思里的 18 件<b>纯材料</b>（{@link #GS_BASIC_PLAIN_IDS}）
 * 左右键交互全部取消 —— 石头 / 材料都不再响应点击，
 * 免得玩家拿它们对着方块左右键时误触发原版行为（放方块、开箱子、挖方块…）。
 *
 * <p>★★ <b>唯一的硬要求：滚木必须排除在外</b>。
 * 「滚木」（{@code GS_ROLLING_LOG_INGOT}）虽然也是材料，但它<b>保留了右键隐身</b>功能，
 * 一旦被这条监听器取消掉，右键就再也隐身不了。所以
 * {@link #GS_BASIC_PLAIN_IDS} 里<b>故意没有它</b>（19 项里去掉滚木 = 18 项）。
 * <b>不要"顺手补全"这个清单。</b>
 *
 * <p>★★ <b>监听器注册（与原脚本不同的唯一一处，且是修缺陷）</b>：
 * 原脚本用 {@code pm.registerEvent(...)} 在脚本里自己注册，{@code GS_PLAIN_REGISTERED}
 * 是脚本级变量 ⇒ <b>每次 {@code /rsc reload} 都会再注册一遍，监听器会累积</b>。
 * Java 版改成 {@code org.bukkit.event.Listener} + 幂等 {@code registerEvents}，
 * 可观察行为一致（{@code setCancelled(true)} 幂等，重复执行没有额外副作用），只是不再累积。
 * <p>优先级：原脚本 {@code registerEvent} 只传 4 个参数（{@code eventClass, listener,
 * Priority.HIGHEST, executor}），<b>没有第 5 个参数 ⇒ ignoreCancelled 默认 false</b>；
 * Java 侧 {@code @EventHandler(priority = EventPriority.HIGHEST)} 默认也是 false。
 * <p>★ 与原脚本一致，这条监听器<b>故意不检查 {@code e.isCancelled()}</b>、
 * 也<b>不检查 {@code e.getHand()}</b>（副手也会派发同一个事件，
 * 而副手拿纯材料同样不该响应）。
 */
public final class PlainSub {

    /**
     * {@code GS_BASIC_PLAIN_IDS} —— 基础构思里的「纯材料」清单（19 项里去掉滚木 = 18 项）。
     *
     * <p>★★ 滚木 {@code GS_ROLLING_LOG_INGOT} <b>故意不在这里</b> —— 保留它的右键隐身。
     */
    public static final String[] GS_BASIC_PLAIN_IDS = {
            "GS_IMAGINATION", "GS_MEMORY_FRAGMENT", "GS_INSPIRATION", "GS_DELUSION",
            "GS_THOUGHT", "GS_CONCEPT", "GS_PROTOTYPE", "GS_LAW_IMAGE", "GS_FIXED_FORM",
            "GS_DREAM", "GS_MOLD", "GS_DREAM_RULE", "GS_DREAM_INGOT",
            "GS_DREAM_REALM", "GS_CONSTRUCT", "GS_TRUE_DREAM", "GS_THOUGHT_CONSTRUCT",
            "GS_WANGXIANG_FRAGMENT"
    };

    /** {@code GS_PLAIN_REGISTERED} —— 监听器闩锁。 */
    private boolean GS_PLAIN_REGISTERED;

    private final ListenerCore core;

    public PlainSub(ListenerCore core) {
        this.core = core;
    }

    // ======================================================================
    // 判定（原脚本 5852~5865 行）
    // ======================================================================

    /** {@code gsPlainIsBasic(item)} —— 这件物品是不是「基础构思纯材料」。 */
    public static boolean gsPlainIsBasic(ItemStack item) {
        if (item == null) {
            return false;
        }
        try {
            String n;
            try {
                n = String.valueOf(item.getType().name());
            } catch (Throwable e) {
                return false;
            }
            if (n.length() == 0 || n.equals("AIR")) {
                return false;                            // 空气直接放过
            }
            for (String id : GS_BASIC_PLAIN_IDS) {
                if (ListenerCore.gsIsItem(item, id)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 启动 + 日志行（原脚本 5934~5944 行）
    // ======================================================================

    /** 对应原脚本 {@code gsPlainStart()}。 */
    public void start() {
        try {
            gsPlainRegisterSync();
            // ★ 「18 件」是 GS_BASIC_PLAIN_IDS.length，**不是硬编码**
            ListenerCore.gsDaLog("基础构思：" + GS_BASIC_PLAIN_IDS.length
                    + " 件纯材料已取消左右键交互（石头/材料都不再响应点击，**滚木保留右键隐身**）");
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 基础构思启动失败: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        }
    }

    /** {@code [构思技艺] 基础构思监听器注册成功（纯材料左右键交互已取消，滚木除外）} */
    public static String registerLine() {
        return ListenerCore.PREFIX + "基础构思监听器注册成功（纯材料左右键交互已取消，滚木除外）";
    }

    /** 原脚本 5937~5938 行的日志正文（不含前缀）。 */
    public static String msgPlain() {
        return "基础构思：" + GS_BASIC_PLAIN_IDS.length
                + " 件纯材料已取消左右键交互（石头/材料都不再响应点击，**滚木保留右键隐身**）";
    }

    // ======================================================================
    // 注册（原脚本 5867~5932 行）
    // ======================================================================

    /**
     * {@code gsPlainRegisterSync()} —— 幂等注册（原脚本是"每次都再注册一遍"）。
     *
     * @return 是否已注册
     */
    public boolean gsPlainRegisterSync() {
        if (GS_PLAIN_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new PlainListener(), GS.plugin());
            GS_PLAIN_REGISTERED = true;
            try {
                Bukkit.getLogger().info(registerLine());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 基础构思监听器注册异常: " + e);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            return false;
        }
    }

    /**
     * 基础构思的 1 条监听器。
     *
     * <p>★ 原脚本先试 {@code JavaAdapter} 造 {@code Listener} / {@code EventExecutor}
     * 的 fallback 分支在 GraalJS 25.1.3 里<b>必然失败</b>（没有 {@code JavaAdapter}）
     * ⇒ 属于死代码，不移植。
     */
    private static final class PlainListener implements Listener {

        /** 原脚本的 {@code handle(e)}。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }

                // 事件自带的物品（右键时有；左键点方块时可能是 null）
                boolean hit = false;
                try {
                    if (gsPlainIsBasic(e.getItem())) {
                        hit = true;
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }

                // 主手兜底：左键点方块 / 对着空气按键时事件可能不带物品
                if (!hit) {
                    try {
                        if (gsPlainIsBasic(p.getInventory().getItemInMainHand())) {
                            hit = true;
                        }
                    } catch (Throwable e2) {
                        // 与原脚本一致：静默
                    }
                }
                if (!hit) {
                    return;
                }

                e.setCancelled(true);
                // ★ 原脚本 `if (Res != null)` 里的 Res = Event.Result；Java 里恒非 null
                try {
                    e.setUseInteractedBlock(Event.Result.DENY);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                try {
                    e.setUseItemInHand(Event.Result.DENY);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 探针用的只读访问
    // ======================================================================

    /** 清单长度（探针用；应恒为 18）。 */
    public static int plainCount() {
        return GS_BASIC_PLAIN_IDS.length;
    }

    /** {@code GS_PLAIN_REGISTERED} 的当前值（探针用）。 */
    boolean isRegistered() {
        return GS_PLAIN_REGISTERED;
    }

    /** 滚木 id —— ★ 用来反向断言"它确实不在清单里"。 */
    public static final String GS_ROLLING_LOG_INGOT = "GS_ROLLING_LOG_INGOT";

    /** 某个 Material 名是否是"空气"（探针用，与原脚本的判据一致）。 */
    static boolean isAir(Material m) {
        try {
            return m == null || m.isAir();
        } catch (Throwable e) {
            return false;
        }
    }
}
