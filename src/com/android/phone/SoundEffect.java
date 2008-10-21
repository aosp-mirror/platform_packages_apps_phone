/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.media.MediaPlayer;
import android.os.Environment;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

/**
 * Helper class used to play DTMF tones in the Phone app.
 */
public class SoundEffect {
    private static final String LOG_TAG = "phone";
    private static final boolean DBG = false;

    private static MediaPlayer sDtmfTone = null;
    private static String[] sDtmfToneFilePath;
    private static char sLastDtmfPlayed;

    /** This class is never instantiated. */
    private SoundEffect() {
    }

    static void init() {
        if (DBG) {
            Log.d(LOG_TAG, "[SoundEffect] init");
        }

        String extension = ".mp3";
        String root = Environment.getRootDirectory().toString();
        String prefix = root + "/sounds/dtmf";

        sDtmfToneFilePath = new String[12];

        int length = sDtmfToneFilePath.length;

        for (int i = 0; i < length; i++) {
            String fileName = getDtmfFileNameForIndex(i);
            StringBuilder buf = new StringBuilder();
            buf.append(prefix);
            buf.append(fileName);
            buf.append(extension);

            sDtmfToneFilePath[i] = buf.toString();
        }
    }

    private static String getDtmfFileNameForIndex(int index) {
        String retVal;

        if (index < 10) {
            retVal = String.valueOf(index);
        } else if (index == 10) {
            retVal = "_star";
        } else {
            retVal = "_pound";
        }

        return retVal;
    }

    static void playDtmfTone(char c) {
        if (!PhoneNumberUtils.is12Key(c)) {
            Log.e(LOG_TAG,
                    "playDtmfTone called with invalid character '" + c + "'");
        } else {
            // Play the tone for the far side
            PhoneApp.getInstance().phone.sendDtmf(c);
            
            // Don't play these locally for now.
            if (false) {
                // Play the tone locally
                int index = c - '0';
    
                if (c >= '0' && c <= '9') {
                    index = c - '0';
                } else if (c == '*') {
                    index = 10;
                } else if (c == '#') {
                    index = 11;
                }
    
                try {
                    if (DBG) {
                        Log.d(LOG_TAG, "[SoundEffect] playDtmfTone for " + c + ", tone index=" + index);
                    }
    
                    if (sDtmfTone == null) {
                        sDtmfTone = new MediaPlayer();
                    }
                    sDtmfTone.reset();
                    sDtmfTone.setDataSource(sDtmfToneFilePath[index]);
                    sDtmfTone.setAudioStreamType(
                            android.media.AudioManager.STREAM_RING);
                    sDtmfTone.prepare();
                    sDtmfTone.seekTo(0);
                    sDtmfTone.start();
                } catch (Exception ex) {
                    if (DBG) Log.e(LOG_TAG,
                            "[SoundEffect] playDtmfTone caught " + ex);
                }
            }
        }
    }
}
