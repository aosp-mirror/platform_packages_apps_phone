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
import android.bluetooth.BluetoothError;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothIntent;
import android.bluetooth.HeadsetBase;
import android.bluetooth.IBluetoothDeviceCallback;
import android.bluetooth.IBluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.ListIterator;

/**
 * Provides Bluetooth Headset and Handsfree profile, as a service in
 * the Phone application.
 * @hide
 */
public class BluetoothHeadsetService extends Service {
    private static final String TAG = "BT HSHFP";
    private static final boolean DBG = true;

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
    private LinkedList<String> mAutoConnectQueue;
    private Call mForegroundCall;
    private Call mRingingCall;
    private Phone mPhone;

    private final HeadsetPriority mHeadsetPriority = new HeadsetPriority();

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
        if (mBluetooth.isEnabled()) {
            mHeadsetPriority.load();
        }
        IntentFilter filter = new IntentFilter(
                BluetoothIntent.REMOTE_DEVICE_DISCONNECT_REQUESTED_ACTION);
        filter.addAction(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION);
        filter.addAction(BluetoothIntent.BOND_STATE_CHANGED_ACTION);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
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

            int priority = BluetoothHeadset.PRIORITY_OFF;
            try {
                priority = mBinder.getPriority(info.mAddress);
            } catch (RemoteException e) {}
            if (priority <= BluetoothHeadset.PRIORITY_OFF) {
                Log.i(TAG, "Rejecting incoming connection because priority = " + priority);
                // TODO: disconnect RFCOMM not ACL. Happens elsewhere too.
                mBluetooth.disconnectRemoteDeviceAcl(info.mAddress);
            }
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
                            autoConnectHeadset();
                        }
                    }
                }

                switch(mRingingCall.getState()) {
                case INCOMING:
                case WAITING:
                    synchronized(this) {
                        if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                            autoConnectHeadset();
                        }
                    }
                break;
                }
            }
        }
    };

    private synchronized void autoConnectHeadset() {
        if (DBG && debugDontReconnect()) {
            return;
        }
        if (mBluetooth.isEnabled()) {
            try {
                mBinder.connectHeadset(null);
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
            } else if (action.equals(BluetoothIntent.BLUETOOTH_STATE_CHANGED_ACTION)) {
                switch (intent.getIntExtra(BluetoothIntent.BLUETOOTH_STATE,
                                           BluetoothError.ERROR)) {
                case BluetoothDevice.BLUETOOTH_STATE_ON:
                    mHeadsetPriority.load();
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(RECONNECT_LAST_HEADSET), 8000);
                    mAg.start(mIncomingConnectionHandler);
                    mBtHandsfree.onBluetoothEnabled();
                    break;
                case BluetoothDevice.BLUETOOTH_STATE_TURNING_OFF:
                    mBtHandsfree.onBluetoothDisabled();
                    mAg.stop();
                    setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE);
                    break;
                }
            } else if (action.equals(BluetoothIntent.BOND_STATE_CHANGED_ACTION)) {
                int bondState = intent.getIntExtra(BluetoothIntent.BOND_STATE,
                                                   BluetoothError.ERROR);
                switch(bondState) {
                case BluetoothDevice.BOND_BONDED:
                    mHeadsetPriority.set(address, BluetoothHeadset.PRIORITY_AUTO);
                    break;
                case BluetoothDevice.BOND_NOT_BONDED:
                    mHeadsetPriority.set(address, BluetoothHeadset.PRIORITY_OFF);
                    break;
                }
            } else if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    mBtHandsfree.sendScoGainUpdate(intent.getIntExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0));
                }

            }
        }
    };

    private static final int RECONNECT_LAST_HEADSET = 1;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case RECONNECT_LAST_HEADSET:
                autoConnectHeadset();
                break;
            }
        }
    };

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

    private void setState(int state) {
        setState(state, BluetoothHeadset.RESULT_SUCCESS);
    }

    private synchronized void setState(int state, int result) {
        if (state != mState) {
            if (DBG) log("Headset state " + mState + " -> " + state + ", result = " + result);
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
                if (mAutoConnectQueue != null) {
                    doNextAutoConnect();
                }
            } else if (mState == BluetoothHeadset.STATE_CONNECTED) {
                mAutoConnectQueue = null;  // cancel further auto-connection
                mHeadsetPriority.bump(mHeadsetAddress.toUpperCase());
            }
        }
    }

    private synchronized boolean doNextAutoConnect() {
        if (mAutoConnectQueue == null || mAutoConnectQueue.size() == 0) {
            mAutoConnectQueue = null;
            return false;
        }
        mHeadsetAddress = mAutoConnectQueue.removeFirst();
        if (DBG) log("pulled " + mHeadsetAddress + " off auto-connect queue");
        setState(BluetoothHeadset.STATE_CONNECTING);
        doHandsfreeSdp();

        return true;
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
        public boolean connectHeadset(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            if (!BluetoothDevice.checkBluetoothAddress(address) && address != null) {
                return false;
            }
            synchronized (BluetoothHeadsetService.this) {
                if (mState == BluetoothHeadset.STATE_CONNECTED ||
                    mState == BluetoothHeadset.STATE_CONNECTING) {
                    Log.w(TAG, "connectHeadset(" + address + "): failed: already in state " +
                          mState + " with headset " + mHeadsetAddress);
                    return false;
                }
                if (address == null) {
                    mAutoConnectQueue = mHeadsetPriority.getSorted();
                    return doNextAutoConnect();
                }
                mHeadsetAddress = address;
                setState(BluetoothHeadset.STATE_CONNECTING);
                doHandsfreeSdp();
            }
            return true;
        }
        public boolean isConnected(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothHeadset.STATE_CONNECTED && mHeadsetAddress.equals(address);
        }
        public void disconnectHeadset() {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
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
                             BluetoothHeadset.RESULT_CANCELED);
                    break;
                case BluetoothHeadset.STATE_CONNECTED:
                    // Send a dummy battery level message to force headset
                    // out of sniff mode so that it will immediately notice
                    // the disconnection. We are currently sending it for
                    // handsfree only.
                    // TODO: Call hci_conn_enter_active_mode() from
                    // rfcomm_send_disc() in the kernel instead.
                    // See http://b/1716887
                    if (mHeadsetType == BluetoothHandsfree.TYPE_HANDSFREE) {
                        mHeadset.sendURC("+CIEV: 7,3");
                    }

                    if (mHeadset != null) {
                        mHeadset.disconnect();
                        mHeadset = null;
                    }
                    setState(BluetoothHeadset.STATE_DISCONNECTED,
                             BluetoothHeadset.RESULT_CANCELED);
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
        public boolean setPriority(String address, int priority) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            if (!BluetoothDevice.checkBluetoothAddress(address) ||
                priority < BluetoothHeadset.PRIORITY_OFF) {
                return false;
            }
            mHeadsetPriority.set(address.toUpperCase(), priority);
            return true;
        }
        public int getPriority(String address) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (!BluetoothDevice.checkBluetoothAddress(address)) {
                return -1;  //TODO: BluetoothError.
            }
            return mHeadsetPriority.get(address.toUpperCase());
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
        setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_CANCELED);
        mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
    }

    /** operates on UPPER CASE addresses */
    private class HeadsetPriority {
        private HashMap<String, Integer> mPriority = new HashMap<String, Integer>();

        public synchronized boolean load() {
            String[] addresses = mBluetooth.listBonds();
            if (addresses == null) {
                return false;  // for example, bluetooth is off
            }
            for (String address : addresses) {
                load(address);
            }
            return true;
        }

        private synchronized int load(String address) {
            int priority = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.getBluetoothHeadsetPriorityKey(address),
                    BluetoothHeadset.PRIORITY_OFF);
            mPriority.put(address, new Integer(priority));
            if (DBG) log("Loaded priority " + address + " = " + priority);
            return priority;
        }

        public synchronized int get(String address) {
            Integer priority = mPriority.get(address);
            if (priority == null) {
                return load(address);
            }
            return priority.intValue();
        }

        public synchronized void set(String address, int priority) {
            int oldPriority = get(address);
            if (oldPriority == priority) {
                return;
            }
            mPriority.put(address, new Integer(priority));
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.getBluetoothHeadsetPriorityKey(address),
                    priority);
            if (DBG) log("Saved priority " + address + " = " + priority);
        }

        /** Mark this headset as highest priority */
        public synchronized void bump(String address) {
            int oldPriority = get(address);
            int maxPriority = BluetoothHeadset.PRIORITY_OFF;

            // Find max, not including given address
            for (String a : mPriority.keySet()) {
                if (address.equals(a)) continue;
                int p = mPriority.get(a).intValue();
                if (p > maxPriority) {
                    maxPriority = p;
                }
            }
            if (maxPriority >= oldPriority) {
                int p = maxPriority + 1;
                set(address, p);
                if (p >= Integer.MAX_VALUE) {
                    rebalance();
                }
            }
        }

        /** shifts all non-zero priorities to be monotonically increasing from
         * PRIORITY_AUTO */
        private synchronized void rebalance() {
            LinkedList<String> sorted = getSorted();
            if (DBG) log("Rebalancing " + sorted.size() + " headset priorities");

            ListIterator<String> li = sorted.listIterator(sorted.size());
            int priority = BluetoothHeadset.PRIORITY_AUTO;
            while (li.hasPrevious()) {
                String address = li.previous();
                set(address, priority);
                priority++;
            }
        }

        /** Get list of headsets sorted by decreasing priority.
         * Headsets with priority equal to PRIORITY_OFF are not included */
        public synchronized LinkedList<String> getSorted() {
            LinkedList<String> sorted = new LinkedList<String>();
            HashMap<String, Integer> toSort = new HashMap<String, Integer>(mPriority);

            // add in sorted order. this could be more efficient.
            while (true) {
                String maxAddress = null;
                int maxPriority = BluetoothHeadset.PRIORITY_OFF;
                for (String address : toSort.keySet()) {
                    int priority = toSort.get(address).intValue();
                    if (priority > maxPriority) {
                        maxAddress = address;
                        maxPriority = priority;
                    }
                }
                if (maxAddress == null) {
                    break;
                }
                sorted.addLast(maxAddress);
                toSort.remove(maxAddress);
            }
            return sorted;
        }
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
