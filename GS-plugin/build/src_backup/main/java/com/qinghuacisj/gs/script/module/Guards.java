package com.qinghuacisj.gs.script.module;

import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * 脚本之间<b>逐字重复</b>的那几个判定/冷却工具。
 *
 * <p><b>为什么值得抽出来</b>：已机械比对确认 ——
 * {@code isMainHandUse} 在 <b>10 个脚本</b>里<b>逐字相同</b>（只有辅助函数名前缀不同），
 * {@code isOurItem} 在 <b>9 个脚本</b>里相同（只有物品 ID 常量不同）。
 * 这些是「消耗按主手算、副手不该触发」这类<b>正确性关键</b>的逻辑，
 * 抄 10 遍就是给自己埋 10 个走样的机会。
 *
 * <p>★ 抽出来<b>不是</b>「顺手优化」：每个方法的语义、调用顺序、
 * 连异常吞法都逐字保留原脚本的写法。唯一的改动是「物品 ID 从常量变成参数」。
 */
public final class Guards {

    private Guards() {
    }

    // ------------------------------------------------------------ 时间

    /**
     * 当前毫秒时间戳。
     *
     * <p>原脚本走 {@code Java.type('java.time.Instant').now().toEpochMilli()}，
     * 降级到 {@code new Date().getTime()}，再降级到 0 —— 那一串是为了绕开
     * 「{@code System} 被 RSC 屏蔽」。Java 里直接用 {@code System.currentTimeMillis()}。
     */
    public static long now() {
        return System.currentTimeMillis();
    }

    // ------------------------------------------------------------ 判定「手里拿的是不是本物品」

    /**
     * 判定物品栈是不是指定的附属物品。
     *
     * <p>★ 原脚本查了两遍（先 RSC 的 {@code getSfItemByItem}，再
     * {@code SlimefunItem.getByItem}）。在原生版里两者是<b>同一个方法</b>
     * （{@code Scripts} 的 {@code getSfItemByItem} 就映射到 {@code SlimefunItem::getByItem}），
     * 但两遍都保留 —— 结构上与原脚本一一对应，行为完全一致。
     */
    public static boolean isOurItem(ItemStack stack, String itemId) {
        try {
            if (stack == null) {
                return false;
            }
            // ① RSC 的判定（最准）
            try {
                SlimefunItem sf = SlimefunItem.getByItem(stack);
                if (sf != null && itemId.equals(String.valueOf(sf.getId()))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            // ② Slimefun 标准 API 兜底
            try {
                SlimefunItem sf2 = SlimefunItem.getByItem(stack);
                if (sf2 != null && itemId.equals(String.valueOf(sf2.getId()))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 判定「这次触发算不算数」—— <b>只认主手</b>。
     *
     * <p>★★ <b>为什么必须拦</b>：Slimefun 对<b>副手</b>也会派发 {@code onUse}，
     * 而消耗是按主手写的 —— 不拦就会出现「副手触发 → 不消耗物品却照样生效」的白嫖。
     *
     * <p>★ 顺序照抄原脚本：先 {@code event.getHand()}，取不到才回退
     * {@code event.getInteractEvent().getHand()}；拿到后只看名字里有没有 {@code OFF}。
     * 最后还要求主手<b>确实拿着本物品</b>。
     */
    public static boolean isMainHandUse(PlayerRightClickEvent event, Player p, String itemId) {
        try {
            String slot = "";
            try {
                slot = String.valueOf(event.getHand().name());
            } catch (Throwable e) {
                slot = "";
            }
            if (slot.isEmpty()) {
                try {
                    slot = String.valueOf(event.getInteractEvent().getHand().name());
                } catch (Throwable e2) {
                    slot = "";
                }
            }
            if (slot.contains("OFF")) {
                return false;
            }

            ItemStack hand;
            try {
                hand = p.getInventory().getItemInMainHand();
            } catch (Throwable e) {
                hand = null;
            }
            return isOurItem(hand, itemId);
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 冷却（按玩家记）

    /**
     * 按玩家记的毫秒冷却表 —— 对应各脚本里那个顶层对象
     * （{@code SG_LAST} / {@code DM_LAST} / {@code BL_LAST} …）。
     *
     * <p>★ 键是 {@code '' + p.getUniqueId()} —— 原脚本用字符串拼接触发 UUID 的
     * {@code toString()}。Java 里等价于 {@link java.util.UUID#toString()}。
     *
     * <p>★ 因为模块是按脚本名缓存的单例，所以「同一玩家共享冷却」的语义与现网一致。
     */
    public static final class Cooldown {
        private final Map<String, Long> last = new HashMap<>();
        private final long cooldownMs;

        public Cooldown(long cooldownMs) {
            this.cooldownMs = cooldownMs;
        }

        /** 剩余冷却毫秒；不在冷却中返回 0。 */
        public long left(Player p) {
            try {
                if (cooldownMs <= 0) {
                    return 0;
                }
                Long v = last.get(p.getUniqueId().toString());
                if (v == null) {
                    return 0;
                }
                long left = v + cooldownMs - now();
                return left > 0 ? left : 0;
            } catch (Throwable e) {
                return 0;
            }
        }

        /** 标记「刚用过」。 */
        public void mark(Player p) {
            try {
                last.put(p.getUniqueId().toString(), now());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }
}
