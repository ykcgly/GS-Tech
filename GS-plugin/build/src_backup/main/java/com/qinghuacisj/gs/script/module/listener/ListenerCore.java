package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.script.module.Guards;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@code gs_listener.js}（14,337 行 / 564 个顶层函数 / 520 个顶层 var）的
 * <b>共享状态 + 共享工具</b>层。
 *
 * <p><b>为什么需要这一层</b>：这个脚本是<b>一个</b> JS 全局作用域，24 个子系统
 * （梦境套 / 光环 / 余念绝响 / 残樱 / 五骊矟 / 威龙 / 基础构思 / 月镰 / 蚀月 /
 * 致万千无言者 / 此刻万籁俱寂 / 阎魔刀 / 武器禁近战 / 篮球 / 篮球生成器 /
 * 钥匙锁 / 孤儿球清扫 / 海水过滤器 / 祖宗发射器 / 灵感掉落 …）互相调用。
 * 机械统计：<b>47 个函数被 2 个以上子系统引用</b>（{@code gsMsNow} 被 52 个函数
 * 调用、跨 8 个子系统；{@code gsIsItem} 跨 16 个子系统）。
 *
 * <p>所以 Java 化时<b>不能</b>把每个子系统做成互不相干的独立模块 ——
 * 必须先把这 47 个函数与它们依赖的顶层 var 收进本类，各子系统再引用同一份实例。
 * 这恰好等价于原脚本「同一个 JS 上下文里只有一份全局状态」。
 *
 * <p>★★ <b>语义等价的关键</b>：{@code Modules.acquire} 用 {@code computeIfAbsent}
 * 缓存模块 ⇒ {@link com.qinghuacisj.gs.script.module.ListenerModule} 只有<b>一个</b>实例，
 * 它的构造器 = 原脚本的顶层代码（只跑一次），它的字段 = 原脚本的顶层 var。
 *
 * <p>★ 本类的每个方法都<b>逐字</b>照抄原脚本的函数体：连 {@code try/catch} 的位置、
 * 吞异常还是记日志、先试哪种写法再降级，全部保留。唯一的改动是
 * 「JS 的 {@code Java.type} 探测」变成了直接的 Java 调用 ——
 * 因为那些探测在 Java 里必然成功（GraalJS 里它们是为了绕开类加载失败）。
 */
public final class ListenerCore {

    /** 本附属的日志前缀，原脚本硬编码在每一处。 */
    public static final String PREFIX = "[构思技艺] ";

    // ======================================================================
    // 顶层物品 ID（原脚本 13~16 行）
    // ======================================================================

    public static final String GS_IMAGINATION = "GS_IMAGINATION";       // 想象力（线）
    public static final String GS_FRAGMENT = "GS_MEMORY_FRAGMENT";      // 记忆碎片（骷髅头颅）
    public static final String GS_INSPIRATION = "GS_INSPIRATION";       // 灵感（末地烛）
    public static final String GS_THINKING_HAT = "GS_THINKING_HAT";     // 奇思妙想帽

    /** 梦境盔甲（原脚本 227 行）。 */
    public static final String GS_DREAM_ARMOR_ID = "GS_DREAM_ARMOR";

    // ======================================================================
    // 「永不掉耐久」清单（原脚本 446~472 行，逐条保留注释里的理由）
    // ======================================================================

    /**
     * ★ 逐字照抄原脚本的数组。注释保留原文，因为它们记录了「为什么这件也要加进来」。
     */
    public static final List<String> GS_NODMG_IDS = List.of(
            "GS_RELEASE_DREAM", "GS_DREAM_ARMOR", "GS_DREAM_HELMET",
            "GS_DREAM_LEGGINGS", "GS_DREAM_BOOTS", "GS_WULI_SPEAR",
            // ★ 奇思妙想帽（原版金头盔）按需求**不掉耐久**
            "GS_THINKING_HAT",
            // ★ 入梦（铁剑）+ 梦想套四件 同样**不掉耐久**
            "GS_ENTER_DREAM", "GS_WISH_HELMET", "GS_WISH_ARMOR",
            "GS_WISH_LEGGINGS", "GS_WISH_BOOTS",
            // ★ 月镰（铁锄）+ 蚀月·死神梦镰（下界合金锄）—— 两把都是工具材质
            "GS_MOON_SCYTHE", "GS_ECLIPSE_SCYTHE",
            // ★ 你等着收我的律师函吧（黑色皮革胸甲）
            "GS_LAWYER_LETTER",
            // ★ 致万千无言者（石镐，原版只有 131 点耐久）
            "GS_VOICELESS"
    );

    // ======================================================================
    // 共享 PDC 键（原脚本各处 Java.type('...NamespacedKey').fromString(...)）
    // ======================================================================

    /** {@code gs:dream_armor_cd} —— 梦境套免死冷却（存玩家 PDC，跨 reload 不丢）。 */
    public static final NamespacedKey GS_DA_CD_KEY = NamespacedKey.fromString("gs:dream_armor_cd");

    /** {@code gs:aura_gen} —— 梦境套光环世代号（存<b>主世界</b> PDC，用于多实例去重）。 */
    public static final NamespacedKey GS_AURA_KEY = NamespacedKey.fromString("gs:aura_gen");

    /**
     * {@code WL_RIDE_TAG = 'gs_ridein'} —— 五骊矟「开机动画里冲过来引路的那匹马」的记分板标签。
     *
     * <p>★★ <b>这是一个跨子系统的耦合点</b>，所以放在共享层：
     * 五骊矟给马打这个标签（原脚本 3710 / 3832 行），
     * 而<b>梦境套的伤害拦截器</b>读它来放行（原脚本 353 行）——
     * 因为那匹马从 7 格外冲过来要 1.5 秒，被打死就"永远上不了马"。
     *
     * <p>★ 靠<b>记分板标签</b>而不是 PDC：实体被 remove 时标签自动跟着消失
     * ⇒ 不需要任何清理代码。这个选择是有意的，别改成 PDC。
     */
    public static final String WL_RIDE_TAG = "gs_ridein";

    // ======================================================================
    // 共享运行期状态（原脚本的顶层 var）
    // ======================================================================

    /** {@code gsInspTasks} —— uuid → 世代号。灵感掉落的自调度链用它自终止。 */
    public final Map<String, long[]> gsInspTasks = new HashMap<>();

    /** {@code GS_DA_CFG} —— 免死配置缓存（加载期读不到配置，所以懒加载）。 */
    public DaConfig gsDaCfg;

    /** {@code GS_AURA_CFG} —— 光环配置缓存。 */
    public AuraConfig gsAuraCfg;

    /** {@code GS_AURA_MY_GEN} —— 本次加载拿到的世代号；-1 = 还没拿到。 */
    public int gsAuraMyGen = -1;

    /** {@code GS_NODMG_REGISTERED} —— 耐久保护监听器是否已注册。 */
    public boolean gsNoDmgRegistered;

    /** {@code GS_WPN_DMG_GUARD} —— 我们自己造成伤害的计数器（伤害器据此放过自家伤害）。 */
    public int gsWpnDmgGuard;

    /** {@code gsDreamFlyOn} —— uuid → true（<b>本机制</b>负责的飞行）。 */
    public final Map<String, Boolean> gsDreamFlyOn = new HashMap<>();

    /** {@code gsSetEffOn} —— uuid → 本机制给过的药效清单。 */
    public final Map<String, List<int[]>> gsSetEffOn = new HashMap<>();

    /** {@code gsSetNightOn} —— uuid → true（这份夜视是本机制给的）。 */
    public final Map<String, Boolean> gsSetNightOn = new HashMap<>();

    /** {@code gsRadSafe} —— uuid → true（防辐射宽限期保护中）。 */
    public final Map<String, Boolean> gsRadSafe = new HashMap<>();

    /** {@code GS_MS_LOCKS} —— 被定身的生物。 */
    public final List<Lock> gsMsLocks = new ArrayList<>();

    /** {@code GS_MS_STAR / GS_MS_DUST / GS_MS_DUST_TRIED} —— 粒子类型探测缓存。 */
    public Particle gsMsStar;
    public Particle gsMsDust;
    public boolean gsMsDustTried;

    /** {@code GS_VO_ENCH / GS_VO_ENCH_TRIED} —— 附魔粒子探测缓存。 */
    public Particle gsVoEnch;
    public boolean gsVoEnchTried;

    /**
     * {@code GS_NOT_ENEMY_TYPES} —— <b>逐字照抄</b>原脚本 8014~8030 行的表。
     *
     * <p>★ 它决定「哪些实体不算战斗目标」。少一项就会让剑气 / 斩击打到自己人
     * （掉落的物品、盔甲架、展示实体、箭矢…），所以这张表一个都不许省。
     */
    public static final Set<String> GS_NOT_ENEMY_TYPES = Set.of(
            "ITEM", "EXPERIENCE_ORB", "ARMOR_STAND",
            "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
            "ITEM_DISPLAY", "BLOCK_DISPLAY", "TEXT_DISPLAY",
            "INTERACTION", "MARKER", "AREA_EFFECT_CLOUD",
            "BOAT", "CHEST_BOAT", "MINECART", "CHEST_MINECART",
            "COMMAND_BLOCK_MINECART", "FURNACE_MINECART", "HOPPER_MINECART",
            "TNT_MINECART", "SPAWNER_MINECART",
            "ARROW", "SPECTRAL_ARROW", "TRIDENT", "FIREWORK_ROCKET",
            "SNOWBALL", "EGG", "ENDER_PEARL", "EYE_OF_ENDER",
            "POTION", "SPLASH_POTION", "LINGERING_POTION",
            "EXPERIENCE_BOTTLE", "FIREBALL", "SMALL_FIREBALL",
            "DRAGON_FIREBALL", "WITHER_SKULL", "SHULKER_BULLET",
            "LLAMA_SPIT", "EVOKER_FANGS", "FALLING_BLOCK",
            "END_CRYSTAL", "TNT", "PRIMED_TNT", "LEASH_HITCH",
            "LIGHTNING_BOLT", "FISHING_BOBBER", "UNKNOWN"
    );

    // ======================================================================
    // 配置载体
    // ======================================================================

    /** {@code gsDaConfig()} 返回的那个对象（原脚本 235~245 行的默认值）。 */
    public static final class DaConfig {
        public boolean enabled = true;
        public double percent = 9;
        public long cooldown = 45000;      // 免死冷却（毫秒）：45000 = 45 秒（套装效果）
        public double heal = 12.0;         // 免死触发后把血量补到至少这么多（12 = 6 颗心）；0 = 不补
        public Set<String> ignores = new HashSet<>(List.of("VOID", "KILL", "SUICIDE"));
        public String sound = "item.totem.use";
        public boolean particle = true;
        public String msg = "&5这是我的梦！";
        public String breakMsg = "&c梦境：该醒醒了";
    }

    /** {@code gsAuraConfig()} 返回的那个对象（原脚本 692~699 行的默认值）。 */
    public static final class AuraConfig {
        public boolean enabled = true;
        public int eachCount = 6;          // 一圈几个点
        public double radius = 0.80;       // 环绕半径（固定不动）
        public double heightHigh = 0.10;   // 第 1 种粒子的高度
        public double heightLow = 0.10;    // 第 2 种粒子的高度
        public List<String> particles = new ArrayList<>(List.of("SOUL"));
    }

    /** {@code GS_MS_LOCKS[i]} 的记录（原脚本 382 行的对象字面量）。 */
    public static final class Lock {
        public Entity e;
        public String id;
        public World w;
        public double x;
        public double y;
        public double z;
        public long until;
        public boolean ai;
        public Color col;
    }

    /** 血条表里的一条记录（原脚本各族 {@code rec = {p, b}}）。 */
    public static final class BarRec {
        public Player p;
        public BossBar b;
    }

    // ======================================================================
    // 工具函数（原脚本 34~67 行）
    // ======================================================================

    /** {@code gsNewItem(id)} —— 取一个全新的自制物品。 */
    public static ItemStack gsNewItem(String id) {
        try {
            io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getById(id);
            if (sf == null) {
                return null;
            }
            return sf.getItem().clone();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsIsItem(item, id)} —— 判断物品是不是指定的自制物品。
     *
     * <p>★ 原脚本注释特别强调「必须用 {@code ('' + xxx)} 把 Java String 转成 JS 字符串再比」，
     * 那是 Nashorn/GraalJS 的坑；Java 里 {@code String.equals} 就是那个语义。
     * 直接复用 {@link Guards#isOurItem} —— 它已被证明是同一套判定。
     */
    public static boolean gsIsItem(ItemStack item, String id) {
        return Guards.isOurItem(item, id);
    }

    /** {@code gsCfg()} —— 安全获取附属配置。 */
    public static FileConfiguration gsCfg() {
        try {
            return com.qinghuacisj.gs.content.Content.config();
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsDaLog(msg)} —— 统一日志入口（原脚本 289 行）。 */
    public static void gsDaLog(String msg) {
        try {
            Bukkit.getLogger().info(PREFIX + msg);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsDaLog} 的 warning 版（原脚本里散落的 {@code getLogger().warning(...)}）。 */
    public static void gsDaWarn(String msg) {
        try {
            Bukkit.getLogger().warning(PREFIX + msg);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 配置读取
    // ======================================================================

    /**
     * {@code gsDaConfig()} —— 免死配置（原脚本 232~286 行）。
     *
     * <p>★ 三处「不缓存」的语义必须保留：①配置还没加载好 ⇒ 返回默认值<b>且不写缓存</b>；
     * ②配置段不存在 ⇒ 写缓存后返回；③读配置抛异常 ⇒ 返回默认值<b>且不写缓存</b>。
     * 这决定了「配置稍后变好时能不能自动生效」。
     */
    public DaConfig gsDaConfig() {
        if (gsDaCfg != null) {
            return gsDaCfg;
        }

        DaConfig def = new DaConfig();

        try {
            FileConfiguration cfg = gsCfg();
            if (cfg == null) {
                return def;                        // 还没加载好 → 先用默认值，不缓存
            }

            ConfigurationSection sec = cfg.getConfigurationSection("dream-set");
            if (sec == null) {
                gsDaCfg = def;
                return gsDaCfg;
            }

            def.enabled = sec.getBoolean("enabled", true);
            def.percent = sec.getDouble("durability-percent", 9);

            List<String> list = sec.getStringList("ignored-causes");
            if (list != null && !list.isEmpty()) {
                def.ignores = new HashSet<>(list);
            }

            long cdMs = sec.getInt("cooldown-millis", 45000);
            if (cdMs < 0) {
                cdMs = 0;
            }
            def.cooldown = cdMs;

            double hv = sec.getDouble("heal", 12.0);
            if (hv < 0) {
                hv = 0;
            }
            def.heal = hv;

            def.sound = sec.getString("sound", def.sound);
            def.particle = sec.getBoolean("particle", true);
            def.msg = sec.getString("message", def.msg);
            def.breakMsg = sec.getString("break-message", def.breakMsg);
        } catch (Throwable e) {
            return def;                            // 配置异常 → 用默认值，别让效果失效
        }

        gsDaCfg = def;
        return gsDaCfg;
    }

    /**
     * {@code gsAuraConfig()} —— 梦境套光环配置（原脚本 689~746 行）。
     *
     * <p>★ 保留的三处细节：①旧版配置键（start-radius / end-radius / steps）存在时
     * 打一条迁移提示；②{@code each-count} 钳到 1~24；③粒子名<b>强制跳过含 HEART 的</b>
     * （♥ 爱心粒子是需求明确排除的），最多 3 种。
     */
    public AuraConfig gsAuraConfig() {
        if (gsAuraCfg != null) {
            return gsAuraCfg;
        }

        AuraConfig def = new AuraConfig();

        try {
            FileConfiguration cfg = gsCfg();
            if (cfg == null) {
                return def;                        // 还没加载好 → 先用默认值，不缓存
            }

            ConfigurationSection sec = cfg.getConfigurationSection("dream-set.aura");
            if (sec == null) {
                gsAuraCfg = def;
                return gsAuraCfg;
            }

            def.enabled = sec.getBoolean("enabled", true);

            // 旧版配置提示：start-radius / end-radius / steps 是"聚拢版本"的键，现已弃用
            if (sec.contains("start-radius") || sec.contains("end-radius") || sec.contains("steps")) {
                gsDaLog("梦境盔甲：config.yml 的 aura 段还是旧版（聚拢），"
                        + "现在改成固定环绕 + 交叉排列 → 建议删掉 config.yml 让它重新生成");
            }

            def.eachCount = sec.getInt("each-count", 6);
            if (def.eachCount < 1) {
                def.eachCount = 1;
            }
            if (def.eachCount > 24) {
                def.eachCount = 24;
            }

            def.radius = sec.getDouble("radius", 0.80);
            def.heightHigh = sec.getDouble("height-high", 0.10);
            def.heightLow = sec.getDouble("height-low", 0.10);
            if (def.heightHigh < def.heightLow) {
                double tmp = def.heightHigh;
                def.heightHigh = def.heightLow;
                def.heightLow = tmp;
            }

            List<String> list = sec.getStringList("particles");
            if (list != null && !list.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (String s : list) {
                    if (out.size() >= 3) {         // 最多 3 种
                        break;
                    }
                    String n = s.toUpperCase(Locale.ROOT);
                    if (n.contains("HEART")) {     // ♥ 爱心粒子强制跳过
                        continue;
                    }
                    out.add(n);
                }
                if (!out.isEmpty()) {
                    def.particles = out;
                }
            }
        } catch (Throwable e) {
            return def;
        }

        gsAuraCfg = def;
        return gsAuraCfg;
    }

    // ======================================================================
    // 梦境套光环的世代号（原脚本 749~785 行）
    // ======================================================================

    /** {@code gsAuraGenWorld()} —— 世代号存在<b>第一个世界</b>的 PDC 上。 */
    public static World gsAuraGenWorld() {
        try {
            return Bukkit.getWorlds().get(0);
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsAuraGenBump()} —— 世代号 +1 并记下「本次加载拿到的是几号」。 */
    public void gsAuraGenBump() {
        try {
            World w = gsAuraGenWorld();
            if (w == null) {
                return;
            }
            PersistentDataContainer pdc = w.getPersistentDataContainer();
            Integer cur = pdc.get(GS_AURA_KEY, PersistentDataType.INTEGER);
            int gen = (cur == null ? 0 : cur) + 1;
            pdc.set(GS_AURA_KEY, PersistentDataType.INTEGER, gen);
            gsAuraMyGen = gen;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsAuraGenNow()} —— 当前世界 PDC 上的世代号；读不到返回 -1。 */
    public static int gsAuraGenNow() {
        try {
            World w = gsAuraGenWorld();
            if (w == null) {
                return -1;
            }
            Integer v = w.getPersistentDataContainer().get(GS_AURA_KEY, PersistentDataType.INTEGER);
            return (v == null) ? -1 : v;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * {@code gsAuraIsCurrent()} —— 本实例是不是"当前那一份"。
     *
     * <p>★ 不是就说明 {@code /rsc reload} 过了，旧任务该停手。
     * 这是原脚本用来让 {@code runRepeating} 停不掉的旧任务自动失效的机制。
     */
    public boolean gsAuraIsCurrent() {
        return (gsAuraMyGen == -1) || (gsAuraGenNow() == gsAuraMyGen);
    }

    // ======================================================================
    // 防辐射宽限期（原脚本 887~892 行）
    // ======================================================================

    /** {@code gsRadMark(uuid, on)} —— 标记/取消「本机制正在保护」。 */
    public void gsRadMark(String uuid, boolean on) {
        if (on) {
            gsRadSafe.put(uuid, Boolean.TRUE);
        } else {
            gsRadSafe.remove(uuid);
        }
    }

    // ======================================================================
    // 药效类型解析（原脚本 911~925 行）
    // ======================================================================

    /**
     * {@code gsSetPotionType(name, alt)} —— 药效名 → 枚举，带改名兼容。
     *
     * <p>★ 原脚本的 {@code alt} 是为「1.20.5+ 抗性叫 RESISTANCE，旧版叫 DAMAGE_RESISTANCE」
     * 准备的。Java 侧 {@code getByName} 覆盖同样的名字集合，语义一致。
     */
    public static PotionEffectType gsSetPotionType(String name, String alt) {
        PotionEffectType t = tryPotionType(name);
        if (t == null && alt != null) {
            t = tryPotionType(alt);
        }
        return t;
    }

    private static PotionEffectType tryPotionType(String n) {
        try {
            return PotionEffectType.getByName(n);
        } catch (Throwable e) {
            return null;
        }
    }

    // ======================================================================
    // 耐久保护（原脚本 473~534 行）
    // ======================================================================

    /** {@code gsIsNoDurabilityItem(item)} —— 是不是「永不掉耐久清单」里的东西。 */
    public static boolean gsIsNoDurabilityItem(ItemStack item) {
        if (item == null) {
            return false;
        }
        try {
            io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf =
                    io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem.getByItem(item);
            if (sf == null) {
                return false;
            }
            String id = String.valueOf(sf.getId());
            for (String s : GS_NODMG_IDS) {
                if (id.equals(s)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return false;
    }

    /**
     * {@code gsRepairWorn(st, id)} —— 把耐久补满（兜底：某些版本护甲受损不一定触发
     * ItemDamageEvent，所以每 10 刻也扫一遍装备栏）。
     *
     * <p>★ {@code id} 留空 = 只要在「永不了耐久清单」里就补（手上拿的用它）。
     */
    public static boolean gsRepairWorn(ItemStack st, String id) {
        if (st == null) {
            return false;
        }
        try {
            if (id == null || id.isEmpty()) {
                if (!gsIsNoDurabilityItem(st)) {
                    return false;
                }
            } else if (!gsIsItem(st, id)) {
                return false;
            }
        } catch (Throwable e) {
            return false;
        }
        try {
            ItemMeta meta;
            try {
                meta = st.getItemMeta();
            } catch (Throwable e2) {
                meta = null;
            }
            if (meta == null) {
                return false;
            }
            if (!(meta instanceof Damageable)) {
                return false;
            }
            Damageable d = (Damageable) meta;
            if (d.getDamage() <= 0) {
                return false;                      // 已满耐久 → 不动
            }
            d.setDamage(0);
            try {
                st.setItemMeta(meta);
            } catch (Throwable e2) {
                return false;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsNoDurabilitySweepAll()} —— 兜底扫描：把所有在线玩家身上磨损的
     * 释梦 / 梦境套 / 律师函 / 手上武器耐久补满（原脚本 560~593 行）。
     *
     * <p>★ 为什么需要它：某些版本护甲受损不一定触发 {@code PlayerItemDamageEvent}，
     * 挥空 / 砸方块也不一定走那个事件 ⇒ 每 10 刻（1 粘液刻）扫一遍兜底。
     *
     * <p>★ 三个逐字保留的细节：
     * <ol>
     *   <li>头盔槽用 {@code ||} 短路：{@code gsRepairWorn(st,'GS_DREAM_HELMET') || gsRepairWorn(st,'GS_THINKING_HAT')}
     *       —— 奇思妙想帽是<b>原版金头盔</b>，靠 id 判定而非槽位。</li>
     *   <li>胸甲槽同理带 {@code GS_LAWYER_LETTER}（黑色皮革胸甲，耐久很低）。</li>
     *   <li>主手 / 副手传 <b>null</b> ⇒ 走「只要在清单里就补」的分支
     *       （五骊矟 / 释梦这些<b>手上拿着</b>的）。</li>
     * </ol>
     *
     * <p>★ 只有真的补了（返回 true）才回写槽位 —— 避免每 10 刻把整份物品栈重写一遍。
     */
    public static void gsNoDurabilitySweepAll() {
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try {
                    var inv = p.getInventory();
                    ItemStack st;
                    st = inv.getHelmet();
                    if (gsRepairWorn(st, "GS_DREAM_HELMET") || gsRepairWorn(st, "GS_THINKING_HAT")) {
                        inv.setHelmet(st);
                    }
                    st = inv.getChestplate();
                    // ★ 律师函（黑色皮革胸甲）同样定期把耐久补满
                    if (gsRepairWorn(st, "GS_DREAM_ARMOR") || gsRepairWorn(st, "GS_LAWYER_LETTER")) {
                        inv.setChestplate(st);
                    }
                    st = inv.getLeggings();
                    if (gsRepairWorn(st, "GS_DREAM_LEGGINGS")) {
                        inv.setLeggings(st);
                    }
                    st = inv.getBoots();
                    if (gsRepairWorn(st, "GS_DREAM_BOOTS")) {
                        inv.setBoots(st);
                    }

                    // ★ 手上拿着的（五骊矟 / 释梦）也补一下：
                    //   武器掉耐久是 PlayerItemDamageEvent，某些版本挥空/砸方块
                    //   不走那个事件，所以这里兜底把 damage 归零。
                    try {
                        ItemStack mi = inv.getItemInMainHand();
                        if (gsRepairWorn(mi, null)) {
                            inv.setItemInMainHand(mi);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    try {
                        ItemStack oi = inv.getItemInOffHand();
                        if (gsRepairWorn(oi, null)) {
                            inv.setItemInOffHand(oi);
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 梦境套「四件齐全」（原脚本 900~910 行）
    // ======================================================================

    /**
     * {@code gsDreamFullSet(p)} —— 头盔 + 盔甲 + 护腿 + 战靴<b>四件齐全</b>。
     *
     * <p>★ 免死从「单件梦境盔甲」升级为套装效果，所以这个判定是免死的<b>前置条件</b>。
     */
    public static boolean gsDreamFullSet(Player p) {
        try {
            var inv = p.getInventory();
            return gsIsItem(inv.getHelmet(), "GS_DREAM_HELMET")
                    && gsIsItem(inv.getChestplate(), "GS_DREAM_ARMOR")
                    && gsIsItem(inv.getLeggings(), "GS_DREAM_LEGGINGS")
                    && gsIsItem(inv.getBoots(), "GS_DREAM_BOOTS");
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 时间（原脚本 6004 / 2999 / 315 行 —— 三个子系统各有一份，逐字相同）
    // ======================================================================

    /** {@code gsMsNow()} / {@code gsWuliNow()} / {@code gsDaNow()} —— 毫秒时间戳。 */
    public static long now() {
        return Guards.now();
    }

    // ======================================================================
    // 血条公共实现（原脚本 6194~6240 行）
    // ======================================================================

    /** {@code gsBarTitleSet(bar, title)} —— 统一入口，落地前强制成字符串。 */
    public static void gsBarTitleSet(BossBar bar, String title) {
        try {
            if (bar != null) {
                bar.setTitle(String.valueOf(title));
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBarTblHide(tbl, p)} —— 隐藏血条（不回收，仍留在表里）。 */
    public static void gsBarTblHide(Map<String, BarRec> tbl, Player p) {
        try {
            BarRec rec = tbl.get(String.valueOf(p.getUniqueId()));
            if (rec == null || rec.b == null) {
                return;
            }
            try {
                rec.b.setVisible(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBarTblDrop(tbl, p)} —— 回收血条：removeAll + 隐藏 + 从表里删掉。 */
    public static void gsBarTblDrop(Map<String, BarRec> tbl, Player p) {
        try {
            String u = String.valueOf(p.getUniqueId());
            BarRec rec = tbl.get(u);
            if (rec == null) {
                return;
            }
            try {
                rec.b.removeAll();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                rec.b.setVisible(false);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            tbl.remove(u);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsBarTblClean(tbl)} —— 清扫已下线的玩家（防血条对象泄漏）。 */
    public static void gsBarTblClean(Map<String, BarRec> tbl) {
        try {
            List<String> dead = new ArrayList<>();
            for (Map.Entry<String, BarRec> en : tbl.entrySet()) {
                BarRec rec = en.getValue();
                boolean on = false;
                try {
                    on = (rec != null && rec.p != null && rec.p.isOnline());
                } catch (Throwable e) {
                    on = false;
                }
                if (!on) {
                    try {
                        if (rec != null && rec.b != null) {
                            rec.b.removeAll();
                        }
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                    dead.add(en.getKey());
                }
            }
            for (String k : dead) {
                tbl.remove(k);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 新建一张血条表（各族一张，等价于原脚本的 {@code GS_XX_BARS = {}}）。 */
    public static Map<String, BarRec> newBarTable() {
        return new LinkedHashMap<>();
    }

    // ======================================================================
    // 月镰系的粒子 / 声音 / 向量工具（原脚本 6015~6098 行）
    // 被 6 个子系统共用，所以提到这里。
    // ======================================================================

    /** {@code gsMsLoc(world, x, y, z)}。 */
    public static Location gsMsLoc(World world, double x, double y, double z) {
        try {
            return new Location(world, x, y, z);
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsMsVec(x, y, z)}。 */
    public static Vector gsMsVec(double x, double y, double z) {
        try {
            return new Vector(x, y, z);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsMsSound(world, loc, name, vol, pitch)}。
     *
     * <p>★ 原脚本先试 {@code world.playSound(loc, ...)}，失败才降级
     * {@code loc.getWorld().playSound(...)}。Java 里两行是同一件事，但保留结构。
     */
    public static void gsMsSound(World world, Location loc, String name, float vol, float pitch) {
        try {
            try {
                world.playSound(loc, name, vol, pitch);
            } catch (Throwable e1) {
                try {
                    loc.getWorld().playSound(loc, name, vol, pitch);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** {@code gsMsSoundAt(p, name, vol, pitch)}。 */
    public static void gsMsSoundAt(Player p, String name, float vol, float pitch) {
        try {
            p.getWorld().playSound(p.getLocation(), name, vol, pitch);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsMsStarType()} —— 星星粒子，按 {@code FIREWORK → FIREWORK_SPARK → CRIT → END_ROD}
     * 顺序探测，<b>探测成功就永久缓存</b>。
     */
    public Particle gsMsStarType() {
        if (gsMsStar != null) {
            return gsMsStar;
        }
        try {
            String[] names = {"FIREWORK", "FIREWORK_SPARK", "CRIT", "END_ROD"};
            for (String n : names) {
                try {
                    Particle t = Particle.valueOf(n);
                    if (t != null) {
                        gsMsStar = t;
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsMsDustType()} —— 红石粒子，按 {@code DUST → REDSTONE} 探测。
     *
     * <p>★ 注意 {@code GS_MS_DUST_TRIED}：探测<b>只做一次</b>，
     * 失败后不再重试（返回 null 且不再进 try 块）。这个闩锁必须保留。
     */
    public Particle gsMsDustType() {
        if (gsMsDust != null) {
            return gsMsDust;
        }
        if (gsMsDustTried) {
            return null;
        }
        gsMsDustTried = true;
        try {
            String[] names = {"DUST", "REDSTONE"};
            for (String n : names) {
                try {
                    Particle t = Particle.valueOf(n);
                    if (t != null) {
                        gsMsDust = t;
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * {@code gsMsDustOpts(r, g, b, size)} —— DUST 必须带 DustOptions。
     *
     * <p>★ 颜色分量做了 {@code (v | 0) & 255}：先截成 int 再取低 8 位，
     * 负数也能正确回绕（如 -1 → 255）。{@code size <= 0} 或 null 时用 1.0。
     */
    public static Particle.DustOptions gsMsDustOpts(double r, double g, double b, Double size) {
        try {
            Color col = Color.fromRGB(((int) r) & 255, ((int) g) & 255, ((int) b) & 255);
            double sz = (size == null || size <= 0) ? 1.0 : size;
            return new Particle.DustOptions(col, (float) sz);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * {@code gsMsDust(world, pt, data, x, y, z)} —— 单点粒子。
     *
     * <p>★ 带 data 先试一次，失败再试不带 data 的 —— 这个降级必须保留，
     * 因为给非 DUST 粒子传 DustOptions 会抛异常。
     */
    public static void gsMsDust(World world, Particle pt, Particle.DustOptions data,
                               double x, double y, double z) {
        try {
            if (pt == null || world == null) {
                return;
            }
            if (data != null) {
                try {
                    world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0, data);
                    return;
                } catch (Throwable ignored) {
                    // 降级到不带 data
                }
            }
            try {
                world.spawnParticle(pt, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 伤害器（原脚本 6373~6393 行）—— 5 个子系统共用
    // ======================================================================

    /**
     * {@code gsMsHurt(ent, dmg, p)} —— 造成伤害，且<b>不留无敌帧</b>。
     *
     * <p>★★ 三个必须逐字保留的细节：
     * <ol>
     *   <li>先 {@code setNoDamageTicks(0)} <b>再</b>读速度、<b>再</b>打伤害、
     *       最后把速度写回去 —— 这样伤害的击退不会把自定义击退冲掉。</li>
     *   <li>{@code GS_WPN_DMG_GUARD++ / --} 用 <b>try/finally</b> 包住 ——
     *       我们自己造成的伤害会被「武器禁近战」等监听器识别并放过。</li>
     *   <li>打完<b>再</b> {@code setNoDamageTicks(0)} —— 否则下一次连击会被原版无敌帧吃掉。</li>
     * </ol>
     */
    public void gsMsHurt(Entity ent, double dmg, Player p) {
        try {
            if (ent == null) {
                return;
            }
            // ★ 原脚本是 JS 动态派发：ent 上有没有 setNoDamageTicks/damage 运行时才知道。
            //   Java 侧 setNoDamageTicks 在 LivingEntity 上、damage 在 Damageable 上
            //   （LivingEntity 继承 Damageable）⇒ 一次转型两者都能用；
            //   转不过去就与「原脚本调一个不存在的方法」等价（抛异常 → 被外层 try 吞掉）。
            LivingEntity d = (LivingEntity) ent;
            try {
                d.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            double vx = 0.0, vy = 0.0, vz = 0.0;
            boolean has = false;
            try {
                Vector v = ent.getVelocity();
                vx = v.getX();
                vy = v.getY();
                vz = v.getZ();
                has = true;
            } catch (Throwable e) {
                has = false;
            }
            gsWpnDmgGuard++;
            try {
                try {
                    if (p != null) {
                        d.damage(dmg, p);
                    } else {
                        d.damage(dmg);
                    }
                } catch (Throwable e1) {
                    try {
                        d.damage(dmg);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            } finally {
                gsWpnDmgGuard--;
            }
            if (has) {
                try {
                    ent.setVelocity(gsMsVec(vx, vy, vz));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
            try {
                d.setNoDamageTicks(0);             // ★ 打完不留无敌帧
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 定身（原脚本 6494~6518 行）
    // ======================================================================

    /**
     * {@code gsMsLockFor(ent, ms, col)} —— 把生物钉在原地 ms 毫秒。
     *
     * <p>★ 两个语义细节：
     * <ol>
     *   <li>已在名单里 ⇒ <b>只刷新到期时间</b>，位置钉在<b>第一次</b>锁定的那一点
     *       （否则每打一下就跟着跑，定身形同虚设）。</li>
     *   <li>记录原来的 {@code hasAI()}，解锁时原样还原。</li>
     * </ol>
     */
    public void gsMsLockFor(Entity ent, long ms, Color col) {
        try {
            if (ent == null) {
                return;
            }
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                return;
            }
            long now = now();
            for (Lock l : gsMsLocks) {
                if (l.id.equals(id)) {
                    l.until = now + ms;
                    if (col != null) {
                        l.col = col;
                    }
                    return;
                }
            }
            Location loc;
            try {
                loc = ent.getLocation();
            } catch (Throwable e) {
                return;
            }
            boolean ai = false;
            try {
                if (ent instanceof LivingEntity) {
                    ai = ((LivingEntity) ent).hasAI();
                }
            } catch (Throwable e) {
                ai = false;
            }
            try {
                if (ent instanceof LivingEntity) {
                    ((LivingEntity) ent).setAI(false);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            try {
                ent.setVelocity(gsMsVec(0.0, 0.0, 0.0));
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            Lock l = new Lock();
            l.e = ent;
            l.id = id;
            l.w = loc.getWorld();
            l.x = loc.getX();
            l.y = loc.getY();
            l.z = loc.getZ();
            l.until = now + ms;
            l.ai = ai;
            l.col = col;
            gsMsLocks.add(l);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 战斗目标判定（原脚本 8032~8042 行）
    // ======================================================================

    /**
     * {@code gsIsCombatTarget(ent, tn)} —— 这个实体算不算「战斗目标」。
     *
     * <p>★ 原脚本在拿不到 {@code LivingEntity} 类信息时<b>返回 true</b>
     * （「交给各模块自己的 getHealth() 探针」）。Java 里类必然拿得到，
     * 所以等价于 {@code ent instanceof LivingEntity}。
     */
    public static boolean gsIsCombatTarget(Entity ent, String tn) {
        try {
            if (tn != null && !tn.isEmpty() && GS_NOT_ENEMY_TYPES.contains(tn)) {
                return false;
            }
            return ent instanceof LivingEntity;
        } catch (Throwable e) {
            return true;
        }
    }

    // ======================================================================
    // 此刻万籁俱寂：伤害公式（原脚本 8690~8700 行）
    // ======================================================================

    /**
     * {@code gsSiDmg(n)} —— 连击层数 → 伤害。
     *
     * <p>★ {@code k} 钳到 1~{@code GS_SI_MAX}，公式 {@code BASE + PER * (k-1)}。
     * 参数由「此刻万籁俱寂」子系统提供，这里只保留算法本体。
     */
    public static double gsSiDmg(int n, int max, double base, double per) {
        int k = n;
        if (k < 1) {
            k = 1;
        }
        if (k > max) {
            k = max;
        }
        return base + per * (k - 1);
    }

    // ======================================================================
    // 附魔粒子（原脚本 7857~7872 行）
    // ======================================================================

    /**
     * {@code gsVoEnchType()} —— 附魔粒子，按
     * {@code ENCHANT → ENCHANTMENT_TABLE → ENCHANTING_TABLE} 探测。
     *
     * <p>★ 与 {@code gsMsDustType} 一样有「只试一次」的闩锁。
     */
    public Particle gsVoEnchType() {
        if (gsVoEnch != null) {
            return gsVoEnch;
        }
        if (gsVoEnchTried) {
            return null;
        }
        gsVoEnchTried = true;
        try {
            String[] names = {"ENCHANT", "ENCHANTMENT_TABLE", "ENCHANTING_TABLE"};
            for (String n : names) {
                try {
                    Particle t = Particle.valueOf(n);
                    if (t != null) {
                        gsVoEnch = t;
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 试下一个
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    // ======================================================================
    // 五骊矟：PDC 冷却（原脚本 3004~3024 行）
    // 与 {@link Guards.Cooldown} 的区别：这个<b>存 PDC</b>，所以 /rsc reload 不丢。
    // ======================================================================

    /** {@code gsWuliCdLeft(p, key, cdMs)} —— 剩余冷却毫秒。 */
    public static long gsWuliCdLeft(Player p, NamespacedKey key, long cdMs) {
        try {
            if (key == null || cdMs <= 0) {
                return 0;
            }
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            if (pdc == null) {
                return 0;
            }
            Long last = pdc.get(key, PersistentDataType.LONG);
            if (last == null) {
                return 0;
            }
            long left = cdMs - (now() - last);
            return left > 0 ? left : 0;
        } catch (Throwable e) {
            return 0;
        }
    }

    /** {@code gsWuliMarkCd(p, key)} —— 记下「刚用过」。 */
    public static void gsWuliMarkCd(Player p, NamespacedKey key) {
        try {
            if (key == null) {
                return;
            }
            PersistentDataContainer pdc = p.getPersistentDataContainer();
            if (pdc != null) {
                pdc.set(key, PersistentDataType.LONG, now());
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 「是不是我们召唤的」（原脚本 3074~3082 / 1125~1153 行）
    // ======================================================================

    /** {@code gsIsOurSummon(ent)} —— 五骊骑阵召唤的马。 */
    public static boolean gsIsOurSummon(Entity ent, NamespacedKey summonKey) {
        try {
            if (ent == null || summonKey == null) {
                return false;
            }
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            return pdc.has(summonKey, PersistentDataType.STRING);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsIsMaiyouSummon(ent)} —— 麦友的酒召唤物。
     *
     * <p>★ 先查 {@code STRING} 再查 {@code BYTE}：<b>兼容旧标记</b>，
     * 老存档里是用 BYTE 写的。少查一次就会让旧召唤物失控。
     */
    public static boolean gsIsMaiyouSummon(Entity ent, NamespacedKey maiyouKey) {
        try {
            if (ent == null || maiyouKey == null) {
                return false;
            }
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            try {
                if (pdc.has(maiyouKey, PersistentDataType.STRING)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 继续查 BYTE
            }
            try {
                if (pdc.has(maiyouKey, PersistentDataType.BYTE)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 逐段碰撞检测（原脚本 5137~5154 行）—— 威龙的 TNT 用
    // ======================================================================

    /**
     * {@code gsWlBlockedAt(world, x, y, z)} —— 这个坐标是不是被方块挡住。
     *
     * <p>★★ 注意原脚本的<b>不可达代码</b>：第 514 行 {@code return !blk.isPassable();}
     * <b>无条件返回</b>，所以它后面的 {@code if (mt != null) return !!mt.isSolid();}
     * 永远走不到。<b>这是死代码，但必须保留</b> —— 它的存在证明作者的原意，
     * 而且 {@code isPassable()} 就是实际生效的判定。Java 版保留同样的结构 + 注释。
     */
    public static boolean gsWlBlockedAt(World world, double x, double y, double z) {
        try {
            var blk = world.getBlockAt(new Location(world, x, y, z));
            if (blk == null) {
                return false;
            }
            try {
                if (blk.getType().isAir()) {
                    return false;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            return !blk.isPassable();
            // ★ 以下为原脚本的死代码（第 515~516 行），永不执行，保留以存档原意：
            // if (mt != null) { try { return !!mt.isSolid(); } catch (e) { } }
            // return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ======================================================================
    // 图腾粒子（原脚本 295~308 行）
    // ======================================================================

    /**
     * {@code gsTotemParticles(p)} —— 不死图腾粒子。
     *
     * <p>★ 原脚本做了跨版本探测（{@code TOTEM_OF_UNDYING} → {@code TOTEM}）。
     * Java 里 {@code TOTEM_OF_UNDYING} 必然存在，但保留降级结构以便对账。
     */
    public static void gsTotemParticles(Player p) {
        try {
            Particle t = null;
            try {
                t = Particle.TOTEM_OF_UNDYING;
            } catch (Throwable ignored) {
                // 试下一个
            }
            if (t == null) {
                try {
                    t = Particle.valueOf("TOTEM");
                } catch (Throwable ignored) {
                    // 放弃
                }
            }
            if (t == null) {
                return;
            }

            Location loc = p.getLocation();
            p.getWorld().spawnParticle(t, loc.getX(), loc.getY() + 1.0, loc.getZ(),
                    40, 0.6, 0.9, 0.6, 0.15);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 属性工具（原脚本里反复出现的「取最大生命 / 设伤害」）
    // ======================================================================

    /** 取实体属性实例（拿不到返回 null）。 */
    public static AttributeInstance attr(Entity ent, Attribute a) {
        try {
            if (!(ent instanceof LivingEntity)) {
                return null;
            }
            return ((LivingEntity) ent).getAttribute(a);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 玩家的 uuid 字符串（原脚本到处用 {@code '' + p.getUniqueId()}）。 */
    public static String uid(Player p) {
        try {
            return String.valueOf(p.getUniqueId());
        } catch (Throwable e) {
            return "";
        }
    }

    /** 实体的 uuid 字符串。 */
    public static String uid(Entity e) {
        try {
            return String.valueOf(e.getUniqueId());
        } catch (Throwable e2) {
            return "";
        }
    }

    /** 实体类型名（原脚本到处用 {@code '' + ent.getType().name()}）。 */
    public static String typeName(Entity e) {
        try {
            return String.valueOf(e.getType().name());
        } catch (Throwable e2) {
            return "";
        }
    }

    /** 记分板标签判定（原脚本 {@code ent.getScoreboardTags().contains(tag)}）。 */
    public static boolean hasTag(Entity e, String tag) {
        try {
            Set<String> tags = e.getScoreboardTags();
            return tags != null && tags.contains(tag);
        } catch (Throwable e2) {
            return false;
        }
    }
}
