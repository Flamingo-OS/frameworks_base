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
import android.os.RemoteException;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.os.IKeyHandler;
import com.android.internal.util.Preconditions;

/**
 * @hide
 */
final class KeyEventHandler {

    private static final String TAG = KeyEventHandler.class.getSimpleName();

    private final IKeyHandler mKeyHandler;
    private final KeyEvent mKeyEvent;

    KeyEventHandler(@NonNull IKeyHandler keyHandler, @NonNull KeyEvent keyEvent) {
        Preconditions.checkNotNull(keyHandler, "Keyhandler cannot be null");
        Preconditions.checkNotNull(keyEvent, "KeyEvent cannot be null");
        mKeyHandler = keyHandler;
        mKeyEvent = keyEvent;
    }

    void deliverKeyEvent() {
        try {
            mKeyHandler.handleKeyEvent(mKeyEvent);
        } catch(RemoteException ex) {
            Log.e(TAG, "Failed to deliver key event to " + mKeyHandler, ex);
        }
    }
}