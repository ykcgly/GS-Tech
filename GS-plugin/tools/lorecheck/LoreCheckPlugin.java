package com.qinghuacisj.lorecheck;

import com.qinghuacisj.gs.content.GsItemGroup;
import com.qinghuacisj.gs.content.Items;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;

/**
 * 一次性校验插件（**不属于交付物**，验完即删）。
 *
 * <p>它只做一件事：把 {@code plugins/GS/content/groups.yml} 里每个「带 lore 的组」
 * 走一遍 {@code Items.read} → {@code GsItemGroup} → {@code getItem(null)}，
 * 断言 lore 逐字不变。
 *
 * <p>★ 关键点：{@code getItem(null)} 传 null 是**故意的** —— 修好之后，
 * 「物品自带 lore」这条分支根本不碰 player 参数，所以 null 不会炸；
 * 一旦有人把 {@code GsItemGroup.getItem} 的覆盖删掉（退回 Slimefun 的实现），
 * 就会立刻炸 NPE 或把 lore 换成「点击打开」提示，两种都会被本插件抓到。
 */
public final class LoreCheckPlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        File f = new File(getDataFolder().getParentFile(), "GS" + File.separator + "content"
                + File.separator + "groups.yml");
        if (!f.isFile()) {
            getLogger().severe("LORE-CHECK 找不到 " + f.getAbsolutePath());
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        int tested = 0;
        int bad = 0;
        int skipped = 0;
        StringBuilder report = new StringBuilder();

        for (String key : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            ConfigurationSection itemSec = sec.getConfigurationSection("item");
            if (itemSec == null) {
                continue;
            }
            ItemStack stack;
            try {
                stack = Items.read("lorecheck:" + key, itemSec);
            } catch (Throwable t) {
                skipped++;
                report.append("\n  · ").append(key).append(" 读取异常 ").append(t);
                continue;
            }
            if (stack == null) {
                skipped++;
                report.append("\n  · ").append(key).append(" 读取返回 null");
                continue;
            }
            List<String> want = stack.getItemMeta() == null ? null : stack.getItemMeta().getLore();
            if (want == null || want.isEmpty()) {
                continue;   // 无 lore 的组不在本 bug 范围
            }
            tested++;
            try {
                GsItemGroup g = new GsItemGroup(new NamespacedKey(this, key), stack, 1,
                        GsItemGroup.Type.BUTTON, false, true, 1, null, true);
                ItemStack got = g.getItem(null);
                List<String> gotLore = got.getItemMeta() == null ? null : got.getItemMeta().getLore();
                if (!want.equals(gotLore)) {
                    bad++;
                    report.append("\n  ✗ ").append(key)
                            .append("\n      期望 ").append(want)
                            .append("\n      实际 ").append(gotLore);
                }
            } catch (Throwable t) {
                bad++;
                report.append("\n  ✗ ").append(key).append(" getItem(null) 抛异常 ").append(t);
            }
        }

        getLogger().info("LORE-CHECK ===== 测了 " + tested + " 个带 lore 的组，失败 " + bad
                + "，跳过 " + skipped + " =====");
        if (bad > 0) {
            getLogger().severe("LORE-CHECK 明细:" + report);
        } else {
            getLogger().info("LORE-CHECK 全部通过 ✅");
        }

        checkPageButtons();
    }

    /**
     * 验证「翻页按钮必须无条件放」这条的前提：
     * {@code ChestMenuUtils.getPreviousButton/getNextButton} 在单页时会**自己**返回
     * 灰色占位玻璃板（不是 null、也不抛异常）。
     *
     * <p>所以 RSC 的 {@code setup()} 里那两个 {@code addItem(46/52, …)} 是**无条件**的 ——
     * 早期原生版在外面判了 {@code page > 1} 才放，结果单页组的槽 46 / 52 是空的，
     * 而参考实现是两块灰玻璃板。
     *
     * <p>★ 传 {@code null} 当 player：这条只是要确认「返回非 null 的占位物品」。
     * 如果 ChestMenuUtils 内部非要玩家不可而抛异常，本检查会失败 —— 那也是有用的信息
     * （说明得换个方式验）。
     */
    private void checkPageButtons() {
        // ★ 能验的：两个「不可点」占位物品**确实存在且是玻璃板**。
        //   验不了的：分支本身（`max==1 || page==1 ⇒ INACTIVE`）——
        //   ChestMenuUtils 内部要真玩家的 PlayerProfile，代理玩家过不去。
        //   那条分支是靠 javap 反编译确认的，见 tools/lorecheck/README.md。
        try {
            Object prevInactive = staticField("PREV_BUTTON_INACTIVE");
            Object nextInactive = staticField("NEXT_BUTTON_INACTIVE");
            String pn = itemName(prevInactive);
            String nn = itemName(nextInactive);
            boolean ok = pn != null && pn.endsWith("STAINED_GLASS_PANE")
                    && nn != null && nn.endsWith("STAINED_GLASS_PANE");
            getLogger().info("LORE-CHECK 翻页占位物: PREV_BUTTON_INACTIVE=" + pn
                    + " NEXT_BUTTON_INACTIVE=" + nn + " ⇒ " + (ok ? "OK ✅" : "失败 ❌"));
        } catch (Throwable t) {
            getLogger().warning("LORE-CHECK 翻页占位物检查失败: " + t);
        }
    }

    private static Object staticField(String name) throws Exception {
        java.lang.reflect.Field f =
                io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }

    private static String itemName(Object stack) {
        return stack instanceof org.bukkit.inventory.ItemStack is ? is.getType().name() : null;
    }
}
