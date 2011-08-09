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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;

import android.app.ActionBar;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.Arrays;

/**
 * Helper class to manage the "Respond via SMS" feature for incoming calls.
 * @see InCallScreen.internalRespondViaSms()
 */
public class RespondViaSmsManager {
    private static final String TAG = "RespondViaSmsManager";
    private static final boolean DBG = true;
    // STOPSHIP: reduce DBG to
    //       (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    /**
     * The popup showing the list of canned responses.
     *
     * This is an AlertDialog containing a ListView showing the possible
     * choices.  This may be null if the InCallScreen hasn't ever called
     * showRespondViaSmsPopup() yet, or if the popup was visible once but
     * then got dismissed.
     */
    private Dialog mPopup;

    /** The array of "canned responses"; see loadCannedResponses(). */
    private String[] mCannedResponses;

    /** SharedPreferences file name for our persistent settings. */
    private static final String SHARED_PREFERENCES_NAME = "respond_via_sms_prefs";

    // Preference keys for the 4 "canned responses"; see RespondViaSmsManager$Settings.
    // Since (for now at least) the number of messages is fixed at 4, and since
    // SharedPreferences can't deal with arrays anyway, just store the messages
    // as 4 separate strings.
    private static final int NUM_CANNED_RESPONSES = 4;
    private static final String KEY_CANNED_RESPONSE_PREF_1 = "canned_response_pref_1";
    private static final String KEY_CANNED_RESPONSE_PREF_2 = "canned_response_pref_2";
    private static final String KEY_CANNED_RESPONSE_PREF_3 = "canned_response_pref_3";
    private static final String KEY_CANNED_RESPONSE_PREF_4 = "canned_response_pref_4";


    /**
     * RespondViaSmsManager constructor.
     */
    public RespondViaSmsManager() {
    }

    public void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    /**
     * Brings up the "Respond via SMS" popup for an incoming call.
     *
     * @param ringingCall the current incoming call
     */
    public void showRespondViaSmsPopup(Call ringingCall) {
        if (DBG) log("showRespondViaSmsPopup()...");

        ListView lv = new ListView(mInCallScreen);

        // Refresh the array of "canned responses".
        // TODO: don't do this here in the UI thread!  (This lookup is very
        // cheap, but it's still a StrictMode violation.  See the TODO comment
        // following loadCannedResponses() for more info.)
        mCannedResponses = loadCannedResponses();

        // Build the list: start with the canned responses, but manually add
        // "Custom message..." as the last choice.
        int numPopupItems = mCannedResponses.length + 1;
        String[] popupItems = Arrays.copyOf(mCannedResponses, numPopupItems);
        popupItems[numPopupItems - 1] = mInCallScreen.getResources()
                .getString(R.string.respond_via_sms_custom_message);

        ArrayAdapter<String> adapter =
                new ArrayAdapter<String>(mInCallScreen,
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

        if (c == null) {
            // Uh oh -- the "ringingCall" doesn't have any connections any more.
            // (In other words, it's no longer ringing.)  This is rare, but can
            // happen if the caller hangs up right at the exact moment the user
            // selects the "Respond via SMS" option.
            // There's nothing to do here (since the incoming call is gone),
            // so just bail out.
            Log.i(TAG, "showRespondViaSmsPopup: null connection; bailing out...");
            return;
        }

        // TODO: at this point we probably should re-check c.getAddress()
        // and c.getNumberPresentation() for validity.  (i.e. recheck the
        // same cases in InCallTouchUi.showIncomingCallWidget() where we
        // should have disallowed the "respond via SMS" feature in the
        // first place.)

        String phoneNumber = c.getAddress();
        if (DBG) log("- phoneNumber: " + phoneNumber);  // STOPSHIP: don't log PII
        lv.setOnItemClickListener(new RespondViaSmsItemClickListener(phoneNumber));

        AlertDialog.Builder builder = new AlertDialog.Builder(mInCallScreen)
                .setCancelable(true)
                .setOnCancelListener(new RespondViaSmsCancelListener())
                .setView(lv);
        mPopup = builder.create();
        mPopup.show();
    }

    /**
     * Dismiss the "Respond via SMS" popup if it's visible.
     *
     * This is safe to call even if the popup is already dismissed, and
     * even if you never called showRespondViaSmsPopup() in the first
     * place.
     */
    public void dismissPopup() {
        if (mPopup != null) {
            mPopup.dismiss();  // safe even if already dismissed
            mPopup = null;
        }
    }

    /**
     * OnItemClickListener for the "Respond via SMS" popup.
     */
    public class RespondViaSmsItemClickListener implements AdapterView.OnItemClickListener {
        // Phone number to send the SMS to.
        private String mPhoneNumber;

        public RespondViaSmsItemClickListener(String phoneNumber) {
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
                launchSmsCompose(mPhoneNumber);
            } else {
                // Send the selected message immediately with no user interaction.
                sendText(mPhoneNumber, message);
            }

            // At this point the user is done dealing with the incoming call, so
            // there's no reason to keep it around.  (It's also confusing for
            // the "incoming call" icon in the status bar to still be visible.)
            // So reject the call now.
            mInCallScreen.hangupRingingCall();

            PhoneApp.getInstance().dismissCallScreen();
        }
    }

    /**
     * OnCancelListener for the "Respond via SMS" popup.
     */
    public class RespondViaSmsCancelListener implements DialogInterface.OnCancelListener {
        public RespondViaSmsCancelListener() {
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
    private void sendText(String phoneNumber, String message) {
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
    private void launchSmsCompose(String phoneNumber) {
        if (DBG) log("launchSmsCompose: number " + phoneNumber);

        // TODO: confirm with SMS guys that this is the correct intent to use.
        Uri uri = Uri.fromParts(Constants.SCHEME_SMS, phoneNumber, null);
        Intent intent = new Intent(Intent.ACTION_VIEW, uri);

        if (DBG) log("- Launching SMS compose UI: " + intent);
        mInCallScreen.startActivity(intent);

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


    /**
     * Settings activity under "Call settings" to let you manage the
     * canned responses; see respond_via_sms_settings.xml
     */
    public static class Settings extends PreferenceActivity
            implements Preference.OnPreferenceChangeListener {
        @Override
        protected void onCreate(Bundle icicle) {
            super.onCreate(icicle);
            if (DBG) log("Settings: onCreate()...");

            getPreferenceManager().setSharedPreferencesName(SHARED_PREFERENCES_NAME);

            // This preference screen is ultra-simple; it's just 4 plain
            // <EditTextPreference>s, one for each of the 4 "canned responses".
            //
            // The only nontrivial thing we do here is copy the text value of
            // each of those EditTextPreferences and use it as the preference's
            // "title" as well, so that the user will immediately see all 4
            // strings when they arrive here.
            //
            // Also, listen for change events (since we'll need to update the
            // title any time the user edits one of the strings.)

            addPreferencesFromResource(R.xml.respond_via_sms_settings);

            EditTextPreference pref;
            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_1);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_2);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_3);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            pref = (EditTextPreference) findPreference(KEY_CANNED_RESPONSE_PREF_4);
            pref.setTitle(pref.getText());
            pref.setOnPreferenceChangeListener(this);

            ActionBar actionBar = getActionBar();
            if (actionBar != null) {
                // android.R.id.home will be triggered in onOptionsItemSelected()
                actionBar.setDisplayHomeAsUpEnabled(true);
            }
        }

        // Preference.OnPreferenceChangeListener implementation
        public boolean onPreferenceChange(Preference preference, Object newValue) {
            if (DBG) log("onPreferenceChange: key = " + preference.getKey());
            if (DBG) log("  preference = '" + preference + "'");
            if (DBG) log("  newValue = '" + newValue + "'");

            EditTextPreference pref = (EditTextPreference) preference;

            // Copy the new text over to the title, just like in onCreate().
            // (Watch out: onPreferenceChange() is called *before* the
            // Preference itself gets updated, so we need to use newValue here
            // rather than pref.getText().)
            pref.setTitle((String) newValue);

            return true;  // means it's OK to update the state of the Preference with the new value
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {
            final int itemId = item.getItemId();
            if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
                CallFeaturesSetting.goUpToTopLevelSetting(this);
                return true;
            }
            return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Read the (customizable) canned responses from SharedPreferences,
     * or from defaults if the user has never actually brought up
     * the Settings UI.
     *
     * This method does disk I/O (reading the SharedPreferences file)
     * so don't call it from the main thread.
     *
     * @see RespondViaSmsManager$Settings
     */
    private String[] loadCannedResponses() {
        if (DBG) log("loadCannedResponses()...");

        SharedPreferences prefs =
                mInCallScreen.getSharedPreferences(SHARED_PREFERENCES_NAME,
                                                   Context.MODE_PRIVATE);
        final Resources res = mInCallScreen.getResources();

        String[] responses = new String[NUM_CANNED_RESPONSES];

        // Note the default values here must agree with the corresponding
        // android:defaultValue attributes in respond_via_sms_settings.xml.

        responses[0] = prefs.getString(KEY_CANNED_RESPONSE_PREF_1,
                                       res.getString(R.string.respond_via_sms_canned_response_1));
        responses[1] = prefs.getString(KEY_CANNED_RESPONSE_PREF_2,
                                       res.getString(R.string.respond_via_sms_canned_response_2));
        responses[2] = prefs.getString(KEY_CANNED_RESPONSE_PREF_3,
                                       res.getString(R.string.respond_via_sms_canned_response_3));
        responses[3] = prefs.getString(KEY_CANNED_RESPONSE_PREF_4,
                                       res.getString(R.string.respond_via_sms_canned_response_4));
        return responses;
    }

    // TODO: Don't call loadCannedResponses() from the UI thread.
    //
    // We should either (1) kick off a background task when the call first
    // starts ringing (probably triggered from the InCallScreen
    // onNewRingingConnection() method) which would run loadCannedResponses()
    // and stash the result away in mCannedResponses, or (2) use an
    // OnSharedPreferenceChangeListener to listen for changes to this
    // SharedPreferences instance, and use that to kick off the background task.
    //
    // In either case:
    //
    // - Make sure we recover sanely if mCannedResponses is still null when it's
    //   actually time to show the popup (i.e. if the background task was too
    //   slow, or if the background task never got started for some reason)
    //
    // - Make sure that all setting and getting of mCannedResponses happens
    //   inside a synchronized block
    //
    // - If we kick off the background task when the call first starts ringing,
    //   consider delaying that until the incoming-call UI actually comes to the
    //   foreground; this way we won't steal any CPU away from the caller-id
    //   query.  Maybe do it from InCallScreen.onResume()?
    //   Or InCallTouchUi.showIncomingCallWidget()?


    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
