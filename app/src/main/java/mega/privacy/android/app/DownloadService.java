package mega.privacy.android.app;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.content.LocalBroadcastManager;
import android.text.format.Formatter;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import mega.privacy.android.app.lollipop.AudioVideoPlayerLollipop;
import mega.privacy.android.app.lollipop.LoginActivityLollipop;
import mega.privacy.android.app.lollipop.ManagerActivityLollipop;
import mega.privacy.android.app.lollipop.PdfViewerActivityLollipop;
import mega.privacy.android.app.lollipop.ZipBrowserActivityLollipop;
import mega.privacy.android.app.lollipop.managerSections.OfflineFragmentLollipop;
import mega.privacy.android.app.lollipop.managerSections.SettingsFragmentLollipop;
import mega.privacy.android.app.lollipop.megachat.ChatSettings;
import nz.mega.sdk.MegaApiAndroid;
import nz.mega.sdk.MegaApiJava;
import nz.mega.sdk.MegaChatApi;
import nz.mega.sdk.MegaChatApiAndroid;
import nz.mega.sdk.MegaChatApiJava;
import nz.mega.sdk.MegaChatError;
import nz.mega.sdk.MegaChatRequest;
import nz.mega.sdk.MegaChatRequestListenerInterface;
import nz.mega.sdk.MegaError;
import nz.mega.sdk.MegaNode;
import nz.mega.sdk.MegaRequest;
import nz.mega.sdk.MegaRequestListenerInterface;
import nz.mega.sdk.MegaTransfer;
import nz.mega.sdk.MegaTransferListenerInterface;

import static mega.privacy.android.app.lollipop.AudioVideoPlayerLollipop.IS_PLAYLIST;
import static mega.privacy.android.app.utils.CacheFolderManager.*;
import static mega.privacy.android.app.utils.FileUtils.*;
import static mega.privacy.android.app.utils.LogUtil.*;
import static mega.privacy.android.app.utils.OfflineUtils.*;
import static mega.privacy.android.app.utils.ThumbnailUtilsLollipop.*;
import static mega.privacy.android.app.utils.Util.*;
import static mega.privacy.android.app.utils.MegaApiUtils.*;
import static mega.privacy.android.app.utils.Constants.*;

/*
 * Background service to download files
 */
public class DownloadService extends Service implements MegaTransferListenerInterface, MegaRequestListenerInterface, MegaChatRequestListenerInterface {

	// Action to stop download
	public static String ACTION_CANCEL = "CANCEL_DOWNLOAD";
	public static String EXTRA_SIZE = "DOCUMENT_SIZE";
	public static String EXTRA_HASH = "DOCUMENT_HASH";
	public static String EXTRA_URL = "DOCUMENT_URL";
	public static String EXTRA_PATH = "SAVE_PATH";
	public static String EXTRA_FOLDER_LINK = "FOLDER_LINK";
	public static String EXTRA_CONTACT_ACTIVITY = "CONTACT_ACTIVITY";
	public static String EXTRA_ZIP_FILE_TO_OPEN = "FILE_TO_OPEN";
	public static String EXTRA_OPEN_FILE = "OPEN_FILE";
	public static String EXTRA_CONTENT_URI = "CONTENT_URI";
	public static String EXTRA_SERIALIZE_STRING = "SERIALIZE_STRING";

	private int errorCount = 0;
	private int alreadyDownloaded = 0;

	private boolean isForeground = false;
	private boolean canceled;

	private String pathFileToOpen;
	private boolean openFile = true;
	private String type = "";
	private boolean isOverquota = false;
	private long downloadedBytesToOverquota = 0;

	MegaApplication app;
	MegaApiAndroid megaApi;
	MegaApiAndroid megaApiFolder;
	MegaChatApiAndroid megaChatApi;
	ChatSettings chatSettings;

	ArrayList<Intent> pendingIntents = new ArrayList<Intent>();

	WifiLock lock;
	WakeLock wl;

	File currentFile;
	File currentDir;
	MegaNode currentDocument;

	DatabaseHandler dbH = null;

	int transfersCount = 0;

	HashMap<Long, Uri> storeToAdvacedDevices;
	HashMap<Long, Boolean> fromMediaViewers;

	private int notificationId = NOTIFICATION_DOWNLOAD;
	private int notificationIdFinal = NOTIFICATION_DOWNLOAD_FINAL;
	private String notificationChannelId = NOTIFICATION_CHANNEL_DOWNLOAD_ID;
	private String notificationChannelName = NOTIFICATION_CHANNEL_DOWNLOAD_NAME;
	private NotificationCompat.Builder mBuilderCompat;
	private Notification.Builder mBuilder;
	private NotificationManager mNotificationManager;

	MegaNode offlineNode;

	boolean isLoggingIn = false;
	private long lastUpdated;

	@SuppressLint("NewApi")
	@Override
	public void onCreate(){
		super.onCreate();
		logDebug("onCreate");

		app = (MegaApplication)getApplication();
		megaApi = app.getMegaApi();
		megaApiFolder = app.getMegaApiFolder();
		megaChatApi = app.getMegaChatApi();

		isForeground = false;
		canceled = false;

		storeToAdvacedDevices = new HashMap<Long, Uri>();
		fromMediaViewers = new HashMap<>();

		int wifiLockMode = WifiManager.WIFI_MODE_FULL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR1) {
            wifiLockMode = WifiManager.WIFI_MODE_FULL_HIGH_PERF;
        }

        dbH = DatabaseHandler.getDbHandler(getApplicationContext());

		WifiManager wifiManager = (WifiManager) getApplicationContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
		lock = wifiManager.createWifiLock(wifiLockMode, "MegaDownloadServiceWifiLock");
		PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MegaDownloadServicePowerLock");
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH){
			mBuilder = new Notification.Builder(DownloadService.this);
		}
		mBuilderCompat = new NotificationCompat.Builder(getApplicationContext());
		mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

	}

	@Override
	public void onDestroy(){
		logDebug("onDestroy");
		if((lock != null) && (lock.isHeld()))
			try{ lock.release(); } catch(Exception ex) {}
		if((wl != null) && (wl.isHeld()))
			try{ wl.release(); } catch(Exception ex) {}

		if(megaApi != null)
		{
			megaApi.removeRequestListener(this);
            megaApi.removeTransferListener(this);
		}

		if (megaChatApi != null){
			megaChatApi.saveCurrentState();
		}

		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		logDebug("onStartCommand");
		canceled = false;

		if(intent == null){
			logWarning("intent==null");
			return START_NOT_STICKY;
		}

		if (intent.getAction() != null){
			if (intent.getAction().equals(ACTION_CANCEL)){
				logDebug("Cancel intent");
				canceled = true;
				megaApi.cancelTransfers(MegaTransfer.TYPE_DOWNLOAD, this);
				megaApiFolder.cancelTransfers(MegaTransfer.TYPE_DOWNLOAD, this);
				return START_NOT_STICKY;
			}
		}

		onHandleIntent(intent);
		return START_NOT_STICKY;
	}

	private boolean isVoiceClipType(String value) {
		return (value != null) && (value.contains(EXTRA_VOICE_CLIP));
	}

    protected void onHandleIntent(final Intent intent) {
		logDebug("onHandleIntent");

        long hash = intent.getLongExtra(EXTRA_HASH, -1);
        String url = intent.getStringExtra(EXTRA_URL);
        boolean isFolderLink = intent.getBooleanExtra(EXTRA_FOLDER_LINK, false);
        openFile = intent.getBooleanExtra(EXTRA_OPEN_FILE, true);
		type = intent.getStringExtra(EXTRA_TRANSFER_TYPE);

		Uri contentUri = null;
        if(intent.getStringExtra(EXTRA_CONTENT_URI)!=null){
            contentUri = Uri.parse(intent.getStringExtra(EXTRA_CONTENT_URI));
        }

        boolean highPriority = intent.getBooleanExtra(HIGH_PRIORITY_TRANSFER, false);
        boolean fromMV = intent.getBooleanExtra("fromMV", false);
		logDebug("fromMV: " + fromMV);

		megaApi = app.getMegaApi();

		UserCredentials credentials = dbH.getCredentials();

		if (credentials != null) {

			String gSession = credentials.getSession();

			if (megaApi.getRootNode() == null) {
				isLoggingIn = MegaApplication.isLoggingIn();
				if (!isLoggingIn) {
					isLoggingIn = true;
					MegaApplication.setLoggingIn(isLoggingIn);

					if (isChatEnabled()) {
						if (megaChatApi == null) {
							megaChatApi = ((MegaApplication) getApplication()).getMegaChatApi();
						}

						int ret = megaChatApi.getInitState();

						if(ret==MegaChatApi.INIT_NOT_DONE||ret==MegaChatApi.INIT_ERROR){
							ret = megaChatApi.init(gSession);
							logDebug("result of init ---> " + ret);
							chatSettings = dbH.getChatSettings();
							if (ret == MegaChatApi.INIT_NO_CACHE) {
								logDebug("condition ret == MegaChatApi.INIT_NO_CACHE");
							} else if (ret == MegaChatApi.INIT_ERROR) {
								logDebug("condition ret == MegaChatApi.INIT_ERROR");
								if (chatSettings == null) {
									logError("ERROR----> Switch OFF chat");
									chatSettings = new ChatSettings();
									chatSettings.setEnabled(false+"");
									dbH.setChatSettings(chatSettings);
								} else {
									logError("ERROR----> Switch OFF chat");
									dbH.setEnabledChat(false + "");
								}
								megaChatApi.logout(this);
							} else {
								logDebug("Chat correctly initialized");
							}
						}
					}

					pendingIntents.add(intent);
					if (!isVoiceClipType(type)) {
						updateProgressNotification();
					}

					megaApi.fastLogin(gSession, this);
					return;
				}
				else{
					logWarning("Another login is processing");
				}
				pendingIntents.add(intent);
				return;
			}
		}

		String serialize = intent.getStringExtra(EXTRA_SERIALIZE_STRING);

		if(serialize!=null){
			logDebug("serializeString: " + serialize);
			currentDocument = MegaNode.unserialize(serialize);
			if(currentDocument != null){
				hash = currentDocument.getHandle();
				logDebug("hash after unserialize: " + hash);
			}
			else{
				logWarning("Node is NULL after unserialize");
			}
		}
		else{
			if (isFolderLink){
				currentDocument = megaApiFolder.getNodeByHandle(hash);
			}
			else{
				currentDocument = megaApi.getNodeByHandle(hash);
			}
		}

        if(intent.getStringExtra(EXTRA_ZIP_FILE_TO_OPEN)!=null){
            pathFileToOpen = intent.getStringExtra(EXTRA_ZIP_FILE_TO_OPEN);
        }
        else{
            pathFileToOpen=null;
        }

        if(url != null){
			logDebug("Public node");
            currentDir = new File(intent.getStringExtra(EXTRA_PATH));
            if (currentDir != null){
                currentDir.mkdirs();
            }
            megaApi.getPublicNode(url, this);
            return;
        }

		if((currentDocument == null) && (url == null)){
			logWarning("Node not found");
			return;
		}

		fromMediaViewers.put(currentDocument.getHandle(), fromMV);

        currentDir = getDir(currentDocument, intent);
        currentDir.mkdirs();
        if (currentDir.isDirectory()){
			logDebug("currentDir is Directory");
            currentFile = new File(currentDir, megaApi.escapeFsIncompatible(currentDocument.getName()));
        }
        else{
			logDebug("currentDir is File");
            currentFile = currentDir;
        }

		logDebug("dir: " + currentDir.getAbsolutePath() + " file: " + currentDocument.getName() + "  Size: " + currentDocument.getSize());
        if(!checkCurrentFile(currentDocument)){
			logDebug("checkCurrentFile == false");

			alreadyDownloaded++;
            if ((megaApi.getNumPendingDownloads() == 0) && (megaApiFolder.getNumPendingDownloads() == 0)){
                onQueueComplete(currentDocument.getHandle());
            }

            return;
        }

        if(!wl.isHeld()){
            wl.acquire();
        }
        if(!lock.isHeld()){
            lock.acquire();
        }

        if(contentUri!=null){
			logDebug("contentUri is NOT null");
            //To download to Advanced Devices
			logDebug("Download to advanced devices checked");
            currentDir = new File(intent.getStringExtra(EXTRA_PATH));
            currentDir.mkdirs();

            if (currentDir.isDirectory()){
				logDebug("To download(dir): " + currentDir.getAbsolutePath() + "/");
            }
            else{
				logWarning("currentDir is not a directory");
            }
            storeToAdvacedDevices.put(currentDocument.getHandle(), contentUri);

			if (currentDir.getAbsolutePath().contains(OFFLINE_DIR)){
				logDebug("currentDir contains OFFLINE_DIR");
				openFile = false;
			}
			else {
				logDebug("currentDir is NOT on OFFLINE_DIR: openFile->" + openFile);
			}

			if (isFolderLink){
				if (dbH.getCredentials() == null) {
					megaApiFolder.startDownload(currentDocument, currentDir.getAbsolutePath() + "/", this);
					logWarning("getCredentials null");
					return;
				}

				logDebug("Folder link node");
				MegaNode currentDocumentAuth = megaApiFolder.authorizeNode(currentDocument);
				if (currentDocumentAuth == null){
					logWarning("CurrentDocumentAuth is null");
					megaApiFolder.startDownload(currentDocument, currentDir.getAbsolutePath() + "/", this);
					return;
				}
				else{
					logDebug("CurrentDocumentAuth is not null");
					currentDocument = megaApiFolder.authorizeNode(currentDocument);
				}
			}

			logDebug("CurrentDocument is not null");

			if (highPriority) {
				String data = isVoiceClipType(type) ? EXTRA_VOICE_CLIP : "";
				megaApi.startDownloadWithTopPriority(currentDocument, currentDir.getAbsolutePath() + "/", data, this);
			} else {
				megaApi.startDownload(currentDocument, currentDir.getAbsolutePath() + "/", this);
			}
        }
        else{
			logDebug("contentUri NULL");
            if (currentDir.isDirectory()){
				logDebug("To download(dir): " + currentDir.getAbsolutePath() + "/");

                if(currentFile.exists()){
					logDebug("The file already exists!");
                    //Check the fingerprint
                    String localFingerprint = megaApi.getFingerprint(currentFile.getAbsolutePath());
                    String megaFingerprint = megaApi.getFingerprint(currentDocument);

                    if((localFingerprint!=null) && (!localFingerprint.isEmpty()) && (megaFingerprint!=null) && (!megaFingerprint.isEmpty()))
                    {
                        if(localFingerprint.compareTo(megaFingerprint)!=0)
                        {
							logDebug("Delete the old version");
                            currentFile.delete();
                        }
                    }
                }

                if (currentDocument.isFolder()){
					logDebug("IS FOLDER");
                }
                else{
					logDebug("IS FILE");
                }

				if (currentDir.getAbsolutePath().contains(OFFLINE_DIR)){
					logDebug("currentDir contains OFFLINE_DIR");
					openFile = false;
				}
				else {
					logDebug("currentDir is NOT on OFFLINE_DIR: openFile->" + openFile);
				}

                if (isFolderLink){

					logDebug("Folder link node");
                    MegaNode currentDocumentAuth = megaApiFolder.authorizeNode(currentDocument);
                    if (currentDocumentAuth == null){
						logWarning("CurrentDocumentAuth is null");
                        megaApiFolder.startDownload(currentDocument, currentDir.getAbsolutePath() + "/", this);
                        return;
                    }
                    else{
						logDebug("CurrentDocumentAuth is not null");
						currentDocument = megaApiFolder.authorizeNode(currentDocument);
					}
                }

				logDebug("CurrentDocument is not null");
				if(highPriority){
					String data = isVoiceClipType(type)? EXTRA_VOICE_CLIP : "";
					megaApi.startDownloadWithTopPriority(currentDocument, currentDir.getAbsolutePath() + "/", data, this);
				}
				else{
					megaApi.startDownload(currentDocument, currentDir.getAbsolutePath() + "/", this);
				}

            }
            else{
				logWarning("currentDir is not a directory");
            }
        }
    }

	private void onQueueComplete(long handle) {
		logDebug("onQueueComplete");

		if((lock != null) && (lock.isHeld()))
			try{ lock.release(); } catch(Exception ex) {}
		if((wl != null) && (wl.isHeld()))
			try{ wl.release(); } catch(Exception ex) {}

        showCompleteNotification(handle);

		isForeground = false;
		stopForeground(true);
		mNotificationManager.cancel(notificationId);
		stopSelf();

		int total = megaApi.getNumPendingDownloads() + megaApiFolder.getNumPendingDownloads();
		logDebug("onQueueComplete: total of files before reset " + total);
		if(total <= 0){
			logDebug("onQueueComplete: reset total downloads");
			megaApi.resetTotalDownloads();
			megaApiFolder.resetTotalDownloads();
			errorCount = 0;
			alreadyDownloaded = 0;
		}
	}

	private File getDir(MegaNode document, Intent intent) {
		boolean toDownloads = (intent.hasExtra(EXTRA_PATH) == false);
		File destDir;
		if (toDownloads) {
			destDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
		} else {
			destDir = new File(intent.getStringExtra(EXTRA_PATH));
		}
		logDebug("Save to: " + destDir.getAbsolutePath());
		return destDir;
	}

	boolean checkCurrentFile(MegaNode document)	{
		logDebug("checkCurrentFile");
		if(currentFile.exists() && (document.getSize() == currentFile.length())){

			currentFile.setReadable(true, false);
//			Toast.makeText(getApplicationContext(), document.getName() + " " +  getString(R.string.general_already_downloaded), Toast.LENGTH_SHORT).show();

			return false;
		}

		if(document.getSize() > ((long)1024*1024*1024*4))
		{
			logDebug("Show size alert: " + document.getSize());
	    	Toast.makeText(getApplicationContext(), getString(R.string.error_file_size_greater_than_4gb),
	    			Toast.LENGTH_LONG).show();
	    	Toast.makeText(getApplicationContext(), getString(R.string.error_file_size_greater_than_4gb),
	    			Toast.LENGTH_LONG).show();
	    	Toast.makeText(getApplicationContext(), getString(R.string.error_file_size_greater_than_4gb),
	    			Toast.LENGTH_LONG).show();
		}
		return true;
	}

	/*
	 * Show download success notification
	 */
	private void showCompleteNotification(long handle) {
		logDebug("showCompleteNotification");
		String notificationTitle, size;

        int totalDownloads = megaApi.getTotalDownloads() + megaApiFolder.getTotalDownloads();

		if(alreadyDownloaded>0 && errorCount>0){
			int totalNumber = totalDownloads + errorCount + alreadyDownloaded;
			notificationTitle = getResources().getQuantityString(R.plurals.download_service_final_notification_with_details, totalNumber, totalDownloads, totalNumber);

			String copiedString = getResources().getQuantityString(R.plurals.already_downloaded_service, alreadyDownloaded, alreadyDownloaded);;
			String errorString = getResources().getQuantityString(R.plurals.upload_service_failed, errorCount, errorCount);
			size = copiedString+", "+errorString;
		}
		else if(alreadyDownloaded>0){
			int totalNumber = totalDownloads + alreadyDownloaded;
			notificationTitle = getResources().getQuantityString(R.plurals.download_service_final_notification_with_details, totalNumber, totalDownloads, totalNumber);

			size = getResources().getQuantityString(R.plurals.already_downloaded_service, alreadyDownloaded, alreadyDownloaded);
		}
		else if(errorCount>0){
			int totalNumber = totalDownloads + errorCount;
			notificationTitle = getResources().getQuantityString(R.plurals.download_service_final_notification_with_details, totalNumber, totalDownloads, totalNumber);

			size = getResources().getQuantityString(R.plurals.download_service_failed, errorCount, errorCount);
		}
		else{
			notificationTitle = getResources().getQuantityString(R.plurals.download_service_final_notification, totalDownloads, totalDownloads);
			String totalBytes = Formatter.formatFileSize(DownloadService.this, megaApi.getTotalDownloadedBytes()+megaApiFolder.getTotalDownloadedBytes());
			size = getString(R.string.general_total_size, totalBytes);
		}

		Intent intent = null;
		if(totalDownloads != 1)
		{
			intent = new Intent(getApplicationContext(), ManagerActivityLollipop.class);

			logDebug("Show notification");
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
				channel.setShowBadge(true);
				channel.setSound(null, null);
				mNotificationManager.createNotificationChannel(channel);

				NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

				mBuilderCompatO
						.setSmallIcon(R.drawable.ic_stat_notify)
						.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
						.setAutoCancel(true).setTicker(notificationTitle)
						.setContentTitle(notificationTitle).setContentText(size)
						.setOngoing(false);

				mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

				mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
			}
			else {
				mBuilderCompat
						.setSmallIcon(R.drawable.ic_stat_notify)
						.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
						.setAutoCancel(true).setTicker(notificationTitle)
						.setContentTitle(notificationTitle).setContentText(size)
						.setOngoing(false);

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
					mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
				}

				mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
			}
		}
		else
		{
			try {
                boolean autoPlayEnabled = Boolean.parseBoolean(dbH.getAutoPlayEnabled());
                if (openFile && autoPlayEnabled) {
					logDebug("Both openFile and autoPlayEnabled are true");
					Boolean externalFile;
					if (!currentFile.getAbsolutePath().contains(Environment.getExternalStorageDirectory().getPath())){
						externalFile = true;
					}
					else {
						externalFile = false;
					}

					boolean fromMV = false;
					if (fromMediaViewers.containsKey(handle)){
						fromMV = fromMediaViewers.get(handle);
					}

					if (MimeTypeList.typeForName(currentFile.getName()).isZip()){
						logDebug("Download success of zip file!");

						if(pathFileToOpen!=null){
							Intent intentZip;
							intentZip = new Intent(this, ZipBrowserActivityLollipop.class);
							intentZip.setAction(ZipBrowserActivityLollipop.ACTION_OPEN_ZIP_FILE);
							intentZip.putExtra(ZipBrowserActivityLollipop.EXTRA_ZIP_FILE_TO_OPEN, pathFileToOpen);
							intentZip.putExtra(ZipBrowserActivityLollipop.EXTRA_PATH_ZIP, currentFile.getAbsolutePath());
							intentZip.putExtra(ZipBrowserActivityLollipop.EXTRA_HANDLE_ZIP, currentDocument.getHandle());


							if(intentZip!=null){
								intentZip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								startActivity(intentZip);
							}
						}
						else{
							Intent intentZip = null;

							intentZip = new Intent(this, ManagerActivityLollipop.class);
							intentZip.setAction(ACTION_EXPLORE_ZIP);
							intentZip.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							intentZip.putExtra(EXTRA_PATH_ZIP, currentFile.getAbsolutePath());

							startActivity(intentZip);
						}

						logDebug("Launch intent to manager.....");
					}
					else if (MimeTypeList.typeForName(currentFile.getName()).isPdf()){
						logDebug("Pdf file");

						if (!fromMV) {
							Intent pdfIntent = new Intent(this, PdfViewerActivityLollipop.class);

							pdfIntent.putExtra("HANDLE", handle);
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !externalFile) {
								pdfIntent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							} else {
								pdfIntent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							}
							pdfIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							pdfIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
								pdfIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							}
							pdfIntent.putExtra("fromDownloadService", true);
							pdfIntent.putExtra("inside", true);
							pdfIntent.putExtra("isUrl", false);
							startActivity(pdfIntent);
						}
						else {
							logDebug("Show notification");
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
								NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
								channel.setShowBadge(true);
								channel.setSound(null, null);
								mNotificationManager.createNotificationChannel(channel);

								NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

								mBuilderCompatO
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
							}
							else {
								mBuilderCompat
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
							}
						}
					}
					else if (MimeTypeList.typeForName(currentFile.getName()).isVideoReproducible() || MimeTypeList.typeForName(currentFile.getName()).isAudio()) {
						logDebug("Video/Audio file");

						if (!fromMV) {
							Intent mediaIntent;
							boolean internalIntent;
							boolean opusFile = false;
							if (MimeTypeList.typeForName(currentFile.getName()).isVideoNotSupported() || MimeTypeList.typeForName(currentFile.getName()).isAudioNotSupported()) {
								mediaIntent = new Intent(Intent.ACTION_VIEW);
								internalIntent = false;
								String[] s = currentFile.getName().split("\\.");
								if (s != null && s.length > 1 && s[s.length - 1].equals("opus")) {
									opusFile = true;
								}
							} else {
								internalIntent = true;
								mediaIntent = new Intent(this, AudioVideoPlayerLollipop.class);
							}

							mediaIntent.putExtra(IS_PLAYLIST, false);
							mediaIntent.putExtra("HANDLE", handle);
							mediaIntent.putExtra("fromDownloadService", true);
                            mediaIntent.putExtra(AudioVideoPlayerLollipop.PLAY_WHEN_READY,app.isActivityVisible());
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !externalFile) {
								mediaIntent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							} else {
								mediaIntent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							}
							if (opusFile) {
								mediaIntent.setDataAndType(mediaIntent.getData(), "audio/*");
							}
							mediaIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							mediaIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
								mediaIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							}

							if (internalIntent) {
								startActivity(mediaIntent);
							}
							else {
								if (isIntentAvailable(this, mediaIntent)) {
									startActivity(mediaIntent);
								}
								else {
									Intent intentShare = new Intent(Intent.ACTION_SEND);
									if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !externalFile) {
										intentShare.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
									} else {
										intentShare.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
									}
									intentShare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
									intentShare.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
									if (isIntentAvailable(this, mediaIntent)) {
										startActivity(intentShare);
									}
								}
							}
						}
						else {
							logDebug("Show notification");
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
								NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
								channel.setShowBadge(true);
								channel.setSound(null, null);
								mNotificationManager.createNotificationChannel(channel);

								NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

								mBuilderCompatO
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
							}
							else {
								mBuilderCompat
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
							}
						}
					}
					else if (MimeTypeList.typeForName(currentFile.getName()).isDocument()) {
						logDebug("Download is document");

						Intent viewIntent = new Intent(Intent.ACTION_VIEW);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
							viewIntent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
						} else {
							viewIntent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
						}
						viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
							viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						}

						if (isIntentAvailable(this, viewIntent))
							startActivity(viewIntent);
						else {
							viewIntent.setAction(Intent.ACTION_GET_CONTENT);

							if (isIntentAvailable(this, viewIntent))
								startActivity(viewIntent);
							else {
								Intent intentShare = new Intent(Intent.ACTION_SEND);
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
									intentShare.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								} else {
									intentShare.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								}
								intentShare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								intentShare.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
								startActivity(intentShare);
							}
						}
					} else if (MimeTypeList.typeForName(currentFile.getName()).isImage()) {
						logDebug("Download is IMAGE");
						if (!fromMV){
							Intent viewIntent = new Intent(Intent.ACTION_VIEW);
							//					viewIntent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
								viewIntent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							} else {
								viewIntent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
							}
							viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

							if (isIntentAvailable(this, viewIntent))
								startActivity(viewIntent);
							else {
								Intent intentShare = new Intent(Intent.ACTION_SEND);
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
									intentShare.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								} else {
									intentShare.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								}
								intentShare.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								intentShare.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
								startActivity(intentShare);
							}
						}
						else {
							logDebug("Show notification");
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
								NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
								channel.setShowBadge(true);
								channel.setSound(null, null);
								mNotificationManager.createNotificationChannel(channel);

								NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

								mBuilderCompatO
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

								mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
							}
							else {
								mBuilderCompat
										.setSmallIcon(R.drawable.ic_stat_notify)
										.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
										.setAutoCancel(true).setTicker(notificationTitle)
										.setContentTitle(notificationTitle).setContentText(size)
										.setOngoing(false);

								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
									mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
								}

								mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
							}
						}

					}
					else if (MimeTypeList.typeForName(currentFile.getName()).isURL()) {
						logDebug("Is URL file");
						InputStream instream = null;

						try {
							// open the file for reading
							instream = new FileInputStream(currentFile.getAbsolutePath());

							// if file the available for reading
							if (instream != null) {
								// prepare the file for reading
								InputStreamReader inputreader = new InputStreamReader(instream);
								BufferedReader buffreader = new BufferedReader(inputreader);

								String line1 = buffreader.readLine();
								if(line1!=null){
									String line2= buffreader.readLine();

									String url = line2.replace("URL=","");

									logDebug("Is URL - launch browser intent");
									Intent i = new Intent(Intent.ACTION_VIEW);
									i.setData(Uri.parse(url));
									startActivity(i);
								}
								else{
									logWarning("Not expected format: Exception on processing url file");
									intent = new Intent(Intent.ACTION_VIEW);
									if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
										intent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), "text/plain");
									} else {
										intent.setDataAndType(Uri.fromFile(currentFile), "text/plain");
									}
									intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
									if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
										intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
									}

									if (isIntentAvailable(this, intent)){
										startActivity(intent);
									}
									else{
										logWarning("No app to url file as text: show notification");
										if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
											NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
											channel.setShowBadge(true);
											channel.setSound(null, null);
											mNotificationManager.createNotificationChannel(channel);

											NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

											mBuilderCompatO
													.setSmallIcon(R.drawable.ic_stat_notify)
													.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
													.setAutoCancel(true).setTicker(notificationTitle)
													.setContentTitle(notificationTitle).setContentText(size)
													.setOngoing(false);

											mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

											mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
										}
										else {
											mBuilderCompat
													.setSmallIcon(R.drawable.ic_stat_notify)
													.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
													.setAutoCancel(true).setTicker(notificationTitle)
													.setContentTitle(notificationTitle).setContentText(size)
													.setOngoing(false);

											if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
												mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
											}

											mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
										}
									}
								}
							}
						} catch (Exception ex) {

							intent = new Intent(Intent.ACTION_VIEW);
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
								intent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), "text/plain");
							} else {
								intent.setDataAndType(Uri.fromFile(currentFile), "text/plain");
							}
							intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
								intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							}

							if (isIntentAvailable(this, intent)){
								startActivity(intent);
							}
							else{
								logWarning("Exception on processing url file: show notification");
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
									NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
									channel.setShowBadge(true);
									channel.setSound(null, null);
									mNotificationManager.createNotificationChannel(channel);

									NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

									mBuilderCompatO
											.setSmallIcon(R.drawable.ic_stat_notify)
											.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
											.setAutoCancel(true).setTicker(notificationTitle)
											.setContentTitle(notificationTitle).setContentText(size)
											.setOngoing(false);

									mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

									mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
								}
								else {
									mBuilderCompat
											.setSmallIcon(R.drawable.ic_stat_notify)
											.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
											.setAutoCancel(true).setTicker(notificationTitle)
											.setContentTitle(notificationTitle).setContentText(size)
											.setOngoing(false);

									if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
										mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
									}

									mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
								}
							}

						} finally {
							// close the file.
							instream.close();
						}

					}else {
						logDebug("Download is OTHER FILE");
						intent = new Intent(Intent.ACTION_VIEW);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
							intent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
						} else {
							intent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
						}
						intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
							intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						}

						if (isIntentAvailable(this, intent))
							startActivity(intent);
						else {
							logWarning("Not intent available for ACTION_VIEW");
							intent.setAction(Intent.ACTION_GET_CONTENT);

							if (isIntentAvailable(this, intent))
								startActivity(intent);
							else {
								logWarning("Not intent available for ACTION_GET_CONTENT");
								intent.setAction(Intent.ACTION_SEND);
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
									intent.setDataAndType(FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								} else {
									intent.setDataAndType(Uri.fromFile(currentFile), MimeTypeList.typeForName(currentFile.getName()).getType());
								}
								intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
								if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
									intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
								}
								startActivity(intent);
							}
						}

						logDebug("Show notification");
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
							NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
							channel.setShowBadge(true);
							channel.setSound(null, null);
							mNotificationManager.createNotificationChannel(channel);

							NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

							mBuilderCompatO
									.setSmallIcon(R.drawable.ic_stat_notify)
									.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
									.setAutoCancel(true).setTicker(notificationTitle)
									.setContentTitle(notificationTitle).setContentText(size)
									.setOngoing(false);

							mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

							mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
						}
						else {
							mBuilderCompat
									.setSmallIcon(R.drawable.ic_stat_notify)
									.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
									.setAutoCancel(true).setTicker(notificationTitle)
									.setContentTitle(notificationTitle).setContentText(size)
									.setOngoing(false);

							if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
								mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
							}

							mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
						}
					}
				} else {
					openFile = true; //Set the openFile to the default

					intent = new Intent(getApplicationContext(), ManagerActivityLollipop.class);

					logDebug("Show notification");
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
						NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
						channel.setShowBadge(true);
						channel.setSound(null, null);
						mNotificationManager.createNotificationChannel(channel);

						NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

						mBuilderCompatO
								.setSmallIcon(R.drawable.ic_stat_notify)
								.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
								.setAutoCancel(true).setTicker(notificationTitle)
								.setContentTitle(notificationTitle).setContentText(size)
								.setOngoing(false);

						mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

						mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
					}
					else {
						mBuilderCompat
								.setSmallIcon(R.drawable.ic_stat_notify)
								.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
								.setAutoCancel(true).setTicker(notificationTitle)
								.setContentTitle(notificationTitle).setContentText(size)
								.setOngoing(false);

						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
							mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
						}

						mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
					}
				}
			}
			catch (Exception e){
				openFile = true; //Set the openFile to the default
				logError("Exception", e);
				intent = new Intent(getApplicationContext(), ManagerActivityLollipop.class);

				logDebug("Show notification");
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
					NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
					channel.setShowBadge(true);
					channel.setSound(null, null);
					mNotificationManager.createNotificationChannel(channel);

					NotificationCompat.Builder mBuilderCompatO = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

					mBuilderCompatO
							.setSmallIcon(R.drawable.ic_stat_notify)
							.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
							.setAutoCancel(true).setTicker(notificationTitle)
							.setContentTitle(notificationTitle).setContentText(size)
							.setOngoing(false);

					mBuilderCompatO.setColor(ContextCompat.getColor(this, R.color.mega));

					mNotificationManager.notify(notificationIdFinal, mBuilderCompatO.build());
				}
				else {
					mBuilderCompat
							.setSmallIcon(R.drawable.ic_stat_notify)
							.setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, intent, 0))
							.setAutoCancel(true).setTicker(notificationTitle)
							.setContentTitle(notificationTitle).setContentText(size)
							.setOngoing(false);

					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
						mBuilderCompat.setColor(ContextCompat.getColor(this, R.color.mega));
					}

					mNotificationManager.notify(notificationIdFinal, mBuilderCompat.build());
				}
			}
		}
	}


	/*
	 * Update notification download progress
	 */
	@SuppressLint("NewApi")
	private void updateProgressNotification() {

		int pendingTransfers = megaApi.getNumPendingDownloads() + megaApiFolder.getNumPendingDownloads();
        int totalTransfers = megaApi.getTotalDownloads() + megaApiFolder.getTotalDownloads();

        long totalSizePendingTransfer = megaApi.getTotalDownloadBytes() + megaApiFolder.getTotalDownloadBytes();
        long totalSizeTransferred = megaApi.getTotalDownloadedBytes() + megaApiFolder.getTotalDownloadedBytes();

		boolean update;

		if(isOverquota){
			logDebug("Overquota flag! is TRUE");
			if(downloadedBytesToOverquota<=totalSizeTransferred){
				update = false;
			}
			else{
				update = true;
				logDebug("Change overquota flag");
				isOverquota = false;
			}
		}
		else{
			logDebug("NOT overquota flag");
			update = true;
		}

		if(update){
			//refresh UI every 1 seconds to avoid too much workload on main thread
			if(!isOverquota) {
				long now = System.currentTimeMillis();
				if (now - lastUpdated > ONTRANSFERUPDATE_REFRESH_MILLIS) {
					lastUpdated = now;
				} else {
					return;
				}
			}
			int progressPercent = (int) Math.round((double) totalSizeTransferred / totalSizePendingTransfer * 100);
			logDebug("Progress: " + progressPercent + "%");

			String message = "";
			if (totalTransfers == 0){
				message = getString(R.string.download_preparing_files);
			}
			else{
				int inProgress = totalTransfers - pendingTransfers + 1;
				message = getResources().getQuantityString(R.plurals.download_service_notification, totalTransfers, inProgress, totalTransfers);
			}

			Intent intent;
			PendingIntent pendingIntent;

			String info = getProgressSize(DownloadService.this, totalSizeTransferred, totalSizePendingTransfer);

			Notification notification = null;

			String contentText = "";

			if(dbH.getCredentials()==null){
				contentText = getString(R.string.download_touch_to_cancel);
				intent = new Intent(DownloadService.this, LoginActivityLollipop.class);
				intent.setAction(ACTION_CANCEL_DOWNLOAD);
				pendingIntent = PendingIntent.getActivity(DownloadService.this, 0, intent, 0);
			}
			else{
				contentText = getString(R.string.download_touch_to_show);
				intent = new Intent(DownloadService.this, ManagerActivityLollipop.class);
				intent.setAction(ACTION_SHOW_TRANSFERS);
				pendingIntent = PendingIntent.getActivity(DownloadService.this, 0, intent, 0);
			}

			int currentapiVersion = android.os.Build.VERSION.SDK_INT;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
				NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
				channel.setShowBadge(true);
				channel.setSound(null, null);
				mNotificationManager.createNotificationChannel(channel);

				NotificationCompat.Builder mBuilderCompat = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

				mBuilderCompat
						.setSmallIcon(R.drawable.ic_stat_notify)
						.setColor(ContextCompat.getColor(this,R.color.mega))
						.setProgress(100, progressPercent, false)
						.setContentIntent(pendingIntent)
						.setOngoing(true).setContentTitle(message).setSubText(info)
						.setContentText(contentText)
						.setOnlyAlertOnce(true);

				notification = mBuilderCompat.build();
			}
			else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				mBuilder
						.setSmallIcon(R.drawable.ic_stat_notify)
						.setColor(ContextCompat.getColor(this,R.color.mega))
						.setProgress(100, progressPercent, false)
						.setContentIntent(pendingIntent)
						.setOngoing(true).setContentTitle(message).setSubText(info)
						.setContentText(contentText)
						.setOnlyAlertOnce(true);
				notification = mBuilder.build();
			}
			else if (currentapiVersion >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH)
			{
				mBuilder
						.setSmallIcon(R.drawable.ic_stat_notify)
						.setProgress(100, progressPercent, false)
						.setContentIntent(pendingIntent)
						.setOngoing(true).setContentTitle(message).setContentInfo(info)
						.setContentText(contentText)
						.setOnlyAlertOnce(true);

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
					mBuilder.setColor(ContextCompat.getColor(this,R.color.mega));
				}

				notification = mBuilder.getNotification();
			}
			else
			{
				notification = new Notification(R.drawable.ic_stat_notify, null, 1);
				notification.flags |= Notification.FLAG_ONGOING_EVENT;
				notification.contentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.download_progress);
				notification.contentIntent = pendingIntent;
				notification.contentView.setImageViewResource(R.id.status_icon, R.drawable.ic_stat_notify);
				notification.contentView.setTextViewText(R.id.status_text, message);
				notification.contentView.setTextViewText(R.id.progress_text, info);
				notification.contentView.setProgressBar(R.id.status_progress, 100, progressPercent, false);
			}

			if (!isForeground) {
				logDebug("Starting foreground!");
				try {
					startForeground(notificationId, notification);
					isForeground = true;
				}
				catch (Exception e){
					isForeground = false;
				}
			} else {
				mNotificationManager.notify(notificationId, notification);
			}
		}
	}

	private void showTransferOverquotaNotification(){
		logDebug("showTransferOverquotaNotification");

		long totalSizePendingTransfer = megaApi.getTotalDownloadBytes() + megaApiFolder.getTotalDownloadBytes();
		long totalSizeTransferred = megaApi.getTotalDownloadedBytes() + megaApiFolder.getTotalDownloadedBytes();

		int progressPercent = (int) Math.round((double) totalSizeTransferred / totalSizePendingTransfer * 100);
		logDebug("Progress: " + progressPercent + "%");

		Intent intent;
		PendingIntent pendingIntent;

		String info = getProgressSize(DownloadService.this, totalSizeTransferred, totalSizePendingTransfer);

		Notification notification = null;

		String contentText = getString(R.string.download_show_info);
		String message = getString(R.string.title_depleted_transfer_overquota);

		if(megaApi.isLoggedIn()==0 || dbH.getCredentials()==null){
			dbH.clearEphemeral();
			intent = new Intent(DownloadService.this, LoginActivityLollipop.class);
			intent.setAction(ACTION_OVERQUOTA_TRANSFER);
			pendingIntent = PendingIntent.getActivity(DownloadService.this, 0, intent, 0);
		}
		else{
			intent = new Intent(DownloadService.this, ManagerActivityLollipop.class);
			intent.setAction(ACTION_OVERQUOTA_TRANSFER);
			pendingIntent = PendingIntent.getActivity(DownloadService.this, 0, intent, 0);
		}

		int currentapiVersion = android.os.Build.VERSION.SDK_INT;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(notificationChannelId, notificationChannelName, NotificationManager.IMPORTANCE_DEFAULT);
			channel.setShowBadge(true);
			channel.setSound(null, null);
			mNotificationManager.createNotificationChannel(channel);

			NotificationCompat.Builder mBuilderCompat = new NotificationCompat.Builder(getApplicationContext(), notificationChannelId);

			mBuilderCompat
					.setSmallIcon(R.drawable.ic_stat_notify)
					.setColor(ContextCompat.getColor(this,R.color.mega))
					.setProgress(100, progressPercent, false)
					.setContentIntent(pendingIntent)
					.setOngoing(true).setContentTitle(message).setSubText(info)
					.setContentText(contentText)
					.setOnlyAlertOnce(true);

			notification = mBuilderCompat.build();
		}
		else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			mBuilder
					.setSmallIcon(R.drawable.ic_stat_notify)
					.setColor(ContextCompat.getColor(this,R.color.mega))
					.setProgress(100, progressPercent, false)
					.setContentIntent(pendingIntent)
					.setOngoing(true).setContentTitle(message).setSubText(info)
					.setContentText(contentText)
					.setOnlyAlertOnce(true);

			notification = mBuilder.build();
		}
		else if (currentapiVersion >= android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH)
		{
			mBuilder
					.setSmallIcon(R.drawable.ic_stat_notify)
					.setProgress(100, progressPercent, false)
					.setContentIntent(pendingIntent)
					.setOngoing(true).setContentTitle(message).setContentInfo(info)
					.setContentText(contentText)
					.setOnlyAlertOnce(true);

			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
				mBuilder.setColor(ContextCompat.getColor(this,R.color.mega));
			}

			notification = mBuilder.getNotification();
		}
		else
		{
			notification = new Notification(R.drawable.ic_stat_notify, null, 1);
			notification.flags |= Notification.FLAG_ONGOING_EVENT;
			notification.contentView = new RemoteViews(getApplicationContext().getPackageName(), R.layout.download_progress);
			notification.contentIntent = pendingIntent;
			notification.contentView.setImageViewResource(R.id.status_icon, R.drawable.ic_stat_notify);
			notification.contentView.setTextViewText(R.id.status_text, message);
			notification.contentView.setTextViewText(R.id.progress_text, info);
			notification.contentView.setProgressBar(R.id.status_progress, 100, progressPercent, false);
		}

		if (!isForeground) {
			logDebug("Starting foreground");
			try {
				startForeground(notificationId, notification);
				isForeground = true;
			}
			catch (Exception e){
				logError("startForeground exception", e);
				isForeground = false;
			}
		} else {
			mNotificationManager.notify(notificationId, notification);
		}
	}

	private void cancel() {
		logDebug("cancel");
		canceled = true;
		isForeground = false;
		stopForeground(true);
		mNotificationManager.cancel(notificationId);
		stopSelf();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void
	onTransferStart(MegaApiJava api, MegaTransfer transfer) {
		logDebug("Download start: " + transfer.getNodeHandle() + ", totalDownloads: " + megaApi.getTotalDownloads() + ",totalDownloads(folder): " + megaApiFolder.getTotalDownloads());

		if (isVoiceClipType(transfer.getAppData())) return;
		if (transfer.getType() == MegaTransfer.TYPE_DOWNLOAD) {
			transfersCount++;
			updateProgressNotification();
		}
	}

	@Override
	public void onTransferFinish(MegaApiJava api, MegaTransfer transfer, MegaError error) {
		logDebug("Node handle: " + transfer.getNodeHandle() + ", Type = " + transfer.getType());

		if(transfer.getType()==MegaTransfer.TYPE_DOWNLOAD){

			boolean isVoiceClip = isVoiceClipType(transfer.getAppData());

			if(!isVoiceClip) transfersCount--;

			if(!transfer.isFolderTransfer()){
				if(transfer.getState()==MegaTransfer.STATE_COMPLETED){
					String size = getSizeString(transfer.getTotalBytes());
					AndroidCompletedTransfer completedTransfer = new AndroidCompletedTransfer(transfer.getFileName(), transfer.getType(), transfer.getState(), size, transfer.getNodeHandle()+"");
					dbH.setCompletedTransfer(completedTransfer);
				}

				if (!isVoiceClip) {
					updateProgressNotification();
				}
			}

			if (canceled) {
				if((lock != null) && (lock.isHeld()))
					try{ lock.release(); } catch(Exception ex) {}
				if((wl != null) && (wl.isHeld()))
					try{ wl.release(); } catch(Exception ex) {}

				logDebug("Download canceled: " + transfer.getNodeHandle());

				if (isVoiceClip) {
					resultTransfersVoiceClip(transfer.getNodeHandle(), ERROR_VOICE_CLIP_TRANSFER);
					File localFile = buildVoiceClipFile(this, transfer.getFileName());
					if (isFileAvailable(localFile)) {
						logDebug("Delete own voiceclip : exists");
						localFile.delete();
					}
				} else {
					File file = new File(transfer.getPath());
					file.delete();
				}
				DownloadService.this.cancel();

			}
			else{
				if (error.getErrorCode() == MegaError.API_OK) {
					logDebug("Download OK - Node handle: " + transfer.getNodeHandle());

					if(isVoiceClip)
						resultTransfersVoiceClip(transfer.getNodeHandle(), SUCCESSFUL_VOICE_CLIP_TRANSFER);

					logDebug("DOWNLOADFILE: " + transfer.getPath());

					//To update thumbnails for videos
					if(isVideoFile(transfer.getPath())){
						logDebug("Is video!!!");
						MegaNode videoNode = megaApi.getNodeByHandle(transfer.getNodeHandle());
						if (videoNode != null){
							if(!videoNode.hasThumbnail()){
								logDebug("The video has not thumb");
								createThumbnailVideo(this, transfer.getPath(), megaApi, transfer.getNodeHandle());
							}
						}
						else{
							logWarning("videoNode is NULL");
						}
					}
					else{
						logDebug("NOT video!");
					}

					File resultFile = new File(transfer.getPath());
					File treeParent = resultFile.getParentFile();
					while(treeParent != null)
					{
						treeParent.setReadable(true, false);
						treeParent.setExecutable(true, false);
						treeParent = treeParent.getParentFile();
					}
					resultFile.setReadable(true, false);
					resultFile.setExecutable(true, false);

					String filePath = transfer.getPath();
					File f = new File(filePath);
					try {
						Intent mediaScanIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
						Uri finishedContentUri;
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
							finishedContentUri = FileProvider.getUriForFile(this, "mega.privacy.android.app.providers.fileprovider", f);
						} else {
							finishedContentUri = Uri.fromFile(f);
						}
						mediaScanIntent.setData(finishedContentUri);
						mediaScanIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
						if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
							mediaScanIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
						}
						this.sendBroadcast(mediaScanIntent);
					}
					catch (Exception e){}

					try {
						MediaScannerConnection.scanFile(getApplicationContext(), new String[]{
								f.getAbsolutePath()}, null, new MediaScannerConnection.OnScanCompletedListener() {
							@Override
							public void onScanCompleted(String path, Uri uri) {
								logDebug("File was scanned successfully");
							}
						});
					}
					catch (Exception e){}

					if(storeToAdvacedDevices.containsKey(transfer.getNodeHandle())){
						logDebug("Now copy the file to the SD Card");
						openFile=false;
						Uri tranfersUri = storeToAdvacedDevices.get(transfer.getNodeHandle());
						MegaNode node = megaApi.getNodeByHandle(transfer.getNodeHandle());
						alterDocument(tranfersUri, node.getName());
					}

					if(transfer.getPath().contains(OFFLINE_DIR)){
						logDebug("It is Offline file");
						dbH = DatabaseHandler.getDbHandler(getApplicationContext());
						offlineNode = megaApi.getNodeByHandle(transfer.getNodeHandle());

						if(offlineNode!=null){
							saveOffline(this, megaApi, dbH, offlineNode, transfer.getPath());
						}
						else{
							saveOfflineChatFile(dbH, transfer);
						}

						refreshOfflineFragment();
						refreshSettingsFragment();
					}
				}
				else
				{
					logError("Download ERROR: " + transfer.getNodeHandle());
					if(isVoiceClip){
						resultTransfersVoiceClip(transfer.getNodeHandle(), ERROR_VOICE_CLIP_TRANSFER);
						File localFile = buildVoiceClipFile(this, transfer.getFileName());
						if (isFileAvailable(localFile)) {
							logDebug("Delete own voice clip : exists");
							localFile.delete();
						}
					}else{
						if(!transfer.isFolderTransfer()){
							errorCount++;
						}
						File file = new File(transfer.getPath());
						file.delete();
					}
				}
			}
			if(isVoiceClip) return;

			if ((megaApi.getNumPendingDownloads() == 0) && (transfersCount==0) && (megaApiFolder.getNumPendingDownloads() == 0)){
				onQueueComplete(transfer.getNodeHandle());
			}
		}
	}

	private void resultTransfersVoiceClip(long nodeHandle, int result){
		logDebug("nodeHandle =  " + nodeHandle + ", the result is " + result);
		Intent intent = new Intent(BROADCAST_ACTION_INTENT_VOICE_CLIP_DOWNLOADED);
		intent.putExtra(EXTRA_NODE_HANDLE, nodeHandle);
		intent.putExtra(EXTRA_RESULT_TRANSFER, result);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}

	private void alterDocument(Uri uri, String fileName) {
		logDebug("alterUri");
	    try {

	    	File tempFolder = getCacheFolder(getApplicationContext(), TEMPORAL_FOLDER);
	    	if (!isFileAvailable(tempFolder)) return;

	    	String sourceLocation = tempFolder.getAbsolutePath() + File.separator +fileName;

			logDebug("Gonna copy: " + sourceLocation);

	        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w");
	        FileOutputStream fileOutputStream = new FileOutputStream(pfd.getFileDescriptor());

	    	InputStream in = new FileInputStream(sourceLocation);
//
//	        OutputStream out = new FileOutputStream(targetLocation);
//
	        // Copy the bits from instream to outstream
	        byte[] buf = new byte[1024];
	        int len;
	        while ((len = in.read(buf)) > 0) {
	        	fileOutputStream.write(buf, 0, len);
	        }
	        in.close();
//	        out.close();


//	        fileOutputStream.write(("Overwritten by MyCloud at " + System.currentTimeMillis() + "\n").getBytes());
	        // Let the document provider know you're done by closing the stream.
	        fileOutputStream.close();
	        pfd.close();

	        File deleteTemp = new File(sourceLocation);
	        deleteTemp.delete();
	    } catch (FileNotFoundException e) {
	        e.printStackTrace();
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}

	@Override
	public void onTransferUpdate(MegaApiJava api, MegaTransfer transfer) {
		if(transfer.getType()==MegaTransfer.TYPE_DOWNLOAD){
			if (canceled) {
				logDebug("Transfer cancel: " + transfer.getNodeHandle());

				if((lock != null) && (lock.isHeld()))
					try{ lock.release(); } catch(Exception ex) {}
				if((wl != null) && (wl.isHeld()))
					try{ wl.release(); } catch(Exception ex) {}

				megaApi.cancelTransfer(transfer);
				DownloadService.this.cancel();
				return;
			}
			if(isVoiceClipType(transfer.getAppData())) return;

			if(!transfer.isFolderTransfer()){
				updateProgressNotification();
			}
		}
	}

	@Override
	public void onTransferTemporaryError(MegaApiJava api, MegaTransfer transfer, MegaError e) {
		logWarning("Download Temporary Error - Node Handle: " + transfer.getNodeHandle() +
				"\nError: " + e.getErrorCode() + " " + e.getErrorString());

		if(transfer.getType()==MegaTransfer.TYPE_DOWNLOAD){
			if(e.getErrorCode() == MegaError.API_EOVERQUOTA) {
				if (e.getValue() != 0) {
					logWarning("TRANSFER OVERQUOTA ERROR: " + e.getErrorCode());

					UserCredentials credentials = dbH.getCredentials();
					if(credentials!=null){
						logDebug("Credentials is NOT null");
					}
					downloadedBytesToOverquota = megaApi.getTotalDownloadedBytes() + megaApiFolder.getTotalDownloadedBytes();
					isOverquota = true;
					logDebug("Downloaded bytes to reach overquota: " + downloadedBytesToOverquota);
					showTransferOverquotaNotification();
				}
			}
		}
	}

	@Override
	public void onRequestStart(MegaApiJava api, MegaRequest request) {
		logDebug("onRequestStart: " + request.getRequestString());
	}

	@Override
	public void onRequestFinish(MegaApiJava api, MegaRequest request, MegaError e) {
		logDebug("onRequestFinish");

		if (request.getType() == MegaRequest.TYPE_PAUSE_TRANSFERS){
			logDebug("TYPE_PAUSE_TRANSFERS finished");
			if (e.getErrorCode() == MegaError.API_OK){
				cancel();
			}
		}
		else if (request.getType() == MegaRequest.TYPE_CANCEL_TRANSFERS){
			logDebug("TYPE_CANCEL_TRANSFERS finished");
			if (e.getErrorCode() == MegaError.API_OK){
				cancel();
			}

		}
		else if (request.getType() == MegaRequest.TYPE_LOGIN){
			if (e.getErrorCode() == MegaError.API_OK){
				logDebug("Fast login OK");
				logDebug("Calling fetchNodes from CameraSyncService");
				megaApi.fetchNodes(this);
			}
			else{
				logError("ERROR: " + e.getErrorString());
				isLoggingIn = false;
				MegaApplication.setLoggingIn(isLoggingIn);
//				finish();
			}
		}
		else if (request.getType() == MegaRequest.TYPE_FETCH_NODES){
			if (e.getErrorCode() == MegaError.API_OK){
				chatSettings = dbH.getChatSettings();
				if(chatSettings!=null) {
					boolean chatEnabled = Boolean.parseBoolean(chatSettings.getEnabled());
					if(chatEnabled){
						logDebug("Chat enabled-->connect");
						megaChatApi.connectInBackground(this);
						isLoggingIn = false;
						MegaApplication.setLoggingIn(isLoggingIn);
					}
					else{
						logDebug("Chat NOT enabled - readyToManager");
						isLoggingIn = false;
						MegaApplication.setLoggingIn(isLoggingIn);
					}
				}
				else{
					logWarning("chatSettings NULL - readyToManager");
					isLoggingIn = false;
					MegaApplication.setLoggingIn(isLoggingIn);
				}

				for (int i=0;i<pendingIntents.size();i++){
					onHandleIntent(pendingIntents.get(i));
				}
				pendingIntents.clear();
			}
			else{
				logError("ERROR: " + e.getErrorString());
				isLoggingIn = false;
				MegaApplication.setLoggingIn(isLoggingIn);
//				finish();
			}
		}
		else{
			logDebug("Public node received");
			if (e.getErrorCode() != MegaError.API_OK) {
				logError("Public node error");
				return;
			}
			else {
				MegaNode node = request.getPublicMegaNode();

				if(node!=null){
					if (currentDir.isDirectory()){
						currentFile = new File(currentDir, megaApi.escapeFsIncompatible(node.getName()));
						logDebug("node.getName(): " + node.getName());

					}
					else{
						currentFile = currentDir;
						logDebug("CURREN");
					}

					logDebug("Public node download launched");
					if(!wl.isHeld()) wl.acquire();
					if(!lock.isHeld()) lock.acquire();
					if (currentDir.isDirectory()){
						logDebug("To downloadPublic(dir): " + currentDir.getAbsolutePath() + "/");
						megaApi.startDownload(node, currentDir.getAbsolutePath() + "/", this);
					}
				}
			}
		}
	}


	@Override
	public void onRequestTemporaryError(MegaApiJava api, MegaRequest request,
			MegaError e) {
		logWarning("Node handle: " + request.getNodeHandle());
	}

	@Override
	public void onRequestUpdate(MegaApiJava api, MegaRequest request) {
		logDebug("onRequestUpdate");
	}

	@Override
	public boolean onTransferData(MegaApiJava api, MegaTransfer transfer, byte[] buffer)
	{
		return true;
	}

	@Override
	public void onRequestStart(MegaChatApiJava api, MegaChatRequest request) {

	}

	@Override
	public void onRequestUpdate(MegaChatApiJava api, MegaChatRequest request) {

	}

	@Override
	public void onRequestFinish(MegaChatApiJava api, MegaChatRequest request, MegaChatError e) {
		if (request.getType() == MegaChatRequest.TYPE_CONNECT){

			isLoggingIn = false;
			MegaApplication.setLoggingIn(isLoggingIn);

			if(e.getErrorCode()==MegaChatError.ERROR_OK){
				logDebug("Connected to chat!");
			}
			else{
				logError("ERROR WHEN CONNECTING " + e.getErrorString());
			}
		}
	}

	@Override
	public void onRequestTemporaryError(MegaChatApiJava api, MegaChatRequest request, MegaChatError e) {

	}

	private void refreshOfflineFragment(){
		Intent intent = new Intent(OfflineFragmentLollipop.REFRESH_OFFLINE_FILE_LIST);
		LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
	}

	private void refreshSettingsFragment() {
		Intent intent = new Intent(BROADCAST_ACTION_INTENT_SETTINGS_UPDATED);
		intent.setAction(SettingsFragmentLollipop.ACTION_REFRESH_CLEAR_OFFLINE_SETTING);
		LocalBroadcastManager.getInstance(getApplicationContext()).sendBroadcast(intent);
	}
}
