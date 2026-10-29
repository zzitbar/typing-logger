package dev.local.typinglog

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 把记录推送到飞书群自定义机器人。
 *
 * 用的是官方 Webhook：POST JSON，msg_type = text。
 * 机器人开启“签名校验”时，把 Secret 填进应用即可，这里按官方算法补 timestamp/sign。
 *
 * 发送在后台串行队列里做，限速 1 秒 1 条，队列满了丢最旧的，不会拖慢输入。
 */
object FeishuPusher {

    private const val MIN_INTERVAL_MS = 1_000L
    private const val MAX_TEXT_LEN = 2_000
    private const val QUEUE_SIZE = 200

    private val queue = LinkedBlockingQueue<String>(QUEUE_SIZE)
    private val sender = Executors.newSingleThreadExecutor()
    private val tester = Executors.newSingleThreadExecutor()

    @Volatile
    private var started = false
    @Volatile
    private var appCtx: Context? = null

    private var lastSentAt = 0L

    /** 有新记录时调用；没开启推送或没配 Webhook 时直接返回。 */
    fun submit(ctx: Context, record: Record) {
        val p = prefs(ctx)
        if (!p.getBoolean(Keys.FEISHU_ENABLED, false)) return
        if (p.getString(Keys.FEISHU_WEBHOOK, "").isNullOrBlank()) return

        appCtx = ctx.applicationContext
        val message = buildMessage(record)
        if (!queue.offer(message)) {
            queue.poll()
            queue.offer(message)
        }
        ensureWorker()
    }

    /** 测试推送：直接发一条，不排队。 */
    fun test(ctx: Context, onResult: (Boolean, String) -> Unit) {
        appCtx = ctx.applicationContext
        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val message = "【数据记录】测试消息\n如果你看到这条，说明推送配置可用。\n$now"
        tester.execute {
            val result = try {
                send(ctx, message)
            } catch (e: Exception) {
                false to (e.message ?: "网络异常")
            }
            recordStatus(ctx, result)
            onResult(result.first, result.second)
        }
    }

    fun lastStatus(ctx: Context): String =
        prefs(ctx).getString(Keys.FEISHU_LAST, "").orEmpty().ifBlank { "尚未推送" }

    // ---------------------------------------------------------------- 内部实现

    private fun ensureWorker() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        sender.execute {
            while (true) {
                val message = try {
                    queue.take()
                } catch (_: InterruptedException) {
                    continue
                }
                val ctx = appCtx ?: continue

                val wait = lastSentAt + MIN_INTERVAL_MS - System.currentTimeMillis()
                if (wait > 0) {
                    try {
                        Thread.sleep(wait)
                    } catch (_: InterruptedException) {
                    }
                }
                lastSentAt = System.currentTimeMillis()

                val result = try {
                    send(ctx, message)
                } catch (e: Exception) {
                    false to (e.message ?: "网络异常")
                }
                recordStatus(ctx, result)
            }
        }
    }

    private fun buildMessage(record: Record): String {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(record.ts))
        var body = record.text
        if (body.length > MAX_TEXT_LEN) body = body.take(MAX_TEXT_LEN) + "…"
        return "【数据记录】$time · ${record.app}\n$body"
    }

    private fun send(ctx: Context, text: String): Pair<Boolean, String> {
        val p = prefs(ctx)
        val webhook = p.getString(Keys.FEISHU_WEBHOOK, "").orEmpty()
        val secret = p.getString(Keys.FEISHU_SECRET, "").orEmpty()
        if (webhook.isBlank()) return false to "未配置 Webhook"

        val payload = JSONObject().apply {
            put("msg_type", "text")
            put("content", JSONObject().put("text", text))
            if (secret.isNotBlank()) {
                val ts = System.currentTimeMillis() / 1000
                put("timestamp", ts.toString())
                put("sign", sign(ts, secret))
            }
        }

        val conn = (URL(webhook).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) return false to "HTTP $code"

            val json = try {
                JSONObject(body)
            } catch (_: Exception) {
                null
            } ?: return false to "返回内容无法解析"

            // 新版返回 code，老版返回 StatusCode
            val bizCode = if (json.has("code")) json.optInt("code", -1) else json.optInt("StatusCode", -1)
            if (bizCode == 0) true to "ok"
            else false to (json.optString("msg").ifBlank { json.optString("StatusMessage") }
                .ifBlank { "code=$bizCode" })
        } finally {
            conn.disconnect()
        }
    }

    /** 飞书自定义机器人签名：key = timestamp + "\n" + secret，消息体为空。 */
    private fun sign(timestamp: Long, secret: String): String {
        val stringToSign = "$timestamp\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(stringToSign.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
    }

    private fun recordStatus(ctx: Context, result: Pair<Boolean, String>) {
        val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = if (result.first) "$time 推送成功" else "$time 推送失败：${result.second}"
        prefs(ctx).edit().putString(Keys.FEISHU_LAST, text).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
}
