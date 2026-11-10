package com.example.homedash.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class CalendarSource(val id: String, val name: String, val url: String)

data class Settings(
    val calendars: List<CalendarSource> = emptyList(),
    val zipCode: String = "",
    val norishUrl: String = "",
    val norishApiKey: String = "",
    val dexcomUsername: String = "",
    val dexcomPassword: String = "",
)

private val Context.dataStore by preferencesDataStore(name = "settings")
private val CALENDARS = stringPreferencesKey("calendars")
private val ZIP_CODE = stringPreferencesKey("zip_code")
private val NORISH_URL = stringPreferencesKey("norish_url")
private val NORISH_API_KEY = stringPreferencesKey("norish_api_key")
private val DEXCOM_USERNAME = stringPreferencesKey("dexcom_username")
private val DEXCOM_PASSWORD = stringPreferencesKey("dexcom_password")

// The Dexcom password is secret too, and like the others it's stored unencrypted in the app's private storage.
/** Saved settings, stored on the device only. The calendar URLs and Norish key are secret, so they never leave it. */
class SettingsStore(private val context: Context) {
    val settings: Flow<Settings> = context.dataStore.data.map { prefs ->
        Settings(
            calendars = decode(prefs[CALENDARS].orEmpty()),
            zipCode = prefs[ZIP_CODE].orEmpty(),
            norishUrl = prefs[NORISH_URL].orEmpty(),
            norishApiKey = prefs[NORISH_API_KEY].orEmpty(),
            dexcomUsername = prefs[DEXCOM_USERNAME].orEmpty(),
            dexcomPassword = prefs[DEXCOM_PASSWORD].orEmpty(),
        )
    }

    suspend fun save(settings: Settings) {
        context.dataStore.edit { prefs ->
            prefs[CALENDARS] = encode(settings.calendars)
            prefs[ZIP_CODE] = settings.zipCode
            prefs[NORISH_URL] = settings.norishUrl
            prefs[NORISH_API_KEY] = settings.norishApiKey
            prefs[DEXCOM_USERNAME] = settings.dexcomUsername
            prefs[DEXCOM_PASSWORD] = settings.dexcomPassword
        }
    }

    private fun encode(sources: List<CalendarSource>): String =
        JSONArray(sources.map { s ->
            JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
        }).toString()

    private fun decode(json: String): List<CalendarSource> {
        if (json.isBlank()) return emptyList()
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CalendarSource(o.getString("id"), o.getString("name"), o.getString("url"))
        }
    }
}
