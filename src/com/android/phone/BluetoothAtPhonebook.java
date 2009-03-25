/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.bluetooth.AtCommandHandler;
import android.bluetooth.AtCommandResult;
import android.bluetooth.AtParser;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog.Calls;
import android.provider.Contacts.Phones;
import android.provider.Contacts.PhonesColumns;
import android.telephony.PhoneNumberUtils;
import android.text.TextUtils;
import android.util.Log;

import java.util.HashMap;

/**
 * Helper for managing phonebook presentation over AT commands
 * @hide
 */
public class BluetoothAtPhonebook {
    private static final String TAG = "BtAtPhonebook";
    private static final boolean DBG = false;
    
    /** The projection to use when querying the call log database in response
     *  to AT+CPBR for the MC, RC, and DC phone books (missed, received, and
     *   dialed calls respectively)
     */
    private static final String[] CALLS_PROJECTION = new String[] {
        Calls._ID, Calls.NUMBER
    };

    /** The projection to use when querying the contacts database in response
     *   to AT+CPBR for the ME phonebook (saved phone numbers).
     */
    private static final String[] PHONES_PROJECTION = new String[] {
        Phones._ID, Phones.NAME, Phones.NUMBER, Phones.TYPE
    };

    /** The projection to use when querying the contacts database in response
     *  to AT+CNUM for the ME phonebook (saved phone numbers).  We need only
     *   the phone numbers here and the phone type.
     */
    private static final String[] PHONES_LITE_PROJECTION = new String[] {
        Phones._ID, Phones.NUMBER, Phones.TYPE
    };

    /** Android supports as many phonebook entries as the flash can hold, but
     *  BT periphals don't. Limit the number we'll report. */
    private static final int MAX_PHONEBOOK_SIZE = 16384;

    private static final String OUTGOING_CALL_WHERE = Calls.TYPE + "=" + Calls.OUTGOING_TYPE;
    private static final String INCOMING_CALL_WHERE = Calls.TYPE + "=" + Calls.INCOMING_TYPE;
    private static final String MISSED_CALL_WHERE = Calls.TYPE + "=" + Calls.MISSED_TYPE;

    private class PhonebookResult {
        public Cursor  cursor; // result set of last query
        public int     numberColumn;
        public int     typeColumn;
        public int     nameColumn;
    };

    private final Context mContext;
    private final BluetoothHandsfree mHandsfree;

    private String mCurrentPhonebook;

    private final HashMap<String, PhonebookResult> mPhonebooks =
            new HashMap<String, PhonebookResult>(4);

    public BluetoothAtPhonebook(Context context, BluetoothHandsfree handsfree) {
        mContext = context;
        mHandsfree = handsfree;
        mPhonebooks.put("DC", new PhonebookResult());  // dialled calls
        mPhonebooks.put("RC", new PhonebookResult());  // received calls
        mPhonebooks.put("MC", new PhonebookResult());  // missed calls
        mPhonebooks.put("ME", new PhonebookResult());  // mobile phonebook

        mCurrentPhonebook = "ME";  // default to mobile phonebook
    }

    /** Returns the last dialled number, or null if no numbers have been called */
    public String getLastDialledNumber() {
        String[] projection = {Calls.NUMBER};
        Cursor cursor = mContext.getContentResolver().query(Calls.CONTENT_URI, projection,
                Calls.TYPE + "=" + Calls.OUTGOING_TYPE, null, Calls.DEFAULT_SORT_ORDER +
                " LIMIT 1");
        if (cursor.getCount() < 1) {
            cursor.close();
            return null;
        }
        cursor.moveToNext();
        int column = cursor.getColumnIndexOrThrow(Calls.NUMBER);
        String number = cursor.getString(column);
        cursor.close();
        return number;
    }
    
    public void register(AtParser parser) {
        // Select Character Set
        // We support IRA and GSM (although we behave the same for both)
        parser.register("+CSCS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CSCS: \"IRA\"");
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args.length < 1) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
                if (((String)args[0]).equals("\"GSM\"") || ((String)args[0]).equals("\"IRA\"")) {
                    return new AtCommandResult(AtCommandResult.OK);
                } else {
                    return mHandsfree.reportCmeError(BluetoothCmeError.OPERATION_NOT_SUPPORTED);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult( "+CSCS: (\"IRA\",\"GSM\")");
            }
        });

        // Select PhoneBook memory Storage
        parser.register("+CPBS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                // Return current size and max size
                if ("SM".equals(mCurrentPhonebook)) {
                    return new AtCommandResult("+CPBS: \"SM\",0," + MAX_PHONEBOOK_SIZE);
                }
                    
                PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, true);
                if (pbr == null) {
                    return mHandsfree.reportCmeError(BluetoothCmeError.OPERATION_NOT_ALLOWED);
                }
                return new AtCommandResult("+CPBS: \"" + mCurrentPhonebook + "\"," +
                        pbr.cursor.getCount() + "," + MAX_PHONEBOOK_SIZE);
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // Select phonebook memory
                if (args.length < 1 || !(args[0] instanceof String)) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
                String pb = ((String)args[0]).trim();
                while (pb.endsWith("\"")) pb = pb.substring(0, pb.length() - 1);
                while (pb.startsWith("\"")) pb = pb.substring(1, pb.length());
                if (getPhonebookResult(pb, false) == null && !"SM".equals(pb)) {
                    if (DBG) log("Dont know phonebook: '" + pb + "'");
                    return mHandsfree.reportCmeError(BluetoothCmeError.OPERATION_NOT_SUPPORTED);
                }
                mCurrentPhonebook = pb;
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CPBS: (\"ME\",\"SM\",\"DC\",\"RC\",\"MC\")");
            }
        });

        // Read PhoneBook Entries
        parser.register("+CPBR", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // Phone Book Read Request
                // AT+CPBR=<index1>[,<index2>]

                // Parse indexes
                int index1;
                int index2;
                if (args.length < 1 || !(args[0] instanceof Integer)) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                } else {
                    index1 = (Integer)args[0];
                }

                if (args.length == 1) {
                    index2 = index1;
                } else if (!(args[1] instanceof Integer)) {
                    return mHandsfree.reportCmeError(BluetoothCmeError.TEXT_HAS_INVALID_CHARS);
                } else {
                    index2 = (Integer)args[1];
                }

                // Shortcut SM phonebook
                if ("SM".equals(mCurrentPhonebook)) {
                    return new AtCommandResult(AtCommandResult.OK);
                }

                // Check phonebook
                PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, false);
                if (pbr == null) {
                    return mHandsfree.reportCmeError(BluetoothCmeError.OPERATION_NOT_ALLOWED);
                }

                // More sanity checks
                if (index1 <= 0 || index2 < index1 || index2 > pbr.cursor.getCount()) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }

                // Process
                AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
                int errorDetected = -1; // no error
                pbr.cursor.moveToPosition(index1 - 1);
                for (int index = index1; index <= index2; index++) {
                    String number = pbr.cursor.getString(pbr.numberColumn);
                    String name = null;
                    int type = -1;
                    if (pbr.nameColumn == -1) {
                        // try caller id lookup
                        // TODO: This code is horribly inefficient. I saw it
                        // take 7 seconds to process 100 missed calls.
                        Cursor c = mContext.getContentResolver().query(
                                Uri.withAppendedPath(Phones.CONTENT_FILTER_URL, number),
                                new String[] {Phones.NAME, Phones.TYPE}, null, null, null);
                        if (c != null) {
                            if (c.moveToFirst()) {
                                name = c.getString(0);
                                type = c.getInt(1);
                            }
                            c.close();
                        }
                        if (DBG && name == null) log("Caller ID lookup failed for " + number);

                    } else {
                        name = pbr.cursor.getString(pbr.nameColumn);
                    }
                    if (name == null) name = "";
                    name = name.trim();
                    if (name.length() > 28) name = name.substring(0, 28);

                    if (pbr.typeColumn != -1) {
                        type = pbr.cursor.getInt(pbr.typeColumn);
                        name = name + "/" + getPhoneType(type);
                    }

                    int regionType = PhoneNumberUtils.toaFromString(number);

                    number = number.trim();
                    number = PhoneNumberUtils.stripSeparators(number);
                    if (number.length() > 30) number = number.substring(0, 30);
                    if (number.equals("-1")) {
                        // unknown numbers are stored as -1 in our database
                        number = "";
                        name = "unknown";
                    }

                    result.addResponse("+CPBR: " + index + ",\"" + number + "\"," +
                                       regionType + ",\"" + name + "\"");
                    if (!pbr.cursor.moveToNext()) {
                        break;
                    }
                }
                return result;
            }
            @Override
            public AtCommandResult handleTestCommand() {
                /* Ideally we should return the maximum range of valid index's
                 * for the selected phone book, but this causes problems for the
                 * Parrot CK3300. So instead send just the range of currently
                 * valid index's.
                 */
                int size;
                if ("SM".equals(mCurrentPhonebook)) {
                    size = 0;
                } else {
                    PhonebookResult pbr = getPhonebookResult(mCurrentPhonebook, false);
                    if (pbr == null) {
                        return mHandsfree.reportCmeError(BluetoothCmeError.OPERATION_NOT_ALLOWED);
                    }
                    size = pbr.cursor.getCount();
                }

                if (size == 0) {
                    /* Sending "+CPBR: (1-0)" can confused some carkits, send "1-1"
                     * instead */
                    size = 1;
                }
                return new AtCommandResult("+CPBR: (1-" + size + "),30,30");
            }
        });
    }

    /** Get the most recent result for the given phone book,
     *  with the cursor ready to go.
     *  If force then re-query that phonebook
     *  Returns null if the cursor is not ready
     */
    private synchronized PhonebookResult getPhonebookResult(String pb, boolean force) {
        if (pb == null) {
            return null;
        }
        PhonebookResult pbr = mPhonebooks.get(pb);
        if (pbr == null) {
            pbr = new PhonebookResult();
        }
        if (force || pbr.cursor == null) {
            if (!queryPhonebook(pb, pbr)) {
                return null;
            }
        }

        if (pbr.cursor == null) {
            return null;
        }

        return pbr;
    }

    private synchronized boolean queryPhonebook(String pb, PhonebookResult pbr) {
        String where;
        boolean ancillaryPhonebook = true;

        if (pb.equals("ME")) {
            ancillaryPhonebook = false;
            where = null;
        } else if (pb.equals("DC")) {
            where = OUTGOING_CALL_WHERE;
        } else if (pb.equals("RC")) {
            where = INCOMING_CALL_WHERE;
        } else if (pb.equals("MC")) {
            where = MISSED_CALL_WHERE;
        } else {
            return false;
        }

        if (pbr.cursor != null) {
            pbr.cursor.close();
            pbr.cursor = null;
        }

        if (ancillaryPhonebook) {
            pbr.cursor = mContext.getContentResolver().query(
                    Calls.CONTENT_URI, CALLS_PROJECTION, where, null,
                    Calls.DEFAULT_SORT_ORDER + " LIMIT " + MAX_PHONEBOOK_SIZE);
            pbr.numberColumn = pbr.cursor.getColumnIndexOrThrow(Calls.NUMBER);
            pbr.typeColumn = -1;
            pbr.nameColumn = -1;
        } else {
            pbr.cursor = mContext.getContentResolver().query(
                    Phones.CONTENT_URI, PHONES_PROJECTION, where, null,
                    Phones.DEFAULT_SORT_ORDER + " LIMIT " + MAX_PHONEBOOK_SIZE);
            pbr.numberColumn = pbr.cursor.getColumnIndex(Phones.NUMBER);
            pbr.typeColumn = pbr.cursor.getColumnIndex(Phones.TYPE);
            pbr.nameColumn = pbr.cursor.getColumnIndex(Phones.NAME);
        }
        Log.i(TAG, "Refreshed phonebook " + pb + " with " + pbr.cursor.getCount() + " results");
        return true;
    }

    private static String getPhoneType(int type) {
        switch (type) {
            case PhonesColumns.TYPE_HOME:
                return "H";
            case PhonesColumns.TYPE_MOBILE:
                return "M";
            case PhonesColumns.TYPE_WORK:
                return "W";
            case PhonesColumns.TYPE_FAX_HOME:
            case PhonesColumns.TYPE_FAX_WORK:
                return "F";
            case PhonesColumns.TYPE_OTHER:
            case PhonesColumns.TYPE_CUSTOM:
            default:
                return "O";
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
