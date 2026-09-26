/*  Copyright (C) 2026 The Gadgetbridge Project

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.victron

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
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

class VictronVebusSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private val batteryEvent = GBDeviceEventBatteryInfo()
    private val valueFormatter = WorkoutValueFormatter()

    init {
        addSupportedService(UUID_SERVICE_VICTRON_VEBUS)
    }

    override fun useAutoConnect(): Boolean {
        return true
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        batteryEvent.level = -1
        batteryEvent.voltage = -1f
        device.resetExtraInfos()

        builder.setDeviceState(GBDevice.State.INITIALIZING)

        // Subscribe to control, data, and setup characteristics for notifications
        // The VE.Bus Smart Dongle uses the Smart service (306b family)
        builder.notify(UUID_CHARACTERISTIC_CTRL, true)
        builder.notify(UUID_CHARACTERISTIC_DATA, true)
        builder.notify(UUID_CHARACTERISTIC_SETUP, true)

        // One-shot reads so values show up immediately instead of waiting for
        // the first notification.
        builder.read(UUID_CHARACTERISTIC_CTRL)
        builder.read(UUID_CHARACTERISTIC_DATA)
        builder.read(UUID_CHARACTERISTIC_SETUP)

        // Write keep-alive to "forever" mode so notifications persist across reconnects
        // Frame format: 06 03 82 <path> <type> <value> written to control characteristic
        // Path 190200 = mode, type 0x42 = 2B LE, value 0x03 = on
        builder.write(UUID_CHARACTERISTIC_CTRL, 0x06.toByte(), 0x03.toByte(), 0x82.toByte(), 0x19.toByte(), 0x02.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte())

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
            UUID_CHARACTERISTIC_CTRL -> {
                // Control characteristic: handle setValue responses and mode changes
                // Frame format: 08 <dev> <path> <type> <value> (ack)
                // Or could be ping responses etc.
                return true
            }

            UUID_CHARACTERISTIC_DATA -> {
                // Data characteristic: receives getValues responses and notifications
                // Frame format from victron-ble: 05 <dev> 81 <path:3B> (getValues request)
                // Responses: 08 <dev> <path:3B> <type> <value>
                val raw = buf.array()
                if (raw.size >= 5) {
                    val dev = (raw[1].toInt() and 0xFF).toByte()
                    val b1 = (raw[2].toInt() and 0xFF).toByte()
                    val b2 = (raw[3].toInt() and 0xFF).toByte()
                    val b3 = (raw[4].toInt() and 0xFF).toByte()
                    val pathHex = "%02X%02X%02X".format(b1, b2, b3)
                    when (pathHex) {
                        MODE_PATH -> {
                            val type = (raw[5].toInt() and 0xFF).toByte()
                            val modeVal = raw[6].toInt() and 0xFF
                            val mode = when (modeVal) {
                                0x01 -> "charger-only"
                                0x02 -> "inverter-only"
                                0x03 -> "on"
                                0x04 -> "off"
                                else -> "unknown"
                            }
                            device.setExtraInfo(EXTRA_MODE, mode)
                            device.sendDeviceUpdateIntent(context)
                            return true
                        }
                        CURRENT_LIMIT_PATH -> {
                            // type 0x42 = 2B LE, 0.1 A units
                            if (raw.size >= 8) {
                                val rawValue = ((raw[6].toInt() and 0xFF).toShort().toInt()) or (((raw[7].toInt() and 0xFF).toShort().toInt()) shl 8)
                                val currentA = if (rawValue == 10) 0.1 else rawValue * 0.1f
                                device.setExtraInfo(EXTRA_CURRENT_LIMIT, String.format("%.1f", currentA) + " A")
                                device.sendDeviceUpdateIntent(context)
                            }
                            return true
                        }
                        // Additional telemetry paths can be added here
                        else -> {
                            // Unknown path, ignore
                            return true
                        }
                    }
                }
                return true
            }

            UUID_CHARACTERISTIC_SETUP -> {
                // Setup characteristic: session initialization
                // In a full implementation, this would handle session setup frames
                return true
            }
        }

        return false
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(VictronVebusSupport::class.java)

        /// Victron VE.Bus Smart Dongle service UUID (Smart service 306b family)
        private val UUID_SERVICE_VICTRON_VEBUS = UUID.fromString("306b0000-b081-4037-83dc-e59fcc3cdfd0")

        /// Control characteristic UUID
        private val UUID_CHARACTERISTIC_CTRL = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")

        /// Data characteristic UUID
        private val UUID_CHARACTERISTIC_DATA = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")

        /// Setup characteristic UUID
        private val UUID_CHARACTERISTIC_SETUP = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

        // VE.Bus paths (from victron-ble vebus_dongle.py)
        private val MODE_PATH = "190200"
        private val CURRENT_LIMIT_PATH = "190203"

        // Mode values
        private const val MODE_ON = 0x03
        private const val MODE_OFF = 0x04
        private const val MODE_CHARGER_ONLY = 0x01
        private const val MODE_INVERTER_ONLY = 0x02

        // Extra info keys (matching VictronVebusCoordinator constants)
        const val EXTRA_MODE = "mode"
        const val EXTRA_CURRENT_LIMIT = "current_limit"
    }
}
