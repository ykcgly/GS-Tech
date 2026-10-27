import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;

import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 探针：验证原生脚本宿主的两个地基假设。
 *
 * <p>① {@code bindings.putMember("name", javaLambda)} 后，JS 能否用 {@code name(args)} 直接调用？
 * ② JS 函数能否被强转成 Java 的 {@code Function<Object[],?>}，并由 Java 侧回调？
 *
 * <p>这两条成立，宿主就能像 RSC 一样用 Java lambda 注入全局函数，
 * 而不需要写一堆手搓的 JS 胶水。
 */
public final class GraalProbe {

    public static void main(String[] args) {
        HostAccess access = HostAccess.newBuilder()
                .allowPublicAccess(true)
                .allowAllImplementations(true)
                .allowAllClassImplementations(true)
                .allowArrayAccess(true)
                .allowListAccess(true)
                .allowMapAccess(true)
                .targetTypeMapping(Double.class, Float.class, null, Double::floatValue)
                .targetTypeMapping(Integer.class, Float.class, null, Integer::floatValue)
                .targetTypeMapping(Object.class, String.class, null, String::valueOf)
                .build();

        try (Context ctx = Context.newBuilder("js")
                .allowAllAccess(true)
                .allowHostAccess(access)
                .allowNativeAccess(false)
                .allowExperimentalOptions(true)
                .allowPolyglotAccess(PolyglotAccess.ALL)
                .allowCreateProcess(true)
                .allowIO(IOAccess.ALL)
                .allowHostClassLookup(s -> !s.startsWith("net.luckperms")
                        && !s.startsWith("me.lucko")
                        && !s.startsWith("org.anjocaido.groupmanager"))
                .allowHostClassLoading(true)
                .build()) {

            Value b = ctx.getBindings("js");

            // ---- ① Java lambda 作为全局函数 ----
            b.putMember("getServer", (Supplier<String>) () -> "SERVER-OK");
            b.putMember("sendMessage", (BiFunction<Object, String, String>)
                    (p, s) -> "MSG[" + p + ":" + s + "]");

            // ---- ② JS 函数转 Function<Object[],?> 并回调 ----
            final StringBuilder called = new StringBuilder();
            b.putMember("runLater", (BiFunction<Function<Object[], ?>, Integer, String>) (fn, delay) -> {
                // 模拟 Bukkit 的 runTaskLater：稍后回调，并把 task 当参数传进去。
                // ★ 关键问题：Java 侧传 Object[]{task}，JS 侧看到的是「展开的一个参数」
                //   还是「一个数组」？JS 回调里把收到的值原样拼出来就能判定。
                Object task = "TASK#" + delay;
                Object r = fn.apply(new Object[]{task});
                called.append("runLater(delay=").append(delay)
                      .append(", fnReturn=").append(r).append(")");
                return "scheduled";
            });

            String js = """
                    var a = getServer();
                    var c = sendMessage('QingHua', 'hi');
                    var got = null, argLen = null, isArr = null;
                    var ret = runLater(function (task) {
                        got = 'cb:' + task;
                        argLen = arguments.length;
                        isArr = Array.isArray(task);
                        return 'ret-from-js';
                    }, 7);
                    JSON.stringify({ a: a, c: c, ret: ret, got: got,
                                     argLen: argLen, isArr: isArr });
                    """;

            Value res = ctx.eval(Source.newBuilder("js", js, "JavaScript").build());
            System.out.println("① + ② 结果: " + res.asString());
            System.out.println("   Java 侧记录: " + called);
            System.out.println("   JS 回调是否真的执行了: "
                    + (res.asString().contains("cb:TASK#7") ? "是" : "否"));

            // ---- ③ 未定义的全局函数会怎样 ----
            Value r3 = ctx.eval("js", "typeof (typeof registerEvent === 'function')");
            System.out.println("③ typeof registerEvent: " + r3.asString());
            Value r4 = ctx.eval("js", "typeof JavaAdapter");
            System.out.println("   typeof JavaAdapter  : " + r4.asString());

            // ---- ④ Java.type 能否取到 Bukkit 类 ----
            Value r5 = ctx.eval("js", "Java.type('org.bukkit.Material').APPLE.name()");
            System.out.println("④ Java.type('org.bukkit.Material').APPLE.name() = " + r5.asString());

            // ---- ⑤ 静态类符号注入（模拟 env.asHostSymbol）----
            b.putMember("MaterialClass", ctx.asValue(org.bukkit.Material.class));
            Value r6 = ctx.eval("js", "MaterialClass.DIAMOND.name()");
            System.out.println("⑤ asValue(Material.class).DIAMOND.name() = " + r6.asString());

        } catch (Throwable t) {
            System.out.println("!! 探针失败: " + t);
            t.printStackTrace(System.out);
        }
    }
}
