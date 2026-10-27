package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code gs_listener.js} 的「副手那颗『篮球』<b>拿不下来</b>」子系统（钥匙球锁 · {@code GS_KL}）
 * （原脚本第 <b>13169~13563</b> 行，顶层入口 {@code gsKlStart()}）。
 *
 * <h2>为什么需要它</h2>
 * 运球 / 持球期间，副手那颗「篮球」只是<b>骗原版客户端发包</b>的钥匙
 * （原版客户端在「手上有物品」时才会对<b>空气</b>发右键包 ⇒ 有了它右键才通）。
 * 但它毕竟是一件真实物品 ⇒ 玩家能把它拖走 / 按 F 换到主手 / 丢出去。
 * 所以这里用 <b>6 条事件</b>把「副手槽上的一切操作」全部拦回，
 * 再加一条「每刻自愈」兜住漏网的（被别的插件拿走 / 脚本 reload 丢状态）。
 *
 * <h2>★★ 6/6 条事件（条数必须对上，日志里写着「6/6 条事件」）</h2>
 * <ol>
 *   <li>① {@code InventoryClickEvent} —— 点击副手槽（拖走 / 放下 / 快速移动 / 数字键 / 丢到窗口外）。
 *       ★ 创造模式物品栏的 {@code InventoryCreativeEvent} 是它的<b>子类</b>，注册父类照样收得到。</li>
 *   <li>② {@code PlayerSwapHandItemsEvent} —— F 键交换主副手。</li>
 *   <li>③ {@code InventoryDragEvent} —— 按住鼠标拖。</li>
 *   <li>④ {@code PlayerDropItemEvent} —— 丢弃（Q 键）。</li>
 *   <li>⑤ {@code PlayerDeathEvent} —— 死亡时剔掉掉落物里的假球 + 球留在死亡点。</li>
 *   <li>⑥ {@code PlayerQuitEvent} —— 退出时收走钥匙（不然重登后副手会挂着一颗幽灵按钮）。</li>
 * </ol>
 * <p>★ 探针会<b>反射数</b> {@link ListenerImpl} 上 {@code @EventHandler} 的个数，
 * 断言它<b>恰好等于 6</b>，并把 {@link #gsKlRegisterLine} 里的 {@code ok} 一起钉住。
 *
 * <h2>★ 副手槽判定位为什么用 {@code getSlot()} 而不是 {@code getRawSlot()}</h2>
 * {@code InventoryClickEvent#getSlot()} 返回的是
 * {@code view.convertSlot(rawSlot)}（字节码实证：字段 {@code whichSlot}），
 * 也就是「<b>相对所点击 inventory 的索引</b>」—— 在玩家自身视图里副手恒为 <b>40</b>。
 * 而 {@code getRawSlot() == 45} 只在「玩家自身视图」成立，箱子视图下会错位
 * （可能误拦箱子里别的槽）⇒ 所以只用 {@code getSlot()}。
 * <p>★ 还要再判一次 {@code getClickedInventory().getHolder()} 必须是<b>这个玩家本人</b>：
 * 在箱子 / 工作台界面里点箱子那一半 ⇒ holder 不是玩家 ⇒ 一概不管（不会误拦箱子槽）。
 *
 * <h2>★ 与「孤儿球清扫」（{@link EntPurgeSub}）的交互</h2>
 * {@link #gsKlKey} 认「自家假球」靠的是两套 PDC 键：
 * {@code gs:bb_ball}（律师函 / {@link BbSub}）与 {@code gs:bg_ball}（生成器 / {@link BgSub}）
 * ⇒ <b>真球（Slimefun 物品）一颗都不会被锁</b>。
 * {@link #gsKlTakeKey} / {@link #gsKlSweep} 同样只动 {@code gsKlKey} 认出来的东西，
 * <b>绝不碰玩家自己的物品</b>。
 *
 * <h2>★ 监听器注册：与原脚本的差异</h2>
 * 原脚本在 {@code gsKlRegisterSync()} 里用 {@code pm.registerEvent(...)} <b>自己注册</b>，
 * <b>每次 {@code /gs reload} 都会再注册一遍</b>（真实缺陷）。Java 侧改成
 * {@link ListenerImpl}（{@code org.bukkit.event.Listener}）+ {@code registerEvents}，
 * 用 {@link #GS_KL_REGISTERED} 闩锁保证<b>只注册一次</b>。
 * <p>★ <b>优先级与 ignoreCancelled 逐处核对</b>：原脚本 {@code registerEvent} 只传了
 * 4 个参数 ⇒ {@code ignoreCancelled} 取默认 <b>false</b>；Java 注解默认也是 false ⇒ 一致，
 * <b>不要</b>写成 true。优先级全部是 {@code Priority.HIGHEST}。
 * <p>★★ 注意 {@link #GS_KL_REGISTERED} 的语义与原脚本完全一致：<b>即使一条都没注册上</b>
 * （{@code ok == 0}）也照样置 true、照样打成功日志；而 {@link #gsKlStart} <b>不 return</b>
 * —— 原脚本注释写得很清楚：「监听器没注册上（<b>每刻自愈仍会跑</b>）」。
 *
 * <h2>★ 死代码清单（逐条，均已在 Java 里等价处理）</h2>
 * <ol>
 *   <li>{@code reg()} 里的 {@code JavaAdapter} 退路 —— GraalJS 25.1.3 没有
 *       {@code JavaAdapter} ⇒ 恒失败，死代码。★ 但 {@code ok} 的计数语义要保留：
 *       {@code pm.registerEvent} 抛了 ⇒ 该条不计入 {@code ok}。Java 侧
 *       {@code registerEvents} 是<b>一次调用注册全部 6 条</b> ⇒ 成功即 6、失败即 0。</li>
 *   <li>{@code gsKlHint} 的第二路（bungee {@code TextComponent.fromLegacyText}）——
 *       {@code Player#sendActionBar(String)} 在 1.21.11 必然可用（编译验证）⇒
 *       第一路必然 {@code return}，第二路走不到。★ 但保留它（3 行）作为降级路径，
 *       且它<b>确实能编译</b>（{@code bungeecord-chat} 在 libs 里）。</li>
 *   <li>{@code gsKlResync} 的 {@code p.updateInventory()} —— 新版 deprecated 但仍在
 *       （编译验证）⇒ 保留。</li>
 *   <li>{@code gsKlIsPlayer} 的鸭子类型判定 —— 原脚本注释说「GraalJS 下 instanceof 对宿主类
 *       不一定可靠，所以用能力判断」。Java 里 {@code instanceof Player} 就是那个判定的
 *       <b>精确等价物</b>（能力检查的三项 —— {@code getUniqueId()} / {@code getInventory()}
 *       / {@code getItemInOffHand()} —— 对任何 {@code Player} 都成立，对非 Player 都不成立）。</li>
 *   <li>{@code gsKlClickedOffSlot} 里 {@code '' + h.getUniqueId()} 的 {@code try/catch} ——
 *       JS 里 holder 不是 Player 时 {@code getUniqueId()} 不存在会抛；Java 用
 *       {@code h instanceof Player} 表达同一个判定。</li>
 * </ol>
 *
 * <h2>★★★ 时间戳陷阱（与 {@link BbSub} 同一类坑）</h2>
 * 原脚本有 2 处把毫秒时间戳先做 {@code | 0}（JS 的 {@code ToInt32}）再比较：
 * <pre>
 *   gsKlHint  : if ((now - (GS_KL_HINT_AT[u] | 0)) &lt; GS_KL_HINT_MS) return;  // 2.5 秒去抖
 *   gsKlPulse : if ((now - (GS_KL_SWEEP_AT  | 0)) &gt;= GS_KL_SWEEP_MS) { ... } // 每 0.5 秒清残留
 * </pre>
 * 两处的 {@code | 0} 都会把毫秒时间戳<b>回绕</b>成 ±2.1e9 量级 ⇒ {@code now - wrapped}
 * ≈ 1.76e12，<b>永远大于</b>阈值 ⇒ 在真 JS 里：
 * <ul>
 *   <li>「拿不下来」的 2.5 秒提示去抖<b>恒不生效</b> ⇒ 连点副手槽会每点都刷 actionbar；</li>
 *   <li>背包残留清扫<b>每刻都跑</b>（不是每 0.5 秒）。</li>
 * </ul>
 * Java 一律用 {@code (int)} 窄化逐位复刻 —— 改成 {@code long} 直接比较会凭空造出
 * 原脚本没有的去抖 / 周期，是<b>可观察差异</b>（探针 D 段有正反两向断言）。
 */
public final class KlSub {

    private final ListenerCore core;
    private final BbSub bb;
    private final BgSub bg;

    public KlSub(ListenerCore core, BbSub bb, BgSub bg) {
        this.core = core;
        this.bb = bb;
        this.bg = bg;
    }

    /** 对应原脚本顶层的 {@code gsKlStart();} 调用（原脚本第 13563 行）。 */
    public void start() {
        gsKlStart();
    }

    // ======================================================================
    // 顶层常量（原脚本 13173~13178 行）
    // ======================================================================

    /** {@code PlayerInventory} 索引：<b>40 = 副手槽</b>。 */
    static final int GS_KL_SLOT_OFF = 40;
    /** 「拿不下来」提示的去抖（毫秒）。★ 见类注释「时间戳陷阱」：真 JS 里恒不生效。 */
    static final long GS_KL_HINT_MS = 2500;
    /** 背包残留清扫周期（毫秒）。★ 见类注释「时间戳陷阱」：真 JS 里每刻都跑。 */
    static final long GS_KL_SWEEP_MS = 500;
    /** 监听器条数（日志里的「N/6 条事件」）。★ 探针会反射数 {@code @EventHandler} 个数钉住它。 */
    static final int KL_EVENT_COUNT = 6;

    /** 同步监听器是否注册成功（Java 侧是幂等闩锁）。 */
    boolean GS_KL_REGISTERED = false;
    /** uuid 字符串 → 上次提示的时间戳。 */
    final Map<String, Long> GS_KL_HINT_AT = new LinkedHashMap<>();
    /** 上次残留清扫的时间戳。 */
    long GS_KL_SWEEP_AT = 0;

    // ======================================================================
    // 判定（原脚本 13180~13226 行）
    // ======================================================================

    /** {@code gsKlUuid(p)} —— 玩家 uuid 字符串（拿不到返回 null）。 */
    static String gsKlUuid(Player p) {
        try {
            return (p == null) ? null : ListenerCore.uid(p);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsKlIsPlayer(o)} —— 是不是「玩家」。
     *
     * <p>★ 原脚本用<b>鸭子类型</b>（拿得到 UUID / 背包 / 副手槽的才算），注释理由是
     * 「GraalJS 下 {@code instanceof} 对宿主类不一定可靠」。Java 里
     * {@code instanceof Player} 就是那个判定的<b>精确等价物</b>。
     */
    static boolean gsKlIsPlayer(Object o) {
        try {
            if (o == null) {
                return false;
            }
            return o instanceof Player;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsKlKey(item)} —— 是不是我们发的那颗「钥匙球」。
     * ★ 律师函的金合欢木按钮（{@code gs:bb_ball}）/ 生成器的白桦木按钮（{@code gs:bg_ball}）。
     * ★★ <b>真球（Slimefun 物品）一颗都不会被认出来</b> ⇒ 不会被锁。
     */
    boolean gsKlKey(ItemStack item) {
        try {
            if (bb.gsBbIsBall(item)) {
                return true;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            if (bg.gsBgIsBall(item)) {
                return true;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /** {@code gsKlHasKey(p)} —— 玩家<b>现在</b>副手里是不是我们的钥匙。 */
    boolean gsKlHasKey(Player p) {
        try {
            if (p == null) {
                return false;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return false;
            }
            return gsKlKey(inv.getItemInOffHand());
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsKlNeed(p)} —— 这个玩家<b>现在应该</b>挂着钥匙球吗（= 正在运球：律师函那条 / 生成器那颗）。 */
    boolean gsKlNeed(Player p) {
        try {
            String u = gsKlUuid(p);
            if (u != null) {
                BbSub.Ball st = bb.GS_BB_ST.get(u);
                if (st != null && "bound".equals(st.mode)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            if (bg.gsBgBoundBallOf(p) != null) {
                return true;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    // ======================================================================
    // 收走 / 补回 / 提示 / 清扫（原脚本 13228~13308 行）
    // ======================================================================

    /**
     * {@code gsKlTakeKey(p)} —— 收走副手那颗钥匙（<b>只收我们自己发的</b>；玩家自己的东西绝不动）。
     *
     * @return 是否真的收走了
     */
    boolean gsKlTakeKey(Player p) {
        try {
            if (p == null) {
                return false;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return false;
            }
            if (!gsKlKey(inv.getItemInOffHand())) {
                return false;
            }
            try {
                inv.setItemInOffHand(null);
            } catch (Throwable e) {
                return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsKlGiveKey(p)} —— 补回钥匙：交给各模块自己的 {@code HandOn}
     * （它们自己会判「副手空着才放」⇒ 不会覆盖玩家物品）。
     *
     * @return 是否真的补上了
     */
    boolean gsKlGiveKey(Player p) {
        try {
            String u = gsKlUuid(p);
            if (u != null) {
                BbSub.Ball st = bb.GS_BB_ST.get(u);
                if (st != null && "bound".equals(st.mode)) {
                    bb.gsBbHandOn(p, st);
                    if (gsKlHasKey(p)) {
                        return true;
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        try {
            BbSub.Ball b = bg.gsBgBoundBallOf(p);
            if (b != null) {
                bg.gsBgHandOn(p, b);
                if (gsKlHasKey(p)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * {@code gsKlHint(p)} —— 提示（原脚本写「去抖 2.5 秒」；见类注释「时间戳陷阱」：
     * 那个 {@code | 0} 让去抖<b>恒不生效</b>）。
     *
     * <p>★ actionbar 比聊天栏不打扰。第一路 {@code Player#sendActionBar(String)}
     * （Spigot / Paper 都有，1.17+ 起稳定）；拿不到就退回第二路（bungee 组件）
     * ⇒ 两条都不行就<b>静默</b>（绝不影响拦截本身）。
     */
    void gsKlHint(Player p) {
        try {
            String u = gsKlUuid(p);
            if (u == null) {
                return;
            }
            long now = ListenerCore.now();
            Long lastObj = GS_KL_HINT_AT.get(u);
            // ★★★ 逐字复刻 JS 的 `GS_KL_HINT_AT[u] | 0`（undefined | 0 === 0 + ToInt32 回绕）
            int last = (lastObj == null) ? 0 : (int) (long) lastObj;
            if ((now - last) < GS_KL_HINT_MS) {
                return;
            }
            GS_KL_HINT_AT.put(u, now);
            String msg = "§6运球时篮球拿不下来";
            try {
                p.sendActionBar(msg);
                return;
            } catch (Throwable e) {
                // 落到第二路
            }
            try {
                p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                        net.md_5.bungee.api.chat.TextComponent.fromLegacyText(msg));
            } catch (Throwable e2) {
                // 两条都不行 ⇒ 静默
            }
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsKlSweep(p)} —— 扫一遍这个玩家的整个背包，把<b>不该在</b>那儿的钥匙清掉。
     * ★ 副手槽 + 正在运球 ⇒ 那颗是「正常」的，留着。
     */
    void gsKlSweep(Player p) {
        try {
            if (p == null) {
                return;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return;
            }
            boolean need = gsKlNeed(p);
            int n = 0;
            try {
                n = inv.getSize();
            } catch (Throwable e) {
                n = 36;
            }
            if (!(n > 0)) {
                n = 36;
            }
            for (int i = 0; i < n; i++) {
                ItemStack it = null;
                try {
                    it = inv.getItem(i);
                } catch (Throwable e) {
                    continue;
                }
                if (!gsKlKey(it)) {
                    continue;
                }
                if (i == GS_KL_SLOT_OFF && need) {
                    continue;                      // 副手槽 + 正在运球 ⇒ 这颗是「正常」的
                }
                try {
                    inv.setItem(i, null);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsKlResync(p)} —— 取消之后把背包<b>主动重发一次</b>。
     *
     * <p>原版对「被取消的点击」一般会自动重发，但不同服务端实现有差异
     * （Paper 上有时只剩客户端本地预测）⇒ 保险起见主动同步，
     * 免得玩家看到「光标上粘着一个其实不存在的按钮」（幽灵物品）。
     * ★ {@code Player#updateInventory()} 新版 deprecated 但仍在（编译验证）⇒ 拿不到就算了。
     */
    static void gsKlResync(Player p) {
        try {
            p.updateInventory();
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ① 点击（原脚本 13310~13345 行）
    // ======================================================================

    /**
     * {@code gsKlClickedOffSlot(p, e)} —— 这次点击是不是<b>玩家本人的副手槽</b>。
     *
     * <p>★ 只看玩家本人的背包视图（{@code getClickedInventory().getHolder()} 必须是这个玩家）：
     * 在箱子 / 工作台界面里点箱子那一半 ⇒ holder 不是玩家 ⇒ 一概不管（不会误拦箱子槽）。
     * ★ 副手槽判定位用 {@code getSlot()}（相对所点击 inventory 的索引，副手恒为 40）——
     * 见类注释。
     */
    static boolean gsKlClickedOffSlot(Player p, InventoryClickEvent e) {
        try {
            Inventory inv = null;
            try {
                inv = e.getClickedInventory();
            } catch (Throwable e2) {
                inv = null;
            }
            if (inv == null) {
                return false;
            }
            InventoryHolder h = null;
            try {
                h = inv.getHolder();
            } catch (Throwable e2) {
                h = null;
            }
            if (h == null) {
                return false;
            }
            String u = null;
            try {
                // ★ 原脚本是 '' + h.getUniqueId()（holder 不是 Player 时 JS 会抛 ⇒ u = null）；
                //   Java 用 instanceof 表达同一个判定。
                u = (h instanceof Player) ? ListenerCore.uid((Player) h) : null;
            } catch (Throwable e2) {
                u = null;
            }
            if (u == null || !u.equals(gsKlUuid(p))) {
                return false;
            }
            int slot = -1;
            try {
                slot = e.getSlot();
            } catch (Throwable e2) {
                slot = -1;
            }
            return (slot == GS_KL_SLOT_OFF);
        } catch (Throwable ex) {
            return false;
        }
    }

    /** ① {@code gsKlOnClick(e)} —— 副手槽上的一切操作都不许。 */
    void gsKlOnClick(InventoryClickEvent e) {
        try {
            if (e == null) {
                return;
            }
            Object who = null;
            try {
                who = e.getWhoClicked();
            } catch (Throwable e2) {
                who = null;
            }
            if (!gsKlIsPlayer(who)) {
                return;
            }
            Player p = (Player) who;
            if (!gsKlHasKey(p)) {
                return;                            // 副手没我们的钥匙 ⇒ 完全不管
            }
            if (!gsKlClickedOffSlot(p, e)) {
                return;                            // 碰的不是副手槽 ⇒ 与本球无关
            }
            try {
                e.setCancelled(true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            gsKlResync(p);
            gsKlHint(p);
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ② F 键交换（原脚本 13347~13362 行）
    // ======================================================================

    /** ② {@code gsKlOnSwap(e)} —— F 键交换主副手。 */
    void gsKlOnSwap(PlayerSwapHandItemsEvent e) {
        try {
            if (e == null) {
                return;
            }
            Player p = null;
            try {
                p = e.getPlayer();
            } catch (Throwable e2) {
                p = null;
            }
            if (!gsKlIsPlayer(p)) {
                return;
            }
            ItemStack off = null;
            ItemStack main = null;
            try {
                off = e.getOffHandItem();
            } catch (Throwable e2) {
                off = null;
            }
            try {
                main = e.getMainHandItem();
            } catch (Throwable e2) {
                main = null;
            }
            if (!gsKlKey(off) && !gsKlKey(main)) {
                return;                            // 交换的不是我们的钥匙 ⇒ 放行
            }
            try {
                e.setCancelled(true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            gsKlResync(p);
            gsKlHint(p);
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ③ 拖拽（原脚本 13364~13383 行）
    // ======================================================================

    /**
     * ③ {@code gsKlOnDrag(e)} —— 按住鼠标拖。
     *
     * <p>★ 只拦「光标上正拿着我们的钥匙」这一路：从副手槽把球<b>拿起来</b>的那一下是
     * {@code InventoryClickEvent}（已被 ① 拦掉）⇒ 正常流程里光标上不可能有钥匙；
     * 真出现了（漏拦 / 别的插件塞的）就必须拦，否则一拖就丢。
     * <p>★ <b>不按 rawSlot 判副手</b>：{@code rawSlot=45} 只在「玩家自身视图」成立，
     * 箱子视图里会错位（可能误拦箱子的别的槽）⇒ 交给 ① 与「每刻自愈」兜底更安全。
     */
    void gsKlOnDrag(InventoryDragEvent e) {
        try {
            if (e == null) {
                return;
            }
            Object who = null;
            try {
                who = e.getWhoClicked();
            } catch (Throwable e2) {
                who = null;
            }
            if (!gsKlIsPlayer(who)) {
                return;
            }
            Player p = (Player) who;
            ItemStack cur = null;
            try {
                cur = e.getCursor();
            } catch (Throwable e2) {
                cur = null;
            }
            if (!gsKlKey(cur)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
            gsKlResync(p);
            gsKlHint(p);
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ④ 丢弃（原脚本 13385~13394 行）
    // ======================================================================

    /** ④ {@code gsKlOnDrop(e)} —— 丢弃（Q 键）。 */
    void gsKlOnDrop(PlayerDropItemEvent e) {
        try {
            if (e == null) {
                return;
            }
            ItemStack it = null;
            try {
                it = e.getItemDrop().getItemStack();
            } catch (Throwable e2) {
                it = null;
            }
            if (!gsKlKey(it)) {
                return;
            }
            try {
                e.setCancelled(true);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ⑤ 死亡（原脚本 13396~13429 行）
    // ======================================================================

    /**
     * ⑤ {@code gsKlOnDeath(e)} —— 掉落列表剔掉钥匙 + 生成器那颗球留在地上。
     *
     * <p>① {@code drops} 里的「假篮球」剔掉（原版死亡会掉副手物品 ⇒ 不清就会在地上多一个按钮）。
     * <p>② 生成器那颗球：<b>留在死亡点</b>（掉在地上 ⇒ 别人可以走过去捡）。
     * <p>③ 副手那颗钥匙当场收走（不等 {@code drops} 列表，双保险）。
     */
    void gsKlOnDeath(PlayerDeathEvent e) {
        try {
            if (e == null) {
                return;
            }
            Player p = null;
            try {
                p = e.getEntity();
            } catch (Throwable e2) {
                p = null;
            }
            if (!gsKlIsPlayer(p)) {
                return;
            }

            // ① drops 里的「假篮球」剔掉
            try {
                List<ItemStack> drops = e.getDrops();
                if (drops != null) {
                    List<ItemStack> rm = new ArrayList<>();
                    int n = 0;
                    try {
                        n = drops.size();
                    } catch (Throwable e2) {
                        n = 0;
                    }
                    for (int i = 0; i < n; i++) {
                        ItemStack it = null;
                        try {
                            it = drops.get(i);
                        } catch (Throwable e2) {
                            it = null;
                        }
                        if (gsKlKey(it)) {
                            rm.add(it);
                        }
                    }
                    for (int j = 0; j < rm.size(); j++) {
                        try {
                            drops.remove(rm.get(j));
                        } catch (Throwable e2) {
                            // 与原脚本一致：静默
                        }
                    }
                }
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }

            // ② 生成器那颗球：留在死亡点
            try {
                BbSub.Ball b = bg.gsBgBoundBallOf(p);
                if (b != null) {
                    bg.gsBgToRest(b);
                }
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }

            // ③ 副手那颗钥匙当场收走（双保险）
            gsKlTakeKey(p);
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // ⑥ 退出（原脚本 13431~13441 行）
    // ======================================================================

    /** ⑥ {@code gsKlOnQuit(e)} —— 退出时收走钥匙（不然重登后副手会挂着一颗幽灵按钮）。 */
    void gsKlOnQuit(PlayerQuitEvent e) {
        try {
            if (e == null) {
                return;
            }
            Player p = null;
            try {
                p = e.getPlayer();
            } catch (Throwable e2) {
                p = null;
            }
            if (!gsKlIsPlayer(p)) {
                return;
            }
            gsKlTakeKey(p);
            gsKlSweep(p);
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 每刻自愈（原脚本 13443~13473 行）
    // ======================================================================

    /**
     * {@code gsKlTickPlayer(p)} —— 每刻自愈：该有却没有 ⇒ 补回；不该有却有 ⇒ 收走。
     *
     * <p>★ 被外力拿走了 ⇒ <b>下一刻</b>补回（不是等 0.5 秒）。
     */
    void gsKlTickPlayer(Player p) {
        try {
            if (p == null) {
                return;
            }
            boolean need = gsKlNeed(p);
            boolean has = gsKlHasKey(p);
            if (need && !has) {
                gsKlGiveKey(p);                    // 被外力拿走了 ⇒ 下一刻补回
            } else if (!need && has) {
                gsKlTakeKey(p);                    // 没在运球却挂着 ⇒ 收走（reload 残留 / 幽灵）
            }
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsKlPulse()} —— 心跳（{@code runRepeating(gsKlPulse, 1, 1)} ⇒ <b>每 1 游戏刻</b>一次）。
     *
     * <p>★★ 原脚本注释写「每 0.5 秒清残留」，但 {@code (GS_KL_SWEEP_AT | 0)} 的 {@code | 0}
     * 会回绕 ⇒ 真 JS 里<b>每刻都跑</b>。逐字保留（见类注释「时间戳陷阱」）。
     */
    void gsKlPulse() {
        try {
            Iterator<? extends Player> it = Bukkit.getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                Player p = null;
                try {
                    p = it.next();
                } catch (Throwable e) {
                    continue;
                }
                try {
                    gsKlTickPlayer(p);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            long now = ListenerCore.now();
            // ★★★ 逐字复刻 JS 的 `GS_KL_SWEEP_AT | 0`（ToInt32 回绕）⇒ 真 JS 里每刻都扫。
            if ((now - (int) GS_KL_SWEEP_AT) >= GS_KL_SWEEP_MS) {
                GS_KL_SWEEP_AT = now;
                Iterator<? extends Player> it2 = Bukkit.getServer().getOnlinePlayers().iterator();
                while (it2.hasNext()) {
                    Player q = null;
                    try {
                        q = it2.next();
                    } catch (Throwable e) {
                        continue;
                    }
                    try {
                        gsKlSweep(q);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable ex) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 监听器（原脚本 13475~13550 行）
    // ======================================================================

    /**
     * 原脚本 {@code gsKlRegisterSync()} 注册的 <b>6</b> 条事件。
     *
     * <p>★ 全部 {@code Priority.HIGHEST} + {@code ignoreCancelled = false}（见类注释）。
     * <p>★★ <b>探针会反射数这个类上 {@code @EventHandler} 的个数并断言 == 6</b>，
     * 以钉住日志里那句「6/6 条事件」。
     */
    static final class ListenerImpl implements Listener {

        private final KlSub sub;

        ListenerImpl(KlSub sub) {
            this.sub = sub;
        }

        /** ① 点击（含创造模式物品栏 —— {@code InventoryCreativeEvent} 是它的子类，父类注册照样收得到）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryClick(InventoryClickEvent e) {
            sub.gsKlOnClick(e);
        }

        /** ② F 键交换。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSwapHands(PlayerSwapHandItemsEvent e) {
            sub.gsKlOnSwap(e);
        }

        /** ③ 拖拽。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInventoryDrag(InventoryDragEvent e) {
            sub.gsKlOnDrag(e);
        }

        /** ④ 丢弃。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDropItem(PlayerDropItemEvent e) {
            sub.gsKlOnDrop(e);
        }

        /** ⑤ 死亡。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(PlayerDeathEvent e) {
            sub.gsKlOnDeath(e);
        }

        /** ⑥ 退出。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onQuit(PlayerQuitEvent e) {
            sub.gsKlOnQuit(e);
        }
    }

    /**
     * 原脚本 {@code gsKlRegisterSync()}。
     *
     * <p>★★ 计数语义逐字保留：原脚本对每条事件调 {@code reg()}，成功才 {@code ok++}；
     * {@code pm.registerEvent} 抛了就走 {@code JavaAdapter} 退路（GraalJS 里不存在 ⇒ 恒失败）
     * ⇒ 该条不计入。Java 的 {@code registerEvents} 是<b>一次调用注册全部 6 条</b>
     * ⇒ 成功即 {@code ok = 6}、失败即 {@code ok = 0}。
     * <p>★★ 无论 {@code ok} 是几，都<b>照样置闩锁 + 打成功日志 + return true</b>
     * （原脚本就是这样：{@code GS_KL_REGISTERED = true} 在日志之前，且不在 {@code ok > 0} 的条件里）。
     */
    boolean gsKlRegisterSync() {
        if (GS_KL_REGISTERED) {
            return true;
        }
        try {
            Plugin plugin = GS.pluginOrNull();
            if (plugin == null) {
                return false;
            }

            int ok = 0;
            try {
                Bukkit.getPluginManager().registerEvents(new ListenerImpl(this), plugin);
                ok = KL_EVENT_COUNT;
            } catch (Throwable e1) {
                // ★ 原脚本的 JavaAdapter 退路在 GraalJS 25.1.3 里不存在 ⇒ 死代码。
                //   原脚本每条事件各自 try ⇒ 全失败时 ok = 0。Java 一次注册全部 ⇒ ok = 0。
                ok = 0;
            }

            GS_KL_REGISTERED = true;
            ListenerCore.gsDaLog(gsKlRegisterLine(ok));
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("钥匙锁注册异常: " + e);
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 13552~13563 行）
    // ======================================================================

    /**
     * 原脚本 {@code gsKlStart()}。
     *
     * <p>★★ 注意它<b>不 return</b>：注册失败只记一条 warning，
     * 心跳（每刻自愈）<b>照样跑</b> —— 原脚本注释原文
     * 「监听器没注册上（<b>每刻自愈仍会跑</b>）」。
     */
    void gsKlStart() {
        try {
            if (!gsKlRegisterSync()) {
                ListenerCore.gsDaWarn("钥匙锁启动失败：监听器没注册上（每刻自愈仍会跑）");
            }
            // ★★ 每 1 游戏刻一次
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsKlPulse();
                }
            }, 1, 1);
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("钥匙锁启动异常: " + e);
        }
    }

    // ======================================================================
    // 日志行（★ 抽成静态方法只为让探针逐字节对账，不改任何行为）
    // ======================================================================

    /**
     * 加载那行（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ {@code ok} 就是「注册成功了几条」，原脚本格式 {@code ok + '/6 条事件'}。
     * ★ {@code GS_KL_SWEEP_MS / 1000} 在 JS 里是 {@code 500 / 1000 = 0.5} ⇒ 拼出 {@code "0.5"}
     * （<b>不是</b> {@code "0"}，也不是 {@code "0.5 秒"} 之外的什么）—— 必须走 {@link JsNum}。
     * 对账金标准：{@code docs/参考服日志_构思技艺.txt} 第 44 行
     * （真 JS 打出来的就是 {@code （6/6 条事件）} 与 {@code + 0.5 秒清残留}）。
     */
    static String gsKlRegisterLine(int ok) {
        return "副手「篮球」钥匙锁已加载（" + ok + "/6 条事件）"
                + " · 运球/持球期间那颗按钮**拿不下来**（点击副手槽 / F 键 / 拖拽 / 丢弃 一律拦回）"
                + " · 被外力拿走 ⇒ 每刻补回 + "
                + JsNum.str((double) GS_KL_SWEEP_MS / 1000.0) + " 秒清残留"
                + " · 死亡时剔掉掉落物里的假球、球留在死亡点 / 退出时收走";
    }
}
