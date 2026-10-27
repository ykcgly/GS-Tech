package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode;
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ClickAction;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * {@code gs_guide_book.js} 的 Java 化版本 —— 「构思技艺图鉴」（攻略书）。
 *
 * <p>由 {@code groups.yml} 的 {@code actions: ["script gs_guide_book"]} 引用
 * （「信息」分类里的图鉴按钮）。点一下直接给玩家一本<b>已经写好内容</b>的成书。
 *
 * <p><b>★ 为什么书是当场构造的原版成书</b>：RSC 的物品读取器不处理 {@code BookMeta}
 * （{@code items.yml} 里没有 {@code pages} / {@code title} / {@code author} 字段），
 * 所以这本书由脚本自己造 {@code WRITTEN_BOOK} + {@code BookMeta}，直接塞进背包。
 *
 * <p>★★ <b>19 页文案由 {@code tools} 从原 JS 机械提取生成，不是手抄的</b>
 * —— 页面文字是<b>玩家可见内容</b>，一个字都不能错。提取脚本按 JS 字符串字面量
 * 规则反转义（{@code \n} 等），再转成 Java 字面量。页数与原脚本日志
 * 「{@code gs_guide_book 已加载（19 页）}」一致。
 *
 * <p>★ 原脚本里那几处 {@code if (GG_Material == null || GG_ItemStack == null) return;}
 * 与 {@code if (GG_ArrayList != null)} 是防御 {@code Java.type} 取类失败；
 * 在 Java 里类符号是编译期常量、必然可用，因此这些守卫<b>天然成立</b>，
 * 无需保留（保留反而是死代码）。
 *
 * <p>★ 每一层 {@code setXxx} 在原脚本里都单独包了 {@code try/catch} 并静默
 * （书页 API 在 1.20.5 前后改过 String ↔ Component，取不到就走降级）。
 * 这里逐层保留 —— 降级行为（书能拿到但没自定义标题）必须与现网一致。
 */
public final class GuideBookModule extends AbstractModule {

    private static final String GG_TITLE = "构思技艺图鉴";
    private static final String GG_AUTHOR = "QingHuaCiSJ";

    /** 19 页，逐字来自原脚本（见类注释）。 */
    private static final String[] GG_PAGES = {
            "祖宗发射器·维什戴尔\n左键开火，射程24，射速0.8，爆炸伤害35，爆炸半径2.5\n爆裂黎明，右键开启，期间可以随时关闭，冷却12秒，含6发弹药，射程24，射速1.5，爆炸伤害175，爆炸半径4.5",
            "释梦：\n右键可以发射一道梦魇，命中生物后，在生物头顶出现噩梦，砸中生物后造成40点伤害",
            "梦境套\n四件齐全才触发套装效果：\n飞行（入梦）\n45秒冷却的免死（触发后回血到12点）\n生命恢复2、夜视、迅捷2、饱和3、抗性提升4、急迫3\n免疫辐射（携带辐射物品不会中debuff）\n摘下任意一件，以上效果立刻消失\n单件装备没有任何效果",
            "\"假为思\"\n钢铁侠中的马克42号浪子同款使用方式（也不算同款），\n使用后穿戴上全套梦境套\n穿戴过程中漂浮9秒，之后缓降2秒",
            "余念绝响\n弹容量为37，左键发射弹药，伤害为37点每发，射程25格，射速极快，F键进行换弹（1.5秒）\n右键开启或关闭专注模式，切换手持会解除\n开镜后伤害提升至77点每发，射程提升至77格",
            "残樱\n弹容量为12，左键开火，伤害为8点每发，单发射程16格，单发射速0.2秒\n右键切换发射方式：单发与三连发，切换冷却1秒\n三连发为三发同时出膛，射程6格，每轮0.3秒，不随距离散布\nF键进行换弹（1.8秒）",
            "麦友的酒\n昨日一去不复回~哦耶耶\n如听仙乐耳暂名\n仙音~好听\n这你叮我\n叮叮叮\n右键召唤一只河豚，原地转8圈、持续8秒，冷却8秒",
            "枯骸骑将\n召唤枯骸将军与坐骑璇骊，枯骸将军800血、普攻20点，璇骊450血，血条仅召唤者可见\n技能为斩击（伤害7、冷却3.5秒、射程10格），冲锋（伤害8、冷却30秒、冲7格），唤骨（伤害20并附带凋零II 5秒、冷却4秒），伺骨/残驹共用50血量（一次召唤3只、存在30秒）。",
            "妄念（阶段boss）\n阶段一300血：幻念僧\n技能：追踪尖牙，移形换影\n阶段二800血：碎念执\n技能：跃斧劈，怨飞斧\n阶段三1000血：妄念魔\n技能：仇恨重压\n妄念会掉落妄想碎片，是合成余念绝响的关键道具\n妄念是存在人心中的贪嗔痴，会不定时的将附近的人拉向妄念周围（64格内、冷却18秒）",
            "威龙\n搬用了三角洲行动威龙的技能\n左键投出磁吸炸弹（前方7格、伤害35、半径3格、冷却15秒）\n右键投出虎蹲炮（前方12格、伤害1并击退定身4.5秒、半径3格、冷却25秒）\nF键动力推进（水平冲刺12格、冷却8秒，击杀生物立刻清空冷却）",
            "五骊矟\n手持时按F键，召唤坐骑，抓起五骊鞚\n当主手：五骊矟，副手：五骊鞚时可以使用左右键\n左键：斩击，冷却0.5秒，伤害7，射程10格，附带一点点击退\n右键：冲锋，冷却10秒，蓄力1秒（期间无敌）后向前冲刺15格，路径伤害15\n冲锋撞到1格高的障碍会上台阶，撞到2格以上则停在墙前\n冲刺期间会自动斩出4道剑气，每道伤害7、无击退",
            "月镰\n左键挥砍前方3*3的区域，伤害11点，命中敌人后叠加“梦”\n“梦”最高12层，7秒内命中+1并重新计时\n当“梦”叠满后，右键释放“噩梦”，半径5格内造成35点伤害并定身3秒",
            "蚀月·死神梦镰\n左键投出蚀月轮，首次命中敌人时停下并控制敌人0.6秒，落地后存在1.5秒，每0.5秒造成14点伤害，命中敌人后叠加“血”\n“血”最高23层，当“血”叠满后，右键释放“审判”，蓄力1.8秒后造成大范围定身并全体巨量伤害（半径8格、233点）",
            "&8䷍Ꝑ8kꙭ#䷎\n根本没有这样的boss\n你只是怕了\n技能：烟花火箭+重锤猛击，投掷三叉戟+闪电苦力怕，龙息弹+凋零首，音波轰鸣",
            "致万千无言者\n左键挥砍前方3*3的区域，伤害7点，命中敌人后叠加“言”\n“言”最高7层，3秒内命中+1、3秒未命中-1\n伤害随连击提高：1层7点，每层+1，满7层13点\n右键释放“逝言”，蓄力0.6秒后挥出灰色弧形斩击\n最远7格、宽3格，最多穿透4个生物，每个造成11点伤害",
            "此刻·万籁俱寂\n左键斩击准星方向3*5*2的区域，伤害17点，命中敌人后叠加“寂”\n“寂”最高99层，7秒内命中+1、7秒未命中直接清0\n伤害随连击提高：初始17点，每层+2，满99层213点\n右键释放“寂灭”，对自身7格半径圆圈内的敌人直接造成当前伤害×2，冷却15秒",
            "阎魔刀·改\n参考鬼泣5维吉尔的阎魔刀所做\n左键居合斩：六段斩击，斩完六段或静待三秒才能再次从第一段开始，第六段斩击有范围高伤并释放次元斩\n右键锚点：在当前位置留下锚点\nshift+右键：撕开裂缝，走进去后传送到锚点位置\n手持就有飞行，抗性4，生命恢复3，速度2，夜视",
            "暗影虫卵（阶段boss）\n阶段一150血：暗影巢虫\n技能：撕咬附带凋零II 5秒\n阶段二650血：暗影猎手\n技能：追踪龙息弹（每8秒、40点伤害），瞬移玩家到身前（每11秒）\n阶段三1000血：暗影女皇\n技能：弹道箭矢（每10秒、20点伤害，落地生成暗影蠕虫），暗影蛆虫（每15秒召唤4只、伤害8），瞬移玩家到身前（每7秒）",
            "守夜人的提灯（阶段boss）\n阶段一450血：巡夜守卫\n技能：弹道箭矢（每7秒，命中后中毒II、缓慢III、失明各3秒），粒子飞天（每9秒，约0.5秒飞20格，随后直接出现在玩家背后）\n阶段二900血：守夜人\n技能：瞬移玩家到身前并控制视角1秒（每8秒、20点伤害），召唤7只守夜蝇（每15秒，70血、伤害10），夜深了（每90秒、996点伤害）",
    };

    public GuideBookModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_guide_book 已加载（" + GG_PAGES.length + " 页）");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_guide_book";
    }

    /** 页数（自检用）。 */
    public static int pageCount() {
        return GG_PAGES.length;
    }

    /** 某一页的原文（自检用，越界返回 null）。 */
    public static String page(int index) {
        return index >= 0 && index < GG_PAGES.length ? GG_PAGES[index] : null;
    }

    @Override
    protected Object onButtonGroupClick(Player p, int slot, ItemStack clickedItem,
                                        ClickAction clickAction, SlimefunGuideMode mode) {
        try {
            if (p == null) {
                return null;
            }

            // ---------- 构造一本写好内容的成书 ----------
            ItemStack st = new ItemStack(Material.WRITTEN_BOOK, 1);
            BookMeta meta;
            try {
                meta = (BookMeta) st.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            if (meta == null) {
                return null;
            }

            try {
                meta.setTitle(GG_TITLE);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默降级
            }
            try {
                meta.setAuthor(GG_AUTHOR);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默降级
            }

            try {
                meta.setPages(new ArrayList<>(Arrays.asList(GG_PAGES)));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默降级（书页 API 版本差异）
            }

            try {
                st.setItemMeta(meta);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默降级
            }

            // ---------- 发放 ----------
            p.getInventory().addItem(st);

            // ★ 第十批：按需求删除「你收下了 构思技艺图鉴，右键即可翻阅」这句聊天提示
            try {
                p.playSound(p.getLocation(), "item.book.page_turn", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // ★ 外层是「发一条红字 + 记一条 warning」，与原脚本逐字一致
            try {
                p.sendMessage(Chat.t("&c图鉴发放出错: " + err));
            } catch (Throwable ignored) {
                // 连发消息都失败就只能放弃（与原脚本一致）
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 图鉴 onButtonGroupClick 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
