package com.qinghuacisj.gs.script;

import com.qinghuacisj.gs.script.module.BadAppleModule;
import com.qinghuacisj.gs.script.module.BorrowLifeModule;
import com.qinghuacisj.gs.script.module.CursedModule;
import com.qinghuacisj.gs.script.module.DingMeModule;
import com.qinghuacisj.gs.script.module.FakeWeisiModule;
import com.qinghuacisj.gs.script.module.GsModule;
import com.qinghuacisj.gs.script.module.GuideBookModule;
import com.qinghuacisj.gs.script.module.InfoCreatorModule;
import com.qinghuacisj.gs.script.module.ListenerModule;
import com.qinghuacisj.gs.script.module.MaiyouWineModule;
import com.qinghuacisj.gs.script.module.NightwatchModule;
import com.qinghuacisj.gs.script.module.PhantomModule;
import com.qinghuacisj.gs.script.module.ReleaseDreamModule;
import com.qinghuacisj.gs.script.module.RiderModule;
import com.qinghuacisj.gs.script.module.RollingLogIngotModule;
import com.qinghuacisj.gs.script.module.ShadowModule;
import com.qinghuacisj.gs.script.module.SongModule;
import com.qinghuacisj.gs.script.module.ThreeModule;
import com.qinghuacisj.gs.script.module.UpdateLogModule;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 脚本名 → Java 模块的登记表。
 *
 * <p><b>★ 阶段②已完成：18 个脚本全部登记在这里，{@link Script#of} 只走这条路。</b>
 * 迁移中途它曾经是「哪些脚本已经不用 GraalJS 了」的台账，查不到就回落到内嵌的
 * GraalJS 宿主；那条回落路（{@code Scripts} / {@code host.GraalScriptHost} /
 * {@code libs/graal} 的 15 个 jar）已随阶段②一并删除。
 *
 * <p>★★ <b>缓存语义必须与原版逐字一致</b>：原版 {@code ScriptEval.getScriptOrNull}
 * 是 {@code SCRIPT_CACHE.computeIfAbsent(file, f -> JavaScriptEval.create(f, addon))}，
 * 而 {@code JavaScriptEval} 的<b>构造器里就求值了脚本</b>。于是：
 * <ul>
 *   <li><b>首次取用即求值</b> —— 脚本顶层代码（注册监听器、启动每刻脉冲）在那一刻就跑。</li>
 *   <li><b>同路径只求值一次</b> —— 两个条目引用同一个 {@code script:} 时共享同一份全局作用域。</li>
 * </ul>
 * 本类用 {@link #INSTANCES} 的 {@code computeIfAbsent} 一一对应：模块的
 * <b>构造器 = 原脚本的顶层代码</b>，模块的<b>成员字段 = 原脚本的顶层变量</b>。
 */
public final class Modules {

    /** 脚本名（不含 .js）→ 工厂。 */
    private static final Map<String, Supplier<GsModule>> FACTORY = new LinkedHashMap<>();

    /** 脚本名 → 已创建的实例（等价于原版的 SCRIPT_CACHE）。 */
    private static final Map<String, GsModule> INSTANCES = new ConcurrentHashMap<>();

    static {
        // ★ 每 Java 化一个脚本，就在这里加一行。
        //   保持按「脚本名」注册，名字与 yml 里 script: 写的完全一致。
        register("gs_info_creator", InfoCreatorModule::new);
        register("gs_update_log", UpdateLogModule::new);
        register("gs_rolling_log_ingot", RollingLogIngotModule::new);
        register("gs_guide_book", GuideBookModule::new);
        register("gs_ding_me", DingMeModule::new);
        register("gs_song", SongModule::new);
        register("gs_release_dream", ReleaseDreamModule::new);
        register("gs_fake_weisi", FakeWeisiModule::new);
        register("gs_maiyou_wine", MaiyouWineModule::new);
        register("gs_borrow_life", BorrowLifeModule::new);
        register("gs_cursed", CursedModule::new);
        register("gs_bad_apple", BadAppleModule::new);
        register("gs_listener", ListenerModule::new);
        register("gs_boss_phantom", PhantomModule::new);
        register("gs_boss_shadow", ShadowModule::new);
        register("gs_boss_three", ThreeModule::new);
        register("gs_boss_rider", RiderModule::new);
        register("gs_boss_nightwatch", NightwatchModule::new);
    }

    private Modules() {
    }

    /** 登记一个已 Java 化的脚本模块。 */
    public static void register(String scriptName, Supplier<GsModule> factory) {
        FACTORY.put(norm(scriptName), factory);
    }

    /** 该脚本是否有对应的 Java 模块（阶段②之后 18 个脚本恒为 true）。 */
    public static boolean has(String scriptName) {
        return FACTORY.containsKey(norm(scriptName));
    }

    /**
     * 取模块实例，<b>首次取用时创建</b>（构造器即原脚本的顶层代码）。
     *
     * @return 已登记时返回实例；未登记时返回 null（yml 里脚本名拼错的情形）
     */
    public static GsModule acquire(String scriptName) {
        String key = norm(scriptName);
        Supplier<GsModule> f = FACTORY.get(key);
        if (f == null) {
            return null;
        }
        return INSTANCES.computeIfAbsent(key, k -> {
            GsModule m = f.get();
            if (m == null) {
                throw new IllegalStateException("模块工厂返回 null: " + k);
            }
            return m;
        });
    }

    /** 已 Java 化的脚本数（启动日志用）。 */
    public static int convertedCount() {
        return FACTORY.size();
    }

    /** 已实例化的模块数（自检用：只有被 yml 真正引用到的脚本才会被实例化）。 */
    public static int instantiatedCount() {
        return INSTANCES.size();
    }

    /** 清空实例缓存（关服 / 重载用）。 */
    public static void clear() {
        INSTANCES.clear();
    }

    /** 去掉 {@code .js} 后缀，与 {@code Script.of} 的入参形态对齐。 */
    private static String norm(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        return n.endsWith(".js") ? n.substring(0, n.length() - 3) : n;
    }
}
