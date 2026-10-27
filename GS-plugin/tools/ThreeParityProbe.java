package com.qinghuacisj.gs.script.module;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code gs_boss_three} 的对账探针 —— 「妄念」三阶段 boss。
 *
 * <p>四类断言，全部<b>机械可复现</b>：
 * <ol>
 *   <li><b>[A] 常量对账</b>：用真 GraalJS 跑 {@code gs_boss_three.js}，读出
 *       <b>全部 109 个顶层 {@code var}</b>，与 {@link ThreeModule} 的字段
 *       <b>按名按值</b>逐个比对（98 个 {@code static final} 常量必须全部对上；
 *       11 个运行期状态变量是实例字段，单独钉住"确实没被搬到静态常量里"）。</li>
 *   <li><b>[B] 加载日志行逐字节对账</b>：6 条日志与
 *       {@code docs/参考服日志_构思技艺.txt}（真 JS 在参考服上打出来的）逐字节相同。</li>
 *   <li><b>[C] 几何 / 数值扫描</b>：把真 JS 的算式跑起来，与 Java 在<b>上万组输入</b>上
 *       逐值比对 —— 含<b>用 {@link Proxy} 造的合成世界</b>驱动的
 *       {@code btPassableAt} / {@code btSafeY} / {@code btDragSlot} / {@code btAxeBlocked}
 *       （JS 与 Java <b>走同一个假世界对象</b>，所以两边比的是同一件事）。</li>
 *   <li><b>[D] 反向断言</b>：把"<b>不应该存在</b>的东西"也钉住 ——
 *       已删除的 {@code BT_DRAG_MIN} / {@code BT_S3_FIRE_*}、恒为 0 的距离门槛、
 *       不存在的 {@code EntityCastSpellEvent} / {@code LootTables.EMPTY}、
 *       以及 yml 里"残樱配方已删除"这个动作。</li>
 * </ol>
 *
 * <p>★★ <b>假世界为什么长这样</b>：{@code btPassableAt} 在真机上走
 * {@code Block#getType().isAir()} / {@code Block#isPassable()}。裸 JVM 里
 * {@code Material.isAir()} 会因 {@code No RegistryAccess implementation found}
 * 抛 {@code ExceptionInInitializerError}（已实测）。所以：
 * <ul>
 *   <li>假 {@code Block} 的 {@code getType()} 返回<b>真的 {@code Material}</b>
 *       （AIR / STONE / GLASS）⇒ JS 与 Java 都会尝试 {@code isAir()}，
 *       都会吃到同一个 {@code ExceptionInInitializerError}，都被各自的
 *       {@code try/catch} 吞掉，然后<b>都落到 {@code Block#isPassable()}</b>
 *       —— 已实测 GraalJS 的 {@code catch (e)} <b>能</b>接住这个 Error
 *       （{@code build/T4.java} 的实测输出：{@code mt=AIR isAir=THREW passable=false}）。</li>
 *   <li>所以两边的判定<b>完全同源</b>：{@code passable == (地形值 == 0)}。
 *       {@code isAir} 早退分支与 {@code !isSolid()} 退路在裸 JVM 里<b>两边都走不到</b>，
 *       由真服测试覆盖 —— 这里比的是<b>扫描逻辑本身</b>（边界、钳制、抬升、落点）。</li>
 * </ul>
 */
public final class ThreeParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 脚本里"运行期状态"的顶层 var（非 static final，不参与常量对账，但必须被认出）。 */
    private static final Set<String> STATE_VARS = Set.of(
            "BT_ACTIVE", "BT_LAST", "BT_KEY", "BT_FANG_KEY", "BT_PDT",
            "BT_MAXATTR", "BT_MUSIC_PICKED", "BT_HP_DIAG",
            "BT_REG_DEATH", "BT_REG_SPELL", "BT_GUARD_OK");

    public static void main(String[] args) throws Exception {
        Path scriptDir = Path.of(args.length > 0 ? args[0] : "../QHCGS/GS/GS/scripts");
        Path logFile = Path.of(args.length > 1 ? args[1] : "docs/参考服日志_构思技艺.txt");
        Path contentDir = Path.of(args.length > 2 ? args[2] : "content");
        Path classFile = Path.of(args.length > 3 ? args[3]
                : "build/sub_three/com/qinghuacisj/gs/script/module/ThreeModule.class");

        System.out.println("=== gs_boss_three（妄念 · 三阶段 boss）对账探针 ===");
        System.out.println();

        Path js = scriptDir.resolve("gs_boss_three.js");
        if (!Files.isRegularFile(js)) {
            System.out.println("!! 找不到脚本: " + js.toAbsolutePath());
            System.exit(1);
        }

        String source = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.eval(Source.newBuilder("js", source, "gs_boss_three.js").build());
            ctx.eval(Source.newBuilder("js", MATH_JS, "math").build());

            constants(ctx);
            logLines(logFile, source);
            sweeps(ctx);
        }

        reverse(contentDir, classFile, source);

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // [A] 常量对账
    // ==================================================================

    private static void constants(Context ctx) throws Exception {
        System.out.println("[A] 常量对账：真 JS 的 109 个顶层 var vs ThreeModule 的字段");

        Value b = ctx.getBindings("js");

        // ① JS 侧：所有 BT_ 开头的顶层 var
        Set<String> jsNames = new LinkedHashSet<>();
        for (String k : b.getMemberKeys()) {
            if (k.startsWith("BT_")) {
                jsNames.add(k);
            }
        }
        check("JS 顶层 var 共 " + jsNames.size() + " 个（期望 109）", jsNames.size() == 109);

        // ② Java 侧：static final 常量逐个按名按值比对
        int matched = 0;
        int stateFound = 0;
        List<String> missing = new ArrayList<>();
        List<String> mismatched = new ArrayList<>();

        for (Field f : ThreeModule.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isFinal(f.getModifiers())) {
                continue;
            }
            String name = f.getName();
            if (!name.startsWith("BT_")) {
                continue;
            }
            f.setAccessible(true);
            Object javaVal = f.get(null);
            if (!b.hasMember(name)) {
                missing.add(name);
                continue;
            }
            matched++;
            Object jsVal = toJava(b.getMember(name));
            if (!sameValue(jsVal, javaVal)) {
                mismatched.add(name + "  JS=" + show(jsVal) + " (" + typeOf(jsVal) + ")"
                        + "  Java=" + show(javaVal) + " (" + typeOf(javaVal) + ")");
            }
        }

        check("Java 侧 static final 常量共 " + matched + " 个（期望 98）", matched == 98);
        check("每个 Java 常量都能在 JS 里按名找到（缺失 " + missing.size() + " 个）"
                + (missing.isEmpty() ? "" : " " + missing), missing.isEmpty());
        if (!mismatched.isEmpty()) {
            for (String s : mismatched) {
                System.out.println("      不一致 " + s);
            }
        }
        check("每个常量的值都逐值相同（不一致 " + mismatched.size() + " 个）", mismatched.isEmpty());

        // ③ JS 侧每个 var 都必须被认领：要么是常量，要么是已知状态变量
        List<String> unclaimed = new ArrayList<>();
        for (String n : jsNames) {
            if (STATE_VARS.contains(n)) {
                stateFound++;
                continue;
            }
            boolean isConst = false;
            try {
                Field f = ThreeModule.class.getDeclaredField(n);
                isConst = Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers());
            } catch (NoSuchFieldException ignored) {
                isConst = false;
            }
            if (!isConst) {
                unclaimed.add(n);
            }
        }
        check("JS 的 11 个运行期状态变量都被认出（认出 " + stateFound + " 个）", stateFound == 11);
        check("没有漏网的 JS 顶层 var（漏 " + unclaimed.size() + " 个）"
                + (unclaimed.isEmpty() ? "" : " " + unclaimed), unclaimed.isEmpty());
        check("98 常量 + 11 状态 = 109，与 JS 顶层 var 总数一致",
                matched + stateFound == jsNames.size());

        // ④ 几个"命门"数值单独再钉一遍（防止有人把常量名改了却侥幸对不上）
        check("BT_S1_HP = 300", ThreeModule.BT_S1_HP == 300.0);
        check("BT_S2_HP = 800", ThreeModule.BT_S2_HP == 800.0);
        check("BT_S3_HP = 1000", ThreeModule.BT_S3_HP == 1000.0);
        check("BT_S1_DMG = 15", ThreeModule.BT_S1_DMG == 15.0);
        check("BT_S2_DMG = 20", ThreeModule.BT_S2_DMG == 20.0);
        check("BT_S3_DMG = 40", ThreeModule.BT_S3_DMG == 40.0);
        check("BT_S1_ARROW_DMG = 12", ThreeModule.BT_S1_ARROW_DMG == 12.0);
        check("BT_S1_ATK_CD = 1500ms", ThreeModule.BT_S1_ATK_CD == 1500L);
        check("BT_S1_FANG_DMG = 15 / RANGE = 12 / CD = 10000",
                ThreeModule.BT_S1_FANG_DMG == 15.0
                        && ThreeModule.BT_S1_FANG_RANGE == 12.0
                        && ThreeModule.BT_S1_FANG_CD == 10000L);
        check("BT_S1_BLINK_READ = 20 刻 / BACK = 8 / HEAL = 50 / CD = 20000",
                ThreeModule.BT_S1_BLINK_READ == 20
                        && ThreeModule.BT_S1_BLINK_BACK == 8.0
                        && ThreeModule.BT_S1_BLINK_HEAL == 50.0
                        && ThreeModule.BT_S1_BLINK_CD == 20000L);
        check("BT_S2_SLASH_DMG = 35 / RANGE = 4 / CD = 10000 / 破盾 = 100 刻",
                ThreeModule.BT_S2_SLASH_DMG == 35.0
                        && ThreeModule.BT_S2_SLASH_RANGE == 4.0
                        && ThreeModule.BT_S2_SLASH_CD == 10000L
                        && ThreeModule.BT_S2_SLASH_SHIELD_CD == 100);
        check("BT_S2_AXE_CHARGE = 30 刻 / RANGE = 10 / DMG = 35 / CD = 12000",
                ThreeModule.BT_S2_AXE_CHARGE == 30
                        && ThreeModule.BT_S2_AXE_RANGE == 10.0
                        && ThreeModule.BT_S2_AXE_DMG == 35.0
                        && ThreeModule.BT_S2_AXE_CD == 12000L);
        check("妄想：DMG = 7 / HP = 25 / LIFE = 180 刻 / MAX = 3 / TAG = wangnian2_vex",
                ThreeModule.BT_S2_VEX_DMG == 7.0
                        && ThreeModule.BT_S2_VEX_HP == 25.0
                        && ThreeModule.BT_S2_VEX_LIFE == 180
                        && ThreeModule.BT_S2_VEX_MAX == 3
                        && "wangnian2_vex".equals(ThreeModule.BT_S2_VEX_TAG));
        check("BT_S3_SLAM_DMG = 150 / R = 3.5 / CD = 11000 / CHARGE = 20 / HOVER = 20",
                ThreeModule.BT_S3_SLAM_DMG == 150.0
                        && ThreeModule.BT_S3_SLAM_R == 3.5
                        && ThreeModule.BT_S3_SLAM_CD == 11000L
                        && ThreeModule.BT_S3_SLAM_CHARGE == 20
                        && ThreeModule.BT_S3_SLAM_HOVER == 20);
        check("★ 锁链拽回六值：出场延迟 10000 / 冷却 18000 / 读条 20 / 范围 64 / 落点半径 1.6 / 单次 8",
                ThreeModule.BT_DRAG_FIRST_MS == 10000L
                        && ThreeModule.BT_DRAG_CD == 18000L
                        && ThreeModule.BT_DRAG_READ == 20
                        && ThreeModule.BT_DRAG_RANGE == 64.0
                        && ThreeModule.BT_DRAG_RING == 1.6
                        && ThreeModule.BT_DRAG_CAP == 8);
        check("★ 锁链不设距离门槛：TRIG_S1/S2/S3 全为 0.0",
                ThreeModule.BT_DRAG_TRIG_S1 == 0.0
                        && ThreeModule.BT_DRAG_TRIG_S2 == 0.0
                        && ThreeModule.BT_DRAG_TRIG_S3 == 0.0);
        check("★ 掉落：碎片只有三阶段 6 个 / 残樱只有二阶段 1 把",
                ThreeModule.BT_FRAG_DROP[0] == 0 && ThreeModule.BT_FRAG_DROP[1] == 0
                        && ThreeModule.BT_FRAG_DROP[2] == 6
                        && ThreeModule.BT_CHERRY_DROP[0] == 0 && ThreeModule.BT_CHERRY_DROP[1] == 1
                        && ThreeModule.BT_CHERRY_DROP[2] == 0);
        check("★ 出场保护 4000ms / 心跳 5 刻 / 三阶段名 §f幻念僧·碎念执·妄念魔",
                ThreeModule.BT_FIRST_MS == 4000L
                        && ThreeModule.BT_TICK == 5
                        && "\u00a7f\u5e7b\u5ff5\u50e7".equals(ThreeModule.BT_S1_NAME)
                        && "\u00a7f\u788e\u5ff5\u6267".equals(ThreeModule.BT_S2_NAME)
                        && "\u00a7f\u5984\u5ff5\u9b54".equals(ThreeModule.BT_S3_NAME));

        System.out.println();
    }

    // ==================================================================
    // [B] 加载日志行逐字节对账
    // ==================================================================

    private static void logLines(Path logFile, String jsSource) throws Exception {
        System.out.println("[B] 加载日志行 vs 参考服日志（逐字节）");
        jsSourceLines = List.of(jsSource.split("\r?\n", -1));

        if (!Files.isRegularFile(logFile)) {
            check("找得到参考服日志 " + logFile.toAbsolutePath(), false);
            System.out.println();
            return;
        }
        List<String> ref = Files.readAllLines(logFile, StandardCharsets.UTF_8);

        String[] mine = ThreeModule.loadLogLines();
        check("本模块的加载日志集合恰好 6 行（loadLogLines().length）", mine.length == 6);

        String[] labels = {
                "① 加载标记（v2026-10-07c 锁链出场延迟10秒·坠击11秒冷却）",
                "② 三阶段目标血量",
                "③ 碎念执 技能2 星环飞斧 + 妄想",
                "④ 妄想碎片掉落（只有第三阶段 6 个）",
                "⑤ 残樱掉落（只有第二阶段 1 把）+ 配方已删除",
                "⑥ 锁链拽回（出场延迟10秒·冷却18秒·读条1秒·范围64格·落点半径1.6·单次8人）",
        };

        for (int i = 0; i < mine.length; i++) {
            String want = mine[i];
            int hit = -1;
            int count = 0;
            for (int k = 0; k < ref.size(); k++) {
                if (ref.get(k).equals(want)) {
                    hit = k;
                    count++;
                }
            }
            if (count != 1) {
                check("日志行 " + labels[i] + "：在参考服日志里<b>恰好</b>出现 1 次（实际 " + count + " 次）", false);
                if (count == 0) {
                    System.out.println("        我方：" + hex(want));
                    // 找出最像的那一行，方便定位差在哪一个字符
                    int best = -1;
                    int bestScore = -1;
                    for (int k = 0; k < ref.size(); k++) {
                        String r = ref.get(k);
                        if (!r.startsWith("[构思技艺]")) {
                            continue;
                        }
                        int sc = commonPrefix(r, want);
                        if (sc > bestScore) {
                            bestScore = sc;
                            best = k;
                        }
                    }
                    if (best >= 0) {
                        System.out.println("        参考：" + hex(ref.get(best)));
                        System.out.println("        首个不同字符位置 = " + bestScore);
                    }
                }
                continue;
            }
            check("日志行 " + labels[i] + " 逐字节相同（参考服第 " + (hit + 1) + " 行）", true);
        }

        // ★ 参考服日志里这 6 行是连续的第 63~68 行
        boolean contiguous = true;
        for (int i = 0; i < mine.length; i++) {
            if (!ref.get(62 + i).equals(mine[i])) {
                contiguous = false;
            }
        }
        check("这 6 行在参考服日志里连续出现在第 63~68 行（顺序也一致）", contiguous);

        // ★ 反向：这 6 行里<b>不得</b>出现 %s（本脚本全部是字符串拼接，不是参数化重载）
        boolean noPct = true;
        for (String s : mine) {
            if (s.contains("%s")) {
                noPct = false;
            }
        }
        check("反向：6 条日志行里没有 %s（原脚本这里是拼接、不是 info(fmt, args) 重载）", noPct);

        // ★★ 反向：本模块的加载日志集合 <b>恰好等于</b>参考服日志里那 6 行
        //    —— 用"只可能属于本脚本"的标记去捞参考服日志，捞出来的必须不多不少
        //       正好是这 6 行（顺序也一致）。日后有人"顺手多补一行"会被这条拦住。
        String[] markers = {"gs_boss_three", "妄念", "碎念执", "锁链拽回", "星环飞斧", "妄想碎片"};
        List<String> owned = new ArrayList<>();
        for (String r : ref) {
            for (String mk : markers) {
                if (r.contains(mk)) {
                    owned.add(r);
                    break;
                }
            }
        }
        Set<String> ownedSet = new LinkedHashSet<>(owned);
        Set<String> mineSet = new LinkedHashSet<>(List.of(mine));
        check("反向：参考服日志里只可能属于本脚本的行恰好 " + ownedSet.size() + " 条（期望 6）",
                ownedSet.size() == 6);
        check("反向：捞出来的这 6 条与本模块的 6 条<b>逐字节完全相同</b>（无第 7 条、无遗漏）",
                ownedSet.equals(mineSet));
        if (!ownedSet.equals(mineSet)) {
            List<String> extra = new ArrayList<>(ownedSet);
            extra.removeAll(mineSet);
            List<String> absent = new ArrayList<>(mineSet);
            absent.removeAll(ownedSet);
            System.out.println("        多出来的：" + extra);
            System.out.println("        我方多写的：" + absent);
        }
        // 顺序也要一致（参考服里这 6 条的出现顺序 = loadLogLines() 的顺序）
        boolean sameOrder = owned.size() == 6;
        for (int i = 0; sameOrder && i < 6; i++) {
            if (!owned.get(i).equals(mine[i])) {
                sameOrder = false;
            }
        }
        check("反向：这 6 条在参考服日志里的出现顺序 = loadLogLines() 的顺序", sameOrder);
        // `gs_boss_three 已加载` 在参考服日志里<b>只出现 1 次</b>
        int loadCount = 0;
        for (String r : ref) {
            if (r.contains("gs_boss_three")) {
                loadCount++;
            }
        }
        check("反向：参考服日志里含 gs_boss_three 的行恰好 1 条（实际 " + loadCount + " 条）",
                loadCount == 1);
        // 原脚本文件里 `已加载` 也只出现 1 次（照抄"脚本自己打"的事实）
        int jsLoad = 0;
        for (String l : jsSourceLines) {
            if (l.contains("已加载")) {
                jsLoad++;
            }
        }
        check("证据：gs_boss_three.js 里 `已加载` 恰好出现 1 次（实际 " + jsLoad + " 次）", jsLoad == 1);

        System.out.println();
    }

    /** 读脚本源码的所有行（[B] 组用来证明"脚本自己打了这一行"）。 */
    private static List<String> jsSourceLines = List.of();

    // ==================================================================
    // [C] 几何 / 数值扫描
    // ==================================================================

    /** 一个测试用例 → Java 侧的期望输出（每个用例 per 个 double；null/失败用 NaN 表示）。 */
    interface VecFn {
        double[] apply(double[] c);
    }

    private static void sweeps(Context ctx) throws Exception {
        System.out.println("[C] 几何 / 数值扫描（真 JS vs Java，逐值比对）");

        // ---------------- C1 btPassableAt（真实世界路径） ----------------
        {
            List<double[]> cases = new ArrayList<>();
            int[] modes = {0, 1, 2, 3, 4, 5, 6};
            int[] xs = {-3, -1, 0, 1, 2, 5, 11, 64, 137};
            int[] ys = {-71, -70, -60, -59, 0, 59, 60, 63, 64, 65, 66, 67, 68, 250, 259, 260};
            int[] zs = {-2, 0, 3, 8, 23, 91};
            for (int m : modes) {
                for (int x : xs) {
                    for (int z : zs) {
                        for (int y : ys) {
                            cases.add(new double[]{m, x, y, z});
                        }
                    }
                }
            }
            for (int m : modes) {
                final int mode = m;
                sweep(ctx, "[C1] btPassableAt（地形 " + mode + " · " + cases.size() + " 组）",
                        "runPassable", mode, cases, 1, c -> new double[]{
                                ThreeModule.btPassableAt(world(mode), (int) c[1], (int) c[2], (int) c[3])
                                        ? 1 : 0});
            }
        }

        // ---------------- C2 btSafeY（真实世界路径） ----------------
        {
            int[] modes = {0, 1, 2, 3, 4, 5, 6};
            double[] xs = {10.37, -4.61, 0.5, 63.99, -0.2};
            double[] zs = {20.63, 7.19, -12.5, 128.75};
            double[] y0s = {-100.0, -71.0, -70.0, -61.0, -60.0, -59.0, 55.0, 60.0, 62.5,
                    63.0, 64.0, 65.0, 66.0, 67.0, 70.0, 75.0, 245.0, 250.0, 251.0, 260.0};
            int[] hs = {1, 2, 3};
            int total = 0;
            for (int m : modes) {
                List<double[]> cases = new ArrayList<>();
                for (double x : xs) {
                    for (double z : zs) {
                        for (double y0 : y0s) {
                            for (int h : hs) {
                                cases.add(new double[]{x, z, y0, h});
                            }
                        }
                    }
                }
                total += cases.size();
                final int mode = m;
                sweep(ctx, "[C2] btSafeY（地形 " + mode + " · " + cases.size() + " 组）",
                        "runSafeY", mode, cases, 1,
                        c -> new double[]{ThreeModule.btSafeY(world(mode), c[0], c[1], c[2], (int) c[3])});
            }
            System.out.println("        （btSafeY 合计 " + total + " 组）");
        }

        // ---------------- C3 锁链落点 btDragSlot（真实世界路径） ----------------
        {
            int[] modes = {0, 1, 2, 3, 4};
            double[] yaws = {0, 15, 45, 90, 135, 180, 270, 359};
            int[] cnts = {1, 2, 3, 8};
            int[] tryNs = {0, 1, 2, 3, 4};
            double[][] bases = {{10.37, 20.63}, {0.5, 0.5}, {63.4, -12.6}};
            double[] sys = {64.0, 61.0};
            int total = 0;
            for (int m : modes) {
                List<double[]> cases = new ArrayList<>();
                for (double[] base : bases) {
                    for (double sy : sys) {
                        for (double yaw : yaws) {
                            for (int cnt : cnts) {
                                for (int i = 0; i < cnt; i++) {
                                    for (int t : tryNs) {
                                        cases.add(new double[]{base[0], base[1], sy, yaw, i, cnt, t});
                                    }
                                }
                            }
                        }
                    }
                }
                total += cases.size();
                final int mode = m;
                sweep(ctx, "[C3] 锁链落点 btDragSlot（地形 " + m + " · " + cases.size() + " 组）",
                        "runDragSlot", mode, cases, 3, c -> {
                            double[] r = ThreeModule.btDragSlot(ThreeModule.btBlockOf(world(mode)),
                                    c[0], c[1], c[2], c[3], (int) c[4], (int) c[5], (int) c[6]);
                            return r == null ? new double[]{Double.NaN, Double.NaN, Double.NaN} : r;
                        });
            }
            System.out.println("        （btDragSlot 合计 " + total + " 组）");
        }

        // ---------------- C4 飞斧撞墙 btAxeBlocked ----------------
        {
            int[] modes = {0, 2, 3, 4};
            double[] xs = {10.37, 0.5, 63.4};
            double[] zs = {20.63, -12.6};
            double[] ys = {63.2, 64.0, 64.85, 65.0, 66.4};
            double[] steps = {-0.62, 0.0, 0.31, 0.62};
            int total = 0;
            for (int m : modes) {
                List<double[]> cases = new ArrayList<>();
                for (double x : xs) {
                    for (double z : zs) {
                        for (double y : ys) {
                            for (double s : steps) {
                                cases.add(new double[]{x, y, z, x + s, z + s});
                            }
                        }
                    }
                }
                total += cases.size();
                final int mode = m;
                sweep(ctx, "[C4] 飞斧撞墙 btAxeBlocked（地形 " + m + " · " + cases.size() + " 组）",
                        "runAxeBlocked", mode, cases, 1, c -> new double[]{
                                ThreeModule.btAxeBlocked(ThreeModule.btBlockOf(world(mode)),
                                        c[0], c[1], c[2], c[3], c[4]) ? 1 : 0});
            }
            System.out.println("        （btAxeBlocked 合计 " + total + " 组）");
        }

        // ---------------- C5 飞斧朝向（边飞边转 +46°/格） ----------------
        {
            List<double[]> cases = new ArrayList<>();
            double[] ds = {-3.7, -1.0, -0.0001, 0.0, 0.0001, 1.0, 3.7};
            double[] tr = {0.0, 0.31, 0.62, 5.0, 9.92};
            for (double dx : ds) {
                for (double dz : ds) {
                    for (double t : tr) {
                        cases.add(new double[]{dx, dz, t});
                    }
                }
            }
            sweep(ctx, "[C5a] 飞斧盔甲架朝向 btAxeStandYaw（" + cases.size() + " 组）",
                    "runAxeYaw", cases, 1,
                    c -> new double[]{ThreeModule.btAxeStandYaw(c[0], c[1], c[2])});
            List<double[]> c2 = new ArrayList<>();
            for (double dx : ds) {
                for (double dz : ds) {
                    c2.add(new double[]{dx, dz});
                }
            }
            sweep(ctx, "[C5b] 飞斧出生朝向 btAxeSpawnYaw（" + c2.size() + " 组）",
                    "runAxeSpawnYaw", c2, 1,
                    c -> new double[]{ThreeModule.btAxeSpawnYaw(c[0], c[1])});
        }

        // ---------------- C6 追踪鳄鱼嘴每刻推进 + 命中判定 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            double[] ps = {-1.5, 0.0, 2.25, 8.5, 40.0};
            double[] ts = {-2.0, 0.0, 3.75, 12.0, 64.0};
            double[] ys = {63.0, 64.2, 66.0, 70.5};
            double[] sp = {0.42};
            for (double x : ps) {
                for (double z : ps) {
                    for (double y : ys) {
                        for (double tx : ts) {
                            for (double tz : ts) {
                                for (double ty : ys) {
                                    for (double s : sp) {
                                        cases.add(new double[]{x, y, z, tx, ty, tz, s});
                                    }
                                }
                            }
                        }
                    }
                }
            }
            sweep(ctx, "[C6a] 鳄鱼嘴推进 btFangStep（" + cases.size() + " 组 · 含 null 分支）",
                    "runFangStep", cases, 7, c -> {
                        double[] r = ThreeModule.btFangStep(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);
                        return r == null ? new double[]{Double.NaN, Double.NaN, Double.NaN,
                                Double.NaN, Double.NaN, Double.NaN, Double.NaN} : r;
                    });

            List<double[]> hc = new ArrayList<>();
            double[] hv = {-2.0, -1.6, -1.599, -1.2, -0.001, 0.0, 0.001, 1.2, 1.6, 1.601, 2.0};
            for (double dx : hv) {
                for (double dz : hv) {
                    for (double dy : hv) {
                        hc.add(new double[]{dx, dz, dy});
                    }
                }
            }
            sweep(ctx, "[C6b] 鳄鱼嘴命中 btFangHit（" + hc.size() + " 组 · 边界 ±1.2 / ±1.6）",
                    "runFangHit", hc, 1,
                    c -> new double[]{ThreeModule.btFangHit(c[0], c[1], c[2]) ? 1 : 0});
        }

        // ---------------- C7 竖斩竖直闸门 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            double[] ey = {60.0, 63.0, 64.0, 64.1, 64.5, 65.0, 66.0, 70.0};
            double[] sy = {64.0, 65.0, 65.9, 66.0, 66.1, 67.6, 68.0, 70.0};
            for (double a : ey) {
                for (double b : sy) {
                    cases.add(new double[]{a, b});
                }
            }
            sweep(ctx, "[C7] 竖斩竖直闸门 btSlashHeightOk（" + cases.size() + " 组 · 边界 ±1.6）",
                    "runSlashHeight", cases, 1,
                    c -> new double[]{ThreeModule.btSlashHeightOk(c[0], c[1]) ? 1 : 0});
        }

        // ---------------- C8 坠地：抬升 + 落地曲线 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            for (int i = 0; i <= 30; i++) {
                cases.add(new double[]{i});
            }
            sweep(ctx, "[C8a] 坠地抬升 btSlamRise（" + cases.size() + " 组 · i/20 浮点除法）",
                    "runSlamRise", cases, 1, c -> new double[]{ThreeModule.btSlamRise((int) c[0])});

            List<double[]> dc = new ArrayList<>();
            double[] sys = {70.0, 64.5, 100.25};
            double[] gys = {64.0, 70.0, 12.5};
            for (double a : sys) {
                for (double b : gys) {
                    for (int i = 0; i <= 12; i++) {
                        dc.add(new double[]{a, b, i});
                    }
                }
            }
            sweep(ctx, "[C8b] 坠地落地曲线 btSlamDropY（" + dc.size() + " 组 · t01 平方 + 钳 1）",
                    "runSlamDropY", dc, 1,
                    c -> new double[]{ThreeModule.btSlamDropY(c[0], c[1], (int) c[2])});
        }

        // ---------------- C9 瞬影 / 召唤点 / 指向 / 归一化 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            double[] pxs = {-100.5, 0.0, 10.37, 2048.0};
            double[] pzs = {-64.25, 0.0, 20.63};
            double[] yaws = {-180, -90, -0.5, 0, 33.75, 90, 180, 359.5};
            for (double x : pxs) {
                for (double z : pzs) {
                    for (double y : yaws) {
                        for (boolean behind : new boolean[]{true, false}) {
                            cases.add(new double[]{x, z, y, behind ? 1 : 0});
                        }
                    }
                }
            }
            sweep(ctx, "[C9a] 瞬影落点 btBlinkTarget（" + cases.size() + " 组 · 背后 = 视线取反）",
                    "runBlink", cases, 2, c -> ThreeModule.btBlinkTarget(
                            c[0], c[1], c[2], ThreeModule.BT_S1_BLINK_BACK, c[3] != 0));

            List<double[]> ac = new ArrayList<>();
            for (double x : pxs) {
                for (double z : pzs) {
                    for (double y : yaws) {
                        ac.add(new double[]{x, z, y});
                    }
                }
            }
            sweep(ctx, "[C9b] 召唤点 btSpawnAhead（" + ac.size() + " 组 · 身前 3 格）",
                    "runAhead", ac, 2, c -> ThreeModule.btSpawnAhead(
                            c[0], c[1], c[2], ThreeModule.BT_SPAWN_AHEAD));

            List<double[]> dcs = new ArrayList<>();
            double[] v = {-5.0, -0.00005, 0.0, 0.00005, 0.5, 5.0};
            for (double a : v) {
                for (double b : v) {
                    dcs.add(new double[]{0.0, 0.0, a, b, 45.0});
                }
            }
            for (double a : v) {
                for (double b : v) {
                    dcs.add(new double[]{10.37, 20.63, 10.37 + a, 20.63 + b, -123.5});
                }
            }
            sweep(ctx, "[C9c] 出手方向 btDirTo（" + dcs.size() + " 组 · 含 n<1e-4 兜底）",
                    "runDirTo", dcs, 2, c -> ThreeModule.btDirTo(c[0], c[1], c[2], c[3], c[4]));

            List<double[]> nc = new ArrayList<>();
            double[] nv = {-3.0, -0.199, -0.2, -0.19, 0.0, 0.19, 0.2, 3.0};
            for (double a : nv) {
                for (double b : nv) {
                    for (double d : nv) {
                        nc.add(new double[]{a, b, d, 0.2});
                        nc.add(new double[]{a, b, d, 1.0e-4});
                    }
                }
            }
            sweep(ctx, "[C9d] 三维归一化 btNorm3（" + nc.size() + " 组 · 含 n<min 返回 null）",
                    "runNorm3", nc, 3, c -> {
                        double[] r = ThreeModule.btNorm3(c[0], c[1], c[2], c[3]);
                        return r == null ? new double[]{Double.NaN, Double.NaN, Double.NaN} : r;
                    });
        }

        // ---------------- C10 小工具 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            double[] steps = {-1.0, 0.0, 0.31, 0.62, 1.6};
            double[] remains = {-0.5, 0.0, 0.31, 0.62, 1.6, 4.0, 10.0};
            for (double a : steps) {
                for (double b : remains) {
                    cases.add(new double[]{a, b});
                }
            }
            sweep(ctx, "[C10a] 步长钳制 btClampStep（" + cases.size() + " 组）",
                    "runClampStep", cases, 1, c -> new double[]{ThreeModule.btClampStep(c[0], c[1])});

            List<double[]> cc = new ArrayList<>();
            for (int i = 0; i <= 12; i++) {
                cc.add(new double[]{i});
            }
            sweep(ctx, "[C10b] 单次最多拉几个 btDragCount（" + cc.size() + " 组 · 上限 8）",
                    "runDragCount", cc, 1, c -> new double[]{ThreeModule.btDragCount((int) c[0])});

            List<double[]> mc = new ArrayList<>();
            for (int s = -1; s <= 5; s++) {
                mc.add(new double[]{s});
            }
            sweep(ctx, "[C10c] BGM 循环间隔 btMusicLoopMs（" + mc.size() + " 组）",
                    "runMusicLoopMs", mc, 1, c -> new double[]{ThreeModule.btMusicLoopMs((int) c[0])});

            List<double[]> bc = new ArrayList<>();
            double[] hps = {-5.0, 0.0, 1.0, 150.0, 300.0, 999.9};
            double[] mxs = {-1.0, 0.0, 1.0, 300.0, 1000.0};
            double[] rms = {0.0, 300.0, 800.0};
            for (double a : hps) {
                for (double b : mxs) {
                    for (double r : rms) {
                        bc.add(new double[]{a, b, r});
                    }
                }
            }
            sweep(ctx, "[C10d] 血条进度 btBarProgress（" + bc.size() + " 组 · 上限 0 时回落 ref.maxHp）",
                    "runBarProgress", bc, 1,
                    c -> new double[]{ThreeModule.btBarProgress(c[0], c[1], c[2])});
        }

        // ---------------- C11 技能触发门槛 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            for (double d = 0.0; d <= 80.0; d += 0.25) {
                cases.add(new double[]{d});
            }
            sweep(ctx, "[C11a] 射击门槛 d<=24（" + cases.size() + " 组）",
                    "runShootOk", cases, 1, c -> new double[]{ThreeModule.btShootRangeOk(c[0]) ? 1 : 0});
            sweep(ctx, "[C11b] 鳄鱼嘴门槛 d<=12+2（" + cases.size() + " 组）",
                    "runFangTrig", cases, 1, c -> new double[]{ThreeModule.btFangTriggerOk(c[0]) ? 1 : 0});
            sweep(ctx, "[C11c] 斩击门槛 d<=4+1（" + cases.size() + " 组）",
                    "runSlashTrig", cases, 1, c -> new double[]{ThreeModule.btSlashTriggerOk(c[0]) ? 1 : 0});
            sweep(ctx, "[C11d] 飞斧门槛 d<=13（" + cases.size() + " 组）",
                    "runAxeTrig", cases, 1, c -> new double[]{ThreeModule.btAxeTriggerOk(c[0]) ? 1 : 0});
            sweep(ctx, "[C11e] 索敌门槛 d<=32（" + cases.size() + " 组）",
                    "runSeekOk", cases, 1, c -> new double[]{ThreeModule.btSeekOk(c[0]) ? 1 : 0});
        }

        // ---------------- C12 冷却闸门 ----------------
        {
            List<double[]> cases = new ArrayList<>();
            long[] nows = {0L, 1000L, 4000L, 9999L, 10000L, 10001L, 18000L, 100000L};
            long[] lasts = {0L, -1L, 4000L, 10000L};
            long[] cds = {1500L, 4000L, 10000L, 11000L, 12000L, 18000L, 20000L};
            for (long n : nows) {
                for (long l : lasts) {
                    for (long c : cds) {
                        cases.add(new double[]{n, l, c});
                    }
                }
            }
            sweep(ctx, "[C12] 冷却闸门 btCdOk（" + cases.size() + " 组 · 含 出场延迟 10s/18s 与 坠击 11s）",
                    "runCdOk", cases, 1,
                    c -> new double[]{ThreeModule.btCdOk((long) c[0], (long) c[1], (long) c[2]) ? 1 : 0});
        }

        System.out.println();
    }

    /** 跑一批用例：JS 侧用 runner（读 __CASES），Java 侧用 fn，逐值比对。 */
    private static void sweep(Context ctx, String label, String runner,
                              List<double[]> cases, int per, VecFn fn) throws Exception {
        sweep(ctx, label, runner, -1, cases, per, fn);
    }

    /**
     * 同上，但先把**合成世界**注入 JS 的 {@code __W}
     * —— 这样 JS 与 Java 走的是<b>同一个假世界对象</b>，比的是同一件事。
     *
     * @param mode 地形编号；{@code -1} = 不注入世界
     */
    private static void sweep(Context ctx, String label, String runner, int mode,
                              List<double[]> cases, int per, VecFn fn) throws Exception {
        if (mode >= 0) {
            ctx.getBindings("js").putMember("__W", world(mode));
        }
        ctx.getBindings("js").putMember("__CASES", cases.toArray(new double[0][]));
        Value v = ctx.eval("js", runner + "()");
        long n = v.getArraySize();
        if (n != (long) cases.size() * per) {
            check(label + "（JS 返回长度 " + n + "，期望 " + ((long) cases.size() * per) + "）", false);
            return;
        }
        int bad = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < cases.size(); i++) {
            double[] want = fn.apply(cases.get(i));
            boolean ok = true;
            for (int k = 0; k < per; k++) {
                double js = v.getArrayElement(i * per + k).asDouble();
                double jv = (k < want.length ? want[k] : Double.NaN);
                if (!sameDouble(js, jv)) {
                    ok = false;
                }
            }
            if (!ok) {
                bad++;
                if (bad <= 3) {
                    detail.append("\n        case").append(java.util.Arrays.toString(cases.get(i)))
                            .append("  JS=").append(showVec(v, i * per, per))
                            .append("  Java=").append(java.util.Arrays.toString(want));
                }
            }
        }
        check(label + " ⇒ 不一致 " + bad + " 组", bad == 0);
        if (bad > 0) {
            System.out.println(detail);
        }
    }

    private static String showVec(Value v, int from, int per) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < per; k++) {
            if (k > 0) {
                sb.append(", ");
            }
            sb.append(v.getArrayElement(from + k).asDouble());
        }
        return sb.append(']').toString();
    }

    // ==================================================================
    // [D] 反向断言
    // ==================================================================

    private static void reverse(Path contentDir, Path classFile, String jsSource) throws Exception {
        System.out.println("[D] 反向断言（钉住不应该存在的东西）");

        // D1 已删除的 BT_DRAG_MIN（4 格内不拉）
        check("反向：ThreeModule <b>不得</b>存在 BT_DRAG_MIN 字段（该规则已按需求删除）",
                !hasField("BT_DRAG_MIN"));

        // D2 已删除的龙息弹 BT_S3_FIRE_*
        boolean fire = hasField("BT_S3_FIRE_DMG") || hasField("BT_S3_FIRE_CD")
                || hasField("BT_S3_FIRE_R") || hasField("BT_S3_FIRE_SPEED");
        check("反向：妄念魔的「龙息弹」常量 BT_S3_FIRE_* 一个都不许有", !fire);
        boolean fireFn = false;
        for (Method m : ThreeModule.class.getDeclaredMethods()) {
            String n = m.getName();
            if (n.equals("btS3Fire") || n.equals("btFireTick")) {
                fireFn = true;
            }
        }
        check("反向：btS3Fire / btFireTick 两个方法都不许有", !fireFn);

        // D3 锁链不设距离门槛（三个门槛全 0，且模块里没有 MIN 判定）
        check("反向：BT_DRAG_TRIG_S1/S2/S3 全为 0.0（不设距离门槛）",
                ThreeModule.BT_DRAG_TRIG_S1 == 0.0
                        && ThreeModule.BT_DRAG_TRIG_S2 == 0.0
                        && ThreeModule.BT_DRAG_TRIG_S3 == 0.0);

        // D4 掉落矩阵
        check("反向：一阶段不掉碎片、不掉残樱",
                ThreeModule.BT_FRAG_DROP[0] == 0 && ThreeModule.BT_CHERRY_DROP[0] == 0);
        check("反向：二阶段不掉碎片、只掉 1 把残樱",
                ThreeModule.BT_FRAG_DROP[1] == 0 && ThreeModule.BT_CHERRY_DROP[1] == 1);
        check("反向：三阶段只掉 6 个碎片、不掉残樱",
                ThreeModule.BT_FRAG_DROP[2] == 6 && ThreeModule.BT_CHERRY_DROP[2] == 0);

        // D5 没有覆写那 4 个"全局脚本"钩子（本脚本自己注册 Bukkit 监听器）
        check("反向：没有覆写 onPlayerJoin（本脚本不用这个钩子）",
                !hasDeclared("onPlayerJoin", org.bukkit.event.player.PlayerJoinEvent.class));
        check("反向：没有覆写 onPlayerQuit（下线不删 boss）",
                !hasDeclared("onPlayerQuit", org.bukkit.event.player.PlayerQuitEvent.class));
        check("反向：没有覆写 onPlayerDeath",
                !hasDeclared("onPlayerDeath", org.bukkit.event.entity.PlayerDeathEvent.class));
        check("反向：没有覆写 onEntityDamage（伤害走自己注册的 HIGHEST 监听器）",
                !hasDeclared("onEntityDamage", org.bukkit.event.entity.EntityDamageEvent.class));
        check("正向：覆写了 onUse",
                hasDeclared("onUse", io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent.class,
                        Object.class));

        // D6 源码纪律
        check("反向：源码里没有 System.out（红线 5）", !jsSource.isEmpty() && !srcContains("System.out"));
        check("反向：源码里没有 JavaAdapter（GraalJS 25.1.3 里不存在）", !srcContains("JavaAdapter"));

        // D7 name()
        String nm = null;
        try {
            nm = new ThreeModule().name();
        } catch (Throwable t) {
            nm = "构造抛异常: " + t;
        }
        check("name() = gs_boss_three（构造器在裸 JVM 里也不许抛）", "gs_boss_three".equals(nm));

        // D8 EntityCastSpellEvent 在 paper-api 1.21.11 不存在 ⇒ 原脚本那一块从未注册
        boolean ecse = true;
        try {
            Class.forName("org.bukkit.event.entity.EntityCastSpellEvent");
        } catch (Throwable t) {
            ecse = false;
        }
        check("证据：paper-api 里<b>没有</b> EntityCastSpellEvent（原脚本那一块被 catch 吞掉、从未生效）",
                !ecse);
        check("反向：ThreeModule 的字节码里<b>不得</b>引用 EntityCastSpellEvent",
                classFile == null || !classHasString(classFile, "EntityCastSpellEvent"));

        // D9 LootTables.EMPTY 不存在 ⇒ 原脚本那三句 setLootTable 从未生效
        boolean ltEmpty = true;
        try {
            org.bukkit.loot.LootTables.class.getField("EMPTY");
        } catch (Throwable t) {
            ltEmpty = false;
        }
        check("证据：paper-api 的 LootTables <b>没有</b> EMPTY 常量（原脚本那三句从未生效）", !ltEmpty);
        check("反向：ThreeModule 的字节码里<b>不得</b>引用 LootTables",
                classFile == null || !classHasString(classFile, "LootTables"));

        // D10 yml：残樱配方已删除
        Path mb = contentDir.resolve("mb_machines.yml");
        Path items = contentDir.resolve("items.yml");
        Path rts = contentDir.resolve("recipe_types.yml");
        if (Files.isRegularFile(mb)) {
            List<String> lines = Files.readAllLines(mb, StandardCharsets.UTF_8);
            int active = 0;
            for (String l : lines) {
                String t = l.trim();
                if (t.startsWith("#")) {
                    continue;
                }
                if (t.contains("GS_CHERRY")) {
                    active++;
                }
            }
            check("★ 残樱配方已删除：mb_machines.yml 里没有<b>非注释</b>的 GS_CHERRY（找到 " + active + " 处）",
                    active == 0);
        } else {
            check("找得到 " + mb, false);
        }
        if (Files.isRegularFile(items)) {
            List<String> lines = Files.readAllLines(items, StandardCharsets.UTF_8);
            List<String> block = topLevelBlock(lines, "GS_CHERRY:");
            check("items.yml 里找得到 GS_CHERRY 段（" + block.size() + " 行）", !block.isEmpty());
            boolean hasRt = false;
            boolean hasRecipe = false;
            for (String l : block) {
                String t = l.trim();
                if (t.startsWith("#")) {
                    continue;
                }
                if (t.startsWith("recipe_type:") && t.contains("GS_RT_WANGNIAN_S2")) {
                    hasRt = true;
                }
                if (t.startsWith("recipe:")) {
                    hasRecipe = true;
                }
            }
            check("★ items.yml 的 GS_CHERRY 挂了 recipe_type: GS_RT_WANGNIAN_S2", hasRt);
            check("★ items.yml 的 GS_CHERRY <b>没有</b> recipe: 九宫格（配方页留空）", !hasRecipe);
        } else {
            check("找得到 " + items, false);
        }
        if (Files.isRegularFile(rts)) {
            List<String> lines = Files.readAllLines(rts, StandardCharsets.UTF_8);
            boolean def = false;
            for (String l : lines) {
                if (l.startsWith("GS_RT_WANGNIAN_S2:")) {
                    def = true;
                }
            }
            check("★ recipe_types.yml 里定义了顶层 GS_RT_WANGNIAN_S2（提示型配方类型）", def);
        } else {
            check("找得到 " + rts, false);
        }

        // D11 原脚本确实定义了 btRegisterGuard（被 InteractGuard 取代，不是"漏了"）
        check("证据：原脚本有 btRegisterGuard（已改用公共 InteractGuard，行为等价）",
                jsSource.contains("function btRegisterGuard"));

        // D12 大扫除参数（这两处我一开始是靠结构推断的，现已逐行核对 ⇒ 用断言钉死）
        check("证据：原脚本 btPurgeVanilla 用 `ref.purgeN % 4 !== 1`（每秒一次）+ 扫描盒 28,16,28",
                jsSource.contains("ref.purgeN % 4 !== 1")
                        && jsSource.contains("getNearbyEntities(l, 28, 16, 28)"));
        check("反向：ThreeModule 用的是同一组参数（`% 4 != 1` + 28,16,28）",
                srcContains("ref.purgeN % 4 != 1") && srcContains("28, 16, 28"));
        check("证据：原脚本 btS2VexLoyal 的扫描盒 = 24,12,24",
                jsSource.contains("getNearbyEntities(ref.ent.getLocation(), 24, 12, 24)"));
        check("反向：ThreeModule 用的是同一组参数（24, 12, 24）",
                srcContains("24, 12, 24"));
        check("证据：原脚本 reload 清扫用记分板标签 'gs_axe' / 'gs_vex'",
                jsSource.contains("addScoreboardTag('gs_axe')")
                        && jsSource.contains("addScoreboardTag('gs_vex')"));
        check("反向：ThreeModule 用的也是 gs_axe / gs_vex（标签名一字未改）",
                srcContains("addScoreboardTag(\"gs_axe\")")
                        && srcContains("addScoreboardTag(\"gs_vex\")"));
        check("证据：原脚本 reload 清扫日志文本（已清理 N 个 reload 残留（妄想 / 飞斧））",
                jsSource.contains("'[构思技艺] 碎念执：已清理 ' + n")
                        && jsSource.contains("个 reload 残留（妄想 / 飞斧）"));
        check("反向：ThreeModule 里同一条日志文本逐字一致",
                srcContains("已清理 \" + n") && srcContains("个 reload 残留（妄想 / 飞斧）"));
        check("证据：原脚本 ref.purgeN 的初值语义（`(ref.purgeN == null ? 0 : ref.purgeN) + 1`）",
                jsSource.contains("ref.purgeN = (ref.purgeN == null ? 0 : ref.purgeN) + 1;"));

        System.out.println();
    }

    /** 取 YAML 里一个顶层键的段落（从该键那行到下一个顶层键/注释块前）。 */
    private static List<String> topLevelBlock(List<String> lines, String key) {
        List<String> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key)) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return out;
        }
        for (int i = start; i < lines.size(); i++) {
            String l = lines.get(i);
            if (i > start && !l.isEmpty() && !l.startsWith(" ") && !l.startsWith("#")) {
                break;
            }
            out.add(l);
        }
        return out;
    }

    private static boolean hasField(String name) {
        try {
            ThreeModule.class.getDeclaredField(name);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }

    private static boolean hasDeclared(String name, Class<?>... params) {
        try {
            ThreeModule.class.getDeclaredMethod(name, params);
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /** 模块源码里有没有某个片段（读的是源文件本身，用于"纪律"类反向断言）。 */
    private static boolean srcContains(String needle) {
        try {
            Path p = Path.of("src/main/java/com/qinghuacisj/gs/script/module/ThreeModule.java");
            if (!Files.isRegularFile(p)) {
                return false;
            }
            String s = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            return s.contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean classHasString(Path classFile, String needle) {
        try {
            if (!Files.isRegularFile(classFile)) {
                return false;
            }
            String s = new String(Files.readAllBytes(classFile), StandardCharsets.ISO_8859_1);
            return s.contains(needle);
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================================================================
    // 合成世界（JS 与 Java 走同一个对象）
    // ==================================================================

    /** 地形：0=空气 1=石头 2=玻璃。 */
    private static int terrain(int x, int y, int z, int mode) {
        switch (mode) {
            case 0: {                       // 丘陵：地面在 60~68，地表偶尔是玻璃
                int h = 60 + Math.floorMod(x * 7 + z * 13, 9);
                if (y < h) {
                    return 1;
                }
                if (y == h) {
                    return (Math.floorMod(x * 31 + z * 17, 7) == 0) ? 2 : 1;
                }
                return 0;
            }
            case 1:
                return 0;                   // 全空气
            case 2:
                return 1;                   // 全实心
            case 3:                         // 平地 + 玻璃地板
                if (y < 64) {
                    return 1;
                }
                return (y == 64) ? 2 : 0;
            case 4:                         // 平地 + 一层天花板（65 空气 / 66 石头）
                if (y <= 64) {
                    return 1;
                }
                return (y == 66) ? 1 : 0;
            case 5:                         // 低处地板（测 -60 钳制）
                return (y == -70) ? 1 : 0;
            case 6:                         // 高处地板（测 250 钳制）
                return (y == 260) ? 1 : 0;
            default:
                return 0;
        }
    }

    private static Material material(int t) {
        if (t == 0) {
            return Material.AIR;
        }
        return (t == 2) ? Material.GLASS : Material.STONE;
    }

    private static final java.util.Map<Integer, World> WORLDS = new java.util.HashMap<>();

    private static World world(int mode) {
        return WORLDS.computeIfAbsent(mode, m -> (World) Proxy.newProxyInstance(
                ThreeParityProbe.class.getClassLoader(),
                new Class<?>[]{World.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "getBlockAt": {
                            Location loc = (Location) args[0];
                            return block(m, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                        }
                        case "getName":
                            return "probe_world_" + m;
                        case "getEntities":
                        case "getNearbyEntities":
                        case "getPlayers":
                        case "getOnlinePlayers":
                            return new ArrayList<>();
                        case "toString":
                            return "ProbeWorld(" + m + ")";
                        case "hashCode":
                            return m;
                        case "equals":
                            return p == args[0];
                        default:
                            return def(method.getReturnType());
                    }
                }));
    }

    private static Block block(int mode, int bx, int by, int bz) {
        return (Block) Proxy.newProxyInstance(
                ThreeParityProbe.class.getClassLoader(),
                new Class<?>[]{Block.class},
                (p, method, args) -> {
                    switch (method.getName()) {
                        case "getType":
                            return material(terrain(bx, by, bz, mode));
                        case "isPassable":
                            return terrain(bx, by, bz, mode) == 0;
                        case "isAir":
                            return terrain(bx, by, bz, mode) == 0;
                        case "isSolid":
                            return terrain(bx, by, bz, mode) != 0;
                        case "toString":
                            return "ProbeBlock(" + bx + "," + by + "," + bz + ")";
                        case "hashCode":
                            return bx * 31 + by * 7 + bz;
                        case "equals":
                            return p == args[0];
                        default:
                            return def(method.getReturnType());
                    }
                });
    }

    private static Object def(Class<?> c) {
        if (!c.isPrimitive()) {
            return null;
        }
        if (c == boolean.class) {
            return false;
        }
        if (c == void.class) {
            return null;
        }
        if (c == long.class) {
            return 0L;
        }
        if (c == double.class) {
            return 0.0d;
        }
        if (c == float.class) {
            return 0.0f;
        }
        if (c == int.class) {
            return 0;
        }
        if (c == short.class) {
            return (short) 0;
        }
        if (c == byte.class) {
            return (byte) 0;
        }
        if (c == char.class) {
            return (char) 0;
        }
        return null;
    }

    // ==================================================================
    // JS 侧算式 —— ★ 逐字符从 gs_boss_three.js 摘出
    //   原脚本里有些算式是**内联**在长函数里的，这里只把**纯算术部分**
    //   原样搬出来（运算符、常量名、钳制顺序一字未改），并把脚本自己的
    //   btPassableAt / btSafeY 直接拿来用（所以假世界对两边是同一个）。
    // ==================================================================

    private static final String MATH_JS =
            "function m_axeYaw(dx, dz, traveled) {\n"
                    + "  return Math.atan2(-dx, dz) * 180 / Math.PI + traveled * 46;\n"
                    + "}\n"
                    + "function m_axeSpawnYaw(dx, dz) {\n"
                    + "  return Math.atan2(-dx, dz) * 180 / Math.PI;\n"
                    + "}\n"
                    + "function m_slamRise(i) {\n"
                    + "  return BT_S3_SLAM_RISE * (i / BT_S3_SLAM_CHARGE);\n"
                    + "}\n"
                    + "function m_slamDropY(sy, gy, i) {\n"
                    + "  var t01 = i / BT_S3_SLAM_DROP;\n"
                    + "  if (t01 > 1) t01 = 1;\n"
                    + "  return sy + (gy - sy) * t01 * t01;\n"
                    + "}\n"
                    + "function m_blink(px, pz, yaw, back, behind) {\n"
                    + "  var rad = yaw * Math.PI / 180;\n"
                    + "  var fx = -Math.sin(rad), fz = Math.cos(rad);\n"
                    + "  if (behind) { fx = -fx; fz = -fz; }\n"
                    + "  return [px + fx * back, pz + fz * back];\n"
                    + "}\n"
                    + "function m_ahead(x, z, yaw, ahead) {\n"
                    + "  var rad = yaw * Math.PI / 180;\n"
                    + "  return [x + (-Math.sin(rad)) * ahead, z + (Math.cos(rad)) * ahead];\n"
                    + "}\n"
                    + "function m_dirTo(fx, fz, tx, tz, yawFallback) {\n"
                    + "  var dx = tx - fx, dz = tz - fz;\n"
                    + "  var n = Math.sqrt(dx * dx + dz * dz);\n"
                    + "  if (n < 1.0e-4) {\n"
                    + "    var rad = yawFallback * Math.PI / 180;\n"
                    + "    return [-Math.sin(rad), Math.cos(rad)];\n"
                    + "  }\n"
                    + "  return [dx / n, dz / n];\n"
                    + "}\n"
                    + "function m_norm3(dx, dy, dz, min) {\n"
                    + "  var n = Math.sqrt(dx * dx + dy * dy + dz * dz);\n"
                    + "  if (!(n >= min)) return null;\n"
                    + "  return [dx / n, dy / n, dz / n];\n"
                    + "}\n"
                    + "function m_fangStep(x, y, z, tx, ty, tz, speed) {\n"
                    + "  var dx = tx - x, dz = tz - z, dy = (ty + 0.6) - y;\n"
                    + "  var n = Math.sqrt(dx * dx + dz * dz);\n"
                    + "  if (n < 1.0e-4) return null;\n"
                    + "  var step = speed;\n"
                    + "  if (step > n) step = n;\n"
                    + "  var nx = x + dx / n * step;\n"
                    + "  var nz = z + dz / n * step;\n"
                    + "  var ny = y + Math.max(-0.12, Math.min(0.12, dy / n * step));\n"
                    + "  return [nx, ny, nz, step, dx, dz, dy];\n"
                    + "}\n"
                    + "function m_fangHit(dx, dz, dy) {\n"
                    + "  return (Math.sqrt(dx * dx + dz * dz) <= BT_S1_FANG_R && Math.abs(dy) <= 1.6) ? 1 : 0;\n"
                    + "}\n"
                    + "function m_slashHeight(entY, slashY) {\n"
                    + "  return (Math.abs(entY + 0.9 - slashY) <= BT_S2_SLASH_H) ? 1 : 0;\n"
                    + "}\n"
                    + "function m_clampStep(step, remain) { return step > remain ? remain : step; }\n"
                    + "function m_dragCount(n) { return n > BT_DRAG_CAP ? BT_DRAG_CAP : n; }\n"
                    + "function m_musicLoopMs(stage) {\n"
                    + "  var i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;\n"
                    + "  var v = BT_MUSIC_LOOP_MS[i];\n"
                    + "  return v <= 0 ? 299000 : v;\n"
                    + "}\n"
                    + "function m_barProgress(hp, mx, refMax) {\n"
                    + "  var m = mx;\n"
                    + "  if (m <= 0) m = refMax;\n"
                    + "  return hp / (m > 0 ? m : 1);\n"
                    + "}\n"
                    + "function m_shootOk(d) { return (d <= BT_S1_ATK_RANGE) ? 1 : 0; }\n"
                    + "function m_fangTrig(d) { return (d <= BT_S1_FANG_RANGE + 2.0) ? 1 : 0; }\n"
                    + "function m_slashTrig(d) { return (d <= BT_S2_SLASH_RANGE + 1.0) ? 1 : 0; }\n"
                    + "function m_axeTrig(d) { return (d <= BT_S2_AXE_TRIGGER) ? 1 : 0; }\n"
                    + "function m_seekOk(d) { return (d <= BT_SEEK) ? 1 : 0; }\n"
                    + "function m_cdOk(now, last, cd) { return ((now - last) >= cd) ? 1 : 0; }\n"
                    + "function m_dragSlot(world, bx, bz, sy, yaw0, i, cnt, tryN) {\n"
                    + "  var ang = (yaw0 * Math.PI / 180) + (Math.PI * 2 * i / cnt);\n"
                    + "  var fx = Math.cos(ang), fz = Math.sin(ang);\n"
                    + "  var rr = BT_DRAG_RING;\n"
                    + "  var spread = (tryN === 0 ? 0 : (tryN % 2 === 0 ? 1 : -1) * Math.ceil(tryN / 2) * 0.8);\n"
                    + "  var ax = bx + fx * rr, az = bz + fz * rr;\n"
                    + "  ax += -fz * spread;\n"
                    + "  az += fx * spread;\n"
                    + "  if (!btPassableAt(world, Math.floor(ax), Math.floor(sy), Math.floor(az))) return null;\n"
                    + "  var ay = btSafeY(world, ax, az, sy, 2);\n"
                    + "  return [ax, az, ay];\n"
                    + "}\n"
                    + "function m_axeBlocked(world, x, y, z, nx, nz) {\n"
                    + "  if (!btPassableAt(world, Math.floor(x), Math.floor(y), Math.floor(z))) return 1;\n"
                    + "  if (!btPassableAt(world, Math.floor(x), Math.floor(y + 0.4), Math.floor(z))) return 1;\n"
                    + "  if (!btPassableAt(world, Math.floor(nx), Math.floor(y), Math.floor(nz))) return 1;\n"
                    + "  if (!btPassableAt(world, Math.floor(nx), Math.floor(y + 0.4), Math.floor(nz))) return 1;\n"
                    + "  return 0;\n"
                    + "}\n"
                    // ---------------- runners ----------------
                    + "function runPassable() {\n"
                    + "  var o = [], cs = __CASES, W = __W;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(btPassableAt(W, c[1], c[2], c[3]) ? 1 : 0); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSafeY() {\n"
                    + "  var o = [], cs = __CASES, W = __W;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(btSafeY(W, c[0], c[1], c[2], c[3])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runDragSlot() {\n"
                    + "  var o = [], cs = __CASES, W = __W;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_dragSlot(W, c[0], c[1], c[2], c[3], c[4], c[5], c[6]);\n"
                    + "    if (r === null) { o.push(NaN, NaN, NaN); } else { o.push(r[0], r[1], r[2]); } }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runAxeBlocked() {\n"
                    + "  var o = [], cs = __CASES, W = __W;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_axeBlocked(W, c[0], c[1], c[2], c[3], c[4])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runAxeYaw() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_axeYaw(c[0], c[1], c[2])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runAxeSpawnYaw() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_axeSpawnYaw(c[0], c[1])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runFangStep() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_fangStep(c[0], c[1], c[2], c[3], c[4], c[5], c[6]);\n"
                    + "    if (r === null) { o.push(NaN, NaN, NaN, NaN, NaN, NaN, NaN); }\n"
                    + "    else { o.push(r[0], r[1], r[2], r[3], r[4], r[5], r[6]); } }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runFangHit() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_fangHit(c[0], c[1], c[2])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSlashHeight() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_slashHeight(c[0], c[1])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSlamRise() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_slamRise(c[0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSlamDropY() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_slamDropY(c[0], c[1], c[2])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runBlink() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_blink(c[0], c[1], c[2], BT_S1_BLINK_BACK, c[3] !== 0);\n"
                    + "    o.push(r[0], r[1]); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runAhead() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_ahead(c[0], c[1], c[2], BT_SPAWN_AHEAD);\n"
                    + "    o.push(r[0], r[1]); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runDirTo() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_dirTo(c[0], c[1], c[2], c[3], c[4]);\n"
                    + "    o.push(r[0], r[1]); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runNorm3() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    var r = m_norm3(c[0], c[1], c[2], c[3]);\n"
                    + "    if (r === null) { o.push(NaN, NaN, NaN); } else { o.push(r[0], r[1], r[2]); } }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runClampStep() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_clampStep(c[0], c[1])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runDragCount() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) {\n"
                    + "    // ★ 直接调用脚本里真的 btDragCount（它收的是锁表对象，不是数字）\n"
                    + "    var hit = {};\n"
                    + "    for (var k = 0; k < cs[i][0]; k++) { hit['u' + k] = 1; }\n"
                    + "    o.push(btDragCount({ hit: hit })); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runMusicLoopMs() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_musicLoopMs(c[0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runBarProgress() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_barProgress(c[0], c[1], c[2])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runShootOk() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { o.push(m_shootOk(cs[i][0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runFangTrig() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { o.push(m_fangTrig(cs[i][0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSlashTrig() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { o.push(m_slashTrig(cs[i][0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runAxeTrig() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { o.push(m_axeTrig(cs[i][0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runSeekOk() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { o.push(m_seekOk(cs[i][0])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    + "function runCdOk() {\n"
                    + "  var o = [], cs = __CASES;\n"
                    + "  for (var i = 0; i < cs.length; i++) { var c = cs[i];\n"
                    + "    o.push(m_cdOk(c[0], c[1], c[2])); }\n"
                    + "  return o;\n"
                    + "}\n"
                    // 假世界在 [C] 之前注入（这里放一个占位说明，真正的对象由 Java 侧 putMember("__W") 注入）
                    + "";

    // ==================================================================
    // 断言工具
    // ==================================================================

    /** 数值比较：允许 1e-9 的绝对/相对容差（cos/sin 的 1 ULP 伪差异）。 */
    private static boolean sameDouble(double a, double b) {
        if (Double.isNaN(a) && Double.isNaN(b)) {
            return true;
        }
        if (Double.isNaN(a) || Double.isNaN(b)) {
            return false;
        }
        double diff = Math.abs(a - b);
        return diff <= 1.0e-9 + 1.0e-9 * Math.abs(b);
    }

    private static Object toJava(Value v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isBoolean()) {
            return v.asBoolean();
        }
        if (v.isString()) {
            return v.asString();
        }
        if (v.hasArrayElements()) {
            int n = (int) v.getArraySize();
            boolean allNum = true;
            for (int i = 0; i < n; i++) {
                if (!v.getArrayElement(i).isNumber()) {
                    allNum = false;
                    break;
                }
            }
            if (allNum) {
                double[] out = new double[n];
                for (int i = 0; i < n; i++) {
                    out[i] = v.getArrayElement(i).asDouble();
                }
                return out;
            }
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                out[i] = v.getArrayElement(i).asString();
            }
            return out;
        }
        if (v.fitsInLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        return v.toString();
    }

    /** 按值比较，允许「整数 ↔ 浮点」的表示差异，但<b>不允许精度损失</b>。 */
    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof String[] sa && b instanceof String[] sb) {
            if (sa.length != sb.length) {
                return false;
            }
            for (int i = 0; i < sa.length; i++) {
                if (!sa[i].equals(sb[i])) {
                    return false;
                }
            }
            return true;
        }
        // ★ 数值数组：JS 侧统一给出 double[]，Java 侧可能是 long[] / int[] ——
        //   这是**表示差异**（对应契约 §7.1 的 int↔long↔double 允许项），
        //   归一化成 double[] 后逐值比，仍然**不允许精度损失**。
        double[] da = asDoubleArray(a);
        double[] db = asDoubleArray(b);
        if (da != null && db != null) {
            if (da.length != db.length) {
                return false;
            }
            for (int i = 0; i < da.length; i++) {
                if (!sameNumber(da[i], db[i])) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return sameNumber(na.doubleValue(), nb.doubleValue());
        }
        if (a instanceof Boolean ba && b instanceof Boolean bb) {
            return ba.equals(bb);
        }
        return a.equals(b);
    }

    /**
     * 把任意数值数组归一成 {@code double[]}；不是数值数组则返回 {@code null}。
     * ★ 只做"最宽表示"，不做任何四舍五入或截断。
     */
    private static double[] asDoubleArray(Object o) {
        if (o instanceof double[] d) {
            return d;
        }
        if (o instanceof long[] l) {
            double[] r = new double[l.length];
            for (int i = 0; i < l.length; i++) {
                r[i] = l[i];
            }
            return r;
        }
        if (o instanceof int[] n) {
            double[] r = new double[n.length];
            for (int i = 0; i < n.length; i++) {
                r[i] = n[i];
            }
            return r;
        }
        if (o instanceof float[] f) {
            double[] r = new double[f.length];
            for (int i = 0; i < f.length; i++) {
                r[i] = f[i];
            }
            return r;
        }
        if (o instanceof short[] s) {
            double[] r = new double[s.length];
            for (int i = 0; i < s.length; i++) {
                r[i] = s[i];
            }
            return r;
        }
        return null;
    }

    /** 数值必须<b>逐位等价</b>（不做 1e-9 容差）—— 常量对账抓的就是"抄错一位"。
     * 只放行 {@code 300} (int) 与 {@code 300.0} (double) 这种<b>无损</b>的表示差异。
     */
    private static boolean sameNumber(double x, double y) {
        if (Double.isNaN(x) && Double.isNaN(y)) {
            return true;
        }
        return x == y;
    }

    private static String show(Object o) {
        if (o == null) {
            return "null";
        }
        double[] d = asDoubleArray(o);
        if (d != null) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < d.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(trim(d[i]));
            }
            return sb.append(']').toString();
        }
        if (o instanceof String[] s) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < s.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append('"').append(s[i]).append('"');
            }
            return sb.append(']').toString();
        }
        if (o instanceof Number n) {
            return trim(n.doubleValue());
        }
        return String.valueOf(o);
    }

    private static String trim(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(java.util.Locale.ROOT, "%.12g", v);
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    /** 逐字节十六进制 + 原样，方便定位"差在哪一个字符"。 */
    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(s).append('"');
        sb.append("  [");
        for (int i = 0; i < s.length(); i++) {
            if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format("%04X", (int) s.charAt(i)));
        }
        return sb.append(']').toString();
    }

    private static int commonPrefix(String a, String b) {
        int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("      OK   " + label);
        } else {
            fail++;
            System.out.println("      失败 " + label);
        }
    }

    /** 给 [C] 组注入假世界（每个地形一个）。 */
    static {
        // 由 sweep() 里的 world(mode) 惰性创建；这里只是显式声明依赖，不做副作用。
        for (int m = 0; m <= 6; m++) {
            WORLDS.put(m, world(m));
        }
    }
}
