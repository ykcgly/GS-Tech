package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.qinghuacisj.gs.util.JsNum;

/**
 * {@code gs_listener.js} 的「阎魔刀·改」子系统
 * （原脚本第 <b>9281~10837</b> 行，顶层入口 {@code gsEmStart()}，第 10837 行）。
 *
 * <p><b>玩法</b>：构思武器第 9 格（金剑材质，{@code GS_ENMA_BLADE}）。
 *
 * <h3>左键 = 居合斩（<b>六刀一循环</b>）</h3>
 * 每刀都是「<b>宽 5 × 长 9 × 高 3</b>」的长方体，以<b>眼睛</b>为原点、沿<b>准星</b>
 * （含俯仰）展开，基础伤害 <b>37</b> 点。
 * <ul>
 *   <li><b>冷却 0.2 秒</b>（比 80ms 去抖门槛大 ⇒ 连点不会被去抖吃掉）；
 *       <b>停手 3 秒清空</b>计数器（下次从第 1 刀起）；特效存在 <b>0.4 秒</b>。</li>
 *   <li>★★ <b>前五刀「画圆圈切掉后面」</b> —— 这是关键机制，<b>不许简化</b>：
 *       每一刀都是一条<b>真正的圆弧</b>（按角度均匀采样，不是贝塞尔近似），
 *       半径沿「上轴」为 {@code r}、沿「前方」压扁到 {@code r × GS_EM_ARCH(0.62)}
 *       ⇒ 圆被「切掉后面」只剩朝前的半圆，看上去是一道<b>扁一点的半圆斩击</b>。
 *       <pre>
 *         第1刀 上撩：  圆心(2.2,0,0) r=2.6 tilt=0°   扫 180°→0°   （下→上）
 *         第2刀 横斩：  圆心(3.6,0,0) r=2.4 tilt=90°  扫 0°→180°   （右→左）
 *         第3刀 斜斩：  圆心(2.2,0,0) r=2.8 tilt=-45° 扫 0°→180°   （左上→右下）
 *         第4刀 横斩：  圆心(2.6,0,0) r=3.0 tilt=90°  扫 180°→0°   （左→右）
 *         第5刀 三弧：  左斜 / 右斜 / 竖斩，三条<b>二次贝塞尔弧的弧顶全部相撞</b>
 *                       在 (GS_EM_S5_PF, GS_EM_S5_PL, GS_EM_S5_PH) = 准星正前方
 *       </pre>
 *       ★ 第 5 刀的控制点是<b>由顶点反推</b>的（{@code c = 2P − (a+b)/2}），
 *       这样弧顶 {@code (a+b)/4 + c/2} 恰好落在 {@code P}。</li>
 *   <li>★★ <b>第 6 刀</b>：以<b>玩家为中心</b>、半径 <b>4.5</b> 格的圆圈（<b>左高右低</b>，
 *       {@code GS_EM_S6_TILT = -0.40} 弧度），圈内额外 <b>77</b> 点伤害（与基础 37 叠加
 *       ⇒ 圈内最多 37 + 77 = 114）。★ 圆面由 {@code e1 = r·cos + u·sin} 与
 *       {@code f}（准星）张成，法线 {@code n = e1 × f}；判定 = 到圆面垂距 ≤
 *       {@code GS_EM_S6_THICK(3.0)} 且面内距离 ≤ 4.5。
 *       ★★ 坑记一下：<b>在「垂直于视线」的平面里旋转圆等于没转</b>（圆在自己平面内怎么转
 *       都是同一个圆），要真的倾斜必须让圆面<b>转出</b>那个平面 —— 这就是 e1 的由来。</li>
 *   <li>★★ <b>第 6 刀附带次元斩</b>（整段从右键挪来 · <b>冷却已删除</b>）：
 *       落点在<b>眼睛沿准星往前 4 格</b>（抬头就往高处扔），作用半径 3 格，
 *       <b>每 0.5 秒 25 点</b>，存在 <b>1.2 秒</b>，<b>持续吸引</b>（每 0.1 秒拽一次，
 *       强度按距离递减 ⇒ 拽到中心附近自然停下），画面 = <b>红紫色大圆环</b>
 *       （半径 3 · 48 点 · 色带缓慢绕环转），画面<b>每 0.25 秒</b>重画（粒子做稀）。</li>
 * </ul>
 *
 * <h3>右键 = 锚点（坐标写入 lore）；Shift+右键 = 裂缝</h3>
 * <ul>
 *   <li><b>右键</b>：把当前位置（含 yaw/pitch）记进脚本内存，并把坐标写进<b>主手阎魔刀的
 *       lore</b> —— ★ 用户要求<b>删掉聊天栏提示词</b>，所以这里一个字都不发聊天；
 *       没记录时 lore 末行显示 {@code §8锚点 §7» §7§c未记录}。</li>
 *   <li><b>Shift+右键 = 裂缝</b>：身前 2 格立起一面「<b>长 7 × 宽 1 × 高 3</b>」的墙，
 *       <b>绘画 2 秒</b>（前一半画横线从左到右、后一半画竖线从上到下）→ 画出
 *       <b>宽 5 × 高 1.6 的实心菱形</b> → <b>留存 3 秒</b>，玩家走到菱形上就传送到锚点。
 *       <b>冷却 5 秒</b>（= 绘画 2 + 留存 3，与裂缝总寿命完全对齐 ⇒ 同时只存在一道）。
 *       ★ 裂缝是一面<b>竖直的墙</b> ⇒ 几何<b>只用水平方向</b>（俯仰不参与），
 *       否则「长7宽1高3」会被斜着拉变形。</li>
 * </ul>
 *
 * <h3>手持增益（拿在主手就生效，脱手立刻消失）</h3>
 * 可飞行 + 抗性提升 IV + 生命恢复 III + 速度 II + 夜视。
 * 一次给 <b>30 秒</b>，剩余不足 <b>27 秒</b>才续期（图标不闪）。
 * ★ 只收「本机制给的那一份」：玩家自己喝的同类药水一律不碰
 * （{@code GS_EM_BUFF_EFF} 记药效清单、{@code GS_EM_BUFF_NV} 记夜视）；
 * ★ 比我们<b>更强</b>的同种药效<b>不覆盖</b>（拿刀反而把速度 V 压成速度 II 是纯粹的削弱）；
 * ★ 只关「我们开启过的」飞行（{@code GS_EM_BUFF_FLY} 记接管状态），创造 / 旁观不动他们。
 * ★★ 第五十四批：原来的抗击退（{@code GS_EM_BUFF_KB}）已随功能一起删除。
 * <p>★★ <b>F = 原版换手</b>：第六十四批起<b>不再注册 {@code PlayerSwapHandItemsEvent}</b>
 * —— 原来 F=锚点、Shift+F=裂缝，现在两个都搬到右键了 ⇒ 按 F 就是原版换手，
 * 阎魔刀一个字都不拦。（这条是「<b>没有</b>换手拦截器」的反向断言。）
 *
 * <h2>★ 与原脚本的有意差异（修掉一个真实缺陷）</h2>
 * <b>原脚本 reload 会累积监听器，Java 版改为幂等注册。</b>
 * 原脚本用 RSC 的 {@code pm.registerEvent(...)} 在脚本里自己注册 <b>7</b> 个监听器，
 * 而 {@code /rsc reload} 会重新 eval 整个脚本 ⇒ 监听器每次 reload 都再注册一遍、
 * 不断累积（{@code GS_EM_REGISTERED} 闩锁是脚本级变量，reload 后归零，挡不住累积）。
 * Java 版把监听器写成 {@link Listener} 实现类、{@code registerEvents} 只注册一次：
 * 事件照常收到、优先级照常、取消照常，可观察行为相同，但 reload 不再累积。
 * ★ 这不是「顺手优化」，是修一个真实缺陷。
 *
 * <p>★ 优先级与 {@code ignoreCancelled} 逐处核对过：原脚本 7 处
 * {@code pm.registerEvent(类, plugin, Priority.HIGHEST, executor, plugin)} <b>都没有第 5 个参数</b>
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 的 {@code @EventHandler} 默认也是 false
 * ⇒ 不加该属性即等价（<b>绝不写成 true</b>）。
 *
 * <h2>死代码 / 走不到的分支（不照抄，在此存档）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 10632~10634 行）：GraalJS 25.1.3 里
 *       {@code JavaAdapter} 是 {@code undefined}（{@code tools/GraalProbe.java} 实测）
 *       ⇒ 这条退路<b>永远走不到</b>。Java 版直接用原生 {@link Listener}。</li>
 *   <li><b>{@code plugin} 四级回退</b>（原脚本 10616~10621 行）：Java 版自己有 Plugin 实例
 *       （{@link GS#plugin()}）⇒ 整条回退链作废；{@code if (plugin == null) return false;}
 *       那条<b>恒不执行</b>。</li>
 *   <li><b>{@code GS_EM_PDT}</b>（原脚本 9415 行的 {@code Java.type} 探测结果）：
 *       Java 里该类必然可用 ⇒ 只保留 {@code GS_EM_K_GEN != null} 这一半判断。</li>
 *   <li><b>{@code gsEmBarColor()} 的 {@code C[name]}</b>：{@code BarColor} 是 enum，
 *       它的公开静态字段恰好就是枚举常量集合 ⇒ 与 {@code valueOf(name)} 同一批名字；
 *       Java 侧合并成一次 {@code valueOf}（同 {@code VoSub} 同处注释）。</li>
 *   <li><b>{@code gsEmRegisterSync()} 的失败分支</b>（原脚本 10804 行的
 *       「阎魔刀·改启动失败：监听器没注册上」）：只有 plugin 拿不到才会走到，
 *       Java 里恒不执行。★ 但<b>日志文案照抄保留</b>。</li>
 *   <li><b>{@code gsEmDrawSlash} 的第一个参数 {@code p}</b>：原脚本传了 {@code p}
 *       但函数体<b>从头到尾没用过它</b>（只用来占位）。Java 侧保留参数以对齐签名，
 *       内部不使用。</li>
 * </ol>
 *
 * <h2>★ {@code GS_EM_REGISTERED} 闩锁</h2>
 * 原脚本里它<b>只写不读</b>（写：9397 行定义 + 10791 行置 true；读：只在 10611 行的
 * {@code if (GS_EM_REGISTERED) return true;} 早退里）。Java 里保留成<b>实例字段</b>：
 * 因为 {@code ListenerModule} 只有一个实例，它天然等价于「幂等注册一次」。
 */
public final class EmSub {

    // ======================================================================
    // 参数（名字必须与 JS 顶层 var 同名 —— 常量对账探针按名字反射比对）
    // ======================================================================

    static final String GS_EM_ID = "GS_ENMA_BLADE";
    /** {@code true = 手持时挖不动方块}。 */
    static final boolean GS_EM_NO_BREAK = true;
    /** {@code false = 只打生物，不打玩家（防误伤）}。 */
    static final boolean GS_EM_HIT_PLAYERS = false;

    // ---- 左键 · 居合斩 ----
    /** ★★ 第五十一批：基础伤害 <b>23 → 37</b>（用户：左键基础伤害改成37）。 */
    static final double GS_EM_DMG = 37.0;
    /** 判定宽（左右各 2.5）。 */
    static final double GS_EM_WIDE = 5.0;
    /** 判定长（前方 9 格）。 */
    static final double GS_EM_RANGE = 9.0;
    /** 判定高。 */
    static final double GS_EM_H = 3.0;
    /** 六刀一循环。 */
    static final int GS_EM_SLASH_N = 6;
    /** ★ 第四十九批：左键冷却 <b>500 → 200</b>（毫秒）= 0.2 秒（比 80ms 去抖门槛大）。 */
    static final long GS_EM_CD = 200L;
    /** ★ 第四十九批：斩击特效存在 <b>600 → 400</b>（毫秒）= 0.4 秒。 */
    static final long GS_EM_FX_MS = 400L;
    /** 停手多久清空计数器（毫秒）= 3 秒。 */
    static final long GS_EM_RESET_MS = 3000L;
    /** 血条颜色。 */
    static final String GS_EM_BAR_COL = "PURPLE";
    /** ★★ 前五刀<b>前方进深</b>的压扁比例（第四十三批：扁一点）= 0.62。 */
    static final double GS_EM_ARCH = 0.62;
    /** ★ 第 6 刀的圆圈半径（格）。 */
    static final double GS_EM_S6_R = 4.5;
    /** ★ 圆圈的倾斜角（绕准星轴）⇒ 负值 = <b>左高右低</b>。 */
    static final double GS_EM_S6_TILT = -0.40;
    /** ★ 圆圈采样点数。 */
    static final int GS_EM_S6_N = 64;
    /** ★★ 第 6 刀<b>圆圈范围内</b>的<b>额外</b>伤害（与基础 37 叠加 ⇒ 圈内最多 114）。 */
    static final double GS_EM_S6_DMG = 77.0;
    /** ★ 圆盘的厚度容差（格，沿圆面法线方向）。 */
    static final double GS_EM_S6_THICK = 3.0;

    // ---- 左键第六刀 · 次元斩（★★ 从右键挪来 · 冷却删除 · 持续 1.2 秒） ----
    /** 落点在身前第 4 格。 */
    static final double GS_EM_DS_DIST = 4.0;
    /** 作用半径 3 格。 */
    static final double GS_EM_DS_R = 3.0;
    /** ★ 第四十五批：每跳伤害 <b>9 → 25</b>。 */
    static final double GS_EM_DS_DMG = 25.0;
    /** 每 0.5 秒造成一次伤害（毫秒）。 */
    static final long GS_EM_DS_EVERY = 500L;
    /** ★★ 第六十四批：存在 <b>4 秒 → 1.2 秒</b>。 */
    static final long GS_EM_DS_LIFE = 1200L;
    /** 吸引强度（每 0.1 秒拽一次的水平速度）。 */
    static final double GS_EM_DS_PULL = 0.42;
    /** ★★ 第六十四批修正 3：<b>大圆环的半径</b>（格），与伤害半径对齐。 */
    static final double GS_EM_DS_BIG = 3.0;
    /** ★★ 第六十四批修正 3：大圆环的<b>圆周采样点数</b>（每 7.5° 一个点）。 */
    static final int GS_EM_DS_RING_N = 48;
    /** ★ 画面重画间隔（第四十二批：0.1 秒 → <b>0.25 秒</b> ⇒ 粒子更稀）。 */
    static final long GS_EM_DS_DRAW_MS = 250L;

    // ---- Shift+右键 · 裂缝 ----
    /** 裂缝出现在身前 2 格。 */
    static final double GS_EM_CRACK_DIST = 2.0;
    /** 裂缝半宽 ⇒ 全长 7 格。 */
    static final double GS_EM_CRACK_HALF = 3.5;
    /** 裂缝高 3 格。 */
    static final double GS_EM_CRACK_H = 3.0;
    /** 横线高度（格）。 */
    static final double GS_EM_CRACK_MIDH = 1.5;
    /** ★★ 绘画时间 1 秒 → <b>2 秒</b>（毫秒）。 */
    static final long GS_EM_CRACK_DRAW = 2000L;
    /** 留存时间 3 秒（毫秒）。 */
    static final long GS_EM_CRACK_KEEP = 3000L;
    /** ★★ 冷却 <b>5 秒</b> = 绘画 2 秒 + 滞留 3 秒（毫秒）。 */
    static final long GS_EM_CRACK_CD = 5000L;
    /** ★ 菱形半宽（格）= (6.5 − 1.5) / 2。 */
    static final double GS_EM_DIA_R = 2.50;
    /** ★ 菱形半高（格）= (2.3 − 0.7) / 2。 */
    static final double GS_EM_DIA_H = 0.80;
    /** 触碰判定半径（格）。 */
    static final double GS_EM_TOUCH_R = 1.30;

    // ---- ★★ 手持增益（拿在主手就生效，脱手立刻消失） ----
    /** 药效一次给 30 秒（刻）。 */
    static final int GS_EM_BUFF_TICKS = 600;
    /** 剩余低于 27 秒才续期（刻）。 */
    static final int GS_EM_BUFF_REF = 540;

    /** ★ 第 5 刀三条弧的<b>共同顶点</b>（三条弧的弧顶都落在这里 ⇒ 「顶点相撞」）。 */
    static final double GS_EM_S5_PF = 4.60;
    static final double GS_EM_S5_PL = 0.00;
    static final double GS_EM_S5_PH = 0.00;

    /** ★ 本模块<b>自己的</b>世界世代号键 {@code gs:em_gen}。 */
    static final NamespacedKey GS_EM_K_GEN = NamespacedKey.fromString("gs:em_gen");

    /**
     * {@code GS_MS_C} —— 月镰子系统的共享色表（原脚本 6171~6175 行）。
     *
     * <p>★★ {@link ListenerCore} 里<b>没有</b>它（见交付说明第 4 条），所以在用到它的
     * 三个类（VoSub / SiSub / EmSub）里各留一份<b>逐字副本</b>；
     * 集成者把它提到 {@code ListenerCore} 后，这三份副本应整体删除。
     */
    static final Map<String, String> GS_MS_C = buildMsColors();

    private static Map<String, String> buildMsColors() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("RED", "§c");
        m.put("DARK_RED", "§4");
        m.put("GOLD", "§6");
        m.put("YELLOW", "§e");
        m.put("GREEN", "§a");
        m.put("AQUA", "§b");
        m.put("BLUE", "§9");
        m.put("LIGHT_PURPLE", "§d");
        m.put("PURPLE", "§5");
        m.put("WHITE", "§f");
        m.put("GRAY", "§7");
        m.put("DARK_GRAY", "§8");
        m.put("BLACK", "§0");
        return Collections.unmodifiableMap(m);
    }

    // ======================================================================
    // 运行期状态（原脚本的顶层 var，★ 非静态：实例 = 原脚本的一次顶层求值）
    // ======================================================================

    /** {@code GS_EM_REGISTERED} —— 幂等闩锁（见类注释）。 */
    boolean GS_EM_REGISTERED = false;
    /** {@code GS_EM_ST} —— uuid → {@code { n, until }}（n = 刚打完第几刀，0 = 未开始）。 */
    final Map<String, EmSt> GS_EM_ST = new HashMap<>();
    /** {@code GS_EM_BARS} —— uuid → {@code { b, p }}（★ 本模块专属表）。 */
    final Map<String, ListenerCore.BarRec> GS_EM_BARS = ListenerCore.newBarTable();
    /** {@code GS_EM_SWING_AT} —— uuid → 上次出刀毫秒（0.2 秒冷却）。 */
    final Map<String, Long> GS_EM_SWING_AT = new HashMap<>();
    /** {@code GS_EM_CLICK_AT} —— {@code uuid|L/R} → 上次处理毫秒（去抖）。 */
    final Map<String, Long> GS_EM_CLICK_AT = new HashMap<>();
    /** {@code GS_EM_CRACK_AT} —— uuid → 上次撕裂缝毫秒（5 秒冷却）。 */
    final Map<String, Long> GS_EM_CRACK_AT = new HashMap<>();
    /** {@code GS_EM_ANCHOR} —— uuid → {@code { w, x, y, z, yaw, pitch }}（右键记录的锚点）。 */
    final Map<String, Anchor> GS_EM_ANCHOR = new HashMap<>();
    /** {@code GS_EM_BUFF_FLY} —— uuid → true（这份飞行是本机制开的）。 */
    final Map<String, Boolean> GS_EM_BUFF_FLY = new HashMap<>();
    /**
     * {@code GS_EM_BUFF_EFF} —— uuid → 本机制给过的药效清单。
     *
     * <p>★ 语义要点：<b>「表里没有这个 uuid」与「表里是个空清单」不是一回事</b> ——
     * 脱手时写的是 {@code null}（删键），而给的时候即使一个都没登记上也会写一个
     * <b>空清单</b>。{@code gsEmBuffTick} 的「快速通道」判的就是 {@code == null}。
     */
    final Map<String, List<Eff>> GS_EM_BUFF_EFF = new HashMap<>();
    /** {@code GS_EM_BUFF_NV} —— uuid → true（这份夜视是本机制给的）。 */
    final Map<String, Boolean> GS_EM_BUFF_NV = new HashMap<>();
    /** {@code GS_EM_FX} —— 正在淡出的斩击特效。 */
    List<Fx> GS_EM_FX = new ArrayList<>();
    /** {@code GS_EM_FIELDS} —— 活跃的次元斩领域。 */
    List<DField> GS_EM_FIELDS = new ArrayList<>();
    /** {@code GS_EM_CRACKS} —— 活跃的裂缝。 */
    List<Crack> GS_EM_CRACKS = new ArrayList<>();
    /** {@code GS_EM_GEN} —— 加载那一刻的世界世代号（判 {@code /rsc reload} 旧实例）。 */
    int GS_EM_GEN = -1;
    /** {@code GS_EM_LAST_PULSE} —— 上一次心跳的毫秒（算 {@code dt} 用）。 */
    long GS_EM_LAST_PULSE = 0;

    // 颜色（懒加载 + 缓存）：紫色为主、蓝色作点缀
    Particle.DustOptions GS_EM_C_PUR = null;
    Particle.DustOptions GS_EM_C_BLU = null;
    // ★★★ 次元斩的大圆环用「红紫色」—— 单独一套，不动上面那套
    Particle.DustOptions GS_EM_C_DSR = null;
    Particle.DustOptions GS_EM_C_DSP = null;

    private final ListenerCore core;

    public EmSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsEmStart();} 调用（原脚本第 10837 行）。 */
    public void start() {
        gsEmStart();
    }

    // ======================================================================
    // 数据类（原脚本里的对象字面量）
    // ======================================================================

    /** {@code GS_EM_ST[uuid]} 的记录。 */
    static final class EmSt {
        int n;
        long until;
    }

    /**
     * 朝向基向量 + 原点 —— 原脚本 {@code gsEmBasis(loc)} 的返回值，
     * 以及 {@code gsEmSwing} 里 {@code mkFrame()} 造出来的「画特效用的坐标系」。
     *
     * <p>局部坐标映射统一走 {@link #gsEmP}：世界点 = 原点 + f·pf + r·pl + u·ph
     * （pf 前 / pl 左右（<b>负 = 左</b>）/ ph 垂直抬升）。
     */
    static final class Frame {
        World w;
        double ox;
        double oy;
        double oz;
        double fx;
        double fy;
        double fz;
        double rx;
        double ry;
        double rz;
        double ux;
        double uy;
        double uz;
    }

    /** 一个形状条目：半圆（真圆弧采样）或二次贝塞尔弧。 */
    static final class Shape {
        /** {@code true} = 圆弧（原脚本 {@code sh.cir === 1}）。 */
        boolean cir;
        double[] o;
        double r;
        double tilt;
        double a0;
        double a1;
        double[] a;
        double[] c;
        double[] b;
    }

    /** {@code GS_EM_FX} 的一条：一次出刀的淡出特效。 */
    static final class Fx {
        Player p;
        World w;
        int idx;
        long t0;
        Frame fr;
    }

    /** {@code GS_EM_FIELDS} 的一条：一个次元斩领域。 */
    static final class DField {
        World w;
        Player owner;
        double x;
        double y;
        double z;
        Frame bs;
        long born;
        long nextHit;
        /** ★ 原脚本里是动态挂上去的字段（{@code f.drawAt}）⇒ Java 用可空 Long。 */
        Long drawAt;
    }

    /** {@code GS_EM_CRACKS} 的一条：一道裂缝。 */
    static final class Crack {
        World w;
        Player owner;
        double x;
        double y;
        double z;
        /** {@code c.bs}：只有水平四分量（裂缝是一面竖直的墙）。 */
        double fx;
        double fz;
        double rx;
        double rz;
        int t;
        boolean used;
    }

    /** {@code GS_EM_ANCHOR[uuid]}：右键记录的锚点。 */
    static final class Anchor {
        String w;
        double x;
        double y;
        double z;
        double yaw;
        double pitch;
    }

    /** {@code GS_EM_BUFF_EFF[uuid]} 里的一项：{@code { t, amp }}。 */
    static final class Eff {
        PotionEffectType t;
        int amp;
    }

    // ======================================================================
    // 小工具
    // ======================================================================

    /** {@code gsMsBarStyle()} —— 原脚本 6252~6260 行。★ {@link ListenerCore} 里没有它。 */
    static BarStyle gsMsBarStyle() {
        try {
            BarStyle s = BarStyle.SEGMENTED_12;
            if (s != null) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到下一档
        }
        try {
            BarStyle s2 = BarStyle.valueOf("SEGMENTED_12");
            if (s2 != null) {
                return s2;
            }
        } catch (Throwable ignored) {
            // 落到下一档
        }
        try {
            return BarStyle.valueOf("SOLID");
        } catch (Throwable ignored) {
            // 与原脚本一致：返回 null
        }
        return null;
    }

    /**
     * JS 的 {@code String(数字)} —— 与 {@link VoSub#jsNum(double)} 逐字相同
     * （★ 本地副本；集成者应统一提到 {@code ListenerCore}，见交付说明第 4 条）。
     */
    static String jsNum(double v) {
        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。
        return JsNum.str(v);
    }

    /** {@code gsEmPur()} —— 紫色（懒加载 + 缓存）。 */
    Particle.DustOptions gsEmPur() {
        if (GS_EM_C_PUR == null) {
            GS_EM_C_PUR = ListenerCore.gsMsDustOpts(150, 45, 255, 1.4);
        }
        return GS_EM_C_PUR;
    }

    /** {@code gsEmBlu()} —— 蓝色（懒加载 + 缓存）。 */
    Particle.DustOptions gsEmBlu() {
        if (GS_EM_C_BLU == null) {
            GS_EM_C_BLU = ListenerCore.gsMsDustOpts(55, 105, 255, 1.4);
        }
        return GS_EM_C_BLU;
    }

    /**
     * {@code gsEmDot(world, x, y, z, i)} —— 每 3 个点掺 1 个蓝色，其余紫色
     * ⇒ 整体「紫中带蓝」。
     *
     * <p>★ 这一套<b>只给六道斩击 / 裂缝</b>用，次元斩不用它（见 {@link #gsEmDsDot}）。
     */
    void gsEmDot(World world, double x, double y, double z, int i) {
        Particle.DustOptions d = ((i % 3) == 1) ? gsEmBlu() : gsEmPur();
        ListenerCore.gsMsDust(world, core.gsMsDustType(), d, x, y, z);
    }

    /** {@code gsEmDsRed()} —— 次元斩大圆环：偏红。 */
    Particle.DustOptions gsEmDsRed() {
        if (GS_EM_C_DSR == null) {
            GS_EM_C_DSR = ListenerCore.gsMsDustOpts(240, 40, 90, 1.5);
        }
        return GS_EM_C_DSR;
    }

    /** {@code gsEmDsPur()} —— 次元斩大圆环：偏紫。 */
    Particle.DustOptions gsEmDsPur() {
        if (GS_EM_C_DSP == null) {
            GS_EM_C_DSP = ListenerCore.gsMsDustOpts(170, 35, 205, 1.5);
        }
        return GS_EM_C_DSP;
    }

    /**
     * {@code gsEmDsDot(world, x, y, z, i, phase)} —— 环上每 4 个点里 2 个偏红、2 个偏紫；
     * {@code phase} 让这层色带<b>缓慢绕着环转</b>。
     *
     * <p>★ 为什么用色带而不是把环转起来：<b>正圆自转是看不出来的</b>（旋转对称），
     * 让红/紫两色沿圆周推进才有「活着」的感觉，而形状始终是一个干净的圆环。
     */
    void gsEmDsDot(World world, double x, double y, double z, int i, int phase) {
        int k = (i + phase) % 4;
        if (k < 0) {
            k += 4;
        }
        ListenerCore.gsMsDust(world, core.gsMsDustType(), (k < 2) ? gsEmDsRed() : gsEmDsPur(), x, y, z);
    }

    /** {@code gsEmIs(item)}。 */
    boolean gsEmIs(ItemStack item) {
        return ListenerCore.gsIsItem(item, GS_EM_ID);
    }

    /** {@code gsEmHand(p)}。 */
    static ItemStack gsEmHand(Player p) {
        try {
            return p.getInventory().getItemInMainHand();
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 居合斩：计数器（内存权威）
    // ======================================================================

    /** {@code gsEmGet(p)}。 */
    EmSt gsEmGet(Player p) {
        EmSt o = new EmSt();
        try {
            if (p == null) {
                return o;
            }
            EmSt m = GS_EM_ST.get(ListenerCore.uid(p));
            if (m != null) {
                o.n = m.n;
                o.until = m.until;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return o;
    }

    /** {@code gsEmSet(p, n, until)} —— {@code n <= 0} 时<b>删表</b>。 */
    void gsEmSet(Player p, int n, long until) {
        try {
            if (p == null) {
                return;
            }
            String u = ListenerCore.uid(p);
            if (n <= 0) {
                GS_EM_ST.remove(u);
            } else {
                EmSt s = new EmSt();
                s.n = n;
                s.until = until;
                GS_EM_ST.put(u, s);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmNext(n)} —— 下一刀该出第几刀：刚打完 n ⇒ 下一刀 = {@code (n % 6) + 1}。
     *
     * <p>★ 打完第 6 刀（n = 6）⇒ {@code (6 % 6) + 1 = 1} ⇒ <b>自动回到第 1 刀</b>。
     */
    static int gsEmNext(int n) {
        if (n <= 0) {
            return 1;
        }
        return (n % GS_EM_SLASH_N) + 1;
    }

    // ======================================================================
    // 居合斩：BossBar
    // ======================================================================

    /** {@code gsEmTitle(n)} —— {@code §5居合 §dN§5/§d6}。 */
    static String gsEmTitle(int n) {
        int k = n;
        if (k <= 0) {
            k = 1;
        }
        return GS_MS_C.get("PURPLE") + "居合 " + GS_MS_C.get("LIGHT_PURPLE") + k
                + GS_MS_C.get("PURPLE") + "/" + GS_MS_C.get("LIGHT_PURPLE") + GS_EM_SLASH_N;
    }

    /** {@code gsEmBarColor()}。 */
    static BarColor gsEmBarColor() {
        try {
            try {
                BarColor c = BarColor.valueOf(GS_EM_BAR_COL);
                if (c != null) {
                    return c;
                }
            } catch (Throwable ignored) {
                // 落到字段访问（enum 上与 valueOf 等价）
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** {@code gsEmBarOf(p)}。 */
    BossBar gsEmBarOf(Player p) {
        try {
            String u = ListenerCore.uid(p);
            ListenerCore.BarRec rec = GS_EM_BARS.get(u);
            if (rec != null && rec.b != null) {
                return rec.b;
            }
            BarColor c = gsEmBarColor();
            BarStyle s = gsMsBarStyle();
            if (c == null || s == null) {
                return null;
            }
            BossBar bar = null;
            try {
                bar = Bukkit.createBossBar(gsEmTitle(1), c, s);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                bar.setVisible(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                bar.addPlayer(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            ListenerCore.BarRec nr = new ListenerCore.BarRec();
            nr.b = bar;
            nr.p = p;
            GS_EM_BARS.put(u, nr);
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsEmBarShow(p, n, left, total)} —— ★ 进度必须是<b>浮点</b>除法。 */
    void gsEmBarShow(Player p, int n, long left, long total) {
        try {
            BossBar b = gsEmBarOf(p);
            if (b == null) {
                return;
            }
            double prog = (total > 0) ? (left / (double) total) : 0;
            if (prog < 0) {
                prog = 0;
            }
            if (prog > 1) {
                prog = 1;
            }
            ListenerCore.gsBarTitleSet(b, gsEmTitle(n));
            try {
                b.setProgress(prog);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                b.setVisible(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmBarHide(p)}。 */
    void gsEmBarHide(Player p) {
        ListenerCore.gsBarTblHide(GS_EM_BARS, p);
    }

    /** {@code gsEmBarDrop(p)}。 */
    void gsEmBarDrop(Player p) {
        ListenerCore.gsBarTblDrop(GS_EM_BARS, p);
    }

    /** {@code gsEmClear(p)} —— 计数器归零 + 藏血条。 */
    void gsEmClear(Player p) {
        try {
            gsEmSet(p, 0, 0);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            gsEmBarHide(p);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmPlayerTick(p)} —— 每 2 刻维护：血条倒计时；<b>3 秒没出刀 ⇒ 清空</b>
     * （下次从第 1 刀起）。
     */
    void gsEmPlayerTick(Player p) {
        try {
            EmSt st = gsEmGet(p);
            if (st.n <= 0) {
                gsEmBarHide(p);
                return;
            }
            long now = ListenerCore.now();
            if (st.until <= 0) {                          // 自愈：有层数没计时 ⇒ 补一份
                gsEmSet(p, st.n, now + GS_EM_RESET_MS);
                gsEmBarShow(p, st.n, GS_EM_RESET_MS, GS_EM_RESET_MS);
                return;
            }
            long left = st.until - now;
            if (left <= 0) {
                gsEmClear(p);                             // ★ 3 秒没出刀 ⇒ 清空，回到第 1 刀
                ListenerCore.gsMsSoundAt(p, "block.beacon.deactivate", 0.5f, 1.4f);
                return;
            }
            gsEmBarShow(p, st.n, left, GS_EM_RESET_MS);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 手持增益（可飞行 / 抗性4 / 生命恢复3 / 速度2 / 夜视）
    // ======================================================================

    /**
     * {@code gsEmBuffEffects(p, uuid, on)} —— 给 / 收走药效 + 夜视。
     *
     * <p>★ 只收「本机制给的那一份」：玩家自己喝的同类药水一律不碰。
     * ★★ 比我们<b>更强</b>的同种药效（玩家自己喝的 / 信标给的）⇒ <b>不覆盖</b>。
     * 理由：拿刀反而把身上的速度 V 压成速度 II，是纯粹的削弱。
     * 这时登记表里记的是 {@code ef.amp}，与身上的 amp 对不上 ⇒ 脱手时那条
     * {@code getAmplifier() != amp} 会自然跳过，玩家自己那份<b>原样保留</b>。
     */
    void gsEmBuffEffects(Player p, String uuid, boolean on) {
        try {
            // ================= 收走 =================
            if (!on) {
                // ① 夜视（单独一张表：玩家自己喝的夜视一律不碰）
                if (Boolean.TRUE.equals(GS_EM_BUFF_NV.get(uuid))) {
                    GS_EM_BUFF_NV.put(uuid, Boolean.FALSE);
                    PotionEffectType nvT = ListenerCore.gsSetPotionType("NIGHT_VISION", null);
                    if (nvT != null) {
                        try {
                            p.removePotionEffect(nvT);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
                // ② 其余三种（等级对得上，才认定是「我们给的那一份」）
                List<Eff> arr = GS_EM_BUFF_EFF.remove(uuid);
                if (arr != null) {
                    for (int i = 0; i < arr.size(); i++) {
                        try {
                            PotionEffectType t = arr.get(i).t;
                            PotionEffect cur = p.getPotionEffect(t);
                            if (cur == null) {
                                continue;
                            }
                            if (cur.getAmplifier() != arr.get(i).amp) {
                                continue;
                            }
                            p.removePotionEffect(t);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
                return;
            }

            // ================= 给 =================
            // （原脚本这里探测 Java.type('org.bukkit.potion.PotionEffect') 是否可用；
            //   Java 里必然可用 ⇒ 那个 null 分支恒不执行）
            final String[] names = {"RESISTANCE", "REGENERATION", "SPEED"};
            final String[] alts = {"DAMAGE_RESISTANCE", null, null};
            final int[] amps = {3, 2, 1};

            List<Eff> applied = new ArrayList<>();
            for (int j = 0; j < names.length; j++) {
                PotionEffectType et = ListenerCore.gsSetPotionType(names[j], alts[j]);
                if (et == null) {
                    continue;
                }

                // ★ 先登记（不论本次是否真的补发），脱手时才知道该清哪些
                Eff eff = new Eff();
                eff.t = et;
                eff.amp = amps[j];
                applied.add(eff);

                // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
                int curAmp = -1;
                int curDur = 0;
                try {
                    PotionEffect c = p.getPotionEffect(et);
                    if (c != null) {
                        curAmp = c.getAmplifier();
                        curDur = c.getDuration();
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }

                // ★★ 比我们更强的同种药效 ⇒ 不覆盖；还够久 ⇒ 不补（判定见 gsEmBuffShouldApply）
                if (!gsEmBuffShouldApply(curAmp, curDur, amps[j], GS_EM_BUFF_REF)) {
                    continue;
                }

                // ambient = true（金色边）、particles = false（不冒粒子）
                try {
                    p.addPotionEffect(new PotionEffect(et, GS_EM_BUFF_TICKS, amps[j], true, false), true);
                } catch (Throwable e) {
                    try {
                        p.addPotionEffect(new PotionEffect(et, GS_EM_BUFF_TICKS, amps[j], true, false));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
            GS_EM_BUFF_EFF.put(uuid, applied);

            // ③ 夜视：玩家当前**已有夜视**（自己喝的）且不是本机制给的 → 完全不碰
            PotionEffectType nvT2 = ListenerCore.gsSetPotionType("NIGHT_VISION", null);
            if (nvT2 != null) {
                boolean owned = Boolean.TRUE.equals(GS_EM_BUFF_NV.get(uuid));
                PotionEffect nvCur = null;
                try {
                    nvCur = p.getPotionEffect(nvT2);
                } catch (Throwable e) {
                    nvCur = null;
                }
                int left = 0;
                try {
                    left = nvCur.getDuration();
                } catch (Throwable e) {
                    left = 0;
                }

                // 0 = 别人给的夜视，完全不碰；1 = 接管但不补；2 = 接管并补（判定见 gsEmNvDecide）
                int nvAct = gsEmNvDecide(owned, nvCur != null, left, GS_EM_BUFF_REF);
                if (nvAct != 0) {
                    GS_EM_BUFF_NV.put(uuid, Boolean.TRUE);
                    if (nvAct == 2) {
                        try {
                            p.addPotionEffect(new PotionEffect(nvT2, GS_EM_BUFF_TICKS, 0, true, false), true);
                        } catch (Throwable e) {
                            try {
                                p.addPotionEffect(new PotionEffect(nvT2, GS_EM_BUFF_TICKS, 0, true, false));
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 三种主药效的「要不要补发」判定 —— 从 {@link #gsEmBuffEffects} 里<b>原样抽出</b>
     * （纯算术，不改行为；抽出来是为了让对账探针能逐值对账 —— 原脚本这里是内联的）。
     *
     * <pre>
     *   curAmp &gt; wantAmp            ⇒ 身上那份**更强** ⇒ 不覆盖（拿刀反而把速度 V 压成 II 是削弱）
     *   curAmp == wantAmp 且还够久  ⇒ 不补（避免图标一闪一闪）
     *   否则                        ⇒ 补发
     * </pre>
     *
     * <p>★ 「身上没有该药效」时调用方传 {@code curAmp = -1, curDur = 0}
     * （原脚本用 {@code var curAmp = -1} 初始化，就是这个语义）。
     */
    static boolean gsEmBuffShouldApply(int curAmp, int curDur, int wantAmp, int ref) {
        if (curAmp > wantAmp) {
            return false;
        }
        if (curAmp == wantAmp && curDur > ref) {
            return false;
        }
        return true;
    }

    /**
     * 夜视的接管 / 补发判定 —— 同样从 {@link #gsEmBuffEffects} 里<b>原样抽出</b>。
     *
     * @return {@code 0} = 完全不碰（别人给的夜视，不触发 / 不接管 / 不登记）·
     *         {@code 1} = 接管并登记，但这次不补发 ·
     *         {@code 2} = 接管并登记，且补发
     */
    static int gsEmNvDecide(boolean owned, boolean has, int left, int ref) {
        if (has && !owned) {
            return 0;
        }
        if (!has || left <= ref) {
            return 2;
        }
        return 1;
    }

    /** {@code gsEmIsCreative(p)} —— 创造 / 旁观模式不动他们的飞行。 */
    static boolean gsEmIsCreative(Player p) {
        try {
            String gm = String.valueOf(p.getGameMode().name());
            return gm.equals("CREATIVE") || gm.equals("SPECTATOR");
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsEmBuffClear(p)} —— 脱手 / 退服 / 重载时的统一回收（不看手持状态，直接收）。 */
    void gsEmBuffClear(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            if (Boolean.TRUE.equals(GS_EM_BUFF_FLY.get(uuid))) {
                if (!gsEmIsCreative(p)) {
                    // 正在飞就先把飞行状态落下来，再收回权限（免得悬在半空）
                    try {
                        if (p.isFlying()) {
                            p.setFlying(false);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        p.setAllowFlight(false);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                GS_EM_BUFF_FLY.put(uuid, Boolean.FALSE);
            }
            gsEmBuffEffects(p, uuid, false);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmBuffTick(p)} —— 每 2 刻（0.1 秒）由 {@link #gsEmPulse()} 结算一次。 */
    void gsEmBuffTick(Player p) {
        try {
            if (p == null) {
                return;
            }
            String uuid = ListenerCore.uid(p);
            boolean hold = false;
            try {
                hold = gsEmIs(gsEmHand(p));
            } catch (Throwable e) {
                hold = false;
            }

            if (hold) {
                // ① 可飞行（只记「我们开的」，免得误关别人的）
                if (gsEmIsCreative(p)) {
                    GS_EM_BUFF_FLY.put(uuid, Boolean.FALSE);
                } else {
                    boolean allow = false;
                    try {
                        allow = p.getAllowFlight();
                    } catch (Throwable e) {
                        allow = false;
                    }
                    if (!allow) {
                        try {
                            p.setAllowFlight(true);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                    GS_EM_BUFF_FLY.put(uuid, Boolean.TRUE);
                }
                // ② 药效 + 夜视
                gsEmBuffEffects(p, uuid, true);
            } else {
                // ★ 快速通道：本来就没有「我们的东西」⇒ 连 gamemode / isFlying 都不用查。
                //   这是绝大多数玩家的常态（手里没拿刀）⇒ 每 0.1 秒的固定开销降到最低。
                if (!Boolean.TRUE.equals(GS_EM_BUFF_FLY.get(uuid))
                        && !GS_EM_BUFF_EFF.containsKey(uuid)
                        && !Boolean.TRUE.equals(GS_EM_BUFF_NV.get(uuid))) {
                    return;
                }

                // 脱手 ⇒ 立刻全部收走
                if (Boolean.TRUE.equals(GS_EM_BUFF_FLY.get(uuid))) {
                    if (!gsEmIsCreative(p)) {
                        try {
                            if (p.isFlying()) {
                                p.setFlying(false);
                            }
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                        try {
                            p.setAllowFlight(false);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                    GS_EM_BUFF_FLY.put(uuid, Boolean.FALSE);
                }
                gsEmBuffEffects(p, uuid, false);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 目标筛选 / 伤害器
    // ======================================================================

    /** {@code gsEmCanHit(ent, selfId)}。 */
    static boolean gsEmCanHit(Entity ent, String selfId) {
        try {
            if (ent == null) {
                return false;
            }
            String id;
            try {
                id = ListenerCore.uid(ent);
            } catch (Throwable e) {
                return false;
            }
            if (id.equals(selfId)) {
                return false;
            }
            String tn;
            try {
                tn = ListenerCore.typeName(ent);
            } catch (Throwable e) {
                return false;
            }
            if (tn.equals("PLAYER") && !GS_EM_HIT_PLAYERS) {
                return false;
            }
            // ★ 沿用的「只认活物」：掉落物 / 经验球 / 展示实体 / 盔甲架 都不算命中
            if (!ListenerCore.gsIsCombatTarget(ent, tn)) {
                return false;
            }
            try {
                ((LivingEntity) ent).getHealth();
            } catch (Throwable e) {
                return false;
            }
            try {
                if (ent.isDead()) {
                    return false;
                }
            } catch (Throwable ignored) {
                // ★ 原脚本这里是空 catch（不 return）—— 保留
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** {@code gsEmHurt(ent, dmg, p)}。 */
    void gsEmHurt(Entity ent, double dmg, Player p) {
        core.gsMsHurt(ent, dmg, p);
    }

    // ======================================================================
    // 玩家朝向基向量（★★ 完整三维，跟着准星）
    // ======================================================================

    /**
     * {@code gsEmBasis(loc)} —— 返回 {@code { fx,fy,fz, rx,ry,rz, ux,uy,uz }}：
     * <pre>
     *   f = **准星朝向**（含俯仰，已归一化）
     *   r = 水平右向（永远水平，不随俯仰倾斜）
     *   u = 垂直于 f 与 r 的"上方"（= r × f）⇒ 抬头时它会向后仰
     * </pre>
     * ★ 与「致万千无言者 / 万籁俱寂」同一套左右口径：面朝 +Z 时右向 = −X
     * ⇒ <b>左侧是 +X</b>。
     */
    static Frame gsEmBasis(Location loc) {
        Frame o = new Frame();
        o.fx = 0.0;
        o.fy = 0.0;
        o.fz = 1.0;
        o.rx = -1.0;
        o.ry = 0.0;
        o.rz = 0.0;
        o.ux = 0.0;
        o.uy = 1.0;
        o.uz = 0.0;
        try {
            // ① 前向 = 视线方向（三维）
            double dx = 0.0;
            double dy = 0.0;
            double dz = 1.0;
            try {
                Vector d = loc.getDirection();
                dx = d.getX();
                dy = d.getY();
                dz = d.getZ();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            double L = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (!(L > 1.0e-6)) {
                dx = 0.0;
                dy = 0.0;
                dz = 1.0;
                L = 1.0;
            }
            dx /= L;
            dy /= L;
            dz /= L;
            o.fx = dx;
            o.fy = dy;
            o.fz = dz;

            // ② 水平右向：优先取前向的**水平分量**；正上/正下看时它退化成 0 ⇒ 退回用 yaw
            double hx = dx;
            double hz = dz;
            double hl = Math.sqrt(hx * hx + hz * hz);
            if (!(hl > 1.0e-4)) {
                double yaw = 0.0;
                try {
                    yaw = loc.getYaw();
                } catch (Throwable e) {
                    yaw = 0.0;
                }
                double rad = yaw * Math.PI / 180.0;
                hx = -Math.sin(rad);
                hz = Math.cos(rad);                       // 原版口径：yaw 0 = 朝 +Z
                hl = Math.sqrt(hx * hx + hz * hz);
                if (!(hl > 1.0e-6)) {
                    hx = 0.0;
                    hz = 1.0;
                    hl = 1.0;
                }
            }
            hx /= hl;
            hz /= hl;
            // r = f × (0,1,0) 的水平化结果 ⇒ 面朝 +Z 时 = (-1,0,0) = 右手边
            o.rx = -hz;
            o.ry = 0.0;
            o.rz = hx;

            // ③ u = r × f（右手系，抬头时向后仰）
            o.ux = o.ry * o.fz - o.rz * o.fy;
            o.uy = o.rz * o.fx - o.rx * o.fz;
            o.uz = o.rx * o.fy - o.ry * o.fx;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默（返回默认基）
        }
        return o;
    }

    /** {@code gsEmP(b, ox, oy, oz, pf, pl, ph)} —— 局部坐标 ⇒ 世界点。 */
    static Location gsEmP(Frame b, double ox, double oy, double oz,
                          double pf, double pl, double ph) {
        return ListenerCore.gsMsLoc(
                b.w,
                ox + b.fx * pf + b.rx * pl + b.ux * ph,
                oy + b.fy * pf + b.ry * pl + b.uy * ph,
                oz + b.fz * pf + b.rz * pl + b.uz * ph);
    }

    // ======================================================================
    // 左键 · 居合斩：六刀形状（局部坐标 pf 前 / pl 左右 / ph 垂直）
    // ======================================================================

    /**
     * {@code gsEmCircle(cf, cl, ch, r, tiltDeg, a0Deg, a1Deg)} ——
     * 「画一个圆圈、把后面切掉」⇒ 朝前的半圆（<b>真圆弧采样，不是贝塞尔近似</b>）。
     *
     * @param o    圆心 [前, 左右, 垂直]
     * @param r    半径
     * @param tilt 上轴倾斜角（度）
     * @param a0   起始角（度，0 = 上轴端、90 = 正前、180 = 另一端）
     * @param a1   结束角（度）
     */
    static Shape gsEmCircle(double cf, double cl, double ch, double r,
                            double tiltDeg, double a0Deg, double a1Deg) {
        Shape s = new Shape();
        s.cir = true;
        s.o = new double[]{cf, cl, ch};
        s.r = r;
        s.tilt = tiltDeg;
        s.a0 = a0Deg;
        s.a1 = a1Deg;
        return s;
    }

    /** {@code gsEmCircDraw(world, b, sh, n, fade)} —— 画上面那种「半圆」。 */
    void gsEmCircDraw(World world, Frame b, Shape sh, int n, double fade) {
        try {
            int step = (fade < 0.45) ? 2 : 1;
            double tl = sh.tilt * Math.PI / 180.0;
            double ct = Math.cos(tl);
            double st = Math.sin(tl);
            double a0 = sh.a0 * Math.PI / 180.0;
            double a1 = sh.a1 * Math.PI / 180.0;
            double rn = sh.r;                             // 沿"上轴"的半径（整圆尺寸）
            double rf = sh.r * GS_EM_ARCH;                // 沿"前方"的半径（扁一点）
            for (int i = 0; i <= n; i += step) {
                double t = i / (double) n;
                double th = a0 + (a1 - a0) * t;
                double cn = Math.cos(th) * rn;            // 上轴方向的分量
                double cfw = Math.sin(th) * rf;           // 正前方方向的分量
                Location pt = gsEmP(b, b.ox, b.oy, b.oz,
                        sh.o[0] + cfw,
                        sh.o[1] + cn * st,
                        sh.o[2] + cn * ct);
                if (pt != null) {
                    gsEmDot(world, pt.getX(), pt.getY(), pt.getZ(), i);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmArcTo(a, b, P)} —— 弧顶<b>精确</b>落在 P 的二次贝塞尔弧（由 P 反推控制点）。
     *
     * <p>弧顶 = {@code (a+b)/4 + c/2} ⇒ 要让弧顶落在 P，控制点取 {@code c = 2P − (a+b)/2}。
     */
    static Shape gsEmArcTo(double[] a, double[] b, double[] p) {
        Shape s = new Shape();
        s.cir = false;
        s.a = a;
        s.b = b;
        s.c = new double[]{
                2.0 * p[0] - (a[0] + b[0]) * 0.5,
                2.0 * p[1] - (a[1] + b[1]) * 0.5,
                2.0 * p[2] - (a[2] + b[2]) * 0.5};
        return s;
    }

    /**
     * {@code gsEmShape(idx)} —— 第 idx 刀（0 基）的形状列表。
     *
     * <p>★★ <b>前五刀「画圆圈切掉后面」</b> 的完整参数表（<b>不许简化</b>）：
     * <pre>
     *   0 上撩   ：圆(2.2,0,0) r=2.6 tilt=  0° 扫 180°→  0°   下→上
     *   1 横斩   ：圆(3.6,0,0) r=2.4 tilt= 90° 扫   0°→180°  右→左
     *   2 斜斩   ：圆(2.2,0,0) r=2.8 tilt=-45° 扫   0°→180°  左上→右下
     *   3 横斩   ：圆(2.6,0,0) r=3.0 tilt= 90° 扫 180°→  0°   左→右
     *   4 三弧   ：左斜 / 右斜 / 竖斩，三条弧顶全部相撞在准星正前方
     * </pre>
     * 第 5 刀（idx = 5）单独处理（见 {@link #gsEmDrawSlash6}）⇒ 返回空表。
     */
    static List<Shape> gsEmShape(int idx) {
        List<Shape> out = new ArrayList<>();
        if (idx == 0) {
            // 第 1 刀 上撩：竖着的圆圈切掉后面 ⇒ 朝前的半圆，从下到上
            //   圆心 (2.2, 0, 0)、半径 2.6 ⇒ 从 (2.2, 0, -2.6) 经正前方到 (2.2, 0, +2.6)
            out.add(gsEmCircle(2.2, 0.0, 0.0, 2.6, 0.0, 180.0, 0.0));
            return out;
        }
        if (idx == 1) {
            // 第 2 刀 横斩：横着的圆圈切掉后面，扫向 0°→180° ⇒ 从右扫到左
            //   圆心更靠前（pf 3.6 vs 2.6）、半径更小（2.4 vs 3.0）
            out.add(gsEmCircle(3.6, 0.0, 0.0, 2.4, 90.0, 0.0, 180.0));
            return out;
        }
        if (idx == 2) {
            // 第 3 刀 左上 → 右下：斜着的圆圈（上轴往左偏 45°）切掉后面
            out.add(gsEmCircle(2.2, 0.0, 0.0, 2.8, -45.0, 0.0, 180.0));
            return out;
        }
        if (idx == 3) {
            // 第 4 刀 横斩：横着的圆圈（上轴 = 左右方向）切掉后面
            //   ⇒ 两端同高、向前鼓。圆心更靠前（2.6）、半径更大（3.0）、左 → 右
            out.add(gsEmCircle(2.6, 0.0, 0.0, 3.0, 90.0, 180.0, 0.0));
            return out;
        }
        if (idx == 4) {
            // 第 5 刀 左斜 + 右斜 + 竖斩 三条弧**同时出现**，
            //   三条弧的**顶点全部相撞**在 (GS_EM_S5_PF, GS_EM_S5_PL, GS_EM_S5_PH)
            //   ★ 三条弧的两端都关于共同顶点对称（中点都落在 (2.2, 0, 0) 上）
            double[] p = {GS_EM_S5_PF, GS_EM_S5_PL, GS_EM_S5_PH};
            out.add(gsEmArcTo(new double[]{2.2, -2.4, 1.4}, new double[]{2.2, 2.4, -1.4}, p));   // 左斜：左上 → 右下
            out.add(gsEmArcTo(new double[]{2.2, 2.4, 1.4}, new double[]{2.2, -2.4, -1.4}, p));   // 右斜：右上 → 左下
            out.add(gsEmArcTo(new double[]{3.0, 0.0, -1.6}, new double[]{3.0, 0.0, 1.6}, p));     // 竖斩：下 → 上
            return out;
        }
        return out;                                       // 第 6 刀单独处理（见 gsEmDrawSlash6）
    }

    /**
     * {@code gsEmDrawSlash6(world, b, fade)} —— 第 6 刀：以<b>玩家为中心</b>、半径 4.5 格的
     * <b>圆圈</b>，整体<b>左高右低</b>。
     *
     * <p>★★ 坑：<b>在「垂直于视线」的平面里旋转圆，等于没转</b> —— 圆在自己的平面内怎么转
     * 都是同一个圆，所以「左高右低」根本不会出现。要真的倾斜，必须让圆面<b>转出</b>那个平面。
     * <p>★ 做法：圆面由这两个基张成 ——
     * <pre>
     *   e1 = 水平右向 r 抬高 TILT 角（= r·cos + u·sin）
     *   e2 = **准星朝向 f**
     * </pre>
     * ⇒ 圆面包含准星方向、绕它倾斜 ⇒ 看上去就是一个「以你为中心、左高右低的圈」。
     * TILT 取负值 ⇒ +r（右手边）压低、−r（左手边）抬高 ⇒ <b>左高右低</b>。
     * ★ 圆上任意点到中心的距离恒为 {@link #GS_EM_S6_R}（e1、e2 是正交单位向量）。
     */
    void gsEmDrawSlash6(World world, Frame b, double fade) {
        try {
            int n = GS_EM_S6_N;
            int step = (fade < 0.45) ? 2 : 1;             // 淡出后期隔点画，视觉上变稀
            double ca = Math.cos(GS_EM_S6_TILT);
            double sa = Math.sin(GS_EM_S6_TILT);
            // e1 = r·cos + u·sin（横向抬起来）；e2 = f（准星方向）
            double e1x = b.rx * ca + b.ux * sa;
            double e1y = b.ry * ca + b.uy * sa;
            double e1z = b.rz * ca + b.uz * sa;
            for (int i = 0; i < n; i += step) {
                double th = (i / (double) n) * 2.0 * Math.PI;
                double c1 = Math.cos(th) * GS_EM_S6_R;
                double c2 = Math.sin(th) * GS_EM_S6_R;
                gsEmDot(world,
                        b.ox + e1x * c1 + b.fx * c2,
                        b.oy + e1y * c1 + b.fy * c2,
                        b.oz + e1z * c1 + b.fz * c2, i);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmArcDraw(world, b, arc, n, fade)} —— 把一条二次贝塞尔弧画到世界里。 */
    void gsEmArcDraw(World world, Frame b, Shape arc, int n, double fade) {
        try {
            int step = (fade < 0.45) ? 2 : 1;
            double[] a = arc.a;
            double[] c = arc.c;
            double[] e = arc.b;
            for (int i = 0; i <= n; i += step) {
                double t = i / (double) n;
                double it = 1.0 - t;
                double w0 = it * it;
                double w1 = 2.0 * t * it;
                double w2 = t * t;
                double pf = w0 * a[0] + w1 * c[0] + w2 * e[0];
                double pl = w0 * a[1] + w1 * c[1] + w2 * e[1];
                double ph = w0 * a[2] + w1 * c[2] + w2 * e[2];
                Location pt = gsEmP(b, b.ox, b.oy, b.oz, pf, pl, ph);
                if (pt != null) {
                    gsEmDot(world, pt.getX(), pt.getY(), pt.getZ(), i);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmShapeDraw(world, b, sh, n, fade)} —— 分派：圆弧 / 贝塞尔弧。 */
    void gsEmShapeDraw(World world, Frame b, Shape sh, int n, double fade) {
        if (sh == null) {
            return;
        }
        if (sh.cir) {
            gsEmCircDraw(world, b, sh, n, fade);
        } else {
            gsEmArcDraw(world, b, sh, n, fade);
        }
    }

    /**
     * {@code gsEmDrawSlash(p, world, b, idx, fade)} —— 按 fade（1 = 刚出刀，0 = 完全消失）
     * 把第 idx 刀画一遍。
     *
     * <p>★ 第一个参数 {@code p} 原脚本传了但<b>函数体从头到尾没用过</b>（保留以对齐签名）。
     */
    void gsEmDrawSlash(Player p, World world, Frame b, int idx, double fade) {
        try {
            if (idx == 5) {
                gsEmDrawSlash6(world, b, fade);
                return;
            }
            List<Shape> arcs = gsEmShape(idx);
            for (int s = 0; s < arcs.size(); s++) {
                gsEmShapeDraw(world, b, arcs.get(s), 18, fade);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmSwing} 里 {@code mkFrame()} 造出来的「画特效用的坐标系」。 */
    static Frame mkFrame(World world, double ox, double oy, double oz, Frame bs) {
        Frame f = new Frame();
        f.w = world;
        f.ox = ox;
        f.oy = oy;
        f.oz = oz;
        f.fx = bs.fx;
        f.fy = bs.fy;
        f.fz = bs.fz;
        f.rx = bs.rx;
        f.ry = bs.ry;
        f.rz = bs.rz;
        f.ux = bs.ux;
        f.uy = bs.uy;
        f.uz = bs.uz;
        return f;
    }

    // ======================================================================
    // 左键 · 居合斩：出刀
    // ======================================================================

    /**
     * {@code gsEmSwing(p)} —— 一次居合斩。
     *
     * <p>★★ <b>特效与伤害全部以「眼睛」为原点、沿准星朝向展开</b>
     * （用户：左键右键的所有释放都要对着玩家准星朝向）；
     * 取不到眼睛位置就退回脚底 + 1.62（原版眼高）。
     *
     * <p>★ 计数器<b>不看有没有命中</b>，出刀就往前走。
     */
    void gsEmSwing(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_EM_SWING_AT.get(u);
            if (last != null && (now - last) < GS_EM_CD) {
                return;                                   // 0.2 秒冷却
            }
            GS_EM_SWING_AT.put(u, now);

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            double ox = 0.0;
            double oy = 0.0;
            double oz = 0.0;
            Location aimLoc;
            boolean gotEye = false;
            try {
                Location eye = p.getEyeLocation();
                if (eye != null) {
                    aimLoc = eye;
                    ox = eye.getX();
                    oy = eye.getY();
                    oz = eye.getZ();
                    gotEye = true;
                } else {
                    aimLoc = null;
                }
            } catch (Throwable e) {
                gotEye = false;
                aimLoc = null;
            }
            if (!gotEye) {
                aimLoc = loc;
                ox = loc.getX();
                oy = loc.getY() + 1.62;
                oz = loc.getZ();
            }
            Frame bs = gsEmBasis(aimLoc);

            // ① 这一刀是第几刀（★ 不看有没有命中，出刀就往前走）
            EmSt st = gsEmGet(p);
            int idx = gsEmNext(st.n) - 1;                 // 0..5
            int n = idx + 1;                              // 展示用 1..6

            // ② 特效（挂进淡出队列，由心跳逐帧重画到 0.4 秒）
            //   ★ 存的是**出刀那一刻的整套坐标系**（含准星朝向）⇒ 淡出期间形状不会歪
            try {
                Fx fx = new Fx();
                fx.p = p;
                fx.w = world;
                fx.idx = idx;
                fx.t0 = now;
                fx.fr = mkFrame(world, ox, oy, oz, bs);
                GS_EM_FX.add(fx);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            gsEmDrawSlash(p, world, mkFrame(world, ox, oy, oz, bs), idx, 1.0);
            ListenerCore.gsMsSound(world, loc, "entity.player.attack.sweep", 0.9f, 1.15f);
            if (idx == 5) {
                ListenerCore.gsMsSound(world, loc, "entity.evoker.cast_spell", 0.9f, 1.3f);
            }

            // ③ 伤害：以眼睛为原点、沿准星朝向的 **宽 5 × 长 9 × 高 3** 长方体
            //   ★ 把实体偏移**投影到 (f, r, u) 三个轴上** ⇒ 抬头就往高处打、低头就往低处打
            String selfId = u;
            java.util.Set<String> hit = new java.util.HashSet<>();
            double halfW = GS_EM_WIDE * 0.5;
            double halfH = GS_EM_H * 0.5 + 0.6;           // ★ 多给 0.6 格容差（脚在准星下方）
            double cx0 = ox + bs.fx * (GS_EM_RANGE * 0.5);
            double cy0 = oy + bs.fy * (GS_EM_RANGE * 0.5);
            double cz0 = oz + bs.fz * (GS_EM_RANGE * 0.5);
            Location cbox = ListenerCore.gsMsLoc(world, cx0, cy0, cz0);
            if (cbox != null) {
                List<Entity> list = null;
                try {
                    list = new ArrayList<>(world.getNearbyEntities(cbox,
                            GS_EM_RANGE * 0.5 + 0.8, GS_EM_H * 0.5 + 1.6, GS_EM_RANGE * 0.5 + 0.8));
                } catch (Throwable e) {
                    list = null;
                }
                if (list != null) {
                    for (Entity ent : list) {
                        if (!gsEmCanHit(ent, selfId)) {
                            continue;
                        }
                        String eid;
                        try {
                            eid = ListenerCore.uid(ent);
                        } catch (Throwable e) {
                            continue;
                        }
                        if (hit.contains(eid)) {
                            continue;
                        }
                        Location el;
                        try {
                            el = ent.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        // 实体"胸口"相对眼睛的偏移
                        double dx = el.getX() - ox;
                        double dy = (el.getY() + 0.9) - oy;
                        double dz = el.getZ() - oz;
                        double pf = dx * bs.fx + dy * bs.fy + dz * bs.fz;   // 沿准星方向
                        double pl = dx * bs.rx + dy * bs.ry + dz * bs.rz;   // 左右
                        double ph = dx * bs.ux + dy * bs.uy + dz * bs.uz;   // 垂直准星
                        if (pf < -0.4 || pf > GS_EM_RANGE) {
                            continue;                                   // 身后 0.4 容差
                        }
                        if (pl < -halfW || pl > halfW) {
                            continue;
                        }
                        if (ph < -halfH || ph > halfH) {
                            continue;
                        }
                        hit.add(eid);
                        gsEmHurt(ent, GS_EM_DMG, p);
                    }
                }
            }

            // ④ ★★ 第 6 刀专属：**圆圈范围内**的**额外**伤害
            //   圆面 = 由 e1（横向抬高 TILT）与 f（准星）张成，法线 n = e1 × f。
            //   判定：实体"胸口"到圆面的**垂距** <= GS_EM_S6_THICK
            //         且**面内距离** <= GS_EM_S6_R ⇒ 命中，扣 GS_EM_S6_DMG 点。
            //   ★ 这是**额外**的：已经被上面长方体打过的敌人**照样**再吃这 77 点，
            //     所以站在刀锋 + 圆圈里最多吃 37 + 77 = 114 点。
            //   ★ 圆圈是**以玩家为中心、360° 一圈**，身后也吃 —— 这正是圆圈的意义。
            if (idx == 5) {
                double ca6 = Math.cos(GS_EM_S6_TILT);
                double sa6 = Math.sin(GS_EM_S6_TILT);
                double e1x = bs.rx * ca6 + bs.ux * sa6;
                double e1y = bs.ry * ca6 + bs.uy * sa6;
                double e1z = bs.rz * ca6 + bs.uz * sa6;
                double nx = e1y * bs.fz - e1z * bs.fy;    // n = e1 × f
                double ny = e1z * bs.fx - e1x * bs.fz;
                double nz = e1x * bs.fy - e1y * bs.fx;
                double nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nl > 1.0e-6) {
                    nx /= nl;
                    ny /= nl;
                    nz /= nl;
                    Location c6 = ListenerCore.gsMsLoc(world, ox, oy, oz);
                    List<Entity> l6 = null;
                    if (c6 != null) {
                        try {
                            l6 = new ArrayList<>(world.getNearbyEntities(c6,
                                    GS_EM_S6_R + 1.0, GS_EM_S6_R + 1.0, GS_EM_S6_R + 1.0));
                        } catch (Throwable e) {
                            l6 = null;
                        }
                    }
                    if (l6 != null) {
                        for (Entity e6 : l6) {
                            if (!gsEmCanHit(e6, selfId)) {
                                continue;
                            }
                            Location el6;
                            try {
                                el6 = e6.getLocation();
                            } catch (Throwable e) {
                                continue;
                            }
                            double ddx = el6.getX() - ox;
                            double ddy = (el6.getY() + 0.9) - oy;
                            double ddz = el6.getZ() - oz;
                            double along = ddx * nx + ddy * ny + ddz * nz;   // 到圆面的垂距
                            if (Math.abs(along) > GS_EM_S6_THICK) {
                                continue;
                            }
                            double d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                            double inPlane2 = d2 - along * along;            // 面内距离的平方
                            if (inPlane2 > GS_EM_S6_R * GS_EM_S6_R) {
                                continue;
                            }
                            gsEmHurt(e6, GS_EM_S6_DMG, p);                   // ★ 额外伤害
                        }
                    }
                }
            }

            // ⑤ 推进计数器：打完第 6 刀后 gsEmNext 自动回到 1
            gsEmSet(p, n, now + GS_EM_RESET_MS);
            gsEmBarShow(p, n, GS_EM_RESET_MS, GS_EM_RESET_MS);

            // ⑥ ★★★ 第 6 刀专属 —— 次元斩（整段从右键挪过来）
            //   ★ 放在计数器推进**之后** ⇒ 此刻 gsEmGet(p).n 已经是 6，与「第六刀」一一对应
            if (idx == 5) {
                try {
                    gsEmDimension(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 左键第六刀 · 次元斩（★★ 从右键整段挪过来）
    // ======================================================================

    /**
     * {@code gsEmDrawBig(world, cx, cy, cz, b, rot, fade)} —— <b>红紫色大圆环</b>。
     *
     * <p>· 旧版：4 条斜线 × 前后两层 ⇒ 沿准星方向看过去是个<b>圆柱</b>。
     * · 新版：<b>一个正圆</b> —— 只用 {@code b.rx / b.ux}（横截面的左右 / 上下两个基向量），
     * <b>完全没有 {@code b.fx} 分量</b> ⇒ 所有点落在<b>同一张平面</b>上，圆柱彻底消失。
     * · 朝向仍然<b>跟着准星（含俯仰）</b>：环面永远垂直于视线 ⇒ 永远正对着你。
     * · 半径 = {@link #GS_EM_DS_BIG}，与伤害半径 {@link #GS_EM_DS_R} 对齐。
     * · {@code rot} 推进环上的<b>色带相位</b>（正圆自转看不出来，色带转才看得见）。
     * ★ 「粒子做稀」保留：{@code fade} 低时隔点画（step = 2）。
     */
    void gsEmDrawBig(World world, double cx, double cy, double cz, Frame b, double rot, double fade) {
        try {
            double r = GS_EM_DS_BIG;
            int n = GS_EM_DS_RING_N;
            int step = (fade < 0.4) ? 2 : 1;
            int phase = (int) Math.floor(rot * 8.0);
            for (int i = 0; i < n; i += step) {
                double a = (i / (double) n) * Math.PI * 2.0;
                double l = Math.cos(a) * r;               // 横截面内：左右
                double h = Math.sin(a) * r;               // 横截面内：上下
                gsEmDsDot(world,
                        cx + b.rx * l + b.ux * h,
                        cy + b.ry * l + b.uy * h,
                        cz + b.rz * l + b.uz * h, i, phase);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmDimension(p)} —— 次元斩（★ 只在左键第六刀里被调用，<b>冷却已删除</b>）。
     *
     * <p>★★ 落点与朝向<b>完全跟着准星</b>：沿视线方向往前 4 格（抬头就往高处扔）。
     */
    void gsEmDimension(Player p) {
        try {
            long now = ListenerCore.now();

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            double ox = 0.0;
            double oy = 0.0;
            double oz = 0.0;
            Location aimLoc;
            boolean gotEye = false;
            try {
                Location eye = p.getEyeLocation();
                if (eye != null) {
                    aimLoc = eye;
                    ox = eye.getX();
                    oy = eye.getY();
                    oz = eye.getZ();
                    gotEye = true;
                } else {
                    aimLoc = null;
                }
            } catch (Throwable e) {
                gotEye = false;
                aimLoc = null;
            }
            if (!gotEye) {
                aimLoc = loc;
                ox = loc.getX();
                oy = loc.getY() + 1.62;
                oz = loc.getZ();
            }
            Frame bs = gsEmBasis(aimLoc);

            double cx = ox + bs.fx * GS_EM_DS_DIST;
            double cy = oy + bs.fy * GS_EM_DS_DIST;
            double cz = oz + bs.fz * GS_EM_DS_DIST;

            Frame frame = mkFrame(world, 0.0, 0.0, 0.0, bs);
            DField f = new DField();
            f.w = world;
            f.owner = p;
            f.x = cx;
            f.y = cy;
            f.z = cz;
            f.bs = frame;
            f.born = now;
            f.nextHit = now + GS_EM_DS_EVERY;
            GS_EM_FIELDS.add(f);

            gsEmDrawBig(world, cx, cy, cz, frame, 0.0, 1.0);
            ListenerCore.gsMsSound(world, ListenerCore.gsMsLoc(world, cx, cy, cz),
                    "entity.enderman.teleport", 1.0f, 0.7f);
            ListenerCore.gsMsSoundAt(p, "entity.evoker.cast_spell", 1.0f, 0.8f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmFieldTick(f, now)} —— 次元斩领域：每 0.1 秒吸一次，每 0.5 秒打一次
     * （★ 只在第六刀出现 · 无冷却）。
     *
     * <p>★ 持续吸引的强度按距离<b>递减</b>（远强近弱）⇒ 拽到中心附近就自然停下，
     * 不会因为「每次都按满速拽」而在中心来回抖动。
     */
    boolean gsEmFieldTick(DField f, long now) {
        try {
            World world = f.w;
            if (world == null) {
                return false;
            }
            Location el2 = ListenerCore.gsMsLoc(world, f.x, f.y + 1.0, f.z);
            if (el2 == null) {
                return false;
            }

            List<Entity> list = null;
            try {
                list = new ArrayList<>(world.getNearbyEntities(el2, GS_EM_DS_R, GS_EM_DS_R, GS_EM_DS_R));
            } catch (Throwable e) {
                list = null;
            }
            String ownerId = "";
            try {
                ownerId = ListenerCore.uid(f.owner);
            } catch (Throwable e) {
                ownerId = "";
            }

            boolean doHit = (now >= f.nextHit);
            if (doHit) {
                f.nextHit = now + GS_EM_DS_EVERY;
            }

            if (list != null) {
                for (Entity ent : list) {
                    if (!gsEmCanHit(ent, ownerId)) {
                        continue;
                    }

                    // ① 持续吸引：把敌人往中心拽（每 0.1 秒一次）
                    try {
                        Location el = ent.getLocation();
                        double vx = f.x - el.getX();
                        double vz = f.z - el.getZ();
                        double d = Math.sqrt(vx * vx + vz * vz);
                        if (d > 0.20) {
                            double k = d / 1.5;
                            if (k > 1.0) {
                                k = 1.0;
                            }
                            vx = vx / d * GS_EM_DS_PULL * k;
                            vz = vz / d * GS_EM_DS_PULL * k;
                            double vy = 0.0;
                            try {
                                vy = ent.getVelocity().getY();
                            } catch (Throwable e) {
                                vy = 0.0;
                            }
                            if (vy > 0.25) {
                                vy = 0.25;
                            }
                            if (vy < -0.25) {
                                vy = -0.25;
                            }
                            ent.setVelocity(new Vector(vx, vy, vz));
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }

                    // ② 伤害：每 0.5 秒 25 点
                    if (doHit) {
                        gsEmHurt(ent, GS_EM_DS_DMG, f.owner);
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    // ======================================================================
    // 右键 / Shift+右键 · 锚点与裂缝
    // ======================================================================

    /**
     * {@code gsEmLorePlain(s)} —— 去掉颜色码，只留纯文本
     * （用来识别「哪一行是我们自己写的锚点行」）。
     *
     * <p>★ 跳过 {@code §} 或 {@code &} 之后的<b>一个</b>字符。用 {@code charAt}
     * 逐个 UTF-16 码元走 —— 与 JS 的 {@code t.charAt(i)} 逐位一致。
     */
    static String gsEmLorePlain(String s) {
        String t = String.valueOf(s);
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u00a7' || c == '&') {
                i++;
                continue;                                 // 跳过颜色码本身
            }
            o.append(c);
        }
        return o.toString();
    }

    /**
     * {@code gsEmLoreSync(p)} —— 把当前锚点写进<b>主手</b>那把阎魔刀的 lore。
     *
     * <p>做法：拿现有 lore ⇒ 去掉旧的锚点行（按纯文本<b>前缀</b>「锚点」识别，
     * 所以 items.yml 里那句「&amp;6右键&amp;7锚点」<b>不会被误删</b>）⇒ 末尾追加新的锚点行。
     * ★ 保留其它所有行 ⇒ 不会覆盖 RSC 或其它脚本加的内容。
     * ★ 没记录锚点时 lore 显示 {@code §8锚点 §7» §7§c未记录}（<b>不发聊天提示</b>）。
     */
    void gsEmLoreSync(Player p) {
        try {
            ItemStack item;
            try {
                item = p.getInventory().getItemInMainHand();
            } catch (Throwable e) {
                return;
            }
            if (!gsEmIs(item)) {
                return;                                   // 手里不是阎魔刀 ⇒ 不管
            }
            ItemMeta meta;
            try {
                meta = item.getItemMeta();
            } catch (Throwable e) {
                return;
            }
            if (meta == null) {
                return;
            }

            Anchor a = GS_EM_ANCHOR.get(ListenerCore.uid(p));
            String line = (a == null)
                    ? "\u00a78锚点 \u00a77\u00bb \u00a7c未记录"
                    : ("\u00a78锚点 \u00a77\u00bb \u00a7d" + Math.round(a.x) + ", "
                    + Math.round(a.y) + ", " + Math.round(a.z) + " \u00a78\u00b7 \u00a77" + a.w);

            List<String> out = new ArrayList<>();
            List<String> cur = null;
            try {
                cur = meta.getLore();
            } catch (Throwable e) {
                cur = null;
            }
            if (cur != null) {
                for (String raw : cur) {
                    String ln = String.valueOf(raw);
                    if (gsEmLorePlain(ln).indexOf("锚点") == 0) {
                        continue;                         // 旧的锚点行 ⇒ 丢掉
                    }
                    out.add(ln);
                }
            }
            out.add(line);
            meta.setLore(out);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmAnchorSet(p)} —— 右键记录锚点（★ 坐标写进 lore 代替聊天提示）。 */
    void gsEmAnchorSet(Player p) {
        try {
            Location l = p.getLocation();
            Anchor a = new Anchor();
            a.w = String.valueOf(p.getWorld().getName());
            a.x = l.getX();
            a.y = l.getY();
            a.z = l.getZ();
            a.yaw = l.getYaw();
            a.pitch = l.getPitch();
            GS_EM_ANCHOR.put(ListenerCore.uid(p), a);
            gsEmLoreSync(p);                              // ★ 坐标写进 lore（代替聊天提示）
            ListenerCore.gsMsSoundAt(p, "block.end_portal_frame.fill", 0.7f, 1.4f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmCrackPoint(c, l, h, f)} —— 裂缝几何：以「玩家身前 2 格」为中心、
     * 朝向玩家的一面立墙。横向 = 玩家左右（长 7）｜厚度 = 视线方向（宽 1）｜竖直 = 高 3。
     *
     * <p>★ 局部横向坐标 {@code l}：<b>负 = 左</b>（与 {@link #gsEmBasis} 的右向相反）。
     * ★★ 订正：「左 k」按<b>长 7 的格号</b>算 —— 左1 ~ 左7，正中间是 <b>左4.0</b>；
     * 几何上「长 7」从 {@code l = -3.5} 跨到 {@code +3.5}，而 左4.0 = {@code l = 0}
     * ⇒ 换算就是 {@code l = k − 4.0}（左1.5 ⇒ l = −2.5、左6.5 ⇒ l = +2.5）。
     */
    static Location gsEmCrackPoint(Crack c, double l, double h, double f) {
        try {
            return ListenerCore.gsMsLoc(c.w, c.x + c.fx * f + c.rx * l,
                    c.y + h,
                    c.z + c.fz * f + c.rz * l);
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsEmCrackOpen(p)} —— Shift+右键撕开裂缝（★ 冷却 5 秒）。 */
    void gsEmCrackOpen(Player p) {
        try {
            long now = ListenerCore.now();
            String u = ListenerCore.uid(p);
            Long last = GS_EM_CRACK_AT.get(u);
            if (last != null && (now - last) < GS_EM_CRACK_CD) {
                return;                                   // 5 秒冷却内不出第二道
            }
            GS_EM_CRACK_AT.put(u, now);

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }
            // ★★ 裂缝是一面**竖直的墙**，所以它的几何**只用水平方向** ——
            //   俯仰（抬头/低头）不参与，否则「长 7 宽 1 高 3」会被斜着拉变形。
            //   （用户这次要求「对着准星朝向」的只有**左键第六刀的次元斩**，锚点 / 裂缝不在其中。）
            Frame bs = gsEmBasis(loc);
            double hfx = bs.fx;
            double hfz = bs.fz;
            double hl = Math.sqrt(hfx * hfx + hfz * hfz);
            if (!(hl > 1.0e-4)) {
                hfx = 0.0;
                hfz = 1.0;
                hl = 1.0;                                 // 正上/正下看 ⇒ 退回 +Z
            }
            hfx /= hl;
            hfz /= hl;
            double cx = loc.getX() + hfx * GS_EM_CRACK_DIST;
            double cy = loc.getY();
            double cz = loc.getZ() + hfz * GS_EM_CRACK_DIST;

            Crack c = new Crack();
            c.w = world;
            c.owner = p;
            c.x = cx;
            c.y = cy;
            c.z = cz;
            c.fx = hfx;
            c.fz = hfz;
            c.rx = -hfz;
            c.rz = hfx;
            c.t = 0;
            c.used = false;
            GS_EM_CRACKS.add(c);
            ListenerCore.gsMsSound(world, ListenerCore.gsMsLoc(world, cx, cy + GS_EM_CRACK_MIDH, cz),
                    "block.end_portal.spawn", 0.8f, 0.8f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmCrackDrawH(c, prog)} —— 画横线：<b>从左到右</b>（progress 0..1）。 */
    void gsEmCrackDrawH(Crack c, double prog) {
        try {
            int n = 40;
            int upto = (int) Math.floor(n * prog);
            for (int i = 0; i <= upto; i++) {
                double t = i / (double) n;                // 0 = 最左（l = -half）… 1 = 最右
                double l = -GS_EM_CRACK_HALF + t * (GS_EM_CRACK_HALF * 2.0);
                Location pt = gsEmCrackPoint(c, l, GS_EM_CRACK_MIDH, 0.0);
                if (pt != null) {
                    gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), i);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmCrackDrawV(c, prog)} —— 画竖线：从上到下（progress 0..1）。 */
    void gsEmCrackDrawV(Crack c, double prog) {
        try {
            int n = 24;
            int upto = (int) Math.floor(n * prog);
            for (int i = 0; i <= upto; i++) {
                double t = i / (double) n;
                double h = GS_EM_CRACK_H - t * GS_EM_CRACK_H;   // 高 3 → 高 0
                Location pt = gsEmCrackPoint(c, 0.0, h, 0.0);
                if (pt != null) {
                    gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), i + 1);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmCrackDrawDiamond(c)} —— 画菱形：★ <b>填满</b>（逐行从左边填到右边），
     * 不是只描边。
     *
     * <p>★★ 大小 = <b>左1.5~左6.5（宽 5.0）</b> × <b>高0.7~高2.3（高 1.6）</b>
     * ⇒ 半宽 2.50、半高 0.80，左右上下都<b>居中</b>在十字交点上。
     * ★ 密度：14 行 × 每行最多 21 点 —— 中间那行是<b>一整条</b>（不是两个端点），
     * 所以看上去是一块实心的紫色菱形，而不是一个空心框。
     */
    void gsEmCrackDrawDiamond(Crack c) {
        try {
            int rows = 14;
            for (int r = 0; r <= rows; r++) {
                double u = (r / (double) rows) * 2.0 - 1.0;      // -1 .. 1（纵向）
                double halfL = GS_EM_DIA_R * (1.0 - Math.abs(u)); // 该行的半宽
                if (halfL <= 0.0) {
                    Location p0 = gsEmCrackPoint(c, 0.0, GS_EM_CRACK_MIDH + u * GS_EM_DIA_H, 0.0);
                    if (p0 != null) {
                        gsEmDot(c.w, p0.getX(), p0.getY(), p0.getZ(), r);
                    }
                    continue;
                }
                int cols = (int) Math.max(2L, Math.round(halfL * 8.0));
                for (int k = 0; k <= cols; k++) {
                    double l = -halfL + (2.0 * halfL) * (k / (double) cols);
                    Location pt = gsEmCrackPoint(c, l, GS_EM_CRACK_MIDH + u * GS_EM_DIA_H, 0.0);
                    if (pt != null) {
                        gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), r + k);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsEmCrackTick(c, dtMs)} —— 裂缝推进：返回 {@code false} 表示该删了。 */
    boolean gsEmCrackTick(Crack c, int dtMs) {
        try {
            if (c.used) {
                return false;
            }
            c.t = c.t + dtMs;
            int t = c.t;
            long drawEnd = GS_EM_CRACK_DRAW;

            if (t < drawEnd) {
                // ① 前一半画横线（从左到右），② 后一半画竖线（从上到下）
                double half = drawEnd / 2.0;
                if (t <= half) {
                    gsEmCrackDrawH(c, t / half);
                } else {
                    gsEmCrackDrawH(c, 1.0);
                    gsEmCrackDrawV(c, (t - half) / half);
                }
                return true;
            }

            // ③ 画完 ⇒ 菱形出现 + 可交互
            gsEmCrackDrawH(c, 1.0);
            gsEmCrackDrawV(c, 1.0);
            gsEmCrackDrawDiamond(c);

            if (t > drawEnd + GS_EM_CRACK_KEEP) {
                return false;                             // 留存 3 秒到点 ⇒ 消失
            }

            // ④ 触碰判定：玩家走到菱形上 ⇒ 传送到右键记录的锚点
            Player owner = c.owner;
            if (owner != null && owner.isOnline()) {
                Location dl = null;
                try {
                    dl = owner.getLocation();
                } catch (Throwable e) {
                    dl = null;
                }
                if (dl != null) {
                    double dx = dl.getX() - c.x;
                    double dy = dl.getY() - c.y;
                    double dz = dl.getZ() - c.z;
                    double lat = dx * c.rx + dz * c.rz;              // 横向偏移
                    double fwd = dx * c.fx + dz * c.fz;              // 前后偏移
                    double hh = dy - GS_EM_CRACK_MIDH;               // 相对菱形中心的高度
                    // 落在菱形"胶囊"内即可（横向按半宽、纵向按半高放宽一点）
                    if (Math.abs(lat) <= (GS_EM_DIA_R + GS_EM_TOUCH_R * 0.5)
                            && Math.abs(hh) <= (GS_EM_DIA_H + GS_EM_TOUCH_R)
                            && Math.abs(fwd) <= GS_EM_TOUCH_R) {
                        if (gsEmTeleport(owner)) {
                            c.used = true;
                            return false;
                        }
                    }
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsEmTeleport(p)} —— 传送到锚点。
     *
     * <p>★ 没记录锚点时<b>不再发聊天提示</b>（lore 上就写着「未记录」）；
     * ★ 传送成功也<b>不再发</b>「裂缝将你送回锚点」。
     */
    boolean gsEmTeleport(Player p) {
        try {
            Anchor a = GS_EM_ANCHOR.get(ListenerCore.uid(p));
            if (a == null) {
                return false;
            }
            World w = null;
            try {
                w = Bukkit.getServer().getWorld(a.w);
            } catch (Throwable e) {
                w = null;
            }
            if (w == null) {
                try {
                    w = p.getWorld();
                } catch (Throwable e) {
                    return false;
                }
            }
            Location dst = new Location(w, a.x, a.y, a.z, (float) a.yaw, (float) a.pitch);
            try {
                p.teleport(dst);
            } catch (Throwable e) {
                return false;
            }
            ListenerCore.gsMsSoundAt(p, "entity.enderman.teleport", 1.0f, 1.0f);
            return true;                                  // ★ 不再发「裂缝将你送回锚点」
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 心跳 / 世代号
    // ======================================================================

    /** {@code gsEmGenNow()} —— 读不到返回 <b>null</b>（★ 不是 -1）。 */
    Integer gsEmGenNow() {
        try {
            World w = Bukkit.getServer().getWorlds().get(0);
            Integer v = w.getPersistentDataContainer().get(GS_EM_K_GEN, PersistentDataType.INTEGER);
            return (v == null) ? Integer.valueOf(0) : v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsEmGenBump()}。 */
    void gsEmGenBump() {
        try {
            Integer cur = gsEmGenNow();
            if (cur == null) {
                cur = Integer.valueOf(0);
            }
            GS_EM_GEN = cur + 1;
            World w = Bukkit.getServer().getWorlds().get(0);
            w.getPersistentDataContainer().set(GS_EM_K_GEN, PersistentDataType.INTEGER, GS_EM_GEN);
        } catch (Throwable e) {
            if (GS_EM_GEN <= 0) {
                GS_EM_GEN = 1;
            }
        }
    }

    /** {@code gsEmGenOk()} —— ★ 读不到世代号 ⇒ 一律按自己的算（恒 true）。 */
    boolean gsEmGenOk() {
        try {
            Integer g = gsEmGenNow();
            if (g == null || GS_EM_GEN == -1) {
                return true;
            }
            return (g == GS_EM_GEN);
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsEmBarsClean()}。 */
    void gsEmBarsClean() {
        ListenerCore.gsBarTblClean(GS_EM_BARS);
    }

    /**
     * {@code gsEmReleaseAll()} —— {@code /rsc reload} 后旧实例：收血条 + 清特效 / 领域 / 裂缝。
     *
     * <p>★ 顺手把<b>手持增益</b>收干净。这是唯一能正确回收「我们开的那份飞行」的时机 ——
     * 新实例的表是空的，它并不知道旧实例给谁开过飞行。
     */
    void gsEmReleaseAll() {
        try {
            for (ListenerCore.BarRec rec : new ArrayList<>(GS_EM_BARS.values())) {
                try {
                    rec.b.removeAll();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            GS_EM_BARS.clear();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            GS_EM_FX = new ArrayList<>();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            GS_EM_FIELDS = new ArrayList<>();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            GS_EM_CRACKS = new ArrayList<>();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        // ★ 本实例被 /rsc reload 淘汰 ⇒ 顺手把手持增益收干净
        try {
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                try {
                    gsEmBuffClear(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsEmPulse()} —— 每 2 刻推进：血条 + 手持增益 + 斩击特效淡出 +
     * 次元斩领域 + 裂缝 + 血条清扫。
     */
    void gsEmPulse() {
        if (!gsEmGenOk()) {
            gsEmReleaseAll();
            return;
        }
        long now = ListenerCore.now();
        int dt = 100;                                     // 心跳周期（毫秒），固定 2 刻
        if (GS_EM_LAST_PULSE > 0) {
            dt = (int) (now - GS_EM_LAST_PULSE);
            if (dt <= 0) {
                dt = 100;
            }
            if (dt > 1000) {
                dt = 1000;                                // 卡顿保护：别让状态一下跳过头
            }
        }
        GS_EM_LAST_PULSE = now;

        // ① 连击血条；★ 顺带结算「手持增益」（拿在手上就生效 / 脱手立刻收）
        try {
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                try {
                    gsEmPlayerTick(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                try {
                    gsEmBuffTick(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ② 斩击特效淡出（GS_EM_FX_MS 内逐帧重画，越往后越稀）
        //   ★ 重画用的是**出刀那一刻存下来的整套坐标系** ⇒ 形状不会因为转头而歪
        try {
            List<Fx> keep = new ArrayList<>();
            for (int i = 0; i < GS_EM_FX.size(); i++) {
                Fx fx = GS_EM_FX.get(i);
                long age = now - fx.t0;
                if (age >= GS_EM_FX_MS) {
                    continue;
                }
                double fade = 1.0 - (age / (double) GS_EM_FX_MS);
                try {
                    gsEmDrawSlash(fx.p, fx.w, fx.fr, fx.idx, fade);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                keep.add(fx);
            }
            GS_EM_FX = keep;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ③ 次元斩领域（吸引 + 每 0.5 秒伤害），画面是**红紫色大圆环**、色带缓慢绕环转
        try {
            List<DField> keepF = new ArrayList<>();
            for (int j = 0; j < GS_EM_FIELDS.size(); j++) {
                DField f = GS_EM_FIELDS.get(j);
                long age2 = now - f.born;
                if (age2 >= GS_EM_DS_LIFE) {
                    continue;
                }
                double fade2 = 1.0 - (age2 / (double) GS_EM_DS_LIFE);
                if (f.drawAt == null || (now - f.drawAt) >= GS_EM_DS_DRAW_MS) {
                    f.drawAt = Long.valueOf(now);
                    try {
                        gsEmDrawBig(f.w, f.x, f.y, f.z, f.bs, age2 * 0.0016, fade2);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
                if (gsEmFieldTick(f, now)) {
                    keepF.add(f);
                }
            }
            GS_EM_FIELDS = keepF;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ④ 裂缝（绘画 → 留存 → 触碰传送）
        try {
            List<Crack> keepC = new ArrayList<>();
            for (int k = 0; k < GS_EM_CRACKS.size(); k++) {
                Crack c = GS_EM_CRACKS.get(k);
                if (gsEmCrackTick(c, dt)) {
                    keepC.add(c);
                }
            }
            GS_EM_CRACKS = keepC;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        try {
            gsEmBarsClean();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 去抖
    // ======================================================================

    /** {@code gsEmClickOnce(p, tag)}。 */
    boolean gsEmClickOnce(Player p, String tag) {
        try {
            long now = ListenerCore.now();
            String k = ListenerCore.uid(p) + "|" + tag;
            Long last = GS_EM_CLICK_AT.get(k);
            if (last != null && (now - last) < 80) {
                return false;
            }
            GS_EM_CLICK_AT.put(k, now);
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code gsEmRecentRight(p, gapMs)}。 */
    boolean gsEmRecentRight(Player p, long gapMs) {
        try {
            Long last = GS_EM_CLICK_AT.get(ListenerCore.uid(p) + "|R");
            if (last == null) {
                return false;
            }
            return (ListenerCore.now() - last) < gapMs;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 注册同步监听器（★ Java 版：幂等注册一次）
    // ======================================================================

    /**
     * {@code gsEmRegisterSync()} —— 注册 <b>7</b> 个 {@code HIGHEST} 监听器。
     *
     * <p>★★ 第六十四批：<b>不再注册 {@code PlayerSwapHandItemsEvent}</b> ——
     * 原来 F=锚点、Shift+F=裂缝，现在两个都搬到<b>右键</b>了
     * ⇒ 按 F 就是<b>原版换手</b>，阎魔刀一个字都不拦。
     */
    boolean gsEmRegisterSync() {
        if (GS_EM_REGISTERED) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new Sync(), GS.plugin());
            GS_EM_REGISTERED = true;
            ListenerCore.gsDaLog("阎魔刀·改监听器注册成功（左键=居合斩六刀·第六刀附带次元斩 / 右键=锚点·坐标写入lore / Shift+右键=裂缝 / F=原版换手）");
            return true;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("阎魔刀·改监听器注册异常: " + e);
            return false;
        }
    }

    /** 本子系统的 7 个监听器（★ 全部 {@code HIGHEST}，{@code ignoreCancelled} 默认 false）。 */
    final class Sync implements Listener {

        /**
         * ② 左键 = 居合斩（第六刀附带次元斩）；右键 = 锚点；Shift+右键 = 撕裂空间。
         *
         * <p>★ 故意<b>不</b>检查 {@code e.isCancelled()}：有些前置插件会先取消
         * {@code *_CLICK_AIR}。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsEmIs(gsEmHand(p))) {
                    return;
                }
                String hs = "";
                try {
                    hs = String.valueOf(e.getHand().name());
                } catch (Throwable e2) {
                    hs = "";
                }
                if (hs.length() > 0 && hs.contains("OFF")) {
                    return;                                   // 只认主手
                }

                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }

                if (act.contains("RIGHT_CLICK")) {
                    // ★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的假事件），
                    //   放过 ⇒ 否则机器会替旁边的人误放次元斩
                    try {
                        ItemStack evItem = e.getItem();
                        String evName = (evItem == null) ? "" : String.valueOf(evItem.getType().name());
                        if (evName.length() == 0 || evName.equals("AIR")) {
                            return;
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    if (!gsEmClickOnce(p, "R")) {
                        return;
                    }
                    e.setCancelled(true);
                    try {
                        e.setUseInteractedBlock(Event.Result.DENY);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        e.setUseItemInHand(Event.Result.DENY);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    GS_EM_CLICK_AT.put(ListenerCore.uid(p) + "|R", ListenerCore.now());
                    // ★★ 右键 = 锚点；**Shift + 右键 = 撕开裂缝**
                    boolean sneak2 = false;
                    try {
                        sneak2 = p.isSneaking();
                    } catch (Throwable e2s) {
                        sneak2 = false;
                    }
                    if (sneak2) {
                        gsEmCrackOpen(p);
                    } else {
                        gsEmAnchorSet(p);
                    }
                } else if (act.contains("LEFT_CLICK")) {
                    if (!gsEmClickOnce(p, "L")) {
                        return;
                    }
                    if (GS_EM_NO_BREAK) {
                        e.setCancelled(true);
                        try {
                            e.setUseInteractedBlock(Event.Result.DENY);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                        try {
                            e.setUseItemInHand(Event.Result.DENY);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                    gsEmSwing(p);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ③ 左键兜底：挥手动画（对着空气点也会发；与 ② 共用去抖 ⇒ 不会放两次）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onAnimation(PlayerAnimationEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                if (!gsEmIs(gsEmHand(p))) {
                    return;
                }
                try {
                    org.bukkit.event.player.PlayerAnimationType at = e.getAnimationType();
                    if (at != null) {
                        String an = String.valueOf(at.name());
                        if (!an.contains("SWING") && !an.contains("ARM")) {
                            return;
                        }
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                if (gsEmRecentRight(p, 300)) {
                    return;                                   // 右键附带的挥手 → 忽略
                }
                if (!gsEmClickOnce(p, "L")) {
                    return;
                }
                gsEmSwing(p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ④ 手持时挖不动方块（双保险）。
         *
         * <p>★ 原脚本是「{@code if (GS_EM_NO_BREAK)} 才注册」；这里因为
         * {@link #GS_EM_NO_BREAK} 是编译期常量 {@code true}，注册进来 + 内部判断
         * 与「条件注册」等价。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockBreak(BlockBreakEvent e) {
            try {
                if (!GS_EM_NO_BREAK) {
                    return;
                }
                if (!gsEmIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ④ 同上（{@code BlockDamageEvent}）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onBlockDamage(BlockDamageEvent e) {
            try {
                if (!GS_EM_NO_BREAK) {
                    return;
                }
                if (!gsEmIs(e.getPlayer().getInventory().getItemInMainHand())) {
                    return;
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ⑤ 玩家退出 ⇒ 清掉血条 + ★ 收走手持增益（飞行 / 药效）。
         * （锚点<b>保留</b>，下次进来还能用）
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onQuit(PlayerQuitEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                gsEmClear(p);
                gsEmBarDrop(p);
                try {
                    gsEmBuffClear(p);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ⑥ ★ 玩家进服 ⇒ 把锚点坐标同步进阎魔刀的 lore（等 1 秒，背包就位后再写）。
         *
         * <p>★ 锚点存在<b>脚本内存</b>里 ⇒ 重启后就是空的，这时 lore 会显示「未记录」，
         * 不会留着上一次的旧坐标误导人。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onJoin(PlayerJoinEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                GS.runLater(() -> gsEmLoreSync(p), 20L);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ⑥ ★ 切换主手物品 ⇒ 同步 lore。
         *
         * <p>★ 这个事件触发时主手<b>还没换完</b> ⇒ 延后 1 刻再读。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onItemHeld(PlayerItemHeldEvent e) {
            try {
                Player p = e.getPlayer();
                if (p == null) {
                    return;
                }
                GS.runLater(() -> gsEmLoreSync(p), 1L);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 启动（原脚本 10800~10835 行）
    // ======================================================================

    /**
     * {@code gsEmStart()} —— bump 世代号 + 注册监听器 + 排心跳 + 打加载日志。
     *
     * <p>★★ 心跳是 {@code runRepeating(gsEmPulse, 20, 2)} ——
     * <b>delay = 20 刻、period = 2 刻，两个参数不同</b>，别抄成一个。
     */
    public void gsEmStart() {
        try {
            gsEmGenBump();
            if (!gsEmRegisterSync()) {
                ListenerCore.gsDaWarn("阎魔刀·改启动失败：监听器没注册上（血条仍会跑）");
            }
            GS.runRepeating(this::gsEmPulse, 20L, 2L);   // 每 2 刻（0.1 秒）推进血条 / 特效 / 领域 / 裂缝
            try {
                ListenerCore.gsDaLog(gsEmStartLine(GS_EM_GEN));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("阎魔刀·改启动异常: " + e);
        }
    }

    /**
     * 原脚本 10808~10830 行的加载日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 抽成静态方法<b>只为让探针逐字节对账</b>，不改任何行为。
     * 对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 39 行）。
     *
     * @param gen {@code GS_EM_GEN}（运行期动态值，探针里单独处理）
     */
    static String gsEmStartLine(int gen) {
        return "阎魔刀·改（" + GS_EM_ID + "）已加载 · 居合斩 "
                + jsNum(GS_EM_DMG) + " 点 / 范围 宽" + jsNum(GS_EM_WIDE) + "×长" + jsNum(GS_EM_RANGE)
                + "×高" + jsNum(GS_EM_H)
                + " / " + GS_EM_SLASH_N + " 刀一循环（冷却 " + jsNum(GS_EM_CD / 1000.0)
                + " 秒 · 特效 " + jsNum(GS_EM_FX_MS / 1000.0) + " 秒 · 停手 " + jsNum(GS_EM_RESET_MS / 1000.0)
                + " 秒清空 · 前五刀「画圆圈切掉后面」成朝前半圆（下→上 / 横斩(右→左) / 左上→右下"
                + " / 横斩(左→右) / 三弧顶点相撞，前方进深压扁 " + jsNum(GS_EM_ARCH)
                + "）· 第5刀三弧顶点相撞于前方 · 第6刀半径" + jsNum(GS_EM_S6_R)
                + " 左高右低圆圈 + 圈内额外 " + jsNum(GS_EM_S6_DMG)
                + " 点）· 朝向 全部沿准星（含俯仰）· 第六刀次元斩 身前 " + jsNum(GS_EM_DS_DIST)
                + " 格 / 半径 " + jsNum(GS_EM_DS_R)
                + " 格 / 画面=红紫色大圆环(半径" + jsNum(GS_EM_DS_BIG) + "·" + GS_EM_DS_RING_N + "点)"
                + " / 每 " + jsNum(GS_EM_DS_EVERY / 1000.0) + " 秒 " + jsNum(GS_EM_DS_DMG)
                + " 点 / 存在 " + jsNum(GS_EM_DS_LIFE / 1000.0) + " 秒 / 无冷却 / 持续吸引"
                + " / 画面每 " + jsNum(GS_EM_DS_DRAW_MS / 1000.0) + " 秒重画（粒子做稀）"
                + " · 裂缝 长" + jsNum(GS_EM_CRACK_HALF * 2) + "×宽1×高" + jsNum(GS_EM_CRACK_H)
                + " / 绘画 " + jsNum(GS_EM_CRACK_DRAW / 1000.0) + " 秒 / 留存 " + jsNum(GS_EM_CRACK_KEEP / 1000.0)
                + " 秒 / 冷却 " + jsNum(GS_EM_CRACK_CD / 1000.0) + " 秒"
                + " / 菱形 宽" + jsNum(GS_EM_DIA_R * 2) + "×高" + jsNum(GS_EM_DIA_H * 2)
                + " · 手持增益 可飞行+抗性IV+生命恢复III+速度II+夜视"
                + "（一次 " + jsNum(GS_EM_BUFF_TICKS / 20.0) + " 秒 · 剩余 " + jsNum(GS_EM_BUFF_REF / 20.0)
                + " 秒续期 · 脱手立刻收走）"
                + " · 右键=锚点（坐标写入 lore，不发聊天提示） · Shift+右键=裂缝"
                + " · F=原版换手 · 世界世代号 " + gen;
    }

    /** 原脚本 10792 行的注册日志（不含前缀），供探针对账。 */
    static String gsEmRegisterLine() {
        return "阎魔刀·改监听器注册成功（左键=居合斩六刀·第六刀附带次元斩 / 右键=锚点·坐标写入lore / Shift+右键=裂缝 / F=原版换手）";
    }
}
