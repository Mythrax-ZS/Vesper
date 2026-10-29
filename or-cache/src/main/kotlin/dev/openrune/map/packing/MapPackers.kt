package dev.openrune.map.packing

import dev.openrune.cache.tools.TaskPriority
import dev.openrune.cache.tools.tasks.CacheTask
import dev.openrune.filesystem.Cache
import java.nio.file.Path

public class MapPackers(private val extraAreaDirectories: List<Path>) : CacheTask() {
    public constructor() : this(emptyList())

    override val priority: TaskPriority
        get() = TaskPriority.MAPS

    override fun init(cache: Cache) {
        MapNpcPacker.encodeCacheMapNpc(cache)
        MapObjPacker.encodeCacheMapObj(cache)
        MapAreaPacker.encodeCacheMapArea(cache, extraAreaDirectories)
    }
}
