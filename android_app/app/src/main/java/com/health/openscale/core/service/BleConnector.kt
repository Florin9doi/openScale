/*
 * openScale
 * Copyright (C) 2025 olie.xdev <olie.xdeveloper@googlemail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.service

import android.annotation.SuppressLint
import android.os.Handler
import com.health.openscale.R
import com.health.openscale.core.bluetooth.BluetoothEvent
import com.health.openscale.core.bluetooth.ScaleCommunicator
import com.health.openscale.core.bluetooth.ScaleFactory
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.ConnectionStatus
import com.health.openscale.core.data.Measurement
import com.health.openscale.core.data.MeasurementTypeKey
import com.health.openscale.core.data.MeasurementValue
import com.health.openscale.core.database.DatabaseRepository
import com.health.openscale.core.utils.LogManager
import com.health.openscale.ui.shared.SnackbarEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages Bluetooth connections to scale devices, handling the connection lifecycle,
 * data reception, and error reporting. It interacts with [com.health.openscale.core.bluetooth.ScaleCommunicator] instances
 * created by [com.health.openscale.core.bluetooth.ScaleFactory] and updates UI state via [com.health.openscale.ui.shared.SharedViewModel] and observable Flows.
 *
 * This class is designed to be used within a [kotlinx.coroutines.CoroutineScope], typically from a ViewModel.
 * It implements [AutoCloseable] to ensure resources are released when it's no longer needed.
 *
 * @param scope The [kotlinx.coroutines.CoroutineScope] in which background operations like connection and
 *              event observation will be launched (e.g., `viewModelScope` from BluetoothViewModel).
 * @param scaleFactory A factory for creating [com.health.openscale.core.bluetooth.ScaleCommunicator] instances based on device information.
 * @param databaseRepository Repository for saving received measurements.
 * @param sharedViewModel ViewModel for showing snackbars and potentially other UI interactions.
 * @param getCurrentScaleUser Callback function to retrieve the current Bluetooth scale user.
 * @param getCurrentAppUserId Callback function to retrieve the ID of the current application user.
 * @param onUserSelectionRequired Callback to notify the UI when user interaction on the device is needed.
 * @param onSavePreferredDevice Callback to save the successfully connected device as preferred.
 */
class BleConnector(
    private val scope: CoroutineScope,
    private val scaleFactory: ScaleFactory,
    private val databaseRepository: DatabaseRepository,
    private val getCurrentScaleUser: () -> ScaleUser?,
    ) : AutoCloseable {

    private companion object {
        const val TAG = "BluetoothConnManager"
        const val DISCONNECT_TIMEOUT_MS = 3000L // Timeout for forceful disconnect if no event received.
    }

    private val _snackbarEvents = MutableSharedFlow<SnackbarEvent>(replay = 0, extraBufferCapacity = 1)
    val snackbarEvents: SharedFlow<SnackbarEvent> = _snackbarEvents.asSharedFlow()

    private val _connectedDeviceName = MutableStateFlow<String?>(null)
    /** Emits the name of the currently connected device, or null if not connected. */
    val connectedDeviceName: StateFlow<String?> = _connectedDeviceName.asStateFlow()

    private val _connectedDeviceAddress = MutableStateFlow<String?>(null)
    /** Emits the MAC address of the currently connected device, or null if not connected. */
    val connectedDeviceAddress: StateFlow<String?> = _connectedDeviceAddress.asStateFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.DISCONNECTED)
    /** Emits the current [ConnectionStatus] of the Bluetooth device. */
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _connectionError = MutableStateFlow<String?>(null)
    /** Emits an error message if a connection or operational error occurs, null otherwise. */
    val connectionError: StateFlow<String?> = _connectionError.asStateFlow()

    private val _userInteractionRequiredEvent =
        MutableStateFlow<BluetoothEvent.UserInteractionRequired?>(null)
    val userInteractionRequiredEvent: StateFlow<BluetoothEvent.UserInteractionRequired?> = _userInteractionRequiredEvent.asStateFlow()

    private var activeCommunicator: ScaleCommunicator? = null
    private var communicatorJob: Job? = null // Job for observing events from the activeCommunicator.
    private var disconnectTimeoutJob: Job? = null // Job for handling disconnect timeouts.

    private val savedBurstSignal = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 64)
    private val pendingSavedCount = AtomicInteger(0)
    @Volatile private var lastSavedArgs: List<Any> = emptyList()

    init {
        scope.launch {
            savedBurstSignal
                .debounce(700)
                .collect {
                    val count = pendingSavedCount.getAndSet(0)
                    if (count <= 0) return@collect

                    if (count == 1) {
                        _snackbarEvents.emit(
                            SnackbarEvent(
                                messageResId = R.string.bluetooth_connector_measurement_saved,
                                messageFormatArgs = lastSavedArgs
                            )
                        )
                    } else {
                        _snackbarEvents.tryEmit(
                            SnackbarEvent(
                                messageResId = R.string.saved_measurements_message,
                                messageFormatArgs = listOf(count)
                            )
                        )
                    }
                }
        }
    }


    /**
     * Attempts to connect to the specified Bluetooth device.
     * This function is suspendable and performs operations in the [scope] provided during construction.
     *
     * Note: Bluetooth permissions and enabled status should be checked by the caller (ViewModel)
     * before invoking this method, as this manager cannot display UI prompts for them.
     *
     * @param deviceInfo Information about the scanned device to connect to.
     */
    @SuppressLint("MissingPermission") // Permissions are expected to be checked by the caller.
    fun connectToDevice(deviceInfo: ScannedDeviceInfo) {
        scope.launch {
            val deviceDisplayName = deviceInfo.name ?: deviceInfo.address
            LogManager.i(TAG, "Attempting to connect to $deviceDisplayName")

            // Some legacy or specific openScale handlers might require a valid user.
            val needsUserCheck = deviceInfo.determinedHandlerDisplayName?.contains("legacy", ignoreCase = true) == true ||
                    deviceInfo.determinedHandlerDisplayName?.startsWith("com.health.openscale") == true

            if (needsUserCheck && getCurrentScaleUser()?.id == 0) {
                LogManager.e(TAG, "User ID is 0, which might be problematic for handler '${deviceInfo.determinedHandlerDisplayName}'. Connection ABORTED.")
                _connectionError.value = "No user selected. Connection to $deviceDisplayName not possible."
                _connectionStatus.value = ConnectionStatus.FAILED
                return@launch
            }

            if (!deviceInfo.isSupported) {
                LogManager.e(TAG, "Device $deviceDisplayName is NOT supported according to ScannedInfo. Connection ABORTED.")
                _connectionError.value = "$deviceDisplayName is not supported."
                _connectionStatus.value = ConnectionStatus.FAILED
                return@launch
            }

            // Release any existing communicator and its observation job if present.
            releaseActiveCommunicator(logPrefix = "Switching to new device: ")

            _connectionStatus.value = ConnectionStatus.CONNECTING
            _connectedDeviceAddress.value = deviceInfo.address
            _connectedDeviceName.value = deviceInfo.name
            _connectionError.value = null // Clear previous errors.

            activeCommunicator = scaleFactory.createCommunicator(deviceInfo)

            if (activeCommunicator == null) {
                LogManager.e(TAG, "ScaleFactory could NOT create a communicator for $deviceDisplayName. Connection ABORTED.")
                _connectionError.value = "Driver for $deviceDisplayName not found or internal error."
                _connectionStatus.value = ConnectionStatus.FAILED
                _connectedDeviceAddress.value = null
                _connectedDeviceName.value = null
                return@launch
            }

            LogManager.i(TAG, "ActiveCommunicator successfully created: ${activeCommunicator!!.javaClass.simpleName}. Starting observation job...")
            observeActiveCommunicatorEvents(deviceInfo)
            activeCommunicator?.connect(deviceInfo.address, getCurrentScaleUser())
        }
    }

    /**
     * Observes connection status and events from the [activeCommunicator].
     * This involves collecting from `isConnected` and `getEventsFlow()`.
     * This job is cancelled and restarted if a new device connection is initiated.
     *
     * @param connectedDeviceInfo Information about the device for which events are being observed.
     *                            Used for display names in logs and UI messages.
     */
    private fun observeActiveCommunicatorEvents(connectedDeviceInfo: ScannedDeviceInfo) {
        val deviceDisplayName = connectedDeviceInfo.name ?: connectedDeviceInfo.address
        communicatorJob?.cancel() // Ensure any previous observation job is stopped.
        communicatorJob = scope.launch {
            activeCommunicator?.let { comm ->
                // Observe the isConnected Flow from the Communicator.
                launch {
                    comm.isConnected.collect { isConnected ->
                        LogManager.d(TAG, "Adapter isConnected: $isConnected for $deviceDisplayName (Status: ${_connectionStatus.value})")
                        if (isConnected) {
                            // Only transition to CONNECTED if we were in the process of CONNECTING.
                            if (_connectionStatus.value == ConnectionStatus.CONNECTING) {
                                _connectionStatus.value = ConnectionStatus.CONNECTED
                                // Address and name should already be set when starting the connection,
                                // but confirm here for safety.
                                _connectedDeviceAddress.value = connectedDeviceInfo.address
                                _connectedDeviceName.value = connectedDeviceInfo.name
                                _connectionError.value = null // Clear any errors on successful connection.
                                LogManager.i(TAG, "Successfully connected to $deviceDisplayName via adapter's isConnected flow.")
                                disconnectTimeoutJob?.cancel() // Successfully connected, timeout no longer needed.
                            }
                        } else {
                            // If isConnected goes false and we were connected or connecting/disconnecting
                            // to this specific device.
                            if ((_connectionStatus.value == ConnectionStatus.CONNECTED ||
                                        _connectionStatus.value == ConnectionStatus.CONNECTING ||
                                        _connectionStatus.value == ConnectionStatus.DISCONNECTING) &&
                                _connectedDeviceAddress.value == connectedDeviceInfo.address
                            ) {
                                LogManager.i(TAG, "Adapter no longer reports connected for $deviceDisplayName. Current status: ${_connectionStatus.value}. Expecting Disconnected Event.")
                                // Do not immediately set to DISCONNECTED here. Wait for the Disconnected event,
                                // as it often provides more information (e.g., reason).
                                // If no event arrives, the timeout in disconnect() or another mechanism will handle it.
                            }
                        }
                    }
                }

                // Observe the Event Flow from the Communicator.
                launch {
                    comm.getEventsFlow().collect { event ->
                        handleBluetoothEvent(event, connectedDeviceInfo)
                    }
                }
            } ?: LogManager.w(TAG, "observeActiveCommunicatorEvents called with null activeCommunicator for $deviceDisplayName")
        }
    }

    /**
     * Handles [BluetoothEvent]s received from the [activeCommunicator].
     * Updates connection status, handles measurements, errors, and other device interactions.
     *
     * @param event The [BluetoothEvent] to handle.
     * @param deviceInfo Information about the device that emitted the event.
     */
    private suspend fun handleBluetoothEvent(event: BluetoothEvent, deviceInfo: ScannedDeviceInfo) {
        val deviceDisplayName = deviceInfo.name ?: deviceInfo.address // Fallback to address for display.
        LogManager.d(TAG, "BluetoothEvent received: $event for $deviceDisplayName")

        when (event) {
            is BluetoothEvent.Connected -> {
                LogManager.i(TAG, "Event: Connected to ${event.deviceName ?: deviceDisplayName} (${event.deviceAddress})")
                disconnectTimeoutJob?.cancel() // Successfully connected, timeout no longer needed.
                if (_connectionStatus.value != ConnectionStatus.CONNECTED) {
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    _connectedDeviceAddress.value = event.deviceAddress
                    _connectedDeviceName.value = event.deviceName ?: deviceInfo.name // Prefer event name.
                    _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_connected_to, messageFormatArgs = listOf(event.deviceName ?: deviceDisplayName)))
                    _connectionError.value = null
                }
            }
            is BluetoothEvent.Disconnected -> {
                LogManager.i(TAG, "Event: Disconnected from ${event.deviceAddress}. Reason: ${event.reason}")
                disconnectTimeoutJob?.cancel() // Disconnect event received, timeout no longer needed.
                // Only act if this disconnect event is for the currently tracked device or if we are in the process of disconnecting.
                if (_connectedDeviceAddress.value == event.deviceAddress || _connectionStatus.value == ConnectionStatus.DISCONNECTING) {
                    _connectionStatus.value = ConnectionStatus.DISCONNECTED
                    _connectedDeviceAddress.value = null
                    _connectedDeviceName.value = null
                    // Optionally: _connectionError.value = "Disconnected: ${event.reason}"
                    releaseActiveCommunicator(logPrefix = "Disconnected event: ")
                } else {
                    LogManager.w(TAG, "Disconnected event for unexpected address ${event.deviceAddress} or status ${_connectionStatus.value}")
                }
            }
            is BluetoothEvent.ConnectionFailed -> {
                LogManager.w(TAG, "Event: Connection failed for ${event.deviceAddress}. Reason: ${event.error}")
                disconnectTimeoutJob?.cancel() // Error, timeout no longer needed.
                // Check if this error is relevant to the current connection attempt.
                if (_connectedDeviceAddress.value == event.deviceAddress || _connectionStatus.value == ConnectionStatus.CONNECTING) {
                    _connectionStatus.value = ConnectionStatus.FAILED
                    _connectionError.value = "Connection to $deviceDisplayName failed: ${event.error}"
                    _connectedDeviceAddress.value = null
                    _connectedDeviceName.value = null
                    releaseActiveCommunicator(logPrefix = "ConnectionFailed event: ")
                } else {
                    LogManager.w(TAG, "ConnectionFailed event for unexpected address ${event.deviceAddress} or status ${_connectionStatus.value}")
                }
            }
            is BluetoothEvent.MeasurementReceived -> {
                LogManager.i(TAG, "Event: Measurement received from $deviceDisplayName: Weight ${event.measurement.weight}")
                saveMeasurementFromEvent(event.measurement, event.deviceAddress, deviceDisplayName)
            }
            is BluetoothEvent.DeviceMessage -> {
                LogManager.d(TAG, "Event: Message from $deviceDisplayName: ${event.message}")
                _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_device_message, messageFormatArgs = listOf(deviceDisplayName, event.message)))
            }
            is BluetoothEvent.Error -> {
                LogManager.e(TAG, "Event: Error from $deviceDisplayName: ${event.error}")
                _connectionError.value = "Error with $deviceDisplayName: ${event.error}"
                // Consider setting status to FAILED if it's a critical error
                // that impacts/loses the connection.
            }

            is BluetoothEvent.UserInteractionRequired -> {
                val actualDeviceIdentifier = event.deviceIdentifier
                LogManager.i(TAG, "Event: UserInteractionRequired (${event.interactionType}) for $actualDeviceIdentifier. Data: ${event.data}")
                _userInteractionRequiredEvent.value = event
            }
        }
    }

    /**
     * Forwards the user's feedback from an interaction to the active [ScaleCommunicator].
     *
     * @param interactionType The type of interaction this feedback corresponds to.
     * @param appUserId The ID of the current application user.
     * @param feedbackData Data provided by the user.
     * @param uiHandler A [android.os.Handler] required by the underlying communicator.
     */
    fun provideUserInteractionFeedback(
        interactionType: BluetoothEvent.UserInteractionType,
        appUserId: Int,
        feedbackData: Any,
        uiHandler: Handler
    ) {
        scope.launch {
            activeCommunicator?.let { comm ->
                LogManager.d(TAG, "Forwarding user interaction feedback to communicator: type=$interactionType, appUserId=$appUserId")
                comm.processUserInteractionFeedback(interactionType, appUserId, feedbackData, uiHandler)
            } ?: LogManager.w(TAG, "provideUserInteractionFeedback called but no active communicator.")
        }
    }

    /**
     * Saves a [com.health.openscale.core.bluetooth.data.ScaleMeasurement] received from a device to the database.
     * This involves creating a [com.health.openscale.core.data.Measurement] entity and associated [com.health.openscale.core.data.MeasurementValue]s.
     *
     * @param measurementData The raw measurement data from the scale.
     * @param deviceAddress The address of the device that sent the measurement.
     * @param deviceName The name of the device.
     */
    private suspend fun saveMeasurementFromEvent(measurementData: ScaleMeasurement, deviceAddress: String, deviceName: String) {
        val currentAppUserId = getCurrentScaleUser()?.id
        if (currentAppUserId == 0) {
            LogManager.e(TAG, "($deviceName): No App User ID to save measurement.")
            _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_measurement_user_missing, messageFormatArgs = listOf(deviceName)))
            return
        }
        LogManager.i(TAG, "($deviceName): Saving measurement for App User ID $currentAppUserId.")

        // This logic is largely identical to what might be in a ViewModel and could
        // potentially be moved entirely into a dedicated MeasurementRepository or similar service.
        scope.launch(Dispatchers.IO) { // Perform database operations on IO dispatcher.
            val newDbMeasurement = Measurement(
                userId = currentAppUserId ?: 0,
                timestamp = measurementData.dateTime?.time ?: System.currentTimeMillis()
            )

            // Fetch measurement type IDs from the database to map keys to foreign keys.
            val typeKeyToIdMap: Map<MeasurementTypeKey, Int> =
                databaseRepository.getAllMeasurementTypes().firstOrNull()
                    ?.associate { it.key to it.id } ?: run {
                    LogManager.e(TAG, "Could not load MeasurementTypes from DB for $deviceName.")
                    _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_measurement_types_not_loaded))
                    return@launch
                }
            fun getTypeIdFromMap(key: MeasurementTypeKey): Int? = typeKeyToIdMap[key]

            val values = mutableListOf<MeasurementValue>()
            measurementData.weight.takeIf { it.isFinite() && it > 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.WEIGHT)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            measurementData.fat.takeIf { it.isFinite() && it > 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.BODY_FAT)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            measurementData.water.takeIf { it.isFinite() && it > 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.WATER)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            measurementData.muscle.takeIf { it.isFinite() && it > 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.MUSCLE)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            measurementData.visceralFat.takeIf { it.isFinite() && it >= 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.VISCERAL_FAT)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            measurementData.bone.takeIf { it.isFinite() && it > 0.0f }?.let {
                getTypeIdFromMap(MeasurementTypeKey.BONE)?.let { typeId ->
                    values.add(
                        MeasurementValue(
                            measurementId = 0,
                            typeId = typeId,
                            floatValue = it
                        )
                    )
                }
            }
            // Add other values here (BMI, BMR etc. if available from ScaleMeasurement)

            if (values.isEmpty()) {
                LogManager.w(TAG, "No valid values from measurement of $deviceName to save.")
                _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_measurement_no_values, messageFormatArgs = listOf(deviceName)))
                return@launch
            }

            try {
                val measurementId = databaseRepository.insertMeasurement(newDbMeasurement)
                val finalValues = values.map { it.copy(measurementId = measurementId.toInt()) }
                finalValues.forEach { databaseRepository.insertMeasurementValue(it) }

                LogManager.i(TAG, "Measurement from $deviceName for User $currentAppUserId saved (ID: $measurementId). Values: ${finalValues.size}")
                pendingSavedCount.incrementAndGet()
                lastSavedArgs = listOf(measurementData.weight, deviceName)
                savedBurstSignal.tryEmit(Unit)
            } catch (e: Exception) {
                LogManager.e(TAG, "Error saving measurement from $deviceName.", e)
                _snackbarEvents.tryEmit(SnackbarEvent(messageResId = R.string.bluetooth_connector_measurement_save_error, messageFormatArgs = listOf(deviceName)))
            }
        }
    }

    /**
     * Disconnects from the currently connected device, if any.
     * This method initiates the disconnection process and starts a timeout
     * to forcefully update the status if the communicator doesn't report disconnection promptly.
     */
    fun disconnect() {
        val deviceDisplayName = _connectedDeviceName.value ?: _connectedDeviceAddress.value ?: "current device"
        LogManager.i(TAG, "disconnect() called for $deviceDisplayName. Active communicator: ${activeCommunicator != null}, Status: ${_connectionStatus.value}")

        if (activeCommunicator == null && _connectionStatus.value != ConnectionStatus.CONNECTED && _connectionStatus.value != ConnectionStatus.CONNECTING) {
            LogManager.w(TAG, "No active communicator or active connection to disconnect.")
            // Ensure status consistency if no active connection exists.
            if (_connectionStatus.value != ConnectionStatus.DISCONNECTED && _connectionStatus.value != ConnectionStatus.FAILED) {
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _connectedDeviceAddress.value = null
                _connectedDeviceName.value = null
            }
            return
        }

        if (_connectionStatus.value != ConnectionStatus.DISCONNECTING && _connectionStatus.value != ConnectionStatus.DISCONNECTED) {
            _connectionStatus.value = ConnectionStatus.DISCONNECTING
        }

        activeCommunicator?.disconnect() // Request the communicator to disconnect.

        // Fallback timeout in case no Disconnected event is received from the communicator.
        disconnectTimeoutJob?.cancel()
        disconnectTimeoutJob = scope.launch {
            delay(DISCONNECT_TIMEOUT_MS)
            if (_connectionStatus.value == ConnectionStatus.DISCONNECTING) {
                LogManager.w(TAG, "Disconnect timeout for $deviceDisplayName. Forcing status to DISCONNECTED.")
                _connectionStatus.value = ConnectionStatus.DISCONNECTED
                _connectedDeviceAddress.value = null
                _connectedDeviceName.value = null
                releaseActiveCommunicator(logPrefix = "Disconnect timeout: ")
            }
        }
    }

    /**
     * Releases the [activeCommunicator] and cancels associated jobs.
     * This includes cancelling the communicator's event observation job and any disconnect timeout.
     * If the communicator implements [AutoCloseable], its `close()` method is called.
     *
     * @param logPrefix A prefix string for log messages, useful for context.
     */
    private fun releaseActiveCommunicator(logPrefix: String = "") {
        LogManager.d(TAG, "${logPrefix}Releasing active communicator: ${activeCommunicator?.javaClass?.simpleName}")
        communicatorJob?.cancel() // Important: Stop the job observing events.
        communicatorJob = null
        disconnectTimeoutJob?.cancel() // Also stop the timeout job for disconnects.
        disconnectTimeoutJob = null

        try {
            (activeCommunicator as? AutoCloseable)?.close() // If the communicator is AutoCloseable.
        } catch (e: Exception) {
            LogManager.e(TAG, "${logPrefix}Error closing activeCommunicator: ${e.message}", e)
        }
        activeCommunicator = null
        LogManager.d(TAG, "${logPrefix}Active communicator released and set to null.")
    }

    /**
     * Sets an external connection error message. This can be used by the hosting ViewModel
     * to report errors that occur outside the manager's direct connection logic (e.g., permission issues).
     *
     * @param errorMessage The error message to display. If null, the error is cleared (see [clearConnectionError]).
     */
    fun setExternalConnectionError(errorMessage: String?) {
        LogManager.w(TAG, "External connection error set: $errorMessage")
        _connectionError.value = errorMessage
        if (errorMessage != null) {
            // When an error is set, typically the connection status should reflect failure.
            // However, be mindful if this is called before any connection attempt has even started.
            // If no connection attempt was active, setting to FAILED might be immediate.
            // If a connection was in progress and this is an additional error, it might already be FAILED.
            if (_connectionStatus.value != ConnectionStatus.CONNECTING &&
                _connectionStatus.value != ConnectionStatus.CONNECTED &&
                _connectionStatus.value != ConnectionStatus.DISCONNECTING
            ) {
                _connectionStatus.value = ConnectionStatus.FAILED
            }
        }
    }

    /**
     * Clears any existing connection error message.
     */
    fun clearConnectionError() {
        if (_connectionError.value != null) {
            _connectionError.value = null
        }
    }

    fun clearUserInteractionEvent() {
        if (_userInteractionRequiredEvent.value != null) {
            _userInteractionRequiredEvent.value = null
        }
    }

    /**
     * Cleans up resources when the BluetoothConnectionManager is no longer needed.
     * This typically involves disconnecting any active connection and releasing the communicator.
     * It's important to call this (e.g., from ViewModel's `onCleared()`) to prevent resource leaks.
     */
    override fun close() {
        LogManager.i(TAG, "Closing BluetoothConnectionManager.")
        // Ensure to disconnect if there's an active connection or attempt.
        if (_connectionStatus.value == ConnectionStatus.CONNECTED || _connectionStatus.value == ConnectionStatus.CONNECTING) {
            disconnect() // Calls the disconnect logic, which also releases the communicator.
        } else {
            // If not connected/connecting, still ensure everything is clean.
            releaseActiveCommunicator(logPrefix = "Closing manager: ")
        }
        LogManager.i(TAG, "BluetoothConnectionManager closed.")
    }
}