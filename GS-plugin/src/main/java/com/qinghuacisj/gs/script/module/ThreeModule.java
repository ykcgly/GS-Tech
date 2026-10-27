package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Spellcaster;
import org.bukkit.entity.Vex;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code gs_boss_three.js} 的 Java 化版本 —— 「妄念」（三阶段 boss · 唤魔者头召唤 · 消耗品）。
 *
 * <p>右键 {@code GS_WANGNIAN} = 召唤<b>第一阶段</b>；一只 boss 死掉 → 接下一只，
 * <b>三只全死才算打完</b>：
 * <ol>
 *   <li><b>幻念僧</b>（{@code PILLAGER} · 空手本体射击）—— {@code BT_S1_HP}(300) 血，
 *       基础伤害 {@code BT_S1_DMG}(15)；本体每 {@code BT_S1_ATK_CD}(1500ms) 射一支
 *       {@code BT_S1_ARROW_DMG}(12) 点的箭；技能① 追踪鳄鱼嘴（15 点 / 12 格 / 10 秒）、
 *       技能② 瞬影（读条 1 秒 → 玩家视角正后方 8 格 + 回 {@code BT_S1_BLINK_HEAL}(50) 血 / 20 秒）</li>
 *   <li><b>碎念执</b>（{@code VINDICATOR}）—— {@code BT_S2_HP}(800) 血，基础伤害 20；
 *       技能① 竖斩（35 点 / 4 格 / 破盾 / 10 秒）、技能② 星环飞斧（蓄力 1.5 秒 → 10 格 / 35 点 /
 *       直线不穿墙 / 撞到就停 → 落点生「妄想」/ 12 秒）</li>
 *   <li><b>妄念魔</b>（{@code RAVAGER}）—— {@code BT_S3_HP}(1000) 血，基础伤害 40；
 *       技能① 坠地（漂浮蓄力 1 秒 → 玩家头顶 → 悬停 1 秒 → 落地 3.5 格内 150 点 / 11 秒）</li>
 * </ol>
 *
 * <p>★★ <b>三阶段通用技能「锁链拽回」—— 反挖三填一</b>：读条 {@code BT_DRAG_READ}(20 刻 = 1 秒)，
 * 把 {@code BT_DRAG_RANGE}(64) 格内的玩家强制传送到 boss 脚下 {@code BT_DRAG_RING}(1.6) 格的
 * 一圈上（单次最多 {@code BT_DRAG_CAP}(8) 人，<b>全程无伤害</b>）。
 * 冷却 {@code BT_DRAG_CD}(18 秒)，但<b>出场延迟单独拉长到 {@code BT_DRAG_FIRST_MS}(10 秒)</b>
 * —— 与本阶段其他技能的 4 秒错开，避免「刚进阶段就被拽到脸上 + 同刻吃满斩击/飞斧」。
 * ★ <b>不设距离门槛</b>（{@code BT_DRAG_TRIG_S1/S2/S3} 全为 {@code 0.0}）：触发条件是
 * 「这一轮还有没被拉过的人」，与距离无关 —— 挖三填一的玩家就在 1～2 格内，
 * 早期版本「4 格内不拉」({@code BT_DRAG_MIN}) 与「有人在射程外」两条规则都恰好放过了他。
 *
 * <p>★★ <b>掉落只在特定阶段</b>：
 * <ul>
 *   <li>{@code GS_WANGXIANG_FRAGMENT}（妄想碎片）<b>只有第三阶段</b>掉 {@code BT_FRAG_DROP[2]}(6) 个，
 *       一/二阶段 {@code 0} 个；</li>
 *   <li>{@code GS_CHERRY}（残樱）<b>只有第二阶段</b>掉 {@code BT_CHERRY_DROP[1]}(1) 把，
 *       一/三阶段 {@code 0} 把。</li>
 * </ul>
 * ★ <b>「删除残樱配方」在 Java 侧的等价实现</b>：配方数据在 {@code content/mb_machines.yml}
 * （构思机）里 —— 那一条已删成墓碑注释，{@code content/items.yml} 的 {@code GS_CHERRY}
 * 改挂 {@code recipe_type: GS_RT_WANGNIAN_S2}（{@code content/recipe_types.yml} 里定义的
 * 「不绑定任何机器」的提示型配方类型）+ 不写 {@code recipe:} 九宫格 ⇒
 * {@code RecipeMachineLoader} 读不到这条配方 ⇒ 发射器里合不出来、配方页左边显示获取提示。
 * 也就是说这个动作<b>完全落在 yml 里，模块里没有对应代码</b>（探针里有三条断言钉住它）。
 *
 * <p>★ <b>妄想</b>（恼鬼 Vex）：伤害 {@code BT_S2_VEX_DMG}(7)、血量 {@code BT_S2_VEX_HP}(25)、
 * {@code BT_S2_VEX_LIFE}(180 刻 = 9 秒) 后处死，场上最多 {@code BT_S2_VEX_MAX}(3) 只；
 * <b>与卫道士同队</b>（同一个 {@code gs:boss3} PDC 标记，值 {@code wangnian2_vex}）
 * ⇒ {@code btOurs} 为真 ⇒ 斩击 / 飞斧的命中判定直接跳过它，伤害事件里也互相免伤、不会互相索敌。
 *
 * <p>★★★ <b>不照抄的死代码</b>（原脚本里走不到的分支，逐条确认过）：
 * <ol>
 *   <li><b>{@code EntityCastSpellEvent} 整块</b>：{@code btRegisterSpell} 的 ① 用
 *       {@code Java.type('org.bukkit.event.entity.EntityCastSpellEvent')}，
 *       而该类在 <b>paper-api 1.21.11 里根本不存在</b>（已 javap 核实）⇒
 *       {@code Java.type} 抛异常被 {@code catch (e) { }} 吞掉，那一块从未注册成功。
 *       ⇒ Java 侧不实现它，{@code ok} 计数保持一致（<b>3 条</b>：伤害 / 血量 / 阵营）。
 *       删掉它<b>不改变任何可观察行为</b>（本来就没注册上）。</li>
 *   <li>{@code btPurgeVanilla} 里的 {@code Spellcaster} 分支：阶段 1 已经是
 *       <b>掠夺者</b>（2026-10-06 由唤魔者改的），掠夺者不是 {@code Spellcaster}
 *       ⇒ {@code ent instanceof SC} 恒为假。保留 {@code instanceof} 判定（行为等价），
 *       但<b>不可能生效</b>。</li>
 *   <li>{@code btFindMaxAttr} 的「遍历 {@code Attribute.values()} 模糊匹配
 *       {@code MAX_HEALTH} / {@code max_health}」退路：1.21.11 上
 *       {@code Attribute.MAX_HEALTH} 是直接可用的静态字段 ⇒ 第一候选就命中，退路走不到。</li>
 *   <li>{@code btP} / {@code btET} / {@code btAttrKey} 的多候选名探测：第一个候选名在
 *       1.21.11 上都存在 ⇒ 后续候选走不到（候选表照抄，语义是「先命中先返回」）。</li>
 *   <li>{@code btSound} 的第二条退路 {@code loc.getWorld().playSound(...)}：
 *       {@code World#playSound(Location, String, float, float)} 不会抛 ⇒ 走不到。</li>
 *   <li>{@code btBarMake} 里 {@code BarColor.valueOf('RED')} / {@code BarStyle.valueOf(...)}
 *       的退路：静态字段直接可用 ⇒ 走不到。</li>
 *   <li>{@code btMusicPlayTo} 的 enum / {@code loc.getWorld().playSound(p, ...)} 两条退路，
 *       以及 {@code btMusicStopTo} 的 {@code SoundCategory.RECORDS} 兜底：
 *       {@code Player#playSound(Location, String, float, float)} /
 *       {@code Player#stopSound(String)} 在 1.21.11 上都可用 ⇒ 走不到。</li>
 *   <li>{@code btMusicTick} 里 {@code if (!okp && rec == null)} 那条
 *       「本服没有音效 X，该阶段 BGM 不播放」的告警：三首曲子
 *       （{@code MUSIC_DISC_PRECIPICE} / {@code _CREATOR} / {@code _CREATOR_MUSIC_BOX}）
 *       在 1.21.11 的 {@code Sound} 里都在（已 javap 核实）⇒ 走不到。代码保留。</li>
 *   <li>{@code btAxeMake} 的 {@code Mat.valueOf('IRON_AXE')} 与 {@code btBreakShield} 的
 *       {@code Mat.valueOf('SHIELD')} 退路：{@code Material.IRON_AXE} / {@code Material.SHIELD}
 *       直接可用 ⇒ 走不到。</li>
 *   <li>{@code btS1Shoot} 里 {@code world.spawnArrow} 失败后「手动 spawn 一支 ARROW」的退路：
 *       1.21.11 的 {@code World#spawnArrow} 不会抛 ⇒ 走不到。★ 仍然保留（成本低、语义清楚）。</li>
 *   <li>{@code btHurt} 的 {@code ent.damage(dmg)} 退路：原脚本传 {@code null} 当来源时走它
 *       —— Java 侧等价物是 {@code src == null} 时直接调 {@code damage(double)}（保留）。</li>
 *   <li>★★ <b>{@code LootTables.EMPTY} 那三句"不掉东西"</b>：{@code org.bukkit.loot.LootTables}
 *       在 paper-api 1.21.11 里<b>没有 {@code EMPTY} 这个常量</b>（已 javap 核实），
 *       而原脚本写的是 {@code if (LootTables.EMPTY != null) e.setLootTable(LootTables.EMPTY);}
 *       ⇒ JS 侧拿到 {@code undefined}，{@code undefined != null} 为 <b>false</b>
 *       ⇒ <b>这三句从未生效过</b>。原生版<b>不设掉落表</b>（照抄"从未生效"这个事实），
 *       真正的"不掉东西"由 {@code DeathListener} 清 {@code drops} 完成。</li>
 *   <li>{@code btNow()} 的三层降级（{@code Instant} → {@code Date} → {@code 0}）：
 *       那是为了绕开 RSC 屏蔽 {@code System}；Java 直接用 {@link Guards#now()}。</li>
 * </ol>
 *
 * <p>★ <b>与原脚本的差异</b>（逐条见交付说明）：{@code getServer()} → {@code Bukkit.getServer()}、
 * {@code runLater} → {@link GS#runLater}、{@code getSfItemById} → {@link GS#sfItem}、
 * 监听器注册的宿主插件从 RSC/Slimefun 换成 {@link GS#plugin()}（原生版不依赖 RSC）、
 * 方块交互拦截改用公共的 {@link InteractGuard}（同一份逻辑，见 §4 公共代码）。
 * <b>所有数值 / 概率 / 刻度 / 音效名 / 粒子名 / PDC 键 / 物品 id 一字未动。</b>
 */
public final class ThreeModule extends AbstractModule {

    // ==================================================================
    // 参数（想改数值改这里就够了）
    // ★ 字段名 = 原脚本顶层 var 名（常量对账探针按名反射比对）
    // ==================================================================

    static final String BT_ITEM_ID = "GS_WANGNIAN";
    static final boolean BT_CONSUME = true;             // ★ 消耗品
    static final double BT_SPAWN_AHEAD = 3.0;           // 在玩家身前 3 格生成
    static final int BT_TICK = 5;                       // AI 心跳：每 5 刻（0.25 秒）一次
    static final long BT_FIRST_MS = 4000L;              // ★ 每个阶段出场 4 秒内**不放技能**
    static final double BT_SEEK = 32.0;                 // 找目标的范围
    static final boolean BT_CREATIVE_FALLBACK = true;   // ★ 场上没有非创造玩家时，是否允许打创造模式

    // ---------------- ① 幻念僧（**掠夺者** PILLAGER · 本体射击） ----------------
    static final String BT_S1_NAME = "\u00a7f\u5e7b\u5ff5\u50e7";   // §f幻念僧
    static final double BT_S1_HP = 300.0;
    static final double BT_S1_DMG = 15.0;               // 基础（近战）伤害
    static final int BT_S1_HEIGHT = 2;                  // 站进去要几格净空（掠夺者 ≈ 2 格高）
    static final double BT_S1_ARROW_DMG = 12.0;         // ★ 每支箭的伤害 **12 点**
    static final double BT_S1_ARROW_SPEED = 3.0;        // 箭速（原版满蓄力弓 ≈ 3.0）
    static final long BT_S1_ATK_CD = 1500L;             // ★ **每 1.5 秒发射一发**
    static final double BT_S1_ATK_RANGE = 24.0;         // 超过这个距离就不射了

    static final double BT_S1_FANG_DMG = 15.0;          // 追踪鳄鱼嘴：15 点
    static final double BT_S1_FANG_RANGE = 12.0;        // 最远飞 12 格
    static final long BT_S1_FANG_CD = 10000L;           // 冷却 10 秒
    static final double BT_S1_FANG_SPEED = 0.42;        // 每刻推进多少格
    static final double BT_S1_FANG_R = 1.2;             // 咬到人判定半径
    static final int BT_S1_FANG_LIFE = 80;              // 最多活 80 刻（4 秒）自毁
    static final int BT_S1_FANG_SPAWN = 3;              // 每隔几刻丢一团新的尖牙

    static final long BT_S1_BLINK_CD = 20000L;          // 冷却 20 秒
    static final int BT_S1_BLINK_READ = 20;             // 读条 20 刻 = 1 秒
    static final double BT_S1_BLINK_BACK = 8.0;         // 出现在玩家**视角正后方** 8 格
    static final boolean BT_S1_BLINK_BEHIND = true;     // true = 背后；false = 视线正前方
    static final double BT_S1_BLINK_HEAL = 50.0;        // 恢复 **50 点**生命

    // ---------------- ② 碎念执（卫道士） ----------------
    static final String BT_S2_NAME = "\u00a7f\u788e\u5ff5\u6267";   // §f碎念执
    static final double BT_S2_HP = 800.0;
    static final double BT_S2_DMG = 20.0;
    static final int BT_S2_HEIGHT = 2;

    static final double BT_S2_SLASH_DMG = 35.0;         // 斩击伤害 **35 点**
    static final long BT_S2_SLASH_CD = 10000L;          // 冷却 **10 秒**
    static final double BT_S2_SLASH_RANGE = 4.0;        // 射程 4 格
    static final double BT_S2_SLASH_SPEED = 0.5;        // 弹道速度：4 格只要 8 刻
    static final int BT_S2_SLASH_WINDUP = 3;            // ★ 举刀刻数：这 3 刻只出粒子不锁方向
    static final double BT_S2_SLASH_KB = 0.95;          // 击退力度（≈ 推出 3 格）
    static final double BT_S2_SLASH_UP = 0.35;          // 顺带挑飞一点点
    static final double BT_S2_SLASH_HALF_W = 1.1;       // 左右判定宽（各 1.1 格）
    static final double BT_S2_SLASH_H = 1.6;            // 竖直刀光的上下范围（各 1.6 格）
    static final int BT_S2_SLASH_SHIELD_CD = 100;       // 破盾：放 100 刻（5 秒）的举盾冷却

    // ---------------- ②-2 碎念执 · 技能 2：星环飞斧 ----------------
    static final long BT_S2_AXE_CD = 12000L;            // 冷却 12 秒
    static final int BT_S2_AXE_CHARGE = 30;             // 环绕星星粒子 30 刻 = 1.5 秒
    static final double BT_S2_AXE_RANGE = 10.0;         // 最远 10 格
    static final double BT_S2_AXE_DMG = 35.0;           // 斧子伤害 35 点
    static final double BT_S2_AXE_SPEED = 0.62;         // 每刻推进多少格
    static final double BT_S2_AXE_KB = 0.42;            // 小小击退
    static final double BT_S2_AXE_UP = 0.18;            // 顺带挑飞一点点
    static final double BT_S2_AXE_R = 1.05;             // 命中判定半径
    static final int BT_S2_AXE_STAR_N = 12;             // 环绕粒子数量
    static final double BT_S2_AXE_STAR_R = 1.15;        // 环绕半径
    static final double BT_S2_AXE_HALF = 0.85;          // 飞行高度 = 生成点 +0.85
    static final double BT_S2_AXE_TRIGGER = 13.0;       // 目标多远以内才放

    static final double BT_S2_VEX_HP = 25.0;            // 妄想血量 25
    static final double BT_S2_VEX_DMG = 7.0;            // 妄想伤害 7
    static final int BT_S2_VEX_LIFE = 180;              // 9 秒 = 180 刻后处死
    static final int BT_S2_VEX_MAX = 3;                 // 场上最多几只
    static final String BT_S2_VEX_TAG = "wangnian2_vex"; // ★ 与卫道士同队的标记值

    // ---------------- ★★★ 通用技能：「锁链拽回」—— 三个阶段共用 ----------------
    static final long BT_DRAG_CD = 18000L;              // 冷却 18 秒（三阶段共用同一套值）★ 内置 CD，**不改**
    static final long BT_DRAG_FIRST_MS = 10000L;        // ★ 锁链出场延迟：进入阶段后 **10 秒**才允许第一次拽人
    static final int BT_DRAG_READ = 20;                 // 读条 20 刻 = 1 秒
    static final double BT_DRAG_RANGE = 64.0;           // ★ 64 格内全部锁
    static final double BT_DRAG_RING = 1.6;             // ★ 落点半径：贴到 boss 身边
    static final double BT_DRAG_HEIGHT = 0.0;           // 落点相对 boss 脚下（0 = 站在 boss 同一地面）
    static final int BT_DRAG_CAP = 8;                   // 单次最多拉几个
    // ★ 各阶段的"多远以外才放"门槛（**默认 0 = 不设门槛，64 格内一律拉**）
    static final double BT_DRAG_TRIG_S1 = 0.0;          // 幻念僧
    static final double BT_DRAG_TRIG_S2 = 0.0;          // 碎念执
    static final double BT_DRAG_TRIG_S3 = 0.0;          // 妄念魔
    // ★ 2026-10-07：原本有个 `BT_DRAG_MIN = 4.0`「4 格内不拉」，**已按需求删除** ——
    //   挖三填一的玩家就在 1～2 格内，这条规则恰好把唯一需要被拉的人放过了。
    //   （反向断言见探针：本类<b>不得</b>存在 BT_DRAG_MIN 字段。）

    // ---------------- ③ 妄念魔（劫掠兽） ----------------
    static final String BT_S3_NAME = "\u00a7f\u5984\u5ff5\u9b54";   // §f妄念魔
    static final double BT_S3_HP = 1000.0;
    static final double BT_S3_DMG = 40.0;
    static final int BT_S3_HEIGHT = 3;

    static final double BT_S3_SLAM_DMG = 150.0;         // 落地 **150 点**
    static final double BT_S3_SLAM_R = 3.5;             // 范围 3.5 格
    static final long BT_S3_SLAM_CD = 11000L;           // 冷却 **11 秒**
    static final int BT_S3_SLAM_CHARGE = 20;            // 原地漂浮蓄力 20 刻 = 1 秒
    static final int BT_S3_SLAM_HOVER = 20;             // 出现在头顶后悬停 20 刻 = 1 秒
    static final double BT_S3_SLAM_RISE = 1.0;          // 蓄力期间往上飘多少格
    static final double BT_S3_SLAM_ABOVE = 3.0;         // 出现在玩家头顶**上方**几格
    static final int BT_S3_SLAM_DROP = 8;               // 从悬停点落地用几刻

    // ★ 2026-10-06：**原技能 2「龙息弹」已按需求删除**（妄念魔现在只有坠地一个技能）
    //   ⇒ 本类<b>不得</b>存在 BT_S3_FIRE_* 系列常量（反向断言见探针）。

    // ---------------- 血量保护 ----------------
    static final long BT_HP_FIX_MS = 6000L;             // 出生后 6 秒内**每拍**重试

    // ---------------- 转场演出 ----------------
    static final long BT_TRANS1_MS = 3000L;             // 幻念僧死 → 羊驼口水烟雾 3 秒 → 碎念执
    static final long BT_TRANS2_MS = 3000L;             // 碎念执死 → 灵魂沙柱 3 秒 → 妄念魔
    static final double BT_SOUL_HEIGHT = 22.0;          // 灵魂沙柱冲上去 22 格
    static final int BT_TRANS_TICKS = 60;               // 转场演出总共 60 刻（3 秒）

    // ---------------- 阶段 BGM ----------------
    static final boolean BT_MUSIC = true;               // false = 完全不放 BGM
    static final double BT_MUSIC_RANGE = 64.0;          // boss 多少格内听得到
    static final double BT_MUSIC_VOL = 4.0;             // 音量（4.0 = 唱片机原版音量）
    static final String[] BT_MUSIC_SOUND = {
            "music_disc.precipice",                     // ① 幻念僧 · Aaron Cherof - Precipice
            "music_disc.creator",                       // ② 碎念执 · Lena Raine - Creator
            "music_disc.creator_music_box"};            // ③ 妄念魔 · Creator（八音盒）
    static final long[] BT_MUSIC_LOOP_MS = {299000L, 176000L, 73000L};
    static final String[] BT_MUSIC_FALLBACK = {
            "music_disc.relic", "music_disc.pigstep", "music_disc.ward"};

    // ---------------- 掉落物 ----------------
    static final String BT_FRAG_ID = "GS_WANGXIANG_FRAGMENT";
    /** 下标 = 阶段 - 1。★ 一阶段 0 个 / 二阶段 0 个 / **三阶段 6 个**（0 = 这一阶段不掉）。 */
    static final int[] BT_FRAG_DROP = {0, 0, 6};

    static final String BT_CHERRY_ID = "GS_CHERRY";
    /** 下标 = 阶段 - 1。★ 一阶段 0 把 / **二阶段 1 把** / 三阶段 0 把。 */
    static final int[] BT_CHERRY_DROP = {0, 1, 0};

    // ---------------- 血条 ----------------
    static final boolean BT_BAR = true;
    static final boolean BT_BAR_ONLY_OWNER = true;      // true = 只有召唤者看得到
    static final double BT_BAR_RANGE = 64.0;

    // ==================================================================
    // 运行期状态 —— **非静态**（实例 = 原脚本的一次顶层求值）
    // ==================================================================

    /** 玩家 uuid → ref（**一条线**：三个阶段共用同一个 ref）。 */
    private final Map<String, Ref> BT_ACTIVE = new HashMap<>();
    /** 玩家 uuid → 上次召唤时间戳（防一次点击被派发两遍）。 */
    private final Map<String, Long> BT_LAST = new HashMap<>();

    /** PDC 标记：{@code gs:boss3}（值 'wangnian1' / 'wangnian2' / 'wangnian3' / 'wangnian2_vex'）。 */
    private NamespacedKey BT_KEY;
    /** PDC 标记：{@code gs:boss3_fang}（我们自己用来做特效的尖牙）。 */
    private NamespacedKey BT_FANG_KEY;

    /** 每个实体只打一次血量失败日志，别刷屏。 */
    private final Set<String> BT_HP_DIAG = new HashSet<>();

    /** MAX_HEALTH 属性键的缓存（{@code BT_MAXATTR}）。 */
    private Attribute BT_MAXATTR;

    /** 每阶段"本服真的有"的音效名（缓存，{@code BT_MUSIC_PICKED}）。 */
    private final String[] BT_MUSIC_PICKED = new String[3];

    /** 监听器闩锁（原脚本的 BT_REG_DEATH / BT_REG_SPELL / BT_GUARD_OK）。 */
    private boolean BT_REG_DEATH = false;
    private boolean BT_REG_SPELL = false;
    private boolean BT_GUARD_OK = false;

    public ThreeModule() {
        // ★ 构造器 = 原脚本的**顶层代码**：日志 + 3 秒后的 reload 残留清扫
        try {
            Bukkit.getLogger().info(loadLine());
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        try {
            BT_KEY = NamespacedKey.fromString("gs:boss3");
        } catch (Throwable e) {
            BT_KEY = null;
        }
        try {
            BT_FANG_KEY = NamespacedKey.fromString("gs:boss3_fang");
        } catch (Throwable e) {
            BT_FANG_KEY = null;
        }

        // ★★ 启动自检（原脚本顶层直接打；看不到这行 = 跑的还是旧脚本）
        try {
            Bukkit.getLogger().info(hpLine());
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            Bukkit.getLogger().info(axeLine());
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            Bukkit.getLogger().info(fragLine());
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            Bukkit.getLogger().info(cherryLine());
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            Bukkit.getLogger().info(dragLine());
        } catch (Throwable ignored) {
            // 静默
        }

        // ★ reload 残留清扫：启动 3 秒后扫一遍全世界
        try {
            GS.runLater(() -> {
                try {
                    btSweepReloadLeftovers();
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            }, 60);
        } catch (Throwable ignored) {
            // 静默
        }
    }

    @Override
    public String name() {
        return "gs_boss_three";
    }

    // ==================================================================
    // 加载日志行 —— 抽成 static 方法只为了让探针能**逐字节**对账
    //   ★ 原脚本全部用**字符串拼接**（不是 info(String, Object...) 的参数化重载）
    //     ⇒ 这几行里**没有任何 %s 瑕疵**（值都拼出来了），逐字保留。
    // ==================================================================

    /**
     * ★★ <b>本模块会打出的<b>全部</b>加载日志行，按打出顺序</b>。
     *
     * <p>存在的唯一目的是让探针能钉住「本模块的加载日志集合<b>恰好</b>等于这 6 行」
     * —— 日后有人"顺手多补一行"会被测试拦住。
     *
     * <p>★ {@code gs_xxx 已加载} 这一行<b>是脚本自己打的、不是框架统一打的</b>：
     * {@code gs_boss_three.js} 第 108 行有（已逐字节核对，见 {@link #loadLine()}），
     * 而 {@code gs_boss_nightwatch.js} / {@code gs_boss_shadow.js} 全文零命中。
     * 所以这里<b>只有</b>本脚本真正打的那 6 行，一条都不多。
     */
    static String[] loadLogLines() {
        return new String[]{
                loadLine(),      // ① 第 108 行（顶层，最早）
                hpLine(),        // ② 三阶段目标血量
                axeLine(),       // ③ 碎念执 技能2 星环飞斧
                fragLine(),      // ④ 妄想碎片掉落
                cherryLine(),    // ⑤ 残樱掉落 + 配方已删除
                dragLine(),      // ⑥ 锁链拽回
        };
    }

    /** {@code gs_boss_three.js} 第 108 行的加载标记。 */
    static String loadLine() {
        return "[构思技艺] gs_boss_three 已加载（v2026-10-07c 锁链出场延迟10秒·坠击11秒冷却）";
    }

    /** 三阶段目标血量自检（原脚本顶层 3251~3252 行）。 */
    static String hpLine() {
        return "[构思技艺] 妄念 三阶段目标血量：幻念僧=" + btNum(BT_S1_HP)
                + "（掠夺者·弩） 碎念执=" + btNum(BT_S2_HP)
                + " 妄念魔=" + btNum(BT_S3_HP);
    }

    /** 碎念执「星环飞斧」参数自检（原脚本顶层 3257~3260 行）。 */
    static String axeLine() {
        return "[构思技艺] 碎念执 技能2 星环飞斧：蓄力=" + btNum(BT_S2_AXE_CHARGE / 20.0)
                + "秒 射程=" + btNum(BT_S2_AXE_RANGE) + "格 伤害=" + btNum(BT_S2_AXE_DMG)
                + " 冷却=" + btNum(BT_S2_AXE_CD / 1000.0) + "秒 ｜ 妄想：伤害=" + btNum(BT_S2_VEX_DMG)
                + " 血量=" + btNum(BT_S2_VEX_HP) + " 存活=" + btNum(BT_S2_VEX_LIFE / 20.0)
                + "秒（与卫道士同队）";
    }

    /** 妄想碎片掉落自检（原脚本顶层 3268~3270 行）。 */
    static String fragLine() {
        return "[构思技艺] 妄念 掉落：妄想碎片 (" + BT_FRAG_ID + ")"
                + " **只有第三阶段（妄念魔）**掉 " + BT_FRAG_DROP[2] + " 个"
                + " ｜ 一阶段 " + BT_FRAG_DROP[0] + " 个 / 二阶段 " + BT_FRAG_DROP[1] + " 个（都不掉）";
    }

    /** 残樱掉落自检（原脚本顶层 3274~3277 行）。 */
    static String cherryLine() {
        return "[构思技艺] 妄念 掉落：残樱 (" + BT_CHERRY_ID + ")"
                + " **只有第二阶段（碎念执）**掉 " + BT_CHERRY_DROP[1] + " 把"
                + " ｜ 一阶段 " + BT_CHERRY_DROP[0] + " 把 / 三阶段 " + BT_CHERRY_DROP[2] + " 把（都不掉）"
                + " ｜ 配方已删除（recipe_type = GS_RT_WANGNIAN_S2）";
    }

    /** 通用「锁链拽回」参数自检（原脚本顶层 3282~3286 行）。 */
    static String dragLine() {
        return "[构思技艺] 锁链拽回（三阶段通用·反挖三填一）：出场延迟=" + btNum(BT_DRAG_FIRST_MS / 1000.0)
                + "秒 冷却=" + btNum(BT_DRAG_CD / 1000.0)
                + "秒 读条=" + btNum(BT_DRAG_READ / 20.0) + "秒 范围=" + btNum(BT_DRAG_RANGE)
                + "格 落点半径=" + btNum(BT_DRAG_RING) + " 单次最多=" + BT_DRAG_CAP
                + "人 ｜ 不设距离门槛（64 格内一律拉）";
    }

    /**
     * JS 的 {@code '' + 数值} —— 整数不带 {@code .0}，小数按最短表示。
     * 只用于上面那几行日志（值域都是 0~299000 的有限数）。
     */
    static String btNum(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1.0e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    // ==================================================================
    // 纯几何 / 数学（★ 抽成 static 是为了让探针能在**大量输入**上逐值比对）
    // ==================================================================

    /** 方块通行判定的抽象 —— 探针用它喂**合成地形**，生产代码用真实 {@link World}。 */
    interface BtBlock {
        boolean passable(int bx, int by, int bz);
    }

    /** 把一个真实世界包成 {@link BtBlock}。 */
    static BtBlock btBlockOf(World world) {
        return (bx, by, bz) -> btPassableAt(world, bx, by, bz);
    }

    /**
     * 方块的通行判定（★ 用 {@code isPassable}：**玻璃挡得住**）。
     *
     * <p>逐字照抄原脚本：空气 ⇒ 通行；否则 {@code isPassable()}；再否则
     * {@code !isSolid()}；全失败 ⇒ 通行。
     */
    static boolean btPassableAt(World world, int bx, int by, int bz) {
        try {
            org.bukkit.block.Block blk;
            try {
                blk = world.getBlockAt(new Location(world, 0 + bx, 0 + by, 0 + bz));
            } catch (Throwable e) {
                return true;
            }
            if (blk == null) {
                return true;
            }
            Material mt = null;
            try {
                mt = blk.getType();
            } catch (Throwable e) {
                mt = null;
            }
            if (mt != null) {
                try {
                    if (mt.isAir()) {
                        return true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                return blk.isPassable();
            } catch (Throwable ignored) {
                // ★ 落到下面的退路（1.21.11 上走不到，保留结构）
            }
            if (mt != null) {
                try {
                    return !mt.isSolid();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return true;
        } catch (Throwable e) {
            return true;
        }
    }

    /** {@code (bx, by, bz)} 往上 {@code height} 格够不够站。 */
    static boolean btHeadroom(BtBlock p, int bx, int bz, int by, int height) {
        try {
            for (int i = 0; i < height; i++) {
                if (!p.passable(bx, by + i, bz)) {
                    return false;
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 找一个能站的落地高度（先往下找实地，找不到就往上抬）。
     *
     * <p>★ 逐字照抄原脚本的扫描边界：{@code lo = start - 4}（下限钳 −60）、
     * {@code hi = start + 8}（上限钳 250）；两轮都失败 ⇒ 返回<b>原始的 y0</b>。
     */
    static double btSafeY(BtBlock p, double x, double z, double y0, int height) {
        try {
            int bx = (int) Math.floor(x);
            int bz = (int) Math.floor(z);
            int start = (int) Math.floor(y0);
            int lo = start - 4;
            int hi = start + 8;
            if (lo < -60) {
                lo = -60;
            }
            if (hi > 250) {
                hi = 250;
            }
            int y;
            for (y = start; y >= lo; y--) {
                if (btHeadroom(p, bx, bz, y, height) && !p.passable(bx, y - 1, bz)) {
                    return y;
                }
            }
            for (y = start; y <= hi; y++) {
                if (btHeadroom(p, bx, bz, y, height)) {
                    return y;
                }
            }
            return y0;
        } catch (Throwable e) {
            return y0;
        }
    }

    /** {@code btSafeY} 的真实世界版本。 */
    static double btSafeY(World world, double x, double z, double y0, int height) {
        return btSafeY(btBlockOf(world), x, z, y0, height);
    }

    /** 三维归一化；{@code n < min} 时返回 {@code null}（对应原脚本的 {@code return}）。 */
    static double[] btNorm3(double dx, double dy, double dz, double min) {
        double n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(n >= min)) {
            return null;
        }
        return new double[]{dx / n, dy / n, dz / n};
    }

    /** 步长钳制：{@code step > remain ⇒ remain}。 */
    static double btClampStep(double step, double remain) {
        return step > remain ? remain : step;
    }

    /** 冷却是否到点：{@code (now - last) >= cd}。 */
    static boolean btCdOk(long now, long last, long cd) {
        return (now - last) >= cd;
    }

    /** 血条进度 = 血量 / 上限（上限 <= 0 ⇒ 用 ref.maxHp，再兜 1）。 */
    static double btBarProgress(double hp, double mx, double refMaxHp) {
        double m = mx;
        if (m <= 0) {
            m = refMaxHp;
        }
        return hp / (m > 0 ? m : 1);
    }

    /** 血条进度钳到 [0, 1]。 */
    static double btClamp01(double v) {
        double r = v;
        if (r < 0) {
            r = 0;
        }
        if (r > 1) {
            r = 1;
        }
        return r;
    }

    /** 召唤点：玩家身前 {@code ahead} 格（yaw 的水平前向 = {@code (-sin, cos)}）。 */
    static double[] btSpawnAhead(double x, double z, double yaw, double ahead) {
        double rad = yaw * Math.PI / 180.0;
        return new double[]{x + (-Math.sin(rad)) * ahead, z + (Math.cos(rad)) * ahead};
    }

    /**
     * 瞬影落点（水平分量）。
     *
     * <p>玩家视线水平方向 {@code (-sin, cos)}；{@code behind} ⇒ 取反
     * ⇒ 落到「玩家视角正后方 {@code back} 格」。
     */
    static double[] btBlinkTarget(double px, double pz, double yaw, double back, boolean behind) {
        double rad = yaw * Math.PI / 180.0;
        double fx = -Math.sin(rad);
        double fz = Math.cos(rad);
        if (behind) {
            fx = -fx;
            fz = -fz;
        }
        return new double[]{px + fx * back, pz + fz * back};
    }

    /**
     * 水平指向：从 {@code (fx,fz)} 指向 {@code (tx,tz)} 的单位向量。
     * {@code n < 1.0e-4} ⇒ 退回 {@code yawFallback} 的朝向（原脚本的兜底分支）。
     */
    static double[] btDirTo(double fx, double fz, double tx, double tz, double yawFallback) {
        double dx = tx - fx;
        double dz = tz - fz;
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) {
            double rad = yawFallback * Math.PI / 180.0;
            return new double[]{-Math.sin(rad), Math.cos(rad)};
        }
        return new double[]{dx / n, dz / n};
    }

    /**
     * 追踪鳄鱼嘴每刻的推进。
     *
     * <p>返回 {@code {nx, ny, nz, step, dx, dz, dy}}；{@code n < 1.0e-4} 时返回 {@code null}
     * （原脚本那个分支是「自毁」）。★ {@code dy} 是**推进前**算出来的高度差，
     * 命中判定用的就是它。
     */
    static double[] btFangStep(double x, double y, double z,
                               double tx, double ty, double tz, double speed) {
        double dx = tx - x;
        double dz = tz - z;
        double dy = (ty + 0.6) - y;
        double n = Math.sqrt(dx * dx + dz * dz);
        if (n < 1.0e-4) {
            return null;
        }
        double step = speed;
        if (step > n) {
            step = n;
        }
        double nx = x + dx / n * step;
        double nz = z + dz / n * step;
        // ★ 缓慢对齐高度，别一下贴脸（±0.12 钳制）
        double ny = y + Math.max(-0.12, Math.min(0.12, dy / n * step));
        return new double[]{nx, ny, nz, step, dx, dz, dy};
    }

    /** 鳄鱼嘴咬到人的判定：水平 ≤ {@code BT_S1_FANG_R} 且竖直 ≤ 1.6。 */
    static boolean btFangHit(double dx, double dz, double dy) {
        return Math.sqrt(dx * dx + dz * dz) <= BT_S1_FANG_R && Math.abs(dy) <= 1.6;
    }

    /** 竖斩的竖直命中闸门：{@code |实体脚 + 0.9 − 刀光高度| <= BT_S2_SLASH_H}。 */
    static boolean btSlashHeightOk(double entY, double slashY) {
        return Math.abs(entY + 0.9 - slashY) <= BT_S2_SLASH_H;
    }

    /** 坠地·蓄力期的抬升量（★ {@code i / CHARGE} 是**浮点**除法）。 */
    static double btSlamRise(int i) {
        return BT_S3_SLAM_RISE * (i / (double) BT_S3_SLAM_CHARGE);
    }

    /** 坠地·落地段的 Y（{@code t01} 钳到 1，平方 ⇒ 越掉越快）。 */
    static double btSlamDropY(double sy, double gy, int i) {
        double t01 = i / (double) BT_S3_SLAM_DROP;
        if (t01 > 1) {
            t01 = 1;
        }
        return sy + (gy - sy) * t01 * t01;
    }

    /** 飞斧盔甲架的朝向（★ 边飞边转：{@code + traveled * 46}）。 */
    static double btAxeStandYaw(double dx, double dz, double traveled) {
        return Math.atan2(-dx, dz) * 180.0 / Math.PI + traveled * 46.0;
    }

    /** 飞斧出生时盔甲架的朝向。 */
    static double btAxeSpawnYaw(double dx, double dz) {
        return Math.atan2(-dx, dz) * 180.0 / Math.PI;
    }

    /** 飞斧的撞墙判定（★ 用 isPassable：**玻璃挡得住**）。 */
    static boolean btAxeBlocked(BtBlock p, double x, double y, double z, double nx, double nz) {
        if (!p.passable((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
            return true;
        }
        if (!p.passable((int) Math.floor(x), (int) Math.floor(y + 0.4), (int) Math.floor(z))) {
            return true;
        }
        if (!p.passable((int) Math.floor(nx), (int) Math.floor(y), (int) Math.floor(nz))) {
            return true;
        }
        if (!p.passable((int) Math.floor(nx), (int) Math.floor(y + 0.4), (int) Math.floor(nz))) {
            return true;
        }
        return false;
    }

    /**
     * 锁链落点：第 {@code i} 个人（共 {@code cnt} 个）的第 {@code tryN} 次尝试。
     *
     * <p>以 boss 朝向为基准均匀铺开：{@code ang = yaw0 + 2π·i/cnt}；
     * 正前方那个点是 {@code (bx + cos·RING, bz + sin·RING)}；
     * {@code tryN > 0} 时沿**切线方向**左右挪
     * {@code spread = ±ceil(tryN/2)·0.8}（躲开墙）。
     *
     * @return {@code {ax, az, ay}}；该点被方块挡住 ⇒ {@code null}（原脚本的 {@code continue}）
     */
    static double[] btDragSlot(BtBlock p, double bx, double bz, double sy, double yaw0,
                               int i, int cnt, int tryN) {
        double ang = (yaw0 * Math.PI / 180.0) + (Math.PI * 2 * i / cnt);
        double fx = Math.cos(ang);
        double fz = Math.sin(ang);
        double rr = BT_DRAG_RING;
        double spread = (tryN == 0 ? 0 : (tryN % 2 == 0 ? 1 : -1) * Math.ceil(tryN / 2.0) * 0.8);
        double ax = bx + fx * rr;
        double az = bz + fz * rr;
        ax += -fz * spread;
        az += fx * spread;
        if (!p.passable((int) Math.floor(ax), (int) Math.floor(sy), (int) Math.floor(az))) {
            return null;
        }
        double ay = btSafeY(p, ax, az, sy, 2);
        return new double[]{ax, az, ay};
    }

    /** 单次最多拉几个：{@code min(n, BT_DRAG_CAP)}。 */
    static int btDragCount(int n) {
        return n > BT_DRAG_CAP ? BT_DRAG_CAP : n;
    }

    /** 某一阶段的 BGM 循环间隔（拿不到就退回 299000）。 */
    static long btMusicLoopMs(int stage) {
        int i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
        long v = BT_MUSIC_LOOP_MS[i];
        return v <= 0 ? 299000L : v;
    }

    /** 技能触发门槛（逐值对照原脚本的比较式）。 */
    static boolean btShootRangeOk(double d) {
        return d <= BT_S1_ATK_RANGE;
    }

    static boolean btFangTriggerOk(double d) {
        return d <= BT_S1_FANG_RANGE + 2.0;
    }

    static boolean btSlashTriggerOk(double d) {
        return d <= BT_S2_SLASH_RANGE + 1.0;
    }

    static boolean btAxeTriggerOk(double d) {
        return d <= BT_S2_AXE_TRIGGER;
    }

    static boolean btSeekOk(double d) {
        return d <= BT_SEEK;
    }

    // ==================================================================
    // 小工具
    // ==================================================================

    private static long btNow() {
        return Guards.now();
    }

    private static boolean btAlive(Entity e) {
        try {
            if (e == null) {
                return false;
            }
            return !(e.isDead() || !e.isValid());
        } catch (Throwable e2) {
            return false;
        }
    }

    private String btTag(Entity e) {
        try {
            if (e == null || BT_KEY == null) {
                return "";
            }
            String v = e.getPersistentDataContainer().get(BT_KEY, PersistentDataType.STRING);
            return (v == null ? "" : String.valueOf(v));
        } catch (Throwable err) {
            return "";
        }
    }

    /** 这是不是我们这条 boss 线上的某一只？（含妄想 —— 它带的是同一个键、不同的值） */
    private boolean btOurs(Entity e) {
        try {
            if (BT_KEY == null || e == null) {
                return false;
            }
            return e.getPersistentDataContainer().has(BT_KEY, PersistentDataType.STRING);
        } catch (Throwable err) {
            return false;
        }
    }

    private static double btDist(Entity a, Entity b) {
        try {
            return a.getLocation().distance(b.getLocation());
        } catch (Throwable e) {
            return 999.0;
        }
    }

    /** 粒子名解析（多个候选，逐个试，老版本没有就退下一个）。 */
    private static Particle btP(String... cands) {
        try {
            for (String c : cands) {
                try {
                    Particle p = Particle.valueOf(c);
                    if (p != null) {
                        return p;
                    }
                } catch (Throwable ignored) {
                    // 试下一个候选
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        return null;
    }

    /** 实体类型解析（多个候选，逐个试）。 */
    private static EntityType btET(String... names) {
        try {
            for (String n : names) {
                try {
                    EntityType t = EntityType.valueOf(n);
                    if (t != null) {
                        return t;
                    }
                } catch (Throwable ignored) {
                    // 试下一个候选
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        return null;
    }

    /** 在某个点炸 particles。 */
    private static void btFx(World world, Particle pt, double x, double y, double z,
                             int n, double dx, double dy, double dz, double speed) {
        try {
            if (world == null || pt == null) {
                return;
            }
            world.spawnParticle(pt, x, y, z, n, dx, dy, dz, speed);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void btSound(World world, Location loc, String name, double vol, double pitch) {
        try {
            try {
                world.playSound(loc, name, (float) vol, (float) pitch);
            } catch (Throwable e1) {
                try {
                    loc.getWorld().playSound(loc, name, (float) vol, (float) pitch);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ---------------- 属性 ----------------

    /** ★★ 专门找 **MAX_HEALTH**（1.21.11 上直接可用；退路见类注释的死代码清单）。 */
    private Attribute btFindMaxAttr() {
        try {
            if (BT_MAXATTR != null) {
                return BT_MAXATTR;
            }
            BT_MAXATTR = Attribute.MAX_HEALTH;
            return BT_MAXATTR;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * ★★ 强设血量上限 —— ① 删掉 MAX_HEALTH 上可能把上限压住的 AttributeModifier
     * （先快照再删，别边遍历边删）② 属性 baseValue 直接写 ③ {@code setMaxHealth()} 兜底。
     */
    private boolean btForceMax(LivingEntity ent, double hp) {
        boolean ok = false;
        try {
            Attribute k = btFindMaxAttr();
            if (k != null) {
                AttributeInstance inst = ent.getAttribute(k);
                if (inst != null) {
                    try {
                        List<AttributeModifier> snap = new ArrayList<>(inst.getModifiers());
                        for (AttributeModifier m : snap) {
                            try {
                                inst.removeModifier(m);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        inst.setBaseValue(hp);
                        ok = true;
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setMaxHealth(hp);
            ok = true;
        } catch (Throwable ignored) {
            // 静默
        }
        return ok;
    }

    /**
     * ★ 把**最终伤害**（含手里武器的加成）精确设成 want 点：
     * 先读出当前实际值 cur（已含装备加成），再把 base 补差值。
     */
    private static boolean btSetDmg(LivingEntity ent, double want) {
        try {
            AttributeInstance inst = ent.getAttribute(Attribute.ATTACK_DAMAGE);
            if (inst == null) {
                return false;
            }
            double cur = inst.getValue();
            double base = inst.getBaseValue();
            inst.setBaseValue(base + (want - cur));
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private double btMaxHp(LivingEntity ent) {
        try {
            Attribute k = btFindMaxAttr();
            if (k == null) {
                return 0;
            }
            AttributeInstance inst = ent.getAttribute(k);
            if (inst == null) {
                return 0;
            }
            return inst.getValue();
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void btHurt(Entity src, Entity ent, double dmg) {
        try {
            try {
                if (ent instanceof LivingEntity le) {
                    le.setNoDamageTicks(0);
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                if (src == null) {
                    // ★ 原脚本注释：boss 可能已经死了 ⇒ 传 null 让 btHurt 退化成无来源伤害
                    ((LivingEntity) ent).damage(dmg);
                } else {
                    ((LivingEntity) ent).damage(dmg, src);
                }
            } catch (Throwable e1) {
                try {
                    ((LivingEntity) ent).damage(dmg);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void btKb(Entity ent, double vx, double vy, double vz) {
        try {
            ent.setVelocity(new Vector(vx, vy, vz));
        } catch (Throwable ignored) {
            // 静默
        }
    }

    /**
     * ★ 破盾 —— 完全照原版"斧头砍盾牌"的规则来：
     * ① {@code p.setCooldown(Material.SHIELD, BT_S2_SLASH_SHIELD_CD)} ⇒ **5 秒内举不起盾**；
     * ② 盾牌按原版口径**掉耐久** = {@code 1 + 向下取整(伤害)}，耐久真掉光了就直接碎掉。
     */
    private static boolean btBreakShield(Player p, double dmg) {
        try {
            if (p == null) {
                return false;
            }
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            if (inv == null) {
                return false;
            }

            ItemStack shield = null;
            boolean slotMain = false;
            try {
                ItemStack m = inv.getItemInMainHand();
                if (m != null && String.valueOf(m.getType().name()).equals("SHIELD")) {
                    shield = m;
                    slotMain = true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            if (shield == null) {
                try {
                    ItemStack o = inv.getItemInOffHand();
                    if (o != null && String.valueOf(o.getType().name()).equals("SHIELD")) {
                        shield = o;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            if (shield == null) {
                return false;
            }

            // ① 物品冷却 ⇒ BT_S2_SLASH_SHIELD_CD 刻内举不起盾（原版斧头同款，最可靠）
            try {
                p.setCooldown(Material.SHIELD, BT_S2_SLASH_SHIELD_CD);
            } catch (Throwable ignored) {
                // 静默
            }

            // ② 耐久：按原版口径扣（1 + floor(伤害)），扣光了就碎
            boolean broke = false;
            try {
                ItemMeta meta = shield.getItemMeta();
                if (meta instanceof Damageable dm) {
                    int max = 336;
                    try {
                        max = shield.getType().getMaxDurability();
                    } catch (Throwable e) {
                        max = 336;
                    }
                    if (max <= 0) {
                        max = 336;
                    }
                    // ★ 原版：1 + 向下取整(伤害)
                    int loss = 1 + (int) Math.floor(dmg < 0 ? 1 : dmg);
                    int cur = 0;
                    try {
                        cur = dm.getDamage();
                    } catch (Throwable e) {
                        cur = 0;
                    }
                    int nd = cur + loss;
                    if (nd >= max) {
                        broke = true;                       // ★ 耐久耗尽 ⇒ 原版规则：直接碎掉
                    } else {
                        dm.setDamage(nd);
                        shield.setItemMeta(meta);
                        if (slotMain) {
                            try {
                                inv.setItemInMainHand(shield);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        } else {
                            try {
                                inv.setItemInOffHand(shield);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            if (broke) {
                if (slotMain) {
                    try {
                        inv.setItemInMainHand(null);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                } else {
                    try {
                        inv.setItemInOffHand(null);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
                try {
                    p.playSound(p.getLocation(), "item.shield.break", 1.0f, 1.0f);
                } catch (Throwable ignored) {
                    // 静默
                }
            } else {
                try {
                    p.stopSound("item.shield.block");
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    p.playSound(p.getLocation(), "item.shield.break", 0.7f, 1.2f);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ==================================================================
    // BossBar（血条）
    // ==================================================================

    private static BossBar btBarMake(String title) {
        if (!BT_BAR) {
            return null;
        }
        try {
            BossBar bar;
            try {
                bar = Bukkit.getServer().createBossBar(title, BarColor.RED, BarStyle.SEGMENTED_10);
            } catch (Throwable e) {
                bar = null;
            }
            if (bar == null) {
                return null;
            }
            try {
                bar.setVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                bar.setProgress(1.0);
            } catch (Throwable ignored) {
                // 静默
            }
            return bar;
        } catch (Throwable e) {
            return null;
        }
    }

    private void btBarSync(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            Entity ent = ref.ent;
            boolean alive = btAlive(ent);

            double prog = 0.0;
            if (alive) {
                try {
                    double hp = ((LivingEntity) ent).getHealth();
                    double mx = btMaxHp((LivingEntity) ent);
                    prog = btBarProgress(hp, mx, ref.maxHp);
                } catch (Throwable e) {
                    prog = 0.0;
                }
            }
            prog = btClamp01(prog);
            try {
                ref.bar.setProgress(prog);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.bar.setVisible(alive);
            } catch (Throwable ignored) {
                // 静默
            }

            // 观看者：**只给召唤者看**
            Map<String, Player> want = new LinkedHashMap<>();
            if (alive) {
                try {
                    String wname = String.valueOf(ent.getWorld().getName());
                    Location el = ent.getLocation();
                    for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                        if (p == null) {
                            continue;
                        }
                        if (BT_BAR_ONLY_OWNER) {
                            try {
                                if (!String.valueOf(p.getUniqueId()).equals(String.valueOf(ref.owner))) {
                                    continue;
                                }
                            } catch (Throwable e) {
                                continue;
                            }
                        }
                        try {
                            if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                                continue;
                            }
                        } catch (Throwable e) {
                            continue;
                        }
                        Location pl;
                        try {
                            pl = p.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (pl.distance(el) > BT_BAR_RANGE) {
                            continue;
                        }
                        try {
                            want.put(String.valueOf(p.getUniqueId()), p);
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            for (Map.Entry<String, Player> en : want.entrySet()) {
                if (ref.barNear.get(en.getKey()) == null) {
                    try {
                        ref.bar.addPlayer(en.getValue());
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    ref.barNear.put(en.getKey(), en.getValue());
                }
            }
            for (String k : new ArrayList<>(ref.barNear.keySet())) {
                if (want.get(k) == null) {
                    try {
                        ref.bar.removePlayer(ref.barNear.get(k));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    ref.barNear.remove(k);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static void btBarDrop(Ref ref) {
        try {
            if (ref == null || ref.bar == null) {
                return;
            }
            try {
                ref.bar.setVisible(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.bar.removeAll();
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.barNear.clear();
            } catch (Throwable ignored) {
                // 静默
            }
            ref.bar = null;
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // ★ 阶段 BGM —— 每个阶段一首，该阶段存活期间 **循环播放**，阶段死亡 **立刻停**
    // ==================================================================

    /** 每阶段"本服真的有"的音效名（缓存 {@code BT_MUSIC_PICKED}）。 */
    private String btMusicSound(int stage) {
        int i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
        try {
            if (BT_MUSIC_PICKED[i] != null) {
                return BT_MUSIC_PICKED[i];
            }
            String[] cands = {BT_MUSIC_SOUND[i], BT_MUSIC_FALLBACK[i],
                    "music_disc.relic", "music_disc.11"};
            try {
                for (String cand : cands) {
                    String nm = "MUSIC_DISC_"
                            + String.valueOf(cand).replace("music_disc.", "").toUpperCase(java.util.Locale.ROOT);
                    try {
                        // ★ 原脚本是 `Sound[nm] != null` —— Java 里等价于「反射拿得到这个静态字段」
                        org.bukkit.Sound.class.getField(nm);
                        BT_MUSIC_PICKED[i] = cand;
                        return BT_MUSIC_PICKED[i];
                    } catch (Throwable ignored) {
                        // 试下一个候选
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            BT_MUSIC_PICKED[i] = BT_MUSIC_SOUND[i];
            return BT_MUSIC_PICKED[i];
        } catch (Throwable e) {
            return BT_MUSIC_SOUND[i];
        }
    }

    private org.bukkit.Sound btMusicEnum(int stage) {
        try {
            String nm = "MUSIC_DISC_"
                    + String.valueOf(btMusicSound(stage)).replace("music_disc.", "")
                    .toUpperCase(java.util.Locale.ROOT);
            return (org.bukkit.Sound) org.bukkit.Sound.class.getField(nm).get(null);
        } catch (Throwable e) {
            return null;
        }
    }

    private boolean btMusicPlayTo(Player p, Location loc, int stage) {
        try {
            if (p == null || loc == null) {
                return false;
            }
            String name = btMusicSound(stage);
            try {
                p.playSound(loc, name, (float) BT_MUSIC_VOL, 1.0f);
                return true;
            } catch (Throwable ignored) {
                // 退到下一条
            }
            org.bukkit.Sound en = btMusicEnum(stage);
            if (en != null) {
                try {
                    p.playSound(loc, en, (float) BT_MUSIC_VOL, 1.0f);
                    return true;
                } catch (Throwable ignored) {
                    // 退到下一条
                }
            }
            try {
                loc.getWorld().playSound(p, name, (float) BT_MUSIC_VOL, 1.0f);
                return true;
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void btMusicStopTo(Player p, int stage) {
        try {
            if (p == null) {
                return;
            }
            String name = btMusicSound(stage);
            try {
                p.stopSound(name);
                return;
            } catch (Throwable ignored) {
                // 退到下一条
            }
            org.bukkit.Sound en = btMusicEnum(stage);
            if (en != null) {
                try {
                    p.stopSound(en);
                    return;
                } catch (Throwable ignored) {
                    // 退到下一条
                }
            }
            // 最后兜底：停掉整个"唱片"音效分类（免得旧曲子还在响）
            try {
                p.stopSound(SoundCategory.RECORDS);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 每秒扫一次：范围内的人没听过 / 到点了 ⇒ 重放；走出范围 / 下线 ⇒ 立刻停。 */
    private void btMusicTick(Ref ref) {
        try {
            if (!BT_MUSIC) {
                return;
            }
            if (ref == null || ref.done || !btAlive(ref.ent)) {
                return;
            }

            int stage = ref.stage;
            if (stage < 1) {
                stage = 1;
            }
            long now = btNow();
            Location bl = null;
            String wname = "";
            try {
                bl = ref.ent.getLocation();
                wname = String.valueOf(bl.getWorld().getName());
            } catch (Throwable ignored) {
                // 静默
            }

            Map<String, Player> want = new LinkedHashMap<>();
            if (bl != null) {
                for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                    if (p == null) {
                        continue;
                    }
                    try {
                        if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                            continue;
                        }
                    } catch (Throwable e) {
                        continue;
                    }
                    Location pl;
                    try {
                        pl = p.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    if (pl.distance(bl) > BT_MUSIC_RANGE) {
                        continue;
                    }
                    try {
                        want.put(String.valueOf(p.getUniqueId()), p);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            long loopMs = btMusicLoopMs(stage);

            for (Map.Entry<String, Player> en : want.entrySet()) {
                String k = en.getKey();
                Music rec = ref.music.get(k);
                // 刚走进范围 / 阶段换了 / 已经听满一整首 ⇒ 重放
                if (rec == null || rec.stage != stage || (now - rec.at) >= loopMs) {
                    boolean okp = false;
                    try {
                        okp = btMusicPlayTo(en.getValue(), bl, stage);
                    } catch (Throwable e) {
                        okp = false;
                    }
                    Music nm = new Music();
                    nm.p = en.getValue();
                    nm.at = now;
                    nm.stage = stage;
                    ref.music.put(k, nm);
                    if (!okp && rec == null) {
                        try {
                            Bukkit.getLogger().info("[构思技艺] 妄念：本服没有音效 "
                                    + btMusicSound(stage) + "，该阶段 BGM 不播放");
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                }
            }
            // 走出范围 / 下线 ⇒ 立刻停
            for (String k2 : new ArrayList<>(ref.music.keySet())) {
                if (want.get(k2) == null) {
                    Music m = ref.music.get(k2);
                    try {
                        btMusicStopTo(m.p, m.stage);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    ref.music.remove(k2);
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** 阶段死亡 / 收摊：把所有还在听的人的音乐 **立刻停掉**。 */
    private void btMusicStopAll(Ref ref) {
        try {
            if (ref == null || ref.music == null) {
                return;
            }
            for (String k : new ArrayList<>(ref.music.keySet())) {
                Music m = ref.music.get(k);
                try {
                    btMusicStopTo(m.p, m.stage);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.music.remove(k);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★ BGM 主循环：每 20 刻（1 秒）跑一次。
     * 用 {@code musGen} 世代号防止"上一阶段的循环还挂在 runLater 里"和新的循环同时跑。
     */
    private void btMusicLoop(Ref ref) {
        try {
            if (!BT_MUSIC) {
                return;
            }
            if (ref == null || ref.done) {
                return;
            }
            ref.musGen = ref.musGen + 1;
            final int gen = ref.musGen;
            Runnable[] step = new Runnable[1];
            step[0] = () -> {
                try {
                    if (ref.done) {
                        return;
                    }
                    if (ref.musGen != gen) {
                        return;                     // 已经换代 ⇒ 这条链作废
                    }
                    if (!btAlive(ref.ent)) {
                        btMusicStopAll(ref);
                        return;
                    }
                    btMusicTick(ref);
                    GS.runLater(step[0], 20);
                } catch (Throwable ignored) {
                    // 与原脚本一致：静默
                }
            };
            try {
                btMusicTick(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            GS.runLater(step[0], 20);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // ★ 掉落物 —— 只有特定阶段掉特定东西
    // ==================================================================

    private boolean btDropOne(Ref ref, String id, int amount) {
        try {
            if (ref == null || id == null) {
                return false;
            }
            World world = ref.lw;
            Double x = ref.lx;
            Double y = ref.ly;
            Double z = ref.lz;
            if (world == null || x == null) {
                return false;
            }

            ItemStack stack = null;
            try {
                io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem sf = GS.sfItem(id);
                if (sf != null) {
                    stack = sf.getItem().clone();
                }
            } catch (Throwable e) {
                stack = null;
            }
            if (stack == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 妄念：取不到 " + id + "，未掉落");
                } catch (Throwable ignored) {
                    // 静默
                }
                return false;
            }
            try {
                stack.setAmount(amount <= 0 ? 1 : amount);
            } catch (Throwable ignored) {
                // 静默
            }

            world.dropItemNaturally(
                    new Location(world, x, (y == null ? 0 : y) + 0.5, z), stack);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★ 妄想碎片：★★★ 只有第三阶段掉，固定 6 个（一/二阶段不掉）。 */
    private void btDropFragments(Ref ref, int stage) {
        try {
            int i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
            int n = BT_FRAG_DROP[i];
            if (n <= 0) {
                return;
            }
            if (!btDropOne(ref, BT_FRAG_ID, n)) {
                return;
            }
            try {
                ref.lw.playSound(new Location(ref.lw, ref.lx, ref.ly, ref.lz),
                        "entity.item.pickup", 0.8f, 1.2f);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★ 残樱：★★★ 只有第二阶段掉，固定 1 把（一 / 三阶段不掉）。 */
    private void btDropCherry(Ref ref, int stage) {
        try {
            int i = (stage >= 1 && stage <= 3) ? (stage - 1) : 0;
            int n = BT_CHERRY_DROP[i];
            if (n <= 0) {
                return;
            }
            if (!btDropOne(ref, BT_CHERRY_ID, n)) {
                return;
            }
            try {
                ref.lw.playSound(new Location(ref.lw, ref.lx, ref.ly, ref.lz),
                        "entity.item.pickup", 0.8f, 1.2f);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 生成 boss / 血量落地
    // ==================================================================

    private void btHpDiag(LivingEntity ent, double want, double max, double cur, String why) {
        try {
            String id;
            try {
                id = String.valueOf(ent.getUniqueId());
            } catch (Throwable e) {
                id = "unknown";
            }
            if (BT_HP_DIAG.contains(id)) {
                return;
            }
            BT_HP_DIAG.add(id);
            String tn;
            try {
                tn = String.valueOf(ent.getType().name());
            } catch (Throwable e) {
                tn = "?";
            }
            String kname = "null";
            try {
                Attribute k = btFindMaxAttr();
                if (k != null) {
                    kname = String.valueOf(k.name());
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念 血量设置失败(" + why + ")：实体="
                        + tn + " 目标=" + btNum(want) + " 实际上限=" + btNum(max)
                        + " 实际血量=" + btNum(cur) + " MAX_HEALTH键=" + kname);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private boolean btApplyHpOnce(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return false;
            }
            // ① 强设上限（删修改器 + 属性 + setMaxHealth 兜底）
            btForceMax(ent, hp);
            // ② ★ 先确认上限到位 —— 没到位就别 setHealth（那一定会被截成原版值）
            double mx = btMaxHp(ent);
            if (mx < hp - 0.5) {
                btHpDiag(ent, hp, mx, -1, "上限没上去");
                return false;
            }
            // ③ 上限对了，再回满血
            try {
                ent.setHealth(hp);
            } catch (Throwable ignored) {
                // 静默
            }
            double now = -1;
            try {
                now = ent.getHealth();
            } catch (Throwable e) {
                now = -1;
            }
            if (now >= hp - 0.5) {
                return true;
            }
            btHpDiag(ent, hp, mx, now, "血量没回满");
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    private void btApplyHp(LivingEntity ent, double hp) {
        try {
            if (ent == null || hp <= 0) {
                return;
            }
            if (btApplyHpOnce(ent, hp)) {
                return;
            }
            // 没成功 ⇒ 延迟重试（越往后间隔越大，别刷爆主线程）
            int[] delays = {1, 3, 6, 10, 20, 40};
            for (int d : delays) {
                GS.runLater(() -> {
                    try {
                        if (!btAlive(ent)) {
                            return;
                        }
                        btApplyHpOnce(ent, hp);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }, d);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★★ 从实体反查它属于哪条"线"（降级伤害缩放要用）。 */
    private Ref btRefOf(Entity ent) {
        try {
            if (ent == null) {
                return null;
            }
            String id = String.valueOf(ent.getUniqueId());
            for (Ref r : BT_ACTIVE.values()) {
                if (r == null) {
                    continue;
                }
                try {
                    if (r.ent != null && String.valueOf(r.ent.getUniqueId()).equals(id)) {
                        return r;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        return null;
    }

    private LivingEntity btSpawnEntity(World world, Location loc, String[] typeNames,
                                       String name, double hp, double dmg, String tagValue) {
        try {
            EntityType t = btET(typeNames);
            if (t == null) {
                try {
                    Bukkit.getLogger().warning("[构思技艺] 妄念：找不到实体类型 "
                            + typeNames[0] + "（版本不支持？）");
                } catch (Throwable ignored) {
                    // 静默
                }
                return null;
            }
            Entity spawned = world.spawnEntity(loc, t);
            if (spawned == null) {
                return null;
            }
            if (!(spawned instanceof LivingEntity e)) {
                return null;
            }

            // ★ 血量：走 btApplyHp（多次写入 + 延迟重试），不再是一次 setHealth
            btApplyHp(e, hp);
            if (dmg >= 0) {
                btSetDmg(e, dmg);
            }

            try {
                e.setCustomName(name);
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setCustomNameVisible(true);
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setRemoveWhenFarAway(false);
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setPersistent(true);
            } catch (Throwable err) {
                // 静默
            }
            try {
                e.setCanPickupItems(false);
            } catch (Throwable err) {
                // 静默
            }
            try {
                if (e instanceof Mob mob) {
                    mob.setAware(true);
                }
            } catch (Throwable err) {
                // 静默
            }

            // 不掉东西
            // ★ 原脚本这里是 `LootTables.EMPTY != null ⇒ e.setLootTable(LootTables.EMPTY)`，
            //   而 `LootTables.EMPTY` 在 paper-api 1.21.11 里**根本不存在**（已 javap 核实）
            //   ⇒ JS 侧拿到 undefined（或抛异常被吞）⇒ `undefined != null` 为 false
            //   ⇒ **这一句从未生效过**。原生版照抄"从未生效"这个事实（不设掉落表），
            //   真正的"不掉东西"由 DeathListener 清 drops 完成。
            try {
                EntityEquipment eq = e.getEquipment();
                if (eq != null) {
                    try {
                        eq.clear();
                    } catch (Throwable err2) {
                        // 静默
                    }
                }
            } catch (Throwable err) {
                // 静默
            }

            if (BT_KEY != null && tagValue != null) {
                try {
                    e.getPersistentDataContainer().set(BT_KEY, PersistentDataType.STRING, tagValue);
                } catch (Throwable err) {
                    // 静默
                }
            }
            return e;
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念：生成 boss 出错 " + err);
            } catch (Throwable e2) {
                // 静默
            }
            return null;
        }
    }

    // ==================================================================
    // 目标选择
    // ==================================================================

    private Player btTarget(Ref ref) {
        try {
            Entity ent = ref.ent;
            if (!btAlive(ent)) {
                return null;
            }
            World world = ent.getWorld();
            String wname = String.valueOf(world.getName());
            Location el = ent.getLocation();

            Player best = null;
            double bestD = 1.0e9;              // 非创造 / 非旁观
            Player fall = null;
            double fallD = 1.0e9;              // 创造模式（兜底）
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                boolean creative = false;
                boolean spec = false;
                try {
                    String gm = String.valueOf(p.getGameMode().name());
                    if (gm.contains("SPECTATOR")) {
                        spec = true;
                    }
                    if (gm.contains("CREATIVE")) {
                        creative = true;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                if (spec) {
                    continue;
                }
                Location pl;
                try {
                    pl = p.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double d = pl.distance(el);
                if (d > BT_SEEK) {
                    continue;
                }
                if (creative) {
                    if (d < fallD) {
                        fallD = d;
                        fall = p;
                    }
                } else {
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
            }
            if (best != null) {
                return best;
            }
            if (BT_CREATIVE_FALLBACK) {
                return fall;
            }
            return null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ==================================================================
    // 主心跳
    // ==================================================================

    private void btTick(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }

            // ① 当前这只没了 → 走转场（或整条线收摊）
            if (!btAlive(ref.ent)) {
                btPhaseEnd(ref);
                return;
            }

            // 记住位置（死的时候要用它的死亡位置召唤下一阶段）
            try {
                Location l0 = ref.ent.getLocation();
                ref.lw = l0.getWorld();
                ref.lx = l0.getX();
                ref.ly = l0.getY();
                ref.lz = l0.getZ();
            } catch (Throwable ignored) {
                // 静默
            }

            // ② 血条
            try {
                btBarSync(ref);
            } catch (Throwable ignored) {
                // 静默
            }

            // ②a ★★ 血量保护：出生后 BT_HP_FIX_MS 窗口内**每拍**重试
            try {
                long fx = ref.hpFixUntil;
                if (!ref.hpOk && btNow() <= fx) {
                    double mx0 = btMaxHp((LivingEntity) ref.ent);
                    double cur0 = -1;
                    try {
                        cur0 = ((LivingEntity) ref.ent).getHealth();
                    } catch (Throwable e) {
                        cur0 = -1;
                    }
                    boolean maxBad = (mx0 < ref.maxHp - 0.5);
                    boolean hpBad = (cur0 >= 0 && cur0 < ref.maxHp * 0.5);
                    if (maxBad || hpBad) {
                        if (btApplyHpOnce((LivingEntity) ref.ent, ref.maxHp)) {
                            ref.hpOk = true;
                            ref.hpScale = 1.0;
                        } else if (maxBad && mx0 > 0) {
                            ref.hpScale = mx0 / ref.maxHp;   // 降级：等效加血
                        }
                    } else {
                        ref.hpOk = true;
                        ref.hpScale = 1.0;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ②b 掐掉唤魔者的**原版技能**（兜底）
            try {
                btPurgeVanilla(ref);
            } catch (Throwable ignored) {
                // 静默
            }

            // ②c ★ 妄想别去咬自家卫道士（EntityTargetEvent 的兜底）
            try {
                if (ref.stage == 2) {
                    btS2VexLoyal(ref);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ③ 正在演出（瞬影 / 蓄力 / 悬停…）⇒ 本拍不放新技能
            if (ref.mode != null && !"idle".equals(ref.mode)) {
                GS.runLater(() -> btTick(ref), BT_TICK);
                return;
            }

            long now = btNow();
            if (now < ref.readyAt) {                      // 出场保护期
                GS.runLater(() -> btTick(ref), BT_TICK);
                return;
            }

            Player tgt = btTarget(ref);
            if (tgt != null) {
                double d = btDist(ref.ent, tgt);
                boolean started = false;   // ★ 同一拍只允许开一个技能

                if (ref.stage == 1) {
                    // ★ 本体射击：**每 1.5 秒一发**（不吃 started 标志 —— 它是瞬发）
                    if (btShootRangeOk(d) && btCdOk(now, ref.atkAt, BT_S1_ATK_CD)) {
                        ref.atkAt = now;
                        btS1Shoot(ref, tgt);
                    }
                    // 技能 1：追踪鳄鱼嘴（15 点 / 12 格 / 10 秒）
                    if (!started && btFangTriggerOk(d) && btCdOk(now, ref.cd1, BT_S1_FANG_CD)) {
                        ref.cd1 = now;
                        started = true;
                        btS1Fang(ref, tgt);
                    }
                    // 技能 2：瞬影 + 回 50 血（20 秒）
                    if (!started && btSeekOk(d) && btCdOk(now, ref.cd2, BT_S1_BLINK_CD)) {
                        ref.cd2 = now;
                        started = true;
                        btS1Blink(ref, tgt);
                    }
                } else if (ref.stage == 2) {
                    // 技能 1：竖斩（35 点 / 4 格 / 破盾 / 樱花瓣 / 10 秒）
                    if (!started && btSlashTriggerOk(d) && btCdOk(now, ref.cd1, BT_S2_SLASH_CD)) {
                        ref.cd1 = now;
                        started = true;
                        btS2Slash(ref, tgt);
                    }
                    // ★ 技能 2：星环飞斧
                    if (!started && btAxeTriggerOk(d) && btCdOk(now, ref.cd3, BT_S2_AXE_CD)) {
                        ref.cd3 = now;
                        started = true;
                        btS2Axe(ref, tgt);
                    }
                } else if (ref.stage == 3) {
                    // 技能 1：坠地（150 点 / 3.5 格 / **11 秒**）
                    if (!started && btSeekOk(d) && btCdOk(now, ref.cd1, BT_S3_SLAM_CD)) {
                        ref.cd1 = now;
                        started = true;
                        btS3Slam(ref, tgt);
                    }
                }

                // ★★★ 通用反卡位技能「锁链拽回」—— **三个阶段都有**
                double dragTrig = 0.0;
                if (ref.stage == 1) {
                    dragTrig = BT_DRAG_TRIG_S1;
                } else if (ref.stage == 2) {
                    dragTrig = BT_DRAG_TRIG_S2;
                } else if (ref.stage == 3) {
                    dragTrig = BT_DRAG_TRIG_S3;
                }
                // ★★ `dragged`（本轮已拉名单）**在 CD 好了那一刻清空**
                if (btCdOk(now, ref.cd4, BT_DRAG_CD)) {
                    ref.dragged = new LinkedHashSet<>();
                    if (btShouldDrag(ref, dragTrig)) {
                        ref.cd4 = now;
                        btDragStart(ref);
                    }
                }
            }

            GS.runLater(() -> btTick(ref), BT_TICK);
        } catch (Throwable e) {
            try {
                if (ref != null && btAlive(ref.ent)) {
                    GS.runLater(() -> btTick(ref), BT_TICK);
                } else {
                    btCleanup(ref);
                }
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ==================================================================
    // ★ Phase 1（掠夺者）**双手清空 + 本体射击**
    // ==================================================================

    private static void btS1Hands(LivingEntity ent) {
        try {
            if (ent == null || !btAlive(ent)) {
                return;
            }
            EntityEquipment eq = ent.getEquipment();
            if (eq == null) {
                return;
            }
            boolean changed = false;
            try {
                ItemStack mh = eq.getItemInMainHand();
                String mhn = (mh == null ? "" : String.valueOf(mh.getType().name()));
                if (!mhn.equals("AIR")) {
                    eq.setItemInMainHand(new ItemStack(Material.AIR));
                    changed = true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ItemStack oh = eq.getItemInOffHand();
                String ohn = (oh == null ? "" : String.valueOf(oh.getType().name()));
                if (!ohn.equals("AIR")) {
                    eq.setItemInOffHand(new ItemStack(Material.AIR));
                    changed = true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            if (changed) {
                try {
                    World world = ent.getWorld();
                    Location l = ent.getLocation();
                    btSound(world, l, "item.armor.equip_iron", 0.4, 1.6);   // 轻微"哐"一声，像丢下弩
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // ★ 掉落率全写 0（清空后本来也没东西，双保险）
            try {
                eq.setItemInMainHandDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                eq.setItemInOffHandDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                eq.setHelmetDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                eq.setChestplateDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                eq.setLeggingsDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                eq.setBootsDropChance(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★ 本体射击：朝目标方向发一支箭（不依赖任何武器）。 */
    private void btS1Shoot(Ref ref, Player tgt) {
        try {
            if (ref == null || tgt == null || !btAlive(ref.ent)) {
                return;
            }
            World world;
            try {
                world = ref.ent.getWorld();
            } catch (Throwable e) {
                return;
            }
            if (world == null) {
                return;
            }

            Location from;
            try {
                from = ref.ent.getEyeLocation();
            } catch (Throwable e) {
                from = ref.ent.getLocation();
            }
            Location tl;
            try {
                tl = tgt.getLocation();
            } catch (Throwable e) {
                return;
            }
            // 瞄胸口偏上一点（打站着的玩家）
            double ty = tl.getY() + 1.0;

            double dx = tl.getX() - from.getX();
            double dy = ty - from.getY();
            double dz = tl.getZ() - from.getZ();
            double[] d1 = btNorm3(dx, dy, dz, 0.2);
            if (d1 == null) {
                return;
            }
            dx = d1[0];
            dy = d1[1];
            dz = d1[2];

            // ★ 一点点自然散布：不至于百发百中，玩家能靠跑位规避
            dx += (Math.random() - 0.5) * 0.045;
            dy += (Math.random() - 0.5) * 0.030;
            dz += (Math.random() - 0.5) * 0.045;
            double[] d2 = btNorm3(dx, dy, dz, 1.0e-4);
            if (d2 == null) {
                return;
            }
            dx = d2[0];
            dy = d2[1];
            dz = d2[2];

            Vector dir = new Vector(dx, dy, dz);

            AbstractArrow arrow;
            try {
                // 出生点稍微往前 0.8 格，免得卡在自己身体里
                Location sp = new Location(world,
                        from.getX() + dx * 0.8, from.getY() - 0.2 + dy * 0.8, from.getZ() + dz * 0.8,
                        from.getYaw(), from.getPitch());
                arrow = world.spawnArrow(sp, dir, (float) BT_S1_ARROW_SPEED, 0.0f);
            } catch (Throwable e) {
                // 兜底：手动 spawn 一支箭再给速度（1.21.11 上走不到，见类注释）
                AbstractArrow tmp = null;
                try {
                    EntityType et = btET("ARROW");
                    if (et != null) {
                        Entity spawned = world.spawnEntity(from, et);
                        if (spawned instanceof AbstractArrow ar) {
                            tmp = ar;
                            try {
                                ar.setVelocity(dir.multiply(BT_S1_ARROW_SPEED));
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                    }
                } catch (Throwable e3) {
                    tmp = null;
                }
                arrow = tmp;
            }
            if (arrow == null) {
                return;
            }

            try {
                arrow.setShooter(ref.ent);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                arrow.setDamage(0.0);          // 伤害由伤害事件统一结算成 12
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                arrow.setPersistent(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                arrow.setKnockbackStrength(0);
            } catch (Throwable ignored) {
                // 静默
            }

            // 弩声 + 一点枪口粒子
            try {
                btSound(world, from, "item.crossbow.shoot", 1.0, 1.0);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                btSound(world, from, "entity.arrow.shoot", 0.8, 1.1);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Particle pt = btP("CRIT", "ENCHANTED_HIT", "SMOKE_NORMAL");
                btFx(world, pt, from.getX() + dx * 1.0, from.getY(), from.getZ() + dz * 1.0,
                        4, 0.06, 0.06, 0.06, 0.01);
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★ 掐掉唤魔者的原版技能（第二道保险）：
     * ① 每拍强行把"正在施法"清成 NONE（阶段 1 已是掠夺者 ⇒ 走不到，见类注释）
     * ② 每秒清掉它召出来的残留恼鬼 / 尖牙（带我们标记的尖牙不算残留）
     */
    private void btPurgeVanilla(Ref ref) {
        try {
            Entity ent = ref.ent;
            if (!btAlive(ent) || ref.stage != 1) {
                return;
            }

            try {
                if (ent instanceof Spellcaster sc) {
                    try {
                        Spellcaster.Spell sp = sc.getSpell();
                        if (sp != null && !String.valueOf(sp.name()).equals("NONE")) {
                            sc.setSpell(Spellcaster.Spell.NONE);
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ Phase 1 是**掠夺者** ⇒ 每拍把双手清空
            try {
                btS1Hands((LivingEntity) ent);
            } catch (Throwable ignored) {
                // 静默
            }

            ref.purgeN = ref.purgeN + 1;
            if (ref.purgeN % 4 != 1) {
                return;      // 后面的大扫除每秒做一次就够了
            }

            try {
                World world = ent.getWorld();
                Location l = ent.getLocation();
                for (Entity v : world.getNearbyEntities(l, 28, 16, 28)) {
                    if (v == null || !btAlive(v)) {
                        continue;
                    }
                    String tn;
                    try {
                        tn = String.valueOf(v.getType().name());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (!tn.contains("VEX") && !tn.contains("FANGS")) {
                        continue;
                    }
                    // 我们自己用来做特效的尖牙 ⇒ 跳过
                    try {
                        if (BT_FANG_KEY != null) {
                            PersistentDataContainer pd = v.getPersistentDataContainer();
                            if (pd != null && pd.has(BT_FANG_KEY, PersistentDataType.STRING)) {
                                continue;
                            }
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        v.remove();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // ① 幻念僧 · 技能 1：追踪鳄鱼嘴（一路咬过去）
    // ==================================================================

    private void btS1Fang(Ref ref, Player tgt) {
        try {
            ref.mode = "fang";
            Fang f = new Fang();
            f.life = 0;
            f.x = 0;
            f.y = 0;
            f.z = 0;
            f.traveled = 0.0;
            f.fangSeq = 0;
            ref.fang = f;
            Location l = ref.ent.getLocation();
            f.x = l.getX();
            f.y = l.getY() + 0.2;
            f.z = l.getZ();

            World world = l.getWorld();
            try {
                world.playSound(l, "entity.evoker.prepare_summon", 1.0f, 1.2f);
            } catch (Throwable ignored) {
                // 静默
            }

            btFangTick(ref, tgt);
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btFangTick(Ref ref, Player tgt) {
        try {
            if (ref == null || ref.fang == null) {
                return;
            }
            if (!btAlive(ref.ent) || !btAlive(tgt)) {
                ref.fang = null;
                ref.mode = "idle";
                return;
            }
            Fang w = ref.fang;
            World world = ref.lw;
            if (world == null) {
                try {
                    world = ref.ent.getWorld();
                } catch (Throwable e) {
                    world = null;
                }
            }
            if (world == null) {
                ref.fang = null;
                ref.mode = "idle";
                return;
            }

            w.life++;
            if (w.life > BT_S1_FANG_LIFE || w.traveled > BT_S1_FANG_RANGE) {
                ref.fang = null;
                ref.mode = "idle";
                return;
            }

            // ★ 追踪：每刻都重新朝目标当前位置算方向
            Location tl = tgt.getLocation();
            double[] st = btFangStep(w.x, w.y, w.z,
                    tl.getX(), tl.getY(), tl.getZ(), BT_S1_FANG_SPEED);
            if (st == null) {
                ref.fang = null;
                ref.mode = "idle";
                return;
            }
            w.x = st[0];
            w.y = st[1];
            w.z = st[2];
            w.traveled += st[3];
            double dx = st[4];
            double dz = st[5];
            double dy = st[6];

            // 视觉：每隔几刻丢一团尖牙（旧的会自动消失）
            if (w.life % BT_S1_FANG_SPAWN == 1) {
                try {
                    EntityType ft = btET("EVOKER_FANGS", "ILLAGER_FANGS", "VEX_FANGS");
                    if (ft != null) {
                        Entity f = world.spawnEntity(
                                new Location(world, w.x, Math.floor(w.y), w.z), ft);
                        if (f != null) {
                        if (f instanceof org.bukkit.entity.EvokerFangs ef) {
                            try {
                                ef.setOwner(ref.ent);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                            try {
                                if (BT_FANG_KEY != null) {
                                    f.getPersistentDataContainer().set(
                                            BT_FANG_KEY, PersistentDataType.STRING, "1");
                                }
                            } catch (Throwable ignored) {
                                // 静默
                            }
                            try {
                                f.setPersistent(false);
                            } catch (Throwable ignored) {
                                // 静默
                            }
                            // 演出用，别留残骸
                            GS.runLater(() -> {
                                try {
                                    f.remove();
                                } catch (Throwable ignored) {
                                    // 静默
                                }
                            }, 14);
                        }
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            try {
                Particle pt = btP("SCULK_CHARGE_POP", "CRIT", "SMOKE_NORMAL");
                btFx(world, pt, w.x, w.y + 0.1, w.z, 3, 0.15, 0.1, 0.15, 0.02);
            } catch (Throwable ignored) {
                // 静默
            }

            // 命中：贴到 1.2 格就算咬到
            if (btFangHit(dx, dz, dy)) {
                btHurt(ref.ent, tgt, BT_S1_FANG_DMG);
                try {
                    btSound(world, tl, "entity.evoker.cast_spell", 0.9, 1.1);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.fang = null;
                ref.mode = "idle";
                return;
            }

            GS.runLater(() -> btFangTick(ref, tgt), 1);
        } catch (Throwable e) {
            try {
                ref.fang = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ==================================================================
    // ① 幻念僧 · 技能 2：读条 1 秒 → 出现在玩家视角正后方 8 格 + 回 50 血
    // ==================================================================

    private void btS1Blink(Ref ref, Player tgt) {
        try {
            ref.mode = "blink";
            Blink b = new Blink();
            b.i = 0;
            b.tgt = tgt;
            ref.blink = b;
            try {
                World world = ref.ent.getWorld();
                btSound(world, ref.ent.getLocation(), "entity.evoker.prepare_wololo", 1.0, 1.0);
            } catch (Throwable ignored) {
                // 静默
            }
            btBlinkTick(ref);
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btBlinkTick(Ref ref) {
        try {
            if (ref == null || ref.blink == null) {
                return;
            }
            if (!btAlive(ref.ent)) {
                ref.blink = null;
                ref.mode = "idle";
                return;
            }
            Blink b = ref.blink;
            b.i++;

            Player tgt = b.tgt;
            if (btAlive(tgt)) {
                try {
                    Location l0 = ref.ent.getLocation();
                    Particle pt = btP("SCULK_SOUL", "PORTAL", "SPELL_WITCH", "SMOKE_LARGE");
                    for (int i = 0; i < 3; i++) {
                        btFx(l0.getWorld(), pt, l0.getX(), l0.getY() + 0.4 + i * 0.3, l0.getZ(),
                                4, 0.3, 0.3, 0.3, 0.02);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            if (b.i < BT_S1_BLINK_READ) {                       // 还在读条
                GS.runLater(() -> btBlinkTick(ref), 1);
                return;
            }

            // ★ 读条结束：挪位置 + 回血
            ref.mode = "idle";
            ref.blink = null;
            try {
                if (btAlive(tgt)) {
                    Location pl = tgt.getLocation();
                    double yaw;
                    try {
                        yaw = pl.getYaw();
                    } catch (Throwable e) {
                        yaw = 0.0;
                    }
                    double[] t = btBlinkTarget(pl.getX(), pl.getZ(), yaw,
                            BT_S1_BLINK_BACK, BT_S1_BLINK_BEHIND);
                    double nx = t[0];
                    double nz = t[1];
                    double ny = btSafeY(pl.getWorld(), nx, nz, pl.getY(), BT_S1_HEIGHT);

                    Location nl = new Location(pl.getWorld(), nx, ny, nz, (float) yaw, 0.0f);

                    World world = pl.getWorld();
                    try {
                        Particle pt2 = btP("PORTAL", "SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL");
                        btFx(world, pt2, nx, ny + 1.0, nz, 40, 0.3, 0.5, 0.3, 0.05);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        world.playSound(nl, "entity.evoker.cast_spell", 1.0f, 0.8f);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        world.playSound(nl, "entity.enderman.teleport", 0.8f, 1.2f);
                    } catch (Throwable ignored) {
                        // 静默
                    }

                    try {
                        ref.ent.teleport(nl);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }

                // ★ 恢复 50 点生命（上限取"属性读到的" → 再兜一层 getMaxHealth()，双保险）
                try {
                    double mx = btMaxHp((LivingEntity) ref.ent);
                    try {
                        double gm = ((LivingEntity) ref.ent).getMaxHealth();
                        if (gm > 0 && (mx <= 0 || gm < mx)) {
                            mx = gm;
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    if (mx <= 0) {
                        mx = ref.maxHp;
                    }
                    double hp = ((LivingEntity) ref.ent).getHealth();
                    double nh = hp + BT_S1_BLINK_HEAL;
                    if (nh > mx) {
                        nh = mx;
                    }
                    if (nh > hp) {
                        ((LivingEntity) ref.ent).setHealth(nh);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            } catch (Throwable e) {
                ref.mode = "idle";
            }
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
                ref.blink = null;
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ==================================================================
    // ② 碎念执 · 技能 1：竖着的一道斩击（破盾 / 樱花瓣 / 4 格 / 击退 3 格）
    // ==================================================================

    private void btS2Slash(Ref ref, Player tgt) {
        try {
            ref.mode = "slash";
            Location l = ref.ent.getLocation();
            // ★ 只记起点，**方向留到出手那一刻再算**
            Slash s = new Slash();
            s.i = 0;
            s.x = l.getX();
            s.y = l.getY() + 1.0;
            s.z = l.getZ();
            s.dx = 0;
            s.dz = 0;
            ref.slash = s;

            // 起手：把刀举起来（0.15 秒），给一点反应时间
            try {
                btSound(l.getWorld(), l, "entity.vindicator.ambient", 0.7, 1.3);
            } catch (Throwable ignored) {
                // 静默
            }
            btSlashTick(ref, tgt);
        } catch (Throwable e) {
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btSlashTick(Ref ref, Player tgt) {
        try {
            if (ref == null || ref.slash == null) {
                return;
            }
            if (!btAlive(ref.ent)) {
                ref.slash = null;
                ref.mode = "idle";
                return;
            }
            Slash s = ref.slash;
            World world;
            try {
                world = ref.ent.getWorld();
            } catch (Throwable e) {
                world = null;
            }
            if (world == null) {
                ref.slash = null;
                ref.mode = "idle";
                return;
            }

            s.i++;

            if (s.i <= BT_S2_SLASH_WINDUP) {           // 举刀的那几刻（只出粒子，**不锁方向**）
                GS.runLater(() -> btSlashTick(ref, tgt), 1);
                return;
            }

            // ★★ 出手这一刻才锁方向
            if (!s.traveledSet) {
                Player t2 = tgt;
                try {
                    boolean okT = (t2 != null && t2.isOnline());
                    if (!okT) {
                        t2 = btTarget(ref);      // 目标跑了/退了 ⇒ 重新挑一个
                    }
                } catch (Throwable e) {
                    t2 = null;
                }
                Location tl = null;
                try {
                    if (t2 != null) {
                        tl = t2.getLocation();
                    }
                } catch (Throwable e) {
                    tl = null;
                }
                double yaw0 = 0.0;
                try {
                    yaw0 = ref.ent.getLocation().getYaw();
                } catch (Throwable ignored) {
                    // 静默
                }
                double[] dir = (tl == null)
                        ? btDirTo(s.x, s.z, s.x, s.z, yaw0)
                        : btDirTo(s.x, s.z, tl.getX(), tl.getZ(), yaw0);
                s.dx = dir[0];
                s.dz = dir[1];
                s.traveled = 0.0;
                s.traveledSet = true;
            }

            double step = btClampStep(BT_S2_SLASH_SPEED, BT_S2_SLASH_RANGE - s.traveled);
            s.x += s.dx * step;
            s.z += s.dz * step;
            s.traveled = s.traveled + step;

            // ★ 视觉：**竖直的一道刀光**（樱花瓣）
            try {
                Particle petal = btP("CHERRY_LEAVES", "CHERRY_LEAF_PARTICLES",
                        "FALLING_SPORE_BLOSSOM", "SPORE_BLOSSOM_AIR", "HEART", "SMOKE_NORMAL");
                for (double k = -BT_S2_SLASH_H; k <= BT_S2_SLASH_H; k += 0.4) {
                    btFx(world, petal, s.x, s.y + k, s.z, 1, 0.05, 0.15, 0.05, 0.02);
                }
                // 侧向厚度：让刀光看着有面不是一条线
                btFx(world, petal, s.x + (-s.dz) * 0.4, s.y, s.z + (s.dx) * 0.4, 2, 0.05, 0.3, 0.05, 0.02);
                btFx(world, petal, s.x + (s.dz) * 0.4, s.y, s.z + (-s.dx) * 0.4, 2, 0.05, 0.3, 0.05, 0.02);
            } catch (Throwable ignored) {
                // 静默
            }

            // 命中判定：宽 1.1 格、竖着 ±1.6 格
            try {
                Collection<Entity> hitList = world.getNearbyEntities(
                        new Location(world, s.x, s.y, s.z),
                        BT_S2_SLASH_HALF_W + 0.5, BT_S2_SLASH_H + 0.5, BT_S2_SLASH_HALF_W + 0.5);
                for (Entity ent : hitList) {
                    if (ent == null || !btAlive(ent)) {
                        continue;
                    }
                    if (ent == ref.ent) {
                        continue;
                    }
                    if (btOurs(ent)) {
                        continue;
                    }
                    String eid;
                    try {
                        eid = String.valueOf(ent.getUniqueId());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (s.hit.contains(eid)) {
                        continue;
                    }

                    Location el;
                    try {
                        el = ent.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    if (!btSlashHeightOk(el.getY(), s.y)) {
                        continue;
                    }

                    // ★ 破盾（只对玩家生效）—— 按原版斧头砍盾的口径扣耐久
                    try {
                        if (ent instanceof Player pl) {
                            btBreakShield(pl, BT_S2_SLASH_DMG);
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }

                    s.hit.add(eid);
                    btHurt(ref.ent, ent, BT_S2_SLASH_DMG);
                    btKb(ent, s.dx * BT_S2_SLASH_KB, BT_S2_SLASH_UP, s.dz * BT_S2_SLASH_KB);
                    try {
                        btSound(world, el, "entity.player.attack.crit", 0.8, 1.2);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            if (s.traveled >= BT_S2_SLASH_RANGE - 0.01) {
                ref.slash = null;
                ref.mode = "idle";
                return;
            }
            GS.runLater(() -> btSlashTick(ref, tgt), 1);
        } catch (Throwable e) {
            try {
                ref.slash = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ==================================================================
    // ②-2 碎念执 · 技能 2：星环飞斧
    // ==================================================================

    private void btS2Axe(Ref ref, Player tgt) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (!btAlive(ref.ent)) {
                return;
            }
            ref.mode = "axe";                       // 演出中 ⇒ 主心跳本拍不再放别的技能

            ref.axeGen = ref.axeGen + 1;            // ★ 世代号
            Axe a = new Axe();
            a.gen = ref.axeGen;
            a.i = 0;
            a.dx = 0;
            a.dz = 0;
            a.traveled = 0.0;
            a.tgt = tgt;
            ref.axe = a;

            Location l;
            try {
                l = ref.ent.getLocation();
            } catch (Throwable e) {
                l = null;
            }
            try {
                if (l != null) {
                    btSound(l.getWorld(), l, "entity.evoker.prepare_summon", 0.6, 1.6);
                }
            } catch (Throwable ignored) {
                // 静默
            }
            btAxeChargeTick(ref);
        } catch (Throwable e) {
            try {
                ref.axe = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** ① 蓄力：环绕星星粒子（1.5 秒）。 */
    private void btAxeChargeTick(Ref ref) {
        try {
            if (ref == null || ref.axe == null || ref.done) {
                return;
            }
            Axe a = ref.axe;
            int gen = a.gen;
            if (ref.axeGen != gen) {
                return;                              // 旧链作废
            }
            if (!btAlive(ref.ent)) {
                ref.axe = null;
                ref.mode = "idle";
                return;
            }

            a.i++;
            World world = null;
            Location l = null;
            try {
                l = ref.ent.getLocation();
                world = l.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }
            if (l == null || world == null) {
                ref.axe = null;
                ref.mode = "idle";
                return;
            }

            Particle star = btP("CRIT", "ENCHANTED_HIT", "END_ROD");
            int n = BT_S2_AXE_STAR_N;
            double stepA = (Math.PI * 2) / n;
            // 两层反向转 ⇒ 看着像"星环在收拢又张开"
            for (int k = 0; k < n; k++) {
                double ang1 = a.i * 0.34 + k * stepA;
                btFx(world, star, l.getX() + Math.cos(ang1) * BT_S2_AXE_STAR_R,
                        l.getY() + 0.35 + (k % 3) * 0.42,
                        l.getZ() + Math.sin(ang1) * BT_S2_AXE_STAR_R, 1, 0.02, 0.02, 0.02, 0);
                double ang2 = -a.i * 0.26 + k * stepA;
                btFx(world, star, l.getX() + Math.cos(ang2) * (BT_S2_AXE_STAR_R * 0.6),
                        l.getY() + 1.65 - (k % 3) * 0.38,
                        l.getZ() + Math.sin(ang2) * (BT_S2_AXE_STAR_R * 0.6), 1, 0.02, 0.02, 0.02, 0);
            }
            if (a.i % 6 == 0) {
                try {
                    btSound(world, l, "block.beacon.ambient", 0.35, 1.5);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            if (a.i >= BT_S2_AXE_CHARGE) {
                btAxeThrow(ref);
                return;
            }
            GS.runLater(() -> btAxeChargeTick(ref), 1);   // ★ 自终止链
        } catch (Throwable e) {
            try {
                ref.axe = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** ②③④ 飞斧：出手瞬间锁方向 → 直线推进、不穿墙、撞到方块/玩家就停。 */
    private void btAxeThrow(Ref ref) {
        try {
            if (ref == null || ref.axe == null || ref.done) {
                return;
            }
            Axe a = ref.axe;
            if (!btAlive(ref.ent)) {
                ref.axe = null;
                ref.mode = "idle";
                return;
            }

            World world = null;
            Location l = null;
            try {
                l = ref.ent.getLocation();
                world = l.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }
            if (world == null) {
                ref.axe = null;
                ref.mode = "idle";
                return;
            }

            // ★★ 方向在这一刻（出手瞬间）才锁 —— 用的是**此刻**的目标位置
            Player tgt = a.tgt;
            try {
                boolean aliveTgt = false;
                try {
                    aliveTgt = (tgt != null && tgt.isOnline() && tgt.getGameMode() != null
                            && !String.valueOf(tgt.getGameMode().name()).contains("SPECTATOR"));
                } catch (Throwable e) {
                    aliveTgt = false;
                }
                if (!aliveTgt) {
                    tgt = btTarget(ref);       // 目标跑了/死了/退出了 ⇒ 重新挑一个
                }
            } catch (Throwable e) {
                tgt = null;
            }

            double dx = 0;
            double dz = 0;
            try {
                if (tgt != null) {
                    Location tl = tgt.getLocation();
                    if (String.valueOf(tl.getWorld().getName()).equals(String.valueOf(world.getName()))) {
                        dx = tl.getX() - l.getX();
                        dz = tl.getZ() - l.getZ();
                    }
                }
            } catch (Throwable e) {
                dx = 0;
                dz = 0;
            }
            double n = Math.sqrt(dx * dx + dz * dz);
            if (n < 1.0e-4) {
                // 没人 / 目标重合 ⇒ 退回 boss 自己的朝向
                double y0 = 0;
                try {
                    y0 = l.getYaw();
                } catch (Throwable ignored) {
                    // 静默
                }
                dx = -Math.sin(y0 * Math.PI / 180.0);
                dz = Math.cos(y0 * Math.PI / 180.0);
            } else {
                dx /= n;
                dz /= n;
            }
            a.dx = dx;
            a.dz = dz;
            a.tgt = tgt;

            // 斧头起点：卫道士身前 0.8 格、胸口高度
            double sx = l.getX() + a.dx * 0.8;
            double sz = l.getZ() + a.dz * 0.8;
            double sy = l.getY() + BT_S2_AXE_HALF;
            // ★ 起点前面就是墙 ⇒ 斧子生不出来，直接放弃这次施法，别把「妄想」塞进墙里
            if (!btPassableAt(world, (int) Math.floor(sx), (int) Math.floor(sy), (int) Math.floor(sz))) {
                try {
                    btSound(world, l, "block.iron_door_close", 0.5, 1.4);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.axe = null;
                ref.mode = "idle";
                return;
            }
            a.x = sx;
            a.y = sy;
            a.z = sz;
            a.world = world;                       // ★ 记下世界

            // ★ 斧子本体：隐形盔甲架 + 手持铁斧
            a.stand = btAxeMake(world, sx, sy, sz, a.dx, a.dz);
            ref.axeStand = a.stand;

            try {
                btSound(world, l, "entity.item.throw", 0.9, 1.1);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Particle sp = btP("SWEEP_ATTACK", "CRIT", "DUST");
                btFx(world, sp, sx, sy, sz, 8, 0.2, 0.2, 0.2, 0.05);
            } catch (Throwable ignored) {
                // 静默
            }

            btAxeFlyTick(ref);
        } catch (Throwable e) {
            try {
                ref.axe = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btAxeFlyTick(Ref ref) {
        try {
            if (ref == null || ref.axe == null || ref.done) {
                return;
            }
            Axe a = ref.axe;
            if (ref.axeGen != a.gen) {
                return;                 // 旧链作废
            }
            World world = a.world;
            if (world == null) {
                try {
                    world = ref.ent.getWorld();
                } catch (Throwable e) {
                    world = null;
                }
            }
            if (world == null) {
                btAxeStop(ref);
                return;
            }

            // —— ① 先判"下一格能不能过去"（★ 用 isPassable：玻璃挡得住）——
            double step = btClampStep(BT_S2_AXE_SPEED, BT_S2_AXE_RANGE - a.traveled);
            double nx = a.x + a.dx * step;
            double nz = a.z + a.dz * step;

            if (btAxeBlocked(btBlockOf(world), a.x, a.y, a.z, nx, nz)) {
                btAxeStop(ref);            // ★ 撞到方块 → 停在当前位置
                return;
            }

            a.x = nx;
            a.z = nz;
            a.traveled += step;

            // —— ② 让斧子实体跟过去 + 边飞边转 ——
            try {
                if (a.stand != null && btAlive(a.stand)) {
                    double yaw = btAxeStandYaw(a.dx, a.dz, a.traveled);
                    a.stand.teleport(new Location(world, a.x, a.y, a.z, (float) yaw, 12.0f));
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // 飞行拖尾
            try {
                Particle tr = btP("CRIT", "SWEEP_ATTACK", "DUST");
                btFx(world, tr, a.x, a.y, a.z, 2, 0.06, 0.06, 0.06, 0.01);
            } catch (Throwable ignored) {
                // 静默
            }

            // —— ③ 撞到生物？给伤害 + 小小击退（每个目标只吃一次）——
            boolean stop = false;
            try {
                Collection<Entity> list = world.getNearbyEntities(
                        new Location(world, a.x, a.y, a.z), BT_S2_AXE_R, BT_S2_AXE_R, BT_S2_AXE_R);
                for (Entity ent : list) {
                    if (ent == null || !btAlive(ent)) {
                        continue;
                    }
                    if (a.stand != null && ent == a.stand) {
                        continue;
                    }
                    if (btOurs(ent)) {
                        continue;                  // ★ 自己人（卫道士 / 妄想）跳过
                    }
                    String eid;
                    try {
                        eid = String.valueOf(ent.getUniqueId());
                    } catch (Throwable e) {
                        continue;
                    }
                    if (a.hit.contains(eid)) {
                        continue;
                    }
                    a.hit.add(eid);

                    boolean isPlayer = (ent instanceof Player);

                    // ★ boss 可能已经死了 ⇒ 传 null 让 btHurt 退化成无来源伤害
                    Entity src = null;
                    try {
                        if (btAlive(ref.ent)) {
                            src = ref.ent;
                        }
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    btHurt(src, ent, BT_S2_AXE_DMG);
                    btKb(ent, a.dx * BT_S2_AXE_KB, BT_S2_AXE_UP, a.dz * BT_S2_AXE_KB);
                    try {
                        btSound(world, ent.getLocation(), "entity.player.attack.crit", 0.9, 1.1);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        Particle sp2 = btP("CRIT", "SWEEP_ATTACK", "DUST");
                        btFx(world, sp2, a.x, a.y, a.z, 10, 0.25, 0.25, 0.25, 0.05);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    if (isPlayer) {
                        stop = true;       // ★ 撞到玩家 → 停下
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }

            if (stop) {
                btAxeStop(ref);
                return;
            }
            if (a.traveled >= BT_S2_AXE_RANGE - 0.01) {
                btAxeStop(ref);            // 飞满 10 格
                return;
            }
            GS.runLater(() -> btAxeFlyTick(ref), 1);
        } catch (Throwable e) {
            try {
                btAxeStop(ref);
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** 收斧 + 在停下位置生妄想 + 解除演出锁。 */
    private void btAxeStop(Ref ref) {
        try {
            if (ref == null || ref.axe == null) {
                return;
            }
            Axe a = ref.axe;
            World world = a.world;
            double x = a.x;
            double y = a.y;
            double z = a.z;
            if (world == null) {
                try {
                    world = ref.ent.getWorld();
                } catch (Throwable e) {
                    world = null;
                }
            }
            if (world == null) {
                // ★ 绝不猜世界 —— 拿不到就只收斧子
                try {
                    if (a.stand != null) {
                        a.stand.remove();
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.axeStand = null;
                ref.axe = null;
                ref.mode = "idle";
                return;
            }

            // 收掉斧子实体（marker 盔甲架 remove 不掉任何东西）
            try {
                if (a.stand != null) {
                    a.stand.remove();
                }
            } catch (Throwable ignored) {
                // 静默
            }
            ref.axeStand = null;

            // 落地粒子
            try {
                Particle pt = btP("SWEEP_ATTACK", "CRIT", "DUST", "SMOKE_NORMAL");
                btFx(world, pt, x, y, z, 12, 0.25, 0.2, 0.25, 0.04);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ 在斧头停下的位置生成妄想
            try {
                btS2VexSpawn(ref, world, x, y, z);
            } catch (Throwable ignored) {
                // 静默
            }

            ref.axe = null;
            ref.mode = "idle";
        } catch (Throwable e) {
            try {
                ref.axe = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** 造"飞斧"本体：隐身体盔甲架 + 手持铁斧（{@code setVisible(false)} 只隐身体，装备照常显示）。 */
    private static LivingEntity btAxeMake(World world, double x, double y, double z,
                                          double dx, double dz) {
        try {
            EntityType t = btET("ARMOR_STAND", "ARMORSTAND");
            if (t == null) {
                return null;
            }
            double yaw = btAxeSpawnYaw(dx, dz);
            Entity spawned = world.spawnEntity(new Location(world, x, y, z, (float) yaw, 0.0f), t);
            if (!(spawned instanceof ArmorStand as)) {
                return null;
            }
            try {
                as.setVisible(false);        // ★ 隐身体，**手上的斧头照样看得见**
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setMarker(true);          // 无碰撞箱 / 不受重力 / 不掉落 / 不可交互
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setGravity(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setSmall(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setBasePlate(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setArms(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setInvulnerable(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setSilent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setCollidable(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                as.addScoreboardTag("gs_axe");   // ★ reload 残留靠标签清扫
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                EntityEquipment eq = as.getEquipment();
                if (eq != null) {
                    eq.setItemInMainHand(new ItemStack(Material.IRON_AXE, 1));
                    eq.setItemInMainHandDropChance(0.0f);
                }
            } catch (Throwable ignored) {
                // 静默
            }
            return as;
        } catch (Throwable e) {
            return null;
        }
    }

    /** ⑤ 妄想（恼鬼 Vex）：7 伤害 / 25 血 / ★与卫道士同队★ / 9 秒后处死。 */
    private Vex btS2VexSpawn(Ref ref, World world, double x, double y, double z) {
        try {
            if (world == null) {
                return null;
            }

            // 场上限流：数一遍附近的自家妄想
            int live = 0;
            try {
                Collection<Entity> near = world.getNearbyEntities(
                        new Location(world, x, y, z), 48, 24, 48);
                for (Entity en : near) {
                    if (btTag(en).equals(BT_S2_VEX_TAG) && btAlive(en)) {
                        live++;
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            if (live >= BT_S2_VEX_MAX) {
                return null;
            }

            // 站得起来的高度（恼鬼会飞，但先找个不卡墙的位置）
            double sy = y - 0.5;
            try {
                sy = btSafeY(world, x, z, y, 1);
            } catch (Throwable ignored) {
                // 静默
            }

            EntityType vt = btET("VEX");
            if (vt == null) {
                return null;
            }
            Entity spawned = world.spawnEntity(new Location(world, x, sy, z, 0.0f, 0.0f), vt);
            if (!(spawned instanceof Vex v)) {
                return null;
            }

            btApplyHp(v, BT_S2_VEX_HP);
            btSetDmg(v, BT_S2_VEX_DMG);

            try {
                v.setCustomName("\u00a7f\u5984\u60f3");        // §f妄想
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.setCustomNameVisible(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.setRemoveWhenFarAway(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.setPersistent(false);                        // 不写盘
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.setCanPickupItems(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.setSilent(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                v.addScoreboardTag("gs_vex");                  // ★ reload 残留清扫
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 同 btSpawnEntity：`LootTables.EMPTY` 在 1.21.11 不存在 ⇒ 原脚本这一句从未生效
            try {
                EntityEquipment eq = v.getEquipment();
                if (eq != null) {
                    eq.clear();
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // ★★ 与卫道士同队：同一个 PDC 标记（值不同）⇒ btOurs() 为真
            try {
                if (BT_KEY != null) {
                    v.getPersistentDataContainer().set(BT_KEY, PersistentDataType.STRING, BT_S2_VEX_TAG);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            try {
                btSound(world, new Location(world, x, sy, z), "entity.evoker.prepare_summon", 0.8, 1.4);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                btSound(world, new Location(world, x, sy, z), "entity.vex.ambient", 0.5, 1.2);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Particle pt = btP("SMOKE_LARGE", "LARGE_SMOKE", "PORTAL",
                        "SOUL_FIRE_FLAME", "SMOKE_NORMAL");
                btFx(world, pt, x, sy + 0.6, z, 18, 0.3, 0.4, 0.3, 0.04);
            } catch (Throwable ignored) {
                // 静默
            }

            // ★★ 9 秒后处死（避免卡服）：先 setHealth(0) 播个死亡动作，20 刻后无条件 remove 兜底
            GS.runLater(() -> {
                try {
                    if (!btAlive(v)) {
                        try {
                            v.remove();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        return;
                    }
                    try {
                        v.getEquipment().clear();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        v.setHealth(0.0);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }, BT_S2_VEX_LIFE);
            GS.runLater(() -> {
                try {
                    v.remove();
                } catch (Throwable ignored) {
                    // 静默
                }
            }, BT_S2_VEX_LIFE + 20);

            return v;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 碎念执：妄想生成失败 " + e);
            } catch (Throwable e2) {
                // 静默
            }
            return null;
        }
    }

    // ==================================================================
    // ③ 妄念魔 · 技能 1：漂浮蓄力 1 秒 → 出现在玩家头顶 → 1 秒后落地 150 点
    // ==================================================================

    private void btS3Slam(Ref ref, Player tgt) {
        try {
            ref.mode = "slam_charge";
            Location l = ref.ent.getLocation();
            // ★★ **不记落点**（落点改成"蓄力结束那一刻"才算）
            Slam s = new Slam();
            s.i = 0;
            s.y0 = l.getY();
            s.x = l.getX();
            s.z = l.getZ();
            s.phase = "charge";
            s.tgt = tgt;
            ref.slam = s;

            try {
                ref.ent.setAI(false);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                if (ref.ent instanceof Mob mob) {
                    mob.setTarget(null);
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                btSound(l.getWorld(), l, "entity.ravager.roar", 1.0, 0.9);
            } catch (Throwable ignored) {
                // 静默
            }
            btSlamTick(ref);
        } catch (Throwable e) {
            try {
                ref.ent.setAI(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btSlamTick(Ref ref) {
        try {
            if (ref == null || ref.slam == null) {
                return;
            }
            if (!btAlive(ref.ent)) {
                ref.slam = null;
                ref.mode = "idle";
                return;
            }
            Slam s = ref.slam;
            LivingEntity ent = (LivingEntity) ref.ent;
            World world = ent.getWorld();

            s.i++;

            if ("charge".equals(s.phase)) {
                // 原地不动 + 慢慢往上飘
                double rise = btSlamRise(s.i);
                double ny = s.y0 + rise;
                try {
                    ent.teleport(new Location(world, s.x, ny, s.z, ent.getLocation().getYaw(), 0.0f));
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    Particle pc = btP("CLOUD", "LARGE_SMOKE", "SMOKE_NORMAL", "PORTAL");
                    btFx(world, pc, s.x, ny + 1.2, s.z, 6, 0.5, 0.2, 0.5, 0.03);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    Particle pb = btP("SCULK_SOUL", "SOUL", "PORTAL", "INSTANT_EFFECT");
                    btFx(world, pb, s.x, ny + 0.2, s.z, 3, 0.4, 0.4, 0.4, 0.02);
                } catch (Throwable ignored) {
                    // 静默
                }
                if (s.i >= BT_S3_SLAM_CHARGE) {
                    // ★★ **落点在这一刻才算**（用此刻的目标位置）
                    Player st = s.tgt;
                    try {
                        boolean okS = (st != null && st.isOnline());
                        if (!okS) {
                            st = btTarget(ref);
                        }
                    } catch (Throwable e) {
                        st = null;
                    }
                    Location tl2 = null;
                    try {
                        if (st != null) {
                            tl2 = st.getLocation();
                        }
                    } catch (Throwable e) {
                        tl2 = null;
                    }
                    try {
                        if (tl2 != null && String.valueOf(tl2.getWorld().getName())
                                .equals(String.valueOf(world.getName()))) {
                            s.tx = tl2.getX();
                            s.ty = tl2.getY();
                            s.tz = tl2.getZ();
                        } else {
                            s.tx = s.x;
                            s.ty = s.y0;
                            s.tz = s.z;   // 没目标 ⇒ 砸自己脚下
                        }
                    } catch (Throwable e) {
                        s.tx = s.x;
                        s.ty = s.y0;
                        s.tz = s.z;
                    }

                    // 出现在目标头顶
                    s.phase = "hover";
                    s.i = 0;
                    double hy = btSafeY(world, s.tx, s.tz, s.ty + BT_S3_SLAM_ABOVE, BT_S3_HEIGHT);
                    if (hy < s.ty + 0.5) {
                        hy = s.ty + BT_S3_SLAM_ABOVE;
                    }
                    s.hy = hy;
                    try {
                        ent.teleport(new Location(world, s.tx, hy, s.tz,
                                ent.getLocation().getYaw(), 0.0f));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        btSound(world, new Location(world, s.tx, hy, s.tz),
                                "entity.ravager.roar", 1.0, 1.1);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        btSound(world, new Location(world, s.tx, hy, s.tz),
                                "entity.wither.spawn", 0.4, 1.4);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
                GS.runLater(() -> btSlamTick(ref), 1);
                return;
            }

            if ("hover".equals(s.phase)) {
                // 悬停在头顶，等 1 秒 ⇒ 这段时间玩家**跑得掉**
                try {
                    ent.teleport(new Location(world, s.tx, s.hy, s.tz,
                            ent.getLocation().getYaw(), 0.0f));
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    Particle ph = btP("CLOUD", "LARGE_SMOKE", "SMOKE_NORMAL");
                    btFx(world, ph, s.tx, s.hy - 0.3, s.tz, 5, 0.4, 0.1, 0.4, 0.02);
                } catch (Throwable ignored) {
                    // 静默
                }
                if (s.i >= BT_S3_SLAM_HOVER) {
                    s.phase = "drop";
                    s.i = 0;
                    // 落点：脚下实地
                    s.gy = btSafeY(world, s.tx, s.tz, s.ty, BT_S3_HEIGHT);
                    s.sy = s.hy;
                }
                GS.runLater(() -> btSlamTick(ref), 1);
                return;
            }

            // 落地：从悬停点快速砸下去
            double dy = btSlamDropY(s.sy, s.gy, s.i);
            try {
                ent.teleport(new Location(world, s.tx, dy, s.tz, ent.getLocation().getYaw(), 0.0f));
            } catch (Throwable ignored) {
                // 静默
            }

            if (s.i >= BT_S3_SLAM_DROP) {
                // ★ 着地：范围内所有生物 150 点
                try {
                    ent.teleport(new Location(world, s.tx, s.gy, s.tz,
                            ent.getLocation().getYaw(), 0.0f));
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    Particle pe = btP("EXPLOSION_LARGE", "EXPLOSION_HUGE", "CLOUD", "LARGE_SMOKE");
                    btFx(world, pe, s.tx, s.gy + 0.5, s.tz, 12, 0.6, 0.4, 0.6, 0.05);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    btSound(world, new Location(world, s.tx, s.gy, s.tz),
                            "entity.ravager.step", 1.0, 0.6);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    btSound(world, new Location(world, s.tx, s.gy, s.tz),
                            "entity.generic.explode", 0.6, 0.7);
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    btSound(world, new Location(world, s.tx, s.gy, s.tz),
                            "entity.ravager.roar", 0.8, 0.7);
                } catch (Throwable ignored) {
                    // 静默
                }

                try {
                    Collection<Entity> list = world.getNearbyEntities(
                            new Location(world, s.tx, s.gy + 0.5, s.tz),
                            BT_S3_SLAM_R, BT_S3_SLAM_R, BT_S3_SLAM_R);
                    for (Entity v : list) {
                        if (v == null || !btAlive(v)) {
                            continue;
                        }
                        if (v == ent) {
                            continue;                    // ★ 自己不吃这一下
                        }
                        if (btOurs(v)) {
                            continue;
                        }
                        Location vl;
                        try {
                            vl = v.getLocation();
                        } catch (Throwable e) {
                            continue;
                        }
                        if (vl.distance(new Location(world, s.tx, s.gy, s.tz)) > BT_S3_SLAM_R) {
                            continue;
                        }
                        btHurt(ent, v, BT_S3_SLAM_DMG);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }

                try {
                    ent.setAI(true);
                } catch (Throwable ignored) {
                    // 静默
                }
                ref.slam = null;
                ref.mode = "idle";
                return;
            }

            GS.runLater(() -> btSlamTick(ref), 1);
        } catch (Throwable e) {
            try {
                ((LivingEntity) ref.ent).setAI(true);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                ref.slam = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    // ==================================================================
    // 阶段切换：死了 → 演出 → 召唤下一只
    // ==================================================================

    private void btPhaseEnd(Ref ref) {
        try {
            if (ref == null || ref.transitioning) {
                // 已经在切了（多拍重复进来）→ 什么都不做
                return;
            }
            ref.transitioning = true;
            btBarDrop(ref);

            // ★ 正在飞斧 / 蓄力？→ 让旧链作废并把斧子实体收掉
            ref.axeGen = ref.axeGen + 1;
            try {
                if (ref.axeStand != null) {
                    ref.axeStand.remove();
                    ref.axeStand = null;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            ref.axe = null;
            // ★ 正在读条拉人？→ 作废
            ref.dragGen = ref.dragGen + 1;
            ref.drag = null;
            // ★ 本阶段召出的妄想一并清掉
            try {
                btS2VexPurge();
            } catch (Throwable ignored) {
                // 静默
            }

            // ★ BGM：这一只死了 ⇒ **立刻停**
            try {
                btMusicStopAll(ref);
            } catch (Throwable ignored) {
                // 静默
            }

            int stage = ref.stage;
            // ★★★ 只有**第三阶段**掉「妄想碎片」= **固定 6 个**
            try {
                btDropFragments(ref, stage);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★★★ 只有**第二阶段**掉「残樱」= **固定 1 把**
            try {
                btDropCherry(ref, stage);
            } catch (Throwable ignored) {
                // 静默
            }

            if (stage >= 3) {
                // ★ 三阶段全部打完 ⇒ 收摊
                btCleanup(ref);
                return;
            }

            World world = ref.lw;
            double x = (ref.lx == null ? 0 : ref.lx);
            double y = (ref.ly == null ? 64 : ref.ly);
            double z = (ref.lz == null ? 0 : ref.lz);

            if (stage == 1) {
                // ★ 羊驼口水烟雾（3 秒）
                btSmokePuff(world, x, y, z, 0);
                GS.runLater(() -> {
                    try {
                        btNext(ref, 2, world, x, y, z);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }, BT_TRANS_TICKS);
            } else if (stage == 2) {
                // ★ 灵魂沙粒子柱往上冲（3 秒窜上去 20 多格）
                btSoulPillar(world, x, y, z, 0);
                GS.runLater(() -> {
                    try {
                        btNext(ref, 3, world, x, y, z);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }, BT_TRANS_TICKS);
            }
        } catch (Throwable e) {
            btCleanup(ref);
        }
    }

    /** ★ 幻念僧之死：一团羊驼口水（LLAMA_SPIT）烟雾。 */
    private void btSmokePuff(World world, double x, double y, double z, int i) {
        try {
            if (world == null) {
                return;
            }
            if (i >= BT_TRANS_TICKS) {
                return;
            }
            Particle pt = btP("LLAMA_SPIT", "SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL");
            double r = 0.8 + i * 0.01;
            for (int a = 0; a < 4; a++) {
                double ang = (i * 0.35) + a * 1.57;
                btFx(world, pt, x + Math.cos(ang) * r, y + 0.6 + (i % 10) * 0.04,
                        z + Math.sin(ang) * r, 2, 0.12, 0.25, 0.12, 0.02);
            }
            btFx(world, pt, x, y + 0.9, z, 3, 0.4, 0.3, 0.4, 0.03);
            if (i % 15 == 0) {
                try {
                    btSound(world, new Location(world, x, y, z), "entity.llama.spit", 0.7, 0.7);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            final int ni = i + 1;
            GS.runLater(() -> btSmokePuff(world, x, y, z, ni), 1);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★ 碎念执之死：灵魂沙粒子柱冲上天（3 秒内冲到 20 多格）。 */
    private void btSoulPillar(World world, double x, double y, double z, int i) {
        try {
            if (world == null) {
                return;
            }
            if (i >= BT_TRANS_TICKS) {
                return;
            }
            double frac = i / (double) BT_TRANS_TICKS;
            double top = BT_SOUL_HEIGHT * frac;               // 逐拍往上长
            Particle pt = btP("SOUL_SAND", "SOUL", "SCULK_SOUL", "ASH", "WHITE_ASH", "SMOKE_NORMAL");
            for (double h = 0; h <= top; h += 0.5) {
                btFx(world, pt, x, y + h + 0.2, z, 1, 0.12, 0.02, 0.12, 0.01);
            }
            // 柱脚一直往外冒
            btFx(world, pt, x, y + 0.3, z, 6, 0.5, 0.05, 0.5, 0.02);
            if (i == 1) {
                try {
                    btSound(world, new Location(world, x, y, z), "block.soul_sand.step", 1.0, 0.6);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            final int ni = i + 1;
            GS.runLater(() -> btSoulPillar(world, x, y, z, ni), 1);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★ 生成下一阶段。 */
    private void btNext(Ref ref, int stage, World world, double x, double y, double z) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (world == null) {
                btCleanup(ref);
                return;
            }

            double sy = btSafeY(world, x, z, y, (stage == 3 ? BT_S3_HEIGHT : BT_S2_HEIGHT));
            Location loc = new Location(world, x, sy, z, 0.0f, 0.0f);

            LivingEntity ent = null;
            if (stage == 2) {
                ent = btSpawnEntity(world, loc, new String[]{"VINDICATOR", "VINDICATION_ILLAGER"},
                        BT_S2_NAME, BT_S2_HP, BT_S2_DMG, "wangnian2");
                if (ent != null) {
                    ref.stage = 2;
                    ref.maxHp = BT_S2_HP;
                    ref.ent = ent;
                    ref.cd1 = btNow() - BT_S2_SLASH_CD + BT_FIRST_MS;   // 出场保护期内不放技能
                    // ★ 飞斧：出场保护期内同样不放 ⇒ **不会一召唤就甩斧子**
                    ref.cd3 = btNow() - BT_S2_AXE_CD + BT_FIRST_MS;
                    ref.cd2 = 0;
                    ref.axe = null;
                    ref.axeStand = null;
                    ref.axeGen = 0;
                    ref.readyAt = btNow() + BT_FIRST_MS;
                    ref.hpFixUntil = btNow() + BT_HP_FIX_MS;   // ★ 血量保护窗口
                    ref.hpOk = false;                          // ★ 还没确认血量写成功
                    ref.hpScale = 1.0;                         // ★ 伤害缩放（1.0 = 正常）
                    ref.bar = btBarMake("碎念执");
                    ref.barNear = new LinkedHashMap<>();
                    try {
                        btSound(world, loc, "entity.vindicator.death", 1.0, 0.8);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        btSound(world, loc, "entity.wither.spawn", 0.5, 1.2);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        Particle pt = btP("LLAMA_SPIT", "SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL");
                        btFx(world, pt, loc.getX(), loc.getY() + 1.0, loc.getZ(),
                                40, 0.4, 0.6, 0.4, 0.05);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } else if (stage == 3) {
                ent = btSpawnEntity(world, loc, new String[]{"RAVAGER"},
                        BT_S3_NAME, BT_S3_HP, BT_S3_DMG, "wangnian3");
                if (ent != null) {
                    ref.stage = 3;
                    ref.maxHp = BT_S3_HP;
                    ref.ent = ent;
                    ref.cd1 = btNow() - BT_S3_SLAM_CD + BT_FIRST_MS;
                    ref.cd2 = 0;      // ★ 妄念魔没有第二个技能（龙息弹已删）
                    ref.readyAt = btNow() + BT_FIRST_MS;
                    ref.hpFixUntil = btNow() + BT_HP_FIX_MS;   // ★ 血量保护窗口
                    ref.hpOk = false;                          // ★ 还没确认血量写成功
                    ref.hpScale = 1.0;                         // ★ 伤害缩放（1.0 = 正常）
                    ref.bar = btBarMake("妄念魔");
                    ref.barNear = new LinkedHashMap<>();
                    try {
                        btSound(world, loc, "entity.ravager.roar", 1.0, 0.8);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        btSound(world, loc, "entity.wither.spawn", 0.6, 1.0);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        Particle pt3 = btP("SOUL_SAND", "SOUL", "SCULK_SOUL", "LARGE_SMOKE");
                        btFx(world, pt3, loc.getX(), loc.getY() + 1.0, loc.getZ(),
                                40, 0.5, 0.6, 0.5, 0.05);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            if (ent == null) {
                btCleanup(ref);
                return;
            }

            ref.transitioning = false;
            ref.mode = "idle";
            // ★ 锁链拽回是**跨阶段共用状态** ⇒ 切阶段时必须作废旧链 + 重置 CD
            ref.dragGen = ref.dragGen + 1;
            ref.drag = null;
            // ★ 锁链用**自己**的出场延迟（10 秒），与本阶段其他技能（4 秒）错开
            ref.cd4 = btNow() - BT_DRAG_CD + BT_DRAG_FIRST_MS;
            ref.dragged = new LinkedHashSet<>();           // ★ 换阶段 ⇒ 换一批没被拉过的人
            ref.lw = world;
            ref.lx = x;
            ref.ly = sy;
            ref.lz = z;

            try {
                btBarSync(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 新阶段起自己那首 BGM
            ref.music = new LinkedHashMap<>();
            try {
                btMusicLoop(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            GS.runLater(() -> btTick(ref), BT_TICK);
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念：切换阶段出错 " + e);
            } catch (Throwable ignored) {
                // 静默
            }
            btCleanup(ref);
        }
    }

    // ==================================================================
    // ★★★ 通用技能「锁链拽回」—— **三个阶段共用**（反挖三填一）
    // ==================================================================

    private void btDragStart(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            if (!btAlive(ref.ent)) {
                return;
            }
            ref.mode = "drag";
            ref.dragGen = ref.dragGen + 1;
            Drag d = new Drag();
            d.gen = ref.dragGen;
            d.i = 0;
            ref.drag = d;                       // ★ 只锁 uuid，不存 Player 引用
            try {
                Location l0 = ref.ent.getLocation();
                btSound(l0.getWorld(), l0, "block.chain.break", 0.9, 0.7);
            } catch (Throwable ignored) {
                // 静默
            }
            btDragTick(ref);
        } catch (Throwable e) {
            try {
                ref.drag = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    private void btDragTick(Ref ref) {
        try {
            if (ref == null || ref.drag == null || ref.done) {
                return;
            }
            Drag d = ref.drag;
            if (ref.dragGen != d.gen) {
                return;                     // 旧链作废
            }
            if (!btAlive(ref.ent)) {
                ref.drag = null;
                ref.mode = "idle";
                return;
            }

            d.i++;
            World world = null;
            Location l = null;
            try {
                l = ref.ent.getLocation();
                world = l.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }
            if (l == null || world == null) {
                ref.drag = null;
                ref.mode = "idle";
                return;
            }

            // ★ 读条期间：把范围内每个人都**锁进 hit**（只记 uuid，不持有引用）
            if (d.i == 1 || d.i % 4 == 0) {
                for (Player p : btDragPlayers(world, l)) {
                    try {
                        d.hit.add(String.valueOf(p.getUniqueId()));
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }

            // 锁链特效：boss → 每个被锁的人，拉一条灵魂沙链
            try {
                Particle pt = btP("CHAIN", "SOUL", "SCULK_SOUL", "ELECTRIC_SPARK", "SMOKE_NORMAL");
                int n = btDragCount(d.hit.size());
                for (int k = 0; k < n; k++) {
                    Player p = btDragPlayerById(world, d, k);
                    if (p == null) {
                        continue;
                    }
                    Location pl;
                    try {
                        pl = p.getLocation();
                    } catch (Throwable e) {
                        continue;
                    }
                    // 沿连线取 8 个点（每刻往前挪一点 ⇒ 看着像"正在被拽过来"）
                    double f = ((d.i % 8) / 8.0);
                    btFx(world, pt, l.getX() + (pl.getX() - l.getX()) * f, l.getY() + 1.2,
                            l.getZ() + (pl.getZ() - l.getZ()) * f, 2, 0.05, 0.05, 0.05, 0);
                }
            } catch (Throwable ignored) {
                // 静默
            }

            if (d.i % 6 == 0) {
                try {
                    btSound(world, l, "block.conduit.ambient", 0.35, 1.7);
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            if (d.i >= BT_DRAG_READ) {
                btDragFinish(ref);
                return;
            }
            GS.runLater(() -> btDragTick(ref), 1);
        } catch (Throwable e) {
            try {
                ref.drag = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** ③ 收网：把锁定的人**散开围成一圈**强制拽到 boss 身边。 */
    private void btDragFinish(Ref ref) {
        try {
            if (ref == null || ref.drag == null) {
                return;
            }
            Drag d = ref.drag;
            World world = null;
            Location l = null;
            try {
                l = ref.ent.getLocation();
                world = l.getWorld();
            } catch (Throwable ignored) {
                // 静默
            }
            ref.drag = null;
            ref.mode = "idle";
            if (l == null || world == null) {
                return;
            }

            // ★ 出手这一刻**重新核范围**：读条期间跑出 BT_DRAG_RANGE 的就放过他（不追）
            //   ★ 只判上限，**不判下限**
            List<Player> cand = new ArrayList<>();
            int n = btDragCount(d.hit.size());
            for (int k = 0; k < n; k++) {
                Player p = btDragPlayerById(world, d, k);
                if (p == null) {
                    continue;
                }
                boolean ok = false;
                try {
                    if (String.valueOf(p.getWorld().getName()).equals(String.valueOf(world.getName()))) {
                        if (p.getLocation().distance(l) <= BT_DRAG_RANGE) {
                            ok = true;
                        }
                    }
                } catch (Throwable e) {
                    ok = false;
                }
                if (ok) {
                    cand.add(p);
                }
            }
            if (cand.isEmpty()) {
                return;
            }
            if (cand.size() > BT_DRAG_CAP) {
                cand = new ArrayList<>(cand.subList(0, BT_DRAG_CAP));
            }

            // 落点：以 boss 为圆心，**围成一圈**铺开
            int cnt = cand.size();
            double yaw0 = 0.0;
            try {
                yaw0 = l.getYaw();
            } catch (Throwable ignored) {
                // 静默
            }
            double sy = btSafeY(world, l.getX(), l.getZ(), l.getY() + BT_DRAG_HEIGHT, 2);

            // 本轮已拉名单：拉过的人这一轮不再拉第二遍
            Set<String> done = ref.dragged;
            if (done == null) {
                done = new LinkedHashSet<>();
                ref.dragged = done;
            }

            BtBlock probe = btBlockOf(world);
            int moved = 0;
            for (int i = 0; i < cnt; i++) {
                Player pl2 = cand.get(i);
                try {
                    // 以 boss 朝向为基准均匀铺开；先试正前方那个点，不行就左右各挪一点（躲开墙）
                    boolean okPos = false;
                    for (int tryN = 0; tryN < 5 && !okPos; tryN++) {
                        double[] slot = btDragSlot(probe, l.getX(), l.getZ(), sy, yaw0, i, cnt, tryN);
                        if (slot == null) {
                            continue;
                        }
                        double ax = slot[0];
                        double az = slot[1];
                        double ay = slot[2];
                        // ★ 先把人从载具上卸下来
                        try {
                            if (pl2.getVehicle() != null) {
                                pl2.leaveVehicle();
                            }
                        } catch (Throwable ignored) {
                            // 静默
                        }
                        try {
                            pl2.teleport(new Location(world, ax, ay, az,
                                    pl2.getLocation().getYaw(), 0.0f));
                            okPos = true;
                        } catch (Throwable e) {
                            okPos = false;
                        }
                    }
                    if (!okPos) {
                        continue;
                    }
                    try {
                        done.add(String.valueOf(pl2.getUniqueId()));   // 记进"本轮已拉"
                    } catch (Throwable ignored) {
                        // 静默
                    }

                    // 传送特效 + 音效（让玩家知道"被拽了"，不至于莫名其妙）
                    try {
                        Particle pp = btP("PORTAL", "SOUL_FIRE_FLAME", "SMOKE_LARGE",
                                "LARGE_SMOKE", "SMOKE_NORMAL");
                        btFx(world, pp, l.getX(), l.getY() + 1.0, l.getZ(), 30, 0.4, 0.6, 0.4, 0.05);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    try {
                        btSound(world, l, "entity.enderman.teleport", 1.0, 0.7);
                    } catch (Throwable ignored) {
                        // 静默
                    }
                    moved++;
                } catch (Throwable ignored) {
                    // 静默
                }
            }

            if (moved > 0) {
                try {
                    btSound(world, l, "entity.wither.spawn", 0.5, 1.5);
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable e) {
            try {
                ref.drag = null;
                ref.mode = "idle";
            } catch (Throwable ignored) {
                // 静默
            }
        }
    }

    /** 列出范围内"该拉"的玩家（跳过旁观 / 创造（看开关）/ 世界不符的人）。 */
    private List<Player> btDragPlayers(World world, Location bossLoc) {
        List<Player> out = new ArrayList<>();
        try {
            String wname = String.valueOf(world.getName());
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                String gm = "";
                try {
                    gm = String.valueOf(p.getGameMode().name());
                } catch (Throwable ignored) {
                    // 静默
                }
                if (gm.contains("SPECTATOR")) {
                    continue;
                }
                if (gm.contains("CREATIVE") && !BT_CREATIVE_FALLBACK) {
                    continue;
                }
                try {
                    if (!p.isOnline()) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                double d2;
                try {
                    d2 = p.getLocation().distance(bossLoc);
                } catch (Throwable e) {
                    continue;
                }
                // ★ 只判上限（64 格），**不判下限**
                if (d2 > BT_DRAG_RANGE) {
                    continue;
                }
                out.add(p);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        return out;
    }

    /** 从 lock 表里取回 Player（按 uuid 找已在线的玩家；取不到就跳过）。 */
    private Player btDragPlayerById(World world, Drag d, int idx) {
        try {
            List<String> keys = new ArrayList<>(d.hit);
            if (idx < 0 || idx >= keys.size()) {
                return null;
            }
            String uid = keys.get(idx);
            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (String.valueOf(p.getUniqueId()).equals(uid)) {
                        if (!String.valueOf(p.getWorld().getName())
                                .equals(String.valueOf(world.getName()))) {
                            return null;
                        }
                        return p;
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 静默
        }
        return null;
    }

    /**
     * ★ 「该拉人了吗？」—— 64 格内**还有没被拉过的人**就放（三个阶段共用）。
     *
     * <p>⚠ 2026-10-07 语义**改过**：旧版 = 「任意一个目标在 {@code trig} 格外就放」
     * —— 挖三填一的玩家永远不满足。现在 = 「64 格内**有任何人**就放」
     * （{@code trig <= 0} 时不设距离门槛）。
     */
    private boolean btShouldDrag(Ref ref, double trig) {
        try {
            if (ref == null || !btAlive(ref.ent)) {
                return false;
            }
            Entity ent = ref.ent;
            World world = ent.getWorld();
            Location el = ent.getLocation();
            String wname = String.valueOf(world.getName());
            Set<String> done = ref.dragged;
            if (done == null) {
                done = new LinkedHashSet<>();
                ref.dragged = done;
            }

            for (Player p : Bukkit.getServer().getOnlinePlayers()) {
                if (p == null) {
                    continue;
                }
                try {
                    if (!String.valueOf(p.getWorld().getName()).equals(wname)) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                String gm = "";
                try {
                    gm = String.valueOf(p.getGameMode().name());
                } catch (Throwable ignored) {
                    // 静默
                }
                if (gm.contains("SPECTATOR")) {
                    continue;
                }
                if (gm.contains("CREATIVE") && !BT_CREATIVE_FALLBACK) {
                    continue;
                }
                try {
                    if (!p.isOnline()) {
                        continue;
                    }
                } catch (Throwable e) {
                    continue;
                }
                String uid;
                try {
                    uid = String.valueOf(p.getUniqueId());
                } catch (Throwable e) {
                    continue;
                }
                if (done.contains(uid)) {
                    continue;                     // 这轮已经拉过
                }
                Location pl;
                try {
                    pl = p.getLocation();
                } catch (Throwable e) {
                    continue;
                }
                double d2;
                try {
                    d2 = pl.distance(el);
                } catch (Throwable e) {
                    continue;
                }
                if (d2 > BT_DRAG_RANGE) {
                    continue;                  // 64 格外不追
                }
                // `trig > 0` 时保留"多远以外才放"的老语义（默认 0 = 不设门槛）
                if (trig > 0 && d2 <= trig) {
                    continue;
                }
                return true;
            }
        } catch (Throwable ignored) {
            // 静默
        }
        return false;
    }

    /**
     * ★ 妄想"忠于队伍"兜底：每拍把「指向自家卫道士」的 target 清掉。
     * 真正的拦截在 {@code EntityTargetEvent} 里，这里只是双保险。
     */
    private void btS2VexLoyal(Ref ref) {
        try {
            if (ref == null || !btAlive(ref.ent)) {
                return;
            }
            World world = ref.ent.getWorld();
            for (Entity v : world.getNearbyEntities(ref.ent.getLocation(), 24, 12, 24)) {
                if (!btTag(v).equals(BT_S2_VEX_TAG) || !btAlive(v)) {
                    continue;
                }
                LivingEntity tg;
                try {
                    tg = (v instanceof Mob mob) ? mob.getTarget() : null;
                } catch (Throwable e) {
                    tg = null;
                }
                if (tg == null || !btOurs(tg)) {
                    continue;
                }
                try {
                    if (v instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /** ★ 清掉全服所有"我们家的妄想"（阶段切换 / 收摊 / reload 残留都靠它）。 */
    private void btS2VexPurge() {
        try {
            for (World w : Bukkit.getServer().getWorlds()) {
                if (w == null) {
                    continue;
                }
                Collection<Entity> ents;
                try {
                    ents = w.getEntities();
                } catch (Throwable e) {
                    continue;
                }
                if (ents == null) {
                    continue;
                }
                for (Entity en : ents) {
                    if (en == null) {
                        continue;
                    }
                    if (!btTag(en).equals(BT_S2_VEX_TAG)) {
                        continue;
                    }
                    try {
                        en.remove();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    /**
     * ★★ reload 残留清扫：启动 3 秒后按**记分板标签**扫一遍清掉
     * 「妄想」（{@code gs_vex}）与「飞斧盔甲架」（{@code gs_axe}）。
     */
    private void btSweepReloadLeftovers() {
        try {
            int n = 0;
            for (World w : Bukkit.getServer().getWorlds()) {
                if (w == null) {
                    continue;
                }
                Collection<Entity> ents;
                try {
                    ents = w.getEntities();
                } catch (Throwable e) {
                    continue;
                }
                if (ents == null) {
                    continue;
                }
                for (Entity en : ents) {
                    if (en == null) {
                        continue;
                    }
                    Set<String> tags;
                    try {
                        tags = en.getScoreboardTags();
                    } catch (Throwable e) {
                        tags = null;
                    }
                    if (tags == null) {
                        continue;
                    }
                    boolean isVex = tags.contains("gs_vex");
                    boolean isAxe = tags.contains("gs_axe");
                    if (btTag(en).equals(BT_S2_VEX_TAG)) {
                        isVex = true;
                    }
                    if (!isVex && !isAxe) {
                        continue;
                    }
                    // ★ 正在被本实例使用的斧子不动（只清"无主"的）
                    if (isAxe) {
                        boolean owned = false;
                        for (Ref r : BT_ACTIVE.values()) {
                            try {
                                if (r != null && r.axeStand != null && r.axeStand == en) {
                                    owned = true;
                                    break;
                                }
                            } catch (Throwable ignored) {
                                // 静默
                            }
                        }
                        if (owned) {
                            continue;
                        }
                    }
                    try {
                        en.remove();
                        n++;
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            }
            if (n > 0) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 碎念执：已清理 " + n
                            + " 个 reload 残留（妄想 / 飞斧）");
                } catch (Throwable ignored) {
                    // 静默
                }
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 整条线收摊（三阶段全部结束 / 出错兜底）
    // ==================================================================

    private void btCleanup(Ref ref) {
        try {
            if (ref == null || ref.done) {
                return;
            }
            ref.done = true;
            try {
                btBarDrop(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                btMusicStopAll(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 飞斧 / 妄想一并收掉
            ref.axeGen = ref.axeGen + 1;
            try {
                if (ref.axeStand != null) {
                    ref.axeStand.remove();
                    ref.axeStand = null;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            ref.axe = null;
            ref.mode = "idle";
            // ★ 读条拉人也一并作废
            ref.dragGen = ref.dragGen + 1;
            ref.drag = null;
            try {
                btS2VexPurge();
            } catch (Throwable ignored) {
                // 静默
            }

            // 万一还留着残兵（中间那只死了第三只没生成成功之类）
            try {
                if (btAlive(ref.ent)) {
                    EntityEquipment eq = ((LivingEntity) ref.ent).getEquipment();
                    if (eq != null) {
                        try {
                            eq.clear();
                        } catch (Throwable ignored) {
                            // 静默
                        }
                    }
                    try {
                        ref.ent.remove();
                    } catch (Throwable ignored) {
                        // 静默
                    }
                }
            } catch (Throwable ignored) {
                // 静默
            }
            ref.ent = null;

            // ★ 解锁"这个玩家还能再召唤"
            try {
                if (ref.owner != null && BT_ACTIVE.get(ref.owner) == ref) {
                    BT_ACTIVE.remove(ref.owner);
                }
            } catch (Throwable ignored) {
                // 静默
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 召唤入口
    // ==================================================================

    private boolean btSummon(Player p) {
        try {
            String uuid = String.valueOf(p.getUniqueId());

            // ① 这条线上还有没打完的 → 静默忽略（**三阶段全死**才能再召）
            Ref old = BT_ACTIVE.get(uuid);
            if (old != null && !old.done) {
                return false;
            }

            World world = p.getWorld();
            Location base = p.getLocation();
            double yaw = 0;
            try {
                yaw = base.getYaw();
            } catch (Throwable ignored) {
                // 静默
            }
            double[] ahead = btSpawnAhead(base.getX(), base.getZ(), yaw, BT_SPAWN_AHEAD);
            double lx = ahead[0];
            double lz = ahead[1];
            double ly = btSafeY(world, lx, lz, base.getY(), BT_S1_HEIGHT);
            Location loc = new Location(world, lx, ly, lz, (float) yaw, 0.0f);

            LivingEntity ent = btSpawnEntity(world, loc, new String[]{"PILLAGER"},
                    BT_S1_NAME, BT_S1_HP, BT_S1_DMG, "wangnian1");
            if (ent == null) {
                return false;
            }
            // ★ 立刻清空双手（把原版掠夺者自带的弩拿走）
            try {
                btS1Hands(ent);
            } catch (Throwable ignored) {
                // 静默
            }

            Ref ref = new Ref();
            ref.owner = uuid;
            ref.stage = 1;
            ref.ent = ent;
            ref.maxHp = BT_S1_HP;
            ref.mode = "idle";
            ref.cd1 = btNow() - BT_S1_FANG_CD + BT_FIRST_MS;      // 出场保护期内不放技能
            ref.cd2 = btNow() - BT_S1_BLINK_CD + BT_FIRST_MS;
            ref.cd3 = btNow() - BT_S2_AXE_CD + BT_FIRST_MS;        // ★ 飞斧冷却（第二阶段才用到）
            ref.cd4 = btNow() - BT_DRAG_CD + BT_DRAG_FIRST_MS;     // ★ 锁链：出场延迟 **10 秒**
            ref.atkAt = btNow() - BT_S1_ATK_CD + BT_FIRST_MS;      // ★ 本体射击
            ref.readyAt = btNow() + BT_FIRST_MS;
            ref.hpFixUntil = btNow() + BT_HP_FIX_MS;   // ★ 血量保护窗口
            ref.hpOk = false;                          // ★ 还没确认血量写成功
            ref.hpScale = 1.0;                         // ★ 伤害缩放（1.0 = 正常）
            ref.bar = null;
            ref.barNear = new LinkedHashMap<>();
            ref.dragGen = 0;
            ref.dragged = new LinkedHashSet<>();
            ref.axeGen = 0;
            ref.transitioning = false;
            ref.done = false;
            ref.music = new LinkedHashMap<>();
            ref.lw = world;
            ref.lx = lx;
            ref.ly = ly;
            ref.lz = lz;

            ref.bar = btBarMake("幻念僧");
            try {
                btBarSync(ref);
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 一阶段 BGM：Precipice，循环播放
            try {
                btMusicLoop(ref);
            } catch (Throwable ignored) {
                // 静默
            }

            BT_ACTIVE.put(uuid, ref);

            try {
                btSound(world, loc, "entity.evoker.prepare_summon", 1.0, 1.0);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                btSound(world, loc, "entity.wither.spawn", 0.4, 1.4);
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Particle pt = btP("PORTAL", "SMOKE_LARGE", "LARGE_SMOKE", "SMOKE_NORMAL");
                btFx(world, pt, lx, ly + 1.0, lz, 30, 0.5, 0.6, 0.5, 0.05);
            } catch (Throwable ignored) {
                // 静默
            }

            GS.runLater(() -> btTick(ref), BT_TICK);
            return true;
        } catch (Throwable e) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念：召唤出错 " + e);
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        }
    }

    // ==================================================================
    // 判定「手里拿的是不是本物品」/ 消耗
    // ==================================================================

    private static boolean btIsOurItem(ItemStack stack) {
        return Guards.isOurItem(stack, BT_ITEM_ID);
    }

    private static boolean btIsMainHandUse(PlayerRightClickEvent event, Player p) {
        return Guards.isMainHandUse(event, p, BT_ITEM_ID);
    }

    private static void btConsume(Player p) {
        try {
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            ItemStack hand = inv.getItemInMainHand();
            if (hand == null) {
                return;
            }
            int amt = 0;
            try {
                amt = hand.getAmount();
            } catch (Throwable e) {
                amt = 0;
            }
            if (amt > 1) {
                hand.setAmount(amt - 1);
            } else {
                inv.setItemInMainHand(null);
            }
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    // ==================================================================
    // 监听器 —— ★ 原脚本**惰性注册**（第一次 onUse 时才挂），这里逐字保留
    //   ⚠ 原脚本用 pm.registerEvent(..., RSC插件) 注册；原生版挂在 GS.plugin() 名下。
    // ==================================================================

    private boolean btRegisterDeath() {
        if (BT_REG_DEATH) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new DeathListener(), GS.plugin());
            BT_REG_DEATH = true;
            return true;
        } catch (Throwable e1) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念：死亡掉落拦截注册失败 " + e1);
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        }
    }

    private boolean btRegisterSpell() {
        if (BT_REG_SPELL) {
            return true;
        }
        try {
            int ok = 0;
            try {
                Bukkit.getPluginManager().registerEvents(new DamageByEntityListener(), GS.plugin());
                ok++;
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Bukkit.getPluginManager().registerEvents(new HpScaleListener(), GS.plugin());
                ok++;
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                Bukkit.getPluginManager().registerEvents(new TargetListener(), GS.plugin());
                ok++;
            } catch (Throwable ignored) {
                // 静默
            }
            // ★ 原脚本还有第 4 块 EntityCastSpellEvent —— 该类在 1.21.11 不存在
            //   ⇒ 原脚本那一块从未注册成功 ⇒ ok 只有 3（见类注释的死代码清单）
            BT_REG_SPELL = (ok > 0);
            if (BT_REG_SPELL) {
                try {
                    Bukkit.getLogger().info("[构思技艺] 妄念：原版施法 / 爆炸 / 阵营拦截 已启用（"
                            + ok + " 条）");
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            return BT_REG_SPELL;
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean btRegisterGuard() {
        if (BT_GUARD_OK) {
            return true;
        }
        try {
            BT_GUARD_OK = InteractGuard.register(BT_ITEM_ID);
            return BT_GUARD_OK;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ★ 清掉落：所有阶段的原版掉落物一律清空。 */
    private final class DeathListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent e) {
            try {
                Entity ent = e.getEntity();
                if (ent == null) {
                    return;
                }
                if (!btOurs(ent)) {
                    return;
                }
                // ★ 原版掉落物全部删除：装备（含 Phase 1 的弩）+ 背包 + 战利品表 + 经验
                try {
                    EntityEquipment eq = ((LivingEntity) ent).getEquipment();
                    if (eq != null) {
                        eq.clear();
                    }
                } catch (Throwable err) {
                    // 静默
                }
                // ★ 同 btSpawnEntity：`LootTables.EMPTY` 在 1.21.11 不存在 ⇒ 原脚本这一句从未生效
                try {
                    e.getDrops().clear();
                } catch (Throwable err) {
                    // 静默
                }
                // 清完装备后再清一遍（有些服务端会把刚清掉的装备又塞回 drops）
                try {
                    e.getDrops().clear();
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    e.setDroppedExp(0);
                } catch (Throwable err) {
                    // 静默
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** ★ 伤害兜底：自家 boss 造成的**非近战**伤害免掉 + 自家弩箭固定 12 点 + 自己人免伤。 */
    private final class DamageByEntityListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageByEntityEvent e) {
            try {
                Entity src = e.getDamager();
                Entity vic = e.getEntity();
                if (src == null || vic == null) {
                    return;
                }

                String cause = "";
                try {
                    cause = String.valueOf(e.getCause().name());
                } catch (Throwable err) {
                    cause = "";
                }

                // ★★ 自己人打自己人 ⇒ 一律免伤
                if (btOurs(src) && btOurs(vic)) {
                    try {
                        e.setCancelled(true);
                    } catch (Throwable err) {
                        // 静默
                    }
                    try {
                        e.setDamage(0.0);
                    } catch (Throwable err) {
                        // 静默
                    }
                    return;
                }

                // ★ 自家的**弩箭** ⇒ 伤害统一改成 BT_S1_ARROW_DMG（12 点）
                try {
                    if (src instanceof org.bukkit.entity.Projectile proj) {
                        Entity shooter = null;
                        try {
                            org.bukkit.projectiles.ProjectileSource s = proj.getShooter();
                            if (s instanceof Entity se) {
                                shooter = se;
                            }
                        } catch (Throwable err2) {
                            shooter = null;
                        }
                        if (shooter != null && btOurs(shooter)) {
                            if (btOurs(vic)) {                 // 打到自己人 ⇒ 免伤
                                try {
                                    e.setCancelled(true);
                                } catch (Throwable err2) {
                                    // 静默
                                }
                                try {
                                    e.setDamage(0.0);
                                } catch (Throwable err2) {
                                    // 静默
                                }
                            } else {                           // 打到别人 ⇒ 固定 12 点
                                try {
                                    e.setDamage(BT_S1_ARROW_DMG);
                                } catch (Throwable err2) {
                                    // 静默
                                }
                            }
                            return;
                        }
                    }
                } catch (Throwable err) {
                    // 静默
                }

                if (btOurs(src) && !cause.contains("ATTACK")) {
                    // 我们自己 boss 造成的**非近战**伤害 ⇒ 一律免掉
                    try {
                        e.setCancelled(true);
                    } catch (Throwable err) {
                        // 静默
                    }
                    try {
                        e.setDamage(0.0);
                    } catch (Throwable err) {
                        // 静默
                    }
                    return;
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /**
     * ★★ 血量降级兜底：万一 {@code MAX_HEALTH} 死活写不进去，就按 {@code hpScale}
     * 把受到的伤害等比缩小。
     *
     * <p>⚠ 原脚本注释说"注册基类 {@code EntityDamageEvent}（子类事件也会派发过来）"，
     * 但 Bukkit 里 {@code EntityDamageByEntityEvent} <b>有自己的 HandlerList</b>
     * ⇒ 近战 / 箭伤其实**不会**走到这条。这里逐字保留同样的注册类 ⇒ 行为一致
     * （<b>刻意不"修好"</b>，改了就是改行为）。
     */
    private final class HpScaleListener implements Listener {
        @EventHandler(priority = EventPriority.HIGH)
        public void onDamage(EntityDamageEvent e) {
            try {
                Entity vic = e.getEntity();
                if (vic == null || !btOurs(vic)) {
                    return;
                }
                Ref rf = btRefOf(vic);
                if (rf == null) {
                    return;
                }
                double sc = rf.hpScale;
                if (sc >= 1.0 || sc <= 0.0) {
                    return;
                }
                double d = 0.0;
                try {
                    d = e.getDamage();
                } catch (Throwable err) {
                    d = 0.0;
                }
                if (d <= 0.0) {
                    return;
                }
                try {
                    e.setDamage(d * sc);
                } catch (Throwable err) {
                    // 静默
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    /** ★★ 阵营：自己人不互相索敌。 */
    private final class TargetListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onTarget(EntityTargetEvent e) {
            try {
                Entity ent = e.getEntity();
                Entity tgt = e.getTarget();
                if (ent == null || tgt == null) {
                    return;
                }
                if (!btOurs(ent)) {
                    return;
                }
                if (!btOurs(tgt)) {
                    return;
                }
                try {
                    e.setCancelled(true);
                } catch (Throwable err) {
                    // 静默
                }
                try {
                    if (ent instanceof Mob mob) {
                        mob.setTarget(null);
                    }
                } catch (Throwable err) {
                    // 静默
                }
            } catch (Throwable err) {
                // 与原脚本一致：静默
            }
        }
    }

    // ==================================================================
    // 入口
    // ==================================================================

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            try {
                btRegisterDeath();
            } catch (Throwable ignored) {
                // 惰性重试
            }
            try {
                btRegisterSpell();
            } catch (Throwable ignored) {
                // 惰性重试
            }
            try {
                btRegisterGuard();
            } catch (Throwable ignored) {
                // 惰性重试
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

            if (!btIsMainHandUse(event, p)) {
                return null;
            }

            String uuid = String.valueOf(p.getUniqueId());
            long now = btNow();
            Long last = BT_LAST.get(uuid);
            if (last != null && (now - last) < 500) {
                return null;
            }
            BT_LAST.put(uuid, now);

            if (!btSummon(p)) {
                return null;
            }
            if (BT_CONSUME) {
                btConsume(p);
            }
        } catch (Throwable err) {
            try {
                Bukkit.getLogger().warning("[构思技艺] 妄念 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 静默
            }
        }
        return null;
    }

    // ★ 玩家下线：**不删 boss**（同世界别人可能正在打），这条线的归属也留着。
    //   ⇒ 原脚本没有 onPlayerQuit，这里也不覆写（反向断言见探针）。

    // ==================================================================
    // 数据模型（等价于原脚本里的那些匿名对象字面量）
    // ==================================================================

    /** 一条 boss 线的全部状态（三个阶段共用同一个 ref）。 */
    static final class Ref {
        String owner;                 // 召唤者 uuid
        int stage = 1;
        LivingEntity ent;
        double maxHp;
        String mode = "idle";         // idle / fang / blink / slash / axe / slam_charge / drag
        long cd1;
        long cd2;
        long cd3;
        long cd4;
        long atkAt;
        long readyAt;
        long hpFixUntil;
        boolean hpOk;
        double hpScale = 1.0;
        BossBar bar;
        Map<String, Player> barNear = new LinkedHashMap<>();
        Fang fang;
        Blink blink;
        Slash slash;
        Slam slam;
        Drag drag;
        int dragGen;
        Set<String> dragged = new LinkedHashSet<>();
        Axe axe;
        LivingEntity axeStand;
        int axeGen;
        boolean transitioning;
        boolean done;
        int musGen;
        Map<String, Music> music = new LinkedHashMap<>();
        World lw;
        Double lx;
        Double ly;
        Double lz;
        int purgeN;
    }

    /** 追踪鳄鱼嘴的推进状态。 */
    static final class Fang {
        int life;
        double x;
        double y;
        double z;
        double traveled;
        int fangSeq;
    }

    /** 瞬影读条状态。 */
    static final class Blink {
        int i;
        Player tgt;
    }

    /** 竖斩状态。 */
    static final class Slash {
        int i;
        double x;
        double y;
        double z;
        double dx;
        double dz;
        double traveled;
        boolean traveledSet;
        Set<String> hit = new LinkedHashSet<>();
    }

    /** 星环飞斧状态。 */
    static final class Axe {
        int gen;
        int i;
        double dx;
        double dz;
        double traveled;
        double x;
        double y;
        double z;
        World world;
        LivingEntity stand;
        Player tgt;
        Set<String> hit = new LinkedHashSet<>();
    }

    /** 坠地状态。 */
    static final class Slam {
        int i;
        double y0;
        double x;
        double z;
        String phase = "charge";
        Player tgt;
        double tx;
        double ty;
        double tz;
        double hy;
        double gy;
        double sy;
    }

    /** 锁链拽回状态。 */
    static final class Drag {
        int gen;
        int i;
        /** ★ 有序集合：原脚本靠 {@code for (var k in d.hit)} 的**插入顺序**取第 idx 个人。 */
        Set<String> hit = new LinkedHashSet<>();
    }

    /** 某个玩家正在听的 BGM。 */
    static final class Music {
        Player p;
        long at;
        int stage;
    }
}
