# ApkForge

本地 APK dex 注入工具（Android 端，无需电脑）。

选一个 APK，把 smali 调用插进指定 Activity 的生命周期方法，附带的 payload dex 一起打进包，产物写到 `Download/ApkForge/`。

---

## 它做什么

```
选 APK ──▶ 解析 Manifest 的 LAUNCHER ──▶ 定位目标类所在 dex
                                            │
                                            ▼
                              在 onCreate 等方法的**第一条指令之前**
                              插入你粘的 invoke 行
                                            │
                                            ▼
                        方法不存在？→ 新建（父类调用 + 注入 + return）
                                            │
                                            ▼
                       payload dex 作为 classesN.dex 追加 ──▶ 重打包
                                            │
                                            ▼
                              Download/ApkForge/xxx_injected.apk
```

**不做**的事情：不重签名、不解码 Manifest 成文本、不处理加固包。

---

## 快速开始

1. 装 `app-debug.apk`
2. 侧边栏 → **配置** → 新建
3. 点「载入示例」→ 得到一套可用的配置（`DemoDialog.inject(Activity)` + 示例 dex）
4. 侧边栏 → **注入** → 选 APK → 选配置 → 开始注入
5. 结果卡上有「安装 / MT 定位 / 分享」

---

## 技术选型

| 依赖 | 版本 | 用途 | 为什么是它 |
|---|---|---|---|
| `com.android.tools.smali:smali-dexlib2` | 3.0.10 | dex 读写 | 随机访问、lazy 解析、无文本往返损失 |
| `junit` | 4.13.2 | 本地端到端验证 | 注入核心不含 Android API，可在 JVM 上跑真实 APK |

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

---

## 许可

仅供学习与研究。
