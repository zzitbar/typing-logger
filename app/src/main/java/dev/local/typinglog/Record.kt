package dev.local.typinglog

import org.json.JSONObject

/** 一条“上屏记录”：某时刻在某个应用里最终落下的文字。 */
data class Record(
    val ts: Long,
    val pkg: String,
    val app: String,
    val text: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ts", ts)
        .put("pkg", pkg)
        .put("app", app)
        .put("text", text)
}
