# 组图标 lore 回归检查（LoreCheck）

一次性校验插件。**不属于交付物**，只在测试服上跑，验完就删。

## 它防的是什么 bug

`更新日志的 lore 全没了`。

Slimefun 的 `ItemGroup.getItem(Player)` 会**无条件覆盖 lore**：

```java
new CustomItemStack(item, meta -> {
    ...
    meta.setLore(Arrays.asList("", ChatColor.GRAY + "> " + ChatColor.GREEN
            + getMessage(p, "guide.tooltips.open-itemgroup")));   // ← 就是这里
});
```

本附属 16 个按钮组（14 条更新日志 + 创作者 + 图鉴）**全部带自定义 lore**
⇒ 只要 `GsItemGroup` 走父类实现，lore 必然被「点击打开」那两行顶掉。

RSC 的 `RSCItemGroupLegacy` 正是靠**重写这个方法**规避的，
反编译其字节码得到的分支逻辑（`GsItemGroup.getItem` 逐条照搬）：

```
if (!item.hasItemMeta())              return super.getItem(p);   // 无 meta ⇒ 交给父类
ItemMeta meta = item.getItemMeta();
if (meta == null)                     return super.getItem(p);   // 同上
List<String> lore = meta.getLore();
if (lore != null && !lore.isEmpty())  return item;               // ★ 有 lore ⇒ 原样返回
return super.getItem(p);                                         // 无 lore ⇒ 父类补提示
```

## 怎么跑

```powershell
# 1) 打包 checker（用 GSPlugin 的 libs 当 classpath，GS.jar 提供 GsItemGroup/Items）
$JH='C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
cd D:\qq下载\粘液科技自用\GSPlugin\tools\lorecheck
$cp = ((Get-ChildItem ..\..\libs -Filter *.jar | % FullName) -join ';') + ';..\..\build\GS.jar'
New-Item -ItemType Directory out -Force | Out-Null
& cmd /c "`"$JH\bin\javac.exe`" -encoding UTF-8 --release 21 -nowarn -proc:none -cp `"$cp`" -d out LoreCheckPlugin.java"

# 2) 打成 jar（plugin.yml 放根）
python -c "import zipfile,os; z=zipfile.ZipFile('LoreCheck.jar','w',zipfile.ZIP_DEFLATED); [z.write(os.path.join(r,f), os.path.relpath(os.path.join(r,f),'out').replace(os.sep,'/')) for r,_,fs in os.walk('out') for f in fs]; z.write('plugin.yml','plugin.yml'); z.close()"

# 3) 丢进测试服，连同刚构建的 GS.jar 一起开服
Copy-Item LoreCheck.jar ..\..\..\_gstest\plugins\LoreCheck.jar -Force
Copy-Item ..\..\build\GS.jar ..\..\..\_gstest\plugins\GS.jar -Force
# 4) 开服后看日志
Select-String ..\..\..\_gstest\logs\latest.log -Pattern 'LORE-CHECK'
```

期望输出：

```
[LoreCheck] LORE-CHECK ===== 测了 23 个带 lore 的组，失败 0，跳过 0 =====
[LoreCheck] LORE-CHECK 全部通过 ✅
```

## 实测记录（2026-10-16）

| 条件 | 结果 |
| --- | --- |
| **有** `GsItemGroup.getItem` 覆盖（当前） | 测了 23 个，**失败 0** ✅ |
| 把覆盖改名停用（退回 Slimefun 实现） | 测了 23 个，**失败 23** ❌ |

⇒ 负向对照证明这个检查**真的抓得住**这个 bug，不是空跑。

## ★ 为什么传 `null` 当 player

「物品自带 lore」那条分支**根本不碰 player 参数**，所以 `getItem(null)` 不会炸。
一旦有人删掉覆盖，`getItem(null)` 会立刻 NPE 或把 lore 换成「点击打开」提示 ——
两种都会被本插件抓到。这是个**故意设计**的哨兵。

## ★ 为什么不在 GSPlugin 里当普通探针跑

`new ItemStack(Material.PAPER)` 在**无服务器环境**下造不出来：
Paper 的 `CraftItemStack` 需要 NMS 的 `net.minecraft.world.item.ItemStack`，
`craftDelegate` 会是 `null`（`NullPointerException: this.craftDelegate is null`）。
撑假 `Registry` 只能解决 `Material` 的静态初始化，解决不了 NMS。
⇒ 这个检查**必须在真服务器里跑**。

---

## 附：同一轮排查里发现的第二个偏差 —— 翻页按钮

用「比对 `RSCItemGroupLegacy` 与 `GsItemGroup` 的方法集」这个办法顺出来的第二个
「日志看不出来」的渲染偏差。

### 现象

单页的物品组，槽 46 / 52 在原版里是**两块黑玻璃板**（不可点占位），
早期原生版是**空的**。

### 原因

RSC 的 `setup()` 里这两个按钮是**无条件**加的（反编译字节码 offset 507 / 542，
中间没有任何 `if`）：

```java
menu.addItem(46, ChestMenuUtils.getPreviousButton(p, page, maxPage));
menu.addMenuClickHandler(46, ...);   // 处理器内部才判 page - 1 > 0
menu.addItem(52, ChestMenuUtils.getNextButton(p, page, maxPage));
menu.addMenuClickHandler(52, ...);   // 处理器内部才判 page + 1 <= maxPage
```

因为 `ChestMenuUtils` **自己就带边界处理**（反编译 `getPreviousButton`：

```
0: iload_2 / iconst_1 / if_icmpeq 10   // max == 1 ⇒ 走占位
5: iload_1 / iconst_1 / if_icmpne 29   // page != 1 ⇒ 走可用
10: PREV_BUTTON_INACTIVE               // ← 黑玻璃板
29: PREV_BUTTON_ACTIVE
```

`getNextButton` 同理，条件是 `max == 1 || page == max`）。

所以外面**不该**再判一次 `page` —— 多判一次就把占位板判没了。

### 修法

`GsItemGroup.open()` 改成与 RSC 一致：无条件 `addItem(46/52)`，
边界判断挪进点击处理器。`maxPage` 的算法也逐字照抄（含那个看着别扭的分支）：

```java
int maxPage = (target == valid.size() - 1) ? page : valid.size() / 36 + 1;
```

### 验证到什么程度

| 项 | 怎么验的 | 结果 |
| --- | --- | --- |
| 占位物品存在且是玻璃板 | 本插件反射读 `PREV_BUTTON_INACTIVE` / `NEXT_BUTTON_INACTIVE` | `BLACK_STAINED_GLASS_PANE` ✅ |
| 「单页 ⇒ 占位」这条分支 | `javap -c` 反编译 `ChestMenuUtils` | 已确认（见上） |
| 分支的**运行时**行为 | ❌ **验不了** —— 见下 | 待真人开图鉴确认 |

**为什么运行时验不了**：`getPreviousButton(p, ...)` 里 `Objects.requireNonNull(player)`，
而它内部的本地化查询要用真 `PlayerProfile`。试过传 `null`（`IllegalArgumentException`）
和动态代理假玩家（`NullPointerException: "container" is null`，PDC 取不到），都过不去。
⇒ **这一条建议真人进游戏点开「信息」看一眼槽 46 / 52 是不是黑玻璃板。**
