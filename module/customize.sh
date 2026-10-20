#!/system/bin/sh
SKIPUNZIP=1
ui_print "- FuckOrigin Thermal 安装中"

DATA=/data/adb/fuckorigin
mkdir -p "$DATA/bin" "$DATA/log"
chmod 700 "$DATA"

# 整包解压（原版只解 bin/*，导致 service.sh / fo_perf.sh 根本没进 MODPATH）
unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >/dev/null 2>&1

if [ -f "$MODPATH/bin/fuckorigind" ]; then
  cp -f "$MODPATH/bin/fuckorigind" "$DATA/bin/fuckorigind"
  chmod 755 "$DATA/bin/fuckorigind"
  rm -rf "$MODPATH/bin"
  ui_print "- daemon 已部署：$DATA/bin/fuckorigind"
else
  ui_print "- 未内置 daemon：WebUI 控制台不可用，频率执行器照常工作"
fi

# service.sh 从 /data/adb/fuckorigin/bin 拉起执行器
cp -f "$MODPATH/fo_perf.sh" "$DATA/bin/fo_perf.sh" 2>/dev/null
cp -f "$MODPATH/fo_whitelist.sh" "$DATA/bin/fo_whitelist.sh" 2>/dev/null
chmod 755 "$DATA/bin/fo_perf.sh" "$DATA/bin/fo_whitelist.sh" 2>/dev/null

set_perm_recursive "$MODPATH" 0 0 0755 0644
ui_print "- 频率执行器已部署：$DATA/bin/fo_perf.sh"
ui_print "- 安装完成"
