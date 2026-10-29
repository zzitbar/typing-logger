package dev.local.typinglog

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 本地存储：每天一个 JSONL 文件，放在应用私有目录 files/logs/ 下。
 * 这里只负责本机读写；联网只发生在 FeishuPusher 里，且需要用户主动开启。
 */
object LogStore {

    private val lock = Any()

    private val dayFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
    private val clockFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss", Locale.US)
    }

    fun dir(ctx: Context): File = File(ctx.filesDir, "logs").apply { mkdirs() }

    fun append(ctx: Context, record: Record) {
        synchronized(lock) {
            val file = File(dir(ctx), dayFormat.get()!!.format(Date(record.ts)) + ".jsonl")
            file.appendText(record.toJson().toString() + "\n", Charsets.UTF_8)
        }
    }

    /** 今天读得懂的一小段预览，直接显示在主界面上。 */
    fun todayPreview(ctx: Context): String {
        val file = File(dir(ctx), dayFormat.get()!!.format(Date()) + ".jsonl")
        if (!file.exists()) return "今天还没有记录。\n\n先开启无障碍服务，然后在任意输入框里打字。"
        val sb = StringBuilder()
        var count = 0
        file.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            try {
                val o = JSONObject(line)
                sb.append(clockFormat.get()!!.format(Date(o.optLong("ts"))))
                    .append("  ")
                    .append(o.optString("app"))
                    .append('\n')
                    .append(o.optString("text"))
                    .append("\n\n")
                count++
            } catch (_: Exception) {
                // 单行损坏就跳过，不影响其它记录
            }
        }
        return if (count == 0) "今天还没有记录。" else "今天共 $count 条：\n\n$sb"
    }

    /** 把所有日志打成一个 zip，放进 cacheDir 供分享。 */
    fun exportZip(ctx: Context): File {
        val out = File(ctx.cacheDir, "typing-log.zip")
        ZipOutputStream(out.outputStream().buffered()).use { zos ->
            dir(ctx).listFiles()?.sortedBy { it.name }?.forEach { f ->
                zos.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return out
    }

    fun clear(ctx: Context) {
        synchronized(lock) {
            dir(ctx).listFiles()?.forEach { it.delete() }
        }
    }
}
