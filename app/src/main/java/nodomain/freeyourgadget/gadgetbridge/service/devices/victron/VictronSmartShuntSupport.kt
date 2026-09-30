package nodomain.freeyourgadget.gadgetbridge.service.devices.victron

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.BatteryCurrentSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.BatteryPowerSampleProvider
import nodomain.freeyourgadget.gadgetbridge.devices.victron.VictronSmartShuntCoordinator
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryCurrentSample
import nodomain.freeyourgadget.gadgetbridge.entities.BatteryPowerSample
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_AMPERE
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_AMPERE_HOUR
import nodomain.freeyourgadget.gadgetbridge.model.ActivitySummaryEntries.UNIT_WATT
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLEScanService
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

class VictronSmartShuntSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private val batteryEvent = GBDeviceEventBatteryInfo()
    private val valueFormatter = WorkoutValueFormatter()

    // Accumulated notifications for 19ec65 key reassembly (may span fragments)
    private val keyNotifs = mutableListOf<ByteArray>()

    private val advertisementReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BLEScanService.EVENT_DEVICE_FOUND) return

            val address = intent.getStringExtra(BLEScanService.EXTRA_DEVICE_ADDRESS) ?: return
            if (address != device.address) return

            val bundle = intent.getBundleExtra(BLEScanService.EXTRA_MANUFACTURER_SPECIFIC_DATA) ?: return
            // Bundle keys are stringified company IDs (e.g. "737" for 0x02E1)
            val data = bundle.getByteArray(VictronInstantReadout.VICTRON_COMPANY_ID.toString()) ?: return
            onVictronAdvertisement(data)
        }
    }

    init {
        addSupportedService(UUID_SERVICE_VICTRON)
        addSupportedService(UUID_SERVICE_SMART)
    }

    override fun useAutoConnect(): Boolean {
        return true
    }

    override fun setContext(gbDevice: GBDevice, btAdapter: android.bluetooth.BluetoothAdapter, context: Context) {
        super.setContext(gbDevice, btAdapter, context)
        // Register for passive advertisement updates
        LocalBroadcastManager.getInstance(context).registerReceiver(
            advertisementReceiver,
            IntentFilter(BLEScanService.EVENT_DEVICE_FOUND)
        )
        LOG.debug("Registered advertisement receiver for {}", gbDevice.address)
    }

    override fun dispose() {
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(advertisementReceiver)
            LOG.debug("Unregistered advertisement receiver")
        } catch (e: Exception) {
            LOG.warn("Failed to unregister advertisement receiver", e)
        }
        super.dispose()
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        batteryEvent.level = -1
        batteryEvent.voltage = -1f
        device.resetExtraInfos()

        builder.setDeviceState(GBDevice.State.INITIALIZING)
        // Ask the shunt to push notifications indefinitely. Without this write
        // the device stops notifying once its keep-alive lapses and the metrics
        // silently drop out until the next reconnect.
        builder.write(UUID_CHARACTERISTIC_KEEP_ALIVE, 0xFF.toByte(), 0xFF.toByte())
        builder.notify(UUID_CHARACTERISTIC_KEEP_ALIVE, true)
        builder.notify(UUID_CHARACTERISTIC_CONSUMED, true)
        builder.notify(UUID_CHARACTERISTIC_POWER, true)
        builder.notify(UUID_CHARACTERISTIC_VOLTAGE, true)
        builder.notify(UUID_CHARACTERISTIC_CURRENT, true)
        builder.notify(UUID_CHARACTERISTIC_CHARGE, true)
        // One-shot reads so values show up immediately instead of waiting for
        // the first notification.
        builder.read(UUID_CHARACTERISTIC_CONSUMED)
        builder.read(UUID_CHARACTERISTIC_POWER)
        builder.read(UUID_CHARACTERISTIC_VOLTAGE)
        builder.read(UUID_CHARACTERISTIC_CURRENT)
        builder.read(UUID_CHARACTERISTIC_CHARGE)

        // If we don't have the advertisement key yet, retrieve it via 19ec65.
        // This enables passive Instant Readout telemetry from advertisements.
        if (getAdvertisementKey() == null) {
            LOG.info("No advertisement key stored, attempting GATT retrieval via 19ec65")
            builder.notify(UUID_SMART_CTRL, true)
            builder.notify(UUID_SMART_DATA, true)
            builder.notify(UUID_SMART_SETUP, true)
            // Smart-service login: fa80ff + f980 to CTRL
            builder.write(UUID_SMART_CTRL, 0xFA.toByte(), 0x80.toByte(), 0xFF.toByte())
            builder.write(UUID_SMART_CTRL, 0xF9.toByte(), 0x80.toByte())
            // Handshake: 01 and 0300 to DATA
            builder.write(UUID_SMART_DATA, 0x01.toByte())
            builder.write(UUID_SMART_DATA, 0x03.toByte(), 0x00.toByte())
            // Request 19ec65: SETUP_LAST + READ_EC65
            // SETUP_LAST = get(19ec66, 19ec65) - 20 bytes
            builder.write(UUID_SMART_DATA, *SETUP_LAST)
            // READ_EC65 = explicit get for 19ec65
            builder.write(UUID_SMART_DATA, *READ_EC65)
            keyNotifs.clear()
        }

        builder.setDeviceState(GBDevice.State.INITIALIZED)
        return builder
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        // Check for 19ec65 key response on Smart service
        if (characteristic.uuid == UUID_SMART_DATA || characteristic.uuid == UUID_SMART_SETUP) {
            keyNotifs.add(value)
            val key = reassembleAdkey(keyNotifs)
            if (key != null) {
                LOG.info("Retrieved 16-byte advertisement key via 19ec65")
                setAdvertisementKey(key)
                keyNotifs.clear()
                // Disable Smart notifies now that we have the key
                // (keep the 6597 telemetry notifies active)
            }
            return true
        }

        if (handleCharacteristic(characteristic.uuid, value)) {
            return true
        }

        return super.onCharacteristicChanged(gatt, characteristic, value)
    }

    override fun onCharacteristicRead(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int
    ): Boolean {
        if (status != BluetoothGatt.GATT_SUCCESS) {
            return super.onCharacteristicRead(gatt, characteristic, value, status)
        }

        if (handleCharacteristic(characteristic.uuid, value)) {
            return true
        }

        return super.onCharacteristicRead(gatt, characteristic, value, status)
    }

    /**
     * Process a Victron Instant Readout advertisement.
     * Decrypts using the stored key and updates device telemetry.
     */
    private fun onVictronAdvertisement(manufacturerData: ByteArray) {
        val keyHex = getAdvertisementKey() ?: run {
            LOG.debug("No advertisement key, skipping passive decrypt")
            return
        }

        val decrypted = VictronInstantReadout.decrypt(manufacturerData, keyHex) ?: return
        val readout = VictronInstantReadout.parseSmartShunt(decrypted) ?: return

        LOG.debug("Instant Readout: {}V {}A {}% ({}Ah)",
            readout.voltage, readout.current, readout.soc, readout.consumedAh)

        // Update device with passive telemetry
        readout.voltage?.let {
            batteryEvent.voltage = it.toFloat()
            evaluateGBDeviceEvent(batteryEvent)
        }
        readout.soc?.let {
            batteryEvent.level = it.roundToInt()
            evaluateGBDeviceEvent(batteryEvent)
        }
        readout.consumedAh?.let {
            device.setExtraInfo(
                VictronSmartShuntCoordinator.EXTRA_CONSUMED,
                valueFormatter.formatValue(it, UNIT_AMPERE_HOUR)
            )
        }
        readout.current?.let { current ->
            device.setExtraInfo(
                VictronSmartShuntCoordinator.EXTRA_CURRENT,
                valueFormatter.formatValue(current, UNIT_AMPERE)
            )
            // Power = V * A
            readout.voltage?.let { voltage ->
                val power = (voltage * current).toInt()
                device.setExtraInfo(
                    VictronSmartShuntCoordinator.EXTRA_POWER,
                    valueFormatter.formatValue(power, UNIT_WATT)
                )
            }
        }
        device.sendDeviceUpdateIntent(context)
    }

    /**
     * Reassemble the 19ec65 response from notification fragments.
     * Looks for 19 ec 65 50 marker, extracts 16 bytes after 6-byte header.
     */
    private fun reassembleAdkey(notifications: List<ByteArray>): ByteArray? {
        val marker = byteArrayOf(0x19, 0xEC.toByte(), 0x65, 0x50)
        for (i in notifications.indices) {
            val blob = notifications[i]
            val idx = blob.indexOfSubarray(marker)
            if (idx < 0) continue
            val hdrStart = idx - 2
            if (hdrStart < 0) continue
            // Full 22-byte frame: 6-byte header + 16-byte key
            if (blob.size >= hdrStart + 22) {
                return blob.copyOfRange(hdrStart + 6, hdrStart + 22)
            }
            // Fragmented: accumulate continuations
            val key = blob.copyOfRange(hdrStart + 6, blob.size).toMutableList()
            for (j in i + 1 until notifications.size) {
                val nxt = notifications[j]
                if (nxt.isEmpty()) continue
                // New frame starts: 08/09/f9/f7/07/02
                if (nxt[0] in byteArrayOf(0x08, 0x09, 0xF9.toByte(), 0xF7.toByte(), 0x07, 0x02)) break
                key.addAll(nxt.toList())
                if (key.size >= 16) break
            }
            if (key.size >= 16) {
                return key.take(16).toByteArray()
            }
        }
        return null
    }

    private fun ByteArray.indexOfSubarray(sub: ByteArray): Int {
        outer@ for (i in 0..size - sub.size) {
            for (j in sub.indices) {
                if (this[i + j] != sub[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /**
     * Get the stored advertisement key (hex string), or null if not set.
     */
    private fun getAdvertisementKey(): String? {
        val prefs = Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.address))
        val key = prefs.getString(PREF_ADVERTISEMENT_KEY, null)
        return if (key.isNullOrBlank()) null else key
    }

    /**
     * Store the advertisement key (as hex string) in device-specific prefs.
     */
    private fun setAdvertisementKey(keyBytes: ByteArray) {
        val hex = keyBytes.joinToString("") { "%02x".format(it) }
        val prefs = Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.address))
        prefs.getPreferences().edit().putString(PREF_ADVERTISEMENT_KEY, hex).apply()
        LOG.info("Stored advertisement key for {}", device.address)
    }

    fun handleCharacteristic(characteristicUUID: UUID, value: ByteArray): Boolean {
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        when (characteristicUUID) {
            UUID_CHARACTERISTIC_KEEP_ALIVE -> {
                // type=un16, scale=0.001, unit=seconds
                val raw = buf.short.toInt() and 0xFFFF
                if (raw == KEEP_ALIVE_FOREVER) {
                    LOG.debug("Keep-alive: forever")
                } else {
                    LOG.debug("Keep-alive: {} s", raw * 0.001)
                }
                return true
            }

            UUID_CHARACTERISTIC_CONSUMED -> {
                // type=sn32, scale=0.1, unit=Ah
                val raw = buf.int
                val consumedAh = raw * 0.1
                LOG.debug("Consumed: {} Ah", consumedAh)
                device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_CONSUMED, valueFormatter.formatValue(consumedAh, UNIT_AMPERE_HOUR))
                device.sendDeviceUpdateIntent(context)
                return true
            }

            UUID_CHARACTERISTIC_POWER -> {
                // type=sb16, scale=1, unit=W
                val raw = buf.short.toInt()
                if (raw == POWER_NOT_AVAILABLE) {
                    // Transient N/A: keep the last good value instead of blanking.
                    LOG.debug("Power: N/A, keeping last value")
                } else {
                    LOG.debug("Power: {} W", raw)
                    device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_POWER, valueFormatter.formatValue(raw, UNIT_WATT))

                    try {
                        GBApplication.acquireDB().use { db ->
                            val batteryPowerSampleProvider = BatteryPowerSampleProvider(gbDevice, db.daoSession)
                            val sample = BatteryPowerSample()
                            sample.timestamp = System.currentTimeMillis()
                            sample.batteryIndex = 0
                            sample.power = raw.toFloat()
                            batteryPowerSampleProvider.persistSamples(sample, context)
                        }
                    } catch (e: Exception) {
                        LOG.error("Error persisting power sample", e)
                    }
                }
                device.sendDeviceUpdateIntent(context)
                return true
            }

            UUID_CHARACTERISTIC_VOLTAGE -> {
                // type=sn16, scale=0.01, unit=V
                val raw = buf.short.toInt()
                if (raw == VOLTAGE_NOT_AVAILABLE) {
                    // Transient N/A: keep the last good value instead of blanking.
                    LOG.debug("Voltage: N/A, keeping last value")
                } else {
                    val volts = raw * 0.01
                    LOG.debug("Voltage: {} V", volts)
                    batteryEvent.voltage = volts.toFloat()
                    // Persisted to the database by the event
                    evaluateGBDeviceEvent(batteryEvent)
                }
                device.sendDeviceUpdateIntent(context)
                return true
            }

            UUID_CHARACTERISTIC_CURRENT -> {
                // type=sn32, scale=0.001, unit=A
                val raw = buf.int
                if (raw == CURRENT_NOT_AVAILABLE) {
                    // Transient N/A: keep the last good value instead of blanking.
                    LOG.debug("Current: N/A, keeping last value")
                } else {
                    val current = raw * 0.001f
                    LOG.debug("Current: {} A", current)
                    device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_CURRENT, valueFormatter.formatValue(current, UNIT_AMPERE))

                    try {
                        GBApplication.acquireDB().use { db ->
                            val batteryCurrentSampleProvider = BatteryCurrentSampleProvider(gbDevice, db.daoSession)
                            val sample = BatteryCurrentSample()
                            sample.timestamp = System.currentTimeMillis()
                            sample.batteryIndex = 0
                            sample.current = current
                            batteryCurrentSampleProvider.persistSamples(sample, context)
                        }
                    } catch (e: Exception) {
                        LOG.error("Error persisting power sample", e)
                    }
                }
                device.sendDeviceUpdateIntent(context)
                return true
            }

            UUID_CHARACTERISTIC_CHARGE -> {
                // type=un16, scale=0.01, unit=%
                val raw = buf.short.toInt() and 0xFFFF
                if (raw == CHARGE_NOT_AVAILABLE) {
                    // Transient N/A: keep the last good value instead of blanking.
                    LOG.debug("Charge: N/A, keeping last value")
                } else {
                    LOG.debug("Charge: {} %", raw * 0.01)
                    batteryEvent.level = (raw * 0.01).roundToInt()
                    evaluateGBDeviceEvent(batteryEvent)
                }
                return true
            }
        }

        return false
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(VictronSmartShuntSupport::class.java)

        private const val PREF_ADVERTISEMENT_KEY = "victron_advertisement_key"

        /// https://communityarchive.victronenergy.com/questions/93919/victron-bluetooth-ble-protocol-publication.html
        private val UUID_SERVICE_VICTRON = UUID.fromString("65970000-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_KEEP_ALIVE = UUID.fromString("6597ffff-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CONSUMED = UUID.fromString("6597eeff-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_POWER = UUID.fromString("6597ed8e-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_VOLTAGE = UUID.fromString("6597ed8d-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CURRENT = UUID.fromString("6597ed8c-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CHARGE = UUID.fromString("65970fff-4bda-4c1e-af4b-551c4cf74769")

        // Smart service (306b) for 19ec65 advertisement key retrieval
        private val UUID_SERVICE_SMART = UUID.fromString("306b0001-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_CTRL = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_DATA = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_SETUP = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

        // 19ec65 key retrieval frames (from victron-ble adkey.py)
        // SETUP_LAST: get(19ec66, 19ec65) - 20 bytes
        private val SETUP_LAST = byteArrayOf(
            0x06, 0x00, 0x82.toByte(), 0x18, 0x93.toByte(), 0x42,
            0x10, 0x27, 0x05, 0x00, 0x82.toByte(), 0x19,
            0xEC.toByte(), 0x66, 0x19, 0xEC.toByte(), 0x65,
            0x03, 0x01, 0x03
        )
        // READ_EC65: explicit get for 19ec65
        private val READ_EC65 = byteArrayOf(
            0x05, 0x03, 0x81.toByte(), 0x19, 0xEC.toByte(), 0x65
        )

        private const val KEEP_ALIVE_FOREVER = 0xFFFF
        private const val POWER_NOT_AVAILABLE = 0x7FFF
        private const val VOLTAGE_NOT_AVAILABLE = 0x7FFF
        private const val CURRENT_NOT_AVAILABLE = 0x7FFFFFFF
        private const val CHARGE_NOT_AVAILABLE = 0xFFFF
    }
}
