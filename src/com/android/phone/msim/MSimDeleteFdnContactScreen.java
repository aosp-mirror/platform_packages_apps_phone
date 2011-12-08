/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;
import static com.android.internal.telephony.MSimConstants.SUB1;
import static com.android.internal.telephony.MSimConstants.SUB2;

/**
 * Activity to let the user delete an FDN contact.
 */
public class MSimDeleteFdnContactScreen extends DeleteFdnContactScreen {
    private static final String LOG_TAG = "MSimDeleteFdnContactScreen";
    private static final boolean DBG = false;

    private static int mSubscription = 0;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
    }

    @Override
    protected void resolveIntent() {
        Intent intent = getIntent();
        mName =  intent.getStringExtra(INTENT_EXTRA_NAME);
        mNumber =  intent.getStringExtra(INTENT_EXTRA_NUMBER);
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, 0);
        if (TextUtils.isEmpty(mNumber)) {
            finish();
        }
    }

    @Override
    protected void deleteContact() {
        StringBuilder buf = new StringBuilder();
        Uri uri = null;

        if (TextUtils.isEmpty(mName)) {
            buf.append("number='");
        } else {
            buf.append("tag='");
            buf.append(mName);
            buf.append("' AND number='");
        }
        buf.append(mNumber);
        buf.append("' AND pin2='");
        buf.append(mPin2);
        buf.append("'");

        if (mSubscription == SUB1) {
            uri = Uri.parse("content://iccmsim/fdn");
        } else if (mSubscription == SUB2) {
            uri = Uri.parse("content://iccmsim/fdn_sub2");
        } else {
            // we should never reach here.
            if (DBG) log("invalid mSubscription") ;
            return;
        }

        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startDelete(0, null, uri, buf.toString(), null);
        displayProgress(true);
    }

    @Override
    protected void log(String msg) {
        Log.d(LOG_TAG, "[MSimDeleteFdnContact] " + msg);
    }
}
