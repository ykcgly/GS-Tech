package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 附属内容（yml / 脚本 / 存档物品）的磁盘布局与读取。
 *
 * <p>布局照抄 RSC 的「附属文件夹」形态，只是根目录换成插件自己的数据目录：
 * <pre>
 * plugins/GS/
 *   config.yml                 ← 由 content/default_config.yml 首次生成，之后只补缺失键
 *   content/
 *     info.yml  items.yml  groups.yml  menus.yml  recipe_types.yml …
 *     saveditems/*.yml
 *     scripts/*.js
 * </pre>
 *
 * <p><b>首次启动会把 jar 内的 {@code content/**} 解包到磁盘</b>，且<b>只补不缺</b>：
 * 磁盘上已存在的文件一律不覆盖 ⇒ 用户可以随意改 yml / 脚本，插件升级也不会冲掉改动。
 * 这与 RSC 把附属放在 {@code addons/} 下让用户直接编辑的体验一致。
 */
public final class Content {

    private static File root;

    private Content() {
    }

    /** 插件数据目录（plugins/GS）。 */
    public static File root() {
        if (root == null) {
            root = GS.plugin().getDataFolder();
        }
        return root;
    }

    public static File dir() {
        return new File(root(), "content");
    }

    public static File savedItemsDir() {
        return new File(dir(), "saveditems");
    }

    /** 插件级 config.yml（由 default_config.yml 派生）。 */
    public static File configFile() {
        return new File(root(), "config.yml");
    }

    /** 已加载的插件配置（首次访问时生成/补全）。脚本的 {@code getAddonConfig()} 用它。 */
    private static YamlConfiguration CONFIG;

    public static YamlConfiguration config() {
        if (CONFIG == null) {
            CONFIG = loadConfig();
        }
        return CONFIG;
    }

    /** 丢弃缓存的配置（重载用）。 */
    public static void reloadConfig() {
        CONFIG = null;
    }

    // ------------------------------------------------------------ 解包

    /**
     * 把 jar 内 {@code content/**} 解包到 {@code plugins/GS/content/}。已存在的不动。
     *
     * <p>★ 阶段②之前这里还会把 {@code lib/**}（GraalJS 那 15 个 jar，70.8 MB）
     * 解包到 {@code plugins/GS/lib/}。18 个脚本全部 Java 化之后那条路整体删除了 ——
     * 成品不再内嵌也不再解包任何 JS 运行库。
     *
     * <p>⚠️ <b>升级须知</b>：老版本的 {@code plugins/GS/lib/} 目录<b>不会</b>被自动清理
     * （本方法只补不缺，从不删用户目录里的东西）。升级后可以手动删掉它，
     * 留着不影响运行，只是白占 70.8 MB。
     *
     * @return 本次新解包的文件数
     */
    public static int bootstrap() throws IOException {
        Files.createDirectories(dir().toPath());
        Files.createDirectories(savedItemsDir().toPath());

        return unpack("content", dir().toPath());
    }

    /** 把 jar 内某个前缀下的全部文件解包到 dest，<b>只补不缺</b>。 */
    private static int unpack(String prefix, Path dest) throws IOException {
        int written = 0;
        for (String name : listBundled(prefix)) {
            String rel = name.substring(prefix.length() + 1);
            if (rel.isEmpty()) {
                continue;
            }
            Path target = dest.resolve(rel);
            if (Files.exists(target)) {
                continue;   // ★ 只补不缺 —— 绝不覆盖用户的改动
            }
            Files.createDirectories(target.getParent());
            try (InputStream in = GS.plugin().getResource(name)) {
                if (in == null) {
                    continue;
                }
                Files.copy(in, target);
                written++;
            }
        }
        return written;
    }

    // ★ 阶段②删除记录：这里原有 `libDir()`（plugins/GS/lib，GraalJS 运行库目录）。
    //   18 个脚本全部 Java 化后不再解包任何 JS 运行库 ⇒ 已删除。
    //   ⚠️ 老版本升级上来的服务器，plugins/GS/lib/ 会残留（本插件只补不缺、从不删用户目录），
    //      手动删掉即可，留着不影响运行。

    /**
     * 列出 jar 内某个前缀下的全部资源名。
     *
     * <p>用 {@code JarFile} 遍历而不是 {@code getResource} —— 后者在打包后只能按名字取，
     * 拿不到目录列表。若插件不是从 jar 跑（开发期直接跑 classes 目录），
     * 退化为扫磁盘目录。
     */
    public static List<String> listBundled(String prefix) {
        List<String> out = new ArrayList<>();
        String norm = prefix.endsWith("/") ? prefix : prefix + "/";
        try {
            java.net.URL url = GS.plugin().getClass().getProtectionDomain().getCodeSource().getLocation();
            File self = new File(url.toURI());
            if (self.isFile()) {
                try (java.util.jar.JarFile jar = new java.util.jar.JarFile(self)) {
                    java.util.Enumeration<java.util.jar.JarEntry> en = jar.entries();
                    while (en.hasMoreElements()) {
                        String n = en.nextElement().getName();
                        if (n.startsWith(norm) && !n.endsWith("/")) {
                            out.add(n);
                        }
                    }
                }
            } else {
                // 开发期：classes 目录
                File d = new File(self, norm);
                if (d.isDirectory()) {
                    collect(d, norm, out);
                }
            }
        } catch (Exception e) {
            GS.warn("列出内置资源失败 (%s): %s", prefix, e);
        }
        Collections.sort(out);
        return out;
    }

    private static void collect(File d, String prefix, List<String> out) {
        File[] fs = d.listFiles();
        if (fs == null) {
            return;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                collect(f, prefix + f.getName() + "/", out);
            } else {
                out.add(prefix + f.getName());
            }
        }
    }

    // ------------------------------------------------------------ yml 读取

    /**
     * 读取 content 下的一个 yml。
     *
     * <p>文件不存在 ⇒ 返回空的 YamlConfiguration（<b>不报错、不创建文件</b>）——
     * 与 RSC 的 {@code readYml} 一致：附属可以只放它需要的几个 yml。
     */
    public static YamlConfiguration yml(String fileName) {
        File f = new File(dir(), fileName);
        if (!f.isFile()) {
            return new YamlConfiguration();
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    /** 读 saveditems 下的存档物品 yml；不存在返回 null。 */
    public static YamlConfiguration savedItem(String stem) {
        // ★ material 原样当文件名用 —— 大小写敏感、不做任何规范化（与 RSC 一致）
        File f = new File(savedItemsDir(), stem + ".yml");
        if (!f.isFile()) {
            return null;
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    // ------------------------------------------------------------ 插件配置

    /**
     * 生成/补全插件 config.yml：以 {@code content/default_config.yml} 为默认值。
     *
     * <p>语义与 RSC 的 {@code AddonConfig} 一致：<b>默认值永不覆盖用户已写的值</b>，
     * 只把缺失的键补上（列表键：默认里有、用户文件里没有 ⇒ 补）。
     */
    public static YamlConfiguration loadConfig() {
        YamlConfiguration defaults = yml("default_config.yml");
        File target = configFile();
        YamlConfiguration user = target.isFile()
                ? YamlConfiguration.loadConfiguration(target)
                : new YamlConfiguration();

        if (defaults.getKeys(false).isEmpty()) {
            // default_config.yml 缺失或没有顶层键 ⇒ 不做任何事（与 RSC 一致）
            return user;
        }

        boolean changed = complete(user, defaults);
        if (changed || !target.isFile()) {
            try {
                user.save(target);
            } catch (IOException e) {
                GS.error(e, "无法写入 config.yml");
            }
        }
        return user;
    }

    /** 把 defaults 里有、target 里没有的键补进 target。返回是否真的改动了。 */
    private static boolean complete(ConfigurationSection target, ConfigurationSection defaults) {
        boolean changed = false;
        for (String k : defaults.getKeys(false)) {
            Object dv = defaults.get(k);
            if (dv instanceof ConfigurationSection ds) {
                ConfigurationSection ts = target.getConfigurationSection(k);
                if (ts == null) {
                    target.createSection(k);
                    ts = target.getConfigurationSection(k);
                    if (ts == null) {
                        continue;
                    }
                    changed = true;
                }
                changed |= complete(ts, ds);
            } else if (!target.contains(k)) {
                target.set(k, dv);
                changed = true;
            }
        }
        return changed;
    }

    // ------------------------------------------------------------ 脚本
    //
    // ★ 阶段②删除记录：这里原有三个方法，都是为「读 .js 脚本原文」服务的：
    //
    //   · `scriptsDir()`   → plugins/GS/content/scripts/
    //   · `script(name)`   → 定位一个脚本文件，带路径穿越守卫
    //                        （与原版 ScriptEval.getScriptOrNull 一致：
    //                         `script: ../../evil` 会被拒绝并告警）
    //   · `readText(file)` → 按 UTF-8 读脚本源码
    //
    // 18 个脚本全部 Java 化之后，`content/scripts/` 连同这 18 个 .js 一起删掉了，
    // 三个方法再无调用点 ⇒ 一并删除（本项目约定：死代码不留，但在注释里记明）。
    //
    // ⚠️ 若日后要重新支持「外部脚本」，路径穿越守卫是必须照抄回来的 ——
    //    它防的是 yml 里 `script: ../../evil` 这类值真去读插件目录外的文件。

    /** 从 jar 内读一个文本资源（备用）。 */
    public static String readBundledText(String name) throws IOException {
        try (InputStream in = GS.plugin().getResource(name)) {
            if (in == null) {
                return null;
            }
            try (Reader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = r.read(buf)) > 0) {
                    sb.append(buf, 0, n);
                }
                return sb.toString();
            }
        }
    }
}
