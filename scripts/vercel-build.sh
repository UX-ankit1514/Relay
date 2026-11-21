#!/usr/bin/env bash
# Builds the Relay Kotlin/JS web app on Vercel.
# Vercel's build image has Node but no Java, so a JDK is downloaded first.
# Firebase web config comes from Vercel environment variables (RELAY_*), never from git.
set -euo pipefail

missing=()
for v in RELAY_FIREBASE_API_KEY RELAY_FIREBASE_AUTH_DOMAIN RELAY_FIREBASE_PROJECT_ID \
         RELAY_FIREBASE_STORAGE_BUCKET RELAY_FIREBASE_MESSAGING_SENDER_ID RELAY_FIREBASE_APP_ID; do
  [ -n "${!v:-}" ] || missing+=("$v")
done
if [ ${#missing[@]} -gt 0 ] && [ ! -f local.properties ]; then
  echo "Missing Vercel environment variables: ${missing[*]}" >&2
  echo "Add them in Vercel → Project → Settings → Environment Variables, then redeploy." >&2
  exit 1
fi

if ! command -v java >/dev/null 2>&1; then
  case "$(uname -m)" in aarch64|arm64) arch=aarch64 ;; *) arch=x64 ;; esac
  echo "Installing Temurin JDK 21 ($arch)…"
  mkdir -p "$HOME/.jdk"
  curl -fsSL "https://api.adoptium.net/v3/binary/latest/21/ga/linux/$arch/jdk/hotspot/normal/eclipse" \
    | tar -xz -C "$HOME/.jdk" --strip-components=1
  export JAVA_HOME="$HOME/.jdk"
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java -version

chmod +x ./gradlew
./gradlew :composeApp:jsBrowserDistribution --no-daemon --console=plain \
  -Pkotlin.compiler.execution.strategy=in-process

ls -la composeApp/build/dist/js/productionExecutable
