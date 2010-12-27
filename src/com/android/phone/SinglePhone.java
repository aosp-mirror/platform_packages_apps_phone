/*
 * Copyright (c) 2010, Code Aurora Forum. All rights reserved.
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
import android.app.Application;
import android.app.ProgressDialog;
import android.os.AsyncResult;
import android.content.Intent;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.IccCard;
import com.android.internal.telephony.MmiCode;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.OtaUtils.CdmaOtaScreenState;



public class SinglePhone {

/* package */ static final String LOG_TAG = "SinglePhone";

    /**
     * SinglePhone -wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (SinglePhone.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (SinglePhone.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     */
    /* package */ static final int DBG_LEVEL = 1;

    private static final boolean DBG =
            (SinglePhone.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (SinglePhone.DBG_LEVEL >= 2);

     // Message codes; see mHandler below.
    private static final int EVENT_SIM_STATE_CHANGED = 8;
    private static final int EVENT_UNSOL_CDMA_INFO_RECORD = 12;
    private static final int EVENT_DOCK_STATE_CHANGED = 13;
    private static final int EVENT_TECHNOLOGY_CHANGED = 17;

    // The MMI codes are also used by the InCallScreen.
    public static final int MMI_COMPLETE = 52;
    public static final int MMI_CANCEL = 53;


    public Phone mPhone;
    public int mPhoneType;

    //public SinglePhoneHandler mHandler; //handler for SinglePhone
    public boolean mIsSimPinEnabled;
    public String mCachedSimPin;
    public boolean mIsSimPukLocked;
    // Last phone state seen by updatePhoneState()
    public Phone.State mLastPhoneState = Phone.State.IDLE;


    // Internal SinglePhone cdma Call state tracker
    public CdmaPhoneCallState mCdmaPhoneCallState = null;

    // Following are the CDMA OTA information Objects used during OTA Call.
    // cdmaOtaProvisionData object store static OTA information that needs
    // to be maintained even during Slider open/close scenarios.
    // cdmaOtaConfigData object stores configuration info to control visiblity
    // of each OTA Screens.
    // cdmaOtaScreenState object store OTA Screen State information.
    public OtaUtils.CdmaOtaProvisionData mCdmaOtaProvisionData = null;
    public OtaUtils.CdmaOtaConfigData mCdmaOtaConfigData = null;
    public OtaUtils.CdmaOtaScreenState mCdmaOtaScreenState = null;
    public OtaUtils.CdmaOtaInCallScreenUiState mCdmaOtaInCallScreenUiState = null;


    SinglePhone(int subscription) {
        if (VDBG) Log.d(LOG_TAG, "Single Phone constructor: "+ subscription);
        // Get the phone
        mPhone = PhoneFactory.getPhone(subscription);
        mPhoneType = mPhone.getPhoneType();
        //mHandler = new SinglePhoneHandler(mPhone);
        //PhoneApp app = PhoneApp.getInstance();

        // register for MMI/USSD
        //mPhone.registerForMmiComplete(mHandler, MMI_COMPLETE, null);

        // register connection tracking to PhoneUtils
        //PhoneUtils.initializeConnectionHandler(mPhone);
        // TODO: Register for Cdma Information Records
        // phone.registerCdmaInformationRecord(mHandler, EVENT_UNSOL_CDMA_INFO_RECORD, null);

        boolean phoneIsCdma = (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA);

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            initializeCdmaVariables();
        }

    }

    public void initializeCdmaVariables() {

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            // Create an instance of CdmaPhoneCallState and initialize it to IDLE
            mCdmaPhoneCallState = new CdmaPhoneCallState();
            mCdmaPhoneCallState.CdmaPhoneCallStateInit();

            if (mCdmaOtaProvisionData == null) {
                mCdmaOtaProvisionData = new OtaUtils.CdmaOtaProvisionData();
            }
            if (mCdmaOtaConfigData == null ) {
                mCdmaOtaConfigData = new OtaUtils.CdmaOtaConfigData();
            }
            if (mCdmaOtaScreenState == null ) {
                mCdmaOtaScreenState = new OtaUtils.CdmaOtaScreenState();
            }
            if (mCdmaOtaInCallScreenUiState == null) {
                mCdmaOtaInCallScreenUiState = new OtaUtils.CdmaOtaInCallScreenUiState();
            }
        }
    }

    public void clearCdmaVariables() {

        if (mPhone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            mCdmaPhoneCallState = null;
            mCdmaOtaProvisionData = null;
            mCdmaOtaConfigData = null;
            mCdmaOtaScreenState = null;
            mCdmaOtaInCallScreenUiState = null;
        }
    }

    /*public class SinglePhoneHandler extends Handler {
        Phone mPhone;

        SinglePhoneHandler(Phone phone) {
            mPhone = phone;
        }
        @Override
        public void handleMessage(Message msg) {
            PhoneApp app = PhoneApp.getInstance();
            app.checkPhoneType();
            switch (msg.what) {

                case MMI_COMPLETE:
                    onMMIComplete((AsyncResult) msg.obj);
                    break;

                case MMI_CANCEL:
                    PhoneUtils.cancelMmiCode(mPhone);
                    break;

                case EVENT_SIM_STATE_CHANGED:
                    // Marks the event where the SIM goes into ready state.
                    // Right now, this is only used for the PUK-unlocking
                    // process.
                    if (msg.obj.equals(IccCard.INTENT_VALUE_ICC_SUBSCRIPTION_READY)) {
                        // when the right event is triggered and there
                        // are UI objects in the foreground, we close
                        // them to display the lock panel.
                        Activity pukEntryActivity;
                        ProgressDialog pukEntryProgressDialog;
                        pukEntryActivity = app.getPUKEntryActivity();
                        pukEntryProgressDialog = app.getPUKEntryProgressDialog();
                        if (pukEntryActivity != null) {
                            pukEntryActivity.finish();
                            pukEntryActivity = null;
                        }
                        if (pukEntryProgressDialog != null) {
                            pukEntryProgressDialog.dismiss();
                            pukEntryProgressDialog = null;
                        }
                    }
                    break;

                case EVENT_UNSOL_CDMA_INFO_RECORD:
                    //TODO: handle message here;
                    break;

                case EVENT_TECHNOLOGY_CHANGED:
                    // Nothing to do here. already handled by checkPhoneType above
                    break;

                case EVENT_DOCK_STATE_CHANGED:
                    // If the phone is docked/undocked during a call, and no wired or BT headset
                    // is connected: turn on/off the speaker accordingly.
                    boolean inDockMode = false;
                    if (app.mDockState == Intent.EXTRA_DOCK_STATE_DESK ||
                            app.mDockState == Intent.EXTRA_DOCK_STATE_CAR) {
                        inDockMode = true;
                    }
                    if (VDBG) Log.d(LOG_TAG, "received EVENT_DOCK_STATE_CHANGED. Phone inDock = "
                            + inDockMode);

                    Phone.State phoneState = mPhone.getState();
                    BluetoothHandsfree btHandsfree;
                    btHandsfree = app.getBluetoothHandsfree();
                    if (phoneState == Phone.State.OFFHOOK &&
                            !app.isHeadsetPlugged() &&
                            !(btHandsfree != null && btHandsfree.isAudioOn())) {
                        PhoneUtils.turnOnSpeaker(app.getAppContext(), inDockMode, true);

                        InCallScreen inCallScreen;
                        inCallScreen = app.getInCallScreen();
                        if (inCallScreen != null) {
                            inCallScreen.requestUpdateTouchUi();
                        }
                    }
                    break;

                default:
                    break;
            }
        }
     }
*/
 /*    private void onMMIComplete(AsyncResult r) {
        if (VDBG) Log.d(LOG_TAG, "onMMIComplete()...");
        MmiCode mmiCode = (MmiCode) r.result;
        PhoneUtils.displayMMIComplete(mPhone, PhoneApp.getInstance(), mmiCode, null, null);
    }*/
};
