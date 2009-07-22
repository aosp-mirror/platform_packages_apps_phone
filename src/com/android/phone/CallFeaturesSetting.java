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

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Settings;
import android.provider.Contacts.PhonesColumns;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.cdma.TtyIntent;
import android.content.Context;

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
    private static final boolean DBG = false;

    // string contants
    private static final String NUM_PROJECTION[] = {PhonesColumns.NUMBER};
    private static final String SRC_TAGS[]       = {"{0}"};

    // String keys for preference lookup
    private static final String BUTTON_CLIR_KEY  = "button_clir_key";
    private static final String BUTTON_CW_KEY    = "button_cw_key";
    private static final String BUTTON_CFU_KEY   = "button_cfu_key";
    private static final String BUTTON_CFB_KEY   = "button_cfb_key";
    private static final String BUTTON_CFNRY_KEY = "button_cfnry_key";
    private static final String BUTTON_CFNRC_KEY = "button_cfnrc_key";
    private static final String BUTTON_VOICEMAIL_KEY = "button_voicemail_key";
    private static final String BUTTON_FDN_KEY   = "button_fdn_key";

    // used to store the state of expanded preferences
    private static final String BUTTON_GSM_MORE_EXPAND_KEY = "button_gsm_more_expand_key";
    private static final String BUTTON_CDMA_MORE_EXPAND_KEY = "button_cdma_more_expand_key";

    private static final String BUTTON_CF_EXPAND_KEY = "button_cf_expand_key";
    private static final String SUMMARY_CFU_KEY   = "summary_cfu_key";
    private static final String SUMMARY_CFB_KEY   = "summary_cfb_key";
    private static final String SUMMARY_CFNRY_KEY = "summary_cfnry_key";
    private static final String SUMMARY_CFNRC_KEY = "summary_cfnrc_key";

    private static final String APP_STATE_KEY     = "app_state_key";
    private static final String DISPLAY_MODE_KEY  = "display_mode_key";

    private static final String BUTTON_TTY_KEY = "button_tty_mode_key";
    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";
    private static final String BUTTON_DS_KEY = "dtmf_settings";

    private Intent mContactListIntent;
    private Intent mFDNSettingIntent;

    // events
    private static final int EVENT_SERVICE_STATE_CHANGED = 100;
    private static final int EVENT_CLIR_EXECUTED         = 200;
    private static final int EVENT_CW_EXECUTED           = 300;
    private static final int EVENT_CF_EXECUTED           = 400;
    /** Event for Async voicemail change call */
    private static final int EVENT_VOICEMAIL_CHANGED     = 500;
    /** track the query cancel event. */
    private static final int EVENT_INITAL_QUERY_CANCELED = 600;
    /** Event for TTY mode change */
    private static final int EVENT_TTY_EXECUTED          = 700;
    private static final int EVENT_TTY_MODE_SET          = 800;
    private static final int EVENT_ENHANCED_VP_EXECUTED  = 1000;

    // preferred TTY mode
    // 0 = disabled
    // 1 = full mode
    // 2 = HCO mode
    // 3 = VCO mode
    static final int preferredTtyMode = 0;

    // preferred VoicePrivacy mode
    // 0 = disabled
    // 1 = enabled
    static final int preferredVPMode = 1;

    // Dtmf tone types
    static final int DTMF_TONE_TYPE_NORMAL = 0;
    static final int DTMF_TONE_TYPE_LONG   = 1;

    // preferred DTMF Tones mode
    static final int preferredDtmfMode = DTMF_TONE_TYPE_NORMAL;


    /** Handle to voicemail pref */
    private static final int VOICEMAIL_PREF_ID = CommandsInterface.CF_REASON_NOT_REACHABLE + 1;

    private Phone mPhone;

    private static final int BUSY_DIALOG = 100;
    private static final int EXCEPTION_ERROR = 200;
    private static final int RESPONSE_ERROR = 300;
    private static final int VM_NOCHANGE_ERROR = 400;
    private static final int VM_RESPONSE_ERROR = 500;

    /** used to track errors with the radio off. */
    private static final int RADIO_OFF_ERROR = 800;
    private static final int INITIAL_BUSY_DIALOG = 900;

    // dialog identifiers for voicemail
    private static final int VOICEMAIL_DIALOG_CONFIRM = 600;
    private static final int VOICEMAIL_DIALOG_PROGRESS = 700;

    // status message sent back from handlers
    //  handleGetCLIRMessage
    //  handleGetCWMessage
    //  handleGetCFMessage
    private static final int MSG_OK = 100;
    private static final int MSG_EXCEPTION = 200;
    private static final int MSG_UNEXPECTED_RESPONSE = 300;
    // special statuses for voicemail controls.
    private static final int MSG_VM_EXCEPTION = 400;
    private static final int MSG_VM_BUSY = 500;
    private static final int MSG_VM_OK = 600;
    private static final int MSG_VM_NOCHANGE = 700;
    private static final int MSG_RADIO_OFF = 800;

    // application states including network error state.
    // this includes seperate state for the inital query, which is cancelable.
    private enum AppState {
        INPUT_READY,
        DIALOG_OPEN,
        WAITING_NUMBER_SELECT,
        BUSY_NETWORK_CONNECT,
        NETWORK_ERROR,
        INITIAL_QUERY
    };
    private AppState mAppState;

    /** Additional state tracking to handle expanded views (lazy queries)*/
    private static final int DISP_MODE_MAIN = -1;
    private static final int DISP_MODE_CF = -2;
    private static final int DISP_MODE_MORE = -3;
    private int mDisplayMode;
    private boolean mCFDataStale = true;
    private boolean mMoreDataStale = true;
    private boolean mIsBusyDialogAvailable = false;

    // toggle buttons
    private PreferenceScreen mSubMenuFDNSettings;
    private ListPreference mButtonCLIR;
    private CheckBoxPreference mButtonCW;
    private EditPhoneNumberPreference mButtonCFU;
    private EditPhoneNumberPreference mButtonCFB;
    private EditPhoneNumberPreference mButtonCFNRy;
    private EditPhoneNumberPreference mButtonCFNRc;
    private EditPhoneNumberPreference mSubMenuVoicemailSettings;
    private PreferenceScreen mButtonCFExpand;
    private PreferenceScreen mButtonGSMMoreExpand;
    private CheckBoxPreference mButtonVoicePrivacy;
    private ListPreference mButtonTTY;
    private ListPreference mButtonDS;

    // cf number strings
    private String mDialingNumCFU;
    private String mDialingNumCFB;
    private String mDialingNumCFNRy;
    private String mDialingNumCFNRc;
    /** string to hold old voicemail number as it is being updated. */
    private String mOldVmNumber;


    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (mAppState != AppState.INPUT_READY) {
            if (DBG) {
                log("onPreferencesHierarchyClick: preference request denied, currently busy.");
            }
            return false;
        }

        if (DBG) log("onPreferencesHierarchyClick: request preference click.");

        AppState nextState = AppState.INPUT_READY;

        if (preference == mButtonCW) {
            handleCWClickRequest(mButtonCW.isChecked());
            nextState = AppState.BUSY_NETWORK_CONNECT;

        } else if (preference == mButtonCLIR) {
            // let the normal listpreference UI take care of this.
            return false;

        } else if ((preference instanceof EditPhoneNumberPreference) &&
                ((preference == mButtonCFU) || (preference == mButtonCFB) ||
                (preference == mButtonCFNRy) || (preference == mButtonCFNRc) ||
                (preference == mSubMenuVoicemailSettings))) {
            nextState = AppState.DIALOG_OPEN;
        } else if (preference == mSubMenuFDNSettings) {
            // let the intent handler from the caller take care of the
            // navigation to the FDN screen.
            return false;

        /** perform the requested expansion, and query the network.*/
        } else if (preference == mButtonCFExpand){
            setDisplayMode(DISP_MODE_CF);
            return true;
        } else if (preference == mButtonGSMMoreExpand){
            // TODO - should have handler for mButtonCDMAMoreExpand?
            setDisplayMode(DISP_MODE_MORE);
        } else if (preference == mButtonVoicePrivacy) {
            handleVoicePrivacyClickRequest(mButtonVoicePrivacy.isChecked());
        } else if (preference == mButtonTTY) {
            //displays the value taken from the Settings.System
            int settingsTtyMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE ,
                    preferredTtyMode);
            mButtonTTY.setValue(Integer.toString(settingsTtyMode));
            return true;
        } else if (preference == mButtonDS) {
            // Let the normal listpreference UI take care of this
            return false;
        }

        if (nextState != AppState.INPUT_READY) {
            setAppState(nextState);
            return true;
        }

        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonCLIR) {
            // send the command and update state.
            handleCLIRClickRequest(mButtonCLIR.findIndexOfValue((String) objValue));
            setAppState(AppState.BUSY_NETWORK_CONNECT);
        } else if (preference == mButtonTTY) {
            // send the command and update state.
            handleTTYClickRequest(preference, objValue);
        } else if (preference == mButtonDS) {
            int index = mButtonDS.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        }
        // always let the preference setting proceed.
        return true;
    }


    /**
     * Perform the query request for the expanded items upon user request.
     */
    public void setDisplayMode(int displayMode) {
        mDisplayMode = displayMode;

        // look for the data if it is considered stale.
        if ((mCFDataStale && (displayMode == DISP_MODE_CF)) ||
                (mMoreDataStale && (displayMode == DISP_MODE_MORE))){
            if (DBG) log("setDisplayMode: performing requested expansion.");

            // check for CDMA, if so just open without querying
            if ( mPhone.getPhoneName().equals("CDMA") ) {
                setAppState(AppState.INPUT_READY);
            } else {
                // If airplane mode is on, do not bother querying.
                if (Settings.System.getInt(getContentResolver(),
                        Settings.System.AIRPLANE_MODE_ON, 0) <= 0 ) {
                    // query state if radio is available
                    //  if its out of service, just wait for the radio to be ready
                    //  if its neither of these states, throw up an error.
                    setAppState(AppState.INITIAL_QUERY);

                    int radioState = mPhone.getServiceState().getState();

                    if (radioState == ServiceState.STATE_IN_SERVICE) {
                        // Query ONLY what we are currently expanding.
                        if (displayMode == DISP_MODE_CF) {
                            queryAllCFOptions();
                        } else {
                            queryMoreOptions();
                        }
                    } else if (radioState == ServiceState.STATE_POWER_OFF){
                        if (DBG) log("onCreate: radio not ready, waiting for signal.");
                        mPhone.registerForServiceStateChanged(mNetworkServiceHandler,
                                EVENT_SERVICE_STATE_CHANGED, null);
                    } else {
                        setAppState(AppState.NETWORK_ERROR, MSG_EXCEPTION);
                    }
                } else {
                    if (DBG) log("setDisplayMode: radio is off!");
                    setAppState(AppState.NETWORK_ERROR, MSG_RADIO_OFF);
                }
            }
        }
    }

    // Preference click listener invoked on OnDialogClosed for EditPhoneNumberPreference.
    public void onDialogClosed(EditPhoneNumberPreference preference, int buttonClicked) {
        if (mAppState != AppState.DIALOG_OPEN) {
            if (DBG) {
                log("onPreferenceClick: preference request denied, currently busy.");
            }
            return;
        } else if (buttonClicked == DialogInterface.BUTTON2) {
            // Button2 is the cancel button.
            setAppState (AppState.INPUT_READY);
            return;
        }

        if (DBG) log("onPreferenceClick: request preference click on dialog close.");

        AppState nextState = AppState.INPUT_READY;

        if (preference instanceof EditPhoneNumberPreference) {
            EditPhoneNumberPreference epn = preference;

            if (epn == mSubMenuVoicemailSettings) {
                handleVMBtnClickRequest();

            } else {
                int reason = 0;
                int time = 0;
                String number = "";
                // We use CommandsInterface.CF_ACTION_REGISTRATION for both the Enable
                // and Update (Button1) functions.
                int action = (epn.isToggled() || (buttonClicked == DialogInterface.BUTTON1)) ?
                        CommandsInterface.CF_ACTION_REGISTRATION :
                        CommandsInterface.CF_ACTION_DISABLE;

                // The formatted string seems to be giving the MMI codes some problems,
                // so we strip the formatting first before sending the number.
                number = PhoneNumberUtils.stripSeparators((epn.getPhoneNumber()));
                if (epn == mButtonCFU) {
                    nextState = AppState.BUSY_NETWORK_CONNECT;
                    reason = CommandsInterface.CF_REASON_UNCONDITIONAL;
                    mDialingNumCFU = number;
                } else if (epn == mButtonCFB) {
                    nextState = AppState.BUSY_NETWORK_CONNECT;
                    reason = CommandsInterface.CF_REASON_BUSY;
                    mDialingNumCFB = number;
                } else if (epn == mButtonCFNRy) {
                    nextState = AppState.BUSY_NETWORK_CONNECT;
                    reason = CommandsInterface.CF_REASON_NO_REPLY;
                    time = 20;
                    mDialingNumCFNRy = number;
                } else if (epn == mButtonCFNRc) {
                    nextState = AppState.BUSY_NETWORK_CONNECT;
                    reason = CommandsInterface.CF_REASON_NOT_REACHABLE;
                    mDialingNumCFNRc = number;
                }

                if (nextState == AppState.BUSY_NETWORK_CONNECT) {
                    handleCFBtnClickRequest(action, reason, time, number);
                }

                if (nextState != AppState.DIALOG_OPEN) {
                    setAppState(nextState);
                }
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

        if (mAppState != AppState.DIALOG_OPEN) {
            if (DBG) {
                log("startSubActivity: dialog start activity request denied, currently busy.");
            }
            return;
        }

        if (DBG) log("startSubActivity: starting requested subactivity");

        super.startActivityForResult(intent, requestCode);

        setAppState (AppState.WAITING_NUMBER_SELECT);
    }

    // asynchronous result call after contacts are selected.
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // there are cases where the contact picker may end up sending us more than one
        // request.  We want to ignore the request if we're not in the correct state.
        if (mAppState != AppState.WAITING_NUMBER_SELECT) {
            if (DBG) log("onActivityResult: wrong state, ignoring message from contact picker.");
            return;
        } else {
            setAppState(AppState.DIALOG_OPEN);
        }

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
            case CommandsInterface.CF_REASON_UNCONDITIONAL:
                mButtonCFU.onPickActivityResult(cursor.getString(0));
                break;
            case CommandsInterface.CF_REASON_BUSY:
                mButtonCFB.onPickActivityResult(cursor.getString(0));
                break;
            case CommandsInterface.CF_REASON_NO_REPLY:
                mButtonCFNRy.onPickActivityResult(cursor.getString(0));
                break;
            case CommandsInterface.CF_REASON_NOT_REACHABLE:
                mButtonCFNRc.onPickActivityResult(cursor.getString(0));
                break;
            case VOICEMAIL_PREF_ID:
                mSubMenuVoicemailSettings.onPickActivityResult(cursor.getString(0));
                break;
            default:
                // TODO: may need exception here.
        }

    }

    // CLIR object
    private void handleCLIRClickRequest(int i) {
        if (DBG) log("handleCLIRClickRequest: requesting set Call Line Id Restriction (CLIR) to " +
                (i == CommandsInterface.CLIR_INVOCATION ? "ENABLE" :
                    (i == CommandsInterface.CLIR_SUPPRESSION ? "DISABLE" : "NETWORK DEFAULT")));
        mPhone.setOutgoingCallerIdDisplay(i,
                Message.obtain(mSetOptionComplete, EVENT_CLIR_EXECUTED));
    }

    // CW object
    private void handleCWClickRequest(boolean b) {
        if (DBG) log("handleCWClickRequest: requesting set call waiting enable (CW) to" +
                Boolean.toString(b));
        mPhone.setCallWaiting(b, Message.obtain(mSetOptionComplete, EVENT_CW_EXECUTED));
    }

    // CF Button objects
    private void handleCFBtnClickRequest(int action, int reason, int time, String number) {
        if (DBG) log("handleCFBtnClickRequest: requesting set call forwarding (CF) " +
                Integer.toString(reason) + " to " + Integer.toString(action) + " with number " +
                number);
        mPhone.setCallForwardingOption(action,
                reason,
                number,
                time,
                Message.obtain(mSetOptionComplete, EVENT_CF_EXECUTED, reason, 0));
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
            setAppState(AppState.INPUT_READY, MSG_VM_NOCHANGE);
            return;
        }

        // otherwise, set it.
        setAppState (AppState.BUSY_NETWORK_CONNECT, MSG_VM_BUSY);
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
                case EVENT_CLIR_EXECUTED:
                    handleSetCLIRMessage();
                    break;
                case EVENT_CW_EXECUTED:
                    handleSetCWMessage();
                    break;
                case EVENT_CF_EXECUTED:
                    handleSetCFMessage(msg.arg1, (AsyncResult) msg.obj);
                    break;
                case EVENT_VOICEMAIL_CHANGED:
                    handleSetVMMessage((AsyncResult) msg.obj);
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // CLIR Object
    private void handleSetCLIRMessage() {
        if (DBG) {
            log("handleSetCLIRMessage: set CLIR request complete, reading value from network.");
        }
        mPhone.getOutgoingCallerIdDisplay(Message.obtain(mGetOptionComplete, EVENT_CLIR_EXECUTED));
    }

    // CW Object
    private void handleSetCWMessage() {
        if (DBG) {
            log("handleSetCWMessage: set CW request complete, reading value back from network.");
        }
        mPhone.getCallWaiting(Message.obtain(mGetOptionComplete, EVENT_CW_EXECUTED));
    }

    // CF Objects
    private void handleSetCFMessage(int reason, AsyncResult r) {
        if (DBG) {
            log("handleSetCFMessage: set CF request complete, reading value back from network.");
        }

        // handle the exception in the set function's async result by
        // propagating it to the getCallForwarding function.  This is
        // so that we can display the error AFTER the setting has gone
        // through the standard (set/get) cycle.
        mPhone.getCallForwardingOption(reason,
                Message.obtain(mGetOptionComplete, EVENT_CF_EXECUTED, reason, 0, r.exception));
    }

    // Voicemail Object
    private void handleSetVMMessage(AsyncResult ar) {
        if (DBG) {
            log("handleSetVMMessage: set VM request complete");
        }
        if (ar.exception == null) {
            if (DBG) log("change VM success!");
            setAppState(AppState.INPUT_READY, MSG_VM_OK);
        } else {
            // TODO: may want to check the exception and branch on it.
            if (DBG) log("change VM failed!");
            setAppState(AppState.NETWORK_ERROR, MSG_VM_EXCEPTION);
        }
        updateVoiceNumberField();
    }

    /*
     * Callback to handle query completions
     */

    // **Callback on option getting when complete.
    private Handler mGetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            boolean bHandled = false;
            int status = MSG_OK;
            switch (msg.what) {
                case EVENT_CLIR_EXECUTED:
                    status = handleGetCLIRMessage((AsyncResult) msg.obj);
                    bHandled = true;
                    break;
                case EVENT_CW_EXECUTED:
                    status = handleGetCWMessage((AsyncResult) msg.obj);
                    bHandled = true;
                    break;
                case EVENT_CF_EXECUTED:
                    status = handleGetCFMessage((AsyncResult) msg.obj, msg.arg1);
                    bHandled = true;
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
            if (status != MSG_OK) {
                setAppState(AppState.NETWORK_ERROR, status);
            } else if (bHandled) {
                setAppState(AppState.INPUT_READY);
            }
        }
    };

    // CLIR Object
    private int handleGetCLIRMessage(AsyncResult ar) {
        // done with query, display the new settings.
        if (ar.exception != null) {
            if (DBG) log("handleGetCLIRMessage: Error getting CLIR enable state.");
            return MSG_EXCEPTION;
        } else {
            int clirArray[] = (int[]) ar.result;
            if (clirArray.length != 2) {
                if (DBG) log("handleGetCLIRMessage: Error getting CLIR state, unexpected value.");
                return MSG_UNEXPECTED_RESPONSE;
            } else {
                if (DBG) log("handleGetCLIRMessage: CLIR enable state successfully queried.");
                syncCLIRUIState(clirArray);
            }
        }
        return MSG_OK;
    }

    // CW Object
    private int handleGetCWMessage(AsyncResult ar) {
        if (ar.exception != null) {
            if (DBG) log("handleGetCWMessage: Error getting CW enable state.");
            return MSG_EXCEPTION;
        } else {
            if (DBG) log("handleGetCWMessage: CW enable state successfully queried.");
            syncCWState((int[]) ar.result);
        }
        return MSG_OK;
    }

    // VP Object
    private int handleGetVPMessage(AsyncResult ar, int voicePrivacyMode) {
        if (ar.exception != null) {
            if (DBG) log("handleGetVPMessage: Error getting VP enable state.");
            return MSG_EXCEPTION;
        } else {
            Log.d(LOG_TAG, "voicePrivacyMode = " + voicePrivacyMode);
            syncVPState((int[]) ar.result);
        }

        return MSG_OK;
    }

    // CF Object
    private int handleGetCFMessage(AsyncResult ar, int reason) {
        // done with query, display the new settings.
        if (ar.exception != null) {
            if (DBG) log("handleGetCFMessage: Error getting CF enable state.");
            return MSG_EXCEPTION;
        } else if (ar.userObj instanceof Throwable) {
            // TODO: I don't think it makes sense to throw the error up to
            // the user, but this may be reconsidered.  For now, just log
            // the specific error and throw up a generic error.
            if (DBG) log("handleGetCFMessage: Error during set call, reason: " + reason +
                    " exception: " + ((Throwable) ar.userObj).toString());
            return MSG_UNEXPECTED_RESPONSE;
        } else {
            CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
            if (cfInfoArray.length == 0) {
                if (DBG) log("handleGetCFMessage: Error getting CF state, unexpected value.");
                return MSG_UNEXPECTED_RESPONSE;
            } else {
                // TODO: look through the information for the voice data
                // in reality, we should probably take the other service
                // classes into account, but this may be more than we
                // want to expose to the user.
                for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                    if ((CommandsInterface.SERVICE_CLASS_VOICE &
                            cfInfoArray[i].serviceClass) != 0) {
                        if (DBG) {
                            log("handleGetCFMessage: CF state successfully queried for reason " +
                                Integer.toBinaryString(reason));
                        }
                        syncCFUIState(reason, cfInfoArray[i]);
                        break;
                    }
                }
            }
        }
        return MSG_OK;
    }

    /*
     * Methods used to sync UI state with that of the network
     */

    // set the state of the UI based on CW State
    private void syncCWState(int cwArray[]) {
        if (DBG) log("syncCWState: Setting UI state consistent with CW enable state of " +
                ((cwArray[0] == 1) ? "ENABLED" : "DISABLED"));
        mButtonCW.setChecked(cwArray[0] == 1);
    }

    /**
     * The logic in this method is based upon the code in {@link CommandsInterface#getCLIR()}.
     *
     * @param clirArgs is the int[2] retrieved from the getCLIR response, please refer to
     * the link above more more details.
     */
    private void syncCLIRUIState(int clirArgs[]) {
        if (DBG) log("syncCLIRUIState: Setting UI state consistent with CLIR.");

        // enable if the setting is valid.
        final boolean enabled = clirArgs[1] == 1 || clirArgs[1] == 3 || clirArgs[1] == 4;
        mButtonCLIR.setEnabled(enabled);

        // set the value of the preference based upon the clirArgs.
        int value = CommandsInterface.CLIR_DEFAULT;
        switch (clirArgs[1]) {
            case 1: // Permanently provisioned
            case 3: // Temporary presentation disallowed
            case 4: // Temporary presentation allowed
                switch (clirArgs[0]) {
                    case 1: // CLIR invoked
                        value = CommandsInterface.CLIR_INVOCATION;
                        break;
                    case 2: // CLIR suppressed
                        value = CommandsInterface.CLIR_SUPPRESSION;
                        break;
                    case 0: // Network default
                    default:
                        value = CommandsInterface.CLIR_DEFAULT;
                        break;
                }
                break;
            case 0: // Not Provisioned
            case 2: // Unknown (network error, etc)
            default:
                value = CommandsInterface.CLIR_DEFAULT;
                break;
        }
        setButtonCLIRValue(value);
    }

    /**
     * Helper function to set both the value and the summary of the CLIR preference.
     */
    private void setButtonCLIRValue (int value) {

        if (mButtonCLIR == null) {
            return;
        }

        // first, set the value.
        mButtonCLIR.setValueIndex(value);

        // set the string summary to reflect the value
        int summary = R.string.sum_default_caller_id;
        switch (value) {
            case CommandsInterface.CLIR_SUPPRESSION:
                summary = R.string.sum_show_caller_id;
                break;
            case CommandsInterface.CLIR_INVOCATION:
                summary = R.string.sum_hide_caller_id;
                break;
            case CommandsInterface.CLIR_DEFAULT:
                summary = R.string.sum_default_caller_id;
                break;
        }
        mButtonCLIR.setSummary(summary);
    }

    // called by syncCFUIState to do repetitive changes to UI button state.
    private void adjustCFbuttonState(EditPhoneNumberPreference epn,
            boolean isActive, int template, String number) {

        if (epn == null) {
            return;
        }

        CharSequence summaryOn = "";

        if (isActive) {
            if (number != null) {
                String values[] = {number};
                summaryOn = TextUtils.replace(getText(template), SRC_TAGS, values);
            }
            epn.setSummaryOn(summaryOn);
        }

        epn.setToggled(isActive);
        epn.setPhoneNumber(number);
    }

    // set the state of the UI based on CF State
    private void syncCFUIState(int reason, CallForwardInfo info) {
        boolean active = (info.status == 1);
        switch (reason) {
            case CommandsInterface.CF_REASON_UNCONDITIONAL:
                if (DBG) log("syncCFUIState: Setting UI state consistent with CFU.");
                adjustCFbuttonState(mButtonCFU, active, R.string.sum_cfu_enabled, info.number);
                mDialingNumCFU = info.number;
                break;
            case CommandsInterface.CF_REASON_BUSY:
                if (DBG) log("syncCFUIState: Setting UI state consistent with CFB.");
                adjustCFbuttonState(mButtonCFB, active, R.string.sum_cfb_enabled, info.number);
                mDialingNumCFB = info.number;
                break;
            case CommandsInterface.CF_REASON_NO_REPLY:
                if (DBG) log("syncCFUIState: Setting UI state consistent with CFNRy.");
                adjustCFbuttonState(mButtonCFNRy, active, R.string.sum_cfnry_enabled, info.number);
                mDialingNumCFNRy = info.number;
                break;
            case CommandsInterface.CF_REASON_NOT_REACHABLE:
                if (DBG) log("syncCFUIState: Setting UI state consistent with CFNRc.");
                adjustCFbuttonState(mButtonCFNRc, active, R.string.sum_cfnrc_enabled, info.number);
                mDialingNumCFNRc = info.number;
                break;
        }
    }

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

    // Handler to track service availability.
    private Handler mNetworkServiceHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_SERVICE_STATE_CHANGED: {
                        ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
                        if (state.getState() == ServiceState.STATE_IN_SERVICE) {
                            if (DBG) {
                                log("mNetworkServiceHandler: network available for queries.");
                            }
                            // Query ONLY what we are interested in now.
                            switch (mDisplayMode) {
                                case DISP_MODE_CF:
                                    queryAllCFOptions();
                                    break;
                                case DISP_MODE_MORE:
                                    queryMoreOptions();
                                    break;
                            }

                            mPhone.unregisterForServiceStateChanged(mNetworkServiceHandler);
                        }
                    }
                    break;
                case EVENT_INITAL_QUERY_CANCELED:
                    if (DBG) log("mNetworkServiceHandler: cancel query requested.");
                    dismissExpandedDialog();
                    break;
            }
        }
    };

    // Request to begin querying for all options.
    private void queryAllCFOptions() {
        if (DBG) log("queryAllCFOptions: begin querying call features.");
        mPhone.getCallForwardingOption(CommandsInterface.CF_REASON_UNCONDITIONAL,
                Message.obtain(mGetAllCFOptionsComplete, EVENT_CF_EXECUTED,
                        CommandsInterface.CF_REASON_UNCONDITIONAL, 0));
    }

    // callback after each step of querying for all options.
    private Handler mGetAllCFOptionsComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            AsyncResult ar = (AsyncResult) msg.obj;
            int status = MSG_OK;

            switch (msg.what) {
                case EVENT_CF_EXECUTED:
                    status = handleGetCFMessage(ar, msg.arg1);
                    int nextReason = -1;
                    switch (msg.arg1) {
                        case CommandsInterface.CF_REASON_UNCONDITIONAL:
                            if (DBG) log("mGetAllOptionsComplete: CFU query done, querying CFB.");
                            nextReason = CommandsInterface.CF_REASON_BUSY;
                            break;
                        case CommandsInterface.CF_REASON_BUSY:
                            if (DBG) {
                                log("mGetAllOptionsComplete: CFB query done, querying CFNRy.");
                            }
                            nextReason = CommandsInterface.CF_REASON_NO_REPLY;
                            break;
                        case CommandsInterface.CF_REASON_NO_REPLY:
                            if (DBG) {
                                log("mGetAllOptionsComplete: CFNRy query done, querying CFNRc.");
                            }
                            nextReason = CommandsInterface.CF_REASON_NOT_REACHABLE;
                            break;
                        case CommandsInterface.CF_REASON_NOT_REACHABLE:
                            if (DBG) {
                                log("mGetAllOptionsComplete: CFNRc query done, querying CLIR.");
                            }
                            break;
                        default:
                            // TODO: should never reach this, may want to throw exception
                    }
                    if (status != MSG_OK) {
                        setAppState(AppState.NETWORK_ERROR, status);
                    } else {
                        if (nextReason != -1) {
                            mPhone.getCallForwardingOption(nextReason,
                                    Message.obtain(mGetAllCFOptionsComplete, EVENT_CF_EXECUTED,
                                            nextReason, 0));
                        } else {
                            mCFDataStale = false;
                            setAppState(AppState.INPUT_READY);
                        }
                    }
                    break;

                default:
                    // TODO: should never reach this, may want to throw exception
                    break;
            }
        }
    };

    // Request to begin querying for all options.
    private void queryMoreOptions() {
        if (DBG) log("queryMoreOptions: begin querying call features.");
        mPhone.getOutgoingCallerIdDisplay(
                Message.obtain(mGetMoreOptionsComplete, EVENT_CLIR_EXECUTED));
    }

    // callback after each step of querying for all options.
    private Handler mGetMoreOptionsComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            AsyncResult ar = (AsyncResult) msg.obj;
            int status = MSG_OK;

            switch (msg.what) {
                case EVENT_CLIR_EXECUTED:
                    status = handleGetCLIRMessage(ar);
                    if (DBG) log("mGetAllOptionsComplete: CLIR query done, querying CW.");
                    if (status != MSG_OK) {
                        setAppState(AppState.NETWORK_ERROR, status);
                    } else {
                        mPhone.getCallWaiting(Message.obtain(mGetMoreOptionsComplete,
                                EVENT_CW_EXECUTED));
                    }
                    break;

                case EVENT_CW_EXECUTED:
                    status = handleGetCWMessage(ar);
                    if (DBG) {
                        log("mGetAllOptionsComplete: CW query done, querying VP.");
                    }
                    if (status != MSG_OK) {
                        setAppState(AppState.NETWORK_ERROR, status);
                    } else {
                        if (mPhone.getPhoneName().equals("GSM")) {
                            mMoreDataStale = false;
                            setAppState(AppState.INPUT_READY);
                        } else {
                            mPhone.getEnhancedVoicePrivacy(Message.obtain(mGetMoreOptionsComplete,
                                EVENT_ENHANCED_VP_EXECUTED));
                        }
                    }
                    break;

                case EVENT_ENHANCED_VP_EXECUTED:
                    status = handleGetVPMessage(ar, msg.arg1);
                    if (DBG) {
                        log("mGetAllOptionsComplete: VP query done, all call features queried.");
                    }
                    if (status != MSG_OK) {
                        setAppState(AppState.NETWORK_ERROR, status);
                    } else {
                        mMoreDataStale = false;
                        setAppState(AppState.INPUT_READY);
                    }
                    break;

                default:
                    // TODO: should never reach this, may want to throw exception
                    break;
            }
        }
    };

    // dialog creation method, called by showDialog()
    @Override
    protected Dialog onCreateDialog(int id) {

        if ((id == BUSY_DIALOG) || (id == VOICEMAIL_DIALOG_PROGRESS) ||
                (id == INITIAL_BUSY_DIALOG)) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle(getText(R.string.updating_title));
            dialog.setIndeterminate(true);

            switch (id) {
                case BUSY_DIALOG:
                    mIsBusyDialogAvailable = true;
                    dialog.setCancelable(false);
                    dialog.setMessage(getText(R.string.updating_settings));
                    break;
                case VOICEMAIL_DIALOG_PROGRESS:
                    dialog.setCancelable(false);
                    dialog.setMessage(getText(R.string.vm_save_number));
                    break;
                case INITIAL_BUSY_DIALOG:
                    // Allowing the user to cancel on the initial query.
                    dialog.setCancelable(true);
                    dialog.setCancelMessage(
                            mNetworkServiceHandler.obtainMessage(EVENT_INITAL_QUERY_CANCELED));
                    dialog.setMessage(getText(R.string.reading_settings));
                    break;
            }
            // make the dialog more obvious by bluring the background.
            dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);

            return dialog;

        // Handle error dialog codes
        } else if ((id == RESPONSE_ERROR) || (id == EXCEPTION_ERROR) ||
                (id == VM_RESPONSE_ERROR) || (id == VM_NOCHANGE_ERROR) ||
                (id == VOICEMAIL_DIALOG_CONFIRM) || (id == RADIO_OFF_ERROR)){

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
                case RESPONSE_ERROR:
                    msgId = R.string.response_error;
                    // Set Button 2, tells the activity that the error is
                    // recoverable on dialog exit.
                    b.setNegativeButton(R.string.close_dialog, this);
                    break;
                case RADIO_OFF_ERROR:
                    msgId = R.string.radio_off_error;
                    // Set Button 3
                    b.setNeutralButton(R.string.close_dialog, this);
                    break;
                case EXCEPTION_ERROR:
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
                dismissExpandedDialog();
                break;
            case DialogInterface.BUTTON1:
                // Negative Button
                finish();
                break;
            default:
                // just let the dialog close and go back to the input
                // ready state
                setAppState (AppState.INPUT_READY);
                // Positive Button
        }
    }

    /** dismiss the expanded dialog view, going back to the main preference view */
    private void dismissExpandedDialog() {
        // The dialogs that can invoke this method (via onClick()), can ONLY
        // be reached when either expanded dialog (More or Call Forwarding)
        // is open.  However, the Monkey somehow managed to get to this code
        // without the expanded dialogs being available (1305094).  Adding null
        // pointer checks just as a good measure.  This should be fine because
        // if the expanded dialog is NOT shown, we want to ignore the dismiss
        // message and go to INPUT_READY anyway.
        switch (mDisplayMode) {
            case DISP_MODE_CF:
                if (mButtonCFExpand != null && mButtonCFExpand.getDialog() != null) {
                    mButtonCFExpand.getDialog().dismiss();
                }
                break;
            case DISP_MODE_MORE:
                if (mButtonGSMMoreExpand != null && mButtonGSMMoreExpand.getDialog() != null) {
                    // TODO - check for mButtonCDMAMoreExpand symmetry
                    mButtonGSMMoreExpand.getDialog().dismiss();
                }
                break;
        }
        mDisplayMode = DISP_MODE_MAIN;
        setAppState (AppState.INPUT_READY);
    }

    // set the app state when no error message needs to be set.
    private void setAppState(AppState requestedState) throws IllegalStateException{
        if (requestedState == AppState.NETWORK_ERROR) {
            if (DBG) log("setAppState: illegal error state without reason.");
            throw new IllegalStateException ("illegal error state without reason.");
        }
        setAppState (requestedState, MSG_OK);
    }

    // set the app state with optional status.
    private void setAppState(AppState requestedState, int msgStatus)
            throws IllegalStateException{

        if (requestedState == mAppState) {
            if (DBG) log("setAppState: requestedState same as current state. ignoring.");
            return;
        }

        // handle errors
        // make sure we dismiss the correct dialogs.
        if (requestedState == AppState.NETWORK_ERROR) {
            if (DBG) log("setAppState: " + requestedState + ": " + msgStatus);
            switch (msgStatus) {
                case MSG_EXCEPTION:
                    if (mAppState == AppState.INITIAL_QUERY) {
                        dismissDialog(INITIAL_BUSY_DIALOG);
                    } else {
                        dismissBusyDialog();
                    }
                    showDialog (EXCEPTION_ERROR);
                    break;
                case MSG_RADIO_OFF:
                    showDialog (RADIO_OFF_ERROR);
                    break;
                case MSG_UNEXPECTED_RESPONSE:
                    if (mAppState == AppState.INITIAL_QUERY) {
                        dismissDialog(INITIAL_BUSY_DIALOG);
                    } else {
                        dismissBusyDialog();
                    }
                    showDialog (RESPONSE_ERROR);
                    break;
                case MSG_VM_EXCEPTION:
                    dismissDialog(VOICEMAIL_DIALOG_PROGRESS);
                    showDialog (VM_RESPONSE_ERROR);
                    break;
                case MSG_OK:
                default:
                    // This should never happen.
            }
            mAppState = requestedState;
            return;
        }

        switch (mAppState) {
            // We can now transition out of the NETWORK_ERROR state, when the
            // user is moving from the expanded views back to the main view.
            case NETWORK_ERROR:
                if (requestedState != AppState.INPUT_READY) {
                    if (DBG) log("setAppState: illegal transition from NETWORK_ERROR");
                    throw new IllegalStateException
                            ("illegal transition from NETWORK_ERROR");
                }
                break;
            case INPUT_READY:
                if (DBG) log("setAppState: displaying busy dialog, reason: " + requestedState);
                if (requestedState == AppState.INITIAL_QUERY) {
                    showDialog(INITIAL_BUSY_DIALOG);
                } else if (requestedState == AppState.BUSY_NETWORK_CONNECT) {
                    showDialog(BUSY_DIALOG);
                } else if (requestedState == AppState.WAITING_NUMBER_SELECT) {
                    if (DBG) log("setAppState: illegal transition from INPUT_READY");
                    throw new IllegalStateException
                            ("illegal transition from INPUT_READY");
                }
                break;
            case DIALOG_OPEN:
                if (requestedState == AppState.INPUT_READY) {
                    if (msgStatus == MSG_VM_NOCHANGE) {
                        showDialog(VM_NOCHANGE_ERROR);
                    }
                } else {
                    if (msgStatus == MSG_VM_BUSY) {
                        showDialog(VOICEMAIL_DIALOG_PROGRESS);
                    } else {
                        showDialog(BUSY_DIALOG);
                    }
                }
                break;
            case INITIAL_QUERY:
                // the initial query state can ONLY go to the input ready state.
                if (requestedState != AppState.INPUT_READY) {
                    if (DBG) log("setAppState: illegal transition from INITIAL_QUERY");
                    throw new IllegalStateException
                            ("illegal transition from INITIAL_QUERY");
                }
                dismissDialog(INITIAL_BUSY_DIALOG);
                break;
            case BUSY_NETWORK_CONNECT:
                if (requestedState != AppState.INPUT_READY) {
                    if (DBG) log("setAppState: illegal transition from BUSY_NETWORK_CONNECT");
                    throw new IllegalStateException
                            ("illegal transition from BUSY_NETWORK_CONNECT");
                }
                if (msgStatus == MSG_VM_OK) {
                    dismissDialog(VOICEMAIL_DIALOG_PROGRESS);
                    showDialog(VOICEMAIL_DIALOG_CONFIRM);
                } else {
                    dismissBusyDialog();
                }
                break;
            case WAITING_NUMBER_SELECT:
                if (requestedState != AppState.DIALOG_OPEN) {
                    if (DBG) log("setAppState: illegal transition from WAITING_NUMBER_SELECT");
                    throw new IllegalStateException
                            ("illegal transition from WAITING_NUMBER_SELECT");
                }
                dismissBusyDialog();
                break;
        }
        mAppState = requestedState;
    }

    /**
     * Make sure that the busy dialog is available before we try to close it.
     * This check needs to be done because the generic busy dialog is used for
     * a number of cases, but we need to make sure it has been displayed before
     * being dismissed.
     */
    private final void dismissBusyDialog() {
        if (mIsBusyDialogAvailable) {
            dismissDialog(BUSY_DIALOG);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mPhone = PhoneFactory.getDefaultPhone();

        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(true);

        if (mPhone.getPhoneName().equals("CDMA")) {
            // Set UI state in onResume because a user could go home, launch some
            // app to change this setting's backend, and re-launch this settings app
            // and the UI state would be inconsistent with actual state
            handleSetVPMessage();
            mPhone.queryTTYMode(Message.obtain(mQueryTTYComplete, EVENT_TTY_EXECUTED));
            // TODO(Moto): Re-launch DTMF settings if necessary onResume
        }

    }

    /*
     * Activity class methods
     */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        mPhone = PhoneFactory.getDefaultPhone();

        // If the phone loads in CDMA mode , Call Settings XML is CDMA specific
        // TODO: For World Phone, that has options to switch n/w mode dynamically, this
        // design will not work and this piece of code may be moved to onResume()
        if (mPhone.getPhoneName().equals("CDMA")) {
            addPreferencesFromResource(R.xml.cdma_call_feature_setting);
        } else {
            addPreferencesFromResource(R.xml.call_feature_setting);
        }
        // get buttons
        PreferenceScreen prefSet = getPreferenceScreen();
        mSubMenuVoicemailSettings = (EditPhoneNumberPreference)
                prefSet.findPreference(BUTTON_VOICEMAIL_KEY);
        mSubMenuFDNSettings = (PreferenceScreen) prefSet.findPreference(BUTTON_FDN_KEY);

        if (mPhone.getPhoneName().equals("CDMA")) {
            mButtonVoicePrivacy = (CheckBoxPreference) findPreference(BUTTON_VP_KEY);

            mButtonTTY = (ListPreference) prefSet.findPreference(BUTTON_TTY_KEY);
            mButtonTTY.setOnPreferenceChangeListener(this);

            // Get the ttyMode from Settings.System and displays it
            int settingsTtyMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    preferredTtyMode);
            mButtonTTY.setValue(Integer.toString(settingsTtyMode));
            UpdatePreferredTtyModeSummary(settingsTtyMode);

            mButtonDS = (ListPreference) findPreference(BUTTON_DS_KEY);
            int index = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, preferredDtmfMode);
            mButtonDS.setValueIndex(index);
            mButtonDS.setOnPreferenceChangeListener(this);
        } else if (mPhone.getPhoneName().equals("GSM")) {
            mButtonCLIR  = (ListPreference) prefSet.findPreference(BUTTON_CLIR_KEY);
            mButtonCW    = (CheckBoxPreference) prefSet.findPreference(BUTTON_CW_KEY);
            mButtonCFU   = (EditPhoneNumberPreference) prefSet.findPreference(BUTTON_CFU_KEY);
            mButtonCFB   = (EditPhoneNumberPreference) prefSet.findPreference(BUTTON_CFB_KEY);
            mButtonCFNRy = (EditPhoneNumberPreference) prefSet.findPreference(BUTTON_CFNRY_KEY);
            mButtonCFNRc = (EditPhoneNumberPreference) prefSet.findPreference(BUTTON_CFNRC_KEY);

            // get a reference to the Preference Screens for Call Forwarding and "More" settings.
            mButtonCFExpand = (PreferenceScreen) prefSet.findPreference(BUTTON_CF_EXPAND_KEY);
            mButtonGSMMoreExpand = (PreferenceScreen) prefSet.findPreference(
                    BUTTON_GSM_MORE_EXPAND_KEY);

            // The intent code that resided here in the past has been moved into the
            // more conventional location in network_setting.xml

            // Set links to the current activity and any UI settings that
            // effect the dialog for each preference.  Also set the
            // dependencies between the child (CFB, CFNRy, CFNRc)
            // preferences and the CFU preference.
            if (mButtonCFU != null){
                mButtonCFU.setParentActivity(this, CommandsInterface.CF_REASON_UNCONDITIONAL, this);
                mButtonCFU.setDialogOnClosedListener(this);
                mButtonCFU.setDialogTitle(R.string.labelCF);
                mButtonCFU.setDialogMessage(R.string.messageCFU);
            }

            if (mButtonCFB != null) {
                mButtonCFB.setParentActivity(this, CommandsInterface.CF_REASON_BUSY, this);
                mButtonCFB.setDialogOnClosedListener(this);
                mButtonCFB.setDependency(BUTTON_CFU_KEY);
                mButtonCFB.setDialogTitle(R.string.labelCF);
                mButtonCFB.setDialogMessage(R.string.messageCFB);
            }

            if (mButtonCFNRy != null) {
                mButtonCFNRy.setParentActivity(this, CommandsInterface.CF_REASON_NO_REPLY, this);
                mButtonCFNRy.setDialogOnClosedListener(this);
                mButtonCFNRy.setDependency(BUTTON_CFU_KEY);
                mButtonCFNRy.setDialogTitle(R.string.labelCF);
                mButtonCFNRy.setDialogMessage(R.string.messageCFNRy);
            }

            if (mButtonCFNRc != null) {
                mButtonCFNRc.setParentActivity(this, CommandsInterface.CF_REASON_NOT_REACHABLE, this);
                mButtonCFNRc.setDialogOnClosedListener(this);
                mButtonCFNRc.setDependency(BUTTON_CFU_KEY);
                mButtonCFNRc.setDialogTitle(R.string.labelCF);
                mButtonCFNRc.setDialogMessage(R.string.messageCFNRc);
            }

            // set the listener for the CLIR list preference so we can issue CLIR commands.
            if (mButtonCLIR != null ) {
                mButtonCLIR.setOnPreferenceChangeListener(this);
            }

            if (mSubMenuFDNSettings != null) {
                mFDNSettingIntent = new Intent(Intent.ACTION_MAIN);
                mFDNSettingIntent.setClassName(this, FdnSetting.class.getName());
                mSubMenuFDNSettings.setIntent (mFDNSettingIntent);
            }
        }

        if (mSubMenuVoicemailSettings != null) {
            mSubMenuVoicemailSettings.setParentActivity(this, VOICEMAIL_PREF_ID, this);
            mSubMenuVoicemailSettings.setDialogOnClosedListener(this);
            mSubMenuVoicemailSettings.setDialogTitle(R.string.voicemail_settings_number_label);
        }

        // create intent to bring up contact list
        mContactListIntent = new Intent(Intent.ACTION_GET_CONTENT);
        mContactListIntent.setType(android.provider.Contacts.Phones.CONTENT_ITEM_TYPE);

        if (mSubMenuFDNSettings != null) {
            mFDNSettingIntent = new Intent(Intent.ACTION_MAIN);
            mFDNSettingIntent.setClassName(this, FdnSetting.class.getName());
            mSubMenuFDNSettings.setIntent (mFDNSettingIntent);
        }

        mAppState = AppState.INPUT_READY;

        if (icicle != null) {
            if (mPhone.getPhoneName().equals("CDMA")) {
                mButtonVoicePrivacy.setChecked(icicle.getBoolean(BUTTON_VP_KEY));
            } else if (mPhone.getPhoneName().equals("GSM")) {
                // retrieve number state
                mDialingNumCFU = icicle.getString(SUMMARY_CFU_KEY);
                mDialingNumCFB = icicle.getString(SUMMARY_CFB_KEY);
                mDialingNumCFNRy = icicle.getString(SUMMARY_CFNRY_KEY);
                mDialingNumCFNRc = icicle.getString(SUMMARY_CFNRC_KEY);

                // reset CF buttons
                adjustCFbuttonState(mButtonCFU, icicle.getBoolean(BUTTON_CFU_KEY),
                        R.string.sum_cfu_enabled, mDialingNumCFU);
                adjustCFbuttonState(mButtonCFB, icicle.getBoolean(BUTTON_CFB_KEY),
                        R.string.sum_cfb_enabled, mDialingNumCFB);
                adjustCFbuttonState(mButtonCFNRy, icicle.getBoolean(BUTTON_CFNRY_KEY),
                        R.string.sum_cfnry_enabled, mDialingNumCFNRy);
                adjustCFbuttonState(mButtonCFNRc, icicle.getBoolean(BUTTON_CFNRC_KEY),
                        R.string.sum_cfnrc_enabled, mDialingNumCFNRc);

                // reset other button state
                setButtonCLIRValue(icicle.getInt(BUTTON_CLIR_KEY));
                if (mButtonCW != null) {
                    mButtonCW.setChecked(icicle.getBoolean(BUTTON_CW_KEY));
                }

                mCFDataStale = icicle.getBoolean(BUTTON_CF_EXPAND_KEY);
                mMoreDataStale = icicle.getBoolean(BUTTON_GSM_MORE_EXPAND_KEY);
            }

            // set app state
            mAppState = (AppState) icicle.getSerializable(APP_STATE_KEY);
            mDisplayMode = icicle.getInt(DISPLAY_MODE_KEY);

        } else {
            // The queries here are now lazily done, and all data is assumed stale
            // when we first start the activity.
            mCFDataStale = true;
            mMoreDataStale = true;

            // check the intent that started this activity and pop up the voicemail
            // dialog if we've been asked to.
            if (getIntent().getAction().equals(ACTION_ADD_VOICEMAIL)) {
                setAppState(AppState.DIALOG_OPEN);
                mSubMenuVoicemailSettings.showPhoneNumberDialog();
            }
        }

        updateVoiceNumberField();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (DBG) log("onSaveInstanceState: saving relevant UI state.");

        // save button state
        if (mPhone.getPhoneName().equals("CDMA")) {
            outState.putBoolean(BUTTON_VP_KEY, mButtonVoicePrivacy.isChecked());
            outState.putInt(BUTTON_TTY_KEY, mButtonTTY.findIndexOfValue(mButtonTTY.getValue()));
        } else if (mPhone.getPhoneName().equals("GSM")) {
            if (mButtonCLIR != null) {
                outState.putInt(BUTTON_CLIR_KEY, mButtonCLIR.findIndexOfValue(mButtonCLIR.getValue()));
            }
            if (mButtonCW != null) {
                outState.putBoolean(BUTTON_CW_KEY, mButtonCW.isChecked());
            }
            if (mButtonCFU != null) {
                outState.putBoolean(BUTTON_CFU_KEY, mButtonCFU.isToggled());
           }
            if (mButtonCFB != null) {
                outState.putBoolean(BUTTON_CFB_KEY, mButtonCFB.isToggled());
            }
            if (mButtonCFNRy != null) {
                outState.putBoolean(BUTTON_CFNRY_KEY, mButtonCFNRy.isToggled());
            }
            if (mButtonCFNRc != null) {
                 outState.putBoolean(BUTTON_CFNRC_KEY, mButtonCFNRc.isToggled());
            }

            // save number state
            outState.putString(SUMMARY_CFU_KEY, mDialingNumCFU);
            outState.putString(SUMMARY_CFB_KEY, mDialingNumCFB);
            outState.putString(SUMMARY_CFNRY_KEY, mDialingNumCFNRy);
            outState.putString(SUMMARY_CFNRC_KEY, mDialingNumCFNRc);

            outState.putBoolean(BUTTON_CF_EXPAND_KEY, mCFDataStale);
            outState.putBoolean(BUTTON_GSM_MORE_EXPAND_KEY, mMoreDataStale);
        }
        // save state of the app
        outState.putSerializable(APP_STATE_KEY, mAppState);

        outState.putInt(DISPLAY_MODE_KEY, mDisplayMode);
    }

    // TTY object
    private void handleTTYClickRequest(Preference preference, Object objValue) {
        int buttonTtyMode;
        buttonTtyMode = Integer.valueOf((String) objValue).intValue();
        int settingsTtyMode = android.provider.Settings.Secure.getInt(
                mPhone.getContext().getContentResolver(),
                android.provider.Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode);
        if (DBG) log("handleTTYClickRequest: requesting set TTY mode enable (TTY) to" +
                Integer.toString(buttonTtyMode));

        if (buttonTtyMode != settingsTtyMode) {
            switch(buttonTtyMode) {
            case Phone.TTY_MODE_OFF:
                mPhone.setTTYMode(Phone.TTY_MODE_OFF,
                        Message.obtain(mSetTTYComplete, EVENT_TTY_MODE_SET));
                break;
            case Phone.TTY_MODE_FULL:
                mPhone.setTTYMode(Phone.TTY_MODE_FULL,
                        Message.obtain(mSetTTYComplete, EVENT_TTY_MODE_SET));
                break;
            case Phone.TTY_MODE_HCO:
                mPhone.setTTYMode(Phone.TTY_MODE_HCO,
                        Message.obtain(mSetTTYComplete, EVENT_TTY_MODE_SET));
                break;
            case Phone.TTY_MODE_VCO:
                mPhone.setTTYMode(Phone.TTY_MODE_VCO,
                        Message.obtain(mSetTTYComplete, EVENT_TTY_MODE_SET));
                break;
            default:
                mPhone.setTTYMode(Phone.TTY_MODE_OFF,
                        Message.obtain(mSetTTYComplete, EVENT_TTY_MODE_SET));
            }
            UpdatePreferredTtyModeSummary(buttonTtyMode);

            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    buttonTtyMode );
        }
    }

    /*
     * Callback to handle TTY mode update completions
     */

    // **Callback on TTY mode when complete.
    private Handler mSetTTYComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // query to make sure we're looking at the same data as that in the network.
            switch (msg.what) {
                case EVENT_TTY_EXECUTED:
                    handleQueryTtyResponse(msg);
                    onResume();
                    break;
                case EVENT_TTY_MODE_SET:
                    onResume();
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // TTY Object
    private void handleSetTTYMessage() {
        if (DBG) {
            log("handleSetTTYMessage: set TTY request complete, reading value from network.");
        }
        mPhone.queryTTYMode(Message.obtain(mQueryTTYComplete, EVENT_TTY_EXECUTED));
        android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Secure.TTY_MODE_ENABLED, preferredTtyMode );
    }

    private void handleQueryTtyResponse(Message msg) {
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            int ttyMode = ((int[])ar.result)[0];



            int settingsTtyMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    preferredTtyMode);

            //check that modemNetworkMode is from an accepted value
            if (ttyMode == Phone.TTY_MODE_OFF ||
                    ttyMode == Phone.TTY_MODE_HCO ||
                    ttyMode == Phone.TTY_MODE_VCO ||
                    ttyMode == Phone.TTY_MODE_FULL) {

                //check changes in modemNetworkMode and updates settingsNetworkMode
                if (ttyMode != settingsTtyMode) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "modemNetworkMode != settingsNetworkMode");
                    }

                    settingsTtyMode = ttyMode;

                    if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                            "settingsNetworkMode = " + settingsTtyMode);
                    }

                    //changes the Settings.System accordingly to modemNetworkMode
                    android.provider.Settings.Secure.putInt(
                            mPhone.getContext().getContentResolver(),
                            android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                            settingsTtyMode);
                }

                UpdatePreferredTtyModeSummary(ttyMode);
                // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                mButtonTTY.setValue(Integer.toString(ttyMode));
            } else {
                if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                resetTtyModeToDefault();
            }
        }
    }

    private void resetTtyModeToDefault() {
        //set the mButtonPreferredTtyMode
        mButtonTTY.setValue(Integer.toString(preferredTtyMode));
        //set the Settings.System
        android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_TTY_MODE,
                    preferredTtyMode );
        //Set the Modem
        mPhone.setTTYMode(preferredTtyMode,
                Message.obtain(mSetTTYComplete, EVENT_TTY_EXECUTED));
    }

    private void UpdatePreferredTtyModeSummary(int TtyMode) {
        switch(TtyMode) {
            case Phone.TTY_MODE_OFF:
                mButtonTTY.setSummary("TTY Mode OFF");
                break;
            case Phone.TTY_MODE_HCO:
                mButtonTTY.setSummary("TTY Mode HCO");
                break;
            case Phone.TTY_MODE_VCO:
                mButtonTTY.setSummary("TTY Mode VCO");
                break;
            case Phone.TTY_MODE_FULL:
                mButtonTTY.setSummary("TTY Mode Full");
                break;
            default:
                mButtonTTY.setSummary("TTY Mode OFF");
        }
    }

    /*
     * Callback to handle query completions
     */

    // **Callback on option getting when complete.
    private Handler mQueryTTYComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_TTY_EXECUTED:
                    handleQueryTTYModeMessage((AsyncResult) msg.obj);
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // TTY Object
    private int handleQueryTTYModeMessage(AsyncResult ar) {
        if (ar.exception != null) {
            if (DBG) log("handleQueryTTYModeMessage: Error getting TTY enable state.");
            return MSG_EXCEPTION;
        } else {
            if (DBG) log("handleQueryTTYModeMessage: TTY enable state successfully queried.");
            syncTTYState((int[]) ar.result);
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.TTY_MODE_ENABLED, preferredTtyMode );
        }
        return MSG_OK;
    }
    /**
     * Tells the StatusBar whether the TTY mode is enabled or disabled
     */
    private static void setStatusBarIcon(Context context, boolean enabled) {
        Intent ttyModeChanged = new Intent(TtyIntent.TTY_ENABLED_CHANGE_ACTION);
        ttyModeChanged.putExtra("ttyEnabled", enabled);
        context.sendBroadcast(ttyModeChanged);
    }

    // set the state of the UI based on TTY State
    private void syncTTYState(int ttyArray[]) {
    if (DBG) log("syncTTYState: Setting UI state consistent with TTY enable state of " +
           ((ttyArray[0] != 0) ? "ENABLED" : "DISABLED"));

        Context context = this;

        if (ttyArray[0] == 0) {
            // turn off TTY icon at StatusBar
            setStatusBarIcon(context, false);
        }
        else {
            //display TTY icon at StatusBar
            setStatusBarIcon(context, true);
        }
    }


    //VP object click
    private void handleVoicePrivacyClickRequest(boolean value) {
        mPhone.enableEnhancedVoicePrivacy(value, Message.obtain(mSetVoicePrivacyComplete,
                EVENT_ENHANCED_VP_EXECUTED));
    }

    // **Callback on VP mode when complete.
    private Handler mSetVoicePrivacyComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            // query to make sure we're looking at the same data as that in the network.
            switch (msg.what) {
                case EVENT_ENHANCED_VP_EXECUTED:
                    handleSetVPMessage();
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // VP Object Set
    private void handleSetVPMessage() {
        mPhone.getEnhancedVoicePrivacy(Message.obtain(mQueryVoicePrivacyComplete,
                EVENT_ENHANCED_VP_EXECUTED));
        android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED, preferredVPMode);
    }

    /*
     * Callback to handle VP query completions
     */

    // **Callback on option getting when complete.
    private Handler mQueryVoicePrivacyComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_ENHANCED_VP_EXECUTED:
                    handleQueryVPModeMessage((AsyncResult) msg.obj);
                    break;
                default:
                    // TODO: should never reach this, may want to throw exception
            }
        }
    };

    // VP Object Query
    private int handleQueryVPModeMessage(AsyncResult ar) {
        if (ar.exception != null) {
            if (DBG) {
                log("handleQueryVPModeMessage: Error getting VoicePrivacy enable state.");
            }
            return MSG_EXCEPTION;
        } else {
            if (DBG) {
                log("handleQueryVPModeMessage: VoicePrivacy enable state successfully queried.");
            }
            syncVPState((int[]) ar.result);
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED,
                    preferredVPMode );
        }
        return MSG_OK;
    }

    // set the state of the UI based on VP state
    private void syncVPState(int vpArray[]) {
        Log.d(LOG_TAG, "syncVPState: Setting UI state consistent with VP enable state of"
                + ((vpArray[0] != 0) ? "ENABLED" : "DISABLED"));
        mButtonVoicePrivacy.setChecked(vpArray[0] != 0);
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
