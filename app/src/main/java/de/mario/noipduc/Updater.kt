package de.mario.noipduc

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Die eigentliche DUC-Logik: IP prüfen, bei Änderung an No-IP melden. */
object Updater {
    private val mutex = Mutex()

    /** Mindestabstand zwischen zwei manuellen Updates (Schutz vor "abuse"-Sperre). */
    private const val MANUAL_COOLDOWN_MS = 60_000L
    private var lastManualAt = 0L

    /**
     * @param force true = auch senden, wenn sich die IP nicht geändert hat (manueller Button).
     *              Automatische Läufe senden nur bei Änderung, weil No-IP häufige
     *              "nochg"-Updates als Missbrauch wertet.
     */
    suspend fun run(context: Context, force: Boolean): NoIpProtocol.Result = mutex.withLock {
        Store.init(context)
        val cfg = Store.config.value
        val now = System.currentTimeMillis()

        val (hosts, hostError) = NoIpProtocol.parseHostnames(cfg.hostnames)
        if (hosts == null || cfg.username.isBlank() || cfg.password.isEmpty()) {
            return NoIpProtocol.Result.Fatal(hostError ?: "Zugangsdaten unvollständig").also { Store.record(it.message) }
        }
        if (now < Store.status.value.notBefore) {
            val min = (Store.status.value.notBefore - now) / 60_000 + 1
            return NoIpProtocol.Result.Retry("Pause nach Serverfehler – noch ca. $min Min.").also { Store.record(it.message) }
        }
        if (force && now - lastManualAt < MANUAL_COOLDOWN_MS) {
            return NoIpProtocol.Result.Retry("Bitte kurz warten – max. ein manuelles Update pro Minute")
                .also { Store.record(it.message) }
        }

        val ip = try {
            NoIpClient.fetchPublicIp()
        } catch (e: Exception) {
            return NoIpProtocol.Result.Retry(e.message ?: "IP-Ermittlung fehlgeschlagen").also { Store.record(it.message) }
        }

        if (!force && ip == Store.status.value.lastIp) {
            return NoIpProtocol.Result.Ok("IP unverändert ($ip) – kein Update nötig", ip).also { Store.record(it.message) }
        }

        if (force) lastManualAt = now
        val result = NoIpClient.update(hosts, cfg.username, cfg.password, ip)
        var suffix = ""
        when (result) {
            is NoIpProtocol.Result.Ok -> Store.setLastIp(result.ip)
            is NoIpProtocol.Result.Retry -> if (result.waitMinutes > 0) {
                Store.setNotBefore(now + result.waitMinutes * 60_000L)
            }
            is NoIpProtocol.Result.Fatal -> if (cfg.enabled) {
                // Laut No-IP darf ein Client nach solchen Fehlern nicht weiter senden.
                Store.setEnabled(false)
                Scheduler.cancel(context)
                Notifier.fatal(context, result.message)
                suffix = " → automatische Updates gestoppt"
            }
        }
        Store.record(result.message + suffix)
        return result
    }
}

class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): ListenableWorker.Result {
        Store.init(applicationContext)
        if (!Store.config.value.enabled) return ListenableWorker.Result.success()
        return when (val r = Updater.run(applicationContext, force = false)) {
            is NoIpProtocol.Result.Ok -> ListenableWorker.Result.success()
            // Serverseitige Pause: nicht sofort erneut versuchen, sondern regulär im nächsten Intervall.
            is NoIpProtocol.Result.Retry -> if (r.waitMinutes > 0) ListenableWorker.Result.success() else ListenableWorker.Result.retry()
            is NoIpProtocol.Result.Fatal -> ListenableWorker.Result.failure()
        }
    }
}

object Scheduler {
    private const val WORK_NAME = "noip-duc-periodic"

    /** WorkManager übersteht Neustarts selbst; Mindestintervall ist 15 Minuten. */
    fun schedule(context: Context, cfg: Config) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            cfg.intervalMinutes.coerceAtLeast(15).toLong(), TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            // Netzwerkfehler: frühestens nach 5 Min. erneut, danach exponentiell länger.
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
