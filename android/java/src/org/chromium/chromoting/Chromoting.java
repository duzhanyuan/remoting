// Copyright 2013 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package org.chromium.chromoting;

import android.annotation.SuppressLint;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;
import android.support.v4.graphics.drawable.DrawableCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.ContextMenu;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import org.chromium.base.Log;
import org.chromium.chromoting.accountswitcher.AccountSwitcher;
import org.chromium.chromoting.accountswitcher.AccountSwitcherFactory;
import org.chromium.chromoting.base.OAuthTokenFetcher;
import org.chromium.chromoting.help.HelpContext;
import org.chromium.chromoting.help.HelpSingleton;
import org.chromium.chromoting.jni.Client;
import org.chromium.chromoting.jni.ConnectionListener;
import org.chromium.chromoting.jni.JniInterface;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * The user interface for querying and displaying a user's host list from the directory server. It
 * also requests and renews authentication tokens using the system account manager.
 */
public class Chromoting extends AppCompatActivity implements ConnectionListener,
        AccountSwitcher.Callback, HostListManager.Callback, View.OnClickListener {
    private static final String TAG = "Chromoting";

    /** Only accounts of this type will be selectable for authentication. */
    private static final String ACCOUNT_TYPE = "com.google";

    /** Scope to use when fetching the OAuth token. */
    private static final String TOKEN_SCOPE = "oauth2:https://www.googleapis.com/auth/chromoting "
            + "https://www.googleapis.com/auth/googletalk";

    /** Result code used for starting {@link DesktopActivity}. */
    public static final int DESKTOP_ACTIVITY = 0;

    /** Preference names for storing selected and recent accounts. */
    private static final String PREFERENCE_SELECTED_ACCOUNT = "account_name";
    private static final String PREFERENCE_RECENT_ACCOUNT_PREFIX = "recent_account_";
    private static final String PREFERENCE_EXPERIMENTAL_FLAGS = "flags";

    /** User's account name (email). */
    private String mAccount;

    /** Helper for fetching the host list. */
    private HostListManager mHostListManager;

    /** List of hosts. */
    private HostInfo[] mHosts = new HostInfo[0];

    /** Refresh button. */
    private MenuItem mRefreshButton;

    /** Host list chooser view shown when at least one host is configured. */
    private ListView mHostListView;

    /** View shown when the user has no configured hosts or host list couldn't be retrieved. */
    private View mEmptyView;

    /** Progress view shown instead of the host list when the host list is loading. */
    private View mProgressView;

    /** Dialog for reporting connection progress. */
    private ProgressDialog mProgressIndicator;

    /**
     * Helper used by SessionConnection for session authentication. Receives onNewIntent()
     * notifications to handle third-party authentication.
     */
    private SessionAuthenticator mAuthenticator;

    private OAuthTokenConsumer mHostConnectingConsumer;

    private OAuthTokenConsumer mHostListRetrievingConsumer;

    private OAuthTokenConsumer mHostDeletingConsumer;

    private DrawerLayout mDrawerLayout;

    private ActionBarDrawerToggle mDrawerToggle;

    /**
     * Task to be run after the navigation drawer is closed. Can be null. This is used to run
     * Help/Feedback tasks which require a screenshot with the drawer closed.
     */
    private Runnable mPendingDrawerCloseTask;

    private AccountSwitcher mAccountSwitcher;

    /** The currently-connected Client, if any. */
    private Client mClient;

    /** Shows a warning explaining that a Google account is required, then closes the activity. */
    private void showNoAccountsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.noaccounts_message);
        builder.setPositiveButton(R.string.noaccounts_add_account,
                new DialogInterface.OnClickListener() {
                    @SuppressLint("InlinedApi")
                    @Override
                    public void onClick(DialogInterface dialog, int id) {
                        Intent intent = new Intent(Settings.ACTION_ADD_ACCOUNT);
                        intent.putExtra(Settings.EXTRA_ACCOUNT_TYPES,
                                new String[] { ACCOUNT_TYPE });
                        ChromotingUtil.startActivitySafely(Chromoting.this, intent);
                        finish();
                    }
                });
        builder.setNegativeButton(R.string.close, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int id) {
                    finish();
                }
            });
        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    finish();
                }
            });

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * Displays the loading indicator. Currently this also hides the host list, but that may
     * change.
     */
    private void showHostListLoadingIndicator() {
        mHostListView.setVisibility(View.GONE);
        mEmptyView.setVisibility(View.GONE);
        mProgressView.setVisibility(View.VISIBLE);
    }

    /**
     * Shows the appropriate view for the host list and hides the loading indicator. Shows either
     * the host list chooser or the host list empty view, depending on whether mHosts contains any
     * hosts.
     */
    private void updateHostListView() {
        mHostListView.setVisibility(mHosts.length == 0 ? View.GONE : View.VISIBLE);
        mEmptyView.setVisibility(mHosts.length == 0 ? View.VISIBLE : View.GONE);
        mProgressView.setVisibility(View.GONE);
    }

    private void runPendingDrawerCloseTask() {
        // Avoid potential recursion problems by null-ing the task first.
        Runnable task = mPendingDrawerCloseTask;
        mPendingDrawerCloseTask = null;
        if (task != null) {
            task.run();
        }
    }

    private void closeDrawerThenRun(Runnable task) {
        mPendingDrawerCloseTask = task;
        if (mDrawerLayout.isDrawerOpen(Gravity.START)) {
            mDrawerLayout.closeDrawer(Gravity.START);
        } else {
            runPendingDrawerCloseTask();
        }
    }

    /** Closes any navigation drawer, then shows the Help screen. */
    public void launchHelp(final HelpContext helpContext) {
        closeDrawerThenRun(new Runnable() {
            @Override
            public void run() {
                HelpSingleton.getInstance().launchHelp(Chromoting.this, helpContext);
            }
        });
    }

    /** Closes any navigation drawer, then shows the Feedback screen. */
    public void launchFeedback() {
        closeDrawerThenRun(new Runnable() {
            @Override
            public void run() {
                HelpSingleton.getInstance().launchFeedback(Chromoting.this);
            }
        });
    }

    /**
     * Called when the activity is first created. Loads the native library and requests an
     * authentication token from the system.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mHostListManager = new HostListManager();

        // Get ahold of our view widgets.
        mHostListView = (ListView) findViewById(R.id.hostList_chooser);
        registerForContextMenu(mHostListView);
        mEmptyView = findViewById(R.id.hostList_empty);
        mHostListView.setOnItemClickListener(
                new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position,
                            long id) {
                        onHostClicked(position);
                    }
                });

        mProgressView = findViewById(R.id.hostList_progress);

        findViewById(R.id.host_setup_link_android).setOnClickListener(this);

        mDrawerLayout = (DrawerLayout) findViewById(R.id.drawer_layout);
        mDrawerToggle = new ActionBarDrawerToggle(this, mDrawerLayout, toolbar, 0, 0) {
            @Override
            public void onDrawerClosed(View drawerView) {
                super.onDrawerClosed(drawerView);
                runPendingDrawerCloseTask();
            }
        };
        mDrawerLayout.addDrawerListener(mDrawerToggle);

        // Disable the hamburger icon animation. This is more complex than it ought to be.
        // The animation can be customized by tweaking some style parameters - see
        // http://developer.android.com/reference/android/support/v7/appcompat/R.styleable.html#DrawerArrowToggle .
        // But these can't disable the animation completely.
        // The icon can only be changed by disabling the drawer indicator, which has side-effects
        // that must be worked around. It disables the built-in click listener, so this has to be
        // implemented and added. This also requires that the toolbar be passed to the
        // ActionBarDrawerToggle ctor above (otherwise the listener is ignored and warnings are
        // logged).
        // Also, the animation itself is a private implementation detail - it is not possible to
        // simply access the first frame of the animation. And the hamburger menu icon doesn't
        // exist as a builtin Android resource, so it has to be provided as an application
        // resource instead (R.drawable.ic_menu). And, on Lollipop devices and above, it should be
        // tinted to match the colorControlNormal theme attribute.
        mDrawerToggle.setDrawerIndicatorEnabled(false);
        mDrawerToggle.setToolbarNavigationClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        if (mDrawerLayout.isDrawerOpen(Gravity.START)) {
                            mDrawerLayout.closeDrawer(Gravity.START);
                        } else {
                            mDrawerLayout.openDrawer(Gravity.START);
                        }
                    }
                });

        // Set the three-line icon instead of the default which is a tinted arrow icon.
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        Drawable menuIcon = ContextCompat.getDrawable(this, R.drawable.ic_menu);
        DrawableCompat.setTint(menuIcon.mutate(),
                ChromotingUtil.getColorAttribute(this, R.attr.colorControlNormal));
        getSupportActionBar().setHomeAsUpIndicator(menuIcon);
        getSupportActionBar().setHomeActionContentDescription(R.string.actionbar_menu);

        mAccountSwitcher = AccountSwitcherFactory.getInstance().createAccountSwitcher(this, this);
        mAccountSwitcher.setNavigation(NavigationMenuAdapter.createNavigationMenu(this));
        LinearLayout navigationDrawer = (LinearLayout) findViewById(R.id.navigation_drawer);
        mAccountSwitcher.setDrawer(navigationDrawer);
        View switcherView = mAccountSwitcher.getView();
        switcherView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        navigationDrawer.addView(switcherView, 0);

        mHostConnectingConsumer = new OAuthTokenConsumer(this, TOKEN_SCOPE);
        mHostListRetrievingConsumer = new OAuthTokenConsumer(this, TOKEN_SCOPE);
        mHostDeletingConsumer = new OAuthTokenConsumer(this, TOKEN_SCOPE);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);
        mDrawerToggle.syncState();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);

        if (mAuthenticator != null) {
            mAuthenticator.onNewIntent(intent);
        }
    }

    /**
     * Called when the activity becomes visible. This happens on initial launch and whenever the
     * user switches to the activity, for example, by using the window-switcher or when coming from
     * the device's lock screen.
     */
    @Override
    public void onStart() {
        super.onStart();

        getSupportActionBar().setTitle(R.string.mode_me2me);

        // Load any previously-selected account and recents from Preferences.
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);

        String selected = prefs.getString(PREFERENCE_SELECTED_ACCOUNT, null);

        ArrayList<String> recents = new ArrayList<String>();
        for (int i = 0;; i++) {
            String prefName = PREFERENCE_RECENT_ACCOUNT_PREFIX + i;
            String recent = prefs.getString(prefName, null);
            if (recent != null) {
                recents.add(recent);
            } else {
                break;
            }
        }

        String[] recentsArray = recents.toArray(new String[recents.size()]);
        mAccountSwitcher.setSelectedAndRecentAccounts(selected, recentsArray);
        mAccountSwitcher.reloadAccounts();
    }

    @Override
    protected void onPause() {
        super.onPause();

        String[] recents = mAccountSwitcher.getRecentAccounts();

        SharedPreferences.Editor preferences = getPreferences(MODE_PRIVATE).edit();
        if (mAccount != null) {
            preferences.putString(PREFERENCE_SELECTED_ACCOUNT, mAccount);
        }

        for (int i = 0; i < recents.length; i++) {
            String prefName = PREFERENCE_RECENT_ACCOUNT_PREFIX + i;
            preferences.putString(prefName, recents[i]);
        }

        preferences.apply();
    }

    /** Called when the activity is finally finished. */
    @Override
    public void onDestroy() {
        super.onDestroy();
        mAccountSwitcher.destroy();

        // TODO(lambroslambrou): Determine whether we really need to tear down the connection here,
        // so we can remove this code.
        if (mClient != null) {
            mClient.destroy();
            mClient = null;
        }
    }

    /** Called when a child Activity exits and sends a result back to this Activity. */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        mAccountSwitcher.onActivityResult(requestCode, resultCode, data);

        if (requestCode == OAuthTokenFetcher.REQUEST_CODE_RECOVER_FROM_OAUTH_ERROR) {
            if (resultCode == RESULT_OK) {
                // User gave OAuth permission to this app (or recovered from any OAuth failure),
                // so retry fetching the token.

                // We actually don't know which consumer triggers the startActivityForResult() but
                // refreshing the host list is the safest action.
                // TODO(yuweih): Distinguish token consumer.
                refreshHostList();
            } else {
                // User denied permission or cancelled the dialog, so cancel the request.
                updateHostListView();
            }
        }
    }

    /** Called when a permissions request has returned. */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        // This is currently only used by AccountSwitcherBasic.
        // Check that the user has granted the needed permission, and reload the accounts.
        // Otherwise, assume something unexpected occurred, or the user cancelled the request.
        if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            mAccountSwitcher.reloadAccounts();
        } else if (permissions.length == 0) {
            Log.e(TAG, "User cancelled the permission request.");
        } else {
            Log.e(TAG, "Permission %s was not granted.", permissions[0]);
        }
    }

    /** Called when the display is rotated (as registered in the manifest). */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        mDrawerToggle.onConfigurationChanged(newConfig);
    }

    private static int getHostIndexForMenu(ContextMenu.ContextMenuInfo menuInfo) {
        return ((AdapterView.AdapterContextMenuInfo) menuInfo).position;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v,
            ContextMenu.ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        if (v.getId() == R.id.hostList_chooser) {
            getMenuInflater().inflate(R.menu.host_context_menu, menu);
            HostInfo info = mHosts[getHostIndexForMenu(menuInfo)];
            menu.setHeaderTitle(info.name);
        }
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        int hostIndex = getHostIndexForMenu(item.getMenuInfo());
        if (itemId == R.id.connect) {
            onHostClicked(hostIndex);
        } else if (itemId == R.id.delete) {
            onDeleteHostClicked(hostIndex);
        } else {
            return false;
        }
        return true;
    }

    /** Called to initialize the action bar. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chromoting_actionbar, menu);
        mRefreshButton = menu.findItem(R.id.actionbar_directoryrefresh);

        if (mAccount == null) {
            // If there is no account, don't allow the user to refresh the listing.
            mRefreshButton.setEnabled(false);
        }

        ChromotingUtil.tintMenuIcons(this, menu);

        return super.onCreateOptionsMenu(menu);
    }

    /** Called whenever an action bar button is pressed. */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (mDrawerToggle.onOptionsItemSelected(item)) {
            return true;
        }

        int id = item.getItemId();
        if (id == R.id.actionbar_directoryrefresh) {
            refreshHostList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Called when the user touches hyperlinked text. */
    @Override
    public void onClick(View view) {
        launchHelp(HelpContext.HOST_SETUP);
    }

    private void onDeleteHostClicked(int hostIndex) {
        HostInfo hostInfo = mHosts[hostIndex];
        final String hostId = hostInfo.id;
        String message = getString(R.string.confirm_host_delete_android, hostInfo.name);
        new AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton(android.R.string.yes,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                deleteHost(hostId);
                                dialog.dismiss();
                            }
                        })
                .setNegativeButton(android.R.string.no, null)
                .create().show();
    }

    /** Called when the user taps on a host entry. */
    private void onHostClicked(int index) {
        HostInfo host = mHosts[index];
        if (host.isOnline) {
            connectToHost(host);
        } else {
            String tooltip = host.getHostOfflineReasonText(this);
            Toast.makeText(this, tooltip, Toast.LENGTH_SHORT).show();
        }
    }

    private void connectToHost(final HostInfo host) {
        if (mClient != null) {
            mClient.destroy();
        }

        mClient = new Client();
        mProgressIndicator = ProgressDialog.show(
                this,
                host.name,
                getString(R.string.footer_connecting),
                true,
                true,
                new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialog) {
                        if (mClient != null) {
                            mClient.destroy();
                            mClient = null;
                        }
                    }
                });

        final SessionConnector connector =
                new SessionConnector(mClient, this, this, mHostListManager);
        mAuthenticator = new SessionAuthenticator(this, mClient, host);
        mHostConnectingConsumer.consume(mAccount, new OAuthTokenFetcher.Callback() {
            @Override
            public void onTokenFetched(String token) {
                connector.connectToHost(mAccount, token, host, mAuthenticator,
                        getPreferences(MODE_PRIVATE).getString(PREFERENCE_EXPERIMENTAL_FLAGS, ""));
            }

            @Override
            public void onError(OAuthTokenFetcher.Error error) {
                showAuthErrorMessage(error);
            }
        });
    }

    private void showAuthErrorMessage(OAuthTokenFetcher.Error error) {
        String explanation = getString(error == OAuthTokenFetcher.Error.NETWORK
                ? R.string.error_network_error : R.string.error_unexpected);
        Toast.makeText(Chromoting.this, explanation, Toast.LENGTH_LONG).show();
    }

    private void refreshHostList() {
        showHostListLoadingIndicator();

        // The refresh button simply makes use of the currently-chosen account.
        mHostListRetrievingConsumer.consume(mAccount, new OAuthTokenFetcher.Callback() {
            @Override
            public void onTokenFetched(String token) {
                mHostListManager.retrieveHostList(token, Chromoting.this);
            }

            @Override
            public void onError(OAuthTokenFetcher.Error error) {
                showAuthErrorMessage(error);
                updateHostListView();
            }
        });
    }

    private void deleteHost(final String hostId) {
        showHostListLoadingIndicator();

        mHostDeletingConsumer.consume(mAccount, new OAuthTokenFetcher.Callback() {
            @Override
            public void onTokenFetched(String token) {
                mHostListManager.deleteHost(token, hostId, Chromoting.this);
            }

            @Override
            public void onError(OAuthTokenFetcher.Error error) {
                showAuthErrorMessage(error);
                updateHostListView();
            }
        });
    }

    @Override
    public void onAccountSelected(String accountName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            String logInAnnouncement = getString(R.string.log_in_account_description, accountName);
            mAccountSwitcher.getView().announceForAccessibility(logInAnnouncement);
        }
        mAccount = accountName;
        JniInterface.setAccountForLogging(accountName);

        // The current host list is no longer valid for the new account, so clear the list.
        mHosts = new HostInfo[0];
        updateUi();
        refreshHostList();
    }

    @Override
    public void onAccountsListEmpty() {
        showNoAccountsDialog();
    }

    @Override
    public void onRequestCloseDrawer() {
        mDrawerLayout.closeDrawers();
    }

    @Override
    public void onHostListReceived(HostInfo[] hosts) {
        // Store a copy of the array, so that it can't be mutated by the HostListManager. HostInfo
        // is an immutable type, so a shallow copy of the array is sufficient here.
        mHosts = Arrays.copyOf(hosts, hosts.length);
        updateHostListView();
        updateUi();
    }

    @Override
    public void onHostUpdated() {
        // Not implemented Yet.
    }

    @Override
    public void onHostDeleted() {
        // Refresh the host list. there is no need to refetch the auth token again.
        // onHostListReceived is in charge to hide the progress indicator.
        mHostListManager.retrieveHostList(mHostDeletingConsumer.getLatestToken(), this);
    }

    @Override
    public void onError(HostListManager.Error error) {
        String explanation = null;
        switch (error) {
            case AUTH_FAILED:
                break;
            case NETWORK_ERROR:
                explanation = getString(R.string.error_network_error);
                break;
            case UNEXPECTED_RESPONSE:
            case SERVICE_UNAVAILABLE:
            case UNKNOWN:
                explanation = getString(R.string.error_unexpected);
                break;
            default:
                // Unreachable.
                return;
        }

        if (explanation != null) {
            Toast.makeText(this, explanation, Toast.LENGTH_LONG).show();
            updateHostListView();
            return;
        }

        // We don't know which consumer triggers onError. Refreshing host list is the most common
        // use case and the latest token should be mostly the same on all consumers.
        // TODO(yuweih): distinguish token consumer.
        mHostListRetrievingConsumer.revokeLatestToken(null);
        Log.e(TAG, "Fresh auth token was rejected.");
        explanation = getString(R.string.error_authentication_failed);
        Toast.makeText(this, explanation, Toast.LENGTH_LONG).show();
        updateHostListView();
    }

    /**
     * Updates the infotext and host list display.
     */
    private void updateUi() {
        if (mRefreshButton != null) {
            mRefreshButton.setEnabled(mAccount != null);
        }
        ArrayAdapter<HostInfo> displayer = new HostListAdapter(this, mHosts);
        mHostListView.setAdapter(displayer);
    }

    @Override
    public void onConnectionState(ConnectionListener.State state, ConnectionListener.Error error) {
        boolean dismissProgress = false;
        switch (state) {
            case INITIALIZING:
            case CONNECTING:
            case AUTHENTICATED:
                // The connection is still being established.
                break;

            case CONNECTED:
                dismissProgress = true;
                // Display the remote desktop.
                startActivityForResult(new Intent(this, Desktop.class), DESKTOP_ACTIVITY);
                break;

            case FAILED:
                dismissProgress = true;
                Toast.makeText(this, getString(error.message()), Toast.LENGTH_LONG).show();
                // Close the Desktop view, if it is currently running.
                finishActivity(DESKTOP_ACTIVITY);
                break;

            case CLOSED:
                // No need to show toast in this case. Either the connection will have failed
                // because of an error, which will trigger toast already. Or the disconnection will
                // have been initiated by the user.
                dismissProgress = true;
                finishActivity(DESKTOP_ACTIVITY);
                break;

            default:
                // Unreachable, but required by Google Java style and findbugs.
                assert false : "Unreached";
        }

        if (dismissProgress && mProgressIndicator != null) {
            mProgressIndicator.dismiss();
            mProgressIndicator = null;
        }
    }
}
