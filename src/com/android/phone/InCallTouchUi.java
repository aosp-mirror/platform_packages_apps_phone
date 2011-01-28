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
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.SlidingTab;
import com.android.internal.telephony.CallManager;


/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class InCallTouchUi extends FrameLayout
        implements View.OnClickListener, SlidingTab.OnTriggerListener {
    private static final int IN_CALL_WIDGET_TRANSITION_TIME = 250; // in ms
    private static final String LOG_TAG = "InCallTouchUi";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    // Phone app instance
    private PhoneApp mApplication;

    // UI containers / elements
    private SlidingTab mIncomingCallWidget;  // UI used for an incoming call
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
    private TextView mSwapButtonLabel;
    private View mCdmaMergeButtonContainer;
    private ImageButton mCdmaMergeButton;
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
        mIncomingCallWidget = (SlidingTab) findViewById(R.id.incomingCallWidget);
        mIncomingCallWidget.setLeftTabResources(
                R.drawable.ic_jog_dial_answer,
                com.android.internal.R.drawable.jog_tab_target_green,
                com.android.internal.R.drawable.jog_tab_bar_left_answer,
                com.android.internal.R.drawable.jog_tab_left_answer
                );
        mIncomingCallWidget.setRightTabResources(
                R.drawable.ic_jog_dial_decline,
                com.android.internal.R.drawable.jog_tab_target_red,
                com.android.internal.R.drawable.jog_tab_bar_right_decline,
                com.android.internal.R.drawable.jog_tab_right_decline
                );

        // For now, we only need to show two states: answer and decline.
        mIncomingCallWidget.setLeftHintText(R.string.slide_to_answer_hint);
        mIncomingCallWidget.setRightHintText(R.string.slide_to_decline_hint);

        mIncomingCallWidget.setOnTriggerListener(this);

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
        mSwapButtonLabel = (TextView) mInCallControls.findViewById(R.id.swapButtonLabel);
        if (PhoneApp.getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            // In CDMA we use a generalized text - "Manage call", as behavior on selecting
            // this option depends entirely on what the current call state is.
            mSwapButtonLabel.setText(R.string.onscreenManageCallsText);
        } else {
            mSwapButtonLabel.setText(R.string.onscreenSwapCallsText);
        }
        //
        mCdmaMergeButtonContainer = mInCallControls.findViewById(R.id.cdmaMergeButtonContainer);
        mCdmaMergeButton = (ImageButton) mInCallControls.findViewById(R.id.cdmaMergeButton);
        mCdmaMergeButton.setOnClickListener(this);

        // Add a custom OnTouchListener to manually shrink the "hit
        // target" of some buttons.
        // (We do this for a few specific buttons which are vulnerable to
        // "false touches" because either (1) they're near the edge of the
        // screen and might be unintentionally touched while holding the
        // device in your hand, or (2) they're in the upper corners and might
        // be touched by the user's ear before the prox sensor has a chance to
        // kick in.)
        View.OnTouchListener smallerHitTargetTouchListener = new SmallerHitTargetTouchListener();
        mAddButton.setOnTouchListener(smallerHitTargetTouchListener);
        mMergeButton.setOnTouchListener(smallerHitTargetTouchListener);
        mDialpadButton.setOnTouchListener(smallerHitTargetTouchListener);
        mBluetoothButton.setOnTouchListener(smallerHitTargetTouchListener);
        mSpeakerButton.setOnTouchListener(smallerHitTargetTouchListener);
        mHoldButton.setOnTouchListener(smallerHitTargetTouchListener);
        mSwapButton.setOnTouchListener(smallerHitTargetTouchListener);
        mCdmaMergeButton.setOnTouchListener(smallerHitTargetTouchListener);
        mSpeakerButton.setOnTouchListener(smallerHitTargetTouchListener);

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
    void updateState(CallManager cm) {
        if (mInCallScreen == null) {
            log("- updateState: mInCallScreen has been destroyed; bailing out...");
            return;
        }

        Phone.State state = cm.getState();  // IDLE, RINGING, or OFFHOOK
        if (DBG) log("- updateState: CallManager state is " + state);

        boolean showIncomingCallControls = false;
        boolean showInCallControls = false;

        final Call ringingCall = cm.getFirstActiveRingingCall();
        // If the FG call is dialing/alerting, we should display for that call
        // and ignore the ringing call. This case happens when the telephony
        // layer rejects the ringing call while the FG call is dialing/alerting,
        // but the incoming call *does* briefly exist in the DISCONNECTING or
        // DISCONNECTED state.
        if ((ringingCall.getState() != Call.State.IDLE)
                && !cm.getActiveFgCallState().isDialing()) {
            // A phone call is ringing *or* call waiting.
            if (mAllowIncomingCallTouchUi) {
                // Watch out: even if the phone state is RINGING, it's
                // possible for the ringing call to be in the DISCONNECTING
                // state.  (This typically happens immediately after the user
                // rejects an incoming call, and in that case we *don't* show
                // the incoming call controls.)
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
            // TODO change the phone to CallManager
            updateInCallControls(cm.getActiveFgCall().getPhone());
        }

        if (showIncomingCallControls && showInCallControls) {
            throw new IllegalStateException(
                "'Incoming' and 'in-call' touch controls visible at the same time!");
        }

        if (showIncomingCallControls) {
            showIncomingCallWidget();
        } else {
            hideIncomingCallWidget();
        }

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
            case R.id.cdmaMergeButton:
                // Clicks on the regular onscreen buttons get forwarded
                // straight to the InCallScreen.
                mInCallScreen.handleOnscreenButtonClick(id);
                break;

            default:
                Log.w(LOG_TAG, "onClick: unexpected click: View " + view + ", id " + id);
                break;
        }
    }

    /**
     * Updates the enabledness and "checked" state of the buttons on the
     * "inCallControls" panel, based on the current telephony state.
     */
    void updateInCallControls(Phone phone) {
        int phoneType = phone.getPhoneType();
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
            if (phoneType == Phone.PHONE_TYPE_CDMA) {
                // In CDMA "Add" option is always given to the user and the
                // "Merge" option is provided as a button on the top left corner of the screen,
                // we always set the mMergeButton to GONE
                mMergeButton.setVisibility(View.GONE);
            } else if ((phoneType == Phone.PHONE_TYPE_GSM)
                    || (phoneType == Phone.PHONE_TYPE_SIP)) {
                mMergeButton.setVisibility(View.VISIBLE);
                mMergeButton.setEnabled(true);
                mAddButton.setVisibility(View.GONE);
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
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
            if ((phoneType == Phone.PHONE_TYPE_GSM)
                    || (phoneType == Phone.PHONE_TYPE_SIP)) {
                // Uh oh, the InCallControlState thinks that "Add" *and* "Merge"
                // should both be available right now.  This *should* never
                // happen with GSM, but if it's possible on any
                // future devices we may need to re-layout Add and Merge so
                // they can both be visible at the same time...
                Log.w(LOG_TAG, "updateInCallControls: Add *and* Merge enabled," +
                        " but can't show both!");
            } else if (phoneType == Phone.PHONE_TYPE_CDMA) {
                // In CDMA "Add" option is always given to the user and the hence
                // in this case both "Add" and "Merge" options would be available to user
                if (DBG) log("updateInCallControls: CDMA: Add and Merge both enabled");
            } else {
                throw new IllegalStateException("Unexpected phone type: " + phoneType);
            }
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

        if (phone.getPhoneType() == Phone.PHONE_TYPE_CDMA) {
            // "Merge"
            // This button is totally hidden (rather than just disabled)
            // when the operation isn't available.
            mCdmaMergeButtonContainer.setVisibility(
                    inCallControlState.canMerge ? View.VISIBLE : View.GONE);
        }

        if (inCallControlState.canSwap && inCallControlState.canHold) {
            // Uh oh, the InCallControlState thinks that Swap *and* Hold
            // should both be available.  This *should* never happen with
            // either GSM or CDMA, but if it's possible on any future
            // devices we may need to re-layout Hold and Swap so they can
            // both be visible at the same time...
            Log.w(LOG_TAG, "updateInCallControls: Hold *and* Swap enabled, but can't show both!");
        }

        if (phoneType == Phone.PHONE_TYPE_CDMA) {
            if (inCallControlState.canSwap && inCallControlState.canMerge) {
                // Uh oh, the InCallControlState thinks that Swap *and* Merge
                // should both be available.  This *should* never happen with
                // CDMA, but if it's possible on any future
                // devices we may need to re-layout Merge and Swap so they can
                // both be visible at the same time...
                Log.w(LOG_TAG, "updateInCallControls: Merge *and* Swap" +
                        "enabled, but can't show both!");
            }
        }

        // One final special case: if the dialpad is visible, that trumps
        // *any* of the upper corner buttons:
        if (inCallControlState.dialpadVisible) {
            mHoldButtonContainer.setVisibility(View.GONE);
            mSwapButtonContainer.setVisibility(View.GONE);
            mCdmaMergeButtonContainer.setVisibility(View.GONE);
        }
    }

    //
    // InCallScreen API
    //

    /**
     * @return true if the onscreen touch UI is enabled (for regular
     * "ongoing call" states) on the current device.
     */
    /* package */ boolean isTouchUiEnabled() {
        return mAllowInCallTouchUi;
    }

    /**
     * @return true if the onscreen touch UI is enabled for
     * the "incoming call" state on the current device.
     */
    /* package */ boolean isIncomingCallTouchUiEnabled() {
        return mAllowIncomingCallTouchUi;
    }

    //
    // SlidingTab.OnTriggerListener implementation
    //

    /**
     * Handles "Answer" and "Reject" actions for an incoming call.
     * We get this callback from the SlidingTab
     * when the user triggers an action.
     *
     * To answer or reject the incoming call, we call
     * InCallScreen.handleOnscreenButtonClick() and pass one of the
     * special "virtual button" IDs:
     *   - R.id.answerButton to answer the call
     * or
     *   - R.id.rejectButton to reject the call.
     */
    public void onTrigger(View v, int whichHandle) {
        log("onDialTrigger(whichHandle = " + whichHandle + ")...");

        switch (whichHandle) {
            case SlidingTab.OnTriggerListener.LEFT_HANDLE:
                if (DBG) log("LEFT_HANDLE: answer!");

                hideIncomingCallWidget();

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

            case SlidingTab.OnTriggerListener.RIGHT_HANDLE:
                if (DBG) log("RIGHT_HANDLE: reject!");

                hideIncomingCallWidget();

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

            default:
                Log.e(LOG_TAG, "onDialTrigger: unexpected whichHandle value: " + whichHandle);
                break;
        }

        // Regardless of what action the user did, be sure to clear out
        // the hint text we were displaying while the user was dragging.
        mInCallScreen.updateSlidingTabHint(0, 0);
    }

    /**
     * Apply an animation to hide the incoming call widget.
     */
    private void hideIncomingCallWidget() {
        if (mIncomingCallWidget.getVisibility() != View.VISIBLE
                || mIncomingCallWidget.getAnimation() != null) {
            // Widget is already hidden or in the process of being hidden
            return;
        }
        // Hide the incoming call screen with a transition
        AlphaAnimation anim = new AlphaAnimation(1.0f, 0.0f);
        anim.setDuration(IN_CALL_WIDGET_TRANSITION_TIME);
        anim.setAnimationListener(new AnimationListener() {

            public void onAnimationStart(Animation animation) {

            }

            public void onAnimationRepeat(Animation animation) {

            }

            public void onAnimationEnd(Animation animation) {
                // hide the incoming call UI.
                mIncomingCallWidget.clearAnimation();
                mIncomingCallWidget.setVisibility(View.GONE);
            }
        });
        mIncomingCallWidget.startAnimation(anim);
    }

    /**
     * Shows the incoming call widget and cancels any animation that may be fading it out.
     */
    private void showIncomingCallWidget() {
        Animation anim = mIncomingCallWidget.getAnimation();
        if (anim != null) {
            anim.reset();
            mIncomingCallWidget.clearAnimation();
        }
        mIncomingCallWidget.reset(false);
        mIncomingCallWidget.setVisibility(View.VISIBLE);
    }

    /**
     * Handles state changes of the SlidingTabSelector widget.  While the user
     * is dragging one of the handles, we display an onscreen hint; see
     * CallCard.getRotateWidgetHint().
     */
    public void onGrabbedStateChange(View v, int grabbedState) {
        if (mInCallScreen != null) {
            // Look up the hint based on which handle is currently grabbed.
            // (Note we don't simply pass grabbedState thru to the InCallScreen,
            // since *this* class is the only place that knows that the left
            // handle means "Answer" and the right handle means "Decline".)
            int hintTextResId, hintColorResId;
            switch (grabbedState) {
                case SlidingTab.OnTriggerListener.NO_HANDLE:
                    hintTextResId = 0;
                    hintColorResId = 0;
                    break;
                case SlidingTab.OnTriggerListener.LEFT_HANDLE:
                    // TODO: Use different variants of "Slide to answer" in some cases
                    // depending on the phone state, like slide_to_answer_and_hold
                    // for a call waiting call, or slide_to_answer_and_end_active or
                    // slide_to_answer_and_end_onhold for the 2-lines-in-use case.
                    // (Note these are GSM-only cases, though.)
                    hintTextResId = R.string.slide_to_answer;
                    hintColorResId = R.color.incall_textConnected;  // green
                    break;
                case SlidingTab.OnTriggerListener.RIGHT_HANDLE:
                    hintTextResId = R.string.slide_to_decline;
                    hintColorResId = R.color.incall_textEnded;  // red
                    break;
                default:
                    Log.e(LOG_TAG, "onGrabbedStateChange: unexpected grabbedState: "
                          + grabbedState);
                    hintTextResId = 0;
                    hintColorResId = 0;
                    break;
            }

            // Tell the InCallScreen to update the CallCard and force the
            // screen to redraw.
            mInCallScreen.updateSlidingTabHint(hintTextResId, hintColorResId);
        }
    }


    /**
     * OnTouchListener used to shrink the "hit target" of some onscreen
     * buttons.
     */
    class SmallerHitTargetTouchListener implements View.OnTouchListener {
        /**
         * Width of the allowable "hit target" as a percentage of
         * the total width of this button.
         */
        private static final int HIT_TARGET_PERCENT_X = 50;

        /**
         * Height of the allowable "hit target" as a percentage of
         * the total height of this button.
         *
         * This is larger than HIT_TARGET_PERCENT_X because some of
         * the onscreen buttons are wide but not very tall and we don't
         * want to make the vertical hit target *too* small.
         */
        private static final int HIT_TARGET_PERCENT_Y = 80;

        // Size (percentage-wise) of the "edge" area that's *not* touch-sensitive.
        private static final int X_EDGE = (100 - HIT_TARGET_PERCENT_X) / 2;
        private static final int Y_EDGE = (100 - HIT_TARGET_PERCENT_Y) / 2;
        // Min/max values (percentage-wise) of the touch-sensitive hit target.
        private static final int X_HIT_MIN = X_EDGE;
        private static final int X_HIT_MAX = 100 - X_EDGE;
        private static final int Y_HIT_MIN = Y_EDGE;
        private static final int Y_HIT_MAX = 100 - Y_EDGE;

        // True if the most recent DOWN event was a "hit".
        boolean mDownEventHit;

        /**
         * Called when a touch event is dispatched to a view. This allows listeners to
         * get a chance to respond before the target view.
         *
         * @return True if the listener has consumed the event, false otherwise.
         *         (In other words, we return true when the touch is *outside*
         *         the "smaller hit target", which will prevent the actual
         *         button from handling these events.)
         */
        public boolean onTouch(View v, MotionEvent event) {
            // if (DBG) log("SmallerHitTargetTouchListener: " + v + ", event " + event);

            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Note that event.getX() and event.getY() are already
                // translated into the View's coordinates.  (In other words,
                // "0,0" is a touch on the upper-left-most corner of the view.)
                int touchX = (int) event.getX();
                int touchY = (int) event.getY();

                int viewWidth = v.getWidth();
                int viewHeight = v.getHeight();

                // Touch location as a percentage of the total button width or height.
                int touchXPercent = (int) ((float) (touchX * 100) / (float) viewWidth);
                int touchYPercent = (int) ((float) (touchY * 100) / (float) viewHeight);
                // if (DBG) log("- percentage:  x = " + touchXPercent + ",  y = " + touchYPercent);

                // TODO: user research: add event logging here of the actual
                // hit location (and button ID), and enable it for dogfooders
                // for a few days.  That'll give us a good idea of how close
                // to the center of the button(s) most touch events are, to
                // help us fine-tune the HIT_TARGET_PERCENT_* constants.

                if (touchXPercent < X_HIT_MIN || touchXPercent > X_HIT_MAX
                        || touchYPercent < Y_HIT_MIN || touchYPercent > Y_HIT_MAX) {
                    // Missed!
                    // if (DBG) log("  -> MISSED!");
                    mDownEventHit = false;
                    return true;  // Consume this event; don't let the button see it
                } else {
                    // Hit!
                    // if (DBG) log("  -> HIT!");
                    mDownEventHit = true;
                    return false;  // Let this event through to the actual button
                }
            } else {
                // This is a MOVE, UP or CANCEL event.
                //
                // We only do the "smaller hit target" check on DOWN events.
                // For the subsequent MOVE/UP/CANCEL events, we let them
                // through to the actual button IFF the previous DOWN event
                // got through to the actual button (i.e. it was a "hit".)
                return !mDownEventHit;
            }
        }
    }


    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
