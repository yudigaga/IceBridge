package com.huanchengfly.icebridge.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEditorDraftTest {
    @Test fun restoresUnsavedRowsIncludingMultilineAndNonAsciiInput() {
        val rows = listOf(
            EditableRule("com.tencent.mm", "com.example.reader", "example.com\nhttps://news.example.com/a", "none", "未完成的备注"),
            EditableRule("*", "com.example.reader", "still editing", "fixed_deep_link", "\u0000")
        )
        assertEquals(rows, DraftRowsCodec.decode(DraftRowsCodec.encode(rows)))
    }

    @Test fun restoresDeletionOfAllRowsInsteadOfReintroducingInitialRows() {
        assertEquals(emptyList<EditableRule>(), DraftRowsCodec.decode(DraftRowsCodec.encode(emptyList())))
    }
}
