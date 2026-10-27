package com.qinghuacisj.gs.script.module;

import com.qinghuacisj.gs.GS;
import com.qinghuacisj.gs.util.Chat;
import io.github.thebusybiscuit.slimefun4.api.events.PlayerRightClickEvent;
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.PufferFish;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code gs_maiyou_wine.js} 的 Java 化版本 —— 「麦友的酒」。
 *
 * <p>物品材质是<b>桶装河豚</b> {@code PUFFERFISH_BUCKET}（不会像「河豚」那样右键被吃掉），
 * 原版「倒出河豚」的交互会被我们拦下 → 右键只走本脚本的召唤。
 *
 * <p>右键一个方块：在<b>被点方块的「被点面」那一格</b>召唤一只河豚，名字叫「麦友的酒」，
 * 8 秒后<b>直接移除</b>（remove，不走死亡流程）。这只河豚 ——
 * <ul>
 *   <li><b>不会移动、不会掉下去</b>：关 AI / 关重力，而且每刻都被「钉」回出生点
 *       （位置写死、只换朝向）；</li>
 *   <li><b>原地 360° 转圈</b>：8 秒转 8 圈 → 160 刻共 2880°，每刻 18°；</li>
 *   <li><b>不断变大变小</b>：用 SCALE 属性做正弦脉动（周期 0.5 秒），0.60 → 2.20 倍；</li>
 *   <li><b>对任何生物都不造成伤害</b>（四道保险：关 AI / 不可碰撞 / 压回小状态 /
 *       同步监听器兜底取消伤害）；</li>
 *   <li><b>与「附近有没有玩家」无关</b>：行为全由我们自己的计时器驱动。</li>
 * </ul>
 *
 * <p>★★ <b>两段「看着在工作、实际永远走不到」的死代码，本移植<b>不照抄</b></b>
 * —— 已用 {@code tools/DeadCodeProbe.java} 在<b>真 GraalJS</b> 里实测确认：
 * <ol>
 *   <li><b>河豚膨胀状态</b>：原脚本 {@code mySetPuff} 第一行就
 *       {@code Java.type('org.bukkit.entity.PufferFish$PuffState')}，
 *       而本版本 Bukkit <b>没有这个类</b> ⇒ {@code Java.type} <b>抛异常</b>
 *       ⇒ 整个函数恒 {@code return false}。
 *       实测输出：{@code TypeError: Access to host class … is not allowed or does not exist.}
 *       <br>⇒ 所以「拿不到缩放属性就退回膨胀状态」这条<b>兜底从来没生效过</b>，
 *       原脚本在那种情况下其实<b>什么都不做</b>。Java 侧照此办理。</li>
 *   <li><b>清空战利品表</b>：原脚本 {@code myFreeze} 里
 *       {@code try { empty = LootTables.EMPTY; } catch (e) { empty = null; }}
 *       —— 本版本 {@code LootTables} 枚举<b>没有 {@code EMPTY} 常量</b>，
 *       实测 GraalJS 取到的是 {@code undefined}，而 {@code undefined != null} 为假
 *       ⇒ {@code ent.setLootTable(...)} <b>永远不执行</b>。
 *       <br>（而且 {@code Entity} 上<b>根本没有 {@code setLootTable} 方法</b> —— 双重死。）
 *       <br>⇒ 掉落物仍然只靠下面第 ② 条监听器兜底，与原脚本一致。</li>
 * </ol>
 *
 * <p>★ <b>没有主手判定</b>；防连点靠 8 秒冷却，冷却中<b>只提示剩余秒数</b>
 * （既不召唤也不发歌词，避免提示词连环刷屏）。
 */
public final class MaiyouWineModule extends AbstractModule {

    // ---------------- 依赖 ----------------
    /** PDC 标记键。★ 用 STRING 而不是 BYTE：字符串没有装箱歧义，最稳。 */
    private static final NamespacedKey MY_MARK = NamespacedKey.fromString("gs:maiyou_fish");

    // ---------------- 参数 ----------------
    static final int MY_DURATION = 160;      // 存活 160 刻 = 8 秒
    static final int MY_TURNS = 8;           // 8 秒内转 8 圈（每刻 18°）
    static final int MY_PULSE = 10;          // 大小脉动周期 10 刻 = 0.5 秒
    static final double MY_MIN = 0.60;       // 最小缩放（不变）
    static final double MY_MAX = 2.20;       // 最大缩放
    static final double MY_DIST = 1.6;       // 右键空气时：出现在玩家面前多少格
    static final String MY_NAME = "§d麦友的酒";
    static final long MY_COOLDOWN_MS = 8000L;   // 使用冷却 8 秒
    static final int MY_LYRIC_GAP = 20;

    /** 提示词：前 4 秒逐句发出（每 20 刻 = 1 秒一句）。 */
    static final String[] MY_LYRICS = {
            "&d昨日一去不复回~哦耶耶~",
            "&d开心比什么都贵~",
            "&d覆水不能再收回~哦耶耶~",
            "&d桃花谢了有玫瑰~"
    };

    /** 冷却（按玩家记，避免右键连点把提示词刷屏）。 */
    private final Guards.Cooldown lastUse = new Guards.Cooldown(MY_COOLDOWN_MS);

    /** 兜底监听器是否已注册（等价原脚本的 {@code MY_GUARD_OK}）。 */
    private static final Set<String> GUARD_REGISTERED = ConcurrentHashMap.newKeySet();

    private static final String GUARD_KEY = "gs_maiyou_wine";

    public MaiyouWineModule() {
        // 加载标记：后台看到这行，说明脚本本身没问题（与原脚本一致：静默失败）
        try {
            Bukkit.getLogger().info("[构思技艺] gs_maiyou_wine 已加载");
        } catch (Throwable ignored) {
            // 原脚本也是 try/catch 静默
        }

        // ★ 顶层代码里注册兜底监听器（原脚本同样在顶层注册）
        try {
            if (registerGuard()) {
                try {
                    Bukkit.getLogger().info(
                            "[构思技艺] 麦友的酒：伤害拦截 + 掉落清空 + 桶交互拦截 已启用");
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
        return "gs_maiyou_wine";
    }

    // ------------------------------------------------------------ 标记

    private static void mark(Entity ent) {
        try {
            if (MY_MARK == null) {
                return;
            }
            ent.getPersistentDataContainer().set(MY_MARK, PersistentDataType.STRING, "1");
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
    }

    private static boolean isMarked(Entity ent) {
        try {
            if (MY_MARK == null) {
                return false;
            }
            PersistentDataContainer pdc = ent.getPersistentDataContainer();
            try {
                if (pdc.has(MY_MARK, PersistentDataType.STRING)) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                // 兼容旧标记
                return pdc.has(MY_MARK, PersistentDataType.BYTE);
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 取出「右键的那个方块 + 被点的面」

    /** {@code myClickedTarget} 的返回值。 */
    private static final class Target {
        Block block;
        BlockFace face;
    }

    private static Target clickedTarget(PlayerRightClickEvent event) {
        Target out = new Target();
        try {
            // ① Slimefun 的 PlayerRightClickEvent 里包着 Bukkit 的 PlayerInteractEvent
            PlayerInteractEvent ie;
            try {
                ie = event.getInteractEvent();
            } catch (Throwable e) {
                ie = null;
            }
            if (ie != null) {
                try {
                    out.block = ie.getClickedBlock();
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    out.face = ie.getBlockFace();
                } catch (Throwable ignored) {
                    // 静默
                }
            }
            // ② 原脚本还会试 event.getClickedBlock() / getClickedFace()（Optional）。
            //   ★ Slimefun 的 PlayerRightClickEvent 上没有这两个方法，
            //     所以那两段在真机上永远取不到值 —— 同上，是死代码，不照抄。
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        if (out.block == null) {
            out.face = null;
        }
        return out;
    }

    // ------------------------------------------------------------ 召唤

    private static PufferFish spawn(Player p, Block block, BlockFace face) {
        try {
            Location loc = null;

            // ① 优先：被右键方块的「被点面」那一格（= 面向玩家的空气侧）
            if (block != null && face != null) {
                try {
                    Block t = block.getRelative(face);
                    if (t != null) {
                        Location b = t.getLocation();
                        loc = new Location(p.getWorld(),
                                b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                    }
                } catch (Throwable e) {
                    loc = null;
                }
            }

            // ② 右键空气（或拿不到方块）→ 出现在玩家面前
            if (loc == null) {
                Location base = p.getLocation();
                Vector d = base.getDirection();
                Vector v = new Vector(d.getX(), 0.0, d.getZ());
                if (v.lengthSquared() < 1.0E-6) {
                    v = new Vector(1.0, 0.0, 0.0);
                }
                v.normalize();
                loc = new Location(p.getWorld(),
                        base.getX() + v.getX() * MY_DIST,
                        base.getY() + 1.2,
                        base.getZ() + v.getZ() * MY_DIST);
            }

            try {
                loc.setYaw(p.getLocation().getYaw());
            } catch (Throwable ignored) {
                // 静默
            }
            try {
                loc.setPitch(0.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            Entity ent = p.getWorld().spawnEntity(loc, EntityType.PUFFERFISH);
            if (!(ent instanceof PufferFish fish)) {
                return null;
            }

            freeze(fish);
            mark(fish);
            return fish;
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 把河豚「定住」：不移动、不下坠、不被推动、不攻击、不乱叫。
     *
     * <p>★ 原脚本末尾那段「把战利品表换成空表」是<b>死代码</b>（见类注释），
     * 这里不照抄 —— 掉落物只靠 {@link Guard} 的第 ② 条兜底，与原脚本实际行为一致。
     */
    private static void freeze(PufferFish ent) {
        if (ent == null) {
            return;
        }
        try {
            ent.setAI(false);                 // 不移动、不攻击
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setGravity(false);            // 悬浮
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setInvulnerable(true);        // 打不死（也不受缺水伤害）
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setSilent(true);              // 不乱叫
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setCollidable(false);         // 不与任何实体碰撞
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setCanPickupItems(false);
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setRemoveWhenFarAway(false);  // 附近没人也不消失
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setPersistent(true);
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setFallDistance(0.0f);
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setCustomName(MY_NAME);       // 名字：麦友的酒
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setCustomNameVisible(true);
        } catch (Throwable ignored) {
            // 静默
        }
        try {
            ent.setVelocity(new Vector(0.0, 0.0, 0.0));
        } catch (Throwable ignored) {
            // 静默
        }
    }

    // ------------------------------------------------------------ 缩放

    /**
     * 用 SCALE 属性设置大小。
     *
     * <p>★ 取不到属性实例（该实体没有 SCALE）⇒ 返回 false。
     * 原脚本在这种情况下会去「退回河豚自身的膨胀状态」，但那条路是死代码
     * （见类注释）⇒ <b>实际什么都不做</b>。这里保持同样的结果。
     */
    private static boolean setScale(PufferFish ent, double s) {
        try {
            AttributeInstance inst = ent.getAttribute(Attribute.SCALE);
            if (inst == null) {
                return false;
            }
            inst.setBaseValue(s);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    // ------------------------------------------------------------ 每刻刷新

    /** 钉住位置 + 转圈 + 变大变小。 */
    private void pulse(PufferFish ent, Location anchor, int i) {
        if (ent == null) {
            return;
        }
        boolean dead;
        try {
            dead = ent.isDead();
        } catch (Throwable e) {
            dead = true;
        }
        if (dead) {
            return;
        }

        // ① 位置永远钉回出生点（写死坐标，只换朝向）→ 不可能移动、不可能掉下去
        try {
            Location loc = anchor.clone();
            double per = 360.0 * MY_TURNS / MY_DURATION;      // 18°
            double yaw = (anchor.getYaw() + per * (i + 1)) % 360.0;
            loc.setYaw((float) yaw);
            loc.setPitch(0.0f);
            ent.teleport(loc);
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }

        // ② 每 20 刻重新「定身」一次（防其它插件/原版把它解开）
        if (i % 20 == 0) {
            freeze(ent);
        }

        // ③ 变大变小：正弦脉动（MY_MIN ~ MY_MAX）
        double s;
        try {
            double mid = (MY_MIN + MY_MAX) / 2.0;
            double amp = (MY_MAX - MY_MIN) / 2.0;
            s = mid + amp * Math.sin(2.0 * Math.PI * i / MY_PULSE);
        } catch (Throwable e) {
            s = 1.0;
        }

        // ★ 原脚本这里是 if (setScale 成功) { 压回小状态 } else { 按阈值算 lv 再设膨胀状态 }，
        //   但两条分支最终都调用死代码 mySetPuff ⇒ 整体等价于「只尝试 setScale」。
        setScale(ent, s);

        if (i + 1 >= MY_DURATION) {
            // 到点：直接移除（remove，不走死亡流程 → 不会有任何死亡表现、也绝无掉落物）
            try {
                ent.remove();
            } catch (Throwable ignored) {
                // 静默
            }
            // 兜底：万一上一步没成，1 秒后再清一次
            GS.runLater(() -> {
                try {
                    if (!ent.isDead()) {
                        ent.remove();
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
            }, 20L);
            return;
        }
        final int next = i + 1;
        GS.runLater(() -> pulse(ent, anchor, next), 1L);
    }

    // ------------------------------------------------------------ 提示词

    private void lyrics(Player p, int idx) {
        if (idx >= MY_LYRICS.length) {
            return;
        }
        try {
            p.sendMessage(Chat.t(MY_LYRICS[idx]));
        } catch (Throwable ignored) {
            // 与原脚本一致：静默
        }
        final int next = idx + 1;
        GS.runLater(() -> lyrics(p, next), MY_LYRIC_GAP);
    }

    // ------------------------------------------------------------ 兜底监听器

    /**
     * 判断某物品是不是「麦友的酒」—— 用来拦住原版「河豚桶」的倒河豚行为。
     *
     * <p>★ 比 {@link Guards#isOurItem} 多一层材质判定
     * （必须是 {@code PUFFERFISH_BUCKET} 或 {@code PUFFERFISH}）。
     */
    private static boolean isOurItem(ItemStack stack) {
        try {
            if (stack == null) {
                return false;
            }
            String tn;
            try {
                tn = String.valueOf(stack.getType().name());
            } catch (Throwable e) {
                return false;
            }
            if (!"PUFFERFISH_BUCKET".equals(tn) && !"PUFFERFISH".equals(tn)) {
                return false;
            }

            // ① RSC 的判定（最准）
            try {
                SlimefunItem sf = SlimefunItem.getByItem(stack);
                if (sf != null && "GS_MAIYOU_WINE".equals(String.valueOf(sf.getId()))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            // ② Slimefun 标准 API 兜底
            try {
                SlimefunItem sf2 = SlimefunItem.getByItem(stack);
                if (sf2 != null && "GS_MAIYOU_WINE".equals(String.valueOf(sf2.getId()))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 静默
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 注册兜底监听器（已注册则直接返回 true）。
     *
     * <p>★ 原脚本注册在 RSC / Slimefun 插件名下；原生版注册在<b>本插件</b>名下 —— 等价物。
     * <p>★ 去重：原脚本用脚本级 {@code MY_GUARD_OK} 闩锁，reload 会重复注册；
     * 这里按脚本名静态去重（效果等价、不累积监听器）。
     */
    private static synchronized boolean registerGuard() {
        if (GUARD_REGISTERED.contains(GUARD_KEY)) {
            return true;
        }
        try {
            Bukkit.getPluginManager().registerEvents(new Guard(), GS.plugin());
            GUARD_REGISTERED.add(GUARD_KEY);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 三个兜底监听器，全部 HIGHEST 优先级。
     *
     * <p>★ 第 ③ 条<b>没有主手判定</b>（只拦 {@code RIGHT_CLICK}）—— 与原脚本一致，
     * 不要照抄 {@code gs_song} 的写法。
     */
    private static final class Guard implements Listener {

        /** ① 我们标记过的河豚造成的伤害 → 一律取消。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDamage(EntityDamageByEntityEvent e) {
            try {
                if (e.isCancelled()) {
                    return;
                }
                Entity d;
                try {
                    d = e.getDamager();
                } catch (Throwable e2) {
                    d = null;
                }
                if (d == null) {
                    return;
                }
                String tn;
                try {
                    tn = String.valueOf(d.getType().name());
                } catch (Throwable e2) {
                    return;
                }
                if (!"PUFFERFISH".equals(tn)) {
                    return;
                }
                if (!isMarked(d)) {
                    return;          // 只认我们自己召的那只
                }
                e.setCancelled(true);
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /** ② 我们标记过的河豚「死」的时候 → 清空掉落物与经验（一个都不掉）。 */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDeath(EntityDeathEvent e) {
            try {
                Entity ent;
                try {
                    ent = e.getEntity();
                } catch (Throwable e2) {
                    ent = null;
                }
                if (ent == null) {
                    return;
                }
                String tn;
                try {
                    tn = String.valueOf(ent.getType().name());
                } catch (Throwable e2) {
                    return;
                }
                if (!"PUFFERFISH".equals(tn)) {
                    return;
                }
                if (!isMarked(ent)) {
                    return;
                }
                try {
                    if (e.getDrops() != null) {
                        e.getDrops().clear();
                    }
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    e.setDroppedExp(0);
                } catch (Throwable ignored) {
                    // 静默
                }
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }

        /**
         * ③ 右键时拦住原版「河豚桶」的倒河豚行为（只走我们自己的召唤逻辑）。
         *
         * <p>★ 只拦 RIGHT_CLICK：左键要留给玩家正常挖方块
         * （手里拿桶 = 相当于空手挖）。
         */
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onInteract(PlayerInteractEvent e) {
            try {
                String act = "";
                try {
                    act = String.valueOf(e.getAction().name());
                } catch (Throwable e2) {
                    act = "";
                }
                if (!act.contains("RIGHT_CLICK")) {
                    return;
                }

                ItemStack h;
                try {
                    h = e.getItem();
                } catch (Throwable e2) {
                    h = null;
                }
                if (h == null) {
                    return;
                }
                if (!isOurItem(h)) {
                    return;
                }

                e.setCancelled(true);      // 原版桶放置河豚 → 取消
            } catch (Throwable ignored) {
                // 与原脚本一致：静默
            }
        }
    }

    // ------------------------------------------------------------ 入口

    @Override
    protected Object onUse(PlayerRightClickEvent event, Object reader) {
        try {
            // ★ 惰性重试：脚本加载时插件可能还没就绪，这里在使用时再补一次
            //   （已装好就是空操作）
            try {
                registerGuard();
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

            // ★ 冷却 8 秒：冷却中直接拦下（既不召唤、也不发提示词，避免刷屏）
            long cdLeft = lastUse.left(p);
            if (cdLeft > 0) {
                try {
                    long secs = (long) Math.ceil(cdLeft / 1000.0);
                    p.sendMessage(Chat.t("&7酒还没醒，&f" + secs + " &7秒后再来。"));
                } catch (Throwable ignored) {
                    // 静默
                }
                return null;
            }

            Target t = clickedTarget(event);
            PufferFish ent = spawn(p, t.block, t.face);
            if (ent == null) {
                try {
                    p.sendMessage(Chat.t("&7酒洒了，河豚没浮上来。"));
                } catch (Throwable ignored) {
                    // 静默
                }
                return null;
            }

            // 出生点 = 之后每一刻都要「钉」回去的锚点
            Location anchor;
            try {
                anchor = ent.getLocation().clone();
            } catch (Throwable e) {
                anchor = null;
            }
            if (anchor == null) {
                try {
                    ent.remove();
                } catch (Throwable ignored) {
                    // 静默
                }
                try {
                    p.sendMessage(Chat.t("&7酒洒了，河豚没浮上来。"));
                } catch (Throwable ignored) {
                    // 静默
                }
                return null;
            }

            // 到这里才算「真的用掉了」→ 开始计时冷却（召唤失败时不占冷却）
            lastUse.mark(p);

            try {
                p.getWorld().playSound(p.getLocation(),
                        "entity.puffer_fish.blow_up", 1.0f, 1.0f);
            } catch (Throwable ignored) {
                // 静默
            }

            // 提示词：前 4 秒逐句发完
            try {
                lyrics(p, 0);
            } catch (Throwable ignored) {
                // 静默
            }

            pulse(ent, anchor, 0);
        } catch (Throwable err) {
            // ★ 外层是「记一条 warning」，与原脚本逐字一致
            try {
                Bukkit.getLogger().warning("[构思技艺] 麦友的酒 onUse 出错: " + err);
            } catch (Throwable ignored) {
                // 同上
            }
        }
        return null;
    }
}
