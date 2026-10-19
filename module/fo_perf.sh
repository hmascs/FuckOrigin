#!/system/bin/sh
# FuckOrigin 频率/温控执行器：读 Settings.Secure，写 sysfs。
export PATH=/system/bin:/vendor/bin:/product/bin:$PATH
SET=/system/bin/settings
POL=/sys/devices/system/cpu/cpufreq/policy6
POL0=/sys/devices/system/cpu/cpufreq/policy0
GPU=/sys/class/kgsl/kgsl-3d0/devfreq
LOG=/data/adb/fuckorigin/log/perf.log

say() { echo "$(date +%H:%M:%S) $*" >> "$LOG"; }

MP=/data/adb/modules/fuckorigin-thermal/module.prop
update_desc() {
  [ -w "$MP" ] || return
  st=$1; cm=$(get sys_perf_c0_max); gm=$(get sys_perf_g_max)
  [ "$cm" = "null" ] && cm="-"; [ "$gm" = "null" ] && gm="-"
  desc="作者 hmascs｜状态：$st｜CPU上限 ${cm}｜GPU上限 ${gm}｜信仰模式 ${faith:-0}"
  grep -q "^description=" "$MP" && sed -i "s|^description=.*|description=$desc|" "$MP"
}
get() { $SET get secure "$1" 2>/dev/null | tr -d "\r"; }
w() { [ -w "$1" ] && echo "$2" > "$1" 2>/dev/null; }

gpu_max_avail() {
  cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null | /system/bin/tr " " "\n" | /system/bin/sort -n | /system/bin/tail -1
}

freeze_thermal() {
  p=$(pidof thermal-engine-v2 2>/dev/null | awk "{print \$1}")
  [ -n "$p" ] && kill -STOP "$p" 2>/dev/null
  for d in /sys/class/thermal/cooling_device*; do
    [ -r "$d/type" ] || continue
    t=$(cat "$d/type" 2>/dev/null)
    case "$t" in cpu-cluster0|cpu-cluster1|gpu) echo 0 > "$d/cur_state" 2>/dev/null;; esac
  done
}

thaw_thermal() {
  for p in $(pidof thermal-engine-v2 2>/dev/null); do kill -CONT "$p" 2>/dev/null; done
}

apply() {
  cmin=$(get sys_perf_c0_min); cmax=$(get sys_perf_c0_max)
  gmin=$(get sys_perf_g_min); gmax=$(get sys_perf_g_max)
  hard=$1
  if [ "$hard" = "1" ]; then
    cmax=$(cat $POL/cpuinfo_max_freq 2>/dev/null); cmin=$cmax
    gmax=$(gpu_max_avail); gmin=$gmax
  fi
  case "$cmin" in ""|null) ;; *) w $POL/scaling_min_freq "$cmin"; w $POL0/scaling_min_freq "$cmin";; esac
  case "$cmax" in ""|null) ;; *) w $POL/scaling_max_freq "$cmax"; w $POL0/scaling_max_freq "$cmax";; esac
  case "$gmin" in ""|null) ;; *) w $GPU/min_freq "$gmin";; esac
  case "$gmax" in ""|null) ;; *) w $GPU/max_freq "$gmax";; esac
}

last_faith=""; last_auto=""; tick=0
CFG=/system/etc/gamecube/SuperResolutionConfigList.xml

# 前台是配置表里的游戏、但侧边栏进程不在时，主动拉起（vivo 自身触发有时会失效）
ensure_sidebar() {
  fg=$(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity \
       | sed -n "s/.*u0 \([^ \/]*\)\/.*/\1/p")
  # 侧边栏进程被拉起即视为"游戏进行中"：套用已保存配置
  if ps -A -o name 2>/dev/null | grep -q "com.vivo.gamecube:assistantui"; then
    if [ "$(get sys_perf_auto)" != "1" ]; then
      cm=$(get sys_perf_c0_max)
      case "$cm" in ""|null) ;; *)
        $SET put secure sys_perf_auto 1
        say "sidebar_应用配置 检测到侧边栏，auto=1"
        auto=1 ;;
      esac
    fi
  else
    if [ "$(get sys_perf_auto)" = "1" ] && [ -z "$(get sys_perf_auto_pkg)" ]; then
      $SET put secure sys_perf_auto 0
      say "sidebar_退出 侧边栏已关闭，auto=0"
      auto=0
    fi
  fi

  # 游戏已退出：清掉残留的游戏状态，避免侧边栏在别的应用上也弹出
  cur0=$(/system/bin/settings get system current_game_package 2>/dev/null)
  case "$cur0" in ""|null) cur0="";; esac
  if [ -n "$cur0" ] && [ -z "$(pidof "$cur0" 2>/dev/null)" ]; then
    /system/bin/settings delete system current_game_package
    say "清理残留 current_game_package=$cur0"
  fi
  case "$fg" in ""|com.vivo.*|com.android.*|com.dsharnessmobile.shell) return;; esac
  hit=0
  [ -f "$CFG" ] && grep -q "pkgName=\"$fg\"" "$CFG" 2>/dev/null && hit=1
  if [ $hit -eq 0 ]; then
    wl=$(/system/bin/settings get global vsr_whitelist_apps 2>/dev/null)
    case ";$wl;" in *";$fg;"*) hit=1;; esac
  fi
  if [ $hit -eq 0 ]; then
    gl=$(/system/bin/settings get secure sys_perf_games 2>/dev/null)
    case ";$gl;" in *";$fg;"*) hit=1;; esac
  fi
  [ $hit -eq 1 ] || return
  ps -A -o name 2>/dev/null | grep -q "com.vivo.gamecube:assistantui" && return
  # gamecube 自身没把游戏写进 current_game_package，补上，否则服务起来了也不出窗口
  cur=$(/system/bin/settings get system current_game_package 2>/dev/null)
  [ "$cur" != "$fg" ] && /system/bin/settings put system current_game_package "$fg"
  say "sidebar_拉起 $fg"
  /system/bin/am start-service -n com.vivo.gamecube/com.vivo.gameassistant.AssistantUIService \
      >/dev/null 2>&1
}

say "启动频率执行器"
while true; do
  tick=$((tick + 1))
  [ $((tick % 5)) -eq 0 ] && ensure_sidebar
  faith=$(get sys_perf_faith); auto=$(get sys_perf_auto)
  # 模块控制台的「解除温控限制 / 信仰模式」会创建 unlocked 文件，等同开启信仰模式
  [ -f /data/adb/fuckorigin/unlocked ] && faith=1
  [ "$auto" = "null" ] && auto=0
  [ "$faith" = "null" ] && faith=0

  # 游戏退出后自动关闭温控解除
  if [ "$faith" = "1" ]; then
    gp=$(get sys_perf_faith_pkg)
    case "$gp" in
      ""|null) ;;
      *) if [ -z "$(pidof "$gp" 2>/dev/null)" ]; then
           say "game_exit $gp -> 自动关闭温控解除"
           $SET put secure sys_perf_faith 0
           faith=0; last_faith=0; thaw_thermal
         fi;;
    esac
  fi

  # 性能模式联动同样在游戏退出后自动关闭
  if [ "$auto" = "1" ]; then
    ap=$(get sys_perf_auto_pkg)
    case "$ap" in
      ""|null) ;;
      *) if [ -z "$(pidof "$ap" 2>/dev/null)" ]; then
           say "game_exit $ap -> 自动关闭去温控(auto)"
           $SET put secure sys_perf_auto 0
           auto=0; last_auto=0; thaw_thermal
         fi;;
    esac
  fi

  if [ "$faith" != "$last_faith" ]; then
    say "faith=$faith"
    [ "$faith" = "1" ] && freeze_thermal
    [ "$faith" = "0" ] && [ "$last_faith" = "1" ] && thaw_thermal
    last_faith=$faith
  fi
  if [ "$auto" != "$last_auto" ]; then
    say "auto=$auto"
    [ "$auto" = "1" ] && freeze_thermal
    [ "$auto" = "0" ] && [ "$last_auto" = "1" ] && thaw_thermal
    last_auto=$auto
  fi

  tick=$((tick + 1))
  [ $((tick % 5)) -eq 0 ] && ensure_sidebar
  update_desc "$([ "$faith" = "1" ] && echo "信仰模式" || ([ "$auto" = "1" ] && echo "性能调度" || echo "待机"))"
  if [ "$faith" = "1" ]; then apply 1; freeze_thermal
  elif [ "$auto" = "1" ]; then apply 0; freeze_thermal
  else apply 0; fi
  sleep 2
done
