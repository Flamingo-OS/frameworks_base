/*
 * Copyright (C) 2022 FlamingoOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.policy

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.os.UserHandle
import android.util.Slog
import android.view.KeyEvent

import com.android.internal.annotations.GuardedBy
import com.android.internal.os.IDeviceKeyManager
import com.android.internal.os.IKeyHandler
import com.android.server.LocalServices
import com.android.server.SystemService

import java.util.UUID

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val BINDER_SERVICE_NAME = "device_key_manager"
private val TAG = DeviceKeyManager::class.simpleName

/**
 * System service that provides an interface for device specific
 * [KeyEvent]'s to be handled by device specific system application
 * components. Binding with this class requires for the component to
 * extend a Service and register key handler with [IDeviceKeyManager],
 * providing the scan codes it is expecting to handle, and the event actions.
 * Device id maybe optional, pass -1 to handle [KeyEvent]s from any device.
 * Unregister when lifecycle of the Service ends with the same token used for
 * registering.
 *
 * @hide
 */
class DeviceKeyManager(context: Context) : SystemService(context) {

    private val mutex = Mutex()

    @GuardedBy("mutex")
    private val keyHandlerDataMap = mutableMapOf<IBinder, KeyHandlerData>()

    private val service = object : IDeviceKeyManager.Stub() {

        override fun registerKeyHandler(
            token: IBinder,
            keyHandler: IKeyHandler,
            scanCodes: IntArray,
            actions: IntArray,
            deviceId: Int
        ) {
            enforceCallerIsSystem()
            val id = UUID.randomUUID()
            Slog.i(TAG, "Registering new key handler - $id")
            coroutineScope.launch {
                mutex.withLock {
                    try {
                        token.linkToDeath({ unregisterKeyHandler(token) }, 0)
                    } catch (e: RemoteException) {
                        Slog.e(TAG, "Client $id died before created.")
                    }
                    keyHandlerDataMap[token] = KeyHandlerData(
                        id,
                        keyHandler,
                        scanCodes.toSet(),
                        actions.toSet(),
                        deviceId
                    )
                }
            }
        }

        override fun unregisterKeyHandler(token: IBinder) {
            enforceCallerIsSystem()
            coroutineScope.launch {
                mutex.withLock {
                    keyHandlerDataMap.remove(token)?.let {
                        Slog.i(TAG, "Unregistered key handler - ${it.id}")
                    } ?: Slog.e(TAG, "No KeyHandler was registered for the given $token")
                }
            }
        }
    }

    private val localService = object : DeviceKeyManagerInternal {

        override fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
            val keyHandlerDataMapToCall = runBlocking {
                mutex.withLock {
                    keyHandlerDataMap.values.filter {
                        it.canHandleKeyEvent(keyEvent)
                    }
                }
            }
            coroutineScope.launch {
                mutex.withLock {
                    keyHandlerDataMapToCall.forEach { keyHandlerData ->
                        try {
                            keyHandlerData.handleKeyEvent(keyEvent)
                        } catch(e: RemoteException) {
                            Slog.e(TAG, "Failed to deliver KeyEvent to KeyHandler ${keyHandlerData.id}", e)
                        }
                    }
                }
            }
            return keyHandlerDataMapToCall.size > 0;
        }
    }

    private lateinit var coroutineScope: CoroutineScope

    override fun onStart() {
        coroutineScope = CoroutineScope(Dispatchers.Default)
        publishBinderService(BINDER_SERVICE_NAME, service)
        LocalServices.addService(DeviceKeyManagerInternal::class.java, localService)
    }

    private fun enforceCallerIsSystem() {
        val callingUid = Binder.getCallingUid()
        val isSystem = UserHandle.isSameApp(callingUid, Process.SYSTEM_UID)
        if (!isSystem) {
            throw SecurityException("Caller $callingUid does not belong to system uid")
        }
    }
}

/**
 * @hide
 */
interface DeviceKeyManagerInternal {
    fun handleKeyEvent(keyEvent: KeyEvent): Boolean
}

private data class KeyHandlerData(
    val id: UUID,
    private val keyHandler: IKeyHandler,
    private val scanCodes: Set<Int>,
    private val actions: Set<Int>,
    private val deviceId: Int
) {
    fun canHandleKeyEvent(event: KeyEvent): Boolean {
        if (deviceId != -1 && deviceId != event.deviceId) {
            return false
        }
        if (actions.isNotEmpty() && !actions.contains(event.action)) {
            return false
        }
        if (scanCodes.isNotEmpty() && !scanCodes.contains(event.scanCode)) {
            return false
        }
        return true
    }

    fun handleKeyEvent(keyEvent: KeyEvent) {
        keyHandler.handleKeyEvent(keyEvent)
    }
}
