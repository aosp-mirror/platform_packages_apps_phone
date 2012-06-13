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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.ViewStub;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.Toast;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.multiwaveview.GlowPadView;
import com.android.internal.widget.multiwaveview.GlowPadView.OnTriggerListener;
import com.android.phone.InCallUiState.InCallScreenMode;

/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class InCallTouchUi extends FrameLayout
        implements View.OnClickListener, View.OnLongClickListener, OnTriggerListener,
        PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {
    private static final String LOG_TAG = "InCallTouchUi";
    private static final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    // Incoming call widget targets
    private static final int ANSWER_CALL_ID = 0;  // drag right
    private static final int SEND_SMS_ID = 1;  // drag up
    private static final int DECLINE_CALL_ID = 2;  // drag left

    /**
     * Reference to the InCallScreen activity that owns us.  This may be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    // Phone app instance
    private PhoneApp mApp;

    // UI containers / elements
    private GlowPadView mIncomingCallWidget;  // UI used for an incoming call
    private boolean mIncomingCallWidgetIsFadingOut;
    private boolean mIncomingCallWidgetShouldBeReset = true;

    /** UI elements while on a regular call (bottom buttons, DTMF dialpad) */
    private View mInCallControls;
    private boolean mShowInCallControlsDuringHidingAnimation;

    //
    private ImageButton mAddButton;
    private ImageButton mMergeButton;
    private ImageButton mEndButton;
    private CompoundButton mDialpadButton;
    private CompoundButton mMuteButton;
    private CompoundButton mAudioButton;
    private CompoundButton mHoldButton;
    private ImageButton mSwapButton;
    private View mHoldSwapSpacer;

    // "Extra button row"
    private ViewStub mExtraButtonRow;
    private ViewGroup mCdmaMergeButton;
    private ViewGroup mManageConferenceButton;
    private ImageButton mManageConferenceButtonImage;

    // "Audio mode" PopupMenu
    private PopupMenu mAudioModePopup;
    private boolean mAudioModePopupVisible = false;

    // Time of the most recent "answer" or "reject" action (see updateState())
    private long mLastIncomingCallActionTime;  // in SystemClock.uptimeMillis() time base

    // Parameters for the GlowPadView "ping" animation; see triggerPing().
    private static final boolean ENABLE_PING_ON_RING_EVENTS = false;
    private static final boolean ENABLE_PING_AUTO_REPEAT = true;
    private static final long PING_AUTO_REPEAT_DELAY_MSEC = 1200;

    private static final int INCOMING_CALL_WIDGET_PING = 101;
    private Handler mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                // If the InCallScreen activity isn't around any more,
                // there's no point doing anything here.
                if (mInCallScreen == null) return;

                switch (msg.what) {
                    case INCOMING_CALL_WIDGET_PING:
                        if (DBG) log("INCOMING_CALL_WIDGET_PING...");
                        triggerPing();
                        break;
                    default:
                        Log.wtf(LOG_TAG, "mHandler: unexpected message: " + msg);
                        break;
                }
            }
        };

    public InCallTouchUi(Context context, AttributeSet attrs) {
        super(context, attrs);

        if (DBG) log("InCallTouchUi constructor...");
        if (DBG) log("- this = " + this);
        if (DBG) log("- context " + context + ", attrs " + attrs);
        mApp = PhoneApp.getInstance();
    }

    void setInCallScreenInstance(InCallScreen inCallScreen) {
        mInCallScreen = inCallScreen;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        if (DBG) log("InCallTouchUi onFinishInflate(this = " + this + ")...");

        // Look up the various UI elements.

        // "Drag-to-answer" widget for incoming calls.
        mIncomingCallWidget = (GlowPadView) findViewById(R.id.incomingCallWidget);
        mIncomingCallWidget.setOnTriggerListener(this);

        // Container for the UI elements shown while on a regular call.
        mInCallControls = findViewById(R.id.inCallControls);

        // Regular (single-tap) buttons, where we listen for click events:
        // Main cluster of buttons:
        mAddButton = (ImageButton) mInCallControls.findViewById(R.id.addButton);
        mAddButton.setOnClickListener(this);
        mAddButton.setOnLongClickListener(this);
        mMergeButton = (ImageButton) mInCallControls.findViewById(R.id.mergeButton);
        mMergeButton.setOnClickListener(this);
        mMergeButton.setOnLongClickListener(this);
        mEndButton = (ImageButton) mInCallControls.findViewById(R.id.endButton);
        mEndButton.setOnClickListener(this);
        mDialpadButton = (CompoundButton) mInCallControls.findViewById(R.id.dialpadButton);
        mDialpadButton.setOnClickListener(this);
        mDialpadButton.setOnLongClickListener(this);
        mMuteButton = (CompoundButton) mInCallControls.findViewById(R.id.muteButton);
        mMuteButton.setOnClickListener(this);
        mMuteButton.setOnLongClickListener(this);
        mAudioButton = (CompoundButton) mInCallControls.findViewById(R.id.audioButton);
        mAudioButton.setOnClickListener(this);
        mAudioButton.setOnLongClickListener(this);
        mHoldButton = (CompoundButton) mInCallControls.findViewById(R.id.holdButton);
        mHoldButton.setOnClickListener(this);
        mHoldButton.setOnLongClickListener(this);
        mSwapButton = (ImageButton) mInCallControls.findViewById(R.id.swapButton);
        mSwapButton.setOnClickListener(this);
        mSwapButton.setOnLongClickListener(this);
        mHoldSwapSpacer = mInCallControls.findViewById(R.id.holdSwapSpacer);

        // TODO: Back when these buttons had text labels, we changed
        // the label of mSwapButton for CDMA as follows:
        //
        //      if (PhoneApp.getPhone().getPhoneType() == Phone.PHONE_TYPE_CDMA) {
        //          // In CDMA we use a generalized text - "Manage call", as behavior on selecting
        //          // this option depends entirely on what the current call state is.
        //          mSwapButtonLabel.setText(R.string.onscreenManageCallsText);
        //      } else {
        //          mSwapButtonLabel.setText(R.string.onscreenSwapCallsText);
        //      }
        //
        // If this is still needed, consider having a special icon for this
        // button in CDMA.

        // Buttons shown on the "extra button row", only visible in certain (rare) states.
        mExtraButtonRow = (ViewStub) mInCallControls.findViewById(R.id.extraButtonRow);

        // Add a custom OnTouchListener to manually shrink the "hit target".
        View.OnTouchListener smallerHitTargetTouchListener = new SmallerHitTargetTouchListener();
        mEndButton.setOnTouchListener(smallerHitTargetTouchListener);
    }

    /**
     * Updates the visibility and/or state of our UI elements, based on
     * the current state of the phone.
     */
    /* package */ void updateState(CallManager cm) {
        if (mInCallScreen == null) {
            log("- updateState: mInCallScreen has been destroyed; bailing out...");
            return;
        }

        Phone.State state = cm.getState();  // IDLE, RINGING, or OFFHOOK
        if (DBG) log("updateState: current state = " + state);

        boolean showIncomingCallControls = false;
        boolean showInCallControls = false;

        final Call ringingCall = cm.getFirstActiveRingingCall();
        final Call.State fgCallState = cm.getActiveFgCallState();

        // If the FG call is dialing/alerting, we should display for that call
        // and ignore the ringing call. This case happens when the telephony
        // layer rejects the ringing call while the FG call is dialing/alerting,
        // but the incoming call *does* briefly exist in the DISCONNECTING or
        // DISCONNECTED state.
        if ((ringingCall.getState() != Call.State.IDLE) && !fgCallState.isDialing()) {
            // A phone call is ringing *or* call waiting.

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
            // if we get an updateState() call immediately after answering/rejecting a call
            // (via onTrigger()), *don't* show the incoming call
            // UI even if the phone is still in the RINGING state.
            // This covers up a slow response from the radio for some actions.
            // To detect that situation, we are using "500 msec" heuristics.
            //
            // Watch out: we should *not* rely on this behavior when "instant text response" action
            // has been chosen. See also onTrigger() for why.
            long now = SystemClock.uptimeMillis();
            if (now < mLastIncomingCallActionTime + 500) {
                log("updateState: Too soon after last action; not drawing!");
                showIncomingCallControls = false;
            }
        } else {
            // Ok, show the regular in-call touch UI (with some exceptions):
            if (okToShowInCallControls()) {
                showInCallControls = true;
            } else {
                if (DBG) log("- updateState: NOT OK to show touch UI; disabling...");
            }
        }

        // In usual cases we don't allow showing both incoming call controls and in-call controls.
        //
        // There's one exception: if this call is during fading-out animation for the incoming
        // call controls, we need to show both for smoother transition.
        if (showIncomingCallControls && showInCallControls) {
            throw new IllegalStateException(
                "'Incoming' and 'in-call' touch controls visible at the same time!");
        }
        if (mShowInCallControlsDuringHidingAnimation) {
            if (DBG) {
                log("- updateState: FORCE showing in-call controls during incoming call widget"
                        + " being hidden with animation");
            }
            showInCallControls = true;
        }

        // Update visibility and state of the incoming call controls or
        // the normal in-call controls.

        if (showInCallControls) {
            if (DBG) log("- updateState: showing in-call controls...");
            updateInCallControls(cm);
            mInCallControls.setVisibility(View.VISIBLE);
        } else {
            if (DBG) log("- updateState: HIDING in-call controls...");
            mInCallControls.setVisibility(View.GONE);
        }

        if (showIncomingCallControls) {
            if (DBG) log("- updateState: showing incoming call widget...");
            showIncomingCallWidget(ringingCall);

            // On devices with a system bar (soft buttons at the bottom of
            // the screen), disable navigation while the incoming-call UI
            // is up.
            // This prevents false touches (e.g. on the "Recents" button)
            // from interfering with the incoming call UI, like if you
            // accidentally touch the system bar while pulling the phone
            // out of your pocket.
            mApp.notificationMgr.statusBarHelper.enableSystemBarNavigation(false);
        } else {
            if (DBG) log("- updateState: HIDING incoming call widget...");
            hideIncomingCallWidget();

            // The system bar is allowed to work normally in regular
            // in-call states.
            mApp.notificationMgr.statusBarHelper.enableSystemBarNavigation(true);
        }

        // Dismiss the "Audio mode" PopupMenu if necessary.
        //
        // The "Audio mode" popup is only relevant in call states that support
        // in-call audio, namely when the phone is OFFHOOK (not RINGING), *and*
        // the foreground call is either ALERTING (where you can hear the other
        // end ringing) or ACTIVE (when the call is actually connected.)  In any
        // state *other* than these, the popup should not be visible.

        if ((state == Phone.State.OFFHOOK)
            && (fgCallState == Call.State.ALERTING || fgCallState == Call.State.ACTIVE)) {
            // The audio mode popup is allowed to be visible in this state.
            // So if it's up, leave it alone.
        } else {
            // The Audio mode popup isn't relevant in this state, so make sure
            // it's not visible.
            dismissAudioModePopup();  // safe even if not active
        }
    }

    private boolean okToShowInCallControls() {
        // Note that this method is concerned only with the internal state
        // of the InCallScreen.  (The InCallTouchUi widget has separate
        // logic to make sure it's OK to display the touch UI given the
        // current telephony state, and that it's allowed on the current
        // device in the first place.)

        // The touch UI is available in the following InCallScreenModes:
        // - NORMAL (obviously)
        // - CALL_ENDED (which is intended to look mostly the same as
        //               a normal in-call state, even though the in-call
        //               buttons are mostly disabled)
        // and is hidden in any of the other modes, like MANAGE_CONFERENCE
        // or one of the OTA modes (which use totally different UIs.)

        return ((mApp.inCallUiState.inCallScreenMode == InCallScreenMode.NORMAL)
                || (mApp.inCallUiState.inCallScreenMode == InCallScreenMode.CALL_ENDED));
    }

    @Override
    public void onClick(View view) {
        int id = view.getId();
        if (DBG) log("onClick(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.addButton:
            case R.id.mergeButton:
            case R.id.endButton:
            case R.id.dialpadButton:
            case R.id.muteButton:
            case R.id.holdButton:
            case R.id.swapButton:
            case R.id.cdmaMergeButton:
            case R.id.manageConferenceButton:
                // Clicks on the regular onscreen buttons get forwarded
                // straight to the InCallScreen.
                mInCallScreen.handleOnscreenButtonClick(id);
                break;

            case R.id.audioButton:
                handleAudioButtonClick();
                break;

            default:
                Log.w(LOG_TAG, "onClick: unexpected click: View " + view + ", id " + id);
                break;
        }
    }

    @Override
    public boolean onLongClick(View view) {
        final int id = view.getId();
        if (DBG) log("onLongClick(View " + view + ", id " + id + ")...");

        switch (id) {
            case R.id.addButton:
            case R.id.mergeButton:
            case R.id.dialpadButton:
            case R.id.muteButton:
            case R.id.holdButton:
            case R.id.swapButton:
            case R.id.audioButton: {
                final CharSequence description = view.getContentDescription();
                if (!TextUtils.isEmpty(description)) {
                    // Show description as ActionBar's menu buttons do.
                    // See also ActionMenuItemView#onLongClick() for the original implementation.
                    final Toast cheatSheet =
                            Toast.makeText(view.getContext(), description, Toast.LENGTH_SHORT);
                    cheatSheet.setGravity(
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, view.getHeight());
                    cheatSheet.show();
                }
                return true;
            }
            default:
                Log.w(LOG_TAG, "onLongClick() with unexpected View " + view + ". Ignoring it.");
                break;
        }
        return false;
    }
    /**
     * Updates the enabledness and "checked" state of the buttons on the
     * "inCallControls" panel, based on the current telephony state.
     */
    private void updateInCallControls(CallManager cm) {
        int phoneType = cm.getActiveFgCall().getPhone().getPhoneType();

        // Note we do NOT need to worry here about cases where the entire
        // in-call touch UI is disabled, like during an OTA call or if the
        // dtmf dialpad is up.  (That's handled by updateState(), which
        // calls okToShowInCallControls().)
        //
        // If we get here, it *is* OK to show the in-call touch UI, so we
        // now need to update the enabledness and/or "checked" state of
        // each individual button.
        //

        // The InCallControlState object tells us the enabledness and/or
        // state of the various onscreen buttons:
        InCallControlState inCallControlState = mInCallScreen.getUpdatedInCallControlState();

        if (DBG) {
            log("updateInCallControls()...");
            inCallControlState.dumpState();
        }

        // "Add" / "Merge":
        // These two buttons occupy the same space onscreen, so at any
        // given point exactly one of them must be VISIBLE and the other
        // must be GONE.
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

        // "End call"
        mEndButton.setEnabled(inCallControlState.canEndCall);

        // "Dialpad": Enabled only when it's OK to use the dialpad in the
        // first place.
        mDialpadButton.setEnabled(inCallControlState.dialpadEnabled);
        mDialpadButton.setChecked(inCallControlState.dialpadVisible);

        // "Mute"
        mMuteButton.setEnabled(inCallControlState.canMute);
        mMuteButton.setChecked(inCallControlState.muteIndicatorOn);

        // "Audio"
        updateAudioButton(inCallControlState);

        // "Hold" / "Swap":
        // These two buttons occupy the same space onscreen, so at any
        // given point exactly one of them must be VISIBLE and the other
        // must be GONE.
        if (inCallControlState.canHold) {
            mHoldButton.setVisibility(View.VISIBLE);
            mHoldButton.setEnabled(true);
            mHoldButton.setChecked(inCallControlState.onHold);
            mSwapButton.setVisibility(View.GONE);
        } else if (inCallControlState.canSwap) {
            mSwapButton.setVisibility(View.VISIBLE);
            mSwapButton.setEnabled(true);
            mHoldButton.setVisibility(View.GONE);
        } else {
            // Neither "Hold" nor "Swap" is available.  This can happen for two
            // reasons:
            //   (1) this is a transient state on a device that *can*
            //       normally hold or swap, or
            //   (2) this device just doesn't have the concept of hold/swap.
            //
            // In case (1), show the "Hold" button in a disabled state.  In case
            // (2), remove the button entirely.  (This means that the button row
            // will only have 4 buttons on some devices.)

            if (inCallControlState.supportsHold) {
                mHoldButton.setVisibility(View.VISIBLE);
                mHoldButton.setEnabled(false);
                mHoldButton.setChecked(false);
                mSwapButton.setVisibility(View.GONE);
                mHoldSwapSpacer.setVisibility(View.VISIBLE);
            } else {
                mHoldButton.setVisibility(View.GONE);
                mSwapButton.setVisibility(View.GONE);
                mHoldSwapSpacer.setVisibility(View.GONE);
            }
        }
        mInCallScreen.updateButtonStateOutsideInCallTouchUi();
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

        // Finally, update the "extra button row": It's displayed above the
        // "End" button, but only if necessary.  Also, it's never displayed
        // while the dialpad is visible (since it would overlap.)
        //
        // The row contains two buttons:
        //
        // - "Manage conference" (used only on GSM devices)
        // - "Merge" button (used only on CDMA devices)
        //
        // Note that mExtraButtonRow is ViewStub, which will be inflated for the first time when
        // any of its buttons becomes visible.
        final boolean showCdmaMerge =
                (phoneType == Phone.PHONE_TYPE_CDMA) && inCallControlState.canMerge;
        final boolean showExtraButtonRow =
                showCdmaMerge || inCallControlState.manageConferenceVisible;
        if (showExtraButtonRow && !inCallControlState.dialpadVisible) {
            // This will require the ViewStub inflate itself.
            mExtraButtonRow.setVisibility(View.VISIBLE);

            // Need to set up mCdmaMergeButton and mManageConferenceButton if this is the first
            // time they're visible.
            if (mCdmaMergeButton == null) {
                setupExtraButtons();
            }
            mCdmaMergeButton.setVisibility(showCdmaMerge ? View.VISIBLE : View.GONE);
            if (inCallControlState.manageConferenceVisible) {
                mManageConferenceButton.setVisibility(View.VISIBLE);
                mManageConferenceButtonImage.setEnabled(inCallControlState.manageConferenceEnabled);
            } else {
                mManageConferenceButton.setVisibility(View.GONE);
            }
        } else {
            mExtraButtonRow.setVisibility(View.GONE);
        }

        if (DBG) {
            log("At the end of updateInCallControls().");
            dumpBottomButtonState();
        }
    }

    /**
     * Set up the buttons that are part of the "extra button row"
     */
    private void setupExtraButtons() {
        // The two "buttons" here (mCdmaMergeButton and mManageConferenceButton)
        // are actually layouts containing an icon and a text label side-by-side.
        mCdmaMergeButton = (ViewGroup) mInCallControls.findViewById(R.id.cdmaMergeButton);
        if (mCdmaMergeButton == null) {
            Log.wtf(LOG_TAG, "CDMA Merge button is null even after ViewStub being inflated.");
            return;
        }
        mCdmaMergeButton.setOnClickListener(this);

        mManageConferenceButton =
                (ViewGroup) mInCallControls.findViewById(R.id.manageConferenceButton);
        mManageConferenceButton.setOnClickListener(this);
        mManageConferenceButtonImage =
                (ImageButton) mInCallControls.findViewById(R.id.manageConferenceButtonImage);
    }

    private void dumpBottomButtonState() {
        log(" - dialpad: " + getButtonState(mDialpadButton));
        log(" - speaker: " + getButtonState(mAudioButton));
        log(" - mute: " + getButtonState(mMuteButton));
        log(" - hold: " + getButtonState(mHoldButton));
        log(" - swap: " + getButtonState(mSwapButton));
        log(" - add: " + getButtonState(mAddButton));
        log(" - merge: " + getButtonState(mMergeButton));
        log(" - cdmaMerge: " + getButtonState(mCdmaMergeButton));
        log(" - swap: " + getButtonState(mSwapButton));
        log(" - manageConferenceButton: " + getButtonState(mManageConferenceButton));
    }

    private static String getButtonState(View view) {
        if (view == null) {
            return "(null)";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("visibility: " + (view.getVisibility() == View.VISIBLE ? "VISIBLE"
                : view.getVisibility() == View.INVISIBLE ? "INVISIBLE" : "GONE"));
        if (view instanceof ImageButton) {
            builder.append(", enabled: " + ((ImageButton) view).isEnabled());
        } else if (view instanceof CompoundButton) {
            builder.append(", enabled: " + ((CompoundButton) view).isEnabled());
            builder.append(", checked: " + ((CompoundButton) view).isChecked());
        }
        return builder.toString();
    }

    /**
     * Updates the onscreen "Audio mode" button based on the current state.
     *
     * - If bluetooth is available, this button's function is to bring up the
     *   "Audio mode" popup (which provides a 3-way choice between earpiece /
     *   speaker / bluetooth).  So it should look like a regular action button,
     *   but should also have the small "more_indicator" triangle that indicates
     *   that a menu will pop up.
     *
     * - If speaker (but not bluetooth) is available, this button should look like
     *   a regular toggle button (and indicate the current speaker state.)
     *
     * - If even speaker isn't available, disable the button entirely.
     */
    private void updateAudioButton(InCallControlState inCallControlState) {
        if (DBG) log("updateAudioButton()...");

        // The various layers of artwork for this button come from
        // btn_compound_audio.xml.  Keep track of which layers we want to be
        // visible:
        //
        // - This selector shows the blue bar below the button icon when
        //   this button is a toggle *and* it's currently "checked".
        boolean showToggleStateIndication = false;
        //
        // - This is visible if the popup menu is enabled:
        boolean showMoreIndicator = false;
        //
        // - Foreground icons for the button.  Exactly one of these is enabled:
        boolean showSpeakerOnIcon = false;
        boolean showSpeakerOffIcon = false;
        boolean showHandsetIcon = false;
        boolean showBluetoothIcon = false;

        if (inCallControlState.bluetoothEnabled) {
            if (DBG) log("- updateAudioButton: 'popup menu action button' mode...");

            mAudioButton.setEnabled(true);

            // The audio button is NOT a toggle in this state.  (And its
            // setChecked() state is irrelevant since we completely hide the
            // btn_compound_background layer anyway.)

            // Update desired layers:
            showMoreIndicator = true;
            if (inCallControlState.bluetoothIndicatorOn) {
                showBluetoothIcon = true;
            } else if (inCallControlState.speakerOn) {
                showSpeakerOnIcon = true;
            } else {
                showHandsetIcon = true;
                // TODO: if a wired headset is plugged in, that takes precedence
                // over the handset earpiece.  If so, maybe we should show some
                // sort of "wired headset" icon here instead of the "handset
                // earpiece" icon.  (Still need an asset for that, though.)
            }
        } else if (inCallControlState.speakerEnabled) {
            if (DBG) log("- updateAudioButton: 'speaker toggle' mode...");

            mAudioButton.setEnabled(true);

            // The audio button *is* a toggle in this state, and indicates the
            // current state of the speakerphone.
            mAudioButton.setChecked(inCallControlState.speakerOn);

            // Update desired layers:
            showToggleStateIndication = true;

            showSpeakerOnIcon = inCallControlState.speakerOn;
            showSpeakerOffIcon = !inCallControlState.speakerOn;
        } else {
            if (DBG) log("- updateAudioButton: disabled...");

            // The audio button is a toggle in this state, but that's mostly
            // irrelevant since it's always disabled and unchecked.
            mAudioButton.setEnabled(false);
            mAudioButton.setChecked(false);

            // Update desired layers:
            showToggleStateIndication = true;
            showSpeakerOffIcon = true;
        }

        // Finally, update the drawable layers (see btn_compound_audio.xml).

        // Constants used below with Drawable.setAlpha():
        final int HIDDEN = 0;
        final int VISIBLE = 255;

        LayerDrawable layers = (LayerDrawable) mAudioButton.getBackground();
        if (DBG) log("- 'layers' drawable: " + layers);

        layers.findDrawableByLayerId(R.id.compoundBackgroundItem)
                .setAlpha(showToggleStateIndication ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.moreIndicatorItem)
                .setAlpha(showMoreIndicator ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.bluetoothItem)
                .setAlpha(showBluetoothIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.handsetItem)
                .setAlpha(showHandsetIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.speakerphoneOnItem)
                .setAlpha(showSpeakerOnIcon ? VISIBLE : HIDDEN);

        layers.findDrawableByLayerId(R.id.speakerphoneOffItem)
                .setAlpha(showSpeakerOffIcon ? VISIBLE : HIDDEN);
    }

    /**
     * Handles a click on the "Audio mode" button.
     * - If bluetooth is available, bring up the "Audio mode" popup
     *   (which provides a 3-way choice between earpiece / speaker / bluetooth).
     * - If bluetooth is *not* available, just toggle between earpiece and
     *   speaker, with no popup at all.
     */
    private void handleAudioButtonClick() {
        InCallControlState inCallControlState = mInCallScreen.getUpdatedInCallControlState();
        if (inCallControlState.bluetoothEnabled) {
            if (DBG) log("- handleAudioButtonClick: 'popup menu' mode...");
            showAudioModePopup();
        } else {
            if (DBG) log("- handleAudioButtonClick: 'speaker toggle' mode...");
            mInCallScreen.toggleSpeaker();
        }
    }

    /**
     * Brings up the "Audio mode" popup.
     */
    private void showAudioModePopup() {
        if (DBG) log("showAudioModePopup()...");

        mAudioModePopup = new PopupMenu(mInCallScreen /* context */,
                                        mAudioButton /* anchorView */);
        mAudioModePopup.getMenuInflater().inflate(R.menu.incall_audio_mode_menu,
                                                  mAudioModePopup.getMenu());
        mAudioModePopup.setOnMenuItemClickListener(this);
        mAudioModePopup.setOnDismissListener(this);

        // Update the enabled/disabledness of menu items based on the
        // current call state.
        InCallControlState inCallControlState = mInCallScreen.getUpdatedInCallControlState();

        Menu menu = mAudioModePopup.getMenu();

        // TODO: Still need to have the "currently active" audio mode come
        // up pre-selected (or focused?) with a blue highlight.  Still
        // need exact visual design, and possibly framework support for this.
        // See comments below for the exact logic.

        MenuItem speakerItem = menu.findItem(R.id.audio_mode_speaker);
        speakerItem.setEnabled(inCallControlState.speakerEnabled);
        // TODO: Show speakerItem as initially "selected" if
        // inCallControlState.speakerOn is true.

        // We display *either* "earpiece" or "wired headset", never both,
        // depending on whether a wired headset is physically plugged in.
        MenuItem earpieceItem = menu.findItem(R.id.audio_mode_earpiece);
        MenuItem wiredHeadsetItem = menu.findItem(R.id.audio_mode_wired_headset);
        final boolean usingHeadset = mApp.isHeadsetPlugged();
        earpieceItem.setVisible(!usingHeadset);
        earpieceItem.setEnabled(!usingHeadset);
        wiredHeadsetItem.setVisible(usingHeadset);
        wiredHeadsetItem.setEnabled(usingHeadset);
        // TODO: Show the above item (either earpieceItem or wiredHeadsetItem)
        // as initially "selected" if inCallControlState.speakerOn and
        // inCallControlState.bluetoothIndicatorOn are both false.

        MenuItem bluetoothItem = menu.findItem(R.id.audio_mode_bluetooth);
        bluetoothItem.setEnabled(inCallControlState.bluetoothEnabled);
        // TODO: Show bluetoothItem as initially "selected" if
        // inCallControlState.bluetoothIndicatorOn is true.

        mAudioModePopup.show();

        // Unfortunately we need to manually keep track of the popup menu's
        // visiblity, since PopupMenu doesn't have an isShowing() method like
        // Dialogs do.
        mAudioModePopupVisible = true;
    }

    /**
     * Dismisses the "Audio mode" popup if it's visible.
     *
     * This is safe to call even if the popup is already dismissed, or even if
     * you never called showAudioModePopup() in the first place.
     */
    public void dismissAudioModePopup() {
        if (mAudioModePopup != null) {
            mAudioModePopup.dismiss();  // safe even if already dismissed
            mAudioModePopup = null;
            mAudioModePopupVisible = false;
        }
    }

    /**
     * Refreshes the "Audio mode" popup if it's visible.  This is useful
     * (for example) when a wired headset is plugged or unplugged,
     * since we need to switch back and forth between the "earpiece"
     * and "wired headset" items.
     *
     * This is safe to call even if the popup is already dismissed, or even if
     * you never called showAudioModePopup() in the first place.
     */
    public void refreshAudioModePopup() {
        if (mAudioModePopup != null && mAudioModePopupVisible) {
            // Dismiss the previous one
            mAudioModePopup.dismiss();  // safe even if already dismissed
            // And bring up a fresh PopupMenu
            showAudioModePopup();
        }
    }

    // PopupMenu.OnMenuItemClickListener implementation; see showAudioModePopup()
    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (DBG) log("- onMenuItemClick: " + item);
        if (DBG) log("  id: " + item.getItemId());
        if (DBG) log("  title: '" + item.getTitle() + "'");

        if (mInCallScreen == null) {
            Log.w(LOG_TAG, "onMenuItemClick(" + item + "), but null mInCallScreen!");
            return true;
        }

        switch (item.getItemId()) {
            case R.id.audio_mode_speaker:
                mInCallScreen.switchInCallAudio(InCallScreen.InCallAudioMode.SPEAKER);
                break;
            case R.id.audio_mode_earpiece:
            case R.id.audio_mode_wired_headset:
                // InCallAudioMode.EARPIECE means either the handset earpiece,
                // or the wired headset (if connected.)
                mInCallScreen.switchInCallAudio(InCallScreen.InCallAudioMode.EARPIECE);
                break;
            case R.id.audio_mode_bluetooth:
                mInCallScreen.switchInCallAudio(InCallScreen.InCallAudioMode.BLUETOOTH);
                break;
            default:
                Log.wtf(LOG_TAG,
                        "onMenuItemClick:  unexpected View ID " + item.getItemId()
                        + " (MenuItem = '" + item + "')");
                break;
        }
        return true;
    }

    // PopupMenu.OnDismissListener implementation; see showAudioModePopup().
    // This gets called when the PopupMenu gets dismissed for *any* reason, like
    // the user tapping outside its bounds, or pressing Back, or selecting one
    // of the menu items.
    @Override
    public void onDismiss(PopupMenu menu) {
        if (DBG) log("- onDismiss: " + menu);
        mAudioModePopupVisible = false;
    }

    /**
     * @return the amount of vertical space (in pixels) that needs to be
     * reserved for the button cluster at the bottom of the screen.
     * (The CallCard uses this measurement to determine how big
     * the main "contact photo" area can be.)
     *
     * NOTE that this returns the "canonical height" of the main in-call
     * button cluster, which may not match the amount of vertical space
     * actually used.  Specifically:
     *
     *   - If an incoming call is ringing, the button cluster isn't
     *     visible at all.  (And the GlowPadView widget is actually
     *     much taller than the button cluster.)
     *
     *   - If the InCallTouchUi widget's "extra button row" is visible
     *     (in some rare phone states) the button cluster will actually
     *     be slightly taller than the "canonical height".
     *
     * In either of these cases, we allow the bottom edge of the contact
     * photo to be covered up by whatever UI is actually onscreen.
     */
    public int getTouchUiHeight() {
        // Add up the vertical space consumed by the various rows of buttons.
        int height = 0;

        // - The main row of buttons:
        height += (int) getResources().getDimension(R.dimen.in_call_button_height);

        // - The End button:
        height += (int) getResources().getDimension(R.dimen.in_call_end_button_height);

        // - Note we *don't* consider the InCallTouchUi widget's "extra
        //   button row" here.

        //- And an extra bit of margin:
        height += (int) getResources().getDimension(R.dimen.in_call_touch_ui_upper_margin);

        return height;
    }


    //
    // GlowPadView.OnTriggerListener implementation
    //

    @Override
    public void onGrabbed(View v, int handle) {

    }

    @Override
    public void onReleased(View v, int handle) {

    }

    /**
     * Handles "Answer" and "Reject" actions for an incoming call.
     * We get this callback from the incoming call widget
     * when the user triggers an action.
     */
    @Override
    public void onTrigger(View view, int whichHandle) {
        if (DBG) log("onTrigger(whichHandle = " + whichHandle + ")...");

        if (mInCallScreen == null) {
            Log.wtf(LOG_TAG, "onTrigger(" + whichHandle
                    + ") from incoming-call widget, but null mInCallScreen!");
            return;
        }

        // The InCallScreen actually implements all of these actions.
        // Each possible action from the incoming call widget corresponds
        // to an R.id value; we pass those to the InCallScreen's "button
        // click" handler (even though the UI elements aren't actually
        // buttons; see InCallScreen.handleOnscreenButtonClick().)

        mShowInCallControlsDuringHidingAnimation = false;
        switch (whichHandle) {
            case ANSWER_CALL_ID:
                if (DBG) log("ANSWER_CALL_ID: answer!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallAnswer);
                mShowInCallControlsDuringHidingAnimation = true;

                // ...and also prevent it from reappearing right away.
                // (This covers up a slow response from the radio for some
                // actions; see updateState().)
                mLastIncomingCallActionTime = SystemClock.uptimeMillis();
                break;

            case SEND_SMS_ID:
                if (DBG) log("SEND_SMS_ID!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallRespondViaSms);

                // Watch out: mLastIncomingCallActionTime should not be updated for this case.
                //
                // The variable is originally for avoiding a problem caused by delayed phone state
                // update; RINGING state may remain just after answering/declining an incoming
                // call, so we need to wait a bit (500ms) until we get the effective phone state.
                // For this case, we shouldn't rely on that hack.
                //
                // When the user selects this case, there are two possibilities, neither of which
                // should rely on the hack.
                //
                // 1. The first possibility is that, the device eventually sends one of canned
                //    responses per the user's "send" request, and reject the call after sending it.
                //    At that moment the code introducing the canned responses should handle the
                //    case separately.
                //
                // 2. The second possibility is that, the device will show incoming call widget
                //    again per the user's "cancel" request, where the incoming call will still
                //    remain. At that moment the incoming call will keep its RINGING state.
                //    The remaining phone state should never be ignored by the hack for
                //    answering/declining calls because the RINGING state is legitimate. If we
                //    use the hack for answer/decline cases, the user loses the incoming call
                //    widget, until further screen update occurs afterward, which often results in
                //    missed calls.
                break;

            case DECLINE_CALL_ID:
                if (DBG) log("DECLINE_CALL_ID: reject!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallReject);

                // Same as "answer" case.
                mLastIncomingCallActionTime = SystemClock.uptimeMillis();
                break;

            default:
                Log.wtf(LOG_TAG, "onDialTrigger: unexpected whichHandle value: " + whichHandle);
                break;
        }

        // On any action by the user, hide the widget.
        //
        // If requested above (i.e. if mShowInCallControlsDuringHidingAnimation is set to true),
        // in-call controls will start being shown too.
        hideIncomingCallWidget();

        // Regardless of what action the user did, be sure to clear out
        // the hint text we were displaying while the user was dragging.
        mInCallScreen.updateIncomingCallWidgetHint(0, 0);
    }

    public void onFinishFinalAnimation() {
        // Not used
    }

    /**
     * Apply an animation to hide the incoming call widget.
     */
    private void hideIncomingCallWidget() {
        // if (DBG) log("hideIncomingCallWidget()...");
        if (mIncomingCallWidget.getVisibility() != View.VISIBLE
                || mIncomingCallWidgetIsFadingOut) {
            // Widget is already hidden or in the process of being hidden
            return;
        }

        // TODO: remove this once we fixed issue 6603655
        log("hideIncomingCallWidget()");

        // Hide the incoming call screen with a transition
        mIncomingCallWidgetIsFadingOut = true;
        ViewPropertyAnimator animator = mIncomingCallWidget.animate();
        animator.cancel();
        animator.setDuration(AnimationUtils.ANIMATION_DURATION);
        animator.setListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                if (mShowInCallControlsDuringHidingAnimation) {
                    if (DBG) log("IncomingCallWidget's hiding animation started");
                    updateInCallControls(mApp.mCM);
                    mInCallControls.setVisibility(View.VISIBLE);
                }
            }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (DBG) log("IncomingCallWidget's hiding animation ended");
                mIncomingCallWidget.setAlpha(1);
                mIncomingCallWidget.setVisibility(View.GONE);
                mIncomingCallWidget.animate().setListener(null);
                mShowInCallControlsDuringHidingAnimation = false;
                mIncomingCallWidgetIsFadingOut = false;
                mIncomingCallWidgetShouldBeReset = true;
            }
            @Override
            public void onAnimationCancel(Animator animation) {
                mIncomingCallWidget.animate().setListener(null);
                mShowInCallControlsDuringHidingAnimation = false;
                mIncomingCallWidgetIsFadingOut = false;
                mIncomingCallWidgetShouldBeReset = true;

                // Note: the code which reset this animation should be responsible for
                // alpha and visibility.
            }
        });
        animator.alpha(0f);
    }

    /**
     * Shows the incoming call widget and cancels any animation that may be fading it out.
     */
    private void showIncomingCallWidget(Call ringingCall) {
        // if (DBG) log("showIncomingCallWidget()...");

        // TODO: remove this once we fixed issue 6603655
        // TODO: wouldn't be ok to suppress this whole request if the widget is already VISIBLE
        //       and we don't need to reset it?
        log("showIncomingCallWidget(). widget visibility: " + mIncomingCallWidget.getVisibility());

        ViewPropertyAnimator animator = mIncomingCallWidget.animate();
        if (animator != null) {
            animator.cancel();
        }
        mIncomingCallWidget.setAlpha(1.0f);

        // Update the GlowPadView widget's targets based on the state of
        // the ringing call.  (Specifically, we need to disable the
        // "respond via SMS" option for certain types of calls, like SIP
        // addresses or numbers with blocked caller-id.)
        final boolean allowRespondViaSms =
                RespondViaSmsManager.allowRespondViaSmsForCall(mInCallScreen, ringingCall);
        final int targetResourceId = allowRespondViaSms
                ? R.array.incoming_call_widget_3way_targets
                : R.array.incoming_call_widget_2way_targets;
        // The widget should be updated only when appropriate; if the previous choice can be reused
        // for this incoming call, we'll just keep using it. Otherwise we'll see UI glitch
        // everytime when this method is called during a single incoming call.
        if (targetResourceId != mIncomingCallWidget.getTargetResourceId()) {
            if (allowRespondViaSms) {
                // The GlowPadView widget is allowed to have all 3 choices:
                // Answer, Decline, and Respond via SMS.
                mIncomingCallWidget.setTargetResources(targetResourceId);
                mIncomingCallWidget.setTargetDescriptionsResourceId(
                        R.array.incoming_call_widget_3way_target_descriptions);
                mIncomingCallWidget.setDirectionDescriptionsResourceId(
                        R.array.incoming_call_widget_3way_direction_descriptions);
            } else {
                // You only get two choices: Answer or Decline.
                mIncomingCallWidget.setTargetResources(targetResourceId);
                mIncomingCallWidget.setTargetDescriptionsResourceId(
                        R.array.incoming_call_widget_2way_target_descriptions);
                mIncomingCallWidget.setDirectionDescriptionsResourceId(
                        R.array.incoming_call_widget_2way_direction_descriptions);
            }

            // This will be used right after this block.
            mIncomingCallWidgetShouldBeReset = true;
        }
        if (mIncomingCallWidgetShouldBeReset) {
            // Watch out: be sure to call reset() and setVisibility() *after*
            // updating the target resources, since otherwise the GlowPadView
            // widget will make the targets visible initially (even before you
            // touch the widget.)
            mIncomingCallWidget.reset(false);
            mIncomingCallWidgetShouldBeReset = false;
        }

        mIncomingCallWidget.setVisibility(View.VISIBLE);

        // Finally, manually trigger a "ping" animation.
        //
        // Normally, the ping animation is triggered by RING events from
        // the telephony layer (see onIncomingRing().)  But that *doesn't*
        // happen for the very first RING event of an incoming call, since
        // the incoming-call UI hasn't been set up yet at that point!
        //
        // So trigger an explicit ping() here, to force the animation to
        // run when the widget first appears.
        //
        mHandler.removeMessages(INCOMING_CALL_WIDGET_PING);
        mHandler.sendEmptyMessageDelayed(
                INCOMING_CALL_WIDGET_PING,
                // Visual polish: add a small delay here, to make the
                // GlowPadView widget visible for a brief moment
                // *before* starting the ping animation.
                // This value doesn't need to be very precise.
                250 /* msec */);
    }

    /**
     * Handles state changes of the incoming-call widget.
     *
     * In previous releases (where we used a SlidingTab widget) we would
     * display an onscreen hint depending on which "handle" the user was
     * dragging.  But we now use a GlowPadView widget, which has only
     * one handle, so for now we don't display a hint at all (see the TODO
     * comment below.)
     */
    @Override
    public void onGrabbedStateChange(View v, int grabbedState) {
        if (mInCallScreen != null) {
            // Look up the hint based on which handle is currently grabbed.
            // (Note we don't simply pass grabbedState thru to the InCallScreen,
            // since *this* class is the only place that knows that the left
            // handle means "Answer" and the right handle means "Decline".)
            int hintTextResId, hintColorResId;
            switch (grabbedState) {
                case GlowPadView.OnTriggerListener.NO_HANDLE:
                case GlowPadView.OnTriggerListener.CENTER_HANDLE:
                    hintTextResId = 0;
                    hintColorResId = 0;
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
            mInCallScreen.updateIncomingCallWidgetHint(hintTextResId, hintColorResId);
        }
    }

    /**
     * Handles an incoming RING event from the telephony layer.
     */
    public void onIncomingRing() {
        if (ENABLE_PING_ON_RING_EVENTS) {
            // Each RING from the telephony layer triggers a "ping" animation
            // of the GlowPadView widget.  (The intent here is to make the
            // pinging appear to be synchronized with the ringtone, although
            // that only works for non-looping ringtones.)
            triggerPing();
        }
    }

    /**
     * Runs a single "ping" animation of the GlowPadView widget,
     * or do nothing if the GlowPadView widget is no longer visible.
     *
     * Also, if ENABLE_PING_AUTO_REPEAT is true, schedule the next ping as
     * well (but again, only if the GlowPadView widget is still visible.)
     */
    public void triggerPing() {
        if (DBG) log("triggerPing: mIncomingCallWidget = " + mIncomingCallWidget);

        if (!mInCallScreen.isForegroundActivity()) {
            // InCallScreen has been dismissed; no need to run a ping *or*
            // schedule another one.
            log("- triggerPing: InCallScreen no longer in foreground; ignoring...");
            return;
        }

        if (mIncomingCallWidget == null) {
            // This shouldn't happen; the GlowPadView widget should
            // always be present in our layout file.
            Log.w(LOG_TAG, "- triggerPing: null mIncomingCallWidget!");
            return;
        }

        if (DBG) log("- triggerPing: mIncomingCallWidget visibility = "
                     + mIncomingCallWidget.getVisibility());

        if (mIncomingCallWidget.getVisibility() != View.VISIBLE) {
            if (DBG) log("- triggerPing: mIncomingCallWidget no longer visible; ignoring...");
            return;
        }

        // Ok, run a ping (and schedule the next one too, if desired...)

        mIncomingCallWidget.ping();

        if (ENABLE_PING_AUTO_REPEAT) {
            // Schedule the next ping.  (ENABLE_PING_AUTO_REPEAT mode
            // allows the ping animation to repeat much faster than in
            // the ENABLE_PING_ON_RING_EVENTS case, since telephony RING
            // events come fairly slowly (about 3 seconds apart.))

            // No need to check here if the call is still ringing, by
            // the way, since we hide mIncomingCallWidget as soon as the
            // ringing stops, or if the user answers.  (And at that
            // point, any future triggerPing() call will be a no-op.)

            // TODO: Rather than having a separate timer here, maybe try
            // having these pings synchronized with the vibrator (see
            // VibratorThread in Ringer.java; we'd just need to get
            // events routed from there to here, probably via the
            // PhoneApp instance.)  (But watch out: make sure pings
            // still work even if the Vibrate setting is turned off!)

            mHandler.sendEmptyMessageDelayed(INCOMING_CALL_WIDGET_PING,
                                             PING_AUTO_REPEAT_DELAY_MSEC);
        }
    }

    // Debugging / testing code

    private void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
