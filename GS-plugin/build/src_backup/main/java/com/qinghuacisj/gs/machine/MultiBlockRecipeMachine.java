package com.qinghuacisj.gs.machine;

import com.google.common.base.Preconditions;
import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.MultiBlockCraftEvent;
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType;
import io.github.thebusybiscuit.slimefun4.core.multiblocks.MultiBlockMachine;
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import io.github.thebusybiscuit.slimefun4.libraries.dough.items.ItemUtils;
import io.github.thebusybiscuit.slimefun4.libraries.paperlib.PaperLib;
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多方块机器 —— 等价于 RSC 的 {@code customs\CustomMultiBlockMachine}。
 *
 * <p><b>为什么继承核心 {@code MultiBlockMachine}：</b>它是核心唯一实现了「右键触发方块 →
 * 检查周围结构 → 检查发射器内容 → 发事件 → 出货」这一整套流程的类，而且
 * {@code postRegister()} 会把自己加进 {@code Slimefun.getRegistry().getMultiBlocks()}，
 * 结构识别（{@code MultiBlock} 的对称性匹配）也由它负责。RSC 的类名虽叫 Custom，
 * 实际只覆写了 {@code load()}、{@code getDisplayRecipes()} 和 {@code onInteract}。
 *
 * <p>★ <b>{@code recipe_type} 被无视</b>：核心 4 参构造器写死传 {@code RecipeType.MULTIBLOCK}
 * （已用 javap 核对字节码），所以 {@code mb_machines.yml} 里就算写了 {@code recipe_type} 也没用。
 * 本附属的 {@code mb_machines.yml} 也确实没写。
 *
 * <p>★ <b>{@code getDisplayRecipes()} 返回空表</b>，于是 {@code load()} 里那段
 * 「成对配对并跳过被禁用物品」的循环<b>一次都不执行</b> —— 真正的配方是构造器里
 * {@code addRecipe()} 加进去的。照搬这个「空实现」，别改成返回真实配方列表：
 * 那样会把 26 条配方<b>再加一遍</b>（{@code recipes} 变成 52 组），
 * {@code RecipeType.getRecipeOutputList} 的 {@code indexOf} 会取到错误位置。
 *
 * <p>★ {@code getFace(work)} 只认 2 和 8（工作方块在第 2 格 → 发射器在上方；
 * 第 8 格 → 在下方），其余一律 {@code SELF}（同层）。本附属 {@code work: 5} ⇒ {@code SELF}，
 * 发射器在<b>同一层</b>，具体方位再由 {@link #dispenserFaceGet()} 从 3x3 配方里推断。
 */
public class MultiBlockRecipeMachine extends MultiBlockMachine {

    /** 触发用的音效（yml 里 {@code sound:} 解析而来；不写就没有声音）。 */
    private final SoundEffect craftSound;

    /** 工作方块在 3x3 配方里的下标（yml 的 {@code work} 减 1）。 */
    private final int workIndex;

    /** 发射器相对工作方块的方向，由配方推断。 */
    private final BlockFace dispenserFace;

    public MultiBlockRecipeMachine(ItemGroup itemGroup, SlimefunItemStack item, ItemStack[] recipe,
                                   Map<ItemStack[], ItemStack> craftRecipes, int work, SoundEffect craftSound) {
        super(itemGroup, item, recipe, getFace(work));

        this.workIndex = work - 1;
        this.craftSound = craftSound;
        this.dispenserFace = dispenserFaceGet();

        for (Map.Entry<ItemStack[], ItemStack> e : craftRecipes.entrySet()) {
            addRecipe(e.getKey(), e.getValue());
        }

        // ★ 注册必须在构造器<b>最后</b>一行 —— 逐字等价于 RSC 的
        //   CustomMultiBlockMachine：register() 会立刻回调 load()（挂组 + 登记配方类型）
        //   与 postRegister()（把自己加进 getMultiBlocks()），那时本对象的字段必须已经就位。
        //   registerDefaultRecipes() 里 26 条配方已经在上面加完了。
        register(GS.plugin());
    }

    /**
     * ★ 逐字照抄 RSC：只有 2 / 8 两个特例，其余同层。
     *
     * <p>{@code work} 的取值范围由读 yml 时保证是 1..9，所以这里不会出现 0 或 10。
     */
    public static BlockFace getFace(int work) {
        return switch (work) {
            case 2 -> BlockFace.UP;
            case 8 -> BlockFace.DOWN;
            default -> BlockFace.SELF;
        };
    }

    /**
     * ★ <b>覆写成空表</b>，见类注释。注意 {@code load()} 读的是父类的
     * {@code displayRecipes} <b>字段</b>（构造器里被初始化成空表），不是这个 getter。
     */
    @Override
    public List<ItemStack> getDisplayRecipes() {
        return new ArrayList<>();
    }

    /**
     * 注册时把机器挂到物品组上，并把「机器本体怎么合成」登记进 {@code MULTIBLOCK} 配方类型。
     *
     * <p>★ 与核心 {@code MultiBlockMachine.load()} 的差别：核心先调 {@code super.load()}，
     * 但 RSC 版本<b>没有</b>调 {@code super.load()}（{@code SlimefunItem.load()} 是空的，
     * 唯一副作用是核心版那段配对循环，而这里 {@code displayRecipes} 是空的所以等价）。
     * 另外 RSC 用了 {@code BaseRSCItemGroup.addItemToGroup}，原生版走本附属自己的
     * {@code GsItemGroup.addContent}。
     */
    @Override
    public void load() {
        if (!hidden) {
            attachToGroup();
        }

        getRecipeType().register(getRecipe(), getRecipeOutput());

        // ★ 保留这条断言：它是「displayRecipes 被谁改了」的哨兵
        Preconditions.checkArgument(
                getDisplayRecipesField().size() % 2 == 0,
                "This MultiBlockMachine's display recipes were illegally modified!");

        List<ItemStack> displayRecipes = getDisplayRecipesField();
        for (int i = 0; i < displayRecipes.size(); i += 2) {
            ItemStack inputStack = displayRecipes.get(i);
            ItemStack outputStack = null;
            if (displayRecipes.size() >= i + 2) {
                outputStack = displayRecipes.get(i + 1);
            }

            SlimefunItem inputItem = SlimefunItem.getByItem(inputStack);
            SlimefunItem outputItem = SlimefunItem.getByItem(outputStack);
            // 输入/产物不是粘液物品，或对应的粘液物品没被禁用 ⇒ 这条配方有效
            if ((inputItem == null || !inputItem.isDisabled()) && (outputItem == null || !outputItem.isDisabled())) {
                recipes.add(new ItemStack[] {inputStack});
                recipes.add(new ItemStack[] {outputStack});
            }
        }
    }

    /** 父类的 {@code displayRecipes} 字段（protected）。 */
    private List<ItemStack> getDisplayRecipesField() {
        return displayRecipes;
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
            GS.warn("无法把多方块机器 %s 加入 %s：它是 FlexItemGroup", getId(), group.getKey());
            return;
        }
        group.add(this);
    }

    /**
     * 右键工作方块 —— 逐字等价于 RSC 的 {@code onInteract}。
     *
     * <p>流程：工作方块材质必须与配方里 {@code workIndex} 位置的方块一致 →
     * 取相对方向的发射器 → 逐条配方做 {@code isCraftable} →
     * 发 {@code MultiBlockCraftEvent} → 检查玩家权限 → 建假背包算产物去处 →
     * 扣料 + 播音效 + 出货。
     *
     * <p>★ 三处「反直觉但必须保留」的细节：
     * <ol>
     *   <li>{@code isCraftable} 是<b>双重宽松</b>比对：先 {@code (checkLore=true, checkAmount=true)}
     *       再退回 {@code (checkLore=false, checkAmount=true)}，并且空配方格要求背包格<b>真空</b>，
     *       多出来的物品会让匹配失败。</li>
     *   <li>扣料用的是 {@code ItemUtils.consumeItem(item, input[j].getAmount(), true)}
     *       —— 扣的是<b>配方要求的量</b>，不是背包里那一格的全部。</li>
     *   <li>没有任何进度条 / 耗电 / 逐刻循环 —— 多方块机器是<b>瞬间</b>完成的。
     *       也没有 {@code onWork} 脚本钩子（本附属 0 处 {@code script:}）。</li>
     * </ol>
     */
    @Override
    public void onInteract(Player p, Block block) {
        Material material = super.getRecipe()[workIndex].getType();
        if (block.getType().equals(material)) {
            Block disBlock = block.getRelative(dispenserFace);
            BlockState bs = PaperLib.getBlockState(disBlock, false).getState();
            if (bs instanceof Dispenser dispenser) {
                Inventory inv = dispenser.getInventory();
                ItemStack[] contents = inv.getContents();

                List<ItemStack[]> inputs = RecipeType.getRecipeInputList(this);

                for (ItemStack[] input : inputs) {
                    if (isCraftable(inv, input)) {
                        ItemStack output = RecipeType.getRecipeOutputList(this, input).clone();
                        MultiBlockCraftEvent event = new MultiBlockCraftEvent(p, this, input, output);

                        Bukkit.getPluginManager().callEvent(event);
                        if (!event.isCancelled() && SlimefunUtils.canPlayerUseItem(p, output, true)) {
                            Inventory fakeInv = createVirtualInventory(inv);
                            Inventory outputInv = findOutputInventory(output, disBlock, inv, fakeInv);
                            if (outputInv != null) {
                                for (int j = 0; j < input.length; ++j) {
                                    ItemStack item = contents[j];
                                    if (item != null && item.getType() != Material.AIR) {
                                        ItemUtils.consumeItem(item, input[j].getAmount(), true);
                                    }
                                }

                                if (craftSound != null) {
                                    craftSound.playAt(block);
                                }
                                outputInv.addItem(output);
                            } else {
                                Slimefun.getLocalization().sendMessage(p, "machines.full-inventory", true);
                            }
                        }
                        return;
                    }
                }

                if (inv.isEmpty()) {
                    Slimefun.getLocalization().sendMessage(p, "machines.inventory-empty", true);
                } else {
                    Slimefun.getLocalization().sendMessage(p, "machines.pattern-not-found", true);
                }
            }
        }
    }

    /**
     * 造一个「每格只留 1 个」的假背包 —— 逐字照抄 RSC。
     *
     * <p>为什么需要它：{@code findOutputInventory} 要用它来预判「扣完料之后腾得出多少空间」。
     * 核心版本的 {@code createVirtualInventory} 也是这个思路，但 RSC 的实现把
     * {@code inv.getContents()} <b>每次循环都重新取一遍</b>（{@code getContents()} 返回的是快照副本），
     * 并且对 null 格也 {@code setItem} —— 行为一致，照搬。
     */
    protected Inventory createVirtualInventory(Inventory inv) {
        Inventory fakeInv = Bukkit.createInventory(null, 9, Component.text("Fake Inventory"));

        for (int j = 0; j < inv.getContents().length; ++j) {
            ItemStack stack = inv.getContents()[j];
            if (stack != null) {
                stack = stack.clone();
                ItemUtils.consumeItem(stack, true);
            }

            fakeInv.setItem(j, stack);
        }

        return fakeInv;
    }

    /**
     * ★ 双重宽松比对 —— 逐字照抄 RSC。
     *
     * <p>注意循环上界用的是 {@code inv.getContents().length}（发射器是 9 格），
     * 而 {@code recipe[j]} 的下标直接跟着走；配方数组由读 yml 时保证长度 9。
     * 空配方格（{@code null}）会走 {@code isItemSimilar} 的「都为空」分支 ——
     * 所以空配方格要求背包格也真空。
     */
    private boolean isCraftable(Inventory inv, ItemStack[] recipe) {
        for (int j = 0; j < inv.getContents().length; ++j) {
            if (!SlimefunUtils.isItemSimilar(inv.getContents()[j], recipe[j], true, true, false, false)) {
                if (!SlimefunUtils.isItemSimilar(inv.getContents()[j], recipe[j], false, true, false, false)) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * ★ 从 3x3 配方里推断发射器方向 —— 逐字照抄 RSC，<b>包括那几个看起来很怪的边界判断</b>。
     *
     * <p>判定顺序与守卫条件（{@code center = workIndex}）：
     * <ol>
     *   <li>{@code center - 3 > 0} 且该格是发射器 → {@code UP}</li>
     *   <li>{@code is[center - 1]} 是发射器 → {@code EAST}（<b>没有</b>边界守卫）</li>
     *   <li>{@code center + 1 >= 9} → {@code SELF}（提前返回）</li>
     *   <li>{@code is[center + 1]} 是发射器 → {@code WEST}</li>
     *   <li>{@code center + 3 < 8} 且 {@code is[center + 3]} 是发射器 → {@code DOWN}</li>
     *   <li>都不满足 → {@code SELF}</li>
     * </ol>
     *
     * <p>★ 这里的 {@code > 0} / {@code >= 9} / {@code < 8} 都是 RSC 原文的<b>可疑写法</b>
     * （正常应该是 {@code >= 3} / {@code < 9} / {@code <= 8}）。改"对"了会让某些
     * work 值下推断出不同的方向，直接改变玩家该把发射器放在哪 —— 所以<b>原样保留</b>。
     */
    private BlockFace dispenserFaceGet() {
        int center = workIndex;
        ItemStack[] is = getRecipe();
        if (center - 3 > 0) {
            ItemStack o1 = is[center - 3];
            if (o1 != null && o1.getType().equals(Material.DISPENSER)) {
                return BlockFace.UP;
            }
        }

        ItemStack o2 = is[center - 1];
        if (o2 != null && o2.getType().equals(Material.DISPENSER)) {
            return BlockFace.EAST;
        }

        if (center + 1 >= 9) {
            return BlockFace.SELF;
        }

        ItemStack o3 = is[center + 1];

        if (o3 != null && o3.getType().equals(Material.DISPENSER)) {
            return BlockFace.WEST;
        }
        if ((center + 3) < 8) {
            ItemStack o4 = is[center + 3];
            if (o4 != null && o4.getType().equals(Material.DISPENSER)) {
                return BlockFace.DOWN;
            }
        }

        return BlockFace.SELF;
    }

    public int getWorkIndex() {
        return workIndex;
    }

    public BlockFace getDispenserFace() {
        return dispenserFace;
    }

    public SoundEffect getCraftSound() {
        return craftSound;
    }

    /** 供读 yml 时校验「配方里必须有一个发射器」用。 */
    public static boolean containsDispenser(ItemStack[] recipe) {
        if (recipe == null) {
            return false;
        }
        for (ItemStack stack : recipe) {
            if (stack != null && stack.getType() == Material.DISPENSER) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读 yml 时用的配方容器 —— 与 RSC 一致用 {@code HashMap}（<b>不是</b> LinkedHashMap）。
     *
     * <p>★ 这一点<b>看起来</b>只是顺序问题，实际会影响「多条配方同时可匹配时先命中哪一条」：
     * {@code ItemStack[]} 的哈希是<b>身份哈希</b>，所以 RSC 里这个顺序本来就不稳定
     * （每次重启都可能不同）。改用 LinkedHashMap 会变成「稳定的声明顺序」——
     * 那是<b>改变行为</b>，不是改进。原样保留 {@code HashMap}。
     */
    public static Map<ItemStack[], ItemStack> newRecipeMap() {
        return new HashMap<>();
    }
}
