package de.mario.noipduc

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Zugangsdaten und Einstellungen. */
data class Config(
    val hostnames: String = "",
    val username: String = "",
    val password: String = "",
    val intervalMinutes: Int = 30,
    val wifiOnly: Boolean = true,
    val enabled: Boolean = false,
) {
    val isComplete: Boolean
        get() = NoIpProtocol.parseHostnames(hostnames).second == null &&
            username.isNotBlank() && password.isNotEmpty()

    /** Passwort nie in toString() (z. B. versehentlich in Logs). */
    override fun toString() =
        "Config(hostnames=$hostnames, username=***, password=***, interval=$intervalMinutes, wifiOnly=$wifiOnly, enabled=$enabled)"
}

/** Laufzeit-Status, der in der Oberfläche angezeigt wird. */
data class Status(
    val lastIp: String = "",
    val lastResult: String = "",
    val lastCheck: String = "",
    val log: List<String> = emptyList(),
    /** Frühester Zeitpunkt (epoch ms) für den nächsten Update-Versuch, z. B. nach "911". */
    val notBefore: Long = 0L,
)

/**
 * Speichert alles verschlüsselt (Schlüssel im Android Keystore) und stellt es als StateFlow bereit.
 * Die Datei ist per data_extraction_rules von Backups/Gerätetransfer ausgeschlossen,
 * denn der Keystore-Schlüssel existiert nur auf diesem Gerät.
 */
object Store {
    private const val FILE = "noip_duc_secure"
    private const val MAX_LOG = 100
    private const val TAG = "NoIpDuc"

    private lateinit var prefs: SharedPreferences

    private val _config = MutableStateFlow(Config())
    val config: StateFlow<Config> = _config

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    @Synchronized
    fun init(context: Context) {
        if (::prefs.isInitialized) return
        val appCtx = context.applicationContext
        prefs = try {
            open(appCtx)
        } catch (e: Exception) {
            // Keystore-Schlüssel verloren/beschädigt (bekanntes Problem bei manchen Geräten):
            // Daten sind nicht mehr entschlüsselbar → verwerfen und neu anlegen statt Absturz.
            Log.w(TAG, "Verschlüsselter Speicher unlesbar, wird zurückgesetzt (${e.javaClass.simpleName})")
            appCtx.deleteSharedPreferences(FILE)
            open(appCtx)
        }
        reload()
    }

    private fun open(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx, FILE, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun reload() {
        if (!::prefs.isInitialized) return
        _config.value = Config(
            hostnames = prefs.getString("hostnames", "") ?: "",
            username = prefs.getString("username", "") ?: "",
            password = prefs.getString("password", "") ?: "",
            intervalMinutes = prefs.getInt("interval", 30),
            wifiOnly = prefs.getBoolean("wifiOnly", true),
            enabled = prefs.getBoolean("enabled", false),
        )
        _status.value = Status(
            lastIp = prefs.getString("lastIp", "") ?: "",
            lastResult = prefs.getString("lastResult", "") ?: "",
            lastCheck = prefs.getString("lastCheck", "") ?: "",
            log = (prefs.getString("log", "") ?: "").split('\n').filter { it.isNotBlank() },
            notBefore = prefs.getLong("notBefore", 0L),
        )
    }

    fun saveConfig(c: Config) {
        val clean = c.copy(
            hostnames = c.hostnames.trim().take(NoIpProtocol.MAX_FIELD_LEN * 4),
            username = c.username.trim().take(NoIpProtocol.MAX_FIELD_LEN),
            password = c.password.take(NoIpProtocol.MAX_FIELD_LEN),
            intervalMinutes = c.intervalMinutes.coerceIn(15, 24 * 60),
        )
        val old = _config.value
        val edit = prefs.edit()
            .putString("hostnames", clean.hostnames)
            .putString("username", clean.username)
            .putString("password", clean.password)
            .putInt("interval", clean.intervalMinutes)
            .putBoolean("wifiOnly", clean.wifiOnly)
            .putBoolean("enabled", clean.enabled)
        // Neue Hosts/Zugangsdaten → beim nächsten Lauf auf jeden Fall senden.
        if (old.hostnames != clean.hostnames || old.username != clean.username || old.password != clean.password) {
            edit.putString("lastIp", "")
            _status.value = _status.value.copy(lastIp = "")
        }
        edit.apply()
        _config.value = clean
    }

    fun setEnabled(enabled: Boolean) = saveConfig(_config.value.copy(enabled = enabled))

    fun setLastIp(ip: String) {
        prefs.edit().putString("lastIp", ip).apply()
        _status.value = _status.value.copy(lastIp = ip)
    }

    fun setNotBefore(epochMs: Long) {
        prefs.edit().putLong("notBefore", epochMs).apply()
        _status.value = _status.value.copy(notBefore = epochMs)
    }

    /** Protokolliert nur App-eigene Texte bzw. bereinigte Server-Antworten – nie Zugangsdaten. */
    fun record(result: String) {
        val now = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())
        val clean = NoIpProtocol.sanitize(result, 160)
        val newLog = (listOf("$now  $clean") + _status.value.log).take(MAX_LOG)
        prefs.edit()
            .putString("lastResult", clean)
            .putString("lastCheck", now)
            .putString("log", newLog.joinToString("\n"))
            .apply()
        _status.value = _status.value.copy(lastResult = clean, lastCheck = now, log = newLog)
    }

    fun clearLog() {
        prefs.edit().putString("log", "").apply()
        _status.value = _status.value.copy(log = emptyList())
    }
}
