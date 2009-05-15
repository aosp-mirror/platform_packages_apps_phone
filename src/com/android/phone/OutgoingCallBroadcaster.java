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
import android.os.Bundle;
import android.util.Config;
import com.android.internal.telephony.Phone;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

/**
 * OutgoingCallBroadcaster receives CALL Intents and sends out broadcast
 * Intents that allow other applications to monitor, redirect, or prevent
 * the outgoing call.  If not aborted, the broadcasts will reach
 * {@link OutgoingCallReceiver}, and be passed on to {@link InCallScreen}.
 *
 * Emergency calls and calls to voicemail when no number is present are
 * exempt from being broadcast.
 */
public class OutgoingCallBroadcaster extends Activity {

    private static final String PERMISSION = android.Manifest.permission.PROCESS_OUTGOING_CALLS;
    private static final String TAG = "OutgoingCallBroadcaster";
    private static final boolean LOGV = Config.LOGV;

    public static final String EXTRA_ALREADY_CALLED = "android.phone.extra.ALREADY_CALLED";
    public static final String EXTRA_ORIGINAL_URI = "android.phone.extra.ORIGINAL_URI";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        if (LOGV) Log.v(TAG, "onResume: Got intent " + intent + ".");

        String action = intent.getAction();
        String number = PhoneNumberUtils.getNumberFromIntent(intent, this);
        if (number != null) {
            number = PhoneNumberUtils.convertKeypadLettersToDigits(number);
            number = PhoneNumberUtils.stripSeparators(number);
        }
        final boolean emergencyNumber =
                (number != null) && PhoneNumberUtils.isEmergencyNumber(number);

        boolean callNow;

        if (getClass().getName().equals(intent.getComponent().getClassName())) {
            // If we were launched directly from the OutgoingCallBroadcaster,
            // not one of its more privileged aliases, then make sure that
            // only the non-privileged actions are allowed.
            if (!Intent.ACTION_CALL.equals(intent.getAction())) {
                Log.w(TAG, "Attempt to deliver non-CALL action; forcing to CALL");
                intent.setAction(Intent.ACTION_CALL);
            }
        }
        
        /* Change CALL_PRIVILEGED into CALL or CALL_EMERGENCY as needed. */
        if (Intent.ACTION_CALL_PRIVILEGED.equals(action)) {
            action = emergencyNumber
                    ? Intent.ACTION_CALL_EMERGENCY
                    : Intent.ACTION_CALL;
            intent.setAction(action);
        }

        if (Intent.ACTION_CALL.equals(action)) {
            if (emergencyNumber) {
                Log.w(TAG, "Cannot call emergency number " + number
                        + " with CALL Intent " + intent + ".");
                finish();
                return;
            }
            callNow = false;
        } else if (Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            if (!emergencyNumber) {
                Log.w(TAG, "Cannot call non-emergency number " + number
                        + " with EMERGENCY_CALL Intent " + intent + ".");
                finish();
                return;
            }
            callNow = true;
        } else {
            Log.e(TAG, "Unhandled Intent " + intent + ".");
            finish();
            return;
        }

        // Make sure the screen is turned on.  This is probably the right
        // thing to do, and more importantly it works around an issue in the
        // activity manager where we will not launch activities consistently
        // when the screen is off (since it is trying to keep them paused
        // and has...  issues).
        //
        // Also, this ensures the device stays awake while doing the following
        // broadcast; technically we should be holding a wake lock here
        // as well.
        PhoneApp.getInstance().wakeUpScreen();
        
        /* If number is null, we're probably trying to call a non-existent voicemail number or
         * something else fishy.  Whatever the problem, there's no number, so there's no point
         * in allowing apps to modify the number. */
        if (number == null) callNow = true;

        if (callNow) {
            intent.setClass(this, InCallScreen.class);
            startActivity(intent);
        }

        Intent broadcastIntent = new Intent(Intent.ACTION_NEW_OUTGOING_CALL);
        if (number != null) broadcastIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        broadcastIntent.putExtra(EXTRA_ALREADY_CALLED, callNow);
        broadcastIntent.putExtra(EXTRA_ORIGINAL_URI, intent.getData().toString());
        if (LOGV) Log.v(TAG, "Broadcasting intent " + broadcastIntent + ".");
        sendOrderedBroadcast(broadcastIntent, PERMISSION, null, null,
                             Activity.RESULT_OK, number, null);

        finish();
    }

}
