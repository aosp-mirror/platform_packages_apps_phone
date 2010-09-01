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
import android.content.SharedPreferences;
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
import java.util.ArrayList;
import java.util.List;

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
    private static final String LEAVE_AS_IS = null;
    private static final String DEFAULT_SIP_PORT = "5060";
    private static final String DEFAULT_PROTOCOL = "UDP";
    private static final String GET_METHOD_PREFIX = "get";

    private PrimaryAccountSelector mPrimaryAccountSelector;
    private AdvancedSettings mAdvancedSettings;
    private SipSharedPreferences mSharedPreferences =
            new SipSharedPreferences(this);

    enum PreferenceKey {
        ProfileName(R.string.profile_name, EMPTY),
        DomainAddress(R.string.domain_address, EMPTY),
        Username(R.string.username, EMPTY),
        Password(R.string.password, EMPTY),
        DisplayName(R.string.display_name, EMPTY),
        ProxyAddress(R.string.proxy_address, EMPTY),
        Port(R.string.port, DEFAULT_SIP_PORT),
        Transport(R.string.transport, DEFAULT_PROTOCOL),
        SendKeepAlive(R.string.send_keepalive, LEAVE_AS_IS);

        /**
         * @param key The key name of the preference.
         * @param defaultValue The default value of the preference.
         */
        PreferenceKey(int text, String defaultValue) {
            this.text = text;
            this.defaultValue = defaultValue;
        }

        final int text;
        final String defaultValue;
        Preference preference;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Log.v(TAG, "start profile editor");
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sip_settings_ui);
        addPreferencesFromResource(R.xml.sip_edit);

        final SipProfile p = (SipProfile) ((savedInstanceState == null)
                ? getIntent().getParcelableExtra(SipSettings.KEY_SIP_PROFILE)
                : savedInstanceState.getParcelable(KEY_PROFILE));

        PreferenceGroup screen = (PreferenceGroup) getPreferenceScreen();
        for (int i = 0, n = screen.getPreferenceCount(); i < n; i++) {
            setupPreference(screen.getPreference(i));
        }

        if (p == null) {
            findViewById(R.id.add_remove_account_bar)
                    .setVisibility(View.GONE);
            screen.setTitle(R.string.sip_edit_new_title);
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
        mAdvancedSettings = new AdvancedSettings();
        mPrimaryAccountSelector = new PrimaryAccountSelector(p);

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
        for (PreferenceKey key : PreferenceKey.values()) {
            Preference pref = key.preference;
            String value = EMPTY;
            if (pref instanceof ListPreference) {
                value = ((ListPreference)pref).getValue();
            } else if (pref instanceof EditTextPreference) {
                value = ((EditTextPreference)pref).getText();
            } else if (pref instanceof CheckBoxPreference) {
                continue;
            }
            if (TextUtils.isEmpty(value) &&
                    (key != PreferenceKey.ProxyAddress)) {
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
                            mSharedPreferences.isReceivingCallsEnabled())
                    .build();
    }

    public boolean onPreferenceChange(Preference pref, Object newValue) {
        if (pref instanceof CheckBoxPreference) return true;
        String value = (String) newValue;
        if (value == null) value = EMPTY;
        if (pref == PreferenceKey.Password.preference) {
            pref.setSummary(SCRAMBLED);
        } else {
            pref.setSummary(value);
        }
        return true;
    }

    private void loadPreferencesFromProfile(SipProfile p) {
        if (p != null) {
            Log.v(TAG, "Edit the existing profile : " + p.getProfileName());
            try {
                Class profileClass = SipProfile.class;
                for (PreferenceKey key : PreferenceKey.values()) {
                    Method meth = profileClass.getMethod(GET_METHOD_PREFIX
                            + getString(key.text), (Class[])null);
                    if (key == PreferenceKey.Port) {
                        setValue(key,
                                String.valueOf(meth.invoke(p, (Object[])null)));
                    } else if (key == PreferenceKey.SendKeepAlive) {
                        boolean value = ((Boolean)
                                meth.invoke(p, (Object[]) null)).booleanValue();
                        setValue(key, getString(value
                                ? R.string.sip_always_send_keepalive
                                : R.string.sip_system_decide));
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
                if (key.defaultValue == LEAVE_AS_IS) continue;
                Preference pref = key.preference;
                pref.setOnPreferenceChangeListener(this);
                if (pref instanceof EditTextPreference) {
                    ((EditTextPreference) pref).setText(key.defaultValue);
                } else if (pref instanceof ListPreference) {
                    ((ListPreference) pref).setValue(key.defaultValue);
                } else {
                    continue;
                }
                pref.setSummary((EMPTY == key.defaultValue)
                        ? getString(R.string.initial_preference_summary)
                        : key.defaultValue);
            }
        }
    }

    private boolean isChecked(PreferenceKey key) {
        // must be PreferenceKey.SendKeepAlive
        ListPreference pref = (ListPreference) key.preference;
        return getString(R.string.sip_always_send_keepalive).equals(
                pref.getValue());
    }

    private String getValue(PreferenceKey key) {
        Preference pref = key.preference;
        if (pref instanceof EditTextPreference) {
            return ((EditTextPreference)pref).getText();
        } else if (pref instanceof ListPreference) {
            return ((ListPreference)pref).getValue();
        }
        throw new RuntimeException("getValue() for the preference " + key.text);
    }

    private void setCheckBox(PreferenceKey key, boolean checked) {
        CheckBoxPreference pref = (CheckBoxPreference) key.preference;
        pref.setChecked(checked);
    }

    private void setValue(PreferenceKey key, String value) {
        Preference pref = key.preference;
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

    private void setupPreference(Preference pref) {
        pref.setOnPreferenceChangeListener(this);
        for (PreferenceKey key : PreferenceKey.values()) {
            String name = getString(key.text);
            if (name.equals(pref.getKey())) {
                key.preference = pref;
                return;
            }
        }
    }

    private class PrimaryAccountSelector {
        private CheckBoxPreference mCheckbox;

        // @param profile profile to be edited; null if adding new profile
        PrimaryAccountSelector(SipProfile profile) {
            mCheckbox = (CheckBoxPreference) getPreferenceScreen()
                    .findPreference(getString(R.string.set_primary));
            String primaryAccountUri = mSharedPreferences.getPrimaryAccount();
            boolean noPrimaryAccountSet = TextUtils.isEmpty(primaryAccountUri);

            // make the first added account primary
            mCheckbox.setChecked(noPrimaryAccountSet || ((profile != null)
                    && profile.getUriString().equals(primaryAccountUri)));
        }

        void commit(SipProfile profile) {
            if (mCheckbox.isChecked()) {
                mSharedPreferences.setPrimaryAccount(profile.getUriString());
            }
        }
    }

    private class AdvancedSettings
            implements Preference.OnPreferenceClickListener {
        private Preference mAdvancedSettingsTrigger;
        private Preference[] mPreferences;
        private boolean mShowing = false;

        AdvancedSettings() {
            mAdvancedSettingsTrigger = getPreferenceScreen().findPreference(
                    getString(R.string.advanced_settings));
            mAdvancedSettingsTrigger.setOnPreferenceClickListener(this);

            loadAdvancedPreferences();
        }

        private void loadAdvancedPreferences() {
            PreferenceGroup screen = (PreferenceGroup) getPreferenceScreen();

            addPreferencesFromResource(R.xml.sip_advanced_edit);
            PreferenceGroup group = (PreferenceGroup) screen.findPreference(
                    getString(R.string.advanced_settings_container));
            screen.removePreference(group);

            mPreferences = new Preference[group.getPreferenceCount()];
            int order = screen.getPreferenceCount();
            for (int i = 0, n = mPreferences.length; i < n; i++) {
                Preference pref = group.getPreference(i);
                pref.setOrder(order++);
                setupPreference(pref);
                mPreferences[i] = pref;
            }
        }

        private void show() {
            mShowing = true;
            mAdvancedSettingsTrigger.setSummary(R.string.advanced_settings_hide);
            PreferenceGroup screen = (PreferenceGroup) getPreferenceScreen();
            for (Preference pref : mPreferences) {
                screen.addPreference(pref);
                Log.v(TAG, "add pref " + pref.getKey() + ": order=" + pref.getOrder());
            }
        }

        private void hide() {
            mShowing = false;
            mAdvancedSettingsTrigger.setSummary(R.string.advanced_settings_show);
            PreferenceGroup screen = (PreferenceGroup) getPreferenceScreen();
            for (Preference pref : mPreferences) {
                screen.removePreference(pref);
            }
        }

        public boolean onPreferenceClick(Preference preference) {
            Log.v(TAG, "optional settings clicked");
            if (!mShowing) {
                show();
            } else {
                hide();
            }
            return true;
        }
    }
}
