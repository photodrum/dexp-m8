#!/usr/bin/env python3
"""Матрица QR-кода страницы автора → app/src/main/java/ru/ogrpfsense/dexpm8/ui/AuthorQr.kt.

Зачем: на книге нет браузера, а приложение бесплатное — ссылка на Бусти показывается
QR-кодом (B43, кнопка «Автор»). AndroidX в проекте нет, поэтому QR не рисуется
библиотекой, а лежит готовой матрицей из нулей и единиц: экран рисует квадратики
`ink.rect`. Матрица 29×29 (версия 3, коррекция M) — 30 символов ссылки влезают с запасом.

Зависимость нужна только для перегенерации (в сборке не участвует):
    pip3 install segno        (или: pip3 install --target /tmp/segno-lib segno)
    PYTHONPATH=/tmp/segno-lib python3 tools/qr_matrix.py

Тихой зоны в матрице нет: её даёт белая подложка экрана (4 модуля), как требует стандарт.
"""
import sys
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app/src/main/java/ru/ogrpfsense/dexpm8/ui/AuthorQr.kt"

URL = "https://boosty.to/photodrum"

HEADER = '''package ru.ogrpfsense.dexpm8.ui

/**
 * QR-код страницы автора на Бусти (B43 → «Автор»). Числа не правятся руками:
 * файл сгенерирован `tools/qr_matrix.py` (segno, версия 3, коррекция M, 29×29).
 *
 * Матрица лежит строками из '0' и '1' (1 — чёрный модуль): AndroidX и сторонних
 * библиотек в проекте нет, поэтому экран рисует код сам — квадратами `ink.rect`
 * по 20 px макета. Тихая зона (4 модуля) — белая подложка [SetAuthorScreen].
 *
 * Ссылка ниже — та же, что закодирована в [ROWS]; меняется только вместе с матрицей.
 */
object AuthorQr {

    /** Адрес страницы. Показывается на экране текстом под кодом. */
    const val URL = "%s"

    /** Адрес без схемы — подпись под кодом: «boosty.to/photodrum». */
    const val SHORT = "%s"

    /** Сторона матрицы в модулях. */
    const val SIZE = %d

    /** Строки матрицы сверху вниз, по [SIZE] символов '0'/'1' в каждой. */
    val ROWS: Array<String> = arrayOf(
%s
    )
}
'''


def main() -> int:
    try:
        import segno
    except ImportError:
        print("нет segno: pip3 install segno (см. шапку файла)")
        return 1
    qr = segno.make(URL, error="m")
    rows = qr.matrix
    size = len(rows)
    if any(len(r) != size for r in rows):
        print("матрица не квадратная")
        return 1
    body = ",\n".join('        "%s"' % "".join(str(b) for b in r) for r in rows)
    OUT.write_text(HEADER % (URL, URL.split("://", 1)[1], size, body), encoding="utf-8")
    print(f"записано {OUT.relative_to(ROOT)}: {size}×{size}, ссылка {URL}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
