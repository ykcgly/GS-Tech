/* ==========================================================
 * 构思技艺 (GS) —— 「释梦」右键效果
 * items.yml 里由  script: gs_release_dream  引用
 * 入口函数固定为 onUse(event)
 * ----------------------------------------------------------
 * 右键 → 朝视线方向打出一道**灵魂沙粒子**（SOUL）弹道：
 *   · 射程 15 格，**碰到实体方块就停下**（不穿墙）
 *   · 没命中生物 → 什么都不发生
 *   · 命中生物 → 从它**正上方 6 格**开始播放下坠动画；
 *     粒子**砸到生物身上那一刻**结算一次 **40 点**伤害（2026-10-06：10 → 25 → 40）；
 *     **只有 1 次下坠 = 1 次伤害**（2026-10-06：3 次 → 1 次）
 *   · 发射时音效 = **只有一个**：音符盒放在**羊毛**上、左键调音 6 次后的那一声
 *     （`block.note_block.guitar`，音高 = 音符盒 note 6 ≈ pitch 0.7071）
 *   · ★ **没有冷却、没有任何提示词**（2026-10-08 第六批）——
 *     原来的 0.3 秒冷却、以及「梦还没醒…」/「可以重新割开梦境」两句提示
 *     已**整段删除**：右键即发，不再有 CD，也不再有提示。
 *   ★ 已经**删掉「内爆/炸开」**，也**没有**任何会持续残留的粒子云
 *   ★ 2026-10-06：每段结算前会**清掉受击无敌帧**（`setNoDamageTicks(0)`）——
 *     否则两次右键的下坠时间点一重叠，后一发会被原版那 10 刻无敌时间吃掉（无伤）。
 *   ★ 2026-10-06：结算时**存下 velocity、打完立刻还原** ⇒ **只掉血、不产生任何击退**
 *     （原版 `damage()` 自带水平击退，会把怪/玩家推开）。
 *
 * ★★ 为什么全部用 runLater 链，而不用 runRepeating + task.cancel()：
 *   RSC 的定时器是这么实现的（ScriptEval#setup）：
 *     AtomicReference<BukkitTask> task = new AtomicReference<>();
 *     Bukkit.getScheduler().runTaskTimer(..., ta -> { r.apply(...); task.set(ta); }, l, t);
 *     return task.get();          // ← 同步返回！此时回调还没跑过
 *   也就是说 **delay > 0 时返回值基本就是 null**，
 *   `task.cancel()` 要么 NPE、要么被 try 吞掉 —— 任务**永远停不下来**
 *   （这就是之前"灵魂沙云/弹道一直冒"的真正原因）。
 *   所以这里改成"一步一个 runLater、自己在末尾决定还要不要下一步"：
 *   完全不需要 cancel，天然自终止。
 * ========================================================== */

// ---------------- 依赖（逐个容错：顶层抛异常会让整个脚本失效） ----------------
var RD_Vector = null;
try { RD_Vector = Java.type('org.bukkit.util.Vector'); } catch (e) { RD_Vector = null; }

var RD_ITEM_ID = 'GS_RELEASE_DREAM';

// 加载标记（后台看到这行说明脚本本身没问题）
try { getServer().getLogger().info('[构思技艺] gs_release_dream 已加载'); } catch (e) { }

// ---------------- 配置（每次右键读一次，改了配置能立刻生效） ----------------
function rdConfig() {
    var def = {
        range: 15.0,           // 射程（格）
        step: 0.7,             // 每个游戏刻前进多少格
        hitRadius: 0.8,
        dropFrom: 6.0,         // 每次下坠从生物正上方几格开始
        hitInterval: 20,       // 两次下坠之间的间隔（游戏刻），20 = 1 秒
        dps: 40.0,             // 每次结算的伤害（2026-10-06：10 → 25 → 40）
        hits: 1,               // 下坠/伤害次数（2026-10-06：3 → **1**，只砸一次）
        // ★ 音效（2026-10-06 改）：**只有一个** —— 音符盒放在**羊毛**上、
        //   左键调音 **6 次**后的那一声（羊毛 ⇒ 吉他音色 block.note_block.guitar）。
        //   音高换算法：音符盒 note n 的 pitch = 2^((n − 12) / 12)；
        //   note 0 = F#3(0.5)、note 6 = C4(**0.7071**)、note 24 = 2.0。
        soundPitch: 0.7071,    // ★ 0.7071 = 左键 6 次（note 6）
        soundVol: 1.0,
        screamSound: 'entity.warden.heartbeat',   // 坚守者的心跳声（现已默认关闭）
        screamDelay: 20,       // 多声之间的间隔（游戏刻）
        screamCount: 0,        // 发射时响几声（2026-10-06：1 → **0**，不再响心跳）
        sounds: ['block.note_block.guitar']
    };

    try {
        var cfg = getAddonConfig();
        if (cfg == null) return def;
        var sec = cfg.getConfigurationSection('release-dream');
        if (sec == null) return def;

        def.range = sec.getDouble('range', 15.0);
        def.step = sec.getDouble('step', 0.7);
        if (def.step < 0.1) def.step = 0.1;
        def.hitRadius = sec.getDouble('hit-radius', 0.8);
        def.dropFrom = sec.getDouble('drop-from', 6.0);
        if (def.dropFrom < 1.0) def.dropFrom = 1.0;

        def.hitInterval = sec.getInt('hit-interval', 20);
        if (def.hitInterval < 1) def.hitInterval = 1;

        def.dps = sec.getDouble('damage-per-second', 40.0);
        def.hits = sec.getInt('damage-seconds', 1);
        if (def.hits < 1) def.hits = 1;
        if (def.hits > 10) def.hits = 10;

        // ★ 音高：sound-pitch（默认 0.7071 = 音符盒 note 6 = 羊毛上左键 6 次）
        def.soundPitch = sec.getDouble('sound-pitch', def.soundPitch);
        if (def.soundPitch < 0.5) def.soundPitch = 0.5;
        if (def.soundPitch > 2.0) def.soundPitch = 2.0;
        def.soundVol = sec.getDouble('sound-volume', def.soundVol);
        if (def.soundVol < 0.0) def.soundVol = 0.0;

        // 心跳声：优先读新键 heartbeat-*，读不到再回退旧键 scream-*
        def.screamSound = sec.getString('heartbeat-sound',
            sec.getString('scream-sound', def.screamSound));
        def.screamDelay = sec.getInt('heartbeat-slime-ticks',
            sec.getInt('scream-slime-ticks', 2)) * 10;   // 1 粘液刻 = 10 刻
        if (def.screamDelay < 1) def.screamDelay = 1;
        def.screamCount = sec.getInt('heartbeat-count', sec.getInt('scream-count', 0));
        if (def.screamCount < 0) def.screamCount = 0;
        if (def.screamCount > 10) def.screamCount = 10;

        var sl = sec.getStringList('sounds');
        if (sl != null && sl.size() > 0) {
            var arr = [];
            var it = sl.iterator();
            while (it.hasNext()) arr.push('' + it.next());
            def.sounds = arr;
        }
    } catch (e) {
    }
    return def;
}

// ---------------- 工具 ----------------
// ★ 2026-10-08 第六批：原来这里的 rdNow / rdPdc / rdCooldownLeft / rdMarkCooldown
//   四个冷却相关函数**已整段删除**（释梦不再有冷却，也不再往玩家 PDC 写时间戳）。

// 灵魂沙粒子
function rdSoul(world, x, y, z, count, spread, extra) {
    try {
        var P = Java.type('org.bukkit.Particle');
        var t = null;
        try { t = P.SOUL; } catch (e) { t = null; }
        if (t == null) { try { t = P.valueOf('SOUL'); } catch (e) { t = null; } }
        if (t == null) return;
        world.spawnParticle(t, x, y, z, count, spread, spread, spread, extra);
    } catch (e) {
    }
}

// 找一个"活体"（掉落物 / 箭 / 展示框这些没有 getHealth）
function rdFindLiving(world, pt, radius, selfId) {
    try {
        var list = world.getNearbyEntities(pt, radius, radius, radius);
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            var eid = '';
            try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
            if (eid === selfId) continue;               // 不打自己
            try { ent.getHealth(); } catch (e) { continue; }
            try { if (ent.isDead()) continue; } catch (e) { }
            return ent;
        }
    } catch (e) {
    }
    return null;
}

// ---------------- 命中后的「N 次下坠 = N 次伤害」（N = cfg.hits，默认 **1**）----------------
// spot 是命中瞬间记下的坐标（后面所有下坠都打这一点，生物被打死/被移除也不会歪）
function rdSequence(world, shooter, victim, cfg) {
    var spot = null;
    try {
        var l = victim.getLocation();
        spot = { x: l.getX(), y: l.getY(), z: l.getZ() };
    } catch (e) {
        return;
    }
    rdCycle(world, shooter, victim, spot, cfg, 1);
}

// 第 n 次下坠：从正上方 dropFrom 格落到生物身上 → 结算 1 次伤害 → 排下一次
function rdCycle(world, shooter, victim, spot, cfg, n) {
    var tx = spot.x;
    var ty = spot.y;
    var tz = spot.z;
    var h = cfg.dropFrom;

    // 起点先来一小团，让人看见"它又从上面来了"
    rdSoul(world, tx, ty + 1.0 + h, tz, 8, 0.15, 0.0);

    function fallStep() {
        try {
            // 画这一段下落轨迹（画密一点 → 看起来是连续下坠）
            var from = h;
            var to = h - 1.0;
            if (to < 0) to = 0;
            var seg = 5;
            for (var i = 0; i <= seg; i++) {
                var yy = ty + 1.0 + from + (to - from) * (i / seg);
                rdSoul(world, tx, yy, tz, 2, 0.10, 0.0);
            }

            h -= 1.0;

            if (h > 0) {
                runLater(fallStep, 1);
                return;                       // 还没落地 → 继续下一步
            }

            // ★ 砸到生物身上：一小团闪光 + 这一次的伤害
            rdSoul(world, tx, ty + 1.0, tz, 8, 0.4, 0.05);
            rdHitDamage(shooter, victim, cfg);

            if (n < cfg.hits) {
                // 下一次下坠：让"每次结算的间隔"约等于 hitInterval 刻
                var rest = cfg.hitInterval - Math.round(cfg.dropFrom);
                if (rest < 1) rest = 1;
                var next = n + 1;
                runLater(function () { rdCycle(world, shooter, victim, spot, cfg, next); }, rest);
            }
        } catch (e) {
        }
    }

    runLater(fallStep, 1);
}

function rdHitDamage(shooter, victim, cfg) {
    try {
        if (victim == null) return;
        try { if (victim.isDead()) return; } catch (e) { return; }
        try { victim.getHealth(); } catch (e) { return; }

        // ★★ 2026-10-06：**先清掉受击无敌帧再结算**。
        //   原版生物挨打后有 noDamageTicks（默认 10 刻）的无敌时间，`damage()` 会被它吃掉
        //   ⇒ 两次右键的下坠时间点一重叠（实测差 4 刻左右），第二发就"无伤"了。
        //   这里把 noDamageTicks 清零，保证**每一段下坠都真的掉血**。
        //   （同类做法：gs_boss_three 的 btHurt、gs_listener 的精神力开火）
        try { victim.setNoDamageTicks(0); } catch (e) { }

        // ★★ 2026-10-06：**取消击退**。
        //   原版 damage() 结算完会按伤害方向给受害者一个水平冲量（把人/怪推开），
        //   这里在打之前把 velocity 存下来、打完**立刻还原** ⇒ 只掉血、位移一点不变。
        //   （只还原速度、不碰位置，所以视觉上也不会抖）
        var vx = 0.0, vy = 0.0, vz = 0.0, hasV = false;
        try {
            var v0 = victim.getVelocity();
            vx = v0.getX(); vy = v0.getY(); vz = v0.getZ();
            hasV = true;
        } catch (e) { hasV = false; }

        try { victim.damage(cfg.dps, shooter); }
        catch (e1) { try { victim.damage(cfg.dps); } catch (e2) { } }

        if (hasV) {
            try {
                if (RD_Vector != null) victim.setVelocity(new RD_Vector(vx, vy, vz));
                else victim.setVelocity(victim.getVelocity().zero());
            } catch (e) { }
        }
    } catch (e) {
    }
}

// ---------------- 发射音效（2026-10-06 改：**只留音符盒那一声**）----------------
//   音符盒放在**羊毛**上 ⇒ 吉他音色 `block.note_block.guitar`；
//   左键调音 6 次 ⇒ note 6 ⇒ pitch = 2^((6 − 12) / 12) ≈ **0.7071**。
//   （铁砧落地 / 锻造台合成 / 坚守者心跳声都撤了，由 cfg 默认值决定；
//     想加回来只要改 addon 的 default_config.yml 里 `sounds` 列表 + `heartbeat-count`）
function rdLaunchSounds(p, cfg) {
    try {
        var world = p.getWorld();
        var loc = p.getLocation();

        var vol = (cfg.soundVol == null ? 1.0 : cfg.soundVol);
        var pit = (cfg.soundPitch == null ? 0.7071 : cfg.soundPitch);

        // ① 音符盒（吉他音色 · note 6）
        for (var i = 0; i < cfg.sounds.length; i++) {
            var s = '' + cfg.sounds[i];
            if (s.length === 0) continue;
            try { world.playSound(loc, s, vol, pit); } catch (e) { }
        }

        // ② 坚守者心跳声（发射时，跟铁砧落地同步）
        if (cfg.screamCount > 0 && cfg.screamSound != null && ('' + cfg.screamSound).length > 0) {
            var scream = '' + cfg.screamSound;
            var play = function () {
                try { world.playSound(loc, scream, 1.6, 1.0); } catch (e) { }
            };
            play();
            var left = cfg.screamCount - 1;
            if (left > 0) {
                (function again(cnt) {
                    if (cnt <= 0) return;
                    runLater(function () {
                        play();
                        again(cnt - 1);
                    }, cfg.screamDelay);
                })(left);
            }
        }
    } catch (e) {
    }
}

// ---------------- 弹道（runLater 链，撞墙 / 超射程 / 命中都会自然结束） ----------------
function rdLaunch(p, cfg) {
    try {
        var world = p.getWorld();
        var eye = p.getEyeLocation();
        var dir = eye.getDirection();                 // 单位向量
        var selfId = '' + p.getUniqueId();
        var Loc = Java.type('org.bukkit.Location');
        var dist = 1.0;                                // 从眼前 1 格起 → 不会打到自己

        function step() {
            try {
                dist += cfg.step;
                if (dist > cfg.range) return;          // 飞到射程尽头 → 结束

                var px = eye.getX() + dir.getX() * dist;
                var py = eye.getY() + dir.getY() * dist;
                var pz = eye.getZ() + dir.getZ() * dist;
                var pt = new Loc(world, px, py, pz);

                // 拖尾：灵魂沙粒子
                rdSoul(world, px, py, pz, 4, 0.06, 0.0);

                // 命中生物？
                var victim = rdFindLiving(world, pt, cfg.hitRadius, selfId);
                if (victim != null) {
                    rdSequence(world, p, victim, cfg);
                    return;
                }

                // 撞到方块？→ 没命中生物，什么都不发生（不穿墙）
                var b = pt.getBlock();
                if (b != null && b.getType() != null && b.getType().isSolid()) return;

                runLater(step, 1);                     // 继续飞
            } catch (e) {
            }
        }

        runLater(step, 1);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 释梦发射出错: ' + e); } catch (e2) { }
    }
}

// ---------------- 入口 ----------------
function onUse(event) {
    var p = null;
    try { p = event.getPlayer(); } catch (e) { p = null; }
    if (p == null) return;

    try {
        var cfg = rdConfig();

        // ★ 2026-10-08 第六批：**冷却已整段删除** ——
        //   不再有 0.3 秒 CD，也不再提示「梦还没醒…」/「可以重新割开梦境」，
        //   右键即可发射（伤害 / 弹道 / 音效全部不变）。

        // 发射音效（只有一声：音符盒放羊毛上、左键 6 次的那个音）
        rdLaunchSounds(p, cfg);

        rdLaunch(p, cfg);
    } catch (err) {
        try { sendMessage(p, '&c释梦出错了: ' + err); } catch (e) { }
        try { getServer().getLogger().warning('[构思技艺] 释梦 onUse 出错: ' + err); } catch (e2) { }
    }
}
