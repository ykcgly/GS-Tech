# 探针（机械对账）使用说明

这些探针是**迁移期的机械证据** —— 它们不是「跑一遍看看」，
而是把 Java 模块的行为与**真 GraalJS 求值出来的原脚本**逐值比对，
两侧算同一个输入、断言同一个输出。

> ★ 成品 jar **不依赖** GraalJS；但**探针**依赖 —— 它们要在真 JS 引擎里
> 跑原脚本当参照物。阶段②已把 `libs/graal/`（15 个 jar / 70.8 MB）从工程里删掉，
> 因为那是**打包进 jar** 用的。探针要重跑时，从下面任一处取 GraalJS 运行库即可。

## 一、GraalJS 运行库从哪来

阶段②删掉的是**工程内**的 `libs/graal/`。同一套 15 个 jar 仍然在：

```
<服务器根目录>/plugins/RykenSlimefunCustomizer/lib/org/graalvm/**
```

（RSC 自己也要用 GraalJS，所以它随插件带着这一套。版本同样是 **25.1.3**。）

实测该目录共 **15 个 jar / 70.8 MB**，与已删的 `libs/graal/` 完全一致。

> ⚠️ 如果哪天连 RSC 也卸了，就用 `tools/FloatInteropProbe.java` 里记录的那套
> 坐标从 Maven Central 重新拉：`org.graalvm.polyglot:polyglot:25.1.3`、
> `org.graalvm.js:js-language:25.1.3`、`org.graalvm.truffle:truffle-runtime:25.1.3` 等。

## 二、编译并运行一个探针

以 `ShadowParityProbe` 为例（它在 `com.qinghuacisj.gs.script.module` 包下，
所以**必须用全限定名**运行）：

```powershell
$JH  = 'C:\Program Files\Microsoft\jdk-21.0.7.6-hotspot'
$RSC = 'D:\qq下载\粘液科技自用\server\plugins\RykenSlimefunCustomizer\lib'

# 1) 类路径 = 本工程的 libs + RSC 的 GraalJS + 编译产物 (+ JOML，见下)
$cp  = @()
$cp += (Get-ChildItem libs -Filter *.jar | % FullName)
$cp += (Get-ChildItem $RSC -Recurse -Filter *.jar | % FullName)
$cp += (Get-Item 'build\classes').FullName
$cpStr = $cp -join ';'

# 2) 编译
New-Item -ItemType Directory -Path build\probechk -Force | Out-Null
& cmd /c "`"$JH\bin\javac.exe`" -encoding UTF-8 --release 21 -nowarn -proc:none -cp `"$cpStr`" -d build\probechk tools\ShadowParityProbe.java"

# 3) 运行（全限定名；加 -Dstdout.encoding=UTF-8 免得中文乱码）
& cmd /c "`"$JH\bin\java.exe`" -Dstdout.encoding=UTF-8 -Dpolyglot.engine.WarnInterpreterOnly=false -cp `"$cpStr;build\probechk`" com.qinghuacisj.gs.script.module.ShadowParityProbe"
```

**★ 需要 JOML 的探针**（凡是把 `org.bukkit.util.Vector` 暴露给 GraalJS 的）：
把 `D:\qq下载\粘液科技自用\server\libraries\org\joml\joml\1.10.8\joml-1.10.8.jar`
加进 `$cp`。已确认需要的有 `ListenerCSubParityProbe`。

## 三、踩过的坑（都真踩过，别再踩）

| 坑 | 现象 | 正解 |
| --- | --- | --- |
| `java`/`javac` 直接调 | `ClassNotFoundException: /encoding=UTF-8` | **一律走 `cmd /c "..."`** |
| javac `@argfile` 用绝对中文路径 | `错误: 无效文件名: D:\qq??\...` | argfile 里写 **cwd 相对路径** |
| 探针在别的包下 | `ClassNotFoundException` | 用**全限定名**运行 |
| 输出中文乱码 | `??????` | 加 `-Dstdout.encoding=UTF-8` |
| 用 `Select-Object -First N` 截 javac 输出 | `exit=-1`（SIGPIPE 假失败） | 重定向到文件再看 |
| `getOnlinePlayers()` 返回 JS 数组 | 脚本 `.iterator()` 抛异常被吞 ⇒ 看起来「什么都没发生」 | 必须返回 **Java 集合** |
| 无服 JVM 里 `Material` / `PotionEffectType` 静态初始化抛 `ExceptionInInitializerError` | GraalJS 的 `try/catch` **抓不到** | 先 `Class.forName(..., false, cl)` 只加载，注入假 `RegistryAccessHolder.INSTANCE` 再放 `<clinit>` 跑 |
| 只 eval 脚本前缀 | 后文定义的函数 `ReferenceError` 被吞 ⇒ 假「0 命中」 | eval **整个** `gs_listener.js` |
| 前一段的 `GS.runLater` 任务漏到下一段 | 串味 | 段与段之间**清空任务队列** |

## 四、探针清单与已知结果

★ **运行全限定名要注意包不一样**（这个坑踩过，白跑一轮）：
- `Listener*SubParityProbe` 在 `com.qinghuacisj.gs.script.module.listener` 下
- `ShadowParityProbe` 等 boss 探针在 `com.qinghuacisj.gs.script.module` 下
- **`ListenerDSubParityProbe` 在默认包**（源码没有 `package` 行）⇒ 直接写类名

| 探针 | 结果 |
| --- | --- |
| `JsNumParityProbe` | 43 / 43（另含 120,000 组随机对拍） |
| `JsNumConsolidationProbe` | 1200 / 1200（13 份私有副本 × 80 个值，全一致） |
| `ShadowParityProbe` | 112 / 112 |
| `ThreeParityProbe` | 117 / 117 |
| `PhantomParityProbe` | 174 / 174 |
| `RiderParityProbe` | 236 / 236 |
| `NightwatchParityProbe` | 121 / 121 |
| `ListenerASubParityProbe` | 176 / 176 |
| `ListenerBSubParityProbe` | 180 / 180 |
| `ListenerCSubParityProbe` | 249 / 249（需 JOML） |
| `ListenerDSubParityProbe` | 201 / 201（**默认包**） |
| `ListenerESubParityProbe` | 392 / 392 |
| `ListenerFSubParityProbe` | 247 / 247 |
| `ListenerGSubParityProbe` | **432 / 435**（3 项已知失败，见下） |
| `FloatInteropProbe` | 7 / 7 |
| `CursedParityProbe` | 25 / 25 |
| `SongDataParityProbe` | 24 / 24 |

### ★ ListenerGSubParityProbe 的 3 项已知失败（**不是回归**）

`C76` / `C115` / `C116` 三项长期失败，已用「切回改动前的源码重新构建再跑同一个探针」验证过：

| 源码状态 | 结果 |
| --- | --- |
| 改动前（`build\src_backup`） | 431 / 4 —— 失败项 `C48` `C76` `C115` `C116` |
| 改动后（当前） | 432 / 3 —— 失败项 `C76` `C115` `C116` |

⇒ 这 3 项是**探针骨架在无服务器环境下的限制**（`PlayerInteractEvent` 空 block 默认取消、
`ProbeSfStack` 假 PDC 读不回 `gs:al_shooting` 标记），不是移植错误：
同一批的 **`C114`（JS↔Java 逐字节对拍）在 `shooting=true/false` 两种情况下都通过**，
说明 Java 侧与 JS 侧行为一致。
另外 `C48`（799ms 节流）是**时间敏感**的，会随机器负载漂移。

### ★ 必须在真服务器里跑的检查：`tools/lorecheck/`

组图标 lore 的回归检查（`更新日志的 lore 全没了` 那个 bug）**没法做成普通探针** ——
`new ItemStack(Material.PAPER)` 在无服务器 JVM 里造不出来（Paper 的 `CraftItemStack`
需要 NMS，`craftDelegate` 是 `null`）。详见 `tools/lorecheck/README.md`。

## 五、两个脚本级对账工具（纯 Python，不需要 GraalJS）

```bash
# 整服启动日志 vs 参考服日志（双向对账）
python tools/log_parity.py <本次 latest.log> [<参考服日志>]
#   → 期望「通过 170 ｜ 失败 0」

# 校验 gs_listener_拆分契约.md 里抄的每一行日志都对得上参考服
python tools/check_split_contract_lines.py
#   → 期望 45 / 45

# 扫全部 18 个脚本里的 float 互操作调用点（统计哪些原本是失效的）
python tools/audit_float_interop.py
```

`log_parity.py` 的 `EXTRA_OK` / `MISSING_OK` 两个名单里的条目**必须写理由**，
「懒得查」不是理由。★ 名单匹配同时认「带 `[构思技艺] ` 前缀的整行」和「去掉前缀的正文」——
早期只认前者，导致只写正文的条目是**死条目**（看着写了、实际从不生效），已修。
