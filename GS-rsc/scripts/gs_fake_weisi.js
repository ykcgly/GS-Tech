/* ==========================================================
 * 构思技艺 (GS) —— 「假为思」右键效果
 * items.yml 里由  script: gs_fake_weisi  引用
 * 入口函数固定为 onUse(event)
 * ----------------------------------------------------------
 * 消耗品：右键使用一次消耗 1 个
 *
 * 演出时间轴（单位：刻，20 刻 = 1 秒）：
 *   t=0      消耗 1 个 / 获得 9 秒漂浮
 *           → 四个**隐形盔甲架**从「前 / 下 / 后 / 上」3.5 格外飞向玩家：
 *               前 → 胸甲    下 → 战靴    后 → 护腿    上 → 头盔
 *             出现时刻：t=22 / 62 / 102 / 142 刻（1.1 / 3.1 / 5.1 / 7.1 秒）
 *             抵达时刻：t=60 / 100 / 140 / 180 刻（3 / 5 / 7 / 9 秒）
 *             —— 每段飞行 38 刻 = 1.9 秒（都在 2 秒以内）
 *             撞上的一瞬间 → 换上那件梦境套（原装备脱进背包）+ 消灭盔甲架
 *   t=180   （漂浮 9 秒结束）→ 立刻给 2 秒「缓降」+ 启动「安全落地」保护
 *   t=280   （第 15 秒）解除防连点锁
 *
 * 音效：盔甲架撞到玩家时播一次 —— 末影龙飞行 entity.ender_dragon.flap
 *       （原来的「铁砧落地 + 使用铁砧」太震耳朵，已移除，只留末影龙）
 *
 * ★ 盔甲架的装备「不掉落」：
 *     ① 盔甲架设成 invulnerable（打不坏 → 不会被击杀掉装备）
 *     ② 消失前先 getEquipment().clear() 再 remove() → 一个物品都不会掉
 *   另外隐形、无重力、静音、不碰撞，只让身上那件装备看得见。
 *
 * 装备不收回：梦境套是消耗「梦境套 + GPS 应急发射器」合成的，
 *   演出结束后永久留在身上。替换下来的原装备（如果有）会先脱进背包，
 *   背包放不下的部分掉在脚边。
 *
 * 安全落地：漂浮结束时玩家在高空，身上会持续把「摔落距离」清零，
 *   并一路持续到玩家真正落地，保证不会摔伤。
 *
 * ★ 为什么全部用 runLater 链、不自己拿 scheduler：
 *   RSC 的定时器返回值不可靠（见 gs_release_dream.js 文件头注释），
 *   runLater(fn, ticks) 是 RSC 提供的「延时 N 刻执行一次」的全局函数。
 * ★ 全容错：顶层任何一处抛异常，RSC 就不会给物品注册右键处理器
 *   （表现是"右键毫无反应"），所以每个 Java.type 都单独 try。
 * ========================================================== */

// ---------------- 依赖（逐个容错） ----------------
var FW_PET = null;
var FW_PE = null;
var FW_ParticleType = null;
try { FW_PET = Java.type('org.bukkit.potion.PotionEffectType'); } catch (e) { FW_PET = null; }
try { FW_PE = Java.type('org.bukkit.potion.PotionEffect'); } catch (e) { FW_PE = null; }
try {
    var FW_Particle = Java.type('org.bukkit.Particle');
    try { FW_ParticleType = FW_Particle.SPIT; } catch (e) { FW_ParticleType = null; }
    if (FW_ParticleType == null) { try { FW_ParticleType = FW_Particle.valueOf('SPIT'); } catch (e) { FW_ParticleType = null; } }
} catch (e) { FW_ParticleType = null; }

// ---------------- 梦境套物品 ID ----------------
var FW_CHEST = 'GS_DREAM_ARMOR';      // 梦境盔甲 → 胸甲槽
var FW_BOOTS = 'GS_DREAM_BOOTS';      // 梦境战靴 → 靴子槽
var FW_LEGS  = 'GS_DREAM_LEGGINGS';   // 梦境护腿 → 护腿槽
var FW_HELM  = 'GS_DREAM_HELMET';     // 梦境头盔 → 头盔槽

// 音效：每换一件只播放末影龙飞行
//   （原来的「铁砧落地 + 使用铁砧」两个音效太震耳朵，已移除）
var FW_SOUNDS = ['entity.ender_dragon.flap'];

// 时间轴（刻）
var FW_LEV_TICKS   = 180;   // 漂浮 9 秒
var FW_SLOW_TICKS  = 40;    // 缓降 2 秒
var FW_UNLOCK      = 280;   // 第 15 秒（t=14s）解除防连点锁
var FW_SPIT_TIMES  = 90;    // 粒子跑 90 次 × 每 2 刻 = 180 刻（9 秒）
var FW_NOFALL_MAX  = 600;   // 安全落地保护最长 600 次 × 2 刻 = 60 秒

// 「铠甲合体」动画参数
var FW_STAND_DIST   = 3.5;  // 盔甲架出现位置距玩家的距离（格）
var FW_FLIGHT_TICKS = 38;   // 每个盔甲架的飞行时长（38 刻 = 1.9 秒 < 2 秒）
var FW_STAND_FIRST  = 22;   // 第 1 个盔甲架的出现时刻（22 刻 = 1.1 秒）
var FW_STAND_GAP    = 40;   // 相邻两个的出现间隔（40 刻 = 2 秒）
//   → 出现：22 / 62 / 102 / 142 刻（1.1 / 3.1 / 5.1 / 7.1 秒）
//   → 抵达：60 / 100 / 140 / 180 刻（3 / 5 / 7 / 9 秒）

// 正在演出中的玩家（uuid → true），避免连点导致多条时间轴重叠
var fwBusy = {};

// 加载标记：后台看到这行，说明脚本本身没问题
try { getServer().getLogger().info('[构思技艺] gs_fake_weisi 已加载'); } catch (e) { }

// ---------------- 工具 ----------------

function fwUuid(p) {
    try { return '' + p.getUniqueId(); } catch (e) { return ''; }
}

// 消耗手里 1 个本物品
function fwConsume(p) {
    try {
        var inv = p.getInventory();
        var hand = inv.getItemInMainHand();
        if (hand == null) return;
        var amt = 0;
        try { amt = hand.getAmount(); } catch (e) { amt = 0; }
        if (amt > 1) {
            hand.setAmount(amt - 1);
        } else {
            inv.setItemInMainHand(null);
        }
    } catch (e) { }
}

// 给玩家一条药水效果（类型名容错）
function fwPotion(p, typeName, durTicks, amp) {
    try {
        if (FW_PET == null || FW_PE == null) return;
        var t = null;
        try { t = FW_PET[typeName]; } catch (e) { t = null; }
        if (t == null) { try { t = FW_PET.valueOf(typeName); } catch (e) { t = null; } }
        if (t == null) return;
        try { p.addPotionEffect(new FW_PE(t, durTicks, amp, true, false), true); }
        catch (e) { try { p.addPotionEffect(new FW_PE(t, durTicks, amp, true, false)); } catch (e2) { } }
    } catch (e) { }
}

// 播一个音效
function fwSound(p, name) {
    try { p.getWorld().playSound(p.getLocation(), '' + name, 1.0, 1.0); } catch (e) { }
}

// 逐个播放 FW_SOUNDS 里的音效
function fwSoundAll(p) {
    for (var i = 0; i < FW_SOUNDS.length; i++) fwSound(p, FW_SOUNDS[i]);
}

// 把某一槽位换上指定梦境套
function fwWear(p, slot, itemId) {
    var stack = null;
    try {
        var sf = getSfItemById(itemId);
        if (sf != null) stack = sf.getItem().clone();
    } catch (e) { stack = null; }
    if (stack == null) return;
    try {
        var inv = p.getInventory();
        if (slot === 'chest') inv.setChestplate(stack);
        else if (slot === 'boots') inv.setBoots(stack);
        else if (slot === 'legs') inv.setLeggings(stack);
        else if (slot === 'head') inv.setHelmet(stack);
    } catch (e) { }
}

// 取某一槽位当前的装备（没有就返回 null）
function fwSlotGet(p, slot) {
    try {
        var inv = p.getInventory();
        if (slot === 'chest') return inv.getChestplate();
        if (slot === 'boots') return inv.getBoots();
        if (slot === 'legs') return inv.getLeggings();
        if (slot === 'head') return inv.getHelmet();
    } catch (e) { }
    return null;
}

// 把一件物品塞进背包，塞不下的掉在脚边
function fwToInventory(p, stack) {
    if (stack == null) return;
    try {
        if (stack.getType() == null) return;
        var n = '';
        try { n = '' + stack.getType().name(); } catch (e) { n = ''; }
        if (n === 'AIR' || n === 'CAVE_AIR' || n === 'VOID_AIR') return;
    } catch (e) { return; }

    try {
        var inv = p.getInventory();
        var rest = inv.addItem(stack.clone());
        if (rest != null && !rest.isEmpty()) {
            var it = rest.iterator();
            while (it.hasNext()) {
                try { p.getWorld().dropItemNaturally(p.getLocation(), it.next()); } catch (e) { }
            }
        }
    } catch (e) { }
}

// 换装备：先把该槽位原来的装备脱进背包，再穿新的
function fwSwap(p, slot, itemId) {
    var old = fwSlotGet(p, slot);
    if (old != null) fwToInventory(p, old);
    fwWear(p, slot, itemId);
}

// ---------------- 「铠甲合体」：隐形盔甲架从四个方向飞来 ----------------

// 玩家朝向的水平单位向量（只取 yaw，不含俯仰，避免前后出现在头顶/脚下）
function fwFacing(p) {
    try {
        var Vec = Java.type('org.bukkit.util.Vector');
        var d = p.getLocation().getDirection();
        var v = new Vec(d.getX(), 0.0, d.getZ());
        if (v.lengthSquared() < 1.0E-6) v = new Vec(1.0, 0.0, 0.0);
        v.normalize();
        return v;
    } catch (e) { return null; }
}

// 在玩家「前 / 下 / 后 / 上」1.8 格处生成一个隐形盔甲架，身上带着对应那件梦境套
function fwSpawnStand(p, dir, itemId, armorSlot) {
    try {
        var Loc = Java.type('org.bukkit.Location');
        var ET  = Java.type('org.bukkit.entity.EntityType');
        var world = p.getWorld();
        var base = p.getLocation();

        var dx = 0.0, dy = 0.0, dz = 0.0;
        if (dir === 'front' || dir === 'back') {
            var v = fwFacing(p);
            if (v == null) return null;
            var sign = (dir === 'front') ? 1.0 : -1.0;
            dx = sign * v.getX() * FW_STAND_DIST;
            dz = sign * v.getZ() * FW_STAND_DIST;
        } else if (dir === 'below') {
            dy = -FW_STAND_DIST;
        } else if (dir === 'above') {
            dy = FW_STAND_DIST;
        }

        var loc = new Loc(world, base.getX() + dx, base.getY() + dy, base.getZ() + dz);
        try { loc.setYaw(base.getYaw()); } catch (e) { }

        var ent = world.spawnEntity(loc, ET.ARMOR_STAND);
        if (ent == null) return null;

        // 隐形（只让身上那件装备可见）+ 打不坏 + 无重力 + 静音 + 不碰撞
        try { ent.setInvisible(true); } catch (e) { }
        try { ent.setInvulnerable(true); } catch (e) { }
        try { ent.setGravity(false); } catch (e) { }
        try { ent.setSilent(true); } catch (e) { }
        try { ent.setCollidable(false); } catch (e) { }
        try { ent.setBasePlate(false); } catch (e) { }
        try { ent.setArms(false); } catch (e) { }
        try { ent.setCanPickupItems(false); } catch (e) { }

        // 把那件梦境套"穿"在盔甲架上
        var stack = null;
        try {
            var sf = getSfItemById(itemId);
            if (sf != null) stack = sf.getItem().clone();
        } catch (e) { stack = null; }
        if (stack != null) {
            try {
                var eq = ent.getEquipment();
                if (eq != null) {
                    if (armorSlot === 'chest') eq.setChestplate(stack);
                    else if (armorSlot === 'boots') eq.setBoots(stack);
                    else if (armorSlot === 'legs') eq.setLeggings(stack);
                    else if (armorSlot === 'head') eq.setHelmet(stack);
                }
            } catch (e) { }
        }
        return ent;
    } catch (e) { return null; }
}

// 消灭盔甲架：先清空装备再 remove → 保证「装备不掉落」
function fwKillStand(stand) {
    if (stand == null) return;
    try { if (stand.isDead()) return; } catch (e) { }
    try { var eq = stand.getEquipment(); if (eq != null) eq.clear(); } catch (e) { }
    try { stand.remove(); } catch (e) { }
}

// 撞到玩家：把那件换上（原装备脱进背包）+ 消灭盔甲架 + 音效
function fwEquipAndKill(p, stand, slot, itemId) {
    fwSwap(p, slot, itemId);
    fwKillStand(stand);
    fwSoundAll(p);
}

// 飞行：每一刻把盔甲架从「出生点」向「玩家当前身体中段」插值。
//   玩家这时候在飘（位置一直在变），所以每一刻都以最新位置为目标 → 一定撞得上。
//   i 从 1 数到 total：第 total 步正好抵达（= 出现时刻 + FW_FLIGHT_TICKS 刻）
function fwFlight(p, stand, from, slot, itemId, i, total) {
    if (stand == null) return;
    var dead = false;
    try { dead = stand.isDead(); } catch (e) { }
    if (dead) return;

    var online = true;
    try { online = p.isOnline(); } catch (e) { online = true; }
    if (!online) { fwKillStand(stand); return; }

    try {
        var Loc = Java.type('org.bukkit.Location');
        var base = p.getLocation();
        var tx = base.getX();
        var ty = base.getY() + 0.9;          // 玩家身体中段
        var tz = base.getZ();

        var k = i / total;                   // 1/total → 1
        var x = from.getX() + (tx - from.getX()) * k;
        var y = from.getY() + (ty - from.getY()) * k;
        var z = from.getZ() + (tz - from.getZ()) * k;

        var yaw = 0.0;
        try { yaw = Math.atan2(-(tx - x), (tz - z)) * 180.0 / Math.PI; } catch (e) { }
        var loc = new Loc(stand.getWorld(), x, y, z);
        try { loc.setYaw(yaw); } catch (e) { }
        stand.teleport(loc);
    } catch (e) { }

    if (i >= total) {
        fwEquipAndKill(p, stand, slot, itemId);
        return;
    }
    runLater(function () { fwFlight(p, stand, from, slot, itemId, i + 1, total); }, 1);
}

// 启动一个盔甲架：延时 delayTicks 后出现，再花 FW_FLIGHT_TICKS 刻飞到玩家身上
function fwLaunch(p, dir, slot, itemId, delayTicks) {
    runLater(function () {
        var stand = fwSpawnStand(p, dir, itemId, slot);
        if (stand == null) {
            // 盔甲架造不出来（版本/权限异常）→ 退化成直接装备，保证功能不失效
            fwSwap(p, slot, itemId);
            fwSoundAll(p);
            return;
        }
        // 保险：万一动画链路中断，最多再等 2 秒就把盔甲架清掉（绝不留实体）
        runLater(function () { fwKillStand(stand); }, FW_FLIGHT_TICKS + 60);

        var from = null;
        try { from = stand.getLocation(); } catch (e) { from = null; }
        if (from == null) { fwEquipAndKill(p, stand, slot, itemId); return; }
        // 出现后隔 1 刻再起跑 —— 这样「出现时刻 + FW_FLIGHT_TICKS」正好是抵达时刻
        runLater(function () { fwFlight(p, stand, from, slot, itemId, 1, FW_FLIGHT_TICKS); }, 1);
    }, delayTicks);
}

// 羊驼口水粒子循环：每 2 刻在周围随机位置冒几颗
function fwSpitLoop(p, timesLeft) {
    if (timesLeft <= 0) return;
    try {
        if (p == null) return;
        var online = true;
        try { online = p.isOnline(); } catch (e) { online = true; }
        if (!online) return;

        if (FW_ParticleType != null) {
            var world = p.getWorld();
            var loc = p.getLocation();
            for (var i = 0; i < 2; i++) {
                var ang = Math.random() * 6.2831853;
                var r = 0.4 + Math.random() * 1.1;
                var x = loc.getX() + Math.cos(ang) * r;
                var z = loc.getZ() + Math.sin(ang) * r;
                var y = loc.getY() + 0.2 + Math.random() * 1.8;
                try {
                    world.spawnParticle(FW_ParticleType, x, y, z, 1, 0.12, 0.12, 0.12, 0.015);
                } catch (e) { }
            }
        }
    } catch (e) { }
    runLater(function () { fwSpitLoop(p, timesLeft - 1); }, 2);
}

// 安全落地保护：持续把「摔落距离」清零，直到玩家真正落地（或落进水里）
function fwNoFallLoop(p, timesLeft) {
    if (timesLeft <= 0) return;
    try {
        if (p == null) return;
        var online = true;
        try { online = p.isOnline(); } catch (e) { online = true; }
        if (!online) return;

        try { p.setFallDistance(0.0); } catch (e) { }

        var done = false;
        try { if (p.isOnGround()) done = true; } catch (e) { }
        if (!done) {
            try { if (p.isInWater() || p.isInLava()) done = true; } catch (e) { }
        }
        if (done) return;
    } catch (e) { }
    runLater(function () { fwNoFallLoop(p, timesLeft - 1); }, 2);
}

// ---------------- 入口 ----------------
function onUse(event) {
    var p = null;
    try { p = event.getPlayer(); } catch (e) { p = null; }
    if (p == null) return;

    var uuid = fwUuid(p);

    try {
        // 演出中不能再触发
        if (fwBusy[uuid]) {
            try { sendMessage(p, '&7铠甲正在合体，稍安勿躁。'); } catch (e) { }
            return;
        }

        // ---------- 消耗 1 个 ----------
        fwConsume(p);

        // ---------- 9 秒漂浮 ----------
        fwPotion(p, 'LEVITATION', FW_LEV_TICKS, 0);

        // ---------- 粒子：漂浮期间不断冒羊驼口水 ----------
        fwSpitLoop(p, FW_SPIT_TIMES);

        // ---------- 铠甲合体：四个隐形盔甲架从 前 / 下 / 后 / 上 飞来 ----------
        //   前→胸甲   下→战靴   后→护腿   上→头盔
        //   出现：1.1 / 3.1 / 5.1 / 7.1 秒   抵达：3 / 5 / 7 / 9 秒（各飞 1.9 秒）
        fwLaunch(p, 'front', 'chest', FW_CHEST, FW_STAND_FIRST);
        fwLaunch(p, 'below', 'boots', FW_BOOTS, FW_STAND_FIRST + FW_STAND_GAP);
        fwLaunch(p, 'back',  'legs',  FW_LEGS,  FW_STAND_FIRST + FW_STAND_GAP * 2);
        fwLaunch(p, 'above', 'head',  FW_HELM,  FW_STAND_FIRST + FW_STAND_GAP * 3);

        // ---------- 漂浮 9 秒结束 → 立刻缓降 + 启动安全落地保护 ----------
        runLater(function () {
            fwPotion(p, 'SLOW_FALLING', FW_SLOW_TICKS, 0);   // 漂浮一结束就缓降
            fwNoFallLoop(p, FW_NOFALL_MAX);                  // 兜底：绝不摔伤
        }, FW_LEV_TICKS);

        // ---------- 13 秒后解除防连点锁 ----------
        fwBusy[uuid] = true;
        runLater(function () {
            fwBusy[uuid] = false;
        }, FW_UNLOCK);

    } catch (err) {
        try { sendMessage(p, '&c假为思出错了: ' + err); } catch (e) { }
        try { getServer().getLogger().warning('[构思技艺] 假为思 onUse 出错: ' + err); } catch (e) { }
    }
}
