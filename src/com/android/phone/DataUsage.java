/*
 * Copyright (C) 2010 The Android Open Source Project
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

import com.android.phone.R;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.net.Uri;
import android.net.ThrottleManager;
import android.util.Log;


/**
 * Lists the data usage and throttle settings
 */
public class DataUsage extends PreferenceActivity {

    private Preference mCurrentUsagePref;
    private Preference mTimeFramePref;
    private Preference mThrottleRatePref;
    private Preference mHelpPref;
    private String mHelpUri;

    private DataUsageListener mDataUsageListener;
    private ThrottleManager mThrottleManager;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mThrottleManager = (ThrottleManager) getSystemService(Context.THROTTLE_SERVICE);

        addPreferencesFromResource(R.xml.data_usage_settings);

        mCurrentUsagePref = findPreference("throttle_current_usage");
        mTimeFramePref = findPreference("throttle_time_frame");
        mThrottleRatePref = findPreference("throttle_rate");
        mHelpPref = findPreference("throttle_help");

        mHelpUri = mThrottleManager.getHelpUri();
        if (mHelpUri == null ) {
            getPreferenceScreen().removePreference(mHelpPref);
        } else {
            mHelpPref.setSummary(getString(R.string.throttle_help_subtext));
        }

        mDataUsageListener = new DataUsageListener(this, mCurrentUsagePref,
                mTimeFramePref, mThrottleRatePref);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mDataUsageListener.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mDataUsageListener.pause();
    }


    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {

        if (preference == mHelpPref) {
            try {
                Intent myIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(mHelpUri));
                startActivity(myIntent);
            } catch (Exception e) {
                ;
            }
        }

        return true;
    }
}
