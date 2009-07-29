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

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Message;
import android.provider.Settings;
import android.telephony.PhoneNumberUtils;
import android.text.Editable;
import android.text.Spannable;
import android.text.method.DialerKeyListener;
import android.text.method.MovementMethod;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.SlidingDrawer;
import android.widget.TextView;

import com.android.internal.telephony.CallerInfo;
import com.android.internal.telephony.CallerInfoAsyncQuery;
import com.android.internal.telephony.Phone;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;


/**
 * Dialer class that encapsulates the DTMF twelve key behaviour.
 * This model backs up the UI behaviour in DTMFTwelveKeyDialerView.java.
 */
public class DTMFTwelveKeyDialer implements
        CallerInfoAsyncQuery.OnQueryCompleteListener,
        SlidingDrawer.OnDrawerOpenListener,
        SlidingDrawer.OnDrawerCloseListener,
        View.OnTouchListener,
        View.OnKeyListener {
    private static final String LOG_TAG = "DTMFTwelveKeyDialer";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    // events
    private static final int PHONE_DISCONNECT = 100;
    private static final int DTMF_SEND_CNF = 101;
    private static final int STOP_DTMF_TONE = 102;



    private Phone mPhone;
    private ToneGenerator mToneGenerator;
    private Object mToneGeneratorLock = new Object();

    // indicate if we want to enable the DTMF tone playback.
    private boolean mDTMFToneEnabled;

    // DTMF tone type
    private int mDTMFToneType;

    // indicate if the confirmation from TelephonyFW is pending.
    private boolean mDTMFBurstCnfPending = false;

    // Queue to queue the short dtmf characters.
    private Queue<Character> mDTMFQueue = new LinkedList<Character>();

    //  Short Dtmf tone duration
    private static final int DTMF_DURATION_MS = 120;


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

    // EditText field used to display the DTMF digits sent so far.
    // - In portrait mode, we use the EditText that comes from
    //   the full dialpad:
    private EditText mDialpadDigits;
    // - In landscape mode, we use a different EditText that's
    //   built into the InCallScreen:
    private EditText mInCallDigits;
    // (Only one of these will be visible at any given point.)

    // InCallScreen reference.
    private InCallScreen mInCallScreen;

    // SlidingDrawer reference.
    private SlidingDrawer mDialerContainer;

    // view reference
    private DTMFTwelveKeyDialerView mDialerView;

    // key listner reference, may or may not be attached to a view.
    private DTMFKeyListener mDialerKeyListener;

    /**
     * Create an input method just so that the textview can display the cursor.
     * There is no selecting / positioning on the dialer field, only number input.
     */
    private static class DTMFDisplayMovementMethod implements MovementMethod {

        /**Return false since we are NOT consuming the input.*/
        public boolean onKeyDown(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
            return false;
        }

        /**Return false since we are NOT consuming the input.*/
        public boolean onKeyUp(TextView widget, Spannable buffer, int keyCode, KeyEvent event) {
            return false;
        }

        /**Return false since we are NOT consuming the input.*/
        public boolean onKeyOther(TextView view, Spannable text, KeyEvent event) {
            return false;
        }

        /**Return false since we are NOT consuming the input.*/
        public boolean onTrackballEvent(TextView widget, Spannable buffer, MotionEvent event) {
            return false;
        }

        /**Return false since we are NOT consuming the input.*/
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            return false;
        }

        public void initialize(TextView widget, Spannable text) {
        }

        public void onTakeFocus(TextView view, Spannable text, int dir) {
        }

        /**Disallow arbitrary selection.*/
        public boolean canSelectArbitrarily() {
            return false;
        }
    }

    /**
     * Our own key listener, specialized for dealing with DTMF codes.
     *   1. Ignore the backspace since it is irrelevant.
     *   2. Allow ONLY valid DTMF characters to generate a tone and be
     *      sent as a DTMF code.
     *   3. All other remaining characters are handled by the superclass.
     */
    private class DTMFKeyListener extends DialerKeyListener {

        private DTMFDisplayAnimation mDTMFDisplayAnimation;

        /**
         * Class that controls the fade in/out of the DTMF dialer field.
         * Logic is tied into the keystroke events handled by the
         * DTMFKeyListener.
         *
         * The key to this logic is the use of WAIT_FOR_USER_INPUT and
         * Animation.fillBefore(true). This keeps the alpha animation in its
         * beginning state until some key interaction is detected.  On the
         * key interaction, the animation start time is reset as appropriate.
         *
         * On fade in:
         *   1.Set and hold the alpha value to 0.0.
         *   2.Animation is triggered on key down.
         *   2.Animation is started immediately.
         * On fade out:
         *   1.Set and hold the alpha value to 1.0.
         *   2.Animation is triggered on key up.
         *   2.Animation is FADE_OUT_TIMEOUT after trigger.
         */
        private class DTMFDisplayAnimation extends Handler implements AnimationListener {
            // events for the fade in and out.
            private static final int EVENT_FADE_IN = -1;
            private static final int EVENT_FADE_OUT = -2;

            // static constants
            // duration for the fade in animation
            private static final int FADE_IN_ANIMATION_TIME = 500;
            // duration for the fade out animation
            private static final int FADE_OUT_ANIMATION_TIME = 1000;
            /**
             * Wait time after last user activity to begin fade out.
             * Timeout to match:
             * {@link com.android.server.PowerManagerService#SHORT_KEYLIGHT_DELAY}
             */
            private static final int FADE_OUT_TIMEOUT = 6000;

            /**
             * Value indicating we should expect user input.  This is used
             * to keep animations in the started / initial state until a new
             * start time is set.
             */
            private static final long WAIT_FOR_USER_INPUT = Long.MAX_VALUE;

            // DTMF display field
            private View mDTMFDisplay;

            // Fade in / out animations.
            private AlphaAnimation mFadeIn;
            private AlphaAnimation mFadeOut;

            /**
             * API implemented for AnimationListener, called on start of animation.
             */
            public void onAnimationStart(Animation animation) {}

            /**
             * API implemented for AnimationListener, called on end of animation.
             * This code just prepares the next animation to be run.
             */
            public void onAnimationEnd(Animation animation) {
                sendEmptyMessage(animation == mFadeOut ? EVENT_FADE_IN : EVENT_FADE_OUT);
            }

            /**
             * API implemented for AnimationListener, called on repeat of animation.
             */
            public void onAnimationRepeat(Animation animation) {}

            /**
             * Handle the FADE_IN and FADE_OUT messages
             */
            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case EVENT_FADE_IN:
                        // just initialize to normal fade in.
                        prepareFadeIn();
                        break;
                    case EVENT_FADE_OUT:
                    default:
                        // set animation to fade out.
                        mDTMFDisplay.setAnimation(mFadeOut);
                        break;
                }
            }

            DTMFDisplayAnimation(EditText display) {
                mDTMFDisplay = display;

                // create fade in animation
                mFadeIn = new AlphaAnimation(0.0f, 1.0f);
                mFadeIn.setDuration(FADE_IN_ANIMATION_TIME);
                mFadeIn.setAnimationListener(this);
                mFadeIn.setFillBefore(true);

                // create fade out animation.
                mFadeOut = new AlphaAnimation(1.0f, 0.0f);
                mFadeOut.setDuration(FADE_OUT_ANIMATION_TIME);
                mFadeOut.setAnimationListener(this);
                mFadeOut.setFillBefore(true);
            }

            /**
             * Set up dtmf display field for the fade in trigger.
             */
            void prepareFadeIn() {
                mDTMFDisplay.setAnimation(mFadeIn);
                mFadeIn.setStartTime(WAIT_FOR_USER_INPUT);
            }

            /**
             * Notify that a key press has occurred, handle the appropriate
             * animation changes.
             */
            void onKeyDown() {
                long currentAnimTime = AnimationUtils.currentAnimationTimeMillis();

                if ((mDTMFDisplay.getAnimation() == mFadeOut) &&
                        (mFadeOut.getStartTime() < currentAnimTime)) {
                    // reset the animation if it is running.
                    mFadeOut.reset();
                } else if (mFadeIn.getStartTime() > currentAnimTime){
                    // otherwise start the fade in.
                    mFadeIn.start();
                }

                // Reset the fade out timer.
                mFadeOut.setStartTime(WAIT_FOR_USER_INPUT);
            }

            /**
             * Notify that a key up has occurred, set the fade out animation
             * start time accordingly.
             */
            void onKeyUp() {
                mFadeOut.setStartTime(AnimationUtils.currentAnimationTimeMillis() +
                        FADE_OUT_TIMEOUT);
            }
        }

        private DTMFKeyListener(EditText display) {
            super();

            // setup the display and animation if we're in landscape.
            if (display != null && InCallScreen.ConfigurationHelper.isLandscape()) {
                mDTMFDisplayAnimation = new DTMFDisplayAnimation(display);
                mDTMFDisplayAnimation.prepareFadeIn();
            }
        }

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
         * Return true if the keyCode is an accepted modifier key for the
         * dialer (ALT or SHIFT).
         */
        private boolean isAcceptableModifierKey(int keyCode) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_ALT_LEFT:
                case KeyEvent.KEYCODE_ALT_RIGHT:
                case KeyEvent.KEYCODE_SHIFT_LEFT:
                case KeyEvent.KEYCODE_SHIFT_RIGHT:
                    return true;
                default:
                    return false;
            }
        }

        /**
         * Overriden so that with each valid button press, we start sending
         * a dtmf code and play a local dtmf tone.
         */
        @Override
        public boolean onKeyDown(View view, Editable content,
                                 int keyCode, KeyEvent event) {
            // if (DBG) log("DTMFKeyListener.onKeyDown, keyCode " + keyCode + ", view " + view);

            // find the character
            char c = (char) lookup(event, content);

            // if not a long press, and parent onKeyDown accepts the input
            if (event.getRepeatCount() == 0 && super.onKeyDown(view, content, keyCode, event)) {

                boolean keyOK = ok(getAcceptedChars(), c);

                // show the display on any key down.
                if (mDTMFDisplayAnimation != null && (keyOK || isAcceptableModifierKey(keyCode))) {
                    mDTMFDisplayAnimation.onKeyDown();
                }

                // if the character is a valid dtmf code, start playing the tone and send the
                // code.
                if (keyOK) {
                    if (DBG) log("DTMFKeyListener reading '" + c + "' from input.");
                    processDtmf(c);
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
            // if (DBG) log("DTMFKeyListener.onKeyUp, keyCode " + keyCode + ", view " + view);

            super.onKeyUp(view, content, keyCode, event);

            // find the character
            char c = (char) lookup(event, content);

            boolean keyOK = ok(getAcceptedChars(), c);

            // show the display on any key down.
            if (mDTMFDisplayAnimation != null && (keyOK || isAcceptableModifierKey(keyCode))) {
                mDTMFDisplayAnimation.onKeyUp();
            }

            if (keyOK) {
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
                    processDtmf(c);
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
                //the below piece of code sends stopDTMF event unnecessarily even when a null event
                //is received, hence commenting it.
                /*if (DBG) log("Stopping the last played tone.");
                stopTone();*/
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
        private char lookup(KeyEvent event) {
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
    private Handler mHandler = new Handler() {
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
                case DTMF_SEND_CNF:
                    if (DBG) log("dtmf confirmation received from FW.");
                    // handle burst dtmf confirmation
                    handleBurstDtmfConfirmation();
                    break;
                case STOP_DTMF_TONE:
                    if (DBG) log("stop-dtmf-tone received.");
                    stopToneCdma();
                    break;
            }
        }
    };


    public DTMFTwelveKeyDialer(InCallScreen parent) {
        mInCallScreen = parent;
        mPhone = ((PhoneApp) mInCallScreen.getApplication()).phone;
        mDialerContainer = (SlidingDrawer) mInCallScreen.findViewById(R.id.dialer_container);

        // mDialerContainer is only valid when we're looking at the portrait version of
        // dtmf_twelve_key_dialer.
        if (mDialerContainer != null) {
            mDialerContainer.setOnDrawerOpenListener(this);
            mDialerContainer.setOnDrawerCloseListener(this);
        }

        // Set up the EditText widget that displays DTMF digits in
        // landscape mode.  (This widget belongs to the InCallScreen, as
        // opposed to mDialpadDigits, which is part of the full dialpad,
        // and is used in portrait mode.)
        mInCallDigits = mInCallScreen.getDialerDisplay();

        mDialerKeyListener = new DTMFKeyListener(mInCallDigits);
        // If the widget exists, set the behavior correctly.
        if (mInCallDigits != null && InCallScreen.ConfigurationHelper.isLandscape()) {
            mInCallDigits.setKeyListener(mDialerKeyListener);
            mInCallDigits.setMovementMethod(new DTMFDisplayMovementMethod());

            // remove the long-press context menus that support
            // the edit (copy / paste / select) functions.
            mInCallDigits.setLongClickable(false);
        }
    }

    /**
     * Called when we want to hide the DTMF Display field immediately.
     *
     * @param shouldHide if true, hide the display (and disable DTMF tones) immediately;
     * otherwise, re-enable the display.
     */
    public void hideDTMFDisplay(boolean shouldHide) {
        DTMFKeyListener.DTMFDisplayAnimation animation = mDialerKeyListener.mDTMFDisplayAnimation;

        // if the animation is in place
        if (animation != null) {
            View text = animation.mDTMFDisplay;

            // and the display is available
            if (text != null) {
                // hide the display if necessary
                text.setVisibility(shouldHide ? View.GONE : View.VISIBLE);
                if (shouldHide) {
                    // null the animation - this makes the display disappear faster
                    text.setAnimation(null);
                } else {
                    // otherwise reset the animation to the initial state.
                    animation.prepareFadeIn();
                }
            }
        }
    }

    /**
     * Null out our reference to the InCallScreen activity.
     * This indicates that the InCallScreen activity has been destroyed.
     * At the same time, get rid of listeners since we're not going to
     * be valid anymore.
     */
    /* package */ void clearInCallScreenReference() {
        mInCallScreen = null;
        mDialerKeyListener = null;
        if (mDialerContainer != null) {
            mDialerContainer.setOnDrawerOpenListener(null);
            mDialerContainer.setOnDrawerCloseListener(null);
        }
        if (mPhone.getPhoneName().equals("CDMA")) {
            mHandler.removeMessages(STOP_DTMF_TONE);
            mHandler.removeMessages(DTMF_SEND_CNF);
            synchronized (mDTMFQueue) {
                mDTMFBurstCnfPending = false;
                mDTMFQueue.clear();
            }
        }
        closeDialer(false);
    }

    /**
     * Dialer code that runs when the dialer is brought up.
     * This includes layout changes, etc, and just prepares the dialer model for use.
     */
    private void onDialerOpen() {
        if (DBG) log("onDialerOpen()...");

        // inflate the view.
        mDialerView = (DTMFTwelveKeyDialerView) mInCallScreen.findViewById(R.id.dtmf_dialer);
        mDialerView.setDialer(this);

        // Have the WindowManager filter out cheek touch events
        mInCallScreen.getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);

        mPhone.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);

        // set to a longer delay while the dialer is up.
        PhoneApp app = PhoneApp.getInstance();
        app.updateWakeState();

        // setup the digit display
        mDialpadDigits = (EditText) mDialerView.findViewById(R.id.dtmfDialerField);
        mDialpadDigits.setKeyListener(new DTMFKeyListener(null));
        mDialpadDigits.requestFocus();

        // remove the long-press context menus that support
        // the edit (copy / paste / select) functions.
        mDialpadDigits.setLongClickable(false);

        // Check for the presence of the keypad (portrait mode)
        View view = mDialerView.findViewById(R.id.one);
        if (view != null) {
            if (DBG) log("portrait mode setup");
            setupKeypad();
        } else {
            if (DBG) log("landscape mode setup");
            // Adding hint text to the field to indicate that keyboard
            // is needed while in landscape mode.
            mDialpadDigits.setHint(R.string.dialerKeyboardHintText);
        }

        // setup the local tone generator.
        startDialerSession();

        // Give the InCallScreen a chance to do any necessary UI updates.
        mInCallScreen.onDialerOpen();
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
                        mToneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, 80);
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
    private void onDialerClose() {
        if (DBG) log("onDialerClose()...");

        // reset back to a short delay for the poke lock.
        PhoneApp app = PhoneApp.getInstance();
        app.updateWakeState();

        mPhone.unregisterForDisconnect(mHandler);

        stopDialerSession();

        // Give the InCallScreen a chance to do any necessary UI updates.
        mInCallScreen.onDialerClose();
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
        // if (DBG) log("onKeyDown:  keyCode " + keyCode);
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
        // if (DBG) log("onKeyUp:  keyCode " + keyCode);
        return mInCallScreen.onKeyUp(keyCode, event);
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
                    processDtmf(mDisplayMap.get(viewId));
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
        // if (DBG) log("onKey:  keyCode " + keyCode + ", view " + v);

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            int viewId = v.getId();
            if (mDisplayMap.containsKey(viewId)) {
                switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (event.getRepeatCount() == 0) {
                        processDtmf(mDisplayMap.get(viewId));
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
        return mDialerContainer != null && mDialerContainer.isOpened();
    }

    /**
     * Forces the dialer into the "open" state.
     * Does nothing if the dialer is already open.
     *
     * @param animate if true, open the dialer with an animation.
     */
    public void openDialer(boolean animate) {
        if (mDialerContainer != null && !mDialerContainer.isOpened()) {
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
        if (mDialerContainer != null && mDialerContainer.isOpened()) {
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
     * Processes the specified digit as a DTMF key, by playing the
     * appropriate DTMF tone, and appending the digit to the EditText
     * field that displays the DTMF digits sent so far.
     */
    private final void processDtmf(char c) {
        // if it is a valid key, then update the display and send the dtmf tone.
        if (PhoneNumberUtils.is12Key(c)) {
            if (DBG) log("updating display and sending dtmf tone for '" + c + "'");

            if (mDialpadDigits != null) {
                mDialpadDigits.getText().append(c);
            }

            // Note we *don't* need to manually append this digit to the
            // landscape-mode EditText field (mInCallDigits), since it
            // gets key events directly and automatically appends whetever
            // the user types.

            // Play the tone if it exists.
            if (mToneMap.containsKey(c)) {
                // begin tone playback.
                startTone(c);
            }
        } else if (DBG) {
            log("ignoring dtmf request for '" + c + "'");
        }

        // Any DTMF keypress counts as explicit "user activity".
        PhoneApp.getInstance().pokeUserActivity();
    }

    /**
     * Clears out the display of "DTMF digits typed so far" that's kept in
     * either mDialpadDigits or mInCallDigits (depending on whether we're
     * in portrait or landscape mode.)
     *
     * The InCallScreen is responsible for calling this method any time a
     * new call becomes active (or, more simply, any time a call ends).
     * This is how we make sure that the "history" of DTMF digits you type
     * doesn't persist from one call to the next.
     *
     * TODO: it might be more elegent if the dialpad itself could remember
     * the call that we're associated with, and clear the digits if the
     * "current call" has changed since last time.  (This would require
     * some unique identifier that's different for each call.  We can't
     * just use the foreground Call object, since that's a singleton that
     * lasts the whole life of the phone process.  Instead, maybe look at
     * the Connection object that comes back from getEarliestConnection()?
     * Or getEarliestConnectTime()?)
     *
     * Or to be even fancier, we could keep a mapping of *multiple*
     * "active calls" to DTMF strings.  That way you could have two lines
     * in use and swap calls multiple times, and we'd still remember the
     * digits for each call.  (But that's such an obscure use case that
     * it's probably not worth the extra complexity.)
     */
    public void clearDigits() {
        if (DBG) log("clearDigits()...");

        if (mDialpadDigits != null) {
            mDialpadDigits.setText("");
        }
        if (mInCallDigits != null) {
            mInCallDigits.setText("");
        }
    }

    /**
     * Starts playing a DTMF tone.  Also begins the local tone playback,
     * if enabled.
     * The access of this function is package rather than private
     * since this is being referred from InCallScreen.
     * InCallScreen calls this function to utilize the DTMF ToneGenerator properties
     * defined here.
     * @param tone a tone code from {@link ToneGenerator}
     */
    /* package */ void startDtmfTone(char tone) {
        if (DBG) log("startDtmfTone()...");
        mPhone.startDtmf(tone);

        // if local tone playback is enabled, start it.
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("startDtmfTone: mToneGenerator == null, tone: " + tone);
                } else {
                    if (DBG) log("starting local tone " + tone);
                    mToneGenerator.startTone(mToneMap.get(tone));
                }
            }
        }
    }

    /**
     * Stops playing the current DTMF tone.
     *
     * The ToneStopper class (similar to that in {@link TwelveKeyDialer#mToneStopper})
     * has been removed in favor of synchronous start / stop calls since tone duration
     * is now a function of the input.
     * The acess of this function is package rather than private
     * since this is being referred from InCallScreen.
     * InCallScreen calls this function to utilize the DTMF ToneGenerator properties
     * defined here.
     */
    /* package */ void stopDtmfTone() {
        if (DBG) log("stopDtmfTone()...");
        mPhone.stopDtmf();

        // if local tone playback is enabled, stop it.
        if (DBG) log("trying to stop local tone...");
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("stopDtmfTone: mToneGenerator == null");
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

    /**
     * Plays the local tone based the phone type.
     */
    private void startTone(char c) {
        if (mPhone.getPhoneName().equals("GSM")) {
            startDtmfTone(c);
        } else {
            startToneCdma(c);
        }
    }

    /**
     * Stops the local tone based on the phone type.
     */
    private void stopTone() {
        if (mPhone.getPhoneName().equals("GSM")) {
            stopDtmfTone();
        } else {
            // Cdma case we do stopTone only for Long DTMF Setting
            if (mDTMFToneType == CallFeaturesSetting.DTMF_TONE_TYPE_LONG) {
                stopToneCdma();
            }
        }
    }

    /**
     * Plays tone when the DTMF setting is normal(Short).
     */
    void startToneCdma(char tone) {
        // Read the settings as it may be changed by the user during the call
        mDTMFToneType = Settings.System.getInt(mInCallScreen.getContentResolver(),
                Settings.System.DTMF_TONE_TYPE_WHEN_DIALING,
                CallFeaturesSetting.preferredDtmfMode);
        // For Short DTMF we need to play the local tone for fixed duration
        if (mDTMFToneType == CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL) {
            sendShortDtmfToNetwork (tone);
        } else {
            // Pass as a char to be sent to network
            Log.i(LOG_TAG, "send long dtmf for " + tone);
            mPhone.startDtmf(tone);
        }

        // if local tone playback is enabled, start it.
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("startToneCdma: mToneGenerator == null, tone: " + tone);
                } else {
                    if (DBG) log("starting local tone " + tone);

                    // Start the new tone.
                    mToneGenerator.startTone(mToneMap.get(tone));

                    // Stopped pending and Started new STOP_DTMF_TONE timer.
                    if (mDTMFToneType == CallFeaturesSetting.DTMF_TONE_TYPE_NORMAL) {
                        mHandler.removeMessages(STOP_DTMF_TONE);
                        mHandler.sendEmptyMessageDelayed(STOP_DTMF_TONE,DTMF_DURATION_MS);
                    }
                }
            }
        }
    }

    /**
     * Sends the dtmf character over the network for short DTMF settings
     * When the characters are entered in quick succession,
     * the characters are queued before sending over the network.
     */
    private void sendShortDtmfToNetwork(char dtmfDigit) {
        synchronized (mDTMFQueue) {
            if (mDTMFBurstCnfPending == true) {
                // Insert the dtmf char to the queue
                mDTMFQueue.add(new Character(dtmfDigit));
            } else {
                String dtmfStr = Character.toString(dtmfDigit);
                Log.i(LOG_TAG,"dtmfsent = " + dtmfStr);
                mPhone.sendBurstDtmf(dtmfStr, 0, 0, mHandler.obtainMessage(DTMF_SEND_CNF));
                // Set flag to indicate wait for Telephony confirmation.
                mDTMFBurstCnfPending = true;
            }
        }
    }

    /**
     * Stops the dtmf from being sent over the network for Long DTMF case and stops local DTMF key feedback tone.
     */
    private void stopToneCdma() {
        if (DBG) log("stopping remote tone.");

        if (mDTMFToneType == CallFeaturesSetting.DTMF_TONE_TYPE_LONG) {
            mPhone.stopDtmf();
        }

        // if local tone playback is enabled, stop it.
        if (DBG) log("trying to stop local tone...");
        if (mDTMFToneEnabled) {
            synchronized (mToneGeneratorLock) {
                if (mToneGenerator == null) {
                    if (DBG) log("stopToneCdma: mToneGenerator == null");
                } else {
                    if (DBG) log("stopping local tone.");
                    mToneGenerator.stopTone();
                }
            }
        }
    }

    /**
     * Handles Burst Dtmf Confirmation from the Framework.
     */
    void handleBurstDtmfConfirmation() {
        Character dtmfChar = null;
        synchronized(mDTMFQueue) {
            mDTMFBurstCnfPending = false;
            if(!mDTMFQueue.isEmpty()) {
                dtmfChar = mDTMFQueue.remove();
                Log.i(LOG_TAG, "The dtmf character removed from queue" + dtmfChar);
            }
        }
        if (dtmfChar != null) {
            sendShortDtmfToNetwork(dtmfChar);
        }
    }

}
