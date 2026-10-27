package com.qinghuacisj.gs.machine;

import io.github.thebusybiscuit.slimefun4.libraries.dough.items.CustomItemStack;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.MachineRecipe;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu;
import me.mrCookieSlime.Slimefun.api.item_transport.ItemTransportFlow;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 带概率 / 免消耗 / 多物品输入的机器配方 —— 等价于 RSC 的
 * {@code AbstractRecipe} + {@code bulit_in\recipes\CustomMachineRecipe}。
 *
 * <p><b>为什么继承核心 {@code MachineRecipe} 而不是另起炉灶：</b>
 * {@code AContainer.recipes} 的类型就是 {@code List<MachineRecipe>}，而
 * {@code AContainer.registerRecipe(MachineRecipe)} 会做
 * {@code recipe.setTicks(recipe.getTicks() / getSpeed())} 并按 speed 缩放 tick。
 * 只要让子类<b>覆写 {@code getTicks}/{@code setTicks} 去读写自己的 raw 字段</b>，
 * 就能白拿这套「速度即除数」的机制 —— 这正是 RSC {@code AbstractRecipe} 的做法，逐字照搬。
 *
 * <p>★ tick 语义（最容易搞错的一处）：
 * <pre>
 *   yml seconds      = 3
 *   raw ticks        = seconds * 2                = 6      （构造时）
 *   有效 ticks T     = raw / speed                         （AContainer.registerRecipe 里除）
 *   配方页显示秒数   = T / 2   （整数除法）        = 3
 *   实际占用的粘液刻 = T + 2                               （建操作 1 刻 + 出货 1 刻）
 * </pre>
 * 材料发生器不同：{@code tickRate} <b>就是</b> raw ticks（不乘 2、speed 固定为 1），
 * 所以 {@code T = tickRate}。
 *
 * <p>★ 概率语义（不是「加权抽一个」）：
 * <ol>
 *   <li>每个产物<b>各自独立</b>掷点：{@code chance >= 100} 恒真、{@code chance < 1} 恒假，
 *       否则 {@code SecureRandom.nextInt(100) < chance}</li>
 *   <li>{@code chooseOne: true} 再从<b>已经掷成功</b>的那些里等概率取一个 ——
 *       不是对所有产物做加权抽签</li>
 * </ol>
 *
 * <p>★ 输入在<b>开工那一刻</b>就被扣掉（{@code matches} 带副作用），不是完工时扣。
 * 中途拆机器 / 重启会丢材料 —— 这是 RSC 的行为，照搬。
 */
public class CustomMachineRecipe extends MachineRecipe {

    /** 多物品输入时配方页显示的占位物。 */
    public static final ItemStack RECIPE_INPUT =
            new CustomItemStack(Material.GREEN_STAINED_GLASS_PANE, "&a多物品输入", "", "&2> &a点击查看");

    /** 多物品输出时配方页显示的占位物。 */
    public static final ItemStack RECIPE_OUTPUT =
            new CustomItemStack(Material.GREEN_STAINED_GLASS_PANE, "&a多物品输出", "", "&2> &a点击查看");

    private final List<InputWrapper> inputs;
    private final List<ItemWrapper> outputs;
    /** 每个产物各自的概率（1..100，已在读 yml 时 clamp）。 */
    private final int[] chances;
    private final boolean chooseOneIfHas;
    private final boolean forDisplay;
    private final boolean hide;
    private final boolean noConsumeAll;

    /** ★ 自己的 raw tick 字段：覆写 get/set 就是为了绕开父类「构造时乘 2」的换算。 */
    private int ticks;

    /** 秒数构造（recipe machines 用；{@code seconds * 2} 得到 raw ticks）。 */
    public CustomMachineRecipe(int seconds, List<InputWrapper> input, ItemStack[] output, int[] chances,
                               boolean chooseOne, boolean forDisplayOnly, boolean hide, boolean noConsumeAll) {
        this(input, output, seconds * 2, chances, chooseOne, forDisplayOnly, hide, noConsumeAll);
    }

    /** raw ticks 构造（材料发生器用，直接传 tickRate）。 */
    public CustomMachineRecipe(List<InputWrapper> input, ItemStack[] output, int ticks, int[] chances,
                               boolean chooseOne, boolean forDisplayOnly, boolean hide, boolean noConsumeAll) {
        super(ticks / 2, flatten(input), output);

        this.inputs = input;
        this.outputs = InvIndex.mergeItems(output);
        this.chances = chances;
        this.chooseOneIfHas = chooseOne;
        this.forDisplay = forDisplayOnly;
        this.hide = hide;
        this.noConsumeAll = noConsumeAll;

        // ★ 父类 ctor 已经把 (ticks / 2) * 2 写进了父类字段，这里必须再覆盖成 raw ticks
        this.ticks = ticks;
    }

    /** 把 wrapper 展开成「喂给父类的物品栈数组」。 */
    private static ItemStack[] flatten(List<InputWrapper> input) {
        List<ItemStack> list = new ArrayList<>();
        for (InputWrapper wrapper : input) {
            wrapper.asArrayStream().forEach(list::add);
        }
        return list.toArray(new ItemStack[0]);
    }

    /** 把核心 {@code MachineRecipe} 转成自己的类型（{@code recipes_import_from} 用）。 */
    public static CustomMachineRecipe from(MachineRecipe mr) {
        if (mr instanceof CustomMachineRecipe cmr) {
            return cmr;
        }
        List<InputWrapper> inputs = new ArrayList<>();
        for (ItemStack stack : mr.getInput()) {
            if (stack != null) {
                inputs.add(InputWrapper.create(new InputDesc(stack, -1, false)));
            }
        }
        return new CustomMachineRecipe(inputs, mr.getOutput(), mr.getTicks(),
                fullChance(mr.getOutput().length), false, false, false, false);
    }

    public static int[] fullChance(int size) {
        int[] list = new int[size];
        for (int i = 0; i < size; i++) {
            list[i] = 100;
        }
        return list;
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void setTicks(int ticks) {
        this.ticks = ticks;
    }

    @Override
    public int getTicks() {
        return ticks;
    }

    // ------------------------------------------------------------------ 概率

    /**
     * 掷点并返回本次实际产出的物品。
     *
     * @param chooseOne true 时从「已掷成功」的产物里等概率取一个
     */
    public List<ItemStack> getMatchChanceResult(boolean chooseOne) {
        List<ItemStack> stacks = new ArrayList<>();

        for (int i = 0; i < getOutput().length; i++) {
            if (matchChance(chances[i])) {
                stacks.add(getOutput()[i].clone());
            }
        }

        if (stacks.isEmpty()) {
            return stacks;
        }

        if (chooseOne) {
            int index = new Random().nextInt(stacks.size());
            return List.of(stacks.get(index));
        }

        return stacks;
    }

    /** 逐字照抄 RSC 的 {@code matchChance}。 */
    public static boolean matchChance(int chance) {
        if (chance >= 100) {
            return true;
        }
        if (chance < 1) {
            return false;
        }

        int result = new SecureRandom().nextInt(100);
        return result < chance;
    }

    // ------------------------------------------------------------------ 匹配 / 消耗

    public boolean matches(InvIndex index) {
        return matches(index, true);
    }

    /**
     * ★ 这个方法<b>有副作用</b>：{@code consumeItems} 为真时会真的把输入扣掉。
     * RSC 就是这么用的（开工即扣料），别把它当纯谓词。
     */
    public boolean matches(InvIndex index, boolean consumeItems) {
        if (this.isForDisplayOnly()) {
            return false;
        }

        for (InputWrapper input : inputs) {
            if (!index.matches(input)) {
                return false;
            }
        }

        // 产物放不下就别开工
        int slotsOutputTakes = 0;
        for (ItemWrapper wrapper : outputs) {
            slotsOutputTakes += wrapper.countStack();
        }
        if (index.getEmptyOutputSlots() < slotsOutputTakes) {
            BlockMenu inv = index.getInv();
            if (!BlockMenuUtil.fits(inv, outputs,
                    inv.getPreset().getSlotsAccessedByItemTransport(ItemTransportFlow.WITHDRAW))) {
                return false;
            }
        }

        if (consumeItems) {
            if (isNoConsumeAll()) {
                return true;
            }
            BlockMenu inv = index.getInv();
            for (InputWrapper wrapper : inputs) {
                for (SlotWrapper slotWrapper : index.getInputs()) {
                    if (!StackUtils.itemsMatch(slotWrapper.getStack(), wrapper.getStack())) {
                        continue;
                    }
                    int left = wrapper.getConsumeAmount();
                    if (left <= 0) {
                        continue;
                    }
                    for (Map.Entry<Integer, Integer> entry : slotWrapper.getAmounts().entrySet()) {
                        int slot = entry.getKey();
                        // 绑定为「不消耗」的槽位整槽跳过
                        if (wrapper.getNoConsume().getLinkedNoConsume().contains(slot)) {
                            continue;
                        }
                        int curr = entry.getValue();
                        if (curr <= left) {
                            left -= curr;
                            inv.consumeItem(slot, curr);
                        } else {
                            inv.consumeItem(slot, left);
                            break;
                        }
                    }
                    break;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 出货

    /**
     * 把本次掷中的产物推进输出槽。
     *
     * <p>★ 恒返回 {@code true}（G-9）：{@code BlockMenuUtil.pushItem} 的余量表被<b>丢弃</b>了，
     * 所以材料发生器永远显示不出「空间不足」，溢出产物静默消失。
     * 本附属未安装 LogiTech，这条路径就是线上实际行为 —— 照搬。
     */
    public boolean pushOutputs(BlockMenu inv) {
        BlockMenuUtil.pushItem(inv, getMatchChanceResult(isChooseOne()),
                inv.getPreset().getSlotsAccessedByItemTransport(ItemTransportFlow.WITHDRAW));
        return true;
    }

    // ------------------------------------------------------------------ 查询

    public List<InputWrapper> getInputs() {
        return inputs;
    }

    public List<ItemWrapper> getOutputs() {
        return outputs;
    }

    public int[] getChances() {
        return chances;
    }

    public boolean isChooseOne() {
        return chooseOneIfHas;
    }

    public boolean isForDisplayOnly() {
        return forDisplay;
    }

    public boolean isHide() {
        return hide;
    }

    public boolean isNoConsumeAll() {
        return noConsumeAll;
    }

    public List<ItemWrapper> cloneOutputs() {
        List<ItemWrapper> result = new ArrayList<>();
        for (ItemWrapper output : outputs) {
            result.add(output.clone());
        }
        return result;
    }

    // ------------------------------------------------------------------ 配方页展示

    public ItemStack getDisplayInput(int index) {
        if (inputs.size() == 1 && inputs.getFirst().countStack() == 1) {
            return inputs.getFirst().asOneStack();
        }
        return RECIPE_INPUT.clone();
    }

    public ItemStack getDisplayOutput(int index) {
        if (outputs.size() == 1) {
            ItemStack out = outputs.getFirst().asOneStack().clone();
            com.qinghuacisj.gs.content.Items.addLore(out, true, richFormatSeconds(getTicks() / 2));
            return out;
        }
        return RECIPE_OUTPUT.clone();
    }

    /** 逐字照抄 RSC 的 {@code richFormatSeconds}：{@code &e制作时间: &bN&es}，超过 60 秒追加可读格式。 */
    public static String richFormatSeconds(int seconds) {
        String lore = "&e制作时间: &b" + seconds + "&es";
        if (seconds > 60) {
            lore = lore.concat("(" + formatSeconds(seconds) + "&e)");
        }
        return lore;
    }

    /** 逐字照抄 RSC 的 {@code formatSeconds}。 */
    public static String formatSeconds(int seconds) {
        if (seconds < 60) {
            return "&b" + seconds + "&es";
        } else if (seconds > 60 && seconds < 3600) {
            int m = seconds / 60;
            int s = seconds % 60;
            return "&b" + m + "&emin" + (s != 0 ? "&b" + s + "&es" : "");
        } else {
            int h = seconds / 3600;
            int m = (seconds % 3600) / 60;
            int s = (seconds % 3600) % 60;
            return "&b" + h + "&eh" + (m != 0 ? "&b" + m + "&emin" : "") + (s != 0 ? "&b" + s + "&es" : "");
        }
    }

}
