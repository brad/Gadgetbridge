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

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.victron.VictronVebusCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.BleVictronTransport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.btle.VictronTransport
import nodomain.freeyourgadget.gadgetbridge.util.GB
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class VictronVebusSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private val batteryEvent = GBDeviceEventBatteryInfo()
    private val valueFormatter = WorkoutValueFormatter()
    private var transport: VictronTransport? = null
    private var notificationReceiver: BroadcastReceiver? = null

    init {
        addSupportedService(UUID_SERVICE_VICTRON_VEBUS)
    }

    override fun useAutoConnect(): Boolean {
        // Connection discipline: on-demand GATT connections only
        return false
    }

    override fun initializeDevice(builder: TransactionBuilder): TransactionBuilder {
        batteryEvent.level = -1
        batteryEvent.voltage = -1f
        device.resetExtraInfos()

        if (transport == null) {
            transport = BleVictronTransport(device, bluetoothAdapter, context)
        }

        builder.setDeviceState(GBDevice.State.INITIALIZING)

        builder.notify(UUID_CHARACTERISTIC_CTRL, true)
        builder.notify(UUID_CHARACTERISTIC_DATA, true)
        builder.notify(UUID_CHARACTERISTIC_SETUP, true)

        builder.read(UUID_CHARACTERISTIC_CTRL)
        builder.read(UUID_CHARACTERISTIC_DATA)
        builder.read(UUID_CHARACTERISTIC_SETUP)

        builder.setDeviceState(GBDevice.State.INITIALIZED)
        registerNotificationReceiver()
        updatePersistentNotification("ON")
        return builder
    }

    override fun onSendConfiguration(config: String) {
        when (config) {
            VictronVebusCoordinator.CMD_SET_MODE_ON -> setMode(BleVictronTransport.MODE_ON)
            VictronVebusCoordinator.CMD_SET_MODE_OFF -> setMode(BleVictronTransport.MODE_OFF)
            VictronVebusCoordinator.CMD_CYCLE_MODE -> cycleMode()
            else -> super.onSendConfiguration(config)
        }
    }

    fun executeCommand(commandFrame: ByteArray): ByteArray? {
        val activeTransport = transport ?: BleVictronTransport(device, bluetoothAdapter, context).also { transport = it }
        val response = activeTransport.executeCommand(commandFrame)
        return response
    }

    private fun setMode(mode: Byte) {
        val path = byteArrayOf(0x19.toByte(), 0x02.toByte(), 0x00.toByte())
        val command = BleVictronTransport.encodeSetValue(path, 0x41.toByte(), byteArrayOf(mode))
        executeCommand(command)

        val modeStr = when (mode.toInt() and 0xFF) {
            0x01 -> "charger-only"
            0x02 -> "inverter-only"
            0x03 -> "on"
            0x04 -> "off"
            else -> "unknown"
        }
        device.setExtraInfo(VictronVebusCoordinator.EXTRA_MODE, modeStr)
        device.sendDeviceUpdateIntent(context)
        updatePersistentNotification(modeStr.uppercase())
    }

    private fun cycleMode() {
        val current = device.getExtraInfo(VictronVebusCoordinator.EXTRA_MODE) as? String
        val nextMode = when (current) {
            "on" -> BleVictronTransport.MODE_OFF
            "off" -> BleVictronTransport.MODE_CHARGER_ONLY
            "charger-only" -> BleVictronTransport.MODE_ON
            else -> BleVictronTransport.MODE_ON
        }
        setMode(nextMode)
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
                return true
            }

            UUID_CHARACTERISTIC_DATA -> {
                val raw = buf.array()
                if (raw.size >= 5) {
                    val b1 = (raw[2].toInt() and 0xFF).toByte()
                    val b2 = (raw[3].toInt() and 0xFF).toByte()
                    val b3 = (raw[4].toInt() and 0xFF).toByte()
                    val pathHex = "%02X%02X%02X".format(b1, b2, b3)
                    when (pathHex) {
                        MODE_PATH -> {
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
                            updatePersistentNotification(mode.uppercase())
                            return true
                        }
                        CURRENT_LIMIT_PATH -> {
                            if (raw.size >= 8) {
                                val rawValue = ((raw[6].toInt() and 0xFF).toShort().toInt()) or (((raw[7].toInt() and 0xFF).toShort().toInt()) shl 8)
                                val currentA = if (rawValue == 10) 0.1 else rawValue * 0.1f
                                device.setExtraInfo(EXTRA_CURRENT_LIMIT, String.format("%.1f", currentA) + " A")
                                device.sendDeviceUpdateIntent(context)
                            }
                            return true
                        }
                        else -> {
                            return true
                        }
                    }
                }
                return true
            }

            UUID_CHARACTERISTIC_SETUP -> {
                return true
            }
        }
        return false
    }

    private fun registerNotificationReceiver() {
        if (notificationReceiver != null) return
        notificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_NOTIFICATION_TOGGLE) {
                    val targetMode = intent.getByteExtra(EXTRA_TARGET_MODE, BleVictronTransport.MODE_ON)
                    setMode(targetMode)
                }
            }
        }
        val filter = IntentFilter(ACTION_NOTIFICATION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(notificationReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(notificationReceiver, filter)
        }
    }

    private fun updatePersistentNotification(stateText: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = GB.NOTIFICATION_CHANNEL_ID_CONNECTION_STATUS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Victron VE.Bus Status",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val turnOffIntent = Intent(ACTION_NOTIFICATION_TOGGLE).apply {
            putExtra(EXTRA_TARGET_MODE, BleVictronTransport.MODE_OFF)
            setPackage(context.packageName)
        }
        val turnOffPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            turnOffIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val turnOnIntent = Intent(ACTION_NOTIFICATION_TOGGLE).apply {
            putExtra(EXTRA_TARGET_MODE, BleVictronTransport.MODE_ON)
            setPackage(context.packageName)
        }
        val turnOnPendingIntent = PendingIntent.getBroadcast(
            context,
            2,
            turnOnIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val turnOffAction = NotificationCompat.Action.Builder(
            R.drawable.ic_bolt,
            "Turn Off",
            turnOffPendingIntent
        ).build()

        val turnOnAction = NotificationCompat.Action.Builder(
            R.drawable.ic_bolt,
            "Turn On",
            turnOnPendingIntent
        ).build()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_device_car)
            .setContentTitle("Victron VE.Bus (" + device.name + ")")
            .setContentText("Status: " + stateText)
            .setOngoing(true)
            .addAction(turnOffAction)
            .addAction(turnOnAction)

        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    override fun dispose() {
        notificationReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (ignored: Exception) {
            }
            notificationReceiver = null
        }
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)
        transport?.dispose()
        transport = null
        super.dispose()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(VictronVebusSupport::class.java)

        private val UUID_SERVICE_VICTRON_VEBUS = UUID.fromString("306b0000-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_CHARACTERISTIC_CTRL = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_CHARACTERISTIC_DATA = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_CHARACTERISTIC_SETUP = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

        private const val MODE_PATH = "190200"
        private const val CURRENT_LIMIT_PATH = "190203"

        const val EXTRA_MODE = "mode"
        const val EXTRA_CURRENT_LIMIT = "current_limit"

        const val ACTION_NOTIFICATION_TOGGLE = "nodomain.freeyourgadget.gadgetbridge.victron.NOTIFICATION_TOGGLE"
        const val EXTRA_TARGET_MODE = "target_mode"
        private const val NOTIFICATION_ID = 9811
    }
}
