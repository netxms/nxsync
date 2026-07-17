package org.netxms.sync

/** Per-node outcome of a sync run. */
class NodeReport(val nodeName: String) {
    var downloaded: Int = 0
    var updated: Int = 0
    var skipped: Int = 0
    val errors: MutableList<String> = mutableListOf()

    val ok: Boolean
        get() = errors.isEmpty()

    fun record(action: Action) {
        when (action) {
            Action.DOWNLOAD -> downloaded++
            Action.UPDATE -> updated++
            Action.SKIP -> skipped++
        }
    }

    fun error(message: String) {
        errors.add(message)
    }
}
