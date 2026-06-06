package uz.shs.better_player_plus

import android.net.Uri
import android.util.Patterns
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import javax.net.ssl.HttpsURLConnection

@UnstableApi
internal object DataSourceUtils {
    private const val USER_AGENT = "User-Agent"
    private const val USER_AGENT_PROPERTY = "http.agent"

    @Volatile
    private var ipHostnameVerifierInstalled = false

    /// Relax TLS *hostname* verification ONLY for bare-IP hosts — the
    /// `dns_auto` case, where a stream is pinned to an IP and the server's
    /// valid, CA-signed domain certificate can never match the IP. The
    /// certificate *chain* is still validated by the default SSLSocketFactory,
    /// and named hosts defer to the platform verifier, so normal HTTPS keeps
    /// full protection. media3's DefaultHttpDataSource opens an
    /// HttpsURLConnection that honors this process-wide default verifier.
    @JvmStatic
    @Synchronized
    private fun installIpHostnameVerifierOnce() {
        if (ipHostnameVerifierInstalled) return
        ipHostnameVerifierInstalled = true
        val platform = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultHostnameVerifier { hostname, session ->
            isIpLiteral(hostname) || platform.verify(hostname, session)
        }
    }

    private fun isIpLiteral(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        // IPv4 via the platform matcher; IPv6 literals always contain ':'
        // (hostnames never do), so that's a sufficient discriminator.
        return host.contains(':') || Patterns.IP_ADDRESS.matcher(host).matches()
    }

    @JvmStatic
    fun getUserAgent(headers: Map<String, String>?): String? {
        var userAgent = System.getProperty(USER_AGENT_PROPERTY)
        if (headers != null && headers.containsKey(USER_AGENT)) {
            val userAgentHeader = headers[USER_AGENT]
            if (userAgentHeader != null) {
                userAgent = userAgentHeader
            }
        }
        return userAgent
    }

    @JvmStatic
    fun getDataSourceFactory(
        userAgent: String?,
        headers: Map<String, String>?
    ): DataSource.Factory {
        installIpHostnameVerifierOnce()
        val dataSourceFactory: DataSource.Factory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS)
            .setReadTimeoutMs(DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS)
        if (headers != null) {
            val notNullHeaders = mutableMapOf<String, String>()
            headers.forEach { entry ->
                notNullHeaders[entry.key] = entry.value
            }
            (dataSourceFactory as DefaultHttpDataSource.Factory).setDefaultRequestProperties(
                notNullHeaders
            )
        }
        return dataSourceFactory
    }

    @JvmStatic
    fun isHTTP(uri: Uri?): Boolean {
        if (uri == null || uri.scheme == null) {
            return false
        }
        val scheme = uri.scheme
        return scheme == "http" || scheme == "https"
    }
}