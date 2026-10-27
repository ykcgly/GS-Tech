/* ==========================================================
 * 构思技艺 (GS) —— 「更新日志」按钮
 * groups.yml 里由  actions: ["script gs_update_log"]  引用
 * 入口函数固定为 onButtonGroupClick(p, slot, clickedItem, clickAction, mode)
 * ----------------------------------------------------------
 * 「信息」分类里的几条更新日志是**展示型条目**：
 * 点一下**只响一声翻页音效**，不做任何实际动作，
 * 避免像旧版那样"点开却跳去配方页"。
 *
 * ★★ 2026-10-13 第八十二批：**删掉翻页提示词**
 *   用户原话：「删除日志翻页提示词，只保留创作者的提示词」
 *   ⇒ 原来点一下会在聊天栏发两句话之一：
 *        「你翻了翻 <被点的日志名>。」/「你翻了翻这页日志。」
 *     现在**两句都删掉**，点日志**不再发任何聊天消息**。
 *   · ★ 音效 `item.book.page_turn` **保留**（那不是"提示词"，是手感反馈）
 *   · ★ 「构思技艺创作者」那格的提示词**不动**
 *     （在 scripts/gs_info_creator.js，两句话照旧发）
 *   ⇒ 所以现在「信息」界面里只有**创作者**会说话，日志全是哑的。
 * ========================================================== */

// 加载标记：后台看到这行，说明脚本本身没问题
try { getServer().getLogger().info('[构思技艺] gs_update_log 已加载'); } catch (e) { }

function onButtonGroupClick(p, slot, clickedItem, clickAction, mode) {
    try {
        if (p == null) return;

        // ★ 第八十二批：**不再发任何聊天提示词**（翻页音效保留）
        try { p.playSound(p.getLocation(), 'item.book.page_turn', 0.8, 1.4); } catch (e) { }
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 更新日志 onButtonGroupClick 出错: ' + err); } catch (e) { }
    }
}
