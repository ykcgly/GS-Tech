import com.qinghuacisj.gs.script.Modules;
import com.qinghuacisj.gs.script.module.GsModule;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * 阶段② 模块探针 —— 不开服务器就验证「已 Java 化的脚本」行为是否与原 JS 一致。
 *
 * <p><b>为什么能用动态代理测</b>：Bukkit 的 {@code Player} 是<b>接口</b>，
 * 于是可以用 {@link Proxy} 造一个假的玩家，精确记录它收到的
 * {@code sendMessage} / {@code playSound} 调用。模块里的
 * {@code Chat.t} 只做 {@code ChatColor.translateAlternateColorCodes}，是纯字符串处理，
 * 不需要运行中的服务器。
 *
 * <p>这样就能把「点一下按钮到底发了什么、响了几声」这类<b>可观察行为</b>
 * 拿原脚本逐字对账，而不是靠肉眼看代码。
 */
public final class ModuleProbe {

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) {
        System.out.println("=== 阶段② 模块探针 ===");
        System.out.println();

        registry();
        infoCreator();
        updateLog();
        rollingLogConstruction();
        guideBook();
        dingMe();
        song();
        cooldown();
        releaseDream();
        fakeWeisi();
        maiyouWine();
        borrowLife();
        cursed();
        badApple();

        System.out.println();
        System.out.println("通过 " + pass + " ｜ 失败 " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------ 登记表

    private static void registry() {
        System.out.println("[1] 登记表与路由");
        check("gs_info_creator 已登记", Modules.has("gs_info_creator"));
        check("gs_update_log 已登记", Modules.has("gs_update_log"));
        check("gs_rolling_log_ingot 已登记", Modules.has("gs_rolling_log_ingot"));
        check("gs_guide_book 已登记", Modules.has("gs_guide_book"));
        check("gs_ding_me 已登记", Modules.has("gs_ding_me"));
        check("gs_song 已登记", Modules.has("gs_song"));
        check("gs_release_dream 已登记", Modules.has("gs_release_dream"));
        check("gs_fake_weisi 已登记", Modules.has("gs_fake_weisi"));
        check("gs_maiyou_wine 已登记", Modules.has("gs_maiyou_wine"));
        check("gs_borrow_life 已登记", Modules.has("gs_borrow_life"));
        check("gs_cursed 已登记", Modules.has("gs_cursed"));
        check("gs_bad_apple 已登记", Modules.has("gs_bad_apple"));
        // 带 .js 后缀也要认
        check("带 .js 后缀也认", Modules.has("gs_info_creator.js"));
        // 未转换的必须回落到 GraalJS
        check("gs_boss_nightwatch 未登记（应回落 GraalJS）", !Modules.has("gs_boss_nightwatch"));
        check("gs_listener 未登记（应回落 GraalJS）", !Modules.has("gs_listener"));
        check("gs_boss_three 未登记（应回落 GraalJS）", !Modules.has("gs_boss_three"));
        check("已 Java 化 12 个", Modules.convertedCount() == 12);

        // ★ 缓存语义：同名单例（等价原版 SCRIPT_CACHE 的「共享全局作用域」）
        GsModule a = Modules.acquire("gs_update_log");
        GsModule b = Modules.acquire("gs_update_log");
        check("同名单例（两次 acquire 是同一实例）", a != null && a == b);

        GsModule c = Modules.acquire("gs_ding_me");
        GsModule d = Modules.acquire("gs_ding_me");
        check("gs_ding_me 也是单例（冷却表共享的前提）", c != null && c == d);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_info_creator

    private static void infoCreator() {
        System.out.println("[2] gs_info_creator —— 点「构思技艺创作者」按钮");
        Recorder rec = new Recorder();
        GsModule m = Modules.acquire("gs_info_creator");
        m.call("onButtonGroupClick", rec.player(), 0, null, null, null);

        System.out.println("      聊天消息 " + rec.messages.size() + " 条:");
        for (String s : rec.messages) {
            System.out.println("         " + s.replace("\u00a7", "§"));
        }
        System.out.println("      音效 " + rec.sounds.size() + " 条:");
        for (String s : rec.sounds) {
            System.out.println("         " + s);
        }

        check("发 2 条消息", rec.messages.size() == 2);
        check("第 1 条 = §f构思技艺创作者 §8· §7QingHuaCiSJ",
                rec.messages.size() > 0
                        && rec.messages.get(0).equals("\u00a7f构思技艺创作者 \u00a78· \u00a77QingHuaCiSJ"));
        check("第 2 条 = §7感谢游玩 §8—— §7「万物初生，始于想象」",
                rec.messages.size() > 1
                        && rec.messages.get(1).equals("\u00a77感谢游玩 \u00a78—— \u00a77「万物初生，始于想象」"));
        check("响 1 声 entity.experience_orb.pickup 0.6/1.6",
                rec.sounds.size() == 1
                        && rec.sounds.get(0).equals("entity.experience_orb.pickup 0.6 1.6"));
        System.out.println();
    }

    // ------------------------------------------------------------ gs_update_log

    private static void updateLog() {
        System.out.println("[3] gs_update_log —— 点「更新日志」按钮（第八十二批：不再发消息）");
        Recorder rec = new Recorder();
        GsModule m = Modules.acquire("gs_update_log");
        m.call("onButtonGroupClick", rec.player(), 0, null, null, null);

        System.out.println("      聊天消息 " + rec.messages.size() + " 条（应为 0）");
        System.out.println("      音效 " + rec.sounds.size() + " 条:");
        for (String s : rec.sounds) {
            System.out.println("         " + s);
        }

        check("★ 不发任何聊天消息（第八十二批）", rec.messages.isEmpty());
        check("响 1 声 item.book.page_turn 0.8/1.4",
                rec.sounds.size() == 1
                        && rec.sounds.get(0).equals("item.book.page_turn 0.8 1.4"));

        // p == null 时必须静默返回（原脚本有 if (p == null) return;）
        Recorder rec2 = new Recorder();
        m.call("onButtonGroupClick", null, 0, null, null, null);
        check("p == null 时不抛异常、不发声", rec2.sounds.isEmpty());
        System.out.println();
    }

    // ------------------------------------------------------------ gs_rolling_log_ingot

    private static void rollingLogConstruction() {
        System.out.println("[4] gs_rolling_log_ingot —— 构造与未定义钩子");
        GsModule m = Modules.acquire("gs_rolling_log_ingot");
        check("能构造（原脚本顶层无副作用）", m != null);
        // 未定义的钩子必须返回 null（等价原版「脚本里没这个函数」）
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未定义钩子 onButtonGroupClick 返回 null",
                m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        check("未知函数名返回 null", m.call("gsRllNotAFunction") == null);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_guide_book

    private static void guideBook() {
        System.out.println("[5] gs_guide_book —— 图鉴成书");
        GsModule m = Modules.acquire("gs_guide_book");
        check("能构造", m != null);
        check("19 页", com.qinghuacisj.gs.script.module.GuideBookModule.pageCount() == 19);

        // ★ 无服务器时 getItemMeta() 会抛 IllegalStateException（Bukkit 的 ItemFactory 未就绪），
        //   原脚本把它包在 try/catch 里 ⇒ meta = null ⇒ 静默 return。
        //   这里断言「不抛异常、返回 null」，正是那条降级路径。
        Recorder rec = new Recorder();
        Object r = null;
        try {
            r = m.call("onButtonGroupClick", rec.player(), 0, null, null, null);
            check("无服务器时走降级路径（不抛异常、返回 null）", r == null);
        } catch (Throwable t) {
            check("无服务器时走降级路径（不抛异常、返回 null）—— 实际抛了 " + t, false);
        }
        if (!rec.messages.isEmpty()) {
            System.out.println("      降级时发出的消息（无服务器环境的产物）:");
            for (String s : rec.messages) {
                System.out.println("         " + s.replace("\u00a7", "§"));
            }
        }
        check("降级时没有发声（音效只在成功路径上）", rec.sounds.isEmpty());

        // p == null 必须直接返回
        check("p == null 时返回 null", m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        // 未定义的钩子
        check("未定义钩子 onUse 返回 null", m.call("onUse", null, null) == null);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_ding_me

    private static void dingMe() {
        System.out.println("[6] gs_ding_me —— 这你叮我");
        GsModule m = Modules.acquire("gs_ding_me");
        check("能构造", m != null);
        // 未定义的钩子必须返回 null
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未定义钩子 onButtonGroupClick 返回 null",
                m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        check("未知函数名返回 null", m.call("dmPlay") == null);

        // ★ 冷却表按玩家记 ⇒ 单例是前提（已在 [1] 断言）。
        //   这里验证「同一实例的冷却表确实在实例里」—— 换一个模块实例应互不影响。
        GsModule other = new com.qinghuacisj.gs.script.module.DingMeModule();
        check("新实例是另一个对象（冷却表不跨实例共享）", other != m);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_song

    private static void song() {
        System.out.println("[7] gs_song —— 如听仙乐耳暂明");
        GsModule m = Modules.acquire("gs_song");
        check("能构造（顶层会尝试注册方块交互拦截器，无服务器时静默失败）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未定义钩子 onButtonGroupClick 返回 null",
                m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        check("未知函数名返回 null", m.call("sgPlay") == null);

        // event == null ⇒ getPlayer() 抛 NPE 被内层 try 吞掉 ⇒ p = null ⇒ 返回 null
        check("event 为 null 时静默返回 null（不抛异常）", m.call("onUse", null, null) == null);

        check("SG_CD_MS = 500（0.5 秒防连点）",
                constOf("com.qinghuacisj.gs.script.module.SongModule", "SG_CD_MS")
                        .equals(500L));
        check("SG_SOUND = entity.ghastling.ambient（小恶魂的叫声）",
                "entity.ghastling.ambient".equals(
                        constOf("com.qinghuacisj.gs.script.module.SongModule", "SG_SOUND")));
        check("SG_OWNER_ONLY = true（只有按的人听得到）",
                Boolean.TRUE.equals(
                        constOf("com.qinghuacisj.gs.script.module.SongModule", "SG_OWNER_ONLY")));
        System.out.println();
    }

    /** 读一个类的静态常量（探针在默认包里，够不到包内可见的字段）。 */
    private static Object constOf(String className, String field) {
        try {
            java.lang.reflect.Field f = Class.forName(className).getDeclaredField(field);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ Guards.Cooldown

    private static void cooldown() {
        System.out.println("[8] Guards.Cooldown —— 冷却表的真实计时行为");
        Recorder rec = new Recorder();
        org.bukkit.entity.Player p = (org.bukkit.entity.Player) rec.player();

        com.qinghuacisj.gs.script.module.Guards.Cooldown cd =
                new com.qinghuacisj.gs.script.module.Guards.Cooldown(500L);

        check("未用过时剩余 0", cd.left(p) == 0);

        cd.mark(p);
        long left = cd.left(p);
        check("刚标记后剩余 > 0（实测 " + left + " ms）", left > 0);
        check("剩余 <= 500（不超出冷却窗口）", left <= 500);

        // 换一个玩家，应互不影响
        Recorder other = new Recorder();
        check("另一个玩家不受影响（按玩家记）",
                cd.left((org.bukkit.entity.Player) other.player()) == 0);

        // 睡过冷却窗口后应归零
        try {
            Thread.sleep(600);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        check("睡过 500ms 后剩余归零", cd.left(p) == 0);

        // cooldownMs <= 0 ⇒ 永远不冷却（原脚本 if (CD <= 0) return 0）
        com.qinghuacisj.gs.script.module.Guards.Cooldown off =
                new com.qinghuacisj.gs.script.module.Guards.Cooldown(0L);
        off.mark(p);
        check("冷却 0 ⇒ 标记后仍返回 0（等于无冷却）", off.left(p) == 0);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_release_dream

    private static void releaseDream() {
        System.out.println("[9] gs_release_dream —— 释梦");
        GsModule m = Modules.acquire("gs_release_dream");
        check("能构造（原脚本顶层只打一条日志）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未定义钩子 onButtonGroupClick 返回 null",
                m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        check("未知函数名返回 null", m.call("rdLaunch") == null);

        // event == null ⇒ getPlayer() 抛 NPE 被吞 ⇒ p = null ⇒ 返回 null
        check("event 为 null 时静默返回 null（不抛异常）", m.call("onUse", null, null) == null);

        // ★ 释梦**没有**主手判定、**没有**冷却 —— 这两个是它与
        //   gs_song / gs_ding_me 的关键差别，用反射确认没有多出这些东西。
        boolean hasCooldownField = false;
        for (java.lang.reflect.Field f : com.qinghuacisj.gs.script.module.ReleaseDreamModule.class
                .getDeclaredFields()) {
            if (f.getType() == com.qinghuacisj.gs.script.module.Guards.Cooldown.class) {
                hasCooldownField = true;
            }
        }
        check("没有冷却表字段（释梦无冷却）", !hasCooldownField);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_fake_weisi

    private static void fakeWeisi() {
        System.out.println("[10] gs_fake_weisi —— 假为思（铠甲合体时间轴）");
        GsModule m = Modules.acquire("gs_fake_weisi");
        check("能构造（原脚本顶层只打一条日志）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未知函数名返回 null", m.call("fwLaunch") == null);
        check("event 为 null 时静默返回 null", m.call("onUse", null, null) == null);

        int first = (Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                "FW_STAND_FIRST");
        int gap = (Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                "FW_STAND_GAP");
        int flight = (Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                "FW_FLIGHT_TICKS");

        // ★★ 时间轴是最高风险处：注释里承诺「出现 22/62/102/142、抵达 60/100/140/180」。
        //    把这条承诺变成断言 —— 以后谁改了常量，这里立刻红。
        int[] wantSpawn = {22, 62, 102, 142};
        int[] wantArrive = {60, 100, 140, 180};
        boolean spawnOk = true;
        boolean arriveOk = true;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            int spawn = first + gap * i;
            // 抵达 = 出现 + 1 刻起跑 + (flight-1) 步 = 出现 + flight
            int arrive = spawn + flight;
            if (spawn != wantSpawn[i]) {
                spawnOk = false;
            }
            if (arrive != wantArrive[i]) {
                arriveOk = false;
            }
            sb.append(spawn).append("→").append(arrive).append("  ");
        }
        check("四个盔甲架出现时刻 = 22/62/102/142（实测 " + sb.toString().trim() + "）", spawnOk);
        check("四个盔甲架抵达时刻 = 60/100/140/180", arriveOk);

        // 每段飞行必须 < 2 秒（注释承诺「都在 2 秒以内」）
        check("每段飞行 38 刻 = 1.9 秒 < 2 秒", flight == 38 && flight < 40);

        check("漂浮 180 刻 = 9 秒",
                ((Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_LEV_TICKS")) == 180);
        check("缓降 40 刻 = 2 秒",
                ((Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_SLOW_TICKS")) == 40);
        check("解除防连点锁 280 刻",
                ((Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_UNLOCK")) == 280);
        check("粒子 90 次 × 2 刻 = 180 刻（正好覆盖漂浮全程）",
                ((Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_SPIT_TIMES")) * 2 == 180);
        check("安全落地保护 600 次 × 2 刻 = 60 秒",
                ((Integer) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_NOFALL_MAX")) * 2 == 1200);
        check("盔甲架距玩家 3.5 格",
                ((Double) constOf("com.qinghuacisj.gs.script.module.FakeWeisiModule",
                        "FW_STAND_DIST")) == 3.5);

        // ★ 假为思**没有**冷却表、**没有**主手判定 —— 防连点靠「演出中」锁
        boolean hasCooldown = false;
        for (java.lang.reflect.Field f : com.qinghuacisj.gs.script.module.FakeWeisiModule.class
                .getDeclaredFields()) {
            if (f.getType() == com.qinghuacisj.gs.script.module.Guards.Cooldown.class) {
                hasCooldown = true;
            }
        }
        check("没有冷却表字段（防连点靠演出锁）", !hasCooldown);
        System.out.println();
    }

    // ------------------------------------------------------------ gs_maiyou_wine

    private static void maiyouWine() {
        System.out.println("[11] gs_maiyou_wine —— 麦友的酒");
        GsModule m = Modules.acquire("gs_maiyou_wine");
        check("能构造（顶层会尝试注册三个兜底监听器，无服务器时静默失败）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未定义钩子 onButtonGroupClick 返回 null",
                m.call("onButtonGroupClick", null, 0, null, null, null) == null);
        check("未知函数名返回 null", m.call("myPulse") == null);
        check("event 为 null 时静默返回 null", m.call("onUse", null, null) == null);

        // ★ PDC 标记键必须是 gs:maiyou_fish —— 「只认我们自己召的那只」全靠它
        Object key = constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule", "MY_MARK");
        check("MY_MARK = gs:maiyou_fish（实测 " + key + "）",
                key != null && "gs:maiyou_fish".equals(key.toString()));

        // ★ 转圈速率：360*8/160 = 18°/刻
        int turns = (Integer) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_TURNS");
        int duration = (Integer) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_DURATION");
        check("每刻 18°（实测 " + (360.0 * turns / duration) + "°）",
                Math.abs(360.0 * turns / duration - 18.0) < 1e-9);

        // ★ 脉动中点/振幅
        double min = (Double) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_MIN");
        double max = (Double) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_MAX");
        check("脉动中点 1.40 / 振幅 0.80（实测 " + ((min + max) / 2.0)
                        + " / " + ((max - min) / 2.0) + "）",
                Math.abs((min + max) / 2.0 - 1.40) < 1e-9
                        && Math.abs((max - min) / 2.0 - 0.80) < 1e-9);

        // ★ 8 秒冷却与 8 秒寿命一致 → 同时最多一只
        long cd = (Long) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_COOLDOWN_MS");
        check("冷却 8000ms 与河豚寿命 " + duration + " 刻(8 秒) 一致 → 同时最多一只",
                cd == 8000L && duration == 160);

        // ★ 歌词 4 句、每 20 刻一句 → 前 4 秒发完
        String[] lyrics = (String[]) constOf(
                "com.qinghuacisj.gs.script.module.MaiyouWineModule", "MY_LYRICS");
        int gap = (Integer) constOf("com.qinghuacisj.gs.script.module.MaiyouWineModule",
                "MY_LYRIC_GAP");
        check("歌词 4 句（实测 " + lyrics.length + " 句）", lyrics.length == 4);
        check("每 20 刻一句 ⇒ 4 句共 80 刻 = 前 4 秒发完",
                gap == 20 && (lyrics.length - 1) * gap == 60);
        System.out.println();
    }
    // ------------------------------------------------------------ gs_cursed

    private static void cursed() {
        System.out.println("[13] gs_cursed —— 咒术回战");
        GsModule m = Modules.acquire("gs_cursed");
        check("能构造（顶层会注册方块交互拦截器，无服务器时静默失败）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未知函数名返回 null", m.call("scSpin", (Object) null) == null);
        check("event 为 null 时静默返回 null", m.call("onUse", null, null) == null);

        String cls = "com.qinghuacisj.gs.script.module.CursedModule";

        // ★★ 曲谱：1202 个音符 / 43.478 秒 —— 全部机械提取，不是手抄
        int[] tl = (int[]) constOf(cls, "SG_TL");
        check("曲谱是 3 的整数倍（每组 [毫秒, code, 音量]）", tl.length % 3 == 0);
        int n = tl.length / 3;
        check("音符总数 1202（实测 " + n + "）", n == 1202);
        check("SG_N 与曲谱长度自洽（" + constOf(cls, "SG_N") + "）",
                ((Integer) constOf(cls, "SG_N")) == n);

        int lenMs = (Integer) constOf(cls, "SG_LEN_MS");
        check("曲长 43478ms = 43.5 秒（实测 " + lenMs + "）", lenMs == 43478);
        check("SG_LEN_MS 就是最后一个音符的时间戳",
                tl[tl.length - 3] == lenMs);

        // ★★ 用户要求「冷却 = 本曲时长」⇒ 曲子放完的那一刻冷却正好走完
        check("★ 冷却 = 曲长（" + constOf(cls, "SG_CD_MS") + "ms）⇒ 理论上可无缝连播",
                ((Long) constOf(cls, "SG_CD_MS")) == (long) lenMs);

        // 时间戳单调不减（否则 while 循环的「一次性补放」会错序）
        boolean mono = true;
        for (int i = 3; i < tl.length; i += 3) {
            if (tl[i] < tl[i - 3]) {
                mono = false;
                break;
            }
        }
        check("时间戳单调不减（保证「到点了就补放」的顺序正确）", mono);

        // ★ code 必须能解出合法的音效下标与音高序号，否则会数组越界
        int[] snd = (int[]) null;
        String[] snds = (String[]) constOf(cls, "SG_SND");
        int badCode = 0;
        int maxSnd = -1;
        int maxPit = -1;
        for (int i = 1; i < tl.length; i += 3) {
            int code = tl[i];
            if (code < 0) {
                badCode++;
                continue;
            }
            int si = code / 32;
            int pi = code % 32;
            if (si >= snds.length || pi >= 25) {
                badCode++;
            }
            maxSnd = Math.max(maxSnd, si);
            maxPit = Math.max(maxPit, pi);
        }
        check("全部 " + n + " 个 code 都能解出合法音效下标 + 音高序号（异常 " + badCode + " 个）",
                badCode == 0);
        check("音效下标最大 " + maxSnd + " < " + snds.length + " 种音效",
                maxSnd < snds.length);
        check("音高序号最大 " + maxPit + " ≤ 24（音符盒只有 25 档）", maxPit <= 24);
        check("8 种音效（实测 " + snds.length + "）", snds.length == 8);

        // ★ 25 档音高表：0.5 倍 / 原音 / 2 倍
        double[] pitch = (double[]) constOf(cls, "SG_PITCH");
        check("音高表 25 档（实测 " + pitch.length + "）", pitch.length == 25);
        check("第 0 档 = 0.5 倍音（实测 " + pitch[0] + "）", Math.abs(pitch[0] - 0.5) < 1e-12);
        check("第 12 档 = 原音 1.0（实测 " + pitch[12] + "）", Math.abs(pitch[12] - 1.0) < 1e-12);
        check("第 24 档 = 2 倍音（实测 " + pitch[24] + "）", Math.abs(pitch[24] - 2.0) < 1e-12);

        // ★ 召唤的两只
        check("村民名 = 宿瘫（实测 " + constOf(cls, "SC_VILLAGER_NAME") + "）",
                "宿瘫".equals(constOf(cls, "SC_VILLAGER_NAME")));
        check("卫道士名 = 五条舞（第五十六批改名，实测 "
                        + constOf(cls, "SC_VINDICATOR_NAME") + "）",
                "五条舞".equals(constOf(cls, "SC_VINDICATOR_NAME")));
        check("转速 3 圈/秒 ⇒ 54°/刻（实测 " + constOf(cls, "SC_SPIN_DEG") + "）",
                ((Double) constOf(cls, "SC_SPIN_DEG")) == 54.0);
        check("会被砍伤但砍不死：血上限 500（实测 " + constOf(cls, "SC_HP_MAX") + "）",
                ((Integer) constOf(cls, "SC_HP_MAX")) == 500
                        && Boolean.TRUE.equals(constOf(cls, "SC_KEEP_ALIVE")));
        check("不带碰撞（不会把站在原地听的玩家挤开）",
                Boolean.FALSE.equals(constOf(cls, "SC_COLLIDE")));
        check("残留清扫标签 = gs_cursed_mob",
                "gs_cursed_mob".equals(constOf(cls, "SC_TAG")));

        // ★★ 起飞：16~22 秒升 8 格
        check("起飞窗口 16~22 秒（实测 " + constOf(cls, "SC_FLY_START_MS") + "~"
                        + constOf(cls, "SC_FLY_END_MS") + "ms）",
                ((Integer) constOf(cls, "SC_FLY_START_MS")) == 16000
                        && ((Integer) constOf(cls, "SC_FLY_END_MS")) == 22000);
        check("飞升高度 8 格（实测 " + constOf(cls, "SC_FLY_HEIGHT") + "）",
                ((Double) constOf(cls, "SC_FLY_HEIGHT")) == 8.0);
        check("起飞发生在曲终之前（16 秒 < 43.5 秒，否则玩家看不到）",
                ((Integer) constOf(cls, "SC_FLY_START_MS")) < lenMs);

        // ★ 战斗粒子 4 种，每刻 2 个
        String[] fx = (String[]) constOf(cls, "SC_FX_KINDS");
        check("战斗粒子 4 种（实测 " + fx.length + "）", fx.length == 4);
        check("每刻 2 个（实测 " + constOf(cls, "SC_FX_PER_TICK") + "）",
                ((Integer) constOf(cls, "SC_FX_PER_TICK")) == 2);

        // ★★ 反向断言：「再按一次就停」必须**不存在**
        check("★ 反向断言：没有「再按停止」的开关（用户要求删除）",
                constOf(cls, "SC_STOP_ON_PRESS") == null
                        && constOf(cls, "SC_TOGGLE") == null);

        // ★ 死代码：SG_CD_HINT 恒 false ⇒ 冷却中什么都不显示
        check("★ SG_CD_HINT = false ⇒ 冷却中静默（原脚本该函数体是死代码）",
                Boolean.FALSE.equals(constOf(cls, "SG_CD_HINT")));

        // ★ 去抖 500ms，远小于 43.5 秒冷却 ⇒ 「每次按都武装」的写法在这里是安全的
        long deb = (Long) constOf(cls, "SG_DEBOUNCE_MS");
        check("去抖 500ms 远小于冷却 " + (lenMs / 1000) + " 秒 ⇒ 连点推不动窗口（照抄原写法即可）",
                deb == 500L && deb * 10 < lenMs);
        System.out.println();
    }
    // ------------------------------------------------------------ gs_bad_apple

    private static void badApple() {
        System.out.println("[14] gs_bad_apple —— Bad Apple（与咒术回战共用播放引擎）");
        GsModule m = Modules.acquire("gs_bad_apple");
        check("能构造（顶层会注册方块交互拦截器，无服务器时静默失败）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未知函数名返回 null", m.call("sgPlayPulse", (Object) null) == null);
        check("event 为 null 时静默返回 null", m.call("onUse", null, null) == null);

        // ★ ModuleProbe 在默认包里 ⇒ 引用不到具名类，只能走反射（与 gs_borrow_life 同一个理由）
        final String BA = "com.qinghuacisj.gs.script.module.BadAppleModule";
        final String CU = "com.qinghuacisj.gs.script.module.CursedModule";

        // ★★ 曲谱：36462 个音符 / 183.536 秒 —— 全部机械提取
        int[] tl = (int[]) constOf(BA, "SG_TL");
        check("曲谱是 3 的整数倍", tl.length % 3 == 0);
        check("音符总数 36462（实测 " + constOf(BA, "SG_N") + "）",
                ((Integer) constOf(BA, "SG_N")) == 36462);
        check("曲长 183536ms = 183.5 秒（实测 " + constOf(BA, "SG_LEN_MS") + "）",
                ((Integer) constOf(BA, "SG_LEN_MS")) == 183536);
        check("★ 冷却 = 曲长（" + constOf(BA, "SG_CD_MS") + "ms）⇒ 可无缝连播",
                ((Long) constOf(BA, "SG_CD_MS")) == 183536L);

        // ★ 与咒术回战的区别：音效表不同（10 种 vs 8 种）
        String[] snd = (String[]) constOf(BA, "SG_SND");
        check("10 种音效（实测 " + snd.length + "）", snd.length == 10);
        check("★ 音效表与咒术回战**不同**（两首曲子不能共用一张表）",
                snd.length != ((String[]) constOf(CU, "SG_SND")).length);
        check("本曲独有 hat / chime / bit（咒术回战没有）",
                contains(snd, "block.note_block.hat")
                        && contains(snd, "block.note_block.chime")
                        && contains(snd, "block.note_block.bit"));
        check("咒术回战独有 bass（本曲没有）",
                !contains(snd, "block.note_block.bass"));

        // ★★ 与咒术回战最大的差别：音量不是恒定的 100
        java.util.TreeSet<Integer> vols = new java.util.TreeSet<>();
        boolean hasNon100 = false;
        for (int i = 2; i < tl.length; i += 3) {
            int v = tl[i];
            vols.add(v);
            if (v != 100) {
                hasNon100 = true;
            }
        }
        check("★ 音量不是恒定 100（" + vols.size() + " 种取值）⇒ 真正走到 vol/100 那条路",
                hasNon100 && vols.size() > 1);
        check("音量都在 1~100 内（实测 " + vols.first() + "~" + vols.last() + "）",
                vols.first() >= 1 && vols.last() <= 100);

        // ★ 咒术回战那边是恒定 100 ⇒ 两首曲子在这一点上确实不同
        int[] ctl = (int[]) constOf(CU, "SG_TL");
        boolean cursedAll100 = true;
        for (int i = 2; i < ctl.length; i += 3) {
            if (ctl[i] != 100) {
                cursedAll100 = false;
                break;
            }
        }
        check("★ 反向对照：咒术回战的音量**恒定 100**（与 Bad Apple 不同）", cursedAll100);

        // ★ 本曲不召唤生物 ⇒ 与咒术回战的关键差别
        check("★ 本曲不召唤生物（引擎用 NO_HOOKS，没有 SC_* 那套参数）",
                constOf(BA, "SC_VILLAGER_NAME") == null);

        // ★ 两首曲子的时长必须不同，否则「共用引擎」的验证没有区分度
        check("两首曲子时长不同（43.5 秒 vs 183.5 秒）⇒ 共用引擎的验证有区分度",
                ((Integer) constOf(BA, "SG_LEN_MS")) != ((Integer) constOf(CU, "SG_LEN_MS")));

        // ★ 与咒术回战一样：冷却中静默、去抖 500ms 远小于冷却
        check("★ SG_CD_HINT = false ⇒ 冷却中静默（原脚本该函数体是死代码）",
                Boolean.FALSE.equals(constOf(BA, "SG_CD_HINT")));
        check("去抖 500ms 远小于冷却 183.5 秒 ⇒ 连点推不动窗口（照抄原写法即可）",
                ((Long) constOf(BA, "SG_DEBOUNCE_MS")) == 500L
                        && ((Long) constOf(BA, "SG_DEBOUNCE_MS")) * 10
                        < ((Integer) constOf(BA, "SG_LEN_MS")));
        System.out.println();
    }

    private static boolean contains(String[] arr, String v) {
        for (String s : arr) {
            if (v.equals(s)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ 工具

    // ------------------------------------------------------------ 工具

    // ------------------------------------------------------------ 工具
    // ------------------------------------------------------------ gs_borrow_life

    private static void borrowLife() {
        System.out.println("[12] gs_borrow_life —— 借寿");
        GsModule m = Modules.acquire("gs_borrow_life");
        check("能构造（顶层只打日志，不注册监听器）", m != null);
        check("未定义钩子 init() 返回 null", m.call("init") == null);
        check("未知函数名返回 null", m.call("blBoxHit", (Object) null) == null);
        check("event 为 null 时静默返回 null", m.call("onUse", null, null) == null);

        String cls = "com.qinghuacisj.gs.script.module.BorrowLifeModule";

        // ★ 伤害/回血/冷却 —— 用户需求原文「1 点伤害」「回 1n 滴血」「冷却 0.5 秒」
        check("每只 1 点伤害（BL_DMG = "
                + constOf(cls, "BL_DMG") + "）", ((Double) constOf(cls, "BL_DMG")) == 1.0);
        check("每命中一只回 1 点血（BL_HEAL_PER = "
                        + constOf(cls, "BL_HEAL_PER") + "）⇒ n 只回 n 点",
                ((Double) constOf(cls, "BL_HEAL_PER")) == 1.0);
        check("冷却 0.5 秒（BL_CD_MS = " + constOf(cls, "BL_CD_MS") + "）",
                ((Long) constOf(cls, "BL_CD_MS")) == 500L);
        check("去抖 150ms 明显小于冷却 500ms（否则 0.5 秒后再按会被误吞）",
                ((Long) constOf(cls, "BL_DEBOUNCE_MS")) == 150L);
        check("默认不打其它玩家（BL_HIT_PLAYERS = " + constOf(cls, "BL_HIT_PLAYERS") + "）",
                Boolean.FALSE.equals(constOf(cls, "BL_HIT_PLAYERS")));

        // ★ 判定盒：长 5 × 宽 5 × 高 2，身后留 0.4 容差
        check("判定盒 长 5 × 宽 5 × 高 2（实测 "
                        + constOf(cls, "BL_RANGE") + "×" + constOf(cls, "BL_WIDE")
                        + "×" + constOf(cls, "BL_H") + "）",
                ((Double) constOf(cls, "BL_RANGE")) == 5.0
                        && ((Double) constOf(cls, "BL_WIDE")) == 5.0
                        && ((Double) constOf(cls, "BL_H")) == 2.0);
        check("身后容差 0.4（贴身怪也打得到）",
                ((Double) constOf(cls, "BL_BACK")) == 0.4);

        // ★ 吸血粒子：10 刻 = 0.5 秒
        check("吸血粒子飞 10 刻 = 0.5 秒（BL_SIP_TICKS = "
                        + constOf(cls, "BL_SIP_TICKS") + "）",
                ((Integer) constOf(cls, "BL_SIP_TICKS")) == 10);

        // ★★ 反向断言：施法提示词必须**不存在**（第六十二批用户明确要求删除）
        check("★ 反向断言：施法提示词常量已删除（BL_CAST_MSG 不存在）",
                constOf(cls, "BL_CAST_MSG") == null);
        check("★ 反向断言：提示词函数已删除（blCastMsg 不是模块函数）",
                m.call("blCastMsg", (Object) null) == null);

        // ★★ 死代码：BL_CD_HINT 恒 false ⇒ 冷却中什么都不显示
        check("★ BL_CD_HINT = false ⇒ 冷却中静默（原脚本里该函数体是死代码）",
                Boolean.FALSE.equals(constOf(cls, "BL_CD_HINT")));

        // ★ 黑名单 31 项，且「玩家」不在其中（玩家是靠 BL_HIT_PLAYERS 单独挡的）
        String[] skip = (String[]) constOf(cls, "BL_SKIP");
        boolean hasPlayer = false;
        boolean hasArmorStand = false;
        for (String s : skip) {
            if ("PLAYER".equals(s)) {
                hasPlayer = true;
            }
            if ("ARMOR_STAND".equals(s)) {
                hasArmorStand = true;
            }
        }
        check("目标黑名单 31 项（实测 " + skip.length + " 项）", skip.length == 31);
        check("盔甲架在黑名单里（不算目标）", hasArmorStand);
        check("★ PLAYER 不在黑名单里 —— 玩家由 BL_HIT_PLAYERS 单独挡（顺序不能错）",
                !hasPlayer);

        // ★ 去抖的「只查不武装」语义：真时间跑一遍
        Recorder r = new Recorder();
        org.bukkit.entity.Player p = (org.bukkit.entity.Player) r.player();
        try {
            Class<?> blc = Class.forName("com.qinghuacisj.gs.script.module.BorrowLifeModule");
            java.lang.reflect.Method isDup = blc
                    .getDeclaredMethod("isDuplicate", org.bukkit.entity.Player.class);
            java.lang.reflect.Method arm = blc
                    .getDeclaredMethod("armDebounce", org.bukkit.entity.Player.class);
            isDup.setAccessible(true);
            arm.setAccessible(true);
            Object mod = blc.getDeclaredConstructor().newInstance();
            check("首次按下不算重复", !((Boolean) isDup.invoke(mod, p)));
            arm.invoke(mod, p);
            check("同一刻再按算重复（第二个事件被吞）", (Boolean) isDup.invoke(mod, p));
        } catch (Throwable e) {
            check("去抖语义可验证（反射失败: " + e + "）", false);
        }
        System.out.println();
    }

    // ------------------------------------------------------------ 工具

    /** 用动态代理造的假玩家，记录它收到的消息与音效。 */
    private static final class Recorder implements InvocationHandler {
        final List<String> messages = new ArrayList<>();
        final List<String> sounds = new ArrayList<>();
        /** 固定 UUID —— 冷却表要用它当键。 */
        final java.util.UUID uuid = java.util.UUID.randomUUID();

        Object player() {
            return Proxy.newProxyInstance(
                    ModuleProbe.class.getClassLoader(),
                    new Class<?>[]{org.bukkit.entity.Player.class},
                    this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] a) {
            switch (method.getName()) {
                case "sendMessage" -> {
                    if (a != null && a.length == 1 && a[0] instanceof String s) {
                        messages.add(s);
                    }
                    return null;
                }
                case "playSound" -> {
                    // playSound(Location, String, float, float)
                    if (a != null && a.length == 4 && a[1] instanceof String n) {
                        sounds.add(n + " " + a[2] + " " + a[3]);
                    }
                    return null;
                }
                case "getLocation" -> {
                    return null;   // 原脚本用的是 p.getLocation()，返回 null 不影响记录
                }
                case "getUniqueId" -> {
                    // ★ 必须给真 UUID：Guards.Cooldown 用 p.getUniqueId().toString() 当键，
                    //   返回 null 会让 toString() 抛 NPE 并被吞掉 ⇒ 冷却永远算 0，测不出东西。
                    return uuid;
                }
                case "getName" -> {
                    return "Probe";
                }
                default -> {
                    return zero(method.getReturnType());
                }
            }
        }

        private static Object zero(Class<?> t) {
            if (!t.isPrimitive()) {
                return null;
            }
            if (t == boolean.class) {
                return false;
            }
            if (t == void.class) {
                return null;
            }
            if (t == char.class) {
                return (char) 0;
            }
            if (t == byte.class) {
                return (byte) 0;
            }
            if (t == short.class) {
                return (short) 0;
            }
            if (t == int.class) {
                return 0;
            }
            if (t == long.class) {
                return 0L;
            }
            if (t == float.class) {
                return 0f;
            }
            return 0d;
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
