package dev.muisc.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure-Kotlin tests for [FolderTree]; items are plain path strings. */
class FolderTreeTest {

    private fun tree(vararg paths: String, collapse: Boolean = true): FolderNode<String> =
        FolderTree.build(paths.toList(), { it }, collapse = collapse)

    @Test
    fun parentOfHandlesRootRelativeAndBackslashes() {
        assertEquals("/a/b", FolderTree.parentOf("/a/b/c.mp3"))
        assertEquals("a", FolderTree.parentOf("a/c.mp3"))
        assertEquals("", FolderTree.parentOf("c.mp3"))
        assertEquals("", FolderTree.parentOf("/c.mp3"))
        assertEquals("C:/Music", FolderTree.parentOf("C:\\Music\\c.mp3"))
    }

    @Test
    fun uncollapsedTreeKeepsEveryLevel() {
        val root = tree("/storage/emulated/0/Music/a.mp3", "/storage/emulated/0/Music/Live/b.mp3", collapse = false)
        assertEquals("", root.path)
        assertEquals(1, root.children.size)
        val storage = root.children[0]
        assertEquals("/storage", storage.path)
        assertEquals("storage", storage.name)
        val music = storage.children[0].children[0].children[0]
        assertEquals("/storage/emulated/0/Music", music.path)
        assertEquals(listOf("/storage/emulated/0/Music/a.mp3"), music.items)
        assertEquals(2, music.totalCount)
        assertEquals(1, music.children.size)
        assertEquals("Live", music.children[0].name)
    }

    @Test
    fun collapsesEmptySingleChildChains() {
        val root = tree(
            "/storage/emulated/0/Music/Album/1.mp3",
            "/storage/emulated/0/Music/Album/2.mp3",
            "/storage/emulated/0/Download/x.mp3",
        )
        // /storage/emulated/0 is the first branch point: it becomes the root.
        assertEquals("/storage/emulated/0", root.path)
        assertEquals("storage/emulated/0", root.name)
        assertEquals(listOf("Download", "Music/Album"), root.children.map { it.name })
        val album = root.children[1]
        assertEquals("/storage/emulated/0/Music/Album", album.path)
        assertEquals(2, album.items.size)
        assertEquals(3, root.totalCount)
    }

    @Test
    fun folderWithItemsIsNeverCollapsedAway() {
        val root = tree("/m/a.mp3", "/m/sub/b.mp3")
        assertEquals("/m", root.path)
        assertEquals(listOf("/m/a.mp3"), root.items)
        assertEquals(1, root.children.size)
        assertEquals("sub", root.children[0].name)
    }

    @Test
    fun childrenAreSortedCaseInsensitively() {
        val root = tree("/r/b/1.mp3", "/r/A/1.mp3", "/r/c/1.mp3", "/r/x.mp3")
        assertEquals(listOf("A", "b", "c"), root.children.map { it.name })
    }

    @Test
    fun rootlessPathsLandInRoot() {
        val root = tree("loose.mp3", "dir/inside.mp3")
        assertEquals("", root.path)
        assertEquals(listOf("loose.mp3"), root.items)
        assertEquals("dir", root.children[0].path)
    }

    @Test
    fun findLocatesNodesByPath() {
        val root = tree("/a/b/c/1.mp3", "/a/b/d/2.mp3")
        assertNotNull(root.find("/a/b/c"))
        assertNull(root.find("/a/zzz"))
        assertEquals(listOf("/a/b/c/1.mp3", "/a/b/d/2.mp3"), root.allItems)
        assertTrue(root.find("/a/b/d")!!.items.contains("/a/b/d/2.mp3"))
    }

    @Test
    fun emptyInputGivesEmptyRoot() {
        val root = FolderTree.build(emptyList<String>(), { it })
        assertEquals("", root.path)
        assertTrue(root.children.isEmpty())
        assertEquals(0, root.totalCount)
    }
}
