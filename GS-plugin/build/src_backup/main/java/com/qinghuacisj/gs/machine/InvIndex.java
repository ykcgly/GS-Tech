package com.qinghuacisj.gs.machine;

import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单快照索引 —— 等价于 RSC 的 {@code bulit_in\wrappers\InvIndex}。
 *
 * <p>为什么要它：核心 {@code AContainer.findNextRecipe} 是「逐槽位一对一」比对配方的，
 * 而 RSC 的配方是<b>合并后</b>的（一个 wrapper 覆盖多个槽位）。所以每 tick 都要先给菜单拍个快照：
 * 把 INSERT 槽里非空的物品按种类合并成 {@link SlotWrapper}，顺便数出 WITHDRAW 槽里有多少个<b>空槽</b>
 * （用于「产物放不下就别开工」的快判）。
 *
 * <p>★ 三个容易写错的点：
 * <ol>
 *   <li>{@code getConsumableAmount} 在<b>找不到匹配槽</b>时返回 {@code -100}（不是 0）——
 *       这样 {@code matches} 的 {@code >=} 判断会稳定失败，而不会把「没有」误当成「有 0 个」。</li>
 *   <li>空输出槽计数只在 WITHDRAW 槽里数，且「空气」也算空（{@code getType().isAir()}）。</li>
 *   <li>{@code matches} 的参数方向：{@code getConsumableAmount(wrapper) >= wrapper.getConsumeAmount()}，
 *       左边是菜单里的量、右边是配方要的量。</li>
 * </ol>
 */
public final class InvIndex {

    private final List<SlotWrapper> inputs;
    private final List<SlotWrapper> outputs;
    private final BlockMenu inv;
    private final int emptyOutputSlots;

    private InvIndex(List<SlotWrapper> inputs, List<SlotWrapper> outputs, BlockMenu inv, int emptyOutputSlots) {
        this.inputs = inputs;
        this.outputs = outputs;
        this.inv = inv;
        this.emptyOutputSlots = emptyOutputSlots;
    }

    /** 把一种物品并进已有 wrapper，或新建一个（合并判据是宽松比对）。 */
    public static void addToWrappers(ItemStack stack, int slot, List<SlotWrapper> wrappers) {
        for (SlotWrapper wrapper : wrappers) {
            if (StackUtils.itemsMatch(wrapper.getStack(), stack)) {
                wrapper.addAmount(slot, stack.getAmount());
                return;
            }
        }
        wrappers.add(SlotWrapper.create(stack, slot));
    }

    /** 给菜单拍快照。 */
    public static InvIndex create(BlockMenu inv) {
        List<SlotWrapper> inputs = new ArrayList<>();
        for (int slot : inv.getPreset().getSlotsAccessedByItemTransport(ItemTransportFlow.INSERT)) {
            ItemStack stack = inv.getItemInSlot(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            addToWrappers(stack, slot, inputs);
        }

        List<SlotWrapper> outputs = new ArrayList<>();
        int emptyOutputSlots = 0;
        for (int slot : inv.getPreset().getSlotsAccessedByItemTransport(ItemTransportFlow.WITHDRAW)) {
            ItemStack stack = inv.getItemInSlot(slot);
            if (stack == null || stack.getType().isAir()) {
                emptyOutputSlots++;
                continue;
            }
            addToWrappers(stack, slot, outputs);
        }

        return new InvIndex(inputs, outputs, inv, emptyOutputSlots);
    }

    /** 把一组物品栈按种类合并成 wrapper 列表（产物合并用）。 */
    public static List<ItemWrapper> mergeItems(ItemStack[] itemStacks) {
        List<ItemWrapper> items = new ArrayList<>();
        if (itemStacks == null) {
            return items;
        }
        for (ItemStack itemStack : itemStacks) {
            if (itemStack == null || itemStack.getType().isAir()) {
                continue;
            }
            boolean matched = false;
            for (ItemWrapper wrapper : items) {
                if (StackUtils.itemsMatch(wrapper.getStack(), itemStack)) {
                    wrapper.addAmount(itemStack.getAmount());
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                items.add(ItemWrapper.create(itemStack));
            }
        }
        return items;
    }

    public boolean contains(ItemStack item) {
        for (SlotWrapper wrapper : inputs) {
            if (StackUtils.itemsMatch(wrapper.getStack(), item)) {
                return true;
            }
        }
        return false;
    }

    /** 配方要的输入是否够（并已扣掉免费部分）。 */
    public boolean matches(InputWrapper wrapper) {
        return getConsumableAmount(wrapper) >= wrapper.getConsumeAmount();
    }

    /** ★ 找不到匹配槽时返回 -100，与 RSC 一致。 */
    public int getConsumableAmount(InputWrapper wrapper) {
        for (SlotWrapper wp : inputs) {
            if (StackUtils.itemsMatch(wrapper.getStack(), wp.getStack())) {
                return wp.getConsumableAmount(wrapper.getNoConsume());
            }
        }
        return -100;
    }

    public SlotWrapper getWrapperForSlot(int slot) {
        for (SlotWrapper wrapper : inputs) {
            if (wrapper.getAmounts().containsKey(slot)) {
                return wrapper;
            }
        }
        return null;
    }

    public ItemStack getItemInSlot(int slot) {
        return inv.getItemInSlot(slot);
    }

    public List<SlotWrapper> getInputs() {
        return inputs;
    }

    public List<SlotWrapper> getOutputs() {
        return outputs;
    }

    public BlockMenu getInv() {
        return inv;
    }

    public int getEmptyOutputSlots() {
        return emptyOutputSlots;
    }
}
