package com.qinghuacisj.gs.machine;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 某个输入槽的汇总 —— 等价于 RSC 的 {@code bulit_in\wrappers\SlotWrapper}。
 *
 * <p>{@link InvIndex} 遍历菜单输入槽时，把<b>同一种物品</b>的多个槽合并成一个 SlotWrapper：
 * {@code stack} 是数量 1 的样板，{@code amounts} 是「槽号 → 该槽数量」，{@code allAmount} 是总量。
 * 之所以要记住槽号，是因为「绑定不消耗槽位」必须在扣物品时按槽跳过。
 *
 * <p>★ 原版用 fastutil 的 {@code Int2IntOpenHashMap}；原生版换成 {@code LinkedHashMap<Integer,Integer>}。
 * 唯一要注意的语义差异：fastutil 的 {@code get} 对不存在的键返回 {@code 0}，而
 * {@code HashMap.get} 返回 {@code null} —— 所以求和处统一用 {@code getOrDefault(slot, 0)}。
 */
public class SlotWrapper {

    /** 数量恒为 1 的样板物品。 */
    private final ItemStack stack;

    /** 该物品在各槽位里的数量（槽号 → 数量）。 */
    private final Map<Integer, Integer> amounts = new LinkedHashMap<>();

    /** 总量。 */
    private int allAmount = 0;

    private SlotWrapper(ItemStack stack, int slot) {
        this.stack = stack.asOne();
        addAmount(slot, stack.getAmount());
    }

    public static SlotWrapper create(ItemStack stack) {
        return new SlotWrapper(stack, -1);
    }

    public static SlotWrapper create(ItemStack stack, int slot) {
        return new SlotWrapper(stack, slot);
    }

    public ItemStack getStack() {
        return stack;
    }

    public Map<Integer, Integer> getAmounts() {
        return amounts;
    }

    public int getAllAmount() {
        return allAmount;
    }

    /**
     * ★ 注意 {@code amounts.put} 是<b>覆盖</b>而不是累加，{@code allAmount} 才是累加 ——
     * 逐字照抄 RSC：同一个槽号只会被写入一次，所以两者在正常路径下一致。
     */
    public void addAmount(int slot, int amount) {
        amounts.put(slot, amount);
        allAmount += amount;
    }

    /** 可用于消耗的数量 = 总量 - 绑定槽位的量 - 不绑定槽位的免费量。 */
    public int getConsumableAmount(NoConsume noConsume) {
        int linked = 0;
        for (int slot : noConsume.getLinkedNoConsume()) {
            linked += amounts.getOrDefault(slot, 0);
        }
        return getConsumableAmount() - linked - noConsume.getNoConsumeAmountExcludeLinked();
    }

    public int getConsumableAmount() {
        return allAmount;
    }
}
