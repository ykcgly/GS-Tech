/* ==========================================================================
 * 构思技艺 (GS) —— 两阶段 boss「守夜人的提灯」(GS_NIGHT_LANTERN)   ★ 第八十六批新增
 * --------------------------------------------------------------------------
 * 物品：items.yml 的 `GS_NIGHT_LANTERN`（item_group: gs_boss / display_tier: 4）
 *       材质 = SOUL_LANTERN，lore = 「摇摇晃晃，眼显微光」
 *       配方 = 万象归序（37 个梦境规则 + 16 个灵魂沙，15 秒）
 * 召唤：手持本物品 **右键**（消耗 1 个，`NW_CONSUME = true`）
 * --------------------------------------------------------------------------
 * ★★ 用户原话（第八十六批，逐字）：
 *   「新增boss，名字：守夜人的提灯，材质：灵魂灯笼，
 *     lore：摇摇晃晃，眼显微光  配方：37个梦境规则+16个灵魂沙放在暗影虫卵后面一格」
 *
 *   一阶段 —— 洞穴蜘蛛「巡夜守卫」
 *     · 删除原版掉落
 *     · 血量 450
 *     · 基础伤害 17
 *     · 冷却 7 秒：弹道箭矢，命中玩家造成 中毒 II / 缓慢 III / 失明 各 3 秒
 *     · 冷却 9 秒：白色粒子飞天（约 0.5 秒 20 格），飞到 20 格后直接出现在玩家背后
 *   二阶段 —— 坚守者「守夜人」
 *     · 删除原版掉落
 *     · 血量 900
 *     · 基础伤害 44
 *     · **只对最近的玩家有仇恨**
 *     · **取消原版冲击波**（SonicBoom）
 *     · 在一阶段死亡位置**爬出来**（原版坚守者出场动画）
 *     · 冷却 8 秒：把玩家瞬移到身前并控制视角 1 秒 + 20 点伤害
 *     · 冷却 15 秒：召唤 7 只「守夜蝇」（幻翼）血量 70 基础伤害 10
 *     · 冷却 90 秒：从地里爬出来开始计时 90 秒，
 *       对最近玩家造成 996 点伤害并提示「夜深了」
 *
 *   掉落（第八十六批）：
 *     · 一阶段固定掉落 **1 把「月镰」**（`GS_MOON_SCYTHE`，配方已删除）
 *     · 二阶段掉落 **6 个「血夜粒」**（`GS_BLOOD_NIGHT_GRAIN`）
 * ==========================================================================
 *
 * ★★ 关键实现要点（都是踩过坑之后定下来的，改动前先读）
 *
 * ① **删原版掉落**只能靠死亡事件里 `getDrops().clear()`：
 *    1.21.11 **没有** `LootTables.EMPTY` 常量，也没有 `minecraft:empty` 战利品表
 *    （老写法「置空战利品表」在这里是**静默失效**的）。
 *    所以三个地方都要清：`getEquipment()` / `getInventory()` / `getDrops()`，
 *    外加 `setDroppedExp(0)`。
 *
 * ② **「只对最近的玩家有仇恨」对坚守者是个特殊问题**（2026-10-14 反编译核实）：
 *    `Warden#getTarget()` 的字节码是 `return this.getTargetFromBrain();` ——
 *    它读的是 **brain 里的 `ATTACK_TARGET` 记忆**，而 **不是** `Mob.target` 字段。
 *    而 `Mob#setTarget(LivingEntity)`（= Bukkit 的 `Mob#setTarget`）只写 `Mob.target`
 *    字段 ⇒ **对坚守者完全无效**。
 *    坚守者真正的入口是 `Warden#setAttackTarget(LivingEntity)`（public），
 *    它做三件事：清 `ROAR_TARGET`、写 `ATTACK_TARGET`、清 `CANT_REACH_WALK_TARGET_SINCE`。
 *    ⇒ 本脚本每拍通过 `entity.getHandle().setAttackTarget(玩家.getHandle())`
 *      **动态派发**调它（GraalJS 直接按名字解析宿主对象的方法，
 *      不需要 `Java.type` 一个 NMS 类 ⇒ 少一层失败可能）。
 *    ★ 另外还挂了两道保险：
 *      · `EntityTargetLivingEntityEvent` / `EntityTargetEvent` 里，
 *        目标不是「最近的玩家」⇒ 取消（兜住走 `Mob#setTarget` 的那条路）；
 *      · 每拍 `clearAnger(其他玩家)`，让原版愤怒系统也别记恨别人。
 *
 * ③ **「取消原版冲击波」**（2026-10-14 反编译核实）：
 *    `SonicBoom` 行为的构造器要求
 *        `ATTACK_TARGET` = VALUE_PRESENT
 *        `SONIC_BOOM_COOLDOWN` = **VALUE_ABSENT**   ← 关键
 *    ⇒ 只要 `SONIC_BOOM_COOLDOWN` 这条记忆**一直在**，这个行为永远起不来。
 *    `SonicBoom.setCooldown(LivingEntity, int)` 就是往这条记忆里写 `Unit.INSTANCE`
 *    并带过期时间 ⇒ 本脚本每拍写一次 `NW_S2_SONIC_BLOCK`(200 刻) 把它按死。
 *    ★ 兜底：监听 `EntityDamageEvent`，`cause == SONIC_BOOM` 且来源是我们这条线的
 *      boss ⇒ 取消。（伤害被取消后 `hurtServer` 返回 false，
 *      连 `lambda$tick$2` 里的击退也不会执行。）
 *
 * ④ **「在一阶段死亡位置爬出来（原版坚守者出场动画）」**（2026-10-14 反编译核实）：
 *    原版 `Warden#finalizeSpawn` 里那段出场动画的触发条件是
 *        `entitySpawnReason == EntitySpawnReason.TRIGGERED`
 *    满足时它做两件事：
 *        `setPose(Pose.EMERGING)`
 *        `brain.setMemoryWithExpiry(IS_EMERGING, Unit.INSTANCE, WardenAi.EMERGE_DURATION)`
 *    然后 `WardenAi.updateActivity()` 会把活动切成 `Activity.EMERGE`，
 *    `Emerging` 行为负责 `setPose(EMERGING)` + 播 `WARDEN_EMERGE` 音效，
 *    时长到点后 `stop()` 里 `setPose(STANDING)`。
 *    ⇒ 本脚本**不依赖 SpawnReason 的映射**（那层映射跨版本会变），
 *      而是拿到实体之后**直接照抄这两行**（`nwEmerge()`）。
 *      `WardenAi.EMERGE_DURATION` = ceil(133.6) = **134 刻 ≈ 6.7 秒**（读不到就用兜底值）。
 *    ★ `Emerging` 的构造器还要求 `WALK_TARGET` = VALUE_ABSENT ⇒
 *      **出场期间不许给它塞索敌目标**（`ref.emergeUntil` 之前 `nwSetTarget` 直接跳过），
 *      否则 `Activity.EMERGE` 起不来、动画就没了。
 *
 * ⑤ **坚守者会「钻地跑路」**（`Digging` 行为到点把自己 `remove(DISCARDED)`）：
 *    `Digging` 的构造器要求 `ATTACK_TARGET` = VALUE_ABSENT ⇒ 只要一直有目标就不会钻。
 *    另外 `Warden#tick` 里 `if (isPersistenceRequired() || requiresCustomPersistence())
 *    WardenAi.setDigCooldown(this)`，而 `setDigCooldown` 只在 `DIG_COOLDOWN`
 *    **已存在**时刷新它（1200 刻）⇒ 我们 `setPersistent(true)` 之后它每刻都在续命。
 *    ★ 本脚本再补一手：每拍 `brain.setMemoryWithExpiry(DIG_COOLDOWN, Unit, 200)`。
 *
 * ⑥ **洞穴蜘蛛的「飞天」不能靠物理**：重力每刻都在拽它，纯 `setVelocity` 上抛
 *    10 刻是抬不满 20 格的 ⇒ 每刻**直接 `teleport` 到算好的高度**，
 *    同时把速度清零（免得重力累加、落下来一截）。
 *
 * ⑦ **弹道箭矢的伤害按 0 处理**：用户只说了「命中玩家造成 中毒2/缓慢3/失明各三秒」，
 *    **没有**给箭伤 ⇒ 箭的 `setDamage(0.0)`，三种 debuff 在
 *    `ProjectileHitEvent` 里自己上（不依赖伤害事件，0 伤害也一定上得去）。
 *
 * ⑧ **守夜蝇（幻翼）白天会烧**：召唤后 **15 秒内**每拍 `setFireTicks(0)` 兜一下
 *    （★ 第八十六批补：用户「给守夜人的召唤物加上防火效果，并且持续时间只持续到
 *    下个技能冷却结束」⇒ 防火**只持续到下一次召唤技能冷却结束**，也就是
 *    `NW_S2_FLY_FIRE_MS` = `NW_S2_FLY_CD` = 15 秒；过了窗口就**恢复原版会烧**）。
 *    ★★ 同一批还改成「**整波换新**」：下一次召唤时先把上一波**全部清掉**再召 7 只
 *    ⇒ 场上永远只有 7 只，不会一波波累加（旧写法一分钟能堆到 28 只）。
 *
 * ⑨ PDC 的 `NamespacedKey` 必须用 `NamespacedKey.fromString('gs:xxx')`，
 *    **不能**用 `new NamespacedKey(plugin, name)`（GraalJS 下重载消歧义失败）。
 * ========================================================================== */

// ==================================================================
// 常量
// ==================================================================
var NW_ITEM_ID     = 'GS_NIGHT_LANTERN';   // items.yml 里的物品 id
var NW_CONSUME     = true;                 // 召唤成功才扣 1 个
var NW_SPAWN_AHEAD = 3.0;                  // 一阶段生成在玩家前方几格
var NW_TICK        = 5;                    // 主循环间隔（刻）
var NW_FIRST_MS    = 4000;                 // 出场保护期（毫秒）：这段时间不放技能
var NW_SEEK        = 48.0;                 // 索敌半径（格）
var NW_HP_FIX_MS   = 8000;                 // 血量强设的重试窗口（毫秒）
var NW_TRANS_TICKS = 40;                   // 阶段切换演出时长（刻）= 2 秒
var NW_BAR_RANGE   = 48.0;                 // 血条可见半径
var NW_BAR_ONLY_OWNER = true;              // 血条只给召唤者看
var NW_TELE_AHEAD  = 2.0;                  // 「瞬移到身前」= boss 正前方几格

// ---- 一阶段：洞穴蜘蛛「巡夜守卫」----
var NW_S1_NAME  = '\u00a75巡夜守卫';
var NW_S1_HP    = 450;
var NW_S1_DMG   = 17;
// · 冷却 7 秒：弹道箭矢
var NW_S1_ARROW_CD    = 7000;
var NW_S1_ARROW_SPEED = 2.0;    // 每刻位移（格）
var NW_S1_ARROW_LIFT  = 0.06;   // 弹道补偿：按水平距离抬枪口（格/格）
//   命中给：中毒 II / 缓慢 III / 失明，各 3 秒 = 60 刻
var NW_S1_DEBUFF_TICKS = 60;
var NW_S1_POISON_AMP   = 1;   // 等级 II（amplifier 1）
var NW_S1_SLOW_AMP     = 2;   // 等级 III（amplifier 2）
var NW_S1_BLIND_AMP    = 0;   // 失明没有等级
// · 冷却 9 秒：白色粒子飞天（约 0.5 秒 20 格）→ 出现在玩家背后
var NW_S1_SKY_CD     = 9000;
var NW_S1_SKY_BLOCKS = 20.0;   // 飞 20 格（用户指定）
var NW_S1_SKY_TICKS  = 10;     // 约 0.5 秒 = 10 刻（用户指定）
var NW_S1_BEHIND     = 1.5;    // 出现在玩家背后几格

// ---- 二阶段：坚守者「守夜人」----
var NW_S2_NAME  = '\u00a75守夜人';
var NW_S2_HP    = 900;
var NW_S2_DMG   = 44;
// · 冷却 8 秒：瞬移到身前 + 控视角 1 秒 + 20 点伤害
var NW_S2_GRAB_CD         = 8000;
var NW_S2_GRAB_DMG        = 20;
var NW_S2_GRAB_LOCK_TICKS = 20;   // 控视角 1 秒 = 20 刻
var NW_S2_GRAB_AHEAD      = 2.0;  // 落在 boss 正前方几格
// · 冷却 15 秒：7 只「守夜蝇」（幻翼）
var NW_S2_FLY_CD   = 15000;
var NW_S2_FLY_N    = 7;
var NW_S2_FLY_NAME = '\u00a75守夜蝇';
var NW_S2_FLY_HP   = 70;
var NW_S2_FLY_DMG  = 10;
// ★★★ 第八十六批补：守夜蝇的「防火」只持续到**下个召唤技能冷却结束**（= 15 秒）
//   用户：「给守夜人的召唤物加上防火效果，并且持续时间只持续到下个技能冷却结束」
//   ★ 窗口一过就**不再灭火** ⇒ 恢复原版「白天会烧」；
//   ★ 同一时刻下一波召唤会把旧的一波清掉（整波换新，见 nwSummonFlies）。
var NW_S2_FLY_FIRE_MS = NW_S2_FLY_CD;
// · 冷却 90 秒：夜深了
var NW_S2_NIGHT_CD  = 90000;
var NW_S2_NIGHT_DMG = 996;
var NW_S2_NIGHT_MSG = '\u00a75夜深了';
// · 把「原版冲击波 / 钻地跑路」两条记忆按死的时长（刻）
var NW_S2_MEM_HOLD  = 200;
// · 读不到 `WardenAi.EMERGE_DURATION` 时的兜底出场时长（刻）
var NW_EMERGE_FALLBACK_TICKS = 134;

// ==================================================================
// ★★★ 第八十六批：掉落物
//   用户原话：「一阶段固定掉落1把月镰」「二阶段掉落6个血夜粒」
//   · 一阶段（巡夜守卫）死掉 ⇒ 死亡位置掉 **1 把「月镰」**（配方已删除，改成纯掉落）
//   · 二阶段（守夜人）死掉   ⇒ 死亡位置掉 **6 个「血夜粒」**
//   ★ 掉落时机在 nwPhaseEnd() 里 —— 那里已经是"某个阶段打完了"的**唯一**入口，
//     而且它拿到的 (x, y, z) 正是**死亡位置**（死亡事件写的 ref.dx/dy/dz）。
// ==================================================================
var NW_DROP_S1_ID = 'GS_MOON_SCYTHE';        // 月镰
var NW_DROP_S1_N  = 1;
var NW_DROP_S2_ID = 'GS_BLOOD_NIGHT_GRAIN';  // 血夜粒
var NW_DROP_S2_N  = 6;

// ==================================================================
// 状态
// ==================================================================
var NW_ACTIVE = {};   // 召唤者 uuid -> ref（每个玩家同时只能有一条线）
var NW_LAST   = {};   // 召唤者 uuid -> 上次点击时间戳（防一次点击被派发两遍）

// PDC
var NW_KEY      = null;   // gs:nightwatch        → 'boss1' / 'boss2' / 'fly'
var NW_PROJ_KEY = null;   // gs:nightwatch_proj   → 'arrow'
var NW_OWN_KEY  = null;   // gs:nightwatch_owner  → 召唤者 uuid（弹体 / 小怪反查用）
var NW_PDT      = null;
try {
    NW_PDT      = Java.type('org.bukkit.persistence.PersistentDataType');
    NW_KEY      = Java.type('org.bukkit.NamespacedKey').fromString('gs:nightwatch');
    NW_PROJ_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:nightwatch_proj');
    NW_OWN_KEY  = Java.type('org.bukkit.NamespacedKey').fromString('gs:nightwatch_owner');
} catch (e) { NW_KEY = null; NW_PROJ_KEY = null; NW_OWN_KEY = null; NW_PDT = null; }

// ==================================================================
// 小工具
// ==================================================================
function nwNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function nwAlive(e) {
    try { if (e == null) return false; return !(e.isDead() || !e.isValid()); }
    catch (e2) { return false; }
}

function nwTagOf(e, key) {
    try {
        if (e == null || key == null || NW_PDT == null) return '';
        var v = e.getPersistentDataContainer().get(key, NW_PDT.STRING);
        return (v == null ? '' : '' + v);
    } catch (err) { return ''; }
}

function nwTag(e)     { return nwTagOf(e, NW_KEY); }
function nwProjTag(e) { return nwTagOf(e, NW_PROJ_KEY); }
function nwOwnerOf(e) { return nwTagOf(e, NW_OWN_KEY); }

function nwSetTagOf(e, key, val) {
    try {
        if (e == null || key == null || NW_PDT == null) return false;
        e.getPersistentDataContainer().set(key, NW_PDT.STRING, '' + val);
        return true;
    } catch (err) { return false; }
}

// 这是我们这条 boss 线上的东西吗（boss / 小怪 / 弹体都算）
function nwOurs(e) {
    try {
        if (e == null || NW_KEY == null || NW_PDT == null) return false;
        if (e.getPersistentDataContainer().has(NW_KEY, NW_PDT.STRING)) return true;
        if (NW_PROJ_KEY != null && e.getPersistentDataContainer().has(NW_PROJ_KEY, NW_PDT.STRING)) return true;
        return false;
    } catch (err) { return false; }
}

// ★ GraalJS 里 Java 类**必须用 `new` 调构造器**（`Java.type('X')(...)` 不成立）
function nwVec(x, y, z) {
    try { var V = Java.type('org.bukkit.util.Vector'); return new V(x, y, z); }
    catch (e) { return null; }
}

function nwLoc(world, x, y, z, yaw, pitch) {
    try { var L = Java.type('org.bukkit.Location'); return new L(world, x, y, z, yaw, pitch); }
    catch (e) { return null; }
}

// 方块的通行判定（★ 用 isPassable：**玻璃挡得住**）
function nwPassableAt(world, bx, by, bz) {
    try {
        var blk = world.getBlockAt(nwLoc(world, 0 + bx, 0 + by, 0 + bz, 0, 0));
        if (blk == null) return true;
        var mt = null;
        try { mt = blk.getType(); } catch (e) { mt = null; }
        if (mt != null) { try { if (mt.isAir()) return true; } catch (e) { } }
        try { return !!blk.isPassable(); } catch (e) { }
        if (mt != null) { try { return !mt.isSolid(); } catch (e) { } }
        return true;
    } catch (e) { return true; }
}

function nwHeadroom(world, bx, bz, by, height) {
    try {
        for (var i = 0; i < height; i++) {
            if (!nwPassableAt(world, bx, by + i, bz)) return false;
        }
        return true;
    } catch (e) { return false; }
}

// 找一个能站的落地高度（先往下找实地，找不到就往上抬）
function nwSafeY(world, x, z, y0, height) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z);
        var start = Math.floor(y0);
        var lo = start - 4, hi = start + 8;
        if (lo < -60) lo = -60;
        if (hi > 250) hi = 250;
        var y;
        for (y = start; y >= lo; y--) {
            if (nwHeadroom(world, bx, bz, y, height) && !nwPassableAt(world, bx, y - 1, bz)) return y;
        }
        for (y = start; y <= hi; y++) {
            if (nwHeadroom(world, bx, bz, y, height)) return y;
        }
        return y0;
    } catch (e) { return y0; }
}

// 粒子名解析（多个候选，逐个试）
function nwP(cands) {
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
var NW_P_SAFE = null;
function nwPSafe() {
    try {
        if (NW_P_SAFE != null) return NW_P_SAFE;
        NW_P_SAFE = nwP(['SOUL_FIRE_FLAME', 'SCULK_SOUL', 'SMOKE', 'FLAME']);
        return NW_P_SAFE;
    } catch (e) { return null; }
}

function nwFx(world, pt, x, y, z, n, dx, dy, dz, speed) {
    try {
        if (world == null || pt == null) return;
        world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
    } catch (e) { }
}

function nwSound(world, loc, name, vol, pitch) {
    try {
        try { world.playSound(loc, name, vol, pitch); }
        catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); } catch (e2) { } }
    } catch (e) { }
}

function nwET(names) {
    try {
        var ET = Java.type('org.bukkit.entity.EntityType');
        for (var i = 0; i < names.length; i++) {
            try { var t = ET[names[i]]; if (t != null) return t; } catch (e) { }
            try { var t2 = ET.valueOf(names[i]); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// 药水效果类型解析（1.21 把 SLOW 改名成 SLOWNESS，两个名字都试）
function nwPT(names) {
    try {
        var PT = Java.type('org.bukkit.potion.PotionEffectType');
        for (var i = 0; i < names.length; i++) {
            try { var t = PT[names[i]]; if (t != null) return t; } catch (e) { }
            try { var t2 = PT.valueOf(names[i]); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ---------------- 属性 ----------------
function nwAttrKey(names) {
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
var NW_MAXATTR = null;
function nwFindMaxAttr() {
    try {
        if (NW_MAXATTR != null) return NW_MAXATTR;
        var k = nwAttrKey(['MAX_HEALTH', 'GENERIC_MAX_HEALTH']);
        if (k != null) { NW_MAXATTR = k; return k; }
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var all = Attr.values();
        for (var i = 0; i < all.length; i++) {
            var a = all[i];
            var nm = '', key = '';
            try { nm = '' + a.name(); } catch (e) { nm = ''; }
            try { key = '' + a.getKey().getKey(); } catch (e) { key = ''; }
            if (nm.indexOf('MAX_HEALTH') >= 0 || key.indexOf('max_health') >= 0) {
                NW_MAXATTR = a;
                return a;
            }
        }
    } catch (e) { }
    return null;
}

// 强设血量上限：删修改器 → 属性 baseValue → 旧 API setMaxHealth 兜底
function nwForceMax(ent, hp) {
    var ok = false;
    try {
        var k = nwFindMaxAttr();
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
function nwSetDmg(ent, want) {
    try {
        var k = nwAttrKey(['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE']);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        var cur = inst.getValue();
        var base = inst.getBaseValue();
        inst.setBaseValue(base + (want - cur));
        return true;
    } catch (e) { return false; }
}

function nwMaxHp(ent) {
    try {
        var k = nwFindMaxAttr();
        if (k == null) return 0;
        var inst = ent.getAttribute(k);
        if (inst == null) return 0;
        return inst.getValue();
    } catch (e) { return 0; }
}

function nwHurt(src, ent, dmg) {
    try {
        try { ent.setNoDamageTicks(0); } catch (e) { }
        try { ent.damage(dmg, src); }
        catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
    } catch (e) { }
}

// ★★ 血量写入：先确认上限到位，再回满血；失败就延迟重试 + 每拍重试。
var NW_HP_DIAG = {};
function nwHpDiag(ent, want, max, cur, why) {
    try {
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { id = 'unknown'; }
        if (NW_HP_DIAG[id] != null) return;
        NW_HP_DIAG[id] = 1;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { tn = '?'; }
        var kname = 'null';
        try { var k = nwFindMaxAttr(); if (k != null) kname = '' + k.name(); } catch (e) { }
        try {
            getServer().getLogger().warning('[构思技艺] 守夜人的提灯 血量设置失败(' + why + ')：实体='
                + tn + ' 目标=' + want + ' 实际上限=' + max + ' 实际血量=' + cur
                + ' MAX_HEALTH键=' + kname);
        } catch (e2) { }
    } catch (e) { }
}

function nwApplyHpOnce(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return false;
        nwForceMax(ent, hp);
        var mx = nwMaxHp(ent);
        if (mx < hp - 0.5) { nwHpDiag(ent, hp, mx, -1, '上限没上去'); return false; }
        try { ent.setHealth(hp); } catch (e) { }
        var now = -1;
        try { now = ent.getHealth(); } catch (e) { now = -1; }
        if (now >= hp - 0.5) return true;
        nwHpDiag(ent, hp, mx, now, '血量没回满');
        return false;
    } catch (e) { return false; }
}

function nwApplyHp(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return;
        if (nwApplyHpOnce(ent, hp)) return;
        var delays = [1, 3, 6, 10, 20, 40];
        for (var i = 0; i < delays.length; i++) {
            runLater(function (e0, h0) {
                return function () {
                    try { if (!nwAlive(e0)) return; nwApplyHpOnce(e0, h0); } catch (e) { }
                };
            }(ent, hp), delays[i]);
        }
    } catch (e) { }
}

// ==================================================================
// BossBar（血条）
// ==================================================================
function nwBarMake(title) {
    try {
        var BarColor = Java.type('org.bukkit.boss.BarColor');
        var BarStyle = Java.type('org.bukkit.boss.BarStyle');
        var c = null, s = null;
        try { c = BarColor.PURPLE; } catch (e) { c = null; }
        if (c == null) { try { c = BarColor.valueOf('PURPLE'); } catch (e2) { } }
        if (c == null) { try { c = BarColor.WHITE; } catch (e2) { } }
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

function nwBarSync(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        var ent = ref.ent;
        var alive = nwAlive(ent);

        var prog = 0.0;
        if (alive) {
            try {
                var hp = ent.getHealth();
                var mx = nwMaxHp(ent);
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
                    if (NW_BAR_ONLY_OWNER) {
                        try { if ('' + p.getUniqueId() !== '' + ref.owner) continue; } catch (e) { continue; }
                    }
                    try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    if (pl.distance(el) > NW_BAR_RANGE) continue;
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

function nwBarDrop(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        try { ref.bar.setVisible(false); } catch (e) { }
        try { ref.bar.removeAll(); } catch (e) { }
        try { for (var k in ref.barNear) { delete ref.barNear[k]; } } catch (e) { }
        ref.bar = null;
    } catch (e) { }
}

function nwBarTitle(stage) {
    if (stage === 1) return NW_S1_NAME;   // 巡夜守卫
    return NW_S2_NAME;                    // 守夜人
}

// ==================================================================
// 找目标 / 找 ref
// ==================================================================
// 最近的玩家（同世界、NW_SEEK 格内）
function nwNearestPlayer(ref) {
    try {
        if (ref == null) return null;
        var world = ref.lw;
        if (world == null) return null;
        var boss = ref.ent;
        if (!nwAlive(boss)) return null;
        var bl = boss.getLocation();
        var best = null, bestD = NW_SEEK;
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
function nwRefOf(ent) {
    try {
        var u = nwOwnerOf(ent);
        if (u == null || u.length === 0) return null;
        var r = NW_ACTIVE[u];
        if (r == null) return null;
        return r;
    } catch (e) { return null; }
}

// ==================================================================
// 生成实体
// ==================================================================
function nwSpawnMob(world, loc, typeNames, name, hp, dmg, tagValue, aware, ownerUuid) {
    try {
        var t = nwET(typeNames);
        if (t == null) {
            try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯：找不到实体类型 ' + typeNames[0]); } catch (e) { }
            return null;
        }
        var e = world.spawnEntity(loc, t);
        if (e == null) return null;

        nwApplyHp(e, hp);
        if (dmg != null && dmg >= 0) nwSetDmg(e, dmg);

        try { e.setCustomName(name); } catch (err) { }
        try { e.setCustomNameVisible(true); } catch (err) { }
        try { e.setRemoveWhenFarAway(false); } catch (err) { }
        try { e.setPersistent(true); } catch (err) { }
        try { e.setCanPickupItems(false); } catch (err) { }
        try { e.setAware(!!aware); } catch (err) { }
        try { e.setSilent(false); } catch (err) { }
        try { e.getEquipment().clear(); } catch (err) { }
        try { var inv = e.getInventory(); if (inv != null) inv.clear(); } catch (err) { }

        nwSetTagOf(e, NW_KEY, tagValue);
        if (ownerUuid != null) nwSetTagOf(e, NW_OWN_KEY, ownerUuid);

        return e;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯：生成 ' + typeNames[0] + ' 出错 ' + e); } catch (e2) { }
        return null;
    }
}

// ==================================================================
// ★★★ 第八十六批：NMS 小工具（坚守者的「索敌 / 反冲击波 / 反钻地 / 出场动画」）
//   ★ 全部**惰性初始化 + try/catch**：拿不到 NMS 就整体降级，
//     绝不让一行 NMS 调用把整条 boss 线搞崩。
// ==================================================================
var NW_NMS_TRIED = false;
var NW_NMS_OK    = false;
var NW_MMT       = null;   // net.minecraft.world.entity.ai.memory.MemoryModuleType
var NW_UNIT      = null;   // net.minecraft.util.Unit
var NW_POSE      = null;   // net.minecraft.world.entity.Pose
var NW_SONIC     = null;   // ...behavior.warden.SonicBoom
var NW_WARDEN_AI = null;   // ...monster.warden.WardenAi

function nwNmsInit() {
    try {
        if (NW_NMS_TRIED) return NW_NMS_OK;
        NW_NMS_TRIED = true;
        try { NW_MMT = Java.type('net.minecraft.world.entity.ai.memory.MemoryModuleType'); } catch (e) { NW_MMT = null; }
        try { NW_UNIT = Java.type('net.minecraft.util.Unit'); } catch (e) { NW_UNIT = null; }
        try { NW_POSE = Java.type('net.minecraft.world.entity.Pose'); } catch (e) { NW_POSE = null; }
        try { NW_SONIC = Java.type('net.minecraft.world.entity.ai.behavior.warden.SonicBoom'); } catch (e) { NW_SONIC = null; }
        try { NW_WARDEN_AI = Java.type('net.minecraft.world.entity.monster.warden.WardenAi'); } catch (e) { NW_WARDEN_AI = null; }
        NW_NMS_OK = (NW_MMT != null && NW_UNIT != null);
        try {
            getServer().getLogger().info('[构思技艺] 守夜人的提灯 NMS 通道：'
                + (NW_NMS_OK ? '可用' : '不可用（降级：只靠 Bukkit 事件兜底）')
                + ' ｜ SonicBoom=' + (NW_SONIC != null ? 'ok' : 'null')
                + ' ｜ WardenAi=' + (NW_WARDEN_AI != null ? 'ok' : 'null'));
        } catch (e) { }
    } catch (e) { NW_NMS_OK = false; }
    return NW_NMS_OK;
}

// 出场动画时长（刻）
function nwEmergeTicks() {
    try {
        nwNmsInit();
        if (NW_WARDEN_AI != null) {
            var v = NW_WARDEN_AI.EMERGE_DURATION;
            if (v != null && v > 0) return v;
        }
    } catch (e) { }
    return NW_EMERGE_FALLBACK_TICKS;
}

// ★★★ 「在一阶段死亡位置爬出来（原版坚守者出场动画）」
//   照抄 1.21.11 `Warden#finalizeSpawn` 里 `EntitySpawnReason.TRIGGERED` 那一支：
//     setPose(Pose.EMERGING)
//     brain.setMemoryWithExpiry(IS_EMERGING, Unit.INSTANCE, WardenAi.EMERGE_DURATION)
//   ⇒ `WardenAi.updateActivity()` 会把活动切成 Activity.EMERGE，
//     `Emerging` 行为负责动画与音效，到点后自己 `setPose(STANDING)`。
function nwEmerge(ent) {
    try {
        if (!nwNmsInit()) return false;
        var hn = ent.getHandle();
        if (hn == null) return false;
        var brain = hn.getBrain();
        if (brain == null) return false;
        brain.setMemoryWithExpiry(NW_MMT.IS_EMERGING, NW_UNIT.INSTANCE, nwEmergeTicks());
        if (NW_POSE != null) { try { hn.setPose(NW_POSE.EMERGING); } catch (e) { } }
        return true;
    } catch (e) { return false; }
}

// ★★★ 把两条「原版记忆」按住：
//   ① DIG_COOLDOWN 常在 ⇒ 不会「钻地跑路」（Digging 到点会把自己 remove 掉）
//   ② SONIC_BOOM_COOLDOWN 常在 ⇒ SonicBoom 行为永远起不来（构造器要求它 ABSENT）
function nwHoldMemories(ent) {
    try {
        if (!nwNmsInit()) return false;
        var hn = ent.getHandle();
        if (hn == null) return false;
        var brain = hn.getBrain();
        if (brain == null) return false;
        try { brain.setMemoryWithExpiry(NW_MMT.DIG_COOLDOWN, NW_UNIT.INSTANCE, NW_S2_MEM_HOLD); } catch (e) { }
        try { if (NW_SONIC != null) NW_SONIC.setCooldown(hn, NW_S2_MEM_HOLD); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ★★★ 「只对最近的玩家有仇恨」
//   坚守者的 `getTarget()` 读的是 brain 里的 `ATTACK_TARGET`，
//   而 Bukkit 的 `Mob#setTarget()` 只写 `Mob.target` 字段 ⇒ **对坚守者无效**。
//   真正的入口是 `Warden#setAttackTarget(LivingEntity)` ⇒ 走 `getHandle()` 动态派发。
//   拿不到 NMS 时退回 `Mob#setTarget`（至少让原版愤怒/事件那一路还能起点作用）。
function nwSetTarget(ref, p) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return false;
        var pn = null;
        try { pn = (p == null ? null : p.getHandle()); } catch (e) { pn = null; }
        var done = false;
        try {
            var hn = boss.getHandle();
            if (hn != null) { hn.setAttackTarget(pn); done = true; }
        } catch (e) { done = false; }
        if (!done) { try { boss.setTarget(p); } catch (e) { } }
        nwHoldMemories(boss);
        return true;
    } catch (e) { return false; }
}

// 原版愤怒系统那边也别记恨别人（和 ATTACK_TARGET 是两条独立的路）
function nwCalmOthers(ref, keep) {
    try {
        if (ref == null) return;
        var boss = ref.ent;
        if (!nwAlive(boss)) return;
        var world = ref.lw;
        if (world == null) return;
        var players = world.getPlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            var same = false;
            try { same = (keep != null) && ('' + p.getUniqueId()) === ('' + keep.getUniqueId()); } catch (e) { same = false; }
            if (same) continue;
            try { boss.clearAnger(p); } catch (e) { }
        }
    } catch (e) { }
}

// ==================================================================
// 技能：一阶段 · 弹道箭矢（冷却 7 秒）
//   命中玩家 ⇒ 中毒 II / 缓慢 III / 失明，各 3 秒
//   ★ 箭伤按 0 处理（用户没给箭伤，只给了三种 debuff）
// ==================================================================
function nwFireArrow(ref) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return false;
        var tgt = nwNearestPlayer(ref);
        if (tgt == null) return false;
        var world = ref.lw;

        var from = null;
        try { from = boss.getEyeLocation(); } catch (e) { from = boss.getLocation(); }
        var to = tgt.getLocation();

        var dx = to.getX() - from.getX();
        var dz = to.getZ() - from.getZ();
        var dy = (to.getY() + 1.0) - from.getY();
        var flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.05 && Math.abs(dy) < 0.05) return false;

        var Arrow = Java.type('org.bukkit.entity.Arrow');
        var ar = null;
        try { ar = world.spawn(from, Arrow); } catch (e) { ar = null; }
        if (ar == null) return false;

        try { ar.setShooter(boss); } catch (e) { }
        try { ar.setDamage(0.0); } catch (e) { }              // ★ 箭伤 0：debuff 才是它的活
        try { ar.setKnockbackStrength(0); } catch (e) { }
        try { ar.setCritical(false); } catch (e) { }
        try {
            var PS = Java.type('org.bukkit.entity.AbstractArrow$PickupStatus');
            ar.setPickupStatus(PS.DISALLOWED);
        } catch (e) { }

        // ★ 弹道补偿：按水平距离抬一点枪口，让抛物线落在玩家身上
        var lift = flat * NW_S1_ARROW_LIFT;
        var dir = nwVec(dx, dy + lift, dz);
        if (dir == null) return false;
        dir.normalize();
        try { ar.setVelocity(dir.multiply(NW_S1_ARROW_SPEED)); } catch (e) { }

        nwSetTagOf(ar, NW_PROJ_KEY, 'arrow');
        nwSetTagOf(ar, NW_OWN_KEY, ref.owner);
        ref.arrow = ar;

        try { nwSound(world, from, 'entity.arrow.shoot', 1.0, 1.1); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// 命中玩家 ⇒ 三种 debuff 各 3 秒
function nwApplyDebuff(p) {
    try {
        if (p == null) return false;
        var PE = Java.type('org.bukkit.potion.PotionEffect');
        var list = [
            [nwPT(['POISON']), NW_S1_POISON_AMP],
            [nwPT(['SLOWNESS', 'SLOW']), NW_S1_SLOW_AMP],
            [nwPT(['BLINDNESS']), NW_S1_BLIND_AMP]
        ];
        var n = 0;
        for (var i = 0; i < list.length; i++) {
            var t = list[i][0];
            if (t == null) continue;
            try {
                p.addPotionEffect(new PE(t, NW_S1_DEBUFF_TICKS, list[i][1], false, true, true));
                n++;
            } catch (e) { }
        }
        return n > 0;
    } catch (e) { return false; }
}

// ==================================================================
// 技能：一阶段 · 白色粒子飞天 → 出现在玩家背后（冷却 9 秒）
//   用户原话：「白色粒子飞天（约0.5秒20格），飞到20格后直接出现在玩家背后」
//   ★ 不能靠物理上抛（重力每刻都在拽）⇒ 每刻直接 teleport 到算好的高度，
//     同时把速度清零，免得重力累加、落下来一截。
// ==================================================================
function nwSkyAscend(ref) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return false;
        var world = ref.lw;
        var bl = boss.getLocation();
        var sx = bl.getX(), sy = bl.getY(), sz = bl.getZ();

        ref.skyGen = (ref.skyGen == null ? 0 : ref.skyGen) + 1;
        var gen = ref.skyGen;
        var stepUp = NW_S1_SKY_BLOCKS / NW_S1_SKY_TICKS;
        var i = 0;

        try { nwSound(world, bl, 'entity.illusioner.mirror_move', 1.0, 1.4); } catch (e) { }

        function step() {
            try {
                if (ref.done || ref.skyGen !== gen) return;
                if (!nwAlive(boss)) return;
                if (i >= NW_S1_SKY_TICKS) { nwAppearBehind(ref); return; }
                i++;
                var y = sy + stepUp * i;
                var at = nwLoc(world, sx, y, sz, 0, 0);
                try { boss.setVelocity(nwVec(0, 0, 0)); } catch (e) { }
                try { boss.teleport(at); } catch (e) { }
                var pt = nwP(['END_ROD', 'WHITE_ASH', 'SNOWFLAKE', 'CLOUD']);
                nwFx(world, pt, sx, y + 0.4, sz, 16, 0.3, 0.3, 0.3, 0.02);
                try { nwSound(world, at, 'entity.phantom.flap', 0.7, 1.6); } catch (e) { }
                runLater(step, 1);
            } catch (e) { }
        }
        step();
        return true;
    } catch (e) { return false; }
}

// 「直接出现在玩家背后」
function nwAppearBehind(ref) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return false;
        var world = ref.lw;
        var tgt = nwNearestPlayer(ref);
        if (tgt == null) return false;

        var tl = tgt.getLocation();
        var yaw = 0.0;
        try { yaw = tl.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        // 玩家正前方 = (-sin, cos) ⇒ 背后取反
        var bx = tl.getX() + Math.sin(rad) * NW_S1_BEHIND;
        var bz = tl.getZ() - Math.cos(rad) * NW_S1_BEHIND;
        var by = nwSafeY(world, bx, bz, tl.getY(), 1);
        var at = nwLoc(world, bx, by, bz, yaw, 0.0);

        // 出发点（天上）的白色粒子收个尾
        try {
            var bl = boss.getLocation();
            var pt0 = nwP(['END_ROD', 'WHITE_ASH', 'SNOWFLAKE', 'CLOUD']);
            nwFx(world, pt0, bl.getX(), bl.getY() + 0.4, bl.getZ(), 30, 0.4, 0.4, 0.4, 0.06);
        } catch (e) { }

        try { boss.teleport(at); } catch (e) { }
        try { boss.setTarget(tgt); } catch (e) { }

        // 落点特效
        try {
            var pt1 = nwP(['END_ROD', 'WHITE_ASH', 'SNOWFLAKE', 'CLOUD']);
            nwFx(world, pt1, bx, by + 0.4, bz, 36, 0.4, 0.7, 0.4, 0.10);
        } catch (e) { }
        try { nwSound(world, at, 'entity.enderman.teleport', 1.0, 1.4); } catch (e) { }
        try { nwSound(world, at, 'entity.spider.ambient', 1.0, 1.5); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ==================================================================
// 技能：二阶段 · 拉到身前 + 控视角 1 秒 + 20 点伤害（冷却 8 秒）
// ==================================================================
function nwGrabPlayer(ref, p) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss) || p == null) return false;
        var world = ref.lw;
        var bl = boss.getLocation();
        var yaw = 0.0;
        try { yaw = bl.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        var fx = bl.getX() + (-Math.sin(rad)) * NW_S2_GRAB_AHEAD;
        var fz = bl.getZ() + (Math.cos(rad)) * NW_S2_GRAB_AHEAD;
        var fy = nwSafeY(world, fx, fz, bl.getY(), 2);

        var from = null;
        try { from = p.getLocation(); } catch (e) { from = null; }

        // 出发点特效（被拽走）
        if (from != null) {
            try {
                var ptOut = nwP(['SCULK_SOUL', 'REVERSE_PORTAL', 'PORTAL', 'SOUL_FIRE_FLAME']);
                nwFx(world, ptOut, from.getX(), from.getY() + 1.0, from.getZ(), 26, 0.35, 0.6, 0.35, 0.08);
            } catch (e) { }
            try { nwSound(world, from, 'entity.enderman.teleport', 1.0, 0.7); } catch (e) { }
        }

        // ★ 面向 boss（yaw + 180 ⇒ 玩家正对坚守者的脸）
        var loc = nwLoc(world, fx, fy, fz, yaw + 180.0, 0.0);
        var ok = false;
        try { ok = p.teleport(loc); } catch (e) { ok = false; }

        // 落点特效
        try {
            var ptIn = nwP(['SCULK_SOUL', 'PORTAL', 'SOUL_FIRE_FLAME']);
            nwFx(world, ptIn, fx, fy + 1.0, fz, 30, 0.35, 0.7, 0.35, 0.12);
        } catch (e) { }
        try { nwSound(world, loc, 'entity.warden.angry', 1.2, 1.0); } catch (e) { }

        // ★ 控视角 1 秒（每刻强行把玩家的视线掰向 boss）
        nwLockView(ref, p, NW_S2_GRAB_LOCK_TICKS);

        // ★ 20 点伤害
        nwHurt(boss, p, NW_S2_GRAB_DMG);
        return ok;
    } catch (e) { return false; }
}

// 每刻强行把玩家的视线掰向 boss（GraalJS 下用「具名函数 + runLater」避免箭头函数）
function nwLockView(ref, p, ticks) {
    try {
        var left = ticks;
        function step() {
            try {
                if (ref == null || ref.done) return;
                if (left <= 0) return;
                left--;
                var boss = ref.ent;
                if (!nwAlive(boss) || !nwAlive(p)) return;
                var bl = boss.getLocation();
                var pl = p.getLocation();
                var dx = bl.getX() - pl.getX();
                var dz = bl.getZ() - pl.getZ();
                var dy = (bl.getY() + 1.4) - (pl.getY() + 1.62);
                var flat = Math.sqrt(dx * dx + dz * dz);
                // Minecraft：yaw 0 = +Z（南），yaw = atan2(-dx, dz)
                var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
                var pitch = -Math.atan2(dy, flat) * 180.0 / Math.PI;
                try { p.setRotation(yaw, pitch); } catch (e) { }
                runLater(step, 1);
            } catch (e) { }
        }
        step();
    } catch (e) { }
}

// ==================================================================
// 技能：二阶段 · 召唤 7 只「守夜蝇」（幻翼，冷却 15 秒）
// ==================================================================
function nwSummonFlies(ref) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return 0;
        var world = ref.lw;
        var bl = boss.getLocation();
        // ★★★ 第八十六批补：**整波换新** —— 先把上一波守夜蝇全部清掉再召新的，
        //   免得一波波累加（每 15 秒 7 只 ⇒ 一分钟能堆到 28 只）。
        nwClearFlies(ref);
        // ★ 这一波的「防火」截止时刻 = 召唤时刻 + NW_S2_FLY_FIRE_MS
        //   （= 下个召唤技能冷却结束；到点后这一波就恢复原版会烧）
        ref.flyFireUntil = nwNow() + NW_S2_FLY_FIRE_MS;
        var n = 0;
        for (var i = 0; i < NW_S2_FLY_N; i++) {
            var ang = (i * 2.0 * Math.PI) / NW_S2_FLY_N;
            var fx = bl.getX() + Math.cos(ang) * 2.0;
            var fz = bl.getZ() + Math.sin(ang) * 2.0;
            var fy = nwSafeY(world, fx, fz, bl.getY() + 1.0, 1);
            var at = nwLoc(world, fx, fy, fz, 0, 0);

            var m = nwSpawnMob(world, at, ['PHANTOM'], NW_S2_FLY_NAME,
                               NW_S2_FLY_HP, NW_S2_FLY_DMG, 'fly', true, ref.owner);
            if (m == null) continue;
            // ★ setSize 会重算上限 ⇒ 改完尺寸再压一次血量
            try { m.setSize(1); } catch (e) { }
            nwApplyHp(m, NW_S2_FLY_HP);
            ref.flies['' + m.getUniqueId()] = m;
            n++;
        }
        if (n > 0) {
            try {
                var pt = nwP(['SCULK_SOUL', 'SOUL_FIRE_FLAME', 'SMOKE']);
                nwFx(world, pt, bl.getX(), bl.getY() + 1.4, bl.getZ(), 40, 1.2, 0.5, 1.2, 0.10);
            } catch (e) { }
            try { nwSound(world, bl, 'entity.phantom.flap', 1.4, 0.8); } catch (e) { }
            try { nwSound(world, bl, 'entity.warden.roar', 1.0, 1.2); } catch (e) { }
        }
        return n;
    } catch (e) { return 0; }
}

// ==================================================================
// 技能：二阶段 · 「夜深了」（冷却 90 秒，从爬出来开始计时）
//   用户原话：「冷却90秒 从地里爬出来开始计时90秒，
//             对最近玩家造成996点伤害并提示「夜深了」」
// ==================================================================
function nwNightStrike(ref, tgt) {
    try {
        var boss = ref.ent;
        if (!nwAlive(boss)) return false;
        var world = ref.lw;
        var bl = null;
        try { bl = boss.getLocation(); } catch (e) { bl = null; }

        // 演出：坚守者的冲击波音效 + 一圈灵魂粒子（**不是**原版冲击波，只是音画）
        if (bl != null) {
            try { nwSound(world, bl, 'entity.warden.sonic_charge', 2.0, 0.7); } catch (e) { }
            try { nwSound(world, bl, 'entity.warden.sonic_boom', 2.0, 0.6); } catch (e) { }
            try { nwSound(world, bl, 'block.sculk_shrieker.shriek', 2.0, 0.8); } catch (e) { }
            try {
                var pt = nwP(['SONIC_BOOM', 'SCULK_SOUL', 'SOUL_FIRE_FLAME']);
                nwFx(world, pt, bl.getX(), bl.getY() + 1.6, bl.getZ(), 60, 1.6, 1.0, 1.6, 0.20);
            } catch (e) { }
        }

        if (tgt != null) {
            // ★ 996 点伤害
            nwHurt(boss, tgt, NW_S2_NIGHT_DMG);
            // ★ 提示「夜深了」
            nwMsg(tgt, NW_S2_NIGHT_MSG);
        }
        return true;
    } catch (e) { return false; }
}

// 给玩家发一条带颜色代码的提示（RSC 的 sendMessage 会翻译 & 代码；失败退回原版 API）
function nwMsg(p, msg) {
    try {
        if (p == null) return;
        try { sendMessage(p, msg); return; } catch (e) { }
        try { p.sendMessage(msg.replace(/&([0-9a-fA-Fk-oK-OrR])/g, '\u00a7$1')); return; } catch (e2) { }
    } catch (e) { }
}

// 大标题（夜深了）
function nwTitle(p, title) {
    try {
        if (p == null) return;
        var t = title.replace(/&([0-9a-fA-Fk-oK-OrR])/g, '\u00a7$1');
        try { p.sendTitle(t, '', 5, 40, 10); return; } catch (e) { }
        try { p.sendTitle(t, ''); return; } catch (e2) { }
    } catch (e) { }
}

// ==================================================================
// 主循环
// ==================================================================
function nwTick(ref) {
    try {
        if (ref == null || ref.done) return;
        if (ref.transitioning) return;

        var boss = ref.ent;
        if (!nwAlive(boss)) { nwPhaseEnd(ref); return; }

        var now = nwNow();

        // ⓪ 记下「这一拍它在哪」—— 二阶段要生成在**一阶段的死亡位置**，
        //    boss 死透之后再 getLocation() 就不一定准了 ⇒ 每拍先存一份兜底。
        //    （死亡事件里还会写一次**精确**的死亡坐标，那个优先。）
        try {
            var bl0 = boss.getLocation();
            ref.dx = bl0.getX(); ref.dy = bl0.getY(); ref.dz = bl0.getZ();
        } catch (e) { }

        // ① 血量兜底（出生后 NW_HP_FIX_MS 内每拍重试，直到确认写成功）
        try {
            if (!ref.hpOk && now < ref.hpFixUntil) {
                if (nwApplyHpOnce(boss, ref.maxHp)) ref.hpOk = true;
            }
        } catch (e) { }

        // ② 名字 / 血量条
        try {
            var cn = '';
            try { cn = '' + boss.getCustomName(); } catch (e) { cn = ''; }
            if (cn.indexOf(ref.name) < 0) boss.setCustomName(ref.name);
        } catch (e) { }
        try { nwBarSync(ref); } catch (e) { }

        var target = null;
        try { target = nwNearestPlayer(ref); } catch (e) { target = null; }

        // ================= 一阶段：巡夜守卫（洞穴蜘蛛）=================
        if (ref.stage === 1) {
            // ③ 主动索敌（洞穴蜘蛛自己会追，这里只是保证「盯的是最近的那个」）
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

            var canCast = (now >= ref.readyAt) && (target != null);

            // · 冷却 7 秒：弹道箭矢（中毒 II / 缓慢 III / 失明 各 3 秒）
            if (canCast && now - ref.cdArrow >= NW_S1_ARROW_CD) {
                ref.cdArrow = now;
                nwFireArrow(ref);
            }
            // · 冷却 9 秒：白色粒子飞天（0.5 秒 20 格）→ 出现在玩家背后
            if (canCast && now - ref.cdSky >= NW_S1_SKY_CD) {
                ref.cdSky = now;
                nwSkyAscend(ref);
            }
        }

        // ================= 二阶段：守夜人（坚守者）=================
        else if (ref.stage === 2) {
            var emerging = (now < ref.emergeUntil);

            // ★ 出场动画期间**不塞索敌目标** ——
            //   `Emerging` 行为的构造器要求 `WALK_TARGET` = VALUE_ABSENT，
            //   塞了目标就会让 Activity.EMERGE 起不来、动画直接没了。
            if (!emerging) {
                try { nwSetTarget(ref, target); } catch (e) { }
                try { nwCalmOthers(ref, target); } catch (e) { }
            }

            var canCast2 = (now >= ref.readyAt) && (target != null) && !emerging;

            // · 冷却 8 秒：拉到身前 + 控视角 1 秒 + 20 点伤害
            if (canCast2 && now - ref.cdGrab >= NW_S2_GRAB_CD) {
                ref.cdGrab = now;
                nwGrabPlayer(ref, target);
            }
            // · 冷却 15 秒：7 只「守夜蝇」
            if (canCast2 && now - ref.cdFly >= NW_S2_FLY_CD) {
                ref.cdFly = now;
                nwSummonFlies(ref);
            }
            // · 冷却 90 秒：「夜深了」（★ 从爬出来开始计时）
            if (!emerging && now >= ref.cdNight) {
                ref.cdNight = now + NW_S2_NIGHT_CD;
                if (target != null) nwNightStrike(ref, target);
            }

            // ④ 「守夜蝇」维护：**防火窗口内**不烧、始终咬最近的玩家
            try {
                for (var fk in ref.flies) {
                    var f = ref.flies[fk];
                    if (f == null || !nwAlive(f)) { delete ref.flies[fk]; continue; }
                    // ★★★ 第八十六批补：灭火**只在防火窗口内**兜底 ——
                    //   窗口 = 召唤时刻 + NW_S2_FLY_FIRE_MS（= 下个召唤技能冷却结束）。
                    //   窗口一过就**不再兜底** ⇒ 幻翼恢复原版「白天会烧」。
                    try {
                        if (now < ref.flyFireUntil && f.getFireTicks() > 0) f.setFireTicks(0);
                    } catch (e) { }
                    if (target == null) continue;
                    var fc = null;
                    try { fc = f.getTarget(); } catch (e) { fc = null; }
                    var need2 = (fc == null);
                    if (!need2) { try { need2 = ('' + fc.getUniqueId()) !== ('' + target.getUniqueId()); } catch (e) { need2 = true; } }
                    if (need2) { try { f.setTarget(target); } catch (e) { } }
                }
            } catch (e) { }
        }

        // ⑤ 继续
        runLater(function () { nwTick(ref); }, NW_TICK);
    } catch (e) {
        try {
            if (ref != null && nwAlive(ref.ent)) runLater(function () { nwTick(ref); }, NW_TICK);
            else nwCleanup(ref);
        } catch (e2) { }
    }
}

// ==================================================================
// 阶段结束 / 收摊
// ==================================================================
// ★★★ 第八十六批补：只清「守夜蝇」这一波（换波时用，**不动**箭矢）
//   返回清掉几只，方便日志 / 测试。
function nwClearFlies(ref) {
    try {
        if (ref == null) return 0;
        var n = 0;
        for (var k in ref.flies) {
            try {
                var f = ref.flies[k];
                if (f != null && nwAlive(f)) { f.remove(); n++; }
            } catch (e) { }
            try { delete ref.flies[k]; } catch (e) { }
        }
        return n;
    } catch (e) { return 0; }
}

function nwPurgeMinions(ref) {
    try {
        if (ref == null) return;
        nwClearFlies(ref);
        try { if (ref.arrow != null && nwAlive(ref.arrow)) ref.arrow.remove(); } catch (e) { }
        try { ref.arrow = null; } catch (e) { }
    } catch (e) { }
}

// ★★★ 掉一个 Slimefun 物品（写法照抄「妄念」那边的 btDropOne）
//   ★ `getSfItemById` 是 RSC 注入的全局函数（本仓库多个脚本都在用）。
//   ★ 取不到物品时**只往后台打一行日志**，不影响收摊流程。
function nwDropOne(world, x, y, z, id, amount) {
    try {
        if (world == null || id == null) return false;
        if (x == null || z == null) return false;

        var stack = null;
        try {
            var sf = getSfItemById(id);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }
        if (stack == null) {
            try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯：取不到 ' + id + '，未掉落'); } catch (e) { }
            return false;
        }
        try { stack.setAmount(amount == null || amount <= 0 ? 1 : amount); } catch (e) { }

        var Loc = Java.type('org.bukkit.Location');
        world.dropItemNaturally(new Loc(world, x, (y == null ? 0 : y) + 0.5, z), stack);
        return true;
    } catch (e) { return false; }
}

// 阶段切换演出（一圈粒子 + 音效，分 6 拍往外扩）
function nwTransFx(world, x, y, z, i, kind) {
    try {
        if (world == null || i >= 6) return;
        var pt = nwP(['SCULK_SOUL', 'SOUL_FIRE_FLAME', 'SMOKE']);
        var pt2 = nwP(['END_ROD', 'WHITE_ASH', 'SNOWFLAKE', 'CLOUD']);
        var frac = i / 6.0;
        var r = 1.0 + frac * 2.6;
        for (var a = 0; a < 12; a++) {
            var ang = a * (Math.PI / 6.0) + frac * 2.0;
            nwFx(world, pt, x + Math.cos(ang) * r, y + 0.6 + frac * 1.5, z + Math.sin(ang) * r, 3, 0.1, 0.2, 0.1, 0.3);
            nwFx(world, pt2, x + Math.cos(ang) * r * 0.7, y + 1.2 + frac * 2.2, z + Math.sin(ang) * r * 0.7, 2, 0.1, 0.2, 0.1, 0.05);
        }
        if (i === 1) { try { nwSound(world, nwLoc(world, x, y, z, 0, 0), 'entity.spider.death', 1.2, 0.7); } catch (e) { } }
        if (i === 3) { try { nwSound(world, nwLoc(world, x, y, z, 0, 0), 'entity.warden.nearby_closer', 1.2, 1.0); } catch (e) { } }
        runLater(function () { nwTransFx(world, x, y, z, i + 1, kind); }, 1);
    } catch (e) { }
}

function nwPhaseEnd(ref) {
    try {
        if (ref == null || ref.transitioning) return;
        ref.transitioning = true;
        nwBarDrop(ref);

        // 正在飞天的链作废（世代号让旧链停手）
        try { ref.skyGen = (ref.skyGen == null ? 0 : ref.skyGen) + 1; } catch (e) { }
        // 本阶段的小怪一并清掉（换阶段不该留着上一阶段的兵）
        try { nwPurgeMinions(ref); } catch (e) { }

        var stage = ref.stage;
        var world = ref.lw;

        // ★★★ 生成位置 = **前一个阶段的死亡位置**
        //   优先级：① 死亡事件写下的**精确**死亡坐标（ref.dx/dy/dz）
        //           ② 主循环每拍记下的**活着的最后位置**（也写进同一组字段）
        //           ③ 都没有（比如 boss 被 remove 掉）⇒ 退回召唤点
        var x, y, z;
        if (ref.dx != null && ref.dy != null && ref.dz != null) {
            x = ref.dx; y = ref.dy; z = ref.dz;
        } else {
            x = (ref.lx == null ? 0 : ref.lx);
            y = (ref.ly == null ? 64 : ref.ly);
            z = (ref.lz == null ? 0 : ref.lz);
        }

        // ★★★ 第八十六批：掉落物
        //   用户原话：「一阶段固定掉落1把月镰」「二阶段掉落6个血夜粒」
        //   ★ 用上面算好的 (x, y, z) —— 那就是**死亡位置**。
        //   ★ 原版掉落已经在死亡事件里清干净了，这里掉的是**我们自己的**物品。
        if (stage === 1) {
            try { nwDropOne(world, x, y, z, NW_DROP_S1_ID, NW_DROP_S1_N); } catch (e) { }
        } else if (stage >= 2) {
            try { nwDropOne(world, x, y, z, NW_DROP_S2_ID, NW_DROP_S2_N); } catch (e) { }
        }

        if (stage >= 2) {
            // 二阶段也打完了 ⇒ 收摊
            nwCleanup(ref);
            return;
        }

        // 一阶段打完 ⇒ 原地「爬出来」二阶段
        nwTransFx(world, x, y, z, 0, 1);
        runLater(function () {
            try { nwNext(ref, 2, world, x, y, z); } catch (e) { }
        }, NW_TRANS_TICKS);
    } catch (e) {
        nwCleanup(ref);
    }
}

// 生成下一阶段（二阶段 = 坚守者「守夜人」，带原版出场动画）
function nwNext(ref, stage, world, x, y, z) {
    try {
        if (ref == null || ref.done) return;
        if (world == null) { nwCleanup(ref); return; }

        var height = (stage === 1 ? 1 : 3);     // 洞穴蜘蛛 1 格、坚守者 3 格
        var sy = nwSafeY(world, x, z, y, height);
        var loc = nwLoc(world, x, sy, z, 0, 0);

        var name, hp, dmg, tag;
        if (stage === 1) { name = NW_S1_NAME; hp = NW_S1_HP; dmg = NW_S1_DMG; tag = 'boss1'; }
        else { name = NW_S2_NAME; hp = NW_S2_HP; dmg = NW_S2_DMG; tag = 'boss2'; }

        var typeNames = (stage === 1 ? ['CAVE_SPIDER'] : ['WARDEN']);
        var ent = nwSpawnMob(world, loc, typeNames, name, hp, dmg, tag, true, ref.owner);
        if (ent == null) { nwCleanup(ref); return; }

        try { ent.setCanPickupItems(false); } catch (e) { }

        ref.stage = stage;
        ref.ent = ent;
        ref.name = name;
        ref.maxHp = hp;

        var now = nwNow();
        ref.hpFixUntil = now + NW_HP_FIX_MS;
        ref.hpOk = false;
        ref.readyAt = now + NW_FIRST_MS;
        ref.transitioning = false;

        if (stage === 1) {
            ref.cdArrow = now - NW_S1_ARROW_CD + NW_FIRST_MS;
            ref.cdSky   = now - NW_S1_SKY_CD + NW_FIRST_MS;
        } else {
            ref.flies = {};
            ref.cdGrab = now - NW_S2_GRAB_CD + NW_FIRST_MS;
            ref.cdFly  = now - NW_S2_FLY_CD + NW_FIRST_MS;

            // ★ 血量 / 伤害再压一次（坚守者自带的上限很高，必须强设）
            try { nwApplyHp(ent, NW_S2_HP); } catch (e) { }
            try { nwSetDmg(ent, NW_S2_DMG); } catch (e) { }

            // ★ 压住「钻地跑路」+「原版冲击波」
            try { nwHoldMemories(ent); } catch (e) { }

            // ★★★ 原版坚守者出场动画（在一阶段的死亡位置「爬出来」）
            var et = nwEmergeTicks();
            var okEmerge = false;
            try { okEmerge = nwEmerge(ent); } catch (e) { }
            if (!okEmerge) {
                // 拿不到 NMS ⇒ 退化成「原地定住这么久」，至少节奏和动画一致
                try {
                    ent.setAI(false);
                    runLater(function () { try { ent.setAI(true); } catch (e) { } }, et);
                } catch (e) { }
            }
            ref.emergeUntil = now + et * 50;
            // ★ 用户：「从地里爬出来开始计时90秒」⇒ 计时从**爬完**开始
            ref.cdNight = ref.emergeUntil + NW_S2_NIGHT_CD;

            try { nwSound(world, loc, 'entity.warden.emerge', 2.0, 1.0); } catch (e) { }
            try { nwSound(world, loc, 'entity.warden.agitated', 2.0, 1.0); } catch (e) { }
            try {
                var pt = nwP(['SCULK_SOUL', 'SOUL_FIRE_FLAME', 'SMOKE']);
                nwFx(world, pt, x, sy + 0.4, z, 50, 0.9, 0.6, 0.9, 0.12);
            } catch (e) { }
        }

        ref.bar = nwBarMake(nwBarTitle(stage));
        try { nwBarSync(ref); } catch (e) { }

        runLater(function () { nwTick(ref); }, NW_TICK);
    } catch (e) { nwCleanup(ref); }
}

function nwCleanup(ref) {
    try {
        if (ref == null || ref.done) return;
        ref.done = true;
        ref.transitioning = false;
        try { nwBarDrop(ref); } catch (e) { }
        try { ref.skyGen = (ref.skyGen == null ? 0 : ref.skyGen) + 1; } catch (e) { }
        try { nwPurgeMinions(ref); } catch (e) { }

        // 万一还留着残兵
        try {
            if (nwAlive(ref.ent)) {
                try { ref.ent.getEquipment().clear(); } catch (e) { }
                try { ref.ent.remove(); } catch (e) { }
            }
        } catch (e) { }
        ref.ent = null;

        // 解锁「这个玩家还能再召唤」
        try {
            if (ref.owner != null && NW_ACTIVE['' + ref.owner] === ref) delete NW_ACTIVE['' + ref.owner];
        } catch (e) { }
    } catch (e) { }
}

// ==================================================================
// 召唤入口
// ==================================================================
function nwSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();

        // 这条线上还有没打完的 → 静默忽略（两阶段全死才能再召）
        var old = NW_ACTIVE[uuid];
        if (old != null && !old.done) return false;

        var world = p.getWorld();
        var base = p.getLocation();
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        var lx = base.getX() + (-Math.sin(rad)) * NW_SPAWN_AHEAD;
        var lz = base.getZ() + (Math.cos(rad)) * NW_SPAWN_AHEAD;
        var ly = nwSafeY(world, lx, lz, base.getY(), 1);
        var loc = nwLoc(world, lx, ly, lz, yaw, 0.0);

        var now = nwNow();
        var ref = {
            owner: uuid,
            stage: 1,
            ent: null,
            name: NW_S1_NAME,
            maxHp: NW_S1_HP,
            bar: null, barNear: {},
            arrow: null,
            flies: {},
            // ★ 这一波守夜蝇的防火截止时刻（0 = 还没召过 ⇒ 不灭火）
            flyFireUntil: 0,
            cdArrow: now - NW_S1_ARROW_CD + NW_FIRST_MS,
            cdSky:   now - NW_S1_SKY_CD + NW_FIRST_MS,
            cdGrab:  now,
            cdFly:   now,
            cdNight: now,
            readyAt: now + NW_FIRST_MS,
            // ★ 飞天链的世代号（换阶段 / 收摊时 +1 让旧链停手）
            skyGen: 0,
            // ★ 死亡位置（死亡事件写精确值，主循环每拍写兜底值）
            dx: null, dy: null, dz: null,
            // ★ 二阶段出场动画的结束时刻（这段时间不索敌、不放技能）
            emergeUntil: 0,
            hpFixUntil: now + NW_HP_FIX_MS,
            hpOk: false,
            transitioning: false,
            done: false,
            lw: world, lx: lx, ly: ly, lz: lz
        };

        var ent = nwSpawnMob(world, loc, ['CAVE_SPIDER'], NW_S1_NAME,
                             NW_S1_HP, NW_S1_DMG, 'boss1', true, uuid);
        if (ent == null) return false;
        ref.ent = ent;

        ref.bar = nwBarMake(nwBarTitle(1));
        try { nwBarSync(ref); } catch (e) { }

        NW_ACTIVE[uuid] = ref;

        try { nwSound(world, loc, 'entity.spider.ambient', 1.0, 0.8); } catch (e) { }
        try { nwSound(world, loc, 'entity.wither.spawn', 0.5, 1.5); } catch (e) { }
        try { nwSound(world, loc, 'entity.warden.nearby_closer', 0.9, 1.4); } catch (e) { }
        try {
            var pt = nwP(['SCULK_SOUL', 'SOUL_FIRE_FLAME', 'SMOKE']);
            nwFx(world, pt, lx, ly + 0.5, lz, 40, 0.6, 0.5, 0.6, 0.15);
        } catch (e) { }

        runLater(function () { nwTick(ref); }, NW_TICK);
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯：召唤出错 ' + e); } catch (e2) { }
        return false;
    }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function nwIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === NW_ITEM_ID) return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === NW_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

function nwIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return nwIsOurItem(hand);
    } catch (e) { return false; }
}

function nwConsume(p) {
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
var NW_REG_DEATH = false;
var NW_REG_SPELL = false;
var NW_REG_GUARD = false;

function nwPlugin() {
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

// ① 清掉落：所有阶段 + 小怪的原版掉落一律清空；顺手记下**精确**死亡位置
function nwRegisterDeath() {
    if (NW_REG_DEATH) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = nwPlugin();
        if (plugin == null) return false;

        var DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent');
        if (DeathEv == null) return false;

        function dh(e) {
            try {
                var ent = e.getEntity();
                if (ent == null) return;
                if (!nwOurs(ent)) return;

                var tag = nwTag(ent);
                var loc = null;
                try { loc = ent.getLocation(); } catch (err) { loc = null; }

                // ★ 原版掉落全部删除：装备 + 背包 + 战利品 + 经验
                try { ent.getEquipment().clear(); } catch (err) { }
                try { var inv = ent.getInventory(); if (inv != null) inv.clear(); } catch (err) { }
                try { e.getDrops().clear(); } catch (err) { }
                try { e.getDrops().clear(); } catch (err) { }   // 清完装备再清一遍
                try { e.setDroppedExp(0); } catch (err) { }

                // ★★ 把**死亡位置**记进这条线 —— 二阶段就生成在这儿
                //    （死亡事件是最准的时机：主循环每 5 刻才跑一拍）
                if (tag === 'boss1' || tag === 'boss2') {
                    try {
                        var r0 = nwRefOf(ent);
                        if (r0 != null && loc != null) {
                            r0.dx = loc.getX(); r0.dy = loc.getY(); r0.dz = loc.getZ();
                        }
                    } catch (err) { }
                }

                // 「守夜蝇」死了 ⇒ 从表里摘掉
                if (tag === 'fly') {
                    try {
                        var r1 = nwRefOf(ent);
                        if (r1 != null) delete r1.flies['' + ent.getUniqueId()];
                    } catch (err) { }
                }
            } catch (err) { }
        }

        try {
            pm.registerEvent(DeathEv, plugin, Priority.HIGHEST,
                function (l, e) { dh(e); }, plugin);
            NW_REG_DEATH = true;
            return true;
        } catch (e1) {
            try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯：死亡掉落拦截注册失败 ' + e1); } catch (e3) { }
            return false;
        }
    } catch (e) { return false; }
}

// ② 弹道命中 / 取消原版冲击波 / 只认最近的玩家
function nwRegisterSpell() {
    if (NW_REG_SPELL) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = nwPlugin();
        if (plugin == null) return false;

        var ok = 0;

        // ②-1 ★ 弹道箭矢命中玩家 ⇒ 中毒 II / 缓慢 III / 失明，各 3 秒
        //      ★ 走 ProjectileHitEvent 而**不是**伤害事件 ——
        //        箭伤被我们设成了 0，0 伤害不一定会派发伤害事件。
        try {
            var HitEv = Java.type('org.bukkit.event.entity.ProjectileHitEvent');
            if (HitEv != null) {
                function hit(e) {
                    try {
                        var proj = null;
                        try { proj = e.getEntity(); } catch (err) { proj = null; }
                        if (proj == null) return;
                        var tag = nwProjTag(proj);
                        if (tag.length === 0) return;
                        if (tag !== 'arrow') return;

                        var he = null;
                        try { he = e.getHitEntity(); } catch (err) { he = null; }
                        if (he != null) {
                            var isPlayer = false;
                            try { isPlayer = ('' + he.getType().name()) === 'PLAYER'; } catch (err) { }
                            if (isPlayer) nwApplyDebuff(he);
                        }
                        var ref = nwRefOf(proj);
                        try { if (ref != null && ref.arrow === proj) ref.arrow = null; } catch (err) { }
                        try { proj.remove(); } catch (err) { }   // 箭插地上就收掉，别留一地箭
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(HitEv, plugin, Priority.HIGHEST, function (l, e) { hit(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-2 ★★★ 「取消原版冲击波」兜底：cause == SONIC_BOOM 且来源是我们这条线的 boss ⇒ 取消
        //      （伤害被取消后 hurtServer 返回 false ⇒ 连击退也不会执行）
        try {
            var DmgEv0 = Java.type('org.bukkit.event.entity.EntityDamageEvent');
            if (DmgEv0 != null) {
                function sonic(e) {
                    try {
                        if (e == null) return;
                        var cause = '';
                        try { cause = '' + e.getCause().name(); } catch (err) { cause = ''; }
                        if (cause.indexOf('SONIC_BOOM') < 0) return;
                        var ds = null;
                        try { ds = e.getDamageSource(); } catch (err) { ds = null; }
                        if (ds == null) return;
                        var de = null, ce = null;
                        try { de = ds.getDirectEntity(); } catch (err) { de = null; }
                        try { ce = ds.getCausingEntity(); } catch (err) { ce = null; }
                        var ours = false;
                        if (de != null && nwTag(de) === 'boss2') ours = true;
                        if (!ours && ce != null && nwTag(ce) === 'boss2') ours = true;
                        if (ours) { try { e.setCancelled(true); } catch (err) { } }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DmgEv0, plugin, Priority.HIGHEST, function (l, e) { sonic(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-3 ★ 「只对最近的玩家有仇恨」兜底：
        //      坚守者想咬的人不是「最近的玩家」⇒ 取消
        //      （主路径是每拍直接写 brain 的 ATTACK_TARGET，这里兜 `Mob#setTarget` 那一路）
        try {
            var TargetEv = Java.type('org.bukkit.event.entity.EntityTargetLivingEntityEvent');
            var TargetEv2 = Java.type('org.bukkit.event.entity.EntityTargetEvent');
            if (TargetEv != null) {
                function tgh(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        if (nwTag(ent) !== 'boss2') return;
                        var tgt = null;
                        try { tgt = e.getTarget(); } catch (err) { tgt = null; }
                        if (tgt == null) return;
                        var ref = nwRefOf(ent);
                        if (ref == null || ref.done) return;
                        var near = nwNearestPlayer(ref);
                        if (near == null) { try { e.setCancelled(true); } catch (err) { } return; }
                        var same = false;
                        try { same = ('' + tgt.getUniqueId()) === ('' + near.getUniqueId()); } catch (err) { same = false; }
                        if (!same) {
                            try { e.setCancelled(true); } catch (err) { }
                            try { e.setTarget(null); } catch (err) { }
                        }
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

        // ②-4 ★ 我们自己射的箭不造成伤害（箭伤 0；这里再兜一层，防别的插件改回去）
        try {
            var DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent');
            if (DmgEv != null) {
                function dmgh(e) {
                    try {
                        var dam = null;
                        try { dam = e.getDamager(); } catch (err) { dam = null; }
                        if (dam == null) return;
                        if (nwProjTag(dam) !== 'arrow') return;
                        try { e.setDamage(0.0); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DmgEv, plugin, Priority.HIGHEST, function (l, e) { dmgh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ②-5 ★ 兜底：我们的实体哪怕真的炸了，也不许动地形
        try {
            var ExplEv = Java.type('org.bukkit.event.entity.EntityExplodeEvent');
            if (ExplEv != null) {
                function exh(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        if (!nwOurs(ent)) return;
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

        // ②-6 ★ 我们的实体不许被别的插件 / 原版挪走（坚守者不会随机传送，只防外因）
        try {
            var ChgEv = Java.type('org.bukkit.event.entity.EntityChangeBlockEvent');
            if (ChgEv != null) {
                function chg(e) {
                    try {
                        var ent = null;
                        try { ent = e.getEntity(); } catch (err) { ent = null; }
                        if (ent == null) return;
                        if (!nwOurs(ent)) return;
                        try { e.setCancelled(true); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(ChgEv, plugin, Priority.HIGHEST, function (l, e) { chg(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        NW_REG_SPELL = (ok > 0);
        if (NW_REG_SPELL) {
            try { getServer().getLogger().info('[构思技艺] 守夜人的提灯：弹道 / 反冲击波 / 索敌限制 已启用（' + ok + ' 条）'); } catch (e) { }
        }
        return NW_REG_SPELL;
    } catch (e) { return false; }
}

// ③ 手持本物品右键方块 → 方块交互一律取消
function nwRegisterGuard() {
    if (NW_REG_GUARD) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = nwPlugin();
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
                if (!nwIsOurItem(h)) return;
                e.setCancelled(true);
            } catch (err) { }
        }

        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST, function (l, e) { handler(e); }, plugin);
            NW_REG_GUARD = true;
            return true;
        } catch (e1) { return false; }
    } catch (e) { return false; }
}

// ==================================================================
// 入口
// ==================================================================
function onUse(event) {
    try {
        try { nwRegisterDeath(); } catch (e) { }    // 惰性重试
        try { nwRegisterSpell(); } catch (e) { }    // 惰性重试
        try { nwRegisterGuard(); } catch (e) { }    // 惰性重试

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        if (!nwIsMainHandUse(event, p)) return;

        var uuid = '' + p.getUniqueId();
        var now = nwNow();
        var last = NW_LAST[uuid];
        if (last != null && (now - (0 + last)) < 500) return;
        NW_LAST[uuid] = now;

        if (!nwSummon(p)) return;
        if (NW_CONSUME) nwConsume(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 守夜人的提灯 onUse 出错: ' + err); } catch (e) { }
    }
}

// ==================================================================
// ★★ 启动自检 —— 把参数打到后台，用来确认"服务器确实加载了这份新脚本"
//    （看不到这几行 = 跑的还是旧脚本 / 压根没加载，要重启服务器）
// ==================================================================
try {
    nwNmsInit();
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 守夜人的提灯 两阶段目标血量：巡夜守卫=' + NW_S1_HP
        + '（洞穴蜘蛛·伤害 ' + NW_S1_DMG + '） 守夜人=' + NW_S2_HP
        + '（坚守者·伤害 ' + NW_S2_DMG + '）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 巡夜守卫：弹道箭矢每 ' + (NW_S1_ARROW_CD / 1000)
        + '秒（箭伤 0，命中给 中毒II / 缓慢III / 失明 各 ' + (NW_S1_DEBUFF_TICKS / 20) + '秒）'
        + ' ｜ 白色粒子飞天每 ' + (NW_S1_SKY_CD / 1000) + '秒 '
        + NW_S1_SKY_BLOCKS + '格 / ' + (NW_S1_SKY_TICKS / 20) + '秒 ⇒ 直接出现在玩家背后');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 守夜人：出场动画 ' + nwEmergeTicks() + ' 刻（原版坚守者 EMERGING）'
        + ' ｜ 拉到身前每 ' + (NW_S2_GRAB_CD / 1000) + '秒（控视角 ' + (NW_S2_GRAB_LOCK_TICKS / 20)
        + '秒 + ' + NW_S2_GRAB_DMG + ' 点伤害）'
        + ' ｜ 守夜蝇每 ' + (NW_S2_FLY_CD / 1000) + '秒 ' + NW_S2_FLY_N
        + '只（幻翼·血 ' + NW_S2_FLY_HP + '·伤害 ' + NW_S2_FLY_DMG
        + '·**整波换新**·防火 ' + (NW_S2_FLY_FIRE_MS / 1000) + '秒）'
        + ' ｜ 夜深了每 ' + (NW_S2_NIGHT_CD / 1000) + '秒（' + NW_S2_NIGHT_DMG + ' 点，从爬出来开始计时）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 守夜人 反制：**取消原版冲击波**（'
        + 'SONIC_BOOM_COOLDOWN 记忆按死 ' + NW_S2_MEM_HOLD + ' 刻 + SONIC_BOOM 伤害取消）'
        + ' ｜ **只对最近的玩家有仇恨**（brain ATTACK_TARGET 每拍改写 + 索敌事件取消 + 清他人愤怒）'
        + ' ｜ 不许钻地跑路（DIG_COOLDOWN 常在）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 守夜人的提灯 掉落：一阶段固定 ' + NW_DROP_S1_N
        + ' 把「月镰」(' + NW_DROP_S1_ID + ')'
        + ' ｜ 二阶段固定 ' + NW_DROP_S2_N + ' 个「血夜粒」(' + NW_DROP_S2_ID + ')'
        + ' ｜ 掉落点 = 各阶段的**死亡位置**');
} catch (e) { }

// ==================================================================
// ★ reload / 重启残留清扫：启动 3 秒后扫一遍全世界，把上一份脚本留下的
//   「我们这条线」的实体（PDC gs:nightwatch / gs:nightwatch_proj）清掉 ——
//   重启之后 ref 表是空的，留着它们只会变成没人管的孤儿。
//   ★★ 但**当前 ref 表里还活着的线**必须放过：否则「刚开服 3 秒内召唤的
//      boss」会被这把扫帚一起扫掉。
// ==================================================================
function nwSweepLeftovers() {
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
                    if (!nwOurs(ent)) continue;
                    // ★★ 正在被管理的线**别动**
                    var own = nwOwnerOf(ent);
                    if (own != null && own.length > 0 && NW_ACTIVE[own] != null) continue;
                    ent.remove();
                    n++;
                } catch (e) { }
            }
        }
        if (n > 0) {
            try { getServer().getLogger().info('[构思技艺] 守夜人的提灯：清掉上一份脚本残留实体 ' + n + ' 个'); } catch (e) { }
        }
    } catch (e) { }
}

try {
    runLater(function () { try { nwSweepLeftovers(); } catch (e) { } }, 60);
} catch (e) { }
