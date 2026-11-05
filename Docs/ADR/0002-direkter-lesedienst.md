# ADR 0002: Foto direkt und ausdrücklich an den Lesedienst senden

Datum: 16.10.2026

Status: Aus dem implementierten Stand rekonstruiert

## Kontext

Die App lässt fotografierte Dokumentseiten durch einen externen Dienst transkribieren. Die vorhandene README beschreibt die Nutzung auf eigenen Geräten mit einem selbst eingetragenen Anthropic-API-Schlüssel.

## Entscheidung im vorhandenen Code

Nach der Foto-Vorschau startet „Seite einlesen“ einen direkten HTTPS-Aufruf an die Anthropic Messages API. `ClaudeClient` sendet ein JPEG als Base64 und fordert eine strukturierte Antwort mit `kind` und `text` an. Modellkennung und Anfrageparameter sind im Client festgelegt.

Der API-Schlüssel liegt in privaten SharedPreferences ohne zusätzliche Verschlüsselung. Es gibt keinen Backend-Proxy. Verbindungswiederholungen durch OkHttp sind deaktiviert; ein erneuter Versuch nach einem angezeigten Fehler erfordert eine Nutzeraktion. Das Abbrechen der Coroutine beendet auch den HTTP-Aufruf.

## Konsequenzen und Findings

- Die App benötigt für das Einlesen Internet und einen nutzbaren persönlichen API-Schlüssel. Ein eigenes Backend ist im Repository nicht vorhanden.
- Die Foto-Vorschau macht den Versand zu einem ausdrücklichen Schritt. Bereits gelesene Seiten bleiben bei einem fehlgeschlagenen neuen Upload erhalten.
- Abgeschnittene oder unvollständige Antworten werden verworfen. Fehlermeldungen führen je nach Ursache zu Wiederholen, Einstellungen oder Neuaufnahme.
- Anfrageformat und Modellkennung sind mit der App-Version gekoppelt. Die MockWebServer-Tests bestätigen das erzeugte Format, aber keine tatsächliche Unterstützung durch den externen Dienst.
- Die Schlüsselablage und der direkte Clientzugriff entsprechen dem dokumentierten Einsatz auf eigenen Geräten. Die README nennt einen authentifizierten Backend-Proxy und Kostenbegrenzung als Themen für eine spätere öffentliche Verteilung.

## Offene Fragen

Soll eine öffentliche Verteilung unterstützt werden? Falls ja, sind Backend, Authentifizierung und Kostensteuerung als eigene Entscheidung zu konkretisieren.

## Belege

- [ClaudeClient](../../app/src/main/java/de/vorleser/ClaudeClient.kt)
- [ReaderViewModel und Schlüsselablage](../../app/src/main/java/de/vorleser/ReaderViewModel.kt)
- [ClaudeClientTest](../../app/src/test/java/de/vorleser/ClaudeClientTest.kt)
- [Projekt-README](../../README.md)
