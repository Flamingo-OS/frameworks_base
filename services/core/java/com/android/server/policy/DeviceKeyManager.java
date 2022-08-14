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

package com.android.server.policy;

import android.annotation.NonNull;
import android.content.Context;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.util.Slog;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IDeviceKeyManager;
import com.android.internal.os.IKeyHandler;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

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
public final class DeviceKeyManager extends SystemService implements Handler.Callback {

    private static final String BINDER_SERVICE_NAME = "device_key_manager";
    private static final String TAG = DeviceKeyManager.class.getSimpleName();

    private static final int MSG_REGISTER = 1;
    private static final int MSG_UNREGISTER = 2;
    private static final int MSG_DELIVER_KEYEVENT = 3;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final List<KeyHandlerInfo> mKeyHandlers = new ArrayList<KeyHandlerInfo>();

    private Handler mHandler = null;

    private final IBinder mService = new IDeviceKeyManager.Stub() {

        @Override
        public void registerKeyHandler(IKeyHandler keyHandler, int[] scanCodes, int[] actions) {
            final KeyHandlerInfo info = new KeyHandlerInfo(keyHandler, scanCodes, actions);
            final Message msg = mHandler.obtainMessage(MSG_REGISTER, info);
            mHandler.sendMessage(msg);
        }

        @Override
        public void unregisterKeyHandler(IKeyHandler keyHandler) {
            final Message msg = mHandler.obtainMessage(MSG_UNREGISTER, keyHandler);
            mHandler.sendMessage(msg);
        }
    };

    private final DeviceKeyManagerInternal mLocalService = new DeviceKeyManagerInternal() {

        @Override
        public boolean handleKeyEvent(KeyEvent keyEvent) {
            final List<KeyEventHandler> keyHandlersToCall;
            synchronized (mLock) {
                keyHandlersToCall = mKeyHandlers.stream()
                    .filter(info -> {
                        return info.canHandleScanCode(keyEvent.getScanCode());
                    })
                    .filter(info -> {
                        return info.canHandleAction(keyEvent.getAction());
                    })
                    .map(info -> {
                        return new KeyEventHandler(info.getKeyHandler(), keyEvent);
                    })
                    .collect(Collectors.toList());
            }
            final Message msg = mHandler.obtainMessage(MSG_DELIVER_KEYEVENT, keyHandlersToCall);
            mHandler.sendMessage(msg);
            return keyHandlersToCall.size() > 0;
        }
    };

    public DeviceKeyManager(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        final HandlerThread serviceThread = new HandlerThread(TAG);
        serviceThread.start();
        mHandler = new Handler(serviceThread.getLooper(), this);
        publishBinderService(BINDER_SERVICE_NAME, mService);
        LocalServices.addService(DeviceKeyManagerInternal.class, mLocalService);
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        switch (msg.what) {
            case MSG_REGISTER: {
                final KeyHandlerInfo info = (KeyHandlerInfo) msg.obj;
                synchronized (mLock) {
                    mKeyHandlers.add(info);
                }
                return true;
            }
            case MSG_UNREGISTER: {
                final IKeyHandler keyHandler = (IKeyHandler) msg.obj;
                synchronized (mLock) {
                    int size = mKeyHandlers.size();
                    for (int i = 0; i < size;) {
                        final KeyHandlerInfo info = mKeyHandlers.get(i);
                        if (info.isSameKeyHandler(keyHandler)) {
                            mKeyHandlers.remove(i);
                            size--;
                        } else {
                            i++;
                        }
                    }
                }
                return true;
            }
            case MSG_DELIVER_KEYEVENT: {
                final List<KeyEventHandler> handlers = (List<KeyEventHandler>) msg.obj;
                handlers.forEach(handler -> {
                    handler.deliverKeyEvent();
                });
                return true;
            }
            default:
                return false;
        }
    }
}
