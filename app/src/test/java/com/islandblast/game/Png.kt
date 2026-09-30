package com.islandblast.game

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

/** Minimal RGB PNG writer (the unit-test classpath has no java.awt). */
object Png {
    fun write(file: File, w: Int, h: Int, argb: IntArray) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { z ->
            val row = ByteArray(1 + w * 3)
            for (y in 0 until h) {
                row[0] = 0
                for (x in 0 until w) {
                    val p = argb[y * w + x]
                    row[1 + x * 3] = (p shr 16).toByte()
                    row[2 + x * 3] = (p shr 8).toByte()
                    row[3 + x * 3] = p.toByte()
                }
                z.write(row)
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            chunk(out, "IHDR", ByteArrayOutputStream().also {
                DataOutputStream(it).apply { writeInt(w); writeInt(h); write(byteArrayOf(8, 2, 0, 0, 0)) }
            }.toByteArray())
            chunk(out, "IDAT", raw.toByteArray())
            chunk(out, "IEND", ByteArray(0))
        }
    }

    private fun chunk(out: DataOutputStream, type: String, data: ByteArray) {
        out.writeInt(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t)
        out.write(data)
        val crc = CRC32().apply { update(t); update(data) }
        out.writeInt(crc.value.toInt())
    }
}
