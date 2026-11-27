# AGENTS.md — ApkForge 维护规范

面向接手本工程的 AI / 人类维护者。**改动前必读。**

---

## 0. 三十秒了解现状

```
本地 APK dex 注入工具（Android 端）
依赖只有 smali-dexlib2 3.0.10 + JUnit
单测全部基于真实 APK，**在 GitHub Actions 上跑**（本地跑会占满手机内存）
4 种注入模式、批量注入、可自造弹窗载荷（payload/）
无重签名、不处理加固包的业务逻辑、只支持 arm64-v8a
另有【去签名校验】功能：绕过目标 App 的签名校验（见 docs/去签名校验.md）
```

> ⚠️ 「dex 注入」与「去签名校验」是**并列的两条线**，不要混读文档。
> 前者改行为（插 invoke-static），后者改环境（改 android:name + 塞载荷）。

**注入性能（28.5MB / 16722 类的 release dex，手机实测）**：

```
注入 1140ms  ·  全流程 4750ms
（走 PoolInjector 增量路径；全量 DexPool 路径约 9~18 秒）
```

### 三个仓库 / 三个体系

| 位置 | 用途 |
|---|---|
| `github.com/zzgs219G/Apk-Forge` | **主仓库**。CI 跑测试 + 打包，产物从 Actions Artifact 取 |
| `cnb.cool/zzgs219/Apk-Forge` | 镜像，代码同步推送 |
| `.github/workflows/test.yml` | push 即跑单测 |
| `.github/workflows/build-apk.yml` | 手动触发打包，产出未签名 APK |


### 目录树（分层有严格语义，不要随意跨层）

```
com.xixin.apkforge
├── MainActivity.kt              只挂 AppRoot

├── core/                        ★ 纯 JVM，禁止依赖 Android API
│   ├── axml/AxmlManifest        二进制 AXML 解析（只读）
│   ├── axml/AxmlEditor          二进制 AXML 改写（只写；见 §11 的两个致命细节）
│   ├── axml/AxmlBoolean         布尔属性原位改写（extractNativeLibs；属性不存在时新增）
│   ├── dex/SmaliCallParser      smali invoke 行 → 结构化
│   ├── dex/ClassInspector       探测类方法/父类
│   ├── dex/DexOpcodes           从文件头读 dex 版本，给出解析/写出共用的 Opcodes
│   ├── dex/PackIntegrity        加固检测（只认 dex 头部的确定性证据）
│   ├── dex/InjectIdempotency    幂等保护：方法开头已是这条调用就跳过
│   ├── dex/LifecyclePoints      注入点清单 + native 降级判定（★ 纯逻辑，可单测）
│   ├── dex/InjectionMode        注入模式 + 落点规划器（★ 纯逻辑，可单测）
│   ├── dex/DexCallInjector      dexlib2 插指令 / 建方法 / 替换方法体（★ 全量路径，慢但保底）
│   ├── dex/PayloadBaker         把配置烘焙进载荷 dex（★ 纯 JVM，可单测）
│   ├── dex/SamplePayload        示例载荷常量（调用行 + asset 路径）
│   ├── dex/patch/               ★★★ 增量注入层（快路径），见 §10 专章
│   ├── apk/ApkRepackager        zip 重打包（替换 dex + 追加 payload + 保对齐 + 字节级进度）
│   ├── apk/ZipCentralDirectory  ZIP 中央目录解析（条目全元数据，数据复用要用）
│   ├── apk/ApkSigningBlock      APK Signing Block 的提取与注入（去校验要搬它）
│   ├── apk/ApkInjectPipeline    dex 注入流水线（Stage 权重 + Step 里程碑 + onTick 高频状态）
│   └── uncheck/                 ★ 去签名校验（另一条线，见 docs/去签名校验.md）
│       ├── CertExtractor        从 APK Signing Block 提取原证书 DER（纯 Java 解析）
│       ├── UncheckPayload       载荷模型 + index.json 解析 + sha256 校验 + 落点常量
│       ├── ZipReuse             数据复用：origin.apk 与整包共享物理字节
│       └── UncheckPipeline      九步主流程（读包→取载荷→改Manifest→组装→注签名块→复用→自检）

├── data/                        持久化与平台 IO（需要 Context）
│   ├── ConfigStore              JSON 配置存储（调用行 + 载荷 + 模式 + 资产）
│   ├── TaskStore                JSON 任务记录 + APK 私有副本
│   ├── OutputStore              MediaStore 写 Download（APK / zip 都走它）
│   ├── ApkMeta                  PackageManager 读应用名/图标/包名
│   ├── ApkFolderScanner         目录树递归扫 .apk（批量注入用）
│   ├── PluginPack               弹窗载荷三件套：表单 ↔ JSONC ↔ zip
│   ├── uncheck/PayloadFetcher   去校验载荷下载 + 缓存（唯一碰 Context 的地方）
│   └── FilePicker               MT 优先的文件/目录选择

├── feature/
│   ├── console/ConsoleViewModel  控制台唯一 VM：选包 → 配置 → 执行 → 记录
│   ├── config/ConfigViewModel    配置页增删改查（与执行流程解耦）
│   ├── batch/BatchViewModel      批量队列（与控制台生命周期不同，故分开）
│   ├── plugin/PluginViewModel    插件包：造载荷、导出 zip、建配置
│   └── uncheck/UncheckViewModel  去签名校验：选包 → 取载荷 → 跑流水线 → 报结果

└── ui/
    ├── AppRoot                  侧边栏 + NavHost（★ 在此持有共享 VM）
    ├── design/Palette.kt        色阶 / 字阶 / 形状 / 间距（唯一视觉来源）
    ├── lifted/                  搬运自 jian_box 的组件库
    │   ├── page/                顶栏 / 流体抽屉
    │   ├── components/          按钮 / 输入 / 选择 / 结果卡 / 徽标
    │   ├── color/               M3 色槽映射
    │   └── m3e/                 M3E 语义动画 + 波浪进度
    ├── anim/                    六边形注入动画 + SVG path 解析
    ├── screen/console/          控制台（当前任务卡 + 记录 + 贴底主操作栏）
    ├── screen/uncheck/          去签名校验页（含日志面板，一键复制）
    ├── screen/setup/            注入配置（配置 / 类名 / 注入点 / 模式 / 调用清单）
    ├── screen/run/              运行（单行实时日志 + 结果卡）
    ├── screen/batch/            批量注入（目录 → 队列）
    ├── screen/plugin/           插件包（改弹窗内容 → 导出 dex + 配置）
    ├── screen/config/           配置管理
    ├── screen/about/            关于
    ├── screen/ComingSoonScreen  未实现入口的占位（如签名）
    └── theme/Theme.kt           只做 Palette → MaterialTheme 的映射
```

### 依赖方向（单向，禁止反向）

```
ui → feature → data → core
 │
 └─→ ui/design（所有 ui 都可依赖）
```

**违反即打回**：
- `core/` 里出现 `android.*` import
- `data/` 反向依赖 `feature/` 或 `ui/`
- `feature/` 里出现 Composable
- 页面直接 new 一个 `ConfigStore`（必须走 ViewModel）
- 在子页面里写 `viewModel()` 拿共享状态（owner 是 NavBackStackEntry，会各拿一份）

### 分层职责

| 层 | 允许 | 禁止 |
|---|---|---|
| `core/` | Kotlin 标准库、dexlib2、`java.util.zip` | `android.*`、Context、Log |
| `data/` | `core/`、Android IO/存储 API | 反向依赖上层、写 UI 逻辑 |
| `feature/` | `core/` `data/`、`ViewModel`、协程 | Composable、直接持有 Activity |
| `ui/design/` | Compose 基础、色板自身 | 业务类型（不得 import `core/feature`） |
| `ui/screen/` | 全部下层 + `ui/design` `ui/anim` `ui/lifted` | 手写色值/字号/弹簧字面量 |

### 测试目录镜像主代码

```
app/src/test/kotlin/com/xixin/apkforge/
├── core/apk/InjectEndToEndTest    9 例  含替代点补 super、替换方法体、产物边界、回调完整性
├── core/apk/PipelineEndToEndTest  4 例  整条流水线、只多一个 dex、阶段序号不倒退、失败给下一步
├── core/apk/RepackArtifactTest    3 例  dex 版本头不变、STORED 条目保持对齐、Stats 如实报告
├── core/dex/ClassInspectorTest    4 例
├── core/dex/InjectIdempotencyTest 6 例  精确重复才拦、换调用行/换签名不拦、端到端跳过
├── core/dex/LifecyclePointsTest  11 例  native 降级 / 体系错配 / 进度单调 / 签名可解析
├── core/dex/InjectionModeTest     7 例  落点规划：不扩点 / 不重复 / 排除 native / 跨体系不串
├── core/dex/PackIntegrityTest     4 例  正常包不误报、加密/大小不符能识别、部分坏不拦
├── core/dex/PluginPayloadTest     9 例  调用行 ↔ 载荷 dex 契约、占位符唯一、烘焙往返、7.0 兼容
├── core/dex/SamplePayloadTest     2 例
├── core/apk/ZipCentralDirectoryTest — 被下面几个间接覆盖
├── core/apk/ApkSigningBlockTest   5 例  提取/注入/幂等/未签名包返回 null/重写会丢（反向说明）
├── core/axml/AxmlBooleanTest      7 例  false→true 原位、幂等、不存在时新增、resourceId 升序、rawValue=NO_INDEX
├── core/uncheck/UncheckPayloadTest      13 例  sha256 标准向量、索引解析、缺文件/格式错、魔数与大小校验、落点常量契约
├── core/uncheck/ZipReuseTest            10 例  体积不翻倍、origin 是完整 APK、arsc 对齐、LFH↔CD extra 一致、标准 ZipFile 交叉验证
├── core/uncheck/UncheckPipelineTest     10 例  七落点齐全、条目一条不少、只多预期条目、自检、复用体积、签名块顺序反向说明
└── support/TestIo                 测试专用 IO 封装

payload/dialog/                   弹窗载荷源码（Java，零依赖）
├── src/                          PayloadDialog.java
├── ApkForgeDialog.json           默认配置模板
├── build.sh                      javac + d8 → out/PayloadDialog.dex + plugin zip
└── out/                          构建产物（dex / json / call.txt / zip）
```


---

## 1. 铁律（违反即打回）

### 1.1 核心逻辑不得引入 Android API

`AxmlManifest` / `SmaliCallParser` / `DexCallInjector` / `ApkRepackager` / `ApkInjectPipeline` **必须**能在纯 JVM 上运行。

理由：二进制改写靠点 UI 验证是不负责任的——出错了只会看到「闪退」，不知道错在哪。当前至少 2 个真 bug 是靠 JVM 测试抓出来的。

想用 `android.util.Log`？用 `println` 或把日志换成返回值。

### 1.2 `AndroidManifest.xml` 只许原样二进制搬运

**禁止**任何形式的「解码成文本 → 修改 → 重编码」。这条路径会产出系统不认的灰包（见 `踩坑记录.md` 坑 1）。

需要改 Manifest 时做**二进制原位补丁**。

### 1.3 `dexlib2` 的 `classes` / `methods` / `fields` 必须先 `.toList()`

它们是 lazy 视图，**每次访问都新建对象**。`it === target` 恒为 `false`，改写会静默失效（坑 2）。

### 1.4 任何新依赖先问「能用平台内置的吗」

本工程刻意只留 1 个运行时依赖。加依赖前先评估：

- 体积（本工程当前约 1.2MB 依赖）
- 是否引用 Android 缺失的 API（`java.util.stream` **完全不存在**；`java.nio.file` 被裁剪）
- 是否引入注解处理器（KSP/kapt 会显著拖慢手机端构建）

### 1.5 共享 ViewModel 的 owner 必须高于共享页面

`viewModel()` 默认以当前 `NavBackStackEntry` 为 owner。控制台、注入配置、运行是**一个任务的三个阶段**，
在各自页面里 `viewModel()` 会拿到三份互不相通的状态（踩坑记录 坑 19）。

规则：共享 VM 在 `AppRoot`（NavHost 之上）取一次，按**必填参数**往下传。
子页面的 `vm` 参数不允许给默认值 —— 默认值本身就是这个 bug 的入口。

### 1.6 导航：平级切换与下钻分开

- 侧边栏平级入口 → `navigate { popUpTo(栈底) { saveState }; launchSingleTop; restoreState }`
- 控制台 → 子页 → 纯 `navigate`，返回用显式目标 `popBackStack(栈底, inclusive = false)`
- 子页里跳平级页 → 先 `popBackStack(栈底)` 再 `go(dest)`

两套机制混用不会崩溃，只会「多点几下才回不去」（坑 20），靠点测试覆盖不全。

### 1.7 注入模式与落点判定只许有一处实现

`InjectionPlanner`（`core/dex/InjectionMode.kt`）是唯一决定「注哪几个方法」的地方。

- 各模式不得在 UI 或 VM 里自己拼 `DexCallInjector.inject()` 调用序列；
- `ONCE` 必须沿用 `LifecyclePoints.resolve()` 的结论，不许为了「模式需要」重算一遍；
- 任何新模式都要在 `InjectionModeTest` 里钉住：不跨体系取点、不碰 native。

理由：分散实现的错法全都指向同一个症状——**注入「成功」但行为为零**，
日志全绿、产物正常。这类失败没有报错，只能靠单测提前拦住。

### 1.8 日志与可复制文本不许用省略号吞内容

`maxLines = 1 + Ellipsis` 只允许用在「截掉的部分是冗余」的地方（如重复的包名前缀）。
日志落点、错误尾句、产物路径、调用行**一律换行，不许截断**，并且必须有复制入口。

理由：这些内容的尾部就是唯一标识（方法名、签名、文件名）。截断后剩下的部分
既不能给人读，也不能贴给 AI 定位问题——那不是整齐，是把答案删了。

### 1.9 产物路径必须用户可见

禁止写 `getExternalFilesDir`（Android 11+ 文件管理器看不到）。一律走 `OutputStore` → `Download/ApkForge/`。

---

### 1.10 主操作只有一个，且必须显式 `align`

全 App 的**主操作只允许一处**：控制台右下角的悬浮按钮。它在任何状态下都常驻
（「选了包才出现」等于在用户最需要入口时把入口藏了），不随滚动隐藏。

位置必须写在 `Modifier.align(...)` 上，**不能省**：`Box` 的子节点默认对齐是
`TopStart`，不写就落在左上角，压住顶栏。它不崩、不报错、布局检查器也看不出异常，
只有装到手机上才看得见（`踩坑记录.md` 坑 27）。

推论：不要在别处再放一个同义按钮「补位」。两个入口同时存在，用户会认为是没听他说话。

### 1.11 注入产物只许多一个 dex，不许写宿主任何其它条目

`ApkRepackager.repack()` **没有** `extraEntries` 这类参数，`ConfigStore.Config`
**没有** `assets` 字段。这是刻意的：入口不存在，错就犯不出来。

理由不是洁癖：往宿主包里写配置文件会多出第二条通道（「资产写进去了」+「载荷按路径
读到了」），任一条没做成都是「注入成功、产物正常、宿主什么都不发生」，**不报错**。
载荷需要的配置一律烘焙进 dex（`core/dex/PayloadBaker`）。

`InjectEndToEndTest` 钉住：条目数恰好 +1、原有条目一个不少、多出来的只能是
`classesN.dex`、绝不出现 `.json` / `.jsonc`。

### 1.12 载荷的 min-api 必须用编译期验证，不能靠自觉

弹窗载荷会被塞进任意宿主（包括 minSdk 21 的老包）。`d8 --min-api` **只影响
desugar 策略**，不会挡住「调用了设备上不存在的 API」——那是运行期
`NoSuchMethodError`，正好砸在宿主启动那一瞬。

`payload/dialog/build.sh` 用 `platforms/android-21/android.jar` 再编一遍做硬门禁。
加任何平台 API 前先跑一次脚本，编不过就是不能用。

## 2. 改动流程

### 2.1 改核心逻辑

```
1. 先看 docs/流程文档.md 对应章节
2. 改代码
3. 加/改 app/src/test/ 里的断言 —— 没有测试的改动不算完成
4. sh gradlew :app:testDebugUnitTest
5. 测试跑通后再改 UI
```

### 2.2 改 UI

```
1. 先跑通构建，确认没有遗留的未使用组件引用
2. 改完 sh gradlew :app:compileDebugKotlin，零警告
3. 最后 sh gradlew :app:assembleDebug 出包
```

### 2.3 命令

> **⚠️ 不要在手机上跑全量测试。** 一次 4 分钟，263 秒里有 188 秒集中在
> `InjectEndToEndTest` / `InjectIdempotencyTest` / `RepackArtifactTest`
> 这三个类（每个用例都要拿 22MB 的 apk 做完整重打包），会把手机内存占满。
>
> **正确做法：push 到 GitHub，云端自动跑。**
>
> ```bash
> git push github main                       # 触发 test.yml
> gh run watch $(gh run list --limit 1 --json databaseId -q '.[0].databaseId') --exit-status
> gh run download <id> -n 测试报告 -D ./tr    # 拉测试报告
> ```
>
> 本机只在**改一小块逻辑**时跑对应目录，例如改增量注入跑：
> `sh gradlew :app:testDebugUnitTest --offline --tests "com.xixin.apkforge.core.dex.patch.*"`
> （约 1 分钟）

```bash
# 构建（本地快速验证用）
sh gradlew assembleDebug --offline

# 只跑增量注入相关的测试（约 1 分钟，本地可以跑）
sh gradlew :app:testDebugUnitTest --offline --tests "com.xixin.apkforge.core.dex.patch.*"

# 编译并只看警告
sh gradlew :app:compileDebugKotlin --offline 2>&1 | grep "^w:"

# 重新生成弹窗载荷 dex（改了 payload/dialog/src 之后必须跑）
# 自带两道门禁：占位符必须恰好出现 1 次、必须能用 android-21 编过
sh payload/dialog/build.sh
cp payload/dialog/out/PayloadDialog.dex app/src/main/assets/plugin/

# 云端打包（产出未签名 APK，从 Actions Artifact 取）
gh workflow run build-apk.yml --repo zzgs219G/Apk-Forge \
  -f build_type="全部(both)" -f version_code="0.5.0" -f run_tests="跳过测试"
gh run download <id> -D dist
```

> 注意：`gradlew` 在本机可能没有执行位，用 `sh gradlew` 而不是 `./gradlew`。

### 2.4 改完增量注入层怎么验（必看）

改 `core/dex/patch/` 下任何文件后，**结构校验通过不算修好**。
必须走完三层，`AGENTS.md` §10.7 有完整说明：

1. `dex2oat64 --compiler-filter=verify` → `rc=0`（只查结构，不执行代码）
2. jadx 逐类 diff：除目标方法外应逐字一致
3. 装机运行：签名 → 安装 → 启动 → 进程存活

---

## 3. 测试规范

### 3.1 必须用真实 APK

`app/src/test/` 的所有断言都基于真实 APK（默认取本工程自己的 `app-debug.apk`）。不许用伪造的 smali 文本或手工构造的 dex 片段代替——真实包的形态（多 dex、Compose 大方法、R8 折叠字段）是伪造样本覆盖不到的。

> **2026-11-27 更正**：本文原先在别处写过「不启用 R8，因为注入测试需要真实的类名与代码形态」。
> 那句话是错的，已删除。**R8 混淆后的包才是线上 App 的真实形态** ——
> 类名被改成 `l.lۢܺ`、字段被折叠、方法被内联，这些恰恰是注入/去校验
> 真正要面对的形态。用未混淆的 debug 包当靶子才是理想化样本。
>
> release 已按 `jian_box` 的做法开启 R8 + 自定义混淆字典（见 `app/build.gradle.kts`
> 的 `release` 块与 `app/proguard-rules.pro`）。实测产物 14.4MB → 2.1MB。
>
> ⚠️ **但本地 Termux 打不出可用的 release 包**：`optimizeReleaseResources`
> 只写 metadata、不产出 `.ap_`，结果是包里**没有 `AndroidManifest.xml`
> 和 `resources.arsc`**（见 §8 已知待办）。拿它当测试靶子会得到一串
> 「APK 里没有 AndroidManifest.xml」的失败 —— 那不是 R8 的问题，
> 是本地环境缺陷。CI 上正常。

### 3.2 断言只钉契约，不钉实现细节

反面例子（坑 13）：

```kotlin
// ❌ AGP 把 MainActivity 分到哪个 dex 是它的自由
assertEquals("classes.dex", r.dexName)
// ✅
assertTrue(!r.dexName.isNullOrBlank())
```

### 3.3 每个断言要能说清「防的是什么」

测试方法名和注释里写清它对应哪个坑。例如：

```kotlin
// 防坑 2：dexlib2 lazy 集合的身份比较
assertTrue("onCreate 首条指令应为 invoke-static", ...)
```

### 3.4 找不到靶子要 skip，不要伪绿

```kotlin
assumeTrue("APK 内没有带 onCreate(Bundle) 的类", target != null)
```

不写 `if (target == null) return`——那会让测试显示 PASS 但什么都没验证。

---

## 4. 代码风格

### 4.1 注释写「为什么」，不写「是什么」

```kotlin
// ❌
// 把 method 替换进 class
// ✅
// dexlib2 的 methods 每次访问新建对象，所以必须先物化再替换，
// 用 === 比较永远 false
```

### 4.2 注释里避开 `*/`

描述通配符 + 斜杠时写成 `` `invoke-` + `/range` ``，别写 `invoke-*/range`（坑 6）。

### 4.3 用户可见文案「跟随用户提问语言」

本工程 UI 文案是中文。技术术语保留英文原文（`onCreate`、`dex`、`invoke-static`）。

### 4.4 错误信息要给「下一步」

```kotlin
// ❌
return Result(false, "注入失败")
// ✅
return Result(false, "所有 dex 里都没有类 $resolved。请确认类名完整（含包名）")
```

---

## 5. 注入相关的硬约束（写代码前必看）

### 5.1 寄存器宽度

| 指令 | 格式 | 寄存器位数 | 上限 |
|---|---|---|---|
| `const-string` | 21c | 8 bit | v255 |
| `invoke-*` | 35c | **4 bit** | **v15** |
| `invoke-*/range` | 3rc | 16 bit | v65535 |

超 v15 或超 5 个寄存器 → 自动切 `/range`；不连续 → **报错**，不许产出非法 dex。

### 5.2 p/v 换算

```
paramRegCount = 参数个数 + 1（实例方法含 this）
p_i = registerCount - paramRegCount + i
v_i = i（须 < registerCount）
```

### 5.3 只许引用 p 族寄存器

注入点在方法**第一条指令之前**，此时 v 族尚未赋值。用户写了 v 寄存器只给 warning，不阻止（有人可能故意这么干），但必须报出来。

### 5.4 native 方法不可插，要降级而不是报错

`onCreate` 是 native（加固壳 / `NativeActivity`）时，`DexCallInjector` 只能返回 `Skipped`。
「Skipped」不该是终点，而应触发重规划：`LifecyclePoints.resolve()` 按
`CREATE_FALLBACKS`（onPostCreate → onStart → onResume → attachBaseContext）换点。

三条约束（`LifecyclePointsTest` 钉住）：
1. 只有 `Status.NATIVE` 才降级，`MISSING` 不降级（没有就新建）；
2. 替代点必须来自**同一个类**的可用清单（`Application` 不能推荐 `onPostCreate`）；
3. 降级原因必须回传到 UI —— 悄悄换掉方法比报错更危险。

### 5.5 新建方法必须补父类调用

生命周期方法是**覆写**语义，覆写的默认契约就是调 super。判定依据：

```kotlin
val superType = cls.superclass ?: return null
if (superType == "Landroid/app/Object;") return null
// 有同名同签名就补
```

**不许用方法名白名单**（坑 5）——白名单漏项时不报错，只是行为不对。

### 5.6 缺返回值的兜底

新建方法非 void 返回时要给 `RETURN` / `RETURN_WIDE` / `RETURN_OBJECT`，否则验证器拒绝。

---

## 6. 文档维护

改动涉及以下内容时，**同批更新对应文档**，不许「先改代码后补文档」：

| 改了什么 | 更新 |
|---|---|
| 流水线步骤、数据流 | `docs/流程文档.md` |
| 模块职责、设计取舍 | `docs/项目介绍.md` |
| 修了 bug | `docs/踩坑记录.md`（新增一条，含教训） |
| 功能增删 | `docs/变更历史.md` |
| 载荷字段 / 烘焙机制 | `docs/流程文档.md`（Step 7.5）+ `docs/项目介绍.md` §4.6 |
| 依赖、环境、限制 | `README.md` |

踩坑记录的**教训部分是可复用的资产**，比修法更重要。写的时候问自己：下次遇到什么形态的问题，这条能救命？

---

## 7. 提交规范

```
<type>(<scope>): <subject>

body（可选，说清为什么这么改）
```

type：`feat` / `fix` / `docs` / `refactor` / `chore` / `test`

scope 用模块名：`inject` / `dex` / `axml` / `ui` / `build` / `docs`

例：

```
fix(dex): 注入静默失效——dexlib2 lazy 集合身份比较恒 false

DexBackedClassDef.methods 每次访问新建对象，`it === targetMethod` 永远
false，导致改写后的方法没被替换进类。改为按「名字 + 签名」匹配。
```

---

## 8. 已知待办（按优先级）

| 项 | 难度 | 说明 |
|---|---|---|
| 重签名 | 高 | 需解决 `apksig` 的 `java.util.stream` 依赖，或调系统 `apksigner`。**当前产物是完全未签名的**，任何设备都装不上 |
| 加固包**脱壳** | 很高 | 现在的 `PackIntegrity` 只识别不脱壳（识别是为了把「找不到类」的误导换成真实原因），脱壳是另一个量级的工程 |
| 重打包压缩改等级 1 | 低 | `classes2.dex` 12.5MB 压缩约 2 秒（占全流程 40%）。改成 `Deflater.BEST_SPEED` 能快一半，代价是包体大 5% 左右 |
| 本地打包 release 缺资源 | 中 | Termux 上 `optimizeReleaseResources` 只写 metadata、不产出 `.ap_`，导致 `assembleRelease` 打出**没有 manifest 和 arsc 的灰包**。CI 上正常。本地需手工把 `linked-resources-binary-format-release.ap_` 补到 `optimized_processed_res/release/optimizeReleaseResources/resources-release-optimize.ap_` 才能出完整包 |
| 7 项测试在 CI 跳过 | 低 | `MetadataIntegrityTest` / `MethodBodyIntegrityTest` / `DexStructuralIntegrityTest` / `MapOffsetValidityTest` / `Format3rcLayoutTest` / `InstructionStartSemanticsTest` / `TryBlockRemapperTest` 依赖 `o.apk`/`i.apk` 这对手工比对样本，CI 上没有。它们是人工排查工具，跳过属预期 |
| SVG 外部化 | 低 | 动画路径从 Kotlin 硬编码改到 `res/raw/*.svg` |

已完成（曾经列在这里）：

| 项 | 做法 | 为什么当时的判据被推翻了 |
|---|---|---|
| 加固包检测 | `core/dex/PackIntegrity` | 原计划的判据「类数异常少 / 字符串全是乱码」**两条都会误报**：实测正常包里存在只有 0~13 个类的 dex（多 dex 切分产物），也存在 `assets/` 下带 2MB 广告 jar 的正常商业包。改用 dex 头部的确定性证据（魔数 / `file_size` / `header_size` / `endian_tag` / 结构与声明自相矛盾），无可调阈值 |
| 幂等保护 | `core/dex/InjectIdempotency` | 判据是**精确重复**（owner + 方法名 + 参数 + 返回值全一致且落在方法开头），不是「载荷类被调用过」——后者会误伤「同一个类要插两个方法」 |

---

## 9. 不要做的事

| ❌ | 为什么 |
|---|---|
| 引入 Room / KSP | 几行 JSON 能解决的事，注解处理器拖慢手机端构建一倍 |
| 用 `apktool` 那套文本往返 | 手机端慢 10~50 倍，且不保真（debug_info 膨胀、dex 版本头被写回） |
| 解码 Manifest 成文本 | 产出灰包 |
| 把核心逻辑改成依赖 Android API | 失去 JVM 测试能力，等于闭眼改二进制 |
| 用伪造 smali 样本代替真实 APK 测试 | 覆盖不到多 dex / Compose 大方法 / R8 折叠等真实形态 |
| 抄 `jian_box` 的自定义抽屉手势 | 那是为绕 M3 旧版 bug 写的，本工程 Compose 1.12 已修 |
| 在白名单里枚举生命周期方法 | 漏项时静默失效 |
| 在子页面 `viewModel()` 拿共享状态 | owner 是 NavBackStackEntry，三份互不相通的 VM |
| 往宿主包里写配置文件（assets 等） | 多一条会断的通道，且越界；载荷配置一律烘焙进 dex |
| 在 `Box` 里省掉 `align` | 默认落左上角，按钮会压到顶栏上，只在真机上看得出来 |
| 把载荷的配置占位符写成 `static final String` | javac 内联成多份，烘焙时无法定位 → 注入成功但不弹窗 |
| 用 `String(dex, ISO_8859_1).contains(...)` 检查 dex 里的文本 | 字符串池是 MUTF-8，中文永远匹配不上，成功会被误判成失败 |
| 把 native 方法不可插当成错误上报 | 用户只知道失败，不知道下一步换哪个点 |
| 给进度条加「加速/补齐」 | 它就变成装饰品，而用户靠它判断卡住没有 |

---

## 10. 增量注入层（`core/dex/patch/`）—— 后续维护的核心地带

这一层是为了把「注入 18 秒」压到「注入 1 秒」而写的。它绕开了 dexlib2 的
`DexPool`（会重排整个池、重编码所有指令），代价是**必须自己保证 dex 布局合法**。

### 10.1 为什么不能靠 `DexPool`

`DexPool.writeTo` 把 8.5 万个字符串全部重新 interning、6 万个方法重新序列化。
实测 28.5MB 的 dex 即使**什么都不改**也要 9~18 秒。

而实际只需要新增 2~5 个池条目。所以增量路径换了个思路：

```
数据段（指令 / 注解 / 调试信息）→ 不做重编码，只搬字节
索引段（6 张 id 表 + class_defs）→ 重建、重排、重映射
```

### 10.2 文件职责

| 文件 | 职责 | 改动风险 |
|---|---|---|
| `FastInjector` | 门面：先试增量，不适用回退全量 | 低 |
| `PoolInjector` | **核心**。池重排 + 段收集 + 排版 + 回填，900+ 行 | **极高** |
| `DexSectionLayout` | 按 ART 规范段序拼接整份 dex | 高 |
| `InstructionRefRemapper` | 指令里的池索引重映射 | 高 |
| `AnnotationRemapper` | 注解四种结构的递归重写 | 中 |
| `EncodedValueRemapper` | encoded_value 递归重映射（注解/静态值共用） | 中 |
| `TryBlockRemapper` | try/catch 的异常类型重映射 | 中 |
| `ClassDataRemapper` | class_data 重编码（uleb128 差分） | 中 |
| `DebugInfoRemapper` | 调试信息状态机重写 | 中 |
| `TypeListRemapper` | proto 参数表重映射 | 低 |
| `DexPatcher` | 旧的「末尾追加」写法，**已被 PoolInjector 取代**，仅保留 `codeHeader` 等查询工具 | 低 |
| `ByteSink` / `GrowableBuffer` / `DexBuffer` | 字节读写基础设施 | 低 |

### 10.3 ART 的四条布局约束（写任何段代码前必读）

来自 `libdexfile/dex/dex_file_verifier.cc`。**违反任何一条都是装机即崩**：

1. **map 项按 `offset` 严格递增**（不是按 type）
   → 否则 `Out of order map item: 3456c then 12c18`
2. **物理布局必须与 map 顺序一致**，校验器用全局游标顺序推进
   → 否则 `Section overlap or out-of-order map`
3. **段与段之间只允许全零填充**
   → 否则 `Non-zero padding 5e before section of type 8193`
4. **同一 type 的所有 item 必须连续**（map 只记「起点 + 个数」）
   → 否则 `List too large for insns size`

外加一条容易忽略的：**`class_defs` 必须满足「父类/接口先于实现类」的偏序**，
否则 `Invalid class definition ordering`。

### 10.4 「段内偏移 + 1」哨兵（本层最容易踩的坑）

dex 里大量字段用 `0` 表示「没有」，但**段内偏移 `0` 是合法的第一项**。
两者冲突时，回填逻辑会 `if (v != 0)` 跳过，第一项永远填不进去 → 产生孤儿项。

已统一改为「值 + 1」哨兵，`0` 仍表示没有。涉及：
`class_data_off`、`annotations_off`、`interfaces_off`、`static_values_off`、
`proto.parameters_off`、`code_item.debug_info_off`、注解链路的四种结构。

**新增任何「可能为 0 的偏移字段」时，必须走同一套哨兵约定。**

### 10.5 对齐规则（别一律 `align(4)`）

| 段 | 对齐 |
|---|---|
| `string_data` / `debug_info` / `annotation_item` / `encoded_array` / `class_data` | **1 字节** |
| 其余（`type_list` / `code_item` / `annotation_set` / `annotation_dir` 等） | 4 字节 |

给 1 字节对齐的段加 4 对齐会在项之间插填充，而 ART **紧密遍历整段** →
后续每项起点全偏，报 `Bad index for debug_info_item parameter_name`。
那个报错里的两个数字跟真实数据毫无关系，极难反推。

### 10.6 性能关键点（改动时勿退化）

1. **只记录真正变化的索引**
   28.5MB dex 有约 99 万条带池引用的指令，池重排后**只有 8.5% 的索引变了**。
   为剩下 91.5% 建 `Patch` 对象要花 1.5 秒。`collect` 里用 `maps` 跳过未变的。
2. **直接从指令字节读池索引，不要拼字符串查表**
   `ref.parameterTypes.map { it.toString() }` 每条约分配 5 个临时对象。
3. **`Format31c`（`const-string/jumbo`）的索引是 u32，不是 u16**
   只读 2 字节会让所有 > 65535 的字符串常量指错。**这条 dex 校验查不出来，只有真机崩。**
4. **避免大段复制**
   各段用 `raw()` + 长度写入，而不是 `toByteArray()` 再写。

### 10.7 验证必须三层，缺一层就会漏 bug

```
① 结构层  dex2oat64 --compiler-filter=verify → rc=0
② 内容层  jadx 反编译原包与产物，逐类 diff（必须 sed 去掉 "loaded from" 注释）
③ 运行层  签名 → 安装 → 启动 → 进程存活
```

**① 只查结构，一行代码都不执行。** 实测有 4 类 bug 它能放行：
`invoke` 寄存器个数编码错、`insns_size` 少算、jumbo 索引截断、类定义偏序违规。

② 的做法（`cat 从产物里 diff` 也行，但 jadx 更可读）：

```bash
jadx -d out_orig --no-res --no-imports -m fallback 原包.apk
jadx -d out_inj  --no-res --no-imports -m fallback 产物.apk
diff <(sed 's|loaded from.*||' out_orig/X.java) <(sed 's|loaded from.*||' out_inj/X.java)
```

**判据：除目标方法外，所有类应逐字一致。** 有差异就是内容错了。

### 10.8 云端验证（不要在手机上跑全量测试）

本地跑一次全量单测 4 分钟、占用大量内存。**push 到 GitHub 即自动跑**：

```bash
git push github main          # 触发 .github/workflows/test.yml
gh run watch $(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run download <id> -n 测试报告 -D ./tr
```

测试靶子就是**本 App 自己的产物**（`assembleRelease` 出的 release 包，
`classes.dex` 29.9MB / 16722 类 / dex 039 / R8 混淆形态）。CI 会先构建靶子再测试。

打包用 `build-apk.yml`（手动触发）：

```bash
gh workflow run build-apk.yml --repo zzgs219G/Apk-Forge \
  -f build_type="全部(both)" -f version_code="0.5.0" -f run_tests="跳过测试"
gh run download <id> -D dist
```

---

## 11. 去签名校验模块（`core/uncheck/` + `core/axml/AxmlEditor`）

> 完整背景见 **`docs/去签名校验.md`**。本节只列「改之前必须知道」的硬约束。

### 11.1 AXML 改写的两条红线（改错了零报错，但完全不生效）

| 红线 | 为什么 | 怎么验 |
|---|---|---|
| **属性必须按 resourceId 升序插入，不能追加末尾** | `PackageParser` 按 framework 资源 ID 升序读 `<application>`，遇乱序**提前停止扫描**。`android:name` 的 ID 是 `0x01010003`（最小）→ 必须插最前 | 与 MT 生成的包逐字节 diff 属性数组 |
| **`rawValue` 必须是字符串池索引，不是 `NO_INDEX`** | `raw` 与 `typedValue.data` 都要填索引且相同；写 `NO_INDEX` 时值和属性名都读不出 | androguard 交叉验证 + `dumpsys package` 看 className |

**症状统一**：系统读不到 `android:name`、App 照旧跑原类、**日志里一条我们的都没有**。

**只验「自己的读函数能读出自己写的结果」不够** —— 那是自洽检查，两边错得一样时测不出来。
必须有：① 独立实现（androguard）交叉验证 ② 装机后 `dumpsys package` 看 `className`。

### 11.2 载荷必须分两个 dex，不能合并

```
boot.dex      SigFix + SigFixApp  → 进目标 App 的 classes{N+1}.dex
payload.dex   SigFixImpl           → 进 assets，运行时 new DexFile(path) 加载
```

`SigFixImpl` 必须由 **BootClassLoader** 加载才能反射访问 hidden API。
编进 App 自己的 dex → 应用域 → 反射被拦（实测 Android 17：
`setHiddenApiExemptions` 直接 blocked）。**合并两个 dex 会让整个方案失效。**

### 11.3 载荷类里禁止读静态字段

`SigFixImpl$IpmHandler` / `$PackageInfoCreator` 会被 `handlerLoader`
重新加载成**另一个 Class 副本**，静态字段在副本之间不共享。

实测日志：`sFake=false sTargetPkg=null` —— 所有改写被 null 检查挡掉，
表现为「代理被调用 29 次，改写 0 次」。

**规则：handler 需要的数据一律经构造器传入。**

### 11.4 载荷要「下载」不是「内置」，且必须校验 sha256

载荷会被**注入到别人的 App 里执行**。跳 sha256 校验 = 信任整条网络链路。
`PayloadFetcher` 里校验不过直接丢弃，不许「先用着再说」。

**四个文件缺一不可**（`UncheckPayload.REQUIRED` 把它们都设为必需）：

```
boot.dex            → 目标包 classes{N+1}.dex（SigFix + SigFixApp）
payload.dex         → assets/nc_sigfix/payload.dex（SigFixImpl）
libusign_arm64.so   → lib/arm64-v8a/libusign.so
libusign_armv7.so   → lib/armeabi-v7a/libusign.so
```

少 `.so` 只剩 Java 层，而 Java 层对「App 自读 APK / native 读签名块」
完全无效 —— 现代 App 普遍至少做其中一个。所以缺 `.so` 直接报错，
不产出「装上去才发现没效果」的索引。

### 11.5 数据复用的三条红线（违反即「装不上」或「体积翻倍」）

`core/uncheck/ZipReuse` 手写 ZIP 让 `origin.apk` 与整包共享物理字节。
它绕开了 `java.util.zip`，代价是必须自己保证布局合法：

| 红线 | 违反后果 |
|---|---|
| origin 的 local header 必须补到 **4096** | 平移量不是 4 的倍数 → `resources.arsc` 失去对齐 → **Android R+ 装机直接拒** |
| LFH 与**中央目录**的 extra 长度必须一致 | 读取方从 CD 拿 `extraLen=0` → 实际平移量恢复原值 → 等于没对齐（且静默） |
| 产物之后**绝不能**再跑 apksigner / zipalign | 它们重写 ZIP，把重叠条目展开成两份 → 体积从 1× 变 2× |

**签名提示必须写进 UI**：产物只能用「手写 ZIP」的工具签（如 MT 管理器）。
这不是可选建议 —— apksigner 一跑，复用就白做了。

### 11.6 签名块必须在「中间包之后、数据复用之前」注入

```
rebuild 中间包 → 注入签名块 → 数据复用 → 输出
                      ↑ 必须在①②之间
```

- 在 rebuild **之前**注入 → `ZipOutputStream` 重写整个 ZIP，签名块不是条目，
  必被丢弃。
- 在复用**之后**注入 → `origin.apk` 的数据区已经固定，改不到了。

云端 2026-11-24 就是漏了这一步：加固包的 `.ncx` 段解密出乱码 → 跳野地址
→ **SIGBUS**。`UncheckPipeline` 的自检直接查「原包有签名块时 origin.apk
里也必须带」，`UncheckPipelineTest` 的「顺序错时签名块会丢」再钉一遍。

### 11.7 与 dex 注入的关系

**不共用 `DexCallInjector`**。去校验只改 Manifest，不插 `invoke-static`。
两条线唯一的共同点是「都产出未签名 APK」。

### 11.8 `core/uncheck/` 必须能跑 JVM 单测

这个模块的失败形态**全是「不报错」**：

```
Manifest 改错          → 系统读不到 android:name → App 照旧跑原类
签名块没搬             → native 层读不到证书 → 静默只剩 Java 层
extractNativeLibs 没改 → .so 不解压 → System.load 失败但不崩
数据复用对齐错         → 装机被拒（这个还算好的，至少有报错）
```

所以 `core/uncheck/` 不许出现 `android.*`。下载/缓存这类必须碰 Context 的
逻辑一律放 `data/uncheck/PayloadFetcher`。

### 11.9 每一步都必须兜住异常，不许往上抛

目标 App 千奇百怪，我们的假设随时会错。

```
MT 的实现在简盒 v2.1.2 上：
  IllegalAccessException（改 static final 字段被拒）
  → 抛 RuntimeException
  → ExceptionInInitializerError
  → App 在 makeApplicationInner 阶段就崩
我们的实现同一条异常：
  → 记日志 → 代理层继续兜 → App 正常启动
```

**崩了等于这个模块不可用**（用户装一个坏包到自己手机上）。
`SigFixApp` 里尤其严禁抛异常 —— 它在 Application 初始化路径上，抛了必崩。

### 11.10 界面禁用必须给可见原因

`UncheckUiState.blockers` 是唯一判据来源，UI 照它说事，**不要在各处重复判断**
（两处必然漂移）。
