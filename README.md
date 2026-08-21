# JourneyMap Entity Bridge

Shows [Immersive Vehicles (MTS)](https://modrinth.com/mod/immersive-vehicles) vehicles as colored markers on [JourneyMap](https://modrinth.com/mod/journeymap) minimap and fullscreen map.

**Forge 1.20.1** | Client-side only | MIT License

## Supported Entities

| Mod | Entity | Marker Color |
|-----|--------|-------------|
| Immersive Vehicles (MTS) | Vehicles (`mts:builder_existing`) | Blue |
| Immersive Vehicles (MTS) | Vehicle seats (`mts:builder_seat`) | Blue |

## How It Works

JourneyMap's built-in entity radar only recognizes vanilla entity types (`IAnimal`, `IMob`, etc.). This mod uses JourneyMap's Plugin API to place `MarkerOverlay` dots at tracked entity positions, updated once per second.

- Entities are matched by registry ID at runtime — **no compile dependency** on MTS
- If JourneyMap is not installed, the mod does nothing (the plugin class is never classloaded)
- If MTS is absent, its entity types are simply never matched

## Installation

Drop the jar into your client's `mods/` folder. Requires:

- Forge 1.20.1 (47.x)
- JourneyMap 5.9.0+ (tested with 5.10.3)

Server-side installation is not needed (but harmless if present).

## Building

```sh
./gradlew build
```

Output jar: `build/libs/jm-entity-bridge-forge-1.20.1-<version>.jar`

Requires JDK 17.

## Notes

- Markers only appear for entities within the client's entity tracking range (same limitation as JourneyMap's built-in radar)
- `mts:builder_rendering` is intentionally excluded — it is a per-player render forwarder that always sits at the player's position, not an actual vehicle
