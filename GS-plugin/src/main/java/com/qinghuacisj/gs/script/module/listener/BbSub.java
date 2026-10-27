package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code gs_listener.js} 的「篮球共用引擎」子系统
 * （原脚本第 <b>10952~12552</b> 行，顶层入口 {@code gsBbStart()}）。
 *
 * <h2>★★★ 先读这段：这个模块现在只剩「共用引擎」一个身份</h2>
 * 顶层常量 {@code GS_BB_ID = 'GS_LAWYER_LETTER'}（＝「你等着收我的律师函吧」）指向的
 * 那件物品 <b>第三十六批已整件删除</b>（{@code items.yml} / {@code groups.yml} 里都没有它），
 * 所以 {@link #gsBbIs(ItemStack)} <b>恒为 false</b>。原作者的注释原文：
 * <pre>
 *   `GS_LAWYER_LETTER` 那件物品**第三十六批已整件删除** ⇒ gsBbIs() 恒为 false，
 *   本模块**只剩「篮球共用引擎」这一个身份**：
 *     「篮球生成器」（GS_BASKET_GEN · gsBg*）复用它的投篮物理 / 落地弹跳 /
 *     展示实体渲染 / 运球血条 / 副手钥匙球那套事件。
 * </pre>
 * <p>★★ <b>逐字保留</b> {@code GS_BB_ID = "GS_LAWYER_LETTER"}：这是原作者留下的历史包袱
 * （{@code BB} = <b>BasketBall</b>，不是「律师函」的缩写）。旧存档里已经发出去的球物品
 * 还要靠它认出来，所以<b>不要「顺手修正」</b>。启动日志里那两行带「律师函」字样的文本
 * 同理 —— 而且它们被 {@code QHCGS\_check\_doc_final.py} / {@code _doc_fix3.py} /
 * {@code _doc_sec.py} / {@code _doc_archive.py} 四个检查脚本<b>逐字断言</b>，改一个字就挂测试。
 *
 * <p>于是本模块的<b>活代码</b>只有：
 * <ol>
 *   <li>共享引擎函数，被 {@link BgSub} 直接复用：{@link #gsBbShootState} /
 *       {@link #gsBbTickShot} / {@link #gsBbDraw} / {@link #gsBbEntDrop} /
 *       {@link #gsBbFloor} / {@link #gsBbFloorSweep} / {@link #gsBbWallSweep} /
 *       {@link #gsBbSolid} / {@link #gsBbBlockAt} / {@link #gsBbHeadItem} /
 *       {@link #gsBbEntSpawn} / {@link #gsBbEntSync} / {@link #gsBbIsBall} /
 *       {@link #gsBbIsEmpty} / {@link #gsBbSpark} …</li>
 *   <li>心跳与世代号（{@link #gsBbGenBump} / {@link #gsBbGenNow} / {@link #gsBbPulse}
 *       里的 {@code mine} 判定）—— 这是 {@code /gs reload} 后让旧任务自终止的唯一手段。</li>
 *   <li>每刻的玩家循环（{@link #gsBbPlayerTick}）。因为 {@code worn} 恒为 false，
 *       它实际只做一件事：{@link #gsBbBarDrop}（幂等，第二条起是空操作）。</li>
 * </ol>
 * ★ 这些「恒不成立」的分支<b>一律逐字保留</b>：它们是可观察行为的定义，
 * 而且哪一天 {@code GS_LAWYER_LETTER} 被重新加回 yml，这段代码要能直接跑起来。
 *
 * <h2>★★★ 逐字保留的两处 JS 数值语义</h2>
 * <ol>
 *   <li><b>日志行里的数字</b>全部走 {@link JsNum#str(double)} / {@link JsNum#toFixed}。
 *       例：{@code GS_BB_DROP_R = 2.0} 在 JS 里拼出来是 {@code "2"}（<b>不是</b> {@code "2.0"}），
 *       {@code GS_BB_BOUNCE_UP = 0.30} 是 {@code "0.3"}。参考服日志
 *       （{@code docs/参考服日志_构思技艺.txt} 第 42 行）逐字印证了这一点。
 *       直接用 {@code Double.toString} / {@code String.format} 会写出<b>不一样的日志行</b>。</li>
 *   <li><b>★★ 时间戳上的 {@code | 0}（ToInt32 回绕）</b> —— 见下面「时间戳陷阱」一节。</li>
 * </ol>
 *
 * <h2>★★★ 时间戳陷阱（本模块最容易移植错的地方）</h2>
 * 原脚本有 4 处把<b>毫秒时间戳</b>先做 {@code | 0} 再参与比较/计算：
 * <pre>
 *   gsBbShootState : if ((now - (st.shotAt  | 0)) &lt; GS_BB_SHOT_CD)  return false;   // 350ms 冷却
 *   gsBbTickRest   : if ((gsMsNow() - (st.touched | 0)) &lt; GS_BB_REBIND_CD) return; // 300ms 静默
 *   gsBbPulse      : if ((nowM - (GS_BB_SWEEP_AT | 0)) &gt;= 2000) { ... }            // 每 2 秒扫背包
 *   gsBgTryShoot   : if ((now - (GS_BG_CLICK_AT[u] | 0)) &lt; 120) return false;     // 120ms 去抖
 *   gsKlHint       : if ((now - (GS_KL_HINT_AT[u] | 0)) &lt; GS_KL_HINT_MS) return;  // 2.5 秒去抖
 *   gsKlPulse      : if ((now - (GS_KL_SWEEP_AT | 0)) &gt;= GS_KL_SWEEP_MS) { ... }  // 每 0.5 秒清残留
 * </pre>
 * {@code gsMsNow()} 是 {@code Instant.now().toEpochMilli()}（≈ 1.76e12）。JS 的 {@code | 0}
 * 是 {@code ToInt32}：先对 2³² 取模再当有符号 32 位解释 ⇒ 时间戳被<b>回绕</b>成
 * 一个 ±2.1e9 量级的数（例：{@code 1759999999999 | 0 === -936591361}）。
 * 于是 {@code now - wrapped} ≈ 1.76e12，<b>永远大于</b>那些几百毫秒的阈值
 * ⇒ <b>这 6 处「冷却 / 去抖 / 静默」在真 JS 里全部恒不生效</b>：
 * <ul>
 *   <li>投篮的 350ms 冷却不生效（实际节流来自 {@link #gsBbClickOnce} 的 80ms 去抖，
 *       那一处<b>没有</b> {@code | 0}，所以它是真的生效的）；</li>
 *   <li>落地后 300ms「不许重新触碰绑定」不生效 ⇒ 球一停就能立刻被捡起；</li>
 *   <li>{@code gsBbPulse} 的背包兜底清扫<b>每刻都跑</b>（不是每 2 秒）；</li>
 *   <li>钥匙锁的 2.5 秒提示去抖、0.5 秒残留清扫周期同样不生效（每刻都跑）。</li>
 * </ul>
 * <p><b>Java 侧用 {@code (int) 长整型} 复刻</b> —— Java 的 {@code (int)} 窄化转换
 * 就是 {@code ToInt32}（截低 32 位、按有符号解释），与 JS 的 {@code | 0}` <b>逐位等价</b>。
 * ★ 千万不要「顺手改成 {@code long} 直接比较」—— 那会凭空造出一个原脚本<b>没有</b>的冷却，
 * 是<b>可观察的行为差异</b>（探针 D 段有正反两向断言钉住这一点）。
 * <p>★ 唯一<b>没有</b> {@code | 0} 的时间戳是 {@link #gsBbTickBound} 里的 {@code st.t0}
 * 和 {@link BgSub#gsBgTickBound} 里的 {@code b.tBoundAt}：前者是运球相位的基准
 * （作者本意就是「绑定时相位归零」），后者反而<b>带了</b> {@code | 0} ⇒ 生成器那颗球的
 * 运球相位是任意的（但 {@code |cos|} 的<b>周期</b>不变，观感一致）。
 *
 * <h2>★ 监听器注册：与原脚本的差异</h2>
 * 原脚本在 {@code gsBbRegisterSync()} 里用 {@code pm.registerEvent(...)} <b>自己注册</b>，
 * <b>每次 {@code /gs reload} 都会再注册一遍</b>（真实缺陷：监听器累积、右键会被处理 N 次）。
 * Java 侧改成 {@link ListenerImpl}（{@code org.bukkit.event.Listener}）+ {@code registerEvents}，
 * 用 {@link #GS_BB_REGISTERED} 闩锁保证<b>只注册一次</b>。
 * <p>★ <b>优先级与 ignoreCancelled 逐处核对</b>：原脚本 {@code registerEvent} 只传了
 * 4 个参数（{@code eventClass, plugin, priority, executor}）⇒ {@code ignoreCancelled}
 * 取<b>默认值 false</b>；Java {@code @EventHandler} 的默认也是 false ⇒ 两边一致，
 * <b>不要</b>写成 {@code ignoreCancelled = true}。优先级是 {@code Priority.HIGHEST}。
 *
 * <h2>★ 死代码清单（逐条，均已在 Java 里等价处理）</h2>
 * <ol>
 *   <li>{@code gsBbCallStatic(cls, method, argTypes, args)} —— 纯 GraalJS 反射垫片，
 *       存在的唯一理由是「GraalJS 里 {@code Bukkit.createProfile} 有重载歧义，
 *       得靠 {@code getMethod(name, Class[])} 手工消歧」。Java 的静态解析天然无歧义
 *       ⇒ 整个函数是死代码，<b>不移植</b>（探针的完备性检查把它登记在 DEAD 名单里）。</li>
 *   <li>{@code gsBbSolid} 里 {@code var pm = t.isPassable();} 那一段 ——
 *       <b>实测 1.21.11 的 {@code org.bukkit.Material} 没有 {@code isPassable()} 方法</b>
 *       （用真 paper-api 编译验证，不是 {@code javap}），所以那段在真 JS 里也是
 *       <b>恒抛异常被吞掉</b>的死代码。Java 里删掉该段（行为一致）。</li>
 *   <li>{@code gsBbSolid} 的尾巴（{@code gsBbNameSolid} + {@code t.isSolid()} 兜底）——
 *       {@code Block#isPassable()} 存在且必然返回 ⇒ 第一个分支<b>无条件 return</b>，
 *       尾巴不可达。★ 但 Java 侧<b>保留</b>它作为 catch 兜底（3 行、无副作用），
 *       因为「哪一天 Paper 改了 {@code Block#isPassable()}」时它是唯一的降级路径。</li>
 *   <li>{@code gsBbEntSpawn} 里 {@code ET.valueOf('ITEM_DISPLAY')} 与
 *       {@code Java.type('...ItemDisplay$ItemDisplayTransform')} 的反射查找 ——
 *       1.21.11 必然成功（编译验证）⇒ 降级分支走不到。Java 里写成直接引用 + 保留
 *       try/catch 结构。</li>
 *   <li>{@code gsBbBallNew} / {@code gsBbHeadItem} 里的 {@code Material[...]} →
 *       {@code Material.valueOf(...)} 二次尝试：{@code Material} 是 enum，
 *       {@code Material['ACACIA_BUTTON']} 就是那个静态字段 ⇒ 与 {@code valueOf} 取到同一批名字，
 *       合并成一次 {@code valueOf}（与 {@link AuraSub} 里 {@code Particle} 的处理同理）。</li>
 *   <li>{@code gsBbBarColor} 的 {@code C[names[i]]} 分支、{@code gsBbSpellType} 的
 *       {@code P[names[i]]} 分支：同上，enum 静态字段 ≡ {@code valueOf}，已合并。</li>
 *   <li>{@code gsBbClear} 的 catch 里 {@code GS_BB_ST[u] = null} 兜底：JS 的
 *       {@code delete} 可能抛，Java 的 {@code Map.remove} 不会 ⇒ 不可达，不照抄。</li>
 *   <li>三处 {@code reg()} 里的 {@code JavaAdapter} 退路 —— GraalJS 25.1.3
 *       <b>没有 {@code JavaAdapter}</b>（本项目已实测）⇒ 恒失败，死代码。
 *       ★ 但原脚本<b>忽略</b> {@code reg()} 的返回值 ⇒ Java 侧同样「注册失败也继续往下走、
 *       照样置闩锁、照样打成功日志」，这一点<b>必须保留</b>（见 {@link #gsBbRegisterSync}）。</li>
 * </ol>
 */
public final class BbSub {

    private final ListenerCore core;

    public BbSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsBbStart();} 调用（原脚本第 12552 行）。 */
    public void start() {
        gsBbStart();
    }

    // ======================================================================
    // 顶层常量（原脚本 10956~11077 行）—— 字段名 = 原脚本 var 名，供常量对账探针反射比对
    // ======================================================================

    /**
     * ★★★ <b>逐字保留</b>：{@code GS_BB_ID = 'GS_LAWYER_LETTER'} 指向的物品
     * <b>已被删除</b>（见类注释）。{@code BB} = BasketBall。不要「顺手修正」。
     */
    static final String GS_BB_ID = "GS_LAWYER_LETTER";

    /** ★ 第二十四批：球物品材质 = 金合欢木按钮（不是给玩家的道具，只是「骗客户端发包」的钥匙）。 */
    static final String GS_BB_BALL_MAT = "ACACIA_BUTTON";
    /** 球物品名字（白色，名字就叫「篮球」）。 */
    static final String GS_BB_BALL_NAME = "篮球";
    /**
     * 空手「挥手」兜底开关（第二十二批的老办法）。
     * ★ 副手有球之后右键真的通了 ⇒ 想避免「左键攻击顺手把球扔出去」就把这里改 false。
     */
    static final boolean GS_BB_SWING_FALLBACK = true;

    /** 球半径（格）—— 直径 0.48 ≈「头颅」大小（玩家头颅是 0.5 格立方）。 */
    static final double GS_BB_R = 0.24;
    /** 球面采样点数。 */
    static final int GS_BB_DOTS = 26;
    /** 单个尘埃粒子的大小（{@code DustOptions} 的 size）。 */
    static final double GS_BB_DOT_SIZE = 0.8;
    /** 自转（弧度/刻）。 */
    static final double GS_BB_SPIN = 0.06;
    /** 橙（篮球主色）。 */
    static final int[] GS_BB_ORANGE = {255, 140, 0};
    /** 黑（篮球线条）。 */
    static final int[] GS_BB_BLACK = {14, 14, 14};

    /** 生成在玩家前方 2 格。 */
    static final double GS_BB_SPAWN_FWD = 2.0;
    /** 生成高度（离地）。 */
    static final double GS_BB_SPAWN_UP = 0.9;
    /** idle 球离玩家超过 6 格 ⇒ 重新放回面前。 */
    static final double GS_BB_LEASH = 6.0;
    /** 触碰半径（水平，格）。 */
    static final double GS_BB_TOUCH = 1.4;
    /** 触碰高度差（格）。 */
    static final double GS_BB_TOUCH_V = 1.6;

    /** ★ 第二十四批：绑定（运球）持续 <b>10 秒</b>（原 6 秒）。 */
    static final long GS_BB_BIND_MS = 10000;
    /** 地面 ↔ 右手 一个来回的时长（秒）。 */
    static final double GS_BB_BOUNCE_S = 0.45;
    /** 右手高度（玩家脚底起算）。 */
    static final double GS_BB_HAND_UP = 1.15;
    /** 右手横向偏移（格）。 */
    static final double GS_BB_HAND_SIDE = 0.42;
    /** ★ 第二十四批：往前挪<b>半个身位</b>（格）—— 观感是「人在身前运球」。 */
    static final double GS_BB_HAND_FWD = 0.5;
    /** 超时落点：附近 2 格内。 */
    static final double GS_BB_DROP_R = 2.0;
    /** 落地后静默 0.3 秒才允许再次触碰绑定。★ 见类注释「时间戳陷阱」：真 JS 里恒不生效。 */
    static final long GS_BB_REBIND_CD = 300;

    /** ★ 血条正文：<b>就这两个字</b>（不带数字、不带层数、不带后缀）。 */
    static final String GS_BB_BAR_TEXT = "运球";
    /** 标题颜色码（§6 = 金橙）。 */
    static final String GS_BB_BAR_COLOR = "§6";

    /** ★ 投篮初速度（格/刻）—— 沿<b>准星方向</b>。 */
    static final double GS_BB_SHOT_SPD = 1.25;
    /** 出手点：沿准星<b>水平</b>方向前移的格数。 */
    static final double GS_BB_SHOT_FWD = 0.6;
    /** 出手后的<b>直线段</b>刻数（这期间不受重力）。 */
    static final int GS_BB_STRAIGHT = 12;
    /** 直线段之后的重力（格/刻²）⇒ 球最终落回地面。 */
    static final double GS_BB_SHOT_GRAV = 0.05;
    /** 撞墙后垂直下坠的初速度。 */
    static final double GS_BB_FALL_FAST = -0.55;
    /** 水平空气阻力。 */
    static final double GS_BB_DRAG = 0.998;
    /** 投篮冷却（毫秒）。★ 见类注释「时间戳陷阱」：真 JS 里恒不生效。 */
    static final long GS_BB_SHOT_CD = 350;
    /** 飞行刻数兜底（防卡死）。 */
    static final int GS_BB_MAX_TICKS = 400;
    /** 撞墙检测的采样步数。 */
    static final int GS_BB_SWEEP_N = 4;

    /** 落地后弹几下（0 = 一落地就停，回到老样子）。 */
    static final int GS_BB_BOUNCES = 2;
    /** 第一次弹起的竖直初速（格/刻，约弹起 1 格）。 */
    static final double GS_BB_BOUNCE_UP = 0.30;
    /** 每弹一次，弹起高度乘这个数（第二下约 0.4 格）。 */
    static final double GS_BB_BOUNCE_DECAY = 0.60;
    /** 落地时水平速度保留比例 ⇒「往前一点点」。 */
    static final double GS_BB_BOUNCE_KEEP = 0.35;
    /** 前弹的水平速度上限（格/刻），别弹太远。 */
    static final double GS_BB_BOUNCE_MAX = 0.25;

    /** true = 用展示实体渲染；false = 回到旧的尘埃粒子球。 */
    static final boolean GS_BB_USE_ENT = true;
    /** 每几刻同步一次位置（1 = 每刻，与原版实体同频）。 */
    static final int GS_BB_ENT_EVERY = 1;
    /** 客户端<b>位置</b>插值刻数（Paper {@code Display#setTeleportDuration}）。 */
    static final int GS_BB_ENT_TPDUR = 0;
    /** 客户端<b>变换</b>插值刻数（{@code setInterpolationDuration}）。 */
    static final int GS_BB_ENT_INTDUR = 0;
    /** 球的影子强度（0 = 不投影）。 */
    static final double GS_BB_ENT_SHADOW = 0.0;

    /** 头颅皮肤的 profile 名字。 */
    static final String GS_BB_HEAD_NAME = "GS_Basketball";
    /** = 原物品 profile 里那四个 int。 */
    static final String GS_BB_HEAD_UUID = "8a04ed5c-bdf5-3965-be39-a8de9435927a";
    /** 头颅皮肤的 textures 属性值（base64）。 */
    static final String GS_BB_HEAD_TEX =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUv"
            + "ZWRmODQ3MTVhNjRkYzQ1NTg2ZjdhNjA3OWY4ZTQ5YTk0NzdjMGZlOTY1ODliNGNmZDcxY2JhMzIyNTRhYzgifX19";
    /** 同一个皮肤的 URL 形式（Bukkit 的 {@code PlayerTextures#setSkin} 走这条）。 */
    static final String GS_BB_HEAD_URL =
            "http://textures.minecraft.net/texture/edf84715a64dc45586f7a6079f8e49a9477c0fe96589b4cfd71cba32254ac8";

    // ---- 球体碰撞：按材质名兜底（原脚本 11780~11783 行）----

    /**
     * ★★ 玻璃板（{@code GLASS_PANE}）这类「薄方块」不能只信 {@code isPassable()}：
     * 它的碰撞箱是<b>中心十字</b>，一旦被判成「可通行」球就会直接穿过去（用户实测）。
     */
    static final String[] GS_BB_SOLID_KEYS = {
            "PANE", "BARS", "CHAIN", "FENCE", "WALL", "SLAB", "STAIRS",
            "DOOR", "TRAPDOOR", "GLASS"};
    /** 命中上面关键词、但<b>不该挡球</b>的例外（墙上的告示牌 / 火把 / 旗帜是装饰，不是墙）。 */
    static final String[] GS_BB_SOFT_EXCEPT = {"SIGN", "TORCH", "BANNER", "HANGING", "CORAL", "FAN"};

    // ---- 原脚本 11103~11114 行的「懒加载缓存」（★ Java 侧不移植，见类注释死代码清单）----
    //   GS_BB_MAT_C / GS_BB_ISC_C : 原脚本里是 Java.type('...Material'/'...ItemStack') 的缓存，
    //                               初值 null、第一次用到时才填。Java 直接引用类，不需要缓存。
    //   GS_BB_FEC                  : 原脚本里是 Java.type('org.bukkit.Color') 的缓存，同上。
    //   ★ 探针把这 3 个登记在 JS_ONLY 名单里，并断言「JS 独有变量恰好就是这 3 个」。

    /** 原脚本 {@code GS_BB_PDT = Java.type('...PersistentDataType')}（★ 这个是真常量，JS 里初值就非 null）。 */
    static final Class<?> GS_BB_PDT = PersistentDataType.class;

    /** 心跳世代号（存在第一个世界的 PDC 上 ⇒ {@code /gs reload} 后旧任务能自终止）。 */
    static final NamespacedKey GS_BB_K_GEN = NamespacedKey.fromString("gs:bb_gen");
    /** ★ 副手那颗临时球的标记（与生成器的 {@code gs:bg_ball} 分开，两套球互不串台）。 */
    static final NamespacedKey GS_BB_K_BALL = NamespacedKey.fromString("gs:bb_ball");
    /**
     * ★★ 「这是我们的球」的实体标记（原脚本 11100 行，定义在篮球段里、被孤儿球清扫复用）。
     * 实体 PDC 会跟着实体一起写盘 ⇒ 万一它真被存进区块存档，下次启动也能一眼认出来删掉。
     */
    static final NamespacedKey GS_ENT_TAG = NamespacedKey.fromString("gs:ent_ball");

    // ======================================================================
    // 运行期状态（原脚本 11078~11114 行）
    // ======================================================================

    /** 造好的头颅 ItemStack（<b>只造一次</b>后缓存）。 */
    ItemStack GS_BB_HEAD_ITEM = null;
    /** null = 还没试过；true = 造得出来；false = 已知造不出来（别再试）。 */
    Boolean GS_BB_ENT_OK = null;
    /** uuid 字符串 → 球状态。 */
    final Map<String, Ball> GS_BB_ST = new LinkedHashMap<>();
    /** 同步监听器是否注册成功（Java 侧是幂等闩锁）。 */
    boolean GS_BB_REGISTERED = false;
    /** ★ 自己的心跳世代号（-1 = 还没拿到）。 */
    int GS_BB_GEN = -1;
    /** 右键去抖（毫秒）。 */
    final Map<String, Long> GS_BB_CLICK_AT = new LinkedHashMap<>();
    /** 上次兜底扫背包的时间戳。 */
    long GS_BB_SWEEP_AT = 0;
    /** uuid 字符串 → 血条记录。 */
    final Map<String, BbBarRec> GS_BB_BARS = new LinkedHashMap<>();
    /** 缓存下来的法术粒子类型（{@code ENTITY_EFFECT} / {@code SPELL_MOB}）。 */
    Particle GS_BB_SPELL = null;
    /** 已经试过查找法术粒子了。 */
    boolean GS_BB_SPELL_TRIED = false;

    // ======================================================================
    // 内部数据结构
    // ======================================================================

    /**
     * 一颗球的全部状态。
     *
     * <p>★★ 原脚本里「律师函的球」（{@code GS_BB_ST[u]}）与「生成器的球」
     * （{@code GS_BG_NODES[k].balls[i]}）是<b>两个不同形状的 JS 字面量对象</b>，
     * 但 {@code gsBbTickShot} / {@code gsBbDraw} / {@code gsBbLand} / {@code gsBbEntSpawn}
     * 这些函数是<b>两边共用</b>的 —— JS 的鸭子类型让「缺字段 = undefined」自然工作
     * （{@code undefined | 0 === 0}、{@code undefined || 0 === 0}）。
     * Java 里合并成<b>同一个类</b>，缺字段 = 默认值，语义完全一致。
     * <ul>
     *   <li>律师函球额外用：{@code t0} / {@code until} / {@code touched} / {@code heldBall}</li>
     *   <li>生成器球额外用：{@code owner} / {@code node} / {@code tBoundAt}</li>
     * </ul>
     */
    static final class Ball {
        /** 'idle' / 'bound' / 'shot' / 'rest'。 */
        String mode = "idle";
        World w = null;
        double x, y, z;
        double vx, vy, vz;
        /** 绑定起始时间戳（律师函球；★ 这里<b>没有</b> {@code | 0}）。 */
        long t0 = 0;
        /** 绑定到期时间戳（律师函球）。 */
        long until = 0;
        /** 自转累加（弧度）。 */
        double spin = 0.0;
        /** 本次飞行的刻数。 */
        int life = 0;
        /** 上次投篮的时间戳（毫秒）。★ 使用处带 {@code | 0} 回绕，见类注释。 */
        long shotAt = 0;
        /** 上次「停住」的时间戳（毫秒）。★ 使用处带 {@code | 0} 回绕。 */
        long touched = 0;
        /** 副手那颗钥匙球是不是我们放的。 */
        boolean heldBall = false;
        /** 还剩几次弹跳。 */
        int bnc = 0;
        /** 本次飞行是否撞过墙（撞过 ⇒ 落地原地弹）。 */
        boolean hitWall = false;
        /** 渲染用的展示实体。 */
        ItemDisplay ent = null;
        /** 实体同步节流计数。 */
        int entTick = 0;
        /** 出手高度（兜底落点）。 */
        double sy0 = 0.0;

        // ---- 生成器球专用 ----
        /** 绑定时那个玩家的 uuid 字符串。 */
        String owner = null;
        /** 属于哪台生成器。 */
        BgSub.BgNode node = null;
        /** 绑定起始时间戳（生成器球）。★ 使用处<b>带</b> {@code | 0} 回绕。 */
        long tBoundAt = 0;
    }

    /**
     * 一条运球血条记录。
     *
     * <p>★ 原脚本用的是 {@code { b: bar, p: p, on: false }}。
     * {@link ListenerCore.BarRec} 只有 {@code p} 与 {@code b}，<b>没有 {@code on}</b>，
     * 而 {@code on} 是 {@code gsBbBarHide} 的判据 ⇒ 本类自带一个三字段记录，
     * 并把 {@code gsBarTblDrop} 的函数体就地内联（见 {@link #gsBbBarDrop}）。
     * <p>★★ 用<b>自己的一张表</b> {@code GS_BB_BARS}（不碰蚀月的 {@code GS_EC_BARS}）：
     * 同一玩家身上可以同时挂多条 bossbar（叠着显示），复用会把蚀月那条顶掉。
     */
    static final class BbBarRec {
        BossBar b = null;
        Player p = null;
        boolean on = false;
    }

    // ======================================================================
    // 副手「篮球」物品（原脚本 11106~11177 行）
    // ======================================================================

    /** {@code gsBbIs(item)} —— 是不是「你等着收我的律师函吧」。★ 物品已删除 ⇒ 恒 false。 */
    static boolean gsBbIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_BB_ID);
    }

    /**
     * {@code gsBbBallNew()} —— 造一颗副手用的「篮球」。
     *
     * <p>★ 名字就是「篮球」（白色），材质 {@code ACACIA_BUTTON}；
     * 打上 PDC 标记 {@code gs:bb_ball} 以便认领。
     */
    ItemStack gsBbBallNew() {
        try {
            Material mat = null;
            // ★ 原脚本先试 Material.valueOf(name)、再试 Material.getMaterial(name)
            //   （见 11120~11121 行）。两个都保留 —— getMaterial 对旧名/别名更宽容。
            try {
                mat = Material.valueOf(GS_BB_BALL_MAT);
            } catch (Throwable e) {
                mat = null;
            }
            if (mat == null) {
                try {
                    mat = Material.getMaterial(GS_BB_BALL_MAT);
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
                    meta.setDisplayName("§f" + GS_BB_BALL_NAME);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    if (GS_BB_PDT != null && GS_BB_K_BALL != null) {
                        meta.getPersistentDataContainer().set(GS_BB_K_BALL, PersistentDataType.STRING, GS_BB_BALL_NAME);
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

    /**
     * {@code gsBbIsBall(item)} —— 是不是我们发的那颗球。
     *
     * <p>判据 ①：PDC {@code gs:bb_ball} == {@code "篮球"}；
     * 判据 ②（兜底）：显示名含「篮球」<b>且</b>材质 == {@code ACACIA_BUTTON}。
     */
    boolean gsBbIsBall(ItemStack item) {
        try {
            if (item == null) {
                return false;
            }
            ItemMeta meta = null;
            try {
                meta = item.getItemMeta();
            } catch (Throwable e) {
                meta = null;
            }
            try {
                if (meta != null && GS_BB_PDT != null && GS_BB_K_BALL != null) {
                    String v = meta.getPersistentDataContainer().get(GS_BB_K_BALL, PersistentDataType.STRING);
                    if (v != null && v.equals(GS_BB_BALL_NAME)) {
                        return true;
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                if (meta == null) {
                    return false;
                }
                String dn = "";
                try {
                    dn = String.valueOf(meta.getDisplayName());
                } catch (Throwable e) {
                    dn = "";
                }
                if (dn.indexOf(GS_BB_BALL_NAME) < 0) {
                    return false;
                }
                Material t = item.getType();
                String n = (t == null) ? "" : t.name();
                return n.equals(GS_BB_BALL_MAT);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /** {@code gsBbIsEmpty(item)} —— 主手 / 副手是不是空着（AIR / CAVE_AIR / VOID_AIR）。 */
    static boolean gsBbIsEmpty(ItemStack item) {
        try {
            if (item == null) {
                return true;
            }
            Material t = item.getType();
            if (t == null) {
                return true;
            }
            String n = t.name();
            return n.equals("AIR") || n.equals("CAVE_AIR") || n.equals("VOID_AIR");
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 副手钥匙球（原脚本 11178~11208 行）
    // ======================================================================

    /**
     * {@code gsBbHandOn(p, st)} —— 绑定期<b>每一刻</b>都把「篮球」放回副手。
     *
     * <p>★ 只在<b>副手空着</b>时放；副手本来有东西 ⇒ 原版本来就会发包 ⇒ <b>绝不动玩家的物品</b>。
     * 每一刻都会被带上一次（见 {@link #gsBbPlayerTick}）⇒ 玩家把球挪走 / 扔了，下一刻立刻补回来。
     */
    void gsBbHandOn(Player p, Ball st) {
        try {
            if (p == null || st == null) {
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

            if (gsBbIsBall(cur)) {
                st.heldBall = true;
                return;                       // 副手已经是我们那颗 ⇒ 复用
            }
            if (st.heldBall) {
                st.heldBall = false;          // 记账过期（球被挪走了）⇒ 重新放
            }

            if (!gsBbIsEmpty(cur)) {
                return;                       // ★ 副手是玩家自己的东西 ⇒ 不碰它
            }
            ItemStack ball = gsBbBallNew();
            if (ball == null) {
                return;
            }
            try {
                inv.setItemInOffHand(ball);
                st.heldBall = true;
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 【离开 bound】{@code gsBbHandOff(p, st)} —— 把副手那颗「篮球」<b>收走</b>。
     * 收的只是我们自己发的临时球 ⇒ 不会丢玩家任何东西。
     */
    void gsBbHandOff(Player p, Ball st) {
        try {
            if (st == null || !st.heldBall) {
                return;
            }
            st.heldBall = false;
            PlayerInventory inv = (p != null) ? p.getInventory() : null;
            if (inv == null) {
                return;
            }
            ItemStack cur = null;
            try {
                cur = inv.getItemInOffHand();
            } catch (Throwable e) {
                cur = null;
            }
            if (gsBbIsBall(cur)) {
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

    /**
     * 【兜底清扫】{@code gsBbSweep(p)} —— 扫一遍在线玩家的<b>整个背包</b>：
     * 只要发现「我们的篮球」而它<b>不该在那儿</b>（不在绑定期 / 不在副手）⇒ 直接清掉。
     * 防的是「玩家把球拖进背包 / 扔了又捡回来 / 脚本重载导致状态丢失」这几种残留。
     *
     * <p>★ 原脚本注释写「每 2 秒扫一遍」，但 {@link #gsBbPulse} 里的时间比较带 {@code | 0}
     * 回绕 ⇒ 真 JS 里<b>每刻都跑</b>。逐字保留（见类注释「时间戳陷阱」）。
     */
    void gsBbSweep(Player p) {
        try {
            if (p == null) {
                return;
            }
            PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return;
            }
            Ball st = GS_BB_ST.get(ListenerCore.uid(p));
            boolean bound = (st != null && "bound".equals(st.mode));
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
                if (!gsBbIsBall(it)) {
                    continue;
                }
                if (i == 40 && bound) {
                    continue;                 // 40 = 副手槽：绑定期那颗是「正常」的，留着
                }
                try {
                    inv.setItem(i, null);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 运球倒计时血条（原脚本 11233~11312 行）
    // ======================================================================

    /**
     * {@code gsBbBarColor()} —— ★ 原版 {@code BarColor} <b>没有 ORANGE</b>
     * ⇒ ORANGE → GOLD → YELLOW 依次试，落到 YELLOW。
     */
    static BarColor gsBbBarColor() {
        try {
            String[] names = {"ORANGE", "GOLD", "YELLOW"};
            for (String name : names) {
                // ★ 原脚本对每个名字先试 C[name]、再试 C.valueOf(name) —— enum 下两者同义，合并。
                try {
                    BarColor c = BarColor.valueOf(name);
                    if (c != null) {
                        return c;
                    }
                } catch (Throwable e) {
                    // 试下一个
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsBbBarStyle()} —— 优先 {@code SEGMENTED_12}，退化到 {@code SOLID}。 */
    static BarStyle gsBbBarStyle() {
        try {
            try {
                BarStyle s = BarStyle.SEGMENTED_12;
                if (s != null) {
                    return s;
                }
            } catch (Throwable e) {
                // 试下一个
            }
            try {
                return BarStyle.valueOf("SOLID");
            } catch (Throwable e) {
                // 放弃
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** 血条标题：<b>只有「运球」两个字</b>（§6 只是颜色码，不显示出来；不带任何数字）。 */
    static String gsBbBarTitle() {
        return GS_BB_BAR_COLOR + GS_BB_BAR_TEXT;
    }

    /**
     * {@code gsBbBarOf(p)} —— 取（必要时创建）这个玩家的运球血条。
     *
     * <p>★ 标题一律<b>纯字符串</b>（§6 颜色码 + 「运球」），<b>绝不能塞 Adventure Component</b>
     * —— 线上事故就是那么来的（会被 toString 成 {@code TextImpl{…}} 一长串 dump）。
     */
    BossBar gsBbBarOf(Player p) {
        try {
            String u = ListenerCore.uid(p);
            BbBarRec rec = GS_BB_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsBbBarColor();
            BarStyle s = gsBbBarStyle();
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            try {
                // ★ 逐字用 getServer().createBossBar(...)（原脚本 11271 行就是它，
                //   不是 Bukkit.createBossBar —— 虽然两者等价）
                bar = Bukkit.getServer().createBossBar(gsBbBarTitle(), c, s);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                bar.setVisible(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                bar.addPlayer(p);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            BbBarRec nr = new BbBarRec();
            nr.b = bar;
            nr.p = p;
            nr.on = false;
            GS_BB_BARS.put(u, nr);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsBbBarShow(p, prog)} —— 显示 / 刷新。
     * {@code prog = 剩余时间 / GS_BB_BIND_MS}（10 秒 ⇒ 1.0，清零 ⇒ 0.0）。
     *
     * <p>★ 参数用 {@code Double} 而不是 {@code double}：原脚本有 {@code prog == null ⇒ 1.0}
     * 这一支，虽然现有调用点都不会传 null，但保留包装类型才能逐字对齐那段逻辑。
     */
    void gsBbBarShow(Player p, Double prog) {
        try {
            BossBar b = gsBbBarOf(p);
            if (b == null) {
                return;
            }
            double v = (prog == null) ? 1.0 : (prog * 1.0);
            if (!(v > 0)) {
                v = 0.0;
            }
            if (v > 1.0) {
                v = 1.0;
            }
            ListenerCore.gsBarTitleSet(b, gsBbBarTitle());
            try {
                b.setProgress(v);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                b.setVisible(true);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            BbBarRec rec = GS_BB_BARS.get(ListenerCore.uid(p));
            if (rec != null) {
                rec.on = true;
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBbBarHide(p)} —— 隐藏（不销毁记录，下次还能复用）。 */
    void gsBbBarHide(Player p) {
        try {
            BbBarRec rec = GS_BB_BARS.get(ListenerCore.uid(p));
            if (rec == null || rec.b == null) {
                return;
            }
            if (!rec.on) {
                return;
            }
            rec.on = false;
            try {
                rec.b.setVisible(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbBarDrop(p)} —— 彻底销毁这条血条。
     *
     * <p>★ 原脚本是 {@code gsBarTblDrop(GS_BB_BARS, p)}；这里把那个函数体<b>就地内联</b>，
     * 因为我们的记录多了一个 {@code on} 字段（{@link ListenerCore.BarRec} 装不下）。
     * 三步（{@code removeAll} → {@code setVisible(false)} → 删表项）与
     * {@link ListenerCore#gsBarTblDrop} 逐行一致。
     */
    void gsBbBarDrop(Player p) {
        try {
            String u = ListenerCore.uid(p);
            BbBarRec rec = GS_BB_BARS.get(u);
            if (rec == null) {
                return;
            }
            try {
                rec.b.removeAll();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                rec.b.setVisible(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            GS_BB_BARS.remove(u);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 粒子（原脚本 11314~11365 行）
    // ======================================================================

    /**
     * {@code gsBbSpellType()} —— 法术粒子类型（跨版本容错），查一次就缓存。
     * ★ {@code ENTITY_EFFECT} → {@code SPELL_MOB} 依次试（1.20.5 改名）。
     */
    Particle gsBbSpellType() {
        if (GS_BB_SPELL != null) {
            return GS_BB_SPELL;
        }
        if (GS_BB_SPELL_TRIED) {
            return null;
        }
        GS_BB_SPELL_TRIED = true;
        try {
            String[] names = {"ENTITY_EFFECT", "SPELL_MOB"};
            for (String name : names) {
                try {
                    Particle t = Particle.valueOf(name);
                    if (t != null) {
                        GS_BB_SPELL = t;
                        return t;
                    }
                } catch (Throwable e) {
                    // 试下一个
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsBbSpellSpark(...)} —— 法术粒子兜底（旧客户端的「有色小点」）。 */
    void gsBbSpellSpark(World world, double x, double y, double z, int[] rgb) {
        try {
            Particle pt = gsBbSpellType();
            if (pt == null || world == null) {
                return;
            }
            Color col = Color.fromRGB(rgb[0] & 255, rgb[1] & 255, rgb[2] & 255);
            world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, col);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbSpark(...)} —— 打一个「球面上的点」。
     *
     * <p>★★ 球<b>彻底不再用烟花粒子</b>：{@code FIREWORK}（烟花）的<b>精灵本身就很大</b>
     * （≈0.4 格）且带外散 ⇒ 球看起来比坐标上的球体外扩一圈。
     * 改用 <b>{@code DUST} + {@code DustOptions}</b>：颜色还是那两色（RGB 精确到分），
     * <b>大小由 {@code GS_BB_DOT_SIZE} 控制</b>。
     */
    void gsBbSpark(World world, double x, double y, double z, int[] rgb) {
        try {
            if (world == null) {
                return;
            }
            Particle pt = core.gsMsDustType();
            if (pt == null) {
                gsBbSpellSpark(world, x, y, z, rgb);
                return;
            }
            Particle.DustOptions d = ListenerCore.gsMsDustOpts(rgb[0], rgb[1], rgb[2], GS_BB_DOT_SIZE);
            // ★ 逐字照抄原脚本的顺序：先画，再判 d == null 走兜底
            //   （原脚本 11351~11352 行就是这么写的）
            ListenerCore.gsMsDust(world, pt, d, x, y, z);
            if (d == null) {
                gsBbSpellSpark(world, x, y, z, rgb);   // DustOptions 建不出来 ⇒ 走兜底
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 头颅皮肤（原脚本 11367~11455 行）
    // ======================================================================

    /**
     * {@code gsBbHeadItem()} —— 造一个「篮球皮肤」的玩家头颅（<b>只造一次</b>后缓存）。
     *
     * <p>★ 两条路：① Paper 的 {@code Bukkit.createProfile} + {@code ProfileProperty}；
     * ② Bukkit 的 {@code createPlayerProfile} + {@code PlayerTextures#setSkin}。
     * 先试 ①，失败再试 ②。
     */
    ItemStack gsBbHeadItem() {
        if (GS_BB_HEAD_ITEM != null) {
            return GS_BB_HEAD_ITEM;
        }
        try {
            Material mat = null;
            try {
                mat = Material.PLAYER_HEAD;
            } catch (Throwable e) {
                mat = null;
            }
            if (mat == null) {
                try {
                    mat = Material.valueOf("PLAYER_HEAD");
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
                boolean applied = gsBbApplyProfilePaper(meta);
                if (!applied) {
                    applied = gsBbApplyProfileBukkit(meta);
                }
                try {
                    st.setItemMeta(meta);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            GS_BB_HEAD_ITEM = st;
            return st;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 路径 ①：Paper 的 {@code com.destroystokyo.paper.profile.PlayerProfile}。
     *
     * <p>★ 原脚本走 {@code gsBbCallStatic('org.bukkit.Bukkit','createProfile', …)} 消歧；
     * Java 里 {@code Bukkit.createProfile(UUID, String)} 无歧义 ⇒ 直接调用。
     * <p>★ 注意 Paper 的 {@code createProfile} 返回
     * {@code com.destroystokyo.paper.profile.PlayerProfile}，而 {@code setOwnerProfile}
     * 收的是 {@code org.bukkit.profile.PlayerProfile} —— <b>两个不同的类型</b>，
     * 这正是原脚本要分成两条路的原因（用真 paper-api 编译验证过）。
     */
    static boolean gsBbApplyProfilePaper(ItemMeta meta) {
        try {
            UUID u = gsBbHeadUuid();
            com.destroystokyo.paper.profile.PlayerProfile prof = null;
            try {
                prof = Bukkit.createProfile(u, GS_BB_HEAD_NAME);
            } catch (Throwable e) {
                prof = null;
            }
            if (prof == null) {
                try {
                    prof = Bukkit.createProfile(u);
                } catch (Throwable e) {
                    prof = null;
                }
            }
            if (prof == null) {
                return false;
            }
            try {
                prof.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", GS_BB_HEAD_TEX));
            } catch (Throwable e) {
                return false;
            }
            if (meta instanceof SkullMeta) {
                SkullMeta sm = (SkullMeta) meta;
                try {
                    sm.setPlayerProfile(prof);
                    return true;
                } catch (Throwable e) {
                    // 试下一条
                }
                try {
                    sm.setOwnerProfile(prof);
                    return true;
                } catch (Throwable e) {
                    // 放弃
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /** 路径 ②：Bukkit 的 {@code createPlayerProfile} + {@code PlayerTextures#setSkin(URL)}。 */
    static boolean gsBbApplyProfileBukkit(ItemMeta meta) {
        try {
            UUID u = gsBbHeadUuid();
            org.bukkit.profile.PlayerProfile prof = null;
            try {
                prof = Bukkit.createPlayerProfile(u);
            } catch (Throwable e) {
                prof = null;
            }
            if (prof == null) {
                return false;
            }
            try {
                org.bukkit.profile.PlayerTextures tex = prof.getTextures();
                if (tex != null) {
                    tex.setSkin(new URL(GS_BB_HEAD_URL));
                }
            } catch (Throwable e) {
                return false;
            }
            if (meta instanceof SkullMeta) {
                SkullMeta sm = (SkullMeta) meta;
                try {
                    sm.setOwnerProfile(prof);
                    return true;
                } catch (Throwable e) {
                    // 试下一条
                }
                // ★★ 原脚本这里还有一次 `meta.setPlayerProfile(prof)` 重试 ——
                //    但 Paper 的 setPlayerProfile 收的是
                //    com.destroystokyo.paper.profile.PlayerProfile，而这条路上拿到的是
                //    org.bukkit.profile.PlayerProfile（**两个不同的类型**，真 paper-api
                //    编译验证过）⇒ 那次重试在真 JS 里也**必然抛异常被吞掉**，属死代码。
                //    Java 侧不照抄（否则编译不过）。
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /** {@code gsBbHeadUuid()} —— 解析固定的头颅 UUID；解析不出来就随机一个。 */
    static UUID gsBbHeadUuid() {
        try {
            return UUID.fromString(GS_BB_HEAD_UUID);
        } catch (Throwable e) {
            try {
                return UUID.randomUUID();
            } catch (Throwable e2) {
                return null;
            }
        }
    }

    // ======================================================================
    // 展示实体渲染（原脚本 11457~11566 行）
    // ======================================================================

    /**
     * {@code gsBbEntSpawn(st)} —— 生成一颗球的展示实体（1.19.4+ 的 {@code ItemDisplay}）。
     *
     * <p>★ 贴地不飘：不要重力、不无敌闪红、不被保存（防重启后残留一地篮球）。
     * ★ 顺手打上「这是我们的球」的实体 PDC 标记（见 {@link #GS_ENT_TAG}）。
     * ★ 渲染成「戴在头上」的居中样式（头颅方块正好 0.5 格，与球同尺寸）。
     */
    ItemDisplay gsBbEntSpawn(Ball st) {
        try {
            if (Boolean.FALSE.equals(GS_BB_ENT_OK)) {
                return null;                          // 已知造不出来 ⇒ 别再试
            }
            ItemStack item = gsBbHeadItem();
            if (item == null) {
                GS_BB_ENT_OK = Boolean.FALSE;
                return null;
            }

            EntityType type = null;
            try {
                type = EntityType.ITEM_DISPLAY;
            } catch (Throwable e) {
                type = null;
            }
            if (type == null) {
                // ★ 原脚本的降级查找：1.21.11 必然成功 ⇒ 走不到（编译验证）。保留结构。
                try {
                    type = EntityType.valueOf("ITEM_DISPLAY");
                } catch (Throwable e) {
                    type = null;
                }
            }
            if (type == null) {
                GS_BB_ENT_OK = Boolean.FALSE;
                return null;                          // 版本太老（< 1.19.4）⇒ 退回粒子
            }

            Location loc = ListenerCore.gsMsLoc(st.w, st.x, st.y, st.z);
            if (loc == null) {
                GS_BB_ENT_OK = Boolean.FALSE;
                return null;
            }

            Entity ent = null;
            try {
                ent = st.w.spawnEntity(loc, type);    // ★ 单重载，安全
            } catch (Throwable e) {
                ent = null;
            }
            if (ent == null) {
                GS_BB_ENT_OK = Boolean.FALSE;
                return null;
            }
            // ★ 我们 spawn 的就是 ITEM_DISPLAY ⇒ 这里的窄化是安全的（原脚本靠 JS 动态派发）。
            if (!(ent instanceof ItemDisplay)) {
                GS_BB_ENT_OK = Boolean.FALSE;
                return null;
            }
            ItemDisplay d = (ItemDisplay) ent;

            try {
                d.setItemStack(item);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                d.setGravity(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                d.setInvulnerable(true);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                d.setPersistent(false);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                d.setSilent(true);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                if (GS_ENT_TAG != null && GS_BB_PDT != null) {
                    d.getPersistentDataContainer().set(GS_ENT_TAG, PersistentDataType.INTEGER, 1);
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                try {
                    d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.HEAD);
                } catch (Throwable e) {
                    try {
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.valueOf("HEAD"));
                    } catch (Throwable e2) {
                        // 放弃
                    }
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ★ 低延迟：客户端插值（Paper 的 Display 扩展方法；拿不到就算了，不影响功能）
            if (GS_BB_ENT_TPDUR > 0) {
                try {
                    d.setTeleportDuration(GS_BB_ENT_TPDUR);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            if (GS_BB_ENT_INTDUR > 0) {
                try {
                    d.setInterpolationDelay(0);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    d.setInterpolationDuration(GS_BB_ENT_INTDUR);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            if (GS_BB_ENT_SHADOW > 0) {
                try {
                    d.setShadowRadius(0.3f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    d.setShadowStrength((float) GS_BB_ENT_SHADOW);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            GS_BB_ENT_OK = Boolean.TRUE;
            return d;
        } catch (Throwable e) {
            GS_BB_ENT_OK = Boolean.FALSE;
            return null;
        }
    }

    /**
     * {@code gsBbEntSync(st)} —— 每刻把实体挪到球的坐标
     * （yaw 用累加的自转角 ⇒ 看起来像篮球在转）。
     *
     * @return true =「已经用实体渲染了」；false =「没实体，请调用方退回粒子」
     */
    boolean gsBbEntSync(Ball st) {
        try {
            if (st == null || st.w == null) {
                return false;
            }
            if (Boolean.FALSE.equals(GS_BB_ENT_OK)) {
                return false;
            }

            ItemDisplay ent = st.ent;
            if (ent != null) {
                boolean alive = true;
                try {
                    alive = ent.isValid();
                } catch (Throwable e) {
                    alive = true;                     // 问不出来就当还活着
                }
                if (!alive) {
                    st.ent = null;
                    ent = null;                       // 区块卸载 / 被清 ⇒ 重建
                }
            }
            if (ent == null) {
                ent = gsBbEntSpawn(st);
                if (ent == null) {
                    return false;
                }
                st.ent = ent;
                st.entTick = 0;
            }

            // ★ 节流：每 GS_BB_ENT_EVERY 刻同步一次（默认 1 = 每刻；调大要配合 GS_BB_ENT_TPDUR 插值）
            st.entTick = st.entTick + 1;
            if (GS_BB_ENT_EVERY > 1 && (st.entTick % GS_BB_ENT_EVERY) != 0) {
                return true;
            }

            double yaw = st.spin * 57.29577951308232;    // 弧度 → 度
            yaw = yaw % 360.0;
            Location loc = ListenerCore.gsMsLoc(st.w, st.x, st.y, st.z);
            if (loc != null) {
                try {
                    loc.setYaw((float) yaw);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    loc.setPitch(0.0f);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                try {
                    ent.teleport(loc);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsBbEntDrop(st)} —— 删掉球的展示实体。
     *
     * <p>★ 先置空 {@code st.ent} 再删：即使 {@code remove()} 抛了，状态也不会留着一个死引用。
     * ★ 连删两次是原脚本的写法（第二次只在 {@code isValid()} 仍为 true 时才删）。
     */
    void gsBbEntDrop(Ball st) {
        try {
            if (st == null) {
                return;
            }
            ItemDisplay ent = st.ent;
            st.ent = null;
            if (ent == null) {
                return;
            }
            try {
                ent.remove();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                if (ent.isValid()) {
                    ent.remove();
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 方块 / 地面（原脚本 11761~11880 行）
    // ======================================================================

    /** {@code gsBbBlockAt(w, x, y, z)} —— 取坐标处的方块（坐标向下取整）。 */
    static Block gsBbBlockAt(World w, double x, double y, double z) {
        try {
            if (w == null) {
                return null;
            }
            int fx = (int) Math.floor(x);
            int fy = (int) Math.floor(y);
            int fz = (int) Math.floor(z);
            try {
                Block b = w.getBlockAt(fx, fy, fz);
                if (b != null) {
                    return b;
                }
            } catch (Throwable e) {
                // 试下一条
            }
            try {
                Location loc = ListenerCore.gsMsLoc(w, fx, fy, fz);
                if (loc != null) {
                    return loc.getBlock();
                }
            } catch (Throwable e) {
                // 放弃
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsBbNameSolid(block)} —— 按材质名判断：命中 ⇒ true（挡球）；没命中 ⇒ false（没意见）。 */
    static boolean gsBbNameSolid(Block block) {
        try {
            if (block == null) {
                return false;
            }
            Material t = block.getType();
            if (t == null) {
                return false;
            }
            String n = t.name();
            if (n.equals("null") || n.isEmpty()) {
                return false;
            }
            String up = n.toUpperCase(Locale.ROOT);
            for (String s : GS_BB_SOFT_EXCEPT) {
                if (up.contains(s)) {
                    return false;                     // 例外先排除
                }
            }
            for (String s : GS_BB_SOLID_KEYS) {
                if (up.contains(s)) {
                    return true;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * {@code gsBbSolid(block)} —— 「能挡住球」的判定。
     *
     * <p>★ 优先用 {@code isPassable()} —— 玻璃 / 树叶这种 <b>{@code isSolid()} 拿不到</b>的方块
     * （玻璃 {@code isPassable=false} 但 {@code Material#isSolid()} 返回 false）以前会被球直接穿过去。
     * <p>★★ {@code isPassable} 说「<b>能</b>过」时<b>不能就此放行</b> ——
     * 玻璃板这类薄方块会被误判，所以再调一次 {@link #gsBbNameSolid} 按材质名兜底。
     * <p>★★ 实测 1.21.11 的 {@code org.bukkit.Material} <b>没有 {@code isPassable()}</b>
     * （真 paper-api 编译验证）⇒ 原脚本里 {@code var pm = t.isPassable();} 那一段
     * 在真 JS 里也是恒抛异常被吞掉的<b>死代码</b>，Java 侧不照抄（见类注释死代码第 2 条）。
     * 尾巴那两行（{@code gsBbNameSolid} + {@code t.isSolid()}）在 {@code Block#isPassable()}
     * 可用的前提下不可达，但保留为 catch 兜底。
     */
    static boolean gsBbSolid(Block block) {
        try {
            if (block == null) {
                return false;
            }
            try {
                boolean pb = block.isPassable();
                if (!pb) {
                    return true;
                }
                if (gsBbNameSolid(block)) {
                    return true;
                }
                return false;
            } catch (Throwable e) {
                // 落到下面的兜底
            }
            Material t = block.getType();
            if (t == null) {
                return false;
            }
            // ★ 原脚本这里还有 `var pm = t.isPassable(); …` —— 1.21.11 无此方法，死代码，已删。
            try {
                if (gsBbNameSolid(block)) {
                    return true;
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                return t.isSolid();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * {@code gsBbFloor(w, x, yTop, z, maxDown)} ——
     * 从 {@code (x, yTop, z)} 向下找最近固体方块的<b>顶面高度</b>；找不到返回 null。
     */
    static Double gsBbFloor(World w, double x, double yTop, double z, Integer maxDown) {
        try {
            int y0 = (int) Math.floor(yTop);
            int lim = (maxDown == null) ? 16 : maxDown;
            for (int i = 0; i <= lim; i++) {
                if (gsBbSolid(gsBbBlockAt(w, x, y0 - i, z))) {
                    return (y0 - i) + 1.0;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsBbFloorSweep(w, x, yPrev, yNow, z, r)} ——
     * 球从 {@code yPrev} 掉到 {@code yNow} 这一段的<b>扫掠</b>落点（防高速穿透地面）。
     */
    static Double gsBbFloorSweep(World w, double x, double yPrev, double yNow, double z, double r) {
        try {
            int hi = (int) Math.floor(yPrev - r);
            int lo = (int) Math.floor(yNow - r) - 2;
            if (lo > hi) {
                lo = hi;
            }
            int lim = 48;
            for (int y = hi; y >= lo && (hi - y) < lim; y--) {
                if (gsBbSolid(gsBbBlockAt(w, x, y, z))) {
                    return y + 1.0;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsBbWallSweep(w, x0, z0, x1, z1, y, steps)} ——
     * 水平位移这一段上有没有墙（等分采样）。
     */
    static boolean gsBbWallSweep(World w, double x0, double z0, double x1, double z1, double y, Integer steps) {
        try {
            int n = (steps == null) ? 4 : steps;
            for (int i = 1; i <= n; i++) {
                // ★ JS 的 i / n 是浮点除法 ⇒ Java 必须显式 (double)（契约 §2.3 的头号坑）
                double t = (double) i / n;
                if (gsBbSolid(gsBbBlockAt(w, x0 + (x1 - x0) * t, y, z0 + (z1 - z0) * t))) {
                    return true;
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /** {@code gsBbDistH(ax, az, bx, bz)} —— 水平距离。 */
    static double gsBbDistH(double ax, double az, double bx, double bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ======================================================================
    // 渲染 / 状态机（原脚本 11881~12300 行）
    // ======================================================================

    /**
     * {@code gsBbDraw(st)} —— 画球。
     *
     * <p>★ 优先走展示实体；实体渲染不可用（{@code GS_BB_ENT_OK === false}）时才退回
     * 尘埃粒子球 —— 那正是 {@code GS_BB_DOTS} 个球面采样点（斐波那契球）的用途。
     */
    void gsBbDraw(Ball st) {
        try {
            if (st == null || st.w == null) {
                return;
            }
            if (GS_BB_USE_ENT) {
                if (gsBbEntSync(st)) {
                    return;
                }
            }
            int n = GS_BB_DOTS;
            double r = GS_BB_R;
            double sp = st.spin;
            double cs = Math.cos(sp);
            double sn = Math.sin(sp);
            double ga = 2.3999632297286531;            // 黄金角
            for (int i = 0; i < n; i++) {
                double y0 = 1.0 - (i + 0.5) * 2.0 / n;
                double rr = Math.sqrt(Math.max(0.0, 1.0 - y0 * y0));
                double th = i * ga;
                double px = Math.cos(th) * rr;
                double pz = Math.sin(th) * rr;
                double rx = px * cs - pz * sn;
                double rz = px * sn + pz * cs;
                int[] rgb = (i % 2 == 0) ? GS_BB_ORANGE : GS_BB_BLACK;
                gsBbSpark(st.w, st.x + rx * r, st.y + y0 * r, st.z + rz * r, rgb);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbClear(u)} —— 丢掉这个玩家的球（连带删掉展示实体）。
     *
     * <p>★ 原脚本 catch 里还有一层 {@code GS_BB_ST[u] = null} 兜底（因为 JS 的
     * {@code delete} 可能抛）；Java 的 {@code Map.remove} 不会抛 ⇒ 该分支不可达，不照抄。
     */
    void gsBbClear(String u) {
        try {
            Ball st = (u != null) ? GS_BB_ST.get(u) : null;
            try {
                gsBbEntDrop(st);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            if (u != null) {
                GS_BB_ST.remove(u);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBbSpawnPos(p, st)} —— 把球放到玩家面前（沿视线水平方向前移 2 格，落在最近地面上）。 */
    void gsBbSpawnPos(Player p, Ball st) {
        try {
            Location loc = p.getLocation();
            World w = p.getWorld();
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

            double x = loc.getX() + fx * GS_BB_SPAWN_FWD;
            double z = loc.getZ() + fz * GS_BB_SPAWN_FWD;
            Double gy = gsBbFloor(w, x, loc.getY() + 1.0, z, 24);
            if (gy == null) {
                gy = loc.getY();
            }
            st.w = w;
            st.x = x;
            st.z = z;
            st.y = gy + GS_BB_R + GS_BB_SPAWN_UP;
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBbNew(p)} —— 新建一颗 idle 球并放到玩家面前。 */
    Ball gsBbNew(Player p) {
        Ball st = new Ball();
        st.mode = "idle";
        st.spin = 0.0;
        st.heldBall = false;
        st.bnc = 0;
        st.hitWall = false;
        st.touched = 0;
        st.t0 = 0;
        st.until = 0;
        st.shotAt = 0;
        st.ent = null;
        st.entTick = 0;
        st.sy0 = 0.0;
        gsBbSpawnPos(p, st);
        return st;
    }

    /** {@code gsBbTouch(p, st)} —— 玩家碰到球了吗（水平 1.4 格 + 竖直 ±1.6 格）。 */
    static boolean gsBbTouch(Player p, Ball st) {
        try {
            Location loc = p.getLocation();
            if (gsBbDistH(st.x, st.z, loc.getX(), loc.getZ()) > GS_BB_TOUCH) {
                return false;
            }
            double dy = st.y - (loc.getY() + 0.9);
            return (dy > -GS_BB_TOUCH_V && dy < GS_BB_TOUCH_V);
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsBbBind(p, st)} —— 开始运球：10 秒倒计时 + 副手出现「篮球」+ 血条满格。 */
    void gsBbBind(Player p, Ball st) {
        long now = ListenerCore.now();
        st.mode = "bound";
        st.t0 = now;
        st.until = now + GS_BB_BIND_MS;
        st.vx = 0.0;
        st.vy = 0.0;
        st.vz = 0.0;
        gsBbHandOn(p, st);
        gsBbBarShow(p, 1.0);
        try {
            ListenerCore.gsMsSoundAt(p, "entity.item.pickup", 0.9f, 1.4f);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        gsBbTickBound(p, st);
    }

    /** {@code gsBbShoot(p)} —— 投篮（右键 / 挥手都走这里）。 */
    boolean gsBbShoot(Player p) {
        return gsBbShootState(p, (p != null) ? GS_BB_ST.get(ListenerCore.uid(p)) : null);
    }

    /**
     * {@code gsBbIsLawyerBall(p, st)} —— 这颗球是不是「律师函那颗」
     * （只有它才有运球血条 / 自己的副手球；生成器的球用另一套）。
     */
    boolean gsBbIsLawyerBall(Player p, Ball st) {
        try {
            if (p == null || st == null) {
                return false;
            }
            return GS_BB_ST.get(ListenerCore.uid(p)) == st;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsBbShootState(p, st)} —— ★★ <b>投篮物理的唯一定义</b>，生成器也复用这一份。
     *
     * <p>★★ 初速 = <b>准星三维方向 × 速度</b>（不再是「水平 + 固定竖直斜角」）：
     * 平视=传球 / 朝下=假动作 / 朝上=高抛。
     * <p>★ 出手点：只沿<b>水平</b>分量前移 {@code GS_BB_SHOT_FWD} 格，高度恒为「右手高度」
     * —— 俯仰角不该把出手点拉到脚底或头顶；正上/正下看时水平位移为 0。
     * <p>★★ 球飞出去了 ⇒ <b>副手那颗「篮球」立刻收走</b>（它只是「开门钥匙」），
     * <b>运球血条立刻收起</b>（运球结束）。这两样<b>只有律师函那颗球才有</b>。
     * <p>★★ 落地还要再弹 {@code GS_BB_BOUNCES} 下；撞墙标记先清零。
     * <p>★★★ 冷却判定 {@code (now - (st.shotAt | 0)) < GS_BB_SHOT_CD} ——
     * {@code | 0} 是 JS 的 {@code ToInt32}，会把毫秒时间戳<b>回绕</b> ⇒ 这个 350ms 冷却
     * 在真 JS 里<b>恒不生效</b>。Java 用 {@code (int)} 窄化<b>逐位复刻</b>
     * （见类注释「时间戳陷阱」）。
     */
    boolean gsBbShootState(Player p, Ball st) {
        try {
            if (p == null) {
                return false;
            }
            String u = ListenerCore.uid(p);
            if (st == null) {
                st = GS_BB_ST.get(u);
            }
            if (st == null || !"bound".equals(st.mode)) {
                return false;
            }
            long now = ListenerCore.now();
            // ★★★ 逐字复刻 JS 的 `st.shotAt | 0`（ToInt32 回绕）。改成 long 直接比较
            //      会凭空造出一个原脚本没有的 350ms 冷却 ⇒ 可观察差异。
            if ((now - (int) st.shotAt) < GS_BB_SHOT_CD) {
                return false;
            }
            st.shotAt = now;

            Location loc = p.getLocation();
            Vector dir = null;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                dir = null;
            }
            double dx = (dir != null) ? dir.getX() : 0.0;
            double dy = (dir != null) ? dir.getY() : 0.0;
            double dz = (dir != null) ? dir.getZ() : 1.0;
            double l = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (l < 1.0e-6) {
                dx = 0.0;
                dy = 0.0;
                dz = 1.0;
                l = 1.0;
            }
            dx /= l;
            dy /= l;
            dz /= l;

            double hl = Math.sqrt(dx * dx + dz * dz);
            double hx = (hl > 1.0e-4) ? (dx / hl) : 0.0;
            double hz = (hl > 1.0e-4) ? (dz / hl) : 0.0;

            st.w = p.getWorld();
            st.x = loc.getX() + hx * GS_BB_SHOT_FWD;
            st.z = loc.getZ() + hz * GS_BB_SHOT_FWD;
            st.y = loc.getY() + GS_BB_HAND_UP;

            st.vx = dx * GS_BB_SHOT_SPD;
            st.vy = dy * GS_BB_SHOT_SPD;
            st.vz = dz * GS_BB_SHOT_SPD;
            st.mode = "shot";
            st.life = 0;
            st.bnc = GS_BB_BOUNCES;
            st.hitWall = false;
            st.sy0 = st.y;                             // 记下出手高度（兜底落点）

            if (gsBbIsLawyerBall(p, st)) {
                gsBbHandOff(p, st);
                gsBbBarHide(p);
            }

            try {
                ListenerCore.gsMsSound(st.w, loc, "entity.arrow.shoot", 1.0f, 1.2f);
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsBbDropNear(p, st)} —— 10 秒没投出去（运球血条清零）⇒ 掉在附近 2 格的地上。 */
    void gsBbDropNear(Player p, Ball st) {
        try {
            // ★★ 超时没投 ⇒ 球掉地上，副手那颗「篮球」同样收走
            gsBbHandOff(p, st);
            // ★★ 运球血条清零的同时消失
            gsBbBarHide(p);
            Location loc = p.getLocation();
            double ang = Math.random() * Math.PI * 2.0;
            double x = loc.getX() + Math.cos(ang) * GS_BB_DROP_R;
            double z = loc.getZ() + Math.sin(ang) * GS_BB_DROP_R;
            st.w = p.getWorld();
            st.x = x;
            st.z = z;
            if (st.y < loc.getY() + 0.8) {
                st.y = loc.getY() + 0.8;               // 至少从腰高处往下掉
            }
            st.vx = 0.0;
            st.vz = 0.0;
            st.vy = -0.12;
            st.mode = "shot";
            st.life = 0;
            st.sy0 = st.y;                             // 同样记下「起落高度」
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBbRest(st, floorY)} —— 真正停住。 */
    static void gsBbRest(Ball st, double floorY) {
        st.mode = "rest";
        st.y = floorY + GS_BB_R;
        st.vx = 0.0;
        st.vy = 0.0;
        st.vz = 0.0;
        st.bnc = 0;                                    // ★ 弹跳用完了（再投出去时会重新给）
        st.touched = ListenerCore.now();
    }

    /**
     * ★★ {@code gsBbLand(st, floorY)} —— 落地不立刻停，<b>先弹两下</b>。
     *
     * <p>还剩弹跳次数 ⇒ 弹一下：竖直速度给 {@code GS_BB_BOUNCE_UP}
     * （逐次 × {@code GS_BB_BOUNCE_DECAY} 衰减），水平速度<b>只保留一点点</b>
     * （× {@code GS_BB_BOUNCE_KEEP}，且不超过 {@code GS_BB_BOUNCE_MAX}）：
     * <ul>
     *   <li>没撞过墙 ⇒ 保留 ⇒ <b>顺着投出的方向往前弹两下</b>再停（像真篮球滚两步）</li>
     *   <li>撞过墙 ⇒ 水平速度本来就是 0 ⇒ <b>原地弹两下</b>再停</li>
     * </ul>
     * ★ 弹起后仍然是 shot 模式（继续走重力 + 撞墙检测），所以第二下会自然再落一次。
     * ★ 弹跳阶段重新计时 {@code life = GS_BB_STRAIGHT + 1}：一是别被 MAX_TICKS 兜底误杀，
     * 二是<b>必须过了直线段</b>（{@code life <= GS_BB_STRAIGHT} 时不受重力 ⇒ 弹起来会一路直冲上天）。
     */
    static void gsBbLand(Ball st, double floorY) {
        int nb = st.bnc;
        if (nb <= 0 || GS_BB_BOUNCES <= 0) {
            gsBbRest(st, floorY);
            return;
        }

        st.bnc = nb - 1;
        st.y = floorY + GS_BB_R;                       // 贴着地面弹起，别陷进去

        // 第几次弹（0 = 第一次）⇒ 弹起高度逐次衰减
        double dec = 1.0;
        for (int i = 0; i < (GS_BB_BOUNCES - nb); i++) {
            dec = dec * GS_BB_BOUNCE_DECAY;
        }
        st.vy = GS_BB_BOUNCE_UP * dec;

        if (st.hitWall) {
            st.vx = 0.0;
            st.vz = 0.0;                               // ★ 撞过墙 ⇒ 原地弹
        } else {
            st.vx = st.vx * GS_BB_BOUNCE_KEEP;         // ★ 没撞墙 ⇒ 往投出的方向再有一点点速度
            st.vz = st.vz * GS_BB_BOUNCE_KEEP;
            double sp = Math.sqrt(st.vx * st.vx + st.vz * st.vz);
            if (sp > GS_BB_BOUNCE_MAX && sp > 1.0e-9) {
                st.vx = st.vx / sp * GS_BB_BOUNCE_MAX;
                st.vz = st.vz / sp * GS_BB_BOUNCE_MAX;
            }
        }
        st.life = GS_BB_STRAIGHT + 1;
    }

    /** {@code gsBbTickIdle(p, st)} —— idle：跟着玩家（超 6 格或高度差 12 格就重放）+ 别陷地里 + 触碰即绑定。 */
    void gsBbTickIdle(Player p, Ball st) {
        try {
            Location loc = p.getLocation();
            st.w = p.getWorld();
            // 球被落下太远 ⇒ 重新放回玩家面前
            if (gsBbDistH(st.x, st.z, loc.getX(), loc.getZ()) > GS_BB_LEASH
                    || Math.abs(st.y - loc.getY()) > 12.0) {
                gsBbSpawnPos(p, st);
            }
            // 别陷进地里
            Double fl = gsBbFloor(st.w, st.x, st.y - GS_BB_R, st.z, 24);
            if (fl != null && (st.y - GS_BB_R) < fl) {
                st.y = fl + GS_BB_R;
            }
            if (gsBbTouch(p, st)) {
                gsBbBind(p, st);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbTickBound(p, st)} —— 运球中：球在「地面」与「右手」之间按 |cos| 往返。
     *
     * <p>★ 血条进度 = 剩余时间 / 10 秒（满条 → 空条 → 消失）。
     * ★ 运球位置前移 {@code GS_BB_HAND_FWD}（半个身位）⇒ 观感是「人在身前运球」。
     * <p>★★ 这里的相位基准 {@code st.t0} <b>没有</b> {@code | 0}
     * （对比生成器那颗球的 {@code tBoundAt}，见类注释「时间戳陷阱」）。
     */
    void gsBbTickBound(Player p, Ball st) {
        try {
            long now = ListenerCore.now();
            long left = st.until - now;
            if (left <= 0) {
                gsBbDropNear(p, st);
                return;
            }
            gsBbBarShow(p, (GS_BB_BIND_MS > 0) ? ((double) left / GS_BB_BIND_MS) : 0.0);

            Location loc = p.getLocation();
            World w = p.getWorld();
            st.w = w;
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

            Double gyD = gsBbFloor(w, loc.getX(), loc.getY(), loc.getZ(), 6);
            double gy = (gyD == null) ? loc.getY() : gyD;
            double loY = gy + GS_BB_R;
            double hiY = loc.getY() + GS_BB_HAND_UP;
            if (hiY < loY) {
                hiY = loY;
            }
            double t = (now - st.t0) / 1000.0;
            double k = Math.abs(Math.cos(Math.PI * t / GS_BB_BOUNCE_S));

            st.x = loc.getX() + rx * GS_BB_HAND_SIDE + fx * GS_BB_HAND_FWD;
            st.z = loc.getZ() + rz * GS_BB_HAND_SIDE + fz * GS_BB_HAND_FWD;
            st.y = loY + k * (hiY - loY);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbTickShot(p, st)} —— 飞行：先直线 {@code GS_BB_STRAIGHT} 刻，之后受重力
     * {@code GS_BB_SHOT_GRAV}；撞墙垂直坠落；头顶撞方块即开始下落；落地走 {@link #gsBbLand}。
     *
     * <p>★ {@code p} 参数<b>用不到</b> —— 原脚本从生成器那边就是以
     * {@code gsBbTickShot(null, b)} 调进来的（{@link BgSub#gsBgNodeTick}），
     * 所以这里对 null 必须安全。
     */
    void gsBbTickShot(Player p, Ball st) {
        try {
            st.life = st.life + 1;
            World w = st.w;

            // ★ 直线段过后才开始下落
            if (st.life > GS_BB_STRAIGHT && GS_BB_SHOT_GRAV > 0) {
                st.vy = st.vy - GS_BB_SHOT_GRAV;
            }
            st.vx = st.vx * GS_BB_DRAG;
            st.vz = st.vz * GS_BB_DRAG;

            double nx = st.x + st.vx;
            double ny = st.y + st.vy;
            double nz = st.z + st.vz;

            boolean wall = false;
            if (Math.abs(st.vx) > 1.0e-6 || Math.abs(st.vz) > 1.0e-6) {
                if (gsBbWallSweep(w, st.x, st.z, nx, nz, st.y, GS_BB_SWEEP_N)) {
                    wall = true;
                }
            }
            if (wall) {
                st.vx = 0.0;
                st.vz = 0.0;
                st.hitWall = true;
                // ★ 弹跳阶段不要「垂直坠落」—— 那会让第二下弹不起来
                boolean bouncing = st.bnc < GS_BB_BOUNCES;
                if (!bouncing && st.vy > GS_BB_FALL_FAST) {
                    st.vy = GS_BB_FALL_FAST;
                }
                nx = st.x;
                nz = st.z;
            }

            // 头顶撞方块 ⇒ 立刻停止上升
            if (st.vy > 1.0e-6 && gsBbSolid(gsBbBlockAt(w, nx, ny + GS_BB_R, nz))) {
                st.vy = 0.0;
                ny = st.y;
            }

            // 落地检测：先扫掠（防高速穿透），再静态找地面
            Double flD = gsBbFloorSweep(w, nx, st.y, ny, nz, GS_BB_R);
            Double fl = flD;
            if (fl == null) {
                fl = gsBbFloor(w, nx, ny - GS_BB_R, nz, 24);
            }
            if (ny <= 0.0 || (fl != null && (ny - GS_BB_R) <= fl)) {
                st.x = nx;
                st.z = nz;
                gsBbLand(st, (fl != null) ? fl : (ny - GS_BB_R));
                return;
            }

            st.x = nx;
            st.y = ny;
            st.z = nz;

            // ★ 飞行刻数兜底（防卡死）：原地找个落点停住
            if (st.life > GS_BB_MAX_TICKS) {
                Double f2 = gsBbFloorSweep(w, st.x, st.y, st.y - 2.0, st.z, GS_BB_R);
                if (f2 == null) {
                    f2 = gsBbFloor(w, st.x, st.y - GS_BB_R, st.z, 24);
                }
                if (f2 == null) {
                    f2 = gsBbFloor(w, st.x, st.sy0, st.z, 24);
                }
                if (f2 == null) {
                    f2 = (st.sy0 > 0) ? st.sy0 : (st.y - GS_BB_R);
                }
                gsBbRest(st, f2);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbTickRest(p, st)} —— 停住后等触碰。
     *
     * <p>★★ 原脚本写「落地后静默 0.3 秒才允许再次触碰绑定」，但
     * {@code (st.touched | 0)} 的 {@code | 0} 会把毫秒时间戳<b>回绕</b>
     * ⇒ 这个 300ms 静默在真 JS 里<b>恒不生效</b>（球一停就能立刻被捡起）。
     * Java 用 {@code (int)} 窄化逐位复刻（见类注释「时间戳陷阱」）。
     */
    void gsBbTickRest(Player p, Ball st) {
        try {
            if ((ListenerCore.now() - (int) st.touched) < GS_BB_REBIND_CD) {
                return;
            }
            if (gsBbTouch(p, st)) {
                gsBbBind(p, st);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbPlayerTick(p)} —— 每刻的玩家处理。
     *
     * <p>★ 入口是「<b>穿着胸甲</b>」：{@code gsBbIs(inv.getChestplate())} 查的是
     * {@code GS_LAWYER_LETTER}，而那件物品<b>已被删除</b> ⇒ {@code worn} 恒为 false
     * ⇒ 这条分支<b>实际只做一件事</b>：把可能残留的球 / 血条清掉。
     * ★ 逐字保留整段（含不可达的运球逻辑）—— 哪一天物品被加回 yml，这段要能直接跑。
     */
    void gsBbPlayerTick(Player p) {
        try {
            String u = ListenerCore.uid(p);

            boolean worn = false;
            try {
                PlayerInventory inv = p.getInventory();
                worn = gsBbIs((inv != null) ? inv.getChestplate() : null);
            } catch (Throwable e) {
                worn = false;
            }

            if (!worn) {
                Ball stGone = GS_BB_ST.get(u);
                if (stGone != null) {
                    try {
                        gsBbHandOff(p, stGone);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    gsBbClear(u);
                }
                try {
                    gsBbBarDrop(p);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
                return;
            }

            Ball st = GS_BB_ST.get(u);
            if (st == null) {
                st = gsBbNew(p);
                GS_BB_ST.put(u, st);
            }
            st.spin = st.spin + GS_BB_SPIN;

            // 绑定期每刻把副手那颗「篮球」带上；离开绑定立刻收走
            if ("bound".equals(st.mode)) {
                gsBbHandOn(p, st);
            } else if (st.heldBall) {
                gsBbHandOff(p, st);
            }

            if ("bound".equals(st.mode)) {
                gsBbTickBound(p, st);
            } else if ("shot".equals(st.mode)) {
                gsBbTickShot(p, st);
            } else if ("rest".equals(st.mode)) {
                gsBbTickRest(p, st);
            } else {
                gsBbTickIdle(p, st);
            }

            // ★★ 运球血条只在 bound 期间存在 —— 一旦离开 bound 立刻收起
            if (!"bound".equals(st.mode)) {
                try {
                    gsBbBarHide(p);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }

            gsBbDraw(st);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 心跳（原脚本 12300~12383 行）
    // ======================================================================

    /** {@code gsBbGenNow()} —— 读当前世代号（读不到返回 null，与 0 区分开）。 */
    Integer gsBbGenNow() {
        try {
            if (GS_BB_PDT == null || GS_BB_K_GEN == null) {
                return null;
            }
            World w = Bukkit.getServer().getWorlds().get(0);
            Integer v = w.getPersistentDataContainer().get(GS_BB_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? Integer.valueOf(0) : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsBbGenBump()} —— 世代号 +1 并写回世界 PDC。
     *
     * <p>★ 为什么需要它：{@code GS.runRepeating} 返回的任务句柄拿不到、也 cancel 不掉，
     * 所以 {@code /gs reload} 之后旧任务还会继续跑。把世代号存在<b>第一个世界</b>的 PDC 上，
     * 每次 {@code start()} 就 +1 并记下「我这次拿到的是几号」；每次脉冲先问
     * {@link #gsBbPulse} 里的 {@code mine} 判定，不是当前那一份就直接停手。
     */
    void gsBbGenBump() {
        try {
            Integer cur = gsBbGenNow();
            if (cur == null) {
                cur = Integer.valueOf(0);
            }
            GS_BB_GEN = cur + 1;
            if (GS_BB_PDT == null || GS_BB_K_GEN == null) {
                return;
            }
            World w = Bukkit.getServer().getWorlds().get(0);
            w.getPersistentDataContainer().set(GS_BB_K_GEN, PersistentDataType.INTEGER, GS_BB_GEN);
        } catch (Throwable e) {
            if (GS_BB_GEN <= 0) {
                GS_BB_GEN = 1;
            }
        }
    }

    /** {@code gsBbClean()} —— 清掉下线玩家的球状态与血条（连带删掉展示实体）。 */
    void gsBbClean() {
        try {
            Map<String, Boolean> live = new HashMap<>();
            Iterator<? extends Player> it = Bukkit.getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                try {
                    live.put(ListenerCore.uid(it.next()), Boolean.TRUE);
                } catch (Throwable e) {
                    // 与原脚本一致：静默
                }
            }
            // ★ 用快照迭代：原脚本是 for-in + delete，被删的键本来也不会再被访问 ⇒ 等价
            for (String k : new ArrayList<>(GS_BB_ST.keySet())) {
                Ball v = GS_BB_ST.get(k);
                if (v == null || !Boolean.TRUE.equals(live.get(k))) {
                    // ★★ 人不在线 ⇒ 连带把展示实体删掉（否则实体没人管、也不会自己消失）
                    try {
                        gsBbEntDrop(v);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        GS_BB_ST.remove(k);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
            // ★★ 运球血条跟着一起清 —— 人都不在线了，血条留着只会变成一条「幽灵条」
            for (String k2 : new ArrayList<>(GS_BB_BARS.keySet())) {
                BbBarRec rb = GS_BB_BARS.get(k2);
                if (rb == null || !Boolean.TRUE.equals(live.get(k2))) {
                    try {
                        if (rb != null && rb.b != null) {
                            rb.b.removeAll();
                        }
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        if (rb != null && rb.b != null) {
                            rb.b.setVisible(false);
                        }
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                    try {
                        GS_BB_BARS.remove(k2);
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsBbPulse()} —— 心跳（{@code runRepeating(gsBbPulse, 1, 1)} ⇒ <b>每 1 游戏刻</b>一次，
     * 不是每 2 刻；球要跟着跳跃）。
     *
     * <p>★★ 世代号不是当前那份 ⇒ 旧实例留下的展示实体<b>必须先删掉</b>，再丢状态
     * （分两趟循环：同一个 for-in 里「边遍历边删」会漏项）。
     * <p>★★ 原脚本注释写「每 2 秒兜底扫一遍全体在线玩家的整个背包」，但
     * {@code (GS_BB_SWEEP_AT | 0)} 的 {@code | 0} 会回绕 ⇒ 真 JS 里<b>每刻都跑</b>。
     * 逐字保留（见类注释「时间戳陷阱」）。
     */
    void gsBbPulse() {
        boolean mine = true;
        try {
            Integer g = gsBbGenNow();
            // 读不到世代号时**不要**把自己当成旧实例（否则心跳会整个停掉）
            if (g != null && GS_BB_GEN != -1) {
                mine = (g.intValue() == GS_BB_GEN);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        if (!mine) {
            // ★★ 旧实例留下的展示实体必须先删掉，再丢状态（两趟）
            try {
                for (String kd : new ArrayList<>(GS_BB_ST.keySet())) {
                    gsBbEntDrop(GS_BB_ST.get(kd));
                }
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            try {
                GS_BB_ST.clear();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            // ★★ 旧实例的运球血条同样作废（否则会在玩家身上留一条「永远不动的条」）
            try {
                for (String kb : new ArrayList<>(GS_BB_BARS.keySet())) {
                    BbBarRec rb = GS_BB_BARS.get(kb);
                    try {
                        if (rb != null && rb.b != null) {
                            rb.b.removeAll();
                            rb.b.setVisible(false);
                        }
                    } catch (Throwable e) {
                        // 与原脚本一致：静默
                    }
                }
                GS_BB_BARS.clear();
            } catch (Throwable e) {
                // 与原脚本一致：静默
            }
            return;
        }
        try {
            Iterator<? extends Player> it = Bukkit.getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                Player p = it.next();
                try {
                    gsBbPlayerTick(p);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
            // ★★ 兜底扫一遍全体在线玩家的整个背包 ——
            //   「篮球」这件临时物品绝不该留在玩家身上（拖进背包 / 扔了又捡 / 脚本重载丢状态 都会残留）
            long nowM = ListenerCore.now();
            // ★★★ 逐字复刻 JS 的 `GS_BB_SWEEP_AT | 0`（ToInt32 回绕）⇒ 真 JS 里每刻都扫。
            if ((nowM - (int) GS_BB_SWEEP_AT) >= 2000) {
                GS_BB_SWEEP_AT = nowM;
                Iterator<? extends Player> it2 = Bukkit.getServer().getOnlinePlayers().iterator();
                while (it2.hasNext()) {
                    try {
                        gsBbSweep(it2.next());
                    } catch (Throwable e3) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        gsBbClean();
    }

    // ======================================================================
    // 右键 / 挥手（原脚本 12385~12407 行）
    // ======================================================================

    /**
     * {@code gsBbClickOnce(p, tag)} —— 右键 / 挥手的 80ms 去抖。
     *
     * <p>★ 这一处<b>没有</b> {@code | 0} ⇒ 它是真的生效的（与类注释里那 6 处相反）。
     * 实测的有效节流就来自这里。
     */
    boolean gsBbClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_BB_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_BB_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * {@code gsBbHandEmpty(p)} —— 主手是不是空着（AIR / CAVE_AIR / VOID_AIR）。
     * ★ 判不出来时<b>返回 false</b>（= 当成「手上有东西」）⇒ 只是不启用挥手兜底，绝不会误触。
     */
    static boolean gsBbHandEmpty(Player p) {
        try {
            if (p == null) {
                return false;
            }
            PlayerInventory inv = p.getInventory();
            return gsBbIsEmpty((inv != null) ? inv.getItemInMainHand() : null);
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 监听器（原脚本 12408~12516 行）
    // ======================================================================

    /**
     * 原脚本 {@code gsBbRegisterSync()} 注册的两个事件。
     *
     * <p>★ 优先级 {@code HIGHEST}、{@code ignoreCancelled} = <b>false</b>
     * （原脚本 {@code registerEvent} 没传第 5 个参数 ⇒ 取默认 false；Java 注解默认也是 false）。
     * <p>★ 原脚本每个事件都先试 {@code pm.registerEvent(eventClass, plugin, priority, executor, plugin)}，
     * 失败再试 {@code JavaAdapter} 造 {@code Listener}/{@code EventExecutor} ——
     * <b>GraalJS 25.1.3 没有 {@code JavaAdapter}</b> ⇒ 那条退路恒失败，属死代码。
     * <p>★★ <b>与原脚本的差异</b>：原脚本每次 reload 都重新 {@code registerEvent}
     * ⇒ 监听器<b>累积</b>（真实缺陷，右键会被处理 N 次）。Java 侧用
     * {@link #GS_BB_REGISTERED} 闩锁 + {@code registerEvents} 保证<b>只注册一次</b>。
     */
    static final class ListenerImpl implements Listener {

        private final BbSub sub;

        ListenerImpl(BbSub sub) {
            this.sub = sub;
        }

        /** ① 右键（需副手有球才发包）⇒ 投篮。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                Ball st = sub.GS_BB_ST.get(ListenerCore.uid(p));
                if (st == null || !"bound".equals(st.mode)) {
                    return;
                }
                String act = "";
                try {
                    act = e.getAction().name();
                } catch (Throwable e2) {
                    act = "";
                }
                if (act.indexOf("RIGHT_CLICK") < 0) {
                    return;
                }
                e.setCancelled(true);
                try {
                    // ★ 原脚本先探测 Event$Result 再用；1.21.11 必然可用 ⇒ 直接引用。
                    e.setUseInteractedBlock(Event.Result.DENY);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                try {
                    e.setUseItemInHand(Event.Result.DENY);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!sub.gsBbClickOnce(p, "R")) {
                    return;
                }
                sub.gsBbShoot(p);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ② 空手「挥手」兜底（空手对着空气右键时客户端不发包 ⇒ 挥手也能投）。
         *
         * <p>★ 原脚本把「是否注册」交给运行期的 {@code GS_BB_SWING_FALLBACK}；
         * Java 的 {@code @EventHandler} 是编译期注册 ⇒ 在方法体首行做同样的开关判断，
         * 语义完全等价（true 时无影响；false 时等价于「没注册」）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                if (!GS_BB_SWING_FALLBACK) {
                    return;
                }
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                Ball st = sub.GS_BB_ST.get(ListenerCore.uid(p));
                if (st == null || !"bound".equals(st.mode)) {
                    return;
                }
                if (!gsBbHandEmpty(p)) {
                    return;                    // 手里有东西的挥手多半是在打人，别误触
                }
                try {
                    var at = e.getAnimationType();
                    if (at != null) {
                        String an = at.name();
                        if (an.indexOf("SWING") < 0 && an.indexOf("ARM") < 0) {
                            return;
                        }
                    }
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
                if (!sub.gsBbClickOnce(p, "W")) {
                    return;
                }
                sub.gsBbShoot(p);
            } catch (Throwable e3) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 原脚本 {@code gsBbRegisterSync()}。
     *
     * <p>★★ 注意原脚本<b>忽略</b>每个 {@code reg()} 的返回值：即使两个事件一个都没注册上，
     * 它也照样把 {@code GS_BB_REGISTERED} 置 true、照样打成功日志、照样往下走
     * （{@code gsBbStart} 里只检查「拿不到 plugin」这一种失败）。
     * Java 侧<b>逐字保留</b>这个语义：{@code registerEvents} 抛了就吞掉，继续置闩锁 + 打日志。
     */
    boolean gsBbRegisterSync() {
        if (GS_BB_REGISTERED) {
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
                // ★ 原脚本在这里还有一条 JavaAdapter 退路 —— GraalJS 25.1.3 里
                //   JavaAdapter 不存在 ⇒ 恒失败，属死代码。原脚本忽略 reg() 的返回值
                //   ⇒ 这里也不 return false，继续往下走。
            }
            GS_BB_REGISTERED = true;
            ListenerCore.gsDaLog(gsBbRegisterLine());
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("律师函监听器注册异常: " + e);
            return false;
        }
    }

    // ======================================================================
    // 启动（原脚本 12517~12552 行）
    // ======================================================================

    /** 原脚本 {@code gsBbStart()}。 */
    void gsBbStart() {
        try {
            gsBbGenBump();
            if (!gsBbRegisterSync()) {
                ListenerCore.gsDaWarn("律师函启动失败：监听器没注册上");
                return;
            }
            // ★★ 每 1 游戏刻一次（不是每 2 刻）—— 球要跟着跳跃
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsBbPulse();
                }
            }, 1, 1);
            ListenerCore.gsDaLog(gsBbStartLine());
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("律师函启动异常: " + e);
        }
    }

    // ======================================================================
    // 日志行（★ 抽成静态方法只为让探针逐字节对账，不改任何行为）
    // ======================================================================

    /**
     * 注册成功那行（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★★ <b>「律师函监听器注册成功」这个字符串被
     * {@code QHCGS\_check\_doc_final.py} / {@code _doc_fix3.py} / {@code _doc_sec.py} /
     * {@code _doc_archive.py} 四个检查脚本逐字断言 —— 改一个字就挂测试。</b>
     * 对账金标准：{@code docs/参考服日志_构思技艺.txt} 第 41 行。
     */
    static String gsBbRegisterLine() {
        return "律师函监听器注册成功（穿着胸甲 → 尘埃粒子篮球 / 触碰绑定 / "
                + "★ 绑定期间副手自动出现「" + GS_BB_BALL_NAME + "」（" + GS_BB_BALL_MAT + "）⇒ 右键直接投 / "
                + "★ 绑定期间挂「" + GS_BB_BAR_TEXT + "」倒计时血条（只有这两个字 / "
                + JsNum.str((double) GS_BB_BIND_MS / 1000.0) + " 秒）/ "
                + "空手挥手兜底 = " + (GS_BB_SWING_FALLBACK ? "开" : "关") + "）";
    }

    /**
     * 启动那行（不含前缀）。
     *
     * <p>★★ 文案里带「律师函」字样是<b>历史遗留</b>（物品已删，但字符串被四个检查脚本断言
     * —— 见 {@link #GS_BB_ID}）。<b>逐字照抄，一个字都不许改。</b>
     * <p>★ 所有数字都走 {@link JsNum}：{@code GS_BB_DROP_R = 2.0} 拼出来是 {@code "2"}、
     * {@code GS_BB_BOUNCE_UP = 0.30} 是 {@code "0.3"}、{@code (GS_BB_R*2).toFixed(2)} 是
     * {@code "0.48"}。对账金标准：{@code docs/参考服日志_构思技艺.txt} 第 42 行。
     */
    static String gsBbStartLine() {
        return "你等着收我的律师函吧（" + GS_BB_ID
                + "）已加载 · 尘埃粒子篮球（非烟花 · 橙黑 " + JsNum.str(GS_BB_DOTS)
                + " 点 · 直径 " + JsNum.toFixed(GS_BB_R * 2, 2)
                + " 格 ≈ 头颅 · 粒子大小 " + JsNum.str(GS_BB_DOT_SIZE)
                + "）/ 触碰 " + JsNum.str(GS_BB_TOUCH)
                + " 格 / 绑定 " + JsNum.str((double) GS_BB_BIND_MS / 1000.0)
                + " 秒 / 超时落点 " + JsNum.str(GS_BB_DROP_R)
                + " 格 / ★ 投篮沿**准星三维方向** 速度 " + JsNum.str(GS_BB_SHOT_SPD)
                + "（平视=传球 / 朝下=假动作 / 朝上=高抛）"
                + " · 先直线 " + JsNum.str(GS_BB_STRAIGHT)
                + " 刻后重力 " + JsNum.str(GS_BB_SHOT_GRAV)
                + " / 撞墙垂直坠落 / 头顶撞方块即开始下落"
                + " / ★★ 落地先弹 " + JsNum.str(GS_BB_BOUNCES)
                + " 下再停（弹起 " + JsNum.str(GS_BB_BOUNCE_UP)
                + " 逐次 ×" + JsNum.str(GS_BB_BOUNCE_DECAY)
                + " · 没撞墙按 ×" + JsNum.str(GS_BB_BOUNCE_KEEP)
                + " 保留前速（上限 " + JsNum.str(GS_BB_BOUNCE_MAX) + "）往前弹 · 撞过墙原地弹）"
                + " / 停下后等触碰"
                + " / ★ 绑定期副手出现「" + GS_BB_BALL_NAME + "」（" + GS_BB_BALL_MAT
                + "）⇒ 右键真发包 · 副手本来有东西就不动 · 离开绑定立刻收走"
                + " / ★ 运球血条「" + GS_BB_BAR_TEXT + "」"
                + JsNum.str((double) GS_BB_BIND_MS / 1000.0)
                + " 秒倒计时（标题只有这两个字 · 满条 → 空条 → 投出/清零即消失 · 颜色 "
                + GS_BB_BAR_COLOR + "）· 运球位置前移 " + JsNum.str(GS_BB_HAND_FWD)
                + " 格（半个身位）"
                + " / ★★ 渲染 = " + (GS_BB_USE_ENT
                    ? ("展示实体 ItemDisplay（头颅皮肤 · 每 " + JsNum.str(GS_BB_ENT_EVERY)
                       + " 刻同步 1 次 · 插值 " + JsNum.str(GS_BB_ENT_TPDUR) + " 刻）")
                    : "尘埃粒子球（26 点）");
    }
}
