package nodomain.freeyourgadget.gadgetbridge.devices.victron

import org.slf4j.LoggerFactory
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.roundToInt

data class VictronInstantReadoutData(
    val voltage: Float? = null,
    val current: Float? = null,
    val consumedAh: Double? = null,
    val soc: Float? = null,
    val power: Int? = null
)

object VictronInstantReadoutParser {
    private val LOG = LoggerFactory.getLogger(VictronInstantReadoutParser::class.java)

    private const val PREFIX_INSTANT_READOUT = 0x10.toByte()
    private const val VICTRON_TYPE_BATTERY_MONITOR = 0x02.toByte()

    fun parse(data: ByteArray, keyHex: String? = null): VictronInstantReadoutData? {
        if (data.size < 7) {
            return null
        }

        if (data[0] != PREFIX_INSTANT_READOUT) {
            return null
        }

        var payload: ByteArray? = null

        if (!keyHex.isNullOrBlank() && keyHex.length == 32) {
            try {
                val keyBytes = hexToBytes(keyHex)
                if (keyBytes != null && keyBytes.isNotEmpty()) {
                    val keyCheck = data[6]
                    if (keyCheck == keyBytes[0]) {
                        val ivCounter = ByteArray(16)
                        ivCounter[0] = data[4]
                        ivCounter[1] = data[5]

                        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                        val secretKey = SecretKeySpec(keyBytes, "AES")
                        val ivSpec = IvParameterSpec(ivCounter)
                        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)

                        if (data.size > 7) {
                            payload = cipher.doFinal(data, 7, data.size - 7)
                        }
                    }
                }
            } catch (e: Exception) {
                LOG.debug("Decryption failed: {}", e.message)
            }
        }

        if (payload == null) {
            if (data.size >= 21) {
                payload = data.copyOfRange(7, data.size)
            } else if (data.size >= 14) {
                payload = data.copyOfRange(4, data.size)
            } else {
                return null
            }
        }

        return parseBatteryMonitorPayload(payload)
    }

    private fun parseBatteryMonitorPayload(payload: ByteArray): VictronInstantReadoutData? {
        if (payload.size < 15) {
            return null
        }

        val reader = BitReader(payload)

        // remaining_mins: u16 (0xFFFF sentinel)
        reader.readUnsigned(16)

        // voltage: s16 (scale: /100) (0x7FFF sentinel)
        val rawVoltageUnsigned = reader.readUnsigned(16)
        val rawVoltageSigned = toSigned(rawVoltageUnsigned, 16)
        val voltage = if (rawVoltageUnsigned == 0x7FFFL || rawVoltageUnsigned == 0xFFFFL) {
            null
        } else {
            rawVoltageSigned / 100.0f
        }

        // alarm: u16
        reader.readUnsigned(16)

        // aux: u16
        reader.readUnsigned(16)

        // aux_mode: u2
        reader.readUnsigned(2)

        // current: s22 (scale: /1000) (0x3FFFFF sentinel)
        val rawCurrentUnsigned = reader.readUnsigned(22)
        val rawCurrentSigned = toSigned(rawCurrentUnsigned, 22)
        val current = if (rawCurrentUnsigned == 0x3FFFFFL) {
            null
        } else {
            rawCurrentSigned / 1000.0f
        }

        // consumed_ah: u20 (scale: /10, negated) (0xFFFFF sentinel)
        val rawConsumedUnsigned = reader.readUnsigned(20)
        val consumedAh = if (rawConsumedUnsigned == 0xFFFFFL) {
            null
        } else {
            -(rawConsumedUnsigned / 10.0)
        }

        // soc: u10 (scale: /10) (0x3FF sentinel)
        val rawSocUnsigned = reader.readUnsigned(10)
        val soc = if (rawSocUnsigned == 0x3FFL) {
            null
        } else {
            rawSocUnsigned / 10.0f
        }

        val power = if (voltage != null && current != null) {
            (voltage * current).roundToInt()
        } else {
            null
        }

        return VictronInstantReadoutData(
            voltage = voltage,
            current = current,
            consumedAh = consumedAh,
            soc = soc,
            power = power
        )
    }

    private fun toSigned(value: Long, bits: Int): Long {
        val signBit = 1L shl (bits - 1)
        return if ((value and signBit) != 0L) {
            value or (-1L shl bits)
        } else {
            value
        }
    }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }

    private class BitReader(private val bytes: ByteArray) {
        private var bitOffset = 0

        fun readUnsigned(bits: Int): Long {
            var value = 0L
            for (i in 0 until bits) {
                val byteIndex = (bitOffset + i) / 8
                val bitIndex = (bitOffset + i) % 8
                if (byteIndex < bytes.size) {
                    val bit = (bytes[byteIndex].toInt() shr bitIndex) and 1
                    value = value or (bit.toLong() shl i)
                }
            }
            bitOffset += bits
            return value
        }
    }
}
