#!/usr/bin/env bash
# 一键构建：Rust 交叉编译 → 拷进 jniLibs → 打包 Debug APK
#
# 用法：./scripts/build.sh [abi...]
#   默认 arm64-v8a x86_64
#
# 注意：Rust 交叉编译在受限沙箱里会被卡住（clang 子进程无法启动），
# 必须在正常权限下执行。
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

NDK="${ANDROID_NDK_HOME:-D:/dev/android-sdk/ndk/27.0.12077973}"
BIN="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin"
RUST_DIR="$ROOT/rust/kugou_server"
JNI_DIR="$ROOT/app/src/main/jniLibs"

export JAVA_HOME="${JAVA_HOME:-D:/dev/jdk17}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-D:/dev/gradle-home}"

ABIS=("$@")
if [ ${#ABIS[@]} -eq 0 ]; then ABIS=(arm64-v8a x86_64); fi

for abi in "${ABIS[@]}"; do
  case "$abi" in
    arm64-v8a)  target=aarch64-linux-android;   clang=aarch64-linux-android21-clang ;;
    armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi21-clang ;;
    x86)        target=i686-linux-android;      clang=i686-linux-android21-clang ;;
    x86_64)     target=x86_64-linux-android;    clang=x86_64-linux-android21-clang ;;
    *) echo "未知 ABI: $abi" >&2; exit 1 ;;
  esac

  ccvar="CC_${target//-/_}"
  arvar="AR_${target//-/_}"
  lc="${target//-/_}"
  linkvar="CARGO_TARGET_${lc^^}_LINKER"

  echo "==> Rust $target"
  ( cd "$RUST_DIR" && env "$ccvar"="$BIN/$clang.cmd" "$arvar"="$BIN/llvm-ar.exe" \
      "$linkvar"="$BIN/$clang.cmd" cargo build --target "$target" --release )

  mkdir -p "$JNI_DIR/$abi"
  cp "$RUST_DIR/target/$target/release/libkugou_server.so" "$JNI_DIR/$abi/"
  echo "    -> $abi ($(stat -c%s "$JNI_DIR/$abi/libkugou_server.so") bytes)"
done

echo "==> Gradle assembleDebug"
"$ROOT/gradlew" assembleDebug

echo
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
