#!/bin/sh
# 编译示例载荷 dex。
#
# 产物：app/src/main/assets/sample/DemoDialog.dex（打进 APK，App 里"载入示例"要用）
#
# ## 为什么自动探测 SDK 路径而不是写死
# 初版写死 `platforms/android-37/android.jar` 与 `build-tools/37.0.0/d8`。
# 这在开发机（Termux + android-37）没问题，但 CI 镜像
# `mobiledevops/android-sdk-image:34.0.1` 只保证 API 34，
# 37 不存在 → 脚本第一行 check 就失败 → 整个流水线红。
# 现在按优先级自动探测，任何 SDK 布局都能跑。
#
# ## 探测优先级（从高到低）
#   1. 显式环境变量 ANDROID_JAR / D8（调用方指定，最高优先）
#   2. ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT 下的最新可用版本
#   3. 常见安装路径兜底
#
# ## 为什么用 sh 而不是 bash
# CNB 的运行环境是 dash，没有 bash。虽然本脚本由 `bash xxx.sh` 调用，
# 但保持 POSIX 兼容可以在没有 bash 的容器里直接 `sh xxx.sh` 跑。

set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)

# ── 定位 SDK 根 ──
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ]; then
  for c in \
    "$HOME/android-sdk" \
    "$HOME/Android/sdk" \
    /opt/android-sdk \
    /usr/local/android-sdk \
    /data/data/com.termux/files/home/android-sdk; do
    [ -d "$c/platforms" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] || { echo "❌ 找不到 Android SDK，请设置 ANDROID_SDK" >&2; exit 1; }
echo "SDK = $SDK"

# ── android.jar：取 platforms 下最高版本 ──
pick_android_jar() {
  if [ -n "${ANDROID_JAR:-}" ] && [ -f "${ANDROID_JAR}" ]; then
    echo "${ANDROID_JAR}"; return
  fi
  best=""
  bestN=0
  for d in "$SDK"/platforms/android-*; do
    [ -f "$d/android.jar" ] || continue
    n=$(echo "$d" | sed 's/.*android-//')
    case "$n" in (*[!0-9]*|"") continue ;; esac
    if [ "$n" -gt "$bestN" ]; then bestN="$n"; best="$d/android.jar"; fi
  done
  echo "$best"
}
JAR=$(pick_android_jar)
[ -n "$JAR" ] && [ -f "$JAR" ] || {
  echo "❌ 找不到 android.jar（$SDK/platforms/android-*/）" >&2
  echo "── $SDK/platforms 实际内容 ──" >&2
  ls -1 "$SDK/platforms" 2>&1 | head -20 >&2 || echo "  (目录不存在或无权限)" >&2
  exit 1
}
echo "android.jar = $JAR"

# ── d8：取 build-tools 下最高版本 ──
pick_d8() {
  if [ -n "${D8:-}" ] && [ -f "${D8}" ]; then
    echo "${D8}"; return
  fi
  for d in "$SDK"/build-tools/*/d8 "$SDK"/build-tools/*/lib/d8.jar; do
    [ -e "$d" ] && { echo "$d"; return; }
  done
  # 兜底：从任意 build-tools 里找 d8.jar，用 java 起
  for j in "$SDK"/build-tools/*/lib/d8.jar; do
    [ -f "$j" ] && { echo "jar:$j"; return; }
  done
  echo ""
}
D8=$(pick_d8)
[ -n "$D8" ] || {
  echo "❌ 找不到 d8（$SDK/build-tools/*/）" >&2
  echo "── $SDK/build-tools 实际内容 ──" >&2
  ls -1 "$SDK/build-tools" 2>&1 | head -20 >&2 || echo "  (目录不存在或无权限)" >&2
  exit 1
}
echo "d8 = $D8"

# ── javac / java：优先 PATH，其次 JDK 常见位置 ──
JAVAC="${JAVAC:-$(command -v javac || true)}"
[ -n "$JAVAC" ] || for j in /usr/lib/jvm/*/bin/javac "$HOME"/.jdks/*/bin/javac; do
  [ -x "$j" ] && { JAVAC="$j"; break; }
done
[ -n "$JAVAC" ] || { echo "❌ 找不到 javac" >&2; exit 1; }

JAVA="${JAVA:-$(command -v java || true)}"
[ -n "$JAVA" ] || for j in /usr/lib/jvm/*/bin/java "$HOME"/.jdks/*/bin/java; do
  [ -x "$j" ] && { JAVA="$j"; break; }
done
[ -n "$JAVA" ] || { echo "❌ 找不到 java" >&2; exit 1; }
echo "javac = $JAVAC"

OUT="$ROOT/app/src/main/assets/sample"
WORK="$HERE/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$OUT"

echo "[1/3] javac  (JDK9+ 已禁用 -bootclasspath，改用 -classpath + --release 11)"
"$JAVAC" -nowarn --release 11 \
  -classpath "$JAR" \
  -d "$WORK/classes" \
  "$HERE/src/com/apkforge/demo/DemoDialog.java"

echo "[2/3] d8  --min-api 21"
CLASSES=$(find "$WORK/classes" -name '*.class')
case "$D8" in
  jar:*)
    # d8.jar 不可直接执行，用 java -cp 起（com.android.tools.r8.D8）
    "$JAVA" -cp "${D8#jar:}" com.android.tools.r8.D8 \
      --min-api 21 --lib "$JAR" --output "$WORK" $CLASSES
    ;;
  *)
    "$D8" --min-api 21 --lib "$JAR" --output "$WORK" $CLASSES
    ;;
esac

echo "[3/3] 拷贝到 assets"
cp "$WORK/classes.dex" "$OUT/DemoDialog.dex"
ls -la "$OUT/DemoDialog.dex"

# 自检：魔数必须是 dex\n
MAGIC=$(head -c 4 "$OUT/DemoDialog.dex")
if [ "$MAGIC" != "dex" ]; then
  echo "❌ 产物魔数错误：$MAGIC" >&2
  exit 1
fi
echo "OK"
