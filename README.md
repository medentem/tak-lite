# TAK Lite

A situational awareness Android application designed for use with Doodle Labs mesh network devices.

## Features

- Real-time location tracking and sharing
- Interactive map display with multiple user positions
- Points of Interest (POI) management
- Push-to-Talk (PTT) audio communication
- Mesh network integration

## Requirements

- Android Studio Arctic Fox or later
- Android SDK 24 (Android 7.0) or later
- Google Maps API key
- Doodle Labs mesh network device

## Setup

1. Clone the repository
2. Open the project in Android Studio
3. Create a `local.properties` file in the root directory and add your Google Maps API key:
   ```
   MAPS_API_KEY=your_api_key_here
   ```
4. Sync the project with Gradle files
5. Build and run the application

## Dependencies

- Google Maps SDK for Android
- Google Play Services Location
- Android Room Database
- Kotlin Coroutines
- AndroidX Lifecycle Components

## Network Architecture

The application communicates over a local mesh network using Doodle Labs devices. Key network features include:

- Local mesh communication for real-time updates
- Internet connectivity when available through mesh gateway
- Peer-to-peer audio communication
- Location data sharing
- POI synchronization

## Development Status

This project is currently in active development. Core features are being implemented and tested.
