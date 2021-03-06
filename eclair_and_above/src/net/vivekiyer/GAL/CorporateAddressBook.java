/* Copyright 2010 Vivek Iyer
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * 	http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.vivekiyer.GAL;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.app.SearchManager;
import android.app.SearchableInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask.Status;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.SearchRecentSuggestions;
import android.support.v4.app.FragmentActivity;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.SearchView;
import com.google.common.collect.HashMultimap;
import net.vivekiyer.GAL.ChoiceDialogFragment.OnChoiceDialogOptionClickListener;
import net.vivekiyer.GAL.CorporateAddressBookFragment.ContactListListener;

import java.util.Locale;
import java.util.Set;

/**
 * @author Vivek Iyer
 * 
 *         This class is the main entry point to the application
 */
public class CorporateAddressBook extends FragmentActivity
	implements ContactListListener, GALSearch.OnSearchCompletedListener, OnChoiceDialogOptionClickListener
{

	// TAG used for logging
	// private static String TAG = "CorporateAddressBook";

	// Object that performs all the ActiveSync magic
	private ActiveSyncManager activeSyncManager;

	// Preference object that stores the account credentials
	private SharedPreferences mPreferences;

	// Used to launch the preference pane
	static final int DISPLAY_PREFERENCES_REQUEST = 0;

	// Used to launch the initial configuration pane
	static final int DISPLAY_CONFIGURATION_REQUEST = 2;
	
	// Tags for finding and retrieving fragments
	static final String mainTag = "R.id.main_fragment"; //$NON-NLS-1$
	static final String contactTag = "R.id.contact_fragment"; //$NON-NLS-1$
	static final String CONTACTS = "mContacts"; //$NON-NLS-1$
	static final String SEARCH_TERM = "latestSearchTerm"; //$NON-NLS-1$
	static final String SELECTED_CONTACT = "selectedContact"; //$NON-NLS-1$
	static final String ONGOING_SEARCH = "search";  //$NON-NLS-1$

	// Progress bar
	private ProgressDialog progressdialog;

	// Last search term
	private String latestSearchTerm;

	private SearchView searchView;

	// TAG used for logging
	// private static String TAG = "CorporateAddressBook";
	
	// Stores the list of contacts returned
	private HashMultimap<String, Contact> mContacts;

	private Contact selectedContact;

	private GALSearch search;

	private boolean isPaused = false;

	/*
	 * (non-Javadoc)
	 *
	 * @see android.app.Activity#onCreate(android.os.Bundle)
	 *
	 * Called when the activity is first created.
	 */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main_fragment);
		
		// Initialize preferences and the activesyncmanager
		mPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		activeSyncManager = new ActiveSyncManager();

		// Create the progress bar
		progressdialog = new ProgressDialog(this);
		progressdialog.setProgressStyle(ProgressDialog.STYLE_SPINNER);
		progressdialog.setCancelable(false);

		// Turn keystrokes into search
		setDefaultKeyMode(DEFAULT_KEYS_SEARCH_LOCAL);

		// Check if we have successfully connected to an Exchange
		// server before.
		// If not launch the config pane and query the user for
		// Exchange server settings
		if (!loadPreferences()) {
			CorporateAddressBook.showConfiguration(this);
		}

		// Get the intent, verify the action and get the query
		// but not if the activity is being recreated (would cause a new search)
		if(savedInstanceState == null || !savedInstanceState.containsKey("mContacts")) { //$NON-NLS-1$
			final Intent intent = getIntent();
			onNewIntent(intent);
		}
	}
	
	@Override
	protected void onNewIntent(Intent intent) {
		if(Intent.ACTION_SEARCH.equals(intent.getAction()))
			intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
		super.onNewIntent(intent);
		setIntent(intent);
		if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
			final String query = intent.getStringExtra(SearchManager.QUERY);
			performSearch(query);
		}		
	}
	
	// Assist user by showing search box whenever returning
	@Override
	protected void onStart() {
		super.onStart();
		
		FragmentManager fm = getSupportFragmentManager();
	    CorporateContactRecordFragment details = (CorporateContactRecordFragment) fm
		    	.findFragmentById(R.id.contact_fragment);
			 
	    if (details != null && details.isInLayout()) {
			CorporateAddressBookFragment contacts = (CorporateAddressBookFragment) fm
				.findFragmentById(R.id.main_fragment);
			contacts.setIsSelectable(true);
			contacts.setViewBackground(false);
			FragmentTransaction ft = fm.beginTransaction();  
			ft.hide(details);  
			ft.commit();
		}
	    
		final Intent intent = getIntent();
		if (intent != null) {
			final Set<String> categories = intent.getCategories();
			if ((categories != null)
					&& categories.contains(Intent.CATEGORY_LAUNCHER)) {
				this.onSearchRequested();
			}
		}
	}
	
	@Override
	protected void onPostCreate(Bundle savedInstanceState) {
		super.onPostCreate(savedInstanceState);
		
		if(savedInstanceState != null && savedInstanceState.containsKey(CONTACTS)) {
			@SuppressWarnings("unchecked")
			HashMultimap<String, Contact> contactsMap = (HashMultimap<String, Contact>) savedInstanceState.get(CONTACTS);
			mContacts = contactsMap;
			String latestSearchTerm = savedInstanceState.getString(SEARCH_TERM);
			selectedContact = (Contact) savedInstanceState.get(SELECTED_CONTACT);
			displaySearchResult(mContacts, latestSearchTerm);
			if(selectedContact != null) {
				selectContact(selectedContact);
			}
			Integer searchHash = savedInstanceState.getInt(ONGOING_SEARCH);
			if(searchHash != 0) {
				search = App.taskManager.get(searchHash);
				if(search != null){
					search.setOnSearchCompletedListener(this);
					App.taskManager.remove(searchHash);
					if(progressdialog != null) {
						progressdialog.setMessage(getString(R.string.retrievingResults));
						progressdialog.show();
					}
				}
			}
		}
	};

	@Override
	protected void onPause() {
		super.onPause();
		isPaused = true;
	}

	@Override
	protected void onResume() {
		super.onResume();
		isPaused = false;
	}

	private void selectContact(Contact selectedContact) {
		CorporateAddressBookFragment contacts = (CorporateAddressBookFragment) getSupportFragmentManager()
				.findFragmentById(R.id.main_fragment);
		contacts.setSelectedContact(selectedContact);
		onContactSelected(selectedContact);		
	}

	@Override
	public void onSaveInstanceState(Bundle outState) {
		outState.putSerializable(CONTACTS, this.mContacts);
		outState.putString(SEARCH_TERM, this.latestSearchTerm);
		outState.putSerializable(SELECTED_CONTACT, this.selectedContact);
		if(search != null && search.getStatus().equals(Status.RUNNING)) {
			outState.putInt(ONGOING_SEARCH, search.hashCode());
			App.taskManager.put(search.hashCode(), search);
		}
	};
	
	private void performSearch(String name) {
		
		if(progressdialog != null) {
			progressdialog.setMessage(getString(R.string.retrievingResults));
			progressdialog.show();
		}

		// Save search in recent list
		final SearchRecentSuggestions suggestions = new SearchRecentSuggestions(
				this, RecentGALSearchTermsProvider.AUTHORITY,
				RecentGALSearchTermsProvider.MODE);
		suggestions.saveRecentQuery(name, null);

		search = new GALSearch(activeSyncManager);
		search.onSearchCompletedListener = this;
		search.execute(name);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onCreateOptionsMenu(android.view.Menu)
	 * 
	 * Displays the menu when the user clicks the options button. In our case
	 * our menu only contains one button - Settings
	 */
	@TargetApi(11)
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		final MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.main_menu, menu);

		// Get the SearchView and set the searchable configuration for Honeycomb
		// and above
		if (!Utility.isPreHoneycomb()) {
			final SearchManager searchManager = (SearchManager) getSystemService(Context.SEARCH_SERVICE);
			final ComponentName component = getComponentName();
			final SearchableInfo searchableInfo = searchManager
					.getSearchableInfo(component);
			searchView = (SearchView) menu.findItem(
					R.id.menu_search).getActionView();
			searchView.setSearchableInfo(searchableInfo);
			
			//this.onSearchRequested();
		}

		return super.onCreateOptionsMenu(menu);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onOptionsItemSelected(android.view.MenuItem)
	 * 
	 * Launches the preferences pane when the user clicks the settings option
	 */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
		case R.id.menu_search:
			return this.onSearchRequested();
		case R.id.settings:
			CorporateAddressBook.showConfiguration(this);
			return true;
		case R.id.clearSearchHistory:
			final SearchRecentSuggestions suggestions = new SearchRecentSuggestions(
					this, RecentGALSearchTermsProvider.AUTHORITY,
					RecentGALSearchTermsProvider.MODE);
			suggestions.clearHistory();
			return true;
		default:
			return super.onOptionsItemSelected(item);
		}
	}

	/**
	 * Launches the preferences activity
	 */
	public static void showConfiguration(Activity parentActivity) {
		final Intent myIntent = new Intent();
		myIntent.setClassName("net.vivekiyer.GAL", //$NON-NLS-1$
				"net.vivekiyer.GAL.Configure"); //$NON-NLS-1$
		parentActivity.startActivityForResult(myIntent, DISPLAY_CONFIGURATION_REQUEST);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onActivityResult(int, int,
	 * android.content.Intent)
	 * 
	 * This method gets called after the launched activity is closed This
	 * application needs to handle the closing of two activity launches - The
	 * preference pane - The config pane
	 */
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case DISPLAY_CONFIGURATION_REQUEST:
			// Initialize the activesync object with the validated settings
			if (!loadPreferences()) {
				CorporateAddressBook.showConfiguration(this);
			}
			break;
		}
	}

	/**
	 * The older version of the application included http and https in the
	 * server name. The newer version no longer has this. Hence clean up is
	 * required
	 */
	private void cleanUpServerName() {
		String serverName = mPreferences.getString(
				Configure.KEY_SERVER_PREFERENCE, ""); //$NON-NLS-1$
		serverName = serverName.toLowerCase(Locale.getDefault());

		if (serverName.startsWith("https://")) { //$NON-NLS-1$
			final SharedPreferences.Editor editor = mPreferences.edit();
			editor.putBoolean(Configure.KEY_USE_SSL, true);
			serverName = serverName.substring(8);
			editor.putString(Configure.KEY_SERVER_PREFERENCE, serverName);
			editor.commit();
		} else if (serverName.startsWith("http://")) { //$NON-NLS-1$
			final SharedPreferences.Editor editor = mPreferences.edit();
			editor.putBoolean(Configure.KEY_USE_SSL, false);
			serverName = serverName.substring(7);
			editor.putString(Configure.KEY_SERVER_PREFERENCE, serverName);
			editor.commit();
		}

		activeSyncManager.setServerName(serverName);
	}

	/**
	 * Reads the stored preferences and initializes the
	 * 
	 * @return True if a valid Exchange server settings was saved during the
	 *         previous launch. False otherwise *
	 * 
	 */
	public boolean loadPreferences() {
		activeSyncManager.setUsername(mPreferences.getString(
				Configure.KEY_USERNAME_PREFERENCE, "")); //$NON-NLS-1$
		activeSyncManager.setPassword(mPreferences.getString(
				Configure.KEY_PASSWORD_PREFERENCE, "")); //$NON-NLS-1$
		activeSyncManager.setDomain(mPreferences.getString(
				Configure.KEY_DOMAIN_PREFERENCE, "")); //$NON-NLS-1$

		// Clean up server name from previous version of the app
		cleanUpServerName();

		activeSyncManager.setActiveSyncVersion(mPreferences.getString(
				Configure.KEY_ACTIVESYNCVERSION_PREFERENCE, "")); //$NON-NLS-1$
		activeSyncManager.setPolicyKey(mPreferences.getString(
				Configure.KEY_POLICY_KEY_PREFERENCE, "")); //$NON-NLS-1$
		activeSyncManager.setAcceptAllCerts(mPreferences.getBoolean(
				Configure.KEY_ACCEPT_ALL_CERTS, true));
		activeSyncManager.setUseSSL(mPreferences.getBoolean(
				Configure.KEY_USE_SSL, true));
		
		// Fix for null device_id
		String device_id_string = mPreferences.getString(Configure.KEY_DEVICE_ID_STRING, null);
		if(device_id_string == null)
		{
			int device_id = mPreferences.getInt(
					Configure.KEY_DEVICE_ID, 0);
			if(device_id > 0)
				device_id_string = String.valueOf(device_id);
			else
				device_id_string = ActiveSyncManager.getUniqueId();
		}
		
		activeSyncManager.setDeviceId(device_id_string);
		
		if (!activeSyncManager.Initialize())
			return false;
		
//		// Fix for null device_id
//		if(device_id == 0)
//			return false;
		
		// Check to see if we have successfully connected to an Exchange server
		// Do we have a previous successful connect with these settings?
		if(!mPreferences.getBoolean(Configure.KEY_SUCCESSFULLY_CONNECTED, false)){
			// If not, let's try
			if (activeSyncManager.getActiveSyncVersion().equalsIgnoreCase("")) { //$NON-NLS-1$
				// If we fail, let's return
				return false;
			}
			else {
				// In case of success, let's make a record of this so that
				// we don't have to check the settings every time we launch.
				// This record will be reset when any change is made to the
				// settings
				SharedPreferences.Editor editor = mPreferences.edit();
				editor.putBoolean(Configure.KEY_SUCCESSFULLY_CONNECTED, true);
				editor.commit();
			}
		}

		return true;
	}

	@TargetApi(11)
	private void displaySearchResult(HashMultimap<String, Contact> contacts, String searchTerm) {
		
		this.mContacts = contacts;
		this.latestSearchTerm = searchTerm;
		
		final FragmentManager fragmentManager = getSupportFragmentManager();
		
		CorporateAddressBookFragment list = (CorporateAddressBookFragment) fragmentManager
		    .findFragmentById(R.id.main_fragment);
		if(list ==  null) {
			Debug.Log("List fragment missing from main activity, discarding search result"); //$NON-NLS-1$
			return;
		}
	    list.displayResult(mContacts, latestSearchTerm);
	    
	    resetAndHideDetails(fragmentManager);    
	    if(!Utility.isPreHoneycomb() && (searchView != null))
	    	searchView.setQuery("", false); //$NON-NLS-1$
	    list.getView().requestFocus();
	}
	
	private void resetAndHideDetails(final FragmentManager fragmentManager) {

		CorporateAddressBookFragment list = (CorporateAddressBookFragment) fragmentManager
		    .findFragmentById(R.id.main_fragment);
		
		CorporateContactRecordFragment details = (CorporateContactRecordFragment) fragmentManager
		    	.findFragmentById(R.id.contact_fragment);
			 
	    if (details != null && details.isInLayout() && !this.isPaused) {
			FragmentTransaction ft = getSupportFragmentManager().beginTransaction();  
			//ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_CLOSE);

			// Below does not work since it clears the detail fragment before anim starts,
			// making it look rather weird. Better off w/o anims, unfortunately.
			//ft.setCustomAnimations(R.anim.slide_in, R.anim.slide_out);
			ft.hide(details);  
			ft.commit();
			fragmentManager.executePendingTransactions();
	    	details.clear();
		}

	    list.setViewBackground(false);
	    selectedContact = null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see android.app.Activity#onStop()
	 * 
	 * Called when the application is closed
	 */
	@Override
	protected void onStop() {
		super.onStop();

		// Make sure that the activesync version and policy key get written
		// to the preferences
		final SharedPreferences.Editor editor = mPreferences.edit();
		editor.putString(Configure.KEY_ACTIVESYNCVERSION_PREFERENCE,
				activeSyncManager.getActiveSyncVersion());
//		editor.putString(Configure.KEY_DEVICE_ID_STRING, activeSyncManager.getDeviceId());
		editor.putString(Configure.KEY_POLICY_KEY_PREFERENCE,
				activeSyncManager.getPolicyKey());
//		editor.putString(Configure.KEY_RESULTS_PREFERENCE, searchResultXML);
//		// EditText text = (EditText) findViewById(R.id.Name);
//		editor.putString(Configure.KEY_SEARCH_TERM_PREFERENCE, latestSearchTerm);

		// Commit the edits!
		editor.commit();
	}

	@Override
	protected void onDestroy() {
		if((progressdialog != null) && progressdialog.isShowing()) {
			try {
				progressdialog.dismiss();
			} catch (java.lang.IllegalArgumentException e) { }
		}
		if(search != null)
			search.setOnSearchCompletedListener(null);
		super.onDestroy();
	};

	@Override
	public void onContactSelected(Contact contact) {
		// Create a parcel with the associated contact object
		// This parcel is used to send data to the activity
		
		this.selectedContact = contact;
		
		final FragmentManager fragmentManager = getSupportFragmentManager();
		
	    CorporateContactRecordFragment details = (CorporateContactRecordFragment) fragmentManager
		            .findFragmentById(R.id.contact_fragment);
		 
	    if (details == null || !details.isInLayout()) {
			final Bundle b = new Bundle();
			b.putParcelable("net.vivekiyer.GAL", selectedContact); //$NON-NLS-1$

			// Launch the activity
			final Intent myIntent = new Intent();
			myIntent.setClassName("net.vivekiyer.GAL", //$NON-NLS-1$
					"net.vivekiyer.GAL.CorporateContactRecord"); //$NON-NLS-1$

			myIntent.putExtras(b);
			startActivity(myIntent);
	    } else {
			CorporateAddressBookFragment list = (CorporateAddressBookFragment) fragmentManager
				.findFragmentById(R.id.main_fragment);
			list.setViewBackground(true);
				
	        details.setContact(selectedContact);
	        
			FragmentTransaction ft = getSupportFragmentManager().beginTransaction();  
			//ft.setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN);
			// Below does not work since it resizes the result fragment before anim starts,
			// making it look rather weird. Better off w/o anims, unfortunately.
			//ft.setCustomAnimations(R.anim.slide_in, R.anim.slide_out);
			ft.show(details);
			ft.commit();
	    }		
	}
	
	@Override
	public void onSearchCleared() {
		resetAndHideDetails(getSupportFragmentManager());
	}

	@TargetApi(11)
	@Override
	public boolean onSearchRequested() {
		if(!Utility.isPreHoneycomb()) {
			if(searchView != null) {
				searchView.setFocusable(true);
			    searchView.setIconified(false);
			    searchView.requestFocusFromTouch();
			    return true;
			}
			else {
				Debug.Log("Running HC+ without SearchView"); //$NON-NLS-1$
				return false;
			}
		}
		return super.onSearchRequested();
	}

	@Override
	public void OnSearchCompleted(int result,
			GALSearch search) {
		if((progressdialog != null) && progressdialog.isShowing()) {
			try {
				progressdialog.dismiss();
			} catch (java.lang.IllegalArgumentException e) { }
		}
		if(result==0) {
			displaySearchResult(search.getContacts(), search.getSearchTerm());
			return;
		}
		ChoiceDialogFragment dialogFragment;
		String title = search.getErrorMesg();
        String message = search.getErrorDetail();
		String positiveButtonText;
		String negativeButtonText;
		switch(result) {
		// Errors that might be remedied by updating server settings
		case 401:
			positiveButtonText = getString(R.string.show_settings);
	        negativeButtonText = getString(android.R.string.cancel);
	        dialogFragment = ChoiceDialogFragment.newInstance(title, message, positiveButtonText, negativeButtonText, DISPLAY_CONFIGURATION_REQUEST, android.R.id.closeButton);
	        dialogFragment.setListener(this);
	        try {
				dialogFragment.show(getSupportFragmentManager(), "checkSettings"); //$NON-NLS-1$
			} catch (java.lang.IllegalStateException e) {
				Debug.Log(e.getMessage());
			}
	        break;
		case ActiveSyncManager.ERROR_UNABLE_TO_REPROVISION:
			positiveButtonText = getString(R.string.show_settings);
	        negativeButtonText = getString(android.R.string.cancel);
	        dialogFragment = ChoiceDialogFragment.newInstance(title, message, positiveButtonText, negativeButtonText, DISPLAY_CONFIGURATION_REQUEST, android.R.id.closeButton);
	        dialogFragment.setListener(this);
	        try {
				dialogFragment.show(getSupportFragmentManager(), "reprovision"); //$NON-NLS-1$
			} catch (java.lang.IllegalStateException e) {
				Debug.Log(e.getMessage());
			}
	        break;
		// Errors that depend on external (non app-related) circumstances
		case 403:
			positiveButtonText = getString(android.R.string.ok);
			negativeButtonText = getString(android.R.string.copy);
			dialogFragment = ChoiceDialogFragment.newInstance(title, message, positiveButtonText, negativeButtonText, android.R.id.closeButton, android.R.id.copy);
			dialogFragment.setListener(this);
			try {
				dialogFragment.show(getSupportFragmentManager(), "unauthorized"); //$NON-NLS-1$
			} catch (java.lang.IllegalStateException e) {
				Debug.Log(e.getMessage());
			}
			break;
		default:
	        dialogFragment = ChoiceDialogFragment.newInstance(title, message);
	        try {
				dialogFragment.show(getSupportFragmentManager(), "ContinueFragTag"); //$NON-NLS-1$
			} catch (java.lang.IllegalStateException e) {
				Debug.Log(e.getMessage());
			}
			break;
		}
	}
	public void onChoiceDialogOptionPressed(int action) {
		if(action == DISPLAY_CONFIGURATION_REQUEST)
			showConfiguration(this);
	}
}