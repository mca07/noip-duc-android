package de.mario.noipduc

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        enableEdgeToEdge()
        setContent { AppTheme { DucScreen() } }
    }

    override fun onResume() {
        super.onResume()
        Store.reload() // Änderungen aus dem Hintergrund-Worker anzeigen
    }
}

private val NoIpGreen = Color(0xFF5B9A1E)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFF8BC34A), secondary = Color(0xFF8BC34A))
    else
        lightColorScheme(primary = NoIpGreen, secondary = NoIpGreen)
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DucScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by Store.config.collectAsStateWithLifecycle()
    val status by Store.status.collectAsStateWithLifecycle()

    var hostnames by rememberSaveable { mutableStateOf(cfg.hostnames) }
    var username by rememberSaveable { mutableStateOf(cfg.username) }
    // Bewusst nicht rememberSaveable: das Passwort soll nicht im gespeicherten Instanz-Zustand landen.
    var password by remember { mutableStateOf(cfg.password) }
    var interval by rememberSaveable { mutableStateOf(cfg.intervalMinutes) }
    var wifiOnly by rememberSaveable { mutableStateOf(cfg.wifiOnly) }
    var showPw by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    fun currentForm() = cfg.copy(
        hostnames = hostnames, username = username, password = password,
        intervalMinutes = interval, wifiOnly = wifiOnly,
    )

    val dirty = currentForm() != cfg
    val hostError = if (hostnames.isBlank()) null else NoIpProtocol.parseHostnames(hostnames).second

    // Android 13+: Erlaubnis für die Fehler-Benachrichtigung erst anfragen, wenn Auto-Update eingeschaltet wird.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun runUpdate(force: Boolean) {
        scope.launch {
            busy = true
            try { Updater.run(context, force) } finally { busy = false }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("No-IP DUC") }) }) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---------- Status ----------
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Gemeldete IP", style = MaterialTheme.typography.labelMedium)
                    Text(
                        status.lastIp.ifEmpty { "–" },
                        fontSize = 28.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    )
                    if (status.lastCheck.isNotEmpty()) {
                        Text("Letzte Prüfung: ${status.lastCheck}", style = MaterialTheme.typography.bodySmall)
                        Text(status.lastResult, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatisch aktualisieren", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (cfg.enabled) "Aktiv – alle ${cfg.intervalMinutes} Min." else "Aus",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = cfg.enabled,
                    // Ausschalten muss immer möglich sein, Einschalten nur mit gültigen Daten.
                    enabled = cfg.enabled || currentForm().isComplete,
                    onCheckedChange = { on ->
                        val newCfg = currentForm().copy(enabled = on)
                        Store.saveConfig(newCfg)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33) {
                                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            Scheduler.schedule(context, newCfg)
                            runUpdate(force = false)
                        } else {
                            Scheduler.cancel(context)
                        }
                    },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !busy && currentForm().isComplete,
                    onClick = {
                        if (dirty) Store.saveConfig(currentForm())
                        runUpdate(force = true)
                    },
                ) { Text("Jetzt aktualisieren") }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }

            HorizontalDivider()

            // ---------- Zugangsdaten ----------
            Text("Zugangsdaten", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = hostnames, onValueChange = { hostnames = it.take(NoIpProtocol.MAX_FIELD_LEN * 4) },
                label = { Text("Hostname(s)") },
                isError = hostError != null,
                supportingText = {
                    Text(hostError ?: "z. B. meinhost.ddns.net – mehrere mit Komma. Bei DDNS-Key: all.ddnskey.com")
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it.take(NoIpProtocol.MAX_FIELD_LEN) },
                label = { Text("Benutzername / E-Mail oder DDNS-Key-User") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it.take(NoIpProtocol.MAX_FIELD_LEN) },
                label = { Text("Passwort oder DDNS-Key-Passwort") },
                supportingText = {
                    Text("Empfohlen: DDNS-Key statt Konto-Passwort – er kann nur Updates senden und ist einzeln widerrufbar.")
                },
                singleLine = true,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Verbergen" else "Zeigen") }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Prüfintervall", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(15, 30, 60, 180).forEach { m ->
                    FilterChip(
                        selected = interval == m,
                        onClick = { interval = m },
                        label = { Text(if (m < 60) "$m Min" else "${m / 60} Std") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nur im WLAN")
                    Text(
                        "Empfohlen: Im Mobilfunk hat das Handy meist eine andere (oft geteilte) IP als dein Anschluss.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
            }

            Button(
                enabled = dirty,
                onClick = {
                    val newCfg = currentForm()
                    Store.saveConfig(newCfg)
                    if (newCfg.enabled) Scheduler.schedule(context, newCfg)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }

            HorizontalDivider()

            // ---------- Protokoll ----------
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Protokoll", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { Store.clearLog() }, enabled = status.log.isNotEmpty()) { Text("Leeren") }
            }
            if (status.log.isEmpty()) {
                Text("Noch keine Einträge.", style = MaterialTheme.typography.bodySmall)
            } else {
                status.log.forEach { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }
            }
            OutlinedButton(onClick = { Store.reload() }) { Text("Aktualisieren") }
        }
    }
}
