#!/data/data/com.termux/files/usr/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-35
RESOURCE_SDK_DIR=/data/data/com.termux/files/home/.cache/android-api/android-9
# fallback: if android-9 missing, use android-35
if [ ! -f "$RESOURCE_SDK_DIR/android.jar" ]; then RESOURCE_SDK_DIR="$SDK_DIR"; fi
TOOLS_DIR=/data/data/com.termux/files/usr/bin
OUT="$PROJECT_DIR/build"
GEN="$OUT/gen"
CLASSES="$OUT/classes"
DEX="$OUT/dex"
RES_COMPILED="$OUT/res-compiled.zip"
RES_APK="$OUT/resources.apk"
KEYSTORE="$PROJECT_DIR/tab-s10fe-release.keystore"

rm -rf "$OUT"
mkdir -p "$GEN" "$CLASSES" "$DEX"

echo "=> Compilando recursos..."
"$TOOLS_DIR/aapt2" compile --dir "$PROJECT_DIR/res" -o "$RES_COMPILED"

echo "=> Linkando..."
"$TOOLS_DIR/aapt2" link \
    -I "$RESOURCE_SDK_DIR/android.jar" \
    --manifest "$PROJECT_DIR/AndroidManifest.xml" \
    --java "$GEN" \
    --min-sdk-version 24 \
    --target-sdk-version 35 \
    --version-code 1 \
    --version-name 1.0.0 \
    --auto-add-overlay \
    -o "$RES_APK" -R "$RES_COMPILED"

echo "=> Compilando Java..."
find "$PROJECT_DIR/src" "$GEN" -type f -name '*.java' -print > "$OUT/sources.list"
cat "$OUT/sources.list"
javac --release 8 -encoding UTF-8 \
    -classpath "$SDK_DIR/android.jar" \
    -d "$CLASSES" \
    @"$OUT/sources.list"

echo "=> Strip MethodParameters (fix D8)..."
python3 "$PROJECT_DIR/tools/strip_methodparams.py" "$CLASSES"

echo "=> Classes.jar..."
jar cf "$OUT/classes.jar" -C "$CLASSES" .

echo "=> D8..."
"$TOOLS_DIR/d8" --release --min-api 24 --lib "$SDK_DIR/android.jar" \
    --output "$DEX" "$OUT/classes.jar"

echo "=> Montando APK..."
cp "$RES_APK" "$OUT/unsigned.apk"
jar uf "$OUT/unsigned.apk" -C "$DEX" classes.dex
# assets
jar uf "$OUT/unsigned.apk" -C "$PROJECT_DIR" assets

echo "=> Zipalign..."
"$TOOLS_DIR/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/TabS10FE-Tracker-aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "=> Gerando keystore..."
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" \
        -storepass tabs10fe \
        -keypass tabs10fe \
        -alias tabs10fe \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Tab S10 FE Precos, OU=Local, O=TabPreco, L=Local, ST=Local, C=BR"
fi

echo "=> Assinando..."
"$TOOLS_DIR/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:tabs10fe \
    --key-pass pass:tabs10fe \
    --out "$OUT/TabS10FE-Tracker.apk" \
    "$OUT/TabS10FE-Tracker-aligned.apk"

echo "=> Verificando..."
"$TOOLS_DIR/apksigner" verify --verbose "$OUT/TabS10FE-Tracker.apk"

DOCUMENTS_DIR=/storage/emulated/0/Documents
DOWNLOADS_DIR=/storage/emulated/0/Download
mkdir -p "$DOCUMENTS_DIR" "$DOWNLOADS_DIR"
cp "$OUT/TabS10FE-Tracker.apk" "$DOCUMENTS_DIR/TabS10FE-Tracker.apk"
cp "$OUT/TabS10FE-Tracker.apk" "$DOWNLOADS_DIR/TabS10FE-Tracker.apk"
echo "APK criado: $OUT/TabS10FE-Tracker.apk"
echo "APK copiado para: $DOCUMENTS_DIR/TabS10FE-Tracker.apk"
echo "APK copiado para: $DOWNLOADS_DIR/TabS10FE-Tracker.apk"
ls -lh "$OUT/TabS10FE-Tracker.apk" "$DOCUMENTS_DIR/TabS10FE-Tracker.apk"
