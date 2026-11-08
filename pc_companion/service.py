from __future__ import annotations

import asyncio
import base64
import ctypes
import errno
import hmac
import json
import os
import secrets
import shutil
import subprocess
import sys
import threading
from pathlib import Path
import time
from http import HTTPStatus
from typing import Any

import numpy as np
import psutil
from websockets.asyncio.server import ServerConnection, serve
from websockets.exceptions import ConnectionClosed

HOST = "0.0.0.0"
PORT = 8765
PATH = "/ws"
INTERVAL_SECONDS = 0.25
SPECTRUM_BANDS = 32
AUDIO_FRAMES_PER_BUFFER = 2048


def hidden_process_options() -> dict[str, Any]:
    if sys.platform != "win32":
        return {}
    startup_info = subprocess.STARTUPINFO()
    startup_info.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup_info.wShowWindow = subprocess.SW_HIDE
    return {
        "startupinfo": startup_info,
        "creationflags": subprocess.CREATE_NO_WINDOW,
    }


def empty_media() -> dict[str, Any]:
    return {
        "title": "",
        "artist": "",
        "album": "",
        "is_playing": False,
        "duration_ms": None,
        "position_ms": None,
    }


class WindowsMediaReader:
    def __init__(self) -> None:
        self._manager: Any = None
        self._playback_status: Any = None
        self._last_media = empty_media()
        self._last_warning = 0.0

    async def start(self) -> None:
        try:
            from winsdk.windows.media.control import (
                GlobalSystemMediaTransportControlsSessionManager as SessionManager,
            )
            from winsdk.windows.media.control import (
                GlobalSystemMediaTransportControlsSessionPlaybackStatus as PlaybackStatus,
            )
        except ImportError:
            print(
                "Windows media metadata disabled; install the optional package with: "
                "python -m pip install winsdk",
                file=sys.stderr,
            )
            return

        try:
            self._manager = await SessionManager.request_async()
            self._playback_status = PlaybackStatus
        except Exception as exc:
            print(f"Windows media metadata unavailable ({exc}); continuing without it.", file=sys.stderr)

    @staticmethod
    def _milliseconds(value: Any) -> int | None:
        if value is None:
            return None
        total_seconds = getattr(value, "total_seconds", None)
        if callable(total_seconds):
            return max(0, int(total_seconds() * 1000))
        return None

    async def current(self) -> dict[str, Any]:
        if self._manager is None:
            return self._last_media.copy()

        try:
            session = self._manager.get_current_session()
            if session is None:
                self._last_media = empty_media()
                return self._last_media.copy()

            media = empty_media()
            properties = await session.try_get_media_properties_async()
            media["title"] = str(properties.title or "")
            media["artist"] = str(properties.artist or "")
            media["album"] = str(properties.album_title or "")

            playback = session.get_playback_info()
            media["is_playing"] = (
                playback is not None
                and playback.playback_status == self._playback_status.PLAYING
            )

            timeline = session.get_timeline_properties()
            if timeline is not None:
                media["position_ms"] = self._milliseconds(timeline.position)
                start_ms = self._milliseconds(timeline.start_time)
                end_ms = self._milliseconds(timeline.end_time)
                if start_ms is not None and end_ms is not None and end_ms >= start_ms:
                    media["duration_ms"] = end_ms - start_ms
            self._last_media = media
        except Exception as error:
            now = time.monotonic()
            if now - self._last_warning > 10:
                print(f"Windows media metadata read failed; retaining last track ({error}).", file=sys.stderr)
                self._last_warning = now

        return self._last_media.copy()

    async def transport(self, action: str) -> dict[str, Any]:
        if self._manager is None:
            return {"type": "control_result", "ok": False, "message": "Windows media controls are unavailable."}
        try:
            session = self._manager.get_current_session()
        except Exception as error:
            return {"type": "control_result", "ok": False, "message": f"Unable to access the active media session: {error}"}
        if session is None:
            return {"type": "control_result", "ok": False, "message": "No active media session was found."}
        methods = {
            "next": "try_skip_next_async",
            "previous": "try_skip_previous_async",
        }
        method_name = methods.get(action)
        if method_name is None:
            return {"type": "control_result", "ok": False, "message": "Unsupported media action."}
        try:
            succeeded = await getattr(session, method_name)()
        except Exception as error:
            succeeded = False
            error_message = str(error)
        else:
            error_message = ""
        if not succeeded:
            if sys.platform != "win32":
                detail = error_message or "Windows media session declined the request."
                return {"type": "control_result", "ok": False, "message": f"Media command was rejected: {detail}"}
            virtual_key = 0xB0 if action == "next" else 0xB1
            try:
                ctypes.windll.user32.keybd_event(virtual_key, 0, 0, 0)
                ctypes.windll.user32.keybd_event(virtual_key, 0, 0x0002, 0)
            except (AttributeError, OSError) as error:
                detail = error_message or str(error)
                return {"type": "control_result", "ok": False, "message": f"Media command was rejected: {detail}"}
        return {
            "type": "control_result",
            "ok": True,
            "message": (
                "Next-track command sent." if action == "next" else "Previous-track command sent."
            ) if not succeeded else (
                "Skipped to the next track." if action == "next" else "Returned to the previous track."
            ),
        }


def adjust_system_volume(steps: int) -> dict[str, Any]:
    if sys.platform != "win32":
        return {"type": "control_result", "ok": False, "message": "System volume controls are supported only on Windows."}
    if not isinstance(steps, int) or steps == 0 or abs(steps) > 20:
        return {"type": "control_result", "ok": False, "message": "Volume adjustment must be between 1 and 20 steps."}
    try:
        user32 = ctypes.windll.user32
        virtual_key = 0xAF if steps > 0 else 0xAE
        for _ in range(abs(steps)):
            user32.keybd_event(virtual_key, 0, 0, 0)
            user32.keybd_event(virtual_key, 0, 0x0002, 0)
    except (AttributeError, OSError) as error:
        return {"type": "control_result", "ok": False, "message": f"System volume command failed: {error}"}
    return {
        "type": "control_result",
        "ok": True,
        "message": f"Volume {'increased' if steps > 0 else 'decreased'} by {abs(steps)} step(s).",
    }


class LoopbackSpectrum:
    def __init__(self) -> None:
        self._audio: Any = None
        self._stream: Any = None
        self._thread: threading.Thread | None = None
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._spectrum = [0.0] * SPECTRUM_BANDS
        self._channels = 0
        self._sample_rate = 0
        self._warned = False

    def _warn(self, message: str) -> None:
        if not self._warned:
            print(message, file=sys.stderr)
            self._warned = True

    def start(self) -> None:
        try:
            import pyaudiowpatch as pyaudio

            self._audio = pyaudio.PyAudio()
            self._thread = threading.Thread(target=self._capture, daemon=True)
            self._thread.start()
        except Exception as exc:
            self._warn(f"WASAPI loopback initialization failed ({exc}); spectrum will be retried.")
            self.close()

    def _open_stream(self) -> None:
        import pyaudiowpatch as pyaudio

        wasapi = self._audio.get_host_api_info_by_type(pyaudio.paWASAPI)
        default_output = self._audio.get_device_info_by_index(int(wasapi["defaultOutputDevice"]))
        if default_output.get("isLoopbackDevice"):
            device = default_output
        else:
            loopbacks = list(self._audio.get_loopback_device_info_generator())
            output_name = str(default_output.get("name", "")).casefold()
            device = next(
                (item for item in loopbacks if output_name and output_name in str(item.get("name", "")).casefold()),
                loopbacks[0] if loopbacks else None,
            )
        if device is None:
            raise RuntimeError("no WASAPI loopback device was found")
        channels = min(2, max(1, int(device["maxInputChannels"])))
        sample_rate = int(device["defaultSampleRate"])
        stream = self._audio.open(
            format=pyaudio.paInt16,
            channels=channels,
            rate=sample_rate,
            input=True,
            input_device_index=int(device["index"]),
            frames_per_buffer=AUDIO_FRAMES_PER_BUFFER,
        )
        self._channels = channels
        self._sample_rate = sample_rate
        self._stream = stream

    def _capture(self) -> None:
        while not self._stop.is_set():
            try:
                if self._stream is None:
                    self._open_stream()
                window = np.hanning(AUDIO_FRAMES_PER_BUFFER).astype(np.float32)
                window_sum = float(window.sum())
                frequencies = np.fft.rfftfreq(AUDIO_FRAMES_PER_BUFFER, d=1.0 / self._sample_rate)
                band_edges = np.geomspace(20.0, self._sample_rate / 2.0, SPECTRUM_BANDS + 1)
                raw_audio = self._stream.read(
                    AUDIO_FRAMES_PER_BUFFER, exception_on_overflow=False
                )
                samples = (
                    np.frombuffer(raw_audio, dtype=np.int16)
                    .reshape(-1, self._channels)
                    .astype(np.float32)
                    .mean(axis=1)
                    / 32768.0
                )
                if samples.size != AUDIO_FRAMES_PER_BUFFER:
                    continue

                magnitudes = np.abs(np.fft.rfft(samples * window)) * (2.0 / window_sum)
                values: list[float] = []
                for low, high in zip(band_edges[:-1], band_edges[1:]):
                    in_band = (frequencies >= low) & (frequencies < high)
                    if np.any(in_band):
                        magnitude = float(np.max(magnitudes[in_band]))
                    else:
                        center = float(np.sqrt(low * high))
                        magnitude = float(magnitudes[np.argmin(np.abs(frequencies - center))])
                    values.append(float(np.clip(magnitude * 4.0, 0.0, 1.0)))

                with self._lock:
                    self._spectrum = values
            except Exception as exc:
                self._warn(f"WASAPI loopback capture interrupted ({exc}); reconnecting.")
                with self._lock:
                    self._spectrum = [0.0] * SPECTRUM_BANDS
                stream, self._stream = self._stream, None
                if stream is not None:
                    try:
                        stream.close()
                    except Exception as close_error:
                        self._warn(f"Failed to close the interrupted WASAPI stream ({close_error}).")
                self._stop.wait(1.0)

    def current(self) -> list[float]:
        with self._lock:
            return self._spectrum.copy()

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None and self._thread.is_alive():
            self._thread.join(timeout=1.0)
        if self._stream is not None:
            try:
                self._stream.stop_stream()
            except Exception as error:
                self._warn(f"Failed to stop the WASAPI stream cleanly ({error}).")
            try:
                self._stream.close()
            except Exception as error:
                self._warn(f"Failed to close the WASAPI stream cleanly ({error}).")
            self._stream = None
        if self._audio is not None:
            try:
                self._audio.terminate()
            except Exception as error:
                self._warn(f"Failed to terminate WASAPI audio capture cleanly ({error}).")
            self._audio = None


class GpuReader:
    def __init__(self) -> None:
        self._value: float | None = None
        self._processes: list[dict[str, Any]] = []
        self._lock = threading.Lock()
        self._thread = threading.Thread(target=self._poll, daemon=True)
        self._thread.start()

    def snapshot(self) -> tuple[float | None, list[dict[str, Any]]]:
        with self._lock:
            return self._value, list(self._processes)

    def _poll(self) -> None:
        command = (
            "$c = Get-CimInstance Win32_PerfFormattedData_GPUPerformanceCounters_GPUEngine "
            "-ErrorAction SilentlyContinue; "
            "if ($c) { $g = $c | Group-Object ProcessId | ForEach-Object { "
            "[pscustomobject]@{pid=[int]$_.Name; percent=[math]::Min(100, ($_.Group | Measure-Object UtilizationPercentage -Sum).Sum)} }; "
            "ConvertTo-Json -Compress -InputObject @($g) }"
        )
        while True:
            value = None
            try:
                result = subprocess.run(
                    ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                    capture_output=True,
                    text=True,
                    timeout=8,
                    check=False,
                    **hidden_process_options(),
                )
                if result.returncode == 0 and result.stdout.strip():
                    parsed = json.loads(result.stdout)
                    processes = parsed if isinstance(parsed, list) else [parsed]
                    self._processes = [{"pid": int(item["pid"]), "gpu_percent": max(0.0, min(100.0, float(item["percent"])))} for item in processes]
                    value = min(100.0, sum(item["gpu_percent"] for item in self._processes))
            except (OSError, ValueError, KeyError, json.JSONDecodeError, subprocess.TimeoutExpired):
                self._processes = []
            with self._lock:
                self._value = value
            time.sleep(2.0)


class VolumeDiskMapper:
    def __init__(self) -> None:
        self._mapping: dict[str, list[str]] = {}
        self._lock = threading.Lock()
        threading.Thread(target=self._poll, daemon=True).start()

    def current(self) -> dict[str, list[str]]:
        with self._lock:
            return dict(self._mapping)

    def _poll(self) -> None:
        command = (
            "Get-Partition | Where-Object DriveLetter | "
            "ForEach-Object { \"$($_.DriveLetter):=PhysicalDrive$($_.DiskNumber)\" }"
        )
        while True:
            mapping: dict[str, list[str]] = {}
            try:
                result = subprocess.run(
                    ["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command],
                    capture_output=True,
                    text=True,
                    timeout=10,
                    check=False,
                    **hidden_process_options(),
                )
                if result.returncode == 0:
                    for line in result.stdout.splitlines():
                        drive, separator, device = line.strip().partition("=")
                        if separator and drive and device:
                            mapping.setdefault(drive.upper(), []).append(device.casefold())
            except (OSError, subprocess.TimeoutExpired):
                pass
            with self._lock:
                self._mapping = mapping
            time.sleep(30.0)


class MetricsReader:
    def __init__(self) -> None:
        psutil.cpu_percent(interval=None)
        self._gpu = GpuReader()
        self._volume_disk_mapper = VolumeDiskMapper()
        self._last_network = psutil.net_io_counters(pernic=True) or {}
        self._last_network_time = time.monotonic()
        self._last_interface_counters = self._last_network
        self._interface_rates: dict[str, tuple[float, float]] = {}
        self._last_process_io: dict[int, tuple[int, int]] = {}
        self._last_process_time = time.monotonic()
        self._process_usage: list[dict[str, Any]] = []
        self._last_disk_io = psutil.disk_io_counters(perdisk=True) or {}
        self._last_disk_io_time = time.monotonic()
        self._disk_io: list[dict[str, Any]] = []

    @staticmethod
    def _percent(value: Any) -> float:
        return float(max(0.0, min(100.0, float(value))))

    def current(self) -> dict[str, int | float]:
        result: dict[str, int | float] = {
            "cpu_percent": 0.0,
            "memory_percent": 0.0,
            "memory_used_bytes": 0,
            "memory_total_bytes": 0,
            "disk_percent": 0.0,
            "disk_used_bytes": 0,
            "disk_total_bytes": 0,
            "volumes": [],
            "network_interfaces": [],
            "processes": [],
            "disk_io": [],
            "gpu_percent": None,
            "net_rx_bps": 0.0,
            "net_tx_bps": 0.0,
        }

        try:
            result["cpu_percent"] = self._percent(psutil.cpu_percent(interval=None))
        except Exception:
            pass

        try:
            memory = psutil.virtual_memory()
            result["memory_percent"] = self._percent(memory.percent)
            result["memory_used_bytes"] = int(memory.used)
            result["memory_total_bytes"] = int(memory.total)
        except Exception:
            pass

        try:
            drive = os.environ.get("SystemDrive", "C:") + "\\"
            disk = psutil.disk_usage(drive)
            result["disk_percent"] = self._percent(disk.percent)
            result["disk_used_bytes"] = int(disk.used)
            result["disk_total_bytes"] = int(disk.total)
        except Exception:
            pass

        result["gpu_percent"], gpu_processes = self._gpu.snapshot()
        result["gpu_processes"] = gpu_processes

        try:
            now = time.monotonic()
            elapsed = max(now - self._last_process_time, 0.001)
            process_usage = []
            next_process_io: dict[int, tuple[int, int]] = {}
            network_connections: dict[int, int] = {}
            try:
                for connection in psutil.net_connections(kind="inet"):
                    if connection.pid is not None:
                        network_connections[connection.pid] = network_connections.get(connection.pid, 0) + 1
            except (psutil.Error, OSError):
                pass
            memory_total = int(psutil.virtual_memory().total)
            for process in psutil.process_iter(["pid", "name", "memory_info", "io_counters"]):
                try:
                    info = process.info
                    pid = int(info["pid"])
                    io = info.get("io_counters")
                    read_bytes = int(io.read_bytes) if io else 0
                    write_bytes = int(io.write_bytes) if io else 0
                    previous_io = self._last_process_io.get(pid, (read_bytes, write_bytes))
                    next_process_io[pid] = (read_bytes, write_bytes)
                    memory_info = info.get("memory_info")
                    process_usage.append({
                        "pid": pid,
                        "name": info.get("name") or f"Process {pid}",
                        "cpu_percent": self._percent(process.cpu_percent(None)),
                        "memory_bytes": int(memory_info.rss) if memory_info else 0,
                        "memory_percent": self._percent(100.0 * memory_info.rss / memory_total) if memory_info and memory_total else 0.0,
                        "disk_read_bps": max(0, read_bytes - previous_io[0]) / elapsed,
                        "disk_write_bps": max(0, write_bytes - previous_io[1]) / elapsed,
                        "network_connections": network_connections.get(pid, 0),
                    })
                except (psutil.Error, OSError, AttributeError, TypeError, ValueError):
                    continue
            self._last_process_io = next_process_io
            self._last_process_time = now
            result["processes"] = process_usage
        except Exception:
            pass

        try:
            now = time.monotonic()
            elapsed = max(now - self._last_disk_io_time, 0.001)
            disk_counters = psutil.disk_io_counters(perdisk=True) or {}
            disk_rates = []
            for name, counter in disk_counters.items():
                previous = self._last_disk_io.get(name)
                if previous:
                    disk_rates.append({"name": name, "read_bps": max(0, counter.read_bytes - previous.read_bytes) / elapsed, "write_bps": max(0, counter.write_bytes - previous.write_bytes) / elapsed})
            self._last_disk_io = disk_counters
            self._last_disk_io_time = now
            result["disk_io"] = disk_rates
        except Exception:
            pass

        try:
            volumes = []
            seen_mounts: set[str] = set()
            for partition in psutil.disk_partitions(all=False):
                mount = partition.mountpoint
                if mount in seen_mounts:
                    continue
                seen_mounts.add(mount)
                try:
                    usage = psutil.disk_usage(mount)
                    volume_name = mount.rstrip("\\") or mount
                    volumes.append({
                        "name": volume_name,
                        "percent": self._percent(usage.percent),
                        "used_bytes": int(usage.used),
                        "total_bytes": int(usage.total),
                        "disk_devices": self._volume_disk_mapper.current().get(volume_name[:2].upper(), []),
                    })
                except (OSError, PermissionError):
                    continue
            result["volumes"] = volumes
        except Exception:
            pass

        try:
            now = time.monotonic()
            elapsed = now - self._last_network_time
            counters = psutil.net_io_counters(pernic=True) or {}
            stats = psutil.net_if_stats()
            interface_rates = []
            total_rx = total_tx = 0.0
            if elapsed > 0:
                for name, counter in counters.items():
                    previous = self._last_interface_counters.get(name)
                    if previous is None or not stats.get(name, None) or not stats[name].isup:
                        continue
                    rx = max(0.0, 8.0 * (counter.bytes_recv - previous.bytes_recv) / elapsed)
                    tx = max(0.0, 8.0 * (counter.bytes_sent - previous.bytes_sent) / elapsed)
                    if rx + tx > 0 or name in self._interface_rates:
                        interface_rates.append({"name": name, "rx_bps": rx, "tx_bps": tx})
                    total_rx += rx
                    total_tx += tx
                self._interface_rates = {item["name"]: (item["rx_bps"], item["tx_bps"]) for item in interface_rates}
                self._last_interface_counters = counters
                self._last_network_time = now
            result["network_interfaces"] = interface_rates
            result["net_rx_bps"] = total_rx
            result["net_tx_bps"] = total_tx
        except Exception:
            pass

        return result


async def authorize(
    connection: ServerConnection, request: Any, token: str
) -> Any:
    if request.path != PATH:
        return connection.respond(HTTPStatus.NOT_FOUND, "Not Found\n")

    values = request.headers.get_all("Authorization")
    supplied = values[0] if len(values) == 1 else ""
    expected = f"Bearer {token}"
    if not hmac.compare_digest(supplied.encode("utf-8"), expected.encode("ascii")):
        return connection.respond(HTTPStatus.UNAUTHORIZED, "Unauthorized\n")
    return None


def managed_path(path_text: str) -> Path:
    candidate = Path(os.path.abspath(path_text))
    if not candidate.drive:
        raise ValueError("Choose a path on a mounted drive.")
    root = Path(candidate.anchor)
    current = root
    for part in candidate.relative_to(root).parts:
        current /= part
        if current.is_symlink():
            raise ValueError("Symbolic links are not supported in the file browser.")
    target = candidate.resolve(strict=True)
    if os.path.commonpath((str(root), str(target))) != str(root):
        raise ValueError("Path is outside its drive.")
    return target


def perform_file_action(action: str, path_text: str, destination_text: str | None = None) -> dict[str, Any]:
    try:
        source = managed_path(path_text)
        if action == "open":
            os.startfile(str(source))
            return {"type": "file_action", "ok": True, "message": f"Opened {source.name or source} on the PC."}
        if action == "run":
            if not source.is_file() or source.suffix.casefold() != ".exe":
                raise ValueError("Only .exe applications can be run from the app.")
            subprocess.Popen([str(source)], cwd=str(source.parent), close_fds=True)
            return {"type": "file_action", "ok": True, "message": f"Started {source.name} on the PC."}
        if action == "delete":
            if source == Path(source.anchor):
                raise ValueError("A drive root cannot be deleted.")
            protected_roots = (
                os.environ.get("WINDIR"),
                os.environ.get("ProgramFiles"),
                os.environ.get("ProgramFiles(x86)"),
                os.environ.get("ProgramData"),
            )
            for protected in filter(None, protected_roots):
                protected_path = Path(protected).resolve()
                if os.path.splitdrive(str(source).casefold())[0] != os.path.splitdrive(str(protected_path).casefold())[0]:
                    continue
                if os.path.commonpath((str(source).casefold(), str(protected_path).casefold())) == str(protected_path).casefold():
                    raise ValueError("Files inside Windows and installed-program folders cannot be deleted from the app.")
            if source.is_dir():
                shutil.rmtree(source)
            else:
                source.unlink()
            return {"type": "file_action", "ok": True, "message": f"Permanently deleted {source.name or source}."}
        if action not in ("copy", "move") or destination_text is None:
            raise ValueError("Unsupported file action.")
        destination = managed_path(destination_text)
        if not destination.is_dir():
            raise ValueError("Paste destination must be a folder.")
        if source == destination or source.parent == destination:
            raise ValueError("Source and destination are the same folder.")
        target = destination / source.name
        if target.exists():
            raise FileExistsError(f"{target.name} already exists. Nothing was overwritten.")
        if action == "move":
            try:
                os.rename(source, target)
            except OSError as error:
                if error.errno != errno.EXDEV and getattr(error, "winerror", None) != 17:
                    raise
                if source.is_dir():
                    try:
                        target.mkdir()
                        shutil.copytree(source, target, symlinks=True, dirs_exist_ok=True)
                    except Exception:
                        if target.exists():
                            shutil.rmtree(target, ignore_errors=True)
                        raise
                    try:
                        shutil.rmtree(source)
                    except OSError as move_error:
                        raise OSError(
                            f"Copied to {target}, but the original folder could not be fully removed: {move_error}"
                        ) from move_error
                else:
                    copy_file_exclusive(source, target)
                    source.unlink()
        elif source.is_dir():
            shutil.copytree(source, target, symlinks=True)
        else:
            copy_file_exclusive(source, target)
        return {
            "type": "file_action",
            "ok": True,
            "message": f"{'Moved' if action == 'move' else 'Copied'} {source.name} to {destination}.",
            "path": str(destination),
        }
    except (OSError, ValueError, shutil.Error) as error:
        return {"type": "file_action", "ok": False, "message": str(error)}


def copy_file_exclusive(source: Path, target: Path) -> None:
    created = False
    try:
        with source.open("rb") as source_file, target.open("xb") as target_file:
            created = True
            shutil.copyfileobj(source_file, target_file)
        shutil.copystat(source, target, follow_symlinks=False)
    except Exception:
        if created:
            target.unlink(missing_ok=True)
        raise


def browse_directory(path_text: str) -> dict[str, Any]:
    try:
        target = managed_path(path_text)
        if not target.is_dir() or not target.drive:
            raise ValueError("Choose a folder on a mounted drive.")
        root = Path(target.anchor).resolve(strict=True)
        if os.path.commonpath((str(root), str(target))) != str(root):
            raise ValueError("Folder is outside its drive.")
        entries = []
        with os.scandir(target) as children:
            for entry in children:
                try:
                    is_dir = entry.is_dir(follow_symlinks=False)
                    if not is_dir and not entry.is_file(follow_symlinks=False):
                        continue
                    entries.append({"name": entry.name, "path": entry.path, "directory": is_dir, "size_bytes": 0 if is_dir else entry.stat(follow_symlinks=False).st_size})
                except (OSError, PermissionError):
                    continue
        entries.sort(key=lambda item: (not item["directory"], -item["size_bytes"], item["name"].casefold()))
        return {"type": "browse", "path": str(target), "parent": str(target.parent) if target != root else None, "entries": entries[:250]}
    except (OSError, ValueError) as error:
        return {"type": "browse", "path": path_text, "parent": None, "entries": [], "error": str(error)}


def connected_monitors() -> list[dict[str, Any]]:
    import mss

    with mss.mss() as capture:
        return [
            {
                "id": index,
                "left": int(monitor["left"]),
                "top": int(monitor["top"]),
                "width": int(monitor["width"]),
                "height": int(monitor["height"]),
                "primary": int(monitor["left"]) == 0 and int(monitor["top"]) == 0,
            }
            for index, monitor in enumerate(capture.monitors[1:], start=1)
        ]


def capture_monitor(monitor_id: int) -> dict[str, Any]:
    from io import BytesIO

    import mss
    from PIL import Image

    with mss.mss() as capture:
        if monitor_id < 1 or monitor_id >= len(capture.monitors):
            raise ValueError("That monitor is no longer connected.")
        monitor = capture.monitors[monitor_id]
        shot = capture.grab(monitor)
        image = Image.frombytes("RGB", shot.size, shot.rgb)
        image.thumbnail((1280, 800), Image.Resampling.LANCZOS)
        output = BytesIO()
        image.save(output, format="JPEG", quality=62, optimize=True)
        return {
            "type": "screen_frame",
            "monitor_id": monitor_id,
            "width": image.width,
            "height": image.height,
            "jpeg": base64.b64encode(output.getvalue()).decode("ascii"),
        }


def remote_input(request: dict[str, Any]) -> dict[str, Any]:
    action = request.get("action")
    user32 = ctypes.windll.user32
    if action in ("move", "left_click", "right_click"):
        monitor_id = request.get("monitor_id")
        x = request.get("x")
        y = request.get("y")
        if not isinstance(monitor_id, int) or not isinstance(x, (int, float)) or not isinstance(y, (int, float)):
            raise ValueError("Invalid pointer coordinates.")
        monitor = next((item for item in connected_monitors() if item["id"] == monitor_id), None)
        if monitor is None:
            raise ValueError("That monitor is no longer connected.")
        x = min(1.0, max(0.0, float(x)))
        y = min(1.0, max(0.0, float(y)))
        point_x = monitor["left"] + round(x * (monitor["width"] - 1))
        point_y = monitor["top"] + round(y * (monitor["height"] - 1))
        if not user32.SetCursorPos(point_x, point_y):
            raise OSError("Windows could not move the pointer.")
        if action != "move":
            down, up = (0x0002, 0x0004) if action == "left_click" else (0x0008, 0x0010)
            user32.mouse_event(down, 0, 0, 0, 0)
            user32.mouse_event(up, 0, 0, 0, 0)
    elif action == "key":
        key = request.get("key")
        if not isinstance(key, str):
            raise ValueError("Invalid key.")
        virtual_keys = {
            **{chr(code): code for code in range(ord("A"), ord("Z") + 1)},
            **{chr(code): code for code in range(ord("0"), ord("9") + 1)},
            "SPACE": 0x20, "ENTER": 0x0D, "BACKSPACE": 0x08, "TAB": 0x09,
            "ESC": 0x1B, "DELETE": 0x2E, "LEFT": 0x25, "UP": 0x26,
            "RIGHT": 0x27, "DOWN": 0x28,
        }
        normalized = key.upper()
        virtual_key = virtual_keys.get(normalized)
        if virtual_key is None:
            raise ValueError("That key is not available on the virtual keyboard.")
        shift = bool(request.get("shift"))
        if shift:
            user32.keybd_event(0x10, 0, 0, 0)
        user32.keybd_event(virtual_key, 0, 0, 0)
        user32.keybd_event(virtual_key, 0, 0x0002, 0)
        if shift:
            user32.keybd_event(0x10, 0, 0x0002, 0)
    else:
        raise ValueError("Unsupported remote input.")
    return {"type": "input_result", "ok": True}


PROTECTED_PROCESS_NAMES = {
    "system",
    "registry",
    "smss.exe",
    "csrss.exe",
    "wininit.exe",
    "services.exe",
    "lsass.exe",
    "winlogon.exe",
}


def terminate_process(pid: int) -> dict[str, Any]:
    try:
        if pid <= 4 or pid == os.getpid():
            raise ValueError("This system process cannot be terminated.")
        process = psutil.Process(pid)
        name = process.name()
        if name.casefold() in PROTECTED_PROCESS_NAMES:
            raise ValueError("This protected Windows process cannot be terminated.")
        process.kill()
        process.wait(timeout=3)
        return {"type": "control_result", "ok": True, "message": f"Ended {name} (PID {pid})."}
    except (OSError, ValueError, psutil.Error) as error:
        return {"type": "control_result", "ok": False, "message": str(error)}


SAFE_SCRIPT_ACTIONS = {
    "shutdown": "Schedule PC shutdown in 30 seconds",
    "restart": "Schedule PC restart in 30 seconds",
    "cancel_power": "Cancel a pending shutdown or restart",
    "restart_gpu_driver": "Reset the display driver (Win+Ctrl+Shift+B)",
    "lock_workstation": "Lock the Windows session",
    "open_task_manager": "Open Task Manager",
}


def run_safe_script(steps: list[str]) -> dict[str, Any]:
    if not steps or len(steps) > 8:
        return {"type": "control_result", "ok": False, "message": "A script must contain 1–8 allowed actions."}
    for action in steps:
        if action not in SAFE_SCRIPT_ACTIONS:
            return {"type": "control_result", "ok": False, "message": f"Unsupported script action: {action}"}
    completed: list[str] = []
    for action in steps:
        try:
            if action == "shutdown":
                subprocess.run(
                    ["shutdown.exe", "/s", "/t", "30", "/c", "Scheduled by VIBE PC Monitor"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=5,
                    **hidden_process_options(),
                )
            elif action == "restart":
                subprocess.run(
                    ["shutdown.exe", "/r", "/t", "30", "/c", "Scheduled by VIBE PC Monitor"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=5,
                    **hidden_process_options(),
                )
            elif action == "cancel_power":
                subprocess.run(
                    ["shutdown.exe", "/a"],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=5,
                    **hidden_process_options(),
                )
            elif action == "restart_gpu_driver":
                if sys.platform != "win32":
                    raise OSError("GPU reset is supported only on Windows.")
                user32 = ctypes.windll.user32
                key_up = 0x0002
                keys = (0x5B, 0x11, 0x10, 0x42)
                for key in keys:
                    user32.keybd_event(key, 0, 0, 0)
                for key in reversed(keys):
                    user32.keybd_event(key, 0, key_up, 0)
            elif action == "lock_workstation":
                if sys.platform != "win32" or not ctypes.windll.user32.LockWorkStation():
                    raise OSError("Windows could not lock the current session.")
            elif action == "open_task_manager":
                subprocess.Popen(["taskmgr.exe"], close_fds=True)
            completed.append(SAFE_SCRIPT_ACTIONS[action])
        except (OSError, subprocess.SubprocessError) as error:
            return {
                "type": "control_result",
                "ok": False,
                "message": f"Stopped after {len(completed)} action(s): {error}",
            }
    return {"type": "control_result", "ok": True, "message": "Completed: " + "; ".join(completed)}


def load_or_create_token() -> str:
    app_data = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    token_file = app_data / "VibePcMonitor" / "auth.token"
    token_file.parent.mkdir(parents=True, exist_ok=True)
    if token_file.exists():
        token = token_file.read_text(encoding="utf-8").strip()
        if token:
            return token
    token = secrets.token_urlsafe(32)
    token_file.write_text(token, encoding="utf-8")
    return token


def enable_adb_reverse() -> bool:
    adb = shutil.which("adb")
    if adb is None:
        sdk_adb = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk" / "platform-tools" / "adb.exe"
        adb = str(sdk_adb) if sdk_adb.is_file() else None
    if adb is None:
        return False
    try:
        result = subprocess.run(
            [adb, "reverse", f"tcp:{PORT}", f"tcp:{PORT}"],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
            **hidden_process_options(),
        )
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


async def run_service(
    adb_reverse: bool = True,
    announce_token: bool = True,
    stop_event: threading.Event | None = None,
    started_event: threading.Event | None = None,
) -> None:
    token = load_or_create_token()
    adb_reversed = enable_adb_reverse() if adb_reverse else False
    clients: set[ServerConnection] = set()
    media = WindowsMediaReader()
    audio = LoopbackSpectrum()
    metrics = MetricsReader()
    screen_requests: dict[ServerConnection, int] = {}

    await media.start()
    audio.start()

    async def handler(websocket: ServerConnection) -> None:
        clients.add(websocket)
        try:
            async for message in websocket:
                try:
                    request = json.loads(message)
                    if request.get("type") == "browse" and isinstance(request.get("path"), str):
                        response = await asyncio.to_thread(browse_directory, request["path"])
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "file_action":
                        action = request.get("action")
                        path = request.get("path")
                        destination = request.get("destination")
                        if not isinstance(action, str) or not isinstance(path, str):
                            raise ValueError("Invalid file action.")
                        if destination is not None and not isinstance(destination, str):
                            raise ValueError("Invalid destination.")
                        response = await asyncio.to_thread(perform_file_action, action, path, destination)
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "terminate_process":
                        pid = request.get("pid")
                        if not isinstance(pid, int):
                            raise ValueError("Invalid process ID.")
                        response = await asyncio.to_thread(terminate_process, pid)
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "run_script":
                        steps = request.get("steps")
                        if not isinstance(steps, list) or not all(isinstance(step, str) for step in steps):
                            raise ValueError("Scripts must be lists of allowed action names.")
                        response = await asyncio.to_thread(run_safe_script, steps)
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "media_command":
                        action = request.get("action")
                        if action in ("next", "previous"):
                            response = await media.transport(action)
                        elif action == "volume":
                            response = await asyncio.to_thread(adjust_system_volume, request.get("volume_steps"))
                        else:
                            response = {"type": "control_result", "ok": False, "message": "Unsupported media action."}
                        response["type"] = "media_result"
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "get_monitors":
                        response = {"type": "monitors", "monitors": await asyncio.to_thread(connected_monitors)}
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    elif request.get("type") == "screen_start":
                        monitor_id = request.get("monitor_id")
                        if not isinstance(monitor_id, int):
                            raise ValueError("Invalid monitor ID.")
                        monitors = await asyncio.to_thread(connected_monitors)
                        if not any(item["id"] == monitor_id for item in monitors):
                            raise ValueError("That monitor is no longer connected.")
                        screen_requests[websocket] = monitor_id
                    elif request.get("type") == "screen_stop":
                        screen_requests.pop(websocket, None)
                    elif request.get("type") == "remote_input":
                        try:
                            response = await asyncio.to_thread(remote_input, request)
                        except (OSError, ValueError) as error:
                            response = {"type": "input_result", "ok": False, "message": str(error)}
                        await websocket.send(json.dumps(response, separators=(",", ":")))
                    else:
                        await websocket.close(code=1008, reason="Unsupported request")
                        break
                except (json.JSONDecodeError, TypeError, AttributeError, ValueError):
                    await websocket.close(code=1008, reason="Invalid request")
                    break
        except ConnectionClosed:
            pass
        finally:
            clients.discard(websocket)
            screen_requests.pop(websocket, None)

    async def process_request(connection: ServerConnection, request: Any) -> Any:
        return await authorize(connection, request, token)

    try:
        async with serve(
            handler,
            HOST,
            PORT,
            process_request=process_request,
            max_size=1024,
            max_queue=1,
            ping_interval=20,
            ping_timeout=20,
        ):
            if started_event is not None:
                started_event.set()
            if announce_token:
                print("\n" + "=" * 72)
                print("PC COMPANION BEARER TOKEN — scan the pairing QR or copy into the Android app:")
                print(token)
                print("=" * 72)
            print(f"WebSocket telemetry listening on ws://{HOST}:{PORT}{PATH}")
            if adb_reversed:
                print("ADB reverse forwarding is active on the connected Android device.")
            print("Audio stays local; only the 32 spectrum values are transmitted.\n")

            loop = asyncio.get_running_loop()
            next_tick = loop.time()
            next_screen_tick = loop.time()
            while stop_event is None or not stop_event.is_set():
                packet = {
                    "timestamp": int(time.time() * 1000),
                    "metrics": metrics.current(),
                    "media": await media.current(),
                    "spectrum": audio.current(),
                }
                encoded = json.dumps(packet, separators=(",", ":"), allow_nan=False)

                if clients:
                    current_clients = tuple(clients)
                    results = await asyncio.gather(
                        *(client.send(encoded) for client in current_clients),
                        return_exceptions=True,
                    )
                    failed = [
                        client
                        for client, result in zip(current_clients, results)
                        if isinstance(result, BaseException)
                    ]
                    if failed:
                        for client in failed:
                            clients.discard(client)
                            screen_requests.pop(client, None)
                        await asyncio.gather(
                            *(client.close() for client in failed), return_exceptions=True
                        )

                if screen_requests and loop.time() >= next_screen_tick:
                    next_screen_tick = loop.time() + 0.5
                    frames = {}
                    for monitor_id in set(screen_requests.values()):
                        try:
                            frames[monitor_id] = await asyncio.to_thread(capture_monitor, monitor_id)
                        except Exception as error:
                            frames[monitor_id] = {"type": "screen_error", "message": str(error)}
                    for client, monitor_id in tuple(screen_requests.items()):
                        frame = frames.get(monitor_id)
                        if frame is not None:
                            try:
                                await client.send(json.dumps(frame, separators=(",", ":")))
                            except ConnectionClosed:
                                clients.discard(client)
                                screen_requests.pop(client, None)

                next_tick += INTERVAL_SECONDS
                delay = next_tick - loop.time()
                if delay > 0:
                    await asyncio.sleep(delay)
                else:
                    next_tick = loop.time()
    finally:
        audio.close()


def main() -> None:
    try:
        asyncio.run(run_service())
    except KeyboardInterrupt:
        print("\nPC companion stopped.")


if __name__ == "__main__":
    main()
