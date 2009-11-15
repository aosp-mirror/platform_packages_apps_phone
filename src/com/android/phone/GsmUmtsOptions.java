/*
 * Copyright (C) 2008 The Android Open Source Project
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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

/**
 * List of Network-specific settings screens.
 */
public class GsmUmtsOptions extends PreferenceActivity {

    private PreferenceScreen mButtonAPNExpand;
    private PreferenceScreen mButtonOperatorSelectionExpand;
    private CheckBoxPreference mButtonPrefer2g;

    private static final String BUTTON_APN_EXPAND_KEY = "button_apn_key";
    private static final String BUTTON_OPERATOR_SELECTION_EXPAND_KEY = "button_carrier_sel_key";
    private static final String BUTTON_PREFER_2G_KEY = "button_prefer_2g_key";


    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_options);
        PreferenceScreen prefSet = getPreferenceScreen();
        mButtonAPNExpand = (PreferenceScreen) prefSet.findPreference(BUTTON_APN_EXPAND_KEY);
        mButtonOperatorSelectionExpand =
                (PreferenceScreen) prefSet.findPreference(BUTTON_OPERATOR_SELECTION_EXPAND_KEY);
        mButtonPrefer2g = (CheckBoxPreference) prefSet.findPreference(BUTTON_PREFER_2G_KEY);
        if (PhoneFactory.getDefaultPhone().getPhoneType() != Phone.PHONE_TYPE_GSM) {
            mButtonAPNExpand.setEnabled(false);
            mButtonOperatorSelectionExpand.setEnabled(false);
            mButtonPrefer2g.setEnabled(false);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey().equals(BUTTON_PREFER_2G_KEY)) {
            return true;
        }
        return false;
    }
}
