#!/bin/sh
# ============================================================================
# 计算版本号，写入 /tmp/cnb/vars.sh 供后续 stage source
#
# ## 优先级（从高到低）
#   1. CNB_IS_TAG=true     → 从 tag 解析（v1.2.0 → 1.2.0 / 120）
#   2. $version_code 有值  → 支持两种填法：整数(210) 或点分(2.1.0)
#   3. version.properties  → 仓库根目录的文件（本地 IDE 构建的兜底默认值）
#   4. 都不行              → fail-fast，不猜
#
# ## 为什么不学 jian_box 用 version 分支
# 那套是为了多人协作下「版本号单一数据源 + 打包后递增回写」。
# ApkForge 单人开发，version.properties 就在仓库根目录，直接读即可。
# 少一个分支 = 少一处要同步的状态。
#
# ## 环境说明
# CNB 的运行环境是 dash，不是 bash：
#   · 没有 pipefail、没有 ${PIPESTATUS}
#   · 没有 `[[ ]]`、没有 `==`
# 所以本脚本刻意只用 POSIX sh 语法。
# ============================================================================

VARS="${VARS_FILE:-/tmp/cnb/vars.sh}"
mkdir -p "$(dirname "$VARS")"

PROPS="version.properties"

# 把版本号写进 vars.sh，后续 stage `. /tmp/cnb/vars.sh` 取用。
# 用单引号包裹，防止值里的特殊字符在 source 时被展开。
emit() {
  echo "VERSION_CODE='$VERSION_CODE'" >> "$VARS"
  echo "VERSION_NAME='$VERSION_NAME'" >> "$VARS"
}

# ── 1. 标签优先 ──
if [ "$CNB_IS_TAG" = "true" ]; then
  TAG="${CNB_TAG:-$CNB_BRANCH}"
  case "$TAG" in
    v*) RAW="$TAG" ;;
    *)  RAW="" ;;
  esac
  if [ -n "$RAW" ]; then
    VERSION_NAME="${RAW#v}"
    # 1.2.3 → 123
    VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. '{printf "%d", $1*100+$2*10+$3}')
    echo "✔ 标签解析：$VERSION_NAME($VERSION_CODE)"
    emit
    exit 0
  fi
  echo "❌ 标签构建但 tag 不是 v* 形态：$TAG"
  exit 1
fi

# ── 2. 手动填的版本号 ──
if [ -n "${version_code:-}" ]; then
  case "$version_code" in
    # 纯整数 → 换算版本名（210 → 2.1.0）
    *[!0-9]*)
      case "$version_code" in
        # 点分版本名 → 换算 code（2.1.0 → 210）
        *.*.*)
          VERSION_NAME="$version_code"
          VERSION_CODE=$(echo "$VERSION_NAME" | awk -F. '{printf "%d", $1*100+$2*10+$3}')
          ;;
        *)
          echo "❌ version_code 需为整数(如 210)或点分版本名(如 2.1.0)，收到：$version_code"
          exit 1
          ;;
      esac
      ;;
    *)
      VERSION_CODE="$version_code"
      VERSION_NAME="$((VERSION_CODE/100)).$(((VERSION_CODE%100)/10)).$((VERSION_CODE%10))"
      ;;
  esac
  echo "✔ 手动指定：$VERSION_NAME($VERSION_CODE)"
  emit
  exit 0
fi

# ── 3. 读 version.properties ──
if [ -f "$PROPS" ]; then
  VERSION_CODE=$(grep -E '^VERSION_CODE=' "$PROPS" | head -1 | cut -d= -f2 | tr -d ' \r')
  VERSION_NAME=$(grep -E '^VERSION_NAME=' "$PROPS" | head -1 | cut -d= -f2 | tr -d ' \r')
  if [ -n "$VERSION_CODE" ] && [ -n "$VERSION_NAME" ]; then
    echo "✔ 读取 $PROPS：$VERSION_NAME($VERSION_CODE)"
    emit
    exit 0
  fi
fi

echo "❌ 无法确定版本号：既不是 tag、也没填 version_code、$PROPS 也不可用"
exit 1
