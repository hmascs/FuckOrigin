#!/system/bin/sh
MODDIR=${0%/*}
DATA=/data/adb/fuckorigin
BIN=$DATA/bin/fuckorigind
LOG=$DATA/log/daemon.log

until [ "$(getprop sys.boot_completed)" = "1" ]; do sleep 2; done

# 上次异常退出计数：连挂两次就禁用自己
if [ -f "$DATA/boot.pending" ]; then
  n=$(cat "$DATA/fail.count" 2>/dev/null || echo 0)
  n=$((n+1)); echo "$n" > "$DATA/fail.count"
  [ "$n" -ge 2 ] && { touch "$MODDIR/disable"; echo "$(date) 连续失败 $n 次，已自动禁用" >> "$LOG"; }
fi
touch "$DATA/boot.pending"

if [ -x "$BIN" ]; then
  nohup "$BIN" >> "$LOG" 2>&1 &
  echo $! > "$DATA/daemon.pid"
  sleep 1
  [ -f "$DATA/boot.pending" ] && rm -f "$DATA/boot.pending" && echo 0 > "$DATA/fail.count"
fi

# 频率/温控执行器（滑块与信仰模式的后端）
PERF=/data/adb/fuckorigin/bin/fo_perf.sh
if [ -x "$PERF" ]; then
  nohup setsid "$PERF" >/dev/null 2>&1 < /dev/null &
fi
