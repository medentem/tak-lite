# TAK Lite

A situational awareness Android application designed for use with mesh network devices (Doodle Labs, Meshtastic)

## Features

- Real-time location tracking and sharing
- Interactive map display with multiple user positions
  - Street, sattelite and hybrid views
  - 3D view of buildings and terrain
- Points of Interest (POI) management and map annotation (lines, areas)
  - Easy longpress-and-drag fan menu to drop and edit annotations
  - Optionally expire annotations and POIs
- Push-to-Talk (PTT) real-time VOIP audio communication (WebRTC-based)
- Mesh network integration
- Offline map tile support

## Example Screenshots

<img src="https://github.com/user-attachments/assets/f8ad6c71-1349-40ee-bb55-24551b9ef2d6" width="250" />
<img src="https://github.com/user-attachments/assets/884b5af4-efd3-4636-9b8e-51f06e07dc58" width="250" />
<img src="https://github.com/user-attachments/assets/faed35d4-25cf-451c-872e-d1215f36cc8a" width="250" />
<img src="https://github.com/user-attachments/assets/09454976-f445-4b4a-9aca-67b984acefe4" width="250" />
<img src="https://github.com/user-attachments/assets/4d578b3e-faa5-4ae0-965b-d44f096407ca" width="250" />
<img src="https://github.com/user-attachments/assets/ae705aec-2977-4098-ba7b-3bb4f35eb8c8" width="250" />
<img src="https://github.com/user-attachments/assets/66a94189-fa41-49ba-aa81-bb705ddc7d1c" width="250" />


## Requirements

- Android Studio Arctic Fox or later
- Android SDK 24 (Android 7.0) or later
- MapTiler API key (for satellite imagery)
- Doodle Labs mesh network device or Meshtastic (no audio support w/ mtastic)

## Setup

1. Clone the repository
2. Open the project in Android Studio
3. Create a `local.properties` file in the root directory and add your API key:
   ```
   MAPTILER_API_KEY=your_maptiler_api_key_here
   ```
   - `MAPTILER_API_KEY` is required for satellite map (MapTiler) functionality. This key is securely loaded by the build system from `local.properties` and injected into the app at build time.
4. Sync the project with Gradle files
5. Build and run the application

## Mesh Rider Setup

1. Ensure one radio on the network is setup as the DHCP server with the radio on 192.168.1.10, subnet 255.255.255.0.
2. If you want the radio's GPS to work with the app, continue:
3. Ensure the GPS service is bound to all network interfaces
4. Ensure the radio firewall allows GPS port traffic

## Dependencies

- MapLibre GL Android SDK (map rendering)
- OpenStreetMap raster tiles (default map)
- MapTiler satellite tiles (satellite view)
- Google Play Services Location (location tracking)
- Android Room Database (local storage)
- Kotlin Coroutines (async operations)
- AndroidX Lifecycle Components (MVVM)
- Hilt (dependency injection)
- WebRTC (real-time VOIP audio)
- Kotlin Serialization (data serialization)
- WorkManager (background tasks)

## Network Architecture

The application communicates over a local mesh network using Doodle Labs devices. Key network features include:

- Local mesh communication for real-time updates
- Internet connectivity when available through mesh gateway
- Peer-to-peer real-time VOIP audio communication (WebRTC)
- Location data sharing
- POI and annotation synchronization

## Mesh Networking Layer

TAK Lite is designed to operate in environments with limited or no internet connectivity by leveraging a mesh networking approach:

- **Doodle Labs Mesh Devices**: The app connects to a Doodle Labs mesh radio, which provides a local, self-healing, peer-to-peer network for all connected devices.
- **Peer Discovery**: Devices on the mesh automatically discover each other using local network broadcast and/or service discovery protocols.
- **Data Synchronization**: Location, POI, and annotation data are shared directly between peers over the mesh, ensuring real-time situational awareness without a central server.
- **VOIP over Mesh**: Push-to-talk audio (WebRTC) is transmitted directly between devices using the mesh network, minimizing latency and supporting robust communication even in disconnected or degraded environments.
- **Gateway Support**: If a mesh node has internet access, it can act as a gateway, allowing the mesh to bridge to external networks as needed.
- **Resilience**: The mesh network is resilient to node failures and adapts dynamically as devices join or leave the network.

**Further Reading:**
- [Doodle Labs Mesh Networking Overview](https://doodlelabs.com)
- [Doodle Labs Technical Documentation](https://support.doodlelabs.com/hc/en-us/categories/360002078032-Technical-Documentation)

## Development Status

This project is currently in active development. Core features are being implemented and tested.

---

## Architecture

- **MVVM Pattern**: The app uses the Model-View-ViewModel (MVVM) architecture for clear separation of concerns and testability.
- **Dependency Injection**: Hilt is used for dependency injection, ensuring modularity and easier testing.
- **Room Database**: Local data persistence is handled via Room, with repository and DAO patterns.
- **Coroutines**: Kotlin Coroutines are used for asynchronous operations, including networking and database access.
- **WorkManager**: Background tasks (such as data sync) are managed using WorkManager.
- **Lifecycle Components**: AndroidX ViewModel and LiveData are used for UI state management.
- **WebRTC**: Real-time VOIP audio is implemented using WebRTC, supporting push-to-talk communication.

## Code Style & Best Practices

- Written in Kotlin, following [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Uses Android KTX extensions for concise code
- Follows SOLID principles and clean architecture
- Material Design 3 guidelines for UI/UX
- Proper error handling and state management
- Accessibility (a11y) and dark mode support

## Testing

- Unit tests for business logic (ViewModels, repositories, etc.)
- UI tests for critical user flows
- Uses mock objects and dependency injection for testability
- Test coverage is maintained for core features

## Security

- Over-the-air encryption is provided by the Doodle Labs mesh network at Layer 2, ensuring that all data transmitted between devices is encrypted at the network level.
- Sensitive API keys are not hardcoded; they are loaded from `local.properties` at build time
- Proper Android permission handling for location and audio
- Follows Android security best practices for data storage and network communication
- Plans for data encryption and secure authentication in future releases

## Documentation

- Public APIs and architecture decisions are documented in-code and in this README
- Contributions should follow the established code style and architecture
- Please open issues or pull requests for suggestions or improvements
