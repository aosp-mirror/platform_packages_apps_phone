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

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;

/**
 * Wrapper for SIP's shared preferences.
 */
public class SipSharedPreferences {
    private static final String SIP_SHARED_PREFERENCES = "SIP_PREFERENCES";
    private static final String KEY_PRIMARY_ACCOUNT = "primary";

    private SharedPreferences mPreferences;
    private Context mContext;

    public SipSharedPreferences(Context context) {
        mPreferences = context.getSharedPreferences(
                SIP_SHARED_PREFERENCES, Context.MODE_WORLD_READABLE);
        mContext = context;
    }

    public void setPrimaryAccount(String accountUri) {
        SharedPreferences.Editor editor = mPreferences.edit();
        editor.putString(KEY_PRIMARY_ACCOUNT, accountUri);
        editor.apply();
    }

    /** Returns the primary account URI or null if it does not exist. */
    public String getPrimaryAccount() {
        return mPreferences.getString(KEY_PRIMARY_ACCOUNT, null);
    }

    public void setSipCallOption(String option) {
        Settings.System.putString(mContext.getContentResolver(),
                Settings.System.SIP_CALL_OPTIONS, option);
    }

    public String getSipCallOption() {
        String option = Settings.System.getString(mContext.getContentResolver(),
                Settings.System.SIP_CALL_OPTIONS);
        return (option != null) ? option
                                : mContext.getString(R.string.sip_address_only);
    }

    public void setReceivingCallsEnabled(boolean enabled) {
        Settings.System.putInt(mContext.getContentResolver(),
                Settings.System.SIP_RECEIVE_CALLS, (enabled ? 1 : 0));
    }

    public boolean isReceivingCallsEnabled() {
        try {
            return (Settings.System.getInt(mContext.getContentResolver(),
                    Settings.System.SIP_RECEIVE_CALLS) != 0);
        } catch (SettingNotFoundException e) {
            Log.e("SIP", "receive_incoming_call option is not set", e);
            return false;
        }
    }

    // TODO: back up to Android Backup
    // TODO: add System settings here
}
