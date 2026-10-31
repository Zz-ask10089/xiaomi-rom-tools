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

    // ---------- 用户自选目录（SAF OpenDocumentTree，持久授权） ----------

    /** 记住的树 URI（null = 未自选） */
    fun saveTree(c: Context): String? =
        c.getSharedPreferences("ui", 0).getString("save_tree", null)

    fun setSaveTree(c: Context, v: String?) =
        c.getSharedPreferences("ui", 0).edit()
            .putString("save_tree", v).remove("save_tree_uri_ok")
            .apply()

    /** 树 URI → 真实文件路径（仅内部存储 primary 卷） */
    fun treeToPath(uri: Uri): String? {
        val doc = uri.lastPathSegment?.let { Uri.decode(it) } ?: return null
        val i = doc.indexOf(':')
        if (i <= 0) return null
        val vol = doc.substring(0, i)
        val rel = doc.substring(i + 1).trim('/')
        if (vol != "primary") return null
        return if (rel.isEmpty())
            Environment.getExternalStorageDirectory().absolutePath
        else File(Environment.getExternalStorageDirectory(), rel).absolutePath
    }

    /** URI 的显示名，如 /Download/rom */
    fun treeLabel(uri: Uri): String {
        val doc = uri.lastPathSegment?.let { Uri.decode(it) } ?: return "自定义目录"
        val rel = doc.substringAfter(':', "").trim('/')
        return if (rel.isEmpty()) "内部存储根目录" else "/$rel"
    }

    /**
     * 当前有效的自选目录：已授权且授权仍有效且目录存在才返回，否则 null。
     * Android 11+ 的 SAF 树授权同时放行该目录的低层文件读写，无需「所有文件访问」。
     */
    fun customDir(c: Context): File? {
        if (Build.VERSION.SDK_INT < 30) return null
        val s = saveTree(c) ?: return null
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val alive = c.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!alive) return null
        val path = treeToPath(uri) ?: return null
        val f = File(path)
        return if (f.isDirectory || f.mkdirs()) f else null
    }

    /** 公共可见基准目录（自选优先），不可用则 null */
    fun sharedBase(c: Context): File? =
        customDir(c) ?: if (publicOk(c)) publicBase() else null

    /** 显示用目录名 */
    fun baseLabel(c: Context): String =
        customDir(c)?.absolutePath?.removePrefix("/storage/emulated/0") ?: "Download"

    /** 当前实际写入的基准目录 */
    fun baseDir(c: Context): File = sharedBase(c) ?: legacyBase(c)

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
     * 把旧私有目录里的分区 / CSV 搬到当前公共可见目录（自选目录优先，同盘 rename 秒完成）。
     * 返回搬移的文件数。在 IO 线程调用。
     */
    fun migrateAll(c: Context): Int {
        val dest = sharedBase(c) ?: return 0
        var moved = 0
        val scanned = ArrayList<String>()
        val pairs = listOf(
            File(legacyBase(c), "payload_extracted") to File(dest, "payload_extracted"),
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
            val dst = File(dest, f.name)
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
