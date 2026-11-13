package ru.ogrpfsense.dexpm8.reader

/**
 * Пункт оглавления книги — плоский список в порядке текста (`Engine.toc()`).
 *
 * Так его отдаёт crengine (`LVTocItem`): заголовок, уровень вложенности, номер страницы
 * и позиция xpointer. Экран B10 (R3) покажет пункты отступом по [level] и подсветит
 * текущую главу — по [page] против страницы на экране (или по [pos] через `pageOf`).
 *
 * @property title заголовок из книги (уже без разметки);
 * @property level вложенность: 1 — верхний уровень, 2 — подраздел и т. д.;
 * @property page страница пункта (0-based), `-1` — движок её не нашёл (например, пункт
 *   в сносках или ссылка на несуществующий файл EPUB);
 * @property pos xpointer — та же позиция, что у `Engine.pos(page)`; переживает смену кегля,
 *   по ней переходят через `Engine.gotoPos(pos)`; null — позицию получить не удалось.
 */
data class TocItem(
    val title: String,
    val level: Int,
    val page: Int,
    val pos: String?,
)
