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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.telephony.MSimTelephonyManager;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.msim.SubscriptionManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

public class XDivertUtility {
    static final String LOG_TAG = "XDivertUtility";
    private static final int SIM_RECORDS_LOADED = 1;
    private static final int EVENT_SUBSCRIPTION_DEACTIVATED = 2;

    private static final String SIM_IMSI = "sim_imsi_key";
    private static final String SIM_NUMBER = "sim_number_key";

    private static final int SUB1 = 0;
    private static final int SUB2 = 1;

    private MSimCallNotifier mCallNotifier;
    private Context mContext;
    private Phone mPhone;
    private MSimPhoneApp mApp;
    protected static XDivertUtility sMe;
    private BroadcastReceiver mReceiver;

    private String[] mImsiFromSim;
    private String[] mStoredImsi;
    private String[] mLineNumber;

    private int mNumPhones = 0;
    private boolean[] mHasImsiChanged;

    public XDivertUtility() {
        sMe = this;
    }

    static XDivertUtility init(MSimPhoneApp app, Phone phone ,
            MSimCallNotifier callNotifier, Context context) {
        synchronized (XDivertUtility.class) {
            if (sMe == null) {
                sMe = new XDivertUtility(app, phone, callNotifier, context);
            } else {
                Log.wtf(LOG_TAG, "init() called multiple times!  sInstance = " + sMe);
            }
            return sMe;
        }
    }

    private XDivertUtility(MSimPhoneApp app, Phone phone, MSimCallNotifier callNotifier,
            Context context) {
        Log.d(LOG_TAG, "onCreate()...");
        SubscriptionManager subMgr = SubscriptionManager.getInstance();

        mApp = app;
        mPhone = phone;
        mCallNotifier = callNotifier;
        mContext = context;

        mReceiver = new XDivertBroadcastReceiver();
        mNumPhones = MSimTelephonyManager.getDefault().getPhoneCount();

        mImsiFromSim = new String[mNumPhones];
        mStoredImsi = new String[mNumPhones];
        mLineNumber = new String[mNumPhones];
        mHasImsiChanged = new boolean[mNumPhones];

        for (int i = 0; i < mNumPhones; i++) {
            subMgr.registerForSubscriptionDeactivated(i, mHandler,
                    EVENT_SUBSCRIPTION_DEACTIVATED, null);
            mPhone = app.getPhone(i);
            // register for SIM_RECORDS_LOADED
            mPhone.registerForSimRecordsLoaded(mHandler, SIM_RECORDS_LOADED, i);
            mHasImsiChanged[i] = true;
        }
        // Register for intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED);
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    static XDivertUtility getInstance() {
        return sMe;
    }

    /**
     * Receiver for intent broadcasts the XDivertUtility cares about.
     */
    private class XDivertBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.v(LOG_TAG,"Action intent recieved:"+action);
            //gets the subscription information ( "0" or "1")
            int subscription = intent.getIntExtra(SUBSCRIPTION_KEY, mApp.getDefaultSubscription());
            if (action.equals(TelephonyIntents.ACTION_RADIO_TECHNOLOGY_CHANGED)) {
                Phone phone = mApp.getPhone(subscription);
                phone.unregisterForSimRecordsLoaded(mHandler);
                phone.registerForSimRecordsLoaded(mHandler, SIM_RECORDS_LOADED, subscription);
            }
        }
    }

    Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            AsyncResult ar;
            switch (msg.what) {
                case SIM_RECORDS_LOADED:
                    ar = (AsyncResult)msg.obj;
                    boolean status = false;

                    if (ar.exception != null) {
                        break;
                    }
                    int subscription = (Integer)ar.userObj;
                    Log.d(LOG_TAG, "subscription = " + subscription);
                    // Get the Imsi value from the SIM records. Retrieve the stored Imsi
                    // value from the shared preference. If both are same, then read the
                    // stored phone number from the shared preference, else prompt the
                    // user to enter them.
                    mImsiFromSim[subscription] = MSimTelephonyManager.getDefault()
                            .getSubscriberId(subscription);
                    mStoredImsi[subscription] = getSimImsi(subscription);
                    Log.d(LOG_TAG, "SIM_RECORDS_LOADED mImsiFromSim = " +
                            mImsiFromSim[subscription] + "mStoredImsi = " +
                            mStoredImsi[subscription]);
                    if ((mStoredImsi[subscription] == null) || ((mImsiFromSim[subscription] != null)
                            && (!mImsiFromSim[subscription].equals(mStoredImsi[subscription])))) {
                        // Imsi from SIM does not match the stored Imsi.
                        // Hence reset the values.
                        mCallNotifier.setXDivertStatus(false);
                        setSimImsi(mImsiFromSim[subscription], subscription);
                        storeNumber(null, subscription);
                    } else if ((mStoredImsi[subscription] != null) &&
                            (mImsiFromSim[subscription] != null) &&
                            mImsiFromSim[subscription].equals(mStoredImsi[subscription])) {
                        // Imsi from SIM matches the stored Imsi so get the stored lineNumbers
                        mLineNumber[subscription] = getNumber(subscription);
                        mHasImsiChanged[subscription] = false;
                        Log.d(LOG_TAG, "Stored Line Number = " + mLineNumber[subscription]);
                    }

                    // Only if Imsi has not changed, query for XDivert status from shared pref
                    // and update the notification bar.
                    if ((!mHasImsiChanged[SUB1]) && (!mHasImsiChanged[SUB2])) {
                        status = mCallNotifier.getXDivertStatus();
                        mCallNotifier.onXDivertChanged(status);
                    }
                    break;
                case EVENT_SUBSCRIPTION_DEACTIVATED:
                    Log.d(LOG_TAG, "EVENT_SUBSCRIPTION_DEACTIVATED");
                    onSubscriptionDeactivated();
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    };

    protected boolean checkImsiReady() {
        for (int i = 0; i < mNumPhones; i++) {
            mStoredImsi[i] = getSimImsi(i);
            mImsiFromSim[i] = MSimTelephonyManager.getDefault().getSubscriberId(i);
            // if imsi is not yet read, then above api returns ""
            if ((mImsiFromSim[i] == null)  || (mImsiFromSim[i] == "")) {
                return false;
            } else if ((mStoredImsi[i] == null) || ((mImsiFromSim[i] != null)
                    && (!mImsiFromSim[i].equals(mStoredImsi[i])))) {
                // Imsi from SIM does not match the stored Imsi.
                // Hence reset the values.
                mCallNotifier.setXDivertStatus(false);
                setSimImsi(mImsiFromSim[i], i);
                storeNumber(null, i);
                mHasImsiChanged[i] = true;
            }
        }
        return true;
    }

    // returns the stored Line Numbers
    protected String[] getLineNumbers() {
        return mLineNumber;
    }

    // returns the stored Imsi from shared preference
    protected String getSimImsi(int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        return sp.getString(SIM_IMSI + subscription, null);
    }

    // saves the Imsi to shared preference
    protected void setSimImsi(String imsi, int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SIM_IMSI + subscription, imsi);
        editor.apply();
    }

    // On Subscription deactivation, clear the Xdivert icon from
    // notification bar
    private void onSubscriptionDeactivated() {
        mCallNotifier.onXDivertChanged(false);
    }

    // returns the stored Line Numbers from shared preference
    protected String getNumber(int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        return sp.getString(SIM_NUMBER + subscription, null);
    }

    // saves the Line Numbers to shared preference
    protected void storeNumber(String number, int subscription) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                mContext);
        SharedPreferences.Editor editor = sp.edit();
        editor.putString(SIM_NUMBER + subscription, number);
        editor.apply();

        // Update the lineNumber which will be passed to XDivertPhoneNumbers
        // to populate the number from next time.
        mLineNumber[subscription] = number;
    }
}
