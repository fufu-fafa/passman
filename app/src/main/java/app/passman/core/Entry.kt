package app.passman.core

import java.text.Collator
import java.util.Locale
import java.util.UUID

/**
 * One vault entry. [code] holds a 2FA setup key (a Base32 secret or an otpauth:// URI); the app
 * shows the rotating code generated from it. [modifiedAt] is UTC epoch milliseconds.
 */
data class Entry(
    val id: String,
    val title: String,
    val userName: String,
    val password: String,
    val code: String,
    val website: String,
    val notes: String,
    val modifiedAt: Long,
) {
    /** True when every user-editable field matches [other] (id and modified date are ignored). */
    fun sameContentAs(other: Entry): Boolean =
        title == other.title && userName == other.userName && password == other.password &&
            code == other.code && website == other.website && notes == other.notes

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Field limits from the PRD data model, in characters (UTF-16 code units). */
object Limits {
    const val TITLE = 100
    const val USER_NAME = 254
    const val PASSWORD = 1_024
    const val CODE = 256
    const val WEBSITE = 2_048
    const val NOTES = 10_000
}

enum class SortOrder { TITLE, MODIFIED }

object EntryQuery {
    private val collator: Collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }

    /** FR-4: matches Title, User Name, Website and Notes; never Password or Code. */
    fun matches(entry: Entry, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return true
        return entry.title.contains(q, ignoreCase = true) ||
            entry.userName.contains(q, ignoreCase = true) ||
            entry.website.contains(q, ignoreCase = true) ||
            entry.notes.contains(q, ignoreCase = true)
    }

    fun sort(entries: List<Entry>, order: SortOrder): List<Entry> = when (order) {
        SortOrder.TITLE -> entries.sortedWith { a, b ->
            val c = collator.compare(a.title, b.title)
            if (c != 0) c else b.modifiedAt.compareTo(a.modifiedAt)
        }
        SortOrder.MODIFIED -> entries.sortedWith { a, b ->
            val c = b.modifiedAt.compareTo(a.modifiedAt)
            if (c != 0) c else collator.compare(a.title, b.title)
        }
    }

    fun filterAndSort(entries: List<Entry>, query: String, order: SortOrder): List<Entry> =
        sort(entries.filter { matches(it, query) }, order)
}
