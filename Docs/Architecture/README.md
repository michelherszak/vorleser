# Architektur und Findings

Stand: 16.10.2026. Die sechs priorisierten Verbesserungen sind umgesetzt. Die Übersicht beschreibt den aktuellen Code; verbleibende offene Punkte stehen am Ende.

## Zweck dieses Ordners

`Docs/Architecture` beschreibt, wie die Anwendung aufgebaut ist und welche Zusammenhänge im Code bestehen. Hier stehen Bestandsaufnahme, Komponenten, Datenfluss und übergreifende Findings. Begründungen einzelner Entscheidungen gehören nach [ADR](../ADR/README.md).

## Ordnerstruktur des Projekts

```text
app/
  build.gradle.kts                 Android-Modul, Abhängigkeiten, Build-Varianten
  src/
    main/
      AndroidManifest.xml         Berechtigungen, Einstiegspunkt, Backup-Verhalten
      java/de/vorleser/            Kotlin-Code, derzeit ein gemeinsames Package
      res/values/strings.xml      UI-Texte und Fehlermeldungen
    test/java/de/vorleser/         Lokale JVM-Tests
    androidTest/java/de/vorleser/  Android- und Compose-Tests auf Gerät/Emulator
gradle/
  gradle-daemon-jvm.properties     JVM-Kriterien für den Gradle-Daemon
  wrapper/                        Gradle-Wrapper und Distributionskonfiguration
Docs/
  Architecture/                   Architekturübersicht und Findings
  ADR/                            Entscheidungsprotokolle
```

Die Verzeichnisse `java/de/vorleser` enthalten Kotlin-Dateien; `java` ist hier der verwendete Android-Quellordner. Es gibt ein App-Modul und keine separaten Module für UI, Datenhaltung oder Fachlogik.

## Komponenten und Verantwortlichkeiten

- [MainActivity.kt](../../app/src/main/java/de/vorleser/MainActivity.kt) setzt Fensterverhalten und den Compose-Einstiegspunkt.
- [ReaderApp.kt](../../app/src/main/java/de/vorleser/ReaderApp.kt) verbindet Bildschirmzustand, Navigation, Berechtigungen und Android-Einstellungsdialoge.
- [CameraScreen.kt](../../app/src/main/java/de/vorleser/CameraScreen.kt), [PhotoReview.kt](../../app/src/main/java/de/vorleser/PhotoReview.kt) und [SettingsScreen.kt](../../app/src/main/java/de/vorleser/SettingsScreen.kt) enthalten die jeweiligen Oberflächen. [ReaderComponents.kt](../../app/src/main/java/de/vorleser/ReaderComponents.kt) bündelt gemeinsame Bedienelemente und Wiederherstellungshinweise.
- [ReaderDependencies.kt](../../app/src/main/java/de/vorleser/ReaderDependencies.kt) definiert austauschbare Abhängigkeiten und die Android-Factory für das ViewModel.
- [ReaderViewModel.kt](../../app/src/main/java/de/vorleser/ReaderViewModel.kt) steuert die Sitzung über `ReaderState` und `StateFlow`. Es koordiniert Fotoverarbeitung, Upload, Persistenz und Sprachausgabe. Die Android-Adapter kümmern sich um API-Schlüssel in privaten SharedPreferences und Kamera-Dateien.
- [ReadingSession.kt](../../app/src/main/java/de/vorleser/ReadingSession.kt) enthält das Sitzungsmodell und `SessionStore`. Dieser speichert die Seiten als JSON und verwaltet ein optionales Wiederholungsfoto.
- [PhotoFiles.kt](../../app/src/main/java/de/vorleser/PhotoFiles.kt) korrigiert EXIF-Ausrichtung und begrenzt Bilder auf maximal 2400 Pixel an der längsten Seite; Ausgabe ist JPEG mit Qualität 90.
- [ClaudeClient.kt](../../app/src/main/java/de/vorleser/ClaudeClient.kt) erzeugt den API-Aufruf, wertet die strukturierte Antwort aus und ordnet Fehler den Aktionen Wiederholen, Einstellungen oder Neuaufnahme zu.
- [Speaker.kt](../../app/src/main/java/de/vorleser/Speaker.kt) bindet Android Text-to-Speech an und wählt eine installierte deutsche Stimme ohne ausgewiesenen Netzwerkbedarf.
- [SpeechPlayback.kt](../../app/src/main/java/de/vorleser/SpeechPlayback.kt) verwaltet Textabschnitte, Wiedergabestatus und Callback-Generationen unabhängig von der Android-TTS-Implementierung.

## Datenfluss und Laufzeitdateien

1. CameraX schreibt eine Aufnahme als `capture-<UUID>.jpg` in das App-Cacheverzeichnis.
2. `PhotoFiles` bereitet das Bild auf. `SessionStore` speichert es als `page-<UUID>.jpg` unter `noBackupFilesDir/reading-session`; `session.json` verweist darauf.
3. Die Oberfläche zeigt das Foto. Erst „Seite einlesen“ startet den API-Aufruf mit dem Base64-kodierten Bild.
4. Ein akzeptiertes Ergebnis wird an die bisherigen Seiten angehängt. Beim erfolgreichen Speichern ersetzt `session.tmp` atomar die Datei `session.json`; anschließend wird das nicht mehr benötigte Foto gelöscht. Ein fehlgeschlagener Commit setzt `unsavedChanges`; fehlgeschlagene Fotobereinigung setzt separat `cleanupPending`.
5. `Speaker` liest die neue Seite vor. „Vorlesen“ verwendet den gesamten Brief in Seitenreihenfolge.
6. Beim erneuten Öffnen lädt das ViewModel die Sitzung und versucht die Bereinigung überholter Sitzungsfotos erneut. Das aktuelle Wiederholungsfoto bleibt erhalten und führt zur Vorschau; ein Upload wird dabei nicht automatisch gestartet.

Diese Laufzeitverzeichnisse liegen auf dem Android-Gerät und sind von der Repository-Ordnerstruktur zu unterscheiden.

## Findings

### Die Trennung nach Testumgebung ist bereits sinnvoll

`src/test` prüft API-Verarbeitung mit MockWebServer, Sitzungsdateien und Wiedergabelogik. `src/androidTest` prüft Bitmap-Verarbeitung und Compose-/Kameraabläufe. Damit liegt Android-unabhängige Logik überwiegend in lokal prüfbaren Klassen. Die Regressionstests prüfen insbesondere die Grenzen zwischen Lesedienst, lokalem Speicher und Wiedergabe.

### Lokales Speichern kann unabhängig vom Upload wiederholt werden

Bei einem Speicherfehler bleibt der erkannte Text im aktuellen Zustand sichtbar. „Speichern erneut versuchen“ schreibt diesen Zustand erneut auf das Gerät und ruft weder den Lesedienst noch die automatische Sprachausgabe erneut auf. Bis zum erfolgreichen Speichern ist „Weitere Seite“ deaktiviert, damit kein weiterer Aufnahmeablauf den ungesicherten Text verdrängt. Nach einem Prozessende ist dieser Text weiterhin verloren; darauf weist die Oberfläche hin.

### Fehlgeschlagene Fotobereinigung ist sichtbar und wiederholbar

`SessionStore.save` meldet nach erfolgreichem Metadaten-Commit mit `SaveResult`, ob alte Fotos zurückgeblieben sind. Das ist kein fehlgeschlagener Commit. Die Oberfläche bietet „Fotos erneut löschen“ an; zusätzlich bereinigt der nächste Start überholte Sitzungsfotos. Ein Fehler beim Schreiben der Metadaten lässt die alte Sitzung und deren Wiederholungsfoto unangetastet.

### UI und Android-Abhängigkeiten sind getrennt

Die Activity enthält nur noch den Einstiegspunkt. Kamera, Vorschau und Einstellungen liegen in eigenen Dateien. Das ViewModel erhält `ReadingStore`, `DocumentReader`, `ReaderSpeaker`, `ApiKeyStore`, `CaptureFiles` und einen IO-Dispatcher über den Konstruktor. Die Factory verbindet sie mit den Android-Implementierungen; JVM-Tests verwenden kontrollierbare Abhängigkeiten und einen Test-Dispatcher.

### Texte und Fehleraktionen sind getrennt

UI-Texte, Fortschrittsanzeigen und sichtbare Fehlertexte liegen in `strings.xml`; die Oberfläche löst Ressourcen-IDs auf. Mehrseitige Brieftitel verwenden eine Plural-Ressource. `ProblemKind.CAMERA` steuert die Kameraberechtigungs-Hilfe unabhängig vom Meldungstext. Das Prompt- und Protokollformat des Lesedienstes bleibt im Client.

### Unbenutzte Datei entfernt

Die leere, nicht eingebundene Datei `app/proguard-rules.pro` wurde entfernt. Die Release-Konfiguration verwendet weiterhin die Android-Standardregeln zur Optimierung.

### Wiederherstellung ist gezielt auf einen aktuellen Brief ausgelegt

Die atomare Metadatenspeicherung und das zurückbehaltene Foto ermöglichen einen erneuten Versuch, ohne vorherige Seiten zu verlieren. Es gibt weder Archiv noch Versionsfeld oder Migrationspfad für `session.json`. Vor Änderungen am Speicherformat sollte die Behandlung bereits gespeicherter Sitzungen ausdrücklich festgelegt werden. Siehe [ADR 0001](../ADR/0001-aktuelle-lesesitzung.md).

### Regressionstests und verbleibende Grenzen

[ReaderViewModelTest](../../app/src/test/java/de/vorleser/ReaderViewModelTest.kt) prüft mit einem echten `SessionStore` unter anderem erfolgreichen Upload mit Speicherung und Vorlesen, wiederholte Speicherfehler ohne erneuten Upload, getrennte Bereinigungsfehler, Bereinigung beim nächsten Start und Abbruch mit sofortigem Neuversuch. [ReaderRecoveryTest](../../app/src/androidTest/java/de/vorleser/ReaderRecoveryTest.kt) prüft die sichtbaren Aktionen zum lokalen Speichern und erneuten Löschen mit simuliertem Lesedienst und simulierter Sprachausgabe.

`PhotoFilesTest` prüft weiterhin eine 90-Grad-Drehung, Größenbegrenzung und defekte Bilder, aber nicht alle im Code behandelten EXIF-Spiegelungen. Reale Transkriptionsqualität, tatsächliche Aussprache und Offline-Funktion der installierten Stimme müssen am Zieltablet geprüft werden. Die externen API-Parameter wurden nicht gegen den produktiven Dienst geprüft.

## Prüfstand

Am 16.10.2026 erfolgreich ausgeführt:

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`: Debug-APK gebaut, 36 JVM-Tests bestanden, keine Lint-Fehler. Lint meldet weiterhin Warnungen, unter anderem zu SDK-/Abhängigkeitsständen und allgemeinen Android-Konfigurationen.
- `./gradlew :app:connectedDebugAndroidTest`: alle 9 Gerätetests bestanden, einschließlich Kameraaufnahme und der zwei neuen Wiederherstellungstests.
- Lokale Markdown-Verweise und `git diff --check` geprüft.

Verwendet wurden Android Studios Java-Laufzeit, das lokale Android SDK und der Emulator `Medium_Phone_API_37.0`. Der erste Gerätetestlauf blieb bei einer Aufnahme über die virtuelle Kamera ohne Bildrückgabe. Nach einem Neustart mit `-camera-back emulated -camera-front emulated` bestand die gesamte Suite. Der produktive API-Dienst wurde dabei nicht aufgerufen.

## Zugehörige Entscheidungen

- [ADR 0001: Eine aktuelle Lesesitzung lokal speichern](../ADR/0001-aktuelle-lesesitzung.md)
- [ADR 0002: Foto direkt und ausdrücklich an den Lesedienst senden](../ADR/0002-direkter-lesedienst.md)
- [ADR 0003: Lokale deutsche Sprachausgabe verwenden](../ADR/0003-lokale-sprachausgabe.md)
- [ADR 0004: Speichern und Fotobereinigung getrennt wiederholen](../ADR/0004-lokale-wiederholungen.md)
