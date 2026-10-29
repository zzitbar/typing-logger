package dev.local.typinglog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 一条热搜。 */
data class HotItem(val rank: Int, val title: String, val hot: String)

/**
 * 微博热搜数据源。
 *
 * 优先用移动端公开接口（不需要登录），失败再退回网页版 ajax 接口。
 * 两个都失败时由界面显示上次缓存。
 */
object HotSearchRepository {

    private const val MOBILE_URL =
        "https://m.weibo.cn/api/container/getIndex" +
            "?containerid=106003type%3D25%26t%3D3%26disable_hot%3D1%26filter_type%3Drealtimehot"
    private const val AJAX_URL = "https://weibo.com/ajax/side/hotSearch"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun load(): List<HotItem> {
        val fromMobile = try {
            fetch(MOBILE_URL, "https://m.weibo.cn/")?.let { parseMobile(it) }
        } catch (_: Exception) {
            null
        }
        if (!fromMobile.isNullOrEmpty()) return fromMobile

        val fromAjax = try {
            fetch(AJAX_URL, "https://weibo.com/")?.let { parseAjax(it) }
        } catch (_: Exception) {
            null
        }
        if (!fromAjax.isNullOrEmpty()) return fromAjax

        return emptyList()
    }

    // ------------------------------------------------------------------ 网络

    private fun fetch(url: String, referer: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Referer", referer)
            setRequestProperty("Accept", "application/json, text/plain, */*")
        }
        try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------------ 解析

    private fun parseMobile(body: String): List<HotItem> {
        val root = JSONObject(body)
        val cards = root.optJSONObject("data")?.optJSONArray("cards") ?: return emptyList()
        val out = ArrayList<HotItem>()
        for (i in 0 until cards.length()) {
            val group = cards.optJSONObject(i)?.optJSONArray("card_group") ?: continue
            for (j in 0 until group.length()) {
                val o = group.optJSONObject(j) ?: continue
                if (o.has("promotion")) continue
                val title = o.optString("desc").trim()
                if (title.isEmpty()) continue
                out.add(HotItem(out.size + 1, title, formatHot(o.optString("desc_extr"))))
                if (out.size >= 50) return out
            }
        }
        return out
    }

    private fun parseAjax(body: String): List<HotItem> {
        val root = JSONObject(body)
        val arr = root.optJSONObject("data")?.optJSONArray("realtime") ?: return emptyList()
        val out = ArrayList<HotItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("word").trim()
            if (title.isEmpty()) continue
            val num = o.optLong("num", 0L)
            out.add(HotItem(out.size + 1, title, formatHot(if (num > 0) num.toString() else "")))
            if (out.size >= 50) return out
        }
        return out
    }

    private fun formatHot(raw: String): String {
        val n = raw.trim().toLongOrNull() ?: return raw.trim()
        return if (n >= 10_000) String.format(Locale.US, "%.1f万", n / 10_000.0) else n.toString()
    }

    // ------------------------------------------------------------------ 缓存

    fun saveCache(ctx: Context, items: List<HotItem>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("t", it.title).put("h", it.hot)) }
        prefs(ctx).edit()
            .putString(Keys.HOT_CACHE, arr.toString())
            .putLong(Keys.HOT_CACHE_AT, System.currentTimeMillis())
            .apply()
    }

    fun loadCache(ctx: Context): Pair<List<HotItem>, Long>? {
        val raw = prefs(ctx).getString(Keys.HOT_CACHE, null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<HotItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(HotItem(i + 1, o.optString("t"), o.optString("h")))
            }
            list to prefs(ctx).getLong(Keys.HOT_CACHE_AT, 0L)
        } catch (_: Exception) {
            null
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
}
