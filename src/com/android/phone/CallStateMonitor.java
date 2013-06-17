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

import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.telephony.CallManager;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;


/**
 * Dedicated Call state monitoring class.  This class communicates directly with
 * the call manager to listen for call state events and notifies registered
 * handlers.
 * It works as an inverse multiplexor for all classes wanted Call State updates
 * so that there exists only one channel to the telephony layer.
 *
 * TODO(santoscordon): Add manual phone state checks (getState(), etc.).
 * TODO(santoscordon): unregister self when radio technology changes.
 */
class CallStateMonitor extends Handler {
    private static final String LOG_TAG = CallStateMonitor.class.getSimpleName();
    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    private CallManager callManager;
    private ArrayList<Handler> registeredHandlers;


    // Events from the Phone object:
    enum CallStateEvent {
        UNKNOWN_EVENT,
        STATE_CHANGED,
        NEW_RINGING_CONNECTION,
        DISCONNECT;

        CallStateEvent fromInteger(int value) {
            CallStateEvent retval = UNKNOWN_EVENT;

            if (value < 0 || value >= CallStateEvent.values().length) {
                Log.e(LOG_TAG, "Value is invalid for CallStateEvent.");
                return CallStateEvent.UNKNOWN_EVENT;
            }

            return CallStateEvent.values()[value];
        }
    }


    // Events generated internally:
    public CallStateMonitor(CallManager callManager) {
        this.callManager = callManager;
        registeredHandlers = new ArrayList<Handler>();

        registerForNotifications();
    }

    /**
     * Register for call state notifications with the CallManager.
     */
    private void registerForNotifications() {
        callManager.registerForPreciseCallStateChanged(this,
                CallStateEvent.STATE_CHANGED.ordinal(), null);
        callManager.registerForNewRingingConnection(this,
                CallStateEvent.NEW_RINGING_CONNECTION.ordinal(), null);
        callManager.registerForDisconnect(this, CallStateEvent.DISCONNECT.ordinal(), null);
    }

    public void addHandler(Handler handler) {
        if (handler != null && !registeredHandlers.contains(handler)) {
            registeredHandlers.add(handler);
        }
    }

    @Override
    public void handleMessage(Message msg) {
        if (DBG) {
            Log.d(LOG_TAG, "handleMessage(" + msg.what + ")");
        }

        for (Handler handler : registeredHandlers) {
            handler.handleMessage(msg);
        }
    }
}
