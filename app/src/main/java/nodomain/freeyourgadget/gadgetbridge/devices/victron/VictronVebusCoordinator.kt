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
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.victron.VictronVebusSupport
import java.util.regex.Pattern

class VictronVebusCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern {
        return Pattern.compile("^(MultiPlus|VE.Bus Smart Dongle) [A-F0-9]+$")
    }

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        if (super.supports(candidate)) {
            return true
        }
        // Rename-proof match: Victron advertisements always carry manufacturer
        // ID 0x02E1 with an unencrypted device-type byte at offset 4
        // VictronConnect lets users rename the device, which changes the advertised
        // BLE name, so the name pattern above is not sufficient on its own.
        val msd = candidate.manufacturerSpecificData ?: return false
        val data = msd.get(VICTRON_COMPANY_ID) ?: return false
        return data.size > VICTRON_DEVICE_TYPE_OFFSET &&
            data[VICTRON_DEVICE_TYPE_OFFSET] == VICTRON_TYPE_INVERTER
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

    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        displayAction(R.string.mode, EXTRA_MODE),
        displayAction(R.string.input_current_limit, EXTRA_CURRENT_LIMIT),
    )

    private fun displayAction(@StringRes descriptionRes: Int, extraKey: String) = deviceCardAction {
        icon = { R.drawable.ic_bolt }
        isVisible = { device -> device.isConnected && !(device.getExtraInfo(extraKey) as? String).isNullOrBlank() }
        description = { _, context -> context.getString(descriptionRes) }
        label = { device, _ -> device.getExtraInfo(extraKey) as? String ?: "" }
        onClick = { _, _ -> }
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_CURRENT_LIMIT = "current_limit"

        // Victron BLE manufacturer data: company ID 0x02E1, unencrypted
        // device-type byte at offset 4 (see victron-ble advertisement format)
        private const val VICTRON_COMPANY_ID = 0x02E1
        private const val VICTRON_DEVICE_TYPE_OFFSET = 4
        private const val VICTRON_TYPE_INVERTER = 0x03.toByte()
    }
}
