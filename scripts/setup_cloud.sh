#!/usr/bin/env bash
set -euo pipefail

cd /workspace/Alter-AI-Windows-
mkdir -p /workspace/setup/downloads /workspace/toolchains /workspace/reference /workspace/.gradle

JDK_VERSION='21.0.13_9'
JDK_SHA='d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c'
JDK_URL="https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_${JDK_VERSION}.tar.gz"
JDK_ARCHIVE=/workspace/setup/downloads/jdk.tar.gz
if [ ! -x /workspace/toolchains/jdk-21.0.13+9/bin/javac ]; then
  curl --fail --location --silent --show-error "$JDK_URL" -o "$JDK_ARCHIVE"
  printf '%s  %s\n' "$JDK_SHA" "$JDK_ARCHIVE" | sha256sum --check
  tar -xzf "$JDK_ARCHIVE" -C /workspace/toolchains
fi

# Use the system CA set, plus the managed proxy CA. Keep Java TLS verification on.
cp /etc/ssl/certs/java/cacerts /workspace/setup/java-cacerts
/workspace/toolchains/jdk-21.0.13+9/bin/keytool -importcert -noprompt \
  -alias alter-environment-proxy -file /usr/local/share/ca-certificates/environment-proxy-ca.crt \
  -keystore /workspace/setup/java-cacerts -storepass changeit >/dev/null 2>&1 || \
  /workspace/toolchains/jdk-21.0.13+9/bin/keytool -list -alias alter-environment-proxy \
    -keystore /workspace/setup/java-cacerts -storepass changeit >/dev/null

if [ ! -d /workspace/reference/ALTER-AI-code-base/.git ]; then
  git clone --depth 1 --single-branch --branch main \
    https://github.com/hardikk-gupta/ALTER-AI-code-base.git /workspace/reference/ALTER-AI-code-base
fi

python3 scripts/setup_fonts.py
source /workspace/setup/activate.sh
./gradlew test --console=plain
git status --short --untracked-files=all
