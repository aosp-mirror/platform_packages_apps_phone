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

import android.os.Message;
import android.os.Handler;
import android.os.Bundle;
import android.os.AsyncResult;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.ListPreference;
import android.preference.PreferenceScreen;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.preference.CheckBoxPreference;
import android.util.Log;

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
    
    private static final int NETWORK_MODE_GLOBAL = 7;
    private static final int NETWORK_MODE_CDMA = 4;
    private static final int NETWORK_MODE_GSM_UMTS = 3;

    //preferredNetworkMode  7 - Global, CDMA Preferred
    //                      4 - CDMA only
    //                      3 - GSM/UMTS only
    static final int preferredNetworkMode = 7;
    
    //UI objects
    private ListPreference mButtonPreferredNetworkMode;
    private CheckBoxPreference mButtonDataRoam;
    
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
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference){
        if (preference == mButtonPreferredNetworkMode) {
            //displays the value taken from the Settings.System
            int settingsNetworkMode = 
                  android.provider.Settings.System.getInt(mPhone.getContext().getContentResolver(),
                  android.provider.Settings.System.PREFERRED_NETWORK_MODE, preferredNetworkMode);            
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
                        .setTitle(R.string.roaming_reenable_title)
                        .setIcon(com.android.internal.R.drawable.ic_dialog_alert)
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

        addPreferencesFromResource(R.xml.network_setting);
        
        mPhone = PhoneFactory.getDefaultPhone();
        mHandler = new MyHandler();

        //get UI object references
        PreferenceScreen prefSet = getPreferenceScreen();

        mButtonDataRoam = (CheckBoxPreference) prefSet.findPreference(BUTTON_ROAMING_KEY);
        mButtonPreferredNetworkMode = 
                (ListPreference) prefSet.findPreference(BUTTON_PREFERED_NETWORK_MODE);
        
        // set the listener for the mButtonPreferredNetworkMode list preference 
        // so we can issue change Preferred Network Mode.
        mButtonPreferredNetworkMode.setOnPreferenceChangeListener(this);
        
        //Get the networkMode from Settings.System and displays it
        int settingsNetworkMode = 
                android.provider.Settings.System.getInt(mPhone.getContext().getContentResolver(),
                android.provider.Settings.System.PREFERRED_NETWORK_MODE, preferredNetworkMode);
        mButtonPreferredNetworkMode.setValue(Integer.toString(settingsNetworkMode));
        // The intent code that resided here in the past has been moved into the
        // more conventional location in network_setting.xml
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
        mPhone.getPreferredNetworkType(mHandler.obtainMessage(
                MyHandler.MESSAGE_GET_PREFERRED_NETWORK_TYPE));
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
            //int buttonNetworkMode = Integer.valueOf((String) objValue).intValue(); //TODO not working!!!
            int buttonNetworkMode;
            buttonNetworkMode = Integer.valueOf((String) objValue).intValue();
            int settingsNetworkMode = 
                  android.provider.Settings.System.getInt(mPhone.getContext().getContentResolver(),
                  android.provider.Settings.System.PREFERRED_NETWORK_MODE, preferredNetworkMode);
            if (buttonNetworkMode != settingsNetworkMode) {
                int modemNetworkMode; 
                switch(buttonNetworkMode) {
                    case NETWORK_MODE_GSM_UMTS:
                        modemNetworkMode = Phone.NT_GSM_UMTS_AUTO_TYPE;
                        break;
                    case NETWORK_MODE_CDMA:
                        modemNetworkMode = Phone.NT_CDMA_EVDO_AUTO_TYPE;
                        break;
                    case NETWORK_MODE_GLOBAL:
                    default:
                        modemNetworkMode = Phone.NT_GLOBAL_AUTO_TYPE;
                } 
                // Set the Settings.System network mode 
                // TODO remove later 
                // TODO, this will be done only later after the reception 
                // of MESSAGE_SET_PREFERRED_NETWORK_TYPE
                android.provider.Settings.System.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.System.PREFERRED_NETWORK_MODE, 
                        buttonNetworkMode );
                //Set the modem network moode
                mPhone.setPreferredNetworkType(modemNetworkMode, mHandler
                        .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
            }
        }

        // always let the preference setting proceed.
        return true;
    }

    private class MyHandler extends Handler {

        private static final int MESSAGE_GET_PREFERRED_NETWORK_TYPE = 0;
        private static final int MESSAGE_SET_PREFERRED_NETWORK_TYPE = 1;
        
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_PREFERRED_NETWORK_TYPE:
                    handleGetPreferredNetworkTypeResponse(msg);
                    break;
                    
                case MESSAGE_SET_PREFERRED_NETWORK_TYPE:
                    handleSetPreferredNetworkTypeResponse(msg);
                    break;
            }
        }
        
        private void handleGetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            
            if (ar.exception == null) {
                int modemNetworkMode = ((int[])ar.result)[0];
                
                if (DBG){
                    log("handleGetPreferredNetworkTypeResponse: modemNetworkMode = " +
                            modemNetworkMode);
                }
                
                int settingsNetworkMode = android.provider.Settings.System.getInt(
                        mPhone.getContext().getContentResolver(),
                        android.provider.Settings.System.PREFERRED_NETWORK_MODE,
                        preferredNetworkMode);
                
                if (DBG){
                    log("handleGetPreferredNetworkTypeReponse: settingsNetworkMode = " +
                            settingsNetworkMode);
                }
                
                //check that modemNetworkMode is from an accepted value
                if (modemNetworkMode == Phone.NT_GLOBAL_AUTO_TYPE ||  
                        modemNetworkMode == Phone.NT_CDMA_EVDO_AUTO_TYPE ||
                        modemNetworkMode == Phone.NT_GSM_UMTS_AUTO_TYPE ) {
                    if (DBG){
                        log("handleGetPreferredNetworkTypeResponse: if 1: modemNetworkMode = " +
                                modemNetworkMode);
                    }
                
                    //check changes in modemNetworkMode and updates settingsNetworkMode 
                    if (modemNetworkMode != settingsNetworkMode) {
                        if (DBG){
                            log("handleGetPreferredNetworkTypeResponse: if 2: modemNetworkMode !="+
                            		" settingsNetworkMode");
                        }
                        
                        settingsNetworkMode = modemNetworkMode;
                        
                        if (DBG){
                            log("handleGetPreferredNetworkTypeResponse: " +
                            		"if 2: settingsNetworkMode = " + settingsNetworkMode);
                        }
                        
                        //changes the Settings.System accordingly to modemNetworkMode
                        android.provider.Settings.System.putInt(
                                mPhone.getContext().getContentResolver(),
                                android.provider.Settings.System.PREFERRED_NETWORK_MODE, 
                                settingsNetworkMode );
                    }

                        //changes the mButtonPreferredNetworkMode accordingly to modemNetworkMode
                        //TODO Remove, rewrite a better algorithm to keep track of this!!
                        mButtonPreferredNetworkMode.setValue(
                                Integer.toString(modemNetworkMode)); 
                    }
                else {
                    if (DBG) log("handleGetPreferredNetworkTypeResponse: else: reset to default");
                    resetNetworkModeToDefault();
                }
            }
        }

        private void handleSetPreferredNetworkTypeResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception == null) {
                int networkMode = Integer.valueOf(
                        mButtonPreferredNetworkMode.getValue()).intValue();
                android.provider.Settings.System.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.System.PREFERRED_NETWORK_MODE, 
                        networkMode );
            } else {
                mPhone.getPreferredNetworkType(obtainMessage(MESSAGE_GET_PREFERRED_NETWORK_TYPE));
            }
        }
        
        private void resetNetworkModeToDefault() {
            //set networkMode to default
            int networkMode = preferredNetworkMode;
            int modemNetworkMode = Phone.NT_GLOBAL_AUTO_TYPE;
            //set the mButtonPreferredNetworkMode
            mButtonPreferredNetworkMode.setValue(Integer.toString(networkMode));
            //set the Settings.System 
            android.provider.Settings.System.putInt(mPhone.getContext().getContentResolver(),
                        android.provider.Settings.System.PREFERRED_NETWORK_MODE, 
                        networkMode );            
            //Set the Modem
            mPhone.setPreferredNetworkType(modemNetworkMode, this //TODO check if mHandler possible
                    .obtainMessage(MyHandler.MESSAGE_SET_PREFERRED_NETWORK_TYPE));
        }
    }
    
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
    
