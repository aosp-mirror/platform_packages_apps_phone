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

import android.content.Context;
import android.provider.Checkin;
import com.android.internal.telephony.Phone;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;

/**
 * Helper class to manage the sliding "call card" on the InCallScreen.
 */
class SlidingCardManager implements ViewTreeObserver.OnGlobalLayoutListener {
    private static final String LOG_TAG = "PHONE/SlidingCardManager";
    private static final boolean DBG = false;

    //
    // In the new "simplified" sliding card UI, the card is always in
    // one of the following states:
    //
    // CARD AT TOP OF SCREEN:
    //   - "In call" states
    //     (including "Dialing", "On hold", "Conference call", "Busy")
    //
    // CARD AT BOTTOM OF SCREEN:
    //   - Incoming call (either normal *or* "Call waiting")
    //   - "Call ended" state:
    //     - This is a non-interactive state. All touch events are ignored,
    //       and the card never moves.)
    //     - This state is only ever used while the Phone is totally idle;
    //       it's visible for a couple of seconds after a call ends
    //       (see InCallScreen.delayedCleanupAfterDisconnect().)
    //
    // Sliding the card UP *always* answers the incoming call.
    // (We put the ongoing call on hold if there's already one line in
    // use, or hang up the ongoing call if both lines are in use.)
    //
    // Sliding the card DOWN *always* ends all current calls.
    //
    // There's no longer any concept of "locking" or "unlocking" the
    // touchscreen by sliding the card.
    //

    /**
     * Reference to the InCallScreen activity that owns us.  This will be
     * null if we haven't been initialized yet *or* after the InCallScreen
     * activity has been destroyed.
     */
    private InCallScreen mInCallScreen;

    private Phone mPhone;

    // Touch mode states, and state of the sliding card
    private boolean mPopupRequested = false;
    private boolean mSlideInProgress = false;
    private int mTouchDownY;  // Y-coordinate of the DOWN event that started the slide
    private boolean mPartialSlideEventSent;

    // mCardAtTop is true if the card should currently be at the top
    // of the screen, or false if it should be at the bottom.
    private boolean mCardAtTop;
    private boolean mCallEndedState;
    private int mCardPreferredX, mCardPreferredY;

    // UI elements:

    private PopupWindow mPopup;
    private CallCard mCallCard;

    // Slide hints
    private ViewGroup mSlideUp;
    private TextView mSlideUpHint;
    private ViewGroup mSlideDown;
    private TextView mSlideDownHint;

    // Some UI elements from the main InCallScreen that we use.
    private ViewGroup mMainFrame;

    // Temporary int array used with various getLocationInWindow() calls.
    private int[] mTempLocation = new int[2];  // Use this only from the main thread!

    // A few hardwired constants used in our screen layout.
    // TODO: These should all really come from resources, but that's
    // nontrivial; see the javadoc for the ConfigurationHelper class.
    // For now, let's at least keep them all here in one place
    // rather than sprinkled througout this file.
    //
    // In PORTRAIT mode, mMainFrame is 308 x 419
    // In LANDSCAPE mode, mMainFrame is 468 x 285
    //
    static final int POPUP_WINDOW_WIDTH_LANDSCAPE = 468;  // same as mMainFrame.getWidth()
    static final int POPUP_WINDOW_HEIGHT_LANDSCAPE = 153;
    //
    static final int POPUP_WINDOW_WIDTH_PORTRAIT = 308;  // same as mMainFrame.getWidth()
    static final int POPUP_WINDOW_HEIGHT_PORTRAIT = 223;
    //
    // Slide hints (portrait mode values come directly from incall_screen.xml):
    static final int SLIDE_UP_HINT_TOP_LANDSCAPE = 88;
    static final int SLIDE_DOWN_HINT_TOP_LANDSCAPE = 160;


    SlidingCardManager() {
    }

    /**
     * Initializes the internal state of the SlidingCardManager.
     *
     * @param phone the Phone app's Phone instance
     * @param inCallScreen the InCallScreen activity
     * @param mainFrame the InCallScreen's main frame containing the in-call UI elements
     */
    /* package */ void init(Phone phone,
                            InCallScreen inCallScreen,
                            ViewGroup mainFrame) {
        if (DBG) log("init()...");
        mPhone = phone;
        mInCallScreen = inCallScreen;
        mMainFrame = mainFrame;

        // Slide hints
        mSlideUp = (ViewGroup) mInCallScreen.findViewById(R.id.slideUp);
        mSlideUpHint = (TextView) mInCallScreen.findViewById(R.id.slideUpHint);
        mSlideDown = (ViewGroup) mInCallScreen.findViewById(R.id.slideDown);
        mSlideDownHint = (TextView) mInCallScreen.findViewById(R.id.slideDownHint);

        // The PopupWindow for the sliding CallCard, which "slides" up and down.
        mPopup = initPopup();

        mCallCard = (CallCard) mPopup.getContentView().findViewById(R.id.callCard);
        if (DBG) log("  - mCallCard = " + mCallCard);
        mCallCard.reset();
        mCallCard.setSlidingCardManager(this);
    }

    /* package */ void setPhone(Phone phone) {
        mPhone = phone;
    }

    /**
     * Null out our reference to the InCallScreen activity.
     * This indicates that the InCallScreen activity has been destroyed.
     */
    void clearInCallScreenReference() {
        mInCallScreen = null;
    }

    //
    // PopupWindow management
    //

    /**
     * Creates and initializes the PopupWindow.
     */
    private PopupWindow initPopup() {
        if (DBG) log("initPopup()...");

        PopupWindow popup = new PopupWindow(mInCallScreen);
        View popupView = mInCallScreen.getLayoutInflater().inflate(R.layout.call_card_popup,
                                                                   null);
        //ignore cheek press on the pop up window
        popup.setIgnoreCheekPress();
        popup.setContentView(popupView);

        // Explicitly null out the popup's background,
        // otherwise it'll get set to the "default" value
        // R.styleable.PopupWindow_popupBackground.
        // (The actual "sliding card" background comes from
        // the top-level View in call_card.xml.)
        popup.setBackgroundDrawable(null);

        return popup;
    }

    /**
     * Updates the PopupWindow's size and makes it visible onscreen.
     *
     * This needs to be done *after* our main UI gets laid out and
     * attached to its Window, because (1) we base the popup's size on the
     * post-layout size of elements in our main View hierarchy, and (2) we
     * need to have a valid window token for our call to
     * mPopup.showAtLocation().
     */
    /* package */ void showPopup() {
        if (DBG) log("showPopup()...");
        mPopupRequested = true;

        // On startup, the very first call to this method comes right
        // after the InCallScreen's main view hierarchy has been attached
        // to its window and laid out; see WindowAttachNotifierView.onAttachedToWindow()
        // and our own onGlobalLayout() method below.

        if (mInCallScreen == null) {
            Log.e(LOG_TAG, "showPopup() called, but the InCallScreen activity is gone!");
            return;
        }

        if (mInCallScreen.isFinishing()) {
            if (DBG) log("showPopup: Activity finishing! Bailing out...");
            return;
        }

        // Also, bail out if we don't have a valid window token yet (which
        // is required for our call to mPopup.showAtLocation() below.)

        // It's normal for this to happen once on startup (thanks to the
        // setInCallScreenMode() call we make immediately when we enter
        // the InCallScreen.)
        if (DBG) log("showPopup(): checking window token: " + mMainFrame.getWindowToken());
        if (mMainFrame.getWindowToken() == null) {
            if (DBG) log("showPopup: no window token yet! Bailing out...");
            // It's OK to bail out and do nothing here, since we'll get
            // *another* showPopup() call as soon as our main View hierarchy
            // first gets attached to a Window (thanks to the
            // WindowAttachNotifierView used by the InCallScreen class.)
            return;
        }

        mPopup.setWidth(InCallScreen.ConfigurationHelper.sPopupWidth);
        mPopup.setHeight(InCallScreen.ConfigurationHelper.sPopupHeight);

        if (DBG) log("showPopup: mMainFrame dimensions: "
                     + mMainFrame.getWidth() + " x " + mMainFrame.getHeight());
        if (DBG) log("showPopup: mPopup dimensions: "
                     + mPopup.getWidth() + " x " + mPopup.getHeight());

        updateCardPreferredPosition();  // Sets mCardAtTop, mCallEndedState,
                                        // mCardPreferredX, and mCardPreferredY
        updateCardSlideHints();

        if (DBG) log("- showPopup: about to showAtLocation(); token = "
                     + mMainFrame.getWindowToken());
        if (DBG) log("- showPopup: Initially positioning popup at "
                     + mCardPreferredX + ", " + mCardPreferredY);
        try {
            // This is a no-op if mPopup is already showing.
            mPopup.showAtLocation(mMainFrame, Gravity.TOP | Gravity.LEFT,
                                  mCardPreferredX, mCardPreferredY);
            if (DBG) log("- showPopup: mPopup.showAtLocation() succeeded.");
        } catch (WindowManager.BadTokenException e) {
            // This should never happen; we've already checked that
            // mMainFrame.getWindowToken() is non-null, and we're also
            // careful never to call this method *after* the InCallScreen
            // activity has been destroyed.
            Log.e(LOG_TAG, "BadTokenException from mPopup.showAtLocation()", e);
        }
    }

    /* package */ void dismissPopup() {
        if (DBG) log("dismissPopup()...");
        mPopupRequested = false;
        if (mPopup != null) mPopup.dismiss();  // This is a no-op if mPopup is not showing
    }

    /**
     * Update the "permanent" position (ie. the actual layout params)
     * of the sliding card based on the current call state.
     *
     * This method sets mCardAtTop, mCallEndedState, mCardPreferredX, and mCardPreferredY.
     * It also repositions the PopupWindow if it's showing.
     *
     * Note that *while sliding* we manually reposition the card
     * on every motion event that comes in.  The x/y position we set here
     * determines where the card should be while *not* sliding.
     *
     * TODO: If the card position changes for some reason *other*
     * than user action (i.e. as a result of an onPhoneStateChanged()
     * callback), we should smoothly animate the position change!
     * (For example, if you're in a call and the other end hangs up, the
     * card should switch to "Call ended" mode and smoothly animate to the
     * bottom position.)
     */
    /* package */ void updateCardPreferredPosition() {
        if (DBG) log("updateCardPreferredPosition()...");
        //if (DBG) log("- card's LayoutParams: " + mCallCard.getLayoutParams());

        // Bail out if our View hierarchy isn't attached to a Window yet
        // (since the mMainFrame.getLocationOnScreen() call below
        // will fail.)
        if (mMainFrame.getWindowToken() == null) {
            if (DBG) log("updateCardPreferredPosition: View hierarchy unattached; bailing...");
            return;
        }

        if (mMainFrame.getHeight() == 0) {
            // The code here needs to know the sizes and positions of some
            // views in the mMainFrame view hierarchy, so you're only
            // allowed to call this method *after* the whole in-call UI
            // has been measured and laid out.
            // (This is why we defer calling showPopup() until an
            // onGlobalLayout() call comes in.)
            throw new IllegalStateException(
                    "updateCardPreferredPosition: main frame not measured yet");
        }

        // Given the current state of the Phone and the UI, decide whether
        // the card should be at the TOP or BOTTOM of the screen right now.

        // Compute the possible coordinates onscreen for the popup.
        // TODO: this block is duplicated below; use a single helper method instead.
        mMainFrame.getLocationInWindow(mTempLocation);
        final int mainFrameX = mTempLocation[0];
        final int mainFrameY = mTempLocation[1];
        if (DBG) log("- mMainFrame loc in window: " + mainFrameX + ", " + mainFrameY);

        // In the "top" position the CallCard is aligned exactly with the
        // top edge of the main frame.
        final int popupTopPosY = mainFrameY;

        // And in the "bottom" position, the bottom of the CallCard is
        // aligned exactly with the bottom of the main frame.
        final int popupBottomPosY = mainFrameY + mMainFrame.getHeight() - mPopup.getHeight();

        if (mPhone.getState() != Phone.State.IDLE) {
            // When the phone is in use, the position of the card is
            // determined solely by whether an incoming call is ringing or
            // not.
            final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
            mCardAtTop = !hasRingingCall;
            mCallEndedState = false;
        } else {
            // Phone is completely idle!  Display the CALL ENDED state
            // with the card at the bottom of the screen.
            mCardAtTop = false;
            mCallEndedState = true;
        }
        mCardPreferredX = mainFrameX;
        mCardPreferredY = mCardAtTop ? popupTopPosY : popupBottomPosY;

        if (DBG) log("==> Setting card preferred position (mCardAtTop = "
                     + mCardAtTop + ") to: "
                     + mCardPreferredX + ", " + mCardPreferredY);

        // This is a no-op if the PopupWindow isn't showing.
        mPopup.update(mCardPreferredX, mCardPreferredY, -1, -1);
    }


    //
    // "Slide hints" management
    //

    /**
     * Update the "slide hints" (displayed onscreen either above or below
     * the slidable CallCard) based on the current state.
     */
    /* package */ void updateCardSlideHints() {
        if (DBG) log("updateCardSlideHints()...");

        if (mSlideInProgress) {
            // If currently sliding, do nothing.  (Leave the hints in whatever
            // state they were before we started the slide.)
            if (DBG) log("--> SLIDING: do nothing...");

            // Or, to have slide hints always hidden while sliding:
            //setSlideHints(0, 0);
            return;
        }

        // Update slide hints based on the current Phone state.

        final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
        final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();

        int slideUpHint = 0;
        int slideDownHint = 0;
        if (hasRingingCall) {
            // Card is at the bottom of the screen.  The "slide up" hint
            // is some variation of "Slide up to answer".
            if (hasActiveCall && hasHoldingCall) {
                // Incoming call with both lines in use; need special wording
                // to warn the user that the ongoing call will be ended.
                slideUpHint = R.string.slide_hint_up_to_answer_end_ongoing;
            } else {
                // Normal hint for incoming calls (with zero or one lines in use).
                slideUpHint = R.string.slide_hint_up_to_answer;
            }
        } else {
            // No ringing call, so we're either in-call or dialing.
            // The Card is at the top of the screen, and the "slide down" hint
            // is some variation of "Slide down to end call".

            if (hasActiveCall && hasHoldingCall) {
                slideDownHint = R.string.slide_hint_down_to_end_calls;
            } else {
                slideDownHint = R.string.slide_hint_down_to_end_call;
            }
        }
        setSlideHints(slideUpHint, slideDownHint);
    }

    /**
     * Sets the text of the "slide hints" based on the specified resource
     * IDs.  (A resource ID of zero means "hide the hint and arrow".)
     */
    private void setSlideHints(int upHintResId, int downHintResId) {
        // TODO: It would probably look really cool to do a "fade in" animation
        // when a hint becomes visible after previously being hidden, rather
        // than having it just pop on.

        // TODO: Also consider having both slide hints *always* visible,
        // so that as you slide you first cover up one and later reveal
        // the other.  But this is tricky: the text of the hint that
        // "starts off hidden" will need to be pre-set to the value it
        // should have *after* the slide is complete.

        if (DBG) {
            String upHint =
                    (upHintResId != 0) ? mInCallScreen.getString(upHintResId) : "<empty>";
            String downHint =
                    (downHintResId != 0) ? mInCallScreen.getString(downHintResId) : "<empty>";
            log("setSlideHints: UP '" + upHint + "', DOWN '" + downHint + "'");
        }

        mSlideUp.setVisibility((upHintResId != 0) ? View.VISIBLE : View.GONE);
        if (upHintResId != 0) mSlideUpHint.setText(upHintResId);

        mSlideDown.setVisibility((downHintResId != 0) ? View.VISIBLE : View.GONE);
        if (downHintResId != 0) mSlideDownHint.setText(downHintResId);
    }

    //
    // Sliding state management
    //

    /**
     * Handles a touch event on the CallCard.
     * @see CallCard.dispatchTouchEvent
     */
    /* package */ void handleCallCardTouchEvent(MotionEvent ev) {
        // if (DBG) log("handleCallCardTouchEvent(" + ev + ")...");

        if (mInCallScreen == null || mInCallScreen.isFinishing()) {
            Log.i(LOG_TAG, "handleCallCardTouchEvent: InCallScreen gone; ignoring touch...");
            return;
        }

        final int action = ev.getAction();

        // All the sliding code depends on deltas, so it
        // doesn't really matter in what coordinate space
        // we are, as long as it's independant of our position
        final int xAbsolute = (int) ev.getRawX();
        final int yAbsolute = (int) ev.getRawY();

        if (isSlideInProgress()) {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // Shouldn't happen in this state.
                    break;
                case MotionEvent.ACTION_MOVE:
                    // Move the CallCard!
                    updateWhileSliding(yAbsolute);
                    break;
                case MotionEvent.ACTION_UP:
                    // See if we've slid far enough to do some action
                    // (ie. hang up, or answer an incoming call,
                    // depending on our current state.)
                    stopSliding(yAbsolute);
                    break;
                case MotionEvent.ACTION_CANCEL:
                    // Because we set the FLAG_IGNORE_CHEEK_PRESSES
                    // WindowManager flag (see init()), we'll get an
                    // ACTION_CANCEL event if a valid ACTION_DOWN is later
                    // followed by an ACTION_MOVE that's a "fat touch".
                    // In this case, abort the slide.
                    if (DBG) log("handleCallCardTouchEvent: ACTION_CANCEL: " + ev);
                    if (InCallScreen.ENABLE_PHONE_UI_EVENT_LOGGING) {
                        float size = ev.getSize();
                        String eventValue =
                                InCallScreen.PHONE_UI_EVENT_REJECTED_FAT_TOUCH + "_" + size;
                        Checkin.logEvent(mInCallScreen.getContentResolver(),
                                         Checkin.Events.Tag.PHONE_UI,
                                         eventValue);
                    }
                    abortSlide();
                    break;
            }
        } else {
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    // This event is a touch DOWN on the card: start sliding!
                    startSliding(xAbsolute, yAbsolute);
                    break;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // If we're not sliding (yet), ignore anything other than
                    // ACTION_DOWN.
                    break;
            }
        }
    }

    /**
     * Start sliding the card.
     * Called when we get a DOWN touch event on the slideable CallCard.
     * x and y are the location of the DOWN event in absolute screen coordinates.
     */
    /* package */ void startSliding(int x, int y) {
        if (DBG) log("startSliding(" + x + ", " + y + ")...");

        if (mCallEndedState) {
            if (DBG) log("startSliding: CALL ENDED state; ignoring...");
            return;
        }

        mSlideInProgress = true;
        mTouchDownY = y;
        mPartialSlideEventSent = false;
    }

    /**
     * Handle a MOVE event while sliding.
     * @param y the y-position of the MOVE event in absolute screen coordinates.
     */
    /* package */ void updateWhileSliding(int y) {
        int totalSlideAmount = y - mTouchDownY;
        // if (DBG) log("--------------> MOTION! y = " + y
        //              + "  (total slide = " + totalSlideAmount + ")");

        // TODO: consider caching popupTopPosY and popupBottomPosY in
        // startSliding (to save a couple of method calls each time here.)
        // TODO: this block is duplicated above; use a single helper method instead.
        mMainFrame.getLocationInWindow(mTempLocation);
        final int mainFrameX = mTempLocation[0];
        final int mainFrameY = mTempLocation[1];

        // In the "top" position the CallCard is aligned exactly with the
        // top edge of the main frame.
        final int popupTopPosY = mainFrameY;

        // And in the "bottom" position, the bottom of the CallCard is
        // aligned exactly with the bottom of the main frame.
        final int popupBottomPosY = mainFrameY + mMainFrame.getHeight() - mPopup.getHeight();

        // Forcibly reposition the CallCard
        int newCardTop = mCardPreferredY + totalSlideAmount;
        // if (DBG) log("  ==> New card top: " + newCardTop);

        // But never slide *beyond* the topmost or bottom-most position:
        if (newCardTop < popupTopPosY) newCardTop = popupTopPosY;
        else if (newCardTop > popupBottomPosY) newCardTop = popupBottomPosY;

        // Forcibly reposition the PopupWindow.
        mPopup.update(mCardPreferredX, newCardTop, -1, -1);

        // Check if the user has slid the CallCard more than 1/4 of the
        // way down.  (If the screen is currently off, doing this will
        // turn it back on.)
        if (!mPartialSlideEventSent) {
            final int slideDistanceRequired = mMainFrame.getHeight() - mPopup.getHeight();
            if ((Math.abs(totalSlideAmount) * 4) > slideDistanceRequired) {
                // if (DBG) log("- updateWhileSliding: Partial card slide!");
                mInCallScreen.onPartialCardSlide();

                // Send this at most once per "slide".
                mPartialSlideEventSent = true;
            }
        }
    }

    /**
     * Stop sliding the card.
     * Called when we get an UP touch event while sliding.
     */
    /* package */ void stopSliding(int y) {
        int totalSlideAmount = y - mTouchDownY;
        if (DBG) log("stopSliding: Total slide delta: " + totalSlideAmount);

        // When not sliding, the card is pegged to either the very top
        // or very bottom of the in-call main frame.

        // So the precise slide amount that *should* be required to "complete
        // the slide" is the amount of vertical space in the in-call frame NOT
        // taken up by the CallCard:

        int slideDistanceRequired = mMainFrame.getHeight() - mPopup.getHeight();
        // if (DBG) log("   -> main frame height: " + mMainFrame.getHeight());
        // if (DBG) log("   -> PopupWindow height:   " + mPopup.getHeight());
        // if (DBG) log("   -> DIFFERENCE = " + slideDistanceRequired);

        // But fudge slideDistanceRequired a little, in case you slid
        // "just far enough" but the card jittered a bit when you let go.
        slideDistanceRequired -= 30;

        // Modify totalSlideAmount so that a positive value means "a slide
        // in the correct direction".  (Thus if the card started at the
        // bottom of the screen, meaning that the user needs to slide
        // *up*, we need to flip the sign of totalSlideAmount.)
        if (!mCardAtTop) totalSlideAmount = -totalSlideAmount;

        if (totalSlideAmount >= slideDistanceRequired) {
            if (DBG) log("==> Slide was far enough! slid "
                         + totalSlideAmount + ", needed >= " + slideDistanceRequired);
            finishSuccessfulSlide();
        } else {
            if (DBG) log("==> Didn't slide enough to take action: slid "
                         + totalSlideAmount + ", needed >= " + slideDistanceRequired);
            abortSlide();
        }
    }

    /**
     * The user successfully completed a "slide" operation.
     * Activate whatever action the slide was supposed to trigger.
     *
     * (That could either be (1) hang up the ongoing call(s), or (2)
     * answer an incoming call.)
     *
     * This method is responsible for triggering any screen updates that need
     * to happen, based on any internal state changes due to the slide.
     */
    private void finishSuccessfulSlide() {
        if (DBG) log("finishSuccessfulSlide()...");

        mSlideInProgress = false;

        // TODO: Need to test lots of possible edge cases here, like if the
        // state of the Phone changes while the slide was happening.
        // (For example, say the user slid the card UP to answer an incoming
        // call, but the phone's no longer ringing by the time we got here...)

        // TODO: The state-checking logic here is very similar to the logic in
        // updateCardSlideHints().  Rather than duplicating this logic in both
        // places, maybe use a single helper function that generates a
        // complete "slidability matrix" (ie. all slide hints / states /
        // actions) based on the current state of the Phone.

        final boolean hasRingingCall = !mPhone.getRingingCall().isIdle();
        final boolean hasActiveCall = !mPhone.getForegroundCall().isIdle();
        final boolean hasHoldingCall = !mPhone.getBackgroundCall().isIdle();

        boolean phoneStateAboutToChange = false;
        String eventValue = null;

        // Perform the "successful slide" action.

        if (mCardAtTop) {
            // Card was initially at the top of the screen, which means
            // that the user just did a successful DOWNWARD slide.
            if (DBG) log("==> Successful DOWNWARD slide!");

            // The downward slide action is to hang up any ongoing
            // call(s).
            if (DBG) log("  =========> Slide complete: HANGING UP...");
            eventValue = InCallScreen.PHONE_UI_EVENT_SLIDE_TO_HANGUP;

            if (hasHoldingCall) PhoneUtils.hangupHoldingCall(mPhone);
            if (hasActiveCall) PhoneUtils.hangupActiveCall(mPhone);

            // Any "hangup" action is going to cause
            // the Phone state to change imminently.
            phoneStateAboutToChange = true;
        } else {
            // Card was initially at the bottom of the screen, which means
            // that the user just did a successful UPWARD slide.
            if (DBG) log("==> Successful UPWARD slide!");

            // The upward slide action is to answer the incoming call.
            // (We put the ongoing call on hold if there's already one line in
            // use, or hang up the ongoing call if both lines are in use.)
            if (DBG) log("  =========> Slide complete: ANSWERING...");
            eventValue = InCallScreen.PHONE_UI_EVENT_SLIDE_TO_ANSWER;

            if (hasActiveCall && hasHoldingCall) {
                mInCallScreen.internalAnswerCallBothLinesInUse();
            } else {
                mInCallScreen.internalAnswerCall();
            }
            // Either of the "answer call" functions is going to cause
            // the Phone state to change imminently.
            phoneStateAboutToChange = true;
        }

        if (InCallScreen.ENABLE_PHONE_UI_EVENT_LOGGING && (eventValue != null)) {
            Checkin.logEvent(mInCallScreen.getContentResolver(),
                             Checkin.Events.Tag.PHONE_UI,
                             eventValue);
        }

        // Finally, update the state of the UI depending on what just happened.
        // Update the "permanent" position of the sliding card, and the slide
        // hints.
        //
        // But *don't* do this if we know the Phone state's about to change,
        // like if the user just did a "slide up to answer".  In that case
        // we know we're going to get a onPhoneStateChanged() call in a few
        // milliseconds, and *that's* going to result in an updateScreen() call.
        // (And if we were to do that update now, we'd just get a brief flash
        // of the card at the bottom or the screen. So don't do anything
        // here.)

        if (!phoneStateAboutToChange) {
            updateCardPreferredPosition();
            updateCardSlideHints();

            // And force an immmediate re-layout.  (No need to do any
            // animation here, since the card's new "preferred position" is
            // exactly where the user just slid it.)
            mMainFrame.requestLayout();
        }
    }

    /**
     * Bail out of an in-progress slide *without* activating whatever
     * action the slide was supposed to trigger.
     */
    private void abortSlide() {
        if (DBG) log("abortSlide()...");

        mSlideInProgress = false;

        // This slide had no effect.  Nothing about the state of the
        // UI has changed, so no need to updateCardPreferredPosition() or
        // updateCardSlideHints().  But we *do* need to reposition the
        // PopupWindow back in its correct position.

        // TODO: smoothly animate back to the preferred position.
        mPopup.update(mCardPreferredX, mCardPreferredY, -1, -1);

        if (InCallScreen.ENABLE_PHONE_UI_EVENT_LOGGING) {
            Checkin.logEvent(mInCallScreen.getContentResolver(),
                             Checkin.Events.Tag.PHONE_UI,
                             InCallScreen.PHONE_UI_EVENT_SLIDE_ABORTED);
        }
    }

    /* package */ boolean isSlideInProgress() {
        return mSlideInProgress;
    }

    /* package */ CallCard getCallCard() {
        return mCallCard;
    }

    /**
     * Updates anything about our View hierarchy or internal state
     * that needs to be different in landscape mode.
     *
     * @see InCallScreen.applyConfigurationToLayout()
     */
    /* package */ void updateForLandscapeMode() {
        if (DBG) log("updateForLandscapeMode()...");

        // Reposition the "slide hints".
        RelativeLayout.LayoutParams lp =
                (RelativeLayout.LayoutParams) mSlideUp.getLayoutParams();
        // Equivalent to setting android:layout_marginTop in XML
        lp.topMargin = SLIDE_UP_HINT_TOP_LANDSCAPE;
        mSlideUp.setLayoutParams(lp);
        lp = (RelativeLayout.LayoutParams) mSlideDown.getLayoutParams();
        // Equivalent to setting android:layout_marginTop in XML
        lp.topMargin = SLIDE_DOWN_HINT_TOP_LANDSCAPE;
        mSlideDown.setLayoutParams(lp);
    }


    /**
     * Trivial subclass of View that notifies the InCallScreen when its
     * onAttachedToWindow() method gets called.
     *
     * This is needed because we can't call showPopup() directly from
     * onCreate() or onResume(), because we're not allowed to call
     * mPopup.showAtLocation() until after we have a window token
     * (ie. after the main View hierarchy first gets attached to a
     * Window.)
     *
     * It's easy for a View to notice when that happens, since all Views
     * in the hierarchy get their onAttachedToWindow() methods called.
     * But unfortunately there's no easy way for the *Activity* to notice
     * this has happened.
     *
     * So we add an instance of this class to the main InCallScreen View
     * hierarchy, purely to get notified when the View hierarchy first
     * gets attached to its Window, at which point we're finally able to
     * show the sliding CallCard PopupWindow.
     *
     * TODO: This is cumbersome.  Could we instead have a standard
     * Activity callback for this?
     */
    /* package */ static class WindowAttachNotifierView extends View {
        private SlidingCardManager mSlidingCardManager;

        WindowAttachNotifierView(Context c) {
            super(c);
        }

        public void setSlidingCardManager(SlidingCardManager slidingCardManager) {
            mSlidingCardManager = slidingCardManager;
        }

        @Override
        protected void onAttachedToWindow() {
            // This is called when the view is attached to a window.
            // At this point it has a Surface and will start drawing.
            if (DBG) mSlidingCardManager.log("WindowAttachNotifierView: onAttachedToWindow!");
            super.onAttachedToWindow();

            // The code in showPopup() needs to know the sizes and
            // positions of some views in the mMainFrame view hierarchy,
            // in order to set the popup window's size and position.  That
            // means that showPopup() needs to be called *after* the whole
            // in-call UI has been measured and laid out.  At this point
            // that hasn't happened yet, so we can't directly call
            // mSlidingCardManager.showPopup() from here.
            //
            // Also, to reduce flicker onscreen, we'd like the PopupWindow
            // to appear *before* any of the main view hierarchy becomes
            // visible.  So we use the main view hierarchy's
            // ViewTreeObserver to get notified *after* the layout
            // happens, but before anything gets drawn.

            // Get the ViewTreeObserver for the main InCallScreen view
            // hierarchy.  (You can only call getViewTreeObserver() after
            // the view tree gets attached to a Window, which is why we do
            // this here rather than in InCallScreen.onCreate().)
            final ViewTreeObserver viewTreeObserver = getViewTreeObserver();

            // Arrange for the SlidingCardManager to get called after
            // the main view tree has been laid out.
            // (addOnPreDrawListener() would also be basically equivalent here.)
            viewTreeObserver.addOnGlobalLayoutListener(mSlidingCardManager);

            // See SlidingCardManager.onGlobalLayout() for the next step.
        }

        @Override
        protected void onDetachedFromWindow() {
            // This is called when the view is detached from a window.
            // At this point it no longer has a surface for drawing.
            if (DBG) mSlidingCardManager.log("WindowAttachNotifierView: onDetachedFromWindow!");
            super.onDetachedFromWindow();

            // Nothing necessary here (yet) since we already
            // dismiss the popup from onDestroy().
        }
    }

    /**
     * Implements the ViewTreeObserver.OnGlobalLayoutListener interface.
     */
    public void onGlobalLayout() {
        if (DBG) log("onGlobalLayout()...");
        if (mPopupRequested && !mPopup.isShowing()) {
            showPopup();
        }
    }

    private void log(String msg) {
        Log.d(LOG_TAG, "[" + this + "] " + msg);
    }
}
