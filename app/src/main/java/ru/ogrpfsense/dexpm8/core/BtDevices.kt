package ru.ogrpfsense.dexpm8.core

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Bluetooth-устройства для B74 и строки «Сопряжённые устройства» на B38 (п. 13).
 * Только вызовы системы; право на местоположение (нужно поиску на 8.1) спрашивает
 * `EinkActivity.requestLocation`, события слушает экран.
 *
 * Что открыто обычному приложению на Android 8.1 (API 27) и чем пользуемся:
 *
 *   список сопряжённых         `getBondedDevices` — BLUETOOTH
 *   поиск рядом                `startDiscovery` — BLUETOOTH_ADMIN; `ACTION_FOUND` приходит
 *                              только при выданном ACCESS_COARSE_LOCATION
 *   сопряжение                 `createBond` — BLUETOOTH_ADMIN; диалог PIN рисует система
 *   подключение/отключение     скрытые `connect`/`disconnect` у прокси A2DP, HEADSET, HID (4) —
 *                              рефлексия; на 8.1 они проверяют только BLUETOOTH_ADMIN
 *   «подключено»               состояние профилей; до прихода прокси — скрытый `isConnected` (ACL)
 *   заряд                      скрытый `BluetoothDevice.getBatteryLevel` (новый в 8.1)
 *   «видна другим»             скрытый `BluetoothAdapter.setScanMode(mode, duration)`; отказ
 *                              прошивки — просто не видна
 *
 * Ограничений на скрытые методы до P (API 28) нет. Каждый вызов в `try`: на другой прошивке
 * строка теряет подробность (заряд, «видна»), экран не падает.
 */
class BtDevices(ctx: Context) {

    companion object {
        /** Скрытые действия API 27: заряд гарнитуры и состояние профиля HID. */
        const val ACTION_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        const val ACTION_INPUT_STATE = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"

        /** `BluetoothProfile.INPUT_DEVICE` — скрытая константа: пульты и клавиатуры. */
        private const val PROFILE_INPUT = 4

        /** Видимость для других устройств, с — «2 мин» в макете B74. */
        const val DISCOVERABLE_S = 120
    }

    /**
     * Вид устройства по классу Bluetooth: подпись в «ДОСТУПНЫЕ» и окончание для «Подключен…» /
     * «Сопряжён…» ([end]: «ы» — мн. ч., «а» — ж. р., «о» — ср. р., пусто — м. р.).
     */
    enum class Kind(val label: String, val end: String) {
        HEADPHONES("Наушники", "ы"),
        SPEAKER("Колонка", "а"),
        CAR("Автомагнитола", "а"),
        TV("Телевизор", ""),
        KEYBOARD("Клавиатура", "а"),
        MOUSE("Мышь", "а"),
        REMOTE("Пульт", ""),
        PHONE("Телефон", ""),
        COMPUTER("Компьютер", ""),
        OTHER("Устройство", "о")
    }

    private val app = ctx.applicationContext

    private val adapter: BluetoothAdapter?
        get() = try {
            BluetoothAdapter.getDefaultAdapter()
        } catch (t: Throwable) {
            null
        }

    // ------------------------------------------------------------ адаптер

    /** `BluetoothAdapter.STATE_*`; нет адаптера — STATE_OFF. */
    fun state(): Int = try {
        adapter?.state ?: BluetoothAdapter.STATE_OFF
    } catch (t: Throwable) {
        fail("state", t); BluetoothAdapter.STATE_OFF
    }

    fun on(): Boolean = state() == BluetoothAdapter.STATE_ON

    fun visible(): Boolean = try {
        adapter?.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
    } catch (t: Throwable) {
        false
    }

    /**
     * Видна другим на [DISCOVERABLE_S] секунд ([on] = true) или снова только «подключаемая».
     * Скрытый `setScanMode`; false — прошивка отказала (тогда в строке нет «видна»).
     */
    fun setVisible(on: Boolean): Boolean {
        val a = adapter ?: return false
        val int = Int::class.javaPrimitiveType
        return try {
            val r = if (on) {
                a.javaClass.getMethod("setScanMode", int, int)
                    .invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, DISCOVERABLE_S)
            } else {
                a.javaClass.getMethod("setScanMode", int)
                    .invoke(a, BluetoothAdapter.SCAN_MODE_CONNECTABLE)
            }
            val ok = r == true
            Trace.step("bt: visible $on -> $ok")
            ok
        } catch (t: Throwable) {
            fail("visible", t); false
        }
    }

    // ------------------------------------------------------------ устройства

    /** Сопряжённые; при выключенном Bluetooth система отдаёт пусто. */
    fun bonded(): List<BluetoothDevice> = try {
        adapter?.bondedDevices?.toList() ?: emptyList()
    } catch (t: Throwable) {
        fail("bonded", t); emptyList()
    }

    /** Имя устройства; нет имени (бывает у найденных) — адрес. */
    fun name(d: BluetoothDevice): String {
        val n = try {
            d.name
        } catch (t: Throwable) {
            null
        }
        return if (n.isNullOrBlank()) d.address else n.trim()
    }

    fun hasName(d: BluetoothDevice): Boolean = try {
        !d.name.isNullOrBlank()
    } catch (t: Throwable) {
        false
    }

    /** Устройство по адресу (для ожиданий экрана); неверный адрес — null. */
    fun device(addr: String): BluetoothDevice? = try {
        adapter?.getRemoteDevice(addr)
    } catch (t: Throwable) {
        null
    }

    fun bondState(d: BluetoothDevice): Int = try {
        d.bondState
    } catch (t: Throwable) {
        BluetoothDevice.BOND_NONE
    }

    /**
     * Значение строки «Сопряжённые устройства» на B38: имя подключённого (в макете «JBL Go 3»),
     * иначе число сопряжённых или «Нет устройств»; Bluetooth выключен — прочерк.
     */
    fun summary(): String {
        if (!on()) return "—"
        val list = bonded()
        if (list.isEmpty()) return "Нет устройств"
        for (d in list) if (connected(d)) return name(d)
        return ru.ogrpfsense.dexpm8.ui.Words.devices(list.size)
    }

    fun kind(d: BluetoothDevice): Kind {
        val c = try {
            d.bluetoothClass
        } catch (t: Throwable) {
            null
        } ?: return Kind.OTHER
        return when (c.majorDeviceClass) {
            // Наушники — только по своим классам: телевизор Sony (KD-49XG8096, класс видео) на
            // книге 2026-11-19 подписывался «Наушники», пока всё остальное шло в наушники.
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (c.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Kind.HEADPHONES
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Kind.CAR
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Kind.SPEAKER
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_MONITOR,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX,
                BluetoothClass.Device.AUDIO_VIDEO_VCR,
                BluetoothClass.Device.AUDIO_VIDEO_CAMCORDER,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CAMERA,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_CONFERENCING,
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_GAMING_TOY -> Kind.TV
                else -> Kind.OTHER
            }
            // Младшие биты периферии: 0x40 — клавиатура, 0x80 — указатель. Пульт перелистывания
            // обычно представляется клавиатурой — так и подписываем.
            BluetoothClass.Device.Major.PERIPHERAL -> when {
                c.deviceClass and 0x40 != 0 -> Kind.KEYBOARD
                c.deviceClass and 0x80 != 0 -> Kind.MOUSE
                else -> Kind.REMOTE
            }
            BluetoothClass.Device.Major.PHONE -> Kind.PHONE
            BluetoothClass.Device.Major.COMPUTER -> Kind.COMPUTER
            else -> Kind.OTHER
        }
    }

    /** Подключено: любой профиль в STATE_CONNECTED; прокси ещё нет — скрытый `isConnected` (ACL). */
    fun connected(d: BluetoothDevice): Boolean {
        if (proxies.isNotEmpty()) {
            for (p in proxies.values) {
                try {
                    if (p.getConnectionState(d) == BluetoothProfile.STATE_CONNECTED) return true
                } catch (ignored: Throwable) {
                }
            }
        }
        return try {
            d.javaClass.getMethod("isConnected").invoke(d) == true
        } catch (t: Throwable) {
            false
        }
    }

    /** Заряд 0…100 или −1 — устройство не сообщает (скрытый `getBatteryLevel`, API 27). */
    fun battery(d: BluetoothDevice): Int = try {
        (d.javaClass.getMethod("getBatteryLevel").invoke(d) as? Int)?.takeIf { it in 0..100 } ?: -1
    } catch (t: Throwable) {
        -1
    }

    // ------------------------------------------------------------ поиск и сопряжение

    fun discovering(): Boolean = try {
        adapter?.isDiscovering ?: false
    } catch (t: Throwable) {
        false
    }

    /** Поиск заново (~12 с у системы). Итог — `ACTION_FOUND` и `ACTION_DISCOVERY_FINISHED`. */
    fun discover(): Boolean {
        val a = adapter ?: return false
        return try {
            if (a.isDiscovering) a.cancelDiscovery()
            val ok = a.startDiscovery()
            Trace.step("bt: discover -> $ok")
            ok
        } catch (t: Throwable) {
            fail("discover", t); false
        }
    }

    fun stopDiscovery() {
        try {
            val a = adapter ?: return
            if (a.isDiscovering) a.cancelDiscovery()
        } catch (t: Throwable) {
            fail("stop discovery", t)
        }
    }

    /** Сопряжение; поиск при этом мешает — останавливаем. Итог — `ACTION_BOND_STATE_CHANGED`. */
    fun bond(d: BluetoothDevice): Boolean {
        stopDiscovery()
        return try {
            val ok = d.createBond()
            Trace.step("bt: bond ${d.address} -> $ok")
            ok
        } catch (t: Throwable) {
            fail("bond", t); false
        }
    }

    // ------------------------------------------------------------ профили

    private val proxies = HashMap<Int, BluetoothProfile>(3)

    /** Прокси профиля пришёл (асинхронно, в UI-потоке) — экран перерисует строки. */
    var onProfiles: (() -> Unit)? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            proxies[profile] = proxy
            onProfiles?.invoke()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxies.remove(profile)
        }
    }

    /** Открыть прокси A2DP, HEADSET и HID — без них подключать нечем. Парно с [close]. */
    fun open() {
        val a = adapter ?: return
        for (p in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_INPUT)) {
            if (proxies.containsKey(p)) continue
            try {
                a.getProfileProxy(app, listener, p)
            } catch (t: Throwable) {
                fail("proxy $p", t)
            }
        }
    }

    fun close() {
        val a = adapter
        for ((p, proxy) in proxies) {
            try {
                a?.closeProfileProxy(p, proxy)
            } catch (ignored: Throwable) {
            }
        }
        proxies.clear()
    }

    /**
     * Подключить по профилям, подходящим виду: колонка — A2DP, наушники и машина — A2DP и HEADSET,
     * пульт и клавиатура — HID, неизвестное — все три. true — хоть один профиль принял запрос;
     * итог приходит событиями ACL и профилей.
     */
    fun connect(d: BluetoothDevice): Boolean {
        stopDiscovery()
        val want = when (kind(d)) {
            Kind.SPEAKER -> intArrayOf(BluetoothProfile.A2DP)
            Kind.HEADPHONES, Kind.CAR -> intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)
            Kind.KEYBOARD, Kind.MOUSE, Kind.REMOTE -> intArrayOf(PROFILE_INPUT)
            else -> intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET, PROFILE_INPUT)
        }
        var ok = false
        for (p in want) {
            val proxy = proxies[p] ?: continue
            if (call(proxy, "connect", d)) ok = true
        }
        Trace.step("bt: connect ${d.address} ${kind(d)} proxies=${proxies.keys} -> $ok")
        return ok
    }

    /** Отключить все профили, где устройство не в STATE_DISCONNECTED. */
    fun disconnect(d: BluetoothDevice): Boolean {
        var ok = false
        for (proxy in proxies.values) {
            val st = try {
                proxy.getConnectionState(d)
            } catch (t: Throwable) {
                BluetoothProfile.STATE_DISCONNECTED
            }
            if (st == BluetoothProfile.STATE_DISCONNECTED) continue
            if (call(proxy, "disconnect", d)) ok = true
        }
        Trace.step("bt: disconnect ${d.address} -> $ok")
        return ok
    }

    private fun call(proxy: BluetoothProfile, method: String, d: BluetoothDevice): Boolean = try {
        proxy.javaClass.getMethod(method, BluetoothDevice::class.java).invoke(proxy, d) == true
    } catch (t: Throwable) {
        fail("$method ${proxy.javaClass.simpleName}", t); false
    }

    private fun fail(what: String, t: Throwable) {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
        Trace.step("bt: $what FAIL ${cause.javaClass.simpleName}: ${cause.message}")
    }
}

