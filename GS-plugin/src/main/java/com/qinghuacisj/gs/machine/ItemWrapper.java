package com.qinghuacisj.gs.machine;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 物品数量包装 —— 等价于 RSC 的 {@code bulit_in\wrappers\ItemWrapper}。
 *
 * <p>RSC 的所有「多个物品合起来算总量」的地方都走这里：一个 wrapper 持有
 * <b>数量恒为 1 的物品栈</b>（构造时 {@code stack.asOne()}）外加一个独立的 {@code amount}。
 * 这样「3 组 64 个 X」就是一个 wrapper（{@code amount = 192}），而不是三份物品栈。
 *
 * <p>为什么必须拆成 {@code toStacks()}：粘液菜单的每个槽位最多放 {@code getMaxStackSize()} 个，
 * 输出时必须按最大堆叠切成多份。切法与 RSC 逐字一致 ——
 * <b>先铺满 {@code countStack - 1} 个满堆，余数单独一份</b>（注意 {@code toStacks(0)} 返回空表，
 * 因为循环条件是 {@code i < countStack(0) - 1} 且 {@code left = 0} 不满足 {@code > 0}）。
 */
public class ItemWrapper implements Cloneable {

    /** 数量恒为 1 的物品栈（构造时已 asOne）。 */
    private final ItemStack stack;
    /** 总量（可以超过最大堆叠）。 */
    private int amount;

    public ItemWrapper(ItemStack stack) {
        this(stack, stack.getAmount());
    }

    public ItemWrapper(ItemStack stack, int amount) {
        this.stack = stack.asOne();
        this.amount = amount;
    }

    public static ItemWrapper create(ItemStack stack) {
        return new ItemWrapper(stack);
    }

    public ItemStack getStack() {
        return stack;
    }

    public int getAmount() {
        return amount;
    }

    public void setAmount(int amount) {
        this.amount = amount;
    }

    public void addAmount(int amount) {
        this.amount += amount;
    }

    public Material getType() {
        return stack.getType();
    }

    public int getMaxStackSize() {
        return stack.getMaxStackSize();
    }

    /** 把 {@code amount} 个物品装进菜单需要几个槽位（向上取整）。 */
    public int countStack(int amount) {
        return (amount + stack.getMaxStackSize() - 1) / stack.getMaxStackSize();
    }

    public int countStack() {
        return countStack(amount);
    }

    public List<ItemStack> toStacks() {
        return toStacks(amount);
    }

    public List<ItemStack> toStacks(int amt) {
        List<ItemStack> list = new ArrayList<>();
        for (int i = 0; i < countStack(amt) - 1; i++) {
            list.add(stack.asQuantity(stack.getMaxStackSize()));
        }
        int left = amt - (stack.getMaxStackSize() * Math.max(0, countStack(amt) - 1));
        if (left > 0) {
            list.add(stack.asQuantity(left));
        }
        return list;
    }

    public ItemStack asOneStack() {
        return stack.asQuantity(Math.min(stack.getMaxStackSize(), getAmount()));
    }

    @Override
    public ItemWrapper clone() {
        return new ItemWrapper(stack.clone(), amount);
    }
}
