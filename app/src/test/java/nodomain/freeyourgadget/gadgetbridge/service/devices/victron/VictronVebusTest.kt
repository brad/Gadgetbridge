package nodomain.freeyourgadget.gadgetbridge.service.devices.victron

import nodomain.freeyourgadget.gadgetbridge.service.btle.BleVictronTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VictronVebusTest {

    @Test
    fun testSetValueCodec() {
        val path = byteArrayOf(0x19.toByte(), 0x02.toByte(), 0x00.toByte())
        val value = byteArrayOf(0x03.toByte())
        val type = 0x41.toByte()

        val encoded = BleVictronTransport.encodeSetValue(path, type, value)
        val expected = byteArrayOf(0x06.toByte(), 0x03.toByte(), 0x82.toByte(), 0x19.toByte(), 0x02.toByte(), 0x00.toByte(), 0x41.toByte(), 0x03.toByte())

        assertArrayEquals(expected, encoded)
    }

    @Test
    fun testGetValuesCodec() {
        val path = byteArrayOf(0x19.toByte(), 0x02.toByte(), 0x00.toByte())
        val encoded = BleVictronTransport.encodeGetValues(path)
        val expected = byteArrayOf(0x05.toByte(), 0x03.toByte(), 0x81.toByte(), 0x19.toByte(), 0x02.toByte(), 0x00.toByte())

        assertArrayEquals(expected, encoded)
    }
}
