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

import com.android.internal.telephony.cdma.CDMAPhone;
import com.android.internal.telephony.Phone;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemProperties;
import android.util.Log;

/*
 * Handles OTA Start procedure at phone power up.
 * At phone power up, if phone is not OTA provisioned (check MIN value of the Phone),
 * OTA Activation screen is shown that helps user activate the phone
 */

public class OtaStartupReceiver extends BroadcastReceiver {
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);

    static final String LOG_TAG = "OTAStartupReceiver";
    private final String UNACTIVATED_MIN2_VALUE = "999";
    private final String UNACTIVATED_MIN_VALUE = "1111110111";
    private boolean mPhoneNeedActivation = false;
    private int mOtaShowActivationScreen = 0;
    private  int mMin2 = 0;
    private  int mMin = 0;
    private  String mMin_string;
    private  String mMin2_string;
    private  int mTmpOtaShowActivationScreen;

    public void onReceive(Context context, Intent intent) {

        PhoneApp app =  PhoneApp.getInstance();
        Phone mPhone = app.phone;

        if (!mPhone.getPhoneName().equals("CDMA")) {
            if (DBG) log("OTAStartupReceiver: Not CDMA phone, no need to process OTA");
            return;
        }
        mMin_string = mPhone.getCdmaMin();
        if (DBG) log("OTAStartupReceiver: min_string: " + mMin_string);
        if ((mMin_string != null) && (mMin_string.length() > 3)) {
            mMin2_string = mMin_string.substring(0, 3);
            if (DBG) log("OTAStartupReceiver: min2_string: " + mMin2_string);
        } else {
            if (DBG) log("OTAStartupReceiver: min_string is NULL or too short, exit");
            return;
        }

        if ((mMin2_string.equals(UNACTIVATED_MIN2_VALUE))
                || (mMin_string.equals(UNACTIVATED_MIN_VALUE))) {
            mPhoneNeedActivation = true;
            if (DBG) log("OTAStartupReceiver: mPhoneNeedActivation is set to TRUE");
        } else {
            if (DBG) log("OTAStartupReceiver: mPhoneNeedActivation is set to FALSE");
        }

        mTmpOtaShowActivationScreen =
                context.getResources().getInteger(R.integer.OtaShowActivationScreen);
        if (DBG) log("OTAStartupReceiver: tmpOtaShowActivationScreen: "
                + mTmpOtaShowActivationScreen);
        mOtaShowActivationScreen = mTmpOtaShowActivationScreen;
        if (DBG) log("OTAStartupReceiver: mOtaShowActivationScreen: " + mOtaShowActivationScreen);

        if ((mPhoneNeedActivation)
                && (mOtaShowActivationScreen == OtaUtils.OTA_SHOW_ACTIVATION_SCREEN_ON)) {
            if (DBG) log("OTAStartupReceiver: activation intent sent.");
            app.cdmaOtaProvisionData.isOtaCallIntentProcessed = false;
            Intent newIntent = new Intent(InCallScreen.ACTION_SHOW_ACTIVATION);
            newIntent.setClass(context, InCallScreen.class);
            newIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(newIntent);
        } else {
            if (DBG) log("OTAStartupReceiver: activation intent NOT sent.");
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
