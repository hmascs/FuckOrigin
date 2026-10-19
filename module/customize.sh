#!/system/bin/sh
SKIPUNZIP=1
ui_print "- FuckOrigin Thermal 安装中"

DATA=/data/adb/fuckorigin
mkdir -p "$DATA/bin" "$DATA/log"
chmod 700 "$DATA"

unzip -o "$ZIPFILE" 'bin/*' -d "$MODPATH" >/dev/null 2>&1
if [ -f "$MODPATH/bin/fuckorigind" ]; then
  cp -f "$MODPATH/bin/fuckorigind" "$DATA/bin/fuckorigind"
  chmod 755 "$DATA/bin/fuckorigind"
  rm -rf "$MODPATH/bin"
  ui_print "- daemon 已部署：$DATA/bin/fuckorigind"
else
  ui_print "! 包内缺少 bin/fuckorigind"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
ui_print "- 控制台：http://127.0.0.1:8899 （开机后自动启动）"
ui_print "- 安装完成"
