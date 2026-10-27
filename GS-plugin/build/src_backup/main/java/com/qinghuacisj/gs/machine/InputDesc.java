package com.qinghuacisj.gs.machine;

import org.bukkit.inventory.ItemStack;

/**
 * 一条输入描述 —— 等价于 RSC 的 {@code bulit_in\wrappers\InputDesc}（原版是个 record）。
 *
 * <p>存在的唯一理由：yml 里 {@code recipes.<name>.input.<key>} 的<b>三条信息</b>
 * （物品栈、绑定槽位 {@code slot}、是否免费 {@code noConsume}）必须先原样收集起来，
 * 再按物品合并成 {@link InputWrapper}。RSC 把「读 yml」和「合并」分成两步，
 * 合并时 {@code noConsume} 才被翻译进 {@link NoConsume} 账本。
 *
 * @param itemStack 该条目的物品栈（数量即 yml 的 {@code amount}）
 * @param slot      绑定的不消耗槽位，缺省 {@code -1} 表示「不绑定槽位」
 * @param noConsume 该条目是否免费（配方级 {@code noConsume} 会强制把每条都置真）
 */
public record InputDesc(ItemStack itemStack, int slot, boolean noConsume) {
}
