import zipfile
from pathlib import Path

root = Path(__file__).parent
source = root / "base.apk"
output = root / "build" / "xbypass-fix-v1.1-unsigned.apk"
hook_dex = root / "build" / "v1.1-dex" / "classes.dex"

with zipfile.ZipFile(source, "r") as original, zipfile.ZipFile(output, "w") as packaged:
    for entry in original.infolist():
        if entry.filename.startswith("META-INF/") or entry.filename == "assets/xposed_init":
            continue
        packaged.writestr(entry, original.read(entry.filename))
    packaged.writestr(
        "assets/xposed_init",
        "io.github.mara.xbypass.StrongBoxHook\n",
        compress_type=zipfile.ZIP_DEFLATED,
    )
    packaged.write(hook_dex, "classes5.dex", compress_type=zipfile.ZIP_STORED)
