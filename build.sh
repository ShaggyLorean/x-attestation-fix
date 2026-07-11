#!/bin/bash
set -e

ANDROID_JAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
BUILD_TOOLS="${BUILD_TOOLS:-$ANDROID_HOME/build-tools/34.0.0}"
KEYSTORE="${KEYSTORE:-$HOME/.android/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"

mkdir -p build/classes build/dex out

echo "[1/5] Compiling Java sources..."
files=$(find src stubs -name '*.java')
javac -cp "$ANDROID_JAR" -d build/classes $files

echo "[2/5] Converting to DEX..."
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --output build/dex \
    $(find build/classes -name '*.class')

echo "[3/5] Assembling APK..."
python3 build_apk.py

echo "[4/5] Zipalign..."
"$BUILD_TOOLS/zipalign" -f 4 out/xbypass-fix-unsigned.apk out/xbypass-fix-aligned.apk

echo "[5/5] Signing..."
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KS_ALIAS" \
    --ks-pass "pass:$KS_PASS" \
    --key-pass "pass:$KS_PASS" \
    --out out/xbypass-fix-v1.0.apk \
    out/xbypass-fix-aligned.apk

echo "Done: out/xbypass-fix-v1.0.apk"
ls -la out/xbypass-fix-v1.0.apk