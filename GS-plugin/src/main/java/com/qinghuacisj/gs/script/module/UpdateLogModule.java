package com.qinghuacisj.gs.script.module;

import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code gs_update_log.js} 的 Java 化版本 —— 「更新日志」按钮（14 个按钮共用）。
 *
 * <p>由 {@code groups.yml} 的 {@code actions: ["script gs_update_log"]} 引用
 * （{@code gs_log_1003a/1003b/1004…1015} 等日志组）。
 *
 * <p>★★ 这个脚本的行为是历史上被反复削减过的，转换时必须逐条对齐：
 * <ul>
 *   <li><b>第八十二批</b>删掉了翻页提示词 ⇒ 点日志<b>不再发任何聊天消息</b>。</li>
 *   <li>音效 {@code item.book.page_turn} <b>保留</b>（那是手感反馈，不是提示词）。</li>
 *   <li>只有「构思技艺创作者」那格会说话（在 {@link InfoCreatorModule}）。</li>
 * </ul>
 *
 * <p>★ 顶层副作用：原脚本加载时会往<b>服务端日志</b>打一行
 * {@code [构思技艺] gs_update_log 已加载}（包在 try/catch 里、静默）。
 * 这里放进构造器 —— 因为原版的 {@code JavaScriptEval} 构造器里就求值脚本，
 * 而 {@code Modules.acquire} 用 {@code computeIfAbsent} 保证「首次取用即构造」，
 * 时序完全一致。
 */
public final class UpdateLogModule extends AbstractModule {

    public UpdateLogModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_update_log 已加载");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_update_log";
    }

    @Override
    protected Object onButtonGroupClick(Player p, int slot, ItemStack clickedItem,
                                        ClickAction clickAction, SlimefunGuideMode mode) {
        try {
            if (p == null) {
                return null;
            }
            // ★ 第八十二批：不再发任何聊天提示词（翻页音效保留）
            try {
                p.playSound(p.getLocation(), "item.book.page_turn", 0.8f, 1.4f);
            } catch (Throwable ignored) {
                // 内层静默（与原脚本一致）
            }
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 更新日志 onButtonGroupClick 出错: " + err);
            } catch (Throwable ignored) {
                // 连日志都失败就只能放弃（与原脚本一致）
            }
        }
        return null;
    }
}
