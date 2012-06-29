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
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.view.animation.Animation.AnimationListener;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.PopupMenu;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.Phone;
import com.android.internal.widget.multiwaveview.MultiWaveView;
import com.android.internal.widget.multiwaveview.MultiWaveView.OnTriggerListener;
import com.android.internal.telephony.CallManager;


/**
 * In-call onscreen touch UI elements, used on some platforms.
 *
 * This widget is a fullscreen overlay, drawn on top of the
 * non-touch-sensitive parts of the in-call UI (i.e. the call card).
 */
public class InCallTouchUi extends FrameLayout
        implements View.OnClickListener, OnTriggerListener,
        PopupMenu.OnMenuItemClickListener, PopupMenu.OnDismissListener {
    private static final int IN_CALL_WIDGET_TRANSITION_TIME = 250; // in ms
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
    private MultiWaveView mIncomingCallWidget;  // UI used for an incoming call
    private View mInCallControls;  // UI elements while on a regular call
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
    //
    private ViewGroup mExtraButtonRow;
    private ViewGroup mCdmaMergeButton;
    private ViewGroup mManageConferenceButton;
    private ImageButton mManageConferenceButtonImage;

    // "Audio mode" PopupMenu
    private PopupMenu mAudioModePopup;
    private boolean mAudioModePopupVisible = false;

    // Time of the most recent "answer" or "reject" action (see updateState())
    private long mLastIncomingCallActionTime;  // in SystemClock.uptimeMillis() time base

    // Parameters for the MultiWaveView "ping" animation; see triggerPing().
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

        // Inflate our contents, and add it (to ourself) as a child.
        LayoutInflater inflater = LayoutInflater.from(context);
        inflater.inflate(
                R.layout.incall_touch_ui,  // resource
                this,                      // root
                true);

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
        mIncomingCallWidget = (MultiWaveView) findViewById(R.id.incomingCallWidget);
        mIncomingCallWidget.setOnTriggerListener(this);

        // Container for the UI elements shown while on a regular call.
        mInCallControls = findViewById(R.id.inCallControls);

        // Regular (single-tap) buttons, where we listen for click events:
        // Main cluster of buttons:
        mAddButton = (ImageButton) mInCallControls.findViewById(R.id.addButton);
        mAddButton.setOnClickListener(this);
        mMergeButton = (ImageButton) mInCallControls.findViewById(R.id.mergeButton);
        mMergeButton.setOnClickListener(this);
        mEndButton = (ImageButton) mInCallControls.findViewById(R.id.endButton);
        mEndButton.setOnClickListener(this);
        mDialpadButton = (CompoundButton) mInCallControls.findViewById(R.id.dialpadButton);
        mDialpadButton.setOnClickListener(this);
        mMuteButton = (CompoundButton) mInCallControls.findViewById(R.id.muteButton);
        mMuteButton.setOnClickListener(this);
        mAudioButton = (CompoundButton) mInCallControls.findViewById(R.id.audioButton);
        mAudioButton.setOnClickListener(this);
        mHoldButton = (CompoundButton) mInCallControls.findViewById(R.id.holdButton);
        mHoldButton.setOnClickListener(this);
        mSwapButton = (ImageButton) mInCallControls.findViewById(R.id.swapButton);
        mSwapButton.setOnClickListener(this);
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
        mExtraButtonRow = (ViewGroup) mInCallControls.findViewById(R.id.extraButtonRow);
        // The two "buttons" here (mCdmaMergeButton and mManageConferenceButton)
        // are actually layouts containing an icon and a text label side-by-side.
        mCdmaMergeButton =
                (ViewGroup) mInCallControls.findViewById(R.id.cdmaMergeButton);
        mCdmaMergeButton.setOnClickListener(this);
        //
        mManageConferenceButton =
                (ViewGroup) mInCallControls.findViewById(R.id.manageConferenceButton);
        mManageConferenceButton.setOnClickListener(this);
        mManageConferenceButtonImage =
                (ImageButton) mInCallControls.findViewById(R.id.manageConferenceButtonImage);

        // Add a custom OnTouchListener to manually shrink the "hit
        // target" of some buttons.
        // (We do this for a few specific buttons which are vulnerable to
        // "false touches" because either (1) they're near the edge of the
        // screen and might be unintentionally touched while holding the
        // device in your hand, or (2) they're in the upper corners and might
        // be touched by the user's ear before the prox sensor has a chance to
        // kick in.)
        //
        // TODO (new ICS layout): not sure which buttons need this yet.
        // For now, use it only with the "End call" button (which extends all
        // the way to the edges of the screen).  But we can consider doing
        // this for "Dialpad" and/or "Add call" if those turn out to be a
        // problem too.
        //
        View.OnTouchListener smallerHitTargetTouchListener = new SmallerHitTargetTouchListener();
        mEndButton.setOnTouchListener(smallerHitTargetTouchListener);
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
        if ((ringingCall.getState() != Call.State.IDLE)
                && !fgCallState.isDialing()) {
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
            // if we attempted to answer or reject an incoming call
            // within the last 500 msec, *don't* show the incoming call
            // UI even if the phone is still in the RINGING state.
            long now = SystemClock.uptimeMillis();
            if (now < mLastIncomingCallActionTime + 500) {
                log("updateState: Too soon after last action; not drawing!");
                showIncomingCallControls = false;
            }
        } else {
            // Ok, show the regular in-call touch UI (with some exceptions):
            if (mInCallScreen.okToShowInCallTouchUi()) {
                showInCallControls = true;
            } else {
                if (DBG) log("- updateState: NOT OK to show touch UI; disabling...");
            }
        }

        // Update visibility and state of the incoming call controls or
        // the normal in-call controls.

        if (showIncomingCallControls && showInCallControls) {
            throw new IllegalStateException(
                "'Incoming' and 'in-call' touch controls visible at the same time!");
        }

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

    // View.OnClickListener implementation
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

    /**
     * Updates the enabledness and "checked" state of the buttons on the
     * "inCallControls" panel, based on the current telephony state.
     */
    void updateInCallControls(CallManager cm) {
        int phoneType = cm.getActiveFgCall().getPhone().getPhoneType();

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

        // The "extra button row" will be visible only if any of its
        // buttons need to be visible.
        boolean showExtraButtonRow = false;

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
        if (inCallControlState.canSwap && inCallControlState.canHold) {
            // Uh oh, the InCallControlState thinks that Swap *and* Hold
            // should both be available.  This *should* never happen with
            // either GSM or CDMA, but if it's possible on any future
            // devices we may need to re-layout Hold and Swap so they can
            // both be visible at the same time...
            Log.w(LOG_TAG, "updateInCallControls: Hold *and* Swap enabled, but can't show both!");
        }

        // CDMA-specific "Merge" button.
        // This button and its label are totally hidden (rather than just disabled)
        // when the operation isn't available.
        boolean showCdmaMerge =
                (phoneType == Phone.PHONE_TYPE_CDMA) && inCallControlState.canMerge;
        if (showCdmaMerge) {
            mCdmaMergeButton.setVisibility(View.VISIBLE);
            showExtraButtonRow = true;
        } else {
            mCdmaMergeButton.setVisibility(View.GONE);
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

        // "Manage conference" (used only on GSM devices)
        // This button and its label are shown or hidden together.
        if (inCallControlState.manageConferenceVisible) {
            mManageConferenceButton.setVisibility(View.VISIBLE);
            showExtraButtonRow = true;
            mManageConferenceButtonImage.setEnabled(inCallControlState.manageConferenceEnabled);
        } else {
            mManageConferenceButton.setVisibility(View.GONE);
        }

        // Finally, update the "extra button row": It's displayed above the
        // "End" button, but only if necessary.  Also, it's never displayed
        // while the dialpad is visible (since it would overlap.)
        if (showExtraButtonRow && !inCallControlState.dialpadVisible) {
            mExtraButtonRow.setVisibility(View.VISIBLE);
        } else {
            mExtraButtonRow.setVisibility(View.GONE);
        }
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
        boolean showSpeakerIcon = false;
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
                showSpeakerIcon = true;
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
            showSpeakerIcon = true;
        } else {
            if (DBG) log("- updateAudioButton: disabled...");

            // The audio button is a toggle in this state, but that's mostly
            // irrelevant since it's always disabled and unchecked.
            mAudioButton.setEnabled(false);
            mAudioButton.setChecked(false);

            // Update desired layers:
            showToggleStateIndication = true;
            showSpeakerIcon = true;
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

        layers.findDrawableByLayerId(R.id.speakerphoneItem)
                .setAlpha(showSpeakerIcon ? VISIBLE : HIDDEN);
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
     *     visible at all.  (And the MultiWaveView widget is actually
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
    // MultiWaveView.OnTriggerListener implementation
    //

    public void onGrabbed(View v, int handle) {

    }

    public void onReleased(View v, int handle) {

    }

    /**
     * Handles "Answer" and "Reject" actions for an incoming call.
     * We get this callback from the incoming call widget
     * when the user triggers an action.
     */
    public void onTrigger(View v, int whichHandle) {
        if (DBG) log("onDialTrigger(whichHandle = " + whichHandle + ")...");

        // On any action by the user, hide the widget:
        hideIncomingCallWidget();

        // ...and also prevent it from reappearing right away.
        // (This covers up a slow response from the radio for some
        // actions; see updateState().)
        mLastIncomingCallActionTime = SystemClock.uptimeMillis();

        // The InCallScreen actually implements all of these actions.
        // Each possible action from the incoming call widget corresponds
        // to an R.id value; we pass those to the InCallScreen's "button
        // click" handler (even though the UI elements aren't actually
        // buttons; see InCallScreen.handleOnscreenButtonClick().)

        if (mInCallScreen == null) {
            Log.wtf(LOG_TAG, "onTrigger(" + whichHandle
                    + ") from incoming-call widget, but null mInCallScreen!");
            return;
        }
        switch (whichHandle) {
            case ANSWER_CALL_ID:
                if (DBG) log("ANSWER_CALL_ID: answer!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallAnswer);
                break;

            case SEND_SMS_ID:
                if (DBG) log("SEND_SMS_ID!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallRespondViaSms);
                break;

            case DECLINE_CALL_ID:
                if (DBG) log("DECLINE_CALL_ID: reject!");
                mInCallScreen.handleOnscreenButtonClick(R.id.incomingCallReject);
                break;

            default:
                Log.wtf(LOG_TAG, "onDialTrigger: unexpected whichHandle value: " + whichHandle);
                break;
        }

        // Regardless of what action the user did, be sure to clear out
        // the hint text we were displaying while the user was dragging.
        mInCallScreen.updateIncomingCallWidgetHint(0, 0);
    }

    /**
     * Apply an animation to hide the incoming call widget.
     */
    private void hideIncomingCallWidget() {
        if (DBG) log("hideIncomingCallWidget()...");
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
    private void showIncomingCallWidget(Call ringingCall) {
        if (DBG) log("showIncomingCallWidget()...");

        Animation anim = mIncomingCallWidget.getAnimation();
        if (anim != null) {
            anim.reset();
            mIncomingCallWidget.clearAnimation();
        }

        // Update the MultiWaveView widget's targets based on the state of
        // the ringing call.  (Specifically, we need to disable the
        // "respond via SMS" option for certain types of calls, like SIP
        // addresses or numbers with blocked caller-id.)

        boolean allowRespondViaSms = RespondViaSmsManager.allowRespondViaSmsForCall(ringingCall);
        if (allowRespondViaSms) {
            // The MultiWaveView widget is allowed to have all 3 choices:
            // Answer, Decline, and Respond via SMS.
            mIncomingCallWidget.setTargetResources(R.array.incoming_call_widget_3way_targets);
            mIncomingCallWidget.setTargetDescriptionsResourceId(
                    R.array.incoming_call_widget_3way_target_descriptions);
            mIncomingCallWidget.setDirectionDescriptionsResourceId(
                    R.array.incoming_call_widget_3way_direction_descriptions);
        } else {
            // You only get two choices: Answer or Decline.
            mIncomingCallWidget.setTargetResources(R.array.incoming_call_widget_2way_targets);
            mIncomingCallWidget.setTargetDescriptionsResourceId(
                    R.array.incoming_call_widget_2way_target_descriptions);
            mIncomingCallWidget.setDirectionDescriptionsResourceId(
                    R.array.incoming_call_widget_2way_direction_descriptions);
        }

        // Watch out: be sure to call reset() and setVisibility() *after*
        // updating the target resources, since otherwise the MultiWaveView
        // widget will make the targets visible initially (even before you
        // touch the widget.)
        mIncomingCallWidget.reset(false);
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
                // MultiWaveView widget visible for a brief moment
                // *before* starting the ping animation.
                // This value doesn't need to be very precise.
                250 /* msec */);
    }

    /**
     * Handles state changes of the incoming-call widget.
     *
     * In previous releases (where we used a SlidingTab widget) we would
     * display an onscreen hint depending on which "handle" the user was
     * dragging.  But we now use a MultiWaveView widget, which has only
     * one handle, so for now we don't display a hint at all (see the TODO
     * comment below.)
     */
    public void onGrabbedStateChange(View v, int grabbedState) {
        if (mInCallScreen != null) {
            // Look up the hint based on which handle is currently grabbed.
            // (Note we don't simply pass grabbedState thru to the InCallScreen,
            // since *this* class is the only place that knows that the left
            // handle means "Answer" and the right handle means "Decline".)
            int hintTextResId, hintColorResId;
            switch (grabbedState) {
                case MultiWaveView.OnTriggerListener.NO_HANDLE:
                case MultiWaveView.OnTriggerListener.CENTER_HANDLE:
                    hintTextResId = 0;
                    hintColorResId = 0;
                    break;
                // TODO: MultiWaveView only has one handle. MultiWaveView could send an event
                // indicating that a snap (but not release) happened. Could be used to show text
                // when user hovers over an item.
                //        case SlidingTab.OnTriggerListener.LEFT_HANDLE:
                //            hintTextResId = R.string.slide_to_answer;
                //            hintColorResId = R.color.incall_textConnected;  // green
                //            break;
                //        case SlidingTab.OnTriggerListener.RIGHT_HANDLE:
                //            hintTextResId = R.string.slide_to_decline;
                //            hintColorResId = R.color.incall_textEnded;  // red
                //            break;
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
            // of the MultiWaveView widget.  (The intent here is to make the
            // pinging appear to be synchronized with the ringtone, although
            // that only works for non-looping ringtones.)
            triggerPing();
        }
    }

    /**
     * Runs a single "ping" animation of the MultiWaveView widget,
     * or do nothing if the MultiWaveView widget is no longer visible.
     *
     * Also, if ENABLE_PING_AUTO_REPEAT is true, schedule the next ping as
     * well (but again, only if the MultiWaveView widget is still visible.)
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
            // This shouldn't happen; the MultiWaveView widget should
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
