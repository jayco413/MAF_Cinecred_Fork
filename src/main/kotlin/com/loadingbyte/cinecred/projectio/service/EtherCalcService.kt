package com.loadingbyte.cinecred.projectio.service

import com.loadingbyte.cinecred.common.httpRequestBuilder
import com.loadingbyte.cinecred.projectio.OdsFormat
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import java.net.URI
import java.net.URISyntaxException
import java.net.http.HttpRequest
import java.nio.file.Path


object EtherCalcService : IndependentService() {

    override val product get() = "EtherCalc"
    override val authorizer get() = null
    override val credentialsRequirement get() = Service.CredentialsRequirement.OPTIONAL
    override val uploadNeedsFilename get() = false
    override val uploadNeedsFormat get() = false

    override val accountsFile: Path get() = SERVICE_CONFIG_DIR.resolve("ethercalc")

    override fun constructAccount(accountId: String, server: URI, credentials: Credentials?): Account =
        EtherCalcAccount(accountId, server, credentials)

    override fun normalizeServer(server: URI): URI {
        val rawPath = server.rawPath
        return if (rawPath.endsWith('/')) server else server.resolve("$rawPath/")
    }

    override fun verifyServer(server: URI, credentials: Credentials?): ServiceError? {
        for (path in arrayOf("static/ethercalc.js", "static/socialcalc.js")) {
            val req = httpRequestBuilder(server.resolve(path)).basicAuth(credentials).build()
            val resp = sendForString(req, this).abort { return it }
            if (resp.statusCode() == 200)
                return null
        }
        return ServiceError.ServiceNotResponsible(this)
    }

    override fun watch(
        link: URI, callbacks: ServiceWatcher.Callbacks, candidateAccounts: List<Account>
    ): ServiceResult<ServiceWatcher> {
        if (candidateAccounts.isEmpty() && verifyServer(link, credentials = null) != null)
            return ServiceResult.Failure(ServiceError.ServiceNotResponsible(this))
        val path = link.rawPath
        val idx = path.lastIndexOf('/')
        if (idx == -1 || idx == path.lastIndex)
            return ServiceResult.Failure(ServiceError.SpreadsheetLinkUnrecognizable(this, link))
        // Note that we deliberately choose ODS over CSV because EtherCalc produces weird artifacts in the latter,
        // like prefixing every @ at the start of a cell with an apostrophe.
        val downloadURI = link.resolve("${path.substring(0, idx)}/_/${path.substring(idx + 1)}/ods")
        val candidateCredentials = candidateAccounts.mapNotNull(Account::credentials)
        val watcher = SimpleDownloadWatcher(downloadURI, candidateCredentials, OdsFormat, callbacks, this)
        watcher.poll()
        return ServiceResult.Success(watcher)
    }


    private class EtherCalcAccount(
        override val id: String,
        override val server: URI,
        override val credentials: Credentials?
    ) : Account {

        override val service get() = EtherCalcService

        override fun upload(
            filename: String?, format: SpreadsheetFormat?, spreadsheet: Spreadsheet, look: SpreadsheetLook
        ): ServiceResult<URI> {
            val body = StringBuilder()
            body.appendLine(
                """socialcalc:version:1.0
MIME-Version: 1.0
Content-Type: multipart/mixed; boundary=SocialCalcSpreadsheetControlSave
--SocialCalcSpreadsheetControlSave
Content-type: text/plain; charset=UTF-8

version:1.0
part:sheet
--SocialCalcSpreadsheetControlSave
Content-type: text/plain; charset=UTF-8

version:1.5"""
            )
            val fontNumbers = HashMap<FontKey, Int>()
            val rSty = StringBuilder()
            for (record in spreadsheet) {
                val rowLook = look.rowLooks[record.recordNo]
                for (col in 0..<spreadsheet.numColumns) {
                    if (rowLook != null) {
                        if (rowLook.borderBottom)
                            rSty.append(":b:::1:")
                        if (rowLook.fontSize != -1 || rowLook.bold || rowLook.italic) {
                            val fontKey = FontKey(rowLook.fontSize, rowLook.bold, rowLook.italic)
                            rSty.append(":f:").append(fontNumbers.computeIfAbsent(fontKey) { fontNumbers.size + 1 })
                        }
                    }
                    val cell = record.cells.getOrElse(col) { "" }
                    if (cell.isNotEmpty() || rSty.isNotEmpty())
                        body.append("cell:").append('A' + col).append(record.recordNo + 1).append(":t:")
                            .append(cell.replace("\n", "\\n").replace(":", "\\c"))
                            .appendLine(rSty)
                    rSty.clear()
                }
            }
            for ((col, colWidth) in look.colWidths.withIndex())
                body.append("col:").append('A' + col).append(":w:").appendLine(colWidth * 5)
            body.appendLine("sheet:layout:1")
                .appendLine("border:1:1px solid rgb(0,0,0)")
            for ((fontKey, fontNumber) in fontNumbers) {
                body.append("font:").append(fontNumber).append(':')
                if (!fontKey.bold && !fontKey.italic) body.append('*') else
                    body.append(if (fontKey.italic) "italic" else "normal").append(' ')
                        .append(if (fontKey.bold) "bold" else "normal")
                body.append(' ')
                if (fontKey.size == -1) body.append('*') else body.append(fontKey.size).append("pt")
                body.appendLine(" *")
            }
            body.appendLine("layout:1:padding:* * * *;vertical-align:bottom;")
                .appendLine("--SocialCalcSpreadsheetControlSave--")

            val req = httpRequestBuilder(server.resolve("_"))
                .basicAuth(credentials)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .header("Content-Type", "text/x-socialcalc")
                .build()
            val resp = sendForString(req, service).abort { return ServiceResult.Failure(it) }
            if (resp.statusCode() != 201)
                return ServiceResult.Failure(ServiceError.Unexpected(service, resp.statusCode()))
            val name = resp.body().substringAfterLast("/")
            val uri = try {
                server.resolve(URI(name))
            } catch (_: URISyntaxException) {
                return ServiceResult.Failure(ServiceError.Generic("Received malformed spreadsheet name: $name"))
            }

            return ServiceResult.Success(uri)
        }

        private data class FontKey(val size: Int, val bold: Boolean, val italic: Boolean)

    }

}
