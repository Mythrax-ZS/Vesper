plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.invtx)
    implementation(projects.api.market)
    implementation(projects.api.player)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.repo)
    implementation(projects.api.script)
    implementation(projects.api.shops)
    implementation(projects.content.interfaces.collectionLog)
    testImplementation(libs.fastutil)
    testImplementation(projects.api.gameProcess)
    testImplementation(projects.api.registry)
    testImplementation(projects.api.route)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.api.objPlugin)
    testImplementation(projects.content.generic.genericItems)
}
