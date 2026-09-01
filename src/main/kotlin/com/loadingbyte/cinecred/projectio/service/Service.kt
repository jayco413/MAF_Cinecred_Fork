package com.loadingbyte.cinecred.projectio.service

import com.dd.plist.NSDictionary
import com.dd.plist.PropertyListParser
import com.loadingbyte.cinecred.common.CONFIG_DIR
import com.loadingbyte.cinecred.common.l10n
import com.loadingbyte.cinecred.common.l10nQuoted
import com.loadingbyte.cinecred.projectio.Spreadsheet
import com.loadingbyte.cinecred.projectio.SpreadsheetFormat
import com.loadingbyte.cinecred.projectio.SpreadsheetLook
import org.apache.http.impl.EnglishReasonPhraseCatalog
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.extension
import kotlin.io.path.readLines
import kotlin.io.path.writeText


val SERVICE_CONFIG_DIR: Path = CONFIG_DIR.resolve("services")
val SERVICES: List<Service> = listOf(GoogleService, NextcloudService, WebDAVService, EtherCalcService)

private val ACCOUNT_LIST_LISTENERS = CopyOnWriteArrayList<() -> Unit>()

fun addAccountListListener(listener: () -> Unit) {
    ACCOUNT_LIST_LISTENERS.add(listener)
}

fun removeAccountListListener(listener: () -> Unit) {
    ACCOUNT_LIST_LISTENERS.remove(listener)
}

fun invokeAccountListListeners() {
    for (listener in ACCOUNT_LIST_LISTENERS)
        listener()
}


interface Service {

    val product: String
    val authorizer: String?
    val accountNeedsServer: Boolean
    val credentialsRequirement: CredentialsRequirement
    val uploadNeedsFilename: Boolean
    val uploadNeedsFormat: Boolean

    val accounts: List<Account>

    /** If [accountNeedsServer] is true, checks very quickly whether a server looks plausible for this service. */
    fun isServerPlausible(server: URI): Boolean = throw NotImplementedError()

    /** Doesn't throw, and instead returns an error. */
    fun addAccount(accountId: String, server: URI?, credentials: Credentials?): ServiceError?

    /** Doesn't throw, and instead returns an error. */
    fun removeAccount(account: Account): ServiceError?

    /**
     * Asynchronously watches the given link.
     * Doesn't throw, and instead returns an error or invokes the problem callback.
     */
    fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceResult<ServiceWatcher>

    enum class CredentialsRequirement { REQUIRED, OPTIONAL, UNUSED }

}


interface Account {

    val id: String
    val service: Service
    val server: URI? get() = null
    val credentials: Credentials? get() = null

    /** Doesn't throw, and instead returns an error. */
    fun upload(
        filename: String?, format: SpreadsheetFormat?, spreadsheet: Spreadsheet, look: SpreadsheetLook
    ): ServiceResult<URI>

}


data class Credentials(val username: String, val password: String)


interface ServiceWatcher {

    /** Asynchronously polls for changes. Doesn't throw, and instead invokes the problem callback. */
    fun poll()
    /** Once this method returns, it is guaranteed that no more calls to the [Callbacks] will be made. */
    fun cancel()

    interface Callbacks {
        fun content(spreadsheets: List<Spreadsheet>)
        fun problem(error: ServiceError)
    }

}


sealed class ServiceError(val message: String) {

    class Generic(message: String) :
        ServiceError(message)

    class ServiceNotResponsible(service: Service) :
        ServiceError(l10n("projectIO.service.serviceNotResponsible", service.product))

    class Unexpected(service: Service, code: Int) :
        ServiceError(
            EnglishReasonPhraseCatalog.INSTANCE.getReason(code, null)
                .let { l10n("projectIO.service.unexpected", service.product, if (it != null) "$code $it" else code) }
        )

    class Unreachable(service: Service) :
        ServiceError(l10n("projectIO.service.unreachable", service.product))

    class Unauthorized(service: Service) :
        ServiceError(l10n("projectIO.service.unauthorized", service.product))

    class SpreadsheetLinkUnrecognizable(service: Service, link: URI) :
        ServiceError(l10n("projectIO.service.spreadsheetLinkUnrecognizable", l10nQuoted(link), service.product))

    class SpreadsheetFileNotFound(service: Service, link: URI) :
        ServiceError(l10n("projectIO.service.spreadsheetFileNotFound", l10nQuoted(link), service.product))

    class SpreadsheetFileForbidden(service: Service) :
        ServiceError(l10n("projectIO.service.spreadsheetFileForbidden", service.authorizer ?: service.product))

    class SpreadsheetFileUnparsable(service: Service, filename: String) :
        ServiceError(l10n("projectIO.service.spreadsheetFileUnparsable", l10nQuoted(filename), service.product))

    class SpreadsheetFileAlreadyExists(service: Service, filename: String) :
        ServiceError(l10n("projectIO.service.spreadsheetFileAlreadyExists", l10nQuoted(filename), service.product))

}


sealed interface ServiceResult<T : Any> {
    data class Success<T : Any>(val value: T) : ServiceResult<T>
    data class Failure<T : Any>(val error: ServiceError) : ServiceResult<T>
}


const val WRITTEN_SERVICE_LINK_EXT = "url"
val SERVICE_LINK_EXTS = listOf("url", "webloc")

/** @throws Exception */
fun readServiceLink(file: Path): URI =
    when (val fileExt = file.extension) {
        "url" -> {
            val line = file.readLines().find { it.startsWith("URL=", ignoreCase = true) }
                ?: throw IllegalArgumentException("Missing URL= entry in .url file.")
            URI(line.substring(4))
        }
        "webloc" -> {
            val plist = PropertyListParser.parse(file)
            require(plist is NSDictionary) { "Top-level element in .webloc file must be an NSDictionary." }
            val rawURI = plist["URL"]?.toString()
                ?: throw IllegalArgumentException("Missing URL entry in .webloc file.")
            URI(rawURI)
        }
        else -> throw IllegalArgumentException("Not a link file extension: .$fileExt")
    }

/** @throws IOException */
fun writeServiceLink(file: Path, link: URI) {
    file.writeText("[InternetShortcut]\r\nURL=$link")
}
