package dev.local.typinglog

import java.util.UUID

/**
 * 首页隐藏入口验证通过后，用一个一次性口令放行日志页，
 * 避免刚输完密码又被要求输一次；外部应用拿不到这个口令。
 */
object Auth {

    private const val TTL_MS = 30_000L

    private var nonce: String? = null
    private var grantedAt = 0L

    fun grant(): String {
        val value = UUID.randomUUID().toString()
        nonce = value
        grantedAt = System.currentTimeMillis()
        return value
    }

    fun consume(candidate: String?): Boolean {
        val expected = nonce ?: return false
        if (candidate != expected) return false
        nonce = null
        return System.currentTimeMillis() - grantedAt <= TTL_MS
    }
}
