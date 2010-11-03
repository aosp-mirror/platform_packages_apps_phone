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

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;

import java.util.HashMap;
import java.util.Map;
import android.app.IntentService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * DTMFSenderService receives android.intent.action.SEND_DTMF intents with an extra
 * String parameter android.intent.extra.DTMF_STRING and sends the string as a burst DTMF
 * if there is an active foreground call.
 *
 * Optional parameters:
 * EXTRA_DTMF_ON: Integer parameter, specifies the length of each DTMF tone.
 * EXTRA_DTMF_OFF: Integer parameter, specifies the length of the pause between the DTMF tones.
 * EXTRA_DTMF_SOUND: Boolean parameter, controls whether the DTMF tones should be played
 * locally as well.
 */
public class DTMFSenderService extends IntentService {

    private static final String TAG = "DTMFSenderService";

    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 80;

    /**
     * Stream type used to play the DTMF tones off call, and mapped to the
     * volume control keys
     */
    private static final int DIAL_TONE_STREAM_TYPE = AudioManager.STREAM_MUSIC;

    private static final int DEFAULT_DTMF_ON = 150;

    private static final int DEFAULT_DTMF_OFF = 100;

    private ToneGenerator mToneGenerator;

    private Object mToneGeneratorLock = new Object();

    private static Map<Character, Integer> tones = new HashMap<Character, Integer>();

    static {
        tones.put('0', ToneGenerator.TONE_DTMF_0);
        tones.put('1', ToneGenerator.TONE_DTMF_1);
        tones.put('2', ToneGenerator.TONE_DTMF_2);
        tones.put('3', ToneGenerator.TONE_DTMF_3);
        tones.put('4', ToneGenerator.TONE_DTMF_4);
        tones.put('5', ToneGenerator.TONE_DTMF_5);
        tones.put('6', ToneGenerator.TONE_DTMF_6);
        tones.put('7', ToneGenerator.TONE_DTMF_7);
        tones.put('8', ToneGenerator.TONE_DTMF_8);
        tones.put('9', ToneGenerator.TONE_DTMF_9);
        tones.put('*', ToneGenerator.TONE_DTMF_S);
        tones.put('#', ToneGenerator.TONE_DTMF_P);
        tones.put('A', ToneGenerator.TONE_DTMF_A);
        tones.put('B', ToneGenerator.TONE_DTMF_B);
        tones.put('C', ToneGenerator.TONE_DTMF_C);
        tones.put('D', ToneGenerator.TONE_DTMF_D);
    }

    public DTMFSenderService() {
        super("DTMFSenderService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // if the mToneGenerator creation fails, just continue without it. It is
        // a local audio signal, and is not as important as the dtmf tone
        // itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    // we want the user to be able to control the volume of the
                    // dial tones
                    // outside of a call, so we use the stream type that is also
                    // mapped to the
                    // volume control keys for this activity
                    mToneGenerator = new ToneGenerator(DIAL_TONE_STREAM_TYPE, TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        Log.d(TAG, "Received Intent in DTMFSenderService");
        Phone phone = PhoneApp.getInstance().phone;
        Call foregroundCall = phone.getForegroundCall();

        if (foregroundCall.getState() != Call.State.ACTIVE) {
            Log.w(TAG, "Unable to send DTMF: No active call");
            return;
        }

        if (intent.getAction().equals(TelephonyManager.ACTION_SEND_DTMF)) {
            String dtmfString = intent.getStringExtra(TelephonyManager.EXTRA_DTMF_STRING);

            if (dtmfString == null) {
                Log.w(TAG, "No DTMF string to send was specified");
                return;
            }

            int dtmfOn = intent.getIntExtra(TelephonyManager.EXTRA_DTMF_ON, DEFAULT_DTMF_ON);
            if (dtmfOn <= 0) {
                dtmfOn = DEFAULT_DTMF_ON;
            }
            int dtmfOff = intent.getIntExtra(TelephonyManager.EXTRA_DTMF_OFF, DEFAULT_DTMF_OFF);
            if (dtmfOff <= 0) {
                dtmfOff = DEFAULT_DTMF_OFF;
            }

            boolean dtmfSound = intent.getBooleanExtra(TelephonyManager.EXTRA_DTMF_SOUND, false);

            Log.i(TAG, "Sending DTMF: " + dtmfString);
            for (char c : dtmfString.toCharArray()) {
                if (!PhoneNumberUtils.is12Key(c)) {
                    Log.d(TAG, "Ignoring invalid DTMF character: " + c);
                    continue;
                }
                if (foregroundCall.getState() != Call.State.ACTIVE) {
                    Log.i(TAG, "Call no longer active, returning");
                    return;
                }
                Log.d(TAG, "Start: " + c);
                phone.startDtmf(c);
                if (dtmfSound) {
                    Integer tone = tones.get(c);
                    if (tone != null) {
                        playTone(tone, dtmfOn);
                    }
                }
                Log.d(TAG, "Waiting DTMF for: " + dtmfOn);
                try {
                    Thread.sleep(dtmfOn);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
                Log.d(TAG, "Stop");
                phone.stopDtmf();
                Log.d(TAG, "Waiting between DTMF for: " + dtmfOff);
                try {
                    Thread.sleep(dtmfOff);
                } catch (InterruptedException e) {
                    Log.e(TAG, "Sleep interrupted", e);
                }
            }
        }
    }

    /**
     * Plays the specified tone for specified milliseconds. The tone is played
     * locally, using the audio stream for phone calls. Tones are NOT played if
     * the device is in silent mode.
     *
     * @param tone a tone code from {@link ToneGenerator}
     * @param len the length of the tone
     */
    void playTone(int tone, int len) {
        // Also do nothing if the phone is in silent mode.
        // We need to re-check the ringer mode for *every* playTone()
        // call, rather than keeping a local flag that's updated in
        // onResume(), since it's possible to toggle silent mode without
        // leaving the current activity (via the ENDCALL-longpress menu.)
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int ringerMode = audioManager.getRingerMode();
        if ((ringerMode == AudioManager.RINGER_MODE_SILENT)
                || (ringerMode == AudioManager.RINGER_MODE_VIBRATE)) {
            return;
        }
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(TAG, "playTone: mToneGenerator == null, tone: " + tone);
                return;
            }
            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone, len);
        }
    }
}
