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
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.sip.SipException;
import android.net.sip.SipErrorCode;
import android.net.sip.SipProfile;
import android.net.sip.SipManager;
import android.net.sip.SipRegistrationListener;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.Process;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.Preference.OnPreferenceClickListener;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
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

/**
 * The PreferenceActivity class for managing sip profile preferences.
 */
public class SipSettings extends PreferenceActivity {
    public static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";
    public static final String PROFILES_DIR = "/profiles/";

    static final String KEY_SIP_PROFILE = "sip_profile";
    static final String PROFILE_OBJ_FILE = ".pobj";

    private static final String PREF_SIP_LIST = "sip_account_list";
    private static final String TAG = "SipSettings";

    private static final int REQUEST_ADD_OR_EDIT_SIP_PROFILE = 1;

    private PackageManager mPackageManager;
    private SipManager mSipManager;

    private String mProfilesDirectory;

    private SipProfile mProfile;

    private PreferenceGroup mSipListContainer;
    private Map<String, SipPreference> mSipPreferenceMap;
    private List<SipProfile> mSipProfileList;
    private SipSharedPreferences mSipSharedPreferences;
    private int mUid = Process.myUid();


    private class SipPreference extends Preference {
        SipProfile mProfile;
        SipPreference(Context c, SipProfile p) {
            super(c);
            setProfile(p);
        }

        SipProfile getProfile() {
            return mProfile;
        }

        void setProfile(SipProfile p) {
            mProfile = p;
            setTitle(p.getProfileName());
            updateSummary(mSipSharedPreferences.isReceivingCallsEnabled()
                    ? getString(R.string.registration_status_checking_status)
                    : getString(R.string.registration_status_not_receiving));
        }

        void updateSummary(String registrationStatus) {
            int profileUid = mProfile.getCallingUid();
            boolean isPrimary = mProfile.getUriString().equals(
                    mSipSharedPreferences.getPrimaryAccount());
            Log.v(TAG, "profile uid is " + profileUid + " isPrimary:"
                    + isPrimary + " registration:" + registrationStatus
                    + " Primary:" + mSipSharedPreferences.getPrimaryAccount()
                    + " status:" + registrationStatus);
            String summary = "";
            if ((profileUid > 0) && (profileUid != mUid)) {
                // from third party apps
                summary = getString(R.string.third_party_account_summary,
                        getPackageNameFromUid(profileUid));
            } else if (isPrimary) {
                summary = getString(R.string.primary_account_summary_with,
                        registrationStatus);
            } else {
                summary = registrationStatus;
            }
            setSummary(summary);
        }
    }

    private String getPackageNameFromUid(int uid) {
        try {
            String[] pkgs = mPackageManager.getPackagesForUid(uid);
            ApplicationInfo ai =
                    mPackageManager.getApplicationInfo(pkgs[0], 0);
            return ai.loadLabel(mPackageManager).toString();
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "cannot find name of uid " + uid, e);
        }
        return "uid:" + uid;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mSipManager = SipManager.getInstance(SipSettings.this);
        mSipSharedPreferences = new SipSharedPreferences(this);
        mPackageManager = getPackageManager();
        setContentView(R.layout.sip_settings_ui);
        addPreferencesFromResource(R.xml.sip_setting);
        mProfilesDirectory = getFilesDir().getAbsolutePath() + PROFILES_DIR;
        mSipListContainer = getPreferenceScreen();
        registerForAddSipListener();

        updateProfilesStatus();
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
                    if (profile.getAutoRegistration()
                            || mSipSharedPreferences.isPrimaryAccount(
                                    profile.getUriString())) {
                        registerProfile(profile);
                    }
                } else {
                    Log.v(TAG, "Removed Profile Name:" + profile.getProfileName());
                    deleteProfile(profile, true);
                }
                updateProfilesStatus();
            } catch (IOException e) {
                Log.v(TAG, "Can not handle the profile : " + e.getMessage());
            }
        }}.start();
    }

    private void registerForAddSipListener() {
        ((Button) findViewById(R.id.add_remove_account_button))
                .setOnClickListener(new android.view.View.OnClickListener() {
                    public void onClick(View v) {
                        startSipEditor(null);
                    }
                });
    }

    private void updateProfilesStatus() {
        new Thread(new Runnable() {
            public void run() {
                try {
                    retrieveSipLists();
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
        return sipProfileList;
    }

    private void retrieveSipLists() {
        mSipPreferenceMap = new LinkedHashMap<String, SipPreference>();
        mSipProfileList = retrieveSipListFromDirectory(mProfilesDirectory);
        processActiveProfilesFromSipService();
        Collections.sort(mSipProfileList, new Comparator<SipProfile>() {
            public int compare(SipProfile p1, SipProfile p2) {
                return p1.getProfileName().compareTo(p2.getProfileName());
            }

            public boolean equals(SipProfile p) {
                // not used
                return false;
            }
        });
        mSipListContainer.removeAll();
        for (SipProfile p : mSipProfileList) {
            addPreferenceFor(p);
            if (mUid == p.getCallingUid()) {
                try {
                    mSipManager.setRegistrationListener(
                            p.getUriString(), createRegistrationListener());
                } catch (SipException e) {
                    Log.e(TAG, "cannot set registration listener", e);
                }
            }
        }
    }

    private void processActiveProfilesFromSipService() {
        SipProfile[] activeList = mSipManager.getListOfProfiles();
        for (SipProfile activeProfile : activeList) {
            SipProfile profile = getProfileFromList(activeProfile);
            if (profile == null) {
                mSipProfileList.add(activeProfile);
            } else {
                profile.setCallingUid(activeProfile.getCallingUid());
            }
        }
    }

    private SipProfile getProfileFromList(SipProfile activeProfile) {
        for (SipProfile p : mSipProfileList) {
            if (p.getUriString().equals(activeProfile.getUriString())) {
                return p;
            }
        }
        return null;
    }

    private void addPreferenceFor(SipProfile p) {
        String status;
        Log.v(TAG, "addPreferenceFor profile uri" + p.getUri());
        SipPreference pref = new SipPreference(this, p);
        mSipPreferenceMap.put(p.getUriString(), pref);
        mSipListContainer.addPreference(pref);

        pref.setOnPreferenceClickListener(
                new Preference.OnPreferenceClickListener() {
                    public boolean onPreferenceClick(Preference pref) {
                        handleProfileClick(((SipPreference) pref).mProfile);
                        return true;
                    }
                });
    }

    private void handleProfileClick(final SipProfile profile) {
        int uid = profile.getCallingUid();
        if (uid == mUid || uid == 0) {
            startSipEditor(profile);
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.alert_dialog_close)
                .setIcon(android.R.drawable.ic_dialog_alert)
                .setPositiveButton(R.string.close_profile,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int w) {
                                deleteProfile(profile, false);
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
            } catch (Exception e) {
                Log.e(TAG, "unregister failed:" + profile.getUriString(), e);
            }
        }
    }

    // TODO: Use the Util class in settings.vpn instead
    public static void deleteProfile(String name) {
        deleteProfile(new File(name));
    }

    private static void deleteProfile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) deleteProfile(child);
        }
        file.delete();
    }

    void deleteProfile(SipProfile p, boolean removeProfile) {
        mSipProfileList.remove(p);
        SipPreference pref = mSipPreferenceMap.remove(p.getUriString());
        mSipListContainer.removePreference(pref);
        if (removeProfile) {
            deleteProfile(mProfilesDirectory + p.getProfileName());
        }
        unRegisterProfile(p);
    }

    public static void saveProfile(String profilesDir, SipProfile p)
            throws IOException {
        File f = new File(profilesDir + p.getProfileName());
        if (!f.exists()) f.mkdirs();
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(
                new File(f, PROFILE_OBJ_FILE)));
        oos.writeObject(p);
        oos.close();
    }

    private void saveProfileToStorage(SipProfile p) throws IOException {
        if (mProfile != null) deleteProfile(mProfile, true);
        saveProfile(mProfilesDirectory, p);
        mSipProfileList.add(p);
        addPreferenceFor(p);
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

    private void showRegistrationMessage(final String profileUri,
            final String message) {
        runOnUiThread(new Runnable() {
            public void run() {
                SipPreference pref = mSipPreferenceMap.get(profileUri);
                if (pref != null) {
                    pref.updateSummary(message);
                }
            }
        });
    }

    private SipRegistrationListener createRegistrationListener() {
        return new SipRegistrationListener() {
            public void onRegistrationDone(String profileUri, long expiryTime) {
                showRegistrationMessage(profileUri, getString(
                        R.string.registration_status_done));
            }

            public void onRegistering(String profileUri) {
                showRegistrationMessage(profileUri, getString(
                        R.string.registration_status_registering));
            }

            public void onRegistrationFailed(String profileUri,
                    String errorCodeString, String message) {
                switch (Enum.valueOf(SipErrorCode.class, errorCodeString)) {
                    case IN_PROGRESS:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_still_trying));
                        break;
                    case INVALID_CREDENTIALS:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_failed, message));
                        break;
                    default:
                        showRegistrationMessage(profileUri, getString(
                                R.string.registration_status_failed_try_later,
                                message));
                }
            }
        };
    }
}
