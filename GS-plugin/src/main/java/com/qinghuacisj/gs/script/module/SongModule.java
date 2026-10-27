package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * {@code gs_song.js} 的 Java 化版本 —— 「如听仙乐耳暂明」。
 *
 * <p>右键：放一声<b>小恶魂的叫声</b>（{@code entity.ghastling.ambient}）。
 * 不消耗物品（可反复按）、0.5 秒防连点（只是防连点，不是长冷却）、
 * 冷却中<b>静默忽略</b>（不提示、不出声）、
 * <b>无法与任何方块交互</b>（手持本物品右键方块时方块功能一律不触发）。
 *
 * <p>★★ 两个关键机制：
 * <ol>
 *   <li><b>只认主手</b>（{@link Guards#isMainHandUse}）：一次右键会连发两个事件
 *       （主手/副手各一个）⇒ 不拦就会「啪」地叫两声。</li>
 *   <li><b>「无法交互」靠一个 HIGHEST 优先级的 {@link PlayerInteractEvent} 拦截器</b>：
 *       Slimefun 在 NORMAL 优先级发事件（触发 {@code onUse}），我们注册在 HIGHEST
 *       ⇒ 一定在 {@code onUse} 之后才 {@code setCancelled}，
 *       所以「音效照放、方块交互被拦」，两者互不影响。
 *       实现见 {@link InteractGuard}（4 个脚本共用，已实测各自注册成功）。</li>
 * </ol>
 *
 * <p>★ 只有<b>按的那个人自己</b>听得到（走 {@code p.playSound}）。
 * 想让附近所有人都听到，把 {@link #OWNER_ONLY} 改成 false。
 *
 * <p>★ 拦截器在<b>构造器</b>里注册 —— 因为原脚本是在顶层代码里注册的
 * （{@code try { if (sgRegisterGuard()) … }} 就在顶层），而
 * <b>模块的构造器 = 原脚本的顶层代码</b>。
 * 原脚本 {@code onUse} 里那句「惰性重试」也保留（加载时插件可能还没就绪）。
 *
 * <p>★ 原脚本还有一条 {@code JavaAdapter} 兜底分支（{@code registerEvent} 抛异常时
 * 改用 {@code new JavaAdapter(Listener, {})}）。那条路<b>永远走不到</b>：
 * GraalJS 25 里 {@code JavaAdapter} 是 {@code undefined}（已实测），
 * 所以它一进 catch 就会再抛一次然后落到 {@code SG_GUARD_OK = false}。
 * 而主路径（5 参 {@code registerEvent}）在本环境下必然成功 —— 已实测
 * 「方块交互拦截 已启用」。因此这里只实现主路径。
 */
public final class SongModule extends AbstractModule {

    // ---------------- 参数 ----------------
    // ★ 字段名与 JS 里的顶层常量名**逐个对齐**，方便用 ConstantParityProbe 机械对账。
    static final String SG_ITEM_ID = "GS_SONG";
    static final String SG_SOUND = "entity.ghastling.ambient";  // ★ 小恶魂的叫声
    static final float SG_VOL = 1.00f;    // 音量
    static final float SG_PIT = 1.00f;    // 音高（1.0 = 原音）
    static final long SG_CD_MS = 500L;    // 0.5 秒防连点（不是长冷却）
    /** true = 只有按的人听得到；false = 附近所有人都听得到。 */
    static final boolean SG_OWNER_ONLY = true;

    /** uuid → 上次使用的时间戳(ms)。等价于原脚本的 {@code SG_LAST}。 */
    private final Guards.Cooldown last = new Guards.Cooldown(SG_CD_MS);

    public SongModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_song 已加载 · 小恶魂的叫声 " + SG_SOUND
                    + " · 防连点 " + SG_CD_MS + "ms");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }

        // ★ 顶层代码里注册拦截器（原脚本同样在顶层注册）
        try {
            if (InteractGuard.register(SG_ITEM_ID)) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 如听仙乐耳暂明：方块交互拦截 已启用");
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    @Override
    public String name() {
        return "gs_song";
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            // 惰性重试：加载时插件可能还没就绪（与原脚本一致）
            try {
                InteractGuard.register(SG_ITEM_ID);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }

            Player p;
            try {
                p = event.getPlayer();
            } catch (Throwable e) {
                p = null;
            }
            if (p == null) {
                return null;
            }

            // ★ 只认主手触发
            if (!Guards.isMainHandUse(event, p, SG_ITEM_ID)) {
                return null;
            }

            // 0.5 秒防连点：冷却中**静默忽略**
            if (last.left(p) > 0) {
                return null;
            }
            last.mark(p);

            // ★ 就叫一声（小恶魂的叫声）
            try {
                org.bukkit.Location loc = p.getLocation();
                if (SG_OWNER_ONLY) {
                    p.playSound(loc, SG_SOUND, SG_VOL, SG_PIT);
                } else {
                    p.getWorld().playSound(loc, SG_SOUND, SG_VOL, SG_PIT);
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 如听仙乐耳暂明 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
