package com.qinghuacisj.gs.machine;

import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 一条输入（已合并） —— 等价于 RSC 的 {@code bulit_in\wrappers\InputWrapper}。
 *
 * <p>RSC 在「读配方」阶段就把同一物品的多条 yml 输入<b>预合并</b>成一个 wrapper：
 * {@code input: {1: X, 2: X}} 变成「一个 wrapper，总量 2」。后果很关键 ——
 * 机器可以从<b>任意</b>输入槽凑齐这 2 个，而不是「槽 1 一个、槽 2 一个」。
 * 合并判据是 {@link StackUtils#itemsMatch(ItemStack, ItemStack)}（宽松比对）。
 *
 * <p>三个数量概念必须分清：
 * <ul>
 *   <li>{@code getAmount()} —— yml 要求的总量（含免费部分）</li>
 *   <li>{@code getConsumeAmount()} = {@code amount - noConsumeAmountExcludeLinked} —— <b>实际要扣的</b>数量。
 *       配方匹配用的就是这个值。</li>
 *   <li>{@code asArrayStream()} —— 喂给 {@code MachineRecipe} 父类的「物品栈数组」。
 *       注意它用的是 {@code getAmount()}（原版注释：{@code // - noConsume.getNoConsumeAmountExcludeLinked()}
 *       那行被注释掉了），也就是说 <b>父类的输入数组里免费部分仍然在</b> —— 与 RSC 一致，别"顺手修"。</li>
 * </ul>
 */
public class InputWrapper extends ItemWrapper {

    /** 不消耗账本（配方级 + 条目级）。 */
    private final NoConsume noConsume = new NoConsume();

    /** 该输入出现过的槽位集合（RSC 里用于 GUI 展示，机器判定不用它）。 */
    private final Set<Integer> slots = new LinkedHashSet<>();

    public InputWrapper(ItemStack stack, int amount) {
        super(stack, amount);
    }

    public InputWrapper(ItemStack stack) {
        super(stack);
    }

    /** 由一条 {@link InputDesc} 建 wrapper（含 noConsume 记账）。 */
    public static InputWrapper create(InputDesc desc) {
        InputWrapper wrapper = new InputWrapper(desc.itemStack());
        wrapper.merge(desc, false);
        return wrapper;
    }

    public NoConsume getNoConsume() {
        return noConsume;
    }

    public Set<Integer> getSlots() {
        return slots;
    }

    public void addSlot(int slot) {
        slots.add(slot);
    }

    /**
     * 把另一条同物品的输入并进来。
     *
     * @param addAmount true 时累加数量（合并多条 yml 条目）；false 时只处理 noConsume 记账
     */
    public void merge(InputDesc desc, boolean addAmount) {
        if (addAmount) {
            addAmount(desc.itemStack().getAmount());
        }
        if (desc.noConsume()) {
            if (desc.slot() != -1) {
                noConsume.addLinkedNoConsume(desc.slot(), desc.itemStack().getAmount());
            } else {
                noConsume.addNoConsumeAmountExcludeLinked(desc.itemStack().getAmount());
            }
        }
    }

    /** 喂给 {@code MachineRecipe} 父类的输入数组（按最大堆叠切分）。 */
    public Stream<ItemStack> asArrayStream() {
        int amt = getAmount();
        return toStacks(amt).stream();
    }

    /** 实际需要扣除的数量。 */
    public int getConsumeAmount() {
        return getAmount() - noConsume.getNoConsumeAmountExcludeLinked();
    }
}
