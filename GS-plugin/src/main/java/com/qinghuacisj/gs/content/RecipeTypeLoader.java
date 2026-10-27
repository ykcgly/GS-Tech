package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * 配方类型加载器 —— 等价于 RSC 的 {@code RecipeTypeReader}。
 *
 * <p>本附属 13 个自定义类型（{@code GS_RT_*}），它们<b>全部只是「图鉴里的图标」</b>：
 * 说明某件物品是怎么来的（击败哪个 boss、钓鱼概率、哪台机器做）。
 *
 * <p>★★ 与其它所有 yml 不同，{@code recipe_types.yml} 的 {@code material} / {@code name}
 * / {@code lore} 是<b>平铺</b>在条目下的，<b>不</b>套 {@code item:} 块。
 * 照抄别的类别的读法会读出一个空物品。
 *
 * <p>本附属<b>没有</b>用到 {@code bind-to-multiblock}（已确认 0 次）——
 * 否则该类型会把配方转发给指定多方块，语义完全不同。
 */
public final class RecipeTypeLoader extends Loader<RecipeType> {

    public RecipeTypeLoader() {
        super("recipe_types.yml");
    }

    @Override
    protected List<SlimefunItemStack> preloadItems(String key, ConfigurationSection sec) {
        // 配方类型不参与物品预加载（与原版一致）
        return List.of();
    }

    @Override
    protected RecipeType readEach(String key, ConfigurationSection sec) {
        // ★ 平铺读取：直接把 sec 本身当 item 块
        ItemStack item = Items.read(where(key), sec);
        if (item == null) {
            GS.warn("%s：缺少或配置错误的 'item'（平铺的 material/name/lore），已跳过", where(key));
            return null;
        }

        if (sec.getString("bind-to-multiblock") != null) {
            // 本附属未使用；一旦用到必须实现「配方转发给多方块」，否则会静默丢配方。
            GS.warn("%s：本原生版尚未实现 bind-to-multiblock，该配方类型将不会转发配方", where(key));
        }

        RecipeType rt = new RecipeType(GS.key(key), item);
        RecipeTypes.register(key, rt);
        return rt;
    }
}
