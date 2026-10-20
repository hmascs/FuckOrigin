#!/system/bin/sh
# FuckOrigin 频率/温控执行器：读 Settings.Secure，写 sysfs。
export PATH=/system/bin:/vendor/bin:/product/bin:$PATH
SET=/system/bin/settings
LOG=/data/adb/fuckorigin/log/perf.log

# 单实例：service.sh（开机）与 App「一键安装并启用」都会拉起执行器，
# 两个实例同时跑会重复写 sysfs / settings。用 mkdir 做原子锁（toybox 的 flock 只收 1 个参数，用不了）。
LOCKD=/data/adb/fuckorigin/perf.lock.d
mkdir -p /data/adb/fuckorigin
if ! mkdir "$LOCKD" 2>/dev/null; then
  _old=$(cat "$LOCKD/pid" 2>/dev/null)
  if [ -n "$_old" ] && kill -0 "$_old" 2>/dev/null; then
    echo "$(date +%H:%M:%S) 已有执行器实例 pid=$_old，退出 $$" >> "$LOG"
    exit 0
  fi
  rm -rf "$LOCKD"
  mkdir "$LOCKD" 2>/dev/null || exit 0
fi
echo $$ > "$LOCKD/pid"
trap 'rm -rf "$LOCKD"' EXIT

# ── 平台探测 ────────────────────────────────────────────────────────
# 原作者把超大核簇写死成 policy6（骁龙 8 至尊版 sun 的布局）。
# 8 Gen 3（pineapple）是 policy0/2/5/7 四簇，X4 在 policy7，且它的
# cpuinfo_max_freq 比 cpu2-4 簇还低，所以不能用"最高频"判断，改用"核数最少"。
# 8 Elite: policy6 有 2 核 → 命中；8 Gen 3: policy7 只有 1 核 → 命中。
pick_prime() {
  _best=""; _bestn=99; _bestf=0
  for _d in /sys/devices/system/cpu/cpufreq/policy*; do
    _f=$(cat "$_d/cpuinfo_max_freq" 2>/dev/null)
    [ -n "$_f" ] || continue
    _n=$(cat "$_d/related_cpus" 2>/dev/null | wc -w)
    [ -n "$_n" ] || _n=99
    if [ -z "$_best" ] || [ "$_n" -lt "$_bestn" ] || \
       { [ "$_n" -eq "$_bestn" ] && [ "$_f" -gt "$_bestf" ]; }; then
      _best=$_d; _bestn=$_n; _bestf=$_f
    fi
  done
  echo "${_best:-/sys/devices/system/cpu/cpufreq/policy0}"
}

pick_little() {
  _best=""; _bestf=99999999
  for _d in /sys/devices/system/cpu/cpufreq/policy*; do
    _f=$(cat "$_d/cpuinfo_max_freq" 2>/dev/null)
    [ -n "$_f" ] || continue
    if [ -z "$_best" ] || [ "$_f" -lt "$_bestf" ]; then _best=$_d; _bestf=$_f; fi
  done
  echo "${_best:-/sys/devices/system/cpu/cpufreq/policy0}"
}

POL=$(pick_prime)
POL0=$(pick_little)
GPU=/sys/class/kgsl/kgsl-3d0/devfreq

# 8 Elite 有 SuperResolutionConfigList.xml，8 Gen 3 只有 FrameInterConfigList.xml
CFG=""
for _c in /system/etc/gamecube/SuperResolutionConfigList.xml /system/etc/gamecube/FrameInterConfigList.xml; do
  [ -f "$_c" ] && { CFG=$_c; break; }
done

say() { echo "$(date +%H:%M:%S) $*" >> "$LOG"; }

MP=/data/adb/modules/fuckorigin-thermal/module.prop
last_desc=""
update_desc() {
  [ -w "$MP" ] || return
  st=$1; cm=$(get sys_perf_c0_max); gm=$(get sys_perf_g_max)
  [ "$cm" = "null" ] && cm="-"; [ "$gm" = "null" ] && gm="-"
  _d="作者 hmascs｜状态：$st｜CPU上限 ${cm}｜GPU上限 ${gm}｜信仰模式 ${faith:-0}"
  # 原来是每轮（2 秒）都 sed -i 重写 module.prop：constant flash 写入 + 与模块管理器读文件竞争。
  # 只在内容变化时写。
  [ "$_d" = "$last_desc" ] && return
  grep -q "^description=" "$MP" && sed -i "s|^description=.*|description=$_d|" "$MP"
  last_desc=$_d
}
get() { $SET get secure "$1" 2>/dev/null | tr -d "\r"; }
w() { [ -w "$1" ] && echo "$2" > "$1" 2>/dev/null; }

gpu_max_avail() {
  cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null | /system/bin/tr " " "\n" | /system/bin/sort -n | /system/bin/tail -1
}

# 可写频点的最高档。cpuinfo_max_freq 是硬件能力值（本机 3302400），
# 不在 scaling_available_frequencies 里（最高 3052800），写进去会被内核拒绝，
# 结果 scaling_max_freq 停在某个中间档（实测 2630400）。
cpu_max_avail() {
  cat "$1/scaling_available_frequencies" 2>/dev/null | /system/bin/tr " " "\n" | /system/bin/sort -n | /system/bin/tail -1
}

# 把请求值夹到 [0, 最高档]，空值原样返回
clamp() {
  _v=$1; _max=$2
  case "$_v" in ""|null) echo "$_v"; return;; esac
  [ -n "$_max" ] || { echo "$_v"; return; }
  [ "$_v" -gt "$_max" ] 2>/dev/null && echo "$_max" || echo "$_v"
}

freeze_thermal() {
  p=$(pidof thermal-engine-v2 2>/dev/null | awk "{print \$1}")
  [ -n "$p" ] && kill -STOP "$p" 2>/dev/null
  for d in /sys/class/thermal/cooling_device*; do
    [ -r "$d/type" ] || continue
    t=$(cat "$d/type" 2>/dev/null)
    case "$t" in cpu-cluster*|cpufreq-cpu*|gpu) echo 0 > "$d/cur_state" 2>/dev/null;; esac
  done
}

thaw_thermal() {
  for p in $(pidof thermal-engine-v2 2>/dev/null); do kill -CONT "$p" 2>/dev/null; done
}

# 信仰/性能模式会把 scaling_min_freq 拉到顶（apply 1 里 cmin=cmax），
# 关掉开关后必须把下限还原成硬件最小值，否则 CPU 永远降不下来、待机也发烫。
restore_dvfs() {
  for _p in "$POL0" "$POL"; do
    _mn=$(cat "$_p/cpuinfo_min_freq" 2>/dev/null)
    [ -n "$_mn" ] && echo "$_mn" > "$_p/scaling_min_freq" 2>/dev/null
  done
  say "恢复 DVFS 下限"
}

apply() {
  cmin=$(get sys_perf_c0_min); cmax=$(get sys_perf_c0_max)
  gmin=$(get sys_perf_g_min); gmax=$(get sys_perf_g_max)
  hard=$1
  if [ "$hard" = "1" ]; then
    cmax=$(cpu_max_avail "$POL"); cmin=$cmax
    gmax=$(gpu_max_avail); gmin=$gmax
  else
    # settings 里可能残留旧版本写入的非法值（cpuinfo_max_freq / 当时的 devfreq 上限），
    # 每轮都夹一次，避免把 CPU/GPU 反向锁死
    _cm=$(cpu_max_avail "$POL"); _gm=$(gpu_max_avail)
    cmax=$(clamp "$cmax" "$_cm"); cmin=$(clamp "$cmin" "$_cm")
    gmax=$(clamp "$gmax" "$_gm"); gmin=$(clamp "$gmin" "$_gm")
  fi
  case "$cmin" in ""|null) ;; *) w $POL/scaling_min_freq "$cmin"; w $POL0/scaling_min_freq "$cmin";; esac
  case "$cmax" in ""|null) ;; *) w $POL/scaling_max_freq "$cmax"; w $POL0/scaling_max_freq "$cmax";; esac
  case "$gmin" in ""|null) ;; *) w $GPU/min_freq "$gmin";; esac
  case "$gmax" in ""|null) ;; *) w $GPU/max_freq "$gmax";; esac
}

last_faith=""; last_auto=""; tick=0; FG_GAME=0

# 交还热控：只要没有解除诉求，就确保 thermal-engine 处于运行态
reconcile_thermal() {
  [ -f /data/adb/fuckorigin/unlocked ] && return
  for p in $(pidof thermal-engine-v2 2>/dev/null); do
    st=$(ps -o STAT -p "$p" 2>/dev/null | tail -1)
    case "$st" in
      *T*) kill -CONT "$p" 2>/dev/null; say "thaw 交还热控 pid=$p" ;;
    esac
  done
}
# CFG 已在文件头按平台探测（SuperResolutionConfigList / FrameInterConfigList）

# 前台是配置表里的游戏、但侧边栏进程不在时，主动拉起（vivo 自身触发有时会失效）
ensure_sidebar() {
  fg=$(dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity \
       | sed -n "s/.*u0 \([^ \/]*\)\/.*/\1/p")
  # 侧边栏进程还在、且 current_game_package 非空，并不代表游戏在前台：
  # 游戏退到后台后这两者都会残留，原来据此置 auto=1，会把热控重新冻回去。
  # 必须要求"前台应用 == 当前游戏"才算真的在玩。
  if ps -A -o name 2>/dev/null | grep -q "com.vivo.gamecube:assistantui"; then
    cg=$(/system/bin/settings get system current_game_package 2>/dev/null)
    case "$cg" in ""|null) cg="";; esac
    if [ "$(get sys_perf_auto)" != "1" ] && [ -n "$cg" ] && [ "$fg" = "$cg" ]; then
      cm=$(get sys_perf_c0_max)
      case "$cm" in ""|null) ;; *)
        $SET put secure sys_perf_auto 1
        $SET put secure sys_perf_auto_pkg "$cg"
        say "sidebar_应用配置 游戏=$cg auto=1"
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
  FG_GAME=0
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
  FG_GAME=1
  FG_GAME=1
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
  # tick 原来每轮加两次、ensure_sidebar 也调两次，实际间隔是 5 秒而不是设计的 10 秒
  tick=$((tick + 1))
  [ $((tick % 5)) -eq 0 ] && ensure_sidebar
  faith=$(get sys_perf_faith); auto=$(get sys_perf_auto)
  # 模块控制台的「解除温控限制 / 信仰模式」会创建 unlocked 文件，等同开启信仰模式
  [ -f /data/adb/fuckorigin/unlocked ] && faith=1
  [ "$auto" = "null" ] && auto=0
  [ "$faith" = "null" ] && faith=0

  # 信仰模式是手动开关，不随游戏退出自动回退

  # 性能模式联动同样在游戏退出后自动关闭
  if [ "$auto" = "1" ]; then
    ap=$(get sys_perf_auto_pkg)
    case "$ap" in
      ""|null) ;;
      *) if [ -z "$(pidof "$ap" 2>/dev/null)" ]; then
           say "game_exit $ap -> 自动关闭去温控(auto)"
           $SET put secure sys_perf_auto 0 2>/dev/null
           # 写失败时不能把 auto 当 0：否则下一轮读到 1 又会 freeze，来回拉扯
           [ "$(get sys_perf_auto)" = "0" ] && { auto=0; last_auto=0; }
           thaw_thermal
         fi;;
    esac
  fi

  if [ "$faith" != "$last_faith" ]; then
    say "faith=$faith"
    [ "$faith" = "1" ] && freeze_thermal
    [ "$faith" = "0" ] && [ "$last_faith" = "1" ] && { thaw_thermal; restore_dvfs; }
    last_faith=$faith
  fi
  if [ "$auto" != "$last_auto" ]; then
    say "auto=$auto"
    [ "$auto" = "1" ] && freeze_thermal
    [ "$auto" = "0" ] && [ "$last_auto" = "1" ] && { thaw_thermal; restore_dvfs; }
    last_auto=$auto
  fi

  update_desc "$([ "$faith" = "1" ] && echo "信仰模式" || ([ "$auto" = "1" ] && echo "性能调度" || echo "待机"))"
  # 显式降温请求：清掉控制台的 unlocked 并立即交还
  if [ "$(get sys_perf_thaw)" = "1" ] && [ "$faith" != "1" ]; then
    rm -f /data/adb/fuckorigin/unlocked
    $SET put secure sys_perf_thaw 0
    thaw_thermal
    faith=0; auto=0
    say "thaw 收到交还请求"
  fi

  # 前台不再是白名单游戏时，撤销自动去温控（信仰模式不受影响）
  if [ "$faith" != "1" ] && [ "$FG_GAME" != "1" ] && [ "$(get sys_perf_auto)" = "1" ]; then
    _now=$(date +%s)
    # settings 写入偶发被拒（cmd: Failure calling service settings: Failed transaction）。
    # 以前每轮（2 秒）都写一次、失败也写，既刷日志又白起进程；改成 30 秒才试一次。
    if [ $((_now - ${last_auto_try:-0})) -ge 30 ]; then
      last_auto_try=$_now
      $SET put secure sys_perf_auto 0 2>/dev/null
    fi
    auto=0
    if [ "$(get sys_perf_auto)" = "0" ]; then
      say "前台非游戏 -> auto=0 并交还热控"
      last_auto_warn=0
    else
      if [ $((_now - ${last_auto_warn:-0})) -ge 60 ]; then
        say "auto 清零被拒（settings 服务异常），30s 后重试；本轮仍交还热控"
        last_auto_warn=$_now
      fi
    fi
    thaw_thermal
  fi

  if [ "$faith" = "1" ]; then apply 1; freeze_thermal
  elif [ "$auto" = "1" ]; then apply 0; freeze_thermal
  else apply 0; reconcile_thermal; fi
  sleep 2
done
