# Architecture Decision Records

## Zweck dieses Ordners

`Docs/ADR` hält einzelne Architekturentscheidungen mit ihrem Kontext und ihren Folgen fest. Die Beschreibung des gesamten Systems und übergreifende Findings stehen unter [Architecture](../Architecture/README.md).

## Vorhandene Aufzeichnungen

- [0001 – Eine aktuelle Lesesitzung lokal speichern](0001-aktuelle-lesesitzung.md)
- [0002 – Foto direkt und ausdrücklich an den Lesedienst senden](0002-direkter-lesedienst.md)
- [0003 – Lokale deutsche Sprachausgabe verwenden](0003-lokale-sprachausgabe.md)
- [0004 – Speichern und Fotobereinigung getrennt wiederholen](0004-lokale-wiederholungen.md)

Die Einträge 0001 bis 0003 sind am 16.10.2026 aus dem vorhandenen Code rekonstruiert. Sie dokumentieren das implementierte Verhalten; ursprüngliche Entscheidungsdiskussionen oder Freigaben sind im Repository nicht belegt. ADR 0004 dokumentiert die anschließend beauftragte und implementierte Weiterentwicklung. Offene Fragen sind keine bereits beschlossenen Änderungen.

## Struktur künftiger Einträge

Pro Entscheidung eine Markdown-Datei mit fortlaufender Nummer und sprechendem Namen: `0005-kurzer-titel.md`. Jeder Eintrag enthält Datum, Status, Kontext, Entscheidung, Konsequenzen, offene Fragen und Belege im Code.

Neue Vorschläge erhalten den Status „Vorgeschlagen“. Eine spätere Ablösung verweist auf den nachfolgenden ADR, damit die Entwicklung nachvollziehbar bleibt. Reine Komponentenbeschreibungen und Testberichte gehören in den Architecture-Ordner.
