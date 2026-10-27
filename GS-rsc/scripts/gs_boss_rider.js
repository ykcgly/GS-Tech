/* ==========================================================
 * 构思技艺 (GS) —— 「枯骸骑将」（boss · 凋零骷髅头颅召唤）
 *   （2026-10-06 由「白骨骑将」改名：物品 枯骸骑将 / 骷髅 枯骸将军 / 骷髅马 璇骊）
 * items.yml 里由  script: gs_boss_rider  引用；入口函数 onUse(event)
 * ----------------------------------------------------------
 * 右键 = 召唤一只 boss（**消耗品**：召唤成功才扣 1 个）
 *
 *   ★ boss 长相
 *     · **骷髅**（Skeleton，头顶名 **枯骸将军**），**骑在一匹骷髅马上**（名 **璇骊**）
 *     · 头戴 **下界合金头盔**（★ 第二十批：头部材质原为「侦测器」，已换）、
 *       身穿**下界合金全套**（头盔/胸甲/护腿/靴子）
 *     · 主手 **金剑**（替换掉骷髅自带的弓 ⇒ 只会近战，不再射箭）
 *     · 盔甲与剑**全部 setUnbreakable(true)** ⇒ **永不掉耐久**，且死亡不掉装备
 *   ★ 出场提示词（同世界所有在线玩家都收得到）
 *     · 枯骸将军：**枯骸未朽，骑将仍征**
 *     · 璇骊：**随将而行，一往无前**
 *   ★ **boss 血条**：枯骸将军 / 璇骊 各挂一条 BossBar（类似凋零、末影龙），
 *     进度 = 各自当前血量 / 最大血量
 *     ★ **只有召唤它的那个玩家看得到**（BR_BAR_ONLY_OWNER = true；改成 false 就是
 *       范围内所有人都能看到）；走远 / 下线 / boss 没了自动收掉
 *   ★ 血量：boss **500**、战马 **250**
 *   ★ 基础（普攻）伤害 **15 点**（2026-10-06：2 → **15**）
 *   ★ 三个技能 = 五骊矟的**削弱版**，**全程没有任何提示词**
 *     · **斩击**：5 点伤害、冷却 **3.5 秒**、射程 10 格（带一点击退 + 横扫粒子）
 *       ★ 剑气**由 boss 射出、一路飞过去**（不是瞬间糊脸）：先 0.3 秒举剑蓄势，
 *         再以约 0.35 格/刻推进 ⇒ 飞完 10 格要 1.4 秒，玩家看得见、躲得开
 *         （想更安全就把 BR_QI_RANGE 调小 / BR_QI_STEP 调慢）
 *       ★ **出场 BR_FIRST_QI 毫秒内不出斩击**（默认 5 秒）——刚召唤就劈一刀玩家反应不过来
 *     · **冲刺**：8 点伤害、冷却 **30 秒**、朝目标冲 **7 格**（1 秒动画，不是瞬移）
 *       —— **冲刺期间 boss 不无敌**（本作 default 就不无敌）
 *       ★ **撞墙不再穿墙（2026-10-06）**：1 格高的障碍 = **当台阶踩上去**继续冲；
 *         **2 格以上**直接**停在墙前结束冲刺**（BR_DASH_STEP_UP / BR_DASH_STOP_H）
 *     · **伺骨**（2026-10-06 新增）：冷却 **30 秒**，召 **3 只**金套骷髅「**伺骨**」，
 *       各骑一匹骷髅马「**残驹**」；伺骨 50 血、伤害 5 点、无特殊技能；残驹 50 血。
 *       ★ **共生**：残驹死 → 伺骨立刻同死；伺骨死 → 残驹立刻同死。
 *       ★ **只存在 30 秒**到点化烟消失（防 boss 一直召唤把服卡死）；无掉落、经验也不掉。
 *   ★ **狂暴（马先死）**：战马比 boss 先死 → boss 被激怒，解锁新能力 ——
 *     朝**最近的玩家**发射**凋零之首**（`setCharged(false)` = 黑色，**不会炸坏地形**），
 *     ★ **追踪射弹**：飞出去之后一路咬着目标修正方向（急转 / 绕柱子仍能甩掉）；
 *     命中 **20 点伤害 + 凋零 II 5 秒**；冷却 4 秒
 *   ★ **boss 死亡 → 战马跟着一起死**；**战马先死 → boss 狂暴**（上面那条）
 *   ★ **原版掉落物全部删除**（骷髅的骨头 / 箭、骷髅马的骨头一律不掉）：
 *     空战利品表 + 死亡事件里 `getDrops().clear()` 双保险
 *   ★ **boss 死亡必掉「五骊矟」**（GS_WULI_SPEAR，掉在它死的地方）
 *
 *   ★ **阵营（2026-10-06）**：枯骸将军 / 璇骊 / 伺骨 / 残驹 **都是自己人** ——
 *     斩击、冲刺、凋零之首**不会误伤**自己人（以前斩击会砍到伺骨，伺骨挨刀就回头打 boss）；
 *     三层保险：① 斩击/冲刺的命中列表里跳过自己人 ② `EntityDamageByEntityEvent`
 *     里"自己人打自己人"直接免伤 ③ `EntityTargetEvent` 里禁止把自己人当目标。
 *   ★ **出场 BGM（2026-10-06）**：boss 周围 **BR_MUSIC_RANGE 格内循环播放**
 *     唱片 **Lena Raine - Pigstep**（**猪灵堡垒遗迹**产出的那张，`music_disc.pigstep`，
 *     时长 2:28 ⇒ 每 148 秒重放）；走远立刻停、走近从头播，boss 归天全部停掉。
 *     服务器没这个音效时自动退到 `BR_MUSIC_FALLBACK`。
 *   ★ **残驹不索敌（2026-10-06）**：残驹（伺骨的骷髅马）**自己不挑目标**，
 *     全程只按**伺骨锁定的那个目标**走 ⇒ 马和骑手不会再各追一个玩家互相拖拽。
 *
 * ----------------------------------------------------------
 * ★★ 「基础伤害 20 点」是怎么落地的（重要，别乱改）：
 *   骷髅拿着**金剑**时，金剑自带的 +4 攻击力会**叠加**到实体属性上，
 *   所以"属性写 20"实际打出来是 24。这里把属性 base 设成
 *   `BR_BASE_DMG - BR_WEAPON_BONUS`(= 16)，最终值才正好 = **20 点**。
 *   如果你的服里"怪物不吃武器属性加成"，把 BR_WEAPON_BONUS 改成 0.0 即可。
 * ----------------------------------------------------------
 * ★ 每个玩家**同时只能有 1 只**活着的枯骸骑将（再右键静默忽略），防连点刷爆。
 * ★ boss 打的是 gs:boss 标记（**不是** gs:summon）⇒ 梦境护腿的杀戮光环
 *   **照常能打到它**（它是敌人，不是召唤物）。
 * ★ **不能与方块交互**：手持本物品右键方块时，方块功能一律取消（HIGHEST 监听器），
 *   但**照样召唤 boss** —— 和「冰」同一个套路。
 * ========================================================== */

try { getServer().getLogger().info('[构思技艺] gs_boss_rider 已加载'); } catch (e) { }

// ---------------- 参数 ----------------
var BR_ITEM_ID      = 'GS_BONE_RIDER';

// ★ 2026-10-06 改名：骷髅 = 枯骸将军，骷髅马 = 璇骊
var BR_BOSS_NAME    = '\u00a7f枯骸将军';   // §f枯骸将军（骷髅头顶名；实体名必须用 §，& 不会被翻译）
var BR_HORSE_NAME   = '\u00a7f璇骊';       // §f璇骊（骷髅马头顶名）

// ★ 出场提示词（同世界所有在线玩家都会收到）
var BR_MSG_BOSS     = '&8【&f枯骸将军&8】&f枯骸未朽，骑将仍征';
var BR_MSG_HORSE    = '&8【&f璇骊&8】&f随将而行，一往无前';

// ★ boss 血条（BossBar）
var BR_BAR          = true;      // false = 关掉血条
var BR_BAR_ONLY_OWNER = true;    // ★ true = **只有召唤它的那个玩家**看得到血条（false = 范围内所有人都看得到）
var BR_BAR_RANGE    = 64.0;      // 多少格内才显示（只在 BR_BAR_ONLY_OWNER = false 时对所有人生效）

var BR_BOSS_HP      = 800.0;    // boss 血量（2026-10-05：350 → 200 → 500 → **800**）
var BR_HORSE_HP     = 450.0;    // 战马血量（2026-10-05：150 → 250 → **450**）
var BR_HORSE_SPEED  = 0.25;     // 战马移速（比玩家略快，追得上人）
var BR_BASE_DMG     = 20.0;     // 基础（普攻）伤害（2026-10-06：2 → 15 → **20**）
var BR_WEAPON_BONUS = 4.0;      // 金剑自带攻击力（原版 4 点；你的服不吃就改 0.0）

// ---------------- 阵营（2026-10-06）：枯骸将军 / 璇骊 / 伺骨 / 残驹 都是**自己人** ----------------
var BR_ALLY         = true;     // true = 开启"不打自己人"（斩击/冲刺/凋零首都不会误伤，伺骨也不会回头打 boss）
var BR_ALLY_TAGS    = {
    'bone_rider': 1, 'bone_rider_horse': 1,
    'bone_rider_servant': 1, 'bone_rider_servant_horse': 1
};

// ---------------- 出场 BGM（2026-10-06）：boss 周围循环播放唱片《Pigstep》 ----------------
//   Lena Raine - Pigstep（**猪灵堡垒遗迹**产出的那张，1.16 加入的 music_disc_pigstep，
//   **时长 2 分 28 秒** = 148 秒）⇒ 音效名 music_disc.pigstep
//   万一这台服务器没有这个音效，会自动退到 BR_MUSIC_FALLBACK
var BR_MUSIC        = true;      // false = 不放音乐
var BR_MUSIC_SOUND  = 'music_disc.pigstep';     // ★ Lena Raine - Pigstep（猪灵堡垒遗迹产出）
var BR_MUSIC_FALLBACK = 'music_disc.ward';      // 没有 pigstep 时的备选（同样偏强劲）
var BR_MUSIC_RANGE  = 64.0;      // ★ boss 多少格内的玩家听得到
var BR_MUSIC_VOL    = 4.0;       // 音量（4.0 = 唱片机原版音量 ⇒ 衰减距离 16 × 4 ≈ 64 格听得见）
var BR_MUSIC_LOOP_MS= 148000;    // ★ 循环间隔 = 曲目长度 2:28 = 148 秒（到点重放 ⇒ 无缝循环）

// 斩击（★ 由 boss 射出、一路飞过去 ⇒ 玩家有时间反应）
var BR_QI_DMG       = 7.0;      // 7 点（2026-10-06：5 → **7**）
var BR_QI_CD_MS     = 3500;     // 冷却 3.5 秒
var BR_QI_RANGE     = 10.0;     // 射程 10 格（觉得还是太远就改 8.0）
var BR_QI_HALF_W    = 2.5;      // 左右各 2.5 格
var BR_QI_HALF_H    = 2.0;      // 上下容差
var BR_QI_KB        = 0.3;      // 一点点击退
var BR_QI_KB_UP     = 0.1;
var BR_QI_WINDUP    = 6;        // 举剑蓄势 6 刻（0.3 秒）：先出粒子再飞，给反应时间
var BR_QI_STEP      = 0.35;     // 剑气**每刻推进多少格** ⇒ 10 格要 29 刻 ≈ 1.45 秒
var BR_QI_BAND      = 1.2;      // 波前的有效厚度（格）：只有波前扫过时才判定命中

// 冲刺
var BR_DASH_DMG     = 8.0;      // 8 点
var BR_DASH_PIERCE  = true;     // ★ 冲刺伤害**无视防具**（2026-10-06）：盔甲/抗性/保护附魔**一律不减伤**
var BR_DASH_CD_MS   = 30000;    // 冷却 30 秒
var BR_DASH_DIST    = 7.0;      // ★ 冲 7 格（原 12 格，太远了）
var BR_DASH_TICKS   = 20;       // 1 秒动画（不是瞬移）
var BR_DASH_HALF_W  = 2.5;      // 路径左右各 2.5 格（宽 5 格）
var BR_DASH_RANGE   = 20.0;     // 目标超过 20 格就不冲（免得隔半个地图乱冲）

// ★ 冲刺撞墙规则（2026-10-06）：**不再穿墙**
var BR_DASH_STEP_UP = 1;        // 最多能自动**抬几格**：1 = 一格高的东西当台阶踩上去（斜坡 / 楼梯）
                                //                   0 = 只有完全平整的地面才冲得过去
var BR_DASH_STOP_H  = 2;        // 障碍高度 ≥ 这个数 ⇒ **撞墙**，冲刺立刻停在墙前（默认 2 格）

// 狂暴：凋零之首（战马先死才解锁）
var BR_SKULL_DMG        = 20.0; // ★ 命中 20 点（2026-10-05：10 → 20）
var BR_SKULL_WITHER_T   = 100;  // ★ 凋零 5 秒（100 刻）
var BR_SKULL_WITHER_AMP = 1;    // ★ 凋零等级：1 = **凋零 II**（0 = 凋零 I）
var BR_SKULL_CD_MS      = 4000; // 冷却 4 秒
var BR_SKULL_SPEED      = 1.1;  // 飞行速度（越小越好躲）
var BR_SKULL_RANGE      = 24.0; // 超过这个距离不发

// ★ 追踪制导（2026-10-05 新增）：发射出去之后**一路追着目标飞**
var BR_SKULL_HOME       = true;  // false = 退回直线飞行（打哪指哪）
var BR_SKULL_HOME_TICK  = 2;     // 每几刻修正一次方向（2 刻 = 0.1 秒，够平滑又不费性能）
var BR_SKULL_TURN       = 0.32;  // 每次修正往目标方向偏转多少（0~1）
                                 //   0.32 ⇒ 追得上但**绕圈/急转能甩掉**；想要"咬死不放"就写 1.0
var BR_SKULL_HOME_RANGE = 32.0;  // 制导范围：超过 32 格就放弃追踪（免得飞出地图还在追）
var BR_SKULL_LIFE       = 100;   // 追踪最长 100 刻（5 秒）⇒ 到点自毁，不会永远飘在天上

// ---------------- 伺骨（2026-10-06 新增：冷却 30 秒 · 召 3 只金套骷髅 · 各配一匹骷髅马）----------------
var BR_SV_CD_MS     = 30000;    // 冷却 30 秒
var BR_SV_FIRST     = 8000;     // 出场 8 秒后才第一次召唤（给玩家喘息）
var BR_SV_COUNT     = 3;        // 一次召 3 只
var BR_SV_MAX_ALIVE = 3;        // 场上同时最多 3 只（满了就不召，也不重置 CD）
var BR_SV_HP        = 50.0;     // 伺骨血量 50
var BR_SV_HORSE_HP  = 50.0;     // 残驹血量 50
var BR_SV_DMG       = 5.0;      // 伺骨伤害 5 点（**含金剑那 4 点** ⇒ 属性 base 写成 5-4）
var BR_SV_LIFE      = 600;      // 只存在 30 秒（600 刻），到点化烟消失
var BR_SV_SPEED     = 0.28;     // （旧参数，留着备用）残驹的巡航速度
var BR_SV_STEP      = 0.22;     // ★ 残驹**每刻**往前挪多少格（0.22 格/刻 ≈ 4.4 格/秒）
var BR_SV_STOP_R    = 1.2;      // 离目标还有这么多格就停下（贴太近会把玩家顶飞）
var BR_SV_SEEK      = 32.0;     // 超过 32 格就不追了
// ★ 2026-10-06：残驹**不索敌**。以前是"离残驹最近的玩家"，和伺骨自己 AI 锁的目标
//   常常不是同一个人 ⇒ 马往这边、骑手往那边，互相拖拽。现在统一听伺骨的。
var BR_SV_HORSE_NO_TARGET = true;   // true = 每刻清掉残驹自己的目标（马不挑敌人）
// ★ 2026-10-06：伺骨索敌**跳过创造模式**（创造模式的玩家不该被围殴；旁观也照旧跳过）
var BR_SV_IGNORE_CREATIVE = true;
var BR_SV_IGNORE_SPECTATOR = true;
var BR_SV_NAME      = '\u00a7f伺骨';   // §f伺骨
var BR_SV_HORSE_NAME= '\u00a7f残驹';   // §f残驹

var BR_TICK         = 5;        // AI 心跳：每 5 刻（0.25 秒）一次
var BR_SEEK_RANGE   = 24.0;     // 找目标的范围（优先用怪物 AI 自己的目标）
var BR_FIRST_DASH   = 5000;     // 出场 5 秒后才允许第一次冲刺
var BR_FIRST_QI     = 5000;     // ★ 出场 5 秒后才允许第一次**斩击**（刚召唤就劈一刀玩家反应不过来）
var BR_SPAWN_AHEAD  = 3.0;      // 在玩家身前 3 格生成
var BR_CONSUME      = true;      // ★ 消耗品（想改成无限用就设 false）
var BR_DROP_ID      = 'GS_WULI_SPEAR';   // ★ boss 死亡**必掉**的物品（五骊矟）

// ---------------- 运行时状态 ----------------
var BR_BOSSES = {};     // boss uuid -> ref（ref = { boss, horse, owner, mode, qiAt, dashAt, ... }）
var BR_OWNER  = {};     // 玩家 uuid -> boss uuid（每个玩家同时 1 只）
var BR_LAST   = {};     // 玩家 uuid -> 上次召唤时间戳（防同一次点击被派发两遍）
var BR_H2B    = {};     // 战马 uuid -> boss uuid（战马死了要反查回 ref）

// ---------------- 伺骨的运行时索引（2026-10-06）----------------
var BR_SERVANTS = {};   // 伺骨 uuid -> sv（sv = { sk, horse, bossRef, life, done }）
var BR_SV_H2S   = {};   // 残驹 uuid -> 伺骨 uuid（残驹死了要反查回 sv）
var BR_SV_S2H   = {};   // 伺骨 uuid -> 残驹 uuid

// boss 标记（护腿光环用 gs:summon 跳过召唤物；这里是 gs:boss ⇒ **不跳过**，能被光环打到）
var BR_BOSS_KEY  = null;
var BR_SKULL_KEY = null;    // 狂暴后发射的凋零之首
var BR_PDT       = null;
try {
    BR_PDT       = Java.type('org.bukkit.persistence.PersistentDataType');
    BR_BOSS_KEY  = Java.type('org.bukkit.NamespacedKey').fromString('gs:boss');
    BR_SKULL_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:boss_skull');
} catch (e) { BR_BOSS_KEY = null; BR_SKULL_KEY = null; BR_PDT = null; }

// 读 / 写实体上的 gs:boss 标记（值：'bone_rider' / 'bone_rider_horse'）
function brTag(ent) {
    try {
        if (ent == null || BR_BOSS_KEY == null || BR_PDT == null) return '';
        var v = ent.getPersistentDataContainer().get(BR_BOSS_KEY, BR_PDT.STRING);
        return (v == null ? '' : '' + v);
    } catch (e) { return ''; }
}

// ---------------- 小工具 ----------------
function brNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

// 材质名兼容（老版本 / 新版本叫法不同 → 逐个试）
function brMat(cands) {
    try {
        var M = Java.type('org.bukkit.Material');
        for (var i = 0; i < cands.length; i++) {
            try {
                var m = M.valueOf(cands[i]);
                if (m != null) return m;
            } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// 造一个「永不掉耐久」的装备
function brGear(cands) {
    var m = brMat(cands);
    if (m == null) return null;
    try {
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        var s = new ItemStack(m, 1);
        try {
            var meta = s.getItemMeta();
            if (meta != null) {
                try { meta.setUnbreakable(true); } catch (e) { }
                s.setItemMeta(meta);
            }
        } catch (e) { }
        return s;
    } catch (e) { return null; }
}

// 设属性值（属性名兼容 1.21+ / 旧版）
function brSetBase(ent, names, v) {
    try {
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var k = null;
        for (var i = 0; i < names.length; i++) {
            try { k = Attr[names[i]]; } catch (e) { k = null; }
            if (k == null) { try { k = Attr.valueOf(names[i]); } catch (e2) { k = null; } }
            if (k != null) break;
        }
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        inst.setBaseValue(v);
        return true;
    } catch (e) { return false; }
}

function brAlive(ent) {
    try { if (ent == null) return false; return !(ent.isDead() || !ent.isValid()); }
    catch (e) { return false; }
}

function brDist(a, b) {
    try { return a.getLocation().distance(b.getLocation()); } catch (e) { return 999.0; }
}

// ★ 出场提示词：发给 **同世界所有在线玩家**（msg 用 & 颜色码，走 RSC 的 sendMessage）
function brAnnounce(world, msg) {
    try {
        var wname = '';
        try { wname = '' + world.getName(); } catch (e) { wname = ''; }
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try {
                if (wname.length > 0 && '' + p.getWorld().getName() !== wname) continue;
            } catch (e) { continue; }
            try { sendMessage(p, msg); }
            catch (e1) {
                // 降级：自己把 & 颜色码翻成 § 再发
                try { p.sendMessage(msg.replace(/&([0-9a-fA-Fk-oK-OrR])/g, '\u00a7$1')); }
                catch (e2) { try { p.sendMessage(msg); } catch (e3) { } }
            }
        }
    } catch (e) { }
}

// ==================================================================
// ★ 阵营判定（2026-10-06）：枯骸将军 / 璇骊 / 伺骨 / 残驹 = **自己人**
// ==================================================================
//   带 gs:boss 标记且值在 BR_ALLY_TAGS 里就是自己人 ⇒
//   ① 斩击 / 冲刺 / 凋零之首**不会误伤**他们（否则伺骨挨一刀就会回头砍 boss）
//   ② 监听器里再兜一层：**自己人造成的伤害打到自己人身上直接取消**（防 AI 平A / 弓箭 / 爆炸）
//   ③ EntityTargetEvent 里**禁止自己人把自己人当目标**
function brIsAlly(ent) {
    try {
        if (!BR_ALLY) return false;
        if (ent == null) return false;
        if (BR_BOSS_KEY == null || BR_PDT == null) return false;
        var pd = ent.getPersistentDataContainer();
        if (pd == null) return false;
        if (!pd.has(BR_BOSS_KEY, BR_PDT.STRING)) return false;
        var v = '' + pd.get(BR_BOSS_KEY, BR_PDT.STRING);
        return (BR_ALLY_TAGS[v] === 1);
    } catch (e) { return false; }
}

// ★ 这是不是"残驹"（伺骨骑的那匹骷髅马）？（2026-10-06：残驹永不索敌）
function brIsServantHorse(ent) {
    try {
        if (ent == null) return false;
        // ① 索引里查得到（快）
        try {
            var uid = '' + ent.getUniqueId();
            if (BR_SV_H2S != null && BR_SV_H2S[uid] != null) return true;
        } catch (e) { }
        // ② 兜底：看 PDC 标记
        if (BR_BOSS_KEY == null || BR_PDT == null) return false;
        var pd = ent.getPersistentDataContainer();
        if (pd == null) return false;
        if (!pd.has(BR_BOSS_KEY, BR_PDT.STRING)) return false;
        return ('' + pd.get(BR_BOSS_KEY, BR_PDT.STRING)) === 'bone_rider_servant_horse';
    } catch (e) { return false; }
}

// ★ 这是不是"伺骨"本体？（伺骨不许盯创造 / 旁观 ⇒ 索敌与伤害都要认得出它）
function brIsServant(ent) {
    try {
        if (ent == null) return false;
        try {
            var uid = '' + ent.getUniqueId();
            if (BR_SERVANTS != null && BR_SERVANTS[uid] != null) return true;
        } catch (e) { }
        if (BR_BOSS_KEY == null || BR_PDT == null) return false;
        var pd = ent.getPersistentDataContainer();
        if (pd == null) return false;
        if (!pd.has(BR_BOSS_KEY, BR_PDT.STRING)) return false;
        return ('' + pd.get(BR_BOSS_KEY, BR_PDT.STRING)) === 'bone_rider_servant';
    } catch (e) { return false; }
}

// ★ 伺骨 / 残驹 打到 **创造·旁观** 的玩家 ⇒ 直接免伤（索敌已经跳过了，这里是最后一道保险）
function brOnServantDamage(e) {
    try {
        if (e == null) return;
        var victim = null, src = null;
        try { victim = e.getEntity(); } catch (err) { return; }
        try { src = e.getDamager(); } catch (err) { src = null; }
        if (victim == null || src == null) return;
        if (!brIsServant(src) && !brIsServantHorse(src)) return;
        if (!brSvBadGM(victim)) return;                 // 只拦"创造 / 旁观"
        try { e.setCancelled(true); } catch (err) { }
        try { e.setDamage(0.0); } catch (err) { }
        try { src.setTarget(null); } catch (err) { }
    } catch (e) { }
}

// ★ 兜底：伤害来源是"自己人"（含它射出的弹体）⇒ 打到自己人身上直接免伤
function brSrcIsAlly(src) {
    try {
        if (!BR_ALLY || src == null) return false;
        if (brIsAlly(src)) return true;
        // 弹体：看射手
        var sh = null;
        try { sh = src.getShooter(); } catch (e) { sh = null; }
        if (sh != null) {
            try {
                if (brIsAlly(sh)) return true;
            } catch (e2) { }
        }
        // 带 boss_skull 标记的凋零之首
        var pd = null;
        try { pd = src.getPersistentDataContainer(); } catch (e) { pd = null; }
        if (pd != null && BR_SKULL_KEY != null && BR_PDT != null) {
            try { if (pd.has(BR_SKULL_KEY, BR_PDT.STRING)) return true; } catch (e) { }
        }
    } catch (e) { }
    return false;
}

// ★ 禁止"自己人把自己人当目标"（AI 层面的保险）
function brOnAllyTarget(e) {
    try {
        if (e == null) return;
        var ent = null, tgt = null;
        try { ent = e.getEntity(); } catch (err) { ent = null; }
        try { tgt = e.getTarget(); } catch (err) { tgt = null; }

        // ★ 残驹**永不索敌**（2026-10-06）：不管盯上谁，一律掐掉 —— 它只听伺骨的
        if (BR_SV_HORSE_NO_TARGET && ent != null && brIsServantHorse(ent)) {
            try { e.setCancelled(true); } catch (err) { }
            try { e.setTarget(null); } catch (err) { }
            return;
        }

        // ★ 伺骨也不许盯**创造 / 旁观**（2026-10-06）
        if (ent != null && tgt != null && brIsServant(ent) && brSvBadGM(tgt)) {
            try { e.setCancelled(true); } catch (err) { }
            try { e.setTarget(null); } catch (err) { }
            return;
        }

        if (!BR_ALLY || ent == null || tgt == null) return;
        if (!brIsAlly(ent)) return;
        if (!brIsAlly(tgt)) return;
        try { e.setCancelled(true); } catch (err) { }
        try { e.setTarget(null); } catch (err) { }
    } catch (err) { }
}

// ★ 禁止"自己人打自己人"（伤害层面的保险：近战 / 弓箭 / 爆炸全算）
function brOnAllyDamage(e) {
    try {
        if (!BR_ALLY || e == null) return;
        var victim = null, src = null;
        try { victim = e.getEntity(); } catch (err) { victim = null; }
        try { src = e.getDamager(); } catch (err) { src = null; }
        if (victim == null) return;
        if (!brIsAlly(victim)) return;
        if (src == null) return;
        if (!brSrcIsAlly(src)) return;
        try { e.setCancelled(true); } catch (err) { }
        try { e.setDamage(0.0); } catch (err) { }
    } catch (e) { }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function brIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === BR_ITEM_ID) return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === BR_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

// ★ 只认主手（Slimefun 对副手也会派发 onUse ⇒ 副手会"不消耗却生效"）
function brIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return brIsOurItem(hand);
    } catch (e) { return false; }
}

function brConsume(p) {
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

// ---------------- ★ 马鞍（2026-10-06 第三轮重写）----------------
//   之前只写一次就没了（残驹身上看不到鞍），现在改成：
//     ① 造鞍（brGear 失败就直接 new ItemStack）
//     ② 四条写入路径全试一遍（不同版本 API 名字不一样，哪条通算哪条）
//     ③ **写完之后立刻回读验证**，没装上也返回 true（让调用方知道要补装）
//     ④ 生成后每隔一阵**复查补装**（见 brSvTick / brTick），掉了一次也能自己补回来
function brSaddle(h) {
    try {
        if (h == null) return false;

        var s = null;
        try { s = brGear(['SADDLE']); } catch (e) { s = null; }
        if (s == null) {
            try {
                var M = Java.type('org.bukkit.Material');
                var m = null;
                try { m = M.SADDLE; } catch (e) { m = null; }
                if (m == null) { try { m = M.valueOf('SADDLE'); } catch (e) { m = null; } }
                if (m != null) s = new (Java.type('org.bukkit.inventory.ItemStack'))(m, 1);
            } catch (e) { s = null; }
        }
        if (s == null) return false;

        var inv = null;
        try { inv = h.getInventory(); } catch (e) { inv = null; }

        var wrote = false;
        if (inv != null) {
            try { inv.setSaddle(s); wrote = true; } catch (e) { }
            try { inv.setItem(0, s); wrote = true; } catch (e) { }
            try {
                var cur = inv.getItem(0);
                if (cur != null && ('' + cur.getType().name()).indexOf('SADDLE') >= 0) wrote = true;
            } catch (e) { }
        }
        try { h.setSaddle(s); wrote = true; } catch (e) { }   // 少数分支有这个便捷方法

        // 回读验证：真的装上了吗？
        try {
            if (inv != null) {
                var chk = null;
                try { chk = inv.getSaddle(); } catch (e) { chk = null; }
                if (chk == null) { try { chk = inv.getItem(0); } catch (e) { chk = null; } }
                if (chk != null && ('' + chk.getType().name()).indexOf('SADDLE') >= 0) return true;
            }
        } catch (e) { }
        return wrote;
    } catch (e) { return false; }
}

// ---------------- 生成：战马（通用）----------------
// hp / name / tag 不传就是 boss 的璇骊；传了就按参数造（伺骨的残驹走同一条路）
function brSpawnHorse(world, loc, hp, name, tag) {
    try {
        var _hp   = (hp   == null ? BR_HORSE_HP : hp);
        var _name = (name == null ? BR_HORSE_NAME : name);
        var _tag  = (tag  == null ? 'bone_rider_horse' : tag);
        var ET = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = ET.SKELETON_HORSE; } catch (e) { t = null; }
        if (t == null) { try { t = ET.valueOf('SKELETON_HORSE'); } catch (e) { } }
        if (t == null) return null;

        var h = world.spawnEntity(loc, t);
        if (h == null) return null;

        try { h.setTamed(true); } catch (e) { try { h.setTame(true); } catch (e2) { } }

        // ★ 马鞍（见 brSaddle）：四条路全试 + 回读验证
        try { brSaddle(h); } catch (e) { }
        // ★ 鞍**绝不掉落**：死亡事件里会额外清一次 inventory（见 brOnEntityDeath）

        brSetBase(h, ['MAX_HEALTH', 'GENERIC_MAX_HEALTH'], _hp);
        try { h.setHealth(_hp); } catch (e) { }
        try {
            // 移速：只升不降
            var Attr = Java.type('org.bukkit.attribute.Attribute');
            var k = null;
            try { k = Attr.MOVEMENT_SPEED; } catch (e) { k = null; }
            if (k == null) { try { k = Attr.valueOf('MOVEMENT_SPEED'); } catch (e) { k = null; } }
            if (k == null) { try { k = Attr.GENERIC_MOVEMENT_SPEED; } catch (e) { k = null; } }
            if (k == null) { try { k = Attr.valueOf('GENERIC_MOVEMENT_SPEED'); } catch (e) { k = null; } }
            if (k != null) {
                var inst = h.getAttribute(k);
                if (inst != null) {
                    var cur = 0.0;
                    try { cur = inst.getBaseValue(); } catch (e) { cur = 0.0; }
                    if (cur < BR_HORSE_SPEED) inst.setBaseValue(BR_HORSE_SPEED);
                }
            }
        } catch (e) { }

        try { h.setRemoveWhenFarAway(false); } catch (e) { }
        try { h.setPersistent(true); } catch (e) { }
        try { h.setCustomName(_name); } catch (e) { }
        try { h.setCustomNameVisible(true); } catch (e) { }   // ★ 璇骊 / 残驹 的名字都看得见
        // 死了不掉东西
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) h.setLootTable(LootTables.EMPTY);
        } catch (e) { }
        try {
            var eq = h.getEquipment();
            if (eq != null) { try { eq.clear(); } catch (e) { } }
        } catch (e) { }
        try {
            if (BR_BOSS_KEY != null && BR_PDT != null) {
                h.getPersistentDataContainer().set(BR_BOSS_KEY, BR_PDT.STRING, _tag);
            }
        } catch (e) { }

        return h;
    } catch (e) { return null; }
}

// ---------------- 生成：boss（骷髅） ----------------
function brSpawnBoss(world, loc) {
    try {
        var ET = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = ET.SKELETON; } catch (e) { t = null; }
        if (t == null) { try { t = ET.valueOf('SKELETON'); } catch (e) { } }
        if (t == null) return null;

        var s = world.spawnEntity(loc, t);
        if (s == null) return null;

        // ★ 装备：头戴**下界合金头盔** + 下界合金全套 + 主手金剑（全部永不掉耐久）
        //   第二十批：头部由「侦测器」改为**下界合金头盔**
        //   （brGear 内部已 `setUnbreakable(true)` ⇒ 不掉耐久）
        try {
            var eq = s.getEquipment();
            if (eq != null) {
                var helmet = brGear(['NETHERITE_HELMET']);
                var chest  = brGear(['NETHERITE_CHESTPLATE']);
                var legs   = brGear(['NETHERITE_LEGGINGS']);
                var boots  = brGear(['NETHERITE_BOOTS']);
                var sword  = brGear(['GOLDEN_SWORD', 'GOLD_SWORD']);

                if (helmet != null) eq.setHelmet(helmet);
                if (chest  != null) eq.setChestplate(chest);
                if (legs   != null) eq.setLeggings(legs);
                if (boots  != null) eq.setBoots(boots);
                eq.setItemInMainHand(sword);       // ★ 换掉弓（没弓 = 不再射箭）
                eq.setItemInOffHand(null);

                // 死亡一件都不掉（下界合金别白送）
                try { eq.setHelmetDropChance(0.0); } catch (e) { }
                try { eq.setChestplateDropChance(0.0); } catch (e) { }
                try { eq.setLeggingsDropChance(0.0); } catch (e) { }
                try { eq.setBootsDropChance(0.0); } catch (e) { }
                try { eq.setItemInMainHandDropChance(0.0); } catch (e) { }
                try { eq.setItemInOffHandDropChance(0.0); } catch (e) { }
            }
        } catch (e) { }

        // 血量 500（BR_BOSS_HP）
        brSetBase(s, ['MAX_HEALTH', 'GENERIC_MAX_HEALTH'], BR_BOSS_HP);
        try { s.setHealth(BR_BOSS_HP); } catch (e) { }

        // ★ 基础伤害 2 点：扣掉金剑自带的 +4，最终值才正好 = 2
        brSetBase(s, ['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE'], BR_BASE_DMG - BR_WEAPON_BONUS);

        // 白天别自燃（骷髅会烧起来）
        try {
            var PET  = Java.type('org.bukkit.potion.PotionEffectType');
            var PE   = Java.type('org.bukkit.potion.PotionEffect');
            var pet  = null;
            try { pet = PET.FIRE_RESISTANCE; } catch (e) { pet = null; }
            if (pet == null) { try { pet = PET.valueOf('FIRE_RESISTANCE'); } catch (e) { pet = null; } }
            if (pet != null) {
                try { s.addPotionEffect(new PE(pet, 999999, 0, true, false)); }
                catch (e1) { try { s.addPotionEffect(new PE(pet, 999999, 0)); } catch (e2) { } }
            }
        } catch (e) { }

        try { s.setCanPickupItems(false); } catch (e) { }
        try { s.setRemoveWhenFarAway(false); } catch (e) { }
        try { s.setPersistent(true); } catch (e) { }
        try { s.setCustomName(BR_BOSS_NAME); } catch (e) { }
        try { s.setCustomNameVisible(true); } catch (e) { }
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) s.setLootTable(LootTables.EMPTY);
        } catch (e) { }
        try {
            if (BR_BOSS_KEY != null && BR_PDT != null) {
                s.getPersistentDataContainer().set(BR_BOSS_KEY, BR_PDT.STRING, 'bone_rider');
            }
        } catch (e) { }

        return s;
    } catch (e) { return null; }
}

// ==================================================================
// ★ boss 血条（BossBar）—— 枯骸将军 / 璇骊 各一条，类似凋零与末影龙
// ==================================================================
// 进度 = 当前血量 / 最大血量，每拍（5 刻）刷新一次。
// ★ 观众默认**只有召唤者本人**（BR_BAR_ONLY_OWNER）：别人走到跟前也不会看到血条；
//   召唤者走远（BR_BAR_RANGE 格）/ 下线 / boss 没了 ⇒ 自动 removePlayer 收掉。
// ★ 标题**不带颜色码**：1.20.5+ 的 createBossBar(String) 走的是 Adventure，
//   塞 § 可能原样显示成乱码 ⇒ 标题纯文字，颜色交给 BarColor。
function brBarMake(title, colorName, styleName) {
    if (!BR_BAR) return null;
    try {
        var BarColor = Java.type('org.bukkit.boss.BarColor');
        var BarStyle = Java.type('org.bukkit.boss.BarStyle');
        var c = null, s = null;
        try { c = BarColor[colorName]; } catch (e) { c = null; }
        if (c == null) { try { c = BarColor.valueOf(colorName); } catch (e2) { c = null; } }
        try { s = BarStyle[styleName]; } catch (e) { s = null; }
        if (s == null) { try { s = BarStyle.valueOf(styleName); } catch (e2) { s = null; } }
        if (c == null || s == null) return null;

        var bar = null;
        try { bar = getServer().createBossBar(title, c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setVisible(true); } catch (e) { }
        try { bar.setProgress(1.0); } catch (e) { }
        return bar;
    } catch (e) { return null; }
}

// 同步一条血条：进度 + 观看者（nearMap 记着"已经加进来的人"，好在他们走远后 removePlayer）
//   ownerUuid：**只有这个玩家**看得到（BR_BAR_ONLY_OWNER = true 时传 boss 的召唤者 uuid）
//   —— 传了就看"是不是本人"，不传就按"BR_BAR_RANGE 格内的所有人"来
function brBarSync(bar, ent, maxHp, nearMap, ownerUuid) {
    try {
        if (bar == null) return;
        var alive = brAlive(ent);

        // ① 进度
        var prog = 0.0;
        if (alive) {
            try {
                var hp = ent.getHealth();
                var mx = maxHp;
                try {
                    var Attr = Java.type('org.bukkit.attribute.Attribute');
                    var k = null;
                    try { k = Attr.MAX_HEALTH; } catch (e) { k = null; }
                    if (k == null) { try { k = Attr.valueOf('MAX_HEALTH'); } catch (e2) { k = null; } }
                    if (k == null) { try { k = Attr.GENERIC_MAX_HEALTH; } catch (e) { k = null; } }
                    if (k == null) { try { k = Attr.valueOf('GENERIC_MAX_HEALTH'); } catch (e2) { k = null; } }
                    if (k != null) {
                        var inst = ent.getAttribute(k);
                        if (inst != null) { var v = inst.getValue(); if (v > 0) mx = v; }
                    }
                } catch (e) { }
                prog = hp / (mx > 0 ? mx : 1);
            } catch (e) { prog = 0.0; }
        }
        if (prog < 0) prog = 0; if (prog > 1) prog = 1;
        try { bar.setProgress(prog); } catch (e) { }
        try { bar.setVisible(alive); } catch (e) { }

        // ② 观看者：范围内加进来，走出去 / 下线就移除
        var want = {};
        if (alive) {
            try {
                var only = (BR_BAR_ONLY_OWNER && ownerUuid != null && '' + ownerUuid !== '');
                var wname = '' + ent.getWorld().getName();
                var el = ent.getLocation();
                var players = getServer().getOnlinePlayers();
                var it = players.iterator();
                while (it.hasNext()) {
                    var p = it.next();
                    if (p == null) continue;
                    // ★ 只给召唤者看：不是本人直接跳过（下线了自然也不在在线列表里 ⇒ 血条自动收掉）
                    if (only) {
                        try { if ('' + p.getUniqueId() !== '' + ownerUuid) continue; } catch (e) { continue; }
                    }
                    try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                    try {
                        var gm = '' + p.getGameMode().name();
                        if (gm.indexOf('SPECTATOR') >= 0) continue;
                    } catch (e) { }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    if (pl.distance(el) > BR_BAR_RANGE) continue;
                    try { want['' + p.getUniqueId()] = p; } catch (e) { }
                }
            } catch (e) { }
        }

        var k2;
        for (k2 in want) {
            if (nearMap[k2] == null) {
                try { bar.addPlayer(want[k2]); } catch (e) { }
                nearMap[k2] = want[k2];
            }
        }
        for (k2 in nearMap) {
            if (want[k2] == null) {
                try { bar.removePlayer(nearMap[k2]); } catch (e) { }
                try { delete nearMap[k2]; } catch (e) { }
            }
        }
    } catch (e) { }
}

// 收摊：整条血条撤掉（boss 没了 / reload）
function brBarDrop(bar, nearMap) {
    try {
        if (bar == null) return;
        try { bar.setVisible(false); } catch (e) { }
        try { bar.removeAll(); } catch (e) { }
        if (nearMap != null) { for (var k in nearMap) { try { delete nearMap[k]; } catch (e) { } } }
    } catch (e) { }
}

// ==================================================================
// ★ 出场 BGM（2026-10-06）：boss 周围**循环播放**唱片《Pigstep》（猪灵堡垒遗迹那张）
// ==================================================================
//   · 曲目：**Lena Raine - Pigstep**（**猪灵堡垒遗迹**产出，`music_disc_pigstep`，时长 **2:28**）
//     音效名 `music_disc.pigstep`；万一这台服务器没有会自动退到 `BR_MUSIC_FALLBACK`
//   · **范围**：只有 boss `BR_MUSIC_RANGE` 格内的玩家听得到（走远立刻停，走近从头播）
//   · **循环**：每 `BR_MUSIC_LOOP_MS`（148 秒 = 曲目长度）重放一次 ⇒ 无缝循环
//   · 音量 4.0 = 原版唱片机音量（衰减距离 16 × 4 ≈ 64 格听得见）
//   · boss 归天 → 给所有还在听的人 `stopSound`，音乐立刻停
// ------------------------------------------------------------------
var BR_MUSIC_PICKED = null;

// 挑一个**这台服务器真的有**的唱片音效（1.16+ 有 pigstep，万一没有就往下退 …）
function brMusicSound() {
    try {
        if (BR_MUSIC_PICKED != null) return BR_MUSIC_PICKED;
        var cands = [BR_MUSIC_SOUND, BR_MUSIC_FALLBACK, 'music_disc.relic', 'music_disc.11'];
        try {
            var Sound = Java.type('org.bukkit.Sound');
            for (var i = 0; i < cands.length; i++) {
                var nm = 'MUSIC_DISC_' + ('' + cands[i]).replace('music_disc.', '').toUpperCase();
                try {
                    if (Sound[nm] != null) { BR_MUSIC_PICKED = cands[i]; return BR_MUSIC_PICKED; }
                } catch (e) { }
            }
        } catch (e) { }
        BR_MUSIC_PICKED = BR_MUSIC_SOUND;
        return BR_MUSIC_PICKED;
    } catch (e) { return BR_MUSIC_SOUND; }
}

function brMusicEnum() {
    try {
        var Sound = Java.type('org.bukkit.Sound');
        var nm = 'MUSIC_DISC_' + ('' + brMusicSound()).replace('music_disc.', '').toUpperCase();
        var v = Sound[nm];
        return (v == null ? null : v);
    } catch (e) { return null; }
}

function brMusicPlayTo(p, loc) {
    try {
        if (p == null || loc == null) return false;
        var name = brMusicSound();
        try { p.playSound(loc, name, BR_MUSIC_VOL, 1.0); return true; } catch (e) { }
        var en = brMusicEnum();
        if (en != null) { try { p.playSound(loc, en, BR_MUSIC_VOL, 1.0); return true; } catch (e2) { } }
        try { loc.getWorld().playSound(p, name, BR_MUSIC_VOL, 1.0); return true; } catch (e3) { }
        return false;
    } catch (e) { return false; }
}

function brMusicStopTo(p) {
    try {
        if (p == null) return;
        var name = brMusicSound();
        try { p.stopSound(name); return; } catch (e) { }
        var en = brMusicEnum();
        if (en != null) { try { p.stopSound(en); return; } catch (e2) { } }
        // 最后兜底：停掉整个"唱片"分类
        try {
            var SC = Java.type('org.bukkit.SoundCategory');
            var c = null;
            try { c = SC.RECORDS; } catch (e3) { c = null; }
            if (c == null) { try { c = SC.valueOf('RECORDS'); } catch (e4) { c = null; } }
            if (c != null) { try { p.stopSound(c); } catch (e5) { } }
        } catch (e) { }
    } catch (e) { }
}

// 每秒检查一次谁在范围内，**到点就重放** ⇒ 循环播放
function brMusicTick(ref) {
    try {
        if (!BR_MUSIC) return;
        if (ref == null || !brAlive(ref.boss)) { brMusicStopAll(ref); return; }
        if (ref.music == null) ref.music = {};

        var now = brNow();
        var bl = null, wname = '';
        try { bl = ref.boss.getLocation(); wname = '' + bl.getWorld().getName(); } catch (e) { }

        var want = {};
        if (bl != null) {
            var it = getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                var p = it.next();
                if (p == null) continue;
                try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                var pl = null;
                try { pl = p.getLocation(); } catch (e) { continue; }
                if (pl.distance(bl) > BR_MUSIC_RANGE) continue;
                try { want['' + p.getUniqueId()] = p; } catch (e) { }
            }
        }

        // ① 刚走进范围 ⇒ 从头播；② 已经听了 148 秒（= 曲目长度）⇒ 重放（循环）
        for (var k in want) {
            var rec = ref.music[k];
            if (rec == null || (now - rec.at) >= BR_MUSIC_LOOP_MS) {
                var okp = false;
                try { okp = brMusicPlayTo(want[k], bl); } catch (e) { okp = false; }
                ref.music[k] = { p: want[k], at: now };
                if (!okp && rec == null) {
                    // 这台服务器没有这个音效 ⇒ 记一笔，别每秒重试刷后台
                    try { getServer().getLogger().info('[构思技艺] 枯骸骑将：本服没有音效 ' + brMusicSound() + '，BGM 不播放'); } catch (e) { }
                }
            }
        }
        // ③ 走出范围 / 下线 ⇒ 立刻停
        for (var k2 in ref.music) {
            if (want[k2] == null) {
                try { brMusicStopTo(ref.music[k2].p); } catch (e) { }
                try { delete ref.music[k2]; } catch (e) { }
            }
        }

        runLater(function () { brMusicTick(ref); }, 20);
    } catch (e) { }
}

// boss 归天 / 收摊：把所有还在听的人的音乐停掉
function brMusicStopAll(ref) {
    try {
        if (ref == null || ref.music == null) return;
        for (var k in ref.music) {
            try { brMusicStopTo(ref.music[k].p); } catch (e) { }
            try { delete ref.music[k]; } catch (e) { }
        }
    } catch (e) { }
}

// ==================================================================
// ★ 伺骨（冷却 30 秒 · 3 只金套骷髅 · 各骑一匹「残驹」）
// ==================================================================
//   · 全身**金套 + 金剑**（全部 setUnbreakable ⇒ 不掉耐久，死亡也不掉装备）
//   · 血量 50；伤害 **5 点**（含金剑自带的 4 点 ⇒ 属性 base 写成 5-4=1）
//   · 残驹血量 50；**共生**：残驹死 → 伺骨同死，伺骨死 → 残驹同死
//   · ★ **全员装马鞍**（2026-10-06），且**死亡一个东西都不掉**（含马鞍：
//     死亡事件里 `getDrops().clear()` + 清空 HorseInventory / Equipment）
//   · **无掉落**：空战利品表 + 死亡事件清 drops + 死亡前清空装备
//   · **只存在 30 秒**（BR_SV_LIFE），到点化烟消失 —— 防 boss 一直召唤把服卡死
// ------------------------------------------------------------------
// ★ 为什么残驹要"脚本自己搬"：
//   骑在上面的骷髅（AI 生物）**不会操控**骷髅马（原版只有玩家能控马），
//   而骷髅马自己又有 AI（会到处溜、会靠近玩家）⇒ 只推速度压不住它。
//   ⇒ 改成**每刻 teleport 到脚本算出来的位置**，位移 100% 由脚本决定，
//     马的 AI 想往哪走都会被立刻盖掉（不会再出现"马和骑手各追一边互相拖拽"）。
// ★ 方向只认**伺骨的目标**（残驹自己不索敌）：`sk.getTarget()` 有效就用它，
//   失效才按"离**伺骨**最近的玩家"挑一个并交给它。
function brSvSpawnSkeleton(world, loc) {
    try {
        var ET = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = ET.SKELETON; } catch (e) { t = null; }
        if (t == null) { try { t = ET.valueOf('SKELETON'); } catch (e) { } }
        if (t == null) return null;

        var s = world.spawnEntity(loc, t);
        if (s == null) return null;

        // 全身金套 + 金剑（永不掉耐久）
        try {
            var eq = s.getEquipment();
            if (eq != null) {
                var h = brGear(['GOLDEN_HELMET', 'GOLD_HELMET']);
                var c = brGear(['GOLDEN_CHESTPLATE', 'GOLD_CHESTPLATE']);
                var l = brGear(['GOLDEN_LEGGINGS', 'GOLD_LEGGINGS']);
                var b = brGear(['GOLDEN_BOOTS', 'GOLD_BOOTS']);
                var w = brGear(['GOLDEN_SWORD', 'GOLD_SWORD']);
                if (h != null) eq.setHelmet(h);
                if (c != null) eq.setChestplate(c);
                if (l != null) eq.setLeggings(l);
                if (b != null) eq.setBoots(b);
                eq.setItemInMainHand(w);          // ★ 换掉弓 ⇒ 不会射箭，只会近战
                eq.setItemInOffHand(null);
                try { eq.setHelmetDropChance(0.0); } catch (e) { }
                try { eq.setChestplateDropChance(0.0); } catch (e) { }
                try { eq.setLeggingsDropChance(0.0); } catch (e) { }
                try { eq.setBootsDropChance(0.0); } catch (e) { }
                try { eq.setItemInMainHandDropChance(0.0); } catch (e) { }
                try { eq.setItemInOffHandDropChance(0.0); } catch (e) { }
            }
        } catch (e) { }

        brSetBase(s, ['MAX_HEALTH', 'GENERIC_MAX_HEALTH'], BR_SV_HP);
        try { s.setHealth(BR_SV_HP); } catch (e) { }
        // 伤害 5 点（含金剑的 +4）
        brSetBase(s, ['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE'], BR_SV_DMG - BR_WEAPON_BONUS);

        // 白天别自燃
        try {
            var PET = Java.type('org.bukkit.potion.PotionEffectType');
            var PE  = Java.type('org.bukkit.potion.PotionEffect');
            var pet = null;
            try { pet = PET.FIRE_RESISTANCE; } catch (e) { pet = null; }
            if (pet == null) { try { pet = PET.valueOf('FIRE_RESISTANCE'); } catch (e) { pet = null; } }
            if (pet != null) {
                try { s.addPotionEffect(new PE(pet, 999999, 0, true, false)); }
                catch (e1) { try { s.addPotionEffect(new PE(pet, 999999, 0)); } catch (e2) { } }
            }
        } catch (e) { }

        try { s.setCanPickupItems(false); } catch (e) { }
        try { s.setRemoveWhenFarAway(false); } catch (e) { }
        try { s.setPersistent(true); } catch (e) { }
        try { s.setCustomName(BR_SV_NAME); } catch (e) { }
        try { s.setCustomNameVisible(true); } catch (e) { }
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) s.setLootTable(LootTables.EMPTY);
        } catch (e) { }
        try {
            if (BR_BOSS_KEY != null && BR_PDT != null) {
                s.getPersistentDataContainer().set(BR_BOSS_KEY, BR_PDT.STRING, 'bone_rider_servant');
            }
        } catch (e) { }
        return s;
    } catch (e) { return null; }
}

// 把某个实体"干净地弄死"：清装备（⇒ 不掉金套）→ 卸乘客 → 扣血走死亡流程
function brSvKill(ent) {
    try {
        if (ent == null) return;
        if (!brAlive(ent)) return;
        try {
            var eq = ent.getEquipment();
            if (eq != null) { try { eq.clear(); } catch (e) { } }
        } catch (e) { }
        // ★ 马鞍（在 HorseInventory 里）也一起清掉 ⇒ 死也不掉鞍
        try {
            var hi = ent.getInventory();
            if (hi != null) { try { hi.clear(); } catch (e) { } }
        } catch (e) { }
        try { ent.eject(); } catch (e) { }
        try { ent.setHealth(0.0); } catch (e) { try { ent.remove(); } catch (e2) { } }
    } catch (e) { }
}

// 化烟消失（30 秒到点 / boss 归天时走这条：直接 remove，不走死亡 ⇒ 更不会产生掉落）
function brSvPuff(ent) {
    try {
        if (ent == null) return;
        var world = null, l = null;
        try { l = ent.getLocation(); world = l.getWorld(); } catch (e) { }
        try {
            var eq = ent.getEquipment();
            if (eq != null) { try { eq.clear(); } catch (e) { } }
        } catch (e) { }
        // ★ 马鞍也清掉（化烟不走死亡流程，本来就掉不出来，这里只是干净）
        try {
            var hi = ent.getInventory();
            if (hi != null) { try { hi.clear(); } catch (e) { } }
        } catch (e) { }
        try { ent.eject(); } catch (e) { }
        try { ent.remove(); } catch (e) { }
        if (world != null && l != null) {
            try {
                var P = Java.type('org.bukkit.Particle');
                var pt = null;
                try { pt = P.SMOKE_LARGE; } catch (e) { pt = null; }
                if (pt == null) { try { pt = P.valueOf('SMOKE_LARGE'); } catch (e) { pt = null; } }
                if (pt == null) { try { pt = P.CLOUD; } catch (e) { pt = null; } }
                if (pt != null) {
                    for (var i = 0; i < 12; i++) {
                        world.spawnParticle(pt, l.getX(), l.getY() + 0.8 + i * 0.08, l.getZ(),
                            2, 0.3, 0.3, 0.3, 0.03);
                    }
                }
            } catch (e) { }
        }
    } catch (e) { }
}

// 收尾：把 sv 从索引里摘掉，并把**另一半**（残驹 / 伺骨）一起带走
//   mode: 'puff' = 化烟（超时 / boss 归天）  'kill' = 走死亡流程（共生陪葬）
function brSvDispose(sv, mode) {
    try {
        if (sv == null || sv.done) return;
        sv.done = true;

        var sk = sv.sk, hs = sv.horse;

        if (mode === 'kill') {
            // 共生：一个死了，**还活着的那个**立刻跟着死
            if (brAlive(sk)) brSvKill(sk);
            if (brAlive(hs)) brSvKill(hs);
        } else {
            brSvPuff(sk);
            brSvPuff(hs);
        }

        try { if (sk != null) delete BR_SERVANTS['' + sk.getUniqueId()]; } catch (e) { }
        try { if (hs != null) delete BR_SV_H2S['' + hs.getUniqueId()]; } catch (e) { }
        try { if (sk != null) delete BR_SV_S2H['' + sk.getUniqueId()]; } catch (e) { }
        sv.sk = null; sv.horse = null;
    } catch (e) { }
}

// ★ 这个玩家不该被伺骨盯上？（创造 / 旁观 —— 2026-10-06：创造模式也必须跳过）
function brSvBadGM(p) {
    try {
        if (p == null) return true;
        var gm = '';
        try { gm = '' + p.getGameMode().name(); } catch (e) { return false; }
        if (gm.length === 0) return false;                                   // 不是玩家 ⇒ getGameMode 会抛 ⇒ 走不到这
        if (BR_SV_IGNORE_CREATIVE  && gm.indexOf('CREATIVE')  >= 0) return true;
        if (BR_SV_IGNORE_SPECTATOR && gm.indexOf('SPECTATOR') >= 0) return true;
        return false;
    } catch (e) { return false; }
}

// ★ 这个目标还能不能追？（活着 / 同世界 / 不是自己人 / **不是创造·旁观**）
function brSvGoodTarget(tgt, wname) {
    try {
        if (tgt == null) return false;
        if (!brAlive(tgt)) return false;
        try { if ('' + tgt.getWorld().getName() !== wname) return false; } catch (e) { return false; }
        if (BR_ALLY) { try { if (brIsAlly(tgt)) return false; } catch (e) { } }
        if (brSvBadGM(tgt)) return false;      // ★ 创造 / 旁观 ⇒ 立刻放弃（中途切创造也马上松手）
        return true;
    } catch (e) { return false; }
}

// ★ 以**伺骨**为圆心挑最近的玩家（**不是**以残驹为圆心 —— 那才是互相拖拽的根源）
function brSvPickTarget(sk, wname) {
    var best = null, bestD = 1e9;
    try {
        var sl = sk.getLocation();
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
            if (brSvBadGM(p)) continue;                 // ★ 创造 / 旁观一律跳过
            if (BR_ALLY) { try { if (brIsAlly(p)) continue; } catch (e) { } }
            var pl = null;
            try { pl = p.getLocation(); } catch (e) { continue; }
            var d = pl.distance(sl);
            if (d < bestD) { bestD = d; best = p; }
        }
        if (best != null && bestD > BR_SV_SEEK) best = null;
    } catch (e) { best = null; }
    return best;
}

// 伺骨的心跳：共生检查 + 30 秒寿命 + 给残驹推速度
function brSvTick(sv) {
    try {
        if (sv == null || sv.done) return;

        var sk = sv.sk, hs = sv.horse;
        if (!brAlive(sk)) { brSvDispose(sv, 'kill'); return; }   // 伺骨没了 → 残驹陪葬
        if (!brAlive(hs)) { brSvDispose(sv, 'kill'); return; }   // 残驹没了 → 伺骨陪葬

        sv.life = (sv.life == null ? 0 : sv.life) + 1;
        if (sv.life >= BR_SV_LIFE) { brSvDispose(sv, 'puff'); return; }   // ★ 30 秒到点

        // ★★★ 残驹**彻底不索敌**（2026-10-06 第二轮）
        //   光"清目标 + 推速度"压不住马的 AI（它会自己往玩家那边溜），
        //   所以改成**每刻直接把残驹 teleport 到该在的位置** —— 位移 100% 由脚本说了算，
        //   马脑子里的任何想法都会在这一刻被盖掉。
        try {
            var world = hs.getWorld();
            var wname = '' + world.getName();

            // ① 马自己不挑敌人：每刻把它的目标清掉
            if (BR_SV_HORSE_NO_TARGET) {
                try { hs.setTarget(null); } catch (e) { }
            }

            // ② 目标：每 BR_TICK 刻重挑一次并缓存（不用每刻遍历在线玩家，省性能）
            sv.tickN = (sv.tickN == null ? 0 : sv.tickN) + 1;
            if ((sv.tickN % BR_TICK) === 1 || !brSvGoodTarget(sv.tgt, wname)) {
                var t = null;
                try { t = sk.getTarget(); } catch (e) { t = null; }
                if (!brSvGoodTarget(t, wname)) {
                    t = brSvPickTarget(sk, wname);
                    if (t != null) { try { sk.setTarget(t); } catch (e) { } }
                }
                sv.tgt = t;
            }

            // ③ 每刻搬运残驹（方向 = 伺骨的目标 ⇒ 马和骑手永远同向，绝不再互相拖拽）
            var tgt = sv.tgt;
            if (tgt != null) {
                var hl = hs.getLocation();
                var tl = tgt.getLocation();
                var dx = tl.getX() - hl.getX();
                var dz = tl.getZ() - hl.getZ();
                var n = Math.sqrt(dx * dx + dz * dz);
                if (n > BR_SV_STOP_R && n <= BR_SV_SEEK) {
                    dx /= n; dz /= n;
                    var step = BR_SV_STEP;
                    if (step > n - BR_SV_STOP_R) step = n - BR_SV_STOP_R;
                    var nx = hl.getX() + dx * step;
                    var nz = hl.getZ() + dz * step;
                    var ny = hl.getY();

                    // 爬坡 / 撞墙（1 格当台阶，≥2 格就原地不动）
                    var sy = brDashStepY(world, nx, ny, nz);
                    if (sy == null) { nx = hl.getX(); nz = hl.getZ(); }
                    else { ny = sy; }

                    var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
                    var Loc = Java.type('org.bukkit.Location');
                    try { hs.teleport(new Loc(world, nx, ny, nz, yaw, hl.getPitch())); } catch (e) { }

                    // ④ 兜底：万一 teleport 没把骑手带走（或它掉下来了），这里补一刀 + 重新扶上马
                    if (brAlive(sk)) {
                        var riding = false;
                        try {
                            var v = sk.getVehicle();
                            riding = (v != null && ('' + v.getUniqueId()) === ('' + hs.getUniqueId()));
                        } catch (e) { riding = false; }
                        if (!riding) {
                            try { sk.teleport(new Loc(world, nx, ny + 1.35, nz, yaw, 0.0)); } catch (e) { }
                            try { hs.addPassenger(sk); } catch (e) { }
                        }
                    }
                }
            }
        } catch (e) { }

        // ⑤ ★ 马鞍复查补装（每 20 刻一次 —— 万一被插件/死亡清空了，这里补回来）
        try {
            if (sv.life % 20 === 3) brSaddle(hs);
        } catch (e) { }

        runLater(function () { brSvTick(sv); }, 1);
    } catch (e) {
        try { brSvDispose(sv, 'puff'); } catch (e2) { }
    }
}

// ---------------- 召唤一组伺骨 ----------------
function brSummonServants(ref) {
    try {
        if (ref == null || !brAlive(ref.boss)) return 0;

        // 已满员 → 不召，也**不重置 CD**（下一拍再试）
        var aliveN = 0;
        try {
            for (var i = 0; i < ref.servants.length; i++) {
                var s0 = ref.servants[i];
                if (s0 != null && !s0.done && brAlive(s0.sk)) aliveN++;
            }
        } catch (e) { aliveN = 0; }
        if (aliveN >= BR_SV_MAX_ALIVE) return 0;

        var world = ref.boss.getWorld();
        var base = ref.boss.getLocation();
        var yaw0 = 0;
        try { yaw0 = base.getYaw(); } catch (e) { yaw0 = 0; }

        var Loc = Java.type('org.bukkit.Location');
        var n = 0;
        for (var k = 0; k < BR_SV_COUNT; k++) {
            var ang = (yaw0 + (k - (BR_SV_COUNT - 1) / 2.0) * 45.0) * Math.PI / 180.0;
            var px = base.getX() + (-Math.sin(ang)) * 3.0;
            var pz = base.getZ() + (Math.cos(ang)) * 3.0;
            var loc = new Loc(world, px, base.getY(), pz);
            try { loc.setYaw((ang * 180.0 / Math.PI + 180.0) % 360.0); loc.setPitch(0.0); } catch (e) { }

            var hs = brSpawnHorse(world, loc, BR_SV_HORSE_HP, BR_SV_HORSE_NAME, 'bone_rider_servant_horse');
            var sk = brSvSpawnSkeleton(world, loc);
            if (sk == null) {
                if (hs != null) { try { hs.remove(); } catch (e) { } }
                continue;
            }
            if (hs != null) {
                try { hs.addPassenger(sk); } catch (e) { }
                // ★ 残驹自己**不索敌**：出生就把目标清掉，往后只听伺骨的
                try { if (BR_SV_HORSE_NO_TARGET) hs.setTarget(null); } catch (e) { }
            }

            var sv = { sk: sk, horse: hs, bossRef: ref, life: 0, done: false };
            try {
                BR_SERVANTS['' + sk.getUniqueId()] = sv;
                if (hs != null) {
                    BR_SV_H2S['' + hs.getUniqueId()] = '' + sk.getUniqueId();
                    BR_SV_S2H['' + sk.getUniqueId()] = '' + hs.getUniqueId();
                }
            } catch (e) { }
            try { ref.servants.push(sv); } catch (e) { }
            n++;

            runLater(function (x) { return function () { brSvTick(x); }; }(sv), BR_TICK);
        }

        if (n > 0) {
            try { world.playSound(base, 'entity.skeleton.hurt', 0.9, 0.7); } catch (e) { }
            try { world.playSound(base, 'entity.wither.skeleton.step', 0.7, 0.8); } catch (e) { }
        }
        return n;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 枯骸骑将 召唤伺骨出错: ' + e); } catch (e2) { }
        return 0;
    }
}

// ---------------- 目标选择 ----------------
function brTarget(ref) {
    try {
        var b = ref.boss;
        if (!brAlive(b)) return null;

        // ① 优先用怪物 AI 自己锁定的目标
        try {
            var t = b.getTarget();
            if (t != null && brAlive(t)) {
                if (brDist(b, t) <= BR_SEEK_RANGE) return t;
            }
        } catch (e) { }

        // ② 没有就找最近的玩家
        var best = null, bestD = 1e9;
        try {
            var players = getServer().getOnlinePlayers();
            var it = players.iterator();
            while (it.hasNext()) {
                var p = it.next();
                if (p == null) continue;
                try { if (p.getGameMode() != null && ('' + p.getGameMode().name()).indexOf('SPECTATOR') >= 0) continue; } catch (e) { }
                try { if ('' + p.getWorld().getName() !== '' + b.getWorld().getName()) continue; } catch (e) { continue; }
                var d = brDist(b, p);
                if (d < bestD) { bestD = d; best = p; }
            }
        } catch (e) { }

        return (best != null && bestD <= BR_SEEK_RANGE) ? best : null;
    } catch (e) { return null; }
}

// ---------------- 斩击：5 点 · 冷却 3.5 秒 · 无提示 ----------------
// ★ 不再"瞬间出现在玩家身上"：
//   ① 先 **举剑蓄势 BR_QI_WINDUP 刻**（0.3 秒，身前出收敛的剑气粒子 + 一声轻响）
//   ② 再把剑气**从 boss 身上一格一格射出去**（BR_QI_STEP 格/刻 ⇒ 10 格约 1.45 秒）
//   ③ 只有**波前扫到**的目标才吃伤害 ⇒ 玩家看得见、来得及跑开或绕开
//   用 ref.slashSeq 做"世代号"：新的一刀会让旧的波自动作废，不会两道波叠着打。
function brStartSlash(ref, tgt) {
    try {
        var b = ref.boss;

        var eye = null;
        try { eye = b.getEyeLocation(); } catch (e) { return; }
        var tl = null;
        try { tl = tgt.getLocation(); } catch (e) { return; }

        var dx = tl.getX() - eye.getX();
        var dy = (tl.getY() + 0.9) - eye.getY();
        var dz = tl.getZ() - eye.getZ();
        var n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 1.0e-4) return;
        dx /= n; dy /= n; dz /= n;

        // 水平方向（击退只用它，免得把人怼进地里）
        var hx = dx, hz = dz;
        var hl = Math.sqrt(hx * hx + hz * hz);
        if (hl < 1.0e-4) { hx = 0.0; hz = 0.0; } else { hx /= hl; hz /= hl; }

        // 起手：boss 转头对准目标（只改朝向，不 teleport —— 它在马背上，tp 会掉下来）
        try {
            var yaw = Math.atan2(-hx, hz) * 180.0 / Math.PI;
            b.setRotation(0 + yaw, 0.0);
        } catch (e) {
            try { b.setRotation(0 + Math.atan2(-hx, hz) * 180.0 / Math.PI, 0.0); } catch (e2) { }
        }

        ref.slashSeq = (ref.slashSeq == null ? 0 : ref.slashSeq) + 1;
        var wid = ref.slashSeq;

        var wave = {
            wx: eye.getX(), wy: eye.getY(), wz: eye.getZ(),
            dx: dx, dy: dy, dz: dz,
            hx: hx, hz: hz,
            hit: {}
        };

        brSlashWindup(ref, wave, wid, 0);
    } catch (e) { }
}

// ① 举剑蓄势（只出粒子，不伤人）
function brSlashWindup(ref, wave, wid, i) {
    try {
        if (ref == null || ref.slashSeq !== wid) return;
        if (!brAlive(ref.boss)) return;

        var b = ref.boss;
        var world = b.getWorld();

        var P = Java.type('org.bukkit.Particle');
        var pt = null;
        try { pt = P.SWEEP_ATTACK; } catch (e) { pt = null; }
        if (pt == null) { try { pt = P.valueOf('SWEEP_ATTACK'); } catch (e) { pt = null; } }

        var steps = BR_QI_WINDUP;
        if (steps < 1) steps = 1;
        var r = 1.30 - 0.85 * (i / steps);            // 圈越收越紧 = 蓄力感

        if (pt != null) {
            for (var k = 0; k < 5; k++) {
                var a = (2.0 * Math.PI * k) / 5.0 + i * 0.35;
                var ox = Math.cos(a) * r;
                var oz = Math.sin(a) * r;
                world.spawnParticle(pt,
                    wave.wx + wave.dx * 1.1 + ox,
                    wave.wy + 0.15 + Math.sin(a) * r * 0.5,
                    wave.wz + wave.dz * 1.1 + oz,
                    1, 0.0, 0.0, 0.0, 0.0);
            }
        }

        if (i === 0) {
            try { world.playSound(b.getLocation(), 'entity.ender_dragon.flap', 0.35, 1.9); } catch (e) { }
        }

        if (i + 1 >= steps) { brSlashWave(ref, wave, wid, 0); return; }
        runLater(function () { brSlashWindup(ref, wave, wid, i + 1); }, 1);
    } catch (e) { }
}

// ② 剑气飞行：每刻推进 BR_QI_STEP 格，只有波前扫到的才判定
function brSlashWave(ref, wave, wid, i) {
    try {
        if (ref == null || ref.slashSeq !== wid) return;
        if (!brAlive(ref.boss)) return;

        var b = ref.boss;
        var world = b.getWorld();

        var d = (i + 1) * BR_QI_STEP;
        if (d > BR_QI_RANGE) return;                     // 飞到头了

        // 波前：一道横着的弧
        try {
            var P = Java.type('org.bukkit.Particle');
            var pt = null;
            try { pt = P.SWEEP_ATTACK; } catch (e) { pt = null; }
            if (pt == null) { try { pt = P.valueOf('SWEEP_ATTACK'); } catch (e) { pt = null; } }
            if (pt != null) {
                var ux = -wave.hz, uz = wave.hx;         // 垂直于飞行方向的"横扫"轴
                for (var s = -4; s <= 4; s++) {
                    var o = (s / 4.0) * BR_QI_HALF_W;
                    world.spawnParticle(pt,
                        wave.wx + wave.dx * d + ux * o,
                        wave.wy + wave.dy * d + 0.2,
                        wave.wz + wave.dz * d + uz * o,
                        1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        } catch (e) { }

        // 只有"波前这一层"命中 ⇒ 站远一点 / 绕到侧面都能躲开
        try {
            var LocQ = Java.type('org.bukkit.Location');
            var list = world.getNearbyEntities(
                new LocQ(world, wave.wx, wave.wy, wave.wz),
                BR_QI_RANGE + 1.0, BR_QI_HALF_H + 2.0, BR_QI_RANGE + 1.0);
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (ent == null) continue;
                    if (!brAlive(ent)) continue;
                    if (ent === b) continue;
                    if (ref.horse != null && ent === ref.horse) continue;
                    if (brIsAlly(ent)) continue;              // ★ 自己人（璇骊 / 伺骨 / 残驹）不误伤

                    var el = null;
                    try { el = ent.getLocation(); } catch (e2) { continue; }
                    var rx = el.getX() - wave.wx;
                    var ry = (el.getY() + 0.9) - wave.wy;
                    var rz = el.getZ() - wave.wz;

                    var along = rx * wave.hx + rz * wave.hz;
                    var lat   = rx * wave.hz - rz * wave.hx;
                    if (Math.abs(lat) > BR_QI_HALF_W) continue;
                    if (Math.abs(ry) > BR_QI_HALF_H) continue;
                    if (along > d || along < d - BR_QI_BAND) continue;   // ★ 波前之外不判定

                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e3) { continue; }
                    if (wave.hit[eid] === 1) continue;
                    wave.hit[eid] = 1;

                    brHurt(b, ent, BR_QI_DMG,
                        wave.hx * BR_QI_KB, BR_QI_KB_UP, wave.hz * BR_QI_KB);
                }
            }
        } catch (e) { }

        runLater(function () { brSlashWave(ref, wave, wid, i + 1); }, 1);
    } catch (e) { }
}

// 造成伤害（可带击退）
function brHurt(src, ent, dmg, kbx, kby, kbz) {
    try {
        try { ent.setNoDamageTicks(0); } catch (e) { }
        try { ent.damage(dmg, src); }
        catch (e1) { try { ent.damage(dmg); } catch (e2) { } }

        if (kbx || kby || kbz) {
            try {
                var Vector = Java.type('org.bukkit.util.Vector');
                ent.setVelocity(new Vector(kbx, kby, kbz));
            } catch (e) { }
        }
    } catch (e) { }
}

// ==================================================================
// ★ 冲刺伤害「**无视防具**」（2026-10-06）
// ==================================================================
//   做法：命中前把受害者登记进 BR_PIERCE（记着"期望伤害"），
//   然后在 EntityDamageByEntityEvent（HIGHEST）里把**减免类修正器**全部归零：
//     ARMOR（盔甲） / MAGIC（保护附魔·抗性） / RESISTANCE / BLOCKING / HARD_HAT
//   原版的伤害是在事件**之后**才按修正器结算的 ⇒ 归零了就等于盔甲一点用都没有。
//   （吸收金心属于事件后结算的另外一层，脚本插不进去，会照常先扣 —— 这是原版机制。）
var BR_PIERCE    = {};      // 受害者 uuid -> { d: 期望伤害, t: 登记时间戳 }
var BR_PIERCE_MS = 800;     // 登记有效期（毫秒），超时就当普通伤害处理

function brHurtPierce(src, ent, dmg, kbx, kby, kbz) {
    try {
        if (BR_DASH_PIERCE) {
            try { BR_PIERCE['' + ent.getUniqueId()] = { d: dmg, t: brNow() }; } catch (e) { }
        }
    } catch (e) { }
    brHurt(src, ent, dmg, kbx, kby, kbz);
}

// 伤害事件里抹掉防具减伤（挂在 brOnSkullDamage 那个 EntityDamageByEntityEvent 上）
function brOnPierce(e) {
    try {
        if (!BR_DASH_PIERCE || e == null) return;
        var victim = null;
        try { victim = e.getEntity(); } catch (err) { return; }
        if (victim == null) return;

        var uid = '';
        try { uid = '' + victim.getUniqueId(); } catch (err) { return; }
        var rec = BR_PIERCE[uid];
        if (rec == null) return;
        try { delete BR_PIERCE[uid]; } catch (err) { }        // ★ 一次性：用完就删
        if ((brNow() - rec.t) > BR_PIERCE_MS) return;         // 过期 ⇒ 当普通伤害

        var DM = null;
        try { DM = Java.type('org.bukkit.event.entity.EntityDamageEvent$DamageModifier'); } catch (err) { DM = null; }
        if (DM == null) { try { e.setDamage(rec.d); } catch (err2) { } return; }

        var names = ['ARMOR', 'MAGIC', 'RESISTANCE', 'BLOCKING', 'HARD_HAT'];
        for (var i = 0; i < names.length; i++) {
            var m = null;
            try { m = DM[names[i]]; } catch (err) { m = null; }
            if (m == null) { try { m = DM.valueOf(names[i]); } catch (err) { m = null; } }
            if (m == null) continue;
            try { e.setDamage(m, 0.0); } catch (err) { }      // 减免量归零 ⇒ 不减伤
        }
        try { e.setDamage(rec.d); } catch (err) { }            // 最终伤害按回我们要的数
    } catch (e) { }
}

// ---------------- 狂暴：凋零之首（20 点 + 凋零 II 5 秒 · 冷却 4 秒 · 追踪 · 不炸地形） ----------------
// ★ 只有**战马比 boss 先死**才会解锁（ref.enraged = true）
// ★ 用的是**黑色**（未蓄力）凋零之首：`setCharged(false)` ⇒ 命中只炸一下实体，
//   **不会破坏方块**（蓝色蓄力头才会炸地形）。
// ★ 伤害与凋零由我们自己结算：伤害事件里把伤害**改写成 20 点**，再去覆盖凋零效果。
function brFireSkull(ref, tgt) {
    try {
        var b = ref.boss;
        var world = b.getWorld();

        var eye = null;
        try { eye = b.getEyeLocation(); } catch (e) { return; }
        var tl = null;
        try { tl = tgt.getLocation(); } catch (e) { return; }

        var dx = tl.getX() - eye.getX();
        var dy = (tl.getY() + 1.0) - eye.getY();
        var dz = tl.getZ() - eye.getZ();
        var n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 1.0e-4) return;
        dx /= n; dy /= n; dz /= n;

        var ET = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = ET.WITHER_SKULL; } catch (e) { t = null; }
        if (t == null) { try { t = ET.valueOf('WITHER_SKULL'); } catch (e) { } }
        if (t == null) return;

        // 从头顶前方一点放出去，免得刚出生就撞到自己脚下的方块
        var Loc = Java.type('org.bukkit.Location');
        var from = new Loc(world,
            eye.getX() + dx * 1.0,
            eye.getY() + 0.3,
            eye.getZ() + dz * 1.0);

        var sk = world.spawnEntity(from, t);
        if (sk == null) return;

        try { if (typeof sk.setCharged === 'function') sk.setCharged(false); } catch (e) { }
        try { if (typeof sk.setShooter === 'function') sk.setShooter(b); } catch (e) { }
        // 爆炸威力 / 是否点火：能设就设，但**别指望它**（原版凋零头的爆炸强度是硬编码的，
        //   实测 setYield 兜不住地形破坏）⇒ 真正的保险是 brRegisterExplode 里清空方块列表
        try { if (typeof sk.setYield === 'function') sk.setYield(0); } catch (e) { }
        try { if (typeof sk.setIsIncendiary === 'function') sk.setIsIncendiary(false); } catch (e) { }

        try {
            var Vector = Java.type('org.bukkit.util.Vector');
            var v = new Vector(dx * BR_SKULL_SPEED, dy * BR_SKULL_SPEED, dz * BR_SKULL_SPEED);
            try { sk.setDirection(new Vector(dx, dy, dz)); } catch (e) { }
            try { sk.setVelocity(v); } catch (e) { }
        } catch (e) { }

        try {
            if (BR_SKULL_KEY != null && BR_PDT != null) {
                sk.getPersistentDataContainer().set(BR_SKULL_KEY, BR_PDT.STRING, '1');
            }
        } catch (e) { }

        try { world.playSound(eye, 'entity.wither.shoot', 0.9, 1.0); } catch (e) { }

        // ★ 追踪制导：让它一路咬着目标飞（不追踪就把 BR_SKULL_HOME 改成 false）
        if (BR_SKULL_HOME) brSkullGuide(sk, tgt, 0);
    } catch (e) { }
}

// ---------------- 凋零之首·追踪制导 ----------------
// 每 BR_SKULL_HOME_TICK 刻把**飞行方向**朝"目标当前位置"偏转一点：
//   新方向 = 归一化( 旧方向 × (1 - TURN) + 指向目标 × TURN )
// ★ 为什么要"慢慢偏"而不是"直接指向"：直接指向 = 必中，玩家再怎么跑都躲不开，
//   变成纯粹的数值惩罚；留一点转向延迟 ⇒ 急转、绕柱子、进隧道都能甩掉。
// ★ 目标每拍重新找「离头颅最近的在线玩家」（原目标跑远了会自动换人），
//   找不到目标就沿原方向直飞（不再修正）。
// ★ BR_SKULL_LIFE 刻后自毁：`remove()` 不会触发爆炸 ⇒ 不会有残留、也不会炸地形。
function brSkullGuide(sk, tgt, step) {
    try {
        if (sk == null) return;
        try { if (sk.isDead()) return; } catch (e) { return; }
        try { if (!sk.isValid()) return; } catch (e) { }

        if (step >= BR_SKULL_LIFE) { try { sk.remove(); } catch (e) { } return; }

        var sl = null;
        try { sl = sk.getLocation(); } catch (e) { sl = null; }
        if (sl == null) return;

        // ① 目标：优先原来的那个（还活着/还在线）；不行就换最近的玩家
        var t = null;
        try {
            if (tgt != null && brAlive(tgt)) {
                try {
                    if (tgt.isOnline == null || tgt.isOnline()) t = tgt;
                } catch (e) { t = tgt; }
            }
        } catch (e) { t = null; }

        if (t == null) {
            var best = null, bestD = 1e9;
            try {
                var players = getServer().getOnlinePlayers();
                var it = players.iterator();
                while (it.hasNext()) {
                    var p = it.next();
                    if (p == null) continue;
                    try {
                        var gm = '' + p.getGameMode().name();
                        if (gm.indexOf('SPECTATOR') >= 0) continue;
                    } catch (e) { }
                    try { if ('' + p.getWorld().getName() !== '' + sl.getWorld().getName()) continue; } catch (e) { continue; }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    var ax = pl.getX() - sl.getX(), ay = pl.getY() + 1.0 - sl.getY(), az = pl.getZ() - sl.getZ();
                    var d = Math.sqrt(ax * ax + ay * ay + az * az);
                    if (d < bestD) { bestD = d; best = p; }
                }
            } catch (e) { }
            if (best != null && bestD <= BR_SKULL_HOME_RANGE) t = best;
        }

        if (t != null) {
            var tl = null;
            try { tl = t.getLocation(); } catch (e) { tl = null; }
            if (tl != null) {
                var wx = tl.getX() - sl.getX();
                var wy = (tl.getY() + 1.0) - sl.getY();
                var wz = tl.getZ() - sl.getZ();
                var wn = Math.sqrt(wx * wx + wy * wy + wz * wz);
                if (wn > 1.0e-4) {
                    wx /= wn; wy /= wn; wz /= wn;

                    // 旧方向（速度）：没速度就退化成"直接指向目标"
                    var ox = wx, oy = wy, oz = wz;
                    try {
                        var vv = sk.getVelocity();
                        var vn = Math.sqrt(vv.getX() * vv.getX() + vv.getY() * vv.getY() + vv.getZ() * vv.getZ());
                        if (vn > 1.0e-4) { ox = vv.getX() / vn; oy = vv.getY() / vn; oz = vv.getZ() / vn; }
                    } catch (e) { }

                    var k = BR_SKULL_TURN;
                    if (k < 0) k = 0; if (k > 1) k = 1;
                    var nx = ox * (1 - k) + wx * k;
                    var ny = oy * (1 - k) + wy * k;
                    var nz = oz * (1 - k) + wz * k;
                    var nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (nn > 1.0e-4) {
                        nx /= nn; ny /= nn; nz /= nn;
                        try {
                            var Vector = Java.type('org.bukkit.util.Vector');
                            sk.setDirection(new Vector(nx, ny, nz));
                            sk.setVelocity(new Vector(nx * BR_SKULL_SPEED, ny * BR_SKULL_SPEED, nz * BR_SKULL_SPEED));
                        } catch (e) { }
                    }
                }
            }
        }

        runLater(function () { brSkullGuide(sk, t, step + BR_SKULL_HOME_TICK); }, BR_SKULL_HOME_TICK);
    } catch (e) {
        try { if (sk != null) sk.remove(); } catch (e2) { }
    }
}

// ---------------- ★ 冲刺的爬坡 / 撞墙判定（2026-10-06）----------------
//   这一格是不是"过得去"的（空气 / 水 / 草 / 火把 ⇒ true；玻璃 / 墙 / 半砖 ⇒ false）
function brPassableAt(world, bx, by, bz) {
    try {
        var LocB = Java.type('org.bukkit.Location');
        var blk = world.getBlockAt(new LocB(world, 0 + bx, 0 + by, 0 + bz));
        if (blk == null) return true;
        var mt = null;
        try { mt = blk.getType(); } catch (e) { mt = null; }
        if (mt != null) { try { if (mt.isAir()) return true; } catch (e) { } }
        try { return !!blk.isPassable(); } catch (e) { }
        if (mt != null) { try { return !mt.isSolid(); } catch (e) { } }
        return true;
    } catch (e) { return true; }
}

// 走到 (x, z) 这一步时脚该落在哪个高度？
//   ① 脚下 + 头顶 都空 ⇒ 平着走
//   ② 抬高 BR_DASH_STEP_UP 格后能站 ⇒ 当台阶踩上去
//   ③ 都不行（= 障碍 ≥ BR_DASH_STOP_H 格）⇒ 返回 **null** 表示撞墙
function brDashStepY(world, x, y, z) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z), by = Math.floor(y);
        // ① 平着走
        if (brPassableAt(world, bx, by, bz) && brPassableAt(world, bx, by + 1, bz)) return y;
        // ② 上台阶（最多 BR_DASH_STEP_UP 格）
        for (var up = 1; up <= BR_DASH_STEP_UP; up++) {
            var ok = true;
            for (var c = 0; c <= 1; c++) {                 // 脚 + 头 两格都要空
                if (!brPassableAt(world, bx, by + up + c, bz)) { ok = false; break; }
            }
            if (ok) return by + up;
        }
        // ③ 撞墙
        return null;
    } catch (e) { return y; }
}

// ---------------- 冲刺（8 点 · 冷却 30 秒 · 不无敌 · 无提示 · 撞墙就停）----------------
function brStartDash(ref, tgt) {
    try {
        ref.mode   = 'dash';
        ref.hit    = {};
        ref.dashI  = 0;

        var l = null;
        try { l = ref.horse.getLocation(); } catch (e) { l = ref.boss.getLocation(); }
        ref.x0 = l.getX(); ref.y0 = l.getY(); ref.z0 = l.getZ();

        var tl = null;
        try { tl = tgt.getLocation(); } catch (e) { tl = null; }
        var dx = 0, dz = 0;
        if (tl != null) { dx = tl.getX() - ref.x0; dz = tl.getZ() - ref.z0; }
        var n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) {
            var yaw0 = 0;
            try { yaw0 = l.getYaw(); } catch (e) { yaw0 = 0; }
            dx = -Math.sin(yaw0 * Math.PI / 180.0);
            dz = Math.cos(yaw0 * Math.PI / 180.0);
        } else { dx /= n; dz /= n; }

        ref.dirX = dx; ref.dirZ = dz;
        // MC：前向量 = (-sin(yaw), cos(yaw)) ⇒ yaw = atan2(-dirX, dirZ)
        ref.yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;

        try { ref.horse.getWorld().playSound(l, 'entity.horse.gallop', 1.6, 0.8); } catch (e) { }
        brDashTick(ref);
    } catch (e) {
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

function brDashTick(ref) {
    try {
        if (ref == null || ref.mode !== 'dash') return;
        if (!brAlive(ref.boss)) { brCleanup(ref); return; }
        if (!brAlive(ref.horse)) { ref.mode = 'idle'; return; }   // 马没了就在地上打

        var world = ref.horse.getWorld();
        var i = ref.dashI;
        var k = (i + 1) / BR_DASH_TICKS;
        var x = ref.x0 + ref.dirX * BR_DASH_DIST * k;
        var z = ref.z0 + ref.dirZ * BR_DASH_DIST * k;
        var y = ref.y0;

        // ★ 撞墙规则（2026-10-06）：1 格 = 上台阶继续冲；≥2 格 = **停在墙前，冲刺结束**
        var stepY = brDashStepY(world, x, y, z);
        if (stepY == null) {
            ref.mode = 'idle';
            try {
                var LocW = Java.type('org.bukkit.Location');
                var wl = new LocW(world, x, y, z);
                try { world.playSound(wl, 'entity.horse.gallop', 0.8, 0.6); } catch (e) { }
                try {
                    var Pw = Java.type('org.bukkit.Particle');
                    var pw = null;
                    try { pw = Pw.CLOUD; } catch (e) { pw = null; }
                    if (pw == null) { try { pw = Pw.valueOf('CLOUD'); } catch (e) { pw = null; } }
                    if (pw != null) world.spawnParticle(pw, x, y + 1.0, z, 10, 0.4, 0.4, 0.4, 0.03);
                } catch (e) { }
            } catch (e) { }
            return;
        }
        y = stepY;

        try {
            var Loc = Java.type('org.bukkit.Location');
            var loc = new Loc(world, x, y, z);
            try { loc.setYaw(ref.yaw); loc.setPitch(0.0); } catch (e) { }
            ref.horse.teleport(loc);
            var Vector = Java.type('org.bukkit.util.Vector');
            ref.horse.setVelocity(new Vector(0.0, 0.0, 0.0));
        } catch (e) { }

        // 路径伤害（宽 5 格，每个目标一次）
        try {
            var list = world.getNearbyEntities(new Loc(world, x, y, z), BR_DASH_HALF_W + 2.0, 3.0, BR_DASH_HALF_W + 2.0);
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null) continue;
                if (!brAlive(ent)) continue;
                if (ent === ref.boss || ent === ref.horse) continue;
                if (brIsAlly(ent)) continue;                  // ★ 冲刺也不撞自己人

                var eid = '';
                try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                if (ref.hit[eid] === 1) continue;

                var el = null;
                try { el = ent.getLocation(); } catch (e) { continue; }
                var rx = el.getX() - x, rz = el.getZ() - z, ry = (el.getY() + 0.9) - y;
                var along = rx * ref.dirX + rz * ref.dirZ;
                var lat   = rx * ref.dirZ - rz * ref.dirX;
                if (Math.abs(lat) > BR_DASH_HALF_W) continue;
                if (along > 2.0 || along < -2.0) continue;
                if (Math.abs(ry) > 3.0) continue;

                ref.hit[eid] = 1;
                brHurtPierce(ref.boss, ent, BR_DASH_DMG, 0.0, 0.0, 0.0);   // ★ 无视防具
            }
        } catch (e) { }

        // 拖尾
        try {
            var P = Java.type('org.bukkit.Particle');
            var pt = null;
            try { pt = P.CLOUD; } catch (e) { pt = null; }
            if (pt == null) { try { pt = P.valueOf('CLOUD'); } catch (e) { pt = null; } }
            if (pt != null) {
                for (var j = -2; j <= 2; j++) {
                    world.spawnParticle(pt, x + j * 1.0, y + 0.15, z + j * 1.0, 2, 0.2, 0.1, 0.2, 0.02);
                }
            }
        } catch (e) { }

        ref.dashI = i + 1;
        if (ref.dashI >= BR_DASH_TICKS) { ref.mode = 'idle'; return; }
        runLater(function () { brDashTick(ref); }, 1);
    } catch (e) {
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

// ---------------- AI 心跳 ----------------
function brTick(ref) {
    try {
        if (ref == null) return;
        if (!brAlive(ref.boss)) { brCleanup(ref); return; }

        // ★ 记下最后一刻的位置（boss 真没了的时候，掉落物要靠这个坐标）
        try {
            var pl = ref.boss.getLocation();
            ref.lx = pl.getX(); ref.ly = pl.getY(); ref.lz = pl.getZ(); ref.lw = pl.getWorld();
        } catch (e) { }

        // ★ 战马比 boss 先死 → **惹怒 boss**（解锁凋零之首）
        if (ref.horse != null && !brAlive(ref.horse)) {
            ref.horse = null;                       // 以后只能走路（冲刺用不了）
            if (!ref.enraged) {
                ref.enraged = true;
                try {
                    var wl = ref.lw;
                    if (wl == null) wl = ref.boss.getWorld();
                    wl.playSound(ref.boss.getLocation(), 'entity.wither.spawn', 1.0, 0.8);
                    var PL = Java.type('org.bukkit.Particle');
                    var pp = null;
                    try { pp = PL.SMOKE_LARGE; } catch (e) { pp = null; }
                    if (pp == null) { try { pp = PL.valueOf('SMOKE_LARGE'); } catch (e) { pp = null; } }
                    if (pp == null) { try { pp = PL.SMOKE; } catch (e) { pp = null; } }
                    if (pp != null) {
                        for (var q = 0; q < 10; q++) {
                            wl.spawnParticle(pp,
                                ref.lx, ref.ly + 1.0 + q * 0.12, ref.lz,
                                2, 0.35, 0.35, 0.35, 0.05);
                        }
                    }
                } catch (e) { }
            }
        }

        // ★ boss 血条：每拍刷新（枯骸将军 / 璇骊 各一条）
        try {
            brBarSync(ref.barBoss,  ref.boss,  BR_BOSS_HP,  ref.barBossNear,  ref.owner);
            brBarSync(ref.barHorse, ref.horse, BR_HORSE_HP, ref.barHorseNear, ref.owner);
        } catch (e) { }

        // ★ 璇骊的马鞍复查补装（每 4 拍 = 1 秒一次）
        try {
            ref.tickN = (ref.tickN == null ? 0 : ref.tickN) + 1;
            if (ref.tickN % 4 === 2 && ref.horse != null) brSaddle(ref.horse);
        } catch (e) { }

        // ★ 伺骨：把已经消散的从列表里剔掉，顺便数一下还活着几只
        try {
            var keep = [];
            for (var si = 0; si < ref.servants.length; si++) {
                var so = ref.servants[si];
                if (so != null && !so.done && brAlive(so.sk)) keep.push(so);
            }
            ref.servants = keep;
        } catch (e) { }

        if (ref.mode !== 'dash') {
            var tgt = brTarget(ref);
            if (tgt != null) {
                var now = brNow();
                var d = brDist(ref.boss, tgt);

                // 斩击：5 点 / 3.5 秒 / 10 格内（★ 由 boss 射出、飞过去）
                //   ★ 出场 BR_FIRST_QI 毫秒内不出斩击（ref.qiAt 初值已把这个延迟算进去）
                if (d <= BR_QI_RANGE && (now - ref.qiAt) >= BR_QI_CD_MS) {
                    ref.qiAt = now;
                    brStartSlash(ref, tgt);
                }
                // ★ 伺骨：冷却 30 秒（出场 BR_SV_FIRST 毫秒后才第一次召）
                if ((now - (ref.svAt == null ? 0 : ref.svAt)) >= BR_SV_CD_MS) {
                    // ★ 只有真的召出来了才重置 CD（满员时下一拍再试）
                    if (brSummonServants(ref) > 0) ref.svAt = now;
                }
                // 冲刺：8 点 / 30 秒 / 20 格内（要马还在）
                if (ref.horse != null && d <= BR_DASH_RANGE && (now - ref.dashAt) >= BR_DASH_CD_MS) {
                    ref.dashAt = now;
                    brStartDash(ref, tgt);
                }
                // ★ 狂暴后：朝最近的玩家发射凋零之首（20 点 + 凋零 II 5 秒 / 冷却 4 秒 / 追踪）
                if (ref.enraged && d <= BR_SKULL_RANGE && (now - (ref.skullAt == null ? 0 : ref.skullAt)) >= BR_SKULL_CD_MS) {
                    ref.skullAt = now;
                    brFireSkull(ref, tgt);
                }
            }
        }

        runLater(function () { brTick(ref); }, BR_TICK);
    } catch (e) {
        // 出错也别让 boss 变成"傻站着"：只要还活着就继续下一拍
        try {
            if (ref != null && brAlive(ref.boss)) runLater(function () { brTick(ref); }, BR_TICK);
            else brCleanup(ref);
        } catch (e2) { }
    }
}

// ---------------- boss 死亡必掉「五骊矟」（只掉一次） ----------------
function brDropReward(ref) {
    try {
        if (ref == null || ref.dropped) return;
        ref.dropped = true;

        var world = ref.lw, x = ref.lx, y = ref.ly, z = ref.lz;
        if (world == null || x == null) {
            try {
                var l = ref.boss.getLocation();
                world = l.getWorld(); x = l.getX(); y = l.getY(); z = l.getZ();
            } catch (e) { }
        }
        if (world == null || x == null) return;

        var stack = null;
        try {
            var sf = getSfItemById(BR_DROP_ID);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }

        if (stack == null) {
            try { getServer().getLogger().warning('[构思技艺] 枯骸骑将：取不到 ' + BR_DROP_ID + '，未掉落'); } catch (e) { }
            return;
        }

        var Loc = Java.type('org.bukkit.Location');
        world.dropItemNaturally(new Loc(world, x, (y == null ? 0 : y) + 0.5, z), stack);
        try { world.playSound(new Loc(world, x, y, z), 'entity.player.levelup', 0.6, 1.2); } catch (e) { }
    } catch (e) { }
}

// ---------------- 收尾：boss 没了 → 战马跟着一起死 ----------------
function brCleanup(ref) {
    try {
        if (ref == null) return;
        var b = ref.boss;
        var h = ref.horse;

        // ★ boss 归天 → 伺骨也一并化烟（陪葬）
        try {
            if (ref.servants != null) {
                for (var vi = 0; vi < ref.servants.length; vi++) {
                    brSvDispose(ref.servants[vi], 'puff');
                }
                ref.servants = [];
            }
        } catch (e) { }

        // ★ 收掉两条 boss 血条
        try { brBarDrop(ref.barBoss,  ref.barBossNear);  ref.barBoss  = null; } catch (e) { }
        try { brBarDrop(ref.barHorse, ref.barHorseNear); ref.barHorse = null; } catch (e) { }

        // ★ 停掉 BGM（boss 没了，音乐也别再放）
        try { brMusicStopAll(ref); } catch (e) { }

        // ★ boss 死亡必掉五骊矟
        brDropReward(ref);

        // ★ 战马跟着一起**死掉**（不是凭空消失：先扣血走死亡流程，不行再兜底移除）
        if (h != null) {
            try { h.eject(); } catch (e) { }
            try {
                var eq = h.getEquipment();
                if (eq != null) { try { eq.clear(); } catch (e) { } }
            } catch (e) { }
            try {
                if (!h.isDead()) {
                    try { h.setHealth(0.0); } catch (e) { }
                    runLater(function () {
                        try { if (h != null && !h.isDead() && h.isValid()) h.remove(); } catch (e2) { }
                    }, 2);
                }
            } catch (e) { try { h.remove(); } catch (e2) { } }
            try {
                var hid = '' + h.getUniqueId();
                if (BR_H2B[hid] != null) delete BR_H2B[hid];
            } catch (e) { }
            ref.horse = null;
        }
        // boss 死了也别掉装备
        if (b != null) {
            try {
                var eq2 = b.getEquipment();
                if (eq2 != null) { try { eq2.clear(); } catch (e) { } }
            } catch (e) { }
        }

        try { if (b != null) delete BR_BOSSES['' + b.getUniqueId()]; } catch (e) { }
        try { if (ref.owner != null && BR_OWNER['' + ref.owner] != null) delete BR_OWNER['' + ref.owner]; } catch (e) { }
    } catch (e) { }
}

// ---------------- 召唤 ----------------
function brSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();

        // ① 已经有一只活的 → 静默忽略（防连点刷爆）
        var oldId = BR_OWNER[uuid];
        if (oldId != null) {
            var old = BR_BOSSES[oldId];
            if (old != null && brAlive(old.boss)) return false;
            try { delete BR_OWNER[uuid]; } catch (e) { }
        }

        var world = p.getWorld();
        var base = p.getLocation();

        // ② 出生点：玩家身前 3 格
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { yaw = 0; }
        var rad = yaw * Math.PI / 180.0;
        var Loc = Java.type('org.bukkit.Location');
        var loc = new Loc(world,
            base.getX() + (-Math.sin(rad)) * BR_SPAWN_AHEAD,
            base.getY(),
            base.getZ() + (Math.cos(rad)) * BR_SPAWN_AHEAD);
        try { loc.setYaw(yaw); loc.setPitch(0.0); } catch (e) { }

        var horse = brSpawnHorse(world, loc);
        var boss  = brSpawnBoss(world, loc);
        if (boss == null) {
            // boss 没生成成功 → 把马也清掉，别留孤儿实体
            if (horse != null) { try { horse.remove(); } catch (e) { } }
            return false;
        }
        if (horse != null) {
            try { horse.addPassenger(boss); } catch (e) { }
        }

        var ref = {
            boss: boss,
            horse: horse,
            owner: uuid,
            mode: 'idle',
            // ★ 出场 5 秒后才允许第一次斩击（刚召唤就劈一刀，玩家根本反应不过来）
            qiAt: brNow() - BR_QI_CD_MS + BR_FIRST_QI,
            dashAt: brNow() - BR_DASH_CD_MS + BR_FIRST_DASH,  // 5 秒后才允许第一次冲刺
            svAt: brNow() - BR_SV_CD_MS + BR_SV_FIRST,        // ★ 8 秒后才第一次召伺骨
            servants: [],         // ★ 场上还活着的伺骨
            barBoss: null, barBossNear: {},      // ★ 枯骸将军的血条
            barHorse: null, barHorseNear: {},    // ★ 璇骊的血条
            music: {},            // ★ BGM：正在听的玩家 uuid -> { p, at }
            hit: {}, dashI: 0, dirX: 0.0, dirZ: 0.0, x0: 0.0, y0: 0.0, z0: 0.0, yaw: 0.0,
            enraged: false,       // ★ 战马先死才置 true（解锁凋零之首）
            skullAt: 0,
            dropped: false,       // ★ 五骊矟只掉一次
            slashSeq: 0,
            lx: loc.getX(), ly: loc.getY(), lz: loc.getZ(), lw: world
        };

        // ★ boss 血条：枯骸将军（红 · 10 段）/ 璇骊（白 · 10 段）
        try { ref.barBoss  = brBarMake('枯骸将军', 'RED',   'SEGMENTED_10'); } catch (e) { }
        try { ref.barHorse = brBarMake('璇骊',     'WHITE', 'SEGMENTED_10'); } catch (e) { }
        try {
            brBarSync(ref.barBoss,  boss,  BR_BOSS_HP,  ref.barBossNear,  uuid);
            brBarSync(ref.barHorse, horse, BR_HORSE_HP, ref.barHorseNear, uuid);
        } catch (e) { }

        var bid = '';
        try { bid = '' + boss.getUniqueId(); } catch (e) { return false; }
        BR_BOSSES[bid] = ref;
        BR_OWNER[uuid] = bid;
        if (horse != null) {
            try { BR_H2B['' + horse.getUniqueId()] = bid; } catch (e) { }
        }

        try { world.playSound(loc, 'entity.wither.spawn', 0.5, 1.0); } catch (e) { }

        // ★ BGM：boss 周围循环播放《Pigstep》（走进范围才播，走出去/归天立刻停）
        try { if (BR_MUSIC) brMusicTick(ref); } catch (e) { }

        // ★ 出场提示词：先报枯骸将军，隔 0.5 秒再报璇骊（同世界所有在线玩家都收得到）
        try { brAnnounce(world, BR_MSG_BOSS); } catch (e) { }
        try {
            runLater(function () { try { brAnnounce(world, BR_MSG_HORSE); } catch (e2) { } }, 10);
        } catch (e) { }

        runLater(function () { brTick(ref); }, BR_TICK);
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 枯骸骑将 召唤出错: ' + e); } catch (e2) { }
        return false;
    }
}

// ---------------- 拦截：手持本物品右键方块 → 方块交互一律取消 ----------------
var BR_GUARD_OK = false;

function brRegisterGuard() {
    if (BR_GUARD_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var InteractEv = null;
        try { InteractEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { InteractEv = null; }
        if (InteractEv == null) return false;

        function handler(e) {
            try {
                var act = '';
                try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                if (act.indexOf('RIGHT_CLICK') < 0) return;      // 左键留给玩家正常挖方块

                var hs = '';
                try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;

                var h = null;
                try { h = e.getItem(); } catch (e2) { h = null; }
                if (h == null) return;
                if (!brIsOurItem(h)) return;

                e.setCancelled(true);      // 方块功能不触发（箱子/耕地/按钮/放置…全拦）
            } catch (err) { }
        }

        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST,
                function (l, e) { handler(e); }, plugin);
            BR_GUARD_OK = true;
        } catch (e1) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                pm.registerEvent(InteractEv, listener, Priority.HIGHEST, exec, plugin);
                BR_GUARD_OK = true;
            } catch (e2) { BR_GUARD_OK = false; }
        }
        return BR_GUARD_OK;
    } catch (e) { return false; }
}

try {
    if (brRegisterGuard()) {
        try { getServer().getLogger().info('[构思技艺] 枯骸骑将：方块交互拦截 已启用'); } catch (e) { }
    }
} catch (e) { }

// ---------------- 拿 Plugin 实例（注册监听器要用） ----------------
function brGetPlugin() {
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

// ---------------- 死亡事件：删原版掉落 / 必掉五骊矟 / 马先死则狂暴 ----------------
function brOnEntityDeath(e) {
    try {
        var ent = null;
        try { ent = e.getEntity(); } catch (err) { ent = null; }
        if (ent == null) return;

        var tag = brTag(ent);
        if (tag === '') return;                       // 不是我们的人 → 不管

        // ★ 原版掉落物（骷髅的骨头 / 箭、骷髅马的骨头）**全部删除**
        try { e.getDrops().clear(); } catch (err) { }
        try { e.setDroppedExp(0); } catch (err) { }
        // ★ 2026-10-06：**马鞍也不许掉** —— 马的鞍存在 HorseInventory 里（不在 Equipment），
        //   有的版本死亡时会连着 inventory 一起吐出来 ⇒ 这里先把 inventory 清空再让 drops 清空
        try {
            if (tag === 'bone_rider_horse' || tag === 'bone_rider_servant_horse') {
                var hi = ent.getInventory();
                if (hi != null) { try { hi.clear(); } catch (err2) { } }
            }
        } catch (err) { }
        try {
            var eqd = ent.getEquipment();
            if (eqd != null) { try { eqd.clear(); } catch (err3) { } }
        } catch (err) { }
        try { e.getDrops().clear(); } catch (err) { }   // 清完装备再清一次，双保险

        var eid = '';
        try { eid = '' + ent.getUniqueId(); } catch (err) { return; }

        if (tag === 'bone_rider') {
            var ref = BR_BOSSES[eid];
            if (ref != null) {
                try {
                    var l = ent.getLocation();
                    ref.lx = l.getX(); ref.ly = l.getY(); ref.lz = l.getZ(); ref.lw = l.getWorld();
                } catch (err) { }
                brDropReward(ref);                    // ★ 必掉五骊矟
                // ★ 战马跟着一起死
                var h = ref.horse;
                if (h != null && brAlive(h)) {
                    try { h.eject(); } catch (err) { }
                    try {
                        var eq = h.getEquipment();
                        if (eq != null) { try { eq.clear(); } catch (err2) { } }
                    } catch (err) { }
                    try { h.setHealth(0.0); } catch (err) { try { h.remove(); } catch (err2) { } }
                }
                brCleanup(ref);
            }
            try { delete BR_BOSSES[eid]; } catch (err) { }

        } else if (tag === 'bone_rider_horse') {
            var bid = BR_H2B[eid];
            if (bid != null) {
                var ref2 = BR_BOSSES[bid];
                if (ref2 != null) {
                    ref2.horse = null;
                    if (!ref2.enraged) {              // ★ 马先死 → 惹怒 boss
                        ref2.enraged = true;
                        try { ent.getWorld().playSound(ent.getLocation(), 'entity.wither.spawn', 1.0, 0.8); } catch (err) { }
                    }
                }
                try { delete BR_H2B[eid]; } catch (err) { }
            }

        // ★ 伺骨 / 残驹：**共生** —— 一个死了，另一个立刻跟着死
        } else if (tag === 'bone_rider_servant') {
            var sv1 = BR_SERVANTS[eid];
            if (sv1 != null) { brSvDispose(sv1, 'kill'); try { delete BR_SERVANTS[eid]; } catch (err) { } }

        } else if (tag === 'bone_rider_servant_horse') {
            var sid = BR_SV_H2S[eid];
            if (sid != null) {
                var sv2 = BR_SERVANTS[sid];
                if (sv2 != null) brSvDispose(sv2, 'kill');
                try { delete BR_SV_H2S[eid]; } catch (err) { }
            }
        }
    } catch (e2) { }
}

// ---------------- 凋零之首命中：伤害固定 20 点 + 凋零 II 5 秒 ----------------
function brOnSkullDamage(e) {
    try {
        // ★ 先过一遍"自己人不打自己人"（本监听器挂在 EntityDamageByEntityEvent 上，
        //   顺手把 boss / 伺骨 之间的误伤 —— 平A、凋零之首、弹体 —— 全部免掉）
        try { brOnAllyDamage(e); } catch (err) { }
        try { brOnPierce(e); } catch (err) { }        // ★ 冲刺伤害无视防具
        try { brOnServantDamage(e); } catch (err) { } // ★ 伺骨不许打创造 / 旁观

        if (BR_SKULL_KEY == null || BR_PDT == null) return;
        var dm = null;
        try { dm = e.getDamager(); } catch (err) { dm = null; }
        if (dm == null) return;

        var v = null;
        try { v = dm.getPersistentDataContainer().get(BR_SKULL_KEY, BR_PDT.STRING); } catch (err) { return; }
        if (v == null) return;                        // 不是我们发的头 → 不管

        try { e.setDamage(BR_SKULL_DMG); } catch (err) { }   // ★ 固定 20 点

        var victim = null;
        try { victim = e.getEntity(); } catch (err) { victim = null; }
        if (victim == null) return;

        // 凋零 II 5 秒：延后 1 刻**强制覆盖**（原版凋零头自带的那份更长，会被我们顶掉）
        runLater(function () {
            try {
                var PET = Java.type('org.bukkit.potion.PotionEffectType');
                var PE = Java.type('org.bukkit.potion.PotionEffect');
                var t = null;
                try { t = PET.WITHER; } catch (err) { t = null; }
                if (t == null) { try { t = PET.valueOf('WITHER'); } catch (err) { t = null; } }
                if (t == null) return;
                try { victim.addPotionEffect(new PE(t, BR_SKULL_WITHER_T, BR_SKULL_WITHER_AMP, true, true), true); }
                catch (err1) { try { victim.addPotionEffect(new PE(t, BR_SKULL_WITHER_T, BR_SKULL_WITHER_AMP)); } catch (err2) { } }
            } catch (err) { }
        }, 1);
    } catch (e2) { }
}

// ==================================================================
// 凋零之首「**不破坏地形**」——真正的实现在这里
// ==================================================================
// ★ 为什么光靠 setCharged(false) / setYield(0) 不够：
//   原版凋零之首命中后**一定**会触发一次爆炸，爆炸强度（power 1）是 NMS 硬编码的，
//   CraftBukkit 的 setYield 在部分版本只影响"掉落率"，压根没接进这条爆炸链
//   ⇒ 结果就是玩家看到的："明明设了黑色头，地形还是被炸了"。
// ★ 彻底的做法：**监听 EntityExplodeEvent，把待破坏的方块列表清空**。
//   Bukkit 爆炸流程是「算出待破坏方块 → 发事件 → 按**事件里的那份列表**去破坏」，
//   所以清空列表 = 一个方块都不会被移除（WorldGuard 等保护插件就是这么干的）。
//   ★ 只清列表、**不 setCancelled(true)**：取消事件有可能把"对生物的伤害"一起掐掉，
//     伤害还是交给 brOnSkullDamage 结算，两边互不干扰。
// ★ 双重判定（两条路任一命中都算我们的头）：
//   ① 弹体自己带 `gs:boss_skull` 标记；
//   ② 弹体的发射者（shooter）带 `gs:boss` 标记（标记万一没写上去时的兜底）。
// ==================================================================
function brIsOurSkull(ent) {
    try {
        if (ent == null) return false;

        // ① 弹体自己的标记
        if (BR_SKULL_KEY != null && BR_PDT != null) {
            try {
                if (ent.getPersistentDataContainer().get(BR_SKULL_KEY, BR_PDT.STRING) != null) return true;
            } catch (e) { }
        }

        // ② 发射者是不是我们的 boss
        try {
            if (typeof ent.getShooter !== 'function') return false;
            var sh = ent.getShooter();
            if (sh == null || typeof sh.getPersistentDataContainer !== 'function') return false;
            if (BR_BOSS_KEY == null || BR_PDT == null) return false;
            if (sh.getPersistentDataContainer().get(BR_BOSS_KEY, BR_PDT.STRING) != null) return true;
        } catch (e) { }
    } catch (e) { }
    return false;
}

function brOnExplode(e) {
    try {
        var ent = null;
        try { ent = e.getEntity(); } catch (err) { return; }
        if (!brIsOurSkull(ent)) return;                   // 不是我们发的头 → 不管

        // ① 主手段：清空待破坏方块列表 ⇒ 地形一个方块都不掉
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
        // 顺带把掉落率设 0（只剩"不掉物品"这一层保险，与上面不冲突）
        try { if (typeof e.setYield === 'function') e.setYield(0); } catch (err) { }

        // ② 兜底：某些分支下"清空列表"可能不被采纳（爆炸链走的是它自己那份集合）。
        //    那就 1 刻之后**照着快照把被炸掉的方块还原** —— 只在"方块真的变了"时才写回，
        //    所以正常情况下这一步是纯检查、一个方块都不动。
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

var BR_EXPLODE_OK = false;

function brRegisterExplode() {
    if (BR_EXPLODE_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var plugin = brGetPlugin();
        if (plugin == null) return false;
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var Ev = null;
        try { Ev = Java.type('org.bukkit.event.entity.EntityExplodeEvent'); } catch (e) { Ev = null; }
        if (Ev == null) return false;

        var ok = false;
        try {
            pm.registerEvent(Ev, plugin, Priority.HIGHEST,
                function (l, e) { brOnExplode(e); }, plugin);
            ok = true;
        } catch (e1) {
            try {
                var LC3 = Java.type('org.bukkit.event.Listener');
                var EC3 = Java.type('org.bukkit.plugin.EventExecutor');
                var lis3 = new JavaAdapter(LC3, {});
                var exe3 = new JavaAdapter(EC3, { execute: function (l, e) { brOnExplode(e); } });
                pm.registerEvent(Ev, lis3, Priority.HIGHEST, exe3, plugin);
                ok = true;
            } catch (e2) { ok = false; }
        }
        BR_EXPLODE_OK = ok;
        return ok;
    } catch (e) { return false; }
}

var BR_DEATH_OK = false;

function brRegisterDeath() {
    if (BR_DEATH_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var plugin = brGetPlugin();
        if (plugin == null) return false;
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var DeathEv = null;
        try { DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent'); } catch (e) { DeathEv = null; }
        if (DeathEv == null) return false;

        var ok = false;
        try {
            pm.registerEvent(DeathEv, plugin, Priority.HIGHEST,
                function (l, e) { brOnEntityDeath(e); }, plugin);
            ok = true;
        } catch (e1) {
            try {
                var LC = Java.type('org.bukkit.event.Listener');
                var EC = Java.type('org.bukkit.plugin.EventExecutor');
                var lis = new JavaAdapter(LC, {});
                var exe = new JavaAdapter(EC, { execute: function (l, e) { brOnEntityDeath(e); } });
                pm.registerEvent(DeathEv, lis, Priority.HIGHEST, exe, plugin);
                ok = true;
            } catch (e2) { ok = false; }
        }

        // 凋零之首的伤害改写（拿不到就算了，掉落清理照样能用）
        try {
            var DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent');
            try {
                pm.registerEvent(DmgEv, plugin, Priority.HIGHEST,
                    function (l, e) { brOnSkullDamage(e); }, plugin);
            } catch (e1) {
                var LC2 = Java.type('org.bukkit.event.Listener');
                var EC2 = Java.type('org.bukkit.plugin.EventExecutor');
                var lis2 = new JavaAdapter(LC2, {});
                var exe2 = new JavaAdapter(EC2, { execute: function (l, e) { brOnSkullDamage(e); } });
                pm.registerEvent(DmgEv, lis2, Priority.HIGHEST, exe2, plugin);
            }
        } catch (e) { }

        BR_DEATH_OK = ok;
        return BR_DEATH_OK;
    } catch (e) { return false; }
}

// ==================================================================
// ★ 阵营（2026-10-06）：禁止"自己人把自己人当目标"
//   伤害那半边（平A / 凋零之首 / 弹体）已经在 brOnSkullDamage 里顺手处理了，
//   这里只补 AI 的**索敌**：骷髅一旦被误伤就会回头砍 boss ⇒ 从源头掐掉。
// ==================================================================
var BR_ALLY_OK = false;

function brRegisterAlly() {
    if (BR_ALLY_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var plugin = brGetPlugin();
        if (plugin == null) return false;
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var Ev = null;
        try { Ev = Java.type('org.bukkit.event.entity.EntityTargetEvent'); } catch (e) { Ev = null; }
        if (Ev == null) return false;

        var ok = false;
        try {
            pm.registerEvent(Ev, plugin, Priority.HIGHEST,
                function (l, e) { brOnAllyTarget(e); }, plugin);
            ok = true;
        } catch (e1) {
            try {
                var LC = Java.type('org.bukkit.event.Listener');
                var EC = Java.type('org.bukkit.plugin.EventExecutor');
                var lis = new JavaAdapter(LC, {});
                var exe = new JavaAdapter(EC, { execute: function (l, e) { brOnAllyTarget(e); } });
                pm.registerEvent(Ev, lis, Priority.HIGHEST, exe, plugin);
                ok = true;
            } catch (e2) { ok = false; }
        }
        BR_ALLY_OK = ok;
        return ok;
    } catch (e) { return false; }
}

try {
    if (brRegisterDeath()) {
        try { getServer().getLogger().info('[构思技艺] 枯骸骑将：死亡掉落处理 已启用'); } catch (e) { }
    }
    if (brRegisterExplode()) {
        try { getServer().getLogger().info('[构思技艺] 枯骸骑将：凋零之首 不破坏地形 已启用'); } catch (e) { }
    } else {
        try { getServer().getLogger().warning('[构思技艺] 枯骸骑将：爆炸拦截注册失败，凋零之首会炸坏地形！'); } catch (e) { }
    }
    if (brRegisterAlly()) {
        try { getServer().getLogger().info('[构思技艺] 枯骸骑将：阵营（不打自己人）已启用'); } catch (e) { }
    } else {
        try { getServer().getLogger().warning('[构思技艺] 枯骸骑将：阵营拦截注册失败，伺骨可能回头打 boss！'); } catch (e) { }
    }
} catch (e) { }

// ---------------- 入口 ----------------
function onUse(event) {
    try {
        try { brRegisterGuard(); } catch (e) { }        // 惰性重试
        try { brRegisterDeath(); } catch (e) { }        // 惰性重试（死亡掉落 / 凋零之首）
        try { brRegisterExplode(); } catch (e) { }      // 惰性重试（凋零之首不破坏地形）
        try { brRegisterAlly(); } catch (e) { }         // 惰性重试（阵营：不打自己人）

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        // ★ 只认主手（副手触发会"不消耗却生效"）
        if (!brIsMainHandUse(event, p)) return;

        // 防同一次点击被派发两遍（主手 + 副手 / 重复注册）
        var uuid = '' + p.getUniqueId();
        var now = brNow();
        var last = BR_LAST[uuid];
        if (last != null && (now - (0 + last)) < 500) return;
        BR_LAST[uuid] = now;

        // ★ 召唤成功才扣物品（已经是"每个玩家 1 只"的上限内）
        if (!brSummon(p)) return;
        if (BR_CONSUME) brConsume(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 枯骸骑将 onUse 出错: ' + err); } catch (e) { }
    }
}

// 玩家下线：不删 boss（别人可能正在打），只把"归属"让出来，方便他下次再召
function onPlayerQuit(event) {
    try {
        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        var bid = BR_OWNER[uuid];
        if (bid == null) return;
        var ref = BR_BOSSES[bid];
        if (ref != null && brAlive(ref.boss)) return;    // 还活着 → 留着
        try { delete BR_OWNER[uuid]; } catch (e) { }
    } catch (e) { }
}
