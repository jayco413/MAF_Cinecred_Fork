package com.loadingbyte.cinecred.projectio.service

import com.loadingbyte.cinecred.common.*
import com.loadingbyte.cinecred.projectio.SPREADSHEET_FORMATS
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import org.springframework.http.ContentDisposition
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.jvm.optionals.getOrNull


inline fun <T : Any> ServiceResult<T>.abort(action: (ServiceError) -> Nothing): T = when (this) {
    is ServiceResult.Success -> value
    is ServiceResult.Failure -> action(error)
}


fun urlEnc(s: String): String =
    URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")


fun isUnreachableHttpStatus(code: Int): Boolean =
    when (code) {
        408, 429, 500, 502, 503, 504 -> true
        else -> false
    }

fun isUnauthorizedHttpStatus(code: Int): Boolean =
    code == 401 || code == 403


fun HttpRequest.Builder.basicAuth(credentials: Credentials?): HttpRequest.Builder =
    if (credentials == null) this else basicAuth(credentials.username, credentials.password)


fun send(req: HttpRequest, service: Service): ServiceResult<HttpResponse<Void>> =
    send(req, HttpResponse.BodyHandlers.discarding(), service)

fun sendForString(req: HttpRequest, service: Service): ServiceResult<HttpResponse<String>> =
    send(req, HttpResponse.BodyHandlers.ofString(), service)

fun sendForStream(req: HttpRequest, service: Service): ServiceResult<HttpResponse<InputStream>> =
    send(req, HttpResponse.BodyHandlers.ofInputStream(), service)

fun <T> send(
    req: HttpRequest, bodyHandler: HttpResponse.BodyHandler<T>, service: Service
): ServiceResult<HttpResponse<T>> {
    val resp = try {
        GLOBAL_HTTP_CLIENT.send(req, bodyHandler)
    } catch (e: InterruptedException) {
        throw e
    } catch (_: IOException) {
        return ServiceResult.Failure(ServiceError.Unreachable(service))
    } catch (e: Exception) {
        LOGGER.error("Error while sending HTTP request.", e)
        return ServiceResult.Failure(ServiceError.Generic("Error while sending HTTP request: ${e.userNotification}"))
    }
    if (isUnreachableHttpStatus(resp.statusCode()))
        return ServiceResult.Failure(ServiceError.Unreachable(service))
    if (isUnauthorizedHttpStatus(resp.statusCode()))
        return ServiceResult.Failure(ServiceError.Unauthorized(service))
    return ServiceResult.Success(resp)
}


fun uploadToWebDAV(
    uri: URI,
    credentials: Credentials?,
    filename: String,
    format: SpreadsheetFormat,
    spreadsheet: Spreadsheet,
    look: SpreadsheetLook,
    service: Service
): ServiceError? {
    // Make sure that the file doesn't exist yet.
    var req = httpRequestBuilder(uri)
        .basicAuth(credentials)
        .method("HEAD", HttpRequest.BodyPublishers.noBody())
        .build()
    var resp = send(req, service).abort { return it }
    if (resp.statusCode() == 200)
        return ServiceError.SpreadsheetFileAlreadyExists(service, filename)
    if (resp.statusCode() != 404)
        return ServiceError.Unexpected(service, resp.statusCode())

    val baos = ByteArrayOutputStream()
    try {
        format.write(baos, spreadsheet, look)
    } catch (e: Exception) {
        LOGGER.error("Failed to serialize spreadsheet.", e)
        return ServiceError.Generic("Failed to serialize spreadsheet: ${e.userNotification}")
    }

    req = httpRequestBuilder(uri)
        .basicAuth(credentials)
        .PUT(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
        // As a second line of defense, make sure again that the file doesn't exist yet.
        .header("If-None-Match", "*")
        .build()
    resp = send(req, service).abort { return it }
    if (resp.statusCode() == 412)
        return ServiceError.SpreadsheetFileAlreadyExists(service, filename)
    if (resp.statusCode() != 201)
        return ServiceError.Unexpected(service, resp.statusCode())

    return null
}


class SimpleDownloadWatcher(
    private val uri: URI,
    candidateCredentials: List<Credentials>,
    private val format: SpreadsheetFormat?,
    callbacks: ServiceWatcher.Callbacks,
    private val service: Service
) : ServiceWatcher {

    private val jobSlot = JobSlot()
    @Volatile private var candidateCredentials = candidateCredentials.ifEmpty { listOf(null) }
    @Volatile private var callbacks: ServiceWatcher.Callbacks? = callbacks

    override fun poll() {
        callbacks ?: return
        jobSlot.submit {
            doPoll()
            // Simple rate limiting.
            Thread.sleep(1000)
        }
    }

    private fun doPoll() {
        val resp = makeRequest() ?: return
        if (resp.statusCode().let { it == 404 || it == 410 }) {
            callbacks?.problem(ServiceError.SpreadsheetFileNotFound(service, uri))
            return
        }
        if (resp.statusCode() != 200) {
            callbacks?.problem(ServiceError.Unexpected(service, resp.statusCode()))
            return
        }

        val filename = resp.headers().firstValue("Content-Disposition").getOrNull()?.let { cd ->
            try {
                ContentDisposition.parse(cd).filename
            } catch (_: Exception) {
                null
            }
        } ?: uri.path.trimEnd('/').substringAfterLast('/')

        var format = this.format
        if (format == null) {
            val fileExt = filename.substringAfterLast('.')
            format = SPREADSHEET_FORMATS.find { fmt -> fmt.fileExt.equals(fileExt, ignoreCase = true) }
            if (format == null) {
                callbacks?.problem(ServiceError.SpreadsheetFileUnparsable(service, filename))
                return
            }
        }

        val spreadsheets = try {
            format.read(resp.body(), l10n("project.template.spreadsheetName"))
        } catch (_: Exception) {
            callbacks?.problem(ServiceError.SpreadsheetFileUnparsable(service, filename))
            return
        }
        callbacks?.content(spreadsheets)
    }

    private fun makeRequest(): HttpResponse<InputStream>? {
        for (credentials in candidateCredentials) {
            val req = httpRequestBuilder(uri).basicAuth(credentials).build()
            when (val respResult = sendForStream(req, service)) {
                is ServiceResult.Success -> {
                    // In case of success, retain only the credentials that granted us access.
                    if (candidateCredentials.size > 1)
                        candidateCredentials = listOf(credentials)
                    return respResult.value
                }
                is ServiceResult.Failure ->
                    // If the credentials are invalid, don't abort yet and instead try the other available credentials.
                    if (respResult.error !is ServiceError.Unauthorized) {
                        callbacks?.problem(respResult.error)
                        return null
                    }
            }
        }
        callbacks?.problem(ServiceError.Unauthorized(service))
        return null
    }

    override fun cancel() {
        callbacks = null
    }

}
