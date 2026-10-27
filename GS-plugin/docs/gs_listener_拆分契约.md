# gs_listener 拆分契约

> 先完整读 `docs/移植契约.md`（通用规范），再读本文件（本脚本专用）。
> 原脚本：`D:\qq下载\粘液科技自用\QHCGS\GS\GS\scripts\gs_listener.js`（14,337 行）
> 参考服日志：`docs\参考服日志_构思技艺.txt`（**真 JS 打出来的**，对账金标准）

---

## 0. 为什么 gs_listener 要特殊处理

它是**一个** JS 全局作用域，14,337 行 / 564 个顶层函数 / 520 个顶层 var，
里面塞了 **24 个子系统**（梦境套 / 光环 / 余念绝响 / 残樱 / 五骊矟 / 威龙 /
基础构思 / 月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂 / 阎魔刀 / 武器禁近战 /
篮球 / 篮球生成器 / 钥匙锁 / 孤儿球清扫 / 海水过滤器 / 祖宗发射器 / 灵感掉落 …）。

机械统计（已跑过脚本）：**47 个函数被 2 个以上子系统引用**，
`gsMsNow` 被 52 个函数调用、跨 8 个子系统；`gsIsItem` 跨 16 个子系统。
所以**不能**把子系统做成互不相干的独立模块。

**已经替你抽好了**：`src\main\java\com\qinghuacisj\gs\script\module\listener\ListenerCore.java`
（已编译通过）。它装了：
- 全部共享顶层常量与状态（`GS_NODMG_IDS` / `GS_NOT_ENEMY_TYPES` / `GS_DA_CD_KEY` /
  `GS_AURA_KEY` / `gsInspTasks` / `gsMsLocks` / `gsWpnDmgGuard` / 各族配置缓存 …）
- 全部 47 个共享函数（`gsNewItem` / `gsIsItem` / `gsCfg` / `gsDaConfig` / `gsDaLog` /
  `gsRepairWorn` / `gsDreamFullSet` / `gsRadMark` / `gsSetPotionType` / `gsAuraIsCurrent` /
  `gsAuraConfig` / `gsAuraGenBump` / `gsAuraGenNow` / `gsTotemParticles` /
  `gsIsNoDurabilityItem` / `gsBarTitleSet` / `gsBarTblHide` / `gsBarTblDrop` /
  `gsBarTblClean` / `newBarTable` / `gsMsLoc` / `gsMsVec` / `gsMsSound` / `gsMsSoundAt` /
  `gsMsStarType` / `gsMsDustType` / `gsMsDustOpts` / `gsMsDust` / `gsMsHurt` /
  `gsMsLockFor` / `gsIsCombatTarget` / `gsSiDmg` / `gsVoEnchType` / `gsWuliCdLeft` /
  `gsWuliMarkCd` / `gsIsOurSummon` / `gsIsMaiyouSummon` / `gsWlBlockedAt` / `now` / …）

**先读 `ListenerCore.java` 一遍**，确认哪些东西已经有了，别重复实现。

---

## 1. 子系统的统一形状

包：`com.qinghuacisj.gs.script.module.listener`

```java
package com.qinghuacisj.gs.script.module.listener;

/**
 * {@code gs_listener.js} 的「XXX」子系统（原脚本第 NNNN~MMMM 行）。
 * …把原脚本的注释、参数理由、踩坑记录搬过来…
 */
public final class XxxSub {

    private final ListenerCore core;

    public XxxSub(ListenerCore core) {
        this.core = core;
    }

    /** 对应原脚本顶层的 {@code gsXxxStart();} 调用。 */
    public void start() {
        // …
    }
}
```

**要点**：
- 顶层 var 若**只属于你这个子系统** ⇒ 做成**实例字段**（`private final` 或可变）。
  **不要**塞进 `ListenerCore`（那是共享层，会被别人改坏）。
- 顶层 var 若**跨子系统**且 `ListenerCore` 里没有 ⇒ **在交付说明里明确指出**
  （需要改共享文件 `ListenerCore.java`，由集成者统一加）。
- 方法名 = 原脚本函数名（`gsMsTick` 就叫 `gsMsTick`）。**不要**翻译成英文。
- 常量字段名 = 原脚本 var 名（`GS_MS_DMG` 就叫 `GS_MS_DMG`），方便常量对账探针反射比对。
- 子系统之间**只允许**通过 `core` 交互。若发现必须直接调用另一个子系统的
  **非共享**函数 ⇒ 在交付说明里指出，不要擅自跨类引用（会编译不过）。

---

## 2. 已定的子系统清单与行号（机械统计出来的，可直接用）

| 子系统 | 原脚本行区间 | 顶层入口 | 谁负责 |
| --- | --: | --- | --- |
| 灵感掉落 / 奇思妙想帽 | 31~208 | 顶层 `runLater` + `onPlayerJoin/Quit` | 集成者（模块本体） |
| 梦境套免死（同步拦截） | 222~440, 596~675 | 顶层 `gsRegisterDreamArmor()` | 集成者（模块本体） |
| 永不掉耐久 | 442~559 | 顶层 `gsNoDurabilityRegisterSync()` | 集成者（模块本体） |
| 梦境套光环（粒子） | 677~900 | `gsAuraStart()` | A |
| 梦境套套装效果 | 892~1117 | `gsSetBonusStart()` | A |
| 开镜 + 余念绝响 | 1119~2044 | `gsPsychicStart()` | B |
| 残樱 | 2046~2809 | `gsCherryStart()` | B |
| 五骊矟 | 2811~4833 | `gsWuliStart()` | C |
| 威龙 | 4835~5833 | `gsWlStart()` | C |
| 基础构思（纯材料） | 5835~5944 | `gsPlainStart()` | C |
| 月镰 | 5946~6846 | `gsMsStart()` | D |
| 蚀月·死神梦镰 | 6848~7789 | `gsEcStart()` | D |
| 致万千无言者 | 7791~8599 | `gsVoStart()` | E |
| 此刻万籁俱寂 | 8601~9279 | `gsSiStart()` | E |
| 阎魔刀·改 | 9281~10837 | `gsEmStart()` | E |
| 武器禁近战 | 10839~10950 | `gsWpnNoMeleeStart()` | E |
| 篮球 | 10952~12552 | `gsBbStart()` | F |
| 篮球生成器 | 12554~13167 | `gsBgStart()` | F |
| 副手篮球钥匙锁 | 13169~13563 | `gsKlStart()` | F |
| 孤儿球清扫 | 11706~13569 | `gsEntPurgeStart()` | F |
| 海水过滤器（钓鱼） | 13571~13671 | `gsSfStart()` | G |
| 祖宗发射器 | 13673~14336 | `gsAlStart()` | G |

⚠️ **注意行区间有重叠**：原脚本是「函数定义在前、顶层调用在后」的布局，
所以某个子系统的**函数定义**可能出现在另一个子系统的**调用点**之后。
判断归属看**函数名前缀**（`gsMs*` 属月镰、`gsEc*` 属蚀月、`gsBb*` 属篮球…），
不要只看行号。`gsEntPurge*` 的定义在 11708，但它的顶层调用在 13569。

---

## 3. 对账金标准（**每个子系统必须逐字节对上自己那几行**）

全部在 `docs\参考服日志_构思技艺.txt`。按子系统列在这里（**`｜`、`·`、`⇒`、
全角括号、`**加粗**`、`①②③④`、`×` 都是字面文本，原样保留**）：

### A —— 光环 + 套装效果
```
[构思技艺] 梦境套：常驻粒子已启动（每 3 刻一次；★ 四件齐全才刷）
[构思技艺] 梦境套：套装效果已就绪（入梦·飞行 / 免死 45 秒 / 生命恢复II·夜视·迅捷II·饱和III·抗性IV·急迫III / 防辐射(宽限期) / 粒子圈）
```
（另见模块本体的 `梦境盔甲：致命伤害拦截已注册（同步监听器）` 与
`释梦 / 梦境套：永不掉耐久已启用（等效 unbreakable）`）

### B —— 余念绝响（含开镜） + 残樱
```
[构思技艺] 余念绝响监听器注册成功（左键=开火 · 右键=开镜（开/关·0.8秒防抖·不需副手） · F=换弹 · Q=原版丢弃）
[构思技艺] 余念绝响：单手持冲锋枪已启用（37 发，射速 2000 RPM，伤害 3~6，射程 25，左键开火，右键开镜（开/关，0.8 秒防抖，★ 不需要副手），F 换弹，Q 原版丢弃）
[构思技艺] 残樱监听器注册成功（左键=樱花射线 · 右键=切换单发/三连发散射 · F=换弹 · Q=原版丢弃）
[构思技艺] 残樱：单手持手枪已启用（12 发，单发 0.2 秒/发 · 射程 16，三连发 3 发**同时出膛**（横向偏移固定 0.20 格，不随距离散布）· 射程 6 · 0.3 秒一轮，伤害 8，换弹 1.8 秒，左键开火，右键切换发射方式，F 换弹）
```
> ⚠️ **更正记录（本文件早先的版本在这里出过错）**：早先本文把上面最后一行写成
> 以 `换弹 1.8 秒` 结尾、**没有右括号**，并加了一句「原样就是这样，照抄」。
> **那是错的** —— 是我抄写时漏了尾巴。原脚本第 2803 行是：
> ```js
> + ' 秒，左键开火，右键切换发射方式，F 换弹）');
> ```
> `docs/参考服日志_构思技艺.txt` 里那一行也以 `，F 换弹）` 收尾。
> 已按 **脚本 + 参考服日志** 更正为上面这行。
>
> ★ **教训**：这类「照抄这行、注意它就是这样」的说明，**必须回源文件核过再写**。
> 一句错的「照抄」比没有说明更危险 —— 它会让执行者把错误当成规范。

### C —— 五骊矟 + 威龙 + 基础构思
```
[构思技艺] 五骊矟监听器注册成功（F=上马/收阵 · 左键=剑气 · 右键=冲锋）
[构思技艺] 五骊矟：五骊骑阵已启用（F = 上马/收阵；左键剑气 7 点·0.5 秒·静默·龙吟+传送音；右键冲锋 15 点·蓄力 1 秒（无敌）·冲刺 15 格（2 秒动画，整段 3.0 秒）·冷却 10 秒）
[构思技艺] 五骊矟：坐骑移速下限 0.28（≈ 12 格/秒；只升不降）
[构思技艺] 五骊矟：蓄力无敌 = true · 冲刺无敌 = false（收阵/下线/死亡/冲刺结束都会无条件解除）
[构思技艺] 五骊矟：开机动画 = **正前方** 7 格冲出一匹骷髅马，1.5 秒内撞到玩家（0.9 格内判定）→ 副手出现「五骊鞚」+ 扶上马；坐骑朝向已对齐玩家视角，不会掰镜头
[构思技艺] 五骊矟：下马动画 = 4 匹护航马化烟 + 队长马朝前方 5 格奔出后化烟（0.5 秒）；玩家全程可自由移动
[构思技艺] 威龙监听器注册成功（左键=磁吸炸弹 · 右键=虎蹲炮 · F=动力推进）
[构思技艺] 威龙：磁吸炸弹已启用（7 格 · 3 秒 · 2 格 35 点 · 冷却 15 秒 · 吸附 1.5 格）
[构思技艺] 威龙：虎蹲炮已启用（12 格 · 2 秒 · 3 格 1 点 + 击退 + 定身 4.5 秒 · 冷却 25 秒）
[构思技艺] 威龙：动力推进已启用（F 键 · 水平 12 格 · 冷却 8 秒 · 击杀生物立即清空冷却）
[构思技艺] 威龙：左键/右键/冲刺落地 → 速度 II 3 秒（自带 5 秒冷却 · 烈焰人火球音）
[构思技艺] 威龙：所有 TNT **永不引爆**、爆炸为手搓演出 ⇒ 不炸方块 / 不炸展示框 / 不触发按钮
[构思技艺] 威龙：TNT 逐段碰撞检测（0.25 格）⇒ 不穿玻璃 / 不穿墙
[构思技艺] 威龙：手持时**无法破坏方块**（左键 / 右键都不动方块），按键即放、不用点方块
[构思技艺] 威龙：冲刺**只走水平方向**（抬头 / 低头不会往上下冲）
[构思技艺] 威龙：**击杀任意生物即清空冲刺冷却**（不要求手持威龙）
[构思技艺] 基础构思监听器注册成功（纯材料左右键交互已取消，滚木除外）
[构思技艺] 基础构思：18 件纯材料已取消左右键交互（石头/材料都不再响应点击，**滚木保留右键隐身**）
```

### D —— 月镰 + 蚀月
```
[构思技艺] 月镰监听器注册成功（左键挥砍叠梦 / 满 12 层右键噩梦）
[构思技艺] 月镰状态：层数存内存（权威）· PDC 备份 可用 · 世界世代号 96
[构思技艺] 月镰（GS_MOON_SCYTHE）已加载 · 挥砍 11 点 / 范围 3 格长 × 3 格宽 / 满 12 层 / 噩梦 35 点 · 半径 5 格 / 定身 3 秒
[构思技艺] 蚀月·死神梦镰监听器注册成功（左键蚀月轮叠血 / 满 23 层右键审判）
[构思技艺] 蚀月状态：层数存内存（权威）· PDC 备份 可用
[构思技艺] 蚀月·死神梦镰（GS_ECLIPSE_SCYTHE）已加载 · 蚀月轮 14 点 / 每 0.5 秒 1 次 / 射程 5 格 / 每刻 0.5 格 / 落地后留 1.5 秒 / 定身 0.6 秒（同目标间…
```
⚠️ 前 6 行里，「世界世代号 96」是**运行期动态值**（世代号），探针里要按动态处理；
其余部分逐字节比对。最后两行在参考日志里被**截断**了（RSC 的日志行有长度上限），
所以只能比对**前缀**；比对到参考日志实际有的长度为止，并在交付说明里写明。

### E —— 致万千无言者 + 此刻万籁俱寂 + 阎魔刀 + 武器禁近战
```
[构思技艺] 致万千无言者监听器注册成功（左键挥砍叠连击 / 右键蓄力 0.6 秒放“逝言”）
[构思技艺] 致万千无言者（GS_VOICELESS）已加载 · 挥砍 7~13 点（随连击 +1/层）/ 满 7 连击 / 窗口 3 秒 · 逝言 蓄力 0.6 秒 / 11 点 / 射程 7 格 / 宽 3 格 / …
[构思技艺] 此刻万籁俱寂监听器注册成功（左键 3×5×2 斩击叠连击 / 右键瞬发“寂灭” 7 格圆圈 AoE）
[构思技艺] 此刻万籁俱寂（GS_SILENCE）已加载 · 斩击 17~213 点（随连击 +2/层 · 满 99 连击）/ 范围 宽3×长5×高2 / 窗口 7 秒（未命中**清 0**） · 寂灭 半径 7 格圆圈…
[构思技艺] 阎魔刀·改监听器注册成功（左键=居合斩六刀·第六刀附带次元斩 / 右键=锚点·坐标写入lore / Shift+右键=裂缝 / F=原版换手）
[构思技艺] 阎魔刀·改（GS_ENMA_BLADE）已加载 · 居合斩 37 点 / 范围 宽5×长9×高3 / 6 刀一循环（冷却 0.2 秒 · 特效 0.4 秒 · 停手 3 秒清空 · 前五刀「画圆圈切掉后面」…
[构思技艺] 武器原版近战已取消（月镰 / 蚀月 / 致万千无言者 / 此刻万籁俱寂）· 已启用 · 对玩家 照常
```

### F —— 篮球 + 生成器 + 钥匙锁 + 孤儿球清扫
```
[构思技艺] 律师函监听器注册成功（穿着胸甲 → 尘埃粒子篮球 / 触碰绑定 / ★ 绑定期间副手自动出现「篮球」（ACACIA_BUTTON）⇒ 右键直接投 / ★ 绑定期间挂「运球」倒计时血条（只有这两个字 / 10 …
[构思技艺] 你等着收我的律师函吧（GS_LAWYER_LETTER）已加载 · 尘埃粒子篮球（非烟花 · 橙黑 26 点 · 直径 0.48 格 ≈ 头颅 · 粒子大小 0.8）/ 触碰 1.4 格 / 绑定 10 秒…
[构思技艺] 篮球生成器（GS_BASKET_GEN）已加载 · 放下后上方 1.5 格生成篮球 / **每台只有 1 颗球**（多人抢同一颗，捡走不再补第二颗）/ 只有**停着的球**捡得走（抢不走别人手上的）/ 超出…
[构思技艺] 副手「篮球」钥匙锁已加载（6/6 条事件） · 运球/持球期间那颗按钮**拿不下来**（点击副手槽 / F 键 / 拖拽 / 丢弃 一律拦回） · 被外力拿走 ⇒ 每刻补回 + 0.5 秒清残留 · 死亡时…
[构思技艺] 展示实体「孤儿球」清扫已加载 · 启动清了 0 个残留 / 之后每 1 秒扫一次孤儿 / 关服前再全清一遍（关服清扫监听 = 已注册） / ★ 认球只认「我们的标记 + 专属头颅」⇒ 别人的展示实体一律不碰
```
⚠️ **`GS_BB_ID` 指向一个已删除的物品**（`GS_LAWYER_LETTER` 是篮球的 ID，
但日志文案写「律师函」是**历史遗留**）。★★ **绝对不要「修正」这些文案** ——
`_doc_final.py` / `_doc_fix3.py` / `_doc_sec.py` / `_doc_archive.py` 都断言了
「你等着收我的律师函吧」和「律师函监听器注册成功」这两句。

### G —— 海水过滤器 + 祖宗发射器
```
[构思技艺] 「海水过滤器」钓鱼获取已加载（监听 PlayerFishEvent · 状态 CAUGHT_FISH · 钓率 7% · 每次 1 个 · 不取消原版钓鱼）
[构思技艺] 祖宗发射器监听器注册成功（左键=开火 · 右键=爆裂黎明 开/关 · 常态 0.8 秒/发 · 技能 1.5 秒/发 · 6 发 · 冷却 12 秒）
[构思技艺] 祖宗发射器<?>：已启用（常态 0.8 秒/发 · **无限弹药** · 红色粒子 · 爆炸 2.5 格 / 35 点；右键「爆裂黎明」⇒ 6 发 ▼ · 1.5 秒/发 · 坚守者冲击波 · 爆炸 4.5 …
```

---

## 4. 监听器怎么注册（**重要，与原脚本不同但可观察行为相同**）

原脚本用 RSC 的 `pm.registerEvent(类, plugin, Priority.X, function(l,e){...}, plugin)`
**在脚本里自己注册监听器**。它有一个已知缺陷：**每次 `/rsc reload` 都会再注册一遍**，
监听器会累积（原脚本用 `GS_XX_REGISTERED` 闩锁 + 世代号缓解，但闩锁是脚本级变量，
reload 后归零 ⇒ 闩锁挡不住累积）。

**Java 侧的正确做法**：把监听器写成 `org.bukkit.event.Listener` 的实现类，
在 `start()` 里 `Bukkit.getPluginManager().registerEvents(listener, GSPlugin.instance())`
**注册一次**。这样：
- **可观察行为相同**：事件照常收到、优先级照常、取消照常。
- **reload 不再累积** —— 这是**修掉一个真实缺陷**，不是「顺手优化」，
  必须在类注释里写明「原脚本 reload 会累积监听器，Java 版改为幂等注册」。
- 若你发现 `GS_XX_REGISTERED` 闩锁在 Java 里**仍然需要**（比如它保护的不只是注册，
  还影响别的逻辑），那就**保留它**并保持同样的语义。

**优先级与 `ignoreCancelled` 必须逐处核对**：原脚本的 `registerEvent(...)` 第 3 个参数
是 `Priority`，没有第 5 个参数（`ignoreCancelled` 默认 **false**）。
Java 侧 `@EventHandler(priority = EventPriority.X)` 默认也是 `ignoreCancelled = false`，
**默认就对**；但如果你写成 `ignoreCancelled = true` 就是**行为改变**，不许。

**`GS_XX_REGISTERED` 那 18 个闩锁**（`GS_AL_REGISTERED` / `GS_BB_REGISTERED` /
`GS_BG_REGISTERED` / `GS_CH_REGISTERED` / `GS_DA_REGISTERED` / `GS_EC_REGISTERED` /
`GS_EM_REGISTERED` / `GS_KL_REGISTERED` / `GS_MS_REGISTERED` / `GS_NODMG_REGISTERED` /
`GS_PLAIN_REGISTERED` / `GS_PSY_REGISTERED` / `GS_SF_REGISTERED` / `GS_SI_REGISTERED` /
`GS_VO_REGISTERED` / `GS_WL_REGISTERED` / `GS_WPN_REGISTERED` / `GS_WULI_REGISTERED`）
在 Java 里，因为 `ListenerModule` 只有**一个实例**，闩锁天然等价于「实例字段 + 只注册一次」。

**★ 有一个例外必须保留**：`gsRegisterDreamArmor` 的 `GS_DA_REGISTERED` 被
`onEntityDamage` **读**来判断「同步监听器是否生效」（生效时兜底诊断不做事）。
这个语义要保留成实例字段。

---

## 5. 编译与自测（**不要跑 `build.ps1`**，有多个同伴并行）

```powershell
$JH = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
Set-Location 'D:\qq下载\粘液科技自用\GSPlugin'
$cp = ((Get-ChildItem libs -Filter *.jar | % FullName) -join ';') + ';' +
      ((Get-ChildItem libs\graal -Filter *.jar | % FullName) -join ';') + ';build\classes;build\sub_core'

& cmd /c "`"$JH\bin\javac.exe`" -encoding UTF-8 --release 21 -nowarn -proc:none -cp `"$cp`" -d build\sub_X src\main\java\com\qinghuacisj\gs\script\module\listener\*.java 2>&1"
```

`build\sub_core` 里已经有编译好的 `ListenerCore.class`（集成者放的）。
如果你需要引用**别的子系统**的类，说明越界了 —— 回到 §1 的规则。

⚠️ PowerShell 陷阱（本机实测）：没有 `pwsh`；`java` 的 `-Dxxx` 参数直接写在 PowerShell
里会被拆坏 ⇒ 一律走 `cmd /c "..."`；中文输出加 `-Dstdout.encoding=UTF-8`；
读文件用 `Get-Content -Encoding utf8`；**不要把 java 的 stdout 通过 PowerShell 管道看**，
重定向到文件再读。

---

## 6. 探针（**必交**）

写 `tools\XxxParityProbe.java`（`public static void main`），最后打印**一行**
`通过 N ｜ 失败 M`，失败 `System.exit(1)`。必须包含：

1. **常量对账**：用**真 GraalJS** 跑原 `gs_listener.js`，读出**你这个子系统的**顶层 var，
   与 Java 的字段**按名按值**比对。命名约定：Java 字段名 = JS 常量名。
   - 数值比较允许 `int↔long↔double` 的表示差异，**不允许精度损失**。
   - 原脚本顶部有 `GS_PDT = Java.type('org.bukkit.persistence.PersistentDataType')`；
     读顶层 var 时若某个 var 会触发 `ExceptionInInitializerError`
     （如 `Attribute.SCALE`），用 JS 侧 `Java.type` 包 shim 绕开
     （参考 `tools\ConstantParityProbe.java` 的 `SHIM`）。
   - ⚠️ **`gs_listener.js` 的顶层有副作用**（会注册监听器、跑 `runRepeating`、
     甚至 `gsBbStart()`）。在探针里 eval 它之前要先把 `getServer()` / `runLater` /
     `runRepeating` / `registerEvent` 等桥接函数换成**空实现**，否则会炸或卡死。
     参考 `tools\ConstantParityProbe.java` 与 `tools\GraalProbe.java` 里已有的桥接桩。
2. **加载日志行**：与你子系统那几行**逐字节**比对（§3）。把日志行抽成
   `static String xxxLine()` 之类的静态方法，好让探针调用。
3. **数值/几何算式**：把你子系统里的关键算式在**大量输入**上与真 JS 逐值比对
   （伤害公式、层数→伤害、冷却、范围判定、粒子角度…）。
   注意浮点边界：扫描点正好落在边界上时会出现 `-2.5` vs `-2.5000000000000004`
   这类**伪差异**，要跳过或容差。
4. **反向断言**：把「**不应该存在**的东西」钉住（如「没有冷却提示词」「没有拦截器」
   「没有再按停止」）。防止日后有人「顺手加功能」。

---

## 7. 交付说明（**必写**，放在最终回复里）

1. 新增/修改的文件清单（绝对路径）。
2. 探针输出（`通过 N ｜ 失败 M`）。
3. **需要集成者做的事**：`ListenerModule` 里该加的那一行构造调用
   （形如 `new XxxSub(core).start();`，并注明它在**原脚本顶层调用序列**里的位置）。
4. **跨子系统依赖**：你发现必须共享但 `ListenerCore` 里没有的东西（逐条列）。
5. **死代码清单**（是什么 / 为什么走不到 / 证据）。
6. **与原脚本的差异清单**（逐条：差异 / 理由 / 为什么不影响可观察行为）。
   没有就写「无」。
7. **不确定 / 没验证到的地方**（诚实列出）。
8. **`GS_XX_REGISTERED` 闩锁处理说明**：你保留了几个、各自语义。

---

## 8. 环境

- JDK：`C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot`（**没有 Gradle**）
- 依赖 jar：`libs\*.jar`；GraalJS：`libs\graal\*.jar`（15 个，25.1.3，**只给探针用**）
- 已编译基线：`build\classes`（含已完成的 12 个模块）+ `build\sub_core`（`ListenerCore`）
- 目标：Paper 1.21.11-132 + Slimefun4
- 测试服：`D:\qq下载\粘液科技自用\_gstest`（**装本插件，不装 RSC**）
