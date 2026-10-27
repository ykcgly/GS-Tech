package booktest;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一次性验证插件：确认 {@code BookMeta} 那套<b>已废弃的 String API</b>
 * 在 Paper 1.21.11 上仍然真的可用。
 *
 * <p><b>为什么必须上真服测</b>：{@code gs_guide_book} 的核心就是当场造一本成书，
 * 而它用的是 {@code setTitle(String)} / {@code setAuthor(String)} /
 * {@code setPages(List<String>)} —— 这三个在 1.20.5 之后都被
 * Component 版取代了。javap 只能证明<b>方法签名还在</b>，
 * 不能证明<b>调用之后真的写进去了</b>（可能是静默 no-op）。
 *
 * <p>所以这里做的是<b>往返测试</b>：写进去 → 读回来 → 逐字比对。
 * 任何一环失效（抛异常或静默丢弃），本插件都会在控制台打 FAIL。
 *
 * <p>★ 这不是交付物，只是一次性验证工具；跑完即可删。
 */
public final class BookTest extends JavaPlugin {

    @Override
    public void onEnable() {
        getLogger().info("========== BookMeta 往返测试开始 ==========");
        int fail = 0;

        // ① 能不能造出 WRITTEN_BOOK
        ItemStack st;
        try {
            st = new ItemStack(Material.WRITTEN_BOOK, 1);
            getLogger().info("PASS 1  new ItemStack(WRITTEN_BOOK, 1) 成功");
        } catch (Throwable t) {
            getLogger().severe("FAIL 1  new ItemStack(WRITTEN_BOOK, 1) 抛了: " + t);
            getLogger().info("========== 测试中止 ==========");
            return;
        }

        // ② getItemMeta 能不能拿到 BookMeta
        BookMeta meta;
        try {
            meta = (BookMeta) st.getItemMeta();
            if (meta == null) {
                getLogger().severe("FAIL 2  getItemMeta() 返回 null");
                getLogger().info("========== 测试中止 ==========");
                return;
            }
            getLogger().info("PASS 2  getItemMeta() 拿到 " + meta.getClass().getName());
        } catch (Throwable t) {
            getLogger().severe("FAIL 2  getItemMeta() 抛了: " + t);
            getLogger().info("========== 测试中止 ==========");
            return;
        }

        // ③ setTitle(String)
        try {
            boolean ok = meta.setTitle("构思技艺图鉴");
            getLogger().info("PASS 3  setTitle(String) 返回 " + ok);
            if (!ok) {
                fail++;
                getLogger().severe("FAIL 3  setTitle(String) 返回 false（写入失败）");
            }
        } catch (Throwable t) {
            fail++;
            getLogger().severe("FAIL 3  setTitle(String) 抛了: " + t);
        }

        // ④ setAuthor(String)
        try {
            meta.setAuthor("QingHuaCiSJ");
            getLogger().info("PASS 4  setAuthor(String) 未抛异常");
        } catch (Throwable t) {
            fail++;
            getLogger().severe("FAIL 4  setAuthor(String) 抛了: " + t);
        }

        // ⑤ setPages(List<String>) —— 最关键的未知
        List<String> pages = new ArrayList<>(Arrays.asList(
                "第一页\n第二行",
                "&8䷍Ꝑ8kꙭ#䷎\n生僻符号页",
                "第三页"));
        try {
            meta.setPages(pages);
            getLogger().info("PASS 5  setPages(List<String>) 未抛异常");
        } catch (Throwable t) {
            fail++;
            getLogger().severe("FAIL 5  setPages(List<String>) 抛了: " + t);
        }

        // ⑥ setItemMeta 回写
        try {
            st.setItemMeta(meta);
            getLogger().info("PASS 6  setItemMeta(meta) 未抛异常");
        } catch (Throwable t) {
            fail++;
            getLogger().severe("FAIL 6  setItemMeta(meta) 抛了: " + t);
        }

        // ⑦ 往返：从物品上重新读回 BookMeta，逐字比对
        try {
            BookMeta back = (BookMeta) st.getItemMeta();
            String title = back.getTitle();
            String author = back.getAuthor();
            List<String> got = back.getPages();

            getLogger().info("PASS 7  往返读取: title=" + title + " author=" + author
                    + " pages=" + got.size());

            if (!"构思技艺图鉴".equals(title)) {
                fail++;
                getLogger().severe("FAIL 7a 标题不一致: " + title);
            }
            if (!"QingHuaCiSJ".equals(author)) {
                fail++;
                getLogger().severe("FAIL 7b 作者不一致: " + author);
            }
            if (got.size() != pages.size()) {
                fail++;
                getLogger().severe("FAIL 7c 页数不一致: 期望 " + pages.size() + " 实得 " + got.size());
            } else {
                for (int i = 0; i < pages.size(); i++) {
                    if (!pages.get(i).equals(got.get(i))) {
                        fail++;
                        getLogger().severe("FAIL 7d 第 " + (i + 1) + " 页不一致");
                        getLogger().severe("        期望 = " + pages.get(i).replace("\n", "\\n"));
                        getLogger().severe("        实得 = " + got.get(i).replace("\n", "\\n"));
                    }
                }
                if (fail == 0) {
                    getLogger().info("PASS 7d 全部 " + pages.size() + " 页逐字节往返一致");
                }
            }
        } catch (Throwable t) {
            fail++;
            getLogger().severe("FAIL 7  往返读取抛了: " + t);
        }

        getLogger().info("========== 结果: " + (fail == 0 ? "全部通过" : (fail + " 项失败"))
                + " ==========");
    }
}
