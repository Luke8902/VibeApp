from __future__ import annotations

import asyncio
import ipaddress
import json
import logging
import os
import socket
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk

import psutil
import qrcode
from PIL import Image, ImageTk

from service import PORT, load_or_create_token, run_service


def lan_ipv4_addresses() -> list[str]:
    preferred = ""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route_socket:
            route_socket.connect(("192.0.2.1", 80))
            preferred = route_socket.getsockname()[0]
    except OSError:
        pass

    addresses: set[str] = set()
    interface_stats = psutil.net_if_stats()
    for interface, entries in psutil.net_if_addrs().items():
        stats = interface_stats.get(interface)
        if stats is not None and not stats.isup:
            continue
        for entry in entries:
            if entry.family != socket.AF_INET:
                continue
            try:
                address = ipaddress.ip_address(entry.address)
            except ValueError:
                continue
            if address.is_loopback or address.is_link_local or address.is_unspecified or address.is_multicast:
                continue
            addresses.add(str(address))

    return sorted(addresses, key=lambda value: (value != preferred, value))


def make_pairing_payload(address: str, token: str) -> str:
    return json.dumps({
        "type": "vibe-pc-connection",
        "version": 1,
        "url": f"ws://{address}:{PORT}/ws",
        "token": token,
    }, separators=(",", ":"))


def make_pairing_qr(address: str, token: str) -> Image.Image:
    return qrcode.make(make_pairing_payload(address, token)).convert("RGB")


class CompanionWindow:
    def __init__(self) -> None:
        self.root = tk.Tk()
        self.root.title("VIBE PC Companion")
        self.root.resizable(False, False)
        self.root.configure(bg="#101319")
        self.stop_event = threading.Event()
        self.started_event = threading.Event()
        self.server_error = ""
        self.token = load_or_create_token()
        self.image_ref: ImageTk.PhotoImage | None = None
        self.addresses = lan_ipv4_addresses()
        self.address_var = tk.StringVar(value=self.addresses[0] if self.addresses else "")
        self.status_var = tk.StringVar(value="Starting PC monitor service…")
        self.pairing_var = tk.StringVar()
        self.log_path = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VibePcMonitor" / "companion.log"
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self.log_file = self.log_path.open("a", encoding="utf-8", buffering=1)
        sys.stdout = self.log_file
        sys.stderr = self.log_file

        self._build_ui()
        self._refresh_qr()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.server_thread = threading.Thread(target=self._serve, name="pc-companion-service", daemon=True)
        self.server_thread.start()
        self.root.after(400, self._update_status)

    def _build_ui(self) -> None:
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("TFrame", background="#101319")
        style.configure("TLabel", background="#101319", foreground="#e7edf5", font=("Segoe UI", 10))
        style.configure("Title.TLabel", foreground="#80f0c0", font=("Segoe UI", 18, "bold"))
        style.configure("Hint.TLabel", foreground="#aeb8c6", wraplength=360, justify="center")
        style.configure("TButton", font=("Segoe UI", 10))
        style.configure("TCombobox", padding=5)

        frame = ttk.Frame(self.root, padding=20)
        frame.grid(row=0, column=0)
        ttk.Label(frame, text="VIBE PC COMPANION", style="Title.TLabel").grid(row=0, column=0, pady=(0, 6))
        ttk.Label(frame, text="Connect your tablet to the same Wi-Fi, then scan this code in the VIBE app.", style="Hint.TLabel").grid(row=1, column=0, pady=(0, 12))

        selector = ttk.Frame(frame)
        selector.grid(row=2, column=0, pady=(0, 6))
        ttk.Label(selector, text="PC network address:").grid(row=0, column=0, padx=(0, 8))
        self.address_box = ttk.Combobox(
            selector,
            textvariable=self.address_var,
            values=self.addresses,
            state="readonly" if self.addresses else "disabled",
            width=20,
        )
        self.address_box.grid(row=0, column=1)
        self.address_box.bind("<<ComboboxSelected>>", lambda _event: self._refresh_qr())
        ttk.Button(selector, text="Refresh", command=self._refresh_addresses).grid(row=0, column=2, padx=(8, 0))

        self.qr_label = ttk.Label(frame)
        self.qr_label.grid(row=3, column=0, pady=8)
        ttk.Label(frame, textvariable=self.pairing_var, style="Hint.TLabel").grid(row=4, column=0, pady=(0, 8))
        ttk.Label(frame, textvariable=self.status_var).grid(row=5, column=0, pady=(4, 2))
        ttk.Label(frame, text="Only scan this code on your trusted local network. It grants PC control.", style="Hint.TLabel").grid(row=6, column=0, pady=(8, 0))

    def _refresh_addresses(self) -> None:
        self.addresses = lan_ipv4_addresses()
        self.address_box.configure(values=self.addresses, state="readonly" if self.addresses else "disabled")
        preferred = self.addresses[0] if self.addresses else ""
        self.address_var.set(preferred)
        self._refresh_qr()

    def _refresh_qr(self) -> None:
        address = self.address_var.get()
        if not address:
            self.qr_label.configure(image="", text="No active network address found.\nConnect to Wi-Fi and press Refresh.")
            self.pairing_var.set("")
            self.status_var.set("Waiting for a network connection")
            return
        if not self.started_event.is_set():
            self.status_var.set("Starting PC monitor service…")
        image = make_pairing_qr(address, self.token).resize((300, 300), Image.Resampling.NEAREST)
        self.image_ref = ImageTk.PhotoImage(image)
        self.qr_label.configure(image=self.image_ref, text="")
        self.pairing_var.set(f"Tablet address: ws://{address}:{PORT}/ws")

    def _serve(self) -> None:
        try:
            asyncio.run(
                run_service(
                    adb_reverse=False,
                    announce_token=False,
                    stop_event=self.stop_event,
                    started_event=self.started_event,
                )
            )
        except Exception as error:
            logging.exception("The PC companion service stopped unexpectedly.")
            self.server_error = str(error)

    def _update_status(self) -> None:
        if self.server_error:
            self.status_var.set(f"Service failed: {self.server_error}\nSee log: {self.log_path}")
        elif self.started_event.is_set():
            self.status_var.set("Ready for QR pairing")
        elif not self.server_thread.is_alive():
            self.status_var.set(f"Service stopped. See log: {self.log_path}")
        else:
            self.status_var.set("Starting PC monitor service…")
        self.root.after(400, self._update_status)

    def close(self) -> None:
        self.stop_event.set()
        self.server_thread.join(timeout=1.5)
        self.log_file.close()
        self.root.destroy()


def main() -> None:
    CompanionWindow().root.mainloop()


if __name__ == "__main__":
    main()
