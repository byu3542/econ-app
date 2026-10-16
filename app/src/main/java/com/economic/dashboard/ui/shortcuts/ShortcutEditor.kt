package com.economic.dashboard.ui.shortcuts

/**
 * Settings parity U7 (R11, KTD6): the editor's working copy. Drag, the
 * TalkBack move actions, remove and add all go through these mutations, so
 * no path can produce an order another couldn't. Nothing is stored until the
 * caller saves [ids]; dropping the editor discards the edit.
 */
class ShortcutEditor(initial: List<String>) {

    private val working: MutableList<String> = ShortcutCatalog.sanitize(initial).toMutableList()
    private val saved: List<String> = working.toList()

    val ids: List<String> get() = working.toList()

    val isFull: Boolean get() = working.size >= ShortcutCatalog.MAX

    /** True once the working copy differs from what the editor opened with. */
    val isDirty: Boolean get() = working != saved

    /** Available shortcuts not in the list, in catalog order. */
    fun more(): List<String> = ShortcutCatalog.available().map { it.id }.filter { it !in working }

    /** Appends [id]. Rejected when full, already present, or not available. */
    fun add(id: String): Boolean {
        if (isFull || id in working || ShortcutCatalog.find(id) == null) return false
        working.add(id)
        return true
    }

    fun remove(id: String): Boolean = working.remove(id)

    /** Moves the shortcut at [from] to [to]; false when either is out of range. */
    fun move(from: Int, to: Int): Boolean {
        if (from !in working.indices || to !in working.indices || from == to) return false
        working.add(to, working.removeAt(from))
        return true
    }
}
