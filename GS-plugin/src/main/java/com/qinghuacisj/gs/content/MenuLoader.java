package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.machine.MachineMenu;
import com.qinghuacisj.gs.machine.Menus;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 菜单加载器 —— 等价于 RSC 的 {@code MenuReader}。
 *
 * <p>★★ <b>本类必须在所有机器加载器之前跑完</b>（G-22）。原因在
 * {@code BlockMenuPreset} 的构造器：它会执行 {@code Slimefun.getRegistry().getMenuPresets().put(id, this)}，
 * 也就是<b>按机器 id 覆盖同名的菜单 preset</b>。而机器在注册时又会去 {@code Menus} 里按 id 取菜单。
 * 如果机器先跑，它取到的是「上一轮遗留 / 不存在」的 preset；菜单后跑又会把机器的 preset 顶掉，
 * 结果就是「菜单里的装饰玻璃板全部消失、变成默认边框」。
 *
 * <p>本附属 {@code menus.yml} 共 <b>7 个</b>菜单，全部走 {@code slots:} 路径：
 * 没有 {@code import}、没有 {@code matrix}、没有 {@code script}。二级键只有
 * {@code size} / {@code slots} / {@code title}；槽级键只有
 * {@code material} / {@code name} / {@code progressbar} / {@code progressBarItem}。
 *
 * <p>★ 逐字保留的三处 RSC 细节：
 * <ol>
 *   <li><b>菜单 id 就是 yml 的顶层 key 原文</b>（<b>不</b>大写化、<b>不</b>走 id_alias）。
 *       机器的 id 走 {@code getId()}（大写化），两者靠 {@code equalsIgnoreCase} 对上 ——
 *       本附属两份 yml 里的大小写本来就一致。</li>
 *   <li>{@code size} 的校验顺序：先查「是不是 9 的倍数」，再查「在不在 9..54」。
 *       注意第二条写成了 {@code (size != -1 && size < 9) || size > 54} ——
 *       {@code size == -1}（没写）会通过。</li>
 *   <li>{@code progressbar: true} 同时干两件事：把该槽位记为进度槽，
 *       并把「该槽的物品」当作进度条物品（除非另有 {@code progressBarItem}）。</li>
 * </ol>
 *
 * <p>★ 有意省略：RSC 会给进度条物品写一个 {@code rsc:progress} 的 PDC 标记。
 * 全仓库 grep 只有<b>写入</b>、没有读取（死代码），且写了会改变装饰物的 NBT，
 * 所以不搬。
 */
public final class MenuLoader extends Loader<MachineMenu> {

    public MenuLoader() {
        super("menus.yml");
    }

    /** 菜单不参与物品预加载（与 RSC 的 {@code preloadItems} 返回空表一致）。 */
    @Override
    protected List<SlimefunItemStack> preloadItems(String key, ConfigurationSection sec) {
        return List.of();
    }

    @Override
    protected MachineMenu readEach(String key, ConfigurationSection sec) {
        // ① ID 冲突检查 —— 注意用的是原文 key（不规范化），与 RSC 一致
        if (Menus.contains(key)) {
            GS.error(null, "%s：ID 冲突：已存在菜单 ID 为 %s 的菜单", where(key), key);
            return null;
        }

        String title = sec.getString("title");  // 可为 null
        boolean playerInvClickable = sec.getBoolean("playerInvClickable", true);
        int size = sec.getInt("size", MachineMenu.NOT_SET);

        if (sec.contains("size") && size != MachineMenu.NOT_SET && size % 9 != 0) {
            GS.error(null, "%s：菜单大小必须是 9 的倍数 (size): %d", where(key), size);
            return null;
        }

        // ★ 注意运算符优先级：这是 RSC 原文的写法
        if ((size != MachineMenu.NOT_SET && size < 9) || size > 54) {
            GS.error(null, "%s：菜单大小超出范围 (size): %d（允许 9..54）", where(key), size);
            return null;
        }

        MachineMenu menu;
        if (sec.contains("import")) {
            menu = readImport(key, sec, title);
        } else if (sec.contains("matrix")) {
            menu = readMatrix(key, sec, title, playerInvClickable);
        } else {
            menu = readSlots(key, sec, title, playerInvClickable, size);
        }

        if (menu != null) {
            Menus.register(menu);
        }
        return menu;
    }

    // ------------------------------------------------------------------ import

    private MachineMenu readImport(String key, ConfigurationSection sec, String title) {
        String menuId = sec.getString("import", "");

        BlockMenuPreset preset = Slimefun.getRegistry().getMenuPresets().get(menuId);
        if (preset != null) {
            return MachineMenu.fromPreset(key, title, preset);
        }

        MachineMenu other = Menus.find(menuId);
        if (other == null) {
            GS.error(null, "%s：无法找到要导入的菜单 (import): %s", where(key), menuId);
            return null;
        }
        return MachineMenu.copyOf(key, title, other);
    }

    // ------------------------------------------------------------------ matrix

    /**
     * {@code matrix} 路径 —— 等价于 RSC 的 {@code readMatrix}。
     *
     * <p>★ <b>RSC 在这条路径上不调用 {@code setSize}，所以 size 保持构造器默认值 0</b>，
     * 随后 {@code apply} 会执行 {@code preset.setSize(0)}。这是 RSC 的一个 bug，
     * 但本附属 {@code menus.yml} 一次都没用 {@code matrix}，所以不可达；
     * 原生版<b>原样保留</b>（若要"修"，会让将来用 matrix 的配置与 RSC 表现不一致）。
     */
    private MachineMenu readMatrix(String key, ConfigurationSection sec, String title, boolean playerInvClickable) {
        int progress = MachineMenu.DEFAULT_PROGRESS_SLOT;
        ItemStack progressItem = createDefaultProgressItem();

        List<String> matrix = sec.getStringList("matrix");
        Map<Integer, ItemStack> slotMap = new HashMap<>();
        Map<Character, ItemStack> mapping = new HashMap<>();
        int slot = 0;

        for (String line : matrix) {
            for (char c : line.toCharArray()) {
                if (!mapping.containsKey(c)) {
                    ConfigurationSection k = sec.getConfigurationSection("mapping." + c);
                    if (k == null) {
                        slot += 1;
                        continue;
                    }
                    ItemStack read = Items.read(where(key) + ":mapping." + c, k);
                    mapping.put(c, read);
                    if (k.getBoolean("progressbar", false)) {
                        progress = slot;
                        progressItem = k.contains("progressBarItem")
                                ? Items.read(where(key) + ":mapping." + c + ":progressBarItem",
                                k.getConfigurationSection("progressBarItem"))
                                : mapping.get(c);
                    }
                }
                slotMap.put(slot, mapping.get(c));
                slot += 1;
            }
        }

        if (progressItem == null) {
            progressItem = createDefaultProgressItem();
        }
        return MachineMenu.ofMatrix(key, title, slotMap, playerInvClickable, progress, progressItem);
    }

    // ------------------------------------------------------------------ slots

    /**
     * {@code slots} 路径 —— 等价于 RSC 的 {@code MenuReader.readEach} 主循环。
     *
     * <p>槽位 key 有两种写法，靠 {@code Integer.parseInt} 是否抛异常来分流：
     * <ul>
     *   <li><b>单个数字</b>（如 {@code "10"}）：支持 {@code progressbar} / {@code progressBarItem}</li>
     *   <li><b>区间</b>（如 {@code "9-53"}）：<b>不支持</b> {@code progressbar}（RSC 原文就没处理），
     *       且区间内所有槽位共享<b>同一个</b> {@link ItemStack} 实例</li>
     * </ul>
     */
    private MachineMenu readSlots(String key, ConfigurationSection sec, String title,
                                 boolean playerInvClickable, int size) {
        int progress = MachineMenu.DEFAULT_PROGRESS_SLOT;
        ItemStack progressItem = createDefaultProgressItem();

        Map<Integer, ItemStack> slotMap = new HashMap<>();
        ConfigurationSection slots = sec.getConfigurationSection("slots");
        if (slots == null) {
            GS.error(null, "%s：没有预设物品 (slots)", where(key));
            return null;
        }

        for (String slot : slots.getKeys(false)) {
            ConfigurationSection item = slots.getConfigurationSection(slot);
            try {
                int realSlot = Integer.parseInt(slot);
                if (realSlot > 53 || realSlot < 0) {
                    GS.warn("%s：槽位超出范围: %s 已跳过", where(key), slot);
                    continue;
                }
                ItemStack itemStack = Items.read(where(key) + ":slots." + slot, item);
                if (itemStack == null) {
                    GS.warn("%s：槽位对应的物品无效 (slots): %s 已跳过", where(key), slot);
                    continue;
                }
                if (item != null && item.getBoolean("progressbar", false)) {
                    progress = realSlot;
                    if (item.contains("progressBarItem")) {
                        progressItem = Items.read(where(key) + ":slots." + slot + ":progressBarItem",
                                item.getConfigurationSection("progressBarItem"));
                    } else {
                        progressItem = itemStack;
                    }
                    // ★ RSC 会在这里给 progressItem 写一个 PDC 标记；该标记全仓库无人读取，
                    //   属于死代码，原生版省略（见类注释）。若 progressBarItem 无效，
                    //   RSC 会在下一行 NPE —— 原生版只是把 progressItem 置空后走默认值。
                }
                slotMap.put(realSlot, itemStack);
            } catch (NumberFormatException e) {
                String[] range = slot.split("-");
                if (range.length != 2) {
                    GS.error(null, "%s：槽位区间表达式非法 (slots): %s", where(key), slot);
                    continue;
                }
                ItemStack stack = Items.read(where(key) + ":slots." + slot, item);
                if (stack == null) {
                    GS.warn("%s：槽位对应的物品无效 (slots): %s", where(key), slot);
                    continue;
                }
                try {
                    int from = Integer.parseInt(range[0]);
                    int to = Integer.parseInt(range[1]);
                    IntStream.rangeClosed(from, to).forEach(i -> {
                        if (i < 0 || i > 53) {
                            GS.warn("%s：槽位超出范围: %s", where(key), slot);
                            return;
                        }
                        slotMap.put(i, stack);
                    });
                } catch (NumberFormatException e2) {
                    GS.error(null, "%s：槽位区间表达式非法 (slots): %s", where(key), slot);
                    return null;
                }
            }
        }

        if (progressItem == null) {
            progressItem = createDefaultProgressItem();
        }
        return MachineMenu.ofSlots(key, title, slotMap, playerInvClickable, progress, progressItem, size);
    }

    /** RSC 的 {@code createDefaultProgressItem}：一块黑玻璃板。 */
    private static ItemStack createDefaultProgressItem() {
        return new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
    }
}
