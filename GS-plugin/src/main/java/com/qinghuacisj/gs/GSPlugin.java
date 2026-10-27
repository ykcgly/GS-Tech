package com.qinghuacisj.gs;

import com.qinghuacisj.gs.script.Script;
import io.github.thebusybiscuit.slimefun4.api.SlimefunAddon;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * 「构思技艺」(GS) —— 独立 jar 附属插件主类。
 *
 * <p>本插件由 RSC 附属（yml + GraalJS 脚本）原生化为纯 Java 插件，
 * 不再依赖 RykenSlimefunCustomizer。
 *
 * <p>启动顺序（照抄 RSC 的 onEnable 语义，不要打乱）：
 * <ol>
 *   <li>登记单例</li>
 *   <li>按类别读取 content/*.yml 并注册到 Slimefun</li>
 *   <li>注册监听器</li>
 * </ol>
 */
public class GSPlugin extends JavaPlugin implements SlimefunAddon {

    private static GSPlugin instance;

    /** 插件单例；onEnable 之前为 null。 */
    public static GSPlugin instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        GS.init(this);
        getLogger().info("构思技艺 (GS) 开始加载  ·  万物初生，始于想象");

        // ★★ 注册 /gs 命令 —— 必须在这里做。
        //   plugin.yml 从第一版起就声明了 `gs` 命令，但一直没注册 executor；
        //   而 Bukkit 的 PluginCommand#execute 取到 executor 字段后是**直接**
        //   调 onCommand 的（javap -c 确认：offset 51 取字段、59 直接 invokeinterface，
        //   中间没有 ifnull）⇒ 敲 /gs 会 NPE。详见 GsCommand 的类注释。
        PluginCommand cmd = getCommand("gs");
        if (cmd != null) {
            GsCommand impl = new GsCommand();
            cmd.setExecutor(impl);
            cmd.setTabCompleter(impl);
        } else {
            getLogger().warning("plugin.yml 里没有 gs 命令 —— /gs 不可用");
        }

        boolean ok = false;
        try {
            Setup.loadAll();
            ok = true;
        } catch (Throwable t) {
            // 与原 JS 版一致：绝不让异常冒泡到 Bukkit 事件总线
            GS.error(t, "加载失败");
        }
        // ★ 只有真的加载成功才报成功 —— 第一版这里无条件打「加载成功」，
        //   于是 GraalJS 缺失导致 onEnable 整个失败时，日志依然显示成功，非常误导。
        if (ok) {
            getLogger().info("构思技艺 (GS) 加载成功！");
        } else {
            getLogger().severe("构思技艺 (GS) 加载失败 —— 详见上方堆栈");
        }
    }

    @Override
    public void onDisable() {
        // 先丢掉脚本模块实例（各模块的静态状态、PDC 缓存），再停调度。
        // ★ 阶段②之前这里还要关 GraalJS 的 Context 与本地资源；18 个脚本全部
        //   Java 化之后没有 JS 引擎了，clearCache() 现在只做 Modules.clear()。
        Script.clearCache();
        GS.shutdown();
        instance = null;
    }

    @Override
    public JavaPlugin getJavaPlugin() {
        return this;
    }

    @Override
    public String getBugTrackerURL() {
        return null;
    }
}
