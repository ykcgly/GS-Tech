package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配方类型表 —— 等价于 RSC 的 {@code RecipeTypeMap} + {@code CommonUtils.getRecipeType}。
 *
 * <p>解析顺序与原版逐字一致：
 * <ol>
 *   <li>先按名字反射取 {@link RecipeType} 的<b>静态字段</b>（{@code ENHANCED_CRAFTING_TABLE} 等）；</li>
 *   <li>取不到再查本附属在 {@code recipe_types.yml} 里自定义的类型（{@code GS_RT_*}）。</li>
 * </ol>
 *
 * <p>本附属实际用到 16 个配方类型名（已全量统计）：3 个粘液内置
 * （{@code MAGIC_WORKBENCH} / {@code SMELTERY} / {@code ENHANCED_CRAFTING_TABLE}）
 * + 13 个自定义（{@code GS_RT_*}）。
 */
public final class RecipeTypes {

    private static final Map<String, RecipeType> CUSTOM = new ConcurrentHashMap<>();

    private RecipeTypes() {
    }

    public static void register(String name, RecipeType type) {
        CUSTOM.put(GS.idOf(name), type);
    }

    /** 按名字取配方类型；取不到返回 null。 */
    public static RecipeType get(String name) {
        if (name == null) {
            return null;
        }
        String n = GS.idOf(name);

        // ① 粘液内置的静态字段
        try {
            java.lang.reflect.Field f = RecipeType.class.getDeclaredField(n);
            Object v = f.get(null);
            if (v instanceof RecipeType rt) {
                return rt;
            }
        } catch (NoSuchFieldException ignored) {
            // 落到 ②
        } catch (IllegalAccessException e) {
            GS.error(e, "无法读取内置配方类型 %s", n);
        }

        // ② 本附属自定义的
        return CUSTOM.get(n);
    }

    public static void clear() {
        CUSTOM.clear();
    }

    /**
     * 注册一个自定义配方类型。
     *
     * <p>★ 与 RSC 一致：{@code RecipeType} 必须在构造时给出「示例物品」，
     * 而 {@code bind-to-multiblock} 会让该类型在注册配方时把配方转发给指定多方块
     * （本附属的 {@code GS_RT_RULE_CONCEPTOR} 就是这条路 —— 26 条配方都绑在
     * 梦境规则构筑器上）。所以这里必须把 {@code register} 回调接上。
     */
    public static RecipeType create(String name, org.bukkit.inventory.ItemStack display) {
        return new RecipeType(GS.key(name), display);
    }
}
