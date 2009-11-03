/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (C) 2009, backport-android-bluetooth - http://code.google.com/p/backport-android-bluetooth/
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package backport.android.bluetooth;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import android.bluetooth.BluetoothError;
import android.bluetooth.RfcommSocket;
import android.util.Log;

/**
 * Represents a connected or connecting Bluetooth Socket.
 * 
 * Currently only supports RFCOMM sockets.
 * 
 * RFCOMM is a connection orientated, streaming transport over Bluetooth. It is
 * also known as the Serial Port Profile (SPP).
 * 
 * TODO: Consider implementing SCO and L2CAP sockets. TODO: Clean up javadoc
 * grammer and formatting. TODO: Remove @hide
 * 
 * @hide
 */
public final class BluetoothSocket implements Closeable {

	static final String TAG = BluetoothSocket.class.getSimpleName();

	static final String EMPTY = "";

	/**
	 * Construct an insecure RFCOMM socket ready to start an outgoing
	 * connection. Call #connect on the returned #BluetoothSocket to begin the
	 * connection. The remote device will not be authenticated and communication
	 * on this socket will not be encrypted.
	 * 
	 * @param address
	 *            remote Bluetooth address that this socket can connect to
	 * @param port
	 *            remote port
	 * @return An RFCOMM BluetoothSocket
	 * @throws IOException
	 *             On error, for example Bluetooth not available, or
	 *             insufficient permissions.
	 */
	public static BluetoothSocket createInsecureRfcommSocket(String address,
			int port) throws IOException {
		return new BluetoothSocket(-1, false, false, address, port);
	}

	/**
	 * Construct a secure RFCOMM socket ready to start an outgoing connection.
	 * Call #connect on the returned #BluetoothSocket to begin the connection.
	 * The remote device will be authenticated and communication on this socket
	 * will be encrypted.
	 * 
	 * @param address
	 *            remote Bluetooth address that this socket can connect to
	 * @param port
	 *            remote port
	 * @return an RFCOMM BluetoothSocket
	 * @throws IOException
	 *             on error, for example Bluetooth not available, or
	 *             insufficient permissions.
	 */
	public static BluetoothSocket createRfcommSocket(String address, int port)
			throws IOException {
		return new BluetoothSocket(-1, true, true, address, port);
	}

	private final int mPort;
	private final String mAddress; /* remote address */
	private final boolean mAuth;
	private final boolean mEncrypt;

	// private final BluetoothInputStream mInputStream;
	private InputStream mInputStream;

	// private final BluetoothOutputStream mOutputStream;
	private OutputStream mOutputStream;

	// private int mSocketData; /* used by native code only */

	private RfcommSocket mRfcommSocket;

	/**
	 * Construct a Bluetooth.
	 * 
	 * @param fd
	 *            fd to use for connected socket, or -1 for a new socket
	 * @param auth
	 *            require the remote device to be authenticated
	 * @param encrypt
	 *            require the connection to be encrypted
	 * @param address
	 *            remote Bluetooth address that this socket can connect to
	 * @param port
	 *            remote port
	 * @throws IOException
	 *             On error, for example Bluetooth not available, or
	 *             insufficient priveleges
	 */
	/* package */BluetoothSocket(int fd, boolean auth, boolean encrypt,
			String address, int port) throws IOException {
		this(null, fd, auth, encrypt, address, port);
	}

	/* package */BluetoothSocket(RfcommSocket socket, int fd, boolean auth,
			boolean encrypt, String address, int port) throws IOException {
		mAuth = auth;
		mEncrypt = encrypt;
		mAddress = address;
		mPort = port;

		if (socket != null) {

			mRfcommSocket = socket;
		} else {

			mRfcommSocket = new RfcommSocket();
		}

		if (fd == -1) {
			initSocketNative();
		} else {
			initSocketFromFdNative(fd);
		}
		// mInputStream = new BluetoothInputStream(this);
		// mOutputStream = new BluetoothOutputStream(this);
	}

	// /* package */native BluetoothSocket acceptNative(int timeout) throws
	// IOException;
	BluetoothSocket acceptNative(int timeout) throws IOException {

		RfcommSocket serverSocket = new RfcommSocket();
		serverSocket.create();
		mRfcommSocket.accept(serverSocket, timeout);

		return new BluetoothSocket(serverSocket, -1, mAuth, mEncrypt, mAddress,
				mPort);
	}

	// /* package */native int availableNative();
	int availableNative() {

		try {

			return mRfcommSocket.getInputStream().available();
		} catch (IOException e) {

			Log.e(TAG, EMPTY, e);

			return -1;
		}
	}

	// /* package */native void bindListenNative(int port) throws IOException;
	/* package */void bindListenNative(int port) throws IOException {

		mRfcommSocket.bind(null, port);
		mRfcommSocket.listen(-1);
	}

	/**
	 * Closes this socket. This will cause other blocking calls on this socket
	 * to immediately throw an IOException.
	 */
	public void close() throws IOException {

		if (mInputStream != null) {

			try {

				mInputStream.close();
			} catch (IOException e) {

				Log.e(TAG, EMPTY, e);
			}
		}

		if (mOutputStream != null) {

			try {

				mOutputStream.close();
			} catch (IOException e) {

				Log.e(TAG, EMPTY, e);
			}
		}

		closeNative();
	}

	// /* package */native void closeNative();
	void closeNative() {

		try {
			mRfcommSocket.shutdown();
		} catch (IOException e) {

			Log.e(TAG, EMPTY, e);
		}
	}

	/**
	 * Attempt to connect to a remote device. This method will block until a
	 * connection is made or the connection fails. If this method returns
	 * without an exception then this socket is now connected. #close can be
	 * used to abort this call from another thread.
	 * 
	 * @throws IOException
	 *             On error, for example connection failure
	 */
	public void connect() throws IOException {
		connectNative(mAddress, mPort, -1);
	}

	// private native void connectNative(String address, int port, int timeout);
	private void connectNative(String address, int port, int timeout) {

		try {

			for (int to = (timeout > 0 ? timeout : 100); to > 0; to -= 100) {

				boolean connect = mRfcommSocket.connect(address, port);

				if (connect) {

					return;
				}

				try {

					Thread.sleep(100);
				} catch (InterruptedException e) {

					// nop.
				}
			}
		} catch (IOException e) {

			Log.e(TAG, EMPTY, e);
		}
	}

	// private native void destroyNative();
	void destroyNative() {

		mRfcommSocket.destroy();
	}

	@Override
	protected void finalize() throws Throwable {
		try {
			close();
		} finally {
			super.finalize();
		}
	}

	/**
	 * Return the address we are connecting, or connected, to.
	 * 
	 * @return Bluetooth address, or null if this socket has not yet attempted
	 *         or established a connection.
	 */
	public String getAddress() {
		return mAddress;
	}

	/**
	 * Get the input stream associated with this socket. The input stream will
	 * be returned even if the socket is not yet connected, but operations on
	 * that stream will throw IOException until the associated socket is
	 * connected.
	 * 
	 * @return InputStream
	 */
	public InputStream getInputStream() throws IOException {

		if (mInputStream != null) {

			return mInputStream;
		}

		// return mInputStream;
		mInputStream = new BluetoothInputStream(mRfcommSocket);
		return mInputStream;
	}

	/**
	 * Get the output stream associated with this socket. The output stream will
	 * be returned even if the socket is not yet connected, but operations on
	 * that stream will throw IOException until the associated socket is
	 * connected.
	 * 
	 * @return OutputStream
	 */
	public OutputStream getOutputStream() throws IOException {
		// return mOutputStream;
		if (mOutputStream != null) {

			return mOutputStream;
		}

		mOutputStream = new BluetoothOutputStream(mRfcommSocket);
		return mOutputStream;
	}

	// private native void initSocketFromFdNative(int fd)
	private void initSocketFromFdNative(int fd) {

		throw new UnsupportedOperationException();
	}

	// private native void initSocketNative();
	private void initSocketNative() {

		try {

			mRfcommSocket.create();
		} catch (IOException e) {

			Log.e(TAG, EMPTY, e);
		}
	}

	// /* package */native int readNative();

	// /* package */native void writeNative(int data);
}
