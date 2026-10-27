/* ==========================================================
 * 构思技艺 (GS) —— 「这你叮我」
 * items.yml 里由  script: gs_ding_me  引用；入口函数 onUse(event)
 * ----------------------------------------------------------
 * 右键：在玩家身上连响 **3 声经验球拾取音**（叮），0.4 秒内发完（t = 0 / 4 / 8 刻）
 *   · 三次固定同一个音效 `entity.experience_orb.pickup`
 *   · **消耗品**：每次右键消耗 1 个
 *   · **冷却 1 秒**（冷却中**静默忽略**：不提示、不消耗、不返还、不出声）
 *   · 使用后**返还 1 个「空的学识之瓶」**（Slimefun 的 FLASK_OF_KNOWLEDGE）
 *   · 使用后**直接 +1 级经验**（giveExpLevels(1)：加的是"等级"，不是经验值）
 * ----------------------------------------------------------
 * ★ 两个瓶子（Slimefun 的一对状态）：
 *     填充的 = **FILLED_FLASK_OF_KNOWLEDGE**（配方输入）
 *     空的   = **FLASK_OF_KNOWLEDGE**（使用后返还，常量 DM_EMPTY_ID）
 *   即"把瓶里的 1 级经验喝掉，瓶子变回空的还给你"。
 *   （若你的服务器/整合包改过这些 ID，用 /sf id 查手持物再对照改。）
 * ========================================================== */

// 加载标记：后台看到这行，说明脚本本身没问题
try { getServer().getLogger().info('[构思技艺] gs_ding_me 已加载'); } catch (e) { }

// ---------------- 参数 ----------------
var DM_SOUND     = 'entity.experience_orb.pickup';   // 经验球「叮」声
var DM_TIMES     = 3;                                // 固定响 3 声，都是这一个音效
var DM_GAP       = 4;                                // 每 4 刻一声 → t=0 / 4 / 8 刻（0.4 秒内）
var DM_VOL       = 1.0;
var DM_PITCH     = 1.0;
var DM_EMPTY_ID  = 'FLASK_OF_KNOWLEDGE';             // 返还的「空的学识之瓶」（Slimefun）
var DM_CD_MS     = 1000;                             // 冷却 1 秒

// ---------------- 冷却（按玩家记） ----------------
var DM_LAST = {};     // uuid -> 上次使用的时间戳(ms)

function dmNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function dmCooldownLeft(p) {
    try {
        if (DM_CD_MS <= 0) return 0;
        var v = DM_LAST['' + p.getUniqueId()];
        if (v == null) return 0;
        var left = (0 + v) + DM_CD_MS - dmNow();
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function dmMarkUsed(p) {
    try { DM_LAST['' + p.getUniqueId()] = dmNow(); } catch (e) { }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function dmIsOurItem(stack) {
    try {
        if (stack == null) return false;
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === 'GS_DING_ME') return true;
        } catch (e) { }
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === 'GS_DING_ME') return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

// ---------------- 判定「这次触发算不算数」 ----------------
// ★★ 只认主手：Slimefun 对**副手**也会派发 onUse，而消耗是按主手写的，
//    不拦就会出现"副手触发 → 不消耗物品却照样升级/给瓶子"的白嫖。
function dmIsMainHandUse(event, p) {
    try {
        // ① 能拿到触发的手就判断：副手一律不处理
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;

        // ② 主手必须确实拿着本物品
        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return dmIsOurItem(hand);
    } catch (e) { return false; }
}

// ---------------- 消耗主手 1 个 ----------------
function dmConsume(p) {
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

// ---------------- 给玩家一个「空的学识之瓶」（背包塞不下就掉脚边） ----------------
function dmGiveEmptyBottle(p) {
    try {
        var stack = null;
        // ① 优先用 RSC 的取物函数（能拿到 Slimefun 物品实例）
        try {
            var sf = getSfItemById(DM_EMPTY_ID);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }

        if (stack == null) {
            // 拿不到（ID 不对 / 该物品被禁用）→ 记一条日志，方便排查
            try { getServer().getLogger().warning('[构思技艺] 这你叮我：取不到 ' + DM_EMPTY_ID + '，未返还空瓶'); } catch (e) { }
            return;
        }

        var inv = p.getInventory();
        var rest = inv.addItem(stack);
        if (rest != null && !rest.isEmpty()) {
            var it = rest.values().iterator();
            while (it.hasNext()) {
                try { p.getWorld().dropItemNaturally(p.getLocation(), it.next()); } catch (e2) { }
            }
        }
    } catch (e) { }
}

// ---------------- 三声连响（每 4 刻一声） ----------------
function dmPlay(p, idx) {
    if (idx >= DM_TIMES) return;
    try { p.getWorld().playSound(p.getLocation(), DM_SOUND, DM_VOL, DM_PITCH); } catch (e) { }
    runLater(function () { dmPlay(p, idx + 1); }, DM_GAP);
}

// ---------------- 入口 ----------------
function onUse(event) {
    try {
        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        // ★ 只认主手触发：副手拿着本物品右键时，Slimefun 也会走到这里，
        //   但消耗是按主手算的 → 不拦就会出现"不消耗却升级/给瓶子"的白嫖
        if (!dmIsMainHandUse(event, p)) return;

        // 冷却 1 秒：冷却中**静默忽略**（不提示、不消耗、不返还、不出声）
        if (dmCooldownLeft(p) > 0) return;
        dmMarkUsed(p);

        // ① 消耗 1 个本体
        dmConsume(p);

        // ② 直接 +1 级经验（注意是"等级"，不是经验值）
        try { p.giveExpLevels(1); } catch (e) { }

        // ③ 返还 1 个「空的学识之瓶」
        dmGiveEmptyBottle(p);

        // ④ 三声「叮」，0.4 秒内发完
        dmPlay(p, 0);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 这你叮我 onUse 出错: ' + err); } catch (e) { }
    }
}
