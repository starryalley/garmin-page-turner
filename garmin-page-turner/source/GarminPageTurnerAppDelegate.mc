import Toybox.WatchUi;
import Toybox.Communications;
import Toybox.System;
import Toybox.Sensor;
import Toybox.Lang;
import Toybox.Timer;

class GarminPageTurnerAppDelegate extends WatchUi.InputDelegate {
    private var mView;
    private var mFlickCooldown = false; // Prevents rapid-fire triggers
    private var mCooldownTimer;

    function initialize(view) {
        InputDelegate.initialize();
        mView = view;
        mCooldownTimer = new Timer.Timer();

        // Register high-frequency accelerometer listener at 25 Hz
        // This gives us 25 samples per second — much better for flick detection
        var options = {
            :period => 1,
            :accelerometer => {
                :enabled => true,
                :sampleRate => 25
            }
        };
        try {
            Sensor.registerSensorDataListener(method(:onSensorData), options);
        } catch (e) {
            System.println("Accel registration failed: " + e.getErrorMessage());
        }
    }

    // Handles touchscreen tap events
    function onTap(clickEvent) {
        var coords = clickEvent.getCoordinates();
        var x = coords[0];
        var y = coords[1];

        var width = System.getDeviceSettings().screenWidth;
        var height = System.getDeviceSettings().screenHeight;

        var yDivider = (height * 0.75).toNumber(); // bottom 25%, top 75%
        var xDivider = (width * 0.20).toNumber(); // left 20%, right 80%

        if (y > yDivider) {
            sendAction("refresh");
        } else {
            if (x < xDivider) {
                sendAction("left");
            } else {
                sendAction("right");
            }
        }

        return true;
    }

    // Handles physical button presses
    function onKey(keyEvent) {
        var key = keyEvent.getKey();
        if (key == WatchUi.KEY_UP) {
            sendAction("left");
            return true;
        } else if (key == WatchUi.KEY_DOWN) {
            sendAction("refresh");
            return true;
        } else if (key == WatchUi.KEY_ENTER) {
            sendAction("right");
            return true;
        }
        return false;
    }

    // Transmits the action command to the companion Android app
    function sendAction(action) {
        mView.setStatus("Sending...", null);

        var message = {
            "command" => action
        };

        var listener = new PageTurnerCommListener(mView, action);

        try {
            Communications.transmit(message, null, listener);
        } catch (e) {
            mView.setStatus("TX Error", false);
        }
    }

    var mSampleCounter = 0;

    var mGestureState = 0; // 0=idle, 1=saw negative Z spike
    var mGestureAge = 0;

    var mRestCount = 0;

    function onSensorData(sensorData as Sensor.SensorData) as Void {
        if (sensorData.accelerometerData == null) {
            return;
        }

        var xs = sensorData.accelerometerData.x;
        var ys = sensorData.accelerometerData.y;
        var zs = sensorData.accelerometerData.z;

        if (xs == null || ys == null || zs == null) {
            return;
        }

        //var batchTs = System.getTimer();

        for (var i = 0; i < xs.size(); i++) {
            var x = xs[i];
            var y = ys[i];
            var z = zs[i];

            //var ts = batchTs + i * 40;
            var magSq = x*x + y*y + z*z;

            var looksRestingOnKeyboard =
                (z < -700 && z > -1400) &&
                (x > -700 && x < 1000) &&
                (y > -1000 && y < 1000);

            if (mGestureState == 0 && looksRestingOnKeyboard) {
                mRestCount += 1;
            } else if (mGestureState == 0) {
                if (mRestCount > 0) {
                    mRestCount -= 1;
                }
            }

            // Log only useful samples/events.
            if (
                z < -1800 ||
                z > 500 ||
                magSq > 2500000 ||
                mGestureState != 0 ||
                looksRestingOnKeyboard
            ) {
                // System.println(
                //     "S," +
                //     mSampleCounter + "," +
                //     ts + "," +
                //     x + "," +
                //     y + "," +
                //     z + "," +
                //     magSq + "," +
                //     mGestureState + "," +
                //     mRestCount
                // );
            }

            mSampleCounter += 1;

            if (mFlickCooldown) {
                continue;
            }

            if (mGestureState == 0) {
                // Require recent keyboard-rest posture first.
                if (mRestCount >= 4 && z < -2200) {
                    // System.println(
                    //     "NEG," +
                    //     mSampleCounter + "," +
                    //     ts + "," +
                    //     x + "," +
                    //     y + "," +
                    //     z + "," +
                    //     mRestCount
                    // );

                    mGestureState = 1;
                    mGestureAge = 0;
                }

            } else if (mGestureState == 1) {
                mGestureAge += 1;

                // Positive rebound after negative Z spike.
                if (z > 1000) {
                    // System.println(
                    //     "FLICK," +
                    //     mSampleCounter + "," +
                    //     ts + "," +
                    //     x + "," +
                    //     y + "," +
                    //     z
                    // );

                    sendAction("right");

                    mGestureState = 0;
                    mGestureAge = 0;
                    mRestCount = 0;

                    mFlickCooldown = true;
                    mCooldownTimer.start(method(:onCooldownExpired), 1500, false);
                    return;
                }

                // Too slow: reject.
                if (mGestureAge > 12) {
                    // System.println(
                    //     "TIMEOUT," +
                    //     mSampleCounter + "," +
                    //     ts
                    // );

                    mGestureState = 0;
                    mGestureAge = 0;
                    mRestCount = 0;
                }
            }
        }
    }

    function onCooldownExpired() as Void {
        mFlickCooldown = false;
    }

}
// ConnectionListener subclass to handle transmission success/error states
class PageTurnerCommListener extends Communications.ConnectionListener {
    private var mView;
    private var mAction;

    function initialize(view, action) {
        ConnectionListener.initialize();
        mView = view;
        mAction = action;
    }

    function onComplete() {
        mView.setStatus("Sent " + mAction.toUpper(), true);
    }

    function onError() {
        mView.setStatus("Failed", false);
    }
}
