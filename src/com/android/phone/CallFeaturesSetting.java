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

import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.cdma.TtyIntent;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.media.AudioManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Contacts.PhonesColumns;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

public class CallFeaturesSetting extends PreferenceActivity
        implements DialogInterface.OnClickListener,
        Preference.OnPreferenceChangeListener,
        EditPhoneNumberPreference.OnDialogClosedListener,
        EditPhoneNumberPreference.GetDefaultNumberListener{

    // intent action for this activity.
    public static final String ACTION_ADD_VOICEMAIL =
        "com.android.phone.CallFeaturesSetting.ADD_VOICEMAIL";

    // debug data
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    // string contants
    private static final String NUM_PROJECTION[] = {PhonesColumns.NUMBER};

    // String keys for preference lookup
    private static final String BUTTON_VOICEMAIL_KEY = "button_voicemail_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";

    private static final String BUTTON_DTMF_KEY   = "button_dtmf_settings";
    private static final String BUTTON_RETRY_KEY  = "button_auto_retry_key";
    private static final String BUTTON_TTY_KEY    = "button_tty_mode_key";
    private static final String BUTTON_HAC_KEY    = "button_hac_key";

    private static final String BUTTON_GSM_UMTS_OPTIONS = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_OPTIONS = "button_cdma_more_expand_key";

    private Intent mContactListIntent;

    /** Event for Async voicemail change call */
    private static final int EVENT_VOICEMAIL_CHANGED     = 500;

    // preferred TTY mode
    // Phone.TTY_MODE_xxx
    static final int preferredTtyMode = Phone.TTY_MODE_OFF;

    // Dtmf tone types
    static final int DTMF_TONE_TYPE_NORMAL = 0;
    static final int DTMF_TONE_TYPE_LONG   = 1;

    private static final String HAC_KEY = "HACSetting";
    private static final String HAC_VAL_ON = "ON";
    private static final String HAC_VAL_OFF = "OFF";

    /** Handle to voicemail pref */
    private static final int VOICEMAIL_PREF_ID = 1;

    private Phone mPhone;

    private AudioManager mAudioManager;

    private static final int VM_NOCHANGE_ERROR = 400;
    private static final int VM_RESPONSE_ERROR = 500;


    // dialog identifiers for voicemail
    private static final int VOICEMAIL_DIALOG_CONFIRM = 600;

    // status message sent back from handlers
    private static final int MSG_OK = 100;

    // special statuses for voicemail controls.
    private static final int MSG_VM_EXCEPTION = 400;
    private static final int MSG_VM_OK = 600;
    private static final int MSG_VM_NOCHANGE = 700;

    private EditPhoneNumberPreference mSubMenuVoicemailSettings;

    private CheckBoxPreference mButtonAutoRetry;
    private CheckBoxPreference mButtonHAC;
    private ListPreference mButtonDTMF;
    private ListPreference mButtonTTY;

    /** string to hold old voicemail number as it is being updated. */
    private String mOldVmNumber;


    TTYHandler ttyHandler;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mSubMenuVoicemailSettings) {
            return true;
        } else if (preference == mButtonDTMF) {
            return true;
        } else if (preference == mButtonTTY) {
            return true;
        } else if (preference == mButtonAutoRetry) {
            android.provider.Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.System.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == mButtonHAC) {
            int hac = mButtonHAC.isChecked() ? 1 : 0;
            // Update HAC value in Settings database
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.HEARING_AID, hac);

            // Update HAC Value in AudioManager
            mAudioManager.setParameter(HAC_KEY, hac != 0 ? HAC_VAL_ON : HAC_VAL_OFF);
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        } else if (preference == mButtonTTY) {
            handleTTYChange(preference, objValue);
        }
        // always let the preference setting proceed.
        return true;
    }

    // Preference click listener invoked on OnDialogClosed for EditPhoneNumberPreference.
    public void onDialogClosed(EditPhoneNumberPreference preference, int buttonClicked) {
        if (DBG) log("onPreferenceClick: request preference click on dialog close.");

        if (preference instanceof EditPhoneNumberPreference) {
            EditPhoneNumberPreference epn = preference;

            if (epn == mSubMenuVoicemailSettings) {
                handleVMBtnClickRequest();
            }
        }
    }

    /**
     * Implemented for EditPhoneNumberPreference.GetDefaultNumberListener.
     * This method set the default values for the various
     * EditPhoneNumberPreference dialogs.
     */
    public String onGetDefaultNumber(EditPhoneNumberPreference preference) {
        if (preference == mSubMenuVoicemailSettings) {
            // update the voicemail number field, which takes care of the
            // mSubMenuVoicemailSettings itself, so we should return null.
            if (DBG) log("updating default for voicemail dialog");
            updateVoiceNumberField();
            return null;
        }

        String vmDisplay = mPhone.getVoiceMailNumber();
        if (TextUtils.isEmpty(vmDisplay)) {
            // if there is no voicemail number, we just return null to
            // indicate no contribution.
            return null;
        }

        // Return the voicemail number prepended with "VM: "
        if (DBG) log("updating default for call forwarding dialogs");
        return getString(R.string.voicemail_abbreviated) + " " + vmDisplay;
    }


    // override the startsubactivity call to make changes in state consistent.
    @Override
    public void startActivityForResult(Intent intent, int requestCode) {
        if (requestCode == -1) {
            // this is an intent requested from the preference framework.
            super.startActivityForResult(intent, requestCode);
            return;
        }

        if (DBG) log("startSubActivity: starting requested subactivity");
        super.startActivityForResult(intent, requestCode);
    }

    // asynchronous result call after contacts are selected.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // there are cases where the contact picker may end up sending us more than one
        // request.  We want to ignore the request if we're not in the correct state.

        if (resultCode != RESULT_OK) {
            if (DBG) log("onActivityResult: contact picker result not OK.");
            return;
        }

        Cursor cursor = getContentResolver().query(data.getData(),
                NUM_PROJECTION, null, null, null);
        if ((cursor == null) || (!cursor.moveToFirst())) {
            if (DBG) log("onActivityResult: bad contact data, no results found.");
            return;
        }

        switch (requestCode) {
            case VOICEMAIL_PREF_ID:
                mSubMenuVoicemailSettings.onPickActivityResult(cursor.getString(0));
                break;
            default:
                // TODO: may need exception here.
        }
    }

    // Voicemail button logic
    private void handleVMBtnClickRequest() {
        // normally called on the dialog close.

        // Since we're stripping the formatting out on the getPhoneNumber()
        // call now, we won't need to do so here anymore.
        String newVMNumber = mSubMenuVoicemailSettings.getPhoneNumber();

        // empty vm number == clearing the vm number ?
        if (newVMNumber == null) {
            newVMNumber = "";
        }

        //throw a warning if they are the same.
        if (newVMNumber.equals(mOldVmNumber)) {
            showVMDialog(MSG_VM_NOCHANGE);
            return;
        }

        // otherwise, set it.
        if (DBG) log("save voicemail #: " + newVMNumber);
        mPhone.setVoiceMailNumber(
                mPhone.getVoiceMailAlphaTag().toString(),
                newVMNumber,
                Message.obtain(mSetOptionComplete, EVENT_VOICEMAIL_CHANGED));
    }

    /*
     * Callback to handle option update completions
     */

    // **Callback on option setting when complete.
    private Handler mSetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // query to make sure we're looking at the same data as that in the network.
            switch (msg.what) {
                case EVENT_VOICEMAIL_CHANGED:
                    handleSetVMMessage((AsyncResult) msg.obj);
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // Voicemail Object
    private void handleSetVMMessage(AsyncResult ar) {
        if (DBG) {
            log("handleSetVMMessage: set VM request complete");
        }
        if (ar.exception == null) {
            if (DBG) log("change VM success!");
            showVMDialog(MSG_VM_OK);
        } else {
            // TODO: may want to check the exception and branch on it.
            if (DBG) log("change VM failed!");
            showVMDialog(MSG_VM_EXCEPTION);
        }
        updateVoiceNumberField();
    }

    /*
     * Methods used to sync UI state with that of the network
     */

    // update the voicemail number from what we've recorded on the sim.
    private void updateVoiceNumberField() {
        if (mSubMenuVoicemailSettings == null) {
            return;
        }

        mOldVmNumber = mPhone.getVoiceMailNumber();
        if (mOldVmNumber == null) {
            mOldVmNumber = "";
        }
        mSubMenuVoicemailSettings.setPhoneNumber(mOldVmNumber);
    }

    /*
     * Helper Methods for Activity class.
     * The inital query commands are split into two pieces now
     * for individual expansion.  This combined with the ability
     * to cancel queries allows for a much better user experience,
     * and also ensures that the user only waits to update the
     * data that is relevant.
     */

    // dialog creation method, called by showDialog()
    @Override
    protected Dialog onCreateDialog(int id) {

        if ((id == VM_RESPONSE_ERROR) || (id == VM_NOCHANGE_ERROR) ||
                (id == VOICEMAIL_DIALOG_CONFIRM)) {

            AlertDialog.Builder b = new AlertDialog.Builder(this);

            int msgId;
            int titleId = R.string.error_updating_title;
            switch (id) {
                case VOICEMAIL_DIALOG_CONFIRM:
                    msgId = R.string.vm_changed;
                    titleId = R.string.voicemail;
                    // Set Button 2
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case VM_NOCHANGE_ERROR:
                    // even though this is technically an error,
                    // keep the title friendly.
                    msgId = R.string.no_change;
                    titleId = R.string.voicemail;
                    // Set Button 2
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case VM_RESPONSE_ERROR:
                    msgId = R.string.vm_change_failed;
                    // Set Button 1
                    b.setPositiveButton(R.string.close_dialog, this);
                    break;
                default:
                    msgId = R.string.exception_error;
                    // Set Button 3, tells the activity that the error is
                    // not recoverable on dialog exit.
                    b.setNeutralButton(R.string.close_dialog, this);
                    break;
            }

            b.setTitle(getText(titleId));
            b.setMessage(getText(msgId));
            b.setCancelable(false);
            AlertDialog dialog = b.create();

            // make the dialog more obvious by bluring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;
        }

        return null;
    }

    // This is a method implemented for DialogInterface.OnClickListener.
    // Used with the error dialog to close the app, voicemail dialog to just dismiss.
    // Close button is mapped to BUTTON1 for the errors that close the activity,
    // while those that are mapped to 3 only move the preference focus.
    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();
        switch (which){
            case DialogInterface.BUTTON3:
                // Neutral Button, used when we want to cancel expansion.
                break;
            case DialogInterface.BUTTON1:
                // Negative Button
                finish();
                break;
            default:
                // just let the dialog close and go back to the input
                // ready state
                // Positive Button
        }
    }

    // set the app state with optional status.
    private void showVMDialog(int msgStatus) {
        switch (msgStatus) {
            case MSG_VM_EXCEPTION:
                showDialog(VM_RESPONSE_ERROR);
                break;
            case MSG_VM_NOCHANGE:
                showDialog(VM_NOCHANGE_ERROR);
                break;
            case MSG_VM_OK:
                showDialog(VOICEMAIL_DIALOG_CONFIRM);
                break;
            case MSG_OK:
            default:
                // This should never happen.
        }
    }

    /*
     * Activity class methods
     */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mPhone = PhoneFactory.getDefaultPhone();

        addPreferencesFromResource(R.xml.call_feature_setting);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // get buttons
        PreferenceScreen prefSet = getPreferenceScreen();
        mSubMenuVoicemailSettings = (EditPhoneNumberPreference)
                prefSet.findPreference(BUTTON_VOICEMAIL_KEY);

        if (mSubMenuVoicemailSettings != null) {
            mSubMenuVoicemailSettings.setParentActivity(this, VOICEMAIL_PREF_ID, this);
            mSubMenuVoicemailSettings.setDialogOnClosedListener(this);
            mSubMenuVoicemailSettings.setDialogTitle(R.string.voicemail_settings_number_label);
        }

        mButtonDTMF = (ListPreference) findPreference(BUTTON_DTMF_KEY);
        mButtonAutoRetry = (CheckBoxPreference) findPreference(BUTTON_RETRY_KEY);
        mButtonHAC = (CheckBoxPreference) findPreference(BUTTON_HAC_KEY);
        mButtonTTY = (ListPreference) findPreference(BUTTON_TTY_KEY);

        if (getResources().getBoolean(R.bool.dtmf_type_enabled)) {
            mButtonDTMF.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mButtonDTMF);
            mButtonDTMF = null;
        }

        if (getResources().getBoolean(R.bool.auto_retry_enabled)) {
            mButtonAutoRetry.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mButtonAutoRetry);
            mButtonAutoRetry = null;
        }

        if (getResources().getBoolean(R.bool.hac_enabled)) {
            mButtonHAC.setOnPreferenceChangeListener(this);
        } else {
            prefSet.removePreference(mButtonHAC);
            mButtonHAC = null;
        }

        if (getResources().getBoolean(R.bool.tty_enabled)) {
            mButtonTTY.setOnPreferenceChangeListener(this);
            ttyHandler = new TTYHandler();
        } else {
            prefSet.removePreference(mButtonTTY);
            mButtonTTY = null;
        }

        if (!getResources().getBoolean(R.bool.world_phone)) {
            prefSet.removePreference(prefSet.findPreference(BUTTON_CDMA_OPTIONS));
            prefSet.removePreference(prefSet.findPreference(BUTTON_GSM_UMTS_OPTIONS));

            if (mPhone.getPhoneName().equals("CDMA")) {
                prefSet.removePreference(prefSet.findPreference(BUTTON_FDN_KEY));
                addPreferencesFromResource(R.xml.cdma_call_options);
            } else {
                addPreferencesFromResource(R.xml.gsm_umts_call_options);
            }
        }

        // create intent to bring up contact list
        mContactListIntent = new Intent(Intent.ACTION_GET_CONTENT);
        mContactListIntent.setType(android.provider.Contacts.Phones.CONTENT_ITEM_TYPE);

        // check the intent that started this activity and pop up the voicemail
        // dialog if we've been asked to.
        if (getIntent().getAction().equals(ACTION_ADD_VOICEMAIL)) {
            mSubMenuVoicemailSettings.showPhoneNumberDialog();
        }
        updateVoiceNumberField();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mButtonDTMF != null) {
            int dtmf = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, DTMF_TONE_TYPE_NORMAL);
            mButtonDTMF.setValueIndex(dtmf);
        }

        if (mButtonAutoRetry != null) {
            int autoretry = Settings.System.getInt(getContentResolver(),
                    Settings.System.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        }

        if (mButtonHAC != null) {
            int hac = Settings.System.getInt(getContentResolver(), Settings.System.HEARING_AID, 0);
            mButtonHAC.setChecked(hac != 0);
        }

        if (mButtonTTY != null) {
            mPhone.queryTTYMode(ttyHandler.obtainMessage(TTYHandler.EVENT_TTY_MODE_GET));
        }
    }

    private void handleTTYChange(Preference preference, Object objValue) {
        int buttonTtyMode;
        buttonTtyMode = Integer.valueOf((String) objValue).intValue();
        int settingsTtyMode = android.provider.Settings.Secure.getInt(
                getContentResolver(),
                android.provider.Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode);
        if (DBG) log("handleTTYChange: requesting set TTY mode enable (TTY) to" +
                Integer.toString(buttonTtyMode));

        if (buttonTtyMode != settingsTtyMode) {
            switch(buttonTtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_FULL:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
                mPhone.setTTYMode(buttonTtyMode,
                        ttyHandler.obtainMessage(TTYHandler.EVENT_TTY_MODE_SET));
                break;
            default:
                mPhone.setTTYMode(Phone.TTY_MODE_OFF,
                        ttyHandler.obtainMessage(TTYHandler.EVENT_TTY_MODE_SET));
            }
        }
    }

    class TTYHandler extends Handler {
        /** Event for TTY mode change */
        private static final int EVENT_TTY_MODE_GET = 700;
        private static final int EVENT_TTY_MODE_SET = 800;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_TTY_MODE_GET:
                    handleQueryTTYModeResponse(msg);
                    break;
                case EVENT_TTY_MODE_SET:
                    handleSetTTYModeResponse(msg);
                    break;
            }
        }

        private void updatePreferredTtyModeSummary(int TtyMode) {
            String [] txts = getResources().getStringArray(R.array.tty_mode_entries);
            switch(TtyMode) {
                case Phone.TTY_MODE_OFF:
                case Phone.TTY_MODE_HCO:
                case Phone.TTY_MODE_VCO:
                case Phone.TTY_MODE_FULL:
                    mButtonTTY.setSummary(txts[TtyMode]);
                    break;
                default:
                    mButtonTTY.setEnabled(false);
                    mButtonTTY.setSummary(txts[Phone.TTY_MODE_OFF]);
            }
        }

        private void handleQueryTTYModeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            if (ar.exception != null) {
                if (DBG) log("handleQueryTTYModeResponse: Error getting TTY state.");
                mButtonTTY.setEnabled(false);
            } else {
                if (DBG) log("handleQueryTTYModeResponse: TTY enable state successfully queried.");

                int ttymode = ((int[]) ar.result)[0];
                if (DBG) log("handleQueryTTYModeResponse:ttymode=" + ttymode);

                Intent ttyModeChanged = new Intent(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
                ttyModeChanged.putExtra("ttyEnabled", ttymode != Phone.TTY_MODE_OFF);
                sendBroadcast(ttyModeChanged);
                android.provider.Settings.Secure.putInt(getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_TTY_MODE, ttymode );
                mButtonTTY.setValue(Integer.toString(ttymode));
                updatePreferredTtyModeSummary(ttymode);
            }
        }

        private void handleSetTTYModeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) log("handleSetTTYModeResponse: Error setting TTY mode, ar.exception"
                        + ar.exception);
            }
            mPhone.queryTTYMode(ttyHandler.obtainMessage(TTYHandler.EVENT_TTY_MODE_GET));
        }

    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
