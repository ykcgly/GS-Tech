package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.script.Script;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 物品组加载器 —— 等价于 RSC 的 {@code ItemGroupReader}。
 *
 * <p>本附属 24 个组，实际只用到 3 种 {@code type}：
 * <pre>
 *   button ×16   点一下跑脚本（更新日志 ×14、创作者、图鉴）
 *   sub     ×6   挂在 gs_main 下的子分类
 *   nested  ×2   gs_main（主分类）与 gs_info（按钮的父组）
 * </pre>
 *
 * <p>★★ <b>按钮组绝不能整类跳过</b>：16 个组里 14 个「更新日志」+ 图鉴 + 创作者
 * 一共 16 个脚本入口<b>只</b>通过按钮触发。跳过按钮组 = 这些脚本永远点不到。
 *
 * <p>★★ 另一个来自 RSC 源码的关键约束（本附属的 yml 注释里也写明了）：
 * <b>按钮的父组必须是 nested 组</b>，因为按钮要求 parent 是 {@code NestedItemGroup}。
 * 所以 {@code gs_info} 也必须写成 {@code nested}。
 *
 * <p>★★★ <b>parent 指向 nested 组时，RSC 会强制把子组降级为 {@code SubItemGroup}</b>，
 * 而 {@code SubItemGroup} 的 {@code isVisible}/{@code isAccessible} 是 <b>final</b> 的
 * ⇒ 子组<b>无法自定义可见性</b>。这是 Slimefun 的硬限制，原生版同样绕不过去。
 */
public final class GroupLoader extends Loader<ItemGroup> {

    public GroupLoader() {
        super("groups.yml");
    }

    // ------------------------------------------------------------ 预加载
    // 物品组不参与预加载（与原版 ItemGroupReader.preloadItems 返回空表一致）

    @Override
    protected List<SlimefunItemStack> preloadItems(String key, ConfigurationSection sec) {
        return List.of();
    }

    // ------------------------------------------------------------ 注册

    @Override
    protected ItemGroup readEach(String key, ConfigurationSection sec) {
        ConfigurationSection itemSec = sec.getConfigurationSection("item");
        ItemStack stack = Items.read(where(key) + ":item", itemSec);
        if (stack == null) {
            GS.warn("%s：缺少或配置错误的 'item'，已跳过", where(key));
            return null;
        }

        String typeName = sec.getString("type", "");
        GsItemGroup.Type type = GsItemGroup.Type.of(typeName);
        if (type == null) {
            GS.warn("%s：缺少或配置错误的 'type': %s，已跳过", where(key), typeName);
            return null;
        }

        // ★ 与原版一致：id 冲突检查（同名组已存在 ⇒ 跳过）
        if (Groups.find(key) != null) {
            GS.warn("%s：物品组 ID 冲突，已跳过", where(key));
            return null;
        }

        int tier = sec.getInt("tier", 3);
        boolean forceHidden = sec.getBoolean("forceHidden", false);
        String parentId = sec.getString("parent");

        // ---- 有父组 ----
        if (parentId != null) {
            ItemGroup raw = Groups.find(parentId);
            if (raw == null) {
                GS.warn("%s：无法找到父物品组 parent: %s，已跳过", where(key), parentId);
                return null;
            }
            // ★★ 分支顺序与 RSC 一致，但要注意：RSC 自己的组是 FlexItemGroup
            //    （不是 Slimefun 的 NestedItemGroup）⇒ 本附属整棵组树都是自建组，
            //    **一个 SubItemGroup 都不会产生**。NestedItemGroup 分支只对外部附属
            //    建的「真·嵌套组」生效。
            if (raw instanceof GsItemGroup parent) {
                GsItemGroup group = build(key, stack, tier, type, forceHidden, true, sec);
                parent.addContent(group);
                group.register(GS.plugin());
                Groups.register(group);
                return group;
            }
            if (raw instanceof NestedItemGroup nig) {
                if (type == GsItemGroup.Type.LOCKED) {
                    GS.warn("%s：LockedItemGroup 不能挂到 NestedItemGroup 下，已跳过", where(key));
                    return null;
                }
                // 父组是外部附属的 NestedItemGroup ⇒ 只能是 SubItemGroup，
                // 而它的 isVisible/isAccessible 是 final 的 ⇒ 自定义可见性失效。
                SubItemGroup sub = new SubItemGroup(GS.key(key), nig, stack, tier);
                nig.addSubGroup(sub);
                sub.register(GS.plugin());
                Groups.register(sub);
                return sub;
            }
            GS.warn("%s：无法把物品组挂到 parent: %s（类型不支持），已跳过", where(key), parentId);
            return null;
        }

        // ---- 无父组 ----
        if (type == GsItemGroup.Type.LOCKED) {
            GS.warn("%s：本附属未使用 locked 类型且缺少 parents 支持，已跳过", where(key));
            return null;
        }

        GsItemGroup group = build(key, stack, tier, type, forceHidden, false, sec);
        group.register(GS.plugin());
        Groups.register(group);
        return group;
    }

    /** 组装一个自建组（含按钮动作 / 脚本）。 */
    private GsItemGroup build(String key, ItemStack stack, int tier, GsItemGroup.Type type,
                              boolean forceHidden, boolean hasParent, ConfigurationSection sec) {
        // seasonal 类型：可见性 = 当前月份 == month（本附属未使用，保留语义）
        boolean seasonalVisible = true;
        if (type == GsItemGroup.Type.SEASONAL) {
            int month = sec.getInt("month", 1);
            seasonalVisible = month == java.time.LocalDate.now().getMonthValue();
        }

        Script script = null;
        String scriptName = sec.getString("script");
        if (scriptName != null && !scriptName.isBlank()) {
            script = Script.of(scriptName);
            if (script == null) {
                GS.warn("%s：物品组脚本不存在: %s", where(key), scriptName);
            } else {
                script.call("init");
            }
        }

        GsItemGroup group = new GsItemGroup(GS.key(key), stack, tier, type,
                forceHidden, hasParent, 1, script, seasonalVisible);

        // ★ 只有 button 组才读 actions（与原版一致）
        if (type == GsItemGroup.Type.BUTTON) {
            for (String action : sec.getStringList("actions")) {
                group.addContent(action);
            }
        }
        return group;
    }
}
