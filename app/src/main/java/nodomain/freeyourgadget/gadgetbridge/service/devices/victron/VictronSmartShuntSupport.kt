package nodomain.freeyourgadget.gadgetbridge.service.devices.victron

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.content.Intent
import nodomain.freeyourgadget.gadgetbridge.devices.victron.VictronInstantReadoutData
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
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.roundToInt

class VictronSmartShuntSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private val batteryEvent = GBDeviceEventBatteryInfo()
    private val valueFormatter = WorkoutValueFormatter()

    init {
        addSupportedService(UUID_SERVICE_VICTRON)
    }

    override fun useAutoConnect(): Boolean {
        return true
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
        builder.setDeviceState(GBDevice.State.INITIALIZED)
        return builder
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
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
        fun handleInstantReadout(readout: VictronInstantReadoutData, device: GBDevice, context: Context) {
            val valueFormatter = WorkoutValueFormatter()

            readout.consumedAh?.let { consumedAh ->
                device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_CONSUMED, valueFormatter.formatValue(consumedAh, UNIT_AMPERE_HOUR))
            }

            readout.power?.let { power ->
                device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_POWER, valueFormatter.formatValue(power, UNIT_WATT))
                try {
                    GBApplication.acquireDB().use { db ->
                        val sampleProvider = BatteryPowerSampleProvider(device, db.daoSession)
                        val sample = BatteryPowerSample().apply {
                            timestamp = System.currentTimeMillis()
                            batteryIndex = 0
                            this.power = power.toFloat()
                        }
                        sampleProvider.persistSamples(sample, context)
                    }
                } catch (e: Exception) {
                    LOG.error("Error persisting power sample from Instant Readout", e)
                }
            }

            readout.current?.let { current ->
                device.setExtraInfo(VictronSmartShuntCoordinator.EXTRA_CURRENT, valueFormatter.formatValue(current, UNIT_AMPERE))
                try {
                    GBApplication.acquireDB().use { db ->
                        val sampleProvider = BatteryCurrentSampleProvider(device, db.daoSession)
                        val sample = BatteryCurrentSample().apply {
                            timestamp = System.currentTimeMillis()
                            batteryIndex = 0
                            this.current = current
                        }
                        sampleProvider.persistSamples(sample, context)
                    }
                } catch (e: Exception) {
                    LOG.error("Error persisting current sample from Instant Readout", e)
                }
            }

            readout.soc?.let { soc ->
                if (soc >= 0f) {
                    device.setBatteryLevel(soc.roundToInt(), 0)
                }
            }

            readout.voltage?.let { voltage ->
                if (voltage >= 0f) {
                    device.setBatteryVoltage(voltage, 0)
                }
            }

            device.sendDeviceUpdateIntent(context)
        }
        private val LOG = LoggerFactory.getLogger(VictronSmartShuntSupport::class.java)

        /// https://communityarchive.victronenergy.com/questions/93919/victron-bluetooth-ble-protocol-publication.html
        private val UUID_SERVICE_VICTRON = UUID.fromString("65970000-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_KEEP_ALIVE = UUID.fromString("6597ffff-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CONSUMED = UUID.fromString("6597eeff-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_POWER = UUID.fromString("6597ed8e-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_VOLTAGE = UUID.fromString("6597ed8d-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CURRENT = UUID.fromString("6597ed8c-4bda-4c1e-af4b-551c4cf74769")
        private val UUID_CHARACTERISTIC_CHARGE = UUID.fromString("65970fff-4bda-4c1e-af4b-551c4cf74769")

        private const val KEEP_ALIVE_FOREVER = 0xFFFF
        private const val POWER_NOT_AVAILABLE = 0x7FFF
        private const val VOLTAGE_NOT_AVAILABLE = 0x7FFF
        private const val CURRENT_NOT_AVAILABLE = 0x7FFFFFFF
        private const val CHARGE_NOT_AVAILABLE = 0xFFFF
    }
}
