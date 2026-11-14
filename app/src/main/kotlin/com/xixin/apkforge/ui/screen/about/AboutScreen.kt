package com.xixin.apkforge.ui.screen.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xixin.apkforge.ui.lifted.page.AppTopBar
import com.xixin.apkforge.ui.lifted.Gap
import com.xixin.apkforge.ui.lifted.SectionTitle
import com.xixin.apkforge.ui.lifted.AppConstants

@Composable
fun AboutScreen(
    onOpenDrawer: () -> Unit = {},
    onOpenConfig: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar(
            title = "关于",
            onMenuClick = onOpenDrawer,
        )

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = AppConstants.SCREEN_EDGE_PADDING,
                end = AppConstants.SCREEN_EDGE_PADDING,
                top = 12.dp,
                bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                InfoCard("工作原理") {
                    Step("1", "读二进制 AndroidManifest.xml，解析出 LAUNCHER Activity")
                    Step("2", "用 dexlib2 打开目标类所在 dex，不做文本 smali 往返")
                    Step("3", "在目标方法第一条指令之前插入你粘的 invoke 行")
                    Step("4", "方法不存在则新建：父类调用 + 注入指令 + return")
                    Step("5", "payload dex 作为 classesN.dex 追加，重新打包")
                    Step("6", "产物写入 Download/ApkForge/")
                }
            }

            item {
                InfoCard("当前限制", "4 项") {
                    Limit("不重签名", "产物只能覆盖安装到已装同签名包的设备。换机安装需自行签名。")
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
                InfoCard("关于 payload dex") {
                    Text(
                        "payload 是你自己用 Java/Kotlin 写好、经 d8 编译出的 dex。" +
                            "里面的类会随包一起加载，被注入的 invoke 调用。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Gap(8.dp)
                    Text(
                        "示例 DemoDialog.inject(Activity) 会延迟 800ms 弹对话框。" +
                            "延迟是必须的——onCreate 开头窗口 token 还没就绪，" +
                            "直接 show 会抛 BadTokenException。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Gap(10.dp)
                    OutlinedButton(onClick = onOpenConfig) { Text("去配置页看示例") }
                }
            }

            item {
                InfoCard("依赖", "2 项") {
                    KV("Dex 引擎", "smali-dexlib2 3.0.10")
                    KV("测试", "junit 4.13.2")
                    Gap(8.dp)
                    Text(
                        "apksig / apkzlib 评估后未采用：前者 6 个类引用 java.util.stream" +
                            "（Android 不存在），后者 bytestorage 引用 java.nio.file" +
                            "（Android 已裁剪）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.padding(vertical = 8.dp)) }
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    trailing: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            SectionTitle(title, trailing = trailing)
            Gap(10.dp)
            content()
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            n,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Limit(name: String, detail: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun KV(key: String, value: String) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
