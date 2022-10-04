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
import android.os.DeadObjectException
import android.os.RemoteException
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
 * Unregister when lifecycle of Service ends.
 *
 * @hide
 */
class DeviceKeyManager(context: Context) : SystemService(context) {

    private val mutex = Mutex()

    @GuardedBy("mutex")
    private val keyHandlerDataList = mutableListOf<KeyHandlerData>()

    private val service = object : IDeviceKeyManager.Stub() {

        override fun registerKeyHandler(
            keyHandler: IKeyHandler,
            scanCodes: IntArray,
            actions: IntArray
        ) {
            val id = UUID.randomUUID()
            Slog.i(TAG, "Registering new key handler, id = $id")
            coroutineScope.launch {
                mutex.withLock {
                    keyHandlerDataList.add(
                        KeyHandlerData(
                            id,
                            keyHandler,
                            scanCodes.toSet(),
                            actions.toSet()
                        )
                    )
                }
            }
        }

        override fun unregisterKeyHandler(keyHandler: IKeyHandler) {
            coroutineScope.launch {
                mutex.withLock {
                    keyHandlerDataList.removeIf { it.keyHandler == keyHandler }
                }
            }
        }
    }

    private val localService = object : DeviceKeyManagerInternal {

        override fun handleKeyEvent(keyEvent: KeyEvent): Boolean {
            val keyHandlerDataListToCall = runBlocking {
                mutex.withLock {
                    keyHandlerDataList.filter {
                        it.canHandleAction(keyEvent.action) &&
                            it.canHandleScanCode(keyEvent.scanCode)
                    }
                }
            }
            coroutineScope.launch {
                mutex.withLock {
                    keyHandlerDataListToCall.forEach { keyHandlerData ->
                        try {
                            keyHandlerData.handleKeyEvent(keyEvent)
                        } catch(e: DeadObjectException) {
                            Slog.e(TAG, "Keyhandler with id ${keyHandlerData.id} has died, removing", e)
                            keyHandlerDataList.removeIf { it.keyHandler == keyHandlerData.keyHandler }
                        } catch(e: RemoteException) {
                            Slog.e(TAG, "Failed to deliver key event $keyEvent to keyhandler with id ${keyHandlerData.id}", e)
                        }
                    }
                }
            }
            return keyHandlerDataListToCall.size > 0;
        }
    }

    private lateinit var coroutineScope: CoroutineScope

    override fun onStart() {
        coroutineScope = CoroutineScope(Dispatchers.Default)
        publishBinderService(BINDER_SERVICE_NAME, service)
        LocalServices.addService(DeviceKeyManagerInternal::class.java, localService)
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
    val keyHandler: IKeyHandler,
    private val scanCodes: Set<Int>,
    private val actions: Set<Int>
) {
    fun canHandleScanCode(scanCode: Int) = scanCodes.contains(scanCode)

    fun canHandleAction(action: Int) = actions.contains(action)

    fun handleKeyEvent(keyEvent: KeyEvent) {
        keyHandler.handleKeyEvent(keyEvent)
    }
}
