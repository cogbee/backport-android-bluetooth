package backport.android.bluetooth;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import android.bluetooth.IBluetoothDevice;
import android.bluetooth.IBluetoothDeviceCallback;
import android.bluetooth.RfcommSocket;
import android.os.RemoteException;
import android.util.Log;

public class BluetoothSocket implements Closeable {

	static final int DEFAULT_CHANNEL = 30;

	static final int EBADFD = 77;
	static final int EADDRINUSE = 98;

	static final int MAX_RFCOMM_CHANNEL = 30;

	private static final String TAG = "BluetoothSocket";

	private static final Field M_ADDRESS_FIELD;

	static {

		Field fld = null;

		try {
			fld = RfcommSocket.class.getDeclaredField("mAddress");

			if (!fld.isAccessible()) {
				fld.setAccessible(true);
			}

		} catch (SecurityException e) {
		} catch (NoSuchFieldException e) {
		}

		M_ADDRESS_FIELD = fld;
	}

	private/* final */BluetoothDevice mRemoteDevice;

	final RfcommSocket mRfcommSocket;

	private InputStream mInStream;

	private OutputStream mOutStream;

	private final SdpHelper mSdp;

	private int mPort = -1;

	/** prevents all native calls after destroyNative() */
	private boolean mClosed;

	/** protects mClosed */
	private final ReentrantReadWriteLock mLock;

	public BluetoothSocket(BluetoothDevice remDev, UUID uuid) {

		mRemoteDevice = remDev;
		mRfcommSocket = new RfcommSocket();

		try {
			mRfcommSocket.create();
		} catch (IOException e) {
		}

		if (uuid == null) {

			mSdp = null;
		} else {

			if (remDev != null) {

				mSdp = new SdpHelper(remDev, uuid);
			} else {

				mSdp = null;
			}
		}

		mClosed = false;
		mLock = new ReentrantReadWriteLock();
	}

	public void close() throws IOException {

		// abort blocking operations on the socket
		mLock.readLock().lock();
		try {
			if (mClosed)
				return;
			if (mSdp != null) {
				mSdp.cancel();
			}

			try {
				mRfcommSocket.shutdownInput();
			} catch (IOException e) {
			}

			try {
				mRfcommSocket.shutdownOutput();
			} catch (IOException e) {
			}

			try {
				mRfcommSocket.shutdown();
			} catch (IOException e) {
			}

		} finally {
			mLock.readLock().unlock();
		}

		// all native calls are guaranteed to immediately return after
		// abortNative(), so this lock should immediatley acquire
		mLock.writeLock().lock();
		try {
			mClosed = true;
			mRfcommSocket.destroy();
		} finally {
			mLock.writeLock().unlock();
		}
	}

	public void connect() throws IOException {

		mLock.readLock().lock();
		try {
			if (mClosed)
				throw new IOException("socket closed");

			if (mSdp != null) {
				mPort = mSdp.doSdp(); // blocks
			}

			mRfcommSocket.connect(mRemoteDevice.getAddress(), mPort); // blocks
		} finally {
			mLock.readLock().unlock();
		}
	}

	public InputStream getInputStream() throws IOException {

		mInStream = mRfcommSocket.getInputStream();

		return mInStream;
	}

	public OutputStream getOutputStream() throws IOException {

		mOutStream = mRfcommSocket.getOutputStream();

		return mOutStream;
	}

	public BluetoothDevice getRemoteDevice() {

		return mRemoteDevice;
	}

	int bindListen() {

		mLock.readLock().lock();

		try {

			if (mClosed) {

				return EBADFD;
			}

			try {

				mRfcommSocket.bind(null);
			} catch (IOException e) {

				return EADDRINUSE;
			}

			try {

				mRfcommSocket.listen(-1);
			} catch (IOException e) {

				return EADDRINUSE;
			}

			return 0;
		} finally {
			mLock.readLock().unlock();
		}
	}

	BluetoothSocket accept(int timeout) throws IOException {
		mLock.readLock().lock();
		try {
			if (mClosed) {
				throw new IOException("socket closed");
			}
		} finally {
			mLock.readLock().unlock();
		}

		BluetoothSocket socket = new BluetoothSocket(null, null);
		RfcommSocket tmp = socket.mRfcommSocket;

		// なぜか-1(infinity)を指定すると、closeできないのでループでinfinityな感じにする.
		int tmpTimeout = (timeout > -1) ? timeout : 500;

		for (;;) {
			mRfcommSocket.accept(tmp, tmpTimeout);

			if (timeout > -1) {
				break;
			}

			mLock.readLock().lock();
			try {
				if (mClosed) {
					return null;
				}

			} finally {
				mLock.readLock().unlock();
			}

			if (tmp.isConnected()) {
				break;
			}
		}

		String addr = obtainAddress(tmp);
		socket.mRemoteDevice = new BluetoothDevice(addr);

		return socket;
	}

	/**
	 * Helper to perform blocking SDP lookup.
	 */
	private static class SdpHelper extends IBluetoothDeviceCallback.Stub {

		private final IBluetoothDevice mService;
		private final UUID mUuid;
		private final BluetoothDevice mDevice;
		private int mChannel;
		private boolean mCanceled;

		public SdpHelper(BluetoothDevice device, UUID uuid) {

			mService = BluetoothDevice.getService();
			mDevice = device;
			mUuid = uuid;
			mCanceled = false;
		}

		/**
		 * Returns the RFCOMM channel for the UUID, or throws IOException on
		 * failure.
		 */
		public synchronized int doSdp() throws IOException {
			if (mCanceled)
				throw new IOException("Service discovery canceled");
			mChannel = -1;

			boolean inProgress = false;
			int uuid16 = UUIDHelper.toUUID16(mUuid);

			try {
				inProgress = mService.getRemoteServiceChannel(mDevice
						.getAddress(), uuid16, this);
				// inProgress = service.fetchRemoteUuids(device.getAddress(),
				// uuid, this);
			} catch (RemoteException e) {
				Log.e(TAG, "", e);
			}

			if (!inProgress)
				throw new IOException("Unable to start Service Discovery");

			try {
				/*
				 * 12 second timeout as a precaution - onRfcommChannelFound
				 * should always occur before the timeout
				 */
				wait(12000); // block

			} catch (InterruptedException e) {
			}

			if (mCanceled) {

				throw new IOException("Service discovery canceled");
			}
			if (mChannel < 1) {

				// mChannel = uuid16 & DEFAULT_CHANNEL;
				mChannel = 1;
				// mChannel = DEFAULT_CHANNEL;
			}
			// if (mChannel < 1)
			// throw new IOException("Service discovery failed");

			return mChannel;
		}

		/** Object cannot be re-used after calling cancel() */
		public synchronized void cancel() {
			if (!mCanceled) {
				mCanceled = true;
				mChannel = -1;
				notifyAll(); // unblock
			}
		}

		// public synchronized void onRfcommChannelFound(int channel) {
		// if (!canceled) {
		// this.channel = channel;
		// notifyAll(); // unblock
		// }
		// }

		public synchronized void onGetRemoteServiceChannelResult(
				String address, int channel) throws RemoteException {
			if (!mCanceled) {
				this.mChannel = channel;
				notifyAll(); // unblock
			}
		}
	}

	private String obtainAddress(RfcommSocket rfcommSocket) {
		String addr = null;

		try {
			addr = (String) M_ADDRESS_FIELD.get(rfcommSocket);
		} catch (IllegalArgumentException e) {
		} catch (IllegalAccessException e) {
		}

		return addr;
	}

}
