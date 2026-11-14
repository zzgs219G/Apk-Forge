package com.xixin.apkforge.core.dex

/**
 * 随 App 打包的示例载荷信息。
 *
 * 放在 core 而不是某个 feature：示例调用行与 assets 里的 DemoDialog.dex
 * 是**同一事实的两个表示**，任何 feature 都会用到，不属于任何一方的私有资产。
 */
object SamplePayload {

    /** assets 内的 dex 路径 */
    const val ASSET_PATH = "sample/DemoDialog.dex"

    /** 载入到 cacheDir 后的文件名 */
    const val FILE_NAME = "DemoDialog.dex"

    /**
     * 与 [ASSET_PATH] 里 `DemoDialog.inject(Activity)` 对应的 smali 调用行。
     *
     * 必须与 dex 内的方法签名逐字一致——否则用户点「填入示例」注入后
     * 运行期抛 NoSuchMethodError，而载荷类自己 catch 了 Throwable，
     * 表现为「静默不弹窗」，极难排查。`SamplePayloadTest` 钉住了这一致性。
     */
    const val CALL =
        "invoke-static {p0}, Lcom/apkforge/demo/DemoDialog;->inject(Landroid/app/Activity;)V"
}
