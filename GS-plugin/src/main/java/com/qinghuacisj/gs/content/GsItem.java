package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

/**
 * 普通自定义物品 —— 等价于 RSC 的 {@code CustomItem}。
 *
 * <p>★★ <b>必须自己覆写 {@link #load()}</b>。原因（javap 实证）：
 * Slimefun4 的 {@code SlimefunItem.preRegister()} 与 {@code postRegister()}
 * 都是<b>空实现</b>（字节码只有一条 {@code return}）——
 * <b>Slimefun 不会自动把物品挂进它的物品组</b>。而 {@code register()} 的最后一步
 * 会调用 {@code load()}，所以「挂组」和「登记配方」都得在这里做。
 *
 * <p>★ 挂组时不能直接调 {@code ItemGroup#add}：本附属的物品组是
 * {@link GsItemGroup}（继承 {@code FlexItemGroup}），而
 * {@code FlexItemGroup#add} 是 {@code final} 且<b>直接抛
 * UnsupportedOperationException</b>（javap 看过字节码：「You cannot add items to a
 * FlexItemGroup!」）。所以要走 {@link GsItemGroup#addContent}。
 */
public class GsItem extends SlimefunItem {

    public GsItem(ItemGroup group, SlimefunItemStack stack, RecipeType recipeType,
                  ItemStack[] recipe, ItemStack output) {
        super(group, stack, recipeType, recipe, output);
    }

    @Override
    public void load() {
        if (!isHidden()) {
            attachToGroup();
        }
        // 把配方登记到配方类型上 —— 指南里点开物品时显示的合成表就靠这一句。
        // （与 RSC 一样不加判空：NULL 类型时抛异常由加载器的 try/catch 兜住。）
        getRecipeType().register(getRecipe(), getRecipeOutput());
    }

    /** 与 RSC 的 {@code BaseRSCItemGroup.addItemToGroup0} 逐字等价。 */
    private void attachToGroup() {
        ItemGroup group = getItemGroup();
        if (group == null) {
            return;
        }
        if (group instanceof GsItemGroup gs) {
            gs.addContent(this);
            return;
        }
        if (group instanceof io.github.thebusybiscuit.slimefun4.api.items.groups.FlexItemGroup) {
            // 外部附属的 FlexItemGroup：加不进去（Slimefun 的限制），只能告警
            GS.warn("无法把物品 %s 加入 %s：它是 FlexItemGroup", getId(), group.getKey());
            return;
        }
        group.add(this);
    }
}
