package dev.pocketshare.server

import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Bounded reader for the Compound File Binary container used by Office 97-2003 documents.
 * It exposes stream contents only and never follows more chain links than a file could contain.
 */
internal class CompoundFile private constructor(
    private val bytes: ByteArray,
) {
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val miniCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val entries: List<Entry>

    init {
        require(bytes.size >= 512) { "Compound file too short" }
        require(SIGNATURE.indices.all { bytes[it] == SIGNATURE[it] }) { "Bad compound file signature" }
        sectorSize = shiftSize(SECTOR_SHIFT_OFFSET, 512, 4096)
        miniSectorSize = shiftSize(MINI_SECTOR_SHIFT_OFFSET, 64, 64)
        miniCutoff = i32(bytes, MINI_CUTOFF_OFFSET)
        fat = readFat(difatSectors())
        entries = readDirectory()
        val root = entries.firstOrNull { it.type == ROOT_TYPE } ?: error("Missing root entry")
        miniFat = readMiniFat()
        miniStream = if (root.size > 0) readChain(root.start, root.size, fat) else ByteArray(0)
    }

    fun stream(vararg names: String): ByteArray? {
        val wanted = names.map { it.lowercase(Locale.ROOT) }.toSet()
        val entry =
            entries.firstOrNull {
                it.type == STREAM_TYPE && it.name.lowercase(Locale.ROOT) in wanted
            } ?: return null
        if (entry.size <= 0) return ByteArray(0)
        return if (entry.size < miniCutoff && miniFat.isNotEmpty()) {
            readMiniChain(entry.start, entry.size)
        } else {
            readChain(entry.start, entry.size, fat)
        }
    }

    private fun shiftSize(
        offset: Int,
        minimum: Int,
        maximum: Int,
    ): Int {
        val shift = u16(bytes, offset)
        require(shift in 0..15) { "Bad sector shift" }
        val size = 1 shl shift
        require(size in minimum..maximum) { "Unsupported sector size" }
        return size
    }

    private fun difatSectors(): List<Int> {
        val sectors = ArrayList<Int>()
        for (index in 0 until DIFAT_ENTRIES) {
            val sector = i32(bytes, DIFAT_OFFSET + index * 4)
            if (sector >= 0) sectors.add(sector)
        }
        var next = i32(bytes, DIFAT_START_OFFSET)
        var visited = 0
        while (next >= 0 && visited++ < MAX_CHAIN) {
            val offset = sectorOffset(next) ?: break
            val perSector = sectorSize / 4 - 1
            for (index in 0 until perSector) {
                val sector = i32(bytes, offset + index * 4)
                if (sector >= 0) sectors.add(sector)
            }
            next = i32(bytes, offset + sectorSize - 4)
        }
        return sectors
    }

    private fun readFat(sectors: List<Int>): IntArray {
        val entries = ArrayList<Int>()
        for (sector in sectors) {
            if (entries.size >= MAX_FAT_ENTRIES) break
            val offset = sectorOffset(sector) ?: continue
            for (index in 0 until sectorSize / 4) {
                entries.add(i32(bytes, offset + index * 4))
            }
        }
        return entries.toIntArray()
    }

    private fun readMiniFat(): IntArray {
        if (miniCutoff <= 0) return IntArray(0)
        var sector = i32(bytes, MINI_FAT_START_OFFSET)
        val entries = ArrayList<Int>()
        val visited = HashSet<Int>()
        while (sector >= 0 && visited.add(sector) && entries.size < MAX_FAT_ENTRIES) {
            val offset = sectorOffset(sector) ?: break
            for (index in 0 until sectorSize / 4) {
                entries.add(i32(bytes, offset + index * 4))
            }
            sector = fat.getOrElse(sector) { END_OF_CHAIN }
        }
        return entries.toIntArray()
    }

    private fun readDirectory(): List<Entry> {
        val output = ByteArrayOutputStream()
        var sector = i32(bytes, DIRECTORY_START_OFFSET)
        val visited = HashSet<Int>()
        while (sector >= 0 && visited.add(sector) && visited.size <= MAX_CHAIN) {
            val offset = sectorOffset(sector) ?: break
            output.write(bytes, offset, sectorSize)
            sector = fat.getOrElse(sector) { END_OF_CHAIN }
        }
        val data = output.toByteArray()
        val result = ArrayList<Entry>()
        for (offset in 0 until data.size step DIRECTORY_ENTRY_SIZE) {
            if (result.size >= MAX_DIRECTORY_ENTRIES) break
            val type = data[offset + 0x42].toInt() and 0xFF
            if (type == 0) continue
            val nameLength = u16(data, offset + 0x40)
            val name = if (nameLength in 2..64) String(data, offset, nameLength - 2, Charsets.UTF_16LE) else ""
            val start = i32(data, offset + 0x74)
            val size = i64(data, offset + 0x78).coerceAtLeast(0)
            result.add(Entry(name, type, start, size))
        }
        return result
    }

    private fun readChain(
        start: Int,
        size: Long,
        table: IntArray,
    ): ByteArray {
        if (size <= 0 || size > MAX_STREAM_BYTES) return ByteArray(0)
        val target = size.toInt()
        val output = ByteArrayOutputStream(minOf(target, 64 * 1024))
        var sector = start
        val visited = HashSet<Int>()
        while (sector >= 0 && output.size() < target && visited.add(sector)) {
            val offset = sectorOffset(sector) ?: break
            val take = minOf(sectorSize, target - output.size())
            output.write(bytes, offset, take)
            sector = table.getOrElse(sector) { END_OF_CHAIN }
        }
        return output.toByteArray()
    }

    private fun readMiniChain(
        start: Int,
        size: Long,
    ): ByteArray {
        if (size <= 0 || size > MAX_STREAM_BYTES) return ByteArray(0)
        val target = size.toInt()
        val output = ByteArrayOutputStream(minOf(target, 64 * 1024))
        var sector = start
        val visited = HashSet<Int>()
        while (sector >= 0 && output.size() < target && visited.add(sector)) {
            val offset = sector.toLong() * miniSectorSize
            if (offset + miniSectorSize > miniStream.size) break
            val take = minOf(miniSectorSize, target - output.size())
            output.write(miniStream, offset.toInt(), take)
            sector = miniFat.getOrElse(sector) { END_OF_CHAIN }
        }
        return output.toByteArray()
    }

    private fun sectorOffset(sector: Int): Int? {
        if (sector < 0) return null
        val offset = (sector.toLong() + 1) * sectorSize
        if (offset < 0 || offset + sectorSize > bytes.size) return null
        return offset.toInt()
    }

    private data class Entry(
        val name: String,
        val type: Int,
        val start: Int,
        val size: Long,
    )

    companion object {
        private const val STREAM_TYPE = 2
        private const val ROOT_TYPE = 5
        private const val END_OF_CHAIN = -2
        private const val MAX_STREAM_BYTES = 16L * 1024 * 1024
        private const val MAX_CHAIN = 1 shl 20
        private const val MAX_FAT_ENTRIES = 1 shl 20
        private const val MAX_DIRECTORY_ENTRIES = 65_536
        private const val DIRECTORY_ENTRY_SIZE = 128
        private const val DIFAT_ENTRIES = 109
        private const val DIFAT_OFFSET = 0x4C
        private const val SECTOR_SHIFT_OFFSET = 0x1E
        private const val MINI_SECTOR_SHIFT_OFFSET = 0x20
        private const val DIRECTORY_START_OFFSET = 0x30
        private const val MINI_CUTOFF_OFFSET = 0x38
        private const val MINI_FAT_START_OFFSET = 0x3C
        private const val DIFAT_START_OFFSET = 0x44
        private val SIGNATURE =
            byteArrayOf(
                0xD0.toByte(),
                0xCF.toByte(),
                0x11,
                0xE0.toByte(),
                0xA1.toByte(),
                0xB1.toByte(),
                0x1A,
                0xE1.toByte(),
            )

        fun open(bytes: ByteArray): CompoundFile? = runCatching { CompoundFile(bytes) }.getOrNull()
    }
}

private fun u16(
    data: ByteArray,
    offset: Int,
): Int {
    if (offset < 0 || offset + 2 > data.size) return -1
    return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
}

private fun i32(
    data: ByteArray,
    offset: Int,
): Int {
    if (offset < 0 || offset + 4 > data.size) return -1
    return (data[offset].toInt() and 0xFF) or
        ((data[offset + 1].toInt() and 0xFF) shl 8) or
        ((data[offset + 2].toInt() and 0xFF) shl 16) or
        ((data[offset + 3].toInt() and 0xFF) shl 24)
}

private fun i64(
    data: ByteArray,
    offset: Int,
): Long {
    if (offset < 0 || offset + 8 > data.size) return -1
    return (data[offset].toLong() and 0xFF) or
        ((data[offset + 1].toLong() and 0xFF) shl 8) or
        ((data[offset + 2].toLong() and 0xFF) shl 16) or
        ((data[offset + 3].toLong() and 0xFF) shl 24) or
        ((data[offset + 4].toLong() and 0xFF) shl 32) or
        ((data[offset + 5].toLong() and 0xFF) shl 40) or
        ((data[offset + 6].toLong() and 0xFF) shl 48) or
        ((data[offset + 7].toLong() and 0xFF) shl 56)
}
