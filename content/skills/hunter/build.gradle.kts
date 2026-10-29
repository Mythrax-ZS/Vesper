plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.pluginCommons)
    implementation(projects.api.invtx)
    implementation(projects.api.registry)
    implementation(projects.api.utils.utilsSkills)
    testImplementation(libs.mockk)
    testImplementation(libs.fastutil)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.engine.annotations)
    testImplementation(projects.content.other.ironman)
}
