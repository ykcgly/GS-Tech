/* ==========================================================
 * 构思技艺 (GS) —— 「妄念」（三阶段 boss · **唤魔者头**召唤 · 消耗品）
 * items.yml 里由  script: gs_boss_three  引用；入口函数 onUse(event)
 * ==========================================================
 * 右键 = 召唤**第一阶段**，一只 boss 死掉 → 接下一只，**三只全死才算打完**：
 *
 *   ① **幻念僧**（**掠夺者 Pillager** · **空手本体射击**）—— **300 血**，基础伤害 **15 点**
 *      · ★ 2026-10-06：**弩去掉了**。原版掠夺者拿弩的装填太慢（≈1.9 秒且不受控），
 *        改成**本体每 1.5 秒发射一支箭**（`BT_S1_ATK_CD`），双手每拍强制清空。
 *        箭伤 **12 点**（`BT_S1_ARROW_DMG`，在伤害事件里按 shooter 统一改写）
 *      · 2026-10-06 **原型由「唤魔者」改为「掠夺者」**：唤魔者自带原版施法（召唤恼鬼 / 尖牙 /
 *        咩咩羊 / 致盲），去不干净；换成掠夺者最省事 —— 但**两个自定义技能全部保留**。
 *      · 技能 1：**追踪鳄鱼嘴** —— 朝玩家放一团一路追人的尖牙（EvokerFangs），
 *        命中 **15 点**伤害，**最远飞 12 格**，冷却 **10 秒**
 *      · 技能 2：**瞬影** —— 读条 **1 秒**，然后出现在**玩家视角正后方 8 格**，
 *        并**恢复 50 点生命值**（2026-10-06：20 → **50**），冷却 **20 秒**
 *      · 死亡：原地留下一团**羊驼口水**烟雾，**3 秒后**在死亡位置召唤 ↓
 *   ② **碎念执**（卫道士 Vindicator）—— **800 血**，基础伤害 **20 点**
 *      · 技能 1：**斩击** —— 一道**竖着的**刀光，伤害 **35 点**（2026-10-06：20 → 16 → **35**），
 *        特效 **樱花瓣**，弹道稍快、**射程 4 格**、击退约 **3 格**，
 *        冷却 **10 秒**（2026-10-06：8 → **10**），**会破盾**（见文末"破盾"说明）
 *        ⚠ **2026-10-06 修正**：方向原本在举刀前就锁死 ⇒ 斩击飞向"玩家 0.15 秒前站的地方"，
 *          现在改成**出手那一刻才定向**
 *      · 技能 2（**2026-10-06 新增**）：**星环飞斧** —— 身上浮现**环绕的星星粒子**、
 *        **持续 1.5 秒**（蓄力）后**飞出一把真斧子**：
 *          · **最远 10 格**、伤害 **35 点**、冷却 **12 秒**、**小小击退**
 *          · **直线飞行、不穿墙**；**撞到方块 / 玩家就停下**
 *          · ★ 斧头**停下的位置**生成一只「**妄想**」（恼鬼 Vex）：**伤害 7、血量 25**，
 *            **9 秒后自动处死**（避免残留卡服），场上最多 3 只
 *          · ★ 妄想**与卫道士同队**（打上 `gs:boss3` 标记）⇒ **不会受到斧子与樱花斩击的伤害**，
 *            两者互相免伤、也不会互相索敌
 *        ⚠ **和斩击一样，出场保护期（`BT_FIRST_MS`）内不会放**，不会一召唤就甩斧子
 *      · 死亡：死亡位置冲起一条**灵魂沙粒子柱**（3 秒窜上去 20 多格），
 *        3 秒后在死亡位置召唤 ↓
 *   ③ **妄念魔**（劫掠兽 Ravager）—— **1000 血**，基础伤害 **40 点**（2026-10-06：25 → **40**）
 *      · 技能 1：**坠地** —— 原地不动、**漂浮抬升**、蓄力 **1 秒**，
 *        然后出现在**玩家头顶**上方，**1 秒后落地**，落地对 **3.5 格**内所有生物
 *        造成 **150 点**伤害（★ **悬停开始后不追踪**：悬停那 1 秒玩家跑得掉），
 *        冷却 **11 秒**（2026-10-07：20 → **11**）
 *        ⚠ **2026-10-06 修正**：落点原本在**起手**就算好（蓄力整整 1 秒）⇒ 砸的是空气，
 *          现在改成**蓄力结束那一刻**才用目标当时的位置
 *      · **原技能 2「龙息弹」已按需求删除**（`BT_S3_FIRE_*` 与 `btS3Fire` / `btFireTick` 一并移除）
 *      · ★ 血量 1500 → 1200 → **1000**：这台服务器的实体血量上限**卡在 1024**，
 *        1200 写不进去 ⇒ 1000 才落得下去。
 *
 * ★ **阶段 BGM（2026-10-06 新增）**：每个阶段各配一首唱片，**该阶段存活期间循环播放**、
 *    **阶段死亡立刻停**（下一阶段再起自己那首）：
 *      ① 幻念僧 → `music_disc.precipice`（Precipice，4:59）
 *      ② 碎念执 → `music_disc.creator`（Creator，2:56）
 *      ③ 妄念魔 → `music_disc.creator_music_box`（Creator 八音盒，1:13）
 *    范围 `BT_MUSIC_RANGE`(64) 格内的玩家听得到（走远停、走近从头播），音量 4.0 = 唱片机原版。
 * ★ **掉落物（2026-10-08 第二批改 · ★★★ 第八十五 / 八十六批再改）**：
 *    **妄想碎片**（`GS_WANGXIANG_FRAGMENT`，唱片碎片材质 · 放在「基础构思」分类）
 *    ★★★ 第八十五批：**只有第三阶段死掉才掉，固定 6 个**（用户原话：
 *      「妄想碎片也改成第三阶段，固定掉落6个」）。
 *      改前是「每一阶段都掉 1 / 3 / 8 个（一轮共 12 个）」。
 *    ★★★ 第八十六批：**二阶段固定掉落 1 把「残樱」**（用户原话：
 *      「妄念一阶段掉落物删除，二阶段换成固定掉落一把残樱，
 *        删除残樱配方，改成击败二阶段妄念掉落」）。
 *      ⇒ 一阶段（幻念僧）**什么都不掉**；二阶段（碎念执）掉 **1 把「残樱」**；
 *        三阶段（妄念魔）掉 **6 个「妄想碎片」**。
 *      · 残樱的 9 格配方已从 `mb_machines.yml` 的构思机里**删除**，
 *        `items.yml` 的 `recipe_type` 改成 `GS_RT_WANGNIAN_S2`
 *        （配方界面左边显示「击败二阶段妄念掉落」，九宫格留空）。
 *    ★ **精神力不再掉落**：改为唤梦机 **60 个妄想碎片 + 1 个残樱 → 1 个余念绝响**
 *      （★★ 第八十五批：原为 36 个 + 1 个入梦）。
 *    想象力**保持原来的获取方式不变**（「构思」分类里点击获取，`GS_RT_CATCH`）。
 * ★ **召唤物图标（2026-10-12 第六十八批改）**：材质 = **`saveditems/hmz.yml`**
 *    （`material_type: saveditem` + `material: hmz`）= **玩家头颅 · 唤魔者皮肤**。
 *    ★ 原来那套「给黄旗帜补 4 层图案」的兜底代码（`btBannerPaint` /
 *      `btBannerPatchTemplate` / `btBannerSweep` + 每 2 秒的扫描循环）
 *      **已随材质换成头颅一并删除** —— 头颅没有旗帜图案可言。
 * ★ **使用动画**：右键时播的「不死图腾动画」**已按需求删除**。
 *
 * ★ **三阶段都没死透之前，同一个玩家无法再召唤**（和第二阶段的枯骸骑将同套限制）。
 * ★ **每个阶段各挂一条 BossBar**（名字就是阶段名），进度 = 当前血量 / 最大血量；
 *   默认**只有召唤者本人看得见**（`BT_BAR_ONLY_OWNER`）。
 * ★ **全部阶段的原版掉落物统统清空**（战利品表 + 死亡事件双保险），经验也不掉。
 * ★ 每个阶段**出场 BT_FIRST_MS 毫秒内不放任何技能**（刚召唤就糊脸玩家反应不过来）。
 * ★ **创造模式怎么处理**：默认"能打到创造模式"，但如果场上同时还有别的非创造玩家，
 *   会**优先挑非创造**的；想完全不理创造模式就把 `BT_CREATIVE_FALLBACK` 设成 false。
 * ★ **消耗品**：召唤成功才扣 1 个（`BT_CONSUME = false` 可改成无限用）。
 * ★ **不能与方块交互**（手持时右键方块一律取消），但**照样召唤 boss**。
 * ★ **三阶段通用技能「锁链拽回」—— 反挖三填一（2026-10-07 修正设计）**
 *    玩家用「**挖三填一**」在 boss 面前挖 1 格深的沟，把 boss **卡住**，
 *    自己站在沟沿上安全输出 ⇒ **boss 打不到人，人却打得到 boss**。
 *    · 读条 **1 秒**（boss 与被锁玩家之间拉**锁链**粒子）→ 把 **64 格内**的玩家
 *      **强制传送**到 boss **脚下 1.6 格**处围成一圈 ⇒ **一定进 boss 的近战范围**
 *    · 冷却 **18 秒**；**不设距离门槛** —— 64 格内**一律全拉**
 *    · ★ **出场延迟 10 秒**（`BT_DRAG_FIRST_MS`，2026-10-07 新增）：本阶段其他技能 4 秒
 *      就绪，锁链**要等到 10 秒**，避免"刚进阶段就被拽到脸上 + 同刻吃满斩击/飞斧"。
 *      ※ 「初始释放时机」 ≠ 「内置冷却」：内置冷却仍是 18 秒，没动。
 *    · ★ 2026-10-07 关键修正：
 *      ① 早期版本的「**4 格内不拉**」(`BT_DRAG_MIN`) **已删除** —— 挖三填一的玩家
 *         离 boss 只有 1～2 格，这条规则恰好把**唯一需要被拉的人**放过了；
 *      ② 早期版本的触发条件是「有人在**射程外**」⇒ 同样的问题，**技能一次都不触发**。
 *         现在改成「**这一轮还有没被拉过的人**」，与距离无关。
 *    · ★ `ref.dragged` 记「本轮已拉过谁」，**在 CD 好了那一刻清空** ——
 *      不清的话第一次拉完之后所有人都进了名单 ⇒ **这个技能一辈子只放一次**。
 *    · ★ **全程无伤害** —— 它是控制手段不是输出手段
 *
 * ★ **破盾（2026-10-06 重做）**：**照原版"斧头砍盾牌"来** ——
 *     ① `p.setCooldown(Material.SHIELD, 100)`（5 秒）⇒ **举不起盾**（这是原版斧头破盾的核心）；
 *     ② 盾牌按原版口径**掉耐久** = `1 + 向下取整(伤害)`（**不再一击压到极限**），
 *        耐久真掉光了就按原版规则直接碎掉。
 * ========================================================== */

try { getServer().getLogger().info('[构思技艺] gs_boss_three 已加载（v2026-10-07c 锁链出场延迟10秒·坠击11秒冷却）'); } catch (e) { }

// ==================================================================
// 参数（想改数值改这里就够了）
// ==================================================================
var BT_ITEM_ID      = 'GS_WANGNIAN';
var BT_CONSUME      = true;     // ★ 消耗品
var BT_SPAWN_AHEAD  = 3.0;      // 在玩家身前 3 格生成
var BT_TICK         = 5;        // AI 心跳：每 5 刻（0.25 秒）一次
var BT_FIRST_MS     = 4000;     // ★ 每个阶段出场 4 秒内**不放技能**
var BT_SEEK         = 32.0;     // 找目标的范围
var BT_CREATIVE_FALLBACK = true;// ★ 场上没有非创造玩家时，是否允许打创造模式（详情见文件头）

// ---------------- ① 幻念僧（**掠夺者** PILLAGER · 本体射击） ----------------
// ★ 2026-10-06 改：原来是「唤魔者 EVOKER」，但唤魔者自带原版施法（召唤恼鬼 / 尖牙），
//   而且之前给它配的资源包剑会被伤害兜底逻辑吃掉 ⇒ 换成**掠夺者**。
// ★ 2026-10-06 再改：**弩去掉了** —— 原版掠夺者用弩的装填太慢（≈1.9 秒一轮还不受控）。
//   现在改成**本体直接发射**：脚本每 `BT_S1_ATK_CD` 毫秒朝目标丢一支箭，
//   节奏完全由脚本说了算（1.5 秒一发），双手**强制清空**（免得它掏出原版弩）。
var BT_S1_NAME      = '\u00a7f\u5e7b\u5ff5\u50e7';   // §f幻念僧
var BT_S1_HP        = 300.0;
var BT_S1_DMG       = 15.0;    // 基础（近战）伤害
var BT_S1_HEIGHT    = 2;       // 站进去要几格净空（掠夺者 ≈ 2 格高）
var BT_S1_ARROW_DMG = 12.0;    // ★ 每支箭的伤害 **12 点**（在伤害事件里按 shooter 追一层，统一改写）
var BT_S1_ARROW_SPEED = 3.0;   // 箭速（原版满蓄力弓 ≈ 3.0；越小越容易躲）
var BT_S1_ATK_CD    = 1500;    // ★ **每 1.5 秒发射一发**（本体发射，不看弩的装填）
var BT_S1_ATK_RANGE = 24.0;    // 超过这个距离就不射了

var BT_S1_FANG_DMG  = 15.0;    // 追踪鳄鱼嘴：15 点
var BT_S1_FANG_RANGE= 12.0;    // 最远飞 12 格
var BT_S1_FANG_CD   = 10000;   // 冷却 10 秒
var BT_S1_FANG_SPEED= 0.42;    // 每刻推进多少格（12 格 ≈ 29 刻 ≈ 1.45 秒）
var BT_S1_FANG_R    = 1.2;     // 咬到人判定半径
var BT_S1_FANG_LIFE = 80;      // 最多活 80 刻（4 秒）自毁，绝不残留
var BT_S1_FANG_SPAWN= 3;       // 每隔几刻丢一团新的尖牙（连成一串追过来）

var BT_S1_BLINK_CD  = 20000;   // 冷却 20 秒
var BT_S1_BLINK_READ= 20;      // 读条 20 刻 = 1 秒
var BT_S1_BLINK_BACK= 8.0;     // 出现在玩家**视角正后方** 8 格
var BT_S1_BLINK_BEHIND = true; // true = 背后；false = 视线正前方（想改理解就翻这个开关）
var BT_S1_BLINK_HEAL= 50.0;    // 恢复 **50 点**生命（2026-10-06：20 → **50**）

// ---------------- ② 碎念执（卫道士） ----------------
var BT_S2_NAME      = '\u00a7f\u788e\u5ff5\u6267';   // §f碎念执
var BT_S2_HP        = 800.0;    // 2026-10-06：1000 → **800**
var BT_S2_DMG       = 20.0;    // 基础（普攻）伤害 20 点
var BT_S2_HEIGHT    = 2;

var BT_S2_SLASH_DMG = 35.0;    // 斩击伤害 **35 点**（2026-10-06：20 → 16 → **35**）
var BT_S2_SLASH_CD  = 10000;   // 冷却 **10 秒**（2026-10-06：8 → **10**）
var BT_S2_SLASH_RANGE= 4.0;    // 射程 4 格
var BT_S2_SLASH_SPEED=0.5;     // 弹道速度：4 格只要 8 刻（0.4 秒）——"稍快"
var BT_S2_SLASH_WINDUP=3;      // ★ 举刀刻数：**这 3 刻只出粒子不锁方向**，第 4 刻出手那一刻才定向
                             //   （2026-10-06 修正：原在起手就锁方向 ⇒ 斩击飞向"玩家 0.15 秒前站的地方"）
var BT_S2_SLASH_KB  = 0.95;    // 击退力度（≈ 推出 3 格）
var BT_S2_SLASH_UP  = 0.35;    // 顺带挑飞一点点（有"跺"的手感）
var BT_S2_SLASH_HALF_W = 1.1;  // 左右判定宽（各 1.1 格）
var BT_S2_SLASH_H   = 1.6;     // 竖直刀光的上下范围（各 1.6 格）——"竖着的斩击"
var BT_S2_SLASH_SHIELD_CD = 100;   // 破盾：放 100 刻（5 秒）的举盾冷却

// ---------------- ②-2 碎念执 · 技能 2：星环飞斧（2026-10-06 新增） ----------------
//   ① 身上浮现**环绕的星星粒子**、持续 `BT_S2_AXE_CHARGE`(30 刻 = 1.5 秒)
//   ② 蓄力结束**飞出一把真斧子**（隐形盔甲架手持铁斧，客户端能看见那把斧头在飞）
//      · **最远 `BT_S2_AXE_RANGE`(10 格)**、伤害 **35 点**、冷却 **12 秒**、**小小击退**
//      · **直线飞行、不穿墙**（用 `btPassableAt` 逐步判定，玻璃也挡得住）
//      · **撞到方块 / 玩家就停下**（不再继续飞）
//   ③ 斧头**停下的位置**生成一只「妄想」（恼鬼 Vex）：**伤害 7、血量 25**
//      · **9 秒后自动处死**（`remove()`，不触发死亡掉落 ⇒ 绝不卡服）
//      · 场上最多 `BT_S2_VEX_MAX`(3) 只，超出就不生
//   ④ ★ 妄想**与卫道士同队**：同样打 `gs:boss3` 标记 ⇒ 斩击 / 飞斧的命中判定里
//      `btOurs(ent)` 直接跳过它，伤害事件里也互相免伤、不会互相索敌。
var BT_S2_AXE_CD     = 12000;   // 冷却 12 秒
var BT_S2_AXE_CHARGE = 30;      // 环绕星星粒子 30 刻 = 1.5 秒
var BT_S2_AXE_RANGE  = 10.0;    // 最远 10 格
var BT_S2_AXE_DMG    = 35.0;    // 斧子伤害 35 点
var BT_S2_AXE_SPEED  = 0.62;    // 每刻推进多少格（10 格 ≈ 16 刻 ≈ 0.8 秒，飞得干脆）
var BT_S2_AXE_KB     = 0.42;    // 小小击退（≈ 推出 1 格多）
var BT_S2_AXE_UP     = 0.18;    // 顺带挑飞一点点
var BT_S2_AXE_R      = 1.05;    // 命中判定半径（撞到玩家的判定盒）
var BT_S2_AXE_STAR_N = 12;      // 环绕粒子数量
var BT_S2_AXE_STAR_R = 1.15;    // 环绕半径
var BT_S2_AXE_HALF   = 0.85;    // 飞行高度 = 生成点 +0.85（胸口高度）
var BT_S2_AXE_TRIGGER= 13.0;    // 目标多远以内才放（省得 10 格斧子白扔）

var BT_S2_VEX_HP     = 25.0;    // 妄想血量 25
var BT_S2_VEX_DMG    = 7.0;     // 妄想伤害 7
var BT_S2_VEX_LIFE   = 180;     // 9 秒 = 180 刻后处死（避免卡服）
var BT_S2_VEX_MAX    = 3;       // 场上最多几只
var BT_S2_VEX_TAG    = 'wangnian2_vex';   // ★ 与卫道士同队的标记值

// ==================================================================
// ★★★ 通用技能：「**锁链拽回**」—— **三个阶段共用**（2026-10-06 新增）
//
//   起因（用户原话：「玩家利用**挖三填一**的方式去挡住 boss，
//   导致 **boss 攻击不到玩家而玩家攻击得到 boss**」）：
//     玩家在 boss 面前挖一条 1 格深的沟（挖三填一），boss 走进去**被卡住**，
//     玩家站在沟沿上安全输出 —— 结果 **boss 打不到人、人却能打 boss**。
//     ★ 关键点：卡位玩家离 boss 只有 **1～2 格**，所以
//       ① 早期版本的 `BT_DRAG_MIN = 4.0`（"近身不拉"）**完全没用** —— 他在圈内；
//       ② 早期版本的触发条件是"有人在**射程外**" ⇒ 他在圈内 ⇒ **技能根本不会触发**。
//     ⇒ 现在的设计：**只要场上还有没被拉到的玩家，就全拉**，不看距离。
//
//   现在的行为：
//     ① 触发条件 = **「有玩家还没被拉过来」**（`BT_DRAG_PENDING`，见下）
//        —— 不再用"射程外"当门槛，**近身的卡位玩家照样会被拉**（这是本技能的 purpose）
//     ② 读条 `BT_DRAG_READ`(20 刻 = 1 秒)，期间锁定范围内的所有玩家
//     ③ 读条结束 → 把他们**强制传送**到 boss **脚下周围**围成一圈
//        —— ★ **落点必须贴着 boss**（`BT_DRAG_RING` 可调小），
//           因为用途是"让 boss 能打到人"，落太远等于没拉
//     ④ **无伤害** —— 纯控制手段，不附带任何伤害
//
//   ★★★ 为什么落点要"贴脸"而不是"围一圈散开"（2026-10-07 修正）：
//     早期版本半径 2.6 格、还刻意"4 格内不拉"，想着"别把人叠一起卡住"。
//     但那是**按"反远程卡位"设计**的，而真实需求是**反挖三填一** ——
//     玩家就在 1 格沟旁边站着，必须**把他拉到 boss 攻击范围内**才有用。
//     半径 2.6 格对幻念僧（近战 3 格）勉强够，**对妄念魔（近战 3 格但模型巨大）偏边缘**。
//     现在压到 1.6 格 ⇒ 一定落在任何阶段的近战范围内，**且不会互相叠在同一格**
//     （1.6 格半径上最多站 3～4 人，其余靠每人 45° 角错开）。
//   ★ 保留的护栏：**已经开始被拉的那一批**（`dragged` 名单）不会被重复拉第二遍，
//     否则读条一结束就把人又瞬移一次，看着像抽搐。
// ==================================================================
var BT_DRAG_CD      = 18000;   // 冷却 18 秒（三阶段共用同一套值）★ 内置 CD，**不改**
// ★★★ 2026-10-07：**锁链的「初始释放时机」单独拉长到 10 秒**
//   （原来它和本阶段的其他技能一样，都吃 `BT_FIRST_MS` = 4 秒 ⇒ **同刻就绪**）。
//   问题：一进新阶段，**锁链和该阶段的输出技能在同一个 4 秒点同时到点**，于是
//   二阶段变成「碎念执先把人拽到脸上 → 紧接樱花斩击 + 星环飞斧双重命中」，
//   玩家被拉过去的那一瞬间就吃了一整套，没有任何反应窗口。
//   现在锁链比本阶段的其他技能**晚 6 秒**才第一次出手（10 秒 vs 4 秒），彻底错开。
//   ⚠ 只改「初始释放时机」，**内置冷却 `BT_DRAG_CD`(18 秒) 保持原样**。
var BT_DRAG_FIRST_MS = 10000;  // ★ 锁链出场延迟：进入阶段后 **10 秒**才允许第一次拽人
var BT_DRAG_READ    = 20;      // 读条 20 刻 = 1 秒
var BT_DRAG_RANGE   = 64.0;    // ★ 64 格内全部锁（需求原文「64 格内的玩家」）
var BT_DRAG_RING    = 1.6;     // ★ 落点半径：贴到 boss 身边（保证进近战范围）
// ★ 2026-10-07：原本有个 `BT_DRAG_MIN = 4.0`「4 格内不拉」，**已按需求删除** ——
//   挖三填一的玩家就在 1～2 格内，这条规则恰好把唯一需要被拉的人放过了。
var BT_DRAG_HEIGHT  = 0.0;     // 落点相对 boss 脚下（0 = 站在 boss 同一地面）
var BT_DRAG_CAP     = 8;       // 单次最多拉几个（防止 20 个人叠成一坨卡服）
// 各阶段的"多远以外才放"门槛（**默认 0 = 不设门槛，64 格内一律拉**）。
// 想改成"只有玩家站远了才拽人"（例如觉得太黏人），把下面三个改成 12 / 10 / 14 即可。
var BT_DRAG_TRIG_S1 = 0.0;     // 幻念僧
var BT_DRAG_TRIG_S2 = 0.0;     // 碎念执
var BT_DRAG_TRIG_S3 = 0.0;     // 妄念魔

// ---------------- ③ 妄念魔（劫掠兽） ----------------
var BT_S3_NAME      = '\u00a7f\u5984\u5ff5\u9b54';   // §f妄念魔
// ★ 2026-10-06：1500 → 1200 → **1000**。
//   实测这台服务器的实体血量上限**卡在 1024**（后台日志：目标=1200 实际上限=1024），
//   所以 1200 写不进去 ⇒ 血量停在原版 100。1000 < 1024，安全。
//   （万一以后还想往上调，往上加之前先确认上限够不够 —— 脚本会打日志）
var BT_S3_HP        = 1000.0;
var BT_S3_DMG       = 40.0;    // 基础（普攻）伤害（2026-10-06：30 → 35 → 25 → **40**）
var BT_S3_HEIGHT    = 3;

var BT_S3_SLAM_DMG  = 150.0;   // 落地 **150 点**（2026-10-06：50 → 40 → **150**）
var BT_S3_SLAM_R    = 3.5;     // 范围 3.5 格
var BT_S3_SLAM_CD   = 11000;   // 冷却 **11 秒**（2026-10-07：20 → **11**，按需求改）
                             //   ※ 出场延迟仍是 BT_FIRST_MS(4 秒) —— 本次只动"冷却"，没动"初始释放时机"
var BT_S3_SLAM_CHARGE=20;      // 原地漂浮蓄力 20 刻 = 1 秒
var BT_S3_SLAM_HOVER= 20;      // 出现在头顶后悬停 20 刻 = 1 秒，然后落地
var BT_S3_SLAM_RISE = 1.0;     // 蓄力期间往上飘多少格
var BT_S3_SLAM_ABOVE= 3.0;     // 出现在玩家头顶**上方**几格
var BT_S3_SLAM_DROP = 8;       // 从悬停点落地用几刻

// ★ 2026-10-06：**原技能 2「龙息弹」已按需求删除**（妄念魔现在只有坠地一个技能）

// ---------------- 血量保护（2026-10-06 新增） ----------------
// 出生后这段时间里，每拍检查"当前血量是否远低于上限"，是就把血补到上限。
// 用途：服务端属性延迟生效时，血条不会一开局就是空的（详见 btApplyHp 注释）。
var BT_HP_FIX_MS    = 6000;   // 出生后 6 秒内**每拍**重试（原来只重试一次 ⇒ 属性晚生效就救不回来）

// ---------------- 转场演出 ----------------
var BT_TRANS1_MS    = 3000;    // 幻念僧死 → 羊驼口水烟雾 3 秒 → 碎念执
var BT_TRANS2_MS    = 3000;    // 碎念执死 → 灵魂沙柱 3 秒 → 妄念魔
var BT_SOUL_HEIGHT  = 22.0;    // 灵魂沙柱冲上去 22 格
var BT_TRANS_TICKS  = 60;      // 转场演出总共 60 刻（3 秒）

// ---------------- 阶段 BGM（2026-10-06 新增）----------------
//   每个阶段一首，**该阶段存活期间循环播放**；阶段死亡**立刻停**（下一阶段再起自己那首）。
//   下标 0/1/2 = 一/二/三阶段。曲目长度（= 循环间隔）：
//     Precipice 4:59 = 299s ｜ Creator 2:56 = 176s ｜ Creator(八音盒) 1:13 = 73s
var BT_MUSIC          = true;    // false = 完全不放 BGM
var BT_MUSIC_RANGE    = 64.0;    // boss 多少格内听得到（走远立刻停、走近从头播）
var BT_MUSIC_VOL      = 4.0;     // 音量（4.0 = 唱片机原版音量 ⇒ 衰减 16 × 4 ≈ 64 格）
var BT_MUSIC_SOUND    = ['music_disc.precipice',          // ① 幻念僧 · Aaron Cherof - Precipice
                         'music_disc.creator',            // ② 碎念执 · Lena Raine - Creator
                         'music_disc.creator_music_box']; // ③ 妄念魔 · Creator（八音盒）
var BT_MUSIC_LOOP_MS  = [299000, 176000, 73000];
var BT_MUSIC_FALLBACK = ['music_disc.relic', 'music_disc.pigstep', 'music_disc.ward'];

// ---------------- 掉落物 ----------------
//   ★ **妄想碎片**（`GS_WANGXIANG_FRAGMENT`，唱片碎片材质 · 基础构思分类）：
//     ★★★ 第八十五批：**只有第三阶段死掉才掉，且固定 6 个**
//       （用户原话：「妄想碎片也改成第三阶段，固定掉落6个」）。
//       改前是「**每一阶段死掉都掉**，数量 = 1 / 3 / 8（打完一整轮共 12 个）」
//       ⇒ 现在一阶段 / 二阶段**什么都不掉**，只有三阶段（妄念魔）掉 **6 个**。
//   ★ **精神力不再是 boss 掉落物**（2026-10-06 最终定案）：改为
//     唤梦机 **60 个妄想碎片 + 1 个残樱 → 1 个余念绝响**
//     （★★ 第八十五批：原来是 36 个 + 1 个入梦，入梦早就换成残樱、数量本批 36 → 60）。
var BT_FRAG_ID       = 'GS_WANGXIANG_FRAGMENT';
// 下标 = 阶段 - 1。★ 第八十五批：一阶段 0 个 / 二阶段 0 个 / **三阶段 6 个**
//   ★ 用 0 表示"这一阶段不掉"（btDropFragments 里 `n <= 0` 会直接 return）。
var BT_FRAG_DROP     = [0, 0, 6];

//   ★★★ 第八十六批：**残樱**（`GS_CHERRY`）—— 用户原话：
//     「妄念一阶段掉落物删除，二阶段换成固定掉落一把残樱，
//       删除残樱配方，改成击败二阶段妄念掉落」
//     ⇒ **只有第二阶段（碎念执）**死掉才掉 **固定 1 把**；
//       一阶段 / 三阶段**都不掉残樱**（三阶段掉的是妄想碎片）。
//     ★ 它的 9 格配方已从 `mb_machines.yml` 的构思机里删除，
//       `items.yml` 的 `recipe_type` 改成 `GS_RT_WANGNIAN_S2`（九宫格留空）。
var BT_CHERRY_ID     = 'GS_CHERRY';
// 下标 = 阶段 - 1。★ 第八十六批：一阶段 0 把 / **二阶段 1 把** / 三阶段 0 把
var BT_CHERRY_DROP   = [0, 1, 0];

// ---------------- 血条 ----------------
var BT_BAR          = true;
var BT_BAR_ONLY_OWNER = true;  // true = 只有召唤者看得到
var BT_BAR_RANGE    = 64.0;

// ---------------- 运行时状态 ----------------
var BT_ACTIVE   = {};   // 玩家 uuid -> ref（**一条线**：三个阶段共用同一个 ref）
var BT_LAST     = {};   // 玩家 uuid -> 上次召唤时间戳（防一次点击被派发两遍）

// PDC 标记
var BT_KEY      = null; // gs:boss3  → 值：'wangnian1' / 'wangnian2' / 'wangnian3'
var BT_FANG_KEY = null; // gs:boss3_fang
var BT_PDT      = null;
try {
    BT_PDT      = Java.type('org.bukkit.persistence.PersistentDataType');
    BT_KEY      = Java.type('org.bukkit.NamespacedKey').fromString('gs:boss3');
    BT_FANG_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:boss3_fang');
} catch (e) { BT_KEY = null; BT_FANG_KEY = null; BT_PDT = null; }

// ==================================================================
// 小工具
// ==================================================================
function btNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function btAlive(e) {
    try { if (e == null) return false; return !(e.isDead() || !e.isValid()); }
    catch (e2) { return false; }
}

function btTag(e) {
    try {
        if (e == null || BT_KEY == null || BT_PDT == null) return '';
        var v = e.getPersistentDataContainer().get(BT_KEY, BT_PDT.STRING);
        return (v == null ? '' : '' + v);
    } catch (err) { return ''; }
}

// 这是不是我们这条 boss 线上的某一只？
function btOurs(e) {
    try {
        if (BT_KEY == null || BT_PDT == null || e == null) return false;
        return e.getPersistentDataContainer().has(BT_KEY, BT_PDT.STRING);
    } catch (err) { return false; }
}

function btDist(a, b) {
    try { return a.getLocation().distance(b.getLocation()); } catch (e) { return 999.0; }
}

// 方块的通行判定（★ 用 isPassable：**玻璃挡得住**）
function btPassableAt(world, bx, by, bz) {
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

// (bx, by, bz) 往上 height 格够不够站
function btHeadroom(world, bx, bz, by, height) {
    try {
        for (var i = 0; i < height; i++) {
            if (!btPassableAt(world, bx, by + i, bz)) return false;
        }
        return true;
    } catch (e) { return false; }
}

// 找一个能站的落地高度（先往下找实地，找不到就往上抬）
function btSafeY(world, x, z, y0, height) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z);
        var start = Math.floor(y0);
        var lo = start - 4, hi = start + 8;
        if (lo < -60) lo = -60;
        if (hi > 250) hi = 250;
        var y;
        for (y = start; y >= lo; y--) {
            if (btHeadroom(world, bx, bz, y, height) && !btPassableAt(world, bx, y - 1, bz)) return y;
        }
        for (y = start; y <= hi; y++) {
            if (btHeadroom(world, bx, bz, y, height)) return y;
        }
        return y0;
    } catch (e) { return y0; }
}

// 粒子名解析（多个候选，逐个试，老版本没有就退下一个）
function btP(cands) {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < cands.length; i++) {
            try {
                var p = P[cands[i]];
                if (p != null) return p;
            } catch (e) { }
            try {
                var p2 = P.valueOf(cands[i]);
                if (p2 != null) return p2;
            } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// 在某个点炸 particles
function btFx(world, pt, x, y, z, n, dx, dy, dz, speed) {
    try {
        if (world == null || pt == null) return;
        world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
    } catch (e) { }
}

function btSound(world, loc, name, vol, pitch) {
    try {
        try { world.playSound(loc, name, vol, pitch); }
        catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); } catch (e2) { } }
    } catch (e) { }
}

// 实体类型解析
function btET(names) {
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
function btAttrKey(names) {
    try {
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        for (var i = 0; i < names.length; i++) {
            try { var k = Attr[names[i]]; if (k != null) return k; } catch (e) { }
            try { var k2 = Attr.valueOf(names[i]); if (k2 != null) return k2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★★ 专门找 **MAX_HEALTH**，名字怎么改都能找到（2026-10-06 加固）：
//   `GENERIC_MAX_HEALTH`（≤1.21.2）→ `MAX_HEALTH`（1.21.3+），中间好几个版本两套名字
//   并存/互斥，光按名字试有可能**两个都取不到** ⇒ 再遍历 `Attribute.values()` 用
//   `name()` / `getKey().getKey()` 里的 `MAX_HEALTH` / `max_health` 模糊匹配兜底。
//   找不到就返回 null（之后每拍还会再找，不做"失败即永久缓存"）。
var BT_MAXATTR = null;
function btFindMaxAttr() {
    try {
        if (BT_MAXATTR != null) return BT_MAXATTR;
        var k = btAttrKey(['MAX_HEALTH', 'GENERIC_MAX_HEALTH']);
        if (k != null) { BT_MAXATTR = k; return k; }
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var all = Attr.values();
        for (var i = 0; i < all.length; i++) {
            var a = all[i];
            var nm = '', key = '';
            try { nm = '' + a.name(); } catch (e) { nm = ''; }
            try { key = '' + a.getKey().getKey(); } catch (e) { key = ''; }
            if (nm.indexOf('MAX_HEALTH') >= 0 || key.indexOf('max_health') >= 0) {
                BT_MAXATTR = a;
                return a;
            }
        }
    } catch (e) { }
    return null;
}

function btSetBase(ent, names, v) {
    try {
        var k = btAttrKey(names);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        inst.setBaseValue(v);
        return true;
    } catch (e) { return false; }
}

// ★★ 强设血量上限（2026-10-06 加固）—— 三条路一起上，任何一条通了就行：
//   ① 删掉 MAX_HEALTH 上可能把上限压住的 AttributeModifier（先快照再删，别边遍历边删）
//   ② 属性 baseValue 直接写（走 btFindMaxAttr，名字改过也找得到）
//   ③ 旧 API `LivingEntity#setMaxHealth()`（内部同样写属性，很多分支正好靠它）
function btForceMax(ent, hp) {
    var ok = false;
    try {
        var k = btFindMaxAttr();
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

// ★ 把**最终伤害**（含手里武器的加成）精确设成 want 点：
//   先读出当前实际值 cur（cur 已经把装备加成算进去了），
//   再把 base 往上 / 往下补差值 ⇒ 不管哪个版本、手里拿什么武器，打出来都是 want。
function btSetDmg(ent, want) {
    try {
        var k = btAttrKey(['ATTACK_DAMAGE', 'GENERIC_ATTACK_DAMAGE']);
        if (k == null) return false;
        var inst = ent.getAttribute(k);
        if (inst == null) return false;
        var cur = inst.getValue();
        var base = inst.getBaseValue();
        inst.setBaseValue(base + (want - cur));
        return true;
    } catch (e) { return false; }
}

function btMaxHp(ent) {
    try {
        var k = btFindMaxAttr();
        if (k == null) return 0;
        var inst = ent.getAttribute(k);
        if (inst == null) return 0;
        return inst.getValue();
    } catch (e) { return 0; }
}

function btHurt(src, ent, dmg) {
    try {
        try { ent.setNoDamageTicks(0); } catch (e) { }
        try { ent.damage(dmg, src); }
        catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
    } catch (e) { }
}

function btKb(ent, vx, vy, vz) {
    try {
        var Vector = Java.type('org.bukkit.util.Vector');
        ent.setVelocity(new Vector(vx, vy, vz));
    } catch (e) { }
}

// ==================================================================
// ★ 破盾（2026-10-06 重做）—— **完全照原版"斧头砍盾牌"的规则来**：
//   ① `p.setCooldown(Material.SHIELD, BT_S2_SLASH_SHIELD_CD)` ⇒ **5 秒内举不起盾**
//      （这就是原版斧头破盾的核心效果，全版本通用）
//   ② 盾牌**掉耐久**：按原版口径 = `1 + 向下取整(伤害)` 点，**不再一击压到极限**
//      （旧写法 `meta.setDamage(max)` 会把它顶到只剩 1 点，太狠了）
//      ★ 耐久真掉光了 ⇒ 按原版规则**直接碎掉**（从对应槽位清掉 + 破碎音效）
// ==================================================================
function btBreakShield(p, dmg) {
    try {
        if (p == null) return false;
        var inv = p.getInventory();
        if (inv == null) return false;

        var shield = null, slotMain = false;
        try {
            var m = inv.getItemInMainHand();
            if (m != null && ('' + m.getType().name()) === 'SHIELD') { shield = m; slotMain = true; }
        } catch (e) { }
        if (shield == null) {
            try {
                var o = inv.getItemInOffHand();
                if (o != null && ('' + o.getType().name()) === 'SHIELD') shield = o;
            } catch (e) { }
        }
        if (shield == null) return false;

        // ① 物品冷却 ⇒ BT_S2_SLASH_SHIELD_CD 刻内举不起盾（原版斧头同款，最可靠）
        try {
            var Mat = Java.type('org.bukkit.Material');
            var sm = null;
            try { sm = Mat.SHIELD; } catch (e) { sm = null; }
            if (sm == null) { try { sm = Mat.valueOf('SHIELD'); } catch (e) { sm = null; } }
            if (sm != null) p.setCooldown(sm, BT_S2_SLASH_SHIELD_CD);
        } catch (e) { }

        // ② 耐久：按原版口径扣（1 + floor(伤害)），扣光了就碎
        var broke = false;
        try {
            var meta = shield.getItemMeta();
            if (meta != null) {
                var dmgApi = Java.type('org.bukkit.inventory.meta.Damageable');
                if (dmgApi != null && meta instanceof dmgApi) {
                    var max = 336;
                    try { max = shield.getType().getMaxDurability(); } catch (e) { max = 336; }
                    if (max <= 0) max = 336;
                    var loss = 1 + Math.floor(dmg == null || dmg < 0 ? 1 : dmg);   // ★ 原版：1 + 向下取整(伤害)
                    var cur = 0;
                    try { cur = meta.getDamage(); } catch (e) { cur = 0; }
                    var nd = cur + loss;
                    if (nd >= max) {
                        broke = true;                       // ★ 耐久耗尽 ⇒ 原版规则：直接碎掉
                    } else {
                        meta.setDamage(nd);
                        shield.setItemMeta(meta);
                        if (slotMain) { try { inv.setItemInMainHand(shield); } catch (e) { } }
                        else { try { inv.setItemInOffHand(shield); } catch (e) { } }
                    }
                }
            }
        } catch (e) { }

        if (broke) {
            if (slotMain) { try { inv.setItemInMainHand(null); } catch (e) { } }
            else { try { inv.setItemInOffHand(null); } catch (e) { } }
            try { p.playSound(p.getLocation(), 'item.shield.break', 1.0, 1.0); } catch (e) { }
        } else {
            try { p.stopSound('item.shield.block'); } catch (e) { }
            try { p.playSound(p.getLocation(), 'item.shield.break', 0.7, 1.2); } catch (e) { }
        }
        return true;
    } catch (e) { return false; }
}

// ==================================================================
// BossBar（血条）
// ==================================================================
function btBarMake(title) {
    if (!BT_BAR) return null;
    try {
        var BarColor = Java.type('org.bukkit.boss.BarColor');
        var BarStyle = Java.type('org.bukkit.boss.BarStyle');
        var c = null, s = null;
        try { c = BarColor.RED; } catch (e) { c = null; }
        if (c == null) { try { c = BarColor.valueOf('RED'); } catch (e2) { } }
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

function btBarSync(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        var ent = ref.ent;
        var alive = btAlive(ent);

        var prog = 0.0;
        if (alive) {
            try {
                var hp = ent.getHealth();
                var mx = btMaxHp(ent);
                if (mx <= 0) mx = ref.maxHp;
                prog = hp / (mx > 0 ? mx : 1);
            } catch (e) { prog = 0.0; }
        }
        if (prog < 0) prog = 0; if (prog > 1) prog = 1;
        try { ref.bar.setProgress(prog); } catch (e) { }
        try { ref.bar.setVisible(alive); } catch (e) { }

        // 观看者：**只给召唤者看**
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
                    if (BT_BAR_ONLY_OWNER) {
                        try { if ('' + p.getUniqueId() !== '' + ref.owner) continue; } catch (e) { continue; }
                    }
                    try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                    var pl = null;
                    try { pl = p.getLocation(); } catch (e) { continue; }
                    if (pl.distance(el) > BT_BAR_RANGE) continue;
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

function btBarDrop(ref) {
    try {
        if (ref == null || ref.bar == null) return;
        try { ref.bar.setVisible(false); } catch (e) { }
        try { ref.bar.removeAll(); } catch (e) { }
        try { for (var k in ref.barNear) { delete ref.barNear[k]; } } catch (e) { }
        ref.bar = null;
    } catch (e) { }
}

// ==================================================================
// ★ 阶段 BGM（2026-10-06 新增）—— 每个阶段一首，该阶段存活期间 **循环播放**，
//   阶段死亡 **立刻停**（下一阶段再起自己那首）。
// ==================================================================
//   · 范围：只有 boss `BT_MUSIC_RANGE` 格内的玩家听得到（走出范围立刻停，走进从头播）
//   · 循环：每"曲目长度"重放一次 ⇒ 无缝循环
//   · 服务器没这张唱片 ⇒ 自动退到 BT_MUSIC_FALLBACK，再退 relic / 11
//   · 阶段死亡（btPhaseEnd）/ 收摊（btCleanup）⇒ `btMusicStopAll` 给所有听的人 stopSound
// ------------------------------------------------------------------
var BT_MUSIC_PICKED = [null, null, null];   // 每阶段"本服真的有"的音效名（缓存）

function btMusicSound(stage) {
    var i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
    try {
        if (BT_MUSIC_PICKED[i] != null) return BT_MUSIC_PICKED[i];
        var cands = [BT_MUSIC_SOUND[i], BT_MUSIC_FALLBACK[i], 'music_disc.relic', 'music_disc.11'];
        try {
            var Sound = Java.type('org.bukkit.Sound');
            for (var j = 0; j < cands.length; j++) {
                var nm = 'MUSIC_DISC_' + ('' + cands[j]).replace('music_disc.', '').toUpperCase();
                try {
                    if (Sound[nm] != null) { BT_MUSIC_PICKED[i] = cands[j]; return BT_MUSIC_PICKED[i]; }
                } catch (e) { }
            }
        } catch (e) { }
        BT_MUSIC_PICKED[i] = BT_MUSIC_SOUND[i];
        return BT_MUSIC_PICKED[i];
    } catch (e) { return BT_MUSIC_SOUND[i]; }
}

function btMusicEnum(stage) {
    try {
        var Sound = Java.type('org.bukkit.Sound');
        var nm = 'MUSIC_DISC_' + ('' + btMusicSound(stage)).replace('music_disc.', '').toUpperCase();
        var v = Sound[nm];
        return (v == null ? null : v);
    } catch (e) { return null; }
}

function btMusicPlayTo(p, loc, stage) {
    try {
        if (p == null || loc == null) return false;
        var name = btMusicSound(stage);
        try { p.playSound(loc, name, BT_MUSIC_VOL, 1.0); return true; } catch (e) { }
        var en = btMusicEnum(stage);
        if (en != null) { try { p.playSound(loc, en, BT_MUSIC_VOL, 1.0); return true; } catch (e2) { } }
        try { loc.getWorld().playSound(p, name, BT_MUSIC_VOL, 1.0); return true; } catch (e3) { }
        return false;
    } catch (e) { return false; }
}

function btMusicStopTo(p, stage) {
    try {
        if (p == null) return;
        var name = btMusicSound(stage);
        try { p.stopSound(name); return; } catch (e) { }
        var en = btMusicEnum(stage);
        if (en != null) { try { p.stopSound(en); return; } catch (e2) { } }
        // 最后兜底：停掉整个"唱片"音效分类（免得旧曲子还在响）
        try {
            var SC = Java.type('org.bukkit.SoundCategory');
            var c = null;
            try { c = SC.RECORDS; } catch (e3) { c = null; }
            if (c == null) { try { c = SC.valueOf('RECORDS'); } catch (e4) { c = null; } }
            if (c != null) { try { p.stopSound(c); } catch (e5) { } }
        } catch (e) { }
    } catch (e) { }
}

// 每秒扫一次：范围内的人没听过 / 到点了 ⇒ 重放（循环）；走出范围 / 下线 ⇒ 立刻停
function btMusicTick(ref) {
    try {
        if (!BT_MUSIC) return;
        if (ref == null || ref.done || !btAlive(ref.ent)) return;
        if (ref.music == null) ref.music = {};

        var stage = ref.stage;
        if (stage == null || stage < 1) stage = 1;
        var now = btNow();
        var bl = null, wname = '';
        try { bl = ref.ent.getLocation(); wname = '' + bl.getWorld().getName(); } catch (e) { }

        var want = {};
        if (bl != null) {
            var it = getServer().getOnlinePlayers().iterator();
            while (it.hasNext()) {
                var p = it.next();
                if (p == null) continue;
                try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
                var pl = null;
                try { pl = p.getLocation(); } catch (e) { continue; }
                if (pl.distance(bl) > BT_MUSIC_RANGE) continue;
                try { want['' + p.getUniqueId()] = p; } catch (e) { }
            }
        }

        var loopMs = BT_MUSIC_LOOP_MS[stage - 1];
        if (loopMs == null || loopMs <= 0) loopMs = 299000;

        for (var k in want) {
            var rec = ref.music[k];
            // 刚走进范围 / 阶段换了 / 已经听满一整首 ⇒ 重放
            if (rec == null || rec.stage !== stage || (now - rec.at) >= loopMs) {
                var okp = false;
                try { okp = btMusicPlayTo(want[k], bl, stage); } catch (e) { okp = false; }
                ref.music[k] = { p: want[k], at: now, stage: stage };
                if (!okp && rec == null) {
                    try { getServer().getLogger().info('[构思技艺] 妄念：本服没有音效 ' + btMusicSound(stage) + '，该阶段 BGM 不播放'); } catch (e) { }
                }
            }
        }
        // 走出范围 / 下线 ⇒ 立刻停
        for (var k2 in ref.music) {
            if (want[k2] == null) {
                try { btMusicStopTo(ref.music[k2].p, ref.music[k2].stage); } catch (e) { }
                try { delete ref.music[k2]; } catch (e) { }
            }
        }
    } catch (e) { }
}

// 阶段死亡 / 收摊：把所有还在听的人的音乐 **立刻停掉**
function btMusicStopAll(ref) {
    try {
        if (ref == null || ref.music == null) return;
        for (var k in ref.music) {
            try { btMusicStopTo(ref.music[k].p, ref.music[k].stage); } catch (e) { }
            try { delete ref.music[k]; } catch (e) { }
        }
        ref.music = {};
    } catch (e) { }
}

// ★ BGM 主循环：每 20 刻（1 秒）跑一次。
//   用 `musGen` 世代号防止"上一阶段的循环还挂在 runLater 里"和新的循环同时跑。
function btMusicLoop(ref) {
    try {
        if (!BT_MUSIC) return;
        if (ref == null || ref.done) return;
        ref.musGen = (ref.musGen == null ? 0 : ref.musGen) + 1;
        var gen = ref.musGen;
        function step() {
            try {
                if (ref == null || ref.done) return;
                if (ref.musGen !== gen) return;                 // 已经换代 ⇒ 这条链作废
                if (!btAlive(ref.ent)) { btMusicStopAll(ref); return; }
                btMusicTick(ref);
                runLater(function () { step(); }, 20);
            } catch (e) { }
        }
        try { btMusicTick(ref); } catch (e) { }
        runLater(function () { step(); }, 20);
    } catch (e) { }
}

// ==================================================================
// ★ 掉落物（2026-10-06 · ★★★ 第八十五 / 八十六批改）
//   · ★★★ **只有第三阶段死掉才掉**「**妄想碎片**」= **固定 6 个**
//     （`btDropFragments`；数量表 BT_FRAG_DROP = [0, 0, 6]）
//     用户原话：「妄想碎片也改成第三阶段，固定掉落6个」
//   · ★★★ **只有第二阶段死掉才掉**「**残樱**」= **固定 1 把**
//     （`btDropCherry`；数量表 BT_CHERRY_DROP = [0, 1, 0]）
//     用户原话：「妄念一阶段掉落物删除，二阶段换成固定掉落一把残樱，
//               删除残樱配方，改成击败二阶段妄念掉落」
//   · **精神力不再掉落** —— 改为唤梦机：60 个妄想碎片 + 1 个残樱 → 1 个余念绝响
//   掉落点用 ref 里**每拍更新**的 lw/lx/ly/lz（实体死亡瞬间取不到位置）
// ==================================================================
function btDropOne(ref, id, amount) {
    try {
        if (ref == null || id == null) return false;
        var world = ref.lw, x = ref.lx, y = ref.ly, z = ref.lz;
        if (world == null || x == null) return false;

        var stack = null;
        try {
            var sf = getSfItemById(id);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }
        if (stack == null) {
            try { getServer().getLogger().warning('[构思技艺] 妄念：取不到 ' + id + '，未掉落'); } catch (e) { }
            return false;
        }
        try { stack.setAmount(amount == null || amount <= 0 ? 1 : amount); } catch (e) { }

        var Loc = Java.type('org.bukkit.Location');
        world.dropItemNaturally(new Loc(world, x, (y == null ? 0 : y) + 0.5, z), stack);
        return true;
    } catch (e) { return false; }
}

// ★ 妄想碎片：★★★ 第八十五批起 **只有第三阶段掉，固定 6 个**（一/二阶段不掉）
//   数量表见上面 BT_FRAG_DROP = [0, 0, 6]
function btDropFragments(ref, stage) {
    try {
        var i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
        var n = BT_FRAG_DROP[i];
        if (n == null || n <= 0) return;
        if (!btDropOne(ref, BT_FRAG_ID, n)) return;
        try {
            var Loc = Java.type('org.bukkit.Location');
            ref.lw.playSound(new Loc(ref.lw, ref.lx, ref.ly, ref.lz), 'entity.item.pickup', 0.8, 1.2);
        } catch (e) { }
    } catch (e) { }
}

// ★ 残樱：★★★ 第八十六批起 **只有第二阶段掉，固定 1 把**（一 / 三阶段不掉）
//   数量表见上面 BT_CHERRY_DROP = [0, 1, 0]
function btDropCherry(ref, stage) {
    try {
        var i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
        var n = BT_CHERRY_DROP[i];
        if (n == null || n <= 0) return;
        if (!btDropOne(ref, BT_CHERRY_ID, n)) return;
        try {
            var Loc = Java.type('org.bukkit.Location');
            ref.lw.playSound(new Loc(ref.lw, ref.lx, ref.ly, ref.lz), 'entity.item.pickup', 0.8, 1.2);
        } catch (e) { }
    } catch (e) { }
}

// ★ 2026-10-06：原来这里的「三阶段全清再掉 1 个精神力」**已按需求删除** ——
//   精神力改为唤梦机（60 个妄想碎片 + 1 个残樱 → 1 个余念绝响），boss 只掉妄想碎片。

// ★ 2026-10-06：原来使用时会播的「不死图腾动画」**已按需求删除**（`btTotemAnim` 整个移除）。

// ==================================================================
// 生成 boss
// ==================================================================
// ==================================================================
// ★★ 血量落地（2026-10-06 二次加固）—— 「劫掠兽只有 100 血」的根治
//   症状：召唤出来**上限还是原版值（劫掠兽 100）**，血条几乎全空、几下就被打死。
//   根因：`setHealth(hp)` 会把 **超过"当时"上限的部分直接截掉**。老写法是
//         「属性写一次 + setHealth 一次」，只要那一次属性没生效（名字对不上 /
//          服务端延迟 / 被修改器压住）⇒ setHealth(1200) 被截成 100，之后再没人补。
//
//   现在的顺序（**先确认上限、再动血量**，任一环节不通都不会去调 setHealth）：
//     ① `btForceMax()`：删修改器 + 属性 baseValue（走 btFindMaxAttr，名字改过也找得到）
//        + `setMaxHealth()` 兜底
//     ② **回读** `btMaxHp()`：`< 目标 - 0.5` ⇒ 判定失败，**不调 setHealth**，直接重试
//     ③ 上限确认到位后才 `setHealth(hp)`，再回读一次
//     ④ 失败 ⇒ 延迟重试 0/1/3/6/10/20/40 刻；**并且每拍重试**（见 btTick 的 hpOk 段）
//   全都失败 ⇒ 往后台打一行带"目标值 / 实际上限 / 实际血量 / 属性键"的日志，方便定位。
// ==================================================================
var BT_HP_DIAG = {};   // 每个实体只打一次失败日志，别刷屏

function btHpDiag(ent, want, max, cur, why) {
    try {
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { id = 'unknown'; }
        if (BT_HP_DIAG[id] != null) return;
        BT_HP_DIAG[id] = 1;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { tn = '?'; }
        var kname = 'null';
        try { var k = btFindMaxAttr(); if (k != null) kname = '' + k.name(); } catch (e) { }
        try {
            getServer().getLogger().warning('[构思技艺] 妄念 血量设置失败(' + why + ')：实体='
                + tn + ' 目标=' + want + ' 实际上限=' + max + ' 实际血量=' + cur
                + ' MAX_HEALTH键=' + kname);
        } catch (e2) { }
    } catch (e) { }
}

function btApplyHpOnce(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return false;
        // ① 强设上限（删修改器 + 属性 + setMaxHealth 兜底）
        btForceMax(ent, hp);
        // ② ★ 先确认上限到位 —— 没到位就别 setHealth（那一定会被截成原版值）
        var mx = btMaxHp(ent);
        if (mx < hp - 0.5) {
            btHpDiag(ent, hp, mx, -1, '上限没上去');
            return false;
        }
        // ③ 上限对了，再回满血
        try { ent.setHealth(hp); } catch (e) { }
        var now = -1;
        try { now = ent.getHealth(); } catch (e) { now = -1; }
        if (now >= hp - 0.5) return true;
        btHpDiag(ent, hp, mx, now, '血量没回满');
        return false;
    } catch (e) { return false; }
}

function btApplyHp(ent, hp) {
    try {
        if (ent == null || hp == null || hp <= 0) return;
        if (btApplyHpOnce(ent, hp)) return;
        // 没成功 ⇒ 延迟重试（越往后间隔越大，别刷爆主线程）
        var delays = [1, 3, 6, 10, 20, 40];
        for (var i = 0; i < delays.length; i++) {
            runLater(function (e0, h0) {
                return function () {
                    try { if (!btAlive(e0)) return; btApplyHpOnce(e0, h0); } catch (e) { }
                };
            }(ent, hp), delays[i]);
        }
    } catch (e) { }
}

// ★★ 从实体反查它属于哪条"线"（降级伤害缩放要用）
function btRefOf(ent) {
    try {
        if (ent == null) return null;
        var id = '' + ent.getUniqueId();
        for (var k in BT_ACTIVE) {
            var r = BT_ACTIVE[k];
            if (r == null) continue;
            try {
                if (r.ent != null && ('' + r.ent.getUniqueId()) === id) return r;
            } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function btSpawnEntity(world, loc, typeNames, name, hp, dmg, tagValue) {
    try {
        var t = btET(typeNames);
        if (t == null) {
            try { getServer().getLogger().warning('[构思技艺] 妄念：找不到实体类型 ' + typeNames[0] + '（版本不支持？）'); } catch (e) { }
            return null;
        }
        var e = world.spawnEntity(loc, t);
        if (e == null) return null;

        // ★ 血量：走 btApplyHp（多次写入 + 延迟重试），不再是一次 setHealth
        btApplyHp(e, hp);
        if (dmg != null && dmg >= 0) btSetDmg(e, dmg);

        try { e.setCustomName(name); } catch (err) { }
        try { e.setCustomNameVisible(true); } catch (err) { }
        try { e.setRemoveWhenFarAway(false); } catch (err) { }
        try { e.setPersistent(true); } catch (err) { }
        try { e.setCanPickupItems(false); } catch (err) { }
        try { e.setAware(true); } catch (err) { }

        // 不掉东西
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) e.setLootTable(LootTables.EMPTY);
        } catch (err) { }
        try {
            var eq = e.getEquipment();
            if (eq != null) { try { eq.clear(); } catch (err2) { } }
        } catch (err) { }

        if (BT_KEY != null && BT_PDT != null && tagValue != null) {
            try { e.getPersistentDataContainer().set(BT_KEY, BT_PDT.STRING, tagValue); } catch (err) { }
        }
        return e;
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 妄念：生成 boss 出错 ' + err); } catch (e2) { }
        return null;
    }
}

// ==================================================================
// 目标选择
// ==================================================================
function btTarget(ref) {
    try {
        var ent = ref.ent;
        if (!btAlive(ent)) return null;
        var world = ent.getWorld();
        var wname = '' + world.getName();
        var el = ent.getLocation();

        var best = null, bestD = 1e9;         // 非创造 / 非旁观
        var fall = null, fallD = 1e9;         // 创造模式（兜底）
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if ('' + p.getWorld().getName() !== wname) continue; } catch (e) { continue; }
            var creative = false, spec = false;
            try {
                var gm = '' + p.getGameMode().name();
                if (gm.indexOf('SPECTATOR') >= 0) spec = true;
                if (gm.indexOf('CREATIVE')  >= 0) creative = true;
            } catch (e) { }
            if (spec) continue;
            var pl = null;
            try { pl = p.getLocation(); } catch (e) { continue; }
            var d = pl.distance(el);
            if (d > BT_SEEK) continue;
            if (creative) { if (d < fallD) { fallD = d; fall = p; } }
            else { if (d < bestD) { bestD = d; best = p; } }
        }
        if (best != null) return best;
        if (BT_CREATIVE_FALLBACK) return fall;
        return null;
    } catch (e) { return null; }
}

// ==================================================================
// 主心跳
// ==================================================================
function btTick(ref) {
    try {
        if (ref == null || ref.done) return;

        // ① 当前这只没了 → 走转场（或整条线收摊）
        if (!btAlive(ref.ent)) {
            btPhaseEnd(ref);
            return;
        }

        // 记住位置（死的时候要用它的死亡位置召唤下一阶段）
        try {
            var l0 = ref.ent.getLocation();
            ref.lw = l0.getWorld();
            ref.lx = l0.getX(); ref.ly = l0.getY(); ref.lz = l0.getZ();
        } catch (e) { }

        // ② 血条
        try { btBarSync(ref); } catch (e) { }

        // ②a ★★ 血量保护（2026-10-06 加固）：出生后 BT_HP_FIX_MS 窗口内，
        //     **每拍**重试一次"强设上限 + 回满血"，直到血量确认正常（打 hpOk 后彻底撒手）。
        //     撒手条件：上限到位 **且** 血量不低于目标的 50% —— 这样绝不会变成"边挨打边回血"。
        //     ★★ 上限万一**死活上不去**（服务端限制 / 别的插件压着）⇒ 立刻启用"伤害缩放"降级：
        //        `hpScale = 实际上限 / 目标上限`，之后它受到的伤害按这个比例缩小
        //        ⇒ **等效血量照样是目标值**（1200 血的效果一分不少），只是血条按真实比例走。
        try {
            var fx = ref.hpFixUntil;
            if (!ref.hpOk && fx != null && btNow() <= fx) {
                var mx0 = btMaxHp(ref.ent);
                var cur0 = -1;
                try { cur0 = ref.ent.getHealth(); } catch (e) { cur0 = -1; }
                var maxBad = (mx0 < ref.maxHp - 0.5);
                var hpBad = (cur0 >= 0 && cur0 < ref.maxHp * 0.5);   // 明显卡在原版血量
                if (maxBad || hpBad) {
                    if (btApplyHpOnce(ref.ent, ref.maxHp)) {
                        ref.hpOk = true;
                        ref.hpScale = 1.0;
                    } else if (maxBad && mx0 > 0) {
                        ref.hpScale = mx0 / ref.maxHp;   // 降级：等效加血
                    }
                } else {
                    ref.hpOk = true;
                    ref.hpScale = 1.0;
                }
            }
        } catch (e) { }

        // ②b 掐掉唤魔者的**原版技能**（兜底，防止 EntityCastSpellEvent 那条没注册成功）
        try { btPurgeVanilla(ref); } catch (e) { }

        // ②c ★ 妄想别去咬自家卫道士（EntityTargetEvent 的兜底：万一那条没注册成功，
        //     这里每拍直接把「指向自己人」的目标清掉）
        try { if (ref.stage === 2) btS2VexLoyal(ref); } catch (e) { }

        // ③ 正在演出（瞬影 / 蓄力 / 悬停…）⇒ 本拍不放新技能
        if (ref.mode != null && ref.mode !== 'idle') {
            runLater(function () { btTick(ref); }, BT_TICK);
            return;
        }

        var now = btNow();
        if (now < ref.readyAt) {                      // 出场保护期
            runLater(function () { btTick(ref); }, BT_TICK);
            return;
        }

        var tgt = btTarget(ref);
        if (tgt != null) {
            var d = btDist(ref.ent, tgt);
            var started = false;      // ★ 同一拍只允许开一个技能（免得两条演出叠在一起）

            if (ref.stage === 1) {
                // ★ 本体射击：**每 1.5 秒一发**（不吃 started 标志 —— 它是瞬发，不是演出）
                if (d <= BT_S1_ATK_RANGE && (now - (ref.atkAt == null ? 0 : ref.atkAt)) >= BT_S1_ATK_CD) {
                    ref.atkAt = now;
                    btS1Shoot(ref, tgt);
                }
                // 技能 1：追踪鳄鱼嘴（15 点 / 12 格 / 10 秒）
                if (!started && d <= BT_S1_FANG_RANGE + 2.0 && (now - ref.cd1) >= BT_S1_FANG_CD) {
                    ref.cd1 = now; started = true;
                    btS1Fang(ref, tgt);
                }
                // 技能 2：瞬影 + 回 20 血（20 秒）
                if (!started && d <= BT_SEEK && (now - ref.cd2) >= BT_S1_BLINK_CD) {
                    ref.cd2 = now; started = true;
                    btS1Blink(ref, tgt);
                }
            } else if (ref.stage === 2) {
                // 技能 1：竖斩（35 点 / 4 格 / 破盾 / 樱花瓣 / 10 秒）
                if (!started && d <= BT_S2_SLASH_RANGE + 1.0 && (now - ref.cd1) >= BT_S2_SLASH_CD) {
                    ref.cd1 = now; started = true;
                    btS2Slash(ref, tgt);
                }
                // ★ 技能 2（2026-10-06 新增）：星环飞斧
                //   —— 星环蓄力 1.5 秒 → 飞出斧子（10 格 / 35 点 / 直线不穿墙 / 撞到就停）
                //      → 斧头停下处生一只妄想（7 伤害 / 25 血 / 9 秒后处死 / 与卫道士同队）
                //   ⚠ 出场保护期（BT_FIRST_MS）内 cd3 还没走完 ⇒ **不会一召唤就甩斧子**
                if (!started && d <= BT_S2_AXE_TRIGGER && (now - ref.cd3) >= BT_S2_AXE_CD) {
                    ref.cd3 = now; started = true;
                    btS2Axe(ref, tgt);
                }
            } else if (ref.stage === 3) {
                // 技能 1：坠地（150 点 / 3.5 格 / **11 秒**）
                // ★ 2026-10-06：技能 2「龙息弹」已删除，妄念魔只剩这一个技能
                if (!started && d <= BT_SEEK && (now - ref.cd1) >= BT_S3_SLAM_CD) {
                    ref.cd1 = now; started = true;
                    btS3Slam(ref, tgt);
                }
            }

            // ★★★ 通用反卡位技能「锁链拽回」—— **三个阶段都有**
            //   治的是**「挖三填一」**：玩家挖 1 格深的沟把 boss 卡住，
            //   站在沟沿上安全输出 —— **boss 打不到人、人却打得到 boss**。
            //   读条 1 秒（画锁链）后，把 **64 格内**的玩家**强制传送**到 boss 脚下。
            //
            //   ⚠⚠ 触发条件**不看距离**（`BT_DRAG_TRIG_*` 全部设成 0）：卡位玩家离 boss
            //     只有 1～2 格，早期版本"有人在射程外才放"的判定对他**永远不成立** ⇒
            //     技能一次都不会触发。改成"**还有没被这一轮拉过的人**"就一定放。
            //   ⚠ 不吃 `started`（它放在最后判）："拽人"必须保证每阶段每 18 秒
            //     至少能出一次手，否则玩家能一直卡着。
            var dragTrig = 0;
            if (ref.stage === 1) dragTrig = BT_DRAG_TRIG_S1;
            else if (ref.stage === 2) dragTrig = BT_DRAG_TRIG_S2;
            else if (ref.stage === 3) dragTrig = BT_DRAG_TRIG_S3;
            // ★★ `dragged`（本轮已拉名单）**在 CD 好了的那一刻清空** ——
            //   不清的话：第一次把所有人拉完 → 全进名单 → 之后 18 秒一到
            //   `btShouldDrag` 永远返回 false ⇒ **这个技能一辈子只放一次**。
            //   清空后语义变成"**每 18 秒是一个新的一轮，轮内每个人都拉一次**"。
            if ((now - ref.cd4) >= BT_DRAG_CD) {
                ref.dragged = {};
                if (btShouldDrag(ref, dragTrig)) {
                    ref.cd4 = now;
                    btDragStart(ref);
                }
            }
        }

        runLater(function () { btTick(ref); }, BT_TICK);
    } catch (e) {
        try {
            if (ref != null && btAlive(ref.ent)) runLater(function () { btTick(ref); }, BT_TICK);
            else btCleanup(ref);
        } catch (e2) { }
    }
}

// ★ Phase 1（掠夺者）**双手清空 + 本体射击**（2026-10-06 重做）
//   ★ 原版掠夺者一出生手里就自带**弩**，而弩的装填节奏（≈1.9 秒）又慢又不受控
//     ⇒ 现在把弩**拿掉**，改成脚本每 `BT_S1_ATK_CD`(1500) 毫秒朝目标射一支箭。
//   · `btS1Hands()`：每拍把主手 / 副手清空（被别的插件塞了东西也会被清掉）
//   · `btS1Shoot()`：本体发射 —— `World#spawnArrow` 造一支箭并 `setShooter(boss)`，
//     箭的伤害由伤害事件按 shooter 统一改写成 `BT_S1_ARROW_DMG`(12 点)
function btS1Hands(ent) {
    try {
        if (ent == null || !btAlive(ent)) return;
        var eq = ent.getEquipment();
        if (eq == null) return;
        var Mat = Java.type('org.bukkit.Material');
        var ItemStack0 = Java.type('org.bukkit.inventory.ItemStack');
        var AIR = Mat.AIR;
        var changed = false;
        try {
            var mh = eq.getItemInMainHand();
            var mhn = (mh == null ? '' : '' + mh.getType().name());
            if (mhn !== 'AIR') { eq.setItemInMainHand(new ItemStack0(AIR)); changed = true; }
        } catch (e) { }
        try {
            var oh = eq.getItemInOffHand();
            var ohn = (oh == null ? '' : '' + oh.getType().name());
            if (ohn !== 'AIR') { eq.setItemInOffHand(new ItemStack0(AIR)); changed = true; }
        } catch (e) { }
        if (changed) {
            try {
                var world = ent.getWorld();
                var l = ent.getLocation();
                btSound(world, l, 'item.armor.equip_iron', 0.4, 1.6);   // 轻微"哐"一声，像丢下弩
            } catch (e) { }
        }
        // ★ 掉落率全写 0（清空后本来也没东西，双保险）
        try { eq.setItemInMainHandDropChance(0.0); } catch (e) { }
        try { eq.setItemInOffHandDropChance(0.0); } catch (e) { }
        try { eq.setHelmetDropChance(0.0); } catch (e) { }
        try { eq.setChestplateDropChance(0.0); } catch (e) { }
        try { eq.setLeggingsDropChance(0.0); } catch (e) { }
        try { eq.setBootsDropChance(0.0); } catch (e) { }
    } catch (e) { }
}

// ★ 本体射击：朝目标方向发一支箭（不依赖任何武器）
function btS1Shoot(ref, tgt) {
    try {
        if (ref == null || tgt == null || !btAlive(ref.ent)) return;
        var world = null;
        try { world = ref.ent.getWorld(); } catch (e) { return; }
        if (world == null) return;

        var from = null;
        try { from = ref.ent.getEyeLocation(); } catch (e) { from = ref.ent.getLocation(); }
        var tl = null;
        try { tl = tgt.getLocation(); } catch (e) { return; }
        // 瞄胸口偏上一点（打站着的玩家）
        var ty = tl.getY();
        try { ty = tl.getY() + 1.0; } catch (e) { }

        var dx = tl.getX() - from.getX();
        var dy = ty - from.getY();
        var dz = tl.getZ() - from.getZ();
        var n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n < 0.2) return;
        dx /= n; dy /= n; dz /= n;

        // ★ 一点点自然散布：不至于百发百中，玩家能靠跑位规避
        dx += (Math.random() - 0.5) * 0.045;
        dy += (Math.random() - 0.5) * 0.030;
        dz += (Math.random() - 0.5) * 0.045;
        var n2 = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (n2 < 1.0e-4) return;
        dx /= n2; dy /= n2; dz /= n2;

        var Vector = Java.type('org.bukkit.util.Vector');
        var dir = new Vector(dx, dy, dz);

        var arrow = null;
        try {
            // 出生点稍微往前 0.8 格，免得卡在自己身体里
            var LocA = Java.type('org.bukkit.Location');
            var sp = new LocA(world, from.getX() + dx * 0.8, from.getY() - 0.2 + dy * 0.8, from.getZ() + dz * 0.8,
                              from.getYaw(), from.getPitch());
            arrow = world.spawnArrow(sp, dir, BT_S1_ARROW_SPEED, 0.0);
        } catch (e) {
            // 兜底：手动 spawn 一支箭再给速度
            try {
                var ET = btET(['ARROW']);
                if (ET != null) {
                    arrow = world.spawnEntity(from, ET);
                    if (arrow != null) { try { arrow.setVelocity(dir.multiply(BT_S1_ARROW_SPEED)); } catch (e2) { } }
                }
            } catch (e3) { arrow = null; }
        }
        if (arrow == null) return;

        try { arrow.setShooter(ref.ent); } catch (e) { }
        try { arrow.setDamage(0.0); } catch (e) { }        // 伤害由伤害事件统一结算成 12
        try { arrow.setPersistent(false); } catch (e) { }
        try { arrow.setKnockbackStrength(0); } catch (e) { }

        // 弩声 + 一点枪口粒子
        try { btSound(world, from, 'item.crossbow.shoot', 1.0, 1.0); } catch (e) { }
        try { btSound(world, from, 'entity.arrow.shoot', 0.8, 1.1); } catch (e) { }
        try {
            var pt = btP(['CRIT', 'ENCHANTED_HIT', 'SMOKE_NORMAL']);
            btFx(world, pt, from.getX() + dx * 1.0, from.getY(), from.getZ() + dz * 1.0, 4, 0.06, 0.06, 0.06, 0.01);
        } catch (e) { }
    } catch (e) { }
}

// ★ 掐掉唤魔者的原版技能（第二道保险）：
//   ① 每拍强行把"正在施法"清成 NONE
//   ② 每秒清掉它召出来的残留恼鬼 / 尖牙（我们自己做视觉用的尖牙带标记，不算残留）
function btPurgeVanilla(ref) {
    try {
        var ent = ref.ent;
        if (!btAlive(ent) || ref.stage !== 1) return;

        try {
            var SC = Java.type('org.bukkit.entity.Spellcaster');
            if (SC != null && ent instanceof SC) {
                try {
                    var sp = ent.getSpell();
                    if (sp != null && ('' + sp.name()) !== 'NONE') ent.setSpell(SC.Spell.NONE);
                } catch (e) { }
            }
        } catch (e) { }

        // ★ Phase 1 是**掠夺者** ⇒ 每拍把双手清空（原版会自带弩，被别的插件塞东西也清掉）
        try { btS1Hands(ent); } catch (e) { }

        ref.purgeN = (ref.purgeN == null ? 0 : ref.purgeN) + 1;
        if (ref.purgeN % 4 !== 1) return;      // 后面的大扫除每秒做一次就够了

        try {
            var world = ent.getWorld();
            var l = ent.getLocation();
            var list = world.getNearbyEntities(l, 28, 16, 28);
            var it = list.iterator();
            while (it.hasNext()) {
                var v = it.next();
                if (v == null || !btAlive(v)) continue;
                var tn = '';
                try { tn = '' + v.getType().name(); } catch (e) { continue; }
                if (tn.indexOf('VEX') < 0 && tn.indexOf('FANGS') < 0) continue;
                // 我们自己用来做特效的尖牙 ⇒ 跳过
                try {
                    if (BT_FANG_KEY != null && BT_PDT != null) {
                        var pd = v.getPersistentDataContainer();
                        if (pd != null && pd.has(BT_FANG_KEY, BT_PDT.STRING)) continue;
                    }
                } catch (e) { }
                try { v.remove(); } catch (e) { }
            }
        } catch (e) { }
    } catch (e) { }
}

// ==================================================================
// ① 幻念僧 · 技能 1：追踪鳄鱼嘴（一路咬过去）
// ==================================================================
function btS1Fang(ref, tgt) {
    try {
        ref.mode = 'fang';
        ref.fang = { life: 0, x: 0, y: 0, z: 0, traveled: 0.0, fangSeq: 0 };
        var l = ref.ent.getLocation();
        ref.fang.x = l.getX(); ref.fang.y = l.getY() + 0.2; ref.fang.z = l.getZ();

        var world = l.getWorld();
        try { world.playSound(l, 'entity.evoker.prepare_summon', 1.0, 1.2); } catch (e) { }

        btFangTick(ref, tgt);
    } catch (e) {
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

function btFangTick(ref, tgt) {
    try {
        if (ref == null || ref.fang == null) { return; }
        if (!btAlive(ref.ent) || !btAlive(tgt)) {
            ref.fang = null; ref.mode = 'idle'; return;
        }
        var w = ref.fang;
        var world = ref.lw;
        if (world == null) { try { world = ref.ent.getWorld(); } catch (e) { world = null; } }
        if (world == null) { ref.fang = null; ref.mode = 'idle'; return; }

        w.life++;
        if (w.life > BT_S1_FANG_LIFE || w.traveled > BT_S1_FANG_RANGE) {
            ref.fang = null; ref.mode = 'idle'; return;
        }

        // ★ 追踪：每刻都重新朝目标当前位置算方向
        var tl = tgt.getLocation();
        var dx = tl.getX() - w.x, dz = tl.getZ() - w.z;
        var dy = (tl.getY() + 0.6) - w.y;
        var n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) { ref.fang = null; ref.mode = 'idle'; return; }
        var step = BT_S1_FANG_SPEED;
        if (step > n) step = n;
        w.x += dx / n * step;
        w.z += dz / n * step;
        w.y += Math.max(-0.12, Math.min(0.12, dy / n * step));   // 缓慢对齐高度，别一下贴脸
        w.traveled += step;

        // 视觉：每隔几刻丢一团尖牙（Evan visible "鳄鱼嘴"），旧的会自动消失
        if (w.life % BT_S1_FANG_SPAWN === 1) {
            try {
                var ft = btET(['EVOKER_FANGS', 'ILLAGER_FANGS', 'VEX_FANGS']);
                if (ft != null) {
                    var Loc = Java.type('org.bukkit.Location');
                    var f = world.spawnEntity(new Loc(world, w.x, Math.floor(w.y), w.z), ft);
                    if (f != null) {
                        try { f.setOwner(ref.ent); } catch (e) { }
                        try { if (BT_FANG_KEY != null && BT_PDT != null) f.getPersistentDataContainer().set(BT_FANG_KEY, BT_PDT.STRING, '1'); } catch (e) { }
                        try { f.setPersistent(false); } catch (e) { }
                        // 演出用，别留残骸
                        runLater(function (fw) { return function () { try { fw.remove(); } catch (e2) { } }; }(f), 14);
                    }
                }
            } catch (e) { }
        }
        try {
            var pt = btP(['SCULK_CHARGE_POP', 'CRIT', 'SMOKE_NORMAL']);
            btFx(world, pt, w.x, w.y + 0.1, w.z, 3, 0.15, 0.1, 0.15, 0.02);
        } catch (e) { }

        // 命中：贴到 1.2 格就算咬到
        if (Math.sqrt(dx * dx + dz * dz) <= BT_S1_FANG_R && Math.abs(dy) <= 1.6) {
            btHurt(ref.ent, tgt, BT_S1_FANG_DMG);
            try { btSound(world, tl, 'entity.evoker.cast_spell', 0.9, 1.1); } catch (e) { }
            ref.fang = null; ref.mode = 'idle'; return;
        }

        runLater(function () { btFangTick(ref, tgt); }, 1);
    } catch (e) {
        try { ref.fang = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ==================================================================
// ① 幻念僧 · 技能 2：读条 1 秒 → 出现在玩家视角正后方 8 格 + 回 20 血
// ==================================================================
function btS1Blink(ref, tgt) {
    try {
        ref.mode = 'blink';
        ref.blink = { i: 0, tgt: tgt };
        try {
            var world = ref.ent.getWorld();
            btSound(world, ref.ent.getLocation(), 'entity.evoker.prepare_wololo', 1.0, 1.0);
        } catch (e) { }
        btBlinkTick(ref);
    } catch (e) {
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

function btBlinkTick(ref) {
    try {
        if (ref == null || ref.blink == null) return;
        if (!btAlive(ref.ent)) { ref.blink = null; ref.mode = 'idle'; return; }
        var b = ref.blink;
        b.i++;

        var tgt = b.tgt;
        if (btAlive(tgt)) {
            try {
                var l0 = ref.ent.getLocation();
                var pt = btP(['SCULK_SOUL', 'PORTAL', 'SPELL_WITCH', 'SMOKE_LARGE']);
                for (var i = 0; i < 3; i++) {
                    btFx(l0.getWorld(), pt, l0.getX(), l0.getY() + 0.4 + i * 0.3, l0.getZ(), 4, 0.3, 0.3, 0.3, 0.02);
                }
            } catch (e) { }
        }

        if (b.i < BT_S1_BLINK_READ) {                       // 还在读条
            runLater(function () { btBlinkTick(ref); }, 1);
            return;
        }

        // ★ 读条结束：挪位置 + 回血
        ref.mode = 'idle';
        ref.blink = null;
        try {
            if (btAlive(tgt)) {
                var pl = tgt.getLocation();
                var yaw = 0.0;
                try { yaw = pl.getYaw(); } catch (e) { yaw = 0.0; }
                var rad = yaw * Math.PI / 180.0;
                // 玩家看向的水平方向
                var fx = -Math.sin(rad), fz = Math.cos(rad);
                if (BT_S1_BLINK_BEHIND) { fx = -fx; fz = -fz; }     // 背后 = 反方向
                var nx = pl.getX() + fx * BT_S1_BLINK_BACK;
                var nz = pl.getZ() + fz * BT_S1_BLINK_BACK;
                var ny = btSafeY(pl.getWorld(), nx, nz, pl.getY(), BT_S1_HEIGHT);

                var Loc = Java.type('org.bukkit.Location');
                var nl = new Loc(pl.getWorld(), nx, ny, nz, yaw, 0.0);

                var world = pl.getWorld();
                try {
                    var pt2 = btP(['PORTAL', 'SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
                    btFx(world, pt2, nx, ny + 1.0, nz, 40, 0.3, 0.5, 0.3, 0.05);
                } catch (e) { }
                try { world.playSound(nl, 'entity.evoker.cast_spell', 1.0, 0.8); } catch (e) { }
                try { world.playSound(nl, 'entity.enderman.teleport', 0.8, 1.2); } catch (e) { }

                try { ref.ent.teleport(nl); } catch (e) { }
            }

            // ★ 恢复 20 点生命（上限取"属性读到的" → 再兜一层 `getMaxHealth()`，双保险）
            try {
                var mx = btMaxHp(ref.ent);
                try { var gm = ref.ent.getMaxHealth(); if (gm > 0 && (mx <= 0 || gm < mx)) mx = gm; } catch (e) { }
                if (mx <= 0) mx = ref.maxHp;
                var hp = ref.ent.getHealth();
                var nh = hp + BT_S1_BLINK_HEAL;
                if (nh > mx) nh = mx;
                if (nh > hp) ref.ent.setHealth(nh);
            } catch (e) { }
        } catch (e) {
            ref.mode = 'idle';
        }
    } catch (e) {
        try { ref.mode = 'idle'; ref.blink = null; } catch (e2) { }
    }
}

// ==================================================================
// ② 碎念执 · 技能 1：竖着的一道斩击（破盾 / 樱花瓣 / 4 格 / 击退 3 格）
// ==================================================================
function btS2Slash(ref, tgt) {
    try {
        ref.mode = 'slash';
        var l = ref.ent.getLocation();
        // ★ 只记起点，**方向留到出手那一刻（btSlashTick 里 `s.i > 举刀刻数`）再算**
        ref.slash = { i: 0, x: l.getX(), y: l.getY() + 1.0, z: l.getZ(), dx: 0, dz: 0, hit: {} };

        // 起手：把刀举起来（0.15 秒），给一点反应时间
        try { btSound(l.getWorld(), l, 'entity.vindicator.ambient', 0.7, 1.3); } catch (e) { }
        btSlashTick(ref, tgt);
    } catch (e) {
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

function btSlashTick(ref, tgt) {
    try {
        if (ref == null || ref.slash == null) return;
        if (!btAlive(ref.ent)) { ref.slash = null; ref.mode = 'idle'; return; }
        var s = ref.slash;
        var world = null;
        try { world = ref.ent.getWorld(); } catch (e) { world = null; }
        if (world == null) { ref.slash = null; ref.mode = 'idle'; return; }

        s.i++;

        if (s.i <= BT_S2_SLASH_WINDUP) {           // 举刀的那几刻（只出粒子，**不锁方向**）
            runLater(function () { btSlashTick(ref, tgt); }, 1);
            return;
        }

        // ★★ 2026-10-06 **出手这一刻才锁方向** —— 与飞斧同一个毛病（玩家会趁读条跑位）。
        //   原写法在 `s.i === 1`（举刀前）就算好方向 ⇒ 斩击飞向"玩家 0.15 秒前站的地方"。
        if (s.traveled == null) {
            var t2 = tgt;
            try {
                var okT = (t2 != null && t2.isOnline());
                if (!okT) t2 = btTarget(ref);      // 目标跑了/退了 ⇒ 重新挑一个
            } catch (e) { t2 = null; }
            var tl = null;
            try { if (t2 != null) tl = t2.getLocation(); } catch (e) { tl = null; }
            var dx = 0, dz = 0;
            if (tl != null) { dx = tl.getX() - s.x; dz = tl.getZ() - s.z; }
            var n = Math.sqrt(dx * dx + dz * dz);
            if (n < 1.0e-4) {
                var y0 = 0; try { y0 = ref.ent.getLocation().getYaw(); } catch (e) { }
                dx = -Math.sin(y0 * Math.PI / 180.0); dz = Math.cos(y0 * Math.PI / 180.0);
            } else { dx /= n; dz /= n; }
            s.dx = dx; s.dz = dz;
            s.traveled = 0.0;
        }

        var step = BT_S2_SLASH_SPEED;
        var remain = BT_S2_SLASH_RANGE - (s.traveled == null ? 0 : s.traveled);
        if (step > remain) step = remain;
        s.x += s.dx * step;
        s.z += s.dz * step;
        s.traveled = (s.traveled == null ? 0 : s.traveled) + step;

        // ★ 视觉：**竖直的一道刀光**（樱花瓣）
        try {
            var petal = btP(['CHERRY_LEAVES', 'CHERRY_LEAF_PARTICLES', 'FALLING_SPORE_BLOSSOM', 'SPORE_BLOSSOM_AIR', 'HEART', 'SMOKE_NORMAL']);
            for (var k = -BT_S2_SLASH_H; k <= BT_S2_SLASH_H; k += 0.4) {
                btFx(world, petal, s.x, s.y + k, s.z, 1, 0.05, 0.15, 0.05, 0.02);
            }
            // 侧向厚度：让刀光看着有面不是一条线
            btFx(world, petal, s.x + (-s.dz) * 0.4, s.y, s.z + (s.dx) * 0.4, 2, 0.05, 0.3, 0.05, 0.02);
            btFx(world, petal, s.x + ( s.dz) * 0.4, s.y, s.z + (-s.dx) * 0.4, 2, 0.05, 0.3, 0.05, 0.02);
        } catch (e) { }

        // 命中判定：宽 1.1 格、竖着 ±1.6 格
        try {
            var hitList = world.getNearbyEntities(new (Java.type('org.bukkit.Location'))(world, s.x, s.y, s.z),
                BT_S2_SLASH_HALF_W + 0.5, BT_S2_SLASH_H + 0.5, BT_S2_SLASH_HALF_W + 0.5);
            var it = hitList.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null || !btAlive(ent)) continue;
                if (ent === ref.ent) continue;
                if (btOurs(ent)) continue;
                var eid = '';
                try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                if (s.hit[eid] === 1) continue;

                var el = null;
                try { el = ent.getLocation(); } catch (e) { continue; }
                if (Math.abs(el.getY() + 0.9 - s.y) > BT_S2_SLASH_H) continue;

                // ★ 破盾（只对玩家生效）—— 按原版斧头砍盾的口径扣耐久
                try {
                    var Player = Java.type('org.bukkit.entity.Player');
                    if (ent instanceof Player) btBreakShield(ent, BT_S2_SLASH_DMG);
                } catch (e) { }

                s.hit[eid] = 1;
                btHurt(ref.ent, ent, BT_S2_SLASH_DMG);
                btKb(ent, s.dx * BT_S2_SLASH_KB, BT_S2_SLASH_UP, s.dz * BT_S2_SLASH_KB);
                try { btSound(world, el, 'entity.player.attack.crit', 0.8, 1.2); } catch (e) { }
            }
        } catch (e) { }

        if (s.traveled >= BT_S2_SLASH_RANGE - 0.01) {
            ref.slash = null; ref.mode = 'idle'; return;
        }
        runLater(function () { btSlashTick(ref, tgt); }, 1);
    } catch (e) {
        try { ref.slash = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ==================================================================
// ②-2 碎念执 · 技能 2：星环飞斧（2026-10-06 新增，2026-10-06 修正落点）
// ------------------------------------------------------------------
//  ① **蓄力 1.5 秒**：身上浮现 `BT_S2_AXE_STAR_N` 颗**环绕的星星粒子**（两层反向转）
//     —— 用**自终止的 runLater 链**（`runRepeating` 的返回值是 null、cancel 不掉，见文末）
//     并配 `axeGen` 世代号：玩家下线 / boss 死亡 / 异常时旧链自动作废，绝不留残影。
//  ② **飞斧本体 = 隐形盔甲架手持一把铁斧**（★ 遵循「能用实体表达的，别用粒子画」）：
//     实体有体积有朝向，逐帧 `teleport` 客户端看到的就是**一把斧头在翻滚着飞出去**，
//     比纯粒子像得多。每帧 `setRotation(yaw, pitch)` 让它边飞边转。
//     ★ 收尾一律 `remove()`（marker 盔甲架不掉落），并在 `ref.axeStand` 记一份，
//       `btCleanup` / 阶段切换时兜底清掉，绝不残留。
//  ③ **直线不穿墙**：每刻推进 `BT_S2_AXE_SPEED`(0.62) 格，**推进前**先用
//     `btPassableAt` 判下一格（**玻璃也挡得住** —— 用 isPassable 而不是 isSolid）。
//  ④ **撞到方块 / 玩家就停**：命中玩家给 35 点伤害 + 小小击退（**只打一次**，
//     用 `hit[eid]` 去重），然后立刻收斧。
//  ⑤ **斧头停下的位置**生成「妄想」（恼鬼 Vex）：7 伤害 / 25 血，
//     **打上 `gs:boss3` 标记 ⇒ 与卫道士同队**（`btOurs` 为真 ⇒ 斩击与飞斧都跳过它，
//     伤害事件里也互相免伤、不会互相索敌），**9 秒后处死**（避免卡服）。
//
// ★★ 2026-10-06 **落点修正**：方向**原本在蓄力开始那一刻就锁死** ⇒ 玩家有整整 1.5 秒
//    可以跑掉，但斧子仍旧飞向"他 1.5 秒前站的地方" ⇒ 落点莫名其妙、看着像 bug。
//    现在改成：**蓄力期间只出粒子、不定方向**；**出手（`btAxeThrow`）那一刻才锁**，
//    并且用的是**那一刻**的目标位置和 boss 位置 ⇒ 斧子真的朝玩家当时所在的方向飞。
//    目标在蓄力期间死了 / 跑出世界 ⇒ 重新挑一个；实在没有就退回 boss 自己的朝向。
// ==================================================================
function btS2Axe(ref, tgt) {
    try {
        if (ref == null || ref.done) return;
        if (!btAlive(ref.ent)) return;
        ref.mode = 'axe';                       // 演出中 ⇒ 主心跳本拍不再放别的技能

        ref.axeGen = (ref.axeGen == null ? 0 : ref.axeGen) + 1;    // ★ 世代号
        // ★ **不锁方向**（只留一个兜底 tgt：万一蓄力期间没人了，好歹朝他原来的方向飞）
        ref.axe = { gen: ref.axeGen, i: 0, dx: 0, dz: 0, traveled: 0.0, hit: {}, tgt: tgt };

        var l = null;
        try { l = ref.ent.getLocation(); } catch (e) { l = null; }
        try { if (l != null) btSound(l.getWorld(), l, 'entity.evoker.prepare_summon', 0.6, 1.6); } catch (e) { }
        btAxeChargeTick(ref);
    } catch (e) {
        try { ref.axe = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ① 蓄力：环绕星星粒子（1.5 秒）
function btAxeChargeTick(ref) {
    try {
        if (ref == null || ref.axe == null || ref.done) return;
        var a = ref.axe;
        var gen = a.gen;
        if (ref.axeGen !== gen) return;          // 旧链作废
        if (!btAlive(ref.ent)) { ref.axe = null; ref.mode = 'idle'; return; }

        a.i++;
        var world = null, l = null;
        try { l = ref.ent.getLocation(); world = l.getWorld(); } catch (e) { }
        if (l == null || world == null) { ref.axe = null; ref.mode = 'idle'; return; }

        var star = btP(['CRIT', 'ENCHANTED_HIT', 'END_ROD']);
        var n = BT_S2_AXE_STAR_N;
        var stepA = (Math.PI * 2) / n;
        // 两层反向转 ⇒ 看着像"星环在收拢又张开"，比一圈单转有张力
        for (var k = 0; k < n; k++) {
            var ang1 = a.i * 0.34 + k * stepA;
            btFx(world, star, l.getX() + Math.cos(ang1) * BT_S2_AXE_STAR_R,
                               l.getY() + 0.35 + (k % 3) * 0.42,
                               l.getZ() + Math.sin(ang1) * BT_S2_AXE_STAR_R, 1, 0.02, 0.02, 0.02, 0);
            var ang2 = -a.i * 0.26 + k * stepA;
            btFx(world, star, l.getX() + Math.cos(ang2) * (BT_S2_AXE_STAR_R * 0.6),
                               l.getY() + 1.65 - (k % 3) * 0.38,
                               l.getZ() + Math.sin(ang2) * (BT_S2_AXE_STAR_R * 0.6), 1, 0.02, 0.02, 0.02, 0);
        }
        if (a.i % 6 === 0) {
            try { btSound(world, l, 'block.beacon.ambient', 0.35, 1.5); } catch (e) { }
        }

        if (a.i >= BT_S2_AXE_CHARGE) {
            btAxeThrow(ref);
            return;
        }
        runLater(function () { btAxeChargeTick(ref); }, 1);      // ★ 自终止链：末尾自己决定还跑不跑
    } catch (e) {
        try { ref.axe = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ②③④ 飞斧：直线推进、不穿墙、撞到方块/玩家就停
function btAxeThrow(ref) {
    try {
        if (ref == null || ref.axe == null || ref.done) return;
        var a = ref.axe;
        if (!btAlive(ref.ent)) { ref.axe = null; ref.mode = 'idle'; return; }

        var world = null, l = null;
        try { l = ref.ent.getLocation(); world = l.getWorld(); } catch (e) { }
        if (world == null) { ref.axe = null; ref.mode = 'idle'; return; }

        // ★★ 2026-10-06 **方向在这一刻（出手瞬间）才锁** —— 用的是**此刻**的目标位置。
        //   （原来在蓄力开始时就锁死 ⇒ 斧子飞向"玩家 1.5 秒前站的地方"，落点看着像 bug）
        var tgt = a.tgt;
        try {
            var aliveTgt = false;
            try { aliveTgt = (tgt != null && tgt.isOnline() && tgt.getGameMode() != null
                              && ('' + tgt.getGameMode().name()).indexOf('SPECTATOR') < 0); }
            catch (e) { aliveTgt = false; }
            if (!aliveTgt) tgt = btTarget(ref);       // 目标跑了/死了/退出了 ⇒ 重新挑一个
        } catch (e) { tgt = null; }

        var dx = 0, dz = 0;
        try {
            if (tgt != null) {
                var tl = tgt.getLocation();
                if (('' + tl.getWorld().getName()) === ('' + world.getName())) {
                    dx = tl.getX() - l.getX(); dz = tl.getZ() - l.getZ();
                }
            }
        } catch (e) { dx = 0; dz = 0; }
        var n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) {
            // 没人 / 目标重合 ⇒ 退回 boss 自己的朝向（总比原地不动强）
            var y0 = 0; try { y0 = l.getYaw(); } catch (e) { }
            dx = -Math.sin(y0 * Math.PI / 180.0); dz = Math.cos(y0 * Math.PI / 180.0);
        } else { dx /= n; dz /= n; }
        a.dx = dx; a.dz = dz;
        a.tgt = tgt;

        // 斧头起点：卫道士身前 0.8 格、胸口高度
        var sx = l.getX() + a.dx * 0.8, sz = l.getZ() + a.dz * 0.8, sy = l.getY() + BT_S2_AXE_HALF;
        // ★ 起点前面就是墙（卫道士贴墙站）⇒ 斧子生不出来，直接放弃这次施法，
        //   别把「妄想」塞进墙里
        if (!btPassableAt(world, Math.floor(sx), Math.floor(sy), Math.floor(sz))) {
            try { btSound(world, l, 'block.iron_door_close', 0.5, 1.4); } catch (e) { }
            ref.axe = null; ref.mode = 'idle';
            return;
        }
        a.x = sx; a.y = sy; a.z = sz;
        a.world = world;                       // ★ 记下世界：boss 中途死了也能找到正确的世界

        // ★ 斧子本体：隐形盔甲架 + 手持铁斧
        a.stand = btAxeMake(world, sx, sy, sz, a.dx, a.dz);
        ref.axeStand = a.stand;

        try { btSound(world, l, 'entity.item.throw', 0.9, 1.1); } catch (e) { }
        try {
            var sp = btP(['SWEEP_ATTACK', 'CRIT', 'DUST']);
            btFx(world, sp, sx, sy, sz, 8, 0.2, 0.2, 0.2, 0.05);
        } catch (e) { }

        btAxeFlyTick(ref);
    } catch (e) {
        try { ref.axe = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

function btAxeFlyTick(ref) {
    try {
        if (ref == null || ref.axe == null || ref.done) return;
        var a = ref.axe;
        if (ref.axeGen !== a.gen) return;                 // 旧链作废
        var world = null;
        try { world = a.world; } catch (e) { world = null; }
        if (world == null) { try { world = ref.ent.getWorld(); } catch (e) { world = null; } }
        if (world == null) { btAxeStop(ref); return; }

        // —— ① 先判"下一格能不能过去"（★ 用 isPassable：玻璃挡得住，isSolid 不行）——
        var step = BT_S2_AXE_SPEED;
        var remain = BT_S2_AXE_RANGE - a.traveled;
        if (step > remain) step = remain;
        var nx = a.x + a.dx * step, nz = a.z + a.dz * step;

        var blocked = false;
        if (!btPassableAt(world, Math.floor(a.x), Math.floor(a.y), Math.floor(a.z))) blocked = true;
        if (!btPassableAt(world, Math.floor(a.x), Math.floor(a.y + 0.4), Math.floor(a.z))) blocked = true;
        if (!btPassableAt(world, Math.floor(nx), Math.floor(a.y), Math.floor(nz))) blocked = true;
        if (!btPassableAt(world, Math.floor(nx), Math.floor(a.y + 0.4), Math.floor(nz))) blocked = true;
        if (blocked) { btAxeStop(ref); return; }         // ★ 撞到方块 → 停在当前位置

        a.x = nx; a.z = nz; a.traveled += step;

        // —— ② 让斧子实体跟过去 + 边飞边转 ——
        try {
            if (a.stand != null && btAlive(a.stand)) {
                var yaw = Math.atan2(-a.dx, a.dz) * 180.0 / Math.PI + a.traveled * 46.0;
                var Loc = Java.type('org.bukkit.Location');
                a.stand.teleport(new Loc(world, a.x, a.y, a.z, yaw, 12.0));
            }
        } catch (e) { }
        // 飞行拖尾
        try {
            var tr = btP(['CRIT', 'SWEEP_ATTACK', 'DUST']);
            btFx(world, tr, a.x, a.y, a.z, 2, 0.06, 0.06, 0.06, 0.01);
        } catch (e) { }

        // —— ③ 撞到生物？给伤害 + 小小击退（每个目标只吃一次）——
        //   ★ **玩家 ⇒ 斧头停下**（需求原文「撞到方块/玩家停下」）；
        //     其它生物只吃伤害，斧头继续飞（不然一只鸡就把斧子挡了，很蠢）。
        var stop = false;
        try {
            var Loc2 = Java.type('org.bukkit.Location');
            var list = world.getNearbyEntities(new Loc2(world, a.x, a.y, a.z),
                                               BT_S2_AXE_R, BT_S2_AXE_R, BT_S2_AXE_R);
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null || !btAlive(ent)) continue;
                if (a.stand != null && ent === a.stand) continue;
                if (btOurs(ent)) continue;                  // ★ 自己人（卫道士 / 妄想）跳过
                var eid = '';
                try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                if (a.hit[eid] === 1) continue;
                a.hit[eid] = 1;

                var isPlayer = false;
                try {
                    var Player = Java.type('org.bukkit.entity.Player');
                    isPlayer = (ent instanceof Player);
                } catch (e) { }

                // ★ boss 可能已经死了 ⇒ 传 null 让 btHurt 退化成无来源伤害
                var src = null;
                try { if (btAlive(ref.ent)) src = ref.ent; } catch (e) { }
                btHurt(src, ent, BT_S2_AXE_DMG);
                btKb(ent, a.dx * BT_S2_AXE_KB, BT_S2_AXE_UP, a.dz * BT_S2_AXE_KB);
                try { btSound(world, ent.getLocation(), 'entity.player.attack.crit', 0.9, 1.1); } catch (e) { }
                try {
                    var sp2 = btP(['CRIT', 'SWEEP_ATTACK', 'DUST']);
                    btFx(world, sp2, a.x, a.y, a.z, 10, 0.25, 0.25, 0.25, 0.05);
                } catch (e) { }
                if (isPlayer) { stop = true; break; }       // ★ 撞到玩家 → 停下
            }
        } catch (e) { }

        if (stop) { btAxeStop(ref); return; }
        if (a.traveled >= BT_S2_AXE_RANGE - 0.01) { btAxeStop(ref); return; }   // 飞满 10 格
        runLater(function () { btAxeFlyTick(ref); }, 1);
    } catch (e) {
        try { btAxeStop(ref); } catch (e2) { }
    }
}

// 收斧 + 在停下位置生妄想 + 解除演出锁
function btAxeStop(ref) {
    try {
        if (ref == null || ref.axe == null) return;
        var a = ref.axe;
        var world = null, x = a.x, y = a.y, z = a.z;
        try { world = a.world; } catch (e) { world = null; }
        if (world == null) { try { world = ref.ent.getWorld(); } catch (e) { world = null; } }
        if (world == null) {
            // ★ 绝不猜世界（猜错会把妄想刷到主世界去）—— 拿不到就只收斧子
            try { if (a.stand != null) a.stand.remove(); } catch (e) { }
            try { ref.axeStand = null; } catch (e) { }
            ref.axe = null; ref.mode = 'idle';
            return;
        }

        // 收掉斧子实体（marker 盔甲架 remove 不掉任何东西）
        try { if (a.stand != null) a.stand.remove(); } catch (e) { }
        try { ref.axeStand = null; } catch (e) { }

        // 落地粒子
        try {
            var pt = btP(['SWEEP_ATTACK', 'CRIT', 'DUST', 'SMOKE_NORMAL']);
            btFx(world, pt, x, y, z, 12, 0.25, 0.2, 0.25, 0.04);
        } catch (e) { }

        // ★ 在斧头停下的位置生成妄想
        try { btS2VexSpawn(ref, world, x, y, z); } catch (e) { }

        ref.axe = null;
        ref.mode = 'idle';
    } catch (e) {
        try { ref.axe = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// 造"飞斧"本体：隐身体盔甲架 + 手持铁斧（★ setVisible(false) 只隐身体，装备照常显示）
function btAxeMake(world, x, y, z, dx, dz) {
    try {
        var t = btET(['ARMOR_STAND', 'ARMORSTAND']);
        if (t == null) return null;
        var Loc = Java.type('org.bukkit.Location');
        var yaw = Math.atan2(-dx, dz) * 180.0 / Math.PI;
        var as = world.spawnEntity(new Loc(world, x, y, z, yaw, 0.0), t);
        if (as == null) return null;
        try { as.setVisible(false); } catch (e) { }        // ★ 隐身体，**手上的斧头照样看得见**
        try { as.setMarker(true); } catch (e) { }          // 无碰撞箱 / 不受重力 / 不掉落 / 不可交互
        try { as.setGravity(false); } catch (e) { }
        try { as.setSmall(false); } catch (e) { }
        try { as.setBasePlate(false); } catch (e) { }
        try { as.setArms(true); } catch (e) { }
        try { as.setInvulnerable(true); } catch (e) { }
        try { as.setSilent(true); } catch (e) { }
        try { as.setCollidable(false); } catch (e) { }
        try { as.setCanPickupItems(false); } catch (e) { }
        try { as.setRemoveWhenFarAway(false); } catch (e) { }
        try { as.addScoreboardTag('gs_axe'); } catch (e) { }   // ★ reload 残留靠标签清扫
        try {
            var Mat = Java.type('org.bukkit.Material');
            var axe = null;
            try { axe = Mat.IRON_AXE; } catch (e) { axe = null; }
            if (axe == null) { try { axe = Mat.valueOf('IRON_AXE'); } catch (e2) { axe = null; } }
            if (axe != null) {
                var St = Java.type('org.bukkit.inventory.ItemStack');
                as.getEquipment().setItemInMainHand(new St(axe, 1));
                as.getEquipment().setItemInMainHandDropChance(0.0);
            }
        } catch (e) { }
        return as;
    } catch (e) { return null; }
}

// ⑤ 妄想（恼鬼 Vex）：7 伤害 / 25 血 / ★与卫道士同队★ / 9 秒后处死
function btS2VexSpawn(ref, world, x, y, z) {
    try {
        if (world == null) return null;

        // 场上限流（免得刷出一堆）：数一遍附近的自家妄想。
        // ★ 用 getNearbyEntities 而不是 getEntities() —— 后者会把整个世界的实体全捞一遍
        //   （大服上几万只），而妄想最多只可能落在卫道士身边 10 格内，范围查询足够了。
        var live = 0;
        try {
            var LocC = Java.type('org.bukkit.Location');
            var near = world.getNearbyEntities(new LocC(world, x, y, z), 48, 24, 48);
            var ei = near.iterator();
            while (ei.hasNext()) {
                var en = ei.next();
                if (btTag(en) === BT_S2_VEX_TAG && btAlive(en)) live++;
            }
        } catch (e) { }
        if (live >= BT_S2_VEX_MAX) return null;

        // 站得起来的高度（恼鬼会飞，但先找个不卡墙的位置）
        var sy = y - 0.5;
        try { sy = btSafeY(world, x, z, y, 1); } catch (e) { }

        var Loc = Java.type('org.bukkit.Location');
        var loc = new Loc(world, x, sy, z, 0, 0);
        var v = world.spawnEntity(loc, btET(['VEX']));
        if (v == null) return null;

        btApplyHp(v, BT_S2_VEX_HP);
        btSetDmg(v, BT_S2_VEX_DMG);

        try { v.setCustomName('\u00a7f\u5984\u60f3'); } catch (e) { }        // §f妄想
        try { v.setCustomNameVisible(true); } catch (e) { }
        try { v.setRemoveWhenFarAway(false); } catch (e) { }
        try { v.setPersistent(false); } catch (e) { }                        // 不写盘
        try { v.setCanPickupItems(false); } catch (e) { }
        try { v.setSilent(true); } catch (e) { }
        try { v.addScoreboardTag('gs_vex'); } catch (e) { }                  // ★ reload 残留清扫
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) v.setLootTable(LootTables.EMPTY);
        } catch (e) { }
        try { var eq = v.getEquipment(); if (eq != null) eq.clear(); } catch (e) { }
        // ★★ 与卫道士同队：同一个 PDC 标记（值不同）⇒ btOurs() 为真
        //    ⇒ 斩击 / 飞斧 / 其它脚本的命中判定都会跳过它
        try {
            if (BT_KEY != null && BT_PDT != null) {
                v.getPersistentDataContainer().set(BT_KEY, BT_PDT.STRING, BT_S2_VEX_TAG);
            }
        } catch (e) { }

        try { btSound(world, loc, 'entity.evoker.prepare_summon', 0.8, 1.4); } catch (e) { }
        try { btSound(world, loc, 'entity.vex.ambient', 0.5, 1.2); } catch (e) { }
        try {
            var pt = btP(['SMOKE_LARGE', 'LARGE_SMOKE', 'PORTAL', 'SOUL_FIRE_FLAME', 'SMOKE_NORMAL']);
            btFx(world, pt, x, sy + 0.6, z, 18, 0.3, 0.4, 0.3, 0.04);
        } catch (e) { }

        // ★★ 9 秒后处死（避免卡服）：先走 setHealth(0) 播个死亡动作，20 刻后无条件 remove 兜底
        var vid = '';
        try { vid = '' + v.getUniqueId(); } catch (e) { }
        runLater(function () {
            try {
                if (!btAlive(v)) { try { v.remove(); } catch (e) { } return; }
                try { v.getEquipment().clear(); } catch (e) { }
                try { v.setHealth(0.0); } catch (e) { }
            } catch (e) { }
        }, BT_S2_VEX_LIFE);
        runLater(function () {
            try { if (v != null) v.remove(); } catch (e) { }
        }, BT_S2_VEX_LIFE + 20);

        return v;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 碎念执：妄想生成失败 ' + e); } catch (e2) { }
        return null;
    }
}

// ==================================================================
// ③ 妄念魔 · 技能 1：漂浮蓄力 1 秒 → 出现在玩家头顶 → 1 秒后落地 50 点
// ==================================================================
function btS3Slam(ref, tgt) {
    try {
        ref.mode = 'slam_charge';
        var l = ref.ent.getLocation();
        // ★★ 2026-10-06：**不记落点**（起手就锁 ⇒ 蓄力 1 秒里玩家早跑开了，砸的是空气）
        //   落点改成"蓄力结束那一刻"才算，见 btSlamTick 的 charge → hover 分支。
        ref.slam = { i: 0, y0: l.getY(), x: l.getX(), z: l.getZ(), phase: 'charge', tgt: tgt };

        try { ref.ent.setAI(false); } catch (e) { }
        try { ref.ent.setTarget(null); } catch (e) { }
        try { btSound(l.getWorld(), l, 'entity.ravager.roar', 1.0, 0.9); } catch (e) { }
        btSlamTick(ref);
    } catch (e) {
        try { ref.ent.setAI(true); } catch (e2) { }
        try { ref.mode = 'idle'; } catch (e2) { }
    }
}

function btSlamTick(ref) {
    try {
        if (ref == null || ref.slam == null) return;
        if (!btAlive(ref.ent)) { ref.slam = null; ref.mode = 'idle'; return; }
        var s = ref.slam;
        var ent = ref.ent;
        var world = ent.getWorld();
        var Loc = Java.type('org.bukkit.Location');

        s.i++;

        if (s.phase === 'charge') {
            // 原地不动 + 慢慢往上飘
            var rise = BT_S3_SLAM_RISE * (s.i / BT_S3_SLAM_CHARGE);
            var ny = s.y0 + rise;
            try { ent.teleport(new Loc(world, s.x, ny, s.z, ent.getLocation().getYaw(), 0.0)); } catch (e) { }
            try {
                var pc = btP(['CLOUD', 'LARGE_SMOKE', 'SMOKE_NORMAL', 'PORTAL']);
                btFx(world, pc, s.x, ny + 1.2, s.z, 6, 0.5, 0.2, 0.5, 0.03);
            } catch (e) { }
            try {
                var pb = btP(['SCULK_SOUL', 'SOUL', 'PORTAL', 'INSTANT_EFFECT']);
                btFx(world, pb, s.x, ny + 0.2, s.z, 3, 0.4, 0.4, 0.4, 0.02);
            } catch (e) { }
            if (s.i >= BT_S3_SLAM_CHARGE) {
                // ★★ 2026-10-06 **落点在这一刻才算**（用此刻的目标位置）
                //   —— 蓄力那 1 秒里玩家可以自由跑位，砸下来会砸在他**当时**站的地方
                //   （悬停那 1 秒仍然不追踪 ⇒ 悬停开始后依然跑得掉，这是原本的设计）
                var st = s.tgt;
                try {
                    var okS = (st != null && st.isOnline());
                    if (!okS) st = btTarget(ref);
                } catch (e) { st = null; }
                var tl2 = null;
                try { if (st != null) tl2 = st.getLocation(); } catch (e) { tl2 = null; }
                try {
                    if (tl2 != null && ('' + tl2.getWorld().getName()) === ('' + world.getName())) {
                        s.tx = tl2.getX(); s.ty = tl2.getY(); s.tz = tl2.getZ();
                    } else {
                        s.tx = s.x; s.ty = s.y0; s.tz = s.z;   // 没目标 ⇒ 砸自己脚下
                    }
                } catch (e) { s.tx = s.x; s.ty = s.y0; s.tz = s.z; }

                // 出现在目标头顶
                s.phase = 'hover';
                s.i = 0;
                var hy = btSafeY(world, s.tx, s.tz, s.ty + BT_S3_SLAM_ABOVE, BT_S3_HEIGHT);
                if (hy < s.ty + 0.5) hy = s.ty + BT_S3_SLAM_ABOVE;
                s.hy = hy;
                try { ent.teleport(new Loc(world, s.tx, hy, s.tz, ent.getLocation().getYaw(), 0.0)); } catch (e) { }
                try { btSound(world, new Loc(world, s.tx, hy, s.tz), 'entity.ravager.roar', 1.0, 1.1); } catch (e) { }
                try { btSound(world, new Loc(world, s.tx, hy, s.tz), 'entity.wither.spawn', 0.4, 1.4); } catch (e) { }
            }
            runLater(function () { btSlamTick(ref); }, 1);
            return;
        }

        if (s.phase === 'hover') {
            // 悬停在头顶，等 1 秒 ⇒ 这段时间玩家**跑得掉**
            try { ent.teleport(new Loc(world, s.tx, s.hy, s.tz, ent.getLocation().getYaw(), 0.0)); } catch (e) { }
            try {
                var ph = btP(['CLOUD', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
                btFx(world, ph, s.tx, s.hy - 0.3, s.tz, 5, 0.4, 0.1, 0.4, 0.02);
            } catch (e) { }
            if (s.i >= BT_S3_SLAM_HOVER) {
                s.phase = 'drop'; s.i = 0;
                // 落点：脚下实地
                s.gy = btSafeY(world, s.tx, s.tz, s.ty, BT_S3_HEIGHT);
                s.sy = s.hy;
            }
            runLater(function () { btSlamTick(ref); }, 1);
            return;
        }

        // 落地：从悬停点快速砸下去
        var t01 = s.i / BT_S3_SLAM_DROP;
        if (t01 > 1) t01 = 1;
        var dy = s.sy + (s.gy - s.sy) * t01 * t01;      // 越掉越快
        try { ent.teleport(new Loc(world, s.tx, dy, s.tz, ent.getLocation().getYaw(), 0.0)); } catch (e) { }

        if (s.i >= BT_S3_SLAM_DROP) {
            // ★ 着地：范围内所有生物 50 点
            try { ent.teleport(new Loc(world, s.tx, s.gy, s.tz, ent.getLocation().getYaw(), 0.0)); } catch (e) { }
            try {
                var pe = btP(['EXPLOSION_LARGE', 'EXPLOSION_HUGE', 'CLOUD', 'LARGE_SMOKE']);
                btFx(world, pe, s.tx, s.gy + 0.5, s.tz, 12, 0.6, 0.4, 0.6, 0.05);
            } catch (e) { }
            try { btSound(world, new Loc(world, s.tx, s.gy, s.tz), 'entity.ravager.step', 1.0, 0.6); } catch (e) { }
            try { btSound(world, new Loc(world, s.tx, s.gy, s.tz), 'entity.generic.explode', 0.6, 0.7); } catch (e) { }
            try { btSound(world, new Loc(world, s.tx, s.gy, s.tz), 'entity.ravager.roar', 0.8, 0.7); } catch (e) { }

            try {
                var list = world.getNearbyEntities(new Loc(world, s.tx, s.gy + 0.5, s.tz), BT_S3_SLAM_R, BT_S3_SLAM_R, BT_S3_SLAM_R);
                var it = list.iterator();
                while (it.hasNext()) {
                    var v = it.next();
                    if (v == null || !btAlive(v)) continue;
                    if (v === ent) continue;                    // ★ 自己不吃这一下
                    if (btOurs(v)) continue;
                    var vl = null;
                    try { vl = v.getLocation(); } catch (e) { continue; }
                    if (vl.distance(new Loc(world, s.tx, s.gy, s.tz)) > BT_S3_SLAM_R) continue;
                    btHurt(ent, v, BT_S3_SLAM_DMG);
                }
            } catch (e) { }

            try { ent.setAI(true); } catch (e) { }
            ref.slam = null;
            ref.mode = 'idle';
            return;
        }

        runLater(function () { btSlamTick(ref); }, 1);
    } catch (e) {
        try { ref.ent.setAI(true); } catch (e2) { }
        try { ref.slam = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ==================================================================
// ★ 2026-10-06：妄念魔的「龙息弹」技能已按需求删除
//   （原本在这里的 btS3Fire / btFireTick 两个函数连同 ExplosionPrime /
//    EntityExplode 两条拦截一起下线，妄念魔现在只有「坠地」一个技能）
// ==================================================================

// ==================================================================
// 阶段切换：死了 → 演出 → 召唤下一只
// ==================================================================
function btPhaseEnd(ref) {
    try {
        if (ref == null || ref.transitioning) {
            // 已经在切了（多拍重复进来）→ 什么都不做
            return;
        }
        ref.transitioning = true;
        btBarDrop(ref);

        // ★ 正在飞斧 / 蓄力？→ 让旧链作废并把斧子实体收掉（世代号 + 直接 remove）
        try { ref.axeGen = (ref.axeGen == null ? 0 : ref.axeGen) + 1; } catch (e) { }
        try { if (ref.axeStand != null) { ref.axeStand.remove(); ref.axeStand = null; } } catch (e) { }
        try { ref.axe = null; } catch (e) { }
        // ★ 正在读条拉人？→ 作废（不然 boss 死了还会把一票玩家瞬移到空气里）
        try { ref.dragGen = (ref.dragGen == null ? 0 : ref.dragGen) + 1; } catch (e) { }
        try { ref.drag = null; } catch (e) { }
        // ★ 本阶段召出的妄想一并清掉（换阶段后不该留着上一阶段的兵）
        try { btS2VexPurge(); } catch (e) { }

        // ★ BGM：这一只死了 ⇒ **立刻停**（下一阶段由 btNext 重新起自己那首）
        try { btMusicStopAll(ref); } catch (e) { }

        var stage = ref.stage;
        // ★★★ 第八十五批：只有**第三阶段**掉「妄想碎片」= **固定 6 个**
        //   （BT_FRAG_DROP = [0, 0, 6]；一/二阶段那一支会直接 return）
        try { btDropFragments(ref, stage); } catch (e) { }
        // ★★★ 第八十六批：只有**第二阶段**掉「残樱」= **固定 1 把**
        //   （BT_CHERRY_DROP = [0, 1, 0]；一/三阶段那一支会直接 return）
        try { btDropCherry(ref, stage); } catch (e) { }

        if (stage >= 3) {
            // ★ 三阶段全部打完 ⇒ 收摊（**不再掉精神力**，精神力改织梦机大配方）
            btCleanup(ref);
            return;
        }

        var world = ref.lw;
        var x = (ref.lx == null ? 0 : ref.lx);
        var y = (ref.ly == null ? 64 : ref.ly);
        var z = (ref.lz == null ? 0 : ref.lz);

        if (stage === 1) {
            // ★ 羊驼口水烟雾（3 秒）
            btSmokePuff(world, x, y, z, 0);
            runLater(function () {
                try { btNext(ref, 2, world, x, y, z); } catch (e) { }
            }, BT_TRANS_TICKS);
        } else if (stage === 2) {
            // ★ 灵魂沙粒子柱往上冲（3 秒窜上去 20 多格）
            btSoulPillar(world, x, y, z, 0);
            runLater(function () {
                try { btNext(ref, 3, world, x, y, z); } catch (e) { }
            }, BT_TRANS_TICKS);
        }
    } catch (e) {
        btCleanup(ref);
    }
}

// ★ 幻念僧之死：一团羊驼口水（LLAMA_SPIT）烟雾
function btSmokePuff(world, x, y, z, i) {
    try {
        if (world == null) return;
        if (i >= BT_TRANS_TICKS) return;
        var pt = btP(['LLAMA_SPIT', 'SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
        var r = 0.8 + i * 0.01;
        for (var a = 0; a < 4; a++) {
            var ang = (i * 0.35) + a * 1.57;
            btFx(world, pt, x + Math.cos(ang) * r, y + 0.6 + (i % 10) * 0.04, z + Math.sin(ang) * r, 2, 0.12, 0.25, 0.12, 0.02);
        }
        btFx(world, pt, x, y + 0.9, z, 3, 0.4, 0.3, 0.4, 0.03);
        if (i % 15 === 0) {
            try {
                var LocS = Java.type('org.bukkit.Location');
                btSound(world, new LocS(world, x, y, z), 'entity.llama.spit', 0.7, 0.7);
            } catch (e) { }
        }
        runLater(function () { btSmokePuff(world, x, y, z, i + 1); }, 1);
    } catch (e) { }
}

// ★ 碎念执之死：灵魂沙粒子柱冲上天（3 秒内冲到 20 多格）
function btSoulPillar(world, x, y, z, i) {
    try {
        if (world == null) return;
        if (i >= BT_TRANS_TICKS) return;
        var frac = i / BT_TRANS_TICKS;
        var top = BT_SOUL_HEIGHT * frac;               // 逐拍往上长
        var pt = btP(['SOUL_SAND', 'SOUL', 'SCULK_SOUL', 'ASH', 'WHITE_ASH', 'SMOKE_NORMAL']);
        for (var h = 0; h <= top; h += 0.5) {
            btFx(world, pt, x, y + h + 0.2, z, 1, 0.12, 0.02, 0.12, 0.01);
        }
        // 柱脚一直往外冒
        btFx(world, pt, x, y + 0.3, z, 6, 0.5, 0.05, 0.5, 0.02);
        if (i === 1) {
            try {
                var LocS = Java.type('org.bukkit.Location');
                btSound(world, new LocS(world, x, y, z), 'block.soul_sand.step', 1.0, 0.6);
            } catch (e) { }
        }
        runLater(function () { btSoulPillar(world, x, y, z, i + 1); }, 1);
    } catch (e) { }
}

// ★ 生成下一阶段
function btNext(ref, stage, world, x, y, z) {
    try {
        if (ref == null || ref.done) return;
        if (world == null) { btCleanup(ref); return; }

        var Loc = Java.type('org.bukkit.Location');
        var sy = btSafeY(world, x, z, y, (stage === 3 ? BT_S3_HEIGHT : BT_S2_HEIGHT));
        var loc = new Loc(world, x, sy, z, 0, 0);

        var ent = null;
        if (stage === 2) {
            ent = btSpawnEntity(world, loc, ['VINDICATOR', 'VINDICATION_ILLAGER'], BT_S2_NAME, BT_S2_HP, BT_S2_DMG, 'wangnian2');
            if (ent != null) {
                ref.stage = 2;
                ref.maxHp = BT_S2_HP;
                ref.ent = ent;
                ref.cd1 = btNow() - BT_S2_SLASH_CD + BT_FIRST_MS;   // 出场保护期内不放技能
                // ★ 飞斧：出场保护期（BT_FIRST_MS）内同样不放 ⇒ **不会一召唤就甩斧子**
                ref.cd3 = btNow() - BT_S2_AXE_CD + BT_FIRST_MS;
                ref.cd2 = 0;
                ref.axe = null; ref.axeStand = null; ref.axeGen = 0;
                ref.readyAt = btNow() + BT_FIRST_MS;
                ref.hpFixUntil = btNow() + BT_HP_FIX_MS;   // ★ 血量保护窗口
                ref.hpOk = false;                          // ★ 还没确认血量写成功
                ref.hpScale = 1.0;                         // ★ 伤害缩放（1.0 = 正常）
                ref.bar = btBarMake('碎念执');
                ref.barNear = {};
                try { btSound(world, loc, 'entity.vindicator.death', 1.0, 0.8); } catch (e) { }
                try { btSound(world, loc, 'entity.wither.spawn', 0.5, 1.2); } catch (e) { }
                try {
                    var pt = btP(['LLAMA_SPIT', 'SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
                    btFx(world, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(), 40, 0.4, 0.6, 0.4, 0.05);
                } catch (e) { }
            }
        } else if (stage === 3) {
            ent = btSpawnEntity(world, loc, ['RAVAGER'], BT_S3_NAME, BT_S3_HP, BT_S3_DMG, 'wangnian3');
            if (ent != null) {
                ref.stage = 3;
                ref.maxHp = BT_S3_HP;
                ref.ent = ent;
                ref.cd1 = btNow() - BT_S3_SLAM_CD + BT_FIRST_MS;
                ref.cd2 = 0;      // ★ 妄念魔没有第二个技能（龙息弹已删）
                ref.readyAt = btNow() + BT_FIRST_MS;
                ref.hpFixUntil = btNow() + BT_HP_FIX_MS;   // ★ 血量保护窗口
                ref.hpOk = false;                          // ★ 还没确认血量写成功
                ref.hpScale = 1.0;                         // ★ 伤害缩放（1.0 = 正常）
                ref.bar = btBarMake('妄念魔');
                ref.barNear = {};
                try { btSound(world, loc, 'entity.ravager.roar', 1.0, 0.8); } catch (e) { }
                try { btSound(world, loc, 'entity.wither.spawn', 0.6, 1.0); } catch (e) { }
                try {
                    var pt3 = btP(['SOUL_SAND', 'SOUL', 'SCULK_SOUL', 'LARGE_SMOKE']);
                    btFx(world, pt3, loc.getX(), loc.getY() + 1.0, loc.getZ(), 40, 0.5, 0.6, 0.5, 0.05);
                } catch (e) { }
            }
        }

        if (ent == null) { btCleanup(ref); return; }

        ref.transitioning = false;
        ref.mode = 'idle';
        // ★ 锁链拽回是**跨阶段共用状态** ⇒ 切阶段时必须作废旧链 + 重置 CD，
        //   否则"换阶段那一瞬间"可能有一批玩家已经被上一阶段读条锁定了。
        try { ref.dragGen = (ref.dragGen == null ? 0 : ref.dragGen) + 1; } catch (e) { }
        try { ref.drag = null; } catch (e) { }
        // ★ 锁链用**自己**的出场延迟（10 秒），与本阶段其他技能（4 秒）错开
        //   —— 见 BT_DRAG_FIRST_MS 的注释（防"拽到脸上 + 斩击/飞斧同刻命中"）
        ref.cd4 = btNow() - BT_DRAG_CD + BT_DRAG_FIRST_MS;
        ref.dragged = {};                              // ★ 换阶段 ⇒ 换一批没被拉过的人
        ref.lw = world; ref.lx = x; ref.ly = sy; ref.lz = z;

        try { btBarSync(ref); } catch (e) { }
        // ★ 新阶段起自己那首 BGM（旧那首在 btPhaseEnd 已经停干净了）
        try { ref.music = {}; } catch (e) { }
        try { btMusicLoop(ref); } catch (e) { }
        runLater(function () { btTick(ref); }, BT_TICK);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 妄念：切换阶段出错 ' + e); } catch (e2) { }
        btCleanup(ref);
    }
}

// ==================================================================
// ★★★ 通用技能「锁链拽回」—— **三个阶段共用**（2026-10-06 新增，反卡位）
// ------------------------------------------------------------------
//  玩家可以站在射程边缘放技能 / 射箭 / 吃药，跑掉，等 CD 再来一轮 ——
//  boss 永远碰不到人。这个技能把 64 格内的玩家**强制拽到 boss 身边**。
//
//  三段结构（照 §7.13 的世代号骨架写，不留残影）：
//    ① `btDragStart`  起手：读条 1 秒，期间画**锁链**（从 boss 连到每个玩家）
//    ② `btDragTick`   每刻：核一次"这个人还在范围内吗"（跑掉就放过，不追）
//    ③ `btDragFinish` 收网：把锁定的人**散开围成一圈**传送到 boss 身边
//
//  ★ 为什么用"传送"而不是"拉"（setVelocity）：
//    拉（给个速度）在原版里**会被玩家自己的疾跑 / 药水 / 抗性顶回去** ——
//    拉 64 格要拉几十刻，玩家早跑没影了，而且中途还能喝药挣脱。
//    需求是"**强制**拽回"，只有传送能做到"强制"。所以这里用 `p.teleport()`。
//  ★ 传送**无伤害**（这是控制手段不是输出手段），也不吃药水/护甲。
//  ★ 落点**围成一圈**而不是全叠在同一点 —— 否则三个人会被塞进同一格互相顶掉。
//  ★ 每个落点都过 `btSafeY` + `btPassableAt` ⇒ 不会塞进墙里/半空/岩浆里。
// ==================================================================
function btDragStart(ref) {
    try {
        if (ref == null || ref.done) return;
        if (!btAlive(ref.ent)) return;
        ref.mode = 'drag';
        ref.dragGen = (ref.dragGen == null ? 0 : ref.dragGen) + 1;
        ref.drag = { gen: ref.dragGen, i: 0, hit: {} };      // ★ 出价只锁 uuid，不存 Player 引用
        try {
            var l0 = ref.ent.getLocation();
            btSound(l0.getWorld(), l0, 'block.chain.break', 0.9, 0.7);
        } catch (e) { }
        btDragTick(ref);
    } catch (e) {
        try { ref.drag = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

function btDragTick(ref) {
    try {
        if (ref == null || ref.drag == null || ref.done) return;
        var d = ref.drag;
        if (ref.dragGen !== d.gen) return;                     // 旧链作废
        if (!btAlive(ref.ent)) { ref.drag = null; ref.mode = 'idle'; return; }

        d.i++;
        var world = null, l = null;
        try { l = ref.ent.getLocation(); world = l.getWorld(); } catch (e) { }
        if (l == null || world == null) { ref.drag = null; ref.mode = 'idle'; return; }

        // ★ 读条期间：把范围内每个人都**锁进 hit**（只记 uuid，不持有引用）
        if (d.i === 1 || d.i % 4 === 0) {
            var list = btDragPlayers(world, l);
            for (var q = 0; q < list.length; q++) {
                try { d.hit['' + list[q].getUniqueId()] = 1; } catch (e) { }
            }
        }

        // 锁链特效：boss → 每个被锁的人，拉一条灵魂沙链
        try {
            var pt = btP(['CHAIN', 'SOUL', 'SCULK_SOUL', 'ELECTRIC_SPARK', 'SMOKE_NORMAL']);
            var n = btDragCount(d);
            for (var k = 0; k < n; k++) {
                var p = btDragPlayerById(world, d, k);
                if (p == null) continue;
                var pl = null;
                try { pl = p.getLocation(); } catch (e) { continue; }
                // 沿连线取 8 个点（每刻往前挪一点 ⇒ 看着像"正在被拽过来"）
                var f = ((d.i % 8) / 8);
                btFx(world, pt, l.getX() + (pl.getX() - l.getX()) * f, l.getY() + 1.2,
                                  l.getZ() + (pl.getZ() - l.getZ()) * f, 2, 0.05, 0.05, 0.05, 0);
            }
        } catch (e) { }

        if (d.i % 6 === 0) {
            try { btSound(world, l, 'block.conduit.ambient', 0.35, 1.7); } catch (e) { }
        }

        if (d.i >= BT_DRAG_READ) { btDragFinish(ref); return; }
        runLater(function () { btDragTick(ref); }, 1);
    } catch (e) {
        try { ref.drag = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// ③ 收网：把锁定的人**散开围成一圈**强制拽到 boss 身边
function btDragFinish(ref) {
    try {
        if (ref == null || ref.drag == null) return;
        var d = ref.drag;
        var world = null, l = null;
        try { l = ref.ent.getLocation(); world = l.getWorld(); } catch (e) { }
        ref.drag = null; ref.mode = 'idle';
        if (l == null || world == null) return;

        // ★ 出手这一刻**重新核范围**：读条期间跑出 `BT_DRAG_RANGE` 的就放过他（不追）
        //   ★ 只判上限，**不判下限** —— 挖三填一的玩家离 boss 只有 1～2 格，
        //     必须照样拉进来（这正是这个技能要治的东西）
        var cand = [];
        var n = btDragCount(d);
        for (var k = 0; k < n; k++) {
            var p = btDragPlayerById(world, d, k);
            if (p == null) continue;
            var ok = false;
            try {
                if (('' + p.getWorld().getName()) === ('' + world.getName())) {
                    if (p.getLocation().distance(l) <= BT_DRAG_RANGE) ok = true;
                }
            } catch (e) { ok = false; }
            if (ok) { try { cand.push(p); } catch (e) { } }
        }
        if (cand.length <= 0) return;
        if (cand.length > BT_DRAG_CAP) cand = cand.slice(0, BT_DRAG_CAP);

        // 落点：以 boss 为圆心，**围成一圈**铺开（不是全叠在同一点）
        //   ★ 半径压到 `BT_DRAG_RING`(1.6) —— 用途是"让 boss 能打到人"，
        //     落太远等于白拉；1.6 格一定在幻念僧/卫道士/劫掠兽的近战范围内
        var cnt = cand.length;
        var yaw0 = 0.0;
        try { yaw0 = l.getYaw(); } catch (e) { }
        var Loc = Java.type('org.bukkit.Location');
        var sy = btSafeY(world, l.getX(), l.getZ(), l.getY() + BT_DRAG_HEIGHT, 2);

        // 本轮已拉名单：拉过的人这一轮不再拉第二遍（防止刚被瞬移完又瞬移一次）
        var done = ref.dragged;
        if (done == null) { done = {}; ref.dragged = done; }

        var moved = 0;
        for (var i = 0; i < cnt; i++) {
            var pl2 = cand[i];
            try {
                // 以 boss 朝向为基准均匀铺开
                var ang = (yaw0 * Math.PI / 180.0) + (Math.PI * 2 * i / cnt);
                var fx = Math.cos(ang), fz = Math.sin(ang);
                // 先试正前方那个点，不行就左右各挪一点（躲开墙）
                var okPos = false;
                for (var tryN = 0; tryN < 5 && !okPos; tryN++) {
                    var rr = BT_DRAG_RING;
                    var spread = (tryN == 0 ? 0 : (tryN % 2 == 0 ? 1 : -1) * Math.ceil(tryN / 2) * 0.8);
                    var ax = l.getX() + fx * rr, az = l.getZ() + fz * rr;
                    ax += -fz * spread; az += fx * spread;
                    if (!btPassableAt(world, Math.floor(ax), Math.floor(sy), Math.floor(az))) continue;
                    var ay = btSafeY(world, ax, az, sy, 2);
                    // ★ 先把人从载具上卸下来：骑乘状态下 teleport 玩家会把坐骑落在原地，
                    //   人马分家很难看（也避免某些版本 teleport 骑乘者时把人卡进方块）
                    try { if (pl2.getVehicle() != null) pl2.leaveVehicle(); } catch (e2) { }
                    try {
                        pl2.teleport(new Loc(world, ax, ay, az, pl2.getLocation().getYaw(), 0.0));
                        okPos = true;
                    } catch (e) { okPos = false; }
                }
                if (!okPos) continue;
                try { done['' + pl2.getUniqueId()] = 1; } catch (e) { }   // 记进"本轮已拉"

                // 传送特效 + 音效（让玩家知道"被拽了"，不至于莫名其妙）
                try {
                    var pp = btP(['PORTAL', 'SOUL_FIRE_FLAME', 'SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
                    btFx(world, pp, l.getX(), l.getY() + 1.0, l.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
                } catch (e) { }
                try { btSound(world, l, 'entity.enderman.teleport', 1.0, 0.7); } catch (e) { }
                moved++;
            } catch (e) { }
        }

        if (moved > 0) {
            try {
                btSound(world, l, 'entity.wither.spawn', 0.5, 1.5);
            } catch (e) { }
        }
    } catch (e) {
        try { ref.drag = null; ref.mode = 'idle'; } catch (e2) { }
    }
}

// 列出范围内"该拉"的玩家（跳过创造/旁观/已经在圈内的人）
function btDragPlayers(world, bossLoc) {
    var out = [];
    try {
        var wname = '' + world.getName();
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if (('' + p.getWorld().getName()) !== wname) continue; } catch (e) { continue; }
            var gm = '';
            try { gm = '' + p.getGameMode().name(); } catch (e) { }
            if (gm.indexOf('SPECTATOR') >= 0) continue;
            if (gm.indexOf('CREATIVE') >= 0 && !BT_CREATIVE_FALLBACK) continue;
            try { if (!p.isOnline()) continue; } catch (e) { continue; }
            var d2 = 0;
            try { d2 = p.getLocation().distance(bossLoc); } catch (e) { continue; }
            // ★ 只判上限（64 格），**不判下限** —— 挖三填一的玩家就在 1～2 格内，
            //   早期那个"4 格内不拉"恰好把唯一需要被拉的人放过了
            if (d2 > BT_DRAG_RANGE) continue;
            out.push(p);
        }
    } catch (e) { }
    return out;
}

// 从 lock 表里取回 Player（按 uuid 找已在线的玩家；取不到就跳过）
function btDragPlayerById(world, d, idx) {
    try {
        var keys = [];
        for (var k in d.hit) keys.push(k);
        if (idx < 0 || idx >= keys.length) return null;
        var uid = keys[idx];
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try {
                if (('' + p.getUniqueId()) === ('' + uid)) {
                    if (('' + p.getWorld().getName()) !== ('' + world.getName())) return null;
                    return p;
                }
            } catch (e) { }
        }
    } catch (e) { }
    return null;
}
function btDragCount(d) {
    try { var n = 0; for (var k in d.hit) n++; return (n > BT_DRAG_CAP ? BT_DRAG_CAP : n); }
    catch (e) { return 0; }
}

// ★ 「该拉人了吗？」—— 64 格内**还有没被拉过的人**就放（三个阶段共用）
//   ⚠⚠ 2026-10-07 语义**改过**：
//     旧版 = 「任意一个目标在 `trig` 格外就放」—— **挖三填一的玩家永远不满足**
//     （他就站在 boss 旁边 1～2 格），所以技能一次都不会触发。
//     现在 = 「64 格内**有任何人**就放」（`trig <= 0` 时不设距离门槛）。
//     `ref.dragged` 记着"这一轮已经拉过谁"，避免同一个人被反复瞬移。
function btShouldDrag(ref, trig) {
    try {
        if (ref == null || !btAlive(ref.ent)) return false;
        var ent = ref.ent, world = ent.getWorld();
        var el = ent.getLocation();
        var wname = '' + world.getName();
        // 本轮已拉过的人（uuid 串），拉过就不再拉第二遍
        var done = ref.dragged;
        if (done == null) { done = {}; ref.dragged = done; }

        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = it.next();
            if (p == null) continue;
            try { if (('' + p.getWorld().getName()) !== wname) continue; } catch (e) { continue; }
            var gm = '';
            try { gm = '' + p.getGameMode().name(); } catch (e) { }
            if (gm.indexOf('SPECTATOR') >= 0) continue;
            if (gm.indexOf('CREATIVE') >= 0 && !BT_CREATIVE_FALLBACK) continue;
            try { if (!p.isOnline()) continue; } catch (e) { continue; }
            var uid = '';
            try { uid = '' + p.getUniqueId(); } catch (e) { continue; }
            if (done[uid] === 1) continue;                     // 这轮已经拉过
            var pl = null;
            try { pl = p.getLocation(); } catch (e) { continue; }
            var d2 = 0;
            try { d2 = pl.distance(el); } catch (e) { continue; }
            if (d2 > BT_DRAG_RANGE) continue;                  // 64 格外不追
            // `trig > 0` 时保留"多远以外才放"的老语义（默认 0 = 不设门槛）
            if (trig > 0 && d2 <= trig) continue;
            return true;
        }
    } catch (e) { }
    return false;
}

// ★ 妄想"忠于队伍"兜底：每拍把「指向自家卫道士」的 target 清掉。
//   真正的拦截在 EntityTargetEvent（同步、高优先级）里，这里只是双保险。
function btS2VexLoyal(ref) {
    try {
        if (ref == null || !btAlive(ref.ent)) return;
        var world = ref.ent.getWorld();
        var ents = world.getNearbyEntities(ref.ent.getLocation(), 24, 12, 24);
        var it = ents.iterator();
        while (it.hasNext()) {
            var v = it.next();
            if (btTag(v) !== BT_S2_VEX_TAG || !btAlive(v)) continue;
            var tg = null;
            try { tg = v.getTarget(); } catch (e) { tg = null; }
            if (tg == null || !btOurs(tg)) continue;
            try { v.setTarget(null); } catch (e) { }
        }
    } catch (e) { }
}

// ★ 清掉全服所有"我们家的妄想"（阶段切换 / 收摊 / reload 残留都靠它）
function btS2VexPurge() {
    try {
        var ws = getServer().getWorlds();
        for (var i = 0; i < ws.size(); i++) {
            var w = ws.get(i);
            if (w == null) continue;
            var ents = null;
            try { ents = w.getEntities(); } catch (e) { ents = null; }
            if (ents == null) continue;
            var it = ents.iterator();
            while (it.hasNext()) {
                var en = it.next();
                if (en == null) continue;
                if (btTag(en) !== BT_S2_VEX_TAG) continue;
                try { en.remove(); } catch (e) { }
            }
        }
    } catch (e) { }
}

// ★★ reload 残留清扫：脚本里的 JS 对象会被重建，旧实例留下的
//   「妄想」和「飞斧盔甲架」没人能认领 ⇒ 启动时按**记分板标签**扫一遍清掉。
//   （`/rsc reload` 不会取消旧脚本的 runLater，所以飞斧有可能停在半路）
function btSweepReloadLeftovers() {
    try {
        var ws = getServer().getWorlds();
        var n = 0;
        for (var i = 0; i < ws.size(); i++) {
            var w = ws.get(i);
            if (w == null) continue;
            var ents = null;
            try { ents = w.getEntities(); } catch (e) { ents = null; }
            if (ents == null) continue;
            var it = ents.iterator();
            while (it.hasNext()) {
                var en = it.next();
                if (en == null) continue;
                var tags = null;
                try { tags = en.getScoreboardTags(); } catch (e) { tags = null; }
                if (tags == null) continue;
                var isVex = false, isAxe = false;
                try { if (tags.contains('gs_vex')) isVex = true; } catch (e) { }
                try { if (tags.contains('gs_axe')) isAxe = true; } catch (e) { }
                if (btTag(en) === BT_S2_VEX_TAG) isVex = true;
                if (!isVex && !isAxe) continue;
                // ★ 正在被本实例使用的斧子不动（只清"无主"的）
                if (isAxe) {
                    var owned = false;
                    for (var k in BT_ACTIVE) {
                        var r = BT_ACTIVE[k];
                        try {
                            if (r != null && r.axeStand != null && r.axeStand === en) { owned = true; break; }
                        } catch (e2) { }
                    }
                    if (owned) continue;
                }
                try { en.remove(); n++; } catch (e) { }
            }
        }
        if (n > 0) {
            try { getServer().getLogger().info('[构思技艺] 碎念执：已清理 ' + n + ' 个 reload 残留（妄想 / 飞斧）'); } catch (e) { }
        }
    } catch (e) { }
}

// ==================================================================
// 整条线收摊（三阶段全部结束 / 出错兜底）
// ==================================================================
function btCleanup(ref) {
    try {
        if (ref == null || ref.done) return;
        ref.done = true;
        try { btBarDrop(ref); } catch (e) { }
        try { btMusicStopAll(ref); } catch (e) { }    // ★ BGM 一并停掉
        // ★ 飞斧 / 妄想一并收掉（世代号让旧链作废 + 实体直接 remove）
        try { ref.axeGen = (ref.axeGen == null ? 0 : ref.axeGen) + 1; } catch (e) { }
        try { if (ref.axeStand != null) { ref.axeStand.remove(); ref.axeStand = null; } } catch (e) { }
        try { ref.axe = null; ref.mode = 'idle'; } catch (e) { }
        // ★ 读条拉人也一并作废
        try { ref.dragGen = (ref.dragGen == null ? 0 : ref.dragGen) + 1; } catch (e) { }
        try { ref.drag = null; } catch (e) { }
        try { btS2VexPurge(); } catch (e) { }

        // 万一还留着残兵（中间那只死了第三只没生成成功之类）
        try {
            if (btAlive(ref.ent)) {
                var eq = ref.ent.getEquipment();
                if (eq != null) { try { eq.clear(); } catch (e) { } }
                try { ref.ent.remove(); } catch (e) { }
            }
        } catch (e) { }
        ref.ent = null;

        // ★ 解锁"这个玩家还能再召唤"
        try {
            if (ref.owner != null && BT_ACTIVE['' + ref.owner] === ref) delete BT_ACTIVE['' + ref.owner];
        } catch (e) { }
    } catch (e) { }
}

// ==================================================================
// 召唤入口
// ==================================================================
function btSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();

        // ① 这条线上还有没打完的 → 静默忽略（**三阶段全死**才能再召）
        var old = BT_ACTIVE[uuid];
        if (old != null && !old.done) return false;

        var world = p.getWorld();
        var base = p.getLocation();
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { }
        var rad = yaw * Math.PI / 180.0;
        var Loc = Java.type('org.bukkit.Location');
        var lx = base.getX() + (-Math.sin(rad)) * BT_SPAWN_AHEAD;
        var lz = base.getZ() + (Math.cos(rad)) * BT_SPAWN_AHEAD;
        var ly = btSafeY(world, lx, lz, base.getY(), BT_S1_HEIGHT);
        var loc = new Loc(world, lx, ly, lz, yaw, 0.0);

        var ent = btSpawnEntity(world, loc, ['PILLAGER'], BT_S1_NAME, BT_S1_HP, BT_S1_DMG, 'wangnian1');
        if (ent == null) return false;
        // ★ 立刻清空双手（把原版掠夺者自带的弩拿走）
        try { btS1Hands(ent); } catch (e) { }

        var ref = {
            owner: uuid,
            stage: 1,
            ent: ent,
            maxHp: BT_S1_HP,
            mode: 'idle',
            cd1: btNow() - BT_S1_FANG_CD + BT_FIRST_MS,      // 出场保护期内不放技能
            cd2: btNow() - BT_S1_BLINK_CD + BT_FIRST_MS,
            cd3: btNow() - BT_S2_AXE_CD + BT_FIRST_MS,        // ★ 飞斧冷却（第二阶段才用到）
            cd4: btNow() - BT_DRAG_CD + BT_DRAG_FIRST_MS,     // ★ 锁链拽回（三阶段共用）：出场延迟 **10 秒**，与其他技能错开
            atkAt: btNow() - BT_S1_ATK_CD + BT_FIRST_MS,     // ★ 本体射击：出场保护期后才开始
            readyAt: btNow() + BT_FIRST_MS,
            hpFixUntil: btNow() + BT_HP_FIX_MS,   // ★ 血量保护窗口（详见 btApplyHp 注释）
            hpOk: false,                          // ★ 还没确认血量写成功
            hpScale: 1.0,                         // ★ 伤害缩放（1.0 = 正常）
            bar: null, barNear: {},
            fang: null, blink: null, slash: null, slam: null,
            drag: null, dragGen: 0,
            dragged: {},        // ★ "本轮已拉过谁"（uuid → 1），拉过就不再拉第二遍
            axe: null, axeStand: null, axeGen: 0,
            transitioning: false,
            done: false,
            music: {},          // ★ BGM：uuid -> {p, at, stage}
            lw: world, lx: lx, ly: ly, lz: lz
        };

        ref.bar = btBarMake('幻念僧');
        try { btBarSync(ref); } catch (e) { }
        // ★ 一阶段 BGM：Precipice，循环播放
        try { btMusicLoop(ref); } catch (e) { }

        BT_ACTIVE[uuid] = ref;

        try { btSound(world, loc, 'entity.evoker.prepare_summon', 1.0, 1.0); } catch (e) { }
        try { btSound(world, loc, 'entity.wither.spawn', 0.4, 1.4); } catch (e) { }
        try {
            var pt = btP(['PORTAL', 'SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL']);
            btFx(world, pt, lx, ly + 1.0, lz, 30, 0.5, 0.6, 0.5, 0.05);
        } catch (e) { }

        runLater(function () { btTick(ref); }, BT_TICK);
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 妄念：召唤出错 ' + e); } catch (e2) { }
        return false;
    }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function btIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === BT_ITEM_ID) return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === BT_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

function btIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return btIsOurItem(hand);
    } catch (e) { return false; }
}

function btConsume(p) {
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
var BT_REG_DEATH = false;
var BT_REG_SPELL = false;

// ★ 清掉落：所有阶段的原版掉落物（绿宝石 / 铁斧 / 不死图腾 …）一律清空
function btRegisterDeath() {
    if (BT_REG_DEATH) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent');
        if (DeathEv == null) return false;

        function dh(e) {
            try {
                var ent = e.getEntity();
                if (ent == null) return;
                if (!btOurs(ent)) return;
                // ★ 原版掉落物全部删除：装备（含 Phase 1 的弩）+ 背包 + 战利品表 + 经验
                try { ent.getEquipment().clear(); } catch (err) { }
                try { var inv = ent.getInventory(); if (inv != null) inv.clear(); } catch (err) { }
                try {
                    var LootTables = Java.type('org.bukkit.loot.LootTables');
                    if (LootTables.EMPTY != null) ent.setLootTable(LootTables.EMPTY);
                } catch (err) { }
                try { e.getDrops().clear(); } catch (err) { }
                // 清完装备后再清一遍（有些服务端会把刚清掉的装备又塞回 drops）
                try { e.getDrops().clear(); } catch (err) { }
                try { e.setDroppedExp(0); } catch (err) { }
            } catch (err) { }
        }

        try {
            pm.registerEvent(DeathEv, plugin, Priority.HIGHEST,
                function (l, e) { dh(e); }, plugin);
            BT_REG_DEATH = true;
            return true;
        } catch (e1) {
            try { getServer().getLogger().warning('[构思技艺] 妄念：死亡掉落拦截注册失败 ' + e1); } catch (e3) { }
            return false;
        }
    } catch (e) { return false; }
}

// ★ 去掉**唤魔者的原版技能**（尖牙 / 恼鬼 / 咩咩 / 致盲）+ 龙息弹不炸方块 + 不掉地形
function btRegisterSpell() {
    if (BT_REG_SPELL) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var ok = 0;

        // ① EntityCastSpellEvent：所有原版施法（召唤尖牙 / 恼鬼 / 咩咩 / 致盲）一律取消
        try {
            var SpellEv = Java.type('org.bukkit.event.entity.EntityCastSpellEvent');
            if (SpellEv != null) {
                function sh(e) {
                    try {
                        var ent = e.getEntity();
                        if (ent == null || !btOurs(ent)) return;
                        try { e.setCancelled(true); } catch (err) { }
                        // 顺手把当前施法动作也清掉，别卡一个姿势
                        try {
                            var SC = Java.type('org.bukkit.entity.Spellcaster');
                            if (SC != null && ent instanceof SC) ent.setSpell(SC.Spell.NONE);
                        } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(SpellEv, plugin, Priority.HIGHEST, function (l, e) { sh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ② 伤害兜底：our boss 造成的**非近战**伤害（尖牙的魔法伤害）全部免掉，
        //    免得出现"鳄鱼嘴多打一下"的**超伤**
        try {
            var DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent');
            if (DmgEv != null) {
                function dmh(e) {
                    try {
                        var src = e.getDamager();
                        var vic = e.getEntity();
                        if (src == null || vic == null) return;

                        var cause = '';
                        try { cause = '' + e.getCause().name(); } catch (err) { cause = ''; }

                        // ★★ 自己人打自己人 ⇒ 一律免伤（2026-10-06 新增）
                        //   妄想（`wangnian2_vex`）与卫道士（`wangnian2`）都带 gs:boss3 标记，
                        //   所以这一条同时挡住：妄想打卫道士、卫道士打妄想、其它阶段互殴。
                        if (btOurs(src) && btOurs(vic)) {
                            try { e.setCancelled(true); } catch (err) { }
                            try { e.setDamage(0.0); } catch (err) { }
                            return;
                        }

                        // ★ 2026-10-06：自家的**弩箭** ⇒ 伤害统一改成 BT_S1_ARROW_DMG（12 点）
                        //   （箭头 entity 自己没标记，得往上追一层 shooter）
                        try {
                            if (typeof src.getShooter === 'function') {
                                var shooter = null;
                                try { shooter = src.getShooter(); } catch (err2) { shooter = null; }
                                if (shooter != null && btOurs(shooter)) {
                                    if (btOurs(vic)) {                 // 打到自己人 ⇒ 免伤
                                        try { e.setCancelled(true); } catch (err2) { }
                                        try { e.setDamage(0.0); } catch (err2) { }
                                    } else {                           // 打到别人 ⇒ 固定 12 点
                                        try { e.setDamage(BT_S1_ARROW_DMG); } catch (err2) { }
                                    }
                                    return;
                                }
                            }
                        } catch (err) { }

                        if (btOurs(src) && cause.indexOf('ATTACK') < 0) {
                            // 我们自己 boss 造成的**非近战**伤害 ⇒ 一律免掉
                            //    （典型：尖牙自带的魔法伤害 —— 我们要的 15 点是脚本手动打的，
                            //     不然会变成 15 + 6 的"超伤"）
                            try { e.setCancelled(true); } catch (err) { }
                            try { e.setDamage(0.0); } catch (err) { }
                            return;
                        }
                        // ★ 2026-10-06：龙息弹已删除，原来那句"龙息弹打到自家 boss 免伤"随之移除
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(DmgEv, plugin, Priority.HIGHEST, function (l, e) { dmh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        // ③ ★★ 血量降级兜底（2026-10-06）：万一 `MAX_HEALTH` 死活写不进去
        //    （某些服务端限制 / 别的插件压着），就按 `hpScale` 把受到的伤害等比缩小
        //    ⇒ 等效血量照样等于设计值。注册基类 `EntityDamageEvent`（子类事件也会派发过来），
        //      所以近战、箭、摔落、火焰、魔法全都能覆盖。
        try {
            var DmgBaseEv = Java.type('org.bukkit.event.entity.EntityDamageEvent');
            if (DmgBaseEv != null) {
                function hph(e) {
                    try {
                        var vic = e.getEntity();
                        if (vic == null || !btOurs(vic)) return;
                        var rf = btRefOf(vic);
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

        // ④ ★★ 阵营：自己人不互相索敌（2026-10-06 新增）
        //   妄想（恼鬼）原版会就近打任何生物 ⇒ 必须掐掉它对自家卫道士的索敌，
        //   同时也保证卫道士不会反过来打妄想（第三层保险，前两层是命中判定 + 伤害事件）。
        try {
            var TargetEv = Java.type('org.bukkit.event.entity.EntityTargetEvent');
            if (TargetEv != null) {
                function tgh(e) {
                    try {
                        var ent = e.getEntity();
                        var tgt = e.getTarget();
                        if (ent == null || tgt == null) return;
                        if (!btOurs(ent)) return;
                        if (!btOurs(tgt)) return;
                        try { e.setCancelled(true); } catch (err) { }
                        try { ent.setTarget(null); } catch (err) { }
                    } catch (err) { }
                }
                try {
                    pm.registerEvent(TargetEv, plugin, Priority.HIGHEST, function (l, e) { tgh(e); }, plugin);
                    ok++;
                } catch (e1) { }
            }
        } catch (e) { }

        BT_REG_SPELL = (ok > 0);
        if (BT_REG_SPELL) {
            try { getServer().getLogger().info('[构思技艺] 妄念：原版施法 / 爆炸 / 阵营拦截 已启用（' + ok + ' 条）'); } catch (e) { }
        }
        return BT_REG_SPELL;
    } catch (e) { return false; }
}

// ★ 手持本物品右键方块 → 方块交互一律取消（和「冰」同一个套路）
var BT_GUARD_OK = false;
function btRegisterGuard() {
    if (BT_GUARD_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');
        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
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
                if (!btIsOurItem(h)) return;
                e.setCancelled(true);
            } catch (err) { }
        }

        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST, function (l, e) { handler(e); }, plugin);
            BT_GUARD_OK = true;
            return true;
        } catch (e1) { return false; }
    } catch (e) { return false; }
}

// ==================================================================
// 入口
// ==================================================================
function onUse(event) {
    try {
        try { btRegisterDeath(); } catch (e) { }    // 惰性重试
        try { btRegisterSpell(); } catch (e) { }    // 惰性重试
        try { btRegisterGuard(); } catch (e) { }    // 惰性重试

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        if (!btIsMainHandUse(event, p)) return;

        var uuid = '' + p.getUniqueId();
        var now = btNow();
        var last = BT_LAST[uuid];
        if (last != null && (now - (0 + last)) < 500) return;
        BT_LAST[uuid] = now;

        if (!btSummon(p)) return;
        if (BT_CONSUME) btConsume(p);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 妄念 onUse 出错: ' + err); } catch (e) { }
    }
}

// ★ 玩家下线：**不删 boss**（同世界别人可能正在打），
//  这条线的归属也留着 —— 想换装备或者重开可以直接上线接着打。

// ==================================================================
// ★★ 启动自检（2026-10-06c）—— 把三阶段目标血量打到后台，
//    用来确认"服务器确实加载了这份新脚本"（看不到这行 = 跑的还是旧脚本，要重启服务器）。
//    如果游戏里血量还是不对，后台会再出现一行「妄念 血量设置失败(...)」写明
//    目标值 / 实际上限 / 实际血量 / 用到的属性键 —— 把那行发出来就能精确定位。
// ==================================================================
try {
    getServer().getLogger().info('[构思技艺] 妄念 三阶段目标血量：幻念僧=' + BT_S1_HP
        + '（掠夺者·弩） 碎念执=' + BT_S2_HP + ' 妄念魔=' + BT_S3_HP);
} catch (e) { }

// ★ 碎念执「星环飞斧」参数自检（看到这行 = 服务器确实加载了这份新脚本）
try {
    getServer().getLogger().info('[构思技艺] 碎念执 技能2 星环飞斧：蓄力=' + (BT_S2_AXE_CHARGE / 20.0)
        + '秒 射程=' + BT_S2_AXE_RANGE + '格 伤害=' + BT_S2_AXE_DMG
        + ' 冷却=' + (BT_S2_AXE_CD / 1000) + '秒 ｜ 妄想：伤害=' + BT_S2_VEX_DMG
        + ' 血量=' + BT_S2_VEX_HP + ' 存活=' + (BT_S2_VEX_LIFE / 20.0) + '秒（与卫道士同队）');
} catch (e) { }

// ★★★ 第八十五 / 八十六批：掉落自检
//   （用户「妄想碎片也改成第三阶段，固定掉落6个」
//     「妄念一阶段掉落物删除，二阶段换成固定掉落一把残樱，
//       删除残樱配方，改成击败二阶段妄念掉落」）
try {
    getServer().getLogger().info('[构思技艺] 妄念 掉落：妄想碎片 (' + BT_FRAG_ID + ')'
        + ' **只有第三阶段（妄念魔）**掉 ' + BT_FRAG_DROP[2] + ' 个'
        + ' ｜ 一阶段 ' + BT_FRAG_DROP[0] + ' 个 / 二阶段 ' + BT_FRAG_DROP[1] + ' 个（都不掉）');
} catch (e) { }

try {
    getServer().getLogger().info('[构思技艺] 妄念 掉落：残樱 (' + BT_CHERRY_ID + ')'
        + ' **只有第二阶段（碎念执）**掉 ' + BT_CHERRY_DROP[1] + ' 把'
        + ' ｜ 一阶段 ' + BT_CHERRY_DROP[0] + ' 把 / 三阶段 ' + BT_CHERRY_DROP[2] + ' 把（都不掉）'
        + ' ｜ 配方已删除（recipe_type = GS_RT_WANGNIAN_S2）');
} catch (e) { }

// ★ 通用「锁链拽回」参数自检（三阶段共用）
try {
    getServer().getLogger().info('[构思技艺] 锁链拽回（三阶段通用·反挖三填一）：出场延迟=' + (BT_DRAG_FIRST_MS / 1000)
        + '秒 冷却=' + (BT_DRAG_CD / 1000)
        + '秒 读条=' + (BT_DRAG_READ / 20.0) + '秒 范围=' + BT_DRAG_RANGE
        + '格 落点半径=' + BT_DRAG_RING + ' 单次最多=' + BT_DRAG_CAP
        + '人 ｜ 不设距离门槛（64 格内一律拉）');
} catch (e) { }

// ==================================================================
// ★ reload 残留清扫：启动 3 秒后扫一遍全世界，把上一份脚本留下的
//   「妄想」和「飞斧盔甲架」（靠 gs:vex / gs:axe 记分板标签识别）清掉。
// ==================================================================
try {
    runLater(function () { try { btSweepReloadLeftovers(); } catch (e) { } }, 60);
} catch (e) { }

