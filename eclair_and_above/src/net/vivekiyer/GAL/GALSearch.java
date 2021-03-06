package net.vivekiyer.GAL;

import java.net.SocketTimeoutException;

import android.os.AsyncTask;
import com.google.common.collect.HashMultimap;

public class GALSearch extends AsyncTask<String, Void, Boolean>
{
	public interface OnSearchCompletedListener{
		void OnSearchCompleted(int result, GALSearch search);
	}

	private ActiveSyncManager activeSyncManager;

	private int errorCode = 0;
	private String errorMesg = ""; //$NON-NLS-1$
	private String errorDetail = ""; //$NON-NLS-1$

	protected volatile OnSearchCompletedListener onSearchCompletedListener;
	
	HashMultimap<String,Contact> mContacts = null;


	public HashMultimap<String,Contact> getContacts() {
		return mContacts;
	}
	public String getSearchTerm() {
		return activeSyncManager.getSearchTerm();
	}
	public OnSearchCompletedListener getOnSearchCompletedListener() {
		synchronized (this) {
			return onSearchCompletedListener;
		}
	}
	public void setOnSearchCompletedListener(
			OnSearchCompletedListener onSearchCompletedListener) {
		synchronized (this) {
			this.onSearchCompletedListener = onSearchCompletedListener;
		}
	}
	public GALSearch(ActiveSyncManager activeSyncManager) {
		this.activeSyncManager = activeSyncManager;
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see android.os.AsyncTask#doInBackground(Params[])
	 * 
	 * The method that searches the GAL
	 */
	@Override
	protected Boolean doInBackground(String... params) {
		try {
			// Search the GAL
			mContacts = null;

			int statusCode = 0;

				do {
				statusCode = activeSyncManager.searchGAL(params[0]);
					switch (statusCode) {
						case 200:
							// All went ok, get the results
							switch(activeSyncManager.getRequestStatus()) {
								case Parser.STATUS_TOO_MANY_DEVICES:
									errorCode = Parser.STATUS_TOO_MANY_DEVICES;
									errorMesg = App.getInstance().getString(R.string.too_many_device_partnerships_title);
									errorDetail = App.getInstance().getString(R.string.too_many_device_partnerships_detail);
									return false;
								case ActiveSyncManager.ERROR_UNABLE_TO_REPROVISION:
									errorCode = ActiveSyncManager.ERROR_UNABLE_TO_REPROVISION;
									errorMesg = App.getInstance().getString(R.string.authentication_failed_title);
									errorDetail = App.getInstance().getString(R.string.please_check_settings);
								case Parser.STATUS_OK:
									break;
								default:
									errorCode = activeSyncManager.getRequestStatus();
									errorMesg = App.getInstance().getString(R.string.unhandled_error, activeSyncManager.getRequestStatus());
									errorDetail = App.getInstance().getString(R.string.unhandled_error_occured);
									return false;
							}
							mContacts = activeSyncManager.getResults();
							break;
						case 449: // RETRY AFTER PROVISIONING
							// Looks like we need to provision again
							activeSyncManager.provisionDevice();
							break;
						case 401: // UNAUTHORIZED
							// Looks like the password expired
							errorCode = 401;
							errorMesg = App.getInstance().getString(R.string.authentication_failed_title);
							errorDetail = App.getInstance().getString(R.string.authentication_failed_detail);
							return false;
						case 403: // FORBIDDEN
							// Device ID not accepted by server
							errorCode = 403;
							errorMesg = App.getInstance().getString(R.string.forbidden_by_server_title);
							errorDetail = App.getInstance().getString(R.string.forbidden_by_server_detail, activeSyncManager.getDeviceId());
							return false;
						default:
							errorCode = statusCode;
							errorMesg = App.getInstance().getString(R.string.connection_failed_title);
							errorDetail = App.getInstance().getString(R.string.connection_failed_detail, statusCode);
							return false;
					}
				} while (statusCode != 200);
		} catch (final SocketTimeoutException e) {
			errorMesg = App.getInstance().getString(R.string.timeout_title);
			errorDetail = App.getInstance().getString(R.string.timeout_detail);
			return false;
		} catch (final Exception e) {
			if (Debug.Enabled) {
				Debug.Log(e.toString());
			} else {
				errorMesg = "Activesync version= " //$NON-NLS-1$
						+ activeSyncManager.getActiveSyncVersion() + "\n" //$NON-NLS-1$
						+ e.toString();
				return false;
			}
		}
		return true;
	}

	public int getErrorCode() {
		return errorCode;
	}
	public String getErrorMesg() {
		return errorMesg;
	}
	public String getErrorDetail() {
		return errorDetail;
	}
	/*
	 * (non-Javadoc)
	 * 
	 * @see android.os.AsyncTask#onPostExecute(java.lang.Object)
	 * 
	 * This method displays the retrieved results in a list view
	 */
	@Override
	protected void onPostExecute(Boolean result) {
		super.onPostExecute(result);
		if(getOnSearchCompletedListener() != null)
			getOnSearchCompletedListener().OnSearchCompleted(result ? 0 : errorCode, this);
	}

}
