#!/bin/sh
# ============================================================================
# 补齐 CI 所需的 Android SDK 平台 / build-tools
#
# ## 为什么需要
# 镜像 mobiledevops/android-sdk-image 只预装 packages.txt 里那几个包
# （现为 platforms;android-35/36 + build-tools;35.0.1/36.1.0），
# 而本工程 compileSdk 一路往上走（当前 37）。Gradle 缺平台会直接失败：
#   Failed to find target with hash string 'android-37'
#
# ## 为什么不是「写死 platforms;android-37」
# 2026-11 实测的两次失败，全是写死导致的：
#   ① 包名不存在：Google 的 sdk repo 里 **没有** platforms;android-37，
#      从 API 36 之后改用「主版本.次版本」命名，包里叫 platforms;android-37.0，
#      装完目录是 platforms/android-37.0（不是 android-37）。
#      sdkmanager 对不存在的包只会报一句 Warning，然后 exit 0 —— 假成功。
#   ② sdkmanager 路径猜错：镜像把 cmdline-tools 解压到 $ANDROID_HOME 根，
#      可执行文件在 $ANDROID_HOME/cmdline-tools/bin/sdkmanager，
#      没有 latest/ 这一层。写死 latest 就是 "not found"。
# 所以本脚本：包名从 compileSdk 推导、装前用 --list 校准、sdkmanager 多路径探测。
#
# ## 环境
# CNB 的 sh 是 dash：无 pipefail / PIPESTATUS / [[ ]]。只用 POSIX 语法。
# ============================================================================

set -eu

ROOT=$(pwd)
GRADLE_FILE="$ROOT/app/build.gradle.kts"

# ── 1. 确定 SDK 根 ──────────────────────────────────────────────
SDK="${ANDROID_SDK:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  for c in /opt/android-sdk-linux /opt/android-sdk "$HOME/android-sdk" "$HOME/Android/sdk"; do
    [ -d "$c" ] && { SDK="$c"; break; }
  done
fi
[ -n "$SDK" ] && [ -d "$SDK" ] || {
  echo "❌ 找不到 Android SDK 根目录（ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT 均为空）"
  exit 1
}
echo "SDK = $SDK"

# ── 2. JDK 自检（jvmToolchain(21) 要求 JDK ≥ 21，镜像必须自带）────
JAVA_BIN=$(command -v java || true)
if [ -n "$JAVA_BIN" ]; then
  echo "java = $(java -version 2>&1 | head -1)"
  JAVA_MAJOR=$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9]*\).*/\1/')
  case "$JAVA_MAJOR" in
    ''|*[!0-9]*) JAVA_MAJOR=0 ;;
  esac
  if [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "❌ 当前 java 主版本 $JAVA_MAJOR < 21，app/build.gradle.kts 的 jvmToolchain(21) 会失败"
    echo "   镜像需换代：mobiledevops/android-sdk-image:36.1.0（自带 openjdk-21）"
    exit 1
  fi
else
  echo "❌ PATH 上没有 java，Gradle 无法启动"
  exit 1
fi

# ── 3. 定位 sdkmanager：镜像布局千差万别，逐个试 ──────────────────
SDKM=""
for c in \
  "$SDK/cmdline-tools/latest/bin/sdkmanager" \
  "$SDK/cmdline-tools/bin/sdkmanager" \
  "$SDK/tools/bin/sdkmanager"; do
  [ -x "$c" ] && { SDKM="$c"; break; }
done
[ -n "$SDKM" ] || SDKM=$(command -v sdkmanager || true)
[ -n "$SDKM" ] || {
  echo "❌ 找不到 sdkmanager，已尝试："
  echo "   $SDK/cmdline-tools/latest/bin/sdkmanager"
  echo "   $SDK/cmdline-tools/bin/sdkmanager"
  echo "   $SDK/tools/bin/sdkmanager"
  echo "── $SDK 实际内容 ──"
  ls -1 "$SDK" 2>&1 | head -20
  exit 1
}
echo "sdkmanager = $SDKM"

# ── 4. 从 build.gradle.kts 读 compileSdk，不许再写死数字 ──────────
COMPILE_SDK=$(grep -E '^[[:space:]]*compileSdk[[:space:]]*=' "$GRADLE_FILE" \
  | head -1 | sed 's/[^0-9]//g')
[ -n "$COMPILE_SDK" ] || {
  echo "❌ 无法从 $GRADLE_FILE 读出 compileSdk"
  exit 1
}
echo "compileSdk = $COMPILE_SDK"

# ── 5. 平台是否已就位（目录名可能是 android-37 或 android-37.0）────
platform_ready() {
  for d in "$SDK/platforms/android-$COMPILE_SDK" "$SDK/platforms/android-$COMPILE_SDK.0"; do
    [ -f "$d/android.jar" ] && return 0
  done
  return 1
}

# build-tools 也要看一眼：示例 dex 生成要用 d8。
# ⚠️ 不能「有任意 d8 就算就绪」：镜像自带 build-tools 36.1.0，那个 d8 存在，
#    会让本函数提前返回成功，从而跳过 build-tools;37.0.0 的安装；
#    而下面第 8 步的复核又会通过，形成「以为装好了其实没装」的假象。
#    这里按 compileSdk 的次版本号严格匹配（37.0.0 / 37.x.y 都算）。
build_tools_ready() {
  for d in "$SDK"/build-tools/"$COMPILE_SDK".*/d8; do
    [ -x "$d" ] && return 0
  done
  return 1
}

if platform_ready && build_tools_ready; then
  echo "✔ platforms 与 build-tools 均已就位，跳过安装（compileSdk=$COMPILE_SDK）"
  ls -1 "$SDK/platforms"
  exit 0
fi

# ── 6. 只装缺的那部分 ────────────────────────────────────────────
# 为什么不一装了之：sdkmanager 发现目标目录已存在，不会覆盖，
# 而是另装一份叫 android-37.0-2 —— platforms 下堆出重复目录，
# 白占磁盘，还会让「取最高版本」的探测选到半成品。
INSTALL_PKGS=""
if ! platform_ready; then
  # 6.1 校准包名：先问 sdkmanager 有哪些，别硬猜
  PKG_LIST=$(mktemp)
  "$SDKM" --sdk_root="$SDK" --list 2>/dev/null > "$PKG_LIST" || true

  pick_pkg() {
    # $1 = 精确候选 1，$2 = 精确候选（可空），$3 = 前缀兜底（取最高非预览版）
    for want in "$1" "$2"; do
      [ -n "$want" ] || continue
      if awk -v p="$want" '$1 == p { f = 1 } END { exit !f }' "$PKG_LIST"; then
        echo "$want"; return 0
      fi
    done
    awk -v pre="$3" '
      $1 ~ "^" pre && $1 !~ /(rc|beta|alpha|dev|canary)/ {
        v = $1; sub("^" pre, "", v); print v "\t" $1
      }' "$PKG_LIST" \
      | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 | cut -f2
  }

  # API 36 之后 Google 把平台包名改成「主版本.次版本」（platforms;android-37.0，
  # 装完目录是 android-37.0），但整数形态也还留在列表里。
  # 两个都试，谁在列表里用谁 —— 不写死任何一边。
  PLATFORM_PKG=$(pick_pkg "platforms;android-$COMPILE_SDK" "platforms;android-$COMPILE_SDK.0" \
    "platforms;android-$COMPILE_SDK")

  if [ -z "$PLATFORM_PKG" ]; then
    echo "❌ sdkmanager 的可用包列表里没有 platforms;android-$COMPILE_SDK*"
    echo "── 列表中的 platforms;android-3* ──"
    awk '$1 ~ /^platforms;android-3/' "$PKG_LIST" | awk '{print "   " $1}' | head -20
    rm -f "$PKG_LIST"
    exit 1
  fi
  INSTALL_PKGS="$PLATFORM_PKG"
  rm -f "$PKG_LIST"
fi

if ! build_tools_ready; then
  INSTALL_PKGS="$INSTALL_PKGS build-tools;$COMPILE_SDK.0.0"
fi
# 去掉可能的首尾空格，日志里别出现「待安装： build-tools;...」
INSTALL_PKGS=$(echo "$INSTALL_PKGS" | sed 's/^ *//; s/ *$//')

# ── 7. 许可一律先接受 ────────────────────────────────────────────
# AGP 自己也会按需补 build-tools，许可没接受就会卡在
# "Failed to install the following Android SDK packages ... licenses have not been accepted"。
yes 2>/dev/null | "$SDKM" --sdk_root="$SDK" --licenses > /dev/null 2>&1 || true

if [ -n "$INSTALL_PKGS" ]; then
  echo "待安装：$INSTALL_PKGS"
  # shellcheck disable=SC2086
  yes 2>/dev/null | "$SDKM" --sdk_root="$SDK" "platform-tools" $INSTALL_PKGS 2>&1 | tail -5 || true
else
  echo "✔ platforms/android-$COMPILE_SDK 与 build-tools 均已就位，无需安装"
fi

# ── 8. 复核：sdkmanager 对不存在的包只 Warning 不报错，必须自己验 ──
platform_ready || {
  echo "❌ 安装后仍找不到 android.jar"
  echo "── $SDK/platforms 实际内容 ──"
  ls -1 "$SDK/platforms" 2>&1 | head -20
  exit 1
}
build_tools_ready || {
  echo "❌ platforms 已就位，但 build-tools 下没有可执行的 d8"
  echo "   生成示例 payload dex 需要它"
  ls -1 "$SDK/build-tools" 2>&1 | head -20
  exit 1
}

echo "✔ SDK 就绪"
ls -1 "$SDK/platforms"
ls -1 "$SDK/build-tools"
