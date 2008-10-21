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
import android.bluetooth.BluetoothDevice;
import android.bluetooth.HeadsetBase;
import android.bluetooth.ScoSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.provider.CallLog.Calls;
import android.provider.Contacts.Phones;
import android.provider.Contacts.PhonesColumns;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.text.TextUtils;
import android.util.Log;

import java.util.LinkedList;
import java.util.HashMap;

/**
 * Bluetooth headset manager for the Phone app.
 * @hide
 */
public class BluetoothHandsfree {
    private static final String TAG = "BT HS/HF";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;  // even more logging

    public static final int TYPE_UNKNOWN           = 0;
    public static final int TYPE_HEADSET           = 1;
    public static final int TYPE_HANDSFREE         = 2;

    private Context mContext;
    private Phone mPhone;
    private ServiceState mServiceState;
    private HeadsetBase mHeadset;  // null when not connected
    private int mHeadsetType;
    private boolean mAudioPossible;
    private ScoSocket mIncomingSco;
    private ScoSocket mOutgoingSco;
    private ScoSocket mConnectedSco;

    private Call mForegroundCall;
    private Call mBackgroundCall;
    private Call mRingingCall;

    private AudioManager mAudioManager;
    private PowerManager mPowerManager;

    private boolean mUserWantsAudio;
    private WakeLock mStartCallWakeLock;  // held while waiting for the intent to start call

    // AT command state
    private static final int MAX_CONNECTIONS = 3;  // Max connections for clcc indexing
                                                   // TODO: Verify

    private boolean mClip = false;  // Calling Line Information Presentation
    private boolean mIndicatorsEnabled = false;
    private boolean mCmee = false;  // Extended Error reporting
    private String  mSelectedPB;  // currently-selected phone book (includes quotes)
    private BluetoothPhoneState mPhoneState;  // for CIND and CIEV updates
    private long[] mClccTimestamps; // Timestamps associated with each clcc index
    private boolean[] mClccUsed;     // Is this clcc index in use
    private boolean mWaitingForCallStart;

    // Audio parameters
    private static final String HEADSET_NREC = "bt_headset_nrec";
    private static final String HEADSET_NAME = "bt_headset_name";

    private HashMap<String, PhoneBookEntry> mPhoneBooks = new HashMap<String, PhoneBookEntry>(5);

    private class PhoneBookEntry {
        public Cursor  mPhonebookCursor; // result set of last phone-book query
        public int     mCursorNumberColumn;
        public int     mCursorNumberTypeColumn;
        public int     mCursorNameColumn;
    };

    public static String typeToString(int type) {
        switch (type) {
        case TYPE_UNKNOWN:
            return "unknown";
        case TYPE_HEADSET:
            return "headset";
        case TYPE_HANDSFREE:
            return "handsfree";
        }
        return null;
    }

    /** The projection to use when querying the call log database in response
        to AT+CPBR for the MC, RC, and DC phone books (missed, received, and
        dialed calls respectively)
    */
    private static final String[] CALLS_PROJECTION = new String[] {
        Calls._ID, Calls.NUMBER
    };

    /** The projection to use when querying the contacts database in response
        to AT+CPBR for the ME phonebook (saved phone numbers).
    */
    private static final String[] PHONES_PROJECTION = new String[] {
        Phones._ID, Phones.NAME,
        Phones.NUMBER, Phones.TYPE
    };

    /** The projection to use when querying the contacts database in response
        to AT+CNUM for the ME phonebook (saved phone numbers).  We need only
        the phone numbers here and the phone type.
    */
    private static final String[] PHONES_LITE_PROJECTION = new String[] {
        Phones._ID, Phones.NUMBER, Phones.TYPE
    };

    /** Android supports as many phonebook entries as the flash can hold, but
     * BT periphals don't. Limit the number we'll report. */
    private static final int MAX_PHONEBOOK_SIZE = 16384;

    private static final String OUTGOING_CALL_WHERE =
            Calls.TYPE + "=" + Calls.OUTGOING_TYPE;
    private static final String INCOMING_CALL_WHERE =
            Calls.TYPE + "=" + Calls.INCOMING_TYPE;
    private static final String MISSED_CALL_WHERE =
            Calls.TYPE + "=" + Calls.MISSED_TYPE;

    public BluetoothHandsfree(Context context, Phone phone) {
        mPhone = phone;
        mContext = context;
        BluetoothDevice bluetooth =
                (BluetoothDevice)context.getSystemService(Context.BLUETOOTH_SERVICE);
        boolean bluetoothCapable = (bluetooth != null);
        mHeadset = null;  // nothing connected yet

        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mStartCallWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                       this.toString());
        mStartCallWakeLock.setReferenceCounted(false);

        if (bluetoothCapable) {
            resetAtState();
        }

        mRingingCall = mPhone.getRingingCall();
        mForegroundCall = mPhone.getForegroundCall();
        mBackgroundCall = mPhone.getBackgroundCall();
        mPhoneState = new BluetoothPhoneState();
        mUserWantsAudio = true;

        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /* package */ synchronized void onBluetoothEnabled() {
        /* Bluez has a bug where it will always accept and then orphan
         * incoming SCO connections, regardless of whether we have a listening
         * SCO socket. So the best thing to do is always run a listening socket
         * while bluetooth is on so that at least we can diconnect it
         * immediately when we don't want it.
         */
        if (mIncomingSco == null) {
            mIncomingSco = createScoSocket();
            mIncomingSco.accept();
        }
    }

    /* package */ synchronized void onBluetoothDisabled() {
        if (mConnectedSco != null) {
            mAudioManager.setBluetoothScoOn(false);
            mConnectedSco.close();
            mConnectedSco = null;
        }
        if (mOutgoingSco != null) {
            mOutgoingSco.close();
            mOutgoingSco = null;
        }
        if (mIncomingSco != null) {
            mIncomingSco.close();
            mIncomingSco = null;
        }
    }

    private boolean isHeadsetConnected() {
        if (mHeadset == null) {
            return false;
        }
        return mHeadset.isConnected();
    }

    /* package */ void connectHeadset(HeadsetBase headset, int headsetType) {
        mHeadset = headset;
        mHeadsetType = headsetType;
        if (mHeadsetType == TYPE_HEADSET) {
            initializeHeadsetAtParser();
        } else {
            initializeHandsfreeAtParser();
        }
        headset.startEventThread();
        configAudioParameters();

        if (inDebug()) {
            startDebug();
        }

        if (isIncallAudio()) {
            audioOn();
        }
    }

    /* returns true if there is some kind of in-call audio we may wish to route
     * bluetooth to */
    private boolean isIncallAudio() {
        Call.State state = mForegroundCall.getState();

        return (state == Call.State.ACTIVE || state == Call.State.ALERTING);
    }

    /* package */ void disconnectHeadset() {
        mHeadset = null;
        stopDebug();
        resetAtState();
    }

    private void resetAtState() {
        mClip = false;
        mIndicatorsEnabled = false;
        mCmee = false;
        mSelectedPB = null;
        mPhoneBooks.clear();
        // DC -- dialled calls
        // RC -- received calls
        // MC -- missed (or unanswered) calls
        // ME -- MT phonebook
        mPhoneBooks.put("\"DC\"", new PhoneBookEntry());
        mPhoneBooks.put("\"RC\"", new PhoneBookEntry());
        mPhoneBooks.put("\"MC\"", new PhoneBookEntry());
        mPhoneBooks.put("\"ME\"", new PhoneBookEntry());
        mClccTimestamps = new long[MAX_CONNECTIONS];
        mClccUsed = new boolean[MAX_CONNECTIONS];
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            mClccUsed[i] = false;
        }

    }

    private void configAudioParameters() {
        String name = mHeadset.getName();
        if (name == null) {
            name = "<unknown>";
        }
        mAudioManager.setParameter(HEADSET_NAME, name);
        mAudioManager.setParameter(HEADSET_NREC, "on");
    }


    /** Represents the data that we send in a +CIND or +CIEV command to the HF
     */
    private class BluetoothPhoneState {
        // 0: no service
        // 1: service
        private int mService;

        // 0: no active call
        // 1: active call (where active means audio is routed - not held call)
        private int mCall;

        // 0: not in call setup
        // 1: incoming call setup
        // 2: outgoing call setup
        // 3: remote party being alerted in an outgoing call setup
        private int mCallsetup;

        // 0: no calls held
        // 1: held call and active call
        // 2: held call only
        private int mCallheld;

        // cellular signal strength of AG: 0-5
        private int mSignal;

        // cellular signal strength in CSQ rssi scale
        private int mRssi;  // for CSQ

        // 0: roaming not active (home)
        // 1: roaming active
        private int mRoam;

        // battery charge of AG: 0-5
        private int mBattchg;

        // 0: not registered
        // 1: registered, home network
        // 5: registered, roaming
        private int mStat;  // for CREG

        private String mRingingNumber;  // Context for in-progress RING's
        private int    mRingingType;

        private boolean mIgnoreRing = false;

        private static final int SERVICE_STATE_CHANGED = 1;
        private static final int PHONE_STATE_CHANGED = 2;
        private static final int RING = 3;

        private Handler mStateChangeHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                switch(msg.what) {
                case RING:
                    AtCommandResult result = ring();
                    if (result != null) {
                        sendURC(result.toString());
                    }
                    break;
                case SERVICE_STATE_CHANGED:
                    ServiceState state = (ServiceState) ((AsyncResult) msg.obj).result;
                    updateServiceState(sendUpdate(), state);
                    break;
                case PHONE_STATE_CHANGED:
                    Connection connection = null;
                    if (((AsyncResult) msg.obj).result instanceof Connection) {
                        connection = (Connection) ((AsyncResult) msg.obj).result;
                    }
                    updatePhoneState(sendUpdate(), connection);
                    break;
                }
            }
        };

        private BluetoothPhoneState() {
            // init members
            updateServiceState(false, mPhone.getServiceState());
            updatePhoneState(false, null);
            mBattchg = 5;  // There is currently no API to get battery level
                           // on demand, so set to 5 and wait for an update
            mSignal = asuToSignal(mPhone.getSignalStrengthASU());

            // register for updates
            mPhone.registerForServiceStateChanged(mStateChangeHandler,
                                                  SERVICE_STATE_CHANGED, null);
            mPhone.registerForPhoneStateChanged(mStateChangeHandler,
                                                PHONE_STATE_CHANGED, null);
            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
            mContext.registerReceiver(mStateReceiver, filter);
        }

        private boolean sendUpdate() {
            return isHeadsetConnected() && mHeadsetType == TYPE_HANDSFREE && mIndicatorsEnabled;
        }

        private boolean sendClipUpdate() {
            return isHeadsetConnected() && mHeadsetType == TYPE_HANDSFREE && mClip;
        }

        /* convert [0,31] ASU signal strength to the [0,5] expected by
         * bluetooth devices. Scale is similar to status bar policy
         */
        private int asuToSignal(int asu) {
            if      (asu >= 16) return 5;
            else if (asu >= 8)  return 4;
            else if (asu >= 4)  return 3;
            else if (asu >= 2)  return 2;
            else if (asu >= 1)  return 1;
            else                return 0;
        }

        /* convert [0,5] signal strength to a rssi signal strength for CSQ
         * which is [0,31]. Despite the same scale, this is not the same value
         * as ASU.
         */
        private int signalToRssi(int signal) {
            // using C4A suggested values
            switch (signal) {
            case 0: return 0;
            case 1: return 4;
            case 2: return 8;
            case 3: return 13;
            case 4: return 19;
            case 5: return 31;
            }
            return 0;
        }


        private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals(Intent.ACTION_BATTERY_CHANGED)) {
                    updateBatteryState(intent);
                } else if (intent.getAction().equals(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED)) {
                    updateSignalState(intent);
                }
            }
        };

        private synchronized void updateBatteryState(Intent intent) {
            int batteryLevel = intent.getIntExtra("level", -1);
            int scale = intent.getIntExtra("scale", -1);
            if (batteryLevel == -1 || scale == -1) {
                return;  // ignore
            }
            batteryLevel = batteryLevel * 5 / scale;
            if (mBattchg != batteryLevel) {
                mBattchg = batteryLevel;
                if (sendUpdate()) {
                    sendURC("+CIEV: 7," + mBattchg);
                }
            }
        }

        private synchronized void updateSignalState(Intent intent) {
            int signal;
            signal = asuToSignal(intent.getIntExtra("asu", -1));
            mRssi = signalToRssi(signal);  // no unsolicited CSQ
            if (signal != mSignal) {
                mSignal = signal;
                if (sendUpdate()) {
                    sendURC("+CIEV: 5," + mSignal);
                }
            }
        }

        private synchronized void updateServiceState(boolean sendUpdate, ServiceState state) {
            int service = state.getState() == ServiceState.STATE_IN_SERVICE ? 1 : 0;
            int roam = state.getRoaming() ? 1 : 0;
            int stat;
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);

            if (service == 0) {
                stat = 0;
            } else {
                stat = (roam == 1) ? 5 : 1;
            }

            if (service != mService) {
                mService = service;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 1," + mService);
                }
            }
            if (roam != mRoam) {
                mRoam = roam;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 6," + mRoam);
                }
            }
            if (stat != mStat) {
                mStat = stat;
                if (sendUpdate) {
                    result.addResponse(toCregString());
                }
            }

            sendURC(result.toString());
        }

        private synchronized void updatePhoneState(boolean sendUpdate, Connection connection) {
            int call = 0;
            int callsetup = 0;
            int callheld = 0;
            int prevCallsetup = mCallsetup;
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);

            if (DBG) log("updatePhoneState()");

            switch (mPhone.getState()) {
            case IDLE:
                mUserWantsAudio = true;  // out of call - reset state
                audioOff();
                break;
            default:
                callStarted();
            }

            switch(mForegroundCall.getState()) {
            case ACTIVE:
                call = 1;
                mAudioPossible = true;
                break;
            case DIALING:
                callsetup = 2;
                mAudioPossible = false;
                break;
            case ALERTING:
                callsetup = 3;
                mAudioPossible = true;
                break;
            default:
                mAudioPossible = false;
            }

            switch(mRingingCall.getState()) {
            case INCOMING:
            case WAITING:
                callsetup = 1;
                break;
            }

            switch(mBackgroundCall.getState()) {
            case HOLDING:
                if (call == 1) {
                    callheld = 1;
                } else {
                    call = 1;
                    callheld = 2;
                }
                break;
            }

            if (mCall != call) {
                mCall = call;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 2," + mCall);
                }
            }
            if (mCallsetup != callsetup) {
                mCallsetup = callsetup;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 3," + mCallsetup);
                }
            }
            if (mCallheld != callheld) {
                mCallheld = callheld;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 4," + mCallheld);
                }
            }

            if (callsetup == 1 && callsetup != prevCallsetup) {
                // new incoming call
                String number = null;
                int type = 128;
                if (sendUpdate) {
                    // find incoming phone number and type
                    if (connection == null) {
                        connection = mRingingCall.getEarliestConnection();
                        if (connection == null) {
                            Log.e(TAG, "Could not get a handle on Connection object for new " +
                                  "incoming call");
                        }
                    }
                    if (connection != null) {
                        number = connection.getAddress();
                        if (number != null) {
                            type = PhoneNumberUtils.toaFromString(number);
                        }
                    }
                    if (number == null) {
                        number = "";
                    }
                }
                if ((call != 0 || callheld != 0) && sendUpdate) {
                    // call waiting
                    result.addResponse("+CCWA: \"" + number + "\"," + type);
                } else {
                    // regular new incoming call
                    mRingingNumber = number;
                    mRingingType = type;
                    mIgnoreRing = false;
                    result.addResult(ring());
                }
            }
            sendURC(result.toString());
        }

        private AtCommandResult ring() {
            if (!mIgnoreRing && mRingingCall.isRinging()) {
                AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
                result.addResponse("RING");
                if (sendClipUpdate()) {
                    result.addResponse("+CLIP: \"" + mRingingNumber + "\"," + mRingingType);
                }

                Message msg = mStateChangeHandler.obtainMessage(RING);
                mStateChangeHandler.sendMessageDelayed(msg, 3000);
                return result;
            }
            return null;
        }

        private synchronized String toCregString() {
            return new String("+CREG: 1," + mStat);
        }

        private synchronized AtCommandResult toCindResult() {
            AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
            String status = "+CIND: " + mService + "," + mCall + "," + mCallsetup + "," +
                            mCallheld + "," + mSignal + "," + mRoam + "," + mBattchg;
            result.addResponse(status);
            return result;
        }

        private synchronized AtCommandResult toCsqResult() {
            AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
            String status = "+CSQ: " + mRssi + ",99";
            result.addResponse(status);
            return result;
        }


        private synchronized AtCommandResult getCindTestResult() {
            return new AtCommandResult("+CIND: (\"service\",(0-1))," + "(\"call\",(0-1))," +
                        "(\"callsetup\",(0-3)),(\"callheld\",(0-2)),(\"signal\",(0-5))," +
                        "(\"roam\",(0-1)),(\"battchg\",(0-5))");
        }

        private synchronized void ignoreRing() {
            mCallsetup = 0;
            mIgnoreRing = true;
            if (sendUpdate()) {
                sendURC("+CIEV: 3," + mCallsetup);
            }
        }

    };

    private static final int SCO_ACCEPTED = 1;
    private static final int SCO_CONNECTED = 2;
    private static final int SCO_CLOSED = 3;
    private static final int CHECK_CALL_STARTED = 4;
    private final Handler mHandler = new Handler() {
        @Override
        public synchronized void handleMessage(Message msg) {
            switch (msg.what) {
            case SCO_ACCEPTED:
                if (msg.arg1 == ScoSocket.STATE_CONNECTED) {
                    if (isHeadsetConnected() && mAudioPossible && mConnectedSco == null) {
                        Log.i(TAG, "Routing audio for incoming SCO connection");
                        mConnectedSco = (ScoSocket)msg.obj;
                        mAudioManager.setBluetoothScoOn(true);
                    } else {
                        Log.i(TAG, "Rejecting incoming SCO connection");
                        ((ScoSocket)msg.obj).close();
                    }
                } // else error trying to accept, try again
                mIncomingSco = createScoSocket();
                mIncomingSco.accept();
                break;
            case SCO_CONNECTED:
                if (msg.arg1 == ScoSocket.STATE_CONNECTED && isHeadsetConnected() &&
                        mConnectedSco == null) {
                    if (DBG) log("Routing audio for outgoing SCO conection");
                    mConnectedSco = (ScoSocket)msg.obj;
                    mAudioManager.setBluetoothScoOn(true);
                } else if (msg.arg1 == ScoSocket.STATE_CONNECTED) {
                    if (DBG) log("Rejecting new connected outgoing SCO socket");
                    ((ScoSocket)msg.obj).close();
                    mOutgoingSco.close();
                }
                mOutgoingSco = null;
                break;
            case SCO_CLOSED:
                if (mConnectedSco == (ScoSocket)msg.obj) {
                    mConnectedSco = null;
                    mAudioManager.setBluetoothScoOn(false);
                } else if (mOutgoingSco == (ScoSocket)msg.obj) {
                    mOutgoingSco = null;
                } else if (mIncomingSco == (ScoSocket)msg.obj) {
                    mIncomingSco = null;
                }
                break;
            case CHECK_CALL_STARTED:
                if (mWaitingForCallStart) {
                    mWaitingForCallStart = false;
                    Log.e(TAG, "Timeout waiting for call to start");
                    sendURC("ERROR");
                    if (mStartCallWakeLock.isHeld()) {
                        mStartCallWakeLock.release();
                    }
                }
                break;
            }
        }
    };

    private ScoSocket createScoSocket() {
        return new ScoSocket(mPowerManager, mHandler, SCO_ACCEPTED, SCO_CONNECTED, SCO_CLOSED);
    }

    /** Request to establish SCO (audio) connection to bluetooth
     * headset/handsfree, if one is connected. Does not block.
     * Returns false if the user has requested audio off, or if there
     * is some other immediate problem that will prevent BT audio.
     */
    /* package */ synchronized boolean audioOn() {
        if (VDBG) log("audioOn()");
        if (!isHeadsetConnected()) {
            if (DBG) log("audioOn(): headset is not connected!");
            return false;
        }

        if (mConnectedSco != null) {
            if (DBG) log("audioOn(): audio is already connected");
            return true;
        }

        if (!mUserWantsAudio) {
            if (DBG) log("audioOn(): user requested no audio, ignoring");
            return false;
        }

        if (mOutgoingSco != null) {
            if (DBG) log("audioOn(): outgoing SCO already in progress");
            return true;
        }
        mOutgoingSco = createScoSocket();
        if (!mOutgoingSco.connect(mHeadset.getAddress())) {
            mOutgoingSco = null;
        }

        return true;
    }

    /** Used to indicate the user requested BT audio on.
     *  This will establish SCO (BT audio), even if the user requested it off
     *  previously on this call.
     */
    /* package */ synchronized void userWantsAudioOn() {
        mUserWantsAudio = true;
        audioOn();
    }
    /** Used to indicate the user requested BT audio off.
     *  This will prevent us from establishing BT audio again during this call
     *  if audioOn() is called.
     */
    /* package */ synchronized void userWantsAudioOff() {
        mUserWantsAudio = false;
        audioOff();
    }

    /** Request to disconnect SCO (audio) connection to bluetooth
     * headset/handsfree, if one is connected. Does not block.
     */
    /* package */ synchronized void audioOff() {
        if (VDBG) log("audioOff()");

        if (mConnectedSco != null) {
            mAudioManager.setBluetoothScoOn(false);
            mConnectedSco.close();
            mConnectedSco = null;
        }
        if (mOutgoingSco != null) {
            mOutgoingSco.close();
            mOutgoingSco = null;
        }
    }

    /* package */ boolean isAudioOn() {
        return (mConnectedSco != null);
    }

    /* package */ void ignoreRing() {
        mPhoneState.ignoreRing();
    }

    /* List of AT error codes specified by the Handsfree profile. */

    private static final int CME_ERROR_AG_FAILURE = 0;
    private static final int CME_ERROR_NO_CONNECTION_TO_PHONE = 1;
    //    private static final int CME_ERROR_ = 2;
    private static final int CME_ERROR_OPERATION_NOT_ALLOWED = 3;
    private static final int CME_ERROR_OPERATION_NOT_SUPPORTED = 4;
    private static final int CME_ERROR_PIN_REQUIRED = 5;
    //    private static final int CME_ERROR_ = 6;
    //    private static final int CME_ERROR_ = 7;
    //    private static final int CME_ERROR_ = 8;
    //    private static final int CME_ERROR_ = 9;
    private static final int CME_ERROR_SIM_MISSING = 10;
    private static final int CME_ERROR_SIM_PIN_REQUIRED = 11;
    private static final int CME_ERROR_SIM_PUK_REQUIRED = 12;
    private static final int CME_ERROR_SIM_FAILURE = 13;
    private static final int CME_ERROR_SIM_BUSY = 14;
    //    private static final int CME_ERROR_ = 15;
    private static final int CME_ERROR_WRONG_PASSWORD = 16;
    private static final int CME_ERROR_SIM_PIN2_REQUIRED = 17;
    private static final int CME_ERROR_SIM_PUK2_REQUIRED = 18;
    //    private static final int CME_ERROR_ = 19;
    private static final int CME_ERROR_MEMORY_FULL = 20;
    private static final int CME_ERROR_INVALID_INDEX = 21;
    //    private static final int CME_ERROR_ = 22;
    private static final int CME_ERROR_MEMORY_FAILURE = 23;
    private static final int CME_ERROR_TEXT_TOO_LONG = 24;
    private static final int CME_ERROR_TEXT_HAS_INVALID_CHARS = 25;
    private static final int CME_ERROR_DIAL_STRING_TOO_LONG = 26;
    private static final int CME_ERROR_DIAL_STRING_HAS_INVALID_CHARS = 27;
    //    private static final int CME_ERROR_ = 28;
    //    private static final int CME_ERROR_ = 29;
    private static final int CME_ERROR_NO_SERVICE = 30;
    //    private static final int CME_ERROR_ = 31;
    private static final int CME_ERROR_911_ONLY_ALLOWED = 32;

    public AtCommandResult reportAtError(int error) {
        if (mCmee) {
            AtCommandResult result =
                    new AtCommandResult(AtCommandResult.UNSOLICITED);
            result.addResponse("+CME ERROR: " + error);
            return result;
        } else {
            return new AtCommandResult(AtCommandResult.ERROR);
        }
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

    private boolean initPhoneBookEntry(String pb, PhoneBookEntry pbe) {
        String where;
        boolean ancillaryPhonebook = true;

        if (pb.equals("\"ME\"")) {
            ancillaryPhonebook = false;
            where = null;
        } else if (pb.equals("\"DC\"")) {
            where = OUTGOING_CALL_WHERE;
        } else if (pb.equals("\"RC\"")) {
            where = INCOMING_CALL_WHERE;
        } else if (pb.equals("\"MC\"")) {
            where = MISSED_CALL_WHERE;
        } else {
            return false;
        }

        if (pbe.mPhonebookCursor == null) {
            if (ancillaryPhonebook) {
//              ContentValues values = new ContentValues();
//              values.put(Calls.NEW, "0");
//              mContext.getContentResolver().update(Calls.CONTENT_URI, values, where, null);
            }

            if (ancillaryPhonebook) {
                pbe.mPhonebookCursor = mContext.getContentResolver().query(
                        Calls.CONTENT_URI, CALLS_PROJECTION, where, null,
                        Calls.DEFAULT_SORT_ORDER + " LIMIT " + MAX_PHONEBOOK_SIZE);
                pbe.mCursorNumberColumn = pbe.mPhonebookCursor.getColumnIndexOrThrow(Calls.NUMBER);
                pbe.mCursorNumberTypeColumn = -1;
                pbe.mCursorNameColumn = -1;
            } else {
                pbe.mPhonebookCursor = mContext.getContentResolver().query(
                        Phones.CONTENT_URI, PHONES_PROJECTION, where, null,
                        Phones.DEFAULT_SORT_ORDER + " LIMIT " + MAX_PHONEBOOK_SIZE);
                pbe.mCursorNumberColumn = pbe.mPhonebookCursor.getColumnIndex(Phones.NUMBER);
                pbe.mCursorNumberTypeColumn = pbe.mPhonebookCursor.getColumnIndex(Phones.TYPE);
                pbe.mCursorNameColumn = pbe.mPhonebookCursor.getColumnIndex(Phones.NAME);
            }
        }

        return true;
    }

    private void sendURC(String urc) {
        if (isHeadsetConnected()) {
            mHeadset.sendURC(urc);
        }
    }

    /** helper to redial last dialled number */
    private AtCommandResult redial() {
        // Get the last dialled number from the phone book
        String[] projection = {Calls.NUMBER};
        Cursor cursor = mContext.getContentResolver().query(Calls.CONTENT_URI, projection,
                Calls.TYPE + "=" + Calls.OUTGOING_TYPE, null, Calls.DEFAULT_SORT_ORDER +
                " LIMIT 1");
        if (cursor.getCount() < 1) {
            // spec seems to suggest sending ERROR if we dont have a
            // number to redial
            if (DBG) log("Bluetooth redial requested (+BLDN), but no previous " +
                  "outgoing calls found. Ignoring");
            cursor.close();
            return new AtCommandResult(AtCommandResult.ERROR);
        }

        cursor.moveToNext();
        int column = cursor.getColumnIndexOrThrow(Calls.NUMBER);
        String number = cursor.getString(column);
        cursor.close();

        // Call it
        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                Uri.fromParts("tel", number, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        // We do not immediately respond OK, wait until we get a phone state
        // update. If we return OK now and the handsfree immeidately requests
        // our phone state it will say we are not in call yet which confuses
        // some devices
        waitForCallStart();
        return new AtCommandResult(AtCommandResult.UNSOLICITED);  // send nothing
    }

    /** Build the +CLCC result
     *  The complexity arises from the fact that we need to maintain the same
     *  CLCC index even as a call moves between states. */
    private synchronized AtCommandResult getClccResult() {
        // Collect all known connections
        Connection[] clccConnections = new Connection[MAX_CONNECTIONS];  // indexed by CLCC index
        LinkedList<Connection> newConnections = new LinkedList<Connection>();
        LinkedList<Connection> connections = new LinkedList<Connection>();
        if (mRingingCall.getState().isAlive()) {
            connections.addAll(mRingingCall.getConnections());
        }
        if (mForegroundCall.getState().isAlive()) {
            connections.addAll(mForegroundCall.getConnections());
        }
        if (mBackgroundCall.getState().isAlive()) {
            connections.addAll(mBackgroundCall.getConnections());
        }

        // Mark connections that we already known about
        boolean clccUsed[] = new boolean[MAX_CONNECTIONS];
        for (int i = 0; i < MAX_CONNECTIONS; i++) {
            clccUsed[i] = mClccUsed[i];
            mClccUsed[i] = false;
        }
        for (Connection c : connections) {
            boolean found = false;
            long timestamp = c.getCreateTime();
            for (int i = 0; i < MAX_CONNECTIONS; i++) {
                if (clccUsed[i] && timestamp == mClccTimestamps[i]) {
                    mClccUsed[i] = true;
                    found = true;
                    clccConnections[i] = c;
                    break;
                }
            }
            if (!found) {
                newConnections.add(c);
            }
        }

        // Find a CLCC index for new connections
        while (!newConnections.isEmpty()) {
            // Find lowest empty index
            int i = 0;
            while (mClccUsed[i]) i++;
            // Find earliest connection
            long earliestTimestamp = newConnections.get(0).getCreateTime();
            Connection earliestConnection = newConnections.get(0);
            for (int j = 0; j < newConnections.size(); j++) {
                long timestamp = newConnections.get(j).getCreateTime();
                if (timestamp < earliestTimestamp) {
                    earliestTimestamp = timestamp;
                    earliestConnection = newConnections.get(j);
                }
            }

            // update
            mClccUsed[i] = true;
            mClccTimestamps[i] = earliestTimestamp;
            clccConnections[i] = earliestConnection;
            newConnections.remove(earliestConnection);
        }

        // Build CLCC
        AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
        for (int i = 0; i < clccConnections.length; i++) {
            if (mClccUsed[i]) {
                String clccEntry = connectionToClccEntry(i, clccConnections[i]);
                if (clccEntry != null) {
                    result.addResponse(clccEntry);
                }
            }
        }

        return result;
    }

    /** Convert a Connection object into a single +CLCC result */
    private String connectionToClccEntry(int index, Connection c) {
        int state;
        switch (c.getState()) {
        case ACTIVE:
            state = 0;
            break;
        case HOLDING:
            state = 1;
            break;
        case DIALING:
            state = 2;
            break;
        case ALERTING:
            state = 3;
            break;
        case INCOMING:
            state = 4;
            break;
        case WAITING:
            state = 5;
            break;
        default:
            return null;  // bad state
        }

        int mpty = 0;
        Call call = c.getCall();
        if (call != null) {
            mpty = call.isMultiparty() ? 1 : 0;
        }

        int direction = c.isIncoming() ? 1 : 0;
        
        String number = c.getAddress();
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        }

        String result = "+CLCC: " + (index + 1) + "," + direction + "," + state + ",0," + mpty;
        if (number != null) {
            result += "," + number + "," + type;
        }
        return result;
    }
    /**
     * Register AT Command handlers to implement the Headset profile
     */
    private void initializeHeadsetAtParser() {
        if (DBG) log("Registering Headset AT commands");
        AtParser parser = mHeadset.getAtParser();
        // Headset's usually only have one button, which is meant to cause the
        // HS to send us AT+CKPD=200 or AT+CKPD.
        parser.register("+CKPD", new AtCommandHandler() {
            private AtCommandResult headsetButtonPress() {
                if (mRingingCall.isRinging()) {
                    // Answer the call
                    PhoneUtils.answerCall(mPhone);
                    audioOn();
                } else if (mForegroundCall.getState().isAlive()) {
                    if (!isAudioOn()) {
                        // Transfer audio from AG to HS
                        audioOn();
                    } else {
                        if (mHeadset.getDirection() == HeadsetBase.DIRECTION_INCOMING &&
                          (System.currentTimeMillis() - mHeadset.getConnectTimestamp()) < 5000) {
                            // Headset made a recent ACL connection to us - and
                            // made a mandatory AT+CKPD request to connect
                            // audio which races with our automatic audio
                            // setup.  ignore
                        } else {
                            // Hang up the call
                            audioOff();
                            PhoneUtils.hangup(mPhone);
                        }
                    }
                } else {
                    // No current call - redial last number
                    return redial();
                }
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleActionCommand() {
                return headsetButtonPress();
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                return headsetButtonPress();
            }
        });
    }

    /**
     * Register AT Command handlers to implement the Handsfree profile
     */
    private void initializeHandsfreeAtParser() {
        if (DBG) log("Registering Handsfree AT commands");
        AtParser parser = mHeadset.getAtParser();
   
        // Answer
        parser.register('A', new AtCommandHandler() {
            @Override
            public AtCommandResult handleBasicCommand(String args) {
                PhoneUtils.answerCall(mPhone);
                return new AtCommandResult(AtCommandResult.OK);
            }
        });
        parser.register('D', new AtCommandHandler() {
            @Override
            public AtCommandResult handleBasicCommand(String args) {
                if (args.length() > 0) {
                    if (args.charAt(0) == '>') {
                        // Yuck - memory dialling requested.
                        // Just dial last number for now
                        if (args.startsWith(">9999")) {   // for PTS test
                            return new AtCommandResult(AtCommandResult.ERROR);
                        }
                        return redial();
                    } else {
                        // Remove trailing ';'
                        if (args.charAt(args.length() - 1) == ';') {
                            args = args.substring(0, args.length() - 1);
                        }
                        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                Uri.fromParts("tel", args, null));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);

                        waitForCallStart();
                        return new AtCommandResult(AtCommandResult.UNSOLICITED);  // send nothing
                    }
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
        });

        // Hang-up command
        parser.register("+CHUP", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                audioOff();

                PhoneUtils.hangup(mPhone);

                return new AtCommandResult(AtCommandResult.OK);
            }
        });

        // Bluetooth Retrieve Supported Features command
        parser.register("+BRSF", new AtCommandHandler() {
            private AtCommandResult sendBRSF() {
                // Bit 0: 3 way calling
                //     1: EC / NR
                //     2: CLI Presentation
                //     5: call reject
                //     6: Enhanced call status
                return new AtCommandResult("+BRSF: 99");
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+BRSF=<handsfree supported features bitmap>
                // Handsfree is telling us which features it supports. We
                // ignore its report for now. But we do respond with our own
                // feature bitmap.
                return sendBRSF();
            }
            @Override
            public AtCommandResult handleActionCommand() {
                // This seems to be out of spec, but lets do the nice thing
                return sendBRSF();
            }
            @Override
            public AtCommandResult handleReadCommand() {
                // This seems to be out of spec, but lets do the nice thing
                return sendBRSF();
            }
        });

        // Call waiting notification on/off
        parser.register("+CCWA", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // Seems to be out of spec, but lets return nicely
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleReadCommand() {
                // Call waiting is always on
                return new AtCommandResult("+CCWA: 1");
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+CCWA=<n>
                // Handsfree is trying to enable/disable call waiting. We
                // cannot disable in the current implementation.
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleTestCommand() {
                // Request for range of supported CCWA paramters
                return new AtCommandResult("+CCWA: (\"n\",(1))");
            }
        });

        // Mobile Equipment Event Reporting enable/disable command
        // Of the full 3GPP syntax paramters (mode, keyp, disp, ind, bfr) we
        // only support paramter ind (disable/enable evert reporting using
        // +CDEV)
        parser.register("+CMER", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult(
                        "+CMER: 3,0,0," + (mIndicatorsEnabled ? "1" : "0"));
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args.length < 4) {
                    // This is a syntax error
                    return new AtCommandResult(AtCommandResult.ERROR);
                } else if (args[0].equals(3) && args[1].equals(0) &&
                           args[2].equals(0)) {
                    if (args[3].equals(0)) {
                        mIndicatorsEnabled = false;
                        return new AtCommandResult(AtCommandResult.OK);
                    } else if (args[3].equals(1)) {
                        mIndicatorsEnabled = true;
                        return new AtCommandResult(AtCommandResult.OK);
                    }
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                } else {
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CMER: (3),(0),(0),(0-1)");
            }
        });

        // Mobile Equipment Error Reporting enable/disable
        parser.register("+CMEE", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // out of spec, assume they want to enable
                mCmee = true;
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CMEE: " + (mCmee ? "1" : "0"));
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+CMEE=<n>
                if (args.length == 0) {
                    // <n> ommitted - default to 0
                    mCmee = false;
                    return new AtCommandResult(AtCommandResult.OK);
                } else if (!(args[0] instanceof Integer)) {
                    // Syntax error
                    return new AtCommandResult(AtCommandResult.ERROR);
                } else {
                    mCmee = ((Integer)args[0] == 1);
                    return new AtCommandResult(AtCommandResult.OK);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                // Probably not required but spec, but no harm done
                return new AtCommandResult("+CMEE: (0-1)");
            }
        });

        // Bluetooth Last Dialled Number
        parser.register("+BLDN", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                return redial();
            }
        });

        // Indicator Update command
        parser.register("+CIND", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return mPhoneState.toCindResult();
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return mPhoneState.getCindTestResult();
            }
        });

        // Query Signal Quality (legacy)
        parser.register("+CSQ", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                return mPhoneState.toCsqResult();
            }
        });

        // Query network registration state
        parser.register("+CREG", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult(mPhoneState.toCregString());
            }
        });

        // Send DTMF. I don't know if we are also expected to play the DTMF tone
        // locally, right now we don't
        parser.register("+VTS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args.length >= 1) {
                    char c;
                    if (args[0] instanceof Integer) {
                        c = ((Integer) args[0]).toString().charAt(0);
                    } else {
                        c = ((String) args[0]).charAt(0);
                    }
                    if (isValidDtmf(c)) {
                        mPhone.sendDtmf(c);
                        return new AtCommandResult(AtCommandResult.OK);
                    }
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
            private boolean isValidDtmf(char c) {
                switch (c) {
                case '#':
                case '*':
                    return true;
                default:
                    if (Character.digit(c, 14) != -1) {
                        return true;  // 0-9 and A-D
                    }
                    return false;
                }
            }
        });

        // List calls
        parser.register("+CLCC", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                return getClccResult();
            }
        });

        // Call Hold and Multiparty Handling command
        parser.register("+CHLD", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args.length >= 1) {
                    if (args[0].equals(0)) {
                        boolean result;
                        if (mRingingCall.isRinging()) {
                            result = PhoneUtils.hangupRingingCall(mPhone);
                        } else {
                            result = PhoneUtils.hangupHoldingCall(mPhone);
                        }
                        if (result) {
                            return new AtCommandResult(AtCommandResult.OK);
                        } else {
                            return new AtCommandResult(AtCommandResult.ERROR);
                        }
                    } else if (args[0].equals(1)) {
                        // Hangup active call, answer held call
                        if (PhoneUtils.answerAndEndActive(mPhone)) {
                            return new AtCommandResult(AtCommandResult.OK);
                        } else {
                            return new AtCommandResult(AtCommandResult.ERROR);
                        }
                    } else if (args[0].equals(2)) {
                        PhoneUtils.switchHoldingAndActive(mPhone);
                        return new AtCommandResult(AtCommandResult.OK);
                    } else if (args[0].equals(3)) {
                        if (mForegroundCall.getState().isAlive() &&
                            mBackgroundCall.getState().isAlive()) {
                            PhoneUtils.mergeCalls(mPhone);
                        }
                        return new AtCommandResult(AtCommandResult.OK);
                    }
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CHLD: (0,1,2,3)");
            }
        });

        // Get Network operator name
        parser.register("+COPS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                String operatorName = mPhone.getServiceState().getOperatorAlphaLong();
                if (operatorName != null) {
                    if (operatorName.length() > 16) {
                        operatorName = operatorName.substring(0, 16);
                    }
                    return new AtCommandResult(
                            "+COPS: 0,0,\"" + operatorName + "\"");
                } else {
                    return new AtCommandResult(
                            "+COPS: 0,0,\"UNKNOWN\",0");
                }
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // Handsfree only supports AT+COPS=3,0
                if (args.length != 2 || !(args[0] instanceof Integer)
                    || !(args[1] instanceof Integer)) {
                    // syntax error
                    return new AtCommandResult(AtCommandResult.ERROR);
                } else if ((Integer)args[0] != 3 || (Integer)args[1] != 0) {
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                } else {
                    return new AtCommandResult(AtCommandResult.OK);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                // Out of spec, but lets be friendly
                return new AtCommandResult("+COPS: (3),(0)");
            }
        });

        // Mobile PIN
        // AT+CPIN is not in the handsfree spec (although it is in 3GPP)
        parser.register("+CPIN", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult("+CPIN: READY");
            }
        });

        // Bluetooth Response and Hold
        // Only supported on PDC (Japan) and CDMA networks.
        parser.register("+BTRH", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                // Replying with just OK indicates no response and hold
                // features in use now
                return new AtCommandResult(AtCommandResult.OK);
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // Neeed PDC or CDMA
                return new AtCommandResult(AtCommandResult.ERROR);
            }
        });

        // Request International Mobile Subscriber Identity (IMSI)
        // Not in bluetooth handset spec
        parser.register("+CIMI", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // AT+CIMI
                String imsi = mPhone.getSubscriberId();
                if (imsi == null || imsi.length() == 0) {
                    return reportAtError(CME_ERROR_SIM_FAILURE);
                } else {
                    return new AtCommandResult(imsi);
                }
            }
        });

        // Calling Line Identification Presentation
        parser.register("+CLIP", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                // Currently assumes the network is provisioned for CLIP
                return new AtCommandResult("+CLIP: " + (mClip ? "1" : "0") + ",1");
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+CLIP=<n>
                if (args.length >= 1 && (args[0].equals(0) || args[0].equals(1))) {
                    mClip = args[0].equals(1);
                    return new AtCommandResult(AtCommandResult.OK);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+CLIP: (0-1)");
            }
        });

        // Noise Reduction and Echo Cancellation control
        parser.register("+NREC", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args[0].equals(0)) {
                    mAudioManager.setParameter(HEADSET_NREC, "off");
                    return new AtCommandResult(AtCommandResult.OK);
                } else if (args[0].equals(1)) {
                    mAudioManager.setParameter(HEADSET_NREC, "on");
                    return new AtCommandResult(AtCommandResult.OK);
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
        });

        // Retrieve Subscriber Number
        parser.register("+CNUM", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                String pb = "\"ME\"";
                PhoneBookEntry pbe = mPhoneBooks.get(pb);
                initPhoneBookEntry(pb, pbe);

                final Cursor phones = pbe.mPhonebookCursor;
                if (phones.moveToNext()) {
                    String number =
                            phones.getString(pbe.mCursorNumberColumn);
                    int type =
                            phones.getInt(pbe.mCursorNumberTypeColumn);
                    // 4 -- voice, 5 -- fax
                    type = (type != PhonesColumns.TYPE_FAX_WORK) ? 4 : 5;
                    return new AtCommandResult(
                            "+CNUM: ,\"" + number + "\"," +
                            PhoneNumberUtils.toaFromString(number) +
                            ",," + type);
                }
                return new AtCommandResult(AtCommandResult.OK);
            }
        });

        // Phone activity status
        parser.register("+CPAS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                int status = 0;
                switch (mPhone.getState()) {
                case IDLE:
                    status = 0;
                    break;
                case RINGING:
                    status = 3;
                    break;
                case OFFHOOK:
                    status = 4;
                    break;
                }
                return new AtCommandResult("+CPAS: " + status);
            }
        });

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
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
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
                if (mSelectedPB == null) {
                    return reportAtError(CME_ERROR_OPERATION_NOT_ALLOWED);
                } else {
                    if (mSelectedPB.equals("\"SM\"")) {
                        return new AtCommandResult("+CPBS: \"SM\",0," + MAX_PHONEBOOK_SIZE);
                    }
                    PhoneBookEntry pbe = mPhoneBooks.get(mSelectedPB);
                    if (!initPhoneBookEntry(mSelectedPB, pbe)) {
                        return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                    } else {
                        int size = pbe.mPhonebookCursor.getCount();
                        return new AtCommandResult("+CPBS: " + mSelectedPB + "," + size +
                                                   "," + MAX_PHONEBOOK_SIZE);
                    }
                }
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args.length < 1) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
                mSelectedPB = (String)args[0];
                if (mSelectedPB.equals("\"SM\"")) {
                    return new AtCommandResult(AtCommandResult.OK);
                }
                if (!mPhoneBooks.containsKey(mSelectedPB)) {
                    mSelectedPB = null;
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                } else {
                    return new AtCommandResult(AtCommandResult.OK);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                // DC -- dialled calls
                // RC -- received calls
                // MC -- missed (or unanswered) calls
                // ME -- MT phonebook
                // SM -- SIM phonebook   ** special cased until we get support
                return new AtCommandResult(
                        "+CPBS: (\"ME\",\"SM\",\"DC\",\"RC\",\"MC\")");
            }
        });


        // Read PhoneBook Entries
        parser.register("+CPBR", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+CPBR=<index1>[,<index2>]
                // Phone Book Read Request
                if (mSelectedPB == null) {
                    return reportAtError(CME_ERROR_OPERATION_NOT_ALLOWED);
                } else if (args.length < 2 || !(args[0] instanceof Integer) ||
                           !(args[1] instanceof Integer)) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }

                if (mSelectedPB.equals("\"SM\"")) {
                    return new AtCommandResult(AtCommandResult.OK);
                }

                PhoneBookEntry pbe = mPhoneBooks.get(mSelectedPB);
                int errorDetected = -1; // no error
                if (pbe.mPhonebookCursor.getCount() > 0) {
                    // Parse out index1 and index2
                    if (args.length < 1) {
                        return new AtCommandResult(AtCommandResult.ERROR);
                    }
                    if (!(args[0] instanceof Integer)) {
                        return reportAtError(CME_ERROR_TEXT_HAS_INVALID_CHARS);
                    }
                    int index1 = (Integer)args[0];
                    int index2 = index1;
                    if (args.length >= 2) {
                        if (args[1] instanceof Integer) {
                            index2 = (Integer)args[1];
                        } else {
                            return reportAtError(CME_ERROR_TEXT_HAS_INVALID_CHARS);
                        }
                    }

                    // Process
                    pbe.mPhonebookCursor.moveToPosition(index1 - 1);
                    AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
                    while (index1 <= index2) {
                        String number = pbe.mPhonebookCursor.getString(pbe.mCursorNumberColumn);
                        String name = null;
                        int type = -1; // will show up as unknown
                        if (pbe.mCursorNameColumn == -1) {
                            // Do a caller ID lookup
                            Cursor c = mPhone.getContext().getContentResolver().query(
                                    Uri.withAppendedPath(Phones.CONTENT_FILTER_URL, number),
                                    new String[] {Phones.NAME, Phones.TYPE}, null, null, null);
                            if (c != null) {
                                if (c.moveToFirst()) {
                                    name = c.getString(0);
                                    type = c.getInt(1);
                                }
                                c.close();
                            }
                        } else {
                            name = pbe.mPhonebookCursor.getString(pbe.mCursorNameColumn);
                        }
                        if (TextUtils.isEmpty(name)) {
                            name = "unknown";
                        } else {
                            name = name.trim();
                            if (name.length() > 28) name = name.substring(0, 28);
                            if (pbe.mCursorNumberTypeColumn != -1) {
                                type = pbe.mPhonebookCursor.getInt(pbe.mCursorNumberTypeColumn);
                            }
                            name = name + "/" + getPhoneType(type);
                        }

                        int regionType = PhoneNumberUtils.toaFromString(number);

                        number = number.trim();
                        if (number.length() > 30) number = number.substring(0, 30);

                        result.addResponse("+CPBR: " + index1 + ",\"" + number + "\"," +
                                           regionType + ",\"" + name + "\"");
                        if (pbe.mPhonebookCursor.moveToNext() == false) {
                            break;
                        }
                        index1++;
                    }
                    return result;
                } else {
                    if (DBG) log("No phone book entries for " + mSelectedPB);
                    return new AtCommandResult(AtCommandResult.OK);
                }
            }
            @Override
            public AtCommandResult handleTestCommand() {
                // Obtain the number of calls according to the
                // phone book type.  We save the result and just return the number
                // of entries in the respective list.

                if (mSelectedPB == null) {
                    return reportAtError(CME_ERROR_OPERATION_NOT_ALLOWED);
                }
                if (mSelectedPB.equals("\"SM\"")) {
                    return new AtCommandResult(AtCommandResult.OK);
                }
                if (!mPhoneBooks.containsKey(mSelectedPB)) {
                    mSelectedPB = null;
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                }

                PhoneBookEntry pbe = mPhoneBooks.get(mSelectedPB);
                int numEntries = 0;

                if (!initPhoneBookEntry(mSelectedPB, pbe)) {
                    return reportAtError(CME_ERROR_OPERATION_NOT_SUPPORTED);
                }

                numEntries = pbe.mPhonebookCursor.getCount();
                if (numEntries > 0) {
                    return new AtCommandResult("+CPBR: (1-" + numEntries + "),30,30");
                }
                return new AtCommandResult(AtCommandResult.OK);
            }
        });
    }

    private static final int START_CALL_TIMEOUT = 10000;  // ms

    private synchronized void waitForCallStart() {
        mWaitingForCallStart = true;
        Message msg = Message.obtain(mHandler, CHECK_CALL_STARTED);
        mHandler.sendMessageDelayed(msg, START_CALL_TIMEOUT);
        if (!mStartCallWakeLock.isHeld()) {
            mStartCallWakeLock.acquire(START_CALL_TIMEOUT);
        }
    }

    private synchronized void callStarted() {
        if (mWaitingForCallStart) {
            mWaitingForCallStart = false;
            sendURC("OK");
            if (mStartCallWakeLock.isHeld()) {
                mStartCallWakeLock.release();
            }
        }
    }

    private DebugThread mDebugThread;

    private boolean inDebug() {
        return DBG && SystemProperties.getBoolean(DebugThread.DEBUG_HANDSFREE, false);
    }

    private boolean allowAudioAnytime() {
        return inDebug() && SystemProperties.getBoolean(DebugThread.DEBUG_HANDSFREE_AUDIO_ANYTIME,
                false);
    }

    private void startDebug() {
        if (DBG && mDebugThread == null) {
            mDebugThread = new DebugThread();
            mDebugThread.start();
        }
    }

    private void stopDebug() {
        if (mDebugThread != null) {
            mDebugThread.interrupt();
            mDebugThread = null;
        }
    }

    /** Debug thread to read debug properties - runs when debug.bt.hfp is true
     *  at the time a bluetooth handsfree device is connected. Debug properties
     *  are polled and mock updates sent every 1 second */
    private class DebugThread extends Thread {
        /** Turns on/off handsfree profile debugging mode */
        private static final String DEBUG_HANDSFREE = "debug.bt.hfp";

        /** Mock battery level change - use 0 to 5 */
        private static final String DEBUG_HANDSFREE_BATTERY = "debug.bt.hfp.battery";

        /** Mock no cellular service when false */
        private static final String DEBUG_HANDSFREE_SERVICE = "debug.bt.hfp.service";

        /** Mock cellular roaming when true */
        private static final String DEBUG_HANDSFREE_ROAM = "debug.bt.hfp.roam";

        /** false to true transition will force an audio (SCO) connection to
         *  be established. true to false will force audio to be disconnected
         */
        private static final String DEBUG_HANDSFREE_AUDIO = "debug.bt.hfp.audio";

        /** true allows incoming SCO connection out of call.
         */
        private static final String DEBUG_HANDSFREE_AUDIO_ANYTIME = "debug.bt.hfp.audio_anytime";

        /** Mock signal strength change in ASU - use 0 to 31 */
        private static final String DEBUG_HANDSFREE_SIGNAL = "debug.bt.hfp.signal";

        /** Debug AT+CLCC: print +CLCC result */
        private static final String DEBUG_HANDSFREE_CLCC = "debug.bt.hfp.clcc";

        @Override
        public void run() {
            boolean oldService = true;
            boolean oldRoam = false;
            boolean oldAudio = false;

            while (!isInterrupted() && inDebug()) {
                int batteryLevel = SystemProperties.getInt(DEBUG_HANDSFREE_BATTERY, -1);
                if (batteryLevel >= 0 && batteryLevel <= 5) {
                    Intent intent = new Intent();
                    intent.putExtra("level", batteryLevel);
                    intent.putExtra("scale", 5);
                    mPhoneState.updateBatteryState(intent);
                }

                boolean serviceStateChanged = false;
                if (SystemProperties.getBoolean(DEBUG_HANDSFREE_SERVICE, true) != oldService) {
                    oldService = !oldService;
                    serviceStateChanged = true;
                }
                if (SystemProperties.getBoolean(DEBUG_HANDSFREE_ROAM, false) != oldRoam) {
                    oldRoam = !oldRoam;
                    serviceStateChanged = true;
                }
                if (serviceStateChanged) {
                    Bundle b = new Bundle();
                    b.putString("state", oldService ? "IN_SERVICE" : "OUT_OF_SERVICE");
                    b.putBoolean("roaming", oldRoam);
                    mPhoneState.updateServiceState(true, ServiceState.newFromBundle(b));
                }

                if (SystemProperties.getBoolean(DEBUG_HANDSFREE_AUDIO, false) != oldAudio) {
                    oldAudio = !oldAudio;
                    if (oldAudio) {
                        audioOn();
                    } else {
                        audioOff();
                    }
                }

                int signalLevel = SystemProperties.getInt(DEBUG_HANDSFREE_SIGNAL, -1);
                if (signalLevel >= 0 && signalLevel <= 31) {
                    Intent intent = new Intent();
                    intent.putExtra("asu", signalLevel);
                    mPhoneState.updateSignalState(intent);
                }

                if (SystemProperties.getBoolean(DEBUG_HANDSFREE_CLCC, false)) {
                    log(getClccResult().toString());
                }
                try {
                    sleep(1000);  // 1 second
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    };

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
