package com.qinghuacisj.gs.machine;

import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu;
import me.mrCookieSlime.Slimefun.Objects.SlimefunItem.abstractItems.AContainer;
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenuPreset;
import net.kyori.adventure.text.Component;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 菜单数据 —— 等价于 RSC 的 {@code customs\menu\CustomMenu}。
 *
 * <p>它<b>不是</b> {@code BlockMenuPreset}，只是一份「槽位 → 物品 + 点击处理器」的数据表。
 * 真正的 preset 由 {@link MachineTicker#init()} 用匿名 {@code BlockMenuPreset} 现造，
 * 再把 {@link #apply(ChestMenu)} 喂进去 —— 这正是 RSC 的结构：
 * 机器类把 {@code createPreset} 覆写成<b>空实现</b>，免得 {@code AContainer} 构造器抢先造一个默认菜单。
 *
 * <p>★★ <b>点击语义（本类最容易搞反的地方）</b>：RSC 的 {@code RSCClickHandler.onClick}
 * <b>恒返回 false</b>，而核心 {@code MenuListener} 做的是
 * {@code event.setCancelled(!handler.onClick(...))} —— 返回 false ⇒ 取消事件 ⇒ <b>物品拿不走</b>。
 * 由此推出两条<b>方向相反</b>的规则：
 * <ul>
 *   <li><b>{@code slots:} / {@code matrix:} 路径</b>：构造器给<b>每一个</b>声明的槽位都挂上了
 *       {@code RSCClickHandler}（内容只是「有脚本就调 onClick」），所以这些槽位
 *       <b>全部拿不走</b>。本附属 7 个菜单全走这条路（{@code menus.yml} 里没有 {@code import}），
 *       也就是说菜单里那 58 个装饰玻璃板<b>都拿不走</b>。</li>
 *   <li><b>{@code import} 路径</b>：从既有 preset 克隆时，只有<b>原本就有处理器</b>的槽位才被包一层
 *       （同样返回 false）；<b>没有</b>处理器的槽位在 {@link #getClickHandler} 里落到缺省
 *       {@code (player, i, itemStack, clickAction) -> true} ⇒ <b>拿得走</b>。</li>
 * </ul>
 *
 * <p>★ 另一条逐字保留的行为：{@code slots:} 路径会给没有显示名的物品强行写上单个空格
 * （{@code Component.text(" ")}），而 {@code import} 路径不写。
 *
 * <p>★ 原生版<b>有意省略</b>的两处 RSC 代码（都不影响线上表现，且避免搬死代码）：
 * <ol>
 *   <li>{@code MenuReader} 在 {@code progressbar} 物品上写了一个
 *       {@code gs:progress} 的 PDC 整数 0。全仓库 grep 只有<b>写入</b>、没有任何读取，
 *       是一段死代码，所以不搬。</li>
 *   <li>{@code cloneFromPresetInventory} 里 {@code Bukkit.createInventory(...)} 造出来的
 *       {@code Inventory} 局部变量从未被使用，且当 {@code title} 为 null 时会 NPE。
 *       本附属没有 {@code import} 菜单，这段不可达；直接删掉。</li>
 * </ol>
 */
public final class MachineMenu {

    /** 未设置 size 的哨兵值（RSC 的 {@code MenuReader.NOT_SET}）。 */
    public static final int NOT_SET = -1;

    /** 默认进度条槽位（{@code MenuReader} 里写死的 22）。 */
    public static final int DEFAULT_PROGRESS_SLOT = 22;

    private final String id;
    private final String title;
    private final Map<Integer, ItemStack> items;
    private final Map<Integer, ChestMenu.MenuClickHandler> clickHandlers;

    private int progressSlot;
    private int size;
    private ItemStack progressBar;
    private boolean playerInvClickable;

    private ChestMenu.MenuOpeningHandler menuOpeningHandler = p -> {
    };
    private ChestMenu.MenuCloseHandler menuCloseHandler = p -> {
    };

    private MachineMenu(String id, String title, Map<Integer, ItemStack> items,
                        boolean playerInvClickable, int progressSlot, ItemStack progressBar, int size) {
        this.id = id;
        this.title = title != null ? Chat.t(title) : null;
        this.items = items;
        this.clickHandlers = new HashMap<>();
        this.playerInvClickable = playerInvClickable;
        this.progressSlot = progressSlot;
        this.progressBar = progressBar != null ? progressBar.clone() : items.get(progressSlot);
        this.size = size;
    }

    // ------------------------------------------------------------------ 三条构造路径

    /**
     * {@code slots:} / {@code matrix:} 路径 —— 逐字等价于 RSC 的 7 参构造器。
     *
     * <p>★ 最后那个 {@code for (int i = 0; i < 54; i++)} 是<b>必须保留</b>的：
     * 它给每个有物品的槽位补上「空格显示名 + 恒 false 的点击处理器」。
     * 只遍历 {@code items} 的 key 集合在语义上等价（不存在的槽位取不到物品），
     * 但这里仍然照抄 0..53 的写法，方便与 RSC 逐行对照。
     */
    public static MachineMenu ofSlots(String id, String title, Map<Integer, ItemStack> items,
                                      boolean playerInvClickable, int progressSlot,
                                      ItemStack progressBar, int size) {
        MachineMenu menu = new MachineMenu(id, title, items, playerInvClickable, progressSlot, progressBar, size);

        for (int i = 0; i < 54; i++) {
            ItemStack item = items.get(i);
            if (item != null) {
                // ★ 无脚本 ⇒ 处理器只做「什么都不做」，但它仍然让槽位不可拿走
                menu.addItem(i, item, (player, slot, itemStack, action) -> false);
            }
        }
        return menu;
    }

    /**
     * {@code matrix:} 路径 —— 等价于 RSC 的 {@code MenuReader.readMatrix}。
     *
     * <p>★ 与 {@code slots:} 路径的唯一区别（RSC 原样）：<b>不调用 {@code setSize}</b>，
     * 所以 {@code size} 保持 Java 字段默认值 {@code 0}；随后 {@code apply} 会执行
     * {@code preset.setSize(0)}。这是 RSC 的一个 bug，但本附属 {@code menus.yml}
     * 一次都没用 {@code matrix}，所以不可达 —— 原样保留以免将来行为漂移。
     */
    public static MachineMenu ofMatrix(String id, String title, Map<Integer, ItemStack> items,
                                       boolean playerInvClickable, int progressSlot, ItemStack progressBar) {
        return ofSlots(id, title, items, playerInvClickable, progressSlot, progressBar, 0);
    }

    /**
     * {@code import: <既有菜单 id>} 路径（另一份已加载菜单）—— 等价于
     * RSC 的 {@code CustomMenu(String, String, CustomMenu)} 拷贝构造器。
     *
     * <p>★ 它<b>共享</b> {@code items} 与 {@code clickHandlers} 两张表（不是深拷贝），
     * 并且<b>继承被导入菜单的 progressSlot / size</b>，只有 title 被替换。
     */
    public static MachineMenu copyOf(String id, String title, MachineMenu source) {
        MachineMenu menu = new MachineMenu(id, title, source.items,
                source.playerInvClickable, source.progressSlot, source.progressBar, source.size);
        menu.clickHandlers.putAll(source.clickHandlers);
        menu.menuOpeningHandler = source.menuOpeningHandler;
        menu.menuCloseHandler = source.menuCloseHandler;
        return menu;
    }

    /**
     * {@code import: <BlockMenuPreset id>} 路径 —— 等价于 RSC 的
     * {@code CustomMenu(String, String, BlockMenuPreset, ScriptEval)} + {@code cloneFromPresetInventory}。
     *
     * <p>进度槽位与进度条的来源（逐字照抄）：
     * <ul>
     *   <li>该 preset 对应的 {@code SlimefunItem} 是 {@code MachineTicker}（RSC 的
     *       {@code CustomMenuHolder}）⇒ 用<b>它自己的菜单</b>的 progressSlot（没有就 22）+ 它的进度条</li>
     *   <li>是 {@code AContainer} ⇒ 固定 22 + 容器的进度条</li>
     *   <li>都不是 ⇒ 保持 {@code -1}（= 后续按默认槽位处理）</li>
     * </ul>
     */
    public static MachineMenu fromPreset(String id, String title, BlockMenuPreset preset) {
        MachineMenu menu = new MachineMenu(id, title, new LinkedHashMap<>(),
                preset == null || preset.isPlayerInventoryClickable(), -1, null, NOT_SET);

        if (preset == null) {
            return menu;
        }

        // ★ 必须先把 contents 取一次：getContents() 内部会 setup() 出 inventory，
        //   后面 getInventory() 才不会 NPE
        preset.getContents();

        for (int i = 0; i < preset.getInventory().getSize(); i++) {
            ItemStack item = preset.getItemInSlot(i);
            if (item != null) {
                // ★ 2 参 addItem：不补空格显示名
                menu.items.put(i, item.clone());
            }

            ChestMenu.MenuClickHandler handler = preset.getMenuClickHandler(i);
            if (handler != null) {
                menu.addMenuClickHandler(i, (player, slot, itemStack, action) -> {
                    handler.onClick(player, slot, itemStack, action);
                    // ★ RSCClickHandler 恒返回 false ⇒ 这些槽位拿不走
                    return false;
                });
            }
        }

        SlimefunItem item = Slimefun.getRegistry().getSlimefunItemIds().get(preset.getID());
        if (item instanceof AbstractMachine machine && machine.getTicker() != null) {
            MachineMenu inner = machine.getTicker().getMenu();
            menu.progressSlot = inner != null ? inner.getProgressSlot() : DEFAULT_PROGRESS_SLOT;
            menu.progressBar = machine.getTicker().getProgressBar();
        } else if (item instanceof AContainer container) {
            menu.progressSlot = DEFAULT_PROGRESS_SLOT;
            menu.progressBar = container.getProgressBar();
        }

        menu.size = preset.getSize();
        return menu;
    }

    // ------------------------------------------------------------------ 组装

    /** 把本菜单应用到实际 preset 上（等价于 RSC 的 {@code CustomMenu.apply}）。 */
    public void apply(ChestMenu preset) {
        preset.setPlayerInventoryClickable(playerInvClickable);

        if (size != NOT_SET) {
            preset.setSize(size);
        }

        for (int slot : items.keySet()) {
            preset.addItem(slot, items.get(slot), getClickHandler(slot));
        }

        preset.addMenuOpeningHandler(menuOpeningHandler);
        preset.addMenuCloseHandler(menuCloseHandler);
    }

    /**
     * ★ 缺省处理器返回 true ⇒ 该槽位可以被玩家拿走。
     * 只有 {@code import} 路径里「原本就没有处理器」的槽位会落到这里。
     */
    private ChestMenu.MenuClickHandler getClickHandler(int slot) {
        return clickHandlers.getOrDefault(slot, (player, i, itemStack, clickAction) -> true);
    }

    /**
     * 等价于 RSC 的 {@code CustomMenu.addItem(int, ItemStack, MenuClickHandler)}。
     *
     * <p>★ 没有显示名的物品会被强行改成单个空格 —— 这是 RSC 为了不让装饰物显示原版材质名做的处理。
     */
    public void addItem(int i, ItemStack item, ChestMenu.MenuClickHandler onClick) {
        if (item.getItemMeta() != null && !item.getItemMeta().hasDisplayName()) {
            item.editMeta(m -> m.displayName(Component.text(" ")));
        }
        items.put(i, item);
        clickHandlers.put(i, onClick);
    }

    public void addMenuClickHandler(int i, ChestMenu.MenuClickHandler onClick) {
        clickHandlers.put(i, onClick);
    }

    // ------------------------------------------------------------------ 查询

    public String getId() {
        return id;
    }

    /** 已翻译色码的标题；未设置时为 null。 */
    public String getTitle() {
        return title;
    }

    public Map<Integer, ItemStack> getItems() {
        return items;
    }

    public int getProgressSlot() {
        return progressSlot;
    }

    public ItemStack getProgressBar() {
        return progressBar;
    }

    public int getSize() {
        return size;
    }

    public boolean isPlayerInvClickable() {
        return playerInvClickable;
    }

    public ChestMenu.MenuClickHandler getMenuClickHandler(int slot) {
        return clickHandlers.get(slot);
    }

    /** 玩家侧标题（未设置时回落到机器名）。 */
    public String titleOrDefault(String fallback) {
        return title == null || title.isBlank() ? fallback : title;
    }

    /** 调试用：把缺省进度槽位记进日志的辅助（不参与逻辑）。 */
    public int progressSlotOrDefault() {
        return progressSlot == NOT_SET ? DEFAULT_PROGRESS_SLOT : progressSlot;
    }
}
