/*
 * Copyright (c) 2020, Psiphon Inc.
 * All rights reserved.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package com.psiphon3.psiphonlibrary;

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.VpnService;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.NfcEvent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.appcompat.app.AlertDialog;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TabHost;
import android.widget.TabHost.OnTabChangeListener;
import android.widget.TabHost.TabSpec;
import android.widget.TextView;
import android.widget.Toast;

import com.psiphon3.StatusActivity;
import com.psiphon3.TunnelState;
import com.psiphon3.psicash.PsiCashClient;
import com.psiphon3.psicash.PsiCashException;
import com.psiphon3.psicash.util.BroadcastIntent;
import com.psiphon3.psiphonlibrary.StatusList.StatusListViewManager;
import com.psiphon3.psiphonlibrary.Utils.MyLog;
import com.psiphon3.subscription.R;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.SharedPreferencesImport;

import org.achartengine.ChartFactory;
import org.achartengine.GraphicalView;
import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;
import org.achartengine.renderer.XYMultipleSeriesRenderer;
import org.achartengine.renderer.XYSeriesRenderer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;

import static android.nfc.NdefRecord.createMime;

public abstract class MainBase {
    public static abstract class Activity extends LocalizedActivities.AppCompatActivity implements MyLog.ILogger {
        public Activity() {
            Utils.initializeSecureRandom();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            MyLog.setLogger(this);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();

            MyLog.unsetLogger();
        }

        /*
         * Partial MyLog.ILogger implementation
         */

        @Override
        public Context getContext() {
            return this;
        }
    }

    public static abstract class TabbedActivityBase extends Activity implements OnTabChangeListener {
        public static final String STATUS_ENTRY_AVAILABLE = "com.psiphon3.MainBase.TabbedActivityBase.STATUS_ENTRY_AVAILABLE";
        public static final String INTENT_EXTRA_PREVENT_AUTO_START = "com.psiphon3.MainBase.TabbedActivityBase.PREVENT_AUTO_START";
        protected static final String ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION = "askedToAccessCoarseLocationPermission";
        protected static final String CURRENT_TAB = "currentTab";
        protected static final String CURRENT_PURCHASE = "currentPurchase";

        protected static final int REQUEST_CODE_PREPARE_VPN = 100;
        protected static final int REQUEST_CODE_VPN_PREFERENCES = 102;
        protected static final int REQUEST_CODE_PROXY_PREFERENCES = 103;
        protected static final int REQUEST_CODE_MORE_PREFERENCES = 104;
        protected static final int REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION = 105;

        public static final String HOME_TAB_TAG = "home_tab_tag";
        public static final String PSICASH_TAB_TAG = "psicash_tab_tag";
        public static final String STATISTICS_TAB_TAG = "statistics_tab_tag";
        public static final String SETTINGS_TAB_TAG = "settings_tab_tag";
        public static final String LOGS_TAB_TAG = "logs_tab_tag";


        protected Button m_toggleButton;
        protected ProgressBar m_connectionProgressBar;

        private StatusListViewManager m_statusListManager = null;
        protected AppPreferences m_multiProcessPreferences;
        protected SponsorHomePage m_sponsorHomePage;
        private LocalBroadcastManager m_localBroadcastManager;
        private TextView m_elapsedConnectionTimeView;
        private TextView m_totalSentView;
        private TextView m_totalReceivedView;
        private DataTransferGraph m_slowSentGraph;
        private DataTransferGraph m_slowReceivedGraph;
        private DataTransferGraph m_fastSentGraph;
        private DataTransferGraph m_fastReceivedGraph;
        private Toast m_invalidProxySettingsToast;
        private Button m_openBrowserButton;
        private LoggingObserver m_loggingObserver;
        private CompositeDisposable compositeDisposable = new CompositeDisposable();
        protected TunnelServiceInteractor tunnelServiceInteractor;
        private StatusEntryAdded m_statusEntryAddedBroadcastReceiver;
        protected StatusActivity.OptionsTabFragment m_optionsTabFragment;

        private BroadcastReceiver broadcastReceiver;
        protected Disposable startUpInterstitialDisposable;

        public TabbedActivityBase() {
            Utils.initializeSecureRandom();
        }

        // Lateral navigation with TabHost:
        // Adapted from here:
        // http://danielkvist.net/code/animated-tabhost-with-slide-gesture-in-android
        private static final int ANIMATION_TIME = 240;
        protected TabHost m_tabHost;
        protected List<TabSpec> m_tabSpecsList;
        protected HorizontalScrollView m_tabsScrollView;
        private int m_currentTab;
        private View m_previousView;
        private View m_currentView;
        private GestureDetector m_gestureDetector;
        protected enum TabIndex {HOME, STATISTICS, OPTIONS, LOGS}

        /**
         * A gesture listener that listens for a left or right swipe and uses
         * the swip gesture to navigate a TabHost that uses an AnimatedTabHost
         * listener.
         * 
         * @author Daniel Kvist
         * 
         */
        class LateralGestureDetector extends SimpleOnGestureListener {
            private static final int SWIPE_MIN_DISTANCE = 120;
            private static final int SWIPE_MAX_OFF_PATH = 250;
            private static final int SWIPE_THRESHOLD_VELOCITY = 200;
            private final int maxTabs;

            /**
             * An empty constructor that uses the tabhosts content view to
             * decide how many tabs there are.
             */
            public LateralGestureDetector() {
                maxTabs = m_tabHost.getTabContentView().getChildCount();
            }

            /**
             * Listens for the onFling event and performs some calculations
             * between the touch down point and the touch up point. It then uses
             * that information to calculate if the swipe was long enough. It
             * also uses the swiping velocity to decide if it was a "true" swipe
             * or just some random touching.
             */
            @Override
            public boolean onFling(MotionEvent event1, MotionEvent event2, float velocityX, float velocityY) {
                if (event1 != null && event2 != null) {
                    // Determine tab swipe direction
                    int direction;
                    if (Math.abs(event1.getY() - event2.getY()) > SWIPE_MAX_OFF_PATH) {
                        return false;
                    }
                    if (event1.getX() - event2.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe right to left
                        direction = 1;
                    } else if (event2.getX() - event1.getX() > SWIPE_MIN_DISTANCE && Math.abs(velocityX) > SWIPE_THRESHOLD_VELOCITY) {
                        // Swipe left to right
                        direction = -1;
                    } else {
                        return false;
                    }

                    // Move in direction until we hit a visible tab, or go out of bounds
                    int newTab = m_currentTab + direction;
                    while (newTab >= 0 && newTab < maxTabs && m_tabHost.getTabWidget().getChildTabViewAt(newTab).getVisibility() != View.VISIBLE) {
                        newTab += direction;
                    }

                    if (newTab < 0 || newTab > (maxTabs - 1)) {
                        return false;
                    }

                    m_tabHost.setCurrentTab(newTab);
                }
                return super.onFling(event1, event2, velocityX, velocityY);
            }
        }

        /**
         * When tabs change we fetch the current view that we are animating to
         * and animate it and the previous view in the appropriate directions.
         */
        @Override
        public void onTabChanged(String tabId) {
            m_currentView = m_tabHost.getCurrentView();
            if (m_previousView != null) {
                if (m_tabHost.getCurrentTab() > m_currentTab) {
                    m_previousView.setAnimation(outToLeftAnimation());
                    m_currentView.setAnimation(inFromRightAnimation());
                } else {
                    m_previousView.setAnimation(outToRightAnimation());
                    m_currentView.setAnimation(inFromLeftAnimation());
                }
            }
            m_previousView = m_currentView;
            m_currentTab = m_tabHost.getCurrentTab();

            m_multiProcessPreferences.put(CURRENT_TAB, m_currentTab);

            // Also scroll to the corresponding tab label if it is not fully in view
            View tabView = m_tabHost.getTabWidget().getChildTabViewAt(m_tabHost.getCurrentTab());
            int vLeft = tabView.getLeft();
            int vRight = tabView.getRight();
            int sScrollOffset = m_tabsScrollView.getScrollX();
            int sWidth = m_tabsScrollView.getWidth();

            if (vLeft < sScrollOffset) {
                m_tabsScrollView.smoothScrollTo(vLeft, 0);
            } else if (vRight > sWidth + sScrollOffset) {
                m_tabsScrollView.smoothScrollTo(vRight - sWidth, 0);
            }

        }

        /**
         * Custom animation that animates in from right
         * 
         * @return Animation the Animation object
         */
        private Animation inFromRightAnimation() {
            Animation inFromRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromRight);
        }

        /**
         * Custom animation that animates out to the right
         * 
         * @return Animation the Animation object
         */
        private Animation outToRightAnimation() {
            Animation outToRight = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outToRight);
        }

        /**
         * Custom animation that animates in from left
         * 
         * @return Animation the Animation object
         */
        private Animation inFromLeftAnimation() {
            Animation inFromLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT, 0.0f,
                    Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(inFromLeft);
        }

        /**
         * Custom animation that animates out to the left
         * 
         * @return Animation the Animation object
         */
        private Animation outToLeftAnimation() {
            Animation outtoLeft = new TranslateAnimation(Animation.RELATIVE_TO_PARENT, 0.0f, Animation.RELATIVE_TO_PARENT, -1.0f, Animation.RELATIVE_TO_PARENT,
                    0.0f, Animation.RELATIVE_TO_PARENT, 0.0f);
            return setProperties(outtoLeft);
        }

        /**
         * Helper method that sets some common properties
         * 
         * @param animation
         *            the animation to give common properties
         * @return the animation with common properties
         */
        private Animation setProperties(Animation animation) {
            animation.setDuration(ANIMATION_TIME);
            animation.setInterpolator(new AccelerateInterpolator());
            return animation;
        }

        @SuppressLint("SetJavaScriptEnabled")
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            m_multiProcessPreferences = new AppPreferences(this);
            // Migrate 'More Options' SharedPreferences to tray preferences:
            // The name of the DefaultSharedPreferences is this.getPackageName() + "_preferences"
            // http://stackoverflow.com/questions/5946135/difference-between-getdefaultsharedpreferences-and-getsharedpreferences
            String prefName = this.getPackageName() + "_preferences";
            m_multiProcessPreferences.migrate(
                    // Top level  preferences
                    new SharedPreferencesImport(this, prefName, CURRENT_TAB, CURRENT_TAB),
                    new SharedPreferencesImport(this, prefName, getString(R.string.egressRegionPreference), getString(R.string.egressRegionPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference)),
                    // More Options preferences
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAllAppsInVpn), getString(R.string.preferenceIncludeAllAppsInVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAppsInVpn), getString(R.string.preferenceIncludeAppsInVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAppsInVpnString), getString(R.string.preferenceIncludeAppsInVpnString)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpn), getString(R.string.preferenceExcludeAppsFromVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceLanguageSelection), getString(R.string.preferenceLanguageSelection))
            );

            EmbeddedValues.initialize(this);
            tunnelServiceInteractor = new TunnelServiceInteractor(getApplicationContext());

            // remove logs from previous sessions
            if (!tunnelServiceInteractor.isServiceRunning(getApplicationContext())) {
                LoggingProvider.LogDatabaseHelper.truncateLogs(this, true);
            }

            // Listen to GOT_NEW_EXPIRING_PURCHASE intent from psicash module
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE);
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, android.content.Intent intent) {
                    String action = intent.getAction();
                    if (action != null) {
                        if (action.equals(BroadcastIntent.GOT_NEW_EXPIRING_PURCHASE)) {
                            tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), TabbedActivityBase.this::startTunnel, false);
                        }
                    }
                }
            };
            LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);
        }

        @Override
        protected void onDestroy() {
            super.onDestroy();
            tunnelServiceInteractor.onDestroy(getApplicationContext());
            if (m_sponsorHomePage != null) {
                m_sponsorHomePage.stop();
                m_sponsorHomePage = null;
            }
            compositeDisposable.dispose();
            LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
            m_localBroadcastManager.unregisterReceiver(m_statusEntryAddedBroadcastReceiver);
            m_statusListManager.onDestroy();
        }

        protected void setupActivityLayout() {
            // Set up tabs
            m_tabHost.setup();

            m_tabSpecsList.clear();
            m_tabSpecsList.add(TabIndex.HOME.ordinal(), m_tabHost.newTabSpec(HOME_TAB_TAG).setContent(R.id.homeTab).setIndicator(getText(R.string.home_tab_name)));
            m_tabSpecsList.add(TabIndex.STATISTICS.ordinal(), m_tabHost.newTabSpec(STATISTICS_TAB_TAG).setContent(R.id.statisticsView).setIndicator(getText(R.string.statistics_tab_name)));
            m_tabSpecsList.add(TabIndex.OPTIONS.ordinal(), m_tabHost.newTabSpec(SETTINGS_TAB_TAG).setContent(R.id.settingsView).setIndicator(getText(R.string.settings_tab_name)));
            m_tabSpecsList.add(TabIndex.LOGS.ordinal(), m_tabHost.newTabSpec(LOGS_TAB_TAG).setContent(R.id.logsTab).setIndicator(getText(R.string.logs_tab_name)));

            for (TabSpec tabSpec : m_tabSpecsList) {
                m_tabHost.addTab(tabSpec);
            }

            m_gestureDetector = new GestureDetector(this, new LateralGestureDetector());
            OnTouchListener onTouchListener = new OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    // Give the view a chance to handle the event first, ie a
                    // scrollview or listview
                    v.onTouchEvent(event);

                    return !m_gestureDetector.onTouchEvent(event);
                }
            };

            m_tabHost.setOnTouchListener(onTouchListener);
            findViewById(R.id.psicashContainer).setOnTouchListener(onTouchListener);
            findViewById(R.id.statisticsView).setOnTouchListener(onTouchListener);
            findViewById(R.id.settingsView).setOnTouchListener(onTouchListener);
            ListView statusListView = (ListView) findViewById(R.id.statusList);
            statusListView.setOnTouchListener(onTouchListener);

            int currentTab = m_multiProcessPreferences.getInt(CURRENT_TAB, 0);
            m_currentTab = currentTab;

            // We need to delay this call until m_tabHost is fully inflated because we are
            // calculating scrolling offsets in the onTabChanged(). This is achieved by using
            // View.post(Runnable)
            m_tabHost.post(() -> m_tabHost.setCurrentTab(currentTab));

            // Set TabChangedListener after restoring last tab to avoid triggering an interstitial,
            // we only want interstitial to be triggered by user actions
            m_tabHost.setOnTabChangedListener(this);

            m_elapsedConnectionTimeView = (TextView) findViewById(R.id.elapsedConnectionTime);
            m_totalSentView = (TextView) findViewById(R.id.totalSent);
            m_totalReceivedView = (TextView) findViewById(R.id.totalReceived);
            m_openBrowserButton = (Button) findViewById(R.id.openBrowserButton);

            m_slowSentGraph = new DataTransferGraph(this, R.id.slowSentGraph);
            m_slowReceivedGraph = new DataTransferGraph(this, R.id.slowReceivedGraph);
            m_fastSentGraph = new DataTransferGraph(this, R.id.fastSentGraph);
            m_fastReceivedGraph = new DataTransferGraph(this, R.id.fastReceivedGraph);

            // Set up the list view
            m_statusListManager = new StatusListViewManager(statusListView);

            m_statusEntryAddedBroadcastReceiver = new StatusEntryAdded();
            m_localBroadcastManager = LocalBroadcastManager.getInstance(this);
            m_localBroadcastManager.registerReceiver(m_statusEntryAddedBroadcastReceiver, new IntentFilter(STATUS_ENTRY_AVAILABLE));

            // The LoggingObserver will run in a separate thread than the main UI thread
            HandlerThread loggingObserverThread = new HandlerThread("LoggingObserverThread");
            loggingObserverThread.start();
            m_loggingObserver = new LoggingObserver(this, new Handler(loggingObserverThread.getLooper()));

            // Force the UI to display logs already loaded into the StatusList message history
            LocalBroadcastManager.getInstance(this).sendBroadcast(new Intent(STATUS_ENTRY_AVAILABLE));

            compositeDisposable.addAll(
                    tunnelServiceInteractor.tunnelStateFlowable()
                            // Update app UI state
                            .doOnNext(state -> runOnUiThread(() -> updateServiceStateUI(state)))
                            .subscribe(),

                    tunnelServiceInteractor.dataStatsFlowable()
                            .startWith(Boolean.FALSE)
                            .doOnNext(isConnected -> runOnUiThread(() -> updateStatisticsUICallback(isConnected)))
                            .subscribe()
            );
        }

        @Override
        protected void onStart() {
            super.onStart();
            tunnelServiceInteractor.onStart(getApplicationContext());
        }

        @Override
        protected void onStop() {
            super.onStop();
            tunnelServiceInteractor.onStop(getApplicationContext());
        }

        @Override
        protected void onResume() {
            super.onResume();

            // Load new logs from the logging provider now
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                m_loggingObserver.dispatchChange(false, LoggingProvider.INSERT_URI);
            } else {
                m_loggingObserver.dispatchChange(false);
            }

            // Load new logs from the logging provider when it changes
            getContentResolver().registerContentObserver(LoggingProvider.INSERT_URI, true, m_loggingObserver);

            // Don't show the keyboard until edit selected
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

            // Update the state of UI when resuming the activity with latest tunnel state if
            // we are not in process of starting the tunnel service.
            // This will ensure proper state of the VPN toggle button if user clicked Cancel Request
            // on the VPN permission prompt, for example.
            compositeDisposable.add(tunnelServiceInteractor.tunnelStateFlowable()
                    .firstOrError()
                    .observeOn(AndroidSchedulers.mainThread())
                    .doOnSuccess(this::updateServiceStateUI)
                    .subscribe());
        }

        @Override
        protected void onPause() {
            super.onPause();

            getContentResolver().unregisterContentObserver(m_loggingObserver);
            cancelInvalidProxySettingsToast();
        }

        protected void doToggle() {
            compositeDisposable.add(
                    tunnelServiceInteractor.tunnelStateFlowable()
                            .firstOrError()
                            .doOnSuccess(state -> {
                                if (state.isRunning()) {
                                    stopTunnelService();
                                } else {
                                    startUp();
                                }
                            })
                            .subscribe()
            );
        }

        public class StatusEntryAdded extends BroadcastReceiver {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (m_statusListManager != null) {
                    m_statusListManager.notifyStatusAdded();
                }
            }
        }

        final protected String PsiCashModifyUrl(String originalUrlString) {
            if (TextUtils.isEmpty(originalUrlString)) {
                return originalUrlString;
            }

            try {
                return PsiCashClient.getInstance(getContext()).modifiedHomePageURL(originalUrlString);
            } catch (PsiCashException e) {
                MyLog.g("PsiCash: error modifying home page: " + e);
            }
            return originalUrlString;
        }

        protected abstract void startUp();

        protected void onRegionSelected(String selectedRegionCode) {
            String egressRegionPreference = m_multiProcessPreferences
                    .getString(getString(R.string.egressRegionPreference),
                            PsiphonConstants.REGION_CODE_ANY);
            if (selectedRegionCode.equals(egressRegionPreference)) {
                return;
            }

            // Store the selection in preferences
            m_multiProcessPreferences.put(getString(R.string.egressRegionPreference), selectedRegionCode);

            // NOTE: reconnects even when Any is selected: we could select a
            // faster server
            tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel, true);
        }

        // Basic check of proxy settings values
        private boolean customProxySettingsValuesValid() {
            boolean useHTTPProxyPreference = UpstreamProxySettings.getUseHTTPProxy(this);
            boolean useCustomProxySettingsPreference = UpstreamProxySettings.getUseCustomProxySettings(this);

            if (!useHTTPProxyPreference ||
                    !useCustomProxySettingsPreference) {
                return true;
            }
            UpstreamProxySettings.ProxySettings proxySettings = UpstreamProxySettings.getProxySettings(this);
            boolean isValid = proxySettings != null &&
                    proxySettings.proxyHost.length() > 0 &&
                    proxySettings.proxyPort >= 1 &&
                    proxySettings.proxyPort <= 65535;
            if (!isValid) {
                runOnUiThread(() -> {
                    cancelInvalidProxySettingsToast();
                    m_invalidProxySettingsToast = Toast.makeText(this, R.string.network_proxy_connect_invalid_values, Toast.LENGTH_SHORT);
                    m_invalidProxySettingsToast.show();
                });
            }
            return isValid;
        }

        private class DataTransferGraph {
            private final Activity m_activity;
            private final LinearLayout m_graphLayout;
            private GraphicalView m_chart;
            private final XYMultipleSeriesDataset m_chartDataset;
            private final XYMultipleSeriesRenderer m_chartRenderer;
            private final XYSeries m_chartCurrentSeries;
            private final XYSeriesRenderer m_chartCurrentRenderer;

            public DataTransferGraph(Activity activity, int layoutId) {
                m_activity = activity;
                m_graphLayout = (LinearLayout) activity.findViewById(layoutId);
                m_chartDataset = new XYMultipleSeriesDataset();
                m_chartRenderer = new XYMultipleSeriesRenderer();
                m_chartRenderer.setGridColor(Color.GRAY);
                m_chartRenderer.setShowGrid(true);
                m_chartRenderer.setShowLabels(false);
                m_chartRenderer.setShowLegend(false);
                m_chartRenderer.setShowAxes(false);
                m_chartRenderer.setPanEnabled(false, false);
                m_chartRenderer.setZoomEnabled(false, false);

                // Make the margins transparent.
                // Note that this value is a bit magical. One would expect
                // android.graphics.Color.TRANSPARENT to work, but it doesn't.
                // Nor does 0x00000000. Ref:
                // http://developer.android.com/reference/android/graphics/Color.html
                m_chartRenderer.setMarginsColor(0x00FFFFFF);

                m_chartCurrentSeries = new XYSeries("");
                m_chartDataset.addSeries(m_chartCurrentSeries);
                m_chartCurrentRenderer = new XYSeriesRenderer();
                m_chartCurrentRenderer.setColor(Color.YELLOW);
                m_chartRenderer.addSeriesRenderer(m_chartCurrentRenderer);
            }

            public void update(ArrayList<Long> data) {
                m_chartCurrentSeries.clear();
                for (int i = 0; i < data.size(); i++) {
                    m_chartCurrentSeries.add(i, data.get(i));
                }
                if (m_chart == null) {
                    m_chart = ChartFactory.getLineChartView(m_activity, m_chartDataset, m_chartRenderer);
                    m_graphLayout.addView(m_chart);
                } else {
                    m_chart.repaint();
                }
            }
        }

        private void updateStatisticsUICallback(boolean isConnected) {
            DataTransferStats.DataTransferStatsForUI dataTransferStats = DataTransferStats.getDataTransferStatsForUI();
            m_elapsedConnectionTimeView.setText(isConnected ? getString(R.string.connected_elapsed_time,
                    Utils.elapsedTimeToDisplay(dataTransferStats.getElapsedTime())) : getString(R.string.disconnected));
            m_totalSentView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesSent(), false));
            m_totalReceivedView.setText(Utils.byteCountToDisplaySize(dataTransferStats.getTotalBytesReceived(), false));
            m_slowSentGraph.update(dataTransferStats.getSlowSentSeries());
            m_slowReceivedGraph.update(dataTransferStats.getSlowReceivedSeries());
            m_fastSentGraph.update(dataTransferStats.getFastSentSeries());
            m_fastReceivedGraph.update(dataTransferStats.getFastReceivedSeries());
        }

        private void cancelInvalidProxySettingsToast() {
            if (m_invalidProxySettingsToast != null) {
                View toastView = m_invalidProxySettingsToast.getView();
                if (toastView != null) {
                    if (toastView.isShown()) {
                        m_invalidProxySettingsToast.cancel();
                    }
                }
            }
        }

        private void updateServiceStateUI(final TunnelState tunnelState) {
            // Do not update the UI if we are in process of starting a tunnel
            if (startUpInterstitialDisposable != null &&
                    !startUpInterstitialDisposable.isDisposed()) {
                return;
            }

            if (tunnelState.isUnknown()) {
                disableToggleServiceUI();
                m_openBrowserButton.setEnabled(false);
                m_toggleButton.setText(getText(R.string.waiting));
                m_connectionProgressBar.setVisibility(View.INVISIBLE);
            } else if (tunnelState.isRunning()) {
                enableToggleServiceUI();
                m_toggleButton.setText(getText(R.string.stop));
                if (tunnelState.connectionData().isConnected()) {
                    m_openBrowserButton.setEnabled(true);
                    m_connectionProgressBar.setVisibility(View.INVISIBLE);
                    ArrayList<String> homePages = tunnelState.connectionData().homePages();
                    final String url;
                    if (homePages != null && homePages.size() > 0) {
                        url = homePages.get(0);
                    } else {
                        url = null;
                    }
                    m_openBrowserButton.setOnClickListener(view -> displayBrowser(this, url));
                } else {
                    m_openBrowserButton.setEnabled(false);
                    m_connectionProgressBar.setVisibility(View.VISIBLE);
                }
            } else {
                // Service not running
                enableToggleServiceUI();
                m_toggleButton.setText(getText(R.string.start));
                m_openBrowserButton.setEnabled(false);
                m_connectionProgressBar.setVisibility(View.INVISIBLE);
            }
        }

        protected void enableToggleServiceUI() {
            m_toggleButton.setEnabled(true);
        }

        protected void disableToggleServiceUI() {
            m_toggleButton.setText(getText(R.string.waiting));
            m_toggleButton.setEnabled(false);
        }

        protected void startTunnel() {
            // Tunnel core needs this dangerous permission to obtain the WiFi BSSID, which is used
            // as a key for applying tactics
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                            == PackageManager.PERMISSION_GRANTED) {
                proceedStartTunnel();
            } else {
                AppPreferences mpPreferences = new AppPreferences(this);
                if (mpPreferences.getBoolean(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, false)) {
                    proceedStartTunnel();
                } else if(!this.isFinishing()){
                    final Context context = this;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            new AlertDialog.Builder(context)
                                    .setCancelable(false)
                                    .setOnKeyListener(
                                            new DialogInterface.OnKeyListener() {
                                                @Override
                                                public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                                    // Don't dismiss when hardware search button is clicked (Android 2.3 and earlier)
                                                    return keyCode == KeyEvent.KEYCODE_SEARCH;
                                                }})
                                    .setTitle(R.string.MainBase_AccessCoarseLocationPermissionPromptTitle)
                                    .setMessage(R.string.MainBase_AccessCoarseLocationPermissionPromptMessage)
                                    .setPositiveButton(R.string.MainBase_AccessCoarseLocationPermissionPositiveButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    ActivityCompat.requestPermissions(TabbedActivityBase.this,
                                                            new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                                                            REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION);
                                                }})
                                    .setNegativeButton(R.string.MainBase_AccessCoarseLocationPermissionNegativeButton,
                                            new DialogInterface.OnClickListener() {
                                                @Override
                                                public void onClick(DialogInterface dialog, int whichButton) {
                                                    m_multiProcessPreferences.put(ASKED_TO_ACCESS_COARSE_LOCATION_PERMISSION, true);
                                                    proceedStartTunnel();
                                                }})
                                    .setOnCancelListener(
                                            new DialogInterface.OnCancelListener() {
                                                @Override
                                                public void onCancel(DialogInterface dialog) {
                                                    // Do nothing (this prompt may reappear)
                                                }})
                                    .show();
                        }
                    });
                }
            }
        }

        @Override
        public void onRequestPermissionsResult(int requestCode,
                                               String permissions[], int[] grantResults) {
            switch (requestCode) {
                case REQUEST_CODE_PERMISSIONS_REQUEST_ACCESS_COARSE_LOCATION:
                    proceedStartTunnel();
                    break;

                default:
                    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
            }
        }

        private void proceedStartTunnel() {
            // Don't start if custom proxy settings is selected and values are invalid
            if (!customProxySettingsValuesValid()) {
                return;
            }
            boolean waitingForPrompt = doVpnPrepare();
            if (!waitingForPrompt) {
                startAndBindTunnelService();
            }
        }

        protected boolean doVpnPrepare() {
            
            // Devices without VpnService support throw various undocumented
            // exceptions, including ActivityNotFoundException and ActivityNotFoundException.
            // For example: http://code.google.com/p/ics-openvpn/source/browse/src/de/blinkt/openvpn/LaunchVPN.java?spec=svn2a81c206204193b14ac0766386980acdc65bee60&name=v0.5.23&r=2a81c206204193b14ac0766386980acdc65bee60#376
            try {
                return vpnPrepare();
            } catch (Exception e) {
                MyLog.e(R.string.tunnel_whole_device_exception, MyLog.Sensitivity.NOT_SENSITIVE);
                // true = waiting for prompt, although we can't start the
                // activity so onActivityResult won't be called
                return true;
            }
        }

        @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
        protected boolean vpnPrepare() throws ActivityNotFoundException {
            // VpnService: need to display OS user warning. If whole device
            // option is
            // selected and we expect to use VpnService, so the prompt here in
            // the UI
            // before starting the service.

            Intent intent = VpnService.prepare(this);
            if (intent != null) {
                // TODO: can we disable the mode before we reach this this
                // failure point with
                // resolveActivity()? We'll need the intent from prepare() or
                // we'll have to mimic it.
                // http://developer.android.com/reference/android/content/pm/PackageManager.html#resolveActivity%28android.content.Intent,%20int%29

                startActivityForResult(intent, REQUEST_CODE_PREPARE_VPN);

                // startAndBindTunnelService will be called in onActivityResult
                return true;
            }

            return false;
        }

        private boolean vpnSettingsRestartRequired() {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.moreOptionsPreferencesName), MODE_PRIVATE);

            // check if selected routing preference has changed
            boolean tunnelAll = prefs.getBoolean(getString(R.string.preferenceIncludeAllAppsInVpn), true);
            boolean tunnelAllNew = m_multiProcessPreferences.getBoolean(getString(R.string.preferenceIncludeAllAppsInVpn), true);
            if (tunnelAll != tunnelAllNew) {
                return true;
            }

            boolean tunnelSelected = prefs.getBoolean(getString(R.string.preferenceIncludeAppsInVpn), false);
            boolean tunnelSelectedNew = m_multiProcessPreferences.getBoolean(getString(R.string.preferenceIncludeAppsInVpn), false);
            if (tunnelSelected != tunnelSelectedNew) {
                return true;
            }

            // check if the selected apps changed
            if (tunnelSelected) {
                String tunnelSelectedString = prefs.getString(getString(R.string.preferenceIncludeAppsInVpnString), "");
                String tunnelSelectedStringNew = m_multiProcessPreferences.getString(getString(R.string.preferenceIncludeAppsInVpnString), "");
                if (!tunnelSelectedString.equals(tunnelSelectedStringNew)) {
                    return true;
                }
            }

            boolean tunnelNotSelected = prefs.getBoolean(getString(R.string.preferenceExcludeAppsFromVpn), false);
            boolean tunnelNotSelectedNew = m_multiProcessPreferences.getBoolean(getString(R.string.preferenceExcludeAppsFromVpn), false);
            if (tunnelNotSelected != tunnelNotSelectedNew) {
                return true;
            }

            // check if the selected apps changed
            if (tunnelNotSelected) {
                String tunnelNotSelectedString = prefs.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
                String tunnelNotSelectedStringNew = m_multiProcessPreferences.getString(getString(R.string.preferenceExcludeAppsFromVpnString), "");
                if (!tunnelNotSelectedString.equals(tunnelNotSelectedStringNew)) {
                    return true;
                }
            }
            return false;
        }

        private boolean proxySettingsRestartRequired() {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.moreOptionsPreferencesName), MODE_PRIVATE);

            // check if "use proxy" has changed
            boolean useHTTPProxyPreference = prefs.getBoolean(getString(R.string.useProxySettingsPreference),
                    false);
            if (useHTTPProxyPreference != UpstreamProxySettings.getUseHTTPProxy(this)) {
                return true;
            }

            // no further checking if "use proxy" is off and has not
            // changed
            if (!useHTTPProxyPreference) {
                return false;
            }

            //check if "add custom headers" checkbox changed
            boolean addCustomHeadersPreference = prefs.getBoolean(
                    getString(R.string.addCustomHeadersPreference), false);
            if (addCustomHeadersPreference != UpstreamProxySettings.getAddCustomHeadersPreference(this)) {
                return true;
            }

            // "add custom headers" is selected, check if
            // upstream headers string has changed
            if (addCustomHeadersPreference) {
                JSONObject newHeaders = new JSONObject();

                for (int position = 1; position <= 6; position++) {
                    int nameID = getResources().getIdentifier("customProxyHeaderName" + position, "string", getPackageName());
                    int valueID = getResources().getIdentifier("customProxyHeaderValue" + position, "string", getPackageName());

                    String namePrefStr = getResources().getString(nameID);
                    String valuePrefStr = getResources().getString(valueID);

                    String name = prefs.getString(namePrefStr, "");
                    String value = prefs.getString(valuePrefStr, "");
                    try {
                        if (!TextUtils.isEmpty(name)) {
                            JSONArray arr = new JSONArray();
                            arr.put(value);
                            newHeaders.put(name, arr);
                        }
                    } catch (JSONException e) {
                        throw new RuntimeException(e);
                    }
                }

                JSONObject oldHeaders = UpstreamProxySettings.getUpstreamProxyCustomHeaders(this);

                if (0 != oldHeaders.toString().compareTo(newHeaders.toString())) {
                    return true;
                }
            }

            // check if "use custom proxy settings"
            // radio has changed
            boolean useCustomProxySettingsPreference = prefs.getBoolean(
                    getString(R.string.useCustomProxySettingsPreference), false);
            if (useCustomProxySettingsPreference != UpstreamProxySettings.getUseCustomProxySettings(this)) {
                return true;
            }

            // no further checking if "use custom proxy" is off and has
            // not changed
            if (!useCustomProxySettingsPreference) {
                return false;
            }

            // "use custom proxy" is selected, check if
            // host || port have changed
            if (!prefs.getString(getString(R.string.useCustomProxySettingsHostPreference), "")
                    .equals(UpstreamProxySettings.getCustomProxyHost(this))
                    || !prefs.getString(getString(R.string.useCustomProxySettingsPortPreference), "")
                    .equals(UpstreamProxySettings.getCustomProxyPort(this))) {
                return true;
            }

            // check if "use proxy authentication" has changed
            boolean useProxyAuthenticationPreference = prefs.getBoolean(
                    getString(R.string.useProxyAuthenticationPreference), false);
            if (useProxyAuthenticationPreference != UpstreamProxySettings.getUseProxyAuthentication(this)) {
                return true;
            }

            // no further checking if "use proxy authentication" is off
            // and has not changed
            if (!useProxyAuthenticationPreference) {
                return false;
            }

            // "use proxy authentication" is checked, check if
            // username || password || domain have changed
            return !prefs.getString(getString(R.string.useProxyUsernamePreference), "")
                    .equals(UpstreamProxySettings.getProxyUsername(this))
                    || !prefs.getString(getString(R.string.useProxyPasswordPreference), "")
                    .equals(UpstreamProxySettings.getProxyPassword(this))
                    || !prefs.getString(getString(R.string.useProxyDomainPreference), "")
                    .equals(UpstreamProxySettings.getProxyDomain(this));
        }

        private boolean moreSettingsRestartRequired() {
            SharedPreferences prefs = getSharedPreferences(getString(R.string.moreOptionsPreferencesName), MODE_PRIVATE);

            // check if disable timeouts setting has changed
            boolean disableTimeoutsNewPreference =
                    prefs.getBoolean(getString(R.string.disableTimeoutsPreference), false);
            boolean disableTimeoutsCurrentPreference =
                    m_multiProcessPreferences.getBoolean(getString(R.string.disableTimeoutsPreference), false);
            return disableTimeoutsCurrentPreference != disableTimeoutsNewPreference;
        }

        private void updateVpnSettingsFromPreferences() {
            // Import 'VPN Settings' values to tray preferences
            String prefName = getString(R.string.moreOptionsPreferencesName);
            m_multiProcessPreferences.migrate(
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAllAppsInVpn), getString(R.string.preferenceIncludeAllAppsInVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAppsInVpn), getString(R.string.preferenceIncludeAppsInVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceIncludeAppsInVpnString), getString(R.string.preferenceIncludeAppsInVpnString)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpn), getString(R.string.preferenceExcludeAppsFromVpn)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceExcludeAppsFromVpnString), getString(R.string.preferenceExcludeAppsFromVpnString))
            );
        }

        private void updateProxySettingsFromPreferences() {
            // Import 'Proxy settings' values to tray preferences
            String prefName = getString(R.string.moreOptionsPreferencesName);
            m_multiProcessPreferences.migrate(
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxySettingsPreference), getString(R.string.useProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useSystemProxySettingsPreference), getString(R.string.useSystemProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPreference), getString(R.string.useCustomProxySettingsPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsHostPreference), getString(R.string.useCustomProxySettingsHostPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useCustomProxySettingsPortPreference), getString(R.string.useCustomProxySettingsPortPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyAuthenticationPreference), getString(R.string.useProxyAuthenticationPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyUsernamePreference), getString(R.string.useProxyUsernamePreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyPasswordPreference), getString(R.string.useProxyPasswordPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.useProxyDomainPreference), getString(R.string.useProxyDomainPreference))
            );
        }

        private void updateMoreSettingsFromPreferences() {
            // Import 'More Options' values to tray preferences
            String prefName = getString(R.string.moreOptionsPreferencesName);
            m_multiProcessPreferences.migrate(
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithSound), getString(R.string.preferenceNotificationsWithSound)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.preferenceNotificationsWithVibrate), getString(R.string.preferenceNotificationsWithVibrate)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.downloadWifiOnlyPreference), getString(R.string.downloadWifiOnlyPreference)),
                    new SharedPreferencesImport(this, prefName, getString(R.string.disableTimeoutsPreference), getString(R.string.disableTimeoutsPreference))
            );
        }

        @Override
        protected void onActivityResult(int request, int result, Intent data) {
            boolean shouldRestart = false;
            switch (request) {
                case REQUEST_CODE_PREPARE_VPN:
                    if (result == RESULT_OK) {
                        startAndBindTunnelService();
                    } else if (result == RESULT_CANCELED) {
                        onVpnPromptCancelled();
                    }
                    // We're done here
                    return;

                case REQUEST_CODE_VPN_PREFERENCES:
                    shouldRestart = vpnSettingsRestartRequired();
                    updateVpnSettingsFromPreferences();
                    break;

                case REQUEST_CODE_PROXY_PREFERENCES:
                    shouldRestart = proxySettingsRestartRequired();
                    updateProxySettingsFromPreferences();
                    break;

                case REQUEST_CODE_MORE_PREFERENCES:
                    shouldRestart = moreSettingsRestartRequired();
                    updateMoreSettingsFromPreferences();
                    break;

                default:
                    super.onActivityResult(request, result, data);
            }
            // Update preferences in the options tab
            if (m_optionsTabFragment != null) {
                m_optionsTabFragment.setSummaryFromPreferences();
            }

            if (shouldRestart) {
                // stop if running and custom proxy settings is selected and values are invalid
                if (!customProxySettingsValuesValid()) {
                    tunnelServiceInteractor.tunnelStateFlowable()
                            .filter(tunnelState -> !tunnelState.isUnknown())
                            .firstOrError()
                            .doOnSuccess(state -> {
                                if (state.isRunning()) {
                                    stopTunnelService();
                                }
                            })
                            .subscribe();
                } else {
                    tunnelServiceInteractor.scheduleRunningTunnelServiceRestart(getApplicationContext(), this::startTunnel, true);
                }
            }
            if (data != null && data.getBooleanExtra(MoreOptionsPreferenceActivity.INTENT_EXTRA_LANGUAGE_CHANGED, false)) {
                // This is a bit of a weird hack to cause a restart, but it works
                // Previous attempts to use the alarm manager or others caused a variable amount of wait (up to about a second)
                // before the activity would relaunch. This *seems* to provide the best functionality across phones.
                // Add a 1 second delay to give activity chance to restart the service if needed
                new Handler().postDelayed(() -> {
                    finish();
                    Intent intent = new Intent(this, StatusActivity.class);
                    intent.putExtra(INTENT_EXTRA_PREVENT_AUTO_START, true);
                    startActivity(intent);
                    System.exit(1);
                }, shouldRestart ? 1000 : 0);
            }
            else {
                super.onActivityResult(request, result, data);
            }
        }

        protected void onVpnPromptCancelled() {}

        protected void startAndBindTunnelService() {
            tunnelServiceInteractor.startTunnelService(getApplicationContext());
        }

        private void stopTunnelService() {
            tunnelServiceInteractor.stopTunnelService();
        }

        /**
         * Determine if the Psiphon local service is currently running.
         * 
         * @see <a href="http://stackoverflow.com/a/5921190/729729">From
         *      StackOverflow answer:
         *      "android: check if a service is running"</a>
         * @return True if the service is already running, false otherwise.
         */
        protected class SponsorHomePage {
            private class SponsorWebChromeClient extends WebChromeClient {
                private final ProgressBar mProgressBar;

                public SponsorWebChromeClient(ProgressBar progressBar) {
                    super();
                    mProgressBar = progressBar;
                }

                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                }

                @Override
                public void onProgressChanged(WebView webView, int progress) {
                    if (mStopped) {
                        return;
                    }

                    mProgressBar.setProgress(progress);
                    mProgressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
                }
            }

            private class SponsorWebViewClient extends WebViewClient {
                private Timer mTimer;
                private boolean mWebViewLoaded = false;
                private boolean mStopped = false;

                public void stop() {
                    mStopped = true;
                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }
                }

                @Override
                public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                    if (mStopped) {
                        return true;
                    }

                    if (mTimer != null) {
                        mTimer.cancel();
                        mTimer = null;
                    }

                    if (mWebViewLoaded) {
                        // Do not PsiCash modify the URL, this is a link on the landing page
                        // that has been clicked
                        displayBrowser(getContext(), url, false);
                    }
                    return mWebViewLoaded;
                }

                @Override
                public void onPageFinished(WebView webView, String url) {
                    if (mStopped) {
                        return;
                    }

                    if (!mWebViewLoaded) {
                        mTimer = new Timer();
                        mTimer.schedule(new TimerTask() {
                            @Override
                            public void run() {
                                if (mStopped) {
                                    return;
                                }
                                mWebViewLoaded = true;
                            }
                        }, 2000);
                    }
                }
            }

            private final WebView mWebView;
            private final SponsorWebViewClient mWebViewClient;
            private final SponsorWebChromeClient mWebChromeClient;
            private final ProgressBar mProgressBar;

            @TargetApi(Build.VERSION_CODES.HONEYCOMB)
            public SponsorHomePage(WebView webView, ProgressBar progressBar) {
                mWebView = webView;
                mProgressBar = progressBar;
                mWebChromeClient = new SponsorWebChromeClient(mProgressBar);
                mWebViewClient = new SponsorWebViewClient();

                mWebView.setWebChromeClient(mWebChromeClient);
                mWebView.setWebViewClient(mWebViewClient);
                
                WebSettings webSettings = mWebView.getSettings();
                webSettings.setJavaScriptEnabled(true);
                webSettings.setDomStorageEnabled(true);
                webSettings.setLoadWithOverviewMode(true);
                webSettings.setUseWideViewPort(true);
            }

            public void stop() {
                mWebViewClient.stop();
                mWebChromeClient.stop();
            }

            public void load(String url) {
                mProgressBar.setVisibility(View.VISIBLE);
                mWebView.loadUrl(url);
            }
        }

        protected void displayBrowser(Context context, String url, boolean b) {

        }

        final protected void displayBrowser(Context context, String urlString) {
            // PsiCash modify URLs by default
            displayBrowser(context, urlString, true);
        }

        protected boolean shouldLoadInEmbeddedWebView(String url) {
            for (String homeTabUrlExclusion : EmbeddedValues.HOME_TAB_URL_EXCLUSIONS) {
                if (url.contains(homeTabUrlExclusion)) {
                    return false;
                }
            }
            return true;
        }
    }
}
