/* ==========================================================
 * 构思技艺 (GS) —— 「信息」界面 · 第一格「构思技艺创作者」
 * 按钮分类脚本：由 groups.yml 的  actions: ["script gs_info_creator"]  引用
 * 入口函数固定为 onButtonGroupClick(p, slot, clickedItem, clickAction, mode)
 * ========================================================== */

function onButtonGroupClick(p, slot, clickedItem, clickAction, mode) {
    sendMessage(p, "&f构思技艺创作者 &8· &7QingHuaCiSJ");
    sendMessage(p, "&7感谢游玩 &8—— &7「万物初生，始于想象」");

    try {
        p.playSound(p.getLocation(), "entity.experience_orb.pickup", 0.6, 1.6);
    } catch (e) {
        // 声音播放失败不影响功能
    }
    // 返回后停留在「信息」界面
}
