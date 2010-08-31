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

package com.android.phone.sip;

import com.android.phone.R;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.sip.SipProfile;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * The activity class for editing a new or existing SIP profile.
 */
public class SipEditor extends PreferenceActivity
        implements Preference.OnPreferenceChangeListener {
    private static final int MENU_SAVE = Menu.FIRST;
    private static final int MENU_DISCARD = Menu.FIRST + 1;

    private static final String TAG = SipEditor.class.getSimpleName();
    private static final String KEY_PROFILE = "profile";
    private static final String SCRAMBLED = "****";
    private static final String EMPTY = "";
    private static final String DEFAULT_SIP_PORT = "5060";
    private static final String DEFAULT_PROTOCOL = "UDP";
    private static final String GET_METHOD_PREFIX = "get";

    private boolean mAddingProfile;

    enum PreferenceKey {
        ProfileName(R.string.profile_name, 0, EMPTY),
        DomainAddress(R.string.domain_address, 1, EMPTY),
        Username(R.string.username, 2, EMPTY),
        Password(R.string.password, 3, EMPTY),
        DisplayName(R.string.display_name, 4, EMPTY),
        ProxyAddress(R.string.proxy_address, 5, EMPTY),
        Port(R.string.port, 6, DEFAULT_SIP_PORT),
        Transport(R.string.transport, 7, DEFAULT_PROTOCOL),
        SendKeepAlive(R.string.send_keepalive, 8, EMPTY),
        AutoRegistration(R.string.auto_registration, 9, EMPTY);

        /**
         * @param key The key name of the preference.
         * @param index The index of the preference in the view.
         * @param defaultValue The default value of the preference.
         */
        PreferenceKey(int text, int index, String defaultValue) {
            this.text = text;
            this.index = index;
            this.defaultValue = defaultValue;
        }

        public final int text;
        public final int index;
        public final String defaultValue;
    }

    private Preference[] mPreferences =
            new Preference[PreferenceKey.values().length];

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "start profile editor");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sip_settings_ui);
        addPreferencesFromResource(R.xml.sip_edit);
        final SipProfile p = (SipProfile) ((savedInstanceState == null)
                ? getIntent().getParcelableExtra(SipSettings.KEY_SIP_PROFILE)
                : savedInstanceState.getParcelable(KEY_PROFILE));

        for (PreferenceKey key : PreferenceKey.values()) {
            mPreferences[key.index] = setupPreference(getString(key.text));
        }
        if (p == null) {
            findViewById(R.id.add_remove_account_bar)
                    .setVisibility(View.GONE);
        } else {
            Button removeButton =
                    (Button)findViewById(R.id.add_remove_account_button);
            removeButton.setText(getString(R.string.remove_sip_account));
            removeButton.setOnClickListener(
                    new android.view.View.OnClickListener() {
                        public void onClick(View v) {
                            setRemovedProfileAndFinish(p);
                        }
                    });
        }
        loadPreferencesFromProfile(p);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_SAVE, 0, R.string.sip_menu_save)
                .setIcon(android.R.drawable.ic_menu_save);
        menu.add(0, MENU_DISCARD, 0, R.string.sip_menu_discard)
                .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SAVE:
                if (validateAndSetResult()) {
                    finish();
                }
                return true;

            case MENU_DISCARD:
                finish();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
                if (validateAndSetResult()) finish();
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setRemovedProfileAndFinish(SipProfile p) {
        try {
            Intent intent = new Intent(this, SipSettings.class);
            intent.putExtra(SipSettings.KEY_SIP_PROFILE, (Parcelable) p);
            setResult(RESULT_FIRST_USER, intent);
            finish();
        } catch (Exception e) {
            showAlert(e.getMessage());
        }
    }

    private void showAlert(String message) {
        new AlertDialog.Builder(this)
                .setTitle(android.R.string.dialog_alert_title)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setMessage(message)
                .setPositiveButton(R.string.alert_dialog_ok,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                            }
                        })
                .show();
    }

    private boolean validateAndSetResult() {
        for(Preference pref : mPreferences)  {
            String value = EMPTY;
            if (pref instanceof ListPreference) {
                value = ((ListPreference)pref).getValue();
            } else if (pref instanceof EditTextPreference) {
                value = ((EditTextPreference)pref).getText();
            } else if (pref instanceof CheckBoxPreference) {
                continue;
            }
            if (TextUtils.isEmpty(value) &&
                    (pref != mPreferences[PreferenceKey.ProxyAddress.index])) {
                showAlert(pref.getTitle() + " "
                        + getString(R.string.empty_alert));
                return false;
            }
        }
        try {
            Intent intent = new Intent(this, SipSettings.class);
            intent.putExtra(SipSettings.KEY_SIP_PROFILE,
                    (Parcelable) createSipProfile());
            setResult(RESULT_OK, intent);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Can not create new SipProfile : " + e.getMessage());
            showAlert(e.getMessage());
            return false;
        }
    }

    private SipProfile createSipProfile() throws Exception {
            return new SipProfile.Builder(
                    getValue(PreferenceKey.Username),
                    getValue(PreferenceKey.DomainAddress))
                    .setProfileName(getValue(PreferenceKey.ProfileName))
                    .setPassword(getValue(PreferenceKey.Password))
                    .setOutboundProxy(getValue(PreferenceKey.ProxyAddress))
                    .setProtocol(getValue(PreferenceKey.Transport))
                    .setDisplayName(getValue(PreferenceKey.DisplayName))
                    .setPort(Integer.parseInt(getValue(PreferenceKey.Port)))
                    .setSendKeepAlive(isChecked(PreferenceKey.SendKeepAlive))
                    .setAutoRegistration(
                            isChecked(PreferenceKey.AutoRegistration))
                    .build();
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref instanceof CheckBoxPreference) return true;
        String value = (String) newValue;
        if (value == null) value = EMPTY;
        if (pref != mPreferences[PreferenceKey.Password.index]) {
            pref.setSummary(value);
        } else {
            pref.setSummary(SCRAMBLED);
        }
        return true;
    }

    private void loadPreferencesFromProfile(SipProfile p) {
        if (p != null) {
            Log.v(TAG, "Edit the existing profile : " + p.getProfileName());
            try {
                Class profileClass = SipProfile.class;
                for (PreferenceKey key : PreferenceKey.values()) {
                    Method meth = profileClass.getMethod(GET_METHOD_PREFIX +
                            getString(key.text), (Class[])null);
                    if (key == PreferenceKey.Port) {
                        setValue(key,
                                String.valueOf(meth.invoke(p, (Object[])null)));
                    } else if (key == PreferenceKey.SendKeepAlive
                            || key == PreferenceKey.AutoRegistration) {
                        setCheckBox(key, ((Boolean)
                                meth.invoke(p, (Object[])null)).booleanValue());
                    } else {
                        setValue(key, (String) meth.invoke(p, (Object[])null));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Can not load pref from profile:" + e.getMessage());
            }
        } else {
            Log.v(TAG, "Edit a new profile");
            for (PreferenceKey key : PreferenceKey.values()) {
                Preference pref = mPreferences[key.index];
                pref.setOnPreferenceChangeListener(this);
                if (pref instanceof EditTextPreference) {
                    ((EditTextPreference)pref).setText(key.defaultValue);
                } else if (pref instanceof ListPreference) {
                    ((ListPreference)pref).setValue(key.defaultValue);
                } else {
                    continue;
                }
                pref.setSummary(EMPTY.equals(key.defaultValue)
                        ? getString(R.string.initial_preference_summary) : key.defaultValue);
            }
        }
    }

    private boolean isChecked(PreferenceKey key) {
        CheckBoxPreference pref = (CheckBoxPreference)mPreferences[key.index];
        return pref.isChecked();
    }

    private String getValue(PreferenceKey key) {
        Preference pref = mPreferences[key.index];
        if (pref instanceof EditTextPreference) {
            return ((EditTextPreference)pref).getText();
        } else if (pref instanceof ListPreference) {
            return ((ListPreference)pref).getValue();
        }
        throw new RuntimeException("getValue() for the preference " + key.text);
    }

    private void setCheckBox(PreferenceKey key, boolean checked) {
        CheckBoxPreference pref = (CheckBoxPreference) mPreferences[key.index];
        pref.setChecked(checked);
    }

    private void setValue(PreferenceKey key, String value) {
        Preference pref = mPreferences[key.index];
        if (pref instanceof EditTextPreference) {
            ((EditTextPreference)pref).setText(value);
        } else if (pref instanceof ListPreference) {
            ((ListPreference)pref).setValue(value);
        }

        if (TextUtils.isEmpty(value)) {
            value = getString(R.string.initial_preference_summary);
        } else {
            if (key == PreferenceKey.Password) value = SCRAMBLED;
        }
        pref.setSummary(value);
    }

    private Preference setupPreference(String key) {
        Preference pref = getPreferenceScreen().findPreference(key);
        pref.setOnPreferenceChangeListener(this);
        return pref;
    }
}
