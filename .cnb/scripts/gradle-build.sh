#!/bin/sh
# ============================================================================
# 统一执行 gradle 打包，并把 gradle 的**真实退出码**透传给流水线
#
# ## 为什么单列一个脚本
# 这段命令原先在 web / api / tag 三个入口里各复制一份（12 行 × 3），
# 与 resolve-version.sh 同样的理由：改一次要同步三处，漏一处的表现是
# "某个入口的行为静默不同"。现在三个入口都 `sh .cnb/scripts/gradle-build.sh`。
#
# ## 为什么用子 shell 包住 gradle（这是本脚本存在的核心原因）
# 原写法：`./gradlew ... 2>&1 | tee build-log/gradle.log`
# **管道的退出码取管道最后一条命令（tee）的**，恒为 0 —— gradle 失败被吞掉。
# 后果不是"报错"，而是"看起来成功"：
#   「执行打包」绿了 → 下一 Stage「整理 APK」才以「❌ 没有 APK 产出」失败，
#   真正的 Kotlin 编译错误要另外去挖阶段日志。
#   2026-11-03 实测：4 条 `@Composable invocations can only happen…` 就是这样被藏掉的。
#
# ## 为什么不是 `set -o pipefail`
# CNB 的运行环境是 dash（见 resolve-version.sh 头部说明），dash 没有 pipefail，
# 也没有 `${PIPESTATUS}`。故用子 shell 把 gradle 的 `$?` 落盘，管道结束后再读回。
#
# ## 环境变量
# GRADLE_TASK / VERSION_NAME / VERSION_CODE / log_level 由上层 Stage 写进
# /tmp/cnb/vars.sh（仅赋值、未 export），子进程不会自动继承，故这里要再 source 一次。
# ============================================================================

if [ -f /tmp/cnb/vars.sh ]; then
  . /tmp/cnb/vars.sh
fi

export LANG=C.UTF-8
export LC_ALL=C.UTF-8
export LANGUAGE=C.UTF-8

# ⚠️ 2026-10-11 修复：网页填的版本号其实从没传进过 gradle —— vars.sh 里写的是
#    VERSION_CODE/VERSION_NAME，而 build.gradle.kts 读的是 APP_VERSION_CODE/APP_VERSION_NAME
#    （GitHub Actions 的 build-apk.yml 一直有这组映射，CNB 流水线抄的时候漏了），
#    缺了它 gradle 读不到环境变量，静默回落默认 212(2.1.2)，只有文件名是填的号。
export APP_VERSION_CODE="${VERSION_CODE:-}"
export APP_VERSION_NAME="${VERSION_NAME:-}"

echo "开始打包：${GRADLE_TASK:-} 版本 ${VERSION_NAME:-}(${VERSION_CODE:-})"

mkdir -p build-log
FLAGS=""
[ "${log_level:-}" = "详细模式" ] && FLAGS="--info --warning-mode all"

# ── JVM 堆按容器内存自适应 ──
# CNB 的 runner 内存 = cpus × 2 GiB（官方文档 grammar.md#pipeline-cpus）。
# 写死 -Xmx4g + MaxMetaspaceSize=1g 在 4 核（8 GiB）容器里已占 5 GiB，
# 加上 Kotlin 编译子进程 / AGP worker / R8 会被 OOM Killer 杀掉
# （表现是构建莫名失败、日志里没有 Java 异常，只有 daemon disappeared）。
# 取 45% 给堆、15% 给 Metaspace，其余留给其它进程。
MEM_MB=$(awk '/MemTotal/{printf "%d", $2/1024}' /proc/meminfo 2>/dev/null || echo 4096)
HEAP_MB=$(( MEM_MB * 45 / 100 ))
[ "$HEAP_MB" -lt 1024 ] && HEAP_MB=1024     # 低于 1g 无法编译
META_MB=$(( MEM_MB * 15 / 100 ))
[ "$META_MB" -lt 512 ] && META_MB=512
echo "容器内存 ${MEM_MB}MB → Gradle 堆 ${HEAP_MB}MB / Metaspace ${META_MB}MB"

# 全部模式时 GRADLE_TASK 含两个任务（assembleDebug assembleRelease），
# 不能加引号——需按空格分词传给 gradlew
# shellcheck disable=SC2086
( ./gradlew ${GRADLE_TASK:-assembleDebug} \
    -Dorg.gradle.jvmargs="-Xmx${HEAP_MB}m -XX:MaxMetaspaceSize=${META_MB}m -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8" \
    --configuration-cache --stacktrace --console=plain --build-cache $FLAGS \
    2>&1
  # 子 shell 里捕获 gradle 的退出码（含被信号杀死时的 128+N），落盘给外层读
  echo $? > build-log/gradle.rc
) | tee build-log/gradle.log

RC=$(cat build-log/gradle.rc 2>/dev/null || echo 1)
if [ "$RC" != "0" ]; then
  echo "❌ gradle 失败（exit $RC）—— 编译错误见上方输出或 build-log/gradle.log"
  exit "$RC"
fi
