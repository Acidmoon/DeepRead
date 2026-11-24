package com.vibecoding.reader.data.update

import com.vibecoding.reader.domain.update.GithubRepo
import com.vibecoding.reader.domain.update.TrustedUpdateUrls
import com.vibecoding.reader.domain.update.UpdateError
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

class UrlConnectionUpdateHttpClient(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 20_000
) : UpdateHttpClient {

    override fun <T> exchange(
        request: UpdateHttpRequest,
        policy: UpdateRedirectPolicy,
        repo: GithubRepo,
        maxBytes: Long,
        consume: (status: Int, headers: Map<String, String>, finalUrl: String, body: InputStream) -> T
    ): T {
        if (!UpdateRedirector.allow(request.url, policy, repo)) {
            throw UpdateError.Security("请求地址不在允许范围内")
        }
        var current = request.url
        var hop = 0
        while (true) {
            val conn = open(current, request.headers)
            try {
                val status = try {
                    conn.responseCode
                } catch (e: SocketTimeoutException) {
                    throw UpdateError.Timeout()
                } catch (e: IOException) {
                    throw UpdateError.Network(e.message ?: "网络不可用")
                }
                val headers = headerMap(conn)
                if (status in REDIRECT_STATUS) {
                    hop++
                    if (hop > TrustedUpdateUrls.MAX_REDIRECTS) {
                        throw UpdateError.Security("重定向次数过多")
                    }
                    val location = headers["location"]
                        ?: throw UpdateError.Security("重定向缺少 Location")
                    current = UpdateRedirector.nextUrl(current, location, policy, repo)
                    continue
                }
                val raw = if (status >= 400) conn.errorStream ?: conn.inputStream else conn.inputStream
                val stream = raw ?: ByteArrayInputStream(ByteArray(0))
                return consume(status, headers, current, LimitedInputStream(stream, maxBytes))
            } catch (e: UpdateError) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw UpdateError.Timeout()
            } catch (e: IOException) {
                throw UpdateError.Network(e.message ?: "网络不可用")
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection)
        conn.instanceFollowRedirects = false
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.doInput = true
        headers.forEach { (k, v) ->
            if (k.equals("Authorization", ignoreCase = true)) return@forEach
            conn.setRequestProperty(k, v)
        }
        return conn
    }

    private fun headerMap(conn: HttpURLConnection): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, values) in conn.headerFields) {
            if (key == null || values.isNullOrEmpty()) continue
            out[key.lowercase(Locale.US)] = values.last()
        }
        return out
    }

    companion object {
        private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)
    }
}

internal class LimitedInputStream(
    private val wrapped: InputStream,
    private val maxBytes: Long
) : InputStream() {
    private var seen = 0L

    override fun read(): Int {
        val b = wrapped.read()
        if (b >= 0) account(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = wrapped.read(b, off, len)
        if (n > 0) account(n.toLong())
        return n
    }

    override fun close() = wrapped.close()

    private fun account(n: Long) {
        seen += n
        if (seen > maxBytes) throw UpdateError.Security("响应超过允许的大小")
    }
}
