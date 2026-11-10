package com.example.homedash.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.homedash.data.CalendarSource
import com.example.homedash.data.DexcomClient
import com.example.homedash.data.NorishClient
import com.example.homedash.data.Settings
import kotlinx.coroutines.launch
import java.util.UUID

// webcal:// (and webcals://) only tells a device to open the link in its calendar app; the feed itself is
// served over https at the same address (iCloud shared calendars, for example). Plain http:// stays
// rejected so secret calendar links aren't sent unencrypted.
private val WEBCAL = Regex("^webcals?://", RegexOption.IGNORE_CASE)

private fun isCalendarUrl(url: String): Boolean = url.startsWith("https://", ignoreCase = true) || WEBCAL.containsMatchIn(url)

private fun webcalToHttps(url: String): String = url.replace(WEBCAL, "https://")

@Composable
fun SettingsDialog(
    initial: Settings,
    onSave: (Settings) -> Unit,
    onDismiss: () -> Unit,
    onRefreshAll: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var dexcomUsername by remember { mutableStateOf(initial.dexcomUsername) }
    var dexcomPassword by remember { mutableStateOf(initial.dexcomPassword) }
    var dexcomTestResult by remember { mutableStateOf<String?>(null) }
    var dexcomTesting by remember { mutableStateOf(false) }
    var refreshStarted by remember { mutableStateOf(false) }
    val drafts = remember { mutableStateListOf<CalendarSource>().apply { addAll(initial.calendars) } }
    var zip by remember { mutableStateOf(initial.zipCode) }
    var norishUrl by remember { mutableStateOf(initial.norishUrl) }
    var norishApiKey by remember { mutableStateOf(initial.norishApiKey) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val calendarsValid = drafts.all { it.url.isBlank() || isCalendarUrl(it.url) }
    val zipValid = zip.isBlank() || zip.length == 5
    val norishUrlValid = norishUrl.isBlank() || norishUrl.startsWith("http://") || norishUrl.startsWith("https://")

    AlertDialog(
        onDismissRequest = onDismiss,
        // Only Save or Cancel close it, so a stray tap can't throw away unsaved edits.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Weather", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = zip,
                    onValueChange = { zip = it.filter(Char::isDigit).take(5) },
                    label = { Text("Fallback ZIP code") },
                    supportingText = { Text("Used when Wi-Fi location isn't available") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = !zipValid,
                    modifier = Modifier.width(260.dp),
                )

                Spacer(Modifier.height(4.dp))
                Text("Meals (Norish)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = norishUrl,
                    onValueChange = { norishUrl = it.trim(); testResult = null },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://192.168.1.50:3000") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = !norishUrlValid,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = norishApiKey,
                    onValueChange = { norishApiKey = it.trim(); testResult = null },
                    label = { Text("API key (optional)") },
                    supportingText = { Text("Norish account settings → API keys") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = norishUrl.isNotBlank() && norishUrlValid && !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = NorishClient(norishUrl, norishApiKey).testConnection()
                                testing = false
                            }
                        },
                    ) { Text(if (testing) "Testing…" else "Test connection") }
                    testResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Blood sugar (Dexcom)", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Use the account that shares readings, not a follower's. Dexcom Share must be on with at least one follower.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = dexcomUsername,
                    onValueChange = { dexcomUsername = it.trim(); dexcomTestResult = null },
                    label = { Text("Username, email, or phone") },
                    supportingText = { Text("Phone numbers as +1 then the number, e.g. +15551234567") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = dexcomPassword,
                    onValueChange = { dexcomPassword = it; dexcomTestResult = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = dexcomUsername.isNotBlank() && dexcomPassword.isNotBlank() && !dexcomTesting,
                        onClick = {
                            dexcomTesting = true
                            dexcomTestResult = null
                            scope.launch {
                                dexcomTestResult = DexcomClient(dexcomUsername, dexcomPassword).testConnection()
                                dexcomTesting = false
                            }
                        },
                    ) { Text(if (dexcomTesting) "Testing…" else "Test connection") }
                    dexcomTestResult?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text("Calendars", style = MaterialTheme.typography.titleSmall)
                drafts.forEachIndexed { index, source ->
                    key(source.id) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = source.name,
                                onValueChange = { drafts[index] = source.copy(name = it) },
                                label = { Text("Name") },
                                singleLine = true,
                                modifier = Modifier.width(160.dp),
                            )
                            OutlinedTextField(
                                value = source.url,
                                onValueChange = { drafts[index] = source.copy(url = it.trim()) },
                                label = { Text("Secret iCal address") },
                                singleLine = true,
                                isError = source.url.isNotBlank() && !isCalendarUrl(source.url),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { drafts.removeAt(index) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove calendar")
                            }
                        }
                    }
                }
                TextButton(onClick = { drafts.add(CalendarSource(UUID.randomUUID().toString(), "", "")) }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Add calendar")
                }

                HorizontalDivider()
                // Refreshes with the saved settings; unsaved edits above take effect after Save.
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onRefreshAll(); refreshStarted = true }) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Refresh all now")
                    }
                    Text(
                        if (refreshStarted) "Refreshing calendars, weather, dinners, and blood sugar…"
                        else "Uses saved settings",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = calendarsValid && zipValid && norishUrlValid,
                onClick = {
                    onSave(
                        Settings(
                            calendars = drafts.filter { it.url.isNotBlank() }
                                .map { it.copy(name = it.name.ifBlank { "Calendar" }, url = webcalToHttps(it.url)) },
                            zipCode = zip,
                            norishUrl = norishUrl.trimEnd('/'),
                            norishApiKey = norishApiKey,
                            dexcomUsername = dexcomUsername,
                            dexcomPassword = dexcomPassword,
                        )
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
