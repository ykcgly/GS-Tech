package com.qinghuacisj.gs.machine;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.MachineProcessHolder;
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.implementation.operations.CraftingOperation;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import io.github.thebusybiscuit.slimefun4.libraries.dough.protection.Interaction;
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 机器 ticker 基类 —— 等价于 RSC 的 {@code bulit_in\tickers\MachineTicker} +
 * {@code CustomMenuHolder} + {@code RecipesHolder} + {@code DataCache} + {@code ProcessorHolder}
 * 这一整条接口链（原生版合成一个抽象类，去掉接口默认方法互相引用的绕圈）。
 *
 * <p>它负责三件事：
 * <ol>
 *   <li><b>造菜单</b>：{@link #init()} 里用匿名 {@code BlockMenuPreset} 现造 preset。
 *       机器类的 {@code createPreset} 是空实现，就是为了让 {@code AContainer} 构造器造不出默认菜单。</li>
 *   <li><b>算电</b>：{@link #takeCharge} 逐刻扣电 —— <b>注意是每 tick 扣</b>，
 *       yml 里的 {@code energyPerCraft} 名字是骗人的，它其实是「每粘液刻的耗电」。
 *       而且 {@code capacity == 0} 时 {@code isChargeable()} 为假，整台机器<b>完全不接电网</b>。</li>
 *   <li><b>记住上次匹配到的配方</b>：多条配方同时可匹配时，机器要"认死"同一条而不是反复横跳。
 *       RSC 用一张静态 {@code Long2ObjectOpenHashMap}，key 是
 *       {@code accessorId | (location.hashCode() << 32)}；本附属只有「上一条配方」一个 accessor，
 *       所以原生版直接用 {@code location.hashCode()} 当 key，语义等价。</li>
 * </ol>
 *
 * <p>★ 本附属四份机器 yml 里 {@code script:} 出现 <b>0 次</b>，所以原生版
 * <b>不实现任何脚本钩子</b>（没有 {@code onTick}、没有菜单 {@code onClick/onOpen/onClose}）。
 * RSC 里 {@code eval == null} 时这些钩子本来就全是空操作。
 *
 * <p>★ {@code canTick}（RSC 的 {@code SuperMultiBlockManager.canTick}）在本附属恒为 true
 * —— 本附属不使用超大多方块，所以原生版直接省略这一层判断，行为一致。
 */
public abstract class MachineTicker implements EnergyNetComponent, MachineProcessHolder<CraftingOperation> {

    /** 默认进度条槽位（RSC {@code CustomMenuHolder.DEFAULT_PROGRESS_SLOT}）。 */
    public static final int DEFAULT_PROGRESS_SLOT = 22;

    /** 默认进度条材质（RSC {@code CustomMenuHolder.DEFAULT_PROGRESS_BAR}）。 */
    public static final ItemStack DEFAULT_PROGRESS_BAR = new CustomItemStack(Material.BLACK_STAINED_GLASS_PANE);

    /** 无自定义菜单时的默认边框（RSC 逐字照抄）。 */
    public static final int[] DEFAULT_BORDER = {0, 1, 2, 3, 4, 5, 6, 7, 8, 13, 31, 36, 37, 38, 39, 40, 41, 42, 43, 44};
    /** 默认输入槽贴图位置。 */
    public static final int[] DEFAULT_BORDER_IN = {9, 10, 11, 12, 18, 21, 27, 28, 29, 30};
    /** 默认输出槽贴图位置。 */
    public static final int[] DEFAULT_BORDER_OUT = {14, 15, 16, 17, 23, 26, 32, 33, 34, 35};

    /** 上一条匹配成功的配方（按方块位置）。RSC 用 static 表，原生版保持一致。 */
    private static final Map<Long, CustomMachineRecipe> LAST_RECIPE = new ConcurrentHashMap<>();

    private final AbstractMachine machine;
    private final MachineMenu menu;
    private final List<CustomMachineRecipe> recipes;
    private final MachineProcessorEx processor;

    protected MachineTicker(AbstractMachine machine, MachineMenu menu, List<CustomMachineRecipe> recipes) {
        this.machine = machine;
        this.menu = menu;
        this.recipes = recipes;
        this.processor = new MachineProcessorEx(this);
    }

    // ------------------------------------------------------------------ 必须实现

    public abstract void tick(Location location);

    /** 电量是否够（并扣电）。材料发生器在电量不足时还要刷「电力不足」状态格，所以各自实现。 */
    public abstract boolean preTick(Location location);

    // ------------------------------------------------------------------ 菜单

    /**
     * 造 preset 并接上菜单。
     *
     * <p>★ 顺序与 RSC 一致：<b>先</b> createPreset，<b>后</b> setProgressBar。
     * 因为 preset 的 {@code init()} 是在 {@code BlockMenuPreset} 构造器里就跑掉的，
     * 那时进度条还没换 —— 与 RSC 完全一样。
     */
    public void init() {
        MachineMenu m = getMenu();
        if (m == null) {
            GS.warn("未找到菜单 %s 使用默认菜单", machine.getId());
            createPreset(machine, machine.getItemName(),
                    preset -> constructMenu(preset, getProgressSlot(), getProgressBar()),
                    this::onNewInstance);
            return;
        }

        createPreset(machine, m.titleOrDefault(machine.getItemName()), m::apply, this::onNewInstance);
        if (m.getProgressBar() != null) {
            processor.setProgressBar(m.getProgressBar());
        }
    }

    /** 默认菜单的贴图布局（RSC {@code CustomMenuHolder.constructMenu}）。 */
    public static void constructMenu(ChestMenu menu, int progressSlot, ItemStack progressBar) {
        constructMenu(menu);
        if (progressBar != null) {
            menu.addItem(progressSlot, progressBar, ChestMenuUtils.getEmptyClickHandler());
        }
    }

    public static void constructMenu(ChestMenu menu) {
        for (int i : DEFAULT_BORDER) {
            menu.addItem(i, ChestMenuUtils.getBackground(), ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i : DEFAULT_BORDER_IN) {
            menu.addItem(i, ChestMenuUtils.getInputSlotTexture(), ChestMenuUtils.getEmptyClickHandler());
        }
        for (int i : DEFAULT_BORDER_OUT) {
            menu.addItem(i, ChestMenuUtils.getOutputSlotTexture(), ChestMenuUtils.getEmptyClickHandler());
        }
    }

    /**
     * 造 {@code BlockMenuPreset} —— 逐字等价于 RSC {@code CustomMenuHolder.createPreset}。
     *
     * <p>{@code getSlotsAccessedByItemTransport} 是这里最关键的一句：INSERT 返回输入槽、
     * WITHDRAW 返回输出槽。{@link InvIndex} 与出货逻辑全都读这个，改错就会「物品进不来/出不去」。
     */
    private void createPreset(SlimefunItem item, String title, Consumer<BlockMenuPreset> setup,
                              BiConsumer<BlockMenu, Block> onNewInstance) {
        new BlockMenuPreset(item.getId(), title) {
            @Override
            public void init() {
                if (setup != null) {
                    setup.accept(this);
                }
            }

            @Override
            public void newInstance(BlockMenu menu, Block b) {
                if (onNewInstance != null) {
                    onNewInstance.accept(menu, b);
                }
            }

            @Override
            public int[] getSlotsAccessedByItemTransport(ItemTransportFlow flow) {
                if (flow == ItemTransportFlow.INSERT) {
                    return getInputSlots();
                }
                return getOutputSlots();
            }

            @Override
            public boolean canOpen(Block b, Player p) {
                return p.isOp() || p.hasPermission("slimefun.inventory.bypass")
                        || (item.canUse(p, false)
                        && Slimefun.getProtectionManager().hasPermission(p, b.getLocation(), Interaction.INTERACT_BLOCK));
            }
        };
    }

    public void onNewInstance(BlockMenu menu, Block b) {
        machine.onNewInstance(menu, b);
    }

    // ------------------------------------------------------------------ 电力

    @Override
    public EnergyNetComponentType getEnergyComponentType() {
        return EnergyNetComponentType.CONSUMER;
    }

    /** ★ 逐字照抄 RSC：{@code isChargeable()} 为假（capacity == 0）时直接放行、不扣电。 */
    public boolean takeCharge(Location l) {
        if (isChargeable()) {
            int charge = getCharge(l);
            if (charge < getEnergyConsumption()) {
                return false;
            }
            setCharge(l, charge - getEnergyConsumption());
        }
        return true;
    }

    // ------------------------------------------------------------------ 进度条

    public int getProgressSlot() {
        MachineMenu m = getMenu();
        if (m == null || m.getProgressSlot() == -1) {
            return DEFAULT_PROGRESS_SLOT;
        }
        return m.getProgressSlot();
    }

    public ItemStack getProgressBar() {
        MachineMenu m = getMenu();
        if (m == null || m.getProgressBar() == null) {
            return DEFAULT_PROGRESS_BAR;
        }
        return m.getProgressBar();
    }

    // ------------------------------------------------------------------ 配方

    /** 找下一条可匹配的配方（跳过上次用的那条，避免反复横跳）。 */
    public CustomMachineRecipe findNextRecipe(InvIndex index, CustomMachineRecipe lastRecipe) {
        for (CustomMachineRecipe recipe : recipes) {
            if (recipe != lastRecipe && recipe.matches(index)) {
                return recipe;
            }
        }
        return null;
    }

    /** 配方页展示：输入/产物成对排列，隐藏的跳过。 */
    public List<ItemStack> getDisplayRecipes() {
        List<ItemStack> displayRecipes = new ArrayList<>();
        int i = 0;
        for (CustomMachineRecipe recipe : recipes) {
            if (recipe.isHide()) {
                continue;
            }
            displayRecipes.add(recipe.getDisplayInput(i));
            displayRecipes.add(recipe.getDisplayOutput(i));
            i++;
        }
        return displayRecipes;
    }

    public List<CustomMachineRecipe> getRecipes() {
        return recipes;
    }

    // ------------------------------------------------------------------ 缓存

    public CustomMachineRecipe getCache(Location location) {
        return LAST_RECIPE.get(key(location));
    }

    public void setCache(Location location, CustomMachineRecipe recipe) {
        LAST_RECIPE.put(key(location), recipe);
    }

    private static long key(Location location) {
        return location.hashCode();
    }

    // ------------------------------------------------------------------ 属性

    @Override
    public String getId() {
        return machine.getId();
    }

    @Override
    public io.github.thebusybiscuit.slimefun4.core.machines.MachineProcessor<CraftingOperation> getMachineProcessor() {
        return processor;
    }

    public MachineProcessorEx getProcessor() {
        return processor;
    }

    public MachineMenu getMenu() {
        return menu;
    }

    public AbstractMachine getMachine() {
        return machine;
    }

    public String getMachineIdentifier() {
        return machine.getId();
    }

    public int[] getInputSlots() {
        return machine.getInputSlots();
    }

    public int[] getOutputSlots() {
        return machine.getOutputSlots();
    }

    public int getEnergyConsumption() {
        return machine.getEnergyConsumption();
    }

    @Override
    public int getCapacity() {
        return machine.getCapacity();
    }
}
