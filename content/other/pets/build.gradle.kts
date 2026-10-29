plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.bottomlessMilk)
    implementation(projects.api.pluginCommons)
    implementation(projects.content.quest)
    testImplementation(libs.mockk)
    testImplementation(projects.api.dropTable)
    testImplementation(projects.api.dropTablePlugin)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.content.drops)
    testImplementation(projects.content.interfaces.collectionLog)
}
