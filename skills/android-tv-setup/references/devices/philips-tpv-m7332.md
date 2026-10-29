# MediaTek m7332, TPV firmware, Philips UHD Android TV (Android 14)

Seen on: Philips 43", product `PH0M_EA_T32_2G`, board `PH0M_EA_T32`, build `UTT1.240424.001`,
security patch 2025-07-05, kernel 4.19.195 built 2025-08-19, 1.69 GB RAM, armeabi-v7a.
Panel 3840x2160, UI runs at an override size of 1920x1080, density 320. ODM: TPV (`org.droidtv.*`, `com.tpv.*`).
Wired Ethernet; the set has no microphone (`android.hardware.microphone` absent) and no built-in camera.

## Result

Debloat and the launcher takeover both worked and survived a reboot. USB storage does not work at all on this
set, for reasons unrelated to any package (see below). The owner's complaint was slowness; the cause was RAM,
not storage and not the CPU.

## What was actually slow

```
MemTotal      1 727 336K        ro.config.low_ram = true
Free RAM        396 552K  -> after cleanup 450 447K
Used RAM      1 495 413K  -> after cleanup 1 267 685K
ZRAM            289 004K  -> after cleanup 210 700K   (data held in compressed swap)
/data          7.2G, 39% used                         storage was never the problem
CPU            351% idle of 400%                      nor was the CPU
```

`load average` sits at ~60 permanently. That is not load: roughly sixty MStar/MediaTek kernel threads
(`DMX_Filt_Task`, `HDMI Rx task`, `VMon_Task`, `CEC`, `Keypad_Check`, …) live in uninterruptible sleep by design.
Reading it as load produces a confident, wrong diagnosis.

The set reaches ~250 MB of ZRAM within twelve minutes of a cold boot, so "it got clogged over time" is wrong too —
1.69 GB is simply not enough for the stock process set, and a reboot buys almost nothing.

## Packages

| Package                          | What it is                                                    | Verdict                    |
| -------------------------------- | ------------------------------------------------------------- | -------------------------- |
| `org.droidtv.candeebug`          | Philips diagnostics agent, 2278 alarms/day, ~10 min CPU       | disabled                   |
| `org.droidtv.gel`                | Usage statistics, 1542 `GATHERING_ALARM_INTENT`               | disabled                   |
| `org.droidtv.tv.frameworklogger` | Firmware event logger                                         | disabled                   |
| `org.droidtv.nettvregistration`  | Philips TV registration, 3 services                           | disabled                   |
| `org.droidtv.eum`                | End User Measurement telemetry                                | disabled                   |
| `org.droidtv.nettv.market`       | Philips app gallery                                           | disabled                   |
| `org.droidtv.fastip`             | Ad-supported IP channels, asks for `COARSE_LOCATION`          | disabled                   |
| `com.tpv.xmic.ots.cms.client`    | TPV CMS client, version string `0.6.04_ad`, holds `READ_LOGS` | disabled                   |
| `org.droidtv.icp`                | `DEVICE_POWER`, `SHUTDOWN`, `SET_TIME`, `SysoProvider`        | keep, it is infra          |
| `org.droidtv.ambisvc`            | Ambilight                                                     | keep                       |
| `com.mediatek.wwtv.tvcenter`     | TV app; the only handler of source switching, HDMI included   | never disable              |
| `com.mediatek.tvinput`           | Input services: HDMI, Component, Composite, SCART, VGA        | keep                       |
| `mediatek.factorymenu.ui`        | Factory menu                                                  | never touch                |
| `com.mediatek.tv.factory`        | Factory command service                                       | never touch                |
| `com.google.android.tvlauncher`  | Google TV home screen                                         | disabled, for the takeover |

Also disabled on request, all ordinary apps: Disney+, Prime Video, Rakuten TV, SPB TV Velcom, YouTube Music,
Play Games, Chromecast built-in (`mediashell`), the stock AirPlay receiver (`com.waxrain.airplayer2`),
Google Assistant (`katniss`) and home-screen recommendations (`tvrecommendations`).

Play Store was deliberately kept: without it the streaming apps stop receiving updates and eventually refuse
to run. Bluetooth was kept too — no devices are paired and the remote is infrared, but Android TV raises
repeated "Bluetooth has stopped" errors when the component is missing, and the saving is only 8.7 MB.

### Do not disable the TV app, even with no aerial

The owner has no aerial, so disabling `com.mediatek.wwtv.tvcenter` looked free. It is not. That package is the
only handler of `ACTION_VIEW` on `vnd.android.cursor.dir/channel` and on passthrough URIs, which is how *any*
source is opened:

```
am start -a android.intent.action.VIEW -d content://android.media.tv/passthrough/...HDMIInputService/HW5
→ Error: Activity not started, unable to resolve Intent
```

The owner saw `ActivityNotFoundException` when switching to HDMI from the remote as well, so the hardware
Source button goes through the same path rather than bypassing Android. Re-enabled within minutes.

## Home takeover

HOME candidates here, with priorities:

```
2       com.google.android.tvlauncher/.MainActivity
0       our launcher
-1000   com.android.tv.settings/.system.FallbackHome
```

`com.google.android.tungsten.setupwraith` is installed but is **not** a HOME candidate on this firmware, so the
`RecoveryActivity` black-screen loop described for Google TV sets does not apply. `set-home-activity` prints
`Success` and changes nothing, as expected at priority 0. Disabling `tvlauncher` alone is enough; our launcher
then wins over `FallbackHome`, and it survives a reboot.

After a **cold boot** the set opens `com.mediatek.wwtv.tvcenter`, not Home, and no setting for the startup
source was found in `settings` or `getprop`. **Standby does not do this**: powering off and on with the remote
returns to whatever was on screen, so in day-to-day use the owner lands on the home screen.

## USB storage does not work on this set

Three different drives, one port, nothing mounts. The cause is not the filesystem and not the partition table:

- Hot-plug is not handled at all. With a mouse in the port the bus shows `vendor_id=7119`; after a reboot with a
  flash drive inserted it shows `vendor_id=2385` (Kingston) together with `sda`, `sda1`, `sda2`, `scsi_host/host0`
  and `disk:8,0`. Inserting either device into a running TV produces no bus change whatsoever.
- Even inserted before power-on, the drive drops off the bus within a minute:

```
09:42:28  ActivityManager: Start proc com.android.externalstorage … MountReceiver
09:43:12  vold: Failed to read vendor from /sys/…/block/sda/device/vendor: No such file or directory
09:43:12  vold: sgdisk failed to scan /dev/block/vold/disk:8,0   exit code 8
09:43:12  TPVTvService: DISK_SCANNED error … DiskInfo.size on a null object reference
```

`sgdisk` fails because the sysfs path is already gone, not because the layout is odd. Reformatting the drive as
MBR + NTFS (both supported — `/proc/filesystems` lists `vfat`, `ntfs`, `fuseblk`, no exFAT) changed nothing.

The port itself is healthy: a HID mouse enumerates, registers four input devices and declares `max_power=500`.
The port is on an EHCI (USB 2.0) controller. A powered hub is the next thing to try; the kernel ring buffer would
say whether this is a supply or a controller fault, and it needs root.

## ADB quirks

- Network ADB dies in standby and does not come back until the set is woken. A reboot that ends in standby
  therefore looks like "the TV never came back".
- DHCP moved the set three times during one session. Find it again by scanning the subnet for an open 5555 and
  confirm with `getprop ro.serialno` before touching anything.
- `/sdcard` does not exist in the shell's mount namespace and `sm list-volumes` reports `emulated;0 unmounted`.
  This is normal here — it was the same before any package was disabled.
- `screencap` occasionally writes a zero-byte file; check the size before pulling.
- `/vendor/bin/set_wopacket_port` crashes once an hour with a linker error about `libcutils.so`. It is a firmware
  defect, unrelated to debloating, and predates any change.

## Locale

`be-BY` is in the firmware's language list and switching to it works. Android's own settings then fall back to
**English**, because Google ships no Belarusian translation for them. The launcher formats its date with
`new Locale("be")` so it stays Belarusian whatever the system language is.
