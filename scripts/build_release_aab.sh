#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEYSTORE_PROPERTIES="${ROOT_DIR}/keystore.properties"
AAB_PATH="${ROOT_DIR}/app/build/outputs/bundle/release/app-release.aab"

if [[ ! -f "${KEYSTORE_PROPERTIES}" ]]; then
    echo "Missing ${KEYSTORE_PROPERTIES}" >&2
    exit 1
fi

STORE_FILE="$(
    awk -F= '
        $1 == "storeFile" {
            value = substr($0, index($0, "=") + 1)
            gsub(/^[ \t]+|[ \t]+$/, "", value)
            print value
        }
    ' "${KEYSTORE_PROPERTIES}"
)"

if [[ -z "${STORE_FILE}" ]]; then
    echo "Missing storeFile in ${KEYSTORE_PROPERTIES}" >&2
    exit 1
fi

if [[ "${STORE_FILE}" != /* ]]; then
    STORE_FILE="${ROOT_DIR}/${STORE_FILE}"
fi

if [[ ! -f "${STORE_FILE}" ]]; then
    echo "Keystore does not exist: ${STORE_FILE}" >&2
    exit 1
fi

cd "${ROOT_DIR}"
./gradlew :app:bundleRelease

if [[ ! -f "${AAB_PATH}" ]]; then
    echo "Expected AAB was not created: ${AAB_PATH}" >&2
    exit 1
fi

echo "Signed release AAB: ${AAB_PATH}"
