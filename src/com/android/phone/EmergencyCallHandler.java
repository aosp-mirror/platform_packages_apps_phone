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

import android.app.Activity;
import android.app.Application;
import android.app.ProgressDialog;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.ServiceState;
import android.util.Log;
import android.view.WindowManager;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

/**
 * Helper class used by the InCallScreen to handle certain special
 * cases when making an emergency call.
 *
 * Specifically, if the user tries to dial an emergency number but the
 * radio is off, e.g. if the device is in airplane mode, this class is
 * responsible for turning the radio back on and retrying the call.
 *
 * This class is initially launched using the same intent originally
 * passed to the InCallScreen (presumably an ACTION_CALL_EMERGENCY intent)
 * but with this class explicitly set as the className/component.  Later,
 * we retry the emergency call by firing off that same intent, with the
 * component cleared, and using an integer extra called
 * EMERGENCY_CALL_RETRY_KEY to convey information about the current state.
 */
public class EmergencyCallHandler extends Activity {
    private static final String TAG = "EmergencyCallHandler";
    private static final boolean DBG = true;  // OK to have this on by default

    /** the key used to get the count from our Intent's extra(s) */
    public static final String EMERGENCY_CALL_RETRY_KEY = "emergency_call_retry_count";
    
    /** count indicating an initial attempt at the call should be made. */
    public static final int INITIAL_ATTEMPT = -1;
    
    /** number of times to retry the call and the time spent in between attempts*/
    public static final int NUMBER_OF_RETRIES = 6;
    public static final int TIME_BETWEEN_RETRIES_MS = 5000;
    
    // constant events
    private static final int EVENT_SERVICE_STATE_CHANGED = 100;
    private static final int EVENT_TIMEOUT_EMERGENCY_CALL = 200;
    
    /**
     * Package holding information needed for the callback.
     */
    private static class EmergencyCallInfo {
        public Phone phone;
        public Intent intent;
        public ProgressDialog dialog;
        public Application app;
    }
    
    /**
     * static handler class, used to handle the two relevent events. 
     */
    private static EmergencyCallEventHandler sHandler;
    private static class EmergencyCallEventHandler extends Handler {
        public void handleMessage(Message msg) {
            switch(msg.what) {
                case EVENT_SERVICE_STATE_CHANGED: {
                        // make the initial call attempt after the radio is turned on.
                        ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
                        if (DBG) Log.d(TAG, "EVENT_SERVICE_STATE_CHANGED: state = " + state);
                        if (state.getState() != ServiceState.STATE_POWER_OFF) {
                            EmergencyCallInfo eci = 
                                (EmergencyCallInfo) ((AsyncResult) msg.obj).userObj;
                            // deregister for the service state change events. 
                            eci.phone.unregisterForServiceStateChanged(this);
                            eci.dialog.dismiss();

                            if (DBG) Log.d(TAG, "About to (re)launch InCallScreen: " + eci.intent);
                            eci.app.startActivity(eci.intent);
                        }
                    }
                    break;

                case EVENT_TIMEOUT_EMERGENCY_CALL: {
                        if (DBG) Log.d(TAG, "EVENT_TIMEOUT_EMERGENCY_CALL...");
                        // repeated call after the timeout period.
                        EmergencyCallInfo eci = (EmergencyCallInfo) msg.obj;
                        eci.dialog.dismiss();

                        if (DBG) Log.d(TAG, "About to (re)launch InCallScreen: " + eci.intent);
                        eci.app.startActivity(eci.intent);
                    }
                    break;
            }
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        Log.i(TAG, "onCreate()...  intent = " + getIntent());
        super.onCreate(icicle);

        // Watch out: the intent action we get here should always be
        // ACTION_CALL_EMERGENCY, since the whole point of this activity
        // is for it to be launched using the same intent originally
        // passed to the InCallScreen, which will always be
        // ACTION_CALL_EMERGENCY when making an emergency call.
        //
        // If we ever get launched with any other action, especially if it's
        // "com.android.phone.InCallScreen.UNDEFINED" (as in bug 3094858), that
        // almost certainly indicates a logic bug in the InCallScreen.
        if (!Intent.ACTION_CALL_EMERGENCY.equals(getIntent().getAction())) {
            Log.w(TAG, "Unexpected intent action!  Should be ACTION_CALL_EMERGENCY, "
                  + "but instead got: " + getIntent().getAction());
        }

        // setup the phone and get the retry count embedded in the intent.
        Phone phone = PhoneFactory.getDefaultPhone();
        int retryCount = getIntent().getIntExtra(EMERGENCY_CALL_RETRY_KEY, INITIAL_ATTEMPT);
        
        // create a new message object.
        EmergencyCallInfo eci = new EmergencyCallInfo();
        eci.phone = phone;
        eci.app = getApplication();
        eci.dialog = constructDialog(retryCount);

        // The Intent we're going to fire off to retry the call is the
        // same one that got us here (except that we *don't* explicitly
        // specify this class as the component!)
        eci.intent = getIntent().setComponent(null);
        // And we'll be firing this Intent from the PhoneApp's context
        // (see the startActivity() calls above) so the
        // FLAG_ACTIVITY_NEW_TASK flag is required.
        eci.intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (DBG) Log.d(TAG, "- initial eci.intent: " + eci.intent);

        // create the handler.
        if (sHandler == null) {
            sHandler = new EmergencyCallEventHandler();
        }
        
        // If this is the initial attempt, we need to register for a radio state
        // change and turn the radio on.  Otherwise, this is just a retry, and
        // we simply wait the alloted time before sending the request to try
        // the call again.
        
        // Note: The radio logic ITSELF will try its best to put the emergency
        // call through once the radio is turned on.  The retry we have here 
        // is in case it fails; the current constants we have include making
        // 6 attempts, with a 5 second delay between each.
        if (retryCount == INITIAL_ATTEMPT) {
            // place the number of pending retries in the intent.
            eci.intent.putExtra(EMERGENCY_CALL_RETRY_KEY, NUMBER_OF_RETRIES);
            
            // turn the radio on and listen for it to complete.
            phone.registerForServiceStateChanged(sHandler, 
                    EVENT_SERVICE_STATE_CHANGED, eci);

            // If airplane mode is on, we turn it off the same way that the 
            // Settings activity turns it off.
            if (Settings.System.getInt(getContentResolver(), 
                    Settings.System.AIRPLANE_MODE_ON, 0) > 0) {
                if (DBG) Log.d(TAG, "Turning off airplane mode...");

                // Change the system setting
                Settings.System.putInt(getContentResolver(), Settings.System.AIRPLANE_MODE_ON, 0);
                
                // Post the intent
                Intent intent = new Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED);
                intent.putExtra("state", false);
                sendBroadcast(intent);

            // Otherwise, for some strange reason the radio is just off, so 
            // we just turn it back on.
            } else {
                if (DBG) Log.d(TAG, "Manually powering radio on...");
                phone.setRadioPower(true);
            }
            
        } else {
            // decrement and store the number of retries.
            if (DBG) Log.d(TAG, "Retry attempt...  retryCount = " + retryCount);
            eci.intent.putExtra(EMERGENCY_CALL_RETRY_KEY, (retryCount - 1));
            
            // get the message and attach the data, then wait the alloted
            // time and send.
            Message m = sHandler.obtainMessage(EVENT_TIMEOUT_EMERGENCY_CALL);
            m.obj = eci;
            sHandler.sendMessageDelayed(m, TIME_BETWEEN_RETRIES_MS);
        }
        finish();
    }
    
    /**
     * create the dialog and hand it back to caller.
     */
    private ProgressDialog constructDialog(int retryCount) {
        // figure out the message to display. 
        int msgId = (retryCount == INITIAL_ATTEMPT) ? 
                R.string.emergency_enable_radio_dialog_message :
                R.string.emergency_enable_radio_dialog_retry;

        // create a system dialog that will persist outside this activity.
        ProgressDialog pd = new ProgressDialog(getApplication());
        pd.setTitle(getText(R.string.emergency_enable_radio_dialog_title));
        pd.setMessage(getText(msgId));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
        pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        
        // show the dialog
        pd.show();
        
        return pd;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        // We shouldn't ever get here, since we should never be launched in
        // "singleTop" mode in the first place.
        Log.w(TAG, "Unexpected call to onNewIntent(): intent=" + intent);
        super.onNewIntent(intent);
    }
}
