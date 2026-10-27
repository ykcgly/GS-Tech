package com.qinghuacisj.gs.script;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.script.module.GsModule;

/**
 * 一个脚本的句柄 —— <b>阶段②完成后的纯 Java 实现</b>。
 *
 * <p>★★ <b>这里原来是一条「双路门面」</b>：同一个脚本名，可能走 {@link Modules} 里
 * 已 Java 化的模块，也可能回落到内嵌的 GraalJS 宿主（{@code Scripts} /
 * {@code host.GraalScriptHost}）。<b>现在 18 个脚本全部 Java 化了</b>，
 * 那条回落路已经整体删除，包括：
 * <ul>
 *   <li>{@code script/Scripts.java}（反射 + 子类加载器装载 Graal 宿主）</li>
 *   <li>{@code script/ScriptHost.java}、{@code script/ScriptFn.java}（宿主接口与函数式签名）</li>
 *   <li>{@code script/host/GraalScriptHost.java}（332 行的 Graal 绑定层）</li>
 *   <li>{@code content/scripts/*.js}（18 个脚本原文，约 36,000 行）</li>
 *   <li>{@code libs/graal/}（15 个 GraalJS 运行库 jar，70.8 MB）</li>
 * </ul>
 * ⇒ 成品 jar 从 <b>34.8 MB 降到约 4 MB</b>，且<b>启动不再需要初始化 JS 引擎</b>。
 *
 * <p>★ <b>为什么当初要那样分两层</b>（留个记录，免得日后有人以为是过度设计）：
 * {@code Script} 刻意<b>不链接 Graal 的任何类</b>，真正的 Graal 实现放在
 * {@code host} 子包里、由子类加载器加载。这样即使 {@code plugins/GS/lib/} 下的
 * 运行库缺失，物品 / 机器 / 配方也照常加载，只有尚未 Java 化的脚本失效并打一条告警
 * —— 而不是 {@code NoClassDefFoundError} 把整个 {@code onEnable} 炸掉。
 * 那是「迁移中途」才需要的容错；迁移完成后它只是纯粹的体积与复杂度负担。
 *
 * <p>用法与原版保持一致：
 * <pre>
 *   Script s = Script.of("gs_bad_apple");   // 找不到脚本返回 null
 *   if (s != null) { s.call("init"); }
 * </pre>
 */
public final class Script {

    private final String name;
    private final GsModule module;

    private Script(String name, GsModule module) {
        this.name = name;
        this.module = module;
    }

    /**
     * 取一个脚本句柄；脚本不存在时返回 null（与原版一致：调用方负责判空）。
     *
     * <p>★★ <b>取到的同时就「求值」。</b> 这不是随手优化，而是语义要求 ——
     * 原版 {@code ScriptEval.getScriptOrNull} 走 {@code JavaScriptEval.create}，
     * 而那个类的<b>构造器里就调用了 {@code contextInit()} 完成求值</b>。
     * 脚本的顶层代码是有副作用的：{@code gs_listener.js} 的顶层会注册 38 个事件
     * 监听器并启动 3 个每刻脉冲（{@code gsBbPulse} / {@code gsBgPulse} / {@code gsKlPulse}）。
     * 若改成懒加载，这些要等到第一个玩家事件才生效，篮球引擎与 Boss 逻辑会静默延迟启动。
     *
     * <p>Java 路用 {@link Modules#acquire} 立刻构造模块 ——
     * <b>模块的构造器 = 原脚本的顶层代码</b>，并用 {@code computeIfAbsent} 缓存。
     *
     * <p>★ <b>关于「只被 {@code groups.yml} 的 {@code actions: - "script X"} 引用」
     * 的那几个脚本</b>（{@code gs_guide_book} / {@code gs_info_creator} /
     * {@code gs_update_log}）：它们<b>保持惰性</b>，直到玩家第一次点那个按钮才实例化。
     * 这与 RSC 一致 —— 证据是参考服日志里 {@code gs_guide_book 已加载（19 页）}
     * 出现在<b>最后一行</b>（在「加载成功」之后），而同为动作引用的
     * {@code gs_update_log} 一次都没出现 ⇒ RSC 也是按需求值。
     * （曾加过一版「全部预实例化」，被 {@code tools/log_parity.py} 当场否掉。）
     */
    public static Script of(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.trim();
        if (!Modules.has(trimmed)) {
            // 阶段②之后不该发生：18 个脚本都已 Java 化。
            // 保留判空而不是抛异常 —— 与原版「找不到脚本返回 null」的约定一致，
            // 而且 yml 里若出现拼错的脚本名，也只该警告不该崩。
            GS.warn("脚本 %s 没有对应的 Java 模块（yml 里的脚本名拼错了？）", trimmed);
            return null;
        }
        return new Script(trimmed, Modules.acquire(trimmed));
    }

    /**
     * 调用脚本里的一个顶层函数。返回值调用方一律忽略（与原版一致）。
     *
     * <p>★ 异常语义对齐原版 {@code evalFunction}：<b>吞掉异常并记日志</b>，
     * 绝不冒泡到 Bukkit 事件总线。
     */
    public Object call(String fnName, Object... args) {
        try {
            return module.call(fnName, args);
        } catch (Throwable t) {
            GS.error(t, "运行脚本 %s 的 %s 时出错", name, fnName);
            return null;
        }
    }

    /** 该脚本是否已经 Java 化（自检 / 日志用）。阶段②之后恒为 true。 */
    public boolean isNative() {
        return module != null;
    }

    public String name() {
        return name;
    }

    /** 清空模块实例（重载 / 关服用）。 */
    public static void clearCache() {
        Modules.clear();
    }
}
