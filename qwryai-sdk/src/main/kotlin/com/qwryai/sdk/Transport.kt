package com.qwryai.sdk

import android.content.Context
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal class HttpRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)
internal class HttpResponse(val status: Int, val body: InputStream)

internal fun interface Http {
    fun execute(request: HttpRequest): HttpResponse
}

internal class UrlConnectionHttp : Http {
    override fun execute(request: HttpRequest): HttpResponse {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        connection.requestMethod = request.method
        connection.connectTimeout = 15_000
        // An answer may pause while the agent works; the server ends a turn itself after ten minutes.
        connection.readTimeout = 600_000
        request.headers.forEach(connection::setRequestProperty)
        request.body?.let { body ->
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        return HttpResponse(status, (if (status in 200..299) connection.inputStream else connection.errorStream) ?: ByteArrayInputStream(ByteArray(0)))
    }
}

/** What the SDK keeps between launches. */
internal interface Store {
    /** A random ID made once per install. Anonymous conversations belong to it. */
    val deviceId: String
    var token: String?
    var userId: String?
}

internal class PreferencesStore(context: Context) : Store {
    private val preferences = context.applicationContext.getSharedPreferences("qwryai_sdk_prefs", Context.MODE_PRIVATE)

    override val deviceId: String = preferences.getString("device_id", null)
        ?: UUID.randomUUID().toString().also { preferences.edit().putString("device_id", it).apply() }

    override var token: String?
        get() = preferences.getString("identity_token", null)
        set(value) = preferences.edit().putString("identity_token", value).apply()

    override var userId: String?
        get() = preferences.getString("user_id", null)
        set(value) = preferences.edit().putString("user_id", value).apply()
}
