package com.qinghuacisj.gs.script.module;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置对账 —— 把<b>真 JS</b> 的 {@code rdConfig()} 跑起来，
 * 与 Java 的 {@code readCfg(section)} 在<b>同一份真配置</b>下逐字段比对。
 *
 * <p><b>为什么这是最关键的一条验证</b>：{@code gs_release_dream} 的伤害、射程、
 * 音高、下坠次数<b>全部来自配置</b>，而配置读取里有一堆钳制
 * （{@code step < 0.1}、{@code hits} 夹到 1..10、{@code soundPitch} 夹到 0.5..2.0、
 * 心跳间隔 {@code * 10}、新键读不到回退旧键…）。
 * 这些钳制写错一个，线上伤害就变了 —— 而且改了配置才看得出来，很难发现。
 *
 * <p>做法：把真的 {@code default_config.yml} 解析成 {@link YamlConfiguration}，
 * 注入给真 JS 当 {@code getAddonConfig()}，让它算出 {@code def}；
 * 再把同一份 section 交给 Java 的 {@code readCfg}。两边逐字段比。
 *
 * <p>★ 还额外验证「section 不存在 ⇒ 用默认值」这条早退路径。
 */
public final class ConfigParityProbe {

    private static int pass = 0;
    private static int fail = 0;

    /** 要逐个比对的字段（顺序无关，仅为输出稳定）。 */
    private static final String[] FIELDS = {
            "range", "step", "hitRadius", "dropFrom", "hitInterval",
            "dps", "hits", "soundPitch", "soundVol",
            "screamSound", "screamDelay", "screamCount", "sounds"
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_release_dream：配置读取逐字段对账（真 JS vs Java）===");
        System.out.println();

        Path root = Path.of(args.length > 0 ? args[0]
                : "../QHCGS/GS/GS");
        Path cfgFile = root.resolve("default_config.yml");
        Path jsFile = root.resolve("scripts/gs_release_dream.js");
        for (Path p : new Path[]{cfgFile, jsFile}) {
            if (!Files.isRegularFile(p)) {
                System.out.println("!! 找不到 " + p.toAbsolutePath());
                System.exit(1);
            }
        }

        // 真配置
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString(new String(Files.readAllBytes(cfgFile), StandardCharsets.UTF_8));
        ConfigurationSection sec = yml.getConfigurationSection("release-dream");
        if (sec == null) {
            System.out.println("!! default_config.yml 里没有 release-dream 段");
            System.exit(1);
        }

        String source = new String(Files.readAllBytes(jsFile), StandardCharsets.UTF_8);

        System.out.println("[A] 真配置（release-dream 段存在）");
        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(s -> true)
                .build()) {
            // 注入 getAddonConfig() → 真配置
            ctx.getBindings("js").putMember("getAddonConfig",
                    (java.util.function.Supplier<YamlConfiguration>) () -> yml);
            ctx.eval(Source.newBuilder("js", source, "JavaScript").build());

            Value def = ctx.getBindings("js").getMember("rdConfig").execute();
            compare(def, sec);
        }

        System.out.println();
        System.out.println("[B] section 缺失 ⇒ 两边都必须用默认值");
        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.getBindings("js").putMember("getAddonConfig",
                    (java.util.function.Supplier<YamlConfiguration>) () -> {
                        YamlConfiguration empty = new YamlConfiguration();
                        return empty;   // 没有 release-dream 段
                    });
            ctx.eval(Source.newBuilder("js", source, "JavaScript").build());
            Value def = ctx.getBindings("js").getMember("rdConfig").execute();
            compare(def, null);
        }

        // ★★ 钳制才是真正危险的地方：正常配置里它们一个都不触发。
        //    用极端值把每一条钳制都踩一遍。
        System.out.println();
        System.out.println("[C] 极端值 —— 把每一条钳制都踩一遍");
        edgeCase(source, "step 太小（0.01）",
                "step: 0.01\n");
        edgeCase(source, "step 负数",
                "step: -5.0\n");
        edgeCase(source, "dropFrom 太小（0.2）",
                "drop-from: 0.2\n");
        edgeCase(source, "hitInterval 为 0",
                "hit-interval: 0\n");
        edgeCase(source, "hits 为 0（应夹到 1）",
                "damage-seconds: 0\n");
        edgeCase(source, "hits 为 99（应夹到 10）",
                "damage-seconds: 99\n");
        edgeCase(source, "soundPitch 为 0.1（应夹到 0.5）",
                "sound-pitch: 0.1\n");
        edgeCase(source, "soundPitch 为 5.0（应夹到 2.0）",
                "sound-pitch: 5.0\n");
        edgeCase(source, "soundVolume 为 -1.0（应夹到 0.0）",
                "sound-volume: -1.0\n");
        edgeCase(source, "heartbeat-count 为 99（应夹到 10）",
                "heartbeat-count: 99\n");
        edgeCase(source, "heartbeat-count 为 -5（应夹到 0）",
                "heartbeat-count: -5\n");
        edgeCase(source, "heartbeat-slime-ticks 为 0（*10 后应夹到 1）",
                "heartbeat-slime-ticks: 0\n");
        // ★ 旧键回退：只给 scream-* 不给 heartbeat-*
        edgeCase(source, "只有旧键 scream-sound / scream-count / scream-slime-ticks",
                "scream-sound: \"entity.warden.roar\"\n"
                        + "scream-count: 3\n"
                        + "scream-slime-ticks: 4\n");
        edgeCase(source, "sounds 为空列表（应保留默认）",
                "sounds: []\n");
        edgeCase(source, "sounds 有多条（应原样取用）",
                "sounds:\n  - \"block.note_block.guitar\"\n  - \"entity.warden.heartbeat\"\n");

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 造一份只含指定字段的 {@code release-dream} 段，两边各算一次再比。
     *
     * <p>★ 字段缺失时两边都必须落到默认值 —— 所以这里既踩了钳制，
     * 也顺带验证了「没写的键用默认」。
     */
    private static void edgeCase(String jsSource, String label, String sectionYaml) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        yml.loadFromString("release-dream:\n"
                + indent(sectionYaml));
        ConfigurationSection sec = yml.getConfigurationSection("release-dream");

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup(s -> true)
                .build()) {
            ctx.getBindings("js").putMember("getAddonConfig",
                    (java.util.function.Supplier<YamlConfiguration>) () -> yml);
            ctx.eval(Source.newBuilder("js", jsSource, "JavaScript").build());
            Value def = ctx.getBindings("js").getMember("rdConfig").execute();

            ReleaseDreamModule.Cfg javaCfg = ReleaseDreamModule.readCfg(sec);
            int before = fail;
            for (String name : FIELDS) {
                Field f = ReleaseDreamModule.Cfg.class.getDeclaredField(name);
                f.setAccessible(true);
                Object javaVal = f.get(javaCfg);
                Object jsVal = toJava(def.getMember(name));
                if (!same(jsVal, javaVal)) {
                    System.out.println("      不一致 " + name
                            + "  JS=" + show(jsVal) + "  Java=" + show(javaVal));
                    fail++;
                }
            }
            if (fail == before) {
                pass++;
                System.out.println("      OK   " + label
                        + "   → step=" + javaCfg.step + " hits=" + javaCfg.hits
                        + " pitch=" + javaCfg.soundPitch + " vol=" + javaCfg.soundVol
                        + " dropFrom=" + javaCfg.dropFrom
                        + " delay=" + javaCfg.screamDelay
                        + " count=" + javaCfg.screamCount
                        + " sound=" + javaCfg.screamSound
                        + " sounds=" + javaCfg.sounds);
            } else {
                System.out.println("      失败 " + label);
            }
        }
    }

    /** 把一段 yaml 缩进两格，好塞进 release-dream: 下面。 */
    private static String indent(String s) {
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            sb.append("  ").append(line).append('\n');
        }
        return sb.toString();
    }

    private static void compare(Value jsDef, ConfigurationSection sec) throws Exception {
        ReleaseDreamModule.Cfg javaCfg = ReleaseDreamModule.readCfg(sec);
        for (String name : FIELDS) {
            Field f = ReleaseDreamModule.Cfg.class.getDeclaredField(name);
            f.setAccessible(true);
            Object javaVal = f.get(javaCfg);
            Value jv = jsDef.getMember(name);
            Object jsVal = toJava(jv);
            boolean ok = same(jsVal, javaVal);
            if (!ok) {
                System.out.println("      不一致 " + name
                        + "  JS=" + show(jsVal) + "  Java=" + show(javaVal));
            }
            check(name + " = " + show(javaVal), ok);
        }
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
            List<String> out = new ArrayList<>();
            for (long i = 0; i < v.getArraySize(); i++) {
                out.add(v.getArrayElement(i).asString());
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

    @SuppressWarnings("unchecked")
    private static boolean same(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a;
            List<Object> lb = (List<Object>) b;
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!String.valueOf(la.get(i)).equals(String.valueOf(lb.get(i)))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        return a.equals(b);
    }

    private static String show(Object o) {
        if (o == null) {
            return "null";
        }
        if (o instanceof String s) {
            return "\"" + s + "\"";
        }
        return String.valueOf(o);
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
