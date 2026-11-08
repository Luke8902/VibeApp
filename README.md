# VIBE PC Monitor

VIBE pairs an Android tablet with a Windows PC companion over a trusted local network. The Android app shows PC resource graphs, process details, music information, lyrics, and an audio-reactive visualizer. The Windows companion also supports the documented file, monitor, and preset-action features.

## Pairing

1. Put the Windows PC and Android device on the same trusted Wi-Fi network.
2. Start the Windows app from `pc_companion\dist\VibePcMonitor\VibePcMonitor.exe`.
3. In the Android app, open the hamburger menu, choose **Scan PC QR code**, and scan the code in the companion window.
4. Allow the companion through Windows Defender Firewall on private networks if prompted.

The app saves the pairing and reconnects on later launches. The QR contains a bearer token that permits PC actions; only display or scan it on a trusted network. The local WebSocket is not encrypted. Anyone who obtains the QR or token and can reach the PC may control the companion's documented capabilities.

## Build the Windows companion

Install Python 3.10+ and run these commands from `pc_companion` in PowerShell:

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\python.exe -m pip install -r requirements-build.txt
.\build_exe.ps1
```

The runnable distribution is `pc_companion\dist\VibePcMonitor\VibePcMonitor.exe`. Keep its neighboring `_internal` folder alongside the executable.

Run companion tests with:

```powershell
.\.venv\Scripts\python.exe -m unittest -v
```

## Build the Android app

Open the project in Android Studio or build and test from PowerShell:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
```

The debug APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

See [pc_companion/README.md](pc_companion/README.md) for companion features, API details, and setup notes.