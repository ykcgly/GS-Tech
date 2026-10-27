import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

/**
 * 探针：验证「用子类加载器 + TCCL 从外部 jar 里跑 GraalJS」这条路线真的可行。
 *
 * <p>本探针<b>不依赖 Bukkit / Slimefun</b>，只用反射碰 Graal —— 这样就能在不起服务器的情况下
 * 先确认机制成立，省掉一轮「改代码 → 起服 → 看日志」的长回路。
 *
 * <p>要验证三件事：
 * <ol>
 *   <li>子加载器能否从 {@code libs/graal/*.jar} 里加载 {@code org.graalvm.polyglot.Context}；</li>
 *   <li>不设 TCCL 时会不会报「找不到语言 js」（Truffle 用 ServiceLoader 发现语言实现）；</li>
 *   <li>设了 TCCL 之后能否真的求值 JS，并能把 Java lambda 注入成全局函数。</li>
 * </ol>
 */
public final class LoaderProbe {

    public static void main(String[] args) throws Exception {
        File graalDir = new File(args.length > 0 ? args[0] : "libs/graal");
        List<URL> urls = new ArrayList<>();
        File[] jars = graalDir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            System.out.println("!! 找不到 GraalJS jar: " + graalDir.getAbsolutePath());
            return;
        }
        for (File f : jars) {
            urls.add(f.toURI().toURL());
        }
        System.out.println("GraalJS jar 数: " + jars.length);

        // 父加载器 = 系统加载器（它看不到 Graal，正好模拟插件加载器的处境）
        URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                LoaderProbe.class.getClassLoader());

        // ---- ① 能否加载 Context ----
        Class<?> ctxB;
        try {
            ctxB = Class.forName("org.graalvm.polyglot.Context", true, loader);
            System.out.println("① 加载 Context: OK  (" + ctxB.getClassLoader() + ")");
        } catch (Throwable t) {
            System.out.println("① 加载 Context: 失败 -> " + t);
            return;
        }

        Object builder = ctxB.getMethod("newBuilder", String[].class)
                .invoke(null, (Object) new String[]{"js"});
        builder.getClass().getMethod("allowAllAccess", boolean.class).invoke(builder, true);

        // ---- ② 不设 TCCL 就构造 Engine ----
        System.out.println("② 不设 TCCL 直接 build:");
        try {
            Object ctx = builder.getClass().getMethod("build").invoke(builder);
            Object v = ctxB.getMethod("eval", String.class, CharSequence.class)
                    .invoke(ctx, "js", "1+2");
            System.out.println("   build 成功，eval 1+2 = " + v);
            ctxB.getMethod("close", boolean.class).invoke(ctx, true);
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("   build 失败 -> " + root);
        }

        // ---- ③ 设了 TCCL 再构造 ----
        System.out.println("③ 设 TCCL 后 build:");
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            Object builder2 = ctxB.getMethod("newBuilder", String[].class)
                    .invoke(null, (Object) new String[]{"js"});
            builder2.getClass().getMethod("allowAllAccess", boolean.class).invoke(builder2, true);
            Object ctx = builder2.getClass().getMethod("build").invoke(builder2);

            Object v = ctxB.getMethod("eval", String.class, CharSequence.class)
                    .invoke(ctx, "js", "1+2");
            System.out.println("   build 成功，eval 1+2 = " + v);

            // 注入一个 Java lambda 当全局函数，再从 JS 调它
            Object bindings = ctxB.getMethod("getBindings", String.class).invoke(ctx, "js");
            Class<?> valueB = Class.forName("org.graalvm.polyglot.Value", true, loader);
            bindings.getClass().getMethod("putMember", String.class, Object.class)
                    .invoke(bindings, "ping",
                            (java.util.function.Supplier<String>) () -> "PONG");
            Object v2 = ctxB.getMethod("eval", String.class, CharSequence.class)
                    .invoke(ctx, "js", "ping() + '/' + (typeof JavaAdapter)");
            System.out.println("   注入 lambda 后 eval = " + v2);

            // 类符号：确认 Java.type 可用
            Object v3 = ctxB.getMethod("eval", String.class, CharSequence.class)
                    .invoke(ctx, "js", "Java.type('java.util.ArrayList').class.name");
            System.out.println("   Java.type 可用性 = " + v3);

            ctxB.getMethod("close", boolean.class).invoke(ctx, true);
            System.out.println("③ 结论：子加载器 + TCCL 路线可行");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("③ 失败 -> " + root);
            root.printStackTrace(System.out);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }

        loader.close();
    }
}
