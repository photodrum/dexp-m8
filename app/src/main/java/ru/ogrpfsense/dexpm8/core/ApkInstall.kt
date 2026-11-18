package ru.ogrpfsense.dexpm8.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import ru.ogrpfsense.dexpm8.ui.Trace

/**
 * Установка скачанного обновления (B76, п. 13).
 *
 * **Почему `PackageInstaller`, а не `ACTION_INSTALL_PACKAGE`.** На API 27 у `Intent` с `file://`
 * срабатывает `FileUriExposedException`, а `android.support.v4.content.FileProvider` в проекте
 * нет (AndroidX не подключаем). Сессия `PackageInstaller` берёт APK как поток и обходится без
 * провайдера вовсе. Побочный плюс: система сама показывает свой диалог подтверждения, и
 * результат приходит в наш приёмник — экран не висит в неизвестности.
 *
 * **Право.** С Android 8 установка вне маркета требует разрешения «Неизвестные источники»
 * для конкретного приложения (`REQUEST_INSTALL_PACKAGES` в манифесте + выдача человеком).
 * Проверка — [canInstall], экран установки открывает [sourcesIntent].
 *
 * **Подпись.** APK обязан быть подписан тем же ключом, что установлен на книге: иначе
 * `INSTALL_FAILED_UPDATE_INCOMPATIBLE` и данные пришлось бы снести (`docs/BUILD.md`,
 * «Смена подписи»). Поэтому первым делом проверяем сумму в `UpdateCheck.download`.
 */
object ApkInstall {

    /** Код результата в `Intent` приёмника. */
    const val STATUS = "ru.ogrpfsense.dexpm8.INSTALL_STATUS"

    /**
     * Итог установки приходит сюда. Приёмник объявлен в манифесте (`exported="false"`),
     * потому что `PendingIntent` из `PackageInstaller` живёт в системе и может прийти
     * уже после смерти процесса — тогда оболочка просто не узнает итог, и это не ошибка.
     *
     * **На книге проверено (2026-11-18):** при успешной установке Android убивает наш процесс
     * (`Force stopping … installPackageLI`) и запускает оболочку заново, поэтому «Готово» на B76
     * увидеть нельзя — экран открывается уже с новой версией и «Обновлений нет». Приёмник
     * по-прежнему нужен для отказа (подпись, место): там процесс жив и экран показывает причину.
     */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
            val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
            // -1 = STATUS_PENDING_USER_ACTION: система не ставит молча, она показывает свой
            // диалог подтверждения, а нам отдаёт интент для запуска. Это не отказ и не итог —
            // ждём второго сообщения, уже с результатом.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
                Trace.step("install: ждём подтверждения человека (${confirm != null})")
                if (confirm != null && ctx != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(confirm)
                    } catch (t: Throwable) {
                        Trace.step("install: диалог не открылся ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
                return
            }
            Trace.step("install: status=$status msg=${msg.ifEmpty { "-" }}")
            val w = waiter
            if (w != null && status != Int.MIN_VALUE) w(status == PackageInstaller.STATUS_SUCCESS, msg)
        }
    }

    /**
     * Кто ждёт итога — экран B76. Статическая ссылка, потому что приёмник создаёт система,
     * а не мы; экран обязан снять себя в `onDetachedFromWindow` ([clearWaiter]).
     */
    @Volatile
    private var waiter: ((Boolean, String) -> Unit)? = null

    fun setWaiter(w: ((Boolean, String) -> Unit)?) {
        waiter = w
    }

    fun clearWaiter() {
        waiter = null
    }

    /** Разрешена ли установка из этого приложения («Неизвестные источники»). */
    fun canInstall(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true
    } catch (t: Throwable) {
        Trace.step("install: canRequestPackageInstalls FAIL ${t.javaClass.simpleName}")
        false
    }

    /** Системный экран выдачи права — тот же, что у `EinkActivity.withInstallPermission`. */
    fun sourcesIntent(ctx: Context): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        Uri.parse("package:" + ctx.packageName)
    )

    /**
     * Отдать APK установщику. `false` — сессию открыть не удалось (нет права, файл битый,
     * места нет); причина в трассе. Итог — в [Receiver], а не здесь: система спрашивает
     * подтверждение у человека и ставит асинхронно.
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() <= 0L) {
            Trace.step("install: файла нет ${apk.name}")
            return false
        }
        if (!canInstall(ctx)) {
            Trace.step("install: нет права на неизвестные источники")
            return false
        }
        var session = -1
        try {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            session = pi.createSession(params)
            pi.openSession(session).use { s ->
                apk.inputStream().use { inp ->
                    s.openWrite("base.apk", 0L, apk.length()).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                        }
                    }
                }
                val done = Intent(ctx, Receiver::class.java).setAction(STATUS)
                // На API 31+ FLAG_MUTABLE обязателен: система дописывает в Intent код итога.
                val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(
                    ctx, session, done, PendingIntent.FLAG_UPDATE_CURRENT or flags
                )
                s.commit(pending.intentSender)
            }
            Trace.step("install: сессия $session, ${apk.length()} б отданы системе")
            return true
        } catch (t: Throwable) {
            Trace.step("install FAIL ${t.javaClass.simpleName}: ${t.message}")
            if (session >= 0) try {
                ctx.packageManager.packageInstaller.abandonSession(session)
            } catch (ignored: Throwable) {
            }
            return false
        }
    }

    /** Человеческий текст отказа установки для строки на B76. Успех экран пишет сам. */
    fun describe(msg: String): String = when {
        msg.contains("VERSION_DOWNGRADE", true) -> "Эта версия уже установлена"
        msg.contains("INCOMPATIBLE", true) -> "Подпись не совпала с установленной"
        msg.contains("INSUFFICIENT_STORAGE", true) -> "Мало места"
        else -> "Система не поставила обновление"
    }
}
