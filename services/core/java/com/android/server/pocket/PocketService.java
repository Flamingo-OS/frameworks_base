/**
 * Copyright (C) 2016 The ParanoidAndroid Project
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

package com.android.server.pocket;

import static android.provider.Settings.System.POCKET_JUDGE;

import android.Manifest;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.pocket.IPocketService;
import android.pocket.IPocketCallback;
import android.pocket.PocketManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.FastPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.statusbar.StatusBarManagerInternal;

import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A service to manage multiple clients that want to listen for pocket state.
 * The service is responsible for maintaining a list of clients and dispatching all
 * pocket -related information.
 *
 * @author Carlo Savignano
 * @hide
 */
public final class PocketService extends SystemService {

    private static final String TAG = PocketService.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Whether to use proximity sensor to evaluate pocket state.
     */
    private static final boolean ENABLE_PROXIMITY_JUDGE = true;

    /**
     * Whether to use light sensor to evaluate pocket state.
     */
    private static final boolean ENABLE_LIGHT_JUDGE = true;

    /**
     * Whether we don't have yet a valid vendor sensor event or pocket service not running.
     */
    private static final int VENDOR_SENSOR_UNKNOWN = 0;

    /**
     * Vendor sensor has been registered, onSensorChanged() has been called and we have a
     * valid event value from Vendor pocket sensor.
     */
    private static final int VENDOR_SENSOR_IN_POCKET = 1;

    /**
     * The rate proximity sensor events are delivered at.
     */
    private static final int PROXIMITY_SENSOR_DELAY = 400000;

    /**
     * Whether we don't have yet a valid proximity sensor event or pocket service not running.
     */
    private static final int PROXIMITY_UNKNOWN = 0;

    /**
     * Proximity sensor has been registered, onSensorChanged() has been called and we have a
     * valid event value which determined proximity sensor is covered.
     */
    private static final int PROXIMITY_POSITIVE = 1;

    /**
     * Proximity sensor has been registered, onSensorChanged() has been called and we have a
     * valid event value which determined proximity sensor is not covered.
     */
    private static final int PROXIMITY_NEGATIVE = 2;

    /**
     * The rate light sensor events are delivered at.
     */
    private static final int LIGHT_SENSOR_DELAY = 400000;

    /**
     * Whether we don't have yet a valid light sensor event or pocket service not running.
     */
    private static final int LIGHT_UNKNOWN = 0;

    /**
     * Light sensor has been registered, onSensorChanged() has been called and we have a
     * valid event value which determined available light is in pocket range.
     */
    private static final int LIGHT_POCKET = 1;

    /**
     * Light sensor has been registered, onSensorChanged() has been called and we have a
     * valid event value which determined available light is outside pocket range.
     */
    private static final int LIGHT_AMBIENT = 2;

    /**
     * Light sensor maximum value registered in pocket with up to semi-transparent fabric.
     */
    private static final float POCKET_LIGHT_MAX_THRESHOLD = 3.0f;

    private static final int POCKET_LOCK_TIMEOUT_DURATION = 3 * 1000;

    private static final int MSG_SYSTEM_BOOTED = 1;
    private static final int MSG_DISPATCH_CALLBACKS = 2;
    private static final int MSG_ADD_CALLBACK = 3;
    private static final int MSG_REMOVE_CALLBACK = 4;
    private static final int MSG_INTERACTIVE_CHANGED = 5;
    private static final int MSG_SENSOR_EVENT_PROXIMITY = 6;
    private static final int MSG_SENSOR_EVENT_LIGHT = 7;
    private static final int MSG_UNREGISTER_TIMEOUT = 8;
    private static final int MSG_SET_LISTEN_EXTERNAL = 9;
    private static final int MSG_SENSOR_EVENT_VENDOR = 10;

    private final Context mContext;
    private final PocketHandler mHandler;
    private final PocketObserver mObserver;
    private final SensorManager mSensorManager;
    private final PowerManager mPowerManager;

    @GuardedBy("mCallbacks")
    private final ArrayList<IPocketCallback> mCallbacks = new ArrayList<>();

    private StatusBarManagerInternal mStatusBarManagerInternal;

    private final Runnable mPocketLockTimeout;
    private boolean mPocketViewTimerActive;
    private boolean mIsPocketLockShowing;

    private boolean mEnabled;
    private boolean mSystemBooted;
    private boolean mInteractive;
    private boolean mPending;

    @Nullable
    private SensorData mProximitySensor, mLightSensor, mVendorSensor;
    @Nullable
    private PrintWriter mPocketBridgeWriter;

    public PocketService(final Context context) {
        super(context);
        mContext = context;

        final HandlerThread handlerThread = new HandlerThread(TAG, Process.THREAD_PRIORITY_BACKGROUND);
        handlerThread.start();
        mHandler = new PocketHandler(handlerThread.getLooper());

        mSensorManager = mContext.getSystemService(SensorManager.class);
        initSensors();

        mPowerManager = context.getSystemService(PowerManager.class);
        mPocketLockTimeout = () -> {
            mPowerManager.goToSleep(SystemClock.uptimeMillis());
            mPocketViewTimerActive = false;
        };

        mObserver = new PocketObserver(mHandler);
        mObserver.onChange(true);
        mObserver.register();

        initPocketBridge();
    }

    private final class PocketObserver extends ContentObserver {

        private boolean mRegistered;

        PocketObserver(final Handler handler) {
            super(handler);
        }

        @Override
        public void onChange(final boolean selfChange) {
            final boolean enabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), POCKET_JUDGE,
                0 /* default */, UserHandle.USER_CURRENT) != 0;
            setEnabled(enabled);
        }

        void register() {
            if (!mRegistered) {
                mContext.getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(POCKET_JUDGE), true, this);
                mRegistered = true;
            }
        }

        void unregister() {
            if (mRegistered) {
                mContext.getContentResolver().unregisterContentObserver(this);
                mRegistered = false;
            }
        }
    }

    private final class PocketHandler extends Handler {

        public PocketHandler(final Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(final Message msg) {
            switch (msg.what) {
                case MSG_SYSTEM_BOOTED:
                    handleSystemBooted();
                    break;
                case MSG_DISPATCH_CALLBACKS:
                    handleDispatchCallbacks();
                    break;
                case MSG_ADD_CALLBACK:
                    handleAddCallback((IPocketCallback) msg.obj);
                    break;
                case MSG_REMOVE_CALLBACK:
                    handleRemoveCallback((IPocketCallback) msg.obj);
                    break;
                case MSG_INTERACTIVE_CHANGED:
                    handleInteractiveChanged(msg.arg1 != 0);
                    break;
                case MSG_SENSOR_EVENT_PROXIMITY:
                    handleProximitySensorEvent((SensorEvent) msg.obj);
                    break;
                case MSG_SENSOR_EVENT_LIGHT:
                    handleLightSensorEvent((SensorEvent) msg.obj);
                    break;
                case MSG_SENSOR_EVENT_VENDOR:
                    handleVendorSensorEvent((SensorEvent) msg.obj);
                    break;
                case MSG_UNREGISTER_TIMEOUT:
                    handleUnregisterTimeout();
                    break;
                case MSG_SET_LISTEN_EXTERNAL:
                    handleSetListeningExternal(msg.arg1 != 0);
                    break;
                default:
                    Slog.w(TAG, "Unknown message: " + msg.what);
            }
        }
    }

    private final class SensorData {

        @Nullable
        private final Sensor mSensor;
        private final SensorEventListener mListener;
        private final int mSamplingPeriod;
        private int mInitalState;
        private boolean mIsRegistered;

        final float maxRange;
        int state, lastState;

        SensorData(
            @Nullable final Sensor sensor,
            final int initialState,
            final int messageCode,
            final int samplingPeriod
        ) {
            mSensor = sensor;
            mSamplingPeriod = samplingPeriod;
            state = lastState = initialState;
            if (mSensor != null) {
                maxRange = mSensor.getMaximumRange();
            } else {
                maxRange = -1f;
            }
            mListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(final SensorEvent sensorEvent) {
                    final Message msg = mHandler.obtainMessage(messageCode, sensorEvent);
                    mHandler.sendMessage(msg);
                }

                @Override
                public void onAccuracyChanged(final Sensor sensor, final int i) { }
            };
        }

        void register() {
            if (mIsRegistered) {
                return;
            }
            mSensorManager.registerListener(
                mListener, mSensor,
                mSamplingPeriod, mHandler);
        }

        void unregister() {
            if (!mIsRegistered) {
                return;
            }
            mSensorManager.unregisterListener(mListener);
            state = lastState = mInitalState;
        }

        JSONObject dump() throws JSONException {
            final JSONObject dump = new JSONObject();
            dump.put("state", state);
            dump.put("lastState", lastState);
            dump.put("isRegistered", mIsRegistered);
            dump.put("maxRange", maxRange);
            return dump;
        }
    };

    @Override
    public void onBootPhase(final int phase) {
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            mStatusBarManagerInternal =
                LocalServices.getService(StatusBarManagerInternal.class);
        } if (phase == PHASE_BOOT_COMPLETED) {
            mHandler.sendEmptyMessage(MSG_SYSTEM_BOOTED);
        }
    }

    @Override
    public void onStart() {
        publishBinderService(Context.POCKET_SERVICE, new PocketServiceWrapper());
        LocalServices.addService(PocketServiceInternal.class, new LocalService());
    }

    private final class PocketServiceWrapper extends IPocketService.Stub {

        @Override // Binder call
        public void addCallback(final IPocketCallback callback) {
            final Message msg = mHandler.obtainMessage(MSG_ADD_CALLBACK, callback);
            mHandler.sendMessage(msg);
        }

        @Override // Binder call
        public void removeCallback(final IPocketCallback callback) {
            final Message msg = mHandler.obtainMessage(MSG_REMOVE_CALLBACK, callback);
            mHandler.sendMessage(msg);
        }

        @Override // Binder call
        public void setListeningExternal(final boolean listen) {
            final Message msg = mHandler.obtainMessage(
                MSG_SET_LISTEN_EXTERNAL, listen ? 1 : 0, -1 /** arg2 */);
            mHandler.sendMessage(msg);
        }

        @Override // Binder call
        public boolean isDeviceInPocket() {
            if (!mSystemBooted) {
                return false;
            }
            return PocketService.this.isDeviceInPocket();
        }

        @Override // Binder call
        protected void dump(final FileDescriptor fd, final PrintWriter pw, final String[] args) {
            if (mContext.checkCallingOrSelfPermission(Manifest.permission.DUMP)
                    != PackageManager.PERMISSION_GRANTED) {
                pw.println("Permission Denial: can't dump Pocket from from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid());
                return;
            }

            final long ident = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }
    }

    private final class LocalService implements PocketServiceInternal {

        /**
         * Notify service about device interactive state changed.
         * {@link PhoneWindowManager#startedWakingUp()}
         * {@link PhoneWindowManager#startedGoingToSleep(int)}
         */
        @Override
        public void onInteractiveChanged(final boolean interactive) {
            final Message msg = mHandler.obtainMessage(
                MSG_INTERACTIVE_CHANGED, interactive ? 1 : 0, -1 /** arg2 */);
            mHandler.sendMessage(msg);
        }

        @Override
        public void stopListening() {
            final Message msg = mHandler.obtainMessage(
                MSG_SET_LISTEN_EXTERNAL, 0, -1 /** arg2 */);
            mHandler.sendMessage(msg);
        }

        @Override
        public boolean isPocketLockShowing() {
            return PocketService.this.mIsPocketLockShowing;
        }

        @Override
        public boolean isDeviceInPocket() {
            return PocketService.this.isDeviceInPocket();
        }
    }

    private void initSensors() {
        final Sensor proximitySensor = mSensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
        if (proximitySensor == null) {
            Slog.i(TAG, "Cannot detect proximity sensor");
        } else {
            mProximitySensor = new SensorData(
                proximitySensor,
                PROXIMITY_UNKNOWN,
                MSG_SENSOR_EVENT_PROXIMITY,
                PROXIMITY_SENSOR_DELAY
            );
        }

        final Sensor lightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            Slog.i(TAG, "Cannot detect light sensor");
        } else {
            mLightSensor = new SensorData(
                lightSensor,
                LIGHT_UNKNOWN,
                MSG_SENSOR_EVENT_LIGHT,
                LIGHT_SENSOR_DELAY
            );
        }

        final String vendorPocketSensor = mContext.getString(R.string.config_pocketJudgeVendorSensorName);
        if (DEBUG) {
            Log.d(TAG, "VENDOR_SENSOR: " +  vendorPocketSensor);
        }
        final Sensor vendorSensor = mSensorManager.getSensorList(Sensor.TYPE_ALL)
            .stream()
            .filter(sensor -> sensor.getStringType().equals(vendorPocketSensor))
            .findFirst()
            .orElse(null);
        if (vendorSensor == null) {
            Slog.i(TAG, "Cannot detect vendor sensor " + vendorPocketSensor);
        } else {
            mVendorSensor = new SensorData(
                vendorSensor,
                VENDOR_SENSOR_UNKNOWN,
                MSG_SENSOR_EVENT_VENDOR,
                SensorManager.SENSOR_DELAY_NORMAL
            );
        }
    }

    private void initPocketBridge() {
        final String sysfsNode = mContext.getString(R.string.config_pocketBridgeSysfsInpocket);
        if (sysfsNode.isEmpty()) {
            return;
        }
        try {
            final FileOutputStream os = new FileOutputStream(sysfsNode);
            mPocketBridgeWriter = new FastPrintWriter(os, true /* autoFlush */, 128 /* bufferLen */);
        } catch(FileNotFoundException e) {
            Slog.e(TAG, "Pocket bridge sysfs node not found", e);
            return;
        }
    }

    private boolean isDeviceInPocket() {
        if (mVendorSensor != null && mVendorSensor.state != VENDOR_SENSOR_UNKNOWN) {
            return mVendorSensor.state == VENDOR_SENSOR_IN_POCKET;
        }

        if (mLightSensor != null && mLightSensor.state != LIGHT_UNKNOWN) {
            return mProximitySensor != null &&
                mProximitySensor.state == PROXIMITY_POSITIVE &&
                mLightSensor.state == LIGHT_POCKET;
        }
        return mProximitySensor != null &&
            mProximitySensor.state == PROXIMITY_POSITIVE;
    }

    private void setEnabled(final boolean enabled) {
        if (mEnabled = enabled) {
            return;
        }
        mEnabled = enabled;
        mHandler.removeCallbacksAndMessages(null);
        update();
    }

    private void update() {
        if (!mEnabled || mInteractive) {
            if (mEnabled && isDeviceInPocket()) {
                // if device is judged to be in pocket while switching
                // to interactive state, we need to keep monitoring.
                return;
            }
            unregisterSensorListeners();
        } else {
            mHandler.removeMessages(MSG_UNREGISTER_TIMEOUT);
            registerSensorListeners();
        }
    }

    private void registerSensorListeners() {
        startListeningForVendorSensor();
        startListeningForProximity();
        startListeningForLight();
    }

    private void unregisterSensorListeners() {
        stopListeningForVendorSensor();
        stopListeningForProximity();
        stopListeningForLight();
    }

    private void startListeningForVendorSensor() {
        if (DEBUG) {
            Log.d(TAG, "startListeningForVendorSensor()");
        }
        if (mVendorSensor != null) {
            mVendorSensor.register();
        }
    }

    private void stopListeningForVendorSensor() {
        if (DEBUG) {
            Log.d(TAG, "stopListeningForVendorSensor()");
        }
        if (mVendorSensor != null) {
            mVendorSensor.unregister();
        }
    }

    private void startListeningForProximity() {
        if (!ENABLE_PROXIMITY_JUDGE || mVendorSensor != null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "startListeningForProximity()");
        }
        if (mProximitySensor != null) {
            mProximitySensor.register();
        }
    }

    private void stopListeningForProximity() {
        if (DEBUG) {
            Log.d(TAG, "startListeningForProximity()");
        }
        if (mProximitySensor != null) {
            mProximitySensor.unregister();
        }
    }

    private void startListeningForLight() {
        if (!ENABLE_LIGHT_JUDGE || mVendorSensor != null) {
            return;
        }
        if (DEBUG) {
            Log.d(TAG, "startListeningForLight()");
        }
        if (mLightSensor != null) {
            mLightSensor.register();
        }
    }

    private void stopListeningForLight() {
        if (DEBUG) {
            Log.d(TAG, "stopListeningForLight()");
        }
        if (mLightSensor != null) {
            mLightSensor.unregister();
        }
    }

    private void handleSystemBooted() {
        if (DEBUG) {
            Log.d(TAG, "onBootPhase(): PHASE_BOOT_COMPLETED");
        }
        mSystemBooted = true;
        if (mPending) {
            final Message msg = mHandler.obtainMessage(
                MSG_INTERACTIVE_CHANGED, mInteractive ? 1 : 0, -1 /** arg2 */);
            mHandler.sendMessage(msg);
            mPending = false;
        }
    }

    private void handleDispatchCallbacks() {
        final boolean isDeviceInPocket = isDeviceInPocket();
        synchronized (mCallbacks) {
            boolean cleanup = false;
            for (final IPocketCallback callback: mCallbacks) {
                try {
                    callback.onStateChanged(isDeviceInPocket, PocketManager.REASON_SENSOR);
                } catch (RemoteException e) {
                    cleanup = true;
                }
            }
            if (cleanup) {
                cleanUpCallbacksLocked();
            }
        }
        dispatchStateToPocketBridge();
        if (isDeviceInPocket) {
            showPocketLock();
        } else {
            hidePocketLock();
        }
    }

    private void cleanUpCallbacksLocked() {
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            final IPocketCallback callback = mCallbacks.get(i);
            if (!callback.asBinder().isBinderAlive()) {
                mCallbacks.remove(i);
            }
        }
    }

    private void handleSetPocketLockVisible(final boolean visible) {
        if (!visible) {
            if (DEBUG) Log.v(TAG, "Clearing pocket timer");
            mHandler.removeCallbacks(mPocketLockTimeout);
            mPocketViewTimerActive = false;
        }
    }

    private void handleSetListeningExternal(final boolean listen) {
        if (listen) {
            // should prevent external processes to register while interactive,
            // while they are allowed to stop listening in any case as for example
            // coming pocket lock will need to.
            if (!mInteractive) {
                registerSensorListeners();
            }
        } else {
            mPocketViewTimerActive = false;
            mHandler.removeCallbacksAndMessages(null);
            unregisterSensorListeners();
        }
        dispatchCallbacks();
    }

    private void handleAddCallback(@Nullable final IPocketCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (mCallbacks) {
            if (!mCallbacks.contains(callback)) {
                mCallbacks.add(callback);
            }
        }
    }

    private void handleRemoveCallback(@Nullable final IPocketCallback callback) {
        if (callback == null) {
            return;
        }
        synchronized (mCallbacks) {
            if (mCallbacks.contains(callback)) {
                mCallbacks.remove(callback);
            }
        }
    }

    private void handleInteractiveChanged(final boolean interactive) {
        final boolean isPocketViewShowing = interactive && isDeviceInPocket();
        if (mPocketViewTimerActive != isPocketViewShowing) {
            mPocketViewTimerActive = isPocketViewShowing;
            mHandler.removeCallbacks(mPocketLockTimeout); // remove any pending requests
            if (mPocketViewTimerActive) {
                if (DEBUG) Log.v(TAG, "Setting pocket timer");
                mHandler.postDelayed(mPocketLockTimeout, POCKET_LOCK_TIMEOUT_DURATION);
            }
        }
        // always update interactive state.
        mInteractive = interactive;

        if (mPending) {
            // working on it, waiting for proper system conditions.
            return;
        } else if (!mPending && !mSystemBooted) {
            // we ain't ready, postpone till system is booted.
            mPending = true;
            return;
        }

        update();
    }

    private void handleVendorSensorEvent(@Nullable final SensorEvent sensorEvent) {
        if (mVendorSensor == null) {
            return;
        }
        final boolean isDeviceInPocket = isDeviceInPocket();

        mVendorSensor.lastState = mVendorSensor.state;

        if (DEBUG) {
            final String sensorEventToString = sensorEvent != null ? sensorEvent.toString() : "NULL";
            Log.d(TAG, "VENDOR_SENSOR: onSensorChanged(), sensorEvent =" + sensorEventToString);
        }
        if (sensorEvent == null) {
            if (DEBUG) Log.d(TAG, "Event is null!");
            mVendorSensor.state = VENDOR_SENSOR_UNKNOWN;
        } else if (sensorEvent.values == null || sensorEvent.values.length == 0) {
            if (DEBUG) Log.d(TAG, "Event has no values! event.values null ? " + (sensorEvent.values == null));
            mVendorSensor.state = VENDOR_SENSOR_UNKNOWN;
        } else {
            final boolean isVendorPocket = sensorEvent.values[0] == 1.0;
            if (DEBUG) {
                final long time = SystemClock.uptimeMillis();
                Log.d(TAG, "Event: time=" + time + ", value=" + sensorEvent.values[0]
                        + ", isInPocket=" + isVendorPocket);
            }
            mVendorSensor.state = isVendorPocket ? VENDOR_SENSOR_IN_POCKET : VENDOR_SENSOR_UNKNOWN;
        }
        if (isDeviceInPocket != isDeviceInPocket()) {
            dispatchCallbacks();
        }
    }

    private void handleLightSensorEvent(@Nullable final SensorEvent sensorEvent) {
        if (mLightSensor == null) {
            return;
        }
        final boolean isDeviceInPocket = isDeviceInPocket();

        mLightSensor.lastState = mLightSensor.state;

        if (DEBUG) {
            final String sensorEventToString = sensorEvent != null ? sensorEvent.toString() : "NULL";
            Log.d(TAG, "LIGHT_SENSOR: onSensorChanged(), sensorEvent =" + sensorEventToString);
        }
        if (sensorEvent == null) {
            if (DEBUG) Log.d(TAG, "Event is null!");
            mLightSensor.state = LIGHT_UNKNOWN;
        } else if (sensorEvent.values == null || sensorEvent.values.length == 0) {
            if (DEBUG) Log.d(TAG, "Event has no values! event.values null ? " + (sensorEvent.values == null));
            mLightSensor.state = LIGHT_UNKNOWN;
        } else {
            final float value = sensorEvent.values[0];
            final boolean isPoor = value >= 0
                    && value <= POCKET_LIGHT_MAX_THRESHOLD;
            if (DEBUG) {
                final long time = SystemClock.uptimeMillis();
                Log.d(TAG, "Event: time= " + time + ", value=" + value
                        + ", maxRange=" + mLightSensor.maxRange + ", isPoor=" + isPoor);
            }
            mLightSensor.state = isPoor ? LIGHT_POCKET : LIGHT_AMBIENT;
        }
        if (isDeviceInPocket != isDeviceInPocket()) {
            dispatchCallbacks();
        }
    }

    private void handleProximitySensorEvent(@Nullable final SensorEvent sensorEvent) {
        if (mProximitySensor == null) {
            return;
        }
        final boolean isDeviceInPocket = isDeviceInPocket();

        mProximitySensor.lastState = mProximitySensor.state;

        if (DEBUG) {
            final String sensorEventToString = sensorEvent != null ? sensorEvent.toString() : "NULL";
            Log.d(TAG, "PROXIMITY_SENSOR: onSensorChanged(), sensorEvent =" + sensorEventToString);
        }
        if (sensorEvent == null) {
            if (DEBUG) Log.d(TAG, "Event is null!");
            mProximitySensor.state = PROXIMITY_UNKNOWN;
        } else if (sensorEvent.values == null || sensorEvent.values.length == 0) {
            if (DEBUG) Log.d(TAG, "Event has no values! event.values null ? " + (sensorEvent.values == null));
            mProximitySensor.state = PROXIMITY_UNKNOWN;
        } else {
            final float value = sensorEvent.values[0];
            final boolean isPositive = sensorEvent.values[0] < mProximitySensor.maxRange;
            if (DEBUG) {
                final long time = SystemClock.uptimeMillis();
                Log.d(TAG, "Event: time=" + time + ", value=" + value
                        + ", maxRange=" + mProximitySensor.maxRange + ", isPositive=" + isPositive);
            }
            mProximitySensor.state = isPositive ? PROXIMITY_POSITIVE : PROXIMITY_NEGATIVE;
        }
        if (isDeviceInPocket != isDeviceInPocket()) {
            dispatchCallbacks();
        }
    }

    private void handleUnregisterTimeout() {
        mHandler.removeCallbacksAndMessages(null);
        unregisterSensorListeners();
    }

    private void dispatchCallbacks() {
        final boolean isDeviceInPocket = isDeviceInPocket();
        if (mInteractive) {
            if (!isDeviceInPocket) {
                mHandler.sendEmptyMessageDelayed(MSG_UNREGISTER_TIMEOUT, 5000 /* ms */);
            } else {
                mHandler.removeMessages(MSG_UNREGISTER_TIMEOUT);
            }
        }
        mHandler.removeMessages(MSG_DISPATCH_CALLBACKS);
        mHandler.sendEmptyMessage(MSG_DISPATCH_CALLBACKS);
    }

    private void showPocketLock() {
        mStatusBarManagerInternal.showPocketLock();
        mIsPocketLockShowing = true;
    }

    private void hidePocketLock() {
        mStatusBarManagerInternal.hidePocketLock();
        mIsPocketLockShowing = false;
    }

    private void dispatchStateToPocketBridge() {
        if (mPocketBridgeWriter != null) {
            mPocketBridgeWriter.println(isDeviceInPocket() ? 1 : 0);
        }
    }

    private void dumpInternal(final PrintWriter pw) {
        final JSONObject dump = new JSONObject();
        try {
            dump.put("service", "POCKET");
            dump.put("enabled", mEnabled);
            dump.put("isDeviceInPocket", isDeviceInPocket());
            dump.put("interactive", mInteractive);
            if (mProximitySensor != null) {
                dump.put("proximitySensor", mProximitySensor.dump());
            }
            if (mLightSensor != null) {
                dump.put("lightSensor", mLightSensor.dump());
            }
            if (mVendorSensor != null) {
                dump.put("vendorSensor", mVendorSensor.dump());
            }
        } catch (JSONException e) {
            Slog.e(TAG, "dump formatting failure", e);
        } finally {
            pw.println(dump);
        }
    }
}
