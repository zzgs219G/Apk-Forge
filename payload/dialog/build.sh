#!/bin/sh
# ============================================================================
# 把本地弹窗载荷编译成「可以直接注入」的产物
#
# ## 产出（全部落在 payload/dialog/out/）
#   PayloadDialog.dex      载荷 dex（模板版：配置位是占位符）
#   call.txt               注入用的 smali 调用行
#   apkforge-plugin.zip    上面两个的打包（App 里「导入插件包」可直接吃）
#
# ## 为什么只有 dex + 调用行两件东西进宿主
# 上一版把 json 一并写进宿主的 `assets/`，产物凭空多一个文件，而且多一条
# 可能断的通道（资产没写进去 = 注入成功但不弹窗）。现在配置**烘焙进 dex**：
# 导出时把 dex 里那条唯一的占位字符串替换成真正的 JSON（见 core/dex/PayloadBaker）。
# 所以这个脚本只产出两件东西：dex 与调用行。配置的编辑入口在 App 里
# （插件包页的表单），默认值也只在 Kotlin 里维护一份——两处默认值必然漂。
#
# ## 为什么用 d8 而不是 dx
# dx 已在 AGP 8 移除；d8 随 build-tools 发布，默认产出 ART 兼容的 dex 版本。
#
# ## 为什么不用 --release
# release 会做 desugaring 与全局优化，可能重命名类/方法。而载荷的类名与方法名
# **本身就是契约**（调用行里硬编码），被改名注入即失效。
#
# ## 环境要求
#   JDK 17+（javac 需要 JDK 21 跑 d8）与 Android build-tools（d8、android.jar）
#   build-tools 路径可用 ANDROID_BUILD_TOOLS 覆盖
# ============================================================================
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/out"
CLASSES="$HERE/build/classes"
SRC="$HERE/src"

# ── 1. 定位 JDK 与 d8 ─────────────────────────────────────────
JAVAC=$(command -v javac || true)
[ -n "$JAVAC" ] || { echo "❌ 找不到 javac，需要 JDK 17+"; exit 1; }

D8=""
if [ -n "${ANDROID_BUILD_TOOLS:-}" ] && [ -x "$ANDROID_BUILD_TOOLS/d8" ]; then
  D8="$ANDROID_BUILD_TOOLS/d8"
else
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk "$HOME/Android/sdk"; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    latest=$(ls -1 "$root/build-tools" 2>/dev/null | sort -V | tail -1)
    if [ -n "$latest" ] && [ -x "$root/build-tools/$latest/d8" ]; then
      D8="$root/build-tools/$latest/d8"
      break
    fi
  done
fi
[ -n "$D8" ] || {
  echo "❌ 找不到 d8（Android build-tools）。"
  echo "   设置 ANDROID_BUILD_TOOLS=/path/to/build-tools/37.0.0 后重试"
  exit 1
}

# d8 在 <sdk>/build-tools/<ver>/d8，往上两级才是 SDK 根
SDK_ROOT=$(cd "$(dirname "$D8")/../.." && pwd)
ANDROID_JAR=""
for api in android-37.0 android-37 android-36.1 android-36 android-35; do
  if [ -f "$SDK_ROOT/platforms/$api/android.jar" ]; then
    ANDROID_JAR="$SDK_ROOT/platforms/$api/android.jar"
    break
  fi
done
[ -n "$ANDROID_JAR" ] || { echo "❌ 找不到 android.jar（需要至少一个 platform）"; exit 1; }

echo "javac    = $JAVAC"
echo "d8       = $D8"
echo "android  = $ANDROID_JAR"

# ── 2. 编译 ────────────────────────────────────────────────────
rm -rf "$CLASSES" "$OUT"
mkdir -p "$CLASSES" "$OUT"

find "$SRC" -name '*.java' > "$HERE/build/sources.txt"
# `-bootclasspath` 指向 android.jar：载荷只允许用 Android 平台 API。
# 用 JDK 自带的 java.* 会放过 java.util.stream / java.nio.file 这类 Android 上
# 不存在的类，注入后宿主一跑到就 NoClassDefFoundError。
#
# ⚠️ `-bootclasspath` 只在 `-source/-target 8` 下被允许（target ≥ 9 会直接报错）。
#    载荷代码刻意避开 lambda / var / switch 表达式，8 的语言级别足够。
"$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$CLASSES" \
  @"$HERE/build/sources.txt" 2>&1 | grep -vE "^(Note|warning: \[options\])" || true

CLASS_COUNT=$(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ')
[ "$CLASS_COUNT" -gt 0 ] || { echo "❌ 没有产出 class，编译失败"; exit 1; }
echo "class    = $CLASS_COUNT 个"

# ── 2.5 API 兼容自检：用最低支持的 platform 再编一遍 ─────────────
# 载荷要能塞进任意宿主，包括 minSdk 21 的老包。d8 的 `--min-api` 只影响
# desugar 策略，**不会**挡住「调用了设备上不存在的 API」——那要到运行期才
# 抛 NoSuchMethodError，正好砸在宿主启动那一瞬、也最难查。
# 用 android-21 的 android.jar 再编一遍，任何 ≥22 的 API 都会当场报错。
API21_JAR=""
for api in android-21; do
  [ -f "$SDK_ROOT/platforms/$api/android.jar" ] && API21_JAR="$SDK_ROOT/platforms/$api/android.jar"
done
if [ -n "$API21_JAR" ]; then
  rm -rf "$HERE/build/classes21"
  mkdir -p "$HERE/build/classes21"
  "$JAVAC" -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$API21_JAR" -classpath "$API21_JAR" \
    -d "$HERE/build/classes21" \
    @"$HERE/build/sources.txt" 2>&1 | grep -vE "^(Note|warning: \[options\])" || true
  if [ ! -f "$HERE/build/classes21/com/apkforge/payload/dialog/PayloadDialog.class" ]; then
    echo "❌ 用 android-21 编译失败：载荷用了 API 21 没有的接口，装到老宿主会 NoSuchMethodError"
    exit 1
  fi
  echo "API 兼容 = 通过（android-21 可编译）"
else
  echo "⚠️ 没装 platforms;android-21，跳过 API 兼容自检"
fi

# ── 3. 转 dex ──────────────────────────────────────────────────
find "$CLASSES" -name '*.class' > "$HERE/build/classes.txt"
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$OUT" \
  $(cat "$HERE/build/classes.txt")

DEX="$OUT/classes.dex"
[ -f "$DEX" ] || { echo "❌ d8 没有产出 classes.dex"; exit 1; }
mv "$DEX" "$OUT/PayloadDialog.dex"
echo "dex      = $(wc -c < "$OUT/PayloadDialog.dex" | tr -d ' ') 字节"

# ── 4. 占位符唯一性自检 ────────────────────────────────────────
# PayloadBaker 靠「全 dex 里唯一的那个占位串」定位配置常量。出现两份就不知道该
# 替换哪个，换错一处就会产出「注入成功、不弹窗」的包——这种失败没有报错。
# 这里在构建期直接数一遍，数出来不是 1 就当场失败。
COUNT=$(grep -c "__APKFORGE_CONFIG__" "$OUT/PayloadDialog.dex" 2>/dev/null || true)
# grep -c 数的是「行」，dex 是二进制，保险起见改用字节级统计
COUNT=$(python3 - "$OUT/PayloadDialog.dex" <<'PY'
import sys
data = open(sys.argv[1], 'rb').read()
print(data.count(b"__APKFORGE_CONFIG__"))
PY
)
echo "占位符   = 出现 $COUNT 次（必须恰好 1 次）"
[ "$COUNT" = "1" ] || { echo "❌ 占位符出现 $COUNT 次：配置常量被内联了，PayloadBaker 无法定位"; exit 1; }

# ── 5. 调用行与默认配置 ────────────────────────────────────────
cat > "$OUT/call.txt" <<'CALL'
invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V
CALL

# ── 6. 打包 ────────────────────────────────────────────────────
# 用 python 打 zip 而不依赖 zip 命令（Termux / 精简镜像里常常没有）
python3 - "$OUT" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
zpath = os.path.join(out, "apkforge-plugin.zip")
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    for name in ("PayloadDialog.dex", "call.txt"):
        z.write(os.path.join(out, name), name)
print("zip      =", zpath)
PY

echo
echo "✅ 完成，产出在 $OUT"
ls -1 "$OUT"
