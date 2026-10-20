#!/system/bin/sh
# 卸载时的清理：停执行器、交还热控、还原频率下限、清掉控制用 settings 键。
# 原来只做了「热区 enabled + CONT 引擎 + 删目录」，
# 若卸载时正处于信仰模式，scaling_min_freq 会永远停在顶频，卸载后待机也发烫。
pkill -f fo_perf.sh 2>/dev/null
[ -f /data/adb/fuckorigin/daemon.pid ] && kill "$(cat /data/adb/fuckorigin/daemon.pid)" 2>/dev/null
pkill -f fuckorigind 2>/dev/null

# 恢复温控
for z in /sys/class/thermal/thermal_zone*; do echo enabled > "$z/mode" 2>/dev/null; done
P=$(pidof thermal-engine-v2 | awk '{print $1}'); [ -n "$P" ] && kill -CONT "$P" 2>/dev/null

# 还原 DVFS 下限（信仰模式会把它拉到顶）
for d in /sys/devices/system/cpu/cpufreq/policy*; do
  mn=$(cat "$d/cpuinfo_min_freq" 2>/dev/null)
  [ -n "$mn" ] && echo "$mn" > "$d/scaling_min_freq" 2>/dev/null
done

# 清掉模块用的 settings 键，避免重装后仍带着旧状态
for k in sys_perf_faith sys_perf_auto sys_perf_auto_pkg sys_perf_c0_max sys_perf_c0_min \
         sys_perf_g_max sys_perf_g_min sys_perf_thaw sys_perf_fi_force; do
  settings delete secure "$k" >/dev/null 2>&1
done

rm -rf /data/adb/fuckorigin
