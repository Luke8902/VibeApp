package com.example.pcmonitor

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.example.pcmonitor.data.Media
import com.example.pcmonitor.data.Metrics
import com.example.pcmonitor.data.PcClient
import com.example.pcmonitor.data.ProcessMetric
import com.example.pcmonitor.data.BrowseResult
import com.example.pcmonitor.data.BrowseEntry
import com.example.pcmonitor.data.FileActionResult
import com.example.pcmonitor.data.ControlResult
import com.example.pcmonitor.data.PcConnectionPayload
import com.example.pcmonitor.data.MonitorInfo
import com.example.pcmonitor.data.ScreenFrame
import com.example.pcmonitor.lyrics.LyricLine
import com.example.pcmonitor.lyrics.LyricsProvider
import com.example.pcmonitor.lyrics.LyricsRepository
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private val Background = Color(0xFF101319)
private val Panel = Color(0xFF1A2028)
private val Mint = Color(0xFF80F0C0)
private val Blue = Color(0xFF81B8FF)
private val Muted = Color(0xFF9BA8B8)
private val visualizerStyles = listOf(
    "Ripple", "Spectrum", "Wave", "Radial", "Pulse", "Aurora", "Orbit",
    "Tunnel", "Particles", "Spiral", "Mirror", "Grid", "Plasma",
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Mint, secondary = Blue, background = Background, surface = Panel, onPrimary = Background, onBackground = Color.White, onSurface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = Background) { MonitorScreen() }
            }
        }
    }
}

@Composable
private fun MonitorScreen() {
    val context = LocalContext.current
    val preferences = remember(context) { context.getSharedPreferences("pc_connection", 0) }
    val client = remember { PcClient() }
    val telemetry by client.telemetry.collectAsState()
    val status by client.status.collectAsState()
    val browseResult by client.browseResult.collectAsState()
    val fileActionResult by client.fileActionResult.collectAsState()
    val controlResult by client.controlResult.collectAsState()
    val mediaControlResult by client.mediaControlResult.collectAsState()
    val monitors by client.monitors.collectAsState()
    val screenFrame by client.screenFrame.collectAsState()
    val screenStatus by client.screenStatus.collectAsState()
    var browserOpen by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf(preferences.getString("address", "ws://127.0.0.1:8765/ws") ?: "ws://127.0.0.1:8765/ws") }
    var token by rememberSaveable { mutableStateOf(preferences.getString("token", "") ?: "") }
    var pairingMessage by rememberSaveable { mutableStateOf("") }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { contents ->
            runCatching { PcConnectionPayload.parse(contents) }
                .onSuccess { pairing ->
                    address = pairing.address
                    token = pairing.token
                    pairingMessage = "PC QR scanned. Connecting…"
                    preferences.edit().putString("address", pairing.address).putString("token", pairing.token).apply()
                }
                .onFailure { error ->
                    pairingMessage = error.message ?: "That QR code could not be used."
                }
        }
    }
    var page by rememberSaveable { mutableStateOf("Graphs") }
    var visualizerStyle by rememberSaveable { mutableStateOf("Ripple") }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val graphHistory = remember { mutableStateMapOf<String, MutableList<Float>>() }
    val repository = remember { LyricsRepository() }
    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsState by remember { mutableStateOf("Lyrics appear when a track is detected.") }
    var lyricsProviderName by rememberSaveable { mutableStateOf(LyricsProvider.LRCLIB.name) }
    val lyricsProvider = LyricsProvider.entries.firstOrNull { it.name == lyricsProviderName } ?: LyricsProvider.LRCLIB
    var albumArtwork by remember { mutableStateOf<Bitmap?>(null) }
    var visualizerGain by rememberSaveable { mutableFloatStateOf(0.7f) }
    val trackKey = "${telemetry.media.title}|${telemetry.media.artist}|${telemetry.media.album}"

    DisposableEffect(client) { onDispose { client.disconnect() } }
    LaunchedEffect(address, token) {
        if (token.isNotBlank()) while (true) {
            if (client.status.value != "Connected" && client.status.value != "Connecting…") client.connect(address, token)
            delay(5_000)
        }
    }
    LaunchedEffect(telemetry.metrics) {
        val m = telemetry.metrics
        val samples = buildList {
            add("CPU" to m.cpu); add("Memory" to m.memory)
            m.volumes.forEach { add("Disk ${it.name}" to it.percent) }
            m.networkInterfaces.forEach { add("Ethernet ${it.name} ↓" to (it.rx / 1_000_000f).coerceIn(0f, 100f)); add("Ethernet ${it.name} ↑" to (it.tx / 1_000_000f).coerceIn(0f, 100f)) }
            m.gpu?.let { add("GPU" to it) }
        }
        samples.forEach { (key, value) ->
            val series = graphHistory.getOrPut(key) { mutableStateListOf() }
            series.add(value)
            if (series.size > 48) series.removeAt(0)
        }
    }
    LaunchedEffect(trackKey, lyricsProvider) {
        val media = telemetry.media
        if (media.title.isBlank()) {
            lyrics = emptyList(); lyricsState = "Start a track on your PC to find synced lyrics."
        } else {
            lyrics = emptyList(); lyricsState = "Searching ${lyricsProvider.displayName}…"
            val found = repository.find(media.title, media.artist, lyricsProvider, media.durationMs)
            lyrics = found
            lyricsState = when {
                found.isEmpty() -> "No lyrics found from ${lyricsProvider.displayName}."
                lyricsProvider == LyricsProvider.LRCLIB -> "Synced lyrics · LRCLIB"
                else -> "Lyrics · lyrics.ovh (timing estimated)"
            }
        }
    }
    LaunchedEffect(trackKey) {
        albumArtwork = null
        val media = telemetry.media
        if (media.artist.isNotBlank() && (media.album.isNotBlank() || media.title.isNotBlank())) {
            albumArtwork = repository.findAlbumArtwork(media.title, media.artist, media.album)
        }
    }
    LaunchedEffect(fileActionResult) {
        fileActionResult?.takeIf { it.ok }?.path?.let(client::browse)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Background) {
                Text("VIBE / PC MONITOR", Modifier.padding(20.dp), color = Mint, fontWeight = FontWeight.Bold)
                listOf("Graphs", "Visualizer", "Scripts", "Monitors").forEach { target ->
                    NavigationDrawerItem(label = { Text(target) }, selected = page == target,
                        onClick = { page = target; scope.launch { drawerState.close() } }, modifier = Modifier.padding(horizontal = 12.dp))
                }
                Text("CONNECT TO YOUR PC", Modifier.padding(start = 20.dp, top = 24.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Put both devices on the same Wi-Fi, then scan the QR code shown by VIBE PC Companion.",
                    Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                    color = Muted,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = {
                        pairingMessage = ""
                        qrScanner.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("Scan the QR code in VIBE PC Companion")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            },
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Scan PC QR code") }
                if (pairingMessage.isNotBlank()) {
                    Text(pairingMessage, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = if (pairingMessage.startsWith("PC QR")) Mint else Color(0xFFFF8585), fontSize = 12.sp)
                }
                Text(status, Modifier.padding(20.dp), color = if (status == "Connected") Mint else Muted, fontSize = 12.sp)
            }
        },
    ) {
        Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { drawerState.open() } }) { Text("☰", fontSize = 24.sp, color = Mint) }
                Column {
                    Text(when (page) { "Graphs" -> "SYSTEM GRAPHS"; "Scripts" -> "PC SCRIPTS"; "Monitors" -> "PC MONITORS"; else -> "NOW PLAYING" }, color = Mint, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(status, color = if (status == "Connected") Mint else Muted, fontSize = 12.sp)
                }
            }
            when (page) {
                "Graphs" -> GraphsScreen(
                    telemetry.metrics,
                    graphHistory,
                    controlResult,
                    onBrowse = { path -> browserOpen = true; client.browse(path) },
                    onTerminate = client::terminateProcess,
                )
                "Scripts" -> ScriptsScreen(controlResult, onRun = client::runScript)
                "Monitors" -> MonitorsScreen(
                    client,
                    status,
                    monitors,
                    screenFrame,
                    screenStatus,
                )
                else -> PlayerCard(
                    telemetry.media,
                    telemetry.spectrum,
                    lyrics,
                    lyricsState,
                    mediaControlResult,
                    lyricsProvider,
                    albumArtwork,
                    Modifier.fillMaxSize(),
                    visualizerStyle,
                    visualizerGain,
                    onStyleChange = { visualizerStyle = it },
                    onProviderChange = { lyricsProviderName = it.name },
                    onSkipNext = { client.mediaCommand("next") },
                    onSkipPrevious = { client.mediaCommand("previous") },
                    onVolumeChange = { steps ->
                        visualizerGain = (visualizerGain + steps * 0.06f).coerceIn(0.15f, 2f)
                        client.mediaCommand("volume", steps)
                    },
                )
            }
        }
    }
    if (browserOpen) DiskBrowserDialog(
        result = browseResult,
        actionResult = fileActionResult,
        onDismiss = { browserOpen = false },
        onOpen = { client.browse(it) },
        onAction = client::performFileAction,
    )
}

@Composable
private fun GraphsScreen(
    metrics: Metrics,
    history: Map<String, List<Float>>,
    controlResult: ControlResult?,
    onBrowse: (String) -> Unit,
    onTerminate: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf("") }
    var killTarget by remember { mutableStateOf<ProcessMetric?>(null) }
    val processIds = metrics.processes.associate { "${it.name} (${it.pid})" to it.pid }
    val processDetails: (String, (ProcessMetric) -> Float, (ProcessMetric) -> String) -> List<Pair<String, String>> = { heading, measure, label ->
        metrics.processes.sortedByDescending(measure).take(10).filter { measure(it) > 0f }.map { "${it.name} (${it.pid})" to label(it) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        controlResult?.let {
            Text(it.message, color = if (it.ok) Mint else Color(0xFFFF8585), fontSize = 12.sp)
        }
        UsageCard("CPU", metrics.cpu, "${metrics.cpu.toInt()}%", history["CPU"].orEmpty(), Mint, expanded == "CPU", processDetails("CPU", { it.cpu }, { "${it.cpu.toInt()}%" }), "Top processes by CPU", { expanded = if (expanded == "CPU") "" else "CPU" }, killablePids = processIds, onKill = { pid -> killTarget = metrics.processes.firstOrNull { it.pid == pid } })
        UsageCard("Memory", metrics.memory, "${metrics.memory.toInt()}% · ${formatBytes(metrics.memoryUsed)} / ${formatBytes(metrics.memoryTotal)}", history["Memory"].orEmpty(), Blue, expanded == "Memory", processDetails("Memory", { it.memoryBytes.toFloat() }, { "${it.memoryPercent.toInt()}% · ${formatBytes(it.memoryBytes)}" }), "Top processes by resident memory", { expanded = if (expanded == "Memory") "" else "Memory" }, killablePids = processIds, onKill = { pid -> killTarget = metrics.processes.firstOrNull { it.pid == pid } })
        if (metrics.volumes.isEmpty()) UsageCard("System drive", metrics.disk, "${metrics.disk.toInt()}% · ${formatBytes(metrics.diskUsed)} / ${formatBytes(metrics.diskTotal)}", history["Disk C:"].orEmpty(), Color(0xFFFFC46B), expanded == "System drive", emptyList(), "Drive-to-disk mapping unavailable · double-tap to browse", { expanded = if (expanded == "System drive") "" else "System drive" }, { onBrowse("C:\\") })
        metrics.volumes.forEach { volume ->
            val title = "Disk ${volume.name}"
            val mappedDevices = volume.diskDevices.toSet()
            val diskIoForVolume = metrics.diskIo.filter { it.name.lowercase() in mappedDevices }
            val ioTitle = if (mappedDevices.isEmpty()) "Drive-to-disk mapping unavailable · double-tap to browse ${volume.name}" else "Physical disk I/O · double-tap to browse ${volume.name}"
            UsageCard(title, volume.percent, "${volume.percent.toInt()}% · ${formatBytes(volume.used)} / ${formatBytes(volume.total)}", history[title].orEmpty(), Color(0xFFFFC46B), expanded == title, diskIoForVolume.map { it.name to "Read ${formatRateBytes(it.readBps)} · Write ${formatRateBytes(it.writeBps)}" }, ioTitle, { expanded = if (expanded == title) "" else title }, { onBrowse(volume.name + "\\") })
        }
        metrics.networkInterfaces.forEach { net ->
            val rxTitle = "Ethernet ${net.name} ↓"
            val txTitle = "Ethernet ${net.name} ↑"
            val networkProcesses = metrics.processes.sortedByDescending { it.networkConnections }.take(10).filter { it.networkConnections > 0 }.map { "${it.name} (${it.pid})" to "${it.networkConnections} active connections" }
            UsageCard("Ethernet · ${net.name} ↓", (net.rx / 1_000_000f).coerceIn(0f, 100f), "↓ ${formatRate(net.rx)} Mbps", history[rxTitle].orEmpty(), Blue, expanded == rxTitle, networkProcesses, "Top processes by active connections (not transfer rate)", { expanded = if (expanded == rxTitle) "" else rxTitle }, killablePids = processIds, onKill = { pid -> killTarget = metrics.processes.firstOrNull { it.pid == pid } })
            UsageCard("Ethernet · ${net.name} ↑", (net.tx / 1_000_000f).coerceIn(0f, 100f), "↑ ${formatRate(net.tx)} Mbps", history[txTitle].orEmpty(), Mint, expanded == txTitle, networkProcesses, "Top processes by active connections (not transfer rate)", { expanded = if (expanded == txTitle) "" else txTitle }, killablePids = processIds, onKill = { pid -> killTarget = metrics.processes.firstOrNull { it.pid == pid } })
        }
        metrics.gpu?.let { gpu ->
            val gpuByPid = metrics.gpuProcesses.associateBy { it.pid }
            val gpuProcesses = metrics.processes.mapNotNull { p -> gpuByPid[p.pid]?.let { p to it.gpu } }.sortedByDescending { it.second }.take(10).filter { it.second > 0f }.map { (p, usage) -> "${p.name} (${p.pid})" to "${usage.toInt()}%" }
            UsageCard("GPU", gpu, "${gpu.toInt()}%", history["GPU"].orEmpty(), Color(0xFFE780F0), expanded == "GPU", gpuProcesses, "Top processes by GPU engine usage", { expanded = if (expanded == "GPU") "" else "GPU" }, killablePids = processIds, onKill = { pid -> killTarget = metrics.processes.firstOrNull { it.pid == pid } })
        }
        if (metrics.gpu == null) Text("GPU usage is not available from this PC's Windows counters.", color = Muted, fontSize = 12.sp)
        Text("Audio stays on your PC; only telemetry and spectrum levels are sent to the app.", color = Muted, fontSize = 11.sp)
    }
    killTarget?.let { process ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("End process?") },
            text = { Text("Force-stop ${process.name} (PID ${process.pid}) on the PC? Unsaved work in this process may be lost.") },
            confirmButton = {
                TextButton(onClick = { onTerminate(process.pid); killTarget = null }) { Text("End process") }
            },
            dismissButton = { TextButton(onClick = { killTarget = null }) { Text("Cancel") } },
            containerColor = Panel,
        )
    }
}

@Composable
private fun UsageCard(
    title: String,
    value: Float,
    subtitle: String,
    history: List<Float>,
    accent: Color,
    expanded: Boolean = false,
    details: List<Pair<String, String>> = emptyList(),
    detailsTitle: String? = null,
    onTap: (() -> Unit)? = null,
    onDoubleTap: (() -> Unit)? = null,
    killablePids: Map<String, Int> = emptyMap(),
    onKill: ((Int) -> Unit)? = null,
) {
    val currentOnTap by rememberUpdatedState(onTap)
    val currentOnDoubleTap by rememberUpdatedState(onDoubleTap)
    Card(
        modifier = if (onTap != null || onDoubleTap != null) Modifier.pointerInput(title) {
            detectTapGestures(onTap = { currentOnTap?.invoke() }, onDoubleTap = { currentOnDoubleTap?.invoke() })
        } else Modifier,
        colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("${value.toInt()}%", color = accent, fontSize = 23.sp, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, color = Muted, fontSize = 11.sp)
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val points = history.ifEmpty { listOf(value) }
                val path = Path()
                points.forEachIndexed { index, point ->
                    val x = if (points.size == 1) 0f else size.width * index / (points.size - 1)
                    val y = size.height * (1f - point.coerceIn(0f, 100f) / 100f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
            }
            if (expanded) {
                Text(detailsTitle ?: "Top processes", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (details.isEmpty()) Text("No current activity to show.", color = Muted, fontSize = 12.sp)
                details.take(10).forEach { (name, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Text(amount, color = Muted, fontSize = 12.sp)
                    }
                    killablePids[name]?.let { pid ->
                        TextButton(onClick = { onKill?.invoke(pid) }, modifier = Modifier.align(Alignment.End)) {
                            Text("End task", color = Color(0xFFFF8585), fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}

private val safeScriptActions = mapOf(
    "shutdown" to "Schedule shutdown (30 seconds)",
    "restart" to "Schedule restart (30 seconds)",
    "cancel_power" to "Cancel pending shutdown/restart",
    "restart_gpu_driver" to "Reset the GPU driver",
    "lock_workstation" to "Lock the Windows session",
    "open_task_manager" to "Open Task Manager",
)

@Composable
private fun ScriptsScreen(result: ControlResult?, onRun: (List<String>) -> Unit) {
    var pendingSteps by remember { mutableStateOf<List<String>?>(null) }

    fun requestRun(steps: List<String>) {
        pendingSteps = steps
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("PC ACTIONS", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        val titles = mapOf(
            "shutdown" to "Power off",
            "restart" to "Restart PC",
            "cancel_power" to "Cancel power",
            "restart_gpu_driver" to "Reset GPU",
            "lock_workstation" to "Lock PC",
            "open_task_manager" to "Task Manager",
        )
        safeScriptActions.toList().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (action, description) ->
                    ScriptTile(titles.getValue(action), description, Modifier.weight(1f)) {
                        requestRun(listOf(action))
                    }
                }
            }
        }
        Text("Actions are fixed, confirmed PC operations; arbitrary commands are not run.", color = Muted, fontSize = 12.sp)
        result?.let { Text(it.message, color = if (it.ok) Mint else Color(0xFFFF8585), fontSize = 13.sp) }
    }

    pendingSteps?.let { steps ->
        val destructive = steps.any { it == "shutdown" || it == "restart" }
        val title = when {
            steps.size > 1 -> "Run script on PC?"
            steps.first() == "shutdown" -> "Shut down the PC?"
            steps.first() == "restart" -> "Restart the PC?"
            steps.first() == "restart_gpu_driver" -> "Reset the GPU driver?"
            else -> "Run action on PC?"
        }
        AlertDialog(
            onDismissRequest = { pendingSteps = null },
            title = { Text(title) },
            text = {
                Text(
                    steps.joinToString("\n") { "• ${safeScriptActions[it] ?: it}" } +
                        if (destructive) "\n\nPower action is scheduled 30 seconds ahead; use Cancel power to abort it." else "",
                )
            },
            confirmButton = {
                TextButton(onClick = { onRun(steps); pendingSteps = null }) { Text("Run on PC") }
            },
            dismissButton = { TextButton(onClick = { pendingSteps = null }) { Text("Cancel") } },
            containerColor = Panel,
        )
    }
}

@Composable
private fun ScriptTile(title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(72.dp)) {
                Text(title, fontWeight = FontWeight.Bold)
            }
            Text(subtitle, color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun MonitorsScreen(
    client: PcClient,
    status: String,
    monitors: List<MonitorInfo>,
    frame: ScreenFrame?,
    screenStatus: String?,
) {
    var selectedMonitor by rememberSaveable { mutableIntStateOf(-1) }
    var keyboardVisible by rememberSaveable { mutableStateOf(true) }
    var shift by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(status) {
        if (status == "Connected") client.getMonitors()
    }
    LaunchedEffect(monitors) {
        if (monitors.none { it.id == selectedMonitor }) {
            selectedMonitor = (monitors.firstOrNull { it.primary } ?: monitors.firstOrNull())?.id ?: -1
        }
    }
    DisposableEffect(client, selectedMonitor, status) {
        if (status == "Connected" && selectedMonitor >= 1) {
            client.startScreen(selectedMonitor)
        }
        onDispose { client.stopScreen() }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            monitors.forEach { monitor ->
                TextButton(onClick = { selectedMonitor = monitor.id }) {
                    Text(
                        "Monitor ${monitor.id}${if (monitor.primary) " · Main" else ""} · ${monitor.width}×${monitor.height}",
                        color = if (monitor.id == selectedMonitor) Mint else Muted,
                    )
                }
            }
            if (status == "Connected") {
                TextButton(onClick = client::getMonitors) { Text("Refresh", color = Blue) }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .background(Color(0xFF05080B), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val selectedFrame = frame?.takeIf { it.monitorId == selectedMonitor }
            if (selectedFrame != null) {
                Image(
                    bitmap = selectedFrame.bitmap.asImageBitmap(),
                    contentDescription = "Live view of monitor $selectedMonitor. Drag to move the PC mouse; tap to left-click; hold to right-click.",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().pointerInput(selectedMonitor, selectedFrame.width, selectedFrame.height) {
                        val scale = min(size.width / selectedFrame.width.toFloat(), size.height / selectedFrame.height.toFloat())
                        val imageWidth = selectedFrame.width * scale
                        val imageHeight = selectedFrame.height * scale
                        val imageLeft = (size.width - imageWidth) / 2f
                        val imageTop = (size.height - imageHeight) / 2f
                        fun normalized(point: Offset): Offset = Offset(
                            ((point.x - imageLeft) / imageWidth).coerceIn(0f, 1f),
                            ((point.y - imageTop) / imageHeight).coerceIn(0f, 1f),
                        )

                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startedAt = SystemClock.uptimeMillis()
                            var dragging = false
                            var lastSentAt = 0L
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: continue
                                if (change.pressed) {
                                    if (!dragging && (change.position - down.position).getDistance() > 8f) {
                                        dragging = true
                                    }
                                    val now = SystemClock.uptimeMillis()
                                    if (dragging && now - lastSentAt >= 35L) {
                                        val position = normalized(change.position)
                                        client.moveMouse(selectedMonitor, position.x, position.y)
                                        lastSentAt = now
                                    }
                                } else {
                                    val position = normalized(change.position)
                                    if (dragging) {
                                        client.moveMouse(selectedMonitor, position.x, position.y)
                                    } else {
                                        val rightClick = SystemClock.uptimeMillis() - startedAt >= 500L
                                        client.clickMouse(selectedMonitor, position.x, position.y, rightClick)
                                    }
                                    change.consume()
                                    break
                                }
                            }
                        }
                    },
                )
            } else {
                Text(
                    screenStatus ?: if (status != "Connected") "Connect to the PC to view its monitors." else "Waiting for the PC screen…",
                    color = Muted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Drag: move · Tap: left click · Hold: right click", color = Muted, fontSize = 11.sp)
            TextButton(onClick = { keyboardVisible = !keyboardVisible }) {
                Text(if (keyboardVisible) "Hide keyboard" else "Show keyboard", color = Mint)
            }
        }
        if (keyboardVisible) {
            RemoteKeyboard(
                shift = shift,
                onShiftChange = { shift = it },
                onKey = { key ->
                    client.sendRemoteKey(key, shift)
                    if (key.length == 1 && shift) shift = false
                },
            )
        }
        Text(
            "Live screen and input use the authenticated PC connection. Only view/control machines you own or are authorized to use.",
            color = Muted,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun RemoteKeyboard(
    shift: Boolean,
    onShiftChange: (Boolean) -> Unit,
    onKey: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            "1234567890".forEach { key ->
                Button(
                    onClick = { onKey(key.toString()) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(key.toString(), fontSize = 13.sp) }
            }
        }
        listOf("QWERTYUIOP", "ASDFGHJKL").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { key ->
                    Button(
                        onClick = { onKey(key.toString()) },
                        modifier = Modifier.weight(1f).height(36.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    ) { Text(if (shift) key.toString() else key.lowercase(), fontSize = 13.sp) }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Button(
                onClick = { onShiftChange(!shift) },
                modifier = Modifier.weight(1.3f).height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text(if (shift) "SHIFT ✓" else "SHIFT", fontSize = 11.sp) }
            "ZXCVBNM".forEach { key ->
                Button(
                    onClick = { onKey(key.toString()) },
                    modifier = Modifier.weight(1f).height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(if (shift) key.toString() else key.lowercase(), fontSize = 13.sp) }
            }
            Button(
                onClick = { onKey("BACKSPACE") },
                modifier = Modifier.weight(1.3f).height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("⌫", fontSize = 15.sp) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("ESC" to "ESC", "TAB" to "TAB", "SPACE" to "SPACE", "ENTER" to "ENTER", "LEFT" to "←", "UP" to "↑", "DOWN" to "↓", "RIGHT" to "→").forEach { (key, label) ->
                Button(
                    onClick = { onKey(key) },
                    modifier = Modifier.weight(if (key == "SPACE") 2f else 1f).height(36.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) { Text(label, fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun DiskBrowserDialog(
    result: BrowseResult?,
    actionResult: FileActionResult?,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    onAction: (String, String, String?) -> Unit,
) {
    var selectedPath by remember(result?.path) { mutableStateOf<String?>(null) }
    var clipboard by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var confirmRun by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = result?.entries?.firstOrNull { it.path == selectedPath }
    LaunchedEffect(actionResult) {
        if (actionResult?.ok == true && pendingAction == "move") clipboard = null
        if (actionResult != null) pendingAction = null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(result?.path ?: "Loading disk…", maxLines = 1) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { selected?.let { clipboard = it.path to false } }, enabled = selected != null) { Text("Copy") }
                    TextButton(onClick = { selected?.let { clipboard = it.path to true } }, enabled = selected != null) { Text("Cut") }
                    TextButton(
                        onClick = {
                            clipboard?.let { (source, cut) ->
                                pendingAction = if (cut) "move" else "copy"
                                result?.let { onAction(pendingAction!!, source, it.path) }
                            }
                        },
                        enabled = clipboard != null && result != null,
                    ) { Text("Paste") }
                    TextButton(onClick = { selected?.let { pendingAction = "open"; onAction("open", it.path, null) } }, enabled = selected != null) { Text("Open on PC") }
                    TextButton(onClick = { confirmRun = true }, enabled = selected?.let { !it.directory && it.name.endsWith(".exe", true) } == true) { Text("Run") }
                    TextButton(onClick = { confirmDelete = true }, enabled = selected != null) { Text("Delete") }
                }
                clipboard?.let { (path, cut) ->
                    Text("${if (cut) "Cut" else "Copied"}: ${path.substringAfterLast('\\')}", color = Muted, fontSize = 11.sp)
                }
                Text("Tap a folder to enter; long-press it to select.", color = Muted, fontSize = 10.sp)
                actionResult?.let {
                    Text(it.message, color = if (it.ok) Mint else Color(0xFFFF8080), fontSize = 12.sp)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    if (result == null) Text("Loading…", color = Muted)
                    else {
                        result.parent?.let { parent ->
                            Text("..  Parent folder", Modifier.fillMaxWidth().pointerInput(parent) { detectTapGestures(onTap = { onOpen(parent) }) }.padding(vertical = 8.dp), color = Mint)
                        }
                        result.error?.let { Text(it, color = Color(0xFFFF8080)) }
                        result.entries.forEach { entry ->
                            val isSelected = entry.path == selectedPath
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(if (isSelected) Color(0x334DE2A3) else Color.Transparent, RoundedCornerShape(6.dp))
                                    .pointerInput(entry.path, entry.directory) {
                                        detectTapGestures(
                                            onTap = { if (entry.directory) onOpen(entry.path) else selectedPath = entry.path },
                                            onLongPress = { selectedPath = entry.path },
                                        )
                                    }
                                    .padding(horizontal = 8.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(if (entry.directory) "▸ ${entry.name}" else entry.name, color = if (entry.directory) Mint else Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                                if (!entry.directory) Text(formatBytes(entry.sizeBytes), color = Muted, fontSize = 12.sp)
                            }
                        }
                        if (result.entries.isEmpty() && result.error == null) Text("This folder is empty.", color = Muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        containerColor = Panel,
    )
    if (confirmRun && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmRun = false },
            title = { Text("Run on PC?") },
            text = { Text("Start ${selected.name} on the connected PC? Only executable files can be run.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRun = false
                    pendingAction = "run"
                    onAction("run", selected.path, null)
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { confirmRun = false }) { Text("Cancel") } },
            containerColor = Panel,
        )
    }
    if (confirmDelete && selected != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Permanently delete?") },
            text = {
                Text(
                    "Delete ${selected.name}${if (selected.directory) " and everything inside it" else ""} from the PC? This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    pendingAction = "delete"
                    onAction("delete", selected.path, null)
                }) { Text("Delete permanently", color = Color(0xFFFF8080)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = Panel,
        )
    }
}

private fun formatRateBytes(bytesPerSecond: Float): String = "%.1f MB/s".format(bytesPerSecond.coerceAtLeast(0f) / 1_000_000f)

@Composable
private fun PlayerCard(
    media: Media,
    spectrum: List<Float>,
    lyrics: List<LyricLine>,
    lyricsStatus: String,
    controlResult: ControlResult?,
    lyricsProvider: LyricsProvider,
    artwork: Bitmap?,
    modifier: Modifier = Modifier,
    visualizerStyle: String,
    visualizerGain: Float,
    onStyleChange: (String) -> Unit,
    onProviderChange: (LyricsProvider) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val transition = rememberInfiniteTransition(label = "water-ripple")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "ripple-phase")
    var lyricsExpanded by rememberSaveable { mutableStateOf(false) }
    var styleMenuOpen by remember { mutableStateOf(false) }
    var lyricsMenuOpen by remember { mutableStateOf(false) }
    val safeLyricsIndex = if (lyrics.isEmpty()) -1 else lyrics.indexOfLast { it.timeMs <= (media.positionMs ?: 0L) }.coerceIn(0, lyrics.lastIndex)
    val palette = remember(artwork) { albumPalette(artwork) }
    val lyricColor = remember(palette) { contrastingTextColor(palette) }

    BoxWithConstraints(modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF07111B))) {
        val panelHeight = 190.dp
        val lyricTop by animateDpAsState(
            targetValue = if (lyricsExpanded) ((maxHeight - panelHeight) / 2).coerceAtLeast(8.dp)
            else (maxHeight - panelHeight - 12.dp).coerceAtLeast(8.dp),
            label = "lyrics-position",
        )
        WaterRippleVisualizer(
            spectrum,
            media.isPlaying,
            phase,
            visualizerStyle,
            palette,
            visualizerGain,
            Modifier.fillMaxSize(),
            onLongPress = { styleMenuOpen = true },
            onTap = { x -> if (x >= 0.5f) onSkipNext() else onSkipPrevious() },
            onSwipe = { _, dy ->
                when {
                    dy < -0.12f -> onVolumeChange((kotlin.math.abs(dy) / 0.08f).toInt().coerceIn(1, 20))
                    dy > 0.12f -> onVolumeChange(-(kotlin.math.abs(dy) / 0.08f).toInt().coerceIn(1, 20))
                }
            },
        )
        controlResult?.let {
            Text(
                it.message,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 10.dp),
                color = if (it.ok) Mint else Color(0xFFFF8585),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
        Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {
            TextButton(onClick = { lyricsMenuOpen = true }) {
                Text("Lyrics · ${lyricsProvider.displayName}", color = Mint, fontSize = 11.sp)
            }
            DropdownMenu(expanded = lyricsMenuOpen, onDismissRequest = { lyricsMenuOpen = false }) {
                LyricsProvider.entries.forEach { provider ->
                    DropdownMenuItem(
                        text = { Text(if (provider == lyricsProvider) "✓ ${provider.displayName}" else provider.displayName) },
                        onClick = { onProviderChange(provider); lyricsMenuOpen = false },
                    )
                }
            }
        }
        Box(
            Modifier.align(Alignment.TopEnd).padding(10.dp)
                .size(58.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Mint.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
                .background(Panel)
                .pointerInput(Unit) { detectTapGestures(onLongPress = { styleMenuOpen = true }) },
            contentAlignment = Alignment.Center,
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork.asImageBitmap(),
                    contentDescription = "Album artwork for ${media.title} by ${media.artist}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("♫", color = Mint, fontSize = 25.sp)
            }
            DropdownMenu(expanded = styleMenuOpen, onDismissRequest = { styleMenuOpen = false }) {
                visualizerStyles.forEach { style ->
                    DropdownMenuItem(
                        text = { Text(if (style == visualizerStyle) "✓ $style" else style) },
                        onClick = { onStyleChange(style); styleMenuOpen = false },
                    )
                }
            }
        }
        Column(
            Modifier.align(Alignment.TopCenter).offset(y = lyricTop).fillMaxWidth().padding(horizontal = 20.dp)
                .pointerInput(lyricsExpanded) { detectTapGestures(onTap = { lyricsExpanded = !lyricsExpanded }) }
                .padding(horizontal = 18.dp, vertical = if (lyricsExpanded) 20.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (lyricsExpanded) 14.dp else 6.dp),
        ) {
            if (lyrics.isEmpty()) {
                Text(lyricsStatus, color = lyricColor, fontSize = 17.sp, textAlign = TextAlign.Center)
            } else {
                if (safeLyricsIndex > 0) {
                    Text(lyrics[safeLyricsIndex - 1].text, color = lyricColor.copy(alpha = 0.72f), fontSize = if (lyricsExpanded) 19.sp else 14.sp, textAlign = TextAlign.Center, maxLines = 2)
                }
                Text(lyrics[safeLyricsIndex].text.ifBlank { "♫" }, color = lyricColor, fontSize = if (lyricsExpanded) 30.sp else 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 3)
                if (safeLyricsIndex < lyrics.lastIndex) {
                    Text(lyrics[safeLyricsIndex + 1].text, color = lyricColor.copy(alpha = 0.88f), fontSize = if (lyricsExpanded) 19.sp else 15.sp, textAlign = TextAlign.Center, maxLines = 2)
                }
            }
            Text(if (lyricsExpanded) "Tap to return" else "Tap lyrics to expand", color = lyricColor.copy(alpha = 0.65f), fontSize = 10.sp)
        }
    }
}

@Composable
private fun WaterRippleVisualizer(
    spectrum: List<Float>,
    isPlaying: Boolean,
    phase: Float,
    style: String,
    palette: List<Color>,
    gain: Float,
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit,
    onTap: (Float) -> Unit,
    onSwipe: (Float, Float) -> Unit,
) {
    val currentLongPress by rememberUpdatedState(onLongPress)
    val currentTap by rememberUpdatedState(onTap)
    val currentSwipe by rememberUpdatedState(onSwipe)
    Canvas(
        modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var end = down.position
                    var dragged = false
                    var longPressed = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: continue
                        end = change.position
                        if ((end - down.position).getDistance() > viewConfiguration.touchSlop) dragged = true
                        if (!dragged && !longPressed &&
                            SystemClock.uptimeMillis() - down.uptimeMillis >= viewConfiguration.longPressTimeoutMillis
                        ) {
                            currentLongPress()
                            longPressed = true
                        }
                        if (!change.pressed) {
                            if (dragged) {
                                currentSwipe(
                                    (end.x - down.position.x) / size.width.coerceAtLeast(1).toFloat(),
                                    (end.y - down.position.y) / size.height.coerceAtLeast(1).toFloat(),
                                )
                            } else if (!longPressed) {
                                currentTap((end.x / size.width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f))
                            }
                            change.consume()
                            break
                        }
                    }
                }
            },
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.49f
        val bands = (spectrum.takeIf { it.isNotEmpty() } ?: List(32) { 0f })
            .map { (sqrt(it.coerceAtLeast(0f) * 3.5f) * gain).coerceIn(0f, 1f) }
        val energy = (bands.average().toFloat() * 2.8f).coerceIn(0f, 1f)
        val motion = if (isPlaying) 1f else 0.35f
        val spectrumColors = palette.takeIf { it.size >= 3 }
            ?: listOf(Color(0xFF00F5D4), Color(0xFF00D9FF), Color(0xFF8A5CFF), Color(0xFFFF42B3), Color(0xFFFFA52E))
        val animatedPhase = phase * 2f * PI.toFloat() * motion
        fun drawPaletteBackdrop() {
            val base = spectrumColors.map { Color(it.red * 0.18f, it.green * 0.18f, it.blue * 0.18f) }
            drawRect(brush = Brush.verticalGradient(base + base.first()))
            drawRect(
                brush = Brush.radialGradient(
                    listOf(
                        spectrumColors[0].copy(alpha = 0.34f + energy * 0.28f),
                        spectrumColors[1].copy(alpha = 0.22f + energy * 0.18f),
                        spectrumColors[2].copy(alpha = 0.12f + energy * 0.12f),
                        Color.Transparent,
                    ),
                    center,
                    radius * (0.9f + energy * 0.5f),
                ),
            )
        }
        if (style == "Spectrum") {
            drawPaletteBackdrop()
            val barGap = size.width * 0.006f
            val barWidth = (size.width - barGap * (bands.size + 1)) / bands.size
            bands.forEachIndexed { index, raw ->
                val value = raw.coerceIn(0f, 1f)
                val barHeight = size.height * (0.04f + value * 0.82f)
                val x = barGap + index * (barWidth + barGap)
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(spectrumColors[index % spectrumColors.size], spectrumColors[(index + 2) % spectrumColors.size].copy(alpha = 0.8f), spectrumColors[(index + 1) % spectrumColors.size].copy(alpha = 0.45f)), startY = size.height - barHeight, endY = size.height),
                    topLeft = Offset(x, (size.height - barHeight) / 2f),
                    size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                    alpha = 0.45f + value * 0.55f,
                )
            }
            return@Canvas
        }
        if (style == "Wave") {
            drawPaletteBackdrop()
            repeat(3) { layer ->
                val path = Path()
                val count = 192
                repeat(count) { index ->
                    val x = size.width * index / (count - 1)
                    val band = bands[index * bands.size / count].coerceIn(0f, 1f)
                    val phaseOffset = phase * 2f * PI.toFloat() * motion + layer
                    val y = center.y + sin(index.toFloat() / count * 14f * PI.toFloat() + phaseOffset).toFloat() * size.height * (0.05f + band * 0.35f)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, spectrumColors[layer + 1].copy(alpha = 0.7f + energy * 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + energy * 5f))
            }
            return@Canvas
        }
        if (style == "Pulse") {
            drawPaletteBackdrop()
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(spectrumColors[0].copy(alpha = 0.85f), spectrumColors[2].copy(alpha = 0.5f), Color.Transparent),
                    center,
                    radius * (0.25f + energy * 0.75f),
                ),
                center = center,
                radius = radius * (0.25f + energy * 0.75f),
            )
            repeat(8) { ring ->
                val ringPhase = (phase * motion + ring / 8f) % 1f
                drawCircle(
                    color = spectrumColors[ring % spectrumColors.size].copy(alpha = (1f - ringPhase) * (0.45f + energy * 0.55f)),
                    center = center,
                    radius = radius * (0.12f + ringPhase * (0.75f + energy * 0.2f)),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + energy * 8f),
                )
            }
            return@Canvas
        }
        if (style == "Aurora") {
            drawPaletteBackdrop()
            repeat(5) { layer ->
                val path = Path()
                val count = 180
                repeat(count) { index ->
                    val x = size.width * index / (count - 1)
                    val band = bands[index * bands.size / count]
                    val wave = sin(index.toFloat() / count * 5f * PI.toFloat() + animatedPhase + layer * 0.8f).toFloat()
                    val y = center.y + wave * size.height * (0.08f + band * 0.34f) + (layer - 2) * size.height * 0.08f
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, spectrumColors[layer % spectrumColors.size].copy(alpha = 0.42f + energy * 0.48f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f + energy * 9f))
            }
            return@Canvas
        }
        if (style == "Orbit") {
            drawPaletteBackdrop()
            drawCircle(brush = Brush.radialGradient(listOf(spectrumColors[2].copy(alpha = 0.65f), Color.Transparent), center, radius), center = center, radius = radius)
            bands.forEachIndexed { index, value ->
                val angle = animatedPhase + index.toFloat() / bands.size * 2f * PI.toFloat()
                val orbit = radius * (0.3f + value * 0.28f)
                val dotRadius = 3f + value * 13f
                drawCircle(
                    color = spectrumColors[index % spectrumColors.size].copy(alpha = 0.45f + value * 0.55f),
                    center = Offset(center.x + cos(angle) * orbit, center.y + sin(angle) * orbit),
                    radius = dotRadius,
                )
            }
            return@Canvas
        }
        if (style == "Tunnel") {
            drawPaletteBackdrop()
            repeat(28) { ring ->
                val progress = (phase * motion + ring / 28f) % 1f
                val band = bands[ring % bands.size]
                val ringRadius = radius * (0.04f + progress * (0.92f + band * 0.2f))
                drawCircle(
                    color = spectrumColors[ring % spectrumColors.size].copy(alpha = (1f - progress) * (0.28f + band * 0.7f)),
                    center = center,
                    radius = ringRadius,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f + band * 6f),
                )
            }
            return@Canvas
        }
        if (style == "Particles") {
            drawPaletteBackdrop()
            repeat(160) { particle ->
                val band = bands[particle % bands.size]
                val angle = particle * 2.39996f + animatedPhase * (0.25f + band)
                val progress = (phase * (0.2f + band * 0.7f) + particle / 160f) % 1f
                val distance = radius * (0.08f + progress * (0.82f + band * 0.28f))
                drawCircle(
                    color = spectrumColors[particle % spectrumColors.size].copy(alpha = (1f - progress * 0.45f) * (0.35f + band * 0.65f)),
                    center = Offset(center.x + cos(angle) * distance, center.y + sin(angle) * distance),
                    radius = 1.5f + band * 5f,
                )
            }
            return@Canvas
        }
        if (style == "Spiral") {
            drawPaletteBackdrop()
            val path = Path()
            val count = 360
            repeat(count) { point ->
                val progress = point.toFloat() / (count - 1)
                val angle = progress * 8f * PI.toFloat() + animatedPhase
                val band = bands[point * bands.size / count]
                val distance = radius * progress * (0.7f + band * 0.45f)
                val pointAt = Offset(center.x + cos(angle) * distance, center.y + sin(angle) * distance)
                if (point == 0) path.moveTo(pointAt.x, pointAt.y) else path.lineTo(pointAt.x, pointAt.y)
            }
            drawPath(path, brush = Brush.sweepGradient(spectrumColors, center), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + energy * 8f))
            return@Canvas
        }
        if (style == "Mirror") {
            drawPaletteBackdrop()
            val gap = size.width * 0.008f
            val barWidth = (size.width - gap * (bands.size + 1)) / bands.size
            bands.forEachIndexed { index, value ->
                val halfHeight = size.height * (0.025f + value * 0.43f)
                val x = gap + index * (barWidth + gap)
                val color = spectrumColors[index % spectrumColors.size]
                drawRoundRect(
                    brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color, color.copy(alpha = 0.35f)), center.y - halfHeight, center.y + halfHeight),
                    topLeft = Offset(x, center.y - halfHeight),
                    size = androidx.compose.ui.geometry.Size(barWidth, halfHeight * 2f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
                )
            }
            return@Canvas
        }
        if (style == "Grid") {
            drawPaletteBackdrop()
            val columns = 24
            val rows = 12
            val gap = size.width * 0.008f
            val cellWidth = (size.width - gap * (columns + 1)) / columns
            val cellHeight = (size.height - gap * (rows + 1)) / rows
            repeat(columns) { column ->
                val value = bands[column * bands.size / columns]
                val litRows = (value * rows).toInt()
                repeat(rows) { row ->
                    val lit = row >= rows - litRows
                    drawRoundRect(
                        color = spectrumColors[column % spectrumColors.size].copy(alpha = if (lit) 0.45f + value * 0.55f else 0.08f),
                        topLeft = Offset(gap + column * (cellWidth + gap), gap + row * (cellHeight + gap)),
                        size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(cellWidth * 0.2f),
                    )
                }
            }
            return@Canvas
        }
        if (style == "Plasma") {
            drawPaletteBackdrop()
            repeat(6) { layer ->
                val band = bands[layer * bands.size / 6]
                val angle = animatedPhase + layer * (2f * PI.toFloat() / 6f)
                val glowCenter = Offset(
                    center.x + cos(angle) * radius * (0.35f + band * 0.28f),
                    center.y + sin(angle * 1.3f) * radius * (0.25f + band * 0.22f),
                )
                val glowRadius = radius * (0.25f + band * 0.55f)
                drawCircle(
                    brush = Brush.radialGradient(listOf(spectrumColors[layer % spectrumColors.size].copy(alpha = 0.8f), spectrumColors[(layer + 2) % spectrumColors.size].copy(alpha = 0.22f), Color.Transparent), glowCenter, glowRadius),
                    center = glowCenter,
                    radius = glowRadius,
                )
            }
            return@Canvas
        }
        if (style == "Radial") {
            drawPaletteBackdrop()
            drawCircle(brush = Brush.radialGradient(listOf(spectrumColors[2].copy(alpha = 0.7f), spectrumColors[1].copy(alpha = 0.22f), Color.Transparent), center, radius), center = center, radius = radius)
            bands.forEachIndexed { index, raw ->
                val angle = index.toFloat() / bands.size * 2f * PI.toFloat()
                val value = raw.coerceIn(0f, 1f)
                val inner = radius * 0.18f
                val outer = inner + radius * (0.15f + value * 0.75f)
                drawLine(
                    color = spectrumColors[index * spectrumColors.size / bands.size].copy(alpha = 0.35f + value * 0.65f),
                    start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner),
                    end = Offset(center.x + cos(angle) * outer, center.y + sin(angle) * outer),
                    strokeWidth = 3f + value * 7f,
                )
            }
            repeat(3) { ring ->
                drawCircle(
                    color = spectrumColors[ring].copy(alpha = 0.55f),
                    center = center,
                    radius = radius * (0.18f + energy * 0.1f + ring * 0.16f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + energy * 4f),
                )
            }
            return@Canvas
        }
        drawPaletteBackdrop()
        drawCircle(
            brush = Brush.radialGradient(
                listOf(spectrumColors[0].copy(alpha = 0.72f), spectrumColors[2].copy(alpha = 0.52f), spectrumColors[3].copy(alpha = 0.28f), Color.Transparent),
                center,
                radius * (1.1f + energy * 0.3f),
            ),
            center = center,
            radius = radius,
        )
        repeat(72) { spoke ->
            val angle = spoke.toFloat() / 72f * (2f * PI.toFloat())
            val band = bands[(spoke * bands.size / 72)].coerceIn(0f, 1f)
            val start = radius * 0.12f
            val end = start + radius * (0.18f + band * 0.85f)
            val color = spectrumColors[spoke * spectrumColors.size / 72]
            drawLine(
                color = color.copy(alpha = 0.22f + band * 0.68f),
                start = Offset(center.x + cos(angle) * start, center.y + sin(angle) * start),
                end = Offset(center.x + cos(angle) * end, center.y + sin(angle) * end),
                strokeWidth = 1.5f + band * 5f,
            )
        }
        repeat(10) { ring ->
            val progress = (phase + ring / 7f) % 1f
            drawCircle(color = spectrumColors[ring % spectrumColors.size].copy(alpha = (1f - progress) * (0.45f + energy * 0.4f)), center = center, radius = radius * (0.12f + progress * 0.86f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2f + energy * 7f))
        }
        repeat(3) { layer ->
            val path = Path()
            val pointCount = 128
            repeat(pointCount) { point ->
                val angle = point.toFloat() / pointCount * (2f * PI.toFloat())
                val band = (bands[point * bands.size / pointCount].coerceIn(0f, 1f) * 4f).coerceIn(0f, 1f)
                val wave = sin(angle * (5f + layer) + phase * 2f * PI.toFloat() * motion + layer).toFloat()
                val contourRadius = radius * (0.16f + layer * 0.14f) + band * radius * (0.52f + layer * 0.08f) + wave * radius * 0.025f
                val x = center.x + cos(angle) * contourRadius
                val y = center.y + sin(angle) * contourRadius
                if (point == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, spectrumColors[(layer + 1) % spectrumColors.size].copy(alpha = 0.7f + energy * 0.25f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f + energy * 6f))
        }
        val coreRadius = radius * (0.07f + energy * 0.12f)
        drawCircle(brush = Brush.radialGradient(listOf(Color.White, spectrumColors[0].copy(alpha = 0.9f), spectrumColors[3].copy(alpha = 0.42f), Color.Transparent), center, coreRadius * 4f), center = center, radius = coreRadius * 4f)
    }
}

private fun albumPalette(bitmap: Bitmap?): List<Color> {
    if (bitmap == null || bitmap.width == 0 || bitmap.height == 0) return emptyList()
    val buckets = mutableMapOf<Int, Int>()
    val stepX = (bitmap.width / 40).coerceAtLeast(1)
    val stepY = (bitmap.height / 40).coerceAtLeast(1)
    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val red = android.graphics.Color.red(pixel)
            val green = android.graphics.Color.green(pixel)
            val blue = android.graphics.Color.blue(pixel)
            val brightness = (red + green + blue) / 3
            if (brightness < 28 || brightness > 245) continue
            val key = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
            buckets[key] = (buckets[key] ?: 0) + 1
        }
    }
    val colors = buckets.entries.sortedByDescending { it.value }.map { (key, _) ->
        Color(
            red = (((key shr 8) and 0xF) * 17) / 255f,
            green = (((key shr 4) and 0xF) * 17) / 255f,
            blue = ((key and 0xF) * 17) / 255f,
        )
    }
    return colors.distinctBy { "${(it.red * 16).toInt()}-${(it.green * 16).toInt()}-${(it.blue * 16).toInt()}" }.take(5)
        .takeIf { it.size >= 3 } ?: emptyList()
}

private fun contrastingTextColor(palette: List<Color>): Color {
    if (palette.isEmpty()) return Color.White
    fun linear(channel: Float): Float =
        if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
    val luminances = palette.map { color ->
        0.2126f * linear(color.red) + 0.7152f * linear(color.green) + 0.0722f * linear(color.blue)
    }
    val whiteContrast = luminances.map { 1.05f / (it + 0.05f) }.average()
    val blackContrast = luminances.map { (it + 0.05f) / 0.05f }.average()
    return if (whiteContrast >= blackContrast) Color.White else Color.Black
}

private fun formatBytes(bytes: Long): String = if (bytes <= 0L) "—" else when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f MB".format(bytes / 1_048_576.0)
    else -> "%.0f KB".format(bytes / 1024.0)
}
private fun formatRate(bitsPerSecond: Float): String = "%.2f".format(bitsPerSecond.coerceAtLeast(0f) / 1_000_000f)
