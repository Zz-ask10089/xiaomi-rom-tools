package com.diankuang.xiaomirom

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * 存取位置策略：
 * - Android 11+ 授予「所有文件访问」后 → 直接读写公共 /sdcard/Download，文件管理器可见；
 * - 未授权 / 旧系统 → 退回应用私有 Download（分享仍可用 FileProvider）。
 */
object Storage {

    /** R+ 且已授予 MANAGE_EXTERNAL_STORAGE */
    fun publicOk(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /** 公共 /sdcard/Download */
    fun publicBase(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** 旧版应用私有 Download（历史文件在这里） */
    fun legacyBase(c: Context): File =
        c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir

    /** 当前实际写入的 Download 基准目录 */
    fun baseDir(c: Context): File = if (publicOk(c)) publicBase() else legacyBase(c)

    /** 分区提取输出目录（自动创建） */
    fun extractDir(c: Context): File =
        File(baseDir(c), "payload_extracted").apply { if (!exists()) mkdirs() }

    /** 「所有文件访问」授权页 Intent（个别 ROM 没有定向页时退回总页） */
    fun settingsIntent(c: Context): Intent {
        val target = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + c.packageName),
        )
        return if (target.resolveActivity(c.packageManager) != null) target
        else Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** 跳转系统「所有文件访问」授权页 */
    fun openSettings(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            c.startActivity(settingsIntent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { }
    }

    /**
     * 把旧私有目录里的分区 / CSV 搬到公共 Download（同盘 rename，秒完成不重抄）。
     * 返回搬移的文件数。在 IO 线程调用。
     */
    fun migrateAll(c: Context): Int {
        if (!publicOk(c)) return 0
        var moved = 0
        val scanned = ArrayList<String>()
        val pairs = listOf(
            File(legacyBase(c), "payload_extracted") to File(publicBase(), "payload_extracted"),
        )
        for ((from, to) in pairs) {
            val files = from.listFiles()?.filter { it.isFile } ?: continue
            if (!to.exists() && !to.mkdirs()) continue
            for (f in files) {
                var dst = File(to, f.name)
                if (dst.exists() && dst.length() == f.length()) { f.delete(); continue }
                if (dst.exists()) dst = File(to, f.name.substringBeforeLast('.') +
                        "-mig" + System.currentTimeMillis() / 1000 + "." +
                        f.name.substringAfterLast('.'))
                if (f.renameTo(dst)) { moved++; scanned.add(dst.absolutePath) }
                else try { f.copyTo(dst, overwrite = true); f.delete(); moved++; scanned.add(dst.absolutePath) }
                catch (e: Exception) { /* 单个失败就留着 */ }
            }
        }
        // CSV 也一起搬
        legacyBase(c).listFiles { f -> f.isFile && f.name.endsWith(".csv") }?.forEach { f ->
            val dst = File(publicBase(), f.name)
            if (dst.exists() && dst.length() == f.length()) { f.delete(); return@forEach }
            if (f.renameTo(dst)) { moved++; scanned.add(dst.absolutePath) }
            else try { f.copyTo(dst, overwrite = true); f.delete(); moved++; scanned.add(dst.absolutePath) }
            catch (e: Exception) { }
        }
        if (scanned.isNotEmpty())
            MediaScannerConnection.scanFile(c, scanned.toTypedArray(), null, null)
        return moved
    }
}
