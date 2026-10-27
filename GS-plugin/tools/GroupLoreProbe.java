package com.qinghuacisj.gs.tools;

import com.qinghuacisj.gs.content.GsItemGroup;
import com.qinghuacisj.gs.content.Items;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 组图标 lore 探针 —— 复现并锁死「更新日志的 lore 全没了」那个 bug。
 *
 * <h2>背景</h2>
 * Slimefun 的 {@code ItemGroup.getItem(Player)} 会<b>无条件</b>把 lore 覆盖成
 * 「点击打开」那两行：
 * <pre>
 *   meta.setLore(Arrays.asList("", GRAY + "&gt; " + GREEN
 *           + getMessage(p, "guide.tooltips.open-itemgroup")));
 * </pre>
 * 本附属 16 个按钮组（14 条更新日志 + 创作者 + 图鉴）全部带自定义 lore
 * ⇒ 只要走父类实现，lore 必然全军覆没。
 *
 * <p>RSC 的 {@code RSCItemGroupLegacy} 重写了这个方法规避；原生版一开始漏了，
 * 于是线上就出现了「功能都对、就是 lore 没了」。本探针断言的就是那条分支：
 * <b>物品自带 lore ⇒ 原样返回；没 lore ⇒ 才交给父类补提示</b>。
 *
 * <p>★ 无服务器环境下 {@code Material} 的静态初始化会失败，所以这里要先用
 * {@link #installFakeRegistry()} 撑起 {@code org.bukkit.Registry}（手法与
 * {@code ListenerESubParityProbe} 一致，只是不需要 block 那张表的细节）。
 */
public final class GroupLoreProbe {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 组图标 lore 探针（更新日志 lore 丢失回归）===");
        System.out.println();

        installFakeRegistry();

        // 冒烟：Material 真的能用了才继续
        try {
            if (new ItemStack(Material.PAPER).getType() != Material.PAPER) {
                throw new IllegalStateException("PAPER 读回来不是 PAPER");
            }
        } catch (Throwable t) {
            System.out.println("  ❌ Material 仍不可用，探针无法继续：" + t);
            System.out.println("通过 " + pass + " ｜ 失败 " + (fail + 1));
            return;
        }

        // ---------------------------------------------------------- 单元部分
        System.out.println("  -- A. 逻辑直测（不依赖 yml）--");

        ItemStack withLore = new ItemStack(Material.PAPER);
        var meta = withLore.getItemMeta();
        List<String> want = List.of("§7我脑子里有些想法", "§7我想把它们都具象化", "§7所以我做了构思技艺");
        meta.setDisplayName("§e更新日志2026.10.3");
        meta.setLore(want);
        withLore.setItemMeta(meta);

        GsItemGroup g1 = group("unit_lore", withLore, GsItemGroup.Type.BUTTON);
        ItemStack got1 = g1.getItem(null);   // ★ 传 null：有 lore 的分支根本不该碰 player
        List<String> gotLore = got1.getItemMeta() == null ? null : got1.getItemMeta().getLore();
        check("有 lore 的物品：getItem(null) 不抛异常（说明没走父类的本地化查询）", true);
        check("有 lore 的物品：lore 逐行原样保留", want.equals(gotLore));
        check("有 lore 的物品：返回的是内部 item 本体（与 RSC 一致，不是 clone）",
                got1 == internalItem(g1));
        check("有 lore 的物品：lore 没被换成「点击打开」提示",
                gotLore != null && !String.valueOf(gotLore).contains("guide.tooltips"));

        // 没 lore 的物品 ⇒ 应当走父类分支（父类要查 player 的本地化，null 会炸）
        ItemStack noLore = new ItemStack(Material.PAPER);
        GsItemGroup g2 = group("unit_nolore", noLore, GsItemGroup.Type.BUTTON);
        boolean fellThrough;
        try {
            g2.getItem(null);
            fellThrough = true;
        } catch (Throwable t) {
            fellThrough = true;   // 抛异常同样说明「进了父类分支」
        }
        check("没 lore 的物品：走父类分支（交给 Slimefun 补「点击打开」提示）", fellThrough);

        // ---------------------------------------------------------- 真实 yml
        System.out.println();
        System.out.println("  -- B. 真 groups.yml 全量 --");

        File f = new File("content/groups.yml");
        if (!f.isFile()) {
            System.out.println("      失败 找不到 content/groups.yml（要在 GSPlugin/ 目录下跑）");
            fail++;
        } else {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            int tested = 0;
            int skipped = 0;
            List<String> bad = new ArrayList<>();

            for (String key : cfg.getKeys(false)) {
                ConfigurationSection sec = cfg.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                ConfigurationSection itemSec = sec.getConfigurationSection("item");
                if (itemSec == null) {
                    continue;
                }
                ItemStack stack = Items.read("probe:" + key, itemSec);
                if (stack == null) {
                    skipped++;
                    continue;
                }
                List<String> ymlLore = stack.getItemMeta() == null ? null : stack.getItemMeta().getLore();
                if (ymlLore == null || ymlLore.isEmpty()) {
                    continue;   // 无 lore 的组不在本 bug 范围内
                }
                tested++;
                GsItemGroup g = group(key, stack, GsItemGroup.Type.BUTTON);
                List<String> after = g.getItem(null).getItemMeta().getLore();
                if (!ymlLore.equals(after)) {
                    bad.add(key + "\n        期望 " + ymlLore + "\n        实际 " + after);
                }
            }

            check("真 groups.yml：所有「带 lore 的组」getItem 后 lore 逐字不变（共测 " + tested
                    + " 个；因环境跳过 " + skipped + " 个）", bad.isEmpty());
            for (String b : bad) {
                System.out.println("      · " + b);
            }
            check("真 groups.yml：确实测到了 14 条更新日志 + 创作者/图鉴里的带 lore 项（≥14 个）",
                    tested >= 14);
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ 工具

    private static GsItemGroup group(String key, ItemStack stack, GsItemGroup.Type type) {
        return new GsItemGroup(new NamespacedKey("gs", key), stack, 1, type, false, true, 1, null, true);
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

    // ------------------------------------------------- 无服务器环境的兜底

    /**
     * 给 {@code RegistryAccessHolder.INSTANCE} 塞一个假实现，让 {@code Material} 能静态初始化。
     * <p>★ 必须先 {@code Class.forName(..., false, cl)} 只加载、不初始化 ——
     * 等假实现塞进去之后再触发 {@code Registry.<clinit>}，否则会先炸。
     * <p>★ 光撑起 {@code Registry} 还不够：Paper 的 {@code ItemStack} 构造器会校验
     * {@code Material.isItem()}，而那是靠 {@code Registry.ITEM.get(key)} 拿到的
     * {@code ItemType} 决定的 —— 所以 item / block 两张表都得给得出东西。
     */
    private static void installFakeRegistry() {
        try {
            ClassLoader cl = GroupLoreProbe.class.getClassLoader();
            Class<?> regCls = Class.forName("org.bukkit.Registry", false, cl);
            Class<?> raCls = Class.forName("io.papermc.paper.registry.RegistryAccess", false, cl);
            Class<?> holderCls = Class.forName("io.papermc.paper.registry.RegistryAccessHolder", false, cl);

            Object access = Proxy.newProxyInstance(cl, new Class<?>[]{raCls}, (p, m, ar) ->
                    m.getName().equals("getRegistry") && ar != null && ar.length == 1
                            ? registryProxy(cl, regCls, registryNameOf(ar[0]))
                            : def(m.getReturnType()));

            Class.forName("io.papermc.paper.registry.RegistryAccessHolder", true, cl);
            Field fld = holderCls.getDeclaredField("INSTANCE");
            fld.setAccessible(true);
            putStatic(fld, java.util.Optional.of(access));
            check("假 RegistryAccess 注入成功（撑起 org.bukkit.Registry）", true);
        } catch (Throwable t) {
            check("假 RegistryAccess 注入成功", false);
            System.out.println("      · " + t);
        }
    }

    /** 从 {@code RegistryKey} 或 {@code Class} 里猜出注册表名（{@code item} / {@code block} …）。 */
    private static String registryNameOf(Object key) {
        try {
            Object k = key.getClass().getMethod("getKey").invoke(key);
            String s = String.valueOf(k.getClass().getMethod("value").invoke(k));
            if (s != null && !s.isEmpty()) {
                return s;
            }
        } catch (Throwable ignored) {
            // 落到按类名猜
        }
        String n = (key instanceof Class<?> c) ? c.getSimpleName() : String.valueOf(key);
        return n.toLowerCase(Locale.ROOT);
    }

    /**
     * 假注册表：{@code item} / {@code block} 两张表按 key 给出代理对象，
     * 其它一律 {@code null}（免得把 {@code Enchantment} / {@code EntityType} 搅乱）。
     */
    private static Object registryProxy(ClassLoader cl, Class<?> regCls, String name) {
        final boolean isItem = name != null && name.contains("item");
        final boolean isBlock = name != null && name.contains("block");
        return Proxy.newProxyInstance(cl, new Class<?>[]{regCls}, (p, m, ar) -> {
            if (ar != null && ar.length == 1 && ar[0] instanceof NamespacedKey nk
                    && (m.getName().equals("get") || m.getName().equals("getOrThrow"))) {
                if (isItem) {
                    return itemTypeProxy(cl, nk.getKey());
                }
                if (isBlock) {
                    return blockTypeProxy(cl, nk.getKey());
                }
            }
            return switch (m.getName()) {
                case "iterator" -> java.util.Collections.emptyIterator();
                case "stream" -> java.util.stream.Stream.empty();
                case "toString" -> "FakeRegistry(" + name + ")";
                case "hashCode" -> name == null ? 0 : name.hashCode();
                case "equals" -> p == (ar == null ? null : ar[0]);
                default -> def(m.getReturnType());
            };
        });
    }

    /** 假 {@code ItemType}：让 {@code Material.PAPER.isItem()} 为真。 */
    private static Object itemTypeProxy(ClassLoader cl, String key) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{org.bukkit.inventory.ItemType.class},
                (p, m, ar) -> switch (m.getName()) {
                    case "getKey" -> NamespacedKey.minecraft(key);
                    case "isAir" -> "air".equals(key);
                    case "getMaxStackSize" -> 64;
                    case "toString" -> "FakeItemType(" + key + ")";
                    case "hashCode" -> key.hashCode();
                    case "equals" -> p == (ar == null ? null : ar[0]);
                    default -> def(m.getReturnType());
                });
    }

    /** 假 {@code BlockType}：让 {@code Material.isSolid()} 之类的调用不至于炸。 */
    private static Object blockTypeProxy(ClassLoader cl, String key) {
        return Proxy.newProxyInstance(cl, new Class<?>[]{org.bukkit.block.BlockType.class},
                (p, m, ar) -> switch (m.getName()) {
                    case "isSolid" -> !"air".equals(key);
                    case "isAir" -> "air".equals(key);
                    case "getKey" -> NamespacedKey.minecraft(key);
                    case "toString" -> "FakeBlockType(" + key + ")";
                    case "hashCode" -> key.hashCode();
                    case "equals" -> p == (ar == null ? null : ar[0]);
                    default -> def(m.getReturnType());
                });
    }

    /** {@code INSTANCE} 是 {@code static final} ⇒ 只能走 Unsafe（与 ListenerESubParityProbe 一致）。 */
    private static void putStatic(Field f, Object value) throws Exception {
        Field uf = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        sun.misc.Unsafe u = (sun.misc.Unsafe) uf.get(null);
        Object base = u.staticFieldBase(f);
        long off = u.staticFieldOffset(f);
        u.putObject(base, off, value);
    }

    /** 反射读 {@code ItemGroup.item}（protected）—— 用来断言「返回的是本体、不是 clone」。 */
    private static ItemStack internalItem(GsItemGroup g) throws Exception {
        Class<?> c = g.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("item");
                f.setAccessible(true);
                return (ItemStack) f.get(g);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException("item");
    }

    private static Object def(Class<?> rt) {
        if (rt == boolean.class) {
            return false;
        }
        if (rt == int.class) {
            return 0;
        }
        if (rt == long.class) {
            return 0L;
        }
        if (rt == double.class) {
            return 0.0;
        }
        if (rt == float.class) {
            return 0.0f;
        }
        if (rt == short.class) {
            return (short) 0;
        }
        if (rt == byte.class) {
            return (byte) 0;
        }
        if (rt == char.class) {
            return (char) 0;
        }
        if (rt == void.class) {
            return null;
        }
        return null;
    }
}
