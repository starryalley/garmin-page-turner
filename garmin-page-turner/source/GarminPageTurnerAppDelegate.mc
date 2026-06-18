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

    // High-frequency accelerometer callback (25 Hz batched every 1 second)
    // Detects a wrist flick by checking total acceleration magnitude across all axes.
    // At rest, magnitude² ≈ 1,000,000 (1g from gravity).
    // A deliberate wrist flick spikes total acceleration well above 1g.
    function onSensorData(sensorData as Sensor.SensorData) as Void {
        if (mFlickCooldown) {
            return; // Still in cooldown from last flick
        }

        if (sensorData.accelerometerData != null) {
            var xSamples = sensorData.accelerometerData.x;
            var ySamples = sensorData.accelerometerData.y;
            var zSamples = sensorData.accelerometerData.z;
            if (xSamples != null && ySamples != null && zSamples != null) {
                // Scan all 25 samples in this batch
                for (var i = 0; i < xSamples.size(); i++) {
                    var ax = xSamples[i];
                    var ay = ySamples[i];
                    var az = zSamples[i];
                    // Magnitude squared — avoids expensive sqrt
                    // Rest: ~1,000,000 (1g²)
                    // Threshold: 2.2g → 2200² = 4,840,000
                    var magSq = ax * ax + ay * ay + az * az;
                    if (magSq > 4840000) {
                        sendAction("right");
                        // 2-second cooldown to prevent double-triggers
                        mFlickCooldown = true;
                        mCooldownTimer.start(method(:onCooldownExpired), 2000, false);
                        return;
                    }
                }
            }
        }
    }

    // Called when cooldown period ends — re-enables flick detection
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
