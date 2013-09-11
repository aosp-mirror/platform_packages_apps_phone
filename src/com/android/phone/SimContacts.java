/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.accounts.Account;
import android.app.ActionBar;
import android.app.ProgressDialog;
import android.content.ContentProviderOperation;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.OperationApplicationException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.GroupMembership;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * SIM Address Book UI for the Phone app.
 */
public class SimContacts extends ADNList {
    private static final String LOG_TAG = "SimContacts";

    private static final String UP_ACTIVITY_PACKAGE = "com.android.contacts";
    private static final String UP_ACTIVITY_CLASS =
            "com.android.contacts.activities.PeopleActivity";

    static final ContentValues sEmptyContentValues = new ContentValues();

    protected static final int MENU_IMPORT_ONE = 1;
    protected static final int MENU_IMPORT_ALL = 2;
    protected static final int MENU_DELETE_ALL = 3;
    protected static final int MENU_ADD_CONTACT = 4;
    protected static final int MENU_EDIT_CONTACT = 5;
    protected static final int MENU_SMS = 6;
    protected static final int MENU_DIAL = 7;
    protected static final int MENU_DELETE = 8;

    private static final int EVENT_CONTACTS_DELETED = 9;

    private ProgressDialog mProgressDialog;

    private Account mAccount;

    private static class NamePhoneTypePair {
        final String name;
        final int phoneType;
        public NamePhoneTypePair(String nameWithPhoneType) {
            // Look for /W /H /M or /O at the end of the name signifying the type
            int nameLen = nameWithPhoneType.length();
            if (nameLen - 2 >= 0 && nameWithPhoneType.charAt(nameLen - 2) == '/') {
                char c = Character.toUpperCase(nameWithPhoneType.charAt(nameLen - 1));
                if (c == 'W') {
                    phoneType = Phone.TYPE_WORK;
                } else if (c == 'M' || c == 'O') {
                    phoneType = Phone.TYPE_MOBILE;
                } else if (c == 'H') {
                    phoneType = Phone.TYPE_HOME;
                } else {
                    phoneType = Phone.TYPE_OTHER;
                }
                name = nameWithPhoneType.substring(0, nameLen - 2);
            } else {
                phoneType = Phone.TYPE_OTHER;
                name = nameWithPhoneType;
            }
        }
    }

    private class ImportAllSimContactsThread extends Thread
            implements OnCancelListener, OnClickListener {

        boolean mCanceled = false;

        public ImportAllSimContactsThread() {
            super("ImportAllSimContactsThread");
        }

        @Override
        public void run() {
            final ContentValues emptyContentValues = new ContentValues();
            final ContentResolver resolver = getContentResolver();

            mCursor.moveToPosition(-1);
            while (!mCanceled && mCursor.moveToNext()) {
                actuallyImportOneSimContact(mCursor, resolver, mAccount);
                mProgressDialog.incrementProgressBy(1);
            }

            mProgressDialog.dismiss();
            finish();
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_NEGATIVE) {
                mCanceled = true;
                mProgressDialog.dismiss();
            } else {
                Log.e(LOG_TAG, "Unknown button event has come: " + dialog.toString());
            }
        }
    }

    private static void actuallyImportOneSimContact(
            final Cursor cursor, final ContentResolver resolver, Account account) {
        final NamePhoneTypePair namePhoneTypePair =
            new NamePhoneTypePair(cursor.getString(NAME_COLUMN));
        final String name = namePhoneTypePair.name;
        final int phoneType = namePhoneTypePair.phoneType;
        final String phoneNumber = cursor.getString(NUMBER_COLUMN);
        final String emailAddresses = cursor.getString(EMAILS_COLUMN);
        final String[] emailAddressArray;
        if (!TextUtils.isEmpty(emailAddresses)) {
            emailAddressArray = emailAddresses.split(",");
        } else {
            emailAddressArray = null;
        }

        final ArrayList<ContentProviderOperation> operationList =
            new ArrayList<ContentProviderOperation>();
        ContentProviderOperation.Builder builder =
            ContentProviderOperation.newInsert(RawContacts.CONTENT_URI);
        String myGroupsId = null;
        if (account != null) {
            builder.withValue(RawContacts.ACCOUNT_NAME, account.name);
            builder.withValue(RawContacts.ACCOUNT_TYPE, account.type);
        } else {
            builder.withValues(sEmptyContentValues);
        }
        operationList.add(builder.build());

        builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
        builder.withValueBackReference(StructuredName.RAW_CONTACT_ID, 0);
        builder.withValue(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        builder.withValue(StructuredName.DISPLAY_NAME, name);
        operationList.add(builder.build());

        builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
        builder.withValueBackReference(Phone.RAW_CONTACT_ID, 0);
        builder.withValue(Data.MIMETYPE, Phone.CONTENT_ITEM_TYPE);
        builder.withValue(Phone.TYPE, phoneType);
        builder.withValue(Phone.NUMBER, phoneNumber);
        builder.withValue(Data.IS_PRIMARY, 1);
        operationList.add(builder.build());

        if (emailAddresses != null) {
            for (String emailAddress : emailAddressArray) {
                builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
                builder.withValueBackReference(Email.RAW_CONTACT_ID, 0);
                builder.withValue(Data.MIMETYPE, Email.CONTENT_ITEM_TYPE);
                builder.withValue(Email.TYPE, Email.TYPE_MOBILE);
                builder.withValue(Email.DATA, emailAddress);
                operationList.add(builder.build());
            }
        }

        if (myGroupsId != null) {
            builder = ContentProviderOperation.newInsert(Data.CONTENT_URI);
            builder.withValueBackReference(GroupMembership.RAW_CONTACT_ID, 0);
            builder.withValue(Data.MIMETYPE, GroupMembership.CONTENT_ITEM_TYPE);
            builder.withValue(GroupMembership.GROUP_SOURCE_ID, myGroupsId);
            operationList.add(builder.build());
        }

        try {
            resolver.applyBatch(ContactsContract.AUTHORITY, operationList);
        } catch (RemoteException e) {
            Log.e(LOG_TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        } catch (OperationApplicationException e) {
            Log.e(LOG_TAG, String.format("%s: %s", e.toString(), e.getMessage()));
        }
    }

    private void importOneSimContact(int position) {
        final ContentResolver resolver = getContentResolver();
        if (mCursor.moveToPosition(position)) {
            actuallyImportOneSimContact(mCursor, resolver, mAccount);
        } else {
            Log.e(LOG_TAG, "Failed to move the cursor to the position \"" + position + "\"");
        }
    }

    /* Followings are overridden methods */

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        Intent intent = getIntent();
        if (intent != null) {
            final String accountName = intent.getStringExtra("account_name");
            final String accountType = intent.getStringExtra("account_type");
            if (!TextUtils.isEmpty(accountName) && !TextUtils.isEmpty(accountType)) {
                mAccount = new Account(accountName, accountType);
            }
        }

        registerForContextMenu(getListView());

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            // android.R.id.home will be triggered in onOptionsItemSelected()
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
    }

    @Override
    protected CursorAdapter newAdapter() {
        return new SimpleCursorAdapter(this, R.layout.sim_import_list_entry, mCursor,
                new String[] { "name" }, new int[] { android.R.id.text1 });
    }

    @Override
    protected Uri resolveIntent() {
        Intent intent = getIntent();
        intent.setData(Uri.parse("content://icc/adn"));
        if (Intent.ACTION_PICK.equals(intent.getAction())) {
            // "index" is 1-based
            mInitialSelection = intent.getIntExtra("index", 0) - 1;
        }
        return intent.getData();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_IMPORT_ALL, 0, R.string.importAllSimEntries);
        menu.add(0, MENU_DELETE_ALL, 0, R.string.deleteAllSimEntries);
        menu.add(0, MENU_ADD_CONTACT, 0, R.string.addSimEntries);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        MenuItem item = menu.findItem(MENU_IMPORT_ALL);
        if (item != null) {
            item.setVisible(mCursor != null && mCursor.getCount() > 0);
        }
        item = menu.findItem(MENU_DELETE_ALL);
        if (item != null) {
            item.setVisible(mCursor != null && mCursor.getCount() > 0);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        CharSequence title, message;
        switch (item.getItemId()) {
            case android.R.id.home:
                Intent intent = new Intent();
                intent.setClassName(UP_ACTIVITY_PACKAGE, UP_ACTIVITY_CLASS);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(intent);
                finish();
                return true;
            case MENU_DELETE_ALL:
                title = getString(R.string.deleteAllSimEntries);
                message = getString(R.string.deleteSimContacts);
                deleteAllSimContactsThread deleteThread = new deleteAllSimContactsThread();
                if (mCursor == null) {
                    showAlertDialog(getString(R.string.cursorError));
                    break;
                }
                prepareProgressDialog(title, message);
                mProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                        getString(R.string.cancel), deleteThread);
                mProgressDialog.show();
                deleteThread.start();
                return true;
            case MENU_ADD_CONTACT:
                showContactScreen(null, null, 1);
                return true;
            case MENU_IMPORT_ALL:
                title = getString(R.string.importAllSimEntries);
                message = getString(R.string.importingSimContacts);

                ImportAllSimContactsThread thread = new ImportAllSimContactsThread();

                // TODO: need to show some error dialog.
                if (mCursor == null) {
                    Log.e(LOG_TAG, "cursor is null. Ignore silently.");
                    break;
                }
                prepareProgressDialog(title, message);
                mProgressDialog.setButton(DialogInterface.BUTTON_NEGATIVE,
                        getString(R.string.cancel), thread);
                mProgressDialog.show();

                thread.start();

                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    void prepareProgressDialog(CharSequence title, CharSequence message) {
        mProgressDialog = new ProgressDialog(this);
        mProgressDialog.setTitle(title);
        mProgressDialog.setMessage(message);
        mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        mProgressDialog.setProgress(0);
        mProgressDialog.setMax(mCursor.getCount());
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position;
        ContextMenu.ContextMenuInfo menuInfo = item.getMenuInfo();
        if (menuInfo instanceof AdapterView.AdapterContextMenuInfo) {
            position = ((AdapterView.AdapterContextMenuInfo)menuInfo).position;
        } else {
            // seems to be some problem
            return super.onContextItemSelected(item);
        }
        switch (item.getItemId()) {
            case MENU_IMPORT_ONE:
                importOneSimContact(position);
                return true;
            case MENU_EDIT_CONTACT:
                editOneSimContact(position);
                return true;
            case MENU_SMS:
                smsToNumber(position);
                return true;
            case MENU_DIAL:
                dialNumber(position);
                return true;
            case MENU_DELETE:
                deleteOneSimContact(position);
                return true;
        }
        return super.onContextItemSelected(item);
    }

    private void smsToNumber(int position) {
        if (mCursor.moveToPosition(position)) {
            String phoneNumber = mCursor.getString(NUMBER_COLUMN);
            phoneNumber = PhoneNumberUtils.formatNumber(phoneNumber);
            Intent intent = new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts(Constants.SCHEME_SMSTO, phoneNumber, null));
            startActivity(intent);
            finish();
        } else {
            showAlertDialog(getString(R.string.cursorError));
        }
    }

    private void dialNumber(int position) {
        if (mCursor.moveToPosition(position)) {
            String phoneNumber = mCursor.getString(NUMBER_COLUMN);
            if (phoneNumber == null || !TextUtils.isGraphic(phoneNumber)) {
                Log.e(LOG_TAG, " There is no number in contact ...");
            }
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                    Uri.fromParts(Constants.SCHEME_TEL, phoneNumber, null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
            startActivity(intent);
            finish();
        } else {
            showAlertDialog(getString(R.string.cursorError));
        }
    }

    private class deleteAllSimContactsThread extends Thread
            implements OnCancelListener, OnClickListener {

        boolean mCanceled = false;

        public deleteAllSimContactsThread() {
            super("deleteAllSimContactsThread");
        }

        @Override
        public void run() {
            int result = 1;
            mCursor.moveToPosition(-1);
            while (!mCanceled && mCursor.moveToNext()) {
                result = result & actuallyDeleteOneSimContact(mCursor);
                mProgressDialog.incrementProgressBy(1);
            }

            mProgressDialog.dismiss();
            Message message = Message.obtain(mHandler, EVENT_CONTACTS_DELETED, (Integer)result);
            mHandler.sendMessage(message);
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public void onClick(DialogInterface dialog, int which) {
            if (which == DialogInterface.BUTTON_NEGATIVE) {
                mCanceled = true;
                mProgressDialog.dismiss();
            } else {
                Log.e(LOG_TAG, "Unknown button event has come: " + dialog.toString());
            }
        }
    }

    private void deleteOneSimContact(int position) {
        if (mCursor.moveToPosition(position)) {
            final NamePhoneTypePair namePhoneTypePair =
                    new NamePhoneTypePair(mCursor.getString(NAME_COLUMN));
            final String name = namePhoneTypePair.name;
            final int phoneType = namePhoneTypePair.phoneType;
            final String phoneNumber = mCursor.getString(NUMBER_COLUMN);

            Uri uri = getUri();
            if (uri == null) {
                Log.e(LOG_TAG, "deleteOneSimContact: uri is null, return!!!");
                return;
            }
            mQueryHandler.startDelete(DELETE_TOKEN, null, uri, "tag=" + name
                        + " AND number=" + phoneNumber, null);
            displayProgress(true);
        } else {
            showAlertDialog(getString(R.string.cursorError));
        }
    }

    private int actuallyDeleteOneSimContact(Cursor cursor){
        final NamePhoneTypePair namePhoneTypePair =
                new NamePhoneTypePair(cursor.getString(NAME_COLUMN));
        final String name = namePhoneTypePair.name;
        final int phoneType = namePhoneTypePair.phoneType;
        final String phoneNumber = cursor.getString(NUMBER_COLUMN);

        Uri uri = getUri();
        int result = -1;
        if (uri != null) {
            result = getContentResolver().delete(uri, "tag=" + name
                    + " AND number=" + phoneNumber, null);
        } else {
            Log.e(LOG_TAG, "actuallyDeleteOneSimContact: uri is null!!!");
        }
        return result;
    }

    private void editOneSimContact(int position) {
        if (mCursor.moveToPosition(position)) {
            final NamePhoneTypePair namePhoneTypePair =
                    new NamePhoneTypePair(mCursor.getString(NAME_COLUMN));
            final String name = namePhoneTypePair.name;
            final int phoneType = namePhoneTypePair.phoneType;
            final String phoneNumber = mCursor.getString(NUMBER_COLUMN);
            showContactScreen(name, phoneNumber, 2);
        } else {
            showAlertDialog(getString(R.string.cursorError));
        }
    }

    private void showContactScreen(String name, String phoneNumber, int requestCode) {
        Intent intent = new Intent();
        intent.setClassName("com.android.phone", "com.android.phone.ContactScreenActivity");
        intent.putExtra("NAME", name);
        intent.putExtra("PHONE", phoneNumber);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode,
            final Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        Uri uri = getUri();
        if (uri == null) {
            Log.e(LOG_TAG, "onActivityResult: uri is null, return!!!");
            return;
        }
        ContentValues values = new ContentValues();

        if (resultCode == RESULT_OK && requestCode == 1){
            String name = intent.getStringExtra("NEWNAME");
            String number = intent.getStringExtra("NEWPHONE");
            values.put("tag", name);
            values.put("number", number);
            mQueryHandler.startInsert(INSERT_TOKEN, null, uri, values);
            displayProgress(true);
        } else if (resultCode == RESULT_OK && requestCode == 2) {
            String oldName = intent.getStringExtra("NAME");
            String oldNumber = intent.getStringExtra("PHONE");
            String newName = intent.getStringExtra("NEWNAME");
            String newNumber = intent.getStringExtra("NEWPHONE");
            values.put("tag", oldName);
            values.put("number", oldNumber);
            values.put("newTag", newName);
            values.put("newNumber", newNumber);
            mQueryHandler.startUpdate(UPDATE_TOKEN, null, uri, values, null, null);
            displayProgress(true);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        if (menuInfo instanceof AdapterView.AdapterContextMenuInfo) {
            AdapterView.AdapterContextMenuInfo itemInfo =
                    (AdapterView.AdapterContextMenuInfo) menuInfo;
            TextView textView = (TextView) itemInfo.targetView.findViewById(android.R.id.text1);
            if (textView != null) {
                menu.setHeaderTitle(textView.getText());
            }
            menu.add(0, MENU_IMPORT_ONE, 0, R.string.importSimEntry);
            menu.add(0, MENU_EDIT_CONTACT, 0, R.string.editContact);
            menu.add(0, MENU_SMS, 0, R.string.sendSms);
            menu.add(0, MENU_DIAL, 0, R.string.dial);
            menu.add(0, MENU_DELETE, 0, R.string.delete);
        }
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        importOneSimContact(position);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                if (mCursor != null && mCursor.moveToPosition(getSelectedItemPosition())) {
                    String phoneNumber = mCursor.getString(NUMBER_COLUMN);
                    if (phoneNumber == null || !TextUtils.isGraphic(phoneNumber)) {
                        // There is no number entered.
                        //TODO play error sound or something...
                        return true;
                    }
                    Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                            Uri.fromParts(Constants.SCHEME_TEL, phoneNumber, null));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                          | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
                    startActivity(intent);
                    finish();
                    return true;
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_CONTACTS_DELETED:
                    reQuery();
                    int result = (Integer)msg.obj;
                    if (result == 1) {
                        showAlertDialog(getString(R.string.allContactdeleteSuccess));
                    } else {
                        showAlertDialog(getString(R.string.allContactdeleteFailed));
                    }
                    break;
            }
        }
    };

    protected Uri getUri() {
        return Uri.parse("content://icc/adn");
    }
}
