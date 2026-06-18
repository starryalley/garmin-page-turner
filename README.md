# Garmin Page Turner Companion

Turn pages on your E-Reader or tablet using your Garmin watch! 

This project allows you to control reading applications (like Kindle, Kobo, or generic PDF readers) by sending page-turn commands from your Garmin watch. It works by using an Android phone as a bridge that emulates a Bluetooth HID Keyboard. In addition there is a Refresh command that's specifically for Eink devices (tested only on Boox tablets).

## 🚀 How it Works

1.  **Garmin Watch App**: Detects gestures (flicks) or button presses and sends a command to the paired Android phone via Garmin ConnectIQ Communications.
2.  **Android Companion App**: Receives the command and translates it into a Bluetooth HID keyboard stroke (e.g., "Right Arrow" or "Page Down") sent to a connected E-Reader.
3.  **E-Reader / Tablet**: Receives the keystroke as if it came from a physical Bluetooth keyboard and turns the page.

## ✨ Features

-   **Touch Interface**: Simple tap zones on the watch face for manual control.
-   **Physical Buttons**: Use your watch's physical buttons (Up/Down/Enter) to turn pages.
-   **Gesture Control**: Turn pages with a "flick" motion of your wrist (uses high-frequency accelerometer data) - mostly for fun, tuned for piano playing by quickly lifting your wrist and back on the keyboard. Realistically it would be faster by tapping on the ereader screen to turn the page though.
-   **Multiple Mappings**: Switch between Arrow Keys, Page Up/Down, or Space/Backspace mappings in the Android app.
-   **Background Service**: The Android app runs as a foreground service to maintain a stable connection even when the screen is off.
-   **Live Logs**: Monospace console in the Android app for real-time debugging of connection states and commands.

## 📂 Project Structure

-   `garmin-page-turner/`: The ConnectIQ application source code (Monkey C).
-   `android-companion/`: The Android bridge application source code (Kotlin/Jetpack Compose).

## 🛠 Setup Instructions

### 1. Garmin Watch
-   Build and side-load the `garmin-page-turner` app using the ConnectIQ SDK.
-   Ensure your watch is paired with your Android phone via the Garmin Connect app.

### 2. Android Phone
-   Build and install the `android-companion` app.
-   Grant necessary Bluetooth and Notification permissions.
-   Start the service using the toggle in the app.
-   **Pairing Mode**: Click "Pairing Mode" in the app and pair your E-Reader/Tablet to your phone as a Bluetooth Keyboard.

### 3. Connection
-   In the Android app, select your E-Reader from the paired devices list and click **Connect**.
-   Open the Page Turner app on your Garmin watch.
-   Once both connections are green (Garmin Watch and E-Reader HID), you are ready to read!

## 📜 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! Feel free to check the issues page if you want to contribute.

