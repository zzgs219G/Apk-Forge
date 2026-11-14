package com.xixin.apkforge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 关于页。
 *
 * 存在意义：把这个工具的**能力边界**写清楚。
 * 注入类工具最怕用户以为「什么包都能改」——壳（360/爱加密/梆梆）的 dex 是加密的，
 * dexlib2 读出来是乱码，注入了也跑不起来；这类限制必须写在明面上，而不是等用户撞墙。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, onOpenConfig: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("关于", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "能力与限制",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← 返回", fontSize = 12.sp) }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InfoCard("工作原理") {
                Step("1", "读 APK 的二进制 AndroidManifest.xml，解析出 LAUNCHER Activity")
                Step("2", "用 smali-dexlib2 打开目标类所在的 dex（不改文本 smali，零往返损失）")
                Step("3", "在目标方法**第一条指令之前**插入你粘的 invoke 行")
                Step("4", "方法不存在就新建：父类调用 + 注入指令 + return，保证生命周期不断裂")
                Step("5", "payload dex 作为 classesN.dex 追加，重新打包（Manifest 原样二进制拷贝）")
                Step("6", "产物写入 Download/ApkForge/")
            }

            InfoCard("当前限制") {
                Limit("不重签名", "产物只能覆盖安装到已装同签名包的设备。换机安装需自行签名。")
                Limit("不处理加固包", "360/爱加密/梆梆等壳的 dex 已加密，dexlib2 读不到真实方法。注入会找不到目标类。")
                Limit("不修改 Manifest 文本", "解码再写回会产出系统不认的灰包，这是 apktool 已知问题。")
                Limit("v 寄存器有风险", "注入点在方法开头，此时 v 族寄存器尚未赋值；用 p0/p1 等参数寄存器才安全。")
            }

            InfoCard("关于 payload dex") {
                Text(
                    "payload 是你自己用 Java/Kotlin 写好、经 d8 编译出的 dex。" +
                        "里面的类会随包一起加载，被注入的 invoke 调用。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "示例：DemoDialog.inject(Activity) 会延迟 800ms 弹一个对话框。" +
                        "延迟是必须的——onCreate 开头窗口 token 还没就绪，直接 show 会抛 BadTokenException。",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    onClick = onOpenConfig,
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
                ) {
                    Text(
                        "去配置页看看示例",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }

            InfoCard("依赖") {
                Dep("smali-dexlib2", "3.0.10", "dex 读写")
                Dep("JUnit", "4.13.2", "本地端到端验证")
                Spacer(Modifier.height(6.dp))
                Text(
                    "仅此两项。apksig / apkzlib 评估后未采用：" +
                        "前者 6 个类引用 java.util.stream（Android 不存在），" +
                        "后者 bytestorage 引用 java.nio.file（Android 已裁剪）。",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun Step(n: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            n,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp),
        )
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
    }
}

@Composable
private fun Limit(name: String, detail: String) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(
            name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.error,
        )
        Text(
            detail,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 15.sp,
        )
    }
}

@Composable
private fun Dep(name: String, version: String, use: String) {
    Row(
        Modifier.padding(vertical = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(130.dp),
        )
        Text(
            version,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(56.dp),
        )
        Text(
            use,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
