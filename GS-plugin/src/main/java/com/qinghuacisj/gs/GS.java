package com.qinghuacisj.gs;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.logging.Level;

/**
 * 全局上下文 —— 插件实例、日志、ID 规范化、调度。
 *
 * <p>对应原 RSC 版脚本里到处出现的
 * Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE
 * 那 32 处调用：它们**只是要一个 Plugin 实例来做调度**，没有任何一处用到 RSC 的 API。
 * 原生版直接用本插件自己，那 32 处 fallback 逻辑整体作废。
 */
public final class GS {

    /** 附属 id（命名空间，必须小写 —— 与 info.yml 的 id: gs 一致）。 */
    public static final String ID = "gs";

    /** 附属显示名。 */
    public static final String NAME = "构思技艺";

    private static GSPlugin plugin;

    private GS() {
    }

    static void init(GSPlugin p) {
        plugin = p;
    }

    static void shutdown() {
        // 取消本插件登记的所有定时任务
        if (plugin != null) {
            Bukkit.getScheduler().cancelTasks(plugin);
        }
    }

    public static GSPlugin plugin() {
        if (plugin == null) {
            throw new IllegalStateException("GS 尚未初始化");
        }
        return plugin;
    }

    /** 拿 Plugin 实例做调度；插件未初始化时回落到 Bukkit 上名为 GS 的插件。 */
    public static Plugin pluginOrNull() {
        if (plugin != null) {
            return plugin;
        }
        return Bukkit.getPluginManager().getPlugin("GS");
    }

    // ------------------------------------------------------------------ 日志

    public static void log(String fmt, Object... args) {
        plugin().getLogger().info(fmt(fmt, args));
    }

    public static void warn(String fmt, Object... args) {
        plugin().getLogger().warning(fmt(fmt, args));
    }

    /** 与原 JS 版一致：异常一律记日志，绝不冒泡到 Bukkit 事件总线。 */
    public static void error(Throwable t, String fmt, Object... args) {
        plugin().getLogger().log(Level.SEVERE, fmt(fmt, args), t);
    }

    private static String fmt(String s, Object... args) {
        if (args == null || args.length == 0) {
            return s;
        }
        try {
            return String.format(s, args);
        } catch (RuntimeException e) {
            return s;
        }
    }

    // ------------------------------------------------------------------ ID

    /**
     * ID 规范化。
     *
     * <p>★ 硬约定（来自原项目踩坑记录）：RSC 的注册表会把 id **大写化**
     * （Tr_pfg 实际存成 TR_PFG），所以任何 id 比较都必须走这里。
     */
    public static String idOf(String raw) {
        return raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 两个 id 是否等价（忽略大小写与首尾空白）。 */
    public static boolean idEquals(String a, String b) {
        return idOf(a).equals(idOf(b));
    }

    /** 构造本附属命名空间下的 NamespacedKey。 */
    public static NamespacedKey key(String name) {
        // ★ 必须用 fromString —— new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、
        //   会静默失败；原生版同样统一走 fromString。
        NamespacedKey k = NamespacedKey.fromString(ID + ":" + name.toLowerCase(Locale.ROOT));
        if (k == null) {
            throw new IllegalArgumentException("非法 key: " + name);
        }
        return k;
    }

    /** 按 id 取已注册的 SlimefunItem；没有则 null。 */
    public static SlimefunItem sfItem(String id) {
        return SlimefunItem.getById(idOf(id));
    }

    // ------------------------------------------------------------------ 调度

    /** 主线程下一刻执行（对应原脚本的 runLater(fn, 1)）。 */
    public static BukkitTask runLater(Runnable r, long delayTicks) {
        return Bukkit.getScheduler().runTaskLater(plugin(), r, Math.max(0L, delayTicks));
    }

    /** 主线程周期任务（对应原脚本的 runRepeating(fn, delay, period)）。 */
    public static BukkitTask runRepeating(Runnable r, long delayTicks, long periodTicks) {
        return Bukkit.getScheduler().runTaskTimer(plugin(), r,
                Math.max(0L, delayTicks), Math.max(1L, periodTicks));
    }
}
