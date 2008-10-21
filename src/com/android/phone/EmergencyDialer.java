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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;

/**
 * EmergencyDialer is a special dailer that is used ONLY for dialing emergency calls.
 * It is a special case of the TwelveKeyDialer that:
 *   1. allows ONLY emergency calls to be dialed
 *   2. disallows voicemail functionality
 *   3. handles keyguard access correctly
 *
 * NOTE: TwelveKeyDialer has been moved into the Contacts App, so the "useful"
 * portions of the TwelveKeyDialer have been moved into this class, as part of some
 * code cleanup.
 */
public class EmergencyDialer extends Activity implements View.OnClickListener,
View.OnLongClickListener, View.OnKeyListener, TextWatcher {

    // intent action for this activity.
    public static final String ACTION_DIAL = "com.android.phone.EmergencyDialer.DIAL";

    // debug constants
    private static final boolean DBG = false;
    private static final String LOG_TAG = "emergency_dialer";

    private static final int STOP_TONE = 1;

    /** The length of DTMF tones in milliseconds */
    private static final int TONE_LENGTH_MS = 150;

    /** The DTMF tone volume relative to other sounds in the stream */
    private static final int TONE_RELATIVE_VOLUME = 50;

    EditText mDigits;
    private View mDelete;
    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();

    // new UI background assets
    private Drawable mDigitsBackground;
    private Drawable mDigitsEmptyBackground;
    private Drawable mDeleteBackground;
    private Drawable mDeleteEmptyBackground;

    // determines if we want to playback local DTMF tones.
    private boolean mDTMFToneEnabled;

    // close activity when screen turns off
    private BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                finish();
            }
        }
    };

    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        // Do nothing
    }

    public void onTextChanged(CharSequence input, int start, int before, int changeCount) {
        // Do nothing
    }


    public void afterTextChanged(Editable input) {
        if (SpecialCharSequenceMgr.handleChars(this, input.toString(), this)) {
            // A special sequence was entered, clear the digits
            mDigits.getText().delete(0, mDigits.getText().length());
        }

        // Set the proper background for the dial input area
        if (mDigits.length() != 0) {
            mDelete.setBackgroundDrawable(mDeleteBackground);
            mDigits.setBackgroundDrawable(mDigitsBackground);
            mDigits.setCompoundDrawablesWithIntrinsicBounds(
                    getResources().getDrawable(R.drawable.ic_dial_number), null, null, null);
        } else {
            mDelete.setBackgroundDrawable(mDeleteEmptyBackground);
            mDigits.setBackgroundDrawable(mDigitsEmptyBackground);
            mDigits.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
    }

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        // Set the content view
        setContentView(R.layout.emergency_dialer);

        // Load up the resources for the text field and delete button
        Resources r = getResources();
        mDigitsBackground = r.getDrawable(R.drawable.btn_dial_textfield_active);
        mDigitsEmptyBackground = r.getDrawable(R.drawable.btn_dial_textfield);
        mDeleteBackground = r.getDrawable(R.drawable.btn_dial_delete_active);
        mDeleteEmptyBackground = r.getDrawable(R.drawable.btn_dial_delete);

        mDigits = (EditText) findViewById(R.id.digits);
        mDigits.setKeyListener(DialerKeyListener.getInstance());
        mDigits.setOnClickListener(this);
        mDigits.setOnKeyListener(this);
        mDigits.setLongClickable(false);
        maybeAddNumberFormatting();

        // Check for the presence of the keypad
        View view = findViewById(R.id.one);
        if (view != null) {
            setupKeypad();
        }

        mDelete = findViewById(R.id.backspace);
        mDelete.setOnClickListener(this);
        mDelete.setOnLongClickListener(this);

        if (icicle != null) {
            super.onRestoreInstanceState(icicle);
        }

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mBroadcastReceiver, intentFilter);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneStopper.removeMessages(STOP_TONE);
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
        unregisterReceiver(mBroadcastReceiver);
    }

    @Override
    protected void onRestoreInstanceState(Bundle icicle) {
        // Do nothing, state is restored in onCreate() if needed
    }

    /**
     * Explicitly turn off number formatting, since it gets in the way of the emergency
     * number detector
     */
    protected void maybeAddNumberFormatting() {
        // Do nothing.
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        // This can't be done in onCreate(), since the auto-restoring of the digits
        // will play DTMF tones for all the old digits if it is when onRestoreSavedInstanceState()
        // is called. This method will be called every time the activity is created, and
        // will always happen after onRestoreSavedInstanceState().
        mDigits.addTextChangedListener(this);
    }

    private void setupKeypad() {
        // Setup the listeners for the buttons
        findViewById(R.id.one).setOnClickListener(this);
        findViewById(R.id.two).setOnClickListener(this);
        findViewById(R.id.three).setOnClickListener(this);
        findViewById(R.id.four).setOnClickListener(this);
        findViewById(R.id.five).setOnClickListener(this);
        findViewById(R.id.six).setOnClickListener(this);
        findViewById(R.id.seven).setOnClickListener(this);
        findViewById(R.id.eight).setOnClickListener(this);
        findViewById(R.id.nine).setOnClickListener(this);
        findViewById(R.id.star).setOnClickListener(this);

        View view = findViewById(R.id.zero);
        view.setOnClickListener(this);
        view.setOnLongClickListener(this);

        findViewById(R.id.pound).setOnClickListener(this);
    }

    /**
     * handle key events
     */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_CALL: {
                if (TextUtils.isEmpty(mDigits.getText().toString())) {
                    // if we are adding a call from the InCallScreen and the phone
                    // number entered is empty, we just close the dialer to expose
                    // the InCallScreen under it.
                    finish();
                } else {
                    // otherwise, we place the call.
                    placeCall();
                }
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void keyPressed(int keyCode) {
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_DOWN, keyCode);
        mDigits.onKeyDown(keyCode, event);
    }

    public boolean onKey(View view, int keyCode, KeyEvent event) {
        switch (view.getId()) {
            case R.id.digits:
                if (keyCode == KeyEvent.KEYCODE_ENTER) {
                    placeCall();
                    return true;
                }
                break;
        }
        return false;
    }

    public void onClick(View view) {
        final Editable digits = mDigits.getText();

        switch (view.getId()) {
            case R.id.one: {
                playTone(ToneGenerator.TONE_DTMF_1);
                keyPressed(KeyEvent.KEYCODE_1);
                return;
            }
            case R.id.two: {
                playTone(ToneGenerator.TONE_DTMF_2);
                keyPressed(KeyEvent.KEYCODE_2);
                return;
            }
            case R.id.three: {
                playTone(ToneGenerator.TONE_DTMF_3);
                keyPressed(KeyEvent.KEYCODE_3);
                return;
            }
            case R.id.four: {
                playTone(ToneGenerator.TONE_DTMF_4);
                keyPressed(KeyEvent.KEYCODE_4);
                return;
            }
            case R.id.five: {
                playTone(ToneGenerator.TONE_DTMF_5);
                keyPressed(KeyEvent.KEYCODE_5);
                return;
            }
            case R.id.six: {
                playTone(ToneGenerator.TONE_DTMF_6);
                keyPressed(KeyEvent.KEYCODE_6);
                return;
            }
            case R.id.seven: {
                playTone(ToneGenerator.TONE_DTMF_7);
                keyPressed(KeyEvent.KEYCODE_7);
                return;
            }
            case R.id.eight: {
                playTone(ToneGenerator.TONE_DTMF_8);
                keyPressed(KeyEvent.KEYCODE_8);
                return;
            }
            case R.id.nine: {
                playTone(ToneGenerator.TONE_DTMF_9);
                keyPressed(KeyEvent.KEYCODE_9);
                return;
            }
            case R.id.zero: {
                playTone(ToneGenerator.TONE_DTMF_0);
                keyPressed(KeyEvent.KEYCODE_0);
                return;
            }
            case R.id.pound: {
                playTone(ToneGenerator.TONE_DTMF_P);
                keyPressed(KeyEvent.KEYCODE_POUND);
                return;
            }
            case R.id.star: {
                playTone(ToneGenerator.TONE_DTMF_S);
                keyPressed(KeyEvent.KEYCODE_STAR);
                return;
            }
            case R.id.digits: {
                placeCall();
                return;
            }
            case R.id.backspace: {
                keyPressed(KeyEvent.KEYCODE_DEL);
                return;
            }
        }
    }

    /**
     * called for long touch events
     */
    public boolean onLongClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.backspace: {
                mDigits.getText().delete(0, mDigits.getText().length());
                return true;
            }
            case R.id.zero: {
                keyPressed(KeyEvent.KEYCODE_PLUS);
                return true;
            }
        }
        return false;
    }

    /**
     * display the alert dialog
     */
    private void displayErrorBadNumber (String number) {
        // construct error string
        CharSequence errMsg;
        if (!TextUtils.isEmpty(number)) {
            errMsg = getString(R.string.dial_emergency_error, number);
        } else {
            errMsg = getText (R.string.dial_emergency_empty_error);
        }

        // construct dialog
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        b.setTitle(getText(R.string.emergency_enable_radio_dialog_title));
        b.setMessage(errMsg);
        b.setPositiveButton(R.string.ok, null);
        b.setCancelable(true);

        // show the dialog
        AlertDialog dialog = b.create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        dialog.show();
    }

    /**
     * turn off keyguard on start.
     */
    @Override
    protected void onResume() {
        super.onResume();

        // retrieve the DTMF tone play back setting.
        mDTMFToneEnabled = Settings.System.getInt(getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                try {
                    mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING,
                            TONE_RELATIVE_VOLUME);
                } catch (RuntimeException e) {
                    Log.w(LOG_TAG, "Exception caught while creating local tone generator: " + e);
                    mToneGenerator = null;
                }
            }
        }

        // Turn keyguard off and set the poke lock timeout to medium.  There is
        // no need to do anything with the wake lock.
        if (DBG) Log.d(LOG_TAG, "turning keyguard off, set to long timeout");
        PhoneApp app = (PhoneApp) getApplication();
        app.disableKeyguard();
        app.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.MEDIUM);
    }

    /**
     * turn on keyguard on pause.
     */
    @Override
    public void onPause() {
        // Turn keyguard back on and set the poke lock timeout to default.  There
        // is no need to do anything with the wake lock.
        if (DBG) Log.d(LOG_TAG, "turning keyguard back on and closing the dailer");
        PhoneApp app = (PhoneApp) getApplication();
        app.reenableKeyguard();
        app.setScreenTimeout(PhoneApp.ScreenTimeoutDuration.DEFAULT);

        super.onPause();

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneStopper.removeMessages(STOP_TONE);
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }

    /**
     * place the call, but check to make sure it is a viable number.
     */
    void placeCall() {
        final String number = mDigits.getText().toString();
        if (PhoneNumberUtils.isEmergencyNumber(number)) {
            if (DBG) Log.d(LOG_TAG, "placing call to " + number);

            // place the call if it is a valid number
            if (number == null || !TextUtils.isGraphic(number)) {
                // There is no number entered.
                playTone(ToneGenerator.TONE_PROP_NACK);
                return;
            }
            Intent intent = new Intent(Intent.ACTION_CALL_EMERGENCY);
            intent.setData(Uri.fromParts("tel", number, null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        } else {
            if (DBG) Log.d(LOG_TAG, "rejecting bad requested number " + number);

            // erase the number and throw up an alert dialog.
            mDigits.getText().delete(0, mDigits.getText().length());
            displayErrorBadNumber(number);
        }
    }

    Handler mToneStopper = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case STOP_TONE:
                    synchronized (mToneGeneratorLock) {
                        if (mToneGenerator == null) {
                            Log.w(LOG_TAG, "mToneStopper: mToneGenerator == null");
                        } else {
                            mToneGenerator.stopTone();
                        }
                    }
                    break;
            }
        }
    };

    /**
     * Play a tone for TONE_LENGTH_MS milliseconds.
     *
     * @param tone a tone code from {@link ToneGenerator}
     */
    void playTone(int tone) {
        // if local tone playback is disabled, just return.
        if (!mDTMFToneEnabled) {
            return;
        }

        synchronized (mToneGeneratorLock) {
            if (mToneGenerator == null) {
                Log.w(LOG_TAG, "playTone: mToneGenerator == null, tone: " + tone);
                return;
            }

            // Remove pending STOP_TONE messages
            mToneStopper.removeMessages(STOP_TONE);

            // Start the new tone (will stop any playing tone)
            mToneGenerator.startTone(tone);
            mToneStopper.sendEmptyMessageDelayed(STOP_TONE, TONE_LENGTH_MS);
        }
    }

}
