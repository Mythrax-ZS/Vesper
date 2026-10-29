plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.attr)
    implementation(projects.api.dropTable)
    implementation(projects.api.dropTablePlugin)
    implementation(projects.content.quest)
    implementation(projects.content.other.treasureTrails)
    implementation(projects.content.skills.utils)
    testImplementation(libs.mockk)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.api.registry)
}
