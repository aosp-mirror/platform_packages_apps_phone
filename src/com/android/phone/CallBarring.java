/*
 * Copyright (c) 2013, The Linux Foundation. All rights reserved.
 Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
      copyright notice, this list of conditions and the following
      disclaimer in the documentation and/or other materials provided
      with the distribution.
    * Neither the name of The Linux Foundation, Inc. nor the names of its
      contributors may be used to endorse or promote products derived
      from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.android.phone;

import android.preference.PreferenceActivity;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Message;
import android.os.Handler;
import android.os.AsyncResult;
import android.util.Log;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.CommandsInterface;
import android.preference.ListPreference;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.preference.EditTextPreference;
import android.preference.PreferenceScreen;
import android.preference.Preference;
import android.view.WindowManager;
import android.provider.Settings;
import android.widget.Toast;
public class CallBarring extends PreferenceActivity implements DialogInterface.OnClickListener,
        Preference.OnPreferenceChangeListener, EditPinPreference.OnPinEnteredListener {

    private static final String  LOG_TAG = "CallBarring:<Helgan>";
    private static final boolean DBG = true;

    private static final String  BUTTON_CALL_BARRING_OUTGOING_KEY
            = "button_call_barring_outgoing_key";
    private static final String  BUTTON_CALL_BARRING_INCOMING_KEY
            = "button_call_barring_incoming_key";
    private static final String  BUTTON_CB_CANCEL_ALL_KEY         = "button_cb_cancel_all_key";
    private static final String  BUTTON_CB_CHANGE_PASSWORD_KEY    = "button_cb_change_password_key";

    private static final String  SETOUTGOING_KEY            = "SETOUTGOING_KEY";
    private static final String  SETINCOMING_KEY            = "SETINCOMING_KEY";
    private static final String  DIALOGSTATE_KEY            = "DIALOGSTATE_KEY";
    private static final String  CBDATASTALE_KEY            = "CBDATASTALE_KEY";
    private static final String  ISBUSYDIALOGAVAILABLE_KEY  = "ISBUSYDIALOGAVAILABLE_KEY";
    private static final String  PASSWORD_KEY               = "PASSWORD_KEY";
    private static final String  NEW_PSW_KEY                = "NEW_PSW_KEY";
    private static final String  ERROR_KEY                  = "ERROR_KEY";

    public static final String SUBSCRIPTION_ID = "SUBSCRIPTION_ID";

    private static final int MIN_PSW_LENGTH = 4;
    private static final int MAX_PSW_LENGTH = 8;

    // Order correspond to string xml file.
    private static final int CB_CLOSE_IN    = -2;
    private static final int CB_CLOSE_OUT   = -1;
    private static final int CB_BAOC        = 0;
    private static final int CB_BAOIC       = 1;
    private static final int CB_BAOICxH     = 2;
    private static final int CB_BAIC        = 3;
    private static final int CB_BAICr       = 4;
    private static final int CB_BA_ALL      = 5;
    private static final int CB_INVALID     = 99;

    private static final int EVENT_CB_QUERY_ALL     = 100;
    private static final int EVENT_CB_CANCEL_QUERY  = 200;
    private static final int EVENT_CB_CANCEL_ALL    = 300;
    private static final int EVENT_CB_SET_COMPLETE  = 400;
    private static final int EVENT_CB_CHANGE_PSW    = 500;


    // dialog id for create
    private static final int BUSY_DIALOG            = 100;
    private static final int EXCEPTION_ERROR        = 200;
    private static final int RESPONSE_ERROR         = 300;
    private static final int RADIO_OFF_ERROR        = 400;
    private static final int INITIAL_BUSY_DIALOG    = 500;
    private static final int INPUT_PSW_DIALOG       = 600;


    // status message sent back from handlers
    private static final int MSG_OK                     = 100;
    private static final int MSG_EXCEPTION              = 200;
    private static final int MSG_UNEXPECTED_RESPONSE    = 300;
    private static final int MSG_RADIO_OFF              = 400;

    private static final int OFF_MODE           = 0;
    private static final int CB_OUTGOING_MODE   = 1;
    private static final int CB_INCOMING_MODE   = 2;
    private static final int OLD_PSW_MODE       = 3;
    private static final int NEW_PSW_MODE       = 4;
    private static final int REENTER_PSW_MODE   = 5;
//  private static final int ERROR_PSW_MODE     = 6;

    private int mOutgoingState   = CB_CLOSE_OUT;
    private int mIncomingState   = CB_CLOSE_IN;
    private int mSetOutgoing     = CB_INVALID;
    private int mSetIncoming     = CB_INVALID;
    private int mDialogState     = OFF_MODE;
    private boolean mCBDataStale = true;
    private boolean mIsBusyDialogAvailable = false;
    private String mPassword = null;
    private String mNewPsw   = null;
    private String mError    = null;

    private Phone mPhone;

    private ListPreference mListOutgoing = null;
    private ListPreference mListIncoming = null;
    private EditPinPreference mEditDialogCancelAll = null;
    private EditPinPreference mEditDialogChangePSW = null;

    private static void log(String msg) {
        if (DBG) Log.d(LOG_TAG, msg);
    }

    private String cbToString(int cb) {

        String cbName = null;
        if (cb == 0) {
            cbName = CommandsInterface.CB_FACILITY_BAOC;
        } else if (cb == 1) {
            cbName = CommandsInterface.CB_FACILITY_BAOIC;
        } else if (cb == 2) {
            cbName = CommandsInterface.CB_FACILITY_BAOICxH;
        } else if (cb == 3) {
            cbName = CommandsInterface.CB_FACILITY_BAIC;
        } else if (cb == 4) {
            cbName = CommandsInterface.CB_FACILITY_BAICr;
        } else if (cb == 5) {
            cbName = CommandsInterface.CB_FACILITY_BA_ALL;
        }
        return cbName;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.call_barring);

        if (DBG) log("onCreate: CallBarring");

        mPhone = PhoneGlobals.getInstance().getPhone();
        PreferenceScreen prefSet = getPreferenceScreen();

        mListOutgoing = (ListPreference) prefSet.findPreference(BUTTON_CALL_BARRING_OUTGOING_KEY);
        mListIncoming = (ListPreference) prefSet.findPreference(BUTTON_CALL_BARRING_INCOMING_KEY);
        mListOutgoing.setOnPreferenceChangeListener(this);
        mListIncoming.setOnPreferenceChangeListener(this);

        mEditDialogCancelAll = (EditPinPreference) prefSet.findPreference(BUTTON_CB_CANCEL_ALL_KEY);
        mEditDialogChangePSW = (EditPinPreference) prefSet.findPreference(
                BUTTON_CB_CHANGE_PASSWORD_KEY);
        mEditDialogCancelAll.setOnPinEnteredListener(this);
        mEditDialogChangePSW.setOnPinEnteredListener(this);

        if (icicle != null) {
            mOutgoingState = icicle.getInt(BUTTON_CALL_BARRING_OUTGOING_KEY);
            mIncomingState = icicle.getInt(BUTTON_CALL_BARRING_INCOMING_KEY);
            mSetOutgoing = icicle.getInt(SETOUTGOING_KEY);
            mSetIncoming = icicle.getInt(SETINCOMING_KEY);
            mDialogState = icicle.getInt(DIALOGSTATE_KEY);
            mCBDataStale = icicle.getBoolean(CBDATASTALE_KEY);
            mIsBusyDialogAvailable = icicle.getBoolean(ISBUSYDIALOGAVAILABLE_KEY);
            mPassword = icicle.getString(PASSWORD_KEY);
            mNewPsw = icicle.getString(NEW_PSW_KEY);
            mError = icicle.getString(ERROR_KEY);
        } else {
            mOutgoingState = CB_CLOSE_OUT;
            mIncomingState = CB_CLOSE_IN;
            mSetOutgoing = CB_INVALID;
            mSetIncoming = CB_INVALID;
            mDialogState = OLD_PSW_MODE;
            mCBDataStale = true;
            mIsBusyDialogAvailable = false;
            mPassword = null;
            mNewPsw = null;
            mError = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (DBG) log("onResume: CallBarring");

        if (mCBDataStale) {
            // If airplane mode is on, do not bother querying.
            if (Settings.System.getInt(getContentResolver(),
                    Settings.System.AIRPLANE_MODE_ON, 0) <= 0 ) {
                queryAllCBOptions();
            } else {

          if (DBG) log("onResume: airplane mode on");
                showDialog (RADIO_OFF_ERROR);
                finish();
            }
        } else {
            mListOutgoing.setValue(String.valueOf(mOutgoingState));
            mListOutgoing.setSummary(mListOutgoing.getEntry());
            mListIncoming.setValue(String.valueOf(mIncomingState));
            mListIncoming.setSummary(mListIncoming.getEntry());
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        if (DBG) log("onSaveInstanceState: saving relevant UI state.");

        outState.putInt(BUTTON_CALL_BARRING_OUTGOING_KEY, mOutgoingState);
        outState.putInt(BUTTON_CALL_BARRING_INCOMING_KEY, mIncomingState);
        outState.putInt(SETOUTGOING_KEY, mSetOutgoing);
        outState.putInt(SETINCOMING_KEY, mSetIncoming);
        outState.putInt(DIALOGSTATE_KEY, mDialogState);
        outState.putBoolean(CBDATASTALE_KEY, mCBDataStale);
        outState.putBoolean(ISBUSYDIALOGAVAILABLE_KEY, mIsBusyDialogAvailable);
        outState.putString(PASSWORD_KEY, mPassword);
        outState.putString(NEW_PSW_KEY, mNewPsw);
        outState.putString(ERROR_KEY, mError);
    }

    // Request to begin querying for call barring.
    private void queryAllCBOptions() {
        showDialog(INITIAL_BUSY_DIALOG);
        mPhone.getCallBarringOption (CommandsInterface.CB_FACILITY_BAOC, "",
                Message.obtain(mGetAllCBOptionsComplete, EVENT_CB_QUERY_ALL, CB_BAOC, 0));
    }

    // callback after each step of querying for all options.
    private Handler mGetAllCBOptionsComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            int status = MSG_OK;

            switch (msg.what) {
                case EVENT_CB_CANCEL_QUERY:
                    removeMessages(EVENT_CB_QUERY_ALL);
                    removeDialog(INITIAL_BUSY_DIALOG);
                    finish();
                    break;
                case EVENT_CB_QUERY_ALL:
                    status = handleGetCBMessage(ar, msg.arg1);

                    if (status != MSG_OK) {
                        removeDialog(INITIAL_BUSY_DIALOG);
                        Log.d("CallBarring","EXCEPTION_ERROR!*********");
                        return;
                    }

                    switch (msg.arg1) {
                        case CB_BAOC:
                            mPhone.getCallBarringOption (CommandsInterface.CB_FACILITY_BAOIC, "",
                                    Message.obtain(mGetAllCBOptionsComplete,
                                    EVENT_CB_QUERY_ALL, CB_BAOIC, 0));
                            break;
                        case CB_BAOIC:
                            mPhone.getCallBarringOption (CommandsInterface.CB_FACILITY_BAOICxH, "",
                                    Message.obtain(mGetAllCBOptionsComplete, EVENT_CB_QUERY_ALL,
                                    CB_BAOICxH, 0));
                            break;
                        case CB_BAOICxH:
                            mPhone.getCallBarringOption (CommandsInterface.CB_FACILITY_BAIC, "",
                                    Message.obtain(mGetAllCBOptionsComplete, EVENT_CB_QUERY_ALL,
                                    CB_BAIC, 0));
                            break;
                        case CB_BAIC:
                            mPhone.getCallBarringOption (CommandsInterface.CB_FACILITY_BAICr, "",
                                    Message.obtain(mGetAllCBOptionsComplete, EVENT_CB_QUERY_ALL,
                                    CB_BAICr, 0));
                            break;
                        case CB_BAICr:
                            mCBDataStale = false;
                            syncUiWithState();
                            removeDialog(INITIAL_BUSY_DIALOG);
                            break;
                        default:
                            // TODO: should never reach this, may want to throw exception
                    }
                    break;

                default:
                    // TODO: should never reach this, may want to throw exception
                    break;
            }
        }
    };

    private int handleGetCBMessage(AsyncResult ar, int reason) {
        if (ar.exception != null) {
            Log.e(LOG_TAG, "handleGetCBMessage: Error getting CB enable state.");
            return MSG_EXCEPTION;
        } else if (ar.userObj instanceof Throwable) {
            Log.e(LOG_TAG, "handleGetCBMessage: Error during set call barring, reason: " + reason +
                    " exception: " + ((Throwable) ar.userObj).toString());
            return MSG_UNEXPECTED_RESPONSE;
        } else {
            int cbState = ((int[])ar.result)[0];
            if (cbState == 0) {
                if (mOutgoingState == reason) {
                    mOutgoingState = CB_CLOSE_OUT;
                }
                if (mIncomingState == reason) {
                    mIncomingState = CB_CLOSE_IN;
                }
            } else if (cbState == 1) {
                switch (reason) {
                    case CB_BAOC:
                    case CB_BAOIC:
                    case CB_BAOICxH:
                        mOutgoingState = reason;
                        break;
                    case CB_BAIC:
                    case CB_BAICr:
                        mIncomingState = reason;
                        break;
                    default:
                }
            } else {
                Log.e(LOG_TAG, "handleGetCBMessage: Error getting CB state, unexpected value.");
                return MSG_UNEXPECTED_RESPONSE;
            }
        }
        return MSG_OK;
    }

    private void syncUiWithState() {
        mListOutgoing.setValue(String.valueOf(mOutgoingState));
        mListOutgoing.setSummary(mListOutgoing.getEntry());
        mListIncoming.setValue(String.valueOf(mIncomingState));
        mListIncoming.setSummary(mListIncoming.getEntry());
        mSetOutgoing = CB_INVALID;
        mSetIncoming = CB_INVALID;
    }

    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (mListOutgoing == preference) {
            if (!mListOutgoing.getValue().equals((String)newValue)) {
                mSetOutgoing = Integer.valueOf((String)newValue);
                resetDialogState(CB_OUTGOING_MODE);
                showPswDialog();
                return false;
            }
        } else if (mListIncoming == preference) {
            if (!mListOutgoing.getValue().equals((String)newValue)) {
                mSetIncoming = Integer.valueOf((String)newValue);
                resetDialogState(CB_INCOMING_MODE);
                showPswDialog();
                return false;

            }
        }
        return true;
    }

    private void resetDialogState(int mode) {
        mDialogState = mode;
        mPassword = null;
        mNewPsw   = null;
        mError    = null;
        mEditDialogChangePSW.setDialogMessage(getResources().getString(R.string.psw_enter_old));
    }

    private void showPswDialog() {
        if (mDialogState == OFF_MODE) {
            return;
        }

        String message = null;
        switch (mDialogState) {
            case CB_OUTGOING_MODE:
            case CB_INCOMING_MODE:
                mEditDialogChangePSW.setDialogTitle(getResources().getString(
                        R.string.input_password));
                break;
            case OLD_PSW_MODE:
                message = getResources().getString(R.string.psw_enter_old);
                mEditDialogChangePSW.setDialogTitle(getResources().getString(
                        R.string.labelCbChangePassword));
                break;
            case NEW_PSW_MODE:
                message = getResources().getString(R.string.psw_enter_new);
                mEditDialogChangePSW.setDialogTitle(getResources().getString(
                        R.string.labelCbChangePassword));
                break;
            case REENTER_PSW_MODE:
                message = getResources().getString(R.string.psw_reenter_new);
                mEditDialogChangePSW.setDialogTitle(getResources().getString(
                        R.string.labelCbChangePassword));
                break;
            default:
        }

        if (mError != null) {
            if (message != null) {
                message = mError + "\n" + message;
            } else {
                message = mError;
            }
            mError = null;
        }

        mEditDialogChangePSW.setText("");
        mEditDialogChangePSW.setDialogMessage(message);
        mEditDialogChangePSW.showPinDialog();
    }

    public void onPinEntered(EditPinPreference preference, boolean positiveResult) {
        if (!positiveResult) {
            mSetOutgoing = CB_INVALID;
            mSetIncoming = CB_INVALID;
            resetDialogState(OLD_PSW_MODE);
            return;
        }

        // read and clear the preference value for next input.
        String tmpPsw = preference.getText();
        preference.setText("");

        if (preference == mEditDialogCancelAll) {
            if ((mOutgoingState == CB_CLOSE_OUT) && (mIncomingState == CB_CLOSE_IN)) {
                showToast(getResources().getString(R.string.no_call_barring));
                return;
            }

            if (!reasonablePSW(tmpPsw)) {
                mError = getResources().getString(R.string.invalidPsw);
                showCancelDialog();
                return;
            }

            showDialog(BUSY_DIALOG);
            mPhone.setCallBarringOption(CommandsInterface.CB_FACILITY_BA_ALL, false, tmpPsw,
                    Message.obtain(mSetOptionComplete, EVENT_CB_CANCEL_ALL));
        } else if (preference == mEditDialogChangePSW) {
            switch (mDialogState) {
                case CB_OUTGOING_MODE:
                case CB_INCOMING_MODE:
                    if (!reasonablePSW(tmpPsw)) {
                        mError = getResources().getString(R.string.invalidPsw);
                        showPswDialog();
                        return;
                    }

                    showDialog(BUSY_DIALOG);

                    String facility = null;
                    boolean lockState;

                    if ((mSetOutgoing != CB_INVALID) && (mSetOutgoing != mOutgoingState)) {
                        lockState = (mSetOutgoing >= 0);
                        facility = cbToString(lockState ? mSetOutgoing : mOutgoingState);
                    } else if ((mSetIncoming != CB_INVALID) && (mSetIncoming != mIncomingState)) {
                        lockState = (mSetIncoming >= 0);
                        facility = cbToString(lockState ? mSetIncoming : mIncomingState);
                    } else {
                        Log.e(LOG_TAG, "Call barring state error!");
                        return;
                    }

                    mPhone.setCallBarringOption(facility, lockState, tmpPsw,
                            Message.obtain(mSetOptionComplete, EVENT_CB_SET_COMPLETE));
                    resetDialogState(OLD_PSW_MODE);
                    break;
                case OLD_PSW_MODE:
                    if (!reasonablePSW(tmpPsw)) {
                        mError = getResources().getString(R.string.invalidPsw);
                        showPswDialog();
                        return;
                    } else {
                        mPassword = tmpPsw;
                        mDialogState = NEW_PSW_MODE;
                        showPswDialog();
                    }
                    break;
                case NEW_PSW_MODE:
                    if (!reasonablePSW(tmpPsw)) {
                        mError = getResources().getString(R.string.invalidPsw);
                        showPswDialog();
                        return;
                    } else {
                        mNewPsw = tmpPsw;
                        mDialogState = REENTER_PSW_MODE;
                        showPswDialog();
                    }
                    break;
                case REENTER_PSW_MODE:
                        if (!tmpPsw.equals(mNewPsw)) {
                            mError = getResources().getString(R.string.cb_psw_dont_match);
                            mNewPsw = null;
                            mDialogState = NEW_PSW_MODE;
                            showPswDialog();
                        } else {
                            mError = null;
                            showDialog(BUSY_DIALOG);
                            mPhone.requestChangeCbPsw(CommandsInterface.CB_FACILITY_BA_ALL,
                                    mPassword, mNewPsw, Message.obtain(mSetOptionComplete,
                                    EVENT_CB_CHANGE_PSW));
                            resetDialogState(OLD_PSW_MODE);
                        }
                    break;
                case OFF_MODE:
                default:
            }
        }
    }

    private Handler mSetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {

            AsyncResult ar = (AsyncResult) msg.obj;

            switch (msg.what) {
                case EVENT_CB_CANCEL_ALL:
                    dismissBusyDialog();
                    if (ar.exception != null) {
                        finish();
                        showToast(getResources().getString(R.string.response_error));
                    } else {
                        mOutgoingState = CB_CLOSE_OUT;
                        mIncomingState = CB_CLOSE_IN;
                        syncUiWithState();
                        showToast(getResources().getString(R.string.operation_successfully));
                    }
                    break;
                case EVENT_CB_SET_COMPLETE:
                    dismissBusyDialog();
                    if (ar.exception != null) {
                        mSetOutgoing = CB_INVALID;
                        mSetIncoming = CB_INVALID;
                        showToast(getResources().getString(R.string.response_error));
                    } else {
                        if (mOutgoingState != mSetOutgoing && mSetOutgoing != CB_INVALID) {
                            mOutgoingState = mSetOutgoing;
                        }
                        if (mIncomingState != mSetIncoming && mSetIncoming != CB_INVALID) {
                            mIncomingState = mSetIncoming;
                        }
                        syncUiWithState();
                        showToast(getResources().getString(R.string.operation_successfully));
                    }
                case EVENT_CB_CHANGE_PSW:
                    dismissBusyDialog();
                    if (ar.exception != null) {
                        showToast(getResources().getString(R.string.response_error));
                    } else {
                        showToast(getResources().getString(R.string.operation_successfully));
                    }
                default:
                    // TODO: should never reach this, may want to throw exception
                    break;
            }
        }
    };

    private void showCancelDialog() {
        mEditDialogCancelAll.setText(mPassword);
        mEditDialogCancelAll.setDialogMessage(mError);
        mEditDialogCancelAll.showPinDialog();
    }

    private boolean reasonablePSW(String psw) {
        if (psw == null || psw.length() < MIN_PSW_LENGTH || psw.length() > MAX_PSW_LENGTH) {
            return false;
        } else {
            return true;
        }
    }

    public void onClick(DialogInterface dialog, int which) {
        dialog.dismiss();

    }



    private void showToast(String message) {
        Toast.makeText(this, message,
                       Toast.LENGTH_SHORT).show();
    }

    @Override
    protected Dialog onCreateDialog(int id) {

        if ((id == BUSY_DIALOG) || (id == INITIAL_BUSY_DIALOG)) {
            ProgressDialog dialog = new ProgressDialog(this);
            dialog.setTitle(getText(R.string.updating_title));
            dialog.setIndeterminate(true);

            switch (id) {
                case BUSY_DIALOG:
                    mIsBusyDialogAvailable = true;
                    dialog.setCancelable(false);
                    dialog.setMessage(getText(R.string.updating_settings));
                    break;
                case INITIAL_BUSY_DIALOG:
                    dialog.setCancelable(true);
                    dialog.setCancelMessage(
                            mGetAllCBOptionsComplete.obtainMessage(EVENT_CB_CANCEL_QUERY));
                    dialog.setMessage(getText(R.string.reading_settings));
                    break;
            }

            return dialog;

        // Handle error dialog codes
        } else if ((id == RESPONSE_ERROR) || (id == EXCEPTION_ERROR) ||
                (id == RADIO_OFF_ERROR)) {

            AlertDialog.Builder b = new AlertDialog.Builder(this);

            int msgId;
            int titleId = R.string.error_updating_title;
            switch (id) {
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

    private final void dismissBusyDialog() {
        if (mIsBusyDialogAvailable) {
            removeDialog(BUSY_DIALOG);
            mIsBusyDialogAvailable = false;
        }
    }

}
