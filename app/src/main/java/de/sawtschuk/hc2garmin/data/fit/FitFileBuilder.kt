package de.sawtschuk.hc2garmin.data.fit

import java.io.ByteArrayOutputStream

object FitFileBuilder {

    private const val FIT_EPOCH_OFFSET = 631065600L  // seconds between 1970-01-01 and 1989-12-31

    fun buildWeightFitFile(
        weightKg: Double,
        fatPercent: Double?,
        epochSeconds: Long
    ): ByteArray {
        val fitTs = (epochSeconds - FIT_EPOCH_OFFSET).toInt()
        val weightRaw = (weightKg * 100 + 0.5).toInt()  // uint16, scale=100, unit=kg
        val fatRaw = if (fatPercent != null) (fatPercent * 100 + 0.5).toInt() else 0xFFFF

        val payload = buildWeightPayload(fitTs, weightRaw, fatRaw)
        return wrapInFitFile(payload)
    }

    fun buildBloodPressureFitFile(
        systolicMmhg: Int,
        diastolicMmhg: Int,
        epochSeconds: Long,
        heartRateBpm: Int = 72
    ): ByteArray {
        val fitTs = (epochSeconds - FIT_EPOCH_OFFSET).toInt()
        val payload = buildBloodPressurePayload(fitTs, systolicMmhg, diastolicMmhg, heartRateBpm)
        return wrapInFitFile(payload)
    }

    private fun buildWeightPayload(fitTs: Int, weightRaw: Int, fatRaw: Int): ByteArray {
        val buf = ByteArrayOutputStream()

        // Definition message for file_id (local 0, global message 0)
        buf.write(0x40)          // definition record header, local 0
        buf.write(0x00)          // reserved
        buf.write(0x00)          // architecture: little-endian
        buf.writeLE16(0)         // global message number: file_id
        buf.write(3)             // number of fields
        buf.write(0);  buf.write(1);  buf.write(0x00)  // field 0: type, enum
        buf.write(1);  buf.write(2);  buf.write(0x84)  // field 1: manufacturer, uint16
        buf.write(4);  buf.write(4);  buf.write(0x86)  // field 4: time_created, uint32

        // Data message for file_id (local 0)
        buf.write(0x00)
        buf.write(9)             // type = 9 = weight scale file
        buf.writeLE16(255)       // manufacturer = 255 (unknown/development)
        buf.writeLE32(fitTs)     // time_created

        // Definition message for weight_scale (local 1, global message 30)
        buf.write(0x41)
        buf.write(0x00)
        buf.write(0x00)
        buf.writeLE16(30)
        buf.write(3)
        buf.write(253); buf.write(4);  buf.write(0x86)  // timestamp, uint32
        buf.write(0);   buf.write(2);  buf.write(0x84)  // weight, uint16
        buf.write(1);   buf.write(2);  buf.write(0x84)  // percent_fat, uint16

        // Data message for weight_scale (local 1)
        buf.write(0x01)
        buf.writeLE32(fitTs)
        buf.writeLE16(weightRaw)
        buf.writeLE16(fatRaw)

        return buf.toByteArray()
    }

    private fun buildBloodPressurePayload(
        fitTs: Int,
        systolicMmhg: Int,
        diastolicMmhg: Int,
        heartRateBpm: Int
    ): ByteArray {
        val mapMmhg = diastolicMmhg + (systolicMmhg - diastolicMmhg) / 3
        val buf = ByteArrayOutputStream()

        // Definition message for file_id (local 0, global message 0)
        buf.write(0x40)          // definition record header, local 0
        buf.write(0x00)          // reserved
        buf.write(0x00)          // architecture: little-endian
        buf.writeLE16(0)         // global message number: file_id
        buf.write(3)             // number of fields
        buf.write(0);  buf.write(1);  buf.write(0x00)  // field 0: type, enum
        buf.write(1);  buf.write(2);  buf.write(0x84)  // field 1: manufacturer, uint16
        buf.write(4);  buf.write(4);  buf.write(0x86)  // field 4: time_created, uint32

        // Data message for file_id (local 0)
        buf.write(0x00)
        buf.write(14)            // type = 14 = blood_pressure file
        buf.writeLE16(255)       // manufacturer = 255 (unknown/development)
        buf.writeLE32(fitTs)     // time_created

        // Definition message for blood_pressure (local 1, global message 51)
        buf.write(0x41)          // definition record header, local 1
        buf.write(0x00)          // reserved
        buf.write(0x00)          // architecture: little-endian
        buf.writeLE16(51)        // global message number: blood_pressure
        buf.write(5)             // number of fields
        buf.write(253); buf.write(4); buf.write(0x86)  // field 253: timestamp, uint32
        buf.write(0);   buf.write(2); buf.write(0x84)  // field 0: systolic_pressure, uint16, mmHg
        buf.write(1);   buf.write(2); buf.write(0x84)  // field 1: diastolic_pressure, uint16, mmHg
        buf.write(2);   buf.write(2); buf.write(0x84)  // field 2: mean_arterial_pressure, uint16, mmHg
        buf.write(6);   buf.write(1); buf.write(0x02)  // field 6: heart_rate, uint8, bpm

        // Data message for blood_pressure (local 1)
        buf.write(0x01)
        buf.writeLE32(fitTs)
        buf.writeLE16(systolicMmhg)
        buf.writeLE16(diastolicMmhg)
        buf.writeLE16(mapMmhg)
        buf.write(heartRateBpm and 0xFF)

        return buf.toByteArray()
    }

    private fun wrapInFitFile(payload: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()

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
