# FuckOrigin Thermal — KernelSU 模块

解除 OriginOS 的温控限频，带 localhost HTML 控制台。

## 为什么不是简单写 sysfs

实测（iQOO Neo10 Pro+ / OriginOS 6 / SM8750）：

1. 95 个 `thermal_zone` 的句柄全握在 **`/vendor/bin/thermal-engine-v2`**（native 进程）手里，
   它周期性把限制写回去 —— 这是 LSPosed 够不到的层。
2. 只 `kill -STOP` 它**不会**撤销已经生效的限制。
3. 只把 `thermal_zone.mode` 写 `disabled` 也**不会**复位已调下去的 `cooling_device.cur_state`。

**三步缺一不可**：

```
kill -STOP thermal-engine-v2          # 止住实时复写
thermal_zone.mode = disabled          # 止住内核 governor
cooling_device.cur_state = 0          # 把已生效的限制复位（cpu-cluster0/1 + gpu）
```

实测效果：policy6 1401 → 4320 MHz，GPU 342 → 1100 MHz，并稳定保持。

## 装法

```bash
# 正常路径
ksud module install FuckOrigin-Thermal-v1.0.0.zip

# 如果装了 nomount 这类 metamodule，ksud 会拒绝安装（Metamodule installation blocked），
# 这时手动铺：
mkdir -p /data/adb/modules/fuckorigin-thermal
cp module.prop *.sh /data/adb/modules/fuckorigin-thermal/
mkdir -p /data/adb/fuckorigin/bin
cp bin/fuckorigind /data/adb/fuckorigin/bin/ && chmod 755 /data/adb/fuckorigin/bin/fuckorigind
```

重启后 `service.sh` 会拉起 daemon，控制台在 **http://127.0.0.1:8899**。

## 安全设计

- **默认不开启**：模块本身只起 daemon，不解锁。必须在控制台手动点。
- **温度兜底**：解锁期间每 1.5 秒检查 `tz_shell`，超过阈值（默认 52℃）自动交还热控。
- **可随时退出**：`交还热控` 按钮 / 卸载模块（uninstall.sh 会恢复所有 zone 与引擎）。
- **崩溃自愈**：`service.sh` 记录开机失败次数，连续 2 次自动 `touch disable` 禁用自己。

## HTTP 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/` | 控制台页面 |
| GET | `/api/status` | 引擎 / 冷却设备 / 频率 / 温度 / Thermal Status |
| GET | `/api/log` | 最近 200 条运行日志 |
| POST | `/api/unlock` | 解锁 |
| POST | `/api/relock` | 交还热控 |
| POST | `/api/threshold` | 改自动回退阈值（body 传数字） |

只绑定 `127.0.0.1`，不对外网开放。

## 两个踩到的坑（写进代码注释了）

1. **PATH**：daemon 调 `/system/bin/sh` 时必须 `.env("PATH", "/system/bin:/system/xbin:/vendor/bin")`。
   继承 Termux 的 PATH 会让 `pidof` / `cat` 解析到 Termux 版本，那些二进制依赖
   `libandroid-support.so`，在 root 下一律链接失败**并静默返回空**。

2. **`$(cat f)` vs `$(< f)`**：95 个热区用 `cat` 是 190 次 fork ≈ **1.4 秒**；
   换 mksh 内建的 `$(< f)` 只要 **45 毫秒**。控制台能 2 秒刷一次全靠这个。
   状态接口从 6.17 秒压到 **0.10 秒**。

## 实测数据（本机快照）

```
机身 50.8℃ · CPU 101.9℃ · GPU 74.1℃ · Thermal Status 4 (CRITICAL)
policy0 3532 MHz / 3532 MHz     policy6 4320 MHz / 4320 MHz
GPU 222 MHz（限频中）/ 1100 MHz 上限
```

> 拍照时机器在 120W 充电 + 高负载，所以 Thermal 4。这个温度下解除温控请自行斟酌。
