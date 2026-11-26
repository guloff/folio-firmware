package com.rustamguloff.foliolink.ui.outbox

import com.rustamguloff.foliolink.core.model.LibraryChange
import com.rustamguloff.foliolink.core.model.OutboxItem
import com.rustamguloff.foliolink.core.model.OutboxOperation
import com.rustamguloff.foliolink.core.model.OutboxOperation.UploadPurpose
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxLogicTest {
    private fun item(op: OutboxOperation) = OutboxItem(fileName = "", title = "t", source = "", operation = op)

    @Test
    fun kindByOperation() {
        assertEquals(OutboxKind.BOOK, OutboxLogic.kind(OutboxOperation.UploadBook))
        assertEquals(OutboxKind.SLEEP_IMAGE, OutboxLogic.kind(OutboxOperation.UploadFile("/sleep", "image/bmp", true, UploadPurpose.SLEEP)))
        assertEquals(OutboxKind.FONT, OutboxLogic.kind(OutboxOperation.UploadFile("/fonts/A", "font/ttf", true, UploadPurpose.FONT)))
        assertEquals(OutboxKind.FILE, OutboxLogic.kind(OutboxOperation.UploadFile("/x", "a/b", false, UploadPurpose.OTHER)))
        assertEquals(OutboxKind.DELETE, OutboxLogic.kind(OutboxOperation.DeleteFile("/a")))
        assertEquals(OutboxKind.DELETE, OutboxLogic.kind(OutboxOperation.DeleteFontFamily("A")))
        assertEquals(OutboxKind.DELETE, OutboxLogic.kind(OutboxOperation.DeleteVocab("w")))
        assertEquals(OutboxKind.STATUS, OutboxLogic.kind(OutboxOperation.Library(LibraryChange.SetStatus("/b", "reading"))))
        assertEquals(OutboxKind.GOAL, OutboxLogic.kind(OutboxOperation.Library(LibraryChange.SetGoal(30))))
        assertEquals(OutboxKind.SHELF, OutboxLogic.kind(OutboxOperation.Library(LibraryChange.CreateShelf("id", "S"))))
        assertEquals(OutboxKind.NOTE, OutboxLogic.kind(OutboxOperation.HighlightNote("1", "n")))
        assertEquals(OutboxKind.SETTINGS, OutboxLogic.kind(OutboxOperation.Settings(mapOf("a" to JsonPrimitive(1)))))
        assertEquals(OutboxKind.SELECT, OutboxLogic.kind(OutboxOperation.SelectSleepImage("a.bmp")))
        assertEquals(OutboxKind.UNKNOWN, OutboxLogic.kind(OutboxOperation.Unsupported(JsonPrimitive("x"))))
    }

    @Test
    fun noticeAndSend() {
        val unsupported = item(OutboxOperation.Unsupported(JsonPrimitive("future")))
        val normal = item(OutboxOperation.DeleteFile("/a"))
        // Непонятное этой версии — только удалить, даже если оно и для другой читалки.
        assertEquals(OutboxNotice.NEEDS_NEWER_APP, OutboxLogic.notice(unsupported, forOtherReader = true))
        assertEquals(OutboxNotice.OTHER_READER, OutboxLogic.notice(normal, forOtherReader = true))
        assertEquals(OutboxNotice.NONE, OutboxLogic.notice(normal, forOtherReader = false))

        assertTrue(OutboxLogic.canSend(normal, connected = true, forOtherReader = false))
        assertFalse(OutboxLogic.canSend(normal, connected = false, forOtherReader = false))
        assertFalse(OutboxLogic.canSend(normal, connected = true, forOtherReader = true))
        assertFalse(OutboxLogic.canSend(unsupported, connected = true, forOtherReader = false))
    }
}
