package com.loadingbyte.cinecred.projectio.service

import com.loadingbyte.cinecred.common.httpRequestBuilder
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Path


object WebDAVService : IndependentService() {

    override val product get() = "WebDAV"
    override val authorizer get() = null
    override val credentialsRequirement get() = Service.CredentialsRequirement.OPTIONAL
    override val uploadNeedsFilename get() = true
    override val uploadNeedsFormat get() = true
    override val accountsFile: Path get() = SERVICE_CONFIG_DIR.resolve("webdav")

    override fun constructAccount(accountId: String, server: URI, credentials: Credentials?): Account =
        WebDAVAccount(accountId, server, credentials)

    override fun normalizeServer(server: URI): URI {
        val rawPath = server.rawPath
        return if (rawPath.endsWith('/')) server else server.resolve("$rawPath/")
    }

    override fun verifyServer(server: URI, credentials: Credentials?): ServiceError? {
        val req = httpRequestBuilder(server)
            .basicAuth(credentials)
            .method("PROPFIND", HttpRequest.BodyPublishers.noBody())
            .build()
        val resp = send(req, this).abort { return it }
        return if (resp.statusCode() == 207) null else ServiceError.ServiceNotResponsible(this)
    }

    override fun watch(
        link: URI, callbacks: ServiceWatcher.Callbacks, candidateAccounts: List<Account>
    ): ServiceResult<ServiceWatcher> {
        if (candidateAccounts.isEmpty() && verifyServer(link, credentials = null) != null)
            return ServiceResult.Failure(ServiceError.ServiceNotResponsible(this))
        val candidateCredentials = candidateAccounts.mapNotNull(Account::credentials)
        val watcher = SimpleDownloadWatcher(link, candidateCredentials, format = null, callbacks, this)
        watcher.poll()
        return ServiceResult.Success(watcher)
    }


    private class WebDAVAccount(
        override val id: String,
        override val server: URI,
        override val credentials: Credentials?
    ) : Account {

        override val service get() = WebDAVService

        override fun upload(
            filename: String?, format: SpreadsheetFormat?, spreadsheet: Spreadsheet, look: SpreadsheetLook
        ): ServiceResult<URI> {
            if (filename.isNullOrBlank())
                return ServiceResult.Failure(ServiceError.Generic("Filename is blank."))
            val uploadURI = server.resolve(urlEnc(filename))
            uploadToWebDAV(uploadURI, credentials, filename, format!!, spreadsheet, look, service)
                ?.let { return ServiceResult.Failure(it) }
            return ServiceResult.Success(uploadURI)
        }

    }

}
