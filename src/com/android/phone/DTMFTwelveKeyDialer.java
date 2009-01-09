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


import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Connection;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.SlidingDrawer;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.method.DialerKeyListener;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;

/**
 * Dialer class that encapsulates the DTMF twelve key behaviour.
 * This model backs up the UI behaviour in DTMFTwelveKeyDialerView.java.
 */
public class DTMFTwelveKeyDialer implements
        CallerInfoAsyncQuery.OnQueryCompleteListener,
        SlidingDrawer.OnDrawerOpenListener,
        SlidingDrawer.OnDrawerCloseListener,
        View.OnClickListener,
        View.OnTouchListener,
        View.OnKeyListener {

    // debug data
    private static final String LOG_TAG = "dtmf dialer";
    private static final boolean DBG = false;

    // events
    private static final int PHONE_DISCONNECT = 100;

    private static Phone mPhone;
    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();

    // indicate if we want to enable the DTMF tone playback.
    private boolean mDTMFToneEnabled;

    /** Hash Map to map a character to a tone*/
    private static final HashMap<Character, Integer> mToneMap =
        new HashMap<Character, Integer>();
    /** Hash Map to map a view id to a character*/
    private static final HashMap<Integer, Character> mDisplayMap =
        new HashMap<Integer, Character>();
    /** Set up the static maps*/
    static {
        // Map the key characters to tones
        mToneMap.put('1', ToneGenerator.TONE_DTMF_1);
        mToneMap.put('2', ToneGenerator.TONE_DTMF_2);
        mToneMap.put('3', ToneGenerator.TONE_DTMF_3);
        mToneMap.put('4', ToneGenerator.TONE_DTMF_4);
        mToneMap.put('5', ToneGenerator.TONE_DTMF_5);
        mToneMap.put('6', ToneGenerator.TONE_DTMF_6);
        mToneMap.put('7', ToneGenerator.TONE_DTMF_7);
        mToneMap.put('8', ToneGenerator.TONE_DTMF_8);
        mToneMap.put('9', ToneGenerator.TONE_DTMF_9);
        mToneMap.put('0', ToneGenerator.TONE_DTMF_0);
        mToneMap.put('#', ToneGenerator.TONE_DTMF_P);
        mToneMap.put('*', ToneGenerator.TONE_DTMF_S);

        // Map the buttons to the display characters
        mDisplayMap.put(R.id.one, '1');
        mDisplayMap.put(R.id.two, '2');
        mDisplayMap.put(R.id.three, '3');
        mDisplayMap.put(R.id.four, '4');
        mDisplayMap.put(R.id.five, '5');
        mDisplayMap.put(R.id.six, '6');
        mDisplayMap.put(R.id.seven, '7');
        mDisplayMap.put(R.id.eight, '8');
        mDisplayMap.put(R.id.nine, '9');
        mDisplayMap.put(R.id.zero, '0');
        mDisplayMap.put(R.id.pound, '#');
        mDisplayMap.put(R.id.star, '*');
    }

    // UI elements
    // Including elements used in the call status, now split
    // between call state and call timer.
    private EditText mDigits;
    private LinearLayout mStatusView;
    private ImageView mStatusCallIcon;
    private Chronometer mStatusCallTime;
    private TextView mStatusCallState;
    private TextView mStatusCallerName;

    // InCallScreen reference.
    private InCallScreen mInCallScreen;

    // SlidingDrawer reference.
    private SlidingDrawer mDialerContainer;

    // view reference
    private DTMFTwelveKeyDialerView mDialerView;

    // key listner reference, may or may not be attached to a view.
    private DTMFKeyListener mDialerKeyListener;

    /**
     * Our own key listener, specialized for dealing with DTMF codes.
     *   1. Ignore the backspace since it is irrelevant.
     *   2. Allow ONLY valid DTMF characters to generate a tone and be
     *      sent as a DTMF code.
     *   3. All other remaining characters are handled by the superclass.
     */
    private class DTMFKeyListener extends DialerKeyListener {

        /**
         * Overriden to return correct DTMF-dialable characters.
         */
        @Override
        protected char[] getAcceptedChars(){
            return DTMF_CHARACTERS;
        }

        /** special key listener ignores backspace. */
        @Override
        public boolean backspace(View view, Editable content, int keyCode,
                KeyEvent event) {
            return false;
        }

        /**
         * Overriden so that with each valid button press, we start sending
         * a dtmf code and play a local dtmf tone.
         */
        @Override
        public boolean onKeyDown(View view, Editable content,
                                 int keyCode, KeyEvent event) {
            // find the character
            char c = (char) lookup(event, content);

            // if not a long press, and parent onKeyDown accepts the input
            if (event.getRepeatCount() == 0 && super.onKeyDown(view, content, keyCode, event)) {

                // if the character is a valid dtmf code, start playing the tone and send the
                // code.
                if (ok(getAcceptedChars(), c)) {
                    if (DBG) log("DTMFKeyListener reading '" + c + "' from input.");
                    playTone(c);
                } else if (DBG) {
                    log("DTMFKeyListener rejecting '" + c + "' from input.");
                }
                return true;
            }
            return false;
        }

        /**
         * Overriden so that with each valid button up, we stop sending
         * a dtmf code and the dtmf tone.
         */
        @Override
        public boolean onKeyUp(View view, Editable content,
                                 int keyCode, KeyEvent event) {

            super.onKeyUp(view, content, keyCode, event);

            // find the character
            char c = (char) lookup(event, content);

            if (ok(getAcceptedChars(), c)) {
                if (DBG) log("Stopping the tone for '" + c + "'");
                stopTone();
                return true;
            }

            return false;
        }

        /**
         * Handle individual keydown events when we DO NOT have an Editable handy.
         */
        public boolean onKeyDown(KeyEvent event) {
            char c = lookup (event);
            if (DBG) log("recieved keydown for '" + c + "'");

            // if not a long press, and parent onKeyDown accepts the input
            if (event.getRepeatCount() == 0 && c != 0) {
                // if the character is a valid dtmf code, start playing the tone and send the
                // code.
                if (ok(getAcceptedChars(), c)) {
                    if (DBG) log("DTMFKeyListener reading '" + c + "' from input.");
                    playTone(c);
                    return true;
                } else if (DBG) {
                    log("DTMFKeyListener rejecting '" + c + "' from input.");
                }
            }
            return false;
        }

        /**
         * Handle individual keyup events.
         *
         * @param event is the event we are trying to stop.  If this is null,
         * then we just force-stop the last tone without checking if the event
         * is an acceptable dialer event.
         */
        public boolean onKeyUp(KeyEvent event) {
            if (event == null) {
                if (DBG) log("Stopping the last played tone.");
                stopTone();
                return true;
            }

            char c = lookup (event);
            if (DBG) log("recieved keyup for '" + c + "'");

            // TODO: stopTone does not take in character input, we may want to
            // consider checking for this ourselves.
            if (ok(getAcceptedChars(), c)) {
                if (DBG) log("Stopping the tone for '" + c + "'");
                stopTone();
                return true;
            }

            return false;
        }

        /**
         * Find the Dialer Key mapped to this event.
         *
         * @return The char value of the input event, otherwise
         * 0 if no matching character was found.
         */
        private char lookup (KeyEvent event) {
            // This code is similar to {@link DialerKeyListener#lookup(KeyEvent, Spannable) lookup}
            int meta = event.getMetaState();
            int number = event.getNumber();

            if (!((meta & (KeyEvent.META_ALT_ON | KeyEvent.META_SHIFT_ON)) == 0) || (number == 0)) {
                int match = event.getMatch(getAcceptedChars(), meta);
                number = (match != 0) ? match : number;
            }

            return (char) number;
        }

        /**
         * Check to see if the keyEvent is dialable.
         */
        boolean isKeyEventAcceptable (KeyEvent event) {
            return (ok(getAcceptedChars(), lookup(event)));
        }

        /**
         * Overrides the characters used in {@link DialerKeyListener#CHARACTERS}
         * These are the valid dtmf characters.
         */
        public final char[] DTMF_CHARACTERS = new char[] {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '#', '*'
        };
    }

    /**
     * Our own handler to take care of the messages from the phone state changes
     */
    private Handler mHandler = new Handler () {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                // disconnect action
                // make sure to close the dialer on ALL disconnect actions.
                case PHONE_DISCONNECT:
                    if (DBG) log("disconnect message recieved, shutting down.");
                    // unregister since we are closing.
                    mPhone.unregisterForDisconnect(this);
                    closeDialer(false);
                    break;
            }
        }
    };


    DTMFTwelveKeyDialer (InCallScreen parent) {
        mInCallScreen = parent;
        mPhone = ((PhoneApp) mInCallScreen.getApplication()).phone;
        mDialerContainer = (SlidingDrawer) mInCallScreen.findViewById(R.id.dialer_container);
        mDialerContainer.setOnDrawerOpenListener(this);
        mDialerContainer.setOnDrawerCloseListener(this);
        mDialerKeyListener = new DTMFKeyListener();
    }

    /**
     * Null out our reference to the InCallScreen activity.
     * This indicates that the InCallScreen activity has been destroyed.
     * At the same time, get rid of listeners since we're not going to
     * be valid anymore.
     */
    void clearInCallScreenReference() {
        mInCallScreen = null;
        mDialerKeyListener = null;
        mDialerContainer.setOnDrawerOpenListener(null);
        mDialerContainer.setOnDrawerCloseListener(null);
        closeDialer(false);
    }

    LinearLayout getView() {
        return mDialerView;
    }

    /**
     * Dialer code that runs when the dialer is brought up.
     * This includes layout changes, etc, and just prepares the dialer model for use.
     */
    void onDialerOpen() {
        if (DBG) log("initMenu()...");

        // inflate the view.
        mDialerView = (DTMFTwelveKeyDialerView) mInCallScreen.findViewById(R.id.dtmf_dialer);
        mDialerView.setDialer(this);

        // Have the WindowManager filter out cheek touch events
        mInCallScreen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        // TODO: Need new assets, Hide the voicemail icon.
        /*
        View v = mDialerView.findViewById(R.id.oneVoicemailIcon);
        if (v != null) {
            v.setVisibility(View.GONE);
        }
        */

        mPhone.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);

        // set to a longer delay while the dialer is up.
        mInCallScreen.updateWakeState();

        // setup the digit display
        mDigits = (EditText) mDialerView.findViewById(R.id.digits);
        mDigits.setKeyListener(new DTMFKeyListener());
        mDigits.requestFocus();

        // remove the long-press context menus that support
        // the edit (copy / paste / select) functions.
        mDigits.setLongClickable(false);

        // setup the status view
        mStatusView = (LinearLayout) mDialerView.findViewById(R.id.status_view);
        mStatusView.setOnClickListener(this);

        // get a handle to the relevant UI widgets
        mStatusCallIcon = (ImageView) mDialerView.findViewById(R.id.status_call_icon);
        mStatusCallTime = (Chronometer) mDialerView.findViewById(R.id.status_call_time);
        mStatusCallState = (TextView) mDialerView.findViewById(R.id.status_call_state);
        mStatusCallerName = (TextView) mDialerView.findViewById(R.id.status_caller_name);

        // Check for the presence of the keypad (portrait mode)
        View view = mDialerView.findViewById(R.id.one);
        if (view != null) {
            if (DBG) log("portrait mode setup");
            setupKeypad();
        } else {
            if (DBG) log("landscape mode setup");
            // Adding hint text to the field to indicate that keyboard
            // is needed while in landscape mode.
            mDigits.setHint(R.string.dialerKeyboardHintText);
        }

        // update the status screen
        updateStatus();

        // setup the local tone generator.
        startDialerSession();
    }

    /**
     * Setup the local tone generator.  Should have corresponding calls to
     * {@link onDialerPause}.
     */
    public void startDialerSession() {
        // see if we need to play local tones.
        mDTMFToneEnabled = Settings.System.getInt(mInCallScreen.getContentResolver(),
                Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 1;

        // create the tone generator
        // if the mToneGenerator creation fails, just continue without it.  It is
        // a local audio signal, and is not as important as the dtmf tone itself.
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    try {
                        mToneGenerator = new ToneGenerator(AudioManager.STREAM_RING, 80);
                    } catch (RuntimeException e) {
                        if (DBG) log("Exception caught while creating local tone generator: " + e);
                        mToneGenerator = null;
                    }
                }
            }
        }
    }

    /**
     * Dialer code that runs when the dialer is closed.
     * This releases resources acquired when we start the dialer.
     */
    public void onDialerClose() {
        // reset back to a short delay for the poke lock.
        mInCallScreen.updateWakeState();

        mPhone.unregisterForDisconnect(mHandler);

        if (mStatusCallTime != null) {
            mStatusCallTime.stop();
        }

        stopDialerSession();
    }

    /**
     * Tear down the local tone generator, corresponds to calls to
     * {@link onDialerResume}
     */
    public void stopDialerSession() {
        // release the tone generator.
        synchronized (mToneGeneratorLock) {
            if (mToneGenerator != null) {
                mToneGenerator.release();
                mToneGenerator = null;
            }
        }
    }

    /**
     * update the status display, code taken mostly from
     * NotificationMgr.updateInCallNotification
     */
    private void updateStatus () {
        if (DBG) log("updating status display");

        // get statuses
        final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();

        // figure out which icon to display
        int resId = (!hasActiveCall && hasHoldingCall) ?
                android.R.drawable.stat_sys_phone_call_on_hold :
                android.R.drawable.stat_sys_phone_call;

        // get the current connected call.
        Call currentCall = hasActiveCall ? mPhone.getForegroundCall()
                : mPhone.getBackgroundCall();
        Connection currentConn = currentCall.getEarliestConnection();

        // update the information about the current connection (chronometer)
        // only if the current connection exists.
        if (currentConn != null) {
            // figure out the elapsed time
            long callDurationMsec = currentConn.getDurationMillis();
            long chronometerBaseTime = SystemClock.elapsedRealtime() - callDurationMsec;

            // figure out the call status to display.
            String statusInfo;
            if (hasHoldingCall && !hasActiveCall) {
                statusInfo = mInCallScreen.getString(R.string.onHold);

                // hide the timer while on hold.
                mStatusCallTime.setVisibility(View.INVISIBLE);
            } else {
                statusInfo = mInCallScreen.getString(R.string.ongoing);

                // setup and start the status timer.
                mStatusCallTime.setVisibility(View.VISIBLE);
                mStatusCallTime.setBase(chronometerBaseTime);
                mStatusCallTime.start();
            }

            // display the call state
            mStatusCallState.setText(statusInfo);

        } else if (DBG) {
            log("updateStatus: connection is null, call display not updated.");
        }

        // this code is independent of the chronometer code; used to
        // display the caller name.
        // TODO: it may not make sense for every point to make separate
        // checks for isConferenceCall, so we need to think about
        // possibly including this in startGetCallerInfo or some other
        // common point.
        if (PhoneUtils.isConferenceCall(currentCall)) {
            // if this is a conference call, just use that as the caller name.
            mStatusCallerName.setText(R.string.card_title_conf_call);
        } else {
            // get the caller name.
            PhoneUtils.CallerInfoToken cit =
                    PhoneUtils.startGetCallerInfo(mInCallScreen, currentCall, this,
                            mStatusCallerName);
            mStatusCallerName.setText(
                    PhoneUtils.getCompactNameFromCallerInfo(cit.currentInfo, mInCallScreen));
        }

        // set the icon
        mStatusCallIcon.setImageResource(resId);
    }

    /**
     * upon completion of the query, update the name field in the status.
     */
    public void onQueryComplete(int token, Object cookie, CallerInfo ci){
        if (DBG) log("callerinfo query complete, updating ui.");

        ((TextView) cookie).setText(PhoneUtils.getCompactNameFromCallerInfo(ci, mInCallScreen));
    }

    /**
     * Called externally (from InCallScreen) to play a DTMF Tone.
     */
    public boolean onDialerKeyDown(KeyEvent event) {
        if (DBG) log("Notifying dtmf key down.");
        return mDialerKeyListener.onKeyDown(event);
    }

    /**
     * Called externally (from InCallScreen) to cancel the last DTMF Tone played.
     */
    public boolean onDialerKeyUp(KeyEvent event) {
        if (DBG) log("Notifying dtmf key up.");
        return mDialerKeyListener.onKeyUp(event);
    }

    /**
     * setup the keys on the dialer activity, using the keymaps.
     */
    private void setupKeypad() {
        // for each view id listed in the displaymap
        View button;
        for (int viewId : mDisplayMap.keySet()) {
            // locate the view
            button = mDialerView.findViewById(viewId);
            // Setup the listeners for the buttons
            button.setOnTouchListener(this);
            button.setClickable(true);
            button.setOnKeyListener(this);
        }
    }

    /**
     * catch the back and call buttons to return to the in call activity.
     */
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            // finish for these events
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_CALL:
                if (DBG) log("exit requested");
                closeDialer(true);  // do the "closing" animation
                return true;
        }
        return mInCallScreen.onKeyDown(keyCode, event);
    }

    /**
     * catch the back and call buttons to return to the in call activity.
     */
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        return mInCallScreen.onKeyUp(keyCode, event);
    }

    /**
     * for clicklistener, process the incoming button presses.
     */
    public void onClick(View view) {
        if (view == mStatusView) {
            if (DBG) log("exit requested from status view");
            closeDialer(true);  // do the "closing" animation
        }
    }

    /**
     * Implemented for the TouchListener, process the touch events.
     */
    public boolean onTouch(View v, MotionEvent event) {
        int viewId = v.getId();

        // if the button is recognized
        if (mDisplayMap.containsKey(viewId)) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    // Append the character mapped to this button, to the display.
                    // start the tone
                    appendDigit(mDisplayMap.get(viewId));
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // stop the tone on ANY other event, except for MOVE.
                    stopTone();
                    break;
            }
            // do not return true [handled] here, since we want the
            // press / click animation to be handled by the framework.
        }
        return false;
    }

    /**
     * Implements View.OnKeyListener for the DTMF buttons.  Enables dialing with trackball/dpad.
     */
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            int viewId = v.getId();
            if (mDisplayMap.containsKey(viewId)) {
                switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (event.getRepeatCount() == 0) {
                        appendDigit(mDisplayMap.get(viewId));
                    }
                    break;
                case KeyEvent.ACTION_UP:
                    stopTone();
                    break;
                }
                // do not return true [handled] here, since we want the
                // press / click animation to be handled by the framework.
            }
        }
        return false;
    }

    /**
     * @return true if the dialer is currently opened (i.e. expanded).
     */
    public boolean isOpened() {
        return mDialerContainer.isOpened();
    }

    /**
     * Forces the dialer into the "open" state.
     * Does nothing if the dialer is already open.
     *
     * @param animate if true, open the dialer with an animation.
     */
    public void openDialer(boolean animate) {
        if (!mDialerContainer.isOpened()) {
            if (animate) {
                mDialerContainer.animateToggle();
            } else {
                mDialerContainer.toggle();
            }
        }
    }

    /**
     * Forces the dialer into the "closed" state.
     * Does nothing if the dialer is already closed.
     *
     * @param animate if true, close the dialer with an animation.
     */
    public void closeDialer(boolean animate) {
        if (mDialerContainer.isOpened()) {
            if (animate) {
                mDialerContainer.animateToggle();
            } else {
                mDialerContainer.toggle();
            }
        }
    }

    /**
     * Implemented for the SlidingDrawer open listener, prepare the dialer.
     */
    public void onDrawerOpened() {
        onDialerOpen();
    }

    /**
     * Implemented for the SlidingDrawer close listener, release the dialer.
     */
    public void onDrawerClosed() {
        onDialerClose();
    }

    /**
     * update the text area and playback the tone.
     */
    private final void appendDigit(char c) {
        // if it is a valid key, then update the display and send the dtmf tone.
        if (PhoneNumberUtils.is12Key(c)) {
            if (DBG) log("updating display and sending dtmf tone for '" + c + "'");

            if (mDigits != null) {
                mDigits.getText().append(c);
            }
            // play the tone if it exists.
            if (mToneMap.containsKey(c)) {
                // begin tone playback.
                playTone(c);
            }
        } else if (DBG) {
            log("ignoring dtmf request for '" + c + "'");
        }

    }

    /**
     * Start playing a DTMF tone, also begin the local tone playback if it is
     * enabled.
     *
     * @param tone a tone code from {@link ToneGenerator}
     */
    void playTone(char tone) {
        if (DBG) log("starting remote tone.");
        PhoneApp.getInstance().phone.startDtmf(tone);

        // if local tone playback is enabled, start it.
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("playTone: mToneGenerator == null, tone: " + tone);
                } else {
                    if (DBG) log("starting local tone " + tone);
                    mToneGenerator.startTone(mToneMap.get(tone));
                }
            }
        }
    }

    /**
     * Stop playing the current DTMF tone.
     *
     * The ToneStopper class (similar to that in {@link TwelveKeyDialer#mToneStopper})
     * has been removed in favor of synchronous start / stop calls since tone duration
     * is now a function of the input.
     */
    void stopTone() {
        if (DBG) log("stopping remote tone.");
        PhoneApp.getInstance().phone.stopDtmf();

        // if local tone playback is enabled, stop it.
        if (DBG) log("trying to stop local tone...");
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("stopTone: mToneGenerator == null");
                } else {
                    if (DBG) log("stopping local tone.");
                    mToneGenerator.stopTone();
                }
            }
        }
    }

    /**
     * Check to see if the keyEvent is dialable.
     */
    boolean isKeyEventAcceptable (KeyEvent event) {
        return (mDialerKeyListener != null && mDialerKeyListener.isKeyEventAcceptable(event));
    }

    /**
     * static logging method
     */
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
