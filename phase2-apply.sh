#!/usr/bin/env bash
# المرحلة 2: إزالة المفتاح وكلمة السر من الكود نهائياً وجعل البناء صارماً.
# لا تُشغَّل إلا بعد دمج المرحلة 1 ونجاح بناء main وتطابق البصمة.
#
# الاستخدام:
#   bash phase2-apply.sh
# للتجربة دون دفع أو فتح PR (يتخطى فحص الأسرار):
#   DRY_RUN=1 bash phase2-apply.sh
set -euo pipefail

REPO="starmaen/LionAI"
BRANCH="security/signing-secrets-phase2"
cd "$(git rev-parse --show-toplevel)"

if [ -z "${DRY_RUN:-}" ]; then
  # لا نكمل إلا إذا كانت الأسرار والبصمة موجودة فعلاً، وإلا ينكسر البناء
  SECRETS="$(gh secret list -R "$REPO" | awk '{print $1}')"
  for s in LION_KEYSTORE_BASE64 LION_KEYSTORE_PASSWORD LION_KEY_PASSWORD; do
    echo "$SECRETS" | grep -qx "$s" || { echo "ERROR: secret $s is missing. Aborting."; exit 1; }
  done
  gh variable list -R "$REPO" | awk '{print $1}' | grep -qx LION_CERT_SHA256 \
    || { echo "ERROR: variable LION_CERT_SHA256 is missing. Aborting."; exit 1; }
  git fetch origin main -q
  git checkout -b "$BRANCH" origin/main
else
  git checkout -b "$BRANCH"
fi

python3 - <<'PY'
import re, sys

def read(p):
    with open(p, encoding="utf-8", newline="") as fh:
        return fh.read()

def write(p, s):
    with open(p, "w", encoding="utf-8", newline="") as fh:
        fh.write(s)

def replace_once(s, old, new, label):
    if s.count(old) != 1:
        sys.exit(f"ERROR: {label}: expected exactly one match, found {s.count(old)}")
    return s.replace(old, new)

# ---------- 1) app/build.gradle.kts ----------
p = "app/build.gradle.kts"
s = read(p)

# حذف كلمتي السر الاحتياطيتين المكتوبتين في الكود
s, n = re.subn(r'^[ \t]*\?: "LionAI[^"\n]*"\r?\n', "", s, flags=re.M)
if n != 2:
    sys.exit(f"ERROR: expected 2 hardcoded fallback lines, removed {n}")

s = replace_once(
    s,
    "    val hasReleaseCredentials = releaseStorePassword != null && releaseKeyPassword != null\n",
    '    val releaseKeystoreFile = rootProject.file("keystore/lionai-release.jks")\n'
    "    val hasReleaseCredentials =\n"
    "        releaseStorePassword != null && releaseKeyPassword != null && releaseKeystoreFile.exists()\n",
    "hasReleaseCredentials",
)

s = replace_once(
    s,
    '    signingConfigs {\n'
    '        create("lion") {\n'
    '            storeFile = rootProject.file("keystore/lionai-release.jks")\n'
    '            storePassword = releaseStorePassword\n'
    '            keyAlias = "lionai"\n'
    '            keyPassword = releaseKeyPassword\n'
    '        }\n'
    '    }\n',
    '    if (hasReleaseCredentials) {\n'
    '        signingConfigs {\n'
    '            create("lion") {\n'
    '                storeFile = releaseKeystoreFile\n'
    '                storePassword = releaseStorePassword\n'
    '                keyAlias = "lionai"\n'
    '                keyPassword = releaseKeyPassword\n'
    '            }\n'
    '        }\n'
    '    }\n',
    "signingConfigs block",
)
write(p, s)
print(f"updated: {p}")

# ---------- 2) workflows: صارمة عند غياب الأسرار ----------
WARN_KEY = '          echo "::warning::LION_KEYSTORE_BASE64 is not set; using the keystore committed in the repo (phase 1 fallback)."\n'
ERR_KEY = ('          echo "::error::LION_KEYSTORE_BASE64 is not set; refusing to build a release with the wrong key."\n'
           '          exit 1\n')
WARN_PIN = ('          echo "::warning::LION_CERT_SHA256 is not set; fingerprint check skipped."\n'
            '          exit 0\n')
ERR_PIN = ('          echo "::error::LION_CERT_SHA256 is not set; refusing to publish without a fingerprint check."\n'
           '          exit 1\n')

for p in (".github/workflows/build.yml", ".github/workflows/release.yml"):
    s = read(p)
    s = replace_once(s, WARN_KEY, ERR_KEY, f"{p} restore step")
    s = replace_once(s, WARN_PIN, ERR_PIN, f"{p} pin step")
    write(p, s)
    print(f"updated: {p}")

# ---------- 3) .gitignore ----------
p = ".gitignore"
s = read(p)
s, n = re.subn(
    r"^# ملاحظة: keystore/lionai-release\.jks.*\r?\n# .*لا تضفه هنا أبداً\.\r?\n",
    "# مفاتيح التوقيع تأتي من GitHub Secrets ولا تُرفع أبداً.\n",
    s, flags=re.M,
)
if n != 1:
    sys.exit(f"ERROR: old keystore note in .gitignore not found exactly once (found {n})")
if "*.jks" not in s:
    s = s.rstrip("\n") + "\n*.jks\n*.keystore\n"
write(p, s)
print(f"updated: {p}")
PY

git rm -q keystore/lionai-release.jks .github/workflows/make-keystore.yml
git add app/build.gradle.kts .github/workflows/build.yml .github/workflows/release.yml .gitignore
git commit -q -m "security: remove committed keystore and hardcoded password; signing only from secrets (phase 2)"

if [ -n "${DRY_RUN:-}" ]; then
  echo "DRY_RUN: committed locally on $BRANCH, nothing pushed."
  exit 0
fi

git push -u origin "$BRANCH"
gh pr create --base main --head "$BRANCH" \
  --title "security: signing key only from secrets (phase 2)" \
  --body "المرحلة 2: حذف keystore/lionai-release.jks و make-keystore.yml وكلمة السر الاحتياطية من الكود. التوقيع من الأسرار فقط، والبناء يفشل إن غابت أو اختلفت البصمة. بناءات الـ PRs (Dependabot) تعمل بتوقيع debug."
echo "Done."
