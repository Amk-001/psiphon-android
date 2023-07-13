package com.psiphon3.billing;

import android.content.Context;
import android.text.TextUtils;
import android.util.Pair;

import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.Purchase;
import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.TunnelState;
import com.psiphon3.log.MyLog;
import com.psiphon3.psiphonlibrary.Authorization;
import com.psiphon3.psiphonlibrary.EmbeddedValues;
import com.psiphon3.subscription.BuildConfig;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

public class PurchaseVerifier {
    private static final String PREFERENCE_PURCHASE_AUTHORIZATION_ID = "preferencePurchaseAuthorization";
    private static final String PREFERENCE_PURCHASE_TOKEN = "preferencePurchaseToken";

    private final AppPreferences appPreferences;
    private final Context context;
    private final VerificationResultListener verificationResultListener;
    private GooglePlayBillingHelper repository;

    private PublishRelay<TunnelState> tunnelConnectionStatePublishRelay = PublishRelay.create();
    private CompositeDisposable compositeDisposable = new CompositeDisposable();
    private Set<String> invalidPurchaseTokensSet = new HashSet<>();

    public PurchaseVerifier(@NonNull Context context, @NonNull VerificationResultListener verificationResultListener) {
        this.context = context;
        this.appPreferences = new AppPreferences(context);
        this.repository = GooglePlayBillingHelper.getInstance(context);
        this.verificationResultListener = verificationResultListener;

        compositeDisposable.add(subscriptionVerificationDisposable());
        compositeDisposable.add(psiCashPurchaseVerificationDisposable());
        queryAllPurchases();
    }

    private Flowable<TunnelState> tunnelConnectionStateFlowable() {
        return tunnelConnectionStatePublishRelay
                .distinctUntilChanged()
                .toFlowable(BackpressureStrategy.LATEST);
    }

    private Disposable psiCashPurchaseVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    // Once connected run IAB check and pass PsiCash purchase and
                    // current tunnel state connection data downstream.
                    if (tunnelState.isRunning() && tunnelState.connectionData().isConnected()) {
                        return repository.purchaseStateFlowable()
                                .flatMapIterable(PurchaseState::purchaseList)
                                // Only pass through PsiCash purchases that we didn't previously
                                // marked as invalid
                                .filter(purchase -> {
                                    if (purchase == null || !GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                                        return false;
                                    }
                                    // Check if we previously marked this purchase as 'bad'
                                    if (invalidPurchaseTokensSet.size() > 0 &&
                                            invalidPurchaseTokensSet.contains(purchase.getPurchaseToken())) {
                                        MyLog.w("PurchaseVerifier: bad PsiCash purchase, continue.");
                                        return false;
                                    }
                                    return true;
                                })
                                .map(purchase -> {
                                    final AppPreferences mp = new AppPreferences(context);
                                    final String psiCashCustomData = mp.getString(context.getString(R.string.persistentPsiCashCustomData), "");
                                    return new Pair<>(purchase, psiCashCustomData);
                                })

                                // We want to avoid trying to redeem the same purchase multiple times
                                // so we consider purchases distinct only if their purchase tokens and
                                // order IDs differ and are ignoring all other fields such as isAcknowledged
                                // which may change anytime for a purchase we have seen already.
                                //
                                // See comments in GooglePlayBillingHelper::processPurchases for more
                                // details on the purchase acknowledgement.
                                //
                                // UPDATE: we also want to (re)try purchase verification in case the PsiCash
                                // custom data has changed due to user login status change.
                                .distinctUntilChanged((a, b) -> {
                                    final Purchase purchaseA = a.first;
                                    final Purchase purchaseB = b.first;
                                    final String customDataA = a.second;
                                    final String customDataB = b.second;

                                    return purchaseA.getPurchaseToken().equals(purchaseB.getPurchaseToken()) &&
                                            purchaseA.getOrderId().equals(purchaseB.getOrderId()) &&
                                            customDataA.equals(customDataB);
                                })
                                .map(pair -> new Pair<>(pair, tunnelState.connectionData()));
                    }
                    // Not connected, do nothing
                    return Flowable.empty();
                })
                // Do not use switchMap here, run the verification in full for each distinct purchase
                .flatMap(pair -> {
                    final Purchase purchase = pair.first.first;
                    final String psiCashCustomData = pair.first.second;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (TextUtils.isEmpty(psiCashCustomData)) {
                        MyLog.w("PurchaseVerifier: error: can't redeem PsiCash purchase, custom data is empty");
                        return Flowable.empty();
                    }

                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .withCustomData(psiCashCustomData)
                                    .build();

                    MyLog.i("PurchaseVerifier: will try and redeem PsiCash purchase.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .flatMap(json -> {
                                        // Purchase redeemed, consume and send PSICASH_PURCHASE_REDEEMED
                                        return repository.consumePurchase(purchase)
                                                .map(__ -> VerificationResult.PSICASH_PURCHASE_REDEEMED)
                                                .toFlowable()
                                                .retryWhen(errors ->
                                                        errors.zipWith(Flowable.range(1, 5), (err, i) -> {
                                                            if (i < 5) {
                                                                if (err instanceof GooglePlayBillingHelper.BillingException) {
                                                                    GooglePlayBillingHelper.BillingException billingException = (GooglePlayBillingHelper.BillingException) err;
                                                                    int errorCode = billingException.getBillingResultResponseCode();
                                                                    // Only retry the cases the developer guide suggests should be retried, see
                                                                    // https://developer.android.com/reference/com/android/billingclient/api/BillingClient.BillingResponseCode
                                                                    if (errorCode == BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE ||
                                                                            errorCode == BillingClient.BillingResponseCode.BILLING_UNAVAILABLE ||
                                                                            errorCode == BillingClient.BillingResponseCode.ERROR ||
                                                                            errorCode == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED) {
                                                                        // exponential backoff with timer
                                                                        int retryInSeconds = (int) Math.pow(1.5, i);
                                                                        MyLog.w("PurchaseVerifier: will retry consuming the purchase in " + retryInSeconds + " seconds");
                                                                        return Flowable.timer((long) retryInSeconds, TimeUnit.SECONDS);
                                                                    }
                                                                }
                                                            } // else
                                                            return Flowable.error(err);
                                                        }).flatMap(x -> x));
                                    }
                            )
                            .doOnError(e -> {
                                MyLog.e("PurchaseVerifier: verifying PsiCash purchase failed with error: " + e);
                            })
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(verificationResultListener::onVerificationResult)
                .subscribe();
    }

    private Disposable subscriptionVerificationDisposable() {
        return tunnelConnectionStateFlowable()
                .switchMap(tunnelState -> {
                    if (!(tunnelState.isRunning() && tunnelState.connectionData().isConnected())) {
                        // Not connected, do nothing
                        return Flowable.empty();
                    }
                    // Once connected run IAB check and pass the subscription state and
                    // current tunnel state connection data downstream.
                    return repository.subscriptionStateFlowable()
                            .map(subscriptionState -> new Pair<>(subscriptionState, tunnelState.connectionData()));
                })
                .switchMap(pair -> {
                    final SubscriptionState subscriptionState = pair.first;
                    final TunnelState.ConnectionData connectionData = pair.second;

                    if (subscriptionState.error() != null) {
                        MyLog.w("PurchaseVerifier: continue due to subscription check error: " + subscriptionState.error());
                        return Flowable.empty();
                    }

                    if (!subscriptionState.hasValidPurchase()) {
                        if (BuildConfig.SUBSCRIPTION_SPONSOR_ID.equals(connectionData.sponsorId())) {
                            MyLog.i("PurchaseVerifier: user has no subscription, will restart as non subscriber.");
                            return Flowable.just(VerificationResult.RESTART_AS_NON_SUBSCRIBER);
                        } else {
                            MyLog.i("PurchaseVerifier: user has no subscription, continue.");
                            return Flowable.empty();
                        }
                    }

                    final Purchase purchase = subscriptionState.purchase();

                    // Check if we previously marked this purchase as 'bad'
                    if (invalidPurchaseTokensSet.size() > 0 &&
                            invalidPurchaseTokensSet.contains(purchase.getPurchaseToken())) {
                        MyLog.w("PurchaseVerifier: bad subscription purchase, continue.");
                        return Flowable.empty();
                    }

                    // Otherwise check if we already have an authorization for this token
                    String persistedPurchaseToken = appPreferences.getString(PREFERENCE_PURCHASE_TOKEN, "");
                    String persistedPurchaseAuthorizationId = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    if (persistedPurchaseToken.equals(purchase.getPurchaseToken()) &&
                            !persistedPurchaseAuthorizationId.isEmpty()) {
                        MyLog.i("PurchaseVerifier: already have authorization for this purchase, continue.");
                        // We already aware of this purchase, do nothing
                        return Flowable.empty();
                    }

                    // We have a fresh purchase. Store the purchase token and reset the persisted authorization Id
                    MyLog.i("PurchaseVerifier: user has new valid subscription purchase.");
                    appPreferences.put(PREFERENCE_PURCHASE_TOKEN, purchase.getPurchaseToken());
                    appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

                    // Now try and fetch authorization for this purchase
                    PurchaseVerificationNetworkHelper purchaseVerificationNetworkHelper =
                            new PurchaseVerificationNetworkHelper.Builder(context)
                                    .withConnectionData(connectionData)
                                    .build();

                    MyLog.i("PurchaseVerifier: will try and fetch new authorization.");
                    return purchaseVerificationNetworkHelper.verifyFlowable(purchase)
                            .map(json -> {
                                        // Note that response with other than 200 HTTP code from the server is
                                        // treated the same as a 200 OK response with empty payload and should result
                                        // in connection restart as a non-subscriber.

                                        if (TextUtils.isEmpty(json)) {
                                            // If payload is empty then do not try to JSON decode,
                                            // remember the bad token and restart as non-subscriber.
                                            invalidPurchaseTokensSet.add(purchase.getPurchaseToken());
                                            MyLog.w("PurchaseVerifier: subscription verification: server returned empty payload.");
                                            return VerificationResult.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        String encodedAuth = new JSONObject(json).getString("signed_authorization");
                                        Authorization authorization = Authorization.fromBase64Encoded(encodedAuth);
                                        if (authorization == null) {
                                            // Expired or invalid purchase,
                                            // remember the bad token and restart as non-subscriber.
                                            invalidPurchaseTokensSet.add(purchase.getPurchaseToken());
                                            MyLog.w("PurchaseVerifier: subscription verification: server returned empty authorization.");
                                            return VerificationResult.RESTART_AS_NON_SUBSCRIBER;
                                        }

                                        // Persist authorization ID and authorization.
                                        appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, authorization.Id());
                                        Authorization.storeAuthorization(context, authorization);

                                        MyLog.i("PurchaseVerifier: subscription verification: server returned new authorization.");

                                        return VerificationResult.RESTART_AS_SUBSCRIBER;
                                    }
                            )
                            .doOnError(e -> {
                                if (e instanceof PurchaseVerificationNetworkHelper.FatalException) {
                                    invalidPurchaseTokensSet.add(purchase.getPurchaseToken());
                                }
                                MyLog.e("PurchaseVerifier: subscription verification: fetching authorization failed with error: " + e);
                            })
                            // If we fail HTTP request after all retries for whatever reason do not
                            // restart connection as a non-subscriber. The user may have a legit purchase
                            // and while we can't upgrade the connection we should try and not show home
                            // pages at least.
                            .onErrorResumeNext(Flowable.empty());

                })
                .doOnNext(verificationResultListener::onVerificationResult)
                .subscribe();
    }

    public Single<String> sponsorIdSingle() {
        return repository.subscriptionStateFlowable()
                .firstOrError()
                .map(subscriptionState -> {
                            if (subscriptionState.hasValidPurchase()) {
                                // Check if we previously marked the purchase as 'bad'
                                String purchaseToken = subscriptionState.purchase().getPurchaseToken();
                                if (invalidPurchaseTokensSet.size() > 0 &&
                                        invalidPurchaseTokensSet.contains(purchaseToken)) {
                                    MyLog.i("PurchaseVerifier: will start with non-subscription sponsor ID due to invalid purchase");
                                    return EmbeddedValues.SPONSOR_ID;
                                }
                                MyLog.i("PurchaseVerifier: will start with subscription sponsor ID");
                                return BuildConfig.SUBSCRIPTION_SPONSOR_ID;
                            }
                            MyLog.i("PurchaseVerifier: will start with non-subscription sponsor ID");
                            return EmbeddedValues.SPONSOR_ID;
                        }
                );
    }

    public void onTunnelState(TunnelState tunnelState) {
        tunnelConnectionStatePublishRelay.accept(tunnelState);
    }

    public void onActiveAuthorizationIDs(List<String> acceptedAuthorizationIds) {
        String purchaseAuthorizationID = appPreferences.getString(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");

        if (TextUtils.isEmpty(purchaseAuthorizationID)) {
            // There is no persisted authorization, do nothing
            return;
        }

        // If server hasn't accepted any authorizations or persisted authorization ID hasn't been
        // accepted then reset persisted purchase token and trigger new IAB check
        if (acceptedAuthorizationIds.isEmpty() || !acceptedAuthorizationIds.contains(purchaseAuthorizationID)) {
            MyLog.i("PurchaseVerifier: persisted purchase authorization ID is not active, will query subscription status.");
            appPreferences.put(PREFERENCE_PURCHASE_TOKEN, "");
            appPreferences.put(PREFERENCE_PURCHASE_AUTHORIZATION_ID, "");
            repository.queryAllPurchases();
        } else {
            MyLog.i("PurchaseVerifier: subscription authorization accepted, continue.");
        }
    }

    public void onDestroy() {
        compositeDisposable.dispose();
    }

    public void queryAllPurchases() {
        repository.startIab();
        repository.queryAllPurchases();
    }

    public Observable<Boolean> hasPendingPsiCashPurchaseObservable() {
        return repository.purchaseStateFlowable()
                .map(PurchaseState::purchaseList)
                .distinctUntilChanged()
                .toObservable()
                .switchMap(purchaseList -> {
                    for (Purchase purchase : purchaseList) {
                        if (GooglePlayBillingHelper.isPsiCashPurchase(purchase)) {
                            return Observable.just(Boolean.TRUE);
                        }
                    }
                    return Observable.just(Boolean.FALSE);
                });
    }

    public enum VerificationResult {
        RESTART_AS_NON_SUBSCRIBER,
        RESTART_AS_SUBSCRIBER,
        PSICASH_PURCHASE_REDEEMED,
    }

    public interface VerificationResultListener {
        void onVerificationResult(VerificationResult action);
    }
}
