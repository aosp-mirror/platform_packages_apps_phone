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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.ContactsContract;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.Window;
import android.widget.TextView;

import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.provider.ContactsContract.CommonDataKinds.Phone;

import static android.view.Window.PROGRESS_VISIBILITY_OFF;
import static android.view.Window.PROGRESS_VISIBILITY_ON;

public class ExportContactsToSim extends Activity {
    private static final String TAG = "ExportContactsToSim";
    private static final int FAILURE = 0;
    private static final int SUCCESS = 1;
    private static final int NO_CONTACTS = 2;
    private TextView mEmptyText;
    private int mResult = SUCCESS;
    protected boolean mIsForeground = false;
    private boolean mSimContactsLoaded = false;

    private static final int CONTACTS_EXPORTED = 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().requestFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.export_contact_screen);
        mEmptyText = (TextView) findViewById(android.R.id.empty);
        doExportToSim();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mIsForeground = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mIsForeground = false;
    }

    private void doExportToSim() {

        displayProgress(true);

        new Thread(new Runnable() {
            public void run() {
                Cursor contactsCursor = getContactsContentCursor();
                if ((contactsCursor.getCount()) < 1) {
                    // If there are no contacts in Phone book display it to user.
                    mResult = NO_CONTACTS;
                } else {
                    //We need to load SIM Records atleast once before exporting to SIM.
                    if (!mSimContactsLoaded) {
                        getContentResolver().query(getUri(), null, null,null, null);
                        mSimContactsLoaded = true;
                    }

                    for (int i = 0; contactsCursor.moveToNext(); i++) {
                        populateContactDataFromCursor(contactsCursor );
                    }
                }
                contactsCursor.close();
                Message message = Message.obtain(mHandler, CONTACTS_EXPORTED, (Integer)mResult);
                mHandler.sendMessage(message);
            }
        }).start();
    }

    private Cursor getContactsContentCursor() {
        Uri phoneBookContentUri = Phone.CONTENT_URI;
        String selection = ContactsContract.Contacts.HAS_PHONE_NUMBER +
                "='1' AND (account_type is NULL OR account_type !=?)";
        String[] selectionArg = new String[] {"SIM"};

        Cursor contactsCursor = getContentResolver().query(phoneBookContentUri, null, selection,
                selectionArg, null);
        return contactsCursor;
    }

    private void populateContactDataFromCursor(final Cursor dataCursor) {
        Uri uri = getUri();
        if (uri == null) {
            Log.d(TAG," populateContactDataFromCursor: uri is null, return ");
            return;
        }
        Uri contactUri;
        int nameIdx = dataCursor
                .getColumnIndex(ContactsContract.Data.DISPLAY_NAME);
        int phoneIdx = dataCursor
                .getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);

        // Extract the name.
        String name = dataCursor.getString(nameIdx);
        // Extract the phone number.
        String rawNumber = dataCursor.getString(phoneIdx);
        String number = PhoneNumberUtils.normalizeNumber(rawNumber);

        ContentValues values = new ContentValues();
        values.put("tag", name);
        values.put("number", number);
        Log.d("ExportContactsToSim", "name : " + name + " number : " + number);
        contactUri = getContentResolver().insert(uri, values);
        if (contactUri == null) {
            Log.e("ExportContactsToSim", "Failed to export contact to SIM for " +
                    "name : " + name + " number : " + number);
            mResult = FAILURE;
        }
    }

    private void showAlertDialog(String value) {
        if (!mIsForeground) {
            Log.d(TAG, "The activitiy is not in foreground. Do not display dialog!!!");
            return;
        }
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Result...");
        alertDialog.setMessage(value);
        alertDialog.setCanceledOnTouchOutside(false);
        alertDialog.setButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // finish ExportContacts activity
                finish();
            }
        });
        alertDialog.show();
    }

    private void displayProgress(boolean loading) {
        mEmptyText.setText(R.string.exportContacts);
        getWindow().setFeatureInt(
                Window.FEATURE_INDETERMINATE_PROGRESS,
                loading ? PROGRESS_VISIBILITY_ON : PROGRESS_VISIBILITY_OFF);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case CONTACTS_EXPORTED:
                    int result = (Integer)msg.obj;
                    if (result == SUCCESS) {
                        showAlertDialog(getString(R.string.exportAllcontatsSuccess));
                    } else if (result == NO_CONTACTS) {
                        showAlertDialog(getString(R.string.exportAllcontatsNoContacts));
                    } else {
                        showAlertDialog(getString(R.string.exportAllcontatsFailed));
                    }
                    break;
            }
        }
    };

    private Uri getUri() {
        return Uri.parse("content://icc/adn");
    }
}
