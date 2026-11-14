# AGENTS.md — ApkForge 维护规范

面向接手本工程的 AI / 人类维护者。**改动前必读。**

---

## 0. 三十秒了解现状

```
本地 APK dex 注入工具（Android 端）
依赖只有 smali-dexlib2 3.0.10 + JUnit
10 项单元测试基于真实 APK，可离线跑
无重签名、不处理加固包、只支持 arm64-v8a
```

### 目录树（分层有严格语义，不要随意跨层）

```
com.xixin.apkforge
├── MainActivity.kt              只挂 AppRoot
│
├── core/                        ★ 纯 JVM，禁止依赖 Android API
│   ├── axml/AxmlManifest        二进制 AXML 解析
│   ├── dex/SmaliCallParser      smali invoke 行 → 结构化
│   ├── dex/ClassInspector       探测类方法/父类
│   ├── dex/DexCallInjector      dexlib2 插指令 / 建方法
│   ├── dex/SamplePayload        示例载荷常量（调用行 + asset 路径）
│   ├── apk/ApkRepackager        zip 重打包
│   └── apk/ApkInjectPipeline    流水线编排
│
├── data/                        持久化与平台 IO（需要 Context）
│   ├── ConfigStore              JSON 配置存储
│   ├── OutputStore              MediaStore 写 Download
│   └── FilePicker               MT 优先的文件选择
│
├── feature/                     ViewModel，一功能一目录
│   ├── inject/InjectViewModel
│   └── config/ConfigViewModel
│
└── ui/
    ├── AppRoot                  侧边栏 + NavHost
    ├── design/                  ★ 设计系统，唯一视觉来源
    │   ├── ForgeTheme           色板 / 字阶 / 形状
    │   ├── Motion               弹簧 / 缓动 / 时长 / 尺寸 token
    │   ├── PressBounce          按压缩放修饰符
    │   ├── ForgeComponents      顶栏 / 按钮 / 徽标 / 卡片 / 读数 …
    │   ├── ForgeInputs          单行 / 多行输入
    │   └── ForgeSelectors       弹出面板 / chip / 文件行
    ├── anim/                    注入动画（2 版并存）+ SVG path 解析
    ├── screen/inject/           注入页
    ├── screen/config/           配置页
    ├── screen/about/            关于页
    └── theme/Theme.kt           只做 Forge → MaterialTheme 的映射
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

### 分层职责

| 层 | 允许 | 禁止 |
|---|---|---|
| `core/` | Kotlin 标准库、dexlib2、`java.util.zip` | `android.*`、Context、Log |
| `data/` | `core/`、Android IO/存储 API | 反向依赖上层、写 UI 逻辑 |
| `feature/` | `core/` `data/`、`ViewModel`、协程 | Composable、直接持有 Activity |
| `ui/design/` | Compose 基础、`Forge` 自身 | 业务类型（不得 import `core/feature`） |
| `ui/screen/` | 全部下层 + `ui/design` `ui/anim` | 手写色值/字号/弹簧字面量 |

### 测试目录镜像主代码

```
app/src/test/kotlin/com/xixin/apkforge/
├── core/apk/InjectEndToEndTest
├── core/dex/ClassInspectorTest
├── core/dex/SamplePayloadTest
└── support/TestIo              测试专用 IO 封装
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

### 1.5 产物路径必须用户可见

禁止写 `getExternalFilesDir`（Android 11+ 文件管理器看不到）。一律走 `OutputStore` → `Download/ApkForge/`。

---

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

```bash
# 构建
sh gradlew assembleDebug

# 全部测试（用本工程自己的 debug 产物当靶子）
sh gradlew :app:testDebugUnitTest

# 指定测试靶子 APK
sh gradlew :app:testDebugUnitTest -Dapkforge.testApk=/path/to/target.apk

# 只看编译警告
sh gradlew :app:compileDebugKotlin --rerun-tasks 2>&1 | grep "^w:"
```

> 注意：`gradlew` 在本机可能没有执行位，用 `sh gradlew` 而不是 `./gradlew`。

---

## 3. 测试规范

### 3.1 必须用真实 APK

`app/src/test/` 的所有断言都基于真实 APK（默认取本工程自己的 `app-debug.apk`）。不许用伪造的 smali 文本或手工构造的 dex 片段代替——真实包的形态（多 dex、Compose 大方法、R8 折叠字段）是伪造样本覆盖不到的。

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

### 5.4 新建方法必须补父类调用

生命周期方法是**覆写**语义，覆写的默认契约就是调 super。判定依据：

```kotlin
val superType = cls.superclass ?: return null
if (superType == "Landroid/app/Object;") return null
// 有同名同签名就补
```

**不许用方法名白名单**（坑 5）——白名单漏项时不报错，只是行为不对。

### 5.5 缺返回值的兜底

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
| 加固包检测 | 中 | 读 dex 时若发现类数异常少 / 字符串全是乱码，提前提示「疑似加固包」 |
| 幂等保护 | 低 | 注入前检查目标方法首条指令是否已是目标调用 |
| 重签名 | 高 | 需解决 `apksig` 的 `java.util.stream` 依赖，或调系统 `apksigner` |
| SVG 外部化 | 低 | 动画路径从 Kotlin 硬编码改到 `res/raw/*.svg` |

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
