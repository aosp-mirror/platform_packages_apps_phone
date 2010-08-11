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

package com.android.phone.sip;

import com.android.phone.R;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.util.Log;
import android.os.IBinder;

import java.util.List;
import javax.sip.SipException;

/**
 * The service class for registering the sip profiles if the auto registration
 * flag is enabled in the sip settings.
 */
public class SipAutoRegistration extends Service {
    public static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";
    public static final String AUTOREG_FLAG = "AUTOREG";
    public static final String SIP_CALL_FIRST_FLAG = "SIPFIRST";
    private static final String TAG = "SipAutoRegistration";

    @Override
    public void onCreate() {
        super.onCreate();
        registerAllProfiles(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerAllProfiles(final Context context) {
        new Thread(new Runnable() {
            public void run() {
                SipManager sipManager = SipManager.getInstance(context);
                List<SipProfile> sipProfileList =
                        SipSettings.retrieveSipListFromDirectory(
                        context.getFilesDir().getAbsolutePath()
                        + SipSettings.PROFILES_DIR);
                for (SipProfile profile : sipProfileList) {
                    try {
                        if (!profile.getAutoRegistration()) continue;
                        sipManager.open(profile,
                                SipSettings.INCOMING_CALL_ACTION, null);
                    } catch (SipException e) {
                        Log.e(TAG, "failed" + profile.getProfileName(), e);
                    }
                }
                stopSelf();
            }}
        ).start();
    }
}
