package com.qinghuacisj.gs.content;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;

import java.util.ArrayList;
import java.util.List;

/**
 * 本附属自建物品组的登记表。
 *
 * <p>为什么需要它：{@code items.yml} 里的 {@code item_group: gs_basic} 必须解析到
 * <b>本附属自己</b>刚建的那个组，而不是粘液全局表。而 {@code gs_main}（主分类）
 * 是个 {@code nested} 组，它的子组是通过 {@code parent:} 挂上去的，
 * 在 Slimefun 全局表里的形态与 RSC 的组并不完全等价。
 *
 * <p>查找顺序与原版 {@code CommonUtils.getItemGroup} 一致：
 * <b>先本附属自建的，再粘液全局的</b>，都按 {@code key} 忽略大小写比较。
 */
public final class Groups {

    private static final List<ItemGroup> OWN = new ArrayList<>();

    private Groups() {
    }

    public static void register(ItemGroup g) {
        if (g != null) {
            OWN.add(g);
        }
    }

    /** 按 key 找组（忽略大小写）；找不到返回 null。 */
    public static ItemGroup find(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (ItemGroup g : OWN) {
            if (g.getKey().getKey().equalsIgnoreCase(id)) {
                return g;
            }
        }
        for (ItemGroup g : Slimefun.getRegistry().getAllItemGroups()) {
            if (g.getKey().getKey().equalsIgnoreCase(id)) {
                return g;
            }
        }
        return null;
    }

    /** 本附属自建的组（按注册顺序）。 */
    public static List<ItemGroup> own() {
        return List.copyOf(OWN);
    }

    public static void clear() {
        OWN.clear();
    }
}
