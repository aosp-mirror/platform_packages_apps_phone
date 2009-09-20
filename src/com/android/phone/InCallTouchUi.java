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
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.RotarySelector;


/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class InCallTouchUi extends FrameLayout
        implements View.OnClickListener, RotarySelector.OnDialTriggerListener {
    private static final String LOG_TAG = "InCallTouchUi";
    private static final boolean DBG =
            (PhoneApp.DBG_LEVEL >= 1) && (SystemProperties.getInt("ro.debuggable", 0) == 1);

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    // Phone app instance
    private PhoneApp mApplication;

    // UI containers / elements
    private RotarySelector mIncomingCallWidget;  // UI used for an incoming call
    private View mInCallControls;  // UI elements while on a regular call
    //
    private Button mAddButton;
    private Button mMergeButton;
    private Button mEndButton;
    private Button mDialpadButton;
    private ToggleButton mBluetoothButton;
    private ToggleButton mMuteButton;
    private ToggleButton mSpeakerButton;
    //
    private View mHoldButtonContainer;
    private ImageButton mHoldButton;
    private TextView mHoldButtonLabel;
    private View mSwapButtonContainer;
    private ImageButton mSwapButton;
    private View mManageConferenceButtonContainer;
    private ImageButton mManageConferenceButton;
    //
    private Drawable mHoldIcon;
    private Drawable mUnholdIcon;
    private Drawable mShowDialpadIcon;
    private Drawable mHideDialpadIcon;

    // Time of the most recent "answer" or "reject" action (see updateState())
    private long mLastIncomingCallActionTime;  // in SystemClock.uptimeMillis() time base

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

        // "Dial-to-answer" widget for incoming calls.
        mIncomingCallWidget = (RotarySelector) findViewById(R.id.incomingCallWidget);
        mIncomingCallWidget.setLeftHandleResource(R.drawable.ic_jog_dial_answer);
        mIncomingCallWidget.setRightHandleResource(R.drawable.ic_jog_dial_decline);
        mIncomingCallWidget.setOnDialTriggerListener(this);

        // Container for the UI elements shown while on a regular call.
        mInCallControls = findViewById(R.id.inCallControls);

        // Regular (single-tap) buttons, where we listen for click events:
        // Main cluster of buttons:
        mAddButton = (Button) mInCallControls.findViewById(R.id.addButton);
        mAddButton.setOnClickListener(this);
        mMergeButton = (Button) mInCallControls.findViewById(R.id.mergeButton);
        mMergeButton.setOnClickListener(this);
        mEndButton = (Button) mInCallControls.findViewById(R.id.endButton);
        mEndButton.setOnClickListener(this);
        mDialpadButton = (Button) mInCallControls.findViewById(R.id.dialpadButton);
        mDialpadButton.setOnClickListener(this);
        mBluetoothButton = (ToggleButton) mInCallControls.findViewById(R.id.bluetoothButton);
        mBluetoothButton.setOnClickListener(this);
        mMuteButton = (ToggleButton) mInCallControls.findViewById(R.id.muteButton);
        mMuteButton.setOnClickListener(this);
        mSpeakerButton = (ToggleButton) mInCallControls.findViewById(R.id.speakerButton);
        mSpeakerButton.setOnClickListener(this);

        // Upper corner buttons:
        mHoldButtonContainer = mInCallControls.findViewById(R.id.holdButtonContainer);
        mHoldButton = (ImageButton) mInCallControls.findViewById(R.id.holdButton);
        mHoldButton.setOnClickListener(this);
        mHoldButtonLabel = (TextView) mInCallControls.findViewById(R.id.holdButtonLabel);
        //
        mSwapButtonContainer = mInCallControls.findViewById(R.id.swapButtonContainer);
        mSwapButton = (ImageButton) mInCallControls.findViewById(R.id.swapButton);
        mSwapButton.setOnClickListener(this);
        //
        mManageConferenceButtonContainer =
                mInCallControls.findViewById(R.id.manageConferenceButtonContainer);
        mManageConferenceButton =
                (ImageButton) mInCallControls.findViewById(R.id.manageConferenceButton);
        mManageConferenceButton.setOnClickListener(this);

        // Icons we need to change dynamically.  (Most other icons are specified
        // directly in incall_touch_ui.xml.)
        mHoldIcon = getResources().getDrawable(R.drawable.ic_in_call_touch_round_hold);
        mUnholdIcon = getResources().getDrawable(R.drawable.ic_in_call_touch_round_unhold);
        mShowDialpadIcon = getResources().getDrawable(R.drawable.ic_in_call_touch_dialpad);
        mHideDialpadIcon = getResources().getDrawable(R.drawable.ic_in_call_touch_dialpad_close);
    }

    /**
     * Updates the visibility and/or state of our UI elements, based on
     * the current state of the phone.
     */
    void updateState(Phone phone) {
        if (DBG) log("updateState(" + phone + ")...");

        if (mInCallScreen == null) {
            log("- updateState: mInCallScreen has been destroyed; bailing out...");
            return;
        }

        Phone.State state = phone.getState();  // IDLE, RINGING, or OFFHOOK
        if (DBG) log("- updateState: phone state is " + state);

        boolean showIncomingCallControls = false;
        boolean showInCallControls = false;

        if (state == Phone.State.RINGING) {
            // A phone call is ringing *or* call waiting.
            if (mAllowIncomingCallTouchUi) {
                // Watch out: even if the phone state is RINGING, it's
                // possible for the ringing call to be in the DISCONNECTING
                // state.  (This typically happens immediately after the user
                // rejects an incoming call, and in that case we *don't* show
                // the incoming call controls.)
                final Call ringingCall = phone.getRingingCall();
                if (ringingCall.getState().isAlive()) {
                    if (DBG) log("- updateState: RINGING!  Showing incoming call controls...");
                    showIncomingCallControls = true;
                }

                // Ugly hack to cover up slow response from the radio:
                // if we attempted to answer or reject an incoming call
                // within the last 500 msec, *don't* show the incoming call
                // UI even if the phone is still in the RINGING state.
                long now = SystemClock.uptimeMillis();
                if (now < mLastIncomingCallActionTime + 500) {
                    log("updateState: Too soon after last action; not drawing!");
                    showIncomingCallControls = false;
                }

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
        mIncomingCallWidget.setVisibility(showIncomingCallControls ? View.VISIBLE : View.GONE);
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
            case R.id.addButton:
            case R.id.mergeButton:
            case R.id.endButton:
            case R.id.dialpadButton:
            case R.id.bluetoothButton:
            case R.id.muteButton:
            case R.id.speakerButton:
            case R.id.holdButton:
            case R.id.swapButton:
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

        // "Add" or "Merge":
        // These two buttons occupy the same space onscreen, so only
        // one of them should be available at a given moment.
        if (inCallControlState.canAddCall) {
            mAddButton.setVisibility(View.VISIBLE);
            mAddButton.setEnabled(true);
            mMergeButton.setVisibility(View.GONE);
        } else if (inCallControlState.canMerge) {
            mMergeButton.setVisibility(View.VISIBLE);
            mMergeButton.setEnabled(true);
            mAddButton.setVisibility(View.GONE);
        } else {
            // Neither "Add" nor "Merge" is available.  (This happens in
            // some transient states, like while dialing an outgoing call,
            // and in other rare cases like if you have both lines in use
            // *and* there are already 5 people on the conference call.)
            // Since the common case here is "while dialing", we show the
            // "Add" button in a disabled state so that there won't be any
            // jarring change in the UI when the call finally connects.
            mAddButton.setVisibility(View.VISIBLE);
            mAddButton.setEnabled(false);
            mMergeButton.setVisibility(View.GONE);
        }
        if (inCallControlState.canAddCall && inCallControlState.canMerge) {
            // Uh oh, the InCallControlState thinks that "Add" *and* "Merge"
            // should both be available right now.  This *should* never
            // happen with either GSM or CDMA, but if it's possible on any
            // future devices we may need to re-layout Add and Merge so
            // they can both be visible at the same time...
            Log.w(LOG_TAG, "updateInCallControls: Add *and* Merge enabled, but can't show both!");
        }

        // "End call": this button has no state and it's always enabled.
        mEndButton.setEnabled(true);

        // "Dialpad": Enabled only when it's OK to use the dialpad in the
        // first place.
        mDialpadButton.setEnabled(inCallControlState.dialpadEnabled);
        //
        if (inCallControlState.dialpadVisible) {
            // Show the "hide dialpad" state.
            mDialpadButton.setText(R.string.onscreenHideDialpadText);
            mDialpadButton.setCompoundDrawablesWithIntrinsicBounds(
                null, mHideDialpadIcon, null, null);
        } else {
            // Show the "show dialpad" state.
            mDialpadButton.setText(R.string.onscreenShowDialpadText);
            mDialpadButton.setCompoundDrawablesWithIntrinsicBounds(
                    null, mShowDialpadIcon, null, null);
        }

        // "Bluetooth"
        mBluetoothButton.setEnabled(inCallControlState.bluetoothEnabled);
        mBluetoothButton.setChecked(inCallControlState.bluetoothIndicatorOn);

        // "Mute"
        mMuteButton.setEnabled(inCallControlState.canMute);
        mMuteButton.setChecked(inCallControlState.muteIndicatorOn);

        // "Speaker"
        mSpeakerButton.setEnabled(inCallControlState.speakerEnabled);
        mSpeakerButton.setChecked(inCallControlState.speakerOn);

        // "Hold"
        // (Note "Hold" and "Swap" are never both available at
        // the same time.  That's why it's OK for them to both be in the
        // same position onscreen.)
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        mHoldButtonContainer.setVisibility(
                inCallControlState.canHold ? View.VISIBLE : View.GONE);
        if (inCallControlState.canHold) {
            // The Hold button icon and label (either "Hold" or "Unhold")
            // depend on the current Hold state.
            if (inCallControlState.onHold) {
                mHoldButton.setImageDrawable(mUnholdIcon);
                mHoldButtonLabel.setText(R.string.onscreenUnholdText);
            } else {
                mHoldButton.setImageDrawable(mHoldIcon);
                mHoldButtonLabel.setText(R.string.onscreenHoldText);
            }
        }

        // "Swap"
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        mSwapButtonContainer.setVisibility(
                inCallControlState.canSwap ? View.VISIBLE : View.GONE);

        if (inCallControlState.canSwap && inCallControlState.canHold) {
            // Uh oh, the InCallControlState thinks that Swap *and* Hold
            // should both be available.  This *should* never happen with
            // either GSM or CDMA, but if it's possible on any future
            // devices we may need to re-layout Hold and Swap so they can
            // both be visible at the same time...
            Log.w(LOG_TAG, "updateInCallControls: Hold *and* Swap enabled, but can't show both!");
        }

        // "Manage conference"
        // This button is totally hidden (rather than just disabled)
        // when the operation isn't available.
        boolean showManageConferenceTouchButton = inCallControlState.manageConferenceVisible
                && inCallControlState.manageConferenceEnabled;
        mManageConferenceButtonContainer.setVisibility(
                showManageConferenceTouchButton ? View.VISIBLE : View.GONE);

        // One final special case: if the dialpad is visible, that trumps
        // *any* of the upper corner buttons:
        if (inCallControlState.dialpadVisible) {
            mHoldButtonContainer.setVisibility(View.GONE);
            mSwapButtonContainer.setVisibility(View.GONE);
            mManageConferenceButtonContainer.setVisibility(View.GONE);
        }
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

    //
    // IncomingCallDialWidget.OnDialTriggerListener implementation
    //

    /**
     * Handles "Answer" and "Reject" actions for an incoming call.
     * We get this callback from the IncomingCallDialWidget
     * when the user triggers an action.
     *
     * To answer or reject the incoming call, we call
     * InCallScreen.handleOnscreenButtonClick() and pass one of the
     * special "virtual button" IDs:
     *   - R.id.answerButton to answer the call
     * or
     *   - R.id.rejectButton to reject the call.
     */
    public boolean onDialTrigger(View v, int whichHandle) {
        if (DBG) log("onDialTrigger(whichHandle = " + whichHandle + ")...");

        switch (whichHandle) {
            case IncomingCallDialWidget.OnDialTriggerListener.LEFT_HANDLE:
                if (DBG) log("LEFT_HANDLE: answer!");

                // Immediately hide the incoming call UI.
                mIncomingCallWidget.setVisibility(View.GONE);
                // ...and also prevent it from reappearing right away.
                // (This covers up a slow response from the radio; see updateState().)
                mLastIncomingCallActionTime = SystemClock.uptimeMillis();

                // Do the appropriate action.
                if (mInCallScreen != null) {
                    // Send this to the InCallScreen as a virtual "button click" event:
                    mInCallScreen.handleOnscreenButtonClick(R.id.answerButton);
                } else {
                    Log.e(LOG_TAG, "answer trigger: mInCallScreen is null");
                }
                break;

            case IncomingCallDialWidget.OnDialTriggerListener.RIGHT_HANDLE:
                if (DBG) log("RIGHT_HANDLE: reject!");

                // Immediately hide the incoming call UI.
                mIncomingCallWidget.setVisibility(View.GONE);
                // ...and also prevent it from reappearing right away.
                // (This covers up a slow response from the radio; see updateState().)
                mLastIncomingCallActionTime = SystemClock.uptimeMillis();

                // Do the appropriate action.
                if (mInCallScreen != null) {
                    // Send this to the InCallScreen as a virtual "button click" event:
                    mInCallScreen.handleOnscreenButtonClick(R.id.rejectButton);
                } else {
                    Log.e(LOG_TAG, "reject trigger: mInCallScreen is null");
                }
                break;

            case IncomingCallDialWidget.OnDialTriggerListener.CENTER_HANDLE:
                Log.e(LOG_TAG, "onDialTrigger: unexpected CENTER_HANDLE event");
                break;

            default:
                Log.e(LOG_TAG, "onDialTrigger: unexpected whichHandle value: " + whichHandle);
                break;
        }

        // Don't freeze the widget.
        // TODO: currently necessary to work around bug 2131875, where the RotarySelector
        // doesn't reset itself after triggering.
        return false;
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
