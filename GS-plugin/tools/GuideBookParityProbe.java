package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图鉴页面逐字节对账 —— 把<b>真 JS</b> 里的 {@code GG_PAGES} 与
 * {@link GuideBookModule} 的 Java 数组逐页比对。
 *
 * <p><b>为什么必须机械比对</b>：19 页文案是<b>玩家可见内容</b>，最长一页 173 字、
 * 含生僻符号（第 14 页首行的 {@code &8䷍Ꝑ8kꙭ#䷎}）与大量标点。
 * 手抄一定会错，而错了在游戏里就是「书上少了一个字」这种极难发现的问题。
 * Java 侧的数组本身也是脚本从 JS 生成的，本探针是<b>第二道独立校验</b>：
 * 直接把真 JS 跑起来读出数组，逐元素 {@code equals}。
 *
 * <p>跑法：{@code java -cp <cp> com.qinghuacisj.gs.script.module.GuideBookParityProbe [js路径]}
 */
public final class GuideBookParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_guide_book：Java 页 vs 真 JS 页 逐字节对账 ===");
        System.out.println();

        Path js = Path.of(args.length > 0 ? args[0]
                : "../QHCGS/GS/GS/scripts/gs_guide_book.js");
        if (!Files.isRegularFile(js)) {
            System.out.println("!! 找不到脚本: " + js.toAbsolutePath());
            System.exit(1);
        }
        String source = new String(Files.readAllBytes(js), StandardCharsets.UTF_8);

        List<String> jsPages = new ArrayList<>();
        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.eval(Source.newBuilder("js", source, "JavaScript").build());
            Value pages = ctx.getBindings("js").getMember("GG_PAGES");
            if (pages == null || !pages.hasArrayElements()) {
                System.out.println("!! JS 侧没拿到 GG_PAGES");
                System.exit(1);
            }
            for (long i = 0; i < pages.getArraySize(); i++) {
                jsPages.add(pages.getArrayElement(i).asString());
            }
        }

        System.out.println("JS 侧  " + jsPages.size() + " 页");
        System.out.println("Java 侧 " + GuideBookModule.pageCount() + " 页");
        System.out.println();
        check("页数一致", jsPages.size() == GuideBookModule.pageCount());

        int bad = 0;
        int n = Math.min(jsPages.size(), GuideBookModule.pageCount());
        for (int i = 0; i < n; i++) {
            String a = jsPages.get(i);
            String b = GuideBookModule.page(i);
            if (!a.equals(b)) {
                bad++;
                System.out.println("      第 " + (i + 1) + " 页不一致");
                System.out.println("        JS   = " + escape(a));
                System.out.println("        Java = " + escape(b));
            }
        }
        check("全部 " + n + " 页逐字节一致", bad == 0);

        // 抽样打印，便于人工扫一眼
        System.out.println();
        System.out.println("      抽查（前 3 页 + 后 2 页）：");
        for (int i : new int[]{0, 1, 2, n - 2, n - 1}) {
            String p = GuideBookModule.page(i);
            System.out.println("        第 " + (i + 1) + " 页 [" + p.length() + " 字] "
                    + p.split("\n")[0]);
        }

        // 关键锚点：这几页是历史上反复改过的，专门断言
        System.out.println();
        check("第 1 页是祖宗发射器·维什戴尔（第八十八/八十九批）",
                GuideBookModule.page(0) != null
                        && GuideBookModule.page(0).startsWith("祖宗发射器·维什戴尔\n"));
        check("第 6 页是残樱（第六十四批修正 6-b）",
                GuideBookModule.page(5) != null
                        && GuideBookModule.page(5).startsWith("残樱\n"));
        check("第 14 页首行含生僻符号 &8䷍Ꝑ8kꙭ#䷎",
                GuideBookModule.page(13) != null
                        && GuideBookModule.page(13).startsWith("&8䷍Ꝑ8kꙭ#䷎\n"));
        check("第 16 页已改名此刻·万籁俱寂（第五十三批）",
                GuideBookModule.page(15) != null
                        && GuideBookModule.page(15).startsWith("此刻·万籁俱寂\n"));
        check("第 16 页第 2 行是「准星方向」（第八十四批）",
                GuideBookModule.page(15) != null
                        && GuideBookModule.page(15).contains("左键斩击准星方向3*5*2的区域"));
        check("第 18 页是暗影虫卵（第八十九批）",
                GuideBookModule.page(17) != null
                        && GuideBookModule.page(17).startsWith("暗影虫卵（阶段boss）\n"));
        check("第 19 页是守夜人的提灯（第八十九批）",
                GuideBookModule.page(18) != null
                        && GuideBookModule.page(18).startsWith("守夜人的提灯（阶段boss）\n"));
        check("第 18 页末行是阶段三「技能：」行（末尾两段已删）",
                GuideBookModule.page(17) != null
                        && GuideBookModule.page(17).endsWith("瞬移玩家到身前（每7秒）"));
        check("第 19 页末行是阶段二「技能：」行（末尾两段已删）",
                GuideBookModule.page(18) != null
                        && GuideBookModule.page(18).endsWith("夜深了（每90秒、996点伤害）"));
        check("第 5 页换弹已改成 F 键（第六十五批）",
                GuideBookModule.page(4) != null
                        && GuideBookModule.page(4).contains("F键进行换弹（1.5秒）"));
        check("第 6 页换弹已改成 F 键（第六十五批）",
                GuideBookModule.page(5) != null
                        && GuideBookModule.page(5).contains("F键进行换弹（1.8秒）"));

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static String escape(String s) {
        return s.replace("\n", "\\n");
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
}
