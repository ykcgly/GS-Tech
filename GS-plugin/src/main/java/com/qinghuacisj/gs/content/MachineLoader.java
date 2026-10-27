package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.machine.InputDesc;
import com.qinghuacisj.gs.machine.InputWrapper;
import com.qinghuacisj.gs.machine.MachineMenu;
import com.qinghuacisj.gs.machine.Menus;
import com.qinghuacisj.gs.machine.StackUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineTier;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineType;
import io.github.thebusybiscuit.slimefun4.utils.LoreBuilder;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 机器类加载器骨架 —— 等价于 RSC 的 {@code YamlReader} 里「机器三兄弟共用」的那部分
 * （{@code getBase} / {@code blockPreloadItems} / {@code anyPreloadItems} / {@code isInvalidSlots}
 * / 四个 lore 辅助 / {@code readInputs} / {@code clamp}）。
 *
 * <p>为什么单独抽一层：{@code recipe_machines.yml}、{@code mat_generators.yml}、{@code mb_machines.yml}
 * 三个 reader 有 <b>70% 的代码是同一份</b>（RSC 靠继承 {@code YamlReader} 复用）。
 * 如果各写各的，一旦某个校验条件要改就得改三处 —— 而这正是最容易「改漏一处导致行为漂移」的地方。
 *
 * <p>★ 逐字保留的几处「看起来多余但不能删」的检查：
 * <ol>
 *   <li>预加载时 {@code mustBeBlock}：配方机器与材料发生器用 {@code blockPreloadItems}
 *       （材质必须是方块），多方块机器用 {@code anyPreloadItems}（不检查）——
 *       因为多方块机器的「物品」只是指南里的展示图标，不落地成方块。</li>
 *   <li>{@code isLegacy()} 检查：老材质名（如 {@code GRASS}）会被拒。本附属的机器
 *       全用现代材质名，所以这条不会触发，但留着以防将来写错材质名时静默注册出问题物品。</li>
 *   <li>四个 lore 辅助的开关默认<b>全是 false</b>。本附属 {@code recipe_machines.yml}
 *       有 3 台机器开了 {@code add_power_buffer_lore}，其余开关 0 次出现 ——
 *       所以「不开开关就不加 lore」这条必须精确实现，多加了 lore 就是玩家可见的差异。</li>
 * </ol>
 */
public abstract class MachineLoader extends Loader<SlimefunItem> {

    protected MachineLoader(String fileName) {
        super(fileName);
    }

    // ------------------------------------------------------------------ 通用基础信息

    /**
     * 读「所有机器共用」的那一段 —— 等价于 RSC 的 {@code YamlReader.getBase}。
     *
     * <p>顺序不能变：ID 冲突检查 → 物品组 → 预加载表 → 配方类型/配方 → 产物。
     * 产物缺省是「物品本身 + {@code item.amount} 个」，{@code recipeOutput} 写了就用它。
     */
    protected Base base(String key, ConfigurationSection sec) {
        String id = idOf(key);

        if (SlimefunItem.getById(id) != null) {
            GS.warn("%s：机器 ID 冲突（%s 已被注册），已跳过", where(key), id);
            return null;
        }

        ItemGroup group = itemGroup(key, sec.getString("item_group"));
        if (group == null) {
            return null;
        }

        SlimefunItemStack pre = SfItems.get(id);
        if (pre == null) {
            GS.warn("%s：预加载表里没有 %s，已跳过", where(key), id);
            return null;
        }

        ItemStack[] recipe = readRecipe(key, sec);
        RecipeType rt = recipeType(key, sec, recipe);

        ItemStack output = null;
        if (sec.contains("recipeOutput")) {
            output = Items.read(where(key) + ":recipeOutput", sec.getConfigurationSection("recipeOutput"));
            if (output == null) {
                GS.warn("%s：设置了 recipeOutput 但物品无效，已跳过", where(key));
                return null;
            }
        }
        if (output == null) {
            output = pre.clone();
            output.setAmount(sec.getInt("item.amount", 1));
        }

        SlimefunItemStack sfis = new SlimefunItemStack(pre.getItemId(), pre.asOne());
        return new Base(group, sfis, rt, recipe, output);
    }

    /** RSC 的 {@code YamlReader.BaseResult}（去掉 {@code @NullMarked} 与 jspecify 注解）。 */
    protected record Base(ItemGroup group, SlimefunItemStack sfis, RecipeType recipeType,
                          ItemStack[] recipe, ItemStack output) {
    }

    // ------------------------------------------------------------------ 预加载

    /**
     * 机器物品材质<b>必须是方块</b> —— 等价于 RSC 的 {@code blockPreloadItems}。
     * 配方机器 / 材料发生器用这个。
     */
    protected List<SlimefunItemStack> blockPreloadItems(String key, ConfigurationSection sec) {
        return internalPreload(key, sec, true, true, true);
    }

    /** 不检查材质是否为方块 —— 等价于 RSC 的 {@code anyPreloadItems}。多方块机器用这个。 */
    protected List<SlimefunItemStack> anyPreloadItems(String key, ConfigurationSection sec) {
        return internalPreload(key, sec, true, true, false);
    }

    private List<SlimefunItemStack> internalPreload(String key, ConfigurationSection sec,
                                                    boolean noAir, boolean noLegacy, boolean mustBeBlock) {
        ConfigurationSection item = sec.getConfigurationSection("item");
        if (item == null) {
            GS.error(null, "%s：缺少 'item' 配置", where(key));
            return null;
        }

        ItemStack stack = Items.read(where(key) + ":item", item);
        if (stack == null) {
            GS.error(null, "%s：'item' 配置错误", where(key));
            return null;
        }

        if ((noAir && stack.getType().isAir())
                || (noLegacy && stack.getType().isLegacy())
                || (mustBeBlock && !stack.getType().isBlock())) {
            GS.error(null, "%s：机器物品材质必须为方块", where(key));
            return null;
        }

        return List.of(new SlimefunItemStack(idOf(key), stack));
    }

    // ------------------------------------------------------------------ 校验

    /** 槽位范围检查（0..53）—— 等价于 RSC 的 {@code isInvalidSlots}。 */
    protected boolean isInvalidSlots(String key, List<Integer> slots, ConfigurationSection sec,
                                     ItemTransportFlow flow) {
        for (int slot : slots) {
            if (slot < 0 || slot > 53) {
                GS.warn("%s：槽位超出范围 0..53：%s %d", where(key),
                        flow == ItemTransportFlow.INSERT ? "输入槽" : "输出槽", slot);
                return true;
            }
        }
        return false;
    }

    /** 数值钳制 —— 等价于 RSC 的 {@code CommonUtils.clamp(int,int,int,File,ConfigurationSection,String)}。 */
    protected int clamp(String key, int v, int a, int b, ConfigurationSection sec, String msg) {
        if (v < a) {
            GS.warn("%s：%s，已转为 %d", where(key), msg, a);
            v = a;
        }
        if (v > b) {
            GS.warn("%s：%s，已转为 %d", where(key), msg, b);
            v = b;
        }
        return v;
    }

    /**
     * 枚举名解析 —— 等价于 RSC 的 {@code CommonUtils.getEnum}。
     *
     * <p>★ 逐字保留 RSC 的<b>模糊匹配</b>语义：先扫一遍，若遇到<b>大小写完全相等</b>的名字立即返回；
     * 否则记住<b>最后一个</b>「忽略大小写相等」的名字并返回它。所以
     * {@code ancient_altar_finish_sound} 与 {@code ANCIENT_ALTAR_FINISH_SOUND} 都能解析成功。
     *
     * <p>★ RSC 的原版实现外面还套了一层 {@code readPipe}（支持 {@code a|b} 竖线轮替），
     * 但那个 lambda 内部误用了<b>外层参数</b> {@code name} 而不是自己的 {@code n}，
     * 导致带竖线的值永远匹配不上。本方法复刻这一<b>实际行为</b>（竖线值一律解析失败），
     * 只是把「永远失败」写成直白的单次比较，不再假装支持竖线。
     */
    protected static <T extends Enum<T>> Optional<T> getEnum(Class<T> type, String name) {
        if (name == null) {
            return Optional.empty();
        }
        T[] values = type.getEnumConstants();
        if (values == null) {
            return Optional.empty();
        }
        T fuzzy = null;
        for (T value : values) {
            if (value.name().equalsIgnoreCase(name)) {
                fuzzy = value;
            }
            if (value.name().equals(name)) {
                return Optional.of(value);
            }
        }
        return Optional.ofNullable(fuzzy);
    }

    // ------------------------------------------------------------------ 输入读取

    /**
     * 读配方输入 —— 等价于 RSC 的 {@code CommonUtils.readInputs}。
     *
     * <p>★ 两个关键点：
     * <ol>
     *   <li><b>预合并</b>：同一物品的多条 yml 条目合成一个 wrapper（总量相加），
     *       所以机器可以从<b>任意</b>输入槽凑齐，而不是「槽 1 一个、槽 2 一个」。</li>
     *   <li>{@code allNoConsume}（配方级 {@code noConsume: true}）会把<b>每一条</b>输入都标成免费，
     *       而条目级 {@code slot:} 决定这份免费是「绑定槽位」还是「从消耗量里扣」。</li>
     * </ol>
     */
    protected List<InputWrapper> readInputs(String key, ConfigurationSection sec, boolean allNoConsume) {
        if (sec == null) {
            return Collections.emptyList();
        }

        List<InputDesc> descs = new ArrayList<>();
        for (String k : sec.getKeys(false)) {
            ConfigurationSection item = sec.getConfigurationSection(k);
            if (item == null) {
                continue;
            }
            ItemStack stack = Items.read(where(key) + ":input." + k, item);
            if (stack == null) {
                continue;
            }
            descs.add(new InputDesc(stack, item.getInt("slot", -1),
                    allNoConsume || item.getBoolean("noConsume", false)));
        }

        List<InputWrapper> wrappers = new ArrayList<>();
        for (InputDesc desc : descs) {
            boolean matched = false;
            for (InputWrapper wrapper : wrappers) {
                if (StackUtils.itemsMatch(wrapper.getStack(), desc.itemStack())) {
                    wrapper.merge(desc, true);
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                wrappers.add(InputWrapper.create(desc));
            }
        }
        return wrappers;
    }

    // ------------------------------------------------------------------ lore

    /** 储能 lore —— 需要 {@code capacity > 0} <b>且</b>开了 {@code add_power_buffer_lore}。 */
    protected void addPowerBufferLore(Base base, ConfigurationSection sec, int capacity) {
        if (capacity > 0 && sec.getBoolean("add_power_buffer_lore", false)) {
            Items.addLore(base.sfis(), true, LoreBuilder.powerBuffer(capacity));
        }
    }

    /** 每秒耗电 lore —— 需要 {@code capacity > 0 && power > 0} <b>且</b>开了 {@code add_power_per_second_lore}。 */
    protected void addPowerPerSecondLore(Base base, ConfigurationSection sec, int capacity, int power) {
        if (capacity > 0 && power > 0 && sec.getBoolean("add_power_per_second_lore", false)) {
            Items.addLore(base.sfis(), true, LoreBuilder.powerPerSecond(power));
        }
    }

    /** 速度 lore —— 只看 {@code add_speed_lore}，<b>不看</b> speed 的值。 */
    protected void addSpeedLore(Base base, ConfigurationSection sec, float speed) {
        if (sec.getBoolean("add_speed_lore", false)) {
            Items.addLore(base.sfis(), true, LoreBuilder.speed(speed));
        }
    }

    /** 机器 tier/type lore —— 缺省 {@code BASIC} / {@code MACHINE}。 */
    protected void addMachineLore(Base base, ConfigurationSection sec) {
        if (!sec.getBoolean("add_machine_lore", false)) {
            return;
        }
        MachineTier tier = getEnum(MachineTier.class, sec.getString("machine_tier")).orElse(MachineTier.BASIC);
        MachineType type = getEnum(MachineType.class, sec.getString("machine_type")).orElse(MachineType.MACHINE);
        Items.addLore(base.sfis(), true, LoreBuilder.machine(tier, type));
    }

    // ------------------------------------------------------------------ 菜单

    /**
     * 按<b>机器 id</b> 找菜单 —— 等价于 RSC 的
     * {@code CommonUtils.getIf(addon.getMenus(), m -> m.getId().equalsIgnoreCase(id))}。
     *
     * <p>找不到时返回 null（机器会退化成默认菜单），并打一条告警。
     */
    protected MachineMenu menuFor(String key, String id) {
        MachineMenu menu = Menus.find(id);
        if (menu == null) {
            GS.warn("%s：未找到菜单 %s，将使用默认菜单", where(key), id);
        }
        return menu;
    }
}
