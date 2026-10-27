package com.qinghuacisj.gs.machine;

import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 配方机器 tick 逻辑 —— 等价于 RSC 的 {@code bulit_in\tickers\RecipeMachineTicker}。
 *
 * <p>这是整个机器层最核心的一段循环，逐字复刻 RSC 的时序（<b>不要</b>换成核心
 * {@code AContainer.tick}，核心版没有 {@code hasViewer()} 守卫、进度槽位写死 22、
 * 完工固定刷黑玻璃板、也不认概率/免消耗）：
 *
 * <pre>
 * 每粘液刻：
 *   ① 扣电（不够就整刻作废）
 *   ② 取菜单；取不到就返回
 *   ③ 没有在跑的操作：
 *        拍 InvIndex 快照 → 先试「上次那条配方」是否仍可匹配（可匹配就不换）
 *        → 否则找新配方（找不到就返回）
 *        → 建 CraftingRecipeOperation 并登记
 *        → 若 0 刻就完成，立刻出货；否则刷进度条
 *   ④ 有操作但没完成：刷进度条（仅当有玩家在看）+ 进度 +1
 *   ⑤ 有操作且已完成：出货
 * </pre>
 *
 * <p>★ 由此推出「一次合成占 {@code T + 2} 个粘液刻」：第 1 刻建操作、第 2..T 刻推进度、
 * 第 T+1 刻出货。当 {@code T <= 0} 时操作一建出来就是完成态，产物在第 1 刻就出（只 +1）。
 * 本附属 {@code GS_INSPIRATION_CONVERTER}（{@code seconds: 3}、{@code speed: 1}）：
 * raw = 6 → T = 6 → 一个周期 8 粘液刻 = 4 秒，配方页写「制作时间: 3s」。
 *
 * <p>★ 两个 {@code hasViewer()} 守卫必须保留：它们决定了「没人在看时不刷进度条」，
 * 换成核心逻辑会变成无条件刷 —— 在无人值守的机器阵列里就是无谓的菜单写入。
 */
public final class RecipeMachineTicker extends MachineTicker {

    public RecipeMachineTicker(AbstractMachine machine, MachineMenu menu, List<CustomMachineRecipe> recipes) {
        super(machine, menu, recipes);
    }

    @Override
    public boolean preTick(Location location) {
        return takeCharge(location);
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
            CustomMachineRecipe recipe = getCache(location);
            // ★ 上次那条配方仍然可匹配就不换 —— 否则两条配方都能吃同一种料时会来回横跳
            if (recipe == null || !recipe.matches(index)) {
                recipe = findNextRecipe(index, recipe);
                if (recipe == null) {
                    return;
                }
                setCache(location, recipe);
            }

            currentOperation = new CraftingRecipeOperation(recipe);
            getProcessor().startOperation(location, currentOperation);
            if (currentOperation.isFinished()) {
                finishOperation(currentOperation, inv, location);
            } else if (inv.hasViewer()) {
                getProcessor().updateProgressBar(inv, getProgressSlot(), currentOperation);
            }
            return;
        }

        if (!currentOperation.isFinished()) {
            if (inv.hasViewer()) {
                getProcessor().updateProgressBar(inv, getProgressSlot(), currentOperation);
            }
            currentOperation.addProgress(1);
            return;
        }

        finishOperation(currentOperation, inv, location);
    }

    /**
     * 完工：出货 + 把进度槽恢复成菜单声明的那个物品（<b>不是</b>固定黑玻璃板）。
     *
     * <p>★ 恢复用的物品是「菜单里 {@code progressSlot} 槽原本放的东西」，菜单没写就用
     * {@code ChestMenuUtils.getBackground()} —— 这是 RSC 的写法，别改成默认进度条材质，
     * 否则 {@code GS_SEA_FILTER} 那种「空闲 = 纸」的菜单会在合成后变成黑玻璃板。
     */
    private void finishOperation(CraftingRecipeOperation currentOperation, BlockMenu inv, Location location) {
        currentOperation.getRecipe().pushOutputs(inv);

        if (inv.hasViewer()) {
            ItemStack progress;
            if (getMenu() == null) {
                progress = ChestMenuUtils.getBackground();
            } else {
                progress = getMenu().getItems().getOrDefault(getProgressSlot(), ChestMenuUtils.getBackground());
            }
            inv.replaceExistingItem(getProgressSlot(), progress);
        }

        getProcessor().endOperation(location);
    }
}
