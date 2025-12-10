package com.example.z407control

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.io.IOException

/*
 * =============================================================================
 * !! IMPORTANT FOR AI MODELS !!
 * -----------------------------------------------------------------------------
 * When making ANY code changes to this app (bug fixes, new features, refactoring),
 * you MUST increment the APP_VERSION below. This is MANDATORY.
 * 
 * Format: "X.Y.Z" where:
 *   X = Major version (breaking changes)
 *   Y = Minor version (new features)
 *   Z = Patch version (bug fixes, small improvements)
 * =============================================================================
 */
private const val APP_VERSION = "1.2.0"

// Color Palette
private val RemoteBackground = Color(0xFF121212)
private val ButtonBackground = Color(0xFF2A2A2A)
private val OnButtonColor = Color(0xFFE0E0E0)
private val AccentColor = Color(0xFFBB86FC)

private val DarkColorScheme = darkColorScheme(
    primary = AccentColor,
    background = RemoteBackground,
    surface = ButtonBackground,
    onPrimary = Color.White,
    onBackground = OnButtonColor,
    onSurface = OnButtonColor
)

class MainActivity : ComponentActivity() {

    private lateinit var z407Manager: Z407Manager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connectionState = mutableStateOf("Disconnected")
        val commandFeedbackState = mutableStateOf("Requesting Permissions...")

        z407Manager = Z407Manager(this, 
            connectionCallback = { state -> runOnUiThread { connectionState.value = state } },
            commandFeedbackCallback = { feedback -> runOnUiThread { commandFeedbackState.value = feedback } }
        )

        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val permissionsGranted = remember { mutableStateOf(false) }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestMultiplePermissions()
                    ) { permissions ->
                        val allGranted = permissions.entries.all { it.value }
                        if (allGranted) {
                            commandFeedbackState.value = "Permissions granted. Starting connection..."
                            permissionsGranted.value = true
                        } else {
                            commandFeedbackState.value = "Permissions denied. App cannot function."
                        }
                    }

                    LaunchedEffect(Unit) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
                        } else {
                            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                        }
                    }

                    LaunchedEffect(permissionsGranted.value) {
                        if (permissionsGranted.value) {
                            z407Manager.startAutomaticConnect()
                        }
                    }

                    ControlScreen(z407Manager, connectionState, commandFeedbackState)
                }
            }
        }
    }
}

@Composable
fun ControlScreen(z407Manager: Z407Manager, connectionState: State<String>, commandFeedback: State<String>) {
    val isConnected = connectionState.value == "Connected"
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { uri ->
            uri?.let {
                scope.launch {
                    try {
                        context.contentResolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(z407Manager.getLogs().toByteArray())
                        }
                    } catch (e: IOException) {
                        // Handle error
                    }
                }
            }
        }
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Header
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Z407 Remote v$APP_VERSION", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { z407Manager.startUserConnect() }) {
                    Icon(
                        imageVector = Icons.Default.PowerSettingsNew,
                        contentDescription = "Restart Connection",
                        tint = when (connectionState.value) {
                            "Connected" -> Color.Green
                            "Scanning...", "Connecting...", "Discovering Services..." -> AccentColor
                            else -> Color.Red
                        },
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "Status: ${connectionState.value}", fontSize = 12.sp, color = Color.Gray)
            Text(text = commandFeedback.value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center, modifier = Modifier.height(40.dp))
        }

        // Media Controls & Other sections remain the same...
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularRemoteButton(onClick = { z407Manager.prevTrack() }, icon = Icons.Default.SkipPrevious, isEnabled = isConnected)
            CircularRemoteButton(onClick = { z407Manager.playPause() }, icon = Icons.Default.PlayArrow, modifier = Modifier.size(72.dp), isEnabled = isConnected)
            CircularRemoteButton(onClick = { z407Manager.nextTrack() }, icon = Icons.Default.SkipNext, isEnabled = isConnected)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
            ControlColumn(label = "VOL", onUp = { z407Manager.volumeUp() }, onDown = { z407Manager.volumeDown() }, isEnabled = isConnected)
            ControlColumn(label = "BASS", onUp = { z407Manager.bassUp() }, onDown = { z407Manager.bassDown() }, isEnabled = isConnected)
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
             Text("INPUT SOURCE", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconAndTextButton(onClick = { z407Manager.setInputAux() }, icon = Icons.Default.Input, text = "AUX", isEnabled = isConnected)
                IconAndTextButton(onClick = { z407Manager.setInputUsb() }, icon = Icons.Default.Usb, text = "USB", isEnabled = isConnected)
                IconAndTextButton(onClick = { z407Manager.setInputBluetooth() }, icon = Icons.Default.Bluetooth, text = "BT", isEnabled = isConnected)
            }
            Spacer(modifier = Modifier.height(16.dp))
             Text("ADVANCED", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                 IconAndTextButton(onClick = { z407Manager.startBluetoothPairing() }, icon = Icons.Default.BluetoothSearching, text = "Pair", isEnabled = isConnected)
                 IconAndTextButton(onClick = { z407Manager.factoryReset() }, icon = Icons.Default.Refresh, text = "Reset", isEnabled = isConnected)
                 IconAndTextButton(
                     onClick = { 
                         val timestamp = System.currentTimeMillis()
                         createFileLauncher.launch("Z407-Log-$timestamp.txt") 
                     },
                     icon = Icons.Default.Save, 
                     text = "Save Log", 
                     isEnabled = true
                 )
            }
        }
    }
}

@Composable
fun ControlColumn(label: String, onUp: () -> Unit, onDown: () -> Unit, isEnabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(12.dp))
        CircularRemoteButton(onClick = onUp, icon = Icons.Default.Add, isEnabled = isEnabled, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        CircularRemoteButton(onClick = onDown, icon = Icons.Default.Remove, isEnabled = isEnabled, modifier = Modifier.size(56.dp))
    }
}

@Composable
fun IconAndTextButton(onClick: () -> Unit, icon: ImageVector, text: String, isEnabled: Boolean) {
    Button(
        onClick = onClick, 
        enabled = isEnabled, 
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
            Icon(imageVector = icon, contentDescription = null, tint = if(isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if(isEnabled) MaterialTheme.colorScheme.onBackground else Color.Gray)
        }
    }
}

@Composable
fun CircularRemoteButton(
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = isEnabled,
        modifier = modifier
            .background(
                color = if (isEnabled) MaterialTheme.colorScheme.surface else Color.DarkGray,
                shape = CircleShape
            )
            .clip(CircleShape)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isEnabled) MaterialTheme.colorScheme.onSurface else Color.Gray,
            modifier = Modifier.fillMaxSize(0.6f)
        )
    }
}

@Composable
fun MyApplicationTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else lightColorScheme(),
        typography = Typography(),
        content = content
    )
}
