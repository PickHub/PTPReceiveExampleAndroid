package edu.kit.tm.torp2p.examples.android.receive;

import java.lang.ref.WeakReference;

import edu.kit.tm.torp2p.examples.android.receive.R;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Standard Android activity. Displays messages received via the ClientService and TorP2P.
 * 
 * @author Simeon Andreev
 * @author Martin Florian
 *
 */
public class MainActivity extends Activity {
	
	/**
	 * Handler for incoming messages from {@link ClientService}.
	 * 
	 * @see ClientService
	 */	
	private static class IncomingHandler extends Handler {

		private final WeakReference<MainActivity> activity;

		public IncomingHandler(WeakReference<MainActivity> activity) { this.activity = activity; }

		@Override
		public void handleMessage(Message msg) {
			if (msg.what == ClientService.MSG_RECEIVE_MESSAGE) {
				Bundle bundle = msg.getData();
				String message = bundle.getString(ClientService.MESSAGE);
				String address = bundle.getString(ClientService.ADDRESS);
				activity.get().showMessage(message, address);
			} else if (msg.what == ClientService.MSG_IDENTIFIER) {
				Bundle bundle = msg.getData();
				activity.get().showOwnIdentifier(bundle.getString(ClientService.ADDRESS));
			} else {
				super.handleMessage(msg);
			}
		}
	}

	private ServiceConnection mConnection = new ServiceConnection() {

		public void onServiceConnected(ComponentName className, IBinder service) {
			clientService = new Messenger(service);

			Toast.makeText(MainActivity.this, "Connected to service.", Toast.LENGTH_SHORT).show();
			try {
				Message msg = Message.obtain(null, ClientService.MSG_REGISTER_CLIENT);
				msg.replyTo = messenger;
				clientService.send(msg);
			} catch (RemoteException e) {
				// In this case the service has crashed before we could even do anything with it
			}
		}

		public void onServiceDisconnected(ComponentName className) {
			clientService = null;
			Toast.makeText(MainActivity.this, "Disconnected from service.", Toast.LENGTH_SHORT).show();
		}
	};

	private Messenger messenger = new Messenger(new IncomingHandler(new WeakReference<MainActivity>(this)));
	private Messenger clientService;
	private boolean bound = false;
	
	private TextView chatBox = null;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		
		chatBox = (TextView)findViewById(R.id.chatbox);

		if (ClientService.isRunning()) bindService();
		else startClient();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		try {
			stopClient();
		} catch (Exception e) {

		}
	}

	private void startClient() {
		if (ClientService.isRunning()) return;
		
		Intent intent = new Intent(MainActivity.this, ClientService.class);
		startService(intent);
		bindService();
	}

	private void stopClient() {

		unbindService();
		stopService(new Intent(MainActivity.this, ClientService.class));
	}

	private void showMessage(String message, String address) {

		chatBox.append(address + ": ");
		chatBox.append(message);
		chatBox.append("\n");
	}
	
	public void showOwnIdentifier(String address) {
		chatBox.append("Own Address: " + address);
		chatBox.append("\n");
	}

	private void bindService() {
		bindService(new Intent(this, ClientService.class), mConnection, Context.BIND_AUTO_CREATE);
		bound = true;
		Toast.makeText(this, "Connecting to service.", Toast.LENGTH_SHORT).show();
	}
	
	private void unbindService() {
		if (!bound) return;

		// If we have received the service, and hence registered with it, then now is the time to unregister.
		if (clientService != null) {
			try {
				Message msg = Message.obtain(null, ClientService.MSG_UNREGISTER_CLIENT);
				msg.replyTo = messenger;
				clientService.send(msg);
			}
			catch (RemoteException e) {

			}
		}
		// Detach our existing connection.
		unbindService(mConnection);
		bound = false;
		Toast.makeText(this, "Disconnecting from service.", Toast.LENGTH_SHORT).show();
	}
}
