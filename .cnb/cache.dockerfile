# 构建缓存镜像：Android SDK + Gradle 依赖
#
# 由 docker:cache 推到 CNB Docker 制品库，供所有构建节点拉取。
# docker.volumes 只在单节点有效，跨节点必须走镜像。
#
# ## 为什么需要
# 基础镜像 android-sdk-image:36.1.0 的 packages.txt 只有
#   platforms;android-35/36 + build-tools;35.0.1/36.1.0
# 而本工程 compileSdk=37，缺的部分由 .cnb/scripts/ensure-sdk.sh 每次现场
# sdkmanager 下载安装。SDK 装在 /opt/android-sdk-linux，不属于 GRADLE_USER_HOME，
# 任何 volume 都缓存不到 —— 只能固化进镜像。
#
# 预装后 ensure-sdk.sh 会检测到「已就位」直接跳过，省掉每次的下载安装。
#
# 重建条件见 pipeline 的 by / versionBy：依赖清单或本文件变更时才重建。

# 36.1.0 自带 openjdk-21，app/build.gradle.kts 的 jvmToolchain(21) 依赖它
FROM mobiledevops/android-sdk-image:36.1.0

# 不能用 /workspace：那是 CNB 挂载仓库代码的目录，运行时会覆盖镜像内容。
WORKDIR /space

# 不能设成 /space/.gradle：项目根就是 /space，Gradle 的项目级缓存也叫 .gradle，
# 两者撞名会互相污染。单独用 gradle-home。
ENV GRADLE_USER_HOME=/space/gradle-home

# 补齐基础镜像缺的 SDK 组件，版本与 ensure-sdk.sh 的推导保持一致：
#   compileSdk=37        → platforms;android-37.0（AGP 要 android.jar）
#   compileSdk=37        → build-tools;37.0.0（tools/sample-payload/build.sh 要 d8）
# ⚠️ 包名必须带小版本号：官方索引里是 platforms;android-37.0，
#    写成 platforms;android-37 会报 "Failed to find package"。
RUN yes | "$ANDROID_HOME/cmdline-tools/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
        "platforms;android-37.0" "build-tools;37.0.0" "platform-tools" > /dev/null \
    && rm -rf "$ANDROID_HOME/.temp" "$ANDROID_HOME/.downloadIntermediates" \
    && echo "SDK 组件：" && ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"

# 只拷依赖清单，源码变更不应触发镜像重建。
# 构建上下文只含本 Dockerfile 与 pipeline 的 by 列表文件。
COPY . .

# 预下载 Gradle 发行包并解析依赖。
# dependencies 失败不阻断：个别配置可能需要源码才能解析，
# 目标是把 wrapper dist 与 modules-2 填满即可。
#
# ⚠️ 这里不能跑 assembleDebug/Release：app/src/main/assets/sample/DemoDialog.dex
#    是 CI 用 tools/sample-payload/build.sh 现场生成的构建产物，源码树里没有。
# 末尾删掉对后续构建无用的内容（路径/时间戳每次都变，只占体积、拖慢拷贝）。
# 两个 du 把体积打进构建日志，一眼能看出装成功没有。
RUN chmod +x gradlew && \
    ./gradlew --version && \
    (./gradlew :app:dependencies --configuration releaseRuntimeClasspath || true) && \
    (./gradlew --stop || true) && \
    rm -rf /space/.gradle "$GRADLE_USER_HOME"/daemon && \
    du -sh "$ANDROID_HOME" "$GRADLE_USER_HOME"
