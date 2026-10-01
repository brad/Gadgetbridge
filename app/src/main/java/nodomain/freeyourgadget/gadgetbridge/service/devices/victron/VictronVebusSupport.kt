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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.activities.workouts.WorkoutValueFormatter
import nodomain.freeyourgadget.gadgetbridge.deviceevents.GBDeviceEventBatteryInfo
import nodomain.freeyourgadget.gadgetbridge.devices.victron.VictronVebusCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLESingleDeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.btle.BLEScanService
import nodomain.freeyourgadget.gadgetbridge.service.btle.BleVictronTransport
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder
import nodomain.freeyourgadget.gadgetbridge.service.btle.VictronTransport
import nodomain.freeyourgadget.gadgetbridge.util.GB
import nodomain.freeyourgadget.gadgetbridge.util.Prefs
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class VictronVebusSupport : AbstractBTLESingleDeviceSupport(LOG) {
    private val batteryEvent = GBDeviceEventBatteryInfo()
    private val valueFormatter = WorkoutValueFormatter()
    private var transport: VictronTransport? = null
    private var notificationReceiver: BroadcastReceiver? = null

    // Accumulated notifications for 19ec65 key reassembly (may span fragments)
    private val keyNotifs = mutableListOf<ByteArray>()
    // Latch for completion-aware key wait (counts down when key is found)
    // Reset before each retrieval attempt (CountDownLatch is single-use)
    private var keyLatch = CountDownLatch(1)

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
        addSupportedService(UUID_SERVICE_VICTRON_VEBUS)
        addSupportedService(UUID_SERVICE_SMART)
    }

    override fun useAutoConnect(): Boolean {
        // Connection discipline: on-demand GATT connections only
        return false
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

        // If we don't have the advertisement key yet, retrieve it via 19ec65.
        // This enables passive Instant Readout telemetry from advertisements.
        // Stability: no inter-write pacing sleeps (Python proved they're unnecessary).
        // Completion-aware: wait up to 20s for key, return immediately when it arrives.
        if (getAdvertisementKey() == null) {
            LOG.info("=== 19ec65 key retrieval starting ===")
            // Reset latch for this attempt
            keyLatch = CountDownLatch(1)
            LOG.info("No advertisement key stored, attempting GATT retrieval via 19ec65")
            device.setBusyTask(R.string.getting_advertisement_key, context)
            LOG.debug("Enabling notifications on SMART_CTRL, SMART_DATA, SMART_SETUP")
            builder.notify(UUID_SMART_CTRL, true)
            builder.notify(UUID_SMART_DATA, true)
            builder.notify(UUID_SMART_SETUP, true)
            // Smart-service login: fa80ff + f980 to CTRL (no pacing delays)
            LOG.info("Writing login fa80ff to CTRL")
            builder.write(UUID_SMART_CTRL, 0xFA.toByte(), 0x80.toByte(), 0xFF.toByte())
            LOG.info("Writing ping f980 to CTRL")
            builder.write(UUID_SMART_CTRL, 0xF9.toByte(), 0x80.toByte())
            // Handshake: 01 and 0300 to DATA (no pacing delays)
            LOG.info("Writing 01 to DATA")
            builder.write(UUID_SMART_DATA, 0x01.toByte())
            LOG.info("Writing 0300 to DATA")
            builder.write(UUID_SMART_DATA, 0x03.toByte(), 0x00.toByte())
            // Request 19ec65: SETUP_LAST + READ_EC65 (no pacing delays)
            LOG.info("Writing SETUP_LAST (get 19ec66->19ec65) to DATA")
            builder.write(UUID_SMART_DATA, *SETUP_LAST)
            LOG.info("Writing READ_EC65 (explicit get 19ec65) to DATA")
            builder.write(UUID_SMART_DATA, *READ_EC65)
            // Completion-aware wait: up to 20s, returns immediately when key arrives
            // (onCharacteristicChanged counts down keyLatch when key is reassembled)
            LOG.info("All writes queued, waiting up to 20s for 19ec65 key notifications...")
            builder.run(Runnable {
                val gotKey = try {
                    keyLatch.await(20, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    LOG.warn("Key wait interrupted", e)
                    false
                }
                if (gotKey) {
                    LOG.info("19ec65 key received within timeout, continuing initialization")
                } else {
                    LOG.warn("Timeout after 20s waiting for 19ec65 key - {} fragments received, {} bytes total",
                        keyNotifs.size, keyNotifs.sumOf { it.size })
                }
            })
            keyNotifs.clear()
            device.unsetBusyTask()
            LOG.info("=== 19ec65 key retrieval finished ===")
        } else {
            LOG.debug("Advertisement key already stored, skipping 19ec65 retrieval")
        }

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

    /**
     * Process a Victron Instant Readout advertisement.
     * Decrypts using the stored advertisement key and updates device telemetry.
     */
    private fun onVictronAdvertisement(manufacturerData: ByteArray) {
        val keyHex = getAdvertisementKey()
        if (keyHex == null) {
            LOG.debug("No advertisement key, skipping passive decrypt")
            return
        }

        val decrypted = VictronInstantReadout.decrypt(manufacturerData, keyHex) ?: return
        val readout = VictronInstantReadout.parseVebus(decrypted) ?: return

        // Update battery info
        readout.batteryVoltage?.let {
            batteryEvent.voltage = it.toFloat()
        }
        readout.soc?.let {
            batteryEvent.level = it.toInt()
        }

        // Update extra infos for UI
        readout.deviceState?.let { state ->
            val modeStr = when (state) {
                0x01 -> "charger-only"
                0x02 -> "inverter-only"
                0x03 -> "on"
                0x04 -> "off"
                0xF0 -> "off"
                0xF1 -> "low power"
                0xF2 -> "fault"
                0xF3 -> "bulk"
                0xF4 -> "absorption"
                0xF5 -> "float"
                0xF6 -> "storage"
                0xF7 -> "equalize"
                0xF8 -> "passthru"
                0xF9 -> "inverting"
                0xFA -> "power assist"
                0xFB -> "power supply"
                else -> "unknown ($state)"
            }
            device.setExtraInfo(VictronVebusCoordinator.EXTRA_MODE, modeStr)
        }

        readout.batteryVoltage?.let {
            device.setExtraInfo("battery_voltage", String.format("%.2f V", it))
        }
        readout.batteryCurrent?.let {
            device.setExtraInfo("battery_current", String.format("%.1f A", it))
        }
        readout.acOutPower?.let {
            device.setExtraInfo("ac_out_power", String.format("%.0f W", it))
        }
        readout.acInPower?.let {
            device.setExtraInfo("ac_in_power", String.format("%.0f W", it))
        }
        readout.soc?.let {
            device.setExtraInfo("soc", String.format("%.0f%%", it))
        }

        device.sendDeviceUpdateIntent(context)
        LOG.debug("Updated VE.Bus from advertisement: V={} SOC={}%", readout.batteryVoltage, readout.soc)
    }

    /**
     * Get the stored advertisement key (hex string), or null if not set.
     */
    private fun getAdvertisementKey(): String? {
        val prefs = Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.address))
        val key = prefs.getPreferences().getString(PREF_ADVERTISEMENT_KEY, null)
        return if (key.isNullOrEmpty()) null else key
    }

    /**
     * Store the advertisement key (bytes) in device-specific prefs as hex.
     */
    private fun setAdvertisementKey(keyBytes: ByteArray) {
        val hex = keyBytes.joinToString("") { "%02x".format(it) }
        val prefs = Prefs(GBApplication.getDeviceSpecificSharedPrefs(device.address))
        prefs.getPreferences().edit().putString(PREF_ADVERTISEMENT_KEY, hex).apply()
        LOG.info("Stored advertisement key for {}", device.address)
    }

    override fun onCharacteristicChanged(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ): Boolean {
        // Check for 19ec65 key response on Smart service
        if (characteristic.uuid == UUID_SMART_DATA || characteristic.uuid == UUID_SMART_SETUP) {
            val hexPreview = value.take(20).joinToString("") { "%02x".format(it) }
            LOG.info("19ec65 fragment #{} on {}: {} bytes: {}{}",
                keyNotifs.size + 1, characteristic.uuid, value.size, hexPreview,
                if (value.size > 20) "..." else "")
            keyNotifs.add(value)
            val totalBytes = keyNotifs.sumOf { it.size }
            LOG.debug("Reassembly: {} fragments, {} total bytes", keyNotifs.size, totalBytes)
            val key = reassembleAdkey(keyNotifs)
            if (key != null) {
                LOG.info("=== 19ec65 key reassembled successfully: {} fragments, {} bytes ===",
                    keyNotifs.size, totalBytes)
                LOG.info("Retrieved 16-byte advertisement key via 19ec65 (key material not logged)")
                setAdvertisementKey(key)
                keyNotifs.clear()
                device.unsetBusyTask()
                // Signal the waiting transaction that the key has arrived
                keyLatch.countDown()
                LOG.debug("Key latch counted down, waiting transaction will continue")
            } else {
                LOG.debug("Key not yet complete after {} fragments", keyNotifs.size)
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
        if (characteristic.uuid == UUID_SMART_DATA || characteristic.uuid == UUID_SMART_SETUP) {
            keyNotifs.add(value)
            val key = reassembleAdkey(keyNotifs)
            if (key != null) {
                LOG.info("Retrieved 16-byte advertisement key via 19ec65")
                setAdvertisementKey(key)
                keyNotifs.clear()
                device.unsetBusyTask()
            }
            return true
        }
        if (handleCharacteristic(characteristic.uuid, value)) {
            return true
        }
        return super.onCharacteristicRead(gatt, characteristic, value, status)
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
        try {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(advertisementReceiver)
            LOG.debug("Unregistered advertisement receiver")
        } catch (e: Exception) {
            LOG.warn("Failed to unregister advertisement receiver", e)
        }
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

        // Smart service for 19ec65 advertisement key retrieval
        private val UUID_SERVICE_SMART = UUID.fromString("306b0001-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_CTRL = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_DATA = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0")
        private val UUID_SMART_SETUP = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0")

        // 19ec65 read commands (from victron-ble adkey.py)
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

        private const val PREF_ADVERTISEMENT_KEY = "victron_advertisement_key"

        private const val MODE_PATH = "190200"
        private const val CURRENT_LIMIT_PATH = "190203"

        const val EXTRA_MODE = "mode"
        const val EXTRA_CURRENT_LIMIT = "current_limit"

        const val ACTION_NOTIFICATION_TOGGLE = "nodomain.freeyourgadget.gadgetbridge.victron.NOTIFICATION_TOGGLE"
        const val EXTRA_TARGET_MODE = "target_mode"
        private const val NOTIFICATION_ID = 9811
    }
}
