# Prüfbericht No-IP DUC (Stand 26.09.2026)

✅ = erfüllt · 🔧 = in dieser Runde behoben · ⚠️ = offen / bewusst akzeptiert

## 1. Bedrohungsmodell
- Schützenswert: No-IP-Zugangsdaten, Richtigkeit des DNS-Eintrags (falsche IP = Umleitung zu Angreifer).
- Angreifer: fremde Apps auf dem Gerät, Netzwerk (öffentliches WLAN), manipulierte IP-Dienste, Diebstahl/Backup des Geräts.

## 2. Zugangsdaten & Speicherung
- ✅ Verschlüsselt (EncryptedSharedPreferences, Schlüssel im Android Keystore)
- 🔧 Beschädigter Keystore führt nicht mehr zum Absturz, Speicher wird zurückgesetzt
- 🔧 Kein Cloud-Backup und kein Gerätetransfer (data_extraction_rules, Android 12+)
- 🔧 Passwort nicht mehr im gespeicherten Instanz-Zustand (rememberSaveable entfernt)
- 🔧 Passwort nie in toString() oder Protokoll
- 🔧 Hinweis in der App: DDNS-Key statt Konto-Passwort verwenden (geringste Rechte, widerrufbar)
- ⚠️ security-crypto ist von Google als veraltet markiert; funktioniert, langfristig auf eigenen Keystore-Wrapper umstellen

## 3. Netzwerk
- ✅ Nur HTTPS, Basic-Auth nur über TLS
- 🔧 Klartext zusätzlich per network_security_config verboten; nur System-CAs (keine Nutzer-Zertifikate)
- 🔧 Weiterleitungen werden nicht verfolgt (Zugangsdaten gehen nie an fremde Hosts)
- 🔧 Antworten auf 4 KB begrenzt, Timeouts 15 s
- 🔧 Öffentliche IP nur übernommen, wenn zwei unabhängige Dienste übereinstimmen und sie öffentlich ist
- ⚠️ Kein Certificate Pinning (No-IP wechselt Zertifikate; Pinning würde die App bei jedem Wechsel lahmlegen)

## 4. Eingaben & Ausgaben
- 🔧 Hostnamen nach RFC 1123 geprüft, max. 20, keine Sonderzeichen (keine Parameter-Injektion in die URL)
- 🔧 Feldlängen begrenzt
- ✅ Alle URL-Parameter URL-kodiert
- 🔧 Unbekannte Serverantworten gekürzt und von Steuerzeichen befreit, bevor sie angezeigt werden

## 5. Android-Plattform
- ✅ Nur die Launcher-Activity ist exportiert, sie nimmt keine Daten von außen an
- ✅ Keine WebView, kein Content Provider, keine Broadcast-Receiver
- 🔧 Minimale Berechtigungen: INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS (erst bei Bedarf angefragt)
- 🔧 PendingIntent explizit und FLAG_IMMUTABLE
- ⚠️ Kein FLAG_SECURE (Screenshots erlaubt, damit Support-Screenshots möglich sind; Passwort ist maskiert)

## 6. Protokoll-Regeln des Dienstes (No-IP)
- ✅ Update nur bei IP-Änderung, eindeutiger User-Agent
- ✅ Bei nohost/badauth/badagent/!donator/abuse: automatische Updates gestoppt
- 🔧 Nach 911 bzw. HTTP 5xx: 30 Minuten Pause
- 🔧 Manuelles Update max. 1× pro Minute
- 🔧 Neue Zugangsdaten/Hosts → nächster Lauf sendet sicher

## 7. Robustheit & Bedienung
- 🔧 Fehler im Hintergrund lösen eine Benachrichtigung aus (vorher unbemerkt)
- 🔧 Backoff bei Netzwerkfehlern (5 Min. exponentiell)
- 🔧 Auto-Update-Schalter lässt sich immer ausschalten
- 🔧 „Nur im WLAN“ standardmäßig an
- ⚠️ Doze/Akku-Optimierung einzelner Hersteller kann Prüfungen verzögern

## 8. Build & Release
- 🔧 R8 (Minify und Shrink) für Release, nötige -dontwarn-Regeln für Tink
- 🔧 Unit-Tests für Protokoll, IP- und Hostname-Prüfung (alle bestanden)
- ⚠️ **Android-Teil nicht kompiliert**: in der Arbeitsumgebung war kein Android SDK verfügbar. Erster Build in Android Studio nötig.
- ⚠️ Release-Signierung: eigener Keystore, nicht im Repo ablegen

## 9. Datenschutz
- ✅ Kein eigenes Backend, keine Analyse, kein Tracking
- ⚠️ Die IP-Dienste (ipify, Amazon, icanhazip) und No-IP sehen die öffentliche IP des Geräts
