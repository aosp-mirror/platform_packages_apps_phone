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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.AudioManager;
import android.net.Uri;
import android.net.sip.SipManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.provider.ContactsContract.CommonDataKinds;
import android.provider.MediaStore;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.MenuItem;
import android.view.WindowManager;
import android.widget.ListAdapter;

import com.android.internal.telephony.CallForwardInfo;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.cdma.TtyIntent;
import com.android.phone.sip.SipSharedPreferences;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Top level "Call settings" UI; see res/xml/call_feature_setting.xml
 *
 * This preference screen is the root of the "Call settings" hierarchy
 * available from the Phone app; the settings here let you control various
 * features related to phone calls (including voicemail settings, SIP
 * settings, the "Respond via SMS" feature, and others.)  It's used only
 * on voice-capable phone devices.
 *
 * Note that this activity is part of the package com.android.phone, even
 * though you reach it from the "Phone" app (i.e. DialtactsActivity) which
 * is from the package com.android.contacts.
 *
 * For the "Mobile network settings" screen under the main Settings app,
 * See {@link MobileNetworkSettings}.
 *
 * @see com.android.phone.MobileNetworkSettings
 */
public class MSimCallFeaturesSetting extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener{
    private static final String LOG_TAG = "CallFeaturesSetting";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private static final String BUTTON_SELECT_SUB_KEY = "button_call_independent_serv";

    //Information about logical "up" Activity
    private static final String UP_ACTIVITY_PACKAGE = "com.android.contacts";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.contacts.activities.DialtactsActivity";

    // String keys for preference lookup
    // TODO: Naming these "BUTTON_*" is confusing since they're not actually buttons(!)
    private static final String BUTTON_PLAY_DTMF_TONE  = "button_play_dtmf_tone";
    private static final String BUTTON_DTMF_KEY        = "button_dtmf_settings";
    private static final String BUTTON_RETRY_KEY       = "button_auto_retry_key";
    private static final String BUTTON_TTY_KEY         = "button_tty_mode_key";
    private static final String BUTTON_HAC_KEY         = "button_hac_key";

    private static final String BUTTON_SIP_CALL_OPTIONS =
            "sip_call_options_key";
    private static final String BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY =
            "sip_call_options_wifi_only_key";
    private static final String SIP_SETTINGS_CATEGORY_KEY =
            "sip_settings_category_key";

    // preferred TTY mode
    // Phone.TTY_MODE_xxx
    static final int preferredTtyMode = Phone.TTY_MODE_OFF;

    // Dtmf tone types
    static final int DTMF_TONE_TYPE_NORMAL = 0;
    static final int DTMF_TONE_TYPE_LONG   = 1;

    public static final String HAC_KEY = "HACSetting";
    public static final String HAC_VAL_ON = "ON";
    public static final String HAC_VAL_OFF = "OFF";

    private Phone mPhone;
    private boolean mForeground;
    private AudioManager mAudioManager;
    private SipManager mSipManager;

    // status message sent back from handlers
    private static final int MSG_OK = 100;


    /** Whether dialpad plays DTMF tone or not. */
    private CheckBoxPreference mPlayDtmfTone;
    private CheckBoxPreference mButtonAutoRetry;
    private CheckBoxPreference mButtonHAC;
    private ListPreference mButtonDTMF;
    private ListPreference mButtonTTY;
    private ListPreference mButtonSipCallOptions;
    private SipSharedPreferences mSipSharedPreferences;

    /*
     * Click Listeners, handle click based on objects attached to UI.
     */

    // Click listener for all toggle events
    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mButtonDTMF) {
            return true;
        } else if (preference == mButtonTTY) {
            return true;
        } else if (preference == mButtonAutoRetry) {
            android.provider.Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    android.provider.Settings.System.CALL_AUTO_RETRY,
                    mButtonAutoRetry.isChecked() ? 1 : 0);
            return true;
        } else if (preference == mButtonHAC) {
            int hac = mButtonHAC.isChecked() ? 1 : 0;
            // Update HAC value in Settings database
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.HEARING_AID, hac);

            // Update HAC Value in AudioManager
            mAudioManager.setParameter(HAC_KEY, hac != 0 ? HAC_VAL_ON : HAC_VAL_OFF);
            return true;
        }
        return false;
    }

    /**
     * Implemented to support onPreferenceChangeListener to look for preference
     * changes.
     *
     * @param preference is the preference to be changed
     * @param objValue should be the value of the selection, NOT its localized
     * display value.
     */
    @Override
    public boolean onPreferenceChange(Preference preference, Object objValue) {
        if (DBG) {
            log("onPreferenceChange(). preferenece: \"" + preference + "\""
                    + ", value: \"" + objValue + "\"");
        }
        if (preference == mButtonDTMF) {
            int index = mButtonDTMF.findIndexOfValue((String) objValue);
            Settings.System.putInt(mPhone.getContext().getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, index);
        } else if (preference == mButtonTTY) {
            handleTTYChange(preference, objValue);
        } else if (preference == mButtonSipCallOptions) {
            handleSipCallOptionsChange(objValue);
        }
        // always let the preference setting proceed.
        return true;
    }

    /*
     * Activity class methods
     */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        if (DBG) log("onCreate(). Intent: " + getIntent());
        mPhone = PhoneApp.getInstance().getPhone();

        addPreferencesFromResource(R.xml.msim_call_feature_setting);

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // get buttons
        PreferenceScreen prefSet = getPreferenceScreen();

        mPlayDtmfTone = (CheckBoxPreference) findPreference(BUTTON_PLAY_DTMF_TONE);
        mButtonDTMF = (ListPreference) findPreference(BUTTON_DTMF_KEY);
        mButtonAutoRetry = (CheckBoxPreference) findPreference(BUTTON_RETRY_KEY);
        mButtonHAC = (CheckBoxPreference) findPreference(BUTTON_HAC_KEY);
        mButtonTTY = (ListPreference) findPreference(BUTTON_TTY_KEY);

        if (mPlayDtmfTone != null) {
            mPlayDtmfTone.setChecked(Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_WHEN_DIALING, 1) != 0);
        }

        if (mButtonDTMF != null) {
            if (getResources().getBoolean(R.bool.dtmf_type_enabled)) {
                mButtonDTMF.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonDTMF);
                mButtonDTMF = null;
            }
        }

        if (mButtonAutoRetry != null) {
            if (getResources().getBoolean(R.bool.auto_retry_enabled)) {
                mButtonAutoRetry.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonAutoRetry);
                mButtonAutoRetry = null;
            }
        }

        if (mButtonHAC != null) {
            if (getResources().getBoolean(R.bool.hac_enabled)) {

                mButtonHAC.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonHAC);
                mButtonHAC = null;
            }
        }

        if (mButtonTTY != null) {
            if (getResources().getBoolean(R.bool.tty_enabled)) {
                mButtonTTY.setOnPreferenceChangeListener(this);
            } else {
                prefSet.removePreference(mButtonTTY);
                mButtonTTY = null;
            }
        }

        createSipCallSettings();

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        PreferenceScreen selectSub = (PreferenceScreen) findPreference(BUTTON_SELECT_SUB_KEY);
        if (selectSub != null) {
            Intent intent = selectSub.getIntent();
            intent.putExtra(SelectSubscription.PACKAGE, "com.android.phone");
            intent.putExtra(SelectSubscription.TARGET_CLASS,
                    "com.android.phone.MSimCallFeaturesSubSetting");
        }

    }

    private void createSipCallSettings() {
        // Add Internet call settings.
        if (PhoneUtils.isVoipSupported()) {
            mSipManager = SipManager.newInstance(this);
            mSipSharedPreferences = new SipSharedPreferences(this);
            addPreferencesFromResource(R.xml.sip_settings_category);
            mButtonSipCallOptions = getSipCallOptionPreference();
            mButtonSipCallOptions.setOnPreferenceChangeListener(this);
            mButtonSipCallOptions.setValueIndex(
                    mButtonSipCallOptions.findIndexOfValue(
                            mSipSharedPreferences.getSipCallOption()));
            mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
        }
    }

    // Gets the call options for SIP depending on whether SIP is allowed only
    // on Wi-Fi only; also make the other options preference invisible.
    private ListPreference getSipCallOptionPreference() {
        ListPreference wifiAnd3G = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS);
        ListPreference wifiOnly = (ListPreference)
                findPreference(BUTTON_SIP_CALL_OPTIONS_WIFI_ONLY);
        PreferenceGroup sipSettings = (PreferenceGroup)
                findPreference(SIP_SETTINGS_CATEGORY_KEY);
        if (SipManager.isSipWifiOnly(this)) {
            sipSettings.removePreference(wifiAnd3G);
            return wifiOnly;
        } else {
            sipSettings.removePreference(wifiOnly);
            return wifiAnd3G;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mForeground = true;

        if (isAirplaneModeOn()) {
            Preference sipSettings = findPreference(SIP_SETTINGS_CATEGORY_KEY);
            PreferenceScreen screen = getPreferenceScreen();
            int count = screen.getPreferenceCount();
            for (int i = 0 ; i < count ; ++i) {
                Preference pref = screen.getPreference(i);
                if (pref != sipSettings) pref.setEnabled(false);
            }
            return;
        }

        if (mButtonDTMF != null) {
            int dtmf = Settings.System.getInt(getContentResolver(),
                    Settings.System.DTMF_TONE_TYPE_WHEN_DIALING, DTMF_TONE_TYPE_NORMAL);
            mButtonDTMF.setValueIndex(dtmf);
        }

        if (mButtonAutoRetry != null) {
            int autoretry = Settings.System.getInt(getContentResolver(),
                    Settings.System.CALL_AUTO_RETRY, 0);
            mButtonAutoRetry.setChecked(autoretry != 0);
        }

        if (mButtonHAC != null) {
            int hac = Settings.System.getInt(getContentResolver(), Settings.System.HEARING_AID, 0);
            mButtonHAC.setChecked(hac != 0);
        }

        if (mButtonTTY != null) {
            int settingsTtyMode = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.PREFERRED_TTY_MODE,
                    Phone.TTY_MODE_OFF);
            mButtonTTY.setValue(Integer.toString(settingsTtyMode));
            updatePreferredTtyModeSummary(settingsTtyMode);
        }

    }

    private boolean isAirplaneModeOn() {
        return Settings.System.getInt(getContentResolver(),
                Settings.System.AIRPLANE_MODE_ON, 0) != 0;
    }

    private void handleTTYChange(Preference preference, Object objValue) {
        int buttonTtyMode;
        buttonTtyMode = Integer.valueOf((String) objValue).intValue();
        int settingsTtyMode = android.provider.Settings.Secure.getInt(
                getContentResolver(),
                android.provider.Settings.Secure.PREFERRED_TTY_MODE, preferredTtyMode);
        if (DBG) log("handleTTYChange: requesting set TTY mode enable (TTY) to" +
                Integer.toString(buttonTtyMode));

        if (buttonTtyMode != settingsTtyMode) {
            switch(buttonTtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_FULL:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
                android.provider.Settings.Secure.putInt(getContentResolver(),
                        android.provider.Settings.Secure.PREFERRED_TTY_MODE, buttonTtyMode);
                break;
            default:
                buttonTtyMode = Phone.TTY_MODE_OFF;
            }

            mButtonTTY.setValue(Integer.toString(buttonTtyMode));
            updatePreferredTtyModeSummary(buttonTtyMode);
            Intent ttyModeChanged = new Intent(TtyIntent.TTY_PREFERRED_MODE_CHANGE_ACTION);
            ttyModeChanged.putExtra(TtyIntent.TTY_PREFFERED_MODE, buttonTtyMode);
            sendBroadcast(ttyModeChanged);
        }
    }

    private void handleSipCallOptionsChange(Object objValue) {
        String option = objValue.toString();
        mSipSharedPreferences.setSipCallOption(option);
        mButtonSipCallOptions.setValueIndex(
                mButtonSipCallOptions.findIndexOfValue(option));
        mButtonSipCallOptions.setSummary(mButtonSipCallOptions.getEntry());
    }

    private void updatePreferredTtyModeSummary(int TtyMode) {
        String [] txts = getResources().getStringArray(R.array.tty_mode_entries);
        switch(TtyMode) {
            case Phone.TTY_MODE_OFF:
            case Phone.TTY_MODE_HCO:
            case Phone.TTY_MODE_VCO:
            case Phone.TTY_MODE_FULL:
                mButtonTTY.setSummary(txts[TtyMode]);
                break;
            default:
                mButtonTTY.setEnabled(false);
                mButtonTTY.setSummary(txts[Phone.TTY_MODE_OFF]);
        }
    }

    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == android.R.id.home) {  // See ActionBar#setDisplayHomeAsUpEnabled()
            Intent intent = new Intent();
            intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Finish current Activity and go up to the top level Settings ({@link CallFeaturesSetting}).
     * This is useful for implementing "HomeAsUp" capability for second-level Settings.
     */
    public static void goUpToTopLevelSetting(Activity activity) {
        Intent intent = new Intent(activity, CallFeaturesSetting.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        activity.startActivity(intent);
        activity.finish();
    }


}
