package edu.kit.tm.ptp.examples.android.receive;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;

import edu.kit.tm.ptp.Identifier;
import edu.kit.tm.ptp.ReceiveListenerAdapter;
import edu.kit.tm.ptp.PTP;
import edu.kit.tm.ptp.utility.Constants;
import edu.kit.tm.ptp.examples.android.receive.R;


import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v4.app.NotificationCompat;
import android.widget.Toast;

/**
 * Service for running PTP. Communicates with MainActivity.java
 * 
 * @see http://stackoverflow.com/questions/4300291/example-communication-between-activity-and-service-using-messaging
 *
 * @author Simeon Andreev
 * @author Martin Florian
 *
 */
public class ClientService extends Service {

	// Where hidden services data will be stored
	public static final String serviceHSDirectory = "TheHiddenService";

	// Strings for formatting app-internal messages
	public static final String MESSAGE = "message";
	public static final String ADDRESS = "address";
	public static final String ID = "id";

	// Message types for app-internal messages
	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_UNREGISTER_CLIENT = 2;
	public static final int MSG_SEND_MESSAGE = 3;
	public static final int MSG_RECEIVE_MESSAGE = 4;
	public static final int MSG_IDENTIFIER = 5;
	public static final int MSG_MESSAGE_STATUS = 6;

	private static boolean running = false;

	/**
	 * Handler for incoming messages from {@link MainActivity}.
	 * 
	 * @see MainActivity
	 */	
	private static class IncomingHandler extends Handler {

		private final WeakReference<ClientService> service;

		public IncomingHandler(WeakReference<ClientService> service) { this.service = service; }

		@Override
		public void handleMessage(final Message msg) {
			if (msg.what == MSG_REGISTER_CLIENT) {
				
				try {
					// Notify client of missed messages.
					while (!service.get().receivedQueue.isEmpty()) {
						edu.kit.tm.ptp.Message element = service.get().receivedQueue.peek();
						Bundle bundle = new Bundle();
						bundle.putString(MESSAGE, element.content);
						bundle.putString(ADDRESS, element.identifier.toString());
						android.os.Message message = android.os.Message.obtain(null, MSG_RECEIVE_MESSAGE);
						message.setData(bundle);
						msg.replyTo.send(message);
						service.get().receivedQueue.remove();
					}
				} catch (RemoteException e) {
					return;
				}
				service.get().client = msg.replyTo;

			} else if (msg.what == MSG_UNREGISTER_CLIENT) {
				service.get().client = null;
			} else {
				super.handleMessage(msg);
			}
		}
	}

	private final Messenger messenger = new Messenger(new IncomingHandler(new WeakReference<ClientService>(this)));
	private ConcurrentLinkedQueue<edu.kit.tm.ptp.Message> receivedQueue = new ConcurrentLinkedQueue<edu.kit.tm.ptp.Message>();
	private PTP ptp = null;
	private Messenger client = null;

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		
		startInNotificationArea();
		
		startTor();	// runs startPTP() on completion
		
		return START_STICKY; // tell Android to restart this services should it be killed
	}
	
	/**
	 * Starts PTP thread. Called from {@link #startTor()}.
	 * 
	 * @param workingDirectory
	 * @param controlPort
	 * @param socksPort
	 * @param localPort
	 */
	private void startPTP(String workingDirectory, int controlPort, int socksPort, int localPort) {

		try {
			ptp = new PTP(workingDirectory, controlPort, socksPort, localPort, serviceHSDirectory);
			ptp.setListener(new ReceiveListenerAdapter() {

				// Pass PTP messages to MainActivity
				@Override
				public void receivedMessage(edu.kit.tm.ptp.Message message) {
					if (client == null) {
						receivedQueue.add(message);
						return;
					}
					try {
						Bundle b = new Bundle();
						b.putString(MESSAGE, message.content);
						b.putString(ADDRESS, message.identifier.getTorAddress());
						android.os.Message msg = android.os.Message.obtain(null, MSG_RECEIVE_MESSAGE);
						msg.setData(b);
						client.send(msg);
					} catch (RemoteException e) {
						client = null;
					}
				}
			});
			new Thread(new Runnable() {

				@Override
				public void run() {
					try {
						// Set up Hidden Service
						ptp.reuseHiddenService();
					} catch (IOException e) {
						throw new RuntimeException(e.getMessage());
					}
					try {
						// Tell client our own PTP Identifier
						Identifier identifier = ptp.getIdentifier();
						Bundle bundle = new Bundle();
						bundle.putString(ADDRESS, identifier.toString());
						android.os.Message message = android.os.Message.obtain(null, MSG_IDENTIFIER);
						message.setData(bundle);
						client.send(message);
					} catch (RemoteException e) {
						client = null;
					}
				}
			}).start();
		} catch (IOException e) {
			throw new RuntimeException(e.getMessage());
		}
	}

	/**
	 * Makes service visible in notification area. In this way, it won't be killed so quickly by the OS.
	 */
	private void startInNotificationArea() {
		Intent notificationIntent = new Intent(this, MainActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
		Notification notification = new NotificationCompat.Builder(this)
			.setContentTitle("PTP Client")
			.setSmallIcon(R.drawable.ic_launcher)
			.setContentIntent(pendingIntent)
			.build();
		startForeground(42, notification);
	}
	
	@Override
	public IBinder onBind(Intent intent) { return messenger.getBinder(); }

	@Override
	public void onDestroy() {
		
		running = false;
		
		stopTor();
		if (ptp != null) ptp.exit();
	}

	public static boolean isRunning() { return running; }
	
	/**
	 * Starts Tor using {@link TorManager}. Runs {@link #startPTP(String, int, int, int)} on success.
	 * 
	 * In case a different Tor manager should be used (e.g., Orbot) - rewrite this.
	 */
	private void startTor() {
		
		TorManager.start(this, new TorManager.Listener() {
			
			@Override
			public void success(final String message) {
				
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
				
				// Get Tor config options
				final String directory = TorManager.getWorkingDirectory(getFilesDir().getPath());
				final int start = message.indexOf(TorManager.delimiter) + 1;
				final int middle = message.indexOf(TorManager.delimiter, start) + 1;
				final int controlPort = Integer.valueOf(message.substring(start, middle - 1));
				final int socksPort = Integer.valueOf(message.substring(middle));
				
				// start PeerTorPeer
				startPTP(directory, controlPort, socksPort, Constants.anyport);	
			}

			@Override
			public void update(String message) {
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}

			@Override
			public void failure(String message) {
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}
		});
	}

	/**
	 * Stop Tor using {@link TorManager}.
	 * 
	 * In case a different Tor manager should be used (e.g., Orbot) - rewrite this.
	 */
	private synchronized void stopTor() {
		TorManager.stop(this, new TorManager.Listener() {

			@Override
			public void success(String message) {
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}

			@Override
			public void update(String message) {
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}

			@Override
			public void failure(String message) {
				// Feedback for user
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}
		});
	}
}