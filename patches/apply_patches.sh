#!/bin/bash
set -e
ROOT="android-wrapper"
echo "Applying EPI app fixes..."

cp patches/MainActivity.kt "$ROOT/app/src/main/java/org/zm/calculator/MainActivity.kt"
cp patches/AndroidManifest.xml "$ROOT/app/src/main/AndroidManifest.xml"

for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$ROOT/app/src/main/res/mipmap-$dens"
  if [ -f "patches/ic_launcher_$dens.b64" ]; then
    base64 -d "patches/ic_launcher_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
  fi
  if [ -f "patches/ic_launcher_round_$dens.b64" ]; then
    base64 -d "patches/ic_launcher_round_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png"
  fi
done

if [ -f "patches/splash.jpg.b64" ]; then
  base64 -d "patches/splash.jpg.b64" > "$ROOT/app/src/main/assets/splash.jpg"
  echo "Splash image installed"
fi

echo "Patches applied successfully"
