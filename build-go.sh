#!/usr/bin/env bash
# Builds the Go runtime sidecar for every supported platform.
# Outputs land in build/go-runtime/; the release jar embeds the binaries and
# extracts the right one at boot. Run from the repository root.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/build/go-runtime"
GO_BIN="${GO_BIN:-go}"

if ! command -v "$GO_BIN" >/dev/null 2>&1; then
    if [ -x "$ROOT/../go/bin/go" ]; then
        GO_BIN="$ROOT/../go/bin/go"
    else
        echo "Go toolchain not found; the proxy will run without the Go runtime" >&2
        echo "(install Go or set GO_BIN, then re-run this script)" >&2
        exit 0
    fi
fi

mkdir -p "$OUT"
cd "$ROOT/goruntime"

echo "== go vet"
"$GO_BIN" vet ./...

echo "== go test"
"$GO_BIN" test ./...

echo "== building binaries"
TARGETS=(
    "linux/amd64"
    "linux/arm64"
    "windows/amd64"
)
for target in "${TARGETS[@]}"; do
    os="${target%%/*}"
    arch="${target##*/}"
    name="waterbucket-go-runtime-${os}-${arch}"
    [ "$os" = "windows" ] && name="${name}.exe"
    echo "  -> $name"
    GOOS="$os" GOARCH="$arch" CGO_ENABLED=0 "$GO_BIN" build -trimpath \
        -ldflags="-s -w" -o "$OUT/$name" .
done

echo "== done: $OUT"
ls -la "$OUT"
