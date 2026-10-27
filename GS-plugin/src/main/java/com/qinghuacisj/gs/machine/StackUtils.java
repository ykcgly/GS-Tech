package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.papermc.paper.datacomponent.DataComponentType;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * 物品比对 —— 等价于 RSC 的 {@code utils\StackUtils}（只取机器层用得到的部分）。
 *
 * <p>为什么不能直接用 {@code ItemStack.isSimilar}：RSC 的机器比对是「按数据组件排除 lore / 自定义模型」
 * 的宽松比对，而 {@code isSimilar} 会把 lore、CustomModelData 全部纳入比较。两者在
 * 「带概率 lore 的产物」「同名不同描述的粘液物品」上结果不同 —— 直接换掉会改变出货判定。
 *
 * <p>逐字保留的 RSC 细节：
 * <ul>
 *   <li>空值语义是<b>不对称</b>的：{@code cache == null || item == null} 时返回 {@code item == null}
 *       —— 即「缓存为空、物品非空」判为不匹配，而「物品为空」一律判为匹配</li>
 *   <li>{@code checkAmount} 只判 {@code item.getAmount() > cache.getAmount()}（{@code >=} 通过）</li>
 *   <li>排除集合按 {@code checkCustomModelId} 选择：true 只排除 LORE，false 同时排除 LORE 与
 *       CUSTOM_MODEL_DATA</li>
 *   <li>{@code shouldCompareLore} 的「强制比 lore」名单：{@code checkLore} 为真、
 *       最大堆叠为 1、或材质属于玩家头颅/刷怪笼/糖/矿车/箱子矿车</li>
 *   <li>lore 比对是<b>纯文本</b>比对（{@code PlainTextComponentSerializer}），忽略样式</li>
 * </ul>
 *
 * <p>★ 与 RSC 的唯一差异（有意为之）：{@code loreMatchesLoose} 在 RSC 里对「一边有 lore、一边没有」
 * 会直接 NPE。原生版把「两边都为空」判为相等、「只有一边为空」判为不等 —— 语义等价且不会把异常
 * 抛进粘液 tick 线程。
 *
 * <p>★ 另一处差异：RSC 通过 {@code VarHandle} 反射读 {@code ItemStack.craftDelegate} 来识别
 * 第三方包装类（{@code ItemStackWrapper}）。本附属只产出 {@code SlimefunItemStack}，
 * 因此只保留 {@code SlimefunItemStack} 这一条分支，不引入反射。
 */
public final class StackUtils {

    /** 只排除 LORE（{@code checkCustomModelId = true} 时用）。 */
    private static final Set<DataComponentType> EXCLUDE_LORE = Set.of(DataComponentTypes.LORE);

    /** 同时排除 LORE 与 CUSTOM_MODEL_DATA（默认路径）。 */
    private static final Set<DataComponentType> EXCLUDE_LORE_AND_CMD =
            Set.of(DataComponentTypes.LORE, DataComponentTypes.CUSTOM_MODEL_DATA);

    private StackUtils() {
    }

    // ------------------------------------------------------------------ 入口

    public static boolean itemsMatch(ItemStack cache, ItemStack itemStack) {
        return itemsMatch(cache, itemStack, false, false, false);
    }

    public static boolean itemsMatch(ItemStack cache, ItemStack itemStack, boolean checkLore) {
        return itemsMatch(cache, itemStack, checkLore, false, false);
    }

    public static boolean itemsMatch(ItemStack cache, ItemStack itemStack, boolean checkLore, boolean checkAmount) {
        return itemsMatch(cache, itemStack, checkLore, checkAmount, false);
    }

    public static boolean itemsMatch(ItemStack cache, ItemStack itemStack,
                                     boolean checkLore, boolean checkAmount, boolean checkCustomModelId) {
        // ★ 空值语义与 RSC 一致（不对称）
        if (cache == null || itemStack == null) {
            return itemStack == null;
        }

        if (itemStack.getType() != cache.getType()) {
            return false;
        }

        if (checkAmount && itemStack.getAmount() > cache.getAmount()) {
            return false;
        }

        ItemStack a = asCraftItemStack(cache);
        ItemStack b = asCraftItemStack(itemStack);

        if (!a.matchesWithoutData(b, checkCustomModelId ? EXCLUDE_LORE : EXCLUDE_LORE_AND_CMD, true)) {
            return false;
        }

        if (shouldCompareLore(itemStack, checkLore)) {
            return loreMatchesLoose(a.getData(DataComponentTypes.LORE), b.getData(DataComponentTypes.LORE));
        }

        return true;
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 「强制比 lore」名单 —— 逐字照抄 RSC。这些材质要么本身不堆叠（RPG 武器），
     * 要么在原版里用 lore 承载唯一标识（灵魂罐、刷怪笼、MomoTech 的符号等）。
     */
    private static boolean shouldCompareLore(ItemStack itemStack, boolean checkLore) {
        return checkLore
                || itemStack.getMaxStackSize() == 1
                || itemStack.getType() == Material.PLAYER_HEAD
                || itemStack.getType() == Material.SPAWNER
                || itemStack.getType() == Material.SUGAR
                || itemStack.getType() == Material.MINECART
                || itemStack.getType() == Material.CHEST_MINECART;
    }

    /** 纯文本 lore 比对（忽略样式），与 RSC 的 {@code loreMatchesLoose} 等价（多了空值保护）。 */
    private static boolean loreMatchesLoose(ItemLore a, ItemLore b) {
        List<Component> a1 = a == null ? List.of() : a.styledLines();
        List<Component> a2 = b == null ? List.of() : b.styledLines();
        if (a1.size() != a2.size()) {
            return false;
        }
        PlainTextComponentSerializer serializer = PlainTextComponentSerializer.plainText();
        for (int i = 0; i < a1.size(); i++) {
            if (!serializer.serialize(a1.get(i)).equals(serializer.serialize(a2.get(i)))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 等价于 RSC 的 {@code asCraftItemStack}：{@code SlimefunItemStack} 是 {@code ItemStack} 的子类，
     * 它的 {@code craftDelegate} 不携带自定义 meta，直接拿去比对会得出错误结果 ——
     * 所以先「洗」成一个普通 {@code ItemStack}。
     */
    public static ItemStack asCraftItemStack(ItemStack stack) {
        if (stack instanceof SlimefunItemStack) {
            return getCleanItem(stack);
        }
        return stack;
    }

    /** 逐字照抄 RSC 的 {@code getCleanItem}：同材质、同数量、同 meta 的普通物品栈。 */
    public static ItemStack getCleanItem(ItemStack item) {
        if (item == null) {
            return new ItemStack(Material.AIR);
        }
        ItemStack cleanItem = new ItemStack(item.getType());
        cleanItem.setAmount(item.getAmount());
        if (item.hasItemMeta()) {
            cleanItem.setItemMeta(item.getItemMeta());
        }
        return cleanItem;
    }
}
