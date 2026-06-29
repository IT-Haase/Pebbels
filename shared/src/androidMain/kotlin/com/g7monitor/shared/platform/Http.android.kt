package com.g7monitor.shared.platform

import java.net.HttpURLConnection
import java.net.URL

actual fun httpPostJson(url: String, body: String, onResult: (Boolean, String) -> Unit) {
    Thread {
        var ok = false
        var info = ""
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = 15000; readTimeout = 15000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            ok = code in 200..299; info = "HTTP $code"
        } catch (t: Throwable) {
            info = t.message ?: "error"
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
        onResult(ok, info)
    }.start()
}
