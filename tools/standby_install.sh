#!/usr/bin/env bash
# Картинка на выключенном экране (B77 «Книга закрыта»).
#
# Заводские картинки «Режим ожидания» и «Выключено» лежат в разделе прошивки
# `/vendor/media/*.jpg` и читаются композитором (`hwcomposer.rk30board.so`) при гашении
# панели. Приложение их не переопределяет — нужен root и запись в /vendor, поэтому скрипт
# запускается с машины по adb, а не из оболочки.
#
#   tools/standby_install.sh                 поставить свои картинки из design/boot
#   tools/standby_install.sh --restore       вернуть заводские из копии на книге
#   tools/standby_install.sh --pull-only     только снять заводские копии на книгу
#
# Что делает установка:
#   1. снимает заводские файлы в /sdcard/dexp-m8-boot/ (копия, если её ещё нет);
#   2. перемонтирует /vendor в rw;
#   3. кладёт design/boot/standby.jpg и poweroff.jpg на место заводских;
#   4. sync и ro обратно (после перезагрузки /vendor и так снова ro).
#
# Требования к файлам (проверены на книге 2026-11-18):
#   - размер 1872×1404 — поворот на 90° против панели, как у заводских;
#   - оттенки серого (JPEG, mode L), не RGB: RGB-файл композитор не рисует;
#   - до ~900 КБ: файл 1,1 МБ не читается, 861 КБ работает;
#   - ч/б без полутонов, без прозрачности.
#
# `adb reboot -p` картинку НЕ показывает: это путь мимо PowerManager. Проверять только
# выключением через меню питания (долгое нажатие → «Выключить»).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/design/boot"
BACKUP=/sdcard/dexp-m8-boot
PHOTOS=(standby.jpg poweroff.jpg)

adb_shell() { adb shell "$@"; }
su_sh() { adb shell "su 0 $*"; }

need() {
    command -v adb >/dev/null 2>&1 || { echo "нет adb в PATH"; exit 1; }
    adb get-state >/dev/null 2>&1 || { echo "книга не подключена (adb devices)"; exit 1; }
}

pull_backup() {
    su_sh "mkdir -p $BACKUP"
    for f in "${PHOTOS[@]}"; do
        if ! su_sh "ls $BACKUP/$f" >/dev/null 2>&1; then
            su_sh "cp /vendor/media/$f $BACKUP/$f" || true
        fi
    done
    su_sh "chmod 644 $BACKUP/*.jpg" || true
    echo "заводские копии: $BACKUP"
}

case "${1:-}" in
    --restore)
        need
        for f in "${PHOTOS[@]}"; do
            su_sh "mount -o rw,remount /vendor"
            su_sh "cp $BACKUP/$f /vendor/media/$f" \
                || { echo "нет копии $BACKUP/$f"; exit 1; }
        done
        su_sh "sync"
        su_sh "mount -o ro,remount /vendor" || true
        echo "заводские картинки возвращены"
        ;;
    --pull-only)
        need
        pull_backup
        ;;
    "")
        need
        for f in "${PHOTOS[@]}"; do
            [ -f "$SRC/$f" ] || { echo "нет $SRC/$f"; exit 1; }
        done
        pull_backup
        for f in "${PHOTOS[@]}"; do
            adb push "$SRC/$f" "/sdcard/$f" >/dev/null
            su_sh "mount -o rw,remount /vendor"
            su_sh "cp /sdcard/$f /vendor/media/$f"
            su_sh "rm -f /sdcard/$f"
        done
        su_sh "sync"
        su_sh "mount -o ro,remount /vendor" || true
        echo "поставлено. Проверять выключением через меню питания, не adb reboot -p"
        ;;
    *)
        echo "непонятный ключ: $1"; exit 2
        ;;
esac
