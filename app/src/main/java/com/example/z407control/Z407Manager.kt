package com.example.z407control

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

@SuppressLint("MissingPermission")
class Z407Manager(
    private val context: Context,
    private val connectionCallback: (String) -> Unit,
    private val commandFeedbackCallback: (String) -> Unit
) {

    companion object {
        private val SERVICE_UUID = UUID.fromString("0000fdc2-0000-1000-8000-00805f9b34fb")
        private val COMMAND_UUID = UUID.fromString("c2e758b9-0e78-41e0-b0cb-98a593193fc5")
        private val RESPONSE_UUID = UUID.fromString("b84ac9c6-29c5-46d4-bba1-9d534784330f")
        private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val TAG = "Z407Manager"
        private const val FAST_CONNECTION_TIMEOUT = 25000L
        private const val EXTENDED_CONNECTION_TIMEOUT = 45000L
        private const val BACKGROUND_CONNECTION_TIMEOUT = 90000L
        private const val DISCOVERY_DELAY = 3000L // Longer delay for stability
        private const val EXTENDED_DISCOVERY_DELAY = 8000L // Very long delay for problematic devices
        private const val MAX_DISCOVERY_RETRIES = 7 // More retries
        private const val GATT_BOUNCE_CYCLES = 5 // More bounce cycles
        private const val POST_UNPAIR_DELAY = 5000L // Wait after unpair before rebond
        private const val POST_REBOND_DELAY = 8000L // Wait after rebond before connect

        // GATT Status helper
        fun gattStatusToString(status: Int): String {
            return when (status) {
                BluetoothGatt.GATT_SUCCESS -> "GATT_SUCCESS"
                BluetoothGatt.GATT_FAILURE -> "GATT_FAILURE"
                8 -> "GATT_CONN_TIMEOUT"
                133 -> "GATT_ERROR (133)"
                19 -> "GATT_CONN_TERMINATE_PEER_USER"
                22 -> "GATT_CONN_TERMINATE_LOCAL_HOST"
                62 -> "GATT_CONN_FAIL_ESTABLISH"
                257 -> "GATT_INTERNAL_ERROR"
                else -> "UNKNOWN_STATUS ($status)"
            }
        }
    }

    private enum class ConnectionStep {
        IDLE, PRE_SEQUENCE_CLEANUP,
        // PHASE 1: Profile cleanup
        DISCONNECT_ALL_PROFILES,    // Disconnect ALL Bluetooth profiles (A2DP, HFP, AVRCP, etc.)
        EXTENDED_WAIT_AFTER_DISCONNECT, // Wait longer after profile disconnect
        
        // PHASE 2: Standard attempts  
        DIRECT_CONNECT_AUTO,
        GATT_BOUNCE_AGGRESSIVE,     // More aggressive bounce cycles
        DIRECT_CONNECT_LE,
        
        // PHASE 3: Extended discovery
        EXTENDED_WAIT_DISCOVERY,    // Connect and wait MUCH longer before discovery
        AGGRESSIVE_DISCOVERY,
        
        // PHASE 4: Scan-based approaches
        SCAN_AND_CONNECT,
        REFRESH_CACHE,
        BACKGROUND_CONNECT,
        
        // PHASE 5: Bonding operations  
        FORCE_REBOND,               // Remove bond and create new one
        WAIT_FOR_REBOND,            // Wait for bond to complete
        POST_REBOND_CONNECT,        // Connect after rebond
        
        // PHASE 6: Last resort
        BLIND_WRITE_ATTEMPT,
        NUCLEAR_BLUETOOTH_RESET,    // Turn Bluetooth OFF then ON
        FINAL_DIRECT_CONNECT,
        
        CONNECTED, FAILED
    }
    
    // Flags to track state across strategies
    @Volatile private var shouldTryBlindWrite = false
    @Volatile private var gattBounceCount = 0
    @Volatile private var aggressiveDiscoveryAttempt = 0
    @Volatile private var rebondAttempted = false
    @Volatile private var nuclearResetAttempted = false
    @Volatile private var savedTargetAddress: String? = null // Save MAC before unpair

    private val handler = Handler(Looper.getMainLooper())
    private val logEntries = mutableListOf<String>()
    
    // Use a robust way to get adapter, handling nulls gracefully in usage
    private val bluetoothAdapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    @Volatile private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var isScanning = false
    @Volatile private var currentStep = ConnectionStep.IDLE
    @Volatile private var stepBeforeConnect = ConnectionStep.IDLE
    private val watchdogHandler = Handler(Looper.getMainLooper())

    private var targetDevice: BluetoothDevice? = null
    @Volatile private var discoveryRetryCount = 0
    @Volatile private var profileDisconnectCount = 0
    
    // Broadcast receiver for bond state changes
    private var bondReceiver: BroadcastReceiver? = null

    private fun log(priority: Int, message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp: [$currentStep] $message"
        logEntries.add(logMessage)
        
        // Keep log size manageable
        if (logEntries.size > 500) {
            logEntries.removeAt(0)
        }

        when (priority) {
            Log.INFO -> Log.i(TAG, logMessage)
            Log.WARN -> Log.w(TAG, logMessage)
            Log.ERROR -> Log.e(TAG, logMessage)
            Log.DEBUG -> Log.d(TAG, logMessage)
        }
    }

    fun getLogs(): String = logEntries.joinToString("\n")
    private fun runOnUiThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            handler.post(action)
        }
    }

    /**
     * Refresh the GATT cache using reflection. This is essential to fix stale cache issues
     * where Android returns empty/stale services after bonding changes.
     */
    @SuppressLint("PrivateApi")
    private fun refreshGattCache(gatt: BluetoothGatt): Boolean {
        log(Log.DEBUG, "Attempting to invoke hidden method 'refresh' on BluetoothGatt.")
        return try {
            val refreshMethod = gatt.javaClass.getMethod("refresh")
            val result = refreshMethod.invoke(gatt) as? Boolean ?: false
            log(Log.INFO, "GATT cache refresh called. Result: $result")
            result
        } catch (e: Exception) {
            log(Log.WARN, "GATT cache refresh failed to invoke: ${e.message}")
            false
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            watchdogHandler.removeCallbacksAndMessages(null)
            val stepAtCallback = currentStep
            val statusStr = gattStatusToString(status)
            
            val newStateStr = when(newState) {
                BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                else -> "UNKNOWN ($newState)"
            }

            log(Log.INFO, "onConnectionStateChange: Status=$statusStr, NewState=$newStateStr")

            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                gatt.close()
                if (bluetoothGatt === gatt) bluetoothGatt = null
                log(Log.INFO, "Disconnected from GATT completely. Previous step was: $stepAtCallback")

                currentStep = if (stepAtCallback == ConnectionStep.CONNECTED) stepBeforeConnect else stepAtCallback

                if (currentStep != ConnectionStep.IDLE && currentStep != ConnectionStep.FAILED) {
                    log(Log.INFO, "Proceeding to next connection strategy from disconnected state.")
                    handler.post { tryNextConnectionStep() }
                } else {
                    resetToIdleState()
                }
                return
            }
            
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                log(Log.INFO, "GATT Connection Established Successfully.")
                stepBeforeConnect = currentStep
                currentStep = ConnectionStep.CONNECTED
                bluetoothGatt = gatt
                discoveryRetryCount = 0
                runOnUiThread { updateConnectionState("Refreshing cache...") }

                // CRITICAL: Refresh GATT cache BEFORE discovering services
                val refreshed = refreshGattCache(gatt)
                
                // If refresh was successful, we might want a slightly longer delay to ensure the stack catches up
                val delayTime = if (refreshed) DISCOVERY_DELAY + 500 else DISCOVERY_DELAY
                
                log(Log.INFO, "Waiting ${delayTime}ms before discovering services (Refresh success: $refreshed)...")
                runOnUiThread { updateConnectionState("Discovering Services...") }

                watchdogHandler.postDelayed({ 
                    log(Log.WARN, "Watchdog: Service discovery timed out (did not trigger onServicesDiscovered).")
                    gatt.disconnect() 
                }, FAST_CONNECTION_TIMEOUT)
                
                handler.postDelayed({ 
                    log(Log.INFO, "Calling discoverServices() now...")
                    if (bluetoothGatt != null) {
                         val result = gatt.discoverServices()
                         log(Log.INFO, "discoverServices() returned: $result")
                         if (!result) {
                             log(Log.ERROR, "discoverServices() returned false immediately. Retrying or failing.")
                             // If it fails immediately, we might want to retry or disconnect
                         }
                    } else {
                        log(Log.WARN, "bluetoothGatt became null before discoverServices execution.")
                    }
                }, delayTime)

            } else {
                log(Log.WARN, "GATT Connection attempt failed. Status: $statusStr. Step: $stepAtCallback")
                gatt.close()
                if (bluetoothGatt === gatt) bluetoothGatt = null
                // If we were trying to connect and it failed, move to next step
                if (currentStep == stepAtCallback) {
                    handler.post { tryNextConnectionStep() }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            watchdogHandler.removeCallbacksAndMessages(null)
            val statusStr = gattStatusToString(status)
            log(Log.INFO, "onServicesDiscovered: Status=$statusStr")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Log all discovered services for debugging
                val services = gatt.services
                log(Log.INFO, "Discovered ${services.size} services.")
                
                // Detailed Service Logging
                services.forEach { service ->
                    log(Log.DEBUG, "  [S] ${service.uuid}")
                    service.characteristics.forEach { char ->
                        // Check if it matches our interested UUIDs
                        val isRelevant = char.uuid == COMMAND_UUID || char.uuid == RESPONSE_UUID
                        val marker = if (isRelevant) "***" else ""
                        log(Log.DEBUG, "    [C] $marker ${char.uuid} $marker")
                    }
                }
                
                commandCharacteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_UUID)
                val responseCharacteristic = gatt.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                
                if (commandCharacteristic == null || responseCharacteristic == null) {
                    discoveryRetryCount++
                    log(Log.WARN, "Required Z407 characteristics NOT found. Retry count: $discoveryRetryCount/$MAX_DISCOVERY_RETRIES")
                    
                    if (discoveryRetryCount < MAX_DISCOVERY_RETRIES) {
                        runOnUiThread { updateCommandFeedback("Retrying discovery ($discoveryRetryCount)...") }
                        
                        log(Log.INFO, "Attempting to refresh cache and discover again...")
                        refreshGattCache(gatt)
                        
                        handler.postDelayed({
                            log(Log.INFO, "Re-calling discoverServices()...")
                            gatt.discoverServices()
                        }, DISCOVERY_DELAY)
                        
                        // Reset watchdog for retry
                        watchdogHandler.postDelayed({ 
                            log(Log.WARN, "Retry Service discovery timed out.")
                            gatt.disconnect() 
                        }, FAST_CONNECTION_TIMEOUT)
                        return
                    }
                    
                    // CRITICAL: All retries failed. Flag for blind write attempt on next strategy.
                    log(Log.ERROR, "Discovery failed after $MAX_DISCOVERY_RETRIES retries. Flagging for BLIND_WRITE strategy.")
                    shouldTryBlindWrite = true
                    gatt.disconnect()
                    return
                }
                
                log(Log.INFO, "Found VALID Z407 characteristics. Setting up notifications...")
                
                val notificationSet = gatt.setCharacteristicNotification(responseCharacteristic, true)
                log(Log.INFO, "setCharacteristicNotification returned: $notificationSet")
                
                val descriptor = responseCharacteristic.getDescriptor(CCCD_UUID)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                         val writeResult = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                         log(Log.INFO, "writeDescriptor (Tiramisu+) returned: $writeResult")
                         if (writeResult != BluetoothStatusCodes.SUCCESS) {
                             log(Log.WARN, "writeDescriptor failed immediately with code: $writeResult")
                         }
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        val writeResult = gatt.writeDescriptor(descriptor)
                        log(Log.INFO, "writeDescriptor (Legacy) returned: $writeResult")
                    }
                } else {
                    log(Log.ERROR, "CCCD Descriptor NOT found on Response Characteristic. Cannot enable notifications.")
                }
                
            } else {
                log(Log.WARN, "Service discovery failed with status: $statusStr. Disconnecting.")
                gatt.disconnect()
            }
        }
        
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            val statusStr = gattStatusToString(status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                log(Log.INFO, "Descriptor write SUCCESS ($statusStr). Ready to send commands.")
                // Send handshake with a small delay to ensure stability
                handler.postDelayed({
                    sendCommand(Commands.HANDSHAKE, "Sending Handshake")
                }, 100)
            } else {
                log(Log.WARN, "Descriptor write FAILED. Status: $statusStr")
                gatt?.disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleResponse(value)
        }
        
        // Android 13+ Callback
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
             // For simplicity, we are not using the new value parameter here as we might target lower SDKs too, 
             // but if we were strictly 33+, we'd use characteristic.value (deprecated but accessible) or the other callback
             // However, the byte[] override is still called in many contexts or we should extract if 33+
             // Let's manually get value if needed, but usually the byte[] overload is sufficient for compatibility.
             // Actually, for Tiramisu, the byte[] callback is often not called if this one is defined.
             // Let's extract value safely.
             val data = characteristic.value
             handleResponse(data)
        }
    }

    private fun tryNextConnectionStep() {
        if (currentStep == ConnectionStep.FAILED) return
        
        watchdogHandler.removeCallbacksAndMessages(null)
        if (isScanning) stopScan()

        val stepToAdvanceFrom = currentStep
        log(Log.INFO, "Advancing from step: $stepToAdvanceFrom (shouldTryBlindWrite=$shouldTryBlindWrite, bounceCount=$gattBounceCount, rebondAttempted=$rebondAttempted)")

        currentStep = when (stepToAdvanceFrom) {
            // PHASE 1: Profile cleanup
            ConnectionStep.IDLE, ConnectionStep.PRE_SEQUENCE_CLEANUP -> ConnectionStep.DISCONNECT_ALL_PROFILES
            ConnectionStep.DISCONNECT_ALL_PROFILES -> ConnectionStep.EXTENDED_WAIT_AFTER_DISCONNECT
            ConnectionStep.EXTENDED_WAIT_AFTER_DISCONNECT -> ConnectionStep.DIRECT_CONNECT_AUTO
            
            // PHASE 2: Standard attempts
            ConnectionStep.DIRECT_CONNECT_AUTO -> ConnectionStep.GATT_BOUNCE_AGGRESSIVE
            ConnectionStep.GATT_BOUNCE_AGGRESSIVE -> ConnectionStep.DIRECT_CONNECT_LE
            ConnectionStep.DIRECT_CONNECT_LE -> ConnectionStep.EXTENDED_WAIT_DISCOVERY
            
            // PHASE 3: Extended discovery
            ConnectionStep.EXTENDED_WAIT_DISCOVERY -> ConnectionStep.AGGRESSIVE_DISCOVERY
            ConnectionStep.AGGRESSIVE_DISCOVERY -> ConnectionStep.SCAN_AND_CONNECT
            
            // PHASE 4: Scan-based approaches
            ConnectionStep.SCAN_AND_CONNECT -> ConnectionStep.REFRESH_CACHE
            ConnectionStep.REFRESH_CACHE -> ConnectionStep.BACKGROUND_CONNECT
            ConnectionStep.BACKGROUND_CONNECT -> {
                // After background connect fails, try rebond if not attempted
                if (!rebondAttempted) ConnectionStep.FORCE_REBOND
                else if (shouldTryBlindWrite) ConnectionStep.BLIND_WRITE_ATTEMPT
                else ConnectionStep.NUCLEAR_BLUETOOTH_RESET
            }
            
            // PHASE 5: Bonding operations
            ConnectionStep.FORCE_REBOND -> ConnectionStep.WAIT_FOR_REBOND
            ConnectionStep.WAIT_FOR_REBOND -> ConnectionStep.POST_REBOND_CONNECT
            ConnectionStep.POST_REBOND_CONNECT -> {
                if (shouldTryBlindWrite) ConnectionStep.BLIND_WRITE_ATTEMPT
                else ConnectionStep.NUCLEAR_BLUETOOTH_RESET
            }
            
            // PHASE 6: Last resort
            ConnectionStep.BLIND_WRITE_ATTEMPT -> {
                if (!nuclearResetAttempted) ConnectionStep.NUCLEAR_BLUETOOTH_RESET
                else ConnectionStep.FINAL_DIRECT_CONNECT
            }
            ConnectionStep.NUCLEAR_BLUETOOTH_RESET -> ConnectionStep.FINAL_DIRECT_CONNECT
            ConnectionStep.FINAL_DIRECT_CONNECT -> ConnectionStep.FAILED
            
            else -> ConnectionStep.FAILED
        }

        if(currentStep == ConnectionStep.FAILED) {
            log(Log.ERROR, "All connection strategies EXHAUSTED. Giving up.")
            runOnUiThread {
                updateConnectionState("Disconnected")
                updateCommandFeedback("FAILED. Manual action required: 1) Turn speaker OFF. 2) Unpair Z407 in Android Settings. 3) Turn speaker ON. 4) Re-pair. 5) Retry app.")
            }
            return
        }
        
        log(Log.INFO, "Executing strategy: $currentStep")

        when (currentStep) {
            // PHASE 1
            ConnectionStep.DISCONNECT_ALL_PROFILES -> disconnectAllProfilesAndProceed("Phase 1: Disconnect All Profiles")
            ConnectionStep.EXTENDED_WAIT_AFTER_DISCONNECT -> extendedWaitAfterDisconnect("Phase 1b: Extended Wait (5s)")
            
            // PHASE 2
            ConnectionStep.DIRECT_CONNECT_AUTO -> connectInternal(false, "Phase 2: Direct (Auto)", FAST_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_AUTO)
            ConnectionStep.GATT_BOUNCE_AGGRESSIVE -> performAggressiveGattBounce("Phase 2b: Aggressive GATT Bounce")
            ConnectionStep.DIRECT_CONNECT_LE -> connectInternal(false, "Phase 2c: Direct (LE)", FAST_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_LE)
            
            // PHASE 3
            ConnectionStep.EXTENDED_WAIT_DISCOVERY -> attemptExtendedWaitDiscovery("Phase 3: Extended Wait Discovery")
            ConnectionStep.AGGRESSIVE_DISCOVERY -> attemptAggressiveDiscovery("Phase 3b: Aggressive Discovery")
            
            // PHASE 4
            ConnectionStep.SCAN_AND_CONNECT -> startScanInternal("Phase 4: Scan & Connect")
            ConnectionStep.REFRESH_CACHE -> refreshCacheAndReconnect("Phase 4b: Cache Refresh")
            ConnectionStep.BACKGROUND_CONNECT -> connectInternal(true, "Phase 4c: Background (Wait)", BACKGROUND_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_LE)
            
            // PHASE 5
            ConnectionStep.FORCE_REBOND -> forceUnpairAndRebond("Phase 5: Force Re-Bond")
            ConnectionStep.WAIT_FOR_REBOND -> waitForRebondCompletion("Phase 5b: Waiting for Bond...")
            ConnectionStep.POST_REBOND_CONNECT -> connectInternal(false, "Phase 5c: Post-Rebond Connect", EXTENDED_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_AUTO)
            
            // PHASE 6
            ConnectionStep.BLIND_WRITE_ATTEMPT -> attemptBlindWrite("Phase 6: Blind Write")
            ConnectionStep.NUCLEAR_BLUETOOTH_RESET -> performNuclearBluetoothReset("Phase 6b: NUCLEAR Bluetooth Reset")
            ConnectionStep.FINAL_DIRECT_CONNECT -> connectInternal(false, "Phase 6c: FINAL Last Resort", EXTENDED_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_AUTO)
            
            else -> { /* FAILED case */ }
        }
    }
    
    /**
     * STRATEGY: DISCONNECT ALL PROFILES
     * Aggressively disconnect ALL Bluetooth profiles (A2DP, HFP, AVRCP) before attempting GATT.
     * This is critical because the Z407 may not expose GATT services while audio profiles are connected.
     */
    private fun disconnectAllProfilesAndProceed(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== DISCONNECT ALL PROFILES STRATEGY ===")
        
        if (targetDevice == null) {
            log(Log.WARN, "No target device, skipping profile disconnect.")
            tryNextConnectionStep()
            return
        }
        
        // Save MAC address in case we need to rebond later
        savedTargetAddress = targetDevice?.address
        profileDisconnectCount = 0
        
        val profilesToDisconnect = listOf(
            BluetoothProfile.A2DP,           // Audio streaming
            BluetoothProfile.HEADSET,        // Hands-free/headset profile
            11                                // AVRCP (BluetoothProfile.AVRCP = 11, hidden constant)
        )
        
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        var profilesProcessed = 0
        val totalProfiles = profilesToDisconnect.size
        
        fun checkAllProfilesProcessed() {
            profilesProcessed++
            if (profilesProcessed >= totalProfiles) {
                log(Log.INFO, "[AllProfiles] All profiles processed. Disconnected $profileDisconnectCount profiles.")
                tryNextConnectionStep()
            }
        }
        
        for (profileType in profilesToDisconnect) {
            try {
                bluetoothManager.adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        val profileName = when (profile) {
                            BluetoothProfile.A2DP -> "A2DP"
                            BluetoothProfile.HEADSET -> "HEADSET"
                            11 -> "AVRCP"
                            else -> "PROFILE_$profile"
                        }
                        
                        log(Log.INFO, "[$profileName] Profile proxy connected.")
                        
                        val connectedDevices = proxy.connectedDevices
                        val z407Device = connectedDevices.firstOrNull { 
                            it.address == targetDevice?.address 
                        }
                        
                        if (z407Device != null) {
                            log(Log.INFO, "[$profileName] Z407 is connected. Attempting disconnect...")
                            
                            try {
                                val disconnectMethod = proxy.javaClass.getMethod("disconnect", BluetoothDevice::class.java)
                                val result = disconnectMethod.invoke(proxy, z407Device)
                                log(Log.INFO, "[$profileName] disconnect() called. Result: $result")
                                profileDisconnectCount++
                            } catch (e: Exception) {
                                log(Log.WARN, "[$profileName] Could not disconnect: ${e.message}")
                            }
                        } else {
                            log(Log.DEBUG, "[$profileName] Z407 not connected via this profile.")
                        }
                        
                        bluetoothManager.adapter.closeProfileProxy(profile, proxy)
                        handler.postDelayed({ checkAllProfilesProcessed() }, 300)
                    }
                    
                    override fun onServiceDisconnected(profile: Int) {
                        log(Log.DEBUG, "[Profile $profile] Proxy disconnected.")
                    }
                }, profileType)
            } catch (e: Exception) {
                log(Log.WARN, "[Profile $profileType] Failed to get proxy: ${e.message}")
                handler.postDelayed({ checkAllProfilesProcessed() }, 100)
            }
        }
        
        // Timeout in case profile proxies don't respond
        handler.postDelayed({
            if (currentStep == ConnectionStep.DISCONNECT_ALL_PROFILES) {
                log(Log.WARN, "[AllProfiles] Timeout waiting for profile proxies. Proceeding anyway.")
                tryNextConnectionStep()
            }
        }, 8000)
    }
    
    /**
     * STRATEGY: EXTENDED WAIT AFTER DISCONNECT
     * Wait a longer time after disconnecting audio profiles to let the device "settle"
     */
    private fun extendedWaitAfterDisconnect(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== EXTENDED WAIT (5 seconds) after profile disconnect ===")
        
        handler.postDelayed({
            log(Log.INFO, "Extended wait complete. Proceeding to connection attempts.")
            tryNextConnectionStep()
        }, 5000)
    }
    
    /**
     * STRATEGY: AGGRESSIVE GATT BOUNCE
     * Rapidly connect and disconnect multiple times with different transports to "wake up" the GATT server.
     * Each cycle uses cache refresh and tries different connection parameters.
     */
    private fun performAggressiveGattBounce(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== AGGRESSIVE GATT BOUNCE (Cycle ${gattBounceCount + 1}/$GATT_BOUNCE_CYCLES) ===")
        
        if (targetDevice == null) {
            log(Log.ERROR, "Target device is null. Skipping bounce.")
            tryNextConnectionStep()
            return
        }
        
        gattBounceCount++
        
        // Alternate transport types to try to trigger different code paths in the BT stack
        val transport = when (gattBounceCount % 3) {
            1 -> BluetoothDevice.TRANSPORT_AUTO
            2 -> BluetoothDevice.TRANSPORT_LE
            else -> BluetoothDevice.TRANSPORT_BREDR
        }
        log(Log.INFO, "[AggressiveBounce] Using transport: $transport")
        
        val bounceCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                log(Log.INFO, "[AggressiveBounce] State: ${gattStatusToString(status)}, newState=$newState")
                
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log(Log.INFO, "[AggressiveBounce] Connected. Refreshing cache...")
                    refreshGattCache(gatt)
                    
                    // Try a quick discovery to see if services appear
                    handler.postDelayed({
                        log(Log.INFO, "[AggressiveBounce] Quick discoverServices check...")
                        val discovered = gatt.discoverServices()
                        log(Log.DEBUG, "[AggressiveBounce] discoverServices returned: $discovered")
                        
                        // Wait briefly, then check and disconnect
                        handler.postDelayed({
                            val services = gatt.services
                            log(Log.INFO, "[AggressiveBounce] Found ${services.size} services in quick check")
                            
                            if (services.isNotEmpty()) {
                                // Unexpected success! Check for our characteristics
                                val cmdChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_UUID)
                                val respChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                                if (cmdChar != null && respChar != null) {
                                    log(Log.INFO, "[AggressiveBounce] SURPRISE SUCCESS! Found Z407 characteristics!")
                                    watchdogHandler.removeCallbacksAndMessages(null)
                                    commandCharacteristic = cmdChar
                                    bluetoothGatt = gatt
                                    currentStep = ConnectionStep.CONNECTED
                                    gattBounceCount = 0
                                    setupNotificationsAndHandshake(gatt, respChar)
                                    return@postDelayed
                                }
                            }
                            
                            // No luck, disconnect and continue bouncing
                            gatt.disconnect()
                        }, 1500)
                    }, 500)
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    log(Log.INFO, "[AggressiveBounce] Disconnected. Cycle complete.")
                    
                    if (gattBounceCount < GATT_BOUNCE_CYCLES) {
                        // Wait, then do another bounce
                        handler.postDelayed({
                            performAggressiveGattBounce(feedback)
                        }, 800)
                    } else {
                        log(Log.INFO, "[AggressiveBounce] All $GATT_BOUNCE_CYCLES cycles complete. Proceeding.")
                        gattBounceCount = 0
                        tryNextConnectionStep()
                    }
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log(Log.DEBUG, "[AggressiveBounce] onServicesDiscovered: ${gattStatusToString(status)}, services=${gatt.services.size}")
            }
        }
        
        watchdogHandler.postDelayed({
            if (currentStep == ConnectionStep.GATT_BOUNCE_AGGRESSIVE) {
                log(Log.WARN, "[AggressiveBounce] Timeout. Proceeding to next step.")
                gattBounceCount = 0
                tryNextConnectionStep()
            }
        }, 25000) // Longer timeout for more cycles
        
        try {
            targetDevice!!.connectGatt(context, false, bounceCallback, transport)
        } catch (e: Exception) {
            log(Log.ERROR, "[AggressiveBounce] Exception: ${e.message}")
            gattBounceCount = 0
            tryNextConnectionStep()
        }
    }
    
    /**
     * STRATEGY: EXTENDED WAIT DISCOVERY
     * Connect and wait a VERY long time (10+ seconds) before attempting service discovery.
     * Some devices need extra time for the GATT server to become ready.
     */
    private fun attemptExtendedWaitDiscovery(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== EXTENDED WAIT DISCOVERY STRATEGY ===")
        log(Log.INFO, "Will wait 10 seconds after connection before discovery...")
        
        if (targetDevice == null) {
            log(Log.ERROR, "Target device is null.")
            tryNextConnectionStep()
            return
        }
        
        val extendedCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                log(Log.INFO, "[ExtendedWait] State: ${gattStatusToString(status)}, newState=$newState")
                
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    bluetoothGatt = gatt
                    log(Log.INFO, "[ExtendedWait] Connected. Refreshing cache and waiting 10 seconds...")
                    
                    refreshGattCache(gatt)
                    runOnUiThread { updateCommandFeedback("Waiting 10s for GATT to stabilize...") }
                    
                    // Wait a VERY long time
                    handler.postDelayed({
                        log(Log.INFO, "[ExtendedWait] 10 seconds elapsed. Calling discoverServices()...")
                        val result = gatt.discoverServices()
                        log(Log.INFO, "[ExtendedWait] discoverServices() returned: $result")
                    }, 10000) // 10 second wait!
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    log(Log.INFO, "[ExtendedWait] Disconnected. Proceeding to next strategy.")
                    tryNextConnectionStep()
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services = gatt.services
                log(Log.INFO, "[ExtendedWait] Discovered ${services.size} services")
                
                if (services.isNotEmpty()) {
                    val cmdChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_UUID)
                    val respChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                    
                    if (cmdChar != null && respChar != null) {
                        log(Log.INFO, "[ExtendedWait] SUCCESS! Found Z407 characteristics!")
                        watchdogHandler.removeCallbacksAndMessages(null)
                        commandCharacteristic = cmdChar
                        currentStep = ConnectionStep.CONNECTED
                        setupNotificationsAndHandshake(gatt, respChar)
                        return
                    }
                }
                
                log(Log.WARN, "[ExtendedWait] No valid services. Disconnecting.")
                gatt.disconnect()
            }
            
            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log(Log.INFO, "[ExtendedWait] Descriptor written!")
                    handler.postDelayed({
                        sendCommand(Commands.HANDSHAKE, "Handshake")
                    }, 100)
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleResponse(value)
            }
        }
        
        watchdogHandler.postDelayed({
            if (currentStep == ConnectionStep.EXTENDED_WAIT_DISCOVERY) {
                log(Log.WARN, "[ExtendedWait] Timeout (45s). Disconnecting and moving on.")
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                tryNextConnectionStep()
            }
        }, EXTENDED_CONNECTION_TIMEOUT) // 45 second timeout
        
        try {
            targetDevice!!.connectGatt(context, false, extendedCallback, BluetoothDevice.TRANSPORT_AUTO)
        } catch (e: Exception) {
            log(Log.ERROR, "[ExtendedWait] Exception: ${e.message}")
            tryNextConnectionStep()
        }
    }
    
    /**
     * STRATEGY: AGGRESSIVE DISCOVERY
     * Connect, wait longer, do multiple discoverServices() calls with longer delays
     */
    private fun attemptAggressiveDiscovery(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== AGGRESSIVE DISCOVERY STRATEGY ===")
        
        if (targetDevice == null) {
            log(Log.ERROR, "Target device is null.")
            tryNextConnectionStep()
            return
        }
        
        aggressiveDiscoveryAttempt = 0
        
        val aggressiveCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                log(Log.INFO, "[Aggressive] State: ${gattStatusToString(status)}, newState=$newState")
                
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    bluetoothGatt = gatt
                    log(Log.INFO, "[Aggressive] Connected. Waiting 3 seconds before first discovery...")
                    
                    // Wait longer before first discovery
                    handler.postDelayed({
                        refreshGattCache(gatt)
                        
                        handler.postDelayed({
                            log(Log.INFO, "[Aggressive] Calling discoverServices()...")
                            gatt.discoverServices()
                        }, 3000) // 3 second delay after refresh
                        
                    }, 3000) // 3 second delay after connect
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    log(Log.INFO, "[Aggressive] Disconnected. Proceeding.")
                    tryNextConnectionStep()
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val services = gatt.services
                log(Log.INFO, "[Aggressive] Discovered ${services.size} services (attempt ${aggressiveDiscoveryAttempt + 1})")
                
                if (services.isNotEmpty()) {
                    val cmdChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(COMMAND_UUID)
                    val respChar = gatt.getService(SERVICE_UUID)?.getCharacteristic(RESPONSE_UUID)
                    
                    if (cmdChar != null && respChar != null) {
                        log(Log.INFO, "[Aggressive] SUCCESS! Found Z407 characteristics!")
                        watchdogHandler.removeCallbacksAndMessages(null)
                        commandCharacteristic = cmdChar
                        currentStep = ConnectionStep.CONNECTED
                        setupNotificationsAndHandshake(gatt, respChar)
                        return
                    }
                }
                
                aggressiveDiscoveryAttempt++
                if (aggressiveDiscoveryAttempt < 3) {
                    log(Log.INFO, "[Aggressive] Retrying discovery in 2 seconds...")
                    refreshGattCache(gatt)
                    handler.postDelayed({
                        gatt.discoverServices()
                    }, 2000)
                } else {
                    log(Log.WARN, "[Aggressive] No services found after ${aggressiveDiscoveryAttempt} attempts. Disconnecting.")
                    gatt.disconnect()
                }
            }
            
            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log(Log.INFO, "[Aggressive] Descriptor written!")
                    handler.postDelayed({
                        sendCommand(Commands.HANDSHAKE, "Handshake")
                    }, 100)
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleResponse(value)
            }
        }
        
        watchdogHandler.postDelayed({
            if (currentStep == ConnectionStep.AGGRESSIVE_DISCOVERY) {
                log(Log.WARN, "[Aggressive] Timeout. Disconnecting and moving on.")
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
                bluetoothGatt = null
                tryNextConnectionStep()
            }
        }, 30000) // 30 second timeout for aggressive strategy
        
        try {
            targetDevice!!.connectGatt(context, false, aggressiveCallback, BluetoothDevice.TRANSPORT_AUTO)
        } catch (e: Exception) {
            log(Log.ERROR, "[Aggressive] Exception: ${e.message}")
            tryNextConnectionStep()
        }
    }
    
    /**
     * BLIND WRITE STRATEGY
     * When discoverServices() fails but we connected successfully, we try to write directly
     * to the known characteristic UUID without going through the normal discovery flow.
     * This mimics how bleak (Python) works on Linux/Windows.
     */
    private fun attemptBlindWrite(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== BLIND WRITE STRATEGY ===")
        log(Log.INFO, "Attempting direct connection and write without service discovery.")
        
        if (targetDevice == null) {
            log(Log.ERROR, "Target device is null. Cannot attempt blind write.")
            tryNextConnectionStep()
            return
        }
        
        // Special callback for blind write - skips discovery, tries writing directly
        val blindWriteCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                val statusStr = gattStatusToString(status)
                log(Log.INFO, "[BlindWrite] onConnectionStateChange: Status=$statusStr, newState=$newState")
                
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    log(Log.INFO, "[BlindWrite] Connected! Attempting refresh then direct write...")
                    bluetoothGatt = gatt
                    
                    // Refresh first
                    refreshGattCache(gatt)
                    
                    // Wait, then try to discover. But if discovery fails, go straight to writing
                    handler.postDelayed({
                        log(Log.INFO, "[BlindWrite] Calling discoverServices one more time...")
                        val result = gatt.discoverServices()
                        log(Log.INFO, "[BlindWrite] discoverServices returned: $result")
                        
                        // Give it some time to complete, with a timeout
                        handler.postDelayed({
                            // Check if we got the characteristics
                            val service = gatt.getService(SERVICE_UUID)
                            if (service != null) {
                                val cmd = service.getCharacteristic(COMMAND_UUID)
                                val resp = service.getCharacteristic(RESPONSE_UUID)
                                if (cmd != null && resp != null) {
                                    log(Log.INFO, "[BlindWrite] SUCCESS! Found characteristics after extra discovery.")
                                    commandCharacteristic = cmd
                                    currentStep = ConnectionStep.CONNECTED
                                    setupNotificationsAndHandshake(gatt, resp)
                                    return@postDelayed
                                }
                            }
                            
                            // If still not found, fail and move to next step
                            log(Log.WARN, "[BlindWrite] Still no characteristics. Disconnecting and moving on.")
                            gatt.disconnect()
                            gatt.close()
                            bluetoothGatt = null
                            shouldTryBlindWrite = false // Don't retry blind write again
                            tryNextConnectionStep()
                        }, 3000) // 3 seconds to wait for discovery
                        
                    }, DISCOVERY_DELAY)
                    
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    gatt.close()
                    if (bluetoothGatt === gatt) bluetoothGatt = null
                    log(Log.INFO, "[BlindWrite] Disconnected. Moving to next strategy.")
                    shouldTryBlindWrite = false
                    tryNextConnectionStep()
                }
            }
            
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log(Log.INFO, "[BlindWrite] onServicesDiscovered: ${gattStatusToString(status)}, services count: ${gatt.services.size}")
                // Let the handler above deal with checking characteristics
            }
            
            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    log(Log.INFO, "[BlindWrite] Descriptor write SUCCESS!")
                    handler.postDelayed({
                        sendCommand(Commands.HANDSHAKE, "Sending Handshake")
                    }, 100)
                } else {
                    log(Log.WARN, "[BlindWrite] Descriptor write FAILED: ${gattStatusToString(status)}")
                }
            }
            
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                handleResponse(value)
            }
        }
        
        // Set timeout
        watchdogHandler.postDelayed({
            log(Log.WARN, "[BlindWrite] Strategy timed out.")
            bluetoothGatt?.close()
            bluetoothGatt = null
            shouldTryBlindWrite = false
            tryNextConnectionStep()
        }, FAST_CONNECTION_TIMEOUT + 5000) // Extra time for blind write
        
        try {
            bluetoothGatt = targetDevice!!.connectGatt(context, false, blindWriteCallback, BluetoothDevice.TRANSPORT_LE)
            if (bluetoothGatt == null) {
                log(Log.ERROR, "[BlindWrite] connectGatt returned null.")
                watchdogHandler.removeCallbacksAndMessages(null)
                shouldTryBlindWrite = false
                tryNextConnectionStep()
            }
        } catch (e: Exception) {
            log(Log.ERROR, "[BlindWrite] Exception: ${e.message}")
            watchdogHandler.removeCallbacksAndMessages(null)
            shouldTryBlindWrite = false
            tryNextConnectionStep()
        }
    }
    
    /**
     * STRATEGY: FORCE UNPAIR AND REBOND
     * Removes the bond (unpair) and initiates a new bond.
     * This clears the OS-level GATT cache which is often the cause of "0 services discovered".
     */
    private fun forceUnpairAndRebond(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== FORCE UNPAIR AND REBOND STRATEGY ===")

        if (targetDevice == null && savedTargetAddress != null) {
            targetDevice = bluetoothAdapter?.getRemoteDevice(savedTargetAddress)
        }

        if (targetDevice == null) {
            log(Log.ERROR, "Target device is null. Cannot rebond.")
            tryNextConnectionStep()
            return
        }

        rebondAttempted = true
        val device = targetDevice!!

        try {
            log(Log.INFO, "Removing bond for ${device.address}...")
            val removeBondMethod = device.javaClass.getMethod("removeBond")
            val result = removeBondMethod.invoke(device) as Boolean
            log(Log.INFO, "removeBond returned: $result")

            if (result) {
                runOnUiThread { updateCommandFeedback("Unpairing... Wait.") }
                // Wait for unpair to complete
                handler.postDelayed({
                    log(Log.INFO, "Initiating new bond (createBond)...")
                    runOnUiThread { updateCommandFeedback("Re-bonding...") }
                    
                    // Register receiver if not already
                     if (bondReceiver == null) {
                        bondReceiver = object : BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: Intent) {
                                if (BluetoothDevice.ACTION_BOND_STATE_CHANGED == intent.action) {
                                    val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                                    val prevState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                                    log(Log.INFO, "Bond State Change: $prevState -> $state")
                                    
                                    if (state == BluetoothDevice.BOND_BONDED) {
                                        log(Log.INFO, "Bonding Complete!")
                                        try { context.unregisterReceiver(this) } catch(e: Exception) {}
                                        bondReceiver = null
                                        if (currentStep == ConnectionStep.WAIT_FOR_REBOND) {
                                            tryNextConnectionStep()
                                        }
                                    }
                                }
                            }
                        }
                        context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
                    }
                    
                    val bondResult = device.createBond()
                    log(Log.INFO, "createBond returned: $bondResult")
                    
                    // Move to wait state
                    currentStep = ConnectionStep.WAIT_FOR_REBOND
                    if (!bondResult) {
                         log(Log.WARN, "createBond returned false. Proceeding anyway.")
                        tryNextConnectionStep() // If immediate fail, move on
                    } else {
                        // Set timeout in waitForRebondCompletion
                        tryNextConnectionStep() 
                    }
                    
                }, POST_UNPAIR_DELAY)
            } else {
                log(Log.WARN, "Failed to remove bond. Proceeding anyway.")
                tryNextConnectionStep()
            }
        } catch (e: Exception) {
            log(Log.ERROR, "Exception during unpair/rebond: ${e.message}")
            tryNextConnectionStep()
        }
    }

    /**
     * STRATEGY: WAIT FOR REBOND
     * Just a holding state while the broadcast receiver waits for BOND_BONDED.
     * Includes a timeout.
     */
    private fun waitForRebondCompletion(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "=== WAITING FOR REBOND ===")
        
        // Timeout for bonding
        handler.postDelayed({
            if (currentStep == ConnectionStep.WAIT_FOR_REBOND) {
                log(Log.WARN, "Bonding timed out. Proceeding.")
                try {
                    if (bondReceiver != null) {
                        context.unregisterReceiver(bondReceiver!!)
                        bondReceiver = null
                    }
                } catch (e: Exception) { /* Ignore */ }
                
                tryNextConnectionStep()
            }
        }, 15000)
    }

    /**
     * STRATEGY: NUCLEAR BLUETOOTH RESET
     * Determines that the stack is FUBAR and toggles the Bluetooth adapter off and on.
     * This is the "Hit it with a hammer" approach.
     */
    private fun performNuclearBluetoothReset(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.WARN, "=== NUCLEAR BLUETOOTH RESET ===")
        
        nuclearResetAttempted = true
        val adapter = bluetoothAdapter
        
        if (adapter == null) {
             tryNextConnectionStep()
             return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            log(Log.WARN, "Cannot programmatically toggle BT on Android 13+. Skipping.")
            tryNextConnectionStep()
            return
        }
        
        try {
            log(Log.WARN, "Disabling Bluetooth Adapter...")
            adapter.disable()
            
            runOnUiThread { updateCommandFeedback("Resetting BT Adapter...") }
            
            // Wait for it to turn off
            handler.postDelayed({
                log(Log.WARN, "Enabling Bluetooth Adapter...")
                adapter.enable()
                
                // Wait for it to turn on and initialize
                handler.postDelayed({
                    log(Log.INFO, "Bluetooth Reset Complete. Resuming...")
                    tryNextConnectionStep()
                }, 5000)
                
            }, 2500)
            
        } catch (e: Exception) {
            log(Log.ERROR, "Failed to reset Bluetooth: ${e.message}")
            tryNextConnectionStep()
        }
    }

    /**
     * Helper to set up notifications and send handshake once we have valid characteristics
     */
    private fun setupNotificationsAndHandshake(gatt: BluetoothGatt, responseChar: BluetoothGattCharacteristic) {
        log(Log.INFO, "Setting up notifications on response characteristic...")
        val notificationSet = gatt.setCharacteristicNotification(responseChar, true)
        log(Log.INFO, "setCharacteristicNotification returned: $notificationSet")
        
        val descriptor = responseChar.getDescriptor(CCCD_UUID)
        if (descriptor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val writeResult = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                log(Log.INFO, "writeDescriptor (Tiramisu+) returned: $writeResult")
            } else {
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val writeResult = gatt.writeDescriptor(descriptor)
                log(Log.INFO, "writeDescriptor (Legacy) returned: $writeResult")
            }
        } else {
            log(Log.WARN, "CCCD Descriptor not found. Trying handshake anyway...")
            handler.postDelayed({
                sendCommand(Commands.HANDSHAKE, "Sending Handshake (no descriptor)")
            }, 200)
        }
    }

    fun startAutomaticConnect() = startConnectionSequence(isUserInitiated = false)
    fun startUserConnect() = startConnectionSequence(isUserInitiated = true)
    
    private fun startConnectionSequence(isUserInitiated: Boolean) {
        log(Log.INFO, "=== Start connection sequence (UserInitiated=$isUserInitiated) ===")
        
        if (currentStep != ConnectionStep.IDLE && currentStep != ConnectionStep.FAILED && !isUserInitiated) {
            log(Log.WARN, "Ignored automatic trigger: Sequence already active in step $currentStep")
            return
        }

        runOnUiThread { commandFeedbackCallback("Initializing...") }
        currentStep = ConnectionStep.PRE_SEQUENCE_CLEANUP
        shouldTryBlindWrite = false   // Reset blind write flag for fresh attempt
        discoveryRetryCount = 0       // Reset retry counter
        gattBounceCount = 0           // Reset bounce counter
        aggressiveDiscoveryAttempt = 0 // Reset aggressive discovery counter
        disconnect() 
        
        handler.postDelayed({ 
             val btAdapter = bluetoothAdapter
            if (btAdapter == null || !btAdapter.isEnabled) {
                log(Log.ERROR, "Bluetooth Helper: Adapter is NULL or DISABLED.")
                updateCommandFeedback("Bluetooth is OFF.")
                return@postDelayed
            }
            
            // Try to find the device in bonded devices
            val bondedDevices = btAdapter.bondedDevices
            targetDevice = bondedDevices.firstOrNull { it.name?.contains("Z407", ignoreCase = true) == true }
                ?: bondedDevices.firstOrNull { it.address == "EC:81:93:FA:27:2D" } // Fallback to known MAC if possible, though user didn\'t request

            if (targetDevice == null) {
                log(Log.INFO, "Z407 not found in bonded list of ${bondedDevices.size} devices.")
                log(Log.INFO, "Bonded list: ${bondedDevices.joinToString { it.name ?: it.address }}")
                log(Log.INFO, "Starting with SCAN strategy.")
                currentStep = ConnectionStep.BACKGROUND_CONNECT // Trick to make next step SCAN
            } else {
                 log(Log.INFO, "Target Device Found: ${targetDevice?.name} [${targetDevice?.address}]")
                 currentStep = ConnectionStep.IDLE 
            }
            tryNextConnectionStep()
        }, 500) 
    }

    private fun connectInternal(autoConnect: Boolean, feedback: String, timeout: Long, transport: Int) {
        if (targetDevice == null) {
            log(Log.ERROR, "connectInternal: Target device is NULL. Advancing.")
            tryNextConnectionStep()
            return
        }
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "Connecting to ${targetDevice!!.address} | Auto: $autoConnect | Transport: $transport")

        val stepAtRequest = currentStep
        watchdogHandler.postDelayed({ 
            if (currentStep == stepAtRequest) {
                log(Log.WARN, "Watchdog: Connect timed out for step $currentStep. Forcing disconnect and next step.")
                bluetoothGatt?.close() 
                bluetoothGatt = null
                tryNextConnectionStep() 
            }
        }, timeout)
        
        try {
            bluetoothGatt = targetDevice!!.connectGatt(context, autoConnect, gattCallback, transport)
            if (bluetoothGatt == null) {
                log(Log.ERROR, "connectGatt returned NULL.")
                watchdogHandler.removeCallbacksAndMessages(null)
                tryNextConnectionStep()
            }
        } catch (e: Exception) {
            log(Log.ERROR, "Exception during connectGatt: ${e.message}")
            watchdogHandler.removeCallbacksAndMessages(null)
            tryNextConnectionStep()
        }
    }

    private fun startScanInternal(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "Starting BLE Scan for Z407...")
        
        val filters = listOf(
            ScanFilter.Builder().setDeviceName("Logi Z407").build(),
            ScanFilter.Builder().setDeviceName("Z407").build()
        )
        // Also scan without filters as some devices don\'t advertise name in packet
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
            
        try {
            // Using wide scan first to be safe, filter in callback
            scanner?.startScan(null, settings, scanCallback)
            isScanning = true
        } catch (e: Exception) {
            log(Log.ERROR, "Failed to start scan: ${e.message}")
            tryNextConnectionStep()
            return
        }

        val stepAtRequest = currentStep
        watchdogHandler.postDelayed({ 
            if(isScanning) {
                log(Log.WARN, "Watchdog: Scan timed out.")
                stopScan()
                if (currentStep == stepAtRequest) tryNextConnectionStep()
            }
        }, FAST_CONNECTION_TIMEOUT)
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown"
            // log(Log.DEBUG, "Scan Result: $name [${device.address}]") // Commented out to avoid log spam
            
            if (name.contains("Z407", ignoreCase = true)) {
                watchdogHandler.removeCallbacksAndMessages(null)
                stopScan()
                log(Log.INFO, "SCAN MATCH: Found Z407! -> $name [${device.address}]")
                targetDevice = device
                stepBeforeConnect = ConnectionStep.SCAN_AND_CONNECT 
                currentStep = ConnectionStep.SCAN_AND_CONNECT
                
                // Once found, connect immediately
                connectInternal(false, "Connecting after scan...", FAST_CONNECTION_TIMEOUT, BluetoothDevice.TRANSPORT_LE)
            }
        }
        
        override fun onScanFailed(errorCode: Int) { 
            log(Log.ERROR, "Scan failed with error code: $errorCode")
            stopScan()
            tryNextConnectionStep() 
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        try {
            scanner?.stopScan(scanCallback)
            log(Log.INFO, "Scan stopped.")
        } catch (e: Exception) {
            log(Log.WARN, "Error stopping scan: ${e.message}")
        }
        isScanning = false
    }

    private fun refreshCacheAndReconnect(feedback: String) {
        runOnUiThread { updateCommandFeedback(feedback) }
        log(Log.INFO, "Strategy: Refresh Cache via temporary connection.")
        
        if (targetDevice == null) {
            log(Log.WARN, "Cannot refresh cache, target device is null.")
            tryNextConnectionStep()
            return
        }

        var success = false
        try {
            // We connect, refresh, and immediately disconnect
            // We don\'t attach the main callback here to avoid confusing the state machine
            val tempCallback = object : BluetoothGattCallback() {
                 override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                     if (newState == BluetoothProfile.STATE_CONNECTED) {
                         success = refreshGattCache(gatt)
                         log(Log.INFO, "Temp connection for refresh established. Refresh result: $success")
                         gatt.disconnect()
                     } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                         gatt.close()
                     }
                 }
            }
            
            val gatt = targetDevice?.connectGatt(context, false, tempCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) {
                log(Log.ERROR, "Failed to create temp GATT for refresh.")
                success = false
            }
            
        } catch (e: Exception) {
            log(Log.ERROR, "Error during cache refresh strategy: ${e.message}")
        }
        
        // Wait a bit regardless of immediate result to let stack settle
        handler.postDelayed({ tryNextConnectionStep() }, 2000)
    }

    fun disconnect() {
        log(Log.INFO, "Public disconnect requested.")
        if (currentStep == ConnectionStep.IDLE) return
        
        currentStep = ConnectionStep.IDLE
        watchdogHandler.removeCallbacksAndMessages(null)
        if (isScanning) stopScan()
        
        bluetoothGatt?.let {
            log(Log.INFO, "Closing active GATT connection.")
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        resetToIdleState()
    }

    private fun resetToIdleState() {
        runOnUiThread { 
            updateConnectionState("Disconnected")
            updateCommandFeedback("Ready to connect") 
        }
    }
    
    // ... [Command Sending Logic Remains similar but safer] ...
    private fun sendCommand(command: ByteArray, feedback: String) {
        if (bluetoothGatt == null) {
            log(Log.WARN, "Cmd Failed: GATT is null.")
            return
        }
        if (commandCharacteristic == null) {
              log(Log.WARN, "Cmd Failed: Characteristic is null (Not discovered yet?).")
              return
        }
        
        runOnUiThread { updateCommandFeedback("CMD: $feedback") }
        log(Log.INFO, "Sending command bytes: ${command.toHexString()} ($feedback)")
        
        try {
             if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                 val res = bluetoothGatt?.writeCharacteristic(commandCharacteristic!!, command, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                 if (res != BluetoothStatusCodes.SUCCESS) log(Log.WARN, "writeCharacteristic returned code: $res")
            } else {
                commandCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                commandCharacteristic?.value = command
                val res = bluetoothGatt?.writeCharacteristic(commandCharacteristic)
                if (res != true) log(Log.WARN, "writeCharacteristic returned false")
            }
        } catch (e: Exception) {
            log(Log.ERROR, "Exception sending command: ${e.message}")
        }
    }

    private fun handleResponse(response: ByteArray) {
        val hex = response.toHexString()
        log(Log.DEBUG, "RX Data: $hex")
        
        val feedback = when (hex) {
            "d40501" -> { sendCommand(Commands.KEEPALIVE_RESPONSE, "Handshake Ack"); null }
            "d40001", "d40003" -> { runOnUiThread { updateConnectionState("Connected") }; "Ready!" }
            "c000" -> "Bass: MAX/UP"
            "c001" -> "Bass: MIN/DOWN"
            "c002" -> "Vol: UP"
            "c003" -> "Vol: DOWN"
            "c004" -> "Play/Pause"
            "c005" -> "Next"
            "c006" -> "Prev"
            "c101", "cf04" -> "Input: Bluetooth"
            "c102", "cf05" -> "Input: AUX"
            "c103", "cf06" -> "Input: USB"
            "c200" -> "Pairing Mode"
            "c300" -> "Factory Reset"
            else -> { "Unknown RX: $hex" }
        }
        feedback?.let { runOnUiThread { updateCommandFeedback(it) } }
    }

    private fun updateConnectionState(message: String) {
        // log(Log.INFO, "UI State: $message")
        connectionCallback(message)
    }

    private fun updateCommandFeedback(message: String) {
        // log(Log.INFO, "UI Feedback: $message")
        commandFeedbackCallback(message)
    }


    // Command wrappers
    fun volumeUp() = sendCommand(Commands.VOLUME_UP, "Volume Up")
    fun volumeDown() = sendCommand(Commands.VOLUME_DOWN, "Volume Down")
    fun playPause() = sendCommand(Commands.PLAY_PAUSE, "Play/Pause")
    fun nextTrack() = sendCommand(Commands.NEXT_TRACK, "Next Track")
    fun prevTrack() = sendCommand(Commands.PREV_TRACK, "Previous Track")
    fun bassUp() = sendCommand(Commands.BASS_UP, "Bass Up")
    fun bassDown() = sendCommand(Commands.BASS_DOWN, "Bass Down")
    fun setInputBluetooth() = sendCommand(Commands.INPUT_BLUETOOTH, "Input to Bluetooth")
    fun setInputAux() = sendCommand(Commands.INPUT_AUX, "Input to AUX")
    fun setInputUsb() = sendCommand(Commands.INPUT_USB, "Input to USB")
    fun startBluetoothPairing() = sendCommand(Commands.BLUETOOTH_PAIR, "Pairing Mode")
    fun factoryReset() = sendCommand(Commands.FACTORY_RESET, "Factory Reset")
}

object Commands {
    val HANDSHAKE = "8405".decodeHex()
    val KEEPALIVE_RESPONSE = "8400".decodeHex()
    val VOLUME_UP = "8002".decodeHex()
    val VOLUME_DOWN = "8003".decodeHex()
    val BASS_UP = "8000".decodeHex()
    val BASS_DOWN = "8001".decodeHex()
    val PLAY_PAUSE = "8004".decodeHex()
    val NEXT_TRACK = "8005".decodeHex()
    val PREV_TRACK = "8006".decodeHex()
    val INPUT_BLUETOOTH = "8101".decodeHex()
    val INPUT_AUX = "8102".decodeHex()
    val INPUT_USB = "8103".decodeHex()
    val BLUETOOTH_PAIR = "8200".decodeHex()
    val FACTORY_RESET = "8300".decodeHex()
}

fun String.decodeHex(): ByteArray {
    check(length % 2 == 0) { "Must have an even length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
