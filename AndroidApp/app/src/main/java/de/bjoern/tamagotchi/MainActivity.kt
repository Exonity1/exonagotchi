package de.bjoern.tamagotchi

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bjoern.tamagotchi.TamagotchiBle.Status
import java.util.Locale
import kotlin.math.roundToInt

/** Haelt die BLE-Verbindung ueber Bildschirmdrehungen hinweg. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    val ble = TamagotchiBle(app)
    override fun onCleared() = ble.close()
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TamagotchiTheme { TamagotchiScreen(vm.ble) }
        }
    }
}

private val BLE_PERMISSIONS = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
)

@Composable
fun TamagotchiScreen(ble: TamagotchiBle) {
    val context = LocalContext.current
    val state by ble.state.collectAsStateWithLifecycle()

    var hasPermissions by remember {
        mutableStateOf(BLE_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> hasPermissions = result.values.all { it } }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (ble.isBluetoothOn) ble.scan() }

    var text by rememberSaveable { mutableStateOf("") }
    val connected = state.status == Status.CONNECTED

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "TAMAGOTCHI",
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )

            LcdPreview(
                line1 = state.lcdText,
                line2 = when {
                    !connected -> "BLE --"
                    state.batteryPercent == null -> "BLE ok"
                    else -> String.format(Locale.ROOT, "BLE ok Akku%4d%%", state.batteryPercent)
                },
                brightness = state.brightness,
            )

            Text(
                buildString {
                    append(state.message)
                    state.batteryPercent?.let { append(" · Akku $it %") }
                    state.batteryMv?.let { append(" ($it mV)") }
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!hasPermissions) {
                Button(
                    onClick = { permissionLauncher.launch(BLE_PERMISSIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Bluetooth-Berechtigung erteilen") }
            } else {
                when (state.status) {
                    Status.IDLE -> Button(
                        onClick = {
                            if (ble.isBluetoothOn) ble.scan()
                            else enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Verbinden") }

                    Status.SCANNING, Status.CONNECTING -> OutlinedButton(
                        onClick = { ble.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Abbrechen")
                    }

                    Status.CONNECTED -> OutlinedButton(
                        onClick = { ble.disconnect() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Trennen") }
                }

                LightCard(
                    brightness = state.brightness,
                    enabled = connected,
                    onChange = { ble.setBrightness(it) },
                )

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Text auf Zeile 1", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = text,
                            onValueChange = { text = it.take(TamagotchiBle.MAX_TEXT) },
                            singleLine = true,
                            enabled = connected,
                            supportingText = {
                                Text("${text.length}/${TamagotchiBle.MAX_TEXT} · Umlaute werden ersetzt")
                            },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { ble.sendText(text) }),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { ble.sendText(text) },
                            enabled = connected && text.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Senden") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LightCard(brightness: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Licht: ${brightness * 100 / 255} %",
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = brightness.toFloat(),
                onValueChange = { onChange(it.roundToInt()) },
                valueRange = 0f..255f,
                enabled = enabled,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Aus" to 0, "15 %" to 40, "50 %" to 128, "100 %" to 255).forEach { (label, v) ->
                    FilledTonalButton(
                        onClick = { onChange(v) },
                        enabled = enabled,
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
        }
    }
}

/** Nachbau des 16x2-Displays: blauer Hintergrund, Helligkeit folgt dem Backlight. */
@Composable
private fun LcdPreview(line1: String, line2: String, brightness: Int) {
    val level = brightness / 255f
    val background = lerp(Color(0xFF0A1236), Color(0xFF2347E6), level)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E0E10))
            .padding(10.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(background)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            listOf(line1, line2).forEach { line ->
                Text(
                    line.padEnd(16).take(16),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    maxLines = 1,
                    softWrap = false,
                    color = Color(0xFFE6EEFF).copy(alpha = 0.55f + 0.45f * level),
                )
            }
        }
    }
}

@Composable
fun TamagotchiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF7FD8FF),
            onPrimary = Color(0xFF00222E),
            secondaryContainer = Color(0xFF1D3452),
            onSecondaryContainer = Color(0xFFD3E8FF),
            background = Color(0xFF0A0E18),
            surface = Color(0xFF0A0E18),
            surfaceContainerHighest = Color(0xFF151D2E),
        ),
        content = content,
    )
}
