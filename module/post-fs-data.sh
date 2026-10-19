#!/system/bin/sh
# FuckOrigin：在 zygote 之前把 gamecube 配置覆盖挂上。
# 应用进程各自有独立挂载命名空间，运行期 mount --bind 只对单个进程有效，
# 必须在这个时机做，之后 fork 出来的进程才会继承。
MODDIR=${0%/*}
SRC="$MODDIR/system/etc/gamecube"
for name in SuperResolutionConfigList.xml FrameInterConfigList.xml; do
  if [ -f "$SRC/$name" ] && [ -f "/system/etc/gamecube/$name" ]; then
    mount --bind "$SRC/$name" "/system/etc/gamecube/$name" 2>/dev/null
  fi
done
exit 0
