package de.mario.noipduc

/**
 * Reine Protokoll-Logik ohne Android-Abhängigkeiten (dadurch per Unit-Test prüfbar).
 * Spezifikation: https://www.noip.com/integrate/request
 */
object NoIpProtocol {

    sealed class Result(val message: String) {
        /** IP wurde gesetzt (good) oder war schon aktuell (nochg). */
        class Ok(message: String, val ip: String) : Result(message)
        /** Vorübergehender Fehler (Netz, 911) – später erneut versuchen. */
        class Retry(message: String, val waitMinutes: Int = 0) : Result(message)
        /** Dauerhafter Fehler – automatische Updates müssen gestoppt werden. */
        class Fatal(message: String) : Result(message)
    }

    private val IPV4 = Regex("""^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$""")

    /** Hostname-Label nach RFC 1123: Buchstaben, Ziffern, Bindestrich, max. 63 Zeichen. */
    private val LABEL = Regex("""^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$""")

    const val MAX_HOSTS = 20
    const val MAX_FIELD_LEN = 256

    fun isPublicIpv4(s: String): Boolean {
        if (!IPV4.matches(s)) return false
        val p = s.split('.').map { it.toInt() }
        return when {
            p[0] == 10 || p[0] == 127 || p[0] == 0 -> false
            p[0] == 172 && p[1] in 16..31 -> false
            p[0] == 192 && p[1] == 168 -> false
            p[0] == 169 && p[1] == 254 -> false
            p[0] == 100 && p[1] in 64..127 -> false  // CGNAT
            p[0] >= 224 -> false                       // Multicast/reserviert
            else -> true
        }
    }

    /** Zerlegt und prüft die Hostnamen-Eingabe. Liefert die Liste oder eine Fehlermeldung. */
    fun parseHostnames(input: String): Pair<List<String>?, String?> {
        val hosts = input.lowercase().split(',', ' ', ';', '\n')
            .map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.distinct()
        if (hosts.isEmpty()) return null to "Mindestens ein Hostname nötig"
        if (hosts.size > MAX_HOSTS) return null to "Maximal $MAX_HOSTS Hostnamen"
        for (h in hosts) {
            val labels = h.split('.')
            if (h.length > 253 || labels.size < 2 || !labels.all { LABEL.matches(it) }) {
                return null to "Ungültiger Hostname: ${h.take(60)}"
            }
        }
        return hosts to null
    }

    /**
     * Wertet die Server-Antwort aus. Bei mehreren Hostnamen kommt eine Zeile pro Host.
     * Unbekannte Inhalte werden gekürzt und von Steuerzeichen befreit, bevor sie angezeigt werden.
     */
    fun parseResponse(httpCode: Int, body: String, ip: String): Result {
        if (httpCode == 401) return Result.Fatal("badauth – Benutzername/Passwort falsch")
        if (httpCode >= 500) return Result.Retry("HTTP $httpCode – Serverfehler bei No-IP", waitMinutes = 30)
        if (httpCode !in 200..299) return Result.Retry("HTTP $httpCode – unerwarteter Status")

        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return Result.Retry("Leere Antwort vom Server")

        for (line in lines) {
            when (line.substringBefore(' ')) {
                "good", "nochg" -> Unit
                "nohost" -> return Result.Fatal("nohost – Hostname existiert nicht in diesem Account")
                "badauth" -> return Result.Fatal("badauth – Benutzername/Passwort falsch")
                "badagent" -> return Result.Fatal("badagent – Client wurde von No-IP gesperrt")
                "!donator" -> return Result.Fatal("!donator – Funktion nur für zahlende Accounts")
                "abuse" -> return Result.Fatal("abuse – Account wegen Missbrauch gesperrt")
                // No-IP verlangt nach 911 mindestens 30 Minuten Pause.
                "911" -> return Result.Retry("911 – Serverfehler bei No-IP, Pause 30 Min.", waitMinutes = 30)
                else -> return Result.Retry("Unerwartete Antwort: ${sanitize(line)}")
            }
        }
        val summary = if (lines.all { it.startsWith("nochg") }) "nochg – IP unverändert ($ip)"
        else "good – IP aktualisiert auf $ip"
        return Result.Ok(summary, ip)
    }

    /** Für Anzeige/Protokoll: nur druckbare Zeichen, max. 80 Zeichen. */
    fun sanitize(s: String, max: Int = 80): String =
        s.filter { it.code in 0x20..0x7E || it.code >= 0xA0 }.take(max)
}
