# GitHub → Google Play: interner Test

Stand: 05.11.2026. Repository: `michelherszak/vorleser`, Paketname: `com.octopotato.vorleser`.

## Vorbereiteter Ablauf

- **Android checks** läuft bei Pull Requests und Pushes nach `master`: Python-Tests der Upload-Logik, Android-Debug-Build, Release-Bundle ohne Upload-Schlüssel, JVM-Tests und Lint. Google-Zugang und Signierung sind dafür nicht erforderlich.
- **Google Play internal** wird unter GitHub → Actions → Run workflow gestartet. Es führt zuerst dieselben Prüfungen aus, baut anschließend ein signiertes AAB und legt es zusammen mit der R8-Mapping-Datei für sieben Tage als Workflow-Artefakt ab.
- `upload=false` baut nur das signierte Bundle. Das ist der Einstieg für den ersten manuellen Upload in Play Console.
- `upload=true` authentifiziert den separaten Upload-Job über OIDC und ein Google-Dienstkonto und lädt ausschließlich in den Track `internal` hoch.
- `release_status=draft` legt einen Entwurf an; Tester erhalten ihn noch nicht. `completed` gibt ihn für interne Tester frei, sofern Google die App-Einrichtung und Veröffentlichung akzeptiert. Der Workflow hat keinen Produktionskanal.
- Beide Signierungs-/Upload-Jobs sind auf `master` beschränkt und verwenden die GitHub-Umgebung `play-internal`. Pull Requests bekommen keine Upload-Secrets und kein Google-OIDC-Token. Externe Actions sind auf Commit-Hashes fixiert.

Die manuellen Workflows werden in GitHub regulär angezeigt, sobald diese Dateien auf dem Standardbranch `master` liegen. Ein Pull Request muss dafür zuerst zusammengeführt werden.

## Einmalig: Play-Console-App und Upload-Schlüssel

1. In der bestehenden Play-Console-App den Paketnamen prüfen. Er muss `com.octopotato.vorleser` sein. Die numerische App-ID in der Dashboard-URL ist kein Android-Paketname.
2. Prüfen, ob bereits ein Bundle/APK hochgeladen und ein Upload-Zertifikat registriert wurde. In diesem Fall den passenden vorhandenen Upload-Schlüssel verwenden; keinen neuen Schlüssel blind erzeugen. Ein verlorener Upload-Schlüssel benötigt den von Google vorgesehenen Reset.
3. Für eine neue App ohne bisherigen Schlüssel einen langlebigen Upload-Keystore erstellen und sicher sichern. Android Studio bietet dafür **Build → Generate Signed Bundle / APK → Android App Bundle → Create new**. Den Keystore und beide Passwörter dauerhaft aufbewahren. Der Debug-Schlüssel ist kein Release-Upload-Schlüssel.
4. Unter Repository → Settings → Environments → `play-internal` folgende Secrets anlegen:

- `ANDROID_KEYSTORE_BASE64`: Base64-Inhalt des Upload-Keystores als eine Zeile.
- `ANDROID_KEYSTORE_PASSWORD`: Keystore-Passwort.
- `ANDROID_KEY_ALIAS`: Alias des Upload-Schlüssels.
- `ANDROID_KEY_PASSWORD`: Passwort des Upload-Schlüssels.

Die Umgebung wurde bereits angelegt und auf den Branch `master` begrenzt. Keystore-Datei, Passwörter und API-Schlüssel dürfen nicht ins öffentliche Repository oder in Chatnachrichten kopiert werden.

Ein vorhandener Keystore lässt sich aus dem eigenen Terminal ohne Ausgabe seines Inhalts als Secret übertragen:

```sh
base64 < /absoluter/pfad/upload-key.jks | tr -d '\n' | \
  gh secret set ANDROID_KEYSTORE_BASE64 --repo michelherszak/vorleser --env play-internal

gh secret set ANDROID_KEYSTORE_PASSWORD --repo michelherszak/vorleser --env play-internal
gh secret set ANDROID_KEY_ALIAS --repo michelherszak/vorleser --env play-internal
gh secret set ANDROID_KEY_PASSWORD --repo michelherszak/vorleser --env play-internal
```

Die letzten drei Befehle fragen den jeweiligen Wert interaktiv ab.

5. Nach dem Merge den Workflow einmal mit `upload=false` starten. Das Artefakt `play-internal-bundle` herunterladen und `app-release.aab` für die erste Einrichtung manuell unter **Testen → Interner Test** in Play Console hochladen. Play App Signing und die von der Console angezeigten Einrichtungsaufgaben abschließen. Die Freundin mit dem Google-Konto ihres Android-Geräts zur Testerliste hinzufügen und später den Opt-in-Link verwenden.

## Einmalig: Google Cloud und Play-API

Die [Google-Anleitung](https://developers.google.com/android-publisher/getting_started) beschreibt die Voraussetzungen. Ein vorhandenes Cloud-Projekt kann verwendet werden. Die Play-Entwickler-ID ist nicht die Cloud-Projekt-ID.

Die folgenden Befehle werden im angemeldeten Google Cloud Shell/Terminal ausgeführt. `PROJECT_ID` durch die tatsächliche Projekt-ID ersetzen. Sie aktivieren die nötigen APIs, erstellen ein Dienstkonto und richten die GitHub-Identität ein; dafür sind entsprechende Verwaltungsrechte im Projekt nötig.

```sh
PLAY_PROJECT_ID='PROJECT_ID'
PLAY_PROJECT_NUMBER="$(gcloud projects describe "$PLAY_PROJECT_ID" --format='value(projectNumber)')"
PLAY_SERVICE_ACCOUNT="github-play-internal@${PLAY_PROJECT_ID}.iam.gserviceaccount.com"

gcloud services enable androidpublisher.googleapis.com iam.googleapis.com iamcredentials.googleapis.com \
  sts.googleapis.com --project="$PLAY_PROJECT_ID"

gcloud iam service-accounts create github-play-internal \
  --project="$PLAY_PROJECT_ID" --display-name='Vorleser GitHub internal releases'

gcloud iam workload-identity-pools create github-vorleser \
  --project="$PLAY_PROJECT_ID" --location=global --display-name='Vorleser GitHub'

gcloud iam workload-identity-pools providers create-oidc github \
  --project="$PLAY_PROJECT_ID" --location=global --workload-identity-pool=github-vorleser \
  --issuer-uri='https://token.actions.githubusercontent.com' \
  --attribute-mapping='google.subject=assertion.sub,attribute.repository_id=assertion.repository_id' \
  --attribute-condition="assertion.repository_id == '1421831711' && assertion.repository_owner_id == '340969342' && assertion.ref == 'refs/heads/master' && assertion.workflow_ref == 'michelherszak/vorleser/.github/workflows/play-internal.yml@refs/heads/master'"

gcloud iam service-accounts add-iam-policy-binding "$PLAY_SERVICE_ACCOUNT" \
  --project="$PLAY_PROJECT_ID" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/${PLAY_PROJECT_NUMBER}/locations/global/workloadIdentityPools/github-vorleser/attribute.repository_id/1421831711"
```

Bei bereits vorhandenen gleichnamigen Ressourcen deren Konfiguration prüfen und weiterverwenden, statt die Erstellungsbefehle erneut auszuführen. Keine projektweite Rolle „Owner“/„Editor“ für das Dienstkonto vergeben. Seine Veröffentlichungsrechte erhält es gezielt in Play Console.

Unter **Play Console → Nutzer und Berechtigungen** die E-Mail des Dienstkontos einladen und auf die Vorleser-App beschränken. Nötig sind das Anzeigen der App-Informationen sowie das Veröffentlichen in Test-Tracks; Test-Verwaltungsrechte nur, falls Google sie für die verwendete Aktion verlangt. Keine Produktions-, Finanz- oder Kontoadministrationsrechte vergeben.

Unter GitHub → Settings → Environments → `play-internal` diese **Variables** setzen:

- `GCP_WORKLOAD_IDENTITY_PROVIDER`: `projects/PROJEKTNUMMER/locations/global/workloadIdentityPools/github-vorleser/providers/github`
- `GCP_SERVICE_ACCOUNT`: `github-play-internal@PROJECT_ID.iam.gserviceaccount.com`
- `PLAY_VERSION_CODE_BASE`: bereits auf `1000` gesetzt, vor Verwendung mit den vorhandenen Play-Versionen abgleichen.

Diese Bezeichner sind keine Passwörter. Für die Google-Authentifizierung wird keine private Service-Account-JSON-Datei benötigt. Der Upload-Job fordert erst unmittelbar vor dem API-Aufruf ein kurzlebiges Token mit dem Scope `https://www.googleapis.com/auth/androidpublisher` an. Grundlagen: [Google Cloud WIF](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines), [GitHub-Authentifizierungsaction](https://github.com/google-github-actions/auth).

## Eine neue Version bereitstellen

1. Änderungen über einen geprüften Pull Request nach `master` übernehmen.
2. GitHub → Actions → **Google Play internal → Run workflow**, Branch `master`.
3. `version_name` eingeben, beispielsweise `1.1.0`.
4. `upload=true` wählen. Für den ersten Verbindungstest `draft` beibehalten; für die beabsichtigte Bereitstellung an interne Tester `completed` wählen.
5. Nach einem erfolgreichen Lauf den Release-Stand in Play Console prüfen. Bei `draft` den Entwurf dort fertigstellen. Die installierte Test-App kann anschließend über Google Play aktualisiert werden.

Google kann zusätzliche Einrichtungsaufgaben, Überprüfung oder Freigaben verlangen. Ein erfolgreicher API-Upload allein garantiert keine sofortige Installierbarkeit. Die Berechtigungen und der erste produktive API-Aufruf müssen mit dem echten Konto noch geprüft werden.

## Versionierung und Fehlerbehandlung

Der `versionCode` ist `PLAY_VERSION_CODE_BASE + github.run_number` des Release-Workflows. Der erste Lauf mit Basis 1000 verwendet 1001. Die Basis vor dem ersten Upload so wählen, dass der berechnete Wert über allen bisherigen Versionen liegt; danach nicht absenken. `version_name` ist davon unabhängig.

Ein **neuer** Workflow-Lauf bekommt eine höhere Nummer. „Re-run jobs“ behält dieselbe Nummer und eignet sich nicht zur erneuten Veröffentlichung einer bereits verwendeten Version. Das Upload-Skript prüft vor dem Upload die vorhandenen Bundle-, APK- und Track-Versionen und lehnt zu kleine Versionsnummern ab. Google prüft die Version zusätzlich selbst.

Das Skript erstellt ein Play-Edit, lädt Bundle und Mapping hoch, aktualisiert nur `internal` und committet das Edit. Bei Fehlern wird das noch offene Edit nach Möglichkeit verworfen. Mutierende API-Aufrufe werden nicht automatisch wiederholt. Ein bereits laufender Review wird nicht automatisch abgebrochen (`ERROR_IF_IN_REVIEW`). Bei einem Commit-Timeout den tatsächlichen Release-Stand in Play Console prüfen, bevor ein neuer Lauf gestartet wird.

Lokale Release-Builds unterstützen weiter `keystore.properties`. Im Workflow werden stattdessen die vier Umgebungsvariablen `ANDROID_KEYSTORE_FILE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` und `ANDROID_KEY_PASSWORD` verwendet. `-PrequireReleaseSigning=true` verhindert einen versehentlich unsignierten Upload-Build. Versionen lassen sich über `-PreleaseVersionCode=...` und `-PreleaseVersionName=...` setzen.

## Prüfstand und Grenzen

Die API-Transaktion wird mit simulierten Antworten getestet; dabei werden keine Google-Veröffentlichungen ausgelöst. Lokal werden sowohl ein Release ohne Upload-Key als auch ein signierter Build mit einem ausschließlich dafür erzeugten Wegwerfschlüssel geprüft. Dieser Testschlüssel wird nicht als echter Upload-Key konfiguriert.

Die App zielt auf Android 16 / API 36; Android 8 bleibt als Mindestversion erhalten. Damit berücksichtigt sie die aktuelle [Google-Play-Zielversionsvorgabe](https://support.google.com/googleplay/android-developer/answer/11926878). Verhalten auf Zielgeräten und Googles weitere Prüfungen, beispielsweise native Bibliotheken und App-Inhalte, werden dadurch nicht ersetzt.

Die App nutzt weiterhin einen vom Nutzer eingegebenen Anthropic-Schlüssel und überträgt Fotos an Anthropic. Die Pipeline erledigt weder Datenschutz-/Data-Safety-Erklärungen noch die noch offene Prüfung der produktiven Transkriptions-API. Diese Angaben müssen vor der jeweiligen Verteilung zutreffend in Play Console hinterlegt werden.

Lokal am 05.11.2026 bestanden: 36 JVM-Tests, 10 Gerätetests auf einem separaten Emulatorprofil, 9 Python-Tests des Upload-Ablaufs, Android Lint ohne Fehler und Actionlint ohne Befund. Ein Build mit einem Wegwerfschlüssel wurde erfolgreich signiert und mit `jarsigner` verifiziert. Fehlender Upload-Schlüssel und ungültiger Versionscode wurden als Fehler abgewiesen. Der Wegwerfschlüssel wurde anschließend gelöscht. Der GitHub-Prüfworkflow wurde anschließend erfolgreich ausgeführt. Die Google-Cloud-Anbindung ist eingerichtet; der erste Play-Console-Upload steht noch aus.

## Erstes Bundle

Da bisher kein APK/AAB in Play Console hochgeladen wurde, wird `com.octopotato.vorleser` als gewünschter Paketname für die erste Veröffentlichung verwendet. Das Kotlin-Package und der Android-Namespace bleiben `de.vorleser`; sie müssen nicht mit der öffentlichen Application-ID identisch sein. Bereits installierte Testversionen mit `de.vorleser.app` sind eine andere App und werden dadurch nicht aktualisiert.

Der erste lokale Release verwendet `versionCode=1` und `versionName=1.0.0`. Nachfolgende GitHub-Läufe starten mit ihrer konfigurierten Basis oberhalb dieser Version. Der dafür erstellte langlebige Upload-Keystore liegt außerhalb des Repositorys mit auf den Benutzer beschränkten Dateirechten; seine Werte werden ausschließlich als Secrets der GitHub-Umgebung `play-internal` verwendet. Eine zusätzliche sichere Sicherung von Keystore und Passwortdatei muss der Eigentümer aufbewahren.
