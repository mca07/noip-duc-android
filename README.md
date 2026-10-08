# No-IP DUC für Android

Kleine Android-App (Kotlin, Jetpack Compose), die das tut, was der Linux-DUC macht:
öffentliche IP regelmäßig prüfen und bei einer Änderung den No-IP-Hostnamen aktualisieren.

## Funktionen
- Login mit No-IP-Account **oder** DDNS-Key (`all.ddnskey.com` + Key-User/Key-Passwort)
- Mehrere Hostnamen (kommagetrennt)
- Hintergrund-Prüfung über WorkManager (15 Min, 30 Min, 1 Std oder 3 Std), läuft auch nach Neustart weiter
- Option „Nur im WLAN“ (standardmäßig an)
- Benachrichtigung, wenn Updates wegen eines Fehlers gestoppt wurden
- Update wird nur gesendet, wenn sich die IP geändert hat (No-IP wertet ständige `nochg`-Updates als Missbrauch)
- Bei dauerhaften Fehlern (`badauth`, `nohost`, `abuse` …) stoppt die App die automatischen Updates, wie No-IP es vorschreibt
- Zugangsdaten verschlüsselt gespeichert (Android Keystore)
- Protokoll der letzten 100 Prüfungen

## Bauen
1. Ordner in Android Studio öffnen (Ladybug oder neuer).
2. Gradle-Sync abwarten, dann **Run** oder *Build › Build APK(s)*.

## Wichtig
Das Handy meldet **seine eigene** öffentliche IP. Im WLAN zu Hause ist das die IP deines
Anschlusses; im Mobilfunknetz ist es eine Provider-IP (oft CGNAT, von außen nicht erreichbar).
Für den Heimanschluss also „Nur im WLAN“ aktivieren. Dauerhaft zuverlässiger bleibt der DUC im
Router (z. B. FRITZ!Box › Dynamic DNS) oder auf einem Rechner, der immer läuft.

Nur IPv4. Die öffentliche IP wird über api.ipify.org, checkip.amazonaws.com und icanhazip.com ermittelt; übernommen wird sie nur, wenn zwei Dienste übereinstimmen.

Sicherheitsprüfung: siehe SECURITY_CHECK.md. Unit-Tests: `./gradlew test` bzw. in Android Studio *Run Tests*.
