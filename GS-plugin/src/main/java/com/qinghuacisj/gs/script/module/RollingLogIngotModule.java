package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.content.Content;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * {@code gs_rolling_log_ingot.js} 的 Java 化版本 —— 滚木锭。
 *
 * <p>效果：右键后，<b>自己前方 宽3 × 长5</b> 区域内所有生物（含自己）获得
 * 「隐身」10 秒；物品不消耗；同一玩家 9 秒冷却；区域高度 = 自己所在层 ±1。
 * 参数可在 {@code config.yml} 的 {@code rolling-log-ingot} 段调。
 *
 * <p>★ 这个脚本在 {@code items.yml} 里是<b>注释掉</b>的（第三十六批起停用），
 * 但脚本文件仍在、仍可被引用。照样转换 —— 台账要完整。
 *
 * <p>★★ 三个转换时最容易写错、已逐条对齐的点：
 * <ol>
 *   <li><b>浮点除法</b>：原脚本的 {@code (minX+maxX)/2 + 0.5} 在 JS 里是浮点除法。
 *       Java 里若写成 {@code /2} 会变成<b>整数除法</b>，中心点会偏 0.5 格、
 *       半径也会算错。这里一律写 {@code / 2.0}。</li>
 *   <li><b>每次新建 PotionEffect</b>：原脚本特意注释「避免多个实体共用同一个对象」，
 *       所以 {@link #newEffect(int)} 每次 new，不能缓存复用。</li>
 *   <li><b>盔甲架跳过</b>：{@code ArmorStand} 也是 {@code LivingEntity}，
 *       必须单独排除（原注释：「别把装饰变没」）。</li>
 * </ol>
 *
 * <p>★ 冷却表 {@code gsRllCooldown} 是原脚本的顶层对象（字符串键），
 * 这里对应为成员字段 {@link #cooldown}；因为是按脚本名缓存的单例，
 * 所以「同一玩家共享冷却」的语义与现网一致。
 */
public final class RollingLogIngotModule extends AbstractModule {

    /** uuid → 冷却结束时间戳（毫秒）。等价于原脚本的 {@code gsRllCooldown}。 */
    private final Map<String, Long> cooldown = new HashMap<>();

    @Override
    public String name() {
        return "gs_rolling_log_ingot";
    }

    // ------------------------------------------------------------ 配置

    /** 配置快照，等价于原脚本 {@code gsRllCfg()} 返回的对象字面量。 */
    static final class Cfg {
        int width = 3;
        int length = 5;
        int invisSeconds = 10;
        int cooldownSeconds = 9;
    }

    /**
     * 钳制配置值 —— 原脚本 {@code gsRllCfg()} 尾部那 5 个 {@code if}。
     *
     * <p>★★ <b>这段是几何正确性的前提</b>：它保证 {@code width} 一定是<b>奇数</b>，
     * 于是 {@link #computeArea} 里的 {@code (width - 1) / 2} 才是精确整除。
     * 原脚本的 {@code half} 在 JS 里是浮点数，若 {@code width} 为偶数，
     * {@code half} 会带 .5、循环变量也随之带 .5，拼出来的格子键形如
     * {@code "1.5,64,2"} —— 而实体判定用的是 {@code getBlockX()} 这类<b>整数</b>，
     * 所以那些半格键<b>永远匹配不上</b>，纯属死格子。
     * 既然钳制保证了奇数，Java 的整数除法与原脚本<b>逐值等价</b>。
     */
    static Cfg clamp(Cfg o) {
        if (o.width < 1) {
            o.width = 1;
        }
        if (o.width % 2 == 0) {
            o.width += 1;      // 保证是奇数，才能以自己为中心平分左右
        }
        if (o.length < 1) {
            o.length = 1;
        }
        if (o.invisSeconds < 1) {
            o.invisSeconds = 1;
        }
        if (o.cooldownSeconds < 0) {
            o.cooldownSeconds = 0;
        }
        return o;
    }

    /** 读取配置（拿不到就用默认值）—— 原脚本整段包在 try/catch 里且静默。 */
    private static Cfg readCfg() {
        Cfg o = new Cfg();
        try {
            YamlConfiguration cfg = Content.config();
            if (cfg != null) {
                o.width = cfg.getInt("rolling-log-ingot.width", 3);
                o.length = cfg.getInt("rolling-log-ingot.length", 5);
                o.invisSeconds = cfg.getInt("rolling-log-ingot.invis-seconds", 10);
                o.cooldownSeconds = cfg.getInt("rolling-log-ingot.cooldown-seconds", 9);
            }
        } catch (Throwable ignored) {
            // 配置缺失：用默认值（与原脚本一致：静默）
        }
        return clamp(o);
    }

    // ------------------------------------------------------------ 工具

    /**
     * 当前毫秒时间戳。
     *
     * <p>原脚本用 {@code Java.type('java.time.Instant')} 绕过「{@code System} 被 RSC 屏蔽」，
     * 并返回 0 表示不可用。Java 里直接用 {@code System.currentTimeMillis()}，
     * 于是调用处的 {@code now > 0} 判断恒真 —— 这是<b>刻意保留</b>的结构，
     * 免得将来对照原脚本时看不出对应关系。
     */
    private static long now() {
        return System.currentTimeMillis();
    }

    /** 每次新建一个效果实例，避免多个实体共用同一个对象。 */
    private static PotionEffect newEffect(int ticks) {
        return new PotionEffect(PotionEffectType.INVISIBILITY, ticks, 0, false, false, true);
    }

    /** 把 yaw 转成水平朝向的单位向量（MC：0=南 +Z，90=西 -X，180=北 -Z，270=东 +X）。 */
    static int[] facing(float yaw) {
        float y = ((yaw % 360f) + 360f) % 360f;
        if (y >= 315f || y < 45f) {
            return new int[]{0, 1};
        }
        if (y < 135f) {
            return new int[]{-1, 0};
        }
        if (y < 225f) {
            return new int[]{0, -1};
        }
        return new int[]{1, 0};
    }

    /**
     * 前方 宽×长 区域的格子集合与水平包围盒 —— 原脚本那段双层循环的产物。
     *
     * <p>抽成包内静态方法是为了能被 {@code tools/ParityProbe} 拿真 JS 对账
     * （几何 + 浮点边界是最容易写错的地方）。
     *
     * <p>★ 格子键的格式必须与 JS 的 {@code cx + "," + (by+dy) + "," + cz} 逐字一致
     * （JS 里整数拼接不带小数点，Java 的 int 拼接同理）。
     */
    static final class Area {
        final Set<String> cells;
        final int minX;
        final int maxX;
        final int minZ;
        final int maxZ;

        Area(Set<String> cells, int minX, int maxX, int minZ, int maxZ) {
            this.cells = cells;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }

    static Area computeArea(int bx, int by, int bz, float yaw, int width, int length) {
        int[] f = facing(yaw);
        int fx = f[0];
        int fz = f[1];
        int lx = -fz;
        int lz = fx;                              // 侧向（左/右）
        int half = (width - 1) / 2;               // 3 宽 → 左右各 1

        Set<String> cells = new HashSet<>();
        int minX = bx, maxX = bx, minZ = bz, maxZ = bz;

        for (int d = 1; d <= length; d++) {
            for (int w = -half; w <= half; w++) {
                int cx = bx + fx * d + lx * w;
                int cz = bz + fz * d + lz * w;
                for (int dy = -1; dy <= 1; dy++) {
                    cells.add(cx + "," + (by + dy) + "," + cz);
                }
                if (cx < minX) {
                    minX = cx;
                }
                if (cx > maxX) {
                    maxX = cx;
                }
                if (cz < minZ) {
                    minZ = cz;
                }
                if (cz > maxZ) {
                    maxZ = cz;
                }
            }
        }
        return new Area(cells, minX, maxX, minZ, maxZ);
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        Player p = event.getPlayer();
        if (p == null) {
            return null;
        }

        Cfg cfg = readCfg();
        long time = now();
        String uuid = p.getUniqueId().toString();

        // ---------------- 冷却检查 ----------------
        if (cfg.cooldownSeconds > 0 && time > 0) {
            Long next = cooldown.get(uuid);
            if (next != null && time < next) {
                // ★ 第六十八批：冷却中的聊天提示词已按需求删除，冷却中直接静默返回
                return null;
            }
        }

        // ---------------- 算出前方 宽×长 的区域集合 ----------------
        Location loc = p.getLocation();
        World world = p.getWorld();
        int bx = loc.getBlockX();
        int by = loc.getBlockY();
        int bz = loc.getBlockZ();

        Area area = computeArea(bx, by, bz, loc.getYaw(), cfg.width, cfg.length);
        Set<String> cells = area.cells;
        int minX = area.minX;
        int maxX = area.maxX;
        int minZ = area.minZ;
        int maxZ = area.maxZ;

        // ---------------- 收集范围内的生物 ----------------
        // ★ 除以 2.0（不是 2）：原脚本是 JS 浮点除法
        double centerX = (minX + maxX) / 2.0 + 0.5;
        double centerZ = (minZ + maxZ) / 2.0 + 0.5;
        double rx = (maxX - minX) / 2.0 + 0.6;
        double rz = (maxZ - minZ) / 2.0 + 0.6;
        Location center = new Location(world, centerX, by + 0.5, centerZ);

        int ticks = cfg.invisSeconds * 20;

        try {
            for (Entity e : world.getNearbyEntities(center, rx, 1.6, rz)) {
                if (!(e instanceof LivingEntity)) {
                    continue;                  // 只对生物
                }
                if (e instanceof ArmorStand) {
                    continue;                  // 盔甲架跳过，别把装饰变没
                }
                Location el = e.getLocation();
                if (!cells.contains(el.getBlockX() + "," + el.getBlockY() + "," + el.getBlockZ())) {
                    continue;
                }
                // ★ 必须转成 LivingEntity：addPotionEffect 在 LivingEntity 上，
                //   不在 Entity 上。JS 是动态派发，Java 得显式转型。
                ((LivingEntity) e).addPotionEffect(newEffect(ticks));
            }
        } catch (Throwable ignored) {
            // 范围实体查询失败也不影响自己拿到效果（与原脚本一致：静默）
        }

        // ---------------- 自己也算（"包括自身"）----------------
        p.addPotionEffect(newEffect(ticks));

        // ---------------- 记录冷却 ----------------
        if (cfg.cooldownSeconds > 0 && time > 0) {
            cooldown.put(uuid, time + cfg.cooldownSeconds * 1000L);
        }

        try {
            p.playSound(loc, "entity.illusioner.cast_spell", 0.7f, 1.3f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        // 物品不消耗：什么都不用做
        return null;
    }
}
