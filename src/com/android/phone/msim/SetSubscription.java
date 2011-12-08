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

import java.lang.Integer;

import android.os.Bundle;
import android.os.Message;
import android.os.Handler;
import android.os.AsyncResult;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.preference.PreferenceCategory;
import android.view.Display;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.LinearLayout.LayoutParams;
import android.content.DialogInterface;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.app.Dialog;

import android.content.Intent;

import com.android.internal.telephony.msim.CardSubscriptionManager;
import com.android.internal.telephony.msim.SubscriptionData;
import com.android.internal.telephony.msim.Subscription;
import com.android.internal.telephony.msim.SubscriptionManager;

import static com.android.internal.telephony.MSimConstants.NUM_SUBSCRIPTIONS;

/**
 * Displays a dialer like interface to Set the Subscriptions.
 */
public class SetSubscription extends PreferenceActivity implements View.OnClickListener,
       DialogInterface.OnDismissListener, DialogInterface.OnClickListener {

    private static final String TAG = "SetSubscription";
    public static final int SUBSCRIPTION_INDEX_INVALID = 99999;

    private TextView mOkButton, mCancelButton;
    SubscriptionCheckBoxPreference subArray[];
    private boolean subErr = false;
    private SubscriptionData[] mCardSubscrInfo;
    private SubscriptionData mCurrentSelSub;
    private SubscriptionData mUserSelSub;
    private SubscriptionManager mSubscriptionManager;
    private CardSubscriptionManager mCardSubscriptionManager;

    //String keys for preference lookup
    private static final String PREF_PARENT_KEY = "subscr_parent";

    private final int MAX_SUBSCRIPTIONS = 2;

    private final int EVENT_SET_SUBSCRIPTION_DONE = 1;

    private final int EVENT_SIM_STATE_CHANGED = 2;

    private final int DIALOG_SET_SUBSCRIPTION_IN_PROGRESS = 100;

    public void onCreate(Bundle icicle) {
        boolean newCardNotify = getIntent().getBooleanExtra("NOTIFY_NEW_CARD_AVAILABLE", false);
        if (!newCardNotify) {
            setTheme(android.R.style.Theme);
        }
        super.onCreate(icicle);

        mSubscriptionManager = SubscriptionManager.getInstance();
        mCardSubscriptionManager = CardSubscriptionManager.getInstance();

        if (newCardNotify) {
            Log.d(TAG, "onCreate: Notify new cards are available!!!!");
            notifyNewCardAvailable();
        } else {
            // get the card subscription info from the Proxy Manager.
            mCardSubscrInfo = new SubscriptionData[MAX_SUBSCRIPTIONS];
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                mCardSubscrInfo[i] = mCardSubscriptionManager.getCardSubscriptions(i);
            }

            addPreferencesFromResource(R.xml.set_subscription_pref);
            setContentView(R.layout.set_subscription_pref_layout);

            mOkButton = (TextView) findViewById(R.id.ok);
            mOkButton.setOnClickListener(this);
            mCancelButton = (TextView) findViewById(R.id.cancel);
            mCancelButton.setOnClickListener(this);

            TextView t1 = (TextView) findViewById(R.id.sub_1);
            TextView t2 = (TextView) findViewById(R.id.sub_2);
            TextView t3 = (TextView) findViewById(R.id.app_name);

            // align the labels
            Display display = getWindowManager().getDefaultDisplay();
            int width = display.getWidth();
            t1.setLayoutParams(new LayoutParams(75, LayoutParams.WRAP_CONTENT));
            t2.setLayoutParams(new LayoutParams(75, LayoutParams.WRAP_CONTENT));
            t3.setLayoutParams(new LayoutParams(width - 150, LayoutParams.WRAP_CONTENT));

            // To store the selected subscriptions
            // index 0 for sub0 and index 1 for sub1
            subArray = new SubscriptionCheckBoxPreference[MAX_SUBSCRIPTIONS];

            if(mCardSubscrInfo != null) {
                populateList();

                mUserSelSub = new SubscriptionData(MAX_SUBSCRIPTIONS);

                updateCheckBoxes();
            } else {
                Log.d(TAG, "onCreate: Card info not available: mCardSubscrInfo == NULL");
            }

            // TODO: registerForSimStateChanged
            if (mSubscriptionManager.isSetSubscriptionInProgress()) {
                Log.d(TAG, "onCreate: SetSubscription is in progress when started this activity");
                showDialog(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);
                mSubscriptionManager.registerForSetSubscriptionCompleted(
                        mHandler, EVENT_SET_SUBSCRIPTION_DONE, null);
            }
        }
    }

    protected void onDestroy () {
        super.onDestroy();
        mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
    }

    private void notifyNewCardAvailable() {
        Log.d(TAG, "notifyNewCardAvailable()");

        new AlertDialog.Builder(this).setMessage(R.string.new_cards_available)
            .setTitle(R.string.config_sub_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(TAG, "new card dialog box:  onClick");
                        //finish();
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(TAG, "new card dialog box:  onDismiss");
                        finish();
                    }
                });
    }

    private void updateCheckBoxes() {
        PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen()
                                             .findPreference(PREF_PARENT_KEY);
        for (int i = 0; i < mCardSubscrInfo.length; i++) {
            PreferenceCategory subGroup = (PreferenceCategory) prefParent
                   .findPreference("sub_group_" + i);
            if (subGroup != null) {
                int count = subGroup.getPreferenceCount();
                Log.d(TAG, "updateCheckBoxes count = " + count);
                for (int j = 0; j < count; j++) {
                    SubscriptionCheckBoxPreference checkBoxPref =
                              (SubscriptionCheckBoxPreference) subGroup.getPreference(j);
                    checkBoxPref.markAllUnChecked();
                }
            }
        }

        // TODO: Use MSImConstants
        mCurrentSelSub = new SubscriptionData(2);
        for (int i = 0; i < NUM_SUBSCRIPTIONS; i++) {
            Subscription sub = mSubscriptionManager.getCurrentSubscription(i);
                    mCurrentSelSub.subscription[i].copyFrom(sub);
        }

        //mCurrentSelSub = mSubscriptionManager.getCurrentSubscriptions();
        if (mCurrentSelSub != null) {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                Log.d(TAG, "updateCheckBoxes: mCurrentSelSub.subscription[" + i + "] = "
                           + mCurrentSelSub.subscription[i]);
                subArray[i] = null;
                if (mCurrentSelSub.subscription[i].subStatus ==
                        Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                    String key = "slot" + mCurrentSelSub.subscription[i].slotId
                                 + " index" + mCurrentSelSub.subscription[i].getAppIndex();

                    Log.d(TAG, "updateCheckBoxes: key = " + key);

                    PreferenceCategory subGroup = (PreferenceCategory) prefParent
                           .findPreference("sub_group_" + mCurrentSelSub.subscription[i].slotId);
                    if (subGroup != null) {
                        SubscriptionCheckBoxPreference checkBoxPref =
                               (SubscriptionCheckBoxPreference) subGroup.findPreference(key);
                        checkBoxPref.markChecked(mapSub(i));
                        subArray[i] = checkBoxPref;
                    }
                }
            }
            mUserSelSub.copyFrom(mCurrentSelSub);
        }
    }

    private SubscriptionID mapSub(int sub) {
        SubscriptionID ret = SubscriptionID.NONE;
        if (sub == 0) ret = SubscriptionID.SUB_0;
        if (sub == 1) ret = SubscriptionID.SUB_1;
        return ret;
    }

    /** add radio buttons to the group */
    private void populateList() {
        PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen().
                findPreference(PREF_PARENT_KEY);
        Display display = getWindowManager().getDefaultDisplay();
        int width = display.getWidth();
        int[] subGroupTitle = {R.string.card_01, R.string.card_02};

        Log.d(TAG, "populateList:  mCardSubscrInfo.length = " + mCardSubscrInfo.length);

        int k = 0;
        // Create PreferenceCatergory sub groups for each card.
        for (SubscriptionData cardSub : mCardSubscrInfo) {
            if ((cardSub != null ) && (cardSub.getLength() > 0)) {
                int i = 0;

                // Create a subgroup for the apps in card 01
                PreferenceCategory subGroup = new PreferenceCategory(this);
                subGroup.setKey("sub_group_" + k);
                subGroup.setTitle(subGroupTitle[k]);
                prefParent.addPreference(subGroup);

                // Add each element as a CheckBoxPreference to the group
                for (Subscription sub : cardSub.subscription) {
                    if (sub != null && sub.appType != null) {
                        Log.d(TAG, "populateList:  mCardSubscrInfo[" + k + "].subscription["
                                + i + "] = " + sub);
                        SubscriptionCheckBoxPreference newCheckBox =
                                                 new SubscriptionCheckBoxPreference(this, width);
                        newCheckBox.setTitleText(sub.appType);
                        // Key is the string : "slot<SlotId> index<IndexId>"
                        newCheckBox.setKey(new String("slot" + k + " index" + i));
                        newCheckBox.setOnSubPreferenceClickListener(mCheckBoxListener);
                        subGroup.addPreference(newCheckBox);
                    }
                    i++;
                }
            }
            k++;
        }
    }

    Preference.OnPreferenceClickListener mCheckBoxListener =
            new Preference.OnPreferenceClickListener() {
        public boolean onPreferenceClick(Preference preference) {
            SubscriptionCheckBoxPreference subPref = (SubscriptionCheckBoxPreference)preference;
            SubscriptionID checked = subPref.getSelectedSubscription();

            Log.d(TAG, "onPreferenceClick: KEY = " + subPref.getKey() + " checked = " + checked);

            if (checked == SubscriptionID.SUB_0) {
                // If user already selected a sub0 uncheck it
                if (subArray[0] != null) {
                        Log.d(TAG, "onPreferenceClick: clearing previously selected SUB0");
                        subArray[0].markAllUnChecked();
                }
                // Store the subArray[0] if there is sub0 selected.
                //if (subArray[0] == null)
                subArray[0] = subPref;

                // If the user changes the subArray[1] to sub0 for the same sim app.
                // mark subArray[1] as null.
                if (subPref == subArray[1]) {
                        Log.d(TAG, "onPreferenceClick: SUB1->SUB0 sets subArray[1] = null");
                    subArray[1] = null;
                }
            } else if (checked == SubscriptionID.SUB_1) {
                // If user already selected a sub1 uncheck it
                if (subArray[1] != null) {
                        Log.d(TAG, "onPreferenceClick: clearing previously selected SUB1");
                        subArray[1].markAllUnChecked();
                }
                subArray[1] = subPref;

                // If the user changes the subArray[0] to sub0 for the same sim app.
                // mark subArray[0] as null.
                if (subPref == subArray[0]) {
                        Log.d(TAG, "onPreferenceClick: SUB0->SUB1 sets subArray[0] = null");
                    subArray[0] = null;
                }
            } else {
                // Use unchecks the preference, clear the array if this is present.
                if (subPref == subArray[0]) {
                    Log.d(TAG, "onPreferenceClick: SUB0->NONE sets subArray[0] = null");
                    subArray[0] = null;
                }
                if (subPref == subArray[1]) {
                    Log.d(TAG, "onPreferenceClick: SUB1->NONE sets subArray[1] = null");
                    subArray[1] = null;
                }
            }
            return true;
        }
    };

    // for View.OnClickListener
    public void onClick(View v) {
        if (v == mOkButton) {
            setSubscription();
        } else if (v == mCancelButton) {
            finish();
        }
    }

    private void setSubscription() {
        Log.d(TAG, "setSubscription");

        int numSubSelected = 0;
        int deactRequiredCount = 0;
        subErr = false;

        for (int i = 0; i < subArray.length; i++) {
            if (subArray[i] != null) {
                numSubSelected++;
            }
        }

        Log.d(TAG, "setSubscription: numSubSelected = " + numSubSelected);

        if (numSubSelected == 0) {
            // Show a message to prompt the user to select atleast one.
            Toast toast = Toast.makeText(getApplicationContext(),
                    R.string.set_subscription_error_atleast_one,
                    Toast.LENGTH_SHORT);
            toast.show();
        } else {
            for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
                if (subArray[i] == null) {
                    if (mCurrentSelSub.subscription[i].subStatus ==
                            Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                        Log.d(TAG, "setSubscription: Sub " + i + " not selected. Setting 99999");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        deactRequiredCount++;
                    }
                } else {
                    // Key is the string :  "slot<SlotId> index<IndexId>"
                    // Split the string into two and get the SlotId and IndexId.
                    String key = subArray[i].getKey();
                    Log.d(TAG, "setSubscription: key = " + key);
                    String splitKey[] = key.split(" ");
                    String sSlotId = splitKey[0].substring(splitKey[0].indexOf("slot") + 4);
                    int slotId = Integer.parseInt(sSlotId);
                    String sIndexId = splitKey[1].substring(splitKey[1].indexOf("index") + 5);
                    int subIndex = Integer.parseInt(sIndexId);

                    if (mCardSubscrInfo[slotId] == null) {
                        Log.d(TAG, "setSubscription: mCardSubscrInfo is not in sync "
                                + "with SubscriptionManager");
                        mUserSelSub.subscription[i].slotId = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gppIndex = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].m3gpp2Index = SUBSCRIPTION_INDEX_INVALID;
                        mUserSelSub.subscription[i].subId = i;
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_DEACTIVATE;

                        if (mCurrentSelSub.subscription[i].subStatus ==
                                Subscription.SubscriptionStatus.SUB_ACTIVATED) {
                            deactRequiredCount++;
                        }
                        continue;
                    }


                    // Compate the user selected subscriptio with the current subscriptions.
                    // If they are not matching, mark it to activate.
                    mUserSelSub.subscription[i].copyFrom(mCardSubscrInfo[slotId].
                            subscription[subIndex]);
                    mUserSelSub.subscription[i].subId = i;
                    if (mCurrentSelSub != null) {
                        // subStatus used to store the activation status as the mCardSubscrInfo
                        // is not keeping track of the activation status.
                        Subscription.SubscriptionStatus subStatus =
                                mCurrentSelSub.subscription[i].subStatus;
                        mUserSelSub.subscription[i].subStatus = subStatus;
                        if ((subStatus != Subscription.SubscriptionStatus.SUB_ACTIVATED) ||
                            (!mUserSelSub.subscription[i].equals(mCurrentSelSub.subscription[i]))) {
                            // User selected a new subscription.  Need to activate this.
                            mUserSelSub.subscription[i].subStatus = Subscription.
                            SubscriptionStatus.SUB_ACTIVATE;
                        }

                        if (mCurrentSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATED
                                 && mUserSelSub.subscription[i].subStatus == Subscription.
                                 SubscriptionStatus.SUB_ACTIVATE) {
                            deactRequiredCount++;
                        }
                    } else {
                        mUserSelSub.subscription[i].subStatus = Subscription.
                                SubscriptionStatus.SUB_ACTIVATE;
                    }
                }
            }

            if (deactRequiredCount >= MAX_SUBSCRIPTIONS) {
                errorMutipleDeactivate();
            } else {
                boolean ret = mSubscriptionManager.setSubscription(mUserSelSub);
                if (ret) {
                    showDialog(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);
                    mSubscriptionManager.registerForSetSubscriptionCompleted(mHandler,
                            EVENT_SET_SUBSCRIPTION_DONE, null);
                } else {
                    //TODO: Already some set sub in progress. Display a Toast?
                }
            }
        }
    }

    /**
     *  Displays an dialog box with error message.
     *  "Deactivation of both subscription is not supported"
     */
    private void errorMutipleDeactivate() {
        Log.d(TAG, "errorMutipleDeactivate()");

        new AlertDialog.Builder(this)
            .setTitle(R.string.config_sub_title)
            .setMessage(R.string.deact_all_sub_not_supported)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        Log.d(TAG, "errorMutipleDeactivate:  onClick");
                        updateCheckBoxes();
                    }
                })
            .show()
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    public void onDismiss(DialogInterface dialog) {
                        Log.d(TAG, "errorMutipleDeactivate:  onDismiss");
                        updateCheckBoxes();
                    }
                });
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            AsyncResult ar;

            switch(msg.what) {
                case EVENT_SET_SUBSCRIPTION_DONE:
                    Log.d(TAG, "EVENT_SET_SUBSCRIPTION_DONE");
                    mSubscriptionManager.unRegisterForSetSubscriptionCompleted(mHandler);
                    dismissDialog(DIALOG_SET_SUBSCRIPTION_IN_PROGRESS);
                    getPreferenceScreen().setEnabled(true);
                    ar = (AsyncResult) msg.obj;

                    String result[] = (String[]) ar.result;

                    if (result != null) {
                        displayAlertDialog(result);
                    } else {
                        finish();
                    }
                    break;
                case EVENT_SIM_STATE_CHANGED:
                    Log.d(TAG, "EVENT_SIM_STATE_CHANGED");
                    PreferenceScreen prefParent = (PreferenceScreen) getPreferenceScreen()
                                             .findPreference(PREF_PARENT_KEY);

                    for (int i = 0; i < mCardSubscrInfo.length; i++) {
                        PreferenceCategory subGroup = (PreferenceCategory) prefParent
                                 .findPreference("sub_group_" + i);
                        if (subGroup != null) {
                            subGroup.removeAll();
                        }
                    }
                    prefParent.removeAll();
                    populateList();
                    updateCheckBoxes();
                    break;
            }
        }
    };

    @Override
    protected Dialog onCreateDialog(int id) {
        if (id == DIALOG_SET_SUBSCRIPTION_IN_PROGRESS) {
            ProgressDialog dialog = new ProgressDialog(this);

            dialog.setMessage(getResources().getString(R.string.set_uicc_subscription_progress));
            dialog.setCancelable(false);
            dialog.setIndeterminate(true);

            return dialog;
        }
        return null;
    }

    @Override
    protected void onPrepareDialog(int id, Dialog dialog) {
        if (id == DIALOG_SET_SUBSCRIPTION_IN_PROGRESS) {
            // when the dialogs come up, we'll need to indicate that
            // we're in a busy state to disallow further input.
            getPreferenceScreen().setEnabled(false);
        }
    }
    private boolean isFailed(String status) {
        Log.d(TAG, "isFailed(" + status + ")");
        if (status == null ||
            (status != null &&
             (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)
              || status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)))) {
            return true;
        }
        return false;
    }

    String setSubscriptionStatusToString(String status) {
        String retStr = null;
        if (status.equals(SubscriptionManager.SUB_ACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_activate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_SUCCESS)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_success);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_failed);
        } else if (status.equals(SubscriptionManager.SUB_DEACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_deactivate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_FAILED)) {
            retStr = getResources().getString(R.string.set_sub_activate_failed);
        } else if (status.equals(SubscriptionManager.SUB_ACTIVATE_NOT_SUPPORTED)) {
            retStr = getResources().getString(R.string.set_sub_activate_not_supported);
        } else if (status.equals(SubscriptionManager.SUB_NOT_CHANGED)) {
            retStr = getResources().getString(R.string.set_sub_no_change);
        }
        return retStr;
    }

    void displayAlertDialog(String msg[]) {
        int resSubId[] = {R.string.set_sub_1, R.string.set_sub_2};
        String dispMsg = "";
        int title = R.string.set_sub_failed;

        if (msg[0] != null && isFailed(msg[0])) {
            subErr = true;
        }
        if (msg[1] != null && isFailed(msg[1])) {
            subErr = true;
        }

        for (int i = 0; i < msg.length; i++) {
            if (msg[i] != null) {
                dispMsg = dispMsg + getResources().getString(resSubId[i]) +
                                      setSubscriptionStatusToString(msg[i]) + "\n";
            }
        }

        if (!subErr) {
            title = R.string.set_sub_success;
        }

        Log.d(TAG, "displayAlertDialog:  dispMsg = " + dispMsg);
        new AlertDialog.Builder(this).setMessage(dispMsg)
            .setTitle(title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setPositiveButton(android.R.string.yes, this)
            .show()
            .setOnDismissListener(this);
    }

    // This is a method implemented for DialogInterface.OnDismissListener
    public void onDismiss(DialogInterface dialog) {
        // If the setSubscription failed for any of the sub, then don'd dismiss the
        // set subscription screen.
        if(!subErr) {
            finish();
        }
    }

    // This is a method implemented for DialogInterface.OnClickListener.
    // Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        // If the setSubscription failed for any of the sub, then don'd dismiss the
        // set subscription screen.
        if(!subErr) {
            finish();
        }
        updateCheckBoxes();
    }
}


// Widget for displaying the sim app name and two check boxes.
class SubscriptionCheckBoxPreference extends Preference implements View.OnClickListener {

    CheckBox mCheckBox1, mCheckBox2;
    TextView mTitleView;
    String mTitle;
    int mWidth;
    Preference.OnPreferenceClickListener mOnPrefClickListener;
    boolean mCheckBox1Status, mCheckBox2Status;

    public SubscriptionCheckBoxPreference(Context context, int width) {
        super(context);

        Log.d("SubscriptionCheckBoxPreference", "Constructor: ENTER: width = " + width);

        setLayoutResource(R.layout.preference_set_sub);
        mWidth = width;
        mOnPrefClickListener = null;
        mCheckBox1Status = false;
        mCheckBox2Status = false;
    }

    public void setTitleText(String resId) {
        mTitle = resId;
        if (mTitleView != null){
            mTitleView.setText(mTitle);
        }
    }

    @Override
    public void onBindView(View view) {
        super.onBindView(view);

        mCheckBox1 = (CheckBox) view.findViewById(R.id.check1);
        mCheckBox1.setOnClickListener(this);
        mCheckBox1.setChecked(mCheckBox1Status);
        mCheckBox1.setLayoutParams(new LayoutParams(75, LayoutParams.WRAP_CONTENT));
        mCheckBox2 = (CheckBox) view.findViewById(R.id.check2);
        mCheckBox2.setOnClickListener(this);
        mCheckBox2.setChecked(mCheckBox2Status);
        mCheckBox2.setLayoutParams(new LayoutParams(75, LayoutParams.WRAP_CONTENT));
        mTitleView = (TextView) view.findViewById(R.id.title1);
        mTitleView.setText(mTitle);
        mTitleView.setLayoutParams(new LayoutParams(mWidth - 150, LayoutParams.WRAP_CONTENT));
    }

    // for View.OnClickListener
    public void onClick(View v) {
        // User can select only one of the check box corresponds to a subscription.
        // So uncheck the other check box if it is already checked.
        if (v == mCheckBox1) {
            if(mCheckBox2.isChecked()) {
                mCheckBox2.setChecked(false);
            }
        }
        if (v == mCheckBox2) {
            if(mCheckBox1.isChecked()) {
                mCheckBox1.setChecked(false);
            }
        }

        if (mOnPrefClickListener != null) {
            mOnPrefClickListener.onPreferenceClick(this);
        }
    }

    public void markAllUnChecked() {
        if (mCheckBox1 != null) {
            mCheckBox1.setChecked(false);
        }
        if (mCheckBox2 != null) {
            mCheckBox2.setChecked(false);
        }
    }

    public void markChecked(SubscriptionID onSub) {
        if (onSub == SubscriptionID.SUB_0) {
            if (mCheckBox1 != null) {
                if (mCheckBox2 != null && mCheckBox2.isChecked()) {
                    mCheckBox2.setChecked(false);
                    mCheckBox2Status = false;
                }
                mCheckBox1.setChecked(true);
            } else {
                //onBindView yet to call.  set a flag and set checked in onBindView
                mCheckBox1Status = true;
                mCheckBox2Status = false;
                Log.d("SubscriptionCheckBoxPreference", "markChecked: mCheckBox1 == null");
            }
        } else if (onSub == SubscriptionID.SUB_1) {
            if (mCheckBox2 != null) {
                if (mCheckBox1 != null && mCheckBox1.isChecked()) {
                    mCheckBox1.setChecked(false);
                    mCheckBox1Status = false;
                }
                mCheckBox2.setChecked(true);
            } else {
                //onBindView yet to call.  set a flag and set checked in onBindView
                mCheckBox2Status = true;
                mCheckBox1Status = false;
                Log.d("SubscriptionCheckBoxPreference", "markChecked: mCheckBox2 == null");
            }
        }
    }

    public SubscriptionID getSelectedSubscription() {
        SubscriptionID ret;
        if (mCheckBox1.isChecked()) {
            ret = SubscriptionID.SUB_0;
        } else if (mCheckBox2.isChecked()) {
            ret = SubscriptionID.SUB_1;
        } else {
            ret = SubscriptionID.NONE;
        }
        return ret;
    }
    public void setOnSubPreferenceClickListener(Preference.
           OnPreferenceClickListener onPreferenceClickListener) {
        mOnPrefClickListener = onPreferenceClickListener;
    }
}

enum SubscriptionID {
    SUB_0,
    SUB_1,
    NONE;
}

