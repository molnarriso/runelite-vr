rootProject.name = "vrgpu-plugin"

dependencyResolutionManagement {
    repositories {
        maven(uri("https://repo.runelite.net")) {
            name = "rrn"
            content {
                includeGroupAndSubgroups("net.runelite")
            }
        }
        mavenCentral {
            content { excludeGroupAndSubgroups("net.runelite") }
        }
    }
}
