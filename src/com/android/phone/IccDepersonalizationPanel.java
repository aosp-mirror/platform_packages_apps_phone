/*
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

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class IccDepersonalizationPanel extends IccPanel {

    //debug constants
    private static final boolean DBG = true;

    //events
    private static final int EVENT_ICC_DEPERSONALIZATION_RESULT = 1;

    private Phone mPhone;
    private int mPersoSubtype;
    private int mInternalSubtype;

    //UI elements
    private EditText     mPinEntry;
    private LinearLayout mEntryPanel;
    private LinearLayout mStatusPanel;
    private TextView     mStatusText;
    private TextView     mPersoSubtypeText;

    private Button       mUnlockButton;
    private Button       mDismissButton;

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
                    if (DBG) log("De-Personalization request failed.");
                    indicateError();
                    postDelayed(new Runnable() {
                                    public void run() {
                                        hideAlert();
                                        mPinEntry.getText().clear();
                                        mPinEntry.requestFocus();
                                    }
                                }, 3000);
                } else {
                    if (DBG) log("De-Personalization success.");
                    indicateSuccess();
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
        mPersoSubtype = IccDepersonalizationConstants.RIL_PERSOSUBSTATE_SIM_NETWORK;
        mInternalSubtype = IccDepersonalizationConstants.SIM_NETWORK;
    }

    //constructor
    public IccDepersonalizationPanel(Context context, int subtype) {
        super(context);
        mPersoSubtype = subtype;
        // This internal mapping of the substate helps in better coding by
        // avoiding switch statements for displaying title/busy/success/error messages.
        mInternalSubtype = IccDepersonalizationConstants.toInternalSubtype(subtype);
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
        setPersoPanelTitle();

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

        mPhone = PhoneFactory.getDefaultPhone();
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

            log("Requesting De-Personalization with subtype " + mPersoSubtype);
            mPhone.getIccCard().supplyDepersonalization(pin, mPersoSubtype,
                    Message.obtain(mHandler, EVENT_ICC_DEPERSONALIZATION_RESULT));
            indicateBusy();
        }
    };

    private void indicateBusy() {
        int[] busyLabels = { R.string.requesting_nw_unlock,
                             R.string.requesting_nw_subset_unlock,
                             R.string.requesting_corporate_unlock,
                             R.string.requesting_sp_unlock,
                             R.string.requesting_sim_unlock,
                             R.string.requesting_rnw1_unlock,
                             R.string.requesting_rnw2_unlock,
                             R.string.requesting_rhrpd_unlock,
                             R.string.requesting_rc_unlock,
                             R.string.requesting_rsp_unlock,
                             R.string.requesting_ruim_unlock };

        mStatusText.setText(busyLabels[mInternalSubtype]);
        mEntryPanel.setVisibility(View.GONE);
        mStatusPanel.setVisibility(View.VISIBLE);
    }

    private void indicateError() {
        int[] errorLabels = { R.string.nw_unlock_failed,
                              R.string.nw_subset_unlock_failed,
                              R.string.corporate_unlock_failed,
                              R.string.sp_unlock_failed,
                              R.string.sim_unlock_failed,
                              R.string.rnw1_unlock_failed,
                              R.string.rnw2_unlock_failed,
                              R.string.rhrpd_unlock_failed,
                              R.string.rc_unlock_failed,
                              R.string.rsp_unlock_failed,
                              R.string.ruim_unlock_failed };

        mStatusText.setText(errorLabels[mInternalSubtype]);
        mEntryPanel.setVisibility(View.GONE);
        mStatusPanel.setVisibility(View.VISIBLE);
    }

    private void indicateSuccess() {
        int[] successLabels = { R.string.nw_unlock_success,
                                R.string.nw_subset_unlock_success,
                                R.string.corporate_unlock_success,
                                R.string.sp_unlock_success,
                                R.string.sim_unlock_success,
                                R.string.rnw1_unlock_success,
                                R.string.rnw2_unlock_success,
                                R.string.rhrpd_unlock_success,
                                R.string.rc_unlock_success,
                                R.string.rsp_unlock_success,
                                R.string.ruim_unlock_success };

        mStatusText.setText(successLabels[mInternalSubtype]);
        mEntryPanel.setVisibility(View.GONE);
        mStatusPanel.setVisibility(View.VISIBLE);
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

    //Sets title of Depersonalization Panel.
    private void setPersoPanelTitle() {
        int[] panelTitles = { R.string.label_ndp,
                              R.string.label_nsdp,
                              R.string.label_cdp,
                              R.string.label_spdp,
                              R.string.label_sdp,
                              R.string.label_rn1dp,
                              R.string.label_rn2dp,
                              R.string.label_rhrpd,
                              R.string.label_rcdp,
                              R.string.label_rspdp,
                              R.string.label_rdp };

        mPersoSubtypeText.setText(panelTitles[mInternalSubtype]);
    }

    private void log(String msg) {
        Log.v(TAG, "[IccDepersonalizationPanel] " + msg);
    }
}
