/*
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (c) 2011-2012 Code Aurora Forum. All rights reserved.
 *
 * Not a Contribution, Apache license notifications and license are retained
 * for attribution purposes only
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

import android.content.Context;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.Editable;
import android.text.Spannable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.telephony.TelephonyManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.IccCardApplicationStatus;
import com.android.internal.telephony.IccCardApplicationStatus.PersoSubState;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class IccDepersonalizationPanel extends IccPanel {

    //debug constants
    private static final boolean DBG = true;

    //events
    private static final int EVENT_ICC_DEPERSONALIZATION_RESULT = 100;

    private Phone mPhone;
    private int mPersoSubtype;
    private int mSubscription;

    //UI elements
    private EditText     mPinEntry;
    private LinearLayout mEntryPanel;
    private LinearLayout mStatusPanel;
    private TextView     mStatusText;
    private TextView     mPersoSubtypeText;

    private Button       mUnlockButton;
    private Button       mDismissButton;

    private final int ENTRY = 0;
    private final int IN_PROGRESS = 1;
    private final int ERROR = 2;
    private final int SUCCESS = 3;

    //private textwatcher to control text entry.
    private TextWatcher mPinEntryWatcher = new TextWatcher() {
        public void beforeTextChanged(CharSequence buffer, int start, int olen, int nlen) {
        }

        public void onTextChanged(CharSequence buffer, int start, int olen, int nlen) {
        }

        public void afterTextChanged(Editable buffer) {
            if (SpecialCharSequenceMgr.handleChars(
                    getContext(), buffer.toString())) {
                mPinEntry.getText().clear();
            }
        }
    };

    //handler for unlock function results
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            if (msg.what == EVENT_ICC_DEPERSONALIZATION_RESULT) {
                AsyncResult res = (AsyncResult) msg.obj;
                if (res.exception != null) {
                    if (DBG) log("De-Personalization request failure.");
                    displayStatus(ERROR);
                    postDelayed(new Runnable() {
                                    public void run() {
                                        hideAlert();
                                        mPinEntry.getText().clear();
                                        mPinEntry.requestFocus();
                                    }
                                }, 3000);
                } else {
                    if (DBG) log("De-Personalization success.");
                    displayStatus(SUCCESS);
                    postDelayed(new Runnable() {
                                    public void run() {
                                        dismiss();
                                    }
                                }, 3000);
                }
            }
        }
    };

    //constructor
    public IccDepersonalizationPanel(Context context) {
        super(context);
        mPersoSubtype = IccCardApplicationStatus.PersoSubState.PERSOSUBSTATE_SIM_NETWORK.ordinal();
    }

    //constructor
    public IccDepersonalizationPanel(Context context, int subtype) {
        super(context);
        mPersoSubtype = subtype;
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        setContentView(R.layout.sim_ndp);

        // PIN entry text field
        mPinEntry = (EditText) findViewById(R.id.pin_entry);
        mPinEntry.setKeyListener(DialerKeyListener.getInstance());
        mPinEntry.setOnClickListener(mUnlockListener);

        // Attach the textwatcher
        CharSequence text = mPinEntry.getText();
        Spannable span = (Spannable) text;
        span.setSpan(mPinEntryWatcher, 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);

        mEntryPanel = (LinearLayout) findViewById(R.id.entry_panel);
        mPersoSubtypeText = (TextView) findViewById(R.id.perso_subtype_text);
        displayStatus(ENTRY);

        mUnlockButton = (Button) findViewById(R.id.ndp_unlock);
        mUnlockButton.setOnClickListener(mUnlockListener);

        // The "Dismiss" button is present in some (but not all) products,
        // based on the "icc_perso_unlock_allow_dismiss" resource.
        mDismissButton = (Button) findViewById(R.id.ndp_dismiss);
        if (getContext().getResources().getBoolean(R.bool.icc_perso_unlock_allow_dismiss)) {
            if (DBG) log("Enabling 'Dismiss' button...");
            mDismissButton.setVisibility(View.VISIBLE);
            mDismissButton.setOnClickListener(mDismissListener);
        } else {
            if (DBG) log("Removing 'Dismiss' button...");
            mDismissButton.setVisibility(View.GONE);
        }

        //status panel is used since we're having problems with the alert dialog.
        mStatusPanel = (LinearLayout) findViewById(R.id.status_panel);
        mStatusText = (TextView) findViewById(R.id.status_text);

        mPhone = PhoneApp.getPhone();
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    //Mirrors IccPinUnlockPanel.onKeyDown().
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    View.OnClickListener mUnlockListener = new View.OnClickListener() {
        public void onClick(View v) {
            String pin = mPinEntry.getText().toString();

            if (TextUtils.isEmpty(pin)) {
                return;
            }

            log("Requesting De-Personalization for subtype " + mPersoSubtype);
            mPhone.getIccCard().supplyDepersonalization(pin, mPersoSubtype,
                Message.obtain(mHandler, EVENT_ICC_DEPERSONALIZATION_RESULT));
            displayStatus(IN_PROGRESS);
        }
    };

    private void displayStatus(int type) {
        int label = 0;
        IccCardApplicationStatus.PersoSubState persosubtype =
                IccCardApplicationStatus.PersoSubState.values()[mPersoSubtype];
        switch (persosubtype) {
            case PERSOSUBSTATE_SIM_NETWORK:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_ndp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_unlock;
                        break;
                    case ERROR:
                        label = R.string.unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_SIM_NETWORK_SUBSET:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_nsdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_nw_subset_unlock;
                        break;
                    case ERROR:
                        label = R.string.nw_subset_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.nw_subset_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_SIM_CORPORATE:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_cdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_corporate_unlock;
                        break;
                    case ERROR:
                        label = R.string.corporate_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.corporate_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_SIM_SERVICE_PROVIDER:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_spdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_sp_unlock;
                        break;
                    case ERROR:
                        label = R.string.sp_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.sp_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_SIM_SIM:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_sdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_sim_unlock;
                        break;
                    case ERROR:
                        label = R.string.sim_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.sim_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_RUIM_NETWORK1:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rn1dp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_rnw1_unlock;
                        break;
                    case ERROR:
                        label = R.string.rnw1_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.rnw1_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_RUIM_NETWORK2:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rn2dp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_rnw2_unlock;
                        break;
                    case ERROR:
                        label = R.string.rnw2_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.rnw2_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_RUIM_HRPD:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rhrpd;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_rhrpd_unlock;
                        break;
                    case ERROR:
                        label = R.string.rhrpd_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.rhrpd_unlock_success;
                        break;
                 }
                 break;
            case PERSOSUBSTATE_RUIM_CORPORATE:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rcdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_rc_unlock;
                        break;
                    case ERROR:
                        label = R.string.rc_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.rc_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_RUIM_SERVICE_PROVIDER:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rspdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_rsp_unlock;
                        break;
                    case ERROR:
                        label = R.string.rsp_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.rsp_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_RUIM_RUIM:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_rdp;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_ruim_unlock;
                        break;
                    case ERROR:
                        label = R.string.ruim_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.ruim_unlock_success;
                        break;
                }
                break;
            case PERSOSUBSTATE_SIM_NETWORK_PUK:
            case PERSOSUBSTATE_SIM_NETWORK_SUBSET_PUK:
            case PERSOSUBSTATE_SIM_CORPORATE_PUK:
            case PERSOSUBSTATE_SIM_SERVICE_PROVIDER_PUK:
            case PERSOSUBSTATE_SIM_SIM_PUK:
            case PERSOSUBSTATE_RUIM_NETWORK1_PUK:
            case PERSOSUBSTATE_RUIM_NETWORK2_PUK:
            case PERSOSUBSTATE_RUIM_HRPD_PUK:
            case PERSOSUBSTATE_RUIM_CORPORATE_PUK:
            case PERSOSUBSTATE_RUIM_SERVICE_PROVIDER_PUK:
            case PERSOSUBSTATE_RUIM_RUIM_PUK:
                switch (type) {
                    case ENTRY:
                        label = R.string.label_puk;
                        break;
                    case IN_PROGRESS:
                        label = R.string.requesting_puk_unlock;
                        break;
                    case ERROR:
                        label = R.string.puk_unlock_failed;
                        break;
                    case SUCCESS:
                        label = R.string.puk_unlock_success;
                        break;
                }
                break;
            default:
                log ("Unsupported Perso Subtype :" + persosubtype);
                return;
        }
        if (type == 0) {
            String displayText = getContext().getString(label);
            mPersoSubtypeText.setText(displayText);
        } else {
            mStatusText.setText(label);
            mEntryPanel.setVisibility(View.GONE);
            mStatusPanel.setVisibility(View.VISIBLE);
        }
    }

    private void hideAlert() {
        mEntryPanel.setVisibility(View.VISIBLE);
        mStatusPanel.setVisibility(View.GONE);
    }

    View.OnClickListener mDismissListener = new View.OnClickListener() {
        public void onClick(View v) {
            if (DBG) log("mDismissListener: skipping depersonalization...");
            dismiss();
        }
    };

    private void log(String msg) {
        Log.d(TAG, "[IccDepersonalizationPanel] " + msg);
    }
}
