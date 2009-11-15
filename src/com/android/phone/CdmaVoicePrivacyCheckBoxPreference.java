package com.android.phone;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import android.content.Context;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.preference.CheckBoxPreference;
import android.util.AttributeSet;
import android.util.Log;

public class CdmaVoicePrivacyCheckBoxPreference extends CheckBoxPreference {
    private static final String LOG_TAG = "CdmaVoicePrivacyCheckBoxPreference";
    private final boolean DBG = (PhoneApp.DBG_LEVEL >= 2);

    Phone phone;
    private MyHandler mHandler = new MyHandler();

    public CdmaVoicePrivacyCheckBoxPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        phone = PhoneFactory.getDefaultPhone();
        phone.getEnhancedVoicePrivacy(mHandler.obtainMessage(MyHandler.MESSAGE_GET_VP));
    }

    public CdmaVoicePrivacyCheckBoxPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.checkBoxPreferenceStyle);
    }

    public CdmaVoicePrivacyCheckBoxPreference(Context context) {
        this(context, null);
    }


    @Override
    protected void onClick() {
        super.onClick();

        phone.enableEnhancedVoicePrivacy(isChecked(),
                mHandler.obtainMessage(MyHandler.MESSAGE_SET_VP));
    }



    private class MyHandler extends Handler {
        private static final int MESSAGE_GET_VP = 0;
        private static final int MESSAGE_SET_VP = 1;

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_GET_VP:
                    handleGetVPResponse(msg);
                    break;
                case MESSAGE_SET_VP:
                    handleSetVPResponse(msg);
                    break;
            }
        }

        private void handleGetVPResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleGetVPResponse: ar.exception=" + ar.exception);
                setEnabled(false);
            } else {
                if (DBG) Log.d(LOG_TAG, "handleGetVPResponse: VP state successfully queried.");
                final int enable = ((int[]) ar.result)[0];
                setChecked(enable != 0);

                android.provider.Settings.Secure.putInt(getContext().getContentResolver(),
                        android.provider.Settings.Secure.ENHANCED_VOICE_PRIVACY_ENABLED, enable);
            }
        }

        private void handleSetVPResponse(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;

            if (ar.exception != null) {
                if (DBG) Log.d(LOG_TAG, "handleSetVPResponse: ar.exception=" + ar.exception);
            }
            if (DBG) Log.d(LOG_TAG, "handleSetVPResponse: re get");

            phone.getEnhancedVoicePrivacy(obtainMessage(MESSAGE_GET_VP));
        }
    }
}
