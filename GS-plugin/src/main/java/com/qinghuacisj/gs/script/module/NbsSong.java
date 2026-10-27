package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.qinghuacisj.gs.util.JsNum;

/**
 * 「右键放一整首红石音乐」的<b>共用播放引擎</b>。
 *
 * <p>★★ <b>为什么抽这个类</b>：{@code gs_cursed}（咒术回战 · 1202 音符 / 43.5 秒）
 * 与 {@code gs_bad_apple}（Bad Apple · 36462 音符 / 183.5 秒）的播放引擎
 * <b>逐字节相同</b> —— 已用「函数体逐字节比对」核实：
 * 15 个同名函数里 {@code sgNow / sgCooldownLeft / sgMarkUsed / sgDebounced /
 * sgIsOurItem / sgIsMainHandUse / sgPlugin} 完全相同，
 * 其余 8 个只差「显示名」「物品 ID」和「咒术回战多出来的召唤生物钩子」。
 * 原脚本作者也明说了：「与『咒术回战』是<b>同一套播放引擎</b>（都由
 * {@code _check/nbs_to_js.py} 生成），区别：本曲<b>不召唤任何生物</b>」。
 * ⇒ 抽成一个类，两个模块各自只留自己的曲谱与钩子。
 *
 * <p>★ 两个脚本的<b>参数与语义完全一致</b>（逐条核实）：
 * <ul>
 *   <li>总音量 0.70、去抖 500ms、{@code SG_CD_HINT=false}、{@code SG_OWNER_ONLY=true}</li>
 *   <li><b>冷却 = 本曲时长</b>（{@code SG_CD_MS = SG_LEN_MS}）</li>
 *   <li>冷却中<b>静默忽略</b>、物品<b>不消耗</b>、<b>没有</b>「再按一次就停」</li>
 *   <li>只认主手、HIGHEST 拦方块交互（音效照放、方块功能一律不触发）</li>
 * </ul>
 *
 * <p>★★★ <b>播放引擎的关键设计</b>（照抄原脚本，不是我的发明）：
 * <ol>
 *   <li><b>不用「每个音符排一个 {@code runTaskLater}」</b>：Bad Apple 有 36462 个音符，
 *       那样要排 36462 个任务；而且 {@code runTaskLater} 的延迟是<b>按刻算的</b>
 *       （1 刻 = 50ms），本曲 1 个 NBS tick = 27.58ms ⇒ 会越播越偏。</li>
 *   <li>改用<b>每刻一步的自调度链</b>，用系统毫秒减去起播时刻，
 *       把「到点了还没放」的音符一次性全放掉 ⇒ <b>不会累积漂移</b>；
 *       服务器卡一下也只是把攒下的音符一起补放。</li>
 *   <li>{@code runLater} 排出去的任务<b>拿不到句柄、cancel 不掉</b>，
 *       所以停播靠<b>世代号</b>：链上每一步先看自己的 gen 还等不等于表里的 gen，
 *       不等就<b>不再排下一步</b> ⇒ 链自己断掉。</li>
 *   <li>世代号用<b>只增不减的全局计数器</b>，绝不复位 ——
 *       若按「表里旧值 +1」算，停掉再开很容易又得到同一个号，
 *       旧链在途的那一步就会<b>认成自己还活着</b> ⇒ 两条链同时播
 *       （声音重叠、还停不掉）。</li>
 * </ol>
 *
 * <p>★ <b>不照抄的几处</b>（都是 Java 里不可能失败的 {@code Java.type} / 反射兜底，
 * 已逐条确认不改变可观察行为）：
 * <ol>
 *   <li>{@code sgSchedule} 的「调度器退路一 / 退路二」—— 原脚本注释自己写明
 *       「第一条是正路，后两条只是别整个哑掉的保险」，而正路 {@code runLater}
 *       在真机上实测可用（参考服日志「排任务方式=runLater」）。那两条退路依赖
 *       {@code Java.extend} 造 Runnable，Java 里没有对应物，也不需要。</li>
 *   <li>{@code sgRegisterGuard} 的「{@code JavaAdapter} 退路」——
 *       GraalJS 25.1.3 里 {@code JavaAdapter} 根本不存在（已在 {@code GraalProbe} 实测），
 *       那条退路<b>永远走不到</b>；Java 侧用 {@link InteractGuard} 一次注册到位。</li>
 * </ol>
 */
public final class NbsSong {

    /**
     * 每个曲子的「专属钩子」。
     *
     * <p>★ 钩子的<b>调用时机</b>严格照抄原脚本，别改：
     * <ul>
     *   <li>{@link #beforeUse} —— {@code onUse} 里「惰性重试注册拦截器」之后、
     *       {@code event.getPlayer()} 判空<b>之前</b>
     *       （咒术回战的 {@code scPurge} 就在这里，所以玩家为 null 也会清扫）。</li>
     *   <li>{@link #onStart} —— 播放状态<b>写进表之后</b>、{@code sgPlayLoop} 之前
     *       （咒术回战的 {@code scSpawnPair} 要读 {@code SG_PLAY[uuid].start}，
     *       所以必须排在写表之后）。</li>
     *   <li>{@link #onTick} —— 每刻的在线判定之后、{@link #playPulse} <b>之前</b>。</li>
     *   <li>{@link #onStop} —— {@code sgPlayStopUuid} 的<b>第一行</b>，
     *       而且<b>不管表里有没有这个玩家都会调</b>
     *       （咒术回战的 {@code scClear} 放在早退之前，「生物和歌一起收」）。</li>
     * </ul>
     */
    public interface Hooks {
        default void beforeUse() {
        }

        default void onStart(Player p) {
        }

        default void onTick(String uuid) {
        }

        default void onStop(String uuid) {
        }
    }

    /** 什么都不做的钩子（Bad Apple 这种不召唤生物的曲子用）。 */
    public static final Hooks NO_HOOKS = new Hooks() {
    };

    // ---------------- 配置（构造后不变） ----------------
    /** 物品 ID（用于主手判定与方块交互拦截）。 */
    final String itemId;
    /** 显示名（只用在日志与冷却提示里）。 */
    final String displayName;
    /** 总音量（所有音符统一乘这个数）。 */
    final double volume;
    /** 音效表：时间轴里 code 的高位就是这张表的下标。 */
    final String[] sounds;
    /** 时间轴：每三个数一组 {@code [毫秒, code, 音量0~100]}。 */
    final int[] tl;
    /** 按音效名单独压音量（两张曲谱都是空的 ⇒ 不压）。 */
    final Map<String, Double> gain;
    final long debounceMs;
    final boolean cooldownHint;
    final boolean ownerOnly;
    final Hooks hooks;

    // ---------------- 派生 ----------------
    /** 音符总数。 */
    final int n;
    /** 全曲时长（毫秒）—— 最后一个音符的时间戳。 */
    final int lenMs;
    /** 冷却 = 本曲时长。 */
    final long cdMs;
    /** 25 档音符盒音高。 */
    final double[] pitch;

    // ---------------- 运行时状态 ----------------
    /** 本曲时长的冷却。 */
    private final Guards.Cooldown last;
    /** 去抖。 */
    private final Map<String, Long> touch = new HashMap<>();
    /** uuid → 播放状态。 */
    private final Map<String, Play> play = new ConcurrentHashMap<>();
    /** ★ 全局单调递增的世代号（永不复位）。 */
    private long gen = 0L;

    /** 一个玩家的播放状态。 */
    private static final class Play {
        Player p;
        long start;
        int i;
        long gen;
    }

    NbsSong(String itemId, String displayName, double volume, String[] sounds, int[] tl,
            Map<String, Double> gain, long debounceMs, boolean cooldownHint, boolean ownerOnly,
            Hooks hooks) {
        this.itemId = itemId;
        this.displayName = displayName;
        this.volume = volume;
        this.sounds = sounds;
        this.tl = tl;
        this.gain = gain;
        this.debounceMs = debounceMs;
        this.cooldownHint = cooldownHint;
        this.ownerOnly = ownerOnly;
        this.hooks = hooks == null ? NO_HOOKS : hooks;

        this.n = tl.length / 3;
        this.lenMs = tl[tl.length - 3];
        this.cdMs = lenMs;
        this.pitch = buildPitch();
        this.last = new Guards.Cooldown(cdMs);
    }

    /** 25 档音符盒音高（与游戏内 {@code 2^((n-12)/12)} 完全一致）。 */
    static double[] buildPitch() {
        double[] out = new double[25];
        for (int i = 0; i < 25; i++) {
            out[i] = Math.pow(2.0, (i - 12) / 12.0);
        }
        return out;
    }

    // ------------------------------------------------------------ 加载日志

    /**
     * 加载标记那一行的<b>公共前缀</b>，形如：
     * <pre>{@code %s 已加载 · %s 1202 个音符 / 43.5 秒 · 冷却 43.5 秒 · 8 种音效 · 排任务方式=runLater}</pre>
     *
     * <p>★★ 开头那两处 {@code %s} 是<b>照抄</b>原脚本的瑕疵：原脚本写的是
     * {@code getLogger().info('%s 已加载 · %s ' + ...)} —— 用的是<b>字符串拼接</b>
     * 而不是 {@code info(String, Object...)} 的参数化重载，所以那两个 {@code %s}
     * <b>原样出现在日志里</b>（作者的本意没实现）。
     * 已用参考服日志逐字节核实，见 {@code CursedParityProbe}。
     * <b>不要改成参数化</b> —— 那会改变现网日志，属于「顺手优化」。
     *
     * <p>★ {@code 排任务方式} 恒为 {@code runLater}：原脚本是探测
     * {@code typeof runLater === 'function'} 后才决定打哪个词，
     * 而原生版直接调 {@link GS#runLater} ⇒ 恒为前者（与原环境实测一致）。
     */
    String loadLine() {
        return "%s 已加载 · %s " + n
                + " 个音符 / " + f1(lenMs / 1000.0) + " 秒 · 冷却 "
                + f1(cdMs / 1000.0) + " 秒 · "
                + sounds.length + " 种音效 · 排任务方式=runLater";
    }

    /** JS {@code .toFixed(1)} —— ★ 必须锁 Locale，否则小数字符可能变逗号。 */
    static String f1(double v) {
        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。
        return JsNum.toFixed(v, 1);
    }

    /** JS {@code .toFixed(0)}。 */
    static String f0(double v) {
        // ★ 阶段②收尾：统一到 util/JsNum（原为本地副本，见 tools/JsNumConsolidationProbe.java）。
        return JsNum.toFixed(v, 0);
    }

    // ------------------------------------------------------------ 拦截器

    /**
     * 注册方块交互拦截器，并在成功时打日志。
     *
     * <p>★ 抽到 {@link InteractGuard} 的判据：它的行为与两个脚本各自的
     * {@code handler} 逐条一致（只拦 RIGHT_CLICK、跳过副手、认本物品、
     * HIGHEST、{@code setCancelled}）。原脚本用脚本级 {@code SG_GUARD_OK} 闩锁，
     * reload 会重复注册；{@code InteractGuard} 按物品 ID 静态去重
     * （效果等价、不累积监听器）。
     */
    void registerGuard() {
        try {
            if (InteractGuard.register(itemId)) {
                try {
                    Bukkit.getLogger().info("[构思技艺] " + displayName + "：方块交互拦截 已启用");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 冷却 / 去抖

    private void markUsed(Player p) {
        last.mark(p);
    }

    private long cooldownLeft(Player p) {
        return last.left(p);
    }

    /**
     * ★ 去抖：一次右键可能连发两个事件（Slimefun 的主手/副手各一个），
     * 只认第一次 ⇒ 不然「按一下」会被当成「按两下」。
     *
     * <p>★★ 注意这里与 {@code gs_borrow_life} <b>不一样</b>：
     * 那个脚本把去抖拆成「只查 / 才武装」两步，因为它的冷却只有 500ms，
     * 会被连点推进窗口；而本引擎是<b>每次按都武装窗口</b>
     * （{@code SG_TOUCH[uuid] = now} 无条件执行）—— 原脚本就这么写的。
     * <br>本引擎的冷却<b>等于整首曲长</b>（43.5 秒 / 183.5 秒），
     * 远大于去抖 500ms，连点根本推不动窗口
     * ⇒ <b>照抄原写法</b>才是正确的，不要「顺手」改成两步式。
     */
    private boolean debounced(Player p) {
        try {
            if (debounceMs <= 0) {
                return false;
            }
            String uuid = p.getUniqueId().toString();
            Long v = touch.get(uuid);
            long now = Guards.now();
            if (v != null && v + debounceMs > now) {
                return true;
            }
            touch.put(uuid, now);
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 冷却提示。
     *
     * <p>★ 两个脚本的第一行都是 {@code if (!SG_CD_HINT) return;}，而
     * {@code SG_CD_HINT} 恒为 {@code false} ⇒ <b>整个函数是空操作</b>。
     * 这里保留开关、不实现提示体，可观察行为与原脚本一致：冷却中<b>什么都不显示</b>。
     */
    private void cooldownHint(Player p, long left) {
        if (!cooldownHint) {
            return;
        }
        // 开关打开时的行为（原脚本写法，保留以便日后需要）
        try {
            long s = (long) Math.ceil(left / 1000.0);
            String msg = org.bukkit.ChatColor.GOLD + displayName + " · 冷却中，还剩 " + s + " 秒";
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

    // ------------------------------------------------------------ 播放引擎

    /**
     * 某个玩家这一轮的<b>起播时刻</b>（毫秒）；没在播则返回 0。
     *
     * <p>★ 给「咒术回战」的起飞动画用：它要按<b>歌的时间</b>算抬升量，
     * 而 {@code sgPlayStart} 是<b>先</b>把状态写进表、<b>再</b>调钩子
     * {@code scSpawnPair} ⇒ 钩子里读得到这个值（原脚本读 {@code SG_PLAY[uuid].start}）。
     */
    long startOf(String uuid) {
        Play st = play.get(uuid);
        return st == null ? 0L : st.start;
    }

    /** 停掉某个玩家正在播的歌（把世代号作废，链上在途的那一步会自终止）。 */
    void playStop(String uuid) {
        try {
            hooks.onStop(uuid);      // ★ 生物和歌一起收（放在最前，下面早退也不会漏）
            Play st = play.get(uuid);
            if (st == null) {
                return;
            }
            st.gen = 0;              // 0 永远不等于任何真实世代号 ⇒ 旧链必断
            play.remove(uuid);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * 自调度链：每刻一步。被停掉（gen 变了）/ 退服 / 播完 ⇒ 断链。
     */
    private void playLoop(String uuid, long g) {
        GS.runLater(() -> {
            Play st = play.get(uuid);
            if (st == null || st.gen != g) {
                return;                        // ★ 已被停掉 / 取代 ⇒ 断链
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
                playStop(uuid);                // 退服 ⇒ 收尾并断链
                return;
            }
            hooks.onTick(uuid);                // ★ 咒术回战：转圈 + 拽回原地 + 战斗粒子
            playPulse(uuid);
            Play st2 = play.get(uuid);
            if (st2 != null && st2.gen == g) {
                playLoop(uuid, g);             // 没播完 ⇒ 排下一步
            }
        }, 1L);
    }

    /** 起播（先把上一首停掉）。 */
    void playStart(Player p) {
        try {
            String uuid = p.getUniqueId().toString();
            playStop(uuid);

            gen = gen + 1;                         // ★ 只增不减，绝不复位
            long g = gen;
            Play st = new Play();
            st.p = p;
            st.start = Guards.now();
            st.i = 0;
            st.gen = g;
            play.put(uuid, st);
            hooks.onStart(p);                      // ★ 咒术回战：召唤「宿瘫」与「五条舞」
            playLoop(uuid, g);
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] " + displayName + " 起播失败: " + e);
            } catch (Throwable e2) {
                // 静默
            }
        }
    }

    /** 心跳：把「到点了」的音符全放掉（由 {@link #playLoop} 每刻调一次）。 */
    private void playPulse(String uuid) {
        try {
            Play st = play.get(uuid);
            if (st == null) {
                return;                    // 已经被停掉了
            }
            Player p = st.p;
            boolean ok = true;
            try {
                if (p == null || !p.isOnline()) {
                    ok = false;
                }
            } catch (Throwable e) {
                ok = false;
            }
            if (!ok) {
                playStop(uuid);
                return;
            }

            long el = Guards.now() - st.start;     // 已经播了多少毫秒
            Location loc = null;
            while (st.i < n && tl[st.i * 3] <= el) {
                int code = tl[st.i * 3 + 1];
                String snd = sounds[code / 32];    // 高位 = 音效下标
                int vol = tl[st.i * 3 + 2];        // 0~100
                st.i++;                            // ★ 注意：先自增，再判空 —— 照抄原顺序
                if (snd == null) {
                    continue;
                }

                double v = volume * (vol / 100.0);
                Double g = gain.get(snd);
                if (g != null) {
                    v = v * g;
                }
                if (v <= 0) {
                    continue;
                }

                if (loc == null) {
                    loc = p.getLocation();         // 跟着玩家走
                }
                double pit = pitch[code % 32];
                try {
                    if (ownerOnly) {
                        p.playSound(loc, snd, (float) v, (float) pit);
                    } else {
                        p.getWorld().playSound(loc, snd, (float) v, (float) pit);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (st.i >= n) {
                playStop(uuid);                    // 播完了
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ------------------------------------------------------------ 入口

    /**
     * {@code onUse} 的共用实现 —— 返回 {@code true} 表示「这次真的起播了」。
     *
     * <p>顺序严格照抄原脚本：
     * ① 惰性重试注册拦截器 → ② 钩子 {@code beforeUse} → ③ 玩家判空
     * → ④ 只认主手 → ⑤ 去抖 → ⑥ 冷却（静默忽略）→ ⑦ 记冷却 → ⑧ 起播。
     */
    boolean onUse(PlayerRightClickEvent event) {
        try {
            // 惰性重试：加载时插件可能还没就绪
            registerGuard();
            hooks.beforeUse();

            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return false;
            }

            // ★ 只认主手触发
            if (!Guards.isMainHandUse(event, p, itemId)) {
                return false;
            }

            // ★ 去抖（一次右键的两个事件只认一个）
            if (debounced(p)) {
                return false;
            }

            // 起播 ⇒ 走冷却（冷却中**静默忽略**，物品不消耗）
            // ★ 「再按一次就停」已按用户要求删除。
            //   冷却正好等于曲长 ⇒ 整首歌期间再按都落在这里、被静默忽略。
            long left = cooldownLeft(p);
            if (left > 0) {
                cooldownHint(p, left);
                return false;
            }
            markUsed(p);

            playStart(p);
            return true;
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] " + displayName + " onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
            return false;
        }
    }
}
