# Vorleser

Android-Tablet-App: Brief fotografieren, Claude liest den Text, Android-TTS liest ihn auf Deutsch vor.

- Kotlin, Jetpack Compose, CameraX, Claude API (`claude-sonnet-5-5`), Android TextToSpeech (de-DE)
- **API-Schlüssel:** nicht im Code. Titel „Vorleser" lange drücken, Schlüssel einmal eingeben (nur Familien-Variante).
  Für den öffentlichen Store: kleinen Proxy-Server davorschalten, App ohne Schlüssel ausliefern.
- Debug-APK: `gradle assembleDebug` (Android SDK nötig)
- Release: `keystore.properties` (storeFile, storePassword, keyAlias, keyPassword) anlegen, dann `gradle bundleRelease`
