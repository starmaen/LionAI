#!/usr/bin/env bash
# يجلب proot (aarch64) حديث من termux-packages متوافق مع Alpine 3.24+ (apk-tools 3.x)
# يُستخرج من حزمة .deb الرسمية لـ termux ويوضع كمكتبات native في jniLibs
set -euo pipefail

# إصدار proot من termux (حدّث هذا الرقم عند توفر إصدار أحدث)
PROOT_VERSION="5.1.107.102"
PROOT_DEB_URL="https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_${PROOT_VERSION}_aarch64.deb"
# SHA256 للحزمة (حدّثه عند تغيير الإصدار)
PROOT_DEB_SHA256="10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030"

DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "⬇️  تحميل proot ${PROOT_VERSION} من termux..."
curl -fsSL --retry 3 -o "$TMP/proot.deb" "$PROOT_DEB_URL"

echo "🔐  التحقق من SHA256..."
echo "${PROOT_DEB_SHA256}  $TMP/proot.deb" | sha256sum -c -

echo "📦  فك الحزمة (باستخدام ar + tar)..."
cd "$TMP"
ar x proot.deb
mkdir -p "$TMP/extract"
# termux packages تستخدم tar.xz أو tar.gz
if [[ -f data.tar.xz ]]; then
    tar -xJf data.tar.xz -C "$TMP/extract"
elif [[ -f data.tar.gz ]]; then
    tar -xzf data.tar.gz -C "$TMP/extract"
else
    echo "❌ لم يتم العثور على data.tar.xz أو data.tar.gz"
    ls -la
    exit 1
fi

# الملفات في حزمة termux:
# /data/data/com.termux/files/usr/bin/proot
# /data/data/com.termux/files/usr/libexec/proot/loader
PROOT_BIN="$TMP/extract/data/data/com.termux/files/usr/bin/proot"
LOADER_BIN="$TMP/extract/data/data/com.termux/files/usr/libexec/proot/loader"

if [[ ! -f "$PROOT_BIN" || ! -f "$LOADER_BIN" ]]; then
    echo "❌ لم يتم العثور على ملفات prout/loader في الحزمة"
    exit 1
fi

mkdir -p "$DEST"
cp "$PROOT_BIN" "$DEST/libproot.so"
cp "$LOADER_BIN" "$DEST/libproot-loader.so"
chmod 755 "$DEST"/libproot*.so

echo "✅ تم تثبيت proot المحدث:"
ls -l "$DEST"/libproot*.so