#!/usr/bin/env python3
"""Create local-only wallpaper assets from images you have permission to use."""
import argparse
import json
import shutil
import uuid
from pathlib import Path

from PIL import Image, ImageOps

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("folder", type=Path, help="Folder containing 1–15 JPEG/PNG/WebP images")
args = parser.parse_args()
files = sorted(p for p in args.folder.iterdir()
               if p.is_file() and p.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"})
if not 1 <= len(files) <= 15:
    parser.error("Use a folder containing 1–15 supported images.")
project = Path(__file__).resolve().parents[1]
output = project / "app/src/main/assets/wallpapers/local"
# Stage outside assets: a failed conversion cannot leave a partially updated APK.
# Preserve prior generated packs outside assets, so obsolete photos are not bundled.
staging = project / ".wallpaper-backups" / ("staging-" + uuid.uuid4().hex)
staging.mkdir(parents=True, exist_ok=True)
catalog = []
for index, source in enumerate(files):
    with Image.open(source) as original:
        image = ImageOps.exif_transpose(original).convert("RGB")
        image.thumbnail((1920, 1920), Image.Resampling.LANCZOS)
        name = f"wallpaper_{index + 1:02d}"
        image.save(staging / f"{name}.jpg", quality=88)
        thumb = ImageOps.fit(image, (320, 180), method=Image.Resampling.LANCZOS)
        thumb.save(staging / f"{name}_thumb.jpg", quality=85)
    catalog.append({"id": name, "name": source.stem, "file": f"local/{name}.jpg",
                    "thumb": f"local/{name}_thumb.jpg"})
(staging / "catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n")
if output.exists():
    backup = project / ".wallpaper-backups" / ("previous-" + uuid.uuid4().hex)
    shutil.move(str(output), str(backup))
    print(f"Previous generated pack preserved at {backup}")
output.parent.mkdir(parents=True, exist_ok=True)
shutil.move(str(staging), str(output))
print(f"Prepared {len(catalog)} wallpapers in {output}. Originals unchanged.")
