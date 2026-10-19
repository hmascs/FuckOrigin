#!/system/bin/sh
# KernelSU 模块卡片上的「执行」按钮 —— 切换温控解锁状态
export PATH=/system/bin:/system/xbin
DATA=/data/adb/fuckorigin
FLAG=$DATA/unlocked
mkdir -p "$DATA"

unlock() {
  P=$(pidof thermal-engine-v2 | awk '{print $1}')
  [ -n "$P" ] && kill -STOP "$P"
  for z in /sys/class/thermal/thermal_zone*; do echo disabled > "$z/mode" 2>/dev/null; done
  for d in /sys/class/thermal/cooling_device*; do
    [ -r "$d/type" ] || continue
    case "$(< "$d/type")" in
      cpu-cluster0|cpu-cluster1|gpu) echo 0 > "$d/cur_state" 2>/dev/null;;
    esac
  done
  date > "$FLAG"
  echo "已解除温控：引擎冻结 / 热区关闭 / 冷却设备归零"
  echo "大核上限 $(< /sys/devices/system/cpu/cpufreq/policy6/scaling_max_freq 2>/dev/null) kHz"
  T=$(for z in /sys/class/thermal/thermal_zone*; do [ -r "$z/type" ] || continue; [ "$(< "$z/type")" = "tz_shell" ] && echo $(< "$z/temp"); done)
  [ -n "$T" ] && echo "机身 $((T / 1000))℃（超过 52℃ 守护进程会自动回退）"
}

relock() {
  for z in /sys/class/thermal/thermal_zone*; do echo enabled > "$z/mode" 2>/dev/null; done
  P=$(pidof thermal-engine-v2 | awk '{print $1}')
  [ -n "$P" ] && kill -CONT "$P"
  rm -f "$FLAG"
  echo "已交还热控"
}

if [ -f "$FLAG" ]; then
  relock
else
  unlock
fi

# 顺手确保 daemon 在跑（WebUI 面板要用它）
if ! pidof fuckorigind >/dev/null 2>&1; then
  if [ -x "$DATA/bin/fuckorigind" ]; then
    # 必须 setsid + 三个重定向全部断开，否则模块管理器的执行会话会一直等着这个子进程
    setsid /system/bin/sh -c "exec $DATA/bin/fuckorigind" </dev/null >>"$DATA/log/daemon.log" 2>&1 &
  fi
fi
