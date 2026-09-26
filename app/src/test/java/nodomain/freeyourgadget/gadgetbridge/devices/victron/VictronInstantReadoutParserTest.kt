package nodomain.freeyourgadget.gadgetbridge.devices.victron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class VictronInstantReadoutParserTest {

    @Test
    fun testParseInvalidShortData() {
        val data = byteArrayOf(0x10, 0x01, 0x02)
        val readout = VictronInstantReadoutParser.parse(data)
        assertNull(readout)
    }

    @Test
    fun testParseInvalidPrefix() {
        val data = ByteArray(20)
        data[0] = 0x20
        val readout = VictronInstantReadoutParser.parse(data)
        assertNull(readout)
    }

    @Test
    fun testParseUnencryptedBatteryMonitorPayload() {
        val payload = buildBatteryMonitorPayload(
            remainingMins = 0xFFFF,
            voltageRaw = 1280,      // 12.80 V
            alarm = 0,
            aux = 0,
            auxMode = 0,
            currentRaw = -1500,     // -1.500 A
            consumedAhRaw = 452,    // -45.2 Ah
            socRaw = 855            // 85.5 %
        )

        // Add 7-byte header
        val advertisement = ByteArray(7 + payload.size)
        advertisement[0] = 0x10.toByte() // prefix
        advertisement[1] = 0x89.toByte() // model id low
        advertisement[2] = 0xA3.toByte() // model id high
        advertisement[3] = 0x02.toByte() // readout type
        advertisement[4] = 0x01.toByte() // iv low
        advertisement[5] = 0x00.toByte() // iv high
        advertisement[6] = 0xAA.toByte() // key check
        System.arraycopy(payload, 0, advertisement, 7, payload.size)

        val readout = VictronInstantReadoutParser.parse(advertisement)
        assertNotNull(readout)
        assertEquals(12.80f, readout!!.voltage!!, 0.01f)
        assertEquals(-1.500f, readout.current!!, 0.001f)
        assertEquals(-45.2, readout.consumedAh!!, 0.01)
        assertEquals(85.5f, readout.soc!!, 0.1f)
        assertEquals(-19, readout.power!!)
    }

    @Test
    fun testParseSentinels() {
        val payload = buildBatteryMonitorPayload(
            remainingMins = 0xFFFF,
            voltageRaw = 0x7FFF,   // Voltage sentinel
            alarm = 0,
            aux = 0,
            auxMode = 0,
            currentRaw = 0x3FFFFF, // Current sentinel
            consumedAhRaw = 0xFFFFF, // Consumed Ah sentinel
            socRaw = 0x3FF          // SOC sentinel
        )

        val advertisement = ByteArray(7 + payload.size)
        advertisement[0] = 0x10.toByte()
        System.arraycopy(payload, 0, advertisement, 7, payload.size)

        val readout = VictronInstantReadoutParser.parse(advertisement)
        assertNotNull(readout)
        assertNull(readout!!.voltage)
        assertNull(readout.current)
        assertNull(readout.consumedAh)
        assertNull(readout.soc)
        assertNull(readout.power)
    }

    @Test
    fun testParseEncryptedPayloadWithKey() {
        val keyHex = "0123456789abcdef0123456789abcdef"
        val keyBytes = hexToBytes(keyHex)

        val plaintextPayload = buildBatteryMonitorPayload(
            remainingMins = 120,
            voltageRaw = 1320,      // 13.20 V
            alarm = 0,
            aux = 0,
            auxMode = 0,
            currentRaw = 2500,      // 2.500 A
            consumedAhRaw = 100,    // -10.0 Ah
            socRaw = 950            // 95.0 %
        )

        val ivCounter = ByteArray(16)
        ivCounter[0] = 0x42.toByte()
        ivCounter[1] = 0x00.toByte()

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivCounter))
        val encryptedPayload = cipher.doFinal(plaintextPayload)

        val advertisement = ByteArray(7 + encryptedPayload.size)
        advertisement[0] = 0x10.toByte() // prefix
        advertisement[1] = 0x89.toByte() // model id low
        advertisement[2] = 0xA3.toByte() // model id high
        advertisement[3] = 0x02.toByte() // readout type
        advertisement[4] = 0x42.toByte() // iv low
        advertisement[5] = 0x00.toByte() // iv high
        advertisement[6] = keyBytes[0]   // key check byte matching keyBytes[0]
        System.arraycopy(encryptedPayload, 0, advertisement, 7, encryptedPayload.size)

        val readout = VictronInstantReadoutParser.parse(advertisement, keyHex)
        assertNotNull(readout)
        assertEquals(13.20f, readout!!.voltage!!, 0.01f)
        assertEquals(2.500f, readout.current!!, 0.001f)
        assertEquals(-10.0, readout.consumedAh!!, 0.01)
        assertEquals(95.0f, readout.soc!!, 0.1f)
        assertEquals(33, readout.power!!)
    }

    private fun buildBatteryMonitorPayload(
        remainingMins: Int,
        voltageRaw: Int,
        alarm: Int,
        aux: Int,
        auxMode: Int,
        currentRaw: Int,
        consumedAhRaw: Int,
        socRaw: Int
    ): ByteArray {
        val writer = BitWriter(16)
        writer.writeUnsigned(remainingMins.toLong(), 16)
        writer.writeUnsigned(voltageRaw.toLong(), 16)
        writer.writeUnsigned(alarm.toLong(), 16)
        writer.writeUnsigned(aux.toLong(), 16)
        writer.writeUnsigned(auxMode.toLong(), 2)
        writer.writeUnsigned(currentRaw.toLong(), 22)
        writer.writeUnsigned(consumedAhRaw.toLong(), 20)
        writer.writeUnsigned(socRaw.toLong(), 10)
        return writer.toByteArray()
    }

    private fun hexToBytes(hex: String): ByteArray {
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    private class BitWriter(capacityBytes: Int) {
        private val buffer = ByteArray(capacityBytes)
        private var bitOffset = 0

        fun writeUnsigned(value: Long, bits: Int) {
            for (i in 0 until bits) {
                val bit = ((value ushr i) and 1L).toInt()
                val byteIndex = (bitOffset + i) / 8
                val bitIndex = (bitOffset + i) % 8
                if (byteIndex < buffer.size) {
                    buffer[byteIndex] = (buffer[byteIndex].toInt() or (bit shl bitIndex)).toByte()
                }
            }
            bitOffset += bits
        }

        fun toByteArray(): ByteArray = buffer
    }
}
