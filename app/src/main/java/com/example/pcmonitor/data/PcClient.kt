package com.example.pcmonitor.data

import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PcClient {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private var socket: WebSocket? = null
    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry
    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status
    private val _browseResult = MutableStateFlow<BrowseResult?>(null)
    val browseResult: StateFlow<BrowseResult?> = _browseResult
    private val _fileActionResult = MutableStateFlow<FileActionResult?>(null)
    val fileActionResult: StateFlow<FileActionResult?> = _fileActionResult
    private val _controlResult = MutableStateFlow<ControlResult?>(null)
    val controlResult: StateFlow<ControlResult?> = _controlResult
    private val _mediaControlResult = MutableStateFlow<ControlResult?>(null)
    val mediaControlResult: StateFlow<ControlResult?> = _mediaControlResult
    private val _monitors = MutableStateFlow<List<MonitorInfo>>(emptyList())
    val monitors: StateFlow<List<MonitorInfo>> = _monitors
    private val _screenFrame = MutableStateFlow<ScreenFrame?>(null)
    val screenFrame: StateFlow<ScreenFrame?> = _screenFrame
    private val _screenStatus = MutableStateFlow<String?>(null)
    val screenStatus: StateFlow<String?> = _screenStatus

    fun browse(path: String) {
        _browseResult.value = null
        socket?.send(JSONObject().put("type", "browse").put("path", path).toString())
    }

    fun performFileAction(action: String, path: String, destination: String? = null) {
        _fileActionResult.value = null
        val request = JSONObject().put("type", "file_action").put("action", action).put("path", path)
        destination?.let { request.put("destination", it) }
        socket?.send(request.toString())
    }

    fun terminateProcess(pid: Int) {
        _controlResult.value = null
        socket?.send(JSONObject().put("type", "terminate_process").put("pid", pid).toString())
    }

    fun runScript(steps: List<String>) {
        _controlResult.value = null
        socket?.send(JSONObject().put("type", "run_script").put("steps", JSONArray(steps)).toString())
    }

    fun mediaCommand(action: String, volumeSteps: Int = 0) {
        _mediaControlResult.value = null
        socket?.send(
            JSONObject().put("type", "media_command")
                .put("action", action).put("volume_steps", volumeSteps).toString(),
        )
    }

    fun getMonitors() {
        _monitors.value = emptyList()
        socket?.send(JSONObject().put("type", "get_monitors").toString())
    }

    fun startScreen(monitorId: Int) {
        _screenFrame.value = null
        _screenStatus.value = null
        socket?.send(JSONObject().put("type", "screen_start").put("monitor_id", monitorId).toString())
    }

    fun stopScreen() {
        socket?.send(JSONObject().put("type", "screen_stop").toString())
        _screenFrame.value = null
    }

    fun moveMouse(monitorId: Int, x: Float, y: Float) {
        sendPointer("move", monitorId, x, y)
    }

    fun clickMouse(monitorId: Int, x: Float, y: Float, right: Boolean) {
        sendPointer(if (right) "right_click" else "left_click", monitorId, x, y)
    }

    fun sendRemoteKey(key: String, shift: Boolean = false) {
        socket?.send(
            JSONObject().put("type", "remote_input").put("action", "key")
                .put("key", key).put("shift", shift).toString(),
        )
    }

    private fun sendPointer(action: String, monitorId: Int, x: Float, y: Float) {
        socket?.send(
            JSONObject().put("type", "remote_input").put("action", action)
                .put("monitor_id", monitorId).put("x", x).put("y", y).toString(),
        )
    }

    @Synchronized
    fun connect(url: String, token: String) {
        disconnect()
        val address = url.trim().let { if (it.endsWith("/ws")) it else it.trimEnd('/') + "/ws" }
        val request = Request.Builder()
            .url(address)
            .header("Authorization", "Bearer ${token.trim()}")
            .build()
        _status.value = "Connecting…"
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _status.value = "Connected"
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    when (root.optString("type")) {
                        "browse" -> _browseResult.value = parseBrowse(root)
                        "monitors" -> {
                            val array = root.optJSONArray("monitors")
                            _monitors.value = array?.let {
                                List(it.length()) { index ->
                                    val item = it.getJSONObject(index)
                                    MonitorInfo(
                                        item.optInt("id"),
                                        item.optInt("left"),
                                        item.optInt("top"),
                                        item.optInt("width"),
                                        item.optInt("height"),
                                        item.optBoolean("primary"),
                                    )
                                }
                            } ?: emptyList()
                            _screenStatus.value = if (_monitors.value.isEmpty()) "No connected monitors were found." else null
                        }
                        "screen_frame" -> {
                            val bytes = Base64.decode(root.getString("jpeg"), Base64.DEFAULT)
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bitmap != null) {
                                _screenFrame.value = ScreenFrame(
                                    root.optInt("monitor_id"),
                                    root.optInt("width"),
                                    root.optInt("height"),
                                    bitmap,
                                )
                                _screenStatus.value = null
                            }
                        }
                        "screen_error" -> _screenStatus.value = root.optString("message", "Screen capture failed.")
                        "input_result" -> if (!root.optBoolean("ok")) {
                            _screenStatus.value = root.optString("message", "PC input failed.")
                        }
                        "file_action" -> _fileActionResult.value = FileActionResult(
                            root.optBoolean("ok"),
                            root.optString("message"),
                            root.optString("path").takeIf { it.isNotBlank() && it != "null" },
                        )
                        "control_result" -> _controlResult.value = ControlResult(
                            root.optBoolean("ok"),
                            root.optString("message"),
                        )
                        "media_result" -> _mediaControlResult.value = ControlResult(
                            root.optBoolean("ok"),
                            root.optString("message"),
                        )
                        else -> _telemetry.value = parse(text)
                    }
                }.onFailure { error ->
                    _status.value = "Connected · invalid PC response: ${error.message ?: "unknown format"}"
                }.onSuccess {
                    if (_status.value.startsWith("Connected · invalid PC response:")) {
                        _status.value = "Connected"
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _status.value = when (response?.code) {
                    401 -> "Unauthorized — check the PC token"
                    else -> "Connection failed: ${t.message ?: "check address and Wi-Fi"}"
                }
                socket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _status.value = "Disconnected"
            }
        })
    }

    @Synchronized
    fun disconnect() {
        socket?.close(1000, "Phone disconnected")
        socket = null
        _status.value = "Not connected"
    }

    private fun parseProcesses(array: JSONArray?): List<ProcessMetric> {
        if (array == null) return emptyList()
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            ProcessMetric(
                pid = item.optInt("pid"),
                name = item.optString("name", "Process ${item.optInt("pid")}"),
                cpu = item.optDouble("cpu_percent").toFloat(),
                memoryBytes = item.optLong("memory_bytes"),
                memoryPercent = item.optDouble("memory_percent").toFloat(),
                diskReadBps = item.optDouble("disk_read_bps").toFloat(),
                diskWriteBps = item.optDouble("disk_write_bps").toFloat(),
                gpu = item.optDouble("gpu_percent").toFloat(),
                networkConnections = item.optInt("network_connections"),
            )
        }
    }

    private fun parseBrowse(root: JSONObject): BrowseResult {
        val entries = root.optJSONArray("entries")?.let { array -> List(array.length()) { i ->
            val item = array.getJSONObject(i)
            BrowseEntry(item.optString("name"), item.optString("path"), item.optBoolean("directory"), item.optLong("size_bytes"))
        } } ?: emptyList()
        return BrowseResult(root.optString("path"), root.optString("parent").takeIf { it.isNotBlank() && it != "null" }, entries, root.optString("error").takeIf { it.isNotBlank() && it != "null" })
    }

    private fun parse(json: String): Telemetry {
        val root = JSONObject(json)
        val m = root.optJSONObject("metrics") ?: JSONObject()
        val mediaObject = root.optJSONObject("media") ?: JSONObject()
        return Telemetry(
            metrics = Metrics(
                cpu = m.optDouble("cpu_percent").toFloat(),
                memory = m.optDouble("memory_percent").toFloat(),
                disk = m.optDouble("disk_percent").toFloat(),
                rx = m.optDouble("net_rx_bps").toFloat(),
                tx = m.optDouble("net_tx_bps").toFloat(),
                memoryUsed = m.optLong("memory_used_bytes"),
                memoryTotal = m.optLong("memory_total_bytes"),
                diskUsed = m.optLong("disk_used_bytes"),
                diskTotal = m.optLong("disk_total_bytes"),
                volumes = m.optJSONArray("volumes")?.let { array ->
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        val devices = item.optJSONArray("disk_devices")?.let { list ->
                            List(list.length()) { i -> list.optString(i).lowercase() }
                        } ?: emptyList()
                        VolumeMetric(item.optString("name"), item.optDouble("percent").toFloat(), item.optLong("used_bytes"), item.optLong("total_bytes"), devices)
                    }
                } ?: emptyList(),
                networkInterfaces = m.optJSONArray("network_interfaces")?.let { array ->
                    List(array.length()) { index ->
                        val item = array.getJSONObject(index)
                        NetworkMetric(item.optString("name"), item.optDouble("rx_bps").toFloat(), item.optDouble("tx_bps").toFloat())
                    }
                } ?: emptyList(),
                processes = parseProcesses(m.optJSONArray("processes")),
                gpuProcesses = parseProcesses(m.optJSONArray("gpu_processes")),
                diskIo = m.optJSONArray("disk_io")?.let { array -> List(array.length()) { i ->
                    val item = array.getJSONObject(i)
                    DiskIoMetric(item.optString("name"), item.optDouble("read_bps").toFloat(), item.optDouble("write_bps").toFloat())
                } } ?: emptyList(),
                gpu = if (m.has("gpu_percent") && !m.isNull("gpu_percent")) m.optDouble("gpu_percent").toFloat() else null,
            ),
            media = Media(
                title = mediaObject.optString("title"),
                artist = mediaObject.optString("artist"),
                album = mediaObject.optString("album"),
                isPlaying = mediaObject.optBoolean("is_playing"),
                durationMs = mediaObject.optLong("duration_ms").takeIf { !mediaObject.isNull("duration_ms") },
                positionMs = mediaObject.optLong("position_ms").takeIf { !mediaObject.isNull("position_ms") },
            ),
            spectrum = root.optJSONArray("spectrum")?.let { array ->
                List(array.length()) { array.optDouble(it).toFloat().coerceIn(0f, 1f) }
            } ?: List(32) { 0f },
        )
    }
}
