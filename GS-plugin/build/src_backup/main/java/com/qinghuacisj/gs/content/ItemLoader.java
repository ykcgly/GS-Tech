package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.script.Script;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.handlers.ItemUseHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.ToolUseHandler;
import io.github.thebusybiscuit.slimefun4.core.handlers.WeaponUseHandler;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 普通物品加载器 —— 等价于 RSC 的 {@code ItemReader}。
 *
 * <p>本附属 {@code items.yml} 共 55 件物品。二级键只有 7 个：
 * <pre>
 *   item_group    ×55
 *   display_tier  ×55
 *   recipe_type   ×55
 *   item          ×55
 *   placeable     ×47
 *   recipe        ×46
 *   script        ×14
 * </pre>
 *
 * <p>★★ RSC 的 {@code ItemReader} 还支持 {@code anti_wither} / {@code soulbound} /
 * {@code piglin_trade_chance} / {@code energy_capacity} / {@code radiation} / {@code rainbow}
 * 六种属性，靠 ByteBuddy 运行时生成子类实现。<b>本附属一次都没用到</b>
 * （已全量统计，各 0 次），所以原生版不实现它们，也不引入 ByteBuddy。
 * 一旦将来用到会打告警，不会静默失效。
 *
 * <p>★ 唯一真正需要处理的是 {@code placeable}，而且它的语义是<b>反转</b>的
 * ——详见 {@link GsUnplaceableItem}。
 */
public final class ItemLoader extends Loader<SlimefunItem> {

    /** 未实现的 RSC 物品属性（本附属未使用）。出现即告警，不静默。 */
    private static final String[] UNSUPPORTED_ATTRS = {
            "anti_wither", "soulbound", "piglin_trade_chance",
            "energy_capacity", "radiation", "rainbow"
    };

    public ItemLoader() {
        super("items.yml");
    }

    // ------------------------------------------------------------ 预加载趟

    /**
     * ★★ 预加载是<b>强制</b>的，不是优化：本附属 52 件物品的配方会互相引用
     * （A 的配方里可能有 B，而 B 还没注册）。实测引用量最大的几个：
     * {@code GS_DELUSION} / {@code GS_THOUGHT} 各 5538 次、{@code GS_IMAGINATION} 2141 次。
     *
     * <p>与 RSC 的 {@code anyPreloadItems} 一致：不做「必须是方块」的检查
     * （{@code mustBeBlock = false}），因为本附属有大量非方块物品。
     */
    @Override
    protected List<SlimefunItemStack> preloadItems(String key, ConfigurationSection sec) {
        ConfigurationSection itemSec = sec.getConfigurationSection("item");
        if (itemSec == null) {
            GS.warn("%s：缺少 'item' 配置", where(key));
            return null;
        }
        ItemStack stack = Items.read(where(key) + ":item", itemSec);
        if (stack == null) {
            GS.warn("%s：'item' 配置错误", where(key));
            return null;
        }
        if (stack.getType().isAir()) {
            GS.warn("%s：物品材质为空气，已跳过", where(key));
            return null;
        }
        return List.of(new SlimefunItemStack(idOf(key), stack));
    }

    // ------------------------------------------------------------ 注册趟

    @Override
    protected SlimefunItem readEach(String key, ConfigurationSection sec) {
        String id = idOf(key);

        // ① ID 冲突检查（与原版 passItemIdConflictCheck 一致）
        if (SlimefunItem.getById(id) != null) {
            GS.warn("%s：物品 ID 冲突（%s 已被注册），已跳过", where(key), id);
            return null;
        }

        // ② 物品组
        ItemGroup group = itemGroup(key, sec.getString("item_group"));
        if (group == null) {
            return null;
        }

        // ③ 预加载表里取物品栈 —— 取不到说明预加载那趟就失败了
        SlimefunItemStack pre = SfItems.get(id);
        if (pre == null) {
            GS.warn("%s：预加载表里没有 %s，已跳过", where(key), id);
            return null;
        }

        // ④ 配方类型 + 配方
        ItemStack[] recipe = readRecipe(key, sec);
        RecipeType rt = recipeType(key, sec, recipe);

        // ⑤ 产物：默认就是物品本身（数量取 item.amount）
        ItemStack output = pre.clone();
        output.setAmount(sec.getInt("item.amount", 1));

        // ⑥ 归一化成「数量 1」的物品栈（与原版的 asOne 语义一致）
        ItemStack one = pre.clone();
        one.setAmount(1);
        SlimefunItemStack sfis = new SlimefunItemStack(pre.getItemId(), one);

        // ⑦ 未实现的属性：告警（本附属不会触发，留着防止将来静默失效）
        for (String attr : UNSUPPORTED_ATTRS) {
            if (sec.contains(attr)) {
                GS.warn("%s：原生版未实现 RSC 属性 '%s'，该属性不会生效", where(key), attr);
            }
        }

        // ⑧ ★ placeable 语义是反的：true ⇒ 不可放置
        boolean unplaceable = sec.getBoolean("placeable", false);
        GsItem instance = unplaceable
                ? new GsUnplaceableItem(group, sfis, rt, recipe, output)
                : new GsItem(group, sfis, rt, recipe, output);

        // ⑨ 脚本挂钩
        String scriptName = sec.getString("script");
        if (scriptName != null && !scriptName.isBlank()) {
            Script script = Script.of(scriptName);
            if (script == null) {
                GS.warn("%s：物品脚本不存在: %s", where(key), scriptName);
            } else {
                script.call("init");
                instance.addItemHandler((ItemUseHandler) e -> {
                    // ★ 第二个参数原版传的是 ItemReader 实例；本附属 14 个 onUse
                    //   签名全是 (event)，没人读它 ⇒ 传 null，行为一致。
                    script.call("onUse", e, null);
                    // ★ 与原版一致：onUse 之后无条件取消事件
                    e.cancel();
                });
                instance.addItemHandler((WeaponUseHandler) (e, p, it) ->
                        script.call("onWeaponHit", e, p, it));
                instance.addItemHandler((ToolUseHandler) (e, it, i, drops) ->
                        script.call("onToolUse", e, it, i, drops));
            }
        }

        // ⑩ hidden / 原版工作台可用
        if (sec.getBoolean("hidden", false)) {
            instance.setHidden(true);
        }
        instance.setUseableInWorkbench(sec.getBoolean("vanilla", false));

        // ⑪ 注册（register 内部会回调 load()，由 GsItem 完成挂组与配方登记）
        instance.register(GS.plugin());
        return instance;
    }
}
