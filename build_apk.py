#!/usr/bin/env python3
import zipfile
from pathlib import Path

BASE_APK = Path(__file__).parent / "base.apk"
DEX_OUT = Path(__file__).parent / "build" / "dex" / "classes.dex"
OUT = Path(__file__).parent / "out" / "xbypass-fix-unsigned.apk"

if not BASE_APK.exists():
    print("base.apk not found. This is the original module APK.")
    print("Place it at the project root before building.")
    raise SystemExit(1)

if not DEX_OUT.exists():
    print("classes.dex not found in build/dex/. Run build.sh first.")
    raise SystemExit(1)

with zipfile.ZipFile(BASE_APK, "r") as src, \
     zipfile.ZipFile(OUT, "w", zipfile.ZIP_STORED) as out:
    for entry in src.infolist():
        if entry.filename.startswith("META-INF/"):
            continue
        if entry.filename == "assets/xposed_init":
            continue
        out.writestr(entry, src.read(entry.filename))
    out.writestr("assets/xposed_init",
                 "io.github.mara.xbypass.MainHook\n",
                 compress_type=zipfile.ZIP_DEFLATED)
    with open(DEX_OUT, "rb") as f:
        out.writestr("classes5.dex", f.read(),
                     compress_type=zipfile.ZIP_STORED)

print(f"assembled {OUT}")