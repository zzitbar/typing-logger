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

    /** 排查用：把收到的文本变化事件原样写进 debug 目录 */
    const val DEBUG_EVENTS = "debug_events"

    /** 最后一次心跳时间（服务内 5 分钟一次，闹钟也会写） */
    const val HEARTBEAT_AT = "heartbeat_at"

    /** 上次“服务未开启”通知时间，用来节流 */
    const val WATCHDOG_NOTICE_AT = "watchdog_notice_at"
}
