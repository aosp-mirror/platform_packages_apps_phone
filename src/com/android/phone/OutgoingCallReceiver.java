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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;
import android.util.Config;
import android.util.Log;
import com.android.internal.telephony.Phone;

/**
 * OutgoingCallReceiver receives NEW_OUTGOING_CALL broadcasts from
 * OutgoingCallBroadcaster, and passes them on to InCallScreen, possibly with
 * a modified phone number and optional network provider's badge.
 */
public class OutgoingCallReceiver extends BroadcastReceiver {

    private static final String TAG = "OutgoingCallReceiver";
    private static final boolean LOGV = Config.LOGV;

    public void onReceive(Context context, Intent intent) {
        boolean alreadyCalled;
        String number;
        String originalUri;

        if (LOGV) Log.v(TAG, "Receiving intent " + intent + ".");

        alreadyCalled = intent.getBooleanExtra(
                OutgoingCallBroadcaster.EXTRA_ALREADY_CALLED, false);
        if (alreadyCalled) {
            if (LOGV) Log.v(TAG, "CALL already placed -- returning.");
            return;
        }

        number = getResultData();
        final PhoneApp app = PhoneApp.getInstance();
        if (app.phone.getPhoneName().equals("CDMA")) {
            boolean activateState = (app.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION);
            boolean dialogState = (app.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_SUCCESS_FAILURE_DLG);
            boolean isOtaCallActive = false;

            if ((app.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_PROGRESS)
                    || (app.cdmaOtaScreenState.otaScreenState
                    == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_LISTENING)) {
                isOtaCallActive = true;
            }

            if (activateState || dialogState) {
                if (dialogState) app.dismissOtaDialogs();
                app.clearOtaState();
                app.clearInCallScreenMode();
            } else if (isOtaCallActive) {
                if (LOGV) Log.v(TAG, "OTA call is active, a 2nd CALL cancelled -- returning.");
                return;
            }
        }

        if (number == null) {
            if (LOGV) Log.v(TAG, "CALL cancelled -- returning.");
            return;
        } else if ((app.phone.getPhoneName().equals("CDMA"))
                && ((app.phone.getState() != Phone.State.IDLE)
                && (app.phone.isOtaSpNumber(number)))) {
            if (LOGV) Log.v(TAG, "Call is active, a 2nd OTA call cancelled -- returning.");
            return;
        } else if (PhoneNumberUtils.isEmergencyNumber(number)) {
            Log.w(TAG, "Cannot modify outgoing call to emergency number " + number + ".");
            return;
        }

        originalUri = intent.getStringExtra(
                OutgoingCallBroadcaster.EXTRA_ORIGINAL_URI);
        if (originalUri == null) {
            Log.e(TAG, "Intent is missing EXTRA_ORIGINAL_URI -- returning.");
            return;
        }

        Uri uri = Uri.parse(originalUri);

        if (LOGV) Log.v(TAG, "CALL to " + number + " proceeding.");

        Intent newIntent = new Intent(Intent.ACTION_CALL, uri);
        newIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);

        PhoneUtils.copyPhoneProviderExtras(intent, newIntent);

        newIntent.setClass(context, InCallScreen.class);
        newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        context.startActivity(newIntent);
    }
}
