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

import android.content.Context;
import android.provider.Settings;
import com.android.internal.telephony.Phone;

/**
 * TODO: This is intended as a temporary repository for behavior policy
 * functions that depend upon the type of phone or the carrier.  Ultimately
 * these sorts of questions should be answered by the telephony layer.
 */

public class TelephonyCapabilities {

    public static boolean useShortDtmfTones(Phone phone, Context context) {
        /**
         * On GSM devices, we never use short tones.
         * On CDMA devices, it depends upon the settings.
         * TODO: I don't think this has anything to do with GSM versus CDMA,
         * should we be looking only at the setting?
         */
        int phoneType = phone.getPhoneType();
        if (phoneType == Phone.PHONE_TYPE_GSM) {
            return false;
        } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
            int toneType = android.provider.Settings.System.getInt(
                    context.getContentResolver(),
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL);
            if (toneType == CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL) {
                return true;
            } else {
                return false;
            }
        } else {
            throw new IllegalStateException("Unexpected phone type: " + phoneType);
        }
    }
}