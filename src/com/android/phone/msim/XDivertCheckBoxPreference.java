/*
 * Copyright (c) 2012 Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.phone;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Looper;
import android.preference.CheckBoxPreference;
import android.telephony.MSimTelephonyManager;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.util.AttributeSet;
import android.widget.Toast;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandException;
import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

// This class handles the actual processing required for XDivert feature.
// Handles the checkbox display.

public class XDivertCheckBoxPreference extends CheckBoxPreference {
    private static final String LOG_TAG = "XDivertCheckBoxPreference";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    int mNumPhones;
    int mAction; // Holds the CFNRc value.i.e.Registration/Disable
    int mReason; // Holds Call Forward reason.i.e.CF_REASON_NOT_REACHABLE
    Phone[] mPhoneObj; // Holds the phone objects for both the subs
    String[] mLine1Number; // Holds the line numbers for both the subs
    String[] mCFLine1Number;// Holds the CFNRc number for both the subs

    // Holds the status of Call Waiting for both the subs
    boolean mSub1CallWaiting;
    boolean mSub2CallWaiting;

    // Holds the value of XDivert feature
    boolean mXdivertStatus;
    TimeConsumingPreferenceListener mTcpListener;

    private MSimCallNotifier mCallNotif;
    private XDivertUtility mXDivertUtility;

    private static final int SUB1 = 0;
    private static final int SUB2 = 1;
    private static final int MESSAGE_GET_CFNRC = 2;
    private static final int MESSAGE_GET_CALL_WAITING = 3;
    private static final int MESSAGE_SET_CFNRC = 4;
    private static final int MESSAGE_SET_CALL_WAITING = 5;
    private static final int REVERT_SET_CFNRC = 6;
    private static final int REVERT_SET_CALL_WAITING = 7;
    private static final int START = 8;
    private static final int STOP = 9;

    public XDivertCheckBoxPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public XDivertCheckBoxPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.checkBoxPreferenceStyle);
    }

    public XDivertCheckBoxPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading,
            String sub1, String sub2) {
        mTcpListener = listener;
        PhoneApp app = PhoneApp.getInstance();
        mCallNotif = (MSimCallNotifier)app.notifier;

        Log.d(LOG_TAG, "init sub1 = " + sub1 + " , sub2 = " + sub2);
        mXDivertUtility = XDivertUtility.getInstance();
        // Store the numbers to shared preference
        mXDivertUtility.storeNumber(sub1, SUB1);
        mXDivertUtility.storeNumber(sub2, SUB2);

        processStartDialog(START, true);
        if (!skipReading) {
            mNumPhones = MSimTelephonyManager.getDefault().getPhoneCount();
            mPhoneObj = new Phone[mNumPhones];
            mLine1Number = new String[mNumPhones];
            mCFLine1Number = new String[mNumPhones];
            for (int i = 0; i < mNumPhones; i++) {
                mPhoneObj[i] = MSimPhoneApp.getInstance().getPhone(i);
                mLine1Number[i] = (i==0) ? sub1 : sub2;
            }

            //Query for CFNRc for SUB1.
            mPhoneObj[SUB1].getCallForwardingOption(CommandsInterface.CF_REASON_NOT_REACHABLE,
                    mGetOptionComplete.obtainMessage(MESSAGE_GET_CFNRC, SUB1, 0));
        }
    }

    @Override
    protected void onClick() {
        super.onClick();

        processStartDialog(START, false);
        Log.d(LOG_TAG,"onClick mXdivertStatus = " + mXdivertStatus);
        mSub1CallWaiting = mXdivertStatus;
        mSub2CallWaiting = mXdivertStatus;
        mAction = (mXdivertStatus ?
                CommandsInterface.CF_ACTION_DISABLE:
                CommandsInterface.CF_ACTION_REGISTRATION);
        mReason = CommandsInterface.CF_REASON_NOT_REACHABLE;
        int time = (mReason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;
        // Due to modem limitation, back-to-back requests to Sub1 & Sub2
        // cannot be sent.For ex: CF request on Sub1 followed by CF request on Sub2.
        // Miminum of 5000ms delay is needed to send the 2nd request.
        // Hence added wait.
        synchronized (this) {
            try {
                wait(5000);
            } catch (InterruptedException e) {
            }
        }

        //Check if CFNRc(SUB1) & CW(SUB1) is already set due to Partial Setting operation.
        //if set,then send the request for SUB2, else for SUB1.
        boolean requestForSub1 = PhoneNumberUtils.compare(mCFLine1Number[SUB1], mLine1Number[SUB2]);
        if ((requestForSub1) && (requestForSub1 == mSub1CallWaiting)
                && (mAction == CommandsInterface.CF_ACTION_REGISTRATION)) {
            //Set CFNRc for SUB2.
            mPhoneObj[SUB2].setCallForwardingOption(mAction,
                    mReason,
                    mLine1Number[SUB1],
                    time,
                    mSetOptionComplete.obtainMessage(MESSAGE_SET_CFNRC, SUB2, 0));
        } else {
            //Set CFNRc for SUB1.
            mPhoneObj[SUB1].setCallForwardingOption(mAction,
                    mReason,
                    mLine1Number[SUB2],
                    time,
                    mSetOptionComplete.obtainMessage(MESSAGE_SET_CFNRC, SUB1, 0));
        }
    }

    void queryCallWaiting(int arg) {
        //Get Call Waiting for "arg" subscription
        mPhoneObj[arg].getCallWaiting(mGetOptionComplete.obtainMessage(MESSAGE_GET_CALL_WAITING,
                arg, MESSAGE_GET_CALL_WAITING));
    }

    private boolean validateXDivert() {
        // Compares if - Sub1 line number == CFNRc number of Sub2
        // Sub2 line number == CFNRc number of Sub1.
        boolean check1 = PhoneNumberUtils.compare(mCFLine1Number[SUB1], mLine1Number[SUB2]);
        boolean check2 = PhoneNumberUtils.compare(mCFLine1Number[SUB2], mLine1Number[SUB1]);
        Log.d(LOG_TAG," CFNR sub1 = " + check1 + " CFNR sub2 = " + check2 + " mSub1CallWaiting = "
                + mSub1CallWaiting + " mSub2CallWaiting = " + mSub2CallWaiting);
        displayAlertMessage(check1, check2, mSub1CallWaiting, mSub2CallWaiting);
        if ((mCFLine1Number[SUB1] != null) && (mCFLine1Number[SUB2] != null)) {
            if ((check1) && (check1 == check2)) {
                if (mSub1CallWaiting && (mSub1CallWaiting == mSub2CallWaiting)) {
                    return true;
                }
                return false;
            }
            return false;
        }
        return false;
    }

    public void displayAlertMessage(boolean sub1Cfnrc, boolean sub2Cfnrc,
            boolean sub1CW, boolean sub2CW) {
        int subStatus[] = {R.string.xdivert_not_active, R.string.xdivert_not_active};
        int resSubId[] = {R.string.set_sub_1, R.string.set_sub_2};
        String dispMsg = "";

        for (int i=0; i < mNumPhones; i++) {
            // Status will be shown as active when:
            // -> Sub1 CFNR is set to Sub2 Line number.
            // -> And Call Waiting for Sub1 is true.
            // Similarly for Sub2.
            if((sub1Cfnrc == true) && (sub1Cfnrc == sub1CW) && (i == SUB1)) {
                subStatus[i] = R.string.xdivert_active;
            }
            if ((sub2Cfnrc == true) && (sub2Cfnrc == sub2CW) && (i == SUB2)) {
                subStatus[i] = R.string.xdivert_active;
            }

            dispMsg = dispMsg + (this.getContext().getString(resSubId[i])) + " " +
                                  (this.getContext().getString(subStatus[i])) + "\n";
        }

        Log.d(LOG_TAG, "displayAlertMessage:  dispMsg = " + dispMsg);
        new AlertDialog.Builder(this.getContext())
            .setTitle(R.string.xdivert_status)
            .setMessage(dispMsg)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(LOG_TAG, "displayAlertMessage:  onClick");
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(LOG_TAG, "displayAlertMessage:  onDismiss");
                    }
                });

    }

    private void processStopDialog(final int state, final boolean read) {
        if (mTcpListener != null) {
            Log.d(LOG_TAG,"stop");
            mTcpListener.onFinished(XDivertCheckBoxPreference.this, read);
        }
    }

    private void processStartDialog(final int state, final boolean read) {
        new Thread(new Runnable() {
            public void run() {
                Looper.prepare();
                int mode = state;
                if (mode == START) {
                    if (mTcpListener != null) {
                        Log.d(LOG_TAG,"start");
                        mTcpListener.onStarted(XDivertCheckBoxPreference.this, read);
                    }
                }
                Looper.loop();
            }
        }).start();
    }

    private final Handler mGetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            switch (msg.what) {
                case MESSAGE_GET_CFNRC:
                    handleGetCFNRCResponse(result, msg.arg1);
                    break;

                case MESSAGE_GET_CALL_WAITING:
                    handleGetCallWaitingResponse(result, msg.arg1, msg.arg2);
                    break;
            }
        }
    };

    private final Handler mSetOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            switch (msg.what) {
                case MESSAGE_SET_CFNRC:
                    handleSetCFNRCResponse(result, msg.arg1);
                    break;

                case MESSAGE_SET_CALL_WAITING:
                    handleSetCallWaitingResponse(result, msg.arg1);
                    break;
            }
        }
    };

    /*Revert operations would be handled as follows:
    **case 1: CFNRc(SUB1)->failure
    **        No Revert operation.
    **case 2: CFNRc(SUB1)->success, CW(SUB1)->failure
    **        Revert CFNRc(SUB1).
    **case 3: CFNRc(SUB1)->success, CW(SUB1)->success,
    **        CFNRc(SUB2)->failure
    **        No Revert operation. Display toast msg stating XDivert set only for Sub0.
    **case 4: CFNRc(SUB1)->success, CW(SUB1)->success,
    **        CFNRc(SUB2)->success, CW(SUB2)->failure
    **        Revert CFNRc(SUB2) and display toast msg stating XDivert set only for Sub0.
    */
    private final Handler mRevertOptionComplete = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult result = (AsyncResult) msg.obj;
            switch (msg.what) {
                case REVERT_SET_CFNRC:
                    handleRevertSetCFNRC(result, msg.arg2);
                    break;
            }
        }
    };

    private void handleGetCFNRCResponse(AsyncResult ar, int arg) {
        if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: done arg = " + arg);
        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: ar.exception = " + ar.exception);
            mTcpListener.onException(XDivertCheckBoxPreference.this,
                    (CommandException) ar.exception);
            processStopDialog(STOP, true);
        } else if (ar.userObj instanceof Throwable) {
                mTcpListener.onError(XDivertCheckBoxPreference.this, RESPONSE_ERROR);
                processStopDialog(STOP, true);
        } else {
            final CallForwardInfo cfInfoArray[] = (CallForwardInfo[]) ar.result;
            if (cfInfoArray == null) {
                if (DBG) Log.d(LOG_TAG, "handleGetCFResponse: cfInfoArray.length==0");
                mTcpListener.onError(XDivertCheckBoxPreference.this, RESPONSE_ERROR);
            } else {
                for (int i = 0, length = cfInfoArray.length; i < length; i++) {
                    if (DBG) Log.d(LOG_TAG, "handleGetCFResponse, cfInfoArray[" + i + "]="
                            + cfInfoArray[i]);
                    if ((CommandsInterface.SERVICE_CLASS_VOICE &
                            cfInfoArray[i].serviceClass) != 0 && arg == SUB1) {
                        CallForwardInfo info = cfInfoArray[i];
                        mCFLine1Number[SUB1] = info.number;

                        //Query Call Waiting for SUB1
                        queryCallWaiting(SUB1);
                    } else if ((CommandsInterface.SERVICE_CLASS_VOICE &
                             cfInfoArray[i].serviceClass) != 0 && arg == SUB2) {
                        CallForwardInfo info = cfInfoArray[i];
                        mCFLine1Number[SUB2] = info.number;

                        //Query Call Waiting for SUB2
                        queryCallWaiting(SUB2);
                    }
                }
            }
        }
    }

    private void handleSetCFNRCResponse(AsyncResult ar, int arg) {
        if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: done on Sub = " + arg);

        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleSetCFResponse: ar.exception = " + ar.exception);
            mTcpListener.onException(XDivertCheckBoxPreference.this,
                    (CommandException) ar.exception);
            handleRevertOperation(arg, REVERT_SET_CFNRC);
        } else if (ar.userObj instanceof Throwable) {
            if (mTcpListener != null) mTcpListener.onError(XDivertCheckBoxPreference.this,
                    RESPONSE_ERROR);
            handleRevertOperation(arg, REVERT_SET_CFNRC);
        } else {
            if (arg == SUB1) {
                mCFLine1Number[arg] = mLine1Number[SUB2];
            } else {
                mCFLine1Number[arg] = mLine1Number[SUB1];
            }

            //Set Call Waiting for the "arg" subscription
            mPhoneObj[arg].setCallWaiting(true,
                   mSetOptionComplete.obtainMessage(MESSAGE_SET_CALL_WAITING, arg, 0));
        }
    }

    private void handleGetCallWaitingResponse(AsyncResult ar, int arg1, int arg2) {
        if (ar.exception != null) {
            Log.d(LOG_TAG, "handleGetCallWaitingResponse: ar.exception = " + ar.exception);
            if (mTcpListener != null) {
                mTcpListener.onException(XDivertCheckBoxPreference.this,
                        (CommandException)ar.exception);
            }
            processStopDialog(STOP, true);
        } else if (ar.userObj instanceof Throwable) {
            if (mTcpListener != null) mTcpListener.onError(XDivertCheckBoxPreference.this,
                    RESPONSE_ERROR);
            processStopDialog(STOP, true);
        } else {
            if (DBG) Log.d(LOG_TAG, "handleGetCallWaitingResponse: CW state successfully queried.");
            //If cwArray[0] is = 1, then cwArray[1] must follow,
            //with the TS 27.007 service class bit vector of services
            //for which call waiting is enabled.
            int[] cwArray = (int[])ar.result;
            if (arg1 == SUB1) {
                mSub1CallWaiting = ((cwArray[0] == 1) && ((cwArray[1] & 0x01) == 0x01));
                Log.d(LOG_TAG,"CW for Sub0 = " + mSub1CallWaiting);
                // Due to modem limitation, back-to-back requests to Sub1 & Sub2
                // cannot be sent.For ex: CF request on Sub1 followed by CF request on Sub2.
                // Miminum of 5000ms delay is needed to send the 2nd request.
                // Hence added wait.
                synchronized (this) {
                    try {
                        wait(5000);
                    } catch (InterruptedException e) {
                    }
                }

                //Query Call Forward for SUB2
                mPhoneObj[SUB2].getCallForwardingOption(CommandsInterface.CF_REASON_NOT_REACHABLE,
                mGetOptionComplete.obtainMessage(MESSAGE_GET_CFNRC, SUB2, 0));
            } else if (arg1 == SUB2) {
                mSub2CallWaiting = ((cwArray[0] == 1) && ((cwArray[1] & 0x01) == 0x01));
                Log.d(LOG_TAG,"CW for Sub1 = " + mSub2CallWaiting);
                processStopDialog(STOP, true);

                //Check if CF numbers match the subscription's phone numbers and
                //Call Waiting is enabled, then set the checkbox accordingly.
                mXdivertStatus = validateXDivert();
                setChecked(mXdivertStatus);
                mCallNotif.onXDivertChanged(mXdivertStatus);
                mCallNotif.setXDivertStatus(mXdivertStatus);
            }
        }
    }

    private void handleSetCallWaitingResponse(AsyncResult ar, int arg) {
        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleSetCallWaitingResponse: ar.exception = " + ar.exception);
            handleRevertOperation(arg, REVERT_SET_CALL_WAITING);
        } else {
            Log.d(LOG_TAG, "handleSetCallWaitingResponse success arg = " + arg);
            int time = (mReason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;
            if (arg == SUB1) {
                // Due to modem limitation, back-to-back requests to Sub1 & Sub2
                // cannot be sent.For ex: CF request on Sub1 followed by CF request on Sub2.
                // Miminum of 5000ms delay is needed to send the 2nd request.
                // Hence added wait.
                synchronized (this) {
                    try {
                        wait(5000);
                    } catch (InterruptedException e) {
                    }
                }

                mSub1CallWaiting = (!mSub1CallWaiting);
                //Set Call Forward for SUB2
                mPhoneObj[SUB2].setCallForwardingOption(mAction,
                        mReason,
                        mLine1Number[SUB1],
                        time,
                        mSetOptionComplete.obtainMessage(MESSAGE_SET_CFNRC, SUB2, 0));
            } else if (arg == SUB2) {
                mSub2CallWaiting = !(mSub2CallWaiting);
                if (mTcpListener != null) {
                    mTcpListener.onFinished(XDivertCheckBoxPreference.this, false);
                }

                //After successful operation of setting CFNRc & CW,
                //set the checkbox accordingly.
                mXdivertStatus = validateXDivert();
                setChecked(mXdivertStatus);
                mCallNotif.onXDivertChanged(mXdivertStatus);
                mCallNotif.setXDivertStatus(mXdivertStatus);
           }
       }
   }

    private void handleRevertOperation(int subscription, int event) {
        Log.d(LOG_TAG,"handleRevertOperation sub = " + subscription + "Event = " + event);
        if (subscription == SUB1) {
            switch (event) {
                case REVERT_SET_CFNRC:
                    if (mTcpListener != null) {
                        mTcpListener.onFinished(XDivertCheckBoxPreference.this, false);
                    }
                break;

                case REVERT_SET_CALL_WAITING:
                    revertCFNRC(SUB1);
                break;
            }
        } else if (subscription == SUB2) {
            switch (event) {
                case REVERT_SET_CFNRC:
                    if (mTcpListener != null) {
                        mTcpListener.onFinished(XDivertCheckBoxPreference.this, false);
                    }

                    Toast toast = Toast.makeText(this.getContext(),
                            R.string.xdivert_partial_set,
                            Toast.LENGTH_LONG);
                            toast.show();
                break;

                case REVERT_SET_CALL_WAITING:
                    revertCFNRC(SUB2);
                break;
            }
        }
    }

    private void revertCFNRC(int arg) {
        int action = (mXdivertStatus ?
                CommandsInterface.CF_ACTION_REGISTRATION:
                CommandsInterface.CF_ACTION_DISABLE);
        int reason = CommandsInterface.CF_REASON_NOT_REACHABLE;
        int time = (reason != CommandsInterface.CF_REASON_NO_REPLY) ? 0 : 20;

        Log.d(LOG_TAG,"revertCFNRc arg = " + arg);
        if (arg == SUB1) {
            mPhoneObj[SUB1].setCallForwardingOption(action,
                    reason,
                    mLine1Number[SUB2],
                    time,
                    mRevertOptionComplete.obtainMessage(REVERT_SET_CFNRC,
                            action, SUB1));
        } else if (arg == SUB2) {
            mPhoneObj[SUB2].setCallForwardingOption(action,
                    reason,
                    mLine1Number[SUB1],
                    time,
                    mRevertOptionComplete.obtainMessage(REVERT_SET_CFNRC,
                            action, SUB2));
        }
    }

    private void handleRevertSetCFNRC(AsyncResult ar, int arg) {
        if (DBG) Log.d(LOG_TAG, "handleRevertSetCFNRC: done arg = " + arg+ "res = " + ar);
        processStopDialog(STOP, false);

        if (ar.exception != null) {
            if (DBG) Log.d(LOG_TAG, "handleRevertSetCFNRC: ar.exception = " + ar.exception);
            mTcpListener.onException(XDivertCheckBoxPreference.this,
                    (CommandException) ar.exception);
        } else if (ar.userObj instanceof Throwable) {
            if (mTcpListener != null) mTcpListener.onError(XDivertCheckBoxPreference.this,
                    RESPONSE_ERROR);
        }

        Toast toast = Toast.makeText(this.getContext(),
                R.string.xdivert_partial_set,
                Toast.LENGTH_LONG);
                toast.show();
    }

}
