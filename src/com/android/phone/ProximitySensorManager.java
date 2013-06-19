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

import android.content.Context;
import android.content.res.Configuration;
import android.os.IPowerManager;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

/**
 * Updates the wake lock used to control proximity sensor behavior,
 *
 * On devices that have a proximity sensor, to avoid false touches
 * during a call, we hold a PROXIMITY_SCREEN_OFF_WAKE_LOCK wake lock
 * whenever the phone is off hook. (When held, that wake lock causes
 * the screen to turn off automatically when the sensor detects an
 * object close to the screen.)
 *
 * This class is a no-op for devices that don't have a proximity
 * sensor.
 *
 * Note this class doesn't care if any UI is the foreground
 * activity or not.  That's because we want the proximity sensor to be
 * enabled any time the phone is in use, to avoid false cheek events
 * for whatever app you happen to be running.
 *
 * Proximity wake lock will *not* be held if any one of the
 * conditions is true while on a call:
 * 1) If the audio is routed via Bluetooth
 * 2) If a wired headset is connected
 * 3) if the speaker is ON
 * 4) If the slider is open(i.e. the hardkeyboard is *not* hidden)
 *
 * TODO: There are still some references to PhoneGlobals that should be removed.
 */
public class ProximitySensorManager {
    private static final String TAG = "ProximitySensorManager";

    private static final boolean DBG =
            (PhoneGlobals.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);
    private static final boolean VDBG = (PhoneGlobals.DBG_LEVEL >= 2);

    private Context mContext;
    private PowerManager mPowerManager;
    private PowerManager.WakeLock mProximityWakeLock;
    private AccelerometerListener mAccelerometerListener;

    private int mOrientation = AccelerometerListener.ORIENTATION_UNKNOWN;
    // True if phone state is OFFHOOK or we are beginning a call,
    // but the phone state has not changed yet
    private boolean mIsInCall = false;
     // TODO: We need to find a better non UI realted name for mIsCallUIForegroundForProximity.
    private boolean mIsCallUIForegroundForProximity = false;
    private boolean mIsHardKeyboardOpen = false;

    public ProximitySensorManager(Context context) {
        mContext = context;
        mPowerManager = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);

        // Wake lock used to control proximity sensor behavior.
        if (mPowerManager.isWakeLockLevelSupported(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            mProximityWakeLock = mPowerManager.newWakeLock(
                    PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, TAG);

            mAccelerometerListener = new AccelerometerListener(mContext,
                    new AccelerometerListener.OrientationListener() {
                        public void orientationChanged(int orientation) {
                            log("orientationChanged()");
                            if (mOrientation != orientation) {
                                mOrientation = orientation;
                                updateProximitySensorMode();
                            }
                        }
                    });
        }

        log("ProximitySensorManager, mProximityWakeLock: " + mProximityWakeLock);
    }

    /* package */ void setIsInCall(boolean isInCall) {
        log("setInCallState(): isInCall = " + isInCall + ".");
        if (mIsInCall != isInCall) {
            mIsInCall = isInCall;
            updateProximitySensorMode();
        }
    }

    // TODO: We need a better non UI related name for this function.
    /* package */ void callUIActived(boolean active) {
        log("callUIActived() active = " + active + ".");
        if (active) {
            mIsCallUIForegroundForProximity = true;
        } else {
            mIsCallUIForegroundForProximity = !mPowerManager.isScreenOn();
        }

        updateProximitySensorMode();
    }

    /* package */ void setIsHardKeyboardOpen(boolean isHardKeyboardOpen) {
        if (mIsHardKeyboardOpen != isHardKeyboardOpen) {
            mIsHardKeyboardOpen = isHardKeyboardOpen;
            updateProximitySensorMode();
        }
    }

    /**
     * This function is called whenever any state related to proximity sensor is changed. Like if
     * setIsOffHook or setIsHardKeyboardOpen is called.
     */
    private void updateProximitySensorMode() {
        log("updateProximitySensorMode()");
        if (mProximityWakeLock != null) {
            synchronized (mProximityWakeLock) {
                // Turn proximity sensor off and turn screen on immediately if
                // we are using a headset, the keyboard is open, or the device
                // is being held in a horizontal position.
                // TODO: There will be some new method to implement isHeadsetPlugged,
                //       isBluetoothHeadsetAudioOn and mIsHardKeyboardOpen
                boolean screenOnImmediately = (PhoneGlobals.getInstance().isHeadsetPlugged()
                /* || PhoneGlobals.getInstance().isSpeakerOn() */
                || PhoneGlobals.getInstance().isBluetoothHeadsetAudioOn() || mIsHardKeyboardOpen);

                // We do not keep the screen off when the user is outside in-call screen and we are
                // horizontal, but we do not force it on when we become horizontal until the
                // proximity sensor goes negative.
                boolean horizontal = mOrientation == AccelerometerListener.ORIENTATION_HORIZONTAL;
                screenOnImmediately |= !mIsCallUIForegroundForProximity && horizontal;

                if (DBG) {
                    log("Headset plugged: " + PhoneGlobals.getInstance().isHeadsetPlugged()
                    /*
                     * + ", speaker on: " +
                     * PhoneGlobals.getInstance().isSpeakerOn()
                     */
                    + ", bluetooth audio on: "
                            + PhoneGlobals.getInstance().isBluetoothHeadsetAudioOn()
                            + ", hard keyboard open: " + mIsHardKeyboardOpen
                            + ", isCallUIForegroundForProximity: "
                            + mIsCallUIForegroundForProximity + ", horizontal: " + horizontal);
                }

                if (mIsInCall && !screenOnImmediately) {
                    // Phone is in use! Arrange for the screen to turn off
                    // automatically when the sensor detects a close object.
                    if (!mProximityWakeLock.isHeld()) {
                        if (DBG) log("Acquiring.");
                        mProximityWakeLock.acquire();
                    } else {
                        if (VDBG) log("Lock already held.");
                    }
                } else {
                    // Phone is either idle, or ringing.  We don't want any
                    // special proximity sensor behavior in either case.
                    if (mProximityWakeLock.isHeld()) {
                        if (DBG) log("Releasing.");
                        // Wait until user has moved the phone away from his head if we are
                        // releasing due to the phone call ending.
                        // Qtherwise, turn screen on immediately
                        int flags =
                            (screenOnImmediately ? 0 : PowerManager.WAIT_FOR_PROXIMITY_NEGATIVE);
                        mProximityWakeLock.release(flags);
                    } else {
                        if (VDBG) {
                            log("Lock already released.");
                        }
                    }

                    if (mAccelerometerListener != null) {
                        // use accelerometer to augment proximity sensor when in call.
                        mAccelerometerListener.enable(mIsInCall);
                    }
                }
            }
        }
    }

    private void log(String msg) {
        Log.d(TAG, msg);
    }
}
