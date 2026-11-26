package com.rustamguloff.foliolink.core.dictionaries

import com.rustamguloff.foliolink.core.model.DeviceInfo
import com.rustamguloff.foliolink.core.model.DictionariesResponse
import com.rustamguloff.foliolink.core.model.DictionaryInfo
import com.rustamguloff.foliolink.core.model.OutboxOperation

/** Правила экрана «Словари» без Compose: поддержка, кэш, режим, отметки каталога. */
object DictionaryRules {
    const val FEATURE = "dictionaries"
    const val ROOT = "/dictionaries"

    /** Поддержка прошивкой: true/false по `features`; null — читалка ещё не сообщала о себе. */
    fun supported(info: DeviceInfo?): Boolean? = info?.let { FEATURE in it.features }

    /** Папка словаря на карте. */
    fun folderPath(folder: String): String = "$ROOT/$folder"

    /** Изменение из очереди в кэше (офлайн-правки видны сразу). Прочие операции — без изменений. */
    fun apply(op: OutboxOperation, cached: DictionariesResponse?): DictionariesResponse? {
        val r = cached ?: return null
        return when (op) {
            is OutboxOperation.SelectDictionary -> r.copy(selected = op.name)
            is OutboxOperation.DeleteDictionary -> r.copy(
                dictionaries = r.dictionaries.filter { it.name != op.name },
                selected = if (r.selected == op.name) DictionariesResponse.ALL else r.selected,
            )
            else -> r
        }
    }

    /** Режим для переключателя: `*`, `-` или имя установленной папки (пропавшая папка — «все»). */
    fun mode(r: DictionariesResponse?): String = r?.effectiveSelection ?: DictionariesResponse.ALL

    /** Используется ли словарь при поиске: «все» — каждый, иначе только выбранный. */
    fun isInUse(d: DictionaryInfo, r: DictionariesResponse): Boolean = when (val m = mode(r)) {
        DictionariesResponse.ALL -> true
        DictionariesResponse.OFF -> false
        else -> d.name == m
    }

    /** Запись каталога уже на читалке (папка с тем же именем, без учёта регистра — FAT). */
    fun isInstalled(entry: CatalogEntry, installed: List<DictionaryInfo>): Boolean =
        installed.any { it.name.equals(entry.id, ignoreCase = true) }

    /** Размер в КБ → байты для форматирования. */
    fun bytes(d: DictionaryInfo): Long = d.sizeKB * 1024
}
