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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemProperties;
import android.os.Handler;
import android.telephony.PhoneNumberUtils;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.view.KeyEvent;
import android.util.Log;
import android.widget.TextView;
import android.widget.Button;


import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.msim.MSimPhoneFactory;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.msim.SubscriptionManager;

import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.ServiceState;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;

public class MSimDialerActivity extends Activity {
    private static final String TAG = "MSimDialerActivity";
    private static final boolean DBG = true;

    private Context mContext;
    private String mCallNumber;
    private String mNumber;
    private AlertDialog mAlertDialog = null;
    private TextView mTextNumber;
    private Intent mIntent;
    private int mPhoneCount = 0;

    public static final String PHONE_SUBSCRIPTION = "Subscription";
    public static final int INVALID_SUB = 99;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mContext = getApplicationContext();
        mCallNumber = getResources().getString(R.string.call_number);
    }

    @Override
    protected void onResume() {
        super.onResume();

        mPhoneCount = MSimTelephonyManager.getDefault().getPhoneCount();
        mIntent = getIntent();
        if (DBG) Log.v(TAG, "Intent = " + mIntent);

        mNumber = PhoneNumberUtils.getNumberFromIntent(mIntent, this);
        if (DBG) Log.v(TAG, "mNumber " + mNumber);
        if (mNumber != null) {
            mNumber = PhoneNumberUtils.convertKeypadLettersToDigits(mNumber);
            mNumber = PhoneNumberUtils.stripSeparators(mNumber);
        }

        Phone phone = null;
        boolean phoneInCall = false;
        //checking if any of the phones are in use
        for (int i = 0; i < mPhoneCount; i++) {
             phone = MSimPhoneFactory.getPhone(i);
             boolean inCall = isInCall(phone);
             if ((phone != null) && (inCall)) {
                 phoneInCall = true;
                 break;
             }
        }
        if (phoneInCall) {
            if (DBG) Log.v(TAG, "subs [" + phone.getSubscription() + "] is in call");
            // use the sub which is already in call
            startOutgoingCall(phone.getSubscription());
        } else {
            if (DBG) Log.v(TAG, "launch dsdsdialer");
            // if none in use, launch the MultiSimDialer
            launchMSDialer();
        }
        Log.d(TAG, "end of onResume()");
    }

    protected void onPause() {
        super.onPause();
        if(DBG) Log.v(TAG, "onPause : " + mIntent);
        if (mAlertDialog != null) {
            mAlertDialog.dismiss();
            mAlertDialog = null;
        }
    }

   private int getSubscriptionForEmergencyCall(){
       Log.d(TAG,"emergency call, getVoiceSubscriptionInService");
       int sub = PhoneApp.getInstance().getVoiceSubscriptionInService();
       return sub;
    }

    private void launchMSDialer() {
        boolean isEmergency = PhoneNumberUtils.isEmergencyNumber(mNumber);
        if (isEmergency) {
            Log.d(TAG,"emergency call");
            startOutgoingCall(getSubscriptionForEmergencyCall());
            return;
        }

        LayoutInflater inflater = (LayoutInflater) mContext.
                getSystemService(LAYOUT_INFLATER_SERVICE);
        View layout = inflater.inflate(R.layout.dialer_ms,
                (ViewGroup) findViewById(R.id.layout_root));

        AlertDialog.Builder builder = new AlertDialog.Builder(MSimDialerActivity.this);
        builder.setView(layout);
        builder.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                Log.d(TAG, "key code is :" + keyCode);
                switch (keyCode) {
                case KeyEvent.KEYCODE_BACK: {
                    mAlertDialog.dismiss();
                    startOutgoingCall(INVALID_SUB);
                    return true;
                    }
                case KeyEvent.KEYCODE_CALL: {
                    Log.d(TAG, "event is" + event.getAction());
                    if (event.getAction() == KeyEvent.ACTION_UP) {
                        return true;
                    } else {
                        mAlertDialog.dismiss();
                        startOutgoingCall(MSimPhoneFactory.getVoiceSubscription());
                        return true;
                    }
                    }
                case KeyEvent.KEYCODE_SEARCH:
                    return true;
                default:
                    return false;
                }
            }
        });

        mAlertDialog = builder.create();

        mTextNumber = (TextView)layout.findViewById(R.id.CallNumber);

        String vm = "";
        if (mIntent.getData() != null)
            vm =  mIntent.getData().getScheme();

        if ((vm != null) && (vm.equals("voicemail"))) {
            mTextNumber.setText(mCallNumber + "VoiceMail" );
            Log.d(TAG, "its voicemail!!!");
        } else {
            mTextNumber.setText(mCallNumber + mNumber);
        }

        Button callCancel = (Button)layout.findViewById(R.id.callcancel);
        callCancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                mAlertDialog.dismiss();
                startOutgoingCall(INVALID_SUB);
            }
        });

        Button[] callButton = new Button[mPhoneCount];
        int[] callMark = {R.id.callmark1, R.id.callmark2};
        int[] subString = {R.string.sub_1, R.string.sub_2};
        int index = 0;
        for (index = 0; index < mPhoneCount; index++) {
            callButton[index] =  (Button) layout.findViewById(callMark[index]);
            callButton[index].setText(subString[index]);
            callButton[index].setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    mAlertDialog.dismiss();
                    switch (v.getId()) {
                    case R.id.callmark1:
                        startOutgoingCall(MSimConstants.SUB1);
                        break;
                    case R.id.callmark2:
                        startOutgoingCall(MSimConstants.SUB2);
                        break;
                    }
                }
            });
        }


        if (MSimConstants.SUB1 == MSimPhoneFactory.getVoiceSubscription()) {
            callButton[MSimConstants.SUB1].setBackgroundResource(R.drawable.highlight_btn_call);
        } else {
            callButton[MSimConstants.SUB2].setBackgroundResource(R.drawable.highlight_btn_call);
        }

        mAlertDialog.show();
    }

    boolean isInCall(Phone phone) {
        if (phone != null) {
            if ((phone.getForegroundCall().getState().isAlive()) ||
                   (phone.getBackgroundCall().getState().isAlive()) ||
                   (phone.getRingingCall().getState().isAlive()))
                return true;
        }
        return false;
    }

    private void startOutgoingCall(int subscription) {
         mIntent.putExtra(SUBSCRIPTION_KEY, subscription);
         mIntent.setClass(MSimDialerActivity.this, OutgoingCallBroadcaster.class);
         if (DBG) Log.v(TAG, "startOutgoingCall for sub " +subscription
                 + " from intent: "+ mIntent);
         if (subscription < mPhoneCount) {
             setResult(RESULT_OK, mIntent);
         } else {
             setResult(RESULT_CANCELED, mIntent);
             Log.d(TAG, "call cancelled");
         }
         finish();
    }
}
