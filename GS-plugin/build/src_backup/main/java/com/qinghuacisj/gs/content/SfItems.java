package com.qinghuacisj.gs.content;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 粘液物品栈解析 —— 等价于 RSC 的 {@code ProjectAddon.getSfStack}。
 *
 * <p>解析顺序（逐字对齐 RSC）：
 * <ol>
 *   <li>全局注册表 {@code SlimefunItem.getById(大写 id)}，取其 {@code getItem()}</li>
 *   <li>本附属的预加载表（<b>先于</b>本附属物品注册完成，用于物品之间互相引用）</li>
 * </ol>
 *
 * <p>★ 为什么必须有预加载表：{@code items.yml} 里 52 个自家物品互相引用
 * （如配方里写 {@code material: GS_DREAM_RULE}），而 A 物品读配方时 B 物品可能还没注册。
 * RSC 的做法是先把**所有**条目的 SlimefunItemStack 预加载进一张表再逐个 readEach，
 * 原生版照做 —— 这也是加载必须分「预加载 → 正式读取」两阶段的原因。
 */
public final class SfItems {

    /** id（已大写） → 物品栈。顺序保留，便于日志排查。 */
    private static final Map<String, SlimefunItemStack> PRELOAD = new LinkedHashMap<>();

    private SfItems() {
    }

    /** 预加载阶段登记一个物品栈。 */
    public static void preload(String id, SlimefunItemStack stack) {
        if (id != null && stack != null) {
            PRELOAD.put(GS.idOf(id), stack);
        }
    }

    /** 是否已预加载（用于「ID 冲突」判定，对应 RSC 的 passItemIdConflictCheck）。 */
    public static boolean isPreloaded(String id) {
        return PRELOAD.containsKey(GS.idOf(id));
    }

    /** 解析一个粘液物品栈；找不到返回 null。 */
    public static SlimefunItemStack get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = GS.idOf(id);

        SlimefunItem sf = SlimefunItem.getById(key);
        if (sf != null && sf.getItem() instanceof SlimefunItemStack sfis) {
            return sfis;
        }
        return PRELOAD.get(key);
    }

    /** 清空预加载表（重载时用）。 */
    public static void clear() {
        PRELOAD.clear();
    }

    /** 预加载表大小（日志用）。 */
    public static int size() {
        return PRELOAD.size();
    }
}
