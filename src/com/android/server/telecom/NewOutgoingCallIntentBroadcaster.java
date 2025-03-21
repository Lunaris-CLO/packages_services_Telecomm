/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.server.telecom;

import android.Manifest;
import android.app.Activity;
import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Trace;
import android.os.UserHandle;
import android.telecom.GatewayInfo;
import android.telecom.Log;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telecom.VideoProfile;
import android.telephony.DisconnectCause;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.telecom.flags.FeatureFlags;
import com.android.server.telecom.callredirection.CallRedirectionProcessor;

// TODO: Needed for move to system service: import com.android.internal.R;

/**
 * OutgoingCallIntentBroadcaster receives CALL and CALL_PRIVILEGED Intents, and broadcasts the
 * ACTION_NEW_OUTGOING_CALL intent. ACTION_NEW_OUTGOING_CALL is an ordered broadcast intent which
 * contains the phone number being dialed. Applications can use this intent to (1) see which numbers
 * are being dialed, (2) redirect a call (change the number being dialed), or (3) prevent a call
 * from being placed.
 *
 * After the other applications have had a chance to see the ACTION_NEW_OUTGOING_CALL intent, it
 * finally reaches the {@link NewOutgoingCallBroadcastIntentReceiver}.
 *
 * Calls where no number is present (like for a CDMA "empty flash" or a nonexistent voicemail
 * number) are exempt from being broadcast.
 *
 * Calls to emergency numbers are still broadcast for informative purposes. The call is placed
 * prior to sending ACTION_NEW_OUTGOING_CALL and cannot be redirected nor prevented.
 */
@VisibleForTesting
public class NewOutgoingCallIntentBroadcaster {
    /**
     * Legacy string constants used to retrieve gateway provider extras from intents. These still
     * need to be copied from the source call intent to the destination intent in order to
     * support third party gateway providers that are still using old string constants in
     * Telephony.
     */
    public static final String EXTRA_GATEWAY_PROVIDER_PACKAGE =
            "com.android.phone.extra.GATEWAY_PROVIDER_PACKAGE";
    public static final String EXTRA_GATEWAY_URI = "com.android.phone.extra.GATEWAY_URI";

    private final CallsManager mCallsManager;
    private Call mCall;
    private final Intent mIntent;
    private final Context mContext;
    private final PhoneNumberUtilsAdapter mPhoneNumberUtilsAdapter;
    private final TelecomSystem.SyncRoot mLock;
    private final DefaultDialerCache mDefaultDialerCache;
    private final MmiUtils mMmiUtils;
    private final FeatureFlags mFeatureFlags;

    /*
     * Whether or not the outgoing call intent originated from the default phone application. If
     * so, it will be allowed to make emergency calls, even with the ACTION_CALL intent.
     */
    private final boolean mIsDefaultOrSystemPhoneApp;

    public static class CallDisposition {
        // True for certain types of numbers that are not intended to be intercepted or modified
        // by third parties (e.g. emergency numbers).
        public boolean callImmediately = false;
        // True for all managed calls, false for self-managed calls.
        public boolean sendBroadcast = true;
        // True for requesting call redirection, false for not requesting it.
        public boolean requestRedirection = true;
        public int disconnectCause = DisconnectCause.NOT_DISCONNECTED;
        String number;
        Uri callingAddress;
    }

    @VisibleForTesting
    public NewOutgoingCallIntentBroadcaster(Context context, CallsManager callsManager,
            Intent intent, PhoneNumberUtilsAdapter phoneNumberUtilsAdapter,
            boolean isDefaultPhoneApp, DefaultDialerCache defaultDialerCache, MmiUtils mmiUtils,
            FeatureFlags featureFlags) {
        mContext = context;
        mCallsManager = callsManager;
        mIntent = intent;
        mPhoneNumberUtilsAdapter = phoneNumberUtilsAdapter;
        mIsDefaultOrSystemPhoneApp = isDefaultPhoneApp;
        mLock = mCallsManager.getLock();
        mDefaultDialerCache = defaultDialerCache;
        mMmiUtils = mmiUtils;
        mFeatureFlags = featureFlags;
    }

    /**
     * Processes the result of the outgoing call broadcast intent, and performs callbacks to
     * the OutgoingCallIntentBroadcasterListener as necessary.
     */
    public class NewOutgoingCallBroadcastIntentReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                Log.startSession("NOCBIR.oR");
                Trace.beginSection("onReceiveNewOutgoingCallBroadcast");
                synchronized (mLock) {
                    Log.v(this, "onReceive: %s", intent);

                    // Once the NEW_OUTGOING_CALL broadcast is finished, the resultData is
                    // used as the actual number to call. (If null, no call will be placed.)
                    String resultNumber = getResultData();
                    Log.i(NewOutgoingCallIntentBroadcaster.this,
                            "Received new-outgoing-call-broadcast for %s with data %s", mCall,
                            Log.pii(resultNumber));

                    boolean endEarly = false;
                    long disconnectTimeout =
                            Timeouts.getNewOutgoingCallCancelMillis(mContext.getContentResolver());
                    if (resultNumber == null) {
                        Log.v(this, "Call cancelled (null number), returning...");
                        disconnectTimeout = getDisconnectTimeoutFromApp(
                                getResultExtras(false), disconnectTimeout);
                        endEarly = true;
                    } else if (isEmergencyNumber(resultNumber)) {
                        Log.w(this, "Cannot modify outgoing call to emergency number %s.",
                                resultNumber);
                        disconnectTimeout = 0;
                        endEarly = true;
                    }

                    if (endEarly) {
                        if (mCall != null) {
                            mCall.disconnect(disconnectTimeout);
                        }
                        return;
                    }

                    // If this call is already disconnected then we have nothing more to do.
                    if (mCall.isDisconnected()) {
                        Log.w(this, "Call has already been disconnected," +
                                        " ignore the broadcast Call %s", mCall);
                        return;
                    }

                    // TODO: Remove the assumption that phone numbers are either SIP or TEL.
                    // This does not impact self-managed ConnectionServices as they do not use the
                    // NewOutgoingCallIntentBroadcaster.
                    Uri resultHandleUri = Uri.fromParts(
                            mPhoneNumberUtilsAdapter.isUriNumber(resultNumber) ?
                                    PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL,
                            resultNumber, null);

                    Uri originalUri = mIntent.getData();

                    if (originalUri.getSchemeSpecificPart().equals(resultNumber)) {
                        Log.v(this, "Call number unmodified after" +
                                " new outgoing call intent broadcast.");
                    } else {
                        Log.v(this, "Retrieved modified handle after outgoing call intent" +
                                " broadcast: Original: %s, Modified: %s",
                                Log.pii(originalUri),
                                Log.pii(resultHandleUri));
                    }

                    GatewayInfo gatewayInfo = getGateWayInfoFromIntent(intent, resultHandleUri);
                    placeOutgoingCallImmediately(mCall, resultHandleUri, gatewayInfo,
                            mIntent.getBooleanExtra(
                                    TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false),
                            mIntent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                                    VideoProfile.STATE_AUDIO_ONLY));
                }
            } finally {
                Trace.endSection();
                Log.endSession();
            }
        }
    }

    /**
     * Processes the supplied intent and starts the outgoing call broadcast process relevant to the
     * intent.
     *
     * This method will handle three kinds of actions:
     *
     * - CALL (intent launched by all third party dialers)
     * - CALL_PRIVILEGED (intent launched by system apps e.g. system Dialer, voice Dialer)
     * - CALL_EMERGENCY (intent launched by lock screen emergency dialer)
     *
     * @return {@link DisconnectCause#NOT_DISCONNECTED} if the call succeeded, and an appropriate
     *         {@link DisconnectCause} if the call did not, describing why it failed.
     */
    @VisibleForTesting
    public CallDisposition evaluateCall() {
        CallDisposition result = new CallDisposition();

        Intent intent = mIntent;
        String action = intent.getAction();
        final Uri handle = intent.getData();

        if (handle == null) {
            Log.w(this, "Empty handle obtained from the call intent.");
            result.disconnectCause = DisconnectCause.INVALID_NUMBER;
            return result;
        }

        boolean isVoicemailNumber = PhoneAccount.SCHEME_VOICEMAIL.equals(handle.getScheme());
        if (isVoicemailNumber) {
            if (Intent.ACTION_CALL.equals(action)
                    || Intent.ACTION_CALL_PRIVILEGED.equals(action)) {
                // Voicemail calls will be handled directly by the telephony connection manager
                Log.i(this, "Voicemail number dialed. Skipping redirection and broadcast", intent);
                mIntent.putExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                        VideoProfile.STATE_AUDIO_ONLY);
                result.callImmediately = true;
                result.requestRedirection = false;
                result.sendBroadcast = false;
                result.callingAddress = handle;
                return result;
            } else {
                Log.i(this, "Unhandled intent %s. Ignoring and not placing call.", intent);
                result.disconnectCause = DisconnectCause.OUTGOING_CANCELED;
                return result;
            }
        }

        PhoneAccountHandle targetPhoneAccount = mIntent.getParcelableExtra(
                TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE);
        boolean isSelfManaged = false;
        if (targetPhoneAccount != null) {
            PhoneAccount phoneAccount =
                    mCallsManager.getPhoneAccountRegistrar().getPhoneAccountUnchecked(
                            targetPhoneAccount);
            if (phoneAccount != null) {
                isSelfManaged = phoneAccount.isSelfManaged();
            }
        }

        result.number = "";
        result.callingAddress = handle;

        if (isSelfManaged) {
            // Self-managed call.
            result.callImmediately = true;
            result.sendBroadcast = false;
            result.requestRedirection = false;
            Log.i(this, "Skipping NewOutgoingCallBroadcast for self-managed call.");
            return result;
        }

        // Placing a managed call
        String number = getNumberFromCallIntent(intent);
        result.number = number;
        if (number == null) {
            result.disconnectCause = DisconnectCause.NO_PHONE_NUMBER_SUPPLIED;
            return result;
        }

        final boolean isEmergencyNumber = isEmergencyNumber(number);
        Log.v(this, "isEmergencyNumber = %s", isEmergencyNumber);

        action = calculateCallIntentAction(intent, isEmergencyNumber);
        intent.setAction(action);

        if (Intent.ACTION_CALL.equals(action)) {
            if (isEmergencyNumber) {
                if (!mIsDefaultOrSystemPhoneApp) {
                    Log.w(this, "Cannot call potential emergency number %s with CALL Intent %s "
                            + "unless caller is system or default dialer.", number, intent);
                    launchSystemDialer(intent.getData());
                    result.disconnectCause = DisconnectCause.OUTGOING_CANCELED;
                    return result;
                } else {
                    result.callImmediately = true;
                    result.requestRedirection = false;
                }
            } else if (mMmiUtils.isDangerousMmiOrVerticalCode(intent.getData())) {
                if (!mIsDefaultOrSystemPhoneApp) {
                    Log.w(this,
                            "Potentially dangerous MMI code %s with CALL Intent %s can only be "
                                    + "sent if caller is the system or default dialer",
                            number, intent);
                    launchSystemDialer(intent.getData());
                    result.disconnectCause = DisconnectCause.OUTGOING_CANCELED;
                    return result;
                }
            }
        } else if (Intent.ACTION_CALL_EMERGENCY.equals(action)) {
            if (!isEmergencyNumber) {
                Log.w(this, "Cannot call non-potential-emergency number %s with EMERGENCY_CALL "
                        + "Intent %s.", number, intent);
                result.disconnectCause = DisconnectCause.OUTGOING_CANCELED;
                return result;
            }
            result.callImmediately = true;
            result.requestRedirection = false;
        } else {
            Log.w(this, "Unhandled Intent %s. Ignoring and not placing call.", intent);
            result.disconnectCause = DisconnectCause.INVALID_NUMBER;
            return result;
        }

        String scheme = mPhoneNumberUtilsAdapter.isUriNumber(number)
                ? PhoneAccount.SCHEME_SIP : PhoneAccount.SCHEME_TEL;
        result.callingAddress = Uri.fromParts(scheme, number, null);

        return result;
    }

    private String getNumberFromCallIntent(Intent intent) {
        String number = null;

        Uri uri = intent.getData();
        if (uri != null) {
            String scheme = uri.getScheme();
            if (scheme != null) {
                if (scheme.equals("tel") || scheme.equals("sip")) {
                    number = uri.getSchemeSpecificPart();
                }
            }
        }

        if (TextUtils.isEmpty(number)) {
            Log.w(this, "Empty number obtained from the call intent.");
            return null;
        }

        boolean isUriNumber = mPhoneNumberUtilsAdapter.isUriNumber(number);
        if (!isUriNumber) {
            number = mPhoneNumberUtilsAdapter.convertKeypadLettersToDigits(number);
            number = mPhoneNumberUtilsAdapter.stripSeparators(number);
        }
        return number;
    }

    public void processCall(Call call, CallDisposition disposition) {
        mCall = call;

        // If the new outgoing call broadast doesn't block, trigger the legacy process call
        // behavior and exit out here.
        if (!mFeatureFlags.isNewOutgoingCallBroadcastUnblocking()) {
            legacyProcessCall(disposition);
            return;
        }
        boolean callRedirectionWithService = false;
        // Only try to do redirection if it was requested and we're not calling immediately.
        // We can expect callImmediately to be true for emergency calls and voip calls.
        if (disposition.requestRedirection && !disposition.callImmediately) {
            CallRedirectionProcessor callRedirectionProcessor = new CallRedirectionProcessor(
                    mContext, mCallsManager, mCall, disposition.callingAddress,
                    mCallsManager.getPhoneAccountRegistrar(),
                    getGateWayInfoFromIntent(mIntent, mIntent.getData()),
                    mIntent.getBooleanExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,
                            false),
                    mIntent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                            VideoProfile.STATE_AUDIO_ONLY));
            /**
             * If there is an available {@link android.telecom.CallRedirectionService}, use the
             * {@link CallRedirectionProcessor} to perform call redirection instead of using
             * broadcasting.
             */
            callRedirectionWithService = callRedirectionProcessor
                    .canMakeCallRedirectionWithServiceAsUser(mCall.getAssociatedUser());
            if (callRedirectionWithService) {
                callRedirectionProcessor.performCallRedirection(mCall.getAssociatedUser());
            }
        }

        // If no redirection was kicked off, place the call now.
        if (!callRedirectionWithService) {
            callImmediately(disposition);
        }

        // Finally, send the non-blocking broadcast if we're supposed to (ie for any non-voip call).
        if (disposition.sendBroadcast) {
            UserHandle targetUser = mCall.getAssociatedUser();
            broadcastIntent(mIntent, disposition.number, false /* receiverRequired */, targetUser);
        }
    }

    /**
     * The legacy non-flagged version of processing a call.  Although there is some code duplication
     * if makes the new flow cleaner to read.
     * @param disposition
     */
    private void legacyProcessCall(CallDisposition disposition) {
        if (disposition.callImmediately) {
            callImmediately(disposition);

            // Don't return but instead continue and send the ACTION_NEW_OUTGOING_CALL broadcast
            // so that third parties can still inspect (but not intercept) the outgoing call. When
            // the broadcast finally reaches the OutgoingCallBroadcastReceiver, we'll know not to
            // initiate the call again because of the presence of the EXTRA_ALREADY_CALLED extra.
        }

        boolean callRedirectionWithService = false;
        if (disposition.requestRedirection) {
            CallRedirectionProcessor callRedirectionProcessor = new CallRedirectionProcessor(
                    mContext, mCallsManager, mCall, disposition.callingAddress,
                    mCallsManager.getPhoneAccountRegistrar(),
                    getGateWayInfoFromIntent(mIntent, mIntent.getData()),
                    mIntent.getBooleanExtra(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE,
                            false),
                    mIntent.getIntExtra(TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                            VideoProfile.STATE_AUDIO_ONLY));
            /**
             * If there is an available {@link android.telecom.CallRedirectionService}, use the
             * {@link CallRedirectionProcessor} to perform call redirection instead of using
             * broadcasting.
             */
            callRedirectionWithService = callRedirectionProcessor
                    .canMakeCallRedirectionWithServiceAsUser(mCall.getAssociatedUser());
            if (callRedirectionWithService) {
                callRedirectionProcessor.performCallRedirection(mCall.getAssociatedUser());
            }
        }

        if (disposition.sendBroadcast) {
            UserHandle targetUser = mCall.getAssociatedUser();
            broadcastIntent(mIntent, disposition.number,
                    !disposition.callImmediately && !callRedirectionWithService, targetUser);
        }
    }

    /**
     * Place a call immediately.
     * @param disposition The disposition; used for retrieving the address of the call.
     */
    private void callImmediately(CallDisposition disposition) {
        boolean speakerphoneOn = mIntent.getBooleanExtra(
                TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false);
        int videoState = mIntent.getIntExtra(
                TelecomManager.EXTRA_START_CALL_WITH_VIDEO_STATE,
                VideoProfile.STATE_AUDIO_ONLY);
        placeOutgoingCallImmediately(mCall, disposition.callingAddress, null,
                speakerphoneOn, videoState);
    }

    /**
     * Sends a new outgoing call ordered broadcast so that third party apps can cancel the
     * placement of the call or redirect it to a different number.
     *
     * @param originalCallIntent The original call intent.
     * @param number Call number that was stored in the original call intent.
     * @param receiverRequired Whether or not the result from the ordered broadcast should be
     *                         processed using a {@link NewOutgoingCallIntentBroadcaster}.
     * @param targetUser User that the broadcast sent to.
     */
    private void broadcastIntent(
            Intent originalCallIntent,
            String number,
            boolean receiverRequired,
            UserHandle targetUser) {
        Intent broadcastIntent = new Intent(Intent.ACTION_NEW_OUTGOING_CALL);
        if (number != null) {
            broadcastIntent.putExtra(Intent.EXTRA_PHONE_NUMBER, number);
        }
        Log.v(this, "Broadcasting intent: %s.", broadcastIntent);

        checkAndCopyProviderExtras(originalCallIntent, broadcastIntent);

        if (mFeatureFlags.isNewOutgoingCallBroadcastUnblocking()) {
            // Where the new outgoing call broadcast is unblocking, do not give receiver FG priority
            // and do not allow background activity starts.
            broadcastIntent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            Log.i(this, "broadcastIntent: Sending non-blocking for %s to %s", mCall.getId(),
                    targetUser);
            if (mFeatureFlags.telecomResolveHiddenDependencies()) {
                mContext.sendBroadcastAsUser(
                        broadcastIntent,
                        targetUser,
                        Manifest.permission.PROCESS_OUTGOING_CALLS);
            } else {
                mContext.sendBroadcastAsUser(
                        broadcastIntent,
                        targetUser,
                        android.Manifest.permission.PROCESS_OUTGOING_CALLS,
                        AppOpsManager.OP_PROCESS_OUTGOING_CALLS);  // initialExtras
            }
        } else {
            Log.i(this, "broadcastIntent: Sending ordered for %s to %s, waitForResult=%b",
                    mCall.getId(), targetUser, receiverRequired);
            final BroadcastOptions options = BroadcastOptions.makeBasic();
            options.setBackgroundActivityStartsAllowed(true);
            // Force receivers of this broadcast intent to run at foreground priority because we
            // want to finish processing the broadcast intent as soon as possible.
            broadcastIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND
                    | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);

            mContext.sendOrderedBroadcastAsUser(
                    broadcastIntent,
                    targetUser,
                    android.Manifest.permission.PROCESS_OUTGOING_CALLS,
                    AppOpsManager.OP_PROCESS_OUTGOING_CALLS,
                    options.toBundle(),
                    receiverRequired ? new NewOutgoingCallBroadcastIntentReceiver() : null,
                    null,  // scheduler
                    Activity.RESULT_OK,  // initialCode
                    number,  // initialData: initial value for the result data (number to be
                             // modified)
                    null);  // initialExtras
        }
    }

    /**
     * Copy all the expected extras set when a 3rd party gateway provider is to be used, from the
     * source intent to the destination one.
     *
     * @param src Intent which may contain the provider's extras.
     * @param dst Intent where a copy of the extras will be added if applicable.
     */
    public void checkAndCopyProviderExtras(Intent src, Intent dst) {
        if (src == null) {
            return;
        }
        if (hasGatewayProviderExtras(src)) {
            dst.putExtra(EXTRA_GATEWAY_PROVIDER_PACKAGE,
                    src.getStringExtra(EXTRA_GATEWAY_PROVIDER_PACKAGE));
            dst.putExtra(EXTRA_GATEWAY_URI,
                    src.getStringExtra(EXTRA_GATEWAY_URI));
            Log.d(this, "Found and copied gateway provider extras to broadcast intent.");
            return;
        }

        Log.d(this, "No provider extras found in call intent.");
    }

    /**
     * Check if valid gateway provider information is stored as extras in the intent
     *
     * @param intent to check for
     * @return true if the intent has all the gateway information extras needed.
     */
    private boolean hasGatewayProviderExtras(Intent intent) {
        final String name = intent.getStringExtra(EXTRA_GATEWAY_PROVIDER_PACKAGE);
        final String uriString = intent.getStringExtra(EXTRA_GATEWAY_URI);

        return !TextUtils.isEmpty(name) && !TextUtils.isEmpty(uriString);
    }

    private static Uri getGatewayUriFromString(String gatewayUriString) {
        return TextUtils.isEmpty(gatewayUriString) ? null : Uri.parse(gatewayUriString);
    }

    /**
     * Extracts gateway provider information from a provided intent..
     *
     * @param intent to extract gateway provider information from.
     * @param trueHandle The actual call handle that the user is trying to dial
     * @return GatewayInfo object containing extracted gateway provider information as well as
     *     the actual handle the user is trying to dial.
     */
    public static GatewayInfo getGateWayInfoFromIntent(Intent intent, Uri trueHandle) {
        if (intent == null) {
            return null;
        }

        // Check if gateway extras are present.
        String gatewayPackageName = intent.getStringExtra(EXTRA_GATEWAY_PROVIDER_PACKAGE);
        Uri gatewayUri = getGatewayUriFromString(intent.getStringExtra(EXTRA_GATEWAY_URI));
        if (!TextUtils.isEmpty(gatewayPackageName) && gatewayUri != null) {
            return new GatewayInfo(gatewayPackageName, gatewayUri, trueHandle);
        }

        return null;
    }

    private void placeOutgoingCallImmediately(Call call, Uri handle, GatewayInfo gatewayInfo,
            boolean speakerphoneOn, int videoState) {
        Log.i(this,
                "Placing call immediately instead of waiting for OutgoingCallBroadcastReceiver");
        // Since we are not going to go through "Outgoing call broadcast", make sure
        // we mark it as ready.
        mCall.setNewOutgoingCallIntentBroadcastIsDone();
        mCallsManager.placeOutgoingCall(call, handle, gatewayInfo, speakerphoneOn, videoState);
    }

    private void launchSystemDialer(Uri handle) {
        Intent systemDialerIntent = new Intent();
        systemDialerIntent.setComponent(mDefaultDialerCache.getDialtactsSystemDialerComponent());
        systemDialerIntent.setAction(Intent.ACTION_DIAL);
        systemDialerIntent.setData(handle);
        systemDialerIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.v(this, "calling startActivity for default dialer: %s", systemDialerIntent);
        mContext.startActivityAsUser(systemDialerIntent, UserHandle.CURRENT);
    }

    /**
     * Check whether or not this is an emergency number, in order to enforce the restriction
     * that only the CALL_PRIVILEGED and CALL_EMERGENCY intents are allowed to make emergency
     * calls.
     *
     * @param number number to inspect in order to determine whether or not an emergency number
     * is being dialed
     * @return True if the handle is an emergency number.
     */
    private boolean isEmergencyNumber(String number) {
        Log.v(this, "Checking restrictions for number : %s", Log.pii(number));
        if (number == null) return false;
        try {
            return mContext.getSystemService(TelephonyManager.class).isEmergencyNumber(
                    number);
        } catch (UnsupportedOperationException uoe) {
            Log.w(this, "isEmergencyNumber: Telephony not supported");
            return false;
        } catch (Exception e) {
            Log.e(this, e, "isEmergencyNumber: Telephony threw an exception.");
            return false;
        }
    }

    /**
     * Given a call intent and whether or not the number to dial is an emergency number, determine
     * the appropriate call intent action.
     *
     * @param intent Intent to evaluate
     * @param isEmergencyNumber Whether or not the number is an emergency
     * number.
     * @return The appropriate action.
     */
    private String calculateCallIntentAction(Intent intent, boolean isEmergencyNumber) {
        String action = intent.getAction();

        /* Change CALL_PRIVILEGED into CALL or CALL_EMERGENCY as needed. */
        if (Intent.ACTION_CALL_PRIVILEGED.equals(action)) {
            if (isEmergencyNumber) {
                Log.i(this, "ACTION_CALL_PRIVILEGED is used while the number is a"
                        + " emergency number. Using ACTION_CALL_EMERGENCY as an action instead.");
                action = Intent.ACTION_CALL_EMERGENCY;
            } else {
                action = Intent.ACTION_CALL;
            }
            Log.v(this, " - updating action from CALL_PRIVILEGED to %s", action);
        }
        return action;
    }

    private long getDisconnectTimeoutFromApp(Bundle resultExtras, long defaultTimeout) {
        if (resultExtras != null) {
            long disconnectTimeout = resultExtras.getLong(
                    TelecomManager.EXTRA_NEW_OUTGOING_CALL_CANCEL_TIMEOUT, defaultTimeout);
            if (disconnectTimeout < 0) {
                disconnectTimeout = 0;
            }
            return Math.min(disconnectTimeout,
                    Timeouts.getMaxNewOutgoingCallCancelMillis(mContext.getContentResolver()));
        } else {
            return defaultTimeout;
        }
    }
}
