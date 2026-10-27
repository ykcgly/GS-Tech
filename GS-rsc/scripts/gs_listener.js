/* ==========================================================
 * 构思技艺 (GS) —— 事件监听脚本
 * 「万物初生，始于想象」
 * ==========================================================
 * 由 info.yml 的 scriptListener: "gs_listener" 引用。
 * 函数命名规则: on + 事件名（去掉 Event 后缀）
 *   PlayerDeathEvent -> onPlayerDeath
 *   PlayerJoinEvent  -> onPlayerJoin
 *   PlayerQuitEvent  -> onPlayerQuit
 * ========================================================== */

// ---------------- 物品 ID ----------------
var GS_IMAGINATION = "GS_IMAGINATION";   // 想象力（线）
var GS_FRAGMENT    = "GS_MEMORY_FRAGMENT"; // 记忆碎片（骷髅头颅）
var GS_INSPIRATION = "GS_INSPIRATION";   // 灵感（末地烛）
var GS_THINKING_HAT = "GS_THINKING_HAT"; // 奇思妙想帽（原版金头盔，2026-10-08 新增）

// ---------------- PersistentDataType（★★ 全局共用，别删）----------------
// 本文件后面很多模块（梦境套 / 光环 / 麦友的酒 / 开镜 / 篮球 / 阎魔刀 /
// 蚀月 / 寂灭 …）都直接用 GS_PDT，所以它必须在这里声明一次。
// ★ 这里包了 try：脚本顶层一旦抛异常，整个监听器都会失效。
// ★★★ 第八十八批：「构念」删除后，配套的 GS_GlassKey / GS_NamespacedKey
//   （虚拟玻璃位置表 'gs:construct_glass'）也一并删掉了。
var GS_PDT = null;
try {
    GS_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) {
    GS_PDT = null;
}

// ---------------- 灵感生成任务（每个在线玩家一个） ----------------
var gsInspTasks = {};

// ================= 工具函数 =================

// 取一个全新的自制物品
function gsNewItem(id) {
    var sf = getSfItemById(id);
    if (sf == null) return null;
    return sf.getItem().clone();
}

// 判断物品是不是指定的自制物品
// ★ 必须用 ('' + xxx) 把 Java String 转成 JS 字符串再比较：
//   Nashorn 里 `java.lang.String` 直接用 === 比 JS 字符串是不可靠的。
function gsIsItem(item, id) {
    if (item == null) return false;
    var sf = getSfItemByItem(item);
    if (sf == null) return false;
    return ('' + sf.getId()) === id;
}

// 安全获取附属配置
function gsCfg() {
    try {
        return getAddonConfig();
    } catch (e) {
        return null;
    }
}

// ================= 记忆碎片：携带想象力死亡时掉落 =================
// 背包中有「想象力」时死亡 -> 消耗 1 个想象力，在死亡位置生成 1 枚「记忆碎片」
// ★ 关键：onPlayerDeath 是 RSC scriptListener 回调，延迟 1 tick 执行。
//   没开"死亡不掉落"时，玩家死亡瞬间背包物品已经变成掉落物 → 此时读背包是空的、
//   检测不到想象力。所以这里**扫描死亡点附近的掉落物实体**来找"想象力掉落物"，
//   删掉它（= 消耗），再生成记忆碎片；这样开不开 keepInventory 都能生效。
function onPlayerDeath(event) {
    var player = event.getEntity();
    if (player == null) return;

    // 五骊骑阵：死亡就收阵（把骷髅马清干净、副手拴绳收走）
    try { gsWuliQuit(player); } catch (e) { }

 // ★ 「携带想象力死亡掉记忆碎片」**已按需求删除** ——
    //   记忆碎片的获取方式改为「构思机里 9 个想象力合成」。
    //   （原来的实现靠配置 memory-fragment.enabled 开关，但服务器上已有一份
    //     addon_configs/gs/config.yml 副本，改模板不生效，所以直接删代码。）
}

// ==========================================================================
// 「佩戴头盔 → 定时掉落」总任务（每个在线玩家一条自调度链）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §1
// ==========================================================================
function gsInspirationInterval() {
    var cfg = gsCfg();
    var insp = 20, hat = 20;
    try { if (cfg != null) insp = cfg.getInt("inspiration.interval", 20); } catch (e0) { }
    try { if (cfg != null) hat = cfg.getInt("thinking-hat.interval", 20); } catch (e1) { }
    if (insp < 1) insp = 1;
    if (hat < 1) hat = 1;
    return insp < hat ? insp : hat;
}

// 是否允许在当前游戏模式掉落（创造模式挂机默认不刷）
function gsHeadDropSurvivalOk(player, cfg, path) {
    try {
        if (cfg != null && !cfg.getBoolean(path, true)) return true;   // 关掉"仅生存"就都允许
        var gm = player.getGameMode();
        var name = (gm == null) ? "SURVIVAL" : ('' + gm.name());       // ★ 转成 JS 字符串再比
        return (name === "SURVIVAL" || name === "ADVENTURE");
    } catch (e) { return true; }
}

function gsTickHeadDrop(player) {
    if (player == null || !player.isOnline()) return;

    var cfg = gsCfg();
    var helmet = null;
    try { helmet = player.getInventory().getHelmet(); } catch (e0) { return; }

    // ① 奇思妙想帽 → 头顶掉「想象力」
    if (cfg == null || cfg.getBoolean("thinking-hat.enabled", true)) {
        if (gsIsItem(helmet, GS_THINKING_HAT)
            && gsHeadDropSurvivalOk(player, cfg, "thinking-hat.survival-only")) {
            var img = gsNewItem(GS_IMAGINATION);
            if (img != null) {
                try {
                    var loc = player.getLocation();
                    // ★「从头上掉落」：生成点抬到头顶上方（玩家身高 1.8 ⇒ +2.2 已在头顶之上），
                    //   物品会从头顶自然落下来。
                    var LocC = null;
                    try { LocC = Java.type('org.bukkit.Location'); } catch (eL) { LocC = null; }
                    if (LocC != null) {
                        var head = new LocC(player.getWorld(), loc.getX(), loc.getY() + 2.2, loc.getZ());
                        player.getWorld().dropItemNaturally(head, img);
                    } else {
                        player.getWorld().dropItemNaturally(loc, img);
                    }
                } catch (e1) {
                    try { player.getWorld().dropItemNaturally(player.getLocation(), img); } catch (e2) { }
                }
            }
        }
    }

    // ② 记忆碎片 → 脚下生成「灵感」（原样保留）
    if (cfg == null || cfg.getBoolean("inspiration.enabled", true)) {
        if (gsIsItem(helmet, GS_FRAGMENT)
            && gsHeadDropSurvivalOk(player, cfg, "inspiration.survival-only")) {
            var drop = gsNewItem(GS_INSPIRATION);
            if (drop != null) player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
    }
}

// 自调度链：被取代（gen 变了）/ 玩家下线 ⇒ 自终止
function gsInspLoop(player, uuid, gen) {
    var period = gsInspirationInterval();
    runLater(function () {
        var alive = false;
        try {
            var e = gsInspTasks[uuid];
            if (e != null && e.gen === gen) alive = true;
        } catch (e0) { alive = false; }
        if (!alive) return;                                   // 已被取代 ⇒ 断链
        try {
            if (player == null || !player.isOnline()) return;  // 下线 ⇒ 断链
            gsTickHeadDrop(player);
        } catch (e1) { }
        gsInspLoop(player, uuid, gen);                        // 排下一步
    }, period);
}

function gsStartInspiration(player) {
    if (player == null) return;
    var uuid = '';
    try { uuid = player.getUniqueId().toString(); } catch (e) { return; }
    var cur = gsInspTasks[uuid];
    var gen = (cur == null ? 0 : cur.gen) + 1;   // ★ 超过旧链的世代号
    gsInspTasks[uuid] = { gen: gen };
    gsInspLoop(player, uuid, gen);
}

function gsStopInspiration(player) {
    if (player == null) return;
    try {
        var uuid = player.getUniqueId().toString();
        var e = gsInspTasks[uuid];
        if (e != null) e.gen = e.gen + 1;        // 让旧链自终止（runRepeating 停不掉，只能这样）
        delete gsInspTasks[uuid];
    } catch (e2) { }
}

function onPlayerJoin(event) {
    gsStartInspiration(event.getPlayer());
}

function onPlayerQuit(event) {
    gsStopInspiration(event.getPlayer());
    try {
        var qu = '' + event.getPlayer().getUniqueId();
        gsSetEffOn[qu] = null; gsSetNightOn[qu] = false;
        gsRadMark(qu, false); gsDreamFlyOn[qu] = false;
    } catch (e) { }
    try { gsWuliQuit(event.getPlayer()); } catch (e) { }   // 五骊骑阵：下线收阵（马不会残留）
}

// ================= 附属加载时：为已在线的玩家启动灵感计时器 =================
runLater(function () {
    try {
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            gsStartInspiration(it.next());
        }
    } catch (e) {
    }
}, 40);

// ==========================================================================
// ★★★ 用户「删除构念」
//   「构念」（GS_CONSTRUCT_ROD）整件删除 ⇒ 本文件里配套的三块代码**一并删除**：
//     ① onBlockBreak —— 挖掉虚拟玻璃时不掉落任何物品（连精准采集也不行）
//     ② gsClearGlassDrops —— 事后清掉刚掉出来的玻璃实体
//     ③ onPlayerInteract —— 右键放玻璃的**兜底实现**
//        （连带 gsGlassMat / gsAirMat / gsGlassKeyOf / gsAddGlass /
//          gsRemoveGlass / gsCanPlaceGlass / gsConsumeItem 一起删掉）
//   ★ 主体逻辑原本在 scripts/gs_construct_idea.js 的 onUse 里，那个文件也删了。
//   ★ GS_PDT 的声明**保留**（见本文件顶部）—— 后面很多模块都在用它。
// ==========================================================================

// ==========================================================================
// 梦境套「免死」：致命伤害拦截（★ 自己注册「同步」监听器）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §15
// ==========================================================================

var GS_DREAM_ARMOR_ID = "GS_DREAM_ARMOR";   // 梦境盔甲
var GS_DA_REGISTERED = false;               // 同步监听器是否注册成功
var GS_DA_CFG = null;                       // 配置缓存（加载期读不到，触发时再读）

// ---------------- 配置（懒加载 + 全容错） ----------------
function gsDaConfig() {
    if (GS_DA_CFG != null) return GS_DA_CFG;

    var def = {
        enabled: true,
        percent: 9,
        cooldown: 45000,          // 免死冷却（毫秒）：45000 = 45 秒（套装效果）
        heal: 12.0,               // 免死触发后把血量补到至少这么多（12 = 6 颗心）；0 = 不补
        ignores: { VOID: 1, KILL: 1, SUICIDE: 1 },
        sound: 'item.totem.use',
        particle: true,
        msg: '&5这是我的梦！',
        breakMsg: '&c梦境：该醒醒了'
    };

    try {
        var cfg = getAddonConfig();
        if (cfg == null) return def;                  // 还没加载好 → 先用默认值，不缓存

        var sec = cfg.getConfigurationSection('dream-set');
        if (sec == null) {
            GS_DA_CFG = def;
            return GS_DA_CFG;
        }

        def.enabled = sec.getBoolean('enabled', true);
        def.percent = sec.getDouble('durability-percent', 9);

        var list = sec.getStringList('ignored-causes');
        if (list != null && list.size() > 0) {
            var m = {};
            var it = list.iterator();
            while (it.hasNext()) { m['' + it.next()] = 1; }
            def.ignores = m;
        }

        var cdMs = sec.getInt('cooldown-millis', 45000);
        if (cdMs < 0) cdMs = 0;
        def.cooldown = cdMs;

        var hv = sec.getDouble('heal', 12.0);
        if (hv < 0) hv = 0;
        def.heal = hv;

        def.sound = sec.getString('sound', def.sound);
        def.particle = sec.getBoolean('particle', true);
        def.msg = sec.getString('message', def.msg);
        def.breakMsg = sec.getString('break-message', def.breakMsg);
    } catch (e) {
        return def;                                   // 配置异常 → 用默认值，别让效果失效
    }

    GS_DA_CFG = def;
    return GS_DA_CFG;
}

// ---------------- 工具 ----------------
function gsDaLog(msg) {
    try { getServer().getLogger().info('[构思技艺] ' + msg); } catch (e) { }
}


// 图腾粒子（跨版本容错：TOTEM_OF_UNDYING / TOTEM）
function gsTotemParticles(p) {
    try {
        var Particle = Java.type('org.bukkit.Particle');
        var t = null;
        try { t = Particle.TOTEM_OF_UNDYING; } catch (e) { }
        if (t == null) { try { t = Particle.valueOf('TOTEM'); } catch (e) { } }
        if (t == null) return;

        var loc = p.getLocation();
        p.getWorld().spawnParticle(t, loc.getX(), loc.getY() + 1.0, loc.getZ(),
                40, 0.6, 0.9, 0.6, 0.15);
    } catch (e) {
    }
}

// ---------------- 同步处理器（真正拦伤害的地方） ----------------
// ---------------- 免死冷却（默认 45 秒，存玩家 PDC，跨 reload 不丢） ----------------
var GS_DA_CD_KEY = null;
try { GS_DA_CD_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:dream_armor_cd'); } catch (e) { GS_DA_CD_KEY = null; }

function gsDaNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); } catch (e) { return 0; }
}

function gsDaCooldownLeft(p, cfg) {
    try {
        if (GS_DA_CD_KEY == null || cfg == null || (0 + cfg.cooldown) <= 0) return 0;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        var v = p.getPersistentDataContainer().get(GS_DA_CD_KEY, PDT.LONG);
        if (v == null) return 0;
        var left = (0 + v) + (0 + cfg.cooldown) - gsDaNow();
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function gsDaMarkCooldown(p) {
    try {
        if (GS_DA_CD_KEY == null) return;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        p.getPersistentDataContainer().set(GS_DA_CD_KEY, PDT.LONG, gsDaNow());
    } catch (e) { }
}

function gsOnEntityDamageSync(event) {
    try {
        if (event == null) return;
        if (event.isCancelled()) return;      // 已被取消 / 已被重复注册的实例处理过 → 跳过

        var ent = null;
        try { ent = event.getEntity(); } catch (e) { return; }
        if (ent == null) return;

        // ★★ 「开机动画里冲过来引路的那匹马」必须**全程免伤** ——
        //   它从 7 格外冲过来要 1.5 秒，这期间被玩家 / 怪物 / 环境打死就"永远上不了马"。
        //   判定靠**记分板标签**（实体被 remove 时标签自动跟着消失 ⇒ 不需要任何清理代码）。
        //   注意：**必须在 `en !== 'PLAYER'` 那一行之前判**，否则非玩家实体直接被放过了。
        try {
            var tags = ent.getScoreboardTags();
            if (tags != null && tags.contains(WL_RIDE_TAG)) { event.setCancelled(true); return; }
        } catch (e0) { }

        var en = '';
        try { en = '' + ent.getType().name(); } catch (e) { return; }
        if (en !== 'PLAYER') return;          // 只关心玩家

        var cfg = gsDaConfig();
        if (!cfg.enabled) return;

        // 指令 / 虚空类伤害不拦
        var cause = '';
        try { cause = '' + event.getCause().name(); } catch (e) { }
        if (cfg.ignores[cause] === 1) return;

        var p = ent;
        try {
            if (p.isDead()) return;
            if (p.getHealth() <= 0) return;
        } catch (e) {
            return;
        }

        var dmg = 0;
        try {
            dmg = event.getFinalDamage();
        } catch (e) {
            try { dmg = event.getDamage(); } catch (e2) { return; }
        }
        if (!(dmg > 0)) return;               // 被盾牌 / 完全免疫时 dmg 为 0

        var health = 0;
        var absorb = 0;
        try {
            health = p.getHealth();
            absorb = p.getAbsorptionAmount();
        } catch (e) {
            return;
        }

        // 伤害后剩余（含吸收血量）> 0 → 不致命，不管
        if (health + absorb - dmg > 0) return;

 // ★ 免死从「梦境盔甲**单件**」升级为**套装效果**
        //   —— 必须四件齐全（头盔 + 盔甲 + 护腿 + 战靴）才免死
        if (!gsDreamFullSet(p)) return;

        // ★ 冷却中（默认 45 秒）→ 这次不免死
        if (gsDaCooldownLeft(p, cfg) > 0) return;

        // ① 拦截这次伤害
        event.setCancelled(true);
        try { gsDaMarkCooldown(p); } catch (e) { }

        // ② 补一口血 —— 免死之后往往只剩残血，不回血根本跑不掉（默认回到 12 点 = 6 颗心）
        //    只有"当前血量低于 heal"时才抬上去；比它高就不动（不扣血）
        try {
            var healTo = 0;
            try { healTo = 0 + cfg.heal; } catch (e2) { healTo = 0; }
            if (healTo > 0) {
                var maxHp = 20.0;
                try { maxHp = 0 + p.getMaxHealth(); } catch (e2) { maxHp = 20.0; }
                if (!(maxHp > 0)) maxHp = 20.0;
                if (healTo > maxHp) healTo = maxHp;
                var curHp = 0;
                try { curHp = 0 + p.getHealth(); } catch (e2) { curHp = 0; }
                if (healTo > curHp) p.setHealth(healTo);
            }
        } catch (e) { }

        // ③ 音效 + 粒子
        try {
            if (cfg.sound != null && ('' + cfg.sound).length > 0) {
                p.getWorld().playSound(p.getLocation(), '' + cfg.sound, 1.0, 1.0);
            }
        } catch (e) { }
        if (cfg.particle) gsTotemParticles(p);

        // ④ 耐久不消耗（释梦 / 梦境套已设为永不掉耐久，见 gsNoDurabilityRegisterSync）

        // ⑤ 提示
        try {
            if (cfg.msg != null && ('' + cfg.msg).length > 0) sendMessage(p, '' + cfg.msg);
        } catch (e) { }
    } catch (e) {
        // 这里绝不能往外抛，否则会影响伤害结算
    }
}

// ==========================================================================
// 「释梦」+「梦境套」永不掉耐久（等效 unbreakable）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §2
// ==========================================================================
var GS_NODMG_IDS = ['GS_RELEASE_DREAM', 'GS_DREAM_ARMOR', 'GS_DREAM_HELMET',
                    'GS_DREAM_LEGGINGS', 'GS_DREAM_BOOTS', 'GS_WULI_SPEAR',
 // ★ 奇思妙想帽（原版金头盔）按需求**不掉耐久**
                    'GS_THINKING_HAT',
 // ★ 入梦（铁剑）+ 梦想套四件 同样**不掉耐久**
                    'GS_ENTER_DREAM', 'GS_WISH_HELMET', 'GS_WISH_ARMOR',
                    'GS_WISH_LEGGINGS', 'GS_WISH_BOOTS',
 // ★ 月镰（铁锄）+ 蚀月·死神梦镰（下界合金锄）
                    //   两把都是**工具材质**，本来会掉耐久 ⇒ 一并纳入保护
                    'GS_MOON_SCYTHE', 'GS_ECLIPSE_SCYTHE',
 // ★ 你等着收我的律师函吧（黑色皮革胸甲）
                    //   按需求**不掉耐久**（皮革甲耐久很低，穿一会儿就破了）
                    'GS_LAWYER_LETTER',
 // ★ 致万千无言者（**石镐**）
                    //   石镐原版只有 131 点耐久，要拿它当主战武器砍 ⇒ 一并纳入保护
                    //   （做法与其它物品完全一致：**一个字节物品数据都不改**，
                    //     所以照样能当材料、能进背包堆叠）
                    'GS_VOICELESS',
 // ★ 此刻万籁俱寂（**金镐**）
                    //   金器原版只有 **33 点耐久**（全游戏最脆），拿它当主战武器砍几下就断
                    //   ⇒ 按需求纳入「永不掉耐久」（做法同上，物品数据一个字节都不改）
                    'GS_SILENCE',
 // ★ 阎魔刀·改（**金剑**）
                    //   金剑同样是 33 点耐久 ⇒ 按需求「不会掉耐久」纳入保护
                    'GS_ENMA_BLADE'];
var GS_NODMG_REGISTERED = false;

function gsIsNoDurabilityItem(item) {
    if (item == null) return false;
    try {
        var sf = getSfItemByItem(item);
        if (sf == null) return false;
        var id = '' + sf.getId();
        for (var i = 0; i < GS_NODMG_IDS.length; i++) {
            if (id === GS_NODMG_IDS[i]) return true;
        }
    } catch (e) { }
    return false;
}

// 事件监听：玩家物品即将掉耐久时直接取消
function gsNoDurabilityRegisterSync() {
    if (GS_NODMG_REGISTERED) return true;
    try {
        var EvClass = null;
        try { EvClass = Java.type('org.bukkit.event.player.PlayerItemDamageEvent'); } catch (e) { EvClass = null; }
        if (EvClass == null) return false;

        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function handle(e) {
            try {
                if (gsIsNoDurabilityItem(e.getItem())) e.setCancelled(true);
            } catch (err) { }
        }

        try {
            pm.registerEvent(EvClass, plugin, Priority.HIGHEST,
                function (l, e) { handle(e); }, plugin);
        } catch (e1) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handle(e); } });
                pm.registerEvent(EvClass, listener, Priority.HIGHEST, exec, plugin);
            } catch (e2) {
                return false;
            }
        }

        GS_NODMG_REGISTERED = true;
        gsDaLog('释梦 / 梦境套：永不掉耐久已启用（等效 unbreakable）');
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 耐久保护注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

// 兜底：某些版本护甲受损不一定触发 ItemDamageEvent，这里每 10 刻把装备栏补满
function gsRepairWorn(st, id) {
    if (st == null) return false;
    try {
        // id 留空 = 只要是「永不了耐久清单」里的东西都补（手上拿的用它）
        if (id == null || id === undefined || id === '') {
            if (!gsIsNoDurabilityItem(st)) return false;
        } else if (!gsIsItem(st, id)) { return false; }
    } catch (e) { return false; }
    try {
        var Damageable = null;
        try { Damageable = Java.type('org.bukkit.inventory.meta.Damageable'); } catch (e2) { Damageable = null; }
        if (Damageable == null) return false;
        var meta = null;
        try { meta = st.getItemMeta(); } catch (e2) { meta = null; }
        if (meta == null) return false;
        var d = null;
        try { d = Damageable.class.cast(meta); } catch (e2) { d = null; }
        if (d == null) return false;
        if (d.getDamage() <= 0) return false;      // 已满耐久 → 不动
        d.setDamage(0);
        try { st.setItemMeta(meta); } catch (e2) { return false; }
        return true;
    } catch (e) { return false; }
}

function gsNoDurabilitySweepAll() {
    try {
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
                var inv = p.getInventory();
                var st = null;
                st = inv.getHelmet();
                if (gsRepairWorn(st, 'GS_DREAM_HELMET') || gsRepairWorn(st, 'GS_THINKING_HAT')) inv.setHelmet(st);
                st = inv.getChestplate();
 // ★ 律师函（黑色皮革胸甲）同样定期把耐久补满
                if (gsRepairWorn(st, 'GS_DREAM_ARMOR') || gsRepairWorn(st, 'GS_LAWYER_LETTER')) inv.setChestplate(st);
                st = inv.getLeggings();
                if (gsRepairWorn(st, 'GS_DREAM_LEGGINGS')) inv.setLeggings(st);
                st = inv.getBoots();
                if (gsRepairWorn(st, 'GS_DREAM_BOOTS')) inv.setBoots(st);

                // ★ 手上拿着的（五骊矟 / 释梦）也补一下：
                //   武器掉耐久是 belongs to PlayerItemDamageEvent，某些版本挥空/砸方块
                //   不走那个事件，所以这里兜底把 damage 归零。
                try {
                    var mi = inv.getItemInMainHand();
                    if (gsRepairWorn(mi, null)) inv.setItemInMainHand(mi);
                } catch (e) { }
                try {
                    var oi = inv.getItemInOffHand();
                    if (gsRepairWorn(oi, null)) inv.setItemInOffHand(oi);
                } catch (e) { }
            } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 注册同步监听器 ----------------
function gsRegisterDreamArmor() {
    try {
        var pm = getServer().getPluginManager();
        var EDE = Java.type('org.bukkit.event.entity.EntityDamageEvent');
        var Priority = Java.type('org.bukkit.event.EventPriority');

        // Plugin 实例：RSC 主类有静态单例 INSTANCE（源码 register 用的就是它）
        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) {
            gsDaLog('梦境盔甲：拿不到 Plugin 实例，致命伤害拦截未注册');
            return false;
        }

        // ① 首选：直接传 JS 函数（Nashorn 会转成 EventExecutor）
        try {
            pm.registerEvent(EDE, plugin, Priority.HIGHEST,
                function (l, e) { gsOnEntityDamageSync(e); }, plugin);
            GS_DA_REGISTERED = true;
            gsDaLog('梦境盔甲：致命伤害拦截已注册（同步监听器）');
            return true;
        } catch (e1) {
            // ② 退路：用 JavaAdapter 造 Listener + EventExecutor
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, {
                    execute: function (l, e) { gsOnEntityDamageSync(e); }
                });
                pm.registerEvent(EDE, listener, Priority.HIGHEST, exec, plugin);
                GS_DA_REGISTERED = true;
                gsDaLog('梦境盔甲：致命伤害拦截已注册（JavaAdapter）');
                return true;
            } catch (e2) {
                gsDaLog('梦境盔甲：注册同步监听器失败 → ' + e1 + ' / ' + e2);
                return false;
            }
        }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 梦境盔甲注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

gsRegisterDreamArmor();

// ---------------- 兜底诊断 ----------------
// 万一同步监听器没注册成功（例如这个版本的 Nashorn 不支持 SAM 转换），
// 这个延迟回调至少能在后台告诉我们"刚刚有一次致命伤害没被拦下"。
function onEntityDamage(event) {
    try {
        if (GS_DA_REGISTERED) return;      // 正常工作时不做事（避免无谓开销）

        var ent = null;
        try { ent = event.getEntity(); } catch (e) { return; }
        if (ent == null) return;
        var en = '';
        try { en = '' + ent.getType().name(); } catch (e) { return; }
        if (en !== 'PLAYER') return;

        if (!gsDreamFullSet(ent)) return;      // ★ 第十二批：免死改为「套装四件齐全」

        var dmg = 0;
        var health = 0;
        try {
            dmg = event.getFinalDamage();
            health = ent.getHealth();
        } catch (e) {
            return;
        }
        if (dmg > 0 && health - dmg <= 0) {
            gsDaLog('梦境套：检测到致命伤害但同步监听器未生效，检查注册日志！');
        }
    } catch (e) {
    }
}

// ==========================================================================
// 梦境套「常驻粒子」：★ 改为**套装效果**（四件齐全才有）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §3
// ==========================================================================

var GS_AURA_KEY = null;
try { GS_AURA_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:aura_gen'); } catch (e) { GS_AURA_KEY = null; }

var GS_AURA_MY_GEN = -1;     // 本次加载拿到的世代号
var GS_AURA_CFG = null;      // 配置缓存（加载期读不到，跑起来再读）

// ---------------- 配置（懒加载 + 全容错） ----------------
function gsAuraConfig() {
    if (GS_AURA_CFG != null) return GS_AURA_CFG;

    var def = {
        enabled: true,
        eachCount: 6,           // 一圈几个点（1 种 × 6 = 6 个，一圈均分）
        radius: 0.80,           // 环绕半径（固定不动，保持不变）
        heightHigh: 0.10,       // 第 1 种粒子的高度（脚部平行）
        heightLow: 0.10,        // 第 2 种粒子的高度（脚部平行）
        particles: ['SOUL']
    };

    try {
        var cfg = getAddonConfig();
        if (cfg == null) return def;                 // 还没加载好 → 先用默认值，不缓存

        var sec = cfg.getConfigurationSection('dream-set.aura');
        if (sec == null) { GS_AURA_CFG = def; return GS_AURA_CFG; }

        def.enabled = sec.getBoolean('enabled', true);

        // 旧版配置提示：start-radius / end-radius / steps 是"聚拢版本"的键，现在已弃用
        if (sec.contains('start-radius') || sec.contains('end-radius') || sec.contains('steps')) {
            gsDaLog('梦境盔甲：config.yml 的 aura 段还是旧版（聚拢），'
                + '现在改成固定环绕 + 交叉排列 → 建议删掉 config.yml 让它重新生成');
        }

        def.eachCount = sec.getInt('each-count', 6);
        if (def.eachCount < 1) def.eachCount = 1;
        if (def.eachCount > 24) def.eachCount = 24;

        def.radius = sec.getDouble('radius', 0.80);
        def.heightHigh = sec.getDouble('height-high', 0.10);
        def.heightLow = sec.getDouble('height-low', 0.10);
        if (def.heightHigh < def.heightLow) {
            var tmp = def.heightHigh;
            def.heightHigh = def.heightLow;
            def.heightLow = tmp;
        }

        var list = sec.getStringList('particles');
        if (list != null && list.size() > 0) {
            var out = [];
            var it = list.iterator();
            while (it.hasNext() && out.length < 3) {     // 最多 3 种
                var n = ('' + it.next()).toUpperCase();
                if (n.indexOf('HEART') >= 0) continue;   // ♥ 爱心粒子强制跳过
                out.push(n);
            }
            if (out.length > 0) def.particles = out;
        }
    } catch (e) {
        return def;
    }

    GS_AURA_CFG = def;
    return GS_AURA_CFG;
}

// ---------------- 世代号（多实例去重） ----------------
function gsAuraGenWorld() {
    try { return getServer().getWorlds().get(0); } catch (e) { return null; }
}

function gsAuraGenBump() {
    try {
        if (GS_AURA_KEY == null || GS_PDT == null) return;
        var w = gsAuraGenWorld();
        if (w == null) return;
        var pdc = w.getPersistentDataContainer();
        var cur = pdc.get(GS_AURA_KEY, GS_PDT.INTEGER);
        var gen = (cur == null ? 0 : cur) + 1;
        pdc.set(GS_AURA_KEY, GS_PDT.INTEGER, gen);
        GS_AURA_MY_GEN = gen;
    } catch (e) {
    }
}

function gsAuraGenNow() {
    try {
        if (GS_AURA_KEY == null || GS_PDT == null) return -1;
        var w = gsAuraGenWorld();
        if (w == null) return -1;
        var v = w.getPersistentDataContainer().get(GS_AURA_KEY, GS_PDT.INTEGER);
        return (v == null) ? -1 : v;
    } catch (e) {
        return -1;
    }
}

// 本脚本实例是不是"当前那一份"（不是就说明 /rsc reload 过了，旧任务该停手）
// 两个常驻任务（粒子 / 冲击波）都用它，保证重载后旧任务自动失效
function gsAuraIsCurrent() {
    return (GS_AURA_MY_GEN === -1) || (gsAuraGenNow() === GS_AURA_MY_GEN);
}

// ---------------- 粒子名 → 枚举（跨版本容错） ----------------
function gsAuraParticle(name) {
    try {
        var P = Java.type('org.bukkit.Particle');
        try { if (P[name] != null) return P[name]; } catch (e) { }
        try { return P.valueOf(name); } catch (e) { }
        // 老版本换名的兜底
        var alts = { 'SCULK_SOUL': 'SOUL', 'SOUL': 'SCULK_SOUL', 'END_ROD': 'END_ROD', 'DRAGON_BREATH': 'DRAGON_BREATH' };
        var alt = alts[name];
        if (alt != null) { try { return P.valueOf(alt); } catch (e) { } }
    } catch (e) {
    }
    return null;
}

// ---------------- 每次脉冲 ----------------
function gsAuraPulse() {
    try {
        // 不是当前实例的任务（reload 之后的旧任务）→ 停手
        if (!gsAuraIsCurrent()) return;

        var cfg = gsAuraConfig();
        if (!cfg.enabled) return;

        var n = cfg.particles.length;                    // 粒子种类数（默认 2）
        if (n < 1) return;

        var total = n * cfg.eachCount;                   // 一圈总共几个点（默认 12）
        var stepA = (Math.PI * 2.0) / total;             // 均分一圈

        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
                if (!gsDreamFullSet(p)) continue;         // 只有四件齐全才刷（第十二批）

                var loc = p.getLocation();
                var x = loc.getX();
                var y = loc.getY();
                var z = loc.getZ();

                // 固定位置的 2×eachCount 个点：位置不随时间变化（只跟着人走）
                for (var i = 0; i < total; i++) {
                    var idx = i % n;                       // 交叉排列：一个隔一个换种类
                    var type = gsAuraParticle(cfg.particles[idx]);
                    if (type == null) continue;

                    var a = i * stepA;
                    var px = x + Math.cos(a) * cfg.radius;
                    var pz = z + Math.sin(a) * cfg.radius;
                    var py = y + (idx === 0 ? cfg.heightHigh : cfg.heightLow);

                    try {
                        p.getWorld().spawnParticle(type, px, py, pz, 1, 0.02, 0.02, 0.02, 0.0);
                    } catch (e) {
                    }
                }
            } catch (e) {
            }
        }
    } catch (e) {
    }
}

// ---------------- 启动 ----------------
function gsAuraStart() {
    try {
        if (GS_AURA_KEY != null && GS_PDT != null) gsAuraGenBump();

        var period = 3;
        try {
            var cfg = getAddonConfig();
            if (cfg != null) {
                var sec = cfg.getConfigurationSection('dream-set.aura');
                if (sec != null) {
                    var v = sec.getInt('interval-ticks', 3);
                    if (v >= 1 && v <= 100) period = v;
                }
            }
        } catch (e) {
        }

        runRepeating(function () { gsAuraPulse(); }, period, period);
        gsDaLog('梦境套：常驻粒子已启动（每 ' + period + ' 刻一次；★ 四件齐全才刷）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 梦境盔甲常驻粒子启动失败: ' + e); } catch (e2) { }
    }
}

gsAuraStart();

// ==========================================================================
// 梦境套「套装效果」：四件齐全才生效（）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §4
// ==========================================================================
var gsDreamFlyOn = {};   // uuid -> true（本机制负责的飞行）
var gsSetEffOn   = {};   // uuid -> [{t, amp}]（本机制给过的药效）
var gsSetNightOn = {};   // uuid -> true（这份夜视是本机制给的）
// ★ 防辐射状态表：**主线程独占**（套装脉冲读写，没有异步参与）⇒ 普通 JS 对象即可。
// ⚠ 曾用 ConcurrentHashMap 准备给「异步辐射事件」共享，
//     后来实测该事件根本进不来 JS（见下方 gsRadShield 的说明）⇒ 已改回普通对象。
var gsRadSafe  = {};   // uuid -> true（本机制正在保护中）

function gsRadMark(uuid, on) {
    if (on) { gsRadSafe[uuid] = true; } else { delete gsRadSafe[uuid]; }
}

function gsRadIsSafe(uuid) {
    return gsRadSafe[uuid] === true;
}

var GS_SET_EFF_TICKS = 600;   // 药效一次给 30 秒
var GS_SET_EFF_REF   = 540;   // 剩余低于 27 秒才续期（不闪图标 / 不闪画面）

function gsDreamFullSet(p) {
    var inv = p.getInventory();
    return gsIsItem(inv.getHelmet(),     'GS_DREAM_HELMET')
        && gsIsItem(inv.getChestplate(), 'GS_DREAM_ARMOR')
        && gsIsItem(inv.getLeggings(),   'GS_DREAM_LEGGINGS')
        && gsIsItem(inv.getBoots(),      'GS_DREAM_BOOTS');
}

// ---------------- 套装药效 ----------------
// 等级：生命恢复 II(amp1) · 迅捷 II(amp1) · 饱和 III(amp2) · 抗性提升 IV(amp3) · 急迫 III(amp2) · 夜视(amp0)
//   一次给 30 秒、剩余低于 27 秒才续期；摘套时立刻收走（只收本机制给的那份）。
function gsSetPotionType(name, alt) {
    var PET = null;
    try { PET = Java.type('org.bukkit.potion.PotionEffectType'); } catch (e) { PET = null; }
    if (PET == null) return null;
    var t = null;
    try { t = PET[name]; } catch (e) { t = null; }
    if (t == null) { try { t = PET.valueOf(name); } catch (e) { t = null; } }
    // 兼容改名：1.20.5+ 的抗性叫 RESISTANCE，旧版叫 DAMAGE_RESISTANCE
    if (t == null && alt != null) {
        try { t = PET[alt]; } catch (e) { t = null; }
        if (t == null) { try { t = PET.valueOf(alt); } catch (e) { t = null; } }
    }
    return t;
}

function gsSetEffects(p, want) {
    try {
        var uuid = '' + p.getUniqueId();

        // ================= 收走 =================
        if (!want) {
            // ① 夜视（单独一套标记：玩家自己喝的夜视一律不碰）
            if (gsSetNightOn[uuid] === true) {
                gsSetNightOn[uuid] = false;
                var nvT = gsSetPotionType('NIGHT_VISION', null);
                if (nvT != null) { try { p.removePotionEffect(nvT); } catch (e) { } }
            }
            // ② 其余五种（等级对得上，才认定是「我们给的那一份」）
            var arr = gsSetEffOn[uuid];
            gsSetEffOn[uuid] = null;
            if (arr != null) {
                for (var i = 0; i < arr.length; i++) {
                    try {
                        var t = arr[i].t;
                        var cur = p.getPotionEffect(t);
                        if (cur == null) continue;
                        if (cur.getAmplifier() !== arr[i].amp) continue;
                        p.removePotionEffect(t);
                    } catch (e) { }
                }
            }
            return;
        }

        // ================= 给 =================
        var PE = null;
        try { PE = Java.type('org.bukkit.potion.PotionEffect'); } catch (e) { PE = null; }
        if (PE == null) return;

        var effs = [
            { name: 'REGENERATION', alt: null,                amp: 1 },   // 生命恢复 II
            { name: 'SPEED',        alt: null,                amp: 1 },   // 迅捷 II
            { name: 'SATURATION',   alt: null,                amp: 2 },   // 饱和 III
            { name: 'RESISTANCE',   alt: 'DAMAGE_RESISTANCE', amp: 3 },   // 抗性提升 IV
            { name: 'HASTE',        alt: 'FAST_DIGGING',      amp: 2 }    // 急迫 III
        ];

        var applied = [];
        for (var j = 0; j < effs.length; j++) {
            var ef = effs[j];
            var et = gsSetPotionType(ef.name, ef.alt);
            if (et == null) continue;

            // ★ 先登记（不论本次是否真的补发），摘套时才知道该清哪些
            applied.push({ t: et, amp: ef.amp });

            // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
            var need = true;
            try {
                var c = p.getPotionEffect(et);
                if (c != null && c.getAmplifier() === ef.amp && c.getDuration() > GS_SET_EFF_REF) need = false;
            } catch (e) { }
            if (!need) continue;

            // ambient = true（金色边）、particles = false（不冒粒子）
            try { p.addPotionEffect(new PE(et, GS_SET_EFF_TICKS, ef.amp, true, false), true); }
            catch (e) { try { p.addPotionEffect(new PE(et, GS_SET_EFF_TICKS, ef.amp, true, false)); } catch (e2) { } }
        }
        gsSetEffOn[uuid] = applied;

        // ③ 夜视：玩家当前**已有夜视**（自己喝的）且不是本机制给的 → 完全不碰
        var nvT2 = gsSetPotionType('NIGHT_VISION', null);
        if (nvT2 != null) {
            var owned = false;
            try { owned = (gsSetNightOn[uuid] === true); } catch (e) { owned = false; }
            var nvCur = null;
            try { nvCur = p.getPotionEffect(nvT2); } catch (e) { nvCur = null; }

            if (nvCur != null && !owned) {
                // 别人给的 → 不触发、不接管、不登记
            } else {
                gsSetNightOn[uuid] = true;
                var left = 0;
                try { left = nvCur.getDuration(); } catch (e) { left = 0; }
                if (nvCur == null || left <= GS_SET_EFF_REF) {
                    try { p.addPotionEffect(new PE(nvT2, GS_SET_EFF_TICKS, 0, true, false), true); }
                    catch (e) { try { p.addPotionEffect(new PE(nvT2, GS_SET_EFF_TICKS, 0, true, false)); } catch (e2) { } }
                }
            }
        }
    } catch (e) { }
}

// ==========================================================================
// ---------------- 防辐射（粘液科技原版辐射机制） ----------------
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §5
// ==========================================================================
var GS_RAD_GRACE_MS = 15000;   // 与 Slimefun config 的 options.radiation-grace-period（15 秒）对齐

var RT_RAD = null;
try { RT_RAD = Java.type('io.github.thebusybiscuit.slimefun4.implementation.tasks.armor.RadiationTask'); } catch (e) { RT_RAD = null; }
var RU_RAD = null;
try { RU_RAD = Java.type('io.github.thebusybiscuit.slimefun4.utils.RadiationUtils'); } catch (e) { RU_RAD = null; }

function gsRadClear(p) {
    try { if (RU_RAD != null) RU_RAD.clearExposure(p); } catch (e) { }
}

// 每 10 刻由套装脉冲调用一次；on=false 表示「脱套了，停手」
function gsRadShield(p, uuid, on) {
    try {
        if (!on) {
            gsRadMark(uuid, false);
            return;
        }
        var was = gsRadIsSafe(uuid);
        gsRadMark(uuid, true);
        if (RT_RAD == null) return;

        // 续宽限期（每 0.5 秒一次；15 秒的窗口，刷新频率绰绰有余）
        try { RT_RAD.addGracePeriod(p); } catch (e) { }

        // 刚凑齐的那一次 → 把历史暴露值清零
        if (!was) {
            gsRadClear(p);
            gsDaLog('梦境套：防辐射已生效 —— 宽限期持续刷新（每 0.5 秒一次，窗口 '
                + (GS_RAD_GRACE_MS / 1000) + ' 秒）');
        }
    } catch (e) { }
}

// ---------------- 每次脉冲：对每个在线玩家结算套装效果 ----------------
function gsDreamSetPulse() {
    var players = getServer().getOnlinePlayers();
    var it = players.iterator();
    while (it.hasNext()) {
        var p = it.next();
        try {
            var uuid = '' + p.getUniqueId();

            // 创造 / 旁观模式：飞行不动（他们本来就该能飞）
            var creative = false;
            try {
                var gm = '' + p.getGameMode().name();
                creative = (gm === 'CREATIVE' || gm === 'SPECTATOR');
            } catch (e) { }

            if (gsDreamFullSet(p)) {
 // ① 入梦（飞行）—— ★ 不再提示「入梦」（除提示词）
                if (creative) {
                    gsDreamFlyOn[uuid] = false;
                } else {
                    var allow = false;
                    try { allow = p.getAllowFlight(); } catch (e) { allow = false; }
                    if (!allow) { try { p.setAllowFlight(true); } catch (e) { } }
                    gsDreamFlyOn[uuid] = true;
                }
                // ② 常驻药效
                gsSetEffects(p, true);
                // ③ 防辐射（续宽限期 · 主线程）
                gsRadShield(p, uuid, true);
            } else {
                // 摘掉任意一件 → 全部收走
                if (gsDreamFlyOn[uuid]) {
                    if (!creative) { try { p.setAllowFlight(false); } catch (e) { } }
                    gsDreamFlyOn[uuid] = false;
                }
                gsSetEffects(p, false);
                gsRadShield(p, uuid, false);
            }
        } catch (e) { }
    }
}

// 每 1 粘液刻（10 刻）跑一次的"装备维护"任务
function gsEquipPulse() {
    try {
        if (!gsAuraIsCurrent()) return;          // 重载后的旧任务 → 停手
        gsDreamSetPulse();                       // 梦境套齐全 → 入梦 / 免死 / 药效 / 防辐射
        gsNoDurabilitySweepAll();                // 兜底：把磨损的释梦/梦境套耐久补满
    } catch (e) {
    }
}

// 启动：套装维护任务（防辐射是主线程的宽限期刷新，已并入 gsDreamSetPulse）
function gsSetBonusStart() {
    try {
        runRepeating(function () { gsEquipPulse(); }, 10, 10);   // 每 1 粘液刻 = 10 游戏刻
        gsDaLog('梦境套：套装效果已就绪（入梦·飞行 / 免死 ' + (gsDaConfig().cooldown / 1000)
            + ' 秒 / 生命恢复II·夜视·迅捷II·饱和III·抗性IV·急迫III / 防辐射(宽限期) / 粒子圈）');
        if (RT_RAD == null) gsDaLog('梦境套：⚠ 拿不到 RadiationTask → 防辐射不可用');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 梦境套套装效果启动失败: ' + e); } catch (e2) { }
    }
}

gsSetBonusStart();


// 「麦友的酒」召唤物标记（key 必须与 scripts/gs_maiyou_wine.js 里一致）
//   —— 多处伤害判定遇到带此标记的实体要**跳过**：它是召唤物、不是敌人。
var GS_MAIYOU_KEY = null;
try { GS_MAIYOU_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:maiyou_fish'); } catch (e) { GS_MAIYOU_KEY = null; }

function gsIsMaiyouSummon(ent) {
    try {
        if (ent == null || GS_MAIYOU_KEY == null) return false;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        var pdc = ent.getPersistentDataContainer();
        try { if (pdc.has(GS_MAIYOU_KEY, PDT.STRING)) return true; } catch (e) { }
        try { if (pdc.has(GS_MAIYOU_KEY, PDT.BYTE)) return true; } catch (e) { }   // 兼容旧标记
        return false;
    } catch (e) { return false; }
}

// ==========================================================================
// 余念绝响（音乐唱片5 · 单手持冲锋枪）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §6
// ==========================================================================

var GS_PSYCHIC_ID = "GS_PSYCHIC_POWER";
var GS_PSY_MUZZLE_Y = 1.2;            // 发射高度（脚底往上 1.2 格 = 胸口；梦境头盔冲击波复用）
var GS_PSY_AMMO = 37;                 // 弹容量
var GS_PSY_FIRE_MS = 30;              // 射击间隔（毫秒）
var GS_PSY_RELOAD_MS = 1500;          // 换弹时长（毫秒）
var GS_PSY_RANGE = 25.0;              // 射程（格）
var GS_PSY_HIT_R = 0.8;               // 命中判定半径（格）
var GS_PSY_DMG_MIN = 3.0;             // 伤害下限
var GS_PSY_DMG_MAX = 6.0;             // 伤害上限

var GS_PSY_KEYS = null;               // { ammo, lastShot, reload, shooting }
var GS_PSY_REGISTERED = false;

function gsPsychicKeys() {
    if (GS_PSY_KEYS != null) return GS_PSY_KEYS;
    var o = { ammo: null, lastShot: null, reload: null, shooting: null };
    try {
        var NK = Java.type('org.bukkit.NamespacedKey');
        o.ammo = NK.fromString('gs:psy_ammo');
        o.lastShot = NK.fromString('gs:psy_lastshot');
        o.reload = NK.fromString('gs:psy_reload');
        o.shooting = NK.fromString('gs:psy_shooting');
    } catch (e) {
        return o;
    }
    GS_PSY_KEYS = o;
    return GS_PSY_KEYS;
}

function gsPsychicPdc(p) {
    try { return p.getPersistentDataContainer(); } catch (e) { return null; }
}

function gsPsychicIsPsychic(item) {
    return gsIsItem(item, GS_PSYCHIC_ID);
}

function gsPsychicNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); } catch (e) { return 0; }
}

// ★★★ 换弹从 Q 改到 F ⇒ 原来那套「Q 的挥手不算开火」的闸门
//   （GS_PSY_Q_AT / GS_PSY_Q_GAP / gsPsychicMarkQ / gsPsychicJustQ）**整个删除**。
//   ★ 为什么不再需要（已反编译核实客户端 XGCraft1.21.11.jar 的字节码）：
//     · Q（丢弃）走 LocalPlayer.drop(boolean)：发完 DROP_ITEM 包之后
//       **还会调一次 swing(MAIN_HAND)** ⇒ 多一个挥手包 ⇒ 被「左键=开火」误判；
//     · F（换手）走 Minecraft.handleKeybinds 里的 swap-offhand 分支，
//       整个分支只有三件事：判 isSpectator → connection.send(
//       new ServerboundPlayerActionPacket(SWAP_ITEM_WITH_OFFHAND, ZERO, DOWN))
//       ⇒ **一个 swing 都不调** ⇒ 不会有多余的挥手包。
//   ⇒ 换成 F 之后这个闸门没有存在意义，删掉（不留死代码）。

// 读弹药（没存过 = 满弹）
function gsPsychicAmmo(p) {
    try {
        var k = gsPsychicKeys();
        if (k.ammo == null || GS_PDT == null) return GS_PSY_AMMO;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return GS_PSY_AMMO;
        var v = pdc.get(k.ammo, GS_PDT.INTEGER);
        return (v == null) ? GS_PSY_AMMO : v;
    } catch (e) { return GS_PSY_AMMO; }
}

function gsPsychicSetAmmo(p, v) {
    try {
        var k = gsPsychicKeys();
        if (k.ammo == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc != null) pdc.set(k.ammo, GS_PDT.INTEGER, v);
    } catch (e) { }
}

// 是否正在换弹
function gsPsychicIsReloading(p) {
    try {
        var k = gsPsychicKeys();
        if (k.reload == null || GS_PDT == null) return false;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.reload, GS_PDT.LONG);
    } catch (e) { return false; }
}

function gsPsychicReloadLeft(p) {
    try {
        if (!gsPsychicIsReloading(p)) return 0;
        var k = gsPsychicKeys();
        var pdc = gsPsychicPdc(p);
        var start = pdc.get(k.reload, GS_PDT.LONG);
        if (start == null) return 0;
        var left = GS_PSY_RELOAD_MS - (gsPsychicNow() - (0 + start));
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

// 上次开火距今（毫秒）
function gsPsychicLastShotAgo(p) {
    try {
        var k = gsPsychicKeys();
        if (k.lastShot == null || GS_PDT == null) return 999999;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return 999999;
        var last = pdc.get(k.lastShot, GS_PDT.LONG);
        if (last == null) return 999999;
        return gsPsychicNow() - (0 + last);
    } catch (e) { return 999999; }
}

function gsPsychicMarkShot(p) {
    try {
        var k = gsPsychicKeys();
        if (k.lastShot == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc != null) pdc.set(k.lastShot, GS_PDT.LONG, gsPsychicNow());
    } catch (e) { }
}

function gsPsychicMarkReload(p) {
    try {
        var k = gsPsychicKeys();
        if (k.reload == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc != null) pdc.set(k.reload, GS_PDT.LONG, gsPsychicNow());
    } catch (e) { }
}

function gsPsychicClearReload(p) {
    try {
        var k = gsPsychicKeys();
        if (k.reload == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc != null) pdc.remove(k.reload);
    } catch (e) { }
}

// shooting 标记：弹道造成带来源伤害时，让 EntityDamageByEntityEvent 监听器放行
function gsPsychicSetShootingFlag(p, on) {
    try {
        var k = gsPsychicKeys();
        if (k.shooting == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return;
        // ★★★ STRING 而不是 BYTE（同 gsScopeSetScoped 的注释：
        //   BYTE + JS 数字会装箱成 Integer ⇒ checkcast Byte 失败 ⇒ **静默失败**）
        if (on) pdc.set(k.shooting, GS_PDT.STRING, '1');
        else pdc.remove(k.shooting);
    } catch (e) { }
}

function gsPsychicIsShooting(p) {
    try {
        var k = gsPsychicKeys();
        if (k.shooting == null || GS_PDT == null) return false;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.shooting, GS_PDT.STRING);
    } catch (e) { return false; }
}

// 颜色码转换：& → §
function gsPsychicColor(s) {
    try {
        var CC = Java.type('org.bukkit.ChatColor');
        return CC.translateAlternateColorCodes('&', s);
    } catch (e) {
        return ('' + s).replace(/&([0-9a-fk-or])/g, '\u00a7$1');
    }
}

// 更新物品名字：正常「余念绝响<弹药>」，换弹中「余念绝响<R>」
// ★★ 物品由「精神力」改名为「**余念绝响**」（用户「精神力改名叫余念绝响」）
//      ⇒ 这里两处显示名跟着改；**弹药逻辑一个字没动**。
function gsPsychicUpdateName(p) {
    try {
        var inv = p.getInventory();
        var hand = null;
        try { hand = inv.getItemInMainHand(); } catch (e) { hand = null; }
        if (hand == null || !gsPsychicIsPsychic(hand)) return;

        var meta = null;
        try { meta = hand.getItemMeta(); } catch (e) { meta = null; }
        if (meta == null) return;

        var name;
        var a = gsPsychicAmmo(p);
        if (gsPsychicReloadLeft(p) > 0) {
            // 换弹中：保留弹药数字，右上角加一个上标小 R（ᴿ）
            name = gsPsychicColor('&d余念绝响<' + a + '\u1d3f>');
        } else {
            name = gsPsychicColor('&d余念绝响<' + a + '>');
        }
        try { meta.setDisplayName(name); } catch (e) { }
        hand.setItemMeta(meta);
        try { inv.setItemInMainHand(hand); } catch (e) { }
    } catch (e) { }
}

// ---------------- 开火（左键）----------------
function gsPsychicFire(p) {
    try {
        if (p == null) return;

        // 射速限制
        if (gsPsychicLastShotAgo(p) < GS_PSY_FIRE_MS) return;

        var a = gsPsychicAmmo(p);

        // ★ 弹药为 0 → 强制不能开火（不打断换弹、不重复触发换弹）
        if (a <= 0) {
            // 没在换弹才触发换弹；正在换弹就让它继续换，别打断
            if (gsPsychicReloadLeft(p) <= 0) gsPsychicStartReload(p);
            return;
        }

        // ★ 有弹药时，换弹中开火 → 打断换弹（清除换弹标记），继续正常开火
        if (gsPsychicReloadLeft(p) > 0) gsPsychicClearReload(p);

        a--;
        gsPsychicSetAmmo(p, a);
        gsPsychicMarkShot(p);

        gsPsychicShoot(p);
        gsPsychicUpdateName(p);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 余念绝响开火出错: ' + e); } catch (e2) { }
    }
}

// 冲击波式伤害：沿胸口射线检测，命中生物立即结算伤害。
// ★ 开瞄准镜（右键开，PDC 标记 gs:psy_scoped）时伤害 37→77、射程 25→77
function gsPsychicShoot(p) {
    try {
        var world = p.getWorld();
        var eye = null;
        try { eye = p.getEyeLocation(); } catch (e) { return; }
        var dir = eye.getDirection();                 // 方向 = 视线（准星）

        // ★ 发射起点改为「胸口」：从眼睛发射会挡住视线
        var base = null;
        try { base = p.getLocation(); } catch (e) { return; }
        var sx = base.getX();
        var sy = base.getY() + GS_PSY_MUZZLE_Y;
        var sz = base.getZ();

        // ★ 开了瞄准镜（右键开 / 关）→ 伤害 37→77、射程 25→77
        var scoped = gsScopeHas(p);
        var dmg = scoped ? 77.0 : 37.0;
        var range = scoped ? 77.0 : GS_PSY_RANGE;

        var selfId = '' + p.getUniqueId();

        // 音效：石按钮点击声（发射时，从胸口位置响；pitch 0.8 更闷、更沉）
        try {
            var LocS = Java.type('org.bukkit.Location');
            world.playSound(new LocS(world, sx, sy, sz), 'block.stone_button.click_on', 0.9, 0.8);
        } catch (e) { }

        // 冲击波「粒子线」：END_ROD 细光点（不再用大块 SONIC_BOOM，免得挡视线）
        var so = null;
        try {
            var Particle = Java.type('org.bukkit.Particle');
            try { so = Particle.END_ROD; } catch (e) { so = null; }
            if (so == null) { try { so = Particle.valueOf('END_ROD'); } catch (e) { so = null; } }
        } catch (e) { }

        // 沿视线逐格推进做射线检测（步长 0.5 格，保证命中精确）
        var Loc = Java.type('org.bukkit.Location');
        var d = 0.0;
        var step = 0;
        while (d <= range) {
            var pt = new Loc(world,
                sx + dir.getX() * d,
                sy + dir.getY() * d,
                sz + dir.getZ() * d);

            // 冲击波粒子光柱（每隔 1 格放一个）
            step++;
            if (so != null && step % 2 === 1) {
                try { world.spawnParticle(so, pt, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
            }

            // 撞墙停
            try {
                var b = pt.getBlock();
                if (b != null && b.getType() != null && b.getType().isSolid()) return;
            } catch (e) { }

            // 命中生物 → 立即伤害
            try {
                var list = world.getNearbyEntities(pt, GS_PSY_HIT_R, GS_PSY_HIT_R, GS_PSY_HIT_R);
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e1) { continue; }
                    if (eid === selfId) continue;
                    try { ent.getHealth(); } catch (e2) { continue; }
                    try { if (ent.isDead()) continue; } catch (e3) { }
                    try {
                        var tn = '' + ent.getType().name();
                        if (tn === 'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM') continue;
                    } catch (e4) { }

                    // ★ 立即结算伤害（带来源，靠 shooting 标记放行）
                    gsPsychicHitDamage(p, ent, dmg);
                    return;                   // 命中一个就停
                }
            } catch (e) { }

            d += 0.5;
        }
    } catch (e) { }
}

// 结算伤害（带来源，靠 shooting 标记让 EntityDamageByEntityEvent 监听器放行）
// ★ 清受击无敌帧（setNoDamageTicks(0)），让伤害立即生效、可连续结算
function gsPsychicHitDamage(shooter, victim, dmg) {
    try {
        if (victim == null) return;
        try { if (victim.isDead()) return; } catch (e) { return; }
        try { victim.getHealth(); } catch (e) { return; }

        // 清掉受击无敌帧 → 精神力伤害没有受击间隔
        try { victim.setNoDamageTicks(0); } catch (e) { }

        gsPsychicSetShootingFlag(shooter, true);
        try { victim.damage(dmg, shooter); }
        catch (e1) { try { victim.damage(dmg); } catch (e2) { } }
        gsPsychicSetShootingFlag(shooter, false);
    } catch (e) { }
}

// ---------------- 换弹 ----------------
function gsPsychicStartReload(p) {
    try {
        // ★ 正在换弹 → 直接忽略，绝不重置换弹进度
        //   （否则连按换弹键会把计时一次次归零，弹永远换不满）
        if (gsPsychicReloadLeft(p) > 0) return;

        // ★ 弹夹已经满了 → 没必要换（静默返回，不提示）
        var ammo = gsPsychicAmmo(p);
        if (ammo >= GS_PSY_AMMO) return;

        gsPsychicMarkReload(p);
        gsPsychicUpdateName(p);

        // 换弹音效：弩装填（音量 2.0，比默认更响、传得更远）
        try {
            var loc = p.getLocation();
            var world = p.getWorld();
            world.playSound(loc, 'item.crossbow.loading_start', 3.0, 1.0);
        } catch (e) { }
    } catch (e) { }
}

function gsPsychicFinishReload(p) {
    try {
        gsPsychicSetAmmo(p, GS_PSY_AMMO);
        gsPsychicClearReload(p);
        gsPsychicUpdateName(p);
    } catch (e) { }
}

// ==========================================================================
// 余念绝响「念力感知」（开镜）：手持余念绝响时**按右键**开 / 关，
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §16
// ==========================================================================
var GS_SCOPE_RADIUS = 0.22;      // 准星圆半径（格）——「再小一点点」
var GS_SCOPE_DIST   = 1.6;       // 圆环出现在眼前多远（格）
var GS_SCOPE_POINTS = 12;        // 圆周上的粒子数（越小越接近点状、越细）
// ★★★ 开镜状态存在**玩家 PDC**（不再靠副手物品）
var GS_SCOPE_SCOPED_KEY = null;
try { GS_SCOPE_SCOPED_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:psy_scoped'); } catch (e) { GS_SCOPE_SCOPED_KEY = null; }
// ★ 修正 5 之前那枚「念力感知」按钮的 PDC 标记 —— 现在**只用来认出遗留物品并清掉**
var GS_SCOPE_KEY = null;
try { GS_SCOPE_KEY = Java.type('org.bukkit.NamespacedKey').fromString('gs:psy_scope'); } catch (e) { GS_SCOPE_KEY = null; }
var GS_SCOPE_TOGGLE_AT = {};      // uuid -> 上次开/关的时间戳(ms)，用于「按一次只切一次」的去抖
// ★★★ 防抖窗口 —— 用户点名「做一个0.8秒的防抖延迟」
//   （原来是 250ms，只够挡掉"同一事件被重复注册的监听器处理多遍"；
//     800ms 还顺手挡掉玩家的连点 ⇒ 一下出现、再一下消失，节奏干净。）
var GS_SCOPE_DEBOUNCE_MS = 800;
var gsScopePurged = {};           // uuid -> true（本次运行已经清过遗留按钮）

function gsScopeIsButton(item) {
    if (item == null || GS_SCOPE_KEY == null) return false;
    try {
        var meta = item.getItemMeta();
        if (meta == null) return false;
        var PDT = Java.type('org.bukkit.persistence.PersistentDataType');
        var v = meta.getPersistentDataContainer().get(GS_SCOPE_KEY, PDT.STRING);
        return v != null;
    } catch (e) { return false; }
}

// ★★★ 第六十四批修正 5：**gsScopeMakeButton 整个删掉** ——
//   开镜不再需要那枚「念力感知」按钮（用户「可以做到不需要副手吗」）。
//   ⇒ 副手从此**完全不受影响**：不会被塞东西、原来的副手物品也不会被挪进背包。

// ★ 开镜状态：读玩家 PDC 的 gs:psy_scoped 标记
// ★★★ 必须用 **STRING**、绝对不能用 BYTE（踩过的真 bug）：
//     `pdc.set(key, PDT.BYTE, 1)` 的第三个参数擦除后是 Object，
//     GraalJS 会把 JS 数字 1 装箱成 **Integer**，而 BytePersistentDataType 的
//     bridge 方法要 **checkcast Byte** ⇒ 抛 ClassCastException；
//     这里外面包着 try/catch ⇒ **静默失败**：标记永远写不进去，
//     于是 gsScopeHas 恒为 false ⇒ 增伤 / 射程 / 准星圆环 / 缓慢 / 夜视
//     **一起消失**（症状：按右键像没反应）。
//   ★ 这个坑本附属早就踩过并记录在案 —— scripts/gs_maiyou_wine.js 第 94 行：
//     「用 STRING 而不是 BYTE：set(key, PDT.BYTE, 1) 的第三个参数要装箱成
//       java.lang.Byte，容易静默失败…字符串没有装箱歧义，最稳。」
//     字符串没有装箱歧义，最稳。
function gsScopeScoped(p) {
    try {
        if (GS_SCOPE_SCOPED_KEY == null || GS_PDT == null) return false;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return false;
        return pdc.has(GS_SCOPE_SCOPED_KEY, GS_PDT.STRING);
    } catch (e) { return false; }
}

function gsScopeSetScoped(p, on) {
    try {
        if (GS_SCOPE_SCOPED_KEY == null || GS_PDT == null) return;
        var pdc = gsPsychicPdc(p);
        if (pdc == null) return;
        // ★★★ STRING，不是 BYTE（理由见上面那段注释）
        if (on) pdc.set(GS_SCOPE_SCOPED_KEY, GS_PDT.STRING, '1');
        else pdc.remove(GS_SCOPE_SCOPED_KEY);
    } catch (e) { }
}

// ★ 名字**保持 gsScopeHas** —— 开火伤害/射程、准星圆环、专注、夜视全都靠它，
//   只把内部实现从"查副手物品"换成"查 PDC 标记"即可，下游一处都不用改。
function gsScopeHas(p) {
    return gsScopeScoped(p);
}

// ★ 把修正 5 之前造的「念力感知」按钮从玩家身上清掉（每人只扫一次）。
//   那些按钮是本机制运行时现造的、打了 gs:psy_scope 标记 ⇒ 删掉绝对安全，
//   也不会误伤玩家的任何真物品。
function gsScopePurge(p) {
    try {
        var uuid = '' + p.getUniqueId();
        if (gsScopePurged[uuid] === true) return;
        gsScopePurged[uuid] = true;
        var inv = p.getInventory();
        // getContents() 对玩家来说是 41 格（主背包 0-35 + 护甲 36-39 + 副手 40）
        var cont = inv.getContents();
        for (var i = 0; i < cont.length; i++) {
            if (gsScopeIsButton(cont[i])) {
                try { inv.setItem(i, null); } catch (e1) { }
            }
        }
    } catch (e) { }
}

// 右键：开 / 关瞄准镜（★★ 从 F 键挪过来）
// ★★★ **只翻一个 PDC 标记，副手一个格子都不碰**。
function gsScopeToggle(p) {
    try {
        // ★★ 防抖（用户「做一个0.8秒的防抖延迟」）：
        //   /rsc reload 之后脚本会**再执行一次**、于是同一按键事件被注册了
        //   多个监听器实例，一次右键会被处理 N 遍 —— 而「开/关」是翻转操作，
        //   翻转偶数次就等于没变（开了又立刻关），玩家看到的就是"按右键没反应"。
        //   （左键开火 / Q 换弹不受影响：前者有射速限制、后者本身幂等。）
        //   800ms 窗口保证「一次按键只切换一次」，也顺手挡掉玩家的连点。
        try {
            var uuid = '' + p.getUniqueId();
            var nowMs = gsPsychicNow();
            var lastMs = GS_SCOPE_TOGGLE_AT[uuid];
            if (lastMs != null && (nowMs - lastMs) < GS_SCOPE_DEBOUNCE_MS) return;
            GS_SCOPE_TOGGLE_AT[uuid] = nowMs;
        } catch (e0) { }

        if (gsScopeScoped(p)) {
            // ★ 关镜：标记一清，准星 / 专注 / 夜视 / 伤害加成**立刻全部消失**
            gsScopeSetScoped(p, false);
            gsScopeUnfocus(p);                 // ★ 关闭瞬间就解除「专注」（移除缓慢 + 夜视）
            try { p.getWorld().playSound(p.getLocation(), 'item.spyglass.stop_using', 2.0, 1.0); } catch (e) { }
            return;
        }
        // ★ 开镜：只写标记 —— **副手不碰、不塞按钮、不动玩家原来的副手物品**
        gsScopeSetScoped(p, true);
        gsScopeFocus(p);                       // ★ 立刻上「专注」（不等下一跳心跳）
        try { p.getWorld().playSound(p.getLocation(), 'item.spyglass.use', 2.0, 1.0); } catch (e) { }
    } catch (e) { }
}

// 把副手原来的东西请出去：优先塞进背包，背包塞不下的部分掉在脚边
function gsScopeStash(p, stack) {
    if (stack == null) return;
    try {
        var n = '';
        try { n = '' + stack.getType().name(); } catch (e) { n = ''; }
        if (n === 'AIR' || n === 'CAVE_AIR' || n === 'VOID_AIR') return;
    } catch (e) { return; }
    try {
        var inv = p.getInventory();
        var rest = inv.addItem(stack.clone());
        if (rest != null && !rest.isEmpty()) {
            var it = rest.values().iterator();
            while (it.hasNext()) {
                try { p.getWorld().dropItemNaturally(p.getLocation(), it.next()); } catch (e2) { }
            }
        }
    } catch (e) { }
}

// 开启瞄准镜时的「专注」状态：缓慢 X（amplifier 9）。
// 节奏与精神力本体药水一致：**一次给 30 秒，剩余低于 27 秒才续期**（不闪图标）。
// ★★ 一旦**关镜**或**切走本武器**（PDC 标记被清掉），**立刻移除**这份缓慢 —— 见 gsScopeUnfocus。
//   ★ 修正 5：以前是"念力感知一离开副手"，现在没有副手按钮了，改成看那个 PDC 标记。
var GS_SCOPE_FOCUS_TICKS = 600;  // 一次给 30 秒
var GS_SCOPE_FOCUS_REF   = 540;  // 剩余低于 27 秒才续期
// ★ 开镜时**额外附带夜视**，
//   同一套节奏：一次给 30 秒、剩余低于 27 秒才续期。
//   ★ 规则：若玩家**当前已有夜视**（自己喝的药水 / 梦境护腿给的）→ 本机制
//     **不触发、也不接管**；收镜时更不会去摘别人给的那份
//     （只有"本机制给过"的才摘，靠 gsScopeNightOn 标记，绝不误伤）。
var GS_SCOPE_NV_TICKS = 600;     // 夜视：一次给 30 秒
var GS_SCOPE_NV_REF   = 540;     // 夜视：剩余低于 27 秒才续期
var gsScopeNightOn = {};         // uuid -> true（这份夜视是本机制给的）
function gsScopeFocus(p) {
    try {
        // ★ 已经关镜（PDC 标记没了）→ 立刻把这份缓慢磨掉
        if (!gsScopeHas(p)) { gsScopeUnfocus(p); return; }
        var PET = null, PE = null;
        try { PET = Java.type('org.bukkit.potion.PotionEffectType'); } catch (e) { PET = null; }
        try { PE = Java.type('org.bukkit.potion.PotionEffect'); } catch (e) { PE = null; }
        if (PET == null || PE == null) return;

        // 1.20.5+ 叫 SLOWNESS，之前叫 SLOW
        var t = null;
        try { t = PET.SLOWNESS; } catch (e) { t = null; }
        if (t == null) { try { t = PET.valueOf('SLOWNESS'); } catch (e) { t = null; } }
        if (t == null) { try { t = PET.SLOW; } catch (e) { t = null; } }
        if (t == null) { try { t = PET.valueOf('SLOW'); } catch (e) { t = null; } }
        if (t == null) return;

        // ★ 先登记"这份缓慢归本机制管" —— 必须放在「够新鲜就跳过」**之前**，
        //   否则续期的那些轮次不会登记，标记会漏掉（切走时就清不掉了）
        try { gsScopeFocusOn['' + p.getUniqueId()] = true; } catch (e) { }

        // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
        // ★ 用 needSlow 标记而不是直接 return —— 否则这一轮会**连夜视那段一起跳过**
        //   （缓慢与夜视是两套独立效果，必须各自判断，不能互相带跑）
        var needSlow = true;
        try {
            var cur = p.getPotionEffect(t);
            if (cur != null && cur.getAmplifier() === 9 && cur.getDuration() > GS_SCOPE_FOCUS_REF) needSlow = false;
        } catch (e) { }

        if (needSlow) {
            try { p.addPotionEffect(new PE(t, GS_SCOPE_FOCUS_TICKS, 9, true, false), true); }
            catch (e) { try { p.addPotionEffect(new PE(t, GS_SCOPE_FOCUS_TICKS, 9, true, false)); } catch (e2) { } }
        }

        // ---------- ★ 夜视（一次 30 秒、剩余低于 27 秒续期）----------
        // ★ 玩家当前**已有夜视**（不是本机制给的）→ 什么都不做：不触发、不接管、不登记。
        var nvT = null;
        try { nvT = PET.NIGHT_VISION; } catch (e) { nvT = null; }
        if (nvT == null) { try { nvT = PET.valueOf('NIGHT_VISION'); } catch (e) { nvT = null; } }
        if (nvT != null) {
            var nvUuid = '' + p.getUniqueId();
            var nvOwned = false;
            try { nvOwned = (gsScopeNightOn[nvUuid] === true); } catch (e) { nvOwned = false; }
            var nvCur = null;
            try { nvCur = p.getPotionEffect(nvT); } catch (e) { nvCur = null; }

            if (nvCur != null && !nvOwned) {
                // 别人给的（自己喝的 / 梦境护腿）→ 完全不碰
            } else {
                try { gsScopeNightOn[nvUuid] = true; } catch (e) { }
                var nvLeft = 0;
                try { nvLeft = nvCur.getDuration(); } catch (e) { nvLeft = 0; }
                // 还够久（> 27 秒）→ 这次不续，避免图标 / 画面闪烁
                if (nvCur == null || nvLeft <= GS_SCOPE_NV_REF) {
                    try { p.addPotionEffect(new PE(nvT, GS_SCOPE_NV_TICKS, 0, true, false), true); }
                    catch (e) { try { p.addPotionEffect(new PE(nvT, GS_SCOPE_NV_TICKS, 0, true, false)); } catch (e2) { } }
                }
            }
        }
    } catch (e) { }
}

// 关闭瞄准镜 / 切走本武器 → 立刻解除「专注」（把刚才给的缓慢 + 夜视移除掉）
var gsScopeFocusOn = {};   // uuid -> true（这份缓慢是本机制给的）
function gsScopeUnfocus(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        var flagged = !!gsScopeFocusOn[uuid];
        gsScopeFocusOn[uuid] = false;

        var PET = null;
        try { PET = Java.type('org.bukkit.potion.PotionEffectType'); } catch (e) { PET = null; }
        if (PET == null) return;

        // ★ 夜视：只有"本机制给过"的才摘（玩家自己喝的 / 梦境护腿给的 → 一律不碰）
        var nvFlagged = false;
        try { nvFlagged = (gsScopeNightOn[uuid] === true); } catch (e) { nvFlagged = false; }
        try { gsScopeNightOn[uuid] = false; } catch (e) { }
        if (nvFlagged) {
            var nvT = null;
            try { nvT = PET.NIGHT_VISION; } catch (e) { nvT = null; }
            if (nvT == null) { try { nvT = PET.valueOf('NIGHT_VISION'); } catch (e) { nvT = null; } }
            if (nvT != null) { try { p.removePotionEffect(nvT); } catch (e) { } }
        }

        var t = null;
        try { t = PET.SLOWNESS; } catch (e) { t = null; }
        if (t == null) { try { t = PET.valueOf('SLOWNESS'); } catch (e) { t = null; } }
        if (t == null) { try { t = PET.SLOW; } catch (e) { t = null; } }
        if (t == null) { try { t = PET.valueOf('SLOW'); } catch (e) { t = null; } }
        if (t == null) return;

        // 两种情况都清掉：
        //   ① 本机制确实给过（uuid 标记还在）
        //   ② 当前身上正好是「缓慢 X（amplifier 9）」—— 这是本机制专属的等级，
        //      原版药水最高只到缓慢 IV(amp 3)，amp 9 不可能是玩家自己喝的，删了很安全。
        //      （② 是为了兜住「标记丢失」的情况，比如中途 /rsc reload 过。）
        var amp = -1;
        try {
            var cur = p.getPotionEffect(t);
            if (cur != null) { try { amp = cur.getAmplifier(); } catch (e2) { amp = -1; } }
        } catch (e) { amp = -1; }

        if (flagged || amp === 9) {
            try { p.removePotionEffect(t); } catch (e) { }
        }
    } catch (e) { }
}

// 每 2 刻画一次准星圆环（空心：圆周一圈粒子，圆心不挡视线）
function gsScopeDraw(p) {
    try {
        if (!gsScopeHas(p)) return;

        // 细粒子：优先 ELECTRIC_SPARK（细小电火花），其次 CRIT（小星点），最后 END_ROD
        var t = null;
        try {
            var Particle = Java.type('org.bukkit.Particle');
            try { t = Particle.ELECTRIC_SPARK; } catch (e) { t = null; }
            if (t == null) { try { t = Particle.valueOf('ELECTRIC_SPARK'); } catch (e) { t = null; } }
            if (t == null) { try { t = Particle.CRIT; } catch (e) { t = null; } }
            if (t == null) { try { t = Particle.valueOf('CRIT'); } catch (e) { t = null; } }
            if (t == null) { try { t = Particle.END_ROD; } catch (e) { t = null; } }
        } catch (e) { t = null; }
        if (t == null) return;

        var eye = p.getEyeLocation();
        var dir = eye.getDirection();
        var Vector = Java.type('org.bukkit.util.Vector');

        // 以视线为法线，构造圆环所在的平面坐标系
        var up = new Vector(0, 1, 0);
        var right = dir.clone().crossProduct(up);
        if (right.lengthSquared() < 1.0E-6) right = new Vector(1, 0, 0);
        right.normalize();
        var up2 = right.clone().crossProduct(dir).normalize();

        var cx = eye.getX() + dir.getX() * GS_SCOPE_DIST;
        var cy = eye.getY() + dir.getY() * GS_SCOPE_DIST;
        var cz = eye.getZ() + dir.getZ() * GS_SCOPE_DIST;

        var world = p.getWorld();
        var n = GS_SCOPE_POINTS;
        for (var i = 0; i < n; i++) {
            var th = (2.0 * Math.PI * i) / n;
            var co = Math.cos(th), si = Math.sin(th);
            try {
                world.spawnParticle(t,
                    cx + GS_SCOPE_RADIUS * (co * right.getX() + si * up2.getX()),
                    cy + GS_SCOPE_RADIUS * (co * right.getY() + si * up2.getY()),
                    cz + GS_SCOPE_RADIUS * (co * right.getZ() + si * up2.getZ()),
                    1, 0.0, 0.0, 0.0, 0.0);
            } catch (e) { }
        }
    } catch (e) { }
}

// 常驻任务：换弹进度 + 切换手持物中断换弹 + 手持药水效果
function gsPsychicPulse() {
    try {
        if (!gsAuraIsCurrent()) return;

        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
 // ★★★ 先把修正 5 之前遗留的「念力感知」按钮清掉
                //   （每人只扫一次；见 gsScopePurge）
                gsScopePurge(p);

                var hand = null;
                try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
                var holding = gsPsychicIsPsychic(hand);

                if (!holding) {
                    if (gsPsychicReloadLeft(p) > 0) gsPsychicClearReload(p);
                    // ★★★ 修正 5：**切换手持物 ⇒ 开镜状态立刻消失**（用户点名）
                    //   · 清 PDC 标记 ⇒ 准星 / 伤害射程加成 / 专注 / 夜视一起消失
                    //   · 副手不用管 —— 修正 5 之后本来就没碰过它
                    gsScopeSetScoped(p, false);
                    gsScopeUnfocus(p);
                    continue;
                }

                if (gsPsychicIsReloading(p) && gsPsychicReloadLeft(p) <= 0) {
                    gsPsychicFinishReload(p);
                }

 // ★ 精神力**不再给任何手持药效**
                //   （原抗性X / 生命恢复V / 速度II / 饱和I 已整段删除；这些增益改由「梦境套」提供）
                //   开镜仍然给「缓慢 X + 夜视」，见 gsScopeFocus / gsScopeUnfocus。

                // 瞄准镜：副手有按钮 → 画准星圆环 + 进入「专注」（缓慢 X）
                gsScopeDraw(p);
                gsScopeFocus(p);
            } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 同步监听器注册 ----------------
function gsPsychicRegisterSync() {
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, {
                        execute: function (l, e) { handler(e); }
                    });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) {
                    return false;
                }
            }
        }

 // ① ★★★ **F 键 = 换弹**（拦下原版换手）
        //   用户「把残樱和余念绝响的换弹改成f键」。
        //   F 的原版行为是「主副手交换」⇒ 拦 PlayerSwapHandItemsEvent。
        //   判定：**主手拿着余念绝响** ⇒ 取消换手 + 走换弹；
        //         主手是别的东西 ⇒ 照常放行，不影响正常游戏。
        //   ★ 这里**不需要 runLater**：取消换手之后主手那件**从头到尾没离开过**，
        //     直接换弹就行（Q 那套要延后 1 刻，是因为 drop() 会先把物品摘下来）。
        var SwapEv = null;
        try { SwapEv = Java.type('org.bukkit.event.player.PlayerSwapHandItemsEvent'); } catch (e) { }
        if (SwapEv != null) {
            reg(SwapEv, function (e) {
                try {
                    if (e.isCancelled()) return;      // 重复注册的实例 → 跳过
                    var p = e.getPlayer();
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsPsychicIsPsychic(hand)) return;   // 没拿枪 → 原版换手
                    e.setCancelled(true);                    // ★ 拦下原版换手
                    gsPsychicStartReload(p);                 // ★ 换弹
                } catch (e3) { }
            });
        }

        // ② 左键开火 / 右键开镜（PlayerInteractEvent）
 // ★★ 按键整体重排（用户「换弹改成q…然后左键开火，右键开镜」）
 // ★★★ 换弹从 Q 改到 F ⇒ 见 ①
        //      左键 = 开火 ｜ 右键 = 开 / 关瞄准镜 ｜ F = 换弹（见 ①）
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = e.getPlayer();
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsPsychicIsPsychic(hand)) return;

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { }
                    if (act.indexOf('RIGHT_CLICK') >= 0) {
 // ★ 右键 = 开 / 关瞄准镜（从 F 键挪过来）
                        e.setCancelled(true);
                        gsScopeToggle(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
 // ★ 左键 = 开火（原来是换弹）
                        e.setCancelled(true);
                        gsPsychicFire(p);
                    }
                } catch (e3) { }
            });
        }

        // ③ EntityDamageByEntityEvent：弹道伤害放行 + **原版近战取消**
 // ★★★ 用户「左键会出现原版伤害，这会导致无法开火」
        //     · 玩家把准星**怼在怪身上**按左键时，客户端发的是**攻击包**
        //       （ServerboundInteractPacket），**不是挥手包** ⇒
        //       PlayerInteractEvent 收不到 ⇒ 枪不开火，反而结算了原版近战那一下。
        //     · 这里把「手持余念绝响时的原版近战」**整个取消**：
        //       怪不掉血、不被击退、**也不留 10 刻无敌帧**
        //       （无敌帧会把紧接着的弹道伤害一起吃掉）。
        //     · 开火本身主要靠 ③-b（挥手包）；这里再兜一次底。
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { }
        if (DmgEv != null) {
            reg(DmgEv, function (e) {
                try {
                    var damager = e.getDamager();
                    if (damager == null) return;
                    var en = '';
                    try { en = '' + damager.getType().name(); } catch (e2) { return; }
                    if (en !== 'PLAYER') return;
                    var p = damager;
                    if (gsPsychicIsShooting(p)) return;   // 余念绝响弹道伤害 → 放行
                    // ★ 只碰原版近战（火焰 / 摔落 / 弹射物之类完全不插手）
                    var cause = '';
                    try { cause = '' + e.getCause().name(); } catch (e2) { return; }
                    if (cause.indexOf('ENTITY_ATTACK') < 0 && cause.indexOf('SWEEP') < 0) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsPsychicIsPsychic(hand)) return;    // 没拿枪 → 原版近战照常
                    e.setCancelled(true);                     // ★ 原版近战整个取消
                    gsPsychicFire(p);                         // ★ 兜底：这一下改成开火
                } catch (e3) { }
            });
        }

 // ③-b ★★★ 左键的**真正兜底** —— 挥手动画
        //   与五骊矟 / 阎魔刀同一套路：客户端**每次左键都会发一次挥手包**，
        //   不管准星对着空气、方块还是怪 ⇒ 保证"左键永远打得出来"。
        //   ★ 与 ② 的 LEFT_CLICK_AIR 同时命中也没关系：gsPsychicFire 有射速限制，
        //     后到的那次静默返回（天然去重，不会双发）。
        //   ★ 只认 ARM_SWING：OFF_ARM_SWING 是**副手**挥（F 换手那一下），不能当开火。
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var type = '';
                    try { type = '' + e.getAnimationType().name(); } catch (e2) { type = ''; }
                    if (type !== 'ARM_SWING') return;
                    var p = e.getPlayer();
                    if (p == null) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsPsychicIsPsychic(hand)) return;    // 没拿枪 → 不理会
                    gsPsychicFire(p);
                } catch (e3) { }
            });
        }

 // ④ ★★★ **Q 恢复原版丢弃** —— 丢弃拦截**整个删除**
        //   用户「把残樱和余念绝响的换弹改成f键」⇒ 换弹已经搬到 F（见 ①），
        //   Q 不再有任何机制用途 ⇒ 拦 PlayerDropItemEvent 的那一段没有意义，删掉。
        //   ★ 现在按 Q 就是**原版丢弃**（枪真的会掉出去）。
        //     掉出去之后主手已经空了 ⇒ 紧接着那个挥手包**不会开火**
        //     （开火路径第一句就是判"主手是不是那把枪"）。

        // ⑤ ★★★ 第六十四批修正 5：**不再注册 InventoryClickEvent / InventoryDragEvent**
        //   那两块唯一的作用是"锁住副手那枚念力感知按钮，不许点击 / 拖动"。
        //   按钮没了 ⇒ 整块删除，少两个全局监听器（顺带不再干扰任何背包操作）。

        GS_PSY_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 余念绝响监听器注册成功（左键=开火 · 右键=开镜（开/关·0.8秒防抖·不需副手） · F=换弹 · Q=原版丢弃）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 余念绝响同步监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsPsychicStart() {
    try {
        runRepeating(function () { gsPsychicPulse(); }, 2, 2);
        gsPsychicRegisterSync();
        gsDaLog('余念绝响：单手持冲锋枪已启用（' + GS_PSY_AMMO + ' 发，射速 '
            + (60000 / GS_PSY_FIRE_MS) + ' RPM，伤害 ' + GS_PSY_DMG_MIN + '~' + GS_PSY_DMG_MAX
            + '，射程 ' + GS_PSY_RANGE + '，左键开火，右键开镜（开/关，'
            + (GS_SCOPE_DEBOUNCE_MS / 1000.0).toFixed(1) + ' 秒防抖，'
            + '★ 不需要副手），F 换弹，Q 原版丢弃）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 余念绝响启动失败: ' + e); } catch (e2) { }
    }
}

gsPsychicStart();

// ==========================================================================
// 残樱（音乐唱片残片 MUSIC_DISC_RELIC · 单手持手枪）★ 第六十二批新增
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §17
//   ★ 以下 2 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//     十六步内，花落无声
//     弹药带有花香，洗净时间腐朽
// ==========================================================================

var GS_CH_ID = "GS_CHERRY";
var GS_CH_AMMO = 12;                  // ★★ 第六十四批：弹容量 **14 → 12**（用户「弹夹改成12发」）
var GS_CH_FIRE_MS = 200;              // ★★ 第六十四批：**单发**射速 **700 → 200**
                                      //   （用户「射速改成0.2」／追问后「单发改成0.2」）
var GS_CH_RELOAD_MS = 1800;           // ★ 换弹 1.8 秒（用户「换弹速度1.8秒」）
var GS_CH_RANGE = 16.0;               // ★ 射程 16 格（用户「朝前方16格范围内」）
var GS_CH_DMG = 8.0;                  // ★ 伤害 8 点（用户「伤害8点」）
var GS_CH_MUZZLE_Y = 1.2;             // 发射高度（脚底往上 1.2 格 = 胸口）
var GS_CH_STEP = 0.5;                 // 弹道采样步长（格）
var GS_CH_HIT_R = 0.8;                // 命中判定半径（格）
// ==========================================================================
// ★★ 右键切换「单发 / 三连发」两种开火方式
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §18
//   ★ 以下 1 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//     ⇒ 三发**同一刻**出膛：不再有 0.1 秒的发间隔（GS_CH_BURST_GAP 已删除），
// ==========================================================================
var GS_CH_BURST_N = 3;                // 三连发的发数（散射 = 一次出膛 3 发）
var GS_CH_BURST_MS = 300;             // ★★ 第六十六批：一轮的间隔 **500 → 300**
                                      //   （用户「射速改成0.3」⇒ 三发每 0.3 秒一轮）
// ★★ **三发模式的射程**（用户「三发模式的射程改成6」）——
//   单发仍然用 GS_CH_RANGE（16 格），**只有三连发缩到 6 格** ⇒ 主打近距爆发。
var GS_CH_BURST_RANGE = 6.0;          // 三发模式的射程（格）
// ★★★ **不再随距离散布**（用户「主打爆发，后续不散布了」）——
//   三发的横向偏移由「越远越大」改成**全程恒定的一个小常数**：
//     off = GS_CH_SPREAD_BASE = **0.20 格**（第 1 格到射程尽头都一样）
//   ⇒ 第六十四批修正 4 的 GS_CH_SPREAD_D0（6 格起点）与 GS_CH_SPREAD_K
//     （0.13 格/格）**两个常量整个删除**，gsCherrySpreadAt() 也一并删除。
//   ★ 用户选的是「保留 6 格内 0.20 格基础偏移」⇒ 三发**不是完全重合**，
//     仍保留 0.20 格的固定横向错开（看得出是三发，但不会越打越散）。
var GS_CH_SPREAD_BASE = 0.20;         // 三发的固定横向偏移（格，全程恒定）
// 三发的横向序号：0 = 正中（永远指哪打哪）· -1 = 左 · +1 = 右
//   ★ 顺序固定为「正中 → 左 → 右」⇒ 弹药只剩 1~2 发时，先保证**正中**那一发打出去。
var GS_CH_SPREAD_SIDES = [0, -1, 1];
var GS_CH_SWITCH_MS = 1000;           // ★ 切换开火方式的冷却（用户「切换冷却1秒」）
// ★ 音效（用户「音效为铜箱子关闭，换弹为铜箱子打开」）
var GS_CH_SND_FIRE = 'block.copper_chest.close';
var GS_CH_SND_RELOAD = 'block.copper_chest.open';
// ★ 切换开火方式的音效：拉杆"咔哒"一声（不占用上面两个用户点名的音效）
var GS_CH_SND_SWITCH = 'block.lever.click';

// ★★★ `burst` / `burstAt`（本轮三连发进度）**两个键整个删掉** ——
//     三发同一刻全部出膛，没有"还剩几发没打"这种状态，一轮的间隔改用 lastShot 算。
var GS_CH_KEYS = null;                // { ammo, lastShot, reload, shooting, mode, switchAt }
var GS_CH_REGISTERED = false;

function gsCherryKeys() {
    if (GS_CH_KEYS != null) return GS_CH_KEYS;
    var o = { ammo: null, lastShot: null, reload: null, shooting: null,
              mode: null, switchAt: null };
    try {
        var NK = Java.type('org.bukkit.NamespacedKey');
        o.ammo = NK.fromString('gs:ch_ammo');
        o.lastShot = NK.fromString('gs:ch_lastshot');
        o.reload = NK.fromString('gs:ch_reload');
        o.shooting = NK.fromString('gs:ch_shooting');
 // ★★ 开火方式 / 切换时间
        //   （★ 修正 4：gs:ch_burst 与 gs:ch_burstat 已随"同时出膛"一起删除）
        o.mode = NK.fromString('gs:ch_mode');        // 0 = 单发，1 = 三连发（散射）
        o.switchAt = NK.fromString('gs:ch_switch');  // 上次切换开火方式的时间
    } catch (e) {
        return o;
    }
    GS_CH_KEYS = o;
    return GS_CH_KEYS;
}

function gsCherryPdc(p) {
    try { return p.getPersistentDataContainer(); } catch (e) { return null; }
}

function gsCherryIsCherry(item) {
    return gsIsItem(item, GS_CH_ID);
}

function gsCherryNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); } catch (e) { return 0; }
}

// ★★★ 换弹从 Q 改到 F ⇒ 原来那套「Q 的挥手不算开火」的闸门
//   （GS_CH_Q_AT / GS_CH_Q_GAP / gsCherryMarkQ / gsCherryJustQ）**整个删除**
//   （与余念绝响同因，理由见上面 gsPsychicNow 那一段的注释）。

// 读弹药（没存过 = 满弹）
function gsCherryAmmo(p) {
    try {
        var k = gsCherryKeys();
        if (k.ammo == null || GS_PDT == null) return GS_CH_AMMO;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return GS_CH_AMMO;
        var v = pdc.get(k.ammo, GS_PDT.INTEGER);
        return (v == null) ? GS_CH_AMMO : v;
    } catch (e) { return GS_CH_AMMO; }
}

function gsCherrySetAmmo(p, v) {
    try {
        var k = gsCherryKeys();
        if (k.ammo == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.set(k.ammo, GS_PDT.INTEGER, v);
    } catch (e) { }
}

function gsCherryIsReloading(p) {
    try {
        var k = gsCherryKeys();
        if (k.reload == null || GS_PDT == null) return false;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.reload, GS_PDT.LONG);
    } catch (e) { return false; }
}

function gsCherryReloadLeft(p) {
    try {
        if (!gsCherryIsReloading(p)) return 0;
        var k = gsCherryKeys();
        var pdc = gsCherryPdc(p);
        var start = pdc.get(k.reload, GS_PDT.LONG);
        if (start == null) return 0;
        var left = GS_CH_RELOAD_MS - (gsCherryNow() - (0 + start));
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function gsCherryLastShotAgo(p) {
    try {
        var k = gsCherryKeys();
        if (k.lastShot == null || GS_PDT == null) return 999999;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return 999999;
        var last = pdc.get(k.lastShot, GS_PDT.LONG);
        if (last == null) return 999999;
        return gsCherryNow() - (0 + last);
    } catch (e) { return 999999; }
}

function gsCherryMarkShot(p) {
    try {
        var k = gsCherryKeys();
        if (k.lastShot == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.set(k.lastShot, GS_PDT.LONG, gsCherryNow());
    } catch (e) { }
}

function gsCherryMarkReload(p) {
    try {
        var k = gsCherryKeys();
        if (k.reload == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.set(k.reload, GS_PDT.LONG, gsCherryNow());
    } catch (e) { }
}

function gsCherryClearReload(p) {
    try {
        var k = gsCherryKeys();
        if (k.reload == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.remove(k.reload);
    } catch (e) { }
}

// shooting 标记：弹道造成带来源伤害时，让 EntityDamageByEntityEvent 监听器放行
function gsCherrySetShootingFlag(p, on) {
    try {
        var k = gsCherryKeys();
        if (k.shooting == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return;
        // ★★★ STRING 而不是 BYTE（同 gsScopeSetScoped 的注释：
        //   BYTE + JS 数字会装箱成 Integer ⇒ checkcast Byte 失败 ⇒ **静默失败**）
        if (on) pdc.set(k.shooting, GS_PDT.STRING, '1');
        else pdc.remove(k.shooting);
    } catch (e) { }
}

function gsCherryIsShooting(p) {
    try {
        var k = gsCherryKeys();
        if (k.shooting == null || GS_PDT == null) return false;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.shooting, GS_PDT.STRING);
    } catch (e) { return false; }
}

// ---------------- ★★ 开火方式（单发 / 三连发）----------------
//   mode 存 PDC（gs:ch_mode）⇒ 跨 /rsc reload 不丢，和弹药一个待遇。
//   0 = 单发（每 0.2 秒一发）｜ 1 = 三连发（一次扳机 3 发，一轮 0.5 秒）
function gsCherryMode(p) {
    try {
        var k = gsCherryKeys();
        if (k.mode == null || GS_PDT == null) return 0;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return 0;
        var v = pdc.get(k.mode, GS_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return 0; }
}

function gsCherrySetMode(p, m) {
    try {
        var k = gsCherryKeys();
        if (k.mode == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.set(k.mode, GS_PDT.INTEGER, m | 0);
    } catch (e) { }
}

function gsCherryIsBurstMode(p) { return gsCherryMode(p) === 1; }

// ★★★ 这里原来有四个函数 ——
//     gsCherryBurstLeft / gsCherrySetBurstLeft / gsCherryBurstAgo / gsCherryMarkBurst
//   （管"本轮三连发还剩几发、上一发什么时候打的"）。
//   三发改成**同一刻全部出膛**之后，这三件事都不存在了：
//     · 没有"还剩几发"—— 一次扳机就把 3 发全打出去；
//     · 没有"发间隔"—— 不需要 0.1 秒的时间戳；
//     · "一轮 0.5 秒"直接用 lastShot（gsCherryLastShotAgo）算就够。
//   ⇒ **四个函数连声明一起删除**（不是留着不用），PDC 键 gs:ch_burst / gs:ch_burstat 同去。

function gsCherrySwitchAgo(p) {
    try {
        var k = gsCherryKeys();
        if (k.switchAt == null || GS_PDT == null) return 999999;
        var pdc = gsCherryPdc(p);
        if (pdc == null) return 999999;
        var last = pdc.get(k.switchAt, GS_PDT.LONG);
        if (last == null) return 999999;
        return gsCherryNow() - (0 + last);
    } catch (e) { return 999999; }
}

function gsCherryMarkSwitch(p) {
    try {
        var k = gsCherryKeys();
        if (k.switchAt == null || GS_PDT == null) return;
        var pdc = gsCherryPdc(p);
        if (pdc != null) pdc.set(k.switchAt, GS_PDT.LONG, gsCherryNow());
    } catch (e) { }
}

// 右键 = 切换开火方式（1 秒冷却）
//   ★ 修正 4：不用再"清掉半截三连发"了 —— 三发是同时出膛的，切换那一刻没有待发射的余弹。
function gsCherryToggleMode(p) {
    try {
        if (p == null) return;
        if (gsCherrySwitchAgo(p) < GS_CH_SWITCH_MS) return;      // ★ 切换冷却 1 秒
        gsCherryMarkSwitch(p);
        var m = gsCherryIsBurstMode(p) ? 0 : 1;
        gsCherrySetMode(p, m);
        gsCherryUpdateName(p);
        try {
            var world = p.getWorld();
            world.playSound(p.getLocation(), GS_CH_SND_SWITCH, 1.0, (m === 1) ? 1.6 : 0.9);
        } catch (e) { }
    } catch (e) { }
}

// 颜色码转换：& → §
function gsCherryColor(s) {
    try {
        var CC = Java.type('org.bukkit.ChatColor');
        return CC.translateAlternateColorCodes('&', s);
    } catch (e) {
        return ('' + s).replace(/&([0-9a-fk-or])/g, '\u00a7$1');
    }
}

// 更新物品名字：正常「残樱<弹药>」，换弹中「残樱<R>」，
// ★★ 三连发模式再挂一个「≡」标记（一眼看出现在是哪种开火方式）
function gsCherryUpdateName(p) {
    try {
        var inv = p.getInventory();
        var hand = null;
        try { hand = inv.getItemInMainHand(); } catch (e) { hand = null; }
        if (hand == null || !gsCherryIsCherry(hand)) return;

        var meta = null;
        try { meta = hand.getItemMeta(); } catch (e) { meta = null; }
        if (meta == null) return;

        var name;
        var a = gsCherryAmmo(p);
        var tail = gsCherryIsBurstMode(p) ? '\u2261' : '';        // ≡ = 三连发
        if (gsCherryReloadLeft(p) > 0) {
            // 换弹中：保留弹药数字，右上角加一个上标小 R（ᴿ）
            name = gsCherryColor('&d残樱<' + a + tail + '\u1d3f>');
        } else {
            name = gsCherryColor('&d残樱<' + a + tail + '>');
        }
        try { meta.setDisplayName(name); } catch (e) { }
        hand.setItemMeta(meta);
        try { inv.setItemInMainHand(hand); } catch (e) { }
    } catch (e) { }
}

// ---------------- 开火（左键）----------------
// ★★ 左键开火（原来是右键）；按开火方式分两条路 ——
//     · 单发：每 GS_CH_FIRE_MS（0.2 秒）一发
//     · 三连发：★★★ 修正 4 起是「**单发散射**」—— 一次扳机 **3 发同时出膛**，
//       每发带一个**固定 0.20 格**的横向偏移（见 GS_CH_SPREAD_BASE）；
//       一轮打完后要等 GS_CH_BURST_MS（0.3 秒）才能再打下一轮。
//       ★ 三发在同一刻打完 ⇒ **心跳不再推进任何东西**（原来那套"后两发交给心跳"
//         的机制连同 burst/burstAt 两个 PDC 键一起删掉了），也就不存在
//         "rsc reload 之后留下停不掉的旧链"的问题。
function gsCherryFire(p) {
    try {
        if (p == null) return;

        if (gsCherryIsBurstMode(p)) {
 // ★★★ 三发**同时**出膛 ⇒
            //   没有"本轮还没打完"这回事（不用查 burstLeft），
            //   也没有 0.1 秒的发间隔（不用查 burstAgo）——
            //   只用 **0.3 秒的一轮间隔**节流，直接拿 lastShot 算。
            if (gsCherryLastShotAgo(p) < GS_CH_BURST_MS) return;
            // ★ 空弹 ⇒ 触发换弹，不开火
            if (gsCherryAmmo(p) <= 0) {
                if (gsCherryReloadLeft(p) <= 0) gsCherryStartReload(p);
                return;
            }
            // ★ 换弹中开火 ⇒ 打断换弹
            if (gsCherryReloadLeft(p) > 0) gsCherryClearReload(p);
            // ★ 一次扳机 = 三发同时出膛（越远越散）
            gsCherryShootScatter(p);
            return;
        }

        // ---- 单发 ----
        // ★ 射速限制：0.2 秒/发（手枪的点射节奏）
        if (gsCherryLastShotAgo(p) < GS_CH_FIRE_MS) return;

        var a = gsCherryAmmo(p);

        // ★ 弹药为 0 → 强制不能开火（不打断换弹、不重复触发换弹）
        if (a <= 0) {
            if (gsCherryReloadLeft(p) <= 0) gsCherryStartReload(p);
            return;
        }

        // ★ 有弹药时，换弹中开火 → 打断换弹，继续正常开火
        if (gsCherryReloadLeft(p) > 0) gsCherryClearReload(p);

        gsCherryShootOnce(p);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 残樱开火出错: ' + e); } catch (e2) { }
    }
}

// 打一发（**单发**模式专用）：扣 1 发弹药 → 出 1 道射线（正中）→ 刷新名字
//   ★ 返回 true 表示真的打出去了（没弹药就返回 false，不空放）
function gsCherryShootOnce(p) {
    try {
        var a = gsCherryAmmo(p);
        if (a <= 0) return false;
        gsCherrySetAmmo(p, a - 1);
        gsCherryMarkShot(p);
        gsCherryShootRay(p, 0, GS_CH_RANGE);
        gsCherryUpdateName(p);
        return true;
    } catch (e) { return false; }
}

// ★★★ **散射**（三连发模式专用）——
//   一次扳机 **3 发同时出膛**，每发带一个**随距离变大**的横向偏移。
//   · 弹药不足 3 发时：**有几发打几发**（顺序 正中 → 左 → 右，先保证正中那发），
//     打完由调用方在下一拍去换弹。
//   · **lastShot 只打点一次**（三发是同一刻，不需要三个时间戳）。
//   ★ 这里**没有**"本轮还剩几发"的状态 —— 三发在同一刻全部出膛，
//     不存在待发射的余弹，所以心跳也不用管它。
function gsCherryShootScatter(p) {
    try {
        var a = gsCherryAmmo(p);
        if (a <= 0) return false;
        var n = (a < GS_CH_BURST_N) ? a : GS_CH_BURST_N;
        gsCherrySetAmmo(p, a - n);
        gsCherryMarkShot(p);
        for (var k = 0; k < n; k++) {
            gsCherryShootRay(p, GS_CH_SPREAD_SIDES[k], GS_CH_BURST_RANGE);
        }
        gsCherryUpdateName(p);
        return true;
    } catch (e) { return false; }
}

// 樱花射线：沿胸口朝准星方向推进，命中生物立即结算 8 点伤害。
// ★ 射程由**调用方**给（★★ 单发 16 格 / 三发 6 格）·
//     粒子 = CHERRY_LEAVES（樱花花瓣）· 撞墙 / 撞生物就停
// ★★★ 多了个 side 参数 —— 横向偏移方向
//       0 = 正中（就打在准星上，**指哪打哪**）· -1 = 左 · +1 = 右
// ★★ 偏移量改成**全程恒定**的 GS_CH_SPREAD_BASE（0.20 格），
//          不再随采样距离变大（"后续不散布了"）。
// ★★★ 第三个参数 range = 本次射线的最大射程（格）。
//   ★ 发射音效**只在 side === 0 那一发放** ⇒ 三发同时出膛**只响一声**（不叠三次）。
function gsCherryShootRay(p, side, range) {
    try {
        var sd = side | 0;
        var rg = range;                       // ★★ 第六十六批：本次射线的射程
        var world = p.getWorld();
        var eye = null;
        try { eye = p.getEyeLocation(); } catch (e) { return; }
        var dir = eye.getDirection();                 // 方向 = 视线（准星）

        // ★ 发射起点 = 「胸口」：从眼睛发射会挡住视线
        var base = null;
        try { base = p.getLocation(); } catch (e) { return; }
        var sx = base.getX();
        var sy = base.getY() + GS_CH_MUZZLE_Y;
        var sz = base.getZ();

        var selfId = '' + p.getUniqueId();

        // ★ 横向偏移用的基向量：**水平右向** r = normalize(-dz, 0, dx)
        //   （垂直于准星中线、且始终水平 ⇒ 散布是一个水平的扇形，好瞄）
        var dx0 = dir.getX(), dz0 = dir.getZ();
        var hl = Math.sqrt(dx0 * dx0 + dz0 * dz0);
        var rxv, rzv;
        if (hl < 1.0e-4) { rxv = 1.0; rzv = 0.0; }    // 垂直往上看/看下时兜底
        else { rxv = -dz0 / hl; rzv = dx0 / hl; }

        // 音效：**铜箱子关闭**（发射时，从胸口位置响）· ★ 只由正中那一发响
        if (sd === 0) {
            try {
                var LocS = Java.type('org.bukkit.Location');
                world.playSound(new LocS(world, sx, sy, sz), GS_CH_SND_FIRE, 1.0, 1.4);
            } catch (e) { }
        }

        // 樱花花瓣粒子（1.21.11 里确认存在；拿不到就走 valueOf 兜底）
        var so = null;
        try {
            var Particle = Java.type('org.bukkit.Particle');
            try { so = Particle.CHERRY_LEAVES; } catch (e) { so = null; }
            if (so == null) { try { so = Particle.valueOf('CHERRY_LEAVES'); } catch (e) { so = null; } }
        } catch (e) { }

        // 沿视线逐格推进做射线检测（步长 0.5 格，保证命中精确）
        var Loc = Java.type('org.bukkit.Location');
        var d = 0.0;
        var step = 0;
        while (d <= rg) {
 // ★★★ 横向偏移**全程恒定 0.20 格**（不再随距离变大）
            var off = GS_CH_SPREAD_BASE * sd;
            var pt = new Loc(world,
                sx + dir.getX() * d + rxv * off,
                sy + dir.getY() * d,
                sz + dir.getZ() * d + rzv * off);

            // 樱花射线（每隔 1 格放一个）
            step++;
            if (so != null && step % 2 === 1) {
                try { world.spawnParticle(so, pt, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
            }

            // 撞墙停
            try {
                var b = pt.getBlock();
                if (b != null && b.getType() != null && b.getType().isSolid()) return;
            } catch (e) { }

            // 命中生物 → 立即伤害
            try {
                var list = world.getNearbyEntities(pt, GS_CH_HIT_R, GS_CH_HIT_R, GS_CH_HIT_R);
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e1) { continue; }
                    if (eid === selfId) continue;
                    try { ent.getHealth(); } catch (e2) { continue; }
                    try { if (ent.isDead()) continue; } catch (e3) { }
                    try {
                        var tn = '' + ent.getType().name();
                        if (tn === 'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM') continue;
                    } catch (e4) { }

                    gsCherryHitDamage(p, ent, GS_CH_DMG);
                    return;                   // 命中一个就停
                }
            } catch (e) { }

            d += GS_CH_STEP;
        }
    } catch (e) { }
}

// 结算伤害（带来源，靠 shooting 标记让 EntityDamageByEntityEvent 监听器放行）
// ★ 清受击无敌帧（setNoDamageTicks(0)），让伤害立即生效
function gsCherryHitDamage(shooter, victim, dmg) {
    try {
        if (victim == null) return;
        try { if (victim.isDead()) return; } catch (e) { return; }
        try { victim.getHealth(); } catch (e) { return; }

        try { victim.setNoDamageTicks(0); } catch (e) { }

        gsCherrySetShootingFlag(shooter, true);
        try { victim.damage(dmg, shooter); }
        catch (e1) { try { victim.damage(dmg); } catch (e2) { } }
        gsCherrySetShootingFlag(shooter, false);
    } catch (e) { }
}

// ---------------- 换弹（F 键 · 1.8 秒）----------------
function gsCherryStartReload(p) {
    try {
        // ★ 正在换弹 → 直接忽略，绝不重置换弹进度
        if (gsCherryReloadLeft(p) > 0) return;

        // ★ 弹夹已经满了 → 没必要换（静默返回，不提示）
        var ammo = gsCherryAmmo(p);
        if (ammo >= GS_CH_AMMO) return;

 // ★★★ 原来这里要"作废没打完的三连发"
        //   （修正 2 加的）—— 现在三发是**同一刻**全部出膛的，
        //   按 F 的那一刻不存在待发射的余弹 ⇒ 这一段整个删掉。

        gsCherryMarkReload(p);
        gsCherryUpdateName(p);

        // 换弹音效：**铜箱子打开**
        try {
            var loc = p.getLocation();
            var world = p.getWorld();
            world.playSound(loc, GS_CH_SND_RELOAD, 1.0, 1.0);
        } catch (e) { }
    } catch (e) { }
}

function gsCherryFinishReload(p) {
    try {
        gsCherrySetAmmo(p, GS_CH_AMMO);
        gsCherryClearReload(p);
        gsCherryUpdateName(p);
    } catch (e) { }
}

// ---------------- 每刻心跳（每 2 刻 = 0.1 秒）----------------
function gsCherryPulse() {
    try {
        if (!gsAuraIsCurrent()) return;

        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
                var hand = null;
                try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
                var holding = gsCherryIsCherry(hand);

                if (!holding) {
                    // ★ 切走残樱（换格子 / 换手持物）⇒ 中断换弹、重新计时
                    //   （★ 修正 4：不用再作废"没打完的三连发"了 —— 三发同时出膛，
                    //     切走的那一刻本来就没有待发射的余弹）
                    if (gsCherryReloadLeft(p) > 0) gsCherryClearReload(p);
                    continue;
                }

                // 换弹时间到 ⇒ 填满弹夹
                if (gsCherryIsReloading(p) && gsCherryReloadLeft(p) <= 0) {
                    gsCherryFinishReload(p);
                }

 // ★★★ 这里原来有"三连发的后续几发由心跳推进"
                //   （每 0.1 秒一拍打一发）。现在三发是**同一刻**全部出膛的，
                //   心跳**什么都不用推** ⇒ 整段删除。
                //
                // ★ 但"打到没弹了自动换弹"这件事还得有人管：散射一次扣 3 发，
                //   弹药可能正好被打空 ⇒ 这里补一拍检查。
                if (gsCherryIsBurstMode(p) && gsCherryAmmo(p) <= 0
                        && gsCherryReloadLeft(p) <= 0) {
                    gsCherryStartReload(p);
                }
                // ★ 残樱**没有 F 键 / 没有开镜** ⇒ 这里不画准星、不给任何药效。
            } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 同步监听器注册 ----------------
function gsCherryRegisterSync() {
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, {
                        execute: function (l, e) { handler(e); }
                    });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) {
                    return false;
                }
            }
        }

        // ① 左键开火 / 右键切换开火方式（PlayerInteractEvent）
 // ★★ 按键重排（用户「左键开火…右键切换发射方式」）
 // ★★★ 换弹从 Q 改到 F ⇒ 见 ③
        //      左键 = 开火 ｜ 右键 = 单发 ↔ 三连发 ｜ F = 换弹（见 ③）
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = e.getPlayer();
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsCherryIsCherry(hand)) return;

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { }
                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★ 右键 = 切换开火方式（单发 ↔ 三连发，1 秒冷却）
                        e.setCancelled(true);
                        gsCherryToggleMode(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        // ★ 左键 = 开火（樱花射线）
                        e.setCancelled(true);
                        gsCherryFire(p);
                    }
                } catch (e3) { }
            });
        }

        // ② EntityDamageByEntityEvent：弹道伤害放行 + **原版近战取消**
 // ★★★ 用户「左键会出现原版伤害，这会导致无法开火」
        //     （与余念绝响同因：准星怼在怪身上时客户端发的是**攻击包**，
        //       PlayerInteractEvent 收不到 ⇒ 不开火、反而结算原版近战）
        //     · 手持残樱时的原版近战**整个取消**：怪不掉血、不被击退、
        //       也不留 10 刻无敌帧（会把紧接着的樱花射线伤害一起吃掉）。
        //     · 开火本身主要靠 ②-b（挥手包）；这里再兜一次底。
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { }
        if (DmgEv != null) {
            reg(DmgEv, function (e) {
                try {
                    var damager = e.getDamager();
                    if (damager == null) return;
                    var en = '';
                    try { en = '' + damager.getType().name(); } catch (e2) { return; }
                    if (en !== 'PLAYER') return;
                    var p = damager;
                    if (gsCherryIsShooting(p)) return;   // 残樱弹道伤害 → 放行
                    // ★ 只碰原版近战（火焰 / 摔落 / 弹射物之类完全不插手）
                    var cause = '';
                    try { cause = '' + e.getCause().name(); } catch (e2) { return; }
                    if (cause.indexOf('ENTITY_ATTACK') < 0 && cause.indexOf('SWEEP') < 0) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsCherryIsCherry(hand)) return;      // 没拿枪 → 原版近战照常
                    e.setCancelled(true);                     // ★ 原版近战整个取消
                    gsCherryFire(p);                          // ★ 兜底：这一下改成开火
                } catch (e3) { }
            });
        }

 // ②-b ★★★ 左键的**真正兜底** —— 挥手动画
        //   与五骊矟 / 阎魔刀 / 余念绝响同一套路：客户端**每次左键都会发一次
        //   挥手包**，不管准星对着空气、方块还是怪 ⇒ 保证"左键永远打得出来"。
        //   ★ 与 ① 的 LEFT_CLICK_AIR 同时命中也没关系：gsCherryFire 有射速限制，
        //     后到的那次静默返回（天然去重，不会双发）。
        //   ★ 只认 ARM_SWING：OFF_ARM_SWING 是**副手**挥（F 换手那一下），不能当开火。
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var type = '';
                    try { type = '' + e.getAnimationType().name(); } catch (e2) { type = ''; }
                    if (type !== 'ARM_SWING') return;
                    var p = e.getPlayer();
                    if (p == null) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsCherryIsCherry(hand)) return;      // 没拿枪 → 不理会
                    gsCherryFire(p);
                } catch (e3) { }
            });
        }

        // ③ ★★★ 第六十五批：**F 键 = 换弹**（拦下原版换手）
        //   用户「把残樱和余念绝响的换弹改成f键」。
        //   F 的原版行为是「主副手交换」⇒ 拦 PlayerSwapHandItemsEvent。
        //   判定：**主手拿着残樱** ⇒ 取消换手 + 走换弹；主手是别的 ⇒ 照常放行。
 // ★ 时残樱是"没有 F 键功能"（**不注册**换手事件），
        //     本批 F 改成换弹 ⇒ **必须注册**（拦下来做的是换弹，不是开镜）。
        //   ★ 这里**不需要 runLater**：取消换手之后主手那件**从头到尾没离开过**。
        var SwapEv = null;
        try { SwapEv = Java.type('org.bukkit.event.player.PlayerSwapHandItemsEvent'); } catch (e) { }
        if (SwapEv != null) {
            reg(SwapEv, function (e) {
                try {
                    if (e.isCancelled()) return;      // 重复注册的实例 → 跳过
                    var p = e.getPlayer();
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsCherryIsCherry(hand)) return;     // 没拿枪 → 原版换手
                    e.setCancelled(true);                    // ★ 拦下原版换手
                    gsCherryStartReload(p);                  // ★ 换弹
                } catch (e3) { }
            });
        }

 // ④ ★★★ **Q 恢复原版丢弃** —— 丢弃拦截**整个删除**
        //   换弹已经搬到 F（见 ③），Q 不再有任何机制用途。
        //   ★ 现在按 Q 就是**原版丢弃**（枪真的会掉出去）；
        //     掉出去之后主手已经空了 ⇒ 紧接着那个挥手包**不会开火**。

        GS_CH_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 残樱监听器注册成功（左键=樱花射线 · 右键=切换单发/三连发散射 · F=换弹 · Q=原版丢弃）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 残樱同步监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsCherryStart() {
    try {
        runRepeating(function () { gsCherryPulse(); }, 2, 2);
        gsCherryRegisterSync();
        gsDaLog('残樱：单手持手枪已启用（' + GS_CH_AMMO + ' 发，单发 '
            + (GS_CH_FIRE_MS / 1000.0).toFixed(1) + ' 秒/发 · 射程 ' + GS_CH_RANGE
            + '，三连发 ' + GS_CH_BURST_N + ' 发**同时出膛**（横向偏移固定 '
            + GS_CH_SPREAD_BASE.toFixed(2) + ' 格，不随距离散布）· 射程 '
            + GS_CH_BURST_RANGE + ' · '
            + (GS_CH_BURST_MS / 1000.0).toFixed(1)
            + ' 秒一轮，伤害 ' + GS_CH_DMG
            + '，换弹 ' + (GS_CH_RELOAD_MS / 1000.0).toFixed(1)
            + ' 秒，左键开火，右键切换发射方式，F 换弹）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 残樱启动失败: ' + e); } catch (e2) { }
    }
}

gsCherryStart();

// 释梦 / 梦境套：永不掉耐久
if (!gsNoDurabilityRegisterSync()) {
    // 注册失败时靠 gsEquipPulse 的 gsNoDurabilitySweepAll 兜底（每 10 刻补耐久）
    try { getServer().getLogger().warning('[构思技艺] 耐久保护事件注册失败，已启用定时兜底'); } catch (e) { }
}

// ==========================================================================
// 五骊矟（金剑 GOLDEN_SWORD · GS_WULI_SPEAR）——「五骊骑阵」
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §19
//   ★ 以下 2 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//     `minecraft:item_model = minecraft:golden_spear` 渲染成金矛
//     （走 items.yml 的 material_type: saveditem → saveditems/wls.yml）。
// ==========================================================================

var GS_WULI_ID = 'GS_WULI_SPEAR';        // 五骊矟（金剑）

var GS_WULI_REIN_KEY   = null;           // 副手拴绳「五骊鞚」的物品标记
var GS_WULI_SUMMON_KEY = null;           // 召唤物标记（护腿光环靠它跳过）
var GS_WULI_QI_KEY     = null;           // 剑气冷却（玩家 PDC）
var GS_WULI_DASH_KEY   = null;           // 冲锋冷却（玩家 PDC）
try {
    var GW_NK = Java.type('org.bukkit.NamespacedKey');
    GS_WULI_REIN_KEY   = GW_NK.fromString('gs:wuli_rein');
    GS_WULI_SUMMON_KEY = GW_NK.fromString('gs:summon');
    GS_WULI_QI_KEY     = GW_NK.fromString('gs:wuli_qi');
    GS_WULI_DASH_KEY   = GW_NK.fromString('gs:wuli_dash');
} catch (e) { }

// ---------------- 参数 ----------------
var WL_REIN_NAME = '\u00a7e五骊鞚';                    // 副手拴绳的名字
// 副手拴绳的 lore —— ★ 由 1 行改成 **4 行**
//   （名字仍是 \u00a7e五骊鞚，见上一行的 WL_REIN_NAME）
var WL_REIN_LORE = [
    '\u00a78五骊之缰 · 战魂所系',
    '\u00a77唯主手执 \u00a7f五骊矟 \u00a77时',
    '\u00a77才可驭使五骊马阵',
    '\u00a78“鞚在手，骊在侧”'
];
var WL_PULSE     = 2;                                  // 骑阵任务间隔（刻）；2 刻 = 0.1 秒

// 马的移速：**只升不降**的速度下限（属性值，不是 m/s）
//   · 换算：**1 点属性 ≈ 43 格/秒**（玩家 0.1 → 冲刺 ×1.3 = 0.13 → 5.6 格/秒，对得上）
//   · 玩家喝「速度 IV」= 每级 +20% ⇒ 0.1 × 1.8 = 0.18；再冲刺 ×1.3 = 0.234 → **约 10 格/秒**
//   · 骷髅马默认只有 **0.2**（≈ 8.6 格/秒）⇒ 所以骑着比"速度 IV 冲刺的玩家"还慢，
//     这就是之前"马太慢"的原因。
//   · 这里取 **0.28**（≈ 12 格/秒：比"速度 IV + 冲刺"再快两成，且仍在原版
//     随机马上限 0.3375 之内，不会飞出去）。**比它快的马保持原速**（只升不降）。
//   · 想再快/再慢就改这一个数：0.234 = 正好对齐速度IV冲刺，0.3375 = 原版最快马。
var WL_HORSE_SPEED = 0.28;

// 蓄力时"前蹄抬起"的姿势
//   · 主手段：AbstractHorse#setRearing(true) —— 客户端直接渲染成"人立"（后腿支点）
//   · 辅助段：把 pitch 仰起来。★ 注意：玩家骑着的马，服务端每刻会把 pitch 刷成
//     "骑手 pitch × 0.5"，所以单靠 pitch 会被覆盖 —— 两者一起上才稳。
var WL_REAR_PITCH  = -42.0;

// 骑阵 ^ 队形：back = 落在队长马身后几格，side = 偏离中线几格（负 = 左，正 = 右）
//   队长马是 "^" 的顶点，护航马两两向后展开 → 左右各 2 匹
// ★ 微调：原来整体偏后 → 中间两只往前 0.5 格、最外侧两只往前 1 格
var WL_FORM = [
    { back: 1.5, side: -1.6 },   // 中间左
    { back: 1.5, side:  1.6 },   // 中间右
    { back: 3.0, side: -3.2 },   // 最外侧左
    { back: 3.0, side:  3.2 }    // 最外侧右
];

// 剑气（左键）
var WL_QI_CD_MS  = 500;       // 冷却 0.5 秒（无提示词！）
var WL_QI_SND1   = 'entity.ender_dragon.flap';    // 斩击音效 ①：末影龙飞行
var WL_QI_SND2   = 'entity.enderman.teleport';    // 斩击音效 ②：末影人传送
var WL_QI_SND1_VOL = 0.8, WL_QI_SND1_PIT = 1.4;
var WL_QI_SND2_VOL = 1.0, WL_QI_SND2_PIT = 1.0;
var WL_QI_DMG    = 7.0;       // 7 点伤害
var WL_QI_RANGE  = 10.0;      // 攻击距离 10 格
var WL_QI_HALF_W = 2.5;       // 剑气左右各 2.5 格（= 5 格长）
var WL_QI_HALF_T = 0.75;      // 剑气厚 1.5 格（= 1 格宽，留点判定余量）
var WL_QI_HALF_H = 1.5;       // 垂直容差
var WL_QI_STEP   = 0.5;       // 每刻推进 0.5 格 → 10 格约 1 秒飞完
var WL_QI_KB     = 0.35;      // 剑气附带的击退（水平，沿剑气方向；原版平A约 0.4）
var WL_QI_KB_UP  = 0.12;      // 击退时稍微挑一点（免得贴地卡住）
// ★ 手持五骊矟时**不与方块交互**（左键点到方块也当成"空挥"）
//   true  = 挖不动方块、也不出挖掘进度条；左键只斩剑气（改回 false 即恢复原版挖掘）
var WL_QI_NO_BLOCK = true;

// 冲锋（右键）
var WL_DASH_CD_MS  = 10000;   // 冷却 10 秒
var WL_CHARGE_MS   = 1000;    // 蓄力 1 秒（期间无法移动、只能转向，**且玩家无敌**）
var WL_DASH_DIST   = 15.0;    // 冲刺 15 格
var WL_DASH_TICKS  = 40;      // 冲刺动画 40 刻 = 2.0 秒（不是瞬移）
                              //   ★ 1.0（蓄力）+ 2.0（冲刺）= 3.0 秒 ⇒ 整段正好 3 秒完成
                              //     想让"冲刺这一段"本身占满 3 秒就把这里改成 60（总时长变 4 秒）
// 冲刺期间**锁视角**（方向本来就不会变，锁住单纯是为了好看）：
//   · 蓄力那 1 秒**不锁** —— 玩家可以自由转视角挑方向，冲刺方向以松手那一刻为准
//   · 冲刺这 2 秒锁死在 st.heading：玩家一动鼠标就被拉回来
//   ★ 实现要点：玩家正骑在马上，`teleport` 有可能把人甩下马 ⇒ 只在"视角真的漂了"
//     才纠正（不动鼠标 = 零打扰），且纠正完立刻检查有没有掉马、掉了就当场归位。
//   ★ 实测副作用：锁视角是靠"每刻 teleport 到当前坐标"实现的，而玩家正骑在马上
//     ⇒ 客户端镜头被服务端反复拽回，画面**明显发顿发卡**。
//     **所以默认关闭（false）**——冲刺方向照样锁死在松手那一刻，只是不再强行掰镜头。
//     想重新开启（接受卡顿）就把它改回 true。
var WL_DASH_LOCK_LOOK = false;
var WL_LOOK_EPS       = 0.5;   // 漂了多少度才纠正（小于这个值当没动，避免每刻白发包）
                               //   ★ 只在 WL_DASH_LOCK_LOOK = true 时才有用

var WL_CHARGE_INVULN = true;  // ★ 蓄力那 1 秒：**玩家无敌**（站桩不能动，总不能挨打）
var WL_DASH_INVULN   = false; // 冲刺那 2 秒要不要也无敌（默认不 —— 你只说了蓄力时）
var WL_DASH_DMG    = 15.0;    // 路径伤害 15 点
var WL_DASH_HALF_W = 2.5;     // 路径左右各 2.5 格（= 宽 5 格）
var WL_DASH_HALF_H = 2.0;     // 垂直容差
// ★ 冲锋撞墙规则（）：**不再穿墙**
var WL_DASH_STEP_UP = 1;      // 最多自动**上几格**：1 = 一格高的东西当台阶踩上去（斜坡 / 楼梯）
                              //                 0 = 只有完全平整的地面才冲得过去
// ★ 蓄力阶段：玩家**头顶 2 格**浮出一匹**抬起前蹄**的粒子马（马头朝玩家右手方向）
//   ★ 画在"玩家右手方向 × 世界 Y"组成的竖直平面上 ⇒ 玩家**抬头就是马的侧影**
//   ★ 位置在头顶**偏前**一点（不是正正头顶）⇒ 完全不挡第一人称平视视野
var WL_FXHORSE_ON        = true;     // 关掉就不画
var WL_FXHORSE_Y         = 2.0;      // 马底离脚底多高（2.0 ≈ 头顶再高一点）
var WL_FXHORSE_FRONT     = 0.9;      // 马在**前方**多远（别贴在镜头上）
var WL_FXHORSE_STEP      = 0.11;     // 每隔多少格点一个粒子
var WL_FXHORSE_EVERY     = 1;        // 每几刻重画一次（1 = 每刻）
var WL_FXHORSE_PARTICLES = ['CRIT', 'ENCHANTED_HIT', 'END_ROD', 'FIREWORK_SPARK'];   // CRIT = 四角星，寿命短
var WL_FXHORSE_EYE       = ['FLAME', 'FIREWORK', 'CLOUD'];   // 眼睛（另一种粒子，跳出来更神）


// ★★ 下马动画（收阵）：4 匹护航马**当场化成烟雾**；玩家骑的那匹**朝前方 5 格奔出去**，
//    跑完再化成烟消失。玩家全程**站着不动**（只播动画，不锁任何操作）。
var WL_DISSMOKE_TICKS  = 8;        // 护航马化烟：几刻内化完（每刻冒一缕，越冒越高）
var WL_DISSMOKE_UP     = 0.42;     // 每缕往上飘多少格
var WL_FLEE_DIST       = 5.0;      // 队长马朝**玩家前方**奔出几格
var WL_FLEE_TICKS      = 10;       // 奔出去用多少刻（10 刻 = 0.5 秒）
var WL_FLEE_SMOKE      = true;     // 跑完是否也化成烟（true）还是直接消失（false）
// ★ 跑到第 5 格后**先抬前蹄站半秒**，再化烟
var WL_FLEE_REAR_TICKS = 10;       // 抬蹄停多久（10 刻 = 0.5 秒）；0 = 跑完直接化烟
var WL_FLEE_REAR_KEEP  = true;     // 化烟那几刻要不要**保持前蹄抬起**（true = 抬着蹄化掉）
// ★ 烟改成和"伺骨超时消失"一样的**大团白烟**（SMOKE_LARGE / LARGE_SMOKE）
var WL_SMOKE_PARTICLES = ['SMOKE_LARGE', 'LARGE_SMOKE', 'SMOKE_NORMAL', 'SMOKE', 'CLOUD'];
var WL_SMOKE_SNDS      = ['entity.horse.gallop', 'entity.generic.explode', 'ENTITY_HORSE_GALLOP'];
var WL_SMOKE_SND_VOL   = 0.6;
var WL_SMOKE_SND_PIT   = 1.6;      // 音调偏高 ⇒ 听起来像"嘶的一声散了"

// ★ F 键开机动画：**正前方 7 格冲出一匹骷髅马**，1.5 秒内撞到玩家身上，
//   撞到的一刻玩家**副手出现「五骊鞚」**（拴绳）并被扶上马背
var WL_RIDE_TAG      = 'gs_ridein';   // ★ 记分板标签：冲过来这匹马身上有它 ⇒ 伤害拦截器一律放行保护
var WL_RIDE_DIST     = 7.0;      // 马从玩家**正前方**几格冲出来
var WL_RIDE_TICKS    = 30;       // 30 刻 = **1.5 秒**冲完（撞到就提前收）
var WL_RIDE_HIT_R    = 0.9;      // ★ 距玩家多少格算"撞到"（贴到身上才上马 ⇒ 更近更有撞击感）
var WL_RIDE_REAR_R   = 3.2;      // 进了这个距离就**抬前蹄**（临撞前的花活）
var WL_RIDE_EASE     = 1.7;      // >1 = 越冲越快；1 = 匀速
var WL_RIDE_SND      = ['entity.horse.gallop', 'ENTITY_HORSE_GALLOP'];  // 冲刺马蹄声
var WL_RIDE_SWAP_TICKS= 3;        // 撞到后等几刻再"换马"（让删除动作看得见）
var WL_RIDE_SPAWN_FX  = true;     // 冲过来的马消失时是否冒一缕烟（强调"它走了"）
var WL_RIDE_SND_EVERY= 4;        // 每几刻播一次马蹄声
var WL_RIDE_SND_VOL  = 0.9;
var WL_RIDE_SND_PIT  = 1.15;     // 音调越低越闷
var WL_RIDE_PARTICLE = ['CLOUD', 'SMOKE_NORMAL', 'SPELL', 'FLAME'];    // 身后的尘

// ★ 冲刺期间**自动斩出 4 道剑气**（不用按左键、也不吃左键那 0.5 秒冷却）
//   朝向 = 冲刺方向（水平），全部在冲刺动画 40 刻内斩完
var WL_DASH_QI_ON       = true;
var WL_DASH_QI_AT       = [0, 6, 12, 18];   // 在第几刻斩（最后一道 + 20 刻飞行 ⇒ 正好在冲刺内收尾）
var WL_DASH_QI_SOUND    = true;             // 自动斩击要不要配音（音量为手动斩的 0.8 倍）
var WL_DASH_QI_KB_MUL   = 0.0;              // ★ 自动斩击的**击退倍率**：0 = 完全没有击退（想有就改 1）

var WL_MSG_USE     = '&6冲锋！';
var WL_MSG_CD      = '&7奔袭调息，静待时机。';

// ---------------- 状态 ----------------
var GS_WULI_STATE = {};        // uuid -> { lead, escorts[], stashed, mode, ... }
var GS_WULI_TOGGLE_AT = {};    // uuid -> 上次开关时间戳（防重复注册的实例把 toggle 翻偶数次）
var GS_WULI_SUMMONING = {};    // uuid -> 1 = **"马冲过来"演出正在播**（期间再按 F 忽略，防重复刷马）
var GS_WULI_RIDE     = {};    // uuid -> 冲过来的那匹马的状态 { h, sx, sy, sz, i }（下线 / 死亡要收马）
var GS_WULI_REGISTERED = false;

// ★★ 「本脚本自己造成的伤害」白名单 —— 极其重要：
//    手持五骊矟时，监听器会把"玩家近战"取消掉、换成剑气；
//    而**剑气 / 冲锋 / 梦境护腿光环**都是脚本自己调 ent.damage(x, p) 打出来的，
//    damager 同样是这个玩家 ⇒ 不打招呼的话，会被自己的监听器当成"近战"取消，
//    伤害全部落空（剑气砍不动人 + 光环失效）。
//    所以脚本每次自己结算伤害前先在这里打个勾，监听器看到勾就放行。
var GS_WULI_DAMAGING = {};     // uuid -> true（这一刻的伤害是本脚本发的）

function gsMarkScriptDamage(p, on) {
    try {
        if (p == null) return;
        GS_WULI_DAMAGING['' + p.getUniqueId()] = !!on;
    } catch (e) { }
}

function gsWuliNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); } catch (e) { return 0; }
}

// ---------------- 冷却（存玩家 PDC → /rsc reload 不丢） ----------------
function gsWuliCdLeft(p, key, cdMs) {
    try {
        if (key == null || GS_PDT == null || cdMs <= 0) return 0;
        var pdc = p.getPersistentDataContainer();
        if (pdc == null) return 0;
        var last = pdc.get(key, GS_PDT.LONG);
        if (last == null) return 0;
        var left = cdMs - (gsWuliNow() - (0 + last));
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

function gsWuliMarkCd(p, key) {
    try {
        if (key == null || GS_PDT == null) return;
        var pdc = p.getPersistentDataContainer();
        if (pdc != null) pdc.set(key, GS_PDT.LONG, gsWuliNow());
    } catch (e) { }
}

// ---------------- 副手拴绳「五骊鞚」 ----------------
function wlMakeRein() {
    try {
        var Material = Java.type('org.bukkit.Material');
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        var mat = null;
        try { mat = Material.LEAD; } catch (e) { mat = null; }
        if (mat == null) { try { mat = Material.valueOf('LEAD'); } catch (e) { mat = null; } }
        if (mat == null) return null;

        var st = new ItemStack(mat, 1);
        var meta = null;
        try { meta = st.getItemMeta(); } catch (e) { meta = null; }
        if (meta == null) return st;

        try { meta.setDisplayName(WL_REIN_NAME); } catch (e) { }
        try {
            var AL = Java.type('java.util.ArrayList');
            var l = new AL();
 // ★ WL_REIN_LORE 现在是数组，逐行写入
            for (var li = 0; li < WL_REIN_LORE.length; li++) {
                l.add(WL_REIN_LORE[li]);
            }
            meta.setLore(l);
        } catch (e) { }
        // ★ 标记：靠它判定"手里这根是不是我们的拴绳"（也靠它拦丢弃 / 拖动 / 换手）
        try {
            if (GS_WULI_REIN_KEY != null && GS_PDT != null) {
                meta.getPersistentDataContainer().set(GS_WULI_REIN_KEY, GS_PDT.STRING, '1');
            }
        } catch (e) { }
        // 双保险：这根绳子是**运行中临时造的**（不是 RSC 模板），可以随便改 NBT，
        //   所以顺手设永不损耗 —— 反正所有交互都已经被取消了，掉不了耐久。
        try { meta.setUnbreakable(true); } catch (e) { }
        try { st.setItemMeta(meta); } catch (e) { }
        return st;
    } catch (e) { return null; }
}

function gsWuliIsRein(item) {
    try {
        if (item == null || GS_WULI_REIN_KEY == null || GS_PDT == null) return false;
        var meta = null;
        try { meta = item.getItemMeta(); } catch (e) { return false; }
        if (meta == null) return false;
        return meta.getPersistentDataContainer().has(GS_WULI_REIN_KEY, GS_PDT.STRING);
    } catch (e) { return false; }
}

// ---------------- 召唤物标记（护腿光环跳过） ----------------
function gsIsOurSummon(ent) {
    try {
        if (ent == null || GS_WULI_SUMMON_KEY == null || GS_PDT == null) return false;
        var pdc = ent.getPersistentDataContainer();
        return pdc.has(GS_WULI_SUMMON_KEY, GS_PDT.STRING);
    } catch (e) { return false; }
}

// 移速：把马的 MOVEMENT_SPEED 抬到至少 WL_HORSE_SPEED（已经更快的就不动它）
function wlSetHorseSpeed(h) {
    try {
        if (h == null) return false;
        var Attr = Java.type('org.bukkit.attribute.Attribute');
        var k = null;
        try { k = Attr.MOVEMENT_SPEED; } catch (e) { k = null; }
        if (k == null) { try { k = Attr.valueOf('MOVEMENT_SPEED'); } catch (e) { k = null; } }
        if (k == null) { try { k = Attr.GENERIC_MOVEMENT_SPEED; } catch (e) { k = null; } }
        if (k == null) { try { k = Attr.valueOf('GENERIC_MOVEMENT_SPEED'); } catch (e) { k = null; } }
        if (k == null) return false;

        var inst = h.getAttribute(k);
        if (inst == null) return false;

        var cur = 0.0;
        try { cur = inst.getBaseValue(); } catch (e) { cur = 0.0; }
        if (cur >= WL_HORSE_SPEED) return true;       // ★ 只升不降

        inst.setBaseValue(WL_HORSE_SPEED);
        return true;
    } catch (e) { return false; }
}

// 前蹄抬起 / 落下：AbstractHorse#setRearing(boolean)
//   ★ 只在"状态真的变了"时才写 —— setRearing 每次写都会刷实体数据，
//     重复写同一个值 = 每 0.1 秒白送 5 个同步包。
function wlSetRear(h, on) {
    try {
        if (h == null) return;
        if (typeof h.setRearing !== 'function') return;      // 老版本 API 没有 → 跳过
        var want = !!on;
        try {
            if (typeof h.isRearing === 'function' && !!h.isRearing() === want) return;
        } catch (e) { }
        h.setRearing(want);
    } catch (e) { }
}

// 玩家无敌开关（蓄力 / 冲刺）
//   ★ 一定要记下"我把它打开了"：收阵 / 下线 / 死亡 / 主手换掉 时无条件关掉，
//     否则一次异常退出就会让玩家永久免伤（invulnerable 不会自己过期）。
function wlSetInvuln(p, on) {
    try {
        if (p == null) return;
        if (typeof p.setInvulnerable !== 'function') return;
        var want = !!on;
        // 已经是目标状态就别写（写一次刷一次实体数据；这里是每次冲锋才动一次，影响不大）
        try {
            if (typeof p.isInvulnerable === 'function' && !!p.isInvulnerable() === want) return;
        } catch (e) { }
        p.setInvulnerable(want);
    } catch (e) { }
}

// 两个角度的最小差值（考虑 ±360° 环绕）
function wlAngDiff(a, b) {
    var d = (a - b) % 360.0;
    if (d > 180.0) d -= 360.0;
    if (d < -180.0) d += 360.0;
    return Math.abs(d);
}

// 冲刺期间把玩家视角钉在 (yaw, pitch)
//   ★ 玩家正骑在队长马上：teleport 有掉马风险 ⇒ ① 漂了才纠正 ② 纠正后立刻检查并归位
function wlLockLook(p, st, yaw, pitch) {
    try {
        if (p == null || !WL_DASH_LOCK_LOOK) return;

        var cur = null;
        try { cur = p.getLocation(); } catch (e) { return; }
        if (cur == null) return;

        // 没漂 → 什么都不做（大多数时刻：玩家根本没动鼠标）
        try {
            if (wlAngDiff(cur.getYaw(), yaw) < WL_LOOK_EPS
                && wlAngDiff(cur.getPitch(), pitch) < WL_LOOK_EPS) return;
        } catch (e) { }

        // ① 先改服务端朝向（最轻量；没有这个 API 的老版本会走 catch）
        try { if (typeof p.setRotation === 'function') p.setRotation(yaw, pitch); } catch (e) { }

        // ② 再强制同步客户端：teleport 到"当前位置 + 锁定朝向"（只有它会真正拉回镜头）
        try {
            var LocL = Java.type('org.bukkit.Location');
            var l = new LocL(p.getWorld(), cur.getX(), cur.getY(), cur.getZ(), yaw, pitch);
            p.teleport(l);
        } catch (e) { }

        // ③ 万一被甩下马 → 当场塞回去（乘骑中断会很难看）
        try {
            var veh = p.getVehicle();
            if (veh == null && st != null && st.lead != null) {
                var ok = false;
                try { ok = !(st.lead.isDead() || !st.lead.isValid()); } catch (e1) { ok = false; }
                if (ok) st.lead.addPassenger(p);
            }
        } catch (e) { }
    } catch (e) { }
}

// ---------------- 生成一匹骷髅马 ----------------
//   isLead = true（玩家骑的那匹）：保留 AI（要能骑）、保留重力（正常骑马）
//   isLead = false（护航马）：关 AI + 关重力 —— 反正每 2 刻都被钉回队形位置
function wlSpawnHorse(world, x, y, z, yaw, isLead) {
    try {
        if (world == null) return null;

        var ET = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = ET.SKELETON_HORSE; } catch (e) { t = null; }
        if (t == null) { try { t = ET.valueOf('SKELETON_HORSE'); } catch (e) { t = null; } }
        if (t == null) return null;

        var Loc = Java.type('org.bukkit.Location');
        var loc = new Loc(world, x, y, z);
        try { loc.setYaw(yaw); loc.setPitch(0.0); } catch (e) { }

        var h = world.spawnEntity(loc, t);
        if (h == null) return null;

        // 已驯服 + 上好鞍（直接塞进鞍位，保证"骑得上去"）
        try { h.setTamed(true); } catch (e) { try { h.setTame(true); } catch (e2) { } }
        try {
            var Material = Java.type('org.bukkit.Material');
            var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
            h.getInventory().setItem(0, new ItemStack(Material.SADDLE, 1));
        } catch (e) { }

        // ★ 移速：抬到「玩家速度 IV」那一档以上（只升不降，见 WL_HORSE_SPEED 注释）
        try { wlSetHorseSpeed(h); } catch (e) { }

        try { h.setSilent(true); } catch (e) { }
        try { h.setInvulnerable(true); } catch (e) { }        // ★ 无敌（护腿光环也会跳过）
        try { h.setCollidable(false); } catch (e) { }
        try { h.setRemoveWhenFarAway(false); } catch (e) { }  // 附近没人也不消失
        try { h.setPersistent(true); } catch (e) { }
        try { h.setFallDistance(0.0); } catch (e) { }
        if (!isLead) {
            try { h.setAI(false); } catch (e) { }
            try { h.setGravity(false); } catch (e) { }
        }

        // 空战利品表：万一死了也一个都不掉
        try {
            var LootTables = Java.type('org.bukkit.loot.LootTables');
            if (LootTables.EMPTY != null) h.setLootTable(LootTables.EMPTY);
        } catch (e) { }

        // ★ 召唤物标记 → 各处伤害判定会跳过它（不伤、不弹）
        try {
            if (GS_WULI_SUMMON_KEY != null && GS_PDT != null) {
                h.getPersistentDataContainer().set(GS_WULI_SUMMON_KEY, GS_PDT.STRING, 'wuli');
            }
        } catch (e) { }

        return h;
    } catch (e) { return null; }
}

function wlRemoveHorse(h) {
    if (h == null) return;
    try { h.eject(); } catch (e) { }
    try {
        var eq = h.getEquipment();
        if (eq != null) { try { eq.clear(); } catch (e) { } }
    } catch (e) { }
    try { if (!h.isDead()) h.remove(); } catch (e) { }
}

// ---------------- 队形跟随：队长马位置 + 朝向 → 4 匹护航马的位置 ----------------
// 朝向换算（MC：yaw 0 = +Z 南、90 = -X 西）：
//   前向量 = (-sin, 0, cos)   左向量 = (cos, 0, sin)
function wlFollowFormation(st, world, cx, cy, cz, yaw, pitch, rear) {
    try {
        if (st == null || world == null) return;
        if (pitch == null) pitch = 0.0;
        rear = !!rear;
        var rad = yaw * Math.PI / 180.0;
        var fx = -Math.sin(rad), fz = Math.cos(rad);
        var lx = Math.cos(rad), lz = Math.sin(rad);

        var Loc = Java.type('org.bukkit.Location');
        for (var i = 0; i < WL_FORM.length; i++) {
            var h = (st.escorts == null) ? null : st.escorts[i];
            if (h == null) continue;
            var ok = true;
            try { ok = !(h.isDead() || !h.isValid()); } catch (e) { ok = false; }
            if (!ok) continue;

            // 前蹄抬起 / 落下（蓄力时 true，其它时候 false）
            wlSetRear(h, rear);

            var f = WL_FORM[i];
            var tx = cx + fx * (-f.back) + lx * f.side;
            var tz = cz + fz * (-f.back) + lz * f.side;

            // ★ 已经站在目标点上（静止不动时）→ 不发传送包，白省一半开销
            //   注意 pitch 也要一起比：否则"抬蹄"这种只改姿势的更新会被跳过
            var near = false;
            try {
                var cur = h.getLocation();
                var ex = cur.getX() - tx, ey = cur.getY() - cy, ez = cur.getZ() - tz;
                near = (ex * ex + ey * ey + ez * ez) < 0.0009
                    && Math.abs(cur.getYaw() - yaw) < 1.0
                    && Math.abs(cur.getPitch() - pitch) < 1.0;
            } catch (e) { near = false; }
            if (near) continue;

            var loc = new Loc(world, tx, cy, tz);
            try { loc.setYaw(yaw); loc.setPitch(pitch); } catch (e) { }
            try { h.teleport(loc); } catch (e) { }
        }
    } catch (e) { }
}

// ★★ 上马时**绝不能动玩家的视角 / 也绝不能碰玩家的位置**
//   坑 1：客户端一坐上坐骑就会把镜头 yaw 对齐到**坐骑的 yaw**，服务端拦不住。
//         而这匹马最后几刻是"朝着玩家冲"的 ⇒ 它的 yaw 跟玩家**正好相反 180°**
//         ⇒ 一 addPassenger 镜头就被掰到背后。
//         正解：**上马前**把坐骑 yaw 刷成玩家当前的 yaw —— "对齐坐骑"对齐到的
//         就是玩家本来的朝向，视觉上一点没变。（这是唯一无副作用的解法）
//   坑 2：★ **玩家在骑乘状态下 `p.teleport(loc)` 会把玩家锁死** ——
//         每刻强发位置包把客户端插值反复拽回，玩家动不了、画面发顿。
//         ⇒ 骑乘中**绝对不要**用 teleport 纠正朝向（哪怕只改 yaw 也不行）。
//         想"温和地"改服务端朝向可以用 `Player#setRotation(yaw,pitch)`（1.20.5+），
//         但客户端镜头是否跟随不保证，所以这里干脆什么都不做。
function wlAlignMount(horse, p) {
    try {
        if (horse == null || p == null) return;
        var l = p.getLocation();
        var h = horse.getLocation();
        var Loc = Java.type('org.bukkit.Location');
        horse.teleport(new Loc(l.getWorld(), l.getX(), l.getY(), l.getZ(), l.getYaw(), 0.0));
        // （顺手清一下速度，别带着冲过来的惯性）
        try { horse.setVelocity(new Java.type('org.bukkit.util.Vector')(0.0, 0.0, 0.0)); } catch (e) { }
        try { horse.setFallDistance(0.0); } catch (e) { }
    } catch (e) { }
}

// ---------------- 下马动画：化烟 + 奔出 ----------------
function wlSmokeType() {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < WL_SMOKE_PARTICLES.length; i++) {
            var n = '' + WL_SMOKE_PARTICLES[i];
            try { var t = P[n]; if (t != null) return t; } catch (e) { }
            try { var t2 = P.valueOf(n); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★ 在某点冒一缕"分散的烟"（不是一大团，避免糊脸）
function wlPuff(world, x, y, z, seed) {
    try {
        var pt = wlSmokeType();
        if (pt == null) return;
        for (var i = 0; i < 6; i++) {
            var a = (i * 2.39996) + seed;                     // 黄金角 → 均匀散开不重叠
            var r = 0.16 + ((i * 7 + seed * 3) % 5) * 0.05;
            try {
                world.spawnParticle(pt,
                    x + Math.cos(a) * r,
                    y + ((i * 5 + seed) % 4) * 0.08,
                    z + Math.sin(a) * r,
                    1, 0.0, 0.0, 0.0, 0.0);
            } catch (e) { }
        }
    } catch (e) { }
}

function wlSmokeSound(world, loc) {
    try {
        if (world == null || loc == null) return;
        for (var i = 0; i < WL_SMOKE_SNDS.length; i++) {
            try { world.playSound(loc, WL_SMOKE_SNDS[i], WL_SMOKE_SND_VOL, WL_SMOKE_SND_PIT); return; } catch (e) { }
        }
    } catch (e) { }
}

// ★ 化烟：每刻冒一缕往上飘，化完直接消失
//   keepRear = true ⇒ 化烟时也**保持前蹄抬起**（队长马"抬着蹄化掉"才好看）
function wlSmokeHorseTick(h, i, keepRear) {
    try {
        if (h == null) return;
        var bad = false;
        try { bad = h.isDead() || !h.isValid(); } catch (e) { bad = true; }
        if (bad) return;
        var l = h.getLocation();
        try { wlSetRear(h, !!keepRear && WL_FLEE_REAR_KEEP); } catch (e) { }
        wlPuff(l.getWorld(), l.getX(), l.getY() + 0.35 + i * WL_DISSMOKE_UP, l.getZ(), i);
        if (i === 0) wlSmokeSound(l.getWorld(), l);
        if (i >= WL_DISSMOKE_TICKS) { wlRemoveHorse(h); return; }
        var kr = !!keepRear && WL_FLEE_REAR_KEEP;
        runLater(function (hh, ii, kk) { return function () { wlSmokeHorseTick(hh, ii + 1, kk); }; }(h, i, kr), 1);
    } catch (e) { }
}

// ★ 奔出之后的收尾：化烟（或直接消失）+ 回调
function wlEndFlee(h, onEnd) {
    try {
        if (h != null) {
            if (WL_FLEE_SMOKE) { try { wlSmokeHorseTick(h, 0, true); } catch (e) { wlRemoveHorse(h); } }
            else { wlRemoveHorse(h); }
        }
    } catch (e) { try { wlRemoveHorse(h); } catch (e2) { } }
    try { if (onEnd) onEnd(); } catch (e9) { }
}

// ★ 跑到第 5 格之后：**原地抬前蹄 WL_FLEE_REAR_TICKS 刻（默认半秒）**，然后再化白烟
function wlFleeRearTick(h, i, onEnd) {
    try {
        if (h == null) { wlEndFlee(h, onEnd); return; }
        var bad = false;
        try { bad = h.isDead() || !h.isValid(); } catch (e) { bad = true; }
        if (bad) { wlEndFlee(h, onEnd); return; }

        var world = null, l = null;
        try { world = h.getWorld(); l = h.getLocation(); } catch (e) { world = null; l = null; }
        if (world == null || l == null) { wlEndFlee(h, onEnd); return; }

        try { wlSetRear(h, true); } catch (e) { }          // ★ 前蹄抬起
        try { h.setVelocity(new Java.type('org.bukkit.util.Vector')(0.0, 0.0, 0.0)); } catch (e) { }

        // 抬蹄那一刻蹬一下地面（扬尘 + 一声嘶）
        if (i === 0) {
            try { wlSmokeSound(world, l); } catch (e) { }
            var pt = wlSmokeType();
            if (pt != null) {
                try { world.spawnParticle(pt, l.getX(), l.getY() + 0.12, l.getZ(), 6, 0.25, 0.06, 0.25, 0.0); } catch (e) { }
            }
        }

        if (i + 1 >= WL_FLEE_REAR_TICKS) { wlEndFlee(h, onEnd); return; }
        runLater(function (hh, ii, cb) {
            return function () { wlFleeRearTick(hh, ii + 1, cb); };
        }(h, i, onEnd), 1);
    } catch (e) { wlEndFlee(h, onEnd); }
}

// ★ 队长马奔出：朝玩家前方 WL_FLEE_DIST 格冲，跑完抬蹄半秒再化烟（或直接消失）
function wlFleeHorseTick(h, sx, sy, sz, dirx, dirz, i, onEnd) {
    try {
        if (h == null) { try { if (onEnd) onEnd(); } catch (e9) { } return; }
        var bad = false;
        try { bad = h.isDead() || !h.isValid(); } catch (e) { bad = true; }
        if (bad) { try { if (onEnd) onEnd(); } catch (e9) { } return; }

        var world = null, l = null;
        try { world = h.getWorld(); l = h.getLocation(); } catch (e) { world = null; l = null; }
        if (world == null || l == null) { try { if (onEnd) onEnd(); } catch (e9) { } return; }

        var k = (i + 1) / WL_FLEE_TICKS;
        if (k > 1) k = 1;
        var e = 1.0 - Math.pow(1.0 - k, 2.2);                 // 起步慢、越跑越快
        var nx = sx + dirx * WL_FLEE_DIST * e;
        var nz = sz + dirz * WL_FLEE_DIST * e;
        var ny = sy;

        // 朝奔出去的方向
        var yaw = Math.atan2(-dirx, dirz) * 180.0 / Math.PI;  // MC：yaw 0 = +Z
        try {
            var Loc = Java.type('org.bukkit.Location');
            h.teleport(new Loc(world, nx, ny, nz, yaw, 0.0));
            h.setVelocity(new Java.type('org.bukkit.util.Vector')(0.0, 0.0, 0.0));
        } catch (e2) { }

        // 蹄下扬尘 + 马蹄声
        var pt = wlSmokeType();
        if (pt != null && (i % 2) === 0) {
            try { world.spawnParticle(pt, nx, ny + 0.12, nz, 4, 0.16, 0.05, 0.16, 0.0); } catch (e3) { }
        }
        if (i % 3 === 0) {
            try {
                world.playSound(new Loc(world, nx, ny, nz), WL_SMOKE_SNDS[0], 0.5, 1.2);
            } catch (e4) { }
        }

        if (i + 1 >= WL_FLEE_TICKS) {
 // ★ 跑到第 WL_FLEE_DIST 格 ⇒ **先抬前蹄站半秒**，再化白烟
            if (WL_FLEE_REAR_TICKS > 0) {
                try { wlFleeRearTick(h, 0, onEnd); } catch (e5) { wlEndFlee(h, onEnd); }
            } else {
                wlEndFlee(h, onEnd);
            }
            return;
        }
        runLater(function (hh, ax, ay, az, dx2, dz2, ii, cb) {
            return function () { wlFleeHorseTick(hh, ax, ay, az, dx2, dz2, ii, cb); };
        }(h, sx, sy, sz, dirx, dirz, i + 1, onEnd), 1);
    } catch (e) {
        try { if (onEnd) onEnd(); } catch (e9) { }
    }
}

// ★ 收阵动画总入口：护航马立刻化烟，队长马朝玩家前方奔出去
//   ★ 玩家的位置 / 朝向**一个字节都不碰**（只动马）⇒ 不锁移动、不掰视角
function gsWuliDismissAnim(p, st) {
    try {
        if (st == null) return;

        // ① 先卸下玩家（不传送、不改朝向）—— 玩家只是"从马上下来"，站原地
        try { if (p != null) p.leaveVehicle(); } catch (e) { try { if (p != null) p.eject(); } catch (e2) { } }

        // ② 玩家当前朝向（只用来定队长马往哪边跑）
        var fx = 0.0, fz = 1.0;
        try {
            var pl = p.getLocation();
            var rad = pl.getYaw() * Math.PI / 180.0;
            fx = -Math.sin(rad); fz = Math.cos(rad);
        } catch (e) { }

        // ③ 4 匹护航马 → 当场化烟
        if (st.escorts != null) {
            for (var i = 0; i < st.escorts.length; i++) {
                var h = st.escorts[i];
                if (h == null) continue;
                wlSmokeHorseTick(h, 0);
            }
        }
        st.escorts = [];

        // ④ 队长马 → 朝玩家前方奔出 WL_FLEE_DIST 格，跑到头再化烟
        var lead = st.lead;
        st.lead = null;
        if (lead != null) {
            var ok = true;
            try { ok = !(lead.isDead() || !lead.isValid()); } catch (e) { ok = false; }
            if (ok) {
                try {
                    var ll = lead.getLocation();
                    wlFleeHorseTick(lead, ll.getX(), ll.getY(), ll.getZ(), fx, fz, 0, null);
                } catch (e) { wlRemoveHorse(lead); }
            } else {
                wlRemoveHorse(lead);
            }
        }
    } catch (e) { }
}

// ---------------- 开机 / 关机 ----------------
// ★ 只生成"玩家要骑的那一匹 + 4 匹护航马" —— 冲过来引路的那匹已经在
//   wlRideFinish 里删掉了，所以这里永远是**全新 spawn**，天生就在玩家位置、朝向 = 玩家朝向。
function gsWuliSummon(p) {
    try {
        var uuid = '' + p.getUniqueId();
        var world = p.getWorld();
        var base = p.getLocation();
        var yaw = 0;
        try { yaw = base.getYaw(); } catch (e) { }

        var st = { lead: null, escorts: [], stashed: null, mode: 'follow', dead: false,
            heading: yaw, fxTick: 0 };

        var rad = yaw * Math.PI / 180.0;
        var fx = -Math.sin(rad), fz = Math.cos(rad);
        var lx = Math.cos(rad), lz = Math.sin(rad);

        // 队长马（玩家骑的那匹）：★ **全新生成**在玩家位置、朝向 = 玩家当前 yaw
        //   （"新马天生就是玩家朝向" ⇒ 上马时客户端把镜头对齐坐骑 = 对齐玩家本来的朝向，
        //     根本不会掰视角，也就不需要任何事后纠正）
        st.lead = wlSpawnHorse(world, base.getX(), base.getY(), base.getZ(), yaw, true);

        // 4 匹护航马（^ 队形，左右各 2）
        for (var i = 0; i < WL_FORM.length; i++) {
            var f = WL_FORM[i];
            st.escorts.push(wlSpawnHorse(world,
                base.getX() + fx * (-f.back) + lx * f.side,
                base.getY(),
                base.getZ() + fz * (-f.back) + lz * f.side,
                yaw, false));
        }

        // 副手：先把原来的东西"请出去"（背包塞不下掉脚边），再放上拴绳
        try {
            var inv = p.getInventory();
            var old = null;
            try { old = inv.getItemInOffHand(); } catch (e) { }
            try { inv.setItemInOffHand(null); } catch (e) { }
            if (old != null && !gsWuliIsRein(old)) gsScopeStash(p, old);
            var rein = wlMakeRein();
            if (rein != null) inv.setItemInOffHand(rein);
        } catch (e) { }

        // 骑上队长马（★ 上马前已把坐骑 yaw 对齐玩家朝向 ⇒ 镜头不会掰）
        try { if (st.lead != null) st.lead.addPassenger(p); } catch (e) { }

        GS_WULI_STATE[uuid] = st;
        try { world.playSound(base, 'entity.horse.saddle', 1.0, 1.0); } catch (e) { }
        return st;
    } catch (e) { return null; }
}

// anim = true → 播下马动画（护航马化烟 / 队长马奔出）；false → 立刻消失（死亡、下线、换武器）
function gsWuliDismiss(p, st, restore, anim) {
    try {
        if (st == null) return;
        st.dead = true;
        st.mode = 'follow';

        // ★ 收阵 = 无条件解除玩家无敌（蓄力无敌只在这段动作里有效，绝不能带出去）
        try { if (p != null) wlSetInvuln(p, false); } catch (e0) { }

        if (anim === true) {
            // ★ 走动画：马自己散场，玩家的位置 / 朝向**一点都不碰**
            gsWuliDismissAnim(p, st);
        } else {
            wlRemoveHorse(st.lead);
            if (st.escorts != null) {
                for (var i = 0; i < st.escorts.length; i++) wlRemoveHorse(st.escorts[i]);
            }
            st.lead = null;
            st.escorts = [];
            // 把玩家从马上卸下来
            try { if (p != null) p.leaveVehicle(); } catch (e) { try { if (p != null) p.eject(); } catch (e2) { } }
        }

        // 副手拴绳收走 + 还回原来那件东西
        try {
            if (p != null) {
                var inv = p.getInventory();
                var off = inv.getItemInOffHand();
                if (gsWuliIsRein(off)) inv.setItemInOffHand(null);
            }
        } catch (e) { }
        if (restore && p != null && st.stashed != null) {
            try { gsScopeStash(p, st.stashed); } catch (e) { }
        }
        st.stashed = null;
    } catch (e) { }
}

function gsWuliQuit(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();

        // ★ "马正在冲过来"的半途状态 → 先把这匹野马收掉
        try {
            var rc = GS_WULI_RIDE[uuid];
            if (rc != null) { wlRemoveHorse(rc.h); GS_WULI_RIDE[uuid] = null; }
        } catch (e0) { }
        try { GS_WULI_SUMMONING[uuid] = null; } catch (e0b) { }

        var st = GS_WULI_STATE[uuid];
        if (st == null) return;
        gsWuliDismiss(p, st, true);
        delete GS_WULI_STATE[uuid];
    } catch (e) { }
}

// F 键 = 开机 / 关机（带 250ms 去抖：/rsc reload 会重复注册监听器，
//   一次按键被处理 N 遍时，"翻转偶数次 = 没反应"）
function gsWuliToggle(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();

        var now = gsWuliNow();
        var last = GS_WULI_TOGGLE_AT[uuid];
        if (last != null && (now - last) < 250) return;
        GS_WULI_TOGGLE_AT[uuid] = now;

        var st = GS_WULI_STATE[uuid];
        if (st != null) {                 // 已开机 → 关机（★ 播下马动画：化烟 + 奔出）
            gsWuliDismiss(p, st, true, true);
            delete GS_WULI_STATE[uuid];
            return;
        }
        wlRideIn(p);                      // 没开机 → **身后 7 格冲出一匹马，撞到才上马**
    } catch (e) { }
}

// ---------- F 键开机演出：身后 7 格冲出一匹骷髅马 → 撞到玩家 → 副手给拴绳 ----------
// 播一个音效（候选链：新包名 → 老包名 → 老枚举名）
function wlRideSound(world, loc, chain, vol, pit) {
    try {
        if (world == null || loc == null) return;
        for (var i = 0; i < chain.length; i++) {
            try { world.playSound(loc, chain[i], vol, pit); return; } catch (e) { }
        }
    } catch (e) { }
}

function wlRideParticle() {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < WL_RIDE_PARTICLE.length; i++) {
            var n = '' + WL_RIDE_PARTICLE[i];
            try { var t = P[n]; if (t != null) return t; } catch (e) { }
            try { var t2 = P.valueOf(n); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★ **正前方**几格的那个坐标（沿玩家朝向往前推）
function wlRideSpawnLoc(p) {
    try {
        var l = p.getLocation();
        var rad = l.getYaw() * Math.PI / 180.0;
        var fx = -Math.sin(rad), fz = Math.cos(rad);      // 朝向（MC：yaw 0 = +Z）
        var Loc = Java.type('org.bukkit.Location');
        return new Loc(l.getWorld(), l.getX() + fx * WL_RIDE_DIST, l.getY(), l.getZ() + fz * WL_RIDE_DIST);
    } catch (e) { return null; }
}

// 开始冲（GS_WULI_SUMMONING 兼做"正在冲"标记：演出期间再按 F 直接忽略）
function wlRideIn(p) {
    try {
        if (p == null || !p.isOnline()) return;
        var uuid = '' + p.getUniqueId();
        if (GS_WULI_SUMMONING[uuid] === 1) return;        // 已经在冲了 → 忽略（防连按刷出两批马）
        var world = p.getWorld();

        // 生成失败（世界禁刷怪等）⇒ 直接走老流程，绝不把玩家卡在"按了 F 没反应"
        var loc = wlRideSpawnLoc(p);
        if (loc == null) { gsWuliSummon(p); return; }
        var h = wlSpawnHorse(world, loc.getX(), loc.getY(), loc.getZ(), 0, true);
        if (h == null) { gsWuliSummon(p); return; }

        // ★★ 冲过来这 1.5 秒里**绝对不能死**（死了就永远上不了马），三层保险：
        //   ① `setInvulnerable(true)` —— 挡掉绝大多数伤害（`wlSpawnHorse` 里已设，这里再设一次）
        //   ② 血量直接顶到上限 —— 就算 invulnerable 被别的插件/指令清掉了，也不会被残血打死
        //   ③ 记分板标签 `gs_ridein` —— 伤害事件拦截器看到就直接 cancel（见 gsOnEntityDamageSync）
        try { h.setInvulnerable(true); } catch (e) { }
        try { h.setHealth(h.getMaxHealth()); } catch (e) { }
        try { h.addScoreboardTag(WL_RIDE_TAG); } catch (e) { }

        GS_WULI_SUMMONING[uuid] = 1;
        gsWuliRideTick(p, {
            h: h,
            sx: loc.getX(), sy: loc.getY(), sz: loc.getZ(),
            i: 0, done: false
        });
    } catch (e) {
        try { GS_WULI_SUMMONING['' + p.getUniqueId()] = null; } catch (e2) { }
        try { gsWuliSummon(p); } catch (e3) { }
    }
}

// ★★ 收场：**冲过来的那匹马直接删除**，等 3 刻后在玩家**原地**另生成一匹新的
//    已驯服骷髅马（朝向 = 玩家朝向）给玩家骑 —— 冲过来的是"引路的马"，不是"要骑的马"。
//    （顺带好处：不用再操心"冲过来的马 yaw 跟玩家相反会不会掰镜头"，
//      新马天生就是玩家朝向，`wlAlignMount` 只需要摆位置。）
function wlRideFinish(p, c) {
    try {
        var uuid = '' + p.getUniqueId();
        try { GS_WULI_RIDE[uuid] = null; } catch (e) { }
        // ★ 注意：这里**故意不清** GS_WULI_SUMMONING —— 换马延迟的 3 刻内保持"演出中"，
        //   玩家连按 F 不会又触发一次冲马。等新马生成完才清。
        if (p == null || !p.isOnline()) {
            try { GS_WULI_SUMMONING[uuid] = null; } catch (e1b) { }
            try { wlRemoveHorse(c == null ? null : c.h); } catch (e2) { }   // 人不在了 → 马也收掉
            return;
        }

        // ① 记下冲过来那匹马的位置（它马上要没了）
        var hx = null, hy = null, hz = null, hw = null;
        try {
            if (c != null && c.h != null) {
                var hl = c.h.getLocation();
                hx = hl.getX(); hy = hl.getY(); hz = hl.getZ(); hw = hl.getWorld();
            }
        } catch (e) { }

        // ② ★ 撞到的那一下：一圈冲击尘 + 一声闷响（"撞上了"的实感）
        try {
            var w2 = p.getWorld();
            var l2 = p.getLocation();
            var pt2 = wlRideParticle();
            if (pt2 != null) {
                for (var ci = 0; ci < 10; ci++) {
                    var a2 = Math.PI * 2.0 * ci / 10.0;
                    w2.spawnParticle(pt2,
                        l2.getX() + Math.cos(a2) * 0.7, l2.getY() + 0.3, l2.getZ() + Math.sin(a2) * 0.7,
                        1, 0.0, 0.0, 0.0, 0.0);
                }
            }
            wlRideSound(w2, l2, ['block.dirt.land', 'entity.horse.gallop'], 0.7, 0.8);
        } catch (e2b) { }

        // ③ ★★ 冲过来的马**直接删除**（原地冒一缕烟，强调"它只是来带路的"）
        try {
            if (WL_RIDE_SPAWN_FX && hw != null) {
                for (var si = 0; si < 3; si++) {
                    try { wlPuff(hw, hx, hy + 0.4 + si * 0.3, hz, si); } catch (e3) { }
                }
            }
        } catch (e4) { }
        try { wlRemoveHorse(c == null ? null : c.h); } catch (e5) { }

        // ④ 等 3 刻（让"删除"看得见），**在玩家原地另生成一匹新马**并开机
        //    ★ gsWuliSummon 全新 spawn：位置 = 玩家位置、yaw = 玩家当前 yaw
        var delay = (WL_RIDE_SWAP_TICKS > 0) ? WL_RIDE_SWAP_TICKS : 1;
        runLater(function (pp, uu) {
            return function () {
                try {
                    if (pp == null || !pp.isOnline() || pp.isDead()) {
                        try { GS_WULI_SUMMONING[uu] = null; } catch (e) { }
                        return;                                              // 人没了就别生成
                    }
                    gsWuliSummon(pp);
                    GS_WULI_SUMMONING[uu] = null;                           // ★ 这时才清标记
                } catch (e) { }
            };
        }(p, uuid), delay);
    } catch (e) { }
}

function gsWuliRideTick(p, c) {
    try {
        if (c == null || c.done) return;
        var uuid = '' + p.getUniqueId();
        GS_WULI_RIDE[uuid] = c;

        // 玩家掉线 / 死亡 → 收马走人（绝不在世界里留一匹野马）
        if (p == null || !p.isOnline() || p.isDead()) {
            wlRemoveHorse(c.h);
            try { GS_WULI_RIDE[uuid] = null; } catch (e) { }
            try { GS_WULI_SUMMONING[uuid] = null; } catch (e2) { }
            return;
        }
        var h = c.h;
        var bad = (h == null);
        if (!bad) { try { bad = h.isDead() || !h.isValid(); } catch (e) { bad = true; } }
        if (bad) { wlRideFinish(p, null); return; }        // 马中途没了 → 照样给拴绳

        var world = p.getWorld();
        var pl = p.getLocation();
        var k = (c.i + 1) / WL_RIDE_TICKS;
        if (k > 1) k = 1;
        var ease = (WL_RIDE_EASE <= 1) ? k : Math.pow(k, WL_RIDE_EASE);   // 越冲越快

        // 目标 = 玩家**此刻**的位置（玩家可以一边被撞一边走）
        var tx = pl.getX(), ty = pl.getY(), tz = pl.getZ();
        var nx = c.sx + (tx - c.sx) * ease;
        var ny = c.sy + (ty - c.sy) * ease;
        var nz = c.sz + (tz - c.sz) * ease;

        // 撞上了？（马身有宽度，用 1.9 格比较稳）⇒ 提前收场
        var dx = tx - nx, dy = ty - ny, dz = tz - nz;
        var d2 = dx * dx + dy * dy + dz * dz;
        var dist = Math.sqrt(d2);

        // ★ 每刻**续一次命**：invulnerable 不是永久的（部分插件会清、指令能清、
        //   凋零 / 火焰 / 虚空这类伤害也可能绕过）⇒ 演出期间每刻重新钉一遍
        try { h.setInvulnerable(true); } catch (e9) { }
        try { if (h.getHealth() < h.getMaxHealth()) h.setHealth(h.getMaxHealth()); } catch (e10) { }
        try { h.addScoreboardTag(WL_RIDE_TAG); } catch (e11) { }

        var Loc = Java.type('org.bukkit.Location');
        // 朝向 = 冲向玩家的方向
        var yaw = pl.getYaw();
        if (dist > 0.05) {
            var hy = Math.atan2(-dx, dz) * 180.0 / Math.PI;   // MC：yaw 0 = +Z
            yaw = hy;
        }
        try { h.teleport(new Loc(world, nx, ny, nz, yaw, 0)); } catch (e) { }

        // 临撞前抬前蹄（花活）+ 身后扬尘
        try { wlSetRear(h, dist <= WL_RIDE_REAR_R); } catch (e2) { }
        var pt = null;
        try {
            pt = wlRideParticle();
            if (pt != null && (c.i % 2) === 0) {
                world.spawnParticle(pt, nx, ny + 0.15, nz, 3, 0.18, 0.05, 0.18, 0.0);
            }
        } catch (e3) { }
        // 马蹄声
        if (WL_RIDE_SND_EVERY > 0 && (c.i % WL_RIDE_SND_EVERY) === 0) {
            try { wlRideSound(world, new Loc(world, nx, ny, nz), WL_RIDE_SND, WL_RIDE_SND_VOL, WL_RIDE_SND_PIT); } catch (e4) { }
        }

        c.i++;
        if (dist <= WL_RIDE_HIT_R || c.i >= WL_RIDE_TICKS) {
            // ★ 撞到 ⇒ 副手出现「五骊鞚」+ 扶上马（1.5 秒内没撞上也会到点强制收）
            try { wlSetRear(h, false); } catch (e5) { }
            wlRideFinish(p, c);
            return;
        }
        runLater(function () { gsWuliRideTick(p, c); }, 1);
    } catch (e) {
        try { wlRideFinish(p, c); } catch (e2) { }
    }
}

// ---------------- 左键：弧形剑气（7 点 · 轻微击退 · 冷却 1.5 秒 · 无提示） ----------------
function wlNotTarget(p, ent) {
    try {
        if (ent == null) return true;
        var eid = '' + ent.getUniqueId();
        var sid = '' + p.getUniqueId();
        if (eid === sid) return true;                       // 不打自己
        try { ent.getHealth(); } catch (e) { return true; } // 只认活体
        try { if (ent.isDead()) return true; } catch (e) { }
        var tn = '' + ent.getType().name();
        if (tn === 'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM' || tn === 'EXPERIENCE_ORB') return true;
        if (gsIsOurSummon(ent)) return true;                 // 自己的召唤物（五骊马 / 悦灵）
        if (gsIsMaiyouSummon(ent)) return true;              // 麦友的酒
        return false;
    } catch (e) { return true; }
}

// 造成伤害，并**自己决定击退多少**：
//   · 不传 kb（冲锋）：先记下当前速度，打完原样塞回去 ⇒ 完全没有击退
//     （原版 knockback 是加在 velocity 上的，所以"存→打→还原"就等于抹掉了它）
//   · 传 kb（剑气）：还原的速度上再叠加一点 kb 冲量 ⇒ 轻微击退
//     kb 分别是 x/y/z 三个分量（已经不是单位向量，直接用数值大小控制"一点点"）
function wlHurt(p, ent, dmg, kbx, kby, kbz) {
    try {
        if (ent == null) return;
        try { ent.setNoDamageTicks(0); } catch (e) { }

        var vx = 0, vy = 0, vz = 0, hasV = false;
        try {
            var v = ent.getVelocity();
            vx = v.getX(); vy = v.getY(); vz = v.getZ();
            hasV = true;
        } catch (e) { }

        // ★ 打勾：这是脚本自己结算的伤害，"禁止近战"的监听器必须放行
        gsMarkScriptDamage(p, true);
        try { ent.damage(dmg, p); }
        catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
        gsMarkScriptDamage(p, false);

        var kx = (kbx == null ? 0.0 : kbx);
        var ky = (kby == null ? 0.0 : kby);
        var kz = (kbz == null ? 0.0 : kbz);
        var anyKb = (kx !== 0.0 || ky !== 0.0 || kz !== 0.0);

        if (hasV || anyKb) {
            try {
                var Vector = Java.type('org.bukkit.util.Vector');
                ent.setVelocity(new Vector(vx + kx, vy + ky, vz + kz));
            } catch (e) { }
        }
    } catch (e) { }
}

// 以 dir 为前进轴的一组正交基（lat = 水平左右，up2 = 与视线垂直的"上下"）
function wlAxes(dir) {
    var dz = dir.getZ(), dx = dir.getX(), dy = dir.getY();
    // lat = dir × (0,1,0) = (-dz, 0, dx)
    var lx = -dz, ly = 0.0, lz = dx;
    var ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
    if (ln < 1.0e-4) { lx = 1.0; ly = 0.0; lz = 0.0; ln = 1.0; }
    lx /= ln; ly /= ln; lz /= ln;
    // up2 = dir × lat
    var ux = dy * lz - dz * ly;
    var uy = dz * lx - dx * lz;
    var uz = dx * ly - dy * lx;
    var un = Math.sqrt(ux * ux + uy * uy + uz * uz);
    if (un < 1.0e-4) { ux = 0.0; uy = 1.0; uz = 0.0; un = 1.0; }
    ux /= un; uy /= un; uz /= un;
    return { lx: lx, ly: ly, lz: lz, ux: ux, uy: uy, uz: uz };
}

function gsWuliTryQi(p) {
    try {
        // 冷却中**完全静默**（不提示、不出声、不出剑气）
        if (gsWuliCdLeft(p, GS_WULI_QI_KEY, WL_QI_CD_MS) > 0) return;
        gsWuliMarkCd(p, GS_WULI_QI_KEY);

        // ★ 斩击音效：末影龙飞行 + 末影人传送（同一时刻一起响）
        try {
            var w = p.getWorld();
            var l = p.getLocation();
            w.playSound(l, WL_QI_SND1, WL_QI_SND1_VOL, WL_QI_SND1_PIT);
            w.playSound(l, WL_QI_SND2, WL_QI_SND2_VOL, WL_QI_SND2_PIT);
        } catch (e) { }

        var eye = null;
        try { eye = p.getEyeLocation(); } catch (e) { return; }
        var dir = null;
        try { dir = eye.getDirection(); } catch (e) { return; }
        if (dir == null) return;

        var base = { x: eye.getX(), y: eye.getY() - 0.35, z: eye.getZ() };
        var ax = wlAxes(dir);
        wlQiStep(p, base, dir, ax, 0.5, {});
    } catch (e) { }
}

// 剑气：一步步往前推（自终止的 runLater 链，不需要 cancel）
//   kbMul：击退倍率（**冲刺自动斩的那 4 道传 0 ⇒ 完全无击退**；手动左键默认 1）
function wlQiStep(p, base, dir, ax, d, hit, kbMul) {
    try {
        if (d > WL_QI_RANGE) return;                  // 飞满 10 格 → 结束
        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

        var dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();

        // 击退方向 = 剑气的**水平**方向（低头/抬头砍时不要把目标往地里/天上怼）
        var hx = dx, hz = dz;
        var hl = Math.sqrt(hx * hx + hz * hz);
        if (hl < 1.0e-4) { hx = 0.0; hz = 0.0; } else { hx /= hl; hz /= hl; }

        // ① 画弧形剑气：在 d 处横着扫一道 5 格长的弧（SWEEP_ATTACK 就是原版横扫的粒子）
        var pt1 = null, pt2 = null;
        try {
            var Particle = Java.type('org.bukkit.Particle');
            try { pt1 = Particle.SWEEP_ATTACK; } catch (e) { pt1 = null; }
            if (pt1 == null) { try { pt1 = Particle.valueOf('SWEEP_ATTACK'); } catch (e) { } }
            try { pt2 = Particle.CRIT; } catch (e) { pt2 = null; }
        } catch (e) { }

        for (var i = -5; i <= 5; i++) {
            var t = i / 5.0;
            var off = t * WL_QI_HALF_W;                 // 左右展开 ±2.5 格
            var bulge = (1.0 - t * t) * 0.45;           // 中间略往前凸 → 弧形
            var px = base.x + dx * (d + bulge) + ax.lx * off;
            var py = base.y + dy * (d + bulge) + ax.ly * off;
            var pz = base.z + dz * (d + bulge) + ax.lz * off;
            if (pt1 != null) { try { world.spawnParticle(pt1, px, py, pz, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { } }
            if (pt2 != null && (i % 2 === 0)) { try { world.spawnParticle(pt2, px, py, pz, 1, 0.05, 0.05, 0.05, 0.0); } catch (e) { } }
        }

        // ② 命中判定：d 前后 ±厚度、左右 ±2.5、上下 ±1.5 的"弧形薄片"里的活体
        var cx = base.x + dx * d, cy = base.y + dy * d, cz = base.z + dz * d;
        var r = WL_QI_HALF_W + WL_QI_HALF_T + 1.0;
        var list = null;
        try {
            var Loc = Java.type('org.bukkit.Location');
            list = world.getNearbyEntities(new Loc(world, cx, cy, cz), r, WL_QI_HALF_H + 1.0, r);
        } catch (e) { list = null; }

        if (list != null) {
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null) continue;
                var eid = '';
                try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                if (hit[eid] === 1) continue;               // 同一道剑气每个目标只吃一次

                if (wlNotTarget(p, ent)) continue;

                var relx = 0, rely = 0, relz = 0;
                try {
                    var el = ent.getLocation();
                    relx = el.getX() - base.x;
                    rely = (el.getY() + 0.9) - base.y;
                    relz = el.getZ() - base.z;
                } catch (e) { continue; }

                var along = relx * dx + rely * dy + relz * dz;
                var lat = relx * ax.lx + rely * ax.ly + relz * ax.lz;
                var vup = relx * ax.ux + rely * ax.uy + relz * ax.uz;

                if (Math.abs(along - d) > WL_QI_HALF_T) continue;
                if (Math.abs(lat) > WL_QI_HALF_W) continue;
                if (Math.abs(vup) > WL_QI_HALF_H) continue;

                hit[eid] = 1;
                // 7 点伤害 + 击退（kbMul = 0 ⇒ 不传 kb ⇒ wlHurt 会把原速度塞回去，完全无击退）
                var km = (kbMul == null ? 1.0 : kbMul);
                if (km <= 0) {
                    wlHurt(p, ent, WL_QI_DMG);
                } else {
                    wlHurt(p, ent, WL_QI_DMG, hx * WL_QI_KB * km, WL_QI_KB_UP * km, hz * WL_QI_KB * km);
                }
            }
        }

        runLater(function () { wlQiStep(p, base, dir, ax, d + WL_QI_STEP, hit, kbMul); }, 1);
    } catch (e) { }
}

// ---------------- 右键：1 秒蓄力（无敌） → 冲刺 15 格（整段 3 秒） ----------------
function gsWuliTryCharge(p) {
    try {
        var uuid = '' + p.getUniqueId();
        var st = GS_WULI_STATE[uuid];
        if (st == null) return;                                  // 没开机 → 右键什么都不做

        // 正在蓄力 / 冲刺中 → 静默忽略（这时候冷却还没走完，不该刷"冷却中"的提示）
        if (st.mode !== 'follow') return;

        if (gsWuliCdLeft(p, GS_WULI_DASH_KEY, WL_DASH_CD_MS) > 0) {
            sendMessage(p, WL_MSG_CD);                           // 冷却提示
            return;
        }

        gsWuliMarkCd(p, GS_WULI_DASH_KEY);
        st.mode = 'charge';
        st.fxTick = 0;

        var l = null;
        try { l = (st.lead != null) ? st.lead.getLocation() : p.getLocation(); } catch (e) { l = p.getLocation(); }
        st.anchor = { x: l.getX(), y: l.getY(), z: l.getZ() };
        try { st.anchor.yaw = p.getLocation().getYaw(); } catch (e) { st.anchor.yaw = 0; }
        st.chargeEnd = gsWuliNow() + WL_CHARGE_MS;

        // ★ 蓄力这 1 秒玩家**无敌**（站桩不能动、也不能跑，再挨打就太亏了）
        //   收阵 / 下线 / 死亡 / 主手换掉 都会在 gsWuliDismiss 里无条件关掉
        if (WL_CHARGE_INVULN) { try { wlSetInvuln(p, true); } catch (e) { } }

        sendMessage(p, WL_MSG_USE);                              // 「冲锋！」
        try { p.getWorld().playSound(p.getLocation(), 'entity.horse.gallop', 1.5, 1.0); } catch (e) { }
    } catch (e) { }
}

// 蓄力期间：钉在地上不动 + 一圈往内收的粒子（提示"在蓄"）
function wlChargeFx(p, st) {
    try {
        var Particle = null;
        try {
            var P = Java.type('org.bukkit.Particle');
            try { Particle = P.CRIT; } catch (e) { Particle = null; }
            if (Particle == null) { try { Particle = P.valueOf('CRIT'); } catch (e) { } }
        } catch (e) { Particle = null; }
        if (Particle == null) return;

        var world = p.getWorld();
        var l = p.getLocation();
        var tk = (st.fxTick == null ? 0 : st.fxTick) + 1;
        st.fxTick = tk;

        var n = 10;
        var r = 1.6 - (tk % 8) * 0.15;
        if (r < 0.5) r = 0.5;
        for (var i = 0; i < n; i++) {
            var a = (Math.PI * 2.0 * i) / n + tk * 0.25;
            var h = 0.2 + ((tk + i * 2) % 12) * 0.13;
            try {
                world.spawnParticle(Particle,
                    l.getX() + Math.cos(a) * r, l.getY() + h, l.getZ() + Math.sin(a) * r,
                    1, 0.0, 0.0, 0.0, 0.0);
            } catch (e) { }
        }
    } catch (e) { }
}

function gsWuliStartDash(p, st) {
    try {
        if (st == null || st.dead) return;

        var yaw = 0;
        try { yaw = st.lead.getLocation().getYaw(); } catch (e) { yaw = st.anchor.yaw; }
        st.heading = yaw;

        // ★ 锁定冲刺期间的视角：方向 = 松手那一刻的朝向（yaw），俯仰 = 当时的 pitch
        st.lockYaw = yaw;
        try { st.lockPitch = p.getLocation().getPitch(); } catch (e) { st.lockPitch = 0.0; }

        var rad = yaw * Math.PI / 180.0;
        st.dirX = -Math.sin(rad);      // MC：yaw 0 = +Z，90 = -X
        st.dirZ = Math.cos(rad);

        st.dashX0 = st.anchor.x;
        st.dashY0 = st.anchor.y;
        st.dashZ0 = st.anchor.z;
        st.dashI = 0;
        st.hit = {};
        st.mode = 'dash';

        // 蓄力结束 → 无敌按配置决定去留（默认 WL_DASH_INVULN = false ⇒ 这里解除）
        try { wlSetInvuln(p, !!WL_DASH_INVULN); } catch (e) { }

        try { p.getWorld().playSound(p.getLocation(), 'entity.horse.gallop', 2.5, 0.8); } catch (e) { }
        wlDashTick(p, st);
    } catch (e) { }
}

// 取第一个存在的粒子类型（版本改名不至于失效）
function wlFxHorseType(chain) {
    try {
        var P = Java.type('org.bukkit.Particle');
        var list = (chain == null) ? WL_FXHORSE_PARTICLES : chain;
        for (var i = 0; i < list.length; i++) {
            var n = '' + list[i];
            try { var t = P[n]; if (t != null) return t; } catch (e) { }
            try { var t2 = P.valueOf(n); if (t2 != null) return t2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ★ 一匹"抬起前蹄"的马 的骨架线段表：每项 = [u1, v1, u2, v2]
//   u = 沿玩家**右手方向**的偏移（马头朝 +u），v = 相对马底的**向上**高度
//   画在竖直平面上 ⇒ 玩家抬头看到的是标准侧影
function wlFxHorseSegs() {
    return [
        // ① 后腿（两条，站在地上）
        [-0.52, 0.00, -0.34, 0.46],
        [-0.40, 0.00, -0.24, 0.44],
        // ② 臀 → 背 → 鬐甲（微微上扬的背线）
        [-0.34, 0.46, -0.30, 0.72],
        [-0.30, 0.72, 0.06, 0.94],
        [0.06, 0.94, 0.40, 1.12],
        // ③ 肚子
        [-0.30, 0.60, 0.06, 0.66],
        [0.06, 0.66, 0.36, 0.86],
        // ④ 脖子（斜向上）
        [0.40, 1.12, 0.66, 1.42],
        // ⑤ 头 + 嘴
        [0.66, 1.42, 0.92, 1.50],
        [0.92, 1.50, 1.06, 1.44],
        // ⑥ 耳朵
        [0.70, 1.46, 0.72, 1.60],
        // ⑦ ★ 前腿（两条，**抬起来**、蹄朝上）
        [0.36, 1.02, 0.56, 0.88],
        [0.56, 0.88, 0.66, 1.14],
        [0.66, 1.14, 0.78, 1.38],
        [0.28, 0.96, 0.46, 0.82],
        [0.46, 0.82, 0.56, 1.06],
        // ⑧ 尾巴（垂下来再翘一点）
        [-0.30, 0.72, -0.58, 0.54],
        [-0.58, 0.54, -0.74, 0.30],
        // ⑨ 鬃毛（几道短线）
        [0.42, 1.16, 0.34, 1.26],
        [0.54, 1.30, 0.46, 1.40]
    ];
}

// ★ 在玩家**头顶 2 格**画一匹抬起前蹄的粒子马
//   ★ 只在**蓄力**期间画（挂在 charge 分支），冲刺一开始就消失
function wlFxHorseFx(world, x, y, z, yaw) {
    try {
        if (!WL_FXHORSE_ON) return;
        var pt = wlFxHorseType(null);
        if (pt == null) return;

        var rad = yaw * Math.PI / 180.0;
        var fx = -Math.sin(rad), fz = Math.cos(rad);        // 朝向（MC：yaw 0 = +Z）
        var rx = -Math.cos(rad), rz = -Math.sin(rad);       // 右手方向
        var S = WL_FXHORSE_STEP;

        // 马底 = 玩家脚底 + Y 偏移，中心在**前方** WL_FXHORSE_FRONT
        var bx = x + fx * WL_FXHORSE_FRONT;
        var by = y + WL_FXHORSE_Y;
        var bz = z + fz * WL_FXHORSE_FRONT;

        var put = function (u, v) {
            try { world.spawnParticle(pt, bx + rx * u, by + v, bz + rz * u, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
        };

        // ① 骨架：每条线段按 WL_FXHORSE_STEP 均匀采样
        var segs = wlFxHorseSegs();
        for (var s = 0; s < segs.length; s++) {
            var g = segs[s];
            var du = g[2] - g[0], dv = g[3] - g[1];
            var len = Math.sqrt(du * du + dv * dv);
            var n = Math.round(len / S);
            if (n < 1) n = 1;
            for (var i = 0; i <= n; i++) {
                var t = i / n;
                put(g[0] + du * t, g[1] + dv * t);
            }
        }

        // ② 眼睛：换一种粒子，让马看起来是"活"的
        var ep = wlFxHorseType(WL_FXHORSE_EYE);
        if (ep != null) {
            try { world.spawnParticle(ep, bx + rx * 0.82, by + 1.52, bz + rz * 0.82, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
        }
    } catch (e) { }
}

// ★ 冲刺期间的一道剑气：朝向 = 冲刺方向（水平），**不经过左键冷却**
function wlDashQi(p, st) {
    try {
        var eye = p.getEyeLocation();
        var Vector = Java.type('org.bukkit.util.Vector');
        var dir = new Vector(st.dirX, 0.0, st.dirZ);
        try { dir = dir.normalize(); } catch (e) { }

        if (WL_DASH_QI_SOUND) {
            try {
                var w = p.getWorld();
                w.playSound(eye, WL_QI_SND1, WL_QI_SND1_VOL * 0.8, WL_QI_SND1_PIT);
                w.playSound(eye, WL_QI_SND2, WL_QI_SND2_VOL * 0.8, WL_QI_SND2_PIT);
            } catch (e) { }
        }

        var base = { x: eye.getX(), y: eye.getY() - 0.35, z: eye.getZ() };
        wlQiStep(p, base, dir, wlAxes(dir), 0.5, {}, WL_DASH_QI_KB_MUL);   // ★ 0 ⇒ 无击退
    } catch (e) { }
}

function wlDashFx(world, x, y, z, st) {
    try {
        var Particle = null;
        try {
            var P = Java.type('org.bukkit.Particle');
            try { Particle = P.CLOUD; } catch (e) { Particle = null; }
            if (Particle == null) { try { Particle = P.valueOf('CLOUD'); } catch (e) { } }
        } catch (e) { Particle = null; }
        if (Particle == null) return;
        for (var i = -2; i <= 2; i++) {
            try {
                world.spawnParticle(Particle,
                    x + i * 1.0, y + 0.15, z + i * 1.0, 2, 0.2, 0.1, 0.2, 0.02);
            } catch (e) { }
        }
    } catch (e) { }
}

// 冲刺：1 刻一步（共 9 刻 ≈ 0.45 秒），整队一起往前推 → 看起来是"冲"而不是"瞬移"
// ★ 冲锋走到这一步时脚该落在哪个高度？（）
//   ① 脚下 + 头顶 都空 ⇒ 平着走
//   ② 抬高 WL_DASH_STEP_UP 格后站得住 ⇒ 当台阶踩上去继续冲
//   ③ 都不行（= 障碍 ≥ 2 格）⇒ 返回 **null** 表示撞墙
function wlDashStepY(world, x, y, z) {
    try {
        var bx = Math.floor(x), bz = Math.floor(z), by = Math.floor(y);
        if (!gsWlBlockedAt(world, bx, by, bz) && !gsWlBlockedAt(world, bx, by + 1, bz)) return y;
        for (var up = 1; up <= WL_DASH_STEP_UP; up++) {
            var ok = true;
            for (var c = 0; c <= 1; c++) {
                if (gsWlBlockedAt(world, bx, by + up + c, bz)) { ok = false; break; }
            }
            if (ok) return by + up;
        }
        return null;
    } catch (e) { return y; }
}

function wlDashTick(p, st) {
    try {
        if (st == null || st.dead) return;
        if (st.mode !== 'dash') return;

        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

        var i = st.dashI;
        var k = (i + 1) / WL_DASH_TICKS;
        var x = st.dashX0 + st.dirX * WL_DASH_DIST * k;
        var z = st.dashZ0 + st.dirZ * WL_DASH_DIST * k;
        var y = st.dashY0;

 // ★ 撞墙规则（）：1 格 = 上台阶继续冲；≥2 格 = **停在墙前，冲锋结束**
        var stepY = wlDashStepY(world, x, y, z);
        if (stepY == null) {
            st.mode = 'follow';
            try { wlSetInvuln(p, false); } catch (e0) { }
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

        // ① 队长马（玩家）往前推（冲刺开始 = 前蹄落下）
        try {
            var Loc = Java.type('org.bukkit.Location');
            var loc = new Loc(world, x, y, z, st.heading, 0.0);
            st.lead.teleport(loc);
            var Vector = Java.type('org.bukkit.util.Vector');
            st.lead.setVelocity(new Vector(0.0, 0.0, 0.0));
            wlSetRear(st.lead, false);
        } catch (e) { }

        // ② 护航马跟队形（冲刺开始 = 前蹄落下，pitch 回 0）
        wlFollowFormation(st, world, x, y, z, st.heading, 0.0, false);

        // ②′ 锁视角（默认关闭）：想让冲刺这 2 秒不能转头 → WL_DASH_LOCK_LOOK 改回 true
        //     关着的时候这里直接 return，一行 teleport 都不会发 ⇒ 画面不会顿
        wlLockLook(p, st, st.lockYaw, st.lockPitch);

        // ③ 路径伤害：宽 5 格（左右各 2.5）、前后各 2 格
        var r = WL_DASH_HALF_W + 2.0;
        var list = null;
        try {
            var LocQ = Java.type('org.bukkit.Location');
            list = world.getNearbyEntities(new LocQ(world, x, y, z), r, WL_DASH_HALF_H + 1.0, r);
        } catch (e) { list = null; }

        if (list != null) {
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (ent == null) continue;
                var eid = '';
                try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                if (st.hit[eid] === 1) continue;              // 一次冲锋每个目标只吃一次
                if (wlNotTarget(p, ent)) continue;

                var relx = 0, rely = 0, relz = 0;
                try {
                    var el = ent.getLocation();
                    relx = el.getX() - x;
                    rely = (el.getY() + 0.9) - y;
                    relz = el.getZ() - z;
                } catch (e) { continue; }

                var along = relx * st.dirX + relz * st.dirZ;
                var lat = relx * st.dirZ - relz * st.dirX;
                if (Math.abs(lat) > WL_DASH_HALF_W) continue;
                if (along > 2.0 || along < -2.0) continue;
                if (Math.abs(rely) > WL_DASH_HALF_H) continue;

                st.hit[eid] = 1;
                wlHurt(p, ent, WL_DASH_DMG);                  // 15 点
            }
        }

        // ④ 冲刺拖尾
        wlDashFx(world, x, y, z, st);

        // ⑤ ★ 冲刺期间自动斩 4 道剑气（不用按左键），朝向 = 冲刺方向
        //    （头顶的粒子马**不在这里画** —— 它只在蓄力那 1 秒出现，冲刺一开始就消失）
        if (WL_DASH_QI_ON && WL_DASH_QI_AT != null) {
            for (var q = 0; q < WL_DASH_QI_AT.length; q++) {
                if (WL_DASH_QI_AT[q] === i) { wlDashQi(p, st); break; }
            }
        }

        st.dashI = i + 1;
        // 冲完 → 回到跟随，并**无条件解除无敌**（万一 WL_DASH_INVULN 开着也要在这里收掉）
        if (st.dashI >= WL_DASH_TICKS) {
            st.mode = 'follow';
            try { wlSetInvuln(p, false); } catch (e0) { }
            return;
        }
        runLater(function () { wlDashTick(p, st); }, 1);
    } catch (e) {
        try {
            if (st != null) st.mode = 'follow';
            wlSetInvuln(p, false);        // 异常退出也要收掉，绝不留"永久免伤"
        } catch (e2) { }
    }
}

// ---------------- 常驻：骑阵跟随 / 条件检查 ----------------
function gsWuliTickPlayer(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        var st = GS_WULI_STATE[uuid];
        if (st == null) return;

        if (st.dead) { delete GS_WULI_STATE[uuid]; return; }

        // 玩家死了 → 交给 onPlayerDeath 收场
        try { if (p.isDead()) return; } catch (e) { }

        var inv = null;
        try { inv = p.getInventory(); } catch (e) { return; }
        var main = null, off = null;
        try { main = inv.getItemInMainHand(); } catch (e) { }
        try { off = inv.getItemInOffHand(); } catch (e) { }

        // ★★ 主手不再是五骊矟 / 副手不再是五骊鞚 → 马与拴绳散场（也播化烟动画）
        if (!gsIsItem(main, GS_WULI_ID) || !gsWuliIsRein(off)) {
            gsWuliDismiss(p, st, true, true);
            delete GS_WULI_STATE[uuid];
            return;
        }

        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

        // 队长马：丢了补一匹、换世界跟着换
        var leadBad = (st.lead == null);
        if (!leadBad) { try { leadBad = st.lead.isDead() || !st.lead.isValid(); } catch (e) { leadBad = true; } }
        if (leadBad) {
            var l0 = p.getLocation();
            st.lead = wlSpawnHorse(world, l0.getX(), l0.getY(), l0.getZ(), l0.getYaw(), true);
            try { wlAlignMount(st.lead, p); } catch (e) { }
        } else {
            try {
                var lw = null;
                try { lw = '' + st.lead.getWorld().getName(); } catch (e) { lw = null; }
                var pw = '' + world.getName();
                if (lw != null && lw !== pw) st.lead.teleport(p.getLocation());
            } catch (e) { }
        }

        // 护航马：缺了就补
        for (var i = 0; i < WL_FORM.length; i++) {
            var h = st.escorts[i];
            var ok = (h != null);
            if (ok) { try { ok = !(h.isDead() || !h.isValid()); } catch (e) { ok = false; } }
            if (!ok) {
                var l1 = p.getLocation();
                st.escorts[i] = wlSpawnHorse(world, l1.getX(), l1.getY(), l1.getZ(), l1.getYaw(), false);
            }
        }

        // 骑上队长马（被弄下来了就再骑上去 —— F / 换掉金剑才是唯一的"下车"方式）
        try {
            var veh = p.getVehicle();
            var same = false;
            if (veh != null && st.lead != null) {
                try { same = ('' + veh.getUniqueId()) === ('' + st.lead.getUniqueId()); } catch (e) { }
            }
            if (!same && st.lead != null) {
                try { p.leaveVehicle(); } catch (e) { try { p.eject(); } catch (e2) { } }
                // ★ 重新扶上马前先对齐坐骑朝向（否则镜头会被掰）
                try { wlAlignMount(st.lead, p); } catch (e) { }
                try { st.lead.addPassenger(p); } catch (e) { }
            }
        } catch (e) { }

        if (st.lead == null) return;

        // ---- 分模式 ----
        if (st.mode === 'charge') {
            // 蓄力：钉在原地（只允许转向，所以每拍把马的朝向刷成玩家视角）
            var pyaw = 0;
            try { pyaw = p.getLocation().getYaw(); } catch (e) { pyaw = st.anchor.yaw; }
            st.heading = pyaw;
            try {
                // ★ 蓄力的那 1 秒：五匹马全部"前蹄抬起"（把 pitch 仰起来，
                //   马会以后腿为支点把前身抬起）—— 冲刺那一刻再落回 0
                var LocC = Java.type('org.bukkit.Location');
                st.lead.teleport(new LocC(world, st.anchor.x, st.anchor.y, st.anchor.z, pyaw, WL_REAR_PITCH));
                var VectorC = Java.type('org.bukkit.util.Vector');
                st.lead.setVelocity(new VectorC(0.0, 0.0, 0.0));
                wlSetRear(st.lead, true);           // ★ 队长马也一起抬蹄
            } catch (e) { }
            wlFollowFormation(st, world, st.anchor.x, st.anchor.y, st.anchor.z, pyaw, WL_REAR_PITCH, true);
            wlChargeFx(p, st);
            // ★ 蓄力期间：**头顶 2 格**浮出一匹**抬起前蹄**的粒子马（冲刺一开始就没了）
            if (WL_FXHORSE_ON && (st.chargeFx % WL_FXHORSE_EVERY) === 0) {
                try {
                    var bl2 = p.getLocation();
                    wlFxHorseFx(world, bl2.getX(), bl2.getY(), bl2.getZ(), bl2.getYaw());
                } catch (e) { }
            }
            st.chargeFx = (st.chargeFx || 0) + 1;
            if (gsWuliNow() >= st.chargeEnd) gsWuliStartDash(p, st);
            return;
        }
        if (st.mode === 'dash') return;        // 冲刺由 1 刻链自己推进

        // 跟随：以队长马的位置 + 朝向摆 ^ 队形（平时四蹄落地 → pitch 0 / rear false）
        var ll = null;
        try { ll = st.lead.getLocation(); } catch (e) { ll = null; }
        if (ll != null) {
            wlSetRear(st.lead, false);
            wlFollowFormation(st, world, ll.getX(), ll.getY(), ll.getZ(), ll.getYaw(), 0.0, false);
        }
    } catch (e) { }
}

function gsWuliPulse() {
    try {
        if (!gsAuraIsCurrent()) return;        // /rsc reload 后的旧任务 → 停手
        // 惰性重试：附属加载期插件可能还没就绪，这里补注册（装好了就是一次布尔判断）
        if (!GS_WULI_REGISTERED) gsWuliRegisterSync();
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsWuliTickPlayer(p); } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 同步监听器注册 ----------------
function gsWuliRegisterSync() {
    if (GS_WULI_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        // 手上是不是「五骊矟」+ 副手是不是「五骊鞚」（= 已开机）
        //   ★ 注意：EntityDamageByEntityEvent 没有 getPlayer()，所以这里统一收玩家参数
        function armedPlayer(p) {
            try {
                if (p == null) return null;
                var inv = p.getInventory();
                if (!gsIsItem(inv.getItemInMainHand(), GS_WULI_ID)) return null;
                if (!gsWuliIsRein(inv.getItemInOffHand())) return null;
                return p;
            } catch (e) { return null; }
        }

        // ① F 键（换手事件）= 开机 / 关机
        var SwapEv = null;
        try { SwapEv = Java.type('org.bukkit.event.player.PlayerSwapHandItemsEvent'); } catch (e) { }
        if (SwapEv != null) {
            reg(SwapEv, function (e) {
                try {
                    if (e.isCancelled()) return;      // 重复注册的实例 → 跳过
                    var p = e.getPlayer();
                    var inv = p.getInventory();
                    // 主手是五骊矟 → 拦下原版换手，改走开机 / 关机
                    if (gsIsItem(inv.getItemInMainHand(), GS_WULI_ID)) {
                        e.setCancelled(true);
                        gsWuliToggle(p);
                        return;
                    }
                    // 没拿五骊矟时：副手那根「五骊鞚」也不许被换走
                    if (gsWuliIsRein(inv.getItemInOffHand())) e.setCancelled(true);
                } catch (e3) { }
            });
        }

        // ② 鼠标左键 = 剑气；右键 = 冲锋
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = armedPlayer(e.getPlayer());
                    if (p == null) return;

                    // 只认主手（副手也会派发同一个事件）
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的事件），
                        //    但玩家真主手拿着五骊矟 → 这是本附属自己派发的假事件，放过，
                        //    否则机器每 0.5 秒就会替旁边的人误触一次「冲锋」。
                        //    真人右键时 event.getItem() 一定就是手里那件五骊矟，不会误伤。
 // ★ 这条"物品是空气就放过"的判据**只对右键生效** ——
                        //      左键本来就可能拿到 null / 空气（对着方块左键时不一定带物品），
                        //      挂在最前面会把整条左键路径吞掉。
                        try {
                            var evItem = e.getItem();
                            var evName = (evItem == null) ? '' : ('' + evItem.getType().name());
                            if (evName.length === 0 || evName === 'AIR') return;
                        } catch (e2i) { }

                        e.setCancelled(true);         // 拦住原版右键（放方块 / 交互）
                        gsWuliTryCharge(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
 // ★★ **左键点到方块 → 改成"不对区块做任何交互"**。
                        //    原因：对着方块按左键时，客户端只在**开始挖掘**那一下发
                        //    LEFT_CLICK_BLOCK，之后按住不再发 ⇒ 剑气时有时无；
                        //    而且会误挖方块、出挖掘进度条。
                        //    现在：LEFT_CLICK_BLOCK 直接取消（不挖 / 不交互），
                        //    剑气另由下面的 PlayerAnimationEvent（挥手包）兜底，
                        //    不再依赖事件到底报的是 AIR 还是 BLOCK。
                        if (WL_QI_NO_BLOCK && act.indexOf('BLOCK') >= 0) {
                            e.setCancelled(true);
                            try {
                                var Res = Java.type('org.bukkit.event.Event$Result');
                                e.setUseInteractedBlock(Res.DENY);
                                e.setUseItemInHand(Res.DENY);
                            } catch (eR) { }
                        }
                        gsWuliTryQi(p);
                    }
                } catch (e3) { }
            });
        }

        // ②-b ★★ 左键的**真正兜底**：挥手动画（PlayerAnimationEvent）
        //   客户端**每次左键都会发一次挥手包**（不管准星对着空气还是方块），
        //   所以这条能保证"剑气永远按得出来"。
        //   与上面的 LEFT_CLICK_AIR/BLOCK 同时命中也没关系：gsWuliTryQi 会先落
        //   0.5 秒冷却，后到的那次直接静默返回（天然去重，不会双发）。
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var type = '';
                    try { type = '' + e.getAnimationType().name(); } catch (e2) { type = ''; }
                    if (type !== 'ARM_SWING') return;      // 只认主手挥手（OFF_ARM_SWING 是 F 换手的副手挥）
                    var pa = armedPlayer(e.getPlayer());
                    if (pa == null) return;
                    gsWuliTryQi(pa);
                } catch (e3) { }
            });
        }

        // ②-c ★★ 手持五骊矟时**彻底不碰方块**（挖不动、也不出挖掘进度条）
        //   左键只用来斩剑气；右键上面已经整体拦掉了。
        var BrkEv = null;
        try { BrkEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
        if (BrkEv != null) {
            reg(BrkEv, function (e) {
                try {
                    if (WL_QI_NO_BLOCK && armedPlayer(e.getPlayer()) != null) e.setCancelled(true);
                } catch (e3) { }
            });
        }
        var DmgBlkEv = null;
        try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
        if (DmgBlkEv != null) {
            reg(DmgBlkEv, function (e) {
                try {
                    if (WL_QI_NO_BLOCK && armedPlayer(e.getPlayer()) != null) e.setCancelled(true);
                } catch (e3) { }
            });
        }

        // ③ 左键打到生物（近战）→ 取消近战，改成斩出剑气
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { }
        if (DmgEv != null) {
            reg(DmgEv, function (e) {
                try {
                    if (e.isCancelled()) return;
                    var d = null;
                    try { d = e.getDamager(); } catch (e2) { d = null; }
                    if (d == null) return;
                    var dn = '';
                    try { dn = '' + d.getType().name(); } catch (e2) { return; }
                    if (dn !== 'PLAYER') return;
                    var p = armedPlayer(d);           // 近战事件没有 getPlayer()，直接拿 damager
                    if (p == null) return;
                    // ★ 脚本自己结算的伤害（剑气 / 冲锋 / 护腿光环）→ 放行，别当成近战取消
                    if (GS_WULI_DAMAGING['' + p.getUniqueId()] === true) return;
                    e.setCancelled(true);
                    gsWuliTryQi(p);
                } catch (e3) { }
            });
        }

        // ④ 右击生物 → 拦住（副手拿着拴绳时，原版的"绳套"会把马拴上）
        //    ★ PlayerInteractAtEntityEvent（盔甲架等）是它的子类 → 一起被拦
 // ★ 改动 改成**主副手任一拿着五骊鞚**就取消，
        //      不再只看副手 —— 万一它被挪到主手也一样栓不了东西。
        var EntEv = null;
        try { EntEv = Java.type('org.bukkit.event.player.PlayerInteractEntityEvent'); } catch (e) { }
        if (EntEv != null) {
            reg(EntEv, function (e) {
                try {
                    var p = e.getPlayer();
                    var inv = p.getInventory();
                    if (gsWuliIsRein(inv.getItemInOffHand()) || gsWuliIsRein(inv.getItemInMainHand())) {
                        e.setCancelled(true);
                    }
                } catch (e3) { }
            });
        }

        // ④-b 拴绳拴到栅栏：走的是另一套事件（PlayerLeashEntityEvent），单独拦
        //      （有的版本只在"把绳子系到栅栏"这一步才发它）
        var LeashEv = null;
        try { LeashEv = Java.type('org.bukkit.event.entity.PlayerLeashEntityEvent'); } catch (e) { }
        if (LeashEv != null) {
            reg(LeashEv, function (e) {
                try {
                    var p = e.getPlayer();
                    var inv = p.getInventory();
                    if (gsWuliIsRein(inv.getItemInOffHand()) || gsWuliIsRein(inv.getItemInMainHand())) {
                        e.setCancelled(true);
                    }
                } catch (e3) { }
            });
        }

        // ⑤ 「五骊鞚」不可以丢掉 / 不可以被移动（丢弃 / 点击 / 拖拽）
        var DropEv = null;
        try { DropEv = Java.type('org.bukkit.event.player.PlayerDropItemEvent'); } catch (e) { }
        if (DropEv != null) {
            reg(DropEv, function (e) {
                try {
                    var dropped = null;
                    try { dropped = e.getItemDrop().getItemStack(); } catch (e2) { }
                    if (gsWuliIsRein(dropped)) e.setCancelled(true);
                } catch (e3) { }
            });
        }
        var ClickEv = null;
        try { ClickEv = Java.type('org.bukkit.event.inventory.InventoryClickEvent'); } catch (e) { }
        if (ClickEv != null) {
            reg(ClickEv, function (e) {
                try {
                    if (gsWuliIsRein(e.getCurrentItem()) || gsWuliIsRein(e.getCursor())) e.setCancelled(true);
                } catch (e3) { }
            });
        }
        var DragEv = null;
        try { DragEv = Java.type('org.bukkit.event.inventory.InventoryDragEvent'); } catch (e) { }
        if (DragEv != null) {
            reg(DragEv, function (e) {
                try {
                    var items = null;
                    try { items = e.getNewItems(); } catch (e2) { items = null; }
                    if (items == null) return;
                    var it = items.values().iterator();
                    while (it.hasNext()) {
                        if (gsWuliIsRein(it.next())) { e.setCancelled(true); return; }
                    }
                } catch (e3) { }
            });
        }

        GS_WULI_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 五骊矟监听器注册成功（F=上马/收阵 · 左键=剑气 · 右键=冲锋）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 五骊矟同步监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsWuliStart() {
    try {
        runRepeating(function () { gsWuliPulse(); }, WL_PULSE, WL_PULSE);
        gsWuliRegisterSync();
        gsDaLog('五骊矟：五骊骑阵已启用（F = 上马/收阵；左键剑气 7 点·'
            + (WL_QI_CD_MS / 1000.0) + ' 秒·静默·龙吟+传送音；'
            + '右键冲锋 15 点·蓄力 ' + (WL_CHARGE_MS / 1000.0) + ' 秒（无敌）·冲刺 ' + WL_DASH_DIST
            + ' 格（' + (WL_DASH_TICKS / 20.0) + ' 秒动画，整段 '
            + ((WL_CHARGE_MS / 1000.0) + (WL_DASH_TICKS / 20.0)).toFixed(1) + ' 秒）·冷却 '
            + (WL_DASH_CD_MS / 1000.0) + ' 秒）');
        gsDaLog('五骊矟：坐骑移速下限 ' + WL_HORSE_SPEED + '（≈ 12 格/秒；只升不降）');
        gsDaLog('五骊矟：蓄力无敌 = ' + WL_CHARGE_INVULN + ' · 冲刺无敌 = ' + WL_DASH_INVULN
            + '（收阵/下线/死亡/冲刺结束都会无条件解除）');
        gsDaLog('五骊矟：开机动画 = **正前方** ' + WL_RIDE_DIST + ' 格冲出一匹骷髅马，'
            + (WL_RIDE_TICKS / 20.0) + ' 秒内撞到玩家（' + WL_RIDE_HIT_R
            + ' 格内判定）→ 副手出现「五骊鞚」+ 扶上马；坐骑朝向已对齐玩家视角，不会掰镜头');
        gsDaLog('五骊矟：下马动画 = 4 匹护航马化烟 + 队长马朝前方 ' + WL_FLEE_DIST
            + ' 格奔出后化烟（' + (WL_FLEE_TICKS / 20.0) + ' 秒）；玩家全程可自由移动');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 五骊矟启动失败: ' + e); } catch (e2) { }
    }
}

gsWuliStart();

// ==========================================================================
// 威龙（铁锭 · GS_WEILONG）——「磁吸炸弹 / 虎蹲炮 / 动力推进」
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §7
// ==========================================================================

var GS_WL_ID = 'GS_WEILONG';        // 威龙（铁锭）

// ---------------- 磁吸炸弹（左键）----------------
var WL_BOMB_DIST       = 7.0;       // 抛出距离：正视前方 7 格
var WL_BOMB_FLY_TICKS  = 14;        // 飞行 14 刻（每刻约 0.5 格 ⇒ 吸附判定不漏）
var WL_BOMB_FUSE_TICKS = 60;        // 落地 / 吸附后 3 秒 = 60 刻
var WL_BOMB_R          = 3.0;       // 伤害半径 3 格
var WL_BOMB_DMG        = 35.0;      // 35 点伤害
var WL_BOMB_CD_MS      = 15000;     // 冷却 15 秒
var WL_BOMB_MAGNET_R   = 1.5;       // 吸附半径 1.5 格
var WL_BOMB_MSG        = '&a磁吸炸弹投出！';
var WL_BOMB_MSG_CD     = '&7磁吸炸弹CD &c{s}秒';      // ★ {s} 会自动替换成**剩余冷却秒数**（1 位小数）
var WL_BOMB_SNDS_THROW = ['entity.player.levelup', 'entity.experience_orb.pickup']; // 「获得经验」音
var WL_BOOM_SNDS       = ['entity.generic.explode'];                                 // TNT 爆炸音
var WL_BOOM_PARTICLES  = ['EXPLOSION_EMITTER', 'EXPLOSION_LARGE', 'EXPLOSION_HUGE', 'SMOKE_LARGE'];

// ---------------- 虎蹲炮（右键）----------------
var WL_CANNON_DIST       = 12.0;    // 抛出距离：前方 12 格
var WL_CANNON_FLY_TICKS  = 18;      // 飞行 18 刻
var WL_CANNON_FUSE_TICKS = 40;      // 落地后 2 秒 = 40 刻
var WL_CANNON_R          = 3.0;     // 伤害半径 3 格
var WL_CANNON_DMG        = 1.0;     // 1 点伤害
var WL_CANNON_KB         = 0.5;     // 击退力度（≈ 1 格）
var WL_CANNON_KB_UP      = 0.18;    // 稍微挑一点，免得贴地卡住
var WL_CANNON_STUN_MS    = 4500;    // 定身 4.5 秒
var WL_CANNON_STUN_LOCK  = 250;     // 先让它被击退飞 0.25 秒，**之后**再定住（两个效果都看得见）
var WL_CANNON_CD_MS      = 25000;   // 冷却 25 秒
var WL_CANNON_MSG_HIT    = '&b目标失衡！';
var WL_CANNON_MSG_CD     = '&7虎蹲炮CD &c{s}秒';
var WL_CANNON_SNDS_THROW = ['entity.wind_charge.throw', 'entity.wind_charge.wind_burst', 'entity.blaze.shoot'];
var WL_WIND_SNDS         = ['entity.wind_charge.wind_burst', 'entity.blaze.shoot'];
var WL_WIND_PARTICLES    = ['WIND_BURST', 'GUST', 'GUST_EMITTER', 'CLOUD', 'SMOKE_NORMAL'];

// ---------------- 动力推进（F 键）----------------
var WL_DASH2_DIST    = 12.0;        // 冲刺 12 格
var WL_DASH2_TICKS   = 10;          // 10 刻走完（0.5 秒），**没有蓄力阶段**
var WL_DASH2_STEP    = 0.4;         // 逐段碰撞检测的步长（★ 12 格一步跳过去会穿墙，必须拆成小步）
var WL_DASH2_FLAT    = true;        // ★ 冲刺**只走水平方向**（东南西北）：抬头 / 低头都不会往上下冲
var WL_DASH2_AXIS    = false;       // true = 再吸附到东 / 南 / 西 / 北四个**正方向**之一（想格子化就开）
var WL_DASH2_HOP     = 0.9;         // 起跳弧线高度（落回原高度）
var WL_DASH2_CD_MS   = 8000;        // 冷却 8 秒（击杀生物时清空）
var WL_DASH2_MSG     = '&b动力推进';
var WL_DASH2_MSG_CD  = '&7动力推进CD &c{s}秒';
var WL_DASH2_SNDS    = ['entity.wind_charge.wind_burst', 'entity.blaze.shoot'];
var WL_SPIT_PARTICLES = ['SPIT', 'SMOKE_NORMAL', 'CLOUD'];   // 肩部持续冒的羊驼口水

// ---------------- 加速 II（左键 / 右键 / 冲刺落地后）----------------
var WL_SPEED_TICKS = 60;            // 3 秒
var WL_SPEED_AMP   = 1;             // amplifier 1 = 速度 II
var WL_SPEED_CD_MS = 5000;          // 拿到药效那一刻开始计时，5 秒
var WL_SPEED_SNDS  = ['entity.blaze.shoot'];

var WL_HIT_PLAYERS = false;         // 炸弹要不要也打玩家（默认**不打**，防误伤队友）
var WL_NO_BREAK    = true;          // ★ 手持威龙时**无法破坏方块**（左键只用来扔炸弹，不会误挖）
var WL_BLOCK_STEP  = 0.25;          // TNT 飞行轨迹的碰撞检测步长（★ 防穿玻璃 / 穿墙）

// ---------------- 运行时状态 ----------------
var GS_WL_CD_KEYS   = null;
var GS_WL_BOMB_KEY  = null;         // TNT 身上的标记
var GS_WL_REGISTERED = false;
var GS_WL_DAMAGING  = {};           // uuid -> true（这一下是本脚本结算的 ⇒ 近战拦截放行）
var GS_WL_BOMBS     = {};           // id -> 炸弹状态
var GS_WL_BOMB_SEQ  = 0;
var GS_WL_STUN      = {};           // 实体 uuid -> { ent, loc, until, lockAt, ai }
var GS_WL_CLICK_AT  = {};           // uuid|L / uuid|R -> 上次处理的毫秒（★ /rsc reload 会重复注册监听器，
                                    //   不去抖的话一次点击会被处理 N 遍 —— 第二遍只会白送一句"CD"提示）

function gsWlKeys() {
    if (GS_WL_CD_KEYS != null) return GS_WL_CD_KEYS;
    try {
        var NK = Java.type('org.bukkit.NamespacedKey');
        GS_WL_CD_KEYS = {
            bomb: NK.fromString('gs:wl_bomb_cd'),
            cannon: NK.fromString('gs:wl_cannon_cd'),
            dash: NK.fromString('gs:wl_dash_cd'),
            speed: NK.fromString('gs:wl_speed_cd')
        };
        GS_WL_BOMB_KEY = NK.fromString('gs:wl_tnt');
    } catch (e) {
        GS_WL_CD_KEYS = { bomb: null, cannon: null, dash: null, speed: null };
    }
    return GS_WL_CD_KEYS;
}

function gsWlIsWeilong(item) { return gsIsItem(item, GS_WL_ID); }

function gsWlCdLeft(p, kind, cdMs) {
    try { return gsWuliCdLeft(p, gsWlKeys()[kind], cdMs); } catch (e) { return 0; }
}
function gsWlMarkCd(p, kind) {
    try { gsWuliMarkCd(p, gsWlKeys()[kind]); } catch (e) { }
}
function gsWlClearCd(p, kind) {
    try {
        var k = gsWlKeys()[kind];
        if (k == null) return;
        var pdc = p.getPersistentDataContainer();
        if (pdc != null) pdc.remove(k);
    } catch (e) { }
}

// ★ 一次点击只处理一遍（同一事件被重复注册的监听器实例各收到一份）
function gsWlClickOnce(p, tag) {
    try {
        var now = gsWuliNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_WL_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_WL_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

// ★ 把「{s}」替换成剩余冷却秒数（保留 1 位小数）
function gsWlCdMsg(base, leftMs) {
    try {
        var s = (0 + leftMs) / 1000.0;
        if (s < 0) s = 0;
        var txt = '' + (Math.round(s * 10) / 10);
        if (txt.indexOf('.') < 0) txt = txt + '.0';
        return ('' + base).replace('{s}', txt);
    } catch (e) { return '' + base; }
}

// ★ 刚刚（gapMs 内）是不是右键过？—— 右键放方块时客户端也会顺手发一次挥手动画，
//   不屏蔽的话会被下面的 PlayerAnimationEvent 误判成"左键 ⇒ 磁吸炸弹"
function gsWlRecentRight(p, gapMs) {
    try {
        var last = GS_WL_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsWuliNow() - last) < gapMs;
    } catch (e) { return false; }
}

function gsWlMsg(p, msg) {
    try {
        p.sendMessage(Java.type('org.bukkit.ChatColor')
            .translateAlternateColorCodes('&', '' + msg));
    } catch (e) { }
}

// 音效：一串候选名，第一个能播的就播（老版本 / 改名都不至于哑掉）
function gsWlPlay(world, loc, names, vol, pit) {
    try {
        if (world == null || loc == null || names == null) return;
        for (var i = 0; i < names.length; i++) {
            try { world.playSound(loc, '' + names[i], 0 + vol, 0 + pit); return; } catch (e) { }
        }
    } catch (e) { }
}

// 粒子：一串候选名，取第一个存在的类型
function gsWlParticle(names) {
    try {
        var P = Java.type('org.bukkit.Particle');
        for (var i = 0; i < names.length; i++) {
            var n = '' + names[i];
            try { if (P[n] != null) return P[n]; } catch (e) { }
            try { return P.valueOf(n); } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function gsWlBurst(world, x, y, z, names, count, ox, oy, oz, extra) {
    try {
        var type = gsWlParticle(names);
        if (type == null || world == null) return;
        world.spawnParticle(type, 0 + x, 0 + y, 0 + z, count, ox, oy, oz, extra);
    } catch (e) { }
}

// ---------------- 目标筛选 / 伤害 ----------------
function gsWlNotTarget(p, ent) {
    try {
        if (ent == null) return true;
        try { ent.getHealth(); } catch (e) { return true; }        // 只认有血量的活体
        try { if (ent.isDead()) return true; } catch (e) { }
        var tn = '' + ent.getType().name();
        if (tn === 'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM'
            || tn === 'EXPERIENCE_ORB') return true;
        if (tn === 'PLAYER') {
            if (!WL_HIT_PLAYERS) return true;                       // 默认不打玩家
            if (p != null && ('' + ent.getUniqueId()) === ('' + p.getUniqueId())) return true;
        }
        if (gsIsOurSummon(ent)) return true;                        // 自己的召唤物
        if (gsIsMaiyouSummon(ent)) return true;                     // 麦友的酒
        return false;
    } catch (e) { return true; }
}

function gsWlHurt(p, ent, dmg) {
    try {
        if (ent == null) return;
        try { ent.setNoDamageTicks(0); } catch (e) { }
        GS_WL_DAMAGING['' + p.getUniqueId()] = true;                // 放行近战拦截
        try { ent.damage(0 + dmg, p); }
        catch (e1) { try { ent.damage(0 + dmg); } catch (e2) { } }
        GS_WL_DAMAGING['' + p.getUniqueId()] = false;
    } catch (e) {
        try { GS_WL_DAMAGING['' + p.getUniqueId()] = false; } catch (e2) { }
    }
}

function gsWlVel(ent, vx, vy, vz) {
    try {
        var Vector = Java.type('org.bukkit.util.Vector');
        ent.setVelocity(new Vector(0 + vx, 0 + vy, 0 + vz));
    } catch (e) { }
}

// ---------------- 定身（击退先飞 0.25 秒，再锁死位置）----------------
function gsWlStun(ent, ms) {
    try {
        if (ent == null) return;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return; }
        if (tn === 'PLAYER') return;                 // 玩家不定身（默认也不打玩家）
        var hadAi = true;
        try { hadAi = ent.hasAI(); } catch (e) { hadAi = true; }
        GS_WL_STUN['' + ent.getUniqueId()] = {
            ent: ent,
            loc: null,
            ai: hadAi,
            lockAt: gsWuliNow() + WL_CANNON_STUN_LOCK,
            until: gsWuliNow() + (0 + ms)
        };
    } catch (e) { }
}

function gsWlPulse() {
    try {
        var now = gsWuliNow();
        var Vector = null;
        try { Vector = Java.type('org.bukkit.util.Vector'); } catch (e) { }
        for (var k in GS_WL_STUN) {
            var s = GS_WL_STUN[k];
            if (s == null) { try { delete GS_WL_STUN[k]; } catch (e) { } continue; }
            var ent = s.ent;
            var alive = false;
            try { alive = (ent != null && ent.isValid() && !ent.isDead()); } catch (e) { alive = false; }
            if (!alive || now >= s.until) {
                try { if (s.ai && alive) ent.setAI(true); } catch (e) { }
                try { delete GS_WL_STUN[k]; } catch (e) { }
                continue;
            }
            if (now < s.lockAt) continue;                       // 先让它被击退飞出去
            if (s.loc == null) {
                try {
                    s.loc = ent.getLocation().clone();
                    ent.setAI(false);
                } catch (e) { continue; }
            }
            try { if (Vector != null) ent.setVelocity(new Vector(0, 0, 0)); } catch (e) { }
            try { ent.teleport(s.loc); } catch (e) { }          // 位置锁死 = 定身
        }
        // 兜底：清掉已经失效的炸弹条目
        for (var bid in GS_WL_BOMBS) {
            var b = GS_WL_BOMBS[bid];
            if (b == null || b.dead) { try { delete GS_WL_BOMBS[bid]; } catch (e) { } }
        }
    } catch (e) { }
}

// ---------------- 加速 II ----------------
function gsWlGrantSpeed(p) {
    try {
        if (p == null) return;
        if (gsWlCdLeft(p, 'speed', WL_SPEED_CD_MS) > 0) return;   // 冷却中静默
        var PET = Java.type('org.bukkit.potion.PotionEffectType');
        var PE = Java.type('org.bukkit.potion.PotionEffect');
        var t = null;
        try { t = PET.SPEED; } catch (e) { t = null; }
        if (t == null) { try { t = PET.valueOf('SPEED'); } catch (e) { t = null; } }
        if (t == null) return;
        p.addPotionEffect(new PE(t, WL_SPEED_TICKS, WL_SPEED_AMP, true, false));
        gsWlMarkCd(p, 'speed');
        gsWlPlay(p.getWorld(), p.getLocation(), WL_SPEED_SNDS, 1.0, 1.0);
    } catch (e) { }
}

// ---------------- 投出 TNT ----------------
function gsWlSpawnTnt(world, loc) {
    try {
        var E = Java.type('org.bukkit.entity.EntityType');
        var t = null;
        try { t = E.PRIMED_TNT; } catch (e) { t = null; }
        if (t == null) { try { t = E.TNT; } catch (e) { t = null; } }
        if (t == null) { try { t = E.valueOf('PRIMED_TNT'); } catch (e) { try { t = E.valueOf('TNT'); } catch (e2) { } } }
        if (t == null) return null;
        return world.spawnEntity(loc, t);
    } catch (e) { return null; }
}

// ★ 这个坐标是不是"过不去"的？玻璃 / 墙 / 半砖 / 树叶 ⇒ true；空气 / 水 / 草 ⇒ false
//   （用 Block#isPassable()：**玻璃 isPassable=false**，所以挡得住炸弹；
//     老版本没有这个 API 就退回 Material#isSolid()）
function gsWlBlockedAt(world, x, y, z) {
    try {
        var Loc = Java.type('org.bukkit.Location');
        var blk = world.getBlockAt(new Loc(world, 0 + x, 0 + y, 0 + z));
        if (blk == null) return false;
        var mt = null;
        try { mt = blk.getType(); } catch (e) { mt = null; }
        if (mt != null) {
            try { if (mt.isAir()) return false; } catch (e) { }
        }
        try { return !blk.isPassable(); } catch (e) { }
        if (mt != null) { try { return !!mt.isSolid(); } catch (e) { } }
        return false;
    } catch (e) { return false; }
}

// ★ 逐段扫描一段轨迹：撞到障碍就返回**撞击点之前**那个采样点（= 障碍物表面外侧），
//   一路畅通返回 null。（TNT 用）
function gsWlRayHit(world, x0, y0, z0, x1, y1, z1) {
    try {
        var dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        var len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4) return null;
        var steps = Math.ceil(len / WL_BLOCK_STEP);
        if (steps < 1) steps = 1;
        var lx = x0, ly = y0, lz = z0;
        for (var s = 1; s <= steps; s++) {
            var t = s / steps;
            var px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t;
            if (gsWlBlockedAt(world, px, py, pz)) return { x: lx, y: ly, z: lz };
            lx = px; ly = py; lz = pz;
        }
        return null;
    } catch (e) { return null; }
}

// ★ 同上，但按"玩家体积"判定（脚下 + 头部两处），给冲刺用
function gsWlDashRay(world, x0, y0, z0, x1, y1, z1) {
    try {
        var dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        var len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-4) return null;
        var steps = Math.ceil(len / WL_DASH2_STEP);
        if (steps < 1) steps = 1;
        var lx = x0, ly = y0, lz = z0;
        for (var s = 1; s <= steps; s++) {
            var t = s / steps;
            var px = x0 + dx * t, py = y0 + dy * t, pz = z0 + dz * t;
            if (gsWlBlockedAt(world, px, py + 0.1, pz) || gsWlBlockedAt(world, px, py + 1.1, pz)) {
                return { x: lx, y: ly, z: lz };
            }
            lx = px; ly = py; lz = pz;
        }
        return null;
    } catch (e) { return null; }
}

// ★ 这个位置是不是"卡在方块里"（脚下 / 头部任一处过不去）⇒ 冲刺落地专用兜底
function gsWlStuckAt(world, x, y, z) {
    try {
        return gsWlBlockedAt(world, x, y + 0.1, z) || gsWlBlockedAt(world, x, y + 1.1, z);
    } catch (e) { return false; }
}

// ★ 卡住就往上抬，最多找 6 格（找不到就原样返回，宁可不抬也别乱传）
function gsWlSafeY(world, x, y, z) {
    try {
        if (!gsWlStuckAt(world, x, y, z)) return y;
        var yy = y;
        for (var s = 0; s < 6; s++) {
            yy += 1.0;
            if (!gsWlStuckAt(world, x, yy, z)) return yy;
        }
        return y;
    } catch (e) { return y; }
}

// ★ 把陷进方块里的玩家拔出来（**绝不让玩家掉到地下一格** ⇒ 也就不会"穿"到墙 / 地板下面）
function gsWlUnstuck(p) {
    try {
        var l = p.getLocation();
        if (l == null) return;
        if (!gsWlStuckAt(l.getWorld(), l.getX(), l.getY(), l.getZ())) return;
        var sy = gsWlSafeY(l.getWorld(), l.getX(), l.getY(), l.getZ());
        var Loc = Java.type('org.bukkit.Location');
        try { p.teleport(new Loc(l.getWorld(), l.getX(), sy, l.getZ(), l.getYaw(), l.getPitch())); } catch (e) { }
        try { p.setFallDistance(0); } catch (e2) { }
    } catch (e) { }
}

function gsWlThrow(p, kind) {
    try {
        var world = p.getWorld();
        var eye = p.getEyeLocation();
        var dir = eye.getDirection();
        var dist = (kind === 'bomb') ? WL_BOMB_DIST : WL_CANNON_DIST;

        var sx = eye.getX() + dir.getX() * 0.35;      // ★ 出生点别推太远，免得贴着玻璃时直接生在玻璃后面
        var sy = eye.getY() + dir.getY() * 0.35 - 0.2;
        var sz = eye.getZ() + dir.getZ() * 0.35;
        var tx = eye.getX() + dir.getX() * dist;
        var ty = eye.getY() + dir.getY() * dist;
        var tz = eye.getZ() + dir.getZ() * dist;

        // ★★ 出生前先整段瞄一遍：视线被墙 / 玻璃挡住 ⇒ 目标点直接截到**障碍前面**
        //    （否则"前方 7 格 / 12 格"会把 TNT 扔到墙的另一侧，看起来就像穿墙）
        var aim = gsWlRayHit(world, sx, sy, sz, tx, ty, tz);
        if (aim != null) { tx = aim.x; ty = aim.y; tz = aim.z; }

        var Loc = Java.type('org.bukkit.Location');
        var tnt = gsWlSpawnTnt(world, new Loc(world, sx, sy, sz));
        if (tnt == null) return null;

        // ★★ 永不引爆 + 不会被别的伤害清掉 + 不写盘
        try { tnt.setFuseTicks(200000); } catch (e) { }
        try { tnt.setInvulnerable(true); } catch (e) { }
        try { tnt.setGravity(false); } catch (e) { }
        try { tnt.setPersistent(false); } catch (e) { }
        try {
            gsWlKeys();
            if (GS_WL_BOMB_KEY != null && GS_PDT != null) {
                tnt.getPersistentDataContainer().set(GS_WL_BOMB_KEY, GS_PDT.STRING, '' + kind);
            }
        } catch (e) { }

        GS_WL_BOMB_SEQ++;
        var b = {
            id: GS_WL_BOMB_SEQ,
            kind: kind,
            p: p,
            world: world,
            tnt: tnt,
            sx: sx, sy: sy, sz: sz,
            tx: tx, ty: ty, tz: tz,
            cx: sx, cy: sy, cz: sz,          // ★ 当前坐标（逐段碰撞检测要用到上一刻的位置）
            flyTicks: (kind === 'bomb') ? WL_BOMB_FLY_TICKS : WL_CANNON_FLY_TICKS,
            i: 0,
            phase: 'fly',
            attach: null,
            fuse: 0,
            fallTicks: 0,
            stable: 0,
            lastY: sy,
            dead: false
        };
        GS_WL_BOMBS['' + b.id] = b;

        // 抛出音效（★ 没有 TNT 点燃音：左键 = 获得经验，右键 = 风弹）
        if (kind === 'bomb') gsWlPlay(world, p.getLocation(), WL_BOMB_SNDS_THROW, 1.0, 1.3);
        else gsWlPlay(world, p.getLocation(), WL_CANNON_SNDS_THROW, 1.0, 1.0);

        runLater(function () { gsWlBombTick(b); }, 0);
        return b;
    } catch (e) { return null; }
}

// 找吸附半径（WL_BOMB_MAGNET_R）内可以吸附的生物
function gsWlFindMagnet(tnt, p) {
    try {
        var l = tnt.getLocation();
        var list = tnt.getWorld().getNearbyEntities(l, WL_BOMB_MAGNET_R, WL_BOMB_MAGNET_R, WL_BOMB_MAGNET_R);
        if (list == null) return null;
        var best = null, bestD = 1.0e9;
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (gsWlNotTarget(p, ent)) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }
            var dx = el.getX() - l.getX();
            var dy = (el.getY() + 0.9) - l.getY();
            var dz = el.getZ() - l.getZ();
            var d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d <= WL_BOMB_MAGNET_R && d < bestD) { bestD = d; best = ent; }
        }
        return best;
    } catch (e) { return null; }
}

function gsWlBombEnd(b, remove) {
    try {
        if (b == null || b.dead) return;
        b.dead = true;
        try { delete GS_WL_BOMBS['' + b.id]; } catch (e) { }
        if (remove && b.tnt != null) { try { b.tnt.remove(); } catch (e) { } }
    } catch (e) { }
}

function gsWlBombTick(b) {
    try {
        if (b == null || b.dead) return;
        var tnt = b.tnt;
        if (tnt == null) { gsWlBombEnd(b, false); return; }
        var alive = true;
        try { alive = tnt.isValid() && !tnt.isDead(); } catch (e) { alive = false; }
        if (!alive) { gsWlBombEnd(b, false); return; }
        try {
            if (b.p == null || !b.p.isOnline()) { gsWlBombEnd(b, true); return; }
        } catch (e) { }

        var Loc = Java.type('org.bukkit.Location');

        // ① 吸附（只有磁吸炸弹，且还没吸上）
        if (b.kind === 'bomb' && b.attach == null) {
            var tgt = gsWlFindMagnet(tnt, b.p);
            if (tgt != null) {
                b.attach = tgt;
                if (b.phase !== 'wait') { b.phase = 'wait'; b.fuse = WL_BOMB_FUSE_TICKS; }
            }
        }

        if (b.phase === 'fly') {
            // ② 飞行：脚本控制轨迹（每刻约 0.5 格 ⇒ 吸附判定不会漏）
            b.i++;
            var k = b.i / b.flyTicks;
            if (k > 1) k = 1;
            var fx = b.sx + (b.tx - b.sx) * k;
            var fy = b.sy + (b.ty - b.sy) * k + Math.sin(Math.PI * k) * 0.6;   // 一点点抛物线
            var fz = b.sz + (b.tz - b.sz) * k;

            // ★★ 逐段扫描这一段轨迹：撞到玻璃 / 墙 / 半砖… 就停在**障碍前面**，绝不穿过去
            var hit = gsWlRayHit(b.world, b.cx, b.cy, b.cz, fx, fy, fz);
            if (hit != null) {
                b.cx = hit.x; b.cy = hit.y; b.cz = hit.z;
                try { tnt.teleport(new Loc(b.world, b.cx, b.cy, b.cz)); } catch (e) { }
                b.phase = 'fall';                       // 撞上了 ⇒ 就地开始下落
                b.fallTicks = 0;
                b.lastY = b.cy;
                try { tnt.setGravity(true); } catch (e) { }
                try { gsWlVel(tnt, 0.0, -0.05, 0.0); } catch (e) { }
            } else {
                b.cx = fx; b.cy = fy; b.cz = fz;
                try { tnt.teleport(new Loc(b.world, b.cx, b.cy, b.cz)); } catch (e) { }
                if (b.i >= b.flyTicks) {
                    b.phase = 'fall';
                    b.fallTicks = 0;
                    b.lastY = b.cy;
                    try { tnt.setGravity(true); } catch (e) { }
                    try { gsWlVel(tnt, 0.0, -0.05, 0.0); } catch (e) { }
                }
            }
        } else if (b.phase === 'fall') {
            // ③ 下落：等它真正落地（真 · 落地判定：onGround 或连续两刻不动）
            b.fallTicks++;
            var y = 0;
            try { y = tnt.getLocation().getY(); } catch (e) { y = b.lastY; }
            if (Math.abs(y - b.lastY) < 0.001) b.stable++; else b.stable = 0;
            b.lastY = y;
            var onGround = false;
            try { onGround = tnt.isOnGround(); } catch (e) { onGround = false; }
            if (onGround || b.stable >= 2 || b.fallTicks > 60) {
                b.phase = 'wait';
                b.fuse = (b.kind === 'bomb') ? WL_BOMB_FUSE_TICKS : WL_CANNON_FUSE_TICKS;
            }
        } else if (b.phase === 'wait') {
            // ④ 计时中：吸住了就跟着目标走
            if (b.attach != null) {
                try {
                    if (!b.attach.isValid() || b.attach.isDead()) b.attach = null;
                    else {
                        var al = b.attach.getLocation();
                        tnt.teleport(new Loc(b.world, al.getX(), al.getY() + 0.9, al.getZ()));
                    }
                } catch (e) { b.attach = null; }
            }
            b.fuse--;
            if (b.fuse <= 0) { gsWlBoom(b); return; }
        }

        runLater(function () { gsWlBombTick(b); }, 1);
    } catch (e) {
        try { gsWlBombEnd(b, true); } catch (e2) { }
    }
}

// ---------------- 爆炸（手搓演出，绝不 createExplosion）----------------
function gsWlBoom(b) {
    try {
        if (b == null || b.dead) return;
        b.dead = true;
        try { delete GS_WL_BOMBS['' + b.id]; } catch (e) { }

        var tnt = b.tnt, world = b.world, p = b.p;
        var l = null;
        try { l = tnt.getLocation(); } catch (e) { l = null; }
        try { tnt.remove(); } catch (e) { }
        if (l == null || world == null) return;

        var x = l.getX(), y = l.getY(), z = l.getZ();
        var isBomb = (b.kind === 'bomb');
        var r = isBomb ? WL_BOMB_R : WL_CANNON_R;
        var dmg = isBomb ? WL_BOMB_DMG : WL_CANNON_DMG;

        // ① 演出：粒子 + 音效
        if (isBomb) {
            gsWlBurst(world, x, y, z, WL_BOOM_PARTICLES, 26, 0.9, 0.9, 0.9, 0.12);
            gsWlPlay(world, l, WL_BOOM_SNDS, 2.5, 0.9);
        } else {
            gsWlBurst(world, x, y, z, WL_WIND_PARTICLES, 45, 1.3, 1.3, 1.3, 0.05);
            gsWlPlay(world, l, WL_WIND_SNDS, 2.0, 1.0);
        }

        // ② 结算伤害 / 击退 / 定身
        var hit = 0;
        var list = null;
        try {
            var Loc = Java.type('org.bukkit.Location');
            list = world.getNearbyEntities(new Loc(world, x, y, z), r, r, r);
        } catch (e) { list = null; }

        if (list != null) {
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (gsWlNotTarget(p, ent)) continue;
                var el = null;
                try { el = ent.getLocation(); } catch (e) { continue; }
                var dx = el.getX() - x;
                var dy = (el.getY() + 0.9) - y;
                var dz = el.getZ() - z;
                var d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r * r) continue;

                hit++;
                gsWlHurt(p, ent, dmg);

                if (!isBomb) {
                    var len = Math.sqrt(d2);
                    var ux = dx, uy = dy, uz = dz;
                    if (len < 1.0e-4) { ux = 0.0; uy = 1.0; uz = 0.0; len = 1.0; }
                    ux /= len; uy /= len; uz /= len;
                    gsWlVel(ent, ux * WL_CANNON_KB, uy * WL_CANNON_KB * 0.4 + WL_CANNON_KB_UP, uz * WL_CANNON_KB);
                    gsWlStun(ent, WL_CANNON_STUN_MS);
                }
            }
        }

        // ③ 提示：虎蹲炮**命中了**才提示
        if (!isBomb && hit > 0 && p != null) gsWlMsg(p, WL_CANNON_MSG_HIT);
    } catch (e) { }
}

// ---------------- 左键：磁吸炸弹 ----------------
function gsWlTryBomb(p) {
    try {
        var bl = gsWlCdLeft(p, 'bomb', WL_BOMB_CD_MS);
        if (bl > 0) { gsWlMsg(p, gsWlCdMsg(WL_BOMB_MSG_CD, bl)); return; }
        var b = gsWlThrow(p, 'bomb');
        if (b == null) return;
        gsWlMarkCd(p, 'bomb');
        gsWlMsg(p, WL_BOMB_MSG);
        gsWlGrantSpeed(p);                       // 检测到左键 → 速度 II（自带 5 秒冷却）
    } catch (e) { }
}

// ---------------- 右键：虎蹲炮 ----------------
function gsWlTryCannon(p) {
    try {
        var cl = gsWlCdLeft(p, 'cannon', WL_CANNON_CD_MS);
        if (cl > 0) { gsWlMsg(p, gsWlCdMsg(WL_CANNON_MSG_CD, cl)); return; }
        var b = gsWlThrow(p, 'cannon');
        if (b == null) return;
        gsWlMarkCd(p, 'cannon');
        gsWlGrantSpeed(p);                       // 检测到右键 → 速度 II（自带 5 秒冷却）
    } catch (e) { }
}

// ---------------- F 键：动力推进 ----------------
function gsWlSpit(p) {
    try {
        var l = p.getLocation();
        gsWlBurst(p.getWorld(), l.getX(), l.getY() + 1.35, l.getZ(), WL_SPIT_PARTICLES,
            3, 0.12, 0.12, 0.12, 0.02);
    } catch (e) { }
}

function gsWlTryDash(p) {
    try {
        var dl = gsWlCdLeft(p, 'dash', WL_DASH2_CD_MS);
        if (dl > 0) { gsWlMsg(p, gsWlCdMsg(WL_DASH2_MSG_CD, dl)); return; }
        gsWlMarkCd(p, 'dash');
        gsWlMsg(p, WL_DASH2_MSG);

        var world = p.getWorld();
        var l = p.getLocation();
        var dx = 0.0, dy = 0.0, dz = 0.0;

        if (WL_DASH2_FLAT) {
            // ★★ 冲刺**只走水平方向**：把视线的水平分量归一化 ⇒ 抬头 / 低头只影响朝向，
            //    不影响冲刺方向（看天也往前冲，绝不会把自己射上天或钻进地里）
            var dir = p.getEyeLocation().getDirection();
            var hx = dir.getX(), hz = dir.getZ();
            var hlen = Math.sqrt(hx * hx + hz * hz);
            if (hlen < 1.0e-4) {                        // 视线几乎垂直 ⇒ 用 yaw 兜底
                var yr = l.getYaw() * Math.PI / 180.0;
                hx = -Math.sin(yr); hz = Math.cos(yr); hlen = 1.0;
            }
            dx = hx / hlen;
            dz = hz / hlen;
            dy = 0.0;
            if (WL_DASH2_AXIS) {                        // 可选：吸附到东 / 南 / 西 / 北四个正方向
                if (Math.abs(dx) >= Math.abs(dz)) { dx = (dx >= 0 ? 1.0 : -1.0); dz = 0.0; }
                else                              { dz = (dz >= 0 ? 1.0 : -1.0); dx = 0.0; }
            }
        } else {
            var d3 = p.getEyeLocation().getDirection();
            dx = d3.getX(); dy = d3.getY(); dz = d3.getZ();   // 老行为：连视线俯仰一起冲
        }

        var st = {
            p: p,
            world: world,
            i: 0,
            wait: 0,
            done: false,
            x0: l.getX(), y0: l.getY(), z0: l.getZ(),
            px: l.getX(), py: l.getY(), pz: l.getZ(),   // ★ 上一刻的位置（逐段碰撞检测用）
            dx: dx, dy: dy, dz: dz,
            yaw: l.getYaw(),
            pitch: l.getPitch()
        };
        gsWlPlay(world, l, WL_DASH2_SNDS, 1.2, 1.0);     // 一个风弹的音效
        runLater(function () { gsWlDashTick(p, st); }, 0);
    } catch (e) { }
}

function gsWlDashTick(p, st) {
    try {
        if (st == null || st.done) return;
        try { if (!p.isOnline()) { st.done = true; return; } } catch (e) { return; }

        var Loc = Java.type('org.bukkit.Location');

        if (st.i < WL_DASH2_TICKS) {
            st.i++;
            var k = st.i / WL_DASH2_TICKS;
            var nx = st.x0 + st.dx * WL_DASH2_DIST * k;
            var ny = st.y0 + st.dy * WL_DASH2_DIST * k + Math.sin(Math.PI * k) * WL_DASH2_HOP;
            var nz = st.z0 + st.dz * WL_DASH2_DIST * k;
            ny = gsWlSafeY(st.world, nx, ny, nz);       // ★ 落点在方块里 ⇒ 抬到能站的高度
            // ★★ 12 格不能一步跳过去（会穿墙）⇒ 逐段（0.4 格）扫一遍，撞墙就停在墙前面
            var hit = gsWlDashRay(st.world, st.px, st.py, st.pz, nx, ny, nz);
            if (hit != null) {
                var hy = gsWlSafeY(st.world, hit.x, hit.y, hit.z);
                try { p.teleport(new Loc(st.world, hit.x, hy, hit.z, st.yaw, st.pitch)); } catch (e) { }
                st.px = hit.x; st.py = hy; st.pz = hit.z;
                try { p.setFallDistance(0); } catch (e) { }
                gsWlSpit(p);
                st.i = WL_DASH2_TICKS;                  // 撞墙 ⇒ 冲刺提前收尾（进"等落地"）
                runLater(function () { gsWlDashTick(p, st); }, 1);
                return;
            }
            st.px = nx; st.py = ny; st.pz = nz;
            try { p.teleport(new Loc(st.world, nx, ny, nz, st.yaw, st.pitch)); } catch (e) { }
            gsWlUnstuck(p);                             // ★ 双保险：真陷进去了立刻拔出来
            try { p.setFallDistance(0); } catch (e) { }
            gsWlSpit(p);                                  // 肩部持续冒羊驼口水
            runLater(function () { gsWlDashTick(p, st); }, 1);
            return;
        }

        // 冲刺动作走完 → 等落地（最多 40 刻 = 2 秒）→ 落地那一刻给速度 II
        gsWlUnstuck(p);                                 // ★ 落地途中也不能陷进方块里
        var onGround = false;
        try { onGround = p.isOnGround(); } catch (e) { onGround = true; }
        st.wait++;
        if (onGround || st.wait > 40) {
            st.done = true;
            try { p.setFallDistance(0); } catch (e) { }
            gsWlUnstuck(p);                             // ★ 落地那一刻再兜一次底
            gsWlSpit(p);
            gsWlGrantSpeed(p);
            return;
        }
        try { p.setFallDistance(0); } catch (e) { }
        gsWlSpit(p);
        runLater(function () { gsWlDashTick(p, st); }, 1);
    } catch (e) {
        try { if (st != null) st.done = true; } catch (e2) { }
        try { gsWlGrantSpeed(p); } catch (e2) { }
    }
}

// ---------------- 玩家下线 / 死亡：收掉还在空中的 TNT ----------------
function gsWlCleanupPlayer(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        for (var bid in GS_WL_BOMBS) {
            var b = GS_WL_BOMBS[bid];
            if (b == null) { try { delete GS_WL_BOMBS[bid]; } catch (e) { } continue; }
            var same = false;
            try { same = (b.p != null && ('' + b.p.getUniqueId()) === uuid); } catch (e) { same = false; }
            if (same) gsWlBombEnd(b, true);
        }
    } catch (e) { }
}

// ---------------- 注册同步监听器 ----------------
function gsWlRegisterSync() {
    if (GS_WL_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        // ① F 键（换手事件）= 动力推进（★ 拦下原版换手）
        var SwapEv = null;
        try { SwapEv = Java.type('org.bukkit.event.player.PlayerSwapHandItemsEvent'); } catch (e) { }
        if (SwapEv != null) {
            reg(SwapEv, function (e) {
                try {
                    if (e.isCancelled()) return;           // 重复注册的实例 → 跳过
                    var p = e.getPlayer();
                    if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) return;
                    e.setCancelled(true);
                    gsWlTryDash(p);
                } catch (e3) { }
            });
        }

        // ② 左键 = 磁吸炸弹；右键 = 虎蹲炮
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    // ★ 故意**不检查 e.isCancelled()**：有些前置插件会先把 *_CLICK_AIR 取消掉，
                    //   一检查就永远收不到"对着空气按键" ⇒ 变成必须点方块才放得出来。
                    //   重复注册的实例交给 gsWlClickOnce() 的 80ms 去抖拦。
                    var p = e.getPlayer();
                    if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手
                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        if (!gsWlClickOnce(p, 'R')) return;   // ★ 一次点击只处理一遍
                        e.setCancelled(true);              // 拦住原版交互（开箱子 / 开门 / 放方块…）
                        gsWlTryCannon(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsWlClickOnce(p, 'L')) return;   // ★ 同上
                        // ★ 手持威龙**挖不动方块** ⇒ 左键点方块也一并取消（连挖掘进度条都不出）
                        if (WL_NO_BREAK) e.setCancelled(true);
                        gsWlTryBomb(p);
                    }
                } catch (e3) { }
            });
        }

        // ②b ★ 左键兜底：**挥手动画**（对着空气点也能收到 —— 客户端左键必定发一次 arm swing）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (!gsWlIsWeilong(p.getInventory().getItemInMainHand())) return;
                    // 只认"挥手"这一种动画（老版本没有 getAnimationType()，拿不到就当它是挥手）
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsWlRecentRight(p, 300)) return;    // ★ 右键附带的动画 → 忽略
                    if (!gsWlClickOnce(p, 'L')) return;     // ★ 与 ② 共用去抖 ⇒ 不会放两次
                    gsWlTryBomb(p);
                } catch (e3) { }
            });
        }

        // ②c ★ 手持威龙 → 无法破坏方块（双保险：即使上面的取消被别的插件放行了也挖不掉）
        if (WL_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsWlIsWeilong(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

        // ③ 近战：手持威龙时一律取消（左键只用来扔炸弹）
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { }
        if (DmgEv != null) {
            reg(DmgEv, function (e) {
                try {
                    if (e.isCancelled()) return;
                    var d = null;
                    try { d = e.getDamager(); } catch (e2) { return; }
                    if (d == null) return;
                    var dn = '';
                    try { dn = '' + d.getType().name(); } catch (e2) { return; }
                    if (dn !== 'PLAYER') return;
                    if (!gsWlIsWeilong(d.getInventory().getItemInMainHand())) return;
                    if (GS_WL_DAMAGING['' + d.getUniqueId()] === true) return;   // 自己结算的伤害放行
                    e.setCancelled(true);
                } catch (e3) { }
            });
        }

        // ④ 击杀生物 → 立刻清空冲刺冷却
        var DeathEv = null;
        try { DeathEv = Java.type('org.bukkit.event.entity.EntityDeathEvent'); } catch (e) { }
        if (DeathEv != null) {
            reg(DeathEv, function (e) {
                try {
                    var victim = e.getEntity();
                    var killer = null;
                    try { killer = victim.getKiller(); } catch (e2) { killer = null; }
                    if (killer == null) return;
                    // ★ **不检查物品**：不管手里拿的是什么，杀了生物就清空冲刺冷却
                    //   （冲刺冷却本来就只有手持威龙时才用得上，没必要再判一次）
                    gsWlClearCd(killer, 'dash');
                } catch (e3) { }
            });
        }

        // ⑤ 下线 / 死亡 → 收掉还在空中的 TNT
        var QuitEv = null;
        try { QuitEv = Java.type('org.bukkit.event.player.PlayerQuitEvent'); } catch (e) { }
        if (QuitEv != null) {
            reg(QuitEv, function (e) {
                try { gsWlCleanupPlayer(e.getPlayer()); } catch (e2) { }
            });
        }
        var PDeathEv = null;
        try { PDeathEv = Java.type('org.bukkit.event.entity.PlayerDeathEvent'); } catch (e) { }
        if (PDeathEv != null) {
            reg(PDeathEv, function (e) {
                try { gsWlCleanupPlayer(e.getEntity()); } catch (e2) { }
            });
        }

        GS_WL_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 威龙监听器注册成功（左键=磁吸炸弹 · 右键=虎蹲炮 · F=动力推进）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 威龙同步监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsWlStart() {
    try {
        runRepeating(function () { gsWlPulse(); }, 2, 2);
        gsWlRegisterSync();
        gsDaLog('威龙：磁吸炸弹已启用（7 格 · 3 秒 · 2 格 ' + WL_BOMB_DMG + ' 点 · 冷却 '
            + (WL_BOMB_CD_MS / 1000.0) + ' 秒 · 吸附 ' + WL_BOMB_MAGNET_R + ' 格）');
        gsDaLog('威龙：虎蹲炮已启用（12 格 · 2 秒 · 3 格 ' + WL_CANNON_DMG + ' 点 + 击退 + 定身 '
            + (WL_CANNON_STUN_MS / 1000.0) + ' 秒 · 冷却 ' + (WL_CANNON_CD_MS / 1000.0) + ' 秒）');
        gsDaLog('威龙：动力推进已启用（F 键 · 水平 ' + WL_DASH2_DIST + ' 格 · 冷却 '
            + (WL_DASH2_CD_MS / 1000.0) + ' 秒 · 击杀生物立即清空冷却）');
        gsDaLog('威龙：左键/右键/冲刺落地 → 速度 II ' + (WL_SPEED_TICKS / 20.0)
            + ' 秒（自带 ' + (WL_SPEED_CD_MS / 1000.0) + ' 秒冷却 · 烈焰人火球音）');
        gsDaLog('威龙：所有 TNT **永不引爆**、爆炸为手搓演出 ⇒ 不炸方块 / 不炸展示框 / 不触发按钮');
        gsDaLog('威龙：TNT 逐段碰撞检测（' + WL_BLOCK_STEP + ' 格）⇒ 不穿玻璃 / 不穿墙');
        gsDaLog('威龙：手持时**无法破坏方块**（左键 / 右键都不动方块），按键即放、不用点方块');
        gsDaLog('威龙：冲刺**只走水平方向**（抬头 / 低头不会往上下冲'
            + (WL_DASH2_AXIS ? '，并吸附到东/南/西/北' : '') + '）');
        gsDaLog('威龙：**击杀任意生物即清空冲刺冷却**（不要求手持威龙）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 威龙启动失败: ' + e); } catch (e2) { }
    }
}

gsWlStart();

/* ==========================================================
 * 基础构思 · 纯材料「取消左右键交互」
 *   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §8
 * ========================================================================== */

// 基础构思里的「纯材料」清单（19 项里去掉滚木 = 18 项）
var GS_BASIC_PLAIN_IDS = [
    'GS_IMAGINATION', 'GS_MEMORY_FRAGMENT', 'GS_INSPIRATION', 'GS_DELUSION',
    'GS_THOUGHT', 'GS_CONCEPT', 'GS_PROTOTYPE', 'GS_LAW_IMAGE', 'GS_FIXED_FORM',
    'GS_DREAM', 'GS_MOLD', 'GS_DREAM_RULE', 'GS_DREAM_INGOT',
    'GS_DREAM_REALM', 'GS_CONSTRUCT', 'GS_TRUE_DREAM', 'GS_THOUGHT_CONSTRUCT',
    'GS_WANGXIANG_FRAGMENT'
    // ★ 滚木 GS_ROLLING_LOG_INGOT **故意不在这里** —— 保留它的右键隐身
];

var GS_PLAIN_REGISTERED = false;

// 这件物品是不是「基础构思纯材料」
function gsPlainIsBasic(item) {
    if (item == null) return false;
    try {
        var n = '' + item.getType().name();
        if (n.length === 0 || n === 'AIR') return false;      // 空气直接放过
    } catch (e) {
        return false;
    }
    for (var i = 0; i < GS_BASIC_PLAIN_IDS.length; i++) {
        if (gsIsItem(item, GS_BASIC_PLAIN_IDS[i])) return true;
    }
    return false;
}

function gsPlainRegisterSync() {
    if (GS_PLAIN_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv == null) return false;

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        function handle(e) {
            try {
                var p = e.getPlayer();
                if (p == null) return;

                // 事件自带的物品（右键时有；左键点方块时可能是 null）
                var hit = false;
                try { if (gsPlainIsBasic(e.getItem())) hit = true; } catch (e2) { }

                // 主手兜底：左键点方块 / 对着空气按键时事件可能不带物品
                if (!hit) {
                    try { if (gsPlainIsBasic(p.getInventory().getItemInMainHand())) hit = true; } catch (e2) { }
                }
                if (!hit) return;

                e.setCancelled(true);
                if (Res != null) {
                    try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                    try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                }
            } catch (e3) { }
        }

        try {
            pm.registerEvent(InterEv, plugin, Priority.HIGHEST,
                function (l, e) { handle(e); }, plugin);
        } catch (e1) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handle(e); } });
                pm.registerEvent(InterEv, listener, Priority.HIGHEST, exec, plugin);
            } catch (e2) {
                return false;
            }
        }

        GS_PLAIN_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 基础构思监听器注册成功（纯材料左右键交互已取消，滚木除外）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 基础构思监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsPlainStart() {
    try {
        gsPlainRegisterSync();
        gsDaLog('基础构思：' + GS_BASIC_PLAIN_IDS.length
            + ' 件纯材料已取消左右键交互（石头/材料都不再响应点击，**滚木保留右键隐身**）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 基础构思启动失败: ' + e); } catch (e2) { }
    }
}

gsPlainStart();

// ==========================================================================
// 月镰（GS_MOON_SCYTHE · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §20
//   ★ 以下 1 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//   ★ 只认主手；副手拿着不触发
// ==========================================================================
var GS_MS_ID       = 'GS_MOON_SCYTHE';
var GS_MS_REACH    = 3.0;      // 挥砍范围：前方 3 格（**长度**）
var GS_MS_WIDE     = 3.0;      // ★ 第十七批：挥砍宽度（左右各 1.5 格 ⇒ 共 3 格宽）
var GS_MS_TALL     = 1.6;      // ★ 第十七批：挥砍上下判定高度（格）
var GS_MS_DMG      = 11.0;     // 挥砍伤害
var GS_MS_SWING_CD = 320;      // 挥砍冷却（毫秒）
var GS_MS_MAX      = 12;       // 「梦」满层
var GS_MS_WINDOW   = 7000;     // 未满层时每层的存活窗口（毫秒）
var GS_MS_FULL_WIN = 30000;    // 满 12 层后的倒计时（毫秒）
var GS_MS_NO_BREAK = true;     // true = 手持月镰挖不动方块
var GS_MS_HIT_PLAYERS = false; // false = 挥砍/噩梦不打其它玩家（只打生物），防误伤

// ★ 月镰 / 蚀月**共用**的「自家伤害」闸门
//   打技能伤害前 +1、打完 -1；文件末尾「取消原版近战」的监听器看到 >0 就放行，
//   否则我们自己的技能伤害也会被当成原版近战一起取消掉。
var GS_WPN_DMG_GUARD = 0;

var GS_MS_NM_RAYS = 24;        // 噩梦射线根数
var GS_MS_NM_R    = 5.0;       // 噩梦半径（格）
var GS_MS_NM_Y    = 0.9;       // 射线起点高度：脚底 + 0.9 ≈ 玩家大腿根部
var GS_MS_NM_DMG  = 35.0;      // 噩梦伤害
var GS_MS_NM_LOCK = 3000;      // 定身时长（毫秒）
var GS_MS_NM_COL  = [150, 60, 255];   // 噩梦紫色
var GS_MS_LOCK_STICK = 4.0;    // 定身粒子柱高度（格）
 // ★ 2.2 → 4.0（用户要求"最起码 4 格高"，否则太不显眼）

var GS_MS_REGISTERED = false;
var GS_MS_CLICK_AT   = {};     // uuid|L / uuid|R -> 上次处理的毫秒（去抖）
var GS_MS_SWING_AT   = {};     // uuid -> 上次挥砍毫秒（冷却）
var GS_MS_SIDE       = {};     // uuid -> 0 / 1（左斜 / 右斜交替）
var GS_MS_BARS       = {};     // uuid -> { b: BossBar, p: Player }
// ★★ 层数**以脚本内存为唯一权威**（PDC 只做跨重启备份）
//   —— 原来只依赖玩家 PDC，线上出现「每次攻击都只显示 1/12」（写进去读不回来）。
var GS_MS_ST         = {};     // uuid -> { n, until, full }
var GS_MS_LOCKS      = [];     // 被定身的生物 { e, id, w, x, y, z, until, ai }
var GS_MS_GEN        = -1;     // 心跳世代号（/rsc reload 后旧实例自动停手）

var GS_MS_STAR = null, GS_MS_DUST = null, GS_MS_DUST_TRIED = false;
var GS_MS_VECC = null, GS_MS_LOCC = null, GS_MS_DOC = null, GS_MS_COLORC = null;

var GS_MS_K_DREAM = null, GS_MS_K_UNTIL = null, GS_MS_K_FULL = null, GS_MS_K_GEN = null;
var GS_MS_PDT = null;
try {
    var MS_NK = Java.type('org.bukkit.NamespacedKey');
    // ★★★ 必须 fromString —— new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败
    GS_MS_K_DREAM = MS_NK.fromString('gs:ms_dream');
    GS_MS_K_UNTIL = MS_NK.fromString('gs:ms_until');
    GS_MS_K_FULL  = MS_NK.fromString('gs:ms_full');
    GS_MS_K_GEN   = MS_NK.fromString('gs:ms_gen');
    GS_MS_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

function gsMsNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); }
    catch (e) { try { return new Date().getTime(); } catch (e2) { return 0; } }
}

function gsMsIs(item) { return gsIsItem(item, GS_MS_ID); }

function gsMsHand(p) {
    try { return p.getInventory().getItemInMainHand(); } catch (e) { return null; }
}

function gsMsLoc(world, x, y, z) {
    try {
        if (GS_MS_LOCC == null) GS_MS_LOCC = Java.type('org.bukkit.Location');
        return new GS_MS_LOCC(world, x, y, z);
    } catch (e) { return null; }
}

function gsMsVec(x, y, z) {
    try {
        if (GS_MS_VECC == null) GS_MS_VECC = Java.type('org.bukkit.util.Vector');
        return new GS_MS_VECC(x, y, z);
    } catch (e) { return null; }
}

function gsMsSound(world, loc, name, vol, pitch) {
    try {
        try { world.playSound(loc, name, vol, pitch); }
        catch (e1) { try { loc.getWorld().playSound(loc, name, vol, pitch); } catch (e2) { } }
    } catch (e) { }
}

function gsMsSoundAt(p, name, vol, pitch) {
    try { p.getWorld().playSound(p.getLocation(), name, vol, pitch); } catch (e) { }
}

// ---------- 粒子类型 ----------
function gsMsStarType() {
    if (GS_MS_STAR != null) return GS_MS_STAR;
    try {
        var P = Java.type('org.bukkit.Particle');
        var names = ['FIREWORK', 'FIREWORK_SPARK', 'CRIT', 'END_ROD'];
        for (var i = 0; i < names.length; i++) {
            try { var t = P[names[i]]; if (t != null) { GS_MS_STAR = t; return t; } } catch (e) { }
            try { var t2 = P.valueOf(names[i]); if (t2 != null) { GS_MS_STAR = t2; return t2; } } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function gsMsDustType() {
    if (GS_MS_DUST != null) return GS_MS_DUST;
    if (GS_MS_DUST_TRIED) return null;
    GS_MS_DUST_TRIED = true;
    try {
        var P = Java.type('org.bukkit.Particle');
        var names = ['DUST', 'REDSTONE'];
        for (var i = 0; i < names.length; i++) {
            try { var t = P[names[i]]; if (t != null) { GS_MS_DUST = t; return t; } } catch (e) { }
            try { var t2 = P.valueOf(names[i]); if (t2 != null) { GS_MS_DUST = t2; return t2; } } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// DUST 必须带 DustOptions（1.20.5+ 用 DUST + DustOptions，旧版是 REDSTONE）
function gsMsDustOpts(r, g, b, size) {
    try {
        if (GS_MS_DOC == null) GS_MS_DOC = Java.type('org.bukkit.Particle$DustOptions');
        if (GS_MS_COLORC == null) GS_MS_COLORC = Java.type('org.bukkit.Color');
        var col = GS_MS_COLORC.fromRGB((r | 0) & 255, (g | 0) & 255, (b | 0) & 255);
        var sz = (size == null || size <= 0) ? 1.0 : size;
        return new GS_MS_DOC(col, sz);
    } catch (e) { return null; }
}

function gsMsDust(world, pt, data, x, y, z) {
    try {
        if (pt == null || world == null) return;
        if (data != null) {
            try { world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, data); return; } catch (e) { }
        }
        try { world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
    } catch (e) { }
}

// ---------- 梦：层数读写（★ 内存权威 + PDC 尽力备份） ----------
// ★★ bug「每次攻击都只显示 1/12」：
//     旧版把层数**只**存在玩家 PDC 上（gsMsGet 直接读 PDC、gsMsSet 直接写 PDC），
//     而 PDC 的写入/回读在 GraalJS 这一侧有任何一环静默失败（异常被 try/catch 吞掉），
//     就会变成「每次读回来都是 0」⇒ 每次都从 0 加到 1 ⇒ 标题恒为 1/12。
//     现在改成 **以脚本内存 `GS_MS_ST` 为唯一权威状态**：
//       · 读：内存优先；内存里没有（刚重启）才尝试从 PDC 恢复一次
//       · 写：先写内存（必定成功）⇒ 再尽力写 PDC（失败也不影响逻辑）
//     内存态在同一个 JS 上下文里跨事件/跨心跳都是同一份，稳定可靠。
function gsMsGet(p) {
    var o = { n: 0, until: 0, full: 0 };
    try {
        if (p == null) return o;
        var u = '' + p.getUniqueId();

        // ① 内存优先
        var m = GS_MS_ST[u];
        if (m != null) {
            o.n = m.n | 0; o.until = 0 + m.until; o.full = m.full | 0;
            return o;
        }

        // ② 内存没有（服务器刚重启 / 玩家刚上线）⇒ 试着从 PDC 恢复一次
        if (GS_MS_PDT == null || GS_MS_K_DREAM == null) return o;
        try {
            var pdc = p.getPersistentDataContainer();
            var n = pdc.get(GS_MS_K_DREAM, GS_MS_PDT.INTEGER);
            var un = pdc.get(GS_MS_K_UNTIL, GS_MS_PDT.LONG);
            var f = pdc.get(GS_MS_K_FULL, GS_MS_PDT.INTEGER);
            if (n != null) o.n = n | 0;
            if (un != null) o.until = 0 + un;
            if (f != null) o.full = f | 0;
        } catch (e) { }
        // 只有「层数 > 0 且 计时还没过」才恢复，否则当作没有
        if (o.n > 0 && o.until > gsMsNow()) GS_MS_ST[u] = { n: o.n, until: o.until, full: o.full };
        else { o.n = 0; o.until = 0; o.full = 0; }
        return o;
    } catch (e) { return o; }
}

// JS number → java.lang.Long（显式走 String 重载，避掉 valueOf 的重载歧义）
function gsMsLong(v) {
    try {
        var n = Math.floor(0 + v);
        if (isNaN(n)) n = 0;
        return Java.type('java.lang.Long').valueOf('' + n);
    } catch (e) { return 0; }
}

function gsMsSet(p, n, until, full) {
    try {
        if (p == null) return;
        var u = '' + p.getUniqueId();
        var nn = n | 0, uu = 0 + until, ff = full | 0;

        // ① 写内存（权威）
        if (nn <= 0) { try { delete GS_MS_ST[u]; } catch (e) { } }
        else GS_MS_ST[u] = { n: nn, until: uu, full: ff };

        // ② 尽力写 PDC（只为跨重启保留；任何失败都不影响上面的内存态）
        if (GS_MS_PDT == null || GS_MS_K_DREAM == null) return;
        try {
            var pdc = p.getPersistentDataContainer();
            pdc.set(GS_MS_K_DREAM, GS_MS_PDT.INTEGER, nn);
            pdc.set(GS_MS_K_UNTIL, GS_MS_PDT.LONG, gsMsLong(uu));
            pdc.set(GS_MS_K_FULL,  GS_MS_PDT.INTEGER, ff);
        } catch (e) { }
    } catch (e) { }
}

// ---------- 梦：BossBar ----------
// ★★ 血条标题一律用**传统 § 颜色码**拼成**纯字符串**，再 `setTitle(字符串)`。
//   已核对该服务端（paper-1.21.11-132）的 `CraftBossBar.class` 常量池：
//   里面**完全没有** net.kyori.adventure 的任何引用 ⇒ 它只实现
//   `setTitle(String)`（内部走 NMS `Component.literal`，§ 交给客户端渲染），
//   **不存在** Adventure 的 `title(Component)` 重载。
// ★ 线上事故（复）：把 Adventure `Component` 直接塞进
//     `setTitle(…)` ⇒ GraalJS 隐式 `toString()` 成
//     `TextImpl{obfuscated=not_set, …, color=NamedTextColor{name="red",…}}`
//     一长串原始 dump —— 月镰 / 蚀月的血条标题全变成了那个鬼样子。
//     ⇒ 结论：**BossBar 标题只喂字符串**，需要颜色就写 § 码。
var GS_MS_C = {
    RED: '§c', DARK_RED: '§4', GOLD: '§6', YELLOW: '§e', GREEN: '§a',
    AQUA: '§b', BLUE: '§9', LIGHT_PURPLE: '§d', PURPLE: '§5',
    WHITE: '§f', GRAY: '§7', DARK_GRAY: '§8', BLACK: '§0'
};

function gsMsCode(name) {
    var k = '' + (name == null ? 'WHITE' : name);
    k = k.toUpperCase();
    return GS_MS_C[k] || '§f';
}

// 单色标题（月镰：整条金色）
function gsMsTitle(text) { return GS_MS_C.GOLD + '' + text; }

// 双色标题（蚀月：主体红色 + 后缀金色）；suffix 为空 ⇒ 只有主体色
function gsMsTitle2(text, suffix, mainColorName) {
    var s = gsMsCode(mainColorName) + '' + text;
    if (suffix != null && ('' + suffix).length > 0) s += GS_MS_C.GOLD + '' + suffix;
    return s;
}

// ★ 统一入口：无论上游传进来的是什么，落地前都强制成字符串
function gsBarTitleSet(bar, title) {
    try { if (bar != null) bar.setTitle('' + title); } catch (e) { }
}

// ==========================================================================
// ★★ 血条公共实现（各族的 gsXxBar* 都只是薄封装）
//   月镰 GS_MS_BARS / 蚀月 GS_EC_BARS / 致万千无言者 GS_VO_BARS /
//   此刻万籁俱寂 GS_SI_BARS / 阎魔刀 GS_EM_BARS / 篮球 GS_BB_BARS
//   ★ 只收「逐字节相同、仅表名不同」的三类操作；各族颜色 / 标题 / 进度
//     策略不同（BarColor / BarOf / BarShow），保持各自实现，不强行合并。
// ==========================================================================

// 隐藏血条（不回收，仍留在表里）
function gsBarTblHide(tbl, p) {
    try {
        var rec = tbl['' + p.getUniqueId()];
        if (rec == null || rec.b == null) return;
        try { rec.b.setVisible(false); } catch (e) { }
    } catch (e) { }
}

// 回收血条：removeAll + 隐藏 + 从表里删掉
function gsBarTblDrop(tbl, p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = tbl[u];
        if (rec == null) return;
        try { rec.b.removeAll(); } catch (e) { }
        try { rec.b.setVisible(false); } catch (e) { }
        delete tbl[u];
    } catch (e) { }
}

// 清扫已下线的玩家（防血条对象泄漏）
function gsBarTblClean(tbl) {
    try {
        for (var k in tbl) {
            var rec = tbl[k];
            var on = false;
            try { on = (rec != null && rec.p != null && rec.p.isOnline()); } catch (e) { on = false; }
            if (!on) {
                try { if (rec != null && rec.b != null) rec.b.removeAll(); } catch (e) { }
                try { delete tbl[k]; } catch (e) { }
            }
        }
    } catch (e) { }
}

function gsMsBarColor(full) {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        var name = (full === 1) ? 'RED' : 'PURPLE';
        try { var c = C.valueOf(name); if (c != null) return c; } catch (e) { }
        try { var c2 = C[name]; if (c2 != null) return c2; } catch (e) { }
    } catch (e) { }
    return null;
}

function gsMsBarStyle() {
    try {
        var S = Java.type('org.bukkit.boss.BarStyle');
        try { var s = S.SEGMENTED_12; if (s != null) return s; } catch (e) { }
        try { var s2 = S.valueOf('SEGMENTED_12'); if (s2 != null) return s2; } catch (e) { }
        try { var s3 = S.valueOf('SOLID'); if (s3 != null) return s3; } catch (e) { }
    } catch (e) { }
    return null;
}

function gsMsBarOf(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_MS_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsMsBarColor(0), s = gsMsBarStyle();
        if (c == null || s == null) return null;
        var bar = null;
        // ★ 标题只喂纯字符串（§ 颜色码没问题，见文件上方 gsMsCode 处的说明）
        try { bar = getServer().createBossBar(gsMsTitle('梦 0/' + GS_MS_MAX), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_MS_BARS[u] = { b: bar, p: p };
        return bar;
    } catch (e) { return null; }
}

function gsMsBarShow(p, n, left, total, full) {
    try {
        var b = gsMsBarOf(p);
        if (b == null) return;
        var prog = (total > 0) ? (left / total) : 0;
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
        var nv = n | 0;
 // ★ 标题改金色；满 12/12 时后面补一句「右键释放“噩梦”」
 // ★ 改成 § 纯字符串（Adventure Component 会被 toString 成 dump）
        var txt = '梦 ' + nv + '/' + GS_MS_MAX;
        if (nv >= GS_MS_MAX) txt = txt + ' 右键释放“噩梦”';
        gsBarTitleSet(b, gsMsTitle(txt));
        try { b.setProgress(prog); } catch (e) { }
        try { var col = gsMsBarColor(full); if (col != null) b.setColor(col); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
    } catch (e) { }
}

function gsMsBarHide(p) { gsBarTblHide(GS_MS_BARS, p); }

function gsMsClear(p) {
    try { gsMsSet(p, 0, 0, 0); } catch (e) { }
    try { gsMsBarHide(p); } catch (e) { }
}

// ---------- 梦：加层 / 每拍维护 ----------
function gsMsAdd(p) {
    try {
        var now = gsMsNow();
        var st = gsMsGet(p);
        if (st.n >= GS_MS_MAX) {
            // 已满层：命中只刷新 30 秒倒计时，层数不再增加
            gsMsSet(p, GS_MS_MAX, now + GS_MS_FULL_WIN, 1);
            gsMsBarShow(p, GS_MS_MAX, GS_MS_FULL_WIN, GS_MS_FULL_WIN, 1);
            return;
        }
        var n = st.n + 1;
        if (n >= GS_MS_MAX) {
            gsMsSet(p, GS_MS_MAX, now + GS_MS_FULL_WIN, 1);
            gsMsBarShow(p, GS_MS_MAX, GS_MS_FULL_WIN, GS_MS_FULL_WIN, 1);
            gsMsSoundAt(p, 'block.beacon.activate', 0.9, 1.4);
        } else {
            gsMsSet(p, n, now + GS_MS_WINDOW, 0);
            gsMsBarShow(p, n, GS_MS_WINDOW, GS_MS_WINDOW, 0);
        }
    } catch (e) { }
}

function gsMsPlayerTick(p) {
    try {
        var st = gsMsGet(p);
        if (st.n <= 0) { gsMsBarHide(p); return; }
        var total = (st.full === 1) ? GS_MS_FULL_WIN : GS_MS_WINDOW;
        var now = gsMsNow();
        // ★ 自愈：层数在、但计时丢了（重启用 PDC 恢复失败等）⇒ 按当前阶段补一份，不直接清零
        if (st.until <= 0) {
            gsMsSet(p, st.n, now + total, st.full);
            gsMsBarShow(p, st.n, total, total, st.full);
            return;
        }
        var left = st.until - now;
        if (left <= 0) {
            gsMsClear(p);
            gsMsSoundAt(p, 'block.beacon.deactivate', 0.6, 1.4);
            return;
        }
        gsMsBarShow(p, st.n, left, total, st.full);
    } catch (e) { }
}

// ---------- 挥砍 ----------
function gsMsCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !GS_MS_HIT_PLAYERS) return false;
        if (tn === 'ARMOR_STAND') return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ★ 只掉血 —— **不击退、不留无敌帧**
//   · 击退：先存 velocity，打完立刻还原（同 tick 内还原 ⇒ 位移被抵消）
//   · 无敌帧：打前 setNoDamageTicks(0) 保证打得动；**打后再清一次**，不留 iframe 给后面吃
//   · GS_WPN_DMG_GUARD++：让「取消原版近战」监听器放行（见文件末尾）
function gsMsHurt(ent, dmg, p) {
    try {
        if (ent == null) return;
        try { ent.setNoDamageTicks(0); } catch (e) { }
        var vx = 0.0, vy = 0.0, vz = 0.0, has = false;
        try {
            var v = ent.getVelocity();
            vx = v.getX(); vy = v.getY(); vz = v.getZ();
            has = true;
        } catch (e) { has = false; }
        GS_WPN_DMG_GUARD++;
        try {
            try { if (p != null) ent.damage(dmg, p); else ent.damage(dmg); }
            catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
        } finally { GS_WPN_DMG_GUARD--; }
        if (has) { try { ent.setVelocity(gsMsVec(vx, vy, vz)); } catch (e) { } }
        try { ent.setNoDamageTicks(0); } catch (e) { }     // ★ 打完不留无敌帧
    } catch (e) { }
}

// 斜劈粒子：一次左斜、一次右斜（星星粒子）
function gsMsSlashFx(world, loc, fw, rt, side) {
    try {
        var pt = gsMsStarType();
        if (pt == null) return;
        var sgn = (side === 0) ? 1.0 : -1.0;
        var n = 14;
        for (var i = 0; i <= n; i++) {
            var t = i / n;
            var f = 0.6 + 2.4 * t;                 // 前向 0.6 → 3.0 格
            var s = sgn * (1.50 - 3.00 * t);       // 横向：从 +1.5 到 -1.5（正好 3 格宽）
            var h = 1.75 - 1.30 * t;               // 高度：1.75 → 0.45（斜着往下劈）
            try {
                world.spawnParticle(pt,
                    loc.getX() + fw.x * f + rt.x * s,
                    loc.getY() + h,
                    loc.getZ() + fw.z * f + rt.z * s,
                    1, 0.0, 0.0, 0.0, 0.0);
            } catch (e) { }
        }
    } catch (e) { }
}

function gsMsSwing(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_MS_SWING_AT[u];
        if (last != null && (now - last) < GS_MS_SWING_CD) return;
        GS_MS_SWING_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return; }

        // 水平前向 + 右向（用于摆斜劈的轨迹）
        var fx = dir.getX(), fz = dir.getZ();
        var fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0e-4) { fx = 0.0; fz = 1.0; fl = 1.0; }
        fx /= fl; fz /= fl;
        var fw = { x: fx, z: fz };
        var rt = { x: -fz, z: fx };

        var side = (GS_MS_SIDE[u] == null) ? 0 : (GS_MS_SIDE[u] | 0);
        GS_MS_SIDE[u] = (side === 0) ? 1 : 0;      // ★ 左斜 / 右斜交替

        var pt = gsMsStarType();
        if (pt != null) {
            try { world.spawnParticle(pt, loc.getX() + fw.x * 1.2, loc.getY() + 1.2, loc.getZ() + fw.z * 1.2, 4, 0.3, 0.3, 0.3, 0.0); } catch (e) { }
        }
        gsMsSlashFx(world, loc, fw, rt, side);
        gsMsSound(world, loc, 'entity.player.attack.sweep', 0.75, 1.25);

 // ★ 命中范围 = **前方 3 格长 × 3 格宽**的矩形柱
        //   （原先是"沿视线每 0.6 格采一个 1.8 粗的柱"，等于一条线 ⇒ 打不到侧前方的怪）
        //   判定用**水平**前向 / 右向做投影；高度上下各 ±GS_MS_TALL（怪身体中心 vs 玩家胸口）
        var selfId = u;
        var hit = {};
        var count = 0;
        var rx = -fz, rz = fx;                     // 水平右向
        var halfW = GS_MS_WIDE * 0.5;              // 左右各 1.5 格
        var px0 = loc.getX(), py0 = loc.getY(), pz0 = loc.getZ();
        var cbox = gsMsLoc(world, px0, py0 + 1.0, pz0);
        if (cbox != null) {
            // 搜索盒：把整个 3×3 区域包进来（对角最远 √(3²+1.5²)≈3.35 ⇒ 取 3.8 保险）
            var list = null;
            try { list = world.getNearbyEntities(cbox, GS_MS_REACH + 0.8, GS_MS_TALL, GS_MS_REACH + 0.8); } catch (e) { list = null; }
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (!gsMsCanHit(ent, selfId)) continue;
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                    if (hit[eid] != null) continue;
                    var el = null;
                    try { el = ent.getLocation(); } catch (e) { continue; }
                    var dx = el.getX() - px0, dz = el.getZ() - pz0;
                    var fwd = dx * fx + dz * fz;                  // 前向投影（长度）
                    var lat = dx * rx + dz * rz;                  // 左右投影（宽度）
                    if (fwd < -0.4 || fwd > GS_MS_REACH) continue;     // 前方 3 格（身后 0.4 容差）
                    if (lat < -halfW || lat > halfW) continue;         // 左右各 1.5 格
                    if (Math.abs((el.getY() + 0.9) - (py0 + 1.0)) > GS_MS_TALL) continue;
                    hit[eid] = 1;
                    gsMsHurt(ent, GS_MS_DMG, p);
                    count++;
                }
            }
        }
        // ★ 命中 >= 1 个生物 ⇒ 叠一层「梦」
        if (count > 0) gsMsAdd(p);
    } catch (e) { }
}

// ---------- 噩梦 ----------
// ★ 定身机制参数化（时长 / 粒子柱颜色都可传）——
//     蚀月·死神梦镰的「蚀月轮」也复用同一套定身，时长 0.5 秒、不画柱。
function gsMsLock(ent) { gsMsLockFor(ent, GS_MS_NM_LOCK, GS_MS_NM_COL); }

function gsMsLockFor(ent, ms, col) {
    try {
        if (ent == null) return;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return; }
        var now = gsMsNow();
        for (var i = 0; i < GS_MS_LOCKS.length; i++) {
            if (GS_MS_LOCKS[i].id === id) {
                // 已在锁定名单里 ⇒ 只刷新到期时间（位置钉在**第一次**锁定的那一点）
                GS_MS_LOCKS[i].until = now + ms;
                if (col != null) GS_MS_LOCKS[i].col = col;
                return;
            }
        }
        var l = null;
        try { l = ent.getLocation(); } catch (e) { return; }
        var ai = false;
        try { ai = ent.hasAI(); } catch (e) { ai = false; }
        try { if (typeof ent.setAI === 'function') ent.setAI(false); } catch (e) { }
        try { ent.setVelocity(gsMsVec(0.0, 0.0, 0.0)); } catch (e) { }
        GS_MS_LOCKS.push({ e: ent, id: id, w: l.getWorld(), x: l.getX(), y: l.getY(), z: l.getZ(),
                           until: now + ms, ai: ai, col: col });
    } catch (e) { }
}

function gsMsLockTick() {
    try {
        if (GS_MS_LOCKS.length === 0) return;
        var now = gsMsNow();
        var keep = [];
        var dust = gsMsDustType();
        for (var i = 0; i < GS_MS_LOCKS.length; i++) {
            var r = GS_MS_LOCKS[i];
            var alive = false;
            try { alive = !(r.e.isDead() || !r.e.isValid()); } catch (e) { alive = false; }
            if (!alive || now >= r.until) {
                try { if (typeof r.e.setAI === 'function') r.e.setAI(r.ai === true); } catch (e) { }
                continue;
            }
            // 钉在原位（teleport 才拉得住，setVelocity 会被 AI 顶回去）
            var back = gsMsLoc(r.w, r.x, r.y, r.z);
            if (back != null) { try { r.e.teleport(back); } catch (e) { } }
            try { r.e.setVelocity(gsMsVec(0.0, 0.0, 0.0)); } catch (e) { }
            // 粒子柱（颜色随记录走；col == null ⇒ 只定身、不画柱）
            if (dust != null && r.col != null) {
                var data = gsMsDustOpts(r.col[0], r.col[1], r.col[2], 1.2);
                for (var h = 0.0; h <= GS_MS_LOCK_STICK + 0.01; h += 0.2) {
                    gsMsDust(r.w, dust, data, r.x, r.y + h, r.z);
                }
            }
            keep.push(r);
        }
        GS_MS_LOCKS = keep;
    } catch (e) { }
}

// 旧实例被顶替时，先把自己锁住的生物放掉（避免 reload 后永久定身）
function gsMsReleaseAll() {
    try {
        for (var i = 0; i < GS_MS_LOCKS.length; i++) {
            var r = GS_MS_LOCKS[i];
            try { if (typeof r.e.setAI === 'function') r.e.setAI(r.ai === true); } catch (e) { }
        }
        GS_MS_LOCKS = [];
        var k;
        for (k in GS_MS_BARS) {
            try { GS_MS_BARS[k].b.removeAll(); } catch (e) { }
            try { delete GS_MS_BARS[k]; } catch (e) { }
        }
    } catch (e) { }
}

function gsMsNightmare(p) {
    try {
        var st = gsMsGet(p);
        if (st.n < GS_MS_MAX) return false;        // 未满 12 层 ⇒ 右键不做任何事
        gsMsClear(p);                              // 先清空，防重复触发

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return true; }
        var world = p.getWorld();
        var bx = loc.getX();
        var by = loc.getY() + GS_MS_NM_Y;          // ★ 大腿根部
        var bz = loc.getZ();

        // ① 24 根紫色射线（水平均分，半径 5 格）
        var pt = gsMsDustType();
        var data = gsMsDustOpts(GS_MS_NM_COL[0], GS_MS_NM_COL[1], GS_MS_NM_COL[2], 1.0);
        if (pt != null) {
            for (var i = 0; i < GS_MS_NM_RAYS; i++) {
                var a = (Math.PI * 2.0 * i) / GS_MS_NM_RAYS;
                var dx = Math.cos(a), dz = Math.sin(a);
                for (var d = 0.4; d <= GS_MS_NM_R + 0.01; d += 0.3) {
                    gsMsDust(world, pt, data, bx + dx * d, by, bz + dz * d);
                }
            }
        }

        gsMsSound(world, loc, 'entity.warden.sonic_boom', 1.5, 0.8);
        gsMsSound(world, loc, 'entity.ender_dragon.growl', 1.2, 1.2);

        // ② 半径 5 格内的生物：35 点伤害 + 定身
        var selfId = '' + p.getUniqueId();
        var box = gsMsLoc(world, bx, by, bz);
        var list = null;
        try { list = world.getNearbyEntities(box, GS_MS_NM_R, 2.5, GS_MS_NM_R); } catch (e) { list = null; }
        if (list != null) {
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (!gsMsCanHit(ent, selfId)) continue;
                var el = null;
                try { el = ent.getLocation(); } catch (e) { continue; }
                var hx = el.getX() - bx, hz = el.getZ() - bz;
                if (Math.sqrt(hx * hx + hz * hz) > GS_MS_NM_R) continue;   // 圆形判定，不是方盒
                if (Math.abs(el.getY() - by) > 2.5) continue;
                gsMsHurt(ent, GS_MS_NM_DMG, p);
                gsMsLock(ent);
            }
        }
        return true;
    } catch (e) { return false; }
}

// ---------- 心跳 ----------
// ★ 返回 null 表示「读不到 / 无法判断」——调用方据此**不要**把当前实例当成旧实例，
//   否则 PDC 一旦不可用，世代号校验会把心跳整个停掉（血条/定身都再不维护）。
function gsMsGenNow() {
    try {
        if (GS_MS_PDT == null || GS_MS_K_GEN == null) return null;
        var w = getServer().getWorlds().get(0);
        var v = w.getPersistentDataContainer().get(GS_MS_K_GEN, GS_MS_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}

function gsMsGenBump() {
    try {
        var cur = gsMsGenNow();
        if (cur == null) cur = 0;                  // 读不到就从 0 起算
        GS_MS_GEN = (cur | 0) + 1;
        if (GS_MS_PDT == null || GS_MS_K_GEN == null) return;
        var w = getServer().getWorlds().get(0);
        w.getPersistentDataContainer().set(GS_MS_K_GEN, GS_MS_PDT.INTEGER, GS_MS_GEN);
    } catch (e) { if (GS_MS_GEN == null || GS_MS_GEN <= 0) GS_MS_GEN = 1; }
}

function gsMsBarsClean() { gsBarTblClean(GS_MS_BARS); }

function gsMsPulse() {
    var mine = true;
    try {
        var g = gsMsGenNow();
        // ★ 只有「确实读到了世代号，且和我的不一样」才认为自己是旧实例；
        //   读不到（null）时一律按自己的算，保证心跳永远在跑。
        if (g != null && GS_MS_GEN !== -1) mine = (g === GS_MS_GEN);
    } catch (e) { }
    if (!mine) {
        // /rsc reload 后旧实例：把自己的定身和血条收干净，然后彻底停手
        gsMsReleaseAll();
        return;
    }
    try {
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsMsPlayerTick(p); } catch (e2) { }
            try { gsEcPlayerTick(p); } catch (e2b) { }     // ★ 第十五批：蚀月「血」血条
        }
    } catch (e) { }
    try { gsMsLockTick(); } catch (e) { }
    try { gsMsBarsClean(); } catch (e) { }
    try { gsEcBarsClean(); } catch (e) { }                 // ★ 第十五批：蚀月血条清理
}

// ---------- 去抖 ----------
function gsMsClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_MS_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_MS_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

function gsMsRecentRight(p, gapMs) {
    try {
        var last = GS_MS_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsMsNow() - last) < gapMs;
    } catch (e) { return false; }
}

// ---------- 注册同步监听器 ----------
function gsMsRegisterSync() {
    if (GS_MS_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ① 左键 = 挥砍（同时挖不动方块）；右键 = 满 12 层放噩梦（其余情况只取消原版交互）
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    // ★ 故意不检查 e.isCancelled()：有些前置插件会先取消 *_CLICK_AIR，
                    //   一检查就变成"必须点方块才放得出来"。重复注册交给去抖。
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsMsIs(gsMsHand(p))) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★ 取消右键交互（铁锄不许耕地、不许开箱子 / 放方块）
                        e.setCancelled(true);
                        if (Res != null) {
                            try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                            try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                        }
                        if (!gsMsClickOnce(p, 'R')) return;
                        gsMsNightmare(p);                                  // 未满 12 层时内部直接返回
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsMsClickOnce(p, 'L')) return;
                        if (GS_MS_NO_BREAK) {
                            e.setCancelled(true);
                            if (Res != null) {
                                try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                                try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                            }
                        }
                        gsMsSwing(p);
                    }
                } catch (e3) { }
            });
        }

        // ② 左键兜底：挥手动画（对着空气点也会发；与 ① 共用去抖 ⇒ 不会放两次）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsMsIs(gsMsHand(p))) return;
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsMsRecentRight(p, 300)) return;    // 右键附带的挥手 → 忽略
                    if (!gsMsClickOnce(p, 'L')) return;
                    gsMsSwing(p);
                } catch (e3) { }
            });
        }

        // ③ 手持月镰 → 挖不动方块（双保险：即使 ① 的取消被别的插件放行也挖不掉）
        if (GS_MS_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsMsIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
            var DmgBlkEv = null;
            try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
            if (DmgBlkEv != null) {
                reg(DmgBlkEv, function (e) {
                    try {
                        if (!gsMsIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

        GS_MS_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 月镰监听器注册成功（左键挥砍叠梦 / 满 12 层右键噩梦）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 月镰监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsMsStart() {
    try {
        gsMsGenBump();
        if (!gsMsRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 月镰启动失败：监听器没注册上'); } catch (e) { }
            return;
        }
        runRepeating(gsMsPulse, 20, 2);      // 每 2 刻（0.1 秒）维护血条 / 定身 / 清理
        try {
            var pdcOk = (GS_MS_PDT != null) && (GS_MS_K_DREAM != null) && (GS_MS_K_UNTIL != null) && (GS_MS_K_FULL != null);
            getServer().getLogger().info('[构思技艺] 月镰状态：层数存内存（权威）· PDC 备份 '
                + (pdcOk ? '可用' : '不可用') + ' · 世界世代号 ' + GS_MS_GEN);
        } catch (e) { }
        try {
            getServer().getLogger().info('[构思技艺] 月镰（' + GS_MS_ID + '）已加载 · 挥砍 '
                + GS_MS_DMG + ' 点 / 范围 ' + GS_MS_REACH + ' 格长 × ' + GS_MS_WIDE + ' 格宽 / 满 '
                + GS_MS_MAX + ' 层 / 噩梦 ' + GS_MS_NM_DMG
                + ' 点 · 半径 ' + GS_MS_NM_R + ' 格 / 定身 ' + (GS_MS_NM_LOCK / 1000) + ' 秒');
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 月镰启动异常: ' + e); } catch (e2) { }
    }
}

gsMsStart();

// ==========================================================================
// 蚀月·死神梦镰（GS_ECLIPSE_SCYTHE · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §9
// ==========================================================================
var GS_EC_ID         = 'GS_ECLIPSE_SCYTHE';
var GS_EC_NO_BREAK   = true;        // true = 手持蚀月挖不动方块
var GS_EC_HIT_PLAYERS = false;      // false = 蚀月轮 / 审判不打其它玩家

// ---- 蚀月轮 ----
var GS_EC_W_RANGE    = 5.0;         // 射程（格，**三维距离**：能朝天上打）
var GS_EC_W_R        = 1.0;         // 圆环半径（约 1 格）
var GS_EC_W_STEP     = 0.5;         // ★ 第三十五批：每刻前进（格）—— 原 0.35 ⇒ 弹速快约 43%
                                    //   （射程 5 格：原来飞 0.7 秒，现在约 0.5 秒；
                                    //    只要 < 1.0 就不会一步跨过整块墙，命中判定半径 1.0 也不会漏怪）
var GS_EC_W_DMG      = 14.0;        // ★ 第十六批：每次伤害脉冲的伤害 = 14 点
var GS_EC_W_PULSE    = 500;         // ★ 第三十五批：每 **0.5 秒**造成一次伤害（毫秒）—— 原 1 秒
var GS_EC_W_LOCK     = 600;         // ★ 第三十五批：命中定身（毫秒）= **0.6 秒** —— 原 0.5 秒
                                    //   （不能太长：定身若 ≥ 出伤间隔又无间隔限制，被打的生物**永远**动不了；
                                    //    本模块靠 GS_EC_W_LOCK_CD 兜底 —— 同目标 3 秒内只定一次）
var GS_EC_W_LOCK_CD  = 3000;        // ★ 第十九批：同一目标两次定身之间的**间隔**（毫秒）= 3 秒
                                    //   第一次命中照样定身 0.6 秒；之后 3 秒内再打中同一目标
                                    //   ⇒ **只掉血、不再定身**（避免"每 0.5 秒定身 0.6 秒"把怪钉死）
                                    //   ★ 间隔过后再命中 ⇒ 重新定身 0.6 秒
var GS_EC_W_H        = 1.8;         // ★ 伤害判定高度（圆环平面 ± 格）
var GS_EC_W_STACK_PER_HIT = false;  // false = 每次伤害脉冲 +1 层「血」（默认）
                                    // true  = 每个被命中的生物各 +1 层
var GS_EC_W_LIFE     = 1500;        // ★ 第三十五批：停下（落地）后原地存在（毫秒）= **1.5 秒**（原 3 秒）
                                    //   ⇒ 配合 GS_EC_W_PULSE=500 ⇒ 落地后共结算 **3 发**伤害脉冲
var GS_EC_W_CD       = 1200;        // 发射冷却（毫秒）= 1.2 秒（★ 第十九批：1 秒 → 1.2 秒；第三十五批确认保持）
var GS_EC_W_GAP      = 0.35;        // 圆环点距（越小越密）
var GS_EC_W_COL      = [235, 30, 40];   // 蚀月轮红色

// ---- 普通左键（近战挥砍）★ ----
//   贴脸按左键时**原版近战**会先打到敌人（并把它击退 / 触发受击无敌帧），
//   等蚀月轮停下打第一发脉冲时，那只怪往往已经出了环外 / 已死 ⇒ hit=0 ⇒ 不叠层。
//   这里给普通左键补一次命中判定：**前方 3 格长 × 3 格宽**内有可命中的生物就 +1 层「血」。
//   ★ 只叠层、**不额外造成伤害**（原版左键本身已经打了），避免贴脸时伤害三连爆。
var GS_EC_SW_HIT      = true;       // 普通左键命中是否叠层（false = 关掉）
var GS_EC_SW_R        = 3.0;        // 判定长度：前方 3 格
var GS_EC_SW_W        = 3.0;        // 判定宽度：共 3 格宽（左右各 1.5）
var GS_EC_SW_H        = 1.6;        // 判定上下高度（格）

// ---- 「血」----
var GS_EC_MAX        = 23;          // 满层
var GS_EC_WINDOW     = 7000;        // 未满层窗口（毫秒）= 7 秒
var GS_EC_FULL_WIN   = 35000;       // 满层倒计时（毫秒）= 35 秒
var GS_EC_BLOOD_COL  = 'RED';       // 「血」的血条颜色 + 标题主色 = 红

// ---- 审判 ----
var GS_EC_J_R        = 8.0;         // 法阵半径（格）
var GS_EC_J_TICKS    = 36;          // ★ 第三十五批：法阵持续 / **蓄力读条**（刻）= **1.8 秒**
 // （120→60 = 3 秒；本批 60→36 = 1.8 秒 ⇒ 蓄力结束才出伤）
var GS_EC_J_DMG      = 233.0;       // 蓄力倒计时结束时从天而降的伤害
var GS_EC_J_INV      = 36;          // ★ 第三十五批：无敌时长（刻）= **1.8 秒**（与蓄力同步解除）
var GS_EC_J_COL_R    = [235, 40, 40];     // 法阵红
var GS_EC_J_COL_P    = [150, 60, 255];    // 法阵紫
var GS_EC_J_COL_W    = [245, 245, 245];   // 法阵白
var GS_EC_J_COL_B    = [70, 110, 255];    // ★ 第二十批：法阵蓝（审判圆柱粒子新增）
var GS_EC_J_MAX_TARGETS = 60;             // ★ 第七十批：审判**最多**砸几个目标（安全阀）
                                          //   每根光柱 ≈ 212 个粒子（23 层 × 8 + 落地 28）
                                          //   ⇒ 上限 60 根 ≈ 1.3 万粒子，且每根**错开 2 毫秒**
                                          //   （gsEcSmite 里 n*2）⇒ 摊在 ~120 毫秒里，不会一 tick 全爆。
                                          //   ★ 掉落物已被 gsEcCanHit 挡掉，这里只防"怪真的特别多"。

var GS_EC_REGISTERED = false;
var GS_EC_CLICK_AT   = {};          // uuid|L / uuid|R -> 上次处理毫秒（去抖）
var GS_EC_LOCK_AT    = {};          // ★ 第十九批：uuid -> 上次「蚀月轮定身」的毫秒（间隔 GS_EC_W_LOCK_CD）
var GS_EC_FIRE_AT    = {};          // uuid -> 上次发射蚀月轮的毫秒（冷却）
var GS_EC_ST         = {};          // uuid -> { n, until, full }（内存权威）
var GS_EC_BARS       = {};          // uuid -> { b, p }
var GS_EC_JUDGING    = {};          // uuid -> true（审判进行中）

var GS_EC_K_BLOOD = null, GS_EC_K_UNTIL = null, GS_EC_K_FULL = null, GS_EC_PDT = null;
try {
    var EC_NK = Java.type('org.bukkit.NamespacedKey');
    // ★★★ 必须 fromString（new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败）
    GS_EC_K_BLOOD = EC_NK.fromString('gs:ec_blood');
    GS_EC_K_UNTIL = EC_NK.fromString('gs:ec_until');
    GS_EC_K_FULL  = EC_NK.fromString('gs:ec_full');
    GS_EC_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

var GS_EC_PET = null, GS_EC_PE = null;
try { GS_EC_PET = Java.type('org.bukkit.potion.PotionEffectType'); } catch (e) { GS_EC_PET = null; }
try { GS_EC_PE = Java.type('org.bukkit.potion.PotionEffect'); } catch (e) { GS_EC_PE = null; }

function gsEcIs(item) { return gsIsItem(item, GS_EC_ID); }

function gsEcHand(p) {
    try { return p.getInventory().getItemInMainHand(); } catch (e) { return null; }
}

// ---------- 命中判定 / 无击退伤害 ----------
function gsEcCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !GS_EC_HIT_PLAYERS) return false;
        if (tn === 'ARMOR_STAND') return false;
 // ★★★ **非活物不算命中**（详见文件头「」那一段）。
        //   ★ 为什么下面那行 getHealth 探针不够：
        //     Paper 1.21.11 的 `org.bukkit.entity.Item` **自己声明了 getHealth()**
        //     （不实现 Damageable，但方法在）⇒ **掉落物能骗过探针** ⇒
        //     审判会对着一地的掉落物逐个降光柱（每根 ≈ 212 粒子）⇒ 卡服。
 // 统一走那套 `gsIsCombatTarget`
        //   （① 显式黑名单 GS_NOT_ENEMY_TYPES，② LivingEntity.class.isInstance）。
        //   ★ 必须排在 PLAYER / ARMOR_STAND 之后、getHealth 探针之前。
        if (!gsIsCombatTarget(ent, tn)) return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// ★ 只掉血、不产生任何击退（存 velocity → 打完立刻还原）
// ★ **也不留无敌帧**（打后再清一次），并加「自家伤害」闸门
function gsEcHurtNoKb(ent, dmg, p) {
    try {
        if (ent == null) return;
        try { ent.setNoDamageTicks(0); } catch (e) { }
        var vx = 0.0, vy = 0.0, vz = 0.0, has = false;
        try {
            var v = ent.getVelocity();
            vx = v.getX(); vy = v.getY(); vz = v.getZ();
            has = true;
        } catch (e) { has = false; }
        GS_WPN_DMG_GUARD++;
        try {
            try { if (p != null) ent.damage(dmg, p); else ent.damage(dmg); }
            catch (e1) { try { ent.damage(dmg); } catch (e2) { } }
        } finally { GS_WPN_DMG_GUARD--; }
        if (has) { try { ent.setVelocity(gsMsVec(vx, vy, vz)); } catch (e) { } }
        try { ent.setNoDamageTicks(0); } catch (e) { }     // ★ 打完不留无敌帧
    } catch (e) { }
}

// ---------- 圆环绘制（法向量 = Y 轴 ⇒ 水平圆） ----------
function gsEcRingAt(world, pt, data, cx, cy, cz, r, gap) {
    try {
        if (pt == null) return;
        var g = (gap == null || gap <= 0) ? 0.4 : gap;
        var n = Math.round((2.0 * Math.PI * r) / g);
        if (n < 8) n = 8;
        if (n > 200) n = 200;
        for (var i = 0; i < n; i++) {
            var a = (2.0 * Math.PI * i) / n;
            gsMsDust(world, pt, data, cx + Math.cos(a) * r, cy, cz + Math.sin(a) * r);
        }
    } catch (e) { }
}

// ---------- 普通左键（近战挥砍）：叠「血」 ----------
// ★ 修「贴脸按左键时，蚀月轮第一发脉冲不叠层」——
//     原版近战先把怪打了（击退 + 受击无敌帧），等蚀月轮停下打脉冲时
//     那只怪常常已经出了环外 / 已死 ⇒ hit=0 ⇒ 不叠层。
//     这里给普通左键补一次命中判定：**前方 3 格长 × 3 格宽**内有可命中的生物 ⇒ +1 层「血」。
//   ★ 只叠层、**不额外造成伤害**（原版左键本身已经打了），免得贴脸时伤害三连爆。
function gsEcMelee(p) {
    try {
        if (!GS_EC_SW_HIT) return 0;
        if (p == null) return 0;
        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return 0; }
        if (loc == null) return 0;
        var world = null;
        try { world = p.getWorld(); } catch (e) { return 0; }
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return 0; }
        var fx = dir.getX(), fz = dir.getZ();
        var fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0e-4) return 0;                  // 垂直看天/看地 ⇒ 谈不上"水平 3 格"
        fx /= fl; fz /= fl;
        var rx = -fz, rz = fx;                      // 水平右向
        var halfW = GS_EC_SW_W * 0.5;
        var px0 = loc.getX(), py0 = loc.getY(), pz0 = loc.getZ();
        var selfId = '' + p.getUniqueId();
        var cbox = gsMsLoc(world, px0, py0 + 1.0, pz0);
        if (cbox == null) return 0;
        var list = null;
        try { list = world.getNearbyEntities(cbox, GS_EC_SW_R + 0.8, GS_EC_SW_H, GS_EC_SW_R + 0.8); } catch (e) { list = null; }
        if (list == null) return 0;
        var n = 0;
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (!gsEcCanHit(ent, selfId)) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }
            var dx = el.getX() - px0, dz = el.getZ() - pz0;
            var fwd = dx * fx + dz * fz;             // 前向投影（长度）
            var lat = dx * rx + dz * rz;             // 左右投影（宽度）
            if (fwd < -0.4 || fwd > GS_EC_SW_R) continue;
            if (lat < -halfW || lat > halfW) continue;
            if (Math.abs((el.getY() + 0.9) - (py0 + 1.0)) > GS_EC_SW_H) continue;
            n++;
        }
        if (n > 0) gsEcAdd(p);
        return n;
    } catch (e) { return 0; }
}

// ---------- 蚀月轮 ----------
function gsEcWheel(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_EC_FIRE_AT[u];
        if (last != null && (now - last) < GS_EC_W_CD) return;     // 冷却 1.2 秒（第十九批）
        GS_EC_FIRE_AT[u] = now;

 // ★ 普通左键（近战挥砍）命中 ⇒ 先叠一层「血」
        //   贴脸时原版近战会把怪打退，蚀月轮第一发脉冲常落空 ⇒ 用这一层兜住
        gsEcMelee(p);

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return; }

 // ★ 飞行方向 = 准星方向的**完整三维向量**（含俯仰）
        //   ⇒ 抬头就能把环打到天上去（"水平"指的是**环本身恒为水平圆**，
        //     不是只能水平投出 —— 圆环的平面永远平行于地面，见 gsEcRingAt）
        var dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();
        var dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0e-4) { dx = 0.0; dy = 0.0; dz = 1.0; dl = 1.0; }
        dx /= dl; dy /= dl; dz /= dl;

        var selfId = u;
        // 起点：齐腰高度 + 朝准星方向前移 0.6 格（抬头打 → 起点也抬高 y）
        var cx = loc.getX() + dx * 0.6;
        var cy = loc.getY() + 1.1 + dy * 0.6;
        var cz = loc.getZ() + dz * 0.6;
        var travelled = 0.0;

        var pt = gsMsDustType();
        var data = gsMsDustOpts(GS_EC_W_COL[0], GS_EC_W_COL[1], GS_EC_W_COL[2], 1.0);

        gsMsSound(world, loc, 'entity.evoker.cast_spell', 0.9, 1.3);

        function step() {
            try {
                if (p == null) return;
                var online = true;
                try { online = p.isOnline(); } catch (e) { online = true; }
                if (!online) return;

                travelled += GS_EC_W_STEP;
                gsEcRingAt(world, pt, data, cx, cy, cz, GS_EC_W_R, GS_EC_W_GAP);

                // ① 飞出去的路上**碰到生物就停**（停下后才开始每秒伤害）
                var touch = false;
                var box = gsMsLoc(world, cx, cy, cz);
                if (box != null) {
                    var list = null;
                    try { list = world.getNearbyEntities(box, GS_EC_W_R, GS_EC_W_H, GS_EC_W_R); } catch (e) { list = null; }
                    if (list != null) {
                        var it = list.iterator();
                        while (it.hasNext()) {
                            var ent = it.next();
                            if (!gsEcCanHit(ent, selfId)) continue;
                            var el = null;
                            try { el = ent.getLocation(); } catch (e) { continue; }
                            var hx = el.getX() - cx, hz = el.getZ() - cz;
                            if (Math.sqrt(hx * hx + hz * hz) > GS_EC_W_R) continue;
                            if (Math.abs((el.getY() + 0.9) - cy) > GS_EC_W_H) continue;
                            touch = true;
                            break;
                        }
                    }
                }

                var stop = false;
                if (touch) {
                    stop = true;                            // 撞到生物：停下
                } else if (travelled >= GS_EC_W_RANGE) {
                    stop = true;                            // 到射程尽头
                } else {
                    // ② 下一格是不是墙？（不穿墙 ⇒ 撞上就停）
                    var nx = cx + dx * GS_EC_W_STEP;
                    var ny = cy + dy * GS_EC_W_STEP;
                    var nz = cz + dz * GS_EC_W_STEP;
                    var nb = gsMsLoc(world, nx, ny, nz);
                    var solid = false;
                    if (nb != null) {
                        try {
                            var blk = nb.getBlock();
                            if (blk != null && blk.getType() != null && blk.getType().isSolid()) solid = true;
                        } catch (e) { }
                    }
                    if (solid) stop = true;
                    else { cx = nx; cy = ny; cz = nz; }
                }

                if (stop) {
                    gsEcHold(world, pt, data, p, selfId, cx, cy, cz, GS_EC_W_LIFE);
                    return;
                }
                runLater(step, 1);
            } catch (e) { }
        }

        runLater(step, 1);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 蚀月轮出错: ' + e); } catch (e2) { }
    }
}

// ---------- 「蚀月轮」定身间隔（★ ）----------
//   需求：**只有第一次命中敌人时才有定身**，之后 3 秒内再打中同一目标**只掉血、不定身**；
// 3 秒过后重新命中 ⇒ 再定身 0.6 秒（0.5 → 0.6）。
//   实现：给每个被定身过的目标记一个「上次定身时刻」（GS_EC_LOCK_AT），
//         距上次不足 GS_EC_W_LOCK_CD（3 秒）就**跳过这次定身**（伤害照常结算）。
//   ★ 表很小（只放最近被定身的目标），每发脉冲顺手清掉过期条目，不会攒内存。
function gsEcLockPrune(now) {
    try {
        var keep = {};
        for (var k in GS_EC_LOCK_AT) {
            if ((now - (0 + GS_EC_LOCK_AT[k])) < GS_EC_W_LOCK_CD) keep[k] = 0 + GS_EC_LOCK_AT[k];
        }
        GS_EC_LOCK_AT = keep;
    } catch (e) { }
}

// 命中一次：3 秒内只定身一次。返回 true = 本次真的定了身。
function gsEcLockOnce(ent, now) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === '') return false;
        var last = GS_EC_LOCK_AT[id];
        if (last != null && (now - (0 + last)) < GS_EC_W_LOCK_CD) return false;   // 定身间隔内
        GS_EC_LOCK_AT[id] = now;
        gsMsLockFor(ent, GS_EC_W_LOCK, null);
        return true;
    } catch (e) { return false; }
}

// 一次伤害脉冲：圆环平面 ±GS_EC_W_H 内**所有**生物 —— 14 点伤害 + 定身 0.6 秒 + 无击退。
// ★ 每次伤害都叠「血」：默认每次脉冲 +1 层；GS_EC_W_STACK_PER_HIT = true 时
//   每个被命中的生物各 +1 层。
// ★ 定身改为「同一目标 3 秒内只定一次」（伤害与叠层**不受影响**）。
function gsEcPulse(world, p, selfId, cx, cy, cz) {
    var hit = 0;
    try {
        var now = gsMsNow();
        gsEcLockPrune(now);
        var box = gsMsLoc(world, cx, cy, cz);
        if (box == null) return 0;
        var list = null;
        try { list = world.getNearbyEntities(box, GS_EC_W_R, GS_EC_W_H, GS_EC_W_R); } catch (e) { list = null; }
        if (list == null) return 0;
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (!gsEcCanHit(ent, selfId)) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }
            var hx = el.getX() - cx, hz = el.getZ() - cz;
            if (Math.sqrt(hx * hx + hz * hz) > GS_EC_W_R) continue;
            if (Math.abs((el.getY() + 0.9) - cy) > GS_EC_W_H) continue;
            gsEcHurtNoKb(ent, GS_EC_W_DMG, p);
            gsEcLockOnce(ent, now);                      // ★ 3 秒内只定身一次（伤害照常）
            hit++;
            if (GS_EC_W_STACK_PER_HIT) gsEcAdd(p);
        }
        if (hit > 0) {
            gsEcAdd(p);                                   // 每次伤害脉冲 +1 层
            var loc = gsMsLoc(world, cx, cy, cz);
            gsMsSound(world, loc, 'entity.player.attack.crit', 0.9, 1.2);
        }
    } catch (e) { }
    return hit;
}

// 停下后：原地存在 ms 毫秒（每 2 刻画一次），期间**每 0.5 秒**来一发伤害脉冲（）。
// 停下瞬间立刻打第一发（命中反馈即时），之后 t = 0.5s / 1.0s … 各一发。
// ★ 1.5 秒生命 ⇒ 共 3 发（第 1 / 11 / 21 刻各一发，之后 left 归零）。
function gsEcHold(world, pt, data, p, selfId, cx, cy, cz, ms) {
    var left = Math.round(ms / 50);
    var per = Math.max(2, Math.round(GS_EC_W_PULSE / 50));   // 0.5 秒 = 10 拍
    var nextPulse = 0;                                        // 0 ⇒ 这一拍就打
    function tick() {
        try {
            if (left <= 0) return;
            gsEcRingAt(world, pt, data, cx, cy, cz, GS_EC_W_R, GS_EC_W_GAP);
            if (nextPulse <= 0) {
                gsEcPulse(world, p, selfId, cx, cy, cz);
                nextPulse = per;
            }
            nextPulse -= 2;
            left -= 2;
            runLater(tick, 2);
        } catch (e) { }
    }
    runLater(tick, 2);
}

// ---------- 「血」：层数读写（内存权威 + PDC 备份） ----------
function gsEcGet(p) {
    var o = { n: 0, until: 0, full: 0 };
    try {
        if (p == null) return o;
        var u = '' + p.getUniqueId();
        var m = GS_EC_ST[u];
        if (m != null) { o.n = m.n | 0; o.until = 0 + m.until; o.full = m.full | 0; return o; }
        if (GS_EC_PDT == null || GS_EC_K_BLOOD == null) return o;
        try {
            var pdc = p.getPersistentDataContainer();
            var n = pdc.get(GS_EC_K_BLOOD, GS_EC_PDT.INTEGER);
            var un = pdc.get(GS_EC_K_UNTIL, GS_EC_PDT.LONG);
            var f = pdc.get(GS_EC_K_FULL, GS_EC_PDT.INTEGER);
            if (n != null) o.n = n | 0;
            if (un != null) o.until = 0 + un;
            if (f != null) o.full = f | 0;
        } catch (e) { }
        if (o.n > 0 && o.until > gsMsNow()) GS_EC_ST[u] = { n: o.n, until: o.until, full: o.full };
        else { o.n = 0; o.until = 0; o.full = 0; }
        return o;
    } catch (e) { return o; }
}

function gsEcSet(p, n, until, full) {
    try {
        if (p == null) return;
        var u = '' + p.getUniqueId();
        var nn = n | 0, uu = 0 + until, ff = full | 0;
        if (nn <= 0) { try { delete GS_EC_ST[u]; } catch (e) { } }
        else GS_EC_ST[u] = { n: nn, until: uu, full: ff };
        if (GS_EC_PDT == null || GS_EC_K_BLOOD == null) return;
        try {
            var pdc = p.getPersistentDataContainer();
            pdc.set(GS_EC_K_BLOOD, GS_EC_PDT.INTEGER, nn);
            pdc.set(GS_EC_K_UNTIL, GS_EC_PDT.LONG, gsMsLong(uu));
            pdc.set(GS_EC_K_FULL,  GS_EC_PDT.INTEGER, ff);
        } catch (e) { }
    } catch (e) { }
}

// ---------- 「血」：BossBar ----------
function gsEcBarColor(colorName) {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        var n = '' + (colorName == null ? GS_EC_BLOOD_COL : colorName);
        var c = null;
        try { c = C[n]; } catch (e) { c = null; }
        if (c == null) { try { c = C.valueOf(n); } catch (e) { c = null; } }
        if (c == null) { try { c = C.RED; } catch (e) { c = null; } }
        return c;
    } catch (e) { return null; }
}

function gsEcBarStyle() {
    try {
        var S = Java.type('org.bukkit.boss.BarStyle');
        try { var s = S.SEGMENTED_12; if (s != null) return s; } catch (e) { }
        try { var s2 = S.valueOf('SOLID'); if (s2 != null) return s2; } catch (e) { }
    } catch (e) { }
    return null;
}

function gsEcBarOf(p, colorName) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_EC_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsEcBarColor(colorName), s = gsEcBarStyle();
        if (c == null || s == null) return null;
        var bar = null;
        // ★ 标题只喂纯字符串（§c 红 / §6 金，见上方 gsMsCode 说明）
        try { bar = getServer().createBossBar(gsMsTitle2('血 0/' + GS_EC_MAX, null, GS_EC_BLOOD_COL), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_EC_BARS[u] = { b: bar, p: p };
        return bar;
    } catch (e) { return null; }
}

function gsEcBarShow(p, main, suffix, colorName, prog) {
    try {
        var b = gsEcBarOf(p, colorName);
        if (b == null) return;
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
        var plain = '' + main + (suffix == null ? '' : '' + suffix);
        gsBarTitleSet(b, gsMsTitle2(main, suffix, colorName));
        try { b.setProgress(prog); } catch (e) { }
        try { var col = gsEcBarColor(colorName); if (col != null) b.setColor(col); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
    } catch (e) { }
}

function gsEcBarHide(p) { gsBarTblHide(GS_EC_BARS, p); }

function gsEcClear(p) {
    try { gsEcSet(p, 0, 0, 0); } catch (e) { }
    try { gsEcBarHide(p); } catch (e) { }
}

// 加一层「血」
function gsEcAdd(p) {
    try {
        var now = gsMsNow();
        var st = gsEcGet(p);
        if (st.n >= GS_EC_MAX) {
            // 已满层：命中只刷新 35 秒倒计时，层数不再增加
            gsEcSet(p, GS_EC_MAX, now + GS_EC_FULL_WIN, 1);
            gsEcBarShow(p, '血 ' + GS_EC_MAX + '/' + GS_EC_MAX, ' 右键释放“审判”',
                        GS_EC_BLOOD_COL, 1.0);
            return;
        }
        var n = st.n + 1;
        if (n >= GS_EC_MAX) {
            gsEcSet(p, GS_EC_MAX, now + GS_EC_FULL_WIN, 1);
            gsEcBarShow(p, '血 ' + GS_EC_MAX + '/' + GS_EC_MAX, ' 右键释放“审判”',
                        GS_EC_BLOOD_COL, 1.0);
            gsMsSoundAt(p, 'block.beacon.activate', 0.9, 1.2);
        } else {
            gsEcSet(p, n, now + GS_EC_WINDOW, 0);
            gsEcBarShow(p, '血 ' + n + '/' + GS_EC_MAX, null, GS_EC_BLOOD_COL, 1.0);
        }
    } catch (e) { }
}

// 每拍维护「血」血条（审判进行中时跳过 —— 那时血条被白色审判倒计时占用）
function gsEcPlayerTick(p) {
    try {
        var u = '';
        try { u = '' + p.getUniqueId(); } catch (e) { return; }
        if (GS_EC_JUDGING[u]) return;
        var st = gsEcGet(p);
        if (st.n <= 0) { gsEcBarHide(p); return; }
        var total = (st.full === 1) ? GS_EC_FULL_WIN : GS_EC_WINDOW;
        var now = gsMsNow();
        if (st.until <= 0) {                       // 自愈：层数在、计时丢了
            gsEcSet(p, st.n, now + total, st.full);
            gsEcBarShow(p, '血 ' + st.n + '/' + GS_EC_MAX,
                        (st.full === 1) ? ' 右键释放“审判”' : null, GS_EC_BLOOD_COL, 1.0);
            return;
        }
        var left = st.until - now;
        if (left <= 0) {
            gsEcClear(p);
            gsMsSoundAt(p, 'block.beacon.deactivate', 0.6, 1.4);
            return;
        }
        gsEcBarShow(p, '血 ' + st.n + '/' + GS_EC_MAX,
                    (st.full === 1) ? ' 右键释放“审判”' : null,
                    GS_EC_BLOOD_COL, (total > 0) ? (left / total) : 0);
    } catch (e) { }
}

function gsEcBarsClean() { gsBarTblClean(GS_EC_BARS); }

// ---------- 审判 ----------
function gsEcDrawField(world, cx, cy, cz) {
    try {
        var dust = gsMsDustType();
        if (dust == null) return;
        var dr = gsMsDustOpts(GS_EC_J_COL_R[0], GS_EC_J_COL_R[1], GS_EC_J_COL_R[2], 1.2);
        var dp = gsMsDustOpts(GS_EC_J_COL_P[0], GS_EC_J_COL_P[1], GS_EC_J_COL_P[2], 1.2);
        var dw = gsMsDustOpts(GS_EC_J_COL_W[0], GS_EC_J_COL_W[1], GS_EC_J_COL_W[2], 1.0);
        var y = cy + 0.15;
        // 三圈同心圆：外红 / 中紫 / 内白
        gsEcRingAt(world, dust, dr, cx, y, cz, GS_EC_J_R, 0.8);
        gsEcRingAt(world, dust, dp, cx, y, cz, GS_EC_J_R * 0.68, 0.7);
        gsEcRingAt(world, dust, dw, cx, y, cz, GS_EC_J_R * 0.36, 0.6);
        // 8 条缓缓旋转的紫色辐条
        var a0 = ((gsMsNow() % 4000) / 4000.0) * Math.PI * 2.0;
        for (var k = 0; k < 8; k++) {
            var a = a0 + (Math.PI * 2.0 * k) / 8.0;
            var ux = Math.cos(a), uz = Math.sin(a);
            for (var rr = 1.2; rr <= GS_EC_J_R + 0.01; rr += 1.0) {
                gsMsDust(world, dust, dp, cx + ux * rr, y, cz + uz * rr);
            }
        }
        // 中心一簇白
        gsMsDust(world, dust, dw, cx, y + 0.25, cz);
        gsMsDust(world, dust, dw, cx, y + 0.65, cz);
    } catch (e) { }
}

// 阵内生物全部定身（短定身 + 每 2 刻刷新 ⇒ 全程钉住；不画柱，视觉交给法阵）
function gsEcFieldLock(p, world, cx, cy, cz) {
    try {
        var selfId = '' + p.getUniqueId();
        var box = gsMsLoc(world, cx, cy + 1.0, cz);
        if (box == null) return;
        var list = null;
        try { list = world.getNearbyEntities(box, GS_EC_J_R, 20.0, GS_EC_J_R); } catch (e) { list = null; }
        if (list == null) return;
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (!gsEcCanHit(ent, selfId)) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }
            var hx = el.getX() - cx, hz = el.getZ() - cz;
            if (Math.sqrt(hx * hx + hz * hz) > GS_EC_J_R) continue;
            gsMsLockFor(ent, 800, null);
        }
    } catch (e) { }
}

// 天降圆柱粒子 → 233 点伤害
// ★ 圆柱除原来的「星星」外，再叠一圈**紫 / 蓝 / 红**三色尘埃（逐层轮换）
function gsEcSky(victim, x, y, z, delay) {
    runLater(function () {
        try {
            var world = null;
            try { world = victim.getWorld(); } catch (e) { world = null; }
            if (world == null) return;
            var pt = gsMsStarType();
            // ★ 三色尘埃（紫 / 蓝 / 红）——每层轮换一种颜色
            var dust = gsMsDustType();
            var dcols = null;
            if (dust != null) {
                dcols = [
                    gsMsDustOpts(GS_EC_J_COL_P[0], GS_EC_J_COL_P[1], GS_EC_J_COL_P[2], 1.2),
                    gsMsDustOpts(GS_EC_J_COL_B[0], GS_EC_J_COL_B[1], GS_EC_J_COL_B[2], 1.2),
                    gsMsDustOpts(GS_EC_J_COL_R[0], GS_EC_J_COL_R[1], GS_EC_J_COL_R[2], 1.2)
                ];
            }
            // 圆柱：半径 1，从上方 16 格一路降下来
            var lv = 0;
            for (var h = 16.0; h >= -0.2; h -= 0.7) {
                if (pt != null) {
                    for (var k = 0; k < 5; k++) {
                        var a = (Math.PI * 2.0 * k) / 5.0;
                        try {
                            world.spawnParticle(pt, x + Math.cos(a) * 1.0, y + h, z + Math.sin(a) * 1.0,
                                1, 0.05, 0.05, 0.05, 0.0);
                        } catch (e) { }
                    }
                }
                if (dcols != null) {                     // ★ 紫/蓝/红三色尘埃，随层号轮换 + 轻微旋转
                    for (var k3 = 0; k3 < 3; k3++) {
                        var a3 = (Math.PI * 2.0 * k3) / 3.0 + lv * 0.42;
                        gsMsDust(world, dust, dcols[(k3 + lv) % 3],
                                 x + Math.cos(a3) * 1.0, y + h, z + Math.sin(a3) * 1.0);
                    }
                }
                lv++;
            }
            // 落地闪光一圈（星 + 三色尘埃）
            for (var k2 = 0; k2 < 14; k2++) {
                var a2 = (Math.PI * 2.0 * k2) / 14.0;
                if (pt != null) {
                    try { world.spawnParticle(pt, x + Math.cos(a2) * 1.7, y + 0.6, z + Math.sin(a2) * 1.7, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
                }
                if (dcols != null) {
                    gsMsDust(world, dust, dcols[k2 % 3], x + Math.cos(a2) * 1.7, y + 0.6, z + Math.sin(a2) * 1.7);
                }
            }
            try {
                var l = gsMsLoc(world, x, y, z);
                if (l != null) world.playSound(l, 'entity.lightning_bolt.impact', 1.0, 1.2);
            } catch (e) { }
            gsEcHurtNoKb(victim, GS_EC_J_DMG, null);
        } catch (e) { }
    }, delay);
}

// 6 秒结束时：把阵内所有生物砸一遍
function gsEcSmite(p, world, cx, cy, cz) {
    try {
        var selfId = '' + p.getUniqueId();
        var box = gsMsLoc(world, cx, cy + 1.0, cz);
        if (box == null) return;
        var list = null;
        try { list = world.getNearbyEntities(box, GS_EC_J_R, 20.0, GS_EC_J_R); } catch (e) { list = null; }
        if (list == null) return;
        var targets = [];
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            if (!gsEcCanHit(ent, selfId)) continue;
            var el = null;
            try { el = ent.getLocation(); } catch (e) { continue; }
            var hx = el.getX() - cx, hz = el.getZ() - cz;
            if (Math.sqrt(hx * hx + hz * hz) > GS_EC_J_R) continue;
            targets.push(ent);
        }
        var n = 0;
        for (var i = 0; i < targets.length; i++) {
            var t = targets[i];
            var tl = null;
            try { tl = t.getLocation(); } catch (e) { continue; }
            gsEcSky(t, tl.getX(), tl.getY(), tl.getZ(), n * 2);
            n++;
            if (n > GS_EC_J_MAX_TARGETS) break;   // ★ 第七十批：安全阀（原来写死的 60）
        }
    } catch (e) { }
}

// ★ **视角锁定已删除**（原 gsEcLockView）——审判期间玩家可自由转视角

// ★ **「无敌保持到落地」已删除**（原 gsEcInvulnUntilLand）——
//   改为**固定 1.8 秒无敌**（GS_EC_J_INV，与蓄力同步），在 gsEcJudge 里读条结束时一并解除

// 法阵主循环：每 2 刻画一次，同时刷新定身 + 白色血条**蓄力读条**
// ★ 标题**只显示「审判」二字**（不再带秒数）；读条长度仍按剩余时间走
function gsEcField(p, world, cx, cy, cz, ticksLeft, lock) {
    try {
        if (ticksLeft <= 0) return;
        gsEcDrawField(world, cx, cy, cz);
        if (lock) gsEcFieldLock(p, world, cx, cy, cz);
        try {
            gsEcBarShow(p, '审判', null, 'WHITE', ticksLeft / GS_EC_J_TICKS);
        } catch (e) { }
    } catch (e) { }
    runLater(function () { gsEcField(p, world, cx, cy, cz, ticksLeft - 2, lock); }, 2);
}

function gsEcJudge(p) {
    try {
        var st = gsEcGet(p);
        if (st.n < GS_EC_MAX) return false;              // 未满 23 层 ⇒ 右键不做任何事
        var u = '' + p.getUniqueId();
        if (GS_EC_JUDGING[u]) return true;               // 审判进行中，忽略重复
        gsEcClear(p);                                    // 先清空「血」

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return true; }
        var world = p.getWorld();
        var cx = loc.getX(), cy = loc.getY(), cz = loc.getZ();

        GS_EC_JUDGING[u] = true;

 // ① ★ 立刻无敌（固定 1.8 秒；**不再漂浮、不再锁视角**）
        try { p.setInvulnerable(true); } catch (e) { }
        try { p.setFallDistance(0.0); } catch (e) { }

        // ② 音效
        gsMsSound(world, loc, 'entity.wither.spawn', 1.4, 0.7);
        gsMsSound(world, loc, 'entity.ender_dragon.growl', 1.3, 0.8);

        // ③ 法阵：半径 8 / 蓄力 1.8 秒（内含定身 + 白色「审判」读条）
        gsEcField(p, world, cx, cy, cz, GS_EC_J_TICKS, true);

 // ④ ★ **蓄力满 1.8 秒才出伤**（天降 233 点），随后收尾
        runLater(function () {
            try { gsEcSmite(p, world, cx, cy, cz); } catch (e) { }
            try { GS_EC_JUDGING[u] = false; } catch (e) { }
            try { gsEcBarHide(p); } catch (e) { }
        }, GS_EC_J_TICKS);

 // ⑤ ★ 无敌固定 1.8 秒（GS_EC_J_INV，与蓄力同步）后解除
        runLater(function () {
            try { p.setInvulnerable(false); } catch (e) { }
        }, GS_EC_J_INV);

        return true;
    } catch (e) { return false; }
}

// ---------- 去抖 ----------
function gsEcClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_EC_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_EC_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

function gsEcRecentRight(p, gapMs) {
    try {
        var last = GS_EC_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsMsNow() - last) < gapMs;
    } catch (e) { return false; }
}

// ---------- 注册同步监听器 ----------
function gsEcRegisterSync() {
    if (GS_EC_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ① 左键 = 蚀月轮；右键 = 满 23 层放审判（其余情况只取消原版交互）
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsEcIs(gsEcHand(p))) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // 取消右键交互（锄类不许耕地 / 不许开箱子）
                        e.setCancelled(true);
                        if (Res != null) {
                            try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                            try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                        }
                        if (!gsEcClickOnce(p, 'R')) return;
                        gsEcJudge(p);                                       // 未满 23 层时内部直接返回
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsEcClickOnce(p, 'L')) return;
                        if (GS_EC_NO_BREAK) {
                            e.setCancelled(true);
                            if (Res != null) {
                                try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                                try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                            }
                        }
                        gsEcWheel(p);
                    }
                } catch (e3) { }
            });
        }

        // ② 左键兜底：挥手动画（与 ① 共用去抖 ⇒ 不会放两次）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsEcIs(gsEcHand(p))) return;
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsEcRecentRight(p, 300)) return;    // 右键附带的挥手 → 忽略
                    if (!gsEcClickOnce(p, 'L')) return;
                    gsEcWheel(p);
                } catch (e3) { }
            });
        }

        // ③ 手持蚀月 → 挖不动方块（双保险）
        if (GS_EC_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsEcIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
            var DmgBlkEv = null;
            try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
            if (DmgBlkEv != null) {
                reg(DmgBlkEv, function (e) {
                    try {
                        if (!gsEcIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

        GS_EC_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 蚀月·死神梦镰监听器注册成功（左键蚀月轮叠血 / 满 23 层右键审判）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 蚀月监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsEcStart() {
    try {
        if (!gsEcRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 蚀月启动失败：监听器没注册上'); } catch (e) { }
            return;
        }
        try {
            var pdcOk = (GS_EC_PDT != null) && (GS_EC_K_BLOOD != null) && (GS_EC_K_UNTIL != null) && (GS_EC_K_FULL != null);
            getServer().getLogger().info('[构思技艺] 蚀月状态：层数存内存（权威）· PDC 备份 '
                + (pdcOk ? '可用' : '不可用'));
        } catch (e) { }
        try {
            getServer().getLogger().info('[构思技艺] 蚀月·死神梦镰（' + GS_EC_ID + '）已加载 · 蚀月轮 '
                + GS_EC_W_DMG + ' 点 / 每 ' + (GS_EC_W_PULSE / 1000) + ' 秒 1 次 / 射程 '
                + GS_EC_W_RANGE + ' 格 / 每刻 ' + GS_EC_W_STEP + ' 格 / 落地后留 '
                + (GS_EC_W_LIFE / 1000) + ' 秒 / 定身 '
                + (GS_EC_W_LOCK / 1000) + ' 秒（同目标间隔 ' + (GS_EC_W_LOCK_CD / 1000)
                + ' 秒）/ 冷却 ' + (GS_EC_W_CD / 1000)
                + ' 秒 / 普通左键叠层 ' + (GS_EC_SW_HIT ? '开' : '关')
                + ' / 满 ' + GS_EC_MAX + ' 层 · 审判 ' + GS_EC_J_DMG + ' 点 / 半径 ' + GS_EC_J_R
                + ' 格 / 蓄力 ' + (GS_EC_J_TICKS / 20) + ' 秒后出伤 / 无敌 ' + (GS_EC_J_INV / 20)
                + ' 秒 / 不锁视角 / 无漂浮 / 圆柱粒子 紫蓝红');
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 蚀月启动异常: ' + e); } catch (e2) { }
    }
}

gsEcStart();

// ==========================================================================
// 致万千无言者（GS_VOICELESS · 构思武器第 2 格 · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §10
// ==========================================================================
var GS_VO_ID          = 'GS_VOICELESS';
var GS_VO_NO_BREAK    = true;      // true = 手持时挖不动方块（石镐不许挖矿）
var GS_VO_HIT_PLAYERS = false;     // false = 挥砍 / 逝言不打其它玩家（只打生物），防误伤

// ---- 被动：连击「言」----
var GS_VO_MAX        = 7;          // 最高连击数
var GS_VO_WINDOW     = 3000;       // 命中窗口（毫秒）= 3 秒
var GS_VO_DMG_BASE   = 7.0;        // 1 连击时的伤害（= 初始伤害 7）
var GS_VO_DMG_PER    = 1.0;        // 每多一层连击的加成 ⇒ 满层 = 7 + 6×1 = 13
var GS_VO_BAR_COL    = 'WHITE';    // 血条颜色：原版 BarColor **没有灰色**（只有
                                   // PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE）
                                   // ⇒ 取最接近的 WHITE，"灰"由 §7 标题文字承担

// ---- 左键：挥砍 ----
var GS_VO_SWING_R    = 3.0;        // 判定长度：前方 3 格
var GS_VO_SWING_W    = 3.0;        // 判定宽度：共 3 格宽（左右各 1.5）
var GS_VO_SWING_H    = 1.6;        // 判定上下高度（格）
var GS_VO_SWING_CD   = 320;        // 挥砍冷却（毫秒）

// ---- 右键：逝言 ----
var GS_VO_QI_CHARGE  = 12;         // 蓄力刻数（12 刻 = 0.6 秒）
var GS_VO_QI_RANGE   = 7.0;        // 最远 7 格
var GS_VO_QI_WIDE    = 3.0;        // 宽度 3 格（左右各 1.5）
var GS_VO_QI_H       = 1.8;        // 弧形斩击的上下判定高度（格）
var GS_VO_QI_PIERCE  = 4;          // 最多穿透 4 个生物（打完第 4 个就散）
var GS_VO_QI_DMG     = 11.0;       // 每个生物 11 点
var GS_VO_QI_STEP    = 0.5;        // 每刻前进（格）—— < 1.0 ⇒ 不会一步跨过整块墙
var GS_VO_QI_CD      = 1200;       // 发射冷却（毫秒）
var GS_VO_QI_TRAIL   = 8;          // 弹道残留附魔文字（刻）⇒ 8 刻 ≈ 0.4 秒
var GS_VO_QI_STACK   = true;       // 逝言命中也叠连击（false = 只有左键挥砍叠）
var GS_VO_QI_COL     = [150, 150, 150];   // 灰色弧形斩击

var GS_VO_REGISTERED = false;
var GS_VO_CLICK_AT   = {};         // uuid|L / uuid|R -> 上次处理毫秒（去抖）
var GS_VO_SWING_AT   = {};         // uuid -> 上次挥砍毫秒（冷却）
var GS_VO_FIRE_AT    = {};         // uuid -> 上次「逝言」发射毫秒（冷却）
var GS_VO_CHARGING   = {};         // uuid -> true（正在蓄力，防重复触发）
var GS_VO_ST         = {};         // uuid -> { n, until }（内存权威）
var GS_VO_BARS       = {};         // uuid -> { b, p }（★ 本模块专属表）
var GS_VO_GEN        = -1;         // 加载那一刻的世界世代号（判 /rsc reload 旧实例）

var GS_VO_ENCH = null, GS_VO_ENCH_TRIED = false;

// ★ 本模块**自己的**世界世代号（gs:vo_gen）—— 不复用月镰的 gs:ms_gen：
//   复用就等于把"重载后旧实例停手"押在月镰模块上（月镰启动失败时它不会 bump），
//   自己一份才能保证 /rsc reload 后本模块的旧实例一定会收手。
var GS_VO_K_GEN = null, GS_VO_PDT = null;
try {
    var VO_NK = Java.type('org.bukkit.NamespacedKey');
    // ★★★ 必须 fromString（new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败）
    GS_VO_K_GEN = VO_NK.fromString('gs:vo_gen');
    GS_VO_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

function gsVoIs(item) { return gsIsItem(item, GS_VO_ID); }

function gsVoHand(p) {
    try { return p.getInventory().getItemInMainHand(); } catch (e) { return null; }
}

// ---------- 粒子类型：附魔台漂浮文字 ----------
//   1.21 叫 ENCHANT；老版本叫 ENCHANTMENT_TABLE / ENCHANTING_TABLE ⇒ 逐个降级尝试
function gsVoEnchType() {
    if (GS_VO_ENCH != null) return GS_VO_ENCH;
    if (GS_VO_ENCH_TRIED) return null;
    GS_VO_ENCH_TRIED = true;
    try {
        var P = Java.type('org.bukkit.Particle');
        var names = ['ENCHANT', 'ENCHANTMENT_TABLE', 'ENCHANTING_TABLE'];
        for (var i = 0; i < names.length; i++) {
            try { var t = P[names[i]]; if (t != null) { GS_VO_ENCH = t; return t; } } catch (e) { }
            try { var t2 = P.valueOf(names[i]); if (t2 != null) { GS_VO_ENCH = t2; return t2; } } catch (e) { }
        }
    } catch (e) { }
    return null;
}

// ---------- 连击「言」：读写（内存权威，不写 PDC） ----------
function gsVoGet(p) {
    var o = { n: 0, until: 0 };
    try {
        if (p == null) return o;
        var m = GS_VO_ST['' + p.getUniqueId()];
        if (m != null) { o.n = m.n | 0; o.until = 0 + m.until; }
    } catch (e) { }
    return o;
}

function gsVoSet(p, n, until) {
    try {
        if (p == null) return;
        var u = '' + p.getUniqueId();
        var nn = n | 0;
        if (nn <= 0) { try { delete GS_VO_ST[u]; } catch (e) { } }
        else GS_VO_ST[u] = { n: nn, until: 0 + until };
    } catch (e) { }
}

// 当前连击对应的伤害：1 连击 = 7 点，每多一层 +1 ⇒ 满 7 层 = 13 点
function gsVoDmg(n) {
    var k = n | 0;
    if (k < 1) k = 1;
    if (k > GS_VO_MAX) k = GS_VO_MAX;
    return GS_VO_DMG_BASE + GS_VO_DMG_PER * (k - 1);
}

// ---------- 连击「言」：BossBar ----------
// ★ 标题一律喂**纯字符串**（§ 颜色码），绝不要把 Adventure Component 塞进 setTitle
// —— 在月镰 / 蚀月上踩过这个坑，会被 GraalJS 隐式 toString() 成一长串 dump。
function gsVoTitle(text) { return GS_MS_C.GRAY + '' + text; }   // ★ 整条灰色（§7）

function gsVoBarColor() {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        var name = GS_VO_BAR_COL;
        try { var c = C.valueOf(name); if (c != null) return c; } catch (e) { }
        try { var c2 = C[name]; if (c2 != null) return c2; } catch (e) { }
    } catch (e) { }
    return null;
}

function gsVoBarOf(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_VO_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsVoBarColor(), s = gsMsBarStyle();      // 样式复用月镰的解析器
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(gsVoTitle('言 0/' + GS_VO_MAX), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_VO_BARS[u] = { b: bar, p: p };
        return bar;
    } catch (e) { return null; }
}

function gsVoBarShow(p, n, left, total) {
    try {
        var b = gsVoBarOf(p);
        if (b == null) return;
        var prog = (total > 0) ? (left / total) : 0;
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
        gsBarTitleSet(b, gsVoTitle('言 ' + (n | 0) + '/' + GS_VO_MAX));
        try { b.setProgress(prog); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
    } catch (e) { }
}

function gsVoBarHide(p) { gsBarTblHide(GS_VO_BARS, p); }

function gsVoClear(p) {
    try { gsVoSet(p, 0, 0); } catch (e) { }
    try { gsVoBarHide(p); } catch (e) { }
}

// ---------- 连击「言」：加层 / 衰减 ----------
function gsVoAdd(p) {
    try {
        var now = gsMsNow();
        var st = gsVoGet(p);
        var n = (st.n | 0) + 1;
        if (n > GS_VO_MAX) n = GS_VO_MAX;
        gsVoSet(p, n, now + GS_VO_WINDOW);
        gsVoBarShow(p, n, GS_VO_WINDOW, GS_VO_WINDOW);
        if (n >= GS_VO_MAX) gsMsSoundAt(p, 'block.beacon.activate', 0.9, 1.4);
    } catch (e) { }
}

// 每 2 刻维护：血条倒计时；窗口过完 ⇒ **连击 -1**（不是清零）
function gsVoPlayerTick(p) {
    try {
        var st = gsVoGet(p);
        if (st.n <= 0) { gsVoBarHide(p); return; }
        var now = gsMsNow();
        if (st.until <= 0) {                       // 自愈：层数在、计时丢了 ⇒ 补一份
            gsVoSet(p, st.n, now + GS_VO_WINDOW);
            gsVoBarShow(p, st.n, GS_VO_WINDOW, GS_VO_WINDOW);
            return;
        }
        var left = st.until - now;
        if (left <= 0) {
            var n = (st.n | 0) - 1;                // ★ 3 秒未命中 ⇒ 掉一层（逐层衰减）
            if (n <= 0) {
                gsVoClear(p);
                gsMsSoundAt(p, 'block.beacon.deactivate', 0.6, 1.4);
            } else {
                gsVoSet(p, n, now + GS_VO_WINDOW); // 窗口重置 ⇒ 再过 3 秒才继续掉
                gsVoBarShow(p, n, GS_VO_WINDOW, GS_VO_WINDOW);
            }
            return;
        }
        gsVoBarShow(p, st.n, left, GS_VO_WINDOW);
    } catch (e) { }
}

function gsVoBarsClean() { gsBarTblClean(GS_VO_BARS); }

// /rsc reload 后旧实例：把自己的血条收干净（连击状态本来就在内存里，随实例一起消失）
function gsVoReleaseAll() {
    try {
        for (var k in GS_VO_BARS) {
            try { GS_VO_BARS[k].b.removeAll(); } catch (e) { }
            try { delete GS_VO_BARS[k]; } catch (e) { }
        }
    } catch (e) { }
}

// ==========================================================================
// ★★ 通用：这个实体算不算「命中了一个敌人」（致万千无言者 / 此刻万籁俱寂 共用）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §11
// ==========================================================================
var GS_LIVING_CLS = null;
try { GS_LIVING_CLS = Java.type('org.bukkit.entity.LivingEntity'); } catch (e) { GS_LIVING_CLS = null; }

// 「不是敌人」的实体类型名（第一层，永远生效）
var GS_NOT_ENEMY_TYPES = {
    'ITEM': 1, 'EXPERIENCE_ORB': 1, 'ARMOR_STAND': 1,
    'ITEM_FRAME': 1, 'GLOW_ITEM_FRAME': 1, 'PAINTING': 1,
    'ITEM_DISPLAY': 1, 'BLOCK_DISPLAY': 1, 'TEXT_DISPLAY': 1,
    'INTERACTION': 1, 'MARKER': 1, 'AREA_EFFECT_CLOUD': 1,
    'BOAT': 1, 'CHEST_BOAT': 1, 'MINECART': 1, 'CHEST_MINECART': 1,
    'COMMAND_BLOCK_MINECART': 1, 'FURNACE_MINECART': 1, 'HOPPER_MINECART': 1,
    'TNT_MINECART': 1, 'SPAWNER_MINECART': 1,
    'ARROW': 1, 'SPECTRAL_ARROW': 1, 'TRIDENT': 1, 'FIREWORK_ROCKET': 1,
    'SNOWBALL': 1, 'EGG': 1, 'ENDER_PEARL': 1, 'EYE_OF_ENDER': 1,
    'POTION': 1, 'SPLASH_POTION': 1, 'LINGERING_POTION': 1,
    'EXPERIENCE_BOTTLE': 1, 'FIREBALL': 1, 'SMALL_FIREBALL': 1,
    'DRAGON_FIREBALL': 1, 'WITHER_SKULL': 1, 'SHULKER_BULLET': 1,
    'LLAMA_SPIT': 1, 'EVOKER_FANGS': 1, 'FALLING_BLOCK': 1,
    'END_CRYSTAL': 1, 'TNT': 1, 'PRIMED_TNT': 1, 'LEASH_HITCH': 1,
    'LIGHTNING_BOLT': 1, 'FISHING_BOBBER': 1, 'UNKNOWN': 1
};

function gsIsCombatTarget(ent, tn) {
    try {
        if (tn != null && tn.length > 0 && GS_NOT_ENEMY_TYPES[tn] === 1) return false;
        if (GS_LIVING_CLS != null) {
            try { return GS_LIVING_CLS.class.isInstance(ent) === true; } catch (e) { }
        }
        return true;      // 拿不到类型信息 ⇒ 交给各模块自己的 getHealth() 探针
    } catch (e) { return true; }
}

// ---------- 命中判定 / 伤害器 ----------
function gsVoCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !GS_VO_HIT_PLAYERS) return false;
 // ★★ 非活物（掉落物 / 经验球 / 展示实体 / 盔甲架 …）**不算命中**
        if (!gsIsCombatTarget(ent, tn)) return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// 复用月镰那套「只掉血、不击退、不留无敌帧」的伤害器
//   （里面同时会 bump GS_WPN_DMG_GUARD ⇒ 末尾「取消原版近战」的监听器会放行这一次）
function gsVoHurt(ent, dmg, p) { gsMsHurt(ent, dmg, p); }

// ---------- 左键：灰色弧光 ----------
function gsVoSlashFx(world, loc, fx, fz, rx, rz) {
    try {
        var pt = gsMsDustType();
        if (pt == null) return;
        var data = gsMsDustOpts(GS_VO_QI_COL[0], GS_VO_QI_COL[1], GS_VO_QI_COL[2], 1.1);
        var n = 14;
        for (var i = 0; i <= n; i++) {
            var t = i / n;
            var f = 0.6 + 2.4 * t;                 // 前向 0.6 → 3.0 格
            var s = 1.50 - 3.00 * t;               // 横向 +1.5 → -1.5（正好 3 格宽）
            var h = 1.75 - 1.30 * t;               // 高度 1.75 → 0.45（斜着往下劈）
            gsMsDust(world, pt, data,
                loc.getX() + fx * f + rx * s,
                loc.getY() + h,
                loc.getZ() + fz * f + rz * s);
        }
    } catch (e) { }
}

function gsVoSwing(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_VO_SWING_AT[u];
        if (last != null && (now - last) < GS_VO_SWING_CD) return;
        GS_VO_SWING_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return; }

        var fx = dir.getX(), fz = dir.getZ();
        var fl = Math.sqrt(fx * fx + fz * fz);
        if (fl < 1.0e-4) { fx = 0.0; fz = 1.0; fl = 1.0; }   // 垂直看天/看地 ⇒ 退化用 +Z
        fx /= fl; fz /= fl;
        var rx = -fz, rz = fx;                                // 水平右向

        gsVoSlashFx(world, loc, fx, fz, rx, rz);
        gsMsSound(world, loc, 'entity.player.attack.sweep', 0.75, 1.25);

        // ★ 这一刀打出去之后连击会 +1 ⇒ 用"加过之后"的层数算伤害
        //   （所以第一刀 = 1 连击 = 7 点，与"初始伤害 7"一致）
        var st = gsVoGet(p);
        var dmg = gsVoDmg((st.n | 0) + 1);

        var selfId = u;
        var hit = {};
        var count = 0;
        var halfW = GS_VO_SWING_W * 0.5;
        var px0 = loc.getX(), py0 = loc.getY(), pz0 = loc.getZ();
        var cbox = gsMsLoc(world, px0, py0 + 1.0, pz0);
        if (cbox != null) {
            var list = null;
            try { list = world.getNearbyEntities(cbox, GS_VO_SWING_R + 0.8, GS_VO_SWING_H, GS_VO_SWING_R + 0.8); } catch (e) { list = null; }
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (!gsVoCanHit(ent, selfId)) continue;
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                    if (hit[eid] != null) continue;
                    var el = null;
                    try { el = ent.getLocation(); } catch (e) { continue; }
                    var dx = el.getX() - px0, dz = el.getZ() - pz0;
                    var fwd = dx * fx + dz * fz;                   // 前向投影（长度）
                    var lat = dx * rx + dz * rz;                   // 左右投影（宽度）
                    if (fwd < -0.4 || fwd > GS_VO_SWING_R) continue;
                    if (lat < -halfW || lat > halfW) continue;
                    if (Math.abs((el.getY() + 0.9) - (py0 + 1.0)) > GS_VO_SWING_H) continue;
                    hit[eid] = 1;
                    gsVoHurt(ent, dmg, p);
                    count++;
                }
            }
        }
        if (count > 0) gsVoAdd(p);        // ★ 命中 >= 1 个生物 ⇒ 连击 +1
    } catch (e) { }
}

// ---------- 右键「逝言」：蓄力 ----------
// 蓄力期间身上浮现附魔台漂浮文字（绕身随机散布一圈 + 上下）
function gsVoEnchBody(p) {
    try {
        var pt = gsVoEnchType();
        if (pt == null) return;
        var loc = p.getLocation();
        var w = p.getWorld();
        for (var i = 0; i < 6; i++) {
            var a = Math.random() * Math.PI * 2.0;
            var r = 0.5 + Math.random() * 0.7;
            var h = 0.5 + Math.random() * 1.5;
            gsMsDust(w, pt, null,
                loc.getX() + Math.cos(a) * r,
                loc.getY() + h,
                loc.getZ() + Math.sin(a) * r);
        }
    } catch (e) { }
}

function gsVoQi(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        if (GS_VO_CHARGING[u] === true) return;              // 已在蓄力 ⇒ 忽略
        var last = GS_VO_FIRE_AT[u];
        if (last != null && (now - last) < GS_VO_QI_CD) return;
        GS_VO_FIRE_AT[u] = now;
        GS_VO_CHARGING[u] = true;

        try {
            var w0 = p.getWorld();
            gsMsSound(w0, p.getLocation(), 'block.enchantment_table.use', 0.9, 1.2);
            gsMsSound(w0, p.getLocation(), 'entity.illusioner.cast_spell', 0.7, 1.3);
        } catch (e) { }

        var left = GS_VO_QI_CHARGE;
        function charge() {
            try {
                var on = true;
                try { on = p.isOnline(); } catch (e) { on = true; }
                if (!on) { try { delete GS_VO_CHARGING[u]; } catch (e) { } return; }
                if (left <= 0) {
                    try { delete GS_VO_CHARGING[u]; } catch (e) { }
                    // ★ 主手已经换掉武器 ⇒ 蓄力作废，不挥出去（也不还冷却，防连点刷）
                    if (gsVoIs(gsVoHand(p))) gsVoQiFire(p);
                    return;
                }
                gsVoEnchBody(p);
                left--;
                runLater(charge, 1);
            } catch (e) { try { delete GS_VO_CHARGING[u]; } catch (e2) { } }
        }
        runLater(charge, 1);
    } catch (e) { }
}

// ---------- 右键「逝言」：灰色弧形斩击 ----------
// 在"垂直于弹道"的平面上画一道新月形弧：横向 ±GS_VO_QI_WIDE/2（= 3 格宽），
// 竖向居中，中部再沿弹道方向前鼓一点 ⇒ 看起来是一道"弧形斩击"而不是一条直线。
function gsVoArcFx(world, pt, data, cx, cy, cz, rx, rz, dx, dy, dz) {
    try {
        if (pt == null) return;
        var half = GS_VO_QI_WIDE * 0.5;          // 1.5 格
        var n = 16;
        for (var i = 0; i <= n; i++) {
            var a = -Math.PI * 0.5 + Math.PI * (i / n);   // -90° → +90°
            var lat = Math.sin(a) * half;                 // 横向 ±1.5（正好 3 格宽）
            var up  = (Math.cos(a) - 0.5) * half;         // 竖向 -0.75 → +0.75（居中）
            var bow = Math.cos(a) * 0.35;                 // 中部往前鼓 ⇒ 新月形
            gsMsDust(world, pt, data,
                cx + rx * lat + dx * bow,
                cy + up + dy * bow,
                cz + rz * lat + dz * bow);
        }
    } catch (e) { }
}

// 弹道残留：把记下来的点重新撒一遍附魔台文字（过期点自然被丢掉）
function gsVoTrailFx(world, ench, trail) {
    try {
        if (ench == null || trail == null) return;
        var now = gsMsNow();
        for (var i = 0; i < trail.length; i++) {
            var t = trail[i];
            if (t.until <= now) continue;
            for (var k = 0; k < 2; k++) {
                gsMsDust(world, ench, null,
                    t.x + (Math.random() - 0.5) * 0.5,
                    t.y + (Math.random() - 0.5) * 0.5,
                    t.z + (Math.random() - 0.5) * 0.5);
            }
        }
    } catch (e) { }
}

function gsVoQiFire(p) {
    try {
        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return; }

        // ★ 蓄力结束的**这一刻**才锁方向（读条是给玩家的反应窗口）
        var dx = dir.getX(), dy = dir.getY(), dz = dir.getZ();
        var dl = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0e-4) { dx = 0.0; dy = 0.0; dz = 1.0; dl = 1.0; }
        dx /= dl; dy /= dl; dz /= dl;

        // 水平右向（铺开 3 格宽用）；正上/正下看时水平分量退化 ⇒ 退回 +X
        var hx = dx, hz = dz;
        var hl = Math.sqrt(hx * hx + hz * hz);
        if (hl < 1.0e-4) { hx = 1.0; hz = 0.0; hl = 1.0; }
        hx /= hl; hz /= hl;
        var rx = -hz, rz = hx;

        var selfId = '' + p.getUniqueId();
        var cx = loc.getX() + dx * 0.6;
        var cy = loc.getY() + 1.1 + dy * 0.6;
        var cz = loc.getZ() + dz * 0.6;
        var travelled = 0.0;
        var pierced = 0;
        var hitIds = {};
        var trail = [];                    // 弹道残留点 { x, y, z, until }
        var tailOn = false;                // 残留补画只开一次

        var pt = gsMsDustType();
        var data = gsMsDustOpts(GS_VO_QI_COL[0], GS_VO_QI_COL[1], GS_VO_QI_COL[2], 1.2);
        var ench = gsVoEnchType();

        gsMsSound(world, loc, 'entity.player.attack.sweep', 1.0, 0.8);
        gsMsSound(world, loc, 'entity.evoker.cast_spell', 0.8, 0.7);

        // 斩击结束后，残留的附魔文字继续补画 GS_VO_QI_TRAIL 刻
        function tail() {
            try {
                gsVoTrailFx(world, ench, trail);
                var now = gsMsNow();
                var keep = [];
                for (var i = 0; i < trail.length; i++) {
                    if (trail[i].until > now) keep.push(trail[i]);
                }
                trail = keep;
                if (keep.length > 0) runLater(tail, 2);
            } catch (e) { }
        }

        function finish() {
            try {
                if (tailOn) return;
                tailOn = true;
                gsVoTrailFx(world, ench, trail);
                runLater(tail, 2);
            } catch (e) { }
        }

        function step() {
            try {
                if (p == null) return;
                var on = true;
                try { on = p.isOnline(); } catch (e) { on = true; }
                if (!on) return;

                travelled += GS_VO_QI_STEP;

                // ① 灰色弧形斩击
                gsVoArcFx(world, pt, data, cx, cy, cz, rx, rz, dx, dy, dz);

                // ② 命中判定：落在"这一拍的弧面"上的生物
                var box = gsMsLoc(world, cx, cy, cz);
                if (box != null) {
                    var list = null;
                    try { list = world.getNearbyEntities(box, GS_VO_QI_WIDE * 0.5 + 0.6, GS_VO_QI_H, GS_VO_QI_WIDE * 0.5 + 0.6); } catch (e) { list = null; }
                    if (list != null) {
                        var before = pierced;
                        var it = list.iterator();
                        while (it.hasNext()) {
                            var ent = it.next();
                            if (!gsVoCanHit(ent, selfId)) continue;
                            var eid = '';
                            try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                            if (hitIds[eid] != null) continue;            // ★ 同一目标只吃一次
                            var el = null;
                            try { el = ent.getLocation(); } catch (e) { continue; }
                            var ex = el.getX() - cx, ey = (el.getY() + 0.9) - cy, ez = el.getZ() - cz;
                            var along = ex * dx + ey * dy + ez * dz;      // 沿弹道方向的分量
                            if (Math.abs(along) > 1.0) continue;          // 必须贴在这一拍的弧面上
                            var lx = ex - along * dx, ly = ey - along * dy, lz = ez - along * dz;
                            if (Math.sqrt(lx * lx + ly * ly + lz * lz) > GS_VO_QI_WIDE * 0.5) continue;
                            hitIds[eid] = 1;
                            gsVoHurt(ent, GS_VO_QI_DMG, p);
                            pierced++;
                        }
                        // ★ 与左键挥砍同一条规则：**这一次攻击命中了 ≥1 个生物 ⇒ 连击 +1**
                        //   （不是"每命中一只 +1" —— 否则一发穿 4 个就白送 4 层）
                        if (pierced > before && GS_VO_QI_STACK) gsVoAdd(p);
                    }
                }

                // ③ 记残留点 + 当拍补画
                trail.push({ x: cx, y: cy, z: cz, until: gsMsNow() + GS_VO_QI_TRAIL * 50 });
                gsVoTrailFx(world, ench, trail);

                // ④ 结束条件：穿透满 4 个 / 到 7 格尽头 / 撞墙
                if (pierced >= GS_VO_QI_PIERCE) { finish(); return; }
                if (travelled >= GS_VO_QI_RANGE) { finish(); return; }

                var nx = cx + dx * GS_VO_QI_STEP;
                var ny = cy + dy * GS_VO_QI_STEP;
                var nz = cz + dz * GS_VO_QI_STEP;
                var nb = gsMsLoc(world, nx, ny, nz);
                var solid = false;
                if (nb != null) {
                    try {
                        var blk = nb.getBlock();
                        if (blk != null && blk.getType() != null && blk.getType().isSolid()) solid = true;
                    } catch (e) { }
                }
                if (solid) { finish(); return; }                          // ★ 撞墙即止（不穿墙）
                cx = nx; cy = ny; cz = nz;

                runLater(step, 1);
            } catch (e) { }
        }

        runLater(step, 1);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 逝言出错: ' + e); } catch (e2) { }
    }
}

// ---------- 去抖 ----------
function gsVoClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_VO_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_VO_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

function gsVoRecentRight(p, gapMs) {
    try {
        var last = GS_VO_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsMsNow() - last) < gapMs;
    } catch (e) { return false; }
}

// ---------- 注册同步监听器 ----------
function gsVoRegisterSync() {
    if (GS_VO_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        // 注册助手（与月镰 / 蚀月同款：失败时退回 JavaAdapter 写法）
        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ① 左键 = 挥砍（同时挖不动方块）；右键 = 「逝言」蓄力
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    // ★ 故意不检查 e.isCancelled()：有些前置插件会先取消 *_CLICK_AIR，
                    //   一检查就变成"必须点方块才放得出来"。重复注册交给去抖。
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsVoIs(gsVoHand(p))) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★ 取消右键交互（石镐不许挖矿 / 不许开箱子 / 不许放方块）
                        e.setCancelled(true);
                        if (Res != null) {
                            try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                            try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                        }
                        if (!gsVoClickOnce(p, 'R')) return;
                        gsVoQi(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsVoClickOnce(p, 'L')) return;
                        if (GS_VO_NO_BREAK) {
                            e.setCancelled(true);
                            if (Res != null) {
                                try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                                try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                            }
                        }
                        gsVoSwing(p);
                    }
                } catch (e3) { }
            });
        }

        // ② 左键兜底：挥手动画（对着空气点也会发；与 ① 共用去抖 ⇒ 不会放两次）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsVoIs(gsVoHand(p))) return;
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsVoRecentRight(p, 300)) return;    // 右键附带的挥手 → 忽略
                    if (!gsVoClickOnce(p, 'L')) return;
                    gsVoSwing(p);
                } catch (e3) { }
            });
        }

        // ③ 手持时挖不动方块（双保险：即使 ① 的取消被别的插件放行也挖不掉）
        if (GS_VO_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsVoIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
            var DmgBlkEv = null;
            try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
            if (DmgBlkEv != null) {
                reg(DmgBlkEv, function (e) {
                    try {
                        if (!gsVoIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

        GS_VO_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 致万千无言者监听器注册成功（左键挥砍叠连击 / 右键蓄力 0.6 秒放“逝言”）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 致万千无言者监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

// ---------- 心跳 ----------
// ★ 自带心跳（**不依赖月镰的 gsMsPulse**）：一旦月镰的监听器注册失败，
//   它的 gsMsStart 会直接 return（**不**开心跳）⇒ 挂在它上面的模块会一起停摆。
//   本模块用自己的 runRepeating + 自己的世界世代号，互不影响。
//   读不到世代号（null）⇒ 一律按自己的算，绝不让心跳因为 PDC 不可用而停。
function gsVoGenNow() {
    try {
        if (GS_VO_PDT == null || GS_VO_K_GEN == null) return null;
        var w = getServer().getWorlds().get(0);
        var v = w.getPersistentDataContainer().get(GS_VO_K_GEN, GS_VO_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}

function gsVoGenBump() {
    try {
        var cur = gsVoGenNow();
        if (cur == null) cur = 0;                  // 读不到就从 0 起算
        GS_VO_GEN = (cur | 0) + 1;
        if (GS_VO_PDT == null || GS_VO_K_GEN == null) return;
        var w = getServer().getWorlds().get(0);
        w.getPersistentDataContainer().set(GS_VO_K_GEN, GS_VO_PDT.INTEGER, GS_VO_GEN);
    } catch (e) { if (GS_VO_GEN == null || GS_VO_GEN <= 0) GS_VO_GEN = 1; }
}

function gsVoPulse() {
    var mine = true;
    try {
        var g = gsVoGenNow();
        if (g != null && GS_VO_GEN !== -1) mine = (g === GS_VO_GEN);
    } catch (e) { }
    if (!mine) { gsVoReleaseAll(); return; }
    try {
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsVoPlayerTick(p); } catch (e2) { }
        }
    } catch (e) { }
    try { gsVoBarsClean(); } catch (e) { }
}

function gsVoStart() {
    try {
        // ★ bump 自己的世代号：/rsc reload 后旧实例读到新号 ⇒ 自己收手
        gsVoGenBump();

        if (!gsVoRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 致万千无言者启动失败：监听器没注册上（连击血条仍会跑）'); } catch (e) { }
        }
        runRepeating(gsVoPulse, 20, 2);      // 每 2 刻（0.1 秒）维护连击血条 / 清理
        try {
            var enchOk = (gsVoEnchType() != null);
            getServer().getLogger().info('[构思技艺] 致万千无言者（' + GS_VO_ID + '）已加载 · 挥砍 '
                + GS_VO_DMG_BASE + '~' + gsVoDmg(GS_VO_MAX) + ' 点（随连击 +' + GS_VO_DMG_PER
                + '/层）/ 满 ' + GS_VO_MAX + ' 连击 / 窗口 ' + (GS_VO_WINDOW / 1000)
                + ' 秒 · 逝言 蓄力 ' + (GS_VO_QI_CHARGE / 20) + ' 秒 / ' + GS_VO_QI_DMG
                + ' 点 / 射程 ' + GS_VO_QI_RANGE + ' 格 / 宽 ' + GS_VO_QI_WIDE
                + ' 格 / 穿透 ' + GS_VO_QI_PIERCE + ' 个 / 冷却 ' + (GS_VO_QI_CD / 1000)
                + ' 秒 · 附魔文字粒子 ' + (enchOk ? '可用' : '不可用') + ' · 世界世代号 ' + GS_VO_GEN);
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 致万千无言者启动异常: ' + e); } catch (e2) { }
    }
}

gsVoStart();

// ==========================================================================
// 此刻万籁俱寂（GS_SILENCE · 构思武器第 6 格 · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §21
//   ★ 以下 2 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//         （半径 GS_SI_RANGE + 1）—— 视线能指任何方向，不能再拿"前方 2.5 格"当中心。
//     ⇒ 服务端会**反复收到 `PlayerInteractEvent(RIGHT_CLICK_AIR)`**。
// ==========================================================================
var GS_SI_ID          = 'GS_SILENCE';
var GS_SI_NO_BREAK    = true;      // true = 手持时挖不动方块（金镐不许挖矿）
var GS_SI_HIT_PLAYERS = false;     // false = 斩击 / 噤言不打其它玩家（只打生物），防误伤

// ---- 被动：连击「寂」----
var GS_SI_MAX        = 99;         // 最高连击数
var GS_SI_WINDOW     = 7000;       // 命中窗口（毫秒）= **7 秒**（★ 第四十批：5 秒 → 7 秒）
var GS_SI_DMG_BASE   = 17.0;       // 初始伤害（1 连击时）
var GS_SI_DMG_PER    = 2.0;        // 每多一层的加成 ⇒ 满 99 层 = 17 + 2×98 = 213
var GS_SI_BAR_COL    = 'RED';      // 血条颜色（红，与「寂」的亮红呼应）

// ---- 左键：斩击（宽 3 × 长 5 × 高 2）----
var GS_SI_WIDE       = 3.0;        // 宽（左右各 1.5 格）
var GS_SI_RANGE      = 5.0;        // 长（前方 5 格）
var GS_SI_H          = 2.0;        // 高（2 格）
var GS_SI_SWING_CD   = 320;        // 挥砍冷却（毫秒）

// ---- ★★ 右键「寂灭」（以玩家为圆心的 7 格圆圈 AoE）----
//   用户：「右键效果改成，对7格半径的圆圈范围内敌人**直接**造成**基础伤害x2**的伤害，
//           **叠加的层数也要算进去**，**冷却15秒**」
//   ★ 旧的「按住蓄力·噤声 / 松手追踪·噤言」那一整套**已整体删除**
//     （它靠"客户端重复发右键包"来判松手，本身是个绕路方案；
//       改成瞬发 AoE 之后连"松手判定"都不需要了）。
//   ★ **圆心 = 玩家自己**（用户确认）⇒ 360° 一圈，前后左右都打；
//     与阎魔刀第 6 刀「以玩家为中心画圆圈」是同一种做法。
//   ★ 判定形状：**水平半径 ≤ 7** 且 **竖直差 ≤ 7**（一个扁圆柱，
//     免得隔着好几层楼把头顶 / 脚下的怪也一起打了）。
//   ★ 伤害 = `gsSiDmg(当前层数) × 2` —— `gsSiDmg()` 会把 0 层夹到 1 层，
//     所以**没叠层时也保底 17×2 = 34 点**；满 99 层 = 213×2 = **426 点**。
//   ★★ 右键**不叠层**（用户确认）—— 层数仍然只靠左键斩击叠。
var GS_SI_BURST_R    = 7.0;        // 圆圈半径（格）
var GS_SI_BURST_MUL  = 2.0;        // 伤害倍率（基础伤害 ×2）
var GS_SI_BURST_CD   = 15000;      // 冷却 15 秒（毫秒）
var GS_SI_BURST_FX_MS = 400;       // 圆圈特效停留 0.4 秒（由心跳重画）
var GS_SI_BURST_FX_N  = 96;        // 圆周采样点数

var GS_SI_REGISTERED = false;
var GS_SI_CLICK_AT   = {};         // uuid|L -> 上次处理毫秒（去抖）
var GS_SI_SWING_AT   = {};         // uuid -> 上次挥砍毫秒（冷却）
var GS_SI_SIDE       = {};         // uuid -> 0 / 1（斜劈左起 / 右起交替）
var GS_SI_ST         = {};         // uuid -> { n, until }（内存权威）
var GS_SI_BARS       = {};         // uuid -> { b, p }（★ 本模块专属表）
var GS_SI_BURST_AT   = {};         // ★★ 第五十三批：uuid -> 上次「寂灭」毫秒（15 秒冷却）
var GS_SI_BURST_FX   = [];         // ★★ 第五十三批：正在淡出的圆圈特效 { w, x, y, z, until }
var GS_SI_GEN        = -1;         // 加载那一刻的世界世代号（判 /rsc reload 旧实例）

var GS_SI_K_GEN = null, GS_SI_PDT = null;
try {
    var SI_NK = Java.type('org.bukkit.NamespacedKey');
    // ★★★ 必须 fromString —— new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败
    GS_SI_K_GEN = SI_NK.fromString('gs:si_gen');
    GS_SI_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

function gsSiIs(item) { return gsIsItem(item, GS_SI_ID); }

function gsSiHand(p) {
    try { return p.getInventory().getItemInMainHand(); } catch (e) { return null; }
}

// ---------- 连击「寂」：读写（内存权威，不写 PDC） ----------
function gsSiGet(p) {
    var o = { n: 0, until: 0 };
    try {
        if (p == null) return o;
        var m = GS_SI_ST['' + p.getUniqueId()];
        if (m != null) { o.n = m.n | 0; o.until = 0 + m.until; }
    } catch (e) { }
    return o;
}

function gsSiSet(p, n, until) {
    try {
        if (p == null) return;
        var u = '' + p.getUniqueId();
        var nn = n | 0;
        if (nn <= 0) { try { delete GS_SI_ST[u]; } catch (e) { } }
        else GS_SI_ST[u] = { n: nn, until: 0 + until };
    } catch (e) { }
}

// 当前连击对应的伤害：1 连击 = 17 点，每多一层 +2 ⇒ 满 99 层 = 213 点
function gsSiDmg(n) {
    var k = n | 0;
    if (k < 1) k = 1;
    if (k > GS_SI_MAX) k = GS_SI_MAX;
    return GS_SI_DMG_BASE + GS_SI_DMG_PER * (k - 1);
}

// ---------- 连击「寂」：BossBar ----------
// ★ 标题一律喂**纯字符串**（§ 颜色码），绝不要把 Adventure Component 塞进 setTitle
// —— 在月镰 / 蚀月上踩过，会被 GraalJS 隐式 toString() 成一长串 dump。
// ★ 按需求：**不用 `N/99`**，直接 `寂 N`；「寂」亮红 §c、数字暗红 §4。
function gsSiTitle(n) { return GS_MS_C.RED + '寂 ' + GS_MS_C.DARK_RED + (n | 0); }

function gsSiBarColor() {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        var name = GS_SI_BAR_COL;
        try { var c = C.valueOf(name); if (c != null) return c; } catch (e) { }
        try { var c2 = C[name]; if (c2 != null) return c2; } catch (e) { }
    } catch (e) { }
    return null;
}

function gsSiBarOf(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_SI_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsSiBarColor(), s = gsMsBarStyle();
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(gsSiTitle(0), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_SI_BARS[u] = { b: bar, p: p };
        return bar;
    } catch (e) { return null; }
}

function gsSiBarShow(p, n, left, total) {
    try {
        var b = gsSiBarOf(p);
        if (b == null) return;
        var prog = (total > 0) ? (left / total) : 0;
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
        gsBarTitleSet(b, gsSiTitle(n));
        try { b.setProgress(prog); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
    } catch (e) { }
}

function gsSiBarHide(p) { gsBarTblHide(GS_SI_BARS, p); }

function gsSiClear(p) {
    try { gsSiSet(p, 0, 0); } catch (e) { }
    try { gsSiBarHide(p); } catch (e) { }
}

// ---------- 连击「寂」：加层 / 到点清零 ----------
function gsSiAdd(p) {
    try {
        var now = gsMsNow();
        var st = gsSiGet(p);
        var n = (st.n | 0) + 1;
        if (n > GS_SI_MAX) n = GS_SI_MAX;
        gsSiSet(p, n, now + GS_SI_WINDOW);
        gsSiBarShow(p, n, GS_SI_WINDOW, GS_SI_WINDOW);
        if (n >= GS_SI_MAX) gsMsSoundAt(p, 'block.beacon.activate', 0.9, 1.2);
    } catch (e) { }
}

// 每 2 刻维护：血条倒计时；窗口过完 ⇒ **直接清 0**（用户指定，不是逐层衰减）
function gsSiPlayerTick(p) {
    try {
        var st = gsSiGet(p);
        if (st.n <= 0) { gsSiBarHide(p); return; }
        var now = gsMsNow();
        if (st.until <= 0) {                       // 自愈：层数在、计时丢了 ⇒ 补一份
            gsSiSet(p, st.n, now + GS_SI_WINDOW);
            gsSiBarShow(p, st.n, GS_SI_WINDOW, GS_SI_WINDOW);
            return;
        }
        var left = st.until - now;
        if (left <= 0) {
            gsSiClear(p);                          // ★ 5 秒未命中 ⇒ 连击**直接清 0**
            gsMsSoundAt(p, 'block.beacon.deactivate', 0.6, 1.2);
            return;
        }
        gsSiBarShow(p, st.n, left, GS_SI_WINDOW);
    } catch (e) { }
}

function gsSiBarsClean() { gsBarTblClean(GS_SI_BARS); }

function gsSiReleaseAll() {
    try {
        for (var k in GS_SI_BARS) {
            try { GS_SI_BARS[k].b.removeAll(); } catch (e) { }
            try { delete GS_SI_BARS[k]; } catch (e) { }
        }
    } catch (e) { }
    try {
        for (var c in GS_SI_BURST_AT) { try { delete GS_SI_BURST_AT[c]; } catch (e) { } }
    } catch (e) { }
    try { GS_SI_BURST_FX = []; } catch (e) { }
}

// ---------- 目标筛选 / 伤害器 ----------
function gsSiCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !GS_SI_HIT_PLAYERS) return false;
 // ★★ 非活物（掉落物 / 经验球 / 展示实体 / 盔甲架 …）**不算命中**
        //   —— 否则对着地上一堆掉落物挥几下就能把「寂」无痛叠满
        if (!gsIsCombatTarget(ent, tn)) return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// 复用月镰那套「只掉血、不击退、不留无敌帧」的伤害器
//   （里面会 bump GS_WPN_DMG_GUARD ⇒ 末尾「取消原版近战」的监听器会放行这一次）
function gsSiHurt(ent, dmg, p) { gsMsHurt(ent, dmg, p); }

// ★★ `gsSiNearest`（旧「噤言」的追踪目标搜索）**已随旧右键一起删除** ——
//   新的「寂灭」是瞬发 AoE，不需要"找最近的敌人"。

// ---------- 左键：斩击（附魔台文字 · 斜劈弧线，两刀一左一右交替） ----------
function gsSiSwing(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_SI_SWING_AT[u];
        if (last != null && (now - last) < GS_SI_SWING_CD) return;
        GS_SI_SWING_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { return; }

 // ★★★ **跟着准星**（3D 视线），不再只跟水平朝向。
        //   用户原话：「此刻万籁俱寂只会对朝向攻击，不会对玩家准星，
        //              改成对玩家准星攻击」
        //   ★ 原来只取 dir 的 X/Z 再归一化（把 dir.getY() 直接丢掉）⇒
        //     抬头 / 低头砍出去的永远是**水平**那一条 ⇒ 「瞄着头顶 / 脚边的怪砍」砍不到。
        //     现在整条 3D 视线都算数。
        //   ★ 正交基（三个轴两两垂直，箱体跟着视线一起倾斜）：
        //       forward = 视线本身（单位向量，含 Y）
        //       right   = forward × 世界上方 = (-fz, 0, fx) 归一化 ⇒ **永远水平**
        //       up      = right × forward                     ⇒ 与视线垂直的"上"
        var fx = dir.getX(), fy = dir.getY(), fz = dir.getZ();
        var fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1.0e-4) { fx = 0.0; fy = 0.0; fz = 1.0; fl = 1.0; }
        fx /= fl; fy /= fl; fz /= fl;

        var rx = -fz, ry = 0.0, rz = fx;                      // 水平右向（未归一化）
        var rl = Math.sqrt(rx * rx + rz * rz);
        if (rl < 1.0e-4) { rx = 1.0; ry = 0.0; rz = 0.0; }    // 正上/正下看 ⇒ 退化用 +X
        else { rx /= rl; rz /= rl; }

        var ux = ry * fz - rz * fy;                           // up = right × forward
        var uy = rz * fx - rx * fz;
        var uz = rx * fy - ry * fx;

        // 斜劈交替：side 0 = 第一刀（左1 → 左3），side 1 = 第二刀（左3 → 左1）
        var side = (GS_SI_SIDE[u] == null) ? 0 : (GS_SI_SIDE[u] | 0);
        GS_SI_SIDE[u] = (side === 0) ? 1 : 0;

        // 特效：附魔台文字沿斜劈弧线
        //   第 1 刀：（长1 左1 高1）→（长5 左3 高2）
        //   第 2 刀：（长1 左3 高1）→（长5 左1 高2）
 // ★★ 「高」这一轴由**世界上方**改成**与视线垂直的上方(ux,uy,uz)**
        //      ⇒ 整条弧线跟着准星一起倾斜（抬头砍就是往斜上划）。
        //      ★ 水平看时 up = (0,1,0) ⇒ 弧线坐标与改前**逐位相同**（老测试原样通过）。
        var pt = gsVoEnchType();
        var latS = (side === 0) ? -1.0 : 1.0;     // 左1 = -1（最左）/ 左3 = +1（最右）
        var latE = (side === 0) ? 1.0 : -1.0;
        var N = 18;
        for (var i = 0; i <= N; i++) {
            var t = i / N;
            var f   = 1.0 + 4.0 * t;                  // 长 1 → 5
            var lat = latS + (latE - latS) * t;       // 左1 → 左3（或反向）
            var h   = 1.0 + 1.0 * t;                  // 高 1 → 2
            gsMsDust(world, pt, null,
                loc.getX() + fx * f + rx * lat + ux * h,
                loc.getY() + fy * f + ry * lat + uy * h,
                loc.getZ() + fz * f + rz * lat + uz * h);
        }
        gsMsSound(world, loc, 'entity.player.attack.sweep', 0.8, 1.1);

        // ★ 伤害判定：整个 **宽 3 × 长 5 × 高 2** 的长方体（不是一条线）
 // ★★ 这个长方体现在**跟着准星一起转**（不再只是水平那一层）——
        //      三个投影轴换成上面那组正交基：
        //        前向 fwd = 沿准星（0 ~ 5）
        //        左右 lat = 沿 right（±1.5）
        //        上下 upj = 沿 up（±GS_SI_H/2 = ±1）⇒ 抬头砍头顶、低头砍脚下
        //      ★ 判定原点 = 玩家**胸口**（脚 + 1.0）。
        //      ★ 水平看时 fy=0、up=(0,1,0) ⇒ 三个投影退化成改前的
        //        「水平前向 / 水平右向 / 世界 Y 差」⇒ 手感与改前完全一致。
        var st = gsSiGet(p);
        var dmg = gsSiDmg((st.n | 0) + 1);            // ★ 按"这一刀打出去之后"的层数算
        var selfId = u;
        var hit = {};
        var count = 0;
        var halfW = GS_SI_WIDE * 0.5;                 // 左右各 1.5 格
        var px0 = loc.getX(), py0 = loc.getY() + 1.0, pz0 = loc.getZ();
        // 搜索盒：以**胸口**为中心、半径 GS_SI_RANGE + 1 的立方体
        //   ★ 视线可以指任何方向 ⇒ 不能再拿「前方 2.5 格」当中心
        //     （那样「低头砍脚下」会整个漏掉）。立方体一定包得住那个 5 格长的箱子。
        var cbox = gsMsLoc(world, px0, py0, pz0);
        if (cbox != null) {
            var half = GS_SI_RANGE + 1.0;
            var list = null;
            try { list = world.getNearbyEntities(cbox, half, half, half); } catch (e) { list = null; }
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (!gsSiCanHit(ent, selfId)) continue;
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                    if (hit[eid] != null) continue;
                    var el = null;
                    try { el = ent.getLocation(); } catch (e) { continue; }
                    var ex = el.getX() - px0;
                    var ey = (el.getY() + 0.9) - py0;              // 怪身体中心 vs 玩家胸口
                    var ez = el.getZ() - pz0;
                    var fwd = ex * fx + ey * fy + ez * fz;         // 沿准星（0~5）
                    var lat = ex * rx + ey * ry + ez * rz;         // 左右（±1.5）
                    var upj = ex * ux + ey * uy + ez * uz;         // 与准星垂直的上下（±1）
                    if (fwd < -0.4 || fwd > GS_SI_RANGE) continue;      // 身后 0.4 容差
                    if (lat < -halfW || lat > halfW) continue;
                    if (Math.abs(upj) > (GS_SI_H * 0.5)) continue;
                    hit[eid] = 1;
                    gsSiHurt(ent, dmg, p);
                    count++;
                }
            }
        }
        if (count > 0) gsSiAdd(p);                    // ★ 命中 ≥ 1 个生物 ⇒ 连击 +1
    } catch (e) { }
}

// ---------- ★★ 右键「寂灭」----------
//   以**玩家自己**为圆心、半径 **GS_SI_BURST_R(7)** 的圆圈，圈内敌人**直接**吃
//   `gsSiDmg(当前层数) × GS_SI_BURST_MUL(2)` 点伤害；冷却 **GS_SI_BURST_CD(15 秒)**。
//   ★ 判定形状 = **水平半径 ≤ 7** 且 **竖直差 ≤ 7**（扁圆柱）。
//   ★ 目标筛选沿用 `gsSiCanHit` ⇒ 玩家（默认）/ 掉落物 / 经验球 / 盔甲架一律不算。
//   ★★ 右键**不叠层**（层数只靠左键叠，与用户确认过）。
function gsSiBurst(p) {
    try {
        if (p == null) return false;
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_SI_BURST_AT[u];
        if (last != null && (now - last) < GS_SI_BURST_CD) return false;   // 冷却中
        GS_SI_BURST_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return false; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return false; }

        // ★ 伤害按**当前层数**算；gsSiDmg 会把 0 层夹到 1 层 ⇒ 没叠层也保底 17×2 = 34
        var st = gsSiGet(p);
        var dmg = gsSiDmg(st.n) * GS_SI_BURST_MUL;

        var px = loc.getX(), py = loc.getY(), pz = loc.getZ();
        var selfId = u;
        var r2 = GS_SI_BURST_R * GS_SI_BURST_R;
        var hit = {};
        var count = 0;

        // 先用一个立方体把附近的实体捞出来，再自己按"圆圈"精确筛
        var cbox = gsMsLoc(world, px, py + 1.0, pz);
        if (cbox != null) {
            var list = null;
            try {
                list = world.getNearbyEntities(cbox, GS_SI_BURST_R, GS_SI_BURST_R, GS_SI_BURST_R);
            } catch (e) { list = null; }
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (!gsSiCanHit(ent, selfId)) continue;
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                    if (hit[eid] != null) continue;
                    var el = null;
                    try { el = ent.getLocation(); } catch (e) { continue; }
                    var dx = el.getX() - px, dz = el.getZ() - pz;
                    if (dx * dx + dz * dz > r2) continue;                       // 水平半径
                    if (Math.abs(el.getY() - py) > GS_SI_BURST_R) continue;     // 竖直容差
                    hit[eid] = 1;
                    gsSiHurt(ent, dmg, p);
                    count++;
                }
            }
        }

        // 特效 + 音效
        gsSiBurstDraw(world, px, py, pz);
        try { GS_SI_BURST_FX.push({ w: world, x: px, y: py, z: pz, until: now + GS_SI_BURST_FX_MS }); } catch (e) { }
        gsMsSoundAt(p, 'entity.wither.spawn', 0.8, 0.6);
        gsMsSoundAt(p, 'block.beacon.deactivate', 1.0, 0.7);
        gsMsSoundAt(p, 'entity.evoker.cast_spell', 1.0, 0.5);
        return true;
    } catch (e) { return false; }
}

// 画那圈 7 格半径的圆：脚边一圈 + 胸口一圈（半密度），看着是个"圈"而不是一条线
function gsSiBurstDraw(world, x, y, z) {
    try {
        var pt = gsVoEnchType();
        if (pt == null) return;
        var n = GS_SI_BURST_FX_N;
        if (n < 8) n = 8;
        for (var i = 0; i < n; i++) {
            var a = (i / n) * 2.0 * Math.PI;
            var cx = x + Math.cos(a) * GS_SI_BURST_R;
            var cz = z + Math.sin(a) * GS_SI_BURST_R;
            gsMsDust(world, pt, null, cx, y + 0.3, cz);
            if ((i % 2) === 0) gsMsDust(world, pt, null, cx, y + 1.8, cz);
        }
    } catch (e) { }
}

// 圆圈特效的淡出（由 gsSiPulse 每 2 刻重画一次，到期就丢）
function gsSiFxTick() {
    try {
        if (GS_SI_BURST_FX.length === 0) return;
        var now = gsMsNow();
        var keep = [];
        for (var i = 0; i < GS_SI_BURST_FX.length; i++) {
            var f = GS_SI_BURST_FX[i];
            if (f == null) continue;
            if (now > f.until) continue;                 // 到期 ⇒ 不再重画
            gsSiBurstDraw(f.w, f.x, f.y, f.z);
            keep.push(f);
        }
        GS_SI_BURST_FX = keep;
    } catch (e) { }
}

// ---------- 去抖 ----------
function gsSiClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_SI_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_SI_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

function gsSiRecentRight(p, gapMs) {
    try {
        var last = GS_SI_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsMsNow() - last) < gapMs;
    } catch (e) { return false; }
}

// ---------- 注册同步监听器 ----------
function gsSiRegisterSync() {
    if (GS_SI_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        // 注册助手（与月镰 / 蚀月 / 致万千无言者同款：失败时退回 JavaAdapter 写法）
        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ① 左键 = 斩击；右键 = 噤声（按住蓄力）
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    // ★ 故意不检查 e.isCancelled()：有些前置插件会先取消 *_CLICK_AIR，
                    //   一检查就变成"必须点方块才放得出来"。重复注册交给去抖。
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsSiIs(gsSiHand(p))) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的事件），
                        //    但玩家真主手拿着本物品 ⇒ 那是本附属自己派发的假事件，放过，
                        //    否则机器每 0.5 秒就会替旁边的人**误刷一次蓄力心跳**。
                        //    真人右键时 event.getItem() 一定就是手里那件物品，不会误伤。
                        try {
                            var evItem = e.getItem();
                            var evName = (evItem == null) ? '' : ('' + evItem.getType().name());
                            if (evName.length === 0 || evName === 'AIR') return;
                        } catch (e2i) { }

                        // ★ 取消右键交互（金镐不许挖矿 / 不许开箱子 / 不许放方块）
                        e.setCancelled(true);
                        if (Res != null) {
                            try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                            try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                        }
 // ★★ 右键 = 瞬发「寂灭」（7 格圆圈 AoE，冷却 15 秒）
                        //   ★ 按住右键时客户端会**反复发包**，冷却会把重复的那些挡掉
                        GS_SI_CLICK_AT['' + p.getUniqueId() + '|R'] = gsMsNow();
                        gsSiBurst(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsSiClickOnce(p, 'L')) return;
                        if (GS_SI_NO_BREAK) {
                            e.setCancelled(true);
                            if (Res != null) {
                                try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                                try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                            }
                        }
                        gsSiSwing(p);
                    }
                } catch (e3) { }
            });
        }

        // ② 左键兜底：挥手动画（对着空气点也会发；与 ① 共用去抖 ⇒ 不会放两次）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsSiIs(gsSiHand(p))) return;
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsSiRecentRight(p, 300)) return;    // 右键附带的挥手 → 忽略
                    if (!gsSiClickOnce(p, 'L')) return;
                    gsSiSwing(p);
                } catch (e3) { }
            });
        }

        // ③ 手持时挖不动方块（双保险：即使 ① 的取消被别的插件放行也挖不掉）
        if (GS_SI_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsSiIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
            var DmgBlkEv = null;
            try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
            if (DmgBlkEv != null) {
                reg(DmgBlkEv, function (e) {
                    try {
                        if (!gsSiIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

        GS_SI_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 此刻万籁俱寂监听器注册成功（左键 3×5×2 斩击叠连击 / 右键瞬发“寂灭” 7 格圆圈 AoE）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 此刻万籁俱寂监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

// ---------- 心跳 / 世代号 ----------
function gsSiGenNow() {
    try {
        if (GS_SI_PDT == null || GS_SI_K_GEN == null) return null;
        var w = getServer().getWorlds().get(0);
        var v = w.getPersistentDataContainer().get(GS_SI_K_GEN, GS_SI_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}

function gsSiGenBump() {
    try {
        var cur = gsSiGenNow();
        if (cur == null) cur = 0;
        GS_SI_GEN = (cur | 0) + 1;
        if (GS_SI_PDT == null || GS_SI_K_GEN == null) return;
        var w = getServer().getWorlds().get(0);
        w.getPersistentDataContainer().set(GS_SI_K_GEN, GS_SI_PDT.INTEGER, GS_SI_GEN);
    } catch (e) { if (GS_SI_GEN == null || GS_SI_GEN <= 0) GS_SI_GEN = 1; }
}

function gsSiGenOk() {
    try {
        var g = gsSiGenNow();
        if (g == null || GS_SI_GEN === -1) return true;    // 读不到世代号 ⇒ 一律按自己的算
        return (g === GS_SI_GEN);
    } catch (e) { return true; }
}

function gsSiPulse() {
    if (!gsSiGenOk()) { gsSiReleaseAll(); return; }
    try {
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsSiPlayerTick(p); } catch (e2) { }
        }
    } catch (e) { }
    try { gsSiBarsClean(); } catch (e) { }
    try { gsSiFxTick(); } catch (e) { }
}

function gsSiStart() {
    try {
        gsSiGenBump();

        if (!gsSiRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 此刻万籁俱寂启动失败：监听器没注册上（连击血条仍会跑）'); } catch (e) { }
        }
        runRepeating(gsSiPulse, 20, 2);      // 每 2 刻（0.1 秒）维护连击血条 / 清理
        try {
            var enchOk = (gsVoEnchType() != null);
            getServer().getLogger().info('[构思技艺] 此刻万籁俱寂（' + GS_SI_ID + '）已加载 · 斩击 '
                + GS_SI_DMG_BASE + '~' + gsSiDmg(GS_SI_MAX) + ' 点（随连击 +' + GS_SI_DMG_PER
                + '/层 · 满 ' + GS_SI_MAX + ' 连击）/ 范围 宽' + GS_SI_WIDE + '×长' + GS_SI_RANGE
                + '×高' + GS_SI_H + ' / 窗口 ' + (GS_SI_WINDOW / 1000) + ' 秒（未命中**清 0**）'
                + ' · 寂灭 半径 ' + GS_SI_BURST_R + ' 格圆圈 × ' + GS_SI_BURST_MUL
                + ' 倍伤害（' + (gsSiDmg(1) * GS_SI_BURST_MUL) + '~'
                + (gsSiDmg(GS_SI_MAX) * GS_SI_BURST_MUL) + ' 点）· 冷却 '
                + (GS_SI_BURST_CD / 1000) + ' 秒 · 不叠层 · 附魔文字粒子 '
                + (enchOk ? '可用' : '不可用')
                + ' · 世界世代号 ' + GS_SI_GEN);
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 此刻万籁俱寂启动异常: ' + e); } catch (e2) { }
    }
}

gsSiStart();

// ==========================================================================
// 阎魔刀·改（GS_ENMA_BLADE · 构思武器第 9 格 · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §22
//   ★ 以下 4 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//   ★ 配方：**冶炼炉（SMELTERY）无序合成**（★ 第五十批新增，九样材料各 1 个）。
//       重画间隔 0.1 秒 → **0.25 秒**，且 `fade` 低时隔点画
//          · 没记录时 ⇒ 末行显示 `锚点 » 未记录`
//        以"锚点"开头），所以 items.yml 里那句「&6右键&7锚点」**不会被误删**
// ==========================================================================
var GS_EM_ID          = 'GS_ENMA_BLADE';
var GS_EM_NO_BREAK    = true;      // true = 手持时挖不动方块
var GS_EM_HIT_PLAYERS = false;     // false = 只打生物，不打玩家（防误伤）

// ---- 左键 · 居合斩 ----
var GS_EM_DMG        = 37.0;       // ★★ 第五十一批：基础伤害 **23 → 37**（用户：左键基础伤害改成37）
 // ★ 演变：7（）→ 23（）→ **37**（）
var GS_EM_WIDE       = 5.0;        // 判定宽（左右各 2.5）
var GS_EM_RANGE      = 9.0;        // 判定长（前方 9 格）
var GS_EM_H          = 3.0;        // 判定高
var GS_EM_SLASH_N    = 6;          // 六刀一循环
var GS_EM_CD         = 200;        // ★ 第四十九批：左键冷却 **500 → 200**（毫秒）= 0.2 秒
 // ★ 演变：0.8 → 0.5（）→ **0.2**（）
                                   //   ★ 比去抖门槛（80ms）大 ⇒ 不会出现"连点被去抖吃掉"
var GS_EM_FX_MS      = 400;        // ★ 第四十九批：斩击特效存在 **600 → 400**（毫秒）= 0.4 秒
 // ★ 演变：0.8 → 0.6（）→ **0.4**（）
var GS_EM_RESET_MS   = 3000;       // 停手多久清空计数器（毫秒）= 3 秒
var GS_EM_BAR_COL    = 'PURPLE';   // 血条颜色
var GS_EM_ARCH        = 0.62;      // ★★ 前五刀**前方进深**的压扁比例（第四十三批：扁一点）
                                   //    半圆的前方鼓出量 = 半径 ⇒ 0.62 = **扁一点的半圆**
                                   //    ★ 只压"进深"（前方），**上下/斜向的幅度是足的**
                                   //      ⇒ 走势仍然是从下到上 / 从上到下 / 斜着切
var GS_EM_S6_R       = 4.5;        // ★ 第 6 刀的圆圈半径（格）
var GS_EM_S6_TILT    = -0.40;      // ★ 圆圈的倾斜角（绕准星轴）⇒ 负值 = **左高右低**
var GS_EM_S6_N       = 64;         // ★ 圆圈采样点数
var GS_EM_S6_DMG     = 77.0;       // ★★ 第 6 刀**圆圈范围内**的**额外**伤害
 // （55 → **77**，用户：第六刀的圆圈伤害77）
 // ★ 演变：13（）→ 55（）→ **77**（）
                                   //   ★ 与基础伤害 37 **叠加** ⇒ 圈内最多 37 + 77 = **114**
 // （曾是 23 + 55 = 78）
var GS_EM_S6_THICK   = 3.0;        // ★ 圆盘的厚度容差（格，沿圆面法线方向）
                                   //   圆盘是"薄饼"，而且**是斜的** ⇒ 侧面 4.5 格处的
                                   //   地面怪会离圆面 2.2 格，容差给到 3.0 才判定得进去；
                                   //   半径那一关仍然卡死 4.5 ⇒ 不会变成"打全屏"。

// ---- 左键第六刀 · 次元斩（★★ 从右键挪来 · 冷却删除 · 持续 1.2 秒）----
var GS_EM_DS_DIST    = 4.0;        // 落点在身前第 4 格
var GS_EM_DS_R       = 3.0;        // 作用半径 3 格
var GS_EM_DS_DMG     = 25.0;       // ★ 第四十五批：每跳伤害 **9 → 25**（用户：右键的次元斩每次伤害25）
var GS_EM_DS_EVERY   = 500;        // 每 0.5 秒造成一次伤害（毫秒）
var GS_EM_DS_LIFE    = 1200;       // ★★ 第六十四批：存在 **4 秒 → 1.2 秒**
                                   //   （用户「技能持续时间改成1.2秒」）
                                   //   ★ 心跳每 0.1 秒一跳、每 0.5 秒打一次伤害
                                   //     ⇒ 1.2 秒内大约打出 2 跳（0.5 / 1.0 秒各一次）
// ★★ 第六十四批：`GS_EM_DS_CD`（原 7 秒冷却）**整个删除**
//   （用户「吸引次元斩的冷却删除」）—— 只有第六刀才出得来，六刀一循环就是它的节奏。
var GS_EM_DS_PULL    = 0.42;       // 吸引强度（每 0.1 秒拽一次的水平速度）
// ★★★ 次元斩的画面改成**红紫色大圆环**（用户「改成红紫色大圆环，不要圆柱了」）
//   · 旧版画的是「四刀（左斜/横斩/竖斩/右斜）× **前后两层**」⇒ 沿准星方向看是个**圆柱**。
//   · 新版画**一个正圆**，落在**准星横截面**内 ⇒ 深度上是一张平面，圆柱彻底消失。
//   · 环的半径就用下面这个 `GS_EM_DS_BIG`（3.0 格 ⇒ 直径 6 格），与伤害半径
//     `GS_EM_DS_R`（3.0）对齐 —— 看到多大环，就是多大范围。
var GS_EM_DS_BIG     = 3.0;        // ★★ 第六十四批修正 3：**大圆环的半径**（格）
                                   //   （原注释是"四刀的大范围半径"；四刀已整体改成圆环）
var GS_EM_DS_RING_N  = 48;         // ★★ 第六十四批修正 3：大圆环的**圆周采样点数**
                                   //   （每 7.5° 一个点；周长 2π×3 ≈ 18.85 格
                                   //     ⇒ 相邻点约 0.39 格，看起来是连续的环）
var GS_EM_DS_DRAW_MS = 250;        // ★ 画面重画间隔（第四十二批：0.1 秒 → **0.25 秒** ⇒ 粒子更稀）

// ---- Shift+右键 · 裂缝（★★ 从 Shift+F 改到 Shift+右键）----
var GS_EM_CRACK_DIST  = 2.0;       // 裂缝出现在身前 2 格
var GS_EM_CRACK_HALF  = 3.5;       // 裂缝半宽 ⇒ 全长 7 格
var GS_EM_CRACK_H     = 3.0;       // 裂缝高 3 格
var GS_EM_CRACK_MIDH  = 1.5;       // 横线高度（格）
// ★★ **裂缝冷却 5 秒 = 绘画 2 秒 + 滞留 3 秒**
//    （用户：撕开裂缝的冷却为 5 秒（2 秒绘画 3 秒滞留））
//    · `GS_EM_CRACK_DRAW` 1 秒 → **2 秒**（绘画变慢，用户点名要 2 秒）
//    · `GS_EM_CRACK_KEEP` 3 秒（**没动**）
//    ⇒ 裂缝**总寿命正好 5 秒**，与冷却**完全对齐** ⇒ 同一时刻只会有一道裂缝。
var GS_EM_CRACK_DRAW  = 2000;      // ★ 绘画时间 **2 秒**（第四十八批：1 → 2）
var GS_EM_CRACK_KEEP  = 3000;      // 留存时间 3 秒（毫秒）
var GS_EM_CRACK_CD    = 5000;      // ★★ 冷却 **5 秒** = 绘画 2 秒 + 滞留 3 秒
// ★★ 第四十七 / 四十八批：菱形大小按用户给的区间定死 ——
//    · 横向：**左1.5 ~ 左6.5 ⇒ 宽 5.0、关于左4.0 对称**
//      ★ 裂缝「长 7」的编号是 **左1 ~ 左7** ⇒ 正中间就是**左4.0**（= 局部 l = 0）
//        ⇒ 半宽 = (6.5 - 1.5) / 2 = **2.50**，菱形**居中**在十字交点上。
// ★ 演变：左2.5~左5.5（半宽 1.50）
// → 左1~左7（半宽 3.00，用户「改成宽 6」）
// → 用户「宽 -1」⇒ 6.0 - 1.0 = **5.0** ⇒ 半宽 **2.50**
//                ★ 仍**居中** ⇒ 就是 左1.5 ~ 左6.5。
//      ★ 菱形半宽 2.50 < 裂缝半宽 3.50 ⇒ 仍**完全落在裂缝里面**，不会超出。
//    · 纵向：**高0.7 ~ 高2.3 ⇒ 高 1.6、关于高1.5 对称**
// ★ 用户「高 -0.8」⇒ 2.4 - 0.8 = **1.6** ⇒ 半高 **0.80**。
//      ★ 高1.5 正是裂缝的竖直中点（`GS_EM_CRACK_MIDH`）⇒ 上下**仍然居中**。
//    ⇒ 现在是一个 **5.0 宽 × 1.6 高**、上下左右都居中的菱形。
//   ★ 触碰判定、每行采样密度全部由这两个常量推出来 ⇒ 改这两个数就够了。
var GS_EM_DIA_R       = 2.50;      // ★ 菱形半宽（格）= (6.5 − 1.5) / 2
var GS_EM_DIA_H       = 0.80;      // ★ 菱形半高（格）= (2.3 − 0.7) / 2
var GS_EM_TOUCH_R     = 1.30;      // 触碰判定半径（格）

// ---- ★★ 手持增益（拿在主手就生效，脱手立刻消失）----
//   用户：「给阎魔刀改加上手持可飞行，抗性4，生命恢复3，速度2，夜视
//          （都是30秒，27秒续，不手持就立刻消失）」
//   · 五种效果：**可飞行** + 抗性提升 IV + 生命恢复 III + 速度 II + 夜视
// ★★ **抗击退已整体删除**
//        （用户：停止思考抗击退，删除抗击退功能 ⇒ 普通 + 爆炸两个属性都不再挂）
//   · 一次给 **30 秒**，剩余不足 **27 秒**才续期 ⇒ 图标不会一闪一闪
//     （与「梦境套」的 GS_SET_EFF_TICKS / GS_SET_EFF_REF 是同一套思路，
//       药效类型也复用那边的 gsSetPotionType ⇒ 抗性改名那类兼容只写一份）
//   · **脱手立刻收走**：脉冲每 2 刻（0.1 秒）跑一次 ⇒ 最多 0.1 秒就没了
//   ★ 只收「本机制给的那一份」：玩家自己喝的同类药水一律不碰
//     （GS_EM_BUFF_EFF 记药效清单、GS_EM_BUFF_NV 记夜视）
//   ★ 只关「我们开启过的」飞行：GS_EM_BUFF_FLY 记接管状态，
//     避免误关喷气背包 / 其它插件给的飞行；创造 / 旁观模式不动他们。
var GS_EM_BUFF_TICKS  = 600;       // 药效一次给 30 秒
var GS_EM_BUFF_REF    = 540;       // 剩余低于 27 秒才续期（不闪图标）

var GS_EM_REGISTERED = false;
var GS_EM_ST          = {};        // uuid -> { n, until }（n = 刚打完第几刀，0 = 未开始）
var GS_EM_BARS        = {};        // uuid -> { b, p }（★ 本模块专属表）
var GS_EM_SWING_AT    = {};        // uuid -> 上次出刀毫秒（0.8 秒冷却）
var GS_EM_CLICK_AT    = {};        // uuid|L/R -> 上次处理毫秒（去抖）
// ★★ 第六十四批：`GS_EM_DS_AT`（次元斩的冷却表）已随冷却一起**删除**
var GS_EM_CRACK_AT    = {};        // ★ 第四十八批：uuid -> 上次撕裂缝毫秒（5 秒冷却）
var GS_EM_ANCHOR      = {};        // uuid -> { w, x, y, z, yaw, pitch }（F 记录的锚点）
// ★ 手持增益的三张登记表（含义见上方 GS_EM_BUFF_TICKS 的注释）
//   ★★ 第五十四批：原来的第四张 GS_EM_BUFF_KB（抗击退登记表）已随功能一起删除
var GS_EM_BUFF_FLY    = {};        // uuid -> true（这份飞行是本机制开的）
var GS_EM_BUFF_EFF    = {};        // uuid -> [{t, amp}]（本机制给过的药效）
var GS_EM_BUFF_NV     = {};        // uuid -> true（这份夜视是本机制给的）
var GS_EM_FX          = [];        // 正在淡出的斩击特效
var GS_EM_FIELDS      = [];        // 活跃的次元斩领域
var GS_EM_CRACKS      = [];        // 活跃的裂缝
var GS_EM_GEN         = -1;        // 加载那一刻的世界世代号（判 /rsc reload 旧实例）

var GS_EM_K_GEN = null, GS_EM_PDT = null;
try {
    var EM_NK = Java.type('org.bukkit.NamespacedKey');
    // ★★★ 必须 fromString —— new NamespacedKey(plugin, name) 在 GraalJS 下重载歧义、会静默失败
    GS_EM_K_GEN = EM_NK.fromString('gs:em_gen');
    GS_EM_PDT = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

// 颜色（懒加载 + 缓存）：紫色为主、蓝色作点缀
var GS_EM_C_PUR = null, GS_EM_C_BLU = null;
function gsEmPur() {
    if (GS_EM_C_PUR == null) GS_EM_C_PUR = gsMsDustOpts(150, 45, 255, 1.4);
    return GS_EM_C_PUR;
}
function gsEmBlu() {
    if (GS_EM_C_BLU == null) GS_EM_C_BLU = gsMsDustOpts(55, 105, 255, 1.4);
    return GS_EM_C_BLU;
}
// 每 3 个点掺 1 个蓝色，其余紫色 ⇒ 整体"紫中带蓝"
// ★ 这一套**只给六道斩击 / 裂缝**用，之后**次元斩不用它了**。
function gsEmDot(world, x, y, z, i) {
    var d = (((i | 0) % 3) === 1) ? gsEmBlu() : gsEmPur();
    gsMsDust(world, gsMsDustType(), d, x, y, z);
}

// ★★★ 次元斩的**大圆环**用「红紫色」（用户「改成红紫色大圆环」）
//   两个色号都落在红紫区间 —— 一个偏红、一个偏紫，交替出现 ⇒ 整体就是红紫色。
//   ★ 单独一套，**不动**上面的 gsEmPur / gsEmBlu（那套是给六道斩击用的，保持原样）。
var GS_EM_C_DSR = null, GS_EM_C_DSP = null;
function gsEmDsRed() {
    if (GS_EM_C_DSR == null) GS_EM_C_DSR = gsMsDustOpts(240, 40, 90, 1.5);
    return GS_EM_C_DSR;
}
function gsEmDsPur() {
    if (GS_EM_C_DSP == null) GS_EM_C_DSP = gsMsDustOpts(170, 35, 205, 1.5);
    return GS_EM_C_DSP;
}
// 环上每 4 个点里 2 个偏红、2 个偏紫；`phase` 让这层色带**缓慢绕着环转**。
//   ★ 为什么用色带而不是把环转起来：**正圆自转是看不出来的**（旋转对称），
//     让红/紫两色沿圆周推进才有"活着"的感觉，而形状始终是一个干净的圆环。
function gsEmDsDot(world, x, y, z, i, phase) {
    var k = ((i | 0) + (phase | 0)) % 4;
    if (k < 0) k += 4;
    gsMsDust(world, gsMsDustType(), (k < 2) ? gsEmDsRed() : gsEmDsPur(), x, y, z);
}

function gsEmIs(item) { return gsIsItem(item, GS_EM_ID); }
function gsEmHand(p) {
    try { return p.getInventory().getItemInMainHand(); } catch (e) { return null; }
}

// ---------- 居合斩：计数器（内存权威）----------
function gsEmGet(p) {
    var o = { n: 0, until: 0 };
    try {
        if (p == null) return o;
        var m = GS_EM_ST['' + p.getUniqueId()];
        if (m != null) { o.n = m.n | 0; o.until = 0 + m.until; }
    } catch (e) { }
    return o;
}
function gsEmSet(p, n, until) {
    try {
        if (p == null) return;
        var u = '' + p.getUniqueId();
        var nn = n | 0;
        if (nn <= 0) { try { delete GS_EM_ST[u]; } catch (e) { } }
        else GS_EM_ST[u] = { n: nn, until: 0 + until };
    } catch (e) { }
}

// 下一刀该出第几刀：刚打完 n ⇒ 下一刀 = (n % 6) + 1
//   ★ 打完第 6 刀（n = 6）⇒ (6 % 6) + 1 = **1** ⇒ 自动回到第 1 刀
function gsEmNext(n) {
    var k = n | 0;
    if (k <= 0) return 1;
    return (k % GS_EM_SLASH_N) + 1;
}

// ---------- 居合斩：BossBar ----------
// ★ 标题一律喂**纯字符串**（§ 颜色码），绝不把 Adventure Component 塞进 setTitle
function gsEmTitle(n) {
    var k = n | 0;
    if (k <= 0) k = 1;
    return GS_MS_C.PURPLE + '居合 ' + GS_MS_C.LIGHT_PURPLE + k
        + GS_MS_C.PURPLE + '/' + GS_MS_C.LIGHT_PURPLE + GS_EM_SLASH_N;
}
function gsEmBarColor() {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        try { var c = C.valueOf(GS_EM_BAR_COL); if (c != null) return c; } catch (e) { }
        try { var c2 = C[GS_EM_BAR_COL]; if (c2 != null) return c2; } catch (e) { }
    } catch (e) { }
    return null;
}
function gsEmBarOf(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_EM_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsEmBarColor(), s = gsMsBarStyle();
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(gsEmTitle(1), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_EM_BARS[u] = { b: bar, p: p };
        return bar;
    } catch (e) { return null; }
}
function gsEmBarShow(p, n, left, total) {
    try {
        var b = gsEmBarOf(p);
        if (b == null) return;
        var prog = (total > 0) ? (left / total) : 0;
        if (prog < 0) prog = 0;
        if (prog > 1) prog = 1;
        gsBarTitleSet(b, gsEmTitle(n));
        try { b.setProgress(prog); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
    } catch (e) { }
}
function gsEmBarHide(p) { gsBarTblHide(GS_EM_BARS, p); }
function gsEmBarDrop(p) { gsBarTblDrop(GS_EM_BARS, p); }
function gsEmClear(p) {
    try { gsEmSet(p, 0, 0); } catch (e) { }
    try { gsEmBarHide(p); } catch (e) { }
}

// 每 2 刻维护：血条倒计时；3 秒没出刀 ⇒ **清空**（下次从第 1 刀起）
function gsEmPlayerTick(p) {
    try {
        var st = gsEmGet(p);
        if (st.n <= 0) { gsEmBarHide(p); return; }
        var now = gsMsNow();
        if (st.until <= 0) {                       // 自愈：有层数没计时 ⇒ 补一份
            gsEmSet(p, st.n, now + GS_EM_RESET_MS);
            gsEmBarShow(p, st.n, GS_EM_RESET_MS, GS_EM_RESET_MS);
            return;
        }
        var left = st.until - now;
        if (left <= 0) {
            gsEmClear(p);                          // ★ 3 秒没出刀 ⇒ 清空，回到第 1 刀
            gsMsSoundAt(p, 'block.beacon.deactivate', 0.5, 1.4);
            return;
        }
        gsEmBarShow(p, st.n, left, GS_EM_RESET_MS);
    } catch (e) { }
}

// ---------- ★★ 手持增益（可飞行 / 抗性4 / 生命恢复3 / 速度2 / 夜视）----------
//   拿在主手 ⇒ 一次给 30 秒、剩余不足 27 秒续期；脱手 ⇒ 立刻全部收走。
//   ★ 只收本机制给的那一份，玩家自己喝的药水不动（做法与「梦境套」一致）。
function gsEmBuffEffects(p, uuid, on) {
    try {
        // ================= 收走 =================
        if (!on) {
            // ① 夜视（单独一张表：玩家自己喝的夜视一律不碰）
            if (GS_EM_BUFF_NV[uuid] === true) {
                GS_EM_BUFF_NV[uuid] = false;
                var nvT = gsSetPotionType('NIGHT_VISION', null);
                if (nvT != null) { try { p.removePotionEffect(nvT); } catch (e) { } }
            }
            // ② 其余三种（等级对得上，才认定是「我们给的那一份」）
            var arr = GS_EM_BUFF_EFF[uuid];
            GS_EM_BUFF_EFF[uuid] = null;
            if (arr != null) {
                for (var i = 0; i < arr.length; i++) {
                    try {
                        var t = arr[i].t;
                        var cur = p.getPotionEffect(t);
                        if (cur == null) continue;
                        if (cur.getAmplifier() !== arr[i].amp) continue;
                        p.removePotionEffect(t);
                    } catch (e) { }
                }
            }
            return;
        }

        // ================= 给 =================
        var PE = null;
        try { PE = Java.type('org.bukkit.potion.PotionEffect'); } catch (e) { PE = null; }
        if (PE == null) return;

        var effs = [
            { name: 'RESISTANCE',   alt: 'DAMAGE_RESISTANCE', amp: 3 },   // 抗性提升 IV
            { name: 'REGENERATION', alt: null,                amp: 2 },   // 生命恢复 III
            { name: 'SPEED',        alt: null,                amp: 1 }    // 速度 II
        ];

        var applied = [];
        for (var j = 0; j < effs.length; j++) {
            var ef = effs[j];
            var et = gsSetPotionType(ef.name, ef.alt);
            if (et == null) continue;

            // ★ 先登记（不论本次是否真的补发），脱手时才知道该清哪些
            applied.push({ t: et, amp: ef.amp });

            // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
            var need = true;
            var curAmp = -1;
            try {
                var c = p.getPotionEffect(et);
                if (c != null) {
                    curAmp = c.getAmplifier();
                    if (curAmp === ef.amp && c.getDuration() > GS_EM_BUFF_REF) need = false;
                }
            } catch (e) { }

            // ★★ 比我们**更强**的同种药效（玩家自己喝的 / 信标给的）⇒ **不覆盖**。
            //   理由：拿刀反而把身上的速度 V 压成速度 II，是纯粹的削弱。
            //   ★ 这时登记表里记的是 ef.amp，与身上的 amp 对不上 ⇒
            //     脱手时那条 `getAmplifier() !== amp` 会自然跳过，玩家自己那份**原样保留**。
            //   （「梦境套」的 gsSetEffects 没有这一层保护 —— 它是护甲常驻效果，
            //     不覆盖反而会出现"穿着套却没效果"的困惑；武器是临时增益，按更强的来更合理。）
            if (curAmp > ef.amp) continue;

            if (!need) continue;

            // ambient = true（金色边）、particles = false（不冒粒子）
            try { p.addPotionEffect(new PE(et, GS_EM_BUFF_TICKS, ef.amp, true, false), true); }
            catch (e) { try { p.addPotionEffect(new PE(et, GS_EM_BUFF_TICKS, ef.amp, true, false)); } catch (e2) { } }
        }
        GS_EM_BUFF_EFF[uuid] = applied;

        // ③ 夜视：玩家当前**已有夜视**（自己喝的）且不是本机制给的 → 完全不碰
        var nvT2 = gsSetPotionType('NIGHT_VISION', null);
        if (nvT2 != null) {
            var owned = (GS_EM_BUFF_NV[uuid] === true);
            var nvCur = null;
            try { nvCur = p.getPotionEffect(nvT2); } catch (e) { nvCur = null; }

            if (nvCur != null && !owned) {
                // 别人给的 → 不触发、不接管、不登记
            } else {
                GS_EM_BUFF_NV[uuid] = true;
                var left = 0;
                try { left = nvCur.getDuration(); } catch (e) { left = 0; }
                if (nvCur == null || left <= GS_EM_BUFF_REF) {
                    try { p.addPotionEffect(new PE(nvT2, GS_EM_BUFF_TICKS, 0, true, false), true); }
                    catch (e) { try { p.addPotionEffect(new PE(nvT2, GS_EM_BUFF_TICKS, 0, true, false)); } catch (e2) { } }
                }
            }
        }
    } catch (e) { }
}

// 创造 / 旁观模式不动他们的飞行（他们本来就该能飞）
function gsEmIsCreative(p) {
    try {
        var gm = '' + p.getGameMode().name();
        return (gm === 'CREATIVE' || gm === 'SPECTATOR');
    } catch (e) { return false; }
}

// 脱手 / 退服 / 重载时的统一回收（不看手持状态，直接收）
function gsEmBuffClear(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        if (GS_EM_BUFF_FLY[uuid]) {
            if (!gsEmIsCreative(p)) {
                // 正在飞就先把飞行状态落下来，再收回权限（免得悬在半空）
                try { if (p.isFlying()) p.setFlying(false); } catch (e) { }
                try { p.setAllowFlight(false); } catch (e) { }
            }
            GS_EM_BUFF_FLY[uuid] = false;
        }
        gsEmBuffEffects(p, uuid, false);
    } catch (e) { }
}

// 每 2 刻（0.1 秒）由 gsEmPulse 对每个在线玩家结算一次
function gsEmBuffTick(p) {
    try {
        if (p == null) return;
        var uuid = '' + p.getUniqueId();
        var hold = false;
        try { hold = gsEmIs(gsEmHand(p)); } catch (e) { hold = false; }

        if (hold) {
            // ① 可飞行（只记「我们开的」，免得误关别人的）
            if (gsEmIsCreative(p)) {
                GS_EM_BUFF_FLY[uuid] = false;
            } else {
                var allow = false;
                try { allow = p.getAllowFlight(); } catch (e) { allow = false; }
                if (!allow) { try { p.setAllowFlight(true); } catch (e) { } }
                GS_EM_BUFF_FLY[uuid] = true;
            }
            // ② 药效 + 夜视
            gsEmBuffEffects(p, uuid, true);
        } else {
            // ★ 快速通道：本来就没有「我们的东西」⇒ 连 gamemode / isFlying 都不用查。
            //   这是绝大多数玩家的常态（手里没拿刀）⇒ 每 0.1 秒的固定开销降到最低。
            if (GS_EM_BUFF_FLY[uuid] !== true
                && GS_EM_BUFF_EFF[uuid] == null
                && GS_EM_BUFF_NV[uuid] !== true) return;

            // 脱手 ⇒ 立刻全部收走
            if (GS_EM_BUFF_FLY[uuid] === true) {
                if (!gsEmIsCreative(p)) {
                    try { if (p.isFlying()) p.setFlying(false); } catch (e) { }
                    try { p.setAllowFlight(false); } catch (e) { }
                }
                GS_EM_BUFF_FLY[uuid] = false;
            }
            gsEmBuffEffects(p, uuid, false);
        }
    } catch (e) { }
}

// ---------- 目标筛选 / 伤害器 ----------
function gsEmCanHit(ent, selfId) {
    try {
        if (ent == null) return false;
        var id = '';
        try { id = '' + ent.getUniqueId(); } catch (e) { return false; }
        if (id === selfId) return false;
        var tn = '';
        try { tn = '' + ent.getType().name(); } catch (e) { return false; }
        if (tn === 'PLAYER' && !GS_EM_HIT_PLAYERS) return false;
 // ★ 沿用的「只认活物」：掉落物 / 经验球 / 展示实体 / 盔甲架 都不算命中
        if (!gsIsCombatTarget(ent, tn)) return false;
        try { ent.getHealth(); } catch (e) { return false; }
        try { if (ent.isDead()) return false; } catch (e) { }
        return true;
    } catch (e) { return false; }
}
function gsEmHurt(ent, dmg, p) { gsMsHurt(ent, dmg, p); }

// ---------- 玩家朝向基向量（★★ 改成**完整三维**，跟着准星）----------
// 返回 { fx,fy,fz, rx,ry,rz, ux,uy,uz }：
//   f = **准星朝向**（含俯仰，已归一化）—— 用户要求「左键右键的所有释放都要对着玩家准星朝向」
//   r = 水平右向（永远水平，不随俯仰倾斜）
//   u = 垂直于 f 与 r 的"上方"（= r × f）⇒ 抬头时它会向后仰
//   ★ 与「致万千无言者 / 万籁俱寂」同一套左右口径：面朝 +Z 时右向 = -X ⇒ **左侧是 +X**
//   ★ 局部坐标映射统一走 gsEmP()：世界点 = 原点 + f*pf + r*pl + u*ph
//     （pf 前 / pl 左右，负 = 左 / ph 垂直抬升）
function gsEmBasis(loc) {
    var o = { fx: 0.0, fy: 0.0, fz: 1.0, rx: -1.0, ry: 0.0, rz: 0.0,
              ux: 0.0, uy: 1.0, uz: 0.0 };
    try {
        // ① 前向 = 视线方向（三维）
        var dx = 0.0, dy = 0.0, dz = 1.0;
        try {
            var d = loc.getDirection();
            dx = 0 + d.getX(); dy = 0 + d.getY(); dz = 0 + d.getZ();
        } catch (e) { }
        var L = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(L > 1.0e-6)) { dx = 0.0; dy = 0.0; dz = 1.0; L = 1.0; }
        dx /= L; dy /= L; dz /= L;
        o.fx = dx; o.fy = dy; o.fz = dz;

        // ② 水平右向：优先取前向的**水平分量**；正上/正下看时它退化成 0 ⇒ 退回用 yaw
        var hx = dx, hz = dz;
        var hl = Math.sqrt(hx * hx + hz * hz);
        if (!(hl > 1.0e-4)) {
            var yaw = 0.0;
            try { yaw = 0 + loc.getYaw(); } catch (e) { yaw = 0.0; }
            var rad = yaw * Math.PI / 180.0;
            hx = -Math.sin(rad); hz = Math.cos(rad);     // 原版口径：yaw 0 = 朝 +Z
            hl = Math.sqrt(hx * hx + hz * hz);
            if (!(hl > 1.0e-6)) { hx = 0.0; hz = 1.0; hl = 1.0; }
        }
        hx /= hl; hz /= hl;
        // r = f × (0,1,0) 的水平化结果 ⇒ 面朝 +Z 时 = (-1,0,0) = 右手边
        o.rx = -hz; o.ry = 0.0; o.rz = hx;

        // ③ u = r × f（右手系，抬头时向后仰）
        o.ux = o.ry * o.fz - o.rz * o.fy;
        o.uy = o.rz * o.fx - o.rx * o.fz;
        o.uz = o.rx * o.fy - o.ry * o.fx;
    } catch (e) { }
    return o;
}

// 局部坐标 (pf 前 / pl 左右 / ph 垂直) ⇒ 世界点
function gsEmP(b, ox, oy, oz, pf, pl, ph) {
    return gsMsLoc(
        b.w,
        ox + b.fx * pf + b.rx * pl + b.ux * ph,
        oy + b.fy * pf + b.ry * pl + b.uy * ph,
        oz + b.fz * pf + b.rz * pl + b.uz * ph);
}

// ==========================================================================
// ---------- 左键 · 居合斩：六刀形状（局部坐标 pf 前 / pl 左右 / ph 垂直）----------
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §23
// ==========================================================================

// 「画一个圆圈、把后面切掉」⇒ 朝前的半圆（真圆弧采样，不是贝塞尔近似）
//   o = 圆心 [前, 左右, 垂直]；r = 半径；tilt = 上轴倾斜角（度）；
//   a0 → a1 = 扫过的角度（度，0 = 上轴端、90 = 正前、180 = 另一端）
function gsEmCircle(cf, cl, ch, r, tiltDeg, a0Deg, a1Deg) {
    return { cir: 1, o: [cf, cl, ch], r: r,
             tilt: tiltDeg, a0: a0Deg, a1: a1Deg };
}

// 画上面那种"半圆"（真圆弧，按角度均匀采样 ⇒ 是圆，不是抛物线）
function gsEmCircDraw(world, b, sh, n, fade) {
    try {
        var step = (fade < 0.45) ? 2 : 1;
        var tl = sh.tilt * Math.PI / 180.0;
        var ct = Math.cos(tl), st = Math.sin(tl);
        var a0 = sh.a0 * Math.PI / 180.0, a1 = sh.a1 * Math.PI / 180.0;
        var RN = sh.r;                       // 沿"上轴"的半径（整圆尺寸）
        var RF = sh.r * GS_EM_ARCH;          // 沿"前方"的半径（扁一点）
        for (var i = 0; i <= n; i += step) {
            var t = i / n;
            var th = a0 + (a1 - a0) * t;
            var cn = Math.cos(th) * RN;      // 上轴方向的分量
            var cfw = Math.sin(th) * RF;     // 正前方方向的分量
            var pt = gsEmP(b, b.ox, b.oy, b.oz,
                           sh.o[0] + cfw,
                           sh.o[1] + cn * st,
                           sh.o[2] + cn * ct);
            if (pt != null) gsEmDot(world, pt.getX(), pt.getY(), pt.getZ(), i);
        }
    } catch (e) { }
}

// 弧顶**精确**落在 P 的二次贝塞尔弧（由 P 反推控制点）—— 第 5 刀"顶点相撞"要用
function gsEmArcTo(a, b, P) {
    return { a: a, b: b,
             c: [2.0 * P[0] - (a[0] + b[0]) * 0.5,
                 2.0 * P[1] - (a[1] + b[1]) * 0.5,
                 2.0 * P[2] - (a[2] + b[2]) * 0.5] };
}

// ★ 第 5 刀三条弧的**共同顶点**（三条弧的弧顶都落在这里 ⇒ "顶点相撞"）
//   ★★ 放在**准星正前方**（用户：顶点应该朝前方）
var GS_EM_S5_PF = 4.60, GS_EM_S5_PL = 0.00, GS_EM_S5_PH = 0.00;

function gsEmShape(idx) {
    if (idx === 0) {
        // 第 1 刀 上撩：**竖着的圆圈切掉后面** ⇒ 朝前的半圆，**从下到上**
        //   圆心 (2.2, 0, 0)、半径 2.6 ⇒ 从 (2.2, 0, -2.6) 经正前方到 (2.2, 0, +2.6)
        return [gsEmCircle(2.2, 0.0, 0.0, 2.6, 0.0, 180.0, 0.0)];
    }
    if (idx === 1) {
 // 第 2 刀 **横斩**（★ 用户「居合第二刀改成横斩」）
        //   同样是**横着的圆圈**切掉后面，但刻意与第 4 刀区分开：
        //     · 圆心更靠前（pf 3.6 vs 2.6）、半径更小（2.4 vs 3.0）
        //     · **扫向相反**：0°→180° ⇒ 从**右**扫到**左**（第 4 刀是左 → 右）
        return [gsEmCircle(3.6, 0.0, 0.0, 2.4, 90.0, 0.0, 180.0)];
    }
    if (idx === 2) {
        // 第 3 刀 左上 → 右下：**斜着的圆圈**（上轴往左偏 45°）切掉后面
        //   tilt = -45° ⇒ 一端在**左上**、经正前方、另一端在**右下**
        return [gsEmCircle(2.2, 0.0, 0.0, 2.8, -45.0, 0.0, 180.0)];
    }
    if (idx === 3) {
        // 第 4 刀 横斩：**横着的圆圈**（上轴 = 左右方向）切掉后面
        //   ⇒ 两端**同高**、向前鼓 —— ★ 用户说这一刀本来就对，走势保持不变
        //   ★ 与第 2 刀的区别：圆心更靠前（2.6）、半径更大（3.0）、**左 → 右**
        return [gsEmCircle(2.6, 0.0, 0.0, 3.0, 90.0, 180.0, 0.0)];
    }
    if (idx === 4) {
        // 第 5 刀 左斜 + 右斜 + 竖斩 三条弧**同时出现**，
        //   ★★ 三条弧的**顶点全部相撞**在 (GS_EM_S5_PF, GS_EM_S5_PL, GS_EM_S5_PH)
        //      —— 这个点就在**准星正前方**，所以三条弧的顶点都是"朝前"的。
        //   弧顶 = (a+b)/4 + c/2 ⇒ 要让弧顶落在 P，控制点取 c = 2P - (a+b)/2
        //   （就是 gsEmArcTo 干的事）。
        //   ★ 三条弧的**两端都关于共同顶点对称**（中点都落在 (2.2, 0, 0) 上），
        //     所以是三条形状干净、方向各异的弧"撞"在同一个点上。
        var P = [GS_EM_S5_PF, GS_EM_S5_PL, GS_EM_S5_PH];
        return [
            gsEmArcTo([2.2, -2.4,  1.4], [2.2, 2.4, -1.4], P),   // 左斜：左上 → 右下
            gsEmArcTo([2.2,  2.4,  1.4], [2.2, -2.4, -1.4], P),  // 右斜：右上 → 左下
            gsEmArcTo([3.0,  0.0, -1.6], [3.0, 0.0,  1.6], P)    // 竖斩：下 → 上
        ];
    }
    return [];                             // 第 6 刀单独处理（见 gsEmDrawSlash6）
}

// 第 6 刀：以**玩家为中心**、半径 4.5 格的**圆圈**，整体**左高右低**
//   ★★ 这里有个坑，记一下：**在「垂直于视线」的平面里旋转圆，等于没转** ——
//      圆在自己的平面内怎么转都是同一个圆，所以"左高右低"根本不会出现。
//      要真的倾斜，必须让圆面**转出**那个平面。
//   ★ 做法：圆面由这两个基张成 ——
//        e1 = 水平右向 r 抬高 TILT 角（= r·cos + u·sin）
//        e2 = **准星朝向 f**
//      ⇒ 圆面包含准星方向、绕它倾斜 ⇒ 看上去就是一个"以你为中心、左高右低的圈"。
//      TILT 取负值 ⇒ +r（右手边）压低、-r（左手边）抬高 ⇒ **左高右低**。
//   ★ 圆上任意点到中心的距离恒为 GS_EM_S6_R（e1、e2 是正交单位向量）。
function gsEmDrawSlash6(world, b, fade) {
    try {
        var N = GS_EM_S6_N;
        var step = (fade < 0.45) ? 2 : 1;              // 淡出后期隔点画，视觉上变稀
        var ca = Math.cos(GS_EM_S6_TILT), sa = Math.sin(GS_EM_S6_TILT);
        // e1 = r·cos + u·sin（横向抬起来）；e2 = f（准星方向）
        var e1x = b.rx * ca + b.ux * sa, e1y = b.ry * ca + b.uy * sa, e1z = b.rz * ca + b.uz * sa;
        for (var i = 0; i < N; i += step) {
            var th = (i / N) * 2.0 * Math.PI;
            var c1 = Math.cos(th) * GS_EM_S6_R;
            var c2 = Math.sin(th) * GS_EM_S6_R;
            gsEmDot(world,
                b.ox + e1x * c1 + b.fx * c2,
                b.oy + e1y * c1 + b.fy * c2,
                b.oz + e1z * c1 + b.fz * c2, i);
        }
    } catch (e) { }
}

// 把一条**弧**（二次贝塞尔）画到世界里（n 段插值）
function gsEmArcDraw(world, b, arc, n, fade) {
    try {
        var step = (fade < 0.45) ? 2 : 1;
        var a = arc.a, c = arc.c, e = arc.b;
        for (var i = 0; i <= n; i += step) {
            var t = i / n, it = 1.0 - t;
            var w0 = it * it, w1 = 2.0 * t * it, w2 = t * t;
            var pf = w0 * a[0] + w1 * c[0] + w2 * e[0];
            var pl = w0 * a[1] + w1 * c[1] + w2 * e[1];
            var ph = w0 * a[2] + w1 * c[2] + w2 * e[2];
            var pt = gsEmP(b, b.ox, b.oy, b.oz, pf, pl, ph);
            if (pt != null) gsEmDot(world, pt.getX(), pt.getY(), pt.getZ(), i);
        }
    } catch (e) { }
}

// 画一个形状条目：**半圆**（真圆弧采样）走 gsEmCircDraw，
// **二次贝塞尔弧**（第 5 刀"顶点相撞"用）走 gsEmArcDraw
function gsEmShapeDraw(world, b, sh, n, fade) {
    if (sh == null) return;
    if (sh.cir === 1) gsEmCircDraw(world, b, sh, n, fade);
    else gsEmArcDraw(world, b, sh, n, fade);
}

// 按 fade（1 = 刚出刀，0 = 完全消失）把第 idx 刀画一遍
function gsEmDrawSlash(p, world, b, idx, fade) {
    try {
        if (idx === 5) { gsEmDrawSlash6(world, b, fade); return; }
        var arcs = gsEmShape(idx);
        for (var s = 0; s < arcs.length; s++) {
            gsEmShapeDraw(world, b, arcs[s], 18, fade);
        }
    } catch (e) { }
}

// ---------- 左键 · 居合斩：出刀 ----------
function gsEmSwing(p) {
    try {
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_EM_SWING_AT[u];
        if (last != null && (now - last) < GS_EM_CD) return;      // 0.2 秒冷却（第四十九批）
        GS_EM_SWING_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

 // ★★ **特效与伤害全部以「眼睛」为原点、沿准星朝向展开**
        //   （用户：左键右键的所有释放都要对着玩家准星朝向）
        //   取不到眼睛位置就退回脚底 + 1.62（原版眼高）
        var ox = 0.0, oy = 0.0, oz = 0.0, aimLoc = null, gotEye = false;
        try {
            var eye = p.getEyeLocation();
            if (eye != null) { aimLoc = eye; ox = eye.getX(); oy = eye.getY(); oz = eye.getZ(); gotEye = true; }
        } catch (e) { gotEye = false; }
        if (!gotEye) { aimLoc = loc; ox = loc.getX(); oy = loc.getY() + 1.62; oz = loc.getZ(); }
        var bs = gsEmBasis(aimLoc);
        // 画特效用的"坐标系"：原点 + 三个基向量
        //   ★ 用 var = function 而不是函数声明（函数声明写在 try 块里语义不统一）
        var mkFrame = function () {
            return { w: world, ox: ox, oy: oy, oz: oz,
                     fx: bs.fx, fy: bs.fy, fz: bs.fz,
                     rx: bs.rx, ry: bs.ry, rz: bs.rz,
                     ux: bs.ux, uy: bs.uy, uz: bs.uz };
        };

        // ① 这一刀是第几刀（★ 不看有没有命中，出刀就往前走）
        var st = gsEmGet(p);
        var idx = gsEmNext(st.n) - 1;                 // 0..5
        var n = idx + 1;                              // 展示用 1..6

        // ② 特效（挂进淡出队列，由心跳逐帧重画到 0.8 秒）
        //   ★ 存的是**出刀那一刻的整套坐标系**（含准星朝向）⇒ 淡出期间形状不会歪
        try {
            GS_EM_FX.push({ p: p, w: world, idx: idx, t0: now, fr: mkFrame() });
        } catch (e) { }
        gsEmDrawSlash(p, world, mkFrame(), idx, 1.0);
        gsMsSound(world, loc, 'entity.player.attack.sweep', 0.9, 1.15);
        if (idx === 5) gsMsSound(world, loc, 'entity.evoker.cast_spell', 0.9, 1.3);

        // ③ 伤害：以眼睛为原点、沿准星朝向的 **宽 5 × 长 9 × 高 3** 长方体
        //   ★ 把实体偏移**投影到 (f, r, u) 三个轴上** ⇒ 抬头就往高处打、低头就往低处打
        var selfId = u;
        var hit = {};
        var halfW = GS_EM_WIDE * 0.5;
        var halfH = GS_EM_H * 0.5 + 0.6;              // ★ 多给 0.6 格容差（脚在准星下方）
        var cx0 = ox + bs.fx * (GS_EM_RANGE * 0.5);
        var cy0 = oy + bs.fy * (GS_EM_RANGE * 0.5);
        var cz0 = oz + bs.fz * (GS_EM_RANGE * 0.5);
        var cbox = gsMsLoc(world, cx0, cy0, cz0);
        if (cbox != null) {
            var list = null;
            try { list = world.getNearbyEntities(cbox, GS_EM_RANGE * 0.5 + 0.8, GS_EM_H * 0.5 + 1.6, GS_EM_RANGE * 0.5 + 0.8); } catch (e) { list = null; }
            if (list != null) {
                var it = list.iterator();
                while (it.hasNext()) {
                    var ent = it.next();
                    if (!gsEmCanHit(ent, selfId)) continue;
                    var eid = '';
                    try { eid = '' + ent.getUniqueId(); } catch (e) { continue; }
                    if (hit[eid] != null) continue;
                    var el = null;
                    try { el = ent.getLocation(); } catch (e) { continue; }
                    // 实体"胸口"相对眼睛的偏移
                    var dx = el.getX() - ox, dy = (el.getY() + 0.9) - oy, dz = el.getZ() - oz;
                    var pf = dx * bs.fx + dy * bs.fy + dz * bs.fz;   // 沿准星方向
                    var pl = dx * bs.rx + dy * bs.ry + dz * bs.rz;   // 左右
                    var ph = dx * bs.ux + dy * bs.uy + dz * bs.uz;   // 垂直准星
                    if (pf < -0.4 || pf > GS_EM_RANGE) continue;     // 身后 0.4 容差
                    if (pl < -halfW || pl > halfW) continue;
                    if (ph < -halfH || ph > halfH) continue;
                    hit[eid] = 1;
                    gsEmHurt(ent, GS_EM_DMG, p);
                }
            }
        }

        // ④ ★★ 第 6 刀专属：**圆圈范围内**的**额外**伤害（用户：算是额外的伤害，13点）
        //   圆面 = 由 e1（横向抬高 TILT）与 f（准星）张成，法线 n = e1 × f。
        //   判定：实体"胸口"到圆面的**垂距** <= GS_EM_S6_THICK
        //         且**面内距离** <= GS_EM_S6_R  ⇒ 命中，扣 GS_EM_S6_DMG 点。
        //   ★ 这是**额外**的：已经被上面长方体打过（7 点）的敌人**照样**再吃这 13 点，
        //     所以站在刀锋+圆圈里最多吃 7 + 13 = 20 点。
        //   ★ 圆圈是**以玩家为中心、360° 一圈**，身后也吃 —— 这正是圆圈的意义。
        if (idx === 5) {
            var ca6 = Math.cos(GS_EM_S6_TILT), sa6 = Math.sin(GS_EM_S6_TILT);
            var e1x = bs.rx * ca6 + bs.ux * sa6;
            var e1y = bs.ry * ca6 + bs.uy * sa6;
            var e1z = bs.rz * ca6 + bs.uz * sa6;
            var nx = e1y * bs.fz - e1z * bs.fy;          // n = e1 × f
            var ny = e1z * bs.fx - e1x * bs.fz;
            var nz = e1x * bs.fy - e1y * bs.fx;
            var nl = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (nl > 1.0e-6) {
                nx /= nl; ny /= nl; nz /= nl;
                var c6 = gsMsLoc(world, ox, oy, oz);
                var l6 = null;
                if (c6 != null) {
                    try { l6 = world.getNearbyEntities(c6, GS_EM_S6_R + 1.0, GS_EM_S6_R + 1.0, GS_EM_S6_R + 1.0); } catch (e) { l6 = null; }
                }
                if (l6 != null) {
                    var it6 = l6.iterator();
                    while (it6.hasNext()) {
                        var e6 = it6.next();
                        if (!gsEmCanHit(e6, selfId)) continue;
                        var el6 = null;
                        try { el6 = e6.getLocation(); } catch (e) { continue; }
                        var ddx = el6.getX() - ox;
                        var ddy = (el6.getY() + 0.9) - oy;
                        var ddz = el6.getZ() - oz;
                        var along = ddx * nx + ddy * ny + ddz * nz;      // 到圆面的垂距
                        if (Math.abs(along) > GS_EM_S6_THICK) continue;
                        var d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                        var inPlane2 = d2 - along * along;               // 面内距离的平方
                        if (inPlane2 > GS_EM_S6_R * GS_EM_S6_R) continue;
                        gsEmHurt(e6, GS_EM_S6_DMG, p);                   // ★ 额外伤害
                    }
                }
            }
        }

        // ⑤ 推进计数器：打完第 6 刀后 gsEmNext 自动回到 1
        gsEmSet(p, n, now + GS_EM_RESET_MS);
        gsEmBarShow(p, n, GS_EM_RESET_MS, GS_EM_RESET_MS);

 // ⑥ ★★★ **第 6 刀专属** —— 次元斩（整段从右键挪过来）
        //   用户：「右键的吸引改到左键的第六刀…只有第六刀才能砍出来这个技能」
        //   ⇒ 只有 idx === 5 才会走到这里；冷却已删除。
        //   ★ 放在计数器推进**之后** ⇒ 此刻 gsEmGet(p).n 已经是 6，
        //     与「第六刀」的说法一一对应（只是顺序，触发条件不变）。
        if (idx === 5) {
            try { gsEmDimension(p); } catch (e) { }
        }
    } catch (e) { }
}

// ---------- 左键第六刀 · 次元斩（★★ 从右键整段挪过来）----------
// ★★★ **红紫色大圆环**（用户「改成红紫色大圆环，不要圆柱了」）
//   · 旧版：4 条斜线（左斜 / 横斩 / 竖斩 / 右斜）× **前后两层**（`fo = ±0.85`）
//     ⇒ 沿准星方向看过去就是个**圆柱**。
//   · 新版：**一个正圆** —— 只用 `b.rx / b.ux`（横截面的左右 / 上下两个基向量），
//     **完全没有 `b.fx` 分量** ⇒ 所有点落在**同一张平面**上，圆柱彻底消失。
//   · 朝向仍然**跟着准星（含俯仰）**：环面永远垂直于视线 ⇒ 永远正对着你。
//   · 半径 = `GS_EM_DS_BIG`（3.0 格 ⇒ 直径 6 格），与伤害半径 `GS_EM_DS_R` 对齐。
//   · 颜色 = **红紫色**（`gsEmDsDot`：偏红 + 偏紫两色交替）。
//   · `rot` 推进环上的**色带相位**（正圆自转看不出来，色带转才看得见）。
// ★ 的"粒子做稀"继续保留：`fade` 低时隔点画（step = 2）。
function gsEmDrawBig(world, cx, cy, cz, b, rot, fade) {
    try {
        var R = GS_EM_DS_BIG;
        var N = GS_EM_DS_RING_N;
        var step = (fade < 0.4) ? 2 : 1;
        var phase = Math.floor((0 + rot) * 8.0);
        for (var i = 0; i < N; i += step) {
            var a = (i / N) * Math.PI * 2.0;
            var l = Math.cos(a) * R;       // 横截面内：左右
            var h = Math.sin(a) * R;       // 横截面内：上下
            gsEmDsDot(world,
                cx + b.rx * l + b.ux * h,
                cy + b.ry * l + b.uy * h,
                cz + b.rz * l + b.uz * h, i, phase);
        }
    } catch (e) { }
}

// ★★ **只在左键第六刀**里被调用（见 gsEmSwing 的 idx === 5 分支），
//   冷却已删除 ⇒ 这里不再做任何"上次什么时候放过"的判断。
function gsEmDimension(p) {
    try {
        var now = gsMsNow();

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }

        // ★★ 落点与朝向**完全跟着准星**：沿视线方向往前 4 格（抬头就往高处扔）
        var ox = 0.0, oy = 0.0, oz = 0.0, aimLoc = null, gotEye = false;
        try {
            var eye = p.getEyeLocation();
            if (eye != null) { aimLoc = eye; ox = eye.getX(); oy = eye.getY(); oz = eye.getZ(); gotEye = true; }
        } catch (e) { gotEye = false; }
        if (!gotEye) { aimLoc = loc; ox = loc.getX(); oy = loc.getY() + 1.62; oz = loc.getZ(); }
        var bs = gsEmBasis(aimLoc);

        var cx = ox + bs.fx * GS_EM_DS_DIST;
        var cy = oy + bs.fy * GS_EM_DS_DIST;
        var cz = oz + bs.fz * GS_EM_DS_DIST;

        var frame = { fx: bs.fx, fy: bs.fy, fz: bs.fz,
                      rx: bs.rx, ry: bs.ry, rz: bs.rz,
                      ux: bs.ux, uy: bs.uy, uz: bs.uz };
        GS_EM_FIELDS.push({ w: world, owner: p, x: cx, y: cy, z: cz,
                            bs: frame,
                            born: now, nextHit: now + GS_EM_DS_EVERY });

        gsEmDrawBig(world, cx, cy, cz, frame, 0.0, 1.0);
        gsMsSound(world, gsMsLoc(world, cx, cy, cz), 'entity.enderman.teleport', 1.0, 0.7);
        gsMsSoundAt(p, 'entity.evoker.cast_spell', 1.0, 0.8);
    } catch (e) { }
}

// 次元斩领域：每 0.1 秒吸一次，每 0.5 秒打一次（★ 只在第六刀出现 · 无冷却）
function gsEmFieldTick(f, now) {
    try {
        var world = f.w;
        if (world == null) return false;
        var el2 = gsMsLoc(world, f.x, f.y + 1.0, f.z);
        if (el2 == null) return false;

        var list = null;
        try { list = world.getNearbyEntities(el2, GS_EM_DS_R, GS_EM_DS_R, GS_EM_DS_R); } catch (e) { list = null; }
        var ownerId = '';
        try { ownerId = '' + f.owner.getUniqueId(); } catch (e) { ownerId = ''; }

        var doHit = (now >= f.nextHit);
        if (doHit) f.nextHit = now + GS_EM_DS_EVERY;

        if (list != null) {
            var it = list.iterator();
            while (it.hasNext()) {
                var ent = it.next();
                if (!gsEmCanHit(ent, ownerId)) continue;

                // ① 持续吸引：把敌人往中心拽（每 0.1 秒一次）
                //   ★ 强度按距离**递减**（远强近弱）⇒ 拽到中心附近就自然停下，
                //     不会因为"每次都按满速拽"而在中心来回抖动。
                try {
                    var el = ent.getLocation();
                    var vx = f.x - el.getX(), vz = f.z - el.getZ();
                    var d = Math.sqrt(vx * vx + vz * vz);
                    if (d > 0.20) {
                        var k = d / 1.5;
                        if (k > 1.0) k = 1.0;
                        vx = vx / d * GS_EM_DS_PULL * k;
                        vz = vz / d * GS_EM_DS_PULL * k;
                        var vy = 0.0;
                        try { vy = ent.getVelocity().getY(); } catch (e) { vy = 0.0; }
                        if (vy > 0.25) vy = 0.25;
                        if (vy < -0.25) vy = -0.25;
                        var V = Java.type('org.bukkit.util.Vector');
                        ent.setVelocity(new V(vx, vy, vz));
                    }
                } catch (e) { }

                // ② 伤害：每 0.5 秒 9 点
                if (doHit) gsEmHurt(ent, GS_EM_DS_DMG, f.owner);
            }
        }
        return true;
    } catch (e) { return true; }
}

// ---------- 右键 / Shift+右键 · 锚点与裂缝（★★ 从 F / Shift+F 挪来）----------
// ★★ **删掉聊天栏的「锚点已记录 / 裂缝将你送回锚点」提示词**
//      （用户：删除锚点和传送提示词），改成把**坐标写进阎魔刀自己的 lore**里
//      （用户：最好把位置记录写到阎魔刀的lore里）—— 玩家看刀就知道锚点在哪。
//   锚点仍然**存在脚本内存里、按玩家分**（lore 只是把同一份数据显示出来）。

// 去掉颜色码，只留纯文本（用来识别"哪一行是我们自己写的锚点行"）
function gsEmLorePlain(s) {
    var t = '' + s, o = '';
    for (var i = 0; i < t.length; i++) {
        var c = t.charAt(i);
        if (c === '\u00a7' || c === '&') { i++; continue; }   // 跳过颜色码本身
        o += c;
    }
    return o;
}

// ★ 把当前锚点写进**主手**那把阎魔刀的 lore（不是阎魔刀就什么都不做）
//   做法：拿现有 lore ⇒ 去掉旧的锚点行（按纯文本前缀"锚点"识别，
//   所以 items.yml 里那句「&6F 键&7锚点」不会被误删）⇒ 末尾追加新的锚点行。
//   ★ 保留其它所有行 ⇒ 不会覆盖 RSC 或其它脚本加的内容。
function gsEmLoreSync(p) {
    try {
        var item = null;
        try { item = p.getInventory().getItemInMainHand(); } catch (e) { return; }
        if (!gsEmIs(item)) return;                       // 手里不是阎魔刀 ⇒ 不管
        var meta = null;
        try { meta = item.getItemMeta(); } catch (e) { return; }
        if (meta == null) return;

        var a = GS_EM_ANCHOR['' + p.getUniqueId()];
        var line = (a == null)
            ? '\u00a78锚点 \u00a77\u00bb \u00a7c未记录'
            : ('\u00a78锚点 \u00a77\u00bb \u00a7d' + Math.round(a.x) + ', '
               + Math.round(a.y) + ', ' + Math.round(a.z) + ' \u00a78\u00b7 \u00a77' + a.w);

        var AL = Java.type('java.util.ArrayList');
        var out = new AL();
        var cur = null;
        try { cur = meta.getLore(); } catch (e) { cur = null; }
        if (cur != null) {
            var it = cur.iterator();
            while (it.hasNext()) {
                var ln = '' + it.next();
                if (gsEmLorePlain(ln).indexOf('锚点') === 0) continue;   // 旧的锚点行 ⇒ 丢掉
                out.add(ln);
            }
        }
        out.add(line);
        meta.setLore(out);
        item.setItemMeta(meta);
    } catch (e) { }
}

function gsEmAnchorSet(p) {
    try {
        var l = p.getLocation();
        GS_EM_ANCHOR['' + p.getUniqueId()] = {
            w: '' + p.getWorld().getName(),
            x: l.getX(), y: l.getY(), z: l.getZ(),
            yaw: l.getYaw(), pitch: l.getPitch()
        };
        gsEmLoreSync(p);                                 // ★ 坐标写进 lore（代替聊天提示）
        gsMsSoundAt(p, 'block.end_portal_frame.fill', 0.7, 1.4);
    } catch (e) { }
}

// 裂缝几何：以「玩家身前 2 格」为中心、朝向玩家的一面立墙
//   横向 = 玩家左右（长 7）｜厚度 = 视线方向（宽 1）｜竖直 = 高 3
//   ★ 局部横向坐标 l：**负 = 左**（与 gsEmBasis 的右向相反）
// ★★ 订正：用户说的「左 k」按 **长 7 的格号** 算 —— 左1 ~ 左7，
//        正中间是 **左4.0**；几何上「长 7」从 l = -3.5 跨到 +3.5，而 左4.0 = l 0。
//        ⇒ 换算就是 **l = k - 4.0**（左1.5 ⇒ l = -2.5、左6.5 ⇒ l = +2.5）。
//        （旧注释写成 `l = HALF - k` 且声称"左1 = 最左端"，是**写反了的**，已订正。）
function gsEmCrackPoint(c, l, h, f) {
    try {
        return gsMsLoc(c.w, c.x + c.bs.fx * f + c.bs.rx * l,
                            c.y + h,
                            c.z + c.bs.fz * f + c.bs.rz * l);
    } catch (e) { return null; }
}

function gsEmCrackOpen(p) {
    try {
 // ★★ **裂缝冷却 5 秒**（= 绘画 2 秒 + 滞留 3 秒）
        //   用户：撕开裂缝的冷却为 5 秒（2 秒绘画 3 秒滞留）
        //   ★ 冷却与裂缝总寿命**完全对齐** ⇒ 同一时刻只会存在一道裂缝，
        //     不能连按 Shift+右键 同时铺好几道。
        var now = gsMsNow();
        var u = '' + p.getUniqueId();
        var last = GS_EM_CRACK_AT[u];
        if (last != null && (now - last) < GS_EM_CRACK_CD) return;   // 5 秒冷却内不出第二道
        GS_EM_CRACK_AT[u] = now;

        var loc = null;
        try { loc = p.getLocation(); } catch (e) { return; }
        var world = null;
        try { world = p.getWorld(); } catch (e) { return; }
 // ★★ 裂缝是一面**竖直的墙**，所以它的几何**只用水平方向** ——
        //   俯仰（抬头/低头）不参与，否则"长 7 宽 1 高 3"会被斜着拉变形。
        //   （用户这次要求"对着准星朝向"的只有**左键第六刀的次元斩**，
        //     锚点 / 裂缝不在其中。）
        var bs = gsEmBasis(loc);
        var hfx = bs.fx, hfz = bs.fz;
        var hl = Math.sqrt(hfx * hfx + hfz * hfz);
        if (!(hl > 1.0e-4)) { hfx = 0.0; hfz = 1.0; hl = 1.0; }   // 正上/正下看 ⇒ 退回 +Z
        hfx /= hl; hfz /= hl;
        var cx = loc.getX() + hfx * GS_EM_CRACK_DIST;
        var cy = loc.getY();
        var cz = loc.getZ() + hfz * GS_EM_CRACK_DIST;

        GS_EM_CRACKS.push({ w: world, owner: p,
                            x: cx, y: cy, z: cz,
                            bs: { fx: hfx, fz: hfz, rx: -hfz, rz: hfx },
                            t: 0, used: false });
        gsMsSound(world, gsMsLoc(world, cx, cy + GS_EM_CRACK_MIDH, cz),
            'block.end_portal.spawn', 0.8, 0.8);
    } catch (e) { }
}

// 画横线：**从左到右**（progress 0..1）
//   ★ l 是"右向"的系数 ⇒ **l 负 = 左**。所以起点用 -HALF（最左），终点 +HALF（最右）。
function gsEmCrackDrawH(c, prog) {
    try {
        var N = 40;
        var upto = Math.floor(N * prog);
        for (var i = 0; i <= upto; i++) {
            var t = i / N;                                     // 0 = 最左（l = -half）… 1 = 最右
            var l = -GS_EM_CRACK_HALF + t * (GS_EM_CRACK_HALF * 2.0);
            var pt = gsEmCrackPoint(c, l, GS_EM_CRACK_MIDH, 0.0);
            if (pt != null) gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), i);
        }
    } catch (e) { }
}

// 画竖线：从上到下（progress 0..1）
function gsEmCrackDrawV(c, prog) {
    try {
        var N = 24;
        var upto = Math.floor(N * prog);
        for (var i = 0; i <= upto; i++) {
            var t = i / N;
            var h = GS_EM_CRACK_H - t * GS_EM_CRACK_H;          // 高 3 → 高 0
            var pt = gsEmCrackPoint(c, 0.0, h, 0.0);
            if (pt != null) gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), i + 1);
        }
    } catch (e) { }
}

// 画菱形：★ **填满**（逐行从左边填到右边），不是只描边
//   ★★ 第四十六 ~ 四十八批：大小 = **左1.5~左6.5（宽 5.0）** × **高0.7~高2.3（高 1.6）**
//      ⇒ 半宽 2.50、半高 0.80，左右上下都**居中**在十字交点上。
//   ★ 密度：14 行 × 每行最多 21 点 —— 中间那行是**一整条**（不是两个端点），
//     所以看上去是一块实心的紫色菱形，而不是一个空心框。
//     ★ 每格的采样密度（每格 8 个点）**没变** ⇒ 改大小只是跟着变尺寸，观感一致。
function gsEmCrackDrawDiamond(c) {
    try {
        var rows = 14;
        for (var r = 0; r <= rows; r++) {
            var u = (r / rows) * 2.0 - 1.0;                     // -1 .. 1（纵向）
            var halfL = GS_EM_DIA_R * (1.0 - Math.abs(u));      // 该行的半宽
            if (halfL <= 0.0) {
                var p0 = gsEmCrackPoint(c, 0.0, GS_EM_CRACK_MIDH + u * GS_EM_DIA_H, 0.0);
                if (p0 != null) gsEmDot(c.w, p0.getX(), p0.getY(), p0.getZ(), r);
                continue;
            }
            var cols = Math.max(2, Math.round(halfL * 8.0));
            for (var k = 0; k <= cols; k++) {
                var l = -halfL + (2.0 * halfL) * (k / cols);
                var pt = gsEmCrackPoint(c, l, GS_EM_CRACK_MIDH + u * GS_EM_DIA_H, 0.0);
                if (pt != null) gsEmDot(c.w, pt.getX(), pt.getY(), pt.getZ(), r + k);
            }
        }
    } catch (e) { }
}

// 裂缝推进：返回 false 表示该删了
function gsEmCrackTick(c, dtMs) {
    try {
        if (c.used) return false;
        c.t = (c.t | 0) + dtMs;
        var t = c.t | 0;
        var drawEnd = GS_EM_CRACK_DRAW;

        if (t < drawEnd) {
            // ① 前一半画横线（从左到右），② 后一半画竖线（从上到下）
            var half = drawEnd / 2;
            if (t <= half) {
                gsEmCrackDrawH(c, t / half);
            } else {
                gsEmCrackDrawH(c, 1.0);
                gsEmCrackDrawV(c, (t - half) / half);
            }
            return true;
        }

        // ③ 画完 ⇒ 菱形出现 + 可交互
        gsEmCrackDrawH(c, 1.0);
        gsEmCrackDrawV(c, 1.0);
        gsEmCrackDrawDiamond(c);

        if (t > drawEnd + GS_EM_CRACK_KEEP) return false;       // 留存 3 秒到点 ⇒ 消失

        // ④ 触碰判定：玩家走到菱形上 ⇒ 传送到 F 记录的锚点
        var owner = c.owner;
        if (owner != null && owner.isOnline()) {
            var dl = null;
            try { dl = owner.getLocation(); } catch (e) { dl = null; }
            if (dl != null) {
                var dx = dl.getX() - c.x, dy = dl.getY() - c.y, dz = dl.getZ() - c.z;
                var lat = dx * c.bs.rx + dz * c.bs.rz;              // 横向偏移
                var fwd = dx * c.bs.fx + dz * c.bs.fz;              // 前后偏移
                var hh = dy - GS_EM_CRACK_MIDH;                     // 相对菱形中心的高度
                // 落在菱形"胶囊"内即可（横向按半宽、纵向按半高放宽一点）
                if (Math.abs(lat) <= (GS_EM_DIA_R + GS_EM_TOUCH_R * 0.5) &&
                    Math.abs(hh) <= (GS_EM_DIA_H + GS_EM_TOUCH_R) &&
                    Math.abs(fwd) <= GS_EM_TOUCH_R) {
                    if (gsEmTeleport(owner)) { c.used = true; return false; }
                }
            }
        }
        return true;
    } catch (e) { return false; }
}

function gsEmTeleport(p) {
    try {
        var a = GS_EM_ANCHOR['' + p.getUniqueId()];
 // ★ 没记录锚点时**不再发聊天提示**（lore 上就写着「未记录」）
        if (a == null) return false;
        var w = null;
        try { w = getServer().getWorld(a.w); } catch (e) { w = null; }
        if (w == null) { try { w = p.getWorld(); } catch (e) { return false; } }
        var Loc = Java.type('org.bukkit.Location');
        var dst = new Loc(w, a.x, a.y, a.z, 0 + a.yaw, 0 + a.pitch);
        try { p.teleport(dst); } catch (e) { return false; }
        gsMsSoundAt(p, 'entity.enderman.teleport', 1.0, 1.0);
        return true;                                     // ★ 不再发「裂缝将你送回锚点」
    } catch (e) { return false; }
}

// ---------- 心跳 ----------
function gsEmGenNow() {
    try {
        if (GS_EM_PDT == null || GS_EM_K_GEN == null) return null;
        var w = getServer().getWorlds().get(0);
        var v = w.getPersistentDataContainer().get(GS_EM_K_GEN, GS_EM_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}
function gsEmGenBump() {
    try {
        var cur = gsEmGenNow();
        if (cur == null) cur = 0;
        GS_EM_GEN = (cur | 0) + 1;
        if (GS_EM_PDT == null || GS_EM_K_GEN == null) return;
        var w = getServer().getWorlds().get(0);
        w.getPersistentDataContainer().set(GS_EM_K_GEN, GS_EM_PDT.INTEGER, GS_EM_GEN);
    } catch (e) { if (GS_EM_GEN == null || GS_EM_GEN <= 0) GS_EM_GEN = 1; }
}
function gsEmGenOk() {
    try {
        var g = gsEmGenNow();
        if (g == null || GS_EM_GEN === -1) return true;
        return (g === GS_EM_GEN);
    } catch (e) { return true; }
}

function gsEmBarsClean() { gsBarTblClean(GS_EM_BARS); }

function gsEmReleaseAll() {
    try {
        for (var k in GS_EM_BARS) {
            try { GS_EM_BARS[k].b.removeAll(); } catch (e) { }
            try { delete GS_EM_BARS[k]; } catch (e) { }
        }
    } catch (e) { }
    try { GS_EM_FX.length = 0; } catch (e) { }
    try { GS_EM_FIELDS.length = 0; } catch (e) { }
    try { GS_EM_CRACKS.length = 0; } catch (e) { }
 // ★ 本实例被 /rsc reload 淘汰 ⇒ 顺手把手持增益收干净。
    //   这是唯一能正确回收「我们开的那份飞行」的时机 ——
    //   新实例的表是空的，它并不知道旧实例给谁开过飞行。
    try {
        var ps = getServer().getOnlinePlayers();
        var pit = ps.iterator();
        while (pit.hasNext()) {
            try { gsEmBuffClear(pit.next()); } catch (e) { }
        }
    } catch (e) { }
}

var GS_EM_LAST_PULSE = 0;

function gsEmPulse() {
    if (!gsEmGenOk()) { gsEmReleaseAll(); return; }
    var now = gsMsNow();
    var dt = 100;                                     // 心跳周期（毫秒），固定 2 刻
    if (GS_EM_LAST_PULSE > 0) {
        dt = now - GS_EM_LAST_PULSE;
        if (dt <= 0) dt = 100;
        if (dt > 1000) dt = 1000;                     // 卡顿保护：别让状态一下跳过头
    }
    GS_EM_LAST_PULSE = now;

 // ① 连击血条；★ 顺带结算「手持增益」（拿在手上就生效 / 脱手立刻收）
    try {
        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsEmPlayerTick(p); } catch (e2) { }
            try { gsEmBuffTick(p); } catch (e2) { }
        }
    } catch (e) { }

    // ② 斩击特效淡出（`GS_EM_FX_MS` 内逐帧重画，越往后越稀）
    //   ★ 重画用的是**出刀那一刻存下来的整套坐标系** ⇒ 形状不会因为转头而歪
 // ★★ 特效存在时间 0.6 → **0.4 秒**。
    //      心跳是每 2 刻（0.1 秒）一次 ⇒ 淡出期间仍有 **4 帧**，够顺。
    //      （若以后把 FX 调到 ≤ 0.2 秒，帧数只剩 2 帧，淡出会显得"跳"，那时要改心跳）
    try {
        var keep = [];
        for (var i = 0; i < GS_EM_FX.length; i++) {
            var fx = GS_EM_FX[i];
            var age = now - fx.t0;
            if (age >= GS_EM_FX_MS) continue;
            var fade = 1.0 - (age / GS_EM_FX_MS);
            try { gsEmDrawSlash(fx.p, fx.w, fx.fr, fx.idx | 0, fade); } catch (e) { }
            keep.push(fx);
        }
        GS_EM_FX = keep;
    } catch (e) { }

    // ③ 次元斩领域（吸引 + 每 0.5 秒伤害），画面是**红紫色大圆环**、色带缓慢绕环转
 // （★ 原来是"四刀 × 前后两层"的圆柱；★ 只由第六刀产生）
 // ★★ **粒子做稀** —— 画面重画从每 0.1 秒改成**每 0.2 秒**
    //      （伤害与吸引仍然是每 0.1 秒判定，手感不受影响；粒子本身有存活时间，
    //        所以看起来还是连续的，只是不再"糊成一片"）
    try {
        var keepF = [];
        for (var j = 0; j < GS_EM_FIELDS.length; j++) {
            var f = GS_EM_FIELDS[j];
            var age2 = now - f.born;
            if (age2 >= GS_EM_DS_LIFE) continue;
            var fade2 = 1.0 - (age2 / GS_EM_DS_LIFE);
            if (f.drawAt == null || (now - f.drawAt) >= GS_EM_DS_DRAW_MS) {
                f.drawAt = now;
                try {
                    gsEmDrawBig(f.w, f.x, f.y, f.z, f.bs, age2 * 0.0016, fade2);
                } catch (e) { }
            }
            if (gsEmFieldTick(f, now)) keepF.push(f);
        }
        GS_EM_FIELDS = keepF;
    } catch (e) { }

    // ④ 裂缝（绘画 → 留存 → 触碰传送）
    try {
        var keepC = [];
        for (var k = 0; k < GS_EM_CRACKS.length; k++) {
            var c = GS_EM_CRACKS[k];
            if (gsEmCrackTick(c, dt)) keepC.push(c);
        }
        GS_EM_CRACKS = keepC;
    } catch (e) { }

    try { gsEmBarsClean(); } catch (e) { }
}

// ---------- 去抖 ----------
function gsEmClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_EM_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_EM_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}
function gsEmRecentRight(p, gapMs) {
    try {
        var last = GS_EM_CLICK_AT['' + p.getUniqueId() + '|R'];
        if (last == null) return false;
        return (gsMsNow() - last) < gapMs;
    } catch (e) { return false; }
}

// ---------- 注册同步监听器 ----------
function gsEmRegisterSync() {
    if (GS_EM_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ① ★★ 第六十四批：**不再注册 PlayerSwapHandItemsEvent**
        //   原来 F=锚点、Shift+F=裂缝，现在两个都搬到**右键**了
        //   （用户「阎魔刀的锚点改成右键，撕裂空间改成shift+右键」）
        //   ⇒ 按 F 就是**原版换手**，阎魔刀一个字都不拦。

        // ② 左键 = 居合斩（第六刀附带次元斩）；右键 = 锚点；Shift+右键 = 撕裂空间
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    // ★ 故意不检查 e.isCancelled()：有些前置插件会先取消 *_CLICK_AIR
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsEmIs(gsEmHand(p))) return;
                    var hs = '';
                    try { hs = '' + e.getHand().name(); } catch (e2) { hs = ''; }
                    if (hs.length > 0 && hs.indexOf('OFF') >= 0) return;      // 只认主手

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }

                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        // ★ 构象侦测器的「模拟右键」：事件里的物品是空气（脚本合成的假事件），
                        //   放过 ⇒ 否则机器会替旁边的人误放次元斩
                        try {
                            var evItem = e.getItem();
                            var evName = (evItem == null) ? '' : ('' + evItem.getType().name());
                            if (evName.length === 0 || evName === 'AIR') return;
                        } catch (e2i) { }
                        if (!gsEmClickOnce(p, 'R')) return;
                        e.setCancelled(true);
                        if (Res != null) {
                            try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                            try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                        }
                        GS_EM_CLICK_AT['' + p.getUniqueId() + '|R'] = gsMsNow();
 // ★★ 右键 = 锚点；**Shift + 右键 = 撕开裂缝**
                        var sneak2 = false;
                        try { sneak2 = p.isSneaking(); } catch (e2s) { sneak2 = false; }
                        if (sneak2) gsEmCrackOpen(p);
                        else gsEmAnchorSet(p);
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        if (!gsEmClickOnce(p, 'L')) return;
                        if (GS_EM_NO_BREAK) {
                            e.setCancelled(true);
                            if (Res != null) {
                                try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                                try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                            }
                        }
                        gsEmSwing(p);
                    }
                } catch (e3) { }
            });
        }

        // ③ 左键兜底：挥手动画（对着空气点也会发；与 ② 共用去抖 ⇒ 不会放两次）
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    if (!gsEmIs(gsEmHand(p))) return;
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (gsEmRecentRight(p, 300)) return;      // 右键附带的挥手 → 忽略
                    if (!gsEmClickOnce(p, 'L')) return;
                    gsEmSwing(p);
                } catch (e3) { }
            });
        }

        // ④ 手持时挖不动方块（双保险）
        if (GS_EM_NO_BREAK) {
            var BreakEv = null;
            try { BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent'); } catch (e) { }
            if (BreakEv != null) {
                reg(BreakEv, function (e) {
                    try {
                        if (!gsEmIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
            var DmgBlkEv = null;
            try { DmgBlkEv = Java.type('org.bukkit.event.block.BlockDamageEvent'); } catch (e) { }
            if (DmgBlkEv != null) {
                reg(DmgBlkEv, function (e) {
                    try {
                        if (!gsEmIs(e.getPlayer().getInventory().getItemInMainHand())) return;
                        e.setCancelled(true);
                    } catch (e2) { }
                });
            }
        }

 // ⑤ 玩家退出 ⇒ 清掉血条 + ★ 收走手持增益（飞行 / 药效）
        //   （锚点**保留**，下次进来还能用）
        var QuitEv = null;
        try { QuitEv = Java.type('org.bukkit.event.player.PlayerQuitEvent'); } catch (e) { }
        if (QuitEv != null) {
            reg(QuitEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    gsEmClear(p);
                    gsEmBarDrop(p);
                    try { gsEmBuffClear(p); } catch (e3) { }
                } catch (e2) { }
            });
        }

 // ⑥ ★ 玩家进服 / 切换主手物品 ⇒ 把锚点坐标同步进阎魔刀的 lore
        //   （用户：最好把位置记录写到阎魔刀的lore里）
        //   ★ 锚点存在**脚本内存**里 ⇒ 重启后就是空的，这时 lore 会显示「未记录」，
        //     不会留着上一次的旧坐标误导人。
        var JoinEv = null;
        try { JoinEv = Java.type('org.bukkit.event.player.PlayerJoinEvent'); } catch (e) { }
        if (JoinEv != null) {
            reg(JoinEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    runLater(function () { gsEmLoreSync(p); }, 20);   // 等 1 秒，背包就位后再写
                } catch (e2) { }
            });
        }
        var HeldEv = null;
        try { HeldEv = Java.type('org.bukkit.event.player.PlayerItemHeldEvent'); } catch (e) { }
        if (HeldEv != null) {
            reg(HeldEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    // ★ 这个事件触发时主手**还没换完** ⇒ 延后 1 刻再读
                    runLater(function () { gsEmLoreSync(p); }, 1);
                } catch (e2) { }
            });
        }

        GS_EM_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 阎魔刀·改监听器注册成功（左键=居合斩六刀·第六刀附带次元斩 / 右键=锚点·坐标写入lore / Shift+右键=裂缝 / F=原版换手）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 阎魔刀·改监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsEmStart() {
    try {
        gsEmGenBump();
        if (!gsEmRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 阎魔刀·改启动失败：监听器没注册上（血条仍会跑）'); } catch (e) { }
        }
        runRepeating(gsEmPulse, 20, 2);      // 每 2 刻（0.1 秒）推进血条 / 特效 / 领域 / 裂缝
        try {
            getServer().getLogger().info('[构思技艺] 阎魔刀·改（' + GS_EM_ID + '）已加载 · 居合斩 '
                + GS_EM_DMG + ' 点 / 范围 宽' + GS_EM_WIDE + '×长' + GS_EM_RANGE + '×高' + GS_EM_H
                + ' / ' + GS_EM_SLASH_N + ' 刀一循环（冷却 ' + (GS_EM_CD / 1000)
                + ' 秒 · 特效 ' + (GS_EM_FX_MS / 1000) + ' 秒 · 停手 ' + (GS_EM_RESET_MS / 1000)
                + ' 秒清空 · 前五刀「画圆圈切掉后面」成朝前半圆（下→上 / 横斩(右→左) / 左上→右下'
                + ' / 横斩(左→右) / 三弧顶点相撞，前方进深压扁 ' + GS_EM_ARCH
                + '）· 第5刀三弧顶点相撞于前方 · 第6刀半径' + GS_EM_S6_R
                + ' 左高右低圆圈 + 圈内额外 ' + GS_EM_S6_DMG
                + ' 点）· 朝向 全部沿准星（含俯仰）· 第六刀次元斩 身前 ' + GS_EM_DS_DIST
                + ' 格 / 半径 ' + GS_EM_DS_R
                + ' 格 / 画面=红紫色大圆环(半径' + GS_EM_DS_BIG + '·' + GS_EM_DS_RING_N + '点)'
                + ' / 每 ' + (GS_EM_DS_EVERY / 1000) + ' 秒 ' + GS_EM_DS_DMG
                + ' 点 / 存在 ' + (GS_EM_DS_LIFE / 1000) + ' 秒 / 无冷却 / 持续吸引'
                + ' / 画面每 ' + (GS_EM_DS_DRAW_MS / 1000) + ' 秒重画（粒子做稀）'
                + ' · 裂缝 长' + (GS_EM_CRACK_HALF * 2) + '×宽1×高' + GS_EM_CRACK_H
                + ' / 绘画 ' + (GS_EM_CRACK_DRAW / 1000) + ' 秒 / 留存 ' + (GS_EM_CRACK_KEEP / 1000)
                + ' 秒 / 冷却 ' + (GS_EM_CRACK_CD / 1000) + ' 秒'
                + ' / 菱形 宽' + (GS_EM_DIA_R * 2) + '×高' + (GS_EM_DIA_H * 2)
                + ' · 手持增益 可飞行+抗性IV+生命恢复III+速度II+夜视'
                + '（一次 ' + (GS_EM_BUFF_TICKS / 20) + ' 秒 · 剩余 ' + (GS_EM_BUFF_REF / 20)
                + ' 秒续期 · 脱手立刻收走）'
                + ' · 右键=锚点（坐标写入 lore，不发聊天提示） · Shift+右键=裂缝'
                + ' · F=原版换手 · 世界世代号 ' + GS_EM_GEN);
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 阎魔刀·改启动异常: ' + e); } catch (e2) { }
    }
}

gsEmStart();

// ==========================================================================
// 月镰 / 蚀月 · 公共：取消「原版近战」那一下（）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §24
//   ★ 以下 1 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//     ② 原因 = ENTITY_ATTACK / ENTITY_SWEEP_ATTACK（火焰、摔落之类完全不碰）
// ==========================================================================
var GS_WPN_NOMELEE = true;      // true = 手持月镰/蚀月/致万千无言者/此刻万籁俱寂时，原版左键近战不造成任何伤害
var GS_WPN_NL_PVP  = false;     // false = 只对生物生效；玩家之间照常（要 PvP 也去掉 ⇒ true）
var GS_WPN_IDS     = ['GS_MOON_SCYTHE', 'GS_ECLIPSE_SCYTHE',
 // ★ 致万千无言者（石镐）——
                      //   它的左键伤害**完全由 gsVo* 模块按连击结算**，
                      //   原版那一下必须取消（否则怪被击退 + 吃无敌帧 ⇒ 连击断掉）
                      'GS_VOICELESS',
 // ★ 此刻万籁俱寂（金镐）——
                      //   同理：伤害由 gsSi* 模块按连击结算；且它打的是**前方 5 格长条**，
                      //   原版近战的击退会把怪推出这条长条 ⇒ 连击直接断
                      'GS_SILENCE',
 // ★ 阎魔刀·改（金剑）——
                      //   伤害由 gsEm* 模块的「居合斩」按 宽5×长9×高3 结算；
                      //   原版近战那一下必须取消（否则怪被击退 + 吃 10 刻无敌帧）
                      'GS_ENMA_BLADE'];

function gsWpnHold(p) {
    try {
        if (p == null) return false;
        var it = null;
        try { it = p.getInventory().getItemInMainHand(); } catch (e) { return false; }
        if (it == null) return false;
        for (var i = 0; i < GS_WPN_IDS.length; i++) {
            if (gsIsItem(it, GS_WPN_IDS[i])) return true;
        }
        return false;
    } catch (e) { return false; }
}

function gsWpnNoMelee(e) {
    try {
        if (!GS_WPN_NOMELEE) return;
        if (GS_WPN_DMG_GUARD > 0) return;                        // ④ 放行自家技能伤害
        var cause = '';
        try { cause = '' + e.getCause().name(); } catch (e0) { return; }
        if (cause.indexOf('ENTITY_ATTACK') < 0 && cause.indexOf('SWEEP') < 0) return;   // ②
        var dam = null;
        try { dam = e.getDamager(); } catch (e0) { return; }
        if (dam == null) return;
        var tn = '';
        try { tn = '' + dam.getType().name(); } catch (e0) { return; }
        if (tn !== 'PLAYER') return;                             // ③
        var victim = null;
        try { victim = e.getEntity(); } catch (e0) { victim = null; }
        if (!GS_WPN_NL_PVP && victim != null) {                   // ⑤
            var vt = '';
            try { vt = '' + victim.getType().name(); } catch (e0) { }
            if (vt === 'PLAYER') return;
        }
        if (!gsWpnHold(dam)) return;                             // ③
        try { e.setCancelled(true); } catch (e0) { }
    } catch (e9) { }
}

var GS_WPN_REGISTERED = false;

function gsWpnNoMeleeStart() {
    if (GS_WPN_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var Ev = null;
        try { Ev = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { Ev = null; }
        if (Ev == null) return false;

        var ok = false;
        try {
            pm.registerEvent(Ev, plugin, Priority.HIGHEST,
                function (l, e) { gsWpnNoMelee(e); }, plugin);
            ok = true;
        } catch (e1) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, e) { gsWpnNoMelee(e); } });
                pm.registerEvent(Ev, listener, Priority.HIGHEST, exec, plugin);
                ok = true;
            } catch (e2) { ok = false; }
        }
        GS_WPN_REGISTERED = ok;
        if (ok) {
            try {
                getServer().getLogger().info('[构思技艺] 武器原版近战已取消（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂）· '
                    + (GS_WPN_NOMELEE ? '已启用' : '已停用')
                    + ' · 对玩家 ' + (GS_WPN_NL_PVP ? '同样取消' : '照常'));
            } catch (e) { }
        } else {
            try { getServer().getLogger().warning('[构思技艺] 取消原版近战：监听器没注册上'); } catch (e) { }
        }
        return ok;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 取消原版近战注册失败: ' + e); } catch (e2) { }
        return false;
    }
}

gsWpnNoMeleeStart();

// ==========================================================================
// 篮球共用引擎（原「你等着收我的律师函吧」GS_LAWYER_LETTER · 构思玩具 · ）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §12
// ==========================================================================
var GS_BB_ID = 'GS_LAWYER_LETTER';
// ★★★ 现状（务必先读这段再改本模块）：
//   `GS_LAWYER_LETTER` 那件物品**第三十六批已整件删除**（items.yml / groups.yml 里都没有它）
//   ⇒ gsBbIs() 恒为 false，本模块**只剩「篮球共用引擎」这一个身份**：
//     「篮球生成器」（GS_BASKET_GEN · gsBg*）复用它的投篮物理 / 落地弹跳 /
//     展示实体渲染 / 运球血条 / 副手钥匙球那套事件。
//   ★ 启动日志里那两行带「律师函」字样的文本是**历史遗留**，看到属正常、可以无视
//     （部署说明.txt「启动日志段」里有同样说明）。
//   ★ `GS_BB_ID` 本身**不要删**：旧存档里已经发出去的球物品还要靠它认出来。
//   ★ 命名里的 `BB` = **BasketBall（篮球）**，不是「律师函」的缩写。

// ---- 副手「篮球」物品（★ ）----
//   这颗球**不是给玩家的道具**，只是"骗客户端发包"的钥匙：
//     原版客户端在"手上（主手或副手）有物品"时才会对**空气**发右键包 ⇒ 有了它右键才通。
//   名字就叫「篮球」（白色）。
// ★ 材质：是**菌光体 SHROOMLIGHT**，★★ 改成 **金合欢木按钮 ACACIA_BUTTON**
//     （用户指定；改的只是"这颗钥匙长什么样"，不影响任何机制）。
//   ★ 只在**副手空着**时放；副手本来有东西 ⇒ 原版本来就会发包 ⇒ **绝不动玩家的物品**。
var GS_BB_BALL_MAT      = 'ACACIA_BUTTON';     // ★ 第二十四批：球物品材质 = 金合欢木按钮
var GS_BB_BALL_NAME     = '篮球';              // 球物品名字
var GS_BB_SWING_FALLBACK = true;               // 空手「挥手」兜底开关（第二十二批的老办法）
                                               //   ★ 副手有球之后右键真的通了 ⇒ 想避免
                                               //     "左键攻击顺手把球扔出去"就把这里改 false

// ---- 球体 ----
// ★★ 球**缩小到「头颅大小」**（玩家头颅是 0.5 格立方 ⇒ 直径取 0.48 格）
//      并且**彻底不再用烟花粒子**：
//        FIREWORK（烟花）的**精灵本身就很大**（≈0.4 格）且带外散 ⇒ 球看起来比坐标上的球体外扩一圈，
//        改用 **DUST + DustOptions** —— 颜色还是那两色（RGB 精确到分），**大小由 DOT_SIZE 控制**。
var GS_BB_R         = 0.24;                    // 球半径（格）—— 直径 0.48 ≈「头颅」大小
var GS_BB_DOTS      = 26;                      // 球面采样点数
var GS_BB_DOT_SIZE  = 0.8;                     // 单个尘埃粒子的大小（DustOptions 的 size）
var GS_BB_SPIN      = 0.06;                    // 自转（弧度/刻）
var GS_BB_ORANGE    = [255, 140, 0];           // 橙（篮球主色）
var GS_BB_BLACK     = [14, 14, 14];            // 黑（篮球线条）

// ---- 生成 / 触碰 ----
var GS_BB_SPAWN_FWD = 2.0;                     // 生成在玩家前方 2 格
var GS_BB_SPAWN_UP  = 0.9;                     // 生成高度（离地）
var GS_BB_LEASH     = 6.0;                     // idle 球离玩家超过 6 格 ⇒ 重新放回面前
var GS_BB_TOUCH     = 1.4;                     // 触碰半径（水平，格）
var GS_BB_TOUCH_V   = 1.6;                     // 触碰高度差（格）

// ---- 绑定（运球）----
var GS_BB_BIND_MS   = 10000;                   // ★ 第二十四批：绑定（运球）持续 **10 秒**（原 6 秒）
var GS_BB_BOUNCE_S  = 0.45;                    // 地面 ↔ 右手 一个来回的时长（秒）
var GS_BB_HAND_UP   = 1.15;                    // 右手高度（玩家脚底起算）
var GS_BB_HAND_SIDE = 0.42;                    // 右手横向偏移（格）
var GS_BB_HAND_FWD  = 0.5;                     // ★ 第二十四批：往前挪**半个身位**（格）
                                               //   否则球贴在身侧，玩家一走就变成"球追着人跑"；
                                               //   往前 0.5 格 ⇒ 观感是"人在身前运球"。
var GS_BB_DROP_R    = 2.0;                     // 超时落点：附近 2 格内
var GS_BB_REBIND_CD = 300;                     // 落地后静默 0.3 秒才允许再次触碰绑定

// ---- 运球倒计时血条（★★ ）----
//   ★ 标题**只有「运球」两个字** —— 不带数字、不带层数、不带后缀；
//     倒计时**由血条长度体现**：进度 = 剩余时间 / GS_BB_BIND_MS（10 秒）⇒ 满条 → 空条 → 消失。
//   ★ 原版 BarColor **没有 ORANGE**（只有 PINK/BLUE/RED/GREEN/YELLOW/PURPLE/WHITE）
//     ⇒ 颜色按 ORANGE → GOLD → YELLOW 依次尝试，落到 **YELLOW**（7 色里最接近橙的）；
//       标题文字再用 §6（金橙）补足"橙色血条"的观感。
var GS_BB_BAR_TEXT  = '运球';                  // ★ 血条正文：**就这两个字**
var GS_BB_BAR_COLOR = '§6';                    // 标题颜色码（§6 = 金橙）

// ---- 投篮（★★ 方向 = 准星**三维**方向，不再是"水平 + 固定斜角"）----
//   ★ 旧做法（第二十一 ~ 二十四批）：水平沿准星 + 竖直**固定 +0.62** ⇒ 无论你怎么看，
//     投出去的都是同一个斜角 ⇒ 平视想传球会往上拱、朝下想拍球会往上飘，**没法做动作**。
// ★★ 的做法：初速向量 = **准星方向（含俯仰角）** × GS_BB_SHOT_SPD，即
//       `v = getDirection() * SPD` ⇒ **指哪打哪**：
//         · **平视**（pitch ≈ 0）⇒ vy ≈ 0 ⇒ 球**平着飞出去** = 传球
//         · **朝下** ⇒ 球往脚下方向飞、**很快落地** ⇒ 假动作（落地后走过去碰就重新拿到）
//         · **朝上** ⇒ 高抛远投
//   ★ 重力改成**两段式**「先直线、后重力」：
//       旧写法（全程固定重力 0.048）会让"平视传球"在 15 格外就砸地 —— 传不出去；
//       但如果为了传球把重力调得极小，朝上 45° 投的射程会变成 v²/g ≈ **195 格**（球飞丢）。
//       所以：**出手后先直线飞 GS_BB_SHOT_STRAIGHT 刻**（这期间完全不受重力 ⇒ 平视 = 真的平传），
//       **之后才开始受重力下坠** ⇒ 球一定会落回地面，且朝上投的射程被压到可接受范围。
var GS_BB_SHOT_SPD  = 1.25;                    // ★ 投篮初速度（格/刻）—— 沿**准星方向**
var GS_BB_SHOT_FWD  = 0.6;                     // 出手点：沿准星**水平**方向前移的格数
var GS_BB_STRAIGHT  = 12;                      // 出手后的**直线段**刻数（这期间不受重力）
var GS_BB_SHOT_GRAV = 0.05;                    // 直线段之后的重力（格/刻²）⇒ 球最终落回地面
var GS_BB_FALL_FAST = -0.55;                   // 撞墙后垂直下坠的初速度
var GS_BB_DRAG      = 0.998;                   // 水平空气阻力
var GS_BB_SHOT_CD   = 350;                     // 投篮冷却（毫秒）
var GS_BB_MAX_TICKS = 400;                     // 飞行刻数兜底（防卡死）
// ★★ 防隧穿。球最快 1.25 格/刻、撞墙后垂直下坠能累积到 2 格/刻以上
//   ⇒ 一格厚的地板会被"一步跨过" ⇒ 撞墙 / 落地都必须做**扫掠检测**（沿这段路径取样），
//   不能只测目标点。GS_BB_SWEEP_N = 水平方向一次取样几段（越大越准、越费一点性能）。
var GS_BB_SWEEP_N   = 4;
// ---- ★★ 落地后**再弹两下** ----
//   球投出去砸到地面 ⇒ **不立刻停**，而是弹 GS_BB_BOUNCES 下：
//     · **没撞过墙** ⇒ 每次落地水平速度按 GS_BB_BOUNCE_KEEP 保留一点点
//       ⇒ 球**顺着投出的方向再往前弹两下**才停（像真篮球那样滚两步）
//     · **撞过墙** ⇒ 水平速度本来就是 0 ⇒ **原地弹两下**就停（不会贴着墙往前蹭）
//   弹起高度逐次衰减（第二下 = 第一下 × GS_BB_BOUNCE_DECAY）⇒ 观感是"弹、弹、停"。
var GS_BB_BOUNCES      = 2;                    // 落地后弹几下（0 = 一落地就停，回到老样子）
var GS_BB_BOUNCE_UP    = 0.30;                 // 第一次弹起的竖直初速（格/刻，约弹起 1 格）
var GS_BB_BOUNCE_DECAY = 0.60;                 // 每弹一次，弹起高度乘这个数（第二下约 0.4 格）
var GS_BB_BOUNCE_KEEP  = 0.35;                 // 落地时水平速度保留比例 ⇒ "往前一点点"
var GS_BB_BOUNCE_MAX   = 0.25;                 // 前弹的水平速度上限（格/刻），别弹太远

// ---- ★★ 篮球改用**展示实体**渲染（默认 ItemDisplay + 头颅贴图）----
//   起因：用户给出一个「橙黑配色头颅」的贴图（GLTC 附属里的物品），要求
//        "把篮球的粒子换成展示方块实体，用这个头颅的材质替换，最好低延迟"。
//   ★ 为什么是 **ItemDisplay（物品展示实体）** 而不是 BlockDisplay：
//       BlockDisplay 只能显示**方块状态**，而「头颅方块」的方块状态里**没有皮肤数据**
//       （皮肤在 item 的 `minecraft:profile` 组件里）⇒ BlockDisplay 只会显示默认皮肤。
//       要显示这个自定义皮肤，必须用 ItemDisplay **显示一个 head 物品**。
//   ★ 延迟：原来的粒子球每刻发 **26 个粒子包**；换成实体后每刻只有 **1 个实体位置包**
//       ⇒ 发包量降到 1/26，渲染交给客户端实体系统（与玩家/怪物同一条通道），更平滑。
//       想再省：把 GS_BB_ENT_EVERY 调成 2、同时 GS_BB_ENT_TPDUR 设 2
//       （客户端在 2 刻内插值补间 ⇒ 视觉一样顺，带宽再砍一半）。
var GS_BB_USE_ENT    = true;                   // true = 用展示实体渲染；false = 回到旧的尘埃粒子球
var GS_BB_ENT_EVERY  = 1;                      // 每几刻同步一次位置（1 = 每刻，与原版实体同频）
var GS_BB_ENT_TPDUR  = 0;                      // 客户端**位置**插值刻数（Paper Display#setTeleportDuration）
var GS_BB_ENT_INTDUR = 0;                      // 客户端**变换**插值刻数（setInterpolationDuration）
var GS_BB_ENT_SHADOW = 0.0;                    // 球的影子强度（0 = 不投影）
//   ★ 头颅贴图（用户提供）：base64 的 textures 属性 + 里面的皮肤 URL。
//     base64 与 URL **是同一条皮肤的两种写法** —— 两条路都试，谁的 API 能用就用谁。
var GS_BB_HEAD_NAME  = 'GS_Basketball';
var GS_BB_HEAD_UUID  = '8a04ed5c-bdf5-3965-be39-a8de9435927a';   // = 原物品 profile 里那四个 int
var GS_BB_HEAD_TEX   = 'eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvZWRmODQ3MTVhNjRkYzQ1NTg2ZjdhNjA3OWY4ZTQ5YTk0NzdjMGZlOTY1ODliNGNmZDcxY2JhMzIyNTRhYzgifX19';
var GS_BB_HEAD_URL   = 'http://textures.minecraft.net/texture/edf84715a64dc45586f7a6079f8e49a9477c0fe96589b4cfd71cba32254ac8';
var GS_BB_HEAD_ITEM  = null;                   // 造好的头颅 ItemStack（**只造一次**后缓存）
var GS_BB_ENT_OK     = null;                   // null = 还没探测；false = 这台服务端没有 ItemDisplay（永久退回粒子）

var GS_BB_ST = {};                             // uuid -> 状态
var GS_BB_REGISTERED = false;
var GS_BB_GEN = -1;                            // 心跳世代号
var GS_BB_CLICK_AT = {};                       // 去抖
var GS_BB_SWEEP_AT = 0;                        // ★ 第二十三批：上次"篮球残留"清扫时刻
// ★★ 运球倒计时血条。**必须用自己这张表** —— 同一玩家身上可以同时挂多条
//    bossbar（会叠着显示），复用蚀月的 GS_EC_BARS 会把蚀月那条血条顶掉。
var GS_BB_BARS = {};                           // uuid -> { b: BossBar, p: Player, on: bool }

var GS_BB_K_GEN = null, GS_BB_PDT = null, GS_BB_K_BALL = null;
try {
    var BB_NK = Java.type('org.bukkit.NamespacedKey');
    GS_BB_K_GEN = BB_NK.fromString('gs:bb_gen');
    GS_BB_K_BALL = BB_NK.fromString('gs:bb_ball');   // ★ 第二十三批：副手「篮球」的标记
    GS_BB_PDT   = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }
// ★★ 打在每个「篮球」展示实体身上的标记（`gs:ent_ball`）。
//   用处：重启后残留的**孤儿球**只能靠它（或那颗专属头颅）认出来 ⇒ 见文件后面
// 「展示实体孤儿球清扫（GS_ENT_* · 重启后残留的无敌篮球）」那一节。
var GS_ENT_TAG = null;
try { GS_ENT_TAG = Java.type('org.bukkit.NamespacedKey').fromString('gs:ent_ball'); } catch (e) { }

var GS_BB_FEC = null;                          // Color 缓存（兜底粒子用）
var GS_BB_SPELL = null, GS_BB_SPELL_TRIED = false;

function gsBbIs(item) { return gsIsItem(item, GS_BB_ID); }

// ---------- 副手「篮球」物品（★★ ）----------
// 为什么要有这颗球：原版客户端**主手空着对着空气右键时根本不发包**（的坑），
//   但只要**手上（主手或副手）有物品**，客户端就会照常发右键包 ⇒ 服务端才收得到事件。
//   于是「绑定期间往副手放一颗篮球」⇒ **右键真的能把球投出去**（不再只能靠挥手兜底）。
//   ★★ 安全前提：**只在副手空着的时候才放** —— 副手本来就有东西（盾 / 火把…）时原版照样发包，
//      此时**绝不动玩家的物品**（不覆盖、不备份、不顶替）⇒ 根本不存在"把玩家东西弄丢"的可能。
var GS_BB_MAT_C = null, GS_BB_ISC_C = null;
function gsBbBallNew() {
    try {
        if (GS_BB_MAT_C == null) GS_BB_MAT_C = Java.type('org.bukkit.Material');
        if (GS_BB_ISC_C == null) GS_BB_ISC_C = Java.type('org.bukkit.inventory.ItemStack');
        var mat = null;
        try { mat = GS_BB_MAT_C.valueOf(GS_BB_BALL_MAT); } catch (e) { mat = null; }
        if (mat == null) { try { mat = GS_BB_MAT_C.getMaterial(GS_BB_BALL_MAT); } catch (e) { } }
        if (mat == null) return null;
        var it = new GS_BB_ISC_C(mat);
        try {
            var m = it.getItemMeta();
            if (m != null) {
                try { m.setDisplayName('§f' + GS_BB_BALL_NAME); } catch (e) { }
                try {
                    if (GS_BB_PDT != null && GS_BB_K_BALL != null)
                        m.getPersistentDataContainer().set(GS_BB_K_BALL, GS_BB_PDT.STRING, GS_BB_BALL_NAME);
                } catch (e) { }
                it.setItemMeta(m);
            }
        } catch (e) { }
        return it;
    } catch (e) { return null; }
}

// 这颗物品是不是**我们发的那颗「篮球」**
//   ① 优先认 **PDC 标记**（玩家自己捡来的菌光体没有这个标记 ⇒ 绝不会误删/误判）
//   ② PDC 用不了（老版本 / 离线桩）才退回「**名字 + 材质都对**」的双判（尽量不误伤）
function gsBbIsBall(item) {
    try {
        if (item == null) return false;
        var m = null;
        try { m = item.getItemMeta(); } catch (e) { m = null; }
        try {
            if (m != null && GS_BB_PDT != null && GS_BB_K_BALL != null) {
                var v = m.getPersistentDataContainer().get(GS_BB_K_BALL, GS_BB_PDT.STRING);
                if (v != null && ('' + v) === GS_BB_BALL_NAME) return true;
            }
        } catch (e) { }
        try {
            if (m == null) return false;
            var dn = '';
            try { dn = '' + m.getDisplayName(); } catch (e) { dn = ''; }
            if (dn.indexOf(GS_BB_BALL_NAME) < 0) return false;
            var t = item.getType();
            var n = (t == null) ? '' : ('' + t.name());
            return (n === GS_BB_BALL_MAT);
        } catch (e) { }
    } catch (e) { }
    return false;
}

// 物品是不是"空的"（AIR / CAVE_AIR / VOID_AIR）
function gsBbIsEmpty(item) {
    try {
        if (item == null) return true;
        var ty = item.getType();
        if (ty == null) return true;
        var n = '' + ty.name();
        return (n === 'AIR' || n === 'CAVE_AIR' || n === 'VOID_AIR');
    } catch (e) { return false; }
}

// 【绑定当刻】副手**空着**才放一颗「篮球」；副手已有东西 ⇒ 原版本来会发包 ⇒ 一律不动
//   ★ 每一刻都会被带上一次（见 gsBbPlayerTick）⇒ 玩家把球挪走 / 扔了，下一刻立刻补回来
function gsBbHandOn(p, st) {
    try {
        if (p == null || st == null) return;
        var inv = p.getInventory();
        if (inv == null) return;
        var cur = null;
        try { cur = inv.getItemInOffHand(); } catch (e) { cur = null; }

        if (gsBbIsBall(cur)) { st.heldBall = true; return; }   // 副手已经是我们那颗 ⇒ 复用
        if (st.heldBall) st.heldBall = false;                  // 记账过期（球被挪走了）⇒ 重新放

        if (!gsBbIsEmpty(cur)) return;    // ★ 副手是**玩家自己的东西** ⇒ 不碰它（原版照样发包）
        var ball = gsBbBallNew();
        if (ball == null) return;
        try { inv.setItemInOffHand(ball); st.heldBall = true; } catch (e) { }
    } catch (e) { }
}

// 【离开 bound】把副手那颗「篮球」**收走**（收的只是我们自己发的临时球 ⇒ 不会丢玩家任何东西）
function gsBbHandOff(p, st) {
    try {
        if (st == null || !st.heldBall) return;
        st.heldBall = false;
        var inv = (p != null) ? p.getInventory() : null;
        if (inv == null) return;
        var cur = null;
        try { cur = inv.getItemInOffHand(); } catch (e) { cur = null; }
        if (gsBbIsBall(cur)) { try { inv.setItemInOffHand(null); } catch (e) { } }
    } catch (e) { }
}

// 【兜底清扫】每 2 秒扫一遍在线玩家的**整个背包**：
//   只要发现「我们的篮球」而它**不该在那儿**（不在绑定期 / 不在副手）⇒ 直接清掉。
//   （防的是"玩家把球拖进背包 / 扔了又捡回来 / 脚本重载导致状态丢失"这几种残留）
function gsBbSweep(p) {
    try {
        if (p == null) return;
        var inv = p.getInventory();
        if (inv == null) return;
        var st = GS_BB_ST['' + p.getUniqueId()];
        var bound = (st != null && st.mode === 'bound');
        var n = 0;
        try { n = inv.getSize(); } catch (e) { n = 36; }
        if (!(n > 0)) n = 36;
        for (var i = 0; i < n; i++) {
            var it = null;
            try { it = inv.getItem(i); } catch (e) { continue; }
            if (!gsBbIsBall(it)) continue;
            if (i === 40 && bound) continue;      // 40 = 副手槽：绑定期那颗是"正常"的，留着
            try { inv.setItem(i, null); } catch (e) { }
        }
    } catch (e) { }
}

// ---------- 运球倒计时血条（★★ ）----------
//   ★ 用**自己的一张表** `GS_BB_BARS`（不碰蚀月的 `GS_EC_BARS`）：
//     同一玩家身上可以同时挂多条 bossbar（叠着显示），复用会把蚀月那条顶掉。
//   ★ 标题一律**纯字符串**（§6 颜色码 + 「运球」），**绝不能塞 Adventure Component**
// —— 线上事故就是那么来的（会被 toString 成 `TextImpl{…}` 一长串 dump）。
//   ★ 原版 BarColor 没有橙色 ⇒ ORANGE → GOLD → YELLOW 依次试，落到 YELLOW。
function gsBbBarColor() {
    try {
        var C = Java.type('org.bukkit.boss.BarColor');
        var names = ['ORANGE', 'GOLD', 'YELLOW'];
        for (var i = 0; i < names.length; i++) {
            try { var c = C[names[i]]; if (c != null) return c; } catch (e) { }
            try { var c2 = C.valueOf(names[i]); if (c2 != null) return c2; } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function gsBbBarStyle() {
    try {
        var S = Java.type('org.bukkit.boss.BarStyle');
        try { var s = S.SEGMENTED_12; if (s != null) return s; } catch (e) { }
        try { var s2 = S.valueOf('SOLID'); if (s2 != null) return s2; } catch (e) { }
    } catch (e) { }
    return null;
}

// 血条标题：**只有「运球」两个字**（§6 只是颜色码，不显示出来；不带任何数字）
function gsBbBarTitle() { return GS_BB_BAR_COLOR + GS_BB_BAR_TEXT; }

function gsBbBarOf(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_BB_BARS[u];
        if (rec != null && rec.b != null) return rec.b;
        var c = gsBbBarColor(), s = gsBbBarStyle();
        if (c == null || s == null) return null;
        var bar = null;
        try { bar = getServer().createBossBar(gsBbBarTitle(), c, s); } catch (e) { bar = null; }
        if (bar == null) return null;
        try { bar.setProgress(1.0); } catch (e) { }
        try { bar.setVisible(false); } catch (e) { }
        try { bar.addPlayer(p); } catch (e) { }
        GS_BB_BARS[u] = { b: bar, p: p, on: false };
        return bar;
    } catch (e) { return null; }
}

// 显示 / 刷新：prog = 剩余时间 / GS_BB_BIND_MS（10 秒 ⇒ 1.0，清零 ⇒ 0.0）
function gsBbBarShow(p, prog) {
    try {
        var b = gsBbBarOf(p);
        if (b == null) return;
        var v = (prog == null) ? 1.0 : (prog * 1.0);
        if (!(v > 0)) v = 0.0;
        if (v > 1.0) v = 1.0;
        gsBarTitleSet(b, gsBbBarTitle());
        try { b.setProgress(v); } catch (e) { }
        try { b.setVisible(true); } catch (e) { }
        var u = '' + p.getUniqueId();
        var rec = GS_BB_BARS[u];
        if (rec != null) rec.on = true;
    } catch (e) { }
}

// 隐藏（投篮投出 / 10 秒清零掉落 / 离开绑定 —— 血条本体留着复用）
function gsBbBarHide(p) {
    try {
        var u = '' + p.getUniqueId();
        var rec = GS_BB_BARS[u];
        if (rec == null || rec.b == null) return;
        if (!rec.on) return;                    // ★ 已经是隐藏态 ⇒ 不再反复调 Java
        rec.on = false;
        try { rec.b.setVisible(false); } catch (e) { }
    } catch (e) { }
}

// 彻底丢掉（脱下胸甲 / 离线 / 脚本重载 ⇒ 下次重新建一条干净的）
function gsBbBarDrop(p) { gsBarTblDrop(GS_BB_BARS, p); }

// ---------- 粒子：DUST（尘埃）★ **不再用烟花** ----------
//   ★ 换掉 FIREWORK 的原因（用户：「篮球有些大，可能是因为烟花粒子是外散导致的」）：
//     FIREWORK 粒子的**精灵本身就大**（≈0.4 格）还带外散 ⇒ 球看起来比坐标上的球体大一圈。
//     DUST（旧名 REDSTONE）的颜色由 DustOptions 精确指定（橙 / 黑一分不差），
//     大小由 DustOptions 的 size 控制（GS_BB_DOT_SIZE）⇒ 球体尺寸**所见即所得**。
function gsBbSpellType() {
    // 极端兜底（连 DUST 都拿不到时）：着色的药水粒子 —— 同样**不是烟花**
    if (GS_BB_SPELL != null) return GS_BB_SPELL;
    if (GS_BB_SPELL_TRIED) return null;
    GS_BB_SPELL_TRIED = true;
    try {
        var P = Java.type('org.bukkit.Particle');
        var names = ['ENTITY_EFFECT', 'SPELL_MOB'];
        for (var i = 0; i < names.length; i++) {
            try { var t = P[names[i]]; if (t != null) { GS_BB_SPELL = t; return t; } } catch (e) { }
            try { var t2 = P.valueOf(names[i]); if (t2 != null) { GS_BB_SPELL = t2; return t2; } } catch (e) { }
        }
    } catch (e) { }
    return null;
}

function gsBbSpellSpark(world, x, y, z, rgb) {
    try {
        var pt = gsBbSpellType();
        if (pt == null || world == null) return;
        if (GS_BB_FEC == null) GS_BB_FEC = Java.type('org.bukkit.Color');
        var col = GS_BB_FEC.fromRGB((rgb[0] | 0) & 255, (rgb[1] | 0) & 255, (rgb[2] | 0) & 255);
        world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, col);
    } catch (e) { }
}

// 一个「橙 / 黑」尘埃粒子点（颜色与原烟花版**完全一致**）
function gsBbSpark(world, x, y, z, rgb) {
    try {
        if (world == null) return;
        var pt = gsMsDustType();
        if (pt == null) { gsBbSpellSpark(world, x, y, z, rgb); return; }
        var d = gsMsDustOpts(rgb[0], rgb[1], rgb[2], GS_BB_DOT_SIZE);
        gsMsDust(world, pt, d, x, y, z);
        if (d == null) gsBbSpellSpark(world, x, y, z, rgb);   // DustOptions 建不出来 ⇒ 走兜底
    } catch (e) { }
}

// ==========================================================================
// ★★ 篮球的「展示实体」渲染
//   用 ItemDisplay 显示一个**带头颅皮肤的 head 物品**：
//     · 头颅模型本身就是 **0.5 格立方**，与原来的球（直径 0.48 格）**大小几乎一样**
//     · 每刻只发 **1 个实体位置包**（旧方案每刻 26 个粒子包）⇒ 低延迟、低带宽
//     · 实体由客户端渲染，走的是和玩家/怪物同一条通道 ⇒ 没有粒子那种"稀稀拉拉"的观感
//   ★ 全程**多层降级**：任何一步拿不到就往下试，最后退回旧的尘埃粒子球 —— 绝不因为
//     版本差异把篮球整个搞没。
// ==========================================================================

// ---------- ① 造那颗「带头颅皮肤」的物品（只造一次，之后用缓存）----------
function gsBbHeadItem() {
    if (GS_BB_HEAD_ITEM != null) return GS_BB_HEAD_ITEM;
    try {
        var Material = Java.type('org.bukkit.Material');
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        var mat = null;
        try { mat = Material.PLAYER_HEAD; } catch (e) { mat = null; }
        if (mat == null) { try { mat = Material.valueOf('PLAYER_HEAD'); } catch (e) { mat = null; } }
        if (mat == null) return null;

        var st = new ItemStack(mat, 1);
        var meta = null;
        try { meta = st.getItemMeta(); } catch (e) { meta = null; }

        var applied = false;
        if (meta != null) {
            // 路 A：**Paper** 的 profile API（`com.destroystokyo.paper.profile`）
            applied = gsBbApplyProfilePaper(meta);
            // 路 B：**Bukkit** 1.18+ 的 PlayerProfile + PlayerTextures
            if (!applied) applied = gsBbApplyProfileBukkit(meta);
            try { st.setItemMeta(meta); } catch (e) { }
        }
        // 皮肤没设上也不放弃：至少还是个头颅（默认皮肤），总比没有好
        GS_BB_HEAD_ITEM = st;
        return st;
    } catch (e) { return null; }
}

// 路 A：Paper —— createProfile(UUID, name) + setProperty(ProfileProperty)
function gsBbApplyProfilePaper(meta) {
    try {
        var Bukkit = Java.type('org.bukkit.Bukkit');
        var UUID = Java.type('java.util.UUID');
        var PP = Java.type('com.destroystokyo.paper.profile.ProfileProperty');
        var u = gsBbHeadUuid();
        // ★ 多重载消歧义：走**精确签名的反射**，不直接调 createProfile（GraalJS 的多重载坑）
        var prof = gsBbCallStatic('org.bukkit.Bukkit', 'createProfile',
                                  ['java.util.UUID', 'java.lang.String'], [u, GS_BB_HEAD_NAME]);
        if (prof == null) prof = gsBbCallStatic('org.bukkit.Bukkit', 'createProfile',
                                                ['java.util.UUID'], [u]);
        if (prof == null) return false;
        try { prof.setProperty(new PP('textures', GS_BB_HEAD_TEX)); } catch (e) { return false; }
        try { meta.setPlayerProfile(prof); return true; } catch (e) { }
        try { meta.setOwnerProfile(prof); return true; } catch (e) { }
    } catch (e) { }
    return false;
}

// 路 B：Bukkit 1.18+ —— createPlayerProfile(UUID) + getTextures().setSkin(URL)
function gsBbApplyProfileBukkit(meta) {
    try {
        var URL = Java.type('java.net.URL');
        var u = gsBbHeadUuid();
        var prof = gsBbCallStatic('org.bukkit.Bukkit', 'createPlayerProfile',
                                  ['java.util.UUID'], [u]);
        if (prof == null) return false;
        try {
            var tex = prof.getTextures();
            if (tex != null) tex.setSkin(new URL(GS_BB_HEAD_URL));   // ★ 只本地设皮肤，**不调 update()**（不触网）
        } catch (e) { return false; }
        try { meta.setOwnerProfile(prof); return true; } catch (e) { }
        try { meta.setPlayerProfile(prof); return true; } catch (e) { }
    } catch (e) { }
    return false;
}

// 固定 UUID（= 原物品 lq.yml 里那四个 int，事先算好写成常量）
//   ★ 固定住的好处：客户端会把这份皮肤**缓存复用**，不会每颗球都重新拉一次贴图。
//   ★ 用 `UUID.fromString(String)`（**单重载**，和 NamespacedKey.fromString 一个路子），
//     避免 `new UUID(long,long)` 那种 64 位运算在 GraalJS 里的精度/重载坑。
function gsBbHeadUuid() {
    try { return Java.type('java.util.UUID').fromString(GS_BB_HEAD_UUID); }
    catch (e) {
        try { return Java.type('java.util.UUID').randomUUID(); } catch (e2) { return null; }
    }
}

// ★★ 反射调用静态方法：**绕开 GraalJS「多重载方法无法消歧义」这个坑**
//   （本项目已在 NamespacedKey 上踩过一次：`new NS(plugin,name)` 直接抛异常）
function gsBbCallStatic(clsName, method, argTypeNames, args) {
    try {
        var Class = Java.type('java.lang.Class');
        var cls = Class.forName(clsName);
        var types = [];
        for (var i = 0; i < argTypeNames.length; i++) types.push(Class.forName(argTypeNames[i]));
        var m = cls.getMethod(method, Java.to(types, 'java.lang.Class[]'));
        return m.invoke(null, Java.to(args, 'java.lang.Object[]'));
    } catch (e) { return null; }
}

// ---------- ② 生成 / 同步 / 移除展示实体 ----------
function gsBbEntSpawn(st) {
    try {
        if (GS_BB_ENT_OK === false) return null;              // 已知造不出来 ⇒ 别再试
        var item = gsBbHeadItem();
        if (item == null) { GS_BB_ENT_OK = false; return null; }

        var ET = null;
        try { ET = Java.type('org.bukkit.entity.EntityType'); } catch (e) { ET = null; }
        if (ET == null) { GS_BB_ENT_OK = false; return null; }
        var type = null;
        try { type = ET.ITEM_DISPLAY; } catch (e) { type = null; }
        if (type == null) { try { type = ET.valueOf('ITEM_DISPLAY'); } catch (e) { type = null; } }
        if (type == null) { GS_BB_ENT_OK = false; return null; }   // 版本太老（< 1.19.4）⇒ 退回粒子

        var loc = gsMsLoc(st.w, st.x, st.y, st.z);
        if (loc == null) { GS_BB_ENT_OK = false; return null; }

        var ent = null;
        try { ent = st.w.spawnEntity(loc, type); } catch (e) { ent = null; }   // ★ 单重载，安全
        if (ent == null) { GS_BB_ENT_OK = false; return null; }

        try { ent.setItemStack(item); } catch (e) { }
        // 贴地不飘：不要重力、不无敌闪红、不被保存（防重启后残留一地篮球）
        try { ent.setGravity(false); } catch (e) { }
        try { ent.setInvulnerable(true); } catch (e) { }
        try { ent.setPersistent(false); } catch (e) { }
        try { ent.setSilent(true); } catch (e) { }
 // ★★ 顺手打上「这是我们的球」的标记（实体 PDC 会跟着实体一起写盘）
        //   ⇒ 万一它真被存进区块存档，下次启动也能一眼认出来删掉（见 gsEntPurge）。
        try {
            if (GS_ENT_TAG != null && GS_BB_PDT != null)
                ent.getPersistentDataContainer().set(GS_ENT_TAG, GS_BB_PDT.INTEGER, 1);
        } catch (e) { }
        // 渲染成"戴在头上"的居中样式（头颅方块正好 0.5 格，与原来的球同尺寸）
        try {
            var T = null;
            try { T = Java.type('org.bukkit.entity.ItemDisplay$ItemDisplayTransform'); } catch (e) { T = null; }
            if (T == null) { try { T = Java.type('org.bukkit.entity.ItemDisplay.ItemDisplayTransform'); } catch (e) { T = null; } }
            if (T != null) {
                try { ent.setItemDisplayTransform(T.HEAD); }
                catch (e) { try { ent.setItemDisplayTransform(T.valueOf('HEAD')); } catch (e2) { } }
            }
        } catch (e) { }
        // ★ 低延迟：客户端插值（Paper 的 Display 扩展方法；拿不到就算了，不影响功能）
        if (GS_BB_ENT_TPDUR > 0) { try { ent.setTeleportDuration(GS_BB_ENT_TPDUR); } catch (e) { } }
        if (GS_BB_ENT_INTDUR > 0) {
            try { ent.setInterpolationDelay(0); } catch (e) { }
            try { ent.setInterpolationDuration(GS_BB_ENT_INTDUR); } catch (e) { }
        }
        if (GS_BB_ENT_SHADOW > 0) {
            try { ent.setShadowRadius(0.3); } catch (e) { }
            try { ent.setShadowStrength(GS_BB_ENT_SHADOW); } catch (e) { }
        }
        GS_BB_ENT_OK = true;
        return ent;
    } catch (e) { GS_BB_ENT_OK = false; return null; }
}

// 每刻把实体挪到球的坐标（yaw 用累加的自转角 ⇒ 看起来像篮球在转）
//   返回 true = "已经用实体渲染了"；false = "没实体，请调用方退回粒子"
function gsBbEntSync(st) {
    try {
        if (st == null || st.w == null) return false;
        if (GS_BB_ENT_OK === false) return false;

        var ent = st.ent;
        if (ent != null) {
            var alive = true;
            try { alive = ent.isValid(); } catch (e) { alive = true; }   // 问不出来就当还活着
            if (!alive) { st.ent = null; ent = null; }                   // 区块卸载 / 被清 ⇒ 重建
        }
        if (ent == null) {
            ent = gsBbEntSpawn(st);
            if (ent == null) return false;
            st.ent = ent;
            st.entTick = 0;
        }

        // ★ 节流：每 GS_BB_ENT_EVERY 刻同步一次（默认 1 = 每刻；调大要配合 GS_BB_ENT_TPDUR 插值）
        st.entTick = (st.entTick | 0) + 1;
        if (GS_BB_ENT_EVERY > 1 && (st.entTick % GS_BB_ENT_EVERY) !== 0) return true;

        var yaw = (st.spin || 0) * 57.29577951308232;    // 弧度 → 度
        yaw = yaw % 360.0;
        var loc = gsMsLoc(st.w, st.x, st.y, st.z);
        if (loc != null) {
            try { loc.setYaw(yaw); } catch (e) { }
            try { loc.setPitch(0.0); } catch (e) { }
            try { ent.teleport(loc); } catch (e) { }
        }
        return true;
    } catch (e) { return false; }
}

// 移除实体（脱下 / 离线 / 状态清空 / 脚本重载都要调，不然会留下一地"幽灵篮球"）
function gsBbEntDrop(st) {
    try {
        if (st == null) return;
        var ent = st.ent;
        st.ent = null;
        if (ent == null) return;
        try { ent.remove(); } catch (e) { }
        try { if (ent.isValid()) ent.remove(); } catch (e) { }
    } catch (e) { }
}

// ==========================================================================
// ★★ 展示实体「孤儿球」清扫（GS_ENT_*）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §13
// ==========================================================================
var GS_ENT_PURGE_EVERY = 20;      // 周期清扫间隔（刻）：20 刻 = 1 秒
var GS_ENT_PURGE_ON    = true;    // 总开关（万一哪天觉得太勤，改成 false 即可）
var GS_ENT_BOOT_N      = 0;       // 启动那一次清掉了几个（启动日志 / 自检用）
var GS_ENT_LAST_N      = 0;       // 最近一次周期清扫清掉了几个
var GS_ENT_DIS_OK      = null;    // 关服前清扫那条监听器注册上没有

// 实体的 UUID（拿不到就 null ⇒ 当成"没有身份"，不保护）
function gsEntUuidOf(e) {
    try { var u = e.getUniqueId(); return (u == null) ? null : ('' + u); } catch (e2) { return null; }
}

// 当前**正在被脚本管理**的球实体 UUID 表（这些不能删）
function gsEntLiveSet() {
    var s = {};
    try {
        for (var k in GS_BB_ST) {
            var st = GS_BB_ST[k];
            if (st == null || st.ent == null) continue;
            var u = gsEntUuidOf(st.ent);
            if (u != null) s[u] = 1;
        }
    } catch (e) { }
    try {
        for (var k2 in GS_BG_NODES) {
            var nd = GS_BG_NODES[k2];
            if (nd == null || nd.balls == null) continue;
            for (var i = 0; i < nd.balls.length; i++) {
                var b = nd.balls[i];
                if (b == null || b.ent == null) continue;
                var u2 = gsEntUuidOf(b.ent);
                if (u2 != null) s[u2] = 1;
            }
        }
    } catch (e) { }
    return s;
}

// 这颗实体是不是"我们的球" —— ① 身上有我们的标记 ② 就是那颗专属头颅
function gsEntIsOurs(e) {
    try {
        if (e == null) return false;
        var tn = '';
        try { tn = '' + e.getType().name(); }
        catch (e1) { try { tn = '' + e.getType(); } catch (e2) { tn = ''; } }
        // ★ 只处理展示实体这一族（ItemDisplay / BlockDisplay / TextDisplay…）
        if (tn.indexOf('DISPLAY') < 0) return false;
        // ① 我们自己打的标记
        try {
            if (GS_ENT_TAG != null && GS_BB_PDT != null) {
                var v = e.getPersistentDataContainer().get(GS_ENT_TAG, GS_BB_PDT.INTEGER);
                if (v != null) return true;
            }
        } catch (e3) { }
        // ② 退路：**老残留没有标记**（本批之前造的孤儿）⇒ 靠那颗专属头颅认
        var it = null;
        try { it = e.getItemStack(); } catch (e4) { it = null; }
        return gsEntItemIsOurs(it);
    } catch (e) { return false; }
}

// 这件物品是不是我们那颗"篮球头颅"（严格：只认 PLAYER_HEAD + profile UUID 相同）
function gsEntItemIsOurs(item) {
    try {
        if (item == null) return false;
        var tn = '';
        try { tn = '' + item.getType().name(); } catch (e0) { return false; }
        if (tn.indexOf('PLAYER_HEAD') < 0 && tn.indexOf('SKULL') < 0) return false;
        var meta = null;
        try { meta = item.getItemMeta(); } catch (e1) { meta = null; }
        if (meta == null) return false;
        var id = null;
        try { var pr = meta.getPlayerProfile(); if (pr != null) id = pr.getId(); } catch (e2) { }
        if (id == null) { try { var op = meta.getOwnerProfile(); if (op != null) id = op.getId(); } catch (e3) { } }
        if (id != null && ('' + id).toLowerCase() === ('' + GS_BB_HEAD_UUID).toLowerCase()) return true;
        // ★ profile 读不出来时再退一步：整件物品和我们那颗头颅"几乎相同"才算
        //   （同样的材质 + 同样的皮肤 ⇒ 实际只可能是我们自己的球）
        var our = gsBbHeadItem();
        if (our != null) { try { if (item.isSimilar(our)) return true; } catch (e4) { } }
        return false;
    } catch (e) { return false; }
}

// 取一个世界里能拿到的展示实体集合（优先 getEntitiesByClass，取不到再退回 getEntities）
function gsEntListIn(w) {
    try {
        try {
            var ID = Java.type('org.bukkit.entity.ItemDisplay');
            var c = w.getEntitiesByClass(ID);
            if (c != null) return c;
        } catch (e) { }
        return w.getEntities();
    } catch (e) { return null; }
}

// ★ 清扫。force = true ⇒ 全删（启动 / 关服）；false ⇒ 只删"不在状态表里"的孤儿
function gsEntPurge(force) {
    var n = 0;
    try {
        if (!GS_ENT_PURGE_ON) return 0;
        var live = force ? null : gsEntLiveSet();
        // 世界列表（iterator / size+get 两种都试，免得某个版本只有一种）
        var wl = [];
        try {
            var worlds = getServer().getWorlds();
            try {
                var itw = worlds.iterator();
                while (itw.hasNext()) wl.push(itw.next());
            } catch (e1) {
                var wn = worlds.size() | 0;
                for (var wi = 0; wi < wn; wi++) wl.push(worlds.get(wi));
            }
        } catch (e0) { wl = []; }
        for (var i = 0; i < wl.length; i++) {
            var w = wl[i];
            if (w == null) continue;
            var list = gsEntListIn(w);
            if (list == null) continue;
            // 先拷成数组再删（"边遍历边删"在真机上会漏项 / 抛异常）
            var arr = [];
            try { var it = list.iterator(); while (it.hasNext()) arr.push(it.next()); }
            catch (e2) { continue; }
            for (var j = 0; j < arr.length; j++) {
                var e = arr[j];
                if (!gsEntIsOurs(e)) continue;
                if (!force && live != null) {
                    var u = gsEntUuidOf(e);
                    if (u != null && live[u] === 1) continue;      // 正在用的 ⇒ 放过
                }
                try { e.remove(); n++; } catch (e5) { }
            }
        }
    } catch (e) { }
    return n;
}

// 周期清扫（挂在心跳上）
function gsEntPulse() {
    try { GS_ENT_LAST_N = gsEntPurge(false); } catch (e) { }
}

function gsEntPurgeStart() {
    try {
        // ① 启动清扫：这一刻内存里没有任何合法球 ⇒ 全删（各模块随后自己重建）
        GS_ENT_BOOT_N = gsEntPurge(true);
        // ② 周期清扫
        runRepeating(gsEntPulse, GS_ENT_PURGE_EVERY, GS_ENT_PURGE_EVERY);
        // ③ 关服 / reload 前再全删一遍（宿主插件被禁用 = 我们要下线了）
        try {
            var pm = getServer().getPluginManager();
            var Priority = Java.type('org.bukkit.event.EventPriority');
            var plugin = null;
            try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
            if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
            if (plugin != null) {
                var PDEv = Java.type('org.bukkit.event.server.PluginDisableEvent');
                var handler = function (e) {
                    try {
                        var pl = null;
                        try { pl = e.getPlugin(); } catch (e1) { }
                        if (pl != null && pl !== plugin) return;     // 只管"我们自己"下线那一刻
                        gsEntPurge(true);
                    } catch (e2) { }
                };
                var ok = false;
                try {
                    pm.registerEvent(PDEv, plugin, Priority.MONITOR,
                        function (l, e) { handler(e); }, plugin);
                    ok = true;
                } catch (e3) {
                    try {
                        var ListenerC = Java.type('org.bukkit.event.Listener');
                        var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                        var listener = new JavaAdapter(ListenerC, {});
                        var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                        pm.registerEvent(PDEv, listener, Priority.MONITOR, exec, plugin);
                        ok = true;
                    } catch (e4) { }
                }
                GS_ENT_DIS_OK = ok;
            }
        } catch (e) { }
        try {
            getServer().getLogger().info('[构思技艺] 展示实体「孤儿球」清扫已加载 · 启动清了 '
                + GS_ENT_BOOT_N + ' 个残留 / 之后每 ' + (GS_ENT_PURGE_EVERY / 20.0)
                + ' 秒扫一次孤儿 / 关服前再全清一遍（关服清扫监听 = '
                + (GS_ENT_DIS_OK === true ? '已注册' : '没注册上（不影响，启动时照样会清）') + '）'
                + ' / ★ 认球只认「我们的标记 + 专属头颅」⇒ 别人的展示实体一律不碰');
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 孤儿球清扫启动异常: ' + e); } catch (e2) { }
    }
}

// ---------- 方块 / 地面 ----------
function gsBbBlockAt(w, x, y, z) {
    try {
        if (w == null) return null;
        var fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        try { var b = w.getBlockAt(fx, fy, fz); if (b != null) return b; } catch (e) { }
        try {
            var loc = gsMsLoc(w, fx, fy, fz);
            if (loc != null) return loc.getBlock();
        } catch (e) { }
    } catch (e) { }
    return null;
}

// ★★ **玻璃板（GLASS_PANE）这类"薄方块"不能只信 isPassable()**
//   玻璃板的碰撞箱是**中心十字**（孤立时只有 2×2 像素），属于"薄方块"；
//   各版本 / 各服务端对它的 `isPassable()` 返回值并不一致，一旦被判成"可通行"，
//   球就会**直接穿过去**（用户实测到的现象）。
//   ⇒ 所以在 isPassable 说"能过"的时候，再按**材质名**兜一层明确清单。
var GS_BB_SOLID_KEYS  = ['PANE', 'BARS', 'CHAIN', 'FENCE', 'WALL', 'SLAB', 'STAIRS',
                         'DOOR', 'TRAPDOOR', 'GLASS'];
//   命中上面关键词、但**不该挡球**的例外（墙上的告示牌 / 火把 / 旗帜是装饰，不是墙）
var GS_BB_SOFT_EXCEPT = ['SIGN', 'TORCH', 'BANNER', 'HANGING', 'CORAL', 'FAN'];

// 按材质名判断：命中 ⇒ true（挡球）；没命中 ⇒ false（没意见，以 isPassable 为准）
function gsBbNameSolid(block) {
    try {
        if (block == null) return false;
        var t = block.getType();
        if (t == null) return false;
        var n = '' + t.name();
        if (n === 'null' || n.length === 0) return false;
        var up = n.toUpperCase();
        for (var i = 0; i < GS_BB_SOFT_EXCEPT.length; i++) {
            if (up.indexOf(GS_BB_SOFT_EXCEPT[i]) >= 0) return false;   // 例外先排除
        }
        for (var k = 0; k < GS_BB_SOLID_KEYS.length; k++) {
            if (up.indexOf(GS_BB_SOLID_KEYS[k]) >= 0) return true;
        }
    } catch (e) { }
    return false;
}

// 「能挡住球」的判定
// ★ 优先用 `isPassable()` —— 玻璃 / 树叶这种 **isSolid() 拿不到**的方块
//     （玻璃 `isPassable=false` 但 `Material#isSolid()` 返回 false）以前会被球直接穿过去；
//     `isPassable()` 拿不到（老版本 / 桩）才退回 `isSolid()`。
// ★★ isPassable 说"**能**过"时**不能就此放行** —— 玻璃板这类薄方块会被误判，
//     所以再调一次 gsBbNameSolid() 按材质名兜底（名单命中 ⇒ 照样算挡球）。
function gsBbSolid(block) {
    try {
        if (block == null) return false;
        try {
            var pb = block.isPassable();
            if (pb != null) { if (!pb) return true; if (gsBbNameSolid(block)) return true; return false; }
        } catch (e) { }
        var t = block.getType();
        if (t == null) return false;
        try {
            var pm = t.isPassable();
            if (pm != null) { if (!pm) return true; if (gsBbNameSolid(block)) return true; return false; }
        } catch (e) { }
        try { if (gsBbNameSolid(block)) return true; } catch (e) { }
        try { return t.isSolid(); } catch (e) { }
    } catch (e) { }
    return false;
}

// 从 (x, yTop, z) 向下找最近固体方块的**顶面高度**；找不到返回 null
function gsBbFloor(w, x, yTop, z, maxDown) {
    try {
        var y0 = Math.floor(yTop);
        var lim = (maxDown == null) ? 16 : maxDown;
        for (var i = 0; i <= lim; i++) {
            if (gsBbSolid(gsBbBlockAt(w, x, y0 - i, z))) return (y0 - i) + 1.0;
        }
    } catch (e) { }
    return null;
}

// ★★ **防隧穿**的地面检测（修"撞墙后穿地板"）
//   老写法：从"**这一刻**的球底 (ny - R)"往下扫 ⇒ 球一步跨过整块地板时
//     （撞墙后长时间垂直下坠，vy 累积到 1.5~2.5 格/刻很常见），
//     扫描起点已经落在**地板的下面** ⇒ 往下扫再也扫不到这块地板 ⇒ 返回 null
//     ⇒ 落地条件不成立 ⇒ 球继续往下掉 ⇒ **直接穿过地板**。
//   正解：从"**上一刻**的球底 (yPrev - R)"往下扫 —— 那一刻球还在空中，
//     一定能扫到这块地板；再配合"这一刻球底已经低于地板顶面"就判定落地 ⇒ 跨再多格也穿不过去。
function gsBbFloorSweep(w, x, yPrev, yNow, z, r) {
    try {
        var hi = Math.floor(yPrev - r);          // 上一刻球底所在格
        var lo = Math.floor(yNow - r) - 2;       // 这一刻球底再往下留 2 格余量
        if (lo > hi) lo = hi;                    // 往上飞时不做向下的扫掠
        var lim = 48;                            // 最多扫 48 格，别把整列方块扫一遍
        for (var y = hi; y >= lo && (hi - y) < lim; y--) {
            if (gsBbSolid(gsBbBlockAt(w, x, y, z))) return y + 1.0;   // 地板顶面
        }
    } catch (e) { }
    return null;
}

// ★★ 水平**扫掠**撞墙检测（老写法只测目标点那一个格）
//   球最快 1.25 格/刻 ⇒ 一格厚的薄墙可能被一步跨过去（目标点在墙后就检测不到）。
//   这里从旧位置到目标位置**分段取样**（默认 4 段），只要中间撞到就算撞墙。
function gsBbWallSweep(w, x0, z0, x1, z1, y, steps) {
    try {
        var n = (steps == null) ? 4 : steps;
        for (var i = 1; i <= n; i++) {
            var t = i / n;
            if (gsBbSolid(gsBbBlockAt(w, x0 + (x1 - x0) * t, y, z0 + (z1 - z0) * t))) return true;
        }
    } catch (e) { }
    return false;
}

function gsBbDistH(ax, az, bx, bz) {
    var dx = ax - bx, dz = az - bz;
    return Math.sqrt(dx * dx + dz * dz);
}

// ---------- 球体绘制 ----------
function gsBbDraw(st) {
    try {
        if (st == null || st.w == null) return;
 // ★★ 优先用**展示实体**（每刻只发 1 个实体包 ⇒ 低延迟）
        if (GS_BB_USE_ENT) {
            if (gsBbEntSync(st)) return;      // true = 已用实体渲染，不用再画粒子
        }
        var n = GS_BB_DOTS, r = GS_BB_R;
        var sp = st.spin || 0;
        var cs = Math.cos(sp), sn = Math.sin(sp);
        var GA = 2.3999632297286531;                 // 黄金角（球面均匀分布）
        for (var i = 0; i < n; i++) {
            var y0 = 1.0 - (i + 0.5) * 2.0 / n;
            var rr = Math.sqrt(Math.max(0.0, 1.0 - y0 * y0));
            var th = i * GA;
            var px = Math.cos(th) * rr, pz = Math.sin(th) * rr;
            var rx = px * cs - pz * sn, rz = px * sn + pz * cs;   // 绕 Y 轴自转
            var rgb = (i % 2 === 0) ? GS_BB_ORANGE : GS_BB_BLACK;
            gsBbSpark(st.w, st.x + rx * r, st.y + y0 * r, st.z + rz * r, rgb);
        }
    } catch (e) { }
}

// ---------- 状态 ----------
function gsBbClear(u) {
    try {
        var st = (u != null) ? GS_BB_ST[u] : null;
 // ★★ 清状态之前**先把这个玩家的展示实体删掉**（不然会留下一地幽灵篮球）
        try { gsBbEntDrop(st); } catch (e) { }
        if (u != null) delete GS_BB_ST[u];
    } catch (e) { try { if (u != null) GS_BB_ST[u] = null; } catch (e2) { } }
}

// 把球放到"玩家前方 2 格、地面上方 0.9 格"
function gsBbSpawnPos(p, st) {
    try {
        var loc = p.getLocation();
        var w = p.getWorld();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { dir = null; }
        var fx = (dir != null) ? dir.getX() : 0.0;
        var fz = (dir != null) ? dir.getZ() : 1.0;
        var l = Math.sqrt(fx * fx + fz * fz);
        if (l < 1.0e-4) { fx = 0.0; fz = 1.0; l = 1.0; }
        fx /= l; fz /= l;

        var x = loc.getX() + fx * GS_BB_SPAWN_FWD;
        var z = loc.getZ() + fz * GS_BB_SPAWN_FWD;
        var gy = gsBbFloor(w, x, loc.getY() + 1.0, z, 24);
        if (gy == null) gy = loc.getY();

        st.w = w;
        st.x = x; st.z = z;
        st.y = gy + GS_BB_R + GS_BB_SPAWN_UP;
    } catch (e) { }
}

function gsBbNew(p) {
    var st = { mode: 'idle', w: null, x: 0, y: 0, z: 0,
               vx: 0, vy: 0, vz: 0, t0: 0, until: 0, spin: 0,
               life: 0, shotAt: 0, touched: 0, heldBall: false,
 // ★ 落地弹跳。bnc = 还剩几下没弹；hitWall = 本次飞行撞没撞过墙
               bnc: 0, hitWall: false,
 // ★ 展示实体（低延迟渲染）。ent = 实体；entTick = 节流计数
               ent: null, entTick: 0,
 // ★ 这次"起飞"时的高度（找不到地面时的兜底落点，别卡在地底）
               sy0: 0 };
    gsBbSpawnPos(p, st);
    return st;
}

// 玩家"身体轴"是否碰到球
function gsBbTouch(p, st) {
    try {
        var loc = p.getLocation();
        if (gsBbDistH(st.x, st.z, loc.getX(), loc.getZ()) > GS_BB_TOUCH) return false;
        var dy = st.y - (loc.getY() + 0.9);
        return (dy > -GS_BB_TOUCH_V && dy < GS_BB_TOUCH_V);
    } catch (e) { return false; }
}

function gsBbBind(p, st) {
    var now = gsMsNow();
    st.mode = 'bound';
    st.t0 = now;
    st.until = now + GS_BB_BIND_MS;
    st.vx = 0.0; st.vy = 0.0; st.vz = 0.0;
 // ★★ 绑定当刻就把「篮球」放进副手（副手空着才放）
    //    ⇒ 主手空着对空气右键时客户端**也会发包** ⇒ 右键真的能投出去
    gsBbHandOn(p, st);
 // ★★ 绑定当刻 ⇒ **运球血条出现**（满条 = 还剩整整 10 秒）
    gsBbBarShow(p, 1.0);
    try { gsMsSoundAt(p, 'entity.item.pickup', 0.9, 1.4); } catch (e) { }
    // ★ 绑定**当刻**就把球摆到「右手」位置，别让它停在生成点等下一刻
    gsBbTickBound(p, st);
}

// ---------- 投篮（★ 方向 = 准星三维方向）----------
// ★★ 抽出通用的 `gsBbShootState(p, st)` ——
//   律师函（gsBb*）与篮球生成器（gsBg*）**共用同一套投篮物理**，
//   只有"是不是律师函那颗球"才需要处理副手球 / 运球血条。
function gsBbShoot(p) {
    return gsBbShootState(p, (p != null) ? GS_BB_ST['' + p.getUniqueId()] : null);
}

// 这个球是不是"律师函那颗"（决定要不要动副手球和血条）
function gsBbIsLawyerBall(p, st) {
    try {
        if (p == null || st == null) return false;
        return GS_BB_ST['' + p.getUniqueId()] === st;
    } catch (e) { return false; }
}

function gsBbShootState(p, st) {
    try {
        if (p == null) return false;
        var u = '' + p.getUniqueId();
        if (st == null) st = GS_BB_ST[u];
        if (st == null || st.mode !== 'bound') return false;
        var now = gsMsNow();
        if ((now - (st.shotAt | 0)) < GS_BB_SHOT_CD) return false;
        st.shotAt = now;

        var loc = p.getLocation();
 // ★★ 取**完整的三维准星向量**（含俯仰角 pitch），而不是只取水平分量
        //      —— 这才是"指哪打哪"：平视 = 平传 / 朝下 = 拍到脚下 / 朝上 = 高抛。
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { dir = null; }
        var dx = (dir != null) ? dir.getX() : 0.0;
        var dy = (dir != null) ? dir.getY() : 0.0;
        var dz = (dir != null) ? dir.getZ() : 1.0;
        var L = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (L < 1.0e-6) { dx = 0.0; dy = 0.0; dz = 1.0; L = 1.0; }
        dx /= L; dy /= L; dz /= L;

        // 出手点：只沿**水平**分量前移 GS_BB_SHOT_FWD 格，高度恒为"右手高度"
        //   —— 俯仰角不该把出手点拉到脚底或头顶；正上/正下看时水平位移为 0（球就在身前正上/正下方出手）。
        var hl = Math.sqrt(dx * dx + dz * dz);
        var hx = (hl > 1.0e-4) ? (dx / hl) : 0.0;
        var hz = (hl > 1.0e-4) ? (dz / hl) : 0.0;

        st.w = p.getWorld();
        st.x = loc.getX() + hx * GS_BB_SHOT_FWD;
        st.z = loc.getZ() + hz * GS_BB_SHOT_FWD;
        st.y = loc.getY() + GS_BB_HAND_UP;

 // ★★ 初速 = **准星三维方向 × 速度**（不再是"水平 + 固定竖直斜角"）
        st.vx = dx * GS_BB_SHOT_SPD;
        st.vy = dy * GS_BB_SHOT_SPD;
        st.vz = dz * GS_BB_SHOT_SPD;
        st.mode = 'shot';
        st.life = 0;
 // ★★ 这次投出去之后，**落地还要再弹 GS_BB_BOUNCES 下**；
        //     撞墙标记先清零（本次飞行中途撞到墙时才会置 true ⇒ 落地改成"原地弹"）
        st.bnc = GS_BB_BOUNCES;
        st.hitWall = false;
        st.sy0 = st.y;                          // ★ 第二十九批：记下出手高度（兜底落点）

 // ★★ 球飞出去了 ⇒ **副手那颗「篮球」立刻收走**（它只是"开门钥匙"）
 // ★★ 球已经投出去了 ⇒ **运球血条立刻收起**（运球结束）
 // ★ 这两样**只有律师函那颗球才有**（生成器球没有血条 / 用另一颗副手球）
        if (gsBbIsLawyerBall(p, st)) {
            gsBbHandOff(p, st);
            gsBbBarHide(p);
        }

        try { gsMsSound(st.w, loc, 'entity.arrow.shoot', 1.0, 1.2); } catch (e) { }
        return true;
    } catch (e) { return false; }
}

// 10 秒没投出去（运球血条清零）⇒ 掉在附近 2 格的地上
function gsBbDropNear(p, st) {
    try {
 // ★★ 超时没投 ⇒ 球掉地上，**副手那颗「篮球」同样收走**
        gsBbHandOff(p, st);
 // ★★ 运球血条**清零的同时消失**
        gsBbBarHide(p);
        var loc = p.getLocation();
        var ang = Math.random() * Math.PI * 2.0;
        var x = loc.getX() + Math.cos(ang) * GS_BB_DROP_R;
        var z = loc.getZ() + Math.sin(ang) * GS_BB_DROP_R;
        st.w = p.getWorld();
        st.x = x; st.z = z;
        if (st.y < loc.getY() + 0.8) st.y = loc.getY() + 0.8;   // 至少从腰高处往下掉
        st.vx = 0.0; st.vz = 0.0;
        st.vy = -0.12;
        st.mode = 'shot';
        st.life = 0;
        st.sy0 = st.y;                          // ★ 第二十九批：同样记下"起落高度"
    } catch (e) { }
}

function gsBbRest(st, floorY) {
    st.mode = 'rest';
    st.y = floorY + GS_BB_R;
    st.vx = 0.0; st.vy = 0.0; st.vz = 0.0;
    st.bnc = 0;                                // ★ 弹跳用完了（再投出去时会重新给）
    st.touched = gsMsNow();
}

// ---------- ★★ 投出后**落地不立刻停，先弹两下** ----------
//   还剩弹跳次数 ⇒ 弹一下：竖直速度给 GS_BB_BOUNCE_UP（逐次 × GS_BB_BOUNCE_DECAY 衰减），
//   水平速度**只保留一点点**（× GS_BB_BOUNCE_KEEP，且不超过 GS_BB_BOUNCE_MAX）：
//     · 没撞过墙 ⇒ 保留 ⇒ **顺着投出的方向往前弹两下**再停（像真篮球滚两步）
//     · 撞过墙   ⇒ 水平速度本来就是 0 ⇒ **原地弹两下**再停
//   弹完 GS_BB_BOUNCES 下 ⇒ gsBbRest() 真正停住。
//   ★ 弹起后仍然是 shot 模式（继续走重力 + 撞墙检测），所以第二下会自然再落一次。
function gsBbLand(st, floorY) {
    var nb = st.bnc | 0;
    if (nb <= 0 || GS_BB_BOUNCES <= 0) { gsBbRest(st, floorY); return; }

    st.bnc = nb - 1;
    st.y = floorY + GS_BB_R;                   // 贴着地面弹起，别陷进去

    // 第几次弹（0 = 第一次）⇒ 弹起高度逐次衰减
    var dec = 1.0;
    for (var i = 0; i < (GS_BB_BOUNCES - nb); i++) dec = dec * GS_BB_BOUNCE_DECAY;
    st.vy = GS_BB_BOUNCE_UP * dec;

    if (st.hitWall) {
        st.vx = 0.0; st.vz = 0.0;              // ★ 撞过墙 ⇒ 原地弹
    } else {
        st.vx = st.vx * GS_BB_BOUNCE_KEEP;     // ★ 没撞墙 ⇒ 往投出的方向再有一点点速度
        st.vz = st.vz * GS_BB_BOUNCE_KEEP;
        var sp = Math.sqrt(st.vx * st.vx + st.vz * st.vz);
        if (sp > GS_BB_BOUNCE_MAX && sp > 1.0e-9) {
            st.vx = st.vx / sp * GS_BB_BOUNCE_MAX;
            st.vz = st.vz / sp * GS_BB_BOUNCE_MAX;
        }
    }
    // ★ 弹跳阶段重新计时：一是别被 MAX_TICKS 兜底误杀，二是**必须过了直线段**
    //   （life <= GS_BB_STRAIGHT 时不受重力 ⇒ 弹起来会一路直冲上天）
    st.life = GS_BB_STRAIGHT + 1;
}

// ---------- 每刻：各状态 ----------
function gsBbTickIdle(p, st) {
    try {
        var loc = p.getLocation();
        st.w = p.getWorld();
        // 球被落下太远 ⇒ 重新放回玩家面前
        if (gsBbDistH(st.x, st.z, loc.getX(), loc.getZ()) > GS_BB_LEASH
            || Math.abs(st.y - loc.getY()) > 12.0) {
            gsBbSpawnPos(p, st);
        }
        // 别陷进地里
        var fl = gsBbFloor(st.w, st.x, st.y - GS_BB_R, st.z, 24);
        if (fl != null && (st.y - GS_BB_R) < fl) st.y = fl + GS_BB_R;
        // 走上去碰到 ⇒ 绑定
        if (gsBbTouch(p, st)) gsBbBind(p, st);
    } catch (e) { }
}

function gsBbTickBound(p, st) {
    try {
        var now = gsMsNow();
        var left = st.until - now;
        if (left <= 0) { gsBbDropNear(p, st); return; }   // 10 秒到（血条清零）⇒ 掉地上

 // ★★ 运球倒计时血条 —— 进度 = 剩余 / 总时长（10 秒）
        //   每刻都刷 ⇒ 条子肉眼可见地缩短；走到 0 的那一瞬，上面那行就会把它收掉。
        gsBbBarShow(p, (GS_BB_BIND_MS > 0) ? (left / GS_BB_BIND_MS) : 0);

        var loc = p.getLocation();
        var w = p.getWorld();
        st.w = w;

        // 右手方向 = 前向 (fx,fz) 的右向量 (-fz, 0, fx)
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { dir = null; }
        var fx = (dir != null) ? dir.getX() : 0.0;
        var fz = (dir != null) ? dir.getZ() : 1.0;
        var l = Math.sqrt(fx * fx + fz * fz);
        if (l < 1.0e-4) { fx = 0.0; fz = 1.0; l = 1.0; }
        fx /= l; fz /= l;
        var rx = -fz, rz = fx;

        var gy = gsBbFloor(w, loc.getX(), loc.getY(), loc.getZ(), 6);
        if (gy == null) gy = loc.getY();
        var loY = gy + GS_BB_R;
        var hiY = loc.getY() + GS_BB_HAND_UP;
        if (hiY < loY) hiY = loY;

        // 在「右手 ↔ 地面」之间来回跳动
        // ★ 用 |cos|：绑定那一刻 k=1 ⇒ 球正好被"接到右手上"，随后落向地面再弹回来
        var t = (now - st.t0) / 1000.0;
        var k = Math.abs(Math.cos(Math.PI * t / GS_BB_BOUNCE_S));
 // ★★ 横向偏移之外再**往前挪半个身位**（GS_BB_HAND_FWD = 0.5 格）
        //   ⇒ 球落在"身前偏右"的位置，看上去是**玩家在身前运球**，
        //     而不是球贴在身侧、玩家一走就"追着人跑"。
        st.x = loc.getX() + rx * GS_BB_HAND_SIDE + fx * GS_BB_HAND_FWD;
        st.z = loc.getZ() + rz * GS_BB_HAND_SIDE + fz * GS_BB_HAND_FWD;
        st.y = loY + k * (hiY - loY);
    } catch (e) { }
}

function gsBbTickShot(p, st) {
    try {
        st.life = (st.life | 0) + 1;
        var w = st.w;

 // ① 沿准星的初速积分（★ 出手后**先直线** GS_BB_STRAIGHT 刻，之后才受重力）
        //     直线段：平视投出去就是**笔直平飞**（= 传球），朝下投立刻往脚下走（= 假动作）；
        //     过了直线段才开始下坠 ⇒ 球一定会落地，朝上投也不会飞到天边。
        if (st.life > GS_BB_STRAIGHT && GS_BB_SHOT_GRAV > 0) st.vy = st.vy - GS_BB_SHOT_GRAV;
        st.vx = st.vx * GS_BB_DRAG;
        st.vz = st.vz * GS_BB_DRAG;

        var nx = st.x + st.vx;
        var ny = st.y + st.vy;
        var nz = st.z + st.vz;

        // ② 撞墙：水平目标位置有实体方块 ⇒ 水平速度清零、改垂直下坠
 // ★ 改成**扫掠检测**（gsBbWallSweep）—— 老写法只测目标点那一个格，
        //     一格厚的薄墙会被一步跨过去（球最快 1.25 格/刻）。
        var wall = false;
        if (Math.abs(st.vx) > 1.0e-6 || Math.abs(st.vz) > 1.0e-6) {
            if (gsBbWallSweep(w, st.x, st.z, nx, nz, st.y, GS_BB_SWEEP_N)) wall = true;
        }
        if (wall) {
            st.vx = 0.0; st.vz = 0.0;
 // ★ 记下"这次飞行撞过墙" ⇒ 落地后**原地弹**（不再往前滚）
            st.hitWall = true;
            // ★ 区分"还在首次飞行"和"已经在弹跳阶段"：
 // · 首次飞行撞墙 ⇒ 照旧**垂直下坠**（这是就有的行为，别改坏）
            //     · 弹跳阶段撞墙 ⇒ **只清水平速度**，别把 vy 打成下坠 ⇒ 否则弹跳被打断
            //   判定用"弹跳次数有没有被消耗过"，而不是 bnc > 0（后者在首次飞行中也成立）。
            var bouncing = (st.bnc | 0) < GS_BB_BOUNCES;
            if (!bouncing && st.vy > GS_BB_FALL_FAST) st.vy = GS_BB_FALL_FAST;
            nx = st.x; nz = st.z;
        }

 // ②' ★ 朝上投时**头顶撞到方块** ⇒ 上升速度清零、就地开始下落
        //      （改成"沿准星方向"之后朝上投变得很常见，不处理的话球会穿过天花板飞到屋顶上）
        if (st.vy > 1.0e-6 && gsBbSolid(gsBbBlockAt(w, nx, ny + GS_BB_R, nz))) {
            st.vy = 0.0;
            ny = st.y;
        }

        // ③ 落地：球底已经到地面（或掉出世界）
 // ★★ 地面改用 **gsBbFloorSweep**（从**上一刻**的球底往下扫）
        //     ⇒ 球一步跨过整块地板也**穿不过去**（老写法正是这么穿的）；
        //     sweep 拿不到（比如这一列真的没有地板）才退回老的单点扫描。
 // ★★ **不立刻停下** —— 交给 gsBbLand()，它先弹 GS_BB_BOUNCES 下再停
        var fl = gsBbFloorSweep(w, nx, st.y, ny, nz, GS_BB_R);
        if (fl == null) fl = gsBbFloor(w, nx, ny - GS_BB_R, nz, 24);
        if (ny <= 0.0 || (fl != null && (ny - GS_BB_R) <= fl)) {
            st.x = nx; st.z = nz;
            gsBbLand(st, (fl != null) ? fl : (ny - GS_BB_R));
            return;
        }

        st.x = nx; st.y = ny; st.z = nz;

        // ④ 兜底：飞太久强行落地（同样用 sweep；实在没有地板就**退回出手高度**，别卡在地底）
        if (st.life > GS_BB_MAX_TICKS) {
            var f2 = gsBbFloorSweep(w, st.x, st.y, st.y - 2.0, st.z, GS_BB_R);
            if (f2 == null) f2 = gsBbFloor(w, st.x, st.y - GS_BB_R, st.z, 24);
            if (f2 == null) f2 = gsBbFloor(w, st.x, st.sy0, st.z, 24);
            if (f2 == null) f2 = (st.sy0 > 0) ? st.sy0 : (st.y - GS_BB_R);
            gsBbRest(st, f2);
        }
    } catch (e) { }
}

function gsBbTickRest(p, st) {
    try {
        // 刚落地静默 0.3 秒，免得"停下的瞬间又被捡起来"
        if ((gsMsNow() - (st.touched | 0)) < GS_BB_REBIND_CD) return;
        if (gsBbTouch(p, st)) gsBbBind(p, st);
    } catch (e) { }
}

// ---------- 每个玩家一跳 ----------
function gsBbPlayerTick(p) {
    try {
        var u = '' + p.getUniqueId();

        var worn = false;
        try {
            var inv = p.getInventory();
            worn = gsBbIs((inv != null) ? inv.getChestplate() : null);
        } catch (e) { worn = false; }

        if (!worn) {
 // ★ 脱下 ⇒ 球消失；★★ **副手那颗「篮球」也要一起收走**
            //   （不然它会留在玩家副手变成一件"垃圾道具"—— 这是我们自己发的临时球，必须带走）
            var stGone = GS_BB_ST[u];
            if (stGone != null) { try { gsBbHandOff(p, stGone); } catch (e) { } gsBbClear(u); }
 // ★★ 脱下胸甲 ⇒ **运球血条整条丢掉**（下次重新绑定时再建一条干净的）
            try { gsBbBarDrop(p); } catch (e) { }
            return;
        }

        var st = GS_BB_ST[u];
        if (st == null) { st = gsBbNew(p); GS_BB_ST[u] = st; }

        st.spin = (st.spin || 0) + GS_BB_SPIN;

 // ★★ 绑定期**每一刻都确保副手有那颗「篮球」**
        //   （玩家把它拖进背包 / 扔了 ⇒ 下一刻立刻补一颗回来，右键才一直有效）；
        //   反过来，**不在绑定期还挂着球 ⇒ 立刻收走**（正常出口已经收过，这里只是兜底）。
        if (st.mode === 'bound') gsBbHandOn(p, st);
        else if (st.heldBall) gsBbHandOff(p, st);

        if (st.mode === 'bound') gsBbTickBound(p, st);
        else if (st.mode === 'shot') gsBbTickShot(p, st);
        else if (st.mode === 'rest') gsBbTickRest(p, st);
        else gsBbTickIdle(p, st);

 // ★★ 运球血条**只在 bound 期间存在** —— 一旦离开 bound 立刻收起
        //   （投篮 / 超时掉落两个出口已经各自收过；这里兜的是 idle / rest 这些"本来就不该有血条"的状态）
        if (st.mode !== 'bound') { try { gsBbBarHide(p); } catch (e) { } }

        gsBbDraw(st);
    } catch (e) { }
}

// ---------- 心跳 ----------
function gsBbGenNow() {
    try {
        if (GS_BB_PDT == null || GS_BB_K_GEN == null) return null;
        var w = getServer().getWorlds().get(0);
        var v = w.getPersistentDataContainer().get(GS_BB_K_GEN, GS_BB_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}

function gsBbGenBump() {
    try {
        var cur = gsBbGenNow();
        if (cur == null) cur = 0;
        GS_BB_GEN = (cur | 0) + 1;
        if (GS_BB_PDT == null || GS_BB_K_GEN == null) return;
        var w = getServer().getWorlds().get(0);
        w.getPersistentDataContainer().set(GS_BB_K_GEN, GS_BB_PDT.INTEGER, GS_BB_GEN);
    } catch (e) { if (GS_BB_GEN == null || GS_BB_GEN <= 0) GS_BB_GEN = 1; }
}

function gsBbClean() {
    try {
        var live = {};
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) { try { live['' + it.next().getUniqueId()] = 1; } catch (e) { } }
        for (var k in GS_BB_ST) {
            if (GS_BB_ST[k] == null || live[k] !== 1) {
 // ★★ 人不在线 ⇒ 连带把展示实体删掉（否则实体没人管、也不会自己消失）
                try { gsBbEntDrop(GS_BB_ST[k]); } catch (e) { }
                try { delete GS_BB_ST[k]; } catch (e) { }
            }
        }
 // ★★ 运球血条跟着一起清 —— 人都不在线了，血条留着只会变成一条"幽灵条"
        for (var k2 in GS_BB_BARS) {
            var rb = GS_BB_BARS[k2];
            if (rb == null || live[k2] !== 1) {
                try { if (rb != null && rb.b != null) rb.b.removeAll(); } catch (e) { }
                try { if (rb != null && rb.b != null) rb.b.setVisible(false); } catch (e) { }
                try { delete GS_BB_BARS[k2]; } catch (e) { }
            }
        }
    } catch (e) { }
}

function gsBbPulse() {
    var mine = true;
    try {
        var g = gsBbGenNow();
        // 读不到世代号时**不要**把自己当成旧实例（否则心跳会整个停掉）
        if (g != null && GS_BB_GEN !== -1) mine = (g === GS_BB_GEN);
    } catch (e) { }
    if (!mine) {
 // ★★ 旧实例留下的展示实体**必须先删掉**，再丢状态
        //   （分两趟循环：同一个 for-in 里"边遍历边删"会漏项）
        try { for (var kd in GS_BB_ST) gsBbEntDrop(GS_BB_ST[kd]); } catch (e) { }
        try { for (var k in GS_BB_ST) delete GS_BB_ST[k]; } catch (e) { }
 // ★★ 旧实例的运球血条同样作废（否则会在玩家身上留一条"永远不动的条"）
        try {
            for (var kb in GS_BB_BARS) {
                var rb = GS_BB_BARS[kb];
                try { if (rb != null && rb.b != null) { rb.b.removeAll(); rb.b.setVisible(false); } } catch (e) { }
                try { delete GS_BB_BARS[kb]; } catch (e) { }
            }
        } catch (e) { }
        return;
    }
    try {
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = it.next();
            try { gsBbPlayerTick(p); } catch (e2) { }
        }
 // ★★ 每 2 秒兜底扫一遍全体在线玩家的**整个背包** ——
        //   「篮球」这件临时物品**绝不该留在玩家身上**（拖进背包 / 扔了又捡 / 脚本重载丢状态 都会残留）
        var nowM = gsMsNow();
        if ((nowM - (GS_BB_SWEEP_AT | 0)) >= 2000) {
            GS_BB_SWEEP_AT = nowM;
            var it2 = getServer().getOnlinePlayers().iterator();
            while (it2.hasNext()) { try { gsBbSweep(it2.next()); } catch (e3) { } }
        }
    } catch (e) { }
    gsBbClean();
}

// ---------- 注册同步监听器（右键 = 投篮） ----------
function gsBbClickOnce(p, tag) {
    try {
        var now = gsMsNow();
        var k = '' + p.getUniqueId() + '|' + tag;
        var last = GS_BB_CLICK_AT[k];
        if (last != null && (now - last) < 80) return false;
        GS_BB_CLICK_AT[k] = now;
        return true;
    } catch (e) { return true; }
}

// 主手是不是空着（AIR / CAVE_AIR / VOID_AIR）
//   ★ 判不出来时**返回 false**（= 当成"手上有东西"）⇒ 只是不启用挥手兜底，绝不会误触
// ★ 判空逻辑收拢到 gsBbIsEmpty（副手放球那段也要判空，别写两份）
function gsBbHandEmpty(p) {
    try {
        if (p == null) return false;
        var inv = p.getInventory();
        return gsBbIsEmpty((inv != null) ? inv.getItemInMainHand() : null);
    } catch (e) { return false; }
}

function gsBbRegisterSync() {
    if (GS_BB_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var Res = null;
        try { Res = Java.type('org.bukkit.event.Event$Result'); } catch (e) { Res = null; }

        // ★ 右键：**只有球正绑在这个玩家手上**时才接管（= 投篮）
 // ★★ 这里的「主手判定」去掉了（原来直接 return 副手）。
        //      原因：原版**主手空着 + 右键空气时，客户端根本不发包** ⇒ 服务端收不到 PlayerInteractEvent
        //      （这是原版限制，任何插件都一样）。所以任何**能收到**的右键都要拿来用：
        //        · 手里拿着任何物品 + 右键空气 / 方块 ⇒ 收到（最常见的通路）
        //        · 空手 + 右键**方块 / 地面** ⇒ 收到（RIGHT_CLICK_BLOCK 照样送）
        //        · 副手拿着物品 + 主手空着右键 ⇒ 这次走的是 OFF_HAND，也认
        //      重复派发（主手 + 副手各一次）由去抖 + 状态机（投出去就不是 bound）兜住。
 // ★★ 绑定期间**副手被我们塞了一颗「篮球」** ⇒ 主手空着对空气右键
        //      客户端**也会发包**了（原版"手上有东西才发"）⇒ 右键这条路**真正打通**。
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    var st = GS_BB_ST['' + p.getUniqueId()];
                    if (st == null || st.mode !== 'bound') return;
                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { act = ''; }
                    if (act.indexOf('RIGHT_CLICK') < 0) return;

                    e.setCancelled(true);
                    if (Res != null) {
                        try { e.setUseInteractedBlock(Res.DENY); } catch (e2) { }
                        try { e.setUseItemInHand(Res.DENY); } catch (e2) { }
                    }
                    if (!gsBbClickOnce(p, 'R')) return;
                    gsBbShoot(p);                                            // 投篮
                } catch (e3) { }
            });
        }

 // ★ 挥手兜底（· 保留但**可关**）：
        //   空手对着空气右键时客户端**不发包** ⇒ 服务端永远收不到 —— 这是原版限制、拦不住。
 // ★ **副手有「篮球」了 ⇒ 右键会正常发包**，这条兜底已经"用不上了"，
        //     所以加了开关 `GS_BB_SWING_FALLBACK`：
        //       · = true （默认）：保留 —— 万一某个版本/服务端行为不同，球也不会卡死在手上
        //       · = false        ：关掉 —— 就不会出现"绑定期左键打怪顺手把球扔出去"
        //     两种取值都只影响**空手挥手**这一路；右键（①②③）永远有效。
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (GS_BB_SWING_FALLBACK && AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var p = e.getPlayer();
                    if (p == null) return;
                    var st = GS_BB_ST['' + p.getUniqueId()];
                    if (st == null || st.mode !== 'bound') return;
                    if (!gsBbHandEmpty(p)) return;                 // 手持物品的挥手不当投篮
                    try {
                        var at = e.getAnimationType();
                        if (at != null) {
                            var an = '' + at.name();
                            if (an.indexOf('SWING') < 0 && an.indexOf('ARM') < 0) return;
                        }
                    } catch (e2) { }
                    if (!gsBbClickOnce(p, 'W')) return;
                    gsBbShoot(p);
                } catch (e3) { }
            });
        }

        GS_BB_REGISTERED = true;
        try { getServer().getLogger().info('[构思技艺] 律师函监听器注册成功（穿着胸甲 → 尘埃粒子篮球 / 触碰绑定 / '
            + '★ 绑定期间副手自动出现「篮球」（' + GS_BB_BALL_MAT + '）⇒ 右键直接投 / '
            + '★ 绑定期间挂「' + GS_BB_BAR_TEXT + '」倒计时血条（只有这两个字 / ' + (GS_BB_BIND_MS / 1000) + ' 秒）/ '
            + '空手挥手兜底 = ' + (GS_BB_SWING_FALLBACK ? '开' : '关') + '）'); } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 律师函监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsBbStart() {
    try {
        gsBbGenBump();
        if (!gsBbRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 律师函启动失败：监听器没注册上'); } catch (e) { }
            return;
        }
        runRepeating(gsBbPulse, 1, 1);       // ★ 每游戏刻（球要跟着跳 / 抛物线要准）
        try {
            getServer().getLogger().info('[构思技艺] 你等着收我的律师函吧（' + GS_BB_ID
                + '）已加载 · 尘埃粒子篮球（非烟花 · 橙黑 ' + GS_BB_DOTS + ' 点 · 直径 '
                + (GS_BB_R * 2).toFixed(2) + ' 格 ≈ 头颅 · 粒子大小 ' + GS_BB_DOT_SIZE + '）/ 触碰 ' + GS_BB_TOUCH
                + ' 格 / 绑定 ' + (GS_BB_BIND_MS / 1000) + ' 秒 / 超时落点 ' + GS_BB_DROP_R
                + ' 格 / ★ 投篮沿**准星三维方向** 速度 ' + GS_BB_SHOT_SPD
                + '（平视=传球 / 朝下=假动作 / 朝上=高抛）'
                + ' · 先直线 ' + GS_BB_STRAIGHT + ' 刻后重力 ' + GS_BB_SHOT_GRAV
                + ' / 撞墙垂直坠落 / 头顶撞方块即开始下落'
                + ' / ★★ 落地先弹 ' + GS_BB_BOUNCES + ' 下再停（弹起 ' + GS_BB_BOUNCE_UP
                + ' 逐次 ×' + GS_BB_BOUNCE_DECAY + ' · 没撞墙按 ×' + GS_BB_BOUNCE_KEEP
                + ' 保留前速（上限 ' + GS_BB_BOUNCE_MAX + '）往前弹 · 撞过墙原地弹）'
                + ' / 停下后等触碰'
                + ' / ★ 绑定期副手出现「' + GS_BB_BALL_NAME + '」（' + GS_BB_BALL_MAT
                + '）⇒ 右键真发包 · 副手本来有东西就不动 · 离开绑定立刻收走'
                + ' / ★ 运球血条「' + GS_BB_BAR_TEXT + '」' + (GS_BB_BIND_MS / 1000)
                + ' 秒倒计时（标题只有这两个字 · 满条 → 空条 → 投出/清零即消失 · 颜色 '
                + GS_BB_BAR_COLOR + '）· 运球位置前移 ' + GS_BB_HAND_FWD + ' 格（半个身位）'
                + ' / ★★ 渲染 = ' + (GS_BB_USE_ENT
                    ? ('展示实体 ItemDisplay（头颅皮肤 · 每 ' + GS_BB_ENT_EVERY + ' 刻同步 1 次 · 插值 '
                       + GS_BB_ENT_TPDUR + ' 刻）') : '尘埃粒子球（26 点）'));
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 律师函启动异常: ' + e); } catch (e2) { }
    }
}

gsBbStart();

// ==========================================================================
// ★★ 篮球生成器（GS_BASKET_GEN · 红色混凝土 · 构思玩具第 8 格）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §25
// ==========================================================================
var GS_BG_ID       = 'GS_BASKET_GEN';
// ★★ 一台生成器**同时只有一颗球**（= 多人抢同一颗）。
// 曾是 10（同时被 10 个人各拿一颗）—— 用户澄清："我说的十个人是打一场比赛
//    用一颗球打"，所以要的是**一颗球多人抢**，不是生成 10 颗。要改回多颗只需改这个数。
var GS_BG_MAX      = 1;                        // 每台生成器同时存在的球上限（1 = 全场共用一颗）
var GS_BG_RANGE    = 32;                       // 离生成器超过这个距离 ⇒ 自动送回
// ★ 球**球心**停在方块上方几格（1.5 ⇒ 球底离方块顶面约 0.26 格，微微悬浮、看得清）
var GS_BG_UP       = 1.5;
var GS_BG_TOUCH    = 1.4;                      // 水平触碰半径（格）
var GS_BG_TOUCH_V  = 1.6;                      // 竖直触碰容差（格）
// ★★ 副手那颗临时球**必须用另一种按钮**：律师函的 gsBbIsBall 有个"名字含篮球 +
//    材质 = ACACIA_BUTTON"的兜底判定 ⇒ 生成器的球若也用金合欢木按钮，会被律师函
//    认领走（两套球串台）。换成白桦木按钮 ⇒ 两边各自认自己的，互不干扰。
var GS_BG_BALL_MAT = 'BIRCH_BUTTON';
var GS_BG_BALL_NAME = '篮球';

var GS_BG_NODES    = {};                       // key -> node { w, x, y, z, balls: [] }
var GS_BG_REGISTERED = false;
var GS_BG_GEN      = -1;                       // ★ 自己的心跳世代号
var GS_BG_CLICK_AT = {};                       // 右键去抖

var GS_BG_K_GEN = null, GS_BG_K_NODES = null, GS_BG_K_BALL = null, GS_BG_PDT = null;
try {
    var BG_NK = Java.type('org.bukkit.NamespacedKey');
    GS_BG_K_GEN   = BG_NK.fromString('gs:bg_gen');    // ★ 绝不复用 gs:bb_gen
    GS_BG_K_NODES = BG_NK.fromString('gs:bg_nodes');  // 生成器位置列表（跨重启）
    GS_BG_K_BALL  = BG_NK.fromString('gs:bg_ball');   // ★ 副手那颗临时球（与律师函的 gs:bb_ball 分开）
    GS_BG_PDT     = Java.type('org.bukkit.persistence.PersistentDataType');
} catch (e) { }

// ---------- 小工具 ----------
function gsBgKey(w, x, y, z) { return '' + w + '|' + (x | 0) + '|' + (y | 0) + '|' + (z | 0); }
function gsBgIs(item) { return gsIsItem(item, GS_BG_ID); }
function gsBgWorld() { try { return getServer().getWorlds().get(0); } catch (e) { return null; } }

// ---------- 生成器节点的增删查 ----------
function gsBgAdd(w, x, y, z) {
    try {
        if (w == null) return null;
        var k = gsBgKey(w.getName(), x, y, z);
        if (GS_BG_NODES[k] != null) return GS_BG_NODES[k];
        var node = { w: w, x: (x | 0), y: (y | 0), z: (z | 0), balls: [] };
        GS_BG_NODES[k] = node;
        gsBgSave();
        return node;
    } catch (e) { return null; }
}

function gsBgNodeAt(w, x, y, z) {
    try { return GS_BG_NODES[gsBgKey(w, x, y, z)] || null; } catch (e) { return null; }
}

// 删掉一个生成器（方块被挖掉）⇒ 它名下的球**全部消失**（实体也要删，不留幽灵）
function gsBgDropNode(node) {
    try {
        if (node == null) return;
        for (var i = 0; i < node.balls.length; i++) gsBbEntDrop(node.balls[i]);
        node.balls = [];
        try { delete GS_BG_NODES[gsBgKey(node.w.getName(), node.x, node.y, node.z)]; } catch (e) { }
        gsBgSave();
    } catch (e) { }
}

// ★★★ 挖掉生成器时，**方块本身必须掉回「篮球生成器」物品**。
//   用户实测：挖掉之后只掉**红色混凝土**（原版材质）⇒ 生成器就永久丢了，
//   想再要一个只能重新合成。
//   ★ 病因：RSC 的 `placeable: true` 只管「放下时把物品变成方块」，
//     **不管「挖掉时把方块变回物品」**（那是 Slimefun 机器的 BlockStorage 才有的待遇，
//     自定义物品走的不是那条路）⇒ 方块一碎，原版就按材质掉了红混凝土。
//   ⇒ 自己接管：先把**原版掉落关掉**（`setDropItems(false)`，这样红混凝土不再掉），
//     再把方块正常打碎，最后**丢一个全新的 `GS_BASKET_GEN`** 出去。
//   ★ 不用 `setCancelled(true)`：取消了方块就碎不掉，还得自己 setType(AIR) 收尾，
//     反而容易和别的插件打架；`setDropItems(false)` 更干净 —— 方块照常碎、只换掉落物。
//   ★ 只对**我们登记过的**生成器生效（外面已经用 gsBgNodeAt 判过），
//     所以不会误伤别的红混凝土方块。
function gsBgDropSelf(ev, blk) {
    try {
        if (ev == null || blk == null) return false;

        // ① 关掉原版掉落（红混凝土就是从这里掉的）
        try { ev.setDropItems(false); } catch (e0) { }

        // ② 丢一个全新的生成器（用 gsNewItem ⇒ 名字 / 材质 / 属性都和合成出来的一模一样）
        var it = gsNewItem(GS_BG_ID);
        if (it == null) return false;
        var loc = null;
        try { loc = blk.getLocation().add(0.5, 0.5, 0.5); } catch (e1) { loc = null; }
        if (loc == null) { try { loc = blk.getLocation(); } catch (e2) { return false; } }
        try { blk.getWorld().dropItemNaturally(loc, it); }
        catch (e3) {
            try { blk.getWorld().dropItem(loc, it); } catch (e4) { return false; }
        }
        return true;
    } catch (e) { return false; }
}

// ---------- 持久化（生成器位置存世界 PDC，重启后还记得）----------
function gsBgSave() {
    try {
        if (GS_BG_PDT == null || GS_BG_K_NODES == null) return;
        var w0 = gsBgWorld();
        if (w0 == null) return;
        var s = '';
        for (var k in GS_BG_NODES) {
            var n = GS_BG_NODES[k];
            if (n == null) continue;
            if (s.length > 0) s += ';';
            s += n.w.getName() + '|' + n.x + '|' + n.y + '|' + n.z;
        }
        w0.getPersistentDataContainer().set(GS_BG_K_NODES, GS_BG_PDT.STRING, s);
    } catch (e) { }
}

function gsBgLoad() {
    try {
        if (GS_BG_PDT == null || GS_BG_K_NODES == null) return;
        var w0 = gsBgWorld();
        if (w0 == null) return;
        var v = w0.getPersistentDataContainer().get(GS_BG_K_NODES, GS_BG_PDT.STRING);
        if (v == null) return;
        var parts = ('' + v).split(';');
        for (var i = 0; i < parts.length; i++) {
            if (parts[i].length === 0) continue;
            var p = parts[i].split('|');
            if (p.length < 4) continue;
            var w = getServer().getWorld(p[0]);
            if (w == null) continue;
            var n = { w: w, x: (p[1] | 0), y: (p[2] | 0), z: (p[3] | 0), balls: [] };
            GS_BG_NODES[gsBgKey(p[0], n.x, n.y, n.z)] = n;
        }
    } catch (e) { }
}

// ---------- 球 ----------
function gsBgNewBall(node) {
    return { mode: 'idle', w: null,
             x: node.x + 0.5, y: node.y + GS_BG_UP, z: node.z + 0.5,
             vx: 0, vy: 0, vz: 0, spin: 0, life: 0, shotAt: 0, tBoundAt: 0,
             bnc: 0, hitWall: false, ent: null, entTick: 0, sy0: 0,
             owner: null, node: node, heldBall: false };
}

function gsBgBind(p, b) {
    try {
        b.mode = 'bound';
        b.owner = '' + p.getUniqueId();
        b.tBoundAt = gsMsNow();
        b.vx = 0; b.vy = 0; b.vz = 0;
        gsBgHandOn(p, b);
    } catch (e) { }
}

// 球回到"停着"的状态（rest = 落地；玩家下线时也走这条）
function gsBgToRest(b) {
    try {
        if (b.owner != null) gsBgHandOffUuid(b.owner);
        b.owner = null;
        b.mode = 'rest';
        b.vx = 0; b.vy = 0; b.vz = 0;
        var fl = gsBbFloorSweep(b.w, b.x, b.y, b.y - 2.0, b.z, GS_BB_R);
        if (fl == null) fl = gsBbFloor(b.w, b.x, b.y - GS_BB_R, b.z, 24);
        if (fl != null) b.y = fl + GS_BB_R;
    } catch (e) { }
}

// ★ 距离超过 GS_BG_RANGE ⇒ 送回生成器上方（变回"待捡"）
function gsBgFar(node, b) {
    try {
        var dx = b.x - (node.x + 0.5), dz = b.z - (node.z + 0.5), dy = b.y - node.y;
        return Math.sqrt(dx * dx + dz * dz + dy * dy) > GS_BG_RANGE;
    } catch (e) { return false; }
}

function gsBgSendHome(node, b) {
    try {
        if (b.owner != null) gsBgHandOffUuid(b.owner);
        b.owner = null;
        b.mode = 'idle';
        b.vx = 0; b.vy = 0; b.vz = 0;
        b.x = node.x + 0.5; b.z = node.z + 0.5; b.y = node.y + GS_BG_UP;   // ★ 球心在方块上方 1.5 格
        b.bnc = 0; b.hitWall = false;
    } catch (e) { }
}

function gsBgTouch(p, b) {
    try {
        var loc = p.getLocation();
        var dx = b.x - loc.getX(), dz = b.z - loc.getZ();
        if (Math.sqrt(dx * dx + dz * dz) > GS_BG_TOUCH) return false;
        return Math.abs(b.y - (loc.getY() + 0.9)) < GS_BG_TOUCH_V;
    } catch (e) { return false; }
}

// 这个玩家已经拿着的球（生成器版）
function gsBgBoundBallOf(p) {
    try {
        var u = '' + p.getUniqueId();
        for (var k in GS_BG_NODES) {
            var n = GS_BG_NODES[k];
            if (n == null) continue;
            for (var i = 0; i < n.balls.length; i++) {
                var b = n.balls[i];
                if (b != null && b.owner === u && b.mode === 'bound') return b;
            }
        }
    } catch (e) { }
    return null;
}

function gsBgHasBound(p) { return gsBgBoundBallOf(p) != null; }

// 这个玩家在这个生成器上是不是已经有一颗了
function gsBgPlayerHasHere(p, node) {
    try {
        var u = '' + p.getUniqueId();
        for (var i = 0; i < node.balls.length; i++) {
            var b = node.balls[i];
            if (b != null && b.owner === u && b.mode === 'bound') return true;
        }
    } catch (e) { }
    return false;
}

// ★ 只有"停着的球"才能被捡（idle = 生成点上待捡 / rest = 落地停住）
function gsBgTryPick(node, b) {
    try {
        if (b.mode !== 'idle' && b.mode !== 'rest') return;
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
                if (gsBgHasBound(p)) continue;            // 一人一次只拿一颗
                if (gsBgPlayerHasHere(p, node)) continue;
                if (!gsBgTouch(p, b)) continue;
                gsBgBind(p, b);
                return;
            } catch (e2) { }
        }
    } catch (e) { }
}

// 跟着玩家运球（位置算法与律师函一致：右手侧 0.42 + 前移 0.5）
function gsBgTickBound(p, b) {
    try {
        var loc = p.getLocation();
        var dir = null;
        try { dir = loc.getDirection(); } catch (e) { dir = null; }
        var fx = (dir != null) ? dir.getX() : 0.0;
        var fz = (dir != null) ? dir.getZ() : 1.0;
        var l = Math.sqrt(fx * fx + fz * fz);
        if (l < 1.0e-4) { fx = 0.0; fz = 1.0; l = 1.0; }
        fx /= l; fz /= l;
        var rx = -fz, rz = fx;
        var gy = gsBbFloor(b.w, loc.getX(), loc.getY(), loc.getZ(), 6);
        if (gy == null) gy = loc.getY();
        var loY = gy + GS_BB_R;
        var hiY = loc.getY() + GS_BB_HAND_UP;
        if (hiY < loY) hiY = loY;
        var t = (gsMsNow() - (b.tBoundAt | 0)) / 1000.0;
        var k = Math.abs(Math.cos(Math.PI * t / GS_BB_BOUNCE_S));
        b.x = loc.getX() + rx * GS_BB_HAND_SIDE + fx * GS_BB_HAND_FWD;
        b.z = loc.getZ() + rz * GS_BB_HAND_SIDE + fz * GS_BB_HAND_FWD;
        b.y = loY + k * (hiY - loY);
    } catch (e) { }
}

// ---------- 每个生成器每刻 ----------
function gsBgNodeTick(node) {
    try {
        var w = node.w;
        if (w == null) return;
        var idleCount = 0;
        for (var i0 = 0; i0 < node.balls.length; i0++) {
            var b0 = node.balls[i0];
            if (b0 == null) continue;
            if (b0.mode === 'idle') idleCount++;
        }
        // ① ★★ 一台生成器**只养一颗球**（GS_BG_MAX = 1）：球**彻底没了**才补一颗。
        //    —— 球在别人手上（bound）/ 飞在半空（shot）/ 落地停着（rest）都算"这颗球还在"，
        //    绝不再长第二颗 ⇒ 多人抢的**就是同一颗球**（"10 个人打一场比赛共用一颗"）。
 // 是"没到 10 颗就一直补"，按用户澄清改掉了，见模块头注释。
        if (node.balls.length < GS_BG_MAX) {
            var nb = gsBgNewBall(node);
            nb.w = w;
            node.balls.push(nb);
            idleCount++;
        }
        // ② 每颗球
        var keep = [];
        for (var i = 0; i < node.balls.length; i++) {
            var b = node.balls[i];
            if (b == null) continue;
            b.w = w;
            b.spin = (b.spin || 0) + GS_BB_SPIN;

            // ★ 跑太远 ⇒ 送回生成器
            if (b.mode !== 'idle' && gsBgFar(node, b)) gsBgSendHome(node, b);

            if (b.mode === 'bound') {
                var p = gsBgPlayerByUuid(b.owner);
                if (p == null) gsBgToRest(b);        // 人不在了 ⇒ 球留在原地（别人可以捡）
                else gsBgTickBound(p, b);
            } else if (b.mode === 'shot') {
                gsBbTickShot(null, b);               // ★ 直接复用律师函的飞行物理
            } else {
                gsBgTryPick(node, b);                // idle / rest ⇒ 等人来捡
            }

            // ★ 保险：任何情况下**只留一颗待捡球**（GS_BG_MAX = 1 时理论上不会出现多余的）
            if (b.mode === 'idle') {
                if (idleCount > GS_BG_MAX) { gsBbEntDrop(b); idleCount--; continue; }
            }
            gsBbDraw(b);                             // ★ 复用律师函的渲染（展示实体 / 粒子）
            keep.push(b);
        }
        node.balls = keep;
    } catch (e) { }
}

function gsBgPlayerByUuid(u) {
    try {
        if (u == null) return null;
        // 优先用 Bukkit.getPlayer(UUID)（拿不到再遍历在线玩家）
        try {
            var Bukkit = Java.type('org.bukkit.Bukkit');
            var UU = Java.type('java.util.UUID');
            var p = Bukkit.getPlayer(UU.fromString(u));
            if (p != null) return p;
        } catch (e) { }
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var q = it.next();
            try { if (('' + q.getUniqueId()) === u) return q; } catch (e2) { }
        }
    } catch (e) { }
    return null;
}

// ---------- 副手那颗临时球（★ 骗原版客户端右键发包，与律师函那颗分开标记）----------
function gsBgBallNew() {
    try {
        var Material = Java.type('org.bukkit.Material');
        var ItemStack = Java.type('org.bukkit.inventory.ItemStack');
        var mat = null;
        try { mat = Material[GS_BG_BALL_MAT]; } catch (e) { mat = null; }
        if (mat == null) { try { mat = Material.valueOf(GS_BG_BALL_MAT); } catch (e) { mat = null; } }
        if (mat == null) { try { mat = Material.ACACIA_BUTTON; } catch (e) { mat = null; } }
        if (mat == null) return null;
        var st = new ItemStack(mat, 1);
        var meta = null;
        try { meta = st.getItemMeta(); } catch (e) { meta = null; }
        if (meta != null) {
            try { meta.setDisplayName('§f' + GS_BG_BALL_NAME); } catch (e) { }
            try {
                if (GS_BG_K_BALL != null && GS_BG_PDT != null) {
                    meta.getPersistentDataContainer().set(GS_BG_K_BALL, GS_BG_PDT.STRING, '1');
                }
            } catch (e) { }
            try { st.setItemMeta(meta); } catch (e) { }
        }
        return st;
    } catch (e) { return null; }
}

function gsBgIsBall(item) {
    try {
        if (item == null) return false;
        var meta = item.getItemMeta();
        if (meta == null) return false;
        if (GS_BG_K_BALL == null || GS_BG_PDT == null) return false;
        return meta.getPersistentDataContainer().get(GS_BG_K_BALL, GS_BG_PDT.STRING) != null;
    } catch (e) { return false; }
}

function gsBgIsEmpty(item) {
    try {
        if (item == null) return true;
        var t = item.getType();
        if (t == null) return true;
        var n = '' + t.name();
        return (n === 'AIR' || n === 'CAVE_AIR' || n === 'VOID_AIR');
    } catch (e) { return false; }
}

function gsBgHandOn(p, b) {
    try {
        if (p == null || b == null) return;
        var inv = p.getInventory();
        if (inv == null) return;
        var cur = null;
        try { cur = inv.getItemInOffHand(); } catch (e) { cur = null; }
        // ★ 副手已经有"钥匙"了（不管是生成器的球还是律师函那颗）⇒ 直接复用，不再塞第二颗
        if (gsBgIsBall(cur) || gsBbIsBall(cur)) { b.heldBall = true; return; }
        if (!gsBgIsEmpty(cur)) return;            // ★ 副手是玩家自己的东西 ⇒ 不碰
        var ball = gsBgBallNew();
        if (ball == null) return;
        try { inv.setItemInOffHand(ball); b.heldBall = true; } catch (e) { }
    } catch (e) { }
}

function gsBgHandOff(p, b) {
    try {
        if (b != null) b.heldBall = false;
        if (p == null) return;
        var inv = p.getInventory();
        if (inv == null) return;
        var cur = null;
        try { cur = inv.getItemInOffHand(); } catch (e) { cur = null; }
        if (gsBgIsBall(cur)) { try { inv.setItemInOffHand(null); } catch (e) { } }
    } catch (e) { }
}

function gsBgHandOffUuid(u) {
    try {
        var p = gsBgPlayerByUuid(u);
        if (p != null) gsBgHandOff(p, null);
    } catch (e) { }
}

// ---------- 右键投篮 ----------
function gsBgTryShoot(p) {
    try {
        if (p == null) return false;
        var u = '' + p.getUniqueId();
        var now = gsMsNow();
        if ((now - (GS_BG_CLICK_AT[u] | 0)) < 120) return false;   // 去抖
        GS_BG_CLICK_AT[u] = now;
        var b = gsBgBoundBallOf(p);
        if (b == null) return false;
        var ok = gsBbShootState(p, b);            // ★ 复用律师函的投篮（准星方向 + 两段式）
        if (ok) gsBgHandOff(p, b);                // 投出去 ⇒ 副手那颗球收走
        return ok;
    } catch (e) { return false; }
}

// ---------- 世代号（自己的，绝不复用 gs:bb_gen）----------
function gsBgGenNow() {
    try {
        if (GS_BG_PDT == null || GS_BG_K_GEN == null) return null;
        var w = gsBgWorld();
        if (w == null) return null;
        var v = w.getPersistentDataContainer().get(GS_BG_K_GEN, GS_BG_PDT.INTEGER);
        return (v == null) ? 0 : (v | 0);
    } catch (e) { return null; }
}

function gsBgGenBump() {
    try {
        var cur = gsBgGenNow();
        if (cur == null) cur = 0;
        GS_BG_GEN = (cur | 0) + 1;
        if (GS_BG_PDT == null || GS_BG_K_GEN == null) return;
        var w = gsBgWorld();
        if (w == null) return;
        w.getPersistentDataContainer().set(GS_BG_K_GEN, GS_BG_PDT.INTEGER, GS_BG_GEN);
    } catch (e) { if (GS_BG_GEN == null || GS_BG_GEN <= 0) GS_BG_GEN = 1; }
}

// ---------- 心跳 ----------
function gsBgPulse() {
    var mine = true;
    try {
        var g = gsBgGenNow();
        if (g != null && GS_BG_GEN !== -1) mine = (g === GS_BG_GEN);
    } catch (e) { }
    if (!mine) {
        // 旧实例放手：把自己造的实体全删掉（不然会和新的实例各留一颗球）
        try {
            for (var kd in GS_BG_NODES) {
                var nd = GS_BG_NODES[kd];
                if (nd == null) continue;
                for (var i = 0; i < nd.balls.length; i++) gsBbEntDrop(nd.balls[i]);
                nd.balls = [];
            }
        } catch (e) { }
        return;
    }
    try {
        for (var k in GS_BG_NODES) {
            var node = GS_BG_NODES[k];
            if (node != null) gsBgNodeTick(node);
        }
    } catch (e) { }
}

// ---------- 监听器：放置 / 破坏 / 右键 ----------
function gsBgRegisterSync() {
    if (GS_BG_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        // ① 放下生成器 ⇒ 登记一个节点
        try {
            var PlaceEv = Java.type('org.bukkit.event.block.BlockPlaceEvent');
            reg(PlaceEv, function (e) {
                try {
                    if (e == null) return;
                    var item = null;
                    try { item = e.getItemInHand(); } catch (err) { item = null; }
                    if (!gsBgIs(item)) return;
                    var blk = null;
                    try { blk = e.getBlock(); } catch (err2) { blk = null; }
                    if (blk == null) return;
                    gsBgAdd(blk.getWorld(), blk.getX(), blk.getY(), blk.getZ());
                } catch (err3) { }
            });
        } catch (e) { }

        // ② 挖掉生成器 ⇒ 球全消失 + ★ 方块本身掉回「篮球生成器」物品
        try {
            var BreakEv = Java.type('org.bukkit.event.block.BlockBreakEvent');
            reg(BreakEv, function (e) {
                try {
                    if (e == null) return;
                    var blk = null;
                    try { blk = e.getBlock(); } catch (err) { blk = null; }
                    if (blk == null) return;
                    var node = gsBgNodeAt(blk.getWorld().getName(), blk.getX(), blk.getY(), blk.getZ());
                    if (node == null) return;
                    gsBgDropNode(node);
 // ★★★ 再把生成器物品掉出来（不然只掉红混凝土，物品就没了）
                    gsBgDropSelf(e, blk);
                } catch (err2) { }
            });
        } catch (e) { }

        // ③ 右键 = 投篮（四路，和律师函一样：主手空 + 右键空气时原版不发包 ⇒ 靠副手球 / 挥手兜底）
        try {
            var InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent');
            reg(InterEv, function (e) {
                try {
                    if (e == null) return;
                    var p = null;
                    try { p = e.getPlayer(); } catch (err) { p = null; }
                    if (p == null) return;
                    if (!gsBgHasBound(p)) return;         // ★ 只有拿着生成器的球时才接管右键
                    gsBgTryShoot(p);
                } catch (err2) { }
            });
        } catch (e) { }

        // ④ 挥手兜底（空手对着空气右键时客户端不发包 ⇒ 挥手也能投）
        try {
            var AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent');
            reg(AnimEv, function (e) {
                try {
                    if (e == null) return;
                    if (GS_BB_SWING_FALLBACK !== true) return;
                    var p = null;
                    try { p = e.getPlayer(); } catch (err) { p = null; }
                    if (p == null) return;
                    if (!gsBgHasBound(p)) return;
                    var inv = p.getInventory();
                    var main = (inv != null) ? inv.getItemInMainHand() : null;
                    if (!gsBgIsEmpty(main)) return;       // 手里有东西的挥手多半是在打人，别误触
                    gsBgTryShoot(p);
                } catch (err2) { }
            });
        } catch (e) { }

        GS_BG_REGISTERED = true;
        return true;
    } catch (e) { return false; }
}

function gsBgStart() {
    try {
        gsBgGenBump();
        gsBgLoad();
        if (!gsBgRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 篮球生成器启动失败：监听器没注册上'); } catch (e) { }
            return;
        }
        runRepeating(gsBgPulse, 1, 1);
        try {
            getServer().getLogger().info('[构思技艺] 篮球生成器（' + GS_BG_ID + '）已加载 · 放下后上方 '
                + GS_BG_UP + ' 格生成篮球 / **每台只有 1 颗球**（多人抢同一颗，捡走不再补第二颗）/ 只有**停着的球**捡得走（抢不走别人手上的）/ 超出 '
                + GS_BG_RANGE + ' 格自动送回 / 挖掉方块球全部消失');
        } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 篮球生成器启动异常: ' + e); } catch (e2) { }
    }
}

gsBgStart();

// ==========================================================================
// ★★ 副手那颗「篮球」**拿不下来**（钥匙球锁 · GS_KL）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §14
// ==========================================================================
var GS_KL_SLOT_OFF   = 40;      // PlayerInventory 索引：**40 = 副手槽**
var GS_KL_HINT_MS    = 2500;    // 「拿不下来」提示的去抖（毫秒）
var GS_KL_SWEEP_MS   = 500;     // 背包残留清扫周期（毫秒）
var GS_KL_REGISTERED = false;
var GS_KL_HINT_AT    = {};
var GS_KL_SWEEP_AT   = 0;

function gsKlUuid(p) {
    try { return (p == null) ? null : ('' + p.getUniqueId()); } catch (e) { return null; }
}

// 是不是"玩家"（鸭子类型：拿不到 UUID / 背包 / 副手槽的都不是）
//   ★ 用能力判断而不是 instanceof：GraalJS 下 instanceof 对宿主类不一定可靠，
//     而且这个函数只用来"放行"，判错顶多是**少拦**（每刻自愈还兜着）。
function gsKlIsPlayer(o) {
    try {
        if (o == null) return false;
        o.getUniqueId();
        var inv = o.getInventory();
        if (inv == null) return false;
        if (typeof inv.getItemInOffHand !== 'function') return false;
        return true;
    } catch (e) { return false; }
}

// 是不是我们发的那颗「钥匙球」（律师函的金合欢木按钮 / 生成器的白桦木按钮）
function gsKlKey(item) {
    try { if (gsBbIsBall(item)) return true; } catch (e) { }
    try { if (gsBgIsBall(item)) return true; } catch (e) { }
    return false;
}

// 玩家**现在**副手里是不是我们的钥匙
function gsKlHasKey(p) {
    try {
        if (p == null) return false;
        var inv = p.getInventory();
        if (inv == null) return false;
        return gsKlKey(inv.getItemInOffHand());
    } catch (e) { return false; }
}

// 这个玩家**现在应该**挂着钥匙球吗（= 正在运球：律师函那条 / 生成器那颗）
function gsKlNeed(p) {
    try {
        var u = gsKlUuid(p);
        if (u != null) {
            var st = GS_BB_ST[u];
            if (st != null && st.mode === 'bound') return true;
        }
    } catch (e) { }
    try { if (gsBgBoundBallOf(p) != null) return true; } catch (e) { }
    return false;
}

// 收走副手那颗钥匙（**只收我们自己发的**；玩家自己的东西绝不动）
function gsKlTakeKey(p) {
    try {
        if (p == null) return false;
        var inv = p.getInventory();
        if (inv == null) return false;
        if (!gsKlKey(inv.getItemInOffHand())) return false;
        try { inv.setItemInOffHand(null); } catch (e) { return false; }
        return true;
    } catch (e) { return false; }
}

// 补回钥匙：交给各模块自己的 HandOn（它们自己会判"副手空着才放"⇒ 不会覆盖玩家物品）
function gsKlGiveKey(p) {
    try {
        var u = gsKlUuid(p);
        if (u != null) {
            var st = GS_BB_ST[u];
            if (st != null && st.mode === 'bound') {
                gsBbHandOn(p, st);
                if (gsKlHasKey(p)) return true;
            }
        }
    } catch (e) { }
    try {
        var b = gsBgBoundBallOf(p);
        if (b != null) {
            gsBgHandOn(p, b);
            if (gsKlHasKey(p)) return true;
        }
    } catch (e) { }
    return false;
}

// 提示（去抖 2.5 秒）：actionbar 比聊天栏不打扰
//   ★ 第一路 `Player#sendActionBar(String)`（Spigot / Paper 都有，1.17+ 起稳定）；
//     拿不到就退回第二路（bungee 组件）⇒ 两条都不行就**静默**（绝不影响拦截本身）。
function gsKlHint(p) {
    try {
        var u = gsKlUuid(p);
        if (u == null) return;
        var now = gsMsNow();
        if ((now - (GS_KL_HINT_AT[u] | 0)) < GS_KL_HINT_MS) return;
        GS_KL_HINT_AT[u] = now;
        var msg = '§6运球时篮球拿不下来';
        try { p.sendActionBar(msg); return; } catch (e) { }
        try {
            var T = Java.type('net.md_5.bungee.api.ChatMessageType');
            var C = Java.type('net.md_5.bungee.api.chat.TextComponent');
            p.spigot().sendMessage(T.ACTION_BAR, C.fromLegacyText(msg));
        } catch (e2) { }
    } catch (e) { }
}

// 这个玩家**当前**该挂着钥匙吗（拿不到状态的玩家 = 不该挂）
function gsKlSweep(p) {
    try {
        if (p == null) return;
        var inv = p.getInventory();
        if (inv == null) return;
        var need = gsKlNeed(p);
        var n = 0;
        try { n = inv.getSize(); } catch (e) { n = 36; }
        if (!(n > 0)) n = 36;
        for (var i = 0; i < n; i++) {
            var it = null;
            try { it = inv.getItem(i); } catch (e) { continue; }
            if (!gsKlKey(it)) continue;
            if (i === GS_KL_SLOT_OFF && need) continue;   // 副手槽 + 正在运球 ⇒ 这颗是"正常"的
            try { inv.setItem(i, null); } catch (e) { }
        }
    } catch (e) { }
}

// 取消之后把背包**主动重发一次**：原版对"被取消的点击"一般会自动重发，
//   但不同服务端实现有差异（Paper 上有时只剩客户端本地预测）⇒ 保险起见主动同步，
//   免得玩家看到"光标上粘着一个其实不存在的按钮"（幽灵物品）。
//   ★ `Player#updateInventory()` 新版是 deprecated 但仍在 ⇒ 拿不到就算了（绝不影响拦截本身）。
function gsKlResync(p) {
    try { p.updateInventory(); } catch (e) { }
}

// ---------- ① 点击：副手槽上的一切操作都不许（拖走 / 放下 / 快速移动 / 数字键 / 丢到窗口外）----------
//   ★ 只看**玩家本人**的背包视图（`getClickedInventory().getHolder()` 必须是这个玩家）：
//     在箱子 / 工作台界面里点箱子那一半 ⇒ holder 不是玩家 ⇒ 一概不管（不会误拦箱子槽）。
//   ★ 副手槽判定位用 `getSlot()`（相对所点击 inventory 的索引，副手恒为 40），
//     这正是社区标准的写法；`getRawSlot()===45` 只在"玩家自身视图"成立，箱子视图下会错位
//     ⇒ 所以**只用 slot**，不用 raw（用 raw 有可能误拦箱子里别的槽）。
function gsKlClickedOffSlot(p, e) {
    try {
        var inv = null;
        try { inv = e.getClickedInventory(); } catch (e2) { inv = null; }
        if (inv == null) return false;
        var h = null;
        try { h = inv.getHolder(); } catch (e2) { h = null; }
        if (h == null) return false;
        var u = null;
        try { u = '' + h.getUniqueId(); } catch (e2) { u = null; }
        if (u == null || u !== gsKlUuid(p)) return false;
        var slot = -1;
        try { slot = e.getSlot() | 0; } catch (e2) { slot = -1; }
        return (slot === GS_KL_SLOT_OFF);
    } catch (e) { return false; }
}

function gsKlOnClick(e) {
    try {
        if (e == null) return;
        var p = null;
        try { p = e.getWhoClicked(); } catch (e2) { p = null; }
        if (!gsKlIsPlayer(p)) return;
        if (!gsKlHasKey(p)) return;                     // 副手没我们的钥匙 ⇒ 完全不管
        if (!gsKlClickedOffSlot(p, e)) return;          // 碰的不是副手槽 ⇒ 与本球无关
        try { e.setCancelled(true); } catch (e2) { }
        gsKlResync(p);
        gsKlHint(p);
    } catch (e) { }
}

// ---------- ② F 键（主副手交换）----------
function gsKlOnSwap(e) {
    try {
        if (e == null) return;
        var p = null;
        try { p = e.getPlayer(); } catch (e2) { p = null; }
        if (!gsKlIsPlayer(p)) return;
        var off = null, main = null;
        try { off = e.getOffHandItem(); } catch (e2) { off = null; }
        try { main = e.getMainHandItem(); } catch (e2) { main = null; }
        if (!gsKlKey(off) && !gsKlKey(main)) return;    // 交换的不是我们的钥匙 ⇒ 放行
        try { e.setCancelled(true); } catch (e2) { }
        gsKlResync(p);
        gsKlHint(p);
    } catch (e) { }
}

// ---------- ③ 拖拽（按住鼠标拖）----------
//   ★ 只拦"光标上正拿着我们的钥匙"这一路：从副手槽把球**拿起来**的那一下是
//     InventoryClickEvent（已被 ① 拦掉）⇒ 正常流程里光标上不可能有钥匙；
//     真出现了（漏拦 / 别的插件塞的）就必须拦，否则一拖就丢。
//   ★ 不按 rawSlot 判副手：rawSlot=45 只在"玩家自身视图"成立，箱子视图里会错位
//     （可能误拦箱子的别的槽）⇒ 交给 ① 与"每刻自愈"兜底更安全。
function gsKlOnDrag(e) {
    try {
        if (e == null) return;
        var p = null;
        try { p = e.getWhoClicked(); } catch (e2) { p = null; }
        if (!gsKlIsPlayer(p)) return;
        var cur = null;
        try { cur = e.getCursor(); } catch (e2) { cur = null; }
        if (!gsKlKey(cur)) return;
        try { e.setCancelled(true); } catch (e2) { }
        gsKlResync(p);
        gsKlHint(p);
    } catch (e) { }
}

// ---------- ④ 丢弃 ----------
function gsKlOnDrop(e) {
    try {
        if (e == null) return;
        var it = null;
        try { it = e.getItemDrop().getItemStack(); } catch (e2) { it = null; }
        if (!gsKlKey(it)) return;
        try { e.setCancelled(true); } catch (e2) { }
    } catch (e) { }
}

// ---------- ⑤ 死亡：掉落列表剔掉钥匙 + 生成器那颗球留在地上 ----------
function gsKlOnDeath(e) {
    try {
        if (e == null) return;
        var p = null;
        try { p = e.getEntity(); } catch (e2) { p = null; }
        if (!gsKlIsPlayer(p)) return;

        // ① drops 里的"假篮球"剔掉（原版死亡会掉副手物品 ⇒ 不清就会在地上多一个按钮）
        try {
            var drops = e.getDrops();
            if (drops != null) {
                var rm = [];
                var n = 0;
                try { n = drops.size() | 0; } catch (e2) { n = 0; }
                for (var i = 0; i < n; i++) {
                    var it = null;
                    try { it = drops.get(i); } catch (e2) { it = null; }
                    if (gsKlKey(it)) rm.push(it);
                }
                for (var j = 0; j < rm.length; j++) { try { drops.remove(rm[j]); } catch (e2) { } }
            }
        } catch (e2) { }

        // ② 生成器那颗球：**留在死亡点**（掉在地上 ⇒ 别人可以走过去捡）
        try {
            var b = gsBgBoundBallOf(p);
            if (b != null) gsBgToRest(b);
        } catch (e2) { }

        // ③ 副手那颗钥匙当场收走（不等 drops 列表，双保险）
        gsKlTakeKey(p);
    } catch (e) { }
}

// ---------- ⑥ 退出：收走钥匙（不然重登后副手会挂着一颗幽灵按钮）----------
function gsKlOnQuit(e) {
    try {
        if (e == null) return;
        var p = null;
        try { p = e.getPlayer(); } catch (e2) { p = null; }
        if (!gsKlIsPlayer(p)) return;
        gsKlTakeKey(p);
        gsKlSweep(p);
    } catch (e) { }
}

// ---------- 每刻自愈 ----------
function gsKlTickPlayer(p) {
    try {
        if (p == null) return;
        var need = gsKlNeed(p);
        var has = gsKlHasKey(p);
        if (need && !has) gsKlGiveKey(p);       // 被外力拿走了 ⇒ 下一刻补回
        else if (!need && has) gsKlTakeKey(p);  // 没在运球却挂着 ⇒ 收走（reload 残留 / 幽灵）
    } catch (e) { }
}

function gsKlPulse() {
    try {
        var it = getServer().getOnlinePlayers().iterator();
        while (it.hasNext()) {
            var p = null;
            try { p = it.next(); } catch (e) { continue; }
            try { gsKlTickPlayer(p); } catch (e) { }
        }
        var now = gsMsNow();
        if ((now - (GS_KL_SWEEP_AT | 0)) >= GS_KL_SWEEP_MS) {
            GS_KL_SWEEP_AT = now;
            var it2 = getServer().getOnlinePlayers().iterator();
            while (it2.hasNext()) {
                var q = null;
                try { q = it2.next(); } catch (e) { continue; }
                try { gsKlSweep(q); } catch (e) { }
            }
        }
    } catch (e) { }
}

// ---------- 注册 ----------
function gsKlRegisterSync() {
    if (GS_KL_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, { execute: function (l, e) { handler(e); } });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) { return false; }
            }
        }

        var ok = 0;
        // ① 点击（含创造模式物品栏 —— InventoryCreativeEvent 是它的子类，父类注册照样收得到）
        try {
            var ClickEv = Java.type('org.bukkit.event.inventory.InventoryClickEvent');
            if (reg(ClickEv, gsKlOnClick)) ok++;
        } catch (e) { }
        // ② F 键交换
        try {
            var SwapEv = Java.type('org.bukkit.event.player.PlayerSwapHandItemsEvent');
            if (reg(SwapEv, gsKlOnSwap)) ok++;
        } catch (e) { }
        // ③ 拖拽
        try {
            var DragEv = Java.type('org.bukkit.event.inventory.InventoryDragEvent');
            if (reg(DragEv, gsKlOnDrag)) ok++;
        } catch (e) { }
        // ④ 丢弃
        try {
            var DropEv = Java.type('org.bukkit.event.player.PlayerDropItemEvent');
            if (reg(DropEv, gsKlOnDrop)) ok++;
        } catch (e) { }
        // ⑤ 死亡
        try {
            var DeathEv = Java.type('org.bukkit.event.entity.PlayerDeathEvent');
            if (reg(DeathEv, gsKlOnDeath)) ok++;
        } catch (e) { }
        // ⑥ 退出
        try {
            var QuitEv = Java.type('org.bukkit.event.player.PlayerQuitEvent');
            if (reg(QuitEv, gsKlOnQuit)) ok++;
        } catch (e) { }

        GS_KL_REGISTERED = true;
        try {
            getServer().getLogger().info('[构思技艺] 副手「篮球」钥匙锁已加载（' + ok + '/6 条事件）'
                + ' · 运球/持球期间那颗按钮**拿不下来**（点击副手槽 / F 键 / 拖拽 / 丢弃 一律拦回）'
                + ' · 被外力拿走 ⇒ 每刻补回 + ' + (GS_KL_SWEEP_MS / 1000) + ' 秒清残留'
                + ' · 死亡时剔掉掉落物里的假球、球留在死亡点 / 退出时收走');
        } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 钥匙锁注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsKlStart() {
    try {
        if (!gsKlRegisterSync()) {
            try { getServer().getLogger().warning('[构思技艺] 钥匙锁启动失败：监听器没注册上（每刻自愈仍会跑）'); } catch (e) { }
        }
        runRepeating(gsKlPulse, 1, 1);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 钥匙锁启动异常: ' + e); } catch (e2) { }
    }
}

gsKlStart();

// ★★ 展示实体「孤儿球」清扫。
//   ★ 必须放在**最后**：这一刻律师函（gsBbStart）与生成器（gsBgStart）都已经把自己
//     的状态表建好了 ⇒ 启动清扫"全删"时不会误伤任何**正在用**的球（本来也没有）。
//     之后再靠周期清扫 / 关服清扫兜住"区块后来才加载"的残留。
gsEntPurgeStart();

// ==========================================================================
// ★★ 第五十八批（2026-10-11）：钓鱼获得「海水过滤器」GS_SEA_FILTER
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §26
//   ★ 以下 1 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//         FISHING / CAUGHT_FISH / CAUGHT_ENTITY / IN_GROUND /
// ==========================================================================

var GS_SF_ID = 'GS_SEA_FILTER';      // 海水过滤器
var GS_SF_CHANCE = 7;                // 钓率 7%
var GS_SF_AMOUNT = 1;                // 1 个
var GS_SF_REGISTERED = false;

// 在玩家（或浮漂）位置掉 1 个海水过滤器
function gsSfDrop(p, hookLoc) {
    var it = gsNewItem(GS_SF_ID);
    if (it == null) return false;
    try { it.setAmount(GS_SF_AMOUNT); } catch (e0) { }
    // ① 优先浮漂位置（= 原版鱼掉落的地方）
    if (hookLoc != null) {
        try { p.getWorld().dropItemNaturally(hookLoc, it); return true; } catch (e1) { }
        try { p.getWorld().dropItem(hookLoc, it); return true; } catch (e2) { }
    }
    // ② 兜底：玩家脚下
    try { p.getWorld().dropItemNaturally(p.getLocation(), it); return true; } catch (e3) { }
    try { p.getWorld().dropItem(p.getLocation(), it); return true; } catch (e4) { }
    return false;
}

// 钓鱼回调
function gsSfOnFish(e) {
    try {
        if (e == null) return;
        var st = e.getState();
        if (st == null) return;
        if (('' + st) !== 'CAUGHT_FISH') return;      // 只认「钓上来了」
        var p = e.getPlayer();
        if (p == null) return;
        if (Math.floor(Math.random() * 100) >= GS_SF_CHANCE) return;   // 93% 直接不给
        var hookLoc = null;
        try {
            var hook = e.getHook();
            if (hook != null) hookLoc = hook.getLocation();
        } catch (eH) { hookLoc = null; }
        gsSfDrop(p, hookLoc);
    } catch (e2) { }
}

// 注册同步监听器（同 gsBbRegisterSync / gsKlRegisterSync 的写法）
function gsSfRegisterSync() {
    if (GS_SF_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        var FishEv = null;
        try { FishEv = Java.type('org.bukkit.event.player.PlayerFishEvent'); } catch (e1) { FishEv = null; }
        if (FishEv == null) return false;

        try {
            pm.registerEvent(FishEv, plugin, Priority.MONITOR,
                function (l, ev) { gsSfOnFish(ev); }, plugin);
            GS_SF_REGISTERED = true;
        } catch (e2) {
            try {
                var ListenerC = Java.type('org.bukkit.event.Listener');
                var ExecC = Java.type('org.bukkit.plugin.EventExecutor');
                var listener = new JavaAdapter(ListenerC, {});
                var exec = new JavaAdapter(ExecC, { execute: function (l, ev) { gsSfOnFish(ev); } });
                pm.registerEvent(FishEv, listener, Priority.MONITOR, exec, plugin);
                GS_SF_REGISTERED = true;
            } catch (e3) { GS_SF_REGISTERED = false; }
        }
        return GS_SF_REGISTERED;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 海水过滤器钓鱼监听注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsSfStart() {
    try {
        var ok = gsSfRegisterSync();
        try {
            getServer().getLogger().info('[构思技艺] 「海水过滤器」钓鱼获取已加载（'
                + (ok ? '监听 PlayerFishEvent · 状态 CAUGHT_FISH' : '★监听没注册上★')
                + ' · 钓率 ' + GS_SF_CHANCE + '% · 每次 ' + GS_SF_AMOUNT + ' 个'
                + ' · 不取消原版钓鱼）');
        } catch (e1) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 海水过滤器钓鱼启动异常: ' + e); } catch (e2) { }
    }
}

gsSfStart();

// ==========================================================================
// 祖宗发射器<?>（岩浆鸡唱片 MUSIC_DISC_LAVA_CHICKEN · 2026-10-12 第七十六批新增）
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §27
//   ★ 以下 3 行被 _check/ 下的校验器**逐字断言**，故留在代码里（改动前先看对应断言）：
//     ① 材质 ⇒ 用户「唱片chirp」⇒ MUSIC_DISC_CHIRP
//     · 加构思机九宫格配方（在 items.yml + mb_machines.yml，本文件不动）。
//     推进用 **runLater 自链**（同 gs_release_dream.js；**不用** runRepeating +
// ==========================================================================

var GS_AL_ID = "GS_ANCESTOR_LAUNCHER";
var GS_AL_AMMO = 6;                   // ★ 爆裂黎明 6 发（名字里 6 个 ▼）
var GS_AL_FIRE_MS = 800;              // ★ 常态射速 0.8 秒/发（第七十八批改；原 1.3 秒）
var GS_AL_SKILL_FIRE_MS = 1500;       // ★ 技能射速 1.5 秒/发（第七十八批改；原 3 秒）
var GS_AL_CD_MS = 12000;              // ★ 技能冷却 12 秒（第七十九批改；原 18 秒）
var GS_AL_TOGGLE_MS = 1000;           // ★ 右键切换防抖 1 秒（用户「防抖1秒」）
var GS_AL_RANGE = 24.0;               // ★ 射程 24 格（两种状态**相同**，用户「射程不变」）
var GS_AL_SPEED_BPS = 30.0;           // 弹速 30 格/秒 = 24 格 / 0.8 秒（用户「0.8秒24格」）
var GS_AL_STEP = 1.5;                 // 每刻前进 1.5 格（20 tick/秒 ⇒ 30 格/秒）
var GS_AL_HIT_R = 0.8;                // 弹道命中生物的判定半径（格）
var GS_AL_MUZZLE_Y = 1.2;             // 发射高度（脚底往上 1.2 格 = 胸口）
var GS_AL_N_R = 2.5;                  // ★ 常态爆炸半径 2.5 格
var GS_AL_N_DMG = 35.0;               // ★ 常态爆炸伤害 35 点（第七十九批改；原 55 点）
var GS_AL_S_R = 4.5;                  // ★ 技能爆炸半径 4.5 格
var GS_AL_S_DMG = 175.0;              // ★ 技能爆炸伤害 175 点（第七十九批改；原 155 点）
var GS_AL_NAME = '祖宗发射器';         // 名字前缀（`<...>` 里放弹药）
var GS_AL_DOT = '\u25bc';             // ▼（U+25BC）—— 名字里一个 ▼ 代表一发弹药
// ★★★ **聊天栏提示词全部删除**（用户「删除右键提示词」）。
//   冷却中 / 已启动 / 已关闭 / 弹药耗尽 —— 一条都不再发消息。
//   ★ 状态看**名字**就够了：常态 `<?>`、技能 `<▼▼▼▼▼▼>`。

// 音效（全部用**字符串 key**，同残樱的写法）
var GS_AL_SND_FIRE = 'block.dispenser.launch';        // 常态开火：发射器"噗"一声
var GS_AL_SND_SFIRE = 'entity.warden.sonic_charge';   // 技能开火：坚守者蓄力
var GS_AL_SND_BOOM = 'entity.generic.explode';        // 常态爆炸
var GS_AL_SND_SBOOM = 'entity.warden.sonic_boom';     // 技能爆炸：坚守者冲击波
var GS_AL_SND_ON = 'block.beacon.activate';           // 开技能
var GS_AL_SND_OFF = 'block.beacon.deactivate';        // 关技能

var GS_AL_KEYS = null;                // { ammo, skill, lastShot, toggle, cd, shooting }
var GS_AL_REGISTERED = false;
var GS_AL_DUST = null;                // 常态红色粒子（DustOptions 缓存）

// ---------------- 基础工具 ----------------
function gsAlKeys() {
    if (GS_AL_KEYS != null) return GS_AL_KEYS;
    var o = { ammo: null, skill: null, lastShot: null, toggle: null, cd: null, shooting: null };
    try {
        var NK = Java.type('org.bukkit.NamespacedKey');
        o.ammo = NK.fromString('gs:al_ammo');        // 技能剩余弹药（0~6）
        o.skill = NK.fromString('gs:al_skill');      // 是否处于「爆裂黎明」
        o.lastShot = NK.fromString('gs:al_lastshot'); // 上次开火时间戳
        o.toggle = NK.fromString('gs:al_toggle');    // 上次右键切换时间戳（防抖用）
        o.cd = NK.fromString('gs:al_cd');            // 技能结束的时间戳（12 秒冷却起点）
        o.shooting = NK.fromString('gs:al_shooting'); // 弹道伤害放行标记
    } catch (e) {
        return o;
    }
    GS_AL_KEYS = o;
    return GS_AL_KEYS;
}

function gsAlPdc(p) {
    try { return p.getPersistentDataContainer(); } catch (e) { return null; }
}

function gsAlIsLauncher(item) {
    return gsIsItem(item, GS_AL_ID);
}

function gsAlNow() {
    try { return Java.type('java.time.Instant').now().toEpochMilli(); } catch (e) { return 0; }
}

function gsAlColor(s) {
    try {
        var CC = Java.type('org.bukkit.ChatColor');
        return CC.translateAlternateColorCodes('&', s);
    } catch (e) {
        return ('' + s).replace(/&([0-9a-fk-or])/g, '\u00a7$1');
    }
}

// 常态红色粒子用的 DustOptions（只建一次）
function gsAlDustOpt() {
    if (GS_AL_DUST != null) return GS_AL_DUST;
    try {
        var Color = Java.type('org.bukkit.Color');
        var DO = Java.type('org.bukkit.Particle$DustOptions');
        GS_AL_DUST = new DO(Color.fromRGB(255, 40, 40), 1.2);
    } catch (e) { GS_AL_DUST = null; }
    return GS_AL_DUST;
}

// ---------------- PDC 状态读写 ----------------
function gsAlAmmo(p) {
    try {
        var k = gsAlKeys();
        if (k.ammo == null || GS_PDT == null) return GS_AL_AMMO;
        var pdc = gsAlPdc(p);
        if (pdc == null) return GS_AL_AMMO;
        var v = pdc.get(k.ammo, GS_PDT.INTEGER);
        return (v == null) ? GS_AL_AMMO : (v | 0);
    } catch (e) { return GS_AL_AMMO; }
}

function gsAlSetAmmo(p, v) {
    try {
        var k = gsAlKeys();
        if (k.ammo == null || GS_PDT == null) return;
        var pdc = gsAlPdc(p);
        if (pdc != null) pdc.set(k.ammo, GS_PDT.INTEGER, v | 0);
    } catch (e) { }
}

// ★★★ 用 STRING 而不是 BYTE（同 gsCherrySetShootingFlag 的注释：
//   BYTE + JS 数字会装箱成 Integer ⇒ checkcast Byte 失败 ⇒ **静默失败**）
function gsAlIsSkill(p) {
    try {
        var k = gsAlKeys();
        if (k.skill == null || GS_PDT == null) return false;
        var pdc = gsAlPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.skill, GS_PDT.STRING);
    } catch (e) { return false; }
}

function gsAlSetSkill(p, on) {
    try {
        var k = gsAlKeys();
        if (k.skill == null || GS_PDT == null) return;
        var pdc = gsAlPdc(p);
        if (pdc == null) return;
        if (on) pdc.set(k.skill, GS_PDT.STRING, '1');
        else pdc.remove(k.skill);
    } catch (e) { }
}

function gsAlSetShootingFlag(p, on) {
    try {
        var k = gsAlKeys();
        if (k.shooting == null || GS_PDT == null) return;
        var pdc = gsAlPdc(p);
        if (pdc == null) return;
        if (on) pdc.set(k.shooting, GS_PDT.STRING, '1');
        else pdc.remove(k.shooting);
    } catch (e) { }
}

function gsAlIsShooting(p) {
    try {
        var k = gsAlKeys();
        if (k.shooting == null || GS_PDT == null) return false;
        var pdc = gsAlPdc(p);
        if (pdc == null) return false;
        return pdc.has(k.shooting, GS_PDT.STRING);
    } catch (e) { return false; }
}

// 通用时间戳读（返回"距今多少毫秒"，没存过 = 999999）
function gsAlAgoOf(p, key) {
    try {
        if (key == null || GS_PDT == null) return 999999;
        var pdc = gsAlPdc(p);
        if (pdc == null) return 999999;
        var v = pdc.get(key, GS_PDT.LONG);
        if (v == null) return 999999;
        return gsAlNow() - (0 + v);
    } catch (e) { return 999999; }
}

function gsAlLastShotAgo(p) { return gsAlAgoOf(p, gsAlKeys().lastShot); }
function gsAlToggleAgo(p) { return gsAlAgoOf(p, gsAlKeys().toggle); }

function gsAlMark(p, key) {
    try {
        if (key == null || GS_PDT == null) return;
        var pdc = gsAlPdc(p);
        if (pdc != null) pdc.set(key, GS_PDT.LONG, gsAlNow());
    } catch (e) { }
}

function gsAlMarkShot(p) { gsAlMark(p, gsAlKeys().lastShot); }
function gsAlMarkToggle(p) { gsAlMark(p, gsAlKeys().toggle); }
function gsAlMarkCd(p) { gsAlMark(p, gsAlKeys().cd); }

// 冷却剩余（毫秒）；0 = 可用
function gsAlCdLeft(p) {
    try {
        var k = gsAlKeys();
        if (k.cd == null || GS_PDT == null) return 0;
        var pdc = gsAlPdc(p);
        if (pdc == null) return 0;
        var at = pdc.get(k.cd, GS_PDT.LONG);
        if (at == null) return 0;
        var left = GS_AL_CD_MS - (gsAlNow() - (0 + at));
        return left > 0 ? left : 0;
    } catch (e) { return 0; }
}

// ---------------- 名字（`<...>` 里放弹药）----------------
//   常态 = 祖宗发射器<?>          （无限弹药，用户「不改变?」）
//   技能 = 祖宗发射器<▼▼▼▼▼▼>    （6 个 ▼，打一发少一个）
function gsAlNameFor(p) {
    if (gsAlIsSkill(p)) {
        var a = gsAlAmmo(p);
        var dots = '';
        for (var i = 0; i < a; i++) dots += GS_AL_DOT;
        return '&c' + GS_AL_NAME + '<' + dots + '>';
    }
    return '&c' + GS_AL_NAME + '<?>';
}

function gsAlUpdateName(p) {
    try {
        var inv = p.getInventory();
        var hand = null;
        try { hand = inv.getItemInMainHand(); } catch (e) { hand = null; }
        if (hand == null || !gsAlIsLauncher(hand)) return;

        var want = gsAlColor(gsAlNameFor(p));

        // ★ 心跳每 10 刻会调到这里 ⇒ **名字没变就一个字节都不写**。
        //   每次 setItemInMainHand 都会让客户端重新同步那一格（白白刷包、
        //   还可能打断客户端的物品动画）⇒ 必须先比对再写。
        var meta = null;
        try { meta = hand.getItemMeta(); } catch (e) { meta = null; }
        if (meta == null) return;
        var cur = null;
        try { cur = meta.getDisplayName(); } catch (e) { cur = null; }
        if (cur != null && ('' + cur) === ('' + want)) return;

        try { meta.setDisplayName(want); } catch (e) { }
        hand.setItemMeta(meta);
        try { inv.setItemInMainHand(hand); } catch (e) { }
    } catch (e) { }
}

// ---------------- 技能开关（右键）----------------
function gsAlToggle(p) {
    try {
        if (p == null) return;

        // ★ 防抖 1 秒：一下右键被服务器识别成两次时不至于"刚开就关"
        if (gsAlToggleAgo(p) < GS_AL_TOGGLE_MS) return;
        gsAlMarkToggle(p);

        if (gsAlIsSkill(p)) { gsAlExitSkill(p, false); return; }

        // 进技能前查冷却
        var cd = gsAlCdLeft(p);
        if (cd > 0) return;                // ★ 第七十七批：冷却中静默失败（不发消息）

        gsAlSetSkill(p, true);
        gsAlSetAmmo(p, GS_AL_AMMO);        // ★ 每次开启都补满 6 发
        gsAlUpdateName(p);
        try { p.getWorld().playSound(p.getLocation(), GS_AL_SND_ON, 1.0, 1.0); } catch (e) { }
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 祖宗发射器切换出错: ' + e); } catch (e2) { }
    }
}

// 退出技能 ⇒ 回常态 + **开始 12 秒冷却**
function gsAlExitSkill(p, silent) {
    try {
        gsAlSetSkill(p, false);
        gsAlSetAmmo(p, GS_AL_AMMO);
        gsAlMarkCd(p);                     // ★ 冷却从这里开始算
        gsAlUpdateName(p);
 // ★ 不再发「已关闭」消息；silent 现在只决定**放不放关闭音效**
        if (!silent) {
            try { p.getWorld().playSound(p.getLocation(), GS_AL_SND_OFF, 1.0, 1.0); } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 开火（左键）----------------
function gsAlFire(p) {
    try {
        if (p == null) return;

        if (gsAlIsSkill(p)) {
            // ★ 技能：3 秒一发
            if (gsAlLastShotAgo(p) < GS_AL_SKILL_FIRE_MS) return;
            var a = gsAlAmmo(p);
            if (a <= 0) { gsAlExitSkill(p, false); return; }

            gsAlSetAmmo(p, a - 1);         // ★ 打一发少一个 ▼
            gsAlMarkShot(p);
            gsAlLaunch(p, true);
            gsAlUpdateName(p);

            // ★ 6 发打完 ⇒ **自动退出技能，回常态**（用户已确认），并开始 12 秒冷却
            if (gsAlAmmo(p) <= 0) {
                gsAlExitSkill(p, true);    // ★ 第七十七批：静默退出（不发消息）
            }
            return;
        }

        // ---- 常态：1.3 秒一发，**无限弹药** ----
        if (gsAlLastShotAgo(p) < GS_AL_FIRE_MS) return;
        gsAlMarkShot(p);
        gsAlLaunch(p, false);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 祖宗发射器开火出错: ' + e); } catch (e2) { }
    }
}

// ==========================================================================
// ---------------- 沿途找第一个挡路的方块（★ 扫掠求交，不是单点采样）----------------
//   ★ 完整设计说明（需求原文 / 参数取舍 / 批次沿革 / 踩坑记录）见 文档存档/gs_listener·模块设计说明.md §28
// ==========================================================================
function gsAlWallHit(world, from, dir, maxLen) {
    try {
        var FCM = Java.type('org.bukkit.FluidCollisionMode');
        var res = world.rayTraceBlocks(from, dir, maxLen, FCM.NEVER, true);
        if (res == null) return null;
        var hp = res.getHitPosition();
        if (hp == null) return null;
        var Loc = Java.type('org.bukkit.Location');
        return new Loc(world, hp.getX(), hp.getY(), hp.getZ());
    } catch (e) { }
    return null;
}

// ---------------- 弹道（runLater 自链 · 真的会飞）----------------
//   每刻前进 GS_AL_STEP(1.5) 格 ⇒ 24 格正好 16 刻 = 0.8 秒。
//   撞到生物 / 撞墙 / 飞到射程尽头 ⇒ 都在**同一处**引爆（三种条件都炸）。
function gsAlLaunch(p, skill) {
    try {
        var world = p.getWorld();
        var eye = null;
        try { eye = p.getEyeLocation(); } catch (e) { return; }
        var dir = eye.getDirection();                  // 方向 = 视线（准星）

        // ★ 发射起点 = 「胸口」（从眼睛发射会挡住视线，同残樱）
        var base = null;
        try { base = p.getLocation(); } catch (e) { return; }
        var sx = base.getX();
        var sy = base.getY() + GS_AL_MUZZLE_Y;
        var sz = base.getZ();

        var selfId = '' + p.getUniqueId();
        var Loc = Java.type('org.bukkit.Location');
        var dist = 0.0;

        // 开火音效（从胸口响）
        try {
            world.playSound(new Loc(world, sx, sy, sz),
                skill ? GS_AL_SND_SFIRE : GS_AL_SND_FIRE, 1.0, 1.0);
        } catch (e) { }

        function step() {
            try {
                var stepLen = GS_AL_STEP;
                // ★ 上一刻的位置 = 这一段扫掠的起点
                var prev = new Loc(world,
                    sx + dir.getX() * dist,
                    sy + dir.getY() * dist,
                    sz + dir.getZ() * dist);
                dist += stepLen;
                var px = sx + dir.getX() * dist;
                var py = sy + dir.getY() * dist;
                var pz = sz + dir.getZ() * dist;
                var pt = new Loc(world, px, py, pz);

                // ① 这一段路上有没有方块挡着（★ 扫掠求交，不是只看终点那一个方块）
                //    ★★ 先判方块、后判生物：否则站在薄墙后面的怪会被**隔墙打中**。
                var wall = gsAlWallHit(world, prev, dir, stepLen);
                if (wall != null) {
                    gsAlTrail(world, wall, skill);
                    gsAlBoom(world, p, wall, skill);
                    return;
                }

                // 拖尾：常态红色粒子 / 技能坚守者冲击波
                gsAlTrail(world, pt, skill);

                // ② 命中生物 ⇒ 引爆
                var victim = gsAlFindLiving(world, pt, GS_AL_HIT_R, selfId);
                if (victim != null) { gsAlBoom(world, p, pt, skill); return; }

                // ③ 飞到射程尽头 ⇒ 引爆
                if (dist >= GS_AL_RANGE) { gsAlBoom(world, p, pt, skill); return; }

                runLater(step, 1);                     // 继续飞
            } catch (e) { }
        }

        runLater(step, 1);
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 祖宗发射器弹道出错: ' + e); } catch (e2) { }
    }
}

// 拖尾特效
function gsAlTrail(world, pt, skill) {
    try {
        var Particle = Java.type('org.bukkit.Particle');
        if (skill) {
            // ★ 坚守者冲击波（弹体本身**没有伤害**，伤害全在引爆那一下）
            try { world.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
        } else {
            // ★ 常态：红色粒子
            var so = gsAlDustOpt();
            if (so != null) {
                try { world.spawnParticle(Particle.DUST, pt, 3, 0.06, 0.06, 0.06, 0.0, so); } catch (e) { }
            }
        }
    } catch (e) { }
}

// 找一个"活着的生物"（排除自己 / 排除盔甲架与掉落物）
function gsAlFindLiving(world, pt, radius, selfId) {
    try {
        var list = world.getNearbyEntities(pt, radius, radius, radius);
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            var eid = '';
            try { eid = '' + ent.getUniqueId(); } catch (e1) { continue; }
            if (eid === selfId) continue;
            try { ent.getHealth(); } catch (e2) { continue; }   // 非生物会抛 ⇒ 天然过滤
            try { if (ent.isDead()) continue; } catch (e3) { }
            try {
                var tn = '' + ent.getType().name();
                if (tn === 'ARMOR_STAND' || tn === 'DROPPED_ITEM' || tn === 'ITEM') continue;
            } catch (e4) { }
            return ent;
        }
    } catch (e) { }
    return null;
}

// ---------------- 引爆（两种状态共用，只有半径 / 伤害 / 特效不同）----------------
function gsAlBoom(world, shooter, pt, skill) {
    try {
        var r = skill ? GS_AL_S_R : GS_AL_N_R;
        var dmg = skill ? GS_AL_S_DMG : GS_AL_N_DMG;
        var selfId = '';
        try { selfId = '' + shooter.getUniqueId(); } catch (e) { selfId = ''; }

        // ---- 视觉：原版爆炸粒子（**不用 createExplosion** ⇒ 不破坏地形）----
        try {
            var Particle = Java.type('org.bukkit.Particle');
            try { world.spawnParticle(Particle.EXPLOSION_EMITTER, pt, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
            try { world.spawnParticle(Particle.EXPLOSION, pt, 12, r * 0.5, r * 0.5, r * 0.5, 0.0); } catch (e) { }
            if (skill) {
                try { world.spawnParticle(Particle.SONIC_BOOM, pt, 1, 0.0, 0.0, 0.0, 0.0); } catch (e) { }
            }
        } catch (e) { }

        // ---- 音效 ----
        try { world.playSound(pt, skill ? GS_AL_SND_SBOOM : GS_AL_SND_BOOM, 1.4, 1.0); } catch (e) { }

        // ---- 伤害：球形范围，**排除自己**，**只打生物** ----
        var list = world.getNearbyEntities(pt, r, r, r);
        var it = list.iterator();
        while (it.hasNext()) {
            var ent = it.next();
            var eid = '';
            try { eid = '' + ent.getUniqueId(); } catch (e1) { continue; }
            if (eid === selfId) continue;                       // ★ 不包括自己
            try { ent.getHealth(); } catch (e2) { continue; }    // ★ 只打生物
            try { if (ent.isDead()) continue; } catch (e3) { }
            // ★ 与 gsAlFindLiving 保持**同一套**过滤：盔甲架 / 掉落物不算「生物」。
            //   （★ 注意：盔甲架在 Bukkit 里**是** LivingEntity、getHealth() 拿得到，
            //     所以光靠上面那句 getHealth 是拦不住它的，必须按类型名再筛一次。）
            try {
                var tn2 = '' + ent.getType().name();
                if (tn2 === 'ARMOR_STAND' || tn2 === 'DROPPED_ITEM' || tn2 === 'ITEM') continue;
            } catch (e4) { }
            // ★ getNearbyEntities 给的是**立方体** ⇒ 再按真实**球**距离过滤一次
            try {
                if (ent.getLocation().distance(pt) > r) continue;
            } catch (e5) { }
            gsAlHitDamage(shooter, ent, dmg);
        }
    } catch (e) { }
}

// 结算伤害（带来源，靠 shooting 标记让 EntityDamageByEntityEvent 监听器放行）
// ★ 清受击无敌帧（setNoDamageTicks(0)），让伤害立即生效（同残樱）
function gsAlHitDamage(shooter, victim, dmg) {
    try {
        if (victim == null) return;
        try { if (victim.isDead()) return; } catch (e) { return; }
        try { victim.getHealth(); } catch (e) { return; }

        try { victim.setNoDamageTicks(0); } catch (e) { }

        gsAlSetShootingFlag(shooter, true);
        try { victim.damage(dmg, shooter); }
        catch (e1) { try { victim.damage(dmg); } catch (e2) { } }
        gsAlSetShootingFlag(shooter, false);
    } catch (e) { }
}

// ---------------- 心跳（每 10 刻）----------------
//   只做一件事：拿着发射器时把名字刷成当前状态（丢过 / 捡回后也能自愈）。
// ★★★ **换手持不再关闭技能**（用户「换手持不会把技能关闭」）——
//     原来切走就把技能收掉并回满弹，现在技能 / 弹药 / 冷却**全部保留**，
//     切回来时名字还是当时的 `<▼…>`。
function gsAlPulse() {
    try {
        if (!gsAuraIsCurrent()) return;

        var players = getServer().getOnlinePlayers();
        var it = players.iterator();
        while (it.hasNext()) {
            var p = it.next();
            try {
                var hand = null;
                try { hand = p.getInventory().getItemInMainHand(); } catch (e) { hand = null; }
                var holding = gsAlIsLauncher(hand);

                // ★★★ 第七十七批：切走了**什么都不做** ——
                //   技能 / 弹药 / 冷却全部原样保留，切回来接着用。
                if (!holding) continue;

                gsAlUpdateName(p);
            } catch (e) { }
        }
    } catch (e) { }
}

// ---------------- 同步监听器注册 ----------------
function gsAlRegisterSync() {
    if (GS_AL_REGISTERED) return true;
    try {
        var pm = getServer().getPluginManager();
        var Priority = Java.type('org.bukkit.event.EventPriority');

        var plugin = null;
        try { plugin = Java.type('org.lins.mmmjjkx.rykenslimefuncustomizer.RykenSlimefunCustomizer').INSTANCE; } catch (e) { plugin = null; }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimeCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('RykenSlimefunCustomizer'); } catch (e) { } }
        if (plugin == null) { try { plugin = pm.getPlugin('Slimefun'); } catch (e) { } }
        if (plugin == null) return false;

        function reg(eventClass, handler) {
            try {
                pm.registerEvent(eventClass, plugin, Priority.HIGHEST,
                    function (l, e) { handler(e); }, plugin);
                return true;
            } catch (e1) {
                try {
                    var ListenerC = Java.type('org.bukkit.event.Listener');
                    var ExecC = Java.type('org.bukkit.event.EventExecutor');
                    var listener = new JavaAdapter(ListenerC, {});
                    var exec = new JavaAdapter(ExecC, {
                        execute: function (l, e) { handler(e); }
                    });
                    pm.registerEvent(eventClass, listener, Priority.HIGHEST, exec, plugin);
                    return true;
                } catch (e2) {
                    return false;
                }
            }
        }

        // ① 左键开火 / 右键切换技能（PlayerInteractEvent）
        //   ★ 右键**必须**取消：唱片右键可以插进唱片机
        var InterEv = null;
        try { InterEv = Java.type('org.bukkit.event.player.PlayerInteractEvent'); } catch (e) { }
        if (InterEv != null) {
            reg(InterEv, function (e) {
                try {
                    var p = e.getPlayer();
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsAlIsLauncher(hand)) return;

                    var act = '';
                    try { act = '' + e.getAction().name(); } catch (e2) { }
                    if (act.indexOf('RIGHT_CLICK') >= 0) {
                        e.setCancelled(true);
                        gsAlToggle(p);                 // ★ 右键 = 爆裂黎明 开 / 关
                    } else if (act.indexOf('LEFT_CLICK') >= 0) {
                        e.setCancelled(true);
                        gsAlFire(p);                   // ★ 左键 = 开火
                    }
                } catch (e3) { }
            });
        }

        // ② EntityDamageByEntityEvent：爆炸伤害放行 + **原版近战取消**
        //   （准星怼在怪身上时客户端发的是攻击包，PlayerInteractEvent 收不到）
        var DmgEv = null;
        try { DmgEv = Java.type('org.bukkit.event.entity.EntityDamageByEntityEvent'); } catch (e) { }
        if (DmgEv != null) {
            reg(DmgEv, function (e) {
                try {
                    var damager = e.getDamager();
                    if (damager == null) return;
                    var en = '';
                    try { en = '' + damager.getType().name(); } catch (e2) { return; }
                    if (en !== 'PLAYER') return;
                    var p = damager;
                    if (gsAlIsShooting(p)) return;     // 爆炸伤害 → 放行
                    var cause = '';
                    try { cause = '' + e.getCause().name(); } catch (e2) { return; }
                    if (cause.indexOf('ENTITY_ATTACK') < 0 && cause.indexOf('SWEEP') < 0) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsAlIsLauncher(hand)) return;  // 没拿发射器 → 原版近战照常
                    e.setCancelled(true);               // ★ 原版近战整个取消
                    gsAlFire(p);                        // ★ 兜底：这一下改成开火
                } catch (e3) { }
            });
        }

        // ②-b 左键的真正兜底 —— 挥手动画（同残樱 / 五骊矟 / 阎魔刀）
        //   ★ 只认 ARM_SWING：OFF_ARM_SWING 是副手挥
        var AnimEv = null;
        try { AnimEv = Java.type('org.bukkit.event.player.PlayerAnimationEvent'); } catch (e) { }
        if (AnimEv != null) {
            reg(AnimEv, function (e) {
                try {
                    var type = '';
                    try { type = '' + e.getAnimationType().name(); } catch (e2) { type = ''; }
                    if (type !== 'ARM_SWING') return;
                    var p = e.getPlayer();
                    if (p == null) return;
                    var hand = null;
                    try { hand = p.getInventory().getItemInMainHand(); } catch (e2) { }
                    if (!gsAlIsLauncher(hand)) return;
                    gsAlFire(p);
                } catch (e3) { }
            });
        }

        GS_AL_REGISTERED = true;
        try {
            getServer().getLogger().info('[构思技艺] 祖宗发射器监听器注册成功'
                + '（左键=开火 · 右键=爆裂黎明 开/关 · 常态 '
                + (GS_AL_FIRE_MS / 1000.0).toFixed(1) + ' 秒/发 · 技能 '
                + (GS_AL_SKILL_FIRE_MS / 1000.0).toFixed(1) + ' 秒/发 · '
                + GS_AL_AMMO + ' 发 · 冷却 ' + (GS_AL_CD_MS / 1000.0).toFixed(0) + ' 秒）');
        } catch (e) { }
        return true;
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 祖宗发射器同步监听器注册异常: ' + e); } catch (e2) { }
        return false;
    }
}

function gsAlStart() {
    try {
        runRepeating(function () { gsAlPulse(); }, 10, 10);
        gsAlRegisterSync();
        gsDaLog('祖宗发射器<?>：已启用（常态 ' + (GS_AL_FIRE_MS / 1000.0).toFixed(1)
            + ' 秒/发 · **无限弹药** · 红色粒子 · 爆炸 ' + GS_AL_N_R + ' 格 / '
            + GS_AL_N_DMG + ' 点；右键「爆裂黎明」⇒ ' + GS_AL_AMMO + ' 发 ▼ · '
            + (GS_AL_SKILL_FIRE_MS / 1000.0).toFixed(1) + ' 秒/发 · 坚守者冲击波 · 爆炸 '
            + GS_AL_S_R + ' 格 / ' + GS_AL_S_DMG + ' 点 · 冷却 '
            + (GS_AL_CD_MS / 1000.0).toFixed(0) + ' 秒 · 防抖 '
            + (GS_AL_TOGGLE_MS / 1000.0).toFixed(0) + ' 秒；射程 ' + GS_AL_RANGE
            + ' 格 · 弹速 ' + GS_AL_SPEED_BPS + ' 格/秒（0.8 秒 24 格）· 不穿墙 · 不破坏地形）');
    } catch (e) {
        try { getServer().getLogger().warning('[构思技艺] 祖宗发射器启动失败: ' + e); } catch (e2) { }
    }
}

gsAlStart();
