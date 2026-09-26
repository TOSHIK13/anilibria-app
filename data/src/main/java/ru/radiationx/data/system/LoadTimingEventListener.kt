package ru.radiationx.data.system

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Одна строка `[net] ...` на каждый HTTP call. Без query-параметров (кроме legacy `query=`),
 * чтобы не светить токены.
 */
class LoadTimingEventListener private constructor(
    private val client: String,
) : EventListener() {

    companion object {
        fun factory(client: String): EventListener.Factory = EventListener.Factory {
            if (LoadTiming.enabled) LoadTimingEventListener(client) else NONE
        }
    }

    private var callStart = 0L
    private var dnsStart = -1L
    private var dnsMs = -1L
    private var connectStart = -1L
    private var connectMs = -1L
    private var tlsStart = -1L
    private var tlsMs = -1L
    private var ttfbMs = -1L
    private var reused = true
    private var protocol: String? = null
    private var code = -1
    private var bytes = -1L

    override fun callStart(call: Call) {
        callStart = LoadTiming.now()
    }

    override fun dnsStart(call: Call, domainName: String) {
        dnsStart = LoadTiming.now()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
        if (dnsStart >= 0) dnsMs = LoadTiming.now() - dnsStart
    }

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        reused = false
        connectStart = LoadTiming.now()
    }

    override fun secureConnectStart(call: Call) {
        tlsStart = LoadTiming.now()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (tlsStart >= 0) tlsMs = LoadTiming.now() - tlsStart
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        if (connectStart >= 0) connectMs = LoadTiming.now() - connectStart
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        protocol = connection.protocol().toString()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        ttfbMs = LoadTiming.now() - callStart
        code = response.code
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        bytes = byteCount
    }

    override fun callEnd(call: Call) {
        print(call, null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        print(call, ioe)
    }

    override fun canceled(call: Call) {
        LoadTiming.log("[net] ${call.describe()} canceled total=${LoadTiming.now() - callStart} client=$client")
    }

    private fun print(call: Call, error: IOException?) {
        val total = LoadTiming.now() - callStart
        val result = if (error != null) "ERR(${error.javaClass.simpleName}: ${error.message})" else code.toString()
        LoadTiming.log(
            "[net] ${call.describe()} $result " +
                    "dns=${dnsMs.ms()} conn=${connectMs.ms()} tls=${tlsMs.ms()} ttfb=${ttfbMs.ms()} total=$total " +
                    "reused=${if (reused) "yes" else "no"} proto=${protocol ?: "-"} size=${bytes.ms()} " +
                    "client=$client +${LoadTiming.sinceStart()}ms"
        )
    }

    private fun Call.describe(): String {
        val url = request().url
        val legacyQuery = url.queryParameter("query")?.let { "?query=$it" }.orEmpty()
        return "${request().method} ${url.host}${url.encodedPath}$legacyQuery"
    }

    private fun Long.ms(): String = if (this < 0) "-" else toString()
}
