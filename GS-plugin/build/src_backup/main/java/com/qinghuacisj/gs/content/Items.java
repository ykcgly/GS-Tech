package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.common.ChatColors;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 物品栈读取 —— 等价于 RSC 的 {@code CommonUtils.readItem}。
 *
 * <p><b>本附属实际只用到 3 种 {@code material_type}</b>（已全量统计，含行内配方写法）：
 * <pre>
 *   slimefun  ×502
 *   mc        ×77      ← 也是缺省值
 *   saveditem ×10
 * </pre>
 * 所以 {@code skull / skull_url / skull_base64 / skull_hash / built_in / uniitem}
 * 这 6 个分支本附属<b>一次都没用</b>。它们依赖 GuizhanLibPlugin / uni-item 两个外部库，
 * 原生版<b>刻意不引入</b>；一旦真被用到，会走 RSC 的 {@code default} 分支语义
 * （先按原版试、再按粘液试）并打一条显眼的告警 —— 不静默、也不假装支持。
 *
 * <p>逐字保留的 RSC 细节（这些才是行为一致的关键）：
 * <ul>
 *   <li>{@code material} 支持 {@code a|b|c} 竖线轮替，取第一个能解析出来的</li>
 *   <li>总失败 ⇒ <b>石头</b>，永不返回 null</li>
 *   <li>{@code amount} 超出 {@code [-1,100]} 只告警、<b>不设置数量</b>（保留默认 1）</li>
 *   <li>附魔用 {@code addUnsafeEnchantment}（可超原版上限）</li>
 *   <li>顺序：modelId → name → lore → setItemMeta → amount → 附魔 → glow</li>
 *   <li>名字/lore 空则<b>不覆盖</b>底材自带的（存档头颅的皮肤、名字因此得以保留/覆盖）</li>
 * </ul>
 */
public final class Items {

    /** RSC 的材质兼容表，逐字照抄。 */
    private static final Map<String, String> MATERIAL_MAPPINGS = Map.of(
            "GRASS", "SHORT_GRASS",
            "SHORT_GRASS", "GRASS",
            "SCUTE", "TURTLE_SCUTE",
            "TURTLE_SCUTE", "SCUTE",
            "CHAIN", "IRON_CHAIN",
            "IRON_CHAIN", "CHAIN"
    );

    private Items() {
    }

    // ------------------------------------------------------------ 入口

    /**
     * 读一个 {@code item:} 块（或配方里的一格）。
     *
     * @param where  出错时用于日志定位的字符串（如 "items.yml:GS_BAD_APPLE"）
     * @param sec    该物品的配置节；null ⇒ 返回 null
     */
    public static ItemStack read(String where, ConfigurationSection sec) {
        if (sec == null) {
            return null;
        }

        String type = sec.getString("material_type", "mc");
        if (!type.equalsIgnoreCase("none")
                && (!sec.contains("material") || sec.getString("material") == null)) {
            GS.warn("%s：设置了 material_type 但没有 material", where);
            return null;
        }

        String material = sec.getString("material", "");
        List<String> lore = Chat.t(sec.getStringList("lore"));
        String name = Chat.t(sec.getString("name", ""));
        boolean glow = sec.getBoolean("glow", false);
        boolean hasEnch = sec.contains("enchantments") && sec.isList("enchantments");
        int modelId = sec.getInt("modelId");
        int amount = sec.getInt("amount", 1);

        // ★ 前缀自动识别（RSC 原样）：决定走哪个 skull 分支。
        //   本附属没有 skull 用法，但保留这段是为了「用到时行为一致 / 至少告警一致」。
        String m = material.trim();
        if (m.startsWith("ey") || m.startsWith("ew")) {
            type = "skull";
        } else if (m.startsWith("http") || m.startsWith("https")) {
            type = "skull_url";
        } else if (m.matches("^[0-9A-Fa-f]{64}+$")) {
            type = "skull_hash";
        }

        // ★ type 上面被前缀识别改写过，lambda 里必须用 final 副本（RSC 也是这么写的）
        final String finalType = type;
        ItemStack stack;
        try {
            stack = readPipe(m, part -> base(where, sec, finalType, part));
            if (stack == null) {
                GS.warn("%s：无法识别材质 %s，已转为石头", where, material);
                stack = new ItemStack(Material.STONE);
            }
        } catch (Throwable t) {
            stack = new ItemStack(Material.STONE);
            GS.error(t, "%s：加载物品失败，已转为石头", where);
        }

        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (modelId > 0) {
                meta.setCustomModelData(modelId);
            }
            if (!name.isBlank()) {
                meta.setDisplayName(name);
            }
            if (!lore.isEmpty()) {
                meta.setLore(lore);
            }
            stack.setItemMeta(meta);
        }

        if (amount > 100 || amount < -1) {
            GS.warn("%s：amount 超出范围 %d（允许 -1..100），本项不生效", where, amount);
        } else {
            stack.setAmount(amount);
        }

        if (hasEnch) {
            for (String ench : sec.getStringList("enchantments")) {
                String[] parts = ench.split(" ");
                if (parts.length != 2) {
                    GS.warn("%s：附魔格式非法 '%s'，已跳过", where, ench);
                    continue;
                }
                Enchantment e = Enchantment.getByKey(
                        NamespacedKey.minecraft(parts[0].toLowerCase(Locale.ROOT)));
                if (e == null) {
                    GS.warn("%s：未知附魔 '%s'，已跳过", where, parts[0]);
                    continue;
                }
                try {
                    stack.addUnsafeEnchantment(e, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ex) {
                    GS.warn("%s：附魔等级非法 '%s'，已跳过", where, ench);
                }
            }
        }

        if (glow) {
            // ★ 用海之眷顾 + 隐藏附魔标记 —— RSC 的做法，逐字保留
            stack.addUnsafeEnchantment(Enchantment.LUCK_OF_THE_SEA, 1);
            stack.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        return stack;
    }

    /** 竖线轮替：{@code a|b|c} 取第一个能解析的。 */
    public static <T> T readPipe(String s, java.util.function.Function<String, T> parser) {
        if (s == null) {
            return null;
        }
        for (String part : s.split("\\|")) {
            T r = parser.apply(part.trim());
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ 底材

    private static ItemStack base(String where, ConfigurationSection sec, String type, String material) {
        switch (type.toLowerCase(Locale.ROOT)) {
            case "none":
                return new ItemStack(Material.AIR);

            case "slimefun", "sf": {
                SlimefunItemStack sfis = SfItems.get(material);
                if (sfis != null) {
                    return sfis.clone();
                }
                GS.warn("%s：找不到粘液物品 %s", where, material);
                return null;
            }

            case "saveditem": {
                // ★ material 原样当文件名（大小写敏感、不规范化），与 RSC 一致
                org.bukkit.configuration.file.YamlConfiguration cfg = Content.savedItem(material);
                if (cfg == null) {
                    GS.warn("%s：存档物品文件不存在 saveditems/%s.yml", where, material);
                    return null;
                }
                ItemStack is = cfg.getItemStack("item");
                if (is == null) {
                    GS.warn("%s：无法识别存档物品 %s", where, material);
                    return null;
                }
                return is;
            }

            case "mc", "minecraft", "vanilla": {
                String mm = material.startsWith("minecraft:") ? material.substring(10) : material;
                Material mat = material(mm);
                if (mat == null) {
                    GS.warn("%s：无法识别原版物品 %s", where, material);
                    return null;
                }
                ItemStack is = new ItemStack(mat);
                ItemMeta meta = is.getItemMeta();
                if (meta != null) {
                    tryReadColor(where, sec, meta);
                    is.setItemMeta(meta);
                }
                return is;
            }

            case "skull", "skull_url", "skull_base64", "skull_hash":
            case "built_in":
            case "uniitem":
                // ★ 本附属从未使用。原生版不引入 GuizhanLibPlugin / uni-item。
                //   退化为 RSC 的 default 分支语义（先原版、再粘液），并告警。
                GS.warn("%s：material_type '%s' 未被原生版支持（本附属未使用），按原版物品重试",
                        where, type);
                return fallback(where, sec, material);

            default: {
                GS.warn("%s：无法识别的 material_type '%s'，按原版物品重试", where, type);
                return fallback(where, sec, material);
            }
        }
    }

    /** RSC 的 default 分支：先按原版、再按粘液物品。 */
    private static ItemStack fallback(String where, ConfigurationSection sec, String material) {
        ItemStack mc = base(where, sec, "mc", material);
        if (mc != null) {
            return mc;
        }
        GS.warn("%s：'%s' 也无法作为粘液物品加载", where, material);
        return base(where, sec, "slimefun", material);
    }

    /** 材质名解析 —— {@code matchMaterial(大写)} + 兼容表。 */
    public static Material material(String s) {
        Material m = Material.matchMaterial(s.toUpperCase(Locale.ROOT));
        if (m != null) {
            return m;
        }
        String mapped = MATERIAL_MAPPINGS.get(s.toUpperCase(Locale.ROOT));
        return mapped == null ? null : Material.matchMaterial(mapped);
    }

    private static void tryReadColor(String where, ConfigurationSection sec, ItemMeta meta) {
        String color = sec.getString("color");
        if (color == null) {
            return;
        }
        String[] parts = color.split(",");
        if (parts.length != 3) {
            GS.warn("%s：物品颜色非法 '%s'，已跳过", where, color);
            return;
        }
        try {
            org.bukkit.Color c = org.bukkit.Color.fromRGB(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()));
            switch (meta) {
                case LeatherArmorMeta lam -> lam.setColor(c);
                case PotionMeta pm -> {
                    pm.setColor(c);
                    pm.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                }
                case FireworkEffectMeta fem -> {
                    fem.setEffect(org.bukkit.FireworkEffect.builder().withColor(c).build());
                    fem.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
                }
                default -> GS.warn("%s：该物品不支持 color（%s），已跳过",
                        where, meta.getClass().getSimpleName());
            }
        } catch (NumberFormatException e) {
            GS.warn("%s：物品颜色非法 '%s'，已跳过", where, color);
        }
    }

    // ------------------------------------------------------------ 物品栈工具

    /** 已注册的粘液物品栈（含本附属自己预加载的）。 */
    public static SlimefunItemStack sfStack(String id) {
        return SfItems.get(id);
    }

    /** 取粘液物品的展示栈；找不到返回 null。 */
    public static ItemStack sfDisplay(String id) {
        SlimefunItem sf = SlimefunItem.getById(GS.idOf(id));
        return sf == null ? null : sf.getItem();
    }

    /** 给物品追加 lore（RSC 的 addLore 语义：emptyLine 为真时先插一个空行）。 */
    public static void addLore(ItemStack stack, boolean emptyLine, String... lore) {
        ItemMeta im = stack.getItemMeta();
        if (im == null) {
            return;
        }
        List<String> l = im.getLore();
        if (l != null) {
            if (emptyLine) {
                l.add("");
            }
            l.addAll(Chat.t(List.of(lore)));
        } else {
            l = Chat.t(List.of(lore));
        }
        im.setLore(l);
        stack.setItemMeta(im);
    }

    /** 兼容用：把 § 色码转成 & 形式（日志/调试）。 */
    public static String plain(String s) {
        return ChatColors.color(s);
    }
}
