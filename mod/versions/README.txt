Building for a different Minecraft version
==========================================

Default: 1.21.11 (nothing to change).

For 26.1.2 / 26.2 / 26.3:
  1. Copy versions/build.gradle.26x over build.gradle
  2. In gradle.properties, replace the four version lines with the ones in
     versions/<your version>/versions.properties
  3. Install a Java 25 JDK (26.x needs Java 25; 1.21.11 needs Java 21)
  4. Run gradlew build as usual

The Java source is the same for every version (Mojang names everywhere).
Version numbers come from the official FabricMC/fabric-example-mod branches.
