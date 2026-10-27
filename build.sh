#!/bin/bash
# 享看电影 Android 原生构建脚本
# 内置腾讯 X5 内核（Chromium 121），替换系统 WebView。
# 依赖：android.jar + tbssdk.jar（不需要 exoplayer / lib_r.jar）
set -e

ROOT="D:/WorkBuddy/apk/moontv"
SDK="D:/WorkBuddy/apk/_build/sdk"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-35/android.jar"
JAVA="D:/WorkBuddy/apk/_analysis/tools/jdk-17.0.20.1+1/bin/java.exe"
JAVAC="D:/WorkBuddy/apk/_analysis/tools/jdk-17.0.20.1+1/bin/javac.exe"
PY="C:/Users/Administrator/.workbuddy/binaries/python/envs/default/Scripts/python.exe"
KS="C:/Users/Administrator/.android/debug.keystore"

cd "$ROOT"

# ===== 0) X5 内核完整性预检（双 ABI，大小不符直接中止，绝不出坏包）=====
echo "=== 0) X5 内核预检 ==="
K32="$ROOT/x5kernel/tbs_core_release.tbs.apk"
K64="$ROOT/x5kernel/tbs_core_release_arm64.tbs.apk"
for K in "$K32" "$K64"; do
  [ -f "$K" ] || { echo "FATAL: 缺少内核 $K"; exit 1; }
done
S32=$(stat -c%s "$K32"); S64=$(stat -c%s "$K64")
[ "$S32" -eq 42475965 ] || { echo "FATAL: 32 位核大小异常 $S32 (期望 42475965)"; exit 1; }
[ "$S64" -eq 51021829 ] || { echo "FATAL: 64 位核大小异常 $S64 (期望 51021829)，禁止出包"; exit 1; }
echo "kernel OK: 32bit=$S32 64bit=$S64"

# ===== 1) 版本号自动 +1 =====
export VC VN
eval $($PY - <<'PYVER'
import io, re
p = 'D:/WorkBuddy/apk/moontv/AndroidManifest.xml'
s = io.open(p, encoding='utf-8').read()
m = re.search(r'android:versionCode="(\d+)"', s)
if not m:
    raise SystemExit('FATAL: versionCode not found')
vc = int(m.group(1)) + 1
s = re.sub(r'android:versionCode="\d+"', 'android:versionCode="%d"' % vc, s)
s = re.sub(r'android:versionName="[^"]*"', 'android:versionName="1.%d"' % (vc - 1), s)
io.open(p, 'w', encoding='utf-8').write(s)
print('VC=%d; VN=1.%d;' % (vc, vc - 1))
PYVER
)
echo "versionCode=$VC versionName=$VN"

# ===== 2) 清目录 =====
rm -rf obj dex out && mkdir -p obj dex out/gen

# ===== 3) aapt2 compile + link（同时生成 R.java）=====
echo "=== 2) aapt2 compile + link ==="
"$BT/aapt2.exe" compile --dir res -o out/compiled.zip
"$BT/aapt2.exe" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --java out/gen -A "$ROOT/assets" out/compiled.zip
test -f out/base.apk || { echo "FATAL: aapt2 link failed"; exit 1; }
test -f out/gen/com/tvshell/moontv/R.java || { echo "FATAL: R.java not generated"; exit 1; }
echo "base.apk + R.java OK"

# ===== 4) javac =====
echo "=== 3) javac ==="
CP="tbssdk.jar"
"$JAVAC" -nowarn -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJ" -classpath "$CP" -d obj \
  src/com/tvshell/moontv/*.java out/gen/com/tvshell/moontv/R.java > "$ROOT/javac_build.log" 2>&1
JRC=$?
if [ "$JRC" -ne 0 ]; then
  echo "FATAL: javac failed rc=$JRC"; cat "$ROOT/javac_build.log"; exit 1
fi
echo "javac OK, class count=$(find obj -name '*.class' | wc -l)"

# ===== 5) d8 dex（min-api 23 自动多 dex；X5 SDK 引用运行时类，d8 容忍）=====
echo "=== 4) d8 dex ==="
"$JAVA" -Xmx1024m -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 23 \
  --lib "$AJ" --output dex $(find obj -name '*.class') tbssdk.jar > /tmp/d8_build.log 2>&1
RC=$?
if [ $RC -ne 0 ] || [ ! -f dex/classes.dex ]; then
  echo "FATAL: d8 failed rc=$RC"; tail -30 /tmp/d8_build.log; exit 1
fi
echo "d8 OK (dex files: $(ls dex | wc -l))"

# ===== 6) inject.py（dex + X5 内核离线包 + 修复 resources.arsc）=====
echo "=== 5) inject ==="
"$PY" "$ROOT/inject.py"
test -f out/base.apk || { echo "FATAL: inject failed"; exit 1; }
echo "inject OK"

# ===== 7) zipalign =====
echo "=== 6) zipalign ==="
"$BT/zipalign.exe" -p -f 4 out/base.apk out/aligned.apk
test -f out/aligned.apk || { echo "FATAL: zipalign failed"; exit 1; }

# ===== 8) apksigner =====
echo "=== 7) apksigner ==="
"$JAVA" -jar "$BT/lib/apksigner.jar" sign --ks "$KS" --ks-pass pass:android \
  --ks-key-alias androiddebugkey --key-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out out/xiandiankan-signed.apk out/aligned.apk 2>&1 | grep -v 'Warning' || true
test -f out/xiandiankan-signed.apk || { echo "FATAL: apksigner failed"; exit 1; }

# ===== 9) 验证 + 交付 =====
echo "=== 8) 验证 ==="
"$BT/aapt.exe" dump badging out/xiandiankan-signed.apk 2>/dev/null | grep -E '^package|application-label:|launchable-activity' | head -4
ls -la out/xiandiankan-signed.apk
cp -f out/xiandiankan-signed.apk "D:/WorkBuddy/apk/享看电影.apk"
cp -f out/xiandiankan-signed.apk "D:/WorkBuddy/apk/享看电影_v${VN}_b${VC}.apk"
echo "BUILD_OK"
