package com.huanchengfly.icebridge.ui

import com.huanchengfly.icebridge.automation.LinkRuleEntry

internal data class EditableRule(
    val sourcePackage: String,
    val targetPackage: String = "",
    val hostsText: String = "",
    val conversion: String = LinkRuleEntry.CONVERSION_NONE,
    val note: String = ""
) {
    companion object {
        fun from(entry: LinkRuleEntry) = EditableRule(
            entry.sourcePackage, entry.targetPackage, entry.host, entry.urlConversion, entry.note
        )
    }
}

/** Only editable strings enter saved instance state; no Activity, icon or device metadata. */
internal object DraftRowsCodec {
    fun encode(rows: List<EditableRule>): List<String> = rows.flatMap {
        listOf(it.sourcePackage, it.targetPackage, it.hostsText, it.conversion, it.note)
    }

    fun decode(values: List<String>): List<EditableRule> {
        require(values.size % 5 == 0) { "Invalid saved rule draft" }
        return values.chunked(5).map { EditableRule(it[0], it[1], it[2], it[3], it[4]) }
    }
}
