# JourneyMap Entity Bridge

Shows modded entities as colored markers on [JourneyMap](https://modrinth.com/mod/journeymap) minimap and fullscreen map.

**Forge 1.20.1** | Client-side only | MIT License

## Supported Entities

| Mod | Entity | Marker Color |
|-----|--------|-------------|
| [Immersive Vehicles (MTS)](https://modrinth.com/mod/immersive-vehicles) | Vehicles, seats | Blue |
| [MTR (Minecraft Transit Railway)](https://modrinth.com/mod/mtr) | Trains | Red |
| [Yuushya](https://modrinth.com/mod/yuushya) | Ride entities | Green |

## How It Works

JourneyMap's built-in entity radar only recognizes vanilla entity types (`IAnimal`, `IMob`, etc.). This mod uses JourneyMap's Plugin API to place `MarkerOverlay` dots at tracked entity positions, updated once per second.

- Entities are matched by registry ID at runtime — **no compile dependency** on IV, MTR, or Yuushya
- If JourneyMap is not installed, the mod does nothing (the plugin class is never classloaded)
- If any tracked mod is absent, its entity type is simply never matched

## Installation

Drop `jm-entity-bridge-x.x.x.jar` into your client's `mods/` folder. Requires:

- Forge 1.20.1 (47.x)
- JourneyMap 5.9.0+ (tested with 5.10.3)

Server-side installation is not needed (but harmless if present).

## Building

```sh
./gradlew build
```

Output jar: `build/libs/jm-entity-bridge-x.x.x.jar`

Requires JDK 17.

## Notes

- Markers only appear for entities within the client's entity tracking range (same limitation as JourneyMap's built-in radar)
- `mts:builder_rendering` is a per-player render forwarder that sits at each player's position — if its blue dot is noise, remove its entry from `EntityMarkerTracker.java`
