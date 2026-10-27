package com.qinghuacisj.gs.machine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单注册表 —— 对应 RSC 的 {@code ProjectAddon.getMenus()}。
 *
 * <p>为什么需要它：机器在注册时要用<b>自己的 id</b> 去 {@code menus.yml} 里找菜单
 * （RSC 的 {@code CommonUtils.getIf(addon.getMenus(), m -> m.getId().equalsIgnoreCase(id))}）。
 * 所以菜单必须先于机器读完，并且要支持<b>忽略大小写</b>的查找 ——
 * yml 里的菜单 id 和机器 id 只要大小写一致就行，RSC 用的是 {@code equalsIgnoreCase}。
 *
 * <p>★ 加载顺序：{@code Setup} 里 <b>菜单在前、机器在后</b>（G-22）。
 */
public final class Menus {

    /** id → 菜单。保留插入顺序，便于日志排查。 */
    private static final Map<String, MachineMenu> MENUS = new LinkedHashMap<>();

    private Menus() {
    }

    public static void register(MachineMenu menu) {
        if (menu != null && menu.getId() != null) {
            MENUS.put(menu.getId(), menu);
        }
    }

    /** 忽略大小写查找（与 RSC 的 {@code equalsIgnoreCase} 一致）。 */
    public static MachineMenu find(String id) {
        if (id == null) {
            return null;
        }
        MachineMenu exact = MENUS.get(id);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, MachineMenu> e : MENUS.entrySet()) {
            if (e.getKey().equalsIgnoreCase(id)) {
                return e.getValue();
            }
        }
        return null;
    }

    public static boolean contains(String id) {
        return find(id) != null;
    }

    public static List<MachineMenu> all() {
        return new ArrayList<>(MENUS.values());
    }

    public static int size() {
        return MENUS.size();
    }

    public static void clear() {
        MENUS.clear();
    }
}
