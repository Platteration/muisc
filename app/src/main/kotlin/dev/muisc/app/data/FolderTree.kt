package dev.muisc.app.data

/**
 * A folder in the library's folder view. Pure Kotlin (no Android types) so it is unit-testable; [T] is the item type
 * (the app uses `Song`). [path] is the full directory path of this folder ("" for the virtual root) and [name] the
 * label to display (the last path segment, or several joined segments after collapsing).
 */
data class FolderNode<T>(
    val path: String,
    val name: String,
    val children: List<FolderNode<T>>,
    /** Items directly inside this folder (not in sub-folders). */
    val items: List<T>,
) {
    /** Items in this folder and every sub-folder, depth-first in child order. */
    val allItems: List<T>
        get() = buildList {
            addAll(items)
            for (c in children) addAll(c.allItems)
        }

    /** Number of items in this folder and all sub-folders. */
    val totalCount: Int get() = items.size + children.sumOf { it.totalCount }

    /** Finds the node whose [path] equals [target] (this node included), or null. */
    fun find(target: String): FolderNode<T>? {
        if (path == target) return this
        for (c in children) c.find(target)?.let { return it }
        return null
    }
}

/**
 * Builds a [FolderNode] tree from items that each carry a file path. Folder chains with a single child and no files
 * (e.g. `/storage/emulated/0/Music`) are collapsed into one node so the user is not shown four empty levels of
 * Android storage layout.
 */
object FolderTree {

    /**
     * @param items the items to place; [pathOf] returns each item's file path (`/a/b/c.mp3`). Items whose path has no
     *   directory component land in the root.
     * @param collapse merge single-child, item-less folders into their child.
     */
    fun <T> build(items: List<T>, pathOf: (T) -> String, collapse: Boolean = true): FolderNode<T> {
        val root = MutableNode<T>("", "")
        for (item in items) {
            val dir = parentOf(pathOf(item))
            val node = if (dir.isEmpty()) root else root.descend(dir)
            node.items += item
        }
        val built = root.freeze()
        return if (collapse) collapseChains(built) else built
    }

    /** Parent directory without trailing slash ("" when there is none). Accepts `/` and `\` separators. */
    fun parentOf(path: String): String {
        val normalized = path.replace('\\', '/')
        val cut = normalized.lastIndexOf('/')
        return if (cut <= 0) "" else normalized.substring(0, cut)
    }

    private class MutableNode<T>(val path: String, val name: String) {
        val children = LinkedHashMap<String, MutableNode<T>>()
        val items = ArrayList<T>()

        /** Walks (creating as needed) the chain of nodes for [dir], an absolute (`/a/b`) or relative (`a/b`) directory. */
        fun descend(dir: String): MutableNode<T> {
            val normalized = dir.replace('\\', '/')
            val absolute = normalized.startsWith("/")
            var node = this
            var built = ""
            for (seg in normalized.split('/')) {
                if (seg.isEmpty()) continue
                built = when {
                    built.isEmpty() && absolute -> "/$seg"
                    built.isEmpty() -> seg
                    else -> "$built/$seg"
                }
                val childPath = built
                node = node.children.getOrPut(seg) { MutableNode(childPath, seg) }
            }
            return node
        }

        fun freeze(): FolderNode<T> = FolderNode(
            path = path,
            name = name,
            children = children.values
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                .map { it.freeze() },
            items = items.toList(),
        )
    }

    /**
     * Recursively merges nodes that have exactly one child and no items of their own into that child. The merged
     * node keeps the deepest path and joins the skipped names ("storage/emulated/0/Music"). The virtual root is
     * collapsed too when it is a pure chain, so the returned root may have a non-empty path.
     */
    private fun <T> collapseChains(node: FolderNode<T>): FolderNode<T> {
        var current = node
        while (current.items.isEmpty() && current.children.size == 1) {
            val only = current.children.first()
            val joined = if (current.name.isEmpty()) only.name else current.name + "/" + only.name
            current = only.copy(name = joined)
        }
        return current.copy(children = current.children.map { collapseChains(it) })
    }
}
