/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.displayhash;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.Service;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteCallback;
import android.view.displayhash.DisplayHash;
import android.view.displayhash.DisplayHashResultCallback;
import android.view.displayhash.VerifiedDisplayHash;

import java.util.Map;

/**
 * A service that handles generating and verify {@link DisplayHash}.
 *
 * The service will generate a DisplayHash based on arguments passed in. Then later that
 * same DisplayHash can be verified to determine that it was created by the system.
 *
 * @hide
 */
@SystemApi
public abstract class DisplayHashingService extends Service {

    /** @hide **/
    public static final String EXTRA_VERIFIED_DISPLAY_HASH =
            "android.service.displayhash.extra.VERIFIED_DISPLAY_HASH";

    /**
     * Name under which a DisplayHashingService component publishes information
     * about itself.  This meta-data must reference an XML resource containing a
     * {@link com.android.internal.R.styleable#DisplayHashingService} tag.
     *
     * @hide
     */
    @SystemApi
    public static final String SERVICE_META_DATA = "android.displayhash.display_hashing_service";

    /**
     * The {@link Intent} action that must be declared as handled by a service in its manifest
     * for the system to recognize it as a DisplayHash providing service.
     *
     * @hide
     */
    @SystemApi
    public static final String SERVICE_INTERFACE =
            "android.service.displayhash.DisplayHashingService";

    private DisplayHashingServiceWrapper mWrapper;
    private Handler mHandler;

    public DisplayHashingService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mWrapper = new DisplayHashingServiceWrapper();
        mHandler = new Handler(Looper.getMainLooper(), null, true);
    }

    @NonNull
    @Override
    public final IBinder onBind(@NonNull Intent intent) {
        return mWrapper;
    }

    /**
     * Generates the DisplayHash that can be used to validate that the system generated the
     * token.
     *
     * @param salt          The salt to use when generating the hmac. This should be unique to the
     *                      caller so the token cannot be verified by any other process.
     * @param buffer        The buffer for the content to generate the hash for.
     * @param bounds        The size and position of the content in window space.
     * @param hashAlgorithm The String for the hashing algorithm to use based values in
     *                      {@link #getDisplayHashAlgorithms(RemoteCallback)}.
     * @param callback      The callback to invoke
     *                      {@link DisplayHashResultCallback#onDisplayHashResult(DisplayHash)}
     *                      if successfully generated a DisplayHash or {@link
     *                      DisplayHashResultCallback#onDisplayHashError(int)} if failed.
     */
    public abstract void onGenerateDisplayHash(@NonNull byte[] salt,
            @NonNull HardwareBuffer buffer, @NonNull Rect bounds,
            @NonNull String hashAlgorithm, @NonNull DisplayHashResultCallback callback);

    /**
     * Returns a map of supported algorithms and their {@link DisplayHashParams}
     */
    @NonNull
    public abstract Map<String, DisplayHashParams> onGetDisplayHashAlgorithms();

    /**
     * Call to verify that the DisplayHash passed in was generated by the system.
     *
     * @param salt        The salt value to use when verifying the hmac. This should be the
     *                    same value that was passed to
     *                    {@link #onGenerateDisplayHash(byte[],
     *                    HardwareBuffer, Rect, String, DisplayHashResultCallback)} to
     *                    generate the token.
     * @param displayHash The token to verify that it was generated by the system.
     * @return a {@link VerifiedDisplayHash} if the provided display hash was originally generated
     * by the system or null if the system did not generate the display hash.
     */
    @Nullable
    public abstract VerifiedDisplayHash onVerifyDisplayHash(@NonNull byte[] salt,
            @NonNull DisplayHash displayHash);

    private void verifyDisplayHash(byte[] salt, DisplayHash displayHash,
            RemoteCallback callback) {
        VerifiedDisplayHash verifiedDisplayHash = onVerifyDisplayHash(salt,
                displayHash);
        final Bundle data = new Bundle();
        data.putParcelable(EXTRA_VERIFIED_DISPLAY_HASH, verifiedDisplayHash);
        callback.sendResult(data);
    }

    private void getDisplayHashAlgorithms(RemoteCallback callback) {
        Map<String, DisplayHashParams> displayHashParams = onGetDisplayHashAlgorithms();
        final Bundle data = new Bundle();
        for (Map.Entry<String, DisplayHashParams> entry : displayHashParams.entrySet()) {
            data.putParcelable(entry.getKey(), entry.getValue());
        }
        callback.sendResult(data);
    }

    private final class DisplayHashingServiceWrapper extends IDisplayHashingService.Stub {
        @Override
        public void generateDisplayHash(byte[] salt, HardwareBuffer buffer, Rect bounds,
                String hashAlgorithm, RemoteCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(DisplayHashingService::onGenerateDisplayHash,
                            DisplayHashingService.this, salt, buffer, bounds,
                            hashAlgorithm, new DisplayHashResultCallback() {
                                @Override
                                public void onDisplayHashResult(
                                        @NonNull DisplayHash displayHash) {
                                    Bundle result = new Bundle();
                                    result.putParcelable(EXTRA_DISPLAY_HASH, displayHash);
                                    callback.sendResult(result);
                                }

                                @Override
                                public void onDisplayHashError(int errorCode) {
                                    Bundle result = new Bundle();
                                    result.putInt(EXTRA_DISPLAY_HASH_ERROR_CODE, errorCode);
                                    callback.sendResult(result);
                                }
                            }));
        }

        @Override
        public void verifyDisplayHash(byte[] salt, DisplayHash displayHash,
                RemoteCallback callback) {
            mHandler.sendMessage(
                    obtainMessage(DisplayHashingService::verifyDisplayHash,
                            DisplayHashingService.this, salt, displayHash, callback));
        }

        @Override
        public void getDisplayHashAlgorithms(RemoteCallback callback) {
            mHandler.sendMessage(obtainMessage(DisplayHashingService::getDisplayHashAlgorithms,
                    DisplayHashingService.this, callback));
        }
    }
}
