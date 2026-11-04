#!/usr/bin/env bash
# يجلب proot وكل اعتمادياته الأصلية (aarch64) مع التحقق من SHA256.
set -euo pipefail

BASE="https://packages.termux.dev/apt/termux-main"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$DEST"

fetch_deb() {
    local name="$1" url="$2" sha="$3"
    echo "⬇️  $name"
    curl -fsSL --retry 3 -o "$TMP/$name.deb" "$BASE/$url"
    echo "$sha  $TMP/$name.deb" | sha256sum -c -
    mkdir -p "$TMP/$name"
    cd "$TMP/$name"
    ar x "$TMP/$name.deb"
    if [[ -f data.tar.xz ]]; then
        tar -xJf data.tar.xz
    elif [[ -f data.tar.zst ]]; then
        tar --zstd -xf data.tar.zst
    elif [[ -f data.tar.gz ]]; then
        tar -xzf data.tar.gz
    else
        echo "❌ أرشيف غير معروف في $name" >&2
        exit 1
    fi
}

fetch_deb proot \
    "pool/main/p/proot/proot_5.1.107.102_aarch64.deb" \
    "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"
fetch_deb libtalloc \
    "pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb" \
    "556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7"
fetch_deb libandroid-shmem \
    "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb" \
    "0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6"

PREFIX="data/data/com.termux/files/usr"
PROOT_BIN="$TMP/proot/$PREFIX/bin/proot"
LOADER_BIN="$TMP/proot/$PREFIX/libexec/proot/loader"
TALLOC_BIN="$TMP/libtalloc/$PREFIX/lib/libtalloc.so.2"
SHMEM_BIN="$TMP/libandroid-shmem/$PREFIX/lib/libandroid-shmem.so"
for file in "$PROOT_BIN" "$LOADER_BIN" "$TALLOC_BIN" "$SHMEM_BIN"; do
    [[ -f "$file" ]] || { echo "❌ ملف أصلي مفقود: $file" >&2; exit 1; }
done

cp "$PROOT_BIN" "$DEST/libproot.so"
cp "$LOADER_BIN" "$DEST/libproot-loader.so"
cp "$TALLOC_BIN" "$DEST/libtalloc.so"
cp "$SHMEM_BIN" "$DEST/libandroid-shmem.so"

# Android يضمّن lib*.so فقط، لا lib*.so.2. غيّر NEEDED دون تغيير حجم ELF.
python3 - "$DEST/libproot.so" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1])
data = p.read_bytes()
old = b"libtalloc.so.2\0"
new = b"libtalloc.so\0\0\0"
assert len(old) == len(new)
assert data.count(old) == 1, "libtalloc NEEDED entry not found exactly once"
p.write_bytes(data.replace(old, new, 1))
PY
chmod 755 "$DEST"/*.so

echo "🔎 فحص الاعتماديات المضمّنة:"
readelf -d "$DEST/libproot.so" | grep NEEDED
readelf -d "$DEST/libproot.so" | grep -q 'Shared library: \[libtalloc.so\]'
readelf -d "$DEST/libproot.so" | grep -q 'Shared library: \[libandroid-shmem.so\]'
! readelf -d "$DEST/libproot.so" | grep -q 'libtalloc.so.2'
for file in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
    test -s "$DEST/$file"
done

echo "✅ proot واعتمادياته جاهزة في $DEST"
ls -lh "$DEST"
