# Kameratest mit Laptopkamera und RealSense

Stand: 16.10.2026.

## Anpassungen in der App

[CameraScreen](../../app/src/main/java/de/vorleser/CameraScreen.kt) verwendet alle von `ProcessCameraProvider.availableCameraInfos` gemeldeten Kameras. Rückkameras werden zuerst angeboten, danach Vorderkameras und weitere Kameras. Die Auswahl bindet den eindeutigen `CameraInfo.cameraSelector`; eine reine Einschränkung auf Vorder-/Rückseite entfällt. Das entspricht der [CameraX-Schnittstelle zur Kameraauswahl](https://developer.android.com/reference/androidx/camera/core/CameraProvider).

Die Oberfläche zeigt „Kamera 1 von 2“ und die von Android gemeldete Ausrichtung. „Kamera wechseln“ geht durch alle verfügbaren Kameras. Die Auswahl bleibt bei einer Activity-Neuerstellung erhalten.

„Foto aufnehmen“ wird erst aktiviert, wenn `PreviewView` einen laufenden Vorschaustream meldet. Eine gebundene Kamera kann bereits vorher gewechselt werden. Wenn nach 15 Sekunden noch kein Stream vorliegt, zeigt die App einen Hinweis zur Verbindung und zum Kamerawechsel. Dadurch bleibt ein erkannter, aber noch nicht liefernder Videoeingang unterscheidbar von einer funktionierenden Vorschau.

## Mac-Kameras an den Emulator durchreichen

Host-Webcams werden über die Emulator-Konfiguration zu einer Android-Vorder- oder Rückkamera. Dafür wird in der App kein RealSense-SDK eingebunden. Die offiziellen [Emulator-Startoptionen](https://developer.android.com/studio/run/emulator-commandline) beschreiben `-webcam-list`, `-camera-front` und `-camera-back`.

Zuerst die aktuelle Zuordnung ermitteln:

```sh
"$ANDROID_HOME/emulator/emulator" -webcam-list
```

Bei der lokalen Prüfung wurden die RealSense als `webcam0` und die MacBook-Kamera als `webcam2` gemeldet. Diese Nummern sind lokal und können sich nach dem Anschließen weiterer Geräte ändern. Mit diesen aktuell ermittelten Werten lautet ein Start beispielsweise:

```sh
"$ANDROID_HOME/emulator/emulator" \
  -avd Medium_Phone_API_37.0 \
  -no-snapshot-load \
  -camera-back webcam0 \
  -camera-front webcam2
```

Ein bereits laufender Emulator muss vor dem Start beendet werden. `-no-snapshot-load` verhindert, dass ein alter Snapshot mit einer früheren Kamera-Konfiguration geladen wird. Die vorhandenen App-Daten werden dabei nicht zurückgesetzt.

Lokal verwies die gespeicherte Konfiguration zunächst für beide Seiten auf `webcam0`. Sie wurde auf unterschiedliche Quellen umgestellt. Diese Zuordnung liegt im lokalen AVD-Profil, nicht in der APK. Die App zeigt die Android-Ausrichtung an; die Host-Namen „MacBook“ und „RealSense“ sind daraus nicht automatisch ableitbar.

## RealSense: Erkennung und Bildübertragung unterscheiden

macOS und der Emulator listen das Gerät als `Intel(R) RealSense(TM) Depth Camera 435i Depth`. Damit ist die Erkennung belegt, nicht die Verfügbarkeit eines nutzbaren RGB-Bildes. Die App fotografiert Dokumente und verarbeitet JPEG-Bilder; Tiefenmessungen werden nicht verwendet.

Der separate lokale RealSense-SDK-Test scheiterte mit `RS2_USB_STATUS_ACCESS`. Direkte FFmpeg-Versuche über AVFoundation lieferten sowohl bei der Laptopkamera als auch bei der RealSense innerhalb von 20 Sekunden kein Bild. Eine funktionierende physische Bildübertragung wurde deshalb bisher nicht bestätigt. Ob macOS-Kamerafreigaben oder andere belegte Kameraressourcen die Ursache sind, ist noch offen. Für einen manuellen Test die Kamera-Freigabe des verwendeten Programms in den macOS-Systemeinstellungen prüfen und andere Kamera-Anwendungen schließen.

## RGB gezielt prüfen

Die installierte RealSense-SDK-Version ist 2.58.1. Deren [macOS-Hinweis](https://raw.githubusercontent.com/realsenseai/librealsense/v2.58.1/doc/installation_osx.md) nennt erhöhte Rechte für den USB-Zugriff. Die lokalen SDK-Aufrufe melden `RS2_USB_STATUS_ACCESS` beziehungsweise `failed to set power state`.

Ein lokaler Diagnosetest wurde mit `RS2_STREAM_COLOR`, `RS2_FORMAT_RGB8`, 640 × 480 Pixeln und 30 Bildern pro Sekunde vorbereitet und kompiliert. Er fordert fünf Farbbilder an und speichert keine Bilder. Die dafür angezeigte macOS-Administratorabfrage wurde abgebrochen; ein erfolgreich gestarteter RGB-Stream ist deshalb weiterhin nicht nachgewiesen.

Die explizite SDK-Auswahl ist eine Anforderung für diesen einzelnen Test. Sie stellt die Webcam-Zuordnung des Android-Emulators nicht dauerhaft auf einen anderen Sensor um. Ein vorhandener RGB-Farbsensor muss zuerst erfolgreich geöffnet werden, bevor die Weitergabe seines Bildes an den Emulator bestätigt werden kann.

## Prüfung der Änderungen

- Debug-APK und Android-Test-APK erfolgreich gebaut.
- Alle 36 JVM-Tests bestanden; Android Lint ohne Fehler.
- Alle 10 Android-Gerätetests in einem separaten, frisch angelegten Emulatorprofil mit zwei emulierten Kameras bestanden. Der neue Test wechselt die Kamera, erstellt die Activity neu und prüft danach eine erfolgreiche Aufnahme.
- Die Gerätetests verwenden das separate Testprofil und verändern die Briefdaten des bestehenden Emulatorprofils nicht.
- Die automatische Prüfung ersetzt den noch offenen Livebild-Test mit den beiden physischen Mac-Kameras nicht.
