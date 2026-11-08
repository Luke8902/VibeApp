import json
import os
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import service
from desktop_app import lan_ipv4_addresses, make_pairing_payload


class FileActionTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)

    def test_permanent_delete_removes_selected_file(self) -> None:
        target = self.root / "sample.txt"
        target.write_text("test", encoding="utf-8")

        result = service.perform_file_action("delete", str(target))

        self.assertTrue(result["ok"])
        self.assertFalse(target.exists())

    def test_permanent_delete_removes_selected_folder(self) -> None:
        target = self.root / "folder"
        target.mkdir()
        (target / "sample.txt").write_text("test", encoding="utf-8")

        result = service.perform_file_action("delete", str(target))

        self.assertTrue(result["ok"])
        self.assertFalse(target.exists())

    def test_delete_refuses_drive_root(self) -> None:
        result = service.perform_file_action("delete", Path.cwd().anchor)

        self.assertFalse(result["ok"])
        self.assertIn("drive root", result["message"])

    def test_delete_refuses_protected_system_folder(self) -> None:
        protected = self.root / "Windows"
        protected.mkdir()
        target = protected / "important.dll"
        target.write_text("test", encoding="utf-8")
        with patch.dict(
            os.environ,
            {"WINDIR": str(protected), "ProgramFiles": "", "ProgramFiles(x86)": "", "ProgramData": ""},
        ):
            result = service.perform_file_action("delete", str(target))

        self.assertFalse(result["ok"])
        self.assertTrue(target.exists())


class ScriptActionTests(unittest.TestCase):
    def test_arbitrary_commands_are_rejected(self) -> None:
        result = service.run_safe_script(["cmd.exe /c whoami"])

        self.assertFalse(result["ok"])

    @patch.object(service.subprocess, "run")
    def test_shutdown_uses_fixed_delayed_command(self, run) -> None:
        run.return_value.returncode = 0

        result = service.run_safe_script(["shutdown"])

        self.assertTrue(result["ok"])
        self.assertEqual(
            run.call_args.args[0],
            ["shutdown.exe", "/s", "/t", "30", "/c", "Scheduled by VIBE PC Monitor"],
        )

    @patch.object(service.subprocess, "Popen")
    def test_task_manager_uses_fixed_program(self, popen) -> None:
        result = service.run_safe_script(["open_task_manager"])

        self.assertTrue(result["ok"])
        popen.assert_called_once_with(["taskmgr.exe"], close_fds=True)


class ChildProcessVisibilityTests(unittest.TestCase):
    def test_windows_helper_processes_are_hidden(self) -> None:
        with patch.object(service.sys, "platform", "win32"):
            options = service.hidden_process_options()

        self.assertEqual(options["creationflags"], service.subprocess.CREATE_NO_WINDOW)
        self.assertEqual(
            options["startupinfo"].dwFlags & service.subprocess.STARTF_USESHOWWINDOW,
            service.subprocess.STARTF_USESHOWWINDOW,
        )
        self.assertEqual(options["startupinfo"].wShowWindow, service.subprocess.SW_HIDE)

    def test_non_windows_helper_process_options_remain_empty(self) -> None:
        with patch.object(service.sys, "platform", "linux"):
            self.assertEqual(service.hidden_process_options(), {})


class MediaControlTests(unittest.IsolatedAsyncioTestCase):
    async def test_next_track_uses_windows_media_session(self) -> None:
        class FakeSession:
            called = False

            async def try_skip_next_async(self) -> bool:
                self.called = True
                return True

        class FakeManager:
            def __init__(self, session) -> None:
                self.session = session

            def get_current_session(self):
                return self.session

        session = FakeSession()
        reader = service.WindowsMediaReader()
        reader._manager = FakeManager(session)

        result = await reader.transport("next")

        self.assertTrue(result["ok"])
        self.assertTrue(session.called)

    async def test_previous_track_uses_windows_media_session(self) -> None:
        class FakeSession:
            called = False

            async def try_skip_previous_async(self) -> bool:
                self.called = True
                return True

        class FakeManager:
            def __init__(self, session) -> None:
                self.session = session

            def get_current_session(self):
                return self.session

        session = FakeSession()
        reader = service.WindowsMediaReader()
        reader._manager = FakeManager(session)

        result = await reader.transport("previous")

        self.assertTrue(result["ok"])
        self.assertTrue(session.called)

    async def test_declined_transport_is_reported_instead_of_success(self) -> None:
        class FakeSession:
            async def try_skip_next_async(self) -> bool:
                return False

        class FakeManager:
            def get_current_session(self):
                return FakeSession()

        reader = service.WindowsMediaReader()
        reader._manager = FakeManager()

        with patch.object(service.sys, "platform", "linux"):
            result = await reader.transport("next")

        self.assertFalse(result["ok"])
        self.assertIn("rejected", result["message"])

    async def test_declined_transport_uses_windows_media_key_fallback(self) -> None:
        class FakeSession:
            async def try_skip_next_async(self) -> bool:
                return False

        class FakeManager:
            def get_current_session(self):
                return FakeSession()

        calls = []
        user32 = SimpleNamespace(keybd_event=lambda *args: calls.append(args))
        reader = service.WindowsMediaReader()
        reader._manager = FakeManager()

        with (
            patch.object(service.sys, "platform", "win32"),
            patch.object(service.ctypes, "windll", SimpleNamespace(user32=user32), create=True),
        ):
            result = await reader.transport("next")

        self.assertTrue(result["ok"])
        self.assertEqual([call[0] for call in calls], [0xB0, 0xB0])

    def test_volume_steps_are_bounded(self) -> None:
        result = service.adjust_system_volume(21)

        self.assertFalse(result["ok"])


class LoopbackRecoveryTests(unittest.TestCase):
    def test_capture_reopens_stream_after_read_failure(self) -> None:
        spectrum = service.LoopbackSpectrum()
        attempts = 0

        class FakeStream:
            def __init__(self, fails: bool) -> None:
                self.fails = fails

            def read(self, *_args, **_kwargs) -> bytes:
                if self.fails:
                    raise OSError("simulated device interruption")
                spectrum._stop.set()
                return bytes(service.AUDIO_FRAMES_PER_BUFFER * 2)

            def close(self) -> None:
                pass

        def reopen() -> None:
            nonlocal attempts
            attempts += 1
            spectrum._channels = 1
            spectrum._sample_rate = 44100
            spectrum._stream = FakeStream(fails=attempts == 1)

        spectrum._open_stream = reopen
        with patch.object(service, "pyaudiowpatch", create=True):
            spectrum._capture()

        self.assertEqual(attempts, 2)


class PairingTests(unittest.TestCase):
    def test_pairing_payload_has_android_connection_fields(self) -> None:
        token = "p" * 43
        payload = json.loads(make_pairing_payload("192.168.1.25", token))

        self.assertEqual(payload["type"], "vibe-pc-connection")
        self.assertEqual(payload["version"], 1)
        self.assertEqual(payload["url"], "ws://192.168.1.25:8765/ws")
        self.assertEqual(payload["token"], token)

    def test_network_address_discovery_excludes_loopback_and_link_local(self) -> None:
        addresses = lan_ipv4_addresses()

        self.assertTrue(all(not address.startswith(("127.", "169.254.")) for address in addresses))


if __name__ == "__main__":
    unittest.main()
