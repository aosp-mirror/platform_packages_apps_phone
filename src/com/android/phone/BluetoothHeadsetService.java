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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.HeadsetBase;
import android.bluetooth.IBluetoothHeadset;
import android.os.ParcelUuid;
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
import java.util.Set;

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

    private BluetoothDevice mDeviceSdpQuery;
    private BluetoothAdapter mAdapter;
    private PowerManager mPowerManager;
    private BluetoothAudioGateway mAg;
    private HeadsetBase mHeadset;
    private int mState;
    private int mHeadsetType;
    private BluetoothHandsfree mBtHandsfree;
    private BluetoothDevice mRemoteDevice;
    private LinkedList<BluetoothDevice> mAutoConnectQueue;
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
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mBtHandsfree = PhoneApp.getInstance().getBluetoothHandsfree();
        mAg = new BluetoothAudioGateway(mAdapter);
        mPhone = PhoneFactory.getDefaultPhone();
        mRingingCall = mPhone.getRingingCall();
        mForegroundCall = mPhone.getForegroundCall();
        if (mAdapter.isEnabled()) {
            mHeadsetPriority.load();
        }
        IntentFilter filter = new IntentFilter(
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        registerReceiver(mBluetoothReceiver, filter);
   }

    @Override
    public void onStart(Intent intent, int startId) {
         if (mAdapter == null) {
            Log.w(TAG, "Stopping BluetoothHeadsetService: device does not have BT");
            stopSelf();
        } else {
            if (!sHasStarted) {
                if (DBG) log("Starting BluetoothHeadsetService");
                if (mAdapter.isEnabled()) {
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
                  ") connection from " + info.mRemoteDevice + "on channel " + info.mRfcommChan);

            int priority = BluetoothHeadset.PRIORITY_OFF;
            HeadsetBase headset;
            try {
                priority = mBinder.getPriority(info.mRemoteDevice);
            } catch (RemoteException e) {}
            if (priority <= BluetoothHeadset.PRIORITY_OFF) {
                Log.i(TAG, "Rejecting incoming connection because priority = " + priority);

                headset = new HeadsetBase(mPowerManager, mAdapter, info.mRemoteDevice,
                        info.mSocketFd, info.mRfcommChan, null);
                headset.disconnect();
                return;
            }
            switch (mState) {
            case BluetoothHeadset.STATE_DISCONNECTED:
                // headset connecting us, lets join
                mRemoteDevice = info.mRemoteDevice;
                setState(BluetoothHeadset.STATE_CONNECTING);
                headset = new HeadsetBase(mPowerManager, mAdapter, mRemoteDevice, info.mSocketFd,
                        info.mRfcommChan, mConnectedStatusHandler);
                mHeadsetType = type;

                mConnectingStatusHandler.obtainMessage(RFCOMM_CONNECTED, headset).sendToTarget();

                break;
            case BluetoothHeadset.STATE_CONNECTING:
                if (!info.mRemoteDevice.equals(mRemoteDevice)) {
                    // different headset, ignoring
                    Log.i(TAG, "Already attempting connect to " + mRemoteDevice +
                          ", disconnecting " + info.mRemoteDevice);

                    headset = new HeadsetBase(mPowerManager, mAdapter, info.mRemoteDevice,
                            info.mSocketFd, info.mRfcommChan, null);
                    headset.disconnect();
                }
                // If we are here, we are in danger of a race condition
                // incoming rfcomm connection, but we are also attempting an
                // outgoing connection. Lets try and interrupt the outgoing
                // connection.
                Log.i(TAG, "Incoming and outgoing connections to " + info.mRemoteDevice +
                            ". Cancel outgoing connection.");
                if (mConnectThread != null) {
                    mConnectThread.interrupt();
                    mConnectThread = null;
                }

                // Now continue with new connection, including calling callback
                mHeadset = new HeadsetBase(mPowerManager, mAdapter, mRemoteDevice,
                        info.mSocketFd, info.mRfcommChan, mConnectedStatusHandler);
                mHeadsetType = type;

                setState(BluetoothHeadset.STATE_CONNECTED, BluetoothHeadset.RESULT_SUCCESS);
                mBtHandsfree.connectHeadset(mHeadset, mHeadsetType);

                if (DBG) log("Successfully used incoming connection");
                break;
            case BluetoothHeadset.STATE_CONNECTED:
                Log.i(TAG, "Already connected to " + mRemoteDevice + ", disconnecting " +
                      info.mRemoteDevice);

                headset = new HeadsetBase(mPowerManager, mAdapter, info.mRemoteDevice,
                        info.mSocketFd, info.mRfcommChan, null);
                headset.disconnect();
                break;
            }
        }
    };

    private synchronized void autoConnectHeadset() {
        if (DBG && debugDontReconnect()) {
            return;
        }
        if (mAdapter.isEnabled()) {
            try {
                mBinder.connectHeadset(null);
            } catch (RemoteException e) {}
        }
    }

    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            if ((mState == BluetoothHeadset.STATE_CONNECTED ||
                    mState == BluetoothHeadset.STATE_CONNECTING) &&
                    action.equals(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED) &&
                    device.equals(mRemoteDevice)) {
                try {
                    mBinder.disconnectHeadset();
                } catch (RemoteException e) {}
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                switch (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                           BluetoothAdapter.ERROR)) {
                case BluetoothAdapter.STATE_ON:
                    mHeadsetPriority.load();
                    mHandler.sendMessageDelayed(mHandler.obtainMessage(RECONNECT_LAST_HEADSET), 8000);
                    mAg.start(mIncomingConnectionHandler);
                    mBtHandsfree.onBluetoothEnabled();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    mBtHandsfree.onBluetoothDisabled();
                    mAg.stop();
                    setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE,
                             BluetoothHeadset.LOCAL_DISCONNECT);
                    break;
                }
            } else if (action.equals(BluetoothDevice.ACTION_BOND_STATE_CHANGED)) {
                int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                                                   BluetoothDevice.ERROR);
                switch(bondState) {
                case BluetoothDevice.BOND_BONDED:
                    if (mHeadsetPriority.get(device) == BluetoothHeadset.PRIORITY_UNDEFINED) {
                        mHeadsetPriority.set(device, BluetoothHeadset.PRIORITY_ON);
                    }
                    break;
                case BluetoothDevice.BOND_NONE:
                    mHeadsetPriority.set(device, BluetoothHeadset.PRIORITY_UNDEFINED);
                    break;
                }
            } else if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    mBtHandsfree.sendScoGainUpdate(intent.getIntExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0));
                }

            } else if (action.equals(BluetoothDevice.ACTION_UUID)) {
                if (device.equals(mDeviceSdpQuery)) {
                    // We have got SDP records for the device we are interested in.
                    getSdpRecordsAndConnect();
                }
            }
        }
    };

    private static final int RECONNECT_LAST_HEADSET = 1;
    private static final int CONNECT_HEADSET_DELAYED = 2;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case RECONNECT_LAST_HEADSET:
                autoConnectHeadset();
                break;
            case CONNECT_HEADSET_DELAYED:
                getSdpRecordsAndConnect();
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
    private static final int RFCOMM_CONNECTED             = 1;
    private static final int RFCOMM_ERROR                 = 2;

    private long mTimestamp;

    /**
     * Thread for RFCOMM connection
     * Messages are sent to mConnectingStatusHandler as connection progresses.
     */
    private RfcommConnectThread mConnectThread;
    private class RfcommConnectThread extends Thread {
        private BluetoothDevice device;
        private int channel;
        private int type;

        private static final int EINTERRUPT = -1000;
        private static final int ECONNREFUSED = -111;

        public RfcommConnectThread(BluetoothDevice device, int channel, int type) {
            super();
            this.device = device;
            this.channel = channel;
            this.type = type;
        }

        private int waitForConnect(HeadsetBase headset) {
            // Try to connect for 20 seconds
            int result = 0;
            for (int i=0; i < 40 && result == 0; i++) {
                // waitForAsyncConnect returns 0 on timeout, 1 on success, < 0 on error.
                result = headset.waitForAsyncConnect(500, mConnectedStatusHandler);
                if (isInterrupted()) {
                    headset.disconnect();
                    return EINTERRUPT;
                }
            }
            return result;
        }

        @Override
        public void run() {
            long timestamp;

            timestamp = System.currentTimeMillis();
            HeadsetBase headset = new HeadsetBase(mPowerManager, mAdapter, device, channel);

            int result = waitForConnect(headset);

            if (result != EINTERRUPT && result != 1) {
                if (result == ECONNREFUSED && mDeviceSdpQuery == null) {
                    // The rfcomm channel number might have changed, do SDP
                    // query and try to connect again.
                    mDeviceSdpQuery = mRemoteDevice;
                    device.fetchUuidsWithSdp();
                    mConnectThread = null;
                    return;
                } else {
                    Log.i(TAG, "Trying to connect to rfcomm socket again after 1 sec");
                    try {
                      sleep(1000);  // 1 second
                    } catch (InterruptedException e) {}
                }
                result = waitForConnect(headset);
            }

            mDeviceSdpQuery = null;
            if (result == EINTERRUPT) return;

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
                mConnectingStatusHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
                Log.w(TAG, "mHeadset.waitForAsyncConnect() error: " + result + "(timeout)");
                return;
            } else {
                mConnectingStatusHandler.obtainMessage(RFCOMM_CONNECTED, headset).sendToTarget();
            }
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
            case RFCOMM_ERROR:
                if (DBG) log("Rfcomm error");
                mConnectThread = null;
                setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE,
                         BluetoothHeadset.LOCAL_DISCONNECT);
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
                setState(BluetoothHeadset.STATE_DISCONNECTED, BluetoothHeadset.RESULT_FAILURE,
                         BluetoothHeadset.REMOTE_DISCONNECT);
                break;
            }
        }
    };

    private void setState(int state) {
        setState(state, BluetoothHeadset.RESULT_SUCCESS);
    }

    private void setState(int state, int result) {
        setState(state, result, -1);
    }

    private synchronized void setState(int state, int result, int initiator) {
        if (state != mState) {
            if (DBG) log("Headset state " + mState + " -> " + state + ", result = " + result);
            if (mState == BluetoothHeadset.STATE_CONNECTED) {
                mBtHandsfree.disconnectHeadset();
            }
            Intent intent = new Intent(BluetoothHeadset.ACTION_STATE_CHANGED);
            intent.putExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, mState);
            mState = state;
            intent.putExtra(BluetoothHeadset.EXTRA_STATE, mState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, mRemoteDevice);
            // Add Extra EXTRA_DISCONNECT_INITIATOR for DISCONNECTED state
            if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                if (initiator == -1) {
                    log("Headset Disconnected Intent without Disconnect Initiator extra");
                } else {
                    intent.putExtra(BluetoothHeadset.EXTRA_DISCONNECT_INITIATOR,
                                    initiator);
                }
            }
            sendBroadcast(intent, BLUETOOTH_PERM);
            if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                mHeadset = null;
                mRemoteDevice = null;
                mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
                if (mAutoConnectQueue != null) {
                    doNextAutoConnect();
                }
            } else if (mState == BluetoothHeadset.STATE_CONNECTING) {
                // Set the priority to AUTO_CONNECT
                mHeadsetPriority.set(mRemoteDevice, BluetoothHeadset.PRIORITY_AUTO_CONNECT);
            } else if (mState == BluetoothHeadset.STATE_CONNECTED) {
                mAutoConnectQueue = null;  // cancel further auto-connection
                mHeadsetPriority.bump(mRemoteDevice);
            }
        }
    }

    private void getSdpRecordsAndConnect() {
        ParcelUuid[] uuids = mRemoteDevice.getUuids();
        if (uuids != null) {
            if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree)) {
                log("SDP UUID: TYPE_HANDSFREE");
                mHeadsetType = BluetoothHandsfree.TYPE_HANDSFREE;
                int channel = mRemoteDevice.getServiceChannel(BluetoothUuid.Handsfree);
                mConnectThread = new RfcommConnectThread(mRemoteDevice, channel, mHeadsetType);
                mConnectThread.start();
                return;
            } else if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP)) {
                log("SDP UUID: TYPE_HEADSET");
                mHeadsetType = BluetoothHandsfree.TYPE_HEADSET;
                int channel = mRemoteDevice.getServiceChannel(BluetoothUuid.HSP);
                mConnectThread = new RfcommConnectThread(mRemoteDevice, channel, mHeadsetType);
                mConnectThread.start();
                return;
            }
        }
        log("SDP UUID: TYPE_UNKNOWN");
        mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
        setState(BluetoothHeadset.STATE_DISCONNECTED,
                BluetoothHeadset.RESULT_FAILURE, BluetoothHeadset.LOCAL_DISCONNECT);
        return;
    }

    private synchronized boolean doNextAutoConnect() {
        if (mAutoConnectQueue == null || mAutoConnectQueue.size() == 0) {
            mAutoConnectQueue = null;
            return false;
        }
        mRemoteDevice = mAutoConnectQueue.removeFirst();
        // Don't auto connect with docks if we are docked with the dock.
        if (isPhoneDocked(mRemoteDevice)) return doNextAutoConnect();

        if (DBG) log("pulled " + mRemoteDevice + " off auto-connect queue");
        setState(BluetoothHeadset.STATE_CONNECTING);
        getSdpRecordsAndConnect();

        return true;
    }

    private boolean isPhoneDocked(BluetoothDevice autoConnectDevice) {
        // This works only because these broadcast intents are "sticky"
        Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_DOCK_EVENT));
        if (i != null) {
            int state = i.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED);
            if (state != Intent.EXTRA_DOCK_STATE_UNDOCKED) {
                BluetoothDevice device = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null && autoConnectDevice.equals(device)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothHeadset.Stub mBinder = new IBluetoothHeadset.Stub() {
        public int getState() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState;
        }
        public BluetoothDevice getCurrentHeadset() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (mState == BluetoothHeadset.STATE_DISCONNECTED) {
                return null;
            }
            return mRemoteDevice;
        }
        public boolean connectHeadset(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                if (mState == BluetoothHeadset.STATE_CONNECTED ||
                    mState == BluetoothHeadset.STATE_CONNECTING) {
                    Log.w(TAG, "connectHeadset(" + device + "): failed: already in state " +
                          mState + " with headset " + mRemoteDevice);
                    return false;
                }
                if (device == null) {
                    mAutoConnectQueue = mHeadsetPriority.getSorted();
                    return doNextAutoConnect();
                }
                mRemoteDevice = device;
                setState(BluetoothHeadset.STATE_CONNECTING);
                if (mRemoteDevice.getUuids() == null) {
                    // We might not have got the UUID change notification from
                    // Bluez yet, if we have just paired. Try after 1.5 secs.
                    mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(CONNECT_HEADSET_DELAYED), 1500);
                } else {
                    getSdpRecordsAndConnect();
                }
            }
            return true;
        }
        public boolean isConnected(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return mState == BluetoothHeadset.STATE_CONNECTED && mRemoteDevice.equals(device);
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
                             BluetoothHeadset.RESULT_CANCELED,
                             BluetoothHeadset.LOCAL_DISCONNECT);
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
                             BluetoothHeadset.RESULT_CANCELED,
                             BluetoothHeadset.LOCAL_DISCONNECT);
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
        public boolean setPriority(BluetoothDevice device, int priority) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            if (priority < BluetoothHeadset.PRIORITY_OFF) {
                return false;
            }
            mHeadsetPriority.set(device, priority);
            return true;
        }
        public int getPriority(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

            return mHeadsetPriority.get(device);
        }
        public int getBatteryUsageHint() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

            return HeadsetBase.getAtInputCount();
        }
    };

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) log("Stopping BluetoothHeadsetService");
        unregisterReceiver(mBluetoothReceiver);
        mBtHandsfree.onBluetoothDisabled();
        mAg.stop();
        sHasStarted = false;
        setState(BluetoothHeadset.STATE_DISCONNECTED,
                 BluetoothHeadset.RESULT_CANCELED,
                 BluetoothHeadset.LOCAL_DISCONNECT);
        mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
    }

    private class HeadsetPriority {
        private HashMap<BluetoothDevice, Integer> mPriority =
                new HashMap<BluetoothDevice, Integer>();

        public synchronized boolean load() {
            Set<BluetoothDevice> devices = mAdapter.getBondedDevices();
            if (devices == null) {
                return false;  // for example, bluetooth is off
            }
            for (BluetoothDevice device : devices) {
                load(device);
            }
            return true;
        }

        private synchronized int load(BluetoothDevice device) {
            int priority = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                    BluetoothHeadset.PRIORITY_UNDEFINED);
            mPriority.put(device, new Integer(priority));
            if (DBG) log("Loaded priority " + device + " = " + priority);
            return priority;
        }

        public synchronized int get(BluetoothDevice device) {
            Integer priority = mPriority.get(device);
            if (priority == null) {
                return load(device);
            }
            return priority.intValue();
        }

        public synchronized void set(BluetoothDevice device, int priority) {
            int oldPriority = get(device);
            if (oldPriority == priority) {
                return;
            }
            mPriority.put(device, new Integer(priority));
            Settings.Secure.putInt(getContentResolver(),
                    Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                    priority);
            if (DBG) log("Saved priority " + device + " = " + priority);
        }

        /** Mark this headset as highest priority */
        public synchronized void bump(BluetoothDevice device) {
            int oldPriority = get(device);
            int maxPriority = BluetoothHeadset.PRIORITY_AUTO_CONNECT;

            // Find max, not including given address
            for (BluetoothDevice d : mPriority.keySet()) {
                if (device.equals(d)) continue;
                int p = mPriority.get(d).intValue();
                if (p > maxPriority) {
                    maxPriority = p;
                }
            }
            if (maxPriority >= oldPriority) {
                int p = maxPriority + 1;
                set(device, p);
                if (p >= Integer.MAX_VALUE) {
                    rebalance();
                }
            }
        }

        /** shifts all non-zero priorities to be monotonically increasing from
         * PRIORITY_AUTO_CONNECT */
        private synchronized void rebalance() {
            LinkedList<BluetoothDevice> sorted = getSorted();
            if (DBG) log("Rebalancing " + sorted.size() + " headset priorities");

            ListIterator<BluetoothDevice> li = sorted.listIterator(sorted.size());
            int priority = BluetoothHeadset.PRIORITY_AUTO_CONNECT;
            while (li.hasPrevious()) {
                BluetoothDevice device = li.previous();
                set(device, priority);
                priority++;
            }
        }

        /** Get list of headsets sorted by decreasing priority.
         * Headsets with priority less than AUTO_CONNECT are not included */
        public synchronized LinkedList<BluetoothDevice> getSorted() {
            LinkedList<BluetoothDevice> sorted = new LinkedList<BluetoothDevice>();
            HashMap<BluetoothDevice, Integer> toSort =
                    new HashMap<BluetoothDevice, Integer>(mPriority);

            // add in sorted order. this could be more efficient.
            while (true) {
                BluetoothDevice maxDevice = null;
                int maxPriority = BluetoothHeadset.PRIORITY_AUTO_CONNECT;
                for (BluetoothDevice device : toSort.keySet()) {
                    int priority = toSort.get(device).intValue();
                    if (priority >= maxPriority) {
                        maxDevice = device;
                        maxPriority = priority;
                    }
                }
                if (maxDevice == null) {
                    break;
                }
                sorted.addLast(maxDevice);
                toSort.remove(maxDevice);
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
