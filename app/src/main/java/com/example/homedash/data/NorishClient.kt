package com.example.homedash.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** One planned dinner. [recipeId] links to the recipe page at {baseUrl}/recipes/{recipeId}. */
data class Dinner(val name: String, val recipeId: String?)

/** [baseUrl] is the instance root, e.g. "http://192.168.1.50:3000". [apiKey] is optional; blank sends no header. */
class NorishClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    /** Planned dinners for the current week (Mon–Sun, server timezone), keyed by date. */
    suspend fun weekDinners(): Map<LocalDate, List<Dinner>> = withContext(Dispatchers.IO) {
        val conn = (URL("${baseUrl.trimEnd('/')}/api/v1/planned-recipes/week").openConnection() as HttpURLConnection)
        try {
            if (apiKey.isNotBlank()) conn.setRequestProperty("x-api-key", apiKey)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            if (conn.responseCode !in 200..299) {
                // The server's error body says whether the failure is in Norish itself or a proxy in front of it.
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.e("Norish", "HTTP ${conn.responseCode} from ${conn.url}: $error")
                val snippet = error.replace(Regex("\\s+"), " ").trim().take(200)
                throw IOException("HTTP ${conn.responseCode} ${conn.responseMessage.orEmpty()}".trim() +
                    if (snippet.isNotEmpty()) " — $snippet" else "")
            }
            val body = conn.inputStream.bufferedReader().readText()
            Log.d("Norish", body) // inspect this to tighten the parser below
            parse(body)
        } finally {
            conn.disconnect()
        }
    }

    /** One-line result for the Test connection button in settings. The raw response is logged under "Norish". */
    suspend fun testConnection(): String = try {
        val count = weekDinners().values.sumOf { it.size }
        if (count == 0) "Connected, but no dinners found this week. Raw response is in Logcat (tag \"Norish\")."
        else "Connected. Found $count dinner${if (count == 1) "" else "s"} this week."
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        "Couldn't connect: ${e.message ?: e::class.simpleName}"
    }

    // Shape from Norish's OpenAPI spec (apps/docs/docs/reference/openapi.json), confirmed against a real
    // v0.26 response: [{"date":"2026-11-01","slot":"Dinner","sortOrder":0,"recipeId":"…","recipeName":"…",…}].
    // Only the Dinner slot is kept; breakfast, lunch and snacks are skipped.
    private fun parse(body: String): Map<LocalDate, List<Dinner>> {
        val items = JSONArray(body)
        val out = mutableMapOf<LocalDate, MutableList<Pair<Int, Dinner>>>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            if (o.optString("slot") != "Dinner") continue
            val date = runCatching { LocalDate.parse(o.optString("date").take(10)) }.getOrNull() ?: continue
            // recipeName is nullable in the spec; optString would turn null into "null".
            val name = if (o.isNull("recipeName")) "" else o.optString("recipeName")
            if (name.isBlank()) continue
            val recipeId = if (o.isNull("recipeId")) null else o.optString("recipeId").takeIf { it.isNotBlank() }
            out.getOrPut(date) { mutableListOf() }.add(o.optInt("sortOrder") to Dinner(name, recipeId))
        }
        return out.mapValues { (_, list) -> list.sortedBy { it.first }.map { it.second } }
    }
}
