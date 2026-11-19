package ru.ogrpfsense.dexpm8.core

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.provider.Settings
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Wi-Fi для списка сетей B61/B38 и подключения (B47, замечание 22).
 *
 * Зачем отдельный слой: `WifiManager` на Android 8.1 отдаёт список сетей только при выданном
 * `ACCESS_COARSE_LOCATION` **и** включённом определении местоположения (`Settings.Secure
 * LOCATION_MODE`); на книге оно выключено (`location_mode = null`), и без него `getScanResults()`
 * возвращает пусто, а `connectionInfo.ssid` — `<unknown ssid>`. Права запрашивает
 * `EinkActivity` (`requestLocation`), здесь только вызовы системы.
 *
 * Подключение — старый `WifiConfiguration` + `addNetwork`/`enableNetwork(id, true)` — обычный
 * порядок для API 27. **На книге ещё не проверено** (в том числе: вернётся ли книга к прежней
 * сети при неверном пароле). Всё, что ходит в систему, — в фоновом потоке экрана: сеть на E-Ink
 * не должна вешать кадр.
 *
 * «Подключаться автоматически» ([connect], `auto`): у обычного приложения на 8.1 нет вызова
 * «не подключаться к этой сети само». Поэтому при `auto = false` сеть после подключения не
 * сохраняется в список известных (`saveConfiguration` не зовём) — после перезагрузки книга её
 * забудет. Так ли это на прошивке — проверить на книге.
 */
class WifiScan(private val ctx: Context) {

    /** Одна сеть из сканирования. [level] — 0…4 для иконки, как `WifiManager.calculateSignalLevel`. */
    class Net(val ssid: String, val level: Int, val secured: Boolean)

    private val app = ctx.applicationContext

    private val wifi: WifiManager? get() = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    /**
     * Включено ли определение местоположения. `Settings.Secure.LOCATION_MODE` на книге не задан
     * (`null`) — это и есть «выключено», поэтому исключение читаем так же, как 0.
     */
    fun locationOn(): Boolean = try {
        Settings.Secure.getInt(app.contentResolver, Settings.Secure.LOCATION_MODE) != 0
    } catch (t: Throwable) {
        false
    }

    /**
     * Запустить сканирование. Возвращает `true`, если система приняла запрос: результат появится
     * не сразу (на книге ~2 с), поэтому читать список — отдельным [results] после паузы.
     */
    fun scan(): Boolean = try {
        wifi?.startScan() ?: false
    } catch (t: Throwable) {
        fail("scan", t); false
    }

    /**
     * Список сетей после [scan]: по одной строке на SSID (берём самую сильную точку), по убыванию
     * сигнала. Пусто — права на местоположение нет, определение выключено или сканирование сорвалось.
     */
    fun results(): Array<Net> {
        return try {
            val raw = wifi?.scanResults ?: return emptyArray()
            // Сеть с несколькими точками доступа приходит несколькими записями: берём сильнейшую.
            val dbm = HashMap<String, Int>(raw.size * 2)
            val locked = HashMap<String, Boolean>(raw.size * 2)
            for (r in raw) {
                val ssid = r.SSID
                if (ssid.isNullOrEmpty()) continue
                val was = dbm[ssid]
                if (was == null || r.level > was) {
                    dbm[ssid] = r.level
                    locked[ssid] = secured(r)
                }
            }
            val order = dbm.keys.sortedByDescending { dbm[it] }
            Array(order.size) { Net(order[it], bars(dbm[order[it]] ?: -100), locked[order[it]] ?: false) }
        } catch (t: Throwable) {
            fail("results", t)
            emptyArray()
        }
    }

    /**
     * Сеть требует пароль. `capabilities` — строка вида `[WPA2-PSK-CCMP][WPS][ESS]`; открытая
     * сеть сюда не попадает вовсе (`[ESS]`). WEP и EAP тоже считаем защищёнными: на 8.1 их
     * подключение просит пароль либо учётные данные.
     */
    private fun secured(r: android.net.wifi.ScanResult): Boolean {
        val c = r.capabilities ?: return false
        return c.contains("WPA") || c.contains("WEP") || c.contains("EAP") || c.contains("PSK")
    }

    /**
     * Итог [connect]: [id] — запись сети в системе (≥ 0, если её удалось создать), [error] —
     * причина отказа для листа B47 (`null` — запрос принят, ждём событий системы).
     */
    class Attempt(val id: Int, val error: String?)

    /**
     * Подключиться к сети [net]. Неверный пароль здесь не виден: система принимает запрос, а отказ
     * приходит позже событием `SUPPLICANT_STATE_CHANGED_ACTION` с `ERROR_AUTHENTICATING` — его
     * ловит экран и зовёт [forget] с [Attempt.id].
     */
    fun connect(net: Net, password: String, auto: Boolean): Attempt {
        val wm = wifi ?: return Attempt(-1, "Wi-Fi недоступен")
        // Android принимает только пароль WPA2 от 8 до 63 символов: на коротком `addNetwork`
        // возвращает −1, и без этой проверки лист молча закрывался бы (проверено на книге
        // 2026-11-19, `validatePsk failed: ascii string size too small`).
        if (net.secured && password.length !in 8..63) return Attempt(-1, "Пароль Wi-Fi — от 8 до 63 символов")
        return try {
            val cfg = WifiConfiguration()
            cfg.SSID = quote(net.ssid)
            if (net.secured) {
                cfg.preSharedKey = quote(password)
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            } else {
                cfg.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
            }
            // Запись с тем же SSID и типом защиты система не дублирует, а обновляет (тот же id).
            val id = wm.addNetwork(cfg)
            if (id < 0) return Attempt(-1, "Не удалось сохранить сеть")
            // Отдельный disconnect() не зовём: если enableNetwork откажет, книга осталась бы
            // без связи. enableNetwork(id, true) на 8.0+ сам переключает на выбранную сеть,
            // а при неудаче система возвращается к прежней (проверено на книге: ~20 с).
            if (!wm.enableNetwork(id, true)) {
                forget(id)
                return Attempt(-1, "Сеть не включилась")
            }
            wm.reconnect()
            Trace.step("wifi connect: '${net.ssid}' id=$id auto=$auto secured=${net.secured}")
            if (auto) wm.saveConfiguration()
            Attempt(id, null)
        } catch (t: Throwable) {
            fail("connect", t)
            Attempt(-1, "Не удалось подключиться")
        }
    }

    /**
     * Удалить запись сети [id], созданную оболочкой (неверный пароль): чужие записи система
     * удалить не даст, свои — да. Без этого в системе оставалась бы сеть «отключена: неверный
     * пароль», и повторная попытка шла бы через неё.
     */
    fun forget(id: Int) {
        if (id < 0) return
        try {
            val wm = wifi ?: return
            val ok = wm.removeNetwork(id)
            if (ok) wm.saveConfiguration()
            Trace.step("wifi forget: id=$id ok=$ok")
        } catch (t: Throwable) {
            fail("forget", t)
        }
    }

    /** SSID в `WifiConfiguration` — в кавычках; лишние кавычки из системы убираем. */
    private fun quote(s: String) = "\"" + s.trim('"') + "\""

    /** Уровень 0…4 для иконки: `calculateSignalLevel` на части прошивок падает на −127 dBm. */
    private fun bars(dbm: Int): Int = try {
        WifiManager.calculateSignalLevel(dbm, 5)
    } catch (t: Throwable) {
        1
    }

    private fun fail(what: String, t: Throwable) =
        Trace.step("wifi $what FAIL: ${t.javaClass.simpleName}: ${t.message}")
}
