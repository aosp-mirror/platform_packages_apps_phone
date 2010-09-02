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

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.sip.SipPhone;
import com.android.phone.sip.SipSettings;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.sip.SipAudioCall;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import java.util.List;
import javax.sip.SipException;

/**
 * Broadcast receiver that handles SIP-related intents.
 */
public class SipBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = SipBroadcastReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, final Intent intent) {
        String action = intent.getAction();

        if (!SipManager.isVoipSupported(context)) {
            Log.v(TAG, "SIP VOIP not supported: " + action);
            return;
        }

        if (action.equals(SipManager.SIP_INCOMING_CALL_ACTION)) {
            takeCall(intent);
        } else if (action.equals(SipManager.SIP_ADD_PHONE_ACTION)) {
            String localSipUri = intent.getStringExtra(SipManager.LOCAL_URI_KEY);
            Log.v(TAG, "new profile: " + localSipUri);
            SipPhone phone = PhoneFactory.makeSipPhone(localSipUri);
            if (phone != null) {
                CallManager.getInstance().registerPhone(phone);
            }
        } else if (action.equals(SipManager.SIP_REMOVE_PHONE_ACTION)) {
            String localSipUri = intent.getStringExtra(SipManager.LOCAL_URI_KEY);
            Log.v(TAG, "removed profile: " + localSipUri);
            removeSipPhone(localSipUri);
        } else if (action.equals(Intent.ACTION_BOOT_COMPLETED)) {
            Log.v(TAG, "start auto registration");
            registerAllProfiles();
        } else {
            Log.v(TAG, "action not processed: " + action);
            return;
        }
    }

    private void removeSipPhone(String sipUri) {
        for (Object phone : CallManager.getInstance().getAllPhones()) {
            if (phone instanceof SipPhone) {
                if (((SipPhone)phone).getSipUri().equals(sipUri)) {
                    CallManager.getInstance().unregisterPhone((SipPhone)phone);
                    return;
                }
            }
        }
        Log.v(TAG, "Remove phone failed:cannot find phone with uri " + sipUri);
    }

    private void takeCall(Intent intent) {
        Context phoneContext = PhoneApp.getInstance();
        try {
            SipAudioCall sipAudioCall = SipManager.getInstance(phoneContext)
                    .takeAudioCall(phoneContext, intent, null, false);
            for (Object phone : CallManager.getInstance().getAllPhones()) {
                if (phone instanceof SipPhone) {
                   if (((SipPhone) phone).canTake(sipAudioCall)) return;
                }
            }
            Log.v(TAG, "drop SIP call: "
                    + sipAudioCall.getPeerProfile().getUriString());
        } catch (SipException e) {
            Log.e(TAG, "process incoming SIP call", e);
        }
    }

    private void registerAllProfiles() {
        final Context context = PhoneApp.getInstance();
        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.SIP_RECEIVE_CALLS) == 0) {
                return;
            }
        } catch (SettingNotFoundException e) {
            Log.e(TAG, "receive_incoming_call option is not set", e);
        }

        new Thread(new Runnable() {
            public void run() {
                SipManager sipManager = SipManager.getInstance(context);
                List<SipProfile> sipProfileList =
                        SipSettings.retrieveSipListFromDirectory(
                        context.getFilesDir().getAbsolutePath()
                        + SipSettings.PROFILES_DIR);
                for (SipProfile profile : sipProfileList) {
                    try {
                        // TODO: change it to check primary account
                        if (!profile.getAutoRegistration()) continue;
                        sipManager.open(profile,
                                SipManager.SIP_INCOMING_CALL_ACTION, null);
                    } catch (SipException e) {
                        Log.e(TAG, "failed" + profile.getProfileName(), e);
                    }
                }
            }}
        ).start();
    }
}
