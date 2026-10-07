package com.arnav.music.backup
import com.arnav.music.core.backup.*
import org.junit.Assert.*
import org.junit.Test
class ArchiveCodecTest {
    private fun archive() = UserArchive(settings = "{}", tables = listOf(ArchiveTable("tracks", listOf("id", "blob", "empty"),
        listOf(listOf(ArchiveValue("string", "音乐 🎵"), ArchiveValue("blob", ArchiveCodec.base64(byteArrayOf(0, -1, 12))), ArchiveValue("null"))))),
        preferences = mapOf("test" to mapOf("counter" to ArchiveValue("long", Long.MAX_VALUE.toString()))))
    @Test fun gzipPreservesUnicodeBinaryNullAndLongs() {
        val original = archive()
        assertEquals(original, ArchiveCodec.decode(ArchiveCodec.encode(original)))
        assertArrayEquals(byteArrayOf(0, -1, 12), ArchiveCodec.unbase64(original.tables.first().rows.first()[1].value))
    }
    @Test fun chunkAssemblyPreservesBytes() {
        val bytes = ByteArray(ArchiveCodec.CHUNK_BYTES * 2 + 23) { (it % 251).toByte() }
        val chunks = ArchiveCodec.chunks(bytes)
        assertEquals(3, chunks.size)
        assertArrayEquals(bytes, chunks.fold(byteArrayOf()) { a, b -> a + b })
        assertEquals(64, ArchiveCodec.hash(bytes).length)
    }
    @Test(expected = IllegalArgumentException::class) fun incorrectChecksumBlocksRestore() {
        ArchiveCodec.decode(ArchiveCodec.encode(archive()), "0".repeat(64))
    }
    @Test(expected = IllegalArgumentException::class) fun futureSchemaBlocksRestore() {
        ArchiveCodec.decode(ArchiveCodec.encode(archive().copy(schema = 2)))
    }
    @Test(expected = IllegalArgumentException::class) fun oversizedBackupIsRejectedWithoutTruncation() {
        ArchiveCodec.encode(archive(), maxBytes = 32)
    }
}
