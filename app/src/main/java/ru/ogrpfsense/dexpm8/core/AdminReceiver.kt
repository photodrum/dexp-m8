package ru.ogrpfsense.dexpm8.core

import android.app.admin.DeviceAdminReceiver

/**
 * Администратор устройства с одной политикой — `force-lock` (res/xml/device_admin.xml).
 * Нужен только для «Сна» по кнопке (`Sys.lockNow`, B5). Пока админ включён, удалить
 * оболочку можно только после его отключения (Настройки → Безопасность → Администраторы);
 * `adb install -r` это не мешает.
 */
class AdminReceiver : DeviceAdminReceiver()
