/*
 * Copyright (C) 2011 The Android Open Source Project
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

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemProperties;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;

import java.util.Arrays;

/**
 * Implementation of the "Respond via SMS" feature for incoming calls.
 */
public class RespondViaSms {
    private static final String TAG = "RespondViaSms";
    private static final boolean DBG = true;
    // STOPSHIP: reduce DBG to
    //       (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /** This class is never instantiated. */
    private RespondViaSms() {
    }

    /**
     * Brings up the "Respond via SMS" popup for an incoming call.
     *
     * @param inCallScreen the InCallScreen instance
     * @param ringingCall the current incoming call
     * @return the resulting Dialog
     */
    public static Dialog showRespondViaSmsPopup(InCallScreen inCallScreen,
                                                Call ringingCall) {
        if (DBG) log("showRespondViaSmsPopup()...");

        ListView lv = new ListView(inCallScreen);

        // Load the canned responses come from an array resource.
        // TODO: This will eventually come from a SharedPreferences, since
        // the responses need to be customizable.  (Ultimately the
        // respond_via_sms_canned_responses strings will only be used as
        // default values.)
        String[] responses = inCallScreen.getResources()
                .getStringArray(R.array.respond_via_sms_canned_responses);

        // And manually add "Custom message..." as the last choice.
        int numPopupItems = responses.length + 1;
        String[] popupItems = Arrays.copyOf(responses, numPopupItems);
        popupItems[numPopupItems - 1] = inCallScreen.getResources()
                .getString(R.string.respond_via_sms_custom_message);

        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(inCallScreen,
                                         android.R.layout.simple_list_item_1,
                                         android.R.id.text1,
                                         popupItems);
        lv.setAdapter(adapter);

        // Create a RespondViaSmsItemClickListener instance to handle item
        // clicks from the popup.
        // (Note we create a fresh instance for each incoming call, and
        // stash away the call's phone number, since we can't necessarily
        // assume this call will still be ringing when the user finally
        // chooses a response.)

        Connection c = ringingCall.getLatestConnection();
        if (DBG) log("- connection: " + c);

        // TODO: at this point we probably should re-check c.getAddress()
        // and c.getNumberPresentation() for validity.  (i.e. recheck the
        // same cases in InCallTouchUi.showIncomingCallWidget() where we
        // should have disallowed the "respond via SMS" feature in the
        // first place.)

        String phoneNumber = c.getAddress();
        if (DBG) log("- phoneNumber: " + phoneNumber);  // STOPSHIP: don't log PII
        lv.setOnItemClickListener(new RespondViaSmsItemClickListener(inCallScreen, phoneNumber));

        AlertDialog.Builder builder = new AlertDialog.Builder(inCallScreen)
                .setCancelable(true)
                .setOnCancelListener(new RespondViaSmsCancelListener(inCallScreen))
                .setView(lv);
        Dialog dialog = builder.create();
        dialog.show();

        return dialog;
    }

    /**
     * OnItemClickListener for the "Respond via SMS" popup.
     */
    public static class RespondViaSmsItemClickListener implements AdapterView.OnItemClickListener {
        // Reference back to the InCallScreen instance.
        private InCallScreen mInCallScreen;

        // Phone number to send the SMS to.
        private String mPhoneNumber;

        public RespondViaSmsItemClickListener(InCallScreen inCallScreen, String phoneNumber) {
            mInCallScreen = inCallScreen;
            mPhoneNumber = phoneNumber;
        }

        /**
         * Handles the user selecting an item from the popup.
         */
        public void onItemClick(AdapterView<?> parent,  // The ListView
                                View view,  // The TextView that was clicked
                                int position,
                                long id) {
            if (DBG) log("RespondViaSmsItemClickListener.onItemClick(" + position + ")...");
            String message = (String) parent.getItemAtPosition(position);
            if (DBG) log("- message: '" + message + "'");

            // The "Custom" choice is a special case.
            // (For now, it's guaranteed to be the last item.)
            if (position == (parent.getCount() - 1)) {
                // Take the user to the standard SMS compose UI.
                launchSmsCompose(mInCallScreen, mPhoneNumber);
            } else {
                // Send the selected message immediately with no user interaction.
                sendText(mInCallScreen, mPhoneNumber, message);
            }

            PhoneApp.getInstance().dismissCallScreen();
        }
    }

    /**
     * OnCancelListener for the "Respond via SMS" popup.
     */
    public static class RespondViaSmsCancelListener implements DialogInterface.OnCancelListener {
        // Reference back to the InCallScreen instance.
        private InCallScreen mInCallScreen;

        public RespondViaSmsCancelListener(InCallScreen inCallScreen) {
            mInCallScreen = inCallScreen;
        }

        /**
         * Handles the user canceling the popup, either by touching
         * outside the popup or by pressing Back.
         */
        public void onCancel(DialogInterface dialog) {
            if (DBG) log("RespondViaSmsCancelListener.onCancel()...");

            // If the user cancels the popup, this presumably means that
            // they didn't actually mean to bring up the "Respond via SMS"
            // UI in the first place (and instead want to go back to the
            // state where they can either answer or reject the call.)
            // So restart the ringer and bring back the regular incoming
            // call UI.

            // This will have no effect if the incoming call isn't still ringing.
            PhoneApp.getInstance().notifier.restartRinger();

            // We hid the MultiWaveView widget way back in
            // InCallTouchUi.onTrigger(), when the user first selected
            // the "SMS" trigger.
            //
            // To bring it back, just force the entire InCallScreen to
            // update itself based on the current telephony state.
            // (Assuming the incoming call is still ringing, this will
            // cause the incoming call widget to reappear.)
            mInCallScreen.requestUpdateScreen();
        }
    }

    /**
     * Sends a text message without any interaction from the user.
     */
    private static void sendText(Context context, String phoneNumber, String message) {
        // STOPSHIP: disable all logging of PII (everywhere in this file)
        if (DBG) log("sendText: number "
                     + phoneNumber + ", message '" + message + "'");

        // TODO: This code should use the new
        //   com.android.mms.intent.action.SENDTO_NO_CONFIRMATION
        // intent once change https://android-git.corp.google.com/g/114664
        // gets checked in.
        // But use the old-school SmsManager API for now.

        final SmsManager smsManager = SmsManager.getDefault();
        smsManager.sendTextMessage(phoneNumber,
                                   null /* scAddress; null means "use default" */,
                                   message,
                                   null /* sentIntent */,
                                   null /* deliveryIntent */);
    }

    /**
     * Brings up the standard SMS compose UI.
     */
    private static void launchSmsCompose(Context context, String phoneNumber) {
        if (DBG) log("launchSmsCompose: number " + phoneNumber);

        // TODO: confirm with SMS guys that this is the correct intent to use.
        Uri uri = Uri.fromParts(Constants.SCHEME_SMS, phoneNumber, null);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);

        if (DBG) log("- Launching SMS compose UI: " + intent);
        context.startActivity(intent);

        // TODO: One open issue here: if the user selects "Custom message"
        // for an incoming call while the device was locked, and the user
        // does *not* have a secure keyguard set, we bring up the
        // non-secure keyguard at this point :-(
        // Instead, we should immediately go to the SMS compose UI.
        //
        // I *believe* the fix is for the SMS compose activity to set the
        // FLAG_DISMISS_KEYGUARD window flag (which will cause the
        // keyguard to be dismissed *only* if it is not a secure lock
        // keyguard.)
        //
        // But it there an equivalent way for me to accomplish that here,
        // without needing to change the SMS app?
        //
        // In any case, I'm pretty sure the SMS UI should *not* to set
        // FLAG_SHOW_WHEN_LOCKED, since we do want the force the user to
        // enter their lock pattern or PIN at this point if they have a
        // secure keyguard set.
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
