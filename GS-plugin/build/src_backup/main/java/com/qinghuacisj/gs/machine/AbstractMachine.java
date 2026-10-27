package com.qinghuacisj.gs.machine;

import com.qinghuacisj.gs.GS;
import com.xzavier0722.mc.plugin.slimefun4.storage.util.StorageCacheUtils;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem;
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.core.machines.MachineProcessor;
import io.github.thebusybiscuit.slimefun4.implementation.handlers.SimpleBlockBreakHandler;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;

/**
 * 机器基类 —— 等价于 RSC 的 {@code customs\AdvancedCustomMachine}。
 *
 * <p><b>为什么继承核心 {@code AContainer}：</b>它是核心唯一一个「一格的、多槽位的、耗电的处理器」
 * 基类，已经带好了 {@code InventoryBlock} / {@code EnergyNetComponent} /
 * {@code MachineProcessHolder} 三件套和 tick 注册。但它给的<b>不能用</b>，必须整段换掉：
 *
 * <table border="1">
 *   <caption>AContainer 缺什么 / 本类补什么</caption>
 *   <tr><th>核心行为</th><th>为什么不行</th><th>本类怎么做</th></tr>
 *   <tr><td>{@code tick(Block)} 写死进度槽 22</td><td>本附属 {@code GS_INSPIRATION_CONVERTER} 在 19、
 *       {@code GS_MIND_SMELTING_FURNACE} 在 13、{@code GS_SEA_FILTER} 在 37</td>
 *       <td>整段换成 {@link MachineTicker#tick}</td></tr>
 *   <tr><td>无 {@code hasViewer()} 守卫</td><td>无人时也在写菜单</td><td>ticker 里逐处守卫</td></tr>
 *   <tr><td>产物按 {@code getResults()} 原样推</td><td>没有概率、没有 chooseOne</td>
 *       <td>{@link CustomMachineRecipe#pushOutputs}</td></tr>
 *   <tr><td>输入逐槽一对一比对</td><td>RSC 的输入是合并过的（可跨槽凑）</td>
 *       <td>{@link InvIndex} + {@link InputWrapper}</td></tr>
 *   <tr><td>构造器里造默认 preset</td><td>会与 {@code menus.yml} 的菜单打架</td>
 *       <td>{@code createPreset} 覆写成<b>空</b>，preset 交给 ticker 造</td></tr>
 *   <tr><td>{@code register} 要求 capacity/耗电/speed 全 &gt; 0</td>
 *       <td>本附属耗电全是 0，会被静默禁用</td>
 *       <td>★ {@code registering} 标志：注册期间谎报 capacity=1、耗电=1</td></tr>
 * </table>
 *
 * <p>★ {@code registering} 这一段是<b>必须复刻</b>的：{@code AContainer.register()} 的字节码是
 * <pre>
 *   if (getCapacity() &gt; 0 &amp;&amp; getEnergyConsumption() &gt; 0 &amp;&amp; getSpeed() &gt; 0) super.register(addon);
 *   else warn("...has not been configured correctly. The Item was disabled.");
 * </pre>
 * 本附属 {@code energyPerCraft: 0} / {@code per: 0}，不做假就会「每台机器都注册失败、机器放不出来」。
 *
 * <p>★ {@code capacity: 1000} 也是必须的：{@code EnergyNetComponent.isChargeable()} 的字节码是
 * {@code getCapacityLong() &gt; 0}，capacity 为 0 意味着机器<b>根本不接电网</b>、
 * {@code takeCharge} 恒成功 —— 那样机器在没电的地方也能白嫖运行。
 */
public abstract class AbstractMachine extends AContainer implements RecipeDisplayItem {

    private MachineTicker ticker;

    private final int[] input;
    private final int[] output;
    private final int energyPerCraft;
    private final int capacity;
    private final int speed;

    /** ★ 注册期间的谎报开关，见类注释。 */
    private boolean registering;

    /** 自己那张操作表（核心 {@code AContainer.processor} 不使用）。 */
    private final MachineProcessorEx processor = new MachineProcessorEx(this);

    protected AbstractMachine(ItemGroup group, SlimefunItemStack stack, RecipeType recipeType,
                              ItemStack[] recipe, ItemStack recipeOutput,
                              int[] input, int[] output, int energyPerCraft, int capacity, int speed) {
        super(group, stack, recipeType, recipe, recipeOutput);
        this.input = input;
        this.output = output;
        this.energyPerCraft = energyPerCraft;
        this.capacity = capacity;
        this.speed = speed;
    }

    // ------------------------------------------------------------------ 注册

    /**
     * 接上 ticker 并注册 —— 逐字等价于 RSC 的 {@code setTicker}。
     *
     * <p>{@code ticker.init()} 必须在 {@code register()} <b>之前</b>：注册会触发
     * {@code postRegister → registerDefaultRecipes}，那时 ticker 必须已经就位。
     */
    public void setTicker(MachineTicker ticker) {
        this.ticker = ticker;
        ticker.init();
        // ★ 注册期间谎报 capacity / 耗电，否则零耗电机器会被 AContainer 静默禁用
        this.registering = true;
        register(GS.plugin());
        this.registering = false;
    }

    @Override
    public void load() {
        // ★ hidden 守卫逐字照抄 RSC：隐藏的机器不进物品组
        if (!hidden) {
            attachToGroup();
        }
        // 机器自己的合成表（指南里点开机器时显示的那张）
        getRecipeType().register(getRecipe(), getRecipeOutput());
    }

    private void attachToGroup() {
        ItemGroup group = getItemGroup();
        if (group == null) {
            return;
        }
        if (group instanceof com.qinghuacisj.gs.content.GsItemGroup gs) {
            gs.addContent(this);
            return;
        }
        if (group instanceof io.github.thebusybiscuit.slimefun4.api.items.groups.FlexItemGroup) {
            GS.warn("无法把机器 %s 加入 %s：它是 FlexItemGroup", getId(), group.getKey());
            return;
        }
        group.add(this);
    }

    /** ★ 必须覆写成空实现：{@code AContainer} 的构造器会调它，不挡住就会造出默认菜单。 */
    @Override
    public void createPreset(SlimefunItem item, String title, Consumer<BlockMenuPreset> setup) {
    }

    /** 逐条把配方登记进 {@code AContainer}（内部会按 speed 除 tick）。 */
    @Override
    protected void registerDefaultRecipes() {
        if (getTicker() == null) {
            return;
        }
        for (CustomMachineRecipe recipe : getTicker().getRecipes()) {
            registerRecipe(recipe);
        }
    }

    // ------------------------------------------------------------------ tick

    @Override
    protected void tick(Block b) {
        if (tick() && ticker != null) {
            ticker.tick(b.getLocation());
        }
    }

    /** RSC 留的可覆写开关，本附属没有任何机器覆写它（恒真）。 */
    public boolean tick() {
        return true;
    }

    @Override
    protected BlockBreakHandler onBlockBreak() {
        return new SimpleBlockBreakHandler() {
            @Override
            public void onBlockBreak(Block b) {
                BlockMenu inv = StorageCacheUtils.getMenu(b.getLocation());
                if (inv != null) {
                    inv.dropItems(b.getLocation(), getInputSlots());
                    inv.dropItems(b.getLocation(), getOutputSlots());
                }
                getProcessor().endOperation(b);
            }
        };
    }

    /** 新方块实例化时挂的钩子（发生器用它把状态格设成不可拿）。 */
    public void onNewInstance(BlockMenu menu, Block b) {
    }

    // ------------------------------------------------------------------ 配方查找

    /**
     * 核心的找配方入口 —— 保留覆写是为了「万一有别的代码路径调它」时行为一致。
     * 真正的 tick 循环在 {@link RecipeMachineTicker} 里。
     */
    @Override
    public MachineRecipe findNextRecipe(BlockMenu inv) {
        InvIndex index = InvIndex.create(inv);
        CustomMachineRecipe recipe = getCache(inv);
        if (recipe == null || !recipe.matches(index)) {
            recipe = ticker == null ? null : ticker.findNextRecipe(index, recipe);
        }
        return recipe;
    }

    private CustomMachineRecipe getCache(BlockMenu inv) {
        return ticker == null ? null : ticker.getCache(inv.getLocation());
    }

    // ------------------------------------------------------------------ 属性

    @Override
    public ItemStack getProgressBar() {
        // AContainer 的构造器会调这里，那时 ticker 还是 null
        if (ticker == null) {
            return MachineTicker.DEFAULT_PROGRESS_BAR;
        }
        return ticker.getProgressBar();
    }

    @Override
    public String getMachineIdentifier() {
        return ticker == null ? getId() : ticker.getMachineIdentifier();
    }

    @Override
    public List<ItemStack> getDisplayRecipes() {
        return ticker == null ? List.of() : ticker.getDisplayRecipes();
    }

    @Override
    public int[] getInputSlots() {
        return input;
    }

    @Override
    public int[] getOutputSlots() {
        return output;
    }

    @Override
    public int getCapacity() {
        return registering ? 1 : capacity;
    }

    @Override
    public int getEnergyConsumption() {
        return registering ? 1 : energyPerCraft;
    }

    @Override
    public int getSpeed() {
        return speed;
    }

    @Override
    public MachineProcessor<CraftingOperation> getMachineProcessor() {
        return processor;
    }

    public MachineProcessorEx getProcessor() {
        return processor;
    }

    public MachineTicker getTicker() {
        return ticker;
    }

    public int getEnergyPerCraft() {
        return energyPerCraft;
    }

    /** 仅供调试：注册期谎报中。 */
    public boolean isRegistering() {
        return registering;
    }
}
