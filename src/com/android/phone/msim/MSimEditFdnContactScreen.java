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

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.text.TextUtils;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;
import static com.android.internal.telephony.MSimConstants.SUB1;
import static com.android.internal.telephony.MSimConstants.SUB2;

/**
 * Activity to let the user add or edit an FDN contact.
 */
public class MSimEditFdnContactScreen extends EditFdnContactScreen {
    private static final String LOG_TAG = "MSimEditFdnContactScreen";
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
        mAddContact = TextUtils.isEmpty(mNumber);
    }

    @Override
    protected Uri getContentURI() {
        if (mSubscription == SUB1) {
            return Uri.parse("content://iccmsim/fdn");
        } else if (mSubscription == SUB2) {
            return Uri.parse("content://iccmsim/fdn_sub2");
        } else {
            // we should never reach here.
            if (DBG) log("invalid mSubscription");
            return null;
        }
    }

    @Override
    protected void addContact() {
        if (DBG) log("addContact");

        if (!isValidNumber(getNumberFromTextField())) {
            handleResult(false, true);
            return;
        }

        Uri uri = getContentURI();

        ContentValues bundle = new ContentValues(4);
        bundle.put("tag", getNameFromTextField());
        bundle.put("number", getNumberFromTextField());
        bundle.put("pin2", mPin2);
        bundle.put(SUBSCRIPTION_KEY, mSubscription);

        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startInsert(0, null, uri, bundle);
        displayProgress(true);
        showStatus(getResources().getText(R.string.adding_fdn_contact));
    }

    @Override
    protected void updateContact() {
        if (DBG) log("updateContact");

        if (!isValidNumber(getNumberFromTextField())) {
            handleResult(false, true);
            return;
        }
        Uri uri = getContentURI();

        ContentValues bundle = new ContentValues();
        bundle.put("tag", mName);
        bundle.put("number", mNumber);
        bundle.put("newTag", getNameFromTextField());
        bundle.put("newNumber", getNumberFromTextField());
        bundle.put("pin2", mPin2);
        bundle.put(SUBSCRIPTION_KEY, mSubscription);

        mQueryHandler = new QueryHandler(getContentResolver());
        mQueryHandler.startUpdate(0, null, uri, bundle, null, null);
        displayProgress(true);
        showStatus(getResources().getText(R.string.updating_fdn_contact));
    }

    /**
     * Handle the delete command, based upon the state of the Activity.
     */
    @Override
    protected void deleteSelected() {
        // delete ONLY if this is NOT a new contact.
        if (!mAddContact) {
            Intent intent = new Intent();
            intent.setClass(this, MSimDeleteFdnContactScreen.class);
            intent.putExtra(INTENT_EXTRA_NAME, mName);
            intent.putExtra(INTENT_EXTRA_NUMBER, mNumber);
            intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
            startActivity(intent);
        }
        finish();
    }

    @Override
    protected void log(String msg) {
        Log.d(LOG_TAG, "[MSimEditFdnContact] " + msg);
    }
}
