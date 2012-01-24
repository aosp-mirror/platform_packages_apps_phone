/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
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

import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.MSimPhoneFactory;
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

    public Phone mPhone;
    public boolean mIsSimPinEnabled;
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
        mPhone = MSimPhoneFactory.getPhone(subscription);

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

};
