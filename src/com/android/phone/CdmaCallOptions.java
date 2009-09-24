package com.android.phone;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import android.content.DialogInterface;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.util.Log;

public class CdmaCallOptions extends PreferenceActivity {
    private static final String LOG_TAG = "CdmaCallOptions";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private static final String BUTTON_VP_KEY = "button_voice_privacy_key";
    private CheckBoxPreference mButtonVoicePrivacy;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.cdma_call_options);

        mButtonVoicePrivacy = (CheckBoxPreference) findPreference(BUTTON_VP_KEY);
        if (PhoneFactory.getDefaultPhone().getPhoneType() != Phone.PHONE_TYPE_CDMA) {
            //disable the entire screen
            getPreferenceScreen().setEnabled(false);
        }
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference.getKey().equals(BUTTON_VP_KEY)) {
            return true;
        }
        return false;
    }

}
