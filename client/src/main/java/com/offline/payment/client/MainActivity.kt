package com.offline.payment.client

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var bleMeshManager: BleMeshManager
    private lateinit var keyStoreService: LocalKeyStoreService

    // Modern Android requires us to ask the user for permission to use the Bluetooth radio
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "Radio Access Granted! \uD83D\uDCFB", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Need Bluetooth permissions to broadcast!", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Initialize our two core backend pillars
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

        // FIX: Hand the BleMeshManager the 'this' Context so it can build the background Vault!
        bleMeshManager = BleMeshManager(this, bluetoothManager.adapter)

        keyStoreService = LocalKeyStoreService()

        // 2. Ask for radio permissions on startup
        requestBluetoothPermissions()

        // 3. Paint the UI on the screen
        setContent {
            MaterialTheme {
                OfflineUPIClientUI(bleMeshManager, keyStoreService)
            }
        }
    }

    private fun requestBluetoothPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }
}

@Composable
fun OfflineUPIClientUI(bleMeshManager: BleMeshManager, keyStoreService: LocalKeyStoreService) {
    var amount by remember { mutableStateOf("500") }
    var status by remember { mutableStateOf("Ready to send offline.") }
    var isBroadcasting by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("\uD83D\uDCF6 Offline UPI", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Send money without the internet.", color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(48.dp))

            // The Amount Input Field
            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount (₹)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))

            // The Action Button
            Button(
                onClick = {
                    if (isBroadcasting) {
                        bleMeshManager.stopBroadcasting()
                        isBroadcasting = false
                        status = "Broadcast stopped."
                    } else {
                        // A. Ensure Hardware Keys exist in the Vault
                        keyStoreService.generateSecureKeyPair()

                        // B. For this frontend simulation, we mock the ciphertext payload
                        val simulatedCiphertext = "SIMULATED_ENCRYPTED_PAYLOAD_FOR_BOB_$amount"

                        // C. Fire up the physical radio!
                        bleMeshManager.broadcastPaymentPacket(simulatedCiphertext)

                        isBroadcasting = true
                        status = "\uD83D\uDCE1 Broadcasting ₹$amount to Mesh Network..."
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = if (isBroadcasting) "Stop Broadcasting" else "Pay Offline",
                    fontSize = 18.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = status,
                color = if (isBroadcasting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}