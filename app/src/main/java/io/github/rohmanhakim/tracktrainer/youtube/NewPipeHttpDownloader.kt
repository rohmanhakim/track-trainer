package io.github.rohmanhakim.tracktrainer.youtube

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.List
import java.util.Map
import javax.annotation.Nonnull
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException

/**
 * Minimal [Downloader] backed by [HttpURLConnection]. NewPipe uses this to fetch YouTube pages
 * (and signature player code) during extraction.
 *
 * No OkHttp dependency for the spike — plain HttpURLConnection keeps the dependency surface tiny.
 */
class NewPipeHttpDownloader : Downloader() {
    private val userAgent = "NewPipeExtractor/0.26.5"

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(@Nonnull request: Request): Response {
        val connection = URL(request.url()).openConnection() as HttpURLConnection
        connection.requestMethod = request.httpMethod()
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.doInput = true
        connection.addRequestProperty("User-Agent", userAgent)
        connection.addRequestProperty("Accept-Language", "en-US,en;q=0.9")

        request.headers().forEach { (name, values) ->
            values.forEach { connection.addRequestProperty(name, it) }
        }

        val responseBody = readBody(connection)
        val headers = connection.headerFields
        return Response(
            connection.responseCode,
            connection.responseMessage,
            headers,
            responseBody,
            connection.url.toString(),
        )
    }

    private fun readBody(connection: HttpURLConnection): String {
        val inputStream: InputStream? = try {
            connection.inputStream
        } catch (e: IOException) {
            // 4xx / 5xx responses surface via the error stream.
            connection.errorStream
        }
        return inputStream?.bufferedReader()?.use { it.readText() } ?: ""
    }
}
