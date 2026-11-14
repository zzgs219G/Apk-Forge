package com.xixin.apkforge

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.xixin.apkforge.ui.AppRoot
import com.xixin.apkforge.ui.theme.ComposeEmptyActivityTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App 固定深色，系统栏图标必须固定用浅色。
        // 不传 style 时 `enableEdgeToEdge()` 按系统明暗决定图标颜色——系统处于浅色时
        // 会给我们深色图标，画在近黑画布上等于看不见（和正文黑字同一类问题）。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            ComposeEmptyActivityTheme {
                AppRoot()
            }
        }
    }
}
