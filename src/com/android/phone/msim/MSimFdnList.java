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
import android.util.Log;
import android.os.Bundle;

import static com.android.internal.telephony.MSimConstants.SUBSCRIPTION_KEY;
import static com.android.internal.telephony.MSimConstants.SUB1;
import static com.android.internal.telephony.MSimConstants.SUB2;

/**
 * FDN List UI for the Phone app.
 */
public class MSimFdnList extends FdnList {
    private int mSubscription = 0;

    private static final String TAG = "MSimFdnList";
    private static final boolean DBG = false;

    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);
    }

    @Override
    protected Uri resolveIntent() {
        Intent intent = getIntent();
        mSubscription = getIntent().getIntExtra(SUBSCRIPTION_KEY, 0);
        if (mSubscription == SUB1) {
            intent.setData(Uri.parse("content://iccmsim/fdn"));
        } else if (mSubscription == SUB2) {
            intent.setData(Uri.parse("content://iccmsim/fdn_sub2"));
        } else {
            // we should never reach here.
            if (DBG) log("invalid mSubscription");
        }
        return intent.getData();
    }

    @Override
    protected void addContact() {
        // if we don't put extras "name" when starting this activity, then
        // MSimEditFdnContactScreen treats it like add contact.
        Intent intent = new Intent();
        intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
        intent.setClass(this, MSimEditFdnContactScreen.class);
        startActivity(intent);
    }

    /**
     * Edit the item at the selected position in the list.
     */
    @Override
    protected void editSelected(int position) {
        if (mCursor.moveToPosition(position)) {
            String name = mCursor.getString(NAME_COLUMN);
            String number = mCursor.getString(NUMBER_COLUMN);

            Intent intent = new Intent();
            intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
            intent.setClass(this, MSimEditFdnContactScreen.class);
            intent.putExtra(INTENT_EXTRA_NAME, name);
            intent.putExtra(INTENT_EXTRA_NUMBER, number);
            startActivity(intent);
        }
    }

    @Override
    protected void deleteSelected() {
        if (mCursor.moveToPosition(getSelectedItemPosition())) {
            String name = mCursor.getString(NAME_COLUMN);
            String number = mCursor.getString(NUMBER_COLUMN);

            Intent intent = new Intent();
            intent.putExtra(SUBSCRIPTION_KEY, mSubscription);
            intent.setClass(this, MSimDeleteFdnContactScreen.class);
            intent.putExtra(INTENT_EXTRA_NAME, name);
            intent.putExtra(INTENT_EXTRA_NUMBER, number);
            startActivity(intent);
        }
    }

    @Override
    protected void log(String msg) {
        Log.d(TAG, "[MSimFdnList] " + msg);
    }
}
