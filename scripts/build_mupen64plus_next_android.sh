#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CORE_ROOT="${REPO_ROOT}/third_party/mupen64plus-libretro-nx"
FORCE_REBUILD="${NIN64_FORCE_CORE_REBUILD:-0}"
ABIS=(arm64-v8a x86_64)

if [[ ! -d "${CORE_ROOT}" ]]; then
    echo "Vendored core not found at ${CORE_ROOT}" >&2
    exit 1
fi

if [[ -n "${ANDROID_NDK_HOME:-}" && -x "${ANDROID_NDK_HOME}/ndk-build" ]]; then
    NDK_BUILD="${ANDROID_NDK_HOME}/ndk-build"
elif [[ -n "${ANDROID_NDK_ROOT:-}" && -x "${ANDROID_NDK_ROOT}/ndk-build" ]]; then
    NDK_BUILD="${ANDROID_NDK_ROOT}/ndk-build"
elif [[ -x "${HOME}/Android/Sdk/ndk/30.0.14904198/ndk-build" ]]; then
    NDK_BUILD="${HOME}/Android/Sdk/ndk/30.0.14904198/ndk-build"
else
    echo "Unable to locate ndk-build. Set ANDROID_NDK_HOME or ANDROID_NDK_ROOT." >&2
    exit 1
fi

echo "Using ndk-build at ${NDK_BUILD}"
echo "Building Mupen64Plus-Next for ${ABIS[*]} with GLES3 enabled..."

for ABI in "${ABIS[@]}"; do
    APP_JNI_LIBS_DIR="${REPO_ROOT}/app/src/main/jniLibs/${ABI}"
    OUTPUT_LIB="${APP_JNI_LIBS_DIR}/libmupen64plus_next_libretro.so"
    BUILT_LIB="${CORE_ROOT}/libretro/libs/${ABI}/libretro.so"

    if [[ -f "${OUTPUT_LIB}" && "${FORCE_REBUILD}" != "1" ]]; then
        echo "Core already built at ${OUTPUT_LIB}, skipping ${ABI} build."
        continue
    fi

    if [[ "${FORCE_REBUILD}" == "1" ]]; then
        echo "Cleaning existing Mupen64Plus-Next objects for ${ABI}..."
        "${NDK_BUILD}" \
            -C "${CORE_ROOT}/libretro/jni" \
            APP_ABI="${ABI}" \
            APP_PLATFORM=android-26 \
            APP_OPTIM=release \
            NDK_DEBUG=0 \
            GLES3=1 \
            clean
    fi

    "${NDK_BUILD}" \
        -C "${CORE_ROOT}/libretro/jni" \
        APP_ABI="${ABI}" \
        APP_PLATFORM=android-26 \
        APP_OPTIM=release \
        NDK_DEBUG=0 \
        GLES3=1 \
        -j"$(nproc)"

    if [[ ! -f "${BUILT_LIB}" ]]; then
        echo "Expected built core not found at ${BUILT_LIB}" >&2
        exit 1
    fi

    mkdir -p "${APP_JNI_LIBS_DIR}"
    cp "${BUILT_LIB}" "${OUTPUT_LIB}"
    echo "Copied packaged ${ABI} core to ${OUTPUT_LIB}"
done
