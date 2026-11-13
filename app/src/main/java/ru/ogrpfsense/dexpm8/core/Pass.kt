package ru.ogrpfsense.dexpm8.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Пароль сетевого каталога в базе (п. 10.12): AES-256/GCM, ключ — в `AndroidKeyStore`.
 *
 * Зачем: `catalogs.pass` лежал открытым текстом, и на этой прошивке (`ro.debuggable=1`,
 * `ro.crypto.type=unsupported`) его читал любой, кто воткнул книгу в компьютер: `run-as` для
 * debug-сборки, `adb root` — для любой. Шифрование поднимает планку: на диске только шифртекст,
 * ключ лежит в keystore, а не рядом с базой.
 *
 * Устройство (почему так):
 *  - **Не хеш.** Вход в каталог — HTTP Basic: серверу нужен сам пароль, а не отпечаток.
 *  - **GCM, а не CBC.** GCM проверяет целостность: подменённый шифртекст не превратится в
 *    «другой пароль», а честно не расшифруется ([open] вернёт "").
 *  - **Свой IV на каждое шифрование** — keystore выдаёт случайный: два одинаковых пароля
 *    не дают одинаковых строк в базе.
 *  - **Ключ не генерируется заново, если он уже есть.** Перегенерация = потеря всех паролей.
 *  - **Ключ в памяти.** `getEntry` на каждом чтении каталога — лишние миллисекунды на 1 ГБ ОЗУ.
 *  - **Формат строки — `v1:` + Base64(IV ‖ шифртекст).** Префикс нужен миграции: строку без
 *    него считаем старым открытым паролем и шифруем при обновлении базы.
 *
 * Предел защиты, чтобы не обманываться: ключ лежит в keystore на том же устройстве, и с
 * `adb root` на этой userdebug-прошивке его можно достать вместе с базой. Это заметно сложнее,
 * чем `select pass`, но абсолютной защиты без шифрования диска нет.
 *
 * Без `Context`, поэтому проходит `tools/typecheck.sh`; живая проверка — только на книге
 * (`docs/BUILD.md`, «Проверка шифрования пароля»).
 */
object Pass {

    /** Алиас ключа в keystore. Меняется только вместе с версией формата (`v1:` → `v2:`). */
    private const val ALIAS = "dexpm8.catalog.pass.v1"

    /** Метка формата в начале строки базы. */
    const val PREFIX = "v1:"

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val KEY_ALG = KeyProperties.KEY_ALGORITHM_AES
    private const val KEY_SIZE = 256
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    /** Ключ после первого обращения; null — ещё не читали из keystore. */
    @Volatile
    private var key: SecretKey? = null

    /** Строка уже в нашем формате (значит, это шифртекст, а не открытый пароль). */
    fun isSealed(s: String): Boolean = s.startsWith(PREFIX)

    /**
     * Зашифровать пароль для базы. Пустая строка — пустая строка (каталог без входа).
     * Ошибка keystore — тоже пустая строка: открытым текстом пароль не пишем никогда,
     * причина уходит в трассу. Такую строку миграция оставит как есть, а не сотрёт.
     */
    fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, secret())
            val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            val out = ByteArray(c.iv.size + body.size)
            System.arraycopy(c.iv, 0, out, 0, c.iv.size)
            System.arraycopy(body, 0, out, c.iv.size, body.size)
            PREFIX + Base64.encodeToString(out, Base64.NO_WRAP)
        } catch (t: Throwable) {
            Trace.step("pass seal FAIL: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    /**
     * Прочитать пароль из базы. Строку без [PREFIX] возвращаем как есть — запись, сделанная
     * до обновления. Не расшифровалось (ключ потерян, строка испорчена) — "", и экран B16
     * ответит «Нужен логин и пароль»: это честнее, чем молча ходить без входа.
     */
    fun open(stored: String): String {
        if (stored.isEmpty() || !isSealed(stored)) return stored
        return try {
            val raw = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            if (raw.size <= IV_BYTES) return ""
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (t: Throwable) {
            Trace.step("pass open FAIL: ${t.javaClass.simpleName}: ${t.message}")
            ""
        }
    }

    /**
     * Перешифровать открытые пароли, оставшиеся от версии 6 (`catalogs.pass` без префикса).
     * Вызывается из `onUpgrade` базы, поэтому читает и пишет через переданные функции.
     * Строку, которую зашифровать не удалось, не трогаем: стереть настоящий пароль хуже,
     * чем оставить его открытым до следующего запуска.
     *
     * @return сколько строк переписано
     */
    fun migrate(query: () -> List<Pair<Long, String>>, update: (Long, String) -> Unit): Int {
        var n = 0
        for ((id, stored) in query()) {
            if (stored.isEmpty() || isSealed(stored)) continue
            val sealed = seal(stored)
            if (sealed.isEmpty()) continue
            update(id, sealed)
            n++
        }
        if (n > 0) Trace.step("pass: перешифровано $n паролей каталогов")
        return n
    }

    private fun secret(): SecretKey {
        key?.let { return it }
        // Каталоги сохраняются с фонового потока: без synchronized можно создать два ключа,
        // и пароли, зашифрованные первым, вторым уже не прочитать.
        synchronized(this) {
            key?.let { return it }
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val found = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = found ?: generate()
            key = k
            return k
        }
    }

    private fun generate(): SecretKey {
        val gen = KeyGenerator.getInstance(KEY_ALG, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE)
                .build()
        )
        Trace.step("pass: ключ создан ($KEY_SIZE бит, $ALIAS)")
        return gen.generateKey()
    }
}
