#!/system/bin/sh
export PATH=/data/data/com.dsharnessmobile.shell/files/usr/bin:/system/bin:/vendor/bin:$PATH
set -e
cd /data/data/com.dsharnessmobile.shell/files/home/.dsh/workspaces/incoming/build
T=tools; AJ=$T/android-35/android.jar
rm -rf out && mkdir -p out/app out/dex out/gen
echo "[0/7] xposed stubs"
rm -rf stub-classes && mkdir -p stub-classes
javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -d stub-classes $(find stub/src -name '*.java') > out/stub.log 2>&1 || { echo "stub 编译失败"; head -10 out/stub.log; exit 1; }
echo "stub classes=$(find stub-classes -name '*.class' | wc -l)"
echo "[1/7] R.java"
aapt package -m -f -J out/gen -M app/AndroidManifest.xml -S app/res -I "$AJ" 2>&1 | grep -v "CREATING STRING" || true
ls out/gen/com/fuckorigin/app/R.java >/dev/null || { echo "R.java 未生成"; exit 1; }
echo "[2/7] compile"
if ! javac -nowarn -source 8 -target 8 -bootclasspath "$AJ" -cp "stub-classes:$AJ" -d out/app \
  $(find app/src -name '*.java') $(find out/gen/com -name '*.java') > out/javac.log 2>&1; then
  echo "[2/7] 编译失败："; grep -v "bootstrap class path" out/javac.log | head -20; exit 1
fi
n=$(find out/app -name '*.class' | wc -l); echo "classes=$n"; [ "$n" -gt 0 ] || exit 1
echo "[3/7] dex"
java -cp $T/r8.jar com.android.tools.r8.D8 --lib "$AJ" --min-api 29 --output out/dex $(find out/app -name '*.class') 2>&1 | tail -5 || true
ls -la out/dex/classes.dex || exit 1
echo "[3.5/7] 模块资源"
rm -rf app/assets/module && mkdir -p app/assets/module
cp -a module_assets/. app/assets/module/ 2>/dev/null || true
ls app/assets/module/ | tr "\n" " "; echo
echo "[4/7] package resources"
aapt package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$AJ" -F out/base.apk \
  --min-sdk-version 29 --target-sdk-version 33 2>&1 | grep -v "CREATING STRING" | tail -3 || true
ls -la out/base.apk || exit 1
echo "[5/7] merge dex"
python3 - <<'PY'
import zipfile
with zipfile.ZipFile("out/base.apk") as zi, zipfile.ZipFile("out/unsigned.apk","w",zipfile.ZIP_DEFLATED) as zo:
    for it in zi.infolist():
        zo.writestr(it, zi.read(it.filename))
    zo.write("out/dex/classes.dex","classes.dex")
    import os
    for base, _, files in os.walk("app/extra"):
        for fn in files:
            full = os.path.join(base, fn)
            zo.write(full, os.path.relpath(full, "app/extra"))
print("ok")
PY
echo "[6/7] align"
zipalign -f 4 out/unsigned.apk out/aligned.apk
echo "[7/7] sign"
if [ ! -f tools/fuckorigin.p12 ]; then
  openssl req -x509 -newkey rsa:2048 -keyout tools/key.pem -out tools/cert.pem -days 10000 -nodes \
    -subj "/CN=FuckOrigin/O=FuckOrigin/C=CN" >/dev/null 2>&1
  openssl pkcs12 -export -inkey tools/key.pem -in tools/cert.pem -out tools/fuckorigin.p12 \
    -name fuckorigin -passout pass:fuckorigin >/dev/null 2>&1
fi
java -jar /data/data/com.dsharnessmobile.shell/files/usr/share/java/apksigner.jar sign --ks tools/fuckorigin.p12 --ks-type PKCS12 --ks-pass pass:fuckorigin \
  --key-pass pass:fuckorigin --out out/FuckOrigin.apk out/aligned.apk
java -jar /data/data/com.dsharnessmobile.shell/files/usr/share/java/apksigner.jar verify --print-certs out/FuckOrigin.apk | head -2
ls -la out/FuckOrigin.apk
