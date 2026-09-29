package ru.radiationx.anilibria.screen.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom

/** Ответ телефону после отправки токена: [ok] + текст для страницы (без токена). */
data class LanReply(val ok: Boolean, val message: String)

/**
 * Минимальный HTTP-сервер в локальной сети: отдаёт телефону страницу ввода токена
 * `http://ip:port/<secret>/` и принимает POST `<secret>/token`. Без секрета в пути — 404.
 * Токен не логируется и в ответы не попадает.
 */
class LanTokenServer(
    private val scope: CoroutineScope,
    private val authorizeUrl: String,
    private val onToken: suspend (String) -> LanReply,
) {

    private companion object {
        const val PREFERRED_PORT = 8765
        const val MAX_BODY = 16 * 1024
        const val MAX_HEADERS = 8 * 1024
        const val SOCKET_TIMEOUT_MS = 5_000
    }

    private val secret: String = ByteArray(18).also { SecureRandom().nextBytes(it) }
        .let { Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }

    private var server: ServerSocket? = null
    private var acceptJob: Job? = null

    /** Принятые запросы живут отдельно от экрана и слушающего сокета; ограничены таймаутами сокета. */
    private val requestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val port: Int get() = server?.localPort ?: -1

    /** Путь страницы без хоста, вида `/<secret>/`. */
    val path: String get() = "/$secret/"

    /** true — сервер слушает порт. */
    fun start(): Boolean {
        if (server != null) return true
        val socket = try {
            ServerSocket(PREFERRED_PORT)
        } catch (e: Exception) {
            try {
                ServerSocket(0)
            } catch (e2: Exception) {
                return false
            }
        }
        server = socket
        acceptJob = scope.launch(Dispatchers.IO) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                // не дочерняя задача acceptJob: stop() после успешного входа не должен обрывать ответ телефону
                requestScope.launch { handle(client) }
            }
        }
        return true
    }

    /** Закрывает только слушающий сокет: уже принятые запросы дорабатывают. */
    fun stop() {
        try {
            server?.close()
        } catch (ignored: Exception) {
        }
        server = null
        acceptJob?.cancel()
        acceptJob = null
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SOCKET_TIMEOUT_MS
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val head = readHead(input) ?: return respond(output, 400, "text/plain", "Bad request")
                val lines = head.split("\r\n")
                val request = lines.first().split(' ')
                if (request.size < 2) return respond(output, 400, "text/plain", "Bad request")
                val method = request[0]
                val path = request[1].substringBefore('?')
                val headers = lines.drop(1).mapNotNull {
                    val i = it.indexOf(':')
                    if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
                }.toMap()

                when {
                    method == "GET" && (path == "/$secret/" || path == "/$secret") ->
                        respond(output, 200, "text/html; charset=utf-8", PAGE.replace("__AUTHORIZE_URL__", authorizeUrl))

                    method == "POST" && path == "/$secret/token" -> {
                        val length = headers["content-length"]?.toIntOrNull() ?: -1
                        if (length < 0 || length > MAX_BODY) {
                            return respond(output, 413, "text/plain", "Too large")
                        }
                        val body = readBody(input, length)
                        val token = parseToken(headers["content-type"].orEmpty(), body)
                        if (token.isNullOrBlank()) {
                            respondJson(output, LanReply(false, "Токен не найден в отправленном тексте"))
                        } else {
                            respondJson(output, onToken(token))
                        }
                    }

                    else -> respond(output, 404, "text/plain", "Not found")
                }
            } catch (e: Exception) {
                // таймаут/обрыв — просто закрываем соединение
            }
        }
    }

    /** Читает заголовки до `\r\n\r\n` побайтно (тело остаётся в потоке). */
    private fun readHead(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < MAX_HEADERS) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when {
                (b == 13 && matched % 2 == 0) || (b == 10 && matched % 2 == 1) -> matched + 1
                b == 13 -> 1
                else -> 0
            }
            if (matched == 4) return buf.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
        }
        return null
    }

    private fun readBody(input: InputStream, length: Int): String {
        val data = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(data, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(data, 0, read, Charsets.UTF_8)
    }

    private fun parseToken(contentType: String, body: String): String? {
        return if (contentType.contains("json", ignoreCase = true)) {
            try {
                JSONObject(body).optString("token").ifBlank { null }
            } catch (e: Exception) {
                null
            }
        } else {
            body.split('&').firstNotNullOfOrNull {
                val i = it.indexOf('=')
                if (i > 0 && it.substring(0, i) == "token") {
                    URLDecoder.decode(it.substring(i + 1), "UTF-8")
                } else {
                    null
                }
            }
        }
    }

    private fun respondJson(output: OutputStream, reply: LanReply) {
        val json = JSONObject().put("ok", reply.ok).put("message", reply.message).toString()
        respond(output, 200, "application/json; charset=utf-8", json)
    }

    private fun respond(output: OutputStream, code: Int, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            400 -> "Bad Request"
            404 -> "Not Found"
            413 -> "Payload Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }
}

/**
 * Site-local IPv4 приставки в домашней сети (Wi-Fi/Ethernet) или null.
 * VPN пропускаем: при включённом VPN активной сетью считается tun-интерфейс (напр. 172.19.0.1),
 * а телефон в той же Wi-Fi до него не достучится.
 */
@Suppress("DEPRECATION")
fun findLocalIpv4(context: Context): String? {
    return try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networks = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        networks.distinct().firstNotNullOfOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            val isLan = !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            if (!isLan) return@firstNotNullOfOrNull null
            cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        } ?: findLanInterfaceIpv4()
    } catch (e: Exception) {
        null
    }
}

/**
 * Включён ли VPN, под который попадает приложение. Тогда Android отправляет ответы сервера в туннель,
 * и телефон из домашней сети страницу не откроет — показываем подсказку на экране входа.
 */
fun isVpnActive(context: Context): Boolean = try {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    cm.activeNetwork
        ?.let { cm.getNetworkCapabilities(it) }
        ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
} catch (e: Exception) {
    false
}

/** Запасной путь: адрес на интерфейсах wlan или eth, если ConnectivityManager не отдал подходящую сеть. */
private fun findLanInterfaceIpv4(): String? = java.net.NetworkInterface.getNetworkInterfaces()
    ?.toList()
    ?.filter { it.isUp && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
    ?.flatMap { it.inetAddresses.toList() }
    ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
    ?.hostAddress

private const val PAGE = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>AniList на ТВ</title>
<style>
body{margin:0;padding:20px;background:#191b1c;color:#EEEEEE;font-family:sans-serif;line-height:1.4}
.c{max-width:480px;margin:0 auto}
h1{font-size:22px;margin:4px 0 16px}
.card{background:#282828;border-radius:12px;padding:16px;margin-bottom:14px}
.card p{margin:0 0 12px;color:#B2B2B2}
a.btn,button{display:block;box-sizing:border-box;width:100%;padding:16px;border:0;border-radius:10px;
background:#FE3635;color:#fff;font-size:17px;font-weight:bold;text-align:center;text-decoration:none}
button:disabled{opacity:.5}
textarea{box-sizing:border-box;width:100%;height:110px;margin-bottom:12px;padding:12px;border:1px solid #444;
border-radius:10px;background:#191b1c;color:#EEEEEE;font:14px monospace}
#s{margin-top:12px;font-size:16px;min-height:22px}
.ok{color:#4CD964}.err{color:#FE3635}
</style></head><body><div class="c">
<h1>Подключение AniList к ТВ</h1>
<div class="card"><p>Шаг 1. Войдите в AniList и скопируйте токен со страницы.</p>
<a class="btn" href="__AUTHORIZE_URL__" target="_blank" rel="noopener">Войти в AniList</a></div>
<div class="card"><p>Шаг 2. Вставьте токен (или скопированную ссылку целиком).</p>
<textarea id="t" placeholder="Вставьте токен" autocomplete="off" autocapitalize="off" spellcheck="false"></textarea>
<button id="b">Отправить на ТВ</button><div id="s"></div></div>
</div><script>
var b=document.getElementById('b'),t=document.getElementById('t'),s=document.getElementById('s');
b.onclick=function(){
 var v=t.value.trim(); if(!v){s.className='err';s.textContent='Вставьте токен';return}
 b.disabled=true;s.className='';s.textContent='Отправляем…';
 fetch('token',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},
  body:'token='+encodeURIComponent(v)})
 .then(function(r){return r.json()})
 .then(function(j){
  s.className=j.ok?'ok':'err';s.textContent=j.message;
  if(j.ok){t.value='';}else{b.disabled=false}
 }).catch(function(){s.className='err';s.textContent='Не получили ответ от ТВ. Посмотрите на экран ТВ: если там открылась первая синхронизация — вход выполнен. Если нет — проверьте, что телефон в той же сети Wi-Fi, и отправьте ещё раз.';b.disabled=false});
};
</script></body></html>"""
