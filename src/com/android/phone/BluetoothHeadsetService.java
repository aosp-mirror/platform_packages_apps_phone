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

import android.app.Service;
import android.bluetooth.BluetoothAudioGateway;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.IBluetoothDeviceCallback;
import android.bluetooth.IBluetoothHeadset;
import android.bluetooth.IBluetoothHeadsetCallback;
import android.bluetooth.HeadsetBase;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.SystemProperties;
import android.os.SystemService;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import android.util.Log;

/**
 * Provides Bluetooth Headset and Handsfree profile, as a service in
 * the Phone application.
 * @hide
 */
public class BluetoothHeadsetService extends Service {
    private static final String TAG = "BT HSHFP";
    private static final boolean DBG = false;

    private static final String PREF_NAME = BluetoothHeadsetService.class.getSimpleName();
    private static final String PREF_LAST_HEADSET = "lastHeadsetAddress";

    private static final int PHONE_STATE_CHANGED = 1;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static boolean sHasStarted = false;

    private BluetoothDevice mBluetooth;
    private PowerManager mPowerManager;
    private BluetoothAudioGateway mAg;
    private HeadsetBase mHeadset;
    private int mState;
    private int mHeadsetType;
    private BluetoothHandsfree mBtHandsfree;
    private String mHeadsetAddress;
    private IBluetoothHeadsetCallback mConnectHeadsetCallback;
    private String mLastHeadsetAddress;
    private Call mForegroundCall;
    private Call mRingingCall;
    private Phone mPhone;

    public BluetoothHeadsetService() {
        mState = BluetoothHeadset.STATE_DISCONNECTED;
        mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mBluetooth = (BluetoothDevice)getSystemService(Context.BLUETOOTH_SERVICE);
        mPowerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
        mBtHandsfree = PhoneApp.getInstance().getBluetoothHandsfree();
        mAg = new BluetoothAudioGateway(mBluetooth);
        mPhone = PhoneFactory.getDefaultPhone();
        mRingingCall = mPhone.getRingingCall();
        mForegroundCall = mPhone.getForegroundCall();
        restoreLastHeadsetAddress();

        IntentFilter filter = new IntentFilter(
                BluetoothIntent.REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION);
        filter.addAction(BluetoothIntent.ENABLED_ACTION);
        filter.addAction(BluetoothIntent.DISABLED_ACTION);
        registerReceiver(mBluetoothIntentReceiver, filter);

        mPhone.registerForPhoneStateChanged(mStateChangeHandler, PHONE_STATE_CHANGED, null);
    }

    @Override
    public void onStart(Intent intent, int startId) {
         if (mBluetooth == null) {
            Log.w(TAG, "Stopping BluetoothHeadsetService: device does not have BT");
            stopSelf();
        } else {
            if (!sHasStarted) {
                if (DBG) log("Starting BluetoothHeadsetService");
                if (mBluetooth.isEnabled()) {
                    mAg.start(mIncomingConnectionHandler);
                    mBtHandsfree.onBluetoothEnabled();
                    // BT might have only just started, wait 6 seconds until
                    // SDP records are registered before reconnecting headset
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(RECONNECT_LAST_HEADSET),
                            6000);
                }
                sHasStarted = true;
            }
        }
    }

    private final Handler mIncomingConnectionHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            BluetoothAudioGateway.IncomingConnectionInfo info =
                    (BluetoothAudioGateway.IncomingConnectionInfo)msg.obj;
            int type = BluetoothHandsfree.TYPE_UNKNOWN;
            switch(msg.what) {
            case BluetoothAudioGateway.MSG_INCOMING_HEADSET_CONNECTION:
                type = BluetoothHandsfree.TYPE_HEADSET;
                break;
            case BluetoothAudioGateway.MSG_INCOMING_HANDSFREE_CONNECTION:
                type = BluetoothHandsfree.TYPE_HANDSFREE;
                break;
            }

            Log.i(TAG, "Incoming rfcomm (" + BluetoothHandsfree.typeToString(type) +
                  ") connection from " + info.mAddress + " on channel " + info.mRfcommChan);

            switch (mState) {
            case BluetoothHeadset.STATE_DISCONNECTED:
                // headset connecting us, lets join
                setState(BluetoothHeadset.STATE_CONNECTING);
                mHeadsetAddress = info.mAddress;
                HeadsetBase headset = new HeadsetBase(mPowerManager, mBluetooth, mHeadsetAddress,
                        info.mSocketFd, info.mRfcommChan, mConnectedStatusHandler);
                mHeadsetType = type;

                mConnectingStatusHandler.obtainMessage(RFCOMM_CONNECTED, headset).sendToTarget();

                break;
            case BluetoothHeadset.STATE_CONNECTING:
                if (!info.mAddress.equals(mHeadsetAddress)) {
                    // different headset, ignoring
                    Log.i(TAG, "Already attempting connect to " + mHeadsetAddress +
                          ", disconnecting " + info.mAddress);
                    mBluetooth.disconnectRemoteDeviceAcl(info.mAddress);
                    break;
                }
                // If we are here, we are in danger of a race condition
                // incoming rfcomm connection, but we are also attempting an
                // outgoing connection. Lets try and interrupt the outgoing
                // connection.
                Log.i(TAG, "Incoming and outgoing connections to " + info.mAddress +
                            ". Cancel outgoing connection.");
                if (mConnectThread != null) {
                    mConnectThread.interrupt();
                }

                // Now continue with new connection, including calling callback
                mHeadset = new HeadsetBase(mPowerManager, mBluetooth, mHeadsetAddress,
                        info.mSocketFd, info.mRfcommChan, mConnectedStatusHandler);
                mHeadsetType = type;

                setState(BluetoothHeadset.STATE_CONNECTED, BluetoothHeadset.RESULT_SUCCESS);
                mBtHandsfree.connectHeadset(mHeadset, mHeadsetType);

                // Make sure that old outgoing connect thread is dead.
                if (mConnectThread != null) {
                    try {
                        // TODO: Don't block in the main thread
                        Log.w(TAG, "Block in main thread to join stale outgoing connection thread");
                        mConnectThread.join();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Connection cancelled twice eh?", e);
                    }
                    mConnectThread = null;
                }
                if (DBG) log("Successfully used incoming connection, and cancelled outgoing " +
                      " connection");
                break;
            case BluetoothHeadset.STATE_CONNECTED:
                Log.i(TAG, "Already connected to " + mHeadsetAddress + ", disconnecting " +
                      info.mAddress);
                mBluetooth.disconnectRemoteDeviceAcl(info.mAddress);
                break;
            }
        }
    };

    private final Handler mStateChangeHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch(msg.what) {
            case PHONE_STATE_CHANGED:
                switch(mForegroundCall.getState()) {
                case DIALING:
                case ALERTING:
                    synchronized(this) {
                        if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                            reconnectLastHeadset();
                        }
                    }
                }

                switch(mRingingCall.getState()) {
                case INCOMING:
                case WAITING:
                    synchronized(this) {
                        if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                            reconnectLastHeadset();
                        }
                    }
                break;
                }
            }
        }
    };

    private void reconnectLastHeadset() {
        if (DBG && debugDontReconnect()) {
            return;
        }
        if (mBluetooth.isEnabled() && mLastHeadsetAddress != null &&
                mBluetooth.hasBonding(mLastHeadsetAddress)) {
            try {
                mBinder.connectHeadset(mLastHeadsetAddress, null);
            } catch (RemoteException e) {}
        }
    }

    private final BroadcastReceiver mBluetoothIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            String address = intent.getStringExtra(BluetoothIntent.ADDRESS);
            if ((mState == BluetoothHeadset.STATE_CONNECTED ||
                    mState == BluetoothHeadset.STATE_CONNECTING) &&
                    action.equals(BluetoothIntent.REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION) &&
                    address.equals(mHeadsetAddress)) {
                try {
                    mBinder.disconnectHeadset();
                } catch (RemoteException e) {}
            } else if (action.equals(BluetoothIntent.ENABLED_ACTION)) {
                mHandler.sendMessageDelayed(mHandler.obtainMessage(RECONNECT_LAST_HEADSET), 8000);
                mAg.start(mIncomingConnectionHandler);
                mBtHandsfree.onBluetoothEnabled();
            } else if (action.equals(BluetoothIntent.DISABLED_ACTION)) {
                mBtHandsfree.onBluetoothDisabled();
                mAg.stop();
            }
        }
    };

    private static final int RECONNECT_LAST_HEADSET = 1;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case RECONNECT_LAST_HEADSET:
                if (mBluetooth.isEnabled()) {
                    reconnectLastHeadset();
                }
                break;
            }
        }
    };

    private void restoreLastHeadsetAddress() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        mLastHeadsetAddress = prefs.getString(PREF_LAST_HEADSET, null);
    }

    private void saveLastHeadsetAddress() {
        SharedPreferences prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(PREF_LAST_HEADSET, mLastHeadsetAddress);
        editor.commit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    // ------------------------------------------------------------------
    // Bluetooth Headset Connect
    // ------------------------------------------------------------------
    private static final int SDP_RESULT                   = 1;
    private static final int RFCOMM_CONNECTED             = 2;
    private static final int RFCOMM_ERROR                 = 3;

    private static final short HEADSET_UUID16 = 0x1108;
    private static final short HANDSFREE_UUID16 = 0x111E;

    private long mTimestamp;

    IBluetoothDeviceCallback.Stub mDeviceCallback = new IBluetoothDeviceCallback.Stub() {
        public void onGetRemoteServiceChannelResult(String address, int channel) {
            mConnectingStatusHandler.obtainMessage(SDP_RESULT, channel, -1, address)
                .sendToTarget();
        }
        public void onCreateBondingResult(String address, int result) { }
        public void onEnableResult(int result) {}
    };

    /**
     * Thread for RFCOMM connection
     * Messages are sent to mConnectingStatusHandler as connection progresses.
     */
    private RfcommConnectThread mConnectThread;
    private class RfcommConnectThread extends Thread {
        private String address;
        private int channel;
        private int type;
        public RfcommConnectThread(String address, int channel, int type) {
            super();
            this.address = address;
            this.channel = channel;
            this.type = type;
        }

        @Override
        public void run() {
            long timestamp;

            timestamp = System.currentTimeMillis();
            HeadsetBase headset = new HeadsetBase(mPowerManager, mBluetooth, address, channel);

            // Try to connect for 20 seconds
            int result = 0;
            for (int i=0; i < 40 && result == 0; i++) {
                result = headset.waitForAsyncConnect(500, mConnectedStatusHandler);
                if (isInterrupted()) {
                    headset.disconnect();
                    return;
                }
            }

            if (DBG) log("RFCOMM connection attempt took " +
                  (System.currentTimeMillis() - timestamp) + " ms");
            if (isInterrupted()) {
                headset.disconnect();
                return;
            }
            if (result < 0) {
                Log.w(TAG, "headset.waitForAsyncConnect() error: " + result);
                mConnectingStatusHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
                return;
            } else if (result == 0) {
                Log.w(TAG, "mHeadset.waitForAsyncConnect() error: " + result + "(timeout)");
                mConnectingStatusHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
                return;
            } else {
                mConnectingStatusHandler.obtainMessage(RFCOMM_CONNECTED, headset).sendToTarget();
            }
        }
    };


    private void doHeadsetSdp() {
        if (DBG) log("Headset SDP request");
        mTimestamp = System.currentTimeMillis();
        mHeadsetType = BluetoothHandsfree.TYPE_HEADSET;
        if (!mBluetooth.getRemoteServiceChannel(mHeadsetAddress, HEADSET_UUID16,
                    mDeviceCallback)) {
            Log.e(TAG, "Could not start headset SDP query");
            setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE);
        }
    }

    private void doHandsfreeSdp() {
        if (DBG) log("Handsfree SDP request");
        mTimestamp = System.currentTimeMillis();
        mHeadsetType = BluetoothHandsfree.TYPE_HANDSFREE;
        if (!mBluetooth.getRemoteServiceChannel(mHeadsetAddress, HANDSFREE_UUID16,
                    mDeviceCallback)) {
            Log.e(TAG, "Could not start handsfree SDP query");
            setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE);
        }
    }

    /**
     * Receives events from mConnectThread back in the main thread.
     */
    private final Handler mConnectingStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (mState != BluetoothHeadset.STATE_CONNECTING) {
                return;  // stale events
            }

            switch (msg.what) {
            case SDP_RESULT:
                if (DBG) log("SDP request returned " + msg.arg1 + " (" + 
                        (System.currentTimeMillis() - mTimestamp + " ms)"));
                if (!((String)msg.obj).equals(mHeadsetAddress)) {
                    return;  // stale SDP result
                }

                if (msg.arg1 > 0) {
                    mConnectThread = new RfcommConnectThread(mHeadsetAddress, msg.arg1,
                                                             mHeadsetType);
                    mConnectThread.start();
                } else {
                    if (msg.arg1 == -1 && mHeadsetType == BluetoothHandsfree.TYPE_HANDSFREE) {
                        doHeadsetSdp();  // try headset
                    } else {
                        setState(BluetoothHeadset.STATE_DISCONNECTED,
                                 BluetoothHeadset.RESULT_FAILURE);
                    }
                }
                break;
            case RFCOMM_ERROR:
                if (DBG) log("Rfcomm error");
                mConnectThread = null;
                setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE);
                break;
            case RFCOMM_CONNECTED:
                if (DBG) log("Rfcomm connected");
                mConnectThread = null;
                mHeadset = (HeadsetBase)msg.obj;
                setState(BluetoothHeadset.STATE_CONNECTED, BluetoothHeadset.RESULT_SUCCESS);

                mBtHandsfree.connectHeadset(mHeadset, mHeadsetType);
                break;
            }
        }
    };

    /**
     * Receives events from a connected RFCOMM socket back in the main thread.
     */
    private final Handler mConnectedStatusHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case HeadsetBase.RFCOMM_DISCONNECTED:
                setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE);
                break;
            }
        }
    };

    private synchronized void setState(int state, int result) {
        String address = mHeadsetAddress;
        setState(state);
        doCallback(address, result);
    }

    private synchronized void doCallback(String address, int result) {
        if (mConnectHeadsetCallback != null) {
            try {
                if (DBG) log("onConnectHeadsetResult(" + address + ", " + result + ")");
                mConnectHeadsetCallback.onConnectHeadsetResult(address, result);
            } catch (RemoteException e) {}
            mConnectHeadsetCallback = null;
        }
    }

    private synchronized void setState(int state) {
        if (state != mState) {
            if (DBG) log("Headset state " + mState + " -> " + state);
            if (mState == BluetoothHeadset.STATE_CONNECTED) {
                // no longer connected - make sure BT audio is taken down
                mBtHandsfree.audioOff();
                mBtHandsfree.disconnectHeadset();
            }
            Intent intent = new Intent(BluetoothIntent.HEADSET_STATE_CHANGED_ACTION);
            intent.putExtra(BluetoothIntent.HEADSET_PREVIOUS_STATE, mState);
            mState = state;
            intent.putExtra(BluetoothIntent.HEADSET_STATE, mState);
            intent.putExtra(BluetoothIntent.ADDRESS, mHeadsetAddress);
            sendBroadcast(intent, BLUETOOTH_PERM);
            if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                mHeadset = null;
                mHeadsetAddress = null;
                mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
            }
            if (mState == BluetoothHeadset.STATE_CONNECTED) {
                mLastHeadsetAddress = mHeadsetAddress;
                saveLastHeadsetAddress();
            }
        }
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothHeadset.Stub mBinder = new IBluetoothHeadset.Stub() {
        public int getState() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState;
        }
        public String getHeadsetAddress() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                return null;
            }
            return mHeadsetAddress;
        }
        public boolean connectHeadset(String address, IBluetoothHeadsetCallback callback) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            if (address == null) {
                address = mLastHeadsetAddress;
            }
            if (!BluetoothDevice.checkBluetoothAddress(address)) {
                return false;
            }
            if (mState == BluetoothHeadset.STATE_CONNECTED ||
                mState == BluetoothHeadset.STATE_CONNECTING) {
                Log.w(TAG, "connectHeadset(" + address + "): failed: already in state " + mState +
                       "with headset " + mHeadsetAddress);
                return false;
            }
            mConnectHeadsetCallback = callback;
            mHeadsetAddress = address;
            setState(BluetoothHeadset.STATE_CONNECTING);
            doHandsfreeSdp();
            return true;
        }
        public boolean isConnected(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothHeadset.STATE_CONNECTED && mHeadsetAddress.equals(address);
        }
        public void disconnectHeadset() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                switch (mState) {
                case BluetoothHeadset.STATE_CONNECTING:
                    if (mConnectThread != null) {
                        // cancel the connection thread
                        mConnectThread.interrupt();
                        try {
                            mConnectThread.join();
                        } catch (InterruptedException e) {
                            Log.e(TAG, "Connection cancelled twice?", e);
                        }
                        mConnectThread = null;
                    }
                    if (mHeadset != null) {
                        mHeadset.disconnect();
                        mHeadset = null;
                    }
                    setState(BluetoothHeadset.STATE_DISCONNECTED,
                             BluetoothHeadset.RESULT_CANCELLED);
                    break;
                case BluetoothHeadset.STATE_CONNECTED:
                    if (mHeadset != null) {
                        mHeadset.disconnect();
                        mHeadset = null;
                    }
                    /* Explicit disconnect from a connected headset - we don't
                     * want to auto-reconnected */
                    mLastHeadsetAddress = null;
                    saveLastHeadsetAddress();
                    setState(BluetoothHeadset.STATE_DISCONNECTED,
                             BluetoothHeadset.RESULT_CANCELLED);
                    break;
                }
            }
        }
        public boolean startVoiceRecognition() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (mState != BluetoothHeadset.STATE_CONNECTED) {
	                return false;
                }
                return mBtHandsfree.startVoiceRecognition();
            }
        }
        public boolean stopVoiceRecognition() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (mState != BluetoothHeadset.STATE_CONNECTED) {
	                return false;
                }
                return mBtHandsfree.stopVoiceRecognition();
            }
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Stopping BluetoothHeadsetService");
        unregisterReceiver(mBluetoothIntentReceiver);
        mBtHandsfree.onBluetoothDisabled();
        mAg.stop();
        sHasStarted = false;
        setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_CANCELLED);
        mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
    }

    /** If this property is false, then don't auto-reconnect BT headset */
    private static final String DEBUG_AUTO_RECONNECT = "debug.bt.hshfp.auto_reconnect";
   
    private boolean debugDontReconnect() {
        return (!SystemProperties.getBoolean(DEBUG_AUTO_RECONNECT, true));
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
