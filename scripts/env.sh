#!/usr/bin/env bash
# Keep every Gradle/JDK/SDK cache write inside the project so sandboxed builds
# with a read-only home still work.
set -euo pipefail
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT/.local/gradle-home}"
export XDG_DATA_HOME="${XDG_DATA_HOME:-$ROOT/.local/xdg-data}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$ROOT/.local/android-user}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
if [ -z "${JAVA_HOME:-}" ]; then
	for candidate in "$HOME/.jdks/temurin-17"* /usr/lib/jvm/java-17-openjdk*; do
		if [ -x "$candidate/bin/java" ]; then
			export JAVA_HOME="$candidate"
			break
		fi
	done
fi
mkdir -p "$GRADLE_USER_HOME" "$XDG_DATA_HOME" "$ANDROID_USER_HOME"
if [ -n "${JAVA_HOME:-}" ]; then
	export PATH="$JAVA_HOME/bin:$PATH"
fi
export PATH="$ANDROID_HOME/platform-tools:$PATH"
echo "JAVA_HOME=$JAVA_HOME"
echo "ANDROID_HOME=$ANDROID_HOME"
echo "GRADLE_USER_HOME=$GRADLE_USER_HOME"
