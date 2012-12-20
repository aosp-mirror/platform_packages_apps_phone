package com.android.phone;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Message;
import android.telephony.PhoneNumberUtils;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallManager;
import com.android.internal.telephony.Phone;
import com.android.phone.InCallUiState.InCallScreenMode;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;

/**
 * Dialer class that encapsulates the DTMF behavior. A single instance of this
 * class is created by {@link PhoneGlobals} and accessible through
 * {@link PhoneGlobals#mDTMFDialer}. This class encapsulates a FIFO queue which
 * holds DTMF dial requests. Whenever the active foreground call ends or
 * changes the queue is cleared if not empty. According to system settings a
 * local DTMF tone is played while a tone is send to the network. Local tones
 * can also be played without sending a tones over the network. This is used by
 * {@link InCallScreen} for handling post DTMF tones initialized by phone
 * number extensions. (for example 5034243,,2,23)
 */
public class DTMFDialer {
    private static final String LOG_TAG = "DTMFDialer";
    private static final boolean DBG = (PhoneGlobals.DBG_LEVEL >= 2);

    // events
    private static final int PHONE_DISCONNECT = 100;
    private static final int DTMF_SEND_CNF = 101;
    private static final int DTMF_STOP = 102;

    // short Dtmf tone duration
    private static final int DTMF_DURATION_MS = 120;

    private CallManager mCM;

    // for thread synchronization of private members
    private final Object mLock = new Object();

    // the tone generator for playing local DTMF tones
    private ToneGenerator mLocalToneGenerator;
    // the local tone character for playing a local tone without sending it
    // over the network
    private Character mCurrentCharacterForLocalDialerTone = null;

    // indicate if the confirmation from TelephonyFW or the timeout for a tone
    // is pending.
    private boolean mDTMFAutoStopPending = false;

    // the entry class for the tone queue
    private final class DTMFQueueEntry {
        // The character to dial
        private Character mcharacter;
        // Use burst DTMF tones for sending to the network
        private boolean useBurstDtmf;

        public DTMFQueueEntry(Character character, boolean useBurstDtmf) {
            this.mcharacter = character;
            this.useBurstDtmf = useBurstDtmf;
        }

        public Character getCharacter() {
            return mcharacter;
        }

        public boolean getUseBurstDtmf() {
            return useBurstDtmf;
        }
    }

    // Queue to queue the DTMF dialer requests
    private Queue<DTMFQueueEntry> mDTMFQueue = new LinkedList<DTMFQueueEntry>();

    // The current DTMF character in send
    private DTMFQueueEntry mDTMFQueueEntryInPlay = null;

    // The DTMF start play request to be continued until a corresponding stop
    // play request is invoked.
    private DTMFQueueEntry mDTMFQueueEntryToPlayWithNoAutoStop = null;

    // The auto stop time is over and the current tone needs to be stopped
    // manually by a call to stopDtmfForTwelveKeyChar()
    private boolean mDTMFManualStopPending = false;

    // The last active call id for which current tones are queued and played
    private long mLastActiveCallId;

    // The current callback to be invoked for started tones
    private OnStartDtmfForTwelveKeyCharListener mOnStartDtmfForTwelveKeyCharListener;

    /**
     * Interface definition for a callback to be invoked when playing a DTMF
     * tone starts.
     */
    public interface OnStartDtmfForTwelveKeyCharListener {
        /**
         * Called when a DTMF tone has been started.
         *
         * @param c
         *            The character for which the tone was started.
         */
        void onStartDtmfForTwelveKeyChar(char c);
    }

    /** Hash Map to map a character to a tone */
    private static final HashMap<Character, Integer> mToneMap = new HashMap<Character, Integer>();

    /** Set up the static maps */
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
    }

    /**
     * Our own handler to take care of the messages from the phone state
     * changes
     */
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            // disconnect action
            // make sure to close the dialer on ALL disconnect actions.
            case PHONE_DISCONNECT:
                if (DBG)
                    log("disconnect message recieved, shutting down.");
                emptyQueue();
                break;
            case DTMF_SEND_CNF:
                if (DBG)
                    log("dtmf confirmation received from FW.");
                // handle burst dtmf confirmation
                handleBurstDtmfConfirmation();
                break;
            case DTMF_STOP:
                if (DBG)
                    log("dtmf delayed stop received.");
                handleDtmfStop();
                break;
            }
        }
    };

    public DTMFDialer() {
        mCM = PhoneGlobals.getInstance().mCM;
        mCM.registerForDisconnect(mHandler, PHONE_DISCONNECT, null);

        synchronized (mLock) {
            try {
                mLocalToneGenerator = new ToneGenerator(
                        AudioManager.STREAM_DTMF, 80);
            } catch (RuntimeException e) {
                if (DBG)
                    log("Exception caught while creating local tone generator: "
                            + e);
                mLocalToneGenerator = null;
            }
        }
    }

    /**
     * Starts or queues a DTMF dial request for the current active call. The
     * tone automatically stops after a short time. If the active call ends or
     * gets inactive before the tone could be played the request will be
     * canceled. The DTMF tone is send over the network. It is also played
     * locally if system settings allow this.
     *
     * @param c
     *         The DTMF character to be send over the network.
     * @return True in the case the given character is a valid DTMF twelve key
     *         character and a active call was found.
     */
    public boolean playDtmfForTwelveKeyChar(char c) {
        if (DBG)
            log("playDtmfForTwelveKeyChar: " + c);

        return processDtmf(c, true /* autoStop */);
    }

    /**
     * Starts or queues a DTMF dial request for the current active call. After
     * the tone starts the dialer will continue to play it and also stops
     * playing further queued request until a corresponding call to
     * stopDtmfForTwelveKeyChar happens. If the active call ends or gets
     * inactive before the tone could be played, than the request will be
     * canceled. The DTMF tone is send over the network. It is also played
     * locally if system settings allow this.
     *
     * @param c
     *            The DTMF character to be send over the network.
     * @return True in the case the given character is a valid DTMF twelve key
     *         character and a active call was found.
     */
    public boolean startDtmfForTwelveKeyChar(char c) {
        if (DBG)
            log("startDtmfForTwelveKeyChar: " + c);

        return processDtmf(c, false /* autoStop */);
    }

    /**
     * Stops playing of a DTMF tone previously started by
     * {@link #startDtmfForTwelveKeyChar(char)}. In the case the previously dial
     * request is still in the queue and waiting to be played, the requested
     * tone will be played like a normal play request later on.
     */
    public void stopDtmfForTwelveKeyChar() {
        if (DBG)
            log("stopDtmfForTwelveKeyChar");

        synchronized (mLock) {
            mDTMFQueueEntryToPlayWithNoAutoStop = null;

            if (mDTMFManualStopPending) // the current tone is waiting to be
                                        // stopped manually, stop it now....
                stopToneAndTryPlayNextFromQueue();
        }
    }

    /**
     * Starts the local DTMF tone for the dialer pad. No tone is send over the
     * network. Used by InCallScreen for playing post DTMF tons included in
     * number extensions.
     */
    public void startLocalDialerToneIfNeeded(char c) {
        // Only play the tone if it exists.
        if (!mToneMap.containsKey(c)) {
            return;
        }

        synchronized (mLock) {
            mCurrentCharacterForLocalDialerTone = c;
            updateLocalDTMFToneIfNeeded();
        }
    }

    /**
     * Stops the local DTMF tone for the dialer pad. Used by InCallScreen for
     * playing post DTMF tons included in number extensions.
     */
    public void stopLocalDialerToneIfNeeded() {
        if (DBG)
            log("stopping dialer tone.");

        synchronized (mLock) {
            mCurrentCharacterForLocalDialerTone = null;
            updateLocalDTMFToneIfNeeded();
        }
    }

    /**
     * Register a callback to be invoked when this dialer starts playing a tone.
     *
     * @param l
     *            The callback that will be called.
     */
    public void setOnStartDtmfForTwelveKeyCharListener(
            OnStartDtmfForTwelveKeyCharListener l) {
        mOnStartDtmfForTwelveKeyCharListener = l;
    }

    /**
     * Removes all currently queued requests and stops playing the current tone.
     */
    private void emptyQueue() {
        if (DBG)
            log("emptyQueue()...");

        mHandler.removeMessages(DTMF_SEND_CNF);
        mHandler.removeMessages(DTMF_STOP);

        synchronized (mLock) {
            mDTMFAutoStopPending = false;
            mDTMFManualStopPending = false;

            mDTMFQueue.clear();
            mDTMFQueueEntryInPlay = null;
            mDTMFQueueEntryToPlayWithNoAutoStop = null;

            updateLocalDTMFToneIfNeeded();
        }
    }

    /**
     * Processes the specified digit as a DTMF key, by playing the appropriate
     * DTMF tone
     */
    private final boolean processDtmf(char c, boolean autoStop) {

        boolean processed = false;

        // if it is a valid key, then send the dtmf tone.
        if (PhoneNumberUtils.is12Key(c)) {
            if (DBG)
                log("sending dtmf tone for '" + c + "'");

            // Play the tone if it exists.
            if (mToneMap.containsKey(c)) {
                // begin tone playback.
                processed = true;
                startTone(c, autoStop);
            }
        } else if (DBG) {
            log("ignoring dtmf request for '" + c + "'");
        }

        // Any DTMF request counts as explicit "user activity".
        PhoneGlobals.getInstance().pokeUserActivity();

        return processed;
    }

    /**
     * Checks if the active call has changed until a previous call to this
     * function. If the active call changed, than the request queue is reseted.
     *
     * @return Returns true if a current active call exists.
     */
    private boolean checkActiveCallId() {
        long curActiveCallId = 0;

        Call activeCall = mCM.getActiveFgCall();

        if (activeCall != null) {
            if (activeCall.getState() == Call.State.ACTIVE
                    || activeCall.getState() == Call.State.ALERTING) {
                // we use the earliest create time of the call as the call id
                curActiveCallId = activeCall.getEarliestCreateTime();
            }
        }

        if (curActiveCallId == 0 || curActiveCallId != mLastActiveCallId) {
            // no active call or active call has changed -> reset queue
            mLastActiveCallId = curActiveCallId;
            emptyQueue();
        }

        // active call found?:
        return (curActiveCallId != 0);
    }

    /**
     * Plays the local tone based the phone type.
     */
    private void startTone(char c, boolean autoStop) {
        // Only play the tone if it exists.
        if (!mToneMap.containsKey(c)) {
            return;
        }

        if (!okToDialDTMFTones()) {
            return;
        }

        if (DBG)
            log("startDtmfTone()...");

        // indicates that we are using automatically shortened DTMF tones for
        // the network. (depends on the phone type)
        boolean shortTone;

        // Read the settings as it may be changed by the user during the call
        Phone phone = mCM.getFgPhone();
        shortTone = PhoneUtils.useShortDtmfTones(phone, phone.getContext());

        DTMFQueueEntry dtmfQueueEntry = new DTMFQueueEntry(c, shortTone);

        if (!autoStop) {
            // register this request as the current active no auto stop tone
            synchronized (mLock) {
                mDTMFQueueEntryToPlayWithNoAutoStop = dtmfQueueEntry;
            }
        }

        // play or queue the request
        playDTMFQueueEntry(dtmfQueueEntry);
    }

    /**
     * Determines when we can dial DTMF tones.
     */
    public boolean okToDialDTMFTones() {
        final boolean hasRingingCall = mCM.hasActiveRingingCall();
        final Call.State fgCallState = mCM.getActiveFgCallState();

        // We're allowed to send DTMF tones when there's an ACTIVE
        // foreground call, and not when an incoming call is ringing
        // (since DTMF tones are useless in that state), or if the
        // Manage Conference UI is visible (since the tab interferes
        // with the "Back to call" button.)

        // We can also dial while in ALERTING state because there are
        // some connections that never update to an ACTIVE state (no
        // indication from the network).
        boolean canDial = (fgCallState == Call.State.ACTIVE || fgCallState == Call.State.ALERTING)
                && !hasRingingCall
                && (PhoneGlobals.getInstance().inCallUiState.inCallScreenMode
                        != InCallScreenMode.MANAGE_CONFERENCE);

        if (DBG)
            log("[okToDialDTMFTones] foreground state: " + fgCallState
                    + ", ringing state: " + hasRingingCall
                    + ", call screen mode: "
                    + PhoneGlobals.getInstance().inCallUiState.inCallScreenMode
                    + ", result: " + canDial);

        return canDial;
    }

    /**
     * Handles Burst Dtmf Confirmation from the Framework.
     */
    private void handleBurstDtmfConfirmation() {
        if (DBG)
            log("handleBurstDtmfConfirmation...");

        handleStopTone();
    }

    /**
     * Handles the delayed stop tone request for a current none Burst Dtmf tone.
     */
    private void handleDtmfStop() {
        if (DBG)
            log("handleDtmfStop...");

        handleStopTone();
    }

    /**
     * handles stop events
     */
    private void handleStopTone() {
        synchronized (mLock) {
            if (mDTMFQueueEntryToPlayWithNoAutoStop == mDTMFQueueEntryInPlay) {
                // the current tone was started via a start request and need to
                // be played until we receive a stop request
                mDTMFQueueEntryToPlayWithNoAutoStop = null;
                mDTMFManualStopPending = true;
            } else
                // stop the current tone and try to play the next tone from the
                // queue
                stopToneAndTryPlayNextFromQueue();
        }
    }

    /**
     * stops the current tone and ties to play the next tone from the queue
     */
    private void stopToneAndTryPlayNextFromQueue() {

        DTMFQueueEntry dtmfQueueEntry = null;

        synchronized (mLock) {
            if (mDTMFQueueEntryInPlay != null) {
                // we are currently playing a network tone:

                if (DBG)
                    log("stopping remote tone.");
                if (!mDTMFQueueEntryInPlay.getUseBurstDtmf())
                    // stop it in the case it is not stopped by the network
                    // itself
                    mCM.stopDtmf();

                if (mDTMFQueueEntryToPlayWithNoAutoStop == mDTMFQueueEntryInPlay)
                    // this is the stop of the current no auto stop tone ->
                    // reset it
                    mDTMFQueueEntryToPlayWithNoAutoStop = null;

                mDTMFQueueEntryInPlay = null;
                // update the local tone
                updateLocalDTMFToneIfNeeded();
            }

            // reset pending stop flags
            mDTMFAutoStopPending = false;
            mDTMFManualStopPending = false;

            if (!mDTMFQueue.isEmpty())
                // check if we need to empty the queue because of active call
                // changes
                checkActiveCallId();

            if (!mDTMFQueue.isEmpty()) {
                // play the next tone from the queue
                dtmfQueueEntry = mDTMFQueue.remove();
                Log.i(LOG_TAG, "The dtmf character removed from queue"
                        + dtmfQueueEntry.getCharacter());

                playDTMFQueueEntry(dtmfQueueEntry);
            }
        }
    }

    /**
     * starts playing or queues the given request
     *
     * @param queueEntry
     *            The play request.
     */
    private void playDTMFQueueEntry(DTMFQueueEntry queueEntry) {
        // set the current active call for this request
        checkActiveCallId();

        synchronized (mLock) {
            if (mDTMFAutoStopPending == true) {
                // Insert the dtmf char to the queue
                mDTMFQueue.add(queueEntry);
            } else {
                mDTMFAutoStopPending = true;

                if (queueEntry.getUseBurstDtmf()) {
                    String dtmfStr = Character.toString(queueEntry
                            .getCharacter());
                    mCM.sendBurstDtmf(dtmfStr, 0, 0,
                            mHandler.obtainMessage(DTMF_SEND_CNF));
                    // Set flag to indicate wait for Telephony confirmation.
                } else {
                    mCM.startDtmf(queueEntry.getCharacter());
                    mHandler.sendMessageDelayed(
                            mHandler.obtainMessage(DTMF_STOP), DTMF_DURATION_MS);
                }

                mDTMFQueueEntryInPlay = queueEntry;
                updateLocalDTMFToneIfNeeded();
            }
        }

        if (mOnStartDtmfForTwelveKeyCharListener != null)
            mOnStartDtmfForTwelveKeyCharListener
                    .onStartDtmfForTwelveKeyChar(queueEntry.mcharacter
                            .charValue());
    }

    /**
     * Starts or stops the local DTMF tone according to the current state.
     */
    private void updateLocalDTMFToneIfNeeded() {
        if (DBG)
            log("update local DTMF tone.");

        boolean localToneEnabled = (PhoneGlobals.getInstance().getResources()
                .getBoolean(R.bool.allow_local_dtmf_tones))
                && android.provider.Settings.System
                        .getInt(PhoneGlobals.getInstance().getContentResolver(),
                                android.provider.Settings.System.DTMF_TONE_WHEN_DIALING,
                                1) == 1;

        if (localToneEnabled) {
            synchronized (mLock) {

                Character c = null;
                int toneDuration = -1;

                if (mDTMFQueueEntryInPlay != null) {
                    // play the tone corresponding to the currently sent DTMF
                    // character
                    c = mDTMFQueueEntryInPlay.getCharacter();
                    if (mDTMFQueueEntryInPlay.getUseBurstDtmf()) {
                        toneDuration = DTMF_DURATION_MS;
                    }
                } else {
                    // play the tone set by startLocalDialerToneIfNeeded
                    c = mCurrentCharacterForLocalDialerTone;
                }

                if (c != null) {
                    if (mLocalToneGenerator == null) {
                        if (DBG)
                            log("updateLocalDTMFToneIfNeeded: mLocalToneGenerator == null, tone: "
                                    + c);
                    } else {
                        if (DBG)
                            log("starting local DTMF tone: " + c);

                        mLocalToneGenerator.startTone(mToneMap.get(c),
                                toneDuration);
                    }
                } else {
                    if (mLocalToneGenerator == null) {
                        if (DBG)
                            log("stopDialerTone: mLocalToneGenerator == null");
                    } else {
                        if (DBG)
                            log("stopping dialer tone.");
                        mLocalToneGenerator.stopTone();
                    }
                }
            }
        }
    }

    /**
     * static logging method
     */
    private static void log(String msg) {
        Log.d(LOG_TAG, msg);
    }
}
