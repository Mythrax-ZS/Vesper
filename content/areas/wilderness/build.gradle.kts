plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.areaChecker)
    implementation(projects.api.death)
    implementation(projects.api.player)
    implementation(projects.api.registry)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.scriptAdvanced)
    implementation(projects.api.spellsRunes)
    implementation(projects.api.attr)
    implementation(projects.api.dropTable)
    implementation(projects.api.dropTablePlugin)
    implementation(projects.content.interfaces.collectionLog)
    testImplementation(libs.mockk)
    testImplementation(libs.fastutil)
    testImplementation(libs.rsprot.api)
}
