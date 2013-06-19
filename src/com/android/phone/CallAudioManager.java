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

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneBase;
import com.android.internal.telephony.PhoneConstants;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

public class CallAudioManager {
    private static final String LOG_TAG = "CallAudioManager";
    private final boolean DBG = true;
    private final boolean VDBG = true;

    private static final boolean DBG_SETAUDIOMODE_STACK = false;

    private Context mContext;
    private BluetoothHeadset mBluetoothHeadset;
    private BluetoothAdapter mBluetoothAdapter;
    private boolean mBluetoothConnectionPending;
    private long mBluetoothConnectionRequestTime;

    /** Speaker state, persisting between wired headset connection events */
    private static boolean sIsSpeakerEnabled = false;

    /** Hash table to store mute (Boolean) values based upon the connection.*/
    private static Hashtable<Connection, Boolean> sConnectionMuteTable =
        new Hashtable<Connection, Boolean>();

    /** Phone state changed event*/
    private static final int PHONE_STATE_CHANGED = -1;

    private BluetoothProfile.ServiceListener mBluetoothProfileServiceListener =
            new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothHeadset = (BluetoothHeadset) proxy;
            if (VDBG) log("- Got BluetoothHeadset: " + mBluetoothHeadset);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mBluetoothHeadset = null;
        }
    };

    private int mSoundRoute = 0;

    public boolean canMute;
    public boolean muteIndicatorOn;

    /** In-call audio routing options; see switchInCallAudio(). */
    public enum InCallAudioMode {
        SPEAKER,    // Speakerphone
        BLUETOOTH,  // Bluetooth headset (if available)
        EARPIECE,   // Handset earpiece (or wired headset, if connected)
    }

    AudioManager mAudioManager;
    CallManager mCM;

    public CallAudioManager(Context context) {
        mContext = context;

        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mCM = CallManager.getInstance();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter != null) {
            mBluetoothAdapter.getProfileProxy(mContext, mBluetoothProfileServiceListener,
                                    BluetoothProfile.HEADSET);
        }

    }

    /**
     * Updates the value of the Mute settings for each connection as needed.
     */
    public void handlePhoneStateChange() {
        if (DBG) log("handlePhoneStateChange: updating mute state for each connection");

        // update the foreground connections, if there are new connections.
        // Have to get all foreground calls instead of the active one
        // because there may two foreground calls co-exist in shore period
        // (a racing condition based on which phone changes firstly)
        // Otherwise the connection may get deleted.
        List<Connection> fgConnections = new ArrayList<Connection>();
        for (Call fgCall : mCM.getForegroundCalls()) {
            if (!fgCall.isIdle()) {
                fgConnections.addAll(fgCall.getConnections());
            }
        }
        for (Connection cn : fgConnections) {
            if (sConnectionMuteTable.get(cn) == null) {
                sConnectionMuteTable.put(cn, Boolean.FALSE);
            }
        }

        // mute is connection based operation, we need loop over
        // all background calls instead of the first one to update
        // the background connections, if there are new connections.
        List<Connection> bgConnections = new ArrayList<Connection>();
        for (Call bgCall : mCM.getBackgroundCalls()) {
            if (!bgCall.isIdle()) {
                bgConnections.addAll(bgCall.getConnections());
            }
        }
        for (Connection cn : bgConnections) {
            if (sConnectionMuteTable.get(cn) == null) {
              sConnectionMuteTable.put(cn, Boolean.FALSE);
            }
        }

        // Check to see if there are any lingering connections here
        // (disconnected connections), use old-school iterators to avoid
        // concurrent modification exceptions.
        Connection cn;
        for (Iterator<Connection> cnlist = sConnectionMuteTable.keySet().iterator();
                cnlist.hasNext();) {
            cn = cnlist.next();
            if (!fgConnections.contains(cn) && !bgConnections.contains(cn)) {
                if (DBG) log("connection '" + cn + "' not accounted for, removing.");
                cnlist.remove();
            }
        }

        // Restore the mute state of the foreground call if we're not IDLE,
        // otherwise just clear the mute state. This is really saying that
        // as long as there is one or more connections, we should update
        // the mute state with the earliest connection on the foreground
        // call, and that with no connections, we should be back to a
        // non-mute state.
        if (mCM.getState() != PhoneConstants.State.IDLE) {
            restoreMuteState();
        } else {
            setMuteInternal(mCM.getFgPhone(), false);
        }
    }

    //
    // Bluetooth helper methods.
    //
    // - BluetoothAdapter is the Bluetooth system service.  If
    //   getDefaultAdapter() returns null
    //   then the device is not BT capable.  Use BluetoothDevice.isEnabled()
    //   to see if BT is enabled on the device.
    //
    // - BluetoothHeadset is the API for the control connection to a
    //   Bluetooth Headset.  This lets you completely connect/disconnect a
    //   headset (which we don't do from the Phone UI!) but also lets you
    //   get the address of the currently active headset and see whether
    //   it's currently connected.

    /**
     * @return true if the Bluetooth on/off switch in the UI should be
     *         available to the user (i.e. if the device is BT-capable
     *         and a headset is connected.)
     */
    /* package */ boolean isBluetoothAvailable() {
        if (VDBG) log("isBluetoothAvailable()...");

        // There's no need to ask the Bluetooth system service if BT is enabled:
        //
        //    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        //    if ((adapter == null) || !adapter.isEnabled()) {
        //        if (DBG) log("  ==> FALSE (BT not enabled)");
        //        return false;
        //    }
        //    if (DBG) log("  - BT enabled!  device name " + adapter.getName()
        //                 + ", address " + adapter.getAddress());
        //
        // ...since we already have a BluetoothHeadset instance.  We can just
        // call isConnected() on that, and assume it'll be false if BT isn't
        // enabled at all.

        // Check if there's a connected headset, using the BluetoothHeadset API.
        boolean isConnected = false;
        if (mBluetoothHeadset != null) {
            List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();

            if (deviceList.size() > 0) {
                BluetoothDevice device = deviceList.get(0);
                isConnected = true;

                if (VDBG) log("  - headset state = " +
                              mBluetoothHeadset.getConnectionState(device));
                if (VDBG) log("  - headset address: " + device);
                if (VDBG) log("  - isConnected: " + isConnected);
            }
        }

        if (VDBG) log("  ==> " + isConnected);
        return isConnected;
    }

    /**
     * @return true if a BT Headset is available, and its audio is currently connected.
     */
    /* package */ boolean isBluetoothAudioConnected() {
        if (mBluetoothHeadset == null) {
            if (VDBG) log("isBluetoothAudioConnected: ==> FALSE (null mBluetoothHeadset)");
            return false;
        }
        List<BluetoothDevice> deviceList = mBluetoothHeadset.getConnectedDevices();

        if (deviceList.isEmpty()) {
            return false;
        }
        BluetoothDevice device = deviceList.get(0);
        boolean isAudioOn = mBluetoothHeadset.isAudioConnected(device);
        if (VDBG) log("isBluetoothAudioConnected: ==> isAudioOn = " + isAudioOn);
        return isAudioOn;
    }

    /* package */ void connectBluetoothAudio() {
//      if (VDBG)
          log("connectBluetoothAudio()...");
      if (mBluetoothHeadset != null) {
          // TODO(BT) check return
          mBluetoothHeadset.connectAudio();
      }

      // Watch out: The bluetooth connection doesn't happen instantly;
      // the connectAudio() call returns instantly but does its real
      // work in another thread.  The mBluetoothConnectionPending flag
      // is just a little trickery to ensure that the onscreen UI updates
      // instantly. (See isBluetoothAudioConnectedOrPending() above.)
      mBluetoothConnectionPending = true;
      mBluetoothConnectionRequestTime = SystemClock.elapsedRealtime();
  }

    /* package */ void disconnectBluetoothAudio() {
//        if (VDBG)
            log("disconnectBluetoothAudio()...");
        if (mBluetoothHeadset != null) {
            mBluetoothHeadset.disconnectAudio();
        }
        mBluetoothConnectionPending = false;
    }

    /**
     * Switches the current routing of in-call audio between speaker,
     * bluetooth, and the built-in earpiece (or wired headset.)
     *
     * This method is used on devices that provide a single 3-way switch
     * for audio routing.  For devices that provide separate toggles for
     * Speaker and Bluetooth, see toggleBluetooth() and toggleSpeaker().
     *
     * TODO: UI design is still in flux.  If we end up totally
     * eliminating the concept of Speaker and Bluetooth toggle buttons,
     * we can get rid of toggleBluetooth() and toggleSpeaker().
     */
    public void switchInCallAudio(InCallAudioMode newMode) {
        log("switchInCallAudio: new mode = " + newMode);
        switch (newMode) {
            case SPEAKER:
                if (!isSpeakerOn()) {
                    // Switch away from Bluetooth, if it was active.
                    if (isBluetoothAvailable() && isBluetoothAudioConnected()) {
                        disconnectBluetoothAudio();
                    }
                    turnOnSpeaker(true, true);
                }
                mSoundRoute = 0;
                break;

            case BLUETOOTH:
                // If already connected to BT, there's nothing to do here.
                if (isBluetoothAvailable() && !isBluetoothAudioConnected()) {
                    // Manually turn the speaker phone off, instead of allowing the
                    // Bluetooth audio routing to handle it, since there's other
                    // important state-updating that needs to happen in the
                    // PhoneUtils.turnOnSpeaker() method.
                    // (Similarly, whenever the user turns *on* the speaker, we
                    // manually disconnect the active bluetooth headset;
                    // see toggleSpeaker() and/or switchInCallAudio().)
                    if (isSpeakerOn()) {
                        turnOnSpeaker(false, true);
                    }
                    connectBluetoothAudio();
                }
                mSoundRoute = 1;
                break;

            case EARPIECE:
                // Switch to either the handset earpiece, or the wired headset (if connected.)
                // (Do this by simply making sure both speaker and bluetooth are off.)
                if (isBluetoothAvailable() && isBluetoothAudioConnected()) {
                    disconnectBluetoothAudio();
                }
                if (isSpeakerOn()) {
                    turnOnSpeaker(false, true);
                }
                mSoundRoute = 2;
                break;

            default:
                Log.wtf(LOG_TAG, "switchInCallAudio: unexpected mode " + newMode);
                break;
        }

        // And finally, update the InCallTouchUi widget (since the "audio
        // mode" button might need to change its appearance based on the
        // new audio state.)
//        updateInCallTouchUi();

    }

    /*
     * onMuteClick is called only when there is a foreground call
     */
    void switchMuteState() {
        boolean newMuteState = !getMute();
        log("onMuteClick(): newMuteState = " + newMuteState);
        setMute(newMuteState);
    }

    public int getSoundRoute() {
        return mSoundRoute;
    }

    public boolean isSpeakerOn() {
        return mAudioManager.isSpeakerphoneOn();
     }

    /**
     * Turns on/off speaker.
     *
     * @param context Context
     * @param flag True when speaker should be on. False otherwise.
     * @param store True when the settings should be stored in the device.
     */
    /* package */ void turnOnSpeaker( boolean flag, boolean store) {
        if (DBG) log("turnOnSpeaker(flag=" + flag + ", store=" + store + ")...");
        mAudioManager.setSpeakerphoneOn(flag);

        // record the speaker-enable value
        if (store) {
            sIsSpeakerEnabled = flag;
        }

        // Update the status bar icon
//        app.notificationMgr.updateSpeakerNotification(flag);

        // We also need to make a fresh call to PhoneApp.updateWakeState()
        // any time the speaker state changes, since the screen timeout is
        // sometimes different depending on whether or not the speaker is
        // in use.
        //app.updateWakeState();

        // Update the Proximity sensor based on speaker state
        //app.updateProximitySensorMode(app.mCM.getState());

        mCM.setEchoSuppressionEnabled(flag);
    }

    /**
     * Get the mute state of foreground phone, which has the current
     * foreground call
     */
    boolean getMute() {
        boolean routeToAudioManager = true;
//            app.getResources().getBoolean(R.bool.send_mic_mute_to_AudioManager);
        if (routeToAudioManager) {
            return mAudioManager.isMicrophoneMute();
        } else {
            return mCM.getMute();
        }
    }

    /**
     *
     * Mute / umute the foreground phone, which has the current foreground call
     *
     * All muting / unmuting from the in-call UI should go through this
     * wrapper.
     *
     * Wrapper around Phone.setMute() and setMicrophoneMute().
     * It also updates the connectionMuteTable and mute icon in the status bar.
     *
     */
    void setMute(boolean muted) {
        // make the call to mute the audio
        setMuteInternal(mCM.getFgPhone(), muted);

        // update the foreground connections to match.  This includes
        // all the connections on conference calls.
        for (Connection cn : mCM.getActiveFgCall().getConnections()) {
            if (sConnectionMuteTable.get(cn) == null) {
                if (DBG) log("problem retrieving mute value for this connection.");
            }
            sConnectionMuteTable.put(cn, Boolean.valueOf(muted));
        }
    }

    /**
     * Internally used muting function.
     */
    private void setMuteInternal(Phone phone, boolean muted) {
//        final PhoneGlobals app = PhoneGlobals.getInstance();
        Context context = phone.getContext();
        boolean routeToAudioManager = true;
//        TODO this need to work as Vanilla.
//            context.getResources().getBoolean(R.bool.send_mic_mute_to_AudioManager);
        if (routeToAudioManager) {
            if (DBG) log("setMuteInternal: using setMicrophoneMute(" + muted + ")...");
            mAudioManager.setMicrophoneMute(muted);
        } else {
            if (DBG) log("setMuteInternal: using phone.setMute(" + muted + ")...");
            phone.setMute(muted);
        }
//        app.notificationMgr.updateMuteNotification();
    }

    /**
     * Restore the mute setting from the earliest connection of the
     * foreground call.
     */
    Boolean restoreMuteState() {
        Phone phone = mCM.getFgPhone();

        //get the earliest connection
        Connection c = phone.getForegroundCall().getEarliestConnection();

        // only do this if connection is not null.
        if (c != null) {

            int phoneType = phone.getPhoneType();

            // retrieve the mute value.
            Boolean shouldMute = null;

            // In CDMA, mute is not maintained per Connection. Single mute apply for
            // a call where  call can have multiple connections such as
            // Three way and Call Waiting.  Therefore retrieving Mute state for
            // latest connection can apply for all connection in that call
            if (phoneType == PhoneConstants.PHONE_TYPE_CDMA) {
                shouldMute = sConnectionMuteTable.get(
                        phone.getForegroundCall().getLatestConnection());
            } else if ((phoneType == PhoneConstants.PHONE_TYPE_GSM)
                    || (phoneType == PhoneConstants.PHONE_TYPE_SIP)) {
                shouldMute = sConnectionMuteTable.get(c);
            }
            if (shouldMute == null) {
                if (DBG) log("problem retrieving mute value for this connection.");
                shouldMute = Boolean.FALSE;
            }

            // set the mute value and return the result.
            setMute (shouldMute.booleanValue());
            return shouldMute;
        }
        return Boolean.valueOf(getMute());
    }

    /**
    * Sets the audio mode per current phone state.
    */
    /* package */ void setAudioMode() {
        if (DBG) Log.d(LOG_TAG, "setAudioMode()..." + mCM.getState());

        AudioManager audioManager = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);
        int modeBefore = audioManager.getMode();
        mCM.setAudioMode();
        int modeAfter = audioManager.getMode();

        if (modeBefore != modeAfter) {
            // Enable stack dump only when actively debugging ("new Throwable()" is expensive!)
            if (DBG_SETAUDIOMODE_STACK) Log.d(LOG_TAG, "Stack:", new Throwable("stack dump"));
        } else {
            if (DBG) Log.d(LOG_TAG, "setAudioMode() no change: "+ audioModeToString(modeBefore));
        }
    }

    private static String audioModeToString(int mode) {
      switch (mode) {
          case AudioManager.MODE_INVALID: return "MODE_INVALID";
          case AudioManager.MODE_CURRENT: return "MODE_CURRENT";
          case AudioManager.MODE_NORMAL: return "MODE_NORMAL";
          case AudioManager.MODE_RINGTONE: return "MODE_RINGTONE";
          case AudioManager.MODE_IN_CALL: return "MODE_IN_CALL";
          default: return String.valueOf(mode);
      }
    }

    private void log(String msg) {
        Log.i(LOG_TAG, msg);
    }
}
