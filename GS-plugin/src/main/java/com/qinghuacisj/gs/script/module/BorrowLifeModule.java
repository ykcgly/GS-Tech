package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code gs_borrow_life.js} 的 Java 化版本 —— 「借寿」。
 *
 * <p>右键：对准星方向<b>长 5 × 宽 5 × 高 2</b> 范围内的所有生物造成 <b>1 点</b>伤害，
 * 冷却 <b>0.5 秒</b>（并消失无敌帧）；范围内命中 n 只 ⇒ 自己回 <b>1×n</b> 点血；
 * 被命中的生物会在 0.5 秒内从身上冒出红色粒子飞向玩家（吸血效果）。
 *
 * <p>★★ <b>判定盒：完整三维朝向</b>（沿准星展开，抬头/低头都能打）
 * <pre>
 *   f = 准星方向（含俯仰，已归一化）        ⇒ 长 5 沿 f
 *   r = 水平右向（永远水平，不随俯仰倾斜）  ⇒ 宽 5 沿 r
 *   u = r × f（垂直于 f 与 r 的"上"）      ⇒ 高 2 沿 u
 *
 *   投影（原点 = 自己胸口「脚下 +1.0」，怪的取样点 = 「脚下 +0.9」）：
 *     fwd = d·f ∈ [-0.4, 5.0]   （身后留 0.4 格容差，免得贴身怪打不到）
 *     lat = d·r ∈ [-2.5, +2.5]
 *     ver = d·u ∈ [-1.0, +1.0]
 * </pre>
 * ★ <b>平视（pitch = 0）时与旧版逐点等价</b>：f=(0,0,1) / r=(−1,0,0) / u=(0,1,0)
 * ⇒ fwd = dz、lat = −dx、ver = dy − 0.1 ⇒ 竖直区间仍是 dy ∈ [−0.9, +1.1]。
 * 这条已由 {@code tools/BorrowLifeParityProbe.java} 用真 JS 钉住。
 *
 * <p>★★ <b>搜索盒必须按外接球算</b>：判定体是三维旋转的、而
 * {@code getNearbyEntities} 的盒子是世界轴对齐的 ⇒ 半宽取
 * √((长/2)² + (宽/2)² + (高/2)²)，不能再只算水平的 √((长/2)²+(宽/2)²)，
 * 否则抬头时高处/低处的怪会「搜不到」，表现为「明明在范围内却打不中」。
 *
 * <p>★★ <b>去抖必须「只查、不武装」</b>：一次右键会连发两个事件（主手/副手），
 * 所以要 150ms 去抖。但<b>不能每次按都刷新窗口</b> —— 冷却期间被挡掉的连点会把窗口
 * 一直往后推，导致<b>冷却刚好结束的那一下被误吞</b>。
 * 正确做法是拆成两步：{@link #isDuplicate} 只读，
 * 只有「这次真的生效」时才 {@link #armDebounce}。
 * （原脚本注释里记着实例：0/100/300/450/510 ms 连点，510ms 那一下本该打出去却被吞。
 * 本移植用 {@code BorrowLifeParityProbe} 把这条时序也钉住了。）
 *
 * <p>★ <b>没有施法提示词</b>（第六十二批用户明确要求删除）—— 施法<b>只出声、不写字</b>。
 * 聊天栏与动作栏都不会冒提示词，这是<b>反向断言</b>：不许再出现。
 *
 * <p>★ 物品<b>不消耗</b>；默认<b>不打其它玩家</b>（{@link #BL_HIT_PLAYERS} = false）。
 */
public final class BorrowLifeModule extends AbstractModule {

    // ---------------- 参数 ----------------
    static final String BL_ITEM_ID = "GS_BORROW_LIFE";

    static final double BL_RANGE = 5.0;      // 长（沿准星方向 5 格，含俯仰）
    static final double BL_WIDE = 5.0;       // 宽（水平右向左右各 2.5 格）
    static final double BL_H = 2.0;          // 高（沿 u = r×f 上下各 1 格）
    static final double BL_BACK = 0.4;       // 身后容差（贴身怪也算打得到）

    static final double BL_DMG = 1.0;        // ★ 每只生物 1 点伤害
    static final double BL_HEAL_PER = 1.0;   // ★ 每命中一只回 1 点血
    static final boolean BL_HIT_PLAYERS = false;  // false = 不打其它玩家

    static final long BL_CD_MS = 500L;           // ★ 冷却 0.5 秒
    static final long BL_DEBOUNCE_MS = 150L;     // 去抖（一次右键可能连发两个事件）
    /** ★ 恒为 false ⇒ {@link #cooldownHint} 是空操作（见类注释）。 */
    static final boolean BL_CD_HINT = false;

    static final int BL_SIP_TICKS = 10;          // ★ 吸血粒子飞 10 刻 = 0.5 秒
    static final int BL_SIP_PER_TICK = 2;        // 每只怪每刻撒几个粒子
    static final double BL_SIP_ARC = 0.35;       // 飞行路径的弧度（0 = 直线）
    static final double BL_SIP_STAGGER = 0.04;   // 每只怪错开一点

    /** 施法音效（把 {@link #BL_SOUND} 设成 null 可关）。 */
    static final String BL_SOUND = "entity.evoker.cast_spell";

    /** 目标筛选黑名单（按 {@code EntityType} 名字）。 */
    static final String[] BL_SKIP = {
            "DROPPED_ITEM", "ITEM", "EXPERIENCE_ORB", "ARROW", "SPECTRAL_ARROW",
            "ARMOR_STAND", "ITEM_FRAME", "GLOW_ITEM_FRAME", "PAINTING",
            "ITEM_DISPLAY", "BLOCK_DISPLAY", "TEXT_DISPLAY", "INTERACTION",
            "FALLING_BLOCK", "PRIMED_TNT", "END_CRYSTAL", "BOAT", "MINECART",
            "LEASH_HITCH", "MARKER", "EGG", "SNOWBALL", "ENDER_PEARL",
            "FIREBALL", "SMALL_FIREBALL", "DRAGON_FIREBALL", "WITHER_SKULL",
            "LLAMA_SPIT", "SHULKER_BULLET", "TRIDENT", "FISHING_BOBBER"
    };

    private static final Set<String> NOT_TARGET = new HashSet<>(List.of(BL_SKIP));

    /** 暗红粒子（{@code Particle.DUST} + {@code DustOptions}）。 */
    private static final Particle.DustOptions DUST =
            new Particle.DustOptions(Color.fromRGB(200, 16, 16), 0.9f);

    // ---------------- 冷却 / 去抖（都按玩家记） ----------------
    /** uuid → 上次成功使用的时间戳(ms)。 */
    private final Guards.Cooldown last = new Guards.Cooldown(BL_CD_MS);
    /** uuid → 上次按下去的时间戳(ms)。 */
    private final Map<String, Long> touch = new HashMap<>();

    /** 吸血粒子链：chainId → 状态。★ {@code BL_GEN} 只增不减，用世代号断链。 */
    private final Map<String, Sip> sip = new HashMap<>();
    private long gen = 0L;

    public BorrowLifeModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] 借寿 已加载 · 沿准星三维朝向 长5×宽5×高2"
                    + "（抬头/低头也能打）· 1 点伤害 / 冷却 0.5 秒 / 命中 n 只回 n 点血 / "
                    + "吸血粒子 0.5 秒 / ★ 施法提示词已按第六十二批要求**删除**（只出声不写字）");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_borrow_life";
    }

    // ------------------------------------------------------------ 冷却 / 去抖

    /**
     * 这次按键是不是「同一次右键的第二个事件」。
     *
     * <p>★ <b>只读</b>，不刷新窗口 —— 见类注释里那条「冷却结束那一下会被误吞」的坑。
     */
    boolean isDuplicate(Player p) {
        try {
            if (BL_DEBOUNCE_MS <= 0) {
                return false;
            }
            Long v = touch.get(p.getUniqueId().toString());
            if (v == null) {
                return false;
            }
            return (v + BL_DEBOUNCE_MS) > Guards.now();
        } catch (Throwable e) {
            return false;
        }
    }

    /** 武装去抖窗口。★ 只在「这次真的生效」时调用。 */
    void armDebounce(Player p) {
        try {
            touch.put(p.getUniqueId().toString(), Guards.now());
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 冷却中的提示。
     *
     * <p>★ 原脚本 {@code blCooldownHint} 的第一行就是 {@code if (!BL_CD_HINT) return;}，
     * 而 {@link #BL_CD_HINT} 恒为 {@code false} ⇒ <b>整个函数是空操作</b>。
     * 这里保留这个开关、不实现提示体（死代码，见类注释），
     * 可观察行为与原脚本一致：冷却中<b>什么都不显示</b>。
     */
    private static void cooldownHint(Player p, long left) {
        if (!BL_CD_HINT) {
            return;
        }
        // 开关打开时的行为（原脚本写法，保留以便日后需要）：
        try {
            long s = (long) Math.ceil(left / 1000.0);
            String msg = org.bukkit.ChatColor.GOLD + "借寿 · 冷却中，还剩 " + s + " 秒";
            try {
                p.sendActionBar(msg);
            } catch (Throwable e2) {
                try {
                    p.sendMessage(msg);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
    }

    // ------------------------------------------------------------ 粒子

    /** 撒一个红色粒子（DUST 优先，失败就退到不需要 data 的 DAMAGE_INDICATOR）。 */
    private static void dust(World world, double x, double y, double z) {
        try {
            if (world == null) {
                return;
            }
            try {
                world.spawnParticle(Particle.DUST, x, y, z, 1, 0.0, 0.0, 0.0, DUST);
                return;
            } catch (Throwable e1) {
                // 退路
            }
            try {
                world.spawnParticle(Particle.DAMAGE_INDICATOR, x, y, z, 1, 0.0, 0.0, 0.0);
            } catch (Throwable e2) {
                // 与原脚本一致：静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 目标筛选

    private static boolean isCombatTarget(Entity ent, String tn) {
        try {
            if (tn != null && !tn.isEmpty() && NOT_TARGET.contains(tn)) {
                return false;
            }
            // ★ 原脚本用反射 isInstance；Java 里就是 instanceof（永不抛异常）
            return ent instanceof LivingEntity;
        } catch (Throwable e) {
            return true;
        }
    }

    private static boolean canHit(Entity ent, String selfId) {
        try {
            if (ent == null) {
                return false;
            }
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                return false;
            }
            if (id.equals(selfId)) {
                return false;
            }
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                return false;
            }
            if ("PLAYER".equals(tn) && !BL_HIT_PLAYERS) {
                return false;
            }
            if (!isCombatTarget(ent, tn)) {
                return false;
            }
            try {
                ((LivingEntity) ent).getHealth();
            } catch (Throwable e) {
                return false;
            }
            try {
                if (ent.isDead()) {
                    return false;
                }
            } catch (Throwable ignored) {
                // ★ 原脚本这里是空 catch（不 return）—— 保留
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 伤害器：1 点 · 无击退 · 消失无敌帧

    private static boolean hurt(LivingEntity ent, double dmg, Player p) {
        try {
            if (ent == null) {
                return false;
            }
            // ★ 「消失无敌帧」：打之前先清，保证这一下一定打得动
            try {
                ent.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 静默
            }

            // 先存速度 —— 打完立刻还原 ⇒ 同刻内位移被抵消（= 不击退）
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;
            boolean has;
            try {
                Vector v = ent.getVelocity();
                vx = v.getX();
                vy = v.getY();
                vz = v.getZ();
                has = true;
            } catch (Throwable e) {
                has = false;
            }

            boolean ok;
            try {
                try {
                    ent.damage(dmg, p);
                    ok = true;
                } catch (Throwable e1) {
                    try {
                        ent.damage(dmg);
                        ok = true;
                    } catch (Throwable e2) {
                        ok = false;
                    }
                }
            } catch (Throwable e) {
                ok = false;
            }

            if (has) {
                try {
                    ent.setVelocity(new Vector(vx, vy, vz));
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // ★ 打完之后**再清一次** —— 不留无敌帧给下一次（0.5 秒后还要再打）
            try {
                ent.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 静默
            }
            return ok;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 回血：命中 n 只 ⇒ 回 BL_HEAL_PER × n 点

    private static double heal(Player p, double amount) {
        try {
            if (p == null || amount <= 0) {
                return 0.0;
            }
            double cur;
            try {
                cur = p.getHealth();
            } catch (Throwable e) {
                return 0.0;
            }
            if (cur <= 0) {
                return 0.0;                       // 已经死了，不救
            }

            double mx;
            try {
                mx = p.getMaxHealth();
            } catch (Throwable e) {
                mx = 0.0;
            }
            // 上限以「属性读到的」为准（可能被别的系统改过），双保险
            try {
                AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
                if (inst != null) {
                    double b = inst.getValue();
                    if (b > 0 && (mx <= 0 || b > mx)) {
                        mx = b;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            if (mx <= 0) {
                mx = 20.0;
            }

            double to = cur + amount;
            if (to > mx) {
                to = mx;
            }
            if (to > cur) {
                try {
                    p.setHealth(to);
                } catch (Throwable e) {
                    return 0.0;
                }
            }
            return to - cur;
        } catch (Throwable e) {
            return 0.0;
        }
    }

    // ------------------------------------------------------------ 判定盒几何（可单测）

    /** 判定盒的三维正交基：前 f、右 r、上 u。 */
    static final class Basis {
        double fx;
        double fy;
        double fz;
        double rx;
        double ry;
        double rz;
        double ux;
        double uy;
        double uz;
    }

    /**
     * 由「准星方向」与「yaw」算出判定盒的基向量。
     *
     * <p>★ 抽成静态方法是为了能用真 JS 逐值对账（{@code BorrowLifeParityProbe}）。
     * 抽出来<b>不改任何行为</b>：算式逐字照抄原脚本。
     *
     * @param dir 准星方向（{@code Location#getDirection()}）
     * @param yaw 玩家 yaw（正上/正下看时水平分量退化，要用它兜底）
     */
    static Basis basis(Vector dir, float yaw) {
        Basis b = new Basis();

        // ① 前向 f = 准星方向（含俯仰，三维归一化）
        double fx = 0.0;
        double fy = 0.0;
        double fz = 1.0;
        try {
            fx = dir.getX();
            fy = dir.getY();
            fz = dir.getZ();
        } catch (Throwable e) {
            fx = 0.0;
            fy = 0.0;
            fz = 1.0;
        }
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (!(fl > 1.0e-6)) {
            fx = 0.0;
            fy = 0.0;
            fz = 1.0;
            fl = 1.0;
        }
        fx /= fl;
        fy /= fl;
        fz /= fl;
        b.fx = fx;
        b.fy = fy;
        b.fz = fz;

        // ② 水平右向 r：取 f 的水平分量；正上/正下看时它退化成 0 ⇒ 退回用 yaw
        double hx = fx;
        double hz = fz;
        double hl = Math.sqrt(hx * hx + hz * hz);
        if (!(hl > 1.0e-4)) {
            double rad = yaw * Math.PI / 180.0;
            hx = -Math.sin(rad);
            hz = Math.cos(rad);            // 原版口径：yaw 0 = 朝 +Z
            hl = Math.sqrt(hx * hx + hz * hz);
            if (!(hl > 1.0e-6)) {
                hx = 0.0;
                hz = 1.0;
                hl = 1.0;
            }
        }
        hx /= hl;
        hz /= hl;
        b.rx = -hz;
        b.ry = 0.0;
        b.rz = hx;                         // 面朝 +Z 时 = (−1,0,0) = 右手边

        // ③ 上向 u = r × f（右手系；抬头时它向后仰）
        b.ux = b.ry * fz - b.rz * fy;
        b.uy = b.rz * fx - b.rx * fz;
        b.uz = b.rx * fy - b.ry * fx;

        return b;
    }

    /**
     * 相对位移是否落在判定盒内。
     *
     * <p>★ 三个区间逐字照抄：{@code fwd ∈ [-BL_BACK, BL_RANGE]}、
     * {@code lat ∈ [-halfW, halfW]}、{@code ver ∈ [-halfH, halfH]}。
     */
    static boolean inside(Basis b, double dx, double dy, double dz) {
        double halfW = BL_WIDE * 0.5;
        double halfH = BL_H * 0.5;
        double fwd = dx * b.fx + dy * b.fy + dz * b.fz;
        double lat = dx * b.rx + dy * b.ry + dz * b.rz;
        double ver = dx * b.ux + dy * b.uy + dz * b.uz;
        if (fwd < -BL_BACK || fwd > BL_RANGE) {
            return false;
        }
        if (lat < -halfW || lat > halfW) {
            return false;
        }
        return !(ver < -halfH) && !(ver > halfH);
    }

    /** 判定盒的搜索结果。 */
    private static final class Box {
        final List<LivingEntity> list = new ArrayList<>();
    }

    /** 长 5 × 宽 5 × 高 2（沿准星三维朝向）。 */
    private static Box boxHit(Player p) {
        Box out = new Box();
        try {
            Location loc;
            try {
                loc = p.getLocation();
            } catch (Throwable e) {
                return out;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return out;
            }
            Vector dir;
            try {
                dir = loc.getDirection();
            } catch (Throwable e) {
                return out;
            }

            Basis b = basis(dir, loc.getYaw());

            double px0 = loc.getX();
            double py0 = loc.getY();
            double pz0 = loc.getZ();
            double halfW = BL_WIDE * 0.5;          // 左右各 2.5
            double halfH = BL_H * 0.5;             // 上下各 1.0（沿 u）

            // 搜索盒：中心放在「沿准星前移 mid 格」处，半宽取**外接球**半径
            double halfR = (BL_RANGE + BL_BACK) * 0.5;
            double mid = (BL_RANGE - BL_BACK) * 0.5;
            double reach = Math.sqrt(halfR * halfR + halfW * halfW + halfH * halfH) + 0.2;
            Location cbox = new Location(world,
                    px0 + b.fx * mid, py0 + 1.0 + b.fy * mid, pz0 + b.fz * mid);

            List<Entity> list;
            try {
                list = new ArrayList<>(world.getNearbyEntities(cbox, reach, reach, reach));
            } catch (Throwable e) {
                return out;
            }

            String selfId = String.valueOf(p.getUniqueId());
            Set<String> seen = new HashSet<>();
            for (Entity ent : list) {
                if (!canHit(ent, selfId)) {
                    continue;
                }
                String eid;
                try {
                    eid = String.valueOf(ent.getUniqueId());
                } catch (Throwable e) {
                    continue;
                }
                if (seen.contains(eid)) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;
                }

                // 相对位移：原点 = 自己胸口（脚下 +1.0），怪的取样点 = 脚下 +0.9
                double dx = el.getX() - px0;
                double dy = (el.getY() + 0.9) - (py0 + 1.0);
                double dz = el.getZ() - pz0;

                if (!inside(b, dx, dy, dz)) {
                    continue;
                }

                seen.add(eid);
                out.list.add((LivingEntity) ent);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return out;
    }

    // ------------------------------------------------------------ 吸血粒子

    /** 一条吸血粒子链的状态。 */
    private static final class Sip {
        final long gen;
        final Player p;
        final List<LivingEntity> targets;
        int i;

        Sip(long gen, Player p, List<LivingEntity> targets) {
            this.gen = gen;
            this.p = p;
            this.targets = targets;
        }
    }

    private void sipStart(Player p, List<LivingEntity> targets) {
        try {
            if (p == null || targets == null || targets.isEmpty()) {
                return;
            }
            gen = gen + 1;                      // ★ 只增不减
            long g = gen;
            String id = String.valueOf(g);
            sip.put(id, new Sip(g, p, targets));
            sipLoop(id, g);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 画「第 i 步」的粒子：每只怪各自从自己胸口飞向玩家胸口。 */
    private void sipDraw(Sip st) {
        try {
            Player p = st.p;
            Location pl;
            try {
                pl = p.getLocation();
            } catch (Throwable e) {
                return;
            }
            World world;
            try {
                world = p.getWorld();
            } catch (Throwable e) {
                return;
            }

            double px = pl.getX();
            double py = pl.getY() + 1.0;
            double pz = pl.getZ();
            double t = st.i / (double) BL_SIP_TICKS;    // 0 → 1
            int n = st.targets.size();

            for (int k = 0; k < n; k++) {
                LivingEntity ent = st.targets.get(k);
                if (ent == null) {
                    continue;
                }
                Location el;
                try {
                    el = ent.getLocation();
                } catch (Throwable e) {
                    continue;                   // 拿不到位置就跳过
                }
                double ex = el.getX();
                double ey = el.getY() + 0.9;
                double ez = el.getZ();

                // 每只怪错开一点点 ⇒ 看起来是一串血珠，不是一团
                double base = t + k * BL_SIP_STAGGER;
                for (int j = 0; j < BL_SIP_PER_TICK; j++) {
                    double f = base + j * (BL_SIP_STAGGER * 0.5);
                    if (f < 0.0) {
                        f = 0.0;
                    }
                    if (f > 1.0) {
                        f = 1.0;
                    }
                    double x = ex + (px - ex) * f;
                    double y = ey + (py - ey) * f;
                    double z = ez + (pz - ez) * f;
                    // 轻微上凸的弧线（0 就是直线）
                    y += Math.sin(f * Math.PI) * BL_SIP_ARC;
                    dust(world, x, y, z);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 自调度链：每刻一步。玩家退服 / 走完 10 刻 ⇒ 断链。 */
    private void sipLoop(String id, long g) {
        try {
            Sip st = sip.get(id);
            if (st == null || st.gen != g) {
                return;                        // ★ 已被取代 ⇒ 断链
            }

            Player p = st.p;
            boolean ok = true;
            try {
                if (p == null || !p.isOnline()) {
                    ok = false;
                }
            } catch (Throwable e1) {
                ok = false;
            }
            if (!ok) {
                sip.remove(id);
                return;
            }

            sipDraw(st);
            st.i = st.i + 1;
            if (st.i >= BL_SIP_TICKS) {
                sip.remove(id);                // 0.5 秒飞完
                return;
            }

            Sip st2 = sip.get(id);
            if (st2 != null && st2.gen == g) {
                GS.runLater(() -> sipLoop(id, g), 1L);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 音效

    private static void castSound(Player p) {
        try {
            if (BL_SOUND == null) {
                return;
            }
            p.getWorld().playSound(p.getLocation(), BL_SOUND, 0.55f, 1.35f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }

            // ★ 只认主手触发
            if (!Guards.isMainHandUse(event, p, BL_ITEM_ID)) {
                return null;
            }

            // ★ 去抖（一次右键的两个事件只认一个）—— **只查，不武装**
            if (isDuplicate(p)) {
                return null;
            }

            // 冷却中 ⇒ 静默忽略（不提示、不出声、不消耗，也**不武装去抖窗口**）
            long left = last.left(p);
            if (left > 0) {
                cooldownHint(p, left);
                return null;
            }

            // ★ 到这一步才是「真的按生效了」⇒ 现在才武装去抖窗口
            armDebounce(p);
            last.mark(p);

            // ① 判定盒：长 5 × 宽 5 × 高 2
            Box box = boxHit(p);
            List<LivingEntity> targets = box.list;
            int n = targets.size();

            // ② 每只 1 点伤害（无击退 · 消失无敌帧）
            for (int i = 0; i < n; i++) {
                try {
                    hurt(targets.get(i), BL_DMG, p);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            // ③ 命中 n 只 ⇒ 回 1×n 点血
            if (n > 0) {
                heal(p, BL_HEAL_PER * n);
            }

            // ④ 吸血粒子：从每只怪身上飞向玩家，0.5 秒
            if (n > 0) {
                sipStart(p, targets);
            }

            // ★★ 第六十二批：原来的第 ⑤ 步「施法提示词：寿来 寿来 寿来」**已整段删除**
            //    ⇒ 现在施法只出声、不写字。
            castSound(p);
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 借寿 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
