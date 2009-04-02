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

import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface.OnClickListener;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Contacts;
import android.provider.Contacts.People;
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

/**
 * SIM Address Book UI for the Phone app.
 */
public class SimContacts extends ADNList {
    private static final int MENU_IMPORT_ONE = 1;
    private static final int MENU_IMPORT_ALL = 2;
    private ProgressDialog mProgressDialog;

    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        registerForContextMenu(getListView());
    }

    private class ImportAllThread extends Thread implements OnCancelListener, OnClickListener {
        boolean mCanceled = false;
        
        public ImportAllThread() {
            super("ImportAllThread");
        }
        
        @Override
        public void run() {
            ContentValues map = new ContentValues();
            ContentResolver cr = getContentResolver();
            Object[] parsed = new Object[2];
            
            mCursor.moveToPosition(-1);
            while (!mCanceled && mCursor.moveToNext()) {
                String name = mCursor.getString(0);
                String number = mCursor.getString(1);

                Uri personUrl = parseName(name, parsed);

                if (personUrl == null) {
                    map.clear();
                    map.put(Contacts.People.NAME, (String) parsed[0]);
                    personUrl = People.createPersonInMyContactsGroup(cr, map);
                    if (personUrl == null) {
                        Log.e(TAG, "Error inserting person " + map);
                        continue;
                    }
                }

                map.clear();
                map.put(Contacts.People.Phones.NUMBER, number);
                map.put(Contacts.People.Phones.TYPE, (Integer) parsed[1]);
                Uri numberUrl = cr.insert(
                        Uri.withAppendedPath(personUrl, Contacts.People.Phones.CONTENT_DIRECTORY),
                        map);
                
                mProgressDialog.incrementProgressBy(1);
                if (numberUrl == null) {
                    Log.e(TAG, "Error inserting phone " + map + " for person " +
                            personUrl + ", removing person");
                    continue;
                }
            }
            
            mProgressDialog.dismiss();

            finish();
        }

        public void onCancel(DialogInterface dialog) {
            mCanceled = true;
        }

        public void onClick(DialogInterface dialog, int which) {
            mCanceled = true;
            mProgressDialog.dismiss();
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
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_IMPORT_ALL:
                CharSequence title = getString(R.string.importAllSimEntries);
                CharSequence message = getString(R.string.importingSimContacts); 

                ImportAllThread thread = new ImportAllThread();

                mProgressDialog = new ProgressDialog(this);
                mProgressDialog.setTitle(title);
                mProgressDialog.setMessage(message);
                mProgressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                mProgressDialog.setButton(getString(R.string.cancel), thread);
                mProgressDialog.setProgress(0);
                mProgressDialog.setMax(mCursor.getCount());
                mProgressDialog.show();
                
                thread.start();
                
                return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_IMPORT_ONE:
                ContextMenu.ContextMenuInfo menuInfo = item.getMenuInfo();
                if (menuInfo instanceof AdapterView.AdapterContextMenuInfo) {
                    int position = ((AdapterView.AdapterContextMenuInfo)menuInfo).position;
                    importOne(position);
                    return true;
                }
        }
        return super.onContextItemSelected(item);
    }

    
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
        }
    }

    @Override
    public void onListItemClick(ListView l, View v, int position, long id) {
        importOne(position);
    }

    private void importOne(int position) {
        if (mCursor.moveToPosition(position)) {
            String name = mCursor.getString(NAME_COLUMN);
            String number = mCursor.getString(NUMBER_COLUMN);
            Object[] parsed = new Object[2];
            Uri personUrl = parseName(name, parsed);

            Intent intent;
            if (personUrl == null) {
                // Add a new contact
                intent = new Intent(Contacts.Intents.Insert.ACTION,
                        Contacts.People.CONTENT_URI);
                intent.putExtra(Contacts.Intents.Insert.NAME, (String)parsed[0]);
                intent.putExtra(Contacts.Intents.Insert.PHONE, number);
                intent.putExtra(Contacts.Intents.Insert.PHONE_TYPE, ((Integer)parsed[1]).intValue());
            } else {
                // Add the number to an existing contact
                intent = new Intent(Intent.ACTION_EDIT, personUrl);
                intent.putExtra(Contacts.Intents.Insert.PHONE, number);
                intent.putExtra(Contacts.Intents.Insert.PHONE_TYPE, ((Integer)parsed[1]).intValue());
            }
            startActivity(intent);
        }
    }

    /**
     * Parse the name looking for /W /H /M or /O at the end, signifying the type.
     *
     * @param name The name from the SIM card
     * @param parsed slot 0 is filled in with the name, and slot 1 is filled
     * in with the type
     */
    private Uri parseName(String name, Object[] parsed) {
        // default to TYPE_MOBILE so you can send SMSs to the numbers
        int type = Contacts.PhonesColumns.TYPE_MOBILE;

        // Look for /W /H /M or /O at the end of the name signifying the type
        int nameLen = name.length();
        if (nameLen - 2 >= 0 && name.charAt(nameLen - 2) == '/') {
            char c = Character.toUpperCase(name.charAt(nameLen - 1));
            if (c == 'W') {
                type = Contacts.PhonesColumns.TYPE_WORK;
            } else if (c == 'M') {
                type = Contacts.PhonesColumns.TYPE_MOBILE;
            } else if (c == 'H') {
                type = Contacts.PhonesColumns.TYPE_HOME;
            } else if (c == 'O') {
                type = Contacts.PhonesColumns.TYPE_MOBILE;
            }
            name = name.substring(0, nameLen - 2);
        }
        parsed[0] = name;
        parsed[1] = type;

        StringBuilder where = new StringBuilder(Contacts.People.NAME);
        where.append('=');
        DatabaseUtils.appendEscapedSQLString(where, name);
        Uri url = null;
        Cursor c = getContentResolver().query(Contacts.People.CONTENT_URI,
                new String[] {Contacts.People._ID},
                where.toString(), null, null);
        if (c != null) {
            if (c.moveToFirst()) {
                url = ContentUris.withAppendedId(Contacts.People.CONTENT_URI, c.getLong(0));
            }
            c.deactivate();
        }
        return url;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                if (mCursor != null && mCursor.moveToPosition(getSelectedItemPosition())) {
                    String number = mCursor.getString(NUMBER_COLUMN);
                    if (number == null || !TextUtils.isGraphic(number)) {
                        // There is no number entered.
                        //TODO play error sound or something...
                        return true;
                    }
                    Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                            Uri.fromParts("tel", number, null));
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
}
