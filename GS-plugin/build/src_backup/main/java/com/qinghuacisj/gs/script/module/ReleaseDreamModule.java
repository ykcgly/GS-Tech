package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.content.Content;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code gs_release_dream.js} 的 Java 化版本 —— 「释梦」。
 *
 * <p>右键 → 朝视线方向打出一道<b>灵魂沙粒子</b>（SOUL）弹道：
 * 射程 15 格、<b>碰到实体方块就停下</b>（不穿墙）；没命中生物什么都不发生；
 * 命中生物 → 从它<b>正上方 6 格</b>播放下坠动画，粒子砸到身上那一刻结算一次
 * <b>40 点</b>伤害，<b>只有 1 次下坠 = 1 次伤害</b>。
 *
 * <p>★★ <b>三个必须逐字保留的行为</b>：
 * <ol>
 *   <li><b>没有冷却、没有任何提示词</b>（2026-10-08 第六批把 0.3 秒 CD 与
 *       「梦还没醒…」/「可以重新割开梦境」两句提示整段删掉了）。</li>
 *   <li><b>没有主手判定</b> —— 和 {@code gs_song}/{@code gs_ding_me} <b>不一样</b>！
 *       释梦<b>不</b>检查主手，所以副手右键也会发射。这是原脚本的现状，
 *       加主手判定就等于改了行为。</li>
 *   <li>结算前<b>清掉受击无敌帧</b>（{@code setNoDamageTicks(0)}），
 *       结算时<b>存下 velocity、打完立刻还原</b> ⇒ <b>只掉血、不产生任何击退</b>。
 *       不清无敌帧的话，两次右键的下坠时间点一重叠，后一发会被原版那 10 刻无敌时间
 *       吃掉（无伤）。</li>
 * </ol>
 *
 * <p>★★ <b>为什么全部用 runLater 链，而不用 runRepeating + task.cancel()</b>：
 * RSC 的 {@code runRepeating} 是<b>同步返回</b>那个 {@code BukkitTask} 的
 * （回调还没跑过，{@code delay > 0} 时基本就是 {@code null}），
 * 所以 {@code task.cancel()} 要么 NPE、要么被 try 吞掉 —— <b>任务永远停不下来</b>
 * （这就是之前「灵魂沙云一直冒」的真正原因）。
 * 改成「一步一个 runLater、自己在末尾决定还要不要下一步」⇒ 天然自终止、不需要 cancel。
 *
 * <p>★ 配置每次右键读一次（改了配置能立刻生效），段名 {@code release-dream}。
 */
public final class ReleaseDreamModule extends AbstractModule {

    private static final String RD_ITEM_ID = "GS_RELEASE_DREAM";

    public ReleaseDreamModule() {
        // 加载标记：后台看到这行说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_release_dream 已加载");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }
    }

    @Override
    public String name() {
        return "gs_release_dream";
    }

    // ------------------------------------------------------------ 配置

    /** 等价于原脚本 {@code rdConfig()} 返回的那个 {@code def} 对象。 */
    static final class Cfg {
        double range = 15.0;          // 射程（格）
        double step = 0.7;            // 每个游戏刻前进多少格
        double hitRadius = 0.8;
        double dropFrom = 6.0;        // 每次下坠从生物正上方几格开始
        int hitInterval = 20;         // 两次下坠之间的间隔（游戏刻）
        double dps = 40.0;            // 每次结算的伤害
        int hits = 1;                 // 下坠 / 伤害次数
        double soundPitch = 0.7071;   // ★ 0.7071 = 音符盒左键 6 次（note 6）
        double soundVol = 1.0;
        String screamSound = "entity.warden.heartbeat";
        int screamDelay = 20;
        int screamCount = 0;
        List<String> sounds = new ArrayList<>(List.of("block.note_block.guitar"));
    }

    /**
     * 读配置（每次右键读一次）。
     *
     * <p>★ 钳制条件逐字照抄 —— 它们决定了线上实际的伤害/射程。
     */
    static Cfg readCfg() {
        try {
            org.bukkit.configuration.file.YamlConfiguration cfg = Content.config();
            if (cfg == null) {
                return readCfg(null);
            }
            return readCfg(cfg.getConfigurationSection("release-dream"));
        } catch (Throwable ignored) {
            return readCfg(null);
        }
    }

    /**
     * 读配置的核心（section 可注入，便于用真配置做逐值对账）。
     *
     * <p>★ {@code sec == null} ⇒ 直接返回默认值（等价原脚本的 {@code if (sec == null) return def;}）。
     */
    static Cfg readCfg(ConfigurationSection sec) {
        Cfg def = new Cfg();
        if (sec == null) {
            return def;
        }
        try {
            def.range = sec.getDouble("range", 15.0);
            def.step = sec.getDouble("step", 0.7);
            if (def.step < 0.1) {
                def.step = 0.1;
            }
            def.hitRadius = sec.getDouble("hit-radius", 0.8);
            def.dropFrom = sec.getDouble("drop-from", 6.0);
            if (def.dropFrom < 1.0) {
                def.dropFrom = 1.0;
            }

            def.hitInterval = sec.getInt("hit-interval", 20);
            if (def.hitInterval < 1) {
                def.hitInterval = 1;
            }

            def.dps = sec.getDouble("damage-per-second", 40.0);
            def.hits = sec.getInt("damage-seconds", 1);
            if (def.hits < 1) {
                def.hits = 1;
            }
            if (def.hits > 10) {
                def.hits = 10;
            }

            def.soundPitch = sec.getDouble("sound-pitch", def.soundPitch);
            if (def.soundPitch < 0.5) {
                def.soundPitch = 0.5;
            }
            if (def.soundPitch > 2.0) {
                def.soundPitch = 2.0;
            }
            def.soundVol = sec.getDouble("sound-volume", def.soundVol);
            if (def.soundVol < 0.0) {
                def.soundVol = 0.0;
            }

            // 心跳声：优先读新键 heartbeat-*，读不到再回退旧键 scream-*
            def.screamSound = sec.getString("heartbeat-sound",
                    sec.getString("scream-sound", def.screamSound));
            def.screamDelay = sec.getInt("heartbeat-slime-ticks",
                    sec.getInt("scream-slime-ticks", 2)) * 10;   // 1 粘液刻 = 10 刻
            if (def.screamDelay < 1) {
                def.screamDelay = 1;
            }
            def.screamCount = sec.getInt("heartbeat-count", sec.getInt("scream-count", 0));
            if (def.screamCount < 0) {
                def.screamCount = 0;
            }
            if (def.screamCount > 10) {
                def.screamCount = 10;
            }

            List<String> sl = sec.getStringList("sounds");
            if (sl != null && !sl.isEmpty()) {
                List<String> arr = new ArrayList<>();
                for (String s : sl) {
                    arr.add(String.valueOf(s));
                }
                def.sounds = arr;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默（用 def 的默认值）
        }
        return def;
    }

    // ------------------------------------------------------------ 工具

    /** 灵魂沙粒子。等价于原脚本的 {@code rdSoul}。 */
    private static void soul(World world, double x, double y, double z,
                            int count, double spread, double extra) {
        try {
            world.spawnParticle(Particle.SOUL, x, y, z, count, spread, spread, spread, extra);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 找一个「活体」（掉落物 / 箭 / 展示框这些没有 {@code getHealth}）。
     *
     * <p>★ JS 靠「调用 {@code ent.getHealth()} 抛不抛异常」来筛选活体 ⇒
     * Java 里等价于 {@code instanceof LivingEntity}。
     * 注意 {@code ArmorStand} 也是 {@code LivingEntity}，所以它<b>会被选中</b>
     * —— 与原脚本一致。
     *
     * <p>★ 返回的是<b>迭代顺序里的第一个</b>符合条件者，不排序、不挑最近的。
     */
    private static LivingEntity findLiving(World world, Location pt, double radius, String selfId) {
        try {
            Collection<Entity> list = world.getNearbyEntities(pt, radius, radius, radius);
            for (Entity ent : list) {
                String eid;
                try {
                    eid = String.valueOf(ent.getUniqueId());
                } catch (Throwable e) {
                    continue;
                }
                if (eid.equals(selfId)) {
                    continue;               // 不打自己
                }
                if (!(ent instanceof LivingEntity le)) {
                    continue;               // 等价 JS 的 getHealth() 抛异常
                }
                try {
                    le.getHealth();
                } catch (Throwable e) {
                    continue;
                }
                try {
                    if (ent.isDead()) {
                        continue;
                    }
                } catch (Throwable ignored) {
                    // ★ 原脚本这里是空 catch（不 continue）—— 保留
                }
                return le;
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    // ------------------------------------------------------------ 命中后的「N 次下坠 = N 次伤害」

    /**
     * spot 是命中瞬间记下的坐标（后面所有下坠都打这一点，
     * 生物被打死/被移除也不会歪）。
     */
    private void sequence(World world, Player shooter, LivingEntity victim, Cfg cfg) {
        final double sx;
        final double sy;
        final double sz;
        try {
            Location l = victim.getLocation();
            sx = l.getX();
            sy = l.getY();
            sz = l.getZ();
        } catch (Throwable e) {
            return;
        }
        cycle(world, shooter, victim, sx, sy, sz, cfg, 1);
    }

    /** 第 n 次下坠：从正上方 dropFrom 格落到生物身上 → 结算 1 次伤害 → 排下一次。 */
    private void cycle(World world, Player shooter, LivingEntity victim,
                       double tx, double ty, double tz, Cfg cfg, int n) {
        final double h0 = cfg.dropFrom;

        // 起点先来一小团，让人看见「它又从上面来了」
        soul(world, tx, ty + 1.0 + h0, tz, 8, 0.15, 0.0);

        // ★ h 是跨 fallStep 多次调用持续变化的闭包变量 ⇒ 用单元素数组承载
        final double[] h = {h0};

        class Fall {
            void step() {
                try {
                    // 画这一段下落轨迹（画密一点 → 看起来是连续下坠）
                    double from = h[0];
                    double to = h[0] - 1.0;
                    if (to < 0) {
                        to = 0;
                    }
                    int seg = 5;
                    for (int i = 0; i <= seg; i++) {
                        double yy = ty + 1.0 + from + (to - from) * (i / (double) seg);
                        soul(world, tx, yy, tz, 2, 0.10, 0.0);
                    }

                    h[0] -= 1.0;

                    if (h[0] > 0) {
                        GS.runLater(this::step, 1L);
                        return;                       // 还没落地 → 继续下一步
                    }

                    // ★ 砸到生物身上：一小团闪光 + 这一次的伤害
                    soul(world, tx, ty + 1.0, tz, 8, 0.4, 0.05);
                    hitDamage(shooter, victim, cfg);

                    if (n < cfg.hits) {
                        // 下一次下坠：让「每次结算的间隔」约等于 hitInterval 刻
                        long rest = cfg.hitInterval - Math.round(cfg.dropFrom);
                        if (rest < 1) {
                            rest = 1;
                        }
                        final int next = n + 1;
                        GS.runLater(() -> cycle(world, shooter, victim, tx, ty, tz, cfg, next), rest);
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        }

        GS.runLater(new Fall()::step, 1L);
    }

    /** 结算一次伤害：清无敌帧、存速度、打、还原速度。 */
    private static void hitDamage(Player shooter, LivingEntity victim, Cfg cfg) {
        try {
            if (victim == null) {
                return;
            }
            try {
                if (victim.isDead()) {
                    return;
                }
            } catch (Throwable e) {
                return;
            }
            try {
                victim.getHealth();
            } catch (Throwable e) {
                return;
            }

            // ★★ 先清掉受击无敌帧再结算（否则重叠时第二发无伤）
            try {
                victim.setNoDamageTicks(0);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // ★★ 取消击退：打之前存 velocity、打完立刻还原
            double vx = 0.0;
            double vy = 0.0;
            double vz = 0.0;
            boolean hasV;
            try {
                Vector v0 = victim.getVelocity();
                vx = v0.getX();
                vy = v0.getY();
                vz = v0.getZ();
                hasV = true;
            } catch (Throwable e) {
                hasV = false;
            }

            try {
                victim.damage(cfg.dps, shooter);
            } catch (Throwable e1) {
                try {
                    victim.damage(cfg.dps);
                } catch (Throwable e2) {
                    // 与原脚本一致：静默
                }
            }

            if (hasV) {
                try {
                    victim.setVelocity(new Vector(vx, vy, vz));
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 发射音效

    /**
     * 发射音效：<b>只有音符盒那一声</b>
     * （音符盒放羊毛上 ⇒ 吉他音色 {@code block.note_block.guitar}；
     * 左键调音 6 次 ⇒ note 6 ⇒ pitch = 2^((6−12)/12) ≈ <b>0.7071</b>）。
     * 心跳声默认 {@code screamCount = 0} ⇒ 不响，但保留这条路径。
     */
    private static void launchSounds(Player p, Cfg cfg) {
        try {
            World world = p.getWorld();
            Location loc = p.getLocation();

            double vol = cfg.soundVol;
            double pit = cfg.soundPitch;

            // ① 音符盒（吉他音色 · note 6）
            for (String s0 : cfg.sounds) {
                String s = String.valueOf(s0);
                if (s.isEmpty()) {
                    continue;
                }
                try {
                    world.playSound(loc, s, (float) vol, (float) pit);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }

            // ② 坚守者心跳声（发射时）
            if (cfg.screamCount > 0 && cfg.screamSound != null && !cfg.screamSound.isEmpty()) {
                final String scream = String.valueOf(cfg.screamSound);
                final World w = world;
                final Location l = loc;

                class Scream {
                    void play() {
                        try {
                            w.playSound(l, scream, 1.6f, 1.0f);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }

                    void again(int cnt) {
                        if (cnt <= 0) {
                            return;
                        }
                        GS.runLater(() -> {
                            play();
                            again(cnt - 1);
                        }, cfg.screamDelay);
                    }
                }

                Scream sc = new Scream();
                sc.play();
                int left = cfg.screamCount - 1;
                if (left > 0) {
                    sc.again(left);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 弹道

    /** 弹道（runLater 链，撞墙 / 超射程 / 命中都会自然结束）。 */
    private void launch(Player p, Cfg cfg) {
        try {
            final World world = p.getWorld();
            final Location eye = p.getEyeLocation();
            final Vector dir = eye.getDirection();       // 单位向量
            final String selfId = String.valueOf(p.getUniqueId());
            // ★ 从眼前 1 格起 → 不会打到自己
            final double[] dist = {1.0};

            class Step {
                void run() {
                    try {
                        dist[0] += cfg.step;
                        if (dist[0] > cfg.range) {
                            return;          // 飞到射程尽头 → 结束
                        }

                        double px = eye.getX() + dir.getX() * dist[0];
                        double py = eye.getY() + dir.getY() * dist[0];
                        double pz = eye.getZ() + dir.getZ() * dist[0];
                        Location pt = new Location(world, px, py, pz);

                        // 拖尾：灵魂沙粒子
                        soul(world, px, py, pz, 4, 0.06, 0.0);

                        // 命中生物？
                        LivingEntity victim = findLiving(world, pt, cfg.hitRadius, selfId);
                        if (victim != null) {
                            sequence(world, p, victim, cfg);
                            return;
                        }

                        // 撞到方块？→ 没命中生物，什么都不发生（不穿墙）
                        org.bukkit.block.Block b = pt.getBlock();
                        if (b != null && b.getType() != null && b.getType().isSolid()) {
                            return;
                        }

                        GS.runLater(this::run, 1L);    // 继续飞
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }

            GS.runLater(new Step()::run, 1L);
        } catch (Throwable e) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 释梦发射出错: " + e);
            } catch (Throwable ignored) {
                // 同上
            }
        }
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

        try {
            Cfg cfg = readCfg();

            // ★ 冷却已整段删除 —— 不再有 0.3 秒 CD，也不再提示，
            //   右键即可发射（伤害 / 弹道 / 音效全部不变）。
            // ★ 也**没有**主手判定（与 gs_song / gs_ding_me 不同）。

            // 发射音效（只有一声：音符盒放羊毛上、左键 6 次的那个音）
            launchSounds(p, cfg);

            launch(p, cfg);
        } catch (Throwable err) {
            // ★ 外层是「给玩家发红字 + 记一条 warning」，与原脚本逐字一致
            try {
                p.sendMessage(com.qinghuacisj.gs.util.Chat.t("&c释梦出错了: " + err));
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 释梦 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
