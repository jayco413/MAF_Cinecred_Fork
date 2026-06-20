package com.loadingbyte.cinecred.projectio.service

import com.google.gson.Gson
import com.loadingbyte.cinecred.common.httpRequest
import com.loadingbyte.cinecred.common.httpRequestBuilder
import com.loadingbyte.cinecred.common.userNotification
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import org.w3c.dom.Node
import java.io.StringWriter
import java.net.URI
import java.net.http.HttpRequest
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory


object NextcloudService : IndependentService() {

    override val product get() = "Nextcloud"
    override val authorizer get() = null
    override val credentialsRequirement get() = Service.CredentialsRequirement.REQUIRED
    override val uploadNeedsFilename get() = true
    override val uploadNeedsFormat get() = true
    override val accountsFile: Path get() = SERVICE_CONFIG_DIR.resolve("nextcloud")

    override fun constructAccount(accountId: String, server: URI, credentials: Credentials?): Account =
        NextcloudAccount(accountId, server, credentials!!)

    override fun normalizeServer(server: URI): URI {
        val rawPath = server.rawPath
        return when {
            rawPath.endsWith(".php/") -> server.resolve(rawPath.dropLast(1))
            !rawPath.endsWith(".php") && !rawPath.endsWith('/') -> server.resolve("$rawPath/")
            else -> server
        }
    }

    override fun verifyServer(server: URI, credentials: Credentials?): ServiceError? {
        var req = httpRequest(server.resolve("status.php"))
        val resp = sendForString(req, this).abort { return it }
        // Note: Including ownCloud support in this service is not trivial because its current "Infinite Scale" product
        // supports neither HTTP basic auth with the user's credentials, nor an easy way for the user to generate
        // application passwords.
        if (resp.statusCode() != 200 || Gson().fromJson(resp.body(), Map::class.java)["productname"] != "Nextcloud")
            return ServiceError.ServiceNotResponsible(this)

        if (credentials != null) {
            req = httpRequestBuilder(server.resolve("remote.php/dav/")).basicAuth(credentials).build()
            if (send(req, this).abort { return it }.statusCode() != 200)
                return ServiceError.ServiceNotResponsible(this)
        }

        return null
    }

    override fun watch(
        link: URI, callbacks: ServiceWatcher.Callbacks, candidateAccounts: List<Account>
    ): ServiceResult<ServiceWatcher> {
        val path = link.path
        val i = path.lastIndexOf('/')
        if (i <= 0 || i == path.lastIndex)
            return ServiceResult.Failure(
                if (candidateAccounts.isEmpty()) ServiceError.ServiceNotResponsible(this)
                else ServiceError.SpreadsheetLinkUnrecognizable(this, link)
            )
        val prefix = path.substring(0, i)
        val suffix = path.substring(i + 1)

        val f = prefix.endsWith("/f")
        val a = prefix.endsWith("/apps/files/files")
        val s = prefix.endsWith("/s")
        if (!(f || a || s) ||
            (f || a) && !suffix.all { c -> c in '0'..'9' } ||
            s && !suffix.all { c -> c in '0'..'9' || c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '-' }
        )
            return ServiceResult.Failure(
                if (candidateAccounts.isEmpty()) ServiceError.ServiceNotResponsible(this)
                else ServiceError.SpreadsheetLinkUnrecognizable(this, link)
            )

        if (candidateAccounts.isEmpty())
            if (verifyServer(normalizeServer(link.resolve(if (a) "../../.." else "..")), credentials = null) != null)
                return ServiceResult.Failure(ServiceError.ServiceNotResponsible(this))
            else if (f || a)
                return ServiceResult.Failure(ServiceError.SpreadsheetFileForbidden(this))

        val watcher: SimpleDownloadWatcher
        if (f || a) {
            // If multiple accounts are permissible for the link, find the one that the linked file actually belongs to.
            var dlURI: URI? = null
            var dlAccount: NextcloudAccount? = null
            for ((idx, account) in candidateAccounts.withIndex()) {
                account as NextcloudAccount
                dlURI = getDownloadURIFromId(account.server, account.credentials, suffix, link)
                    .abort { if (idx < candidateAccounts.lastIndex) continue else return ServiceResult.Failure(it) }
                dlAccount = account
                break
            }
            watcher = SimpleDownloadWatcher(dlURI!!, listOf(dlAccount!!.credentials), format = null, callbacks, this)
        } else {
            val dlURI = normalizeServer(link.resolve("..")).resolve("public.php/dav/files/$suffix")
            watcher = SimpleDownloadWatcher(dlURI, emptyList(), format = null, callbacks, this)
        }
        watcher.poll()
        return ServiceResult.Success(watcher)
    }

    private fun getDownloadURIFromId(server: URI, credentials: Credentials, id: String, link: URI): ServiceResult<URI> {
        val d = "DAV:"
        val oc = "http://owncloud.org/ns"
        val doc = DocumentBuilderFactory.newNSInstance().newDocumentBuilder().domImplementation
            .createDocument(d, "searchrequest", null)
        val sr = doc.documentElement
        sr.appendChild(doc.createElementNS(d, "basicsearch").apply {
            appendChild(doc.createElementNS(d, "select").apply {
                appendChild(doc.createElementNS(d, "prop").apply {
                    // We don't actually need this property, but the WebDAV spec requires us to select something.
                    appendChild(doc.createElementNS(d, "displayname"))
                })
            })
            appendChild(doc.createElementNS(d, "from").apply {
                appendChild(doc.createElementNS(d, "scope").apply {
                    appendChild(doc.createElementNS(d, "href").apply {
                        textContent = "/files/${urlEnc(credentials.username)}"
                    })
                    appendChild(doc.createElementNS(d, "depth").apply {
                        textContent = "infinity"
                    })
                })
            })
            appendChild(doc.createElementNS(d, "where").apply {
                appendChild(doc.createElementNS(d, "eq").apply {
                    appendChild(doc.createElementNS(d, "prop").apply {
                        appendChild(doc.createElementNS(oc, "oc:fileid"))
                    })
                    appendChild(doc.createElementNS(d, "literal").apply {
                        textContent = id
                    })
                })
            })
        })
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(writer))
        val reqBody = writer.toString()

        val req = httpRequestBuilder(server.resolve("remote.php/dav/"))
            .basicAuth(credentials)
            .method("SEARCH", HttpRequest.BodyPublishers.ofString(reqBody))
            .header("Content-Type", "text/xml")
            .build()
        val resp = sendForStream(req, this).abort { return ServiceResult.Failure(it) }
        if (resp.statusCode() != 207)
            return ServiceResult.Failure(ServiceError.Unexpected(this, resp.statusCode()))
        try {
            val respDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resp.body())
            val hrefNode = XPathFactory.newInstance().newXPath().compile("/multistatus/response/href")
                .evaluate(respDoc, XPathConstants.NODE) as Node
            return ServiceResult.Success(server.resolve(hrefNode.textContent))
        } catch (_: Exception) {
            return ServiceResult.Failure(ServiceError.SpreadsheetFileForbidden(this))
        }
    }


    private class NextcloudAccount(
        override val id: String,
        override val server: URI,
        override val credentials: Credentials
    ) : Account {

        override val service get() = NextcloudService

        override fun upload(
            filename: String?, format: SpreadsheetFormat?, spreadsheet: Spreadsheet, look: SpreadsheetLook
        ): ServiceResult<URI> {
            if (filename.isNullOrBlank())
                return ServiceResult.Failure(ServiceError.Generic("Filename is blank."))

            val uploadURI = server.resolve("remote.php/dav/files/${urlEnc(credentials.username)}/${urlEnc(filename)}")
            uploadToWebDAV(uploadURI, credentials, filename, format!!, spreadsheet, look, service)
                ?.let { return ServiceResult.Failure(it) }

            val reqBody = """<?xml version="1.0" encoding="UTF-8"?>
                    <propfind xmlns="DAV:" xmlns:oc="http://owncloud.org/ns"><prop><oc:fileid/></prop></propfind>"""
            val req = httpRequestBuilder(uploadURI)
                .basicAuth(credentials)
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(reqBody))
                .header("Content-Type", "text/xml")
                .build()
            val resp = sendForStream(req, service).abort { return ServiceResult.Failure(it) }
            if (resp.statusCode() != 207)
                return ServiceResult.Failure(ServiceError.Unexpected(service, resp.statusCode()))
            try {
                val respDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resp.body())
                val idNode = XPathFactory.newInstance().newXPath().compile("/multistatus/response/propstat/prop/fileid")
                    .evaluate(respDoc, XPathConstants.NODE) as Node
                return ServiceResult.Success(server.resolve("/index.php/f/${idNode.textContent}"))
            } catch (e: Exception) {
                return ServiceResult.Failure(ServiceError.Generic("Could not extract link: ${e.userNotification}"))
            }
        }

    }

}
