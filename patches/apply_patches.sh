#!/bin/bash
set -e
ROOT="android-wrapper"
echo "Applying EPI app fixes..."

cp patches/MainActivity.kt "$ROOT/app/src/main/java/org/zm/calculator/MainActivity.kt"
cp patches/WebAppInterface.kt "$ROOT/app/src/main/java/org/zm/calculator/WebAppInterface.kt"
cp patches/AndroidManifest.xml "$ROOT/app/src/main/AndroidManifest.xml"

# App name: EPI CALCULATOR
mkdir -p "$ROOT/app/src/main/res/values"
if [ -f patches/strings.xml ]; then
  cp patches/strings.xml "$ROOT/app/src/main/res/values/strings.xml"
  echo "App name set to EPI CALCULATOR"
else
  cat > "$ROOT/app/src/main/res/values/strings.xml" << 'STR'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">EPI CALCULATOR</string>
    <string name="loc_channel_name">Live location</string>
    <string name="loc_notification_title">لوکیشن چل رہی ہے</string>
    <string name="loc_notification_text">ایپ بند ہونے پر بھی لوکیشن سرور پر جاتی رہتی ہے</string>
</resources>
STR
  echo "App name set to EPI CALCULATOR (default)"
fi

mkdir -p "$ROOT/app/src/main/assets"
mkdir -p "$ROOT/app/src/main/res/xml"

if [ -f patches/file_paths.xml ]; then
  cp patches/file_paths.xml "$ROOT/app/src/main/res/xml/file_paths.xml"
  echo "Installed file_paths.xml"
else
  cat > "$ROOT/app/src/main/res/xml/file_paths.xml" << 'XML'
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="cache" path="." />
    <external-cache-path name="extcache" path="." />
    <external-path name="external" path="." />
    <files-path name="files" path="." />
</paths>
XML
  echo "Wrote default file_paths.xml"
fi

BEST_ICON=""
for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "$ROOT/app/src/main/res/mipmap-$dens"
  INSTALLED=0
  if [ -f "patches/ic_launcher_$dens.b64" ]; then
    if base64 -d "patches/ic_launcher_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" 2>/dev/null; then
      SZ=$(wc -c < "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png")
      if [ "$SZ" -gt 500 ]; then
        echo "Installed ic_launcher for $dens ($SZ bytes)"
        BEST_ICON="$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
        INSTALLED=1
      else
        rm -f "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
      fi
    fi
  fi
  if [ "$INSTALLED" -eq 0 ] && [ -n "$BEST_ICON" ] && [ -f "$BEST_ICON" ]; then
    cp "$BEST_ICON" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png"
    echo "Fallback icon → $dens"
  fi
  if [ -f "patches/ic_launcher_round_$dens.b64" ]; then
    if base64 -d "patches/ic_launcher_round_$dens.b64" > "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null; then
      SZ=$(wc -c < "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png")
      if [ "$SZ" -le 500 ]; then
        cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
      fi
    else
      cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
    fi
  else
    cp "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher.png" "$ROOT/app/src/main/res/mipmap-$dens/ic_launcher_round.png" 2>/dev/null || true
  fi
done

if [ -f "patches/splash.jpg.b64" ]; then
  if base64 -d "patches/splash.jpg.b64" > "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null; then
    SZ=$(wc -c < "$ROOT/app/src/main/assets/splash.jpg")
    if [ "$SZ" -gt 5000 ]; then
      echo "Splash image installed ($SZ bytes)"
    else
      echo "WARNING: splash too small"
      rm -f "$ROOT/app/src/main/assets/splash.jpg"
    fi
  else
    echo "WARNING: invalid splash base64"
  fi
elif [ -f "patches/splash.jpg" ]; then
  cp patches/splash.jpg "$ROOT/app/src/main/assets/splash.jpg"
  echo "Splash copied from patches/splash.jpg"
else
  echo "NOTE: no splash — solid blue fallback will be used"
fi

echo "Patches applied successfully"
ls -la "$ROOT/app/src/main/res/mipmap-"*/*.png 2>/dev/null | head -20 || true
ls -la "$ROOT/app/src/main/assets/splash.jpg" 2>/dev/null || echo "(no splash)"
ls -la "$ROOT/app/src/main/res/xml/file_paths.xml" 2>/dev/null || true
cat "$ROOT/app/src/main/res/values/strings.xml" 2>/dev/null || true
