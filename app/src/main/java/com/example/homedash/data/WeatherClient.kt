package com.example.homedash.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class CurrentWeather(
    val tempF: Int,
    val summary: String,
    val high: Int,
    val low: Int,
    val location: String = "",
    // Where the weather is for; the card links to the forecast page for this spot.
    val latitude: Double? = null,
    val longitude: Double? = null,
)
data class Place(val latitude: Double, val longitude: Double, val name: String)

class WeatherClient {
    /** Current conditions plus today's high and low, in °F. Open-Meteo needs no API key. */
    suspend fun current(latitude: Double, longitude: Double): CurrentWeather = withContext(Dispatchers.IO) {
        val body = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
        )
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        CurrentWeather(
            tempF = current.getDouble("temperature_2m").roundToInt(),
            summary = describe(current.getInt("weather_code")),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
        )
    }

    /** Looks up a US ZIP code. Returns null if nothing matches. */
    suspend fun lookupZip(zip: String): Place? = withContext(Dispatchers.IO) {
        val body = get("https://geocoding-api.open-meteo.com/v1/search?name=$zip&count=1&countryCode=US&format=json")
        val first = JSONObject(body).optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        Place(first.getDouble("latitude"), first.getDouble("longitude"), first.getString("name"))
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    // WMO weather interpretation codes, as used by Open-Meteo.
    private fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55 -> "Drizzle"
        56, 57, 66, 67 -> "Freezing rain"
        61, 63, 65, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorm"
        else -> "Unknown"
    }
}
