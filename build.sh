#!/usr/bin/env bash
# MiniVault 一键编译脚本（Windows + MSYS bash）
#
# 为什么这么绕：这台机器的 DSH 沙箱只允许子进程写工作区内路径，
# 所以 JAVA_HOME / GRADLE_USER_HOME / ANDROID_USER_HOME / TMPDIR 全部落到工作区；
# 又因为 Gradle 的 jansi/native 库加载走 ANSI 转换，撑不住中文路径，
# 所以这些「缓存类」目录放在 ASCII 路径 _gbt 下，工程本身留在原处。
set -euo pipefail

ROOT="$(cygpath -m "$(cd "$(dirname "$0")" && pwd)")"   # 形如 E:/AI-agent/DSH-test/工作/vault-mini
BASE="E:/AI-agent/DSH-test/_gbt"                  # ASCII 缓存根
TOOLS="$BASE/tools"

export JAVA_HOME="$TOOLS/jdk-21.0.13+9"
export ANDROID_HOME="$BASE/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_USER_HOME="$BASE/android-home"
export GRADLE_USER_HOME="$BASE/gradle-home"
export TMPDIR="$BASE/tmp"
export TEMP="$BASE/tmp"
export TMP="$BASE/tmp"
mkdir -p "$GRADLE_USER_HOME" "$TMPDIR" "$ANDROID_USER_HOME"

GRADLE_LAUNCHER="$TOOLS/gradle-8.13/lib/gradle-launcher-8.13.jar"

if [ "$#" -gt 0 ]; then tasks=("$@"); else tasks=(assembleDebug); fi

"$JAVA_HOME/bin/java" -classpath "$GRADLE_LAUNCHER" org.gradle.launcher.GradleMain \
  -p "$ROOT" "${tasks[@]}" --no-daemon --console=plain

for t in "${tasks[@]}"; do
  if [ "$t" = "assembleDebug" ]; then
    VERSION="$(grep -oE 'versionName *= *"[^"]+"' "$ROOT/app/build.gradle.kts" | head -1 | sed -E 's/.*"([^"]+)"/\1/')"
    mkdir -p "$ROOT/dist"
    cp "$ROOT/app/build/outputs/apk/debug/app-debug.apk" "$ROOT/dist/D-pwm.apk"
    echo "APK -> $ROOT/dist/D-pwm.apk (版本名见 app/build.gradle.kts)"
  fi
done
