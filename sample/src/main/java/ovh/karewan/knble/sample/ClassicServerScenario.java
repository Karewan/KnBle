package ovh.karewan.knble.sample;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

import ovh.karewan.knble.BleError;
import ovh.karewan.knble.ClassicOptions;
import ovh.karewan.knble.ClassicServerOptions;
import ovh.karewan.knble.KnBle;
import ovh.karewan.knble.interfaces.BleClassicCallback;
import ovh.karewan.knble.interfaces.BleClassicServerCallback;
import ovh.karewan.knble.interfaces.BleWriteCallback;
import ovh.karewan.knble.struct.BleDevice;

/**
 * The classic bench made with the classic server of KnBle: the protocol of the ESP32 classic bench and of benchhost
 * (PING, INFO, STREAM, SINK, RATE, STOP, DISC, STATS), so the classic scenarios of another phone run against it
 * (--es classic <MAC of this phone>). Insecure, SPP UUID, name KnBleSpp: what those scenarios expect.
 */
final class ClassicServerScenario {
	private static final int VERSION = 1;
	private static final int CHUNK = 990;

	@NonNull
	private final Report r;
	private final AtomicInteger mClients = new AtomicInteger();

	ClassicServerScenario(@NonNull Report report) {
		r = report;
	}

	/**
	 * Scenario thread: the server for this long, the clients served on the main thread
	 */
	void run(long durationMs, boolean secure) {
		CountDownLatch result = new CountDownLatch(1);
		int[] error = {0};
		ClassicServerOptions options = new ClassicServerOptions.Builder().setName(ClassicScenarios.NAME).setUuid(ClassicOptions.SPP)
				.setSecure(secure).build();
		KnBle.startClassicServer(options, new BleClassicServerCallback() {
			@Nullable
			@Override
			public BleClassicCallback onClientConnected(@NonNull BleDevice client) {
				r.log("Server: client %s (%s), %d so far", client.getMac(), client.getName(), mClients.incrementAndGet());
				return new Client(client);
			}

			@Override
			public void onServerStarted() {
				r.log("Classic server started (%s, %s)", ClassicScenarios.NAME, secure ? "secure" : "insecure");
				result.countDown();
			}

			@Override
			public void onServerFailed(int e) {
				error[0] = e;
				r.log("Classic server failed: %s", BleError.name(e));
				result.countDown();
			}
		});
		try {
			if (!result.await(10, TimeUnit.SECONDS)) r.log("Classic server: no result in 10 s");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		r.result("classic_server", "started=%b error=%s", error[0] == 0 && KnBle.isClassicServerStarted(), BleError.name(error[0]));
		if (error[0] != 0) return;
		r.log("Classic server for %d s: run the classic scenarios on another phone with --es classic <MAC of this phone>", durationMs / 1000);
		SystemClock.sleep(durationMs);
		KnBle.stopClassicServer();
		r.result("classic_server_end", "clients=%d", mClients.get());
	}

	private static byte[] ascii(@NonNull String s) {
		return s.getBytes(StandardCharsets.US_ASCII);
	}

	/**
	 * One client: its commands, its sink and its streams (main thread, the RATE frames on a thread of their own)
	 */
	private final class Client implements BleClassicCallback {
		@NonNull
		private final BleDevice mDevice;
		private final StringBuilder mLine = new StringBuilder();
		private final CRC32 mSinkCrc = new CRC32();
		private long mRx, mSinkLeft, mSinkTotal, mSinkFirst;
		private final AtomicInteger mTx = new AtomicInteger();
		@Nullable
		private HandlerThread mRateThread;
		private volatile boolean mOpen = true, mStopRate;

		Client(@NonNull BleDevice device) {
			mDevice = device;
		}

		private void send(@NonNull byte[] b, @Nullable BleWriteCallback callback) {
			mTx.addAndGet(b.length);
			KnBle.writeClassic(mDevice, b, callback);
		}

		private void answer(@NonNull String text) {
			send(ascii(text + "\n"), null);
		}

		@Override
		public void onConnected() {
			r.log("Server: %s connected", mDevice.getMac());
		}

		@Override
		public void onDisconnected(boolean connectFailed, int status) {
			mOpen = false;
			mStopRate = true;
			HandlerThread t = mRateThread;
			if (t != null) t.quitSafely();
			r.log("Server: %s disconnected (%s) rx=%d tx=%d", mDevice.getMac(), BleError.name(status), mRx, mTx.get());
		}

		@Override
		public void onData(@NonNull byte[] d) {
			mRx += d.length;
			int i = 0, n = d.length;
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

		private void handle(@NonNull String line) {
			String[] p = line.trim().split(" ");
			if (p.length == 0 || p[0].isEmpty()) return;
			long a = 0, b = 0;
			try {
				if (p.length > 1) a = Long.parseLong(p[1]);
				if (p.length > 2) b = Long.parseLong(p[2]);
			} catch (NumberFormatException ignored) {
			}
			switch (p[0]) {
				case "PING" -> answer("PONG " + a);
				case "INFO" -> answer("INFO knble-classic-server v" + VERSION);
				case "STREAM" -> stream(a);
				case "SINK" -> {
					mSinkTotal = mSinkLeft = a;
					mSinkCrc.reset();
					mSinkFirst = 0;
					answer("SINK READY");
				}
				case "RATE" -> rate(a, (int) b);
				case "STOP" -> mStopRate = true;
				case "DISC" -> {
					answer("DISC " + a);
					// The server closes the connection: the client sees it lost
					new Handler(android.os.Looper.getMainLooper()).postDelayed(() -> KnBle.disconnectClassic(mDevice), a);
				}
				case "STATS" -> answer("STATS rx=" + mRx + " tx=" + mTx.get() + " cong=0 failures=0");
				default -> answer("ERR " + p[0]);
			}
		}

		/**
		 * The pattern of the bench, as fast as the link goes: every chunk queued at once (KnBle writes them in order)
		 */
		private void stream(long total) {
			long start = SystemClock.elapsedRealtime();
			long sent = 0;
			while (sent < total) {
				int n = (int) Math.min(CHUNK, total - sent);
				byte[] b = new byte[n];
				for (int i = 0; i < n; i++) {
					long pos = sent + i;
					b[i] = (byte) (pos * 31 + (pos >> 8));
				}
				sent += n;
				boolean last = sent >= total;
				send(b, !last ? null : new BleWriteCallback() {
					@Override
					public void onWriteSuccess() {
						answer("END " + total + " " + (SystemClock.elapsedRealtime() - start) + " 0");
					}

					@Override
					public void onWriteFailed(int error) {
						r.log("Server: stream failed %s", BleError.name(error));
					}
				});
			}
			if (total == 0) answer("END 0 0 0");
		}

		/**
		 * Frames "$KNB,seq,payload*XX" at a steady rate until STOP, on a thread of their own
		 */
		private void rate(long bytesPerS, int frameSize) {
			if (mRateThread != null) return;
			int size = Math.max(24, Math.min(600, frameSize));
			long perS = Math.max(1, bytesPerS / size);
			HandlerThread t = new HandlerThread("rate");
			t.start();
			mRateThread = t;
			mStopRate = false;
			Handler h = new Handler(t.getLooper());
			long start = SystemClock.elapsedRealtime();
			long[] seq = {0};
			h.post(new Runnable() {
				@Override
				public void run() {
					if (mStopRate || !mOpen) {
						if (mOpen) answer("STOP " + seq[0] + " " + (SystemClock.elapsedRealtime() - start));
						mRateThread = null;
						t.quitSafely();
						return;
					}
					long now = SystemClock.elapsedRealtime();
					while (start + seq[0] * 1000 / perS <= now) {
						StringBuilder sb = new StringBuilder("$KNB,").append(seq[0]).append(',');
						int payload = size - sb.length() - 5;
						for (int i = 0; i < payload; i++) sb.append((char) ('A' + (seq[0] + i) % 26));
						int x = 0;
						for (int i = 1; i < sb.length(); i++) x ^= sb.charAt(i);
						sb.append(String.format(Locale.US, "*%02X\r\n", x));
						send(ascii(sb.toString()), null);
						seq[0]++;
					}
					h.postDelayed(this, 5);
				}
			});
		}
	}
}
