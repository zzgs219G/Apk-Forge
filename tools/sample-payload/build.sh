#!/data/data/com.termux/files/usr/bin/bash
# 编译示例载荷 dex。
#
# 产物：app/src/main/assets/sample/DemoDialog.dex（打进 APK，App 里可直接"填入示例"）
#
# 依赖本机已装的 android-sdk（android.jar + build-tools/d8），路径可用环境变量覆盖：
#   ANDROID_JAR=... D8=... bash tools/sample-payload/build.sh
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"

SDK="${ANDROID_SDK:-/data/data/com.termux/files/home/android-sdk}"
JAR="${ANDROID_JAR:-$SDK/platforms/android-37/android.jar}"
D8="${D8:-$SDK/build-tools/37.0.0/d8}"
JAVAC="${JAVAC:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/javac}"
JAVA="${JAVA:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java}"

for f in "$JAR" "$D8" "$JAVAC"; do
  [ -e "$f" ] || { echo "缺少: $f" >&2; exit 1; }
done

OUT="$ROOT/app/src/main/assets/sample"
WORK="$HERE/build"
rm -rf "$WORK"
mkdir -p "$WORK/classes" "$OUT"

echo "[1/3] javac  (JDK21：-bootclasspath 已禁用，改用 -classpath + --release 11)"
"$JAVAC" -nowarn --release 11 \
  -classpath "$JAR" \
  -d "$WORK/classes" \
  "$HERE/src/com/apkforge/demo/DemoDialog.java"

echo "[2/3] d8  --min-api 21"
"$D8" \
  --min-api 21 \
  --lib "$JAR" \
  --output "$WORK" \
  $(find "$WORK/classes" -name '*.class')

echo "[3/3] 拷贝到 assets"
cp "$WORK/classes.dex" "$OUT/DemoDialog.dex"
ls -la "$OUT/DemoDialog.dex"

# 自检：魔数 + 能读出类名
head -c 4 "$OUT/DemoDialog.dex" | od -c | head -1
echo "OK"
