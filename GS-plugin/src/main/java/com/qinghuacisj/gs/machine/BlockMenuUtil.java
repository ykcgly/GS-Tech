package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单推物 / 容量预判 —— 等价于 RSC 的 {@code utils\BlockMenuUtil}（只取机器层用到的部分）。
 *
 * <p>核心 {@code BlockMenu.pushItem} 只会「整栈地塞」，而 RSC 的产物是<b>合并后按最大堆叠切分</b>的
 * 一组物品栈，需要自己遍历输出槽做「先填已有同类堆、再占空槽」的合并 —— 这就是 {@code pushItem}。
 *
 * <p>★ 逐字保留的语义（都是会影响线上表现的地方）：
 * <ul>
 *   <li>{@code pushItem} 会<b>就地修改</b>传入的物品栈数量（{@code item.setAmount(...)}），
 *       所以调用方必须先 clone —— RSC 的 {@code getMatchChanceResult} 正是这么做的。</li>
 *   <li>塞不下的余量以 {@code CustomItemStack(item, leftAmount)} 返回；全塞下返回 {@code null}。</li>
 *   <li>{@code fits} 是「先尽量塞进已有同类堆，剩下的按最大堆叠数占空槽」的粗判 ——
 *       它<b>不考虑</b>空槽里已有别的物品的情形（空槽是预先数出来的）。</li>
 *   <li>已有堆的匹配用的是 {@code checkLore = true}（比输入匹配严格），与 RSC 一致。</li>
 * </ul>
 */
public final class BlockMenuUtil {

    private BlockMenuUtil() {
    }

    /**
     * 把一个物品栈推进若干槽位。
     *
     * @return 塞不下的余量；全部塞下返回 {@code null}
     */
    public static ItemStack pushItem(BlockMenu blockMenu, ItemStack item, int... slots) {
        if (item == null || item.getType().isAir()) {
            return null;
        }

        int leftAmount = item.getAmount();

        for (int slot : slots) {
            if (leftAmount <= 0) {
                break;
            }

            ItemStack existing = blockMenu.getItemInSlot(slot);

            if (existing == null || existing.getType().isAir()) {
                int received = Math.min(leftAmount, item.getMaxStackSize());
                ItemStack clone = item.clone();
                clone.setAmount(received);
                blockMenu.replaceExistingItem(slot, clone);
                leftAmount -= received;
                item.setAmount(Math.max(0, leftAmount));
            } else {
                int existingAmount = existing.getAmount();
                if (existingAmount >= item.getMaxStackSize()) {
                    continue;
                }

                if (!StackUtils.itemsMatch(item, existing, true, false)) {
                    continue;
                }

                int received = Math.max(0, Math.min(item.getMaxStackSize() - existingAmount, leftAmount));
                leftAmount -= received;
                existing.setAmount(existingAmount + received);
                item.setAmount(leftAmount);
            }
        }

        if (leftAmount > 0) {
            return new CustomItemStack(item, leftAmount);
        }
        return null;
    }

    /** 逐栈推进菜单；返回「没塞下的物品 → 数量」表（RSC 会把它丢掉，见 G-9）。 */
    public static Map<ItemStack, Integer> pushItem(BlockMenu blockMenu, ItemStack[] items, int... slots) {
        if (items == null || items.length == 0) {
            return Map.of();
        }
        List<ItemStack> listItems = new ArrayList<>();
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                listItems.add(item);
            }
        }
        return pushItem(blockMenu, listItems, slots);
    }

    /** 逐栈推进菜单；返回「没塞下的物品 → 数量」表。 */
    public static Map<ItemStack, Integer> pushItem(BlockMenu blockMenu, List<ItemStack> items, int... slots) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }

        Map<ItemStack, Integer> itemMap = new HashMap<>();
        for (ItemStack item : items) {
            if (item != null && item.getType() != Material.AIR) {
                ItemStack leftOver = pushItem(blockMenu, item, slots);
                if (leftOver != null) {
                    itemMap.put(leftOver, itemMap.getOrDefault(leftOver, 0) + leftOver.getAmount());
                }
            }
        }
        return itemMap;
    }

    /**
     * 一组产物是否塞得进这些槽位。
     *
     * <p>算法与 RSC 一致：先数空槽，然后对每个 wrapper 先把量塞进已有同类堆，
     * 剩下的按 {@code countStack} 折算成空槽数从 {@code emptySlots} 里扣，扣成负数即失败。
     */
    public static boolean fits(BlockMenu blockMenu, List<ItemWrapper> wrappers, int... slots) {
        int emptySlots = 0;
        for (int slot : slots) {
            ItemStack stack = blockMenu.getItemInSlot(slot);
            if (stack == null || stack.getType().isAir()) {
                emptySlots++;
            }
        }

        for (ItemWrapper wrapper : wrappers) {
            int incoming = wrapper.getAmount();
            for (int slot : slots) {
                ItemStack stack = blockMenu.getItemInSlot(slot);

                if (stack != null && stack.getMaxStackSize() > stack.getAmount()
                        && StackUtils.itemsMatch(wrapper.getStack(), stack, true, false)) {
                    incoming -= stack.getMaxStackSize() - stack.getAmount();
                }

                if (incoming <= 0) {
                    break;
                }
            }

            emptySlots -= wrapper.countStack(incoming);
            if (emptySlots < 0) {
                return false;
            }
        }

        return true;
    }
}
