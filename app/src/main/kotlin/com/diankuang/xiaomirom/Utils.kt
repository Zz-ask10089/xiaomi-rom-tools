package com.diankuang.xiaomirom

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.util.Locale

object Utils {
    fun copy(context: Context, s: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("xiaomirom", s))
        Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
    }

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(context, "没有可打开链接的浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    fun fmtSize(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f GB", b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f MB", b.toDouble() / (1L shl 20))
        else -> "$b B"
    }
}
