package ovh.karewan.knble.benchhost;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.zip.CRC32;

/**
 * Bluetooth classic bench on a phone: an RFCOMM server (Serial Port Profile UUID, insecure: no pairing needed) with the
 * protocol of the ESP32 classic bench (testbench/README.md, "Bluetooth classic bench"): PING, INFO, STREAM, SINK, RATE,
 * STOP, DISC, STATS. One connection at a time; a reader thread, a writer thread, the jobs on a thread of their own.
 * The sample finds it with --es classic <MAC of this phone>.
 */
@SuppressLint("MissingPermission")
final class ClassicServer {
	static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	private static final int VERSION = 1;
	private static final int CHUNK = 990;

	private final BenchServer.Log mLog;
	@Nullable
	private final BluetoothAdapter mAdapter;
	private volatile boolean mRunning;
	@Nullable
	private volatile BluetoothServerSocket mServer;

	ClassicServer(@NonNull Context context, @NonNull BenchServer.Log log) {
		mLog = log;
		BluetoothManager manager = context.getSystemService(BluetoothManager.class);
		mAdapter = manager == null ? null : manager.getAdapter();
	}

	void start() {
		if (mAdapter == null || mRunning) return;
		mRunning = true;
		new Thread(this::acceptLoop, "classic-accept").start();
	}

	void stop() {
		mRunning = false;
		BluetoothServerSocket s = mServer;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void acceptLoop() {
		while (mRunning) {
			try {
				BluetoothServerSocket server = mAdapter.listenUsingInsecureRfcommWithServiceRecord("KnBleSpp", SPP);
				mServer = server;
				mLog.log("Classic SPP server listening (" + mAdapter.getAddress() + ")");
				while (mRunning) {
					BluetoothSocket socket = server.accept();
					mLog.log("Classic connected: " + socket.getRemoteDevice().getAddress());
					new Session(socket).run();
				}
			} catch (IOException | RuntimeException e) {
				if (mRunning) mLog.log("Classic server: " + e.getMessage());
				SystemClock.sleep(1000);
			}
		}
	}

	private static byte[] ascii(@NonNull String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * One connection: runs on the accept thread until the socket closes
	 */
	private final class Session {
		private final BluetoothSocket mSocket;
		private final LinkedBlockingQueue<byte[]> mOut = new LinkedBlockingQueue<>(256);
		private volatile boolean mOpen = true, mStopRate;
		@Nullable
		private volatile Thread mJob;
		private long mRx, mTx;
		private long mSinkLeft, mSinkTotal, mSinkFirst;
		private final CRC32 mSinkCrc = new CRC32();
		private final StringBuilder mLine = new StringBuilder();

		Session(@NonNull BluetoothSocket socket) {
			mSocket = socket;
		}

		void run() {
			Thread writer = new Thread(this::writeLoop, "classic-writer");
			writer.start();
			byte[] buffer = new byte[16384];
			try {
				InputStream in = mSocket.getInputStream();
				int n;
				while ((n = in.read(buffer)) >= 0) feed(buffer, n);
			} catch (IOException e) {
				// Closed by the phone or by DISC
			}
			mOpen = false;
			mStopRate = true;
			writer.interrupt();
			close();
			mLog.log(String.format(Locale.US, "Classic disconnected rx=%d tx=%d", mRx, mTx));
		}

		private void close() {
			try {
				mSocket.close();
			} catch (IOException ignored) {
			}
		}

		private void writeLoop() {
			try {
				OutputStream out = mSocket.getOutputStream();
				while (mOpen) {
					byte[] b = mOut.take();
					out.write(b);
					mTx += b.length;
				}
			} catch (InterruptedException | IOException ignored) {
				// End of the session
			}
		}

		private void send(@NonNull byte[] b) {
			try {
				while (mOpen && !mOut.offer(b, 100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
					// The RFCOMM flow control holds the writer
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		private void answer(@NonNull String text) {
			send(ascii(text + "\n"));
		}

		private void job(@NonNull Runnable r) {
			if (mJob != null) return;
			Thread t = new Thread(() -> {
				r.run();
				mJob = null;
			}, "classic-job");
			mJob = t;
			t.start();
		}

		private void stream(long total) {
			long start = SystemClock.elapsedRealtime(), sent = 0;
			while (sent < total && mOpen) {
				int n = (int) Math.min(CHUNK, total - sent);
				byte[] b = new byte[n];
				for (int i = 0; i < n; i++) {
					long p = sent + i;
					b[i] = (byte) (p * 31 + (p >> 8));
				}
				send(b);
				sent += n;
			}
			while (mOpen && !mOut.isEmpty()) SystemClock.sleep(1);
			answer("END " + total + " " + (SystemClock.elapsedRealtime() - start) + " 0");
		}

		private void rate(long bytesPerS, int frameSize) {
			int size = Math.max(24, Math.min(600, frameSize));
			long perS = Math.max(1, bytesPerS / size);
			long seq = 0, start = SystemClock.elapsedRealtime();
			mStopRate = false;
			while (!mStopRate && mOpen) {
				long due = start + seq * 1000 / perS;
				long wait = due - SystemClock.elapsedRealtime();
				if (wait > 0) {
					SystemClock.sleep(Math.min(wait, 5));
					continue;
				}
				StringBuilder sb = new StringBuilder("$KNB,").append(seq).append(',');
				int payload = size - sb.length() - 5;
				for (int i = 0; i < payload; i++) sb.append((char) ('A' + (seq + i) % 26));
				int x = 0;
				for (int i = 1; i < sb.length(); i++) x ^= sb.charAt(i);
				sb.append(String.format(Locale.US, "*%02X\r\n", x));
				send(ascii(sb.toString()));
				seq++;
			}
			if (mOpen) answer("STOP " + seq + " " + (SystemClock.elapsedRealtime() - start));
		}

		private void handle(@NonNull String line) {
			String[] p = line.trim().split(" ");
			if (p.length == 0 || p[0].isEmpty()) return;
			long a = 0, b = 0;
			try {
				if (p.length > 1) a = Long.parseLong(p[1]);
				if (p.length > 2) b = Long.parseLong(p[2]);
			} catch (NumberFormatException ignored) {
			}
			long fa = a, fb = b;
			switch (p[0]) {
				case "PING" -> answer("PONG " + a);
				case "INFO" -> answer("INFO knble-classic-phone v" + VERSION);
				case "STREAM" -> job(() -> stream(fa));
				case "SINK" -> {
					mSinkTotal = mSinkLeft = a;
					mSinkCrc.reset();
					mSinkFirst = 0;
					answer("SINK READY");
				}
				case "RATE" -> job(() -> rate(fa, (int) fb));
				case "STOP" -> mStopRate = true;
				case "DISC" -> {
					answer("DISC " + a);
					new Thread(() -> {
						SystemClock.sleep(fa);
						mLog.log("Classic: closing (DISC)");
						close();
					}, "classic-disc").start();
				}
				case "STATS" -> answer("STATS rx=" + mRx + " tx=" + mTx + " cong=0 failures=0");
				default -> answer("ERR " + p[0]);
			}
		}

		private void feed(@NonNull byte[] d, int n) {
			mRx += n;
			int i = 0;
			while (i < n) {
				if (mSinkLeft > 0) {
					if (mSinkFirst == 0) mSinkFirst = SystemClock.elapsedRealtime();
					int take = (int) Math.min(mSinkLeft, n - i);
					mSinkCrc.update(d, i, take);
					mSinkLeft -= take;
					i += take;
					if (mSinkLeft == 0) {
						answer(String.format(Locale.US, "SINK %d %08X %d", mSinkTotal, mSinkCrc.getValue(), SystemClock.elapsedRealtime() - mSinkFirst));
					}
					continue;
				}
				char c = (char) (d[i++] & 0xFF);
				if (c == '\r') continue;
				if (c == '\n') {
					String line = mLine.toString();
					mLine.setLength(0);
					handle(line);
				} else if (mLine.length() < 63) {
					mLine.append(c);
				}
			}
		}
	}
}
