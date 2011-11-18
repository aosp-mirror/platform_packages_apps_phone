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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAudioGateway;
import android.bluetooth.BluetoothAudioGateway.IncomingConnectionInfo;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.HeadsetBase;
import android.bluetooth.IBluetooth;
import android.bluetooth.IBluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.ParcelUuid;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides Bluetooth Headset and Handsfree profile, as a service in
 * the Phone application.
 * @hide
 */
public class BluetoothHeadsetService extends Service {
    private static final String TAG = "Bluetooth HSHFP";
    private static final boolean DBG = true;

    private static final String PREF_NAME = BluetoothHeadsetService.class.getSimpleName();
    private static final String PREF_LAST_HEADSET = "lastHeadsetAddress";

    private static final int PHONE_STATE_CHANGED = 1;

    private static final String BLUETOOTH_ADMIN_PERM = android.Manifest.permission.BLUETOOTH_ADMIN;
    private static final String BLUETOOTH_PERM = android.Manifest.permission.BLUETOOTH;

    private static boolean sHasStarted = false;

    private BluetoothDevice mDeviceSdpQuery;
    private BluetoothAdapter mAdapter;
    private IBluetooth mBluetoothService;
    private PowerManager mPowerManager;
    private BluetoothAudioGateway mAg;
    private BluetoothHandsfree mBtHandsfree;
    private ConcurrentHashMap<BluetoothDevice, BluetoothRemoteHeadset> mRemoteHeadsets;
    private BluetoothDevice mAudioConnectedDevice;

    @Override
    public void onCreate() {
        super.onCreate();
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mPowerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        mBtHandsfree = PhoneApp.getInstance().getBluetoothHandsfree();
        mAg = new BluetoothAudioGateway(mAdapter);
        IntentFilter filter = new IntentFilter(
                BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        registerReceiver(mBluetoothReceiver, filter);

        IBinder b = ServiceManager.getService(BluetoothAdapter.BLUETOOTH_SERVICE);
        if (b == null) {
            throw new RuntimeException("Bluetooth service not available");
        }
        mBluetoothService = IBluetooth.Stub.asInterface(b);
        mRemoteHeadsets = new ConcurrentHashMap<BluetoothDevice, BluetoothRemoteHeadset>();
   }

   private class BluetoothRemoteHeadset {
       private int mState;
       private int mAudioState;
       private int mHeadsetType;
       private HeadsetBase mHeadset;
       private IncomingConnectionInfo mIncomingInfo;

       BluetoothRemoteHeadset() {
           mState = BluetoothProfile.STATE_DISCONNECTED;
           mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
           mHeadset = null;
           mIncomingInfo = null;
           mAudioState = BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
       }

       BluetoothRemoteHeadset(int headsetType, IncomingConnectionInfo incomingInfo) {
           mState = BluetoothProfile.STATE_DISCONNECTED;
           mHeadsetType = headsetType;
           mHeadset = null;
           mIncomingInfo = incomingInfo;
           mAudioState = BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
       }
   }

   synchronized private BluetoothDevice getCurrentDevice() {
       for (BluetoothDevice device : mRemoteHeadsets.keySet()) {
           int state = mRemoteHeadsets.get(device).mState;
           if (state == BluetoothProfile.STATE_CONNECTING ||
               state == BluetoothProfile.STATE_CONNECTED) {
               return device;
           }
       }
       return null;
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
                }
                sHasStarted = true;
            }
        }
    }

    private final Handler mIncomingConnectionHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            synchronized(BluetoothHeadsetService.this) {
                IncomingConnectionInfo info = (IncomingConnectionInfo)msg.obj;
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
                      ") connection from " + info.mRemoteDevice + "on channel " +
                      info.mRfcommChan);

                int priority = BluetoothProfile.PRIORITY_OFF;
                HeadsetBase headset;
                priority = getPriority(info.mRemoteDevice);
                if (priority <= BluetoothProfile.PRIORITY_OFF) {
                    Log.i(TAG, "Rejecting incoming connection because priority = " + priority);

                    headset = new HeadsetBase(mPowerManager, mAdapter,
                                              info.mRemoteDevice,
                                              info.mSocketFd, info.mRfcommChan,
                                              null);
                    headset.disconnect();
                    try {
                        mBluetoothService.notifyIncomingConnection(info.mRemoteDevice.getAddress(),
                                                                   true);
                    } catch (RemoteException e) {
                        Log.e(TAG, "notifyIncomingConnection", e);
                    }
                    return;
                }

                BluetoothRemoteHeadset remoteHeadset;
                BluetoothDevice device = getCurrentDevice();

                int state = BluetoothProfile.STATE_DISCONNECTED;
                if (device != null) {
                    state = mRemoteHeadsets.get(device).mState;
                }

                switch (state) {
                case BluetoothProfile.STATE_DISCONNECTED:
                    // headset connecting us, lets join
                    remoteHeadset = new BluetoothRemoteHeadset(type, info);
                    mRemoteHeadsets.put(info.mRemoteDevice, remoteHeadset);

                    try {
                        mBluetoothService.notifyIncomingConnection(
                           info.mRemoteDevice.getAddress(), false);
                    } catch (RemoteException e) {
                        Log.e(TAG, "notifyIncomingConnection");
                    }
                    break;
                case BluetoothProfile.STATE_CONNECTING:
                    if (!info.mRemoteDevice.equals(device)) {
                        // different headset, ignoring
                        Log.i(TAG, "Already attempting connect to " + device +
                              ", disconnecting " + info.mRemoteDevice);

                        headset = new HeadsetBase(mPowerManager, mAdapter,
                                                  info.mRemoteDevice,
                                                  info.mSocketFd, info.mRfcommChan,
                                                  null);
                        headset.disconnect();
                        break;
                    }

                    // Incoming and Outgoing connections to the same headset.
                    // The state machine manager will cancel outgoing and accept the incoming one.
                    // Update the state
                    mRemoteHeadsets.get(info.mRemoteDevice).mHeadsetType = type;
                    mRemoteHeadsets.get(info.mRemoteDevice).mIncomingInfo = info;

                    try {
                        mBluetoothService.notifyIncomingConnection(
                            info.mRemoteDevice.getAddress(), false);
                    } catch (RemoteException e) {
                        Log.e(TAG, "notifyIncomingConnection");
                    }
                    break;
                case BluetoothProfile.STATE_CONNECTED:
                    Log.i(TAG, "Already connected to " + device + ", disconnecting " +
                            info.mRemoteDevice);
                    rejectIncomingConnection(info);
                    break;
                }
            }
        }
    };

    private void rejectIncomingConnection(IncomingConnectionInfo info) {
        HeadsetBase headset = new HeadsetBase(mPowerManager, mAdapter,
            info.mRemoteDevice, info.mSocketFd, info.mRfcommChan, null);
        headset.disconnect();
    }


    private final BroadcastReceiver mBluetoothReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            BluetoothDevice currDevice = getCurrentDevice();
            int state = BluetoothProfile.STATE_DISCONNECTED;
            if (currDevice != null) {
                state = mRemoteHeadsets.get(currDevice).mState;
            }

            if ((state == BluetoothProfile.STATE_CONNECTED ||
                    state == BluetoothProfile.STATE_CONNECTING) &&
                    action.equals(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED) &&
                    device.equals(currDevice)) {
                try {
                    mBinder.disconnect(currDevice);
                } catch (RemoteException e) {}
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                switch (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                                           BluetoothAdapter.ERROR)) {
                case BluetoothAdapter.STATE_ON:
                    mAg.start(mIncomingConnectionHandler);
                    mBtHandsfree.onBluetoothEnabled();
                    break;
                case BluetoothAdapter.STATE_TURNING_OFF:
                    mBtHandsfree.onBluetoothDisabled();
                    mAg.stop();
                    if (currDevice != null) {
                        try {
                            mBinder.disconnect(currDevice);
                        } catch (RemoteException e) {}
                    }
                    break;
                }
            } else if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                    mBtHandsfree.sendScoGainUpdate(intent.getIntExtra(
                            AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0));
                }

            } else if (action.equals(BluetoothDevice.ACTION_UUID)) {
                if (device.equals(mDeviceSdpQuery) && device.equals(currDevice)) {
                    // We have got SDP records for the device we are interested in.
                    getSdpRecordsAndConnect(device);
                }
            }
        }
    };

    private static final int CONNECT_HEADSET_DELAYED = 1;
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CONNECT_HEADSET_DELAYED:
                    BluetoothDevice device = (BluetoothDevice) msg.obj;
                    getSdpRecordsAndConnect(device);
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
            HeadsetBase headset = new HeadsetBase(mPowerManager, mAdapter,
                                                  device, channel);

            int result = waitForConnect(headset);

            if (result != EINTERRUPT && result != 1) {
                if (result == ECONNREFUSED && mDeviceSdpQuery == null) {
                    // The rfcomm channel number might have changed, do SDP
                    // query and try to connect again.
                    mDeviceSdpQuery = getCurrentDevice();
                    device.fetchUuidsWithSdp();
                    mConnectThread = null;
                    return;
                } else {
                    Log.i(TAG, "Trying to connect to rfcomm socket again after 1 sec");
                    try {
                      sleep(1000);  // 1 second
                    } catch (InterruptedException e) {
                      return;
                    }
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
            BluetoothDevice device = getCurrentDevice();
            if (device == null ||
                mRemoteHeadsets.get(device).mState != BluetoothProfile.STATE_CONNECTING) {
                return;  // stale events
            }

            switch (msg.what) {
            case RFCOMM_ERROR:
                if (DBG) log("Rfcomm error");
                mConnectThread = null;
                setState(device, BluetoothProfile.STATE_DISCONNECTED);
                break;
            case RFCOMM_CONNECTED:
                if (DBG) log("Rfcomm connected");
                mConnectThread = null;
                HeadsetBase headset = (HeadsetBase)msg.obj;
                setState(device, BluetoothProfile.STATE_CONNECTED);

                mRemoteHeadsets.get(device).mHeadset = headset;
                mBtHandsfree.connectHeadset(headset, mRemoteHeadsets.get(device).mHeadsetType);
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
                mBtHandsfree.resetAtState();
                mBtHandsfree.setVirtualCallInProgress(false);
                BluetoothDevice device = getCurrentDevice();
                if (device != null) {
                    setState(device, BluetoothProfile.STATE_DISCONNECTED);
                }
                break;
            }
        }
    };

    private synchronized void setState(BluetoothDevice device, int state) {
        int prevState = mRemoteHeadsets.get(device).mState;
        if (state != prevState) {
            if (DBG) log("Device: " + device +
                " Headset  state" + prevState + " -> " + state);
            if (prevState == BluetoothProfile.STATE_CONNECTED) {
                // Headset is disconnecting, stop the parser.
                mBtHandsfree.disconnectHeadset();
            }
            Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, state);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                mRemoteHeadsets.get(device).mHeadset = null;
                mRemoteHeadsets.get(device).mHeadsetType = BluetoothHandsfree.TYPE_UNKNOWN;
            }

            mRemoteHeadsets.get(device).mState = state;

            sendBroadcast(intent, BLUETOOTH_PERM);
            if (state == BluetoothHeadset.STATE_CONNECTED) {
                // Set the priority to AUTO_CONNECT
                setPriority(device, BluetoothHeadset.PRIORITY_AUTO_CONNECT);
                adjustOtherHeadsetPriorities(device);
            }
            try {
                mBluetoothService.sendConnectionStateChange(device, BluetoothProfile.HEADSET,
                                                            state, prevState);
            } catch (RemoteException e) {
                Log.e(TAG, "sendConnectionStateChange: exception");
            }
       }
    }

    private void adjustOtherHeadsetPriorities(BluetoothDevice connectedDevice) {
       for (BluetoothDevice device : mAdapter.getBondedDevices()) {
          if (getPriority(device) >= BluetoothHeadset.PRIORITY_AUTO_CONNECT &&
              !device.equals(connectedDevice)) {
              setPriority(device, BluetoothHeadset.PRIORITY_ON);
          }
       }
    }

    private void setPriority(BluetoothDevice device, int priority) {
        try {
            mBinder.setPriority(device, priority);
        } catch (RemoteException e) {
            Log.e(TAG, "Error while setting priority for: " + device);
        }
    }

    private int getPriority(BluetoothDevice device) {
        try {
            return mBinder.getPriority(device);
        } catch (RemoteException e) {
            Log.e(TAG, "Error while getting priority for: " + device);
        }
        return BluetoothProfile.PRIORITY_UNDEFINED;
    }

    private synchronized void getSdpRecordsAndConnect(BluetoothDevice device) {
        if (!device.equals(getCurrentDevice())) {
            // stale
            return;
        }

        // Check if incoming connection has already connected.
        if (mRemoteHeadsets.get(device).mState == BluetoothProfile.STATE_CONNECTED) {
            return;
        }

        ParcelUuid[] uuids = device.getUuids();
        ParcelUuid[] localUuids = mAdapter.getUuids();
        int type = BluetoothHandsfree.TYPE_UNKNOWN;
        if (uuids != null) {
            if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree) &&
                BluetoothUuid.isUuidPresent(localUuids, BluetoothUuid.Handsfree_AG)) {
                log("SDP UUID: TYPE_HANDSFREE");
                type = BluetoothHandsfree.TYPE_HANDSFREE;
                mRemoteHeadsets.get(device).mHeadsetType = type;
                int channel = device.getServiceChannel(BluetoothUuid.Handsfree);
                mConnectThread = new RfcommConnectThread(device, channel, type);
                if (mAdapter.isDiscovering()) {
                    mAdapter.cancelDiscovery();
                }
                mConnectThread.start();
                if (getPriority(device) < BluetoothHeadset.PRIORITY_AUTO_CONNECT) {
                    setPriority(device, BluetoothHeadset.PRIORITY_AUTO_CONNECT);
                }
                return;
            } else if (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP) &&
                BluetoothUuid.isUuidPresent(localUuids, BluetoothUuid.HSP_AG)) {
                log("SDP UUID: TYPE_HEADSET");
                type = BluetoothHandsfree.TYPE_HEADSET;
                mRemoteHeadsets.get(device).mHeadsetType = type;
                int channel = device.getServiceChannel(BluetoothUuid.HSP);
                mConnectThread = new RfcommConnectThread(device, channel, type);
                if (mAdapter.isDiscovering()) {
                    mAdapter.cancelDiscovery();
                }
                mConnectThread.start();
                if (getPriority(device) < BluetoothHeadset.PRIORITY_AUTO_CONNECT) {
                    setPriority(device, BluetoothHeadset.PRIORITY_AUTO_CONNECT);
                }
                return;
            }
        }
        log("SDP UUID: TYPE_UNKNOWN");
        mRemoteHeadsets.get(device).mHeadsetType = type;
        setState(device, BluetoothProfile.STATE_DISCONNECTED);
        return;
    }

    /**
     * Handlers for incoming service calls
     */
    private final IBluetoothHeadset.Stub mBinder = new IBluetoothHeadset.Stub() {
        public int getConnectionState(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            BluetoothRemoteHeadset headset = mRemoteHeadsets.get(device);
            if (headset == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return headset.mState;
        }

        public List<BluetoothDevice> getConnectedDevices() {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            return getDevicesMatchingConnectionStates(
                new int[] {BluetoothProfile.STATE_CONNECTED});
        }

        public boolean connect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                BluetoothDevice currDevice = getCurrentDevice();

                if (currDevice == device ||
                    getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
                    return false;
                }
                if (currDevice != null) {
                    disconnect(currDevice);
                }
                try {
                    return mBluetoothService.connectHeadset(device.getAddress());
                } catch (RemoteException e) {
                    Log.e(TAG, "connectHeadset");
                    return false;
                }
            }
        }

        public boolean disconnect(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                                           "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                BluetoothRemoteHeadset headset = mRemoteHeadsets.get(device);
                if (headset == null ||
                    headset.mState == BluetoothProfile.STATE_DISCONNECTED ||
                    headset.mState == BluetoothProfile.STATE_DISCONNECTING) {
                    return false;
                }
                try {
                    return mBluetoothService.disconnectHeadset(device.getAddress());
                } catch (RemoteException e) {
                    Log.e(TAG, "disconnectHeadset");
                    return false;
                }
            }
        }

        public synchronized boolean isAudioConnected(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            if (device.equals(mAudioConnectedDevice)) return true;
            return false;
        }

        public synchronized List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            List<BluetoothDevice> headsets = new ArrayList<BluetoothDevice>();
            for (BluetoothDevice device: mRemoteHeadsets.keySet()) {
                int headsetState = getConnectionState(device);
                for (int state : states) {
                    if (state == headsetState) {
                        headsets.add(device);
                        break;
                    }
                }
            }
            return headsets;
        }

        public boolean startVoiceRecognition(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (device == null ||
                    mRemoteHeadsets.get(device) == null ||
                    mRemoteHeadsets.get(device).mState != BluetoothProfile.STATE_CONNECTED) {
                    return false;
                }
                return mBtHandsfree.startVoiceRecognition();
            }
        }

        public boolean stopVoiceRecognition(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (device == null ||
                    mRemoteHeadsets.get(device) == null ||
                    mRemoteHeadsets.get(device).mState != BluetoothProfile.STATE_CONNECTED) {
                    return false;
                }

                return mBtHandsfree.stopVoiceRecognition();
            }
        }

        public int getBatteryUsageHint(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");

            return HeadsetBase.getAtInputCount();
        }

        public int getPriority(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                int priority = Settings.Secure.getInt(getContentResolver(),
                        Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                        BluetoothProfile.PRIORITY_UNDEFINED);
                return priority;
            }
        }

        public boolean setPriority(BluetoothDevice device, int priority) {
            enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM,
                "Need BLUETOOTH_ADMIN permission");
            synchronized (BluetoothHeadsetService.this) {
                Settings.Secure.putInt(getContentResolver(),
                        Settings.Secure.getBluetoothHeadsetPriorityKey(device.getAddress()),
                        priority);
                if (DBG) log("Saved priority " + device + " = " + priority);
                return true;
            }
        }

        public boolean createIncomingConnect(BluetoothDevice device) {
            synchronized (BluetoothHeadsetService.this) {
                HeadsetBase headset;
                setState(device, BluetoothProfile.STATE_CONNECTING);

                IncomingConnectionInfo info = mRemoteHeadsets.get(device).mIncomingInfo;
                headset = new HeadsetBase(mPowerManager, mAdapter,
                                          device,
                                          info.mSocketFd, info.mRfcommChan,
                                          mConnectedStatusHandler);

                mRemoteHeadsets.get(device).mHeadset = headset;

                mConnectingStatusHandler.obtainMessage(RFCOMM_CONNECTED, headset).sendToTarget();
                return true;
            }
        }

        public boolean startScoUsingVirtualVoiceCall(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (device == null ||
                    mRemoteHeadsets.get(device) == null ||
                    mRemoteHeadsets.get(device).mState != BluetoothProfile.STATE_CONNECTED ||
                    getAudioState(device) != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    return false;
                }
                return mBtHandsfree.initiateScoUsingVirtualVoiceCall();
            }
        }

        public boolean stopScoUsingVirtualVoiceCall(BluetoothDevice device) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            synchronized (BluetoothHeadsetService.this) {
                if (device == null ||
                    mRemoteHeadsets.get(device) == null ||
                    mRemoteHeadsets.get(device).mState != BluetoothProfile.STATE_CONNECTED ||
                    getAudioState(device) == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    return false;
                }
                return mBtHandsfree.terminateScoUsingVirtualVoiceCall();
            }
        }

        public boolean rejectIncomingConnect(BluetoothDevice device) {
            synchronized (BluetoothHeadsetService.this) {
                BluetoothRemoteHeadset headset = mRemoteHeadsets.get(device);
                if (headset != null) {
                    IncomingConnectionInfo info = headset.mIncomingInfo;
                    rejectIncomingConnection(info);
                } else {
                    Log.e(TAG, "Error no record of remote headset");
                }
                return true;
            }
        }

        public boolean acceptIncomingConnect(BluetoothDevice device) {
            synchronized (BluetoothHeadsetService.this) {
                HeadsetBase headset;
                BluetoothRemoteHeadset cachedHeadset = mRemoteHeadsets.get(device);
                if (cachedHeadset == null) {
                    Log.e(TAG, "Cached Headset is Null in acceptIncomingConnect");
                    return false;
                }
                IncomingConnectionInfo info = cachedHeadset.mIncomingInfo;
                headset = new HeadsetBase(mPowerManager, mAdapter,
                                          device,
                                          info.mSocketFd, info.mRfcommChan,
                                          mConnectedStatusHandler);

                setState(device, BluetoothProfile.STATE_CONNECTED);

                cachedHeadset.mHeadset = headset;
                mBtHandsfree.connectHeadset(headset, cachedHeadset.mHeadsetType);

                if (DBG) log("Successfully used incoming connection");
                return true;
            }
        }

        public  boolean cancelConnectThread() {
            synchronized (BluetoothHeadsetService.this) {
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
                return true;
            }
        }

        public boolean connectHeadsetInternal(BluetoothDevice device) {
            synchronized (BluetoothHeadsetService.this) {
                BluetoothDevice currDevice = getCurrentDevice();
                if (currDevice == null) {
                    BluetoothRemoteHeadset headset = new BluetoothRemoteHeadset();
                    mRemoteHeadsets.put(device, headset);

                    setState(device, BluetoothProfile.STATE_CONNECTING);
                    if (device.getUuids() == null) {
                        // We might not have got the UUID change notification from
                        // Bluez yet, if we have just paired. Try after 1.5 secs.
                        Message msg = new Message();
                        msg.what = CONNECT_HEADSET_DELAYED;
                        msg.obj = device;
                        mHandler.sendMessageDelayed(msg, 1500);
                    } else {
                        getSdpRecordsAndConnect(device);
                    }
                    return true;
                } else {
                      Log.w(TAG, "connectHeadset(" + device + "): failed: already in state " +
                            mRemoteHeadsets.get(currDevice).mState +
                            " with headset " + currDevice);
                }
                return false;
            }
        }

        public boolean disconnectHeadsetInternal(BluetoothDevice device) {
            synchronized (BluetoothHeadsetService.this) {
                BluetoothRemoteHeadset remoteHeadset = mRemoteHeadsets.get(device);
                if (remoteHeadset == null) return false;

                if (remoteHeadset.mState == BluetoothProfile.STATE_CONNECTED) {
                    // Send a dummy battery level message to force headset
                    // out of sniff mode so that it will immediately notice
                    // the disconnection. We are currently sending it for
                    // handsfree only.
                    // TODO: Call hci_conn_enter_active_mode() from
                    // rfcomm_send_disc() in the kernel instead.
                    // See http://b/1716887
                    setState(device, BluetoothProfile.STATE_DISCONNECTING);

                    HeadsetBase headset = remoteHeadset.mHeadset;
                    if (remoteHeadset.mHeadsetType == BluetoothHandsfree.TYPE_HANDSFREE) {
                        headset.sendURC("+CIEV: 7,3");
                    }

                    if (headset != null) {
                        headset.disconnect();
                        headset = null;
                    }
                    setState(device, BluetoothProfile.STATE_DISCONNECTED);
                    return true;
                } else if (remoteHeadset.mState == BluetoothProfile.STATE_CONNECTING) {
                    // The state machine would have canceled the connect thread.
                    // Just set the state here.
                    setState(device, BluetoothProfile.STATE_DISCONNECTED);
                    return true;
                }
                return false;
            }
        }

        public boolean setAudioState(BluetoothDevice device, int state) {
            // mRemoteHeadsets handles put/get concurrency by itself
            int prevState = mRemoteHeadsets.get(device).mAudioState;
            mRemoteHeadsets.get(device).mAudioState = state;
            if (state == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                mAudioConnectedDevice = device;
            } else if (state == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                mAudioConnectedDevice = null;
            }
            Intent intent = new Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intent.putExtra(BluetoothHeadset.EXTRA_STATE, state);
            intent.putExtra(BluetoothHeadset.EXTRA_PREVIOUS_STATE, prevState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            sendBroadcast(intent, android.Manifest.permission.BLUETOOTH);
            if (DBG) log("AudioStateIntent: " + device + " State: " + state
                         + " PrevState: " + prevState);
            return true;
        }

        public int getAudioState(BluetoothDevice device) {
            // mRemoteHeadsets handles put/get concurrency by itself
            BluetoothRemoteHeadset headset = mRemoteHeadsets.get(device);
            if (headset == null) return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;

            return headset.mAudioState;
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
        BluetoothDevice device = getCurrentDevice();
        if (device != null) {
            setState(device, BluetoothProfile.STATE_DISCONNECTED);
        }
    }



    private static void log(String msg) {
        Log.d(TAG, msg);
    }
}
