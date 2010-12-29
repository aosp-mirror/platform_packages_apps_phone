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
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.sip.SipPhone;
import com.android.phone.sip.SipProfileDb;
import com.android.phone.sip.SipSharedPreferences;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.sip.SipAudioCall;
import android.net.sip.SipException;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

import java.util.List;

/**
 * Broadcast receiver that handles SIP-related intents.
 */
public class SipBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = SipBroadcastReceiver.class.getSimpleName();
    private SipSharedPreferences mSipSharedPreferences;

    @Override
    public void onReceive(Context context, final Intent intent) {
        String action = intent.getAction();

        if (!PhoneUtils.isVoipSupported()) {
            Log.v(TAG, "SIP VOIP not supported: " + action);
            return;
        }
        mSipSharedPreferences = new SipSharedPreferences(context);

        if (action.equals(SipManager.ACTION_SIP_INCOMING_CALL)) {
            takeCall(intent);
        } else if (action.equals(SipManager.ACTION_SIP_ADD_PHONE)) {
            String localSipUri = intent.getStringExtra(SipManager.EXTRA_LOCAL_URI);
            SipPhone phone = PhoneFactory.makeSipPhone(localSipUri);
            if (phone != null) {
                CallManager.getInstance().registerPhone(phone);
            }
            Log.d(TAG, "new phone: " + localSipUri + " #phones="
                    + CallManager.getInstance().getAllPhones().size());
        } else if (action.equals(SipManager.ACTION_SIP_REMOVE_PHONE)) {
            String localSipUri = intent.getStringExtra(SipManager.EXTRA_LOCAL_URI);
            removeSipPhone(localSipUri);
            Log.d(TAG, "removed phone: " + localSipUri + " #phones="
                    + CallManager.getInstance().getAllPhones().size());
        } else if (action.equals(SipManager.ACTION_SIP_SERVICE_UP)) {
            Log.v(TAG, "start auto registration");
            registerAllProfiles();
        } else {
            Log.v(TAG, "action not processed: " + action);
            return;
        }
    }

    private void removeSipPhone(String sipUri) {
        for (Phone phone : CallManager.getInstance().getAllPhones()) {
            if (phone.getPhoneType() == Phone.PHONE_TYPE_SIP) {
                if (((SipPhone) phone).getSipUri().equals(sipUri)) {
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
            SipAudioCall sipAudioCall = SipManager.newInstance(phoneContext)
                    .takeAudioCall(intent, null);
            for (Phone phone : CallManager.getInstance().getAllPhones()) {
                if (phone.getPhoneType() == Phone.PHONE_TYPE_SIP) {
                   if (((SipPhone) phone).canTake(sipAudioCall)) return;
                }
            }
            Log.v(TAG, "drop SIP call: " + intent);
        } catch (SipException e) {
            Log.e(TAG, "process incoming SIP call", e);
        }
    }

    private void registerAllProfiles() {
        final Context context = PhoneApp.getInstance();
        new Thread(new Runnable() {
            public void run() {
                SipManager sipManager = SipManager.newInstance(context);
                SipProfileDb profileDb = new SipProfileDb(context);
                List<SipProfile> sipProfileList =
                        profileDb.retrieveSipProfileList();
                for (SipProfile profile : sipProfileList) {
                    try {
                        if (!profile.getAutoRegistration() &&
                                !profile.getUriString().equals(
                                mSipSharedPreferences.getPrimaryAccount())) {
                            continue;
                        }
                        sipManager.open(profile,
                                SipUtil.createIncomingCallPendingIntent(),
                                null);
                    } catch (SipException e) {
                        Log.e(TAG, "failed" + profile.getProfileName(), e);
                    }
                }
            }}
        ).start();
    }
}
