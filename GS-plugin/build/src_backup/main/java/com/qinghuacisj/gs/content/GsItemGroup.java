package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.script.Script;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.groups.FlexItemGroup;
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile;
import io.github.thebusybiscuit.slimefun4.core.guide.GuideHistory;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.guide.SurvivalSlimefunGuide;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义物品组 —— 忠实移植 RSC 的 {@code RSCItemGroupLegacy}。
 *
 * <p>为什么必须自己写而不是直接用 Slimefun 的 {@code ItemGroup}：本附属的
 * 16 个物品组是 <b>button</b> 类型 —— 它们在指南里不打开物品列表，而是<b>点一下跑一段脚本</b>
 * （如「更新日志」按钮跑 {@code gs_update_log.js}）。Slimefun 原生的 ItemGroup 没有这个概念。
 *
 * <p>逐字保留的行为：
 * <ul>
 *   <li>内容可以是「粘液物品 / 子物品组 / 按钮动作字符串」三种之一</li>
 *   <li>显示顺序按 {@code display_tier} 升序（缺省取该组 tier，再缺省 1000）</li>
 *   <li>每页 36 格，从槽 9 开始排到槽 45 前</li>
 *   <li>可见性：{@code forceHidden || 有父组 || sub || button} ⇒ 主菜单里不显示</li>
 *   <li>按钮动作必须是「两段式」：{@code <类型> <内容>}，否则告警并忽略</li>
 * </ul>
 */
public final class GsItemGroup extends FlexItemGroup {

    /** 物品组类型。 */
    public enum Type {
        NESTED, SUB, SEASONAL, BUTTON, LOCKED, NORMAL;

        public static Type of(String s) {
            if (s == null) {
                return null;
            }
            String v = s.trim().toLowerCase(java.util.Locale.ROOT);
            // 别名（与 RSC 的 GroupType 一致）
            if (v.equals("parent")) {
                return NESTED;
            }
            if (v.equals("action")) {
                return BUTTON;
            }
            for (Type t : values()) {
                if (t.name().toLowerCase(java.util.Locale.ROOT).equals(v)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** display_tier 全局表 —— 与 RSC 一样是静态的，按对象身份索引。 */
    private static final Map<Object, Integer> DISPLAY_TIERS = new ConcurrentHashMap<>();
    private static final int DEFAULT_TIER = 1000;

    public static void registerDisplayTier(Object instance, int tier) {
        DISPLAY_TIERS.put(instance, tier);
    }

    public static int displayTier(Object instance) {
        Integer v = DISPLAY_TIERS.get(instance);
        if (v != null) {
            return v;
        }
        if (instance instanceof ItemGroup g) {
            return g.getTier();
        }
        return DEFAULT_TIER;
    }

    // ------------------------------------------------------------------

    private final List<Object> contents = new ArrayList<>();
    private final Type type;
    private final boolean forceHidden;
    private final boolean hasParent;
    private final int page;
    private final Script script;
    private final boolean seasonalVisible;

    public GsItemGroup(NamespacedKey key, ItemStack item, int tier, Type type,
                       boolean forceHidden, boolean hasParent, int page,
                       Script script, boolean seasonalVisible) {
        super(key, item, tier);
        this.type = type;
        this.forceHidden = forceHidden;
        this.hasParent = hasParent;
        this.page = page;
        this.script = script;
        this.seasonalVisible = seasonalVisible;
    }

    public void addContent(SlimefunItem sf) {
        contents.add(sf);
    }

    public void addContent(ItemGroup g) {
        contents.add(g);
    }

    public void addContent(String action) {
        contents.add(action);
    }

    public List<Object> getContents() {
        return contents;
    }

    public Type type() {
        return type;
    }

    // ------------------------------------------------------------ 可见性

    @Override
    public boolean isVisible(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        if (forceHidden || hasParent || type == Type.SUB || type == Type.BUTTON) {
            return false;
        }
        if (type == Type.NESTED || type == Type.NORMAL) {
            return true;   // 与 RSC 一致的兼容处理
        }
        return seasonalVisible;
    }

    public boolean isVisibleInNested(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        return !forceHidden && seasonalVisible;
    }

    // ------------------------------------------------------------ 打开

    @Override
    public void open(Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        GuideHistory history = profile.getGuideHistory();
        if (mode == SlimefunGuideMode.SURVIVAL_MODE) {
            history.add(this, page);
        }

        ChestMenu menu = new ChestMenu(Slimefun.getLocalization().getMessage(p, "guide.title.main"));
        SurvivalSlimefunGuide guide =
                (SurvivalSlimefunGuide) Slimefun.getRegistry().getSlimefunGuide(mode);
        menu.setEmptySlotsClickable(false);
        menu.addMenuOpeningHandler(SoundEffect.GUIDE_BUTTON_CLICK_SOUND::playFor);
        guide.createHeader(p, profile, menu);

        ItemStack backIcon = history.size() > 1
                ? ChestMenuUtils.getBackButton(p, "",
                        ChatColor.GRAY + Slimefun.getLocalization().getMessage(p, "guide.back.guide"))
                : ChestMenuUtils.getBackButton(p, "",
                        "&f左键: &7返回上一页",
                        "&fShift + 左键: &7返回主菜单");
        menu.addItem(1, backIcon, (pl, s, is, action) -> {
            if (history.size() == 1 || action.isShiftClicked()) {
                SlimefunGuide.openMainMenu(profile, mode, history.getMainMenuPage());
                return false;
            }
            history.goBack(guide);
            return false;
        });

        int index = 9;
        int target = 36 * (page - 1) - 1;

        List<Object> valid = contents.stream()
                .filter(c -> visibleInGroup(c, p, profile, mode))
                .sorted(Comparator.comparingInt(GsItemGroup::displayTier))
                .toList();

        while (target < valid.size() - 1 && index < 45) {
            ++target;
            Object content = valid.get(target);
            if (content instanceof GsItemGroup sub) {
                menu.addItem(index, sub.getItem(p));
                menu.addMenuClickHandler(index, (pl, slot, item, action) -> {
                    if (sub.type == Type.BUTTON) {
                        // 按钮：不打开子组，只跑动作
                        for (Object o : sub.contents) {
                            if (o instanceof String ac) {
                                readAction(ac, mode, pl, slot, item, action);
                            }
                        }
                        return false;
                    }
                    sub.open(pl, profile, mode);
                    return false;
                });
            } else if (content instanceof ItemGroup g) {
                menu.addItem(index, g.getItem(p));
                menu.addMenuClickHandler(index, (pl, slot, item, action) -> {
                    Slimefun.getRegistry().getSlimefunGuide(mode).openItemGroup(profile, g, 1);
                    return false;
                });
            } else if (content instanceof SlimefunItem sf) {
                menu.addItem(index, sf.getItem());
                menu.addMenuClickHandler(index, (pl, slot, item, action) -> {
                    Slimefun.getRegistry().getSlimefunGuide(mode).displayItem(profile, sf, true);
                    return false;
                });
            }
            index++;
        }

        // 翻页按钮
        if (page > 1) {
            menu.addItem(46, ChestMenuUtils.getPreviousButton(p, page, (valid.size() - 1) / 36 + 1));
            menu.addMenuClickHandler(46, (pl, slot, item, action) -> {
                newPage(page - 1).open(pl, profile, mode);
                return false;
            });
        }
        if (index == 45 && target < valid.size() - 1) {
            menu.addItem(52, ChestMenuUtils.getNextButton(p, page, (valid.size() - 1) / 36 + 1));
            menu.addMenuClickHandler(52, (pl, slot, item, action) -> {
                newPage(page + 1).open(pl, profile, mode);
                return false;
            });
        }

        menu.open(p);
    }

    private GsItemGroup newPage(int newPage) {
        // ★ 用父类 protected 的 item 字段（FlexItemGroup#getItem(Player) 要玩家，
        //   翻页时手上没有玩家，也不能为此改物品外观）
        GsItemGroup g = new GsItemGroup(getKey(), item, getTier(), type,
                forceHidden, hasParent, newPage, script, seasonalVisible);
        g.contents.addAll(contents);
        return g;
    }

    /** 组内某个内容是否可见。 */
    private boolean visibleInGroup(Object content, Player p, PlayerProfile profile, SlimefunGuideMode mode) {
        if (content instanceof GsItemGroup g) {
            return g.isVisibleInNested(p, profile, mode);
        }
        return true;
    }

    // ------------------------------------------------------------ 按钮动作

    /**
     * 执行一条按钮动作。格式必须恰好两段：{@code <类型> <内容>}。
     *
     * <p>本附属只用到 {@code script} 一种；其余四种按 RSC 原样实现，
     * 以免将来加了动作却静默失效。
     */
    public void readAction(String action, SlimefunGuideMode mode, Player p, int slot,
                           ItemStack clickedItem, ClickAction clickAction) {
        String[] parts = action.split(" ");
        if (parts.length != 2) {
            GS.warn("物品组 %s 的按钮动作格式非法（应为两段）: %s", getKey().getKey(), action);
            return;
        }
        String kind = parts[0];
        String content = parts[1];

        switch (kind) {
            case "script" -> {
                // ★ 与 RSC 一致：直接按 basename 找脚本，绕过任何缓存
                Script s = Script.of(content);
                if (s == null) {
                    GS.warn("物品组 %s 的按钮脚本不存在: %s.js", getKey().getKey(), content);
                    return;
                }
                s.call("onButtonGroupClick", p, slot, clickedItem, clickAction, mode);
            }
            case "console" -> {
                content = action.replace(kind + " ", "");
                org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(),
                        content.replaceAll("%player%", p.getName()));
            }
            case "open_itemgroup" -> {
                String[] k = content.split(":");
                if (k.length < 2) {
                    GS.warn("物品组 %s 的 open_itemgroup 非法: %s", getKey().getKey(), content);
                    return;
                }
                int pg = 1;
                if (k.length > 2) {
                    try {
                        pg = Integer.parseInt(k[2]);
                    } catch (NumberFormatException ignored) {
                        // 保持 1
                    }
                }
                for (ItemGroup g : Slimefun.getRegistry().getAllItemGroups()) {
                    if (g.getKey().getNamespace().equals(k[0]) && g.getKey().getKey().equals(k[1])) {
                        Slimefun.getRegistry().getSlimefunGuide(mode)
                                .openItemGroup(profileOf(p), g, pg);
                        break;
                    }
                }
            }
            case "display_slimefunitem" -> {
                SlimefunItem sf = SlimefunItem.getById(GS.idOf(content));
                if (sf == null) {
                    GS.warn("物品组 %s 的 display_slimefunitem 找不到物品: %s",
                            getKey().getKey(), content);
                    return;
                }
                Slimefun.getRegistry().getSlimefunGuide(mode).displayItem(profileOf(p), sf, true);
            }
            case "link" -> p.sendMessage(com.qinghuacisj.gs.util.Chat.t(
                    "&e单击此处打开链接: &7" + content));
            default -> GS.warn("物品组 %s 的按钮动作类型未知: %s", getKey().getKey(), kind);
        }
    }

    private static PlayerProfile profileOf(Player p) {
        return PlayerProfile.find(p).orElse(null);
    }
}
