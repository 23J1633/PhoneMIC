#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
TASK_ROOT="$PWD"
export PATH="$TASK_ROOT/.tools/node/bin:$TASK_ROOT/.tools/go/bin:$PATH"
export JAVA_HOME=/home/dong/.local/share/jdks/temurin-17
export ANDROID_HOME=/home/dong/Android/Sdk
export GOPROXY=https://goproxy.cn
mkdir -p artifacts .signing
npm ci --registry=https://registry.npmmirror.com --no-audit --no-fund --fetch-retries=2 --fetch-timeout=90000
npm run typecheck
npm test -- --runInBand
node scripts/fetch-speech.mjs
if [ ! -f .signing/release.keystore ]; then
  SIGNING_PASSWORD="$(openssl rand -hex 24)"
  printf 'storePassword=%s\nkeyPassword=%s\n' "$SIGNING_PASSWORD" "$SIGNING_PASSWORD" > .signing/key.properties
  chmod 600 .signing/key.properties
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore .signing/release.keystore -storepass "$SIGNING_PASSWORD" -keypass "$SIGNING_PASSWORD" -alias phonemic -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=PhoneMIC, OU=Development, O=PhoneMIC, C=CN' -noprompt
  chmod 600 .signing/release.keystore
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > android/local.properties
(cd android && chmod +x gradlew && ./gradlew :app:assembleRelease --init-script ../scripts/ovita-repositories.gradle --no-daemon --console=plain -PreactNativeArchitectures=arm64-v8a)
APP_VERSION="$(node -p "require('./package.json').version")"
APK_PATH="artifacts/PhoneMIC-$APP_VERSION-arm64.apk"
cp android/app/build/outputs/apk/release/app-release.apk "$APK_PATH"
(cd companion && go mod download && go test -race ./... && GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags='-s -w -H=windowsgui' -o ../artifacts/PhoneMIC-Desktop.exe .)
sha256sum "$APK_PATH" artifacts/PhoneMIC-Desktop.exe > artifacts/SHA256SUMS.txt
printf 'Build complete on %s\n' "$(hostname)"
