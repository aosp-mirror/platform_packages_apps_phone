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
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.HeadsetBase;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.os.SystemProperties;
import android.telephony.PhoneNumberUtils;
import android.telephony.ServiceState;
import android.telephony.SignalStrength;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.telephony.CallManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;

/**
 * Bluetooth headset manager for the Phone app.
 * @hide
 */
public class BluetoothHandsfree {
    private static final String TAG = "Bluetooth HS/HF";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 1)
            && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneApp.DBG_LEVEL >= 2);  // even more logging

    public static final int TYPE_UNKNOWN           = 0;
    public static final int TYPE_HEADSET           = 1;
    public static final int TYPE_HANDSFREE         = 2;

    /** The singleton instance. */
    private static BluetoothHandsfree sInstance;

    private final Context mContext;
    private final BluetoothAdapter mAdapter;
    private final CallManager mCM;
    private BluetoothA2dp mA2dp;

    private BluetoothDevice mA2dpDevice;
    private int mA2dpState;
    private boolean mPendingAudioState;
    private int mAudioState;

    private ServiceState mServiceState;
    private HeadsetBase mHeadset;
    private BluetoothHeadset mBluetoothHeadset;
    private int mHeadsetType;   // TYPE_UNKNOWN when not connected
    private boolean mAudioPossible;
    private BluetoothSocket mConnectedSco;

    private IncomingScoAcceptThread mIncomingScoThread = null;
    private ScoSocketConnectThread mConnectScoThread = null;
    private SignalScoCloseThread mSignalScoCloseThread = null;

    private AudioManager mAudioManager;
    private PowerManager mPowerManager;

    private boolean mPendingSco;  // waiting for a2dp sink to suspend before establishing SCO
    private boolean mA2dpSuspended;
    private boolean mUserWantsAudio;
    private WakeLock mStartCallWakeLock;  // held while waiting for the intent to start call
    private WakeLock mStartVoiceRecognitionWakeLock;  // held while waiting for voice recognition

    // AT command state
    private static final int GSM_MAX_CONNECTIONS = 6;  // Max connections allowed by GSM
    private static final int CDMA_MAX_CONNECTIONS = 2;  // Max connections allowed by CDMA

    private long mBgndEarliestConnectionTime = 0;
    private boolean mClip = false;  // Calling Line Information Presentation
    private boolean mIndicatorsEnabled = false;
    private boolean mCmee = false;  // Extended Error reporting
    private long[] mClccTimestamps; // Timestamps associated with each clcc index
    private boolean[] mClccUsed;     // Is this clcc index in use
    private boolean mWaitingForCallStart;
    private boolean mWaitingForVoiceRecognition;
    // do not connect audio until service connection is established
    // for 3-way supported devices, this is after AT+CHLD
    // for non-3-way supported devices, this is after AT+CMER (see spec)
    private boolean mServiceConnectionEstablished;

    private final BluetoothPhoneState mBluetoothPhoneState;  // for CIND and CIEV updates
    private final BluetoothAtPhonebook mPhonebook;
    private Phone.State mPhoneState = Phone.State.IDLE;
    CdmaPhoneCallState.PhoneCallState mCdmaThreeWayCallState =
                                            CdmaPhoneCallState.PhoneCallState.IDLE;

    private DebugThread mDebugThread;
    private int mScoGain = Integer.MIN_VALUE;

    private static Intent sVoiceCommandIntent;

    // Audio parameters
    private static final String HEADSET_NREC = "bt_headset_nrec";
    private static final String HEADSET_NAME = "bt_headset_name";

    private int mRemoteBrsf = 0;
    private int mLocalBrsf = 0;

    // CDMA specific flag used in context with BT devices having display capabilities
    // to show which Caller is active. This state might not be always true as in CDMA
    // networks if a caller drops off no update is provided to the Phone.
    // This flag is just used as a toggle to provide a update to the BT device to specify
    // which caller is active.
    private boolean mCdmaIsSecondCallActive = false;
    private boolean mCdmaCallsSwapped = false;

    /* Constants from Bluetooth Specification Hands-Free profile version 1.5 */
    private static final int BRSF_AG_THREE_WAY_CALLING = 1 << 0;
    private static final int BRSF_AG_EC_NR = 1 << 1;
    private static final int BRSF_AG_VOICE_RECOG = 1 << 2;
    private static final int BRSF_AG_IN_BAND_RING = 1 << 3;
    private static final int BRSF_AG_VOICE_TAG_NUMBE = 1 << 4;
    private static final int BRSF_AG_REJECT_CALL = 1 << 5;
    private static final int BRSF_AG_ENHANCED_CALL_STATUS = 1 <<  6;
    private static final int BRSF_AG_ENHANCED_CALL_CONTROL = 1 << 7;
    private static final int BRSF_AG_ENHANCED_ERR_RESULT_CODES = 1 << 8;

    private static final int BRSF_HF_EC_NR = 1 << 0;
    private static final int BRSF_HF_CW_THREE_WAY_CALLING = 1 << 1;
    private static final int BRSF_HF_CLIP = 1 << 2;
    private static final int BRSF_HF_VOICE_REG_ACT = 1 << 3;
    private static final int BRSF_HF_REMOTE_VOL_CONTROL = 1 << 4;
    private static final int BRSF_HF_ENHANCED_CALL_STATUS = 1 <<  5;
    private static final int BRSF_HF_ENHANCED_CALL_CONTROL = 1 << 6;

    // VirtualCall - true if Virtual Call is active, false otherwise
    private boolean mVirtualCallStarted = false;

    // Voice Recognition - true if Voice Recognition is active, false otherwise
    private boolean mVoiceRecognitionStarted;


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

    /**
     * Initialize the singleton BluetoothHandsfree instance.
     * This is only done once, at startup, from PhoneApp.onCreate().
     */
    /* package */ static BluetoothHandsfree init(Context context, CallManager cm) {
        synchronized (BluetoothHandsfree.class) {
            if (sInstance == null) {
                sInstance = new BluetoothHandsfree(context, cm);
            } else {
                Log.wtf(TAG, "init() called multiple times!  sInstance = " + sInstance);
            }
            return sInstance;
        }
    }

    /** Private constructor; @see init() */
    private BluetoothHandsfree(Context context, CallManager cm) {
        mCM = cm;
        mContext = context;
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        boolean bluetoothCapable = (mAdapter != null);
        mHeadset = null;
        mHeadsetType = TYPE_UNKNOWN; // nothing connected yet
        if (bluetoothCapable) {
            mAdapter.getProfileProxy(mContext, mProfileListener,
                                     BluetoothProfile.A2DP);
        }
        mA2dpState = BluetoothA2dp.STATE_DISCONNECTED;
        mA2dpDevice = null;
        mA2dpSuspended = false;

        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        mStartCallWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                       TAG + ":StartCall");
        mStartCallWakeLock.setReferenceCounted(false);
        mStartVoiceRecognitionWakeLock = mPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                       TAG + ":VoiceRecognition");
        mStartVoiceRecognitionWakeLock.setReferenceCounted(false);

        mLocalBrsf = BRSF_AG_THREE_WAY_CALLING |
                     BRSF_AG_EC_NR |
                     BRSF_AG_REJECT_CALL |
                     BRSF_AG_ENHANCED_CALL_STATUS;

        if (sVoiceCommandIntent == null) {
            sVoiceCommandIntent = new Intent(Intent.ACTION_VOICE_COMMAND);
            sVoiceCommandIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        if (mContext.getPackageManager().resolveActivity(sVoiceCommandIntent, 0) != null &&
                BluetoothHeadset.isBluetoothVoiceDialingEnabled(mContext)) {
            mLocalBrsf |= BRSF_AG_VOICE_RECOG;
        }

        mBluetoothPhoneState = new BluetoothPhoneState();
        mUserWantsAudio = true;
        mVirtualCallStarted = false;
        mVoiceRecognitionStarted = false;
        mPhonebook = new BluetoothAtPhonebook(mContext, this);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        cdmaSetSecondCallState(false);

        if (bluetoothCapable) {
            resetAtState();
        }

    }

    /**
     * A thread that runs in the background waiting for a Sco Server Socket to
     * accept a connection. Even after a connection has been accepted, the Sco Server
     * continues to listen for new connections.
     */
    private class IncomingScoAcceptThread extends Thread{
        private final BluetoothServerSocket mIncomingServerSocket;
        private BluetoothSocket mIncomingSco;
        private boolean stopped = false;

        public IncomingScoAcceptThread() {
            BluetoothServerSocket serverSocket = null;
            try {
                serverSocket = BluetoothAdapter.listenUsingScoOn();
            } catch (IOException e) {
                Log.e(TAG, "Could not create BluetoothServerSocket");
                stopped = true;
            }
            mIncomingServerSocket = serverSocket;
        }

        @Override
        public void run() {
            while (!stopped) {
                try {
                    mIncomingSco = mIncomingServerSocket.accept();
                } catch (IOException e) {
                    Log.e(TAG, "BluetoothServerSocket could not accept connection");
                }

                if (mIncomingSco != null) {
                    connectSco();
                }
            }
        }

        private void connectSco() {
            synchronized (BluetoothHandsfree.this) {
                if (!Thread.interrupted() && isHeadsetConnected() &&
                    (mAudioPossible || allowAudioAnytime()) &&
                    mConnectedSco == null) {
                    Log.i(TAG, "Routing audio for incoming SCO connection");
                    mConnectedSco = mIncomingSco;
                    mAudioManager.setBluetoothScoOn(true);
                    setAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTED,
                        mHeadset.getRemoteDevice());

                    if (mSignalScoCloseThread == null) {
                        mSignalScoCloseThread = new SignalScoCloseThread();
                        mSignalScoCloseThread.setName("SignalScoCloseThread");
                        mSignalScoCloseThread.start();
                    }
                } else {
                    Log.i(TAG, "Rejecting incoming SCO connection");
                    try {
                        mIncomingSco.close();
                    }catch (IOException e) {
                        Log.e(TAG, "Error when closing incoming Sco socket");
                    }
                    mIncomingSco = null;
                }
            }
        }

        // must be called with BluetoothHandsfree locked
        void shutdown() {
            try {
                mIncomingServerSocket.close();
            } catch (IOException e) {
                Log.w(TAG, "Error when closing server socket");
            }
            stopped = true;
            interrupt();
        }
    }

    /**
     * A thread that runs in the background waiting for a Sco Socket to
     * connect.Once the socket is connected, this thread shall be
     * shutdown.
     */
    private class ScoSocketConnectThread extends Thread{
        private BluetoothSocket mOutgoingSco;

        public ScoSocketConnectThread(BluetoothDevice device) {
            try {
                mOutgoingSco = device.createScoSocket();
            } catch (IOException e) {
                Log.w(TAG, "Could not create BluetoothSocket");
                failedScoConnect();
            }
        }

        @Override
        public void run() {
            try {
                mOutgoingSco.connect();
            }catch (IOException connectException) {
                Log.e(TAG, "BluetoothSocket could not connect");
                mOutgoingSco = null;
                failedScoConnect();
            }

            if (mOutgoingSco != null) {
                connectSco();
            }
        }

        private void connectSco() {
            synchronized (BluetoothHandsfree.this) {
                if (!Thread.interrupted() && isHeadsetConnected() && mConnectedSco == null) {
                    if (VDBG) log("Routing audio for outgoing SCO conection");
                    mConnectedSco = mOutgoingSco;
                    mAudioManager.setBluetoothScoOn(true);

                    setAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTED,
                      mHeadset.getRemoteDevice());

                    if (mSignalScoCloseThread == null) {
                        mSignalScoCloseThread = new SignalScoCloseThread();
                        mSignalScoCloseThread.setName("SignalScoCloseThread");
                        mSignalScoCloseThread.start();
                    }
                } else {
                    if (VDBG) log("Rejecting new connected outgoing SCO socket");
                    try {
                        mOutgoingSco.close();
                    }catch (IOException e) {
                        Log.e(TAG, "Error when closing Sco socket");
                    }
                    mOutgoingSco = null;
                    failedScoConnect();
                }
            }
        }

        private void failedScoConnect() {
            // Wait for couple of secs before sending AUDIO_STATE_DISCONNECTED,
            // since an incoming SCO connection can happen immediately with
            // certain headsets.
            Message msg = Message.obtain(mHandler, SCO_AUDIO_STATE);
            msg.obj = mHeadset.getRemoteDevice();
            mHandler.sendMessageDelayed(msg, 2000);

            // Sync with interrupt() statement of shutdown method
            // This prevents resetting of a valid mConnectScoThread.
            // If this thread has been interrupted, it has been shutdown and
            // mConnectScoThread is/will be reset by the outer class.
            // We do not want to do it here since mConnectScoThread could be
            // assigned with a new object.
            synchronized (ScoSocketConnectThread.this) {
                if (!isInterrupted()) {
                    resetConnectScoThread();
                }
            }
        }

        // must be called with BluetoothHandsfree locked
        void shutdown() {
            closeConnectedSco();

            // sync with isInterrupted() check in failedScoConnect method
            // see explanation there
            synchronized (ScoSocketConnectThread.this) {
                interrupt();
            }
        }
    }

    /*
     * Signals when a Sco connection has been closed
     */
    private class SignalScoCloseThread extends Thread{
        private boolean stopped = false;

        @Override
        public void run() {
            while (!stopped) {
                BluetoothSocket connectedSco = null;
                synchronized (BluetoothHandsfree.this) {
                    connectedSco = mConnectedSco;
                }
                if (connectedSco != null) {
                    byte b[] = new byte[1];
                    InputStream inStream = null;
                    try {
                        inStream = connectedSco.getInputStream();
                    } catch (IOException e) {}

                    if (inStream != null) {
                        try {
                            // inStream.read is a blocking call that won't ever
                            // return anything, but will throw an exception if the
                            // connection is closed
                            int ret = inStream.read(b, 0, 1);
                        }catch (IOException connectException) {
                            // call a message to close this thread and turn off audio
                            // we can't call audioOff directly because then
                            // the thread would try to close itself
                            Message msg = Message.obtain(mHandler, SCO_CLOSED);
                            mHandler.sendMessage(msg);
                            break;
                        }
                    }
                }
            }
        }

        // must be called with BluetoothHandsfree locked
        void shutdown() {
            stopped = true;
            closeConnectedSco();
            interrupt();
        }
    }

    private void connectScoThread(){
        // Sync with setting mConnectScoThread to null to assure the validity of
        // the condition
        synchronized (ScoSocketConnectThread.class) {
            if (mConnectScoThread == null) {
                BluetoothDevice device = mHeadset.getRemoteDevice();
                if (getAudioState(device) == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    setAudioState(BluetoothHeadset.STATE_AUDIO_CONNECTING, device);
                }

                mConnectScoThread = new ScoSocketConnectThread(mHeadset.getRemoteDevice());
                mConnectScoThread.setName("HandsfreeScoSocketConnectThread");

                mConnectScoThread.start();
            }
        }
    }

    private void resetConnectScoThread() {
        // Sync with if (mConnectScoThread == null) check
        synchronized (ScoSocketConnectThread.class) {
            mConnectScoThread = null;
        }
    }

    // must be called with BluetoothHandsfree locked
    private void closeConnectedSco() {
        if (mConnectedSco != null) {
            try {
                mConnectedSco.close();
            } catch (IOException e) {
                Log.e(TAG, "Error when closing Sco socket");
            }

            BluetoothDevice device = null;
            if (mHeadset != null) {
                device = mHeadset.getRemoteDevice();
            }
            mAudioManager.setBluetoothScoOn(false);
            setAudioState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, device);

            mConnectedSco = null;
        }
    }

    /* package */ synchronized void onBluetoothEnabled() {
        /* Bluez has a bug where it will always accept and then orphan
         * incoming SCO connections, regardless of whether we have a listening
         * SCO socket. So the best thing to do is always run a listening socket
         * while bluetooth is on so that at least we can disconnect it
         * immediately when we don't want it.
         */

        if (mIncomingScoThread == null) {
            mIncomingScoThread = new IncomingScoAcceptThread();
            mIncomingScoThread.setName("incomingScoAcceptThread");
            mIncomingScoThread.start();
        }
    }

    /* package */ synchronized void onBluetoothDisabled() {
        // Close off the SCO sockets
        audioOff();

        if (mIncomingScoThread != null) {
            mIncomingScoThread.shutdown();
            mIncomingScoThread = null;
        }
    }

    private boolean isHeadsetConnected() {
        if (mHeadset == null || mHeadsetType == TYPE_UNKNOWN) {
            return false;
        }
        return mHeadset.isConnected();
    }

    /* package */ synchronized void connectHeadset(HeadsetBase headset, int headsetType) {
        mHeadset = headset;
        mHeadsetType = headsetType;
        if (mHeadsetType == TYPE_HEADSET) {
            initializeHeadsetAtParser();
        } else {
            initializeHandsfreeAtParser();
        }

        // Headset vendor-specific commands
        registerAllVendorSpecificCommands();

        headset.startEventThread();
        configAudioParameters();

        if (inDebug()) {
            startDebug();
        }

        if (isIncallAudio()) {
            audioOn();
        } else if ( mCM.getFirstActiveRingingCall().isRinging()) {
            // need to update HS with RING when single ringing call exist
            mBluetoothPhoneState.ring();
        }
    }

    /* returns true if there is some kind of in-call audio we may wish to route
     * bluetooth to */
    private boolean isIncallAudio() {
        Call.State state = mCM.getActiveFgCallState();

        return (state == Call.State.ACTIVE || state == Call.State.ALERTING);
    }

    /* package */ synchronized void disconnectHeadset() {
        audioOff();

        // No need to check if isVirtualCallInProgress()
        // terminateScoUsingVirtualVoiceCall() does the check
        terminateScoUsingVirtualVoiceCall();

        mHeadsetType = TYPE_UNKNOWN;
        stopDebug();
        resetAtState();
    }

    /* package */ synchronized void resetAtState() {
        mClip = false;
        mIndicatorsEnabled = false;
        mServiceConnectionEstablished = false;
        mCmee = false;
        mClccTimestamps = new long[GSM_MAX_CONNECTIONS];
        mClccUsed = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            mClccUsed[i] = false;
        }
        mRemoteBrsf = 0;
        mPhonebook.resetAtState();
    }

    /* package */ HeadsetBase getHeadset() {
        return mHeadset;
    }

    private void configAudioParameters() {
        String name = mHeadset.getRemoteDevice().getName();
        if (name == null) {
            name = "<unknown>";
        }
        mAudioManager.setParameters(HEADSET_NAME+"="+name+";"+HEADSET_NREC+"=on");
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
        private boolean mStopRing = false;

        // current or last call start timestamp
        private long mCallStartTime = 0;
        // time window to reconnect remotely-disconnected SCO
        // in mili-seconds
        private static final int RETRY_SCO_TIME_WINDOW = 1000;

        private static final int SERVICE_STATE_CHANGED = 1;
        private static final int PRECISE_CALL_STATE_CHANGED = 2;
        private static final int RING = 3;
        private static final int PHONE_CDMA_CALL_WAITING = 4;
        private static final int BATTERY_CHANGED = 5;
        private static final int SIGNAL_STRENGTH_CHANGED = 6;

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
                case PRECISE_CALL_STATE_CHANGED:
                case PHONE_CDMA_CALL_WAITING:
                    Connection connection = null;
                    if (((AsyncResult) msg.obj).result instanceof Connection) {
                        connection = (Connection) ((AsyncResult) msg.obj).result;
                    }
                    handlePreciseCallStateChange(sendUpdate(), connection);
                    break;
                case BATTERY_CHANGED:
                    updateBatteryState((Intent) msg.obj);
                    break;
                case SIGNAL_STRENGTH_CHANGED:
                    updateSignalState((Intent) msg.obj);
                    break;
                }
            }
        };

        private BluetoothPhoneState() {
            // init members
            // TODO May consider to repalce the default phone's state and signal
            //      by CallManagter's state and signal
            updateServiceState(false, mCM.getDefaultPhone().getServiceState());
            handlePreciseCallStateChange(false, null);
            mBattchg = 5;  // There is currently no API to get battery level
                           // on demand, so set to 5 and wait for an update
            mSignal = asuToSignal(mCM.getDefaultPhone().getSignalStrength());

            // register for updates
            // Use the service state of default phone as BT service state to
            // avoid situation such as no cell or wifi connection but still
            // reporting in service (since SipPhone always reports in service).
            mCM.getDefaultPhone().registerForServiceStateChanged(mStateChangeHandler,
                                                  SERVICE_STATE_CHANGED, null);
            mCM.registerForPreciseCallStateChanged(mStateChangeHandler,
                    PRECISE_CALL_STATE_CHANGED, null);
            mCM.registerForCallWaiting(mStateChangeHandler,
                PHONE_CDMA_CALL_WAITING, null);

            IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED);
            filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
            mContext.registerReceiver(mStateReceiver, filter);
        }

        private void updateBtPhoneStateAfterRadioTechnologyChange() {
            if(VDBG) Log.d(TAG, "updateBtPhoneStateAfterRadioTechnologyChange...");

            //Unregister all events from the old obsolete phone
            mCM.getDefaultPhone().unregisterForServiceStateChanged(mStateChangeHandler);
            mCM.unregisterForPreciseCallStateChanged(mStateChangeHandler);
            mCM.unregisterForCallWaiting(mStateChangeHandler);

            //Register all events new to the new active phone
            mCM.getDefaultPhone().registerForServiceStateChanged(mStateChangeHandler,
                                                  SERVICE_STATE_CHANGED, null);
            mCM.registerForPreciseCallStateChanged(mStateChangeHandler,
                    PRECISE_CALL_STATE_CHANGED, null);
            mCM.registerForCallWaiting(mStateChangeHandler,
                PHONE_CDMA_CALL_WAITING, null);
        }

        private boolean sendUpdate() {
            return isHeadsetConnected() && mHeadsetType == TYPE_HANDSFREE && mIndicatorsEnabled
                   && mServiceConnectionEstablished;
        }

        private boolean sendClipUpdate() {
            return isHeadsetConnected() && mHeadsetType == TYPE_HANDSFREE && mClip &&
                   mServiceConnectionEstablished;
        }

        private boolean sendRingUpdate() {
            if (isHeadsetConnected() && !mIgnoreRing && !mStopRing &&
                    mCM.getFirstActiveRingingCall().isRinging()) {
                if (mHeadsetType == TYPE_HANDSFREE) {
                    return mServiceConnectionEstablished ? true : false;
                }
                return true;
            }
            return false;
        }

        private void stopRing() {
            mStopRing = true;
        }

        /* convert [0,31] ASU signal strength to the [0,5] expected by
         * bluetooth devices. Scale is similar to status bar policy
         */
        private int gsmAsuToSignal(SignalStrength signalStrength) {
            int asu = signalStrength.getGsmSignalStrength();
            if      (asu >= 16) return 5;
            else if (asu >= 8)  return 4;
            else if (asu >= 4)  return 3;
            else if (asu >= 2)  return 2;
            else if (asu >= 1)  return 1;
            else                return 0;
        }

        /**
         * Convert the cdma / evdo db levels to appropriate icon level.
         * The scale is similar to the one used in status bar policy.
         *
         * @param signalStrength
         * @return the icon level
         */
        private int cdmaDbmEcioToSignal(SignalStrength signalStrength) {
            int levelDbm = 0;
            int levelEcio = 0;
            int cdmaIconLevel = 0;
            int evdoIconLevel = 0;
            int cdmaDbm = signalStrength.getCdmaDbm();
            int cdmaEcio = signalStrength.getCdmaEcio();

            if (cdmaDbm >= -75) levelDbm = 4;
            else if (cdmaDbm >= -85) levelDbm = 3;
            else if (cdmaDbm >= -95) levelDbm = 2;
            else if (cdmaDbm >= -100) levelDbm = 1;
            else levelDbm = 0;

            // Ec/Io are in dB*10
            if (cdmaEcio >= -90) levelEcio = 4;
            else if (cdmaEcio >= -110) levelEcio = 3;
            else if (cdmaEcio >= -130) levelEcio = 2;
            else if (cdmaEcio >= -150) levelEcio = 1;
            else levelEcio = 0;

            cdmaIconLevel = (levelDbm < levelEcio) ? levelDbm : levelEcio;

            if (mServiceState != null &&
                  (mServiceState.getRadioTechnology() == ServiceState.RADIO_TECHNOLOGY_EVDO_0 ||
                   mServiceState.getRadioTechnology() == ServiceState.RADIO_TECHNOLOGY_EVDO_A)) {
                  int evdoEcio = signalStrength.getEvdoEcio();
                  int evdoSnr = signalStrength.getEvdoSnr();
                  int levelEvdoEcio = 0;
                  int levelEvdoSnr = 0;

                  // Ec/Io are in dB*10
                  if (evdoEcio >= -650) levelEvdoEcio = 4;
                  else if (evdoEcio >= -750) levelEvdoEcio = 3;
                  else if (evdoEcio >= -900) levelEvdoEcio = 2;
                  else if (evdoEcio >= -1050) levelEvdoEcio = 1;
                  else levelEvdoEcio = 0;

                  if (evdoSnr > 7) levelEvdoSnr = 4;
                  else if (evdoSnr > 5) levelEvdoSnr = 3;
                  else if (evdoSnr > 3) levelEvdoSnr = 2;
                  else if (evdoSnr > 1) levelEvdoSnr = 1;
                  else levelEvdoSnr = 0;

                  evdoIconLevel = (levelEvdoEcio < levelEvdoSnr) ? levelEvdoEcio : levelEvdoSnr;
            }
            // TODO(): There is a bug open regarding what should be sent.
            return (cdmaIconLevel > evdoIconLevel) ?  cdmaIconLevel : evdoIconLevel;

        }


        private int asuToSignal(SignalStrength signalStrength) {
            if (signalStrength.isGsm()) {
                return gsmAsuToSignal(signalStrength);
            } else {
                return cdmaDbmEcioToSignal(signalStrength);
            }
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
                    Message msg = mStateChangeHandler.obtainMessage(BATTERY_CHANGED, intent);
                    mStateChangeHandler.sendMessage(msg);
                } else if (intent.getAction().equals(
                            TelephonyIntents.ACTION_SIGNAL_STRENGTH_CHANGED)) {
                    Message msg = mStateChangeHandler.obtainMessage(SIGNAL_STRENGTH_CHANGED,
                                                                    intent);
                    mStateChangeHandler.sendMessage(msg);
                } else if (intent.getAction().equals(
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)) {
                    int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                    int oldState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                        BluetoothProfile.STATE_DISCONNECTED);
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);


                    // We are only concerned about Connected sinks to suspend and resume
                    // them. We can safely ignore SINK_STATE_CHANGE for other devices.
                    if (device == null || (mA2dpDevice != null && !device.equals(mA2dpDevice))) {
                        return;
                    }

                    synchronized (BluetoothHandsfree.this) {
                        mA2dpState = state;
                        if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            mA2dpDevice = null;
                        } else {
                            mA2dpDevice = device;
                        }
                        if (oldState == BluetoothA2dp.STATE_PLAYING &&
                            mA2dpState == BluetoothProfile.STATE_CONNECTED) {
                            if (mA2dpSuspended) {
                                if (mPendingSco) {
                                    mHandler.removeMessages(MESSAGE_CHECK_PENDING_SCO);
                                    if (DBG) log("A2DP suspended, completing SCO");
                                    connectScoThread();
                                    mPendingSco = false;
                                }
                            }
                        }
                    }
                } else if (intent.getAction().
                           equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
                    mPhonebook.handleAccessPermissionResult(intent);
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
            // NOTE this function is called by the BroadcastReceiver mStateReceiver after intent
            // ACTION_SIGNAL_STRENGTH_CHANGED and by the DebugThread mDebugThread
            if (!isHeadsetConnected()) {
                return;
            }

            SignalStrength signalStrength = SignalStrength.newFromBundle(intent.getExtras());
            int signal;

            if (signalStrength != null) {
                signal = asuToSignal(signalStrength);
                mRssi = signalToRssi(signal);  // no unsolicited CSQ
                if (signal != mSignal) {
                    mSignal = signal;
                    if (sendUpdate()) {
                        sendURC("+CIEV: 5," + mSignal);
                    }
                }
            } else {
                Log.e(TAG, "Signal Strength null");
            }
        }

        private synchronized void updateServiceState(boolean sendUpdate, ServiceState state) {
            int service = state.getState() == ServiceState.STATE_IN_SERVICE ? 1 : 0;
            int roam = state.getRoaming() ? 1 : 0;
            int stat;
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
            mServiceState = state;
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

        private synchronized void handlePreciseCallStateChange(boolean sendUpdate,
                Connection connection) {
            int call = 0;
            int callsetup = 0;
            int callheld = 0;
            int prevCallsetup = mCallsetup;
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
            Call foregroundCall = mCM.getActiveFgCall();
            Call backgroundCall = mCM.getFirstActiveBgCall();
            Call ringingCall = mCM.getFirstActiveRingingCall();

            if (VDBG) log("updatePhoneState()");

            // This function will get called when the Precise Call State
            // {@link Call.State} changes. Hence, we might get this update
            // even if the {@link Phone.state} is same as before.
            // Check for the same.

            Phone.State newState = mCM.getState();
            if (newState != mPhoneState) {
                mPhoneState = newState;
                switch (mPhoneState) {
                case IDLE:
                    mUserWantsAudio = true;  // out of call - reset state
                    audioOff();
                    break;
                default:
                    callStarted();
                }
            }

            switch(foregroundCall.getState()) {
            case ACTIVE:
                call = 1;
                mAudioPossible = true;
                break;
            case DIALING:
                callsetup = 2;
                mAudioPossible = true;
                // We also need to send a Call started indication
                // for cases where the 2nd MO was initiated was
                // from a *BT hands free* and is waiting for a
                // +BLND: OK response
                // There is a special case handling of the same case
                // for CDMA below
                if (mCM.getFgPhone().getPhoneType() == Phone.PHONE_TYPE_GSM) {
                    callStarted();
                }
                break;
            case ALERTING:
                callsetup = 3;
                // Open the SCO channel for the outgoing call.
                mCallStartTime = System.currentTimeMillis();
                audioOn();
                mAudioPossible = true;
                break;
            case DISCONNECTING:
                // This is a transient state, we don't want to send
                // any AT commands during this state.
                call = mCall;
                callsetup = mCallsetup;
                callheld = mCallheld;
                break;
            default:
                mAudioPossible = false;
            }

            switch(ringingCall.getState()) {
            case INCOMING:
            case WAITING:
                callsetup = 1;
                break;
            case DISCONNECTING:
                // This is a transient state, we don't want to send
                // any AT commands during this state.
                call = mCall;
                callsetup = mCallsetup;
                callheld = mCallheld;
                break;
            }

            switch(backgroundCall.getState()) {
            case HOLDING:
                if (call == 1) {
                    callheld = 1;
                } else {
                    call = 1;
                    callheld = 2;
                }
                break;
            case DISCONNECTING:
                // This is a transient state, we don't want to send
                // any AT commands during this state.
                call = mCall;
                callsetup = mCallsetup;
                callheld = mCallheld;
                break;
            }

            if (mCall != call) {
                if (call == 1) {
                    // This means that a call has transitioned from NOT ACTIVE to ACTIVE.
                    // Switch on audio.
                    mCallStartTime = System.currentTimeMillis();
                    audioOn();
                }
                mCall = call;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 2," + mCall);
                }
            }
            if (mCallsetup != callsetup) {
                mCallsetup = callsetup;
                if (sendUpdate) {
                    // If mCall = 0, send CIEV
                    // mCall = 1, mCallsetup = 0, send CIEV
                    // mCall = 1, mCallsetup = 1, send CIEV after CCWA,
                    // if 3 way supported.
                    // mCall = 1, mCallsetup = 2 / 3 -> send CIEV,
                    // if 3 way is supported
                    if (mCall != 1 || mCallsetup == 0 ||
                        mCallsetup != 1 && (mRemoteBrsf & BRSF_HF_CW_THREE_WAY_CALLING) != 0x0) {
                        result.addResponse("+CIEV: 3," + mCallsetup);
                    }
                }
            }

            if (mCM.getDefaultPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
                PhoneApp app = PhoneApp.getInstance();
                if (app.cdmaPhoneCallState != null) {
                    CdmaPhoneCallState.PhoneCallState currCdmaThreeWayCallState =
                            app.cdmaPhoneCallState.getCurrentCallState();
                    CdmaPhoneCallState.PhoneCallState prevCdmaThreeWayCallState =
                        app.cdmaPhoneCallState.getPreviousCallState();

                    log("CDMA call state: " + currCdmaThreeWayCallState + " prev state:" +
                        prevCdmaThreeWayCallState);
                    callheld = getCdmaCallHeldStatus(currCdmaThreeWayCallState,
                                                     prevCdmaThreeWayCallState);

                    if (mCdmaThreeWayCallState != currCdmaThreeWayCallState) {
                        // In CDMA, the network does not provide any feedback
                        // to the phone when the 2nd MO call goes through the
                        // stages of DIALING > ALERTING -> ACTIVE we fake the
                        // sequence
                        if ((currCdmaThreeWayCallState ==
                                CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                                    && app.cdmaPhoneCallState.IsThreeWayCallOrigStateDialing()) {
                            mAudioPossible = true;
                            if (sendUpdate) {
                                if ((mRemoteBrsf & BRSF_HF_CW_THREE_WAY_CALLING) != 0x0) {
                                    result.addResponse("+CIEV: 3,2");
                                    // Mimic putting the call on hold
                                    result.addResponse("+CIEV: 4,1");
                                    mCallheld = callheld;
                                    result.addResponse("+CIEV: 3,3");
                                    result.addResponse("+CIEV: 3,0");
                                }
                            }
                            // We also need to send a Call started indication
                            // for cases where the 2nd MO was initiated was
                            // from a *BT hands free* and is waiting for a
                            // +BLND: OK response
                            callStarted();
                        }

                        // In CDMA, the network does not provide any feedback to
                        // the phone when a user merges a 3way call or swaps
                        // between two calls we need to send a CIEV response
                        // indicating that a call state got changed which should
                        // trigger a CLCC update request from the BT client.
                        if (currCdmaThreeWayCallState ==
                                CdmaPhoneCallState.PhoneCallState.CONF_CALL &&
                                prevCdmaThreeWayCallState ==
                                  CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                            mAudioPossible = true;
                            if (sendUpdate) {
                                if ((mRemoteBrsf & BRSF_HF_CW_THREE_WAY_CALLING) != 0x0) {
                                    result.addResponse("+CIEV: 2,1");
                                    result.addResponse("+CIEV: 3,0");
                                }
                            }
                        }
                    }
                    mCdmaThreeWayCallState = currCdmaThreeWayCallState;
                }
            }

            boolean callsSwitched;

            if (mCM.getDefaultPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA &&
                mCdmaThreeWayCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                callsSwitched = mCdmaCallsSwapped;
            } else {
                callsSwitched =
                    (callheld == 1 && ! (backgroundCall.getEarliestConnectTime() ==
                        mBgndEarliestConnectionTime));
                mBgndEarliestConnectionTime = backgroundCall.getEarliestConnectTime();
            }


            if (mCallheld != callheld || callsSwitched) {
                mCallheld = callheld;
                if (sendUpdate) {
                    result.addResponse("+CIEV: 4," + mCallheld);
                }
            }

            if (callsetup == 1 && callsetup != prevCallsetup) {
                // new incoming call
                String number = null;
                int type = 128;
                // find incoming phone number and type
                if (connection == null) {
                    connection = ringingCall.getEarliestConnection();
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
                if ((call != 0 || callheld != 0) && sendUpdate) {
                    // call waiting
                    if ((mRemoteBrsf & BRSF_HF_CW_THREE_WAY_CALLING) != 0x0) {
                        result.addResponse("+CCWA: \"" + number + "\"," + type);
                        result.addResponse("+CIEV: 3," + callsetup);
                    }
                } else {
                    // regular new incoming call
                    mRingingNumber = number;
                    mRingingType = type;
                    mIgnoreRing = false;
                    mStopRing = false;

                    if ((mLocalBrsf & BRSF_AG_IN_BAND_RING) != 0x0) {
                        mCallStartTime = System.currentTimeMillis();
                        audioOn();
                    }
                    result.addResult(ring());
                }
            }
            sendURC(result.toString());
        }

        private int getCdmaCallHeldStatus(CdmaPhoneCallState.PhoneCallState currState,
                                  CdmaPhoneCallState.PhoneCallState prevState) {
            int callheld;
            // Update the Call held information
            if (currState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                if (prevState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                    callheld = 0; //0: no calls held, as now *both* the caller are active
                } else {
                    callheld = 1; //1: held call and active call, as on answering a
                            // Call Waiting, one of the caller *is* put on hold
                }
            } else if (currState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                callheld = 1; //1: held call and active call, as on make a 3 Way Call
                        // the first caller *is* put on hold
            } else {
                callheld = 0; //0: no calls held as this is a SINGLE_ACTIVE call
            }
            return callheld;
        }


        private AtCommandResult ring() {
            if (sendRingUpdate()) {
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

        private synchronized void updateCallHeld() {
            if (mCallheld != 0) {
                mCallheld = 0;
                sendURC("+CIEV: 4,0");
            }
        }

        private synchronized AtCommandResult toCindResult() {
            AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
            int call, call_setup;

            // Handsfree carkits expect that +CIND is properly responded to.
            // Hence we ensure that a proper response is sent for the virtual call too.
            if (isVirtualCallInProgress()) {
                call = 1;
                call_setup = 0;
            } else {
                // regular phone call
                call = mCall;
                call_setup = mCallsetup;
            }

            mSignal = asuToSignal(mCM.getDefaultPhone().getSignalStrength());
            String status = "+CIND: " + mService + "," + call + "," + call_setup + "," +
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

        private void scoClosed() {
            // sync on mUserWantsAudio change
            synchronized(BluetoothHandsfree.this) {
                if (mUserWantsAudio &&
                    System.currentTimeMillis() - mCallStartTime < RETRY_SCO_TIME_WINDOW) {
                    Message msg = mHandler.obtainMessage(SCO_CONNECTION_CHECK);
                    mHandler.sendMessage(msg);
                }
            }
        }
    };

    private static final int SCO_CLOSED = 3;
    private static final int CHECK_CALL_STARTED = 4;
    private static final int CHECK_VOICE_RECOGNITION_STARTED = 5;
    private static final int MESSAGE_CHECK_PENDING_SCO = 6;
    private static final int SCO_AUDIO_STATE = 7;
    private static final int SCO_CONNECTION_CHECK = 8;

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized (BluetoothHandsfree.this) {
                switch (msg.what) {
                case SCO_CLOSED:
                    audioOff();
                    // notify mBluetoothPhoneState that the SCO channel has closed
                    mBluetoothPhoneState.scoClosed();
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
                case CHECK_VOICE_RECOGNITION_STARTED:
                    if (mWaitingForVoiceRecognition) {
                        mWaitingForVoiceRecognition = false;
                        Log.e(TAG, "Timeout waiting for voice recognition to start");
                        sendURC("ERROR");
                    }
                    break;
                case MESSAGE_CHECK_PENDING_SCO:
                    if (mPendingSco && isA2dpMultiProfile()) {
                        Log.w(TAG, "Timeout suspending A2DP for SCO (mA2dpState = " +
                                mA2dpState + "). Starting SCO anyway");
                        connectScoThread();
                        mPendingSco = false;
                    }
                    break;
                case SCO_AUDIO_STATE:
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    if (getAudioState(device) == BluetoothHeadset.STATE_AUDIO_CONNECTING) {
                        setAudioState(BluetoothHeadset.STATE_AUDIO_DISCONNECTED, device);
                    }
                    break;
                case SCO_CONNECTION_CHECK:
                    synchronized (mBluetoothPhoneState) {
                        // synchronized on mCall change
                        if (mBluetoothPhoneState.mCall == 1) {
                            // Sometimes, the SCO channel is torn down by HF with no reason.
                            // Because we are still in active call, reconnect SCO.
                            // audioOn does nothing if the SCO is already on.
                            audioOn();
                        }
                    }
                    break;
                }
            }
        }
    };


    private synchronized void setAudioState(int state, BluetoothDevice device) {
        if (VDBG) log("setAudioState(" + state + ")");
        if (mBluetoothHeadset == null) {
            mAdapter.getProfileProxy(mContext, mProfileListener, BluetoothProfile.HEADSET);
            mPendingAudioState = true;
            mAudioState = state;
            return;
        }
        mBluetoothHeadset.setAudioState(device, state);
    }

    private synchronized int getAudioState(BluetoothDevice device) {
        if (mBluetoothHeadset == null) return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        return mBluetoothHeadset.getAudioState(device);
    }

    private BluetoothProfile.ServiceListener mProfileListener =
            new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = (BluetoothHeadset) proxy;
                synchronized(BluetoothHandsfree.this) {
                    if (mPendingAudioState) {
                        mBluetoothHeadset.setAudioState(mHeadset.getRemoteDevice(), mAudioState);
                        mPendingAudioState = false;
                    }
                }
            } else if (profile == BluetoothProfile.A2DP) {
                mA2dp = (BluetoothA2dp) proxy;
            }
        }
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.HEADSET) {
                mBluetoothHeadset = null;
            } else if (profile == BluetoothProfile.A2DP) {
                mA2dp = null;
            }
        }
    };

    /*
     * Put the AT command, company ID, arguments, and device in an Intent and broadcast it.
     */
    private void broadcastVendorSpecificEventIntent(String command,
                                                    int companyId,
                                                    int commandType,
                                                    Object[] arguments,
                                                    BluetoothDevice device) {
        if (VDBG) log("broadcastVendorSpecificEventIntent(" + command + ")");
        Intent intent =
                new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                        commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY
            + "." + Integer.toString(companyId));

        mContext.sendBroadcast(intent, android.Manifest.permission.BLUETOOTH);
    }

    void updateBtHandsfreeAfterRadioTechnologyChange() {
        if (VDBG) Log.d(TAG, "updateBtHandsfreeAfterRadioTechnologyChange...");

        mBluetoothPhoneState.updateBtPhoneStateAfterRadioTechnologyChange();
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
        if (mHeadsetType == TYPE_HANDSFREE && !mServiceConnectionEstablished) {
            if (DBG) log("audioOn(): service connection not yet established!");
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

        if (mPendingSco) {
            if (DBG) log("audioOn(): SCO already pending");
            return true;
        }

        mA2dpSuspended = false;
        mPendingSco = false;
        if (isA2dpMultiProfile() && mA2dpState == BluetoothA2dp.STATE_PLAYING) {
            if (DBG) log("suspending A2DP stream for SCO");
            mA2dpSuspended = mA2dp.suspendSink(mA2dpDevice);
            if (mA2dpSuspended) {
                mPendingSco = true;
                Message msg = mHandler.obtainMessage(MESSAGE_CHECK_PENDING_SCO);
                mHandler.sendMessageDelayed(msg, 2000);
            } else {
                Log.w(TAG, "Could not suspend A2DP stream for SCO, going ahead with SCO");
            }
        }

        if (!mPendingSco) {
            connectScoThread();
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
        if (VDBG) log("audioOff(): mPendingSco: " + mPendingSco +
                ", mConnectedSco: " + mConnectedSco +
                ", mA2dpState: " + mA2dpState +
                ", mA2dpSuspended: " + mA2dpSuspended);

        if (mA2dpSuspended) {
            if (isA2dpMultiProfile()) {
                if (DBG) log("resuming A2DP stream after disconnecting SCO");
                mA2dp.resumeSink(mA2dpDevice);
            }
            mA2dpSuspended = false;
        }

        mPendingSco = false;

        if (mSignalScoCloseThread != null) {
            mSignalScoCloseThread.shutdown();
            mSignalScoCloseThread = null;
        }

        // Sync with setting mConnectScoThread to null to assure the validity of
        // the condition
        synchronized (ScoSocketConnectThread.class) {
            if (mConnectScoThread != null) {
                mConnectScoThread.shutdown();
                resetConnectScoThread();
            }
        }

        closeConnectedSco();    // Should be closed already, but just in case
    }

    /* package */ boolean isAudioOn() {
        return (mConnectedSco != null);
    }

    private boolean isA2dpMultiProfile() {
        return mA2dp != null && mHeadset != null && mA2dpDevice != null &&
                mA2dpDevice.equals(mHeadset.getRemoteDevice());
    }

    /* package */ void ignoreRing() {
        mBluetoothPhoneState.ignoreRing();
    }

    private void sendURC(String urc) {
        if (isHeadsetConnected()) {
            mHeadset.sendURC(urc);
        }
    }

    /** helper to redial last dialled number */
    private AtCommandResult redial() {
        String number = mPhonebook.getLastDialledNumber();
        if (number == null) {
            // spec seems to suggest sending ERROR if we dont have a
            // number to redial
            if (VDBG) log("Bluetooth redial requested (+BLDN), but no previous " +
                  "outgoing calls found. Ignoring");
            return new AtCommandResult(AtCommandResult.ERROR);
        }
        // Outgoing call initiated by the handsfree device
        // Send terminateScoUsingVirtualVoiceCall
        terminateScoUsingVirtualVoiceCall();
        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                Uri.fromParts(Constants.SCHEME_TEL, number, null));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);

        // We do not immediately respond OK, wait until we get a phone state
        // update. If we return OK now and the handsfree immeidately requests
        // our phone state it will say we are not in call yet which confuses
        // some devices
        expectCallStart();
        return new AtCommandResult(AtCommandResult.UNSOLICITED);  // send nothing
    }

    /** Build the +CLCC result
     *  The complexity arises from the fact that we need to maintain the same
     *  CLCC index even as a call moves between states. */
    private synchronized AtCommandResult gsmGetClccResult() {
        // Collect all known connections
        Connection[] clccConnections = new Connection[GSM_MAX_CONNECTIONS];  // indexed by CLCC index
        LinkedList<Connection> newConnections = new LinkedList<Connection>();
        LinkedList<Connection> connections = new LinkedList<Connection>();

        Call foregroundCall = mCM.getActiveFgCall();
        Call backgroundCall = mCM.getFirstActiveBgCall();
        Call ringingCall = mCM.getFirstActiveRingingCall();

        if (ringingCall.getState().isAlive()) {
            connections.addAll(ringingCall.getConnections());
        }
        if (foregroundCall.getState().isAlive()) {
            connections.addAll(foregroundCall.getConnections());
        }
        if (backgroundCall.getState().isAlive()) {
            connections.addAll(backgroundCall.getConnections());
        }

        // Mark connections that we already known about
        boolean clccUsed[] = new boolean[GSM_MAX_CONNECTIONS];
        for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
            clccUsed[i] = mClccUsed[i];
            mClccUsed[i] = false;
        }
        for (Connection c : connections) {
            boolean found = false;
            long timestamp = c.getCreateTime();
            for (int i = 0; i < GSM_MAX_CONNECTIONS; i++) {
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
            result += ",\"" + number + "\"," + type;
        }
        return result;
    }

    /** Build the +CLCC result for CDMA
     *  The complexity arises from the fact that we need to maintain the same
     *  CLCC index even as a call moves between states. */
    private synchronized AtCommandResult cdmaGetClccResult() {
        // In CDMA at one time a user can have only two live/active connections
        Connection[] clccConnections = new Connection[CDMA_MAX_CONNECTIONS];// indexed by CLCC index
        Call foregroundCall = mCM.getActiveFgCall();
        Call ringingCall = mCM.getFirstActiveRingingCall();

        Call.State ringingCallState = ringingCall.getState();
        // If the Ringing Call state is INCOMING, that means this is the very first call
        // hence there should not be any Foreground Call
        if (ringingCallState == Call.State.INCOMING) {
            if (VDBG) log("Filling clccConnections[0] for INCOMING state");
            clccConnections[0] = ringingCall.getLatestConnection();
        } else if (foregroundCall.getState().isAlive()) {
            // Getting Foreground Call connection based on Call state
            if (ringingCall.isRinging()) {
                if (VDBG) log("Filling clccConnections[0] & [1] for CALL WAITING state");
                clccConnections[0] = foregroundCall.getEarliestConnection();
                clccConnections[1] = ringingCall.getLatestConnection();
            } else {
                if (foregroundCall.getConnections().size() <= 1) {
                    // Single call scenario
                    if (VDBG) log("Filling clccConnections[0] with ForgroundCall latest connection");
                    clccConnections[0] = foregroundCall.getLatestConnection();
                } else {
                    // Multiple Call scenario. This would be true for both
                    // CONF_CALL and THRWAY_ACTIVE state
                    if (VDBG) log("Filling clccConnections[0] & [1] with ForgroundCall connections");
                    clccConnections[0] = foregroundCall.getEarliestConnection();
                    clccConnections[1] = foregroundCall.getLatestConnection();
                }
            }
        }

        // Update the mCdmaIsSecondCallActive flag based on the Phone call state
        if (PhoneApp.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.SINGLE_ACTIVE) {
            cdmaSetSecondCallState(false);
        } else if (PhoneApp.getInstance().cdmaPhoneCallState.getCurrentCallState()
                == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
            cdmaSetSecondCallState(true);
        }

        // Build CLCC
        AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
        for (int i = 0; (i < clccConnections.length) && (clccConnections[i] != null); i++) {
            String clccEntry = cdmaConnectionToClccEntry(i, clccConnections[i]);
            if (clccEntry != null) {
                result.addResponse(clccEntry);
            }
        }

        return result;
    }

    /** Convert a Connection object into a single +CLCC result for CDMA phones */
    private String cdmaConnectionToClccEntry(int index, Connection c) {
        int state;
        PhoneApp app = PhoneApp.getInstance();
        CdmaPhoneCallState.PhoneCallState currCdmaCallState =
                app.cdmaPhoneCallState.getCurrentCallState();
        CdmaPhoneCallState.PhoneCallState prevCdmaCallState =
                app.cdmaPhoneCallState.getPreviousCallState();

        if ((prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE)
                && (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL)) {
            // If the current state is reached after merging two calls
            // we set the state of all the connections as ACTIVE
            state = 0;
        } else {
            switch (c.getState()) {
            case ACTIVE:
                // For CDMA since both the connections are set as active by FW after accepting
                // a Call waiting or making a 3 way call, we need to set the state specifically
                // to ACTIVE/HOLDING based on the mCdmaIsSecondCallActive flag. This way the
                // CLCC result will allow BT devices to enable the swap or merge options
                if (index == 0) { // For the 1st active connection
                    state = mCdmaIsSecondCallActive ? 1 : 0;
                } else { // for the 2nd active connection
                    state = mCdmaIsSecondCallActive ? 0 : 1;
                }
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
        }

        int mpty = 0;
        if (currCdmaCallState == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
            if (prevCdmaCallState == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                // If the current state is reached after merging two calls
                // we set the multiparty call true.
                mpty = 1;
            } else {
                // CALL_CONF state is not from merging two calls, but from
                // accepting the second call. In this case first will be on
                // hold in most cases but in some cases its already merged.
                // However, we will follow the common case and the test case
                // as per Bluetooth SIG PTS
                mpty = 0;
            }
        } else {
            mpty = 0;
        }

        int direction = c.isIncoming() ? 1 : 0;

        String number = c.getAddress();
        int type = -1;
        if (number != null) {
            type = PhoneNumberUtils.toaFromString(number);
        }

        String result = "+CLCC: " + (index + 1) + "," + direction + "," + state + ",0," + mpty;
        if (number != null) {
            result += ",\"" + number + "\"," + type;
        }
        return result;
    }

    /*
     * Register a vendor-specific command.
     * @param commandName the name of the command.  For example, if the expected
     * incoming command is <code>AT+FOO=bar,baz</code>, the value of this should be
     * <code>"+FOO"</code>.
     * @param companyId the Bluetooth SIG Company Identifier
     * @param parser the AtParser on which to register the command
     */
    private void registerVendorSpecificCommand(String commandName,
                                               int companyId,
                                               AtParser parser) {
        parser.register(commandName,
                        new VendorSpecificCommandHandler(commandName, companyId));
    }

    /*
     * Register all vendor-specific commands here.
     */
    private void registerAllVendorSpecificCommands() {
        AtParser parser = mHeadset.getAtParser();

        // Plantronics-specific headset events go here
        registerVendorSpecificCommand("+XEVENT",
                                      BluetoothAssignedNumbers.PLANTRONICS,
                                      parser);
    }

    /**
     * Register AT Command handlers to implement the Headset profile
     */
    private void initializeHeadsetAtParser() {
        if (VDBG) log("Registering Headset AT commands");
        AtParser parser = mHeadset.getAtParser();
        // Headsets usually only have one button, which is meant to cause the
        // HS to send us AT+CKPD=200 or AT+CKPD.
        parser.register("+CKPD", new AtCommandHandler() {
            private AtCommandResult headsetButtonPress() {
                if (mCM.getFirstActiveRingingCall().isRinging()) {
                    // Answer the call
                    mBluetoothPhoneState.stopRing();
                    sendURC("OK");
                    PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
                    // If in-band ring tone is supported, SCO connection will already
                    // be up and the following call will just return.
                    audioOn();
                    return new AtCommandResult(AtCommandResult.UNSOLICITED);
                } else if (mCM.hasActiveFgCall()) {
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
                            PhoneUtils.hangup(PhoneApp.getInstance().mCM);
                        }
                    }
                    return new AtCommandResult(AtCommandResult.OK);
                } else {
                    // No current call - redial last number
                    return redial();
                }
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
        if (VDBG) log("Registering Handsfree AT commands");
        AtParser parser = mHeadset.getAtParser();
        final Phone phone = mCM.getDefaultPhone();

        // Answer
        parser.register('A', new AtCommandHandler() {
            @Override
            public AtCommandResult handleBasicCommand(String args) {
                sendURC("OK");
                mBluetoothPhoneState.stopRing();
                PhoneUtils.answerCall(mCM.getFirstActiveRingingCall());
                return new AtCommandResult(AtCommandResult.UNSOLICITED);
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
                        // Send terminateScoUsingVirtualVoiceCall
                        terminateScoUsingVirtualVoiceCall();
                        // Remove trailing ';'
                        if (args.charAt(args.length() - 1) == ';') {
                            args = args.substring(0, args.length() - 1);
                        }

                        args = PhoneNumberUtils.convertPreDial(args);

                        Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                                Uri.fromParts(Constants.SCHEME_TEL, args, null));
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        mContext.startActivity(intent);

                        expectCallStart();
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
                sendURC("OK");
                if (isVirtualCallInProgress()) {
                    terminateScoUsingVirtualVoiceCall();
                } else {
                    if (mCM.hasActiveFgCall()) {
                        PhoneUtils.hangupActiveCall(mCM.getActiveFgCall());
                    } else if (mCM.hasActiveRingingCall()) {
                        PhoneUtils.hangupRingingCall(mCM.getFirstActiveRingingCall());
                    } else if (mCM.hasActiveBgCall()) {
                        PhoneUtils.hangupHoldingCall(mCM.getFirstActiveBgCall());
                    }
                }
                return new AtCommandResult(AtCommandResult.UNSOLICITED);
            }
        });

        // Bluetooth Retrieve Supported Features command
        parser.register("+BRSF", new AtCommandHandler() {
            private AtCommandResult sendBRSF() {
                return new AtCommandResult("+BRSF: " + mLocalBrsf);
            }
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+BRSF=<handsfree supported features bitmap>
                // Handsfree is telling us which features it supports. We
                // send the features we support
                if (args.length == 1 && (args[0] instanceof Integer)) {
                    mRemoteBrsf = (Integer) args[0];
                } else {
                    Log.w(TAG, "HF didn't sent BRSF assuming 0");
                }
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
                    boolean valid = false;
                    if (args[3].equals(0)) {
                        mIndicatorsEnabled = false;
                        valid = true;
                    } else if (args[3].equals(1)) {
                        mIndicatorsEnabled = true;
                        valid = true;
                    }
                    if (valid) {
                        if ((mRemoteBrsf & BRSF_HF_CW_THREE_WAY_CALLING) == 0x0) {
                            mServiceConnectionEstablished = true;
                            sendURC("OK");  // send immediately, then initiate audio
                            if (isIncallAudio()) {
                                audioOn();
                            } else if (mCM.getFirstActiveRingingCall().isRinging()) {
                                // need to update HS with RING cmd when single
                                // ringing call exist
                                mBluetoothPhoneState.ring();
                            }
                            // only send OK once
                            return new AtCommandResult(AtCommandResult.UNSOLICITED);
                        } else {
                            return new AtCommandResult(AtCommandResult.OK);
                        }
                    }
                }
                return reportCmeError(BluetoothCmeError.OPERATION_NOT_SUPPORTED);
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
                return mBluetoothPhoneState.toCindResult();
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return mBluetoothPhoneState.getCindTestResult();
            }
        });

        // Query Signal Quality (legacy)
        parser.register("+CSQ", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                return mBluetoothPhoneState.toCsqResult();
            }
        });

        // Query network registration state
        parser.register("+CREG", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                return new AtCommandResult(mBluetoothPhoneState.toCregString());
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
                        phone.sendDtmf(c);
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
                int phoneType = phone.getPhoneType();
                // Handsfree carkits expect that +CLCC is properly responded to.
                // Hence we ensure that a proper response is sent for the virtual call too.
                if (isVirtualCallInProgress()) {
                    String number = phone.getLine1Number();
                    AtCommandResult result = new AtCommandResult(AtCommandResult.OK);
                    String args;
                    if (number == null) {
                        args = "+CLCC: 1,0,0,0,0,\"\",0";
                    }
                    else
                    {
                        args = "+CLCC: 1,0,0,0,0,\"" + number + "\"," +
                                  PhoneNumberUtils.toaFromString(number);
                    }
                    result.addResponse(args);
                    return result;
                }
                if (phoneType == Phone.PHONE_TYPE_CDMA) {
                    return cdmaGetClccResult();
                } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                    return gsmGetClccResult();
                } else {
                    throw new IllegalStateException("Unexpected phone type: " + phoneType);
                }
            }
        });

        // Call Hold and Multiparty Handling command
        parser.register("+CHLD", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                int phoneType = phone.getPhoneType();
                Call ringingCall = mCM.getFirstActiveRingingCall();
                Call backgroundCall = mCM.getFirstActiveBgCall();

                if (args.length >= 1) {
                    if (args[0].equals(0)) {
                        boolean result;
                        if (ringingCall.isRinging()) {
                            result = PhoneUtils.hangupRingingCall(ringingCall);
                        } else {
                            result = PhoneUtils.hangupHoldingCall(backgroundCall);
                        }
                        if (result) {
                            return new AtCommandResult(AtCommandResult.OK);
                        } else {
                            return new AtCommandResult(AtCommandResult.ERROR);
                        }
                    } else if (args[0].equals(1)) {
                        if (phoneType == Phone.PHONE_TYPE_CDMA) {
                            if (ringingCall.isRinging()) {
                                // Hangup the active call and then answer call waiting call.
                                if (VDBG) log("CHLD:1 Callwaiting Answer call");
                                PhoneUtils.hangupRingingAndActive(phone);
                            } else {
                                // If there is no Call waiting then just hangup
                                // the active call. In CDMA this mean that the complete
                                // call session would be ended
                                if (VDBG) log("CHLD:1 Hangup Call");
                                PhoneUtils.hangup(PhoneApp.getInstance().mCM);
                            }
                            return new AtCommandResult(AtCommandResult.OK);
                        } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                            // Hangup active call, answer held call
                            if (PhoneUtils.answerAndEndActive(
                                    PhoneApp.getInstance().mCM, ringingCall)) {
                                return new AtCommandResult(AtCommandResult.OK);
                            } else {
                                return new AtCommandResult(AtCommandResult.ERROR);
                            }
                        } else {
                            throw new IllegalStateException("Unexpected phone type: " + phoneType);
                        }
                    } else if (args[0].equals(2)) {
                        sendURC("OK");
                        if (phoneType == Phone.PHONE_TYPE_CDMA) {
                            // For CDMA, the way we switch to a new incoming call is by
                            // calling PhoneUtils.answerCall(). switchAndHoldActive() won't
                            // properly update the call state within telephony.
                            // If the Phone state is already in CONF_CALL then we simply send
                            // a flash cmd by calling switchHoldingAndActive()
                            if (ringingCall.isRinging()) {
                                if (VDBG) log("CHLD:2 Callwaiting Answer call");
                                PhoneUtils.answerCall(ringingCall);
                                PhoneUtils.setMute(false);
                                // Setting the second callers state flag to TRUE (i.e. active)
                                cdmaSetSecondCallState(true);
                            } else if (PhoneApp.getInstance().cdmaPhoneCallState
                                    .getCurrentCallState()
                                    == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                                if (VDBG) log("CHLD:2 Swap Calls");
                                PhoneUtils.switchHoldingAndActive(backgroundCall);
                                // Toggle the second callers active state flag
                                cdmaSwapSecondCallState();
                            }
                        } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                            PhoneUtils.switchHoldingAndActive(backgroundCall);
                        } else {
                            throw new IllegalStateException("Unexpected phone type: " + phoneType);
                        }
                        return new AtCommandResult(AtCommandResult.UNSOLICITED);
                    } else if (args[0].equals(3)) {
                        sendURC("OK");
                        if (phoneType == Phone.PHONE_TYPE_CDMA) {
                            CdmaPhoneCallState.PhoneCallState state =
                                PhoneApp.getInstance().cdmaPhoneCallState.getCurrentCallState();
                            // For CDMA, we need to check if the call is in THRWAY_ACTIVE state
                            if (state == CdmaPhoneCallState.PhoneCallState.THRWAY_ACTIVE) {
                                if (VDBG) log("CHLD:3 Merge Calls");
                                PhoneUtils.mergeCalls();
                            } else if (state == CdmaPhoneCallState.PhoneCallState.CONF_CALL) {
                                // State is CONF_CALL already and we are getting a merge call
                                // This can happen when CONF_CALL was entered from a Call Waiting
                                mBluetoothPhoneState.updateCallHeld();
                            }
                        } else if (phoneType == Phone.PHONE_TYPE_GSM) {
                            if (mCM.hasActiveFgCall() && mCM.hasActiveBgCall()) {
                                PhoneUtils.mergeCalls();
                            }
                        } else {
                            throw new IllegalStateException("Unexpected phone type: " + phoneType);
                        }
                        return new AtCommandResult(AtCommandResult.UNSOLICITED);
                    }
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
            @Override
            public AtCommandResult handleTestCommand() {
                mServiceConnectionEstablished = true;
                sendURC("+CHLD: (0,1,2,3)");
                sendURC("OK");  // send reply first, then connect audio
                if (isIncallAudio()) {
                    audioOn();
                } else if (mCM.getFirstActiveRingingCall().isRinging()) {
                    // need to update HS with RING when single ringing call exist
                    mBluetoothPhoneState.ring();
                }
                // already replied
                return new AtCommandResult(AtCommandResult.UNSOLICITED);
            }
        });

        // Get Network operator name
        parser.register("+COPS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleReadCommand() {
                String operatorName = phone.getServiceState().getOperatorAlphaLong();
                if (operatorName != null) {
                    if (operatorName.length() > 16) {
                        operatorName = operatorName.substring(0, 16);
                    }
                    return new AtCommandResult(
                            "+COPS: 0,0,\"" + operatorName + "\"");
                } else {
                    return new AtCommandResult(
                            "+COPS: 0");
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
                    return reportCmeError(BluetoothCmeError.OPERATION_NOT_SUPPORTED);
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
                String imsi = phone.getSubscriberId();
                if (imsi == null || imsi.length() == 0) {
                    return reportCmeError(BluetoothCmeError.SIM_FAILURE);
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

        // AT+CGSN - Returns the device IMEI number.
        parser.register("+CGSN", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // Get the IMEI of the device.
                // phone will not be NULL at this point.
                return new AtCommandResult("+CGSN: " + phone.getDeviceId());
            }
        });

        // AT+CGMM - Query Model Information
        parser.register("+CGMM", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // Return the Model Information.
                String model = SystemProperties.get("ro.product.model");
                if (model != null) {
                    return new AtCommandResult("+CGMM: " + model);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
        });

        // AT+CGMI - Query Manufacturer Information
        parser.register("+CGMI", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                // Return the Model Information.
                String manuf = SystemProperties.get("ro.product.manufacturer");
                if (manuf != null) {
                    return new AtCommandResult("+CGMI: " + manuf);
                } else {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
            }
        });

        // Noise Reduction and Echo Cancellation control
        parser.register("+NREC", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (args[0].equals(0)) {
                    mAudioManager.setParameters(HEADSET_NREC+"=off");
                    return new AtCommandResult(AtCommandResult.OK);
                } else if (args[0].equals(1)) {
                    mAudioManager.setParameters(HEADSET_NREC+"=on");
                    return new AtCommandResult(AtCommandResult.OK);
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
        });

        // Voice recognition (dialing)
        parser.register("+BVRA", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                if (!BluetoothHeadset.isBluetoothVoiceDialingEnabled(mContext)) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
                if (args.length >= 1 && args[0].equals(1)) {
                    synchronized (BluetoothHandsfree.this) {
                        if (!isVoiceRecognitionInProgress() &&
                            !isCellularCallInProgress() &&
                            !isVirtualCallInProgress()) {
                            try {
                                mContext.startActivity(sVoiceCommandIntent);
                            } catch (ActivityNotFoundException e) {
                                return new AtCommandResult(AtCommandResult.ERROR);
                            }
                            expectVoiceRecognition();
                        }
                    }
                    return new AtCommandResult(AtCommandResult.UNSOLICITED);  // send nothing yet
                } else if (args.length >= 1 && args[0].equals(0)) {
                    if (isVoiceRecognitionInProgress()) {
                        audioOff();
                    }
                    return new AtCommandResult(AtCommandResult.OK);
                }
                return new AtCommandResult(AtCommandResult.ERROR);
            }
            @Override
            public AtCommandResult handleTestCommand() {
                return new AtCommandResult("+BVRA: (0-1)");
            }
        });

        // Retrieve Subscriber Number
        parser.register("+CNUM", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                String number = phone.getLine1Number();
                if (number == null) {
                    return new AtCommandResult(AtCommandResult.OK);
                }
                return new AtCommandResult("+CNUM: ,\"" + number + "\"," +
                        PhoneNumberUtils.toaFromString(number) + ",,4");
            }
        });

        // Microphone Gain
        parser.register("+VGM", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+VGM=<gain>    in range [0,15]
                // Headset/Handsfree is reporting its current gain setting
                return new AtCommandResult(AtCommandResult.OK);
            }
        });

        // Speaker Gain
        parser.register("+VGS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleSetCommand(Object[] args) {
                // AT+VGS=<gain>    in range [0,15]
                if (args.length != 1 || !(args[0] instanceof Integer)) {
                    return new AtCommandResult(AtCommandResult.ERROR);
                }
                mScoGain = (Integer) args[0];
                int flag =  mAudioManager.isBluetoothScoOn() ? AudioManager.FLAG_SHOW_UI:0;

                mAudioManager.setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, mScoGain, flag);
                return new AtCommandResult(AtCommandResult.OK);
            }
        });

        // Phone activity status
        parser.register("+CPAS", new AtCommandHandler() {
            @Override
            public AtCommandResult handleActionCommand() {
                int status = 0;
                switch (mCM.getState()) {
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

        mPhonebook.register(parser);
    }

    public void sendScoGainUpdate(int gain) {
        if (mScoGain != gain && (mRemoteBrsf & BRSF_HF_REMOTE_VOL_CONTROL) != 0x0) {
            sendURC("+VGS:" + gain);
            mScoGain = gain;
        }
    }

    public AtCommandResult reportCmeError(int error) {
        if (mCmee) {
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
            result.addResponse("+CME ERROR: " + error);
            return result;
        } else {
            return new AtCommandResult(AtCommandResult.ERROR);
        }
    }

    private static final int START_CALL_TIMEOUT = 10000;  // ms

    private synchronized void expectCallStart() {
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

    private static final int START_VOICE_RECOGNITION_TIMEOUT = 5000;  // ms

    private synchronized void expectVoiceRecognition() {
        mWaitingForVoiceRecognition = true;
        Message msg = Message.obtain(mHandler, CHECK_VOICE_RECOGNITION_STARTED);
        mHandler.sendMessageDelayed(msg, START_VOICE_RECOGNITION_TIMEOUT);
        if (!mStartVoiceRecognitionWakeLock.isHeld()) {
            mStartVoiceRecognitionWakeLock.acquire(START_VOICE_RECOGNITION_TIMEOUT);
        }
    }

    /* package */ synchronized boolean startVoiceRecognition() {

        if  ((isCellularCallInProgress()) ||
             (isVirtualCallInProgress()) ||
             mVoiceRecognitionStarted) {
            Log.e(TAG, "startVoiceRecognition: Call in progress");
            return false;
        }

        mVoiceRecognitionStarted = true;

        if (mWaitingForVoiceRecognition) {
            // HF initiated
            mWaitingForVoiceRecognition = false;
            sendURC("OK");
        } else {
            // AG initiated
            sendURC("+BVRA: 1");
        }
        boolean ret = audioOn();
        if (ret == false) {
            mVoiceRecognitionStarted = false;
        }
        if (mStartVoiceRecognitionWakeLock.isHeld()) {
            mStartVoiceRecognitionWakeLock.release();
        }
        return ret;
    }

    /* package */ synchronized boolean stopVoiceRecognition() {

        if (!isVoiceRecognitionInProgress()) {
            return false;
        }

        mVoiceRecognitionStarted = false;

        sendURC("+BVRA: 0");
        audioOff();
        return true;
    }

    // Voice Recognition in Progress
    private boolean isVoiceRecognitionInProgress() {
        return (mVoiceRecognitionStarted || mWaitingForVoiceRecognition);
    }

    /*
     * This class broadcasts vendor-specific commands + arguments to interested receivers.
     */
    private class VendorSpecificCommandHandler extends AtCommandHandler {

        private String mCommandName;

        private int mCompanyId;

        private VendorSpecificCommandHandler(String commandName, int companyId) {
            mCommandName = commandName;
            mCompanyId = companyId;
        }

        @Override
        public AtCommandResult handleReadCommand() {
            return new AtCommandResult(AtCommandResult.ERROR);
        }

        @Override
        public AtCommandResult handleTestCommand() {
            return new AtCommandResult(AtCommandResult.ERROR);
        }

        @Override
        public AtCommandResult handleActionCommand() {
            return new AtCommandResult(AtCommandResult.ERROR);
        }

        @Override
        public AtCommandResult handleSetCommand(Object[] arguments) {
            broadcastVendorSpecificEventIntent(mCommandName,
                                               mCompanyId,
                                               BluetoothHeadset.AT_CMD_TYPE_SET,
                                               arguments,
                                               mHeadset.getRemoteDevice());
            return new AtCommandResult(AtCommandResult.OK);
        }
    }

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

    // VirtualCall SCO support
    //

    // Cellular call in progress
    private boolean isCellularCallInProgress() {
        if (mCM.hasActiveFgCall() || mCM.hasActiveRingingCall()) return true;
        return false;
    }

    // Virtual Call in Progress
    private boolean isVirtualCallInProgress() {
        return mVirtualCallStarted;
    }

    void setVirtualCallInProgress(boolean state) {
        mVirtualCallStarted = state;
    }

    //NOTE: Currently the VirtualCall API does not allow the application to initiate a call
    // transfer. Call transfer may be initiated from the handsfree device and this is handled by
    // the VirtualCall API
    synchronized boolean initiateScoUsingVirtualVoiceCall() {
        if (DBG) log("initiateScoUsingVirtualVoiceCall: Received");
        // 1. Check if the SCO state is idle
        if (isCellularCallInProgress() || isVoiceRecognitionInProgress()) {
            Log.e(TAG, "initiateScoUsingVirtualVoiceCall: Call in progress");
            return false;
        }

        // 2. Perform outgoing call setup procedure
        if (mBluetoothPhoneState.sendUpdate() && !isVirtualCallInProgress()) {
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
            // outgoing call
            result.addResponse("+CIEV: 3,2");
            result.addResponse("+CIEV: 2,1");
            result.addResponse("+CIEV: 3,0");
            sendURC(result.toString());
            if (DBG) Log.d(TAG, "initiateScoUsingVirtualVoiceCall: Sent Call-setup procedure");
        }

        mVirtualCallStarted = true;

        // 3. Open the Audio Connection
        if (audioOn() == false) {
            log("initiateScoUsingVirtualVoiceCall: audioON failed");
            terminateScoUsingVirtualVoiceCall();
            return false;
        }

        mAudioPossible = true;

        // Done
        if (DBG) log("initiateScoUsingVirtualVoiceCall: Done");
        return true;
    }

    synchronized boolean terminateScoUsingVirtualVoiceCall() {
        if (DBG) log("terminateScoUsingVirtualVoiceCall: Received");

        if (!isVirtualCallInProgress()) {
            return false;
        }

        // 1. Release audio connection
        audioOff();

        // 2. terminate call-setup
        if (mBluetoothPhoneState.sendUpdate()) {
            AtCommandResult result = new AtCommandResult(AtCommandResult.UNSOLICITED);
            // outgoing call
            result.addResponse("+CIEV: 2,0");
            sendURC(result.toString());
            if (DBG) log("terminateScoUsingVirtualVoiceCall: Sent Call-setup procedure");
        }
        mVirtualCallStarted = false;
        mAudioPossible = false;

        // Done
        if (DBG) log("terminateScoUsingVirtualVoiceCall: Done");
        return true;
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

        /** Debug AT+BSIR - Send In Band Ringtones Unsolicited AT command.
         * debug.bt.unsol.inband = 0 => AT+BSIR = 0 sent by the AG
         * debug.bt.unsol.inband = 1 => AT+BSIR = 0 sent by the AG
         * Other values are ignored.
         */

        private static final String DEBUG_UNSOL_INBAND_RINGTONE =
            "debug.bt.unsol.inband";

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
                    mBluetoothPhoneState.updateBatteryState(intent);
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
                    b.putInt("state", oldService ? 0 : 1);
                    b.putBoolean("roaming", oldRoam);
                    mBluetoothPhoneState.updateServiceState(true, ServiceState.newFromBundle(b));
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
                    SignalStrength signalStrength = new SignalStrength(signalLevel, -1, -1, -1,
                            -1, -1, -1, true);
                    Intent intent = new Intent();
                    Bundle data = new Bundle();
                    signalStrength.fillInNotifierBundle(data);
                    intent.putExtras(data);
                    mBluetoothPhoneState.updateSignalState(intent);
                }

                if (SystemProperties.getBoolean(DEBUG_HANDSFREE_CLCC, false)) {
                    log(gsmGetClccResult().toString());
                }
                try {
                    sleep(1000);  // 1 second
                } catch (InterruptedException e) {
                    break;
                }

                int inBandRing =
                    SystemProperties.getInt(DEBUG_UNSOL_INBAND_RINGTONE, -1);
                if (inBandRing == 0 || inBandRing == 1) {
                    AtCommandResult result =
                        new AtCommandResult(AtCommandResult.UNSOLICITED);
                    result.addResponse("+BSIR: " + inBandRing);
                    sendURC(result.toString());
                }
            }
        }
    }

    public void cdmaSwapSecondCallState() {
        if (VDBG) log("cdmaSetSecondCallState: Toggling mCdmaIsSecondCallActive");
        mCdmaIsSecondCallActive = !mCdmaIsSecondCallActive;
        mCdmaCallsSwapped = true;
    }

    public void cdmaSetSecondCallState(boolean state) {
        if (VDBG) log("cdmaSetSecondCallState: Setting mCdmaIsSecondCallActive to " + state);
        mCdmaIsSecondCallActive = state;

        if (!mCdmaIsSecondCallActive) {
            mCdmaCallsSwapped = false;
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
