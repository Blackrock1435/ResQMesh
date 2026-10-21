package com.resqmesh.app

import android.app.Application
import com.resqmesh.app.data.MeshDatabase
import com.resqmesh.app.location.AppLocationManager
import com.resqmesh.app.mesh.MeshManager
import com.resqmesh.app.security.IdentityManager

/** Manual dependency container (no DI framework: the graph is small and explicit). */
class ResQApp : Application() {
    val idm by lazy { IdentityManager(this) }
    val db by lazy { MeshDatabase.create(this) }
    val mesh by lazy { MeshManager(this, db, idm) }
    val location by lazy { AppLocationManager(this) }
}
