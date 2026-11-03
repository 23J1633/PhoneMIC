#!/usr/bin/env bash
set -euo pipefail
TASK_ROOT="${1:-/home/dong/projects/phonemic}"
mkdir -p "$TASK_ROOT/.tools" "$TASK_ROOT/.cache" "$TASK_ROOT/artifacts"
cd "$TASK_ROOT"
if [ ! -x .tools/node/bin/node ]; then
  curl --fail --location --retry 3 https://nodejs.org/dist/v22.21.1/node-v22.21.1-linux-x64.tar.xz -o .cache/node.tar.xz
  mkdir -p .tools/node
  tar -xJf .cache/node.tar.xz --strip-components=1 -C .tools/node
fi
if [ ! -x .tools/go/bin/go ]; then
  curl --fail --location --retry 3 --connect-timeout 20 https://dl.google.com/go/go1.25.4.linux-amd64.tar.gz -o .cache/go.tar.gz
  tar -xzf .cache/go.tar.gz -C .tools
fi
export PATH="$TASK_ROOT/.tools/node/bin:$TASK_ROOT/.tools/go/bin:$PATH"
export JAVA_HOME=/home/dong/.local/share/jdks/temurin-17
export ANDROID_HOME=/home/dong/Android/Sdk
node --version
go version
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" 'platforms;android-36' 'build-tools;36.0.0' 'ndk;28.2.13676358' 'cmake;3.22.1'
