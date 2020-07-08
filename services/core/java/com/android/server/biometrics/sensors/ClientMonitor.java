/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.biometrics.BiometricsProtoEnums;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import java.util.NoSuchElementException;

/**
 * Abstract base class for keeping track and dispatching events from the biometric's HAL to the
 * the current client.  Subclasses are responsible for coordinating the interaction with
 * the biometric's HAL for the specific action (e.g. authenticate, enroll, enumerate, etc.).
 */
public abstract class ClientMonitor<T> extends LoggableMonitor implements IBinder.DeathRecipient {

    private static final String TAG = "Biometrics/ClientMonitor";
    protected static final boolean DEBUG = BiometricServiceBase.DEBUG;

    /**
     * Interface that ClientMonitor holders should use to receive callbacks.
     */
    public interface FinishCallback {
        /**
         * Invoked when the ClientMonitor operation is complete. This abstracts away asynchronous
         * (i.e. Authenticate, Enroll, Enumerate, Remove) and synchronous (i.e. generateChallenge,
         * revokeChallenge) so that a scheduler can process ClientMonitors regardless of their
         * implementation.
         *
         * @param clientMonitor Reference of the ClientMonitor that finished.
         * @param success True if the operation completed successfully.
         */
        void onClientFinished(ClientMonitor clientMonitor, boolean success);
    }

    /**
     * Interface that allows ClientMonitor subclasses to retrieve a fresh instance to the HAL.
     */
    public interface LazyDaemon<T> {
        /**
         * @return A fresh instance to the biometric HAL
         */
        T getDaemon();
    }

    @NonNull private final Context mContext;
    @NonNull protected final LazyDaemon<T> mLazyDaemon;
    private final int mTargetUserId;
    @NonNull private final String mOwner;
    private final int mSensorId; // sensorId as configured by the framework

    @Nullable private IBinder mToken;
    @Nullable private ClientMonitorCallbackConverter mListener;
    // Currently only used for authentication client. The cookie generated by BiometricService
    // is never 0.
    private final int mCookie;
    boolean mAlreadyDone;

    @NonNull protected FinishCallback mFinishCallback;

    /**
     * @param context    system_server context
     * @param lazyDaemon pointer for lazy retrieval of the HAL
     * @param token      a unique token for the client
     * @param listener   recipient of related events (e.g. authentication)
     * @param userId     target user id for operation
     * @param owner      name of the client that owns this
     * @param cookie     BiometricPrompt authentication cookie (to be moved into a subclass soon)
     * @param sensorId   ID of the sensor that the operation should be requested of
     * @param statsModality One of {@link BiometricsProtoEnums} MODALITY_* constants
     * @param statsAction   One of {@link BiometricsProtoEnums} ACTION_* constants
     * @param statsClient   One of {@link BiometricsProtoEnums} CLIENT_* constants
     */
    public ClientMonitor(@NonNull Context context, @NonNull LazyDaemon<T> lazyDaemon,
            @Nullable IBinder token, @Nullable ClientMonitorCallbackConverter listener, int userId,
            @NonNull String owner, int cookie, int sensorId, int statsModality, int statsAction,
            int statsClient) {
        super(statsModality, statsAction, statsClient);
        mContext = context;
        mLazyDaemon = lazyDaemon;
        mToken = token;
        mListener = listener;
        mTargetUserId = userId;
        mOwner = owner;
        mCookie = cookie;
        mSensorId = sensorId;

        try {
            if (token != null) {
                token.linkToDeath(this, 0);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "caught remote exception in linkToDeath: ", e);
        }
    }

    public int getCookie() {
        return mCookie;
    }

    /**
     * Invoked if the scheduler is unable to start the ClientMonitor (for example the HAL is null).
     * If such a problem is detected, the scheduler will not invoke
     * {@link #start(FinishCallback)}.
     */
    public abstract void unableToStart();

    /**
     * Starts the ClientMonitor's lifecycle. Invokes {@link #startHalOperation()} when internal book
     * keeping is complete.
     * @param finishCallback invoked when the operation is complete (succeeds, fails, etc)
     */
    public void start(@NonNull FinishCallback finishCallback) {
        mFinishCallback = finishCallback;
    }

    /**
     * Starts the HAL operation specific to the ClientMonitor subclass.
     */
    protected abstract void startHalOperation();

    public boolean isAlreadyDone() {
        return mAlreadyDone;
    }

    public void destroy() {
        if (mToken != null) {
            try {
                mToken.unlinkToDeath(this, 0);
            } catch (NoSuchElementException e) {
                // TODO: remove when duplicate call bug is found
                Slog.e(TAG, "destroy(): " + this + ":", new Exception("here"));
            }
            mToken = null;
        }
        mListener = null;
    }

    @Override
    public void binderDied() {
        binderDiedInternal(true /* clearListener */);
    }

    // TODO(b/157790417): Move this to the scheduler
    void binderDiedInternal(boolean clearListener) {
        // If the current client dies we should cancel the current operation.
        if (this instanceof Interruptable) {
            Slog.e(TAG, "Binder died, cancelling client");
            ((Interruptable) this).cancel();
        }
        mToken = null;
        if (clearListener) {
            mListener = null;
        }
    }

    public final Context getContext() {
        return mContext;
    }

    public final String getOwnerString() {
        return mOwner;
    }

    public final ClientMonitorCallbackConverter getListener() {
        return mListener;
    }

    public final int getTargetUserId() {
        return mTargetUserId;
    }

    public final IBinder getToken() {
        return mToken;
    }

    public final int getSensorId() {
        return mSensorId;
    }

    public final T getFreshDaemon() {
        return mLazyDaemon.getDaemon();
    }
}
