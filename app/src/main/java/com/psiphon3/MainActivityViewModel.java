package com.psiphon3;

import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.jakewharton.rxrelay2.PublishRelay;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.TunnelServiceInteractor;
import com.psiphon3.psiphonlibrary.UpstreamProxySettings;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;

public class MainActivityViewModel extends AndroidViewModel implements LifecycleObserver {
    private final TunnelServiceInteractor tunnelServiceInteractor;
    private final BroadcastReceiver broadcastReceiver;
    private final PublishRelay<Boolean> customProxyValidationResultRelay = PublishRelay.create();
    private final PublishRelay<Object> availableRegionsSelectionRelay = PublishRelay.create();
    private final PublishRelay<Object> openVpnSettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openProxySettingsRelay = PublishRelay.create();
    private final PublishRelay<Object> openMoreOptionsRelay = PublishRelay.create();
    private PublishRelay<String> externalBrowserUrlRelay = PublishRelay.create();
    private PublishRelay<String> lastLogEntryRelay = PublishRelay.create();
    private boolean isFirstRun = true;

    public MainActivityViewModel(@NonNull Application application) {
        super(application);
        tunnelServiceInteractor = new TunnelServiceInteractor(getApplication(), true);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BroadcastIntent.TUNNEL_RESTART);
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action != null) {
                    if (BroadcastIntent.TUNNEL_RESTART.equals(action)) {
                        tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplication().getApplicationContext(), false);
                    }
                }
            }
        };
        LocalBroadcastManager.getInstance(getApplication()).registerReceiver(broadcastReceiver, intentFilter);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    protected void onLifeCycleStop() {
        tunnelServiceInteractor.onStop(getApplication());
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    protected void onLifeCycleStart() {
        tunnelServiceInteractor.onStart(getApplication());
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        LocalBroadcastManager.getInstance(getApplication().getApplicationContext()).unregisterReceiver(broadcastReceiver);
        tunnelServiceInteractor.onDestroy(getApplication());
    }

    public Flowable<TunnelState> tunnelStateFlowable() {
        return tunnelServiceInteractor.tunnelStateFlowable();
    }

    public Flowable<Boolean> dataStatsFlowable() {
        return tunnelServiceInteractor.dataStatsFlowable();
    }

    public void stopTunnelService() {
        tunnelServiceInteractor.stopTunnelService();
    }

    public void startTunnelService() {
        tunnelServiceInteractor.startTunnelService(getApplication());
    }

    public void restartTunnelService() {
        tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplication(), true);
    }

    public void restartTunnelService(boolean resetReconnectFlag) {
        tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplication(), resetReconnectFlag);
    }

    public void sendLocaleChangedMessage() {
        tunnelServiceInteractor.sendLocaleChangedMessage();
    }

    // Basic check of proxy settings values
    public boolean validateCustomProxySettings() {
        boolean useHTTPProxyPreference = UpstreamProxySettings.getUseHTTPProxy(getApplication());
        boolean useCustomProxySettingsPreference = UpstreamProxySettings.getUseCustomProxySettings(getApplication());

        if (!useHTTPProxyPreference ||
                !useCustomProxySettingsPreference) {
            return true;
        }
        UpstreamProxySettings.ProxySettings proxySettings = UpstreamProxySettings.getProxySettings(getApplication());
        boolean isValid = proxySettings != null &&
                UpstreamProxySettings.isValidProxyHostName(proxySettings.proxyHost) &&
                UpstreamProxySettings.isValidProxyPort(proxySettings.proxyPort);

        customProxyValidationResultRelay.accept(isValid);

        return isValid;
    }

    public Flowable<Boolean> customProxyValidationResultFlowable() {
        return customProxyValidationResultRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalAvailableRegionsUpdate() {
        availableRegionsSelectionRelay.accept(new Object());
    }

    public Flowable<Object> updateAvailableRegionsFlowable() {
        return availableRegionsSelectionRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenVpnSettings() {
        openVpnSettingsRelay.accept(new Object());
    }

    public Flowable<Object> openVpnSettingsFlowable() {
        return openVpnSettingsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenProxySettings() {
        openProxySettingsRelay.accept(new Object());
    }

    public Flowable<Object> openProxySettingsFlowable() {
        return openProxySettingsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalOpenMoreOptions() {
        openMoreOptionsRelay.accept(new Object());
    }

    public Flowable<Object> openMoreOptionsFlowable() {
        return openMoreOptionsRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalExternalBrowserUrl(String url) {
        externalBrowserUrlRelay.accept(url);
    }

    public Flowable<String> externalBrowserUrlFlowable() {
        return externalBrowserUrlRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public void signalLastLogEntryAdded(String log) {
        lastLogEntryRelay.accept(log);
    }

    public Flowable<String> lastLogEntryFlowable() {
        return lastLogEntryRelay.toFlowable(BackpressureStrategy.LATEST);
    }

    public boolean isFirstRun() {
        return isFirstRun;
    }

    public void setFirstRun(boolean firstRun) {
        isFirstRun = firstRun;
    }

    public boolean isServiceRunning(Context context) {
        return tunnelServiceInteractor.isServiceRunning(context);
    }

    public void notifyTunnelServiceResume() {
        tunnelServiceInteractor.onResume();
    }
}
