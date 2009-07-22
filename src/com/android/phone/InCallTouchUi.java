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
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ToggleButton;

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

    // UI containers / elements
    private View mIncomingCallControls;  // UI elements used for an incoming call
    private View mInCallControls;  // UI elements while on a regular call
    //
    private Button mHoldButton;
    private Button mEndCallButton;
    private Button mDialpadButton;
    private ToggleButton mBluetoothButton;
    private ToggleButton mMuteButton;
    private ToggleButton mSpeakerButton;
    private Button mAddCallButton;
    private Button mMergeCallsButton;
    private Button mSwapCallsButton;
    private Button mManageConferenceButton;

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

        // "Double-tap" buttons, where we listen for raw touch events
        // and possibly turn them into double-tap events:
        mIncomingCallControls.findViewById(R.id.answerButton).setOnTouchListener(this);
        mIncomingCallControls.findViewById(R.id.rejectButton).setOnTouchListener(this);

        // Regular (single-tap) buttons, where we listen for click events:
        // Main cluster of buttons:
        mHoldButton = (Button) mInCallControls.findViewById(R.id.holdButton);
        mHoldButton.setOnClickListener(this);
        mEndCallButton = (Button) mInCallControls.findViewById(R.id.endCallButton);
        mEndCallButton.setOnClickListener(this);
        mDialpadButton = (Button) mInCallControls.findViewById(R.id.dialpadButton);
        mDialpadButton.setOnClickListener(this);
        mBluetoothButton = (ToggleButton) mInCallControls.findViewById(R.id.bluetoothButton);
        mBluetoothButton.setOnClickListener(this);
        mMuteButton = (ToggleButton) mInCallControls.findViewById(R.id.muteButton);
        mMuteButton.setOnClickListener(this);
        mSpeakerButton = (ToggleButton) mInCallControls.findViewById(R.id.speakerButton);
        mSpeakerButton.setOnClickListener(this);
        // Upper corner buttons:
        mAddCallButton = (Button) mInCallControls.findViewById(R.id.addCallButton);
        mAddCallButton.setOnClickListener(this);
        mMergeCallsButton = (Button) mInCallControls.findViewById(R.id.mergeCallsButton);
        mMergeCallsButton.setOnClickListener(this);
        mSwapCallsButton = (Button) mInCallControls.findViewById(R.id.swapCallsButton);
        mSwapCallsButton.setOnClickListener(this);
        mManageConferenceButton =
                (Button) mInCallControls.findViewById(R.id.manageConferenceButton);
        mManageConferenceButton.setOnClickListener(this);
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
                // Ok, the in-call touch UI is available on this platform,
                // so make it visible (with some exceptions):
                if (mInCallScreen.okToShowInCallTouchUi()) {
                    showInCallControls = true;
                } else {
                    if (DBG) log("- updateState: NOT OK to show touch UI; disabling...");
                }
            }
        }

        if (showInCallControls) {
            updateInCallControls(phone);
        }

        if (showIncomingCallControls && showInCallControls) {
            throw new IllegalStateException(
                "'Incoming' and 'in-call' touch controls visible at the same time!");
        }
        mIncomingCallControls.setVisibility(showIncomingCallControls ? View.VISIBLE : View.GONE);
        mInCallControls.setVisibility(showInCallControls ? View.VISIBLE : View.GONE);

        // TODO: As an optimization, also consider setting the visibility
        // of the overall InCallTouchUi widget to GONE if *nothing at all*
        // is visible right now.
    }

    // View.OnClickListener implementation
    public void onClick(View view) {
        int id = view.getId();
        if (DBG) log("onClick(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.answerButton:
            case R.id.rejectButton:
                // These are "double-tap" buttons; we should never get regular
                // clicks from them.
                Log.w(LOG_TAG, "onClick: unexpected click: View " + view + ", id " + id);
                throw new IllegalStateException("Unexpected click from double-tap button");
                // TODO: remove above "throw" after initial debugging.
                // break;

            case R.id.holdButton:
            case R.id.endCallButton:
            case R.id.dialpadButton:
            case R.id.bluetoothButton:
            case R.id.muteButton:
            case R.id.speakerButton:
            case R.id.addCallButton:
            case R.id.mergeCallsButton:
            case R.id.swapCallsButton:
            case R.id.manageConferenceButton:
                // Clicks on the regular onscreen buttons get forwarded
                // straight to the InCallScreen.
                mInCallScreen.handleOnscreenButtonClick(id);
                break;

            default:
                if (DBG) log("onClick: unexpected click: View " + view + ", id " + id);
                throw new IllegalStateException("Unexpected click event");
                // TODO: remove above "throw" after initial debugging.
                // break;
        }
    }

    private void onDoubleTap(View view) {
        int id = view.getId();
        if (DBG) log("onDoubleTap(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.answerButton:
            case R.id.rejectButton:
                // Pass these clicks on to the InCallScreen.
                mInCallScreen.handleOnscreenButtonClick(id);
                break;

            default:
                Log.w(LOG_TAG, "onDoubleTap: unexpected double-tap: View " + view + ", id " + id);
                throw new IllegalStateException("Unexpected double-tap event");
                // TODO: remove above "throw" after initial debugging.
                // break;
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

    /**
     * Updates the enabledness and "checked" state of the buttons on the
     * "inCallControls" panel, based on the current telephony state.
     */
    void updateInCallControls(Phone phone) {
        // Note we do NOT need to worry here about cases where the entire
        // in-call touch UI is disabled, like during an OTA call or if the
        // dtmf dialpad is up.  (That's handled by updateState(), which
        // calls InCallScreen.okToShowInCallTouchUi().)
        //
        // If we get here, it *is* OK to show the in-call touch UI, so we
        // now need to update the enabledness and/or "checked" state of
        // each individual button.
        //

        // The InCallControlState object tells us the enabledness and/or
        // state of the various onscreen buttons:
        InCallControlState inCallControlState = mInCallScreen.getUpdatedInCallControlState();

        // "Hold"
        // Note: for now "Hold" isn't a ToggleButton, so we don't need to
        // update its "checked" state.  Just enable or disable it:
        mHoldButton.setEnabled(inCallControlState.canHold);

        // "End call": this button has no state and it's always enabled.
        mEndCallButton.setEnabled(true);

        // "Dialpad": Enabled only when it's OK to use the dialpad in the
        // first place.
        mDialpadButton.setEnabled(inCallControlState.dialpadEnabled);

        // "Bluetooth"
        mBluetoothButton.setEnabled(inCallControlState.bluetoothEnabled);
        mBluetoothButton.setChecked(inCallControlState.bluetoothIndicatorOn);

        // "Mute"
        mMuteButton.setEnabled(inCallControlState.canMute);
        mMuteButton.setChecked(inCallControlState.muteIndicatorOn);

        // "Speaker"
        mSpeakerButton.setEnabled(inCallControlState.speakerEnabled);
        mSpeakerButton.setChecked(inCallControlState.speakerOn);

        // "Add call"
        // (Note "add call" and "merge calls" are never both available at
        // the same time.  That's why it's OK for them to both be in the
        // same position onscreen.)
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        mAddCallButton.setVisibility(inCallControlState.canAddCall ? View.VISIBLE : View.GONE);

        // "Merge calls"
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        mMergeCallsButton.setVisibility(inCallControlState.canMerge ? View.VISIBLE : View.GONE);

        // "Swap calls"
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        mSwapCallsButton.setVisibility(inCallControlState.canSwap ? View.VISIBLE : View.GONE);

        // "Manage conference"
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        boolean showManageConferenceTouchButton = inCallControlState.manageConferenceVisible
                && inCallControlState.manageConferenceEnabled;
        mManageConferenceButton.setVisibility(showManageConferenceTouchButton
                                              ? View.VISIBLE : View.GONE);
    }


    //
    // InCallScreen API
    //

    /**
     * @return true if the onscreen touch UI is enabled (for regular
     * "ongoing call" states) on the current device.
     */
    public boolean isTouchUiEnabled() {
        return mAllowInCallTouchUi;
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }

    private static void logErr(String msg) {
        Log.e(LOG_TAG, msg);
    }
}
