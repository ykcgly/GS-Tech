package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code gs_fake_weisi.js} 的 Java 化版本 —— 「假为思」。
 *
 * <p>消耗品：右键一次消耗 1 个。演出时间轴（单位：刻，20 刻 = 1 秒）：
 * <pre>
 *   t=0      消耗 1 个 / 获得 9 秒漂浮
 *            → 四个隐形盔甲架从「前 / 下 / 后 / 上」3.5 格外飞向玩家：
 *                前 → 胸甲   下 → 战靴   后 → 护腿   上 → 头盔
 *              出现：t=22 / 62 / 102 / 142 刻（1.1 / 3.1 / 5.1 / 7.1 秒）
 *              抵达：t=60 / 100 / 140 / 180 刻（3 / 5 / 7 / 9 秒）
 *              每段飞行 38 刻 = 1.9 秒
 *              撞上的一瞬间 → 换上那件梦境套（原装备脱进背包）+ 消灭盔甲架
 *   t=180    （漂浮 9 秒结束）→ 立刻给 2 秒缓降 + 启动「安全落地」保护
 *   t=280    （第 15 秒）解除防连点锁
 * </pre>
 *
 * <p>★ 音效：盔甲架撞到玩家时播一次 {@code entity.ender_dragon.flap}。
 *
 * <p>★★ <b>盔甲架的装备「不掉落」靠两件事</b>：
 * ① 设成 {@code invulnerable}（打不坏 → 不会被击杀掉装备）；
 * ② 消失前先 {@code getEquipment().clear()} 再 {@code remove()} → 一个物品都不会掉。
 *
 * <p>★ <b>没有主手判定、没有时间冷却</b> —— 防连点靠 {@link #busy} 这个「演出中」锁
 * （连点时会收到「&amp;7铠甲正在合体，稍安勿躁。」）。
 * 这是它与 {@code gs_song}/{@code gs_ding_me} 的差别，不要照抄。
 *
 * <p>★ 装备<b>不收回</b>：梦境套是消耗「梦境套 + GPS 应急发射器」合成的，
 * 演出结束后永久留在身上。替换下来的原装备先脱进背包，背包放不下的掉在脚边。
 *
 * <p>★ 全部用 runLater 链，不自己拿 scheduler（RSC 的定时器返回值不可靠，
 * 见 {@code gs_release_dream} 文件头注释）。
 */
public final class FakeWeisiModule extends AbstractModule {

    // ---------------- 梦境套物品 ID ----------------
    static final String FW_CHEST = "GS_DREAM_ARMOR";      // 梦境盔甲 → 胸甲槽
    static final String FW_BOOTS = "GS_DREAM_BOOTS";      // 梦境战靴 → 靴子槽
    static final String FW_LEGS = "GS_DREAM_LEGGINGS";    // 梦境护腿 → 护腿槽
    static final String FW_HELM = "GS_DREAM_HELMET";      // 梦境头盔 → 头盔槽

    /** 音效：每换一件只播放末影龙飞行。 */
    static final String[] FW_SOUNDS = {"entity.ender_dragon.flap"};

    // ---------------- 时间轴（刻） ----------------
    static final int FW_LEV_TICKS = 180;    // 漂浮 9 秒
    static final int FW_SLOW_TICKS = 40;    // 缓降 2 秒
    static final int FW_UNLOCK = 280;       // 第 15 秒（t=14s）解除防连点锁
    static final int FW_SPIT_TIMES = 90;    // 粒子跑 90 次 × 每 2 刻 = 180 刻（9 秒）
    static final int FW_NOFALL_MAX = 600;   // 安全落地保护最长 600 次 × 2 刻 = 60 秒

    // ---------------- 「铠甲合体」动画参数 ----------------
    static final double FW_STAND_DIST = 3.5;   // 盔甲架出现位置距玩家的距离（格）
    static final int FW_FLIGHT_TICKS = 38;     // 每个盔甲架的飞行时长（38 刻 = 1.9 秒）
    static final int FW_STAND_FIRST = 22;      // 第 1 个盔甲架的出现时刻
    static final int FW_STAND_GAP = 40;        // 相邻两个的出现间隔

    /** 正在演出中的玩家（uuid → true），避免连点导致多条时间轴重叠。 */
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    public FakeWeisiModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_fake_weisi 已加载");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_fake_weisi";
    }

    // ------------------------------------------------------------ 工具

    private static String uuid(Player p) {
        try {
            return String.valueOf(p.getUniqueId());
        } catch (Throwable e) {
            return "";
        }
    }

    /** 消耗手里 1 个本物品。 */
    private static void consume(Player p) {
        try {
            PlayerInventory inv = p.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand == null) {
                return;
            }
            int amt;
            try {
                amt = hand.getAmount();
            } catch (Throwable e) {
                amt = 0;
            }
            if (amt > 1) {
                hand.setAmount(amt - 1);
            } else {
                inv.setItemInMainHand(null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 给玩家一条药水效果。
     *
     * <p>★ 原脚本用 {@code FW_PET[typeName]} 动态取枚举、取不到再 {@code valueOf}；
     * Java 里直接写常量，等价。参数 {@code (t, durTicks, amp, true, false)}
     * = 环境效果、不显示粒子；{@code addPotionEffect(effect, true)} 是强制覆盖版本。
     */
    private static void potion(Player p, PotionEffectType type, int durTicks, int amp) {
        try {
            if (type == null) {
                return;
            }
            try {
                p.addPotionEffect(new PotionEffect(type, durTicks, amp, true, false), true);
            } catch (Throwable e) {
                try {
                    p.addPotionEffect(new PotionEffect(type, durTicks, amp, true, false));
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 播一个音效（走 world，附近都能听到）。 */
    private static void sound(Player p, String name) {
        try {
            p.getWorld().playSound(p.getLocation(), String.valueOf(name), 1.0f, 1.0f);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void soundAll(Player p) {
        for (String s : FW_SOUNDS) {
            sound(p, s);
        }
    }

    /** 取附属物品（{@code getSfItemById} = {@code SlimefunItem.getById}）。 */
    private static ItemStack sfStack(String itemId) {
        try {
            SlimefunItem sf = SlimefunItem.getById(itemId);
            if (sf != null) {
                return sf.getItem().clone();
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** 把某一槽位换上指定梦境套。 */
    private static void wear(Player p, String slot, String itemId) {
        ItemStack stack = sfStack(itemId);
        if (stack == null) {
            return;
        }
        try {
            PlayerInventory inv = p.getInventory();
            switch (slot) {
                case "chest" -> inv.setChestplate(stack);
                case "boots" -> inv.setBoots(stack);
                case "legs" -> inv.setLeggings(stack);
                case "head" -> inv.setHelmet(stack);
                default -> {
                    // 与原脚本一致：未知槽位什么都不做
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 取某一槽位当前的装备（没有就返回 null）。 */
    private static ItemStack slotGet(Player p, String slot) {
        try {
            PlayerInventory inv = p.getInventory();
            switch (slot) {
                case "chest":
                    return inv.getChestplate();
                case "boots":
                    return inv.getBoots();
                case "legs":
                    return inv.getLeggings();
                case "head":
                    return inv.getHelmet();
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /** 把一件物品塞进背包，塞不下的掉在脚边。 */
    private static void toInventory(Player p, ItemStack stack) {
        if (stack == null) {
            return;
        }
        try {
            if (stack.getType() == null) {
                return;
            }
            String n;
            try {
                n = String.valueOf(stack.getType().name());
            } catch (Throwable e) {
                n = "";
            }
            if ("AIR".equals(n) || "CAVE_AIR".equals(n) || "VOID_AIR".equals(n)) {
                return;
            }
        } catch (Throwable e) {
            return;
        }

        try {
            PlayerInventory inv = p.getInventory();
            Map<Integer, ItemStack> rest = inv.addItem(stack.clone());
            if (rest != null && !rest.isEmpty()) {
                for (ItemStack it : rest.values()) {
                    try {
                        p.getWorld().dropItemNaturally(p.getLocation(), it);
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 换装备：先把该槽位原来的装备脱进背包，再穿新的。 */
    private static void swap(Player p, String slot, String itemId) {
        ItemStack old = slotGet(p, slot);
        if (old != null) {
            toInventory(p, old);
        }
        wear(p, slot, itemId);
    }

    // ------------------------------------------------------------ 「铠甲合体」

    /**
     * 玩家朝向的水平单位向量（只取 yaw，不含俯仰，
     * 避免前后出现在头顶/脚下）。
     */
    private static Vector facing(Player p) {
        try {
            Vector d = p.getLocation().getDirection();
            Vector v = new Vector(d.getX(), 0.0, d.getZ());
            if (v.lengthSquared() < 1.0E-6) {
                v = new Vector(1.0, 0.0, 0.0);
            }
            v.normalize();
            return v;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 在玩家「前 / 下 / 后 / 上」3.5 格处生成一个隐形盔甲架，身上带着对应那件梦境套。 */
    private static ArmorStand spawnStand(Player p, String dir, String itemId, String armorSlot) {
        try {
            World world = p.getWorld();
            Location base = p.getLocation();

            double dx = 0.0;
            double dy = 0.0;
            double dz = 0.0;
            if ("front".equals(dir) || "back".equals(dir)) {
                Vector v = facing(p);
                if (v == null) {
                    return null;
                }
                double sign = "front".equals(dir) ? 1.0 : -1.0;
                dx = sign * v.getX() * FW_STAND_DIST;
                dz = sign * v.getZ() * FW_STAND_DIST;
            } else if ("below".equals(dir)) {
                dy = -FW_STAND_DIST;
            } else if ("above".equals(dir)) {
                dy = FW_STAND_DIST;
            }

            Location loc = new Location(world,
                    base.getX() + dx, base.getY() + dy, base.getZ() + dz);
            try {
                loc.setYaw(base.getYaw());
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Entity ent = world.spawnEntity(loc, EntityType.ARMOR_STAND);
            if (!(ent instanceof ArmorStand stand)) {
                return null;
            }

            // 隐形（只让身上那件装备可见）+ 打不坏 + 无重力 + 静音 + 不碰撞
            try {
                stand.setInvisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setGravity(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setSilent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setCollidable(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setBasePlate(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setArms(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                stand.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 静默
            }

            // 把那件梦境套「穿」在盔甲架上
            ItemStack stack = sfStack(itemId);
            if (stack != null) {
                try {
                    org.bukkit.inventory.EntityEquipment eq = stand.getEquipment();
                    if (eq != null) {
                        switch (armorSlot) {
                            case "chest" -> eq.setChestplate(stack);
                            case "boots" -> eq.setBoots(stack);
                            case "legs" -> eq.setLeggings(stack);
                            case "head" -> eq.setHelmet(stack);
                            default -> {
                                // 与原脚本一致：未知槽位什么都不做
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return stand;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 消灭盔甲架：<b>先清空装备再 remove</b> → 保证「装备不掉落」。
     */
    private static void killStand(ArmorStand stand) {
        if (stand == null) {
            return;
        }
        try {
            if (stand.isDead()) {
                return;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默（注意：原脚本这里是空 catch，不 return）
        }
        try {
            org.bukkit.inventory.EntityEquipment eq = stand.getEquipment();
            if (eq != null) {
                eq.clear();
            }
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            stand.remove();
        } catch (Throwable ignored) {
            // 静默
        }
    }

    /** 撞到玩家：把那件换上（原装备脱进背包）+ 消灭盔甲架 + 音效。 */
    private static void equipAndKill(Player p, ArmorStand stand, String slot, String itemId) {
        swap(p, slot, itemId);
        killStand(stand);
        soundAll(p);
    }

    /**
     * 飞行：每一刻把盔甲架从「出生点」向「玩家当前身体中段」插值。
     *
     * <p>★ 玩家这时候在飘（位置一直在变），所以每一刻都以<b>最新位置</b>为目标
     * → 一定撞得上。{@code i} 从 1 数到 {@code total}：第 {@code total} 步正好抵达
     * （= 出现时刻 + {@link #FW_FLIGHT_TICKS} 刻）。
     */
    private void flight(Player p, ArmorStand stand, Location from,
                        String slot, String itemId, int i, int total) {
        if (stand == null) {
            return;
        }
        boolean dead = false;
        try {
            dead = stand.isDead();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (dead) {
            return;
        }

        boolean online = true;
        try {
            online = p.isOnline();
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (!online) {
            killStand(stand);
            return;
        }

        try {
            Location base = p.getLocation();
            double tx = base.getX();
            double ty = base.getY() + 0.9;          // 玩家身体中段
            double tz = base.getZ();

            double k = i / (double) total;          // 1/total → 1
            double x = from.getX() + (tx - from.getX()) * k;
            double y = from.getY() + (ty - from.getY()) * k;
            double z = from.getZ() + (tz - from.getZ()) * k;

            double yaw = 0.0;
            try {
                yaw = Math.atan2(-(tx - x), (tz - z)) * 180.0 / Math.PI;
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            Location loc = new Location(stand.getWorld(), x, y, z);
            try {
                loc.setYaw((float) yaw);
            } catch (Throwable ignored) {
                // 静默
            }
            stand.teleport(loc);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        if (i >= total) {
            equipAndKill(p, stand, slot, itemId);
            return;
        }
        final int next = i + 1;
        GS.runLater(() -> flight(p, stand, from, slot, itemId, next, total), 1L);
    }

    /** 启动一个盔甲架：延时 delayTicks 后出现，再花 {@link #FW_FLIGHT_TICKS} 刻飞到玩家身上。 */
    private void launch(Player p, String dir, String slot, String itemId, int delayTicks) {
        GS.runLater(() -> {
            ArmorStand stand = spawnStand(p, dir, itemId, slot);
            if (stand == null) {
                // 盔甲架造不出来（版本/权限异常）→ 退化成直接装备，保证功能不失效
                swap(p, slot, itemId);
                soundAll(p);
                return;
            }
            // 保险：万一动画链路中断，最多再等 2 秒就把盔甲架清掉（绝不留实体）
            GS.runLater(() -> killStand(stand), FW_FLIGHT_TICKS + 60L);

            Location from;
            try {
                from = stand.getLocation();
            } catch (Throwable e) {
                from = null;
            }
            if (from == null) {
                equipAndKill(p, stand, slot, itemId);
                return;
            }
            // 出现后隔 1 刻再起跑 —— 这样「出现时刻 + FW_FLIGHT_TICKS」正好是抵达时刻
            final Location start = from;
            GS.runLater(() -> flight(p, stand, start, slot, itemId, 1, FW_FLIGHT_TICKS), 1L);
        }, delayTicks);
    }

    /** 羊驼口水粒子循环：每 2 刻在周围随机位置冒几颗。 */
    private void spitLoop(Player p, int timesLeft) {
        if (timesLeft <= 0) {
            return;
        }
        try {
            if (p == null) {
                return;
            }
            boolean online = true;
            try {
                online = p.isOnline();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (!online) {
                return;
            }

            World world = p.getWorld();
            Location loc = p.getLocation();
            for (int i = 0; i < 2; i++) {
                double ang = Math.random() * 6.2831853;
                double r = 0.4 + Math.random() * 1.1;
                double x = loc.getX() + Math.cos(ang) * r;
                double z = loc.getZ() + Math.sin(ang) * r;
                double y = loc.getY() + 0.2 + Math.random() * 1.8;
                try {
                    world.spawnParticle(Particle.SPIT, x, y, z, 1, 0.12, 0.12, 0.12, 0.015);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        final int next = timesLeft - 1;
        GS.runLater(() -> spitLoop(p, next), 2L);
    }

    /**
     * 安全落地保护：持续把「摔落距离」清零，直到玩家真正落地（或落进水里）。
     */
    private void noFallLoop(Player p, int timesLeft) {
        if (timesLeft <= 0) {
            return;
        }
        try {
            if (p == null) {
                return;
            }
            boolean online = true;
            try {
                online = p.isOnline();
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (!online) {
                return;
            }

            try {
                p.setFallDistance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            boolean done = false;
            try {
                if (p.isOnGround()) {
                    done = true;
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
            if (!done) {
                try {
                    if (p.isInWater() || p.isInLava()) {
                        done = true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (done) {
                return;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        final int next = timesLeft - 1;
        GS.runLater(() -> noFallLoop(p, next), 2L);
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        Player p;
        try {
            p = event.getPlayer();
        } catch (Throwable e) {
            p = null;
        }
        if (p == null) {
            return null;
        }
        // ★ lambda 里要用，必须是 effectively final —— p 在 catch 里被赋过 null，所以取个别名
        final Player pl = p;

        String id = uuid(p);

        try {
            // 演出中不能再触发
            if (busy.contains(id)) {
                try {
                    p.sendMessage(Chat.t("&7铠甲正在合体，稍安勿躁。"));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
                return null;
            }

            // ---------- 消耗 1 个 ----------
            consume(p);

            // ---------- 9 秒漂浮 ----------
            potion(p, PotionEffectType.LEVITATION, FW_LEV_TICKS, 0);

            // ---------- 粒子：漂浮期间不断冒羊驼口水 ----------
            spitLoop(p, FW_SPIT_TIMES);

            // ---------- 铠甲合体：四个隐形盔甲架从 前 / 下 / 后 / 上 飞来 ----------
            //   前→胸甲   下→战靴   后→护腿   上→头盔
            //   出现：1.1 / 3.1 / 5.1 / 7.1 秒   抵达：3 / 5 / 7 / 9 秒（各飞 1.9 秒）
            launch(p, "front", "chest", FW_CHEST, FW_STAND_FIRST);
            launch(p, "below", "boots", FW_BOOTS, FW_STAND_FIRST + FW_STAND_GAP);
            launch(p, "back", "legs", FW_LEGS, FW_STAND_FIRST + FW_STAND_GAP * 2);
            launch(p, "above", "head", FW_HELM, FW_STAND_FIRST + FW_STAND_GAP * 3);

            // ---------- 漂浮 9 秒结束 → 立刻缓降 + 启动安全落地保护 ----------
            GS.runLater(() -> {
                potion(pl, PotionEffectType.SLOW_FALLING, FW_SLOW_TICKS, 0);  // 漂浮一结束就缓降
                noFallLoop(pl, FW_NOFALL_MAX);                                // 兜底：绝不摔伤
            }, FW_LEV_TICKS);

            // ---------- 13 秒后解除防连点锁 ----------
            busy.add(id);
            GS.runLater(() -> busy.remove(id), FW_UNLOCK);

        } catch (Throwable err) {
            // ★ 外层是「给玩家发红字 + 记一条 warning」，与原脚本逐字一致
            try {
                p.sendMessage(Chat.t("&c假为思出错了: " + err));
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 假为思 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
