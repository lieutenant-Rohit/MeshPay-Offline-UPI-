package com.offline.payment.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import java.util.UUID

// Notice the new 'context' parameter in the constructor!
class BleMeshManager(private val context: Context, bluetoothAdapter: BluetoothAdapter?) {

    private var bleAdvertiser: BluetoothLeAdvertiser? = bluetoothAdapter?.bluetoothLeAdvertiser
    private var gattServer: BluetoothGattServer? = null

    // This variable safely holds your massive encrypted payload in the background
    private var activePayload: String = ""

    companion object {
        // The ID of the Megaphone (To find the phone in the room)
        val OFFLINE_UPI_SERVICE_UUID: UUID = UUID.fromString("11223344-5566-7788-9900-AABBCCDDEEFF")
        // The ID of the Vault Door (To find the money once connected)
        val PAYMENT_PAYLOAD_CHAR_UUID: UUID = UUID.fromString("AABBCCDD-5566-7788-9900-112233445566")
    }

    // --- 1. THE GATT SERVER CALLBACK (Handles the iPhone connecting and asking for data) ---
    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                println("📱 iPhone Connected to Vault: ${device.address}")
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                println("👋 iPhone Disconnected.")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == PAYMENT_PAYLOAD_CHAR_UUID) {
                println("📥 iPhone requested the money! Sending full payload...")

                // Convert the massive payload into raw bytes
                val payloadBytes = activePayload.toByteArray(Charsets.UTF_8)

                // Android automatically handles slicing the data if it's too big for one packet
                val valueToSend = if (offset < payloadBytes.size) {
                    payloadBytes.sliceArray(offset until payloadBytes.size)
                } else {
                    byteArrayOf()
                }

                // Push the data across the bridge!
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, valueToSend)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    // --- 2. THE RADIO MEGAPHONE CALLBACK ---
    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            println("📡 BLE Hybrid Beacon Started: Waiting for connections!")
        }
        override fun onStartFailure(errorCode: Int) {
            println("❌ BLE Broadcast Failed. Error Code: $errorCode")
        }
    }

    // --- 3. THE MAIN LAUNCH FUNCTION ---
    @SuppressLint("MissingPermission")
    fun broadcastPaymentPacket(encryptedPayloadBase64: String) {
        if (bleAdvertiser == null) {
            println("Bluetooth is not enabled or not supported.")
            return
        }

        // Store the payload securely in RAM so the GATT server can access it later
        activePayload = encryptedPayloadBase64

        // Step A: Build the GATT Server (The Vault)
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

        val service = BluetoothGattService(OFFLINE_UPI_SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val characteristic = BluetoothGattCharacteristic(
            PAYMENT_PAYLOAD_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        service.addCharacteristic(characteristic)
        gattServer?.addService(service)

        // Step B: Turn on the Megaphone (Legacy Mode)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true) // Crucial: Open the doors!
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(OFFLINE_UPI_SERVICE_UUID))
            // Notice: We completely removed the payload from the broadcast!
            .build()

        bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopBroadcasting() {
        bleAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()
        println("🛑 BLE Hybrid Beacon Stopped.")
    }
}