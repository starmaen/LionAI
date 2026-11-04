#!/usr/bin/env bash
# يجلب proot (aarch64) المطلوب لتشغيل بيئة Linux داخل التطبيق ويضعه كمكتبات native.
# التنفيذ من مسار التطبيق (nativeLibraryDir) هو الطريقة الوحيدة المسموحة لتشغيل ملف تنفيذي على Android 10+.
set -euo pipefail
URL="https://raw.githubusercontent.com/green-green-avk/build-proot-android/master/packages/proot-android-aarch64.tar.gz"
SHA256="9629eb30cdf86e95c6ba681f8ab89c6fdaa9eca093d5577163513c99af5ca281"
DEST="$(cd "$(dirname "$0")/.." && pwd)/app/src/main/jniLibs/arm64-v8a"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 3 -o "$TMP/proot.tgz" "$URL"
echo "$SHA256  $TMP/proot.tgz" | sha256sum -c -
tar -xzf "$TMP/proot.tgz" -C "$TMP"
mkdir -p "$DEST"
cp "$TMP/root/bin/proot" "$DEST/libproot.so"
cp "$TMP/root/libexec/proot/loader" "$DEST/libproot-loader.so"
chmod 755 "$DEST"/libproot*.so
ls -l "$DEST"
