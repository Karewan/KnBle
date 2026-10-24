package ovh.karewan.knble.sample;

import android.app.Application;

import ovh.karewan.knble.KnBle;

public class SampleApp extends Application {

	@Override
	public void onCreate() {
		super.onCreate();

		// Off by default: the debug logs of KnBle (a stack trace per line) would distort the measurements
		KnBle.DEBUG = false;
		KnBle.init(this);
	}
}
