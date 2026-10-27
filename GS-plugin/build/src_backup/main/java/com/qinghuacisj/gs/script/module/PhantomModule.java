package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Explosive;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.WitherSkull;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * {@code gs_boss_phantom.js} 的 Java 化版本 —— 「幻翼 boss」（单阶段 · 消耗品）。
 *
 * <p>右键 = 召唤一只 boss（<b>消耗品</b>：召唤成功才扣 1 个）。物品 id
 * {@link #PH_ITEM_ID}（{@code GS_PHANTOM}），每个玩家同时只能有 1 只活着的
 * （再右键静默忽略，防连点刷爆）。
 *
 * <p><b>长相 / 体型</b>：原版<b>幻翼</b>，体型档 {@link #PH_SIZE} = 5；
 * 幻翼基础碰撞箱 0.9 × 0.5，体型档再乘 {@code (1 + 0.15 × size)}，
 * 然后用原版缩放属性 {@code Attribute.SCALE} 等比放大到约
 * <b>4.5 宽 × 2.5 高</b>（{@link #PH_SCALE} ≈ 2.857）。
 * 缩放是<b>等比</b>的（原版没有「只改高不改宽」的公开接口），
 * 所以做不到凋零那种 0.9×3.5 的瘦高形状。
 * 白天不烧、不自然消失、不掉原版掉落物。
 *
 * <p><b>血量 1000</b>（{@link #PH_HP}），本服 {@code MAX_HEALTH} 上限 1024 的加固：
 * 「先确认上限 → 再 setHealth」+ 延迟重试（{@code [1,3,6,10,20,40]} 刻）
 * + 失败降级（按 {@code hpScale} 等比缩伤 ⇒ 等效血量不变）。
 * <b>普攻 = 原版幻翼伤害</b>：<b>不写</b> {@code ATTACK_DAMAGE} 属性 ——
 * 幻翼原版近战 = {@code 6 + 体型档} ⇒ 本 boss = <b>11 点</b>。
 *
 * <p><b>可视化血条</b>：一条 BossBar，进度 = 当前血量 / 上限；
 * {@link #PH_BAR_ONLY_OWNER} = true ⇒ <b>只有召唤者本人看得见</b>。
 *
 * <p><b>五个技能</b>（数值按需求，冷却单位毫秒；AI 心跳 {@link #PH_TICK} = 5 刻，
 * 每次只放一个技能，五个冷却各自独立走）：
 * <pre>
 *   ① 坚守者冲击波 冷却 13000 · 蓄力 20 刻 · 伤害 10 点 · 射线 30 格 · 击退 水平 2.5 / 垂直 0.5
 *   ② 龙息弹 ×3    冷却  7000 · 1 枚瞄准 + 2 枚随机落点（散布 8 格）· 弹速 1.1
 *   ③ 凋零之首 ×3  冷却 11000 · 1 枚追击 + 2 枚随机落点 · 转向系数 0.22 · 10 秒自毁 · 不炸地形
 *   ④ 三叉戟       冷却  5000 · 伤害 8（原版溺尸投掷）· 落点召唤闪电苦力怕
 *   ⑤ 蓄力冲刺撞击 冷却 17000 · 蓄力 30 刻（3 次烟花音效）· 冲刺 2.2 格/刻 · 命中 55 点
 * </pre>
 * ★ 技能 ⑤ 的 17 秒冷却<b>从开始蓄力那一刻</b>算起（{@code ref.cd5} 当场置为当前时间）。
 *
 * <p><b>阵营</b>：boss / 闪电苦力怕 / 自家弹体（凋零之首、三叉戟、龙息云）之间互相免伤，
 * 也不会互相索敌 —— 免得 boss 一炮把自己刚召唤的苦力怕打死。
 * 打的是 PDC 标记 {@code gs:boss} 系列（<b>不是</b> gs:summon）⇒
 * 梦境护腿的杀戮光环照常能打到它。
 *
 * <p>★ <b>不能与方块交互</b>：手持本物品右键方块时方块功能一律取消（HIGHEST 监听器），
 * 但照样召唤 boss —— 和「妄念」「冰」同一个套路，用 {@link InteractGuard}。
 *
 * <p>★★ <b>加载期不注册监听器</b>：原脚本的 {@code phRegisterEvents} / {@code phRegisterGuard}
 * <b>只在 {@code onUse} 里惰性调用</b>。参考服日志里只有 4 行（已加载 / 参数 / 技能 / PDC），
 * <b>没有</b>「伤害 / 死亡 / 爆炸拦截 已启用」—— 这就是机械证据：
 * 注册必须发生在第一次右键，而不是脚本加载时。本移植照此办理。
 *
 * <hr>
 * <p><b>★ 不照抄的死代码</b>（逐条确认过，删掉不改变任何可观察行为）：
 * <ol>
 *   <li>{@code PH_REG_EXP} / {@code PH_REG_DEATH} 两个顶层 {@code var} ——
 *       只声明、<b>从不读也从不写</b>（真 JS 里恒为 false，全文件没有任何引用点）。
 *       真正被读的闩锁只有 {@code PH_REG_DMG}（→ 静态 {@link #EVENTS_REGISTERED}）
 *       与 {@code PH_REG_GUARD}（→ {@link InteractGuard} 内部去重）。</li>
 *   <li>{@code phSetDmg(ent, want)} 整个函数 —— <b>定义了但全文件没有一处调用</b>
 *       （原脚本注释写着「想改成固定值：在 phSummonBoss 里补一句 phSetDmg(ent, 你的值)」，
 *       说明它是留给甲方改数值的钩子，当前是死的）。
 *       ★ 连它的依赖 {@code ATTACK_DAMAGE} / {@code GENERIC_ATTACK_DAMAGE} 查找一并省掉
 *       ⇒ 普攻就是原版幻翼的 {@code 6 + 体型档}。</li>
 *   <li>{@code phNow} 的降级链 {@code new Date().getTime()} → {@code 0} ——
 *       {@code System.currentTimeMillis()} 在 Java 下不会失败，那两级永远走不到。</li>
 *   <li>{@code phPlugin} 的四级解析链（{@code RSC.INSTANCE} → 插件名 ×2 → Slimefun）——
 *       原生版自己就是插件，等价物是 {@link GS#pluginOrNull()}（与本项目其它模块一致）。</li>
 *   <li>{@code phAttrKey} 的 {@code GENERIC_SCALE} / {@code GENERIC_MAX_HEALTH} /
 *       {@code GENERIC_ATTACK_DAMAGE} 旧名，以及 {@code phFindMaxAttr} 里
 *       「遍历 {@code Attribute.values()} 找名字含 MAX_HEALTH」的兜底 ——
 *       1.21.11 里 {@code Attribute} 已经是<b>接口</b>、{@code values()} 不存在，
 *       且新名 {@code SCALE} / {@code MAX_HEALTH} 一定存在 ⇒ 全部走不到。</li>
 *   <li>{@code PH_MAXATTR} 这个缓存 —— 只有在「按名字找属性」那条路上才有意义，
 *       原生版直接写 {@code Attribute.MAX_HEALTH}，缓存没有对象。</li>
 *   <li>{@code phP} / {@code phET} 的 {@code P[name]} 与 {@code P.valueOf(name)}
 *       双重查找 —— Java 枚举只有 {@code valueOf} 一条路，等价。</li>
 *   <li>{@code phBarMake} 里 {@code BarStyle.SEGMENTED_20} 取不到时的
 *       {@code SEGMENTED_10} / {@code SOLID} 退路，以及 {@code BarColor.RED}
 *       取不到时的 {@code valueOf('RED')} —— Java 枚举常量编译期就确定，退路不可达。</li>
 *   <li>{@code phSpawnBoss} 的 {@code typeof e.setSize === 'function'} /
 *       {@code typeof e.setShouldBurnInDay === 'function'}、
 *       {@code phTick} 的同名探测、{@code phPetSpawn} 的 {@code typeof c.setPowered} ——
 *       实体类型写死 PHANTOM / CREEPER，Java 侧 {@code instanceof} 即可。</li>
 *   <li>{@code phWitherSkulls} 的 {@code typeof sk.setCharged} / {@code setYield} /
 *       {@code setIsIncendiary} 探测 —— 实体类型写死 WITHER_SKULL，一定实现
 *       {@link WitherSkull} 与 {@link Explosive}。</li>
 *   <li>{@code phTrident} 的 {@code typeof tr.setDamage === 'function'} ——
 *       实体类型写死 TRIDENT ⇒ 一定是 {@link AbstractArrow}。</li>
 *   <li>{@code phOnExplode} 的 {@code typeof e.setYield === 'function'} ——
 *       {@code EntityExplodeEvent#setYield} 是编译期确定存在的。</li>
 *   <li>{@code phFireworkStack} 的 {@code Material.FIREWORK} 退路 ——
 *       1.21.11 里 {@code FIREWORK_ROCKET} 一定存在。</li>
 *   <li>{@code phSpawnBoss} 注释里提到的「{@code LootTables.EMPTY} / 置空战利品表」——
 *       原脚本自己就写明 1.21.11 没有这两样、那段代码是静默失败的；本移植不写。</li>
 *   <li>若干 {@code null} 判定：{@code phApplyHpOnce} 的 {@code hp == null}、
 *       {@code phTick} 的 {@code fx != null}、{@code phUnstick} 的
 *       {@code ref.unstickAt == null ? 0 : ...}、{@code phBarSync} 的
 *       {@code rf.hpScale == null}、{@code phVanillaStack} 的 {@code amount == null} ——
 *       这些在 JS 里是「字段可能缺」，Java 的 {@code double} / {@code long} / {@code int}
 *       不是引用，字段一定存在且已初始化 ⇒ 判定恒真/恒假，删掉等价。</li>
 *   <li>{@code phDashLaunch} 的 {@code st.phase = 'fly'} 与 {@code phDashTick} 的
 *       {@code st.hit = false} —— <b>写了从不读</b>（write-only 状态）。</li>
 *   <li>★★ <b>{@code setDirection} 不是死路径</b>（这里曾经写错过，特此更正）：
 *       {@code setDirection} <b>声明在 {@code Fireball} 上</b>
 *       （{@code Fireball extends Projectile, Explosive}），
 *       而 {@code WitherSkull extends Fireball}、{@code DragonFireball extends Fireball}
 *       ⇒ <b>凋零之首与龙息弹都有</b>、两句 {@code setDirection} <b>真的生效</b>；
 *       只有 {@code Trident}（{@code extends AbstractArrow, ThrowableProjectile}）
 *       没有 ⇒ 那一处抛异常被 {@code catch} 吞掉。
 *       <br>★ <b>踩过的坑</b>：{@code javap org.bukkit.entity.Entity} /
 *       {@code javap org.bukkit.entity.Projectile} 都<b>看不到</b> {@code setDirection}，
 *       但那是"javap 对接口<b>只列自己声明的方法、不列继承来的</b>"——
 *       {@code Fireball} 这一层才声明它。<b>不能用父接口的 javap 结果判定子接口有没有某方法</b>
 *       （要判定就写个类去编译，或 javap 到声明它的那一层）。
 *       <br>★ Java 侧 {@code instanceof Fireball} 就是原脚本
 *       {@code try { pr.setDirection(...); } catch (e) { }} 的<b>静态化</b>：
 *       是 Fireball 族就调用（生效），否则跳过（等价于"抛了被吞"）。</li>
 *   <li>{@code phTrident} 的 {@code PH_TR_LIFE} 兜底之外，{@code phTridentTrack}
 *       里 {@code sp = 9.0} 的初值 —— 只在 {@code getVelocity()} 抛异常时才用得上，
 *       是「异常兜底」不是死代码，<b>保留</b>。</li>
 * </ol>
 * <p>★ {@code phOnExplode} 里「1 刻后按快照还原方块」那一支<b>不删</b>：
 * 正常情况确实不会触发（{@code blockList().clear()} 之后没有方块被炸），
 * 但它是原脚本明写的「真保险」，删掉就改变了极端情况下的行为。
 *
 * <hr>
 * <p><b>★ 与原脚本的差异</b>（全部已在类内注释标注，逐条说明为什么不影响可观察行为）：
 * <ol>
 *   <li><b>监听器只注册一次</b>：原脚本用脚本级 {@code PH_REG_DMG} 闩锁，
 *       RSC reload 会重复注册（监听器泄漏，且旧实例的 {@code PH_ACTIVE} 永远不释放）。
 *       这里用静态闩锁 {@link #EVENTS_REGISTERED} + {@link #CURRENT} 转发到当前实例
 *       —— 首次注册的时机、日志行、条数（4）完全一致，只是不再累积监听器。</li>
 *   <li><b>方块交互拦截走 {@link InteractGuard}</b>：已机械比对，
 *       {@code phRegisterGuard} 的函数体与它逐字相同（只差物品 ID 常量）。</li>
 *   <li><b>{@code Math.random} 走可注入的 {@link #RANDOM}</b>：生产环境就是
 *       {@code Math::random}，与 JS 完全一致；可注入只是为了探针能逐值对账
 *       「1 枚瞄准 + 2 枚随机落点」的每一个坐标。</li>
 *   <li><b>NMS {@code refreshDimensions}</b>：原脚本调 {@code ent.getHandle().refreshDimensions()}。
 *       1.21.11 的 paper-api <b>没有</b>公开等价方法，这里用反射走同一条 NMS 路
 *       （{@code getHandle()} → {@code refreshDimensions()}）；拿不到也只是「没立刻刷新」，
 *       属性本身已经写进去了（1.21.3+ 服务端在 SCALE 变更时自己也会刷一次）。</li>
 *   <li><b>{@code toFixed} / 数字转字符串</b>：JS 的 {@code Number#toFixed} 与
 *       {@code '' + 数字} 有自己的一套十进制格式化规则（Java 的 {@code String.format}
 *       与 {@code Double.toString} 在边界上不一样，{@code Double.toString(1000.0)}
 *       会多出 {@code ".0"}）。这里按 ECMA-262 的规则重写
 *       {@link #toFixed} / {@link #jsNum}，日志行才能与参考服逐字节相同。
 *       <br>★ <b>这两条语义务必写清楚，防止以后被"优化"回去</b>：
 *       <ul>
 *         <li>{@code toFixed(f)} = 对<b>精确二进制值</b>取绝对值后
 *             <b>平局远离零</b>（{@code HALF_UP}），再补回符号。
 *             注意两点：① {@code (-0.05).toFixed(0)} 是 <b>{@code "-0"}</b>
 *             （结果是 0 但输入为负 ⇒ 负号要留）；② {@code (-2.05).toFixed(1)}
 *             是 <b>{@code "-2.0"}</b> 而不是 {@code "-2.1"} ——
 *             因为 {@code -2.05} 的精确二进制值是 {@code -2.04999999999999982…}，
 *             比 2.05 的绝对值小。所以<b>必须</b>用 {@code new BigDecimal(x)}
 *             （精确构造器）而<b>不能</b>用 {@code BigDecimal.valueOf(x)}
 *             （它走 {@code Double.toString} 的最短表示，会算错）。
 *             <br>★ 平局方向是<b>实测</b>结论：ECMA-262 的正文写的是"取较大的 n"，
 *             但 GraalJS 25.1.3 实测 {@code (-2.5).toFixed(0) === "-3"}，
 *             而参考服跑的就是 GraalJS ⇒ 以实测为准。</li>
 *         <li>{@code String(x)} = <b>最短可往返</b>十进制表示：有效数字位数尽量少，
 *             且能解析回同一个 double。指数 {@code k} 满足 {@code -6 < k ≤ 21} 时
 *             走普通记数法，否则走科学记数法（<b>小写 e</b>、指数<b>带符号</b>、
 *             <b>无多余前导零</b>）；整数值<b>不带 {@code .0}</b>；
 *             {@code ±0 → "0"}。Java 的 {@code Double.toString} 两条都不满足
 *             （{@code "4.9E-324"} vs JS 的 {@code "5e-324"}、
 *             {@code "1000.0"} vs JS 的 {@code "1000"}），
 *             所以不能只做大小写替换，得自己找最短表示（见 {@link #shortestDecimal}）。</li>
 *       </ul>
 *       <br>★ {@link #toFixed} / {@link #jsNum} / {@link #shortestDecimal}
 *       都是<b>无副作用的纯静态方法</b>，可以直接搬到别处
 *       （本项目另有 3 份同源拷贝）。</li>
 *   <li><b>PDC 的 {@code PersistentDataType} 用 Java 常量</b>：
 *       JS 走 {@code Java.type('...PersistentDataType')} 存进 {@code PH_PDT}；
 *       原生版直接写 {@link PersistentDataType#STRING}。同一个对象，无行为差异。</li>
 *   <li><b>{@code phPetLoop} / 残留清扫用方法引用而不是闭包</b>：
 *       JS 里 {@code phPetLoop} 是<b>块内函数声明</b>（提升到函数作用域）；
 *       Java 里是实例方法，自调度链（每 100 刻）逐字对应。</li>
 * </ol>
 *
 * <p><b>★ 不确定 / 没法在纯 Java 下 100% 等价的地方</b>：
 * <ol>
 *   <li>{@code ent.getHandle().refreshDimensions()} 走反射 —— 若未来服务端把
 *       {@code refreshDimensions} 改成非 public，反射会失败（原脚本的 NMS 直调也会同时失效）。
 *       退化行为与「拿不到 handle」完全一致。</li>
 *   <li>原脚本用 {@code pm.registerEvent(...)} 分别注册 4 条；原生版用
 *       {@code registerEvents(一个 Listener, 4 个 @EventHandler)}。Bukkit 的
 *       {@code EntityDamageEvent} 与 {@code EntityDamageByEntityEvent}
 *       <b>共用同一个 HandlerList</b>（后者继承前者的静态 {@code getHandlerList()}），
 *       所以两种写法的派发顺序与集合完全相同；唯一的差别是
 *       「一次 registerEvents 全成功 / 全失败」而不是「逐条成败」——
 *       在真机上要么 4 条全成功要么全抛，实际不可能部分成功。</li>
 * </ol>
 */
public final class PhantomModule extends AbstractModule {

    // ==================================================================
    // 参数 —— 名字与 JS 顶层 var 逐字同名（常量对账探针按名字反射比对）
    // ==================================================================

    static final String PH_ITEM_ID = "GS_PHANTOM";
    static final boolean PH_CONSUME = true;        // ★ 消耗品：召唤成功才扣 1 个
    static final double PH_SPAWN_AHEAD = 4.0;      // 在玩家前方 4 格、上方 3 格生成（幻翼是飞行的）
    static final double PH_SPAWN_UP = 3.0;
    static final String PH_KEYNAME = "boss_phantom";   // PDC 标记键名（→ gs:boss_phantom）
    static final int PH_DROP_XP = 120000;          // ★ 击杀掉落的经验值（第十三批修订：0 → 120000）

    /** ★★ 名字：前两个 {@code \uFFFD} 是需求原文里就带的替换符，原样保留（没有做任何"修复"）。 */
    static final String PH_NAME = "\uFFFD\u4DCD\uA7508\uFFFDk\uA66D#\u4DCE";

    static final double PH_HP = 1000.0;            // 血量（本服实体上限 1024 ⇒ 1000 写得进去）
    static final int PH_SIZE = 5;                  // 幻翼体型档（影响模型 & 基础碰撞箱 ×(1+0.15×size)）
    static final double PH_BOX_W = 4.5;            // ★ 期望碰撞箱宽度（格）；缩放值按它反推
    /** {@code PH_BOX_W / (0.9 * (1.0 + 0.15 * PH_SIZE))} ⇒ ≈ 2.857。★ 算式逐字照抄。 */
    static final double PH_SCALE = PH_BOX_W / (0.9 * (1.0 + 0.15 * PH_SIZE));
    static final int PH_TICK = 5;                  // AI 心跳：每 5 刻（0.25 秒）
    static final int PH_FIRST_MS = 4000;           // ★ 出场 4 秒内**不放技能**
    static final double PH_SEEK = 48.0;            // 找目标的范围
    static final int PH_HP_FIX_MS = 6000;          // 出生后 6 秒内每拍重试血量上限
    static final boolean PH_CREATIVE_FALLBACK = true;  // 场上没有非创造玩家时，是否允许打创造模式
    static final int PH_UNSTICK_MS = 4000;         // ★ 原地不动超过这么久（且目标很远）⇒ 防卡位救援
    static final int PH_UNSTICK_GAP = 10000;       // 两次救援之间的最小间隔

    // ---- 技能 1：坚守者冲击波 ----
    static final int PH_SB_CD = 13000;             // 冷却 13 秒
    static final int PH_SB_CHARGE = 20;            // 蓄力 20 刻 = 1 秒
    static final double PH_SB_DMG = 10.0;          // ★ 原版坚守者冲击波伤害 = 10 点
    static final double PH_SB_RANGE = 30.0;        // 射线最长 30 格（原版口径）
    static final double PH_SB_HIT_R = 1.0;         // 沿射线每格的判定半径
    static final double PH_SB_KB = 2.5;            // 水平击退（原版）
    static final double PH_SB_KB_UP = 0.5;         // 垂直击退（原版）

    // ---- 技能 2：三枚龙息弹 ----
    static final int PH_DF_CD = 7000;              // 冷却 7 秒
    static final int PH_DF_N = 3;                  // 一共几枚
    static final int PH_DF_RAND = 2;               // 其中几枚随机落点（剩下的瞄准玩家）
    static final double PH_DF_SPREAD = 8.0;        // 随机落点的散布半径
    static final double PH_DF_SPEED = 1.1;         // 弹速（原版龙息弹约 1.0）

    // ---- 技能 3：三枚凋零之首 ----
    static final int PH_WS_CD = 11000;             // 冷却 11 秒
    static final int PH_WS_N = 3;                  // 一共几枚
    static final int PH_WS_RAND = 2;               // 其中几枚随机落点
    static final double PH_WS_SPREAD = 8.0;        // 随机落点的散布半径
    static final double PH_WS_SPEED = 1.0;         // 弹速
    static final double PH_WS_TURN = 0.22;         // ★ 追击头的转向系数（越大越粘人；0 = 直飞）
    static final int PH_WS_GUIDE_T = 2;            // 每 2 刻修正一次方向
    static final int PH_WS_LIFE = 200;             // 10 秒后自毁（remove 不爆炸，不留残留）

    // ---- 技能 4：投掷三叉戟 + 落点召唤闪电苦力怕 ----
    static final int PH_TR_CD = 5000;              // 冷却 5 秒
    static final double PH_TR_SPEED = 1.6;         // 投掷速度
    static final double PH_TR_DMG = 8.0;           // ★ 原版溺尸投掷伤害 = 8 点
    static final int PH_TR_LIFE = 120;             // 6 秒还没落地 ⇒ 按当前位置当落点兜底
    // ★ 弹道补偿：三叉戟是箭类弹体（有重力 0.05 格/刻²）⇒ 远距离会往下掉。
    //   按「水平距离 ÷ 速度」估飞行时间，把落点抬高 ½·g·t²，再乘这个系数（1.0 = 精确补偿）。
    static final double PH_TR_GRAV = 0.05;
    static final double PH_TR_LEAD = 1.0;
    static final double PH_TR_LEAD_CAP = 12.0;     // 抬高的上限（格），防止超远距离抬到天上
    static final int PH_PET_MAX = 4;               // 场上最多几只闪电苦力怕
    static final int PH_PET_LIFE = 1200;           // 60 秒后自动消失（防卡服）
    // ★★ 第六十三批：`PH_PET_GRIEF` 常量已**删除**（用户「第三格boss生成的闪电苦力怕
    //    删除破坏方块功能」）。苦力怕的爆炸现在**无条件**不掉方块，见 phIsOurExplosive。

    // ---- 技能 5：蓄力冲刺撞击 ----
    //   ★ 2026-10-09 第十三批新增。冷却**从开始蓄力那一刻**算起（17 秒 = 蓄力 1.5 秒 + 15.5 秒空窗）。
    static final int PH_DA_CD = 17000;             // 冷却 17 秒
    static final int PH_DA_CHARGE = 30;            // 蓄力 30 刻 = 1.5 秒
    static final int PH_DA_FW_TIMES = 3;           // 蓄力期间放 3 次**烟花音效**（每 0.5 秒一次）
    static final double PH_DA_SPEED = 2.2;         // 冲刺速度（格/刻）⇒ 44 格/秒（玩家跑速 5.6 格/秒）
    static final double PH_DA_DMG = 55.0;          // ★ 命中伤害 55 点
    static final double PH_DA_HIT_R = 2.5;         // 命中判定半径（水平，格）—— boss 体型 4.5 宽，给 2.5
    static final double PH_DA_HIT_V = 2.5;         // 命中判定垂直容差（格）
    static final double PH_DA_MAX = 64.0;          // 单次冲刺最长距离（格）

    // ---- 血条 ----
    static final boolean PH_BAR = true;
    static final boolean PH_BAR_ONLY_OWNER = true; // true = 只有召唤者本人看得到
    static final double PH_BAR_RANGE = 64.0;

    // ---- 标记值：boss=本体 / pet=闪电苦力怕 / skull=凋零之首 / trident=三叉戟 ----
    static final String PH_V_BOSS = "boss";
    static final String PH_V_PET = "pet";
    static final String PH_V_SKULL = "skull";
    static final String PH_V_TRIDENT = "trident";

    // ==================================================================
    // 探针钩子（不是原脚本的常量，故不是 final ⇒ 不参与常量对账）
    // ==================================================================

    /**
     * ★ JS {@code Math.random} 的替身。
     *
     * <p>生产环境就是 {@code Math::random}，与真 JS 完全一致；
     * 之所以做成可替换的，是为了让 {@code PhantomParityProbe} 注入同一条确定性序列，
     * 把「1 枚瞄准 + 2 枚随机落点」的<b>每一个坐标</b>都拿来与真 JS 逐值对账 ——
     * 否则随机落点这一块只能做「看起来在半径内」这种弱断言。
     */
    static DoubleSupplier RANDOM = Math::random;

    // ==================================================================
    // 运行期状态（非静态 ⇒ 等价于原脚本的顶层 var；实例 = 一次顶层求值）
    // ==================================================================

    /** 玩家 uuid → boss 状态（原 {@code PH_ACTIVE}）。★ 用 LinkedHashMap 对齐 JS 对象的插入序。 */
    private final Map<String, Ref> active = new LinkedHashMap<>();
    /** 玩家 uuid → 上次召唤时间戳（原 {@code PH_LAST}，防同一次点击被派发两遍）。 */
    private final Map<String, Long> last = new HashMap<>();
    /** 苦力怕 uuid → { 实体, 时间戳 }（原 {@code PH_PETS}）。 */
    private final Map<String, Pet> pets = new LinkedHashMap<>();
    /** 已经报过「血量设置失败」的实体 uuid（原 {@code PH_HP_DIAG}，同一个实体只报一次）。 */
    private final Set<String> hpDiag = new HashSet<>();

    /** PDC 键 {@code gs:boss_phantom}（原 {@code PH_KEY}）；{@code fromString} 返回 null 时也是 null。 */
    private final NamespacedKey key;

    // ==================================================================
    // 静态登记（幂等 + 转发到「当前实例」）
    // ==================================================================

    /** 当前活跃的模块实例 —— 监听器是静态的，永远转发到最新实例。 */
    private static volatile PhantomModule CURRENT;

    /** 伤害 / 死亡 / 爆炸 四条监听器是否已挂上（原脚本的 {@code PH_REG_DMG} 闩锁）。 */
    private static volatile boolean EVENTS_REGISTERED = false;

    // ==================================================================
    // 构造器 = 原脚本的顶层代码
    // ==================================================================

    public PhantomModule() {
        // ① 加载标记（原脚本第一行，在常量声明之前）
        try {
            Bukkit.getLogger().info(loadLine());
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }

        // ② 启动自检：参数行 + 技能行（原脚本在文件末尾，顺序与日志一致）
        try {
            Bukkit.getLogger().info(paramLine());
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            Bukkit.getLogger().info(skillLine());
        } catch (Throwable ignored) {
            // 静默
        }

        // ③ PDC 键初始化
        //   ★★ 必须用**静态工厂 fromString**，**绝对不要** `new NamespacedKey(plugin, name)`：
        //      GraalJS 对 (Plugin,String) 与 (String,String) 两个构造器**无法消歧义**，
        //      会抛 `Multiple applicable overloads found` ⇒ PH_KEY / PH_PDT 全为 null
        //      ⇒ phTagOf() 恒返回 '' ⇒ **死亡事件第一句就 return**，
        //        掉落清空 / 经验 / 自定义掉落 **一条都不会执行**（2026-10-09 线上实测）。
        //   ★ 原脚本不判 fromString 的返回值 ⇒ 真返回 null 时会打出「PDC 键 null 初始化 OK」。
        //      这里照抄（不"修好"它）。
        NamespacedKey k = null;
        try {
            k = NamespacedKey.fromString("gs:" + PH_KEYNAME);
            try {
                Bukkit.getLogger().info(pdcLine(k));
            } catch (Throwable e0) {
                // 静默
            }
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 幻翼boss：PDC 键初始化失败 " + e);
            } catch (Throwable e2) {
                // 静默
            }
        }
        this.key = k;

        CURRENT = this;

        // ④ 苦力怕数量 / 存活清扫：每 5 秒一次（自调度链，与原脚本一致）
        try {
            GS.runLater(this::phPetLoop, 100L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ⑤ ★ reload 残留清扫：加载 3 秒后扫全世界，把上一份脚本留下的
        //    boss / 闪电苦力怕 / 弹体（靠 PDC 标记识别）清掉 —— 否则它们是「没人管」的，
        //    血条没了、技能也不会放，只会站着挨打。
        try {
            GS.runLater(this::phPurgeLeftovers, 60L);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    @Override
    public String name() {
        return "gs_boss_phantom";
    }

    // ==================================================================
    // 日志行（抽成 static 方法 ⇒ 探针能逐字节对账）
    // ==================================================================

    /** 加载标记行。 */
    static String loadLine() {
        return "[构思技艺] gs_boss_phantom 已加载";
    }

    /**
     * 参数自检行。
     *
     * <p>★ 原脚本用的是<b>字符串拼接</b>：{@code '' + PH_HP} 得到 {@code "1000"}（不是
     * {@code "1000.0"}），{@code PH_SCALE.toFixed(3)} 得到 {@code "2.857"}。
     * 所以这里必须走 {@link #jsNum} / {@link #toFixed}，不能用 {@code String.valueOf(double)}。
     */
    static String paramLine() {
        return "[构思技艺] 幻翼boss 参数：血量=" + jsNum(PH_HP)
                + " 普攻=原版（" + (6 + PH_SIZE) + " 点）"
                + " 缩放=" + toFixed(PH_SCALE, 3) + "（目标碰撞箱 " + jsNum(PH_BOX_W) + " × "
                + toFixed(phBoxH(PH_SCALE, PH_SIZE), 2) + "，体型档=" + PH_SIZE + "）";
    }

    /**
     * 技能自检行。
     *
     * <p>★ 里面的 {@code ①②③④}、{@code ×}、{@code →}、{@code ｜} 全是<b>字面文本</b>，原样保留。
     * <br>★ {@code PH_SB_CD / 1000} 这类整除：四个冷却（13000/7000/11000/5000）与
     * {@code PH_FIRST_MS}（4000）都是 1000 的整数倍 ⇒ JS 浮点除法与 Java 整除<b>结果相同</b>。
     */
    static String skillLine() {
        return "[构思技艺] 幻翼boss 技能："
                + "①坚守者冲击波 " + jsNum(PH_SB_DMG) + "点/" + (PH_SB_CD / 1000) + "秒"
                + " ②龙息弹×" + PH_DF_N + "（瞄准1+随机" + PH_DF_RAND + "）" + (PH_DF_CD / 1000) + "秒"
                + " ③凋零之首×" + PH_WS_N + "（追击1+随机" + PH_WS_RAND + "，不炸地形）"
                + (PH_WS_CD / 1000) + "秒"
                + " ④三叉戟" + jsNum(PH_TR_DMG) + "点→落点闪电苦力怕" + (PH_TR_CD / 1000) + "秒"
                + " ｜ 出场保护=" + (PH_FIRST_MS / 1000) + "秒 心跳=" + PH_TICK + "刻";
    }

    /** PDC 初始化行。★ 原脚本拼的是 {@code PH_KEY} 这个 {@code NamespacedKey} 对象的 toString。 */
    static String pdcLine(NamespacedKey key) {
        return "[构思技艺] 幻翼boss：PDC 键 " + key + " 初始化 OK";
    }

    // ==================================================================
    // 数字格式化：ECMA-262 的 Number#toFixed 与 Number::toString
    // ==================================================================

    /**
     * JS {@code Number.prototype.toFixed(f)} 的等价实现。
     *
     * <p>★★ <b>平局方向以「实测」为准，不是以规范文字为准</b>：
     * ECMA-262 写的是「取整数 n 使 {@code n / 10^f - x} 的绝对值最小；若有两个，
     * <b>取较大的 n</b>」—— 按字面 {@code (-2.5).toFixed(0)} 该是 {@code "-2"}。
     * 但 <b>GraalJS 25.1.3 实测给的是 {@code "-3"}</b>（即"平局远离零"），
     * 而参考服跑的就是 GraalJS ⇒ <b>以 GraalJS 的实测行为为准</b>。
     * 所以这里统一用 {@link RoundingMode#HALF_UP}（平局远离零），对正负号一视同仁。
     * 探针里拿 76 个值 × 9 个位数（含 {@code ±0.5 / ±1.5 / ±2.5 / ±0.05 / ±2.675}）
     * 与真 GraalJS 逐字节对过账。
     *
     * <p>★ <b>负零</b>：{@code (-0.05).toFixed(0)} 在 JS 里是 <b>{@code "-0"}</b>、
     * {@code (-0.004).toFixed(2)} 是 <b>{@code "-0.00"}</b>（负号保留）。
     * {@code BigDecimal} 没有负零，所以这里把符号单独拆出来处理 ——
     * 判据是 {@code v < 0}（{@code -0.0} 不满足 ⇒ 与 JS 一样不给负号）。
     *
     * <p>★ 用的是 {@code new BigDecimal(v)}（把 double 的<b>精确</b>二进制值展开，
     * 不是 {@code BigDecimal.valueOf} 的最短表示）—— 因为 {@code toFixed} 规范
     * 就是对精确值做的：{@code (1.005).toFixed(2) === "1.00"}、
     * {@code (0.1).toFixed(20)} 会打出 {@code 0.10000000000000000555}。
     */
    static String toFixed(double v, int f) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (Math.abs(v) >= 1.0e21) {
            return jsNum(v);                       // 规范：≥ 10^21 直接走 ToString(x)
        }
        try {
            boolean neg = v < 0;                   // ★ -0.0 不 < 0 ⇒ 不给负号（同 JS）
            String s = new BigDecimal(Math.abs(v))
                    .setScale(f, RoundingMode.HALF_UP)
                    .toPlainString();
            return neg ? ("-" + s) : s;
        } catch (Throwable e) {
            return jsNum(v);
        }
    }

    /**
     * JS {@code '' + 数字} 的等价实现（{@code Number::toString} 的十进制规则）。
     *
     * <p>关键差别：JS 对整数值的 double <b>不带 {@code .0}</b>（{@code '' + 1000.0 === "1000"}），
     * 而 Java 的 {@code Double.toString(1000.0)} 是 {@code "1000.0"} ——
     * 直接拼进日志行就会与参考服差一个字节。
     *
     * <p>规则（ECMA-262 §6.1.6.1.20）：把<b>最短</b>十进制有效数字记作 s（k 位）、
     * 指数位 n，则
     * <ul>
     *   <li>{@code k ≤ n ≤ 21} ⇒ s 后面补 {@code n-k} 个 0</li>
     *   <li>{@code 0 < n ≤ 21} ⇒ s 前 n 位 + {@code .} + 余下位</li>
     *   <li>{@code -6 < n ≤ 0} ⇒ {@code 0.} + {@code -n} 个 0 + s</li>
     *   <li>否则 ⇒ 科学计数法 {@code d.ddde±x}（尾数不带 {@code .0}）</li>
     * </ul>
     *
     * <p>★ <b>为什么不能直接用 {@code Double.toString}</b>：Java 的
     * {@code Double.toString} 在次正规数上不是"最短往返"
     * （{@code Double.toString(Double.MIN_VALUE)} 是 {@code "4.9E-324"}，
     * 而 JS {@code String(5e-324)} 是 {@code "5e-324"}）。
     * 所以这里自己找最短表示：从 1 位有效数字开始加，直到能<b>解析回同一个 double</b>
     * —— 这正是 ECMA-262 对 s 的定义（"k as small as possible"）。
     */
    static String jsNum(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        if (v == 0.0) {
            return "0";                            // ★ 含 -0.0：JS String(-0) === "0"
        }
        if (v < 0) {
            return "-" + jsNum(-v);
        }
        BigDecimal bd = shortestDecimal(v);
        String digits = bd.unscaledValue().toString();
        int k = digits.length();
        int n = k - bd.scale();
        if (k <= n && n <= 21) {
            StringBuilder sb = new StringBuilder(digits);
            for (int i = 0; i < n - k; i++) {
                sb.append('0');
            }
            return sb.toString();
        }
        if (0 < n && n <= 21) {
            return digits.substring(0, n) + "." + digits.substring(n);
        }
        if (-6 < n && n <= 0) {
            StringBuilder sb = new StringBuilder("0.");
            for (int i = 0; i < -n; i++) {
                sb.append('0');
            }
            return sb.append(digits).toString();
        }
        String e = String.valueOf(Math.abs(n - 1));
        String sign = (n - 1 >= 0) ? "+" : "-";
        if (k == 1) {
            return digits + "e" + sign + e;
        }
        return digits.charAt(0) + "." + digits.substring(1) + "e" + sign + e;
    }

    /**
     * ECMA-262 的「最短往返十进制」：有效数字位数 k <b>尽量小</b>，
     * 且 {@code s × 10^(n-k)} 能<b>解析回同一个 double</b>。
     *
     * <p>做法：拿 double 的<b>精确</b>十进制值，从 1 位有效数字开始逐步加位
     * （{@link RoundingMode#HALF_EVEN}），第一个能往返的就是规范要的那个 s。
     * 17 位一定能往返（IEEE-754 double 的性质），所以循环必然在 17 步内结束。
     *
     * <p>★ 这样才与 JS 的 {@code String(数字)} 一致：
     * {@code 5e-324}（而不是 Java 的 {@code 4.9E-324}）、
     * {@code 1234567890123456800}（而不是 {@code 1.2345678901234568E18}）。
     */
    private static BigDecimal shortestDecimal(double v) {
        BigDecimal exact = new BigDecimal(v);
        for (int p = 1; p <= 17; p++) {
            BigDecimal c = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (c.doubleValue() == v) {
                return c.stripTrailingZeros();
            }
        }
        return exact.stripTrailingZeros();
    }

    // ==================================================================
    // 可单测的算式（抽出来只为对账，算式逐字照抄原脚本）
    // ==================================================================

    /** 缩放值反推：{@code 期望宽度 / (幻翼基础宽 0.9 × (1 + 0.15 × 体型档))}。 */
    static double phScaleFor(double boxW, int size) {
        return boxW / (0.9 * (1.0 + 0.15 * size));
    }

    /** 缩放后的碰撞箱高度：{@code 0.5 × (1 + 0.15 × 体型档) × 缩放值}。 */
    static double phBoxH(double scale, int size) {
        return 0.5 * (1.0 + 0.15 * size) * scale;
    }

    /**
     * 三叉戟的弹道补偿抬高量（原 {@code phTrident} 里那四行）。
     *
     * <pre>
     *   tt   = 水平距离 / PH_TR_SPEED
     *   drop = 0.5 × PH_TR_GRAV × tt² × PH_TR_LEAD      // 钳到 [0, PH_TR_LEAD_CAP]
     * </pre>
     */
    static double phTridentDrop(double dh) {
        double tt = dh / PH_TR_SPEED;
        double drop = 0.5 * PH_TR_GRAV * tt * tt * PH_TR_LEAD;
        if (drop > PH_TR_LEAD_CAP) {
            drop = PH_TR_LEAD_CAP;
        }
        if (drop < 0.0) {
            drop = 0.0;
        }
        return drop;
    }

    /** 弹体瞄准方向（单位向量）；两点几乎重合时返回 null（原 {@code phLaunch} 的 {@code n < 1e-4}）。 */
    static double[] phAim(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 1.0e-4) {
            return null;
        }
        return new double[]{dx / n, dy / n, dz / n};
    }

    /**
     * 凋零之首追击的转向算式（原 {@code phSkullGuide} 的 1174~1197 行）。
     *
     * <pre>
     *   旧方向归一化（速度 &lt; 0.05 时退回 (0,0,1) 且长度取 PH_WS_SPEED）
     *   新方向 = normalize( 旧方向 × (1 - PH_WS_TURN) + 指向目标 × PH_WS_TURN )
     * </pre>
     *
     * @return {@code {nx, ny, nz}}（已乘 PH_WS_SPEED 的新速度）；不满足修正条件时返回 null
     *         （原脚本那时<b>什么都不做</b>：既不 setDirection 也不 setVelocity）
     */
    static double[] phSkullSteer(double vx, double vy, double vz,
                                 double sx, double sy, double sz,
                                 double tx, double ty, double tz) {
        double dx = vx;
        double dy = vy;
        double dz = vz;
        double sp = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (sp < 0.05) {
            sp = PH_WS_SPEED;
            dx = 0;
            dy = 0;
            dz = 1;
        }
        dx /= sp;
        dy /= sp;
        dz /= sp;

        double wx = tx - sx;
        double wy = (ty + 1.0) - sy;
        double wz = tz - sz;
        double wn = Math.sqrt(wx * wx + wy * wy + wz * wz);
        if (!(wn > 1.0e-4)) {
            return null;
        }
        wx /= wn;
        wy /= wn;
        wz /= wn;
        double k = PH_WS_TURN;
        double nx = dx * (1.0 - k) + wx * k;
        double ny = dy * (1.0 - k) + wy * k;
        double nz = dz * (1.0 - k) + wz * k;
        double nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(nn > 1.0e-4)) {
            return null;
        }
        return new double[]{nx / nn * PH_WS_SPEED, ny / nn * PH_WS_SPEED, nz / nn * PH_WS_SPEED};
    }

    /**
     * 蓄力冲刺的命中判定（原 {@code phDashTick} 的 1453~1456 行）。
     *
     * <p>★ 垂直判定用的是<b>脚下 Y</b> 之差（不是眼睛/胸口），逐字照抄。
     */
    static boolean phDashHitTest(double px, double py, double pz,
                                 double nx, double ny, double nz) {
        double hx = px - nx;
        double hz = pz - nz;
        double hyd = Math.sqrt(hx * hx + hz * hz);
        double vyd = Math.abs(py - ny);
        return hyd <= PH_DA_HIT_R && vyd <= PH_DA_HIT_V;
    }

    /**
     * 蓄力期间烟花音效的「几刻一次」（原 {@code phDashChargeTick} 的 1362 行）：
     * {@code max(1, floor(ceil(PH_DA_CHARGE / 2) / PH_DA_FW_TIMES))} ⇒ 30/2=15，15/3 ⇒ <b>5</b>。
     */
    static int phFwPerSteps() {
        return (int) Math.max(1, Math.floor(Math.ceil(PH_DA_CHARGE / 2.0) / PH_DA_FW_TIMES));
    }

    /**
     * 第 {@code step} 刻该不该放烟花音效；该放则返回音高，不放返回 {@code -1}。
     *
     * <p>音高 = {@code 0.9 + idx × 0.2}（idx = {@code floor(step / perSteps)}，上限 3 次）。
     */
    static double phFwPitchAt(int step) {
        int perSteps = phFwPerSteps();
        int idx = (int) Math.floor(step / (double) perSteps);
        if (step % perSteps == 0 && idx < PH_DA_FW_TIMES) {
            return 0.9 + idx * 0.2;
        }
        return -1.0;
    }

    /**
     * 「1 枚瞄准 + N-1 枚随机落点」的落点表（原 {@code phSpreadPoints}）。
     *
     * <p>★ 第 1 个点 = 玩家当前位置（<b>直线瞄准，不追踪</b>）；
     * 其余 = 玩家周围 {@code spreadR} 格内随机取点（半径系数 {@code 0.35 + 0.65×rand}），
     * 落点用 {@link #phGroundY} 自动贴地，再统一 {@code +1.2} 抬高。
     *
     * @return 每个元素是 {@code {x, y, z}}
     */
    static List<double[]> phSpreadPointsCore(double px, double py, double pz,
                                             World world, int total, double spreadR) {
        List<double[]> out = new ArrayList<>();
        try {
            out.add(new double[]{px, py + 1.2, pz});
            for (int i = 1; i < total; i++) {
                double a = RANDOM.getAsDouble() * Math.PI * 2.0;
                double r = spreadR * (0.35 + 0.65 * RANDOM.getAsDouble());
                double x = px + Math.cos(a) * r;
                double z = pz + Math.sin(a) * r;
                double y = phGroundY(world, x, z, py);
                out.add(new double[]{x, y + 1.2, z});
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默（已经算出来的点照样用）
        }
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    /** 当前毫秒时间戳。★ 原脚本的 {@code new Date()} / {@code 0} 两级降级在 Java 下不可达。 */
    private static long phNow() {
        return Guards.now();
    }

    private static boolean phAlive(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            return !(e.isDead() || !e.isValid());
        } catch (Throwable e2) {
            return false;
        }
    }

    /** PDC 标记读取（原 {@code phTagOf}）；键没建出来时恒返回空串。 */
    private String phTagOf(Entity e) {
        try {
            if (e == null || key == null) {
                return "";
            }
            String v = e.getPersistentDataContainer().get(key, PersistentDataType.STRING);
            return (v == null ? "" : String.valueOf(v));
        } catch (Throwable err) {
            return "";
        }
    }

    /** PDC 标记写入（原 {@code phSetTag}）。 */
    private void phSetTag(Entity e, String val) {
        try {
            if (e == null || key == null) {
                return;
            }
            e.getPersistentDataContainer().set(key, PersistentDataType.STRING, val);
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    private boolean phIsBoss(Entity e) {
        return PH_V_BOSS.equals(phTagOf(e));
    }

    /** ★「自己人」= 本体 + 闪电苦力怕（弹体不算，弹体只用来认归属）。 */
    private boolean phIsFriend(Entity e) {
        String t = phTagOf(e);
        return PH_V_BOSS.equals(t) || PH_V_PET.equals(t);
    }

    private static double phDist(Entity a, Entity b) {
        try {
            return a.getLocation().distance(b.getLocation());
        } catch (Throwable e) {
            return 999.0;
        }
    }

    /**
     * 按候选名取枚举常量（原 {@code phP} / {@code phET} 的「按名字找，找不到换下一个」）。
     *
     * <p>★ 原脚本每条候选都试 {@code P[name]} 再试 {@code P.valueOf(name)}；
     * Java 枚举只有 {@code valueOf} 一条路，但「按顺序取第一个存在的名字」这个语义完全一致
     * —— 1.21.11 里改过名的粒子（{@code SMOKE_LARGE} → {@code LARGE_SMOKE}、
     * {@code EXPLOSION_NORMAL} → {@code EXPLOSION}）因此照样能落到正确的那个。
     */
    private static <T extends Enum<T>> T phEnum(Class<T> type, String... names) {
        try {
            for (String n : names) {
                try {
                    return Enum.valueOf(type, n);
                } catch (Throwable e) {
                    // 换下一个候选
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
        return null;
    }

    private static Particle phP(String... cands) {
        return phEnum(Particle.class, cands);
    }

    private static EntityType phET(String... names) {
        return phEnum(EntityType.class, names);
    }

    private static void phFx(World world, Particle pt, double x, double y, double z,
                             int n, double dx, double dy, double dz, double speed) {
        try {
            if (world == null || pt == null) {
                return;
            }
            world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private static void phSound(World world, Location loc, String name, double vol, double pitch) {
        try {
            try {
                world.playSound(loc, name, (float) vol, (float) pitch);
            } catch (Throwable e1) {
                try {
                    loc.getWorld().playSound(loc, name, (float) vol, (float) pitch);
                } catch (Throwable e2) {
                    // 静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 方块的通行判定（原 {@code phPassableAt}）。★ 任何异常都当「可通行」。 */
    private static boolean phPassableAt(World world, double bx, double by, double bz) {
        try {
            Block blk = world.getBlockAt(new Location(world, 0 + bx, 0 + by, 0 + bz));
            if (blk == null) {
                return true;
            }
            Material mt = null;
            try {
                mt = blk.getType();
            } catch (Throwable e) {
                mt = null;
            }
            if (mt != null) {
                try {
                    if (mt.isAir()) {
                        return true;
                    }
                } catch (Throwable e) {
                    // 静默
                }
            }
            try {
                return blk.isPassable();
            } catch (Throwable e) {
                // 落到下面的兜底
            }
            if (mt != null) {
                try {
                    return !mt.isSolid();
                } catch (Throwable e) {
                    // 静默
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /**
     * 在 {@code (x, z)} 附近找一个「能站」的地面高度（返回地面上一格）。
     *
     * <p>★ 先从上往下找「自己可通行 + 下面一格不可通行」的那一层；
     * 找不到就从 {@code start} 往上找第一个可通行层；再找不到返回 {@code y0}。
     * <p>★ 搜索窗口 {@code [start-6, start+8]} 再钳到 {@code [-60, 250]}，逐字照抄。
     */
    private static double phGroundY(World world, double x, double z, double y0) {
        try {
            double bx = Math.floor(x);
            double bz = Math.floor(z);
            double start = Math.floor(y0);
            double lo = start - 6;
            double hi = start + 8;
            if (lo < -60) {
                lo = -60;
            }
            if (hi > 250) {
                hi = 250;
            }
            double y;
            for (y = start + 4; y >= lo; y--) {
                if (phPassableAt(world, bx, y, bz) && !phPassableAt(world, bx, y - 1, bz)) {
                    return y;
                }
            }
            for (y = start; y <= hi; y++) {
                if (phPassableAt(world, bx, y, bz)) {
                    return y;
                }
            }
            return y0;
        } catch (Throwable e) {
            return y0;
        }
    }

    // ==================================================================
    // 属性 / 血量
    // ==================================================================

    /** ≥1.21.3 = {@code SCALE}。★ {@code GENERIC_SCALE} 旧名在 1.21.11 不存在。 */
    private static Attribute phScaleAttr() {
        try {
            return Attribute.SCALE;
        } catch (Throwable e) {
            return null;
        }
    }

    /** ≥1.21.3 = {@code MAX_HEALTH}。★ 原脚本的「遍历 Attribute 找名字」兜底不可达。 */
    private static Attribute phFindMaxAttr() {
        try {
            return Attribute.MAX_HEALTH;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 把最大血量属性抬到 {@code hp}。
     *
     * <p>★ 先把该属性上的<b>所有修饰符摘掉</b>再写基础值（否则别的插件挂的
     * {@code ADD_NUMBER} 会把上限顶回去），最后再补一刀已被 Paper 标记为过时但**仍然可用**的
     * {@code setMaxHealth}。
     */
    private static boolean phForceMax(LivingEntity ent, double hp) {
        boolean ok = false;
        try {
            Attribute k = phFindMaxAttr();
            if (k != null) {
                AttributeInstance inst = ent.getAttribute(k);
                if (inst != null) {
                    try {
                        List<AttributeModifier> snap = new ArrayList<>(inst.getModifiers());
                        for (AttributeModifier m : snap) {
                            try {
                                inst.removeModifier(m);
                            } catch (Throwable e) {
                                // 静默
                            }
                        }
                    } catch (Throwable e) {
                        // 静默
                    }
                    try {
                        inst.setBaseValue(hp);
                        ok = true;
                    } catch (Throwable e) {
                        // 静默
                    }
                }
            }
        } catch (Throwable e) {
            // 静默
        }
        try {
            ent.setMaxHealth(hp);
            ok = true;
        } catch (Throwable e) {
            // 静默
        }
        return ok;
    }

    /** 读实体的实际上限（属性口径），拿不到返回 0。 */
    private static double phMaxHp(LivingEntity ent) {
        try {
            Attribute k = phFindMaxAttr();
            if (k == null) {
                return 0;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return 0;
            }
            return inst.getValue();
        } catch (Throwable e) {
            return 0;
        }
    }

    /** 「血量设置失败」每个实体只报一次（原 {@code phHpDiag} 的 {@code PH_HP_DIAG} 去重）。 */
    private void phHpDiag(LivingEntity ent, double want, double max, double cur, String why) {
        try {
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                id = "unknown";
            }
            if (!hpDiag.add(id)) {
                return;
            }
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                tn = "?";
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 幻翼boss 血量设置失败(" + why + ")：实体="
                        + tn + " 目标=" + want + " 实际上限=" + max + " 实际血量=" + cur);
            } catch (Throwable e2) {
                // 静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 一次性的血量写入：先抬上限 → 确认上限真的上去了（差 &gt; 0.5 视为没上去）→
     * {@code setHealth} → 确认血量真的回满了。
     *
     * @return 是否成功（上限没上去 或 血量没回满 都算失败，并记一条 diag）
     */
    private boolean phApplyHpOnce(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return false;
            }
            phForceMax(ent, hp);
            double mx = phMaxHp(ent);
            if (mx < hp - 0.5) {
                phHpDiag(ent, hp, mx, -1, "上限没上去");
                return false;
            }
            try {
                ent.setHealth(hp);
            } catch (Throwable e) {
                // 静默
            }
            double now = -1;
            try {
                now = ent.getHealth();
            } catch (Throwable e) {
                now = -1;
            }
            if (now >= hp - 0.5) {
                return true;
            }
            phHpDiag(ent, hp, mx, now, "血量没回满");
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 血量写入 + 失败重试。
     *
     * <p>★ 第一次没成功就排 {@code [1,3,6,10,20,40]} 刻的六次重试 ——
     * 服务端有时要等实体完全加入世界之后才认 {@code MAX_HEALTH}。
     */
    private void phApplyHp(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return;
            }
            if (phApplyHpOnce(ent, hp)) {
                return;
            }
            int[] delays = {1, 3, 6, 10, 20, 40};
            for (int d : delays) {
                GS.runLater(() -> {
                    try {
                        if (!phAlive(ent)) {
                            return;
                        }
                        phApplyHpOnce(ent, hp);
                    } catch (Throwable e) {
                        // 静默
                    }
                }, d);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 碰撞体积：原版缩放属性 {@code Attribute.SCALE}。
     *
     * <p>幻翼最终尺寸 = 基础 0.9×0.5 × (1 + 0.15×size) × 缩放值。
     * 写完属性<b>立刻 {@code refreshDimensions()}</b>（NMS 上是 public，走反射），
     * 否则要等实体自己想起来刷新，血条都出来了箱子还没变大。
     * 每一步都包 try/catch：万一取不到 handle，也只是「没放大」，不会崩。
     *
     * <p>★ 已经是目标值（差 ≤ 0.001）就直接返回：<b>不每拍调 refreshDimensions</b>
     * （没必要地折腾碰撞箱）。
     */
    private static boolean phApplyScale(LivingEntity ent) {
        try {
            Attribute k = phScaleAttr();
            if (k == null) {
                return false;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return false;
            }
            double cur = -1.0;
            try {
                cur = inst.getBaseValue();
            } catch (Throwable e) {
                cur = -1.0;
            }
            if (Math.abs(cur - PH_SCALE) <= 0.001) {
                return true;
            }
            try {
                inst.setBaseValue(PH_SCALE);
            } catch (Throwable e) {
                // 静默
            }
            refreshDimensions(ent);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * NMS 的 {@code refreshDimensions()}（原脚本的 {@code ent.getHandle().refreshDimensions()}）。
     *
     * <p>1.21.11 的 paper-api 没有公开等价方法（{@code Entity} 上只有
     * {@code getWidth()} / {@code getHeight()} 两个读取口），所以走反射进 NMS：
     * {@code CraftEntity#getHandle()} → {@code Entity#refreshDimensions()}，
     * 两者在服务端实现里都是 public。拿不到也只是「没立刻刷新」——
     * 1.21.3+ 的服务端在 {@code SCALE} 属性变更时自己也会调一次
     * {@code LivingEntity#onAttributeUpdated} → {@code refreshDimensions()}，
     * 所以属性本身写进去了，尺寸一定会跟上。
     */
    private static void refreshDimensions(Entity ent) {
        try {
            Object handle = ent.getClass().getMethod("getHandle").invoke(ent);
            if (handle == null) {
                return;
            }
            handle.getClass().getMethod("refreshDimensions").invoke(handle);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 把实际碰撞箱打进后台（对得上 = 缩放真的生效了）。 */
    private static void phLogBox(LivingEntity ent) {
        try {
            double w = -1;
            double h = -1;
            try {
                w = ent.getWidth();
            } catch (Throwable e) {
                w = -1;
            }
            try {
                h = ent.getHeight();
            } catch (Throwable e) {
                h = -1;
            }
            Bukkit.getLogger().info("[构思技艺] 幻翼boss 实际碰撞箱：宽=" + jsNum(w) + " 高=" + jsNum(h)
                    + "（期望 " + jsNum(PH_BOX_W) + " × " + toFixed(phBoxH(PH_SCALE, PH_SIZE), 2)
                    + "；缩放值=" + toFixed(PH_SCALE, 3) + " 体型档=" + PH_SIZE + "）");
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 血条
    // ==================================================================

    /** 造一条血条（原 {@code phBarMake}）。★ 枚举退路在 Java 下不可达，见类注释。 */
    private static BossBar phBarMake(String title) {
        if (!PH_BAR) {
            return null;
        }
        try {
            BossBar bar;
            try {
                bar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SEGMENTED_20);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setVisible(true);
            } catch (Throwable e) {
                // 静默
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable e) {
                // 静默
            }
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 血条同步：进度 = 血量 / 上限（钳到 [0,1]），可见性 = 还活着，
     * 观众集合 = 同世界 + 距离 ≤ {@link #PH_BAR_RANGE}（{@link #PH_BAR_ONLY_OWNER} 时只有召唤者）。
     *
     * <p>★ {@code pl.distance(el)} <b>没有</b>单独包 try/catch（原脚本也没有）⇒
     * 一旦抛异常，整个观众收集循环就此中断，但已经收进 {@code want} 的人照样保留。照抄。
     */
    private void phBarSync(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            Entity ent = ref.ent;
            boolean alive = phAlive(ent);

            double prog = 0.0;
            if (alive) {
                try {
                    double hp = ((LivingEntity) ent).getHealth();
                    double mx = phMaxHp((LivingEntity) ent);
                    if (mx <= 0) {
                        mx = ref.maxHp;
                    }
                    prog = hp / (mx > 0 ? mx : 1);
                } catch (Throwable e) {
                    prog = 0.0;
                }
            }
            if (prog < 0) {
                prog = 0;
            }
            if (prog > 1) {
                prog = 1;
            }
            try {
                ref.bar.setProgress(prog);
            } catch (Throwable e) {
                // 静默
            }
            try {
                ref.bar.setVisible(alive);
            } catch (Throwable e) {
                // 静默
            }

            Map<String, Player> want = new LinkedHashMap<>();
            if (alive) {
                try {
                    String wname = String.valueOf(ent.getWorld().getName());
                    Location el = ent.getLocation();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        if (PH_BAR_ONLY_OWNER) {
                            try {
                                if (!String.valueOf(p.getUniqueId()).equals(String.valueOf(ref.owner))) {
                                    continue;
                                }
                            } catch (Throwable e) {
                                continue;
                            }
                        }
                        try {
                            if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                                continue;
                            }
                        } catch (Throwable e) {
                            continue;
                        }
                        Location pl;
                        try {
                            pl = p.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (pl.distance(el) > PH_BAR_RANGE) {
                            continue;
                        }
                        try {
                            want.put(String.valueOf(p.getUniqueId()), p);
                        } catch (Throwable e) {
                            // 静默
                        }
                    }
                } catch (Throwable e) {
                    // 静默
                }
            }

            for (Map.Entry<String, Player> en : want.entrySet()) {
                if (ref.barNear.get(en.getKey()) == null) {
                    try {
                        ref.bar.addPlayer(en.getValue());
                    } catch (Throwable e) {
                        // 静默
                    }
                    ref.barNear.put(en.getKey(), en.getValue());
                }
            }
            // ★ 先收集键再删，避免边遍历边改（JS 里是边遍历边 delete，等价）
            List<String> drop = new ArrayList<>();
            for (Map.Entry<String, Player> en : ref.barNear.entrySet()) {
                if (want.get(en.getKey()) == null) {
                    drop.add(en.getKey());
                }
            }
            for (String k : drop) {
                Player p = ref.barNear.get(k);
                try {
                    ref.bar.removePlayer(p);
                } catch (Throwable e) {
                    // 静默
                }
                ref.barNear.remove(k);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 收掉血条（原 {@code phBarDrop}）。 */
    private static void phBarDrop(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            try {
                ref.bar.setVisible(false);
            } catch (Throwable e) {
                // 静默
            }
            for (Map.Entry<String, Player> en : new ArrayList<>(ref.barNear.entrySet())) {
                try {
                    ref.bar.removePlayer(en.getValue());
                } catch (Throwable e) {
                    // 静默
                }
            }
            ref.barNear.clear();
            try {
                ref.bar.removeAll();
            } catch (Throwable e) {
                // 静默
            }
            ref.bar = null;
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 目标选择
    // ==================================================================

    /**
     * 选目标：同世界、在线、非旁观、距离 ≤ {@link #PH_SEEK} 的<b>最近非创造</b>玩家；
     * 没有的话（且 {@link #PH_CREATIVE_FALLBACK}）退回最近的创造玩家。
     */
    private LivingEntity phTarget(Ref ref) {
        try {
            Entity ent = ref.ent;
            if (!phAlive(ent)) {
                return null;
            }
            World world = ent.getWorld();
            String wname = String.valueOf(world.getName());
            Location el = ent.getLocation();

            Player best = null;
            double bestD = 1e9;      // 非创造 / 非旁观
            Player fall = null;
            double fallD = 1e9;      // 创造（兜底）

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!p.isOnline()) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                try {
                    if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                String gm = "";
                try {
                    gm = String.valueOf(p.getGameMode().name());
                } catch (Throwable e) {
                    gm = "";
                }
                if (gm.contains("SPECTATOR")) {
                    continue;
                }
                double d = 1e9;
                try {
                    d = p.getLocation().distance(el);
                } catch (Throwable e) {
                    d = 1e9;
                }
                if (d > PH_SEEK) {
                    continue;
                }
                if (gm.contains("CREATIVE")) {
                    if (d < fallD) {
                        fallD = d;
                        fall = p;
                    }
                } else {
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
            if (best != null) {
                return best;
            }
            if (PH_CREATIVE_FALLBACK) {
                return fall;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 生成
    // ==================================================================

    /**
     * 生成本体。
     *
     * <p>顺序<b>不能换</b>：① 先 {@code setSize}（会触发一次 refreshDimensions）
     * → ② 再写缩放属性 + 立刻刷新碰撞箱 → ③ 血量（多次写入 + 延迟重试）。
     * 普攻<b>不写属性</b> ⇒ 保持原版幻翼伤害（6 + 体型档 = 11）。
     */
    private LivingEntity phSpawnBoss(Player p) {
        try {
            World world = p.getWorld();
            Location base = p.getLocation();
            double yaw = 0;
            try {
                yaw = base.getYaw();
            } catch (Throwable e) {
                // 静默
            }
            double rad = yaw * Math.PI / 180.0;

            double lx = base.getX() + (-Math.sin(rad)) * PH_SPAWN_AHEAD;
            double lz = base.getZ() + (Math.cos(rad)) * PH_SPAWN_AHEAD;
            double ly = base.getY() + PH_SPAWN_UP;
            // ★ 别生成在天花板里（幻翼会卡在里面挨窒息伤害）⇒ 往上找一个通透的高度
            try {
                for (int up = 0; up <= 8; up++) {
                    if (phPassableAt(world, Math.floor(lx), Math.floor(ly + up), Math.floor(lz))) {
                        ly = ly + up;
                        break;
                    }
                }
            } catch (Throwable e) {
                // 静默
            }
            Location loc = new Location(world, lx, ly, lz, (float) yaw, 20.0f);

            EntityType t = phET("PHANTOM");
            if (t == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 幻翼boss：找不到实体类型 PHANTOM");
                } catch (Throwable e) {
                    // 静默
                }
                return null;
            }
            Entity e = world.spawnEntity(loc, t);
            if (e == null) {
                return null;
            }

            // ① 先定体型（setSize 会触发一次 refreshDimensions，必须在写缩放之前）
            try {
                if (e instanceof Phantom ph) {
                    ph.setSize(PH_SIZE);
                }
            } catch (Throwable err) {
                // 静默
            }
            // ② 再写缩放属性 + 立刻刷新碰撞箱
            try {
                if (e instanceof LivingEntity le) {
                    phApplyScale(le);
                }
            } catch (Throwable err) {
                // 静默
            }

            // ③ 血量：走 phApplyHp（多次写入 + 延迟重试）
            if (e instanceof LivingEntity le2) {
                phApplyHp(le2, PH_HP);
            }
            // ★ 普攻**不写属性** ⇒ 保持原版幻翼伤害（6 + 体型档）

            try {
                e.setCustomName(PH_NAME);
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setCustomNameVisible(true);
            } catch (Throwable err) {
                // 静默
            }
            if (e instanceof LivingEntity le3) {
                try {
                    le3.setRemoveWhenFarAway(false);
                } catch (Throwable err) {
                    // 静默
                }
            }
            try {
                e.setPersistent(true);
            } catch (Throwable err) {
                // 静默
            }
            if (e instanceof LivingEntity le4) {
                try {
                    le4.setCanPickupItems(false);
                } catch (Throwable err) {
                    // 静默
                }
            }
            if (e instanceof Mob mob) {
                try {
                    mob.setAware(true);
                } catch (Throwable err) {
                    // 静默
                }
            }
            if (e instanceof Phantom ph2) {
                try {
                    ph2.setShouldBurnInDay(false);
                } catch (Throwable err) {
                    // 静默
                }
            }
            try {
                e.setSilent(false);
            } catch (Throwable err) {
                // 静默
            }

            // 不穿装备。
            // ★★ 关于「不掉幻翼膜」：1.21.11 里 LootTables.EMPTY 这个常量已经不存在，
            //    Bukkit.getLootTable('minecraft:empty') 也取不到（本体 1358 张表里没有 empty）
            //    ⇒ 「把战利品表置空」这条路在 1.21.11 走不通（旧代码里那句是静默失败的）。
            //    幻翼膜来自战利品表 entities/phantom，唯一可靠的做法是在
            //    EntityDeathEvent 里 e.getDrops().clear()（见 BossListener#onDeath）。
            if (e instanceof LivingEntity le5) {
                try {
                    EntityEquipment eq = le5.getEquipment();
                    if (eq != null) {
                        try {
                            eq.clear();
                        } catch (Throwable err2) {
                            // 静默
                        }
                    }
                } catch (Throwable err) {
                    // 静默
                }
            }

            phSetTag(e, PH_V_BOSS);
            return (e instanceof LivingEntity le6) ? le6 : null;
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 幻翼boss：生成出错 " + err);
            } catch (Throwable e2) {
                // 静默
            }
            return null;
        }
    }

    /**
     * 召唤：每人同时只能有一只（已有活着的 ⇒ 静默忽略）。
     *
     * <p>★ 五个冷却的初值都写成 {@code now - CD + PH_FIRST_MS} ⇒ 出场保护期结束后，
     * 冷却时间<b>从召唤那一刻</b>开始算（不是从保护期结束才算）。
     */
    private boolean phSummon(Player p) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // 这条线还有活着的 ⇒ 静默忽略（每人同时只能有一只）
            Ref old = active.get(uuid);
            if (old != null && !old.done && phAlive(old.ent)) {
                return false;
            }

            LivingEntity ent = phSpawnBoss(p);
            if (ent == null) {
                return false;
            }

            long now = phNow();
            Location loc = ent.getLocation();
            Ref ref = new Ref();
            ref.owner = uuid;
            ref.ent = ent;
            ref.maxHp = PH_HP;
            ref.mode = "idle";
            ref.sonic = null;
            ref.dash = null;
            ref.cd1 = now - PH_SB_CD + PH_FIRST_MS;   // 出场保护期内不放技能
            ref.cd2 = now - PH_DF_CD + PH_FIRST_MS;
            ref.cd3 = now - PH_WS_CD + PH_FIRST_MS;
            ref.cd4 = now - PH_TR_CD + PH_FIRST_MS;
            ref.cd5 = now - PH_DA_CD + PH_FIRST_MS;
            ref.readyAt = now + PH_FIRST_MS;
            ref.hpFixUntil = now + PH_HP_FIX_MS;
            ref.hpOk = false;
            ref.hpScale = 1.0;
            ref.bar = null;
            ref.lastX = loc.getX();
            ref.lastZ = loc.getZ();
            ref.lastT = now;
            ref.unstickAt = 0;
            ref.lw = loc.getWorld();
            ref.lx = loc.getX();
            ref.ly = loc.getY();
            ref.lz = loc.getZ();
            ref.done = false;

            ref.bar = phBarMake(PH_NAME);
            try {
                phBarSync(ref);
            } catch (Throwable e) {
                // 静默
            }

            active.put(uuid, ref);

            try {
                phSound(loc.getWorld(), loc, "entity.phantom.ambient", 1.6, 0.6);
            } catch (Throwable e) {
                // 静默
            }
            try {
                phSound(loc.getWorld(), loc, "entity.wither.spawn", 0.5, 1.5);
            } catch (Throwable e) {
                // 静默
            }
            try {
                Particle pt = phP("SCULK_SOUL", "SOUL", "SMOKE_LARGE", "LARGE_SMOKE");
                phFx(loc.getWorld(), pt, loc.getX(), loc.getY(), loc.getZ(), 40, 1.2, 0.8, 1.2, 0.05);
            } catch (Throwable e) {
                // 静默
            }

            // 出生 2 秒后把实际碰撞箱打到后台（确认缩放生效）
            GS.runLater(() -> {
                try {
                    if (phAlive(ent)) {
                        phLogBox(ent);
                    }
                } catch (Throwable e) {
                    // 静默
                }
            }, 40L);

            GS.runLater(() -> phTick(ref), PH_TICK);
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 幻翼boss：召唤出错 " + e);
            } catch (Throwable e2) {
                // 静默
            }
            return false;
        }
    }

    // ==================================================================
    // AI 主循环
    // ==================================================================

    /**
     * 防卡位救援：原地不动超过 {@link #PH_UNSTICK_MS}（且目标在 6 格之外、
     * 距上次救援超过 {@link #PH_UNSTICK_GAP}）⇒ 直接挪到<b>目标头顶上方 6 格的空中</b>
     * （幻翼本来就在天上飞）。
     */
    private void phUnstick(Ref ref) {
        try {
            Location l = ref.ent.getLocation();
            double dx = l.getX() - ref.lastX;
            double dz = l.getZ() - ref.lastZ;
            double moved = Math.sqrt(dx * dx + dz * dz);
            if (moved > 0.75) {
                ref.lastX = l.getX();
                ref.lastZ = l.getZ();
                ref.lastT = phNow();
                return;
            }
            long now = phNow();
            if (now - ref.lastT < PH_UNSTICK_MS) {
                return;
            }
            if (now - ref.unstickAt < PH_UNSTICK_GAP) {
                ref.lastT = now;
                return;
            }

            LivingEntity tgt = phTarget(ref);
            if (tgt == null) {
                ref.lastT = now;
                return;
            }
            if (phDist(ref.ent, tgt) < 6.0) {
                ref.lastT = now;
                return;
            }

            // 救援：挪到目标头顶上方 6 格的空中
            Location tl = tgt.getLocation();
            Location dst = new Location(tl.getWorld(), tl.getX(), tl.getY() + 6.0, tl.getZ(),
                    l.getYaw(), 0.0f);
            try {
                ref.ent.teleport(dst);
            } catch (Throwable e) {
                // 静默
            }
            ref.unstickAt = now;
            ref.lastT = now;
            ref.lastX = dst.getX();
            ref.lastZ = dst.getZ();
            try {
                Bukkit.getLogger().info("[构思技艺] 幻翼boss 触发防卡位救援（原地不动超过 "
                        + (PH_UNSTICK_MS / 1000) + " 秒）");
            } catch (Throwable e) {
                // 静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /**
     * AI 心跳（每 {@link #PH_TICK} 刻一拍，自调度链）。
     *
     * <p>顺序：① 本体没了就收摊 → ② 记住位置 → ③ 血条 → ④ 血量/缩放保护窗口 →
     * ⑤ 缩放与白天不烧兜底 → ⑥ 防卡位 → ⑦ 演出中则本拍不放新技能 →
     * ⑧ 出场保护期 → ⑨ 选目标 + <b>同一拍只开一个技能</b>（五个技能按 ①②③④⑤ 顺序试）。
     */
    private void phTick(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }

            // ① 本体没了 ⇒ 收摊
            if (!phAlive(ref.ent)) {
                phCleanup(ref);
                return;
            }

            // 记住位置
            try {
                Location l0 = ref.ent.getLocation();
                ref.lw = l0.getWorld();
                ref.lx = l0.getX();
                ref.ly = l0.getY();
                ref.lz = l0.getZ();
            } catch (Throwable e) {
                // 静默
            }

            // ② 血条
            try {
                phBarSync(ref);
            } catch (Throwable e) {
                // 静默
            }

            // ③ 血量 / 缩放保护（出生后 PH_HP_FIX_MS 窗口内每拍重试）
            try {
                long fx = ref.hpFixUntil;
                if (!ref.hpOk && phNow() <= fx) {
                    double mx0 = phMaxHp(ref.ent);
                    double cur0 = -1;
                    try {
                        cur0 = ref.ent.getHealth();
                    } catch (Throwable e) {
                        cur0 = -1;
                    }
                    boolean maxBad = (mx0 < ref.maxHp - 0.5);
                    boolean hpBad = (cur0 >= 0 && cur0 < ref.maxHp * 0.5);
                    if (maxBad || hpBad) {
                        if (phApplyHpOnce(ref.ent, ref.maxHp)) {
                            ref.hpOk = true;
                            ref.hpScale = 1.0;
                        } else if (maxBad && mx0 > 0) {
                            ref.hpScale = mx0 / ref.maxHp;   // ★ 降级：按比例缩伤 ⇒ 等效血量不变
                        }
                    } else {
                        ref.hpOk = true;
                        ref.hpScale = 1.0;
                    }
                }
            } catch (Throwable e) {
                // 静默
            }

            // ④ 缩放 / 白天不烧 兜底（每拍确认一次，被别的插件改了也能拉回来）
            try {
                phApplyScale(ref.ent);
            } catch (Throwable e) {
                // 静默
            }
            try {
                if (ref.ent instanceof Phantom ph) {
                    ph.setShouldBurnInDay(false);
                }
            } catch (Throwable e) {
                // 静默
            }

            // ⑤ 防卡位
            try {
                phUnstick(ref);
            } catch (Throwable e) {
                // 静默
            }

            // ⑥ 演出中 ⇒ 本拍不放新技能
            if (ref.mode != null && !"idle".equals(ref.mode)) {
                GS.runLater(() -> phTick(ref), PH_TICK);
                return;
            }

            long now = phNow();
            if (now < ref.readyAt) {                       // 出场保护期
                GS.runLater(() -> phTick(ref), PH_TICK);
                return;
            }

            LivingEntity tgt = phTarget(ref);
            if (tgt != null) {
                double d = phDist(ref.ent, tgt);
                boolean started = false;      // ★ 同一拍只开一个技能

                // 技能 1：坚守者冲击波（13 秒）
                if (!started && d <= PH_SB_RANGE && (now - ref.cd1) >= PH_SB_CD) {
                    ref.cd1 = now;
                    started = true;
                    phSonicStart(ref, tgt);
                }
                // 技能 2：三枚龙息弹（7 秒）
                if (!started && d <= PH_SEEK && (now - ref.cd2) >= PH_DF_CD) {
                    ref.cd2 = now;
                    started = true;
                    phDragonBreath(ref, tgt);
                }
                // 技能 3：三枚凋零之首（11 秒）
                if (!started && d <= PH_SEEK && (now - ref.cd3) >= PH_WS_CD) {
                    ref.cd3 = now;
                    started = true;
                    phWitherSkulls(ref, tgt);
                }
                // 技能 4：投掷三叉戟 + 落点召唤闪电苦力怕（5 秒）
                if (!started && d <= PH_SEEK && (now - ref.cd4) >= PH_TR_CD) {
                    ref.cd4 = now;
                    started = true;
                    phTrident(ref, tgt);
                }
                // 技能 5：蓄力冲刺撞击（17 秒 · ★ 冷却从蓄力开始时算）
                if (!started && d <= PH_SEEK && (now - ref.cd5) >= PH_DA_CD) {
                    ref.cd5 = now;
                    started = true;
                    phDashStart(ref, tgt);
                }
            }

            GS.runLater(() -> phTick(ref), PH_TICK);
        } catch (Throwable e) {
            try {
                if (ref != null && phAlive(ref.ent)) {
                    GS.runLater(() -> phTick(ref), PH_TICK);
                } else {
                    phCleanup(ref);
                }
            } catch (Throwable e2) {
                // 静默
            }
        }
    }

    // ==================================================================
    // 技能 1：坚守者冲击波（10 点 · 冷却 13 秒 · 射线 30 格）
    // ==================================================================

    private void phSonicStart(Ref ref, LivingEntity tgt) {
        try {
            ref.mode = "sonic";
            Sonic st = new Sonic();
            st.step = 0;
            st.tgt = tgt;
            ref.sonic = st;
            try {
                Location l = ref.ent.getLocation();
                phSound(l.getWorld(), l, "entity.warden.sonic_charge", 2.4, 1.0);
            } catch (Throwable e) {
                // 静默
            }
            phSonicTick(ref);
        } catch (Throwable e) {
            ref.mode = "idle";
            ref.sonic = null;
        }
    }

    /** 蓄力一拍（每 2 刻一次，共 {@link #PH_SB_CHARGE} 拍 = 20 刻 = 1 秒）。 */
    private void phSonicTick(Ref ref) {
        try {
            Sonic st = ref.sonic;
            if (st == null) {
                ref.mode = "idle";
                return;
            }
            if (!phAlive(ref.ent) || st.tgt == null || !phAlive(st.tgt)) {
                ref.mode = "idle";
                ref.sonic = null;
                return;
            }

            // 面向目标（视觉）
            try {
                Location el = ref.ent.getLocation();
                Location tl = st.tgt.getLocation();
                double dx = tl.getX() - el.getX();
                double dz = tl.getZ() - el.getZ();
                double dy = (tl.getY() + 1.0) - (el.getY() + 1.0);
                double yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
                double pitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI;
                ref.ent.setRotation((float) yaw, (float) pitch);
            } catch (Throwable e) {
                // 静默
            }

            // 蓄力粒子：头前方由外向内收束
            try {
                World w = ref.ent.getWorld();
                Location l2 = ref.ent.getLocation();
                Particle pt = phP("SCULK_SOUL", "SOUL", "SMOKE_NORMAL", "SMOKE");
                phFx(w, pt, l2.getX(), l2.getY() + 1.0, l2.getZ(), 4, 0.6, 0.6, 0.6, 0.0);
                phFx(w, pt, l2.getX(), l2.getY(), l2.getZ(), 3, 0.5, 0.4, 0.5, 0.02);
                if (st.step + 1 == PH_SB_CHARGE) {
                    try {
                        phSound(w, l2, "entity.warden.sonic_charge", 2.4, 1.6);
                    } catch (Throwable e) {
                        // 静默
                    }
                }
            } catch (Throwable e) {
                // 静默
            }

            st.step++;
            if (st.step >= PH_SB_CHARGE) {
                ref.mode = "idle";
                ref.sonic = null;
                phSonicFire(ref, st.tgt);
                return;
            }
            GS.runLater(() -> phSonicTick(ref), 2L);
        } catch (Throwable e) {
            ref.mode = "idle";
            ref.sonic = null;
        }
    }

    /** 真正的 {@code DamageType.SONIC_BOOM} 伤害源（原版的护甲/附魔绕过关系一并继承）。 */
    private static DamageSource phSonicSource(Entity boss) {
        try {
            DamageSource.Builder b = DamageSource.builder(DamageType.SONIC_BOOM);
            try {
                b = b.withCausingEntity(boss);
            } catch (Throwable e) {
                // 静默
            }
            try {
                b = b.withDirectEntity(boss);
            } catch (Throwable e) {
                // 静默
            }
            return b.build();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 发射：射线被方块挡住就停在墙前（打不穿墙）；命中判定沿射线每格取一小球。 */
    private void phSonicFire(Ref ref, LivingEntity tgt) {
        try {
            Entity e = ref.ent;
            World world = e.getWorld();
            Location eye = ref.ent.getEyeLocation();
            Location te = tgt.getEyeLocation();

            Vector dir = te.toVector().subtract(eye.toVector());
            double d = dir.length();
            if (d < 0.2) {
                return;
            }
            dir.normalize();

            double maxD = d + 3.0;
            if (maxD > PH_SB_RANGE) {
                maxD = PH_SB_RANGE;
            }
            if (maxD < 4.0) {
                maxD = 4.0;
            }

            // 射线撞墙 ⇒ 停在墙前（打不穿墙）
            double len = maxD;
            try {
                RayTraceResult rr = world.rayTraceBlocks(eye, dir, maxD, FluidCollisionMode.NEVER, true);
                if (rr != null) {
                    Vector hp = rr.getHitPosition();
                    if (hp != null) {
                        Vector seg = hp.clone().subtract(eye.toVector());
                        len = seg.length();
                        if (len < 0.6) {
                            len = 0.6;
                        }
                    }
                }
            } catch (Throwable err) {
                // 静默
            }

            try {
                phSound(world, eye, "entity.warden.sonic_boom", 3.0, 1.0);
            } catch (Throwable err) {
                // 静默
            }

            // 冲击波粒子
            Particle pt = phP("SONIC_BOOM");
            if (pt != null) {
                for (double t = 0.6; t <= len; t += 1.0) {
                    try {
                        world.spawnParticle(pt, eye.getX() + dir.getX() * t,
                                eye.getY() + dir.getY() * t, eye.getZ() + dir.getZ() * t,
                                1, 0.0, 0.0, 0.0, 0.0);
                    } catch (Throwable err) {
                        // 静默
                    }
                }
            }

            // 命中判定：沿射线每格取一小球，撞到谁算谁
            DamageSource src = phSonicSource(e);
            Map<String, Integer> hit = new HashMap<>();
            for (double s = 0.6; s <= len + 0.01; s += 1.0) {
                Location loc = new Location(world, eye.getX() + dir.getX() * s,
                        eye.getY() + dir.getY() * s, eye.getZ() + dir.getZ() * s);
                Collection<Entity> col;
                try {
                    col = world.getNearbyEntities(loc, PH_SB_HIT_R, PH_SB_HIT_R, PH_SB_HIT_R);
                } catch (Throwable err) {
                    col = null;
                }
                if (col == null) {
                    continue;
                }
                for (Entity v : col) {
                    if (v == null || v == e) {
                        continue;
                    }
                    if (!phAlive(v)) {
                        continue;
                    }
                    if (phIsFriend(v)) {
                        continue;                   // ★ 不打自己人
                    }
                    String id;
                    try {
                        id = String.valueOf(v.getUniqueId());
                    } catch (Throwable err) {
                        id = String.valueOf(s);
                    }
                    if (hit.containsKey(id)) {
                        continue;
                    }
                    hit.put(id, 1);
                    phSonicHurt(v, e, src, eye);
                }
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 结算一发冲击波：清无敌帧 → 伤害（拿不到 SONIC_BOOM 伤害源就退回以 boss 为源）→ 击退。
     *
     * <p>★ 击退照原版：水平 {@link #PH_SB_KB}、垂直 {@link #PH_SB_KB_UP}；
     * 目标几乎正上/正下（水平距离 &lt; 1e-4）时改用 boss 的朝向，再退化就是 (1,0)。
     *
     * <p>★ 原脚本对「非生物」是「{@code v.damage} 方法找不到 ⇒ 静默失败」，
     * Java 里等价于不进 {@code instanceof LivingEntity} 分支；击退那一段<b>照样执行</b>。
     */
    private static void phSonicHurt(Entity v, Entity boss, DamageSource src, Location eye) {
        try {
            if (v instanceof LivingEntity le) {
                try {
                    le.setNoDamageTicks(0);
                } catch (Throwable e) {
                    // 静默
                }
                if (src != null) {
                    try {
                        le.damage(PH_SB_DMG, src);
                    } catch (Throwable e) {
                        try {
                            le.damage(PH_SB_DMG, boss);
                        } catch (Throwable e2) {
                            // 静默
                        }
                    }
                } else {
                    try {
                        le.damage(PH_SB_DMG, boss);
                    } catch (Throwable e) {
                        // 静默
                    }
                }
            }
            // 击退（照原版：水平 2.5、垂直 0.5）
            Location vl;
            try {
                vl = v.getLocation();
            } catch (Throwable e) {
                vl = null;
            }
            if (vl == null) {
                return;
            }
            double dx = vl.getX() - eye.getX();
            double dz = vl.getZ() - eye.getZ();
            double n = Math.sqrt(dx * dx + dz * dz);
            if (n < 1.0e-4) {
                Vector bd;
                try {
                    bd = boss.getLocation().getDirection();
                } catch (Throwable e) {
                    bd = null;
                }
                dx = (bd == null ? 1.0 : bd.getX());
                dz = (bd == null ? 0.0 : bd.getZ());
                n = Math.sqrt(dx * dx + dz * dz);
                if (n < 1.0e-4) {
                    dx = 1.0;
                    dz = 0.0;
                    n = 1.0;
                }
            }
            dx /= n;
            dz /= n;
            try {
                v.setVelocity(new Vector(dx * PH_SB_KB, PH_SB_KB_UP, dz * PH_SB_KB));
            } catch (Throwable e) {
                // 静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能 2：三枚龙息弹（1 瞄准 + 2 随机落点 · 冷却 7 秒）
    // ==================================================================
    // 落点：第 1 个 = 玩家当前位置（"锁定玩家"，**直线瞄准，不追踪**）
    //       其余 = 玩家周围 PH_DF_SPREAD 格内随机取点，自动贴地

    /** 取落点表（包一层：从实体上读位置/世界，算式在 {@link #phSpreadPointsCore}）。 */
    private static List<double[]> phSpreadPoints(LivingEntity tgt, int total, double spreadR) {
        try {
            Location pl = tgt.getLocation();
            World world = tgt.getWorld();
            return phSpreadPointsCore(pl.getX(), pl.getY(), pl.getZ(), world, total, spreadR);
        } catch (Throwable e) {
            return new ArrayList<>();
        }
    }

    /**
     * 从 boss 眼睛朝某个点发射一枚弹体（通用）。
     *
     * <p>★ 出生点 = 眼睛 + 单位方向 × 1.2；速度 = 单位方向 × {@code speed}。
     */
    private static Entity phLaunch(World world, LivingEntity boss, EntityType et,
                                   double[] pt, double speed) {
        try {
            Location eye = boss.getEyeLocation();
            double[] dir = phAim(eye.getX(), eye.getY(), eye.getZ(), pt[0], pt[1], pt[2]);
            if (dir == null) {
                return null;
            }
            double dx = dir[0];
            double dy = dir[1];
            double dz = dir[2];

            Location from = new Location(world, eye.getX() + dx * 1.2, eye.getY() + dy * 1.2,
                    eye.getZ() + dz * 1.2);
            Entity pr = world.spawnEntity(from, et);
            if (pr == null) {
                return null;
            }
            try {
                if (pr instanceof Projectile pj) {
                    pj.setShooter(boss);
                }
            } catch (Throwable e) {
                // 静默
            }
            // ★ 原脚本（1067 行）：`try { pr.setDirection(new Vec(dx, dy, dz)); } catch (e) { }`
            //
            //   `phLaunch` 是「通用弹体发射器」，`pr` 的**运行期**类型取决于入参 `et`，
            //   而 `setDirection` 只声明在 `Fireball` 上（`Fireball extends Projectile, Explosive`）：
            //     · et = DRAGON_FIREBALL ⇒ `DragonFireball extends Fireball` ⇒ **有** ⇒ 生效
            //     · et = WITHER_SKULL    ⇒ `WitherSkull   extends Fireball` ⇒ **有** ⇒ 生效
            //     · et = TRIDENT         ⇒ `Trident extends AbstractArrow, ThrowableProjectile`
            //                              ⇒ **没有** ⇒ 抛异常被 catch 吞掉
            //   （★ 注意：`javap` 看 `Entity` / `Projectile` 都**看不到** `setDirection`，
            //     但那是"接口只列自己声明的方法、不列继承来的"——
            //     `Fireball` 这一层才声明它。别再用 javap 父接口来判断子接口有没有。）
            //
            //   ⇒ Java 里 `instanceof Fireball` 就是原脚本那个 try/catch 的**静态化**：
            //     是 Fireball 族就调用（生效），否则跳过（等价于"抛了被吞"）。
            //     三分之二的调用点（龙息弹 / 凋零之首）是真的生效的，不能省。
            try {
                if (pr instanceof Fireball fb) {
                    fb.setDirection(new Vector(dx, dy, dz));
                }
            } catch (Throwable e) {
                // 静默
            }
            try {
                pr.setVelocity(new Vector(dx * speed, dy * speed, dz * speed));
            } catch (Throwable e) {
                // 静默
            }
            return pr;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 三枚龙息弹：1 枚瞄准 + 2 枚随机落点。原版龙息弹本身不破坏地形（只留龙息云）。 */
    private void phDragonBreath(Ref ref, LivingEntity tgt) {
        try {
            LivingEntity e = ref.ent;
            World world = e.getWorld();
            EntityType et = phET("DRAGON_FIREBALL");
            if (et == null) {
                return;
            }

            List<double[]> pts = phSpreadPoints(tgt, PH_DF_N, PH_DF_SPREAD);
            for (double[] p : pts) {
                Entity pr = phLaunch(world, e, et, p, PH_DF_SPEED);
                if (pr != null) {
                    // 原版龙息弹本身不破坏地形（只留龙息云），这里把爆炸威力也归零更保险
                    try {
                        if (pr instanceof Explosive ex) {
                            ex.setYield(0);
                        }
                    } catch (Throwable err) {
                        // 静默
                    }
                    try {
                        if (pr instanceof Explosive ex) {
                            ex.setIsIncendiary(false);
                        }
                    } catch (Throwable err) {
                        // 静默
                    }
                }
            }
            try {
                Location l = e.getLocation();
                phSound(world, l, "entity.ender_dragon.shoot", 2.0, 0.9);
            } catch (Throwable err) {
                // 静默
            }
            try {
                Particle pt = phP("DRAGON_BREATH", "SMOKE_LARGE", "LARGE_SMOKE");
                Location l2 = e.getLocation();
                phFx(world, pt, l2.getX(), l2.getY() + 0.6, l2.getZ(), 12, 0.6, 0.4, 0.6, 0.03);
            } catch (Throwable err) {
                // 静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能 3：三枚凋零之首（1 追击 + 2 随机落点 · 冷却 11 秒 · 不炸地形）
    // ==================================================================

    private void phWitherSkulls(Ref ref, LivingEntity tgt) {
        try {
            LivingEntity e = ref.ent;
            World world = e.getWorld();
            EntityType et = phET("WITHER_SKULL");
            if (et == null) {
                return;
            }

            List<double[]> pts = phSpreadPoints(tgt, PH_WS_N, PH_WS_SPREAD);
            for (int i = 0; i < pts.size(); i++) {
                Entity sk = phLaunch(world, e, et, pts.get(i), PH_WS_SPEED);
                if (sk == null) {
                    continue;
                }

                // ★ 黑色未蓄力头：命中实体只结算伤害，不触发"危险爆炸"
                try {
                    if (sk instanceof WitherSkull ws) {
                        ws.setCharged(false);
                    }
                } catch (Throwable err) {
                    // 静默
                }
                // 爆炸威力 / 点火：能设就设（真正的保险是 phOnExplode 清空方块列表）
                try {
                    if (sk instanceof Explosive ex) {
                        ex.setYield(0);
                    }
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    if (sk instanceof Explosive ex) {
                        ex.setIsIncendiary(false);
                    }
                } catch (Throwable err) {
                    // 静默
                }
                phSetTag(sk, PH_V_SKULL);

                // 第 1 枚 = 追击玩家的那一枚（后面的是随机落点，直飞）
                if (i == 0 && PH_WS_TURN > 0.0) {
                    try {
                        phSkullGuide(sk, tgt, 0);
                    } catch (Throwable err) {
                        // 静默
                    }
                } else {
                    try {
                        phSkullExpire(sk, 0);
                    } catch (Throwable err) {
                        // 静默
                    }
                }
            }
            try {
                phSound(world, e.getLocation(), "entity.wither.shoot", 1.6, 1.2);
            } catch (Throwable err) {
                // 静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★ 追击：每 {@link #PH_WS_GUIDE_T} 刻把飞行方向朝目标偏一点。
     *
     * <p>新方向 = 归一化( 旧方向 × (1 − 转向系数) + 指向目标 × 转向系数 )
     * ⇒ 慢慢偏，急转 / 绕柱子能甩掉，而不是"必中"的数值惩罚。
     *
     * <p>★ 目标没了（或一开始就没给）时，在 40 格内重新找一个最近的玩家；
     * 找不到就只做「超时自毁」的检查，不再修正方向。
     */
    private void phSkullGuide(Entity sk, LivingEntity tgt, int step) {
        try {
            if (sk == null) {
                return;
            }
            boolean alive;
            try {
                alive = !(sk.isDead() || !sk.isValid());
            } catch (Throwable e) {
                alive = false;
            }
            if (!alive) {
                return;
            }

            if (step >= PH_WS_LIFE) {
                try {
                    sk.remove();
                } catch (Throwable e) {
                    // 静默
                }
                return;   // 超时自毁（不爆炸）
            }

            LivingEntity t = null;
            try {
                if (tgt != null && phAlive(tgt)) {
                    t = tgt;
                }
            } catch (Throwable e) {
                t = null;
            }
            if (t == null) {
                // 目标没了就在附近找一个玩家
                Player best = null;
                double bestD = 1e9;
                try {
                    Location sl = sk.getLocation();
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        try {
                            if (!String.valueOf(p.getWorld().getName())
                                    .equals(String.valueOf(sl.getWorld().getName()))) {
                                continue;
                            }
                        } catch (Throwable e) {
                            continue;
                        }
                        double d = 1e9;
                        try {
                            d = p.getLocation().distance(sl);
                        } catch (Throwable e) {
                            d = 1e9;
                        }
                        if (d > 40.0) {
                            continue;
                        }
                        String gm = "";
                        try {
                            gm = String.valueOf(p.getGameMode().name());
                        } catch (Throwable e) {
                            gm = "";
                        }
                        if (gm.contains("SPECTATOR")) {
                            continue;
                        }
                        if (d < bestD) {
                            bestD = d;
                            best = p;
                        }
                    }
                } catch (Throwable e) {
                    // 静默
                }
                t = best;
            }

            if (t != null) {
                try {
                    Location sl2 = sk.getLocation();
                    Location tl = t.getLocation();
                    Vector cur = sk.getVelocity();
                    double[] nv = phSkullSteer(cur.getX(), cur.getY(), cur.getZ(),
                            sl2.getX(), sl2.getY(), sl2.getZ(),
                            tl.getX(), tl.getY(), tl.getZ());
                    if (nv != null) {
                        // ★ 原脚本（1194~1195 行）：
                        //     try { sk.setDirection(new Vec(nx, ny, nz)); } catch (e) { }
                        //     try { sk.setVelocity(new Vec(nx*PH_WS_SPEED, ...)); } catch (e) { }
                        //   `sk` **恒为 `WitherSkull`**（只从 phWitherSkulls 的
                        //   `phET(['WITHER_SKULL'])` 来，再传进本函数），
                        //   而 `WitherSkull extends Fireball` ⇒ `setDirection` **一定存在**、
                        //   这句**每次都生效**（不是死路径）。
                        //   Java 侧变量静态类型是 `Entity`，故用 `instanceof Fireball` 精确复现
                        //   —— 与原脚本的 try/catch 语义一致（不是 Fireball 就等价于"抛了被吞"）。
                        try {
                            if (sk instanceof Fireball fb) {
                                fb.setDirection(new Vector(nv[0], nv[1], nv[2]));
                            }
                        } catch (Throwable e) {
                            // 静默
                        }
                        try {
                            sk.setVelocity(new Vector(nv[0], nv[1], nv[2]));
                        } catch (Throwable e) {
                            // 静默
                        }
                    }
                } catch (Throwable e) {
                    // 静默
                }
            }
            final LivingEntity ft = t;
            GS.runLater(() -> phSkullGuide(sk, ft, step + PH_WS_GUIDE_T), PH_WS_GUIDE_T);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 随机落点的那种：不追踪，但也要超时自毁。 */
    private static void phSkullExpire(Entity sk, int step) {
        try {
            if (sk == null) {
                return;
            }
            boolean alive;
            try {
                alive = !(sk.isDead() || !sk.isValid());
            } catch (Throwable e) {
                alive = false;
            }
            if (!alive) {
                return;
            }
            if (step >= PH_WS_LIFE) {
                try {
                    sk.remove();
                } catch (Throwable e) {
                    // 静默
                }
                return;
            }
            GS.runLater(() -> phSkullExpire(sk, step + PH_WS_GUIDE_T), PH_WS_GUIDE_T);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能 4：投掷三叉戟（原版溺尸投掷伤害 8）→ 落点召唤闪电苦力怕
    // ==================================================================

    private void phTrident(Ref ref, LivingEntity tgt) {
        try {
            LivingEntity e = ref.ent;
            World world = e.getWorld();
            EntityType et = phET("TRIDENT");
            if (et == null) {
                return;
            }

            Location tl = tgt.getLocation();
            // ★ 弹道补偿：箭类弹体有重力 ⇒ 按飞行时间把瞄准点抬高 ½·g·t²
            double dxh = tl.getX() - e.getLocation().getX();
            double dzh = tl.getZ() - e.getLocation().getZ();
            double dh = Math.sqrt(dxh * dxh + dzh * dzh);
            double drop = phTridentDrop(dh);

            double[] pt = {tl.getX(), tl.getY() + 1.0 + drop, tl.getZ()};
            Entity tr = phLaunch(world, e, et, pt, PH_TR_SPEED);
            if (tr == null) {
                return;
            }

            phSetTag(tr, PH_V_TRIDENT);
            // ★ 原版溺尸投掷伤害 = 8
            try {
                if (tr instanceof AbstractArrow arrow) {
                    arrow.setDamage(PH_TR_DMG);
                }
            } catch (Throwable err) {
                // 静默
            }
            // 玩家捡不走 boss 的武器
            try {
                if (tr instanceof AbstractArrow arrow) {
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
                }
            } catch (Throwable err) {
                // 静默
            }

            try {
                phSound(world, e.getLocation(), "item.trident.throw", 1.4, 1.0);
            } catch (Throwable err) {
                // 静默
            }
            try {
                Location l2 = e.getLocation();
                Particle pt2 = phP("CRIT", "SMOKE_NORMAL", "SMOKE");
                phFx(world, pt2, l2.getX(), l2.getY() + 0.5, l2.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
            } catch (Throwable err) {
                // 静默
            }

            // 追踪落地（速度归零 或 超时）
            phTridentTrack(ref, tr, 0);
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 追踪三叉戟落地：飞出去 3 刻以后速度掉到近 0 ⇒ 判定「扎进地面/停在某处」；
     * 或者超过 {@link #PH_TR_LIFE} 刻 ⇒ 按当前位置当落点兜底。
     */
    private void phTridentTrack(Ref ref, Entity tr, int step) {
        try {
            if (tr == null) {
                return;
            }
            boolean gone;
            try {
                gone = (tr.isDead() || !tr.isValid());
            } catch (Throwable e) {
                gone = true;
            }
            if (gone) {
                return;
            }

            if (step > PH_TR_LIFE) {
                phTridentLand(ref, tr);
                return;
            }

            double sp = 9.0;
            try {
                Vector v = tr.getVelocity();
                sp = Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
            } catch (Throwable e) {
                sp = 9.0;
            }

            if (step >= 3 && sp < 0.06) {
                phTridentLand(ref, tr);
                return;
            }

            GS.runLater(() -> phTridentTrack(ref, tr, step + 1), 1L);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 落地：收掉三叉戟 → 雷声 + 电火花 → <b>落点召唤一只原版闪电苦力怕</b>。 */
    private void phTridentLand(Ref ref, Entity tr) {
        try {
            Location loc;
            try {
                loc = tr.getLocation();
            } catch (Throwable e) {
                loc = null;
            }
            try {
                tr.remove();
            } catch (Throwable e) {
                // 静默
            }
            if (loc == null) {
                return;
            }

            World world = loc.getWorld();
            try {
                phSound(world, loc, "item.trident.thunder", 1.8, 1.2);
            } catch (Throwable e) {
                // 静默
            }
            try {
                Particle pt = phP("ELECTRIC_SPARK", "EXPLOSION_NORMAL", "SMOKE_NORMAL");
                phFx(world, pt, loc.getX(), loc.getY() + 0.4, loc.getZ(), 24, 0.5, 0.4, 0.5, 0.06);
            } catch (Throwable e) {
                // 静默
            }

            phPetSpawn(world, loc);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 技能 5：蓄力冲刺撞击（55 点 · 冷却 17 秒 · 蓄力 1.5 秒）
    // ==================================================================
    // ★ 完整流程：
    //   ① 蓄力 1.5 秒（PH_DA_CHARGE = 30 刻）：面向目标 + 粒子向身体收束，
    //      期间放 3 次烟花音效（每 0.5 秒一次，音高递增：0.9 / 1.1 / 1.3）
    //   ② 冲刺：朝「蓄力结束那一刻玩家所在的位置」直线冲过去 ——
    //      每刻移动 PH_DA_SPEED(2.2) 格 = 44 格/秒（玩家跑速只有 5.6 格/秒），
    //      所以只要玩家在蓄力的 1.5 秒里没离开那条线，基本躲不掉。
    //   ③ 命中 ⇒ 55 点伤害（伤害源走 DamageType.MACE_SMASH）
    //        + 重锤猛击音效 + 击退；未命中 / 撞墙 ⇒ 什么都不发生。

    /** 让实体面向目标（视觉）。★ 用的是双方的<b>脚下</b>位置，dy 那两项都 +1.0 后相减。 */
    private static void phFaceTo(Entity ent, LivingEntity tgt) {
        try {
            Location el = ent.getLocation();
            Location tl = tgt.getLocation();
            double dx = tl.getX() - el.getX();
            double dz = tl.getZ() - el.getZ();
            double dy = (tl.getY() + 1.0) - (el.getY() + 1.0);
            double yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
            double pitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI;
            ent.setRotation((float) yaw, (float) pitch);
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    private void phDashStart(Ref ref, LivingEntity tgt) {
        try {
            ref.mode = "dash";
            Dash st = new Dash();
            st.phase = "charge";
            st.step = 0;
            st.tgt = tgt;
            ref.dash = st;
            try {
                Location l = ref.ent.getLocation();
                phSound(l.getWorld(), l, "entity.phantom.ambient", 2.0, 0.5);
            } catch (Throwable e) {
                // 静默
            }
            phDashChargeTick(ref);
        } catch (Throwable e) {
            ref.dash = null;
            ref.mode = "idle";
        }
    }

    /** 蓄力一拍（每 2 刻一次，共 {@link #PH_DA_CHARGE} 拍 = 30 刻 = 1.5 秒）。 */
    private void phDashChargeTick(Ref ref) {
        try {
            Dash st = ref.dash;
            if (st == null) {
                ref.mode = "idle";
                return;
            }
            if (!phAlive(ref.ent)) {
                ref.dash = null;
                ref.mode = "idle";
                return;
            }
            if (st.tgt == null || !phAlive(st.tgt)) {
                ref.dash = null;
                ref.mode = "idle";
                return;
            }

            phFaceTo(ref.ent, st.tgt);

            // 蓄力粒子：向身体收束
            try {
                World w = ref.ent.getWorld();
                Location l2 = ref.ent.getLocation();
                Particle pt = phP("FIREWORK", "FIREWORK_SPARK", "FLAME");
                phFx(w, pt, l2.getX(), l2.getY() + 1.2, l2.getZ(), 6, 0.9, 0.7, 0.9, 0.01);
                phFx(w, pt, l2.getX(), l2.getY(), l2.getZ(), 4, 0.6, 0.5, 0.6, 0.03);
            } catch (Throwable e) {
                // 静默
            }

            // ★ 烟花音效：把 1.5 秒均分成 3 段，每段开头响一次（音高递增）
            double pitch = phFwPitchAt(st.step);
            if (pitch >= 0) {
                try {
                    World w2 = ref.ent.getWorld();
                    Location l3 = ref.ent.getLocation();
                    phSound(w2, l3, "entity.firework_rocket.launch", 2.2, pitch);
                } catch (Throwable e) {
                    // 静默
                }
            }

            st.step++;
            if (st.step >= PH_DA_CHARGE) {
                phDashLaunch(ref);
                return;
            }
            GS.runLater(() -> phDashChargeTick(ref), 2L);
        } catch (Throwable e) {
            ref.dash = null;
            ref.mode = "idle";
        }
    }

    /** 锁定「蓄力结束那一刻玩家所在的位置」，算出冲刺方向与剩余距离。 */
    private void phDashLaunch(Ref ref) {
        try {
            Dash st = ref.dash;
            if (st == null) {
                ref.mode = "idle";
                return;
            }
            LivingEntity e = ref.ent;
            LivingEntity tgt = st.tgt;
            if (!phAlive(e) || tgt == null || !phAlive(tgt)) {
                ref.dash = null;
                ref.mode = "idle";
                return;
            }

            // ★ 锁定「蓄力结束那一刻玩家所在的位置」
            Location loc;
            try {
                loc = tgt.getLocation();
            } catch (Throwable err) {
                loc = null;
            }
            if (loc == null) {
                ref.dash = null;
                ref.mode = "idle";
                return;
            }

            Location el = e.getLocation();
            double dx = loc.getX() - el.getX();
            double dy = (loc.getY() + 1.0) - (el.getY() + 1.0);
            double dz = loc.getZ() - el.getZ();
            double L = Math.sqrt(dx * dx + dy * dy + dz * dz);

            // 已经贴脸了 ⇒ 直接结算
            if (L < 1.0) {
                phDashHit(ref, tgt);
                return;
            }

            st.phase = "fly";
            st.dir = new Vector(dx / L, dy / L, dz / L);
            st.left = Math.min(L, PH_DA_MAX);
            st.hit = false;

            try {
                phSound(e.getWorld(), el, "entity.phantom.swoop", 2.0, 0.8);
            } catch (Throwable err) {
                // 静默
            }

            phDashTick(ref);
        } catch (Throwable err) {
            ref.dash = null;
            ref.mode = "idle";
        }
    }

    /** 冲刺一拍：每刻前进 {@link #PH_DA_SPEED} 格（或剩余距离），撞墙/走完就停。 */
    private void phDashTick(Ref ref) {
        try {
            Dash st = ref.dash;
            if (st == null) {
                ref.mode = "idle";
                return;
            }
            LivingEntity e = ref.ent;
            if (!phAlive(e)) {
                ref.dash = null;
                ref.mode = "idle";
                return;
            }

            double step = Math.min(PH_DA_SPEED, st.left);
            Location loc = e.getLocation();
            double nx = loc.getX() + st.dir.getX() * step;
            double ny = loc.getY() + st.dir.getY() * step;
            double nz = loc.getZ() + st.dir.getZ() * step;

            // 撞墙就停（未命中 ⇒ 什么都不会发生）
            boolean blocked = false;
            try {
                if (!phPassableAt(loc.getWorld(), Math.floor(nx), Math.floor(ny + 1.0),
                        Math.floor(nz))) {
                    blocked = true;
                }
            } catch (Throwable err) {
                blocked = false;
            }

            Location nl = new Location(loc.getWorld(), nx, ny, nz, loc.getYaw(), loc.getPitch());
            try {
                e.teleport(nl);
            } catch (Throwable err) {
                // 静默
            }

            // 冲刺拖尾
            try {
                Particle pt = phP("FIREWORK", "FIREWORK_SPARK", "END_ROD");
                phFx(loc.getWorld(), pt, nx, ny + 1.0, nz, 10, 0.6, 0.6, 0.6, 0.02);
            } catch (Throwable err) {
                // 静默
            }

            st.left -= step;

            // ★ 命中判定：只判定锁定的那个玩家
            LivingEntity tp = st.tgt;
            if (tp != null && phAlive(tp)) {
                Location tl;
                try {
                    tl = tp.getLocation();
                } catch (Throwable err) {
                    tl = null;
                }
                if (tl != null) {
                    if (phDashHitTest(tl.getX(), tl.getY(), tl.getZ(), nx, ny, nz)) {
                        phDashHit(ref, tp);
                        return;
                    }
                }
            }

            if (blocked || st.left <= 0.01) {
                ref.dash = null;
                ref.mode = "idle";
                try {
                    phSound(loc.getWorld(), nl, "entity.phantom.flap", 1.4, 0.8);
                } catch (Throwable err) {
                    // 静默
                }
                return;
            }
            GS.runLater(() -> phDashTick(ref), 1L);
        } catch (Throwable err) {
            ref.dash = null;
            ref.mode = "idle";
        }
    }

    /** 命中：55 点伤害（{@code MACE_SMASH} 伤害源）+ 重锤猛击音效 + 朝冲刺方向甩出去。 */
    private void phDashHit(Ref ref, LivingEntity p) {
        try {
            ref.dash = null;
            ref.mode = "idle";

            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                loc = null;
            }
            if (loc == null) {
                return;
            }

            // ① 55 点伤害（伤害源走重锤猛击；拿不到就退回以 boss 为源）
            LivingEntity e = ref.ent;
            try {
                p.setNoDamageTicks(0);
            } catch (Throwable err) {
                // 静默
            }
            DamageSource src = phMaceSource(e);
            try {
                if (src != null) {
                    p.damage(PH_DA_DMG, src);
                } else {
                    p.damage(PH_DA_DMG, e);
                }
            } catch (Throwable err) {
                // 静默
            }

            // ② 重锤的猛击音效 + 撞击粒子
            try {
                World w = loc.getWorld();
                phSound(w, loc, "item.mace.smash_ground_heavy", 2.8, 1.0);
                phSound(w, loc, "item.mace.smash_air", 2.2, 0.9);
                Particle pt = phP("EXPLOSION_LARGE", "EXPLOSION", "CRIT");
                phFx(w, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(), 8, 0.9, 0.6, 0.9, 0.06);
            } catch (Throwable err) {
                // 静默
            }

            // ③ 击退：朝冲刺方向甩出去
            try {
                Vector d = e.getLocation().getDirection();
                p.setVelocity(new Vector(d.getX() * 1.8, 0.7, d.getZ() * 1.8));
            } catch (Throwable err) {
                // 静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** 重锤猛击伤害源（{@code DamageType.MACE_SMASH}）；拿不到类型就返回 null。 */
    private static DamageSource phMaceSource(Entity boss) {
        try {
            DamageType dt;
            try {
                dt = DamageType.MACE_SMASH;
            } catch (Throwable e) {
                dt = null;
            }
            if (dt == null) {
                return null;
            }
            DamageSource.Builder b = DamageSource.builder(dt);
            try {
                b = b.withCausingEntity(boss);
            } catch (Throwable e) {
                // 静默
            }
            try {
                b = b.withDirectEntity(boss);
            } catch (Throwable e) {
                // 静默
            }
            return b.build();
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 闪电苦力怕（原版 · 不被 boss 的技能伤害 · 有数量与存活上限）
    // ==================================================================

    /**
     * 清扫：先摘掉「已经死了 / 已超 {@link #PH_PET_LIFE}」的记录，
     * 再把超上限的部分按<b>出生时间从早到晚</b>收掉。
     *
     * <p>★ 排序稳定性照抄 JS {@code Array.prototype.sort}（规范要求稳定）
     * ⇒ 同一毫秒出生的几只，收掉的顺序与插入顺序一致。这里用 {@code LinkedHashMap}
     * + 索引排序（{@code Arrays.sort(Object[])} 是 TimSort，稳定）对齐。
     */
    private void phPetSweep() {
        try {
            long now = phNow();
            List<String> keys = new ArrayList<>();
            List<Long> ats = new ArrayList<>();
            Iterator<Map.Entry<String, Pet>> it = pets.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Pet> en = it.next();
                Pet rec = en.getValue();
                if (rec == null) {
                    it.remove();
                    continue;
                }
                boolean alive;
                try {
                    alive = !(rec.e.isDead() || !rec.e.isValid());
                } catch (Throwable e) {
                    alive = false;
                }
                if (!alive) {
                    it.remove();
                    continue;
                }
                if (now - rec.at >= PH_PET_LIFE) {
                    try {
                        rec.e.remove();
                    } catch (Throwable e) {
                        // 静默
                    }
                    it.remove();
                    continue;
                }
                keys.add(en.getKey());
                ats.add(rec.at);
            }
            // 超上限：把最早的几只收掉
            if (keys.size() > PH_PET_MAX) {
                int n = keys.size();
                Integer[] ord = new Integer[n];
                for (int i = 0; i < n; i++) {
                    ord[i] = i;
                }
                Arrays.sort(ord, (a, b) -> Long.compare(ats.get(a), ats.get(b)));
                int over = n - PH_PET_MAX;
                for (int i = 0; i < over; i++) {
                    String k = keys.get(ord[i]);
                    Pet rec2 = pets.get(k);
                    if (rec2 != null) {
                        try {
                            rec2.e.remove();
                        } catch (Throwable e) {
                            // 静默
                        }
                    }
                    pets.remove(k);
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    /** 每 5 秒一次的苦力怕清扫链（自调度，与原脚本一致）。 */
    private void phPetLoop() {
        try {
            phPetSweep();
        } catch (Throwable e) {
            // 静默
        }
        try {
            GS.runLater(this::phPetLoop, 100L);
        } catch (Throwable e) {
            // 静默
        }
    }

    /** 落点召唤一只<b>原版闪电苦力怕</b>（{@code setPowered(true)}），并登记进 {@link #pets}。 */
    private Creeper phPetSpawn(World world, Location loc) {
        try {
            if (world == null || loc == null) {
                return null;
            }
            EntityType et = phET("CREEPER");
            if (et == null) {
                return null;
            }
            Entity c = world.spawnEntity(loc, et);
            if (c == null) {
                return null;
            }

            // ★ 原版闪电苦力怕（被雷劈过的苦力怕 = powered）
            //   （掉落与经验不在这里管：靠死亡事件里 getDrops().clear() / setDroppedExp(0)）
            try {
                if (c instanceof Creeper cr) {
                    cr.setPowered(true);
                }
            } catch (Throwable e) {
                // 静默
            }
            if (c instanceof LivingEntity le) {
                try {
                    le.setRemoveWhenFarAway(false);
                } catch (Throwable e) {
                    // 静默
                }
            }
            try {
                c.setPersistent(true);
            } catch (Throwable e) {
                // 静默
            }
            phSetTag(c, PH_V_PET);

            pets.put(String.valueOf(c.getUniqueId()), new Pet(c, phNow()));
            // 出生特效（闪电味道）
            try {
                Particle pt = phP("ELECTRIC_SPARK", "EXPLOSION_NORMAL");
                phFx(world, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(), 30, 0.4, 0.8, 0.4, 0.08);
            } catch (Throwable e) {
                // 静默
            }
            try {
                phSound(world, loc, "entity.lightning_bolt.thunder", 0.6, 1.6);
            } catch (Throwable e) {
                // 静默
            }
            try {
                phSound(world, loc, "entity.creeper.primed", 1.2, 1.4);
            } catch (Throwable e) {
                // 静默
            }

            phPetSweep();
            return (c instanceof Creeper cr2) ? cr2 : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 收摊
    // ==================================================================

    /** 收摊：标记 done、断掉演出、收血条、从 {@link #active} 里摘掉。 */
    private void phCleanup(Ref ref) {
        try {
            if (ref == null) {
                return;
            }
            if (ref.done) {
                return;
            }
            ref.done = true;
            if ("sonic".equals(ref.mode)) {
                ref.sonic = null;
            }
            if ("dash".equals(ref.mode)) {
                ref.dash = null;
            }
            ref.mode = "idle";
            try {
                phBarDrop(ref);
            } catch (Throwable e) {
                // 静默
            }
            List<String> drop = new ArrayList<>();
            for (Map.Entry<String, Ref> en : active.entrySet()) {
                if (en.getValue() == ref) {
                    drop.add(en.getKey());
                }
            }
            for (String k : drop) {
                active.remove(k);
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // ★ 掉落物（2026-10-09 第十三批）：清空原版掉落后，固定掉 11 件（各 1 个）
    // ==================================================================
    //   重锤 · 三叉戟 · 龙蛋 · 下界之星 · 腐肉 · 火药 · 幽匿催发体 · 幻翼膜 ·
    //   本 boss 召唤物（GS_PHANTOM）· 烟花火箭（飞行时间 = 1）· 鞘翅
    //   ★ 原版掉落物与经验在死亡事件里已经清空；这里只负责「加东西」。

    private static ItemStack phVanillaStack(String name, int amount) {
        try {
            Material m;
            try {
                m = Material.valueOf(name);
            } catch (Throwable e) {
                m = null;
            }
            if (m == null) {
                return null;
            }
            String nm = "";
            try {
                nm = String.valueOf(m.name());
            } catch (Throwable e2) {
                nm = "";
            }
            if ("AIR".equals(nm) || nm.isEmpty()) {
                return null;
            }
            return new ItemStack(m, (amount <= 0) ? 1 : amount);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 烟花火箭（power = 飞行时间；1 = 「1 秒的那种」）。 */
    private static ItemStack phFireworkStack(int power) {
        try {
            Material m;
            try {
                m = Material.valueOf("FIREWORK_ROCKET");
            } catch (Throwable e) {
                m = null;
            }
            if (m == null) {
                try {
                    m = Material.valueOf("FIREWORK");
                } catch (Throwable e2) {
                    m = null;
                }
            }
            if (m == null) {
                return null;
            }
            ItemStack st = new ItemStack(m, 1);
            try {
                ItemMeta meta = st.getItemMeta();
                if (meta != null) {
                    try {
                        if (meta instanceof FireworkMeta fm) {
                            fm.setPower(power);
                        }
                    } catch (Throwable e3) {
                        // 静默
                    }
                    st.setItemMeta(meta);
                }
            } catch (Throwable e4) {
                // 静默
            }
            return st;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 死亡掉落：9 件原版物品 + 1 个烟花火箭 + 1 个本 boss 召唤物 = 11 件。 */
    private static void phDropLoot(Entity ent) {
        try {
            if (ent == null) {
                return;
            }
            World world;
            double x = 0;
            double y = 0;
            double z = 0;
            try {
                Location l = ent.getLocation();
                world = l.getWorld();
                x = l.getX();
                y = l.getY();
                z = l.getZ();
            } catch (Throwable e) {
                world = null;
            }
            if (world == null) {
                return;
            }

            Location loc = new Location(world, x, y + 0.5, z);

            // ① 原版物品（各 1 个）
            String[] names = {"MACE", "TRIDENT", "DRAGON_EGG", "NETHER_STAR",
                    "ROTTEN_FLESH", "GUNPOWDER", "SCULK_CATALYST",
                    "PHANTOM_MEMBRANE", "ELYTRA"};
            for (String name : names) {
                ItemStack s1 = phVanillaStack(name, 1);
                if (s1 != null) {
                    try {
                        world.dropItemNaturally(loc, s1);
                    } catch (Throwable e) {
                        // 静默
                    }
                }
            }

            // ② 烟花火箭（飞行时间 1）
            ItemStack fw = phFireworkStack(1);
            if (fw != null) {
                try {
                    world.dropItemNaturally(loc, fw);
                } catch (Throwable e) {
                    // 静默
                }
            }

            // ③ 本 boss 召唤物（粘液物品：取模板再 clone）
            try {
                SlimefunItem sf = GS.sfItem(PH_ITEM_ID);
                if (sf != null) {
                    ItemStack s2 = sf.getItem().clone();
                    try {
                        s2.setAmount(1);
                    } catch (Throwable e) {
                        // 静默
                    }
                    world.dropItemNaturally(loc, s2);
                }
            } catch (Throwable e) {
                // 静默
            }

            try {
                phSound(world, loc, "entity.player.levelup", 0.8, 1.2);
            } catch (Throwable e) {
                // 静默
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器
    // ==================================================================

    /**
     * 注册伤害 / 血量降级 / 死亡 / 爆炸四条监听器（已注册则直接返回 true）。
     *
     * <p>★ 原脚本在 {@code onUse} 里<b>惰性</b>注册，注册成功才打那行
     * 「伤害 / 死亡 / 爆炸拦截 已启用（N 条）」—— 参考服日志里没有这行，
     * 因为那次会话没召唤过 boss。本移植保持同一时机。
     *
     * <p>★ 去重：原脚本用脚本级 {@code PH_REG_DMG} 闩锁，reload 会重复注册（泄漏）；
     * 这里用静态闩锁 + {@link #CURRENT} 转发（效果等价、不累积监听器）。
     */
    private static synchronized boolean phRegisterEvents() {
        if (EVENTS_REGISTERED) {
            return true;
        }
        try {
            Plugin plugin = GS.pluginOrNull();
            if (plugin == null) {
                return false;
            }
            int ok = 0;
            try {
                // 一个 Listener 上挂 4 个 @EventHandler ⇒ 等价于原脚本的 4 次 registerEvent
                Bukkit.getPluginManager().registerEvents(new BossListener(), plugin);
                ok = 4;
            } catch (Throwable e1) {
                ok = 0;
            }
            if (ok > 0) {
                EVENTS_REGISTERED = true;
                try {
                    Bukkit.getLogger().info("[构思技艺] 幻翼boss：伤害 / 死亡 / 爆炸拦截 已启用（"
                            + ok + " 条）");
                } catch (Throwable e) {
                    // 静默
                }
            }
            return ok > 0;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 四条监听器，全部转发到 {@link #CURRENT}（见 {@link #phRegisterEvents} 的说明）。 */
    private static final class BossListener implements Listener {

        /** ① 伤害 / 阵营（HIGHEST）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamageByEntity(EntityDamageByEntityEvent e) {
            PhantomModule m = CURRENT;
            if (m != null) {
                m.phOnDamageByEntity(e);
            }
        }

        /** ② 血量降级兜底（HIGH）：{@code MAX_HEALTH} 写不进去时按 hpScale 等比缩伤。 */
        @EventHandler(priority = EventPriority.HIGH)
        public void onDamageBase(EntityDamageEvent e) {
            PhantomModule m = CURRENT;
            if (m != null) {
                m.phOnDamageBase(e);
            }
        }

        /** ③ 死亡：清掉落 / 清经验 / 收血条（HIGHEST）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent e) {
            PhantomModule m = CURRENT;
            if (m != null) {
                m.phOnDeath(e);
            }
        }

        /** ④ 爆炸：自家的凋零之首 / 闪电苦力怕 ⇒ 清空待破坏方块（HIGHEST）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onExplode(EntityExplodeEvent e) {
            PhantomModule m = CURRENT;
            if (m != null) {
                m.phOnExplode(e);
            }
        }
    }

    /** 按实体反查 boss 状态（原 {@code phRefOf}）。 */
    private Ref phRefOf(Entity ent) {
        try {
            if (ent == null) {
                return null;
            }
            String id = String.valueOf(ent.getUniqueId());
            for (Ref r : active.values()) {
                if (r == null) {
                    continue;
                }
                try {
                    if (r.ent != null && String.valueOf(r.ent.getUniqueId()).equals(id)) {
                        return r;
                    }
                } catch (Throwable e) {
                    // 静默
                }
            }
        } catch (Throwable e) {
            // 静默
        }
        return null;
    }

    /**
     * 弹体的「归属者」：箭 / 头 / 三叉戟看 {@code shooter}，龙息云看 {@code source}。
     *
     * <p>★ 原脚本用 {@code typeof s.getUniqueId === 'function'} 判「是不是实体」——
     * Java 里就是 {@code instanceof Entity}（{@code BlockProjectileSource} 不是实体）。
     */
    private static Entity phShooterOf(Entity ent) {
        try {
            if (ent == null) {
                return null;
            }
            try {
                if (ent instanceof Projectile pj) {
                    ProjectileSource s = pj.getShooter();
                    if (s instanceof Entity se) {
                        return se;
                    }
                }
            } catch (Throwable e) {
                // 静默
            }
            try {
                if (ent instanceof AreaEffectCloud aec) {
                    ProjectileSource s2 = aec.getSource();
                    if (s2 instanceof Entity se2) {
                        return se2;
                    }
                }
            } catch (Throwable e) {
                // 静默
            }
        } catch (Throwable e) {
            // 静默
        }
        return null;
    }

    /**
     * 阵营免伤：<b>只有「挨打的这一方是自己人」才管</b>
     * —— 玩家 / 别的生物挨打一律交给原版，一概不插手。
     */
    private void phOnDamageByEntity(EntityDamageByEntityEvent e) {
        try {
            Entity src = e.getDamager();
            Entity vic = e.getEntity();
            if (src == null || vic == null) {
                return;
            }

            if (!phIsFriend(vic)) {
                return;
            }

            // ① 自己人打自己人 ⇒ 免伤（boss / 闪电苦力怕 互相之间）
            if (phIsFriend(src)) {
                try {
                    e.setCancelled(true);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    e.setDamage(0.0);
                } catch (Throwable err) {
                    // 静默
                }
                return;
            }

            // ② 自家的弹体（凋零之首 / 三叉戟 / 龙息云）打到自己人 ⇒ 免伤
            Entity own = phShooterOf(src);
            if (own != null && phIsFriend(own)) {
                try {
                    e.setCancelled(true);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    e.setDamage(0.0);
                } catch (Throwable err) {
                    // 静默
                }
                return;
            }

            // ③ 弹体自己带标记的（万一 shooter 取不到）
            String t = phTagOf(src);
            if (PH_V_SKULL.equals(t) || PH_V_TRIDENT.equals(t)) {
                try {
                    e.setCancelled(true);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    e.setDamage(0.0);
                } catch (Throwable err) {
                    // 静默
                }
                return;
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /** 血量降级兜底：{@code MAX_HEALTH} 写不进去时，按 {@code hpScale} 等比缩伤（等效血量不变）。 */
    private void phOnDamageBase(EntityDamageEvent e) {
        try {
            Entity vic = e.getEntity();
            if (vic == null || !phIsBoss(vic)) {
                return;
            }
            Ref rf = phRefOf(vic);
            if (rf == null) {
                return;
            }
            double sc = rf.hpScale;
            if (sc >= 1.0 || sc <= 0.0) {
                return;
            }
            double d;
            try {
                d = e.getDamage();
            } catch (Throwable err) {
                d = 0.0;
            }
            if (d <= 0.0) {
                return;
            }
            try {
                e.setDamage(d * sc);
            } catch (Throwable err) {
                // 静默
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 死亡处理：<b>先清干净原版掉落</b>（幻翼膜来自战利品表 entities/phantom，
     * 1.21.11 没有 {@code LootTables.EMPTY} ⇒ {@code getDrops().clear()} 是唯一可靠手段），
     * 再按标记决定经验与自定义掉落。
     */
    private void phOnDeath(EntityDeathEvent e) {
        try {
            LivingEntity ent = e.getEntity();
            if (ent == null) {
                return;
            }
            String t = phTagOf(ent);
            if (t == null || t.isEmpty()) {
                return;
            }

            // ① 原版掉落物一律清空（幻翼膜 / 手上装备 / 掉落列表）
            try {
                EntityEquipment eq = ent.getEquipment();
                if (eq != null) {
                    eq.clear();
                }
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.getDrops().clear();
            } catch (Throwable err) {
                // 静默
            }
            // 清完再清一遍（有些服务端会把刚清掉的东西又塞回 drops）
            try {
                e.getDrops().clear();
            } catch (Throwable err) {
                // 静默
            }

            if (PH_V_BOSS.equals(t)) {
                // ② boss 的经验 = PH_DROP_XP（第十三批修订：120000）
                try {
                    e.setDroppedExp(PH_DROP_XP);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    phDropLoot(ent);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    String id = String.valueOf(ent.getUniqueId());
                    for (Ref r : new ArrayList<>(active.values())) {
                        if (r == null) {
                            continue;
                        }
                        try {
                            if (String.valueOf(r.ent.getUniqueId()).equals(id)) {
                                phCleanup(r);
                            }
                        } catch (Throwable err) {
                            // 静默
                        }
                    }
                } catch (Throwable err) {
                    // 静默
                }
            } else {
                // ★ 自家人（落点召唤的闪电苦力怕等）：经验一并清 0，不给白嫖
                try {
                    e.setDroppedExp(0);
                } catch (Throwable err) {
                    // 静默
                }
            }
            String pk;
            try {
                pk = String.valueOf(ent.getUniqueId());
            } catch (Throwable err) {
                pk = "";
            }
            if (!pk.isEmpty() && pets.get(pk) != null) {
                pets.remove(pk);
            }
        } catch (Throwable err) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 这只爆炸算不算「我们家的」。
     *
     * <p>★★ 第六十三批：闪电苦力怕<b>一律</b>算自家爆炸 ⇒ 无条件清空待破坏方块列表
     * （原来这里挂着 {@code && !PH_PET_GRIEF}，开关已按用户要求删除 ⇒ 没有任何"再打开"的口子）。
     */
    private boolean phIsOurExplosive(Entity ent) {
        try {
            if (ent == null) {
                return false;
            }
            String t = phTagOf(ent);
            if (PH_V_SKULL.equals(t)) {
                return true;                        // 自家的凋零之首
            }
            if (PH_V_PET.equals(t)) {
                return true;                        // 闪电苦力怕（不破坏方块）
            }
            // 兜底：shooter 是自家 boss 的爆炸（头的标记万一丢了）
            Entity own = phShooterOf(ent);
            if (own != null && phIsFriend(own)) {
                return true;
            }
        } catch (Throwable e) {
            // 静默
        }
        return false;
    }

    /**
     * 爆炸拦截：<b>绝不破坏地形</b>。
     *
     * <p>① 主手段：清空「待破坏方块」列表 ⇒ 地形一个方块都不掉；
     * ② 兜底：1 刻后照着快照还原真的被炸掉的方块（正常情况是纯检查）。
     * <br>★ 原版凋零头的爆炸威力是硬编码的，光设 {@code setYield} 兜不住，
     * 所以那层「快照还原」<b>保留</b>（不当死代码删）。
     */
    private void phOnExplode(EntityExplodeEvent e) {
        try {
            Entity ent;
            try {
                ent = e.getEntity();
            } catch (Throwable err) {
                return;
            }
            if (!phIsOurExplosive(ent)) {
                return;
            }

            // ① 主手段：清空"待破坏方块"列表 ⇒ 地形一个方块都不掉
            List<BlockSnap> snap = null;
            try {
                List<Block> list = e.blockList();
                if (list != null) {
                    try {
                        if (list.size() > 0) {
                            snap = new ArrayList<>();
                            Iterator<Block> bit = list.iterator();
                            while (bit.hasNext()) {
                                Block bl = bit.next();
                                try {
                                    snap.add(new BlockSnap(bl, bl.getBlockData()));
                                } catch (Throwable err2) {
                                    // 静默
                                }
                            }
                        }
                    } catch (Throwable err2) {
                        // 静默
                    }
                    list.clear();
                }
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setYield(0);
            } catch (Throwable err) {
                // 静默
            }

            // ② 兜底：1 刻后照着快照还原真的被炸掉的方块（正常情况是纯检查）
            if (snap != null && !snap.isEmpty()) {
                final List<BlockSnap> s2 = snap;
                GS.runLater(() -> {
                    try {
                        for (BlockSnap rec : s2) {
                            try {
                                if (!String.valueOf(rec.b.getType().name())
                                        .equals(String.valueOf(rec.d.getMaterial().name()))) {
                                    rec.b.setBlockData(rec.d);
                                }
                            } catch (Throwable err3) {
                                // 静默
                            }
                        }
                    } catch (Throwable err4) {
                        // 静默
                    }
                }, 1L);
            }
        } catch (Throwable e2) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 手持本物品右键方块 ⇒ 方块交互一律取消（和「冰」「妄念」同一个套路）。
     *
     * <p>★ 已机械比对：{@code phRegisterGuard} 与 {@link InteractGuard} 逐字相同，
     * 故直接复用公共实现（原脚本注册在 RSC 插件名下，原生版注册在本插件名下 —— 等价物）。
     * <br>★ 原脚本这个函数<b>不打任何日志</b>（参考服日志里没有「方块交互拦截 已启用」那行）。
     */
    private static boolean phRegisterGuard() {
        return InteractGuard.register(PH_ITEM_ID);
    }

    /** 消耗手里 1 个本物品。 */
    private static void phConsume(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand == null) {
                return;
            }
            int amt;
            try {
                amt = hand.getAmount();
            } catch (Throwable e) {
                amt = 0;
            }
            if (amt > 1) {
                hand.setAmount(amt - 1);
            } else {
                inv.setItemInMainHand(null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** reload 残留清扫：把上一份脚本留下的 boss / 苦力怕 / 弹体（靠 PDC 标记识别）清掉。 */
    private void phPurgeLeftovers() {
        try {
            int removed = 0;
            for (World w : Bukkit.getWorlds()) {
                for (Entity en : w.getEntities()) {
                    if (en == null) {
                        continue;
                    }
                    String t = phTagOf(en);
                    if (PH_V_BOSS.equals(t) || PH_V_PET.equals(t)
                            || PH_V_SKULL.equals(t) || PH_V_TRIDENT.equals(t)) {
                        try {
                            en.remove();
                            removed++;
                        } catch (Throwable e) {
                            // 静默
                        }
                    }
                }
            }
            if (removed > 0) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 幻翼boss：清扫上一份脚本残留 " + removed + " 个实体");
                } catch (Throwable e) {
                    // 静默
                }
            }
        } catch (Throwable e) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            try {
                phRegisterEvents();               // 惰性重试
            } catch (Throwable e) {
                // 静默
            }
            try {
                phRegisterGuard();                // 惰性重试
            } catch (Throwable e) {
                // 静默
            }

            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }

            if (!Guards.isMainHandUse(event, p, PH_ITEM_ID)) {
                return null;
            }

            String uuid = String.valueOf(p.getUniqueId());
            long now = phNow();
            Long l = last.get(uuid);
            // ★ 500 毫秒去抖（原脚本写死的字面量，不是常量）
            if (l != null && (now - l) < 500) {
                return null;
            }
            last.put(uuid, now);

            if (!phSummon(p)) {
                return null;
            }
            if (PH_CONSUME) {
                phConsume(p);
            }
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 幻翼boss onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }

    // ==================================================================
    // 内部数据结构
    // ==================================================================

    /** 一只 boss 的全部状态（原脚本里那个 {@code ref} 字面量对象）。 */
    private static final class Ref {
        String owner;
        LivingEntity ent;
        double maxHp;
        /** {@code 'idle'} / {@code 'sonic'} / {@code 'dash'}。 */
        String mode;
        Sonic sonic;
        Dash dash;
        long cd1;
        long cd2;
        long cd3;
        long cd4;
        long cd5;
        long readyAt;
        long hpFixUntil;
        boolean hpOk;
        /** ★ 上限写不进去时的降级系数（按比例缩伤 ⇒ 等效血量不变）。 */
        double hpScale;
        BossBar bar;
        /** uuid → 已经加到血条上的玩家（原 {@code ref.barNear}）。 */
        final Map<String, Player> barNear = new LinkedHashMap<>();
        double lastX;
        double lastZ;
        long lastT;
        long unstickAt;
        World lw;
        double lx;
        double ly;
        double lz;
        boolean done;
    }

    /** 冲击波蓄力状态（原 {@code ref.sonic = { step, tgt }}）。 */
    private static final class Sonic {
        int step;
        LivingEntity tgt;
    }

    /** 冲刺状态（原 {@code ref.dash}）。 */
    private static final class Dash {
        /** ★ 只写不读（原脚本的 write-only 状态）。 */
        String phase;
        int step;
        LivingEntity tgt;
        Vector dir;
        double left;
        /** ★ 只写不读（原脚本的 write-only 状态）。 */
        boolean hit;
    }

    /** 一只闪电苦力怕的记录（原 {@code PH_PETS[k] = { e, at }}）。 */
    private static final class Pet {
        final Entity e;
        final long at;

        Pet(Entity e, long at) {
            this.e = e;
            this.at = at;
        }
    }

    /** 爆炸前的一格方块快照（原 {@code phOnExplode} 里的 {@code { b, d }}）。 */
    private static final class BlockSnap {
        final Block b;
        final BlockData d;

        BlockSnap(Block b, BlockData d) {
            this.b = b;
            this.d = d;
        }
    }
}
