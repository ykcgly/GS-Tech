package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.api.events.AsyncMachineOperationFinishEvent;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineProcessHolder;
import io.github.thebusybiscuit.slimefun4.core.machines.MachineProcessor;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.blocks.BlockPosition;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.Event;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机器操作管理器 —— 等价于 RSC 的 {@code bulit_in\recipes\AdvancedMachineProcessor}。
 *
 * <p>为什么不用核心 {@code MachineProcessor} 自带的 map：核心那张表在 GuguProject 分支里
 * 被存到了方块数据里（{@code StorageCacheUtils}），而 RSC 的配方对象
 * （{@link CustomMachineRecipe}）<b>不可序列化</b>（含 {@code InputWrapper} / 随机数账本）。
 * 所以 RSC 自己开了一张 {@code ConcurrentHashMap<BlockPosition, CraftingRecipeOperation>}。
 * 原生版照做。
 *
 * <p>★ 三处必须逐字保留的行为：
 * <ol>
 *   <li>{@code startOperation} 是 {@code putIfAbsent} —— <b>已在跑的操作不会被顶掉</b>，
 *       所以「同一台机器同一刻只可能有一个操作」这条不变量由这里保证。</li>
 *   <li>废弃的 {@code startOperation(..., CraftingOperation)} 三个重载<b>直接返回 false</b>，
 *       不落表。这是 RSC 故意留的「防止核心 {@code AContainer.tick} 误启动」的闸门。</li>
 *   <li>{@code endOperation} 只在操作<b>已完成</b>时发 {@code AsyncMachineOperationFinishEvent}；
 *       提前结束（拆机器、断电）走 {@code operation.onCancel(pos)}，<b>不发事件</b>。</li>
 * </ol>
 */
public class MachineProcessorEx extends MachineProcessor<CraftingOperation> {

    private final Map<BlockPosition, CraftingRecipeOperation> operations = new ConcurrentHashMap<>();

    public MachineProcessorEx(MachineProcessHolder<CraftingOperation> owner) {
        super(owner);
    }

    // ------------------------------------------------------------------ 启动

    @Override
    @Deprecated
    public boolean startOperation(Location loc, CraftingOperation operation) {
        return false;
    }

    @Override
    @Deprecated
    public boolean startOperation(Block b, CraftingOperation operation) {
        return false;
    }

    @Override
    @Deprecated
    public boolean startOperation(BlockPosition pos, CraftingOperation operation) {
        return false;
    }

    public boolean startOperation(Location loc, CraftingRecipeOperation operation) {
        return startOperation(new BlockPosition(loc), operation);
    }

    public boolean startOperation(Block b, CraftingRecipeOperation operation) {
        return startOperation(b.getLocation(), operation);
    }

    public boolean startOperation(BlockPosition pos, CraftingRecipeOperation operation) {
        return operations.putIfAbsent(pos, operation) == null;
    }

    // ------------------------------------------------------------------ 查询

    @Override
    public CraftingRecipeOperation getOperation(Location loc) {
        return operations.get(new BlockPosition(loc));
    }

    @Override
    public CraftingRecipeOperation getOperation(Block b) {
        return getOperation(b.getLocation());
    }

    @Override
    public CraftingRecipeOperation getOperation(BlockPosition pos) {
        return operations.get(pos);
    }

    // ------------------------------------------------------------------ 结束

    @Override
    public boolean endOperation(Location loc) {
        return endOperation(new BlockPosition(loc));
    }

    @Override
    public boolean endOperation(Block b) {
        return endOperation(b.getLocation());
    }

    @Override
    public boolean endOperation(BlockPosition pos) {
        CraftingRecipeOperation operation = operations.remove(pos);

        if (operation == null) {
            return false;
        }

        // 只有「真的做完了」才发事件；提前中止走 onCancel（与核心一致）
        if (operation.isFinished()) {
            Event event = new AsyncMachineOperationFinishEvent(pos, this, operation);
            Bukkit.getPluginManager().callEvent(event);
        } else {
            operation.onCancel(pos);
        }
        return true;
    }

    // ------------------------------------------------------------------ 进度条

    @Override
    public void updateProgressBar(BlockMenu inv, int slot, CraftingOperation operation) {
        if (getProgressBar() == null) {
            return;
        }

        int remainingTicks = operation.getRemainingTicks();
        int totalTicks = operation.getTotalTicks();

        // 操作已完成时不必再刷（核心 #3538 的修法，照搬）
        if (remainingTicks > 0 || totalTicks > 0) {
            ChestMenuUtils.updateProgressbar(inv, slot, remainingTicks, totalTicks, getProgressBar());
        }
    }
}
