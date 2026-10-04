"""OT Studio LoRA Trainer: a small Windows app that trains a FLUX dev LoRA on Replicate from 10 to 20 photos.

It wraps train_lora.py. The Replicate token is typed in by the user, kept in memory only, and never saved.
Nothing is uploaded or paid for until the user ticks the agreement and presses Train LoRA.
"""
from __future__ import annotations

import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import webbrowser

import train_lora as train

TITLE = "OT Studio LoRA Trainer"
TOKENS_URL = "https://replicate.com/account/api-tokens"
DASHBOARD_URL = "https://replicate.com/trainings"
ORANGE, BLACK, SURFACE, TEXT, MUTED, BLUE = "#F7781A", "#000000", "#1B1B1B", "#ECECEC", "#A8A8A8", "#739FC6"


def default_jobs_dir() -> Path:
    return Path.home() / "Documents" / "OT Studio LoRA"


def suggest_trigger(name: str) -> str:
    """A rare, prompt-friendly word from the LoRA name, such as MARKFACE for "Mark face"."""
    letters = re.sub(r"[^A-Za-z0-9]", "", name).upper()[:12]
    if not letters or not letters[0].isalpha():
        letters = "OT" + letters
    return letters if len(letters) >= 4 else letters + "LORA"


class TrainerApp:
    def __init__(self, root: tk.Tk, api_factory=train.ReplicateApi, jobs_dir: Path | None = None, poll_seconds: float = 30.0):
        self.root = root
        self.api_factory = api_factory
        self.jobs_dir = Path(jobs_dir) if jobs_dir else default_jobs_dir()
        self.poll_seconds = poll_seconds
        self.api = None
        self.username = ""
        self.photos: list[Path] = []
        self.thumbs = []
        self.job_folder: Path | None = None
        self.events: queue.Queue = queue.Queue()
        self.working = False
        self.stop_polling = threading.Event()
        self.build()
        self.root.after(150, self.pump)

    # ---------- layout ----------
    def build(self):
        r = self.root
        r.title(TITLE)
        icon = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent / "branding")) / "trainer-icon.png"
        if icon.exists():
            try:
                self._icon = tk.PhotoImage(file=str(icon))
                r.iconphoto(True, self._icon)
            except tk.TclError:
                pass
        r.configure(bg=BLACK)
        r.minsize(500, 600)
        style = ttk.Style(r)
        style.theme_use("clam")
        style.configure(".", background=BLACK, foreground=TEXT, fieldbackground=SURFACE, font=("Segoe UI", 10))
        style.configure("Card.TFrame", background=SURFACE)
        style.configure("Card.TLabel", background=SURFACE, foreground=TEXT)
        style.configure("Muted.TLabel", background=SURFACE, foreground=MUTED, font=("Segoe UI", 9))
        style.configure("Head.TLabel", background=SURFACE, foreground=TEXT, font=("Segoe UI", 11, "bold"))
        style.configure("Title.TLabel", background=BLACK, foreground=ORANGE, font=("Segoe UI", 18, "bold"))
        style.configure("TCheckbutton", background=SURFACE, foreground=TEXT)
        style.map("TCheckbutton", background=[("active", SURFACE)])
        style.configure("Accent.TButton", background=ORANGE, foreground=BLACK, font=("Segoe UI", 11, "bold"), padding=8)
        style.map("Accent.TButton", background=[("disabled", "#5A3A20"), ("active", "#FF8C33")])
        style.configure("TButton", background="#2A3B50", foreground=TEXT, padding=6)
        style.map("TButton", background=[("active", "#34495F")])
        style.configure("TEntry", foreground=TEXT, insertcolor=TEXT)
        style.configure("Horizontal.TProgressbar", background=ORANGE, troughcolor=SURFACE)

        outer = ttk.Frame(r, padding=16)
        outer.pack(fill="both", expand=True)
        ttk.Label(outer, text=TITLE, style="Title.TLabel").pack(anchor="w")
        ttk.Label(outer, text="Train a LoRA of one face or style from 10 to 20 photos. Use it afterwards in OT Studio on any device.",
                  wraplength="6.2i").pack(anchor="w", pady=(2, 10))

        def card(title, note=None):
            f = ttk.Frame(outer, style="Card.TFrame", padding=12)
            f.pack(fill="x", pady=(0, 10))
            ttk.Label(f, text=title, style="Head.TLabel").pack(anchor="w")
            if note:
                ttk.Label(f, text=note, style="Muted.TLabel", wraplength="6i").pack(anchor="w", pady=(2, 6))
            return f

        c1 = card("1. Replicate token", "Paste the token from your Replicate account. It stays in memory and is never saved.")
        row = ttk.Frame(c1, style="Card.TFrame"); row.pack(fill="x")
        self.token = tk.StringVar()
        ttk.Entry(row, textvariable=self.token, show="•", width=46).pack(side="left", fill="x", expand=True)
        self.check_button = ttk.Button(row, text="Check token", command=self.check_token)
        self.check_button.pack(side="left", padx=(8, 0))
        ttk.Button(row, text="Get a token", command=lambda: webbrowser.open(TOKENS_URL)).pack(side="left", padx=(8, 0))
        self.account = ttk.Label(c1, text="", style="Muted.TLabel")
        self.account.pack(anchor="w", pady=(6, 0))

        c2 = card("2. Photos", "Choose 10 to 20 clear photos of the same face or style, from different angles and in different light.")
        row = ttk.Frame(c2, style="Card.TFrame"); row.pack(fill="x")
        ttk.Button(row, text="Choose photos...", command=self.choose_photos).pack(side="left")
        self.photo_count = ttk.Label(row, text="No photos chosen", style="Card.TLabel")
        self.photo_count.pack(side="left", padx=10)
        self.strip = ttk.Frame(c2, style="Card.TFrame")
        self.strip.pack(fill="x", pady=(8, 0))

        c3 = card("3. Name and trigger word", "The trigger word goes into your prompts to call up this LoRA, so pick a word that means nothing else.")
        grid = ttk.Frame(c3, style="Card.TFrame"); grid.pack(fill="x")
        ttk.Label(grid, text="LoRA name", style="Card.TLabel").grid(row=0, column=0, sticky="w", pady=2)
        self.name = tk.StringVar()
        ttk.Entry(grid, textvariable=self.name, width=36).grid(row=0, column=1, sticky="we", padx=8)
        ttk.Label(grid, text="Trigger word", style="Card.TLabel").grid(row=1, column=0, sticky="w", pady=2)
        self.trigger = tk.StringVar()
        ttk.Entry(grid, textvariable=self.trigger, width=36).grid(row=1, column=1, sticky="we", padx=8)
        grid.columnconfigure(1, weight=1)
        self.name.trace_add("write", self.name_changed)
        self.trigger.trace_add("write", lambda *_: self.refresh())

        c4 = card("4. Train", "Training runs on Replicate with ostris/flux-dev-lora-trainer (1,000 steps). It costs about $1.50 to $2 and takes about 20 to 30 minutes.")
        self.agree = tk.BooleanVar()
        ttk.Checkbutton(c4, text="Upload these photos to my Replicate account and start this paid training run.",
                        variable=self.agree, command=self.refresh).pack(anchor="w")
        row = ttk.Frame(c4, style="Card.TFrame"); row.pack(fill="x", pady=(8, 0))
        self.train_button = ttk.Button(row, text="Train LoRA", style="Accent.TButton", command=self.start_training)
        self.train_button.pack(side="left")
        ttk.Button(row, text="Resume a job...", command=self.resume_job).pack(side="left", padx=8)
        self.cancel_button = ttk.Button(row, text="Cancel training", command=self.cancel_training)
        self.progress = ttk.Progressbar(c4, mode="indeterminate")
        self.status = ttk.Label(c4, text="", style="Card.TLabel", wraplength="6i")
        self.status.pack(anchor="w", pady=(8, 0))

        self.result = ttk.Frame(outer, style="Card.TFrame", padding=12)
        ttk.Label(self.result, text="Your LoRA is ready", style="Head.TLabel").pack(anchor="w")
        self.reference = tk.StringVar()
        row = ttk.Frame(self.result, style="Card.TFrame"); row.pack(fill="x", pady=(6, 0))
        ttk.Label(row, text="Reference", style="Card.TLabel").pack(side="left")
        ttk.Entry(row, textvariable=self.reference, state="readonly", width=56).pack(side="left", fill="x", expand=True, padx=8)
        ttk.Button(row, text="Copy", command=lambda: self.copy(self.reference.get())).pack(side="left")
        self.result_note = ttk.Label(self.result, text="", style="Muted.TLabel", wraplength="6i")
        self.result_note.pack(anchor="w", pady=(6, 6))
        ttk.Button(self.result, text="Open the job folder", command=self.open_folder).pack(anchor="w")
        self.refresh()

    # ---------- state ----------
    def name_changed(self, *_):
        current = self.trigger.get()
        if not current or current == getattr(self, "_auto_trigger", ""):
            self._auto_trigger = suggest_trigger(self.name.get()) if self.name.get().strip() else ""
            self.trigger.set(self._auto_trigger)
        self.refresh()

    def ready_problem(self) -> str:
        if not self.username:
            return "Check the token first."
        if not 1 <= len(self.photos) <= 20:
            return "Choose 1 to 20 photos. Ten or more work best."
        if not self.name.get().strip():
            return "Name the LoRA."
        if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_]{0,79}", self.trigger.get()):
            return "Use a trigger word of letters, digits, or underscores, starting with a letter."
        if not self.agree.get():
            return "Tick the box to agree to the upload and the charge."
        return ""

    def refresh(self):
        problem = self.ready_problem()
        self.train_button.state(["disabled"] if problem or self.working else ["!disabled"])
        if not self.working and not self.result.winfo_ismapped():
            self.status.configure(text=problem or "Ready to train.")

    def say(self, text):
        self.events.put(("status", text))

    def pump(self):
        try:
            while True:
                kind, value = self.events.get_nowait()
                if kind == "status":
                    self.status.configure(text=value)
                elif kind == "account":
                    self.username = value
                    self.account.configure(text=f"Signed in as {value}." if value else "")
                    self.check_button.state(["!disabled"])
                elif kind == "error":
                    self.set_working(False)
                    self.check_button.state(["!disabled"])
                    self.status.configure(text=value)
                    messagebox.showerror(TITLE, value)
                elif kind == "done":
                    self.set_working(False)
                    self.show_result(value)
                elif kind == "ended":
                    self.set_working(False)
                    self.status.configure(text=value)
                self.refresh()
        except queue.Empty:
            pass
        self.root.after(150, self.pump)

    def set_working(self, on: bool):
        self.working = on
        if on:
            self.progress.pack(fill="x", pady=(10, 0), before=self.status)
            self.progress.start(12)
            self.cancel_button.pack(side="left", padx=8)
        else:
            self.progress.stop()
            self.progress.pack_forget()
            self.cancel_button.pack_forget()

    def background(self, work):
        def run():
            try:
                work()
            except train.TrainingError as e:
                self.events.put(("error", str(e)))
            except Exception:
                self.events.put(("error", "The network or Replicate failed. Nothing is retried on its own. Check the Replicate dashboard, then use Resume a job."))
        threading.Thread(target=run, daemon=True).start()

    # ---------- actions ----------
    def check_token(self):
        token = self.token.get().strip()
        if not token:
            messagebox.showinfo(TITLE, "Paste your Replicate token first.")
            return
        self.check_button.state(["disabled"])
        self.say("Checking the token...")

        def work():
            api = self.api_factory(token)
            name = train.account_username(api)
            self.api = api
            self.events.put(("account", name))
            self.say("Token works. Now choose the photos.")
        self.background(work)

    def choose_photos(self):
        files = filedialog.askopenfilenames(title="Choose 10 to 20 photos", filetypes=[("Photos", "*.jpg *.jpeg *.png *.webp")])
        if not files:
            return
        chosen = [Path(f) for f in files]
        if len(chosen) > 20:
            messagebox.showwarning(TITLE, "The trainer takes at most 20 photos. The first 20 were kept.")
            chosen = chosen[:20]
        self.photos = chosen
        self.photo_count.configure(text=f"{len(chosen)} photo{'s' if len(chosen) != 1 else ''} chosen" + ("" if len(chosen) >= 10 else " (10 or more work best)"))
        self.show_thumbs()
        self.refresh()

    def show_thumbs(self):
        for child in self.strip.winfo_children():
            child.destroy()
        self.thumbs = []
        try:
            from PIL import Image, ImageTk
        except ImportError:
            return
        for i, path in enumerate(self.photos):
            try:
                with Image.open(path) as im:
                    im.thumbnail((52, 52))
                    photo = ImageTk.PhotoImage(im.convert("RGB"))
            except Exception:
                continue
            self.thumbs.append(photo)
            tk.Label(self.strip, image=photo, bg=SURFACE).grid(row=i // 10, column=i % 10, padx=2, pady=2)

    def start_training(self):
        problem = self.ready_problem()
        if problem:
            messagebox.showinfo(TITLE, problem)
            return
        name, trigger, photos = self.name.get().strip(), self.trigger.get(), list(self.photos)
        slug = train.destination_slug(name)
        folder = self.jobs_dir / f"{slug}-{time.strftime('%Y%m%d-%H%M%S')}"
        self.job_folder = folder
        self.result.pack_forget()
        self.set_working(True)
        self.stop_polling.clear()

        def work():
            self.say("Preparing the photos...")
            self.jobs_dir.mkdir(parents=True, exist_ok=True)
            destination = train.ensure_destination(self.api, self.username, slug)
            train.prepare(photos, folder, name, destination, trigger)
            self.say("Uploading the photos and starting the training...")
            with train.job_lock(folder):
                job = train.submit(folder, self.api, accept_charge=True, upload_photos=True)
            self.watch(folder, job)
        self.background(work)

    def watch(self, folder: Path, job):
        started = time.monotonic()
        while job["status"] not in train.TERMINAL:
            minutes = int((time.monotonic() - started) // 60)
            self.say(f"Training on Replicate: {job['status']}, {minutes} min so far. You can close this window; training keeps running, and Resume a job picks it up later.")
            if self.stop_polling.wait(self.poll_seconds):
                return
            with train.job_lock(folder):
                job = train.status(folder, self.api)
        if job["status"] == "succeeded":
            self.events.put(("done", (folder, job)))
        else:
            self.events.put(("ended", f"The training {job['status']}. Nothing else was charged after that. See {DASHBOARD_URL} for details."))

    def resume_job(self):
        if not self.username:
            messagebox.showinfo(TITLE, "Check the token first. The job needs it to ask Replicate for the training's state.")
            return
        chosen = filedialog.askdirectory(title="Choose the job folder", initialdir=str(self.jobs_dir if self.jobs_dir.exists() else Path.home()))
        if not chosen:
            return
        folder = Path(chosen)
        self.job_folder = folder
        self.result.pack_forget()
        self.set_working(True)
        self.stop_polling.clear()

        def work():
            self.say("Asking Replicate for the training's state...")
            with train.job_lock(folder):
                job = train.status(folder, self.api)
            self.watch(folder, job)
        self.background(work)

    def cancel_training(self):
        if not self.job_folder or not messagebox.askyesno(TITLE, "Cancel this training at Replicate? Time already used is still charged."):
            return
        self.stop_polling.set()
        folder = self.job_folder

        def work():
            with train.job_lock(folder):
                job = train.cancel(folder, self.api)
            self.events.put(("ended", f"Training {job['status']}."))
        self.background(work)

    def show_result(self, value):
        folder, job = value
        self.reference.set(job["reference"])
        self.result_note.configure(text=(
            f"Trigger word: {job['trigger_word']}. Put it in your prompts.\n"
            "On the phone, open OT Studio's LoRA library. Tap Import file and choose trained.otstudio-lora.json from the job folder, "
            "or tap Add manually and paste the reference above. Use the LoRA with the FLUX dev LoRA image model.\n"
            "The job folder also keeps a backup of the weights."))
        self.result.pack(fill="x", pady=(0, 10))
        self.status.configure(text="Done. The LoRA is saved in your Replicate account.")

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.status.configure(text="Copied.")

    def open_folder(self):
        if self.job_folder and self.job_folder.exists():
            if os.name == "nt":
                os.startfile(self.job_folder)  # noqa: S606 - opens Explorer on the user's own folder
            else:
                subprocess.run(["xdg-open", str(self.job_folder)], check=False)

    def close(self):
        if self.working and not messagebox.askyesno(TITLE, "Training keeps running at Replicate, and it is still charged. Close anyway? Resume a job picks it up later."):
            return
        self.stop_polling.set()
        self.root.destroy()


def main():
    if os.name == "nt":
        # Sharp text on scaled displays; layout sizes are in inches, so they follow the scaling.
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
        except Exception:
            pass
    root = tk.Tk()
    app = TrainerApp(root)
    root.protocol("WM_DELETE_WINDOW", app.close)
    root.mainloop()


if __name__ == "__main__":
    sys.exit(main())
