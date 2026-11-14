package dev.pocketshare.tunnel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Resolves the bundled cloudflared executable and builds a minimal, private runtime environment.
 * The binary ships as `libcloudflared.so` so Android extracts it to the app's native library
 * directory, which is one of the few places where executing a file is allowed.
 */
class CloudflaredRuntime(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val filesRoot = File(appContext.filesDir, "cloudflared")
    private val cacheRoot = File(appContext.cacheDir, "cloudflared")

    val workingDirectory: File = File(filesRoot, "work").ensureDirectory()
    private val homeDirectory: File = File(filesRoot, "home").ensureDirectory()
    private val tempDirectory: File = File(cacheRoot, "tmp").ensureDirectory()

    val binary: File? = locateBinary()

    val versionLabel: String = "2026.10.7-pocketshare.1"

    fun baseEnvironment(): Map<String, String> =
        buildMap {
            put("HOME", homeDirectory.absolutePath)
            put("TMPDIR", tempDirectory.absolutePath)
            put("XDG_CACHE_HOME", File(cacheRoot, "xdg").ensureDirectory().absolutePath)
            put("NO_AUTOUPDATE", "true")
            val caBundle = ensureCaBundle()
            if (caBundle != null) put("SSL_CERT_FILE", caBundle.absolutePath)
        }

    /**
     * Android does not expose a global resolv.conf. These are the phone's active DNS servers;
     * [CloudflaredCommand] passes them as `TUNNEL_DNS_SERVERS` to the patched cloudflared build,
     * which uses them for every Go resolver lookup (SRV, quick-tunnel API, feature selector).
     */
    fun dnsServers(): List<String> {
        return try {
            val manager =
                appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return emptyList()
            val network = manager.activeNetwork ?: return emptyList()
            manager
                .getLinkProperties(network)
                ?.dnsServers
                ?.mapNotNull { address -> address.hostAddress?.substringBefore('%') }
                ?.filter { it.isNotBlank() }
                ?.distinct()
                ?.take(4)
                .orEmpty()
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Android's Go-based cloudflared cannot see netd's DNS configuration: Go reads
     * `/etc/resolv.conf`, which does not exist on Android, and falls back to `[::1]:53`. Resolve
     * the edge hostnames through Android's own resolver here and hand cloudflared numeric
     * `TUNNEL_EDGE` addresses instead. This skips SRV discovery entirely; cloudflared still
     * verifies the edge certificate, so a poisoned DNS answer cannot impersonate Cloudflare.
     *
     * IPv4 is preferred because most mobile networks on the supported API range are IPv4-only.
     * The two region hostnames are the same SRV targets cloudflared would otherwise discover.
     */
    fun edgeAddresses(): List<String> =
        EDGE_HOSTS
            .flatMap { host ->
                runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            }.filterNot { address ->
                address.isAnyLocalAddress ||
                    address.isLoopbackAddress ||
                    address.isLinkLocalAddress ||
                    address.isMulticastAddress
            }.let {
                it.filterIsInstance<Inet4Address>() +
                    it.filterIsInstance<Inet6Address>()
            }.mapNotNull { address -> address.hostAddress?.substringBefore('%') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_EDGE_ADDRESSES)

    private fun locateBinary(): File? {
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val candidates =
            buildList {
                if (nativeDir != null) add(File(nativeDir, BINARY_NAME))
                if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                    add(File(filesRoot, "bin/cloudflared"))
                }
            }
        return candidates.firstOrNull { file ->
            file.isFile && (file.canExecute() || file.setExecutable(true))
        }
    }

    /**
     * Concatenates Android's system CA store into one PEM file because Go's crypto/x509 only reads
     * a single bundle from SSL_CERT_FILE on Android.
     */
    private fun ensureCaBundle(): File? {
        val target = File(filesRoot, "ca-bundle.pem")
        val sourceDirs =
            listOf(
                File("/apex/com.android.conscrypt/cacerts"),
                File(System.getenv("ANDROID_ROOT") ?: "/system", "etc/security/cacerts"),
                File("/system/etc/security/cacerts"),
            ).distinct()
        val roots =
            sourceDirs.firstOrNull { dir ->
                dir.isDirectory && dir.listFiles()?.isNotEmpty() == true
            } ?: return target.takeIf { it.isFile && it.length() > 0 }

        return try {
            val certificates =
                roots
                    .listFiles()
                    ?.asSequence()
                    ?.filter { it.isFile }
                    ?.mapNotNull { file -> runCatching { file.readText() }.getOrNull() }
                    ?.filter { it.contains("BEGIN CERTIFICATE") }
                    ?.distinct()
                    ?.toList()
                    .orEmpty()
            if (certificates.isEmpty()) {
                target.delete()
                return null
            }
            filesRoot.mkdirs()
            val temp = File(filesRoot, "ca-bundle.pem.tmp")
            temp.bufferedWriter().use { writer ->
                certificates.forEach { pem ->
                    writer.write(pem.trimEnd())
                    writer.newLine()
                }
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            target
        } catch (_: Exception) {
            target.takeIf { it.isFile && it.length() > 0 }
        }
    }

    private fun File.ensureDirectory(): File {
        if (!isDirectory) mkdirs()
        return this
    }

    private companion object {
        const val BINARY_NAME = "libcloudflared.so"
        const val MAX_EDGE_ADDRESSES = 4
        val EDGE_HOSTS = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
    }
}
