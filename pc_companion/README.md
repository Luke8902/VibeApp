# Windows PC Companion

A local Windows companion that streams telemetry, media metadata, and a 32-band audio spectrum to a native Android app over an authenticated WebSocket. It provides limited, documented PC actions and interactive monitor viewing/control; it does not expose a general command shell.

## Requirements and setup

- Windows 10/11
- Python 3.10 or newer

From PowerShell, run these commands inside `pc_companion`:

```powershell
py -3.10 -m venv .venv
.\.venv\Scripts\python.exe -m pip install --upgrade pip
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
```

## Build and run the Windows app

Build and run the Windows companion from the `pc_companion` directory:

```powershell
.\build_exe.ps1
.\dist\VibePcMonitor\VibePcMonitor.exe
```

The companion window shows the PC's local address and pairing QR code. Put the PC and tablet on the same trusted Wi-Fi, then open the VIBE app's hamburger menu, select **Scan PC QR code**, and scan it. The app saves the address and token and reconnects automatically on later launches. If the PC has multiple network adapters, select the address used by the Wi-Fi network shared with the tablet. On first run, allow VIBE PC Companion through Windows Defender Firewall on private networks. ADB is not required. Closing the companion window exits the program; start it again from the same executable. Its GPU and disk-monitoring helper processes run with their console windows hidden.

The QR contains the PC's WebSocket address and bearer token. Keep it private: anyone who scans it and can reach the PC can issue the documented PC actions. The token is stored in `%LOCALAPPDATA%\VibePcMonitor\auth.token`; diagnostics are written to `%LOCALAPPDATA%\VibePcMonitor\companion.log`. For development, run `.\.venv\Scripts\python.exe service.py`; this prints the token to the terminal.

## Telemetry and graceful fallbacks

A JSON object is sent every 250 ms with a Unix-millisecond timestamp, CPU/RAM/all mounted-volume/network/physical-disk-I/O metrics, process CPU/memory/disk-I/O samples, GPU and per-process GPU counters when Windows exposes them, Windows current-media metadata, and exactly 32 spectrum values in the range 0–1. Network rates are adapter-level byte-counter deltas converted to bits per second; Windows process network activity is represented by open connection counts, not per-process throughput. The authenticated WebSocket supports folder browsing and copy/move/open/run actions. Paths are confined to mounted drive roots, symbolic links are rejected, and copy/paste refuses to overwrite an existing destination. Running a program is limited to `.exe` files and requires explicit confirmation in the app. Opening a file uses its normal Windows file association. If no media session exists, the media fields are empty/false/null. `PyAudioWPatch` captures WASAPI loopback audio locally and NumPy computes the spectrum; raw audio is never transmitted or saved. If loopback capture is interrupted, the service clears the spectrum temporarily and retries opening the device while continuing telemetry. If `winsdk` is not installed, media fields remain empty; transient metadata-read errors retain the last successfully read track until the next successful read.

The authenticated WebSocket also supports process termination, previous/next media transport controls via Windows Global System Media Transport Controls (with a Windows media-key fallback if the active session declines a request), and system-volume adjustment through the Windows volume keys. Media commands return a separate result message so the app can report whether a request was accepted or rejected. The visualizer page uses right/left half taps to skip forward/back, and vertical swipes to adjust PC volume. Swipe-driven audio sensitivity follows those volume changes. A small built-in script allowlist provides `shutdown`, `restart`, `cancel_power`, `restart_gpu_driver`, `lock_workstation`, and `open_task_manager`. Shutdown/restart are scheduled 30 seconds ahead so they can be canceled; the GPU reset sends the Windows display-driver reset shortcut. The Scripts page presents these as confirmed preset actions; arbitrary batch, shell, or elevated terminal commands are not exposed. The lock action uses Microsoft's documented `LockWorkStation` API, and shutdown behavior follows the Windows `shutdown` command documentation. Process termination rejects protected core Windows processes and requires confirmation in the app.

The Monitors page enumerates connected displays, streams a resized JPEG preview while the page is open, and forwards tablet pointer/virtual-keyboard input to the current Windows session. It uses `mss` and Pillow for screen capture and Windows user-input APIs for pointer/key events. This is interactive remote control: keep the bearer token private and use it only on a trusted network. The WebSocket transport is not encrypted. The virtual keyboard can be hidden at any time. The file browser offers permanent deletion only after an in-app confirmation; it refuses drive roots and paths inside Windows, Program Files, and ProgramData.

If no media session exists, the media fields are empty/false/null. `PyAudioWPatch` captures WASAPI loopback audio locally and NumPy computes the spectrum; raw audio is never transmitted or saved. If loopback capture is interrupted, the service retries the audio stream rather than stopping capture permanently.

References for the built-in PC actions:
- [Microsoft LockWorkStation function](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-lockworkstation)
- [Microsoft shutdown command](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/shutdown)
- [Microsoft SendInput function](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput)

The Android app can select LRCLIB for synced lyrics or lyrics.ovh for plain lyrics; lyrics.ovh lines are timed approximately across the track because that API returns no timestamps. Both need internet access on the phone. The lyrics.ovh client is open source under MIT; the LRCLIB API is openly accessible and its database provides synced lyrics. Lyrics content remains subject to each source's terms and licensing. Cover art is matched through the MusicBrainz open metadata API and Cover Art Archive, with Deezer's public search API as a fallback, then colors are sampled from the fetched album image for the visualizer palette. Album artwork is catalog imagery and is shown alongside the corresponding track. The WebSocket uses `ws://` without transport encryption. Use QR pairing only on a trusted LAN and keep the bearer token private.

Provider references: [LRCLIB API documentation](https://lrclib.net/docs), [lyrics.ovh open-source API project](https://github.com/NTag/lyrics.ovh), [MusicBrainz API](https://musicbrainz.org/doc/MusicBrainz_API), [Cover Art Archive](https://coverartarchive.org/), and [Deezer API](https://developers.deezer.com/api).



## Android app

Open this project in Android Studio, allow the Gradle sync to finish, then build and install the `app` configuration. Run Android unit tests from PowerShell with `.\gradlew.bat testDebugUnitTest`. Pair by scanning the QR shown in VIBE PC Companion while both devices are on the same trusted Wi-Fi; the app saves the connection and reconnects automatically on later launches. The app shows live CPU, memory, individual volume, network-adapter, disk I/O, and GPU graphs. Tap a graph to reveal its top processes; process rows include a confirmed End task action. Double-tap a disk graph to browse that volume and view its mapped physical-drive I/O. In the file browser, tap a file to select it, tap a folder to enter it, or long-press a folder to select it. Copy/Cut stage an item and Paste transfers it to the current folder; Open on PC uses the normal Windows association, and Run starts a selected `.exe` after confirmation. Permanent deletion requires a second confirmation and is blocked for Windows/program folders and drive roots. The Scripts page has confirmed preset buttons only; it does not expose a custom shell or elevated terminal. The Monitors page previews connected displays and supports drag-to-move, tap/hold mouse clicks, and a hideable virtual keyboard. The visualizer has 13 audio-reactive styles; long-press it to switch styles, tap its left/right sides to navigate tracks, and swipe vertically to adjust volume and visual intensity. The top-right album image is matched through MusicBrainz/Cover Art Archive, with Deezer as a fallback, and supplies the visualizer palette. Choose LRCLIB synced lyrics or lyrics.ovh plain lyrics from the top-left provider menu. Lyrics use whichever of black or white gives better contrast against the sampled album palette, and can be tapped to float and expand in the middle.
