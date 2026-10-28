#!/bin/bash
set -e
ROOT="android-wrapper"
echo "Applying EPI app fixes..."

cp patches/MainActivity.kt "$ROOT/app/src/main/java/org/zm/calculator/MainActivity.kt"
cp patches/AndroidManifest.xml "$ROOT/app/src/main/AndroidManifest.xml"

mkdir -p "$ROOT/app/src/main/assets"

# Install launcher icons for all densities, falling back to nearest good one
BEST_ICON=""
for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$ROOT/app/src/main/res/mipmap-$dens"
  INSTALLED=0
  if [ -f "patches/ic_launcher_$dens.b64" ]; then
    if base64 -d "patches/ic_launcher_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" 2>/dev/null; then
      # Verify it is a real PNG (min size)
      SZ=$(wc -c < "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png")
      if [ "$SZ" -gt 500 ]; then
        echo "Installed ic_launcher for $dens ($SZ bytes)"
        BEST_ICON="$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
        INSTALLED=1
      else
        echo "WARNING: ic_launcher_$dens too small/invalid ($SZ bytes) — will fallback"
        rm -f "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
      fi
    fi
  fi
  if [ "$INSTALLED" -eq 0 ]; then
    if [ -n "$BEST_ICON" ] && [ -f "$BEST_ICON" ]; then
      cp "$BEST_ICON" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
      echo "Fallback: copied best available icon to $dens"
    else
      echo "WARNING: no icon available for $dens"
    fi
  fi

  # Round icon
  if [ -f "patches/ic_launcher_round_$dens.b64" ]; then
    if base64 -d "patches/ic_launcher_round_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null; then
      SZ=$(wc -c < "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png")
      if [ "$SZ" -gt 500 ]; then
        echo "Installed ic_launcher_round for $dens"
      else
        cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
      fi
    else
      cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
    fi
  else
    cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
  fi
done

# Splash
if [ -f "patches/splash.jpg.b64" ]; then
  if base64 -d "patches/splash.jpg.b64" > "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null; then
    SZ=$(wc -c < "$ROOT/app/src/main/assets/splash.jpg")
    if [ "$SZ" -gt 1000 ]; then
      echo "Splash image installed ($SZ bytes)"
    else
      echo "WARNING: splash too small — skipped"
      rm -f "$ROOT/app/src/main/assets/splash.jpg"
    fi
  else
    echo "WARNING: invalid splash base64 — skipped"
  fi
else
  echo "NOTE: no splash.jpg.b64 — MainActivity will use solid blue fallback"
fi

echo "Patches applied successfully"
echo "--- Installed icons ---"
ls -la "$ROOT/app/src/main/res/mipmap-"*/*.png 2>/dev/null || true
ls -la "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null || echo "(no splash asset)"
