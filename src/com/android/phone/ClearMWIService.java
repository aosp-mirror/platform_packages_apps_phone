/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.app.IntentService;
import android.content.Intent;

/**
 * Handles the intent to clear the voicemails that is triggered when a notification is dismissed.
 */
public class ClearMWIService extends IntentService {
    /** This action is used to clear voicemails. */
    public static final String ACTION_CLEAR_VOICEMAILS =
            "com.android.phone.intent.CLEAR_VOICEMAILS";

    private PhoneGlobals mApp;

    public ClearMWIService() {
        super(ClearMWIService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mApp = PhoneGlobals.getInstance();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (ACTION_CLEAR_VOICEMAILS.equals(intent.getAction())) {
            mApp.notificationMgr.cancelMWINotification();
        }
    }
}
