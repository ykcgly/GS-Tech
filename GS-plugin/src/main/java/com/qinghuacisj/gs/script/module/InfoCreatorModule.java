package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * {@code gs_info_creator.js} 的 Java 化版本 —— 「信息」界面第一格
 * 「构思技艺创作者」按钮。
 *
 * <p>由 {@code groups.yml} 的 {@code actions: ["script gs_info_creator"]} 引用，
 * 入口固定为 {@code onButtonGroupClick}。原脚本 17 行，无顶层副作用。
 *
 * <p>★ 保留的行为细节：{@code playSound} 在<b>原脚本里就包着 try/catch 且静默</b>
 * （注释写明「声音播放失败不影响功能」）—— 所以这里也必须静默吞掉，
 * <b>不能</b>让它冒泡到外层记日志，否则日志内容会与现网不一致。
 * 两条 {@code sendMessage} 原脚本<b>没有</b>保护，因此这里也不加。
 */
public final class InfoCreatorModule extends AbstractModule {

    @Override
    public String name() {
        return "gs_info_creator";
    }

    @Override
    protected Object onButtonGroupClick(Player p, int slot, ItemStack clickedItem,
                                        ClickAction clickAction, SlimefunGuideMode mode) {
        p.sendMessage(Chat.t("&f构思技艺创作者 &8· &7QingHuaCiSJ"));
        p.sendMessage(Chat.t("&7感谢游玩 &8—— &7「万物初生，始于想象」"));

        try {
            p.playSound(p.getLocation(), "entity.experience_orb.pickup", 0.6f, 1.6f);
        } catch (Throwable ignored) {
            // 声音播放失败不影响功能（与原脚本一致：静默）
        }
        // 返回后停留在「信息」界面
        return null;
    }
}
