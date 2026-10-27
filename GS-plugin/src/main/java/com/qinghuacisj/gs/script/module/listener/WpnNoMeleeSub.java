package com.qinghuacisj.gs.script.module.listener;

import com.qinghuacisj.gs.GS;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@code gs_listener.js} 的「武器禁近战」子系统
 * （原脚本第 <b>10839~10950</b> 行，顶层入口 {@code gsWpnNoMeleeStart()}，第 10950 行）。
 *
 * <p><b>作用</b>：手持月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改时，
 * <b>取消原版左键近战那一下伤害</b>。
 *
 * <h2>★ 为什么非取消不可（不是「顺手改手感」）</h2>
 * 这五把武器的左键伤害<b>全部由脚本自己按连击 / 判定盒结算</b>。原版那一下如果留着，
 * 会带来两个致命副作用：
 * <ol>
 *   <li><b>击退</b> —— 怪被推出判定盒。尤其「此刻万籁俱寂」打的是<b>前方 5 格长条</b>、
 *       「阎魔刀·改」打的是<b>前方 9 格长条</b>，怪被推出去 ⇒ <b>连击直接断</b>。</li>
 *   <li><b>10 刻无敌帧</b> —— 原版伤害会给怪挂上 {@code noDamageTicks}，
 *       紧随其后的脚本伤害会被原版逻辑吃掉 ⇒ 连击数字涨了、伤害却没落地。</li>
 * </ol>
 * ⇒ 取消原版那一下，是把伤害结算权<b>完整收归脚本</b>。
 *
 * <h2>★ 五道放行闸门（逐条核对过，缺一条都会出问题）</h2>
 * <ol>
 *   <li>{@link #GS_WPN_NOMELEE} 为 {@code false} ⇒ 整个功能关掉（配置开关）。</li>
 *   <li>★★ <b>{@code gsWpnDmgGuard > 0} 就放行</b> —— 这是脚本<b>自家技能</b>伤害的
 *       「通行证」。{@code gsMsHurt} 在造成伤害前会把这个计数器 +1、事后 -1
 *       （见 {@link ListenerCore#gsMsHurt}）⇒ 自家技能那一下<b>不会被自己取消掉</b>。
 *       ★ 没有这道闸门，本模块会把脚本自己的全部伤害一起吃光（武器直接变废铁）。
 *       ★ 读的必须是 {@link ListenerCore} 上<b>同一个实例字段</b>
 *       {@code core.gsWpnDmgGuard} —— 换一份副本就等于闸门失效。</li>
 *   <li>原因必须是 {@code ENTITY_ATTACK} 或含 {@code SWEEP}（{@code ENTITY_SWEEP_ATTACK}）
 *       ⇒ 火焰 / 摔落 / 中毒 / 荆棘之类<b>完全不碰</b>。</li>
 *   <li>攻击者必须是 {@code PLAYER}（怪打人不归这里管）。</li>
 *   <li>受击者若是 {@code PLAYER} 且 {@link #GS_WPN_NL_PVP} 为 {@code false} ⇒ 放行
 *       （玩家之间照常打，要连 PvP 一起禁就把该项设成 true）。</li>
 * </ol>
 * 最后才查「攻击者手上是不是那五把武器之一」，是才取消。
 *
 * <h2>★ 与原脚本的有意差异（修掉一个真实缺陷）</h2>
 * <b>原脚本 reload 会累积监听器，Java 版改为幂等注册。</b>
 * 原脚本用 {@code pm.registerEvent(...)} 在脚本里自己注册，而 {@code /rsc reload}
 * 会重新 eval 整个脚本 ⇒ 监听器每次 reload 都再注册一遍、不断累积
 * （{@code GS_WPN_REGISTERED} 闩锁是脚本级变量，reload 后归零，挡不住累积）。
 * Java 版把监听器写成 {@link Listener} 实现类、{@code registerEvents} 只注册一次：
 * 事件照常收到、优先级照常、取消照常，可观察行为相同，但 reload 不再累积。
 * ★ 这不是「顺手优化」，是修一个真实缺陷。
 *
 * <p>★ 优先级与 {@code ignoreCancelled} 逐处核对过：原脚本
 * {@code pm.registerEvent(Ev, plugin, Priority.HIGHEST, executor, plugin)} <b>没有第 5 个参数</b>
 * ⇒ {@code ignoreCancelled} 默认 <b>false</b>；Java 的 {@code @EventHandler} 默认也是 false
 * ⇒ 不加该属性即等价（<b>绝不写成 true</b>）。
 *
 * <h2>死代码 / 走不到的分支（不照抄，在此存档）</h2>
 * <ol>
 *   <li><b>{@code JavaAdapter} 退路</b>（原脚本 10924~10931 行）：GraalJS 25.1.3 里
 *       {@code JavaAdapter} 是 {@code undefined}（{@code tools/GraalProbe.java} 实测）
 *       ⇒ 这条退路<b>永远走不到</b>。Java 版直接用原生 {@link Listener}。</li>
 *   <li><b>{@code plugin} 四级回退</b>（原脚本 10907~10911 行）：Java 版自己有 Plugin 实例
 *       （{@link GS#plugin()}）⇒ 整条回退链作废；{@code if (plugin == null) return false;}
 *       那条<b>恒不执行</b>。</li>
 *   <li><b>{@code Ev == null} 的判断</b>（原脚本 10916 行）：Java 里
 *       {@link EntityDamageByEntityEvent} 必然可用 ⇒ 恒不执行。</li>
 *   <li><b>{@code ok == false} 的失败分支</b>（原脚本 10941 行的
 *       「取消原版近战：监听器没注册上」）：只有注册抛异常才会走到，Java 里几乎不可能。
 *       ★ 但<b>日志文案照抄保留</b>（万一将来注册真失败必须打同一句）。</li>
 * </ol>
 *
 * <h2>★ {@code GS_WPN_REGISTERED} 闩锁</h2>
 * 原脚本里它<b>只写不读</b>（写：10899 行定义 + 10933 行置 ok；读：只在 10902 行的
 * {@code if (GS_WPN_REGISTERED) return true;} 早退里）。Java 里保留成<b>实例字段</b>：
 * 因为 {@code ListenerModule} 只有一个实例，它天然等价于「幂等注册一次」。
 *
 * <h2>★ 本子系统<b>没有</b>「已加载」日志</h2>
 * 原脚本这一段只有<b>一句</b> {@code info} 日志（注册成功那句），
 * <b>没有</b>「（GS_WPN_…）已加载 · …」那一行 —— 因为本模块没有可配参数要播报。
 * ★ 与「致万千无言者 / 此刻万籁俱寂 / 阎魔刀·改」三个模块<b>都有一行「已加载」</b>形成
 * 明确对照：脚本有 ⇒ Java 必须有；脚本没有 ⇒ Java 必须<b>没有</b>。
 * 探针里有反向断言钉住这一点（{@code WpnNoMeleeSub} 源码里不得出现 {@code 已加载}）。
 */
public final class WpnNoMeleeSub {

    // ======================================================================
    // 参数（名字必须与 JS 顶层 var 同名 —— 常量对账探针按名字反射比对）
    // ======================================================================

    /** {@code true = 手持那五把武器时，原版左键近战不造成任何伤害}。 */
    static final boolean GS_WPN_NOMELEE = true;

    /** {@code false = 只对生物生效；玩家之间照常（要 PvP 也去掉 ⇒ true）}。 */
    static final boolean GS_WPN_NL_PVP = false;

    /**
     * 被禁用的五把武器 ID。
     *
     * <p>★ 致万千无言者（石镐）—— 它的左键伤害<b>完全由 {@code gsVo*} 模块按连击结算</b>，
     * 原版那一下必须取消（否则怪被击退 + 吃无敌帧 ⇒ 连击断掉）。
     * <br>★ 此刻万籁俱寂（金镐）—— 同理；且它打的是<b>前方 5 格长条</b>，
     * 原版近战的击退会把怪推出这条长条 ⇒ 连击直接断。
     * <br>★ 阎魔刀·改（金剑）—— 伤害由 {@code gsEm*} 模块的「居合斩」按
     * 宽5×长9×高3 结算；原版近战那一下必须取消（否则怪被击退 + 吃 10 刻无敌帧）。
     */
    static final List<String> GS_WPN_IDS = Collections.unmodifiableList(Arrays.asList(
            "GS_MOON_SCYTHE", "GS_ECLIPSE_SCYTHE",
            "GS_VOICELESS", "GS_SILENCE", "GS_ENMA_BLADE"));

    // ======================================================================
    // 运行期状态
    // ======================================================================

    /** {@code GS_WPN_REGISTERED} —— 幂等闩锁（见类注释）。 */
    boolean GS_WPN_REGISTERED = false;

    private final ListenerCore core;

    public WpnNoMeleeSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsWpnNoMeleeStart();} 调用（原脚本第 10950 行）。 */
    public void start() {
        gsWpnNoMeleeStart();
    }

    // ======================================================================
    // 判定
    // ======================================================================

    /** {@code gsWpnHold(p)} —— 主手是不是那五把武器之一。 */
    static boolean gsWpnHold(Player p) {
        try {
            if (p == null) {
                return false;
            }
            ItemStack it;
            try {
                it = p.getInventory().getItemInMainHand();
            } catch (Throwable e) {
                return false;
            }
            if (it == null) {
                return false;
            }
            for (int i = 0; i < GS_WPN_IDS.size(); i++) {
                if (ListenerCore.gsIsItem(it, GS_WPN_IDS.get(i))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * {@code gsWpnNoMelee(e)} —— 五道闸门（见类注释）全过 ⇒ {@code e.setCancelled(true)}。
     *
     * <p>★ 本方法<b>读的是</b> {@code core.gsWpnDmgGuard} 这个<b>实例字段</b>，
     * 与 {@link ListenerCore#gsMsHurt} 增减的是同一个 ⇒ 「自家技能伤害放行」成立。
     */
    void gsWpnNoMelee(EntityDamageByEntityEvent e) {
        try {
            if (!GS_WPN_NOMELEE) {
                return;
            }
            if (core.gsWpnDmgGuard > 0) {
                return;                                   // ④ 放行自家技能伤害
            }
            String cause = "";
            try {
                cause = String.valueOf(e.getCause().name());
            } catch (Throwable e0) {
                return;
            }
            // ② 原因 = ENTITY_ATTACK / ENTITY_SWEEP_ATTACK（火焰、摔落之类完全不碰）
            if (!cause.contains("ENTITY_ATTACK") && !cause.contains("SWEEP")) {
                return;
            }
            Entity dam;
            try {
                dam = e.getDamager();
            } catch (Throwable e0) {
                return;
            }
            if (dam == null) {
                return;
            }
            String tn = "";
            try {
                tn = String.valueOf(dam.getType().name());
            } catch (Throwable e0) {
                return;
            }
            if (!tn.equals("PLAYER")) {
                return;                                   // ③
            }
            Entity victim = null;
            try {
                victim = e.getEntity();
            } catch (Throwable e0) {
                victim = null;
            }
            if (!GS_WPN_NL_PVP && victim != null) {       // ⑤
                String vt = "";
                try {
                    vt = String.valueOf(victim.getType().name());
                } catch (Throwable e0) {
                    // ★ 原脚本这里是空 catch（不 return）—— 保留
                }
                if (vt.equals("PLAYER")) {
                    return;
                }
            }
            if (!gsWpnHold((Player) dam)) {
                return;                                   // ③
            }
            try {
                e.setCancelled(true);
            } catch (Throwable e0) {
                // 与原脚本一致：静默
            }
        } catch (Throwable e9) {
            // 与原脚本一致：静默
        }
    }

    // ======================================================================
    // 注册（★ Java 版：幂等注册一次）
    // ======================================================================

    /**
     * {@code gsWpnNoMeleeStart()} —— 注册 <b>1</b> 个 {@code HIGHEST} 监听器 + 打日志。
     *
     * <p>★ 成功日志走 {@link ListenerCore#gsDaLog}（等价于原脚本的
     * {@code getServer().getLogger().info('[构思技艺] ' + ...)}），
     * 失败日志走 {@link ListenerCore#gsDaWarn}（等价于 {@code warning}）。
     */
    boolean gsWpnNoMeleeStart() {
        if (GS_WPN_REGISTERED) {
            return true;
        }
        try {
            boolean ok;
            try {
                Bukkit.getPluginManager().registerEvents(new Sync(), GS.plugin());
                ok = true;
            } catch (Throwable e1) {
                ok = false;
            }
            GS_WPN_REGISTERED = ok;
            if (ok) {
                ListenerCore.gsDaLog(gsWpnStartLine(GS_WPN_NOMELEE, GS_WPN_NL_PVP));
            } else {
                ListenerCore.gsDaWarn("取消原版近战：监听器没注册上");
            }
            return ok;
        } catch (Throwable e) {
            ListenerCore.gsDaWarn("取消原版近战注册失败: " + e);
            return false;
        }
    }

    /** 本子系统的监听器（★ {@code HIGHEST}，{@code ignoreCancelled} 默认 false）。 */
    final class Sync implements Listener {

        /** {@code EntityDamageByEntityEvent} —— 原版近战的统一入口。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamageByEntity(EntityDamageByEntityEvent e) {
            gsWpnNoMelee(e);
        }
    }

    // ======================================================================
    // 日志文案（抽成静态方法只为让探针逐字节对账，不改任何行为）
    // ======================================================================

    /**
     * 原脚本 10936~10938 行的成功日志（不含 {@code [构思技艺] } 前缀）。
     *
     * <p>对账金标准（{@code docs/参考服日志_构思技艺.txt} 第 40 行）：
     * <pre>
     * [构思技艺] 武器原版近战已取消（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂）· 已启用 · 对玩家 照常
     * </pre>
     * ★ 注意括号里<b>只列了四把</b>（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂）——
     * 虽然 {@link #GS_WPN_IDS} 里有<b>五</b>个 ID（还含阎魔刀·改），
     * 但日志文案是<b>历史遗留</b>、没有同步更新。★ <b>照抄，不许「顺手修正」</b>：
     * 这是要逐字节对账的日志。
     */
    static String gsWpnStartLine(boolean nomelee, boolean pvp) {
        return "武器原版近战已取消（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂）· "
                + (nomelee ? "已启用" : "已停用")
                + " · 对玩家 " + (pvp ? "同样取消" : "照常");
    }
}
