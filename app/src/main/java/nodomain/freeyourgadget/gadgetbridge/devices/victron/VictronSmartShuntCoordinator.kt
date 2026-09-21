package nodomain.freeyourgadget.gadgetbridge.devices.victron

import androidx.annotation.StringRes
import nodomain.freeyourgadget.gadgetbridge.R
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCardAction
import nodomain.freeyourgadget.gadgetbridge.devices.DeviceCoordinator
import nodomain.freeyourgadget.gadgetbridge.devices.deviceCardAction
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice
import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceCandidate
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport
import nodomain.freeyourgadget.gadgetbridge.service.devices.victron.VictronSmartShuntSupport
import java.util.regex.Pattern

class VictronSmartShuntCoordinator : AbstractBLEDeviceCoordinator() {
    override fun getSupportedDeviceName(): Pattern {
        return Pattern.compile("^SmartShunt [A-Z0-9]+$")
    }

    override fun supports(candidate: GBDeviceCandidate): Boolean {
        if (super.supports(candidate)) {
            return true
        }
        // Rename-proof match: Victron advertisements always carry manufacturer
        // ID 0x02E1 with an unencrypted device-type byte at offset 4
        // (0x02 = battery monitor / SmartShunt). VictronConnect lets users
        // rename the device, which changes the advertised BLE name, so the
        // name pattern above is not sufficient on its own.
        val msd = candidate.manufacturerSpecificData ?: return false
        val data = msd.get(VICTRON_COMPANY_ID) ?: return false
        return data.size > VICTRON_DEVICE_TYPE_OFFSET &&
            data[VICTRON_DEVICE_TYPE_OFFSET] == VICTRON_TYPE_BATTERY_MONITOR
    }

    override fun getManufacturer(): String {
        return "Victron"
    }

    override fun getDeviceSupportClass(device: GBDevice): Class<out DeviceSupport> {
        return VictronSmartShuntSupport::class.java
    }

    override fun getDeviceNameResource(): Int {
        return R.string.devicetype_victron_smartshunt
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
        return DeviceCoordinator.DeviceKind.BATTERY_MONITOR
    }

    override fun getCustomActions(): List<DeviceCardAction> = listOf(
        displayAction(R.string.consumed_electrical_energy, EXTRA_CONSUMED),
        displayAction(R.string.power_w, EXTRA_POWER),
        displayAction(R.string.electrical_current, EXTRA_CURRENT),
    )

    private fun displayAction(@StringRes descriptionRes: Int, extraKey: String) = deviceCardAction {
        icon = { R.drawable.ic_bolt }
        isVisible = { device -> device.isConnected && !(device.getExtraInfo(extraKey) as? String).isNullOrBlank() }
        description = { _, context -> context.getString(descriptionRes) }
        label = { device, _ -> device.getExtraInfo(extraKey) as? String ?: "" }
        onClick = { _, _ -> }
    }

    companion object {
        const val EXTRA_CONSUMED = "consumed"
        const val EXTRA_POWER = "power"
        const val EXTRA_CURRENT = "current"

        // Victron BLE manufacturer data: company ID 0x02E1, unencrypted
        // device-type byte at offset 4 (see victron-ble advertisement format)
        private const val VICTRON_COMPANY_ID = 0x02E1
        private const val VICTRON_DEVICE_TYPE_OFFSET = 4
        private const val VICTRON_TYPE_BATTERY_MONITOR = 0x02.toByte()
    }
}
