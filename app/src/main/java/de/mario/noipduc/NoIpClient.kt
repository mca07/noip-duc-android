package de.mario.noipduc

import android.util.Base64
import de.mario.noipduc.NoIpProtocol.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Netzwerk-Teil des DUC. Sicherheitsregeln:
 * - ausschließlich HTTPS (zusätzlich per network_security_config erzwungen)
 * - keine Weiterleitungen folgen (Zugangsdaten könnten sonst an fremde Hosts gehen)
 * - Antworten auf 4 KB begrenzt
 * - öffentliche IP nur übernehmen, wenn zwei unabhängige Dienste übereinstimmen
 */
object NoIpClient {

    private const val UPDATE_URL = "https://dynupdate.no-ip.com/nic/update"

    /** No-IP verlangt einen aussagekräftigen User-Agent ("Name/Version Kontakt"). */
    private val USER_AGENT = "NoIpDuc-Android/${BuildConfig.VERSION_NAME} android-duc"

    private const val MAX_BODY = 4 * 1024

    private val IP_SERVICES = listOf(
        "https://api.ipify.org",
        "https://checkip.amazonaws.com",
        "https://ipv4.icanhazip.com",
    )

    /**
     * Fragt die Dienste nacheinander ab, bis zwei dieselbe gültige öffentliche IPv4 melden.
     * Ein einzelner manipulierter oder fehlerhafter Dienst kann den DNS-Eintrag so nicht umbiegen.
     */
    suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
        val answers = mutableListOf<String>()
        var lastError: Exception? = null
        for (service in IP_SERVICES) {
            try {
                val (code, body) = httpGet(service, auth = null)
                val text = body.trim()
                if (code == 200 && NoIpProtocol.isPublicIpv4(text)) {
                    if (text in answers) return@withContext text
                    answers += text
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        when {
            answers.size >= 2 -> throw IOException("IP-Dienste widersprechen sich – Update ausgelassen")
            else -> throw IOException(
                "Öffentliche IP nicht sicher ermittelbar" +
                    (lastError?.let { " (${it.javaClass.simpleName})" } ?: "")
            )
        }
    }

    suspend fun update(hosts: List<String>, user: String, pass: String, ip: String): Result =
        withContext(Dispatchers.IO) {
            val url = UPDATE_URL +
                "?hostname=" + URLEncoder.encode(hosts.joinToString(","), "UTF-8") +
                "&myip=" + URLEncoder.encode(ip, "UTF-8")
            val auth = Base64.encodeToString("$user:$pass".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val (code, body) = try {
                httpGet(url, auth)
            } catch (e: Exception) {
                // Nur den Fehlertyp protokollieren – Meldungen könnten URLs o. Ä. enthalten.
                return@withContext Result.Retry("Netzwerkfehler (${e.javaClass.simpleName})")
            }
            NoIpProtocol.parseResponse(code, body, ip)
        }

    private fun httpGet(url: String, auth: String?): Pair<Int, String> {
        val u = URL(url)
        require(u.protocol == "https") { "Nur HTTPS erlaubt" }
        val conn = u.openConnection() as HttpsURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (auth != null) conn.setRequestProperty("Authorization", "Basic $auth")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.use { s ->
                val buf = ByteArray(MAX_BODY)
                var total = 0
                while (total < MAX_BODY) {
                    val n = s.read(buf, total, MAX_BODY - total)
                    if (n < 0) break
                    total += n
                }
                String(buf, 0, total, Charsets.UTF_8)
            } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }
}
