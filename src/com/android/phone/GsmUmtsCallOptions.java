package com.android.phone;

import com.android.internal.telephony.PhoneFactory;

import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;

public class GsmUmtsCallOptions extends PreferenceActivity {
    private static final String LOG_TAG = "GsmUmtsCallOptions";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.gsm_umts_call_options);

        if (!PhoneFactory.getDefaultPhone().getPhoneName().equals("GSM")) {
            //disable the entire screen
            getPreferenceScreen().setEnabled(false);
        }
    }
}
