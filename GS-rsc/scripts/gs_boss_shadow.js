/* ==========================================================================
 * 构思技艺 (GS) —— 三阶段 boss「暗影虫卵」(GS_SHADOW_EGG)      ★ 第八十二批新增
 * --------------------------------------------------------------------------
 * 物品：items.yml 的 `GS_SHADOW_EGG`（item_group: gs_boss / display_tier: 4）
 *       材质 = 存档物品 `saveditems/myr.yml`（玩家头颅 + minecraft:item_model）
 *       lore 留空、配方留空（recipe_type: "NULL"）—— 用户要求「暂时留空」
 * 召唤：手持本物品 **右键**（消耗 1 个，`SB_CONSUME = true`）
 * --------------------------------------------------------------------------
 * ★★ 用户原话（第八十二批，逐字）：
 *   「新增一个阶段boss，材质为 ...\saveditems\myr.yml，lore暂时留空，配方暂时留空」
 *   「暗影虫卵」（物品名，2026-10-13 追加确认）
 *
 *   一阶段 —— 末影螨「暗影巢虫」
 *     · 删除原版掉落
 *     · 血量 150
 *     · 每次攻击玩家时玩家获得凋零 II 5 秒
 *     · 不会吸引末影人仇恨
 *     · 基础伤害 16
 *   二阶段 —— 末影人「暗影猎手」
 *     · 删除原版掉落
 *     · 血量 650
 *     · 基础伤害 25
 *     · 每 8 秒朝玩家发射一枚追踪龙息弹，不会留下龙息团，伤害 40
 *     · 每 11 秒将玩家瞬移到身前
 *   三阶段 —— 末影人「暗影女皇」
 *     · 删除原版掉落
 *     · 血量 1000
 *     · 每 7 秒将玩家瞬移到身前
 *     · 基础伤害 33
 *     · 每 10 秒发射一枚弹道箭矢伤害 20，
 *       箭矢落地位置生成末影螨「暗影蠕虫」
 *       （不吸引末影人仇恨，血量 30，会不断靠近暗影女皇，
 *         与女皇接触后死亡并为女皇恢复 77 血量）
 *     · 每 15 秒脚下生成 4 个末影螨「暗影蛆虫」
 *       （删除原版掉落，血量 90，不吸引末影人仇恨，基础伤害 8，
 *         生成 15 秒后死亡，死亡位置留下龙息团）
 *
 * ★★ 用户原话（第八十三批，逐字）：
 *   「二阶段三阶段改成在前一个阶段的死亡位置生成，
 *     瞬移技能的使用冷却分开计算，别的技能可能有阶段开始时的4秒冷却，
 *     但是瞬移直接在阶段开始就使用一次，
 *     暗影蛆虫的龙息团采用原版龙喷出的龙息」
 *   「龙息只对玩家造成伤害，不会对boss以及对应小怪造成伤害」
 * ==========================================================================
 *
 * ★★ 关键实现要点（都是踩过坑之后定下来的，改动前先读）
 *
 * ① **「不会留下龙息团」靠取消 `ProjectileHitEvent` 实现**（2026-10-13 反编译核实）：
 *    `net.minecraft.world.entity.projectile.Projectile#preHitTargetOrDeflectSelf`
 *    的字节码是：
 *        callProjectileHitEvent(...)  →  hitCancelled = event.isCancelled()
 *        if (type != BLOCK && hitCancelled) return ProjectileDeflection.NONE;
 *        else hitTargetOrDeflectSelf(...)   // ← 这里面才调 onHit
 *    ⇒ **取消事件之后 NMS 的 `onHit` 整个不跑** ⇒ `DragonFireball.onHit` 里那段
 *      「新建 AreaEffectCloud + 龙息粒子 + INSTANT_DAMAGE」**根本不会执行**。
 *      所以龙息弹既不留云、也没有原版伤害，伤害 40 完全由脚本自己结算。
 *    （反证：如果只 `remove()` 弹体而不取消事件，云还是会出来。）
 *
 * ② **末影人乱传送必须掐掉**：原版末影人挨打 / 沾水 / 追击时都会 `randomTeleport`，
 *    一个会瞬移的 boss 根本没法打。`Entity#randomTeleport` 里调了
 *    `CraftEventFactory.callEntityTeleportEvent` 并检查 `isCancelled()`
 *    ⇒ 监听 `EntityTeleportEvent`，凡是**我们这条线的末影人**一律取消。
 *    （★ 我们自己的技能只传送**玩家**，走的是 `PlayerTeleportEvent`，不受影响。）
 *
 * ③ **末影人会搬方块**：`EnderMan` 自带 `EndermanTakeBlockGoal` / `EndermanLeaveBlockGoal`，
 *    不拦的话 boss 会把战场拆了 ⇒ 监听 `EntityChangeBlockEvent`，我们的 boss 一律取消。
 *
 * ④ **末影人天生会主动攻击末影螨**（反编译确认）：
 *    `EnderMan.registerGoals()` 里有
 *    `NearestAttackableTargetGoal<>(this, Endermite.class, 1, false, false)`
 *    ——**无条件**、跟 `isPlayerSpawned` 无关。所以「不吸引末影人仇恨」不能靠
 *    「用刷怪蛋生成」糊过去，必须监听 `EntityTargetLivingEntityEvent` /
 *    `EntityTargetEvent`，目标是**我们这条线的末影螨**时取消。
 *
 * ⑤ **末影人 boss 不会自己追人**：原版 `EndermanLookForPlayerGoal` 只在玩家
 *    「盯着它看」时才锁定目标 ⇒ 光靠原版 AI，boss 会站着不动。
 *    因此每 `SB_TICK`(5 刻) 主动 `setTarget(最近的玩家)`；
 *    `EnderMan.registerGoals()` 里有 `MeleeAttackGoal`，设了目标就会追打。
 *    （★ 副作用：`EndermanFreezeWhenLookedAt` 还在，玩家盯着它看时它会僵住 ——
 *      这是原版末影人的招牌行为，本批**未改动**，要改再说。）
 *
 * ⑥ **「追踪」龙息弹 = 每刻重设一次 `setDirection`**：`AbstractHurtingProjectile`
 *    自己会走惯性，我们每刻把速度方向掰回玩家身上 ⇒ 就是追踪弹。
 *
 * ⑦ **末影螨不会到点自爆**（反编译确认 1.21.11 的 `Endermite#tick()` 只做了
 *    `yBodyRot` 同步 + `super.tick()`，**没有**老的 2400 刻寿命自毁）⇒
 *    小怪的生命周期完全由脚本自己管（`SB_S3_MAGGOT_LIFE_MS`）。
 *
 * ⑧ **删原版掉落**只能靠死亡事件里 `getDrops().clear()`：
 *    1.21.11 **没有** `LootTables.EMPTY` 常量，也没有 `minecraft:empty` 战利品表
 *    （老写法「置空战利品表」在这里是**静默失效**的）。
 *
 * ⑨ PDC 的 `NamespacedKey` 必须用 `NamespacedKey.fromString('gs:xxx')`，
 *    **不能**用 `new NamespacedKey(plugin, name)`（GraalJS 下重载消歧义失败）。
 *
 * ⑩ 「龙息团」= **原版末影龙喷出的那团**（第八十三批改）：
 *    建云的过程逐条抄 1.21.11 `DragonFireball#onHit`，但**范围与时长按用户给的值**：
 *    半径 **固定 4**、存在 **8 秒（160 刻）**、`INSTANT_DAMAGE` 等级 1（= 12 点）、
 *    `waitTime` 保留类默认的 20 刻。详见常量处的对照表。
 *    ★★ 但它**只对玩家造成伤害** —— 原版那团云六亲不认，会连女皇和她自己的
 *       蛆虫一起烤，所以监听器里加了 `sbCloudGuard()`：
 *       受击者是我们这条线上的人 + 伤害来源是**我们打过记号的云**（或云的主人）
 *       ⇒ 取消。玩家那边一点不受影响。
 *
 * ⑪ **二 / 三阶段生成在「前一个阶段的死亡位置」**（第八十三批）：
 *    死亡事件里把**精确**死亡坐标写进 `ref.dx/dy/dz`（最准），
 *    `sbTick` 每拍再写一份「活着的最后位置」兜底 ⇒ `sbPhaseEnd` 优先用前者。
 *
 * ⑫ **「瞬移」的冷却单独算**（第八十三批）：
 *    其它技能受 `SB_FIRST_MS`（4 秒）出场保护期约束，瞬移**不受** ——
 *    它有自己的 `ref.teleReadyAt`（阶段开始 = now），并且 `cdTele` 在
 *    `sbNext` 里先拨到「刚好走完」⇒ **阶段开场立刻瞬移一次**，
 *    之后按 7 秒 / 11 秒自己走，和别的技能的冷却互不影响。
 *
 * ★★★ 第八十五批：**第三阶段掉落物**（用户：「暗影虫卵第三阶段掉落物，固定掉落6个」）
 *    · 只有**第三阶段（暗影女皇）**死掉才掉 —— **固定 6 个「暗影宝珠」**
 *      （`GS_SHADOW_ORB`，items.yml 里那件燧石 + 附魔无限的物品）
 *    · 一阶段（暗影巢虫）/ 二阶段（暗影猎手）死掉**什么都不掉**
 *    · 掉落点在 `sbPhaseEnd()` 的 `stage >= 3` 那一支，坐标就是**死亡位置**
 *      （和阶段接棒用的是同一组 x/y/z）
 *    · 取物品走 RSC 注入的 `getSfItemById()`，和「妄念」掉妄想碎片同一套写法
 *    · 配方**仍然留空**（`recipe_type: "NULL"`）—— 本批只补掉落
 *
 * ★★★ 第八十六批：**第二阶段掉落物**（用户：「暗影虫卵二阶段固定掉落一把致万千无言者，
 *    删除配方，改成击败二阶段暗影掉落」）
 *    · **第二阶段（暗影猎手）**死掉 ⇒ 死亡位置掉 **1 把「致万千无言者」**
 *      （`GS_VOICELESS`）
 *    · 它的 9 格配方已从 `mb_machines.yml` 的构思机里**删除**，
 *      `items.yml` 的 `recipe_type` 改成 `GS_RT_SHADOW_S2`
 *      （配方界面左边显示「击败二阶段暗影虫卵掉落」，九宫格留空）
 *    · 一阶段仍然什么都不掉；三阶段仍然掉 6 个「暗影宝珠」
 * ========================================================================== */

// ==================================================================
// 常量
// ==================================================================
var SB_ITEM_ID     = 'GS_SHADOW_EGG';   // items.yml 里的物品 id
var SB_CONSUME     = true;              // 召唤成功才扣 1 个
var SB_SPAWN_AHEAD = 3.0;               // 一阶段生成在玩家前方几格
var SB_TICK        = 5;                 // 主循环间隔（刻）
var SB_FIRST_MS    = 4000;              // 出场保护期（毫秒）：这段时间不放技能
var SB_SEEK        = 48.0;              // 索敌半径（格）
var SB_HP_FIX_MS   = 8000;              // 血量强设的重试窗口（毫秒）
var SB_TRANS_TICKS = 40;                // 阶段切换演出时长（刻）= 2 秒
var SB_BAR_RANGE   = 48.0;              // 血条可见半径
var SB_BAR_ONLY_OWNER = true;           // 血条只给召唤者看
var SB_TELE_AHEAD  = 2.0;               // 「瞬移到身前」= boss 正前方几格

// ---- 一阶段：末影螨「暗影巢虫」----
var SB_S1_NAME  = '\u00a75\u6697\u5f71\u5de2\u866b';   // §5暗影巢虫
var SB_S1_HP    = 150;
var SB_S1_DMG   = 16;
var SB_S1_WITHER_TICKS = 100;   // 5 秒 = 100 刻
var SB_S1_WITHER_AMP   = 1;     // 等级 II（amplifier 1）

// ---- 二阶段：末影人「暗影猎手」----
var SB_S2_NAME  = '\u00a75\u6697\u5f71\u730e\u624b';   // §5暗影猎手
var SB_S2_HP    = 650;
var SB_S2_DMG   = 25;
var SB_S2_BREATH_CD    = 8000;   // 每 8 秒一发
var SB_S2_BREATH_DMG   = 40;     // 伤害 40
var SB_S2_BREATH_R     = 3.0;    // 命中半径
var SB_S2_BREATH_SPEED = 0.9;    // 每刻位移（格）
var SB_S2_BREATH_LIFE_MS = 8000; // 飞这么久还没中 ⇒ 自行消散
var SB_S2_TELE_CD      = 11000;  // 每 11 秒把玩家瞬移到身前

// ---- 三阶段：末影人「暗影女皇」----
var SB_S3_NAME  = '\u00a75\u6697\u5f71\u5973\u7687';   // §5暗影女皇
var SB_S3_HP    = 1000;
var SB_S3_DMG   = 33;
var SB_S3_TELE_CD   = 7000;    // 每 7 秒把玩家瞬移到身前
var SB_S3_ARROW_CD  = 10000;   // 每 10 秒一支箭
var SB_S3_ARROW_DMG = 20;      // 箭伤 20
var SB_S3_ARROW_SPEED = 1.6;
var SB_S3_ARROW_SPREAD = 0.0;

// 「暗影蠕虫」：箭矢落点生成，爬向女皇，接触即死并给女皇回血
var SB_S3_WORM_NAME  = '\u00a75\u6697\u5f71\u8815\u866b';   // §5暗影蠕虫
var SB_S3_WORM_HP    = 30;
var SB_S3_WORM_HEAL  = 77;     // 接触后给女皇回 77 血
var SB_S3_WORM_SPEED = 0.30;   // 每刻位移
var SB_S3_WORM_TOUCH = 1.6;    // 判定「接触」的距离

// 「暗影蛆虫」：女皇脚下每 15 秒刷 4 只，15 秒后死亡并留下龙息团
var SB_S3_MAGGOT_NAME  = '\u00a75\u6697\u5f71\u86c6\u866b';  // §5暗影蛆虫
var SB_S3_MAGGOT_HP    = 90;
var SB_S3_MAGGOT_DMG   = 8;
var SB_S3_MAGGOT_CD    = 15000;
var SB_S3_MAGGOT_N     = 4;
var SB_S3_MAGGOT_LIFE_MS = 15000;   // 生成 15 秒后死亡
// ★★★ 第八十三批：蛆虫死亡留下的龙息团 = **原版末影龙喷出的那团龙息**
//   逐条对齐 1.21.11 `DragonFireball#onHit`（反编译核对）建云的 7 步 ——
//     ① new AreaEffectCloud(level, x, y, z)             → world.spawn(loc, AEC)
//     ② setOwner(owner)                                  ← owner 是 LivingEntity 才设
//     ③ setCustomParticle(DRAGON_BREATH.create(1.0f))    → setParticle(pt, 1.0f)
//     ④ setRadius(3.0f)
//     ⑤ setDuration(600)
//     ⑥ setRadiusPerTick((7.0f - getRadius()) / getDuration())
//     ⑦ addEffect(new MobEffectInstance(INSTANT_DAMAGE, 1, 1))
//
//   ★★ 本批第三条追加要求（用户逐字）：
//        「龙息的范围固定4，存在时间8秒」
//      ⇒ **④⑤⑥ 三步按用户给的数字改掉**（其余原版参数一律照旧）：
//        · 半径 **固定 4**（原版是 3 → 7 一路扩大 ⇒ 现在 `radiusPerTick = 0`）
//        · 存在 **8 秒 = 160 刻**（原版是 600 刻 = 30 秒）
//      ★ 伤害仍然是原版的 `6 << 1` = **12 点**，
//        每 `reapplicationDelay`（原版默认 20 刻）再来一次；
//        粒子仍然是原版的 `DRAGON_BREATH` 强度 1.0f。
//   ★ **不要**动 `waitTime`：原版那团云的 `waitTime` 是**默认值 20**（先蓄 1 秒），
//     之前脚本写死 `setWaitTime(0)` ⇒ 那不是原版行为，本批已删掉。
//   ★ 只有 `setPotionDurationScale(0.25f)` 在 Bukkit 侧**没有对应 API**
//     （`org.bukkit.entity.AreaEffectCloud` 接口里根本没这个方法）——
//     它只缩放「药水效果的持续时间」，而这里唯一的效果是**瞬间伤害**（duration 1），
//     缩放与否没有任何可观察差异。
//   ★★ 「只对玩家造成伤害」**不靠**这里的参数，靠监听器里的 sbCloudGuard()
//      —— 原版那团云是**六亲不认**的，会连女皇和她自己的蛆虫一起烤。
var SB_S3_CLOUD_R      = 4.0;       // ★ 固定半径 4（用户：范围固定4）
var SB_S3_CLOUD_TICKS  = 160;       // ★ 存在 8 秒（用户：存在时间8秒）
var SB_S3_CLOUD_AMP    = 1;         // 原版 MobEffectInstance(INSTANT_DAMAGE, 1, 1)
var SB_S3_CLOUD_POWER  = 1.0;       // 原版 DRAGON_BREATH.create(1.0f)

// ==================================================================
// ★★★ 第八十五批：掉落物
//   用户原话：「暗影虫卵第三阶段掉落物，固定掉落6个」
//   ⇒ **只有第三阶段（暗影女皇）**死掉才掉 **6 个「暗影宝珠」**
//      （一阶段「暗影巢虫」/ 二阶段「暗影猎手」死掉**什么都不掉**）。
//   ★ 掉落时机在 sbPhaseEnd() 里 —— 那里已经是"某个阶段打完了"的**唯一**入口，
//     而且它拿到的 (x, y, z) 正是**死亡位置**（死亡事件写的 ref.dx/dy/dz）。
//   ★ 物品本体在 items.yml 的 GS_SHADOW_ORB（燧石 · 基础构思第 20 格 · 附魔无限）。
//   ★ 拾取音效和「妄念」那边（btDropFragments）用同一个，手感一致。
// ==================================================================
var SB_DROP_ORB_ID = 'GS_SHADOW_ORB';   // items.yml 里的物品 id
var SB_DROP_ORB_N  = 6;                 // ★ 固定 6 个（用户指定）

// ==================================================================
// ★★★ 第八十六批：二阶段掉落物
//   用户原话：「暗影虫卵二阶段固定掉落一把致万千无言者，删除配方，
//             改成击败二阶段暗影掉落」
//   ⇒ **二阶段（暗影猎手）**死掉 ⇒ 死亡位置掉 **1 把「致万千无言者」**
//     （`GS_VOICELESS`，items.yml 里的 9 格配方已删除，改成纯掉落）
//   · 一阶段（暗影巢虫）死掉**什么都不掉**
//   · 三阶段（暗影女皇）死掉仍然掉 6 个「暗影宝珠」（第八十五批）
// ==================================================================
var SB_DROP_VOICE_ID = 'GS_VOICELESS';   // 致万千无言者
var SB_DROP_VOICE_N  = 1;                // ★ 固定 1 把（用户指定）

// ==================================================================
// 状态
// ==================================================================
var SB_ACTIVE = {};   // 召唤者 uuid -> ref（每个玩家同时只能有一条线）
var SB_LAST   = {};   // 召唤者 uuid -> 上次点击时间戳（防一次点击被派发两遍）

// PDC
var SB_KEY      = null;   // gs:shadowboss        → 'boss1' / 'boss2' / 'boss3' / 'worm' / 'maggot'
var SB_PROJ_KEY = null;   // gs:shadowboss_proj   → 'breath' / 'arrow'
var SB_OWN_KEY  = null;   // gs:shadowboss_owner  → 召唤者 uuid（弹体 / 小怪反查用）
// ★★ 第八十三批：龙息团的标记。**故意用第三个 key** ——
//    `sbOurs()` 只认上面两个 ⇒ 云不会被 sbSweepLeftovers() 当成"我们的人"扫掉
//    （原版云自己会到点消散，不需要我们管）。
var SB_CLOUD_KEY = null;  // gs:shadowboss_cloud  → '1'
var SB_PDT      = null;
try {
    SB_PDT      = Java.type('org.bukkit.persistence.PersistentDataType');
    SB_KEY      = Java.type('org.bukkit.NamespacedKey').fromString('gs:shadowboss');
    SB_PROJ_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:shadowboss_proj');
    SB_OWN_KEY  = Java.type('org.bukkit.NamespacedKey').fromString('gs:shadowboss_owner');
    SB_CLOUD_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:shadowboss_cloud');
} catch (e) { SB_KEY = null; SB_PROJ_KEY = null; SB_OWN_KEY = null; SB_CLOUD_KEY = null; SB_PDT = null; }

// ==================================================================
// 小工具
// ==================================================================
function sbNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function sbAlive(e) {
    try { if (e == null) return false; return !(e.isDead() || !e.isValid()); }
    catch (e2) { return false; }
}

function sbTagOf(e, key) {
    try {
        if (e == null || key == null || SB_PDT == null) return '';
        var v = e.getPersistentDataContainer().get(key, SB_PDT.STRING);
        return (v == null ? '' : '' + v);
    } catch (err) { return ''; }
}

function sbTag(e)      { return sbTagOf(e, SB_KEY); }
function sbProjTag(e)  { return sbTagOf(e, SB_PROJ_KEY); }
function sbOwnerOf(e)  { return sbTagOf(e, SB_OWN_KEY); }
// ★★ 第八十三批：这是我们造的「龙息团」吗（「只对玩家造成伤害」的判定用）
function sbCloudTag(e) { return sbTagOf(e, SB_CLOUD_KEY); }

function sbSetTagOf(e, key, val) {
    try {
        if (e == null || key == null || SB_PDT == null) return false;
        e.getPersistentDataContainer().set(key, SB_PDT.STRING, '' + val);
        return true;
    } catch (err) { return false; }
}

// 这是我们这条 boss 线上的东西吗（boss / 小怪 / 弹体都算）
function sbOurs(e) {
    try {
        if (e == null || SB_KEY == null || SB_PDT == null) return false;
        if (e.getPersistentDataContainer().has(SB_KEY, SB_PDT.STRING)) return true;
        if (SB_PROJ_KEY != null && e.getPersistentDataContainer().has(SB_PROJ_KEY, SB_PDT.STRING)) return true;
        return false;
    } catch (err) { return false; }
}

// 这是我们这条线上的**末影螨**吗（「不吸引末影人仇恨」的判定用）
//   ★ 只要「带我们的标记」且「本体是末影螨」就算 —— 一阶段巢虫、
//     三阶段的蠕虫 / 蛆虫全是末影螨，都要保护；
//     二 / 三阶段的 boss 是**末影人**，类型这一关天然把它们排除掉。
function sbIsOurMite(e) {
    try {
        if (e == null) return false;
        if (sbTag(e).length === 0) return false;
        return ('' + e.getType().name()).indexOf('ENDERMITE') >= 0;
    } catch (err) { return false; }
}

function sbDist(a, b) {
    try { return a.getLocation().distance(b.getLocation()); } catch (e) { return 999.0; }
}

// ★ GraalJS 里 Java 类**必须用 `new` 调构造器**（`Java.type('X')(...)` 不成立）
function sbVec(x, y, z) {
    try { var V = Java.type('org.bukkit.util.Vector'); return new V(x, y, z); }
    catch (e) { return null; }
}

function sbLoc(world, x, y, z, yaw, pitch) {
    try { var L = Java.type('org.bukkit.Location'); return new L(world, x, y, z, yaw, pitch); }
    catch (e) { return null; }
}

// 方块的通行判定（★ 用 isPassable：**玻璃挡得住**）
function sbPassableAt(world, bx, by, bz) {
    try {
        var blk = world.getBlockAt(sbLoc(world, 0 + bx, 0 + by, 0 + bz, 0, 0));
        if (blk == null) return true;
        var mt = null;
        try { mt = blk.getType(); } catch (e) { mt = null; }
        if (mt != null) { try { if (mt.isAir()) return true; } catch (e) { } }
        try { return !!blk.isPassable(); } catch (e) { }
        if (mt != null) { try { return !mt.isSolid(); } catch (e) { } }
        return true;
    } catch (e) { return true; }
}

function sbHeadroom(world, bx, bz, by, height) {
    try {
        for (var i = 0; i < height; i++) {
            if (!sbPassableAt(world, bx, by + i, bz)) return false;
        }
        return true;
    } catch (e) { return false; }
}

// 找一个能站的落地高度（先往下找实地，找不到就往上抬）
function sbSafeY(world, x, z, y0, height) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z);
        var start = Math.floor(y0);
        var lo = start - 4, hi = start + 8;
        if (lo < -60) lo = -60;
        if (hi > 250) hi = 250;
        var y;
        for (y = start; y >= lo; y--) {
            if (sbHeadroom(world, bx, bz, y, height) && !sbPassableAt(world, bx, y - 1, bz)) return y;
        }
        for (y = start; y <= hi; y++) {
            if (sbHeadroom(world, bx, bz, y, height)) return y;
        }
        return y0;
    } catch (e) { return y0; }
}

// 粒子名解析（多个候选，逐个试）
function sbP(cands) {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < cands.length; i++) {
            try { var p = P[cands[i]]; if (p != null) return p; } catch (e) { }
            try { var p2 = P.valueOf(cands[i]); if (p2 != null) return p2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★ 只挑「不需要附加数据」的粒子（SimpleParticleType）——
//   DRAGON_BREATH 这类要 data 的粒子直接 spawnParticle 会抛异常，别拿来当兜底。
var SB_P_SAFE = null;
function sbPSafe() {
    try {
        if (SB_P_SAFE != null) return SB_P_SAFE;
        SB_P_SAFE = sbP(['SOUL_FIRE_FLAME', 'PORTAL', 'SMOKE', 'SMOKE_NORMAL', 'FLAME']);
        return SB_P_SAFE;
    } catch (e) { return null; }
}

function sbFx(world, pt, x, y, z, n, dx, dy, dz, speed) {
    try {
        if (world == null || pt == null) return;
        world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
    } catch (e) { }
}

// ★ 龙息粒子：`Particle.DRAGON_BREATH` 在 NMS 那边是 `PowerParticleOption`（要 data），
//   但 Bukkit 侧到底要不要 data、要什么类型，各版本不一样 ⇒
//   用 `Particle#getDataType()` 探一次并缓存：
//     Void/null ⇒ 直接 spawnParticle；Float ⇒ 走带 data 的重载；其他 ⇒ 整体退回 sbPSafe()。
var SB_P_BREATH = null;
var SB_P_BREATH_DATA = null;
var SB_P_BREATH_TRIED = false;

function sbBreathParticle(world) {
    try {
        if (SB_P_BREATH_TRIED) return SB_P_BREATH;
        SB_P_BREATH_TRIED = true;
        var pt = null;
        try { pt = Java.type('org.bukkit.Particle').DRAGON_BREATH; } catch (e) { pt = null; }
        if (pt == null) { SB_P_BREATH = null; return null; }

        var dn = '';
        try {
            var dt = pt.getDataType();
            dn = (dt == null ? '' : '' + dt.getName());
        } catch (e) { dn = ''; }

        if (dn.length === 0 || dn === 'java.lang.Void') {
            SB_P_BREATH = pt; SB_P_BREATH_DATA = null; return pt;
        }
        if (dn === 'java.lang.Float') {
            SB_P_BREATH = pt; SB_P_BREATH_DATA = 1.0; return pt;
        }
        // 其它 data 类型（DustOptions / BlockData …）本脚本不用，别每拍抛异常
        SB_P_BREATH = null;
        return null;
    } catch (e) { SB_P_BREATH = null; return null; }
}

function sbBreathFx(world, x, y, z, n) {
    try {
        if (world == null) return;
        var pt = sbBreathParticle(world);
        if (pt != null) {
            if (SB_P_BREATH_DATA == null) {
                world.spawnParticle(pt, x, y, z, n, 0.08, 0.08, 0.08, 0.0);
            } else {
                world.spawnParticle(pt, x, y, z, n, 0.08, 0.08, 0.08, 0.0, SB_P_BREATH_DATA);
            }
            return;
        }
        sbFx(world, sbPSafe(), x, y, z, n, 0.08, 0.08, 0.08, 0.01);
    } catch (e) { }
}

function sbSound(world, loc, name, vol, pitch) {
    try {
        try { world.playSound(loc, name, vol, pitch); }
        catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); } catch (e2) { } }
    } catch (e) { }
}

function sbET(names) {
    try {
        var ET = Java.type('org.bukkit.entity.EntityType');
        for (var i = 0; i < names.length; i++) {
            try { var t = ET[names[i]]; if (t != null) return t; } catch (e) { }
            try { var t2 = ET.valueOf(names[i]); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ---------------- 属性 ----------------
function sbAttrKey(names) {
    try {
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        for (var i = 0; i < names.length; i++) {
            try { var k = Attr[names[i]]; if (k != null) return k; } catch (e) { }
            try { var k2 = Attr.valueOf(names[i]); if (k2 != null) return k2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★★ 专门找 **MAX_HEALTH**（`GENERIC_MAX_HEALTH` ≤1.21.2 → `MAX_HEALTH` 1.21.3+），
//   名字怎么改都能找到：两个名字都试，再遍历 values() 模糊匹配兜底。
var SB_MAXATTR = null;
function sbFindMaxAttr() {
    try {
        if (SB_MAXATTR != null) return SB_MAXATTR;
        var k = sbAttrKey(['MAX_HEALTH', 'GENERIC_MAX_HEALTH']);
        if (k != null) { SB_MAXATTR = k; return k; }
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var all = Attr.values();
        for (var i = 0; i < all.length; i++) {
            var a = all[i];
            var nm = '', key = '';
            try { nm = '' + a.name(); } catch (e) { nm = ''; }
            try { key = '' + a.getKey().getKey(); } catch (e) { key = ''; }
            if (nm.indexOf('MAX_HEALTH') >= 0 || key.indexOf('max_health') >= 0) {
                SB_MAXATTR = a;
                return a;
            }
        }
    } catch (e) { }
    return null;
}

// 强设血量上限：删修改器 → 属性 baseValue → 旧 API setMaxHealth 兜底
function sbForceMax(ent, hp) {
    var ok = false;
    try {
        var k = sbFindMaxAttr();
        if (k != null) {
            var inst = ent.getAttribute(k);
            if (inst != null) {
                try {
                    var AL = Java.type('java.util.ArrayList');
                    var snap = new AL(inst.getModifiers());
                    var it = snap.iterator();
                    while (it.hasNext()) {
                        try { inst.removeModifier(it.next()); } catch (e) { }
                    }
                } catch (e) { }
                try { inst.setBaseValue(hp); ok = true; } catch (e) { }
            }
        }
    } catch (e) { }
    try { ent.setMaxHealth(hp); ok = true; } catch (e) { }
    return ok;
}

// 把**最终伤害**（含手里武器的加成）精确设成 want 点
function sbSetDmg(ent, want) {
    try {
        var k = sbAttrKey(['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE']);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        var cur = inst.getValue();
        var base = inst.getBaseValue();
        inst.setBaseValue(base + (want - cur));
        return true;
    } catch (e) { return false; }
}

function sbMaxHp(ent) {
    try {
        var k = sbFindMaxAttr();
        if (k == null) return 0;
        var inst = ent.getAttribute(k);
        if (inst == null) return 0;
        return inst.getValue();
    } catch (e) { return 0; }
}

function sbHurt(src, ent, dmg) {
    try {
        try { ent.setNoDamageTicks(0); } catch (e) { }
        try { ent.damage(dmg, src); }
        catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
    } catch (e) { }
}

// 直接改血（回血用，绕过无敌帧与护甲）
function sbHeal(ent, amount) {
    try {
        if (ent == null || !sbAlive(ent)) return false;
        var hp = ent.getHealth();
        var mx = sbMaxHp(ent);
        if (mx <= 0) mx = hp + amount;
        var n = hp + amount;
        if (n > mx) n = mx;
        ent.setHealth(n);
        return true;
    } catch (e) { return false; }
}

// ★★ 血量写入：先确认上限到位，再回满血；失败就延迟重试 + 每拍重试。
var SB_HP_DIAG = {};
function sbHpDiag(ent, want, max, cur, why) {
    try {
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { id = 'unknown'; }
        if (SB_HP_DIAG[id] != null) return;
        SB_HP_DIAG[id] = 1;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { tn = '?'; }
        var kname = 'null';
        try { var k = sbFindMaxAttr(); if (k != null) kname = '' + k.name(); } catch (e) { }
        try {
            getServer().getLogger().warning('[构思技艺] 暗影虫卵 血量设置失败(' + why + ')：实体='
                + tn + ' 目标=' + want + ' 实际上限=' + max + ' 实际血量=' + cur
                + ' MAX_HEALTH键=' + kname);
        } catch (e2) { }
    } catch (e) { }
}

function sbApplyHpOnce(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return false;
        sbForceMax(ent, hp);
        var mx = sbMaxHp(ent);
        if (mx < hp - 0.5) { sbHpDiag(ent, hp, mx, -1, '上限没上去'); return false; }
        try { ent.setHealth(hp); } catch (e) { }
        var now = -1;
        try { now = ent.getHealth(); } catch (e) { now = -1; }
        if (now >= hp - 0.5) return true;
        sbHpDiag(ent, hp, mx, now, '血量没回满');
        return false;
    } catch (e) { return false; }
}

function sbApplyHp(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return;
        if (sbApplyHpOnce(ent, hp)) return;
        var delays = [1, 3, 6, 10, 20, 40];
        for (var i = 0; i < delays.length; i++) {
            runLater(function (e0, h0) {
                return function () {
                    try { if (!sbAlive(e0)) return; sbApplyHpOnce(e0, h0); } catch (e) { }
                };
            }(ent, hp), delays[i]);
        }
    } catch (e) { }
}

// ==================================================================
// BossBar（血条）
// ==================================================================
function sbBarMake(title) {
    try {
        var BarColor = Java.type('org.bukkit.boss.BarColor');
        var BarStyle = Java.type('org.bukkit.boss.BarStyle');
        var c = null, s = null;
        try { c = BarColor.PURPLE; } catch (e) { c = null; }
        if (c == null) { try { c = BarColor.valueOf('PURPLE'); } catch (e2) { } }
        if (c == null) { try { c = BarColor.RED; } catch (e2) { } }
        try { s = BarStyle.SEGMENTED_10; } catch (e) { s = null; }
        if (s == null) { try { s = BarStyle.valueOf('SEGMENTED_10'); } catch (e2) { } }
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(title, c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setVisible(true); } catch (e) { }
        try { bar.setProgress(1.0); } catch (e) { }
        return bar;
    } catch (e) { return null; }
}

function sbBarSync(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        var ent = ref.ent;
        var alive = sbAlive(ent);

        var prog = 0.0;
        if (alive) {
            try {
                var hp = ent.getHealth();
                var mx = sbMaxHp(ent);
                if (mx <= 0) mx = ref.maxHp;
                prog = hp / (mx > 0 ? mx : 1);
            } catch (e) { prog = 0.0; }
        }
        if (prog < 0) prog = 0; if (prog > 1) prog = 1;
        try { ref.bar.setProgress(prog); } catch (e) { }
        try { ref.bar.setVisible(alive); } catch (e) { }

        var want = {};
        if (alive) {
            try {
                var wname = '' + ent.getWorld().getName();
                var el = ent.getLocation();
                var players = getServer().getOnlinePlayers();
                var it = players.iterator();
                while (it.hasNext()) {
                    var p = it.next();
                    if (p == null) continue;
                    if (SB_BAR_ONLY_OWNER) {
                        try { if ('' + p.getUniqueId() !== '' + ref.owner) continue; } catch (e) { continue; }
                    }
                    try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    if (pl.distance(el) > SB_BAR_RANGE) continue;
                    try { want['' + p.getUniqueId()] = p; } catch (e) { }
                }
            } catch (e) { }
        }
        var k;
        for (k in want) {
            if (ref.barNear[k] == null) { try { ref.bar.addPlayer(want[k]); } catch (e) { } ref.barNear[k] = want[k]; }
        }
        for (k in ref.barNear) {
            if (want[k] == null) {
                try { ref.bar.removePlayer(ref.barNear[k]); } catch (e) { }
                try { delete ref.barNear[k]; } catch (e) { }
            }
        }
    } catch (e) { }
}

function sbBarDrop(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        try { ref.bar.setVisible(false); } catch (e) { }
        try { ref.bar.removeAll(); } catch (e) { }
        try { for (var k in ref.barNear) { delete ref.barNear[k]; } } catch (e) { }
        ref.bar = null;
    } catch (e) { }
}

// ==================================================================
// 找目标 / 找 ref
// ==================================================================
// 最近的玩家（同世界、SB_SEEK 格内）
function sbNearestPlayer(ref) {
    try {
        if (ref == null) return null;
        var world = ref.lw;
        if (world == null) return null;
        var boss = ref.ent;
        if (!sbAlive(boss)) return null;
        var bl = boss.getLocation();
        var best = null, bestD = SB_SEEK;
        var players = world.getPlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if (p.isDead()) continue; } catch (e) { }
            try { if (p.getGameMode() != null && ('' + p.getGameMode().name()) === 'SPECTATOR') continue; } catch (e) { }
            var d = 0.0;
            try { d = p.getLocation().distance(bl); } catch (e) { continue; }
            if (d < bestD) { bestD = d; best = p; }
        }
        return best;
    } catch (e) { return null; }
}

// 从弹体 / 小怪反查它属于哪条线（PDC 里记了召唤者 uuid）
function sbRefOf(ent) {
    try {
        var u = sbOwnerOf(ent);
        if (u == null || u.length === 0) return null;
        var r = SB_ACTIVE[u];
        if (r == null) return null;
        return r;
    } catch (e) { return null; }
}

// ==================================================================
// 生成实体
// ==================================================================
function sbSpawnMob(world, loc, typeNames, name, hp, dmg, tagValue, aware, ownerUuid) {
    try {
        var t = sbET(typeNames);
        if (t == null) {
            try { getServer().getLogger().warning('[构思技艺] 暗影虫卵：找不到实体类型 ' + typeNames[0]); } catch (e) { }
            return null;
        }
        var e = world.spawnEntity(loc, t);
        if (e == null) return null;

        sbApplyHp(e, hp);
        if (dmg != null && dmg >= 0) sbSetDmg(e, dmg);

        try { e.setCustomName(name); } catch (err) { }
        try { e.setCustomNameVisible(true); } catch (err) { }
        try { e.setRemoveWhenFarAway(false); } catch (err) { }
        try { e.setPersistent(true); } catch (err) { }
        try { e.setCanPickupItems(false); } catch (err) { }
        try { e.setAware(!!aware); } catch (err) { }
        try { e.setSilent(false); } catch (err) { }
        try { e.getEquipment().clear(); } catch (err) { }
        try { var inv = e.getInventory(); if (inv != null) inv.clear(); } catch (err) { }

        sbSetTagOf(e, SB_KEY, tagValue);
        if (ownerUuid != null) sbSetTagOf(e, SB_OWN_KEY, ownerUuid);

        return e;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 暗影虫卵：生成 ' + typeNames[0] + ' 出错 ' + e); } catch (e2) { }
        return null;
    }
}

// ==================================================================
// 技能：把玩家瞬移到 boss 身前
// ==================================================================
function sbPullToFront(ref, p) {
    try {
        var boss = ref.ent;
        if (!sbAlive(boss) || p == null) return false;
        var world = ref.lw;
        var bl = boss.getLocation();
        var yaw = 0.0;
        try { yaw = bl.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        var fx = bl.getX() + (-Math.sin(rad)) * SB_TELE_AHEAD;
        var fz = bl.getZ() + (Math.cos(rad)) * SB_TELE_AHEAD;
        var fy = sbSafeY(world, fx, fz, bl.getY(), 2);

        var from = null;
        try { from = p.getLocation(); } catch (e) { from = null; }

        // 出发点特效（反向传送门 = 被吸走）
        if (from != null) {
            try {
                var ptOut = sbP(['REVERSE_PORTAL', 'PORTAL', 'SMOKE_NORMAL']);
                sbFx(world, ptOut, from.getX(), from.getY() + 1.0, from.getZ(), 24, 0.35, 0.6, 0.35, 0.08);
            } catch (e) { }
            try { sbSound(world, from, 'entity.enderman.teleport', 1.0, 0.8); } catch (e) { }
        }

        var loc = sbLoc(world, fx, fy, fz, yaw + 180.0, 0.0);
        var ok = false;
        try { ok = p.teleport(loc); } catch (e) { ok = false; }

        // 落点特效
        try {
            var ptIn = sbP(['PORTAL', 'REVERSE_PORTAL', 'SMOKE_NORMAL']);
            sbFx(world, ptIn, fx, fy + 1.0, fz, 30, 0.35, 0.7, 0.35, 0.12);
        } catch (e) { }
        try { sbSound(world, loc, 'entity.enderman.teleport', 1.0, 1.3); } catch (e) { }
        return ok;
    } catch (e) { return false; }
}

// ==================================================================
// 技能：追踪龙息弹（二阶段）
// ==================================================================
function sbFireBreath(ref) {
    try {
        var boss = ref.ent;
        if (!sbAlive(boss)) return false;
        var tgt = sbNearestPlayer(ref);
        if (tgt == null) return false;
        var world = ref.lw;

        var from = null;
        try { from = boss.getEyeLocation(); } catch (e) { from = boss.getLocation(); }
        var to = tgt.getLocation();
        var dir = sbVec(to.getX() - from.getX(),
                        (to.getY() + 1.0) - from.getY(),
                        to.getZ() - from.getZ());
        if (dir == null || dir.lengthSquared() < 0.01) return false;
        dir.normalize();

        var fb = null;
        try {
            var DF = Java.type('org.bukkit.entity.DragonFireball');
            fb = world.spawn(from, DF);
        } catch (e) { fb = null; }
        if (fb == null) return false;

        try { fb.setShooter(boss); } catch (e) { }
        // ★ yield 只是兜底：真正生效的是「取消 ProjectileHitEvent」⇒ 连爆炸带云全没了
        try { fb.setYield(0.0); } catch (e) { }
        try { fb.setIsIncendiary(false); } catch (e) { }
        try { fb.setDirection(dir.multiply(SB_S2_BREATH_SPEED)); } catch (e) { }

        sbSetTagOf(fb, SB_PROJ_KEY, 'breath');
        sbSetTagOf(fb, SB_OWN_KEY, ref.owner);

        try { sbSound(world, from, 'entity.ender_dragon.shoot', 1.0, 1.35); } catch (e) { }
        try { sbSound(world, from, 'entity.ender_dragon.ambient', 0.6, 1.5); } catch (e) { }

        ref.breath = fb;
        ref.breathGen = (ref.breathGen == null ? 0 : ref.breathGen) + 1;
        sbBreathHoming(ref, fb, ref.breathGen, sbNow());
        return true;
    } catch (e) { return false; }
}

// 每刻把弹体掰回玩家身上 ⇒ 「追踪」
function sbBreathHoming(ref, fb, gen, born) {
    try {
        if (ref == null || ref.breathGen !== gen) return;
        if (!sbAlive(fb)) return;
        if (sbNow() - born > SB_S2_BREATH_LIFE_MS) {
            try { fb.remove(); } catch (e) { }
            try { if (ref.breath === fb) ref.breath = null; } catch (e) { }
            return;
        }
        var tgt = sbNearestPlayer(ref);
        if (tgt != null) {
            var fl = null;
            try { fl = fb.getLocation(); } catch (e) { fl = null; }
            var tl = null;
            try { tl = tgt.getLocation(); } catch (e) { tl = null; }
            if (fl != null && tl != null) {
                var d = sbVec(tl.getX() - fl.getX(),
                              (tl.getY() + 1.0) - fl.getY(),
                              tl.getZ() - fl.getZ());
                if (d != null && d.lengthSquared() > 0.01) {
                    d.normalize();
                    try { fb.setDirection(d.multiply(SB_S2_BREATH_SPEED)); } catch (e) { }
                }
            }
        }
        // 拖尾（弹体自己有原版拖尾，这里再补一圈，命中判定前的观感更足）
        try {
            var fl2 = fb.getLocation();
            sbBreathFx(ref.lw, fl2.getX(), fl2.getY(), fl2.getZ(), 2);
        } catch (e) { }
        runLater(function () { sbBreathHoming(ref, fb, gen, born); }, 1);
    } catch (e) { }
}

// 龙息弹命中：范围伤害 40 + 特效（★ 绝不留龙息团）
function sbBreathBoom(ref, loc) {
    try {
        if (loc == null) return;
        var world = null;
        try { world = loc.getWorld(); } catch (e) { world = null; }
        if (world == null) return;

        var boss = (ref == null ? null : ref.ent);

        // ① 伤害：命中点 SB_S2_BREATH_R 格内的**玩家**，每人 40
        var hit = 0;
        try {
            var list = world.getNearbyEntities(loc, SB_S2_BREATH_R, SB_S2_BREATH_R + 1.0, SB_S2_BREATH_R);
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null) continue;
                var tn = '';
                try { tn = '' + ent.getType().name(); } catch (e) { continue; }
                if (tn !== 'PLAYER') continue;
                try { if (ent.isDead()) continue; } catch (e) { }
                // ★ 不要打到自己人
                if (sbOurs(ent)) continue;
                sbHurt(boss, ent, SB_S2_BREATH_DMG);
                hit++;
            }
        } catch (e) { }

        // ② 视觉：龙息 + 灵魂火 + 末影粒子
        try {
            var x = loc.getX(), y = loc.getY(), z = loc.getZ();
            sbBreathFx(world, x, y, z, 60);
            sbFx(world, sbP(['SOUL_FIRE_FLAME', 'SOUL', 'FLAME']), x, y, z, 30, 0.6, 0.6, 0.6, 0.06);
            sbFx(world, sbP(['PORTAL']), x, y, z, 40, 0.8, 0.8, 0.8, 0.5);
        } catch (e) { }

        // ③ 音效
        try { sbSound(world, loc, 'entity.dragon_fireball.explode', 1.2, 1.0); } catch (e) { }
        try { sbSound(world, loc, 'entity.ender_dragon.hurt', 0.6, 1.6); } catch (e) { }

        // ★★ 这里**故意不建 AreaEffectCloud** —— 用户要求「不会留下龙息团」
        return hit;
    } catch (e) { return 0; }
}

// ==================================================================
// 技能：弹道箭矢（三阶段）
// ==================================================================
function sbFireArrow(ref) {
    try {
        var boss = ref.ent;
        if (!sbAlive(boss)) return false;
        var tgt = sbNearestPlayer(ref);
        if (tgt == null) return false;
        var world = ref.lw;

        var from = null;
        try { from = boss.getEyeLocation(); } catch (e) { from = boss.getLocation(); }
        var to = tgt.getLocation();
        // ★ 稍微往上抬一点，让「弹道」有抛物线的味道
        var dir = sbVec(to.getX() - from.getX(),
                        (to.getY() + 1.0) - from.getY() + 0.35,
                        to.getZ() - from.getZ());
        if (dir == null || dir.lengthSquared() < 0.01) return false;
        dir.normalize();

        var arrow = null;
        try { arrow = world.spawnArrow(from, dir, SB_S3_ARROW_SPEED, SB_S3_ARROW_SPREAD); } catch (e) { arrow = null; }
        if (arrow == null) return false;

        try { arrow.setShooter(boss); } catch (e) { }
        try { arrow.setDamage(SB_S3_ARROW_DMG); } catch (e) { }     // 兜底，真正的定值在伤害事件里
        try { arrow.setKnockbackStrength(0); } catch (e) { }
        try { arrow.setCritical(false); } catch (e) { }
        // ★ 内部枚举要用 `$` 写法（`Java.type('X').Nested` 在 GraalJS 下取不到）
        try {
            var PS = Java.type('org.bukkit.entity.AbstractArrow$PickupStatus');
            if (PS != null && PS.DISALLOWED != null) arrow.setPickupStatus(PS.DISALLOWED);
        } catch (e) { }

        sbSetTagOf(arrow, SB_PROJ_KEY, 'arrow');
        sbSetTagOf(arrow, SB_OWN_KEY, ref.owner);

        try { sbSound(world, from, 'entity.arrow.shoot', 1.0, 1.2); } catch (e) { }
        try { sbSound(world, from, 'entity.enderman.ambient', 0.7, 1.4); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ==================================================================
// 小怪：暗影蠕虫（箭矢落点）/ 暗影蛆虫（女皇脚下）
// ==================================================================
function sbSpawnWorm(ref, loc) {
    try {
        if (ref == null || loc == null) return null;
        var world = ref.lw;
        var my = sbSafeY(world, loc.getX(), loc.getZ(), loc.getY(), 1);
        var at = sbLoc(world, loc.getX(), my, loc.getZ(), 0, 0);
        // ★ aware = false：它不追玩家，只被脚本驱赶着往女皇爬
        var w = sbSpawnMob(world, at, ['ENDERMITE'], SB_S3_WORM_NAME,
                           SB_S3_WORM_HP, 0, 'worm', false, ref.owner);
        if (w == null) return null;
        ref.worms['' + w.getUniqueId()] = w;
        try {
            var pt = sbP(['PORTAL', 'REVERSE_PORTAL', 'SMOKE_NORMAL']);
            sbFx(world, pt, at.getX(), at.getY() + 0.3, at.getZ(), 18, 0.3, 0.3, 0.3, 0.08);
        } catch (e) { }
        try { sbSound(world, at, 'entity.endermite.ambient', 0.8, 1.4); } catch (e) { }
        return w;
    } catch (e) { return null; }
}

function sbSpawnMaggotWave(ref) {
    try {
        var boss = ref.ent;
        if (!sbAlive(boss)) return 0;
        var world = ref.lw;
        var bl = boss.getLocation();
        var n = 0;
        for (var i = 0; i < SB_S3_MAGGOT_N; i++) {
            var ang = (i * 2.0 * Math.PI) / SB_S3_MAGGOT_N;
            var mx = bl.getX() + Math.cos(ang) * 1.2;
            var mz = bl.getZ() + Math.sin(ang) * 1.2;
            var my = sbSafeY(world, mx, mz, bl.getY(), 1);
            var at = sbLoc(world, mx, my, mz, 0, 0);
            var m = sbSpawnMob(world, at, ['ENDERMITE'], SB_S3_MAGGOT_NAME,
                               SB_S3_MAGGOT_HP, SB_S3_MAGGOT_DMG, 'maggot', true, ref.owner);
            if (m == null) continue;
            ref.maggots['' + m.getUniqueId()] = { e: m, dieAt: sbNow() + SB_S3_MAGGOT_LIFE_MS };
            n++;
        }
        if (n > 0) {
            try {
                var pt = sbP(['PORTAL', 'REVERSE_PORTAL', 'SMOKE_NORMAL']);
                sbFx(world, pt, bl.getX(), bl.getY() + 0.3, bl.getZ(), 40, 1.2, 0.3, 1.2, 0.12);
            } catch (e) { }
            try { sbSound(world, bl, 'entity.endermite.ambient', 1.0, 0.7); } catch (e) { }
        }
        return n;
    } catch (e) { return 0; }
}

// ★★★ 第八十三批：蛆虫死亡留下的「龙息团」= **原版末影龙喷出的那团龙息**
//   参数逐条抄 1.21.11 `DragonFireball#onHit`（见文件头常量处的对照表）。
//   ★★ 伤害**不是**这里筛的 —— 原版云靠 `INSTANT_DAMAGE` 结算，
//      它连女皇和她自己的蛆虫一起烤；「只对玩家造成伤害」由
//      监听器里的 `sbCloudGuard()` 兜住（受击者是我们的人 ⇒ 取消）。
function sbBreathCloud(world, loc, owner) {
    try {
        if (world == null || loc == null) return null;
        var cloud = null;
        try {
            var AEC = Java.type('org.bukkit.entity.AreaEffectCloud');
            cloud = world.spawn(loc, AEC);
        } catch (e) { cloud = null; }
        if (cloud == null) {
            // 拿不到云就退化成一次性粒子（不该发生，留个退路）
            sbBreathFx(world, loc.getX(), loc.getY() + 0.4, loc.getZ(), 40);
            return null;
        }

        // ★★ 给我们造的云打个记号 ⇒ sbCloudGuard() 靠它认出「这伤害是龙息团来的」
        try { sbSetTagOf(cloud, SB_CLOUD_KEY, '1'); } catch (e) { }

        // ② setOwner(owner) —— 原版只在 owner 是 LivingEntity 时才设
        try { if (owner != null) cloud.setSource(owner); } catch (e) { }

        // ③ setCustomParticle(DRAGON_BREATH.create(1.0f))
        var got = false;
        try {
            var pt = sbBreathParticle(world);
            if (pt != null) {
                if (SB_P_BREATH_DATA == null) {
                    // 探到 DRAGON_BREATH 的 data 是 Void ⇒ 不带 data 调用
                    try { cloud.setParticle(pt); got = true; } catch (e) { }
                } else {
                    // ★★ `Particle.DRAGON_BREATH` 的 data 类型是 **java.lang.Float**
                    //    （已从 paper-api 的 `Particle` 枚举构造器核对：
                    //      `new Particle("DRAGON_BREATH", 35, "dragon_breath", Float.class)`）
                    //    ⇒ 必须**显式**给一个 `java.lang.Float`：
                    //      给 JS 的 number 会被装成 `Double`，
                    //      而 `CraftParticle.createParticleParam` 里有
                    //          Preconditions.checkArgument(dataType.isInstance(data), ...)
                    //      一句，`Float.class.isInstance(Double)` = false ⇒ 直接抛异常，
                    //      结果就是云**没有粒子**（看着像一团空气）。
                    try {
                        var F = Java.type('java.lang.Float');
                        cloud.setParticle(pt, F.valueOf(SB_S3_CLOUD_POWER));
                        got = true;
                    } catch (e) {
                        try { cloud.setParticle(pt); got = true; } catch (e2) { }
                    }
                }
            }
        } catch (e) { }
        if (!got) { try { cloud.setParticle(sbPSafe()); } catch (e) { } }

        // ④⑤⑥ ★★ 本批第三条追加要求：「龙息的范围固定4，存在时间8秒」
        //   · 半径 **固定 4**（原版是 3 → 7 一路扩大）⇒ `radiusPerTick` 给 **0**，
        //     云**不再随时间变大**；
        //   · 存在 **8 秒 = 160 刻**（原版是 600 刻 = 30 秒）。
        try { cloud.setRadius(SB_S3_CLOUD_R); } catch (e) { }
        try { cloud.setDuration(SB_S3_CLOUD_TICKS); } catch (e) { }
        try { cloud.setRadiusPerTick(0.0); } catch (e) { }
        // ★ **故意不调 setWaitTime()** —— 原版那团云用的是默认值 20 刻（先蓄 1 秒）。
        //   之前脚本写死 setWaitTime(0)，那不是原版行为，本批已删。

        // ⑦ addEffect(new MobEffectInstance(INSTANT_DAMAGE, 1, 1))
        try {
            var PT = Java.type('org.bukkit.potion.PotionEffectType');
            var PE = Java.type('org.bukkit.potion.PotionEffect');
            var id = PT.INSTANT_DAMAGE;
            if (id != null) {
                cloud.addCustomEffect(new PE(id, 1, SB_S3_CLOUD_AMP, false, true, true), true);
            }
        } catch (e) { }

        try { sbSound(world, loc, 'entity.ender_dragon.ambient', 0.5, 1.7); } catch (e) { }
        return cloud;
    } catch (e) { return null; }
}

// ==================================================================
// 阶段切换演出
// ==================================================================
function sbTransFx(world, x, y, z, i, kind) {
    try {
        if (world == null) return;
        if (i >= SB_TRANS_TICKS) return;
        var frac = i / SB_TRANS_TICKS;
        if (kind === 1) {
            // 一阶段（末影螨）之死：末影粒子柱往上窜
            var pt = sbP(['PORTAL', 'REVERSE_PORTAL', 'SMOKE_NORMAL']);
            var top = 6.0 * frac;
            for (var h = 0; h <= top; h += 0.5) {
                sbFx(world, pt, x, y + h + 0.2, z, 2, 0.15, 0.02, 0.15, 0.4);
            }
            sbFx(world, pt, x, y + 0.3, z, 8, 0.6, 0.1, 0.6, 0.3);
            if (i === 1) { try { sbSound(world, sbLoc(world, x, y, z, 0, 0), 'entity.endermite.death', 1.0, 0.8); } catch (e) { } }
        } else {
            // 二阶段（末影人）之死：一圈反向传送门 + 龙息环
            var pt2 = sbP(['REVERSE_PORTAL', 'PORTAL', 'SMOKE_NORMAL']);
            var r = 1.0 + frac * 3.0;
            for (var a = 0; a < 12; a++) {
                var ang = a * (Math.PI / 6.0) + frac * 2.0;
                sbFx(world, pt2, x + Math.cos(ang) * r, y + 0.6 + frac * 1.5, z + Math.sin(ang) * r, 3, 0.1, 0.2, 0.1, 0.3);
            }
            sbBreathFx(world, x, y + 1.0 + frac * 2.0, z, 6);
            if (i === 1) { try { sbSound(world, sbLoc(world, x, y, z, 0, 0), 'entity.enderman.death', 1.2, 0.7); } catch (e) { } }
        }
        runLater(function () { sbTransFx(world, x, y, z, i + 1, kind); }, 1);
    } catch (e) { }
}

// ==================================================================
// 主循环
// ==================================================================
function sbTick(ref) {
    try {
        if (ref == null || ref.done) return;
        if (ref.transitioning) return;

        var boss = ref.ent;
        if (!sbAlive(boss)) { sbPhaseEnd(ref); return; }

        var now = sbNow();

        // ⓪ ★★ 第八十三批：记下「这一拍它在哪」——
        //    二 / 三阶段要生成在**前一个阶段的死亡位置**，而 boss 死透之后
        //    再 getLocation() 就不一定准了 ⇒ 每拍先存一份**活着的最后位置**兜底。
        //    （死亡事件里还会写一次**精确**的死亡坐标，那个优先。）
        try {
            var bl0 = boss.getLocation();
            ref.dx = bl0.getX(); ref.dy = bl0.getY(); ref.dz = bl0.getZ();
        } catch (e) { }

        // ① 血量兜底（出生后 SB_HP_FIX_MS 内每拍重试，直到确认写成功）
        try {
            if (!ref.hpOk && now < ref.hpFixUntil) {
                if (sbApplyHpOnce(boss, ref.maxHp)) ref.hpOk = true;
            }
        } catch (e) { }

        // ② 名字 / 血量条
        try {
            var cn = '';
            try { cn = '' + boss.getCustomName(); } catch (e) { cn = ''; }
            if (cn.indexOf(ref.name) < 0) boss.setCustomName(ref.name);
        } catch (e) { }
        try { sbBarSync(ref); } catch (e) { }

        var target = null;
        try { target = sbNearestPlayer(ref); } catch (e) { target = null; }

        // ③ 主动索敌（★ 末影人不会自己盯上玩家，必须每拍塞一次目标）
        try {
            var cur = null;
            try { cur = boss.getTarget(); } catch (e) { cur = null; }
            if (target != null) {
                var need = (cur == null);
                if (!need) { try { need = ('' + cur.getUniqueId()) !== ('' + target.getUniqueId()); } catch (e) { need = true; } }
                if (need) boss.setTarget(target);
            } else if (cur != null) {
                try { boss.setTarget(null); } catch (e) { }
            }
        } catch (e) { }

        // ④ 出场保护期内不放技能
        var canCast = (now >= ref.readyAt) && (target != null);
        // ★★★ 第八十三批：「瞬移」是**独立**的一条冷却 ——
        //   用户要求「别的技能可能有阶段开始时的 4 秒冷却，但是瞬移直接在
        //   阶段开始就使用一次」⇒ 它**不吃** SB_FIRST_MS 那 4 秒保护期，
        //   用自己的一份 teleReadyAt（阶段一开始就等于 now）。
        var canTele = (now >= ref.teleReadyAt) && (target != null);

        // ================= 一阶段：暗影巢虫 =================
        if (ref.stage === 1) {
            // 只有普攻（16 点）+ 命中时给玩家凋零 II 5 秒（在伤害事件里加）
            // 这里不做额外技能。
        }

        // ================= 二阶段：暗影猎手 =================
        else if (ref.stage === 2) {
            // · 每 8 秒一发追踪龙息弹（伤害 40、不留龙息团）
            if (canCast && now - ref.cdBreath >= SB_S2_BREATH_CD) {
                ref.cdBreath = now;
                if (sbFireBreath(ref)) { /* 冷却已记 */ }
            }
            // · 每 11 秒把玩家瞬移到身前（★ 独立冷却，开场就来一次）
            if (canTele && now - ref.cdTele >= SB_S2_TELE_CD) {
                ref.cdTele = now;
                sbPullToFront(ref, target);
            }
        }

        // ================= 三阶段：暗影女皇 =================
        else if (ref.stage === 3) {
            // · 每 7 秒把玩家瞬移到身前（★ 独立冷却，开场就来一次）
            if (canTele && now - ref.cdTele >= SB_S3_TELE_CD) {
                ref.cdTele = now;
                sbPullToFront(ref, target);
            }
            // · 每 10 秒一支弹道箭（落点生成「暗影蠕虫」）
            if (canCast && now - ref.cdArrow >= SB_S3_ARROW_CD) {
                ref.cdArrow = now;
                sbFireArrow(ref);
            }
            // · 每 15 秒脚下 4 只「暗影蛆虫」
            if (canCast && now - ref.cdMaggot >= SB_S3_MAGGOT_CD) {
                ref.cdMaggot = now;
                sbSpawnMaggotWave(ref);
            }
        }

        // ⑤ 三阶段：小怪维护
        if (ref.stage === 3) {
            // 「暗影蛆虫」到点自爆（死亡事件里会留下龙息团）
            try {
                for (var mk in ref.maggots) {
                    var mo = ref.maggots[mk];
                    if (mo == null) { delete ref.maggots[mk]; continue; }
                    var me = mo.e;
                    if (!sbAlive(me)) { delete ref.maggots[mk]; continue; }
                    if (now >= mo.dieAt) {
                        delete ref.maggots[mk];
                        sbHurt(null, me, 9999.0);      // ★ 走伤害管线 ⇒ 会触发死亡事件 ⇒ 留龙息团
                    }
                }
            } catch (e) { }

            // 「暗影蠕虫」爬向女皇，接触即死 + 给女皇回 77 血
            try {
                var bl = boss.getLocation();
                for (var wk in ref.worms) {
                    var w = ref.worms[wk];
                    if (w == null || !sbAlive(w)) { delete ref.worms[wk]; continue; }
                    var wl = null;
                    try { wl = w.getLocation(); } catch (e) { wl = null; }
                    if (wl == null) { delete ref.worms[wk]; continue; }
                    var dx = bl.getX() - wl.getX();
                    var dy = bl.getY() - wl.getY();
                    var dz = bl.getZ() - wl.getZ();
                    var flat = Math.sqrt(dx * dx + dz * dz);
                    if (flat <= SB_S3_WORM_TOUCH && Math.abs(dy) <= 2.0) {
                        // ★ 接触 ⇒ 死亡 + 女皇回血 77
                        delete ref.worms[wk];
                        sbHurt(null, w, 9999.0);
                        var healed = sbHeal(boss, SB_S3_WORM_HEAL);
                        try {
                            var pt = sbP(['HEART', 'VILLAGER_HAPPY', 'HAPPY_VILLAGER']);
                            sbFx(ref.lw, pt, bl.getX(), bl.getY() + 2.2, bl.getZ(), 10, 0.5, 0.4, 0.5, 0.0);
                        } catch (e) { }
                        try { sbSound(ref.lw, bl, 'entity.zombie_villager.cure', 1.0, 1.5); } catch (e) { }
                        continue;
                    }
                    if (flat > 0.01) {
                        var inv = 1.0 / flat;
                        var vy = 0.0;
                        if (dy > 0.6) vy = 0.22;                 // 女皇在上面就往上蹭一点
                        else if (dy < -0.6) vy = -0.22;
                        try {
                            w.setVelocity(sbVec(dx * inv * SB_S3_WORM_SPEED, vy, dz * inv * SB_S3_WORM_SPEED));
                        } catch (e) { }
                    }
                }
            } catch (e) { }
        }

        // ⑥ 继续
        runLater(function () { sbTick(ref); }, SB_TICK);
    } catch (e) {
        try {
            if (ref != null && sbAlive(ref.ent)) runLater(function () { sbTick(ref); }, SB_TICK);
            else sbCleanup(ref);
        } catch (e2) { }
    }
}

// ==================================================================
// 阶段结束 / 收摊
// ==================================================================
function sbPurgeMinions(ref) {
    try {
        if (ref == null) return;
        for (var k in ref.maggots) {
            try { var mo = ref.maggots[k]; if (mo != null && sbAlive(mo.e)) mo.e.remove(); } catch (e) { }
            try { delete ref.maggots[k]; } catch (e) { }
        }
        for (var k2 in ref.worms) {
            try { var w = ref.worms[k2]; if (w != null && sbAlive(w)) w.remove(); } catch (e) { }
            try { delete ref.worms[k2]; } catch (e) { }
        }
    } catch (e) { }
}

// ==================================================================
// ★★★ 第八十五批：掉一个 Slimefun 物品（用户：「第三阶段掉落物，固定掉落6个」）
//   ★ 写法照抄「妄念」那边的 btDropOne()（gs_boss_three.js）—— 那条路已经跑了很久，
//     取物品 / 落地 / 报错兜底都验证过了，不再另起一套。
//   ★ `getSfItemById` 是 RSC 注入的全局函数（本仓库 6 个脚本都在用）。
//   ★ 取不到物品时**只往后台打一行日志**，不影响收摊流程。
// ==================================================================
function sbDropOne(world, x, y, z, id, amount) {
    try {
        if (world == null || id == null) return false;
        if (x == null || z == null) return false;

        var stack = null;
        try {
            var sf = getSfItemById(id);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }
        if (stack == null) {
            try { getServer().getLogger().warning('[构思技艺] 暗影虫卵：取不到 ' + id + '，未掉落'); } catch (e) { }
            return false;
        }
        try { stack.setAmount(amount == null || amount <= 0 ? 1 : amount); } catch (e) { }

        var Loc = Java.type('org.bukkit.Location');
        world.dropItemNaturally(new Loc(world, x, (y == null ? 0 : y) + 0.5, z), stack);
        return true;
    } catch (e) { return false; }
}

// ★ 第三阶段（暗影女皇）死掉 ⇒ 原地掉 SB_DROP_ORB_N 个「暗影宝珠」
function sbDropOrbs(world, x, y, z) {
    try {
        var yy = (y == null ? 0 : y);
        if (!sbDropOne(world, x, yy, z, SB_DROP_ORB_ID, SB_DROP_ORB_N)) return;
        try {
            var Loc = Java.type('org.bukkit.Location');
            sbSound(world, new Loc(world, x, yy, z), 'entity.item.pickup', 0.8, 1.2);
        } catch (e) { }
    } catch (e) { }
}

function sbPhaseEnd(ref) {
    try {
        if (ref == null || ref.transitioning) return;
        ref.transitioning = true;
        sbBarDrop(ref);

        // 正在飞的龙息弹作废（世代号让旧链停手）
        try { ref.breathGen = (ref.breathGen == null ? 0 : ref.breathGen) + 1; } catch (e) { }
        try { if (ref.breath != null) { ref.breath.remove(); ref.breath = null; } } catch (e) { }
        // 本阶段的小怪一并清掉（换阶段不该留着上一阶段的兵）
        try { sbPurgeMinions(ref); } catch (e) { }

        var stage = ref.stage;
        var world = ref.lw;

        // ★★★ 第八十三批：二 / 三阶段生成在**前一个阶段的死亡位置**（用户要求）。
        //   优先级：① 死亡事件写下的**精确**死亡坐标（ref.dx/dy/dz）
        //           ② 主循环每拍记下的**活着的最后位置**（也写进同一组字段）
        //           ③ 都没有（比如 boss 被 remove 掉、一帧都没跑）⇒ 退回召唤点
        var x, y, z;
        if (ref.dx != null && ref.dy != null && ref.dz != null) {
            x = ref.dx; y = ref.dy; z = ref.dz;
        } else {
            x = (ref.lx == null ? 0 : ref.lx);
            y = (ref.ly == null ? 64 : ref.ly);
            z = (ref.lz == null ? 0 : ref.lz);
        }

        // ★★★ 第八十五批 / 第八十六批：掉落物
        //   用户原话：「暗影虫卵第三阶段掉落物，固定掉落6个」
        //             「暗影虫卵二阶段固定掉落一把致万千无言者，删除配方，
        //               改成击败二阶段暗影掉落」
        //   ⇒ 二阶段（暗影猎手）死掉 ⇒ 掉 **1 把「致万千无言者」**；
        //     三阶段（暗影女皇）死掉 ⇒ 掉 **6 个「暗影宝珠」**；
        //     一阶段（暗影巢虫）死掉 ⇒ **什么都不掉**。
        //   ★ 用上面算好的 (x, y, z) —— 那就是**死亡位置**
        //     （优先取死亡事件写的 ref.dx/dy/dz，其次是主循环记的最后位置）。
        //   ★ 原版掉落已经在死亡事件里清干净了（装备 / 背包 / 战利品 / 经验），
        //     这里掉的是**我们自己的**物品。
        if (stage === 2) {
            try { sbDropOne(world, x, y, z, SB_DROP_VOICE_ID, SB_DROP_VOICE_N); } catch (e) { }
        }
        if (stage >= 3) {
            try { sbDropOrbs(world, x, y, z); } catch (e) { }
        }

        if (stage >= 3) {
            // 三阶段全部打完 ⇒ 收摊
            sbCleanup(ref);
            return;
        }

        var kind = (stage === 1 ? 1 : 2);
        sbTransFx(world, x, y, z, 0, kind);
        runLater(function () {
            try { sbNext(ref, stage + 1, world, x, y, z); } catch (e) { }
        }, SB_TRANS_TICKS);
    } catch (e) {
        sbCleanup(ref);
    }
}

// 生成下一阶段
function sbNext(ref, stage, world, x, y, z) {
    try {
        if (ref == null || ref.done) return;
        if (world == null) { sbCleanup(ref); return; }

        var height = (stage === 1 ? 1 : 3);     // 末影螨 1 格、末影人 3 格
        var sy = sbSafeY(world, x, z, y, height);
        var loc = sbLoc(world, x, sy, z, 0, 0);

        var name, hp, dmg, tag;
        if (stage === 1) { name = SB_S1_NAME; hp = SB_S1_HP; dmg = SB_S1_DMG; tag = 'boss1'; }
        else if (stage === 2) { name = SB_S2_NAME; hp = SB_S2_HP; dmg = SB_S2_DMG; tag = 'boss2'; }
        else { name = SB_S3_NAME; hp = SB_S3_HP; dmg = SB_S3_DMG; tag = 'boss3'; }

        var typeNames = (stage === 1 ? ['ENDERMITE'] : ['ENDERMAN']);
        var ent = sbSpawnMob(world, loc, typeNames, name, hp, dmg, tag, true, ref.owner);
        if (ent == null) { sbCleanup(ref); return; }

        // 末影人：不许搬方块（由 EntityChangeBlockEvent 拦截）、不许乱传送（EntityTeleportEvent）
        try { ent.setCanPickupItems(false); } catch (e) { }
        try { ent.setScreaming(false); } catch (e) { }

        ref.stage = stage;
        ref.ent = ent;
        ref.name = name;
        ref.maxHp = hp;
        ref.transitioning = false;
        ref.hpOk = false;
        ref.hpFixUntil = sbNow() + SB_HP_FIX_MS;

        var now = sbNow();
        var teleCd = (stage === 3 ? SB_S3_TELE_CD : SB_S2_TELE_CD);
        // ★ 其它技能：出场保护期 SB_FIRST_MS（4 秒）照旧
        ref.readyAt  = now + SB_FIRST_MS;
        ref.cdBreath = now - SB_S2_BREATH_CD + SB_FIRST_MS;
        ref.cdArrow  = now - SB_S3_ARROW_CD + SB_FIRST_MS;
        ref.cdMaggot = now - SB_S3_MAGGOT_CD + SB_FIRST_MS;
        // ★★★ 第八十三批：「瞬移」**单独算冷却**（用户要求）——
        //   · teleReadyAt 不含那 4 秒 ⇒ 阶段一开始就可用；
        //   · cdTele 先拨到「刚好走完」⇒ 下一拍判定立刻成立 ⇒ **开场先瞬移一次**；
        //   · 之后再按 7 秒 / 11 秒自己走，和其它技能的冷却互不影响。
        ref.teleReadyAt = now;
        ref.cdTele      = now - teleCd;

        ref.bar = sbBarMake(sbBarTitle(stage));
        try { sbBarSync(ref); } catch (e) { }

        // 出场特效
        try {
            var pt = sbP(['REVERSE_PORTAL', 'PORTAL', 'SMOKE_NORMAL']);
            sbFx(world, pt, x, sy + 1.0, z, 40, 0.6, 0.9, 0.6, 0.25);
        } catch (e) { }
        try { sbSound(world, loc, 'entity.enderman.teleport', 1.2, 0.7); } catch (e) { }
        try { sbSound(world, loc, 'entity.wither.spawn', 0.5, 1.6); } catch (e) { }
        try { sbSound(world, loc, 'entity.ender_dragon.growl', 0.8, 1.4); } catch (e) { }

        runLater(function () { sbTick(ref); }, SB_TICK);
    } catch (e) {
        sbCleanup(ref);
    }
}

function sbBarTitle(stage) {
    if (stage === 1) return '\u6697\u5f71\u5de2\u866b';      // 暗影巢虫
    if (stage === 2) return '\u6697\u5f71\u730e\u624b';      // 暗影猎手
    return '\u6697\u5f71\u5973\u7687';                        // 暗影女皇
}

function sbCleanup(ref) {
    try {
        if (ref == null || ref.done) return;
        ref.done = true;
        ref.transitioning = false;
        try { sbBarDrop(ref); } catch (e) { }
        try { ref.breathGen = (ref.breathGen == null ? 0 : ref.breathGen) + 1; } catch (e) { }
        try { if (ref.breath != null) { ref.breath.remove(); ref.breath = null; } } catch (e) { }
        try { sbPurgeMinions(ref); } catch (e) { }

        // 万一还留着残兵
        try {
            if (sbAlive(ref.ent)) {
                try { ref.ent.getEquipment().clear(); } catch (e) { }
                try { ref.ent.remove(); } catch (e) { }
            }
        } catch (e) { }
        ref.ent = null;

        // 解锁「这个玩家还能再召唤」
        try {
            if (ref.owner != null && SB_ACTIVE['' + ref.owner] === ref) delete SB_ACTIVE['' + ref.owner];
        } catch (e) { }
    } catch (e) { }
}

// ==================================================================
// 召唤入口
// ==================================================================
function sbSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();

        // 这条线上还有没打完的 → 静默忽略（三阶段全死才能再召）
        var old = SB_ACTIVE[uuid];
        if (old != null && !old.done) return false;

        var world = p.getWorld();
        var base = p.getLocation();
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        var lx = base.getX() + (-Math.sin(rad)) * SB_SPAWN_AHEAD;
        var lz = base.getZ() + (Math.cos(rad)) * SB_SPAWN_AHEAD;
        var ly = sbSafeY(world, lx, lz, base.getY(), 1);
        var loc = sbLoc(world, lx, ly, lz, yaw, 0.0);

        var now = sbNow();
        var ref = {
            owner: uuid,
            stage: 1,
            ent: null,
            name: SB_S1_NAME,
            maxHp: SB_S1_HP,
            bar: null, barNear: {},
            breath: null, breathGen: 0,
            worms: {}, maggots: {},
            cdBreath: now - SB_S2_BREATH_CD + SB_FIRST_MS,
            cdTele:   now - SB_S2_TELE_CD,
            cdArrow:  now - SB_S3_ARROW_CD + SB_FIRST_MS,
            cdMaggot: now - SB_S3_MAGGOT_CD + SB_FIRST_MS,
            readyAt:  now + SB_FIRST_MS,
            // ★★ 第八十三批：瞬移的**独立**门闸 —— 一阶段用不上（巢虫不会瞬移），
            //    但字段先给全，免得 sbTick 里读到 undefined
            teleReadyAt: now,
            // ★★ 第八十三批：死亡位置（死亡事件写精确值，主循环每拍写兜底值）
            dx: null, dy: null, dz: null,
            hpFixUntil: now + SB_HP_FIX_MS,
            hpOk: false,
            transitioning: false,
            done: false,
            lw: world, lx: lx, ly: ly, lz: lz
        };

        var ent = sbSpawnMob(world, loc, ['ENDERMITE'], SB_S1_NAME,
                             SB_S1_HP, SB_S1_DMG, 'boss1', true, uuid);
        if (ent == null) return false;
        ref.ent = ent;

        ref.bar = sbBarMake(sbBarTitle(1));
        try { sbBarSync(ref); } catch (e) { }

        SB_ACTIVE[uuid] = ref;

        try { sbSound(world, loc, 'entity.endermite.ambient', 1.0, 0.8); } catch (e) { }
        try { sbSound(world, loc, 'entity.wither.spawn', 0.5, 1.5); } catch (e) { }
        try { sbSound(world, loc, 'entity.ender_dragon.growl', 0.7, 1.5); } catch (e) { }
        try {
            var pt = sbP(['REVERSE_PORTAL', 'PORTAL', 'SMOKE_NORMAL']);
            sbFx(world, pt, lx, ly + 0.5, lz, 40, 0.6, 0.5, 0.6, 0.2);
        } catch (e) { }

        runLater(function () { sbTick(ref); }, SB_TICK);
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 暗影虫卵：召唤出错 ' + e); } catch (e2) { }
        return false;
    }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function sbIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === SB_ITEM_ID) return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === SB_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

function sbIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return sbIsOurItem(hand);
    } catch (e) { return false; }
}

function sbConsume(p) {
    try {
        var inv = p.getInventory();
        var hand = inv.getItemInMainHand();
        if (hand == null) return;
        var amt = 0;
        try { amt = hand.getAmount(); } catch (e) { amt = 0; }
        if (amt > 1) hand.setAmount(amt - 1);
        else inv.setItemInMainHand(null);
    } catch (e) { }
}

// ==================================================================
// 监听器
// ==================================================================
var SB_REG_DEATH  = false;
var SB_REG_SPELL  = false;
var SB_REG_GUARD  = false;

function sbPlugin() {
    try {
        var pm = getServer().getPluginManager();
        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        return plugin;
    } catch (e) { return null; }
}

// ★★★ 第八十三批：「龙息只对玩家造成伤害，不会对 boss 以及对应小怪造成伤害」
//
//   为什么需要这道闸：
//     · 蛆虫死后的龙息团是**原版那团云**，伤害靠 `INSTANT_DAMAGE` 结算；
//     · 反编译 1.21.11 `AreaEffectCloud#serverTick`：
//           effect.applyInstantenousEffect(this, this.getOwner(), entity, amp, 1.0)
//       即 `source = 云自己`、`indirectSource = 云的主人`；
//       再往下 `InstantaneousMobEffect#applyInstantenousEffect` 用的是
//           damageSources().indirectMagic(source, indirectSource)
//       ⇒ DamageSource 的 **directEntity = 云**、**causingEntity = 云的主人**；
//     · 原版云**不认阵营** ⇒ 女皇和她自己的蛆虫站在云里也会被自己的龙息烤。
//
//   闸门逻辑（**只保护我们自己人**，玩家那边一点都不受影响）：
//     · 受击者不在我们这条线上 ⇒ 直接放过；
//     · 伤害的「直接来源」是我们打过记号的龙息团（`gs:shadowboss_cloud`）⇒ 取消；
//     · 兜底：伤害的「元凶」（causingEntity）是这条线上的人（= 云的主人）⇒ 取消。
//   ★ 顺带把「自己人误伤自己人」也一起挡了（boss 的普攻 / 我们自己射的箭）。
function sbCloudGuard(e) {
    try {
        if (e == null) return false;
        var vic = null;
        try { vic = e.getEntity(); } catch (err) { vic = null; }
        if (vic == null) return false;
        if (!sbOurs(vic)) return false;          // ★ 只保护我们自己人

        var ds = null;
        try { ds = e.getDamageSource(); } catch (err) { ds = null; }
        if (ds == null) return false;

        var de = null, ce = null;
        try { de = ds.getDirectEntity(); } catch (err) { de = null; }
        try { ce = ds.getCausingEntity(); } catch (err) { ce = null; }

        // ① 伤害的「直接来源」就是我们造的那团龙息
        if (de != null && sbCloudTag(de).length > 0) { e.setCancelled(true); return true; }
        // ② 兜底：伤害的「元凶」是这条线上的人（云的主人 = 本阶段的 boss）
        if (ce != null && sbOurs(ce)) { e.setCancelled(true); return true; }
        return false;
    } catch (err) { return false; }
}

// ① 清掉落：所有阶段 + 小怪的原版掉落一律清空
function sbRegisterDeath() {
    if (SB_REG_DEATH) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = sbPlugin();
        if (plugin == null) return false;

        var DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent');
        if (DeathEv == null) return false;

        function dh(e) {
            try {
                var ent = e.getEntity();
                if (ent == null) return;
                if (!sbOurs(ent)) return;

                var tag = sbTag(ent);
                var loc = null;
                try { loc = ent.getLocation(); } catch (err) { loc = null; }

                // ★ 原版掉落全部删除：装备 + 背包 + 战利品 + 经验
                try { ent.getEquipment().clear(); } catch (err) { }
                try { var inv = ent.getInventory(); if (inv != null) inv.clear(); } catch (err) { }
                try { e.getDrops().clear(); } catch (err) { }
                try { e.getDrops().clear(); } catch (err) { }   // 清完装备再清一遍
                try { e.setDroppedExp(0); } catch (err) { }

                // ★★ 第八十三批：boss 死了 ⇒ 把**死亡位置**记进这条线，
                //    下一阶段就生成在这儿（用户：「二阶段三阶段改成在
                //    前一个阶段的死亡位置生成」）。
                //    ★ 死亡事件是最准的时机 —— sbTick 每 5 刻才跑一拍，
                //      等它发现"人没了"时已经过了最多 0.25 秒。
                if (tag === 'boss1' || tag === 'boss2' || tag === 'boss3') {
                    try {
                        var r0 = sbRefOf(ent);
                        if (r0 != null && loc != null) {
                            r0.dx = loc.getX(); r0.dy = loc.getY(); r0.dz = loc.getZ();
                        }
                    } catch (err) { }
                }

                // ★ 「暗影蛆虫」死亡 ⇒ 死亡位置留下**原版那团龙息**
                if (tag === 'maggot' && loc != null) {
                    try {
                        var r1 = sbRefOf(ent);
                        var own = (r1 == null ? null : r1.ent);   // 云的主人 = 这条线的 boss
                        sbBreathCloud(loc.getWorld(), loc, own);
                    } catch (err) { }
                }
                // 「暗影蠕虫」死亡不留云（用户只要求蛆虫留）
            } catch (err) { }
        }

        try {
            pm.registerEvent(DeathEv, plugin, Priority.HIGHEST,
                function (l, e) { dh(e); }, plugin);
            SB_REG_DEATH = true;
            return true;
        } catch (e1) {
            try { getServer().getLogger().warning('[构思技艺] 暗影虫卵：死亡掉落拦截注册失败 ' + e1); } catch (e3) { }
            return false;
        }
    } catch (e) { return false; }
}

// ② 反制原版末影人行为 + 弹道命中 + 伤害定值 + 阵营保护
function sbRegisterSpell() {
    if (SB_REG_SPELL) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = sbPlugin();
        if (plugin == null) return false;

        var ok = 0;

        // ②-1 ★★ 「不吸引末影人仇恨」：目标是我们的末影螨 ⇒ 取消锁定
        //      （原版 Enderman 无条件 NearestAttackableTargetGoal<Endermite>）
        try {
            var TargetEv = Java.type('org.bukkit.event.entity.EntityTargetLivingEntityEvent');
            var TargetEv2 = Java.type('org.bukkit.event.entity.EntityTargetEvent');
            if (TargetEv != null) {
                function tgh(e) {
                    try {
                        var tgt = null;
                        try { tgt = e.getTarget(); } catch (err) { tgt = null; }
                        if (tgt == null) return;
                        if (!sbIsOurMite(tgt)) return;
                        try { e.setCancelled(true); } catch (err) { }
                        try { e.setTarget(null); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(TargetEv, plugin, Priority.HIGHEST, function (l, e) { tgh(e); }, plugin);
                    ok++;
                } catch (e1) { }
                if (TargetEv2 != null && TargetEv2 !== TargetEv) {
                    try {
                        pm.registerEvent(TargetEv2, plugin, Priority.HIGHEST, function (l, e) { tgh(e); }, plugin);
                        ok++;
                    } catch (e1) { }
                }
            }
        } catch (e) { }

        // ②-2 ★★ 末影人 boss **不许乱传送**（挨打 / 沾水 / 追击时的 randomTeleport 全部掐掉）
        try {
            var TeleEv = Java.type('org.bukkit.event.entity.EntityTeleportEvent');
            if (TeleEv != null) {
                function teh(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        var tag = sbTag(ent);
                        if (tag !== 'boss2' && tag !== 'boss3') return;
                        try { e.setCancelled(true); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(TeleEv, plugin, Priority.HIGHEST, function (l, e) { teh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-3 ★★ 末影人 boss **不许搬方块 / 放方块**（EndermanTakeBlockGoal / LeaveBlockGoal）
        try {
            var ChgEv = Java.type('org.bukkit.event.entity.EntityChangeBlockEvent');
            if (ChgEv != null) {
                function chg(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        if (!sbOurs(ent)) return;
                        try { e.setCancelled(true); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(ChgEv, plugin, Priority.HIGHEST, function (l, e) { chg(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-4 ★★ 弹道命中：
        //   · 'breath' → **取消事件** ⇒ NMS 的 onHit 整个不跑 ⇒ 不留龙息团、无原版伤害，
        //                伤害 40 由 sbBreathBoom 自己结算
        //   · 'arrow'  → 不取消（箭照常扎上去），只在**落点**生成「暗影蠕虫」
        try {
            var HitEv = Java.type('org.bukkit.event.entity.ProjectileHitEvent');
            if (HitEv != null) {
                function hit(e) {
                    try {
                        var proj = null;
                        try { proj = e.getEntity(); } catch (err) { proj = null; }
                        if (proj == null) return;
                        var tag = sbProjTag(proj);
                        if (tag.length === 0) return;

                        var ref = sbRefOf(proj);

                        if (tag === 'breath') {
                            try { e.setCancelled(true); } catch (err) { }   // ★★ 关键一行
                            var at = null;
                            try {
                                var hb = e.getHitBlock();
                                if (hb != null) at = hb.getLocation().add(0.5, 0.5, 0.5);
                            } catch (err) { at = null; }
                            if (at == null) {
                                try {
                                    var he = e.getHitEntity();
                                    if (he != null) at = he.getLocation();
                                } catch (err) { at = null; }
                            }
                            if (at == null) { try { at = proj.getLocation(); } catch (err) { at = null; } }
                            try { if (at != null) sbBreathBoom(ref, at); } catch (err) { }
                            try { proj.remove(); } catch (err) { }
                            try { if (ref != null && ref.breath === proj) ref.breath = null; } catch (err) { }
                            return;
                        }

                        if (tag === 'arrow') {
                            var at2 = null;
                            try {
                                var he2 = e.getHitEntity();
                                if (he2 != null) at2 = he2.getLocation();
                            } catch (err) { at2 = null; }
                            if (at2 == null) {
                                try {
                                    var hb2 = e.getHitBlock();
                                    if (hb2 != null) at2 = hb2.getLocation().add(0.5, 1.0, 0.5);
                                } catch (err) { at2 = null; }
                            }
                            if (at2 == null) { try { at2 = proj.getLocation(); } catch (err) { at2 = null; } }
                            // ★ 只有三阶段的箭才生虫
                            try {
                                if (ref != null && ref.stage === 3 && !ref.done && at2 != null) {
                                    sbSpawnWorm(ref, at2);
                                }
                            } catch (err) { }
                            try { proj.remove(); } catch (err) { }   // 箭插地上就收掉，别留一地箭
                            return;
                        }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(HitEv, plugin, Priority.HIGHEST, function (l, e) { hit(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-5 ★ 伤害定值 / 凋零：
        //   · 'arrow'（我们射的箭）→ 伤害**固定 20**
        //   · 'boss1'（暗影巢虫）打到玩家 → 玩家获得**凋零 II 5 秒**
        try {
            var DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent');
            if (DmgEv != null) {
                function dmgh(e) {
                    try {
                        var dam = null;
                        try { dam = e.getDamager(); } catch (err) { dam = null; }
                        if (dam == null) return;

                        var ptag = sbProjTag(dam);
                        if (ptag === 'arrow') {
                            try { e.setDamage(SB_S3_ARROW_DMG); } catch (err) { }
                            return;
                        }

                        var tag = sbTag(dam);
                        if (tag !== 'boss1') return;

                        var vic = null;
                        try { vic = e.getEntity(); } catch (err) { vic = null; }
                        if (vic == null) return;
                        try { if (('' + vic.getType().name()) !== 'PLAYER') return; } catch (err) { return; }

                        // ★ 每次攻击玩家 ⇒ 凋零 II 5 秒
                        try {
                            var PT = Java.type('org.bukkit.potion.PotionEffectType');
                            var PE = Java.type('org.bukkit.potion.PotionEffect');
                            var wt = PT.WITHER;
                            if (wt != null) {
                                vic.addPotionEffect(new PE(wt, SB_S1_WITHER_TICKS, SB_S1_WITHER_AMP, false, true, true));
                            }
                        } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DmgEv, plugin, Priority.HIGHEST, function (l, e) { dmgh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-6 ★ 兜底：我们的弹体哪怕真的炸了，也不许动地形
        try {
            var ExplEv = Java.type('org.bukkit.event.entity.EntityExplodeEvent');
            if (ExplEv != null) {
                function exh(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        if (!sbOurs(ent)) return;
                        try { e.setCancelled(true); } catch (err) { }
                        try { e.blockList().clear(); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(ExplEv, plugin, Priority.HIGHEST, function (l, e) { exh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-7 ★★★ 第八十三批：**龙息团只对玩家造成伤害**
        //   （原版那团云六亲不认，会连女皇和她自己的蛆虫一起烤 ⇒ 这里挡住）
        //   ★ 只注册基类 `EntityDamageEvent` 就够了 ——
        //     反编译核对过：`EntityDamageByEntityEvent` **没有**自己的 HandlerList，
        //     它继承的就是 `EntityDamageEvent` 那一份 ⇒ 一份注册两条路都收到。
        try {
            var DmgEv0 = Java.type('org.bukkit.event.entity.EntityDamageEvent');
            if (DmgEv0 != null) {
                try {
                    pm.registerEvent(DmgEv0, plugin, Priority.HIGHEST,
                        function (l, e) { sbCloudGuard(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        SB_REG_SPELL = (ok > 0);
        if (SB_REG_SPELL) {
            try { getServer().getLogger().info('[构思技艺] 暗影虫卵：反制 / 弹道 / 伤害拦截 已启用（' + ok + ' 条）'); } catch (e) { }
        }
        return SB_REG_SPELL;
    } catch (e) { return false; }
}

// ③ 手持本物品右键方块 → 方块交互一律取消
function sbRegisterGuard() {
    if (SB_REG_GUARD) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = sbPlugin();
        if (plugin == null) return false;

        var InteractEv = Java.type('org.bukkit.event.player.PlayerInteractEvent');
        if (InteractEv == null) return false;

        function handler(e) {
            try {
                var act = '';
                try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                if (act.indexOf('RIGHT_CLICK') < 0) return;
                var hs = '';
                try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;
                var h = null;
                try { h = e.getItem(); } catch (e2) { h = null; }
                if (h == null) return;
                if (!sbIsOurItem(h)) return;
                e.setCancelled(true);
            } catch (err) { }
        }

        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST, function (l, e) { handler(e); }, plugin);
            SB_REG_GUARD = true;
            return true;
        } catch (e1) { return false; }
    } catch (e) { return false; }
}

// ==================================================================
// 入口
// ==================================================================
function onUse(event) {
    try {
        try { sbRegisterDeath(); } catch (e) { }    // 惰性重试
        try { sbRegisterSpell(); } catch (e) { }    // 惰性重试
        try { sbRegisterGuard(); } catch (e) { }    // 惰性重试

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        if (!sbIsMainHandUse(event, p)) return;

        var uuid = '' + p.getUniqueId();
        var now = sbNow();
        var last = SB_LAST[uuid];
        if (last != null && (now - (0 + last)) < 500) return;
        SB_LAST[uuid] = now;

        if (!sbSummon(p)) return;
        if (SB_CONSUME) sbConsume(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 暗影虫卵 onUse 出错: ' + err); } catch (e) { }
    }
}

// ==================================================================
// ★★ 启动自检 —— 把参数打到后台，用来确认"服务器确实加载了这份新脚本"
//    （看不到这几行 = 跑的还是旧脚本 / 压根没加载，要重启服务器）
// ==================================================================
try {
    getServer().getLogger().info('[构思技艺] 暗影虫卵 三阶段目标血量：暗影巢虫=' + SB_S1_HP
        + '（末影螨·凋零II 5秒·伤害 ' + SB_S1_DMG + '） 暗影猎手=' + SB_S2_HP
        + '（末影人·伤害 ' + SB_S2_DMG + '） 暗影女皇=' + SB_S3_HP
        + '（末影人·伤害 ' + SB_S3_DMG + '）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 暗影猎手 追踪龙息弹：每 ' + (SB_S2_BREATH_CD / 1000)
        + '秒 伤害=' + SB_S2_BREATH_DMG + ' 半径=' + SB_S2_BREATH_R
        + ' **不留龙息团**（取消 ProjectileHitEvent ⇒ 原版 onHit 不跑）'
        + ' ｜ 瞬移玩家：每 ' + (SB_S2_TELE_CD / 1000) + '秒（**独立冷却**，阶段开场先来一次）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 暗影女皇：瞬移玩家每 ' + (SB_S3_TELE_CD / 1000)
        + '秒（**独立冷却**，阶段开场先来一次） ｜ 弹道箭每 ' + (SB_S3_ARROW_CD / 1000)
        + '秒 伤害=' + SB_S3_ARROW_DMG
        + ' ⇒ 落点生成「暗影蠕虫」血=' + SB_S3_WORM_HP + ' 接触回血=' + SB_S3_WORM_HEAL
        + ' ｜ 蛆虫每 ' + (SB_S3_MAGGOT_CD / 1000) + '秒 ' + SB_S3_MAGGOT_N
        + '只 血=' + SB_S3_MAGGOT_HP + ' 伤害=' + SB_S3_MAGGOT_DMG
        + ' 存活=' + (SB_S3_MAGGOT_LIFE_MS / 1000) + '秒');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 暗影虫卵 阶段推进：二/三阶段生成在**前一阶段的死亡位置**'
        + ' ｜ 蛆虫死亡留**原版末影龙那团龙息**：半径固定 ' + SB_S3_CLOUD_R
        + ' · 存在 ' + (SB_S3_CLOUD_TICKS / 20)
        + '秒 · INSTANT_DAMAGE 等级 ' + SB_S3_CLOUD_AMP
        + '（= ' + (6 << SB_S3_CLOUD_AMP) + ' 点）**只对玩家造成伤害**');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 暗影虫卵 反制：末影人不索敌我们的末影螨 / '
        + 'boss 不乱传送 / boss 不搬方块 / 弹体不炸地形 / **龙息不伤自己人**');
} catch (e) { }

// ★★★ 第八十五批 / 第八十六批：掉落自检
//   （用户「暗影虫卵第三阶段掉落物，固定掉落6个」
//     「暗影虫卵二阶段固定掉落一把致万千无言者，删除配方，改成击败二阶段暗影掉落」）
try {
    getServer().getLogger().info('[构思技艺] 暗影虫卵 掉落：**二阶段（暗影猎手）**掉 '
        + SB_DROP_VOICE_N + ' 把「致万千无言者」(' + SB_DROP_VOICE_ID + ')'
        + ' ｜ **三阶段（暗影女皇）**掉 ' + SB_DROP_ORB_N + ' 个「暗影宝珠」(' + SB_DROP_ORB_ID + ')'
        + ' ｜ 一阶段不掉 ｜ 掉落点 = 各阶段的**死亡位置**');
} catch (e) { }

// ==================================================================
// ★ reload / 重启残留清扫：启动 3 秒后扫一遍全世界，把上一份脚本留下的
//   「我们这条线」的实体（PDC gs:shadowboss / gs:shadowboss_proj）清掉 ——
//   重启之后 ref 表是空的，留着它们只会变成没人管的孤儿。
//   ★★ 但**当前 ref 表里还活着的线**必须放过：否则「刚开服 3 秒内召唤的
//      boss」会被这把扫帚一起扫掉（第八十二批的逻辑测试就是这么抓出来的）。
// ==================================================================
function sbSweepLeftovers() {
    try {
        var worlds = getServer().getWorlds();
        var it = worlds.iterator();
        var n = 0;
        while (it.hasNext()) {
            var w = it.next();
            if (w == null) continue;
            // ★ 先快照成 JS 数组再删（边遍历边 remove 会 ConcurrentModificationException）
            var arr = [];
            try {
                var list = w.getEntities();
                var i2 = list.iterator();
                while (i2.hasNext()) arr.push(i2.next());
            } catch (e) { continue; }
            for (var i = 0; i < arr.length; i++) {
                var ent = arr[i];
                try {
                    if (ent == null) continue;
                    if (!sbOurs(ent)) continue;
                    // ★★ 正在被管理的线**别动** —— 否则「刚开服 3 秒内召唤的 boss」
                    //    会被这把扫帚顺手扫掉（测试桩就是这么抓出来的）。
                    var own = sbOwnerOf(ent);
                    if (own != null && own.length > 0 && SB_ACTIVE[own] != null) continue;
                    ent.remove();
                    n++;
                } catch (e) { }
            }
        }
        if (n > 0) {
            try { getServer().getLogger().info('[构思技艺] 暗影虫卵：清掉上一份脚本残留实体 ' + n + ' 个'); } catch (e) { }
        }
    } catch (e) { }
}

try {
    runLater(function () { try { sbSweepLeftovers(); } catch (e) { } }, 60);
} catch (e) { }
