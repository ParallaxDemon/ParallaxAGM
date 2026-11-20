package ai.antigem.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ai.antigem.data.agent.parallax.ParallaxPreferences
import ai.antigem.ui.components.ParallaxAccent
import ai.antigem.ui.components.bouncyVerticalScroll

/** ParallaxAI agent connection settings: panel URL, API key, job timeout and conversation context length. */
@Composable
fun ParallaxSettingsScreen(cardBg: androidx.compose.ui.graphics.Color, cardBorder: androidx.compose.foundation.BorderStroke) {
    val context = LocalContext.current
    val prefs = remember { ParallaxPreferences(context) }
    val base by prefs.baseUrl.collectAsState()
    val key by prefs.apiKey.collectAsState()
    val timeout by prefs.timeoutSeconds.collectAsState()
    val history by prefs.historyLimit.collectAsState()

    var baseInput by remember(base) { mutableStateOf(base) }
    var keyInput by remember(key) { mutableStateOf(key) }
    var timeoutInput by remember(timeout) { mutableStateOf(timeout.toString()) }
    var historyInput by remember(history) { mutableStateOf(history.toString()) }
    var showKey by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    val fieldModifier = Modifier.fillMaxWidth()

    Column(
        Modifier.fillMaxSize().bouncyVerticalScroll(rememberScrollStateCompat()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("ParallaxAI", style = MaterialTheme.typography.titleLarge, color = ParallaxAccent)
        Text(
            "Continuous chats run through the ParallaxAI panel API on GitHub Actions. " +
                "Recent turns are sent as context with every message so the conversation continues.",
            style = MaterialTheme.typography.bodyMedium
        )

        Column(
            Modifier.fillMaxWidth().background(cardBg, RoundedCornerShape(12.dp)).border(cardBorder, RoundedCornerShape(12.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = baseInput,
                onValueChange = { baseInput = it; saved = false },
                label = { Text("Panel URL") },
                singleLine = true,
                modifier = fieldModifier,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it; saved = false },
                label = { Text("API key") },
                singleLine = true,
                modifier = fieldModifier,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                trailingIcon = {
                    Text(
                        if (showKey) "Hide" else "Show",
                        Modifier.bouncyClickableCompat { showKey = !showKey },
                        color = ParallaxAccent
                    )
                }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = timeoutInput,
                    onValueChange = { timeoutInput = it; saved = false },
                    label = { Text("Timeout (s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = historyInput,
                    onValueChange = { historyInput = it; saved = false },
                    label = { Text("Context turns") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.weight(1f)
                )
            }
            Button(
                onClick = {
                    prefs.setConnection(baseInput, keyInput)
                    prefs.setTimeout(timeoutInput.toIntOrNull() ?: 900)
                    prefs.setHistoryLimit(historyInput.toIntOrNull() ?: 12)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (saved) "Saved" else "Save settings")
            }
            Text(
                "Context turns = how many previous messages are sent with each prompt (0–40). " +
                    "Timeout is how long the app waits for a job (60–3600 s).",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/* Small helpers keep this screen self-contained without pulling in extra composable utilities. */
@Composable
private fun rememberScrollStateCompat() = androidx.compose.foundation.rememberScrollState()

private fun Modifier.bouncyClickableCompat(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)
