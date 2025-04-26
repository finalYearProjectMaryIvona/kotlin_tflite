# Traffic Detection Mobile App (Android only)

An Android mobile application that performs real-time traffic object detection using TensorFlow Lite models.  
Designed to capture, analyze, and log traffic data by processing either live camera feed or uploaded video files.  
Works with a backend server to sync session data and detection results.

## Features

- Real-time object detection and tracking of road vehicles (cars, buses, trucks, motorcycles, bicycles)
- Bounding box visualization with class identification and confidence scores
- Movement direction tracking for detected objects
- Analyze live camera input or pre-recorded videos
- User authentication and session management
- Log and send detected traffic object data to a remote server
- Location-based session tagging using GPS
- TFLite model
- Public/private session visibility options
- Test mode for debugging and image capture

## Technologies Used

- Kotlin
- Android SDK
- TensorFlow Lite (YOLOv10s model)
- OkHttp (for REST API communication)
- Google Location Services API
- Android Jetpack (ViewModel, LiveData, Navigation)
- CameraX API


## Getting Started

### Clone the repository
```bash
git clone https://github.com/finalYearProjectMaryIvona/kotlin_tflite.git
cd kotlin_tflite
```
### Open in Android Studio
- Open the project folder in Android Studio.
- Let Gradle sync and download all required dependencies.

## Important assets
Model and labels are already in assets folder (app/src/main/assets/)

## Environment Setup

Update BASE_IP with device's ipv4 address in Constants.kt
```bash
// Replace PUTYOURIPV4HERE with your server's IPv4 address
const val BASE_IP = "http://YOUR_SERVER_IP:5000"
```

Update detection settings if needed in Constants.kt
```bash
// Detection settings
const val CONFIDENCE_THRESHOLD = 0.35f  // Minimum confidence score
const val IOU_THRESHOLD = 0.45f  // Intersection over Union threshold

// Tracking settings
const val MAX_DISAPPEARED_FRAMES = 25  // How long to track after object disappears
const val DIRECTION_THRESHOLD = 0.015f  // Minimum movement to detect direction
```
## Required Permissions
When running the app, Camera, Location and Storage permissions will be asked.

## Usage
- Login with your email. (new email will be created if it doesn't exist in the database, server needs to be running for authentication).
- Capture live camera view.
- Detect and track traffic objects with coloured bounding boxes.
- See detection boxes and IDs on screen.
- See tracking done on screen.
- Set session privacy with Public/Private toggle.
- Detection results and session data are automatically sent to the backend server.
- Open Test mode to see tutorial.

## Build and Run
Have mobile device connected to computer (through wifi or cable). Once Android Studio sees you mobile device, click on Run button to build and deploy the app on mobile device.

## Backend Server
This mobile app needs a backend server for user management, session storage and visualization

Make sure the [Backend Server](https://github.com/finalYearProjectMaryIvona/backend_server) is running and is accessible from the mobile device (on the same wifi or hotspot).

## Links
[Backend Server](https://github.com/finalYearProjectMaryIvona/backend_server)<br/>
[React Web App](https://github.com/finalYearProjectMaryIvona/front_end_web_app)<br/>
