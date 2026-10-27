package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Map;

/**
 * {@code gs_ding_me.js} 的 Java 化版本 —— 「这你叮我」。
 *
 * <p>右键：在玩家身上连响 <b>3 声</b>经验球拾取音（叮），0.4 秒内发完
 * （t = 0 / 4 / 8 刻）。消耗品（每次右键消耗 1 个）、冷却 1 秒
 * （冷却中<b>静默忽略</b>：不提示、不消耗、不返还、不出声）、
 * 使用后返还 1 个「空的学识之瓶」（Slimefun 的 {@code FLASK_OF_KNOWLEDGE}）、
 * 并直接 {@code giveExpLevels(1)}（加的是<b>等级</b>，不是经验值）。
 *
 * <p>★★ <b>三个必须逐字保留的坑</b>：
 * <ol>
 *   <li><b>只认主手</b>（{@link #isMainHandUse}）：Slimefun 对<b>副手</b>也会派发
 *       {@code onUse}，而消耗是按主手写的 —— 不拦就会出现「副手触发 → 不消耗物品
 *       却照样升级/给瓶子」的白嫖。判定顺序也要照抄：先看
 *       {@code event.getHand()}，取不到才回退 {@code event.getInteractEvent().getHand()}。</li>
 *   <li><b>先标记冷却再消耗</b>：原脚本是 {@code dmMarkUsed(p)} 在
 *       {@code dmConsume(p)} <b>之前</b>。顺序反了会让「消耗成功但异常中断」
 *       的路径变得可重复触发。</li>
 *   <li><b>三声连响是「响一声再排下一声」</b>（{@link #play}），不是一次排三个任务
 *       —— 原脚本用 {@code runLater} 自递归。所以第 1 声是<b>立即</b>响、
 *       第 2 声在 4 刻后、第 3 声在 8 刻后。</li>
 * </ol>
 *
 * <p>★ 冷却表 {@code DM_LAST} 是原脚本的顶层对象，这里对应成员字段 {@link #last}；
 * 因为是按脚本名缓存的单例，「同一玩家共享冷却」的语义与现网一致。
 */
public final class DingMeModule extends AbstractModule {

    // ---------------- 参数 ----------------
    private static final String DM_ITEM_ID = "GS_DING_ME";
    private static final String DM_SOUND = "entity.experience_orb.pickup";  // 经验球「叮」声
    private static final int DM_TIMES = 3;                                  // 固定响 3 声
    private static final int DM_GAP = 4;                                    // 每 4 刻一声
    private static final float DM_VOL = 1.0f;
    private static final float DM_PITCH = 1.0f;
    private static final String DM_EMPTY_ID = "FLASK_OF_KNOWLEDGE";         // 返还的空瓶（Slimefun）
    private static final long DM_CD_MS = 1000L;                             // 冷却 1 秒

    /** uuid → 上次使用的时间戳(ms)。等价于原脚本的 {@code DM_LAST}。 */
    private final Guards.Cooldown last = new Guards.Cooldown(DM_CD_MS);

    public DingMeModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_ding_me 已加载");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_ding_me";
    }

    // ------------------------------------------------------------ 消耗 / 返还

    /** 消耗主手 1 个。 */
    private static void consume(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand == null) {
                return;
            }
            int amt;
            try {
                amt = hand.getAmount();
            } catch (Throwable e) {
                amt = 0;
            }
            if (amt > 1) {
                hand.setAmount(amt - 1);
            } else {
                inv.setItemInMainHand(null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 给玩家一个「空的学识之瓶」（背包塞不下就掉脚边）。 */
    private static void giveEmptyBottle(Player p) {
        try {
            ItemStack stack = null;
            // ① 优先用取物函数（能拿到 Slimefun 物品实例）
            try {
                SlimefunItem sf = SlimefunItem.getById(DM_EMPTY_ID);
                if (sf != null) {
                    stack = sf.getItem().clone();
                }
            } catch (Throwable e) {
                stack = null;
            }

            if (stack == null) {
                // 拿不到（ID 不对 / 该物品被禁用）→ 记一条日志，方便排查
                try {
                    Bukkit.getLogger().warning(
                            "[构思技艺] 这你叮我：取不到 " + DM_EMPTY_ID + "，未返还空瓶");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return;
            }

            PlayerInventory inv = p.getInventory();
            Map<Integer, ItemStack> rest = inv.addItem(stack);
            if (rest != null && !rest.isEmpty()) {
                for (ItemStack it : rest.values()) {
                    try {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 三声连响（每 4 刻一声）

    /**
     * 响第 {@code idx} 声，然后排下一声。
     *
     * <p>★ 注意是<b>先响后排</b>，所以 t = 0 / 4 / 8 刻 —— 与注释一致。
     * 玩家中途下线时 {@code getWorld()} 可能失效，原脚本靠 try/catch 兜住，
     * 这里同样。
     */
    private void play(Player p, int idx) {
        if (idx >= DM_TIMES) {
            return;
        }
        try {
            p.getWorld().playSound(p.getLocation(), DM_SOUND, DM_VOL, DM_PITCH);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        GS.runLater(() -> play(p, idx + 1), DM_GAP);
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }

            // ★ 只认主手触发：副手拿着本物品右键时，Slimefun 也会走到这里，
            //   但消耗是按主手算的 → 不拦就会出现"不消耗却升级/给瓶子"的白嫖
            if (!Guards.isMainHandUse(event, p, DM_ITEM_ID)) {
                return null;
            }

            // 冷却 1 秒：冷却中**静默忽略**（不提示、不消耗、不返还、不出声）
            if (last.left(p) > 0) {
                return null;
            }
            last.mark(p);

            // ① 消耗 1 个本体
            consume(p);

            // ② 直接 +1 级经验（注意是"等级"，不是经验值）
            try {
                p.giveExpLevels(1);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ③ 返还 1 个「空的学识之瓶」
            giveEmptyBottle(p);

            // ④ 三声「叮」，0.4 秒内发完
            play(p, 0);
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 这你叮我 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
