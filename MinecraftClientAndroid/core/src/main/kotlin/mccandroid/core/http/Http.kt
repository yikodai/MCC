package mccandroid.core.http

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** HTTP 响应 */
class HttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
) {
    val isSuccess: Boolean get() = statusCode in 200..299
}

/** HTTP 请求失败（网络层错误，不含 4xx/5xx 状态码） */
class HttpException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 极简 HTTP 客户端。
 *
 * 桌面版 MCC 使用自带的 ProxiedWebRequest；安卓端直接使用 [HttpURLConnection]，
 * 这样系统代理、VPN、蜂窝网络切换都由系统统一处理。
 */
object Http {

    private const val DEFAULT_TIMEOUT_MS = 30_000
    const val USER_AGENT = "MCC-Android/0.1"

    fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): HttpResponse = request("GET", url, null, null, headers, timeoutMs)

    fun postForm(
        url: String,
        form: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): HttpResponse = request("POST", url, "application/x-www-form-urlencoded", form, headers, timeoutMs)

    fun postJson(
        url: String,
        json: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): HttpResponse = request("POST", url, "application/json", json, headers, timeoutMs)

    fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): HttpResponse = request("POST", url, contentType, body, headers, timeoutMs)

    private fun request(
        method: String,
        url: String,
        contentType: String?,
        body: String?,
        headers: Map<String, String>,
        timeoutMs: Int,
    ): HttpResponse {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", contentType ?: "text/plain")
                }
            }

            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }

            val status = connection.responseCode
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.use { String(it.readBytes(), StandardCharsets.UTF_8) } ?: ""
            return HttpResponse(status, responseBody, connection.headerFields ?: emptyMap())
        } catch (exception: IOException) {
            throw HttpException("请求 $url 失败：${exception.message}", exception)
        } finally {
            connection?.disconnect()
        }
    }
}