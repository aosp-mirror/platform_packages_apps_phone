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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

/*
 * Handles OTA Start procedure at phone power up. At phone power up, if phone is not OTA
 * provisioned (check MIN value of the Phone) and 'device_provisioned' is not set,
 * OTA Activation screen is shown that helps user activate the phone
 */
public class OtaStartupReceiver extends BroadcastReceiver {
    private static final String TAG = "OtaStartupReceiver";
    private static final boolean DBG = (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final int MIN_READY = 10;
    private Context mContext;

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case MIN_READY:
                Log.v(TAG, "Attempting OtaActivation from handler");
                OtaUtils.maybeDoOtaCall(mContext, mHandler, MIN_READY);
            }
        }
    };

    public void onReceive(Context context, Intent intent) {
        mContext = context;

        if (!OtaUtils.isCdmaPhone()) {
            if (DBG) Log.d(TAG, "Not a CDMA phone, no need to process OTA");
            return;
        }

        if (shouldPostpone(context)) {
            if (DBG) Log.d(TAG, "Postponing CDMA provisioning until wizard runs");
            return;
        }

        // The following depends on the phone process being persistent. Normally we can't
        // expect a BroadcastReceiver to persist after returning from this function but it does
        // because the phone activity is persistent.
        OtaUtils.maybeDoOtaCall(mContext, mHandler, MIN_READY);
    }
    
    /**
     * On devices that provide a phone initialization wizard (such as Google Setup Wizard), we 
     * allow delaying CDMA OTA setup so it can be done in a single wizard. The wizard is responsible
     * for (1) disabling itself once it has been run and/or (2) setting the 'device_provisioned' 
     * flag to something non-zero and (3) calling the OTA Setup with the action below.
     * 
     * NB: Typical phone initialization wizards will install themselves as the homescreen
     * (category "android.intent.category.HOME") with a priority higher than the default.  
     * The wizard should set 'device_provisioned' when it completes, disable itself with the
     * PackageManager.setComponentEnabledSetting() and then start home screen.
     * 
     * @return true if setup will be handled by wizard, false if it should be done now.
     */
    private boolean shouldPostpone(Context context) {
        Intent intent = new Intent("android.intent.action.DEVICE_INITIALIZATION_WIZARD");
        ResolveInfo resolveInfo = context.getPackageManager().resolveActivity(intent, 
                PackageManager.MATCH_DEFAULT_ONLY);
        boolean provisioned = Settings.Secure.getInt(context.getContentResolver(),
                Settings.Secure.DEVICE_PROVISIONED, 0) != 0;
        String mode = SystemProperties.get("ro.setupwizard.mode", "REQUIRED");
        boolean runningSetupWizard = "REQUIRED".equals(mode) || "OPTIONAL".equals(mode);
        if (DBG) {
            Log.v(TAG, "resolvInfo = " + resolveInfo + ", provisioned = " + provisioned 
                    + ", runningSetupWizard = " + runningSetupWizard);
        }
        return resolveInfo != null && !provisioned && runningSetupWizard;
    }
}
