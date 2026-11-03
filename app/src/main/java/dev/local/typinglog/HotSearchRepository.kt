package dev.local.typinglog

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** 一条热搜。url 用于知乎这类可以直接跳到详情页的源。 */
data class HotItem(
    val rank: Int,
    val title: String,
    val hot: String,
    val url: String = ""
)

/** 榜单来源。 */
enum class HotSource { WEIBO, DOUYIN, ZHIHU }

/**
 * 热搜数据源：
 * - 微博：移动端公开接口（不需要登录），失败退回网页版 ajax 接口。
 * - 抖音：iesdouyin 的公开榜单接口，返回 word_list。
 * 两个源各自缓存一份，切换标签时先显示缓存再刷新。
 */
object HotSearchRepository {

    private const val WEIBO_MOBILE_URL =
        "https://m.weibo.cn/api/container/getIndex" +
            "?containerid=106003type%3D25%26t%3D3%26disable_hot%3D1%26filter_type%3Drealtimehot"
    private const val WEIBO_AJAX_URL = "https://weibo.com/ajax/side/hotSearch"
    private const val DOUYIN_URL =
        "https://www.iesdouyin.com/web/api/v2/hotsearch/billboard/word/"
    private const val ZHIHU_URL =
        "https://www.zhihu.com/api/v3/feed/topstory/hot-list-web?limit=50&desktop=true"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 5) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    fun load(source: HotSource): List<HotItem> = when (source) {
        HotSource.WEIBO -> loadWeibo()
        HotSource.DOUYIN -> loadDouyin()
        HotSource.ZHIHU -> loadZhihu()
    }

    // ------------------------------------------------------------------ 微博

    private fun loadWeibo(): List<HotItem> {
        val fromMobile = try {
            fetch(WEIBO_MOBILE_URL, "https://m.weibo.cn/")?.let { parseWeiboMobile(it) }
        } catch (_: Exception) {
            null
        }
        if (!fromMobile.isNullOrEmpty()) return fromMobile

        val fromAjax = try {
            fetch(WEIBO_AJAX_URL, "https://weibo.com/")?.let { parseWeiboAjax(it) }
        } catch (_: Exception) {
            null
        }
        if (!fromAjax.isNullOrEmpty()) return fromAjax

        return emptyList()
    }

    private fun parseWeiboMobile(body: String): List<HotItem> {
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

    private fun parseWeiboAjax(body: String): List<HotItem> {
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

    // ------------------------------------------------------------------ 抖音

    private fun loadDouyin(): List<HotItem> {
        val body = try {
            fetch(DOUYIN_URL, "https://www.douyin.com/")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return try {
            parseDouyin(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseDouyin(body: String): List<HotItem> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("word_list") ?: return emptyList()
        val out = ArrayList<HotItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("word").trim()
            if (title.isEmpty()) continue
            val num = o.optLong("hot_value", 0L)
            out.add(HotItem(out.size + 1, title, formatHot(if (num > 0) num.toString() else "")))
            if (out.size >= 50) break
        }
        return out
    }

    // ------------------------------------------------------------------ 知乎

    private fun loadZhihu(): List<HotItem> {
        val body = try {
            fetch(ZHIHU_URL, "https://www.zhihu.com/hot")
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        return try {
            parseZhihu(body)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseZhihu(body: String): List<HotItem> {
        val root = JSONObject(body)
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<HotItem>()
        for (i in 0 until arr.length()) {
            val target = arr.optJSONObject(i)?.optJSONObject("target") ?: continue
            val title = target.optJSONObject("title_area")?.optString("text")?.trim().orEmpty()
            if (title.isEmpty()) continue
            val hot = target.optJSONObject("metrics_area")?.optString("text")?.trim().orEmpty()
            val url = target.optJSONObject("link")?.optString("url")?.trim().orEmpty()
            out.add(HotItem(out.size + 1, title, hot, url))
            if (out.size >= 50) break
        }
        return out
    }

    // ------------------------------------------------------------------ 公共

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

    private fun formatHot(raw: String): String {
        val n = raw.trim().toLongOrNull() ?: return raw.trim()
        return if (n >= 10_000) String.format(Locale.US, "%.1f万", n / 10_000.0) else n.toString()
    }

    // ------------------------------------------------------------------ 缓存

    fun saveCache(ctx: Context, source: HotSource, items: List<HotItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().put("t", it.title).put("h", it.hot).put("u", it.url))
        }
        prefs(ctx).edit()
            .putString(cacheKey(source), arr.toString())
            .putLong(cacheAtKey(source), System.currentTimeMillis())
            .apply()
    }

    fun loadCache(ctx: Context, source: HotSource): Pair<List<HotItem>, Long>? {
        val raw = prefs(ctx).getString(cacheKey(source), null) ?: return null
        return try {
            val arr = JSONArray(raw)
            val list = ArrayList<HotItem>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(HotItem(i + 1, o.optString("t"), o.optString("h"), o.optString("u")))
            }
            list to prefs(ctx).getLong(cacheAtKey(source), 0L)
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheKey(source: HotSource) =
        "hot_cache_" + source.name.lowercase(Locale.US)

    private fun cacheAtKey(source: HotSource) =
        "hot_cache_at_" + source.name.lowercase(Locale.US)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
}
