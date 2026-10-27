package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.JsNum;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * {@code gs_listener.js} 的「梦境套 · 套装效果」子系统
 * （原脚本第 <b>877~1117</b> 行，顶层入口 {@code gsSetBonusStart()}）。
 *
 * <p><b>玩法</b>：每 <b>10 刻</b>（1 粘液刻）跑一次 {@code gsEquipPulse}，对每个在线玩家结算：
 * <ul>
 *   <li><b>四件齐全</b>（{@link ListenerCore#gsDreamFullSet(Player)}）⇒
 *       ① 开飞行（{@code setAllowFlight(true)}，创造/旁观模式不动 —— 他们本来就该能飞）；
 *       ② 常驻药效；③ 防辐射宽限期续期。</li>
 *   <li>缺任意一件 ⇒ 全部收走：关掉<b>本机制开的</b>飞行、收走<b>本机制给的</b>药效、
 *       取消防辐射保护标记。</li>
 * </ul>
 *
 * <p>★★ <b>药效续期</b>：一次给 {@code GS_SET_EFF_TICKS = 600} 刻（30 秒），
 * 剩余 {@code > GS_SET_EFF_REF = 540} 刻（27 秒）就<b>不重发</b> ——
 * 这样图标/画面不会因为反复重发而闪烁。等级逐字保留：
 * 生命恢复 II(amp 1) · 迅捷 II(amp 1) · 饱和 III(amp 2) · 抗性 IV(amp 3) · 急迫 III(amp 2) · 夜视(amp 0)。
 *
 * <p>★★ <b>摘套时只收走本机制给的那一份</b>：
 * <ul>
 *   <li>五种主药效：先按 uuid 取出「我给过的清单」，<b>逐个核对当前等级是否等于我给的等级</b>，
 *       对得上才 {@code removePotionEffect} —— 玩家自己后来喝了更高等级的同种药水就不会被误收。</li>
 *   <li>夜视：单独一套标记（{@code gsSetNightOn}）。玩家<b>自己喝的</b>夜视
 *       （{@code nvCur != null && !owned}）一律不碰、不接管、不登记。</li>
 * </ul>
 *
 * <p>★★ <b>防辐射</b>走 Slimefun 原版机制：
 * {@code RadiationTask.addGracePeriod(p)} 续 15 秒宽限期（与 Slimefun config 的
 * {@code options.radiation-grace-period} 对齐，见 {@link #GS_RAD_GRACE_MS}），
 * 刚凑齐的那一次额外用 {@code RadiationUtils.clearExposure(p)} 把历史暴露值清零。
 * <p>★ 原脚本用 {@code Java.type} 探测这两个类，<b>可能为 null</b> ⇒
 * 保留「拿不到就降级」的分支：{@link #RT_RAD} / {@link #RU_RAD} 为 null 时
 * 只标记状态、不做实际调用，并在启动时多打一条 <b>info</b> 日志
 * （{@link #gsRadUnavailableLine()}，走 {@link ListenerCore#gsDaLog} ——
 * 原脚本 1111 行用的就是 {@code gsDaLog}，<b>不是</b> warning，
 * 尽管需求描述里把它叫「记一条 warning」）。
 * Java 侧用 {@code Class.forName(..., initialize=false)} 复刻这个探测 ——
 * <b>不能</b>直接静态调用，否则 Slimefun 缺失时会在类加载期 {@code NoClassDefFoundError}，
 * 那就丢掉了原脚本的降级能力。
 *
 * <p>★★ <b>关于监听器</b>：原脚本的「梦境盔甲免死」（222~440 行，含
 * {@code pm.registerEvent(..., Priority.HIGHEST, ...)} 的<b>同步</b>监听器）
 * 属于<b>模块本体</b>，不在这里。本子系统（877~1117 行）里
 * <b>一处 {@code pm.registerEvent} 都没有</b>（全脚本 38 处 registerEvent，
 * 落在本行区间内的是 0 处）⇒ 本类<b>不</b>实现 {@code org.bukkit.event.Listener}，
 * 也就不存在「原脚本 reload 会累积监听器」那个缺陷。
 * 本子系统唯一的常驻任务是 {@code runRepeating}，靠<b>世代号</b>
 * （{@link ListenerCore#gsAuraIsCurrent()}，见 {@link AuraSub} 的类注释）自终止。
 *
 * <p>★ <b>死代码/降级分支</b>（逐条列出）：
 * <ol>
 *   <li>原脚本 957~958 行 {@code try { PE = Java.type('...PotionEffect'); } catch { PE = null; }
 *       if (PE == null) return;} —— Java 侧该类必然可用，这个 early return 恒不执行
 *       （已按「不照抄死分支」处理，不写进 Java）。</li>
 *   <li>原脚本 913~914 行 {@code PET = Java.type('...PotionEffectType'); if (PET == null) return null;}
 *       —— 同上，恒不执行（该函数在 {@link ListenerCore#gsSetPotionType} 里已实现）。</li>
 *   <li>原脚本 1021 / 1023 行的 {@code Java.type} 探测<b>不是</b>死代码：
 *       在没装 Slimefun 或换了包名的环境下真的会拿到 null ⇒ 保留（见上面防辐射段）。</li>
 *   <li>原脚本 884~886 行的注释提到「曾用 ConcurrentHashMap 准备给异步辐射事件共享，
 *       后来实测该事件根本进不来 JS」—— 那是<b>注释里的历史</b>，不是代码；
 *       {@code gsRadSafe} 就是普通对象，Java 侧对应 {@link ListenerCore#gsRadSafe}（HashMap，主线程独占）。</li>
 * </ol>
 *
 * <p>★ <b>跨子系统依赖</b>：{@code gsEquipPulse()} 的第二句是
 * {@code gsNoDurabilitySweepAll()}（原脚本 560~594 行），它属于「永不掉耐久」子系统。
 * 共享层里已经有 {@link ListenerCore#gsNoDurabilitySweepAll()} ⇒
 * {@link #gsNoDurabilitySweepAll()} 直接转发过去，<b>不需要集成者接线</b>。
 *
 * <p>★ <b>为「大量输入逐值对账」而做的纯函数抽取</b>（<b>算式一个字都没改</b>，
 * 抽出来只为让 {@code tools/ListenerASubParityProbe} 能把它们和真 JS 在
 * 成千上万组输入上逐值比对；调用点仍然是原来那段代码）：
 * <ul>
 *   <li>{@link #gsSetEffNeed(boolean, int, int, int)} —— 主药效「还需不需要补发」</li>
 *   <li>{@link #gsSetNightNeed(boolean, int)} —— 夜视「还需不需要补发」</li>
 *   <li>{@link #gsSetNightAction(boolean, boolean, int)} —— 夜视三分支（不碰 / 接管不补 / 接管并补）</li>
 *   <li>{@link #gsSetTakeAway(boolean, int, int)} —— 「该不该收走」（等级对得上才收）</li>
 * </ul>
 * 其中 {@code gsSetNightAction} 有一处<b>无副作用的重排</b>：原脚本把
 * {@code left = nvCur.getDuration()} 写在 {@code else} 分支里，这里提到分支之前 ——
 * 那只是一次 getter 读取，且「{@code nvCur == null} ⇒ 读时长抛异常 ⇒ left = 0」
 * 两种写法完全一致，可观察行为不变。
 */
public final class SetBonusSub {

    // ======================================================================
    // 常量：名字与 JS 顶层 var 同名（常量对账探针按名反射比对）
    // ======================================================================

    /** {@code GS_SET_EFF_TICKS} —— 药效一次给 30 秒（原脚本 897 行）。 */
    static final int GS_SET_EFF_TICKS = 600;

    /** {@code GS_SET_EFF_REF} —— 剩余低于 27 秒才续期（不闪图标 / 不闪画面）（原脚本 898 行）。 */
    static final int GS_SET_EFF_REF = 540;

    /** {@code GS_RAD_GRACE_MS} —— 与 Slimefun config 的 {@code options.radiation-grace-period} 对齐（原脚本 1018 行）。 */
    static final long GS_RAD_GRACE_MS = 15000;

    /**
     * {@code RT_RAD} —— 原脚本 1020~1021 行
     * {@code try { RT_RAD = Java.type('...tasks.armor.RadiationTask'); } catch (e) { RT_RAD = null; }}。
     *
     * <p>★ 用 {@code Class.forName(..., initialize = false)} 复刻：<b>只探测类是否存在</b>，
     * 不触发静态初始化（真机上第一次真正调用 {@code addGracePeriod} 时才初始化，
     * 与原脚本的时机一致）。拿不到 ⇒ null ⇒ 走降级分支。
     */
    static final Class<?> RT_RAD = gsRadProbe(
            "io.github.thebusybiscuit.slimefun4.implementation.tasks.armor.RadiationTask");

    /** {@code RU_RAD} —— 原脚本 1022~1023 行，同上的探测。 */
    static final Class<?> RU_RAD = gsRadProbe(
            "io.github.thebusybiscuit.slimefun4.utils.RadiationUtils");

    private final ListenerCore core;

    public SetBonusSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsSetBonusStart();} 调用（原脚本第 1117 行）。 */
    public void start() {
        gsSetBonusStart();
    }

    // ======================================================================
    // 跨子系统调用：gsNoDurabilitySweepAll()
    // ======================================================================

    /**
     * {@code gsEquipPulse()} 的第二句是「永不掉耐久」子系统的
     * {@code gsNoDurabilitySweepAll()}（原脚本 560~594 行）。
     *
     * <p>★ 共享层里已经有它：{@link ListenerCore#gsNoDurabilitySweepAll()}（public static）
     * ⇒ 这里<b>直接调用</b>，不需要任何钩子、也不需要集成者额外接线。
     * 这是与原脚本逐字对应的一行：
     * <pre>
     *   try {
     *       if (!gsAuraIsCurrent()) return;          // 重载后的旧任务 → 停手
     *       gsDreamSetPulse();                       // 梦境套齐全 → 入梦 / 免死 / 药效 / 防辐射
     *       gsNoDurabilitySweepAll();                // 兜底：把磨损的释梦/梦境套耐久补满
     *   } catch (e) {
     *   }
     * </pre>
     */
    private static void gsNoDurabilitySweepAll() {
        // ★ 不额外 catch：抛出的异常由 gsEquipPulse 的外层 try 吞掉，与原脚本一致
        ListenerCore.gsNoDurabilitySweepAll();
    }

    // ======================================================================
    // 防辐射（原脚本 1014~1050 行）
    // ======================================================================

    /** 原脚本 1021/1023 行的 {@code Java.type} 探测 —— 拿不到返回 null（= 原脚本的 catch 分支）。 */
    private static Class<?> gsRadProbe(String name) {
        try {
            return Class.forName(name, false, SetBonusSub.class.getClassLoader());
        } catch (Throwable e) {
            return null;
        }
    }

    /** {@code gsRadIsSafe(uuid)} —— 原脚本 893~895 行（{@code gsRadSafe[uuid] === true}）。 */
    private boolean gsRadIsSafe(String uuid) {
        return core.gsRadSafe.get(uuid) == Boolean.TRUE;
    }

    /** {@code gsRadClear(p)} —— 原脚本 1025~1027 行：把历史暴露值清零。 */
    public void gsRadClear(Player p) {
        try {
            if (RU_RAD != null) {
                RU_RAD.getMethod("clearExposure", Player.class).invoke(null, p);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * {@code gsRadShield(p, uuid, on)} —— 原脚本 1029~1050 行。
     *
     * <p>★ 顺序必须逐字保留：{@code on == false} ⇒ 只清标记；
     * {@code on == true} ⇒ <b>先读 was、再标记 true</b>，然后才判 {@code RT_RAD == null} 提前返回。
     * 也就是说「拿不到 RadiationTask」时 {@code gsRadSafe} 仍然会被标上 —— 这是原脚本的行为。
     */
    public void gsRadShield(Player p, String uuid, boolean on) {
        try {
            if (!on) {
                core.gsRadMark(uuid, false);
                return;
            }
            boolean was = gsRadIsSafe(uuid);
            core.gsRadMark(uuid, true);
            if (RT_RAD == null) {
                return;
            }

            // 续宽限期（每 0.5 秒一次；15 秒的窗口，刷新频率绰绰有余）
            try {
                RT_RAD.getMethod("addGracePeriod", Player.class).invoke(null, p);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            // 刚凑齐的那一次 → 把历史暴露值清零
            if (!was) {
                gsRadClear(p);
                ListenerCore.gsDaLog(gsRadShieldLine());
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 套装药效（原脚本 908~1012 行）
    // ======================================================================

    /** 原脚本 960~966 行 {@code effs} 表里的一行。★ 包内可见：探针要按表对账。 */
    static final class Eff {
        final String name;
        final String alt;
        final int amp;

        Eff(String name, String alt, int amp) {
            this.name = name;
            this.alt = alt;
            this.amp = amp;
        }
    }

    /**
     * 原脚本 960~966 行的 {@code effs} 表，逐字照抄（顺序也不能换）。★ 包内可见：探针要对账。
     *
     * <p>★ <b>{@code alt} 两列在 1.21.11 上是死代码</b>：{@code DAMAGE_RESISTANCE} 与
     * {@code FAST_DIGGING} 已经<b>不是</b> {@code PotionEffectType} 的静态字段
     * （{@code javap} + 探针实测），而主名 {@code RESISTANCE} / {@code HASTE} 都在 ⇒
     * {@link ListenerCore#gsSetPotionType} 的第一次查询必然命中，兜底那一次永远走不到。
     * 保留它们只是为了与原脚本逐字对齐（原脚本的注释也说了「旧版才叫这两个名字」）。
     */
    static final List<Eff> GS_SET_EFFS = List.of(
            new Eff("REGENERATION", null, 1),                  // 生命恢复 II
            new Eff("SPEED", null, 1),                         // 迅捷 II
            new Eff("SATURATION", null, 2),                    // 饱和 III
            new Eff("RESISTANCE", "DAMAGE_RESISTANCE", 3),     // 抗性提升 IV
            new Eff("HASTE", "FAST_DIGGING", 2)                // 急迫 III
    );

    // ---------------------------------------------------------------- 纯判定（只为对账，算式逐字照抄）

    /**
     * 「这份药效还需不需要补发」—— 原脚本 978~983 行的判定，逐字照抄：
     * <pre>
     *   need = true;
     *   if (c != null &amp;&amp; c.getAmplifier() === wantAmp &amp;&amp; c.getDuration() &gt; GS_SET_EFF_REF) need = false;
     * </pre>
     *
     * <p>★ 抽成静态方法<b>只为能在大量输入上与真 JS 逐值对账</b>
     * （{@code ListenerASubParityProbe}），算式本身一个字都没改。
     *
     * @param has      玩家身上是否已有这种药效（{@code c != null}）
     * @param curAmp   现有药效的等级
     * @param curDur   现有药效的剩余刻数
     * @param wantAmp  本机制要给的等级
     */
    static boolean gsSetEffNeed(boolean has, int curAmp, int curDur, int wantAmp) {
        return !(has && curAmp == wantAmp && curDur > GS_SET_EFF_REF);
    }

    /**
     * 「夜视还需不需要补发」—— 原脚本 1005 行的判定，逐字照抄：
     * {@code if (nvCur == null || left <= GS_SET_EFF_REF)}。
     *
     * <p>★ 抽出来只为对账；注意边界是 {@code <=}（与主药效的 {@code >} 互补）。
     */
    static boolean gsSetNightNeed(boolean has, int left) {
        return !has || left <= GS_SET_EFF_REF;
    }

    /**
     * 「这份药效该不该收走」—— 原脚本 945~948 行的判定，逐字照抄：
     * <pre>
     *   var cur = p.getPotionEffect(t);
     *   if (cur == null) continue;
     *   if (cur.getAmplifier() !== recAmp) continue;
     *   p.removePotionEffect(t);
     * </pre>
     *
     * <p>★ 抽出来只为对账：<b>等级对得上才收</b>，所以玩家自己后来喝的更高等级药水不会被误收。
     */
    static boolean gsSetTakeAway(boolean has, int curAmp, int recAmp) {
        return has && curAmp == recAmp;
    }

    /**
     * 夜视的<b>三分支</b>判定 —— 原脚本 999~1008 行的结构，逐字照抄：
     * <pre>
     *   if (nvCur != null &amp;&amp; !owned) {
     *       // 别人给的 → 不触发、不接管、不登记
     *   } else {
     *       gsSetNightOn[uuid] = true;
     *       var left = 0;
     *       try { left = nvCur.getDuration(); } catch (e) { left = 0; }
     *       if (nvCur == null || left &lt;= GS_SET_EFF_REF) { 给药 }
     *   }
     * </pre>
     *
     * <p>★ 抽出来只为对账（探针用大量 (has, owned, left) 组合与真 JS 逐值比对）。
     * ★ 唯一的改写：原脚本把 {@code left} 的计算写在 {@code else} 分支里，
     * 这里提到调用点之前 —— 那只是一次 getter 读取、无副作用，
     * 且「{@code nvCur == null} ⇒ 读时长抛异常 ⇒ left = 0」两种写法完全一致，
     * 所以可观察行为不变。
     *
     * @return {@code 0} = 别人给的（不碰）· {@code 1} = 接管但不补发 · {@code 2} = 接管并补发
     */
    static int gsSetNightAction(boolean has, boolean owned, int left) {
        if (has && !owned) {
            return 0;
        }
        return gsSetNightNeed(has, left) ? 2 : 1;
    }

    /**
     * {@code gsSetEffects(p, want)} —— 原脚本 926~1012 行。
     *
     * <p>★ {@code want == false} 是「收走」，{@code true} 是「给 / 续期」。
     *
     * <p>★ <b>记录格式的内部编码差异</b>（唯一一处，已在交付说明里列出）：
     * 原脚本往 {@code gsSetEffOn[uuid]} 里存的是 {@code {t: PotionEffectType, amp: int}}；
     * {@link ListenerCore#gsSetEffOn} 的类型是 {@code Map<String, List<int[]>>}，
     * 装不下 {@code PotionEffectType} ⇒ 这里编码成 {@code int[]{effs 表下标, amp}}，
     * 收走时用同一个 {@code name / alt} 重新解析回 {@code PotionEffectType}
     * （{@code PotionEffectType.getByName} 返回的是注册表里的<b>同一个单例</b>，
     * 所以「等级对不对得上」的判定与原脚本完全一致）。
     * 该编码<b>只在本类内读写</b>，不改变任何可观察行为。
     * （★ 不用 {@code PotionEffectType.getId()} 编码 —— 它已被标记为
     * {@code deprecated for removal}，用它会让本模块在未来 Paper 上编译不过。）
     */
    public void gsSetEffects(Player p, boolean want) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // ================= 收走 =================
            if (!want) {
                // ① 夜视（单独一套标记：玩家自己喝的夜视一律不碰）
                if (core.gsSetNightOn.get(uuid) == Boolean.TRUE) {
                    core.gsSetNightOn.put(uuid, Boolean.FALSE);
                    PotionEffectType nvT = ListenerCore.gsSetPotionType("NIGHT_VISION", null);
                    if (nvT != null) {
                        try {
                            p.removePotionEffect(nvT);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
                // ② 其余五种（等级对得上，才认定是「我们给的那一份」）
                List<int[]> arr = core.gsSetEffOn.get(uuid);
                core.gsSetEffOn.put(uuid, null);
                if (arr != null) {
                    for (int[] rec : arr) {
                        try {
                            if (rec[0] < 0 || rec[0] >= GS_SET_EFFS.size()) {
                                continue;
                            }
                            Eff ef = GS_SET_EFFS.get(rec[0]);
                            PotionEffectType t = ListenerCore.gsSetPotionType(ef.name, ef.alt);
                            if (t == null) {
                                continue;
                            }
                            PotionEffect cur = p.getPotionEffect(t);
                            // ★ 等级对得上才收（算式见 gsSetTakeAway 的注释）
                            if (!gsSetTakeAway(cur != null,
                                    cur == null ? 0 : cur.getAmplifier(), rec[1])) {
                                continue;
                            }
                            p.removePotionEffect(t);
                        } catch (Throwable ignored) {
                            // 与原脚本一致：静默
                        }
                    }
                }
                return;
            }

            // ================= 给 =================
            List<int[]> applied = new ArrayList<>();
            for (int j = 0; j < GS_SET_EFFS.size(); j++) {
                Eff ef = GS_SET_EFFS.get(j);
                PotionEffectType et = ListenerCore.gsSetPotionType(ef.name, ef.alt);
                if (et == null) {
                    continue;
                }

                // ★ 先登记（不论本次是否真的补发），摘套时才知道该清哪些
                applied.add(new int[]{j, ef.amp});

                // 剩余还够（等级对得上 且 > 27 秒）→ 不重复给，避免图标闪烁
                boolean need = true;
                try {
                    PotionEffect c = p.getPotionEffect(et);
                    // ★ 算式见 gsSetEffNeed 的注释（抽出来只为对账）
                    need = gsSetEffNeed(c != null,
                            c == null ? 0 : c.getAmplifier(),
                            c == null ? 0 : c.getDuration(),
                            ef.amp);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默（need 保持 true）
                }
                if (!need) {
                    continue;
                }

                // ambient = true（金色边）、particles = false（不冒粒子）
                try {
                    p.addPotionEffect(new PotionEffect(et, GS_SET_EFF_TICKS, ef.amp, true, false), true);
                } catch (Throwable e) {
                    try {
                        p.addPotionEffect(new PotionEffect(et, GS_SET_EFF_TICKS, ef.amp, true, false));
                    } catch (Throwable ignored) {
                        // 与原脚本一致：静默
                    }
                }
            }
            core.gsSetEffOn.put(uuid, applied);

            // ③ 夜视：玩家当前**已有夜视**（自己喝的）且不是本机制给的 → 完全不碰
            PotionEffectType nvT2 = ListenerCore.gsSetPotionType("NIGHT_VISION", null);
            if (nvT2 != null) {
                boolean owned = false;
                try {
                    owned = (core.gsSetNightOn.get(uuid) == Boolean.TRUE);
                } catch (Throwable e) {
                    owned = false;
                }
                PotionEffect nvCur = null;
                try {
                    nvCur = p.getPotionEffect(nvT2);
                } catch (Throwable e) {
                    nvCur = null;
                }

                int left = 0;
                try {
                    left = nvCur.getDuration();
                } catch (Throwable e) {
                    left = 0;
                }

                // ★ 三分支判定（算式见 gsSetNightAction 的注释；抽出来只为对账）
                int act = gsSetNightAction(nvCur != null, owned, left);
                if (act == 0) {
                    // 别人给的 → 不触发、不接管、不登记
                } else {
                    core.gsSetNightOn.put(uuid, Boolean.TRUE);
                    if (act == 2) {
                        try {
                            p.addPotionEffect(new PotionEffect(nvT2, GS_SET_EFF_TICKS, 0, true, false), true);
                        } catch (Throwable e) {
                            try {
                                p.addPotionEffect(new PotionEffect(nvT2, GS_SET_EFF_TICKS, 0, true, false));
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 每次脉冲（原脚本 1052~1093 行）
    // ======================================================================

    /** {@code gsDreamSetPulse()} —— 对每个在线玩家结算套装效果。 */
    public void gsDreamSetPulse() {
        Collection<? extends Player> players = Bukkit.getServer().getOnlinePlayers();
        for (Player p : players) {
            try {
                String uuid = String.valueOf(p.getUniqueId());

                // 创造 / 旁观模式：飞行不动（他们本来就该能飞）
                boolean creative = false;
                try {
                    String gm = String.valueOf(p.getGameMode().name());
                    creative = "CREATIVE".equals(gm) || "SPECTATOR".equals(gm);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }

                if (ListenerCore.gsDreamFullSet(p)) {
                    // ① 入梦（飞行）—— ★ 不再提示「入梦」（除提示词）
                    if (creative) {
                        core.gsDreamFlyOn.put(uuid, Boolean.FALSE);
                    } else {
                        boolean allow = false;
                        try {
                            allow = p.getAllowFlight();
                        } catch (Throwable e) {
                            allow = false;
                        }
                        if (!allow) {
                            try {
                                p.setAllowFlight(true);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                        core.gsDreamFlyOn.put(uuid, Boolean.TRUE);
                    }
                    // ② 常驻药效
                    gsSetEffects(p, true);
                    // ③ 防辐射（续宽限期 · 主线程）
                    gsRadShield(p, uuid, true);
                } else {
                    // 摘掉任意一件 → 全部收走
                    if (core.gsDreamFlyOn.get(uuid) == Boolean.TRUE) {
                        if (!creative) {
                            try {
                                p.setAllowFlight(false);
                            } catch (Throwable ignored) {
                                // 与原脚本一致：静默
                            }
                        }
                        core.gsDreamFlyOn.put(uuid, Boolean.FALSE);
                    }
                    gsSetEffects(p, false);
                    gsRadShield(p, uuid, false);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默（单个玩家出错不影响其他人）
            }
        }
    }

    // ======================================================================
    // 装备维护任务（原脚本 1095~1103 行）
    // ======================================================================

    /** {@code gsEquipPulse()} —— 每 10 刻跑一次。 */
    public void gsEquipPulse() {
        try {
            if (!core.gsAuraIsCurrent()) {
                return;                               // 重载后的旧任务 → 停手
            }
            gsDreamSetPulse();                        // 梦境套齐全 → 入梦 / 免死 / 药效 / 防辐射
            gsNoDurabilitySweepAll();                 // 兜底：把磨损的释梦/梦境套耐久补满
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 启动（原脚本 1105~1115 行）
    // ======================================================================

    /** {@code gsSetBonusStart()} —— 排常驻任务 + 打加载日志。 */
    public void gsSetBonusStart() {
        try {
            // 每 1 粘液刻 = 10 游戏刻
            GS.runRepeating(new Runnable() {
                @Override
                public void run() {
                    gsEquipPulse();
                }
            }, 10L, 10L);

            ListenerCore.gsDaLog(gsSetBonusStartLine(core.gsDaConfig().cooldown));
            if (RT_RAD == null) {
                ListenerCore.gsDaLog(gsRadUnavailableLine());
            }
        } catch (Throwable e) {
            try {
                Bukkit.getServer().getLogger().warning("[构思技艺] 梦境套套装效果启动失败: " + e);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ======================================================================
    // 日志行（抽成静态方法，供探针逐字节对账）
    // ======================================================================

    /**
     * 原脚本 1109~1110 行的加载日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 4 行）：
     * <pre>
     * [构思技艺] 梦境套：套装效果已就绪（入梦·飞行 / 免死 45 秒 / 生命恢复II·夜视·迅捷II·饱和III·抗性IV·急迫III / 防辐射(宽限期) / 粒子圈）
     * </pre>
     *
     * <p>★★ <b>{@code 45} 来自 {@code gsDaConfig().cooldown / 1000}</b>，而 JS 的
     * {@code /} 是<b>浮点除法</b>：{@code cooldown-millis} 配成 {@code 45500} 时
     * JS 打出的是 {@code 45.5}，Java 写 {@code cooldownMs / 1000}（整数除法）会打出
     * {@code 45} —— 这是一处<b>真实的日志差异</b>（配置不是 1000 的整数倍时现网可见）。
     * 所以这里用 {@code / 1000.0} + {@link JsNum#str(double)} 精确复刻 JS 的
     * 数字→字符串语义（默认值 45000 ⇒ {@code 45.0} ⇒ {@code "45"}，与参考服日志逐字节一致）。
     */
    static String gsSetBonusStartLine(long cooldownMs) {
        return "梦境套：套装效果已就绪（入梦·飞行 / 免死 " + JsNum.str(cooldownMs / 1000.0)
                + " 秒 / 生命恢复II·夜视·迅捷II·饱和III·抗性IV·急迫III / 防辐射(宽限期) / 粒子圈）";
    }

    /**
     * 原脚本 1111 行的日志（不含前缀）。
     *
     * <p>★ <b>运行期条件触发</b>：只有 {@code RT_RAD == null}（拿不到 RadiationTask）才打，
     * 所以它<b>不在</b> {@code docs/参考服日志_构思技艺.txt} 里 ——
     * 参考服装了 Slimefun，探测必然成功。这是<b>预期缺席</b>，不是漏移植。
     * ★ 另外它是 {@code gsDaLog}（<b>info</b>）而不是 warning ——
     * 尽管需求描述里把它叫「记一条 warning」。
     */
    static String gsRadUnavailableLine() {
        return "梦境套：⚠ 拿不到 RadiationTask → 防辐射不可用";
    }

    /**
     * 原脚本 1046~1047 行的日志（不含前缀）。
     *
     * <p>★ <b>运行期条件触发</b>：要有玩家穿着梦境套四件齐全（且是"刚凑齐"的那一次）才打，
     * 参考服的启动日志里<b>没有</b>这一行 —— 参考服是空服启动，没人穿全套。
     * 同样是<b>预期缺席</b>，不是漏移植。
     *
     * <pre>
     * 梦境套：防辐射已生效 —— 宽限期持续刷新（每 0.5 秒一次，窗口 15 秒）
     * </pre>
     *
     * <p>★ {@code GS_RAD_GRACE_MS / 1000} 同样是 JS 浮点除法 ⇒ 用
     * {@link JsNum#str(double)}（常量 15000 ⇒ {@code 15.0} ⇒ {@code "15"}）。
     */
    static String gsRadShieldLine() {
        return "梦境套：防辐射已生效 —— 宽限期持续刷新（每 0.5 秒一次，窗口 "
                + JsNum.str(GS_RAD_GRACE_MS / 1000.0) + " 秒）";
    }
}
