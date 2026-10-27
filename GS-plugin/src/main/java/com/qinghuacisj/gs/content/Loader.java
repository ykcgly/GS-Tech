package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.List;

/**
 * 加载器骨架 —— 等价于 RSC 的 {@code YamlReader<T>}。
 *
 * <p>RSC 的读取分两趟，这个顺序<b>不能改</b>：
 * <ol>
 *   <li><b>预加载趟</b>（T1）：只读每个条目的 {@code item:} 块，把
 *       {@code SlimefunItemStack} 塞进 {@link SfItems} 预加载表。</li>
 *   <li><b>注册趟</b>（T2）：这时才真正构造并注册物品。</li>
 * </ol>
 * 之所以必须两趟：本附属有 <b>52 件自引用物品</b> —— A 的配方里可能引用 B，
 * 而 B 还没注册。没有预加载表就会大面积丢物品。
 * （实测引用量：GS_DELUSION / GS_THOUGHT 各 5538 次、GS_IMAGINATION 2141 次…）
 *
 * <p>本附属的 yml <b>没有用到</b> RSC 的 {@code register} 条件注册、
 * {@code lateInit} 延迟加载、{@code id_alias} 别名（已全量统计，出现 0 次），
 * 所以这里不实现那三套机制 —— 但保留 {@code id} 大写化这一条硬约定。
 */
public abstract class Loader<T> {

    /** 配置文件名（用于日志与定位）。 */
    protected final String fileName;
    /** 已读入的 yml。 */
    protected final YamlConfiguration cfg;

    protected Loader(String fileName) {
        this.fileName = fileName;
        this.cfg = Content.yml(fileName);
    }

    /** 本类别的条目数（顶层 key 数）。 */
    public int size() {
        return cfg.getKeys(false).size();
    }

    public boolean isEmpty() {
        return cfg.getKeys(false).isEmpty();
    }

    /** 出错时用于日志定位的前缀，如 "items.yml:GS_BAD_APPLE"。 */
    protected String where(String key) {
        return fileName + ":" + key;
    }

    /**
     * 预加载趟：返回该条目要登记的物品栈（可返回空表表示本类别不预加载）。
     *
     * @return null 表示该条目有问题（与原版一样：报错并跳过）
     */
    protected abstract List<io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack>
            preloadItems(String key, ConfigurationSection sec);

    /** 注册趟：真正构造并注册。返回 null 表示跳过。 */
    protected abstract T readEach(String key, ConfigurationSection sec);

    /** 条目 id（本附属无 id_alias ⇒ 就是 key 大写化）。 */
    protected String idOf(String key) {
        return GS.idOf(key);
    }

    // ------------------------------------------------------------------ 两趟

    /**
     * 预加载趟。把每个条目的物品栈登记进 {@link SfItems}。
     *
     * @return 成功登记的条数
     */
    public int preload() {
        int n = 0;
        for (String key : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                List<io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack> list =
                        preloadItems(key, sec);
                if (list == null) {
                    continue;
                }
                for (var sfis : list) {
                    SfItems.preload(sfis.getItemId(), sfis);
                    n++;
                }
            } catch (Throwable t) {
                GS.error(t, "%s：预加载失败，已跳过", where(key));
            }
        }
        return n;
    }

    /**
     * 注册趟。
     *
     * <p>逐条 try/catch —— 与原版一致：<b>单条坏配置不能让整个附属加载失败</b>。
     *
     * @return 成功注册的条数
     */
    public int readAll() {
        int n = 0;
        for (String key : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            try {
                T obj = readEach(key, sec);
                if (obj != null) {
                    n++;
                    // display_tier 只对物品与物品组登记（与原版一致）
                    if (obj instanceof io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
                            || obj instanceof io.github.thebusybiscuit.slimefun4.api.items.ItemGroup) {
                        registerDisplayTier(sec, obj);
                    }
                }
            } catch (Throwable t) {
                GS.error(t, "%s：读取失败，已跳过", where(key));
            }
        }
        return n;
    }

    /** 登记显示排序权重（缺省 1000，与原版一致）。 */
    private void registerDisplayTier(ConfigurationSection sec, Object obj) {
        if (sec.contains("display_tier")) {
            GsItemGroup.registerDisplayTier(obj, sec.getInt("display_tier", 1000));
        }
    }

    // ------------------------------------------------------------------ 共用读取

    /**
     * 读配方：{@code recipe:} 下的 {@code 1..9} 键 → 长度 9 的数组。
     *
     * <p>★ 注意：{@code recipe} 段缺失时返回<b>长度 9 的空数组</b>（不是 null）——
     * 与 RSC 一致。这对多方块/机器的「逐格精确匹配」语义有影响。
     */
    protected org.bukkit.inventory.ItemStack[] readRecipe(String key, ConfigurationSection sec) {
        return readRecipe(sec, 9);
    }

    protected org.bukkit.inventory.ItemStack[] readRecipe(ConfigurationSection sec, int size) {
        ConfigurationSection r = sec.getConfigurationSection("recipe");
        org.bukkit.inventory.ItemStack[] out = new org.bukkit.inventory.ItemStack[size];
        if (r == null) {
            return out;
        }
        for (int i = 0; i < size; i++) {
            ConfigurationSection cell = r.getConfigurationSection(String.valueOf(i + 1));
            out[i] = Items.read(fileName + ":recipe#" + (i + 1), cell);
        }
        return out;
    }

    /**
     * 解析 {@code recipe_type}。
     *
     * <p>与 RSC 一致的顺序：先按 {@code RecipeType} 的<b>静态字段名</b>反射取，
     * 取不到再查本附属自定义的配方类型表。取不到 ⇒ {@code NULL} 类型 + 空配方。
     *
     * <p>★ 注意 {@code piglin_trade_chance} 的存在会<b>强制</b>把配方类型改成
     * {@code BARTER_DROP}（本附属未使用，但保留以免将来加了静默失效）。
     */
    protected io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType recipeType(
            String key, ConfigurationSection sec, org.bukkit.inventory.ItemStack[] recipeHolder) {
        if (sec.getBoolean("piglin_trade_chance")) {
            return io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType.BARTER_DROP;
        }
        String name = sec.getString("recipe_type", "NULL").toUpperCase(java.util.Locale.ROOT);
        io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType rt = RecipeTypes.get(name);
        if (rt == null) {
            GS.warn("%s：未知的配方类型 recipe_type: %s", where(key), name);
            return io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType.NULL;
        }
        return rt;
    }

    /** 按 id 找物品组：先本附属自建的，再粘液全局的（与原版一致）。 */
    protected io.github.thebusybiscuit.slimefun4.api.items.ItemGroup itemGroup(String key, String id) {
        if (id == null) {
            GS.warn("%s：缺少 item_group", where(key));
            return null;
        }
        for (String part : id.split("\\|")) {
            io.github.thebusybiscuit.slimefun4.api.items.ItemGroup g = Groups.find(part.trim());
            if (g != null) {
                return g;
            }
        }
        GS.warn("%s：无法找到物品组 item_group: %s", where(key), id);
        return null;
    }
}
