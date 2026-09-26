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
import android.content.Context;

import java.util.UUID;

/**
 * Transport-agnostic interface for Victron VE.Bus device communication.
 * Implementations handle the low-level BLE (or eventually HTTP) transport.
 * <p/>
 * All commands are serialized through a single {@link #executeCommand} entry point,
 * ensuring on-demand GATT connections only and never holding the dongle connection
 * persistently (which would block other applications).
 */
public interface VictronTransport {
    /** Called when the transport is first set up, with the device and adapter. */
    void setContext(nodomain.freeyourgadget.gadgetbridge.impl.GBDevice gbDevice,
                    android.bluetooth.BluetoothAdapter btAdapter,
                    android.content.Context context);

    /** Returns whether a transport-level connection is established with the device. */
    boolean isConnected();

    /** Returns whether a transport-level connection is being established. */
    boolean isConnecting();

    /** Attempts an initial connection to the device. */
    boolean connectFirstTime();

    /** Attempts to establish a connection to the device. */
    boolean connect();

    /** Disposes of this instance, closing all connections and freeing all resources. */
    void dispose();

    /** Returns true if a connection attempt shall be made automatically whenever needed. */
    boolean useAutoConnect();

    /** Configures this instance to automatically attempt to reconnect after a connection loss. */
    void setAutoReconnect(boolean enable);

    /** Returns whether this instance is configured to automatically reconnect. */
    boolean getAutoReconnect();

    /** Converts String in a device specific way. */
    String customStringFilter(String inputString);

    /**
     * Executes a command against the VE.Dongle.
     * This is the single entry point — both the watch path (Phase 5) and the
     * gateway intent (Phase 2) funnel through it.
     * Commands are serialized; the connection is opened, the command sent, then closed.
     *
     * @param command the command frame to execute (e.g. setValue 06 03 82 <path> <type> <value>)
     * @return the response frame, or null if no response expected/available
     */
    byte[] executeCommand(byte[] command);

    /**
     * Read a characteristic value from the device.
     *
     * @param characteristicUUID the UUID of the characteristic to read
     * @return the raw value bytes, or null on failure
     */
    byte[] readCharacteristic(UUID characteristicUUID);

    /**
     * Subscribe or unsubscribe to notifications on a characteristic.
     *
     * @param characteristicUUID the UUID of the characteristic
     * @param enable           true to subscribe, false to unsubscribe
     */
    void setCharacteristicNotification(UUID characteristicUUID, boolean enable);
}