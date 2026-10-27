package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.machine.CustomMachineRecipe;
import com.qinghuacisj.gs.machine.InputWrapper;
import com.qinghuacisj.gs.machine.MachineMenu;
import com.qinghuacisj.gs.machine.RecipeMachine;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方机器加载器 —— 等价于 RSC 的 {@code RecipeMachineReader} +
 * {@code RecipeMachineTickerCreator} + {@code RecipeReader.addToList}。
 *
 * <p>本附属 {@code recipe_machines.yml} 共 <b>4 台</b>机器，每台平均 6～7 条配方，
 * 配方级键只有 {@code seconds} / {@code input} / {@code output}，
 * 输出条目里带 {@code chance} / {@code material} / {@code material_type} / {@code amount}。
 * 机器级键：{@code item_group} / {@code item} / {@code recipe} / {@code recipe_type} /
 * {@code display_tier} / {@code input} / {@code output} / {@code capacity} /
 * {@code energyPerCraft} / {@code speed} / {@code add_power_buffer_lore}。
 *
 * <p>★ <b>{@code GS_SEA_FILTER} 没有 {@code recipe:} 键</b>（它只有 {@code recipes:} 里的
 * 17 条钓鱼概率表）。所以本加载器<b>绝不能</b>把「缺少 {@code recipe:}」当成错误 ——
 * 那样会直接少一台机器。{@code Loader.readRecipe} 在缺段时返回长度 9 的空数组，
 * 正好符合 RSC 的 {@code CommonUtils.readRecipe} 语义。
 *
 * <p>★ 配方读取的逐字语义（都会影响出货）：
 * <ol>
 *   <li>{@code seconds} 缺省 <b>-1</b>，小于 0 ⇒ 该条配方报错并<b>跳过</b>（不是取 0）</li>
 *   <li>{@code input} 为空 ⇒ 该条配方报错跳过（配方机器不允许零输入）</li>
 *   <li>{@code chance} 钳制到 <b>1..100</b>：写 0 会变成 1，写 200 会变成 100</li>
 *   <li>产物里的 null 会被 {@code removeNulls} 剔掉 —— 注意 <b>AIR 不会被剔</b>，
 *       只有读取失败的 null 才会</li>
 *   <li>输入在读取阶段就<b>预合并</b>（同一物品的多条 yml 条目合成一个 wrapper）</li>
 * </ol>
 *
 * <p>★ 本附属 0 处 {@code script:}，所以不加载脚本、不挂 {@code onTick} 钩子。
 */
public final class RecipeMachineLoader extends MachineLoader {

    public RecipeMachineLoader() {
        super("recipe_machines.yml");
    }

    @Override
    protected List<io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack>
            preloadItems(String key, ConfigurationSection sec) {
        // 配方机器用 blockPreloadItems：机器物品必须是方块
        return blockPreloadItems(key, sec);
    }

    @Override
    protected SlimefunItem readEach(String key, ConfigurationSection sec) {
        Base base = base(key, sec);
        if (base == null) {
            return null;
        }

        MachineMenu menu = menuFor(key, idOf(key));

        List<Integer> input = sec.getIntegerList("input");
        List<Integer> output = sec.getIntegerList("output");

        if (input.isEmpty()) {
            GS.error(null, "%s：缺少或配置错误 '输入槽' (input)", where(key));
            return null;
        }
        if (output.isEmpty()) {
            GS.error(null, "%s：缺少或配置错误 '输出槽' (output)", where(key));
            return null;
        }
        if (isInvalidSlots(key, input, sec, ItemTransportFlow.INSERT)
                || isInvalidSlots(key, output, sec, ItemTransportFlow.WITHDRAW)) {
            return null;
        }

        int capacity = sec.getInt("capacity", 0);
        if (capacity < 0) {
            GS.error(null, "%s：缺少或配置错误 '能源容量' (capacity)", where(key));
            return null;
        }

        int energy = sec.getInt("energyPerCraft", 0);
        if (energy < 0) {
            GS.error(null, "%s：缺少或配置错误 '能量消耗' (energyPerCraft)", where(key));
            return null;
        }

        int speed = sec.getInt("speed", 1);
        if (speed <= 0) {
            GS.error(null, "%s：配置错误 '合成速度' (speed)", where(key));
            return null;
        }

        addPowerBufferLore(base, sec, capacity);
        addPowerPerSecondLore(base, sec, capacity, energy);
        addSpeedLore(base, sec, speed);
        addMachineLore(base, sec);

        List<CustomMachineRecipe> recipes = readRecipes(key, sec);
        if (recipes.isEmpty()) {
            GS.warn("机器 %s 不含任何工作配方!", idOf(key));
        }

        return new RecipeMachine(base.group(), base.sfis(), base.recipeType(), base.recipe(), base.output(),
                toIntArray(input), toIntArray(output), energy, capacity, speed, menu, recipes);
    }

    // ------------------------------------------------------------------ 配方

    /**
     * 读 {@code recipes:} 段 —— 等价于 RSC 的
     * {@code RecipeMachineTickerCreator.read} + {@code readRecipes(file, section, addon, false)}。
     *
     * <p>也支持 {@code recipes_import_from: <机器 id>}：直接复制另一台机器的配方列表。
     */
    private List<CustomMachineRecipe> readRecipes(String key, ConfigurationSection sec) {
        List<CustomMachineRecipe> result = new ArrayList<>();

        String importFrom = sec.getString("recipes_import_from");
        if (importFrom != null) {
            SlimefunItem sf = SlimefunItem.getById(importFrom);
            if (sf == null) {
                GS.warn("%s：无效的配方复制源 (recipes_import_from): %s", where(key), importFrom);
            } else {
                result.addAll(recipesOf(sf));
            }
        }

        ConfigurationSection recipes = sec.getConfigurationSection("recipes");
        if (recipes != null) {
            for (String rk : recipes.getKeys(false)) {
                ConfigurationSection recipe = recipes.getConfigurationSection(rk);
                if (recipe == null) {
                    continue;
                }
                CustomMachineRecipe r = readRecipe(key, rk, recipe);
                if (r != null) {
                    result.add(r);
                }
            }
        }
        return result;
    }

    /** 单条配方 —— 等价于 RSC 的 {@code readRecipes(...)} 循环体。 */
    private CustomMachineRecipe readRecipe(String key, String rk, ConfigurationSection recipe) {
        int seconds = recipe.getInt("seconds", -1);
        if (seconds < 0) {
            GS.error(null, "%s：配方 %s 缺少或配置错误 '配方耗时' (seconds)", where(key), rk);
            return null;
        }

        List<InputWrapper> input = readInputs(key, recipe.getConfigurationSection("input"),
                recipe.getBoolean("noConsume", false));
        if (input.isEmpty()) {
            GS.error(null, "%s：配方 %s 缺少 '输入物品' (input)", where(key), rk);
            return null;
        }

        ConfigurationSection outputs = recipe.getConfigurationSection("output");
        if (outputs == null) {
            GS.error(null, "%s：配方 %s 缺少 '输出物品' (output)", where(key), rk);
            return null;
        }

        List<ItemStack> output = new ArrayList<>();
        List<Integer> chances = new ArrayList<>();
        for (String k : outputs.getKeys(false)) {
            ConfigurationSection outputCfg = outputs.getConfigurationSection(k);
            if (outputCfg == null) {
                break;
            }
            ItemStack item = Items.read(where(key) + ":recipes." + rk + ":output." + k, outputCfg);
            if (item == null) {
                GS.error(null, "%s：配方 %s 输出物品配置错误 (output.%s)", where(key), rk, k);
                continue;
            }
            chances.add(clamp(key, outputCfg.getInt("chance", 100), 1, 100, outputCfg, "'概率 (chance) 非法'"));
            output.add(item);
        }

        boolean chooseOne = recipe.getBoolean("chooseOne", false);
        boolean forDisplay = recipe.getBoolean("forDisplay", false);
        boolean hide = recipe.getBoolean("hide", false);
        boolean noConsumeAll = recipe.getBoolean("noConsume", false);

        ItemStack[] outputsArray = removeNulls(output.toArray(new ItemStack[0]));
        int[] chancesArray = new int[chances.size()];
        for (int i = 0; i < chances.size(); i++) {
            chancesArray[i] = chances.get(i);
        }

        return new CustomMachineRecipe(seconds, input, outputsArray, chancesArray,
                chooseOne, forDisplay, hide, noConsumeAll);
    }

    /** {@code recipes_import_from} 的配方来源 —— 等价于 RSC 的 {@code TickerCreator.readRecipes(SlimefunItem)}。 */
    private static List<CustomMachineRecipe> recipesOf(SlimefunItem item) {
        if (item instanceof com.qinghuacisj.gs.machine.AbstractMachine acm && acm.getTicker() != null) {
            return acm.getTicker().getRecipes();
        }
        if (item instanceof AContainer ac) {
            List<CustomMachineRecipe> list = new ArrayList<>();
            for (MachineRecipe mr : ac.getMachineRecipes()) {
                list.add(CustomMachineRecipe.from(mr));
            }
            return list;
        }
        return new ArrayList<>();
    }

    /** 等价于 RSC 的 {@code CommonUtils.removeNulls}：只剔 null，<b>不</b>剔空气。 */
    private static ItemStack[] removeNulls(ItemStack[] origin) {
        int count = 0;
        for (ItemStack element : origin) {
            if (element != null) {
                count++;
            }
        }
        ItemStack[] result = new ItemStack[count];
        int index = 0;
        for (ItemStack element : origin) {
            if (element != null) {
                result[index++] = element;
            }
        }
        return result;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }
}
