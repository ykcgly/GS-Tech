# 构思技艺 (GS) 

> \*\*万物初生，始于想象\*\*
>
> 我的世界粘液科技（Slimefun）附属 · 基于 \*\*RykenSlimeCustomizer (RSC)\*\* 编写
> 服务端版本：\*\*1.21.11\*\* ｜ 全部使用\*\*原版材质\*\*

\---

## 一、这是什么

**构思技艺**是一个围绕「想象」这个主题设计的 Slimefun 附属：

从想象里长出材料，用材料造出机器，用机器推演出武器与梦境，最后面对五个由「念头」具现成的 Boss。

它是一个 **纯脚本驱动** 的附属——55 件物品、8 台机器、5 个 Boss、2 段红石音乐，
所有行为逻辑全部写在本包收录的 **18 个 `.js` 文件**里，由 RSC 的脚本引擎（GraalJS）在服务端沙箱中执行。

**本包内容**：`scripts/` 目录下的 18 个 `.js`，不含 yml 配置、存档物品和文档。
换句话说——**这里只有"行为"，没有"声明"**。光靠这 18 个文件跑不起来，它们需要配套的 `items.yml` / `groups.yml` / `saveditems/` 才能挂到粘液书上（详见第八节）。

\---

## 二、⚠ 版本要求

**本附属于 1.21.11 制作，低版本会出问题。**

低于 1.21.11 可能导致：

* 部分特效、粒子消失
* 新增音效无法播放
* 新增方块 / 物品变成空气或报错
* 严重时插件崩溃、存档损坏

核心原因是脚本大量依赖新版本的原版音效键、粒子名和物品组件。**请确认服务端版本后再使用。**

依赖环境：

|项目|要求|
|-|-|
|服务端|Paper / Purpur 1.21.11|
|Slimefun|4.x|
|RykenSlimeCustomizer|必需，脚本引擎由它提供|
|Java|按 RSC 要求（GraalJS 跑在 JVM 里）|

\---

## 三、内容规模

七个内容分类（外加主分类本身）：

|顺序|分类|图标材质|数量|
|:-:|-|-|-|
|1|信息|`PAPER` 纸|16 个按钮（创作者 + 图鉴 + 14 条更新日志）|
|2|基础构思|`STRING` 线|21 件纯材料|
|3|构思机器|`SEA\_LANTERN` 海晶灯|8 台机器|
|4|构思武器|`IRON\_SWORD` 铁剑|10 件|
|5|构思防具|`IRON\_CHESTPLATE` 铁胸甲|9 件|
|6|构思boss|`WITHER\_SKELETON\_SKULL` 凋零骷髅头颅|5 件|
|7|构思玩具|`BREEZE\_ROD` 旋风棒|10 件|

合计 **55 件物品 + 8 台机器 + 16 个界面按钮**。

五个 Boss 各有独立机制，没有一个是"血厚一点的原版怪"：

|Boss|机制概要|
|-|-|
|枯骸骑将|单阶段 · 骑行 · 四技能 · 狂暴凋零之首 · BGM|
|妄念|三阶段 · 逐阶段技能 · BossBar · 锁链拽回|
|暗影虫卵|三阶段 · 反制 / 弹道 / 伤害拦截|
|守夜人的提灯|两阶段 · NMS 索敌限制 / 反冲击波|
|幻翼|四技能 · 体型缩放 · 不炸地形|

\---

## 四、脚本是怎么被加载的

这里有 **三种入口**，理解这一点是读懂这套源码的前提。

### ① 全局监听：`gs\_listener.js`

`info.yml` 里声明：

```yaml
scriptListener: "gs\_listener"
```

这一个文件是整套附属的**主干**，677 KB，按模块分段。它负责：

* 梦境套的全部被动（免死 / 药效 / 防辐射 / 粒子）
* 月镰、蚀月·死神梦镰、残樱、余念绝响、致万千无言者、此刻·万籁俱寂、阎魔刀·改、五骊矟、威龙、祖宗发射器、篮球生成器、海水过滤器
* 基础构思纯材料拦截、副手钥匙锁、展示实体清扫、原版近战取消

入口函数只有四个是 RSC 主动调的：

```
onPlayerDeath · onPlayerJoin · onPlayerQuit · onEntityDamage
```

**其余全部靠文件内自注册的同步监听器 + `runLater` 自链心跳。**

### ② 物品右键：`items.yml` 的 `script:`

只有 **14 件**物品这样挂，入口统一是：

```js
function onUse(event) { ... }
```

### ③ 界面按钮：`groups.yml` 的 `type: button`

```yaml
type: button
actions: \["script gs\_guide\_book"]
```

入口统一是：

```js
function onButtonGroupClick(p, slot, clickedItem, clickAction, mode) { ... }
```

### ★ 为什么一大批物品**故意不写** `script:`

因为 `script:` 只把 **`onUse`（右键）** 挂上去。而：

* 月镰、蚀月、残樱、余念绝响、致万千无言者、此刻·万籁俱寂、阎魔刀·改、五骊矟、威龙、祖宗发射器、篮球生成器这些，**左键 / 右键 / F 键都要用**；
* 月镰和蚀月是**工具材质**（铁锄 / 下界合金锄），右键会被原版抢走 `useItemInHand`，走 `onUse` 会**时灵时不灵**。

所以它们**全部在 `gs\_listener.js` 里自己 `registerEvent` 注册同步监听器**，物品那边不写 `script:`。

\---

## 五、18 个脚本逐一说明

### 核心

|文件|大小|作用|
|-|-|-|
|`gs\_listener.js`|661 KB|全局监听主干，见上一节|

### Boss（5）

|文件|作用|入口|
|-|-|-|
|`gs\_boss\_rider.js`|枯骸骑将|`onUse`、`onPlayerQuit`|
|`gs\_boss\_three.js`|妄念（三阶段 + BossBar + 锁链拽回）|`onUse`|
|`gs\_boss\_shadow.js`|暗影虫卵（三阶段 + 反制 / 弹道 / 伤害拦截）|`onUse`|
|`gs\_boss\_nightwatch.js`|守夜人的提灯（两阶段 + NMS 索敌限制）|`onUse`|
|`gs\_boss\_phantom.js`|幻翼（四技能 + 体型缩放）|`onUse`|

### 音乐（2）

|文件|作用|
|-|-|
|`gs\_bad\_apple.js`|Bad Apple — **36462 个音符 / 183.5 秒 / 10 种音效**|
|`gs\_cursed.js`|咒术回战 — **1202 个音符 / 43.5 秒 / 8 种音效**，宿瘫 + 五条舞，第 16\~22 秒飞升 8 格|

### 道具与玩具（7）

|文件|作用|
|-|-|
|`gs\_release\_dream.js`|释梦：灵魂沙弹道 → 落下炸开 → 灵魂沙云 → 每秒伤害|
|`gs\_borrow\_life.js`|借寿：沿准星三维朝向 长 5 × 宽 5 × 高 2，1 点伤害 / 0.5 秒冷却，命中 n 只回 n 点血|
|`gs\_rolling\_log\_ingot.js`|滚木：右键前方 宽 3 × 长 5 隐身 10 秒，9 秒冷却，物品不消耗|
|`gs\_fake\_weisi.js`|假为思：一键自动穿上梦境套|
|`gs\_maiyou\_wine.js`|麦友的酒：召唤河豚 + 伤害拦截 + 掉落清空 + 桶交互拦截|
|`gs\_song.js`|如听仙乐耳暂明：小恶魂叫声 `entity.ghastling.ambient`，500ms 防连点|
|`gs\_ding\_me.js`|这你叮我|

### 界面按钮（3）

|文件|作用|入口|
|-|-|-|
|`gs\_guide\_book.js`|图鉴：点「信息」第 2 格当场构造成书，页数 = `GG\_PAGES.length`|`onButtonGroupClick`|
|`gs\_update\_log.js`|更新日志：16 个日志按钮，只播一个翻页音效|`onButtonGroupClick`|
|`gs\_info\_creator.js`|创作者：发两句提示词 + 经验球音效|`onButtonGroupClick`|

\---

## 六、写这套脚本必须知道的五个坑

这些不是理论，是**线上炸过的**。

### 1\. BossBar 标题只能喂 `String`

线上核心 `CraftBossBar` 只有 `setTitle(String)`（内部 `Component.literal`），**没有** Adventure 的 `title(Component)` 重载。

所以颜色只能用 **`§` 码**（`§6` 金 / `§c` 红 / `§f` 白）。塞 `Component.text(...)` 会被 GraalJS 隐式 `toString()`，血条标题直接显示成：

```
TextImpl{obfuscated=not\_set, …}
```

统一入口是 `gsBarTitleSet()`。

### 2\. 长演出一律用 `runLater` 自链，别用 `runRepeating`

RSC 的 `runRepeating` **返回值不可靠**。视角锁定 / 法阵绘制 / 落地检测各起一条 `runLater` 自链，互不干扰。

### 3\. 矩形柱判定要用水平投影，不能沿视线采样

月镰挥砍范围是**前方 3 格长 × 3 格宽**的矩形柱，判定靠水平前向 / 右向投影：

```
fwd ∈ \[-0.4, 3]  ∧  |lat| ≤ 1.5  ∧  高度差 ≤ GS\_MS\_TALL
```

原先"沿视线每 0.6 格采一个柱"等于**一条线**，**打不到侧前方的怪**。

### 4\. 取消原版近战，必须放行"自己打的伤害"

`gsWpnNoMelee` 监听 `EntityDamageByEntityEvent`，对月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂取消整个事件——**伤害 + 击退 + 无敌帧一起消失**（三者都在 `hurt()` 里）。

但我们的技能伤害走 `ent.damage(dmg, 玩家)`，事件里 `damager` 同样是玩家、`cause` 同样是 `ENTITY\_ATTACK`。**不设闸门会把自己也取消掉。**

解法用 `GS\_WPN\_DMG\_GUARD` 计数器（打前 +1 / 打完 −1，`damage()` 是同步的）。

另外两个细节：

* 原因字符串是 `ENTITY\_ATTACK` **和** `ENTITY\_SWEEP\_ATTACK`，判 `cause.name()` 要**同时认这两个**
* 想"只掉血不击退"就**存 velocity → 打完立刻还原**；想不留无敌帧就**打前 `setNoDamageTicks(0)` + 打后再清一次**（只清一遍不够，`hurt()` 会重新设成 10 刻）

### 5\. 复用定身机制有安全前提

`gsMsLockFor(ent, ms, col)` 被蚀月轮复用（0.6 秒、不画柱）。

**前提**：要么时长小于两次伤害的间隔，要么有"同目标 N 秒内只定一次"的外闸。

蚀月轮是**定身 0.6 秒 > 出伤间隔 0.5 秒**，靠 `GS\_EC\_W\_LOCK\_CD`（同目标 3 秒只定一次）兜底。

**去掉那道闸就会变成永久定身**——曾经 1.5 秒定身 + 每秒伤害时，线上实测"怪永远动不了"。

\---

## 七、已知限制

**1. 脚本改动必须重启服务器。**
`/rsc reload` **不会重新读取 `scripts/` 里的 `.js`**（只重载配置）。只 reload 的话跑的还是旧脚本。
另外脚本创建的计时器**不会随附属重载被取消**，重载时已在线的玩家可能短暂出现双倍产出，重登即恢复。

**2. 冷却数据存在内存中。**
滚木隐身、篮球等冷却，服务器重启后清空。

**3. 本附属不修改任何原版战利品表。**
基础构思那批材料的获取途径**全部是机器合成或 Boss 掉落**，挖原版方块**只掉原版的东西**。

\---

## 八、这包里有什么 / 没什么

**有**：`scripts/` 下 18 个 `.js`，1.87 MB 源码，压缩后 0.51 MB。

**没有**：yml 配置、存档物品、文档。

想让它真正跑起来，还需要配套的：

```
plugins/RykenSlimeCustomizer/addons/GS/
├── info.yml                    # scriptListener = gs\_listener
├── groups.yml                  # 分类 + 按钮
├── items.yml                   # 55 件物品
├── recipe\_types.yml            # 13 个「只显示、不合成」的配方类型
├── recipe\_machines.yml         # 万象归序 / 唤梦机 / 海水过滤器的真配方
├── mb\_machines.yml             # 构思机的真配方（多方块）
├── mat\_generators.yml          # 拟人思维 / 想象力生成器 / 概念分析仪
├── menus.yml                   # 机器界面排版
├── saveditems/                 # ★ 10 个存档物品，是内容的一部分
└── scripts/                    # ★ 本包这 18 个 .js
```

**`saveditems/` 和 `scripts/` 都不能漏。** 凡是 `material\_type: saveditem` 的物品，材质**烘焙在存档物品里的**（纹饰 / 头颅皮肤 / `item\_model` 组件写不进 `items.yml`），少了对应 `.yml`，物品会变成空气或石头。

### 改脚本时的约定

* **加事件逻辑** → 写进 `gs\_listener.js`
* **函数名 = `on` + 事件名（去掉 `Event`）**，例如 `BlockBreakEvent` → `onBlockBreak`
* **加界面按钮** → `groups.yml` 里 `type: button` + `actions: \["script 脚本名"]`

