package com.rustamguloff.foliolink.ui.outbox

import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation

// Логика экрана очереди без Compose (проверяется JVM-тестами).

/** Вид элемента очереди — по нему выбирается значок. */
enum class OutboxKind { BOOK, SLEEP_IMAGE, FONT, DICTIONARY, FILE, DELETE, STATUS, GOAL, SHELF, NOTE, SETTINGS, SELECT, UNKNOWN }

/** Пометка под элементом. */
enum class OutboxNotice {
    NONE,
    /** Записан более новой версией приложения: можно только удалить. */
    NEEDS_NEWER_APP,
    /** Сделан для другой читалки: уйдёт, когда подключится она. */
    OTHER_READER,
}

object OutboxLogic {
    fun kind(op: OutboxOperation): OutboxKind = when (op) {
        OutboxOperation.UploadBook -> OutboxKind.BOOK
        is OutboxOperation.UploadFile -> when (op.purpose) {
            OutboxOperation.UploadPurpose.SLEEP -> OutboxKind.SLEEP_IMAGE
            OutboxOperation.UploadPurpose.FONT -> OutboxKind.FONT
            OutboxOperation.UploadPurpose.DICTIONARY -> OutboxKind.DICTIONARY
            OutboxOperation.UploadPurpose.BOOK -> OutboxKind.BOOK
            OutboxOperation.UploadPurpose.OTHER -> OutboxKind.FILE
        }
        is OutboxOperation.DeleteFile, is OutboxOperation.DeleteFontFamily, is OutboxOperation.DeleteDictionary,
        is OutboxOperation.DeleteHighlight, is OutboxOperation.DeleteVocab -> OutboxKind.DELETE
        is OutboxOperation.Library -> when (op.change) {
            is LibraryChange.SetStatus -> OutboxKind.STATUS
            is LibraryChange.SetGoal -> OutboxKind.GOAL
            else -> OutboxKind.SHELF
        }
        is OutboxOperation.HighlightNote -> OutboxKind.NOTE
        is OutboxOperation.Settings -> OutboxKind.SETTINGS
        is OutboxOperation.SelectSleepImage, is OutboxOperation.SelectDictionary -> OutboxKind.SELECT
        is OutboxOperation.Unsupported -> OutboxKind.UNKNOWN
    }

    fun notice(item: OutboxItem, forOtherReader: Boolean): OutboxNotice = when {
        !item.operation.isSupported -> OutboxNotice.NEEDS_NEWER_APP
        forOtherReader -> OutboxNotice.OTHER_READER
        else -> OutboxNotice.NONE
    }

    /** «Отправить сейчас» по элементу: только понятные этой версии, при связи. */
    fun canSend(item: OutboxItem, connected: Boolean, forOtherReader: Boolean): Boolean =
        connected && item.operation.isSupported && !forOtherReader
}
