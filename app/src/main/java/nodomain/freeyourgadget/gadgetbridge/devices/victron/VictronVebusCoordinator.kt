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
package nodomain.freeyourgadget.gadgetbridge.devices.victron

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.GBApplication
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.model.BatteryConfig
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.victron.VictronVebusSupport
import java.util.UUID
import java.util.regex.Pattern

class VictronVebusCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern {
        return Pattern.compile("^(MultiPlus|VE.Bus Smart Dongle) [A-F0-9]+$")
    }

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        if (super.supports(candidate)) {
            return true
        }
        if (candidate.supportsService(UUID_SERVICE_VICTRON_VEBUS)) {
            return true
        }
        // Match by name (case-insensitive) for VE.Bus dongles
        val name = candidate.name?.lowercase() ?: ""
        if (name.contains("ve.bus") || name.contains("multiplus")) {
            return true
        }
        // Rename-proof match: Victron advertisements always carry manufacturer
        // ID 0x02E1. Match any Victron device here; the device type byte
        // check is too strict for the Smart Dongle which may not advertise
        // as type 0x03 (inverter).
        val msd = candidate.manufacturerSpecificData
        val data = msd.get(VICTRON_COMPANY_ID) ?: return false
        // Accept any Victron device with manufacturer data; user selects
        // the correct device type during pairing if needed
        return data.size > VICTRON_DEVICE_TYPE_OFFSET
    }

    override fun getManufacturer(): String {
        return "Victron"
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> {
        return VictronVebusSupport::class.java
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_victron_vebus
    }

    override fun suggestUnbindBeforePair(): Boolean {
        return false
    }

    override fun getBondingStyle(): Int {
        // Must be paired for the service to show up
        return BONDING_STYLE_BOND
    }

    override fun getDefaultIconResource(): Int {
        return R.drawable.ic_device_car
    }

    override fun getDeviceKind(device: GBDevice): DeviceCoordinator.DeviceKind {
        return DeviceCoordinator.DeviceKind.SMART_DISPLAY
    }

    override fun getBatteryCount(device: GBDevice): Int {
        return 0
    }

    override fun getBatteryConfig(device: GBDevice): Array<BatteryConfig> {
        return arrayOf()
    }

    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        toggleOnOffAction(),
        displayAction(R.string.mode, EXTRA_MODE) { device, _ ->
            GBApplication.deviceService(device).onSendConfiguration(CMD_CYCLE_MODE)
        },
        displayAction(R.string.input_current_limit, EXTRA_CURRENT_LIMIT),
    )

    private fun toggleOnOffAction() = deviceCardAction {
        icon = { R.drawable.ic_bolt }
        isVisible = { device -> device.isConnected }
        description = { device, context ->
            val mode = device.getExtraInfo(EXTRA_MODE) as? String
            if (mode == "off") context.getString(R.string.controlcenter_power_off) else context.getString(R.string.mode)
        }
        label = { device, _ ->
            device.getExtraInfo(EXTRA_MODE) as? String ?: ""
        }
        onClick = { device, _ ->
            val mode = device.getExtraInfo(EXTRA_MODE) as? String
            if (mode == "off") {
                GBApplication.deviceService(device).onSendConfiguration(CMD_SET_MODE_ON)
            } else {
                GBApplication.deviceService(device).onSendConfiguration(CMD_SET_MODE_OFF)
            }
        }
    }

    private fun displayAction(
        @StringRes descriptionRes: Int,
        extraKey: String,
        clickAction: ((GBDevice, android.content.Context) -> Unit)? = null
    ) = deviceCardAction {
        icon = { R.drawable.ic_bolt }
        isVisible = { device -> device.isConnected && !(device.getExtraInfo(extraKey) as? String).isNullOrBlank() }
        description = { _, context -> context.getString(descriptionRes) }
        label = { device, _ -> device.getExtraInfo(extraKey) as? String ?: "" }
        onClick = { device, context -> clickAction?.invoke(device, context) }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CURRENT_LIMIT = "current_limit"

        const val CMD_SET_MODE_ON = "set_mode_on"
        const val CMD_SET_MODE_OFF = "set_mode_off"
        const val CMD_CYCLE_MODE = "cycle_mode"

        private val UUID_SERVICE_VICTRON_VEBUS = UUID.fromString("306b0000-b081-4037-83dc-e59fcc3cdfd0")

        // Victron BLE manufacturer data: company ID 0x02E1, unencrypted
        // device-type byte at offset 4 (see victron-ble advertisement format)
        private const val VICTRON_COMPANY_ID = 0x02E1
        private const val VICTRON_DEVICE_TYPE_OFFSET = 4
        private const val VICTRON_TYPE_INVERTER = 0x03.toByte()
    }
}
