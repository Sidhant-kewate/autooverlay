#!/usr/bin/env bash
# Usage: ./get-apk.sh https://github.com/YOU/REPO
# Pushes this project to your repo, lets GitHub Actions build it, downloads AutoTapOverlay.apk here.
# Needs: git (logged in to GitHub) and ideally GitHub CLI `gh` (https://cli.github.com, run `gh auth login` once).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="${1:-}"
[ -z "$REPO" ] && read -rp "GitHub repo link: " REPO
REPO="${REPO%/}"; REPO="${REPO%.git}"
command -v git >/dev/null || { echo "Install git first"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo ">> Cloning $REPO ..."
git clone "$REPO.git" "$TMP/repo"
cd "$TMP/repo"

if [ -n "$(git ls-files | head -1)" ]; then
  echo "!! This repo already has files in it. The project will be mixed into its root."
  read -rp "Continue anyway? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || { echo "Cancelled. Use an empty repo."; exit 1; }
fi

echo ">> Copying project in ..."
(cd "$HERE" && tar --exclude=.git --exclude=local.properties --exclude=build --exclude=.gradle \
  --exclude='*.apk' -cf - .) | tar xf -

git add -A
git commit -m "Add AutoTap Overlay" || git commit --allow-empty -m "Rebuild APK"
echo ">> Pushing ..."
git push -u origin HEAD

if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  echo ">> Waiting for GitHub to build (about 3-5 minutes) ..."
  sleep 10
  RUN=$(gh run list --workflow "Build APK" --limit 1 --json databaseId -q '.[0].databaseId')
  gh run watch "$RUN" --exit-status
  gh run download "$RUN" -n AutoTapOverlay-debug-apk -D "$TMP/out"
  cp "$TMP/out/app-debug.apk" "$HERE/AutoTapOverlay.apk"
  echo ""; echo "DONE -> $HERE/AutoTapOverlay.apk"
else
  echo ""
  echo "Pushed. 'gh' is not installed/logged in, so finish in the browser:"
  echo "  1. Open $REPO/actions"
  echo "  2. Click the newest 'Build APK' run, wait for the green tick"
  echo "  3. Download 'AutoTapOverlay-debug-apk' under Artifacts and unzip it"
fi
