package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 配方机器 —— {@code recipe_machines.yml} 里那 4 台机器（含只有钓鱼概率表、没有 {@code recipe:} 的
 * {@code GS_SEA_FILTER}）都是这个类。
 *
 * <p>它自己不写任何逻辑，只是把「输入/输出槽 + 耗电 + 容量 + 速度」交给
 * {@link AbstractMachine}，把 tick 循环交给 {@link RecipeMachineTicker}。
 * 之所以拆出这一层而不是让 {@code AbstractMachine} 直接可实例化：RSC 里
 * 「配方机器」和「材料发生器」是同一个 {@code AdvancedCustomMachine} 类配不同 ticker，
 * 但两者的<b>配方页渲染</b>、<b>状态格</b>、<b>输入槽</b>差别很大，
 * 原生版用两个具体类把差异钉死在类型上，读代码时不用再回 yml 猜。
 *
 * <p>★ 构造完成后必须调 {@link #setTicker}（内部 {@code ticker.init()} + {@code register}），
 * 由 {@code RecipeMachineLoader} 负责。
 */
public class RecipeMachine extends AbstractMachine {

    public RecipeMachine(ItemGroup group, SlimefunItemStack stack, RecipeType recipeType,
                         ItemStack[] recipe, ItemStack recipeOutput,
                         int[] input, int[] output, int energyPerCraft, int capacity, int speed,
                         MachineMenu menu, List<CustomMachineRecipe> recipes) {
        super(group, stack, recipeType, recipe, recipeOutput, input, output, energyPerCraft, capacity, speed);
        setTicker(new RecipeMachineTicker(this, menu, recipes));
    }
}
