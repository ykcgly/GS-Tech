/* ==========================================================
 * 构思技艺 (GS) —— 「�䷍Ꝑ8�kꙭ#䷎」（**幻翼** boss · 单阶段 · 消耗品）
 * items.yml 里由 script: gs_boss_phantom 引用；入口函数 onUse(event)
 * ----------------------------------------------------------
 * 右键 = 召唤一只 boss（**消耗品**：召唤成功才扣 1 个）
 *
 * ★ **名字**：`\uFFFD\u4DCD\uA7508\uFFFDk\uA66D#\u4DCE`
 *   （前两个 `\uFFFD` 是需求里就带的替换符 —— 原样保留，没有做任何"修复"。
 *     想换名字只改 PH_NAME 一个常量，物品名在 items.yml 里单独写。）
 *
 * ★ **长相 / 体型**：**幻翼（Phantom）**，`PH_SIZE = 5` 体型档。
 *   · 幻翼的基础碰撞箱是 0.9 × 0.5，体型档会再乘 `(1 + 0.15 × size)`
 *   · 再用**原版缩放属性** `Attribute.SCALE`（1.21.3+ 的名字；老名字 GENERIC_SCALE）
 *     把它整体放大到 **约 4.5 宽 × 2.5 高**（≈ 凋零那个量级）
 *     ★ 缩放是**等比**的（原版只提供等比缩放，没有"只改高不改宽"的公开接口），
 *       所以做不到凋零那种 0.9×3.5 的"瘦高"形状。
 *       想要更大/更瘦高：改 `PH_BOX_W`（宽度）即可，缩放值会自动反推：
 *         PH_SCALE = PH_BOX_W / (0.9 × (1 + 0.15 × PH_SIZE))
 *       注意：这只是**视觉+碰撞**同时放大，改完重启服务器就生效。
 *   · 白天不烧（`setShouldBurnInDay(false)`）、不自然消失、不掉原版掉落物
 *
 * ★ **血量 1000**（甲方指定）+ 本服 `MAX_HEALTH` 上限卡 1024 的加固：
 *   走「先确认上限 → 再 setHealth」+ 延迟重试 + 失败降级（按比例缩伤），
 *   和「妄念」同一套逻辑（PH_HP_FIX_MS 窗口内每拍重试）。
 * ★ **普攻 = 原版幻翼伤害**：**不写 ATTACK_DAMAGE 属性** ——
 *   幻翼原版近战伤害就是 `6 + 体型档` ⇒ 本 boss = **11 点**。
 *   （想改成固定值：在 phSummonBoss 里补一句 phSetDmg(ent, 你的值) 即可。）
 *
 * ★ **可视化血条**：一条 BossBar，进度 = 当前血量 / 1000；
 *   默认**只有召唤者本人看得见**（PH_BAR_ONLY_OWNER，改成 false = 范围 64 格内都可见）。
 *
 * ★ **四个技能**（全部数值按需求，冷却单位毫秒）：
 *   ┌ 技能 1「**坚守者冲击波**」冷却 **13 秒**
 *   │   · 完全照**原版坚守者**的口径做：蓄力 **1 秒**（`entity.warden.sonic_charge` +
 *   │     头前方收束的粒子）→ 朝目标射出**一道冲击波**
 *   │   · 伤害 **10 点 = 原版坚守者冲击波伤害**，用**真正的 `DamageType.SONIC_BOOM`**
 *   │     伤害源结算（原版的护甲/附魔绕过关系一并继承，死亡提示也是原版那条）
 *   │   · 射线被方块挡住就**停在墙前**（打不穿墙）；最长 **30 格**
 *   │   · 击退照原版：水平 **2.5**、垂直 **0.5**
 *   ├ 技能 2「**三枚龙息弹**」冷却 **7 秒**
 *   │   · **1 枚瞄准玩家**（瞄准"当前站位"直线飞，**不是追踪**）
 *   │   · **2 枚随机落点**（玩家周围 8 格内随机取点，落点自动贴地）
 *   │   · 用的是**原版 `DragonFireball`** ⇒ 命中后留下的就是**原版龙息**
 *   │     （原版龙息云：半径 3、持续 30 秒、瞬间伤害），伤害也是原版数值
 *   └ 技能 3「**三枚凋零之首**」冷却 **11 秒**
 *       · **1 枚追击玩家**（慢慢转向咬人，急转/绕柱子能甩掉）、**2 枚随机落点**
 *       · **不改伤害** ⇒ 原版凋零之首：命中 **8 点 + 凋零 II 1 秒**
 *       · ★★ **绝不破坏地形**：黑色未蓄力头 + `setYield(0)` + `setIsIncendiary(false)`，
 *         再叠一层**真保险** —— 监听 `EntityExplodeEvent` **清空待破坏方块列表**
 *         （原版凋零头的爆炸威力是硬编码的，光设 setYield 兜不住，见 phOnExplode 注释）
 *       · 飞 **10 秒**后自毁（`remove()` 不触发爆炸，不会留残留）
 *   ┌ 技能 4「**投掷三叉戟**」冷却 **5 秒**
 *   │   · 朝玩家扔一枚**真三叉戟**（原版 `Trident` 弹体，`setDamage(8)` = **原版溺尸投掷伤害**）
 *   │   · **在三叉戟的落点**召唤一只**原版闪电苦力怕**（`Creeper#setPowered(true)`）
 *   │   · ★ 闪电苦力怕**不会被 boss 的技能打到**（伤害事件里按阵营免伤，见 phOnDamageByEntity）
 *   │   · 苦力怕**最多同时 PH_PET_MAX(4) 只**，出生 **60 秒**后自动消失（防卡服）
 *   │   · ★★ 第六十三批：苦力怕的爆炸**不再破坏任何方块**（只炸人、不掉方块）。
 *   │     用户原话：「第三格boss生成的闪电苦力怕删除破坏方块功能」。
 *   │     做法：原来的 `PH_PET_GRIEF` 开关**整个删掉**（不是改成 false），
 *   │     苦力怕的爆炸**无条件**走 `phOnExplode` 清空待破坏方块列表
 *   │     ⇒ 没有任何"再打开"的口子。
 *   ├ 技能 5「**蓄力冲刺撞击**」冷却 **17 秒**（★ 从**开始蓄力**那一刻算起）
 *   │   · 蓄力 **1.5 秒**（`PH_DA_CHARGE = 30` 刻），期间放 **3 次烟花音效**
 *   │     （`entity.firework_rocket.launch`，每 0.5 秒一次，音高递增）
 *   │   · 蓄力结束后朝「**玩家那一刻所在的位置**」**直线冲刺**
 *   │     （`PH_DA_SPEED = 2.2` 格/刻 = **44 格/秒**，玩家跑速只有 5.6 ⇒ 基本跑不掉）
 *   │   · **命中玩家** ⇒ **55 点伤害**（伤害源走 `DamageType.MACE_SMASH`）
 *   │     + **重锤猛击音效**（`item.mace.smash_ground_heavy`）+ 击退；
 *   │   · **未命中 / 撞墙** ⇒ 什么都不发生
 *   └ （技能每次只放一个；AI 心跳 5 刻，五个冷却各自独立走）
 *
 * ★ **阵营**：boss / 闪电苦力怕 / 自家弹体（凋零之首、三叉戟、龙息云）之间**互相免伤**，
 *   也不会互相索敌 —— 免得 boss 一炮把自己刚召唤的苦力怕打死。
 * ★ **boss 死了**：清空原版掉落（幻翼膜）与经验，血条收掉，召唤者可以再召下一个。
 * ★ **每个玩家同时只能有 1 只**活着的这个 boss（再右键静默忽略），防连点刷爆。
 * ★ **不能与方块交互**：手持本物品右键方块时，方块功能一律取消（HIGHEST 监听器），
 *   但**照样召唤 boss** —— 和「妄念」「冰」同一个套路。
 * ★ 打的是 `gs:boss` 标记（**不是** gs:summon）⇒ 梦境护腿的杀戮光环**照常能打到它**。
 *
 * ----------------------------------------------------------
 * ★ 排查用自检：加载时会往后台打一行「幻翼boss 参数」，游戏里如果行为不对，
 *   先把那行日志发出来（看不到 = 服务器跑的还是旧脚本，要**完整重启**）。
 * ★ 出生后会把**实际碰撞箱**打进后台（期望 4.50 × 2.50）—— 对得上就说明缩放属性生效了。
 * ========================================================== */

try { getServer().getLogger().info('[构思技艺] gs_boss_phantom 已加载'); } catch (e) { }

// ==================================================================
// 常量
// ==================================================================
var PH_ITEM_ID     = 'GS_PHANTOM';
var PH_CONSUME     = true;        // ★ 消耗品：召唤成功才扣 1 个
var PH_SPAWN_AHEAD = 4.0;         // 在玩家前方 4 格、上方 3 格生成（幻翼是飞行的）
var PH_SPAWN_UP    = 3.0;
var PH_KEYNAME     = 'boss_phantom';   // PDC 标记键名（→ `gs:boss_phantom`，用 NamespacedKey.fromString 建）
var PH_DROP_XP     = 120000;      // ★ 击杀掉落的经验值（第十三批修订：0 → 120000）

// ★★ 名字：前两个 \uFFFD 是需求原文里就带的替换符，原样保留
var PH_NAME        = '\uFFFD\u4DCD\uA7508\uFFFDk\uA66D#\u4DCE';

var PH_HP          = 1000.0;      // 血量（本服实体上限 1024 ⇒ 1000 写得进去）
var PH_SIZE        = 5;           // 幻翼体型档（影响模型 & 基础碰撞箱 ×(1+0.15×size)）
var PH_BOX_W       = 4.5;         // ★ 期望碰撞箱宽度（格）；缩放值按它反推
var PH_SCALE       = PH_BOX_W / (0.9 * (1.0 + 0.15 * PH_SIZE));   // ⇒ ≈ 2.857
var PH_TICK        = 5;           // AI 心跳：每 5 刻（0.25 秒）
var PH_FIRST_MS    = 4000;        // ★ 出场 4 秒内**不放技能**（刚召唤就放玩家反应不过来）
var PH_SEEK        = 48.0;        // 找目标的范围
var PH_HP_FIX_MS   = 6000;        // 出生后 6 秒内每拍重试血量上限
var PH_CREATIVE_FALLBACK = true;  // 场上没有非创造玩家时，是否允许打创造模式
var PH_UNSTICK_MS  = 4000;        // ★ 原地不动超过这么久（且目标很远）⇒ 防卡位救援
var PH_UNSTICK_GAP = 10000;       // 两次救援之间的最小间隔

// ---- 技能 1：坚守者冲击波 ----
var PH_SB_CD       = 13000;       // 冷却 13 秒
var PH_SB_CHARGE   = 20;          // 蓄力 20 刻 = 1 秒
var PH_SB_DMG      = 10.0;        // ★ 原版坚守者冲击波伤害 = 10 点
var PH_SB_RANGE    = 30.0;        // 射线最长 30 格（原版口径）
var PH_SB_HIT_R    = 1.0;         // 沿射线每格的判定半径
var PH_SB_KB       = 2.5;         // 水平击退（原版）
var PH_SB_KB_UP    = 0.5;         // 垂直击退（原版）

// ---- 技能 2：三枚龙息弹 ----
var PH_DF_CD       = 7000;        // 冷却 7 秒
var PH_DF_N        = 3;           // 一共几枚
var PH_DF_RAND     = 2;           // 其中几枚随机落点（剩下的瞄准玩家）
var PH_DF_SPREAD   = 8.0;         // 随机落点的散布半径
var PH_DF_SPEED    = 1.1;         // 弹速（原版龙息弹约 1.0）

// ---- 技能 3：三枚凋零之首 ----
var PH_WS_CD       = 11000;       // 冷却 11 秒
var PH_WS_N        = 3;           // 一共几枚
var PH_WS_RAND     = 2;           // 其中几枚随机落点
var PH_WS_SPREAD   = 8.0;         // 随机落点的散布半径
var PH_WS_SPEED    = 1.0;         // 弹速
var PH_WS_TURN     = 0.22;        // ★ 追击头的转向系数（越大越粘人；0 = 直飞）
var PH_WS_GUIDE_T  = 2;           // 每 2 刻修正一次方向
var PH_WS_LIFE     = 200;         // 10 秒后自毁（remove 不爆炸，不留残留）

// ---- 技能 4：投掷三叉戟 + 落点召唤闪电苦力怕 ----
var PH_TR_CD       = 5000;        // 冷却 5 秒
var PH_TR_SPEED    = 1.6;         // 投掷速度
var PH_TR_DMG      = 8.0;         // ★ 原版溺尸投掷伤害 = 8 点
var PH_TR_LIFE     = 120;         // 6 秒还没落地 ⇒ 按当前位置当落点兜底
// ★ 弹道补偿：三叉戟是箭类弹体（有重力 0.05 格/刻²）⇒ 远距离会往下掉。
//   按「水平距离 ÷ 速度」估飞行时间，把落点抬高 ½·g·t²，再乘这个系数（1.0 = 精确补偿）。
var PH_TR_GRAV     = 0.05;
var PH_TR_LEAD     = 1.0;
var PH_TR_LEAD_CAP = 12.0;        // 抬高的上限（格），防止超远距离抬到天上
var PH_PET_MAX     = 4;           // 场上最多几只闪电苦力怕
var PH_PET_LIFE    = 1200;        // 60 秒后自动消失（防卡服）
// ★★ 第六十三批：`PH_PET_GRIEF` 常量已**删除**（用户「第三格boss生成的闪电苦力怕
//    删除破坏方块功能」）。苦力怕的爆炸现在**无条件**不掉方块，见 phIsOurExplosive。

// ---- 技能 5：蓄力冲刺撞击 ----
//   ★ 2026-10-09 第十三批新增。冷却**从开始蓄力那一刻**算起（17 秒 = 蓄力 1.5 秒 + 15.5 秒空窗）。
var PH_DA_CD       = 17000;       // 冷却 17 秒
var PH_DA_CHARGE   = 30;          // 蓄力 30 刻 = 1.5 秒
var PH_DA_FW_TIMES = 3;           // 蓄力期间放 3 次**烟花音效**（1.5 秒内 3 次，每 0.5 秒一次）
var PH_DA_SPEED    = 2.2;         // 冲刺速度（格/刻）⇒ 44 格/秒（玩家跑速 5.6 格/秒）
var PH_DA_DMG      = 55.0;        // ★ 命中伤害 55 点
var PH_DA_HIT_R    = 2.5;         // 命中判定半径（水平，格）—— boss 体型 4.5 宽，给 2.5
var PH_DA_HIT_V    = 2.5;         // 命中判定垂直容差（格）
var PH_DA_MAX      = 64.0;        // 单次冲刺最长距离（格）

// ---- 血条 ----
var PH_BAR           = true;
var PH_BAR_ONLY_OWNER = true;     // true = 只有召唤者本人看得到
var PH_BAR_RANGE     = 64.0;

// ==================================================================
// 状态
// ==================================================================
var PH_ACTIVE = {};    // 玩家 uuid -> ref
var PH_LAST   = {};    // 玩家 uuid -> 上次召唤时间戳（防同一次点击被派发两遍）
var PH_PETS   = {};    // 苦力怕 uuid -> { e: 实体, at: 时间戳 }
var PH_KEY    = null;  // NamespacedKey
var PH_PDT    = null;  // PersistentDataType
var PH_MAXATTR = null;

// 标记值：boss=本体 / pet=闪电苦力怕 / skull=凋零之首 / trident=三叉戟
var PH_V_BOSS = 'boss';
var PH_V_PET  = 'pet';
var PH_V_SKULL = 'skull';
var PH_V_TRIDENT = 'trident';

// ==================================================================
// 工具
// ==================================================================
function phNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function phAlive(e) {
    try { if (e == null) return false; return !(e.isDead() || !e.isValid()); }
    catch (e2) { return false; }
}

function phPlugin() {
    try {
        var p = null;
        try { p = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { p = null; }
        if (p == null) { try { p = getServer().getPluginManager().getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (p == null) { try { p = getServer().getPluginManager().getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (p == null) { try { p = getServer().getPluginManager().getPlugin('Slimefun'); } catch (e) { } }
        return p;
    } catch (e) { return null; }
}

function phTagOf(e) {
    try {
        if (e == null || PH_KEY == null || PH_PDT == null) return '';
        var v = e.getPersistentDataContainer().get(PH_KEY, PH_PDT.STRING);
        return (v == null ? '' : '' + v);
    } catch (err) { return ''; }
}

function phSetTag(e, val) {
    try {
        if (e == null || PH_KEY == null || PH_PDT == null) return;
        e.getPersistentDataContainer().set(PH_KEY, PH_PDT.STRING, val);
    } catch (err) { }
}

function phIsBoss(e)   { return phTagOf(e) === PH_V_BOSS; }
// ★ 「自己人」= 本体 + 闪电苦力怕（弹体不算，弹体只用来认归属）
function phIsFriend(e) { var t = phTagOf(e); return t === PH_V_BOSS || t === PH_V_PET; }

function phDist(a, b) {
    try { return a.getLocation().distance(b.getLocation()); } catch (e) { return 999.0; }
}

function phP(cands) {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < cands.length; i++) {
            try { var p = P[cands[i]]; if (p != null) return p; } catch (e) { }
            try { var p2 = P.valueOf(cands[i]); if (p2 != null) return p2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function phFx(world, pt, x, y, z, n, dx, dy, dz, speed) {
    try {
        if (world == null || pt == null) return;
        world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
    } catch (e) { }
}

function phSound(world, loc, name, vol, pitch) {
    try {
        try { world.playSound(loc, name, vol, pitch); }
        catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); } catch (e2) { } }
    } catch (e) { }
}

function phET(names) {
    try {
        var ET = Java.type('org.bukkit.entity.EntityType');
        for (var i = 0; i < names.length; i++) {
            try { var t = ET[names[i]]; if (t != null) return t; } catch (e) { }
            try { var t2 = ET.valueOf(names[i]); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// 方块的通行判定
function phPassableAt(world, bx, by, bz) {
    try {
        var Loc = Java.type('org.bukkit.Location');
        var blk = world.getBlockAt(new Loc(world, 0 + bx, 0 + by, 0 + bz));
        if (blk == null) return true;
        var mt = null;
        try { mt = blk.getType(); } catch (e) { mt = null; }
        if (mt != null) { try { if (mt.isAir()) return true; } catch (e) { } }
        try { return !!blk.isPassable(); } catch (e) { }
        if (mt != null) { try { return !mt.isSolid(); } catch (e) { } }
        return true;
    } catch (e) { return true; }
}

// 在 (x, z) 附近找一个"能站"的地面高度（返回地面上一格）
function phGroundY(world, x, z, y0) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z);
        var start = Math.floor(y0);
        var lo = start - 6, hi = start + 8;
        if (lo < -60) lo = -60;
        if (hi > 250) hi = 250;
        var y;
        for (y = start + 4; y >= lo; y--) {
            if (phPassableAt(world, bx, y, bz) && !phPassableAt(world, bx, y - 1, bz)) return y;
        }
        for (y = start; y <= hi; y++) {
            if (phPassableAt(world, bx, y, bz)) return y;
        }
        return y0;
    } catch (e) { return y0; }
}

// ==================================================================
// 属性 / 血量
// ==================================================================
function phAttrKey(names) {
    try {
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        for (var i = 0; i < names.length; i++) {
            try { var k = Attr[names[i]]; if (k != null) return k; } catch (e) { }
            try { var k2 = Attr.valueOf(names[i]); if (k2 != null) return k2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ≥1.21.3 = MAX_HEALTH；≤1.21.2 = GENERIC_MAX_HEALTH
function phFindMaxAttr() {
    try {
        if (PH_MAXATTR != null) return PH_MAXATTR;
        var k = phAttrKey(['MAX_HEALTH', 'GENERIC_MAX_HEALTH']);
        if (k != null) { PH_MAXATTR = k; return k; }
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var all = Attr.values();
        for (var i = 0; i < all.length; i++) {
            var a = all[i];
            var nm = '', key = '';
            try { nm = '' + a.name(); } catch (e) { nm = ''; }
            try { key = '' + a.getKey().getKey(); } catch (e) { key = ''; }
            if (nm.indexOf('MAX_HEALTH') >= 0 || key.indexOf('max_health') >= 0) {
                PH_MAXATTR = a;
                return a;
            }
        }
    } catch (e) { }
    return null;
}

function phSetDmg(ent, want) {
    try {
        var k = phAttrKey(['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE']);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        var cur = inst.getValue();
        var base = inst.getBaseValue();
        inst.setBaseValue(base + (want - cur));
        return true;
    } catch (e) { return false; }
}

function phForceMax(ent, hp) {
    var ok = false;
    try {
        var k = phFindMaxAttr();
        if (k != null) {
            var inst = ent.getAttribute(k);
            if (inst != null) {
                try {
                    var AL = Java.type('java.util.ArrayList');
                    var snap = new AL(inst.getModifiers());
                    var it = snap.iterator();
                    while (it.hasNext()) { try { inst.removeModifier(it.next()); } catch (e) { } }
                } catch (e) { }
                try { inst.setBaseValue(hp); ok = true; } catch (e) { }
            }
        }
    } catch (e) { }
    try { ent.setMaxHealth(hp); ok = true; } catch (e) { }
    return ok;
}

function phMaxHp(ent) {
    try {
        var k = phFindMaxAttr();
        if (k == null) return 0;
        var inst = ent.getAttribute(k);
        if (inst == null) return 0;
        return inst.getValue();
    } catch (e) { return 0; }
}

var PH_HP_DIAG = {};
function phHpDiag(ent, want, max, cur, why) {
    try {
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { id = 'unknown'; }
        if (PH_HP_DIAG[id] != null) return;
        PH_HP_DIAG[id] = 1;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { tn = '?'; }
        try {
            getServer().getLogger().warning('[构思技艺] 幻翼boss 血量设置失败(' + why + ')：实体='
                + tn + ' 目标=' + want + ' 实际上限=' + max + ' 实际血量=' + cur);
        } catch (e2) { }
    } catch (e) { }
}

function phApplyHpOnce(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return false;
        phForceMax(ent, hp);
        var mx = phMaxHp(ent);
        if (mx < hp - 0.5) { phHpDiag(ent, hp, mx, -1, '上限没上去'); return false; }
        try { ent.setHealth(hp); } catch (e) { }
        var now = -1;
        try { now = ent.getHealth(); } catch (e) { now = -1; }
        if (now >= hp - 0.5) return true;
        phHpDiag(ent, hp, mx, now, '血量没回满');
        return false;
    } catch (e) { return false; }
}

function phApplyHp(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return;
        if (phApplyHpOnce(ent, hp)) return;
        var delays = [1, 3, 6, 10, 20, 40];
        for (var i = 0; i < delays.length; i++) {
            runLater(function (e0, h0) {
                return function () {
                    try { if (!phAlive(e0)) return; phApplyHpOnce(e0, h0); } catch (e) { }
                };
            }(ent, hp), delays[i]);
        }
    } catch (e) { }
}

// ★★ 碰撞体积：原版缩放属性（Attribute.SCALE）
//   幻翼最终尺寸 = Phanom基础(0.9×0.5) × (1 + 0.15×size) × 缩放值
//   写完属性**立刻 refreshDimensions()**（NMS 上是 public，直接调），
//   否则要等实体自己想起来刷新，血条都出来了箱子还没变大。
//   每一步都包 try/catch：万一取不到 handle，也只是"没放大"，不会崩。
function phApplyScale(ent) {
    try {
        var k = phAttrKey(['SCALE', 'GENERIC_SCALE']);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        var cur = -1.0;
        try { cur = inst.getBaseValue(); } catch (e) { cur = -1.0; }
        // ★ 已经是目标值 ⇒ 直接返回：**不每拍调 refreshDimensions**（没必要地折腾碰撞箱）
        if (Math.abs(cur - PH_SCALE) <= 0.001) return true;
        try { inst.setBaseValue(PH_SCALE); } catch (e) { }
        try { ent.getHandle().refreshDimensions(); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// 把实际碰撞箱打进后台（对得上 = 缩放真的生效了）
function phLogBox(ent) {
    try {
        var w = -1, h = -1;
        try { w = ent.getWidth(); } catch (e) { w = -1; }
        try { h = ent.getHeight(); } catch (e) { h = -1; }
        getServer().getLogger().info('[构思技艺] 幻翼boss 实际碰撞箱：宽=' + w + ' 高=' + h
            + '（期望 ' + PH_BOX_W + ' × ' + (0.5 * (1.0 + 0.15 * PH_SIZE) * PH_SCALE).toFixed(2)
            + '；缩放值=' + PH_SCALE.toFixed(3) + ' 体型档=' + PH_SIZE + '）');
    } catch (e) { }
}

// ==================================================================
// 血条
// ==================================================================
function phBarMake(title) {
    if (!PH_BAR) return null;
    try {
        var BarColor = Java.type('org.bukkit.boss.BarColor');
        var BarStyle = Java.type('org.bukkit.boss.BarStyle');
        var c = null, s = null;
        try { c = BarColor.RED; } catch (e) { c = null; }
        if (c == null) { try { c = BarColor.valueOf('RED'); } catch (e2) { } }
        try { s = BarStyle.SEGMENTED_20; } catch (e) { s = null; }
        if (s == null) { try { s = BarStyle.SEGMENTED_10; } catch (e2) { } }
        if (s == null) { try { s = BarStyle.valueOf('SOLID'); } catch (e2) { } }
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(title, c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setVisible(true); } catch (e) { }
        try { bar.setProgress(1.0); } catch (e) { }
        return bar;
    } catch (e) { return null; }
}

function phBarSync(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        var ent = ref.ent;
        var alive = phAlive(ent);

        var prog = 0.0;
        if (alive) {
            try {
                var hp = ent.getHealth();
                var mx = phMaxHp(ent);
                if (mx <= 0) mx = ref.maxHp;
                prog = hp / (mx > 0 ? mx : 1);
            } catch (e) { prog = 0.0; }
        }
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
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
                    if (PH_BAR_ONLY_OWNER) {
                        try { if ('' + p.getUniqueId() !== '' + ref.owner) continue; } catch (e) { continue; }
                    }
                    try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    if (pl.distance(el) > PH_BAR_RANGE) continue;
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

function phBarDrop(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        try { ref.bar.setVisible(false); } catch (e) { }
        var k;
        for (k in ref.barNear) {
            try { ref.bar.removePlayer(ref.barNear[k]); } catch (e) { }
            try { delete ref.barNear[k]; } catch (e) { }
        }
        try { ref.bar.removeAll(); } catch (e) { }
        ref.bar = null;
    } catch (e) { }
}

// ==================================================================
// 生成
// ==================================================================
function phTarget(ref) {
    try {
        var ent = ref.ent;
        if (!phAlive(ent)) return null;
        var world = ent.getWorld();
        var wname = '' + world.getName();
        var el = ent.getLocation();

        var best = null, bestD = 1e9;     // 非创造 / 非旁观
        var fall = null, fallD = 1e9;     // 创造（兜底）
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if (!p.isOnline()) continue; } catch (e) { continue; }
            try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
            var gm = '';
            try { gm = '' + p.getGameMode().name(); } catch (e) { gm = ''; }
            if (gm.indexOf('SPECTATOR') >= 0) continue;
            var d = 1e9;
            try { d = p.getLocation().distance(el); } catch (e) { d = 1e9; }
            if (d > PH_SEEK) continue;
            if (gm.indexOf('CREATIVE') >= 0) {
                if (d < fallD) { fallD = d; fall = p; }
            } else {
                if (d < bestD) { bestD = d; best = p; }
            }
        }
        if (best != null) return best;
        if (PH_CREATIVE_FALLBACK) return fall;
        return null;
    } catch (e) { return null; }
}

function phSpawnBoss(p) {
    try {
        var world = p.getWorld();
        var base = p.getLocation();
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;

        var Loc = Java.type('org.bukkit.Location');
        var lx = base.getX() + (-Math.sin(rad)) * PH_SPAWN_AHEAD;
        var lz = base.getZ() + (Math.cos(rad)) * PH_SPAWN_AHEAD;
        var ly = base.getY() + PH_SPAWN_UP;
        // ★ 别生成在天花板里（幻翼会卡在里面挨窒息伤害）⇒ 往上找一个通透的高度
        try {
            for (var up = 0; up <= 8; up++) {
                if (phPassableAt(world, Math.floor(lx), Math.floor(ly + up), Math.floor(lz))) {
                    ly = ly + up;
                    break;
                }
            }
        } catch (e) { }
        var loc = new Loc(world, lx, ly, lz, yaw, 20.0);

        var t = phET(['PHANTOM']);
        if (t == null) {
            try { getServer().getLogger().warning('[构思技艺] 幻翼boss：找不到实体类型 PHANTOM'); } catch (e) { }
            return null;
        }
        var e = world.spawnEntity(loc, t);
        if (e == null) return null;

        // ① 先定体型（setSize 会触发一次 refreshDimensions，必须在写缩放之前）
        try { if (typeof e.setSize === 'function') e.setSize(PH_SIZE); } catch (err) { }
        // ② 再写缩放属性 + 立刻刷新碰撞箱
        try { phApplyScale(e); } catch (err) { }

        // ③ 血量：走 phApplyHp（多次写入 + 延迟重试）
        phApplyHp(e, PH_HP);
        // ★ 普攻**不写属性** ⇒ 保持原版幻翼伤害（6 + 体型档）

        try { e.setCustomName(PH_NAME); } catch (err) { }
        try { e.setCustomNameVisible(true); } catch (err) { }
        try { e.setRemoveWhenFarAway(false); } catch (err) { }
        try { e.setPersistent(true); } catch (err) { }
        try { e.setCanPickupItems(false); } catch (err) { }
        try { e.setAware(true); } catch (err) { }
        try { if (typeof e.setShouldBurnInDay === 'function') e.setShouldBurnInDay(false); } catch (err) { }
        try { e.setSilent(false); } catch (err) { }

        // 不穿装备。
        // ★★ 关于「不掉幻翼膜」：1.21.11 里 **`LootTables.EMPTY` 这个常量已经不存在**，
        //    `Bukkit.getLootTable('minecraft:empty')` 也取不到（本体 1358 张表里没有 empty）
        //    ⇒ **「把战利品表置空」这条路在 1.21.11 走不通**（旧代码里那句是静默失败的）。
        //    幻翼膜来自战利品表 `entities/phantom`，**唯一可靠的做法**是在
        //    EntityDeathEvent 里 `e.getDrops().clear()`（见下面的 dth）。
        try {
            var eq = e.getEquipment();
            if (eq != null) { try { eq.clear(); } catch (err2) { } }
        } catch (err) { }

        phSetTag(e, PH_V_BOSS);
        return e;
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 幻翼boss：生成出错 ' + err); } catch (e2) { }
        return null;
    }
}

function phSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();

        // 这条线还有活着的 ⇒ 静默忽略（每人同时只能有一只）
        var old = PH_ACTIVE[uuid];
        if (old != null && !old.done && phAlive(old.ent)) return false;

        var ent = phSpawnBoss(p);
        if (ent == null) return false;

        var now = phNow();
        var loc = ent.getLocation();
        var ref = {
            owner: uuid,
            ent: ent,
            maxHp: PH_HP,
            mode: 'idle',
            sonic: null,
            dash: null,
            cd1: now - PH_SB_CD + PH_FIRST_MS,   // 出场保护期内不放技能
            cd2: now - PH_DF_CD + PH_FIRST_MS,
            cd3: now - PH_WS_CD + PH_FIRST_MS,
            cd4: now - PH_TR_CD + PH_FIRST_MS,
            cd5: now - PH_DA_CD + PH_FIRST_MS,
            readyAt: now + PH_FIRST_MS,
            hpFixUntil: now + PH_HP_FIX_MS,
            hpOk: false,
            hpScale: 1.0,
            bar: null, barNear: {},
            lastX: loc.getX(), lastZ: loc.getZ(), lastT: now,
            unstickAt: 0,
            lw: loc.getWorld(), lx: loc.getX(), ly: loc.getY(), lz: loc.getZ(),
            done: false
        };

        ref.bar = phBarMake(PH_NAME);
        try { phBarSync(ref); } catch (e) { }

        PH_ACTIVE[uuid] = ref;

        try { phSound(loc.getWorld(), loc, 'entity.phantom.ambient', 1.6, 0.6); } catch (e) { }
        try { phSound(loc.getWorld(), loc, 'entity.wither.spawn', 0.5, 1.5); } catch (e) { }
        try {
            var pt = phP(['SCULK_SOUL', 'SOUL', 'SMOKE_LARGE', 'LARGE_SMOKE']);
            phFx(loc.getWorld(), pt, loc.getX(), loc.getY(), loc.getZ(), 40, 1.2, 0.8, 1.2, 0.05);
        } catch (e) { }

        // 出生 2 秒后把实际碰撞箱打到后台（确认缩放生效）
        runLater(function () { try { if (phAlive(ent)) phLogBox(ent); } catch (e) { } }, 40);

        runLater(function () { phTick(ref); }, PH_TICK);
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 幻翼boss：召唤出错 ' + e); } catch (e2) { }
        return false;
    }
}

// ==================================================================
// AI 主循环
// ==================================================================
function phUnstick(ref) {
    try {
        var l = ref.ent.getLocation();
        var moved = 0.0;
        var dx = l.getX() - ref.lastX, dz = l.getZ() - ref.lastZ;
        moved = Math.sqrt(dx * dx + dz * dz);
        if (moved > 0.75) {
            ref.lastX = l.getX(); ref.lastZ = l.getZ(); ref.lastT = phNow();
            return;
        }
        var now = phNow();
        if (now - ref.lastT < PH_UNSTICK_MS) return;
        if (now - (ref.unstickAt == null ? 0 : ref.unstickAt) < PH_UNSTICK_GAP) { ref.lastT = now; return; }

        var tgt = phTarget(ref);
        if (tgt == null) { ref.lastT = now; return; }
        if (phDist(ref.ent, tgt) < 6.0) { ref.lastT = now; return; }

        // 救援：挪到目标头顶上方 6 格的空中（幻翼本来就在天上飞）
        var tl = tgt.getLocation();
        var Loc = Java.type('org.bukkit.Location');
        var dst = new Loc(tl.getWorld(), tl.getX(), tl.getY() + 6.0, tl.getZ(), l.getYaw(), 0.0);
        try { ref.ent.teleport(dst); } catch (e) { }
        ref.unstickAt = now;
        ref.lastT = now;
        ref.lastX = dst.getX(); ref.lastZ = dst.getZ();
        try { getServer().getLogger().info('[构思技艺] 幻翼boss 触发防卡位救援（原地不动超过 '
            + (PH_UNSTICK_MS / 1000) + ' 秒）'); } catch (e) { }
    } catch (e) { }
}

function phTick(ref) {
    try {
        if (ref == null || ref.done) return;

        // ① 本体没了 ⇒ 收摊
        if (!phAlive(ref.ent)) { phCleanup(ref); return; }

        // 记住位置
        try {
            var l0 = ref.ent.getLocation();
            ref.lw = l0.getWorld();
            ref.lx = l0.getX(); ref.ly = l0.getY(); ref.lz = l0.getZ();
        } catch (e) { }

        // ② 血条
        try { phBarSync(ref); } catch (e) { }

        // ③ 血量 / 缩放保护（出生后 PH_HP_FIX_MS 窗口内每拍重试）
        try {
            var fx = ref.hpFixUntil;
            if (!ref.hpOk && fx != null && phNow() <= fx) {
                var mx0 = phMaxHp(ref.ent);
                var cur0 = -1;
                try { cur0 = ref.ent.getHealth(); } catch (e) { cur0 = -1; }
                var maxBad = (mx0 < ref.maxHp - 0.5);
                var hpBad = (cur0 >= 0 && cur0 < ref.maxHp * 0.5);
                if (maxBad || hpBad) {
                    if (phApplyHpOnce(ref.ent, ref.maxHp)) {
                        ref.hpOk = true; ref.hpScale = 1.0;
                    } else if (maxBad && mx0 > 0) {
                        ref.hpScale = mx0 / ref.maxHp;   // ★ 降级：按比例缩伤 ⇒ 等效血量不变
                    }
                } else {
                    ref.hpOk = true; ref.hpScale = 1.0;
                }
            }
        } catch (e) { }

        // ④ 缩放 / 白天不烧 兜底（每拍确认一次，被别的插件改了也能拉回来）
        try { phApplyScale(ref.ent); } catch (e) { }
        try { if (typeof ref.ent.setShouldBurnInDay === 'function') ref.ent.setShouldBurnInDay(false); } catch (e) { }

        // ⑤ 防卡位
        try { phUnstick(ref); } catch (e) { }

        // ⑥ 演出中 ⇒ 本拍不放新技能
        if (ref.mode != null && ref.mode !== 'idle') {
            runLater(function () { phTick(ref); }, PH_TICK);
            return;
        }

        var now = phNow();
        if (now < ref.readyAt) {                       // 出场保护期
            runLater(function () { phTick(ref); }, PH_TICK);
            return;
        }

        var tgt = phTarget(ref);
        if (tgt != null) {
            var d = phDist(ref.ent, tgt);
            var started = false;      // ★ 同一拍只开一个技能

            // 技能 1：坚守者冲击波（13 秒）
            if (!started && d <= PH_SB_RANGE && (now - ref.cd1) >= PH_SB_CD) {
                ref.cd1 = now; started = true;
                phSonicStart(ref, tgt);
            }
            // 技能 2：三枚龙息弹（7 秒）
            if (!started && d <= PH_SEEK && (now - ref.cd2) >= PH_DF_CD) {
                ref.cd2 = now; started = true;
                phDragonBreath(ref, tgt);
            }
            // 技能 3：三枚凋零之首（11 秒）
            if (!started && d <= PH_SEEK && (now - ref.cd3) >= PH_WS_CD) {
                ref.cd3 = now; started = true;
                phWitherSkulls(ref, tgt);
            }
            // 技能 4：投掷三叉戟 + 落点召唤闪电苦力怕（5 秒）
            if (!started && d <= PH_SEEK && (now - ref.cd4) >= PH_TR_CD) {
                ref.cd4 = now; started = true;
                phTrident(ref, tgt);
            }
            // 技能 5：蓄力冲刺撞击（17 秒 · ★ 冷却从蓄力开始时算）
            if (!started && d <= PH_SEEK && (now - ref.cd5) >= PH_DA_CD) {
                ref.cd5 = now; started = true;
                phDashStart(ref, tgt);
            }
        }

        runLater(function () { phTick(ref); }, PH_TICK);
    } catch (e) {
        try {
            if (ref != null && phAlive(ref.ent)) runLater(function () { phTick(ref); }, PH_TICK);
            else phCleanup(ref);
        } catch (e2) { }
    }
}

// ==================================================================
// 技能 1：坚守者冲击波（10 点 · 冷却 13 秒 · 射线 30 格）
// ==================================================================
function phSonicStart(ref, tgt) {
    try {
        ref.mode = 'sonic';
        ref.sonic = { step: 0, tgt: tgt };
        try {
            var l = ref.ent.getLocation();
            phSound(l.getWorld(), l, 'entity.warden.sonic_charge', 2.4, 1.0);
        } catch (e) { }
        phSonicTick(ref);
    } catch (e) {
        ref.mode = 'idle'; ref.sonic = null;
    }
}

function phSonicTick(ref) {
    try {
        var st = ref.sonic;
        if (st == null) { ref.mode = 'idle'; return; }
        if (!phAlive(ref.ent) || st.tgt == null || !phAlive(st.tgt)) {
            ref.mode = 'idle'; ref.sonic = null; return;
        }

        // 面向目标（视觉）
        try {
            var el = ref.ent.getLocation();
            var tl = st.tgt.getLocation();
            var dx = tl.getX() - el.getX();
            var dz = tl.getZ() - el.getZ();
            var dy = (tl.getY() + 1.0) - (el.getY() + 1.0);
            var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
            var pitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI;
            ref.ent.setRotation(yaw, pitch);
        } catch (e) { }

        // 蓄力粒子：头前方由外向内收束
        try {
            var w = ref.ent.getWorld();
            var l2 = ref.ent.getLocation();
            var pt = phP(['SCULK_SOUL', 'SOUL', 'SMOKE_NORMAL', 'SMOKE']);
            phFx(w, pt, l2.getX(), l2.getY() + 1.0, l2.getZ(), 4, 0.6, 0.6, 0.6, 0.0);
            phFx(w, pt, l2.getX(), l2.getY(), l2.getZ(), 3, 0.5, 0.4, 0.5, 0.02);
            if (st.step + 1 === PH_SB_CHARGE) {
                try { phSound(w, l2, 'entity.warden.sonic_charge', 2.4, 1.6); } catch (e) { }
            }
        } catch (e) { }

        st.step++;
        if (st.step >= PH_SB_CHARGE) {
            ref.mode = 'idle';
            ref.sonic = null;
            phSonicFire(ref, st.tgt);
            return;
        }
        runLater(function () { phSonicTick(ref); }, 2);
    } catch (e) {
        ref.mode = 'idle'; ref.sonic = null;
    }
}

function phSonicSource(boss) {
    try {
        var DS = Java.type('org.bukkit.damage.DamageSource');
        var DT = Java.type('org.bukkit.damage.DamageType');
        var b = DS.builder(DT.SONIC_BOOM);
        try { b = b.withCausingEntity(boss); } catch (e) { }
        try { b = b.withDirectEntity(boss); } catch (e) { }
        return b.build();
    } catch (e) { return null; }
}

function phSonicFire(ref, tgt) {
    try {
        var e = ref.ent;
        var world = e.getWorld();
        var eye = e.getEyeLocation();
        var te = tgt.getEyeLocation();

        var Vec = Java.type('org.bukkit.util.Vector');
        var dir = te.toVector().subtract(eye.toVector());
        var d = dir.length();
        if (d < 0.2) return;
        dir.normalize();

        var maxD = d + 3.0;
        if (maxD > PH_SB_RANGE) maxD = PH_SB_RANGE;
        if (maxD < 4.0) maxD = 4.0;

        // 射线撞墙 ⇒ 停在墙前（打不穿墙）
        var len = maxD;
        try {
            var FCM = Java.type('org.bukkit.FluidCollisionMode');
            var rr = world.rayTraceBlocks(eye, dir, maxD, FCM.NEVER, true);
            if (rr != null) {
                var hp = rr.getHitPosition();
                if (hp != null) {
                    var seg = hp.clone().subtract(eye.toVector());
                    len = seg.length();
                    if (len < 0.6) len = 0.6;
                }
            }
        } catch (err) { }

        try { phSound(world, eye, 'entity.warden.sonic_boom', 3.0, 1.0); } catch (err) { }

        // 冲击波粒子
        var pt = phP(['SONIC_BOOM']);
        if (pt != null) {
            for (var t = 0.6; t <= len; t += 1.0) {
                try {
                    world.spawnParticle(pt, eye.getX() + dir.getX() * t,
                        eye.getY() + dir.getY() * t, eye.getZ() + dir.getZ() * t, 1, 0, 0, 0, 0);
                } catch (err) { }
            }
        }

        // 命中判定：沿射线每格取一小球，撞到谁算谁
        var Loc = Java.type('org.bukkit.Location');
        var src = phSonicSource(e);
        var hit = {};
        for (var s = 0.6; s <= len + 0.01; s += 1.0) {
            var loc = new Loc(world, eye.getX() + dir.getX() * s,
                eye.getY() + dir.getY() * s, eye.getZ() + dir.getZ() * s);
            var col = null;
            try { col = world.getNearbyEntities(loc, PH_SB_HIT_R, PH_SB_HIT_R, PH_SB_HIT_R); } catch (err) { col = null; }
            if (col == null) continue;
            var it = col.iterator();
            while (it.hasNext()) {
                var v = it.next();
                if (v == null || v === e) continue;
                if (!phAlive(v)) continue;
                if (phIsFriend(v)) continue;                   // ★ 不打自己人
                var id = '';
                try { id = '' + v.getUniqueId(); } catch (err) { id = '' + s; }
                if (hit[id] != null) continue;
                hit[id] = 1;
                phSonicHurt(v, e, src, eye);
            }
        }
    } catch (err) { }
}

function phSonicHurt(v, boss, src, eye) {
    try {
        try { v.setNoDamageTicks(0); } catch (e) { }
        if (src != null) {
            try { v.damage(PH_SB_DMG, src); }
            catch (e) { try { v.damage(PH_SB_DMG, boss); } catch (e2) { } }
        } else {
            try { v.damage(PH_SB_DMG, boss); } catch (e) { }
        }
        // 击退（照原版：水平 2.5、垂直 0.5）
        var vl = null;
        try { vl = v.getLocation(); } catch (e) { vl = null; }
        if (vl == null) return;
        var dx = vl.getX() - eye.getX();
        var dz = vl.getZ() - eye.getZ();
        var n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) {
            var bd = null;
            try { bd = boss.getLocation().getDirection(); } catch (e) { bd = null; }
            dx = (bd == null ? 1.0 : bd.getX());
            dz = (bd == null ? 0.0 : bd.getZ());
            n = Math.sqrt(dx * dx + dz * dz);
            if (n < 1.0e-4) { dx = 1.0; dz = 0.0; n = 1.0; }
        }
        dx /= n; dz /= n;
        try {
            var Vec = Java.type('org.bukkit.util.Vector');
            v.setVelocity(new Vec(dx * PH_SB_KB, PH_SB_KB_UP, dz * PH_SB_KB));
        } catch (e) { }
    } catch (e) { }
}

// ==================================================================
// 技能 2：三枚龙息弹（1 瞄准 + 2 随机落点 · 冷却 7 秒）
// ==================================================================
// 落点：第 1 个 = 玩家当前位置（"锁定玩家"，**直线瞄准，不追踪**）
//       其余 = 玩家周围 PH_DF_SPREAD 格内随机取点，自动贴地
function phSpreadPoints(tgt, total, spreadR) {
    var out = [];
    try {
        var pl = tgt.getLocation();
        out.push({ x: pl.getX(), y: pl.getY() + 1.2, z: pl.getZ() });
        var world = tgt.getWorld();
        for (var i = 1; i < total; i++) {
            var a = Math.random() * Math.PI * 2.0;
            var r = spreadR * (0.35 + 0.65 * Math.random());
            var x = pl.getX() + Math.cos(a) * r;
            var z = pl.getZ() + Math.sin(a) * r;
            var y = phGroundY(world, x, z, pl.getY());
            out.push({ x: x, y: y + 1.2, z: z });
        }
    } catch (e) { }
    return out;
}

// 从 boss 眼睛朝某个点发射一枚弹体（通用）
function phLaunch(world, boss, et, pt, speed) {
    try {
        var eye = boss.getEyeLocation();
        var dx = pt.x - eye.getX();
        var dy = pt.y - eye.getY();
        var dz = pt.z - eye.getZ();
        var n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 1.0e-4) return null;
        dx /= n; dy /= n; dz /= n;

        var Loc = Java.type('org.bukkit.Location');
        var Vec = Java.type('org.bukkit.util.Vector');
        var from = new Loc(world, eye.getX() + dx * 1.2, eye.getY() + dy * 1.2, eye.getZ() + dz * 1.2);
        var pr = world.spawnEntity(from, et);
        if (pr == null) return null;
        try { pr.setShooter(boss); } catch (e) { }
        try { pr.setDirection(new Vec(dx, dy, dz)); } catch (e) { }
        try { pr.setVelocity(new Vec(dx * speed, dy * speed, dz * speed)); } catch (e) { }
        return pr;
    } catch (e) { return null; }
}

function phDragonBreath(ref, tgt) {
    try {
        var e = ref.ent;
        var world = e.getWorld();
        var et = phET(['DRAGON_FIREBALL']);
        if (et == null) return;

        var pts = phSpreadPoints(tgt, PH_DF_N, PH_DF_SPREAD);
        for (var i = 0; i < pts.length; i++) {
            var pr = phLaunch(world, e, et, pts[i], PH_DF_SPEED);
            if (pr != null) {
                // 原版龙息弹本身不破坏地形（只留龙息云），这里把爆炸威力也归零更保险
                try { pr.setYield(0); } catch (err) { }
                try { pr.setIsIncendiary(false); } catch (err) { }
            }
        }
        try {
            var l = e.getLocation();
            phSound(world, l, 'entity.ender_dragon.shoot', 2.0, 0.9);
        } catch (err) { }
        try {
            var pt = phP(['DRAGON_BREATH', 'SMOKE_LARGE', 'LARGE_SMOKE']);
            var l2 = e.getLocation();
            phFx(world, pt, l2.getX(), l2.getY() + 0.6, l2.getZ(), 12, 0.6, 0.4, 0.6, 0.03);
        } catch (err) { }
    } catch (err) { }
}

// ==================================================================
// 技能 3：三枚凋零之首（1 追击 + 2 随机落点 · 冷却 11 秒 · 不炸地形）
// ==================================================================
function phWitherSkulls(ref, tgt) {
    try {
        var e = ref.ent;
        var world = e.getWorld();
        var et = phET(['WITHER_SKULL']);
        if (et == null) return;

        var pts = phSpreadPoints(tgt, PH_WS_N, PH_WS_SPREAD);
        for (var i = 0; i < pts.length; i++) {
            var sk = phLaunch(world, e, et, pts[i], PH_WS_SPEED);
            if (sk == null) continue;

            // ★ 黑色未蓄力头：命中实体只结算伤害，不触发"危险爆炸"
            try { if (typeof sk.setCharged === 'function') sk.setCharged(false); } catch (err) { }
            // 爆炸威力 / 点火：能设就设（真正的保险是 phOnExplode 清空方块列表）
            try { if (typeof sk.setYield === 'function') sk.setYield(0); } catch (err) { }
            try { if (typeof sk.setIsIncendiary === 'function') sk.setIsIncendiary(false); } catch (err) { }
            phSetTag(sk, PH_V_SKULL);

            // 第 1 枚 = 追击玩家的那一枚（后面的是随机落点，直飞）
            if (i === 0 && PH_WS_TURN > 0.0) {
                try { phSkullGuide(sk, tgt, 0); } catch (err) { }
            } else {
                try { phSkullExpire(sk, 0); } catch (err) { }
            }
        }
        try { phSound(world, e.getLocation(), 'entity.wither.shoot', 1.6, 1.2); } catch (err) { }
    } catch (err) { }
}

// ★ 追击：每 PH_WS_GUIDE_T 刻把飞行方向朝目标偏一点
//   新方向 = 归一化( 旧方向 × (1 - 转向系数) + 指向目标 × 转向系数 )
//   慢慢偏 ⇒ 急转 / 绕柱子能甩掉，而不是"必中"的数值惩罚
function phSkullGuide(sk, tgt, step) {
    try {
        if (sk == null) return;
        var alive = false;
        try { alive = !(sk.isDead() || !sk.isValid()); } catch (e) { alive = false; }
        if (!alive) return;

        if (step >= PH_WS_LIFE) { try { sk.remove(); } catch (e) { } return; }   // 超时自毁（不爆炸）

        var t = null;
        try { if (tgt != null && phAlive(tgt)) t = tgt; } catch (e) { t = null; }
        if (t == null) {
            // 目标没了就在附近找一个玩家
            var best = null, bestD = 1e9;
            try {
                var sl = sk.getLocation();
                var players = getServer().getOnlinePlayers();
                var it = players.iterator();
                while (it.hasNext()) {
                    var p = it.next();
                    if (p == null) continue;
                    try { if ('' + p.getWorld().getName() !== '' + sl.getWorld().getName()) continue; } catch (e) { continue; }
                    var d = 1e9;
                    try { d = p.getLocation().distance(sl); } catch (e) { d = 1e9; }
                    if (d > 40.0) continue;
                    var gm = '';
                    try { gm = '' + p.getGameMode().name(); } catch (e) { gm = ''; }
                    if (gm.indexOf('SPECTATOR') >= 0) continue;
                    if (d < bestD) { bestD = d; best = p; }
                }
            } catch (e) { }
            t = best;
        }

        if (t != null) {
            var sl2 = sk.getLocation();
            var tl = t.getLocation();
            var cur = sk.getVelocity();
            var dx = cur.getX(), dy = cur.getY(), dz = cur.getZ();
            var sp = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (sp < 0.05) { sp = PH_WS_SPEED; dx = 0; dy = 0; dz = 1; }
            dx /= sp; dy /= sp; dz /= sp;

            var wx = tl.getX() - sl2.getX();
            var wy = (tl.getY() + 1.0) - sl2.getY();
            var wz = tl.getZ() - sl2.getZ();
            var wn = Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wn > 1.0e-4) {
                wx /= wn; wy /= wn; wz /= wn;
                var k = PH_WS_TURN;
                var nx = dx * (1.0 - k) + wx * k;
                var ny = dy * (1.0 - k) + wy * k;
                var nz = dz * (1.0 - k) + wz * k;
                var nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (nn > 1.0e-4) {
                    nx /= nn; ny /= nn; nz /= nn;
                    var Vec = Java.type('org.bukkit.util.Vector');
                    try { sk.setDirection(new Vec(nx, ny, nz)); } catch (e) { }
                    try { sk.setVelocity(new Vec(nx * PH_WS_SPEED, ny * PH_WS_SPEED, nz * PH_WS_SPEED)); } catch (e) { }
                }
            }
        }
        runLater(function () { phSkullGuide(sk, t, step + PH_WS_GUIDE_T); }, PH_WS_GUIDE_T);
    } catch (e) { }
}

// 随机落点的那种：不追踪，但也要超时自毁
function phSkullExpire(sk, step) {
    try {
        if (sk == null) return;
        var alive = false;
        try { alive = !(sk.isDead() || !sk.isValid()); } catch (e) { alive = false; }
        if (!alive) return;
        if (step >= PH_WS_LIFE) { try { sk.remove(); } catch (e) { } return; }
        runLater(function () { phSkullExpire(sk, step + PH_WS_GUIDE_T); }, PH_WS_GUIDE_T);
    } catch (e) { }
}

// ==================================================================
// 技能 4：投掷三叉戟（原版溺尸投掷伤害 8）→ 落点召唤闪电苦力怕
// ==================================================================
function phTrident(ref, tgt) {
    try {
        var e = ref.ent;
        var world = e.getWorld();
        var et = phET(['TRIDENT']);
        if (et == null) return;

        var tl = tgt.getLocation();
        // ★ 弹道补偿：箭类弹体有重力 ⇒ 按飞行时间把瞄准点抬高 ½·g·t²
        var dxh = tl.getX() - e.getLocation().getX();
        var dzh = tl.getZ() - e.getLocation().getZ();
        var dh = Math.sqrt(dxh * dxh + dzh * dzh);
        var tt = dh / PH_TR_SPEED;
        var drop = 0.5 * PH_TR_GRAV * tt * tt * PH_TR_LEAD;
        if (drop > PH_TR_LEAD_CAP) drop = PH_TR_LEAD_CAP;
        if (drop < 0.0) drop = 0.0;

        var pt = { x: tl.getX(), y: tl.getY() + 1.0 + drop, z: tl.getZ() };
        var tr = phLaunch(world, e, et, pt, PH_TR_SPEED);
        if (tr == null) return;

        phSetTag(tr, PH_V_TRIDENT);
        // ★ 原版溺尸投掷伤害 = 8
        try { if (typeof tr.setDamage === 'function') tr.setDamage(PH_TR_DMG); } catch (err) { }
        // 玩家捡不走 boss 的武器
        try {
            var PS = Java.type('org.bukkit.entity.AbstractArrow$PickupStatus');
            tr.setPickupStatus(PS.DISALLOWED);
        } catch (err) { }

        try { phSound(world, e.getLocation(), 'item.trident.throw', 1.4, 1.0); } catch (err) { }
        try {
            var l2 = e.getLocation();
            var pt2 = phP(['CRIT', 'SMOKE_NORMAL', 'SMOKE']);
            phFx(world, pt2, l2.getX(), l2.getY() + 0.5, l2.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
        } catch (err) { }

        // 追踪落地（速度归零 或 超时）
        phTridentTrack(ref, tr, 0);
    } catch (err) { }
}

function phTridentTrack(ref, tr, step) {
    try {
        if (tr == null) return;
        var gone = false;
        try { gone = (tr.isDead() || !tr.isValid()); } catch (e) { gone = true; }
        if (gone) return;

        if (step > PH_TR_LIFE) { phTridentLand(ref, tr); return; }

        var sp = 9.0;
        try {
            var v = tr.getVelocity();
            sp = Math.sqrt(v.getX() * v.getX() + v.getY() * v.getY() + v.getZ() * v.getZ());
        } catch (e) { sp = 9.0; }

        // 飞出去 3 刻以后速度掉到近 0 ⇒ 判定"扎进地面/停在某处"
        if (step >= 3 && sp < 0.06) { phTridentLand(ref, tr); return; }

        runLater(function () { phTridentTrack(ref, tr, step + 1); }, 1);
    } catch (e) { }
}

function phTridentLand(ref, tr) {
    try {
        var loc = null;
        try { loc = tr.getLocation(); } catch (e) { loc = null; }
        try { tr.remove(); } catch (e) { }
        if (loc == null) return;

        var world = loc.getWorld();
        try { phSound(world, loc, 'item.trident.thunder', 1.8, 1.2); } catch (e) { }
        try {
            var pt = phP(['ELECTRIC_SPARK', 'EXPLOSION_NORMAL', 'SMOKE_NORMAL']);
            phFx(world, pt, loc.getX(), loc.getY() + 0.4, loc.getZ(), 24, 0.5, 0.4, 0.5, 0.06);
        } catch (e) { }

        // ★ 落点召唤一只原版闪电苦力怕
        phPetSpawn(world, loc);
    } catch (e) { }
}

// ==================================================================
// 技能 5：蓄力冲刺撞击（55 点 · 冷却 17 秒 · 蓄力 1.5 秒）· 2026-10-09 第十三批
// ==================================================================
// ★ 完整流程：
//   ① **蓄力 1.5 秒**（PH_DA_CHARGE = 30 刻）：面向目标 + 粒子向身体收束，
//      期间放 **3 次烟花音效**（每 0.5 秒一次，音高递增：0.9 / 1.1 / 1.3）
//   ② **冲刺**：朝「**蓄力结束那一刻玩家所在的位置**」直线冲过去 ——
//      每刻移动 PH_DA_SPEED(2.2) 格 = **44 格/秒**（玩家跑速只有 5.6 格/秒），
//      所以只要玩家在蓄力的 1.5 秒里没离开那条线，基本躲不掉。
//   ③ **命中** ⇒ **55 点伤害**（伤害源走 DamageType.MACE_SMASH，死亡提示是「被重锤猛击」）
//        + **重锤猛击音效** `item.mace.smash_ground_heavy` + 击退；
//      **未命中 / 撞墙** ⇒ 什么都不发生。
// ★ 17 秒冷却**从开始蓄力那一刻算起**（调度处 ref.cd5 当场就置为当前时间）。
// ★ 想让它更「粘人」：把 st.dir 换成每刻朝玩家重算（现在是发射瞬间锁定）。
// ★ 想调难度：PH_DA_SPEED 调小 / PH_DA_HIT_R 调小 / PH_DA_CHARGE 调大。
function phFaceTo(ent, tgt) {
    try {
        var el = ent.getLocation();
        var tl = tgt.getLocation();
        var dx = tl.getX() - el.getX();
        var dz = tl.getZ() - el.getZ();
        var dy = (tl.getY() + 1.0) - (el.getY() + 1.0);
        var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
        var pitch = -Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI;
        ent.setRotation(yaw, pitch);
    } catch (e) { }
}

function phDashStart(ref, tgt) {
    try {
        ref.mode = 'dash';
        ref.dash = { phase: 'charge', step: 0, tgt: tgt };
        try {
            var l = ref.ent.getLocation();
            phSound(l.getWorld(), l, 'entity.phantom.ambient', 2.0, 0.5);
        } catch (e) { }
        phDashChargeTick(ref);
    } catch (e) {
        ref.dash = null; ref.mode = 'idle';
    }
}

function phDashChargeTick(ref) {
    try {
        var st = ref.dash;
        if (st == null) { ref.mode = 'idle'; return; }
        if (!phAlive(ref.ent)) { ref.dash = null; ref.mode = 'idle'; return; }
        if (st.tgt == null || !phAlive(st.tgt)) { ref.dash = null; ref.mode = 'idle'; return; }

        phFaceTo(ref.ent, st.tgt);

        // 蓄力粒子：向身体收束
        try {
            var w = ref.ent.getWorld();
            var l2 = ref.ent.getLocation();
            var pt = phP(['FIREWORK', 'FIREWORK_SPARK', 'FLAME']);
            phFx(w, pt, l2.getX(), l2.getY() + 1.2, l2.getZ(), 6, 0.9, 0.7, 0.9, 0.01);
            phFx(w, pt, l2.getX(), l2.getY(), l2.getZ(), 4, 0.6, 0.5, 0.6, 0.03);
        } catch (e) { }

        // ★ 烟花音效：把 1.5 秒均分成 3 段，每段开头响一次（音高递增）
        var perSteps = Math.max(1, Math.floor(Math.ceil(PH_DA_CHARGE / 2) / PH_DA_FW_TIMES));
        var idx = Math.floor(st.step / perSteps);
        if (st.step % perSteps === 0 && idx < PH_DA_FW_TIMES) {
            try {
                var w2 = ref.ent.getWorld();
                var l3 = ref.ent.getLocation();
                phSound(w2, l3, 'entity.firework_rocket.launch', 2.2, 0.9 + idx * 0.2);
            } catch (e) { }
        }

        st.step++;
        if (st.step >= PH_DA_CHARGE) { phDashLaunch(ref); return; }
        runLater(function () { phDashChargeTick(ref); }, 2);
    } catch (e) {
        ref.dash = null; ref.mode = 'idle';
    }
}

function phDashLaunch(ref) {
    try {
        var st = ref.dash;
        if (st == null) { ref.mode = 'idle'; return; }
        var e = ref.ent;
        var tgt = st.tgt;
        if (!phAlive(e) || tgt == null || !phAlive(tgt)) { ref.dash = null; ref.mode = 'idle'; return; }

        // ★ 锁定「蓄力结束那一刻玩家所在的位置」
        var loc = null;
        try { loc = tgt.getLocation(); } catch (err) { loc = null; }
        if (loc == null) { ref.dash = null; ref.mode = 'idle'; return; }

        var el = e.getLocation();
        var Vec = Java.type('org.bukkit.util.Vector');
        var dx = loc.getX() - el.getX();
        var dy = (loc.getY() + 1.0) - (el.getY() + 1.0);
        var dz = loc.getZ() - el.getZ();
        var L = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 已经贴脸了 ⇒ 直接结算
        if (L < 1.0) { phDashHit(ref, tgt); return; }

        st.phase = 'fly';
        st.dir = new Vec(dx / L, dy / L, dz / L);
        st.left = Math.min(L, PH_DA_MAX);
        st.hit = false;

        try { phSound(e.getWorld(), el, 'entity.phantom.swoop', 2.0, 0.8); } catch (err) { }

        phDashTick(ref);
    } catch (err) {
        ref.dash = null; ref.mode = 'idle';
    }
}

function phDashTick(ref) {
    try {
        var st = ref.dash;
        if (st == null) { ref.mode = 'idle'; return; }
        var e = ref.ent;
        if (!phAlive(e)) { ref.dash = null; ref.mode = 'idle'; return; }

        var step = Math.min(PH_DA_SPEED, st.left);
        var loc = e.getLocation();
        var nx = loc.getX() + st.dir.getX() * step;
        var ny = loc.getY() + st.dir.getY() * step;
        var nz = loc.getZ() + st.dir.getZ() * step;

        // 撞墙就停（未命中 ⇒ 什么都不会发生）
        var blocked = false;
        try {
            if (!phPassableAt(loc.getWorld(), Math.floor(nx), Math.floor(ny + 1.0), Math.floor(nz))) blocked = true;
        } catch (err) { blocked = false; }

        var Loc = Java.type('org.bukkit.Location');
        var nl = new Loc(loc.getWorld(), nx, ny, nz, loc.getYaw(), loc.getPitch());
        try { e.teleport(nl); } catch (err) { }

        // 冲刺拖尾
        try {
            var pt = phP(['FIREWORK', 'FIREWORK_SPARK', 'END_ROD']);
            phFx(loc.getWorld(), pt, nx, ny + 1.0, nz, 10, 0.6, 0.6, 0.6, 0.02);
        } catch (err) { }

        st.left -= step;

        // ★ 命中判定：只判定锁定的那个玩家
        var tp = st.tgt;
        if (tp != null && phAlive(tp)) {
            var tl = null;
            try { tl = tp.getLocation(); } catch (err) { tl = null; }
            if (tl != null) {
                var hx = tl.getX() - nx, hz = tl.getZ() - nz;
                var hyd = Math.sqrt(hx * hx + hz * hz);
                var vyd = Math.abs(tl.getY() - ny);
                if (hyd <= PH_DA_HIT_R && vyd <= PH_DA_HIT_V) { phDashHit(ref, tp); return; }
            }
        }

        if (blocked || st.left <= 0.01) {
            ref.dash = null; ref.mode = 'idle';
            try { phSound(loc.getWorld(), nl, 'entity.phantom.flap', 1.4, 0.8); } catch (err) { }
            return;
        }
        runLater(function () { phDashTick(ref); }, 1);
    } catch (err) {
        ref.dash = null; ref.mode = 'idle';
    }
}

function phDashHit(ref, p) {
    try {
        ref.dash = null; ref.mode = 'idle';

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { loc = null; }
        if (loc == null) return;

        // ① 55 点伤害（伤害源走重锤猛击；拿不到就退回以 boss 为源）
        var e = ref.ent;
        try { p.setNoDamageTicks(0); } catch (err) { }
        var src = phMaceSource(e);
        try {
            if (src != null) p.damage(PH_DA_DMG, src);
            else p.damage(PH_DA_DMG, e);
        } catch (err) { }

        // ② 重锤的猛击音效 + 撞击粒子
        try {
            var w = loc.getWorld();
            phSound(w, loc, 'item.mace.smash_ground_heavy', 2.8, 1.0);
            phSound(w, loc, 'item.mace.smash_air', 2.2, 0.9);
            var pt = phP(['EXPLOSION_LARGE', 'EXPLOSION', 'CRIT']);
            phFx(w, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(), 8, 0.9, 0.6, 0.9, 0.06);
        } catch (err) { }

        // ③ 击退：朝冲刺方向甩出去
        try {
            var Vec = Java.type('org.bukkit.util.Vector');
            var d = e.getLocation().getDirection();
            p.setVelocity(new Vec(d.getX() * 1.8, 0.7, d.getZ() * 1.8));
        } catch (err) { }
    } catch (err) { }
}

function phMaceSource(boss) {
    try {
        var DS = Java.type('org.bukkit.damage.DamageSource');
        var DT = Java.type('org.bukkit.damage.DamageType');
        var dt = null;
        try { dt = DT.MACE_SMASH; } catch (e) { dt = null; }
        if (dt == null) return null;
        var b = DS.builder(dt);
        try { b = b.withCausingEntity(boss); } catch (e) { }
        try { b = b.withDirectEntity(boss); } catch (e) { }
        return b.build();
    } catch (e) { return null; }
}
// ==================================================================
// 闪电苦力怕（原版 · 不被 boss 的技能伤害 · 有数量与存活上限）
// ==================================================================
function phPetSweep() {
    try {
        var now = phNow();
        var list = [];
        var k;
        for (k in PH_PETS) {
            var rec = PH_PETS[k];
            if (rec == null) { try { delete PH_PETS[k]; } catch (e) { } continue; }
            var alive = false;
            try { alive = !(rec.e.isDead() || !rec.e.isValid()); } catch (e) { alive = false; }
            if (!alive) { try { delete PH_PETS[k]; } catch (e) { } continue; }
            if (now - rec.at >= PH_PET_LIFE) {
                try { rec.e.remove(); } catch (e) { }
                try { delete PH_PETS[k]; } catch (e) { }
                continue;
            }
            list.push({ k: k, at: rec.at });
        }
        // 超上限：把最早的几只收掉
        if (list.length > PH_PET_MAX) {
            list.sort(function (a, b) { return a.at - b.at; });
            var over = list.length - PH_PET_MAX;
            for (var i = 0; i < over; i++) {
                var rec2 = PH_PETS[list[i].k];
                if (rec2 != null) { try { rec2.e.remove(); } catch (e) { } }
                try { delete PH_PETS[list[i].k]; } catch (e) { }
            }
        }
    } catch (e) { }
}

function phPetSpawn(world, loc) {
    try {
        if (world == null || loc == null) return null;
        var et = phET(['CREEPER']);
        if (et == null) return null;
        var c = world.spawnEntity(loc, et);
        if (c == null) return null;

        // ★ 原版闪电苦力怕（被雷劈过的苦力怕 = powered）
        //   （掉落与经验不在这里管：靠死亡事件 dth 里 `getDrops().clear()` / `setDroppedExp(0)`）
        try { if (typeof c.setPowered === 'function') c.setPowered(true); } catch (e) { }
        try { c.setRemoveWhenFarAway(false); } catch (e) { }
        try { c.setPersistent(true); } catch (e) { }
        phSetTag(c, PH_V_PET);

        PH_PETS['' + c.getUniqueId()] = { e: c, at: phNow() };
        // 出生特效（闪电味道）
        try {
            var pt = phP(['ELECTRIC_SPARK', 'EXPLOSION_NORMAL']);
            phFx(world, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(), 30, 0.4, 0.8, 0.4, 0.08);
        } catch (e) { }
        try { phSound(world, loc, 'entity.lightning_bolt.thunder', 0.6, 1.6); } catch (e) { }
        try { phSound(world, loc, 'entity.creeper.primed', 1.2, 1.4); } catch (e) { }

        phPetSweep();
        return c;
    } catch (e) { return null; }
}

// ==================================================================
// 收摊
// ==================================================================
function phCleanup(ref) {
    try {
        if (ref == null) return;
        if (ref.done) return;
        ref.done = true;
        if (ref.mode === 'sonic') { ref.sonic = null; }
        if (ref.mode === 'dash') { ref.dash = null; }
        ref.mode = 'idle';
        try { phBarDrop(ref); } catch (e) { }
        var k;
        for (k in PH_ACTIVE) {
            if (PH_ACTIVE[k] === ref) { try { delete PH_ACTIVE[k]; } catch (e) { } }
        }
    } catch (e) { }
}

// ==================================================================
// ★ 掉落物（2026-10-09 第十三批）：清空原版掉落后，固定掉 11 件（各 1 个）
// ==================================================================
//   重锤 · 三叉戟 · 龙蛋 · 下界之星 · 腐肉 · 火药 · 幽匿催发体 · 幻翼膜 ·
//   本 boss 召唤物（GS_PHANTOM）· 烟花火箭（飞行时间 = 1）· 鞘翅
//   ★ 原版掉落物与经验在 dth 里已经清空；这里只负责「加东西」。
function phVanillaStack(name, amount) {
    try {
        var M = Java.type('org.bukkit.Material');
        var m = null;
        try { m = M.valueOf(name); } catch (e) { m = null; }
        if (m == null) return null;
        var nm = '';
        try { nm = '' + m.name(); } catch (e2) { nm = ''; }
        if (nm === 'AIR' || nm.length === 0) return null;
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        return new ItemStack(m, (amount == null || amount <= 0) ? 1 : amount);
    } catch (e) { return null; }
}

// 烟花火箭（power = 飞行时间；1 = 「1 秒的那种」）
function phFireworkStack(power) {
    try {
        var M = Java.type('org.bukkit.Material');
        var m = null;
        try { m = M.valueOf('FIREWORK_ROCKET'); } catch (e) { m = null; }
        if (m == null) { try { m = M.valueOf('FIREWORK'); } catch (e2) { m = null; } }
        if (m == null) return null;
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        var st = new ItemStack(m, 1);
        try {
            var meta = st.getItemMeta();
            if (meta != null) {
                try { meta.setPower(power); } catch (e3) { }
                st.setItemMeta(meta);
            }
        } catch (e4) { }
        return st;
    } catch (e) { return null; }
}

function phDropLoot(ent) {
    try {
        if (ent == null) return;
        var world = null, x = 0, y = 0, z = 0;
        try {
            var l = ent.getLocation();
            world = l.getWorld(); x = l.getX(); y = l.getY(); z = l.getZ();
        } catch (e) { world = null; }
        if (world == null) return;

        var Loc = Java.type('org.bukkit.Location');
        var loc = new Loc(world, x, y + 0.5, z);

        // ① 原版物品（各 1 个）
        var names = ['MACE', 'TRIDENT', 'DRAGON_EGG', 'NETHER_STAR',
                     'ROTTEN_FLESH', 'GUNPOWDER', 'SCULK_CATALYST',
                     'PHANTOM_MEMBRANE', 'ELYTRA'];
        for (var i = 0; i < names.length; i++) {
            var s1 = phVanillaStack(names[i], 1);
            if (s1 != null) { try { world.dropItemNaturally(loc, s1); } catch (e) { } }
        }

        // ② 烟花火箭（飞行时间 1）
        var fw = phFireworkStack(1);
        if (fw != null) { try { world.dropItemNaturally(loc, fw); } catch (e) { } }

        // ③ 本 boss 召唤物（粘液物品：取模板再 clone）
        try {
            var sf = getSfItemById(PH_ITEM_ID);
            if (sf != null) {
                var s2 = sf.getItem().clone();
                try { s2.setAmount(1); } catch (e) { }
                world.dropItemNaturally(loc, s2);
            }
        } catch (e) { }

        try { phSound(world, loc, 'entity.player.levelup', 0.8, 1.2); } catch (e) { }
    } catch (e) { }
}
// ==================================================================
// 监听器
// ==================================================================
var PH_REG_DMG = false;
var PH_REG_EXP = false;
var PH_REG_DEATH = false;
var PH_REG_GUARD = false;

function phRegisterEvents() {
    if (PH_REG_DMG) return true;
    try {
        var pm = getServer().getPluginManager();
        var plugin = phPlugin();
        if (plugin == null) return false;
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var ok = 0;

        // ① 伤害 / 阵营
        try {
            var DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent');
            if (DmgEv != null) {
                function dmh(e) { phOnDamageByEntity(e); }
                try {
                    pm.registerEvent(DmgEv, plugin, Priority.HIGHEST, function (l, e) { dmh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ② 血量降级兜底：MAX_HEALTH 写不进去时按 hpScale 等比缩伤（等效血量不变）
        try {
            var DmgBaseEv = Java.type('org.bukkit.event.entity.EntityDamageEvent');
            if (DmgBaseEv != null) {
                function hph(e) {
                    try {
                        var vic = e.getEntity();
                        if (vic == null || !phIsBoss(vic)) return;
                        var rf = phRefOf(vic);
                        if (rf == null) return;
                        var sc = rf.hpScale;
                        if (sc == null || sc >= 1.0 || sc <= 0.0) return;
                        var d = 0.0;
                        try { d = e.getDamage(); } catch (err) { d = 0.0; }
                        if (d <= 0.0) return;
                        try { e.setDamage(d * sc); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DmgBaseEv, plugin, Priority.HIGH, function (l, e) { hph(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ③ 死亡：清掉落 / 清经验 / 收血条
        try {
            var DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent');
            if (DeathEv != null) {
                function dth(e) {
                    try {
                        var ent = e.getEntity();
                        if (ent == null) return;
                        var t = phTagOf(ent);
                        if (t === '' || t == null) return;

                        // ① 原版掉落物一律清空（幻翼膜 / 手上装备 / 掉落列表）
                        //    ★ 幻翼原版会掉「幻翼膜」（战利品表 entities/phantom），
                        //      必须**先清干净**再放我们要的东西。
                        //    ★ 1.21.11 没有 `LootTables.EMPTY` / `minecraft:empty` ⇒ 置空战利品表
                        //      这条路走不通，`getDrops().clear()` 才是唯一可靠手段。
                        try { ent.getEquipment().clear(); } catch (err) { }
                        try { e.getDrops().clear(); } catch (err) { }
                        // 清完再清一遍（有些服务端会把刚清掉的东西又塞回 drops）
                        try { e.getDrops().clear(); } catch (err) { }

                        if (t === PH_V_BOSS) {
                            // ② boss 的经验 = PH_DROP_XP（第十三批修订：120000）
                            try { e.setDroppedExp(PH_DROP_XP); } catch (err) { }
                            try { phDropLoot(ent); } catch (err) { }
                            try {
                                var id = '' + ent.getUniqueId();
                                for (var k in PH_ACTIVE) {
                                    var r = PH_ACTIVE[k];
                                    if (r == null) continue;
                                    try { if ('' + r.ent.getUniqueId() === id) phCleanup(r); } catch (err) { }
                                }
                            } catch (err) { }
                        } else {
                            // ★ 自家人（落点召唤的闪电苦力怕等）：经验一并清 0，不给白嫖
                            try { e.setDroppedExp(0); } catch (err) { }
                        }
                        var pk = '';
                        try { pk = '' + ent.getUniqueId(); } catch (err) { pk = ''; }
                        if (pk.length > 0 && PH_PETS[pk] != null) { try { delete PH_PETS[pk]; } catch (err) { } }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DeathEv, plugin, Priority.HIGHEST, function (l, e) { dth(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ④ 爆炸：自家的凋零之首（+ 关掉破坏时的苦力怕）⇒ 清空待破坏方块
        try {
            var ExpEv = Java.type('org.bukkit.event.entity.EntityExplodeEvent');
            if (ExpEv != null) {
                function exh(e) { phOnExplode(e); }
                try {
                    pm.registerEvent(ExpEv, plugin, Priority.HIGHEST, function (l, e) { exh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        PH_REG_DMG = (ok > 0);
        if (PH_REG_DMG) {
            try { getServer().getLogger().info('[构思技艺] 幻翼boss：伤害 / 死亡 / 爆炸拦截 已启用（' + ok + ' 条）'); } catch (e) { }
        }
        return PH_REG_DMG;
    } catch (e) { return false; }
}

function phRefOf(ent) {
    try {
        if (ent == null) return null;
        var id = '' + ent.getUniqueId();
        for (var k in PH_ACTIVE) {
            var r = PH_ACTIVE[k];
            if (r == null) continue;
            try { if (r.ent != null && ('' + r.ent.getUniqueId()) === id) return r; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// 弹体的"归属者"：箭/头/三叉戟看 shooter，龙息云看 source
function phShooterOf(ent) {
    try {
        if (ent == null) return null;
        try {
            if (typeof ent.getShooter === 'function') {
                var s = ent.getShooter();
                if (s != null && typeof s.getUniqueId === 'function') return s;
            }
        } catch (e) { }
        try {
            if (typeof ent.getSource === 'function') {
                var s2 = ent.getSource();
                if (s2 != null && typeof s2.getUniqueId === 'function') return s2;
            }
        } catch (e) { }
    } catch (e) { }
    return null;
}

function phOnDamageByEntity(e) {
    try {
        var src = e.getDamager();
        var vic = e.getEntity();
        if (src == null || vic == null) return;

        var vicFriend = phIsFriend(vic);
        // 只有"挨打的这一方是自己人"才需要管；玩家 / 别的生物挨打 ⇒ 交给原版，一概不插手
        if (!vicFriend) return;

        // ① 自己人打自己人 ⇒ 免伤（boss / 闪电苦力怕 互相之间）
        if (phIsFriend(src)) {
            try { e.setCancelled(true); } catch (err) { }
            try { e.setDamage(0.0); } catch (err) { }
            return;
        }

        // ② 自家的弹体（凋零之首 / 三叉戟 / 龙息云）打到自己人 ⇒ 免伤
        var own = phShooterOf(src);
        if (own != null && phIsFriend(own)) {
            try { e.setCancelled(true); } catch (err) { }
            try { e.setDamage(0.0); } catch (err) { }
            return;
        }

        // ③ 弹体自己带标记的（万一 shooter 取不到）
        var t = phTagOf(src);
        if (t === PH_V_SKULL || t === PH_V_TRIDENT) {
            try { e.setCancelled(true); } catch (err) { }
            try { e.setDamage(0.0); } catch (err) { }
            return;
        }
    } catch (err) { }
}

function phIsOurExplosive(ent) {
    try {
        if (ent == null) return false;
        var t = phTagOf(ent);
        if (t === PH_V_SKULL) return true;                        // 自家的凋零之首
        // ★★ 第六十三批：闪电苦力怕**一律**算自家爆炸 ⇒ 无条件清空待破坏方块列表
        //    （原来这里挂着 `&& !PH_PET_GRIEF`，开关已按用户要求删除）
        if (t === PH_V_PET) return true;                          // 闪电苦力怕（不破坏方块）
        // 兜底：shooter 是自家 boss 的爆炸（头的标记万一丢了）
        var own = phShooterOf(ent);
        if (own != null && phIsFriend(own)) return true;
    } catch (e) { }
    return false;
}

function phOnExplode(e) {
    try {
        var ent = null;
        try { ent = e.getEntity(); } catch (err) { return; }
        if (!phIsOurExplosive(ent)) return;

        // ① 主手段：清空"待破坏方块"列表 ⇒ 地形一个方块都不掉
        var snap = null;
        try {
            var list = e.blockList();
            if (list != null) {
                try {
                    if (list.size() > 0) {
                        snap = [];
                        var bit = list.iterator();
                        while (bit.hasNext()) {
                            var bl = bit.next();
                            try { snap.push({ b: bl, d: bl.getBlockData() }); } catch (err2) { }
                        }
                    }
                } catch (err2) { }
                list.clear();
            }
        } catch (err) { }
        try { if (typeof e.setYield === 'function') e.setYield(0); } catch (err) { }

        // ② 兜底：1 刻后照着快照还原真的被炸掉的方块（正常情况是纯检查）
        if (snap != null && snap.length > 0) {
            runLater(function () {
                try {
                    for (var i = 0; i < snap.length; i++) {
                        var rec = snap[i];
                        try {
                            if ('' + rec.b.getType().name() !== '' + rec.d.getMaterial().name()) {
                                rec.b.setBlockData(rec.d);
                            }
                        } catch (err3) { }
                    }
                } catch (err4) { }
            }, 1);
        }
    } catch (e2) { }
}

// 手持本物品右键方块 ⇒ 方块交互一律取消（和「冰」「妄念」同一个套路）
function phRegisterGuard() {
    if (PH_REG_GUARD) return true;
    try {
        var pm = getServer().getPluginManager();
        var plugin = phPlugin();
        if (plugin == null) return false;
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var InteractEv = Java.type('org.bukkit.event.player.PlayerInteractEvent');
        if (InteractEv == null) return false;

        var handler = function (e) {
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
                if (!phIsOurItem(h)) return;
                e.setCancelled(true);
            } catch (err) { }
        };
        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST, function (l, e) { handler(e); }, plugin);
            PH_REG_GUARD = true;
            return true;
        } catch (e1) { return false; }
    } catch (e) { return false; }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function phIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === PH_ITEM_ID) return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === PH_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

function phIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return phIsOurItem(hand);
    } catch (e) { return false; }
}

function phConsume(p) {
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
// 入口
// ==================================================================
function onUse(event) {
    try {
        try { phRegisterEvents(); } catch (e) { }   // 惰性重试
        try { phRegisterGuard(); } catch (e) { }    // 惰性重试

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        if (!phIsMainHandUse(event, p)) return;

        var uuid = '' + p.getUniqueId();
        var now = phNow();
        var last = PH_LAST[uuid];
        if (last != null && (now - (0 + last)) < 500) return;
        PH_LAST[uuid] = now;

        if (!phSummon(p)) return;
        if (PH_CONSUME) phConsume(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 幻翼boss onUse 出错: ' + err); } catch (e) { }
    }
}

// ==================================================================
// 启动自检
// ==================================================================
try {
    getServer().getLogger().info('[构思技艺] 幻翼boss 参数：血量=' + PH_HP
        + ' 普攻=原版（' + (6 + PH_SIZE) + ' 点）'
        + ' 缩放=' + PH_SCALE.toFixed(3) + '（目标碰撞箱 ' + PH_BOX_W + ' × '
        + (0.5 * (1.0 + 0.15 * PH_SIZE) * PH_SCALE).toFixed(2) + '，体型档=' + PH_SIZE + '）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 幻翼boss 技能：'
        + '①坚守者冲击波 ' + PH_SB_DMG + '点/' + (PH_SB_CD / 1000) + '秒'
        + ' ②龙息弹×' + PH_DF_N + '（瞄准1+随机' + PH_DF_RAND + '）' + (PH_DF_CD / 1000) + '秒'
        + ' ③凋零之首×' + PH_WS_N + '（追击1+随机' + PH_WS_RAND + '，不炸地形）' + (PH_WS_CD / 1000) + '秒'
        + ' ④三叉戟' + PH_TR_DMG + '点→落点闪电苦力怕' + (PH_TR_CD / 1000) + '秒'
        + ' ｜ 出场保护=' + (PH_FIRST_MS / 1000) + '秒 心跳=' + PH_TICK + '刻');
} catch (e) { }

// ==================================================================
// 初始化（PDC 键 + 苦力怕清扫 + reload 残留清扫）
// ==================================================================
try {
    var NS = Java.type('org.bukkit.NamespacedKey');
    // ★★ 必须用**静态工厂 fromString**，**绝对不要** `new NamespacedKey(plugin, name)`：
    //    GraalJS 对 (Plugin,String) 与 (String,String) 两个构造器**无法消歧义**，
    //    会抛 `TypeError: instantiate ... Multiple applicable overloads found`
    //    ⇒ PH_KEY / PH_PDT 全为 null ⇒ phTagOf() 恒返回 '' ⇒ **死亡事件第一句就 return**，
    //      掉落清空 / 经验 / 自定义掉落 **一条都不会执行**（2026-10-09 线上实测就是这个）。
    //    全仓其它脚本（boss_three=`gs:boss3` / rider=`gs:boss` / listener=`gs:*`）都是 fromString。
    PH_KEY = NS.fromString('gs:' + PH_KEYNAME);
    PH_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
    try { getServer().getLogger().info('[构思技艺] 幻翼boss：PDC 键 ' + PH_KEY + ' 初始化 OK'); } catch (e0) { }
} catch (e) {
    try { getServer().getLogger().warning('[构思技艺] 幻翼boss：PDC 键初始化失败 ' + e); } catch (e2) { }
}

// 苦力怕数量/存活清扫：每 5 秒一次
try {
    function phPetLoop() {
        try { phPetSweep(); } catch (e) { }
        runLater(function () { phPetLoop(); }, 100);
    }
    runLater(function () { phPetLoop(); }, 100);
} catch (e) { }

// ★ reload 残留清扫：加载 3 秒后扫全世界，把上一份脚本留下的
//   boss / 闪电苦力怕 / 弹体（靠 PDC 标记识别）清掉 —— 否则它们是"没人管"的，
//   血条没了、技能也不会放，只会站着挨打。
try {
    runLater(function () {
        try {
            var removed = 0;
            var worlds = getServer().getWorlds();
            var it = worlds.iterator();
            while (it.hasNext()) {
                var w = it.next();
                var ents = w.getEntities();
                var eit = ents.iterator();
                while (eit.hasNext()) {
                    var en = eit.next();
                    if (en == null) continue;
                    var t = phTagOf(en);
                    if (t === PH_V_BOSS || t === PH_V_PET || t === PH_V_SKULL || t === PH_V_TRIDENT) {
                        try { en.remove(); removed++; } catch (e) { }
                    }
                }
            }
            if (removed > 0) {
                try { getServer().getLogger().info('[构思技艺] 幻翼boss：清扫上一份脚本残留 ' + removed + ' 个实体'); } catch (e) { }
            }
        } catch (e) { }
    }, 60);
} catch (e) { }
