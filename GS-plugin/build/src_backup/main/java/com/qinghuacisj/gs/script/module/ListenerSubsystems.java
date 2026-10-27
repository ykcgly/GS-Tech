package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.script.module.listener.AlSub;
import com.qinghuacisj.gs.script.module.listener.AuraSub;
import com.qinghuacisj.gs.script.module.listener.BbSub;
import com.qinghuacisj.gs.script.module.listener.BgSub;
import com.qinghuacisj.gs.script.module.listener.CherrySub;
import com.qinghuacisj.gs.script.module.listener.EcSub;
import com.qinghuacisj.gs.script.module.listener.EmSub;
import com.qinghuacisj.gs.script.module.listener.EntPurgeSub;
import com.qinghuacisj.gs.script.module.listener.KlSub;
import com.qinghuacisj.gs.script.module.listener.ListenerCore;
import com.qinghuacisj.gs.script.module.listener.MsSub;
import com.qinghuacisj.gs.script.module.listener.PlainSub;
import com.qinghuacisj.gs.script.module.listener.PsychicSub;
import com.qinghuacisj.gs.script.module.listener.SetBonusSub;
import com.qinghuacisj.gs.script.module.listener.SfSub;
import com.qinghuacisj.gs.script.module.listener.SiSub;
import com.qinghuacisj.gs.script.module.listener.VoSub;
import com.qinghuacisj.gs.script.module.listener.WlSub;
import com.qinghuacisj.gs.script.module.listener.WpnNoMeleeSub;
import com.qinghuacisj.gs.script.module.listener.WuliSub;

/**
 * {@code gs_listener.js} 的<b>子系统启动顺序表</b>。
 *
 * <p>★★ <b>为什么单独一个类</b>：原脚本 14,337 行里有 45 处顶层语句，
 * 其中 22 处是「启动一个子系统」（{@code gsXxxStart()}）。这些调用的
 * <b>相对顺序是可观察行为</b>：
 * <ul>
 *   <li>日志行的<b>先后顺序</b>（参考服日志里就是按这个顺序排列的）。</li>
 *   <li>谁先注册监听器 ⇒ 同优先级下谁先收到事件。</li>
 *   <li>谁先占住共享资源（世代号 / PDC 键 / 记分板标签）。</li>
 * </ul>
 * 把顺序集中在这一个方法里，好处是：①一眼能看出全貌 ②日后有人加子系统时
 * 被迫先想清楚「插在哪一步」。
 *
 * <p>★ 原脚本的顶层顺序（机械提取，行号即原脚本行号）：
 * <pre>
 *   200   runLater(…, 40)              灵感掉落补启动（本模块本体）
 *   644   gsRegisterDreamArmor();      梦境盔甲致命伤害拦截（本模块本体）
 *   875   gsAuraStart();               梦境套常驻粒子
 *  1117   gsSetBonusStart();           梦境套套装效果
 *  2044   gsPsychicStart();            开镜 + 余念绝响
 *  2809   gsCherryStart();             残樱
 *  2812   gsNoDurabilityRegisterSync() 永不掉耐久（本模块本体）
 *  4833   gsWuliStart();               五骊矟
 *  5833   gsWlStart();                 威龙
 *  5944   gsPlainStart();              基础构思
 *  6846   gsMsStart();                 月镰
 *  7789   gsEcStart();                 蚀月·死神梦镰
 *  8599   gsVoStart();                 致万千无言者
 *  9279   gsSiStart();                 此刻万籁俱寂
 * 10837   gsEmStart();                 阎魔刀·改
 * 10950   gsWpnNoMeleeStart();         武器禁近战
 * 12552   gsBbStart();                 篮球
 * 13167   gsBgStart();                 篮球生成器
 * 13563   gsKlStart();                 副手篮球钥匙锁
 * 13569   gsEntPurgeStart();           孤儿球清扫
 * 13671   gsSfStart();                 海水过滤器（钓鱼）
 * 14336   gsAlStart();                 祖宗发射器
 * </pre>
 *
 * <p>⚠️ <b>注意原脚本的一个真实陷阱</b>：它的函数<b>定义</b>位置与<b>调用</b>位置
 * 是分开的（函数定义在前、调用集中在中后段），所以「按行号顺序启动」时，
 * 某个子系统的定义可能在另一个子系统的调用点<b>之后</b>。
 * 例如 {@code gsEntPurge*} 的定义在 11708，但顶层调用在 13569。
 * 本表用的是<b>顶层调用</b>的行号。
 */
final class ListenerSubsystems {

    private ListenerSubsystems() {
    }

    /**
     * 按原脚本的顶层顺序启动全部子系统。
     *
     * @param module 宿主模块（子系统可能反向调用它的钩子，例如 {@code gsWuliQuit}）
     * @param core   共享状态与共享工具
     */
    static void startAll(ListenerModule module, ListenerCore core) {
        // ★ 顺序即行为，别重排（见类注释）。每行都标注了原脚本行号。

        // ---- ① 本模块本体：事件钩子 + 灵感掉落 + 免死 + 耐久保护 ----
        module.registerBukkitListeners();           // 等价于原脚本各处 registerEvent
        module.gsRegisterDreamArmor();              //  644 梦境盔甲致命伤害拦截
        module.scheduleInspirationCatchUp();        //  200 灵感掉落补启动（为已在线的玩家）

        // ---- ② 21 个子系统：严格按原脚本顶层调用顺序 ----
        //  各子系统在 listener 子包里，随移植推进逐个接入。
        //  接入时请保持下面的相对顺序，并在交付说明里确认行号。
        startSubsystems(module, core);
    }

    /**
     * 子系统启动区。★ 每接入一个子系统就在对应位置加一行，
     * <b>不要</b>为了「看起来整齐」而按字母序排列 —— 顺序是行为。
     */
    private static void startSubsystems(ListenerModule module, ListenerCore core) {
        new AuraSub(core).start();                     //  875  gsAuraStart();
        new SetBonusSub(core).start();                 // 1117  gsSetBonusStart();
        new PsychicSub(core).start();                  // 2044  gsPsychicStart();
        new CherrySub(core).start();                   // 2809  gsCherryStart();
        module.gsNoDurabilityRegisterSync();           // 2812  gsNoDurabilityRegisterSync()
        new WuliSub(core).start();                     // 4833  gsWuliStart();
        new WlSub(core).start();                       // 5833  gsWlStart();
        new PlainSub(core).start();                    // 5944  gsPlainStart();

        // ★ 6846/7789 必须两段式：月镰心跳会驱动蚀月（gsEcPlayerTick / gsEcBarsClean）。
        //   漏掉 bindEc ⇒ 蚀月的血条不刷新、也不清理 —— 静默故障，不报错。
        MsSub ms = new MsSub(core);
        EcSub ec = new EcSub(core);
        ms.bindEc(ec);
        ms.start();                                    // 6846  gsMsStart();
        ec.start();                                    // 7789  gsEcStart();

        new VoSub(core).start();                       // 8599  gsVoStart();
        new SiSub(core).start();                       // 9279  gsSiStart();
        new EmSub(core).start();                       // 10837 gsEmStart();
        new WpnNoMeleeSub(core).start();               // 10950 gsWpnNoMeleeStart();

        // ★ 篮球三件套共用状态表，靠构造器注入（不经 ListenerCore）。
        BbSub bb = new BbSub(core);
        BgSub bg = new BgSub(core, bb);
        bb.start();                                    // 12552 gsBbStart();
        bg.start();                                    // 13167 gsBgStart();
        new KlSub(core, bb, bg).start();               // 13563 gsKlStart();
        // ★ 孤儿球清扫**必须最后**：这一刻律师函与生成器都已建好自己的状态表，
        //   启动清扫「全删」时才不会误伤正在用的球（原脚本 13565~13568 的注释）。
        new EntPurgeSub(core, bb, bg).start();         // 13569 gsEntPurgeStart();

        new SfSub(core).start();                       // 13671 gsSfStart();
        new AlSub(core).start();                       // 14336 gsAlStart();
    }
}
