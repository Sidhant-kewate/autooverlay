# AutoTap Overlay
Floating ON/OFF button over any app. When ON, it watches the screen and taps the spot that matches your saved button image.

## Build the APK
Option A (no install): push this folder to a GitHub repo -> Actions tab -> "Build APK" -> download artifact `app-debug.apk`.
Option B: open in Android Studio -> Build > Build APK(s).

## Use
1. Open app, do steps 1-4 in order. 2 = turn on "AutoTap Overlay" under accessibility.
2. Step 3: pick a small cropped screenshot of ONLY the button you want tapped.
3. Step 4 starts the overlay. Tap the floating button to turn ON/OFF; drag to move it.

Tuning (OverlayService.kt): THRESHOLD (lower = stricter match), INTERVAL_MS, COOLDOWN_MS.

## Automatic build (no Android Studio needed)
- Windows:  `powershell -ExecutionPolicy Bypass -File build.ps1`
- Linux / macOS / WSL:  `./build.sh`
Output: `AutoTapOverlay.apk` in this folder.

## Easiest: give it your repo link (GitHub builds it for you)
- Windows:  `powershell -ExecutionPolicy Bypass -File get-apk.ps1 https://github.com/YOU/REPO`
- Linux / macOS / WSL:  `./get-apk.sh https://github.com/YOU/REPO`
Use a NEW EMPTY repo. Install GitHub CLI (`gh auth login`) for a fully automatic download.
