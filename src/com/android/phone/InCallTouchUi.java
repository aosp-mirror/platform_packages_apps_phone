/*
 * Copyright (C) 2009 The Android Open Source Project
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
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;


/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class InCallTouchUi extends FrameLayout
        implements View.OnClickListener, View.OnTouchListener {
    private static final String LOG_TAG = "InCallTouchUi";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 1);

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    // Phone app instance
    private PhoneApp mApplication;

    // UI containers and elements
    private View mIncomingCallControls;  // UI elements used for an incoming call
    private View mInCallControls;  // UI elements while on a regular call
    //
    private View mAnswerButton;  // "Answer" (for incoming calls)
    private View mRejectButton;  // "Decline" or "Reject" (for incoming calls)
    private View mEndCallButton;  // "Hang up"

    // Double-tap detection state
    private long mLastTouchTime;  // in SystemClock.uptimeMillis() time base
    private View mLastTouchView;

    // Overall enabledness of the "touch UI" features
    private boolean mAllowIncomingCallTouchUi;
    private boolean mAllowInCallTouchUi;

    public InCallTouchUi(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DBG) log("InCallTouchUi constructor...");
        if (DBG) log("- this = " + this);
        if (DBG) log("- context " + context + ", attrs " + attrs);

        // Inflate our contents, and add it (to ourself) as a child.
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(
                R.layout.incall_touch_ui,  // resource
                this,                      // root
                true);

        mApplication = PhoneApp.getInstance();

        // The various touch UI features are enabled on a per-product
        // basis.  (These flags in config.xml may be overridden by
        // product-specific overlay files.)

        mAllowIncomingCallTouchUi = getResources().getBoolean(R.bool.allow_incoming_call_touch_ui);
        if (DBG) log("- incoming call touch UI: "
                     + (mAllowIncomingCallTouchUi ? "ENABLED" : "DISABLED"));
        mAllowInCallTouchUi = getResources().getBoolean(R.bool.allow_in_call_touch_ui);
        if (DBG) log("- regular in-call touch UI: "
                     + (mAllowInCallTouchUi ? "ENABLED" : "DISABLED"));
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (DBG) log("InCallTouchUi onFinishInflate(this = " + this + ")...");

        // Look up the various UI elements.

        // The containers:
        mIncomingCallControls = findViewById(R.id.incomingCallControls);
        mInCallControls = findViewById(R.id.inCallControls);

        // The buttons:
        mAnswerButton = findViewById(R.id.answerButton);
        mAnswerButton.setOnTouchListener(this);
        mRejectButton = findViewById(R.id.rejectButton);
        mRejectButton.setOnTouchListener(this);
        mEndCallButton = findViewById(R.id.endCallButton);
        mEndCallButton.setOnTouchListener(this);
    }

    /**
     * Updates the visibility and/or state of our UI elements, based on
     * the current state of the phone.
     */
    void updateState(Phone phone) {
        if (DBG) log("updateState(" + phone + ")...");

        Phone.State state = phone.getState();  // IDLE, RINGING, or OFFHOOK

        boolean showIncomingCallControls = false;
        boolean showInCallControls = false;

        if (state == Phone.State.RINGING) {
            // A phone call is ringing *or* call waiting.
            if (mAllowIncomingCallTouchUi) {
                if (DBG) log("- updateState: RINGING!  Showing incoming call controls...");
                showIncomingCallControls = true;

                // TODO: UI design issue: if the device is NOT currently
                // locked, we probably don't need to make the user
                // double-tap the "incoming call" buttons.  (The device
                // presumably isn't in a pocket or purse, so we don't need
                // to worry about false touches while it's ringing.)
                // But OTOH having "inconsistent" buttons might just make
                // it *more* confusing.
            }
        } else {
            if (mAllowInCallTouchUi) {
                if (DBG) log("- updateState: not ringing; showing regular in-call touch UI...");
                showInCallControls = true;
            }
        }

        if (showIncomingCallControls && showInCallControls) {
            throw new IllegalStateException(
                "'Incoming' and 'in-call' touch controls visible at the same time!");
        }
        mIncomingCallControls.setVisibility(showIncomingCallControls ? View.VISIBLE : View.GONE);
        mInCallControls.setVisibility(showInCallControls ? View.VISIBLE : View.GONE);

        // TODO: As an optimization, also consider setting the visibility
        // of the overall InCallTouchUi widget to GONE if *nothing* here
        // is visible right now.
    }

    // View.OnClickListener implementation
    public void onClick(View view) {
        int id = view.getId();
        if (DBG) log("onClick(View " + view + ", id " + id + ")...");

        // TODO: still need to add all the regular in-call buttons like
        // mute/speaker/bluetooth, hold, and dialpad.
    }

    private void onDoubleTap(View view) {
        int id = view.getId();
        if (DBG) log("onDoubleTap(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.answerButton:
                mInCallScreen.internalAnswerCall();
                // TODO: It might be cleaner to send an event thru the
                // InCallScreen's onClick() code path rather than calling
                // an "internal" InCallScreen method directly from here.
                break;

            case R.id.rejectButton:
                mInCallScreen.internalHangupRingingCall();
                // TODO: It might be cleaner to send an event thru the
                // InCallScreen's onClick() code path rather than calling
                // an "internal" InCallScreen method directly from here.
                break;

            case R.id.endCallButton:
                // TODO: Ultimately the "end call" button won't *need* to
                // be double-tapped.  (It'll just be a regular button.)
                // But we can't do that until the proximity sensor features
                // are all hooked up.
                mInCallScreen.internalHangup();
                // TODO: It might be cleaner to send an event thru the
                // InCallScreen's onClick() code path rather than calling
                // an "internal" InCallScreen method directly from here.
                break;

            default:
                if (DBG) log("onDoubleTap: unexpected double-tap: View " + view + ", id " + id);
                break;
        }
    }

    // View.OnTouchListener implementation
    public boolean onTouch(View v, MotionEvent event) {
        if (DBG) log ("onTouch(View " + v + ")...");

        // Look for double-tap events.

        // TODO: it's a little ugly to do the double-tap detection right
        // here; consider extracting it out to a helper class that can
        // listen for double-taps on arbitrary View(s), or maybe even
        // a whole new "DoubleTapButton" widget.

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            long now = SystemClock.uptimeMillis();
            if (DBG) log("- InCallTouchUi: handling a DOWN event, t = " + now);

            // Look for a double-tap on a particular View:
            if ((v == mLastTouchView)
                && (now < (mLastTouchTime
                           + ViewConfiguration.getDoubleTapTimeout()))) {
                if (DBG) log("==> InCallTouchUi: DOUBLE-TAP!");
                onDoubleTap(v);

                // Since we (presumably) just answered or rejected an
                // incoming call, that means the phone state is about to
                // change.  The in-call touch UI will update itself as
                // soon as the phone state change event gets back to the
                // InCallScreen (which will ultimately trigger an
                // updateScreen() call here.)
                // TODO: But also, consider explicitly starting some
                // fancier animation here, like fading out the
                // answer/decline buttons, or sliding them offscreen...
            }
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            // Stash away the view that was touched and the current
            // time in case this is the first tap of a double-tap
            // gesture.  (We measure the time from the first tap's UP
            // to the second tap's DOWN.)
            mLastTouchTime = SystemClock.uptimeMillis();
            mLastTouchView = v;
        }

        // And regardless of what just happened, we *always*
        // consume touch events here.
        return true;
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private static void logErr(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
