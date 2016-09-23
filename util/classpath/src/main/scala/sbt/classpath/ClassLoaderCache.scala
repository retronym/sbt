package sbt.classpath

import java.lang.ref.{ Reference, SoftReference }
import java.io.File
import java.net.URLClassLoader
import java.util.HashMap

import sbt.classpath

object ClassLoaderCache {
  var instance: ClassLoaderCache = null
}

// Hack for testing only
private[sbt] final class ClassLoaderCache(val commonParent: ClassLoader) {
  private[this] val delegate = new HashMap[List[File], Reference[CachedClassLoader]]

  /**
   * Returns a ClassLoader with `commonParent` as a parent and that will load classes from classpath `files`.
   * The returned ClassLoader may be cached from a previous call if the last modified time of all `files` is unchanged.
   * This method is thread-safe.
   */
  def apply(files: List[File]): ClassLoader = synchronized {
    val tstamps = files.map(_.lastModified)
    getFromReference(files, tstamps, delegate.get(files))
  }

  private var cachedInterfaceLoaders = new HashMap[File, Reference[CachedInterfaceClassLoader]]
  def cachedInterfaceJarLoader(interfaceJar: File, scalaLoader: ClassLoader): ClassLoader = synchronized {
    val sbtLoader = getClass.getClassLoader
    def create = {
      def createDualLoader(scalaLoader: ClassLoader, sbtLoader: ClassLoader): ClassLoader = {
        val xsbtiFilter = (name: String) => name.startsWith("xsbti.")
        val notXsbtiFilter = (name: String) => !xsbtiFilter(name)
        new classpath.DualLoader(scalaLoader, notXsbtiFilter, x => true, sbtLoader, xsbtiFilter, x => false)
      }

      val dual = createDualLoader(scalaLoader, sbtLoader)
      new URLClassLoader(Array(interfaceJar.toURI.toURL), dual)
    }
    val tstamp = interfaceJar.lastModified()

    def newEntry(): ClassLoader =
      {
        val loader = create
        cachedInterfaceLoaders.put(interfaceJar, new SoftReference(new CachedInterfaceClassLoader(loader, interfaceJar, tstamp, scalaLoader)))
        loader
      }

    def get(existing: CachedInterfaceClassLoader): ClassLoader =
      if (existing == null || tstamp != existing.timestamp || (scalaLoader ne existing.scalaLoader)) {
        newEntry()
      } else
        existing.loader
    def getFromReference(existingRef: Reference[CachedInterfaceClassLoader]) =
      if (existingRef eq null)
        newEntry()
      else
        get(existingRef.get)

    getFromReference(cachedInterfaceLoaders.get(interfaceJar))

  }

  private[this] def getFromReference(files: List[File], stamps: List[Long], existingRef: Reference[CachedClassLoader]) =
    if (existingRef eq null)
      newEntry(files, stamps)
    else
      get(files, stamps, existingRef.get)

  private[this] def get(files: List[File], stamps: List[Long], existing: CachedClassLoader): ClassLoader =
    if (existing == null || stamps != existing.timestamps) {
      newEntry(files, stamps)
    } else
      existing.loader

  private[this] def newEntry(files: List[File], stamps: List[Long]): ClassLoader =
    {
      val loader = new URLClassLoader(files.map(_.toURI.toURL).toArray, commonParent)
      delegate.put(files, new SoftReference(new CachedClassLoader(loader, files, stamps)))
      loader
    }
}
private[sbt] final class CachedClassLoader(val loader: ClassLoader, val files: List[File], val timestamps: List[Long])
private[sbt] final class CachedInterfaceClassLoader(val loader: ClassLoader, val interfaceJar: File, val timestamp: Long, val scalaLoader: ClassLoader)

