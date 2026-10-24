package com.diankuang.xiaomirom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边 / 系统栏配色统一由 App() 按当前主题模式处理（支持手动切深浅色）
        setContent {
            App()
        }
    }
}
