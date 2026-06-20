package com.loadingbyte.cinecred.projectio.service

import com.formdev.flatlaf.util.SystemInfo
import com.google.gson.Gson
import com.loadingbyte.cinecred.common.LOGGER
import com.loadingbyte.cinecred.common.createFileSafely
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.nio.file.attribute.*
import java.nio.file.attribute.AclEntryPermission.*
import java.security.SecureRandom
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock
import kotlin.io.path.*


abstract class IndependentService : Service {

    protected abstract val accountsFile: Path
    protected abstract fun constructAccount(accountId: String, server: URI, credentials: Credentials?): Account
    protected abstract fun normalizeServer(server: URI): URI
    protected abstract fun verifyServer(server: URI, credentials: Credentials?): ServiceError?
    protected abstract fun watch(link: URI, callbacks: ServiceWatcher.Callbacks, candidateAccounts: List<Account>):
            ServiceResult<ServiceWatcher>

    final override val accountNeedsServer get() = true
    private val accountsLock = ReentrantLock()

    final override var accounts =
        if (accountsFile.notExists()) persistentListOf() else try {
            val entries = Gson().fromJson(deobfuscate(accountsFile.readBytes()), List::class.java) ?: emptyList<Any>()
            entries.mapNotNull { entry ->
                if (entry !is Map<*, *>) return@mapNotNull null
                val id = entry["id"] as? String ?: return@mapNotNull null
                val server = try {
                    normalizeServer(URI(entry["server"] as? String ?: return@mapNotNull null))
                } catch (_: URISyntaxException) {
                    return@mapNotNull null
                }
                if (!isServerPlausible(server)) return@mapNotNull null
                val username = entry["username"] as? String
                val password = entry["password"] as? String
                val credentials = if (username != null && password != null) Credentials(username, password) else null
                if (credentialsRequirement == Service.CredentialsRequirement.REQUIRED && credentials == null)
                    return@mapNotNull null
                constructAccount(id, server, credentials)
            }.toPersistentList()
        } catch (e: Exception) {
            LOGGER.error("Could read the accounts file at '{}'.", accountsFile, e)
            persistentListOf()
        }
        private set

    private fun writeAccountsFile() {
        try {
            accountsFile.createFileSafely()

            // Adapted from: com.google.api.client.util.store.FileDataStoreFactory
            if (SystemInfo.isWindows) {
                val permissions = setOf(
                    READ_DATA, WRITE_DATA, APPEND_DATA, READ_NAMED_ATTRS, WRITE_NAMED_ATTRS,
                    READ_ATTRIBUTES, WRITE_ATTRIBUTES, DELETE, READ_ACL, WRITE_ACL, WRITE_OWNER, SYNCHRONIZE
                )
                val owner = accountsFile.fileAttributesView<FileOwnerAttributeView>().owner
                val entry = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW).setPermissions(permissions).setPrincipal(owner).build()
                accountsFile.fileAttributesView<AclFileAttributeView>().acl = listOf(entry)
            } else {
                val permissions = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                accountsFile.setPosixFilePermissions(permissions)
            }

            val entries = accounts.map { account ->
                mutableMapOf("id" to account.id, "server" to account.server.toString()).apply {
                    account.credentials?.let { credentials ->
                        putAll(arrayOf("username" to credentials.username, "password" to credentials.password))
                    }
                }
            }
            accountsFile.writeBytes(obfuscate(Gson().toJson(entries)))
        } catch (e: Exception) {
            LOGGER.error("Could write to the accounts file at '{}'.", accountsFile, e)
        }
    }

    private fun obfuscate(plaintext: String): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        return iv + setupCipher(Cipher.ENCRYPT_MODE, iv).doFinal(plaintext.toByteArray())
    }

    private fun deobfuscate(ciphertext: ByteArray): String {
        if (ciphertext.size <= 12) return ""
        val iv = ciphertext.copyOf(12)
        return String(setupCipher(Cipher.DECRYPT_MODE, iv).doFinal(ciphertext.copyOfRange(12, ciphertext.size)))
    }

    private fun setupCipher(opmode: Int, iv: ByteArray): Cipher {
        val rawKey = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec("NEBBNMMPOLJBOMNP".toCharArray(), "GPCAMPMGNJHLLOFN".toByteArray(), 65536, 256))
        val key = SecretKeySpec(rawKey.encoded, "AES")
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(opmode, key, GCMParameterSpec(128, iv)) }
    }

    override fun isServerPlausible(server: URI): Boolean =
        (server.scheme == "http" || server.scheme == "https") && !server.isOpaque &&
                !server.authority.isNullOrBlank() && server.query.isNullOrEmpty() && server.fragment.isNullOrEmpty()

    final override fun addAccount(accountId: String, server: URI?, credentials: Credentials?): ServiceError? {
        val server = normalizeServer(server!!)
        if (!isServerPlausible(server))
            return ServiceError.ServiceNotResponsible(this)
        verifyServer(server, credentials)?.let { return it }
        if (credentialsRequirement == Service.CredentialsRequirement.REQUIRED && credentials == null)
            return ServiceError.Unauthorized(this)

        val account = constructAccount(accountId, server, credentials)
        accountsLock.withLock {
            if (accounts.any { it.id == accountId })
                return ServiceError.Generic("Account ID already in use.")
            accounts = accounts.add(account)
            writeAccountsFile()
        }

        invokeAccountListListeners()
        return null
    }

    final override fun removeAccount(account: Account): ServiceError? {
        if (account.service != this || account !in accounts)
            return ServiceError.ServiceNotResponsible(this)

        accountsLock.withLock {
            accounts = accounts.remove(account)
            if (accounts.isEmpty())
                try {
                    accountsFile.deleteIfExists()
                } catch (e: Exception) {
                    LOGGER.error("Could not delete the accounts file at '{}'.", accountsFile, e)
                }
            else
                writeAccountsFile()
        }

        invokeAccountListListeners()
        return null
    }

    final override fun watch(link: URI, callbacks: ServiceWatcher.Callbacks): ServiceResult<ServiceWatcher> {
        if (link.scheme != "http" && link.scheme != "https" || link.isOpaque ||
            link.authority.isNullOrBlank() || !link.path.isEmpty() && !link.path.startsWith('/')
        )
            return ServiceResult.Failure(ServiceError.ServiceNotResponsible(this))
        val candidateAccounts = accounts.filter { account ->
            val server = account.server!!
            link.scheme == server.scheme && link.authority == server.authority && link.path.startsWith(server.path)
        }
        return watch(link, callbacks, candidateAccounts)
    }

}
