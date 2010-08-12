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

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipRegistrationListener;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.Button;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sip.SipException;

/**
 * The PreferenceActivity class for managing sip profile preferences.
 */
public class SipSettings extends PreferenceActivity {
    public static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";
    public static final String AUTOREG_FLAG = "AUTOREG";
    public static final String SIP_CALL_FIRST_FLAG = "SIPFIRST";
    public static final String PROFILES_DIR = "/profiles/";

    static final String KEY_SIP_PROFILE = "sip_profile";
    static final String PROFILE_OBJ_FILE = ".pobj";

    private static final String PREF_AUTO_REG = "auto_reg";
    private static final String PREF_SIP_CALL_FIRST = "sip_call_first";
    private static final String PREF_SIP_LIST = "sip_account_list";
    private static final String TAG = "SipSettings";
    private static final String REGISTERED = "REGISTERED";
    private static final String UNREGISTERED = "NOT REGISTERED";

    private static final int REQUEST_ADD_OR_EDIT_SIP_PROFILE = 1;

    private static final int CONTEXT_MENU_REGISTER_ID = ContextMenu.FIRST;
    private static final int CONTEXT_MENU_UNREGISTER_ID = ContextMenu.FIRST + 1;
    private static final int CONTEXT_MENU_EDIT_ID = ContextMenu.FIRST + 2;
    private static final int CONTEXT_MENU_DELETE_ID = ContextMenu.FIRST + 3;
    private static final int EXPIRY_TIME = 600;

    private SipManager mSipManager;

    private String mProfilesDirectory;

    private SipProfile mProfile;

    private PreferenceCategory mSipListContainer;
    private Map<String, SipPreference> mSipPreferenceMap;
    private List<SipProfile> mSipProfileList;
    private SharedPreferences.Editor   mSettingsEditor;

    private class SipPreference extends Preference {
        SipProfile mProfile;
        SipPreference(Context c, SipProfile p) {
            super(c);
            setProfile(p);
        }

        void setProfile(SipProfile p) {
            mProfile = p;
            setTitle(p.getProfileName());
            try {
                setSummary(mSipManager.isRegistered(p.getUriString())
                        ? REGISTERED : UNREGISTERED);
            } catch (SipException e) {
                Log.e(TAG, "Error!setProfileSummary:", e);
            }
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.sip_settings_ui);
        addPreferencesFromResource(R.xml.sip_setting);
        mProfilesDirectory = getFilesDir().getAbsolutePath() + PROFILES_DIR;
        mSipListContainer = (PreferenceCategory) findPreference(PREF_SIP_LIST);

        registerForAddSipListener();

        // for long-press gesture on a profile preference
        registerForContextMenu(getListView());

        registerForGlobalSettingsListener();

        updateProfilesStatus();
    }

    private void registerForAddSipListener() {
        ((Button) findViewById(R.id.add_remove_account_button))
                .setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(View v) {
                        startSipEditor(null);
                    }
                });
    }

    public interface ClickEventCallback {
        public void handle(boolean enabled);
    }

    private class AutoRegistrationClickHandler implements ClickEventCallback {
        public void handle(boolean enabled) {
            registerEnabledProfiles(enabled);
        }
    }

    private void registerForGlobalSettingsListener() {
        mSettingsEditor = getSharedPreferences(
                SIP_SHARED_PREFERENCES, Context.MODE_WORLD_READABLE).edit();
        setCheckBoxClickEventListener(PREF_AUTO_REG,
                AUTOREG_FLAG, new AutoRegistrationClickHandler());
        setCheckBoxClickEventListener(PREF_SIP_CALL_FIRST,
                SIP_CALL_FIRST_FLAG, null);
    }

    private void setCheckBoxClickEventListener(String preference,
            final String flag, final ClickEventCallback clickEvent) {
        ((CheckBoxPreference) findPreference(preference))
                .setOnPreferenceClickListener(
                new OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference preference) {
                        boolean enabled =
                                ((CheckBoxPreference) preference).isChecked();
                        mSettingsEditor.putBoolean(flag, enabled);
                        mSettingsEditor.commit();
                        if (clickEvent != null) clickEvent.handle(enabled);
                        return true;
                    }
                });
    }

    private void updateProfilesStatus() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    mSipManager = SipManager.getInstance(SipSettings.this);
                    retrieveSipListFromStorage();
                } catch (Exception e) {
                    Log.e(TAG, "isRegistered", e);
                }
            }
        }).start();
    }

    public static List<SipProfile> retrieveSipListFromDirectory(
            String directory) {
        List<SipProfile> sipProfileList = Collections.synchronizedList(
                new ArrayList<SipProfile>());

        File root = new File(directory);
        String[] dirs = root.list();
        if (dirs == null) return sipProfileList;
        for (String dir : dirs) {
            File f = new File(
                    new File(root, dir), SipSettings.PROFILE_OBJ_FILE);
            if (!f.exists()) continue;
            try {
                SipProfile p = SipSettings.deserialize(f);
                if (p == null) continue;
                if (!dir.equals(p.getProfileName())) continue;

                sipProfileList.add(p);
            } catch (IOException e) {
                Log.e(TAG, "retrieveProfileListFromStorage()", e);
            }
        }
        Collections.sort(sipProfileList, new Comparator<SipProfile>() {
            public int compare(SipProfile p1, SipProfile p2) {
                return p1.getProfileName().compareTo(p2.getProfileName());
            }

            public boolean equals(SipProfile p) {
                // not used
                return false;
            }
        });
        return sipProfileList;
    }

    private void retrieveSipListFromStorage() {

        mSipPreferenceMap = new LinkedHashMap<String, SipPreference>();
        mSipProfileList = retrieveSipListFromDirectory(mProfilesDirectory);
        mSipListContainer.removeAll();

        for (SipProfile p : mSipProfileList) {
            addPreferenceFor(p, true);
        }
    }

    private void registerEnabledProfiles(boolean enabled) {
        try {
            for (SipProfile p : mSipProfileList) {
                if (p.getAutoRegistration() == false) continue;
                if (enabled) {
                    if (!mSipManager.isRegistered(p.getUriString())) {
                        registerProfile(p);
                    }
                } else {
                    unRegisterProfile(p);
                }
            }
        } catch (SipException e) {
            Log.e(TAG, "Error!registerEnabledProfiles():", e);
        }
    }

    private void addPreferenceFor(SipProfile p, boolean addToContainer)
            {
        String status;
        try {
            Log.v(TAG, "addPreferenceFor profile uri" + p.getUri());
            status = mSipManager.isRegistered(p.getUriString())
                    ? REGISTERED : UNREGISTERED;
        } catch (Exception e) {
            Log.e(TAG, "Cannot get status of profile" + p.getProfileName(), e);
            return;
        }
        SipPreference pref = new SipPreference(this, p);
        mSipPreferenceMap.put(p.getUriString(), pref);
        if (addToContainer) mSipListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        startSipEditor(((SipPreference) pref).mProfile);
                        return true;
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterForContextMenu(getListView());
    }

    private SipProfile getProfile(int position) {
        return ((position >= 0) ? mSipProfileList.get(position) : null);
    }

    private int getProfilePositionFrom(AdapterContextMenuInfo menuInfo) {
        return menuInfo.position - mSipListContainer.getOrder() - 1;
    }

    private void registerProfile(SipProfile profile) {
        if (profile != null) {
            try {
                mSipManager.open(profile, SipManager.SIP_INCOMING_CALL_ACTION,
                        createRegistrationListener());
            } catch (Exception e) {
                Log.e(TAG, "register failed", e);
            }
        }
    }

    private void unRegisterProfile(SipProfile profile) {
        if (profile != null) {
            try {
                mSipManager.close(profile.getUriString());
                setProfileSummary(profile, UNREGISTERED);
            } catch (Exception e) {
                Log.e(TAG, "unregister failed:" + profile.getUriString(), e);
            }
        }
    }

    // TODO: Use the Util class in settings.vpn instead
    private void deleteProfile(String name) {
        deleteProfile(new File(name));
    }

    private void deleteProfile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) deleteProfile(child);
        }
        file.delete();
    }

    void deleteProfile(SipProfile p) {
        mSipProfileList.remove(p);
        SipPreference pref = mSipPreferenceMap.remove(p.getUriString());
        mSipListContainer.removePreference(pref);
        deleteProfile(mProfilesDirectory + p.getProfileName());
        unRegisterProfile(p);
    }

    private void saveProfileToStorage(SipProfile p) throws IOException {
        if (mProfile != null) deleteProfile(mProfile);
        File f = new File(mProfilesDirectory + p.getProfileName());
        if (!f.exists()) f.mkdirs();
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
                new File(f, PROFILE_OBJ_FILE)));
        oos.writeObject(p);
        oos.close();
        mSipProfileList.add(p);
        addPreferenceFor(p, true);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent intent) {
        if (resultCode != RESULT_OK && resultCode != RESULT_FIRST_USER) return;
        new Thread() {
            public void run() {
            try {
                SipProfile profile = intent.getParcelableExtra(KEY_SIP_PROFILE);
                if (resultCode == RESULT_OK) {
                    Log.v(TAG, "New Profile Name:" + profile.getProfileName());
                    saveProfileToStorage(profile);
                    if (((CheckBoxPreference) findPreference
                            (PREF_AUTO_REG)).isChecked() &&
                            profile.getAutoRegistration() == true) {
                        registerProfile(profile);
                    }
                } else {
                    Log.v(TAG, "Removed Profile Name:" + profile.getProfileName());
                    deleteProfile(profile);
                }
            } catch (IOException e) {
                Log.v(TAG, "Can not handle the profile : " + e.getMessage());
            }
        }}.start();
    }

    static SipProfile deserialize(File profileObjectFile) throws IOException {
        try {
            ObjectInputStream ois = new ObjectInputStream(new FileInputStream(
                    profileObjectFile));
            SipProfile p = (SipProfile) ois.readObject();
            ois.close();
            return p;
        } catch (ClassNotFoundException e) {
            Log.d(TAG, "deserialize a profile", e);
            return null;
        }
    }

    private void startSipEditor(final SipProfile profile) {
        mProfile = profile;
        Intent intent = new Intent(this, SipEditor.class);
        intent.putExtra(KEY_SIP_PROFILE, (Parcelable) profile);
        startActivityForResult(intent, REQUEST_ADD_OR_EDIT_SIP_PROFILE);
    }

    private void setProfileSummary(SipProfile profile, String message) {
        setProfileSummary(profile.getUriString(), message);
    }

    private void setProfileSummary(final String profileUri,
            final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    SipPreference pref = mSipPreferenceMap.get(profileUri);
                    if (pref != null) {
                        pref.setSummary(message);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "setSessionSummary failed:" + e);
                }
            }
        });
    }

    private SipRegistrationListener createRegistrationListener() {
        return new SipRegistrationListener() {
            public void onRegistrationDone(String profileUri, long expiryTime) {
                setProfileSummary(profileUri,
                        (expiryTime <= 0) ? UNREGISTERED : REGISTERED);
            }

            public void onRegistrationFailed(String profileUri,
                    String className, String message) {
                setProfileSummary(profileUri, "Registration error: " + message);
            }

            public void onRegistering(String profileUri) {
                setProfileSummary(profileUri, "Registering...");
            }
        };
    }
}
