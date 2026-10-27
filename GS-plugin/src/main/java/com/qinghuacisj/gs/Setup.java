package com.qinghuacisj.gs;

import com.qinghuacisj.gs.content.Content;
import com.qinghuacisj.gs.content.GroupLoader;
import com.qinghuacisj.gs.content.ItemLoader;
import com.qinghuacisj.gs.content.MaterialGeneratorLoader;
import com.qinghuacisj.gs.content.MenuLoader;
import com.qinghuacisj.gs.content.MultiBlockLoader;
import com.qinghuacisj.gs.content.RecipeMachineLoader;
import com.qinghuacisj.gs.content.RecipeTypeLoader;
import com.qinghuacisj.gs.script.Modules;
import com.qinghuacisj.gs.script.Script;
import com.qinghuacisj.gs.script.ScriptListener;

/**
 * 加载编排 —— 按类别读取 {@code content/*.yml} 并注册到 Slimefun。
 *
 * <p>顺序照抄 RSC 的 {@code ProjectAddonLoader.load()}，<b>不能改</b>：
 * <ol>
 *   <li><b>T0</b>　信息 → 监听脚本（{@code scriptListener}）→ 附属配置</li>
 *   <li><b>T0</b>　物品组 → 配方类型 → <b>菜单</b>（同步，必须先于机器与物品）</li>
 *   <li><b>T1</b>　预加载：物品与机器的 {@code item:} 块先登记成物品栈</li>
 *   <li><b>T2</b>　注册：物品 → 配方机器 / 材料发生器 / 多方块机器</li>
 *   <li><b>T3</b>　延迟初始化 + 把物品挂进物品组</li>
 * </ol>
 *
 * <p>为什么物品组必须先于物品：{@code items.yml} 里 {@code item_group: gs_basic}
 * 必须能解析到本附属刚建的组。为什么预加载必须先于注册：本附属 52 件物品的配方
 * 互相引用，没有预加载表会大面积丢物品 —— 机器的合成表也一样（它们的输入/产物
 * 全是本附属自己的物品）。
 *
 * <p>★★ 为什么<b>菜单必须在机器之前</b>：{@code BlockMenuPreset} 的构造器会执行
 * {@code Slimefun.getRegistry().getMenuPresets().put(id, this)}，也就是按 id
 * <b>覆盖</b>同名 preset；而机器在注册时会按 id 从菜单表里取菜单。
 * 顺序反了就会出现「菜单装饰全部消失、只剩默认边框」。
 */
public final class Setup {

    private Setup() {
    }

    /** 本次加载的统计（日志用）。 */
    public static final class Stats {
        public int groups, recipeTypes, menus, preloaded, items, machines, generators, multiBlocks;
        /** 上次完整加载耗时（毫秒）。 */
        public long elapsedMs;
        /** 上次完整加载是否成功。 */
        public boolean ok;
    }

    /**
     * 上一次完整加载的统计 —— {@code /gs info} 用。
     *
     * <p>★ 只读快照。加载只发生在 {@code onEnable}（以及显式的 {@code /gs reload}），
     * 都在主线程上，所以这里不需要加锁。
     */
    private static Stats last;

    /** 上一次完整加载的统计；从未加载过时为 null。 */
    public static Stats lastStats() {
        return last;
    }

    /** 把插件自己的 content 解包到磁盘，然后完整加载。 */
    public static void loadAll() throws Exception {
        long t0 = System.currentTimeMillis();
        Stats s = new Stats();
        last = s;
        s.ok = false;

        // ---------- T0 前置：解包 content 与 lib（只补不缺，绝不覆盖用户改动）----------
        int unpacked = Content.bootstrap();
        if (unpacked > 0) {
            GS.log("首次运行：已从 jar 释放 %d 个文件到 %s", unpacked, Content.root().getPath());
        }

        // ---------- T0：附属配置 ----------
        Content.config();

        // ---------- T0：监听脚本（必须先于物品：脚本顶层代码会注册自己的监听器）----------
        loadListenerScript();

        // ---------- T0：物品组 / 配方类型 / 菜单 ----------
        GroupLoader groups = new GroupLoader();
        s.groups = groups.readAll();

        RecipeTypeLoader recipeTypes = new RecipeTypeLoader();
        s.recipeTypes = recipeTypes.readAll();

        // ★★ 菜单必须在机器之前读完（G-22）：BlockMenuPreset 的构造器会按 id
        //    覆盖 Slimefun.getRegistry().getMenuPresets()，而机器注册时又会按 id 取菜单。
        //    顺序反了就会出现「菜单装饰全部消失、只剩默认边框」。
        MenuLoader menus = new MenuLoader();
        s.menus = menus.readAll();

        // ---------- T1：预加载物品栈 ----------
        // ★ 物品与机器一起预加载 —— 与 RSC 一致（它的 T1 把所有 Reader 的 preload 并行跑完
        //   才开始 T2）。机器配方里会引用本附属自己的物品，没有预加载表就会大面积丢配方。
        ItemLoader items = new ItemLoader();
        RecipeMachineLoader recipeMachines = new RecipeMachineLoader();
        MaterialGeneratorLoader generators = new MaterialGeneratorLoader();
        MultiBlockLoader multiBlocks = new MultiBlockLoader();

        s.preloaded = items.preload();
        recipeMachines.preload();
        generators.preload();
        multiBlocks.preload();

        // ---------- T2：注册物品 ----------
        // ★ 挂组是「边注册边挂」的：SlimefunItem.register() 内部会回调 load()，
        //   而 GsItem.load() 里就把自己挂进物品组了（见 GsItem 的说明）。
        //   RSC 那边绕了一层「先攒着、最后统一挂」，纯粹是因为它的 T2 跑在线程池上；
        //   原生版单线程顺序注册，不需要那一层。
        s.items = items.readAll();

        // ---------- T2：注册机器 ----------
        s.machines = recipeMachines.readAll();
        s.generators = generators.readAll();
        s.multiBlocks = multiBlocks.readAll();

        s.elapsedMs = System.currentTimeMillis() - t0;
        s.ok = true;

        GS.log("构思技艺 (GS) 加载完成  ·  用时 %d ms", s.elapsedMs);
        GS.log("  物品组 %d ｜ 配方类型 %d ｜ 菜单 %d ｜ 预加载 %d ｜ 物品 %d",
                s.groups, s.recipeTypes, s.menus, s.preloaded, s.items);
        GS.log("  配方机器 %d ｜ 材料发生器 %d ｜ 多方块机器 %d",
                s.machines, s.generators, s.multiBlocks);
        // 阶段②进度：已 Java 化的脚本数 / 实际被引用到的脚本数
        GS.log("  脚本已 Java 化 %d 个 ｜ 本次实际实例化 %d 个",
                Modules.convertedCount(), Modules.instantiatedCount());

        // ★★ 这里**故意不**预实例化「只被物品组动作引用」的脚本（gs_guide_book /
        //    gs_info_creator / gs_update_log）—— 曾经加过一版 Modules.preloadAll()，
        //    被 tools/log_parity.py 当场否掉，记录如下，别再犯：
        //      参考服日志里 gs_guide_book 那一行在**最后一行**（第 88 行），
        //      位置在「构思技艺 (GS) 加载成功！」（第 86 行）**之后**；
        //      而同为「只被动作引用」的 gs_update_log **一次都没出现**。
        //      ⇒ RSC 也是**按需求值**的，那一行是参考服那次会话里**有人点了「图鉴」按钮**
        //        才打出来的，不是加载期打的。预加载会凭空多出 `gs_update_log 已加载`。
        //    ⇒ 结论：保持惰性（与 RSC 一致）。玩家点按钮时照样会打那一行。
    }

    /** 读取 {@code info.yml} 的 {@code scriptListener} 并接线。 */
    private static void loadListenerScript() {
        String name = Content.yml("info.yml").getString("scriptListener", "");
        if (name == null || name.isBlank()) {
            GS.warn("info.yml 里没有 scriptListener，事件监听脚本不会加载");
            return;
        }
        Script script = Script.of(name);
        if (script == null) {
            // ★ 阶段②之后不再有 scripts/*.js：这里只可能是 info.yml 的
            //   scriptListener 写了个没有对应 Java 模块的名字。
            GS.warn("info.yml 的 scriptListener 写的是 %s，但没有对应的 Java 模块", name);
            return;
        }
        ScriptListener.register(script);
        // ★ 不再带 .js 后缀 —— 阶段②之后没有脚本文件了，这是纯 Java 模块名。
        //   （这行是 [GS] 前缀的框架行，不在 [构思技艺] 对账范围内。）
        GS.log("已注册监听脚本: %s", name);
    }
}
