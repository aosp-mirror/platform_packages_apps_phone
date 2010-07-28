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

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.android.internal.telephony.Phone;

/**
 * Trampoline activity that handles the com.android.phone.PERFORM_CDMA_PROVISIONING intent.
 * This activity is protected by the android.permission.PERFORM_CDMA_PROVISIONING permission.
 *
 * On regular phones, we simply redirect to the InCallScreen, which will
 * handle the PERFORM_CDMA_PROVISIONING action by launching an OTASP call.
 *
 * On data-only devices, though, we manually launch the OTASP call without
 * displaying the in-call UI, and then immediately finish(), so that our
 * caller (presumably the SetupWizard) can display some sort of progress UI.
 *
 * TODO: The class name InCallScreenShowActivation is misleading, since
 * the InCallScreen is not involved at all in CDMA provisioning on some
 * devices.  Let's eventually rename this to something like
 * CdmaProvisioningLauncher or CdmaProvisioningHandler...
 */
public class InCallScreenShowActivation extends Activity {
    private static final String LOG_TAG = "InCallScreenShowActivation";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        Log.d(LOG_TAG, "onCreate: intent = " + intent);
        Bundle extras = intent.getExtras();
        if (extras != null) {
            Log.i(LOG_TAG, "      - has extras: size = " + extras.size()); // forces an unparcel()
            Log.i(LOG_TAG, "      - extras = " + extras);
        }

        PhoneApp app = PhoneApp.getInstance();
        Phone phone = app.getPhone();
        if (!TelephonyCapabilities.supportsOtasp(phone)) {
            Log.w(LOG_TAG, "CDMA Provisioning not supported on this device");
            setResult(RESULT_CANCELED);
            finish();
            return;
        }

        if (intent.getAction().equals(OtaUtils.ACTION_PERFORM_CDMA_PROVISIONING)) {

            // On voice-capable devices, we perform CDMA provisioning in
            // "interactive" mode by directly launching the InCallScreen.

            // The voice_capable flag currently comes from a resource,
            // which is overrideable on a per-product basis.
            // TODO: this resource needs to be used from apps/Contacts
            // also, so we'll probably move it into the framework at some
            // point...
            boolean interactiveMode = getResources().getBoolean(R.bool.voice_capable);

            // TODO: Or, this may eventually become a PackageManager "system
            // feature", in which case we'd do something like this instead:
            // boolean interactiveMode =
            //   getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY_VOICE_CALLS);

            Log.d(LOG_TAG, "- interactiveMode = " + interactiveMode);

            // Testing: check the intent extra that allows the caller to
            // manually enable/disable "interactive mode", regardless of
            // whether the current device is voice-capable.
            // STOPSHIP: disable before release.
            if (intent.hasExtra(OtaUtils.EXTRA_OVERRIDE_INTERACTIVE_MODE)) {
                interactiveMode =
                        intent.getBooleanExtra(OtaUtils.EXTRA_OVERRIDE_INTERACTIVE_MODE, false);
                Log.d(LOG_TAG, "===> MANUALLY OVERRIDING interactiveMode to " + interactiveMode);
            }

            // We allow the caller to pass a PendingIntent (as the
            // EXTRA_NONINTERACTIVE_OTASP_RESULT_PENDING_INTENT extra)
            // which we'll later use to notify them when the OTASP call
            // fails or succeeds.
            //
            // Stash that away here, and we'll fire it off later in
            // OtaUtils.sendOtaspResult().
            app.cdmaOtaScreenState.otaspResultCodePendingIntent =
                        (PendingIntent) intent.getParcelableExtra(
                                OtaUtils.EXTRA_OTASP_RESULT_CODE_PENDING_INTENT);

            if (interactiveMode) {
                // On voice-capable devices, immediately bring up the in-call
                // UI.  (By passing along the ACTION_PERFORM_CDMA_PROVISIONING
                // intent, this will cause the InCallScreen to launch an OTASP
                // call.)

                Intent newIntent = new Intent().setClass(this, InCallScreen.class)
                        .setAction(OtaUtils.ACTION_PERFORM_CDMA_PROVISIONING);

                Log.d(LOG_TAG, "==> Launching in-call UI for CDMA provisioning: " + newIntent);
                startActivity(newIntent);

                // Note the result we set here is actually irrelevant, since
                // the InCallScreen's "interactive" OTASP sequence never
                // actually finish()es; it ends by directly launching the Home
                // activity.  So our caller won't actually ever get an
                // onActivityResult() call in this case.
                setResult(OtaUtils.RESULT_INTERACTIVE_OTASP_STARTED);
            } else {
                // On data-only devices, manually launch the OTASP call
                // *without* displaying any UI.  (Our caller, presumably
                // SetupWizardActivity, is responsible for displaying some
                // sort of progress UI.)

                Log.d(LOG_TAG, "==> Starting non-interactive CDMA provisioning...");
                int callStatus = OtaUtils.startNonInteractiveOtasp(this);

                if (callStatus == PhoneUtils.CALL_STATUS_DIALED) {
                    Log.d(LOG_TAG, "  ==> successful result from startNonInteractiveOtasp(): "
                          + callStatus);
                    setResult(OtaUtils.RESULT_NONINTERACTIVE_OTASP_STARTED);
                } else {
                    Log.w(LOG_TAG, "Failure code from startNonInteractiveOtasp(): " + callStatus);
                    setResult(OtaUtils.RESULT_NONINTERACTIVE_OTASP_FAILED);
                }
            }
        } else {
            Log.e(LOG_TAG, "Unexpected intent action: " + intent);
            setResult(RESULT_CANCELED);
        }

        finish();
    }

}
