plugins {
    id("base-conventions")
    id("game-cache-test-conventions")
}

dependencies {
    implementation(projects.api.attr)
    implementation(projects.api.pluginCommons)
    implementation(projects.api.invtx)
    implementation(projects.api.objCharges)
    implementation(projects.api.specials)
    implementation(projects.engine.utilsBits)
    testImplementation(libs.fastutil)
    testImplementation(libs.mockk)
    testImplementation(projects.api.invStorage)
    testImplementation(projects.api.death)
    testImplementation(projects.api.market)
    testImplementation(projects.api.registry)
    testImplementation(projects.content.interfaces.bank)
}
