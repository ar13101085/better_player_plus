package uz.shs.better_player_plus

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

@UnstableApi
internal object DataSourceUtils {
    private const val USER_AGENT = "User-Agent"
    private const val USER_AGENT_PROPERTY = "http.agent"

    @Volatile
    private var trustAllTlsInstalled = false

    /// Accept ALL TLS certificates for media playback. IPTV streams come from
    /// many third-party CDNs, and older devices ship a stale system CA store
    /// that lacks newer roots (e.g. "Sectigo … Root R46"), so an otherwise
    /// valid stream cert fails with "Trust anchor for certification path not
    /// found" and the channel won't play. media3's DefaultHttpDataSource opens
    /// an HttpsURLConnection that honors these process-wide defaults, so a
    /// trust-all socket factory + hostname verifier lets every stream play
    /// regardless of its certificate or the device's CA age.
    ///
    /// Scope: only the media HTTP stack (HttpsURLConnection) is affected — the
    /// host app's own backend traffic uses its own clients.
    @JvmStatic
    @Synchronized
    private fun installTrustAllTlsOnce() {
        if (trustAllTlsInstalled) return
        trustAllTlsInstalled = true

        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }

        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(TrustAllManager()), null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
    }

    /// Trust manager that accepts every certificate chain. Extends
    /// X509ExtendedTrustManager so the socket / engine overloads — the ones
    /// HttpsURLConnection actually invokes — are all covered.
    private class TrustAllManager : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
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
        installTrustAllTlsOnce()
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