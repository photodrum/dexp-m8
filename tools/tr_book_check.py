#!/usr/bin/env python3
"""Проверка перевода слова на книге без экрана (docs/CHECKS.md, «Направление перевода на хосте»).

Строит два EPUB (английский `dc:language=en`; русский `rus` с латинским словом), кладёт их в
/sdcard/Books, открывает библиотеку (сканер заполняет `books.lang`), открывает каждую книгу
(`--es read <id>`), удерживает первое слово, нажимает «Словарь» в меню выделения и вкладку
«Перевод». Направление и перевод читаются из трассы DexpM8: `b30: перевод '…' [en → ru, язык
книги en] -> …`. В конце файлы удаляются с книги и записи уходят из базы.
Нужен debug-APK на книге по adb; координаты — для экрана 1404×1872 (D.u = 1)."""
import os, re, sqlite3, subprocess, sys, time, zipfile

ADB = os.path.expanduser('~/.local/android-sdk/platform-tools/adb')
PKG = 'ru.ogrpfsense.dexpm8'
TX, TY = 102.4, 117.0


def sh(*a, **k):
    return subprocess.run([ADB] + list(a), capture_output=True, text=True, **k).stdout


def epub(path, title, lang, paras):
    body = ''.join('<p>%s</p>' % p for p in paras)
    with zipfile.ZipFile(path, 'w') as z:
        z.writestr(zipfile.ZipInfo('mimetype'), 'application/epub+zip')
        z.writestr('META-INF/container.xml',
            '<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">'
            '<rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>')
        z.writestr('OEBPS/content.opf',
            '<?xml version="1.0" encoding="utf-8"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">'
            '<metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>%s</dc:title><dc:creator>Тест Перевода</dc:creator>'
            '<dc:language>%s</dc:language><dc:identifier id="id">trtest-%s</dc:identifier></metadata>'
            '<manifest><item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>'
            '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/></manifest>'
            '<spine toc="ncx"><itemref idref="c1"/></spine></package>' % (title, lang, lang))
        z.writestr('OEBPS/toc.ncx',
            '<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">'
            '<head/><docTitle><text>%s</text></docTitle><navMap><navPoint id="n1" playOrder="1"><navLabel><text>Глава</text></navLabel>'
            '<content src="c1.xhtml"/></navPoint></navMap></ncx>' % title)
        z.writestr('OEBPS/c1.xhtml',
            '<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>%s</title></head>'
            '<body>%s</body></html>' % (title, body))


def trace_since(mark):
    out = sh('logcat', '-d', '-s', 'DexpM8:I', 'AndroidRuntime:E')
    return [l for l in out.splitlines() if l.split(' I DexpM8')[0] > mark or 'AndroidRuntime' in l]


def db():
    os.makedirs('/tmp/dbx', exist_ok=True)
    for f in ('library.db', 'library.db-wal', 'library.db-shm'):
        open('/tmp/dbx/' + f, 'wb').write(subprocess.run(
            [ADB, 'exec-out', 'run-as', PKG, 'cat', 'databases/' + f], capture_output=True).stdout)
    return sqlite3.connect('/tmp/dbx/library.db')


def one(name, path_dev, check_word_re, expect_dir):
    c = db()
    row = c.execute("select _id, lang from books where path = ?", (path_dev,)).fetchone()
    print('книга', name, 'в базе:', row)
    if not row:
        return False
    bid, lang = row
    sh('shell', 'am', 'force-stop', PKG)
    sh('logcat', '-c')
    sh('shell', 'am', 'start', '-n', PKG + '/.ui.EinkActivity', '--es', 'read', str(bid))
    time.sleep(14)
    # удержание на первом слове первой строки
    sh('shell', 'input', 'swipe', '150', '170', '150', '170', '1600')
    time.sleep(4)
    log = sh('logcat', '-d', '-s', 'DexpM8:I')
    m = re.search(r"b50: выделено '([^']*)' отрезков=\d+ первый=(-?\d+),(-?\d+),(-?\d+),(-?\d+)", log)
    if not m:
        print('  выделения нет; трасса:\n' + '\n'.join(log.splitlines()[-12:]))
        return False
    word = m.group(1)
    ry, rh = int(m.group(3)), int(m.group(5))
    bottom = TY + ry + rh
    y0 = bottom + 17.4
    print('  выделено «%s», низ %.0f, панель с %.0f' % (word, bottom, y0))
    sh('shell', 'input', 'tap', '704', str(int(y0 + 103)))     # «Словарь» в меню выделения
    time.sleep(4)
    sh('shell', 'input', 'tap', '736', '1084')                  # вкладка «Перевод»
    time.sleep(9)
    log = sh('logcat', '-d', '-s', 'DexpM8:I')
    tr = [l for l in log.splitlines() if 'b30: перевод' in l]
    opened = [l for l in log.splitlines() if 'b30: open' in l]
    print('  ', opened[-1:] or 'лист не открылся')
    for l in tr:
        print('  ', l.split(' I DexpM8  : ')[-1])
    ok = bool(tr) and expect_dir in tr[-1]
    print('  ожидали «%s» ->' % expect_dir, 'OK' if ok else 'НЕ СОВПАЛО')
    return ok


def main():
    en = '/storage/emulated/0/Books/trtest_en.epub'
    ru = '/storage/emulated/0/Books/trtest_rus.epub'
    epub('/tmp/trtest_en.epub', 'Trtest English', 'en',
         ['Weekend begins soon. ' + 'The house stands near the river and the old bridge. ' * 12] * 6)
    epub('/tmp/trtest_rus.epub', 'Тест перевода русская', 'rus',
         ['Weekend начался рано. ' + 'Дом стоит у реки возле старого моста. ' * 12] * 6)
    sh('push', '/tmp/trtest_en.epub', en)
    sh('push', '/tmp/trtest_rus.epub', ru)
    sh('shell', 'am', 'broadcast', '-a', 'android.intent.action.MEDIA_SCANNER_SCAN_FILE', '-d', 'file://' + en)
    # библиотека: сканер запускается при её открытии
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.ui.EinkActivity')
    time.sleep(8)
    sh('shell', 'input', 'tap', '361', '1237')
    time.sleep(30)
    r1 = one('en', en, None, 'en → ru')
    r2 = one('rus (латинское слово в русской книге)', ru, None, 'авто → ru')
    print('=== ИТОГ', 'OK' if r1 and r2 else 'ЕСТЬ РАСХОЖДЕНИЯ')
    # Убрать тестовые книги: файлы с книги, записи уйдут при следующем проходе сканера.
    sh('shell', 'rm', '-f', en, ru)
    # Запись в MediaStore переживает файл, а сканер берёт пути оттуда и не считает книгу
    # пропавшей, — без этой строки в базе остаются две «призрачные» книги.
    sh('shell', "content delete --uri content://media/external/file --where \"_data LIKE '%trtest%'\"")
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.ui.EinkActivity')
    time.sleep(8)
    sh('shell', 'input', 'tap', '361', '1237')
    time.sleep(15)
    left = db().execute("select count(*) from books where path like '%trtest%'").fetchone()[0]
    print('тестовых записей в базе после уборки:', left)
    sh('shell', 'am', 'force-stop', PKG)
    sh('shell', 'am', 'start', '-n', PKG + '/.ui.EinkActivity')


main()
