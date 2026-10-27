#!/bin/bash
set -e
ROOT="android-wrapper"
echo "Applying EPI app fixes..."

cp patches/MainActivity.kt "$ROOT/app/src/main/java/org/zm/calculator/MainActivity.kt"
cp patches/AndroidManifest.xml "$ROOT/app/src/main/AndroidManifest.xml"

mkdir -p "$ROOT/app/src/main/assets"

for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$ROOT/app/src/main/res/mipmap-$dens"
  if [ -f "patches/ic_launcher_$dens.b64" ]; then
    if base64 -d "patches/ic_launcher_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" 2>/dev/null; then
      echo "Installed ic_launcher for $dens"
      if [ -f "patches/ic_launcher_round_$dens.b64" ]; then
        base64 -d "patches/ic_launcher_round_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || \
          cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png"
      else
        cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png"
      fi
    else
      echo "WARNING: invalid base64 for ic_launcher_$dens — skipped"
    fi
  else
    echo "WARNING: missing patches/ic_launcher_$dens.b64"
  fi
done

if [ -f "patches/splash.jpg.b64" ]; then
  if base64 -d "patches/splash.jpg.b64" > "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null; then
    echo "Splash image installed ($(wc -c < "$ROOT/app/src/main/assets/splash.jpg") bytes)"
  else
    echo "WARNING: invalid splash base64 — skipped"
  fi
else
  echo "WARNING: missing patches/splash.jpg.b64"
fi

echo "Patches applied successfully"
ls -la "$ROOT/app/src/main/res/mipmap-"*/*.png 2>/dev/null || true
ls -la "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null || true
