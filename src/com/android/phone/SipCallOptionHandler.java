/**
 * Copyright (C) 2010 The Android Open Source Project
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
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.sip.SipManager;
import android.net.sip.SipProfile;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.phone.sip.SipSettings;
import com.android.phone.sip.SipSharedPreferences;

import java.util.List;
import javax.sip.SipException;

/**
 * SipCallOptionHandler select the sip phone based on the call option.
*/
public class SipCallOptionHandler extends Activity implements
        DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

    static final String TAG = "SipCallOptionHandler";
    static final int DIALOG_SELECT_PHONE_TYPE = 0;
    static final int DIALOG_SELECT_OUTGOING_SIP_PHONE = 1;
    static final int DIALOG_START_SIP_SETTINGS = 2;
    static final int DIALOG_SIZE = 3;

    private Intent mIntent;
    private List<SipProfile> mProfileList;
    private String mCallOption;
    private String mNumber;
    private SipSharedPreferences mSipSharedPreferences;
    private Dialog[] mDialogs = new Dialog[DIALOG_SIZE];
    private SipProfile mPrimarySipProfile;
    private boolean mUseSipPhone = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mIntent = (Intent)getIntent().getParcelableExtra
            (OutgoingCallBroadcaster.EXTRA_NEW_CALL_INTENT);
        if (mIntent == null) {
            finish();
            return;
        }

        // If we're trying to make a SIP call, return a SipPhone if one is
        // available.
        //
        // - If it's a sip: URI, this is definitely a SIP call, regardless
        //   of whether the data is a SIP address or a regular phone
        //   number.
        //
        // - If this is a tel: URI but the data contains an "@" character
        //   (see PhoneNumberUtils.isUriNumber()) we consider that to be a
        //   SIP number too.
        //
        // TODO: Eventually we may want to disallow that latter case
        //       (e.g. "tel:foo@example.com").
        //
        // TODO: We should also consider moving this logic into the
        //       CallManager, where it could be made more generic.
        //       (For example, each "telephony provider" could be allowed
        //       to register the URI scheme(s) that it can handle, and the
        //       CallManager would then find the best match for every
        //       outgoing call.)

        mSipSharedPreferences = new SipSharedPreferences(this);
        mCallOption = mSipSharedPreferences.getSipCallOption();
        Log.v(TAG, "Call option is " + mCallOption);
        Uri uri = mIntent.getData();
        String scheme = uri.getScheme();
        mNumber = PhoneNumberUtils.getNumberFromIntent(mIntent, this);
        if ("sip".equals(scheme) || PhoneNumberUtils.isUriNumber(mNumber)) {
            mUseSipPhone = true;
        }

        if (!mUseSipPhone &&
                mCallOption.equals(Settings.System.SIP_ASK_ME_EACH_TIME)) {
            showDialog(DIALOG_SELECT_PHONE_TYPE);
            return;
        }
        if (mCallOption.equals(Settings.System.SIP_ALWAYS)) {
            mUseSipPhone = true;
        }
        if (mUseSipPhone) {
            startGetPrimarySipPhoneThread();
        } else {
            setResultAndFinish();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        for (Dialog dialog : mDialogs) {
            if (dialog != null) dialog.dismiss();
        }
        finish();
    }

    protected Dialog onCreateDialog(int id) {
        Dialog dialog;
        switch(id) {
        case DIALOG_SELECT_PHONE_TYPE:
            dialog = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.pick_outgoing_call_phone_type,
                            mNumber))
                    .setSingleChoiceItems(R.array.phone_type_values, -1, this)
                    .setOnCancelListener(this)
                    .create();
            break;
        case DIALOG_SELECT_OUTGOING_SIP_PHONE:
            dialog = new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.pick_outgoing_sip_phone,
                            mNumber))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setSingleChoiceItems(getProfileNameArray(), -1, this)
                    .setOnCancelListener(this)
                    .create();
            break;
        case DIALOG_START_SIP_SETTINGS:
            dialog = new AlertDialog.Builder(this)
                    .setTitle(R.string.no_sip_account_found_title)
                    .setMessage(getString(R.string.no_sip_account_found, mNumber))
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setPositiveButton(android.R.string.yes, this)
                    .setNegativeButton(android.R.string.cancel, this)
                    .setOnCancelListener(this)
                    .create();
            break;
        default:
            dialog = null;
        }
        if (dialog != null) {
            mDialogs[id] = dialog;
        }
        return dialog;
    }

    private CharSequence[] getProfileNameArray() {
        CharSequence[] entries = new CharSequence[mProfileList.size()];
        int i = 0;
        for (SipProfile p : mProfileList) {
            entries[i++] = p.getProfileName();
        }
        return entries;
    }

    public void onClick(DialogInterface dialog, int id) {
        if (dialog == mDialogs[DIALOG_SELECT_PHONE_TYPE]) {
            String selection = getResources().getStringArray(
                    R.array.phone_type_values)[id];
            Log.v(TAG, "User pick phone " + selection);
            if (selection.equals(getString(R.string.internet_phone))) {
                mUseSipPhone = true;
                startGetPrimarySipPhoneThread();
                return;
            }
        } else if (dialog == mDialogs[DIALOG_SELECT_OUTGOING_SIP_PHONE]) {
            mPrimarySipProfile = mProfileList.get(id);
        } else {
            if (id == DialogInterface.BUTTON_POSITIVE) {
                // Redirect to sip settings and drop the call.
                Intent newIntent = new Intent(this, SipSettings.class);
                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(newIntent);
            }
            finish();
            return;
        }
        setResultAndFinish();
    }

    public void onCancel(DialogInterface dialog) {
        finish();
    }

    private void createSipPhoneIfNeeded(SipProfile p) {
        CallManager cm = PhoneApp.getInstance().mCM;
        if (PhoneUtils.getSipPhoneFromUri(cm, p.getUriString()) != null) return;

        // Create the phone since we can not find it in CallManager
        try {
            SipManager.getInstance(this).open(p);
            Phone phone = PhoneFactory.makeSipPhone(p.getUriString());
            if (phone != null) {
                cm.registerPhone(phone);
            } else {
                Log.e(TAG, "cannot make sipphone profile" + p);
            }
        } catch (SipException e) {
            Log.e(TAG, "cannot open sip profile" + p, e);
        }
    }

    private void setResultAndFinish() {
        runOnUiThread(new Runnable() {
            public void run() {
                if (mPrimarySipProfile != null) {
                    Log.v(TAG, "primary SIP URI is " +
                            mPrimarySipProfile.getUriString());
                    createSipPhoneIfNeeded(mPrimarySipProfile);
                    mIntent.putExtra(OutgoingCallBroadcaster.EXTRA_SIP_PHONE_URI,
                            mPrimarySipProfile.getUriString());
                }
                if (mUseSipPhone && mPrimarySipProfile == null) {
                    showDialog(DIALOG_START_SIP_SETTINGS);
                    return;
                } else {
                    startActivity(mIntent);
                }
                finish();
            }
        });
    }

    private void startGetPrimarySipPhoneThread() {
        new Thread(new Runnable() {
            public void run() {
                getPrimarySipPhone();
            }
        }).start();
    }

    private void getPrimarySipPhone() {
        String primarySipUri = mSipSharedPreferences.getPrimaryAccount();

        mPrimarySipProfile = getPrimaryFromExistingProfiles(primarySipUri);
        if (mPrimarySipProfile == null) {
            if ((mProfileList != null) && (mProfileList.size() > 0)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        showDialog(DIALOG_SELECT_OUTGOING_SIP_PHONE);
                    }
                });
                return;
            }
        }
        setResultAndFinish();
    }

    private SipProfile getPrimaryFromExistingProfiles(String primarySipUri) {
        mProfileList = SipSettings.retrieveSipListFromDirectory(
                getFilesDir().getAbsolutePath() +
                SipSettings.PROFILES_DIR);
        if (mProfileList == null) return null;
        for (SipProfile p : mProfileList) {
            if (p.getUriString().equals(primarySipUri)) return p;
        }
        return null;
    }
}
