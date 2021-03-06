package com.twofours.surespot.backup;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import com.actionbarsherlock.app.SherlockActivity;
import com.actionbarsherlock.view.Menu;
import com.actionbarsherlock.view.MenuInflater;
import com.actionbarsherlock.view.MenuItem;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.common.AccountPicker;
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.model.ChildList;
import com.google.api.services.drive.model.ChildReference;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.ParentReference;
import com.twofours.surespot.R;
import com.twofours.surespot.common.FileUtils;
import com.twofours.surespot.common.SurespotConstants;
import com.twofours.surespot.common.SurespotLog;
import com.twofours.surespot.common.Utils;
import com.twofours.surespot.identity.IdentityController;
import com.twofours.surespot.network.IAsyncCallback;
import com.twofours.surespot.network.IAsyncCallbackTuple;
import com.twofours.surespot.ui.SingleProgressDialog;
import com.twofours.surespot.ui.UIUtils;

public class ExportIdentityActivity extends SherlockActivity {
	private static final String TAG = "ExportIdentityActivity";
	private List<String> mIdentityNames;
	private DriveHelper mDriveHelper;
	private Spinner mSpinner;

	private TextView mAccountNameDisplay;
	public static final String[] ACCOUNT_TYPE = new String[] { GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE };
	private SingleProgressDialog mSpd;
	private SingleProgressDialog mSpdBackupDir;
	private AlertDialog mDialog;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_export_identity);

		Utils.configureActionBar(this, getString(R.string.identity), getString(R.string.backup), true);
		final String identityDir = FileUtils.getIdentityExportDir().toString();

		TextView tvBackupWarning = (TextView) findViewById(R.id.backupIdentitiesWarning);
		Spannable s1 = new SpannableString(getString(R.string.help_backupIdentities1));
		s1.setSpan(new ForegroundColorSpan(Color.RED), 0, s1.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
		tvBackupWarning.setText(s1);

		final TextView tvPath = (TextView) findViewById(R.id.backupLocalLocation);
		mSpinner = (Spinner) findViewById(R.id.identitySpinner);

		final ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, R.layout.sherlock_spinner_item);
		adapter.setDropDownViewResource(R.layout.sherlock_spinner_dropdown_item);
		mIdentityNames = IdentityController.getIdentityNames(this);

		for (String name : mIdentityNames) {
			adapter.add(name);
		}

		mSpinner.setAdapter(adapter);

		String backupUsername = getIntent().getStringExtra("backupUsername");
		getIntent().removeExtra("backupUsername");

		mSpinner.setSelection(adapter.getPosition(backupUsername == null ? IdentityController.getLoggedInUser() : backupUsername));
		mSpinner.setOnItemSelectedListener(new OnItemSelectedListener() {

			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {

				String identityFile = identityDir + File.separator + IdentityController.caseInsensitivize(adapter.getItem(position))
						+ IdentityController.IDENTITY_EXTENSION;
				tvPath.setText(identityFile);
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent) {

			}

		});

		Button exportToSdCardButton = (Button) findViewById(R.id.bExportSd);

		exportToSdCardButton.setEnabled(FileUtils.isExternalStorageMounted());

		exportToSdCardButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				// TODO progress
				final String user = (String) mSpinner.getSelectedItem();
				mDialog = UIUtils.passwordDialog(ExportIdentityActivity.this, getString(R.string.backup_identity, user),
						getString(R.string.enter_password_for, user), new IAsyncCallback<String>() {
							@Override
							public void handleResponse(String result) {
								if (!TextUtils.isEmpty(result)) {
									exportIdentity(user, result);
								}
								else {
									Utils.makeToast(ExportIdentityActivity.this, getString(R.string.no_identity_exported));
								}
							}
						});

			}
		});

		mDriveHelper = new DriveHelper(getApplicationContext(), true);
		Button exportToGoogleDriveButton = (Button) findViewById(R.id.bBackupDrive);

		exportToGoogleDriveButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {
				if (mDriveHelper.getDriveAccount() != null) {
					if (mDriveHelper.getDriveService() != null) {
						backupIdentityDrive(true);
					}
				}
				else {
					chooseAccount(false);
				}
			}
		});

		mAccountNameDisplay = (TextView) findViewById(R.id.exportDriveAccount);
		String account = getSharedPreferences(IdentityController.getLoggedInUser(), Context.MODE_PRIVATE).getString("pref_google_drive_account",
				getString(R.string.no_google_account_selected));
		mAccountNameDisplay.setText(account);

		Button chooseAccountButton = (Button) findViewById(R.id.bSelectDriveAccount);
		chooseAccountButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View v) {

				chooseAccount(true);
			}
		});
	}

	// //////// Local
	private void exportIdentity(String user, String password) {
		IdentityController.exportIdentity(ExportIdentityActivity.this, user, password, new IAsyncCallback<String>() {
			@Override
			public void handleResponse(String response) {
				if (response == null) {
					Utils.makeToast(ExportIdentityActivity.this, getString(R.string.no_identity_exported));
				}
				else {
					Utils.makeLongToast(ExportIdentityActivity.this, response);
				}

			}
		});
	}

	// //////// DRIVE

	private void chooseAccount(boolean ask) {
		Intent accountPickerIntent = AccountPicker.newChooseAccountIntent(null, null, ACCOUNT_TYPE, ask, null, null, null, null);

		try {
			startActivityForResult(accountPickerIntent, SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT);
		}
		catch (ActivityNotFoundException e) {
			Utils.makeToast(ExportIdentityActivity.this, getString(R.string.device_does_not_support_google_drive));
			SurespotLog.i(TAG, e, "chooseAccount");
		}

	}

	private void removeAccount() {
		SharedPreferences.Editor editor = getSharedPreferences(IdentityController.getLoggedInUser(), Context.MODE_PRIVATE).edit();
		editor.remove("pref_google_drive_account");
		editor.commit();

		mAccountNameDisplay.setText(R.string.no_google_account_selected);

	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case SurespotConstants.IntentRequestCodes.CHOOSE_GOOGLE_ACCOUNT:
			if (data != null) {

				SurespotLog.w("Preferences", "SELECTED ACCOUNT WITH EXTRA: %s", data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
				Bundle b = data.getExtras();

				String accountName = b.getString(AccountManager.KEY_ACCOUNT_NAME);

				SurespotLog.d("Preferences", "Selected account: " + accountName);
				if (accountName != null && accountName.length() > 0) {

					mDriveHelper.setDriveAccount(accountName);
					mAccountNameDisplay.setText(accountName);
				}
			}
			break;

		case SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH:
			if (resultCode == Activity.RESULT_OK) {
				Drive drive = mDriveHelper.getDriveService();
				if (drive != null) {
					backupIdentityDrive(false);

				}
			}
			else {

			}
		}
	}

	private void backupIdentityDrive(final boolean firstAttempt) {
		if (mSpdBackupDir == null) {
			mSpdBackupDir = new SingleProgressDialog(this, getString(R.string.progress_drive_dir_check), 1000);
		}
		mSpdBackupDir.show();
		new AsyncTask<Void, Void, String>() {

			@Override
			protected String doInBackground(Void... params) {

				return ensureDriveIdentityDirectory();

			}

			protected void onPostExecute(final String identityDirId) {
				mSpdBackupDir.hide();
				if (identityDirId == null) {
					if (!firstAttempt) {
						Utils.makeToast(ExportIdentityActivity.this, getString(R.string.could_not_backup_identity_to_google_drive));
					}
					return;
				}

				SurespotLog.d(TAG, "identity file id: %s", identityDirId);
				final String user = (String) mSpinner.getSelectedItem();
				mDialog = UIUtils.passwordDialog(ExportIdentityActivity.this, getString(R.string.backup_identity, user),
						getString(R.string.enter_password_for, user), new IAsyncCallback<String>() {
							@Override
							public void handleResponse(final String password) {
								if (!TextUtils.isEmpty(password)) {
									if (mSpd == null) {
										mSpd = new SingleProgressDialog(ExportIdentityActivity.this, getString(R.string.progress_backup_identity_drive), 0);
									}

									mSpd.show();

									IdentityController.getExportIdentity(ExportIdentityActivity.this, user, password,
											new IAsyncCallbackTuple<byte[], String>() {

												@Override
												public void handleResponse(byte[] identityData, final String message) {

													if (identityData == null) {

														ExportIdentityActivity.this.runOnUiThread(new Runnable() {

															@Override
															public void run() {
																mSpd.hide();
																Utils.makeToast(ExportIdentityActivity.this,
																		message == null ? getString(R.string.could_not_backup_identity_to_google_drive)
																				: message);

															}
														});

														return;
													}

													final boolean backedUp = updateIdentityDriveFile(identityDirId, user, identityData);

													ExportIdentityActivity.this.runOnUiThread(new Runnable() {

														@Override
														public void run() {
															mSpd.hide();
															if (!backedUp) {
																Utils.makeToast(ExportIdentityActivity.this,
																		getString(R.string.could_not_backup_identity_to_google_drive));
															}
															else {
																Utils.makeToast(ExportIdentityActivity.this,
																		getString(R.string.identity_successfully_backed_up_to_google_drive));
															}
														}
													});

												}
											});

								}
								else {
									Utils.makeToast(ExportIdentityActivity.this, getString(R.string.no_identity_exported));
								}
							}
						});

			};

		}.execute();

	}

	public String ensureDriveIdentityDirectory() {
		String identityDirId = null;
		try {
			// see if identities directory exists

			FileList identityDir = mDriveHelper.getDriveService().files().list()
					.setQ("title = '" + SurespotConstants.DRIVE_IDENTITY_FOLDER + "' and trashed = false").execute();
			List<com.google.api.services.drive.model.File> items = identityDir.getItems();

			if (items.size() > 0) {
				for (com.google.api.services.drive.model.File file : items) {
					if (!file.getLabels().getTrashed()) {
						SurespotLog.d(TAG, "identity folder already exists");
						identityDirId = file.getId();
					}
				}
			}
			if (identityDirId == null) {
				com.google.api.services.drive.model.File file = new com.google.api.services.drive.model.File();
				file.setTitle(SurespotConstants.DRIVE_IDENTITY_FOLDER);
				file.setMimeType(SurespotConstants.MimeTypes.DRIVE_FOLDER);

				com.google.api.services.drive.model.File insertedFile = mDriveHelper.getDriveService().files().insert(file).execute();

				identityDirId = insertedFile.getId();

			}

		}
		catch (UserRecoverableAuthIOException e) {
			try {
				startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
			}
			catch (NullPointerException npe) {
				return null;
			}
		}
		catch (IOException e) {
			SurespotLog.w(TAG, e, "createDriveIdentityDirectory");
		}
		catch (SecurityException e) {
			SurespotLog.e(TAG, e, "createDriveIdentityDirectory");
			// when key is revoked on server this happens...should return userrecoverable it seems
			// was trying to figure out how to test this
			// seems like the only way around this is to remove and re-add android account:
			// http://stackoverflow.com/questions/5805657/revoke-account-permission-for-an-app
			this.runOnUiThread(new Runnable() {

				@Override
				public void run() {
					Utils.makeLongToast(ExportIdentityActivity.this, getString(R.string.re_add_google_account));

				}
			});

		}
		return identityDirId;

	}

	public boolean updateIdentityDriveFile(String idDirId, String username, byte[] identityData) {
		try {
			// gzip identity for consistency - fucked up on this, now have to add code to handle both (gzipped and not gzipped) on restore from google drive
			// RM#260
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			GZIPOutputStream gzout = new GZIPOutputStream(out);
			gzout.write(identityData);
			gzout.close();
			byte[] gzIdData = out.toByteArray();

			ByteArrayContent content = new ByteArrayContent("application/octet-stream", gzIdData);
			String caseInsensitiveUsername = IdentityController.caseInsensitivize(username);
			String filename = caseInsensitiveUsername + IdentityController.IDENTITY_EXTENSION;

			// see if identity exists
			com.google.api.services.drive.model.File file = null;
			ChildReference idFile = getIdentityFile(idDirId, caseInsensitiveUsername);
			if (idFile != null) {

				// update
				file = mDriveHelper.getDriveService().files().get(idFile.getId()).execute();
				if (file != null && !file.getLabels().getTrashed()) {
					SurespotLog.d(TAG, "updateIdentityDriveFile, updating existing identity file: %s", filename);
					mDriveHelper.getDriveService().files().update(file.getId(), file, content).execute();
					return true;
				}
			}

			// create
			SurespotLog.d(TAG, "updateIdentityDriveFile, inserting new identity file: %s", filename);

			file = new com.google.api.services.drive.model.File();
			ParentReference pr = new ParentReference();
			pr.setId(idDirId);
			ArrayList<ParentReference> parent = new ArrayList<ParentReference>(1);
			parent.add(pr);
			file.setParents(parent);
			file.setTitle(filename);
			file.setMimeType(SurespotConstants.MimeTypes.SURESPOT_IDENTITY);

			com.google.api.services.drive.model.File insertedFile = mDriveHelper.getDriveService().files().insert(file, content).execute();
			return true;

		}
		catch (UserRecoverableAuthIOException e) {
			startActivityForResult(e.getIntent(), SurespotConstants.IntentRequestCodes.REQUEST_GOOGLE_AUTH);
		}
		catch (IOException e) {
			SurespotLog.w(TAG, e, "updateIdentityDriveFile");
		}
		catch (SecurityException e) {
			SurespotLog.e(TAG, e, "createDriveIdentityDirectory");
			// when key is revoked on server this happens...should return userrecoverable it seems
			// was trying to figure out how to test this
			// seems like the only way around this is to remove and re-add android account:
			// http://stackoverflow.com/questions/5805657/revoke-account-permission-for-an-app
			this.runOnUiThread(new Runnable() {

				@Override
				public void run() {
					Utils.makeLongToast(ExportIdentityActivity.this, getString(R.string.re_add_google_account));

				}
			});

		}
		return false;
	}

	private ChildReference getIdentityFile(String identityDirId, String username) throws IOException {
		ChildReference idFile = null;

		// "title = '" + username + "'";
		ChildList identityFileList = mDriveHelper.getDriveService().children().list(identityDirId)
				.setQ("title='" + username + IdentityController.IDENTITY_EXTENSION + "' and trashed = false").execute();
		List<ChildReference> items = identityFileList.getItems();

		if (items.size() == 1) {
			SurespotLog.d(TAG, "getIdentityFile, found identity file for: %s", username);
			idFile = items.get(0);
			// for (ChildReference file : items) {
			// if (!file.getLabels().getTrashed()) {
			// SurespotLog.d(TAG, "identity folder already exists");
			// identityDirId = file.getId();
			// }
			// }
		}
		else {
			if (items.size() > 1) {
				// delete all but one identity...should never happen
				SurespotLog.w(TAG, "$d identities with the same filename found on google drive: %s", items.size(), username);

				for (int i = items.size(); i > 1; i--) {
					SurespotLog.w(TAG, "deleting identity file from google drive %s", username);
					mDriveHelper.getDriveService().files().delete(items.get(i - 1).getId()).execute();
				}
				idFile = items.get(0);
			}
		}

		return idFile;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getSupportMenuInflater();
		inflater.inflate(R.menu.menu_help, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:
			finish();

			return true;
		case R.id.menu_help:
			View view = LayoutInflater.from(this).inflate(R.layout.dialog_help_backup, null);

			TextView tv = (TextView) view.findViewById(R.id.helpBackup1);
			UIUtils.setHtml(this, tv, R.string.help_backup_what);

			TextView t1 = (TextView) view.findViewById(R.id.helpBackup2);
			t1.setText(Html.fromHtml(getString(R.string.help_backup_local)));
			t1.setMovementMethod(LinkMovementMethod.getInstance());

			TextView t2 = (TextView) view.findViewById(R.id.helpBackup3);
			UIUtils.setHtml(this, t2, R.string.help_backup_drive1);

			t2 = (TextView) view.findViewById(R.id.helpBackup4);
			t2.setText(R.string.help_backup_drive2);

			mDialog = UIUtils.showHelpDialog(this, R.string.surespot_help, view, false);
			return true;

		default:
			return super.onOptionsItemSelected(item);
		}

	}

	@Override
	protected void onPause() {
		super.onPause();
		if (mDialog != null && mDialog.isShowing()) {
			mDialog.dismiss();
		}
	}
}
