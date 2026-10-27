package com.qinghuacisj.gs.machine;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 材料发生器 tick 逻辑 —— 等价于 RSC 的 {@code bulit_in\tickers\MaterialGeneartorMachineTicker}
 * （类名里的拼写错误是 RSC 原文，原生版没有沿用）。
 *
 * <p>与配方机器的差别（每一条都会影响线上表现）：
 * <ol>
 *   <li><b>只有一条配方</b>，{@code getRecipes().getFirst()}，永远用它。没有「上次配方」缓存。</li>
 *   <li><b>没有进度条</b>，改用一个固定状态格 {@code status:}：
 *       电量不足写「电力不足」（红玻璃板）、生产中写「生产中」（绿玻璃板）、
 *       出货失败写「空间不足」（橙玻璃板）。</li>
 *   <li>★ <b>状态格来自 {@code mat_generators.yml} 的 {@code status:}，不是菜单的 {@code progressbar:}</b>
 *       （G-15）。两个文件各读各的、从不交叉校验，本附属两份 yml 里恰好都是槽 4。</li>
 *   <li>★ <b>完工时不恢复状态格</b>（G-16）：成功出货只是结束操作，格子仍显示「生产中」，
 *       下一刻立刻又开新操作 —— 所以只要有空位，这个格子<b>长期就是「&amp;a生产中」</b>。
 *       只有扣电失败才会被改成「电力不足」。</li>
 *   <li><b>没有 onTick 脚本钩子</b>（RSC 的生成器 ticker 从不调脚本）。</li>
 * </ol>
 *
 * <p>★ {@code getOnlyRecipe().matches(index)} 在原生版走的是
 * {@link CustomMachineRecipe#matches(InvIndex)}：输入为空（发生器零输入）所以输入检查是空转，
 * 但<b>产物放不下的检查仍然生效</b> —— 满仓时不会开新操作。
 * （RSC 装了 LogiTech 时会换成 {@code MGeneratorRecipeRSC.matches}，那个是无条件 return true；
 * 本附属未安装 LogiTech，所以走的就是这里的路径。）
 *
 * <p>★ 配方页显示的是合成速度 + 每个产物成对出现，速度文案用的是
 * <b>{@code tickRate} 本身</b>（不是 {@code tickRate + 2}）—— 与 RSC 一致。
 */
public final class MaterialGeneratorTicker extends MachineTicker {

    /** 状态格：电力不足。 */
    public static final ItemStack NO_POWER =
            new CustomItemStack(Material.RED_STAINED_GLASS_PANE, "&4电力不足", "");
    /** 状态格：空间不足。 */
    public static final ItemStack NO_SPACE =
            new CustomItemStack(Material.ORANGE_STAINED_GLASS_PANE, "&c空间不足", "");
    /** 状态格：生产中。 */
    public static final ItemStack PROCESSING =
            new CustomItemStack(Material.LIME_STAINED_GLASS_PANE, "&a生产中", "");

    private final int statusSlot;

    public MaterialGeneratorTicker(AbstractMachine machine, MachineMenu menu,
                                   List<CustomMachineRecipe> recipes, int statusSlot) {
        super(machine, menu, recipes);
        this.statusSlot = statusSlot;
    }

    public int getStatusSlot() {
        return statusSlot;
    }

    /** ★ 扣电失败时先把状态格刷成「电力不足」（仅当有玩家在看），再作废本刻。 */
    @Override
    public boolean preTick(Location location) {
        if (!takeCharge(location)) {
            if (statusSlot == -1) {
                return false;
            }
            BlockMenu inv = StorageCacheUtils.getMenu(location);
            if (inv != null && inv.hasViewer()) {
                inv.replaceExistingItem(statusSlot, NO_POWER);
            }
            return false;
        }
        return true;
    }

    @Override
    public void tick(Location location) {
        if (!preTick(location)) {
            return;
        }

        BlockMenu inv = StorageCacheUtils.getMenu(location);
        if (inv == null) {
            return;
        }

        CraftingRecipeOperation currentOperation = getProcessor().getOperation(location);
        if (currentOperation == null) {
            InvIndex index = InvIndex.create(inv);
            if (getOnlyRecipe().matches(index)) {
                currentOperation = new CraftingRecipeOperation(getOnlyRecipe());
                getProcessor().startOperation(location, currentOperation);
                if (currentOperation.isFinished()) {
                    finishOperation(currentOperation, inv, location);
                }
            }
            return;
        }

        if (!currentOperation.isFinished()) {
            if (statusSlot != -1 && inv.hasViewer()) {
                inv.replaceExistingItem(statusSlot, PROCESSING);
            }
            currentOperation.addProgress(1);
            return;
        }

        finishOperation(currentOperation, inv, location);
    }

    /** ★ 只有出货失败才写「空间不足」；成功时<b>不</b>复位状态格（G-16）。 */
    private void finishOperation(CraftingRecipeOperation currentOperation, BlockMenu inv, Location location) {
        if (!currentOperation.getRecipe().pushOutputs(inv)) {
            if (statusSlot != -1 && inv.hasViewer()) {
                inv.replaceExistingItem(statusSlot, NO_SPACE);
            }
        }
        getProcessor().endOperation(location);
    }

    public CustomMachineRecipe getOnlyRecipe() {
        return getRecipes().getFirst();
    }

    /** ★ 速度文案里的 tick 数就是 {@code tickRate}（有效 tick），不是 {@code tickRate + 2}。 */
    @Override
    public List<ItemStack> getDisplayRecipes() {
        ItemStack speed = new CustomItemStack(
                Material.KNOWLEDGE_BOOK,
                "&a&l速度",
                "&a&l每 &b&l" + getOnlyRecipe().getTicks() + " &a&l个粘液刻生成一次");
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack gen : getOnlyRecipe().getOutput()) {
            list.add(speed);
            list.add(gen);
        }
        return list;
    }

    /** ★ 发生器零输入 —— 返回空数组，菜单里第 9 行往上全是输出槽。 */
    @Override
    public int[] getInputSlots() {
        return new int[0];
    }
}
