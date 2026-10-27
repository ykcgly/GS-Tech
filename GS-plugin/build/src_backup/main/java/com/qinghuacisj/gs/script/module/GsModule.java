package com.qinghuacisj.gs.script.module;

/**
 * 一个脚本模块（原 GraalJS 脚本的 Java 实现）。
 *
 * <p>阶段②的目标是把 18 个 GraalJS 脚本逐个改成 Java 类，为了做到
 * <b>每一步都能编译、能上服验证</b>，迁移是增量的：已经转好的脚本走本接口，
 * 没转的继续走 GraalJS。<b>现在 18 个全部转完了</b>，那条 GraalJS 回落路
 * （{@code script/Scripts.java}、{@code script/host/}、{@code libs/graal}）已删除，
 * 本接口成了唯一的实现方式。
 *
 * <p>★ <b>一个脚本 = 一个模块实例。</b> 原版 {@code ScriptEval.SCRIPT_CACHE}
 * 按规范化路径缓存 {@code JavaScriptEval}，所以两个条目引用同一个
 * {@code script:} 时<b>共享同一份全局作用域</b>（脚本里的顶层 {@code var}
 * 因此是共享的）。{@link com.qinghuacisj.gs.script.Modules} 用同样的方式缓存实例，
 * 于是模块的<b>成员字段就等价于原脚本的顶层变量</b>，语义逐字对应。
 *
 * <p>★ 命名：{@link #call} 的函数名一律用原脚本里的原名（{@code onUse}、
 * {@code onButtonGroupClick} …），不做任何重命名 —— 这样出问题时能直接对着
 * JS 源码排查。
 */
public interface GsModule {

    /** 脚本名（不含 {@code .js}），与 yml 里 {@code script:} 写的一致。 */
    String name();

    /**
     * 按原脚本的函数名调用。
     *
     * <p>返回值调用方一律忽略（与原版 {@code evalFunction} 一致）；
     * 函数不存在时返回 null —— 这也是原版的行为（找不到函数直接返回 null，
     * 并把该函数名标记为失败，之后不再重试）。
     */
    Object call(String fnName, Object... args);
}
