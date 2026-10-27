package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code gs_boss_phantom} 的对账探针。
 *
 * <p>把<b>真 JS</b>（GraalJS 直接跑 {@code gs_boss_phantom.js}）与 Java 模块
 * {@link PhantomModule} 在这四块上逐值/逐字节对账：
 * <ol>
 *   <li><b>[A] 常量</b>：JS 的 73 个顶层 {@code var PH_*} 与 Java 侧字段<b>按名按值</b>比对，
 *       并做<b>反向</b>断言 —— 每个 JS 常量都必须有 Java 对应物（不许静默漏抄），
 *       每个 JS 状态变量都必须有明确去向（移植的 / 判死的，不许"顺手补一个"）。</li>
 *   <li><b>[B] 加载日志行</b>：拿<b>参考服日志</b>里那 4 行真身逐字节比
 *       （含 {@code × · ⇒ ｜ （） ①②③④} 与 {@code \uFFFD} 这些字符），
 *       并反向钉住「本模块的加载日志集合<b>恰好</b>等于那 4 行」。</li>
 *   <li><b>[C] 数字格式化</b>：{@code toFixed} / {@code jsNum} 与真 JS 的
 *       {@code Number#toFixed} / {@code String()} 逐值比 ——
 *       这是日志行能否逐字节相同的<b>最高风险点</b>
 *       （{@code Double.toString(1000.0)} 是 {@code "1000.0"}，而 JS 是 {@code "1000"}）。</li>
 *   <li><b>[D][E] 算式</b>：缩放换算 / 三叉戟弹道补偿 / 弹体瞄准 / 凋零头转向 /
 *       冲刺命中判定 / 烟花音效节拍 / 「1 枚瞄准 + 2 枚随机落点」的落点分配，
 *       全部与真 JS 逐值比（随机落点靠<b>两侧注入同一条确定性随机序列</b>来对账）。</li>
 * </ol>
 *
 * <p>★ 精度口径（诚实说明，不是"放水"）：
 * <ul>
 *   <li>只含 {@code + - * / sqrt abs min max floor ceil} 的算式 —— 这些运算
 *       IEEE-754 与 ECMA-262 都要求<b>正确舍入</b>，所以按<b>位精确</b>（{@code ==}）比。</li>
 *   <li>含 {@code cos / sin / atan2} 的算式 —— 规范<b>不要求</b>正确舍入，
 *       GraalJS 的 fdlibm 移植与 JDK 可能差 1 ulp。这不是移植差异，
 *       所以按 {@code 1e-9} 绝对容差比（真抄错一个系数会差出好几个数量级）。</li>
 * </ul>
 */
public final class PhantomParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    private static final String SCRIPT =
            "D:/qq下载/粘液科技自用/QHCGS/GS/GS/scripts/gs_boss_phantom.js";

    /** 参考服日志 —— 里面那 4 行是**真 JS** 在真服上打出来的。 */
    private static final String REF_LOG =
            "docs/参考服日志_构思技艺.txt";

    private static final String MODULE_SRC =
            "src/main/java/com/qinghuacisj/gs/script/module/PhantomModule.java";

    // ==================================================================
    // [A] 常量对账的名单
    // ==================================================================

    /**
     * JS 常量块（{@code gs_boss_phantom.js:1~58} 的 {@code ^var PH_}）+ 4 个标记值常量。
     *
     * <p>★ 这份名单是<b>手抄自脚本</b>的，用来做<b>反向断言</b>：
     * 反射只能发现「Java 有、JS 也有但对不上」的字段，
     * 发现不了「JS 有、Java 直接漏了」—— 名单能。
     */
    private static final String[] CONST_NAMES = {
            "PH_ITEM_ID", "PH_CONSUME", "PH_SPAWN_AHEAD", "PH_SPAWN_UP", "PH_KEYNAME",
            "PH_DROP_XP", "PH_NAME", "PH_HP", "PH_SIZE", "PH_BOX_W", "PH_SCALE",
            "PH_TICK", "PH_FIRST_MS", "PH_SEEK", "PH_HP_FIX_MS", "PH_CREATIVE_FALLBACK",
            "PH_UNSTICK_MS", "PH_UNSTICK_GAP",
            "PH_SB_CD", "PH_SB_CHARGE", "PH_SB_DMG", "PH_SB_RANGE", "PH_SB_HIT_R",
            "PH_SB_KB", "PH_SB_KB_UP",
            "PH_DF_CD", "PH_DF_N", "PH_DF_RAND", "PH_DF_SPREAD", "PH_DF_SPEED",
            "PH_WS_CD", "PH_WS_N", "PH_WS_RAND", "PH_WS_SPREAD", "PH_WS_SPEED",
            "PH_WS_TURN", "PH_WS_GUIDE_T", "PH_WS_LIFE",
            "PH_TR_CD", "PH_TR_SPEED", "PH_TR_DMG", "PH_TR_LIFE", "PH_TR_GRAV",
            "PH_TR_LEAD", "PH_TR_LEAD_CAP", "PH_PET_MAX", "PH_PET_LIFE",
            "PH_DA_CD", "PH_DA_CHARGE", "PH_DA_FW_TIMES", "PH_DA_SPEED", "PH_DA_DMG",
            "PH_DA_HIT_R", "PH_DA_HIT_V", "PH_DA_MAX",
            "PH_BAR", "PH_BAR_ONLY_OWNER", "PH_BAR_RANGE",
            "PH_V_BOSS", "PH_V_PET", "PH_V_SKULL", "PH_V_TRIDENT",
    };

    /**
     * JS 的<b>运行期状态</b>顶层 {@code var}（不是常量）⇒ Java 侧的对应物，
     * {@code null} = <b>判死</b>（照契约不许移植，也不许"顺手补回来"）。
     */
    private static final String[][] STATE_MAP = {
            {"PH_ACTIVE", "active"},
            {"PH_LAST", "last"},
            {"PH_PETS", "pets"},
            {"PH_KEY", "key"},
            {"PH_PDT", null},          // PersistentDataType.STRING 直接用常量，无需变量
            {"PH_MAXATTR", null},      // 属性缓存的唯一用途（按名字找属性）在 1.21.11 不可达
            {"PH_HP_DIAG", "hpDiag"},
            {"PH_REG_DMG", "EVENTS_REGISTERED"},
            {"PH_REG_EXP", null},      // ★ 死代码：只声明，从不读也从不写
            {"PH_REG_DEATH", null},    // ★ 死代码：同上
            {"PH_REG_GUARD", null},    // → InteractGuard 内部按物品 id 去重
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_boss_phantom：常量 + 加载日志行 + 数字格式化 + 算式 对账 ===");
        System.out.println();

        constants();
        loadLines();
        numberFormat();
        formulas();
        spreadPoints();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ==================================================================
    // [A] 常量对账
    // ==================================================================

    private static void constants() throws Exception {
        System.out.println("[A] 常量对账：真 JS 顶层 var vs PhantomModule 字段");

        Path js = Paths.get(SCRIPT);
        if (!Files.isRegularFile(js)) {
            check("脚本存在（" + SCRIPT + "）", false);
            System.out.println();
            return;
        }
        String jsSrc = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);

        // ---- A0：先看 JS 里到底声明了哪些顶层 var（机械提取，不靠人眼） ----
        Set<String> jsVars = new LinkedHashSet<>();
        Matcher m = Pattern.compile("(?m)^var\\s+(PH_[A-Z0-9_]+)\\s*=").matcher(jsSrc);
        while (m.find()) {
            jsVars.add(m.group(1));
        }
        System.out.println("      JS 顶层 PH_* 变量共 " + jsVars.size() + " 个");

        Set<String> expected = new LinkedHashSet<>(Arrays.asList(CONST_NAMES));
        for (String[] e : STATE_MAP) {
            expected.add(e[0]);
        }
        // 反向：名单与脚本必须完全一致（多一个/少一个都要报）
        Set<String> missingInScript = new TreeSet<>(expected);
        missingInScript.removeAll(jsVars);
        Set<String> notInList = new TreeSet<>(jsVars);
        notInList.removeAll(expected);
        check("JS 里的每个顶层 PH_* 都在对账名单里（多出来的："
                + (notInList.isEmpty() ? "无" : notInList.toString()) + "）", notInList.isEmpty());
        check("对账名单里的每个名字在 JS 里都存在（少掉的："
                + (missingInScript.isEmpty() ? "无" : missingInScript.toString()) + "）",
                missingInScript.isEmpty());

        // ---- A1：真 JS 跑起来，读常量真值 ----
        Map<String, Object> jsVals = new LinkedHashMap<>();
        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.eval(Source.newBuilder("js", jsSrc, "gs_boss_phantom.js").build());
            Value b = ctx.getBindings("js");
            for (String n : CONST_NAMES) {
                if (!b.hasMember(n)) {
                    check("JS 顶层存在 " + n, false);
                    continue;
                }
                jsVals.put(n, toJava(b.getMember(n)));
            }
        } catch (Throwable t) {
            check("真 JS 求值成功（" + t + "）", false);
            System.out.println();
            return;
        }

        // ---- A2：逐个按名按值比对 ----
        int compared = 0;
        for (String n : CONST_NAMES) {
            if (!jsVals.containsKey(n)) {
                continue;
            }
            Field f;
            try {
                f = PhantomModule.class.getDeclaredField(n);
            } catch (NoSuchFieldException e) {
                check("Java 侧有字段 " + n + "（★ 漏抄了）", false);
                continue;
            }
            f.setAccessible(true);
            Object jv;
            try {
                jv = f.get(null);
            } catch (Throwable t) {
                check(n + " 是静态字段", false);
                continue;
            }
            compared++;
            Object sv = jsVals.get(n);
            boolean ok = sameValue(sv, jv);
            if (!ok) {
                System.out.println("      不一致 " + n
                        + "  JS=" + show(sv) + " (" + typeOf(sv) + ")"
                        + "  Java=" + show(jv) + " (" + typeOf(jv) + ")");
            }
            check(n + " = " + show(jv), ok);
        }
        check("常量比对数量 = " + compared + "（名单 " + CONST_NAMES.length + " 个）",
                compared == CONST_NAMES.length);

        // ---- A3：状态变量的去向（反向） ----
        for (String[] e : STATE_MAP) {
            String jsName = e[0];
            String javaName = e[1];
            if (javaName == null) {
                boolean absent = true;
                for (Field f : allFields(PhantomModule.class)) {
                    if (f.getName().equals(jsName)) {
                        absent = false;
                        break;
                    }
                }
                check("★ " + jsName + " 判死（Java 侧确实没有这个字段）", absent);
            } else {
                boolean found = false;
                for (Field f : allFields(PhantomModule.class)) {
                    if (f.getName().equals(javaName)) {
                        found = true;
                        break;
                    }
                }
                check(jsName + " → Java 字段 " + javaName, found);
            }
        }

        // ---- A4：第六十三批删掉的常量必须两边都没有 ----
        check("★ PH_PET_GRIEF 在 JS 里确实不存在（第六十三批删除）",
                !jsVars.contains("PH_PET_GRIEF"));
        check("★ PH_PET_GRIEF 在 Java 里也不存在", !hasField(PhantomModule.class, "PH_PET_GRIEF"));

        System.out.println();
    }

    // ==================================================================
    // [B] 加载日志行
    // ==================================================================

    private static void loadLines() throws Exception {
        System.out.println("[B] 加载日志行必须与参考服日志逐字节一致");

        // ---- B0：脚本第 86 行那一句，逐字节取出字面量 ----
        Path js = Paths.get(SCRIPT);
        if (!Files.isRegularFile(js)) {
            check("脚本存在（" + SCRIPT + "）", false);
            System.out.println();
            return;
        }
        String jsSrc = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);
        int idx = jsSrc.indexOf("已加载");
        check("脚本里有「已加载」这一行（全文恰好 1 处）", idx >= 0
                && jsSrc.indexOf("已加载", idx + 1) < 0);
        String fromScript = null;
        if (idx >= 0) {
            int a = jsSrc.lastIndexOf('\'', idx);
            int b = jsSrc.indexOf('\'', idx);
            if (a >= 0 && b > a) {
                fromScript = jsSrc.substring(a + 1, b);
            }
        }
        System.out.println("      脚本 : " + fromScript);
        check("脚本里那句字面量 = " + PhantomModule.loadLine(),
                PhantomModule.loadLine().equals(fromScript));
        check("★ 脚本里那句就是「[构思技艺] gs_boss_phantom 已加载」（不是框架打的）",
                "[构思技艺] gs_boss_phantom 已加载".equals(fromScript));

        // ---- B1：参考服日志里属于本模块的行 ----
        Path ref = Paths.get(REF_LOG);
        if (!Files.isRegularFile(ref)) {
            check("参考服日志存在（" + REF_LOG + "）", false);
            System.out.println();
            return;
        }
        List<String> all = Files.readAllLines(ref, StandardCharsets.UTF_8);
        List<String> mine = new ArrayList<>();
        for (String line : all) {
            if (line.contains("gs_boss_phantom") || line.contains("幻翼boss")) {
                mine.add(line);
            }
        }
        for (String s : mine) {
            System.out.println("      参考 : " + s);
        }
        check("参考服日志里本模块的行恰好 4 行（实际 " + mine.size() + "）", mine.size() == 4);
        if (mine.size() != 4) {
            System.out.println();
            return;
        }

        // ---- B2：Java 侧产出的 4 行 ----
        NamespacedKeyHolder holder = new NamespacedKeyHolder();
        String[] java = {
                PhantomModule.loadLine(),
                PhantomModule.paramLine(),
                PhantomModule.skillLine(),
                holder.line(),
        };
        String[] names = {"已加载", "参数", "技能", "PDC 键"};
        for (int i = 0; i < 4; i++) {
            boolean ok = java[i].equals(mine.get(i));
            if (!ok) {
                System.out.println("      不一致[" + names[i] + "]");
                System.out.println("        Java : " + java[i]);
                System.out.println("        参考 : " + mine.get(i));
                System.out.println("        差异 : " + diff(java[i], mine.get(i)));
            }
            check(names[i] + " 行逐字节相同（len " + java[i].length()
                    + " vs " + mine.get(i).length() + "）", ok);
        }

        // ---- B3：★★ 反向断言：加载日志集合恰好等于这 4 行 ----
        List<String> noArgLines = new ArrayList<>();
        for (Method mm : PhantomModule.class.getDeclaredMethods()) {
            if (Modifier.isStatic(mm.getModifiers()) && mm.getParameterCount() == 0
                    && mm.getReturnType() == String.class && mm.getName().endsWith("Line")) {
                mm.setAccessible(true);
                noArgLines.add(mm.getName());
            }
        }
        System.out.println("      无参 *Line() 方法：" + noArgLines);
        check("★ 无参「日志行」方法恰好 3 个（已加载 / 参数 / 技能）+ pdcLine(1 参) = 4 行",
                noArgLines.size() == 3);
        check("★ 这 3 个就是 loadLine / paramLine / skillLine",
                new TreeSet<>(noArgLines).equals(
                        new TreeSet<>(Arrays.asList("loadLine", "paramLine", "skillLine"))));

        // ★ 注意：这里扫的是**去掉注释后的代码**。
        //   本模块的类注释里**故意**写着 ATTACK_DAMAGE / phSetDmg / JavaAdapter /
        //   PH_PET_GRIEF / 已加载 这些名字（用来记录"为什么不移植"），
        //   直接扫全文会全部误报。
        String code = readModuleCode();
        check("★ 模块代码里「已加载」恰好出现 1 次（多打一行就会被这条拦住）",
                code != null && countOf(code, "已加载") == 1);
        for (String other : new String[]{"gs_boss_nightwatch", "gs_boss_shadow"}) {
            check("★ 没有为「一致性」去补别的脚本的加载行（" + other + " 零命中）",
                    code != null && !code.contains(other));
        }        // 参考服日志里也不该有那两个脚本的加载行（它们脚本本身就没写）
        check("★ 参考服日志里没有 gs_boss_nightwatch / gs_boss_shadow 的「已加载」行",
                !all.stream().anyMatch(l -> l.contains("已加载")
                        && (l.contains("nightwatch") || l.contains("shadow"))));

        System.out.println();
    }

    /** 造一个 {@code gs:boss_phantom} 键（原脚本用 {@code NamespacedKey.fromString}）。 */
    private static final class NamespacedKeyHolder {
        private final org.bukkit.NamespacedKey key;

        NamespacedKeyHolder() {
            org.bukkit.NamespacedKey k = null;
            try {
                k = org.bukkit.NamespacedKey.fromString("gs:" + PhantomModule.PH_KEYNAME);
            } catch (Throwable ignored) {
                k = null;
            }
            this.key = k;
        }

        String line() {
            if (key == null) {
                return "<NamespacedKey 构造失败>";
            }
            return PhantomModule.pdcLine(key);
        }
    }

    // ==================================================================
    // [C] 数字格式化（最高风险点）
    // ==================================================================

    private static void numberFormat() throws Exception {
        System.out.println("[C] toFixed / jsNum 与真 JS 逐值一致（日志行能否逐字节相同的关键）");

        double[] vals = {
                0.0, -0.0, 1.0, -1.0, 2.5, -2.5, 4.5, 1000.0, -1000.0,
                2.857142857142857, 2.5, 1.75, 0.875,
                0.5 * (1.0 + 0.15 * 5) * (4.5 / (0.9 * (1.0 + 0.15 * 5))),
                0.1, 0.2, 0.3, 1.0 / 3.0, 2.0 / 3.0,
                1.005, -1.005, 9.995, 2.675, 0.125, 0.375, 123456.789,
                1e-6, 1e-7, 1.5e-7, 1234567890123456.0, 1e16, 1e20, 1e21, 1e22,
                5e-324, 1.7976931348623157e308, 1234567890123456789.0,
                55.0, 10.0, 8.0, 11.0, 64.0, 0.01, 0.05, 12.0, 1.6, 2.2,
                // ★ 平局（x.5 / x.x5 这类）—— 用来钉死 toFixed 的"平局往哪边倒"：
                //   ECMA-262 说"取较大的 n"，但 V8/GraalJS 实测是"远离零"。
                //   参考服跑的是 GraalJS ⇒ 以实测为准。
                -0.5, -1.5, -3.5, -4.5, 0.5, 1.5, 3.5, 4.5,
                -0.05, -0.15, -0.25, -0.35, 0.05, 0.15, 0.25, 0.35,
                -1.25, -2.25, -1.75, 1.25, 2.25, 1.75,
                -2.675, 2.675, -9.995, -1.005, 1.005,
                -2.2250738585072014e-308, 2.2250738585072014e-308,
                // ★ 父代理给的 ground truth 里点名的几个（"-2.0" 与 "-0.00" 的负零特判）
                -2.05, -0.004, -0.0001, 0.004,
        };
        int[] fs = {0, 1, 2, 3, 4, 6, 10, 17, 100};

        // ① toFixed
        StringBuilder jsIn = new StringBuilder();
        for (double v : vals) {
            jsIn.append(v).append(',');
        }
        String jsArr = "[" + jsIn.substring(0, jsIn.length() - 1) + "]";
        String jsFs = Arrays.toString(fs).replace(" ", "");

        String[] jsToFixed = evalStrings(
                "function toFixedAll(vals, fs) {\n"
                        + "  var out = [];\n"
                        + "  for (var i = 0; i < vals.length; i++) {\n"
                        + "    for (var j = 0; j < fs.length; j++) out.push(vals[i].toFixed(fs[j]));\n"
                        + "  }\n"
                        + "  return out;\n"
                        + "}\n"
                        + "toFixedAll(" + jsArr + ", " + jsFs + ");");

        int n = 0;
        int bad = 0;
        for (double v : vals) {
            for (int f : fs) {
                String got = PhantomModule.toFixed(v, f);
                String want = jsToFixed[n++];
                if (!got.equals(want)) {
                    bad++;
                    if (bad <= 8) {
                        System.out.println("      不一致 toFixed(" + v + ", " + f + ") JS=\""
                                + want + "\" Java=\"" + got + "\"");
                    }
                }
            }
        }
        check("toFixed：" + n + " 例（" + vals.length + " 值 × " + fs.length + " 位）逐字节一致",
                bad == 0);

        // ② jsNum（'' + 数字）
        String[] jsStr = evalStrings(
                "function strAll(vals) {\n"
                        + "  var out = [];\n"
                        + "  for (var i = 0; i < vals.length; i++) out.push('' + vals[i]);\n"
                        + "  return out;\n"
                        + "}\n"
                        + "strAll(" + jsArr + ");");
        int bad2 = 0;
        for (int i = 0; i < vals.length; i++) {
            String got = PhantomModule.jsNum(vals[i]);
            if (!got.equals(jsStr[i])) {
                bad2++;
                System.out.println("      不一致 String(" + vals[i] + ") JS=\"" + jsStr[i]
                        + "\" Java=\"" + got + "\"");
            }
        }
        check("jsNum（'' + 数字）：" + vals.length + " 例逐字节一致", bad2 == 0);

        // ③ NaN / Infinity / 越界
        check("toFixed(NaN,2) = \"NaN\"（JS 规范）",
                "NaN".equals(PhantomModule.toFixed(Double.NaN, 2)));
        check("toFixed(+Inf,2) = \"Infinity\"",
                "Infinity".equals(PhantomModule.toFixed(Double.POSITIVE_INFINITY, 2)));
        check("toFixed(-Inf,2) = \"-Infinity\"",
                "-Infinity".equals(PhantomModule.toFixed(Double.NEGATIVE_INFINITY, 2)));
        check("jsNum(NaN) = \"NaN\"", "NaN".equals(PhantomModule.jsNum(Double.NaN)));
        check("jsNum(1e21) = \"1e+21\"（≥10^21 走科学计数法）",
                "1e+21".equals(PhantomModule.jsNum(1e21)));
        check("jsNum(-0.0) = \"0\"（JS 的 String(-0) 是 \"0\"）",
                "0".equals(PhantomModule.jsNum(-0.0)));

        // ④ ★ 日志行里真正用到的那两个值
        check("★ jsNum(1000.0) = \"1000\"（不是 Java 默认的 \"1000.0\"）",
                "1000".equals(PhantomModule.jsNum(PhantomModule.PH_HP)));
        check("★ toFixed(PH_SCALE,3) = \"2.857\"",
                "2.857".equals(PhantomModule.toFixed(PhantomModule.PH_SCALE, 3)));
        check("★ toFixed(碰撞箱高,2) = \"2.50\"（尾零必须留着）",
                "2.50".equals(PhantomModule.toFixed(
                        PhantomModule.phBoxH(PhantomModule.PH_SCALE, PhantomModule.PH_SIZE), 2)));

        System.out.println();
    }

    // ==================================================================
    // [D] 算式对账
    // ==================================================================

    private static void formulas() throws Exception {
        System.out.println("[D] 缩放 / 弹道 / 瞄准 / 转向 / 命中判定 与真 JS 逐值一致");

        // ---- D1：缩放换算（纯四则 ⇒ 位精确） ----
        check("phScaleFor(4.5,5) == 4.5/(0.9*(1+0.15*5))",
                PhantomModule.phScaleFor(4.5, 5)
                        == 4.5 / (0.9 * (1.0 + 0.15 * 5)));
        check("phScaleFor == PH_SCALE", PhantomModule.phScaleFor(4.5, 5) == PhantomModule.PH_SCALE);
        check("phBoxH(PH_SCALE,5) == 0.5*(1+0.15*5)*PH_SCALE",
                PhantomModule.phBoxH(PhantomModule.PH_SCALE, 5)
                        == 0.5 * (1.0 + 0.15 * 5) * PhantomModule.PH_SCALE);
        int badScale = 0;
        for (int size = 0; size <= 10; size++) {
            for (double boxW = 1.0; boxW <= 10.0; boxW += 0.5) {
                double s = boxW / (0.9 * (1.0 + 0.15 * size));
                if (PhantomModule.phScaleFor(boxW, size) != s) {
                    badScale++;
                }
                if (PhantomModule.phBoxH(s, size) != 0.5 * (1.0 + 0.15 * size) * s) {
                    badScale++;
                }
            }
        }
        check("缩放换算 220 例位精确（size 0~10 × 宽 1.0~10.0）", badScale == 0);

        // ---- D2：三叉戟弹道补偿（含 sqrt ⇒ 位精确；含 12.0 上限钳制） ----
        String[] jsDrop = evalStrings(
                "var PH_TR_SPEED = " + d(PhantomModule.PH_TR_SPEED) + ";\n"
                        + "var PH_TR_GRAV = " + d(PhantomModule.PH_TR_GRAV) + ";\n"
                        + "var PH_TR_LEAD = " + d(PhantomModule.PH_TR_LEAD) + ";\n"
                        + "var PH_TR_LEAD_CAP = " + d(PhantomModule.PH_TR_LEAD_CAP) + ";\n"
                        + "function dropOf(dh) {\n"
                        + "  var tt = dh / PH_TR_SPEED;\n"
                        + "  var drop = 0.5 * PH_TR_GRAV * tt * tt * PH_TR_LEAD;\n"
                        + "  if (drop > PH_TR_LEAD_CAP) drop = PH_TR_LEAD_CAP;\n"
                        + "  if (drop < 0.0) drop = 0.0;\n"
                        + "  return drop;\n"
                        + "}\n"
                        + "function dropAll(ds) { var o = []; for (var i=0;i<ds.length;i++) o.push('' + dropOf(ds[i])); return o; }\n"
                        + "dropAll(" + numArr(dhCases()) + ");");
        List<Double> dhs = dhCases();
        int badDrop = 0;
        for (int i = 0; i < dhs.size(); i++) {
            String got = PhantomModule.jsNum(PhantomModule.phTridentDrop(dhs.get(i)));
            if (!got.equals(jsDrop[i])) {
                badDrop++;
                if (badDrop <= 5) {
                    System.out.println("      不一致 dh=" + dhs.get(i)
                            + " JS=" + jsDrop[i] + " Java=" + got);
                }
            }
        }
        check("三叉戟抬高量：" + dhs.size() + " 例位精确（含 0 / 极远 / 触发 12.0 上限）", badDrop == 0);
        check("dh=0 ⇒ 抬高 0", PhantomModule.phTridentDrop(0.0) == 0.0);
        check("dh=1.6（1 刻射程）⇒ 抬高极小（½·0.05·1² = 0.025）",
                Math.abs(PhantomModule.phTridentDrop(1.6) - 0.025) < 1e-15);
        check("★ 极远距离被钳到 PH_TR_LEAD_CAP = 12.0",
                PhantomModule.phTridentDrop(1000.0) == 12.0);
        check("★ 钳制点就在 12.0（找到第一个被钳住的 dh 并确认它之前的没被钳）",
                clampBoundaryOk());

        // ---- D3：弹体瞄准方向（sqrt ⇒ 位精确；含 1e-4 退化） ----
        check("★ 两点重合 ⇒ null（原脚本 n < 1.0e-4 直接 return null）",
                PhantomModule.phAim(1.0, 2.0, 3.0, 1.0, 2.0, 3.0) == null);
        check("★ 距离 1e-5（< 1e-4）⇒ 也 null",
                PhantomModule.phAim(0, 0, 0, 1e-5, 0, 0) == null);
        check("★ 距离 1e-3（> 1e-4）⇒ 有方向",
                PhantomModule.phAim(0, 0, 0, 1e-3, 0, 0) != null);
        int badAim = 0;
        double[][] aims = {
                {0, 0, 0, 1, 0, 0}, {0, 0, 0, 0, 1, 0}, {0, 0, 0, 0, 0, 1},
                {0, 0, 0, -1, -2, -3}, {10, 64, -30, -12, 70, 5},
                {0.5, 1.5, 2.5, 3.5, 4.5, 5.5}, {-100, 200, 300, 100, 200, 300},
        };
        for (double[] c : aims) {
            double[] got = PhantomModule.phAim(c[0], c[1], c[2], c[3], c[4], c[5]);
            double[] want = jsAim(c);
            if (got == null || want == null) {
                if (got != want) {
                    badAim++;
                }
                continue;
            }
            for (int i = 0; i < 3; i++) {
                if (got[i] != want[i]) {
                    badAim++;
                }
            }
        }
        check("弹体瞄准方向 " + aims.length + " 例位精确（含负数 / 大坐标）", badAim == 0);
        check("瞄准方向是单位向量（|dir| = 1）", () -> {
            double[] v = PhantomModule.phAim(3.0, 7.0, -2.0, 11.0, 40.0, 6.0);
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            return Math.abs(n - 1.0) < 1e-15;
        });

        // ---- D4：凋零之首转向（sqrt ⇒ 位精确） ----
        int badSteer = 0;
        int steerNull = 0;
        int steerOk = 0;
        int seed = 12345;
        for (int i = 0; i < 4000; i++) {
            seed = seed * 48271 % 2147483647;
            double vx = (seed % 2000) / 1000.0 - 1.0;
            seed = seed * 48271 % 2147483647;
            double vy = (seed % 2000) / 1000.0 - 1.0;
            seed = seed * 48271 % 2147483647;
            double vz = (seed % 2000) / 1000.0 - 1.0;
            seed = seed * 48271 % 2147483647;
            double sx = (seed % 4000) / 100.0 - 20.0;
            seed = seed * 48271 % 2147483647;
            double sy = (seed % 4000) / 100.0 - 20.0;
            seed = seed * 48271 % 2147483647;
            double sz = (seed % 4000) / 100.0 - 20.0;
            seed = seed * 48271 % 2147483647;
            double tx = sx + (seed % 2000) / 100.0 - 10.0;
            seed = seed * 48271 % 2147483647;
            double ty = sy + (seed % 2000) / 100.0 - 10.0;
            seed = seed * 48271 % 2147483647;
            double tz = sz + (seed % 2000) / 100.0 - 10.0;

            double[] got = PhantomModule.phSkullSteer(vx, vy, vz, sx, sy, sz, tx, ty, tz);
            double[] want = jsSteer(vx, vy, vz, sx, sy, sz, tx, ty, tz);
            if (got == null || want == null) {
                steerNull++;
                if (got != want) {
                    badSteer++;
                }
                continue;
            }
            steerOk++;
            for (int k = 0; k < 3; k++) {
                if (got[k] != want[k]) {
                    badSteer++;
                    if (badSteer <= 3) {
                        System.out.println("      不一致 steer JS=" + Arrays.toString(want)
                                + " Java=" + Arrays.toString(got));
                    }
                    break;
                }
            }
        }
        check("凋零之首转向 4000 例位精确（成功 " + steerOk + " ｜ 返回 null " + steerNull + "）",
                badSteer == 0);

        // ★ 专门补一组「速度 < 0.05」的用例（随机用例不一定抽到这么小的速度），
        //   把 sp<0.05 的退回分支也拉去和真 JS 逐值对账。
        double[][] slow = {
                {0, 0, 0, 0, 0, 0, 0, 0, 10},        // 完全静止
                {0, 0, 0.049, 0, 0, 0, 0, 0, 10},    // 刚好在门槛下
                {0.01, 0, 0, 5, 64, 5, 5, 65, 5},    // 极慢 + 目标在上方
                {0, -0.02, 0, 0, 0, 0, -7, 3, 20},   // 极慢 + 目标在斜后方
                {0.03, 0.03, 0.03, 1, 2, 3, 4, 6, 8},// 极慢 + 全分量
                {0.05, 0, 0, 0, 0, 0, 0, 0, 10},     // 门槛上（不 < 0.05 ⇒ 正常分支）
                {0.0500000001, 0, 0, 0, 0, 0, 0, 0, 10},
        };
        int badSlow = 0;
        for (double[] c : slow) {
            double[] got = PhantomModule.phSkullSteer(c[0], c[1], c[2], c[3], c[4], c[5],
                    c[6], c[7], c[8]);
            double[] want = jsSteer(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8]);
            boolean ok;
            if (got == null || want == null) {
                ok = (got == want);
            } else {
                ok = got[0] == want[0] && got[1] == want[1] && got[2] == want[2];
            }
            if (!ok) {
                badSlow++;
                System.out.println("      不一致 slow v=" + Arrays.toString(c)
                        + " JS=" + (want == null ? "null" : Arrays.toString(want))
                        + " Java=" + (got == null ? "null" : Arrays.toString(got)));
            }
        }
        check("★ 速度 < 0.05 的退回分支 " + slow.length + " 例与真 JS 逐值一致", badSlow == 0);
        // ★ 关于「速度 < 0.05 的退回」这一条 —— 先说清楚为什么这么写。
        //   原脚本（gs_boss_phantom.js:1176~1178 逐字）：
        //       var sp = Math.sqrt(dx*dx + dy*dy + dz*dz);
        //       if (sp < 0.05) { sp = PH_WS_SPEED; dx = 0; dy = 0; dz = 1; }
        //       dx /= sp; dy /= sp; dz /= sp;
        //   ⇒ 退回分支的**可观察后果**是「不返回 null」：
        //     若没有这一句，sp = 0 ⇒ 0/0 = NaN ⇒ nn = NaN ⇒ (nn > 1e-4) 为假
        //     ⇒ 原脚本整段跳过 ⇒ 返回 null。
        //   所以用「速度全 0 时必须非 null」来钉住这个分支的存在。
        //   ★ 而「退回后 v[2] 恰好等于 PH_WS_SPEED」**只在目标正好在水平正前方时**成立
        //     （否则 k=0.22 的转向会把方向掰上去，v[2] 自然小于 PH_WS_SPEED）——
        //     最初把断言写成"任何目标都恰好等于"是**探针写错了**，不是实现错，
        //     这里改成先证明"退回分支被走到"，再在"转向是恒等变换"的输入上比精确值。
        check("★ 速度全 0 ⇒ 非 null（证明 sp<0.05 的退回分支被走到；没有它会是 NaN ⇒ null）",
                PhantomModule.phSkullSteer(0, 0, 0, 0, 0, 0, 0, 0, 10) != null);
        check("★ 速度 0.049（< 0.05）与速度 0 结果完全相同（同一分支）", () -> {
            double[] a = PhantomModule.phSkullSteer(0, 0, 0, 0, 0, 0, 0, 0, 10);
            double[] b = PhantomModule.phSkullSteer(0, 0, 0.049, 0, 0, 0, 0, 0, 10);
            return a != null && b != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
        });
        check("★ 速度 0.05（不 < 0.05）⇒ 走正常分支（方向取速度本身，不再退回 +z）", () -> {
            // ★ 速度必须**不平行于目标方向**才看得出分支差异：
            //   若用 (0,0,0.05)，归一化后也是 (0,0,1)，与退回分支结果恰好相同 ⇒ 分不出来。
            //   这里用 (0.05,0,0)：正常分支下方向是 +x，与退回分支的 +z 明显不同。
            double[] a = PhantomModule.phSkullSteer(0, 0, 0, 0, 0, 0, 0, -1, 10);
            double[] b = PhantomModule.phSkullSteer(0.05, 0, 0, 0, 0, 0, 0, -1, 10);
            return a != null && b != null && a[2] == PhantomModule.PH_WS_SPEED
                    && b[2] < 0.5;
        });
        check("★ 目标正好在水平正前方（wy=0）⇒ 转向是恒等变换，"
                        + "退回后 v = (0,0,PH_WS_SPEED) 精确成立",
                () -> {
                    // 弹体在 (0,0,0)，目标在 (0,-1,10) ⇒ wy = (ty+1) - sy = 0
                    double[] v = PhantomModule.phSkullSteer(0, 0, 0, 0, 0, 0, 0, -1, 10);
                    return v != null && v[0] == 0.0 && v[1] == 0.0
                            && v[2] == PhantomModule.PH_WS_SPEED;
                });
        check("★ 退回后的方向是 +z（不是 +x / +y）", () -> {
            double[] v = PhantomModule.phSkullSteer(0, 0, 0, 0, 0, 0, 0, -1, 10);
            return v != null && v[2] > 0.0;
        });
        check("★ 目标与弹体同点 ⇒ null（wn 不 > 1e-4 ⇒ 原脚本整段跳过）",
                PhantomModule.phSkullSteer(1, 0, 0, 5, 5, 5, 5, 4, 5) == null);
        check("★ 新速度长度 = PH_WS_SPEED（归一化后再乘）", () -> {
            double[] v = PhantomModule.phSkullSteer(1, 0, 0, 0, 0, 0, 10, 0, 10);
            double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
            return Math.abs(n - PhantomModule.PH_WS_SPEED) < 1e-15;
        });
        check("★ 转向系数 = 0.22（越小越钝）⇒ 一拍的偏转角远小于 90°", () -> {
            double[] v = PhantomModule.phSkullSteer(1, 0, 0, 0, 0, 0, 0, 0, 10);
            // 旧方向 (1,0,0)，目标在 +z ⇒ 新方向夹角应为 atan(0.22/(0.78)) ≈ 15.7°
            double ang = Math.toDegrees(Math.atan2(v[2], v[0]));
            return ang > 14.0 && ang < 17.0;
        });

        // ---- D5：冲刺命中判定（sqrt/abs ⇒ 位精确；边界是 <=） ----
        check("★ 水平恰 2.5 ⇒ 命中（<= 不是 <）",
                PhantomModule.phDashHitTest(2.5, 0, 0, 0, 0, 0));
        check("★ 水平 2.5 + 1e-9 ⇒ 不命中",
                !PhantomModule.phDashHitTest(2.5 + 1e-9, 0, 0, 0, 0, 0));
        check("★ 垂直恰 2.5 ⇒ 命中",
                PhantomModule.phDashHitTest(0, 2.5, 0, 0, 0, 0));
        check("★ 垂直 2.5 + 1e-9 ⇒ 不命中",
                !PhantomModule.phDashHitTest(0, 2.5 + 1e-9, 0, 0, 0, 0));
        check("★ 水平超但垂直够 ⇒ 不命中（两个条件是与）",
                !PhantomModule.phDashHitTest(2.6, 0, 0, 0, 0, 0));
        check("★ 斜向：水平距离按 √(hx²+hz²) 算（3,4 ⇒ 5 > 2.5 ⇒ 不命中）",
                !PhantomModule.phDashHitTest(3, 0, 4, 0, 0, 0));
        check("★ 垂直只看脚下 Y 之差（用 sqrt 之外的 abs）",
                PhantomModule.phDashHitTest(0, -2.5, 0, 0, 0, 0));
        int badHit = 0;
        for (int i = -30; i <= 30; i++) {
            for (int j = -30; j <= 30; j++) {
                for (int k = -30; k <= 30; k++) {
                    double px = i * 0.25;
                    double py = j * 0.25;
                    double pz = k * 0.25;
                    boolean got = PhantomModule.phDashHitTest(px, py, pz, 0, 0, 0);
                    double hyd = Math.sqrt(px * px + pz * pz);
                    double vyd = Math.abs(py);
                    boolean want = hyd <= PhantomModule.PH_DA_HIT_R
                            && vyd <= PhantomModule.PH_DA_HIT_V;
                    if (got != want) {
                        badHit++;
                    }
                }
            }
        }
        check("冲刺命中判定 226981 例（±7.5 格 × 0.25 步长）与公式一致", badHit == 0);

        // ---- D6：蓄力烟花音效节拍（整数运算 ⇒ 精确） ----
        check("★ perSteps = max(1, floor(ceil(30/2)/3)) = 5", PhantomModule.phFwPerSteps() == 5);
        check("★ 30 刻 ÷ 5 = 恰好 6 拍，音效只在 step ∈ {0,5,10} 响（共 3 次 = PH_DA_FW_TIMES）",
                () -> {
                    int n = 0;
                    for (int s = 0; s < PhantomModule.PH_DA_CHARGE; s++) {
                        if (PhantomModule.phFwPitchAt(s) >= 0) {
                            n++;
                        }
                    }
                    return n == PhantomModule.PH_DA_FW_TIMES;
                });
        check("★ 音高依次 0.9 / 1.1 / 1.3",
                PhantomModule.phFwPitchAt(0) == 0.9
                        && PhantomModule.phFwPitchAt(5) == 1.1
                        && PhantomModule.phFwPitchAt(10) == 1.3);
        check("★ step=1 不响（不是每拍都响）", PhantomModule.phFwPitchAt(1) < 0);
        check("★ step=15（第 4 段）不响（idx 已 >= PH_DA_FW_TIMES）",
                PhantomModule.phFwPitchAt(15) < 0);
        check("★ step=25 不响（idx=5）", PhantomModule.phFwPitchAt(25) < 0);
        int badFw = 0;
        String[] jsFw = evalStrings(
                "var PH_DA_CHARGE = " + PhantomModule.PH_DA_CHARGE + ";\n"
                        + "var PH_DA_FW_TIMES = " + PhantomModule.PH_DA_FW_TIMES + ";\n"
                        + "var perSteps = Math.max(1, Math.floor(Math.ceil(PH_DA_CHARGE / 2) / PH_DA_FW_TIMES));\n"
                        + "function fwAt(step) {\n"
                        + "  var idx = Math.floor(step / perSteps);\n"
                        + "  if (step % perSteps === 0 && idx < PH_DA_FW_TIMES) return '' + (0.9 + idx * 0.2);\n"
                        + "  return '-1';\n"
                        + "}\n"
                        + "var o = [];\n"
                        + "for (var s = -5; s <= 60; s++) o.push(fwAt(s));\n"
                        + "o;");
        for (int s = -5; s <= 60; s++) {
            String got = PhantomModule.jsNum(PhantomModule.phFwPitchAt(s));
            if (!got.equals(jsFw[s + 5])) {
                badFw++;
                if (badFw <= 5) {
                    System.out.println("      不一致 fwAt(" + s + ") JS=" + jsFw[s + 5]
                            + " Java=" + got);
                }
            }
        }
        check("烟花音效节拍 66 例（step -5~60）逐值一致", badFw == 0);

        // ---- D7：日志行里的整除（JS 浮点除法 vs Java 整除） ----
        check("★ 5 个冷却都是 1000 的整数倍 ⇒ JS 浮点除法与 Java 整除结果相同",
                PhantomModule.PH_SB_CD % 1000 == 0 && PhantomModule.PH_DF_CD % 1000 == 0
                        && PhantomModule.PH_WS_CD % 1000 == 0 && PhantomModule.PH_TR_CD % 1000 == 0
                        && PhantomModule.PH_FIRST_MS % 1000 == 0);
        check("★ 技能日志行只列 ①②③④（技能⑤ 17 秒**不在里面** —— 原脚本就没写）",
                !PhantomModule.skillLine().contains("⑤")
                        && !PhantomModule.skillLine().contains("17秒"));
        check("★ 参数行「普攻=原版（11 点）」来自 6 + PH_SIZE（不是硬编码 11）",
                PhantomModule.paramLine().contains("普攻=原版（" + (6 + PhantomModule.PH_SIZE) + " 点）"));

        // ---- D8：源码级反向断言（死代码不许被"顺手补回来"） ----
        //   ★ 同样只扫**去掉注释后的代码**（类注释里刻意保留了这些名字）
        String src = readModuleCode();
        if (src != null) {
            check("★ 没有写 ATTACK_DAMAGE 属性（普攻保持原版幻翼 6+体型档）",
                    !src.contains("ATTACK_DAMAGE"));
            check("★ 没有移植 phSetDmg（原脚本定义了但全文件零调用）",
                    !src.contains("phSetDmg"));
            check("★ 没有 JavaAdapter / Java.extend（GraalJS 25 里不存在）",
                    !src.contains("JavaAdapter") && !src.contains("Java.extend"));
            check("★ 没有用 new NamespacedKey(...)（GraalJS 重载歧义；必须 fromString）",
                    !src.contains("new NamespacedKey("));
            check("★ 没有 System.out.println（契约红线 5）", !src.contains("System.out"));
            check("★ 没有 PH_PET_GRIEF（第六十三批删除，苦力怕无条件不掉方块）",
                    !src.contains("PH_PET_GRIEF"));
            check("★ 没有 PH_REG_EXP / PH_REG_DEATH（原脚本里只声明、从不读写的死变量）",
                    !src.contains("PH_REG_EXP") && !src.contains("PH_REG_DEATH"));
            check("★ 保留了「1 刻后按快照还原方块」的兜底（原脚本明写的真保险）",
                    src.contains("setBlockData"));
            check("★ 保留了「清完再清一遍」的第二次 getDrops().clear()",
                    countOf(src, "getDrops().clear()") == 2);
            // ★★ setDirection 必须**在**（这里曾经漏移植过，特此钉住，防止再被删）
            //   原脚本 1067 行（phLaunch，通用弹体发射器）与 1194 行（phSkullGuide，凋零之首）
            //   各有一句 `try { x.setDirection(new Vec(...)); } catch (e) { }`。
            //   `setDirection` **声明在 Fireball 上**（Fireball extends Projectile, Explosive）
            //   ⇒ WitherSkull / DragonFireball 都有、这两句**真的生效**；
            //   只有 Trident（extends AbstractArrow, ThrowableProjectile）没有 ⇒ 抛了被吞。
            //   ★ 曾经因为 `javap Entity` / `javap Projectile` 看不到它就误判成死路径 ——
            //     那是"javap 对接口只列自己声明的方法、不列继承来的"，不能用父接口判定子接口。
            check("★ 模块代码里 setDirection 恰好调用 2 次（phLaunch + phSkullGuide）"
                            + " —— 漏掉会让凋零之首不再转向",
                    countOf(src, ".setDirection(") == 2);
            check("★ 两处都走 instanceof Fireball 分派（= 原脚本 try/catch 的静态化："
                            + "Fireball 族生效、Trident 静默失败）",
                    countOf(src, "instanceof Fireball") == 2);
            check("★ phLaunch 里那句在（龙息弹 / 凋零之首的朝向确实会被设置）",
                    src.contains("fb.setDirection(new Vector(dx, dy, dz))"));
            check("★ phSkullGuide 里那句在（追击中的凋零之首朝向确实会被设置）",
                    src.contains("fb.setDirection(new Vector(nv[0], nv[1], nv[2]))"));
        } else {
            check("能读到模块源码（" + MODULE_SRC + "）", false);
        }

        System.out.println();
    }

    /** 12.0 钳制边界：找到第一个被钳住的 dh，确认它前一个还没被钳。 */
    private static boolean clampBoundaryOk() {
        double prev = PhantomModule.phTridentDrop(0.0);
        for (double dh = 0.0; dh < 500.0; dh += 0.001) {
            double cur = PhantomModule.phTridentDrop(dh);
            if (cur == PhantomModule.PH_TR_LEAD_CAP) {
                return prev < PhantomModule.PH_TR_LEAD_CAP;
            }
            prev = cur;
        }
        return false;
    }

    // ==================================================================
    // [E] 「1 枚瞄准 + 2 枚随机落点」的分配
    // ==================================================================

    private static void spreadPoints() throws Exception {
        System.out.println("[E] 落点分配（1 枚瞄准 + N-1 枚随机）与真 JS 逐值一致");

        // ★ 两侧注入同一条确定性随机序列（MINSTD），才能把"随机落点"也逐值对账。
        //   JS 侧覆盖 Math.random；Java 侧换掉 PhantomModule.RANDOM。
        //   序列本身在 JS 与 Java 下逐位相同：state*48271 < 2^53 ⇒ 双精度能精确表示。
        String jsFn =
                "function lcg(seed) {\n"
                        + "  var s = seed;\n"
                        + "  return function () { s = (s * 48271) % 2147483647; return s / 2147483647.0; };\n"
                        + "}\n"
                        + "function withSeed(seed) { Math.random = lcg(seed); }\n"
                        // ---- gs_boss_phantom.js:270-282 逐字 ----
                        + "function phPassableAt(world, bx, by, bz) {\n"
                        + "    try {\n"
                        + "        var Loc = Java.type('org.bukkit.Location');\n"
                        + "        var blk = world.getBlockAt(new Loc(world, 0 + bx, 0 + by, 0 + bz));\n"
                        + "        if (blk == null) return true;\n"
                        + "        var mt = null;\n"
                        + "        try { mt = blk.getType(); } catch (e) { mt = null; }\n"
                        + "        if (mt != null) { try { if (mt.isAir()) return true; } catch (e) { } }\n"
                        + "        try { return !!blk.isPassable(); } catch (e) { }\n"
                        + "        if (mt != null) { try { return !mt.isSolid(); } catch (e) { } }\n"
                        + "        return true;\n"
                        + "    } catch (e) { return true; }\n"
                        + "}\n"
                        // ---- gs_boss_phantom.js:285-301 逐字 ----
                        + "function phGroundY(world, x, z, y0) {\n"
                        + "    try {\n"
                        + "        var bx = Math.floor(x), bz = Math.floor(z);\n"
                        + "        var start = Math.floor(y0);\n"
                        + "        var lo = start - 6, hi = start + 8;\n"
                        + "        if (lo < -60) lo = -60;\n"
                        + "        if (hi > 250) hi = 250;\n"
                        + "        var y;\n"
                        + "        for (y = start + 4; y >= lo; y--) {\n"
                        + "            if (phPassableAt(world, bx, y, bz) && !phPassableAt(world, bx, y - 1, bz)) return y;\n"
                        + "        }\n"
                        + "        for (y = start; y <= hi; y++) {\n"
                        + "            if (phPassableAt(world, bx, y, bz)) return y;\n"
                        + "        }\n"
                        + "        return y0;\n"
                        + "    } catch (e) { return y0; }\n"
                        + "}\n"
                        // ---- gs_boss_phantom.js:1032-1048 逐字 ----
                        + "function phSpreadPoints(tgt, total, spreadR) {\n"
                        + "    var out = [];\n"
                        + "    try {\n"
                        + "        var pl = tgt.getLocation();\n"
                        + "        out.push({ x: pl.getX(), y: pl.getY() + 1.2, z: pl.getZ() });\n"
                        + "        var world = tgt.getWorld();\n"
                        + "        for (var i = 1; i < total; i++) {\n"
                        + "            var a = Math.random() * Math.PI * 2.0;\n"
                        + "            var r = spreadR * (0.35 + 0.65 * Math.random());\n"
                        + "            var x = pl.getX() + Math.cos(a) * r;\n"
                        + "            var z = pl.getZ() + Math.sin(a) * r;\n"
                        + "            var y = phGroundY(world, x, z, pl.getY());\n"
                        + "            out.push({ x: x, y: y + 1.2, z: z });\n"
                        + "        }\n"
                        + "    } catch (e) { }\n"
                        + "    return out;\n"
                        + "}\n"
                        // ---- 驱动器：假目标（world = null，与 Java 侧一致） ----
                        + "function flat(px, py, pz, total, spreadR, seed) {\n"
                        + "  withSeed(seed);\n"
                        + "  var tgt = {\n"
                        + "    getLocation: function () { return {\n"
                        + "      getX: function () { return px; },\n"
                        + "      getY: function () { return py; },\n"
                        + "      getZ: function () { return pz; } }; },\n"
                        + "    getWorld: function () { return null; }\n"
                        + "  };\n"
                        + "  var pts = phSpreadPoints(tgt, total, spreadR);\n"
                        + "  var out = [];\n"
                        + "  for (var i = 0; i < pts.length; i++) {\n"
                        + "    out.push('' + pts[i].x); out.push('' + pts[i].y); out.push('' + pts[i].z);\n"
                        + "  }\n"
                        + "  return out;\n"
                        + "}\n";

        // 用例：瞄准点 / 玩家高度 / 数量 / 散布半径 / 随机种子
        Object[][] cases = {
                {100.5, 64.0, -200.25, 3, 8.0, 7},        // 技能②③ 的真实参数（×3，散布 8）
                {0.0, 70.0, 0.0, 3, 8.0, 999},            // 原点
                {-1234.75, -60.5, 9876.25, 3, 8.0, 424242}, // 负坐标 + 极低高度
                {0.5, 250.0, 0.5, 3, 8.0, 1},             // 高度上限附近
                {1.0, 64.0, 2.0, 1, 8.0, 5},              // total = 1 ⇒ 只瞄准，**不抽随机数**
                {1.0, 64.0, 2.0, 5, 8.0, 5},              // total = 5 ⇒ 4 枚随机
                {3.0, 64.0, 4.0, 3, 0.0, 11},             // 散布 0 ⇒ 落点与玩家同 XZ
        };

        for (Object[] c : cases) {
            double px = (Double) c[0];
            double py = (Double) c[1];
            double pz = (Double) c[2];
            int total = (Integer) c[3];
            double spreadR = (Double) c[4];
            long seed = ((Integer) c[5]).longValue();

            String[] js = evalStrings(jsFn + "flat(" + d(px) + "," + d(py) + "," + d(pz)
                    + "," + total + "," + d(spreadR) + "," + seed + ");");

            PhantomModule.RANDOM = lcg(seed);
            List<double[]> got;
            try {
                got = PhantomModule.phSpreadPointsCore(px, py, pz, null, total, spreadR);
            } finally {
                PhantomModule.RANDOM = Math::random;
            }

            String label = "px=" + px + " py=" + py + " pz=" + pz
                    + " total=" + total + " spread=" + spreadR + " seed=" + seed;
            boolean ok = got.size() == total && js.length == total * 3;
            if (ok) {
                for (int i = 0; i < total; i++) {
                    double[] p = got.get(i);
                    if (!eq(p[0], js[i * 3]) || !eq(p[1], js[i * 3 + 1]) || !eq(p[2], js[i * 3 + 2])) {
                        ok = false;
                        System.out.println("      不一致 " + label + " 点#" + i
                                + " JS=(" + js[i * 3] + ", " + js[i * 3 + 1] + ", " + js[i * 3 + 2]
                                + ") Java=(" + PhantomModule.jsNum(p[0]) + ", "
                                + PhantomModule.jsNum(p[1]) + ", " + PhantomModule.jsNum(p[2]) + ")");
                    }
                }
            }
            check(label + " ⇒ " + total + " 个点逐值一致", ok);
        }

        // ---- 结构性断言（不依赖随机序列） ----
        check("★ 第 1 个点就是玩家自身（x/z 原样，y + 1.2）—— 「瞄准1」不打提前量", () -> {
            PhantomModule.RANDOM = lcg(12345);
            try {
                List<double[]> pts = PhantomModule.phSpreadPointsCore(11.5, 64.0, -7.25, null, 3, 8.0);
                double[] p0 = pts.get(0);
                return p0[0] == 11.5 && p0[1] == 64.0 + 1.2 && p0[2] == -7.25;
            } finally {
                PhantomModule.RANDOM = Math::random;
            }
        });
        check("★ 第 1 个点**不消耗**随机数（先取瞄准点，再进循环）", () -> {
            int[] n = {0};
            PhantomModule.RANDOM = () -> {
                n[0]++;
                return 0.5;
            };
            try {
                PhantomModule.phSpreadPointsCore(0, 0, 0, null, 1, 8.0);
                return n[0] == 0;
            } finally {
                PhantomModule.RANDOM = Math::random;
            }
        });
        check("★ total = N 恰好消耗 2×(N-1) 个随机数（每点：角度 1 个 + 半径 1 个）", () -> {
            int[] n = {0};
            PhantomModule.RANDOM = () -> {
                n[0]++;
                return 0.5;
            };
            try {
                PhantomModule.phSpreadPointsCore(0, 0, 0, null, 3, 8.0);
                int a = n[0];
                n[0] = 0;
                PhantomModule.phSpreadPointsCore(0, 0, 0, null, 5, 8.0);
                return a == 4 && n[0] == 8;
            } finally {
                PhantomModule.RANDOM = Math::random;
            }
        });
        check("★ 随机落点在 [0.35, 1.0] × spreadR 的半径内（系数 0.35 + 0.65×rand）", () -> {
            PhantomModule.RANDOM = lcg(2024);
            try {
                double px = 0;
                double pz = 0;
                List<double[]> pts = PhantomModule.phSpreadPointsCore(px, 64.0, pz, null, 200, 8.0);
                for (int i = 1; i < pts.size(); i++) {
                    double r = Math.sqrt(pts.get(i)[0] * pts.get(i)[0]
                            + pts.get(i)[2] * pts.get(i)[2]);
                    if (r > 8.0 + 1e-9) {
                        return false;
                    }
                }
                return true;
            } finally {
                PhantomModule.RANDOM = Math::random;
            }
        });
        check("★ 散布半径 0 ⇒ 所有点与玩家同 XZ（半径系数乘 0）", () -> {
            PhantomModule.RANDOM = lcg(9);
            try {
                List<double[]> pts = PhantomModule.phSpreadPointsCore(5.0, 64.0, 6.0, null, 3, 0.0);
                for (double[] p : pts) {
                    if (Math.abs(p[0] - 5.0) > 1e-12 || Math.abs(p[2] - 6.0) > 1e-12) {
                        return false;
                    }
                }
                return true;
            } finally {
                PhantomModule.RANDOM = Math::random;
            }
        });
        check("★ world = null 时 phGroundY 回落到 floor(y0)（无 World 无法测真地形，两侧行为一致）",
                () -> {
                    PhantomModule.RANDOM = lcg(3);
                    try {
                        List<double[]> pts = PhantomModule.phSpreadPointsCore(0.0, 64.9, 0.0, null, 3, 8.0);
                        // y = floor(64.9) + 1.2 = 66.2
                        return Math.abs(pts.get(1)[1] - (64.0 + 1.2)) < 1e-12;
                    } finally {
                        PhantomModule.RANDOM = Math::random;
                    }
                });

        // ---- 技能②③ 的真实参数就是「1 枚瞄准 + 2 枚随机」 ----
        check("★ 龙息弹：PH_DF_N=3 / PH_DF_RAND=2 ⇒ 1 瞄准 + 2 随机",
                PhantomModule.PH_DF_N == 3 && PhantomModule.PH_DF_RAND == 2
                        && PhantomModule.PH_DF_N - PhantomModule.PH_DF_RAND == 1);
        check("★ 凋零之首：PH_WS_N=3 / PH_WS_RAND=2 ⇒ 1 瞄准 + 2 随机",
                PhantomModule.PH_WS_N == 3 && PhantomModule.PH_WS_RAND == 2
                        && PhantomModule.PH_WS_N - PhantomModule.PH_WS_RAND == 1);
        check("★ 日志行里的「瞄准1+随机2」与常量自洽",
                PhantomModule.skillLine().contains("瞄准" + (PhantomModule.PH_DF_N
                        - PhantomModule.PH_DF_RAND) + "+随机" + PhantomModule.PH_DF_RAND)
                        && PhantomModule.skillLine().contains("追击" + (PhantomModule.PH_WS_N
                        - PhantomModule.PH_WS_RAND) + "+随机" + PhantomModule.PH_WS_RAND));

        System.out.println();
    }

    private static DoubleSupplier lcg(long seed) {
        final long[] s = {seed};
        return () -> {
            s[0] = (s[0] * 48271L) % 2147483647L;
            return s[0] / 2147483647.0;
        };
    }

    /** 坐标比较：cos/sin 可能差 1 ulp（规范不要求正确舍入），故用 1e-9 绝对容差。 */
    private static boolean eq(double a, String jsText) {
        double b;
        try {
            b = Double.parseDouble(jsText);
        } catch (RuntimeException e) {
            return false;
        }
        return Math.abs(a - b) <= 1e-9;
    }

    // ==================================================================
    // JS 求值
    // ==================================================================

    private static Context newCtx() {
        return Context.newBuilder("js").allowAllAccess(true)
                .allowHostClassLookup(s -> true).build();
    }

    private static String[] evalStrings(String code) {
        Value v;
        try (Context ctx = newCtx()) {
            v = ctx.eval(Source.newBuilder("js", code, "probe").build());
            if (v == null || v.isNull() || !v.hasArrayElements()) {
                throw new IllegalStateException("探针 JS 片段没有返回数组（completion value 丢失）："
                        + code.substring(Math.max(0, code.length() - 120)));
            }
            int n = (int) v.getArraySize();
            String[] out = new String[n];
            for (int i = 0; i < n; i++) {
                out[i] = v.getArrayElement(i).asString();
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException("探针 JS 片段求值失败：" + e, e);
        }
    }

    private static double[] jsAim(double[] c) {
        String[] r = evalStrings(
                "function aim(ex,ey,ez,tx,ty,tz) {\n"
                        + "  var dx = tx - ex, dy = ty - ey, dz = tz - ez;\n"
                        + "  var n = Math.sqrt(dx*dx + dy*dy + dz*dz);\n"
                        + "  if (n < 1.0e-4) return null;\n"
                        + "  return [dx/n, dy/n, dz/n];\n"
                        + "}\n"
                        + "var r = aim(" + d(c[0]) + "," + d(c[1]) + "," + d(c[2]) + ","
                        + d(c[3]) + "," + d(c[4]) + "," + d(c[5]) + ");\n"
                        + "r === null ? ['NULL'] : ['' + r[0], '' + r[1], '' + r[2]];");
        if (r.length == 1 && "NULL".equals(r[0])) {
            return null;
        }
        return new double[]{Double.parseDouble(r[0]), Double.parseDouble(r[1]),
                Double.parseDouble(r[2])};
    }

    private static double[] jsSteer(double vx, double vy, double vz,
                                    double sx, double sy, double sz,
                                    double tx, double ty, double tz) {
        String[] r = evalStrings(
                "var PH_WS_SPEED = " + d(PhantomModule.PH_WS_SPEED) + ";\n"
                        + "var PH_WS_TURN = " + d(PhantomModule.PH_WS_TURN) + ";\n"
                        // ---- gs_boss_phantom.js:1174-1197 逐字（去掉 setDirection/setVelocity） ----
                        + "function steer(vx,vy,vz,sx,sy,sz,tx,ty,tz) {\n"
                        + "  var dx = vx, dy = vy, dz = vz;\n"
                        + "  var sp = Math.sqrt(dx * dx + dy * dy + dz * dz);\n"
                        + "  if (sp < 0.05) { sp = PH_WS_SPEED; dx = 0; dy = 0; dz = 1; }\n"
                        + "  dx /= sp; dy /= sp; dz /= sp;\n"
                        + "  var wx = tx - sx;\n"
                        + "  var wy = (ty + 1.0) - sy;\n"
                        + "  var wz = tz - sz;\n"
                        + "  var wn = Math.sqrt(wx * wx + wy * wy + wz * wz);\n"
                        + "  if (wn > 1.0e-4) {\n"
                        + "    wx /= wn; wy /= wn; wz /= wn;\n"
                        + "    var k = PH_WS_TURN;\n"
                        + "    var nx = dx * (1.0 - k) + wx * k;\n"
                        + "    var ny = dy * (1.0 - k) + wy * k;\n"
                        + "    var nz = dz * (1.0 - k) + wz * k;\n"
                        + "    var nn = Math.sqrt(nx * nx + ny * ny + nz * nz);\n"
                        + "    if (nn > 1.0e-4) {\n"
                        + "      nx /= nn; ny /= nn; nz /= nn;\n"
                        + "      return [nx * PH_WS_SPEED, ny * PH_WS_SPEED, nz * PH_WS_SPEED];\n"
                        + "    }\n"
                        + "  }\n"
                        + "  return null;\n"
                        + "}\n"
                        + "var r = steer(" + d(vx) + "," + d(vy) + "," + d(vz) + ","
                        + d(sx) + "," + d(sy) + "," + d(sz) + ","
                        + d(tx) + "," + d(ty) + "," + d(tz) + ");\n"
                        + "r === null ? ['NULL'] : ['' + r[0], '' + r[1], '' + r[2]];");
        if (r.length == 1 && "NULL".equals(r[0])) {
            return null;
        }
        return new double[]{Double.parseDouble(r[0]), Double.parseDouble(r[1]),
                Double.parseDouble(r[2])};
    }

    private static List<Double> dhCases() {
        List<Double> out = new ArrayList<>();
        for (double dh = 0.0; dh <= 30.0; dh += 0.25) {
            out.add(dh);
        }
        out.add(50.0);
        out.add(100.0);
        out.add(200.0);
        out.add(1000.0);
        out.add(1e6);
        return out;
    }

    // ==================================================================
    // 工具
    // ==================================================================

    private static String readModuleSource() {
        try {
            Path p = Paths.get(MODULE_SRC);
            if (!Files.isRegularFile(p)) {
                return null;
            }
            return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 读模块源码并<b>剥掉注释与字符串里的注释符干扰</b>。
     *
     * <p>★ 为什么必须剥：类注释里<b>故意</b>写了 {@code ATTACK_DAMAGE} / {@code phSetDmg} /
     * {@code JavaAdapter} / {@code PH_PET_GRIEF} 这些名字（记录"为什么不移植"），
     * 直接扫全文会让「这些死代码没被移植」的反向断言全部误报。
     *
     * <p>★ 剥离器认得字符串字面量与转义，所以 {@code "//"} 之类不会被误当注释。
     */
    private static String readModuleCode() {
        String s = readModuleSource();
        if (s == null) {
            return null;
        }
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        boolean inStr = false;
        boolean inChar = false;
        boolean inLine = false;
        boolean inBlock = false;
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            char nx = (i + 1 < n) ? s.charAt(i + 1) : '\0';
            if (inLine) {
                if (c == '\n') {
                    inLine = false;
                    out.append(c);
                }
                continue;
            }
            if (inBlock) {
                if (c == '*' && nx == '/') {
                    inBlock = false;
                    i++;
                } else if (c == '\n') {
                    out.append(c);       // 保留行号
                }
                continue;
            }
            if (inStr) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < n) {
                        out.append(s.charAt(++i));
                    }
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (inChar) {
                out.append(c);
                if (c == '\\') {
                    if (i + 1 < n) {
                        out.append(s.charAt(++i));
                    }
                } else if (c == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (c == '/' && nx == '/') {
                inLine = true;
                i++;
                continue;
            }
            if (c == '/' && nx == '*') {
                inBlock = true;
                i++;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '\'') {
                inChar = true;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static List<Field> allFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        Class<?> k = c;
        while (k != null && k != Object.class) {
            out.addAll(Arrays.asList(k.getDeclaredFields()));
            k = k.getSuperclass();
        }
        return out;
    }

    private static boolean hasField(Class<?> c, String name) {
        for (Field f : allFields(c)) {
            if (f.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** {@code double} → JS 数字字面量（保证往返精确）。 */
    private static String d(double v) {
        if (Double.isNaN(v)) {
            return "NaN";
        }
        if (Double.isInfinite(v)) {
            return v > 0 ? "Infinity" : "-Infinity";
        }
        return Double.toString(v);
    }

    private static String numArr(List<Double> vals) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(d(vals.get(i)));
        }
        return sb.append(']').toString();
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
        if (v.fitsInLong()) {
            return v.asLong();
        }
        if (v.isNumber()) {
            return v.asDouble();
        }
        return v.toString();
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof Boolean || b instanceof Boolean) {
            return a.equals(b);
        }
        if (a instanceof String || b instanceof String) {
            return a.equals(b);
        }
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            if (Double.compare(da, db) != 0) {
                return false;
            }
            if (a instanceof Long && b instanceof Long) {
                return ((Long) a).longValue() == ((Long) b).longValue();
            }
            return true;
        }
        return a.equals(b);
    }

    private static String show(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            StringBuilder sb = new StringBuilder("\"");
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x20 || c > 0x7E) {
                    sb.append(String.format(Locale.ROOT, "\\u%04X", (int) c));
                } else {
                    sb.append(c);
                }
            }
            return sb.append('"').toString();
        }
        if (o instanceof Double dv && dv == Math.rint(dv) && !Double.isInfinite(dv)) {
            return String.valueOf(dv);
        }
        return String.valueOf(o);
    }

    private static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getSimpleName();
    }

    private static int countOf(String s, String sub) {
        int n = 0;
        int i = 0;
        while ((i = s.indexOf(sub, i)) >= 0) {
            n++;
            i += sub.length();
        }
        return n;
    }

    /** 逐码点报差异 —— 中文/全角字符肉眼难分，只有码点能定位。 */
    private static String diff(String a, String b) {
        int n = Math.min(a.length(), b.length());
        for (int i = 0; i < n; i++) {
            if (a.charAt(i) != b.charAt(i)) {
                return "首个差异 @ " + i + " Java=U+"
                        + String.format(Locale.ROOT, "%04X", (int) a.charAt(i))
                        + " 参考=U+"
                        + String.format(Locale.ROOT, "%04X", (int) b.charAt(i));
            }
        }
        if (a.length() != b.length()) {
            return "长度不同：Java " + a.length() + " 参考 " + b.length()
                    + "（前 " + n + " 个字符相同）";
        }
        return "无差异";
    }

    private interface Bool {
        boolean get();
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

    private static void check(String label, Bool b) {
        boolean ok;
        try {
            ok = b.get();
        } catch (Throwable t) {
            System.out.println("      异常 " + label + " : " + t);
            ok = false;
        }
        check(label, ok);
    }
}
