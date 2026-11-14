#!/usr/bin/env bash
# Builds the pinned cloudflared source with the Pocket Share DNS patch and installs the
# resulting executables as JNI libraries so Android extracts them into nativeLibraryDir.
#
# Why a patched build: Android has no /etc/resolv.conf. Go's resolver therefore falls back to
# [::1]:53, which is normally closed, so cloudflared cannot resolve SRV records, the quick-tunnel
# API, or the feature selector even though Android's own resolver works. The patch reads
# TUNNEL_DNS_SERVERS (filled by the app) and points Go's resolver at a loopback forwarder.
set -euo pipefail

VERSION="2026.10.7"
PATCH_VERSION="${PATCH_VERSION:-2026.10.7-pocketshare.1}"
GO_VERSION="1.27.3"
GO_SHA256_AMD64="65efbc80fdc1fa2cd2631cad5b481777a68e8725f34e89f50fc6ac714e379b40"
SOURCE_SHA256="d268031cb8906675b6b6353f18a007e6fc028f170094c5ac9220493bc8147016"
BUILD_TIME="${BUILD_TIME:-2026-11-14}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BUILD_DIR="${BUILD_DIR:-${ROOT}/.build/cloudflared}"
DOWNLOAD_DIR="${BUILD_DIR}/downloads"
SRC_DIR="${BUILD_DIR}/src/cloudflared-${VERSION}"
GO_DIR="${BUILD_DIR}/go${GO_VERSION}"
GO_BIN="${GO_DIR}/go/bin/go"

case "$(uname -m)" in
  x86_64 | amd64) GO_ARCH="amd64" ;;
  *)
    echo "Unsupported build host: $(uname -m). The pinned Go checksum is for linux-amd64." >&2
    exit 1
    ;;
esac

mkdir -p "${DOWNLOAD_DIR}" "${BUILD_DIR}/src" "${BUILD_DIR}/gomodcache" "${BUILD_DIR}/gocache"

download() {
  local url="$1" destination="$2" expected="$3"
  if [[ -f "${destination}" ]] && echo "${expected}  ${destination}" | sha256sum --check --status; then
    return
  fi
  echo "Downloading ${url}…"
  curl --fail --location --retry 3 --retry-delay 2 --output "${destination}.tmp" "${url}"
  echo "${expected}  ${destination}.tmp" | sha256sum --check
  mv "${destination}.tmp" "${destination}"
}

if [[ ! -x "${GO_BIN}" ]]; then
  GO_ARCHIVE="go${GO_VERSION}.linux-${GO_ARCH}.tar.gz"
  download "https://go.dev/dl/${GO_ARCHIVE}" "${DOWNLOAD_DIR}/${GO_ARCHIVE}" "${GO_SHA256_AMD64}"
  rm -rf "${GO_DIR}"
  mkdir -p "${GO_DIR}"
  tar -C "${GO_DIR}" -xzf "${DOWNLOAD_DIR}/${GO_ARCHIVE}"
fi

if [[ ! -f "${SRC_DIR}/go.mod" ]]; then
  SOURCE_ARCHIVE="cloudflared-${VERSION}.tar.gz"
  download \
    "https://github.com/cloudflare/cloudflared/archive/refs/tags/${VERSION}.tar.gz" \
    "${DOWNLOAD_DIR}/${SOURCE_ARCHIVE}" \
    "${SOURCE_SHA256}"
  rm -rf "${SRC_DIR}"
  mkdir -p "${BUILD_DIR}/src"
  tar -C "${BUILD_DIR}/src" -xzf "${DOWNLOAD_DIR}/${SOURCE_ARCHIVE}"
fi

if ! grep -q "configureCustomDNS" "${SRC_DIR}/cmd/cloudflared/main.go"; then
  echo "Applying cloudflared-dns.patch…"
  patch -d "${SRC_DIR}" -p1 --forward < "${ROOT}/scripts/cloudflared-dns.patch"
fi

build() {
  local goarch="$1" output="$2"
  echo "Building cloudflared ${PATCH_VERSION} for linux/${goarch}…"
  (
    cd "${SRC_DIR}"
    env \
      CGO_ENABLED=0 \
      GOOS=linux \
      GOARCH="${goarch}" \
      GOMODCACHE="${BUILD_DIR}/gomodcache" \
      GOCACHE="${BUILD_DIR}/gocache" \
      "${GO_BIN}" build \
      -buildvcs=false \
      -trimpath \
      -ldflags="-s -w -X main.Version=${PATCH_VERSION} -X main.BuildTime=${BUILD_TIME}" \
      -o "${output}" \
      ./cmd/cloudflared
  )
}

build arm64 "${BUILD_DIR}/cloudflared-linux-arm64"
build amd64 "${BUILD_DIR}/cloudflared-linux-amd64"

install -m 0755 "${BUILD_DIR}/cloudflared-linux-arm64" "${ROOT}/app/src/main/jniLibs/arm64-v8a/libcloudflared.so"
install -m 0755 "${BUILD_DIR}/cloudflared-linux-amd64" "${ROOT}/app/src/main/jniLibs/x86_64/libcloudflared.so"

echo
echo "Installed patched cloudflared ${PATCH_VERSION}:"
sha256sum \
  "${ROOT}/app/src/main/jniLibs/arm64-v8a/libcloudflared.so" \
  "${ROOT}/app/src/main/jniLibs/x86_64/libcloudflared.so"
