package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code gs_listener.js} 的「篮球生成器」子系统
 * （原脚本第 <b>12554~13167</b> 行，顶层入口 {@code gsBgStart()}）。
 *
 * <h2>玩法</h2>
 * 放下 {@code GS_BASKET_GEN}（红色混凝土 · 构思玩具第 8 格）⇒ 方块上方
 * {@code GS_BG_UP}（1.5）格生成一颗篮球。
 * <ul>
 *   <li>★★ <b>一台生成器同时只有一颗球</b>（{@code GS_BG_MAX = 1}）—— 多人抢同一颗，
 *       捡走不再补第二颗。曾经是 10（同时被 10 个人各拿一颗），用户澄清：
 *       「我说的十个人是打一场比赛用一颗球打」⇒ 要的是<b>一颗球多人抢</b>。
 *       要改回多颗只需改这个数。</li>
 *   <li>只有<b>停着的球</b>捡得走（抢不走别人手上的）。</li>
 *   <li>超出 {@code GS_BG_RANGE}（32）格自动送回生成器上方。</li>
 *   <li>挖掉方块 ⇒ 球全部消失，<b>并且方块本身掉回一个全新的「篮球生成器」</b>
 *       （见 {@link #gsBgDropSelf}）。</li>
 * </ul>
 *
 * <h2>★★ 与「律师函」（{@link BbSub}）的复用关系</h2>
 * 本模块<b>不重复实现</b>任何物理：投篮走 {@link BbSub#gsBbShootState}、
 * 飞行走 {@link BbSub#gsBbTickShot}、渲染走 {@link BbSub#gsBbDraw}、
 * 删实体走 {@link BbSub#gsBbEntDrop}、找地面走 {@link BbSub#gsBbFloor} /
 * {@link BbSub#gsBbFloorSweep}。球状态用的是同一个 {@link BbSub.Ball} 类
 * （原脚本是鸭子类型的 JS 字面量，缺字段 = undefined）。
 *
 * <h2>★ 两套副手球必须分开（否则两套球串台）</h2>
 * 律师函的 {@code gsBbIsBall} 有个「名字含篮球 + 材质 = {@code ACACIA_BUTTON}」的兜底判定
 * ⇒ 生成器的球若也用金合欢木按钮，会被律师函认领走。
 * 所以这里用 {@code BIRCH_BUTTON}（白桦木按钮）+ PDC {@code gs:bg_ball}，
 * 与律师函的 {@code gs:bb_ball} <b>完全分开</b>。
 *
 * <h2>★ 生成器位置的持久化</h2>
 * 存<b>第一个世界</b>的 PDC 键 {@code gs:bg_nodes}，格式
 * {@code 世界名|x|y|z;世界名|x|y|z}（跨重启后还记得）。
 * ★ 用 {@link LinkedHashMap} 而不是 {@code HashMap} 存 {@link #GS_BG_NODES}：
 * 原脚本是 JS 对象，{@code for-in} 按<b>插入顺序</b>遍历 ⇒ 写进 PDC 的字符串顺序
 * 必须一致，否则落盘字节不同。
 *
 * <h2>★ 监听器注册：与原脚本的差异</h2>
 * 原脚本在 {@code gsBgRegisterSync()} 里用 {@code pm.registerEvent(...)} <b>自己注册</b>，
 * <b>每次 {@code /gs reload} 都会再注册一遍</b>（真实缺陷）。Java 侧改成
 * {@link ListenerImpl}（{@code org.bukkit.event.Listener}）+ {@code registerEvents}，
 * 用 {@link #GS_BG_REGISTERED} 闩锁保证<b>只注册一次</b>。
 * <p>★ <b>优先级与 ignoreCancelled 逐处核对</b>：原脚本 {@code registerEvent} 只传了
 * 4 个参数 ⇒ {@code ignoreCancelled} 取默认 <b>false</b>；Java 注解默认也是 false ⇒ 一致，
 * <b>不要</b>写成 true。优先级全部是 {@code Priority.HIGHEST}。
 *
 * <h2>★ 死代码清单（逐条，均已在 Java 里等价处理）</h2>
 * <ol>
 *   <li>三处 {@code reg()} 里的 {@code JavaAdapter} 退路 —— GraalJS 25.1.3 没有
 *       {@code JavaAdapter} ⇒ 恒失败，死代码。★ 但原脚本<b>忽略</b> {@code reg()} 的返回值
 *       ⇒ Java 侧同样「注册失败也继续往下走、照样置闩锁、照样打成功日志」。</li>
 *   <li>{@code gsBgBallNew} 的 {@code Material[GS_BG_BALL_MAT]} 分支 —— enum 的静态字段
 *       ≡ {@code valueOf}，已合并成一次 {@code valueOf}。</li>
 *   <li>{@code gsBgBallNew} 里「{@code BIRCH_BUTTON} 拿不到 ⇒ 退回 {@code ACACIA_BUTTON}」
 *       的兜底 —— {@code BIRCH_BUTTON} 在 1.21.11 必然存在（编译验证）⇒ 走不到。
 *       ★ 保留该兜底：它是「两套球不能串台」这条硬约束的最后一道防线。</li>
 *   <li>{@code gsBgPlayerByUuid} 里「{@code Bukkit.getPlayer(UUID)} 拿不到 ⇒ 遍历在线玩家」
 *       的兜底 —— 两条路都保留（第一条是正常路径，第二条防 {@code getPlayer} 在
 *       非主线程 / 未缓存时的行为差异）。</li>
 *   <li>{@code gsBgDropSelf} 的 {@code dropItemNaturally} → {@code dropItem} 降级 ——
 *       前者在 1.21.11 必然可用（编译验证）⇒ 走不到，保留结构。</li>
 * </ol>
 *
 * <h2>★★★ 时间戳陷阱（与 {@link BbSub} 同一类坑）</h2>
 * 原脚本有 2 处把毫秒时间戳先做 {@code | 0}（JS 的 {@code ToInt32}）再参与运算：
 * <pre>
 *   gsBgTryShoot  : if ((now - (GS_BG_CLICK_AT[u] | 0)) &lt; 120) return false;   // 120ms 去抖
 *   gsBgTickBound : var t = (gsMsNow() - (b.tBoundAt | 0)) / 1000.0;           // 运球相位
 * </pre>
 * 前者因为回绕而<b>恒不生效</b>；后者让生成器那颗球的运球相位变成<b>任意值</b>
 * （但 {@code |cos|} 的<b>周期</b>不变 ⇒ 观感一致）。
 * Java 一律用 {@code (int)} 窄化逐位复刻 —— 改成 {@code long} 直接比较会凭空造出
 * 原脚本没有的 120ms 去抖，是<b>可观察差异</b>（探针 D 段有正反两向断言）。
 */
public final class BgSub {

    private final ListenerCore core;
    private final BbSub bb;

    public BgSub(ListenerCore core, BbSub bb) {
        this.core = core;
        this.bb = bb;
    }

    /** 对应原脚本顶层的 {@code gsBgStart();} 调用（原脚本第 13167 行）。 */
    public void start() {
        gsBgStart();
    }

    // ======================================================================
    // 顶层常量（原脚本 12558~12586 行）
    // ======================================================================

    /** 生成器物品 id（红色混凝土 · 构思玩具第 8 格）。 */
    static final String GS_BG_ID = "GS_BASKET_GEN";
    /** 每台生成器同时存在的球上限（1 = 全场共用一颗）。★ 见类注释。 */
    static final int GS_BG_MAX = 1;
    /** 离生成器超过这个距离 ⇒ 自动送回。 */
    static final int GS_BG_RANGE = 32;
    /** ★ 球<b>球心</b>停在方块上方几格（1.5 ⇒ 球底离方块顶面约 0.26 格，微微悬浮、看得清）。 */
    static final double GS_BG_UP = 1.5;
    /** 水平触碰半径（格）。 */
    static final double GS_BG_TOUCH = 1.4;
    /** 竖直触碰容差（格）。 */
    static final double GS_BG_TOUCH_V = 1.6;
    /** ★★ 副手那颗临时球用<b>白桦木按钮</b>（与律师函的金合欢木按钮分开，见类注释）。 */
    static final String GS_BG_BALL_MAT = "BIRCH_BUTTON";
    /** 球物品名字。 */
    static final String GS_BG_BALL_NAME = "篮球";

    /** 心跳世代号（★ 绝不复用 {@code gs:bb_gen}）。 */
    static final NamespacedKey GS_BG_K_GEN = NamespacedKey.fromString("gs:bg_gen");
    /** 生成器位置列表（跨重启）。 */
    static final NamespacedKey GS_BG_K_NODES = NamespacedKey.fromString("gs:bg_nodes");
    /** ★ 副手那颗临时球（与律师函的 {@code gs:bb_ball} 分开）。 */
    static final NamespacedKey GS_BG_K_BALL = NamespacedKey.fromString("gs:bg_ball");
    /** 原脚本 {@code GS_BG_PDT = Java.type('...PersistentDataType')}。 */
    static final Class<?> GS_BG_PDT = PersistentDataType.class;

    // ======================================================================
    // 运行期状态（原脚本 12574~12579 行）
    // ======================================================================

    /** key（{@code 世界名|x|y|z}）→ 生成器节点。★ 用 LinkedHashMap 保插入顺序（见类注释）。 */
    final Map<String, BgNode> GS_BG_NODES = new LinkedHashMap<>();
    /** 同步监听器是否注册成功（Java 侧是幂等闩锁）。 */
    boolean GS_BG_REGISTERED = false;
    /** ★ 自己的心跳世代号（-1 = 还没拿到）。 */
    int GS_BG_GEN = -1;
    /** 右键去抖（毫秒）。 */
    final Map<String, Long> GS_BG_CLICK_AT = new LinkedHashMap<>();

    /**
     * 一台生成器。
     *
     * <p>★ 原脚本是 {@code { w, x, y, z, balls: [] }}；{@code x/y/z} 一律是
     * {@code (x | 0)} 过的<b>整数</b>（{@link #gsBgAdd}）。
     */
    static final class BgNode {
        World w = null;
        int x, y, z;
        final List<BbSub.Ball> balls = new ArrayList<>();
    }

    // ======================================================================
    // 小工具（原脚本 12588~12591 行）
    // ======================================================================

    /**
     * {@code gsBgKey(w, x, y, z)} —— 生成器的字符串键。
     * ★ 所有调用点传进来的 {@code w} 都是<b>世界名</b>（{@code w.getName()} / 存档里读出的字符串），
     * 所以 Java 侧参数类型直接用 {@code String}（原脚本是 JS 的 {@code '' + w}）。
     */
    static String gsBgKey(String w, double x, double y, double z) {
        return w + "|" + ((int) x) + "|" + ((int) y) + "|" + ((int) z);
    }

    /** {@code gsBgIs(item)} —— 是不是「篮球生成器」方块物品。 */
    static boolean gsBgIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_BG_ID);
    }

    /** {@code gsBgWorld()} —— 第一个世界（拿不到返回 null）。 */
    static World gsBgWorld() {
        try {
            return Bukkit.getServer().getWorlds().get(0);
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 生成器节点的增删查（原脚本 12593~12652 行）
    // ======================================================================

    /** {@code gsBgAdd(w, x, y, z)} —— 登记一台生成器（已存在就返回已有的那台）。 */
    BgNode gsBgAdd(World w, double x, double y, double z) {
        try {
            if (w == null) {
                return null;
            }
            String k = gsBgKey(w.getName(), x, y, z);
            BgNode ex = GS_BG_NODES.get(k);
            if (ex != null) {
                return ex;
            }
            BgNode node = new BgNode();
            node.w = w;
            node.x = (int) x;
            node.y = (int) y;
            node.z = (int) z;
            GS_BG_NODES.put(k, node);
            gsBgSave();
            return node;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsBgNodeAt(w, x, y, z)} —— 这个坐标上有没有登记过的生成器。 */
    BgNode gsBgNodeAt(String w, double x, double y, double z) {
        try {
            BgNode n = GS_BG_NODES.get(gsBgKey(w, x, y, z));
            return (n == null) ? null : n;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 删掉一个生成器（方块被挖掉）⇒ 它名下的球<b>全部消失</b>（实体也要删，不留幽灵）。 */
    void gsBgDropNode(BgNode node) {
        try {
            if (node == null) {
                return;
            }
            for (int i = 0; i < node.balls.size(); i++) {
                bb.gsBbEntDrop(node.balls.get(i));
            }
            node.balls.clear();
            try {
                GS_BG_NODES.remove(gsBgKey(node.w.getName(), node.x, node.y, node.z));
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            gsBgSave();
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★★★ {@code gsBgDropSelf(ev, blk)} —— 挖掉生成器时，<b>方块本身必须掉回「篮球生成器」物品</b>。
     *
     * <p>用户实测：挖掉之后只掉<b>红色混凝土</b>（原版材质）⇒ 生成器就永久丢了，
     * 想再要一个只能重新合成。
     * <p>★ 病因：RSC 的 {@code placeable: true} 只管「放下时把物品变成方块」，
     * <b>不管「挖掉时把方块变回物品」</b>（那是 Slimefun 机器的 {@code BlockStorage} 才有的待遇，
     * 自定义物品走的不是那条路）⇒ 方块一碎，原版就按材质掉了红混凝土。
     * <p>⇒ 自己接管：先把<b>原版掉落关掉</b>（{@code setDropItems(false)}，这样红混凝土不再掉），
     * 再把方块正常打碎，最后<b>丢一个全新的 {@code GS_BASKET_GEN}</b> 出去。
     * <p>★ 不用 {@code setCancelled(true)}：取消了方块就碎不掉，还得自己 {@code setType(AIR)} 收尾，
     * 反而容易和别的插件打架；{@code setDropItems(false)} 更干净 —— 方块照常碎、只换掉落物。
     * <p>★ 只对<b>我们登记过的</b>生成器生效（调用方已经用 {@link #gsBgNodeAt} 判过），
     * 所以不会误伤别的红混凝土方块。
     */
    static boolean gsBgDropSelf(BlockBreakEvent ev, Block blk) {
        try {
            if (ev == null || blk == null) {
                return false;
            }

            // ① 关掉原版掉落（红混凝土就是从这里掉的）
            try {
                ev.setDropItems(false);
            } catch (Throwable e0) {
                // 与原脚本一致：静默
            }

            // ② 丢一个全新的生成器（用 gsNewItem ⇒ 名字 / 材质 / 属性都和合成出来的一模一样）
            ItemStack it = ListenerCore.gsNewItem(GS_BG_ID);
            if (it == null) {
                return false;
            }
            Location loc = null;
            try {
                loc = blk.getLocation().add(0.5, 0.5, 0.5);
            } catch (Throwable e1) {
                loc = null;
            }
            if (loc == null) {
                try {
                    loc = blk.getLocation();
                } catch (Throwable e2) {
                    return false;
                }
            }
            try {
                blk.getWorld().dropItemNaturally(loc, it);
            } catch (Throwable e3) {
                try {
                    blk.getWorld().dropItem(loc, it);
                } catch (Throwable e4) {
                    return false;
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 持久化（原脚本 12654~12691 行）
    // ======================================================================

    /**
     * {@code gsBgSave()} —— 把生成器位置列表写进第一个世界的 PDC。
     * 格式 {@code 世界名|x|y|z;世界名|x|y|z}。
     */
    void gsBgSave() {
        try {
            if (GS_BG_PDT == null || GS_BG_K_NODES == null) {
                return;
            }
            World w0 = gsBgWorld();
            if (w0 == null) {
                return;
            }
            StringBuilder s = new StringBuilder();
            for (String k : GS_BG_NODES.keySet()) {
                BgNode n = GS_BG_NODES.get(k);
                if (n == null) {
                    continue;
                }
                if (s.length() > 0) {
                    s.append(';');
                }
                s.append(n.w.getName()).append('|')
                        .append(n.x).append('|').append(n.y).append('|').append(n.z);
            }
            w0.getPersistentDataContainer().set(GS_BG_K_NODES, PersistentDataType.STRING, s.toString());
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgLoad()} —— 从第一个世界的 PDC 读回生成器位置列表。 */
    void gsBgLoad() {
        try {
            if (GS_BG_PDT == null || GS_BG_K_NODES == null) {
                return;
            }
            World w0 = gsBgWorld();
            if (w0 == null) {
                return;
            }
            String v = w0.getPersistentDataContainer().get(GS_BG_K_NODES, PersistentDataType.STRING);
            if (v == null) {
                return;
            }
            // ★ split 一律带 limit = -1：JS 的 String.prototype.split 保留尾随空串，Java 默认会丢
            String[] parts = v.split(";", -1);
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].isEmpty()) {
                    continue;
                }
                String[] p = parts[i].split("\\|", -1);
                if (p.length < 4) {
                    continue;
                }
                World w = Bukkit.getServer().getWorld(p[0]);
                if (w == null) {
                    continue;
                }
                BgNode n = new BgNode();
                n.w = w;
                n.x = jsToInt32(p[1]);
                n.y = jsToInt32(p[2]);
                n.z = jsToInt32(p[3]);
                GS_BG_NODES.put(gsBgKey(p[0], n.x, n.y, n.z), n);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * JS 的 {@code (字符串 | 0)}（= {@code ToNumber} 后 {@code ToInt32}）的等价实现。
     * ★ 不要用 {@code Integer.parseInt}：它对 {@code "12abc"} 返回 12，而 JS 会得到
     * {@code NaN | 0 === 0}。我们写盘的一定是纯整数，但结构上要一致。
     */
    static int jsToInt32(String s) {
        try {
            double d = Double.parseDouble(s.trim());
            if (Double.isNaN(d)) {
                return 0;
            }
            return (int) d;
        } catch (Throwable e) {
            return 0;
        }
    }

    // ======================================================================
    // 球（原脚本 12692~12766 行）
    // ======================================================================

    /**
     * {@code gsBgNewBall(node)} —— 在生成器上方造一颗 idle 球。
     * ★ 球心在方块正上方 {@code GS_BG_UP} 格。
     */
    BbSub.Ball gsBgNewBall(BgNode node) {
        BbSub.Ball b = new BbSub.Ball();
        b.mode = "idle";
        b.w = null;
        b.x = node.x + 0.5;
        b.y = node.y + GS_BG_UP;
        b.z = node.z + 0.5;
        b.vx = 0.0;
        b.vy = 0.0;
        b.vz = 0.0;
        b.spin = 0.0;
        b.life = 0;
        b.shotAt = 0;
        b.tBoundAt = 0;
        b.bnc = 0;
        b.hitWall = false;
        b.ent = null;
        b.entTick = 0;
        b.sy0 = 0.0;
        b.owner = null;
        b.node = node;
        b.heldBall = false;
        return b;
    }

    /** {@code gsBgBind(p, b)} —— 这个玩家拿到了这颗球（开始运球 / 持球）。 */
    void gsBgBind(Player p, BbSub.Ball b) {
        try {
            b.mode = "bound";
            b.owner = ListenerCore.uid(p);
            b.tBoundAt = ListenerCore.now();
            b.vx = 0.0;
            b.vy = 0.0;
            b.vz = 0.0;
            gsBgHandOn(p, b);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgToRest(b)} —— 球留在原地（收走副手那颗，落到最近地面）。 */
    void gsBgToRest(BbSub.Ball b) {
        try {
            if (b.owner != null) {
                gsBgHandOffUuid(b.owner);
            }
            b.owner = null;
            b.mode = "rest";
            b.vx = 0.0;
            b.vy = 0.0;
            b.vz = 0.0;
            Double fl = BbSub.gsBbFloorSweep(b.w, b.x, b.y, b.y - 2.0, b.z, BbSub.GS_BB_R);
            if (fl == null) {
                fl = BbSub.gsBbFloor(b.w, b.x, b.y - BbSub.GS_BB_R, b.z, 24);
            }
            if (fl != null) {
                b.y = fl + BbSub.GS_BB_R;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgFar(node, b)} —— 球离生成器是不是超出 {@code GS_BG_RANGE} 格（三维距离）。 */
    static boolean gsBgFar(BgNode node, BbSub.Ball b) {
        try {
            double dx = b.x - (node.x + 0.5);
            double dz = b.z - (node.z + 0.5);
            double dy = b.y - node.y;
            return Math.sqrt(dx * dx + dz * dz + dy * dy) > GS_BG_RANGE;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsBgSendHome(node, b)} —— 跑太远 ⇒ 送回生成器上方（回到 idle）。 */
    void gsBgSendHome(BgNode node, BbSub.Ball b) {
        try {
            if (b.owner != null) {
                gsBgHandOffUuid(b.owner);
            }
            b.owner = null;
            b.mode = "idle";
            b.vx = 0.0;
            b.vy = 0.0;
            b.vz = 0.0;
            b.x = node.x + 0.5;
            b.z = node.z + 0.5;
            b.y = node.y + GS_BG_UP;
            b.bnc = 0;
            b.hitWall = false;
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgTouch(p, b)} —— 玩家碰到球了吗（水平 1.4 格 + 竖直 ±1.6 格）。 */
    static boolean gsBgTouch(Player p, BbSub.Ball b) {
        try {
            Location loc = p.getLocation();
            double dx = b.x - loc.getX();
            double dz = b.z - loc.getZ();
            if (Math.sqrt(dx * dx + dz * dz) > GS_BG_TOUCH) {
                return false;
            }
            return Math.abs(b.y - (loc.getY() + 0.9)) < GS_BG_TOUCH_V;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsBgBoundBallOf(p)} —— 这个玩家<b>手上</b>那颗生成器球（没有返回 null）。 */
    BbSub.Ball gsBgBoundBallOf(Player p) {
        try {
            String u = ListenerCore.uid(p);
            for (String k : GS_BG_NODES.keySet()) {
                BgNode n = GS_BG_NODES.get(k);
                if (n == null) {
                    continue;
                }
                for (int i = 0; i < n.balls.size(); i++) {
                    BbSub.Ball b = n.balls.get(i);
                    if (b != null && u.equals(b.owner) && "bound".equals(b.mode)) {
                        return b;
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsBgHasBound(p)} —— 这个玩家现在拿着生成器的球吗。 */
    boolean gsBgHasBound(Player p) {
        return gsBgBoundBallOf(p) != null;
    }

    /** {@code gsBgPlayerHasHere(p, node)} —— 这个玩家已经拿了<b>这台</b>生成器的球吗。 */
    static boolean gsBgPlayerHasHere(Player p, BgNode node) {
        try {
            String u = ListenerCore.uid(p);
            for (int i = 0; i < node.balls.size(); i++) {
                BbSub.Ball b = node.balls.get(i);
                if (b != null && u.equals(b.owner) && "bound".equals(b.mode)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * {@code gsBgTryPick(node, b)} —— idle / rest 的球等人来捡。
     * ★ 只有<b>停着的球</b>捡得走（{@code mode} 必须是 idle / rest）；抢不走别人手上的。
     */
    void gsBgTryPick(BgNode node, BbSub.Ball b) {
        try {
            if (!"idle".equals(b.mode) && !"rest".equals(b.mode)) {
                return;
            }
            Iterator<? extends Player> it = Bukkit.getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                Player p = it.next();
                try {
                    if (gsBgHasBound(p)) {
                        continue;                  // 已经拿着球了
                    }
                    if (gsBgPlayerHasHere(p, node)) {
                        continue;
                    }
                    if (!gsBgTouch(p, b)) {
                        continue;
                    }
                    gsBgBind(p, b);
                    return;
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBgTickBound(p, b)} —— 持球中：球在「地面」与「右手」之间按 |cos| 往返。
     *
     * <p>★★ 相位基准 {@code (b.tBoundAt | 0)} <b>带</b> {@code | 0}（与律师函那颗球的
     * {@code st.t0} 相反）⇒ 真 JS 里这个相位是<b>任意值</b>（时间戳被 ToInt32 回绕）。
     * 但 {@code |cos|} 的<b>周期</b>只取决于 {@code now} 的增量 ⇒ 观感一致（球照样在
     * 0.9 秒一个来回地弹）。Java 用 {@code (int)} 窄化逐位复刻。
     */
    void gsBgTickBound(Player p, BbSub.Ball b) {
        try {
            Location loc = p.getLocation();
            Vector dir = null;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                dir = null;
            }
            double fx = (dir != null) ? dir.getX() : 0.0;
            double fz = (dir != null) ? dir.getZ() : 1.0;
            double l = Math.sqrt(fx * fx + fz * fz);
            if (l < 1.0e-4) {
                fx = 0.0;
                fz = 1.0;
                l = 1.0;
            }
            fx /= l;
            fz /= l;
            double rx = -fz;
            double rz = fx;

            Double gyD = BbSub.gsBbFloor(b.w, loc.getX(), loc.getY(), loc.getZ(), 6);
            double gy = (gyD == null) ? loc.getY() : gyD;
            double loY = gy + BbSub.GS_BB_R;
            double hiY = loc.getY() + BbSub.GS_BB_HAND_UP;
            if (hiY < loY) {
                hiY = loY;
            }
            // ★★★ 逐字复刻 JS 的 `b.tBoundAt | 0`（ToInt32 回绕）
            double t = (ListenerCore.now() - (int) b.tBoundAt) / 1000.0;
            double k = Math.abs(Math.cos(Math.PI * t / BbSub.GS_BB_BOUNCE_S));

            b.x = loc.getX() + rx * BbSub.GS_BB_HAND_SIDE + fx * BbSub.GS_BB_HAND_FWD;
            b.z = loc.getZ() + rz * BbSub.GS_BB_HAND_SIDE + fz * BbSub.GS_BB_HAND_FWD;
            b.y = loY + k * (hiY - loY);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBgNodeTick(node)} —— 一台生成器每刻的处理。
     *
     * <p>① ★★ 一台生成器<b>只养一颗球</b>（{@code GS_BG_MAX = 1}）：球<b>彻底没了</b>才补一颗。
     * 球在别人手上（bound）/ 飞在半空（shot）/ 落地停着（rest）都算「这颗球还在」，
     * 绝不再长第二颗 ⇒ 多人抢的<b>就是同一颗球</b>。
     * <p>② 每颗球：自转 → 跑太远送回 → 按 mode 分发
     * （bound 走 {@link #gsBgTickBound}；shot <b>直接复用</b>
     * {@link BbSub#gsBbTickShot}；idle / rest 走 {@link #gsBgTryPick}）。
     * <p>★ 渲染复用 {@link BbSub#gsBbDraw}（展示实体 / 粒子）。
     */
    void gsBgNodeTick(BgNode node) {
        try {
            World w = node.w;
            if (w == null) {
                return;
            }
            int idleCount = 0;
            for (int i0 = 0; i0 < node.balls.size(); i0++) {
                BbSub.Ball b0 = node.balls.get(i0);
                if (b0 == null) {
                    continue;
                }
                if ("idle".equals(b0.mode)) {
                    idleCount++;
                }
            }
            // ① ★★ 一台生成器只养一颗球：球彻底没了才补一颗
            if (node.balls.size() < GS_BG_MAX) {
                BbSub.Ball nb = gsBgNewBall(node);
                nb.w = w;
                node.balls.add(nb);
                idleCount++;
            }
            // ② 每颗球
            List<BbSub.Ball> keep = new ArrayList<>();
            for (int i = 0; i < node.balls.size(); i++) {
                BbSub.Ball b = node.balls.get(i);
                if (b == null) {
                    continue;
                }
                b.w = w;
                b.spin = b.spin + BbSub.GS_BB_SPIN;

                // ★ 跑太远 ⇒ 送回生成器
                if (!"idle".equals(b.mode) && gsBgFar(node, b)) {
                    gsBgSendHome(node, b);
                }

                if ("bound".equals(b.mode)) {
                    Player p = gsBgPlayerByUuid(b.owner);
                    if (p == null) {
                        gsBgToRest(b);             // 人不在了 ⇒ 球留在原地（别人可以捡）
                    } else {
                        gsBgTickBound(p, b);
                    }
                } else if ("shot".equals(b.mode)) {
                    bb.gsBbTickShot(null, b);      // ★ 直接复用律师函的飞行物理
                } else {
                    gsBgTryPick(node, b);          // idle / rest ⇒ 等人来捡
                }

                // ★ 保险：任何情况下只留一颗待捡球（GS_BG_MAX = 1 时理论上不会出现多余的）
                if ("idle".equals(b.mode)) {
                    if (idleCount > GS_BG_MAX) {
                        bb.gsBbEntDrop(b);
                        idleCount--;
                        continue;
                    }
                }
                bb.gsBbDraw(b);                    // ★ 复用律师函的渲染（展示实体 / 粒子）
                keep.add(b);
            }
            node.balls.clear();
            node.balls.addAll(keep);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgPlayerByUuid(u)} —— uuid 字符串 → 在线玩家。 */
    static Player gsBgPlayerByUuid(String u) {
        try {
            if (u == null) {
                return null;
            }
            // 优先用 Bukkit.getPlayer(UUID)（拿不到再遍历在线玩家）
            try {
                Player p = Bukkit.getPlayer(UUID.fromString(u));
                if (p != null) {
                    return p;
                }
            } catch (Throwable e) {
                // 落到遍历
            }
            Iterator<? extends Player> it = Bukkit.getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                Player q = it.next();
                try {
                    if (ListenerCore.uid(q).equals(u)) {
                        return q;
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    // ======================================================================
    // 副手那颗临时球（原脚本 12896~12940 行）
    // ======================================================================

    /**
     * {@code gsBgBallNew()} —— 造一颗副手用的「篮球」（★ 白桦木按钮，与律师函的分开）。
     * ★ 同样只是「骗原版客户端发包」的钥匙，不是给玩家的道具。
     */
    ItemStack gsBgBallNew() {
        try {
            Material mat = null;
            // ★ enum 静态字段 ≡ valueOf，合并成一次
            try {
                mat = Material.valueOf(GS_BG_BALL_MAT);
            } catch (Throwable e) {
                mat = null;
            }
            if (mat == null) {
                try {
                    mat = Material.ACACIA_BUTTON;
                } catch (Throwable e) {
                    mat = null;
                }
            }
            if (mat == null) {
                return null;
            }
            ItemStack st = new ItemStack(mat, 1);
            ItemMeta meta = null;
            try {
                meta = st.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            if (meta != null) {
                try {
                    meta.setDisplayName("§f" + GS_BG_BALL_NAME);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    if (GS_BG_K_BALL != null && GS_BG_PDT != null) {
                        meta.getPersistentDataContainer().set(GS_BG_K_BALL, PersistentDataType.STRING, "1");
                    }
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    st.setItemMeta(meta);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            return st;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsBgIsBall(item)} —— 靠 PDC {@code gs:bg_ball} 认领（★ 不看名字，避免与律师函串台）。 */
    boolean gsBgIsBall(ItemStack item) {
        try {
            if (item == null) {
                return false;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta == null) {
                return false;
            }
            if (GS_BG_K_BALL == null || GS_BG_PDT == null) {
                return false;
            }
            return meta.getPersistentDataContainer().get(GS_BG_K_BALL, PersistentDataType.STRING) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsBgIsEmpty(item)} —— 副手是不是空着。
     * ★ 与 {@link BbSub#gsBbIsEmpty} 在原脚本里是<b>逐字相同</b>的两份实现（作者复制粘贴的），
     * 这里委托过去，行为完全一致。
     */
    static boolean gsBgIsEmpty(ItemStack item) {
        return BbSub.gsBbIsEmpty(item);
    }

    /**
     * {@code gsBgHandOn(p, b)} —— 持球期间每刻把副手那颗临时球带上。
     * ★ 副手已经是我们两套球里的任意一颗 ⇒ 复用；副手是玩家自己的东西 ⇒ 不碰。
     */
    void gsBgHandOn(Player p, BbSub.Ball b) {
        try {
            if (p == null || b == null) {
                return;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return;
            }
            ItemStack cur = null;
            try {
                cur = inv.getItemInOffHand();
            } catch (Throwable e) {
                cur = null;
            }
            if (gsBgIsBall(cur) || bb.gsBbIsBall(cur)) {
                b.heldBall = true;
                return;
            }
            if (!gsBgIsEmpty(cur)) {
                return;
            }
            ItemStack ball = gsBgBallNew();
            if (ball == null) {
                return;
            }
            try {
                inv.setItemInOffHand(ball);
                b.heldBall = true;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgHandOff(p, b)} —— 收走副手那颗生成器球（<b>只收我们自己发的</b>）。 */
    void gsBgHandOff(Player p, BbSub.Ball b) {
        try {
            if (b != null) {
                b.heldBall = false;
            }
            if (p == null) {
                return;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return;
            }
            ItemStack cur = null;
            try {
                cur = inv.getItemInOffHand();
            } catch (Throwable e) {
                cur = null;
            }
            if (gsBgIsBall(cur)) {
                try {
                    inv.setItemInOffHand(null);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBgHandOffUuid(u)} —— 按 uuid 收走副手那颗球。 */
    void gsBgHandOffUuid(String u) {
        try {
            Player p = gsBgPlayerByUuid(u);
            if (p != null) {
                gsBgHandOff(p, null);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBgTryShoot(p)} —— 投篮（复用 {@link BbSub#gsBbShootState}）。
     *
     * <p>★★ 120ms 去抖带 {@code | 0} 回绕 ⇒ 真 JS 里<b>恒不生效</b>。
     * Java 用 {@code (int)} 窄化逐位复刻（见类注释「时间戳陷阱」）。
     */
    boolean gsBgTryShoot(Player p) {
        try {
            if (p == null) {
                return false;
            }
            String u = ListenerCore.uid(p);
            long now = ListenerCore.now();
            Long lastObj = GS_BG_CLICK_AT.get(u);
            // ★★★ 逐字复刻 JS 的 `GS_BG_CLICK_AT[u] | 0`（undefined | 0 === 0）
            int last = (lastObj == null) ? 0 : (int) (long) lastObj;
            if ((now - last) < 120) {
                return false;
            }
            GS_BG_CLICK_AT.put(u, now);

            BbSub.Ball b = gsBgBoundBallOf(p);
            if (b == null) {
                return false;
            }
            boolean ok = bb.gsBbShootState(p, b);      // ★ 复用律师函的投篮（准星方向 + 两段式）
            if (ok) {
                gsBgHandOff(p, b);                     // 投出去 ⇒ 副手那颗球收走
            }
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 心跳（原脚本 12993~13042 行）
    // ======================================================================

    /** {@code gsBgGenNow()} —— 读当前世代号（读不到返回 null，与 0 区分开）。 */
    Integer gsBgGenNow() {
        try {
            if (GS_BG_PDT == null || GS_BG_K_GEN == null) {
                return null;
            }
            World w = gsBgWorld();
            if (w == null) {
                return null;
            }
            Integer v = w.getPersistentDataContainer().get(GS_BG_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? Integer.valueOf(0) : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsBgGenBump()} —— 世代号 +1 并写回世界 PDC（与律师函同一套机制，见 {@link BbSub#gsBbGenBump}）。 */
    void gsBgGenBump() {
        try {
            Integer cur = gsBgGenNow();
            if (cur == null) {
                cur = Integer.valueOf(0);
            }
            GS_BG_GEN = cur + 1;
            if (GS_BG_PDT == null || GS_BG_K_GEN == null) {
                return;
            }
            World w = gsBgWorld();
            if (w == null) {
                return;
            }
            w.getPersistentDataContainer().set(GS_BG_K_GEN, PersistentDataType.INTEGER, GS_BG_GEN);
        } catch (Throwable e) {
            if (GS_BG_GEN <= 0) {
                GS_BG_GEN = 1;
            }
        }
    }

    /**
     * {@code gsBgPulse()} —— 心跳（{@code runRepeating(gsBgPulse, 1, 1)} ⇒ <b>每 1 游戏刻</b>一次）。
     * ★ 世代号不是当前那份 ⇒ 旧实例留下的球全部删掉（实体也要删），然后直接停手。
     */
    void gsBgPulse() {
        boolean mine = true;
        try {
            Integer g = gsBgGenNow();
            if (g != null && GS_BG_GEN != -1) {
                mine = (g.intValue() == GS_BG_GEN);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        if (!mine) {
            try {
                for (String kd : new ArrayList<>(GS_BG_NODES.keySet())) {
                    BgNode nd = GS_BG_NODES.get(kd);
                    if (nd == null) {
                        continue;
                    }
                    for (int i = 0; i < nd.balls.size(); i++) {
                        bb.gsBbEntDrop(nd.balls.get(i));
                    }
                    nd.balls.clear();
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return;
        }
        try {
            for (String k : new ArrayList<>(GS_BG_NODES.keySet())) {
                BgNode node = GS_BG_NODES.get(k);
                if (node != null) {
                    gsBgNodeTick(node);
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 监听器（原脚本 13044~13146 行）
    // ======================================================================

    /**
     * 原脚本 {@code gsBgRegisterSync()} 注册的 4 个事件。
     * ★ 全部 {@code Priority.HIGHEST} + {@code ignoreCancelled = false}（见类注释）。
     */
    static final class ListenerImpl implements Listener {

        private final BgSub sub;

        ListenerImpl(BgSub sub) {
            this.sub = sub;
        }

        /** ① 放下生成器 ⇒ 登记节点。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPlace(BlockPlaceEvent e) {
            try {
                if (e == null) {
                    return;
                }
                ItemStack it = null;
                try {
                    it = e.getItemInHand();
                } catch (Throwable err) {
                    it = null;
                }
                if (!gsBgIs(it)) {
                    return;
                }
                Block blk = null;
                try {
                    // ★ 逐字用 e.getBlock()（原脚本就是它），不要换成 getBlockPlaced()：
                    //   两者在 Bukkit 里对 BlockPlaceEvent 的语义有过历史分歧，用同一个调用最稳。
                    blk = e.getBlock();
                } catch (Throwable err) {
                    blk = null;
                }
                if (blk == null) {
                    return;
                }
                sub.gsBgAdd(blk.getWorld(), blk.getX(), blk.getY(), blk.getZ());
            } catch (Throwable err2) {
                // 与原脚本一致：静默
            }
        }

        /** ② 挖掉生成器 ⇒ 球全消失 + 方块掉回「篮球生成器」物品。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBreak(BlockBreakEvent e) {
            try {
                if (e == null) {
                    return;
                }
                Block blk = null;
                try {
                    blk = e.getBlock();
                } catch (Throwable err) {
                    blk = null;
                }
                if (blk == null) {
                    return;
                }
                BgSub.BgNode node = null;
                try {
                    node = sub.gsBgNodeAt(blk.getWorld().getName(), blk.getX(), blk.getY(), blk.getZ());
                } catch (Throwable err) {
                    node = null;
                }
                if (node == null) {
                    return;                        // ★ 只对我们登记过的生成器生效
                }
                sub.gsBgDropNode(node);
                gsBgDropSelf(e, blk);
            } catch (Throwable err2) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 右键（靠副手球发包）⇒ 投篮。★ 只有拿着生成器的球时才接管右键。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                if (e == null) {
                    return;
                }
                Player p = null;
                try {
                    p = e.getPlayer();
                } catch (Throwable err) {
                    p = null;
                }
                if (p == null) {
                    return;
                }
                if (!sub.gsBgHasBound(p)) {
                    return;
                }
                sub.gsBgTryShoot(p);
            } catch (Throwable err2) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ④ 挥手兜底（空手对着空气右键时客户端不发包 ⇒ 挥手也能投）。
         *
         * <p>★ 与 {@link BbSub.ListenerImpl#onAnimation} 同理：开关在方法体首行判断，
         * 语义等价于原脚本的「运行期决定要不要注册」。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                if (e == null) {
                    return;
                }
                if (!BbSub.GS_BB_SWING_FALLBACK) {
                    return;
                }
                Player p = null;
                try {
                    p = e.getPlayer();
                } catch (Throwable err) {
                    p = null;
                }
                if (p == null) {
                    return;
                }
                if (!sub.gsBgHasBound(p)) {
                    return;
                }
                PlayerInventory inv = p.getInventory();
                ItemStack main = (inv != null) ? inv.getItemInMainHand() : null;
                if (!gsBgIsEmpty(main)) {
                    return;                        // 手里有东西的挥手多半是在打人，别误触
                }
                sub.gsBgTryShoot(p);
            } catch (Throwable err2) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 原脚本 {@code gsBgRegisterSync()}。
     * ★ 与原脚本一致：{@code registerEvents} 失败也<b>吞掉</b>、继续置闩锁 + 打成功日志
     * （原脚本忽略每个 {@code reg()} 的返回值）。
     */
    boolean gsBgRegisterSync() {
        if (GS_BG_REGISTERED) {
            return true;
        }
        try {
            Plugin plugin = GS.pluginOrNull();
            if (plugin == null) {
                return false;
            }
            try {
                Bukkit.getPluginManager().registerEvents(new ListenerImpl(this), plugin);
            } catch (Throwable e1) {
                // ★ 原脚本的 JavaAdapter 退路在 GraalJS 25.1.3 里不存在 ⇒ 死代码；
                //   原脚本忽略 reg() 的返回值 ⇒ 这里也不 return false。
            }
            GS_BG_REGISTERED = true;
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 13148~13167 行）
    // ======================================================================

    /** 原脚本 {@code gsBgStart()}。 */
    void gsBgStart() {
        try {
            gsBgGenBump();
            gsBgLoad();
            if (!gsBgRegisterSync()) {
                ListenerCore.gsDaWarn("篮球生成器启动失败：监听器没注册上");
                return;
            }
            // ★★ 每 1 游戏刻一次
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsBgPulse();
                }
            }, 1, 1);
            ListenerCore.gsDaLog(gsBgStartLine());
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("篮球生成器启动异常: " + e);
        }
    }

    // ======================================================================
    // 日志行（★ 抽成静态方法只为让探针逐字节对账，不改任何行为）
    // ======================================================================

    /**
     * 加载那行（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 数字走 {@link JsNum}：{@code GS_BG_UP = 1.5} → {@code "1.5"}、
     * {@code GS_BG_RANGE = 32} → {@code "32"}。
     * 对账金标准：{@code docs/参考服日志_构思技艺.txt} 第 43 行。
     */
    static String gsBgStartLine() {
        return "篮球生成器（" + GS_BG_ID + "）已加载 · 放下后上方 " + JsNum.str(GS_BG_UP)
                + " 格生成篮球 / **每台只有 1 颗球**（多人抢同一颗，捡走不再补第二颗）"
                + "/ 只有**停着的球**捡得走（抢不走别人手上的）/ 超出 "
                + JsNum.str(GS_BG_RANGE) + " 格自动送回 / 挖掉方块球全部消失";
    }
}
