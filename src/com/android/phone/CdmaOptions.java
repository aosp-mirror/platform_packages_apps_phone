/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.AsyncResult;
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
public class CdmaOptions extends PreferenceActivity implements Preference.OnPreferenceChangeListener{
    
    // debug data
    private static final String LOG_TAG = "CdmaOptions";
    private static final boolean DBG = true;
    
    //String keys for preference lookup
    private static final String BUTTON_CDMA_ROAMING_KEY = "cdma_roaming_mode_key";
    private static final String BUTTON_CDMA_NW_PREFERENCE_KEY = "cdma_network_prefernces_key";
    private static final String BUTTON_CDMA_SUBSCRIPTION_KEY = "subscription_key";
    private static final String BUTTON_CB_SMS_EXPAND_KEY = "cdma_cell_broadcast_sms_key";
    
    // Used for CDMA roaming mode
    private static final int CDMA_ROAMING_MODE_HOME = 0;
    private static final int CDMA_ROAMING_MODE_AFFILIATED = 1;
    private static final int CDMA_ROAMING_MODE_ANY = 2;
    
    //preferredCdmaRoamingMode  0 - Home Networks only, preferred
    //                          1 - Roaming on affiliated networks
    //                          2 - Roaming on any network
    static final int preferredCdmaRoamingMode = 0;
    
    // Used for CDMA subscription mode
    private static final int CDMA_SUBSCRIPTION_RUIM_SIM = 0;
    private static final int CDMA_SUBSCRIPTION_NV = 1;
    
    //preferredSubscriptionMode  0 - RUIM/SIM, preferred
    //                           1 - NV
    static final int preferredSubscriptionMode = CDMA_SUBSCRIPTION_NV;
    
    //UI objects
    private ListPreference mButtonCdmaRoam;
    private ListPreference mButtonCdmaNwPreference;
    private ListPreference mButtonCdmaSubscription;
    private PreferenceScreen mButtonCbSmsExpand;
    
    private Phone mPhone;
    private MyHandler mHandler;

    
    /** 
     * Invoked on each preference click in this hierarchy, overrides 
     * PreferenceActivity's implementation.  Used to make sure we track the
     * preference click events.
     */
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonCdmaRoam) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonCdmaRoam.");
            //displays the value taken from the Settings.System
            int cdmaRoamingMode = android.provider.Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE, preferredCdmaRoamingMode);            
            mButtonCdmaRoam.setValue(Integer.toString(cdmaRoamingMode));
            return true;
        } 
        else if (preference == mButtonCdmaNwPreference) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonCdmaNwPreference.");
            return true;
        }
        else if (preference == mButtonCdmaSubscription) {
            if (DBG) log("onPreferenceTreeClick: preference == mButtonCdmaSubscription.");
            int cdmaSubscriptionMode = android.provider.Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_SUBSCRIPTION_MODE, preferredSubscriptionMode);            
            mButtonCdmaSubscription.setValue(Integer.toString(cdmaSubscriptionMode));
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

    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_options);
        
        mPhone = PhoneFactory.getDefaultPhone();
        mHandler = new MyHandler();

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonCdmaRoam = (ListPreference) prefSet.findPreference(BUTTON_CDMA_ROAMING_KEY);
        mButtonCdmaSubscription = 
                (ListPreference) prefSet.findPreference(BUTTON_CDMA_SUBSCRIPTION_KEY);
        mButtonCdmaNwPreference = 
                (ListPreference) prefSet.findPreference(BUTTON_CDMA_NW_PREFERENCE_KEY);
        mButtonCbSmsExpand = (PreferenceScreen) prefSet.findPreference(BUTTON_CB_SMS_EXPAND_KEY);

        if (mPhone.getPhoneName().equals("CDMA")) {
            // set the listener for the mButtonCdmaRoam list preference so we can issue 
            // change CDMA Roaming Mode.
            mButtonCdmaRoam.setOnPreferenceChangeListener(this);
            // set the listener for the mButtonCdmaRoam list preference so we can issue 
            // change CDMA Roaming Mode.
            mButtonCdmaSubscription.setOnPreferenceChangeListener(this);

            //Get the settingsCdmaRoamingMode from Settings.System and displays it
            int settingsCdmaRoamingMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE, 
                    preferredCdmaRoamingMode);
            mButtonCdmaRoam.setValue(Integer.toString(settingsCdmaRoamingMode));

            //Get the settingsCdmaSubscriptionMode from Settings.System and displays it
            int settingsCdmaSubscriptionMode = android.provider.Settings.Secure.getInt(
                    mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_SUBSCRIPTION_MODE, 
                    preferredSubscriptionMode);
            mButtonCdmaSubscription.setValue(Integer.toString(settingsCdmaSubscriptionMode));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        mPhone = PhoneFactory.getDefaultPhone();
        // upon resumption from the sub-activity, make sure we re-enable the
        // preferences.
        getPreferenceScreen().setEnabled(true);

        if (mPhone.getPhoneName().equals("CDMA")) {
            mPhone.queryCdmaRoamingPreference(
                    mHandler.obtainMessage(MyHandler.MESSAGE_QUERY_ROAMING_PREFERENCE));
        }
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes specifically on CLIR.
     *
     * @param preference is the preference to be changed, should be mButtonCdmaRoam.
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        mPhone = PhoneFactory.getDefaultPhone();
        if (preference == mButtonCdmaRoam) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonCdmaRoam.setValue((String) objValue);
            int buttonCdmaRoamingMode;
            buttonCdmaRoamingMode = Integer.valueOf((String) objValue).intValue();
            int settingsCdmaRoamingMode = android.provider.Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_ROAMING_MODE, preferredCdmaRoamingMode);
            if (buttonCdmaRoamingMode != settingsCdmaRoamingMode) {
                int statusCdmaRoamingMode; 
                switch(buttonCdmaRoamingMode) {
                    case CDMA_ROAMING_MODE_ANY:
                        statusCdmaRoamingMode = Phone.CDMA_RM_ANY;
                        break;
                    case CDMA_ROAMING_MODE_AFFILIATED:
                        statusCdmaRoamingMode = Phone.CDMA_RM_AFFILIATED;
                        break;
                    case CDMA_ROAMING_MODE_HOME:
                    default:
                        statusCdmaRoamingMode = Phone.CDMA_RM_HOME;
                }
                //Set the Settings.Secure network mode       
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE, 
                        buttonCdmaRoamingMode );
                //Set the roaming preference mode
                mPhone.setCdmaRoamingPreference(statusCdmaRoamingMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_ROAMING_PREFERENCE));
            }
        }
        if (preference == mButtonCdmaSubscription) {
            //NOTE onPreferenceChange seems to be called even if there is no change
            //Check if the button value is changed from the System.Setting
            mButtonCdmaSubscription.setValue((String) objValue);
            int buttonCdmaSubscriptionMode;
            buttonCdmaSubscriptionMode = Integer.valueOf((String) objValue).intValue();
            int settingsCdmaSubscriptionMode = android.provider.Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_SUBSCRIPTION_MODE, preferredSubscriptionMode);
            if (buttonCdmaSubscriptionMode != settingsCdmaSubscriptionMode) {
                int statusCdmaSubscriptionMode; 
                switch(buttonCdmaSubscriptionMode) {
                    case CDMA_SUBSCRIPTION_NV:
                        statusCdmaSubscriptionMode = Phone.CDMA_SUBSCRIPTION_NV;
                        break;
                    case CDMA_SUBSCRIPTION_RUIM_SIM:
                        statusCdmaSubscriptionMode = Phone.CDMA_SUBSCRIPTION_RUIM_SIM;
                        break;
                    default:
                        statusCdmaSubscriptionMode = Phone.PREFERRED_CDMA_SUBSCRIPTION;
                } 
                //Set the Settings.System network mode        
                android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_SUBSCRIPTION_MODE, 
                        buttonCdmaSubscriptionMode );
                //Set the CDMA subscription mode
                mPhone.setCdmaSubscription(statusCdmaSubscriptionMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_CDMA_SUBSCRIPTION));
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private class MyHandler extends Handler {

        private static final int MESSAGE_QUERY_ROAMING_PREFERENCE = 0;
        private static final int MESSAGE_SET_ROAMING_PREFERENCE = 1;
        private static final int MESSAGE_SET_CDMA_SUBSCRIPTION = 2;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_QUERY_ROAMING_PREFERENCE:
                    handleQueryCdmaRoamingPreference(msg);
                    break;
                    
                case MESSAGE_SET_ROAMING_PREFERENCE:
                    handleSetCdmaRoamingPreference(msg);
                    break;
                case MESSAGE_SET_CDMA_SUBSCRIPTION:
                    handleSetCdmaSubscriptionMode(msg);
                    break;
            }
        }

        private void handleQueryCdmaRoamingPreference(Message msg) {
            mPhone = PhoneFactory.getDefaultPhone();
            AsyncResult ar = (AsyncResult) msg.obj;
            
            if (ar.exception == null) {
                int statusCdmaRoamingMode = ((int[])ar.result)[0];
                int settingsRoamingMode = android.provider.Settings.Secure.getInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.Secure.CDMA_ROAMING_MODE, preferredCdmaRoamingMode);
                //check that statusCdmaRoamingMode is from an accepted value
                if (statusCdmaRoamingMode == Phone.CDMA_RM_HOME ||  
                        statusCdmaRoamingMode == Phone.CDMA_RM_AFFILIATED ||
                        statusCdmaRoamingMode == Phone.CDMA_RM_ANY ) {
                    //check changes in statusCdmaRoamingMode and updates settingsRoamingMode 
                    if (statusCdmaRoamingMode != settingsRoamingMode) {
                        settingsRoamingMode = statusCdmaRoamingMode;
                        //changes the Settings.Secure accordingly to statusCdmaRoamingMode
                        android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                                android.provider.Settings.Secure.CDMA_ROAMING_MODE, 
                                settingsRoamingMode );
                    }
                    //changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                    mButtonCdmaRoam.setValue(
                            Integer.toString(statusCdmaRoamingMode));
                }
                else {
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
            int cdmaRoamingMode = preferredCdmaRoamingMode;
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

    private void handleSetCdmaSubscriptionMode(Message msg) {
        mPhone = PhoneFactory.getDefaultPhone();
        AsyncResult ar = (AsyncResult) msg.obj;

        if (ar.exception == null) {
            int cdmaSubscriptionMode = Integer.valueOf(mButtonCdmaSubscription.getValue()).intValue();
            android.provider.Settings.Secure.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.Secure.CDMA_SUBSCRIPTION_MODE, 
                    cdmaSubscriptionMode );
        } 
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}


