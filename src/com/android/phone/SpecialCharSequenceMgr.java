/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Telephony.Intents;
import com.android.internal.telephony.PhoneFactory;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.WindowManager;

/**
 * Helper class to listen for some magic character sequences
 * that are handled specially by the Phone app.
 */
public class SpecialCharSequenceMgr {
    private static final String TAG = PhoneApp.LOG_TAG;
    private static final boolean DBG = false;

    private static final String MMI_IMEI_DISPLAY = "*#06#";

    /** This class is never instantiated. */
    private SpecialCharSequenceMgr() {
    }

    static boolean handleChars(Context context, String input) {
        return handleChars(context, input, false, null);
    }
    
    /**
     * Generally used for the PUK unlocking case, where we
     * want to be able to maintain a handle to the calling
     * activity so that we can close it or otherwise display
     * indication if the PUK code is recognized.
     * 
     * NOTE: The counterpart to this file in Contacts does
     * NOT contain the special PUK handling code, since it
     * does NOT need it.  When the device gets into PUK-
     * locked state, the keyguard comes up and the only way
     * to unlock the device is through the Emergency dialer,
     * which is still in the Phone App.
     */
    static boolean handleChars(Context context, String input, Activity pukInputActivity) {
        return handleChars(context, input, false, pukInputActivity);
    }

    static boolean handleChars(Context context, String input, boolean useSystemWindow) {
        return handleChars(context, input, useSystemWindow, null);
    }

    /**
     * Check for special strings of digits from an input 
     * string.
     * 
     * @param context input Context for the events we handle
     * @param input the dial string to be examined
     * @param useSystemWindow used for the IMEI event to
     * determine display behaviour.
     * @param pukInputActivity activity that originated this
     * PUK call, tracked so that we can close it or otherwise 
     * indicate that special character sequence is 
     * successfully processed. 
     */
    static boolean handleChars(Context context,
                               String input, 
                               boolean useSystemWindow,
                               Activity pukInputActivity) {
        
        //get rid of the separators so that the string gets parsed correctly 
        String dialString = PhoneNumberUtils.stripSeparators(input);
        
        if (handleIMEIDisplay(context, dialString, useSystemWindow)
            || handlePinEntry(context, dialString, pukInputActivity)
            || handleAdnEntry(context, dialString)
            || handleSecretCode(context, dialString)) {
            return true;
        }
        
        return false;
    }

    /**
     * Handles secret codes to launch arbitrary activities in the form of *#*#<code>#*#*.
     * If a secret code is encountered an Intent is started with the android_secret_code://<code>
     * URI.
     * 
     * @param context the context to use
     * @param input the text to check for a secret code in
     * @return true if a secret code was encountered
     */
    static boolean handleSecretCode(Context context, String input) {
        // Secret codes are in the form *#*#<code>#*#*
        int len = input.length();
        if (len > 8 && input.startsWith("*#*#") && input.endsWith("#*#*")) {
            Intent intent = new Intent(Intents.SECRET_CODE_ACTION,
                    Uri.parse("android_secret_code://" + input.substring(4, len - 4)));
            context.sendBroadcast(intent);
            return true;
        }

        return false;
    }

    static boolean handleAdnEntry(Context context, String input) {
        /* ADN entries are of the form "N(N)(N)#" */
        
        // if the phone is keyguard-restricted, then just ignore this
        // input.  We want to make sure that sim card contacts are NOT
        // exposed unless the phone is unlocked, and this code can be
        // accessed from the emergency dialer.
        if (PhoneApp.getInstance().getKeyguardManager().inKeyguardRestrictedInputMode()) {
            return false;
        }
        
        int len = input.length();
        if ((len > 1) && (len < 5) && (input.endsWith("#"))) {
            try {
                int index = Integer.parseInt(input.substring(0, len-1));
                Intent intent = new Intent(Intent.ACTION_PICK);

                intent.setClassName("com.android.phone",
                                    "com.android.phone.SimContacts");
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.putExtra("index", index);
                PhoneApp.getInstance().startActivity(intent);

                return true;
            } catch (NumberFormatException ex) {}
        }
        return false;
    }

    static boolean handlePinEntry(Context context, String input, Activity pukInputActivity) {
        // TODO: The string constants here should be removed in favor of some call to a 
        // static the MmiCode class that determines if a dialstring is an MMI code.
        if ((input.startsWith("**04") || input.startsWith("**05")) 
                && input.endsWith("#")) {
            PhoneApp app = PhoneApp.getInstance();
            boolean isMMIHandled = app.phone.handlePinMmi(input);
            
            // if the PUK code is recognized then indicate to the
            // phone app that an attempt to unPUK the device was
            // made with this activity.  The PUK code may still
            // fail though, but we won't know until the MMI code
            // returns a result.
            if (isMMIHandled && input.startsWith("**05")) {
                app.setPukEntryActivity(pukInputActivity);
            }
            return isMMIHandled;
        }
        return false;
    }

    static boolean handleIMEIDisplay(Context context,
                                     String input, boolean useSystemWindow) {
        if (input.equals(MMI_IMEI_DISPLAY)) {
            showIMEIPanel(context, useSystemWindow);
            return true;
        }

        return false;
    }

    static void showIMEIPanel(Context context, boolean useSystemWindow) {
        if (DBG) log("showIMEIPanel");

        String imeiStr = PhoneFactory.getDefaultPhone().getDeviceId();

        AlertDialog alert = new AlertDialog.Builder(context)
                .setTitle(R.string.imei)
                .setMessage(imeiStr)
                .setPositiveButton(R.string.ok, null)
                .setCancelable(false)
                .show();
        alert.getWindow().setType(WindowManager.LayoutParams.TYPE_PRIORITY_PHONE);
    }

    private static void log(String msg) {
        Log.d(TAG, "[SpecialCharSequenceMgr] " + msg);
    }
}
