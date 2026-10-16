# ADR 0004: Speichern und Fotobereinigung getrennt wiederholen

Datum: 16.10.2026

Status: Implementiert auf Grundlage der beauftragten Verbesserungen

## Kontext

Die App konnte erkannte Seiten bei einem Speicherfehler nur vorübergehend anzeigen. Eine erneute lokale Speicherung war nicht möglich. Außerdem ignorierte die Fotobereinigung das Ergebnis von `File.delete()`, sodass die Oberfläche eine vollständig abgeschlossene Löschung nahelegen konnte, obwohl Fotos zurückblieben.

## Entscheidung

Das ViewModel unterscheidet nicht gespeicherte Änderungen (`unsavedChanges`) und noch offene Fotobereinigung (`cleanupPending`). Ein fehlgeschlagener Metadaten-Commit wird weiterhin als `IOException` gemeldet. Nach einem erfolgreichen Commit liefert `ReadingStore.save` dagegen einen `SaveResult`, der zurückgebliebene Fotos separat ausweist.

„Speichern erneut versuchen“ speichert ausschließlich die bereits erkannte Sitzung. Diese Aktion startet weder einen Upload noch eine erneute automatische Wiedergabe. Solange Änderungen nicht gespeichert sind, wird keine weitere Seite aufgenommen. Ein ausdrücklich bestätigter neuer Brief kann die Sitzung weiterhin verwerfen.

„Fotos erneut löschen“ wiederholt ausschließlich die Bereinigung. Beim Start wird sie nach dem Laden der gültigen Sitzung ebenfalls versucht. Das aktuell referenzierte Wiederholungsfoto bleibt geschützt. Eine beschädigte Sitzung wird nicht stillschweigend durch eine leere Sitzung ersetzt oder bereinigt.

Die Abhängigkeiten des ViewModels werden über den Konstruktor übergeben. Eine Android-Factory stellt die produktiven Implementierungen bereit; Tests können Schreib- und Löschfehler sowie verzögerten Abbruch gezielt erzeugen.

## Konsequenzen

- Speicherplatzprobleme erfordern keinen erneuten kostenpflichtigen Upload derselben bereits erkannten Seite.
- Ein erfolgreicher Commit bleibt auch bei fehlgeschlagener Bereinigung ein erfolgreicher Commit. Seiten werden deshalb nicht versehentlich als fehlend behandelt oder erneut angehängt.
- Ein Prozessende vor erfolgreicher lokaler Speicherung verliert weiterhin den nur im Arbeitsspeicher gehaltenen Text. Die Oberfläche weist darauf hin.
- Zurückbleibende Sitzungsfotos werden sichtbar gemacht und erneut zu löschen versucht. Auch eine wiederholte Löschung kann scheitern; eine sofortige vollständige Entfernung wird dann nicht behauptet.
- Das bestehende JSON-Format bleibt lesbar; diese Änderung führt kein neues Speicherformat ein.

## Validierung

JVM-Regressionstests verwenden den echten `SessionStore` mit kontrollierten Schreib- und Löschfehlern. Compose-Gerätetests prüfen die sichtbaren Wiederholungsaktionen mit simuliertem Lesedienst und simulierter Sprachausgabe. Dadurch entstehen keine produktiven API-Aufrufe.

## Belege

- [ReaderViewModel](../../app/src/main/java/de/vorleser/ReaderViewModel.kt)
- [SessionStore](../../app/src/main/java/de/vorleser/ReadingSession.kt)
- [Abhängigkeiten und Android-Factory](../../app/src/main/java/de/vorleser/ReaderDependencies.kt)
- [ReaderViewModelTest](../../app/src/test/java/de/vorleser/ReaderViewModelTest.kt)
- [ReaderRecoveryTest](../../app/src/androidTest/java/de/vorleser/ReaderRecoveryTest.kt)
