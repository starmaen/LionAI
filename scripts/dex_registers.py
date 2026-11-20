#!/usr/bin/env python3
"""يقيس عدد سجلات dex لأكبر دوال com.lion في الـ APK. الدوال الضخمة (>255 سجلاً) تُحمّل ببايت كود
يُفسد التحقق على بعض الأجهزة («Verifier rejected class»)، فنراقبها في كل بناء."""
import glob, os, re, subprocess, sys, tempfile, zipfile

apk = sys.argv[1] if len(sys.argv) > 1 else ""
tools = sorted(glob.glob(os.path.join(os.environ.get("ANDROID_HOME", ""), "build-tools", "*", "dexdump")))
if not apk or not tools:
    print("dexdump أو APK غير متاح؛ تخطّي القياس")
    sys.exit(0)
tmp = tempfile.mkdtemp()
rows = []
with zipfile.ZipFile(apk) as z:
    for n in z.namelist():
        if not re.fullmatch(r"classes\d*\.dex", n):
            continue
        path = z.extract(n, tmp)
        out = subprocess.run([tools[-1], "-d", path], capture_output=True, text=True, errors="replace").stdout
        cls = meth = None
        for line in out.splitlines():
            m = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
            if m:
                cls = m.group(1)
                continue
            m = re.match(r"\s+name\s*:\s*'([^']+)'", line)
            if m:
                meth = m.group(1)
                continue
            m = re.match(r"\s+registers\s*:\s*(\d+)", line)
            if m and cls and cls.startswith("Lcom/lion/"):
                rows.append((int(m.group(1)), cls, meth, n))
rows.sort(reverse=True)
for regs, cls, meth, dex in rows[:8]:
    level = "error" if regs > 255 else "warning"
    print(f"::{level} title=dex registers::{regs} registers in {cls}.{meth} ({dex})")
print("top methods:", [(r, c.split("/")[-1], m) for r, c, m, _ in rows[:5]])
