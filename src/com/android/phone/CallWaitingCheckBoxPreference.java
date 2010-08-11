package com.android.phone;

import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.Phone;

import static com.android.phone.TimeConsumingPreferenceActivity.RESPONSE_ERROR;

import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;
import android.util.Log;

import com.android.internal.telephony.Phone;

public class CallWaitingCheckBoxPreference extends CheckBoxPreference {
    private static final String LOG_TAG = "CallWaitingCheckBoxPreference";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    private final MyHandler mHandler = new MyHandler();
    Phone phone;
    TimeConsumingPreferenceListener tcpListener;

    public CallWaitingCheckBoxPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        phone = PhoneApp.getPhone();
    }

    public CallWaitingCheckBoxPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.checkBoxPreferenceStyle);
    }

    public CallWaitingCheckBoxPreference(Context context) {
        this(context, null);
    }

    void init(TimeConsumingPreferenceListener listener, boolean skipReading) {
        tcpListener = listener;

        if (!skipReading) {
            phone.getCallWaiting(mHandler.obtainMessage(MyHandler.MESSAGE_GET_CALL_WAITING,
                    MyHandler.MESSAGE_GET_CALL_WAITING, MyHandler.MESSAGE_GET_CALL_WAITING));
            if (tcpListener != null) {
                tcpListener.onStarted(this, true);
            }
        }
    }

    @Override
    protected void onClick() {
        super.onClick();

        phone.setCallWaiting(isChecked(),
                mHandler.obtainMessage(MyHandler.MESSAGE_SET_CALL_WAITING));
        if (tcpListener != null) {
            tcpListener.onStarted(this, false);
        }
    }

    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_CALL_WAITING = 0;
        private static final int MESSAGE_SET_CALL_WAITING = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_CALL_WAITING:
                    handleGetCallWaitingResponse(msg);
                    break;
                case MESSAGE_SET_CALL_WAITING:
                    handleSetCallWaitingResponse(msg);
                    break;
            }
        }

        private void handleGetCallWaitingResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (tcpListener != null) {
                if (msg.arg2 == MESSAGE_SET_CALL_WAITING) {
                    tcpListener.onFinished(CallWaitingCheckBoxPreference.this, false);
                } else {
                    tcpListener.onFinished(CallWaitingCheckBoxPreference.this, true);
                }
            }

            if (ar.exception != null) {
                if (DBG) {
                    Log.d(LOG_TAG, "handleGetCallWaitingResponse: ar.exception=" + ar.exception);
                }
                if (tcpListener != null) {
                    tcpListener.onException(CallWaitingCheckBoxPreference.this,
                            (CommandException)ar.exception);
                }
            } else if (ar.userObj instanceof Throwable) {
                if (tcpListener != null) tcpListener.onError(CallWaitingCheckBoxPreference.this, RESPONSE_ERROR);
            } else {
                if (DBG) Log.d(LOG_TAG, "handleGetCallWaitingResponse: CW state successfully queried.");
                int[] cwArray = (int[])ar.result;
                // If cwArray[0] is = 1, then cwArray[1] must follow,
                // with the TS 27.007 service class bit vector of services
                // for which call waiting is enabled.
                try {
                    setChecked(((cwArray[0] == 1) && ((cwArray[1] & 0x01) == 0x01)));
                } catch (ArrayIndexOutOfBoundsException e) {
                    Log.e(LOG_TAG, "handleGetCallWaitingResponse: improper result: err ="
                            + e.getMessage());
                }
            }
        }

        private void handleSetCallWaitingResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetCallWaitingResponse: ar.exception=" + ar.exception);
                //setEnabled(false);
            }
            if (DBG) Log.d(LOG_TAG, "handleSetCallWaitingResponse: re get");

            phone.getCallWaiting(obtainMessage(MESSAGE_GET_CALL_WAITING,
                    MESSAGE_SET_CALL_WAITING, MESSAGE_SET_CALL_WAITING, ar.exception));
        }
    }
}
