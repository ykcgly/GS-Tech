package com.qinghuacisj.gs.content;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.NotPlaceable;
import org.bukkit.inventory.ItemStack;

/**
 * 「不可放置」的自定义物品。
 *
 * <p>★★★ <b>这个类存在的原因是一个极易踩的反转语义。</b>
 *
 * <p>RSC 的 {@code ItemReader} 里写的是：
 * <pre>
 *   if (section.getBoolean("placeable", false)) {
 *       // 注入 NotPlaceable
 *   }
 * </pre>
 * 也就是说 <b>{@code placeable: true} 的含义是「不可放置」</b>，与字面完全相反。
 * {@code placeable: false}（本附属用了 45 次）其实是<b>空操作</b>。
 *
 * <p>本附属真正受影响的只有一件：<b>{@code GS_BASKET_GEN}</b>（篮球生成器，
 * {@code items.yml:1698} 写着 {@code placeable: true}）。
 * 照字面理解会写成「可放置」⇒ 玩家能把它当方块插在地上，而原版是<b>不能</b>的。
 * 这就是「naive 移植最可能出现的静默行为偏差」。
 *
 * <p>判定链路（javap 实证）：Slimefun4 的 {@code BlockListener} 里是
 * {@code slimefunItem instanceof NotPlaceable} ⇒ 直接跳过放置处理。
 * 所以只要类实现了 {@link NotPlaceable} 即可，不需要额外挂 {@code ItemUseHandler}
 * ——RSC 用 ByteBuddy 动态注入时也<b>只</b>加了这一个接口，没有加 handler。
 *
 * <p>原生版不用 ByteBuddy：{@code placeable} 是每个条目独立的开关，
 * 拆成两个类（本类与 {@link GsItem}）就足够了，比运行时生成类更好读也更好调试。
 */
public class GsUnplaceableItem extends GsItem implements NotPlaceable {

    public GsUnplaceableItem(ItemGroup group, SlimefunItemStack stack, RecipeType recipeType,
                             ItemStack[] recipe, ItemStack output) {
        super(group, stack, recipeType, recipe, output);
    }
}
