import Toybox.WatchUi;
import Toybox.Graphics;
import Toybox.System;

class GarminPageTurnerAppView extends WatchUi.View {
    private var mStatus = "Ready";
    private var mStatusColor = Graphics.COLOR_WHITE;

    function initialize() {
        View.initialize();
    }

    // Load your resources here
    function onLayout(dc) {
    }

    // Called when this View is brought to the foreground
    function onShow() {
    }

    // Update the view
    function onUpdate(dc) {
        var width = dc.getWidth();
        var height = dc.getHeight();

        // Clear screen with a dark, premium background (black)
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();

        // Set divider line styling
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_TRANSPARENT);
        dc.setPenWidth(2);

        // Divider coordinates
        var yDivider = (height * 0.75).toNumber();
        
        // Vertical divider separating Left and Right zones (in the top 75%), 20% LEFT and 80% RIGHT
        var xDivider = (width * 0.20).toNumber();
        dc.drawLine(xDivider, 0, xDivider, yDivider);
        
        // Horizontal divider separating Left/Right zones from Bottom Refresh zone
        dc.drawLine(0, yDivider, width, yDivider);

        // Draw buttons/text labels
        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_TRANSPARENT);
        
        // Left label center
        dc.drawText(
            xDivider / 2,
            height * 0.35,
            Graphics.FONT_LARGE,
            "<",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );

        // Right label center
        dc.drawText(
            xDivider + ((width - xDivider) / 2),
            height * 0.35,
            Graphics.FONT_LARGE,
            ">",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );

        // Refresh Label
        dc.drawText(width / 2, height * 0.86, Graphics.FONT_SMALL, "REFRESH", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Draw App Title
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height * 0.12, Graphics.FONT_XTINY, "PAGE TURNER", Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        // Draw current status with custom dynamic colors
        dc.setColor(mStatusColor, Graphics.COLOR_TRANSPARENT);
        dc.drawText(width / 2, height * 0.55, Graphics.FONT_SMALL, mStatus, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }

    // Called when this View is removed from the screen
    function onHide() {
    }

    // Set the status message and color
    function setStatus(status, success) {
        mStatus = status;
        if (success == null) {
            mStatusColor = Graphics.COLOR_WHITE;
        } else if (success) {
            mStatusColor = Graphics.COLOR_GREEN;
        } else {
            mStatusColor = Graphics.COLOR_RED;
        }
        WatchUi.requestUpdate();
    }
}
