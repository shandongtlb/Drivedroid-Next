#!/system/bin/sh
# All argument values are single-quoted by RootShell.quote before this script.
# This script never changes SELinux policy, stops system services or deletes files.
set -eu

fail() {
    printf '%s\n' "$1" >&2
    exit 1
}

read_value() {
    if [ -r "$1" ]; then
        cat "$1"
    fi
}

probe() {
    printf 'root=%s\n' "$(id -u)"
    printf 'boot=%s\n' "$(read_value /proc/sys/kernel/random/boot_id)"
    printf 'model=%s\n' "$(getprop ro.product.model)"
    printf 'android=%s\n' "$(getprop ro.build.version.release)"
    printf 'sdk=%s\n' "$(getprop ro.build.version.sdk)"
    printf 'selinux=%s\n' "$(getenforce 2>/dev/null || true)"
    printf 'usb_config=%s\n' "$(getprop sys.usb.config)"
    printf 'usb_state=%s\n' "$(getprop sys.usb.state)"
    for root_path in /config/usb_gadget /sys/kernel/config/usb_gadget; do
        if [ -d "$root_path" ]; then
            printf 'base=%s\n' "$root_path"
            active_count=0
            for gadget_path in "$root_path"/*; do
                [ -f "$gadget_path/UDC" ] || continue
                controller="$(read_value "$gadget_path/UDC")"
                if [ -n "$controller" ]; then
                    if [ "${gadget_path##*/}" = dd_reborn ]; then
                        printf 'owned_udc=%s\n' "$controller"
                        printf 'owned_file=%s\n' "$(read_value "$gadget_path/functions/mass_storage.0/lun.0/file")"
                        printf 'owned_ro=%s\n' "$(read_value "$gadget_path/functions/mass_storage.0/lun.0/ro")"
                        printf 'owned_cdrom=%s\n' "$(read_value "$gadget_path/functions/mass_storage.0/lun.0/cdrom")"
                        printf 'owned_nofua=%s\n' "$(read_value "$gadget_path/functions/mass_storage.0/lun.0/nofua")"
                    else
                        active_count=$((active_count + 1))
                        printf 'original=%s\n' "$gadget_path"
                        printf 'udc=%s\n' "$controller"
                    fi
                fi
            done
            printf 'active_count=%s\n' "$active_count"
            break
        fi
    done
    for controller_path in /sys/class/udc/*; do
        if [ -e "$controller_path" ]; then
            printf 'available_udc=%s\n' "${controller_path##*/}"
            break
        fi
    done
    for legacy_path in /sys/class/android_usb/android0 /sys/devices/virtual/android_usb/android0; do
        if [ -f "$legacy_path/enable" ] && [ -f "$legacy_path/functions" ]; then
            for lun_path in "$legacy_path/f_mass_storage/lun" "$legacy_path/f_mass_storage/lun0"; do
                if [ -f "$lun_path/file" ]; then
                    printf 'legacy=%s\n' "$legacy_path"
                    printf 'lun=%s\n' "$lun_path"
                    printf 'functions=%s\n' "$(cat "$legacy_path/functions")"
                    printf 'enabled=%s\n' "$(cat "$legacy_path/enable")"
                    printf 'legacy_file=%s\n' "$(cat "$lun_path/file")"
                    printf 'legacy_ro=%s\n' "$(read_value "$lun_path/ro")"
                    printf 'legacy_cdrom=%s\n' "$(read_value "$lun_path/cdrom")"
                    printf 'legacy_nofua=%s\n' "$(read_value "$lun_path/nofua")"
                    printf 'old_ro=%s\n' "$(read_value "$lun_path/ro")"
                    printf 'old_cdrom=%s\n' "$(read_value "$lun_path/cdrom")"
                    break
                fi
            done
            break
        fi
    done
}

diagnose_write() {
    probe
    if [ -n "${IMAGE:-}" ] && [ -f "$IMAGE" ]; then
        printf '\nimage_path=%s\n' "$IMAGE"
        stat -c 'image_bytes=%s allocated_blocks_512=%b mode=%a uid=%u gid=%g' "$IMAGE" || true
        stat -f -c 'backing_filesystem=%T' "$IMAGE" || true
        ls -lZ "$IMAGE" || true
        df -k "$IMAGE" || true
    fi
    printf '\n--- USB / storage / permission kernel messages (read only) ---\n'
    if kernel_messages="$(dmesg 2>&1)"; then
        filtered="$(printf '%s\n' "$kernel_messages" | tail -n 600 | grep -Ei 'mass.storage|fsg|usb.*(error|fail)|f2fs.*(error|fail)|ext4.*(error|fail)|avc:.*denied|fscrypt|encrypt' | tail -n 80 || true)"
        if [ -n "$filtered" ]; then
            printf '%s\n' "$filtered"
        else
            printf 'No matching recent messages; this does not prove that writes succeeded.\n'
        fi
    else
        printf 'Kernel log unavailable: %s\n' "$kernel_messages"
    fi
}

check_path() {
    [ -f "$IMAGE" ] || fail '镜像路径不存在，或不是普通文件。'
    [ -s "$IMAGE" ] || fail '镜像为空。'
    [ -r "$IMAGE" ] || fail 'Root 无法读取此镜像。'
    if [ "$READONLY" = 0 ]; then
        [ -w "$IMAGE" ] || fail '此镜像不可写。'
        # O_RDWR without O_TRUNC; checks actual opening, without changing bytes.
        if ! (exec 3<> "$IMAGE"); then
            fail '无法以读写方式打开原镜像。请检查存储是否只读，或先导入应用镜像目录。'
        fi
    fi
}

verify_lun_mode() {
    checked_lun="$1"
    actual_ro="$(cat "$checked_lun/ro")"
    actual_cdrom=0
    if [ -f "$checked_lun/cdrom" ]; then
        actual_cdrom="$(cat "$checked_lun/cdrom")"
    fi
    printf 'actual_ro=%s\n' "$actual_ro"
    printf 'actual_cdrom=%s\n' "$actual_cdrom"
    if [ "$READONLY" = 0 ] && [ "$actual_ro" != 0 ]; then
        fail '内核没有接受可写挂载，已退回只读。请先导入应用目录再试；当前挂载会回滚，不会显示可写成功。'
    fi
    [ "$actual_ro" = "$READONLY" ] || fail '内核实际读写状态与所选模式不同，已取消挂载。'
    [ "$actual_cdrom" = "$CDROM" ] || fail '内核实际光驱状态与所选模式不同，已取消挂载。'
}

mount_configfs() {
    check_path
    own="$BASE/dd_reborn"
    if [ -f "$own/UDC" ] && [ -n "$(cat "$own/UDC")" ]; then
        fail '本应用的 USB 设备仍在使用，请先弹出。'
    fi
    mkdir -p "$own"
    printf '0x1d6b\n' > "$own/idVendor"
    printf '0x0104\n' > "$own/idProduct"
    printf '0x0200\n' > "$own/bcdUSB"
    printf '0x0100\n' > "$own/bcdDevice"
    mkdir -p "$own/strings/0x409"
    printf 'DriveDroid Reborn\n' > "$own/strings/0x409/manufacturer"
    printf 'USB Image Disk\n' > "$own/strings/0x409/product"
    printf 'DDREBORN0001\n' > "$own/strings/0x409/serialnumber"
    mkdir -p "$own/configs/c.1/strings/0x409"
    printf 'Mass Storage\n' > "$own/configs/c.1/strings/0x409/configuration"
    printf '250\n' > "$own/configs/c.1/MaxPower"
    mkdir -p "$own/functions/mass_storage.0"
    lun="$own/functions/mass_storage.0/lun.0"
    [ -d "$lun" ] || fail '内核没有提供 USB Mass Storage LUN。'
    [ -z "$(cat "$lun/file")" ] || fail 'USB LUN 中仍有镜像，请先弹出或恢复 USB。'
    switched=0
    rollback() {
        result=$?
        trap - EXIT HUP INT TERM
        if [ "$switched" = 1 ]; then
            printf '\n' > "$own/UDC" || true
            if [ -n "$ORIGINAL" ] && [ -z "$(read_value "$ORIGINAL/UDC")" ]; then
                printf '%s\n' "$UDC" > "$ORIGINAL/UDC" || true
            fi
        fi
        # Preparation can fail after opening the image but before switching UDC.
        printf '\n' > "$lun/file" || true
        exit "$result"
    }
    trap rollback EXIT HUP INT TERM
    printf '1\n' > "$lun/removable"
    printf '%s\n' "$CDROM" > "$lun/cdrom"
    printf '%s\n' "$READONLY" > "$lun/ro"
    printf '%s\n' "$IMAGE" > "$lun/file"
    verify_lun_mode "$lun"
    if [ ! -L "$own/configs/c.1/mass_storage.0" ]; then
        ln -s "$own/functions/mass_storage.0" "$own/configs/c.1/mass_storage.0"
    fi
    if [ -n "$ORIGINAL" ]; then
        [ "$(cat "$ORIGINAL/UDC")" = "$UDC" ] || fail '系统 USB 配置已经改变，请重新检测。'
        switched=1
        printf '\n' > "$ORIGINAL/UDC"
    else
        switched=1
    fi
    printf '%s\n' "$UDC" > "$own/UDC"
    sleep 1
    [ "$(cat "$own/UDC")" = "$UDC" ] || fail '系统 USB 服务接管了连接，当前 ROM 不支持此切换方式。'
    [ -n "$(cat "$lun/file")" ] || fail '镜像未成功挂载。'
    verify_lun_mode "$lun"
    switched=0
    trap - EXIT HUP INT TERM
    printf 'mounted=configfs\n'
}

unmount_configfs() {
    own="$BASE/dd_reborn"
    if [ -f "$own/functions/mass_storage.0/lun.0/file" ]; then
        mounted_file="$(cat "$own/functions/mass_storage.0/lun.0/file")"
        if [ -n "$mounted_file" ] && [ ! "$mounted_file" -ef "$IMAGE" ]; then
            fail 'USB 镜像已被其他进程改变，拒绝弹出不属于本次会话的镜像。'
        fi
    fi
    if [ -f "$own/UDC" ] && [ -n "$(cat "$own/UDC")" ]; then
        [ "$(cat "$own/UDC")" = "$UDC" ] || fail 'USB 控制器已改变，拒绝断开其他连接。'
        printf '\n' > "$own/UDC"
    fi
    release_result=0
    if [ -f "$own/functions/mass_storage.0/lun.0/file" ]; then
        printf '\n' > "$own/functions/mass_storage.0/lun.0/file" || release_result=1
    fi
    if [ -n "$ORIGINAL" ]; then
        current="$(cat "$ORIGINAL/UDC")"
        if [ -z "$current" ]; then
            printf '%s\n' "$UDC" > "$ORIGINAL/UDC"
        elif [ "$current" != "$UDC" ]; then
            fail '原 USB 配置已被系统改变，恢复记录已保留。'
        fi
    fi
    [ "$release_result" = 0 ] || fail 'USB 已恢复，但镜像仍被内核占用，请重试弹出。'
    printf 'unmounted=1\n'
}

mount_legacy() {
    check_path
    [ -z "$(cat "$LUN/file")" ] || fail '此 USB LUN 已被使用。'
    [ "$(cat "$LEGACY/functions")" = "$FUNCTIONS" ] || fail 'USB 配置已经改变。'
    rollback_legacy() {
        result=$?
        trap - EXIT HUP INT TERM
        printf '0\n' > "$LEGACY/enable" || true
        printf '\n' > "$LUN/file" || true
        if [ -f "$LUN/cdrom" ]; then
            printf '%s\n' "$OLD_CDROM" > "$LUN/cdrom" || true
        fi
        printf '%s\n' "$OLD_RO" > "$LUN/ro" || true
        printf '%s\n' "$FUNCTIONS" > "$LEGACY/functions" || true
        printf '%s\n' "$ENABLED" > "$LEGACY/enable" || true
        exit "$result"
    }
    trap rollback_legacy EXIT HUP INT TERM
    printf '0\n' > "$LEGACY/enable"
    printf 'mass_storage\n' > "$LEGACY/functions"
    if [ -f "$LUN/cdrom" ]; then
        printf '%s\n' "$CDROM" > "$LUN/cdrom"
    else
        [ "$CDROM" = 0 ] || fail '此内核不支持光驱模式。'
    fi
    printf '%s\n' "$READONLY" > "$LUN/ro"
    printf '%s\n' "$IMAGE" > "$LUN/file"
    verify_lun_mode "$LUN"
    printf '1\n' > "$LEGACY/enable"
    [ -n "$(cat "$LUN/file")" ] || fail '镜像未成功挂载。'
    verify_lun_mode "$LUN"
    trap - EXIT HUP INT TERM
    printf 'mounted=legacy\n'
}

unmount_legacy() {
    mounted_file="$(cat "$LUN/file")"
    if [ -n "$mounted_file" ] && [ ! "$mounted_file" -ef "$IMAGE" ]; then
        fail '此 LUN 已被其他镜像占用，拒绝覆盖。'
    fi
    printf '0\n' > "$LEGACY/enable"
    release_result=0
    printf '\n' > "$LUN/file" || release_result=1
    if [ -f "$LUN/cdrom" ]; then
        printf '%s\n' "$OLD_CDROM" > "$LUN/cdrom" || release_result=1
    fi
    printf '%s\n' "$OLD_RO" > "$LUN/ro" || release_result=1
    printf '%s\n' "$FUNCTIONS" > "$LEGACY/functions"
    printf '%s\n' "$ENABLED" > "$LEGACY/enable"
    [ "$release_result" = 0 ] || fail 'USB 功能已恢复，但 LUN 尚未完全释放，请重试。'
    printf 'unmounted=1\n'
}

[ "$(id -u)" = 0 ] || fail '没有获得 Root 权限。'
case "$ACTION" in
    diagnose_write)
        diagnose_write
        ;;
    probe)
        probe
        ;;
    stat)
        [ -f "$IMAGE" ] || fail '请选择一个存在的 ISO / IMG 普通文件。'
        printf 'bytes=%s\n' "$(stat -c %s "$IMAGE")"
        ;;
    mount_configfs)
        mount_configfs
        ;;
    unmount_configfs)
        unmount_configfs
        ;;
    mount_legacy)
        mount_legacy
        ;;
    unmount_legacy)
        unmount_legacy
        ;;
    *)
        fail '未知 USB 操作。'
        ;;
esac
