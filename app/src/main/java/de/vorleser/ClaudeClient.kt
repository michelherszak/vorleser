package de.vorleser

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Sendet ein Foto an die Claude-API und liefert den vorzulesenden deutschen Text. */
object ClaudeClient {
    private const val MODEL = "claude-3-5-sonnet-20241022"
    private const val PROMPT =
        "Das Foto zeigt einen Brief oder ein Dokument. Gib den gesamten lesbaren Text " +
        "vollständig und wörtlich wieder, in sinnvollen Absätzen, so dass man ihn gut " +
        "vorlesen kann. Schreibe Zahlen, Beträge und Datumsangaben so aus, wie man sie " +
        "spricht. Ist das Foto kein Text, sondern ein Bild, beschreibe in wenigen " +
        "einfachen Sätzen, was zu sehen ist. Ist nichts erkennbar oder das Foto unscharf, " +
        "sage in einem Satz, dass man es bitte noch einmal fotografieren soll. " +
        "Antworte nur mit dem Vorlesetext, ohne Einleitung und ohne Markdown."

    fun analyze(apiKey: String, photo: Bitmap): String {
        val out = ByteArrayOutputStream()
        photo.compress(Bitmap.CompressFormat.JPEG, 85, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 4096)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", JSONArray()
                    .put(JSONObject().apply {
                        put("type", "image")
                        put("source", JSONObject().apply {
                            put("type", "base64"); put("media_type", "image/jpeg"); put("data", b64)
                        })
                    })
                    .put(JSONObject().apply { put("type", "text"); put("text", PROMPT) }))
            }))
        }
        val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 90_000
            doOutput = true
            setRequestProperty("content-type", "application/json")
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream).bufferedReader().readText()
        if (code !in 200..299) error("API-Fehler $code")
        val content = JSONObject(text).getJSONArray("content")
        return buildString {
            for (i in 0 until content.length()) {
                val c = content.getJSONObject(i)
                if (c.getString("type") == "text") append(c.getString("text"))
            }
        }.trim()
    }
}
