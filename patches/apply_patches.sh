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
      # Use same for round if no separate round provided
      if [ -f "patches/ic_launcher_round_$dens.b64" ]; then
        base64 -d "patches/ic_launcher_round_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || \
          cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png"
      else
        cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png"
      fi
    else
      echo "WARNING: invalid base64 for ic_launcher_$dens — skipped"
    fi
  fi
done

if [ -f "patches/splash.jpg.b64" ]; then
  if base64 -d "patches/splash.jpg.b64" > "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null; then
    echo "Splash image installed"
  else
    echo "WARNING: invalid splash base64 — skipped"
  fi
fi

echo "Patches applied successfully"
