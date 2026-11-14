package com.xixin.apkforge.ui.screen.about

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.design.Dim
import com.xixin.apkforge.ui.design.Forge
import com.xixin.apkforge.ui.design.ForgeCard
import com.xixin.apkforge.ui.design.ForgeSecondaryButton
import com.xixin.apkforge.ui.design.ForgeTopBar
import com.xixin.apkforge.ui.design.KVRow
import com.xixin.apkforge.ui.design.SectionLabel
import com.xixin.apkforge.ui.design.VGap

/**
 * 关于页。把能力边界写在明面上。
 *
 * 注入类工具最怕用户以为「什么包都能改」。壳的 dex 是加密的，
 * dexlib2 读出来是乱码——这类限制必须在撞墙前告知。
 */
@Composable
fun AboutScreen(
    onOpenDrawer: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Forge.Colors.bg)
    ) {
        ForgeTopBar(
            title = "关于",
            subtitle = "原理与限制",
            onMenu = onOpenDrawer,
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = Dim.edge, end = Dim.edge, top = 4.dp, bottom = 44.dp
            ),
            verticalArrangement = Arrangement.spacedBy(Dim.sectionGap),
        ) {
            item {
                Column {
                    SectionLabel("工作原理")
                    VGap(12.dp)
                    Step("01", "读二进制 AndroidManifest.xml，解析出 LAUNCHER Activity")
                    Step("02", "用 dexlib2 打开目标类所在 dex，不做文本 smali 往返")
                    Step("03", "在目标方法第一条指令之前插入你粘的 invoke 行")
                    Step("04", "方法不存在则新建：父类调用 + 注入指令 + return")
                    Step("05", "payload dex 作为 classesN.dex 追加，重新打包")
                    Step("06", "产物写入 Download/ApkForge/")
                }
            }

            item {
                Column {
                    SectionLabel("当前限制", trailing = "4 项")
                    VGap(12.dp)
                    Limit(
                        "不重签名",
                        "产物只能覆盖安装到已装同签名包的设备。换机安装需自行签名。",
                    )
                    Limit(
                        "不处理加固包",
                        "360 / 爱加密 / 梆梆等壳的 dex 已加密，dexlib2 读不到真实方法，" +
                            "注入会报「找不到目标类」。",
                    )
                    Limit(
                        "v 寄存器有风险",
                        "注入点在方法第一条指令之前，v 族寄存器尚未赋值。" +
                            "只引用 p0/p1 等参数寄存器才安全。",
                    )
                    Limit(
                        "无幂等保护",
                        "重复注入会在方法头叠加调用。UI 挡住了重复点击，但这是 UI 保证。",
                    )
                }
            }

            item {
                Column {
                    SectionLabel("关于 payload dex")
                    VGap(12.dp)
                    Text(
                        "payload 是你自己用 Java/Kotlin 写好、经 d8 编译出的 dex。" +
                            "里面的类会随包一起加载，被注入的 invoke 调用。",
                        style = Forge.Type.body,
                        color = Forge.Colors.textDim,
                    )
                    VGap(10.dp)
                    Text(
                        "示例 DemoDialog.inject(Activity) 会延迟 800ms 弹对话框。" +
                            "延迟是必须的——onCreate 开头窗口 token 还没就绪，" +
                            "直接 show 会抛 BadTokenException。",
                        style = Forge.Type.caption,
                        color = Forge.Colors.textFaint,
                    )
                    VGap(14.dp)
                    ForgeSecondaryButton("去配置页看示例", onOpenConfig)
                }
            }

            item {
                Column {
                    SectionLabel("依赖", trailing = "2 项")
                    VGap(12.dp)
                    KVRow("Dex 引擎", "smali-dexlib2 3.0.10")
                    KVRow("测试", "junit 4.13.2")
                    VGap(12.dp)
                    Text(
                        "apksig / apkzlib 评估后未采用：前者 6 个类引用 java.util.stream" +
                            "（Android 不存在），后者 bytestorage 引用 java.nio.file" +
                            "（Android 已裁剪）。",
                        style = Forge.Type.caption,
                        color = Forge.Colors.textFaint,
                    )
                }
            }

            item { Spacer(Modifier.height(4.dp)) }
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 5.dp)) {
        Text(
            n,
            style = Forge.Type.microMono,
            color = Forge.Colors.accent,
            modifier = Modifier.width(28.dp),
        )
        Text(
            text,
            style = Forge.Type.body,
            color = Forge.Colors.textDim,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Limit(name: String, detail: String) {
    ForgeCard(Modifier.padding(vertical = 4.dp)) {
        Column(Modifier.padding(13.dp)) {
            Text(name, style = Forge.Type.title, color = Forge.Colors.amber)
            VGap(5.dp)
            Text(detail, style = Forge.Type.caption, color = Forge.Colors.textFaint)
        }
    }
}
