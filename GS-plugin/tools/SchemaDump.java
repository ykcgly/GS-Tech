import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 用**真实的 Bukkit YAML 解析器**（与插件运行时同一个）导出 content 下所有 yml 的键结构。
 *
 * <p>这是最权威的 schema 来源：不靠猜、不靠正则，直接看解析器读出来什么。
 * 顺带验证 content 能被真实解析器读通（有异常会立刻暴露）。
 *
 * <p>不是插件的一部分，只放在 tools/ 里手工跑。
 */
public final class SchemaDump {

    /** 输出走文件而不是 stdout —— 控制台代码页（GBK）会把中文写坏。 */
    private static java.io.PrintWriter out;

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "content");
        File outFile = new File(args.length > 1 ? args[1] : "build/schema.txt");
        out = new java.io.PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(outFile), java.nio.charset.StandardCharsets.UTF_8));

        out.println("############ 顶层文件 ############");
        File[] files = root.listFiles();
        if (files == null) {
            out.println("目录不存在: " + root.getAbsolutePath());
            return;
        }
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile() || !f.getName().endsWith(".yml")) {
                continue;
            }
            dumpFile(f, f.getName());
        }

        File si = new File(root, "saveditems");
        if (si.isDirectory()) {
            out.println();
            out.println("############ saveditems ############");
            File[] sfs = si.listFiles();
            java.util.Arrays.sort(sfs);
            for (File f : sfs) {
                if (f.isFile() && f.getName().endsWith(".yml")) {
                    dumpFile(f, "saveditems/" + f.getName());
                }
            }
        }
        out.flush();
        out.close();
        out.println("OK -> " + outFile.getAbsolutePath());
    }

    /** 打印一个文件的顶层键，以及每个顶层条目下的二级键（含出现次数）。 */
    private static void dumpFile(File f, String label) {
        out.println();
        out.println("========== " + label + "  (" + f.length() + " B) ==========");
        YamlConfiguration cfg;
        try {
            cfg = YamlConfiguration.loadConfiguration(f);
        } catch (Throwable t) {
            out.println("  !! 解析失败: " + t);
            return;
        }

        // 顶层键 -> 该条目下二级键的计数
        Map<String, Map<String, Integer>> perEntry = new LinkedHashMap<>();
        for (String top : cfg.getKeys(false)) {
            ConfigurationSection sec = cfg.getConfigurationSection(top);
            if (sec == null) {
                out.printf("  %-34s = %s%n", top, brief(cfg.get(top)));
                continue;
            }
            Map<String, Integer> sub = new TreeMap<>();
            for (String k : sec.getKeys(false)) {
                sub.merge(k, 1, Integer::sum);
            }
            perEntry.put(top, sub);
        }

        if (perEntry.isEmpty()) {
            return;
        }
        out.printf("  顶层条目 %d 个：%s%n", perEntry.size(), perEntry.keySet());

        // 汇总：二级键 -> 出现次数
        Map<String, Integer> agg = new TreeMap<>();
        for (Map<String, Integer> sub : perEntry.values()) {
            sub.forEach((k, v) -> agg.merge(k, v, Integer::sum));
        }
        out.println("  --- 二级键汇总（键 × 出现条目数）---");
        agg.forEach((k, v) -> out.printf("      %-32s ×%d%n", k, v));
    }

    private static String brief(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof List<?> l) {
            return "list[" + l.size() + "]";
        }
        String s = String.valueOf(o);
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }
}
