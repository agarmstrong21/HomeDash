package com.example.homedash.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

// US Dexcom Share server and application ID. Accounts outside the US (shareous1.dexcom.com) or in Japan
// (share.dexcom.jp, with its own application ID) would need these changed; see pydexcom's const.py.
private const val BASE_URL = "https://share2.dexcom.com/ShareWebServices/Services/"
private const val APPLICATION_ID = "d89443d2-327c-4a6f-89e5-496bbb0317db"

/** One CGM reading. [trend] is Dexcom's direction name, e.g. "Flat" or "FortyFiveUp". */
data class GlucoseReading(val mgdl: Int, val trend: String, val time: Instant) {
    val arrow: String get() = TREND_ARROWS[trend].orEmpty()
    fun minutesAgo(now: Instant = Instant.now()): Long = Duration.between(time, now).toMinutes().coerceAtLeast(0)
}

private val TREND_ARROWS = mapOf(
    "DoubleUp" to "↑↑", "SingleUp" to "↑", "FortyFiveUp" to "↗", "Flat" to "→",
    "FortyFiveDown" to "↘", "SingleDown" to "↓", "DoubleDown" to "↓↓",
    "NotComputable" to "?", "RateOutOfRange" to "-",
)

/**
 * Dexcom rejected the username or password, or locked the account after too many tries. Callers should stop
 * retrying until the settings change: each retry is another failed login and can extend the lockout.
 */
class DexcomAuthException(message: String) : IOException(message)

private class DexcomSessionExpired : IOException("Dexcom session expired")

/**
 * Latest reading from the Dexcom Share service (the same unofficial API pydexcom uses; endpoints, application
 * IDs, and error codes are from pydexcom's const.py and dexcom.py: https://github.com/gagebenne/pydexcom).
 *
 * Uses the credentials of the account that shares readings (not a follower's). Share must be on with at least
 * one follower. The session is kept between calls and only renewed when Dexcom says it expired.
 */
class DexcomClient(
    private val username: String,
    private val password: String,
) {
    private val lock = Mutex()
    private var sessionId: String? = null

    /** Most recent reading in the last 24 hours, or null if there is none. */
    suspend fun latest(): GlucoseReading? = withContext(Dispatchers.IO) {
        lock.withLock {
            val session = sessionId ?: login().also { sessionId = it }
            try {
                read(session)
            } catch (e: DexcomSessionExpired) {
                // Same as pydexcom: one fresh login, then one retry.
                read(login().also { sessionId = it })
            }
        }
    }

    /** One-line result for the Test connection button in settings. */
    suspend fun testConnection(): String = try {
        val reading = latest()
        if (reading == null) "Connected, but no readings in the last 24 hours."
        else "Connected. Latest: ${reading.mgdl} mg/dL ${reading.arrow}, ${reading.minutesAgo()} min ago."
    } catch (e: CancellationException) {
        throw e
    } catch (e: DexcomAuthException) {
        "Login failed: ${e.message}"
    } catch (e: Exception) {
        "Couldn't connect: ${e.message ?: e::class.simpleName}"
    }

    private fun login(): String {
        val accountId = postForId(
            "General/AuthenticatePublisherAccount",
            JSONObject().put("accountName", username).put("password", password).put("applicationId", APPLICATION_ID),
        )
        return postForId(
            "General/LoginPublisherAccountById",
            JSONObject().put("accountId", accountId).put("password", password).put("applicationId", APPLICATION_ID),
        )
    }

    private fun read(session: String): GlucoseReading? {
        val body = post("Publisher/ReadPublisherLatestGlucoseValues?sessionId=$session&minutes=1440&maxCount=1", JSONObject())
        val readings = JSONArray(body)
        if (readings.length() == 0) return null
        val r = readings.getJSONObject(0)
        // "DT" looks like "Date(1730500000000-0500)": epoch millis, then the phone's UTC offset.
        val millis = Regex("""Date\((\d+)""").find(r.getString("DT"))?.groupValues?.get(1)?.toLong()
            ?: throw IOException("Unexpected Dexcom date \"${r.optString("DT")}\"")
        return GlucoseReading(r.getInt("Value"), r.optString("Trend"), Instant.ofEpochMilli(millis))
    }

    /** The login endpoints return a bare JSON string; all zeros means Dexcom didn't accept it. */
    private fun postForId(endpoint: String, json: JSONObject): String {
        val id = JSONTokener(post(endpoint, json)).nextValue() as? String
        if (id.isNullOrBlank() || id == "00000000-0000-0000-0000-000000000000") {
            throw DexcomAuthException("Dexcom didn't accept the username or password")
        }
        return id
    }

    private fun post(endpoint: String, json: JSONObject): String {
        val conn = URL(BASE_URL + endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            if (conn.responseCode in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
            val error = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw errorFor(conn.responseCode, error)
        } finally {
            conn.disconnect()
        }
    }

    // Error codes as handled in pydexcom's _handle_error_code.
    private fun errorFor(status: Int, body: String): IOException {
        val o = runCatching { JSONObject(body) }.getOrNull()
        val code = o?.optString("Code").orEmpty()
        val message = o?.optString("Message").orEmpty()
        return when {
            code == "SessionIdNotFound" || code == "SessionNotValid" -> DexcomSessionExpired()
            code == "AccountPasswordInvalid" -> DexcomAuthException("wrong username or password")
            code == "SSO_AuthenticateMaxAttemptsExceeded" ->
                DexcomAuthException("too many attempts; Dexcom has locked logins for a while")
            code == "SSO_InternalError" && "Cannot Authenticate" in message -> DexcomAuthException("wrong username or password")
            code == "InvalidArgument" && "accountName" in message -> DexcomAuthException("username isn't valid")
            code == "InvalidArgument" && "password" in message -> DexcomAuthException("password isn't valid")
            code.isNotBlank() -> IOException("HTTP $status $code: $message")
            else -> IOException("HTTP $status")
        }
    }
}
