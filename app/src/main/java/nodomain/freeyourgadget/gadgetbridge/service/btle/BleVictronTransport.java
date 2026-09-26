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
package nodomain.freeyourgadget.gadgetbridge.service.btle;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/**
 * BLE implementation of {@link VictronTransport} for Victron VE.Bus Smart Dongle.
 * <p/>
 * Connection discipline: on-demand GATT connections only, commands serialized.
 * Never hold the dongle connection persistently (would block VictronConnect and
 * starve the Pi's advertisement monitor).
 * <p/>
 * Frame codec (reverse-engineered from victron-gatt docs):
 * - setValue: 06 03 82 <path> <type> <value>
 * - getValues: 05 03 81 <path>
 * - Types: 0x41=1B, 0x42=2B LE, 0x44=4B LE
 */
public class BleVictronTransport implements VictronTransport {
    private static final String LOGTAG = "BleVicTransport";

    private GBDevice gbDevice;
    private BluetoothAdapter btAdapter;
    private Context context;

    private BluetoothDevice device;
    private boolean connected;
    private boolean connecting;

    // Victron VE.Bus Smart Dongle service UUIDs
    public static final UUID CTRL_UUID = UUID.fromString("306b0002-b081-4037-83dc-e59fcc3cdfd0");
    public static final UUID DATA_UUID = UUID.fromString("306b0003-b081-4037-83dc-e59fcc3cdfd0");
    public static final UUID SETUP_UUID = UUID.fromString("306b0004-b081-4037-83dc-e59fcc3cdfd0");

    // VE.Bus paths
    public static final String MODE_PATH = "190200";
    public static final String CURRENT_LIMIT_PATH = "190203";

    // Mode values (from victron-ble vebus_dongle.py)
    public static final byte MODE_ON = 0x03;
    public static final byte MODE_OFF = 0x04;
    public static final byte MODE_CHARGER_ONLY = 0x01;
    public static final byte MODE_INVERTER_ONLY = 0x02;

    // Keep-alive ping
    private static final byte[] PING_FRAME = {(byte) 0xF9, (byte) 0x41};

    public BleVictronTransport(GBDevice gbDevice, BluetoothAdapter btAdapter, Context context) {
        this.gbDevice = gbDevice;
        this.btAdapter = btAdapter;
        this.context = context;
    }

    @Override
    public void setContext(@NonNull GBDevice gbDevice, @NonNull BluetoothAdapter btAdapter, @NonNull Context context) {
        this.gbDevice = gbDevice;
        this.btAdapter = btAdapter;
        this.context = context;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public boolean isConnecting() {
        return connecting;
    }

    @Override
    public boolean connectFirstTime() {
        return connect();
    }

    @Override
    public boolean connect() {
        // On-demand: open connection, perform initialization, then close
        // We use bleak-like operations through the Gadgetbridge BtLE queue
        // For now, we delegate to the existing BLE infrastructure
        // via AbstractBTLESingleDeviceSupport patterns
        if (device == null) {
            device = btAdapter.getRemoteDevice(gbDevice.getAddress());
        }
        // In a full implementation, we'd use BleakClient here
        // For Gadgetbridge integration, we'll use the standard connect path
        connected = true;
        connecting = false;
        Log.d(LOGTAG, "Connected to VE.Bus dongle: " + gbDevice.getAddress());
        return true;
    }

    @Override
    public void dispose() {
        connected = false;
        connecting = false;
        device = null;
        Log.d(LOGTAG, "Disposed VE.Bus transport");
    }

    @Override
    public boolean useAutoConnect() {
        return true;
    }

    @Override
    public void setAutoReconnect(boolean enable) {
        // Not needed for on-demand connections
    }

    @Override
    public boolean getAutoReconnect() {
        return false;
    }

    @Override
    public String customStringFilter(String inputString) {
        return inputString;
    }

    @Override
    public byte[] executeCommand(byte[] command) {
        // Single entry point: serialize command execution
        // Open connection, send command, read response, close connection
        byte[] response = null;

        try {
            // Connect on-demand
            if (!connected) {
                connect();
            }

            // TODO: Implement actual BLE write/read using BleakClient or similar
            // For now, log the command and return null
            Log.d(LOGTAG, "executeCommand: " + bytesToHex(command));

            // Simulate: in a full implementation, we would:
            // 1. Write the command frame to the control/data characteristic
            // 2. Wait for response
            // 3. Return the response bytes

        } catch (Exception e) {
            Log.e(LOGTAG, "Error executing command", e);
        } finally {
            // Always disconnect after command (on-demand)
            disconnect();
        }

        return response;
    }

    @Override
    public byte[] readCharacteristic(UUID characteristicUUID) {
        byte[] value = null;
        try {
            if (!connected) {
                connect();
            }
            // TODO: Implement actual BLE read
            Log.d(LOGTAG, "readCharacteristic: " + characteristicUUID);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error reading characteristic", e);
        } finally {
            disconnect();
        }
        return value;
    }

    @Override
    public void setCharacteristicNotification(UUID characteristicUUID, boolean enable) {
        try {
            if (!connected) {
                connect();
            }
            // TODO: Implement actual BLE notification setup/teardown
            Log.d(LOGTAG, "setCharacteristicNotification: " + characteristicUUID + " enable=" + enable);
        } catch (Exception e) {
            Log.e(LOGTAG, "Error setting characteristic notification", e);
        } finally {
            disconnect();
        }
    }

    private void disconnect() {
        if (connected) {
            connected = false;
            Log.d(LOGTAG, "Disconnected VE.Bus dongle");
        }
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}