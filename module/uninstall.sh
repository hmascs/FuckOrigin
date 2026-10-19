#!/system/bin/sh
[ -f /data/adb/fuckorigin/daemon.pid ] && kill "$(cat /data/adb/fuckorigin/daemon.pid)" 2>/dev/null
pkill -f fuckorigind 2>/dev/null
# 恢复温控
for z in /sys/class/thermal/thermal_zone*; do echo enabled > "$z/mode" 2>/dev/null; done
P=$(pidof thermal-engine-v2 | awk '{print $1}'); [ -n "$P" ] && kill -CONT "$P" 2>/dev/null
rm -rf /data/adb/fuckorigin
