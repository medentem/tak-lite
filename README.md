# TAK Lite

A situational awareness Android application designed for use with mesh network devices (Doodle Labs, Meshtastic)

## Features

- Real-time location tracking and sharing
  - Peers are shown as green dots
- Interactive map display with multiple user positions
  - Street, sattelite and hybrid views
  - 3D view of buildings and terrain
- Points of Interest (POI) management and map annotation (lines, areas)
  - Easy longpress-and-drag fan menu to drop and edit annotations
  - Optionally expire annotations and POIs
  - View elevation chart for lines
- Text messaging
- Push-to-Talk (PTT) real-time VOIP audio communication (Layer 2 only, WebRTC-based)
- Mesh network integration (meshtastic or doodle labs)
- Offline map tile support
- Dark mode support

## Example Screenshots

Peer Locations
<br />
<img src="https://github.com/user-attachments/assets/70fac0d2-ee35-4cc0-a166-27d73342fdf2" width=200 />
<br />

Adaptive fan menu w/ coordinates and distance from current location
<br />
<img src="https://github.com/user-attachments/assets/17f764fb-f6f4-416d-8323-66144bd0769d" width=200 />
<br />

Annotation Editing
<br />
<img src="https://github.com/user-attachments/assets/aba0119c-90eb-4765-964f-8ba3213e423a" width=200 />
<br />

Line Elevation View
<br />
<img src="https://github.com/user-attachments/assets/0b7e4158-1142-499a-9a55-47bbf33e6f2e" width=200 />
<br />

3D Building & Terrain (terrain 3d is just hill shading now, true 3d will be added later)
<br />
<img src="https://github.com/user-attachments/assets/13a5f07a-ac55-4a96-837f-3f589ad3a12e" width=200 />
<br />

Settings to Configure Experience
<br />
<img src="https://github.com/user-attachments/assets/6fc716c3-7bbd-401b-a900-d7f0808183f5" width=200 />
<br />

## Please Support the Project - Mapping API's are not free, sadly
- [GitHub Sponsors](https://github.com/sponsors/medentem)
- Bitcoin (BTC): 1BimJFWZA6LXhgiTcrj7rFBB3hbENrCLRc
- Ethereum (ETH): 0x035d513B41c91c61c4C4E8382a675FaeF53AD953

## Requirements

- Android Studio Arctic Fox or later
- Android SDK 24 (Android 7.0) or later
- MapTiler API key (for satellite imagery)
- Doodle Labs mesh network device or Meshtastic (no audio support w/ mtastic)

## De-Googled Device Support

TAK Lite is designed to work on devices without Google Play Services (LineageOS, GrapheneOS, etc.):

- **COMING SOON** - alternatives for other google dependencies
- **Privacy-Friendly Donations**: Multiple donation options including cryptocurrency, PayPal, and GitHub Sponsors
- **Manual Premium Activation**: Users can activate premium features after donating through alternative methods

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


- **Meshtastic Devices**: The app connects to a Meshtastic device, which provides the network layer for peer discovery, sending annotations and telemetry.
- **Data Synchronization**: Location, POI, and annotation data are shared directly between peers over the mesh, ensuring real-time situational awareness without a central server.
- **Resilience**: The mesh network is resilient to node failures and adapts dynamically as devices join or leave the network.
- **IN PROGRESS - Doodle Labs Mesh Devices**: The app connects to a Doodle Labs mesh radio, which provides a local, self-healing, peer-to-peer network for all connected devices.
  - **Peer Discovery**: Devices on the mesh automatically discover each other using local network broadcast and/or service discovery protocols.
  - **VOIP over Mesh**: Push-to-talk audio (WebRTC) is transmitted directly between devices using the mesh network, minimizing latency and supporting robust communication even in disconnected or degraded environments.
  - **Gateway Support**: If a mesh node has internet access, it can act as a gateway, allowing the mesh to bridge to external networks as needed.

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

## Security

- Over-the-air encryption is provided by the mesh device - Doodle Labs mesh network at Layer 2 or Meshtastic's channel and PKI encryption - ensuring that all data transmitted between devices is encrypted at the network level.
- Sensitive API keys are not hardcoded; they are loaded from `local.properties` at build time
- Proper Android permission handling for location and audio
- Follows Android security best practices for data storage and network communication
- Plans for data encryption and secure authentication in future releases

## Documentation

- Contributions should follow the established code style and architecture
- Please open issues or pull requests for suggestions or improvements
