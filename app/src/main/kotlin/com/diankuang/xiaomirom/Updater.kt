package com.diankuang.xiaomirom

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 云更新：直接读取 GitHub Releases 的最新版（Release 标题/标签即版本号，如 v2.8）。
 * 优先 api.github.com（可拿标题、更新说明、APK 附件直链）；
 * 不通时退回 github.com/releases/latest 的 302 重定向地址解析标签。
 */
object Updater {
    const val REPO = "Zz-ask10089/xiaomi-rom-tools"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val HTML = "https://github.com/$REPO/releases/latest"

    class Info(val version: String, val notes: String, val page: String, val apk: String)

    private val VER = Regex("v?(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE)

    private fun parseVer(s: String): List<Int>? =
        VER.find(s)?.groupValues?.get(1)?.split('.')?.mapNotNull { it.toIntOrNull() }

    /** a > b ? 按数字段比较，缺段补 0（v2.10 > v2.9） */
    private fun greater(a: List<Int>, b: List<Int>): Boolean {
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** 有新版返回 Info；已最新返回 null；所有途径都失败抛 IOException。 */
    fun check(curVersionName: String): Info? {
        val cur = parseVer(curVersionName) ?: listOf(0)
        var last: Exception? = null

        // 1) 官方 API
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", Http.UA)
            c.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (c.responseCode == 200) {
                    val o = JSONObject(c.inputStream.readBytes().toString(Charsets.UTF_8))
                    val title = o.optString("name").ifBlank { o.optString("tag_name") }
                    val v = parseVer(title) ?: return null
                    var apk = ""
                    val assets = o.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val a = assets.getJSONObject(i)
                            if (a.optString("name").endsWith(".apk")) {
                                apk = a.optString("browser_download_url"); break
                            }
                        }
                    }
                    val page = o.optString("html_url", HTML)
                    return if (greater(v, cur))
                        Info("v" + v.joinToString("."), o.optString("body", "").trim(), page, apk)
                    else null
                } else last = IOException("API HTTP " + c.responseCode)
            } finally { c.disconnect() }
        } catch (e: Exception) { last = e }

        // 2) 退回 releases/latest 重定向（只能拿标签）
        try {
            val c = URL(HTML).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.requestMethod = "HEAD"
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", Http.UA)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: ""
                    val v = parseVer(loc) ?: return null
                    return if (greater(v, cur))
                        Info("v" + v.joinToString("."), "", loc, "")
                    else null
                }
                last = IOException("redirect HTTP $code")
            } finally { c.disconnect() }
        } catch (e: Exception) { last = e }

        throw (last ?: IOException("update check failed"))
    }

    /** 记住已提示过的版本，自动检查不重复打扰（手动检查不受限）。 */
    fun seenVer(c: android.content.Context): String =
        c.getSharedPreferences("update", 0).getString("seen", "") ?: ""

    fun markSeenVer(c: android.content.Context, v: String) =
        c.getSharedPreferences("update", 0).edit().putString("seen", v).apply()
}
