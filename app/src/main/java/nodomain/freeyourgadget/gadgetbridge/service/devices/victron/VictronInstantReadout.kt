package nodomain.freeyourgadget.gadgetbridge.service.devices.victron

import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Victron Instant Readout advertisement decryption and parsing.
 *
 * Ported from the victron-ble Python package (keshavdv/victron-ble).
 * Advertisements carry AES-128-CTR encrypted telemetry in the
 * manufacturer-specific data (company ID 0x02E1).
 *
 * Container format (after company ID):
 *   [0:2]  prefix (u16 LE)
 *   [2:4]  model_id (u16 LE)
 *   [4]    readout_type (u8)
 *   [5:7]  iv (u16 LE) - CTR counter initial value
 *   [7:]   encrypted_data
 *
 * The first byte of encrypted_data must match the first byte of the
 * advertisement key (key check byte).
 */
object VictronInstantReadout {
    private val LOG = LoggerFactory.getLogger(VictronInstantReadout::class.java)

    const val VICTRON_COMPANY_ID = 0x02E1

    /**
     * Decrypt Instant Readout manufacturer data.
     *
     * @param manufacturerData Raw manufacturer-specific data (after company ID)
     * @param keyHex 32-char hex advertisement key
     * @return Decrypted payload, or null if key mismatch or decryption fails
     */
    fun decrypt(manufacturerData: ByteArray, keyHex: String): ByteArray? {
        if (manufacturerData.size < 8) {
            LOG.debug("Manufacturer data too short: {}", manufacturerData.size)
            return null
        }

        val buf = ByteBuffer.wrap(manufacturerData).order(ByteOrder.LITTLE_ENDIAN)
        val iv = buf.getShort(5).toInt() and 0xFFFF
        val encryptedData = manufacturerData.copyOfRange(7, manufacturerData.size)

        val keyBytes = try {
            keyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            LOG.warn("Invalid key hex", e)
            return null
        }

        if (keyBytes.size != 16) {
            LOG.warn("Key must be 16 bytes, got {}", keyBytes.size)
            return null
        }

        // Key check byte: first byte of encrypted data must match first byte of key
        if (encryptedData.isEmpty() || encryptedData[0] != keyBytes[0]) {
            LOG.debug("Key check byte mismatch - wrong key for this device")
            return null
        }

        return try {
            // CTR counter: 128-bit, initial value = iv (little-endian in low bytes)
            val ivBytes = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(iv.toShort()).array()

            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                IvParameterSpec(ivBytes)
            )
            cipher.doFinal(encryptedData)
        } catch (e: Exception) {
            LOG.warn("Decryption failed", e)
            null
        }
    }

    /**
     * Parse decrypted SmartShunt (battery monitor) payload.
     *
     * Bit layout (LSB-first, from victron_ble/devices/battery_monitor.py):
     *   remaining_mins: 16 bits unsigned (0xFFFF = N/A)
     *   voltage: 16 bits signed, /100 V (0x7FFF = N/A)
     *   alarm: 16 bits unsigned
     *   aux: 16 bits unsigned
     *   aux_mode: 2 bits unsigned (0=starter, 1=midpoint, 2=temp, 3=disabled)
     *   current: 22 bits signed, /1000 A (0x3FFFFF = N/A)
     *   consumed_ah: 20 bits unsigned, -value/10 Ah (0xFFFFF = N/A)
     *   soc: 10 bits unsigned, /10 % (0x3FF = N/A)
     */
    fun parseSmartShunt(decrypted: ByteArray): SmartShuntReadout? {
        if (decrypted.size < 15) {
            LOG.debug("Decrypted data too short for SmartShunt: {}", decrypted.size)
            return null
        }

        return try {
            val reader = BitReader(decrypted)

            val remainingMins = reader.readUnsignedInt(16)
            val voltageRaw = reader.readSignedInt(16)
            val alarm = reader.readUnsignedInt(16)
            val aux = reader.readUnsignedInt(16)
            val auxMode = reader.readUnsignedInt(2)
            val currentRaw = reader.readSignedInt(22)
            val consumedAhRaw = reader.readUnsignedInt(20)
            val socRaw = reader.readUnsignedInt(10)

            SmartShuntReadout(
                remainingMins = if (remainingMins != 0xFFFF) remainingMins else null,
                voltage = if (voltageRaw != 0x7FFF) voltageRaw / 100.0 else null,
                alarm = alarm,
                auxMode = auxMode,
                current = if (currentRaw != 0x3FFFFF) currentRaw / 1000.0 else null,
                consumedAh = if (consumedAhRaw != 0xFFFFF) -consumedAhRaw / 10.0 else null,
                soc = if (socRaw != 0x3FF) socRaw / 10.0 else null,
                auxValue = aux
            )
        } catch (e: Exception) {
            LOG.warn("Failed to parse SmartShunt payload", e)
            null
        }
    }

    /**
     * Bit reader for Victron's LSB-first packed structures.
     */
    private class BitReader(private val data: ByteArray) {
        private var index = 0

        private fun readBit(): Int {
            val bit = (data[index shr 3].toInt() shr (index and 7)) and 1
            index++
            return bit
        }

        fun readUnsignedInt(numBits: Int): Int {
            var value = 0
            for (position in 0 until numBits) {
                value = value or (readBit() shl position)
            }
            return value
        }

        fun readSignedInt(numBits: Int): Int {
            val unsigned = readUnsignedInt(numBits)
            return if (unsigned and (1 shl (numBits - 1)) != 0) {
                unsigned - (1 shl numBits)
            } else {
                unsigned
            }
        }
    }
}

/**
 * Parsed SmartShunt Instant Readout telemetry.
 */
data class SmartShuntReadout(
    val remainingMins: Int?,
    val voltage: Double?,
    val alarm: Int,
    val auxMode: Int,
    val current: Double?,
    val consumedAh: Double?,
    val soc: Double?,
    val auxValue: Int
)
