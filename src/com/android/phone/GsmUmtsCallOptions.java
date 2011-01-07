/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

public class GsmUmtsCallOptions extends PreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsCallOptions";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private static final String BUTTON_CF_EXPAND_KEY = "button_cf_expand_key";
    private static final String BUTTON_MORE_EXPAND_KEY = "button_more_expand_key";
    public static final String SUBSCRIPTION_ID = "SUBSCRIPTION_ID";

    private PreferenceScreen subscriptionPrefCFE;

    private int mSubscription = 0;
    private Phone mPhone;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_call_options);

        // getting selected subscription
        mSubscription = getIntent().getIntExtra(CallFeaturesSetting.SUBSCRIPTION_ID, 0);
        // setting selected subscription for GsmUmtsCallForwardOptions.java
        subscriptionPrefCFE  = (PreferenceScreen) findPreference(BUTTON_CF_EXPAND_KEY);
        subscriptionPrefCFE.getIntent().putExtra(SUBSCRIPTION_ID, mSubscription);
        // setting selected subscription for GsmUmtsAdditionalCallOptions.java
        PreferenceScreen subscriptionPrefAdditionSettings =
                (PreferenceScreen) findPreference(BUTTON_MORE_EXPAND_KEY);
        subscriptionPrefAdditionSettings.getIntent().putExtra(SUBSCRIPTION_ID, mSubscription);
        Log.d(LOG_TAG, "Getting GsmUmtsCallOptions subscription =" + mSubscription);
        mPhone = PhoneApp.getPhone(mSubscription);

        if (mPhone.getPhoneType() != Phone.PHONE_TYPE_GSM) {
            Log.d(LOG_TAG, "Non GSM Phone!");
            //disable the entire screen
            getPreferenceScreen().setEnabled(false);
        }
    }
}
