/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Intent;
import android.net.Uri;
import android.telephony.MSimTelephonyManager;
import android.util.Log;

import static com.android.internal.telephony.MSimConstants.SUB1;
import static com.android.internal.telephony.MSimConstants.SUB2;

/**
 * SIM Address Book UI for the Phone app.
 */
public class MSimContacts extends SimContacts {
    private static final String LOG_TAG = "MSimContacts";

    protected int mSubscription = 0;

    @Override
    protected Uri resolveIntent() {
        Intent intent = getIntent();
        mSubscription = MSimTelephonyManager.getDefault().getPreferredVoiceSubscription();
        if (mSubscription == SUB1) {
            intent.setData(Uri.parse("content://iccmsim/adn"));
        } else if (mSubscription == SUB2) {
            intent.setData(Uri.parse("content://iccmsim/adn_sub2"));
        } else {
            Log.d(TAG, "resolveIntent:Invalid subcription");
        }

        if (Intent.ACTION_PICK.equals(intent.getAction())) {
            // "index" is 1-based
            mInitialSelection = intent.getIntExtra("index", 0) - 1;
        } else if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            mInitialSelection = 0;
        }
        return intent.getData();
    }

}
