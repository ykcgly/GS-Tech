package com.qinghuacisj.gs.machine;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 不消耗记账 —— 等价于 RSC 的 {@code bulit_in\wrappers\NoConsume}。
 *
 * <p>RSC 的 {@code noConsume} 有<b>两套互相独立</b>的机制，这个类就是它们的账本：
 * <ol>
 *   <li><b>不绑定槽位</b>（yml 里写 {@code noConsume: true} 且没写 {@code slot:}）：
 *       数量累加进 {@code noConsumeAmountExcludeLinked}，最后从
 *       {@code InputWrapper.getConsumeAmount()} 里减掉 —— 也就是「配方要 3 个，其中 1 个免费」。</li>
 *   <li><b>绑定槽位</b>（yml 里同时写 {@code slot: N}）：槽号进 {@code linkedNoConsume}。
 *       消耗时这些槽位被<b>整个跳过</b>（{@code if (linkedNoConsume.contains(slot)) continue;}），
 *       而且它们<b>不</b>参与 {@code getConsumeAmount()} 的扣减 —— 因为「绑定槽里的东西本来就不该被动」。</li>
 * </ol>
 *
 * <p>★ 本附属的四份机器 yml 里 {@code noConsume} / {@code slot:} 出现 <b>0 次</b>，
 * 这里实现它是为了「将来真用到时行为一致」，不是为了当前配置。
 *
 * <p>★ 原版用 fastutil 的 {@code IntOpenHashSet}；原生版换成 {@code LinkedHashSet<Integer>} ——
 * 只用到「去重 + 包含判断 + 求和」，语义完全一致，且不引入新依赖。
 */
public final class NoConsume {

    /** 不绑定槽位的免费数量（从消耗量里直接扣掉）。 */
    private int noConsumeAmountExcludeLinked = 0;

    /** 绑定槽位的免费数量（仅统计用，不参与扣减）。 */
    private int noConsumeLinkedAmount = 0;

    /** 被绑定为「不消耗」的槽位号。 */
    private final Set<Integer> linkedNoConsume = new LinkedHashSet<>();

    public int getNoConsumeAmountExcludeLinked() {
        return noConsumeAmountExcludeLinked;
    }

    public int getNoConsumeLinkedAmount() {
        return noConsumeLinkedAmount;
    }

    public Set<Integer> getLinkedNoConsume() {
        return linkedNoConsume;
    }

    public void addNoConsumeAmountExcludeLinked(int amount) {
        this.noConsumeAmountExcludeLinked += amount;
    }

    public void addLinkedNoConsume(int slot) {
        linkedNoConsume.add(slot);
    }

    public void addLinkedNoConsume(int slot, int amount) {
        linkedNoConsume.add(slot);
        noConsumeLinkedAmount += amount;
    }
}
