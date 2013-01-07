package com.twofours.surespot.network;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.client.CookieStore;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.HttpContext;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.android.gcm.GCMRegistrar;
import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.AsyncHttpResponseHandler;
import com.loopj.android.http.PersistentCookieStore;
import com.loopj.android.http.RequestParams;
import com.twofours.surespot.SurespotApplication;
import com.twofours.surespot.SurespotConstants;
import com.twofours.surespot.ui.activities.LoginActivity;

public class NetworkController {
	protected static final String TAG = "NetworkController";
	private static Cookie mConnectCookie;

	private static void setConnectCookie(Cookie connectCookie) {
		// we be authorized
		NetworkController.mConnectCookie = connectCookie;
		setUnauthorized(false);
	}

	private static AsyncHttpClient mClient;
	private static CookieStore mCookieStore;

	public static void get(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
		mClient.get(SurespotConstants.BASE_URL + url, params, responseHandler);
	}

	public static void post(String url, RequestParams params, AsyncHttpResponseHandler responseHandler) {
		mClient.post(SurespotConstants.BASE_URL + url, params, responseHandler);
	}

	public static Cookie getConnectCookie() {
		return mConnectCookie;
	}

	public static boolean hasSession() {
		return mConnectCookie != null;
	}

	public static CookieStore getCookieStore() {
		return mCookieStore;
	}

	private static boolean mUnauthorized;

	private static boolean isUnauthorized() {
		return mUnauthorized;
	}

	public static synchronized void setUnauthorized(boolean unauthorized) {

		NetworkController.mUnauthorized = unauthorized;
	}

	static {
		mCookieStore = new PersistentCookieStore(SurespotApplication.getAppContext());
		if (mCookieStore.getCookies().size() > 0) {
			Log.v(TAG, "mmm cookies in the jar: " + mCookieStore.getCookies().size());
			mConnectCookie = extractConnectCookie(mCookieStore);
		}

		mClient = new AsyncHttpClient();
		mClient.setCookieStore(mCookieStore);

		// handle 401s
		((DefaultHttpClient) mClient.getHttpClient()).addResponseInterceptor(new HttpResponseInterceptor() {

			@Override
			public void process(HttpResponse response, HttpContext context) throws HttpException, IOException {

				if (response.getStatusLine().getStatusCode() == HttpStatus.SC_UNAUTHORIZED) {
					String origin = context.getAttribute("http.cookie-origin").toString();

					if (origin != null) {
						Log.v(TAG, "response origin: " + origin);
						if (!origin.equals("[" + SurespotConstants.BASE_URL.substring(7) + "/login]")) {

							if (!NetworkController.isUnauthorized()) {
								mClient.cancelRequests(SurespotApplication.getAppContext(), true);

								Log.v(TAG, "launching login intent");
								Intent intent = new Intent(SurespotApplication.getAppContext(), LoginActivity.class);
								intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								SurespotApplication.getAppContext().startActivity(intent);

								setUnauthorized(true);
							}

						}
					}
				}
			}
		});

	}

	public static void addUser(String username, String password, String publicKey, final AsyncHttpResponseHandler responseHandler) {
		Map<String, String> params = new HashMap<String, String>();
		params.put("username", username);
		params.put("password", password);
		params.put("publickey", publicKey);
		// get the gcm id
		SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
				android.content.Context.MODE_PRIVATE);
		String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);

		boolean gcmUpdatedTemp = false;
		if (gcmIdReceived != null) {

			params.put("gcmId", gcmIdReceived);
			gcmUpdatedTemp = true;
		}

		final boolean gcmUpdated = gcmUpdatedTemp;

		post("/users", new RequestParams(params), new AsyncHttpResponseHandler() {

			@Override
			public void onSuccess(int responseCode, String result) {
				setConnectCookie(extractConnectCookie(mCookieStore));
				if (mConnectCookie == null) {
					Log.e(TAG, "did not get cookie from signup");
					responseHandler.onFailure(new Exception("Did not get cookie."), "Did not get cookie.");
				}
				else {

					// update shared prefs
					if (gcmUpdated) {
						SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
								android.content.Context.MODE_PRIVATE);
						String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);
						settings.edit().putString(SurespotConstants.GCM_ID_SENT, gcmIdReceived);
					}

					responseHandler.onSuccess(responseCode, result);
				}

			}

			@Override
			public void onFailure(Throwable arg0, String content) {
				responseHandler.onFailure(arg0, content);
			}

		});

	}

	private static Cookie extractConnectCookie(CookieStore cookieStore) {
		for (Cookie c : cookieStore.getCookies()) {
			// System.out.println("Cookie name: " + c.getName() + " value: " +
			// c.getValue());
			if (c.getName().equals("connect.sid")) { return c; }
		}
		return null;

	}

	public static void login(String username, String password, final AsyncHttpResponseHandler responseHandler) {
		Map<String, String> params = new HashMap<String, String>();
		params.put("username", username);
		params.put("password", password);

		// get the gcm id
		SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
				android.content.Context.MODE_PRIVATE);
		String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);
		String gcmIdSent = settings.getString(SurespotConstants.GCM_ID_SENT, null);

		boolean gcmUpdatedTemp = false;
		// update the gcmid if it differs
		if (gcmIdReceived != null && !gcmIdReceived.equals(gcmIdSent)) {

			params.put("gcmId", gcmIdReceived);
			gcmUpdatedTemp = true;
		}

		// just be javascript already
		final boolean gcmUpdated = gcmUpdatedTemp;

		post("/login", new RequestParams(params), new AsyncHttpResponseHandler() {

			@Override
			public void onSuccess(int responseCode, String result) {
				setConnectCookie(extractConnectCookie(mCookieStore));
				if (mConnectCookie == null) {
					Log.e(TAG, "Did not get cookie from login.");
					responseHandler.onFailure(new Exception("Did not get cookie."), null);
				}
				else {
					// update shared prefs
					if (gcmUpdated) {
						SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
								android.content.Context.MODE_PRIVATE);
						String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);
						settings.edit().putString(SurespotConstants.GCM_ID_SENT, gcmIdReceived);
					}

					responseHandler.onSuccess(responseCode, result);
				}

			}

			@Override
			public void onFailure(Throwable arg0, String content) {
				responseHandler.onFailure(arg0, content);
			}
		});

	}

	public static void getFriends(AsyncHttpResponseHandler responseHandler) {
		get("/friends", null, responseHandler);
	}

	public static void getNotifications(AsyncHttpResponseHandler responseHandler) {
		get("/notifications", null, responseHandler);

	}

	public static void getMessages(String room, AsyncHttpResponseHandler responseHandler) {
		get("/conversations/" + room + "/messages", null, responseHandler);
	}

	public static void getPublicKey(String username, AsyncHttpResponseHandler responseHandler) {
		get("/publickey/" + username, null, responseHandler);

	}

	public static void invite(String friendname, AsyncHttpResponseHandler responseHandler) {

		post("/invite/" + friendname, null, responseHandler);

	}

	public static void respondToInvite(String friendname, String action, AsyncHttpResponseHandler responseHandler) {
		post("/invites/" + friendname + "/" + action, null, responseHandler);
	}

	public static void registerGcmId(final AsyncHttpResponseHandler responseHandler) {
		// make sure the gcm is set
		// use case:
		// user signs-up without google account (unlikely)
		// user creates google account
		// user opens app again, we have session so neither login or add user is called (which wolud set the gcm)
		// so we need to upload the gcm here if we haven't already
		// get the gcm id
		SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
				android.content.Context.MODE_PRIVATE);
		String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);
		String gcmIdSent = settings.getString(SurespotConstants.GCM_ID_SENT, null);

		Map<String, String> params = new HashMap<String, String>();

		boolean gcmUpdatedTemp = false;
		// update the gcmid if it differs
		if (gcmIdReceived != null && !gcmIdReceived.equals(gcmIdSent)) {

			params.put("gcmId", gcmIdReceived);
			gcmUpdatedTemp = true;
		}
		else {
			return;
		}

		// just be javascript already
		final boolean gcmUpdated = gcmUpdatedTemp;

		post("/registergcm", new RequestParams(params), new AsyncHttpResponseHandler() {

			@Override
			public void onSuccess(int responseCode, String result) {

				// update shared prefs
				if (gcmUpdated) {
					SharedPreferences settings = SurespotApplication.getAppContext().getSharedPreferences(SurespotConstants.PREFS_FILE,
							android.content.Context.MODE_PRIVATE);
					String gcmIdReceived = settings.getString(SurespotConstants.GCM_ID_RECEIVED, null);
					settings.edit().putString(SurespotConstants.GCM_ID_SENT, gcmIdReceived);
				}

				responseHandler.onSuccess(responseCode, result);
			}

			@Override
			public void onFailure(Throwable arg0, String arg1) {
				responseHandler.onFailure(arg0, arg1);
			}

		});

	}

	public static void userExists(String username, AsyncHttpResponseHandler responseHandler) {
		get("/users/" + username + "/exists", null, responseHandler);
	}

	/**
	 * Unregister this account/device pair within the server.
	 */
	public static void unregister(final Context context, final String regId) {
		Log.i(TAG, "unregistering device (regId = " + regId + ")");
		GCMRegistrar.setRegisteredOnServer(context, false);
	}
}