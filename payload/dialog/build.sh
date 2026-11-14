#!/bin/sh
# ============================================================================
# 把本地弹窗载荷编译成可直接注入的 dex，并生成调用配置
#
# ## 产出（全部落在 payload/out/）
#   PayloadDialog.dex         注入用的 payload dex
#   ApkForgeDialog.json       放进宿主 assets/ 的配置文件
#   call.txt                  注入用的 smali 调用行
#   apkforge-plugin.zip       上面三个的打包（可直接在 App 里「导入插件包」）
#
# ## 为什么用 d8 而不是 javac + dx
# dx 已在 AGP 8 移除；d8 随 build-tools 发布，且默认产出与 ART 兼容的
# dex 版本。参数只需 `--min-api` 与 `--output`，无需 Gradle。
#
# ## 为什么不用 `--release`
# release 模式会做 desugaring 与全局优化，可能把 `-injars` 里的类重命名；
# 载荷的**类名与方法名本身就是契约**（调用行里硬编码），一旦被改名注入即失效。
#
# ## 环境要求
#   JDK 17+（javac）与 Android build-tools（d8）
#   build-tools 路径可用 ANDROID_BUILD_TOOLS 覆盖
# ============================================================================
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/out"
CLASSES="$HERE/build/classes"
SRC="$HERE/src"
LIBS="$HERE/libs"

# ── 1. 定位 JDK 与 d8 ─────────────────────────────────────────
JAVAC=$(command -v javac || true)
[ -n "$JAVAC" ] || { echo "❌ 找不到 javac，需要 JDK 17+"; exit 1; }

D8=""
if [ -n "${ANDROID_BUILD_TOOLS:-}" ] && [ -x "$ANDROID_BUILD_TOOLS/d8" ]; then
  D8="$ANDROID_BUILD_TOOLS/d8"
else
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk "$HOME/Android/sdk"; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    # 取版本号最大的那个
    latest=$(ls -1 "$root/build-tools" 2>/dev/null | sort -V | tail -1)
    if [ -n "$latest" ] && [ -x "$root/build-tools/$latest/d8" ]; then
      D8="$root/build-tools/$latest/d8"
      break
    fi
  done
fi
[ -n "$D8" ] || {
  echo "❌ 找不到 d8（Android build-tools）。"
  echo "   设置 ANDROID_BUILD_TOOLS=/path/to/build-tools/36.1.0 后重试"
  exit 1
}

ANDROID_JAR=""
# d8 在 <sdk>/build-tools/<ver>/d8，往上三级才是 SDK 根
SDK_ROOT=$(cd "$(dirname "$D8")/../.." && pwd)
for api in android-37.0 android-37 android-36 android-35; do
  if [ -f "$SDK_ROOT/platforms/$api/android.jar" ]; then
    ANDROID_JAR="$SDK_ROOT/platforms/$api/android.jar"
    break
  fi
done
[ -n "$ANDROID_JAR" ] || {
  echo "❌ 找不到 android.jar（需要至少一个 platform）"
  exit 1
}

echo "javac    = $JAVAC"
echo "d8       = $D8"
echo "android  = $ANDROID_JAR"

# ── 2. 编译 ────────────────────────────────────────────────────
rm -rf "$CLASSES" "$OUT"
mkdir -p "$CLASSES" "$OUT"

find "$SRC" -name '*.java' > "$HERE/build/sources.txt"
# `-bootclasspath` 指向 android.jar：载荷只允许用 Android 平台 API。
# 用 JDK 自带的 java.* 编译会放过 java.util.stream / java.nio.file 这类
# Android 上不存在的类，注入后宿主一跑到就 NoClassDefFoundError。
#
# ⚠️ `-bootclasspath` 只在 `-source/-target 8` 下被允许：
#    target ≥ 9 的 javac 会直接报 "option --boot-class-path not allowed with target 17"。
#    载荷代码刻意避开 lambda / var / switch 表达式，8 的语言级别足够。
"$JAVAC" -source 8 -target 8 -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$CLASSES" \
  @"$HERE/build/sources.txt" 2>&1 | grep -vE "^(Note|warning: \[options\])" || true

CLASS_COUNT=$(find "$CLASSES" -name '*.class' | wc -l | tr -d ' ')
[ "$CLASS_COUNT" -gt 0 ] || { echo "❌ 没有产出 class，编译失败"; exit 1; }
echo "class    = $CLASS_COUNT 个"

# ── 3. 转 dex ──────────────────────────────────────────────────
find "$CLASSES" -name '*.class' > "$HERE/build/classes.txt"
"$D8" --min-api 21 --lib "$ANDROID_JAR" --output "$OUT" \
  $(cat "$HERE/build/classes.txt")

DEX="$OUT/classes.dex"
[ -f "$DEX" ] || { echo "❌ d8 没有产出 classes.dex"; exit 1; }
mv "$DEX" "$OUT/PayloadDialog.dex"
echo "dex      = $(wc -c < "$OUT/PayloadDialog.dex" | tr -d ' ') 字节"

# ── 4. 调用行与默认配置 ────────────────────────────────────────
cat > "$OUT/call.txt" <<'CALL'
invoke-static {p0}, Lcom/apkforge/payload/dialog/PayloadDialog;->inject(Landroid/app/Activity;)V
CALL

cp "$HERE/ApkForgeDialog.json" "$OUT/ApkForgeDialog.json"

# ── 5. 打包 ────────────────────────────────────────────────────
# 用 python 打 zip 而不依赖 zip 命令（Termux / 精简镜像里常常没有）
python3 - "$OUT" <<'PY'
import os, sys, zipfile
out = sys.argv[1]
zpath = os.path.join(out, "apkforge-plugin.zip")
with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(os.path.join(out, "PayloadDialog.dex"), "PayloadDialog.dex")
    z.write(os.path.join(out, "ApkForgeDialog.json"), "ApkForgeDialog.json")
    z.write(os.path.join(out, "call.txt"), "call.txt")
print("zip      =", zpath)
PY

echo
echo "✅ 完成，产出在 $OUT"
ls -1 "$OUT"
