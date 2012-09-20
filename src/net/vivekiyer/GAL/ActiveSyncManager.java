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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Random;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import com.android.exchange.adapter.GalParser;
import com.android.exchange.adapter.ProvisionParser;
import com.google.common.collect.HashMultimap;

/**
 * @author Vivek Iyer
 *
 * This class is responsible for implementing the ActiveSync commands that
 * are used to connect to the Exchange server and  query the GAL
 */
/**
 * @author vivek
 *
 */
public class ActiveSyncManager {
	private String mPolicyKey = "0";
	private String mAuthString;
	private String mUri;
	private String mServerName;
	private String mDomain;
	private String mUsername;
	private String mPassword;
	private boolean mUseSSL;
	private boolean mAcceptAllCerts;
	private String mActiveSyncVersion = "";	
	private int mDeviceId = -1;
	private HashMultimap<String, Contact> mResults;

	public boolean isUseSSLSet() {
		return mUseSSL;
	}

	public void setUseSSL(boolean mUseSSL) {
		this.mUseSSL = mUseSSL;
	}

	public boolean isAcceptAllCertsSet() {
		return mAcceptAllCerts;
	}

	public void setAcceptAllCerts(boolean mAcceptAllCerts) {
		this.mAcceptAllCerts = mAcceptAllCerts;
	}		

	public String getActiveSyncVersion() {
		return mActiveSyncVersion;
	}

	public void setActiveSyncVersion(String version) {
		mActiveSyncVersion = version;
	}

	public String getDomain() {
		return mDomain;
	}

	public void setDomain(String domain) {
		mDomain = domain;
	}

	public String getPolicyKey() {
		return mPolicyKey;
	}

	public void setPolicyKey(String policyKey) {
		this.mPolicyKey = policyKey;
	}

	public String getServerName() {
		return mServerName;
	}

	public void setServerName(String serverName) {
		this.mServerName = serverName;
	}

	public void setmUsername(String username) {
		this.mUsername = username;
	}

	public void setPassword(String password) {
		this.mPassword = password;
	}

	public int getDeviceId(){
		return mDeviceId;
	}

	public void setDeviceId(int deviceId){
		mDeviceId = deviceId;
	}

	public HashMultimap<String, Contact> getResults(){
		return mResults;
	}

	/**
	 * Generates the auth string from the username, password and domain
	 */
	private void generateAuthString(){
		// For BPOS the DOMAIN is not required, so remove the backslash
		if(mDomain.equalsIgnoreCase(""))
			mAuthString = "Basic "
					+ Utility.base64Encode(mUsername + ":" + mPassword);
		else
			mAuthString = "Basic "
					+ Utility.base64Encode(mDomain + "\\" + mUsername + ":"
							+ mPassword);
	}

	/**
	 * Initializes the class by assigning the Exchange URL and the AuthString  
	 * @throws URISyntaxException 
	 */
	public boolean Initialize() {

		generateAuthString();

		Random rand = new Random();

		if(mDeviceId == -1){
			while(mDeviceId <= 0)
			{
				mDeviceId = rand.nextInt();;
			}
		}
		
		// If we don't have a server name, 
		// there is no way we can proceed
		if(mServerName.compareToIgnoreCase("") == 0)
			return false;

		// this is where we will send it
		try {
			URI uri = new URI(
					(mUseSSL) ? "https" : "http", 	// Scheme					
							mServerName ,					// Authority
							"/Microsoft-Server-ActiveSync", // path
							"User="							// query
							+ mUsername
							+ "&DeviceId=" 
							+ mDeviceId
							+ "&DeviceType=Android" 
							+ "&Cmd=",
							null							// fragment
					);

			mUri = uri.toString();
		} catch (URISyntaxException e) {
			return false;
		}

		return true;
	}

	public ActiveSyncManager() {		
	}

	public ActiveSyncManager(
			String serverName, 
			String domain, 
			String username,
			String password,
			boolean useSSL,
			boolean acceptAllCerts,
			String policyKey, 
			String activeSyncVersion,
			int deviceId) {

		mServerName = serverName;
		mDomain = domain;
		mUsername = username;
		mPassword = password;
		mPolicyKey = policyKey;
		mActiveSyncVersion = activeSyncVersion;
		mUseSSL = useSSL;
		mAcceptAllCerts = acceptAllCerts;
		mDeviceId = deviceId;
	}

	/**
	 * @throws Exception
	 * @return Status code returned from the Exchange server
	 * 
	 * Connects to the Exchange server and obtains the version of ActiveSync supported 
	 * by the server 
	 */
	public int getExchangeServerVersion() throws Exception {

		// First get the options from the server
		CommandRequest request = new CommandRequest(
				mUri,
				mAuthString, 
				mUseSSL, 
				mActiveSyncVersion, 
				mAcceptAllCerts, 
				mPolicyKey);

		HttpResponse response = request.getOptions();		

		// 200 indicates a success
		int statusCode = response.getStatusLine().getStatusCode() ; 

		if( statusCode == 200){

			Header [] headers = response.getHeaders("MS-ASProtocolVersions");

			if (headers.length != 0) {

				Header header = headers[0];

				// Parse out the ActiveSync Protocol version
				String versions = header.getValue();

				// Look for the last comma, and parse out the highest
				// version
				mActiveSyncVersion = versions.substring(versions
						.lastIndexOf(",") + 1);

				// Provision the device if necessary
				provisionDevice();
			}
		}
		return statusCode;
	}



	/**
	 * @param query The name to search the GAL for
	 * @param mContacts 
	 * @param result The XML contacts returned by the Exchange server
	 * 
	 * @return The status code returned from the Exchange server
	 * @throws Exception
	 * 
	 * This method searches the GAL on the Exchange server
	 */
	public int searchGAL(String query) throws Exception 
	{
		int ret = 0;
		GalRequest request = new GalRequest(
				mUri,
				mAuthString, 
				mUseSSL, 
				mActiveSyncVersion, 
				mAcceptAllCerts, 
				mPolicyKey,
				query,
				100);

		// Get the WBXML input stream from the response
		CommandResponse resp = new CommandResponse(request.getResponse(true));

		// Make sure there were no errors
		ret = resp.getStatusCode();
		
		if(ret == 200)
		{			
			GalParser gp = new GalParser(resp.getWBXMLInputStream());
			gp.parse();
			mResults = gp.getResults();
		}
		return ret;
	}

	/**
	 * @throws Exception
	 * 
	 * Sends a Provision command to the Exchange server. Only needed for Exchange 2007 and above 
	 */
	public void provisionDevice() {

		try{
			ProvisionRequest provRequest = new ProvisionRequest(
					mUri,
					mAuthString, 
					mUseSSL, 
					mActiveSyncVersion, 
					mAcceptAllCerts, 
					mPolicyKey);

			System.out.println("Sending provision request");
			// Get the WBXML input stream from the response
			CommandResponse resp = new CommandResponse(provRequest.getResponse(true));
			
			System.out.println("Received provision response");
			
			// Make sure there were no errors
			if(resp.getStatusCode() != 200)
			{
				Debug.Log(resp.getErrorString());
				return;
			}

			ProvisionParser pp = new ProvisionParser(resp.getWBXMLInputStream());
			if(pp.parse() == false)
			{
				Debug.Log("Failed to parse policy key");
				return;
			}

			System.out.println("Key = "+ pp.getSecuritySyncKey());

			// Now that we have the temp policy key
			// Tell the server we accept everything it says
			AckProvisionRequest ackProvRequest = new AckProvisionRequest(
					mUri,
					mAuthString, 
					mUseSSL, 
					mActiveSyncVersion, 
					mAcceptAllCerts, 
					mPolicyKey,
					pp.getSecuritySyncKey(), 
					pp.isRemoteWipeRequested()
					);
			
			System.out.println("Sending provision ack");
			
			// Get the WBXML input stream from the response
			resp = new CommandResponse(ackProvRequest.getResponse(false));
			System.out.println("Received ack response");
			
			// Make sure there were no errors
			if(resp.getStatusCode() != 200)
			{
				Debug.Log(resp.getErrorString());
				return;
			}

			pp = new ProvisionParser(resp.getWBXMLInputStream());
			if(pp.parse() == false)
			{
				Debug.Log("Error in acknowledging Provision request");
				return;
			}
			System.out.println("Final policy Key = "+ pp.getSecuritySyncKey());

			mPolicyKey = pp.getSecuritySyncKey();

		}
		catch(Exception ex)
		{
			Debug.Log("Provisioning failed. Error string:\n" + ex.toString());
			return;
		}

	}
}
