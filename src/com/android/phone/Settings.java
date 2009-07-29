/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Message;
import android.os.Handler;
import android.os.Bundle;
import android.os.AsyncResult;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

/**
 * List of Phone-specific settings screens.
 */
public class Settings extends PreferenceActivity implements DialogInterface.OnClickListener,
        DialogInterface.OnDismissListener, Preference.OnPreferenceChangeListener{

    // debug data
    private static final String LOG_TAG = "NetworkSettings";
    private static final boolean DBG = true;

    //String keys for preference lookup
    private static final String BUTTON_PREFERED_NETWORK_MODE = "preferred_network_mode_key";
    private static final String BUTTON_ROAMING_KEY = "button_roaming_key";
    private static final String BUTTON_PREFER_2G_KEY = "button_prefer_2g_key";
    private static final String BUTTON_CDMA_SYSTEM_SELECT_KEY = "cdma_system_select_key";
    private static final String GSM_OPTIONS_KEY = "gsm_umts_options_key";
    private static final String CDMA_OPTIONS_KEY = "cdma_options_key";

    // Used for CDMA roaming mode
    private static final int CDMA_ROAMING_MODE_HOME = 0;
    private static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    private static final int CDMA_ROAMING_MODE_ANY = 2;

    // PREFERRED_CDMA_ROAMING_MODE  0 - Home Networks only, preferred
    //                              1 - Roaming on affiliated networks
    //                              2 - Roaming on any network
    static final int PREFERRED_CDMA_ROAMING_MODE = 0;

    static final int preferredNetworkMode = Phone.PREFERRED_NT_MODE;

    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private CheckBoxPreference mButtonDataRoam;
    private CheckBoxPreference mButtonPrefer2g;
    private ListPreference mButtonCdmaRoam;

    private Phone mPhone;
    private MyHandler mHandler;
    private boolean mOkClicked;


    //This is a method implemented for DialogInterface.OnClickListener.
    //  Used to dismiss the dialogs when they come up.
    public void onClick(DialogInterface dialog, int which) {
        if (which == DialogInterface.BUTTON1) {
            mPhone.setDataRoamingEnabled(true);
            mOkClicked = true;
        } else {
            // Reset the toggle
            mButtonDataRoam.setChecked(false);
        }
    }

    public void onDismiss(DialogInterface dialog) {
        // Assuming that onClick gets called first
        if (!mOkClicked) {
            mButtonDataRoam.setChecked(false);
        }
    }

    /**
     * Invoked on each preference click in this hierarchy, overrides
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = android.provider.Settings.Secure.getInt(mPhone.getContext().
                    getContentResolver(), android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                    preferredNetworkMode);
            mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
            return true;
        }
        else if (preference == mButtonDataRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonDataRoam.");

            //normally called on the toggle click
            if (mButtonDataRoam.isChecked()) {
                // First confirm with a warning dialog about charges
                mOkClicked = false;
                new AlertDialog.Builder(this).setMessage(
                        getResources().getString(R.string.roaming_warning))
                        .setTitle(android.R.string.dialog_alert_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(android.R.string.yes, this)
                        .setNegativeButton(android.R.string.no, this)
                        .show()
                        .setOnDismissListener(this);
            }
            else {
                mPhone.setDataRoamingEnabled(false);
            }
            return true;
        }
        else if (preference == mButtonCdmaRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonCdmaRoam.");
            //displays the value taken from the Settings.System
            int cdmaRoamingMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE, PREFERRED_CDMA_ROAMING_MODE);
            mButtonCdmaRoam.setValue(Integer.toString(cdmaRoamingMode));
            return true;
        }
        else if (preference == mButtonPrefer2g) {
            int networkType = mButtonPrefer2g.isChecked() ? Phone.NT_MODE_GSM_ONLY
                    : Phone.NT_MODE_WCDMA_PREF;
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                    networkType);
            mPhone.setPreferredNetworkType(networkType,
                    mHandler.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            return true;
        }
        else {
            // if the button is anything but the simple toggle preference,
            // we'll need to disable all preferences to reject all click
            // events until the sub-activity's UI comes up.
            preferenceScreen.setEnabled(false);
            // Let the intents be launched by the Preference manager
            return false;
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        mPhone = PhoneFactory.getDefaultPhone();

        if (mPhone.getPhoneName().equals("CDMA")) {
            addPreferencesFromResource(R.xml.network_setting_cdma);

        } else {
            addPreferencesFromResource(R.xml.network_setting);
        }

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mHandler = new MyHandler();
        mButtonDataRoam = (CheckBoxPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);

        // Remove the other network type options
        boolean isCdma = mPhone.getPhoneName().equals("CDMA");

        // TODO: The radio technology could be changed dynamically after the phone has been created
        if (isCdma) {
            mButtonCdmaRoam =
                (ListPreference) prefSet.findPreference(BUTTON_CDMA_SYSTEM_SELECT_KEY);
            // set the listener for the mButtonCdmaRoam list preference so we can issue
            // change CDMA Roaming Mode.
            mButtonCdmaRoam.setOnPreferenceChangeListener(this);

            //Get the settingsCdmaRoamingMode from Settings.System and displays it
            int settingsCdmaRoamingMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                    PREFERRED_CDMA_ROAMING_MODE);
            mButtonCdmaRoam.setValue(Integer.toString(settingsCdmaRoamingMode));
        } else {
            mButtonPrefer2g = (CheckBoxPreference) prefSet.findPreference(BUTTON_PREFER_2G_KEY);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(true);

        // Set UI state in onResume because a user could go home, launch some
        // app to change this setting's backend, and re-launch this settings app
        // and the UI state would be inconsistent with actual state
        mButtonDataRoam.setChecked(mPhone.getDataRoamingEnabled());
        if (mPhone.getPhoneName().equals("CDMA")) {
            mPhone.queryCdmaRoamingPreference(
                    mHandler.obtainMessage(MyHandler.MESSAGE_QUERY_ROAMING_PREFERENCE));
        } else {
            // Get the state for 'prefer 2g' setting
            mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                    MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
        }
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCLIR.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (preference == mButtonPreferredNetworkMode) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonPreferredNetworkMode.setValue((String) objValue);
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode;
                switch(buttonNetworkMode) {
                    case Phone.NT_MODE_GLOBAL:
                        modemNetworkMode = Phone.NT_MODE_GLOBAL;
                        break;
                    case Phone.NT_MODE_EVDO_NO_CDMA:
                        modemNetworkMode = Phone.NT_MODE_EVDO_NO_CDMA;
                        break;
                    case Phone.NT_MODE_CDMA_NO_EVDO:
                        modemNetworkMode = Phone.NT_MODE_CDMA_NO_EVDO;
                        break;
                    case Phone.NT_MODE_CDMA:
                        modemNetworkMode = Phone.NT_MODE_CDMA;
                        break;
                    case Phone.NT_MODE_GSM_UMTS:
                        modemNetworkMode = Phone.NT_MODE_GSM_UMTS;
                        break;
                    case Phone.NT_MODE_WCDMA_ONLY:
                        modemNetworkMode = Phone.NT_MODE_WCDMA_ONLY;
                        break;
                    case Phone.NT_MODE_GSM_ONLY:
                        modemNetworkMode = Phone.NT_MODE_GSM_ONLY;
                        break;
                    case Phone.NT_MODE_WCDMA_PREF:
                        modemNetworkMode = Phone.NT_MODE_WCDMA_PREF;
                        break;
                    default:
                        modemNetworkMode = Phone.PREFERRED_NT_MODE;
                }
                updatePreferredNetworkModeSummary(buttonNetworkMode);

                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        buttonNetworkMode );
                //Set the modem network mode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        } else if (preference == mButtonCdmaRoam) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonCdmaRoam.setValue((String) objValue);
            int buttonCdmaRoamingMode;
            buttonCdmaRoamingMode = Integer.valueOf((String) objValue).intValue();
            int settingsCdmaRoamingMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE, PREFERRED_CDMA_ROAMING_MODE);
            if (buttonCdmaRoamingMode != settingsCdmaRoamingMode) {
                int statusCdmaRoamingMode;
                switch(buttonCdmaRoamingMode) {
                    case CDMA_ROAMING_MODE_ANY:
                        statusCdmaRoamingMode = Phone.CDMA_RM_ANY;
                        break;
                        /*
                    case CDMA_ROAMING_MODE_AFFILIATED:
                        statusCdmaRoamingMode = Phone.CDMA_RM_AFFILIATED;
                        break;
                        */
                    case CDMA_ROAMING_MODE_HOME:
                    default:
                        statusCdmaRoamingMode = Phone.CDMA_RM_HOME;
                }
                //Set the Settings.System network mode
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                        buttonCdmaRoamingMode );
                //Set the roaming preference mode
                mPhone.setCdmaRoamingPreference(statusCdmaRoamingMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_ROAMING_PREFERENCE));
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private class MyHandler extends Handler {

        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;
        private static final int MESSAGE_QUERY_ROAMING_PREFERENCE = 2;
        private static final int MESSAGE_SET_ROAMING_PREFERENCE = 3;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;

                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
                case MESSAGE_QUERY_ROAMING_PREFERENCE:
                    handleQueryCdmaRoamingPreference(msg);
                    break;

                case MESSAGE_SET_ROAMING_PREFERENCE:
                    handleSetCdmaRoamingPreference(msg);
                    break;
             }
        }

        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];

                if (DBG) {
                    log ("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }

                int settingsNetworkMode = android.provider.Settings.Secure.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);

                if (DBG) {
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }

                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_MODE_WCDMA_PREF ||
                        modemNetworkMode == Phone.NT_MODE_GSM_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_WCDMA_ONLY ||
                        modemNetworkMode == Phone.NT_MODE_GSM_UMTS ||
                        modemNetworkMode == Phone.NT_MODE_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_CDMA_NO_EVDO ||
                        modemNetworkMode == Phone.NT_MODE_EVDO_NO_CDMA ||
                        modemNetworkMode == Phone.NT_MODE_GLOBAL ) {
                    if (DBG) {
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }

                    //check changes in modemNetworkMode and updates settingsNetworkMode
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG) {
                            log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                    "modemNetworkMode != settingsNetworkMode");
                        }

                        settingsNetworkMode = modemNetworkMode;

                        if (DBG) { log("handleGetPreferredNetworkTypeResponse: if 2: " +
                                "settingsNetworkMode = " + settingsNetworkMode);
                        }

                        //changes the Settings.System accordingly to modemNetworkMode
                        android.provider.Settings.Secure.putInt(
                                mPhone.getContext().getContentResolver(),
                                android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                                settingsNetworkMode );
                    }
                    if (mPhone.getPhoneName().equals("CDMA")) {
                        updatePreferredNetworkModeSummary(modemNetworkMode);
                        // changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                        mButtonPreferredNetworkMode.setValue(Integer.toString(modemNetworkMode));
                    } else {
                        mButtonPrefer2g.setChecked(modemNetworkMode == Phone.NT_MODE_GSM_ONLY);
                    }
                } else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int networkMode;
                if (mPhone.getPhoneName().equals("CDMA")) {
                    networkMode =
                        Integer.valueOf(mButtonPreferredNetworkMode.getValue()).intValue();
                } else {
                    networkMode = mButtonPrefer2g.isChecked()
                            ? Phone.NT_MODE_GSM_ONLY : Phone.NT_MODE_WCDMA_PREF;
                }
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        networkMode);
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }

        private void resetNetworkModeToDefault() {
            if (mPhone.getPhoneName().equals("CDMA")) {
                //set the mButtonPreferredNetworkMode
                mButtonPreferredNetworkMode.setValue(Integer.toString(preferredNetworkMode));
            } else {
                mButtonPrefer2g.setChecked(preferredNetworkMode == Phone.NT_MODE_GSM_ONLY);
            }
            //set the Settings.System
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode );
            //Set the Modem
            mPhone.setPreferredNetworkType(preferredNetworkMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }

        private void handleQueryCdmaRoamingPreference(Message msg) {
            mPhone = PhoneFactory.getDefaultPhone();
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int statusCdmaRoamingMode = ((int[])ar.result)[0];
                int settingsRoamingMode = android.provider.Settings.Secure.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                        PREFERRED_CDMA_ROAMING_MODE);
                //check that statusCdmaRoamingMode is from an accepted value
                if (statusCdmaRoamingMode == Phone.CDMA_RM_HOME ||
                        statusCdmaRoamingMode == Phone.CDMA_RM_AFFILIATED ||
                        statusCdmaRoamingMode == Phone.CDMA_RM_ANY ) {
                    //check changes in statusCdmaRoamingMode and updates settingsRoamingMode
                    if (statusCdmaRoamingMode != settingsRoamingMode) {
                        settingsRoamingMode = statusCdmaRoamingMode;
                        //changes the Settings.System accordingly to statusCdmaRoamingMode
                        android.provider.Settings.Secure.putInt(
                                mPhone.getContext().getContentResolver(),
                                android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                                settingsRoamingMode );
                    }
                    //changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    mButtonCdmaRoam.setValue(
                            Integer.toString(statusCdmaRoamingMode));
                } else {
                    resetCdmaRoamingModeToDefault();
                }
            }
        }

        private void handleSetCdmaRoamingPreference(Message msg) {
            mPhone = PhoneFactory.getDefaultPhone();
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int cdmaRoamingMode = Integer.valueOf(mButtonCdmaRoam.getValue()).intValue();
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                        cdmaRoamingMode );
            } else {
                mPhone.queryCdmaRoamingPreference(obtainMessage(MESSAGE_QUERY_ROAMING_PREFERENCE));
            }
        }

        private void resetCdmaRoamingModeToDefault() {
            mPhone = PhoneFactory.getDefaultPhone();
            //set cdmaRoamingMode to default
            int cdmaRoamingMode = PREFERRED_CDMA_ROAMING_MODE;
            int statusCdmaRoamingMode = Phone.CDMA_RM_HOME;
            //set the mButtonCdmaRoam
            mButtonCdmaRoam.setValue(Integer.toString(cdmaRoamingMode));
            //set the Settings.System
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE,
                        cdmaRoamingMode );
            //Set the Status
            mPhone.setCdmaRoamingPreference(statusCdmaRoamingMode,
                    this.obtainMessage(MyHandler.MESSAGE_SET_ROAMING_PREFERENCE));
        }
    }

    private void updatePreferredNetworkModeSummary(int NetworkMode) {
        if (!mPhone.getPhoneName().equals("CDMA")) return;
        mButtonPreferredNetworkMode.setSummary(mButtonPreferredNetworkMode.getEntry());
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
