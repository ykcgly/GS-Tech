/* ==========================================================
 * 构思技艺 (GS) —— 滚木锭
 * items.yml 里由  script: gs_rolling_log_ingot  引用
 * 入口函数固定为 onUse(event)
 * ----------------------------------------------------------
 * 效果：右键后，**自己前方 宽3 × 长5 的区域**内所有生物
 *       （含自己）获得「隐身」10 秒。
 *       · 物品不消耗
 *       · 内置 9 秒冷却（同一玩家）—— ★ 冷却中**不再发聊天提示**（第六十八批删除）
 *       · 区域高度 = 自己所在层 ±1
 * 参数都能在 addon_configs/gs/config.yml 的 rolling-log-ingot 段调。
 * ========================================================== */

// ---------------- Java 类（System 被 RSC 屏蔽，用 java.time） ----------------
var GS_Instant = null;
try { GS_Instant = Java.type('java.time.Instant'); } catch (e) { GS_Instant = null; }

var GS_Location = Java.type('org.bukkit.Location');
var GS_PotionEffectType = Java.type('org.bukkit.potion.PotionEffectType');
var GS_PotionEffect = Java.type('org.bukkit.potion.PotionEffect');
var GS_LivingEntity = Java.type('org.bukkit.entity.LivingEntity');
var GS_ArmorStand = Java.type('org.bukkit.entity.ArmorStand');

// uuid -> 冷却结束时间戳（毫秒）
var gsRllCooldown = {};

function gsRllNow() {
    if (GS_Instant == null) return 0;
    try { return GS_Instant.now().toEpochMilli(); } catch (e) { return 0; }
}

// ---------------- 读取配置（拿不到就用默认值） ----------------
function gsRllCfg() {
    var o = { width: 3, length: 5, invisSeconds: 10, cooldownSeconds: 9 };
    try {
        var cfg = getAddonConfig();
        if (cfg != null) {
            o.width = cfg.getInt("rolling-log-ingot.width", 3);
            o.length = cfg.getInt("rolling-log-ingot.length", 5);
            o.invisSeconds = cfg.getInt("rolling-log-ingot.invis-seconds", 10);
            o.cooldownSeconds = cfg.getInt("rolling-log-ingot.cooldown-seconds", 9);
        }
    } catch (e) {
        // 配置缺失：用默认值
    }
    if (o.width < 1) o.width = 1;
    if (o.width % 2 === 0) o.width += 1;      // 保证是奇数，才能以自己为中心平分左右
    if (o.length < 1) o.length = 1;
    if (o.invisSeconds < 1) o.invisSeconds = 1;
    if (o.cooldownSeconds < 0) o.cooldownSeconds = 0;
    return o;
}

// 每次新建一个效果实例，避免多个实体共用同一个对象
function gsRllNewEffect(ticks) {
    return new GS_PotionEffect(GS_PotionEffectType.INVISIBILITY, ticks, 0, false, false, true);
}

// 把 yaw 转成水平朝向的单位向量（MC：0=南 +Z，90=西 -X，180=北 -Z，270=东 +X）
function gsRllFacing(yaw) {
    var y = ((yaw % 360) + 360) % 360;
    if (y >= 315 || y < 45) return [0, 1];
    if (y < 135) return [-1, 0];
    if (y < 225) return [0, -1];
    return [1, 0];
}

function onUse(event) {
    var p = event.getPlayer();
    if (p == null) return;

    var cfg = gsRllCfg();
    var now = gsRllNow();
    var uuid = p.getUniqueId().toString();

    // ---------------- 冷却检查 ----------------
    if (cfg.cooldownSeconds > 0 && now > 0) {
        var next = gsRllCooldown[uuid];
        if (next != null && now < next) {
            // ★ 第六十八批：冷却中的聊天提示词（"滚木锭尚在沉寂…"）已按需求删除，
            //   冷却本身不变 —— 冷却中直接**静默返回**，不再发任何消息。
            return;
        }
    }

    // ---------------- 算出前方 宽×长 的区块集合 ----------------
    var loc = p.getLocation();
    var world = p.getWorld();
    var bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();

    var f = gsRllFacing(loc.getYaw());
    var fx = f[0], fz = f[1];
    var lx = -fz, lz = fx;                    // 侧向（左/右）
    var half = (cfg.width - 1) / 2;           // 3 宽 → 左右各 1

    var cells = {};                           // "x,y,z" -> true
    var minX = bx, maxX = bx, minZ = bz, maxZ = bz;

    for (var d = 1; d <= cfg.length; d++) {
        for (var w = -half; w <= half; w++) {
            var cx = bx + fx * d + lx * w;
            var cz = bz + fz * d + lz * w;
            for (var dy = -1; dy <= 1; dy++) {
                cells[cx + "," + (by + dy) + "," + cz] = true;
            }
            if (cx < minX) minX = cx;
            if (cx > maxX) maxX = cx;
            if (cz < minZ) minZ = cz;
            if (cz > maxZ) maxZ = cz;
        }
    }

    // ---------------- 收集范围内的生物 ----------------
    var centerX = (minX + maxX) / 2 + 0.5;
    var centerZ = (minZ + maxZ) / 2 + 0.5;
    var rx = (maxX - minX) / 2 + 0.6;
    var rz = (maxZ - minZ) / 2 + 0.6;
    var center = new GS_Location(world, centerX, by + 0.5, centerZ);

    var ticks = cfg.invisSeconds * 20;

    try {
        var list = world.getNearbyEntities(center, rx, 1.6, rz);
        var it = list.iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (!(e instanceof GS_LivingEntity)) continue;   // 只对生物
            if (e instanceof GS_ArmorStand) continue;        // 盔甲架跳过，别把装饰变没
            var el = e.getLocation();
            if (cells[el.getBlockX() + "," + el.getBlockY() + "," + el.getBlockZ()] !== true) continue;
            e.addPotionEffect(gsRllNewEffect(ticks));
        }
    } catch (e) {
        // 范围实体查询失败也不影响自己拿到效果
    }

    // ---------------- 自己也算（"包括自身"）----------------
    p.addPotionEffect(gsRllNewEffect(ticks));

    // ---------------- 记录冷却 ----------------
    if (cfg.cooldownSeconds > 0 && now > 0) {
        gsRllCooldown[uuid] = now + cfg.cooldownSeconds * 1000;
    }

    try {
        p.playSound(loc, "entity.illusioner.cast_spell", 0.7, 1.3);
    } catch (e) {
    }
    // 物品不消耗：什么都不用做
}
