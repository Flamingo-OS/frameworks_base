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

import com.android.internal.os.IKeyHandler;
import com.android.internal.util.Preconditions;

import java.util.Arrays;
import java.util.List;

/**
 * @hide
 */
final class KeyHandlerInfo {

    private final IKeyHandler mKeyHandler;
    private final int[] mScanCodes;
    private final int[] mActions;

    KeyHandlerInfo(@NonNull IKeyHandler keyHandler, int[] scanCodes, int[] actions) {
        Preconditions.checkNotNull(keyHandler, "Keyhandler cannot be null");
        mKeyHandler = keyHandler;
        mScanCodes = Arrays.copyOf(scanCodes, scanCodes.length);
        Arrays.sort(mScanCodes);
        mActions = Arrays.copyOf(actions, actions.length);
        Arrays.sort(mActions);
    }

    @NonNull
    IKeyHandler getKeyHandler() {
        return mKeyHandler;
    }

    boolean isSameKeyHandler(IKeyHandler keyHandler) {
        return mKeyHandler.equals(keyHandler);
    }

    boolean canHandleScanCode(int scanCode) {
        return Arrays.binarySearch(mScanCodes, scanCode) >= 0;
    }

    boolean canHandleAction(int action) {
        return Arrays.binarySearch(mActions, action) >= 0;
    }
}