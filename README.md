# Vorleser

Android-App für die Familie: eine Briefseite fotografieren, das Foto prüfen, Text einlesen und auf Deutsch vorlesen lassen. Kotlin, Jetpack Compose und CameraX; Android 8 oder neuer.

## Einlesen und Vorlesen

- **Texterkennung:** Claude Sonnet 5.5 (`claude-sonnet-5-5`) verarbeitet das Foto über die Anthropic-API. Das Einlesen benötigt Internet und API-Guthaben. Beträge, Datumsangaben und Namen werden im Original übernommen; unlesbare Stellen sollen gekennzeichnet werden. Eine KI-Transkription kann trotzdem Fehler enthalten.
- **Sprachausgabe:** eine installierte deutsche Android-Offline-Stimme mit etwas langsamerem Tempo. Die App wählt eine Stimme, die laut Android keine Netzwerkverbindung benötigt. Fehlt sie, führt „Stimme einrichten“ zu den Android-Einstellungen. Nach der Installation die Stimme erneut prüfen.
- Jede Aufnahme wird vor dem Hochladen angezeigt. „Neu fotografieren“ ersetzt nur die aktuelle Aufnahme; bereits eingelesene Seiten bleiben erhalten.
- „Weitere Seite“ hängt die nächste Seite an denselben Brief an. Nach dem Einlesen wird die neue Seite vorgelesen; „Vorlesen“ liest den gesamten Brief. „Stopp“ beendet auch eine noch wartende Sprachausgabe.
- „Abbrechen“ beendet eine laufende Verbindung und kehrt zur Fotovorschau zurück. Wiederholungen erfolgen nur auf ausdrücklichen Tastendruck.
- Abgeschnittene, leere und unvollständige API-Antworten werden nicht als vollständige Seite übernommen. Die App bietet erneutes Einlesen oder eine neue Aufnahme an. Sehr dichte Seiten bei Bedarf in kleineren Abschnitten fotografieren.

## Einrichtung und Daten

1. Unter „Einstellungen“ den eigenen Anthropic-API-Schlüssel eintragen. Die Eingabe ist verdeckt. Der Schlüssel wird derzeit in privaten Android-App-Einstellungen gespeichert, **ohne zusätzliche Verschlüsselung**. Diese Variante ist für eigene Geräte gedacht.
2. Eine deutsche Offline-Stimme in Android installieren und prüfen.
3. Kamera freigeben und eine vollständige, scharfe Seite bei gutem Licht fotografieren.

Mit „Seite einlesen“ wird das ausgewählte Foto über HTTPS an Anthropic geschickt. Die Datenverarbeitung auf Anbieterseite richtet sich nach dessen API-Bedingungen. Für das Vorlesen verwendet die App die lokale Android-Sprachausgabe.

Der aktuelle Brief und ein noch nicht erfolgreich eingelesenes Foto werden im privaten, von Android-Backups ausgeschlossenen App-Verzeichnis gespeichert. So bleiben Text, Seitenreihenfolge und Wiederholungsfoto nach dem Neuerstellen oder Beenden der App erhalten. Ein abgebrochener Upload wird beim Wiederöffnen nicht automatisch wiederholt. Nach erfolgreichem Einlesen wird das Wiederholungsfoto entfernt. „Neuer Brief“ löscht nach Bestätigung den bisher gespeicherten Brief und die Aufnahme. Es gibt kein Briefarchiv und keine Export- oder Teilen-Funktion.

Die App wird durch diese Änderungen nicht veröffentlicht. Für eine spätere öffentliche Verteilung wären ein authentifizierter Backend-Proxy statt eines gemeinsamen App-Schlüssels, eine bewusste Kostenbegrenzung und ein passender Datenschutzhinweis nötig.

## Bauen und prüfen

Android SDK 35 und ein für den Gradle-Wrapper geeignetes JDK werden benötigt. Android Studios Java-Laufzeit kann über `JAVA_HOME` ausgewählt werden; die Gradle-Daemon-Konfiguration liegt unter `gradle/gradle-daemon-jvm.properties`.

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Der zweite Aufruf benötigt ein Android-Testgerät oder einen gestarteten Emulator. Die Tests verwenden lokale Beispieldaten bzw. einen simulierten API-Server; es entstehen keine Anthropic-Aufrufe. Getestet werden unter anderem Sprachwarteschlange, Abbrechen, API-Fehler, Trunkierung, Wiederherstellung, mehrere Seiten und der Kamera-/Vorschauablauf. Lesegenauigkeit, reale Kameraqualität und die tatsächlich installierte Offline-Stimme müssen zusätzlich am Zieltablet geprüft werden.

Die Debug-APK liegt unter `app/build/outputs/apk/debug/app-debug.apk`.

Für eine signierte Release-Version `keystore.properties` mit `storeFile`, `storePassword`, `keyAlias`, `keyPassword` lokal anlegen und `./gradlew :app:bundleRelease` ausführen. Schlüsseldateien und API-Schlüssel gehören nicht ins Repository.
