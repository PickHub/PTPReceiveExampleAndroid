package edu.kit.tm.torp2p.examples.android.receive;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;

import edu.kit.tm.torp2p.examples.android.receive.R;

import edu.kit.tm.torp2p.ReceiveListenerAdapter;
import edu.kit.tm.torp2p.TorP2P;
import edu.kit.tm.torp2p.utility.Constants;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.widget.Toast;


/* source : http://stackoverflow.com/questions/4300291/example-communication-between-activity-and-service-using-messaging */
public class ClientService extends Service {

	public static final String serviceHSDirectory = "TheHiddenService";

	public static final String MESSAGE = "message";
	public static final String ADDRESS = "address";
	public static final String ID = "id";

	public static final int MSG_REGISTER_CLIENT = 1;
	public static final int MSG_UNREGISTER_CLIENT = 2;
	public static final int MSG_SEND_MESSAGE = 3;
	public static final int MSG_RECEIVE_MESSAGE = 4;
	public static final int MSG_IDENTIFIER = 5;
	public static final int MSG_MESSAGE_STATUS = 6;

	private static final String RECEIVEDFILE = "received";

	private static class Tuple {

		public final String address;
		public final String message;
		public long id;


		public Tuple(String address, String message, long id) {
			this.address = address;
			this.message = message;
			this.id = id;
		}

	}

	private static boolean running = false;
	public static boolean torrunning = false;

	private final BroadcastReceiver receiver = new BroadcastReceiver() {

		@Override
		public void onReceive(final Context context, Intent intent) {
			final String action = intent.getAction();

			if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)){
				NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
				final boolean connected = info.isConnected();

				if (!connected) {
					if (!torrunning) return;

					TorManager.stop(context);
				} else if (connected) {
					if (torrunning) return;

					//rebootTor(context);
				}
			}
		}

	};

	private static class IncomingHandler extends Handler {

		private final WeakReference<ClientService> service;

		public IncomingHandler(WeakReference<ClientService> service) { this.service = service; }

		@Override
		public void handleMessage(final Message msg) {
			if (msg.what == MSG_REGISTER_CLIENT) {
				
				try {
					// Notify client of onion address.
					Bundle bundle = new Bundle();
					// FIXME
					//Identifier identifier = service.get().torp2p.getIdentifier();
					//bundle.putString(ADDRESS, identifier.getTorAddress());
					bundle.putString(ADDRESS, "TODO");
					Message message = Message.obtain(null, MSG_IDENTIFIER);
					message.setData(bundle);
					msg.replyTo.send(message);
				} catch (RemoteException e) {
					return;
				}
				
				try {
					// Notify client of missed messages.
					while (!service.get().receivedQueue.isEmpty()) {
						Tuple element = service.get().receivedQueue.peek();
						Bundle bundle = new Bundle();
						bundle.putString(MESSAGE, element.message);
						bundle.putString(ADDRESS, element.address);
						Message message = Message.obtain(null, MSG_RECEIVE_MESSAGE);
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
	private ConcurrentLinkedQueue<Tuple> receivedQueue = new ConcurrentLinkedQueue<Tuple>();
	private TorP2P torp2p = null;
	private Messenger client = null;

	@Override
	public void onCreate() {
		super.onCreate();
		running = true;

		final IntentFilter filters = new IntentFilter();
		filters.addAction("android.net.wifi.WIFI_STATE_CHANGED");
		filters.addAction("android.net.wifi.STATE_CHANGE");

		registerReceiver(receiver, filters);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		
		startInNotificationArea();
		
		startTor(); // runs startTorP2P() on completion
		
		return START_STICKY;
	}
	
	private void startTorP2P(String workingDirectory, int controlPort, int socksPort, int localPort) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);

		// FIXME: must this be put?
		Editor editor = preferences.edit();

		editor.putString(getResources().getString(R.string.working_directory), workingDirectory);
		editor.putInt(getResources().getString(R.string.control_port), controlPort);
		editor.putInt(getResources().getString(R.string.socks_port), socksPort);
		editor.putInt(getResources().getString(R.string.local_port), localPort);
		editor.putLong(getResources().getString(R.string.message_id), 0);
		editor.commit();

		client = null;

		receivedQueue = new ConcurrentLinkedQueue<>(readElements(workingDirectory, RECEIVEDFILE));

		try {
			torp2p = new TorP2P(workingDirectory, controlPort, socksPort, localPort, serviceHSDirectory);
			torp2p.setListener(new ReceiveListenerAdapter() {

				@Override
				public void receivedMessage(edu.kit.tm.torp2p.Message message) {
					if (client == null) {
						receivedQueue.add(new Tuple(message.identifier.getTorAddress(), message.content, 0));
						return;
					}

					try {
						Bundle b = new Bundle();
						b.putString(MESSAGE, message.content);
						b.putString(ADDRESS, message.identifier.getTorAddress());
						Message msg = Message.obtain(null, MSG_RECEIVE_MESSAGE);
						msg.setData(b);
						client.send(msg);
					} catch (RemoteException e) {
						client = null;
					}
				}

			});

			if (torrunning) {
				new Thread(new Runnable() {

					@Override
					public void run() {
						try {
							torp2p.reuseHiddenService(false);
						} catch (IOException e) {
							throw new RuntimeException(e.getMessage());
						}
					}

				}).start();
			} else {
				ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
				NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

				if (activeNetworkInfo != null && activeNetworkInfo.isConnected())
					rebootTor(this);
			}
			
			editor = preferences.edit();
			editor.putInt(getResources().getString(R.string.local_port), torp2p.getLocalPort());
			editor.commit();

		} catch (IOException e) {
			throw new RuntimeException(e.getMessage());
		}
	}

	
	private void startInNotificationArea() {
		Intent notificationIntent = new Intent(this, MainActivity.class);
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
		Notification notification = new NotificationCompat.Builder(this)
			.setContentTitle("TorP2P Client")
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

		unregisterReceiver(receiver);
		if (torp2p != null) torp2p.exit();

		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
		String workingDirectory = preferences.getString(getResources().getString(R.string.working_directory), null);

		writeQueue(receivedQueue, new HashSet<Long>(), workingDirectory, RECEIVEDFILE);
	}

	public static boolean isRunning() { return running; }

	private void writeQueue(ConcurrentLinkedQueue<Tuple> queue, HashSet<Long> banned, String workingDirectory, String fileName) {
		BufferedWriter buffer = null;

		try {
			File file = new File(workingDirectory + File.separator + fileName);
			FileWriter writer = new FileWriter(file);
			buffer = new BufferedWriter(writer);

			while (!queue.isEmpty()) {
				if (!banned.contains(queue.peek().id)) {
					buffer.write(queue.peek().address + "\n");
					buffer.write(queue.peek().message + "\n");
					buffer.write(String.valueOf(queue.peek().id) + "\n");
				}
				queue.remove();
			}

			buffer.close();
		} catch (IOException e) {

		} finally {
			if (buffer != null) try { buffer.close(); } catch (IOException e) { }
		}
	}

	private Vector<Tuple> readElements(String workingDirectory, String fileName) {
		BufferedReader buffer = null;
		Vector<Tuple> elements = new Vector<Tuple>();

		try {
			File file = new File(workingDirectory + File.separator + fileName);
			FileReader writer = new FileReader(file);
			buffer = new BufferedReader(writer);

			while (buffer.ready()) {
				String address = buffer.readLine();
				String message = buffer.readLine();
				String id = buffer.readLine();
				elements.add(new Tuple(address, message, Long.valueOf(id)));
			}

			buffer.close();
			file.delete();
		} catch (IOException e) {

		} finally {
			if (buffer != null) try { buffer.close(); } catch (IOException e) { }
		}

		return elements;
	}
	
	private void startTor() {
		
		ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
		NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();

		if (activeNetworkInfo == null || !activeNetworkInfo.isConnected()) {
			Toast.makeText(this, "No internet connection.", Toast.LENGTH_SHORT).show();
			return;
		}		
		
		TorManager.start(this, new TorManager.Listener() {
			
			@Override
			public void success(final String message) {
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
				
				ClientService.torrunning = true;
				
				final String directory = TorManager.getWorkingDirectory(getFilesDir().getPath());
				final int start = message.indexOf(TorManager.delimiter) + 1;
				final int middle = message.indexOf(TorManager.delimiter, start) + 1;
				final int controlPort = Integer.valueOf(message.substring(start, middle - 1));
				final int socksPort = Integer.valueOf(message.substring(middle));
				
				startTorP2P(directory, controlPort, socksPort, Constants.anyport);
			}

			@Override
			public void update(String message) { Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show(); }

			@Override
			public void failure(String message) {
				ClientService.torrunning = false;
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
			}
		});
	}

	private synchronized void stopTor() {
		TorManager.stop(this, new TorManager.Listener() {

			@Override
			public void success(String message) {
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
				ClientService.torrunning = false;
			}

			@Override
			public void update(String message) { Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show(); }

			@Override
			public void failure(String message) { Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show(); }

		});
	}
	
	private synchronized void rebootTor(final Context context) {
		if (torrunning) return;

		TorManager.start(context, new TorManager.Listener() {

			@Override
			public void success(final String message) {
				Toast.makeText(ClientService.this, message, Toast.LENGTH_SHORT).show();
				
				ClientService.torrunning = true;
				
				final int start = message.indexOf(TorManager.delimiter) + 1;
				final int middle = message.indexOf(TorManager.delimiter, start) + 1;
				final int controlPort = Integer.valueOf(message.substring(start, middle - 1));
				final int socksPort = Integer.valueOf(message.substring(middle));

				SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(ClientService.this);

				String workingDirectory = preferences.getString(getResources().getString(R.string.working_directory), null);

				Editor editor = preferences.edit();

				editor.putInt(getResources().getString(R.string.control_port), controlPort);
				editor.putInt(getResources().getString(R.string.socks_port), socksPort);
				editor.commit();

				torp2p.getConfiguration().setTorConfiguration(workingDirectory, controlPort, socksPort);

				new Thread(new Runnable() {

					@Override
					public void run() {
						try {
							torp2p.reuseHiddenService(true);
						} catch (IOException e) {
							throw new RuntimeException(e.getMessage());
						}
					}

				}).start();
			}

			@Override
			public void update(String message) { }

			@Override
			public void failure(String message) { throw new RuntimeException(message); }

		});
	}

}