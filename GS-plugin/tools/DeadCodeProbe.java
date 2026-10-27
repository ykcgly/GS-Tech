package com.qinghuacisj.gs.script.module;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

/**
 * 死代码判定探针 —— 用<b>真 GraalJS</b>（与生产同一版本）实测
 * 「原脚本里那几段容错分支到底走不走得到」。
 *
 * <p><b>为什么必须实测</b>：{@code gs_maiyou_wine} 里有两段看起来在「兜底」的代码：
 * <ol>
 *   <li>{@code myFreeze} 里 {@code LootTables.EMPTY} → 想清空战利品表；</li>
 *   <li>{@code mySetPuff} 里 {@code Java.type('org.bukkit.entity.PufferFish$PuffState')}
 *       → 想用河豚自身的膨胀状态表现大小。</li>
 * </ol>
 * 光看代码会觉得它们在工作。但 {@code javap} 显示这个版本的 Bukkit 里
 * <b>既没有 {@code LootTables.EMPTY} 常量，也没有 {@code PufferFish$PuffState} 类</b>。
 *
 * <p>问题是「GraalJS 遇到不存在的类/字段时是抛异常、还是返回 undefined」——
 * 这决定了 catch 分支怎么走，进而决定要不要在 Java 里照抄这两段。
 * 推理容易错，所以直接跑真 JS 问它。
 *
 * <p>★ 结论会写进 {@code MaiyouWineModule} 的类注释。
 */
public final class DeadCodeProbe {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        System.out.println("=== gs_maiyou_wine 死代码判定（真 GraalJS 实测）===");
        System.out.println();

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostClassLookup(s -> true)
                .build()) {
            Value b = ctx.getBindings("js");

            // ---- ① Java.type 一个不存在的类，是抛异常还是返回别的？ ----
            Value r1 = ctx.eval(Source.newBuilder("js",
                    "var out1 = '';\n"
                            + "try { var X = Java.type('org.bukkit.entity.PufferFish$PuffState');\n"
                            + "      out1 = 'RETURNED:' + X; }\n"
                            + "catch (e) { out1 = 'THREW:' + e; }\n"
                            + "out1;", "probe1").build());
            String s1 = r1.asString();
            System.out.println("① Java.type('…PufferFish$PuffState')");
            System.out.println("       " + s1);
            check("不存在的类 ⇒ Java.type 抛异常（所以 mySetPuff 必然 return false）",
                    s1.startsWith("THREW:"));

            // ---- ② 访问不存在的静态字段，抛异常还是 undefined？ ----
            Value r2 = ctx.eval(Source.newBuilder("js",
                    "var out2 = '';\n"
                            + "try { var L = Java.type('org.bukkit.loot.LootTables');\n"
                            + "      var e2 = L.EMPTY;\n"
                            + "      out2 = 'RETURNED:' + (typeof e2) + ':' + e2 + ':' + (e2 == null); }\n"
                            + "catch (e) { out2 = 'THREW:' + e; }\n"
                            + "out2;", "probe2").build());
            String s2 = r2.asString();
            System.out.println();
            System.out.println("② LootTables.EMPTY（该常量在本版本不存在）");
            System.out.println("       " + s2);
            check("取不到 EMPTY ⇒ 无论抛异常还是 undefined，`empty != null` 都为假 ⇒ 战利品表永远不被设置",
                    s2.startsWith("THREW:") || s2.startsWith("RETURNED:undefined:undefined:true"));

            // ---- ③ 对照组已删除 ----
            //   ★ 原本想实测 Attribute.SCALE 可取值。但无服务器环境下
            //     Java.type('org.bukkit.attribute.Attribute') 会触发静态初始化，
            //     而它依赖 RegistryAccess ⇒ ExceptionInInitializerError，
            //     这个错误会**直接中止 JVM**（不是能被 JS catch 的普通异常）。
            //   ★ 该常量是否存在已由 javap 确认（public static final Attribute SCALE），
            //     运行时可用性由真服测试覆盖，这里不再重复验证。

            // ---- ④ mySetPuff 在真脚本里到底返回什么 ----
            System.out.println();
            System.out.println("④ 直接把真脚本的 mySetPuff 拿出来跑");
            try {
                String src = new String(java.nio.file.Files.readAllBytes(
                        java.nio.file.Path.of(args.length > 0 ? args[0]
                                : "../QHCGS/GS/GS/scripts/gs_maiyou_wine.js")),
                        java.nio.charset.StandardCharsets.UTF_8);
                Value b2 = ctx.getBindings("js");
                ctx.eval(Source.newBuilder("js", src, "JavaScript").build());
                // 传一个假的 ent：mySetPuff 第一行就 Java.type，所以传 null 也能测出结论
                Value res = b2.getMember("mySetPuff").execute((Object) null, 1);
                System.out.println("       mySetPuff(null, 1) = " + res);
                check("mySetPuff 恒为 false（死代码）", !res.asBoolean());
            } catch (Throwable t) {
                System.out.println("       跑真脚本失败（可能缺 getServer 等宿主函数）: " + t);
            }

            // ---- ⑤ setRemoveWhenFarAway / setPersistent 是否存在 ----
            System.out.println();
            System.out.println("⑤ myFreeze 里其它调用的可用性");
            check("LivingEntity#setRemoveWhenFarAway 存在（这条是活的）", hasMethod(
                    "org.bukkit.entity.LivingEntity", "setRemoveWhenFarAway", boolean.class));
            check("Entity#setPersistent 存在（这条是活的）", hasMethod(
                    "org.bukkit.entity.Entity", "setPersistent", boolean.class));
            check("Mob#setCanPickupItems 存在（这条是活的）", hasMethod(
                    "org.bukkit.entity.Mob", "setCanPickupItems", boolean.class));
            // ★ Entity 上根本没有 setLootTable —— 所以「清空战利品表」那段不但
            //   因为 EMPTY 取不到而走不到，就算走到了也会因为方法不存在而抛异常。
            //   双重死代码，Java 侧不照抄。
            check("Entity#setLootTable 不存在 ⇒ 战利品表那段是「双重死代码」",
                    !hasMethod("org.bukkit.entity.Entity", "setLootTable"));
        }

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static boolean hasMethod(String cls, String name, Class<?>... params) {
        try {
            Class.forName(cls).getMethod(name, params);
            return true;
        } catch (Throwable t) {
            return false;
        }
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
