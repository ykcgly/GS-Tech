/* ==========================================================
 * 构思技艺 (GS) —— 「如听仙乐耳暂明」
 * items.yml 里由  script: gs_song  引用；入口函数 onUse(event)
 * ----------------------------------------------------------
 * 右键：放一声**小恶魂的叫声**（`entity.ghastling.ambient`）
 *   · **不消耗**物品（可以反复按）。
 *   · ★★ 第五十五批：用户「把如听仙乐耳暂名的音效改回小恶魂的叫声」
 *     ⇒ 第五十四批那段「播放整首《Nyan Cat》红石音乐」的曲谱 + 播放引擎
 *       **整个删掉了**，回到最简单的一声叫。
 *       （想再要红石音乐，用 `_check/nbs_to_js.py` 重新生成即可。）
 *   · **冷却 0.5 秒**（`SG_CD_MS`）：只是防连点，不是长冷却。
 *     一次右键会连发两个事件（主手/副手各一个）⇒ 不拦就会「啪」地叫两声。
 *   · 冷却中**静默忽略**（不提示、不出声）。
 *   · **无法与任何方块交互**：手持本物品右键方块时，方块的功能一律不触发
 *     （不开箱子 / 不耕田 / 不放方块 / 不点按钮…），只走本脚本的音效。
 * ----------------------------------------------------------
 * ★★ 只有**按的那个人自己**听得到（走 p.playSound）。
 *    想让**附近所有人都听到**，把下面 SG_OWNER_ONLY 改成 false 就行。
 * ★ 「无法交互」的实现顺序（和「麦友的酒」拦桶同一个套路）：
 *   Slimefun 在 NORMAL 优先级发 PlayerRightClickEvent（触发 onUse），
 *   我们注册在 HIGHEST → 一定在 onUse 之后才 setCancelled，
 *   所以「音效照放、方块交互被拦」，两者互不影响。
 * ========================================================== */

// ---------------- 参数 ----------------
var SG_ITEM_ID     = 'GS_SONG';
var SG_SOUND       = 'entity.ghastling.ambient';   // ★ 小恶魂的叫声
var SG_VOL         = 1.00;    // 音量
var SG_PIT       = 1.00;    // 音高（1.0 = 原音）
var SG_CD_MS       = 500;     // 0.5 秒防连点（不是长冷却）
var SG_OWNER_ONLY  = true;    // true = 只有按的人听得到；false = 附近所有人都听得到

// 加载标记：后台看到这行，说明脚本本身没问题
try {
    getServer().getLogger().info('[构思技艺] gs_song 已加载 · 小恶魂的叫声 ' + SG_SOUND
        + ' · 防连点 ' + SG_CD_MS + 'ms');
} catch (e) { }

// ---------------- 冷却（按玩家记） ----------------
var SG_LAST = {};     // uuid -> 上次使用的时间戳(ms)

function sgNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function sgCooldownLeft(p) {
    try {
        if (SG_CD_MS <= 0) return 0;
        var v = SG_LAST['' + p.getUniqueId()];
        if (v == null) return 0;
        var left = (0 + v) + SG_CD_MS - sgNow();
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function sgMarkUsed(p) {
    try { SG_LAST['' + p.getUniqueId()] = sgNow(); } catch (e) { }
}

// ---------------- 判定「手里拿的是不是本物品」 ----------------
function sgIsOurItem(stack) {
    try {
        if (stack == null) return false;
        // ① RSC 的判定（最准）
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === SG_ITEM_ID) return true;
        } catch (e) { }
        // ② Slimefun 标准 API 兜底
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === SG_ITEM_ID) return true;
        } catch (e) { }
        return false;
    } catch (e) { return false; }
}

// ---------------- 判定「这次触发算不算数」（只认主手） ----------------
function sgIsMainHandUse(event, p) {
    try {
        var slot = '';
        try { slot = '' + event.getHand().name(); } catch (e) { slot = ''; }
        if (slot.length === 0) {
            try { slot = '' + event.getInteractEvent().getHand().name(); } catch (e2) { slot = ''; }
        }
        if (slot.indexOf('OFF') >= 0) return false;

        var hand = null;
        try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
        return sgIsOurItem(hand);
    } catch (e) { return false; }
}

// ---------------- 拦截：手持本物品右键方块 → 方块交互一律取消 ----------------
var SG_GUARD_OK = false;

function sgPlugin() {
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

function sgRegisterGuard() {
    if (SG_GUARD_OK) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = sgPlugin();
        if (plugin == null) return false;

        var InteractEv = null;
        try { InteractEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { InteractEv = null; }
        if (InteractEv == null) return false;

        function handler(e) {
            try {
                var act = '';
                try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                if (act.indexOf('RIGHT_CLICK') < 0) return;    // 只拦右键（左键留给玩家正常挖方块）

                // ★ 只认主手
                var hs = '';
                try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;

                var h = null;
                try { h = e.getItem(); } catch (e2) { h = null; }
                if (h == null) return;
                if (!sgIsOurItem(h)) return;

                e.setCancelled(true);      // 方块功能不触发（箱子/耕地/按钮/放置…全拦）
            } catch (err) { }
        }

        try {
            pm.registerEvent(InteractEv, plugin, Priority.HIGHEST,
                function (l, e) { handler(e); }, plugin);
            SG_GUARD_OK = true;
        } catch (e1) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                pm.registerEvent(InteractEv, listener, Priority.HIGHEST, exec, plugin);
                SG_GUARD_OK = true;
            } catch (e2) { SG_GUARD_OK = false; }
        }
        return SG_GUARD_OK;
    } catch (e) { return false; }
}

try {
    if (sgRegisterGuard()) {
        try { getServer().getLogger().info('[构思技艺] 如听仙乐耳暂明：方块交互拦截 已启用'); } catch (e) { }
    }
} catch (e) { }

// ---------------- 入口 ----------------
function onUse(event) {
    try {
        // 惰性重试：加载时插件可能还没就绪
        try { sgRegisterGuard(); } catch (e) { }

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        // ★ 只认主手触发
        if (!sgIsMainHandUse(event, p)) return;

        // 0.5 秒防连点：冷却中**静默忽略**
        if (sgCooldownLeft(p) > 0) return;
        sgMarkUsed(p);

        // ★ 就叫一声（小恶魂的叫声）
        try {
            var loc = p.getLocation();
            if (SG_OWNER_ONLY) p.playSound(loc, SG_SOUND, SG_VOL, SG_PIT);
            else               p.getWorld().playSound(loc, SG_SOUND, SG_VOL, SG_PIT);
        } catch (e) { }
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 如听仙乐耳暂明 onUse 出错: ' + err); } catch (e) { }
    }
}
