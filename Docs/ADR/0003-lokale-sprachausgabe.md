# ADR 0003: Lokale deutsche Sprachausgabe verwenden

Datum: 16.10.2026

Status: Aus dem implementierten Stand rekonstruiert

## Kontext

Nach dem Einlesen soll die App den deutschen Text vorlesen. Lange Briefe müssen die Eingabegrenzen der Android-Sprachausgabe berücksichtigen; „Stopp“ soll auch eine während der Initialisierung wartende Wiedergabe beenden.

## Entscheidung im vorhandenen Code

`Speaker` verwendet Android Text-to-Speech und wählt eine installierte deutsche Stimme, deren Metadaten keinen Netzwerkbedarf ausweisen. Stimmen mit `Locale.GERMANY` werden bevorzugt, danach die gemeldete Qualität. Die Sprechgeschwindigkeit wird auf `0.85` gesetzt.

`SpeechPlayback` steuert die Wiedergabe über `SpeechOutput`. Text wird entsprechend der maximalen Eingabelänge aufgeteilt; zusammengehörige UTF-16-Surrogatpaare bleiben erhalten. Generationskennungen verhindern, dass alte Abschluss- oder Fehlercallbacks eine neue Wiedergabe beeinflussen.

## Konsequenzen und Findings

- Für die Sprachausgabe verwendet die App keinen eigenen Cloud-Aufruf. Das Einlesen des Fotos bleibt ein separater Online-Schritt.
- Eine passende Stimme muss auf dem Gerät vorhanden sein. Andernfalls zeigt die App Einrichtungshilfe und ermöglicht eine erneute Prüfung.
- Die Trennung zwischen Android-Anbindung und Wiedergabelogik ermöglicht JVM-Tests mit einer simulierten Ausgabe.
- Die Tests prüfen unter anderem Textreihenfolge, Stopp während der Initialisierung, veraltete Callbacks und Unicode-Grenzen. Die tatsächliche Aussprache und Offline-Funktion der installierten Engine sind dadurch nicht belegt.

## Offene Fragen

Welche TTS-Engine und deutsche Stimme werden auf den Zieltablets verwendet? Deren Verhalten muss auf diesen Geräten, einschließlich eines Versuchs ohne Netzwerk, geprüft werden.

## Belege

- [Speaker](../../app/src/main/java/de/vorleser/Speaker.kt)
- [SpeechPlayback](../../app/src/main/java/de/vorleser/SpeechPlayback.kt)
- [SpeechPlaybackTest](../../app/src/test/java/de/vorleser/SpeechPlaybackTest.kt)
