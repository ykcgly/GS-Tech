package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.machine.MultiBlockRecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 多方块机器加载器 —— 等价于 RSC 的 {@code MultiBlockMachineReader}。
 *
 * <p>本附属 {@code mb_machines.yml} 只有 <b>1 台</b>机器（{@code GS_RULE_CONCEPTOR}），
 * 机器级键：{@code item_group} / {@code display_tier} / {@code item} / {@code recipe} /
 * {@code work} / {@code sound} / {@code recipes}；{@code recipes:} 下 26 条，每条只有
 * {@code input}（1..9 号格）与 {@code output}。
 *
 * <p>★ 与另外两个机器加载器的四处关键差异：
 * <ol>
 *   <li>预加载走 {@code anyPreloadItems}（<b>不</b>检查材质是否为方块）——
 *       多方块机器的「物品」只是指南里的展示图标，不落地成方块。</li>
 *   <li>{@code work} 必须在 <b>1..9</b>（不是 0..8）；随后减 1 得到 3x3 配方里的下标。</li>
 *   <li>配方里<b>必须有一个发射器</b>（DISPENSER），且 {@code recipe[work-1]} 必须非空 ——
 *       否则整台机器跳过。</li>
 *   <li>{@code sound} 是<b>可选</b>的，但一旦写了就必须能被 {@code SoundEffect} 解析出来，
 *       否则整台机器跳过（不是「没声音」）。</li>
 * </ol>
 *
 * <p>★ 两处「RSC 原文如此」的行为：
 * <ul>
 *   <li>{@code recipes:} 用 {@code HashMap} 收集（{@code ItemStack[]} 是身份哈希），
 *       所以 26 条配方的注册顺序不稳定 —— 原样保留，见
 *       {@link MultiBlockRecipeMachine#newRecipeMap()}。</li>
 *   <li>{@code input} 段缺失时 {@code readRecipe} 返回<b>长度 9 的全 null 数组</b>（不是 null），
 *       所以「缺少 input 就跳过」这条分支实际上永不触发；一条全 null 的配方会匹配
 *       「发射器全空」的情形。这是 RSC 的行为，原样保留。</li>
 * </ul>
 *
 * <p>★ 本附属 0 处 {@code script:}，所以不加载脚本、不挂 {@code onWork} 钩子。
 */
public final class MultiBlockLoader extends MachineLoader {

    public MultiBlockLoader() {
        super("mb_machines.yml");
    }

    @Override
    protected List<io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack>
            preloadItems(String key, ConfigurationSection sec) {
        // ★ 多方块机器用 anyPreloadItems：不要求机器物品是方块
        return anyPreloadItems(key, sec);
    }

    @Override
    protected SlimefunItem readEach(String key, ConfigurationSection sec) {
        Base base = base(key, sec);
        if (base == null) {
            return null;
        }

        addMachineLore(base, sec);

        int workSlot = sec.getInt("work", -1);
        if (workSlot < 1 || workSlot > 9) {
            GS.error(null, "%s：缺少或配置错误 '工作槽' (work)", where(key));
            return null;
        }

        if (base.recipe() == null) {
            GS.error(null, "%s：缺少或配置错误 '配方' (recipe)", where(key));
            return null;
        }

        if (!MultiBlockRecipeMachine.containsDispenser(base.recipe())) {
            GS.error(null, "%s：配方里缺少发射器", where(key));
            return null;
        }

        if (base.recipe()[workSlot - 1] == null) {
            GS.error(null, "%s：工作槽对应的方块不存在", where(key));
            return null;
        }

        Map<ItemStack[], ItemStack> recipes = readRecipes(key, sec);

        SoundEffect sound = null;
        if (sec.contains("sound")) {
            String soundString = sec.getString("sound");
            Optional<SoundEffect> soundEffect = getEnum(SoundEffect.class, soundString);
            if (soundEffect.isEmpty()) {
                GS.error(null, "%s：无效的声音类型 (sound): %s", where(key), soundString);
                return null;
            }
            sound = soundEffect.get();
        }

        return new MultiBlockRecipeMachine(base.group(), base.sfis(), base.recipe(),
                recipes, workSlot, sound);
    }

    /**
     * 读 {@code recipes:} 段 —— 等价于 RSC 的 {@code MultiBlockMachineReader.readRecipes}。
     *
     * <p>★ 用 {@link MultiBlockRecipeMachine#newRecipeMap()}（HashMap）而非有序表，理由见那里。
     */
    private Map<ItemStack[], ItemStack> readRecipes(String key, ConfigurationSection sec) {
        Map<ItemStack[], ItemStack> map = MultiBlockRecipeMachine.newRecipeMap();

        ConfigurationSection recipes = sec.getConfigurationSection("recipes");
        if (recipes == null) {
            return map;
        }

        for (String rk : recipes.getKeys(false)) {
            ConfigurationSection recipe = recipes.getConfigurationSection(rk);
            if (recipe == null) {
                continue;
            }

            ItemStack[] inputs = readRecipe(recipe, 9);
            if (inputs == null) {
                GS.warn("%s：配方 %s 缺少 '输入物品' (input) 已跳过", where(key), rk);
                continue;
            }

            ItemStack output = Items.read(where(key) + ":recipes." + rk + ":output",
                    recipe.getConfigurationSection("output"));
            if (output == null) {
                GS.warn("%s：配方 %s 缺少 '输出物品' (output) 已跳过", where(key), rk);
                continue;
            }

            map.put(inputs, output);
        }
        return map;
    }
}
