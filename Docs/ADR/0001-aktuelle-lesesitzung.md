# ADR 0001: Eine aktuelle Lesesitzung lokal speichern

Datum: 16.10.2026

Status: Aus dem implementierten Stand rekonstruiert

## Kontext

Ein Brief kann aus mehreren Seiten bestehen. Bereits erkannter Text und ein noch nicht erfolgreich eingelesenes Foto sollen beim Neuerstellen der Activity oder erneuten Öffnen der App erhalten bleiben.

## Entscheidung im vorhandenen Code

`ReadingSession` enthält die geordneten Textseiten und optional den Namen eines Fotos. `SessionStore` speichert genau diese aktuelle Sitzung unter `noBackupFilesDir/reading-session`. Metadaten werden zunächst in `session.tmp` geschrieben und synchronisiert und ersetzen anschließend atomar `session.json`.

Ein neues Foto ersetzt den bisherigen Entwurf; erfolgreich gespeicherter Text benötigt das Foto nicht mehr. „Neuer Brief“ speichert eine leere Sitzung und versucht, bisherige Seitenfotos zu entfernen. Zurückbleibende Fotos werden nach der in [ADR 0004](0004-lokale-wiederholungen.md) beschriebenen Regel sichtbar gemacht und erneut bereinigt. Das ViewModel stellt die Sitzung wieder her, ohne einen Upload erneut zu starten.

## Konsequenzen und Findings

- Die Wiederherstellung benötigt weder Benutzerkonto noch Backend. Der Speicher liegt im privaten App-Bereich außerhalb von Android-Backups.
- Eine fehlgeschlagene Validierung eines neuen Fotos überschreibt die bestehende Sitzung nicht. Die Bereinigung alter Fotos erfolgt nach dem Ersetzen der Metadaten.
- Die Speicherung bietet kein Briefarchiv. „Neuer Brief“ entfernt den aktuellen Inhalt ohne Wiederherstellungsfunktion.
- Das JSON-Format enthält kein Versionsfeld. Eine spätere Formatänderung benötigt eine Entscheidung zum Umgang mit bestehenden Daten.
- Bei beschädigten Metadaten meldet der Code einen Ladefehler, statt still eine neue Sitzung zu erzeugen.

## Offene Fragen

Wie sollen bestehende Sitzungen bei einer künftigen Änderung des Speicherformats migriert werden?

## Belege

- [ReadingSession und SessionStore](../../app/src/main/java/de/vorleser/ReadingSession.kt)
- [ReaderViewModel](../../app/src/main/java/de/vorleser/ReaderViewModel.kt)
- [SessionStoreTest](../../app/src/test/java/de/vorleser/SessionStoreTest.kt)
