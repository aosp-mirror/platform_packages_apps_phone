/*
 * Copyright (c) 2012, Code Aurora Forum. All rights reserved.
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

import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.msim.MSimPhoneFactory;
import com.android.phone.OtaUtils.CdmaOtaScreenState;



public class MSPhone {

/* package */ static final String LOG_TAG = "MSPhone";

    /**
     * MSPhone -wide debug level:
     *   0 - no debug logging
     *   1 - normal debug logging if ro.debuggable is set (which is true in
     *       "eng" and "userdebug" builds but not "user" builds)
     *   2 - ultra-verbose debug logging
     *
     * Most individual classes in the phone app have a local DBG constant,
     * typically set to
     *   (MSPhone.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1)
     * or else
     *   (MSPhone.DBG_LEVEL >= 2)
     * depending on the desired verbosity.
     */
    /* package */ static final int DBG_LEVEL = 1;

    private static final boolean DBG =
            (MSPhone.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (MSPhone.DBG_LEVEL >= 2);

    public Phone mPhone;
    public boolean mIsSimPinEnabled;
    public boolean mIsSimPukLocked;

    // Last phone state seen by updatePhoneState()
    public PhoneConstants.State mLastPhoneState = PhoneConstants.State.IDLE;


    // Internal MSPhone cdma Call state tracker
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


    MSPhone(int subscription) {
        if (VDBG) Log.d(LOG_TAG, "Single Phone constructor: "+ subscription);
        // Get the phone
        mPhone = MSimPhoneFactory.getPhone(subscription);

        boolean phoneIsCdma = (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA);

        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            initializeCdmaVariables();
        }

    }

    public void initializeCdmaVariables() {

        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
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
        if (mPhone.getPhoneType() == PhoneConstants.PHONE_TYPE_CDMA) {
            mCdmaPhoneCallState = null;
            mCdmaOtaProvisionData = null;
            mCdmaOtaConfigData = null;
            mCdmaOtaScreenState = null;
            mCdmaOtaInCallScreenUiState = null;
        }
    }

};
