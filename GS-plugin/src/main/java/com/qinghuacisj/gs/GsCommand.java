package com.qinghuacisj.gs;

import com.qinghuacisj.gs.content.Content;
import com.qinghuacisj.gs.script.Modules;
import com.qinghuacisj.gs.script.Script;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /gs} 主命令 —— {@code info} / {@code reload}。
 *
 * <p>★★ <b>这个类补的是一个真实缺陷</b>：{@code plugin.yml} 从第一版起就声明了
 * <pre>
 *   commands:
 *     gs:
 *       usage: /gs &lt;reload|info&gt;
 * </pre>
 * 但<b>从来没有注册过 executor</b>。而 Bukkit 的
 * {@code PluginCommand#execute} 在取到 {@code executor} 字段后<b>直接</b>
 * {@code invokeinterface CommandExecutor.onCommand}，<b>中间没有 ifnull</b>
 * （已用 {@code javap -c} 逐条字节码确认：offset 51 取字段、offset 59 直接调用）
 * ⇒ 玩家敲 {@code /gs} 会得到一个 <b>NullPointerException</b>，
 * 而 {@code plugin.yml} 里又白纸黑字写着这个命令存在。
 * 要发布给别人用，这条必须补上。
 *
 * <p>★ <b>为什么不照抄 RSC 的 {@code /rsc reload} 做完整重载</b>：
 * Slimefun <b>没有</b>反注册 API —— {@code SlimefunItem.register()} 之后无法摘除，
 * 再读一遍 yml 只会往注册表里塞同 id 的副本（Slimefun 会刷一屏
 * 「ID 已存在」告警，物品组里出现重复条目）。所以本命令<b>只做真正安全的那部分</b>，
 * 并且明确告诉用户「内容改动要重启」，而不是假装重载成功。
 *
 * <p>★ <b>配置其实不需要 reload</b>：本附属的 {@code config.yml} 是<b>每轮都重读</b>的
 * （见 {@code ListenerModule} 等处的说明：玩家可能改配置后立刻生效），
 * 所以 {@code reload} 的主要作用是<b>重新注册监听器</b>与刷新配置缓存。
 */
public final class GsCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[构思技艺] ";

    /** 子命令 —— 与 plugin.yml 的 usage 一致。 */
    private static final List<String> SUBS = List.of("info", "reload");

    @Override
    public boolean onCommand(CommandSender sender, Command command,
                             String label, String[] args) {
        // ★ 整条包在 try/catch 里：命令是用户输入触发的，
        //   任何异常都只该回一句红字，不该把 NPE 抛进 Bukkit 的命令分发里。
        try {
            if (args.length == 0) {
                info(sender);
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "info":
                    info(sender);
                    return true;
                case "reload":
                    reload(sender);
                    return true;
                default:
                    sender.sendMessage(ChatColor.RED + "用法: /" + label + " <info|reload>");
                    return true;
            }
        } catch (Throwable t) {
            GS.error(t, "执行 /%s 时出错", label);
            sender.sendMessage(ChatColor.RED + PREFIX + "命令执行出错，详见控制台");
            return true;
        }
    }

    /** 打印版本与上次加载的统计。 */
    private void info(CommandSender to) {
        to.sendMessage(ChatColor.GOLD + PREFIX + ChatColor.WHITE + GS.NAME
                + " (GS)  v" + GS.plugin().getDescription().getVersion());
        to.sendMessage(ChatColor.GRAY + "  作者: "
                + String.join(", ", GS.plugin().getDescription().getAuthors()));

        Setup.Stats s = Setup.lastStats();
        if (s == null) {
            to.sendMessage(ChatColor.RED + "  尚未加载（看控制台日志）");
            return;
        }
        to.sendMessage(ChatColor.GRAY + "  上次加载: "
                + (s.ok ? ChatColor.GREEN + "成功" : ChatColor.RED + "失败")
                + ChatColor.GRAY + "  ·  用时 " + s.elapsedMs + " ms");
        to.sendMessage(ChatColor.GRAY + "  物品组 " + s.groups
                + " ｜ 配方类型 " + s.recipeTypes
                + " ｜ 菜单 " + s.menus
                + " ｜ 物品 " + s.items);
        to.sendMessage(ChatColor.GRAY + "  配方机器 " + s.machines
                + " ｜ 材料发生器 " + s.generators
                + " ｜ 多方块机器 " + s.multiBlocks);
        to.sendMessage(ChatColor.GRAY + "  脚本模块 " + Modules.convertedCount()
                + " 个（已实例化 " + Modules.instantiatedCount() + " 个）");
        to.sendMessage(ChatColor.DARK_GRAY + "  内容目录: " + Content.dir().getPath());
    }

    /**
     * 安全重载。
     *
     * <p>★ 只做两件真的安全的事：
     * <ol>
     *   <li>丢掉配置缓存 —— 下次访问重新读盘（其实每轮都重读，这里只是保证立即生效）；</li>
     *   <li>清掉脚本模块实例 —— 下次取用时重新构造（＝重新注册监听器、重启脉冲）。</li>
     * </ol>
     *
     * <p>⚠️ <b>不</b>重读 {@code content/*.yml}：Slimefun 无法反注册物品，
     * 重读只会产生同 id 副本。改物品 / 机器 / 配方请<b>重启服务器</b>。
     */
    private void reload(CommandSender to) {
        Content.reloadConfig();
        Script.clearCache();
        to.sendMessage(ChatColor.GREEN + PREFIX + "配置缓存与脚本模块已重置。");
        to.sendMessage(ChatColor.YELLOW + "  ⚠ 物品 / 机器 / 配方的改动需要"
                + ChatColor.WHITE + "重启服务器" + ChatColor.YELLOW + "才会生效 —— "
                + "Slimefun 不支持反注册物品，热重载只会产生重复条目。");
        GS.log("控制台执行了 /gs reload（配置缓存 + 脚本模块已重置）");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        String p = args[0].toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String s : SUBS) {
            if (s.startsWith(p)) {
                out.add(s);
            }
        }
        return out;
    }
}
