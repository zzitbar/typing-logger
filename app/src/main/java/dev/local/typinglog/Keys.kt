package dev.local.typinglog

/** SharedPreferences 的键，统一放一处，避免拼错。 */
object Keys {
    const val PREFS = "config"

    const val ENABLED = "enabled"

    const val PASSWORD_HASH = "pwd_hash"

    const val FEISHU_ENABLED = "feishu_enabled"
    const val FEISHU_WEBHOOK = "feishu_webhook"
    const val FEISHU_SECRET = "feishu_secret"
    const val FEISHU_LAST = "feishu_last"
}
