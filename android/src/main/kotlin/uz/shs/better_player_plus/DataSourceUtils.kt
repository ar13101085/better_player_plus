package uz.shs.better_player_plus

import android.net.Uri
import android.util.Patterns
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

@UnstableApi
internal object DataSourceUtils {
    private const val USER_AGENT = "User-Agent"
    private const val USER_AGENT_PROPERTY = "http.agent"

    @Volatile
    private var ipTolerantTlsInstalled = false

    /// Relax TLS verification ONLY for bare-IP hosts — the `dns_auto` case,
    /// where a stream is pinned to an IP. The server then answers with its
    /// default certificate, which neither matches the IP (hostname check) nor
    /// chains to a trusted root for that IP (chain check), so BOTH fail. We
    /// skip both, but only when the peer host is a bare IP literal; named hosts
    /// defer to the platform verifier + trust manager, so normal HTTPS keeps
    /// full protection. media3's DefaultHttpDataSource opens an
    /// HttpsURLConnection that honors these process-wide defaults.
    @JvmStatic
    @Synchronized
    private fun installIpTolerantTlsOnce() {
        if (ipTolerantTlsInstalled) return
        ipTolerantTlsInstalled = true

        // Hostname check: pass IP literals, else defer to the platform verifier.
        val platformVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        HttpsURLConnection.setDefaultHostnameVerifier { hostname, session ->
            isIpLiteral(hostname) || platformVerifier.verify(hostname, session)
        }

        // Chain check: wrap the platform trust manager, skipping validation
        // only when the connection's peer host is a bare IP literal.
        val platformTm = platformTrustManager() ?: return
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(IpTolerantTrustManager(platformTm)), null)
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
    }

    private fun platformTrustManager(): X509ExtendedTrustManager? {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.firstOrNull { it is X509ExtendedTrustManager } as? X509ExtendedTrustManager
    }

    private fun isIpLiteral(host: String?): Boolean {
        if (host.isNullOrEmpty()) return false
        // IPv4 via the platform matcher; IPv6 literals always contain ':'
        // (hostnames never do), so that's a sufficient discriminator.
        return host.contains(':') || Patterns.IP_ADDRESS.matcher(host).matches()
    }

    /// Delegating trust manager that accepts the server chain WITHOUT
    /// validation when the peer host is a bare IP literal (the dns_auto case),
    /// and otherwise performs normal platform validation. Extends
    /// X509ExtendedTrustManager so the socket / engine overloads — the ones
    /// HttpsURLConnection actually invokes — can read the peer host for scoping.
    private class IpTolerantTrustManager(
        private val delegate: X509ExtendedTrustManager
    ) : X509ExtendedTrustManager() {

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
            val host = try { (socket as? SSLSocket)?.handshakeSession?.peerHost } catch (e: Exception) { null }
            if (isIpLiteral(host)) return
            delegate.checkServerTrusted(chain, authType, socket)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
            if (isIpLiteral(engine?.peerHost)) return
            delegate.checkServerTrusted(chain, authType, engine)
        }

        // No host context on this overload — fall back to full validation.
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            delegate.checkServerTrusted(chain, authType)

        // Client auth + accepted issuers: always delegate unchanged.
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
            delegate.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
            delegate.checkClientTrusted(chain, authType, engine)

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            delegate.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
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
        installIpTolerantTlsOnce()
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