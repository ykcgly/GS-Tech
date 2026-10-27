package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * {@code gs_listener.js} 的「梦境套 · 常驻粒子光环」子系统
 * （原脚本第 <b>677~875</b> 行，顶层入口 {@code gsAuraStart()}）。
 *
 * <p><b>玩法</b>：穿着梦境套<b>四件齐全</b>的玩家脚下会有一圈固定不动的粒子环，
 * 每 {@code interval-ticks}（默认 <b>3</b> 刻）刷一次。环上共
 * {@code 粒子种类数 × each-count} 个点（默认 1 × 6 = 6 个），角度<b>均分整圈</b>、
 * 半径固定 {@code 0.80} 格；粒子种类按 {@code i % n} <b>交叉排列</b>，
 * 第 0 种踩 {@code height-high}、其余踩 {@code height-low}（默认两个都是 0.10，
 * 所以默认看不出高低差 —— 这是配置项而非写死的差异）。
 *
 * <p>★ 需求明确<b>排除爱心粒子</b> ⇒ 配置里的粒子名只要含 {@code HEART} 就强制跳过
 * （该过滤在 {@link ListenerCore#gsAuraConfig()} 里，本类不重复实现 ——
 * 原脚本的 {@code gsAuraPulse} 里也没有 HEART 判断，见下面「死代码」第 3 条）。
 *
 * <p>★★ <b>世代号机制</b>（原脚本 748~783 行，实现在 {@link ListenerCore}）：
 * RSC 的 {@code runRepeating} 返回的任务句柄<b>拿不到、也 cancel 不掉</b>，
 * 所以 {@code /rsc reload} 之后旧任务还会继续跑。作者的办法是：
 * 把世代号存在<b>第一个世界</b>的 PDC（{@code gs:aura_gen}）上，
 * 每次 {@code gsAuraStart()} 就 +1 并记下「我这次拿到的是几号」；
 * 每次脉冲先问 {@link ListenerCore#gsAuraIsCurrent()}，不是当前那一份就直接停手。
 * <b>Java 版逐字保留这个语义</b> —— {@link GS#runRepeating} 同样拿不到句柄，
 * 世代号是唯一能让 reload 后旧任务失效的手段。
 *
 * <p>★ <b>本子系统没有任何监听器</b>：原脚本 677~875 行里<b>没有一处</b>
 * {@code pm.registerEvent(...)}（全脚本 38 处 registerEvent，最近的分别在 615/629
 * 行的「梦境盔甲免死」和 1881 行的「余念绝响」，都落在别的子系统里）。
 * 所以这里<b>不</b>实现 {@code org.bukkit.event.Listener}，也没有「reload 会累积监听器」
 * 这个缺陷 —— 唯一的常驻任务是靠世代号自终止的 {@code runRepeating}。
 *
 * <p>★ <b>死代码/降级分支</b>（逐条列出，均已在 Java 里等价处理）：
 * <ol>
 *   <li>原脚本 786~789 行 {@code gsAuraParticle} 先试 {@code P[name]}（静态字段）、
 *       再试 {@code P.valueOf(name)}。{@code Particle} 是 <b>enum</b>，
 *       它的公开静态字段恰好就是枚举常量集合 ⇒ 两条路取的是同一批名字，
 *       Java 侧合并成一次 {@code Particle.valueOf(name)}，可观察行为相同
 *       （能取到的名字集合一致、取不到时同样落到 alts 兜底）。</li>
 *   <li>原脚本 851~853 行 {@code if (GS_AURA_KEY != null && GS_PDT != null)}：
 *       {@code GS_PDT} 是 {@code Java.type('...PersistentDataType')} 的探测结果，
 *       Java 侧该类必然可用 ⇒ 只保留 {@code GS_AURA_KEY != null} 这一半
 *       （{@link ListenerCore#GS_AURA_KEY} 是 {@code NamespacedKey.fromString}，
 *       恒非 null，所以这一半也恒真；保留它只为逐字对齐结构）。</li>
 *   <li>原脚本 792 行 {@code alts} 里的 {@code 'END_ROD': 'END_ROD'} 与
 *       {@code 'DRAGON_BREATH': 'DRAGON_BREATH'} 是<b>恒等兜底</b>：
 *       它们只有在 {@code P.valueOf(name)} 已经失败之后才会被查到，
 *       而这两个名字在 1.21.11 的 {@code Particle} 里<b>都是真常量</b>
 *       （{@code javap} 实测）⇒ {@code valueOf} 必然成功 ⇒
 *       <b>这两条表项永远走不到</b>（探针 D 段有对应的断言）。
 *       Java 侧保留同样的查表结构（不省略），行为一致。</li>
 *   <li>反向的一条：{@code Particle.HEART} 也是真常量 ⇒
 *       {@code gsAuraParticle("HEART")} <b>能拿到粒子</b>。
 *       所以「爱心粒子强制跳过」这条需求<b>只能</b>在
 *       {@link ListenerCore#gsAuraConfig()} 里做（从配置表里剔除），
 *       本类的循环里没有、也不该有 HEART 判断 ——
 *       原脚本的 {@code gsAuraPulse} 里同样没有。</li>
 * </ol>
 */
public final class AuraSub {

    private final ListenerCore core;

    public AuraSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsAuraStart();} 调用（原脚本第 875 行）。 */
    public void start() {
        gsAuraStart();
    }

    // ======================================================================
    // 粒子名 → 枚举（原脚本 785~798 行）
    // ======================================================================

    /**
     * {@code gsAuraParticle(name)} —— 粒子名 → 枚举（跨版本容错）。
     *
     * <p>原脚本三段式：① {@code P[name]} ② {@code P.valueOf(name)} ③ {@code alts} 兜底。
     * ①② 在 enum 上等价（见类注释），Java 合并成一次 {@code valueOf}。
     */
    static Particle gsAuraParticle(String name) {
        try {
            if (name == null) {
                return null;
            }
            // ① + ② P[name] / P.valueOf(name)
            try {
                return Particle.valueOf(name);
            } catch (Throwable ignored) {
                // 落到 ③ 老版本换名兜底
            }
            // ③ 老版本换名的兜底
            String alt = gsAuraParticleAlt(name);
            if (alt != null) {
                try {
                    return Particle.valueOf(alt);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默 → 返回 null
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return null;
    }

    /**
     * 原脚本 792 行的 {@code alts} 字面量，<b>逐字照抄</b>：
     * {@code {'SCULK_SOUL':'SOUL','SOUL':'SCULK_SOUL','END_ROD':'END_ROD','DRAGON_BREATH':'DRAGON_BREATH'}}。
     *
     * <p>JS 侧 {@code alts[name]} 取不到时是 {@code undefined}，而
     * {@code undefined != null} 为 <b>false</b> ⇒ 直接跳过兜底。Java 用 {@code null} 表示同一件事。
     */
    private static String gsAuraParticleAlt(String name) {
        switch (name) {
            case "SCULK_SOUL":
                return "SOUL";
            case "SOUL":
                return "SCULK_SOUL";
            case "END_ROD":
                return "END_ROD";
            case "DRAGON_BREATH":
                return "DRAGON_BREATH";
            default:
                return null;
        }
    }

    // ======================================================================
    // 环上取点（原脚本 809~836 行）—— 抽成静态方法只为对账，算式逐字照抄
    // ======================================================================

    /**
     * 光环上第 {@code i} 个点相对玩家脚底的偏移 {@code {dx, dy, dz}}。
     *
     * <pre>
     *   total = n * eachCount          // 一圈总共几个点
     *   stepA = (Math.PI * 2.0)/total  // 均分一圈
     *   idx   = i % n                  // 交叉排列：一个隔一个换种类
     *   a     = i * stepA
     *   dx    = cos(a) * radius
     *   dz    = sin(a) * radius
     *   dy    = (idx === 0) ? heightHigh : heightLow
     * </pre>
     *
     * <p>★ {@code stepA} 在原脚本里是循环外算一次；这里每次调用重算 ——
     * 同一对 {@code (total)} 的除法结果在 IEEE-754 下<b>逐位相同</b>，
     * 所以 {@code i * stepA} 也逐位相同，不产生任何浮点差异。
     */
    static double[] gsAuraPoint(int i, int total, int n, double radius,
                                double heightHigh, double heightLow) {
        int idx = i % n;
        double stepA = (Math.PI * 2.0) / total;
        double a = i * stepA;
        double dx = Math.cos(a) * radius;
        double dz = Math.sin(a) * radius;
        double dy = (idx == 0) ? heightHigh : heightLow;
        return new double[]{dx, dy, dz};
    }

    // ======================================================================
    // 每次脉冲（原脚本 800~848 行）
    // ======================================================================

    /** {@code gsAuraPulse()} —— 每 {@code interval-ticks} 刻跑一次。 */
    public void gsAuraPulse() {
        try {
            // 不是当前实例的任务（reload 之后的旧任务）→ 停手
            if (!core.gsAuraIsCurrent()) {
                return;
            }

            ListenerCore.AuraConfig cfg = core.gsAuraConfig();
            if (!cfg.enabled) {
                return;
            }

            int n = cfg.particles.size();                 // 粒子种类数（默认 1）
            if (n < 1) {
                return;
            }

            int total = n * cfg.eachCount;                // 一圈总共几个点（默认 6）
            double radius = cfg.radius;
            double heightHigh = cfg.heightHigh;
            double heightLow = cfg.heightLow;

            Collection<? extends Player> players = Bukkit.getServer().getOnlinePlayers();
            for (Player p : players) {
                try {
                    if (!ListenerCore.gsDreamFullSet(p)) {
                        continue;                         // 只有四件齐全才刷
                    }

                    Location loc = p.getLocation();
                    double x = loc.getX();
                    double y = loc.getY();
                    double z = loc.getZ();

                    // 固定位置的 total 个点：位置不随时间变化（只跟着人走）
                    for (int i = 0; i < total; i++) {
                        int idx = i % n;                  // 交叉排列：一个隔一个换种类
                        Particle type = gsAuraParticle(cfg.particles.get(idx));
                        if (type == null) {
                            continue;
                        }

                        double[] pt = gsAuraPoint(i, total, n, radius, heightHigh, heightLow);
                        double px = x + pt[0];
                        double py = y + pt[1];
                        double pz = z + pt[2];

                        try {
                            p.getWorld().spawnParticle(type, px, py, pz, 1, 0.02, 0.02, 0.02, 0.0);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默（单个玩家出错不影响其他人）
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 启动（原脚本 850~873 行）
    // ======================================================================

    /**
     * {@code gsAuraStart()} —— 抬世代号 + 排常驻任务 + 打加载日志。
     *
     * <p>★ 周期读的是 {@code dream-set.aura.interval-ticks}，默认 <b>3</b>，
     * 只有落在 <b>[1, 100]</b> 才采纳（越界 ⇒ 保持 3）。注意这是
     * {@code gsAuraConfig()} 之外<b>单独</b>读的一次配置（原脚本就是这么写的），
     * 所以不要"顺手"合并进 {@link ListenerCore#gsAuraConfig()}。
     */
    public void gsAuraStart() {
        try {
            if (ListenerCore.GS_AURA_KEY != null) {
                core.gsAuraGenBump();
            }

            long period = 3;
            try {
                FileConfiguration cfg = ListenerCore.gsCfg();
                if (cfg != null) {
                    ConfigurationSection sec = cfg.getConfigurationSection("dream-set.aura");
                    if (sec != null) {
                        int v = sec.getInt("interval-ticks", 3);
                        if (v >= 1 && v <= 100) {
                            period = v;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默（用默认 3）
            }

            final long per = period;
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsAuraPulse();
                }
            }, per, per);

            ListenerCore.gsDaLog(gsAuraStartLine(period));
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 梦境盔甲常驻粒子启动失败: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * 原脚本 869 行的加载日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>★ 抽成静态方法<b>只为让探针逐字节对账</b>，不改任何行为。
     * 对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 3 行）：
     * <pre>
     * [构思技艺] 梦境套：常驻粒子已启动（每 3 刻一次；★ 四件齐全才刷）
     * </pre>
     */
    static String gsAuraStartLine(long period) {
        return "梦境套：常驻粒子已启动（每 " + period + " 刻一次；★ 四件齐全才刷）";
    }
}
