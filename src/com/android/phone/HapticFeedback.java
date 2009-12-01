/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.phone;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Resources;
import android.os.Vibrator;
import android.util.Log;
import android.provider.Settings;
import android.provider.Settings.System;

/**
 * Handles the haptic feedback: a light buzz happening when the user
 * presses a soft key (UI button or capacitive key).  The haptic
 * feedback is controlled by:
 * - a system resource for the pattern
 *   The pattern used is tuned per device and stored in an internal
 *   resource (config_virtualKeyVibePattern.)
 * - a system setting HAPTIC_FEEDBACK_ENABLED.
 *   HAPTIC_FEEDBACK_ENABLED can be changed by the user using the
 *   system Settings activity. It must be rechecked each time the
 *   activity comes in the foreground (onResume).
 *
 * This class is not thread safe. It assumes it'll be called from the
 * UI thead.
 *
 * Typical usage:
 * --------------
 *   static private final boolean HAPTIC_ENABLED = true;
 *   private HapticFeedback mHaptic = new HapticFeedback();
 *
 *   protected void onCreate(Bundle icicle) {
 *     mHaptic.init((Context)this, HAPTIC_ENABLED);
 *   }
 *
 *   protected void onResume() {
 *     // Refresh the system setting.
 *     mHaptic.checkSystemSetting();
 *   }
 *
 *   public void foo() {
 *     mHaptic.vibrate();
 *   }
 *
 */

public class HapticFeedback {
    private static final int VIBRATION_PATTERN_ID =
            com.android.internal.R.array.config_virtualKeyVibePattern;
    /** If no pattern was found, vibrate for a small amount of time. */
    private static final long DURATION = 10;  // millisec.
    /** Play the haptic pattern only once. */
    private static final int NO_REPEAT = -1;

    private static final String TAG = "HapticFeedback";
    private Context mContext;
    private long[] mHapticPattern;
    private Vibrator mVibrator;

    private boolean mEnabled;
    private Settings.System mSystemSettings;
    private ContentResolver mContentResolver;
    private boolean mSettingEnabled;

    /**
     * Initialize this instance using the app and system
     * configs. Since these don't change, init is typically called
     * once in 'onCreate'.
     * checkSettings is not called during init.
     * @param context To look up the resources and system settings.
     * @param enabled If false, vibrate will be a no-op regardless of
     * the system settings.
     */
    public void init(Context context, boolean enabled) {
        mEnabled = enabled;
        if (enabled) {
            mVibrator = new Vibrator();
            if (!loadHapticSystemPattern(context.getResources())) {
                mHapticPattern = new long[] {0, DURATION, 2 * DURATION, 3 * DURATION};
            }
            mSystemSettings = new Settings.System();
            mContentResolver = context.getContentResolver();
        }
    }


    /**
     * Reload the system settings to check if the user enabled the
     * haptic feedback.
     */
    public void checkSystemSetting() {
        if (!mEnabled) {
            return;
        }
        try {
            int val = mSystemSettings.getInt(mContentResolver, System.HAPTIC_FEEDBACK_ENABLED, 0);
            mSettingEnabled = val != 0;
        } catch (Resources.NotFoundException nfe) {
            Log.e(TAG, "Could not retrieve system setting.", nfe);
            mSettingEnabled = false;
        }

    }


    /**
     * Generate the haptic feedback vibration. Only one thread can
     * request it. If the phone is already in a middle of an haptic
     * feedback sequence, the request is ignored.
     */
    public void vibrate() {
        if (!mEnabled || !mSettingEnabled) {
            return;
        }
        mVibrator.vibrate(mHapticPattern, NO_REPEAT);
    }

    /**
     * @return true If the system haptic pattern was found.
     */
    private boolean loadHapticSystemPattern(Resources r) {
        int[] pattern;

        mHapticPattern = null;
        try {
            pattern = r.getIntArray(VIBRATION_PATTERN_ID);
        } catch (Resources.NotFoundException nfe) {
            Log.e(TAG, "Vibrate pattern missing.", nfe);
            return false;
        }

        if (null == pattern || pattern.length == 0) {
            Log.e(TAG, "Haptic pattern is null or empty.");
            return false;
        }

        // int[] to long[] conversion.
        mHapticPattern = new long[pattern.length];
        for (int i = 0; i < pattern.length; i++) {
            mHapticPattern[i] = pattern[i];
        }
        return true;
    }
}
