# ApkForge

本地 APK dex 注入工具（Android 端，无需电脑）。

选一个 APK，把 smali 调用插进它的生命周期方法，附带的 payload dex 一起打进包，产物写到 `Download/ApkForge/`。
支持单包注入、目录批量注入，以及自己捏一份弹窗载荷（改文案 → 导出 dex + 配置）。

---

## 它做什么

```
选 APK ──▶ 解析 Manifest 的 LAUNCHER ──▶ 定位目标类所在 dex
                                            │
                                            ▼
                              在生命周期方法的**第一条指令之前**
                              插入你粘的 invoke 行（注入点默认自动）
                                            │
                                            ▼
                        方法不存在？→ 新建（父类调用 + 注入 + return）
                                            │
                                            ▼
                       payload dex 作为 classesN.dex 追加 ──▶ 重打包（逐条目上报进度）
                                            │
                                            ▼
                              Download/ApkForge/xxx_injected.apk
                                            │
                                            ▼
                              任务记录：包 + 配置 + 注入点 + 产物
```

**不做**的事情：不重签名、不解码 Manifest 成文本、不处理加固包。

**native 的 onCreate** 会挡路（加固壳 / `NativeActivity` 常见）。这时自动按
`onPostCreate` → `onStart` → `onResume` → `attachBaseContext` 的顺序换点，并把原因写在界面上。

### 四种注入模式

| 模式 | 载体 | 用于 |
|---|---|---|
| 单次注入 | 启动切入点 | 弹一次公告、初始化钩子 |
| 每次进入 | `onStart` + `onResume` | 每次回到页面都要检查（更新提示、校验） |
| 多点全覆盖 | 所有可插的生命周期点 | 调试打点，要看完整时序 |
| 替换方法体 | 单点，丢弃原方法体 | 打桩/劫持（风险最高，UI 会警告） |

模式与落点的判定收在 `InjectionPlanner`（纯 JVM），
不会出现「注入成功但行为为零」那种无声失败。

---

## 快速开始

1. 装 `app-debug.apk`
2. 控制台 → **选 APK 开始**（自动读出应用名 / 图标 / 包名 / LAUNCHER Activity）
3. 选一套配置；没有就点「新建」，或先点「先填一套示例跑通流程」
4. 确认注入点（默认「自动」，需要接管时点「指定」）→ 右下角悬浮按钮 **开始注入**
5. 跑完看结果卡：改了什么、产物在哪、安装 / MT 定位 / 分享

任务会留一条记录在控制台：下次想换套配置再注入，点记录就行，不用重新找包。

**批量注入**：右上角菜单 → 批量注入 → 选一个目录（含子目录，最多 50 个）→ 挑一套配置 → 开始。
串行跑，每项带自己的状态与失败原因。

**插件包**：侧边栏 → 插件包 → 调弹窗（标题/正文/三个按钮/风格/智能弹出/静默期/强制性）→ 导出。
产出 `Download/ApkForge/apkforge-plugin.zip`（**dex + 调用行**，配置已烘焙进 dex），并顺手建一条配置。

> 产物里只会多出 `classesN.dex` 一个条目。不会往宿主的 `assets/` 写任何文件——
> 注入一个包只该让它多一个 dex，别的不该动。

### 自己构建弹窗载荷

```bash
export ANDROID_HOME=/path/to/android-sdk      # 需要 build-tools（d8）与 platforms;android-37.0
sh payload/dialog/build.sh
# 产出 payload/dialog/out/{PayloadDialog.dex, call.txt, apkforge-plugin.zip}
```

载荷只依赖 `android.app.AlertDialog` / `org.json`，**没有网络代码**。
配置**烘焙在 dex 里**（占位符 `__APKFORGE_CONFIG__` 由 `PayloadBaker` 替换），
所以改文案要重新导出 dex 再重新注入——换来的是产物里不多一个文件。

脚本自带两道门禁：

- 占位符在整份 dex 里必须恰好出现 1 次（写成 `static final` 会被内联成多份，
  烘焙时无法定位 → 运行时静默不弹窗）；
- 用 `platforms/android-21/android.jar` 再编一遍。`d8 --min-api` 不挡
  「调用了设备上不存在的 API」，那要到运行期才 `NoSuchMethodError`。

---

## 技术选型

| 依赖 | 版本 | 用途 | 为什么是它 |
|---|---|---|---|
| `com.android.tools.smali:smali-dexlib2` | 3.0.10 | dex 读写 | 随机访问、lazy 解析、无文本往返损失 |
| `junit` | 4.13.2 | 本地端到端验证 | 注入核心不含 Android API，可在 JVM 上跑真实 APK |

弹窗载荷（`payload/`）用 `javac + d8` 直接构建，**零第三方依赖**——它会被塞进别人的 APK，
体积和依赖面都必须克制。

**评估后未采用**：

- `apksig`（9.5.1）— 不重签名用不上；且 6 个类引用 `java.util.stream`，Android 上不存在该包
- `apkzlib`（9.5.1）— `bytestorage` 引用 `java.nio.file`，Android 已裁剪；`java.util.zip` 够用
- `org.smali:dexlib2`（2.5.2）— 2020 年停更，不认 dex 038/039
- `net.dongliu:apk-parser` — 拉 bcprov/bcpkix 1.58，只为读 Manifest 不值
- Room — 配置只有几条记录，KSP 注解处理器拖慢手机端构建，用 `org.json` 足够

---

## 环境

本工程在 Termux（Android/arm64）上开发与构建，配置对齐参考工程 `sx_rjk`：

```properties
# gradle.properties
org.gradle.java.home=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk
org.gradle.configuration-cache=true
org.gradle.configureondemand=true
org.gradle.vfs.watch=true
kotlin.incremental=true
```

| 组件 | 版本 | 备注 |
|---|---|---|
| Gradle | 9.5.0 | 本地已就绪；不用 9.8.2（当时只有 0 字节 `.part`） |
| AGP | 9.3.0 | 本地缓存最高版本；9.4.1 未缓存 |
| Kotlin | 2.4.10 | 同上 |
| JDK | 21 | `compileOptions` 与 `jvmToolchain(21)` 统一 |
| compileSdk / minSdk / targetSdk | 37 / 28 / 34 | 平台包名是 `platforms;android-37.0`，目录 `android-37.0` |

```bash
# 本地构建
sh gradlew assembleDebug

# 跑验证（用真实 APK）
sh gradlew :app:testDebugUnitTest
```

### 云端构建

CI 用的公共镜像只预装 android-35/36，而本工程 compileSdk=37，
所以流水线第一步会跑 `.cnb/scripts/ensure-sdk.sh` 补齐平台与 build-tools。

脚本不写死包名和路径，而是从 `app/build.gradle.kts` 读 `compileSdk` 后向
`sdkmanager --list` 查询实际包名（API 36 之后 Google 把平台包名改成了
`主版本.次版本`，目录也从 `android-37` 变成 `android-37.0`）。
本地想手动补也可以直接跑：

```bash
sh .cnb/scripts/ensure-sdk.sh
```

---

## 文档

| 文件 | 内容 |
|---|---|
| `docs/流程文档.md` | 注入流水线每一步的实现细节与数据流 |
| `docs/项目介绍.md` | 架构、模块职责、设计取舍 |
| `docs/踩坑记录.md` | 每个真实踩到的坑：现象、根因、修法、教训 |
| `payload/dialog/` | 弹窗载荷源码与构建脚本（独立于 App 构建） |
| `docs/变更历史.md` | 版本演进 |
| `AGENTS.md` | AI 维护者规范（改动前必读） |

---

## 已知限制

| 限制 | 后果 | 说明 |
|---|---|---|
| 不重签名 | 只能覆盖安装到已装同签名包的设备 | 换机需自行用 MT 等工具签名 |
| 不处理加固包 | 注入会「找不到目标类」 | 360/爱加密/梆梆的 dex 已加密，dexlib2 读出乱码 |
| v 寄存器有风险 | 运行时可能异常 | 注入点在方法开头，v 族尚未赋值；用 `p0`/`p1` 才安全 |
| 无幂等保护 | 重复注入会在方法头叠加调用 | UI 靠 `running` 状态挡重复点击，是 UI 保证不是数据保证 |
| 只支持 arm64-v8a | 32 位设备装不上 | `abiFilters` 收窄，换 ABI 改 `app/build.gradle.kts` |
| 批量注入串行 | 50 个包要等较久 | 并行只会更快 OOM，串行还能保日志因果顺序 |
| 弹窗载荷无网络能力 | 只能显示本地配置的公告 | 刻意为之：注入进别人 App 的东西不该有联网轮询的行为 |

---

## 许可

仅供学习与研究。
