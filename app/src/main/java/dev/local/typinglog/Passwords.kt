package dev.local.typinglog

import android.content.Context
import java.security.MessageDigest
import java.util.Locale

/** 进入界面的密码。只保存 SHA-256，不存明文。 */
object Passwords {

    /** 默认密码 */
    const val DEFAULT = "ZHHL@91xxkj"

    fun hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append(String.format(Locale.US, "%02x", b.toInt() and 0xff))
        return sb.toString()
    }

    private fun expected(ctx: Context): String =
        prefs(ctx).getString(Keys.PASSWORD_HASH, null) ?: hash(DEFAULT)

    fun matches(ctx: Context, input: String): Boolean = hash(input) == expected(ctx)

    fun change(ctx: Context, old: String, new: String): Boolean {
        if (!matches(ctx, old)) return false
        if (new.length < 4) return false
        prefs(ctx).edit().putString(Keys.PASSWORD_HASH, hash(new)).apply()
        return true
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Keys.PREFS, Context.MODE_PRIVATE)
}
