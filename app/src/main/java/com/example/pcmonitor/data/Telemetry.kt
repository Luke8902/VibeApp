package com.example.pcmonitor.data

import android.graphics.Bitmap

data class Metrics(
    val cpu: Float = 0f,
    val memory: Float = 0f,
    val disk: Float = 0f,
    val rx: Float = 0f,
    val tx: Float = 0f,
    val memoryUsed: Long = 0,
    val memoryTotal: Long = 0,
    val diskUsed: Long = 0,
    val diskTotal: Long = 0,
    val volumes: List<VolumeMetric> = emptyList(),
    val networkInterfaces: List<NetworkMetric> = emptyList(),
    val processes: List<ProcessMetric> = emptyList(),
    val gpuProcesses: List<ProcessMetric> = emptyList(),
    val diskIo: List<DiskIoMetric> = emptyList(),
    val gpu: Float? = null,
)

data class VolumeMetric(
    val name: String,
    val percent: Float,
    val used: Long,
    val total: Long,
    val diskDevices: List<String> = emptyList(),
)

data class NetworkMetric(
    val name: String,
    val rx: Float,
    val tx: Float,
)

data class ProcessMetric(
    val pid: Int,
    val name: String,
    val cpu: Float = 0f,
    val memoryBytes: Long = 0,
    val memoryPercent: Float = 0f,
    val diskReadBps: Float = 0f,
    val diskWriteBps: Float = 0f,
    val gpu: Float = 0f,
    val networkConnections: Int = 0,
)

data class DiskIoMetric(val name: String, val readBps: Float, val writeBps: Float)

data class BrowseEntry(val name: String, val path: String, val directory: Boolean, val sizeBytes: Long)

data class BrowseResult(val path: String, val parent: String?, val entries: List<BrowseEntry>, val error: String? = null)

data class FileActionResult(val ok: Boolean, val message: String, val path: String? = null)

data class ControlResult(val ok: Boolean, val message: String)

data class MonitorInfo(
    val id: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val primary: Boolean,
)

data class ScreenFrame(val monitorId: Int, val width: Int, val height: Int, val bitmap: Bitmap)

data class Media(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
)

data class Telemetry(
    val metrics: Metrics = Metrics(),
    val media: Media = Media(),
    val spectrum: List<Float> = List(32) { 0f },
)
