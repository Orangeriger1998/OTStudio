"""Drives the LoRA Trainer window against a simulated Replicate. No credentials or network calls."""
import json
from pathlib import Path
import tempfile
import time
import tkinter as tk
import unittest
from unittest.mock import patch

import lora_trainer_app as app_module
import train_lora as train

VERSION_HASH = "a" * 64


class FakeReplicate:
    def __init__(self, token):
        assert token == "r8_synthetic"
        self.calls, self.models, self.polls = [], {}, 0

    def request(self, method, path, body=None, allow_missing=False):
        self.calls.append((method, path))
        if path == "/v1/account":
            return {"username": "tester"}
        if method == "GET" and path.startswith("/v1/models/tester/"):
            return self.models.get(path.rsplit("/", 1)[-1])
        if method == "POST" and path == "/v1/models":
            self.models[body["name"]] = dict(body)
            return dict(body)
        if method == "POST" and path.endswith("/trainings"):
            assert body["destination"] == "tester/lora-sam" and body["input"]["trigger_word"] == "SAMLORA"
            return {"id": "train1", "status": "starting"}
        if method == "GET" and path == "/v1/trainings/train1":
            self.polls += 1
            if self.polls < 2:
                return {"id": "train1", "status": "processing"}
            return {"id": "train1", "status": "succeeded", "output": {"version": "tester/lora-sam:" + VERSION_HASH, "weights": "https://replicate.delivery/x/trained_model.tar"}}
        raise AssertionError((method, path))

    def upload(self, archive):
        return "file.abc", "https://api.replicate.com/v1/files/file.abc"

    def download(self, url, target):
        train.delivery_url(url)
        Path(target).write_bytes(b"synthetic weights")


class TrainerAppTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.dir = Path(self.temp.name)
        self.root = tk.Tk()
        self.root.withdraw()
        self.app = app_module.TrainerApp(self.root, api_factory=FakeReplicate, jobs_dir=self.dir / "jobs", poll_seconds=0.01)

    def tearDown(self):
        self.app.stop_polling.set()
        self.root.destroy()
        self.temp.cleanup()

    def spin(self, until, seconds=10):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            self.root.update()
            if until():
                return True
            time.sleep(0.01)
        return False

    def test_trigger_suggestion_follows_the_name_until_edited(self):
        self.app.name.set("Sam")
        self.assertEqual("SAMLORA", self.app.trigger.get())
        self.app.trigger.set("MYWORD")
        self.app.name.set("Sam face")
        self.assertEqual("MYWORD", self.app.trigger.get())
        self.assertEqual("OT9LORA", app_module.suggest_trigger("9"))

    def test_nothing_uploads_until_every_step_is_done_then_training_finishes(self):
        self.assertEqual("Check the token first.", self.app.ready_problem())
        self.app.token.set("r8_synthetic")
        self.app.check_token()
        self.assertTrue(self.spin(lambda: self.app.username == "tester"))
        photos = []
        from PIL import Image
        for i in range(10):
            # Every second photo is a PNG saved under a .jpg name, as phone apps often do; the trainer converts it.
            p = self.dir / f"p{i}.jpg"
            Image.new("RGB", (600, 520), (i * 20, 90, 160)).save(p, "PNG" if i % 2 else "JPEG")
            photos.append(p)
        small = self.dir / "tiny.jpg"; Image.new("RGB", (300, 300)).save(small, "JPEG")
        broken = self.dir / "broken.jpg"; broken.write_bytes(b"\xff\xd8\xff" + b"x" * 50)
        with patch.object(app_module.filedialog, "askopenfilenames", return_value=[str(p) for p in photos + [small, broken]]), \
                patch.object(app_module.messagebox, "showwarning") as warned:
            self.app.choose_photos()
        self.assertEqual(10, len(self.app.photos))
        message = warned.call_args[0][1]
        self.assertIn("tiny.jpg is too small (300 by 300 pixels)", message)
        self.assertIn("broken.jpg is not a photo the trainer can read", message)
        self.assertIn("Accepted: JPG, PNG, WebP, HEIC", message)
        # Choosing the same photos again adds nothing.
        with patch.object(app_module.filedialog, "askopenfilenames", return_value=[str(photos[0])]):
            self.app.choose_photos()
        self.assertEqual(10, len(self.app.photos))
        self.app.name.set("Sam")
        self.assertIn("Tick the box", self.app.ready_problem())
        self.assertEqual([("GET", "/v1/account")], self.app.api.calls)
        self.app.agree.set(True)
        self.assertEqual("", self.app.ready_problem())
        self.app.start_training()
        self.assertTrue(self.spin(lambda: self.app.reference.get() != ""), self.app.status.cget("text"))
        self.assertEqual("tester/lora-sam/" + VERSION_HASH, self.app.reference.get())
        folder = self.app.job_folder
        library = json.loads((folder / "trained.otstudio-lora.json").read_text(encoding="utf-8"))
        self.assertEqual(("Sam", "SAMLORA", "otstudio-lora-v1"), (library["name"], library["trigger_word"], library["format"]))
        self.assertTrue((folder / "lora-weights.tar").exists())
        # The ZIP holds plain JPEG copies, and the staging copies are gone.
        import zipfile
        with zipfile.ZipFile(folder / "photos.zip") as z:
            names = z.namelist()
            self.assertEqual([f"photo-{i:02d}.jpg" for i in range(1, 11)], names)
            self.assertTrue(all(z.read(n).startswith(b"\xff\xd8\xff") for n in names))
        self.assertFalse(any(p.name.startswith(".photos-") for p in self.app.jobs_dir.iterdir()))
        # The private model was created once, the training was posted once, and the token never reached a file.
        self.assertEqual(1, sum(1 for c in self.app.api.calls if c == ("POST", "/v1/models")))
        self.assertEqual(1, sum(1 for c in self.app.api.calls if c[0] == "POST" and c[1].endswith("/trainings")))
        for f in folder.rglob("*"):
            if f.is_file():
                self.assertNotIn(b"r8_synthetic", f.read_bytes())


if __name__ == "__main__":
    unittest.main()
