plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.guice)
    implementation(projects.api.config)
    implementation(projects.api.invtx)
    implementation(projects.api.player)
    implementation(projects.api.playerOutput)
    implementation(projects.api.repo)
    implementation(projects.api.script)
    implementation(projects.api.scriptAdvanced)
    implementation(projects.engine.coroutine)
    implementation(projects.engine.events)
    implementation(projects.engine.game)
    implementation(projects.engine.map)
    implementation(projects.engine.objtx)
    implementation(projects.engine.plugin)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.api.registry)
    testImplementation(projects.engine.annotations)
}
