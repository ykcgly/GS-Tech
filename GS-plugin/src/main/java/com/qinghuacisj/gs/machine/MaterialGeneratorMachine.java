package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 材料发生器 —— {@code mat_generators.yml} 里那 3 台机器
 * （等价于 RSC 的 {@code customs\CustomMaterialGenerator}）。
 *
 * <p>与 {@link RecipeMachine} 的差别全部体现在 {@link MaterialGeneratorTicker} 里：
 * 只有一条配方、没有进度条（改用 {@code status:} 状态格）、零输入、
 * 有效 tick 数就是 {@code tickRate}（不乘 2）、{@code speed} 硬编码为 1。
 *
 * <p>★ {@code status} 由 {@code mat_generators.yml} 直接给出（本附属 3 台全是 {@code 4}），
 * <b>不是</b>从 {@code menus.yml} 的 {@code progressbar:} 推出来的 —— 两份文件从不交叉校验（G-15）。
 * 读 yml 时 {@code status} 缺省为 {@code -1}，表示「不刷状态格」。
 *
 * <p>★ {@link #onNewInstance} 是 RSC {@code CustomMaterialGenerator} 覆写的那一个：
 * 给状态格挂一个「空处理器」，让玩家<b>点不动</b>它（返回 false ⇒ 事件被取消）。
 * 这一条<b>必须保留</b>：否则状态格在菜单里就是一个可自由移动的普通格子，
 * 玩家能把「电力不足」的红玻璃板顺走，整台机器的状态显示就废了。
 * 注意 RSC 是<b>无条件</b>调用的（{@code status == -1} 时写进 {@code -1} 号槽的处理器表，
 * 没有副作用），原生版照抄。
 */
public class MaterialGeneratorMachine extends AbstractMachine {

    private final int statusSlot;

    public MaterialGeneratorMachine(ItemGroup group, SlimefunItemStack stack, RecipeType recipeType,
                                    ItemStack[] recipe, ItemStack recipeOutput,
                                    int[] output, int energyPerCraft, int capacity, int speed,
                                    int statusSlot, MachineMenu menu, List<CustomMachineRecipe> recipes) {
        // ★ 发生器零输入：input 恒为空数组
        super(group, stack, recipeType, recipe, recipeOutput, new int[0], output,
                energyPerCraft, capacity, speed);
        this.statusSlot = statusSlot;
        setTicker(new MaterialGeneratorTicker(this, menu, recipes, statusSlot));
    }

    public int getStatusSlot() {
        return statusSlot;
    }

    /** ★ 与 {@link AbstractMachine#getInputSlots()} 同义，显式写出来强调「发生器没有输入」。 */
    @Override
    public int[] getInputSlots() {
        return new int[0];
    }

    @Override
    public void onNewInstance(BlockMenu menu, Block b) {
        // ★ 逐字等价于 RSC：状态格不可点击（getEmptyClickHandler 返回 false ⇒ 取消事件）
        menu.addMenuClickHandler(statusSlot, ChestMenuUtils.getEmptyClickHandler());
    }
}
