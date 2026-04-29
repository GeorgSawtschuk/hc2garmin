package de.sawtschuk.hc2garmin.data.fit

import java.io.ByteArrayOutputStream

object FitFileBuilder {

    private const val FIT_EPOCH_OFFSET = 631065600L  // seconds between 1970-01-01 and 1989-12-31

    fun buildWeightFitFile(weightKg: Double, epochSeconds: Long): ByteArray {
        val fitTs = (epochSeconds - FIT_EPOCH_OFFSET).toInt()
        val weightRaw = (weightKg * 100 + 0.5).toInt()  // uint16, scale=100, unit=kg

        val payload = buildPayload(fitTs, weightRaw)
        return wrapInFitFile(payload)
    }

    private fun buildPayload(fitTs: Int, weightRaw: Int): ByteArray {
        val buf = ByteArrayOutputStream()

        // Definition message for file_id (local 0, global message 0)
        buf.write(0x40)          // definition record header, local 0
        buf.write(0x00)          // reserved
        buf.write(0x00)          // architecture: little-endian
        buf.writeLE16(0)         // global message number: file_id
        buf.write(3)             // number of fields
        // field 0: type,         size=1, base_type=enum(0x00)
        buf.write(0);  buf.write(1);  buf.write(0x00)
        // field 1: manufacturer, size=2, base_type=uint16(0x84)
        buf.write(1);  buf.write(2);  buf.write(0x84)
        // field 4: time_created, size=4, base_type=uint32(0x86)
        buf.write(4);  buf.write(4);  buf.write(0x86)

        // Data message for file_id (local 0)
        buf.write(0x00)
        buf.write(9)             // type = 9 = weight scale file
        buf.writeLE16(255)       // manufacturer = 255 (unknown/development)
        buf.writeLE32(fitTs)     // time_created

        // Definition message for weight_scale (local 1, global message 30)
        buf.write(0x41)          // definition record header, local 1
        buf.write(0x00)          // reserved
        buf.write(0x00)          // architecture: little-endian
        buf.writeLE16(30)        // global message number: weight_scale
        buf.write(2)             // number of fields
        // field 253: timestamp, size=4, base_type=uint32(0x86)
        buf.write(253); buf.write(4);  buf.write(0x86)
        // field 0:   weight,    size=2, base_type=uint16(0x84)
        buf.write(0);   buf.write(2);  buf.write(0x84)

        // Data message for weight_scale (local 1)
        buf.write(0x01)
        buf.writeLE32(fitTs)     // timestamp
        buf.writeLE16(weightRaw) // weight in units of 0.01 kg

        return buf.toByteArray()
    }

    private fun wrapInFitFile(payload: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()

        // Write 12-byte header body (without CRC)
        val hdrBuf = ByteArrayOutputStream()
        hdrBuf.write(0x0E)        // header size = 14
        hdrBuf.write(0x20)        // protocol version 2.0
        hdrBuf.writeLE16(2156)    // profile version
        hdrBuf.writeLE32(payload.size)
        hdrBuf.write(byteArrayOf(0x2E, 0x46, 0x49, 0x54))  // ".FIT"
        val hdrBytes = hdrBuf.toByteArray()

        result.write(hdrBytes)
        result.writeLE16(crc16(hdrBytes))  // header CRC
        result.write(payload)

        result.writeLE16(crc16(result.toByteArray()))  // file CRC

        return result.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLE16(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLE32(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun crc16(data: ByteArray): Int {
        val t = intArrayOf(
            0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
            0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400
        )
        var crc = 0
        for (b in data) {
            val byte = b.toInt() and 0xFF
            var tmp = t[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor t[byte and 0xF]
            tmp = t[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor t[(byte ushr 4) and 0xF]
        }
        return crc and 0xFFFF
    }
}
