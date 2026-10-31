package com.diankuang.xiaomirom

import android.content.Context
import android.content.Intent
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {
    /** 生成带 BOM 的 CSV，返回文件（在 IO 线程调用）。授权后写公共 Download。 */
    fun export(context: Context, roms: List<Parser.Rom>): File {
        var dir = Storage.baseDir(context)
        if (!dir.exists()) dir.mkdirs()
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val f = File(dir, "xiaomirom_$ts.csv")
        OutputStreamWriter(FileOutputStream(f), "UTF-8").use { w ->
            w.write("﻿")
            w.write("机型,代号,地区,通道,类型,版本,安卓,大小,日期,文件名,源序号,下载直链\n")
            for (r in roms) {
                val base = csv(r.device) + "," + csv(r.codename) + "," + csv(r.region) + "," +
                        csv(r.channel) + "," + csv(r.type) + "," + csv(r.version) + "," +
                        csv(r.android) + "," + csv(r.size) + "," + csv(r.date) + "," +
                        csv(r.fileName) + ","
                if (r.cdnUrls.isEmpty()) {
                    w.write(base + ",\n")
                } else {
                    for (i in r.cdnUrls.indices) {
                        w.write(base + (i + 1) + "," + csv(r.cdnUrls[i]) + "\n")
                    }
                }
            }
        }
        return f
    }

    fun share(context: Context, f: File, mime: String = "text/csv") {
        // 相对某个已授权基准目录的 URI（FileProviderLite 允许其子目录）
        val bases = listOfNotNull(
            Storage.customDir(context),
            Storage.legacyBase(context),
            Storage.publicBase(),
            context.filesDir,
        )
        val base = bases.firstOrNull { f.absolutePath.startsWith(it.absolutePath) }
            ?: Storage.legacyBase(context)
        val rel = if (f.absolutePath.startsWith(base.absolutePath))
            f.absolutePath.substring(base.absolutePath.length) else f.name
        val u = Uri.Builder().scheme("content")
            .authority(context.packageName + ".fileprovider")
            .path(rel).build()
        val it = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, u)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(it, "分享文件"))
    }

    private fun csv(s: String?): String {
        if (s.isNullOrEmpty()) return ""
        return if (!s.contains(',') && !s.contains('"') && !s.contains('\n')) s
        else '"' + s.replace("\"", "\"\"") + '"'
    }
}
