"""Wallpaper pack checks: originals, dimensions, stale assets and failed imports."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

from PIL import Image


class WallpaperPackTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "scripts").mkdir()
        self.script = self.root / "scripts/prepare_wallpapers.py"
        shutil.copy2(Path(__file__).with_name("prepare_wallpapers.py"), self.script)
        self.output = self.root / "app/src/main/assets/wallpapers/local"

    def images(self, count, name):
        folder = self.root / name
        folder.mkdir()
        for i in range(count):
            Image.new("RGB", (2000, 1200), (i, 30, 50)).save(folder / f"{i:02d}.jpg")
        return folder

    def run_script(self, folder):
        return subprocess.run([sys.executable, str(self.script), str(folder)], capture_output=True)

    def test_supported_sizes_and_originals(self):
        for count in (1, 3, 13):
            with self.subTest(count=count):
                folder = self.images(count, f"images-{count}")
                before = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.iterdir()}
                self.assertEqual(self.run_script(folder).returncode, 0)
                catalog = json.loads((self.output / "catalog.json").read_text())
                self.assertEqual(len(catalog), count)
                self.assertEqual(len(list(self.output.glob("*.jpg"))), count * 2)
                for entry in catalog:
                    with Image.open(self.output / Path(entry["file"]).name) as image:
                        self.assertLessEqual(max(image.size), 1920)
                    with Image.open(self.output / Path(entry["thumb"]).name) as image:
                        self.assertEqual(image.size, (320, 180))
                self.assertEqual(before, {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                                          for p in folder.iterdir()})

    def test_replacement_keeps_backup_but_does_not_bundle_old_images(self):
        self.assertEqual(self.run_script(self.images(3, "old")).returncode, 0)
        self.assertEqual(self.run_script(self.images(1, "new")).returncode, 0)
        self.assertEqual(len(list(self.output.glob("*.jpg"))), 2)
        backups = list((self.root / ".wallpaper-backups").glob("previous-*"))
        self.assertEqual(len(backups), 1)
        self.assertEqual(len(list(backups[0].glob("*.jpg"))), 6)

    def test_failed_conversion_preserves_current_pack(self):
        self.assertEqual(self.run_script(self.images(1, "valid")).returncode, 0)
        before = {p.name: p.read_bytes() for p in self.output.iterdir()}
        broken = self.root / "broken"
        broken.mkdir()
        (broken / "broken.jpg").write_bytes(b"not an image")
        self.assertNotEqual(self.run_script(broken).returncode, 0)
        self.assertEqual(before, {p.name: p.read_bytes() for p in self.output.iterdir()})

    def test_rejects_empty_and_oversized_catalog(self):
        for count in (0, 16):
            self.assertNotEqual(self.run_script(self.images(count, f"invalid-{count}")).returncode, 0)
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
