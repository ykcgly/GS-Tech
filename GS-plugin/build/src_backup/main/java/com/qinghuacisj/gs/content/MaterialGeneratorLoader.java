package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.machine.CustomMachineRecipe;
import com.qinghuacisj.gs.machine.MachineMenu;
import com.qinghuacisj.gs.machine.MaterialGeneratorMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 材料发生器加载器 —— 等价于 RSC 的 {@code MaterialGeneratorReader} +
 * {@code MaterialGeneratorMachineTickerCreator} + {@code CustomMaterialGenerator}。
 *
 * <p>本附属 {@code mat_generators.yml} 共 <b>3 台</b>机器，机器级键只有：
 * {@code item_group} / {@code item} / {@code recipe} / {@code recipe_type} / {@code display_tier} /
 * {@code output} / {@code outputItem} / {@code capacity} / {@code per} / {@code tickRate} /
 * {@code status} / {@code add_power_buffer_lore}。
 * <b>没有 {@code outputs:} 段</b>（所以 {@code chooseOne} 用不上，但代码保留 —— 它决定
 * 「多个产物是各自独立掷点还是等概率抽一个」，是出货语义的一部分）。
 *
 * <p>★ 三处与配方机器不同、且都会影响线上表现的地方：
 * <ol>
 *   <li><b>耗时字段叫 {@code tickRate}，且它就是有效 tick 数</b> ——
 *       不像 {@code seconds} 要乘 2。{@code speed} 被硬编码成 <b>1</b>
 *       （RSC 的 {@code CustomMaterialGenerator} 构造器里写死），所以
 *       {@code AContainer.registerRecipe} 的除法是除以 1，不会改变 tick。</li>
 *   <li><b>输入恒为空</b>：发生器不接受任何输入，输入槽数组传 {@code new int[0]}。</li>
 *   <li>{@code status} 是<b>状态格槽号</b>，缺省 <b>-1</b>（不刷状态格）；
 *       小于 -1 视为配置错误、整台机器跳过。它来自本文件，<b>不是</b> {@code menus.yml} 的
 *       {@code progressbar:}（G-15）。</li>
 * </ol>
 *
 * <p>★ 生效的默认值：{@code capacity} 缺省 <b>0</b>、{@code per} 缺省 <b>0</b>、
 * {@code tickRate} 缺省 <b>0</b>（&lt; 1 直接跳过整台机器）、{@code status} 缺省 <b>-1</b>。
 *
 * <p>★ 本附属 0 处 {@code script:}，所以不加载脚本。
 */
public final class MaterialGeneratorLoader extends MachineLoader {

    public MaterialGeneratorLoader() {
        super("mat_generators.yml");
    }

    @Override
    protected List<io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack>
            preloadItems(String key, ConfigurationSection sec) {
        // 材料发生器也用 blockPreloadItems：机器物品必须是方块
        return blockPreloadItems(key, sec);
    }

    @Override
    protected SlimefunItem readEach(String key, ConfigurationSection sec) {
        Base base = base(key, sec);
        if (base == null) {
            return null;
        }

        MachineMenu menu = menuFor(key, idOf(key));

        List<Integer> output = sec.getIntegerList("output");
        if (output.isEmpty()) {
            GS.error(null, "%s：缺少或配置错误 '输出槽' (output)", where(key));
            return null;
        }
        if (isInvalidSlots(key, output, sec, ItemTransportFlow.WITHDRAW)) {
            return null;
        }

        int capacity = sec.getInt("capacity", 0);
        if (capacity < 0) {
            GS.error(null, "%s：配置错误 '能源容量' (capacity)", where(key));
            return null;
        }

        int energy = sec.getInt("per", 0);
        if (energy < 0) {
            GS.error(null, "%s：配置错误 '能量消耗' (per)", where(key));
            return null;
        }

        int status = sec.getInt("status", -1);

        // ★ 顺序与 RSC 一致：先读配方（含 tickRate 校验），再校验 status
        CustomMachineRecipe recipe = readGeneratorRecipe(key, sec);
        if (recipe == null) {
            return null;
        }
        if (status < -1) {
            GS.error(null, "%s：缺少或配置错误 '状态槽' (status)", where(key));
            return null;
        }

        addPowerBufferLore(base, sec, capacity);
        addPowerPerSecondLore(base, sec, capacity, energy);
        // ★ 材料发生器<b>不</b>调用 addSpeedLore（RSC 的 MaterialGeneratorReader 里没有这一行）
        addMachineLore(base, sec);

        List<CustomMachineRecipe> recipes = List.of(recipe);
        return new MaterialGeneratorMachine(base.group(), base.sfis(), base.recipeType(), base.recipe(),
                base.output(), toIntArray(output), energy, capacity, 1, status, menu, recipes);
    }

    // ------------------------------------------------------------------ 配方

    /**
     * 读发生器唯一的配方 —— 等价于 RSC 的 {@code MaterialGeneratorMachineTickerCreator.read}。
     *
     * <p>产物来源有两处，<b>先 {@code outputs:} 段、后 {@code outputItem:}</b>，
     * 两处的产物会<b>合并到同一个数组</b>（顺序即声明顺序）。
     *
     * <p>★ 与 RSC 的 {@code outputItems == null} 分支一致：{@code outputs:} 段缺失是<b>正常</b>的
     * （本附属三台机器都没写），不会报错，只产出 {@code outputItem} 那一项。
     */
    private CustomMachineRecipe readGeneratorRecipe(String key, ConfigurationSection sec) {
        List<ItemStack> outputs = new ArrayList<>();
        List<Integer> chances = new ArrayList<>();
        boolean chooseOne = sec.getBoolean("chooseOne", false);

        ConfigurationSection outputItems = sec.getConfigurationSection("outputs");
        if (outputItems != null) {
            for (String k : outputItems.getKeys(false)) {
                ConfigurationSection outputCfg = outputItems.getConfigurationSection(k);
                if (outputCfg == null) {
                    break;
                }
                ItemStack item = Items.read(where(key) + ":outputs." + k, outputCfg);
                if (item == null) {
                    GS.error(null, "%s：物品配置错误 (outputs.%s)", where(key), k);
                    continue;
                }
                chances.add(clamp(key, outputCfg.getInt("chance", 100), 1, 100, outputCfg, "'概率 (chance) 非法'"));
                outputs.add(item);
            }
        }

        ConfigurationSection outputItem = sec.getConfigurationSection("outputItem");
        if (outputItem != null) {
            ItemStack item = Items.read(where(key) + ":outputItem", outputItem);
            if (item == null) {
                GS.error(null, "%s：物品配置错误 (outputItem)", where(key));
            } else {
                chances.add(clamp(key, outputItem.getInt("chance", 100), 1, 100, outputItem, "'概率 (chance) 非法'"));
                outputs.add(item);
            }
        }

        int tickRate = sec.getInt("tickRate");
        if (tickRate < 1) {
            GS.error(null, "%s：配置错误 '配方耗时' (tickRate)", where(key));
            return null;
        }

        int[] chancesArray = new int[chances.size()];
        for (int i = 0; i < chances.size(); i++) {
            chancesArray[i] = chances.get(i);
        }

        // ★ 用 raw-ticks 构造器：tickRate 就是有效 tick 数，不乘 2
        return new CustomMachineRecipe(List.of(), outputs.toArray(new ItemStack[0]), tickRate,
                chancesArray, chooseOne, false, false, false);
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
