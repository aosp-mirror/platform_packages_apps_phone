LOCAL_PATH:= $(call my-dir)

# Static library with some common classes for the phone apps.
# To use it add this line in your Android.mk
#  LOCAL_STATIC_JAVA_LIBRARIES := com.android.phone.common
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := user

LOCAL_SRC_FILES := \
	src/com/android/phone/ButtonGridLayout.java \
	src/com/android/phone/HapticFeedback.java

LOCAL_MODULE := com.android.phone.common
include $(BUILD_STATIC_JAVA_LIBRARY)

# Build the Phone app which includes the emergency dialer. See Contacts
# for the 'other' dialer.
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := user

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_SRC_FILES += \
        src/com/android/phone/INetworkQueryService.aidl \
        src/com/android/phone/INetworkQueryServiceCallback.aidl

LOCAL_PACKAGE_NAME := Phone
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
