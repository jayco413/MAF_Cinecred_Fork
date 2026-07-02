plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

val twelveMonkeysVersion = "3.13.1"

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.7.0.202606012155-r")
    implementation("com.github.weisj:jsvg:2.1.0")
    // For writing Windows ICO icon files:
    implementation(dependencyFactory.create("com.twelvemonkeys.imageio", "imageio-bmp", twelveMonkeysVersion))
    // For writing macOS ICNS icon files:
    implementation(dependencyFactory.create("com.twelvemonkeys.imageio", "imageio-icns", twelveMonkeysVersion))
}
