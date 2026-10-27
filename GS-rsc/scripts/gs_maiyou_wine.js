/* ==========================================================
 * 构思技艺 (GS) —— 「麦友的酒」
 * items.yml 里由  script: gs_maiyou_wine  引用；入口函数 onUse(event)
 * ----------------------------------------------------------
 * 物品材质是**桶装河豚** PUFFERFISH_BUCKET（不会像「河豚」那样右键被吃掉），
 * 原版「倒出河豚」的交互会被我们拦下 → 右键只走本脚本的召唤。
 * ----------------------------------------------------------
 * 右键一个方块：在**被点方块的「被点面」那一格**召唤一只河豚，
 * 名字叫「麦友的酒」，8 秒后**直接移除**（remove，不走死亡流程）。这只河豚——
 *   · **不会移动、不会掉下去**：关 AI / 关重力，而且每刻都被"钉"回出生点
 *     （位置写死、只换朝向），所以任何情况下都不可能位移或下坠
 *   · **原地 360° 转圈**：8 秒转 8 圈 → 160 刻共 2880°，每刻 18°
 *   · **不断变大变小**：优先用 SCALE 属性做平滑脉动（周期 0.5 秒），
 *                       缩放从 0.60 倍 → 2.20 倍（最大时约一个方块大）；
 *                       拿不到属性就退回河豚自身的「膨胀状态」来表现
 *   · **对任何生物都不造成伤害**（四道保险）：
 *       ① 关 AI ② 不可碰撞 ③ 每刻压回「小」状态
 *       ④ 同步监听器兜底 —— 凡是我们标记过的河豚造成的伤害，一律取消
 *   · **与"附近有没有玩家"无关**：行为全由我们自己的计时器驱动
 *
 * ★ onUse 的 event 是 Slimefun 的 PlayerRightClickEvent，
 *   它包着一个 Bukkit 的 PlayerInteractEvent —— 「点了哪个方块 / 哪个面」
 *   要从 getInteractEvent() 里取（也兼容直接把 block/face 放在事件上的版本）。
 * ★ 为什么用「被点面」而不是射线：
 *   之前用射线找方块，命中点可能在很远处/高处，而且"命中方块 + 上方一格"
 *   有可能正好落在实心方块里 → 表现为"从天上掉下来 / 一出来就窒息"。
 *   被点面那一格永远是"面向玩家的那一侧"，基本都是空气。
 * ★ 为什么用 teleport 而不是 setRotation 转圈：
 *   setRotation 只改服务端字段，不一定会同步给客户端。
 * ★ 使用冷却 8 秒（与河豚 8 秒寿命一致 → 同时最多一只）：
 *   冷却中右键**只提示剩余秒数**，既不召唤也不发歌词，避免提示词连环刷屏。
 * ========================================================== */

// 加载标记：后台看到这行，说明脚本本身没问题
try { getServer().getLogger().info('[构思技艺] gs_maiyou_wine 已加载'); } catch (e) { }

// ---------------- 依赖（逐个容错） ----------------
var MY_MARK = null;
try { MY_MARK = Java.type('org.bukkit.NamespacedKey').fromString('gs:maiyou_fish'); } catch (e) { MY_MARK = null; }

// 缩放属性：1.21.3+ 叫 SCALE，1.20.5~1.21.2 叫 GENERIC_SCALE
var MY_SCALE_KEY = null;
try {
    var MY_Attr = Java.type('org.bukkit.attribute.Attribute');
    try { MY_SCALE_KEY = MY_Attr.SCALE; } catch (e) { MY_SCALE_KEY = null; }
    if (MY_SCALE_KEY == null) { try { MY_SCALE_KEY = MY_Attr.valueOf('SCALE'); } catch (e) { MY_SCALE_KEY = null; } }
    if (MY_SCALE_KEY == null) { try { MY_SCALE_KEY = MY_Attr.GENERIC_SCALE; } catch (e) { MY_SCALE_KEY = null; } }
    if (MY_SCALE_KEY == null) { try { MY_SCALE_KEY = MY_Attr.valueOf('GENERIC_SCALE'); } catch (e) { MY_SCALE_KEY = null; } }
} catch (e) { MY_SCALE_KEY = null; }

// ---------------- 参数 ----------------
var MY_DURATION = 160;    // 存活 160 刻 = 8 秒
var MY_TURNS    = 8;      // 8 秒内转 8 圈（每刻 18°）
var MY_PULSE    = 10;     // 大小脉动周期 10 刻 = 0.5 秒（8 秒共 16 次）
var MY_MIN      = 0.60;   // 最小缩放（不变）
var MY_MAX      = 2.20;   // 最大缩放 —— 河豚基础模型约 0.4 格，×2.2 ≈ 接近一个方块
var MY_DIST     = 1.6;    // 右键空气时：出现在玩家面前多少格
var MY_NAME     = '§d麦友的酒';   // 河豚的名字（两侧动物同名）
var MY_COOLDOWN_MS = 8000;        // 使用冷却 8 秒（与河豚寿命一致 → 同时只存在一只，提示词不会连环刷屏）

// 提示词：前 4 秒逐句发出（每 20 刻 = 1 秒一句）
var MY_LYRIC_GAP = 20;
var MY_LYRICS = [
    '&d昨日一去不复回~哦耶耶~',
    '&d开心比什么都贵~',
    '&d覆水不能再收回~哦耶耶~',
    '&d桃花谢了有玫瑰~'
];

// ---------------- 冷却（按玩家记，避免右键连点把提示词刷屏） ----------------
var MY_LAST_USE = {};     // uuid -> 上次使用的时间戳(ms)

function myNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

// 返回剩余冷却毫秒（0 = 可以用了）；不消耗冷却
function myCooldownLeft(p) {
    try {
        if (MY_COOLDOWN_MS <= 0) return 0;
        var v = MY_LAST_USE['' + p.getUniqueId()];
        if (v == null) return 0;
        var left = (0 + v) + MY_COOLDOWN_MS - myNow();
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function myMarkUsed(p) {
    try { MY_LAST_USE['' + p.getUniqueId()] = myNow(); } catch (e) { }
}

// ---------------- 标记 ----------------
// ★ 用 STRING 而不是 BYTE：Nashorn 里 set(key, PDT.BYTE, 1) 的第三个参数
//   要装箱成 java.lang.Byte，容易静默失败（一旦失败，后面"清掉落/拦伤害"全部失准）。
//   字符串没有装箱歧义，最稳。
function myMark(ent) {
    try {
        if (MY_MARK == null) return;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        ent.getPersistentDataContainer().set(MY_MARK, PDT.STRING, '1');
    } catch (e) { }
}

function myIsMarked(ent) {
    try {
        if (MY_MARK == null) return false;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        var pdc = ent.getPersistentDataContainer();
        try { if (pdc.has(MY_MARK, PDT.STRING)) return true; } catch (e) { }
        try { if (pdc.has(MY_MARK, PDT.BYTE)) return true; } catch (e) { }   // 兼容旧标记
        return false;
    } catch (e) { return false; }
}

// ---------------- 取出「右键的那个方块 + 被点的面」 ----------------
function myClickedTarget(event) {
    var out = { block: null, face: null };
    try {
        // ① Slimefun 的 PlayerRightClickEvent 里包着 Bukkit 的 PlayerInteractEvent
        var ie = null;
        try { ie = event.getInteractEvent(); } catch (e) { ie = null; }
        if (ie != null) {
            try { out.block = ie.getClickedBlock(); } catch (e) { }
            try { out.face = ie.getBlockFace(); } catch (e) { }
        }

        // ② 事件本身也可能直接提供（部分是 Optional，就地拆包）
        if (out.block == null) {
            try {
                var b = event.getClickedBlock();
                if (b != null) { try { b = b.orElse(null); } catch (e) { } out.block = b; }
            } catch (e) { }
        }
        if (out.face == null) {
            try {
                var f = event.getClickedFace();
                if (f != null) { try { f = f.orElse(null); } catch (e) { } out.face = f; }
            } catch (e) { }
        }
    } catch (e) { }
    if (out.block == null) out.face = null;
    return out;
}

// ---------------- 召唤 ----------------
function mySpawn(p, block, face) {
    try {
        var ET  = Java.type('org.bukkit.entity.EntityType');
        var Loc = Java.type('org.bukkit.Location');
        var Vec = Java.type('org.bukkit.util.Vector');

        var loc = null;

        // ① 优先：被右键方块的「被点面」那一格（= 面向玩家的空气侧）
        if (block != null && face != null) {
            try {
                var t = block.getRelative(face);
                if (t != null) {
                    var b = t.getLocation();
                    loc = new Loc(p.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                }
            } catch (e) { loc = null; }
        }

        // ② 右键空气（或拿不到方块）→ 出现在玩家面前
        if (loc == null) {
            var base = p.getLocation();
            var d = base.getDirection();
            var v = new Vec(d.getX(), 0.0, d.getZ());
            if (v.lengthSquared() < 1.0E-6) v = new Vec(1.0, 0.0, 0.0);
            v.normalize();
            loc = new Loc(p.getWorld(),
                base.getX() + v.getX() * MY_DIST,
                base.getY() + 1.2,
                base.getZ() + v.getZ() * MY_DIST);
        }

        try { loc.setYaw(p.getLocation().getYaw()); } catch (e) { }
        try { loc.setPitch(0.0); } catch (e) { }

        var ent = p.getWorld().spawnEntity(loc, ET.PUFFERFISH);
        if (ent == null) return null;

        myFreeze(ent);
        myMark(ent);
        return ent;
    } catch (e) { return null; }
}

// 把河豚"定住"：不移动、不下坠、不被推动、不攻击、不乱叫
function myFreeze(ent) {
    if (ent == null) return;
    try { ent.setAI(false); } catch (e) { }                    // 不移动、不攻击
    try { ent.setGravity(false); } catch (e) { }               // 悬浮
    try { ent.setInvulnerable(true); } catch (e) { }           // 打不死（也不受缺水伤害）
    try { ent.setSilent(true); } catch (e) { }                 // 不乱叫
    try { ent.setCollidable(false); } catch (e) { }            // 不与任何实体碰撞
    try { ent.setCanPickupItems(false); } catch (e) { }
    try { ent.setRemoveWhenFarAway(false); } catch (e) { }     // 附近没人也不消失
    try { ent.setPersistent(true); } catch (e) { }
    try { ent.setFallDistance(0.0); } catch (e) { }
    try { ent.setCustomName(MY_NAME); } catch (e) { }          // 名字：麦友的酒
    try { ent.setCustomNameVisible(true); } catch (e) { }
    try {
        var Vec = Java.type('org.bukkit.util.Vector');
        ent.setVelocity(new Vec(0.0, 0.0, 0.0));
    } catch (e) { }

    // ★★ 从「源头」断掉掉落物：把战利品表换成空表。
    //    这样即使河豚提前死了（被别的插件杀 / 环境伤害），也一个物品都不会掉，
    //    不依赖事件监听器是否注册成功 —— 比"死亡事件里清 drops"更可靠。
    try {
        var LootTables = Java.type('org.bukkit.loot.LootTables');
        var empty = null;
        try { empty = LootTables.EMPTY; } catch (e) { empty = null; }
        if (empty != null) ent.setLootTable(empty);
    } catch (e) { }
}

// ---------------- 缩放 ----------------
function mySetScale(ent, s) {
    try {
        if (MY_SCALE_KEY == null) return false;
        var inst = ent.getAttribute(MY_SCALE_KEY);
        if (inst == null) return false;
        inst.setBaseValue(s);
        return true;
    } catch (e) { return false; }
}

// 河豚自身的「膨胀状态」：0 = 小 / 1 = 中 / 2 = 大（拿不到缩放属性时用它表现大小）
function mySetPuff(ent, level) {
    try {
        var Puff = Java.type('org.bukkit.entity.PufferFish$PuffState');
        var st = null;
        if (level <= 0) st = Puff.SHRUNK;
        else if (level === 1) st = Puff.MID;
        else st = Puff.FULL;
        if (st == null) return false;
        try { ent.setPufferState(st); return true; } catch (e) { }
        try { ent.setPuffState(st); return true; } catch (e) { }
        try { ent.setPuffState(level); return true; } catch (e) { }
    } catch (e) { }
    return false;
}

// ---------------- 每刻刷新：钉住位置 + 转圈 + 变大变小 ----------------
function myPulse(ent, anchor, i) {
    if (ent == null) return;
    var dead = false;
    try { dead = ent.isDead(); } catch (e) { dead = true; }
    if (dead) return;

    // ① 位置永远钉回出生点（写死坐标，只换朝向）→ 不可能移动、不可能掉下去
    try {
        var loc = anchor.clone();
        var per = 360.0 * MY_TURNS / MY_DURATION;              // 18°
        var yaw = (anchor.getYaw() + per * (i + 1)) % 360.0;
        loc.setYaw(yaw);
        loc.setPitch(0.0);
        ent.teleport(loc);
    } catch (e) { }

    // ② 每 20 刻重新"定身"一次（防其它插件/原版把它解开）
    if (i % 20 === 0) myFreeze(ent);

    // ③ 变大变小：正弦脉动（MY_MIN ~ MY_MAX）
    var s = 1.0;
    try {
        var mid = (MY_MIN + MY_MAX) / 2.0;
        var amp = (MY_MAX - MY_MIN) / 2.0;
        s = mid + amp * Math.sin(2.0 * Math.PI * i / MY_PULSE);
    } catch (e) { s = 1.0; }

    var usedScale = mySetScale(ent, s);
    if (usedScale) {
        // 缩放已由属性负责 → 把原版膨胀状态压回最小（别让它自己膨胀起来）
        try { mySetPuff(ent, 0); } catch (e) { }
    } else {
        // 没有缩放属性 → 就用膨胀状态表现大小（大/中/小）
        var lv = 0;
        if (s >= (MY_MIN + (MY_MAX - MY_MIN) * 0.66)) lv = 2;
        else if (s >= (MY_MIN + (MY_MAX - MY_MIN) * 0.33)) lv = 1;
        mySetPuff(ent, lv);
    }

    if (i + 1 >= MY_DURATION) {
        // 到点：直接移除（remove，不走死亡流程 → 不会有任何死亡表现、也绝无掉落物）
        try { ent.remove(); } catch (e) { }
        // 兜底：万一上一步没成，1 秒后再清一次
        runLater(function () {
            try { if (!ent.isDead()) ent.remove(); } catch (e) { }
        }, 20);
        return;
    }
    runLater(function () { myPulse(ent, anchor, i + 1); }, 1);
}

// ---------------- 提示词：一句一句地发（前 4 秒发完） ----------------
function myLyrics(p, idx) {
    if (idx >= MY_LYRICS.length) return;
    try { sendMessage(p, MY_LYRICS[idx]); } catch (e) { }
    runLater(function () { myLyrics(p, idx + 1); }, MY_LYRIC_GAP);
}

// ---------------- 兜底监听器：① 伤害取消 ② 掉落物清空 ③ 拦原版「桶」 ----------------
var MY_GUARD_OK = false;

// 判断某物品是不是「麦友的酒」—— 用来拦住原版「河豚桶」的倒河豚行为
function myIsOurItem(stack) {
    try {
        if (stack == null) return false;
        var tn = '';
        try { tn = '' + stack.getType().name(); } catch (e) { return false; }
        if (tn !== 'PUFFERFISH_BUCKET' && tn !== 'PUFFERFISH') return false;

        // ① RSC 的判定（最准）
        try {
            var sf = getSfItemByItem(stack);
            if (sf != null && ('' + sf.getId()) === 'GS_MAIYOU_WINE') return true;
        } catch (e) { }

        // ② Slimefun 标准 API 兜底
        try {
            var SFI = Java.type('io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem');
            var sf2 = SFI.getByItem(stack);
            if (sf2 != null && ('' + sf2.getId()) === 'GS_MAIYOU_WINE') return true;
        } catch (e) { }

        return false;
    } catch (e) { return false; }
}

function myRegisterGuard() {
    if (MY_GUARD_OK) return true;      // 已全部注册好，不再重复
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(evClass, handler) {
            try {
                pm.registerEvent(evClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(evClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var ok1 = false, ok2 = false, ok3 = false;

        // ① 我们标记过的河豚造成的伤害 → 一律取消
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { DmgEv = null; }
        if (DmgEv != null) {
            ok1 = reg(DmgEv, function (e) {
                try {
                    if (e.isCancelled()) return;
                    var d = null;
                    try { d = e.getDamager(); } catch (e2) { d = null; }
                    if (d == null) return;
                    var tn = '';
                    try { tn = '' + d.getType().name(); } catch (e2) { return; }
                    if (tn !== 'PUFFERFISH') return;
                    if (!myIsMarked(d)) return;          // 只认我们自己召的那只
                    e.setCancelled(true);
                } catch (err) { }
            });
        }

        // ② 我们标记过的河豚「死」的时候 → 清空掉落物与经验（一个都不掉）
        var DeathEv = null;
        try { DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent'); } catch (e) { DeathEv = null; }
        if (DeathEv != null) {
            ok2 = reg(DeathEv, function (e) {
                try {
                    var ent = null;
                    try { ent = e.getEntity(); } catch (e2) { ent = null; }
                    if (ent == null) return;
                    var tn = '';
                    try { tn = '' + ent.getType().name(); } catch (e2) { return; }
                    if (tn !== 'PUFFERFISH') return;
                    if (!myIsMarked(ent)) return;
                    try { if (e.getDrops() != null) e.getDrops().clear(); } catch (e2) { }
                    try { e.setDroppedExp(0); } catch (e2) { }
                } catch (err) { }
            });
        }

        // ③ 右键时拦住原版「河豚桶」的倒河豚行为（只走我们自己的召唤逻辑）
        //    ★ 只拦 RIGHT_CLICK：左键要留给玩家正常挖方块（手里拿桶 = 相当于空手挖）
        var InteractEv = null;
        try { InteractEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { InteractEv = null; }
        if (InteractEv != null) {
            ok3 = reg(InteractEv, function (e) {
                try {
                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                    if (act.indexOf('RIGHT_CLICK') < 0) return;

                    var h = null;
                    try { h = e.getItem(); } catch (e2) { h = null; }
                    if (h == null) return;
                    if (!myIsOurItem(h)) return;

                    e.setCancelled(true);      // 原版桶放置河豚 → 取消
                } catch (err) { }
            });
        }

        MY_GUARD_OK = (ok1 && ok2 && ok3);
        return MY_GUARD_OK;
    } catch (e) { return false; }
}

try {
    if (myRegisterGuard()) {
        try { getServer().getLogger().info('[构思技艺] 麦友的酒：伤害拦截 + 掉落清空 + 桶交互拦截 已启用'); } catch (e) { }
    }
} catch (e) { }

// ---------------- 入口 ----------------
function onUse(event) {
    try {
        // ★ 惰性重试：脚本加载时插件可能还没就绪（拿不到 plugin → 注册失败），
        //   这里在使用时再补一次，确保三个监听器最终一定装上（已装好就是空操作）
        try { myRegisterGuard(); } catch (e) { }

        var p = null;
        try { p = event.getPlayer(); } catch (e) { p = null; }
        if (p == null) return;

        // ★ 冷却 8 秒：冷却中直接拦下（既不召唤、也不发提示词，避免刷屏）
        var cdLeft = myCooldownLeft(p);
        if (cdLeft > 0) {
            try {
                var secs = Math.ceil(cdLeft / 1000.0);
                sendMessage(p, '&7酒还没醒，&f' + secs + ' &7秒后再来。');
            } catch (e) { }
            return;
        }

        var t = myClickedTarget(event);
        var ent = mySpawn(p, t.block, t.face);
        if (ent == null) {
            try { sendMessage(p, '&7酒洒了，河豚没浮上来。'); } catch (e) { }
            return;
        }

        // 出生点 = 之后每一刻都要"钉"回去的锚点
        var anchor = null;
        try { anchor = ent.getLocation().clone(); } catch (e) { anchor = null; }
        if (anchor == null) {
            try { ent.remove(); } catch (e) { }
            try { sendMessage(p, '&7酒洒了，河豚没浮上来。'); } catch (e) { }
            return;
        }

        // 到这里才算"真的用掉了" → 开始计时冷却（召唤失败时不占冷却）
        myMarkUsed(p);

        try { p.getWorld().playSound(p.getLocation(), 'entity.puffer_fish.blow_up', 1.0, 1.0); } catch (e) { }

        // 提示词：前 4 秒逐句发完
        try { myLyrics(p, 0); } catch (e) { }

        myPulse(ent, anchor, 0);
    } catch (err) {
        try { getServer().getLogger().warning('[构思技艺] 麦友的酒 onUse 出错: ' + err); } catch (e) { }
    }
}
