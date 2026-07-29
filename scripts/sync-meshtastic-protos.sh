#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SUB="$ROOT/external/meshtastic-protobufs"
DEST="$ROOT/app/src/main/proto"
TAG="${1:-}"
cd "$SUB"
if [[ -n "$TAG" ]]; then
  git fetch --tags
  git checkout "$TAG"
fi
rm -rf "$DEST/meshtastic"
mkdir -p "$DEST/meshtastic"
cp -R "$SUB/meshtastic/"* "$DEST/meshtastic/"
cp "$SUB/nanopb.proto" "$DEST/nanopb.proto"
# Keep legacy Java package so existing Kotlin imports (com.geeksville.mesh) keep compiling.
find "$DEST/meshtastic" -name '*.proto' -print0 | xargs -0 sed -i '' 's/option java_package = "org.meshtastic.proto";/option java_package = "com.geeksville.mesh";/'
echo "Synced meshtastic protos from $(git -C "$SUB" describe --tags --always) into $DEST (java_package overridden to com.geeksville.mesh)"
