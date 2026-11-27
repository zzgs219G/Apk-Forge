# ApkForge R8 规则
#
# 抄自 jian_box，但**删掉了它用不到的部分**（Room / Navigation / serialization
# 都是那边的依赖），换成本工程实际用到的（dexlib2 / Compose / 生命周期）。
# 照抄一堆与自己无关的 -keep 是最糟的：它们看起来像"保险"，实际让 R8 少删东西，
# 而且没人敢删 —— 因为不知道当初为什么加。

-keepattributes SourceFile,LineNumberTable     # 崩溃栈要行号定位
-keepattributes Signature,InnerClasses         # Kotlin 内部类与泛型反射

# ── JNI：native 方法名不可改 ──
#
# 本工程目前没有 native 代码，但这条留着：它只在"真的有 native 方法"时生效，
# 未来加 JNI 时忘了写会得到 UnsatisfiedLinkError，症状是启动即崩、栈里
# 只说"找不到方法"，而看不出是混淆改的名。
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── dexlib2：字节码层面的名字不能碰 ──
#
# ⚠️ 这条是**本工程区别于普通 App 的关键**。
#
# 我们的核心功能是"读写别人 APK 里的 dex"。dexlib2 通过 Builder 接口 +
# 反射构造实现类（`DexFileFactory` 按文件头选 `DexBackedDexFile`），
# 且大量使用泛型接口（`DexBuffer`/`TypeReference`/`MethodReference`）。
# 混淆这些接口的泛型签名与实现类名，会在运行期变成
# `ClassNotFoundException` 或 `AbstractMethodError` —— 而且只在**真正解析
# 目标 APK 时**才炸，单测跑不到那条路径。
# 用两个变体：`**` 匹配任意层包，`*` 匹配直接子项。
# 实测单独写 `**` 在本工程的 classpath 布局下没生效（553 个类仍被改名），
# 两个都写才能覆盖 —— R8 的包通配在这点上与 ProGuard 文档的表述不完全一致。
-keep class com.android.tools.smali.dexlib2.** { *; }
-keep class com.android.tools.smali.dexlib2.* { *; }
-keep class com.android.tools.smali.** { *; }
-keep interface com.android.tools.smali.dexlib2.** { *; }
-dontwarn com.android.tools.smali.dexlib2.**

# dexlib2 依赖的 guava 部分（它用了一小撮，R8 会报缺类）
-dontwarn com.google.common.**
-dontwarn javax.annotation.**

# ── Compose ──
#
# AGP + Compose 编译器会自动处理大部分，但 `@Composable` 函数被反射调用的
# 场景（Preview、测试）需要保留名字。本工程没有 Preview 依赖，只保留
# runtime 需要的最小集。
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}

# ── AndroidX ViewModel ──
#
# `viewModel()` 用反射调用无参构造创建实例。类名可改，但**构造函数必须留**。
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# ── 本工程的数据类 ──
#
# ConfigStore / TaskStore 用 org.json 手写序列化，字段名是字符串字面量
# 硬编码在代码里 —— 所以**字段改名不影响**。这里不加 -keep，
# 让 R8 正常压缩。如果将来改用反射式序列化（如 Gson），必须回来加。
#
# 同理 `core/` 里的所有类都是自己 new 的，不经过反射，不需要 keep。

# 混淆字典与 -repackageclasses 由 build.gradle.kts 构建期生成规则文件传入
# （与 jian_box 同一套做法，见那里的注释）。
