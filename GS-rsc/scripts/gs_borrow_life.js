/* ==========================================================
 * 构思技艺 (GS) —— 「借寿」
 * items.yml 里由  script: gs_borrow_life  引用；入口函数 onUse(event)
 * 分类：构思玩具 (gs_toy) ｜ 材质：发酵蛛眼 FERMENTED_SPIDER_EYE
 *   ★ 第五十六批放在「构思武器」；★ 第五十九批按用户要求「借寿放到构思玩具」
 *     ⇒ 改成 gs_toy 第 10 格。
 * 获取：魔法工作台（MAGIC_WORKBENCH）九宫格
 * ----------------------------------------------------------
 * ★★ 用户需求（原话）：
 *   「右键效果：对准星方向长5宽5高2的范围内的所有生物造成1点的伤害，
 *     冷却0.5秒（消失无敌帧），范围内有n只生物被命中，自己就能回1n滴血，
 *     并且被命中的生物会在0.5秒内从生物身上出现红色粒子飞向玩家
 *     （模拟一种吸血的效果）」
 * ----------------------------------------------------------
 * 【判定盒：长 5 × 宽 5 × 高 2】
 *   · ★★ 第五十九批：**改成完整三维朝向**（沿准星展开，抬头/低头都能打）。
 *     用户原话：「现在不能朝上朝下，我需要朝上朝下也能攻击」
 *     基向量（和「阎魔刀·改」的 gsEmBasis 同一套口径）：
 *       f = **准星方向**（含俯仰，已归一化）      ⇒ 长 5 沿 f
 *       r = **水平右向**（永远水平，不随俯仰倾斜）⇒ 宽 5 沿 r
 *       u = r × f（垂直于 f 与 r 的"上"）        ⇒ 高 2 沿 u
 *     投影（原点 = 自己胸口「脚下 +1.0」，怪的取样点 = 「脚下 +0.9」）：
 *       前向 fwd = d·f ∈ [-0.4, 5.0]  （长 5；身后留 0.4 格容差，免得贴身怪打不到）
 *       左右 lat = d·r ∈ [-2.5, +2.5] （宽 5）
 *       上下 ver = d·u ∈ [-1.0, +1.0] （高 2，沿"垂直于准星的上"上下各 1）
 *     ⇒ 抬头看天 ⇒ 判定体跟着仰起来，往**天上**打；低头 ⇒ 往**脚下**打。
 *   ★ **平视（pitch = 0）时与旧版逐点等价**：f=(0,0,1) / r=(−1,0,0) / u=(0,1,0)
 *     ⇒ fwd = dz、lat = −dx、ver = dy − 0.1 ⇒ 竖直区间仍是 dy ∈ [−0.9, +1.1]。
 *     （回归用例专门钉了这一点，改几何时别把平视手感改掉。）
 *   ★ 搜索用 `getNearbyEntities` 取一个**外接方盒**，再用上面的投影精确过滤。
 *     ★★ 判定体现在是**三维旋转**的 ⇒ 搜索盒半宽要按**外接球**
 *       √((长/2)² + (宽/2)² + (高/2)²) 算，不能再只算水平的 √((长/2)²+(宽/2)²)
 *       —— 否则抬头时高处/低处的怪会"搜不到"，表现为"明明在范围内却打不中"。
 *
 * 【施法提示词：★ 第六十二批已删除】
 *   · 第五十九批曾按用户要求加过「每次施法都写一句 寿来 寿来 寿来」。
 *   · ★★ 第六十二批用户明确说「**借寿提示词删除**」⇒ 常量 `BL_CAST_MSG`、
 *     函数 `blCastMsg`、onUse 里那一步调用**全部删除**。
 *   · 现在施法**只出声、不写字**（聊天栏 / 动作栏都不会再冒提示词）。
 *
 * 【伤害：1 点 · 消失无敌帧】
 *   · `blHurt()` 打之前 `setNoDamageTicks(0)`、打之后再清一次 ⇒
 *     **怪的受击无敌帧被无视**，每 0.5 秒都能实打实地扣 1 点。
 *   · 同时**不造成击退**（先存速度、打完立刻还原，同刻内位移被抵消）——
 *     否则每 0.5 秒把怪推一下，怪会被推着跑。
 *
 * 【回血：命中 n 只 ⇒ 回 1×n 点】
 *   · n = 本次判定盒内**实际被打到**的生物数（去重后）。
 *   · 回血上限受 `Attribute.MAX_HEALTH` 约束，满血时不溢出。
 *   · 想改成"每只回 2 点"⇒ 改 `BL_HEAL_PER`。
 *
 * 【吸血粒子：红色粒子从怪身上飞向玩家，持续 0.5 秒】
 *   · 由 `runLater` 驱动的**自调度链**（每刻一步、共 `BL_SIP_TICKS = 10` 刻 = 0.5 秒）。
 *   · 每只被命中的怪各自从**自己当前位置的胸口**沿直线飞向**玩家当前位置的胸口**，
 *     参数 f 从 0 走到 1；再叠一点正弦弧度，看起来像一串血珠被吸过来。
 *   · 粒子 = `Particle.DUST` + 暗红 `DustOptions`（1.21.11 里确认存在）；
 *     拿不到就退回 `DAMAGE_INDICATOR`（暗红心形，不需要 data）。
 *   · ★★ **绝不用 `runTaskTimer`** —— `BukkitScheduler` 上
 *     `runTaskTimer(Plugin, Runnable, long, long)` 与
 *     `runTaskTimer(Plugin, Consumer<BukkitTask>, long, long)` 两个重载
 *     会让 GraalJS 分不清（第五十四批「排任务失败」的病因）。
 *     `runLater(fn, delay)` 是 RSC 注入的，没有重载歧义。
 *   · `runLater` 返回不了句柄 ⇒ **停不掉**，所以用**只增不减的全局世代号 `BL_GEN`**
 *     断链：每一步先核对自己的 gen 还在不在，不在就不再排下一步。
 *
 * 【冷却 0.5 秒】
 *   · `BL_CD_MS = 500`；另有 `BL_DEBOUNCE_MS = 150` 去抖
 *     （一次右键可能连发两个事件）—— 去抖必须**明显小于**冷却，
 *     否则"0.5 秒后再按"会被去抖误吞。
 *   · 冷却中**静默忽略**（不提示、不出声、不消耗）。
 *
 * 【其它】
 *   · 物品**不消耗**（可以反复按）。
 *   · 默认**不打其它玩家**（`BL_HIT_PLAYERS = false`，和全项目其它武器一致，防误伤）。
 *   · 目标筛选沿用「寂灭」那套：掉落物 / 经验球 / 展示实体 / 盔甲架一律不算。
 * ========================================================== */

// ---------------- 施法提示词：★ 第六十二批**已删除** ----------------
//   ★★ 用户原话：「借寿提示词删除」。
//   ⇒ 第五十九批加的「每次施法写一句『寿来 寿来 寿来』」**整段撤掉**：
//     常量 `BL_CAST_MSG`、函数 `blCastMsg`、以及 onUse 里那一步调用**全部删除**。
//   ★ 现在施法**只出声、不写字**（聊天栏 / 动作栏都不会再冒提示词）。
//   ★ 这条是**用户明确推翻**上一批的要求 ⇒ 校验器里所有"提示词必须存在"的
//     断言都已改成**反向断言**（不许再出现），免得以后又被改回来。

// 加载标记：后台看到这行，说明脚本本身没问题
try {
    getServer().getLogger().info('[构思技艺] 借寿 已加载 · 沿准星三维朝向 长5×宽5×高2（抬头/低头也能打）· '
        + '1 点伤害 / 冷却 0.5 秒 / 命中 n 只回 n 点血 / 吸血粒子 0.5 秒 / '
        + '★ 施法提示词已按第六十二批要求**删除**（只出声不写字）');
} catch (e) { }

// ---------------- 参数 ----------------
var BL_ITEM_ID      = 'GS_BORROW_LIFE';

var BL_RANGE        = 5.0;      // 长（沿准星方向 5 格，含俯仰）
var BL_WIDE         = 5.0;      // 宽（水平右向左右各 2.5 格）
var BL_H            = 2.0;      // 高（沿 u = r×f 上下各 1 格）
var BL_BACK         = 0.4;      // 身后容差（贴身怪也算打得到）

var BL_DMG          = 1.0;      // ★ 每只生物 1 点伤害
var BL_HEAL_PER     = 1.0;      // ★ 每命中一只回 1 点血（"回 1n 滴血"）
var BL_HIT_PLAYERS  = false;    // false = 不打其它玩家（只打生物），防误伤

var BL_CD_MS        = 500;      // ★ 冷却 0.5 秒
var BL_DEBOUNCE_MS  = 150;      // 去抖（一次右键可能连发两个事件）
var BL_CD_HINT      = false;    // true = 冷却中提示还剩几秒

var BL_SIP_TICKS    = 10;       // ★ 吸血粒子飞 10 刻 = 0.5 秒
var BL_SIP_PER_TICK = 2;        // 每只怪每刻撒几个粒子
var BL_SIP_ARC      = 0.35;     // 飞行路径的弧度（0 = 直线）
var BL_SIP_STAGGER  = 0.04;     // 每只怪错开一点，看起来是一串而不是一团

// ★★ 施法提示词：**第六十二批已按用户要求删除** ——
//    常量 / 函数 / 调用三处全部撤掉，这里只留这条说明（别再往回加）。

// ---------------- 粒子 / 颜色（逐个容错） ----------------
var BL_PT      = null;    // 首选：DUST（要带 DustOptions 才是红的）
var BL_PT_FB   = null;    // 退路：不需要 data 的红色系粒子
var BL_DUST    = null;    // DustOptions（暗红）
var BL_LOCC    = null;    // org.bukkit.Location 构造器缓存

try {
    var BL_P = Java.type('org.bukkit.Particle');
    try { BL_PT = BL_P.DUST; } catch (e) { BL_PT = null; }
    try { BL_PT_FB = BL_P.DAMAGE_INDICATOR; } catch (e) { BL_PT_FB = null; }
    if (BL_PT_FB == null) { try { BL_PT_FB = BL_P.CRIT; } catch (e) { } }
} catch (e) { }

try {
    if (BL_PT != null) {
        var BL_Color = Java.type('org.bukkit.Color');
        var BL_Dust  = Java.type('org.bukkit.Particle$DustOptions');
        BL_DUST = new BL_Dust(BL_Color.fromRGB(200, 16, 16), 0.9);
    }
} catch (e) { BL_DUST = null; }

// ---------------- 小工具 ----------------
function blNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function blLoc(world, x, y, z) {
    try {
        if (BL_LOCC == null) BL_LOCC = Java.type('org.bukkit.Location');
        return new BL_LOCC(world, x, y, z);
    } catch (e) { return null; }
}

// 撒一个红色粒子（DUST 优先，拿不到就退到不需要 data 的那个）
function blDust(world, x, y, z) {
    try {
        if (world == null) return;
        if (BL_PT != null && BL_DUST != null) {
            try { world.spawnParticle(BL_PT, x, y, z, 1, 0.0, 0.0, 0.0, BL_DUST); return; }
            catch (e1) { }
        }
        if (BL_PT_FB != null) {
            try { world.spawnParticle(BL_PT_FB, x, y, z, 1, 0.0, 0.0, 0.0); } catch (e2) { }
        }
    } catch (e) { }
}

// ---------------- 冷却 / 去抖（都按玩家记） ----------------
var BL_LAST  = {};    // uuid -> 上次成功使用的时间戳(ms)
var BL_TOUCH = {};    // uuid -> 上次按下去的时间戳(ms)

function blCooldownLeft(p) {
    try {
        if (BL_CD_MS <= 0) return 0;
        var v = BL_LAST['' + p.getUniqueId()];
        if (v == null) return 0;
        var left = (0 + v) + BL_CD_MS - blNow();
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function blMarkUsed(p) {
    try { BL_LAST['' + p.getUniqueId()] = blNow(); } catch (e) { }
}

// ★ 去抖只做「同一次右键的两个事件只认一个」这一件事 ——
//   所以**拆成"查"和"武装"两步**：查是只读的，只有在**这次按下去真的生效**时
//   才武装窗口。
//   ★★ 为什么不能像常见写法那样"每次按都刷新窗口"：冷却期间被挡掉的连点会把
//   窗口一直往后推 ⇒ **冷却刚好结束的那一下会被误吞**。
//   （逻辑测试里抓到的实例：0 / 100 / 300 / 450 / 510 ms 连点，
//     510ms 那一下本该打出去，却被 450ms 那次武装的窗口吞掉了。）
function blIsDuplicate(p) {
    try {
        if (BL_DEBOUNCE_MS <= 0) return false;
        var v = BL_TOUCH['' + p.getUniqueId()];
        if (v == null) return false;
        return ((0 + v) + BL_DEBOUNCE_MS) > blNow();
    } catch (e) { return false; }
}

function blArmDebounce(p) {
    try { BL_TOUCH['' + p.getUniqueId()] = blNow(); } catch (e) { }
}

function blCooldownHint(p, left) {
    try {
        if (!BL_CD_HINT) return;
        var s = Math.ceil(left / 1000);
        var C = null;
        try { C = Java.type('net.md_5.bungee.api.ChatColor'); } catch (e) { C = null; }
        var msg = (C != null ? ('' + C.GOLD) : '') + '借寿 · 冷却中，还剩 ' + s + ' 秒';
        try { p.sendActionBar(msg); }
        catch (e2) { try { p.sendMessage(msg); } catch (e3) { } }
    } catch (e) { }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function blIsOurItem(stack) {
    try {
        if (stack == null) return false;
        // ① RSC 的判定（最准）
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === BL_ITEM_ID) return true;
        } catch (e) { }
        // ② Slimefun 标准 API 兜底
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === BL_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

// 只认主手（副手那次触发不算）
function blIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;

        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return blIsOurItem(hand);
    } catch (e) { return false; }
}

// ---------------- 目标筛选（沿用「寂灭」那套） ----------------
var BL_NOT_TARGET = {};
try {
    var BL_SKIP = ['DROPPED_ITEM', 'ITEM', 'EXPERIENCE_ORB', 'ARROW', 'SPECTRAL_ARROW',
                   'ARMOR_STAND', 'ITEM_FRAME', 'GLOW_ITEM_FRAME', 'PAINTING',
                   'ITEM_DISPLAY', 'BLOCK_DISPLAY', 'TEXT_DISPLAY', 'INTERACTION',
                   'FALLING_BLOCK', 'PRIMED_TNT', 'END_CRYSTAL', 'BOAT', 'MINECART',
                   'LEASH_HITCH', 'MARKER', 'EGG', 'SNOWBALL', 'ENDER_PEARL',
                   'FIREBALL', 'SMALL_FIREBALL', 'DRAGON_FIREBALL', 'WITHER_SKULL',
                   'LLAMA_SPIT', 'SHULKER_BULLET', 'TRIDENT', 'FISHING_BOBBER'];
    for (var _i = 0; _i < BL_SKIP.length; _i++) BL_NOT_TARGET[BL_SKIP[_i]] = 1;
} catch (e) { }

var BL_LIVING_CLS = null;
try { BL_LIVING_CLS = Java.type('org.bukkit.entity.LivingEntity'); } catch (e) { BL_LIVING_CLS = null; }

function blIsCombatTarget(ent, tn) {
    try {
        if (tn != null && tn.length > 0 && BL_NOT_TARGET[tn] === 1) return false;
        if (BL_LIVING_CLS != null) {
            try { return BL_LIVING_CLS.class.isInstance(ent) === true; } catch (e) { }
        }
        return true;      // 拿不到类型信息 ⇒ 交给下面的 getHealth() 探针
    } catch (e) { return true; }
}

function blCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !BL_HIT_PLAYERS) return false;
        if (!blIsCombatTarget(ent, tn)) return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ---------------- 伤害器：1 点 · 无击退 · 消失无敌帧 ----------------
function blHurt(ent, dmg, p) {
    try {
        if (ent == null) return false;
        // ★ 「消失无敌帧」：打之前先清，保证这一下一定打得动
        try { ent.setNoDamageTicks(0); } catch (e) { }

        // 先存速度 —— 打完立刻还原 ⇒ 同刻内位移被抵消（= 不击退）
        var vx = 0.0, vy = 0.0, vz = 0.0, has = false;
        try {
            var v = ent.getVelocity();
            vx = v.getX(); vy = v.getY(); vz = v.getZ();
            has = true;
        } catch (e) { has = false; }

        var ok = false;
        try {
            try { ent.damage(dmg, p); ok = true; }
            catch (e1) { try { ent.damage(dmg); ok = true; } catch (e2) { ok = false; } }
        } catch (e) { ok = false; }

        if (has) { try { ent.setVelocity(blVec(vx, vy, vz)); } catch (e) { } }
        // ★ 打完之后**再清一次** —— 不留无敌帧给下一次（0.5 秒后还要再打）
        try { ent.setNoDamageTicks(0); } catch (e) { }
        return ok;
    } catch (e) { return false; }
}

var BL_VECC = null;
function blVec(x, y, z) {
    try {
        if (BL_VECC == null) BL_VECC = Java.type('org.bukkit.util.Vector');
        return new BL_VECC(x, y, z);
    } catch (e) { return null; }
}

// ---------------- 回血：命中 n 只 ⇒ 回 BL_HEAL_PER × n 点 ----------------
function blHeal(p, amount) {
    try {
        if (p == null || amount <= 0) return 0.0;
        var cur = 0.0;
        try { cur = 0 + p.getHealth(); } catch (e) { return 0.0; }
        if (cur <= 0) return 0.0;                       // 已经死了，不救

        var mx = 0.0;
        try { mx = 0 + p.getMaxHealth(); } catch (e) { mx = 0.0; }
        // 上限以「属性读到的」为准（可能被别的系统改过），双保险
        try {
            var A = Java.type('org.bukkit.attribute.Attribute');
            var inst = p.getAttribute(A.MAX_HEALTH);
            if (inst != null) {
                var b = 0 + inst.getValue();
                if (b > 0 && (mx <= 0 || b > mx)) mx = b;
            }
        } catch (e) { }
        if (mx <= 0) mx = 20.0;

        var to = cur + amount;
        if (to > mx) to = mx;
        if (to > cur) { try { p.setHealth(to); } catch (e) { return 0.0; } }
        return to - cur;
    } catch (e) { return 0.0; }
}

// ---------------- 判定盒：长 5 × 宽 5 × 高 2（沿准星三维朝向） ----------------
//   ★★ 第五十九批：由「水平前向 + 竖直」改成**完整三维基向量**。
//      返回 { list: [实体…], fx, fy, fz }；f 是归一化后的准星方向（粒子/调试要用）。
//   ★ 平视时 f=(0,0,1) / r=(−1,0,0) / u=(0,1,0) ⇒ 与旧版逐点等价（回归用例钉住了）。
function blBoxHit(p) {
    var out = { list: [], fx: 0.0, fy: 0.0, fz: 1.0 };
    try {
        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return out; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return out; }
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return out; }

        // ① 前向 f = **准星方向**（含俯仰，三维归一化）
        var fx = 0.0, fy = 0.0, fz = 1.0;
        try {
            fx = 0 + dir.getX(); fy = 0 + dir.getY(); fz = 0 + dir.getZ();
        } catch (e) { fx = 0.0; fy = 0.0; fz = 1.0; }
        var fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(fl > 1.0e-6)) { fx = 0.0; fy = 0.0; fz = 1.0; fl = 1.0; }
        fx /= fl; fy /= fl; fz /= fl;
        out.fx = fx; out.fy = fy; out.fz = fz;

        // ② 水平右向 r：取 f 的**水平分量**；正上/正下看时它退化成 0 ⇒ 退回用 yaw
        //    （和 gsEmBasis 完全同一套写法）
        var hx = fx, hz = fz;
        var hl = Math.sqrt(hx * hx + hz * hz);
        if (!(hl > 1.0e-4)) {
            var yaw = 0.0;
            try { yaw = 0 + loc.getYaw(); } catch (e) { yaw = 0.0; }
            var rad = yaw * Math.PI / 180.0;
            hx = -Math.sin(rad); hz = Math.cos(rad);      // 原版口径：yaw 0 = 朝 +Z
            hl = Math.sqrt(hx * hx + hz * hz);
            if (!(hl > 1.0e-6)) { hx = 0.0; hz = 1.0; hl = 1.0; }
        }
        hx /= hl; hz /= hl;
        var rx = -hz, ry = 0.0, rz = hx;                  // 面朝 +Z 时 = (−1,0,0) = 右手边

        // ③ 上向 u = r × f（右手系；抬头时它向后仰）
        var ux = ry * fz - rz * fy;
        var uy = rz * fx - rx * fz;
        var uz = rx * fy - ry * fx;

        var px0 = loc.getX(), py0 = loc.getY(), pz0 = loc.getZ();
        var halfW = BL_WIDE * 0.5;                        // 左右各 2.5
        var halfH = BL_H * 0.5;                           // 上下各 1.0（沿 u）

        // 搜索盒：中心放在「沿准星前移 mid 格」处，半宽取**外接球**半径。
        //  ★★ 判定体是**三维旋转**的、搜索盒是世界轴对齐的 ⇒ 三个方向都要罩住：
        //     沿 f 的跨度 = [−BL_BACK, BL_RANGE] ⇒ 半长 (RANGE+BACK)/2 = 2.7、中心偏 2.3
        //     外接球半径 = √(半长² + 半宽² + 半高²) = √(2.7² + 2.5² + 1.0²) ≈ 3.81
        //     （旧版只按水平算 √(2.5²+2.5²) ≈ 3.54 ⇒ 抬头/低头时高处低处的怪会漏搜）
        var halfR = (BL_RANGE + BL_BACK) * 0.5;
        var mid = (BL_RANGE - BL_BACK) * 0.5;
        var reach = Math.sqrt(halfR * halfR + halfW * halfW + halfH * halfH) + 0.2;
        var cbox = blLoc(world, px0 + fx * mid, py0 + 1.0 + fy * mid, pz0 + fz * mid);
        if (cbox == null) return out;

        var list = null;
        try {
            list = world.getNearbyEntities(cbox, reach, reach, reach);
        } catch (e) { list = null; }
        if (list == null) return out;

        var selfId = '' + p.getUniqueId();
        var seen = {};
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (!blCanHit(ent, selfId)) continue;
            var eid = '';
            try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
            if (seen[eid] != null) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }

            // 相对位移：原点 = 自己胸口（脚下 +1.0），怪的取样点 = 脚下 +0.9
            var dx = el.getX() - px0;
            var dy = (el.getY() + 0.9) - (py0 + 1.0);
            var dz = el.getZ() - pz0;

            var fwd = dx * fx + dy * fy + dz * fz;        // 长（沿准星，含俯仰）
            var lat = dx * rx + dy * ry + dz * rz;        // 宽（沿水平右向）
            var ver = dx * ux + dy * uy + dz * uz;        // 高（沿 r×f）
            if (fwd < -BL_BACK || fwd > BL_RANGE) continue;
            if (lat < -halfW || lat > halfW) continue;
            if (ver < -halfH || ver > halfH) continue;

            seen[eid] = 1;
            out.list.push(ent);
        }
    } catch (e) { }
    return out;
}

// ---------------- 吸血粒子：从怪身上飞向玩家，持续 0.5 秒 ----------------
//   ★ runLater 给不了句柄 ⇒ 用**只增不减**的 BL_GEN 断链
var BL_SIP = {};     // chainId -> { gen, p, targets, i }
var BL_GEN = 0;      // ★ 全局单调递增，永不复位

function blSipStart(p, targets) {
    try {
        if (p == null || targets == null || targets.length === 0) return;
        BL_GEN = BL_GEN + 1;                    // ★ 只增不减
        var gen = BL_GEN;
        var id = '' + gen;
        BL_SIP[id] = { gen: gen, p: p, targets: targets, i: 0 };
        blSipLoop(id, gen);
    } catch (e) { }
}

// 画「第 i 步」的粒子：每只怪各自从自己胸口飞向玩家胸口
function blSipDraw(st) {
    try {
        var p = st.p;
        var pl = null;
        try { pl = p.getLocation(); } catch (e) { return; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

        var px = pl.getX(), py = pl.getY() + 1.0, pz = pl.getZ();
        var t = st.i / BL_SIP_TICKS;            // 0 → 1（这一步的进度）
        var n = st.targets.length;

        for (var k = 0; k < n; k++) {
            var ent = st.targets[k];
            if (ent == null) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }   // 拿不到位置就跳过
            var ex = el.getX(), ey = el.getY() + 0.9, ez = el.getZ();

            // 每只怪错开一点点 ⇒ 看起来是一串血珠，不是一团
            var base = t + k * BL_SIP_STAGGER;
            for (var j = 0; j < BL_SIP_PER_TICK; j++) {
                var f = base + j * (BL_SIP_STAGGER * 0.5);
                if (f < 0.0) f = 0.0;
                if (f > 1.0) f = 1.0;
                var x = ex + (px - ex) * f;
                var y = ey + (py - ey) * f;
                var z = ez + (pz - ez) * f;
                // 轻微上凸的弧线（0 就是直线）
                y += Math.sin(f * Math.PI) * BL_SIP_ARC;
                blDust(world, x, y, z);
            }
        }
    } catch (e) { }
}

// 自调度链：每刻一步。玩家退服 / 走完 10 刻 ⇒ 断链
function blSipLoop(id, gen) {
    try {
        var st = null;
        try { st = BL_SIP[id]; } catch (e0) { st = null; }
        if (st == null || st.gen !== gen) return;      // ★ 已被取代 ⇒ 断链

        var p = st.p;
        var ok = true;
        try { if (p == null || !p.isOnline()) ok = false; } catch (e1) { ok = false; }
        if (!ok) { BL_SIP[id] = null; return; }

        blSipDraw(st);
        st.i = st.i + 1;
        if (st.i >= BL_SIP_TICKS) { BL_SIP[id] = null; return; }   // 0.5 秒飞完

        var st2 = null;
        try { st2 = BL_SIP[id]; } catch (e2) { st2 = null; }
        if (st2 != null && st2.gen === gen) {
            blSchedule(function () { blSipLoop(id, gen); }, 1);
        }
    } catch (e) { }
}

// ---------------- 排任务 ----------------
//   拿插件实例（只在 runLater 不可用的退路里才用得到）
function blPlugin() {
    try {
        var pm = getServer().getPluginManager();
        var pl = null;
        try { pl = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { pl = null; }
        if (pl == null) {
            try { pl = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; }
            catch (e) { }
        }
        if (pl == null) { try { pl = pm.getPlugin('Slimefun'); } catch (e) { } }
        return pl;
    } catch (e) { return null; }
}

// 排一步（delay 刻之后执行 fn）。
//  ★★ 三条路依次试，**第一条是正路**，后两条只是「别整个哑掉」的保险。
function blSchedule(fn, delay) {
    // ① 正路：RSC 注入的 runLater（本附属十几处心跳都在用，实测可用）
    try { runLater(fn, delay); return true; } catch (eRL) { }

    // ② 退路一：用 Java.extend **显式**造一个 Runnable，避开重载歧义
    try {
        var RC = Java.type('java.lang.Runnable');
        var Impl = Java.extend(RC, { run: function () { fn(); } });
        var rr = new Impl();
        var sched = getServer().getScheduler();
        var pl = blPlugin();
        if (sched != null && pl != null) { sched.runTaskLater(pl, rr, delay); return true; }
    } catch (e1) { }

    // ③ 退路二：直接传函数（多半会因重载歧义失败，但试一下不要钱）
    try {
        var sched2 = getServer().getScheduler();
        var pl2 = blPlugin();
        if (sched2 != null && pl2 != null) { sched2.runTaskLater(pl2, fn, delay); return true; }
    } catch (e2) {
        try { getServer().getLogger().warning('[构思技艺] 借寿 排任务失败: ' + e2); } catch (e3) { }
    }
    return false;
}

// ---------------- 音效（可关：把 BL_SOUND 设成 null） ----------------
var BL_SOUND = 'entity.evoker.cast_spell';

function blCastSound(p) {
    try {
        if (BL_SOUND == null) return;
        p.getWorld().playSound(p.getLocation(), BL_SOUND, 0.55, 1.35);
    } catch (e) { }
}

// ---------------- 入口 ----------------
function onUse(event) {
    try {
        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        // ★ 只认主手触发
        if (!blIsMainHandUse(event, p)) return;

        // ★ 去抖（一次右键的两个事件只认一个）—— **只查，不武装**
        if (blIsDuplicate(p)) return;

        // 冷却中 ⇒ 静默忽略（不提示、不出声、不消耗，也**不武装去抖窗口**）
        var left = blCooldownLeft(p);
        if (left > 0) { blCooldownHint(p, left); return; }

        // ★ 到这一步才是"真的按生效了" ⇒ 现在才武装去抖窗口
        blArmDebounce(p);
        blMarkUsed(p);

        // ① 判定盒：长 5 × 宽 5 × 高 2
        var box = blBoxHit(p);
        var targets = box.list;
        var n = targets.length;

        // ② 每只 1 点伤害（无击退 · 消失无敌帧）
        for (var i = 0; i < n; i++) {
            try { blHurt(targets[i], BL_DMG, p); } catch (e) { }
        }

        // ③ 命中 n 只 ⇒ 回 1×n 点血
        var healed = 0.0;
        if (n > 0) { healed = blHeal(p, BL_HEAL_PER * n); }

        // ④ 吸血粒子：从每只怪身上飞向玩家，0.5 秒
        if (n > 0) { blSipStart(p, targets); }

        // ★★ 第六十二批：原来的第 ⑤ 步「施法提示词：寿来 寿来 寿来」**已整段删除**
        //    （用户「借寿提示词删除」）⇒ 现在施法只出声、不写字。
        blCastSound(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 借寿 onUse 出错: ' + err); } catch (e) { }
    }
}
