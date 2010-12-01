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
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.telephony.Phone;

/**
 * OutgoingCallBroadcaster receives CALL and CALL_PRIVILEGED Intents, and
 * broadcasts the ACTION_NEW_OUTGOING_CALL intent which allows other
 * applications to monitor, redirect, or prevent the outgoing call.

 * After the other applications have had a chance to see the
 * ACTION_NEW_OUTGOING_CALL intent, it finally reaches the
 * {@link OutgoingCallReceiver}, which passes the (possibly modified)
 * intent on to the {@link InCallScreen}.
 *
 * Emergency calls and calls where no number is present (like for a CDMA
 * "empty flash" or a nonexistent voicemail number) are exempt from being
 * broadcast.
 */
public class OutgoingCallBroadcaster extends Activity {

    private static final String PERMISSION = android.Manifest.permission.PROCESS_OUTGOING_CALLS;
    private static final String TAG = "OutgoingCallBroadcaster";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    public static final String EXTRA_ALREADY_CALLED = "android.phone.extra.ALREADY_CALLED";
    public static final String EXTRA_ORIGINAL_URI = "android.phone.extra.ORIGINAL_URI";
    public static final String EXTRA_NEW_CALL_INTENT = "android.phone.extra.NEW_CALL_INTENT";
    public static final String EXTRA_SIP_PHONE_URI = "android.phone.extra.SIP_PHONE_URI";

    /**
     * Identifier for intent extra for sending an empty Flash message for
     * CDMA networks. This message is used by the network to simulate a
     * press/depress of the "hookswitch" of a landline phone. Aka "empty flash".
     *
     * TODO: Receiving an intent extra to tell the phone to send this flash is a
     * temporary measure. To be replaced with an external ITelephony call in the future.
     * TODO: Keep in sync with the string defined in TwelveKeyDialer.java in Contacts app
     * until this is replaced with the ITelephony API.
     */
    public static final String EXTRA_SEND_EMPTY_FLASH = "com.android.phone.extra.SEND_EMPTY_FLASH";

    /**
     * OutgoingCallReceiver finishes NEW_OUTGOING_CALL broadcasts, starting
     * the InCallScreen if the broadcast has not been canceled, possibly with
     * a modified phone number and optional provider info (uri + package name + remote views.)
     */
    public class OutgoingCallReceiver extends BroadcastReceiver {
        private static final String TAG = "OutgoingCallReceiver";

        public void onReceive(Context context, Intent intent) {
            doReceive(context, intent);
            finish();
        }

        public void doReceive(Context context, Intent intent) {
            if (DBG) Log.v(TAG, "doReceive: " + intent);

            boolean alreadyCalled;
            String number;
            String originalUri;

            alreadyCalled = intent.getBooleanExtra(
                    OutgoingCallBroadcaster.EXTRA_ALREADY_CALLED, false);
            if (alreadyCalled) {
                if (DBG) Log.v(TAG, "CALL already placed -- returning.");
                return;
            }

            number = getResultData();
            final PhoneApp app = PhoneApp.getInstance();

            if (TelephonyCapabilities.supportsOtasp(app.phone)) {
                boolean activateState = (app.cdmaOtaScreenState.otaScreenState
                        == OtaUtils.CdmaOtaScreenState.OtaScreenState.OTA_STATUS_ACTIVATION);
                boolean dialogState = (app.cdmaOtaScreenState.otaScreenState
                        == OtaUtils.CdmaOtaScreenState.OtaScreenState
                        .OTA_STATUS_SUCCESS_FAILURE_DLG);
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
                    if (DBG) Log.v(TAG, "OTA call is active, a 2nd CALL cancelled -- returning.");
                    return;
                }
            }

            if (number == null) {
                if (DBG) Log.v(TAG, "CALL cancelled (null number), returning...");
                return;
            } else if (TelephonyCapabilities.supportsOtasp(app.phone)
                    && (app.phone.getState() != Phone.State.IDLE)
                    && (app.phone.isOtaSpNumber(number))) {
                if (DBG) Log.v(TAG, "Call is active, a 2nd OTA call cancelled -- returning.");
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

            // Since the number could be modified/rewritten by the broadcast,
            // we have to strip the unwanted characters here.
            number = PhoneNumberUtils.stripSeparators(
                    PhoneNumberUtils.convertKeypadLettersToDigits(number));

            if (DBG) Log.v(TAG, "CALL to " + /*number*/ "xxxxxxx" + " proceeding.");

            startSipCallOptionsHandler(context, intent, uri, number);
        }
    }

    private void startSipCallOptionsHandler(Context context, Intent intent,
            Uri uri, String number) {
        Intent newIntent = new Intent(Intent.ACTION_CALL, uri);
        newIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);

        PhoneUtils.checkAndCopyPhoneProviderExtras(intent, newIntent);

        newIntent.setClass(context, InCallScreen.class);
        newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        Intent selectPhoneIntent = new Intent(EXTRA_NEW_CALL_INTENT, uri);
        selectPhoneIntent.setClass(context, SipCallOptionHandler.class);
        selectPhoneIntent.putExtra(EXTRA_NEW_CALL_INTENT, newIntent);
        selectPhoneIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (DBG) Log.v(TAG, "startSipCallOptionsHandler(): " +
                "calling startActivity: " + selectPhoneIntent);
        context.startActivity(selectPhoneIntent);
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        final Configuration configuration = getResources().getConfiguration();

        if (DBG) Log.v(TAG, "onCreate: this = " + this + ", icicle = " + icicle);
        if (DBG) Log.v(TAG, " - getIntent() = " + intent);
        if (DBG) Log.v(TAG, " - configuration = " + configuration);

        if (icicle != null) {
            // A non-null icicle means that this activity is being
            // re-initialized after previously being shut down.
            //
            // In practice this happens very rarely (because the lifetime
            // of this activity is so short!), but it *can* happen if the
            // framework detects a configuration change at exactly the
            // right moment; see bug 2202413.
            //
            // In this case, do nothing.  Our onCreate() method has already
            // run once (with icicle==null the first time), which means
            // that the NEW_OUTGOING_CALL broadcast for this new call has
            // already been sent.
            Log.i(TAG, "onCreate: non-null icicle!  "
                  + "Bailing out, not sending NEW_OUTGOING_CALL broadcast...");

            // No need to finish() here, since the OutgoingCallReceiver from
            // our original instance will do that.  (It'll actually call
            // finish() on our original instance, which apparently works fine
            // even though the ActivityManager has already shut that instance
            // down.  And note that if we *do* call finish() here, that just
            // results in an "ActivityManager: Duplicate finish request"
            // warning when the OutgoingCallReceiver runs.)

            return;
        }

        String action = intent.getAction();
        String number = PhoneNumberUtils.getNumberFromIntent(intent, this);
        // Check the number, don't convert for sip uri
        // TODO put uriNumber under PhoneNumberUtils
        if (number != null) {
            if (!PhoneNumberUtils.isUriNumber(number)) {
                number = PhoneNumberUtils.convertKeypadLettersToDigits(number);
                number = PhoneNumberUtils.stripSeparators(number);
            }
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
        // TODO: This code is redundant with some code in InCallScreen: refactor.
        if (Intent.ACTION_CALL_PRIVILEGED.equals(action)) {
            action = emergencyNumber
                    ? Intent.ACTION_CALL_EMERGENCY
                    : Intent.ACTION_CALL;
            if (DBG) Log.v(TAG, "- updating action from CALL_PRIVILEGED to " + action);
            intent.setAction(action);
        }

        if (Intent.ACTION_CALL.equals(action)) {
            if (emergencyNumber) {
                Log.w(TAG, "Cannot call emergency number " + number
                        + " with CALL Intent " + intent + ".");

                Intent invokeFrameworkDialer = new Intent();

                // TwelveKeyDialer is in a tab so we really want
                // DialtactsActivity.  Build the intent 'manually' to
                // use the java resolver to find the dialer class (as
                // opposed to a Context which look up known android
                // packages only)
                invokeFrameworkDialer.setClassName("com.android.contacts",
                                                   "com.android.contacts.DialtactsActivity");
                invokeFrameworkDialer.setAction(Intent.ACTION_DIAL);
                invokeFrameworkDialer.setData(intent.getData());

                if (DBG) Log.v(TAG, "onCreate(): calling startActivity for Dialer: "
                               + invokeFrameworkDialer);
                startActivity(invokeFrameworkDialer);
                finish();
                return;
            }
            callNow = false;
        } else if (Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            // ACTION_CALL_EMERGENCY case: this is either a CALL_PRIVILEGED
            // intent that we just turned into a CALL_EMERGENCY intent (see
            // above), or else it really is an CALL_EMERGENCY intent that
            // came directly from some other app (e.g. the EmergencyDialer
            // activity built in to the Phone app.)
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

        /* If number is null, we're probably trying to call a non-existent voicemail number,
         * send an empty flash or something else is fishy.  Whatever the problem, there's no
         * number, so there's no point in allowing apps to modify the number. */
        if (number == null || TextUtils.isEmpty(number)) {
            if (intent.getBooleanExtra(EXTRA_SEND_EMPTY_FLASH, false)) {
                Log.i(TAG, "onCreate: SEND_EMPTY_FLASH...");
                PhoneUtils.sendEmptyFlash(PhoneApp.getPhone());
                finish();
                return;
            } else {
                Log.i(TAG, "onCreate: null or empty number, setting callNow=true...");
                callNow = true;
            }
        }

        if (callNow) {
            intent.setClass(this, InCallScreen.class);
            if (DBG) Log.v(TAG, "onCreate(): callNow case, calling startActivity: " + intent);
            startActivity(intent);
        }

        // For now, SIP calls will be processed directly without a
        // NEW_OUTGOING_CALL broadcast.
        //
        // TODO: In the future, though, 3rd party apps *should* be allowed to
        // intercept outgoing calls to SIP addresses as well.  To do this, we should
        // (1) update the NEW_OUTGOING_CALL intent documentation to explain this
        // case, and (2) pass the outgoing SIP address by *not* overloading the
        // EXTRA_PHONE_NUMBER extra, but instead using a new separate extra to hold
        // the outgoing SIP address.  (Be sure to document whether it's a URI or just
        // a plain address, whether it could be a tel: URI, etc.)
        Uri uri = intent.getData();
        String scheme = uri.getScheme();
        if ("sip".equals(scheme) || PhoneNumberUtils.isUriNumber(number)) {
            startSipCallOptionsHandler(this, intent, uri, number);
            finish();
            return;
        }

        Intent broadcastIntent = new Intent(Intent.ACTION_NEW_OUTGOING_CALL);
        if (number != null) {
            broadcastIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        }
        PhoneUtils.checkAndCopyPhoneProviderExtras(intent, broadcastIntent);
        broadcastIntent.putExtra(EXTRA_ALREADY_CALLED, callNow);
        broadcastIntent.putExtra(EXTRA_ORIGINAL_URI, uri.toString());

        if (DBG) Log.v(TAG, "Broadcasting intent: " + broadcastIntent + ".");
        sendOrderedBroadcast(broadcastIntent, PERMISSION, new OutgoingCallReceiver(),
                null, Activity.RESULT_OK, number, null);
    }

    // Implement onConfigurationChanged() purely for debugging purposes,
    // to make sure that the android:configChanges element in our manifest
    // is working properly.
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (DBG) Log.v(TAG, "onConfigurationChanged: newConfig = " + newConfig);
    }
}
