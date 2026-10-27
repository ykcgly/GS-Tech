package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/**
 * 带配方的合成操作 —— 等价于 RSC 的 {@code bulit_in\recipes\CraftingRecipeOperation}。
 *
 * <p>存在的理由有两个：
 * <ol>
 *   <li>核心 {@code CraftingOperation} 只认「配料数组 + 产物数组」，而 RSC 的产物是<b>运行时掷点</b>的，
 *       操作对象必须反过来持有那个 {@link CustomMachineRecipe}，完工时才能调 {@code pushOutputs}。</li>
 *   <li><b>PLACEHOLDER 是为了骗过构造器校验。</b> {@code CraftingOperation(ItemStack[], ItemStack[], int)}
 *       会 {@code Validate.notEmpty(ingredients)} / {@code Validate.notEmpty(results)} /
 *       {@code Validate.isTrue(ticks >= 0)}。材料发生器<b>零输入</b>，传真实空数组会直接抛异常；
 *       所以两边都塞一个 {@code {AIR}} 占位。RSC 从不读 {@code getIngredients()}/{@code getResults()}，
 *       产物一律从配方对象取 —— 照搬。</li>
 * </ol>
 *
 * <p>★ 顺带解释了「为什么 {@code seconds: 0} 合法」：{@code ticks = 0} 不触发
 * {@code ticks >= 0} 校验，且 {@code isFinished()} 立刻为真 ⇒ 产物在第 1 刻就出。
 */
public class CraftingRecipeOperation extends CraftingOperation {

    /** 骗过 {@code CraftingOperation} 的非空校验用的占位数组（绝不读取）。 */
    private static final ItemStack[] PLACEHOLDER = new ItemStack[] {new ItemStack(Material.AIR)};

    private final CustomMachineRecipe recipe;

    public CraftingRecipeOperation(CustomMachineRecipe recipe) {
        this(recipe, recipe.getTicks());
    }

    public CraftingRecipeOperation(CustomMachineRecipe recipe, int ticks) {
        super(PLACEHOLDER, PLACEHOLDER, ticks);
        this.recipe = recipe;
    }

    public CustomMachineRecipe getRecipe() {
        return recipe;
    }
}
